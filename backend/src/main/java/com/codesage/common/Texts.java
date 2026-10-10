package com.codesage.common;

public final class Texts {

    private Texts() {
    }

    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
