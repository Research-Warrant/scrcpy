package com.genymobile.scrcpy.aoa;

import com.genymobile.scrcpy.Options;

import org.junit.Assert;
import org.junit.Test;

/**
 * Contract §1.3: exit codes 20-29 and their REASON. The option failures that exit 29 are covered where they are parsed: malformed
 * nonces in AoaProtocolTest.testParseInvalidNonces, each validateAoa rule in OptionsAoaTest.testAoaRejectedCombinations.
 */
public class AoaExitCodesTest {

    private static final Object[][] CODES = {
            {AoaException.SDK_TOO_OLD, 20, "SDK_TOO_OLD"},
            {AoaException.NO_ACCESSORY, 21, "NO_ACCESSORY"},
            {AoaException.NOT_OUR_ACCESSORY, 22, "NOT_OUR_ACCESSORY"},
            {AoaException.PERMISSION_DENIED, 23, "PERMISSION_DENIED"},
            {AoaException.OPEN_FAILED, 24, "OPEN_FAILED"},
            {AoaException.NO_HOST, 25, "NO_HOST"},
            {AoaException.LINK_LOST, 26, "LINK_LOST"},
            {AoaException.LINK_STALLED, 27, "LINK_STALLED"},
            {AoaException.PROTOCOL, 28, "PROTOCOL"},
            {AoaException.BAD_OPTION, 29, "BAD_OPTION"},
    };

    @Test
    public void testCodesAndReasons() {
        for (Object[] row : CODES) {
            int code = (Integer) row[0];
            String reason = (String) row[2];
            Assert.assertEquals(reason, row[1], code);
            Assert.assertEquals(reason, AoaException.reasonOf(code));
            AoaException e = new AoaException(code, "detail");
            Assert.assertEquals(code, e.getExitCode());
            Assert.assertEquals("aoa: " + reason + ": detail", e.getMessage());
        }
    }

    @Test
    public void testCodesOutsideTheLaneAreUnknown() {
        for (int code : new int[] {0, 1, 19, 30}) {
            Assert.assertEquals("UNKNOWN", AoaException.reasonOf(code));
        }
    }

    @Test
    public void testUpstreamFailuresStayNonAoa() {
        // Server.main maps any throwable other than AoaException to exit 1
        try {
            Options.parse("4.0", "scid=0001e240", "aoa=0a0b0c0d", "audio=false");
            Assert.fail("Accepted a wrong client version");
        } catch (AoaException e) {
            Assert.fail("A wrong client version is an AOA failure: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            // expected
        }
    }
}
