package com.genymobile.scrcpy.aoa;

import com.genymobile.scrcpy.BuildConfig;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.Ln;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The AOA transport: carries the video socket (channel 1) and the control socket (channel 2) over the USB accessory, instead of
 * the adb tunnel.
 * <p>
 * The rest of the server does not know about it: for each stream, the link creates a local socket pair, gives one end to the server
 * (exactly where a LocalSocket would be used) and pumps the other end over the accessory. The link also runs the handshake (the
 * phone speaks first), a keepalive, and a watchdog. On any failure of the link, the process exits with the matching
 * {@link AoaException} code: a session is never resumed, the host relaunches the server.
 */
public final class AccessoryLink {

    private static final long HELLO_RESEND_MS = 500;
    private static final long HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final long PING_INTERVAL_MS = 1000;
    private static final long WATCHDOG_PERIOD_MS = 250;
    private static final long SILENCE_TIMEOUT_NS = 4_000_000_000L;
    private static final long WRITE_BLOCKED_TIMEOUT_NS = 4_000_000_000L;

    private final ParcelFileDescriptor accessoryFd; // the single open of the accessory, kept for the life of the process
    private final FileInputStream in;
    private final FileOutputStream out;
    private final Object writeLock = new Object();

    private final int nonce;
    private final String serial;

    // Server ends of the socket pairs (given to the Streamer and the ControlChannel) and link ends (pumped by this class)
    private final FileDescriptor videoFd;
    private final FileDescriptor videoLinkFd;
    private final FileDescriptor controlFd; // null without control
    private final FileDescriptor controlLinkFd; // null without control

    private final MessageParser parser = new MessageParser();
    private final CountDownLatch handshakeDone = new CountDownLatch(1);
    private final AtomicBoolean exiting = new AtomicBoolean();
    private volatile boolean closing;
    private volatile boolean connected; // set when the HELLO_HOST with our nonce is received
    private volatile long lastHostByteNs;
    private volatile long writingSinceNs; // when the write() in progress started, 0 when none
    private int staleMessages; // accessed only by the reader thread
    private boolean controlBroken; // accessed only by the reader thread

    private AccessoryLink(AccessoryOpener.Opened accessory, int nonce, boolean control) throws IOException {
        this.accessoryFd = accessory.getFd();
        this.serial = accessory.getSerial();
        this.nonce = nonce;
        // Plain streams, never buffered: a read() of a larger size than a transfer would lose data (f_accessory)
        in = new FileInputStream(accessoryFd.getFileDescriptor());
        out = new FileOutputStream(accessoryFd.getFileDescriptor());

        FileDescriptor[] video = socketPair();
        videoFd = video[0];
        videoLinkFd = video[1];
        if (control) {
            FileDescriptor[] controlPair = socketPair();
            controlFd = controlPair[0];
            controlLinkFd = controlPair[1];
        } else {
            controlFd = null;
            controlLinkFd = null;
        }
    }

    /**
     * Open the accessory and complete the handshake with the host. Returns once the host has answered, with the video and control
     * streams flowing.
     * <p>
     * Failures before the accessory is open throw {@link AoaException}; failures after it exit the process with their code.
     */
    public static AccessoryLink open(int scid, int nonce, boolean control) throws IOException {
        AccessoryOpener.Opened accessory = AccessoryOpener.open(scid);
        AccessoryLink link = new AccessoryLink(accessory, nonce, control);
        link.connect();
        return link;
    }

    /**
     * The server end of the video stream, used like the video socket.
     */
    public FileDescriptor getVideoFd() {
        return videoFd;
    }

    /**
     * The server end of the control stream, used like the control socket, or null without control.
     */
    public FileDescriptor getControlFd() {
        return controlFd;
    }

    /**
     * Unblock the server threads using the streams, like shutting down the sockets.
     */
    public void shutdown() {
        closing = true;
        shutdownQuietly(videoFd);
        if (controlFd != null) {
            shutdownQuietly(controlFd);
        }
    }

