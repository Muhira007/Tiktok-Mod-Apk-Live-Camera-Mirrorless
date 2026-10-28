package dev.otglive.bridge;

import android.graphics.Bitmap;
import dalvik.system.PathClassLoader;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Runs against classes and libotglive_image.so from the installed TikTok package. */
public final class NativeNv21AbiTest {
    private static int clamp(int value) { return value<0?0:Math.min(255,value); }
    private static byte[] expected(int[] argb,int width,int height) {
        byte[] yuv=new byte[width*height*3/2]; int yi=0,uv=width*height;
        for(int row=0;row<height;row++) for(int col=0;col<width;col++) {
            int color=argb[row*width+col],r=(color>>>16)&255,g=(color>>>8)&255,b=color&255;
            yuv[yi++]=(byte)clamp(((66*r+129*g+25*b+128)>>8)+16);
            if((row&1)==0&&(col&1)==0) {
                yuv[uv++]=(byte)clamp(((112*r-94*g-18*b+128)>>8)+128);
                yuv[uv++]=(byte)clamp(((-38*r-74*g+112*b+128)>>8)+128);
            }
        }
        return yuv;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("APK path list and nativeLibraryDir required");
        PathClassLoader loader=new PathClassLoader(args[0],args[1],NativeNv21AbiTest.class.getClassLoader());
        Class<?> bridge=loader.loadClass("dev.otglive.bridge.CameraNodeUsbBridge");
        Method convert=bridge.getDeclaredMethod("bitmapToNv21",Bitmap.class); convert.setAccessible(true);
        Class<?> nativeType=loader.loadClass("dev.otglive.bridge.NativeImageProcessor");
        Method available=nativeType.getDeclaredMethod("isAvailable"); available.setAccessible(true);
        int width=720,height=1280;
        int[] colors=new int[width*height];
        for(int y=0;y<height;y++) for(int x=0;x<width;x++)
            colors[y*width+x]=0xff000000|((x*17+y*3)&255)<<16|((x*5+y*19)&255)<<8|((x*11+y*7)&255);
        Bitmap bitmap=Bitmap.createBitmap(colors,width,height,Bitmap.Config.ARGB_8888);
        byte[] actual=(byte[])convert.invoke(null,bitmap);
        if(!(Boolean)available.invoke(null)) throw new AssertionError("native library did not load");
        if(!Arrays.equals(expected(colors,width,height),actual)) throw new AssertionError("native NV21 differs from Java reference");
        long started=System.nanoTime();
        for(int i=0;i<30;i++) convert.invoke(null,bitmap);
        double milliseconds=(System.nanoTime()-started)/1e6/30;
        bitmap.recycle();
        System.out.println("PASS native NV21 exact output; 720x1280 average_ms="+milliseconds);
    }
}
