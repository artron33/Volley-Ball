package com.siu.android.volleyball.samples.util;

import android.util.Log;

/**
 * Created by lukas on 8/29/13.
 */
public class AppLogger {

    // D
    public static void d(String string) {
        Log.d(AppLogger.class.getName(), string);
    }

    public static void d(String string, Throwable tr) {
        Log.d(AppLogger.class.getName(), string, tr);
    }

    public static void d(String string, Object... args) {
        d(String.format(string, args));
    }

    public static void d(String string, Throwable tr, Object... args) {
        d(String.format(string, args), tr);
    }


    // E
    public static void e(String string) {
        Log.e(AppLogger.class.getName(), string);
    }

    public static void e(String string, Throwable tr) {
        Log.e(AppLogger.class.getName(), string, tr);
    }

    public static void e(String string, Object... args) {
        e(String.format(string, args));
    }

    public static void e(String string, Throwable tr, Object... args) {
        e(String.format(string, args), tr);
    }
}