    /**
     * Release the accessory at the normal end of the server. Sends a best-effort BYE (a blocked write is still caught by the
     * watchdog).
     */
    public void close() {
        closing = true;
        if (exiting.get()) {
            return;
        }
        byte[] bye = AoaProtocol.bye(AoaProtocol.BYE_EXITING, "server stopping");
        send(bye, bye.length);
        closeQuietly(accessoryFd);
        // The link ends of the socket pairs are left to the process exit: the pumps may still be reading them
        closeQuietly(videoFd);
        if (controlFd != null) {
            closeQuietly(controlFd);
        }
    }

    private void connect() {
        startThread("aoa-reader", this::readLoop);
        startThread("aoa-watchdog", this::watchdogLoop);

        int features = AoaProtocol.FEATURE_VIDEO | (controlFd != null ? AoaProtocol.FEATURE_CONTROL : 0);
        byte[] hello = AoaProtocol.hello(nonce, Build.VERSION.SDK_INT, android.os.Process.myPid(), features, BuildConfig.VERSION_NAME,
                serial);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_TIMEOUT_MS);
        try {
            do {
                send(hello, hello.length);
                if (handshakeDone.await(HELLO_RESEND_MS, TimeUnit.MILLISECONDS)) {
                    break;
                }
            } while (System.nanoTime() < deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!connected) {
            String detail = "no HELLO_HOST with nonce " + AoaProtocol.formatNonce(nonce) + " within " + HANDSHAKE_TIMEOUT_MS + " ms";
            fail(AoaException.NO_HOST, detail);
            // Only reached if another thread is already exiting the process
            throw new AoaException(AoaException.NO_HOST, detail);
        }

        Ln.i("aoa: host connected (dropped " + staleMessages + " stale messages and " + parser.getSkippedBytes() + " stray bytes)");

        startThread("aoa-video", () -> pumpLoop(videoLinkFd, AoaProtocol.CHANNEL_VIDEO));
        if (controlLinkFd != null) {
            startThread("aoa-control", () -> pumpLoop(controlLinkFd, AoaProtocol.CHANNEL_CONTROL));
        }
        startThread("aoa-keepalive", this::keepaliveLoop);
    }

    /**
     * Read 16 KiB at a time and handle the host messages.
     */
    private void readLoop() {
        byte[] buffer = new byte[AoaProtocol.MAX_IO];
        try {
            while (true) {
                int r = in.read(buffer, 0, AoaProtocol.MAX_IO);
                if (r < 0) {
                    fail(AoaException.LINK_LOST, "end of stream on read");
                    return;
                }
                lastHostByteNs = System.nanoTime();
                parser.feed(buffer, 0, r, this::onMessage);
            }
        } catch (AoaException e) {
            fail(e.getExitCode(), e.getDetail());
        } catch (IOException e) {
            fail(AoaException.LINK_LOST, "read: " + e.getMessage());
        }
    }

    private void onMessage(int channel, int type, byte[] buffer, int offset, int length) {
        if (!connected) {
            if (AoaProtocol.isHelloHostFor(nonce, channel, type, buffer, offset, length)) {
                connected = true;
                parser.setStrict();
                handshakeDone.countDown();
            } else {
                // Left by an earlier server (or a HELLO_HOST meant for one)
                ++staleMessages;
            }
            return;
        }

        if (channel == AoaProtocol.CHANNEL_CONTROL) {
            forwardControl(buffer, offset, length);
        } else if (channel == AoaProtocol.CHANNEL_SESSION) {
            onSessionMessage(type, buffer, offset, length);
        }
        // Channel 1 (video) only flows from the phone to the host: ignore it
    }

    private void forwardControl(byte[] buffer, int offset, int length) {
        if (controlLinkFd == null || controlBroken) {
            return;
        }
        try {
            IO.writeFully(controlLinkFd, buffer, offset, length);
        } catch (IOException e) {
            // The controller stopped reading: the server is stopping
            controlBroken = true;
        }
    }

    private void onSessionMessage(int type, byte[] buffer, int offset, int length) {
        switch (type) {
            case AoaProtocol.TYPE_PING:
                byte[] pong = new byte[AoaProtocol.HEADER_LENGTH + length];
                AoaProtocol.writeHeader(pong, AoaProtocol.CHANNEL_SESSION, AoaProtocol.TYPE_PONG, length);
                System.arraycopy(buffer, offset, pong, AoaProtocol.HEADER_LENGTH, length);
                send(pong, pong.length);
                break;
            case AoaProtocol.TYPE_BYE:
                int reason = length > 0 ? buffer[offset] & 0xff : AoaProtocol.BYE_STOP;
                exit(0, "aoa: host sent BYE (reason " + reason + ")", false);
                break;
            default:
                // PONG (any received byte keeps the link alive), repeated HELLO_HOST, unknown types: ignore
                break;
        }
    }

