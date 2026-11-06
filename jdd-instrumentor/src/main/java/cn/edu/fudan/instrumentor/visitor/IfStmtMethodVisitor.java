package cn.edu.fudan.instrumentor.visitor;

import datastructrue.inst.FeedBackRecord;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import cn.edu.fudan.utils.LogUtils;

import java.util.HashMap;

@Deprecated
public class IfStmtMethodVisitor extends MethodVisitor {

    // [<行号-hashcode>]
    private HashMap<Integer, Integer> instSiteMap;
    private String currentMtdName;

    public IfStmtMethodVisitor(MethodVisitor mv, HashMap<Integer, Integer> instSiteMap, String name) {
        super(Opcodes.ASM9, mv);
        this.instSiteMap = instSiteMap;
        this.currentMtdName = name;
    }

    @Override
    public void visitLineNumber(int line, Label start) {

        // 如果行号对上了，就插入FeedBackRecord#updateArriveSiteHashCode的静态方法调用
        // 后续Fuzzer便可以从FeedBackRecord.arriveSiteHashCode判断生成出来的Gadget到达了什么地方
        if (instSiteMap.containsKey(line)) {
            LogUtils.info2Log("对方法：" + this.currentMtdName + "进行IfStmt插桩");
            LogUtils.info2Log("    插桩行号为" + line);
            Integer tmpHashCode = instSiteMap.get(line);
            // mv.visitIntInsn(Opcodes.SIPUSH, tmpHashCode);
            mv.visitLdcInsn(tmpHashCode);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(FeedBackRecord.class),
                    "updateArriveSiteHashCode", "(I)V", false);
            LogUtils.info2Log("    插桩完成");
        }

        mv.visitLineNumber(line, start);
    }

}
