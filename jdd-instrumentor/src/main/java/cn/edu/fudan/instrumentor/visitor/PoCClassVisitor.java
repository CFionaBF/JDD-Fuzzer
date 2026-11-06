package cn.edu.fudan.instrumentor.visitor;

import cn.edu.fudan.data.AgentGlobalDataContainer;
import cn.edu.fudan.utils.LogUtils;
import datastructrue.Pair;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashSet;

public class PoCClassVisitor extends ClassVisitor {

    private HashSet<Pair<String, String>> methodPairSet;

    public PoCClassVisitor(ClassVisitor cv, String clzName) {
        super(Opcodes.ASM9, cv);
        methodPairSet = AgentGlobalDataContainer.allSinkMtdMap.get(clzName);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor mv;
        mv = cv.visitMethod(access, name, descriptor, signature, exceptions);
        try {
            Pair<String, String> crtMtdPair = new Pair<>(name, descriptor, false);
            // 进行Sink方法的插桩
            if ((methodPairSet != null && methodPairSet.contains(crtMtdPair))
                    || isSinkMtdName(name)) {
                mv = new PoCSinkMethodAdapter(mv, access, name, descriptor);
            }
            // 对所有的方法都会进行插桩
            // mv = new PoCAllMethodAdapter(mv, access, name, descriptor);
        } catch (Exception e) {
            e.printStackTrace();
            LogUtils.warn2Log("插桩方法" + name + "过程中出错");
            System.exit(0);
        }

        return mv;
    }

    /**
     * 当signature为null的时候，只能比对方法名字了
     *
     * @param mtdName
     * @return
     */
    private boolean isSinkMtdName(String mtdName) {

        if(methodPairSet == null || methodPairSet.isEmpty()) {
            return false;
        }
        for (Pair<String, String> pair : methodPairSet) {
            if (pair.getKey().equals(mtdName)) {
                return true;
            }
        }
        return false;

    }


}
