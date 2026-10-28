package dev.otglive.bridge;

import android.graphics.Bitmap;
import android.util.Log;

/** Fast ARM64 image conversion with a safe Java fallback at the call site. */
final class NativeImageProcessor {
    private static final String TAG="OtgLive";
    private static volatile boolean available;
    private static boolean failureLogged;
    static {
        try {
            System.loadLibrary("otglive_image");
            available=true;
            Log.i(TAG,"Native ARM64 image processor loaded");
        } catch(Throwable error) {
            Log.w(TAG,"Native image processor unavailable; Java conversion retained",error);
        }
    }
    private NativeImageProcessor() { }
    static byte[] bitmapToNv21(Bitmap bitmap) {
        if(!available) return null;
        try { return nativeBitmapToNv21(bitmap); }
        catch(Throwable error) {
            available=false;
            if(!failureLogged) {
                failureLogged=true;
                Log.e(TAG,"Native NV21 conversion failed; falling back to Java",error);
            }
            return null;
        }
    }
    static boolean isAvailable() { return available; }
    private static native byte[] nativeBitmapToNv21(Bitmap bitmap);
}
