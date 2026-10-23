package com.genymobile.scrcpy.aoa;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MessageParserTest {

    private static final class Message {
        private final int channel;
        private final int type;
        private final byte[] payload;

        Message(int channel, int type, byte[] payload) {
            this.channel = channel;
            this.type = type;
            this.payload = payload;
        }
    }

    /**
     * Collects messages and, like the link, switches the parser to strict on the HELLO_HOST with its nonce.
     */
    private static final class Collector implements MessageParser.Handler {
        private final MessageParser parser;
        private final int nonce;
        private final List<Message> messages = new ArrayList<>();
        private int stale;
        private boolean connected;

        Collector(MessageParser parser, int nonce) {
            this.parser = parser;
            this.nonce = nonce;
        }

        @Override
        public void onMessage(int channel, int type, byte[] buffer, int offset, int length) {
            if (!connected) {
                if (AoaProtocol.isHelloHostFor(nonce, channel, type, buffer, offset, length)) {
                    connected = true;
                    parser.setStrict();
                } else {
                    ++stale;
                }
                return;
            }
            messages.add(new Message(channel, type, Arrays.copyOfRange(buffer, offset, offset + length)));
        }
    }

    private static byte[] helloHost(int nonce) {
        return AoaProtocol.message(AoaProtocol.CHANNEL_SESSION, AoaProtocol.TYPE_HELLO_HOST,
                ByteBuffer.allocate(AoaProtocol.HELLO_HOST_LENGTH).putInt(nonce).putInt(0).array());
    }

    private static byte[] payload(int length, int seed) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; ++i) {
            data[i] = (byte) (seed + i);
        }
        return data;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    /**
     * Feed data in reads of at most chunk bytes, like the reader thread.
     */
    private static void feed(MessageParser parser, MessageParser.Handler handler, byte[] data, int chunk) {
        for (int off = 0; off < data.length; off += chunk) {
            parser.feed(data, off, Math.min(chunk, data.length - off), handler);
        }
    }

    @Test
    public void testReassemblyAcrossReads() {
        int nonce = 0x11223344;
        byte[] control = payload(AoaProtocol.MAX_PAYLOAD, 1);
        byte[] small = payload(3, 2);
        byte[] stream = concat(helloHost(nonce), AoaProtocol.message(AoaProtocol.CHANNEL_CONTROL, 0, control),
                AoaProtocol.message(AoaProtocol.CHANNEL_CONTROL, 0, small));

        // Every read size, from 1 byte to a whole transfer, gives the same messages
        for (int chunk : new int[] {1, 7, 8, 9, 1000, AoaProtocol.MAX_IO}) {
            MessageParser parser = new MessageParser();
            Collector collector = new Collector(parser, nonce);
            feed(parser, collector, stream, chunk);
            Assert.assertTrue(collector.connected);
            Assert.assertEquals(2, collector.messages.size());
            Assert.assertEquals(AoaProtocol.CHANNEL_CONTROL, collector.messages.get(0).channel);
            Assert.assertArrayEquals(control, collector.messages.get(0).payload);
            Assert.assertArrayEquals(small, collector.messages.get(1).payload);
        }
    }

    @Test
    public void testSeveralMessagesInOneRead() {
        int nonce = 0x0badf00d;
        byte[] ping = new byte[AoaProtocol.HEADER_LENGTH + AoaProtocol.PING_LENGTH];
        AoaProtocol.writePing(ping, 1, 42);
        byte[] stream = concat(helloHost(nonce), ping, AoaProtocol.message(AoaProtocol.CHANNEL_CONTROL, 0, payload(10, 3)),
                AoaProtocol.bye(AoaProtocol.BYE_STOP, ""));

        MessageParser parser = new MessageParser();
        Collector collector = new Collector(parser, nonce);
        parser.feed(stream, 0, stream.length, collector);

        Assert.assertEquals(3, collector.messages.size());
        Assert.assertEquals(AoaProtocol.TYPE_PING, collector.messages.get(0).type);
        Assert.assertEquals(AoaProtocol.PING_LENGTH, collector.messages.get(0).payload.length);
        Assert.assertEquals(AoaProtocol.CHANNEL_CONTROL, collector.messages.get(1).channel);
        Assert.assertEquals(AoaProtocol.TYPE_BYE, collector.messages.get(2).type);
        Assert.assertArrayEquals(new byte[] {AoaProtocol.BYE_STOP}, collector.messages.get(2).payload);
    }

    @Test
    public void testStaleBytesBeforeHandshakeAreDropped() {
        int nonce = 0x5eed0001;
        byte[] garbage = {0x4f, 0x4b, 0x7f, 0x00, 0x12, 0x34, 0x4f};
        byte[] stream = concat(
                garbage,
                // messages an earlier server never read: a PING, control data and the HELLO_HOST meant for it
                AoaProtocol.message(AoaProtocol.CHANNEL_SESSION, AoaProtocol.TYPE_PING, payload(AoaProtocol.PING_LENGTH, 0)),
                AoaProtocol.message(AoaProtocol.CHANNEL_CONTROL, 0, payload(100, 4)),
                helloHost(0x5eed0000),
                helloHost(nonce),
                AoaProtocol.message(AoaProtocol.CHANNEL_CONTROL, 0, payload(5, 5)));

        MessageParser parser = new MessageParser();
        Collector collector = new Collector(parser, nonce);
        feed(parser, collector, stream, 13);

        Assert.assertTrue(collector.connected);
        Assert.assertEquals(3, collector.stale);
        Assert.assertEquals(garbage.length, parser.getSkippedBytes());
        Assert.assertEquals(1, collector.messages.size());
        Assert.assertArrayEquals(payload(5, 5), collector.messages.get(0).payload);
    }

    @Test
    public void testInvalidHeaderAfterHandshakeIsAProtocolError() {
        int nonce = 0x00c0ffee;
        MessageParser parser = new MessageParser();
        Collector collector = new Collector(parser, nonce);
        byte[] hello = helloHost(nonce);
        parser.feed(hello, 0, hello.length, collector);

        byte[] bad = {0x4f, 0x4b, 0x01, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00}; // channel 3
        try {
            parser.feed(bad, 0, bad.length, collector);
            Assert.fail("Accepted an invalid header after the handshake");
        } catch (AoaException e) {
            Assert.assertEquals(AoaException.PROTOCOL, e.getExitCode());
            Assert.assertEquals("invalid header 4f4b010300000001", e.getDetail());
        }
    }

    @Test
    public void testStrictModeAppliesWithinTheSameRead() {
        int nonce = 0x00000001;
        byte[] stream = concat(helloHost(nonce), new byte[] {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        MessageParser parser = new MessageParser();
        Collector collector = new Collector(parser, nonce);
        try {
            parser.feed(stream, 0, stream.length, collector);
            Assert.fail("Resynchronized after the handshake");
        } catch (AoaException e) {
            Assert.assertEquals(AoaException.PROTOCOL, e.getExitCode());
        }
        Assert.assertTrue(collector.connected);
    }

    @Test
    public void testPartialHeaderWaits() {
        int nonce = 0x12345678;
        byte[] hello = helloHost(nonce);
        MessageParser parser = new MessageParser();
        Collector collector = new Collector(parser, nonce);
        parser.feed(hello, 0, 5, collector);
        Assert.assertFalse(collector.connected);
        Assert.assertEquals(0, parser.getSkippedBytes());
        parser.feed(hello, 5, hello.length - 5, collector);
        Assert.assertTrue(collector.connected);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFeedTooLarge() {
        MessageParser parser = new MessageParser();
        parser.feed(new byte[AoaProtocol.MAX_IO + 1], 0, AoaProtocol.MAX_IO + 1, (channel, type, buffer, offset, length) -> { });
    }
}
