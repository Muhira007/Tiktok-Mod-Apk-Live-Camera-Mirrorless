package dev.otglive.bridge;

import android.graphics.Matrix;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.WeakHashMap;

/** Substitute pixels at the CameraVideoNode output actually observed during LIVE. */
public final class LiveNodeTextureBridge {
    private static final String TAG="OtgLive";
    private static final WeakHashMap<Object,State> states=new WeakHashMap<>();
    private static long failures;
    private static final class Slot {
        int texture,width,height,users;
        VePreviewUsbBridge.Image image;
    }
    private static final class State {
        final EGLContext context;
        final Handler handler;
        final Slot[] slots=new Slot[6];
        final Constructor<?> constructor;
        final Object rgb;
        final Field frameBuffer,frameRotation;
        final Method release,refCount,setMatrix,setMatrixArray,updateCapture,updateNtp,getCapture,getNtp;
        final Matrix transform=new Matrix();
        final float[] transformArray=new float[16];
        ByteBuffer upload;
        Slot last;
        long frames,frozen;
        long metricsStarted=SystemClock.elapsedRealtimeNanos(),metricFrames,metricUnique,metricUploads;
        long uploadNs,finishNs,ageNs,lastSequence=-1;
        boolean closed;
        State(Object frame,EGLContext context) throws Exception {
            this.context=context;
            this.handler=Looper.myLooper()==null?null:new Handler(Looper.myLooper());
            ClassLoader loader=frame.getClass().getClassLoader();
            Class<?> type=loader.loadClass("X.0huW");
            Class<?> buffer=loader.loadClass("com.ss.ttlivestreamer.core.buffer.k");
            rgb=type.getField("RGB").get(null);
            constructor=buffer.getConstructor(int.class,int.class,type,int.class,Runnable.class);
            frameBuffer=frame.getClass().getField("buffer");
            frameRotation=frame.getClass().getField("rotation");
            release=buffer.getMethod("release");
            refCount=buffer.getMethod("getRefCount");
            setMatrix=buffer.getMethod("setTransformMatrix",Matrix.class);
            setMatrixArray=buffer.getMethod("setTransformMatrixArray",float[].class);
            updateCapture=buffer.getMethod("updateCaptureMs",long.class);
            updateNtp=buffer.getMethod("updateCaptureServerNtpMs",long.class);
            getCapture=buffer.getMethod("getCaptureMs");
            getNtp=buffer.getMethod("getCaptureServerNtpMs");
            transform.setScale(1,-1); transform.postTranslate(0,1);
            android.opengl.Matrix.setIdentityM(transformArray,0);
            transformArray[5]=-1; transformArray[13]=1;
            for(int i=0;i<slots.length;i++) slots[i]=new Slot();
        }
    }
    private static final class Released implements Runnable {
        final State state;
        final Slot slot;
        volatile boolean armed;
        Released(State state,Slot slot) { this.state=state; this.slot=slot; }
        public void run() {
            if(!armed) return;
            synchronized(state) { if(slot.users>0) slot.users--; }
            if(state.closed && state.handler!=null) state.handler.post(()->cleanup(state));
        }
    }
    private static void cleanup(State state) {
        if(!state.context.equals(EGL14.eglGetCurrentContext())) return;
        synchronized(state) {
            for(Slot slot:state.slots) if(slot.users==0 && slot.texture!=0) {
                GLES20.glDeleteTextures(1,new int[]{slot.texture},0);
                slot.texture=0; slot.image=null;
            }
        }
    }
    private static Slot upload(State state,VePreviewUsbBridge.Image image) {
        synchronized(state) {
            if(state.last!=null && state.last.image==image) return state.last;
            Slot selected=null;
            for(Slot slot:state.slots) if(slot.users==0) { selected=slot; break; }
            if(selected==null) {
                state.frozen++;
                if(state.frozen==1 || state.frozen%150==0) Log.i(TAG,"Sony LIVE GPU pool busy; retaining last Sony image count="+state.frozen);
                return state.last;
            }
            int[] binding=new int[1],alignment=new int[1];
            long uploadStarted=SystemClock.elapsedRealtimeNanos();
            GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D,binding,0);
            GLES20.glGetIntegerv(GLES20.GL_UNPACK_ALIGNMENT,alignment,0);
            try {
                if(selected.texture==0) {
                    int[] texture=new int[1];
                    GLES20.glGenTextures(1,texture,0);
                    selected.texture=texture[0];
                    if(selected.texture==0) throw new IllegalStateException("No RGB texture allocated");
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,selected.texture);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,1);
                if(state.upload==null || state.upload.capacity()!=image.rgba.length)
                    state.upload=ByteBuffer.allocateDirect(image.rgba.length).order(ByteOrder.nativeOrder());
                state.upload.clear(); state.upload.put(image.rgba); state.upload.flip();
                if(selected.width!=image.width || selected.height!=image.height) {
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,image.width,image.height,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,state.upload);
                    selected.width=image.width; selected.height=image.height;
                } else GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,image.width,image.height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,state.upload);
                // Uploads must finish before another shared GL thread samples this source texture.
                long finishStarted=SystemClock.elapsedRealtimeNanos();
                GLES20.glFinish();
                state.finishNs+=SystemClock.elapsedRealtimeNanos()-finishStarted;
                if(!GLES20.glIsTexture(selected.texture)) throw new IllegalStateException("RGB upload did not create a valid GL texture");
                selected.image=image;
                state.last=selected;
                state.metricUploads++;
                state.uploadNs+=SystemClock.elapsedRealtimeNanos()-uploadStarted;
                return selected;
            } finally {
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,alignment[0]);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,binding[0]);
            }
        }
    }
    public static void replaceFrame(Object node,Object frame,Object metadata) {
        if(node==null || frame==null || !node.getClass().getName().equals("X.0hqC")) return;
        try {
            VePreviewUsbBridge.Image image=VePreviewUsbBridge.liveNodeImage();
            if(image==null) return;
            EGLContext context=EGL14.eglGetCurrentContext();
            if(context==null || context.equals(EGL14.EGL_NO_CONTEXT)) throw new IllegalStateException("CameraVideoNode output has no current GL context");
            State state;
            synchronized(states) {
                state=states.get(node);
                if(state==null || !state.context.equals(context)) {
                    if(state!=null) { state.closed=true; cleanup(state); }
                    state=new State(frame,context);
                    states.put(node,state);
                    Log.i(TAG,"Sony LIVE CameraVideoNode output hook active; GL="+GLES20.glGetString(GLES20.GL_VERSION));
                }
            }
            Object old=state.frameBuffer.get(frame);
            // CameraVideoNode dispatches a reusable OES buffer with refcount zero. Preserve that ABI.
            int refs=((Number)state.refCount.invoke(old)).intValue();
            if(refs!=0) throw new IllegalStateException("Unexpected source buffer refcount="+refs);
            Slot slot=upload(state,image);
            if(slot==null) return;
            Released released=new Released(state,slot);
            Object replacement=state.constructor.newInstance(slot.width,slot.height,state.rgb,slot.texture,released);
            state.setMatrix.invoke(replacement,state.transform);
            state.setMatrixArray.invoke(replacement,(Object)state.transformArray);
            state.updateCapture.invoke(replacement,((Number)state.getCapture.invoke(old)).longValue());
            state.updateNtp.invoke(replacement,((Number)state.getNtp.invoke(old)).longValue());
            state.release.invoke(replacement); // Initial 1 -> 0, matching the source node's ownership.
            synchronized(state) { slot.users++; }
            released.armed=true;
            if(metadata instanceof Bundle) {
                Bundle bundle=(Bundle)metadata;
                bundle.putInt("src_width",slot.width); bundle.putInt("src_height",slot.height);
                bundle.putBoolean("horizontal_flipped",false);
            }
            state.frameRotation.setInt(frame,0);
            state.frameBuffer.set(frame,replacement);
            state.frames++;
            state.metricFrames++;
            long now=SystemClock.elapsedRealtimeNanos();
            if(state.lastSequence!=slot.image.sequence) { state.metricUnique++; state.lastSequence=slot.image.sequence; }
            state.ageNs+=Math.max(0,now-slot.image.captureNs);
            if(now-state.metricsStarted>=5000000000L) {
                double seconds=(now-state.metricsStarted)/1e9;
                Log.i(TAG,"Sony LIVE performance: output_fps="+(state.metricFrames/seconds)
                        +" unique_sony_fps="+(state.metricUnique/seconds)
                        +" duplicate_frames="+(state.metricFrames-state.metricUnique)
                        +" upload_ms="+(state.metricUploads==0?0:state.uploadNs/1e6/state.metricUploads)
                        +" gpu_finish_ms="+(state.metricUploads==0?0:state.finishNs/1e6/state.metricUploads)
                        +" read_to_send_age_ms="+(state.ageNs/1e6/state.metricFrames));
                state.metricsStarted=now;
                state.metricFrames=state.metricUnique=state.metricUploads=state.uploadNs=state.finishNs=state.ageNs=0;
            }
            if(state.frames==1 || state.frames%150==0)
                Log.i(TAG,"Sony -> actual LIVE CameraVideoNode RGB frames="+state.frames+" size="+slot.width+"x"+slot.height+" rotation=0 texture="+slot.texture);
        } catch(Throwable error) {
            long count=++failures;
            if(count==1 || count%150==0) Log.e(TAG,"Sony CameraVideoNode texture replacement failed count="+count,error);
        }
    }
    public static void stopManager(Object manager) {
        try {
            Object node=manager.getClass().getField("LLJJIJIIJIL").get(manager);
            State state;
            synchronized(states) { state=states.remove(node); }
            if(state!=null) {
                state.closed=true;
                if(state.handler!=null) state.handler.post(()->cleanup(state));
                Log.i(TAG,"Sony LIVE RGB pipeline stopped; sent="+state.frames);
            }
        } catch(Throwable error) { Log.w(TAG,"Cannot inspect stopped LIVE node",error); }
        VePreviewUsbBridge.liveExit(manager);
    }
}
