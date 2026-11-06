package cn.edu.fudan.instrumentor.visitor;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.AdviceAdapter;

import datastructrue.inst.JDDFuzzRecord;

import org.objectweb.asm.Type;

public class WriteFileOracle extends AdviceAdapter {

    String crtClzName;
    String crtMtdName;
    String crtDesc;
    Type[] argumentTypes;
    int maxLocals;
    boolean isStatic;

    protected WriteFileOracle(MethodVisitor methodVisitor, int access, String name, String descriptor, String clzName) {
        super(ASM9, methodVisitor, access, name, descriptor);
        this.crtClzName = clzName;
        this.crtMtdName = name;
        this.crtDesc = descriptor;
    }

    @Override
    protected void onMethodEnter() {
        Type[] argTypes = Type.getArgumentTypes(crtDesc);
            argumentTypes = new Type[argTypes.length];
            for (int i = 0; i < argTypes.length; i++) {
                argumentTypes[i] = argTypes[i];
            }
            maxLocals = argumentTypes.length + (isStatic ? 0 : 1);
            // Load number of arguments
            mv.visitIntInsn(BIPUSH, maxLocals);
            // Create Object[] array
            mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            mv.visitVarInsn(ASTORE, maxLocals);

            // Load 'this' reference if method is not static
            if (!isStatic) {
                mv.visitVarInsn(ALOAD, maxLocals);
                mv.visitIntInsn(BIPUSH, 0);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitInsn(AASTORE);
            }

            // Push each method parameter onto the Object[] array
            int start = isStatic ? 0 : 1;
            for (int i = start; i < maxLocals; i++) {
                mv.visitVarInsn(ALOAD, maxLocals);
                mv.visitIntInsn(BIPUSH, i);

                Type tp = argumentTypes[i - start];

                // Boxing primitive types
                if (tp.equals(Type.BOOLEAN_TYPE)) {
                    mv.visitVarInsn(ILOAD, i);
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean",
                            "valueOf", "(Z)Ljava/lang/Boolean;", false);
                }
                else if (tp.equals(Type.BYTE_TYPE)) {
                    mv.visitVarInsn(ILOAD, i);
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte",
                            "valueOf", "(B)Ljava/lang/Byte;", false);
                }
                else if (tp.equals(Type.CHAR_TYPE)) {
                    mv.visitVarInsn(ILOAD, i);
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character",
                            "valueOf", "(C)Ljava/lang/Character;", false);
                }
                else if (tp.equals(Type.SHORT_TYPE)) {
                    mv.visitVarInsn(ILOAD, i);
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short",
                            "valueOf", "(S)Ljava/lang/Short;", false);
                }
                else if (tp.equals(Type.INT_TYPE)) {
                    mv.visitVarInsn(ILOAD, i);
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer",
                            "valueOf", "(I)Ljava/lang/Integer;", false);
                }
                else if (tp.equals(Type.LONG_TYPE)) {
                    mv.visitVarInsn(LLOAD, i);
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long",
                            "valueOf", "(J)Ljava/lang/Long;", false);
                    i++;
                }
                else if (tp.equals(Type.FLOAT_TYPE)) {
                    mv.visitVarInsn(FLOAD, i);
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float",
                            "valueOf", "(F)Ljava/lang/Float;", false);
                }
                else if (tp.equals(Type.DOUBLE_TYPE)) {
                    mv.visitVarInsn(DLOAD, i);
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double",
                            "valueOf", "(D)Ljava/lang/Double;", false);
                    i++;
                }
                else {
                    mv.visitVarInsn(ALOAD, i);
                }

                mv.visitInsn(AASTORE);
            }
            String className = Type.getInternalName(JDDFuzzRecord.class);
            String message = "FILE" + " " + constructClzMtdPair(crtClzName, crtMtdName, crtDesc);
            mv.visitLdcInsn(message);
            mv.visitVarInsn(ALOAD, maxLocals);

            mv.visitMethodInsn(INVOKESTATIC, className, "fileOracle",
                    "(Ljava/lang/String;[Ljava/lang/Object;)V", false);
    }

    public static String constructClzMtdPair(String clzName, String mtdName, String desc) {
        return clzName + "#" + mtdName + "#" + desc;
    }

}
