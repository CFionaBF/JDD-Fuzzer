package cn.edu.fudan.instrumentor;

import cn.edu.fudan.data.AgentGlobalDataContainer;
import cn.edu.fudan.data.DataUtil;
import cn.edu.fudan.instrumentor.visitor.PoCClassVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashSet;

public class HPoCInstrumentor implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {


        byte[] tmpBytes = classfileBuffer;
        // 获取所有的Sink Class的名字
        HashSet<String> instClzSet = new HashSet<>(AgentGlobalDataContainer.allSinkMtdMap.keySet());
        if (className != null) {
            className = className.replace("/", ".");
        }
        if (!instClzSet.contains(className)) {
            return classfileBuffer;
        }
        // 跳过
        // Notice: 这块代码在对recordTmpFQGadgetCallStack方法加入了限制进入次数和线程安全后其实已经没多大用处了，
        //  但还是留着吧，毕竟有些类确实没必要插桩
        if (className.startsWith("datastructrue.inst")
                || className.startsWith("cn.edu.fudan")
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
                || className.equals("java.util.Arrays")
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
            return tmpBytes;
        }
        ClassReader transformCR = new ClassReader(classfileBuffer);
        ClassWriter transformCW = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        TraceClassVisitor tcv = new TraceClassVisitor(transformCW, new PrintWriter(System.out));
        ClassVisitor cv;
        if (DataUtil.globalDebugFlag) {
            cv = new PoCClassVisitor(tcv, className);
        } else {
            cv = new PoCClassVisitor(transformCW, className);
        }
        transformCR.accept(cv, ClassReader.EXPAND_FRAMES);
        tmpBytes = transformCW.toByteArray();

        return tmpBytes;
    }
}
