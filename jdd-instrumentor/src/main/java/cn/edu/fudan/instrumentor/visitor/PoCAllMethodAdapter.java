package cn.edu.fudan.instrumentor.visitor;

import cn.edu.fudan.data.DataUtil;
import cn.edu.fudan.data.record.PoCInfoFeedBackRecord;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import java.util.Arrays;

public class PoCAllMethodAdapter extends AdviceAdapter {

    public boolean isEnter = false;

    public boolean isRecordEnterVisitLineNum = false;
    private int startLineNum = -1;

    public PoCAllMethodAdapter(MethodVisitor mv, int access, String name, String desc) {
        super(Opcodes.ASM9, mv, access, name, desc);
    }

//    @Override
//    protected void onMethodEnter() {
//        try {
//            if (isEnter) {
//                return;
//            }
//            // System.out.println("对方法" + this.getName() + "进行到达记录插桩");
//            if (DataUtil.globalDebugFlag) {
//                System.out.println("========================onMethodEnter============================");
//                System.out.println("方法名" + this.getName() + "方法参数" + Arrays.toString(this.getArgumentTypes()) +
//                        "方法返回值" + this.getReturnType().toString() + "起始行号" + this.startLineNum);
//            }
//
//            isEnter = true;
//            // System.out.println("    插桩完成");
//        } catch (Exception e) {
//            e.printStackTrace();
//            System.out.println("插桩方法" + this.getName() + "过程中出错");
//            System.exit(0);
//        }
//    }

    @Override
    public void visitLineNumber(int line, Label start) {
        // 第一次进入visitLineNumber方法的应当就是源代码中方法的首行
        // 因此第一次进入的时候直接获取行号后插桩
        if (!isRecordEnterVisitLineNum) {
            startLineNum = line;
            isRecordEnterVisitLineNum = true;
            mv.visitCode();
            mv.visitLdcInsn(startLineNum);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(PoCInfoFeedBackRecord.class),
                    "recordTmpFQGadgetCallStack", "(I)V", false);
            if (DataUtil.globalDebugFlag){
                System.out.println("========================visitLineNumber============================");
                System.out.println("方法名" + this.getName() + "方法参数" + Arrays.toString(this.getArgumentTypes()) +
                        "方法返回值" + this.getReturnType().toString() + "起始行号" + this.startLineNum);
            }
        }
        mv.visitLineNumber(line, start);
    }

}
