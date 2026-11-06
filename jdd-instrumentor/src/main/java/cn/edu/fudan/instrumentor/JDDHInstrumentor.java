package cn.edu.fudan.instrumentor;

import cn.edu.fudan.agent.AegAgent;
import cn.edu.fudan.data.AgentGlobalDataContainer;
import cn.edu.fudan.data.distance.data.InstNode;
import cn.edu.fudan.instrumentor.visitor.WriteFileOracleWrapper;
import cn.edu.fudan.utils.LogUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

public class JDDHInstrumentor implements ClassFileTransformer {

    /**
     * COMPUTE_FRAMES has ASM load the types it merges (getCommonSuperClass). By default it uses the loader of
     * ASM's own ClassWriter, which does not see the application's classes (e.g. bsh/Primitive), so computing
     * the frames of an application class threw, transform() failed and the JVM silently kept the class
     * uninstrumented. Resolve the types through the loader of the class being transformed instead.
     */
    private static ClassWriter newFrameComputingWriter(ClassLoader loader) {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : ClassLoader.getSystemClassLoader();
            }
        };
    }
    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {

        // 没开启ODD插桩前，直接返回原来的字节码
        if (AegAgent.modeInt == 1 && !AegAgent.isJDDInstStart) {
            return classfileBuffer;
        }
        className = className.replace("/", ".");
        if (className.startsWith("cn.edu.fudan")
                || className.startsWith("cn.edu.fdu")
                || className.startsWith("ysoserial")
                || className.equals("java.io.PrintStream")
                || className.equals("java.lang.Thread")
                || className.startsWith("java.lang.instrument")
                || className.equals("java.lang.Throwable")
                || className.equals("java.lang.Exception")
                || className.equals("java.lang.StackTraceElement")
                || className.equals("java.lang.Object")
                || className.equals("java.lang.String")
                || className.equals("java.lang.StringBuilder")
                || className.equals("java.lang.AbstractStringBuilder")
                || className.equals("java.lang.Integer")
                || className.equals("java.lang.Boolean")
                || className.equals("java.lang.Long")
                || className.equals("java.lang.Short")
                || className.equals("java.lang.Float")
                || className.equals("java.lang.Double")
                || className.equals("java.lang.Character")
                || className.equals("java.lang.Byte")
                || className.equals("java.lang.Math")
                || className.equals("java.lang.System")
                || className.startsWith("java.util.regex")) {
            return classfileBuffer;
        }
        if (AgentGlobalDataContainer.gContainer.bbInstInfoMap.containsKey(className)) {
            ClassWriter cw = newFrameComputingWriter(loader);
            // 遍历每个插桩点，然后进行插桩
            Set<InstNode> bbSet =  AgentGlobalDataContainer.gContainer.bbInstInfoMap.get(className);
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassNode cn = new ClassNode();
            int parsingOptions = ClassReader.SKIP_FRAMES;
            cr.accept(cn, parsingOptions);
            for (InstNode node : bbSet) {
                MethodNode targetNode = null;
                // 找到要插桩的方法
                for (MethodNode mn : cn.methods) {
                    if (mn.name.equals(node.mtdName) && mn.desc.equals(node.mtdDesc)) {
                        targetNode = mn;
                        break;
                    }
                }
                if (targetNode == null) {
                    LogUtils.error("Can not find " + className
                            + " method: " + node.mtdName
                            + " with desc: " + node.mtdDesc);
                    System.exit(0);
                }
                // 有记录Label就通过label进行插桩，没有就通过instNum进行插桩
                if (node.lineNum != -1) {
                    doLineNumInstrument(targetNode, node);
                } else if (node.instNum != -1) {
                    doInstNumInstrument(targetNode, node);
                } else if (node.label != -1) {
                    doLabelInstrument(targetNode, node);
                }

                LogUtils.success2Log("Finished Instruct for "
                        + className + " "
                        + node.mtdName + " "
                        + node.mtdDesc + " at LineNum:"
                        + node.lineNum + " with Label:"
                        + node.label + " with InstID:"
                        + node.instNum);
            }
            cn.accept(cw);
            // 更新插桩后的字节码
            classfileBuffer = cw.toByteArray();
        }

        // 对特定的Sink点进行可达性验证插桩
        if (AgentGlobalDataContainer.gContainer.sinkInstMtdInfoMap.containsKey(className)) {
            ClassWriter cw = newFrameComputingWriter(loader);
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassNode cn = new ClassNode();
            int parsingOptions = ClassReader.SKIP_FRAMES;
            cr.accept(cn, parsingOptions);
            for (MethodNode mn : cn.methods) {
                if (isSinkMethodNode(mn, AgentGlobalDataContainer.gContainer.sinkInstMtdInfoMap.get(className))) {
                    InsnList newInstructions = new InsnList();
                    newInstructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "datastructrue/inst/JDDFuzzRecord", "doSinkArrivedFeedBack", "()V", false));
                    mn.instructions.insertBefore(mn.instructions.get(0), newInstructions);

                    LogUtils.info2Log("Finished SINK Instrument for "
                            + mn.name + " "
                            + mn.desc);
                }
            }
            cn.accept(cw);
            classfileBuffer = cw.toByteArray();
        }

        // Reachability marker hook (JNDI/CUSTOM/CLOJURE families). Separate from the generic sink block
        // above and driven by a family-scoped map, so it never touches the whole app's sink list (which
        // includes zero-arg methods and constructors, where an unconditional ALOAD would be invalid
        // bytecode). For a registered method it reads one Object/array argument and passes it to
        // sinkMarkerOracle, which only compares it to this run's inert token. Inert while the map is empty.
        if (AgentGlobalDataContainer.gContainer.sinkMarkerArgMap != null
                && AgentGlobalDataContainer.gContainer.sinkMarkerArgMap.containsKey(className)) {
            java.util.Map<String, Integer> argByMtd = AgentGlobalDataContainer.gContainer.sinkMarkerArgMap.get(className);
            try {
            ClassWriter cw = newFrameComputingWriter(loader);
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassNode cn = new ClassNode();
            cr.accept(cn, ClassReader.SKIP_FRAMES);
            for (MethodNode mn : cn.methods) {
                Integer argIndex = argByMtd.get(mn.name + "#" + mn.desc);
                if (argIndex == null
                        || (mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                        || mn.instructions.size() == 0) {
                    continue;
                }
                org.objectweb.asm.Type[] pt = org.objectweb.asm.Type.getArgumentTypes(mn.desc);
                boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
                int slot;
                if (argIndex == -1) {
                    if (isStatic) {
                        continue;   // -1 is the receiver, which a static method has none of
                    }
                    slot = 0;
                } else {
                    if (argIndex < 0 || argIndex >= pt.length) {
                        continue;
                    }
                    int sort = pt[argIndex].getSort();
                    if (sort != org.objectweb.asm.Type.OBJECT && sort != org.objectweb.asm.Type.ARRAY) {
                        continue;   // only reference arguments can be read as Object
                    }
                    slot = isStatic ? 0 : 1;
                    for (int i = 0; i < argIndex; i++) {
                        slot += pt[i].getSize();   // long/double occupy two local slots
                    }
                }
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, slot));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "datastructrue/inst/JDDFuzzRecord", "sinkMarkerOracle", "(Ljava/lang/Object;)V", false));
                mn.instructions.insertBefore(mn.instructions.get(0), hook);
                LogUtils.info2Log("Finished MARKER Instrument for " + className + " " + mn.name + " " + mn.desc + " arg " + argIndex);
            }
            cn.accept(cw);
            classfileBuffer = cw.toByteArray();
            } catch (Throwable t) {
                // an exception escaping transform() makes the JVM silently keep the class untransformed, dropping
                // the other instrumentation too: keep that, and say why the marker hook is missing
                LogUtils.error("MARKER Instrument failed for " + className + ": " + t);
                t.printStackTrace();
            }
        }

        // FILE oracle, write side: FileOutputStream.write*(...) calls JDDFuzzRecord.fileWriteOracle(this) first
        if (className.equals("java.io.FileOutputStream")) {
            ClassWriter cw = newFrameComputingWriter(loader);
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassNode cn = new ClassNode();
            cr.accept(cn, ClassReader.SKIP_FRAMES);
            for (MethodNode mn : cn.methods) {
                if (mn.name.equals("write") && (mn.access & Opcodes.ACC_NATIVE) == 0) {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "datastructrue/inst/JDDFuzzRecord", "fileWriteOracle", "(Ljava/lang/Object;)V", false));
                    mn.instructions.insert(hook);
                }
            }
            cn.accept(cw);
            classfileBuffer = cw.toByteArray();
            LogUtils.info2Log("Finished write oracle instrument for " + className);
        }

        // Because the server side is strange, we design a special oracle for arbitray file write vulnerability
        if (className.equals("java.io.File")) {
            ClassWriter cw = newFrameComputingWriter(loader);
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassVisitor cv = new WriteFileOracleWrapper(cw, className);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);
            classfileBuffer = cw.toByteArray();
            LogUtils.info2Log("Finished Oracle Instrument for " + className);
        }


        return classfileBuffer;

    }

    public void doLineNumInstrument(MethodNode methodNode, InstNode instInfo) {
        AbstractInsnNode targetInsnNode = null;
        for (AbstractInsnNode node: methodNode.instructions) {
            if (node instanceof LineNumberNode) {
                if (instInfo.lineNum == ((LineNumberNode) node).line) {
                    targetInsnNode = node;
                    break;
                }
            }
        }
        if (targetInsnNode != null) {
            InsnList newInstructions = constructInstList(instInfo);
            methodNode.instructions.insert(targetInsnNode, newInstructions);
        }
    }

    public void doLabelInstrument(MethodNode targetNode, InstNode instInfo) {
        int i = 0;
        // InsnList newInstructions = new InsnList();
        AbstractInsnNode targetInsnNode = null;
        for (AbstractInsnNode node : targetNode.instructions) {
            // newInstructions.insert(node);
            if (node instanceof LabelNode) {
                if (i == instInfo.label){
                    //newInstructions.insert(mtdNode);
                    targetInsnNode = node;
                    break;
                }
                i++;
            }
        }
        if (targetInsnNode != null) {
            InsnList newInstructions = constructInstList(instInfo);
            targetNode.instructions.insert(targetInsnNode, newInstructions);
        }
    }

    public void doInstNumInstrument(MethodNode targetNode, InstNode instInfo) {
        AbstractInsnNode targetInsnNode = targetNode.instructions.get(instInfo.instNum);
        InsnList newInstructions = constructInstList(instInfo);
        targetNode.instructions.insertBefore(targetInsnNode, newInstructions);
    }

    private InsnList constructInstList(InstNode instInfo) {
        InsnList newInstructions = new InsnList();
        int bbHash;
        // 如果有一次merge，那就是正常的hash
        // 如果一次也没有merge同时都是-1，那也是正常
        if ((instInfo.isConditionInstMerge || instInfo.isArriveMtdInstMerge)) {
            bbHash = instInfo.hash;
        } else if (instInfo.conditionHash != -1 || instInfo.arrMtdHash != -1) {
            bbHash = -1;
        } else {
            bbHash = instInfo.hash;
        }
        newInstructions.add(new LdcInsnNode(bbHash));
        if (bbHash != -1) {
            LogUtils.success2Log("完成bb插桩");
        }
        newInstructions.add(new LdcInsnNode(instInfo.conditionHash));
        if (instInfo.conditionHash != -1) {
            LogUtils.success2Log("完成condition插桩");
        }
        newInstructions.add(new LdcInsnNode(instInfo.arrMtdHash));
        if (instInfo.arrMtdHash != -1) {
            LogUtils.success2Log("完成arrivedMethod插桩");
        }
        newInstructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "datastructrue/inst/JDDFuzzRecord",
                "updateArrivedBasicBlock",
                "(III)V",
                false
        ));
        return newInstructions;
    }

    /**
     * 判断一个MethodNode是否是sink方法的
     *
     * @param methodNode
     * @param sinkMtdSet
     * @return
     */
    public boolean isSinkMethodNode(MethodNode methodNode, HashSet<String> sinkMtdSet) {

        for (String mtdInfo : sinkMtdSet) {
            if (methodNode.name.equals(mtdInfo.split("#")[0])
                    && methodNode.desc.equals(mtdInfo.split("#")[1])) {
                return true;
            }
        }
        return false;

    }
}
