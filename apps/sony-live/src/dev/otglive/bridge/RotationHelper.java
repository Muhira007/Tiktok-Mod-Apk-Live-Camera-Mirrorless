package dev.otglive.bridge;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.Log;
import java.io.File;
import java.util.Scanner;

public final class RotationHelper {
    private static final String TAG = "OtgLive";
    private static volatile int cachedRotation = 90;
    private static volatile boolean cachedMirror = true;
    private static long lastCheck = 0;

    private static void checkConfig() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 1000) return;
        lastCheck = now;
        try {
            File rf = new File("/sdcard/otglive_rotation.txt");
            if (rf.exists()) {
                try (Scanner s = new Scanner(rf)) {
                    if (s.hasNextInt()) {
                        int val = s.nextInt();
                        if (val != cachedRotation) {
                            cachedRotation = val;
                            Log.i(TAG, "Dynamic rotation changed to: " + cachedRotation);
                        }
                    }
                }
            }
            File mf = new File("/sdcard/otglive_mirror.txt");
            if (mf.exists()) {
                try (Scanner s = new Scanner(mf)) {
                    if (s.hasNextInt()) {
                        boolean val = (s.nextInt() == 1);
                        if (val != cachedMirror) {
                            cachedMirror = val;
                            Log.i(TAG, "Dynamic mirror changed to: " + cachedMirror);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public static Bitmap apply(Bitmap src) {
        checkConfig();
        int deg = cachedRotation;
        boolean mirror = cachedMirror;
        if (deg % 360 == 0 && !mirror) return src;
        Matrix m = new Matrix();
        if (deg % 360 != 0) {
            m.postRotate(deg);
        }
        if (mirror) {
            m.postScale(-1, 1);
        }
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    /** One decoder owns this target. Published frames contain copies, never this bitmap. */
    public static final class Renderer {
        private final Matrix matrix = new Matrix();
        private final RectF bounds = new RectF();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap target;
        private Canvas canvas;
        private int width = -1, height = -1, rotation;
        private boolean mirror;
        public Renderer() { paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC)); }
        public Bitmap apply(Bitmap src) {
            checkConfig();
            int degrees = cachedRotation;
            boolean flip = cachedMirror;
            if (degrees % 360 == 0 && !flip) return src;
            if (target == null || width != src.getWidth() || height != src.getHeight()
                    || rotation != degrees || mirror != flip) {
                close(); width = src.getWidth(); height = src.getHeight(); rotation = degrees; mirror = flip;
                matrix.reset(); matrix.postRotate(degrees);
                if (flip) matrix.postScale(-1, 1);
                bounds.set(0, 0, width, height); matrix.mapRect(bounds);
                matrix.postTranslate(-bounds.left, -bounds.top);
                target = Bitmap.createBitmap(Math.round(bounds.width()), Math.round(bounds.height()), Bitmap.Config.ARGB_8888);
                canvas = new Canvas(target);
            }
            canvas.drawBitmap(src, matrix, paint);
            return target;
        }
        public void close() {
            canvas = null;
            if (target != null) { target.recycle(); target = null; }
        }
    }
}
