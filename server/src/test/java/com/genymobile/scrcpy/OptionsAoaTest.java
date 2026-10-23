package com.genymobile.scrcpy;

import com.genymobile.scrcpy.aoa.AoaException;

import org.junit.Assert;
import org.junit.Test;

public class OptionsAoaTest {

    private static final String VERSION = BuildConfig.VERSION_NAME;

    private static void assertBadOption(String... args) {
        try {
            Options.parse(args);
            Assert.fail("Accepted " + String.join(" ", args));
        } catch (AoaException e) {
            Assert.assertEquals(AoaException.BAD_OPTION, e.getExitCode());
        }
    }

    @Test
    public void testWithoutAoa() {
        Options options = Options.parse(VERSION, "scid=0001e240", "tunnel_forward=true", "audio=false");
        Assert.assertFalse(options.isAoa());
        Assert.assertTrue(options.isTunnelForward());
    }

    @Test
    public void testAoa() {
        Options options = Options.parse(VERSION, "scid=0001e240", "aoa=8badf00d", "audio=false", "video_codec=h265", "max_size=720",
                "clipboard_autosync=false", "cleanup=false");
        Assert.assertTrue(options.isAoa());
        Assert.assertEquals(0x8badf00d, options.getAoaNonce());
        Assert.assertEquals(0x0001e240, options.getScid());
        Assert.assertTrue(options.getVideo());
        Assert.assertTrue(options.getControl());
        Assert.assertFalse(options.isTunnelForward());
    }

    @Test
    public void testAoaWithoutControl() {
        Options options = Options.parse(VERSION, "scid=1", "aoa=00000001", "audio=false", "control=false", "tunnel_forward=false");
        Assert.assertTrue(options.isAoa());
        Assert.assertFalse(options.getControl());
    }

    @Test
    public void testAoaRejectedCombinations() {
        assertBadOption(VERSION, "aoa=8badf00d", "audio=false"); // no scid
        assertBadOption(VERSION, "scid=-1", "aoa=8badf00d", "audio=false");
        assertBadOption(VERSION, "scid=1", "aoa=8badf00d"); // audio defaults to true
        assertBadOption(VERSION, "scid=1", "aoa=8badf00d", "audio=true");
        assertBadOption(VERSION, "scid=1", "aoa=8badf00d", "audio=false", "tunnel_forward=true");
        assertBadOption(VERSION, "scid=1", "aoa=8badf00d", "audio=false", "video=false");
    }

    @Test
    public void testAoaMalformed() {
        assertBadOption(VERSION, "scid=1", "aoa=00000000", "audio=false");
        assertBadOption(VERSION, "scid=1", "aoa=8BADF00D", "audio=false");
        assertBadOption(VERSION, "scid=1", "aoa=8badf0", "audio=false");
        assertBadOption(VERSION, "scid=1", "aoa=", "audio=false");
    }
}
