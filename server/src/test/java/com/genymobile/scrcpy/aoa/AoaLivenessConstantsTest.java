package com.genymobile.scrcpy.aoa;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;

/**
 * Contract §4.1-§4.2 and §1.3 timings. The constants are private: they are read by reflection so that renaming or changing one
 * fails here, with the contract row it breaks.
 */
public class AoaLivenessConstantsTest {

    private static long constant(Class<?> cls, String name) throws ReflectiveOperationException {
        Field field = cls.getDeclaredField(name);
        field.setAccessible(true);
        return ((Number) field.get(null)).longValue();
    }

    @Test
    public void testLinkTimings() throws ReflectiveOperationException {
        Assert.assertEquals("§4.1 HELLO resend period", 500, constant(AccessoryLink.class, "HELLO_RESEND_MS"));
        Assert.assertEquals("§4.1 handshake timeout", 10_000, constant(AccessoryLink.class, "HANDSHAKE_TIMEOUT_MS"));
        Assert.assertEquals("§4.1 PING interval", 1000, constant(AccessoryLink.class, "PING_INTERVAL_MS"));
        Assert.assertEquals("§4.1 silence timeout", 4_000_000_000L, constant(AccessoryLink.class, "SILENCE_TIMEOUT_NS"));
        Assert.assertEquals("§4.1 blocked write timeout", 4_000_000_000L, constant(AccessoryLink.class, "WRITE_BLOCKED_TIMEOUT_NS"));
        Assert.assertEquals("§4.2 watchdog period", 250, constant(AccessoryLink.class, "WATCHDOG_PERIOD_MS"));
    }

    @Test
    public void testAccessoryWait() throws ReflectiveOperationException {
        Assert.assertEquals("§1.3 accessory wait", 5000, constant(AccessoryOpener.class, "ACCESSORY_WAIT_MS"));
        Assert.assertEquals("§1.3 accessory poll period", 250, constant(AccessoryOpener.class, "ACCESSORY_POLL_MS"));
        Assert.assertEquals("§1.3 minimum SDK (Android 13)", 33, constant(AccessoryOpener.class, "MIN_SDK"));
    }
}
