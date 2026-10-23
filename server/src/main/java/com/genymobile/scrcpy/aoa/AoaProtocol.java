package com.genymobile.scrcpy.aoa;

import com.genymobile.scrcpy.util.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Wire format of the AOA transport: every message is an 8-byte header followed by its payload, big-endian, at most
 * {@link #MAX_IO} bytes in total so that it fits in one f_accessory transfer.
 *
 * <pre>
 * offset 0  u16 magic   0x4F4B ("OK")
 * offset 2  u8  version 1
 * offset 3  u8  channel 0 session, 1 video, 2 control
 * offset 4  u8  type    channel 0: message type; channels 1 and 2: 0
 * offset 5  u8  flags   0, ignored by receivers
 * offset 6  u16 length  payload bytes (channels 1 and 2: at least 1)
 * </pre>
 * <p>
 * This class has no Android dependency, so it is covered by plain JVM unit tests.
 */
public final class AoaProtocol {

    /** One f_accessory transfer: a phone read() returns at most this and drops the rest of a larger transfer. */
    public static final int MAX_IO = 16384;
    public static final int HEADER_LENGTH = 8;
    public static final int MAX_PAYLOAD = MAX_IO - HEADER_LENGTH;

    public static final int MAGIC = 0x4F4B;
    public static final int VERSION = 1;

    public static final int CHANNEL_SESSION = 0;
    public static final int CHANNEL_VIDEO = 1;
    public static final int CHANNEL_CONTROL = 2;

    public static final int TYPE_HELLO = 0x01;
    public static final int TYPE_HELLO_HOST = 0x02;
    public static final int TYPE_PING = 0x03;
    public static final int TYPE_PONG = 0x04;
    public static final int TYPE_BYE = 0x05;

    public static final int BYE_STOP = 0;
    public static final int BYE_EXITING = 1;
    public static final int BYE_ERROR = 2;

    public static final int FEATURE_VIDEO = 1;
    public static final int FEATURE_CONTROL = 1 << 1;

    public static final int PING_LENGTH = 12; // u32 seq | u64 sender clock
    public static final int HELLO_HOST_LENGTH = 8; // u32 nonce | u32 features

    private static final int MAX_STRING_LENGTH = 255;

    private AoaProtocol() {
        // not instantiable
    }

    /**
     * Write the header of a message into {@code buffer[0..8)}.
     */
    public static void writeHeader(byte[] buffer, int channel, int type, int payloadLength) {
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Invalid payload length: " + payloadLength);
        }
        buffer[0] = (byte) (MAGIC >> 8);
        buffer[1] = (byte) MAGIC;
        buffer[2] = (byte) VERSION;
        buffer[3] = (byte) channel;
        buffer[4] = (byte) type;
        buffer[5] = 0; // flags
        buffer[6] = (byte) (payloadLength >> 8);
        buffer[7] = (byte) payloadLength;
    }

    /**
     * Build a whole message (header and payload).
     */
    public static byte[] message(int channel, int type, byte[] payload) {
        byte[] msg = new byte[HEADER_LENGTH + payload.length];
        writeHeader(msg, channel, type, payload.length);
        System.arraycopy(payload, 0, msg, HEADER_LENGTH, payload.length);
        return msg;
    }

    /**
     * Parse the header at {@code buffer[offset..offset+8)}.
     *
     * @return the payload length if the header is valid (magic, version, channel and length all check), -1 otherwise
     */
    public static int parseHeader(byte[] buffer, int offset) {
        int magic = (buffer[offset] & 0xff) << 8 | (buffer[offset + 1] & 0xff);
        int version = buffer[offset + 2] & 0xff;
        int channel = buffer[offset + 3] & 0xff;
        int length = (buffer[offset + 6] & 0xff) << 8 | (buffer[offset + 7] & 0xff);
        if (magic != MAGIC || version != VERSION || channel > CHANNEL_CONTROL || length > MAX_PAYLOAD) {
            return -1;
        }
        if (channel != CHANNEL_SESSION && length == 0) {
            return -1;
        }
        return length;
    }

    public static int channelOf(byte[] header, int offset) {
        return header[offset + 3] & 0xff;
    }

    public static int typeOf(byte[] header, int offset) {
        return header[offset + 4] & 0xff;
    }

    /**
     * Build the HELLO message, the first message the phone sends.
     * <p>
     * Payload: u32 nonce | u16 sdk | u32 pid | u32 features | u8 length + server version (UTF-8) | u8 length + accessory serial (UTF-8).
     */
    public static byte[] hello(int nonce, int sdk, int pid, int features, String serverVersion, String serial) {
        byte[] version = truncatedUtf8(serverVersion);
        byte[] serialBytes = truncatedUtf8(serial);
        ByteBuffer payload = ByteBuffer.allocate(4 + 2 + 4 + 4 + 1 + version.length + 1 + serialBytes.length);
        payload.putInt(nonce);
        payload.putShort((short) sdk);
        payload.putInt(pid);
        payload.putInt(features);
        payload.put((byte) version.length).put(version);
        payload.put((byte) serialBytes.length).put(serialBytes);
        return message(CHANNEL_SESSION, TYPE_HELLO, payload.array());
    }

    /**
     * Tell whether a message is the HELLO_HOST that answers our HELLO (same nonce). HELLO_HOSTs with another nonce were meant for
     * an earlier server.
     */
    public static boolean isHelloHostFor(int nonce, int channel, int type, byte[] buffer, int offset, int length) {
        return channel == CHANNEL_SESSION && type == TYPE_HELLO_HOST && length >= HELLO_HOST_LENGTH
                && readU32(buffer, offset) == nonce;
    }

    /**
     * Build a PING message into {@code buffer[0..HEADER_LENGTH+PING_LENGTH)}.
     */
    public static void writePing(byte[] buffer, int seq, long clockNs) {
        writeHeader(buffer, CHANNEL_SESSION, TYPE_PING, PING_LENGTH);
        ByteBuffer.wrap(buffer, HEADER_LENGTH, PING_LENGTH).putInt(seq).putLong(clockNs);
    }

    public static byte[] bye(int reason, String text) {
        byte[] textBytes = truncatedUtf8(text);
        byte[] payload = new byte[1 + textBytes.length];
        payload[0] = (byte) reason;
        System.arraycopy(textBytes, 0, payload, 1, textBytes.length);
        return message(CHANNEL_SESSION, TYPE_BYE, payload);
    }

    /**
     * Parse the value of the {@code aoa} server option: exactly 8 lowercase hex digits, not {@code 00000000}.
     */
    public static int parseNonce(String value) {
        if (value.length() != 8) {
            throw new AoaException(AoaException.BAD_OPTION, "aoa must be 8 lowercase hex digits: \"" + value + "\"");
        }
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                throw new AoaException(AoaException.BAD_OPTION, "aoa must be 8 lowercase hex digits: \"" + value + "\"");
            }
        }
        int nonce = (int) Long.parseLong(value, 16);
        if (nonce == 0) {
            throw new AoaException(AoaException.BAD_OPTION, "aoa must not be 00000000");
        }
        return nonce;
    }

    public static String formatNonce(int nonce) {
        return String.format(Locale.ROOT, "%08x", nonce);
    }

    /**
     * The serial the host gives the accessory: {@code orka-<scid as 8 hex digits>}.
     */
    public static String expectedSerial(int scid) {
        return "orka-" + String.format(Locale.ROOT, "%08x", scid);
    }

    static int readU32(byte[] buffer, int offset) {
        return ByteBuffer.wrap(buffer, offset, 4).getInt();
    }

    private static byte[] truncatedUtf8(String s) {
        byte[] bytes = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        int length = StringUtils.getUtf8TruncationIndex(bytes, MAX_STRING_LENGTH);
        if (length == bytes.length) {
            return bytes;
        }
        byte[] truncated = new byte[length];
        System.arraycopy(bytes, 0, truncated, 0, length);
        return truncated;
    }
}
