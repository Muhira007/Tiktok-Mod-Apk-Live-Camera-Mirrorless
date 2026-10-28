import java.io.File;
import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.builder.*;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.immutable.reference.ImmutableTypeReference;
import org.jf.dexlib2.writer.pool.DexPool;

/** Patch only the LIVE camera wrapper. Other application methods are preserved. */
public class PatchLiveDex {
    static final String TARGET="Lcom/ss/lyrax/video/LyraxCameraCapture;";
    static final String BRIDGE="Ldev/otglive/bridge/LiveUsbBridge;";
    static final String RESULT="Lcom/ss/lyrax/LyraxErrorCode;";
    static final String NODE="LX/0hto;";
    static final String NODEBRIDGE="Ldev/otglive/bridge/CameraNodeUsbBridge;";
    static final String STREAM="Lcom/ss/ttlivestreamer/livestreamv2/capture/LiveStreamVideoCapture;";
    static final String STREAMBRIDGE="Ldev/otglive/bridge/VePreviewUsbBridge;";
    static final String BUFFER="Lcom/ss/ttlivestreamer/core/buffer/VideoFrame$Buffer;";
    static final String TEXTUREBRIDGE="Ldev/otglive/bridge/LiveNodeTextureBridge;";
    static final Map<String,String> PATCH=new LinkedHashMap<>();
    static {
        PATCH.put("startPlatformCapture","start");
        PATCH.put("stopPlatformCapture","stop");
        PATCH.put("switchCameraFront","keepSony");
        PATCH.put("switchCameraBack","keepSony");
        PATCH.put("switchCameraTo","keepSony");
        PATCH.put("updatePlatformCaptureConfig","configure");
    }
    public static void main(String[] args) throws Exception {
        DexFile input=DexFileFactory.loadDexFile(new File(args[0]),Opcodes.forApi(34));
        DexPool output=new DexPool(input.getOpcodes());
        Set<String> patched=new LinkedHashSet<>();
        Set<String> nodePatched=new LinkedHashSet<>();
        Set<String> streamPatched=new LinkedHashSet<>();
        Set<String> actualPatched=new LinkedHashSet<>();
        for(ClassDef cls:input.getClasses()) {
            boolean source=cls.getType().equals("LX/0hoe;");
            boolean manager=cls.getType().equals("LX/0htp;");
            if(source || manager) {
                List<Method> methods=new ArrayList<>();
                for(Method method:cls.getMethods()) {
                    List<? extends CharSequence> types=method.getParameterTypes();
                    boolean frame=source && method.getName().equals("LJII") && types.size()==2
                            && types.get(0).toString().equals("Lcom/ss/ttlivestreamer/core/buffer/VideoFrame;")
                            && types.get(1).toString().equals("Landroid/os/Bundle;");
                    boolean stop=manager && method.getName().equals("LJIIL") && types.isEmpty();
                    if(!frame && !stop) { methods.add(method); continue; }
                    MutableMethodImplementation body=new MutableMethodImplementation(method.getImplementation());
                    int count=frame?3:1;
                    int register=body.getRegisterCount()-count;
                    ImmutableMethodReference ref=new ImmutableMethodReference(TEXTUREBRIDGE,frame?"replaceFrame":"stopManager",
                            Collections.nCopies(count,"Ljava/lang/Object;"),"V");
                    body.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,register,count,ref));
                    methods.add(new ImmutableMethod(method.getDefiningClass(),method.getName(),method.getParameters(),method.getReturnType(),method.getAccessFlags(),method.getAnnotations(),method.getHiddenApiRestrictions(),body));
                    actualPatched.add(frame?"actual-frame":"actual-stop");
                    System.out.println("Patched observed LIVE pipeline "+cls.getType()+"->"+method.getName());
                }
                output.internClass(new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods));
                continue;
            }
            if(cls.getType().equals(NODE)) {
                List<Method> methods=new ArrayList<>();
                for(Method method:cls.getMethods()) {
                    String name=method.getName();
                    if(!name.equals("LLJJIII") && !name.equals("stop") && !name.equals("LLJJJIL")) { methods.add(method); continue; }
                    boolean start=name.equals("LLJJIII"),keep=name.equals("LLJJJIL");
                    String target=start?"start":keep?"keep":"stop";
                    List<String> params=start?Arrays.asList("Ljava/lang/Object;","I","I","I"):keep?Arrays.asList("Ljava/lang/Object;","I"):Arrays.asList("Ljava/lang/Object;");
                    List<Instruction> instructions=Arrays.asList(
                        new ImmutableInstruction35c(Opcode.INVOKE_STATIC,params.size(),0,1,2,3,0,new ImmutableMethodReference(NODEBRIDGE,target,params,"V")),
                        new ImmutableInstruction10x(Opcode.RETURN_VOID));
                    MethodImplementation body=new ImmutableMethodImplementation(params.size(),instructions,Collections.emptyList(),Collections.emptyList());
                    methods.add(new ImmutableMethod(method.getDefiningClass(),name,method.getParameters(),method.getReturnType(),method.getAccessFlags(),method.getAnnotations(),method.getHiddenApiRestrictions(),body));
                    nodePatched.add(name);
                    System.out.println("Patched legacy camera node "+name+" -> CameraNodeUsbBridge."+target);
                }
                output.internClass(new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods));
                continue;
            }
            if(cls.getType().equals(STREAM)) {
                List<Method> methods=new ArrayList<>();
                for(Method method:cls.getMethods()) {
                    List<? extends CharSequence> types=method.getParameterTypes();
                    boolean frame=method.getName().equals("LLJJI") && types.size()==5 && types.get(0).toString().equals(BUFFER)
                        && types.get(1).toString().equals("I") && types.get(2).toString().equals("I")
                        && types.get(3).toString().equals("I") && types.get(4).toString().equals("J") && method.getReturnType().equals("I");
                    boolean start=method.getName().equals("LLJJIII") && types.size()==3 && types.get(0).toString().equals("I")
                        && types.get(1).toString().equals("I") && types.get(2).toString().equals("I") && method.getReturnType().equals("V");
                    boolean stop=method.getName().equals("stop") && types.isEmpty() && method.getReturnType().equals("V");
                    if(!frame && !start && !stop) { methods.add(method); continue; }
                    MutableMethodImplementation body=new MutableMethodImplementation(method.getImplementation());
                    int register;
                    if(frame) {
                        // p0=this, p1=Buffer, p2/p3/p4=int, p5/p6=long.
                        register=body.getRegisterCount()-6;
                        ImmutableMethodReference ref=new ImmutableMethodReference(STREAMBRIDGE,"replaceLiveBuffer",Arrays.asList("Ljava/lang/Object;"),"Ljava/lang/Object;");
                        body.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,register,1,ref));
                        body.addInstruction(1,new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,register));
                        body.addInstruction(2,new BuilderInstruction21c(Opcode.CHECK_CAST,register,new ImmutableTypeReference(BUFFER)));
                    } else {
                        // p0 is the first parameter register of an instance method.
                        register=body.getRegisterCount()-(start?4:1);
                        ImmutableMethodReference ref=new ImmutableMethodReference(STREAMBRIDGE,start?"liveEnter":"liveExit",Arrays.asList("Ljava/lang/Object;"),"V");
                        body.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,register,1,ref));
                    }
                    methods.add(new ImmutableMethod(method.getDefiningClass(),method.getName(),method.getParameters(),method.getReturnType(),method.getAccessFlags(),method.getAnnotations(),method.getHiddenApiRestrictions(),body));
                    String key=frame?"frame":start?"start":"stop";
                    streamPatched.add(key);
                    System.out.println("Patched LiveStreamVideoCapture "+method.getName()+" -> "+key);
                }
                output.internClass(new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods));
                continue;
            }
            if(!cls.getType().equals(TARGET)) { output.internClass(cls); continue; }
            List<Method> methods=new ArrayList<>();
            for(Method method:cls.getMethods()) {
                String bridge=PATCH.get(method.getName());
                if(bridge==null) { methods.add(method); continue; }
                if(!method.getReturnType().equals(RESULT)) throw new IllegalStateException("Unexpected ABI: "+method);
                int count=method.getParameterTypes().size();
                boolean config=bridge.equals("configure");
                List<String> params=config?Arrays.asList("Ljava/lang/Object;","Ljava/lang/Object;"):Arrays.asList("Ljava/lang/Object;");
                ImmutableMethodReference ref=new ImmutableMethodReference(BRIDGE,bridge,params,"Ljava/lang/Object;");
                List<Instruction> instructions=Arrays.asList(
                    new ImmutableInstruction35c(Opcode.INVOKE_STATIC,config?2:1,1,2,0,0,0,ref),
                    new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,0),
                    new ImmutableInstruction21c(Opcode.CHECK_CAST,0,new ImmutableTypeReference(RESULT)),
                    new ImmutableInstruction11x(Opcode.RETURN_OBJECT,0));
                MethodImplementation body=new ImmutableMethodImplementation(count+2,instructions,Collections.emptyList(),Collections.emptyList());
                methods.add(new ImmutableMethod(method.getDefiningClass(),method.getName(),method.getParameters(),method.getReturnType(),method.getAccessFlags(),method.getAnnotations(),method.getHiddenApiRestrictions(),body));
                patched.add(method.getName());
                System.out.println("Patched "+method.getName()+" -> LiveUsbBridge."+bridge);
            }
            output.internClass(new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods));
        }
        if(!patched.equals(PATCH.keySet())) throw new IllegalStateException("Incomplete patch: "+patched);
        if(nodePatched.size()!=3) throw new IllegalStateException("Incomplete legacy patch: "+nodePatched);
        if(!streamPatched.equals(new LinkedHashSet<>(Arrays.asList("frame","start","stop")))) throw new IllegalStateException("Incomplete stream patch: "+streamPatched);
        if(actualPatched.size()!=2) throw new IllegalStateException("Incomplete actual LIVE pipeline patch: "+actualPatched);
        output.writeTo(new org.jf.dexlib2.writer.io.FileDataStore(new File(args[1])));
        DexFile checked=DexFileFactory.loadDexFile(new File(args[1]),Opcodes.forApi(34));
        System.out.println("Rebuilt DEX: "+checked.getClasses().size()+" classes; "+patched.size()+" LIVE methods changed");
    }
}
