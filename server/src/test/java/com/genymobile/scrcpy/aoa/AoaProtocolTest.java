package com.genymobile.scrcpy.aoa;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class AoaProtocolTest {

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; ++i) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    @Test
    public void testWriteHeader() {
        byte[] header = new byte[AoaProtocol.HEADER_LENGTH];
        AoaProtocol.writeHeader(header, AoaProtocol.CHANNEL_CONTROL, 0, 0x1234);
        Assert.assertArrayEquals(bytes(0x4f, 0x4b, 0x01, 0x02, 0x00, 0x00, 0x12, 0x34), header);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWriteHeaderPayloadTooLarge() {
        AoaProtocol.writeHeader(new byte[AoaProtocol.HEADER_LENGTH], AoaProtocol.CHANNEL_VIDEO, 0, AoaProtocol.MAX_PAYLOAD + 1);
    }

    @Test
    public void testSizes() {
        Assert.assertEquals(16384, AoaProtocol.MAX_IO);
        Assert.assertEquals(16376, AoaProtocol.MAX_PAYLOAD);
    }

    @Test
    public void testParseValidHeaders() {
        Assert.assertEquals(0, AoaProtocol.parseHeader(bytes(0x4f, 0x4b, 0x01, 0x00, 0x03, 0x00, 0x00, 0x00), 0));
        Assert.assertEquals(16376, AoaProtocol.parseHeader(bytes(0x4f, 0x4b, 0x01, 0x01, 0x00, 0x00, 0x3f, 0xf8), 0));
        Assert.assertEquals(1, AoaProtocol.parseHeader(bytes(0x4f, 0x4b, 0x01, 0x02, 0x00, 0x00, 0x00, 0x01), 0));
        // flags are ignored
        Assert.assertEquals(1, AoaProtocol.parseHeader(bytes(0x4f, 0x4b, 0x01, 0x02, 0x00, 0xff, 0x00, 0x01), 0));
        // at an offset
        Assert.assertEquals(2, AoaProtocol.parseHeader(bytes(0xaa, 0x4f, 0x4b, 0x01, 0x01, 0x00, 0x00, 0x00, 0x02), 1));
    }

    @Test
    public void testParseInvalidHeaders() {
        // bad magic
        Assert.assertEquals(-1, AoaProtocol.parseHeader(bytes(0x4f, 0x41, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00), 0));
        // bad version
        Assert.assertEquals(-1, AoaProtocol.parseHeader(bytes(0x4f, 0x4b, 0x02, 0x00, 0x01, 0x00, 0x00, 0x00), 0));
        // channel 3 (reserved for audio) and above
        Assert.assertEquals(-1, AoaProtocol.parseHeader(bytes(0x4f, 0x4b, 0x01, 0x03, 0x00, 0x00, 0x00, 0x01), 0));
        // payload over the maximum
        Assert.assertEquals(-1, AoaProtocol.parseHeader(bytes(0x4f, 0x4b, 0x01, 0x01, 0x00, 0x00, 0x3f, 0xf9), 0));
        // empty payload on the video and control channels
        Assert.assertEquals(-1, AoaProtocol.parseHeader(bytes(0x4f, 0x4b, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00), 0));
        Assert.assertEquals(-1, AoaProtocol.parseHeader(bytes(0x4f, 0x4b, 0x01, 0x02, 0x00, 0x00, 0x00, 0x00), 0));
    }

    @Test
    public void testHello() {
        byte[] hello = AoaProtocol.hello(0x1a2b3c4d, 34, 1234, AoaProtocol.FEATURE_VIDEO | AoaProtocol.FEATURE_CONTROL, "5.0.1",
                "orka-0001e240");
        byte[] expected = bytes(
                0x4f, 0x4b, 0x01, 0x00, 0x01, 0x00, 0x00, 0x22, // header: session channel, HELLO, 34 bytes
                0x1a, 0x2b, 0x3c, 0x4d, // nonce
                0x00, 0x22, // sdk 34
                0x00, 0x00, 0x04, 0xd2, // pid 1234
                0x00, 0x00, 0x00, 0x03, // features: video, control
                0x05, '5', '.', '0', '.', '1', // server version
                0x0d, 'o', 'r', 'k', 'a', '-', '0', '0', '0', '1', 'e', '2', '4', '0' // accessory serial
        );
        Assert.assertArrayEquals(expected, hello);
    }

    @Test
    public void testHelloTruncatesLongStrings() {
        StringBuilder longSerial = new StringBuilder();
        for (int i = 0; i < 300; ++i) {
            longSerial.append('x');
        }
        byte[] hello = AoaProtocol.hello(1, 33, 1, AoaProtocol.FEATURE_VIDEO, "5.0.1", longSerial.toString());
        Assert.assertEquals(AoaProtocol.HEADER_LENGTH + 4 + 2 + 4 + 4 + 1 + 5 + 1 + 255, hello.length);
        Assert.assertEquals((byte) 255, hello[AoaProtocol.HEADER_LENGTH + 20]);
    }

    @Test
    public void testIsHelloHostFor() {
        int nonce = 0xcafe0001;
        byte[] payload = bytes(0xca, 0xfe, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00);
        Assert.assertTrue(AoaProtocol.isHelloHostFor(nonce, AoaProtocol.CHANNEL_SESSION, AoaProtocol.TYPE_HELLO_HOST, payload, 0, 8));
        // another nonce: meant for an earlier server
        Assert.assertFalse(AoaProtocol.isHelloHostFor(0xcafe0002, AoaProtocol.CHANNEL_SESSION, AoaProtocol.TYPE_HELLO_HOST, payload, 0, 8));
        // wrong type, wrong channel, short payload
        Assert.assertFalse(AoaProtocol.isHelloHostFor(nonce, AoaProtocol.CHANNEL_SESSION, AoaProtocol.TYPE_PING, payload, 0, 8));
        Assert.assertFalse(AoaProtocol.isHelloHostFor(nonce, AoaProtocol.CHANNEL_CONTROL, AoaProtocol.TYPE_HELLO_HOST, payload, 0, 8));
        Assert.assertFalse(AoaProtocol.isHelloHostFor(nonce, AoaProtocol.CHANNEL_SESSION, AoaProtocol.TYPE_HELLO_HOST, payload, 0, 4));
    }

    @Test
    public void testPing() {
        byte[] ping = new byte[AoaProtocol.HEADER_LENGTH + AoaProtocol.PING_LENGTH];
        AoaProtocol.writePing(ping, 7, 0x0102030405060708L);
        Assert.assertArrayEquals(bytes(0x4f, 0x4b, 0x01, 0x00, 0x03, 0x00, 0x00, 0x0c, 0x00, 0x00, 0x00, 0x07, 0x01, 0x02, 0x03, 0x04, 0x05,
                0x06, 0x07, 0x08), ping);
    }

    @Test
    public void testBye() {
        byte[] bye = AoaProtocol.bye(AoaProtocol.BYE_EXITING, "ok");
        Assert.assertArrayEquals(bytes(0x4f, 0x4b, 0x01, 0x00, 0x05, 0x00, 0x00, 0x03, 0x01, 'o', 'k'), bye);
    }

    @Test
    public void testParseNonce() {
        Assert.assertEquals(0x0001e240, AoaProtocol.parseNonce("0001e240"));
        Assert.assertEquals(0xffffffff, AoaProtocol.parseNonce("ffffffff"));
        Assert.assertEquals("0001e240", AoaProtocol.formatNonce(0x0001e240));
        Assert.assertEquals("ffffffff", AoaProtocol.formatNonce(0xffffffff));
    }

    @Test
    public void testParseInvalidNonces() {
        String[] invalid = {"", "1e240", "0001e2400", "0001E240", "0001e24g", "00000000", "-0001e24", "0x01e240"};
        for (String value : invalid) {
            try {
                AoaProtocol.parseNonce(value);
                Assert.fail("Accepted aoa=" + value);
            } catch (AoaException e) {
                Assert.assertEquals(AoaException.BAD_OPTION, e.getExitCode());
            }
        }
    }

    @Test
    public void testExpectedSerial() {
        Assert.assertEquals("orka-0001e240", AoaProtocol.expectedSerial(0x1e240));
        Assert.assertEquals("orka-7fffffff", AoaProtocol.expectedSerial(0x7fffffff));
    }

    @Test
    public void testExceptionMessageAndCodes() {
        AoaException e = new AoaException(AoaException.PERMISSION_DENIED, "openAccessory: java.lang.SecurityException");
        Assert.assertEquals(23, e.getExitCode());
        Assert.assertEquals("aoa: PERMISSION_DENIED: openAccessory: java.lang.SecurityException", e.getMessage());
        Assert.assertEquals("openAccessory: java.lang.SecurityException", e.getDetail());

        String[] reasons = {"SDK_TOO_OLD", "NO_ACCESSORY", "NOT_OUR_ACCESSORY", "PERMISSION_DENIED", "OPEN_FAILED", "NO_HOST", "LINK_LOST",
                "LINK_STALLED", "PROTOCOL", "BAD_OPTION"};
        for (int i = 0; i < reasons.length; ++i) {
            Assert.assertEquals(reasons[i], AoaException.reasonOf(20 + i));
        }
    }

    @Test
    public void testUtf8() {
        // version and serial are UTF-8
        byte[] hello = AoaProtocol.hello(1, 33, 1, AoaProtocol.FEATURE_VIDEO, "é", "");
        byte[] e = "é".getBytes(StandardCharsets.UTF_8);
        Assert.assertEquals(2, hello[AoaProtocol.HEADER_LENGTH + 14]);
        Assert.assertEquals(e[0], hello[AoaProtocol.HEADER_LENGTH + 15]);
        Assert.assertEquals(e[1], hello[AoaProtocol.HEADER_LENGTH + 16]);
        Assert.assertEquals(0, hello[AoaProtocol.HEADER_LENGTH + 17]);
    }
}
