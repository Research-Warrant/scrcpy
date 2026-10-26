package com.genymobile.scrcpy;

import org.junit.Assert;
import org.junit.Test;

/**
 * Contract §1.2: without aoa, the server parses the provider's lane D command lines exactly as upstream. The lines are the
 * provider's (orka-backend provider/scrcpy/args_test.go), without the "CLASSPATH=... Server" prefix.
 */
public class OptionsUpstreamParityTest {

    private static final String VERSION = BuildConfig.VERSION_NAME;

    private static Options parse(String line) {
        String[] options = line.split(" ");
        String[] args = new String[options.length + 1];
        args[0] = VERSION;
        System.arraycopy(options, 0, args, 1, options.length);
        return Options.parse(args);
    }

    private static void assertLaneD(Options options, boolean video, boolean control, int maxSize, int videoBitRate) {
        Assert.assertFalse(options.isAoa());
        Assert.assertEquals(0, options.getAoaNonce());
        Assert.assertTrue(options.isTunnelForward());
        Assert.assertEquals(video, options.getVideo());
        Assert.assertFalse(options.getAudio());
        Assert.assertEquals(control, options.getControl());
        Assert.assertEquals(0x0001e240, options.getScid());
        Assert.assertEquals(maxSize, options.getMaxSize());
        Assert.assertEquals(videoBitRate, options.getVideoBitRate());
    }

    @Test
    public void testLaneDVideo() {
        Options options = parse("scid=0001e240 tunnel_forward=true audio=false max_size=1280 max_fps=30 "
                + "video_codec_options=i-frame-interval=1 clipboard_autosync=false cleanup=false");
        assertLaneD(options, true, true, 1280, 8000000);
    }

    @Test
    public void testLaneDH265OwnerProfile() {
        Options options = parse("scid=0001e240 tunnel_forward=true audio=false video_codec=h265 video_bit_rate=1000000 max_size=720 "
                + "max_fps=25 video_codec_options=i-frame-interval=1 clipboard_autosync=false cleanup=false");
        assertLaneD(options, true, true, 720, 1000000);
    }

    @Test
    public void testControlOnly() {
        Options options = parse("scid=0001e240 log_level=info tunnel_forward=true video=false audio=false control=true power_on=false "
                + "clipboard_autosync=false cleanup=false");
        assertLaneD(options, false, true, 0, 8000000);
    }

    @Test
    public void testListEncoders() {
        Options options = parse("scid=0001e240 log_level=info list_encoders=true cleanup=false");
        Assert.assertFalse(options.isAoa());
        Assert.assertEquals(0, options.getAoaNonce());
        Assert.assertTrue(options.getList());
    }

    @Test
    public void testLaneE() {
        Options options = parse("scid=0001e240 aoa=0a0b0c0d audio=false video_codec=h265 video_bit_rate=1000000 max_size=720 max_fps=25 "
                + "video_codec_options=i-frame-interval=1 clipboard_autosync=false cleanup=false");
        Assert.assertTrue(options.isAoa());
        Assert.assertEquals(0x0a0b0c0d, options.getAoaNonce());
        Assert.assertFalse(options.isTunnelForward());
    }

    @Test
    public void testListModeStillValidatesAoa() {
        // The real list-mode behaviour behind contract §1.2: aoa is validated, then the list runs
        Options options = parse("scid=00000001 aoa=0a0b0c0d audio=false list_encoders=true");
        Assert.assertTrue(options.isAoa());
        Assert.assertTrue(options.getList());
    }
}
