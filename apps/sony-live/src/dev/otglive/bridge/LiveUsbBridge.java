package dev.otglive.bridge;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;

/** Entry points called by the patched LIVE camera wrapper in TikTok 47.5.3. */
public final class LiveUsbBridge {
    private static final String TAG="OtgLive";
    private static final IdentityHashMap<Object,Session> sessions=new IdentityHashMap<>();
    private static final Handler main=new Handler(Looper.getMainLooper());

    private static Object error(Object capture,String code) {
        try { return capture.getClass().getClassLoader().loadClass("com.ss.lyrax.LyraxErrorCode").getField(code).get(null); }
        catch(Exception e) { throw new IllegalStateException("Lyrax ABI mismatch",e); }
    }
    private static Context context() throws Exception {
        Class<?> thread=Class.forName("android.app.ActivityThread");
        Method current=thread.getDeclaredMethod("currentApplication");
        current.setAccessible(true);
        Application app=(Application)current.invoke(null);
        if(app==null) throw new IllegalStateException("Application belum tersedia");
        return app;
    }
    public static synchronized Object start(Object capture) {
        if(sessions.containsKey(capture)) return error(capture,"SUCCESS");
        // One physical UVC stream. Stop a previous LIVE wrapper before claiming it again.
        for(Session previous:sessions.values()) previous.camera.close();
        sessions.clear();
        try {
            Context app=context();
            Session session=new Session(app,capture);
            sessions.put(capture,session);
            session.camera.start();
            Log.i(TAG,"Patched LyraxCameraCapture.startPlatformCapture: Sony UVC source");
            return error(capture,"SUCCESS");
        } catch(Exception e) {
            sessions.remove(capture);
            Log.e(TAG,"Cannot start Sony LIVE source",e);
            return error(capture,"FAILURE");
        }
    }
    public static synchronized Object stop(Object capture) {
        Session session=sessions.remove(capture);
        if(session!=null) session.camera.close();
        setState(capture,"STOPPED");
        Log.i(TAG,"Patched LIVE USB capture stopped");
        return error(capture,"SUCCESS");
    }
    public static Object keepSony(Object capture) {
        // Prevent the original front/back selector from opening the phone camera.
        Log.i(TAG,"LIVE source remains Sony USB");
        return error(capture,"SUCCESS");
    }
    public static Object configure(Object capture,Object config) {
        if(config==null) return error(capture,"INVALID_PARAMS");
        // Source is fixed at the Sony's negotiated size. Output scaling stays in Lyrax.
        return error(capture,"SUCCESS");
    }
    private static void setState(Object capture,String value) {
        try {
            Class<?> state=capture.getClass().getClassLoader().loadClass("com.ss.lyrax.video.LyraxVideoSourceState");
            capture.getClass().getField("state").set(capture,state.getField(value).get(null));
        } catch(Exception e) { Log.w(TAG,"Cannot update LIVE source state",e); }
    }
    private static final class Session implements UvcCamera.Listener {
        final Object capture;
        final Context app;
        final UvcCamera camera;
        final Method makeFrame,push,timestamp,release;
        long delivered;
        Session(Context app,Object capture) throws Exception {
            this.app=app; this.capture=capture;
            ClassLoader loader=capture.getClass().getClassLoader();
            Class<?> frame=loader.loadClass("com.ss.lyrax.video.LyraxVideoFrame");
            Class<?> wrapper=loader.loadClass("com.ss.lyrax.video.AbsLyraxVideoPlatformWrapper");
            makeFrame=wrapper.getMethod("buildFrameFromBitmap",Bitmap.class);
            push=capture.getClass().getMethod("pushVideoFrame",frame);
            timestamp=frame.getMethod("setTimeStampUs",long.class);
            release=frame.getMethod("release");
            camera=new UvcCamera(app,this);
        }
        public void status(String message) {
            Log.i(TAG,"LIVE USB: "+message);
            if(message.startsWith("ERROR") || message.startsWith("ZV-1 MJPEG"))
                main.post(() -> Toast.makeText(app,message,Toast.LENGTH_LONG).show());
        }
        public void jpeg(byte[] jpeg,long timestampUs) {
            Bitmap bitmap=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);
            if(bitmap==null) return;
            Bitmap rotated=RotationHelper.apply(bitmap);
            Object frame=null;
            try {
                frame=makeFrame.invoke(null,rotated);
                if(frame==null) throw new IllegalStateException("RGBA frame builder returned null");
                timestamp.invoke(frame,timestampUs);
                Object result=push.invoke(capture,frame);
                delivered++;
                if(delivered==1 || delivered%150==0)
                    Log.i(TAG,"Sony -> Lyrax pushVideoFrame count="+delivered+" result="+result);
            } catch(Exception e) {
                Log.e(TAG,"Sony frame rejected by LIVE pipeline",e);
                throw new IllegalStateException(e);
            } finally {
                if(frame!=null) try { release.invoke(frame); } catch(Exception e) { Log.w(TAG,"Frame release failed",e); }
                if(rotated!=bitmap) rotated.recycle();
                bitmap.recycle();
            }
        }
    }
}
