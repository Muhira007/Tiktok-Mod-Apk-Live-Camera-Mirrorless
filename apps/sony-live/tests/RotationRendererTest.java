package dev.otglive.bridge;
import android.graphics.Bitmap;
import java.lang.reflect.Field;
public final class RotationRendererTest {
    static void config(int degrees,boolean mirror) throws Exception {
        for(String name:new String[]{"cachedRotation","cachedMirror","lastCheck"}) {
            Field field=RotationHelper.class.getDeclaredField(name); field.setAccessible(true);
            if(name.equals("cachedRotation")) field.setInt(null,degrees);
            else if(name.equals("cachedMirror")) field.setBoolean(null,mirror);
            else field.setLong(null,System.currentTimeMillis()+60000);
        }
    }
    public static void main(String[] args) throws Exception {
        Bitmap src=Bitmap.createBitmap(8,6,Bitmap.Config.ARGB_8888);
        for(int y=0;y<6;y++) for(int x=0;x<8;x++) src.setPixel(x,y,0xff000000|(x*31<<16)|(y*41<<8)|(x+y));
        RotationHelper.Renderer renderer=new RotationHelper.Renderer();
        for(int angle:new int[]{0,90,180,270}) for(boolean mirror:new boolean[]{false,true}) {
            config(angle,mirror);
            Bitmap expected=RotationHelper.apply(src), actual=renderer.apply(src);
            if(!expected.sameAs(actual)) throw new AssertionError("Rotation/mirror mismatch "+angle+" "+mirror);
            if(renderer.apply(src)!=actual) throw new AssertionError("Target was reallocated");
            // A later source frame must replace all pixels in the reused target.
            src.setPixel(0,0,src.getPixel(0,0)^0x0000ff00);
            Bitmap nextExpected=RotationHelper.apply(src);
            if(!nextExpected.sameAs(renderer.apply(src))) throw new AssertionError("Stale pixels");
            if(nextExpected!=src) nextExpected.recycle();
            if(expected!=src) expected.recycle();
        }
        renderer.close(); src.recycle();
        System.out.println("PASS Android bitmap rotation: 8 orientation/mirror combinations, reuse and fresh pixels");
    }
}
