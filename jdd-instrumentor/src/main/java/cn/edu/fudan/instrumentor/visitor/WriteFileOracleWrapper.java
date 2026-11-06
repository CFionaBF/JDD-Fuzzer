package cn.edu.fudan.instrumentor.visitor;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import cn.edu.fudan.utils.LogUtils;

public class WriteFileOracleWrapper extends ClassVisitor {
    private String crtInstClassName;

    public WriteFileOracleWrapper(ClassVisitor classVisitor, String clzName) {
        super(Opcodes.ASM9, classVisitor);
        this.crtInstClassName = clzName;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
            String[] exceptions) {
        MethodVisitor mv = cv.visitMethod(access, name, descriptor, signature, exceptions);
        LogUtils.info2Log("[+] 进入Oracle类方法 " + this.crtInstClassName + " " + name + " " + descriptor);
        if (name.equals("<init>")) {
            LogUtils.info2Log("[+]插桩Oracle方法" + this.crtInstClassName + " " + name + " " + descriptor);
            mv = new WriteFileOracle(mv, access, name, descriptor, crtInstClassName);
        }
        return mv;
    }
}
