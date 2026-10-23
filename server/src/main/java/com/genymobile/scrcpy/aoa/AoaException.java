package com.genymobile.scrcpy.aoa;

/**
 * A failure of the AOA transport ("lane E"), with the process exit code that tells the host why the server stopped.
 * <p>
 * The exit code is authoritative; the message ({@code aoa: <REASON>: <detail>}) is for logs only. Codes 20-29 belong to the
 * AOA transport; upstream keeps 0 and 1.
 */
public final class AoaException extends RuntimeException {

    public static final int SDK_TOO_OLD = 20;
    public static final int NO_ACCESSORY = 21;
    public static final int NOT_OUR_ACCESSORY = 22;
    public static final int PERMISSION_DENIED = 23;
    public static final int OPEN_FAILED = 24;
    public static final int NO_HOST = 25;
    public static final int LINK_LOST = 26;
    public static final int LINK_STALLED = 27;
    public static final int PROTOCOL = 28;
    public static final int BAD_OPTION = 29;

    private final int exitCode;
    private final String detail;

    public AoaException(int exitCode, String detail) {
        this(exitCode, detail, null);
    }

    public AoaException(int exitCode, String detail, Throwable cause) {
        super(formatMessage(exitCode, detail), cause);
        this.exitCode = exitCode;
        this.detail = detail;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getDetail() {
        return detail;
    }

    /**
     * Format the single log line of a failure, without the "[server] ERROR: " prefix added by the logger.
     */
    public static String formatMessage(int exitCode, String detail) {
        return "aoa: " + reasonOf(exitCode) + ": " + detail;
    }

    public static String reasonOf(int exitCode) {
        switch (exitCode) {
            case SDK_TOO_OLD:
                return "SDK_TOO_OLD";
            case NO_ACCESSORY:
                return "NO_ACCESSORY";
            case NOT_OUR_ACCESSORY:
                return "NOT_OUR_ACCESSORY";
            case PERMISSION_DENIED:
                return "PERMISSION_DENIED";
            case OPEN_FAILED:
                return "OPEN_FAILED";
            case NO_HOST:
                return "NO_HOST";
            case LINK_LOST:
                return "LINK_LOST";
            case LINK_STALLED:
                return "LINK_STALLED";
            case PROTOCOL:
                return "PROTOCOL";
            case BAD_OPTION:
                return "BAD_OPTION";
            default:
                return "UNKNOWN";
        }
    }
}
