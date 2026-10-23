package com.genymobile.scrcpy.aoa;

import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.hardware.usb.UsbAccessory;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Opens the USB accessory the host switched the phone into, from the shell uid, through the hidden {@code IUsbManager}.
 * <p>
 * The public {@code UsbManager} cannot be used under {@code app_process} (no package, no permission dialog), so the binder interface
 * is called through reflection. The method signatures differ across Android releases: among the public overloads whose parameters
 * can all be filled ({@code UsbAccessory}, the shell package name, user 0), the one with the fewest parameters is called.
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi,BlockedPrivateApi")
public final class AccessoryOpener {

    public static final int MIN_SDK = 33; // Android 13: Android 12 refuses openAccessory to the shell uid

    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final long ACCESSORY_WAIT_MS = 5000;
    private static final long ACCESSORY_POLL_MS = 250;

    /**
     * The opened accessory. The caller must keep the {@link ParcelFileDescriptor} referenced for the life of the process (it is closed
     * when collected) and must never open the accessory a second time.
     */
    public static final class Opened {
        private final ParcelFileDescriptor fd;
        private final String serial;

        Opened(ParcelFileDescriptor fd, String serial) {
            this.fd = fd;
            this.serial = serial;
        }

        public ParcelFileDescriptor getFd() {
            return fd;
        }

        public String getSerial() {
            return serial;
        }
    }

    private AccessoryOpener() {
        // not instantiable
    }

    /**
     * Wait for the accessory, check that it is the one the host set up for this server ({@code orka-<scid>}) and open it.
     *
     * @throws AoaException with the exit code of the failure (20 to 24)
     */
    public static Opened open(int scid) {
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            throw new AoaException(AoaException.SDK_TOO_OLD, "Android SDK " + Build.VERSION.SDK_INT + " < " + MIN_SDK);
        }

        Object usb = getUsbManager();
        Method getCurrentAccessory = findMethod(usb, "getCurrentAccessory");
        Method openAccessory = findMethod(usb, "openAccessory");