    /**
     * Send what the server writes to one stream, one message per read.
     */
    private void pumpLoop(FileDescriptor from, int channel) {
        FileInputStream source = new FileInputStream(from);
        byte[] msg = new byte[AoaProtocol.MAX_IO];
        while (true) {
            int r;
            try {
                r = source.read(msg, AoaProtocol.HEADER_LENGTH, AoaProtocol.MAX_PAYLOAD);
            } catch (IOException e) {
                return; // the server end was shut down: the server is stopping
            }
            if (r < 0) {
                return;
            }
            AoaProtocol.writeHeader(msg, channel, 0, r);
            if (!send(msg, AoaProtocol.HEADER_LENGTH + r)) {
                return;
            }
        }
    }

    private void keepaliveLoop() {
        byte[] ping = new byte[AoaProtocol.HEADER_LENGTH + AoaProtocol.PING_LENGTH];
        for (int seq = 0; !closing; ++seq) {
            AoaProtocol.writePing(ping, seq, System.nanoTime());
            if (!send(ping, ping.length)) {
                return;
            }
            sleep(PING_INTERVAL_MS);
        }
    }

    /**
     * A write() to the accessory never times out when the host stops reading, and it cannot be interrupted from Java: this thread
     * ends the process instead.
     */
    private void watchdogLoop() {
        while (true) {
            sleep(WATCHDOG_PERIOD_MS);
            long now = System.nanoTime();
            long writingSince = writingSinceNs;
            if (writingSince != 0 && now - writingSince > WRITE_BLOCKED_TIMEOUT_NS) {
                // Even while closing: a blocked BYE must not keep the process alive
                exit(AoaException.LINK_STALLED, AoaException.formatMessage(AoaException.LINK_STALLED,
                        "write() blocked for " + TimeUnit.NANOSECONDS.toMillis(now - writingSince) + " ms"), true);
            }
            if (connected && now - lastHostByteNs > SILENCE_TIMEOUT_NS) {
                fail(AoaException.LINK_LOST, "no byte from the host for " + TimeUnit.NANOSECONDS.toMillis(now - lastHostByteNs) + " ms");
            }
        }
    }

    /**
     * Write one whole message with one write().
     *
     * @return false if the write failed while the server is stopping (otherwise the process exits)
     */
    private boolean send(byte[] msg, int length) {
        synchronized (writeLock) {
            writingSinceNs = System.nanoTime();
            try {
                out.write(msg, 0, length);
                return true;
            } catch (IOException e) {
                fail(AoaException.LINK_LOST, "write: " + e.getMessage());
                return false;
            } finally {
                writingSinceNs = 0;
            }
        }
    }

    /**
     * End the process on a link failure, unless the server is already stopping (then the link going away is expected).
     */
    private void fail(int exitCode, String detail) {
        if (closing) {
            return;
        }
        exit(exitCode, AoaException.formatMessage(exitCode, detail), true);
    }

    private void exit(int exitCode, String message, boolean error) {
        if (!exiting.compareAndSet(false, true)) {
            return;
        }
        if (error) {
            Ln.e(message);
        } else {
            Ln.i(message);
        }
        // Closing the accessory also returns a read() blocked on it
        closeQuietly(accessoryFd);
        System.exit(exitCode);
    }

    private static FileDescriptor[] socketPair() throws IOException {
        FileDescriptor serverEnd = new FileDescriptor();
        FileDescriptor linkEnd = new FileDescriptor();
        try {
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0, serverEnd, linkEnd);
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
        return new FileDescriptor[] {serverEnd, linkEnd};
    }

    private static void startThread(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void shutdownQuietly(FileDescriptor fd) {
        try {
            Os.shutdown(fd, OsConstants.SHUT_RDWR);
        } catch (ErrnoException e) {
            // ignore
        }
    }

    private static void closeQuietly(FileDescriptor fd) {
        try {
            Os.close(fd);
        } catch (ErrnoException e) {
            // ignore
        }
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        try {
            fd.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
