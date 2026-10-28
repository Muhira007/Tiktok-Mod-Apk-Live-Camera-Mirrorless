package dev.otglive.bridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.*;
import java.nio.ByteBuffer;

/** Share one Sony UVC source between the LIVE preparation UI and the real encoder. */
public final class VePreviewUsbBridge {
    private static final String TAG="OtgLive";
    private static volatile boolean previewEnabled;
    private static volatile boolean liveEnabled;
    private static volatile Image latest;
    private static UvcCamera camera;
    private static volatile DecodeSession decoder;
    private static volatile boolean legacyNeedsNv21;
    private static Constructor<?> constructor;
    private static Method initBuffer,setDepend,getTimestamp;
    private static Object pixelFormat;
    private static Constructor<?> liveBufferConstructor;
    private static ClassLoader liveBufferLoader;
    private static long replaced,liveReplaced;
    private static final long HANDOFF_MS=15000;
    private static final long LIVE_IDLE_MS=4000;
    private static volatile long handoffUntil,lastLiveFrame;
    private static boolean monitorRunning;
    static final class Image {
        final byte[] nv21,rgba; final int width,height;
        final long sequence,captureNs;
        Image(byte[] nv21,byte[] rgba,int width,int height,long sequence,long captureNs) {
            this.nv21=nv21; this.rgba=rgba; this.width=width; this.height=height;
            this.sequence=sequence; this.captureNs=captureNs;
        }
    }
    private static final class Encoded {
        final byte[] jpeg;
        final long timestampUs;
        Encoded(byte[] jpeg,long timestampUs) { this.jpeg=jpeg; this.timestampUs=timestampUs; }
    }
    private static final class DecodeSession implements Runnable {
        final LatestFrameMailbox<Encoded> mailbox=new LatestFrameMailbox<>();
        volatile boolean closed;
        final Thread thread=new Thread(this,"OtgLive-decode");
        long sequence,windowFrames,windowStart,decodeNs,rotateNs,nv21Ns,rgbaNs;
        void offer(byte[] jpeg,long timestampUs) { mailbox.offer(new Encoded(jpeg,timestampUs)); }
        void close() { closed=true; mailbox.close(); }
        public void run() {
            Bitmap reusable=null;
            RotationHelper.Renderer renderer=new RotationHelper.Renderer();
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inMutable=true;
            options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            windowStart=SystemClock.elapsedRealtimeNanos();
            try {
                while(!closed) {
                    Encoded input=mailbox.take();
                    if(input==null) break;
                    if(!previewEnabled && !liveEnabled && SystemClock.elapsedRealtime()>handoffUntil) continue;
                    long start=SystemClock.elapsedRealtimeNanos();
                    options.inBitmap=reusable;
                    Bitmap bitmap;
                    try { bitmap=BitmapFactory.decodeByteArray(input.jpeg,0,input.jpeg.length,options); }
                    catch(IllegalArgumentException incompatibleReuse) {
                        options.inBitmap=null;
                        bitmap=BitmapFactory.decodeByteArray(input.jpeg,0,input.jpeg.length,options);
                    }
                    if(bitmap==null) continue;
                    if(reusable!=null && reusable!=bitmap) reusable.recycle();
                    reusable=bitmap;
                    long decoded=SystemClock.elapsedRealtimeNanos();
                    Bitmap rotated=renderer.apply(bitmap);
                    try {
                        long rotatedAt=SystemClock.elapsedRealtimeNanos();
                        // NV21 is needed for the preparation UI, but not the observed RGB LIVE node.
                        boolean needNv21=previewEnabled || legacyNeedsNv21;
                        byte[] nv21=needNv21?CameraNodeUsbBridge.bitmapToNv21(rotated):null;
                        long converted=SystemClock.elapsedRealtimeNanos();
                        // Preview consumes NV21 only. Avoid allocating/copying a 3.7 MB RGBA
                        // array every frame until the actual LIVE RGB node requests it.
                        boolean needRgba=liveEnabled || !needNv21;
                        byte[] rgba=null;
                        if(needRgba) {
                            rgba=new byte[rotated.getWidth()*rotated.getHeight()*4];
                            rotated.copyPixelsToBuffer(ByteBuffer.wrap(rgba));
                        }
                        long ready=SystemClock.elapsedRealtimeNanos();
                        Image image=new Image(nv21,rgba,rotated.getWidth(),rotated.getHeight(),++sequence,input.timestampUs*1000L);
                        synchronized(VePreviewUsbBridge.class) {
                            if(!closed && decoder==this) latest=image;
                        }
                        windowFrames++;
                        decodeNs+=decoded-start; rotateNs+=rotatedAt-decoded;
                        nv21Ns+=converted-rotatedAt; rgbaNs+=ready-converted;
                        if(ready-windowStart>=5000000000L) {
                            double seconds=(ready-windowStart)/1e9;
                            Log.i(TAG,"Sony decode performance: mode="+(needNv21?"preview-NV21+RGB":"LIVE-RGB")
                                    +" processed_fps="+(windowFrames/seconds)
                                    +" decode_ms="+(decodeNs/1e6/windowFrames)+" rotate_ms="+(rotateNs/1e6/windowFrames)
                                    +" nv21_ms="+(nv21Ns/1e6/windowFrames)+" rgba_ms="+(rgbaNs/1e6/windowFrames)
                                    +" rgba_enabled="+needRgba
                                    +" stale_jpeg_dropped="+mailbox.dropped()+" queue_capacity=1");
                            windowStart=ready; windowFrames=decodeNs=rotateNs=nv21Ns=rgbaNs=0;
                        }
                    } finally { /* Renderer retains its reusable target until decoder shutdown. */ }
                }
            } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch(Throwable error) { if(!closed) Log.e(TAG,"Sony decode worker failed",error); }
            finally { renderer.close(); if(reusable!=null && !reusable.isRecycled()) reusable.recycle(); }
        }
    }
    public static synchronized void enter(Object fragment) {
        Log.i(TAG,"LIVE preparation enter: "+fragment.getClass().getName());
        previewEnabled=true;
        legacyNeedsNv21=false;
        liveEnabled=false;
        handoffUntil=0;
        lastLiveFrame=0;
        try {
            Context context=(Context)fragment.getClass().getMethod("getContext").invoke(fragment);
            if(context==null) return;
            ensureCamera(context.getApplicationContext());
        } catch(Exception e) { Log.e(TAG,"Cannot start LIVE preparation UVC source",e); }
    }
    public static synchronized void exit(Object fragment) {
        previewEnabled=false;
        if(!liveEnabled) handoffUntil=SystemClock.elapsedRealtime()+HANDOFF_MS;
        startMonitorLocked();
        Log.i(TAG,"LIVE preparation exited; waiting for VESDK handoff="+(!liveEnabled));
    }
    public static synchronized void liveEnter(Object owner) {
        liveEnabled=true;
        legacyNeedsNv21=true;
        handoffUntil=0;
        lastLiveFrame=SystemClock.elapsedRealtime();
        startMonitorLocked();
        try {
            Class<?> thread=Class.forName("android.app.ActivityThread");
            Object app=thread.getDeclaredMethod("currentApplication").invoke(null);
            if(!(app instanceof Context)) throw new IllegalStateException("Application unavailable");
            ensureCamera(((Context)app).getApplicationContext());
            Log.i(TAG,"TikTok encoder USB substitution enabled: "+owner.getClass().getName());
        } catch(Exception e) { Log.e(TAG,"Cannot start Sony source for TikTok encoder",e); }
    }
    public static synchronized void liveExit(Object owner) {
        liveEnabled=false;
        legacyNeedsNv21=false;
        handoffUntil=0;
        lastLiveFrame=0;
        stopIfUnused();
        Log.i(TAG,"TikTok encoder USB substitution disabled");
    }
    private static void ensureCamera(Context context) throws Exception {
        if(camera!=null) return;
        final DecodeSession session=new DecodeSession();
        decoder=session;
        session.thread.start();
        camera=new UvcCamera(context,new UvcCamera.Listener() {
            public void status(String message) { Log.i(TAG,"VESDK/encoder USB: "+message); }
            public void jpeg(byte[] bytes,long timestamp) {
                session.offer(bytes,timestamp);
            }
        });
        camera.start();
    }
    private static void stopIfUnused() {
        if(previewEnabled || liveEnabled) return;
        latest=null;
        DecodeSession oldDecoder=decoder; decoder=null;
        if(oldDecoder!=null) oldDecoder.close();
        UvcCamera old=camera; camera=null;
        if(old!=null) old.close();
    }
    private static void startMonitorLocked() {
        if(monitorRunning) return;
        monitorRunning=true;
        Thread monitor=new Thread(()->{
            for(;;) {
                try { Thread.sleep(1000); }
                catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
                synchronized(VePreviewUsbBridge.class) {
                    long now=SystemClock.elapsedRealtime();
                    if(!previewEnabled && liveEnabled && lastLiveFrame!=0 && now-lastLiveFrame>LIVE_IDLE_MS) {
                        liveEnabled=false;
                        lastLiveFrame=0;
                        Log.i(TAG,"TikTok LIVE frame stream ended; releasing Sony USB source");
                    }
                    if(!previewEnabled && !liveEnabled && handoffUntil!=0 && now>handoffUntil) {
                        handoffUntil=0;
                        Log.i(TAG,"VESDK handoff window expired");
                    }
                    if(!previewEnabled && !liveEnabled && handoffUntil==0) {
                        stopIfUnused();
                        monitorRunning=false;
                        return;
                    }
                }
            }
        },"OtgLive-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }
    public static synchronized Object replace(Object original) {
        // The active LIVE node owns its own RGB handoff. Ordinary camera callbacks
        // must not keep USB alive or allocate NV21 frames after preparation ends.
        if(!previewEnabled) return original;
        Image image=latest;
        if(image==null || image.nv21==null || original==null) return original;
        try {
            if(constructor==null) {
                Class<?> frame=original.getClass();
                Class<?> format=frame.getClassLoader().loadClass("com.ss.android.ttvecamera.TECameraFrame$ETEPixelFormat");
                pixelFormat=format.getField("PIXEL_FORMAT_NV21").get(null);
                constructor=frame.getConstructor(int.class,int.class,long.class);
                initBuffer=frame.getMethod("initBufferFrame",byte[].class,int.class,format,int.class);
                setDepend=frame.getMethod("setDependSurfaceTimestamp",boolean.class);
                getTimestamp=frame.getMethod("getTimeStampNS");
            }
            long timestamp=((Long)getTimestamp.invoke(original)).longValue();
            Object frame=constructor.newInstance(image.width,image.height,timestamp);
            initBuffer.invoke(frame,image.nv21,0,pixelFormat,0);
            setDepend.invoke(frame,false);
            replaced++;
            if(replaced==1 || replaced%150==0) Log.i(TAG,"Sony -> VESDK LIVE preview frames="+replaced+" size="+image.width+"x"+image.height);
            return frame;
        } catch(Exception e) { Log.e(TAG,"VESDK Sony frame substitution failed",e); return original; }
    }
    static synchronized Image liveNodeImage() {
        long now=SystemClock.elapsedRealtime();
        if(!previewEnabled && !liveEnabled && now>handoffUntil) return null;
        if(!liveEnabled) Log.i(TAG,"Sony USB handed to actual LIVE RGB node; preview NV21 conversion disabled");
        liveEnabled=true;
        legacyNeedsNv21=false;
        handoffUntil=0;
        lastLiveFrame=now;
        startMonitorLocked();
        Image image=latest;
        // First LIVE callback can still reference the last NV21-only preview image.
        // Returning null for that one callback lets the next decoded frame publish RGBA.
        return image!=null && image.rgba!=null?image:null;
    }
    /** Replace the phone-camera buffer at the final Java entry point used by the LIVE encoder. */
    public static Object replaceLiveBuffer(Object original) {
        legacyNeedsNv21=true;
        Image image=latest;
        if(!liveEnabled || image==null || image.nv21==null || original==null) return original;
        try {
            ClassLoader loader=original.getClass().getClassLoader();
            if(liveBufferConstructor==null || liveBufferLoader!=loader) {
                Class<?> nv21=loader.loadClass("com.ss.ttlivestreamer.core.buffer.NV21Buffer");
                liveBufferConstructor=nv21.getConstructor(byte[].class,int.class,int.class,Runnable.class);
                liveBufferLoader=loader;
            }
            Object buffer=liveBufferConstructor.newInstance(image.nv21,image.width,image.height,(Runnable)()->{});
            liveReplaced++;
            if(liveReplaced==1 || liveReplaced%150==0)
                Log.i(TAG,"Sony -> TikTok LIVE encoder buffers="+liveReplaced+" size="+image.width+"x"+image.height);
            return buffer;
        } catch(Exception e) {
            Log.e(TAG,"TikTok LIVE encoder Sony substitution failed",e);
            return original;
        }
    }
}
