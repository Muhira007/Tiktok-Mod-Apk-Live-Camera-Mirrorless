package dev.otglive.bridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.lang.reflect.*;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** The LIVE SDK's legacy camera node, used before the Lyrax platform wrapper. */
public final class CameraNodeUsbBridge {
    private static final String TAG="OtgLive";
    private static final IdentityHashMap<Object,Session> sessions=new IdentityHashMap<>();
    private static Field field(Object target,String name) throws Exception {
        for(Class<?> cls=target.getClass();cls!=null;cls=cls.getSuperclass()) {
            try { Field f=cls.getDeclaredField(name); f.setAccessible(true); return f; }
            catch(NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    private static Object get(Object target,String name) throws Exception { return field(target,name).get(target); }
    private static void set(Object target,String name,Object value) throws Exception { field(target,name).set(target,value); }
    public static synchronized void start(Object node,int width,int height,int fps) {
        Log.i(TAG,"CameraNodeUsbBridge.start class="+node.getClass().getName()+" requested="+width+"x"+height+" fps="+fps);
        if(sessions.containsKey(node)) return;
        try {
            for(Session old:sessions.values()) old.close();
            sessions.clear();
            set(node,"LLJJIJIL",1280); set(node,"LLJJJ",720);
            set(node,"LLJJJIL",Math.min(30,Math.max(1,fps))); set(node,"LLJJJJ",1); set(node,"B",true);
            Session session=new Session(node);
            sessions.put(node,session);
            session.camera.start();
        } catch(Exception e) { Log.e(TAG,"Legacy Sony source initialization failed",e); }
    }
    public static synchronized void stop(Object node) {
        Session session=sessions.remove(node);
        if(session!=null) session.close();
        try { set(node,"LLJJJJ",2); set(node,"B",false); } catch(Exception e) { Log.w(TAG,"Status update failed",e); }
        Log.i(TAG,"CameraNodeUsbBridge.stop class="+node.getClass().getName());
    }
    public static void keep(Object node,int camera) { Log.i(TAG,"Keep Sony source; ignored phone camera selector "+camera); }
    private static void call(Object listener,String name) throws Exception {
        if(listener==null) return;
        Method method=listener.getClass().getMethod(name); method.setAccessible(true); method.invoke(listener);
    }
    private static final class Frame {
        final byte[] jpeg; final long timestamp;
        Frame(byte[] jpeg,long timestamp) { this.jpeg=jpeg; this.timestamp=timestamp; }
    }
    private static final class Session implements UvcCamera.Listener {
        final Object node,listener;
        final Context context;
        final Handler handler;
        final UvcCamera camera;
        final Constructor<?> nv21;
        final Method push,toI420,release;
        final AtomicReference<Frame> pending=new AtomicReference<>();
        final AtomicBoolean scheduled=new AtomicBoolean();
        volatile boolean closed;
        boolean notified;
        long pushed;
        Session(Object node) throws Exception {
            this.node=node;
            context=((Context)get(node,"LLJZ")).getApplicationContext();
            handler=(Handler)get(node,"LLJJL");
            listener=get(node,"LLJJJJLIIL");
            ClassLoader loader=node.getClass().getClassLoader();
            Class<?> buffer=loader.loadClass("com.ss.ttlivestreamer.core.buffer.VideoFrame$Buffer");
            Class<?> nv21Class=loader.loadClass("com.ss.ttlivestreamer.core.buffer.NV21Buffer");
            nv21=nv21Class.getConstructor(byte[].class,int.class,int.class,Runnable.class);
            toI420=buffer.getMethod("toI420"); release=buffer.getMethod("release");
            push=node.getClass().getMethod("LLJJI",buffer,int.class,int.class,int.class,long.class); push.setAccessible(true);
            camera=new UvcCamera(context,this);
        }
        public void status(String message) {
            Log.i(TAG,"Legacy LIVE USB: "+message);
            if(message.startsWith("ERROR") || message.startsWith("ZV-1 MJPEG"))
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context,message,Toast.LENGTH_LONG).show());
        }
        public void jpeg(byte[] jpeg,long timestamp) {
            if(closed) return;
            pending.set(new Frame(jpeg,timestamp));
            if(scheduled.compareAndSet(false,true)) handler.post(this::drain);
        }
        private void drain() {
            try {
                Frame frame=pending.getAndSet(null);
                if(frame==null || closed) return;
                Bitmap bitmap=BitmapFactory.decodeByteArray(frame.jpeg,0,frame.jpeg.length);
                if(bitmap==null) return;
                Bitmap rotated=RotationHelper.apply(bitmap);
                int width=rotated.getWidth(),height=rotated.getHeight();
                byte[] pixels;
                try { pixels=bitmapToNv21(rotated); } finally { if (rotated != bitmap) rotated.recycle(); bitmap.recycle(); }
                Object raw=nv21.newInstance(pixels,width,height,(Runnable)()->{});
                Object yuv=null;
                try {
                    yuv=toI420.invoke(raw);
                    if(!notified) { call(listener,"LJIIL"); notified=true; }
                    Object result=push.invoke(node,yuv,width,height,0,frame.timestamp);
                    pushed++;
                    if(pushed==1 || pushed%150==0) Log.i(TAG,"Sony -> legacy LIVE count="+pushed+" result="+result);
                } finally {
                    if(yuv!=null) release.invoke(yuv);
                    release.invoke(raw);
                }
            } catch(Throwable e) { Log.e(TAG,"Legacy LIVE frame delivery failed",e); }
            finally {
                scheduled.set(false);
                if(!closed && pending.get()!=null && scheduled.compareAndSet(false,true)) handler.post(this::drain);
            }
        }
        void close() {
            closed=true; pending.set(null); camera.close();
            if(notified) handler.post(() -> { try { call(listener,"LJ"); } catch(Exception e) { Log.w(TAG,"Stop notification failed",e); } });
        }
    }
    static byte[] bitmapToNv21(Bitmap bitmap) {
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        if((w&1)!=0 || (h&1)!=0) throw new IllegalArgumentException("Even dimensions required");
        byte[] nativeResult=NativeImageProcessor.bitmapToNv21(bitmap);
        if(nativeResult!=null) return nativeResult;
        int[] argb=new int[w*h]; bitmap.getPixels(argb,0,w,0,0,w,h);
        byte[] yuv=new byte[w*h*3/2]; int yi=0,uv=w*h;
        for(int row=0;row<h;row++) for(int col=0;col<w;col++) {
            int color=argb[row*w+col],r=(color>>>16)&255,g=(color>>>8)&255,b=color&255;
            yuv[yi++]=(byte)clamp(((66*r+129*g+25*b+128)>>8)+16);
            if((row&1)==0 && (col&1)==0) {
                yuv[uv++]=(byte)clamp(((112*r-94*g-18*b+128)>>8)+128);
                yuv[uv++]=(byte)clamp(((-38*r-74*g+112*b+128)>>8)+128);
            }
        }
        return yuv;
    }
    private static int clamp(int value) { return value<0?0:Math.min(255,value); }
}
