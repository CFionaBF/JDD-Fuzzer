package cn.edu.fudan.instrumentor;

import cn.edu.fudan.data.AgentGlobalDataContainer;
import cn.edu.fudan.data.DataUtil;
import cn.edu.fudan.utils.LogUtils;
import datastructrue.inst.IfStmtInstRecord;
import datastructrue.inst.MethodInstRecord;
import cn.edu.fudan.instrumentor.visitor.InstClassVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * 插桩器，配合Instrumentation可以在JVM加载类时直接将记录代码插入到对应的条件语句和方法的开头中去
 */
@Deprecated
public class HInstrumentor implements ClassFileTransformer {


    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        byte[] tmpBytes = classfileBuffer;
        HashMap<String, HashSet<IfStmtInstRecord>> tmpClzConditionMap = AgentGlobalDataContainer.instInfo.classIfStmtsMap;
        HashMap<String, HashSet<MethodInstRecord>> tmpGdtMtdMap = AgentGlobalDataContainer.instInfo.classMethodsMap;
        tmpGdtMtdMap.putAll(AgentGlobalDataContainer.sinkInstInfo);

        // 获取所有的需要被插桩的类
        Set<String> tmpInstClzName = new HashSet<>(tmpClzConditionMap.keySet());
        tmpInstClzName.addAll(tmpGdtMtdMap.keySet());
        if (className != null) {
            className = className.replace("/", ".");
        }
        try {
            // 只有是指定了需要插桩的类，才会进入该if代码块
            if (tmpInstClzName.contains(className)) {
                // 调用定制化的ClassVisitor进行插桩
                LogUtils.info2Log("插桩类：" + className);
                ClassReader transformCR = new ClassReader(classfileBuffer);
                ClassWriter transformCW = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
                TraceClassVisitor tcv = new TraceClassVisitor(transformCW, new PrintWriter(System.out));
                ClassVisitor cv;
                if (DataUtil.globalDebugFlag) {
                    cv = new InstClassVisitor(tcv, tmpClzConditionMap.get(className),
                            tmpGdtMtdMap.get(className));
                } else {
                    cv = new InstClassVisitor(transformCW, tmpClzConditionMap.get(className),
                            tmpGdtMtdMap.get(className));
                }
                // EXPAND_FRAMES解决报错：LocalVariablesSorter only accepts expanded frames (see ClassReader.EXPAND_FRAMES)
                transformCR.accept(cv, ClassReader.EXPAND_FRAMES);

                tmpBytes = transformCW.toByteArray();
                LogUtils.info2Log("类" + className + "插桩完成");
            }
        } catch (Exception | Error e) {
            LogUtils.warn2Log("In `HInstrumentor.transform`: 处理类" + className + "时出错");
            e.printStackTrace();
        }


        return tmpBytes;
    }
}
