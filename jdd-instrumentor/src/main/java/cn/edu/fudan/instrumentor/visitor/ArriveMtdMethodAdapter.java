package cn.edu.fudan.instrumentor.visitor;


import datastructrue.inst.FeedBackRecord;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import cn.edu.fudan.utils.LogUtils;

@Deprecated
public class ArriveMtdMethodAdapter extends AdviceAdapter {

    private String currentMtdName;
    private int hashCode;

    public ArriveMtdMethodAdapter(MethodVisitor mv, int access, String name, String desc, Integer hashCode) {
        super(Opcodes.ASM9, mv, access, name, desc);
        this.currentMtdName = name;
        this.hashCode = hashCode;
    }

    @Override
    protected void onMethodEnter() {
        LogUtils.info2Log("对Gadget方法" + this.currentMtdName + "进行到达性判断插桩");
        mv.visitCode();
        // mv.visitIntInsn(Opcodes.SIPUSH, this.hashCode);
        mv.visitLdcInsn(this.hashCode);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(FeedBackRecord.class),
                "updateArriveMtdHashCode", "(I)V", false);
        LogUtils.info2Log("    插桩完成");
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        super.visitMaxs(maxLocals, maxLocals);
    }

}
