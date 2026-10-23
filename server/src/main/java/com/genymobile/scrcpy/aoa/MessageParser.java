package com.genymobile.scrcpy.aoa;

/**
 * Reassembles AOA transport messages from the bytes the phone reads from the accessory.
 * <p>
 * Before the handshake, an invalid header means "skip one byte and look again": the host side may still hold bytes queued for an
 * earlier server. Once {@link #setStrict()} has been called (on the HELLO_HOST that answers our HELLO), an invalid header is a
 * protocol error.
 * <p>
 * This class has no Android dependency, so it is covered by plain JVM unit tests.
 */
public final class MessageParser {

    public interface Handler {
        /**
         * Handle one whole message. The payload is only valid during the call.
         */
        void onMessage(int channel, int type, byte[] buffer, int offset, int length);
    }

    // A feed is at most MAX_IO bytes and what is left after parsing is less than one message, so 2 * MAX_IO always fits
    private final byte[] buffer = new byte[2 * AoaProtocol.MAX_IO];
    private int size;
    private boolean strict;
    private long skippedBytes;

    /**
     * From now on, reject invalid headers instead of resynchronizing. Takes effect for the next message, even within the
     * current {@link #feed(byte[], int, int, Handler)} call.
     */
    public void setStrict() {
        strict = true;
    }

    /**
     * Number of bytes skipped while resynchronizing (before the handshake).
     */
    public long getSkippedBytes() {
        return skippedBytes;
    }

    /**
     * Append {@code data[offset..offset+length)} (at most {@link AoaProtocol#MAX_IO} bytes) and handle every whole message.
     *
     * @throws AoaException with {@link AoaException#PROTOCOL} on an invalid header in strict mode
     */
    public void feed(byte[] data, int offset, int length, Handler handler) {
        if (length > AoaProtocol.MAX_IO) {
            throw new IllegalArgumentException("Feed too large: " + length);
        }
        System.arraycopy(data, offset, buffer, size, length);
        size += length;

        int pos = 0;
        while (size - pos >= AoaProtocol.HEADER_LENGTH) {
            int payloadLength = AoaProtocol.parseHeader(buffer, pos);
            if (payloadLength < 0) {
                if (strict) {
                    throw new AoaException(AoaException.PROTOCOL, "invalid header " + hex(buffer, pos, AoaProtocol.HEADER_LENGTH));
                }
                ++pos;
                ++skippedBytes;
                continue;
            }
            int messageLength = AoaProtocol.HEADER_LENGTH + payloadLength;
            if (size - pos < messageLength) {
                break; // wait for the rest of the message
            }
            int channel = AoaProtocol.channelOf(buffer, pos);
            int type = AoaProtocol.typeOf(buffer, pos);
            handler.onMessage(channel, type, buffer, pos + AoaProtocol.HEADER_LENGTH, payloadLength);
            pos += messageLength;
        }

        System.arraycopy(buffer, pos, buffer, 0, size - pos);
        size -= pos;
    }

    private static String hex(byte[] data, int offset, int length) {
        StringBuilder builder = new StringBuilder(length * 2);
        for (int i = offset; i < offset + length; ++i) {
            builder.append(Character.forDigit((data[i] >> 4) & 0xf, 16)).append(Character.forDigit(data[i] & 0xf, 16));
        }
        return builder.toString();
    }
}
