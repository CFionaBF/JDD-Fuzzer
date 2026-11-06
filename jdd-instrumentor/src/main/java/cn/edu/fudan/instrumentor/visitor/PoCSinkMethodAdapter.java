package cn.edu.fudan.instrumentor.visitor;

import cn.edu.fudan.data.record.PoCInfoFeedBackRecord;
import cn.edu.fudan.utils.LogUtils;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

public class PoCSinkMethodAdapter extends AdviceAdapter {

    public boolean isEnter = false;

    public PoCSinkMethodAdapter(MethodVisitor mv, int access, String name, String desc) {
        super(Opcodes.ASM9, mv, access, name, desc);
    }

    @Override
    protected void onMethodEnter() {

        try {
            if (isEnter) {
                return ;
            }
            LogUtils.info2Log("对Sink方法" + this.getName() + "进行Sink插桩");
            mv.visitCode();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(PoCInfoFeedBackRecord.class),
                    "recordGadgetCallStack", "()V", false);
            LogUtils.info2Log("    插桩完成");
            isEnter = true;
        } catch (Exception e) {
            e.printStackTrace();
            LogUtils.warn2Log("插桩方法" + this.getName() + "过程中出错");
            System.exit(0);
        }

    }

}
