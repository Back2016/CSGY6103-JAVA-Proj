package edu.nyu.cs6103.p2p.common;

public final class ThrowableUtils {
    private ThrowableUtils() {
    }

    public static String rootCauseMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