        // Workarounds mark scrcpy's ActivityThread as a system thread. UsbAccessory's constructor then
        // requires its IUsbSerialReader to be a local Stub, which is only true inside system_server, so
        // unmarshalling the accessory from the Binder reply throws IllegalArgumentException. Clear the
        // flag while the accessory is read and opened.
        boolean wasSystem = setSystemThread(false);
        try {
            return openAs(usb, getCurrentAccessory, openAccessory, scid);
        } finally {
            setSystemThread(wasSystem);
        }
    }

    private static Opened openAs(Object usb, Method getCurrentAccessory, Method openAccessory, int scid) {
        UsbAccessory accessory = waitForAccessory(usb, getCurrentAccessory);

        String expectedSerial = AoaProtocol.expectedSerial(scid);
        String serial = readSerial(accessory);
        if (serial == null) {
            throw new AoaException(AoaException.NOT_OUR_ACCESSORY, "accessory serial unreadable");
        }
        if (!serial.equals(expectedSerial)) {
            throw new AoaException(AoaException.NOT_OUR_ACCESSORY, "accessory serial " + serial + ", expected " + expectedSerial);
        }

        ParcelFileDescriptor fd = (ParcelFileDescriptor) invoke(usb, openAccessory, accessory);
        if (fd == null) {
            throw new AoaException(AoaException.OPEN_FAILED, "openAccessory returned null");
        }

        Ln.i("aoa: accessory open (sdk " + Build.VERSION.SDK_INT + ", " + signature(getCurrentAccessory) + ", " + signature(openAccessory)
                + ")");
        return new Opened(fd, serial);
    }

    /**
     * Set ActivityThread.currentActivityThread().mSystemThread and return its previous value (false if there is no
     * ActivityThread or the field cannot be accessed).
     */
    private static boolean setSystemThread(boolean value) {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            if (activityThread == null) {
                return false;
            }
            Field field = activityThreadClass.getDeclaredField("mSystemThread");
            field.setAccessible(true);
            boolean previous = field.getBoolean(activityThread);
            field.setBoolean(activityThread, value);
            return previous;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Ln.w("aoa: could not change ActivityThread.mSystemThread: " + e);
            return false;
        }
    }

    private static Object getUsbManager() {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManager.getMethod("getService", String.class).invoke(null, "usb");
            if (binder == null) {
                throw new AoaException(AoaException.OPEN_FAILED, "no \"usb\" service");
            }
            Class<?> stub = Class.forName("android.hardware.usb.IUsbManager$Stub");
            return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (ReflectiveOperationException e) {
            throw new AoaException(AoaException.OPEN_FAILED, "IUsbManager unavailable: " + unwrap(e), e);
        }
    }

    private static UsbAccessory waitForAccessory(Object usb, Method getCurrentAccessory) {
        // The accessory may be attached a little after the server starts
        long deadline = SystemClock.elapsedRealtime() + ACCESSORY_WAIT_MS;
        while (true) {
            UsbAccessory accessory = (UsbAccessory) invoke(usb, getCurrentAccessory, null);
            if (accessory != null) {
                return accessory;
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw new AoaException(AoaException.NO_ACCESSORY, "no current accessory after " + ACCESSORY_WAIT_MS + " ms");
            }
            SystemClock.sleep(ACCESSORY_POLL_MS);
        }
    }

    private static Method findMethod(Object usb, String name) {
        Method best = null;
        for (Method method : usb.getClass().getMethods()) {
            if (method.getName().equals(name) && isFillable(method)
                    && (best == null || method.getParameterTypes().length < best.getParameterTypes().length)) {
                best = method;
            }
        }
        if (best == null) {
            throw new AoaException(AoaException.OPEN_FAILED, "no usable IUsbManager." + name + " overload");
        }
        return best;
    }

    private static boolean isFillable(Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (type != UsbAccessory.class && type != String.class && type != int.class) {
                return false;
            }
        }
        return true;
    }

    /**
     * Call an IUsbManager method, filling UsbAccessory with the accessory, String with the shell package and int with user 0.
     */
    private static Object invoke(Object usb, Method method, UsbAccessory accessory) {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; ++i) {
            if (types[i] == UsbAccessory.class) {
                args[i] = accessory;
            } else if (types[i] == String.class) {
                args[i] = SHELL_PACKAGE;
            } else {
                args[i] = 0;
            }
        }
        try {
            return method.invoke(usb, args);
        } catch (InvocationTargetException | IllegalAccessException | RuntimeException e) {
            Throwable cause = unwrap(e);
            // The exception may have no message: log its stack so the host log shows where it came from
            Ln.e("aoa: " + method.getName() + " failed", cause);
            if (cause instanceof SecurityException) {
                throw new AoaException(AoaException.PERMISSION_DENIED, method.getName() + ": " + cause, cause);
            }
            throw new AoaException(AoaException.OPEN_FAILED, method.getName() + ": " + cause, cause);
        }
    }

    /**
     * Read the accessory serial. {@code UsbAccessory.getSerial()} passes the caller package, which is null under app_process, so the
     * service refuses it: ask the accessory's {@code IUsbSerialReader} directly with the shell package (uid 2000 holds MANAGE_USB).
     */
    private static String readSerial(UsbAccessory accessory) {
        try {
            Field field = UsbAccessory.class.getDeclaredField("mSerialNumberReader");
            field.setAccessible(true);
            Object reader = field.get(accessory);
            Method getSerial = Class.forName("android.hardware.usb.IUsbSerialReader").getMethod("getSerial", String.class);
            return (String) getSerial.invoke(reader, SHELL_PACKAGE);
        } catch (Throwable t) {
            Ln.w("aoa: IUsbSerialReader.getSerial failed: " + unwrap(t));
        }
        try {
            return accessory.getSerial();
        } catch (Throwable t) {
            Ln.w("aoa: UsbAccessory.getSerial failed: " + t);
            return null;
        }
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
    }

    private static String signature(Method method) {
        StringBuilder builder = new StringBuilder(method.getReturnType().getSimpleName()).append(' ').append(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; ++i) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(types[i].getSimpleName());
        }
        return builder.append(')').toString();
    }
}
