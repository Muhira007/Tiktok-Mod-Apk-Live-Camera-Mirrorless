import java.io.File;
import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.builder.*;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.writer.pool.DexPool;

/** Minimal production hooks: LIVE lifecycle and Sony preview frame substitution only. */
public final class PatchProductionDex {
    static final String BRIDGE="Ldev/otglive/bridge/VePreviewUsbBridge;";
    static final String FRAME="Lcom/ss/android/ttvecamera/TECameraFrame;";

    static int words(Method method) {
        int result=(method.getAccessFlags()&AccessFlags.STATIC.getValue())==0?1:0;
        for(CharSequence type:method.getParameterTypes())
            result+=(type.toString().equals("J")||type.toString().equals("D"))?2:1;
        return result;
    }

    static ImmutableMethodReference ref(String name,String result) {
        return new ImmutableMethodReference(BRIDGE,name,
                Collections.singletonList("Ljava/lang/Object;"),result);
    }

    public static void main(String[] args) throws Exception {
        String mode=args[2];
        if(!mode.equals("preview") && !mode.equals("provider"))
            throw new IllegalArgumentException("Mode must be preview or provider");
        DexFile input=DexFileFactory.loadDexFile(new File(args[0]),Opcodes.forApi(34));
        DexPool output=new DexPool(input.getOpcodes());
        int patched=0;
        for(ClassDef cls:input.getClasses()) {
            List<Method> methods=new ArrayList<>();
            for(Method method:cls.getMethods()) {
                String hook=null;
                boolean replace=false;
                List<? extends CharSequence> params=method.getParameterTypes();
                if(mode.equals("preview")
                        && cls.getType().equals("Lcom/bytedance/android/livesdk/broadcast/preview/LiveBroadcastPreviewFragment;")
                        && params.isEmpty()) {
                    if(method.getName().equals("onResume") || method.getName().equals("oU")) hook="enter";
                    if(method.getName().equals("onDestroyView") || method.getName().equals("Es")) hook="exit";
                }
                if(mode.equals("provider") && params.size()==1 && params.get(0).equals(FRAME)) {
                    if(cls.getType().equals("LX/1PW1;") && method.getName().equals("LIZ")) replace=true;
                    if(cls.getType().equals("LX/1Pab;") && method.getName().equals("LJIILJJIL")) replace=true;
                    if(replace) hook="replace";
                }
                if(hook==null) { methods.add(method); continue; }
                if(method.getImplementation()==null) throw new IllegalStateException("No body: "+method);
                MutableMethodImplementation body=new MutableMethodImplementation(method.getImplementation());
                int p0=body.getRegisterCount()-words(method);
                int register=replace?p0+1:p0;
                if(register>255) throw new IllegalStateException("Register too large: "+register);
                body.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,register,1,
                        ref(hook,replace?"Ljava/lang/Object;":"V")));
                if(replace) {
                    body.addInstruction(1,new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,register));
                    body.addInstruction(2,new BuilderInstruction21c(Opcode.CHECK_CAST,register,
                            new ImmutableTypeReference(FRAME)));
                }
                methods.add(new ImmutableMethod(method.getDefiningClass(),method.getName(),method.getParameters(),
                        method.getReturnType(),method.getAccessFlags(),method.getAnnotations(),
                        method.getHiddenApiRestrictions(),body));
                patched++;
                System.out.println("Production hook "+mode+": "+cls.getType()+"->"+method.getName()+" via "+hook);
            }
            output.internClass(new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),
                    cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods));
        }
        int expected=mode.equals("preview")?4:2;
        if(patched!=expected) throw new IllegalStateException("Unexpected "+mode+" hook count: "+patched);
        output.writeTo(new org.jf.dexlib2.writer.io.FileDataStore(new File(args[1])));
        DexFileFactory.loadDexFile(new File(args[1]),Opcodes.forApi(34));
    }
}
