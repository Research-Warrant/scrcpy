package com.genymobile.scrcpy.aoa;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Contract §3.2-§3.6: the channel byte, the length bounds per channel, unknown session types, and channel 2 as a byte stream.
 */
public class AoaChannelFramingTest {

    private static byte[] header(int channel, int type, int length) {
        return new byte[] {0x4f, 0x4b, 0x01, (byte) channel, (byte) type, 0x00, (byte) (length >> 8), (byte) length};
    }

    @Test
    public void testOnlyChannels0To2AreValid() {
        for (int channel = 0; channel <= 255; ++channel) {
            int expected = channel <= AoaProtocol.CHANNEL_CONTROL ? 1 : -1; // channel 3 is reserved for audio, invalid today
            Assert.assertEquals("channel " + channel, expected, AoaProtocol.parseHeader(header(channel, 0, 1), 0));
        }
    }

    @Test
    public void testLengthBoundsPerChannel() {
        Assert.assertEquals(0, AoaProtocol.parseHeader(header(AoaProtocol.CHANNEL_SESSION, AoaProtocol.TYPE_PONG, 0), 0));
        Assert.assertEquals(16376, AoaProtocol.parseHeader(header(AoaProtocol.CHANNEL_SESSION, AoaProtocol.TYPE_PONG, 16376), 0));
        Assert.assertEquals(-1, AoaProtocol.parseHeader(header(AoaProtocol.CHANNEL_SESSION, AoaProtocol.TYPE_PONG, 16377), 0));
        for (int channel : new int[] {AoaProtocol.CHANNEL_VIDEO, AoaProtocol.CHANNEL_CONTROL}) {
            Assert.assertEquals(-1, AoaProtocol.parseHeader(header(channel, 0, 0), 0));
            Assert.assertEquals(1, AoaProtocol.parseHeader(header(channel, 0, 1), 0));
            Assert.assertEquals(16376, AoaProtocol.parseHeader(header(channel, 0, 16376), 0));
            Assert.assertEquals(-1, AoaProtocol.parseHeader(header(channel, 0, 16377), 0));
        }
    }

    @Test
    public void testUnknownSessionTypePassesTheStrictParser() {
        MessageParser parser = new MessageParser();
        parser.setStrict();
        byte[] msg = AoaProtocol.message(AoaProtocol.CHANNEL_SESSION, 0x7f, new byte[] {1, 2, 3});
        int[] seen = new int[2];
        parser.feed(msg, 0, msg.length, (channel, type, buffer, offset, length) -> {
            seen[0] = type;
            seen[1] = length;
        });
        Assert.assertEquals(0x7f, seen[0]);
        Assert.assertEquals(3, seen[1]);
    }

    @Test
    public void testLargestControlMessageSpansMessages() {
        // The largest SET_CLIPBOARD, header included (ControlMessageReader.MESSAGE_MAX_SIZE)
        byte[] control = new byte[1 << 18];
        for (int i = 0; i < control.length; ++i) {
            control[i] = (byte) (i * 31 + 7);
        }

        // Cut it as AccessoryLink.pumpLoop does when every read() fills a whole payload
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        int messages = 0;
        for (int pos = 0; pos < control.length; pos += AoaProtocol.MAX_PAYLOAD) {
            int length = Math.min(AoaProtocol.MAX_PAYLOAD, control.length - pos);
            byte[] msg = AoaProtocol.message(AoaProtocol.CHANNEL_CONTROL, 0, Arrays.copyOfRange(control, pos, pos + length));
            Assert.assertTrue(msg.length <= AoaProtocol.MAX_IO);
            wire.write(msg, 0, msg.length);
            ++messages;
        }
        Assert.assertEquals(17, messages);

        MessageParser parser = new MessageParser();
        parser.setStrict();
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] stream = wire.toByteArray();
        for (int pos = 0; pos < stream.length; pos += AoaProtocol.MAX_IO) {
            parser.feed(stream, pos, Math.min(AoaProtocol.MAX_IO, stream.length - pos), (channel, type, buffer, offset, length) -> {
                Assert.assertEquals(AoaProtocol.CHANNEL_CONTROL, channel);
                received.write(buffer, offset, length);
            });
        }
        Assert.assertArrayEquals(control, received.toByteArray());
    }
}
