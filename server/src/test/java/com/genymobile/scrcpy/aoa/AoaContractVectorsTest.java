package com.genymobile.scrcpy.aoa;

import org.junit.Assert;
import org.junit.Test;

/**
 * The vectors of doc/orka/aoa-lane-contract.md Appendix A, one test per row. orka-backend asserts the same rows in
 * TestWireMatchesTheForkVectors: change both sides, and the contract, together.
 */
public class AoaContractVectorsTest {

    /** Parse hex digits, ignoring spaces, so that each vector reads as its Appendix A row. */
    private static byte[] hex(String s) {
        String digits = s.replace(" ", "");
        byte[] result = new byte[digits.length() / 2];
        for (int i = 0; i < result.length; ++i) {
            result[i] = (byte) Integer.parseInt(digits.substring(2 * i, 2 * i + 2), 16);
        }
        return result;
    }

    @Test
    public void testVideoMessage() {
        Assert.assertArrayEquals(hex("4f4b0101 00000001 aa"), AoaProtocol.message(AoaProtocol.CHANNEL_VIDEO, 0, hex("aa")));
    }

    @Test
    public void testHelloHost() {
        byte[] msg = AoaProtocol.message(AoaProtocol.CHANNEL_SESSION, AoaProtocol.TYPE_HELLO_HOST, hex("01020304 00000000"));
        Assert.assertArrayEquals(hex("4f4b0100 02000008 01020304 00000000"), msg);
        Assert.assertTrue(AoaProtocol.isHelloHostFor(0x01020304, AoaProtocol.CHANNEL_SESSION, AoaProtocol.TYPE_HELLO_HOST, msg,
                AoaProtocol.HEADER_LENGTH, AoaProtocol.HELLO_HOST_LENGTH));
    }

    @Test
    public void testHello() {
        byte[] msg = AoaProtocol.hello(0x01020304, 34, 0x0a0b0c0d, 3, "5.0.1", "orka-0001e240");
        // nonce | sdk | pid | features | 05 "5.0.1" | 0d "orka-0001e240"
        byte[] expected = hex("4f4b0100 01000022" + "01020304 0022 0a0b0c0d 00000003" + "05 352e302e31" + "0d 6f726b61 2d 3030303165323430");
        Assert.assertArrayEquals(expected, msg);
    }

    @Test
    public void testPongHeader() {
        byte[] header = new byte[AoaProtocol.HEADER_LENGTH];
        AoaProtocol.writeHeader(header, AoaProtocol.CHANNEL_SESSION, AoaProtocol.TYPE_PONG, 12);
        Assert.assertArrayEquals(hex("4f4b0100 0400000c"), header);
    }

    @Test
    public void testByeStop() {
        Assert.assertArrayEquals(hex("4f4b0100 05000002 0078"), AoaProtocol.bye(AoaProtocol.BYE_STOP, "x"));
    }

    @Test
    public void testSizes() {
        Assert.assertEquals(16384, AoaProtocol.MAX_IO);
        Assert.assertEquals(8, AoaProtocol.HEADER_LENGTH);
        Assert.assertEquals(16376, AoaProtocol.MAX_PAYLOAD);
    }
}
