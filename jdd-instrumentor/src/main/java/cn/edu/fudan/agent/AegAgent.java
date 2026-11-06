package cn.edu.fudan.agent;


import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.data.AgentGlobalDataContainer;
import cn.edu.fudan.data.DataUtil;
import cn.edu.fudan.exception.inst.BuildCFGException;
import cn.edu.fudan.instrumentor.JDDHInstrumentor;
import cn.edu.fudan.utils.ConfigUtils;
import cn.edu.fudan.utils.LogUtils;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;

public class AegAgent {

    public static int modeInt;
    public static boolean isJDDInstStart = false;
    public static int allInstClzNum;
    public static int instClzNum = 0;
    private static final HashSet<String> clzNameBlackList = new HashSet<>(Arrays.asList("$MH", "$BMH", "java.util.ArrayList"));
    public static void premain(String agentArgs, Instrumentation inst) throws BuildCFGException {
        doTransform(agentArgs, inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) throws BuildCFGException {
        doTransform(agentArgs, inst);
    }

    private static void doTransform(String agentArgs, Instrumentation inst) throws BuildCFGException {

        String[] args = new String[2];
        if (agentArgs == null || (args = agentArgs.split(",")).length < 2) {
            LogUtils.warn("Usage: java -jar <jdd-fuzzer path> -javaagent:<jdd-instrumentor path>=<0|1>,<isDebug>\n" +
                    "0 for jdd-fuzzer, 1 for PoC info collection(Deprecated), 2 for ODDFuzzer reproduce(Deprecated)");
            System.exit(0);
        }
        String mode = args[0];
        String isDebug = args[1];
        if (isDebug.equals("true")) {
            DataUtil.globalDebugFlag = true;
        } else {
            DataUtil.globalDebugFlag = false;
        }
        modeInt = Integer.parseInt(mode);
        if (modeInt == 0) {
            doAEGModeInst(inst);
        }

    }

    private static void doAEGModeInst(Instrumentation inst) throws BuildCFGException {

        LogUtils.info2Log("Instumentation Start.....");
        LogUtils.info("Instumentation Start, waiting.....");

        // Notice: 顺序务必不要弄错
        ConfigUtils.initInAgent();
        if (!ConfigUtils.checkInputDir()) System.exit(0);
        GlobalDataContainer.getInstance().buildAllIOCDCFG();
        AgentGlobalDataContainer.loadConditions();
        AgentGlobalDataContainer.extractAllSinkInstInfo();
        AgentGlobalDataContainer.extractAllIfStmtRecord();
        try {
            AgentGlobalDataContainer.initCFGAndInstInfo();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(0);
        }
        // AgentGlobalDataContainer.loadAllInstClasses();
        AgentGlobalDataContainer.loadAllJDDInstClasses();
        inst.addTransformer(new JDDHInstrumentor(), true);
        doInstrument(inst);

        LogUtils.info2Log("Instrumentation finished!");
        LogUtils.info("Instrumentation finished!");
        GlobalDataContainer.getInstance().setStartWithAgent();


    }

//    private static void doPoCInfoCollectModeInst(Instrumentation inst) {
//
//        System.out.println("进行PoC信息搜集模式的插桩");
//        // AgentGlobalDataContainer.loadAllPoCSinkClasses();
//        AgentGlobalDataContainer.initSinkInfo();
//        AgentGlobalDataContainer.loadAllJdkClasses();
//        AgentGlobalDataContainer.loadAllClassPathClasses();
//        AgentGlobalDataContainer.loadLibClasses();
//        // 配置插桩器
//        inst.addTransformer(new HPoCInstrumentor(), true);
//        // 进行插桩
//        doInstrument(inst);
//
//
//    }


    private static void doInstrument(Instrumentation inst) {

        Class[] allLoadedClz = inst.getAllLoadedClasses();
        allInstClzNum = allLoadedClz.length;
        for (Class clz : allLoadedClz) {
            String className = clz.getName();
            if (DataUtil.globalDebugFlag) {
                LogUtils.info2Log("Instrumenting: " + className + ".....");
            }
            // 避免一些特殊的类对插桩的影响
            if (blackListCheck(className)) {
                continue;
            }
            if (modeInt == 1 && !AgentGlobalDataContainer.allSinkMtdMap.containsKey(className)) {
                continue;
            }
            if (modeInt == 2
                    && !AgentGlobalDataContainer.oddfuzzClzSet.contains(className)
                    && !AgentGlobalDataContainer.allSinkMtdMap.containsKey(className)) {
                continue;
            }
            if ((++instClzNum) % 1000 == 0) {
                LogUtils.info2Log("Instrumenting " + instClzNum + "/" + AegAgent.allInstClzNum + "  " + getCurrentTime());
                LogUtils.info("Instrumenting " + instClzNum + "/" + AegAgent.allInstClzNum + "  " + getCurrentTime());
            }
            try {
                if (inst.isModifiableClass(clz)) {
                    inst.retransformClasses(clz);
                }
            } catch (Error e) {
                if (DataUtil.globalDebugFlag) {
                    e.printStackTrace();
                    LogUtils.warn2Log("处理类" + clz.getName() + "时错误" + e.getMessage());
                }
            } catch (UnmodifiableClassException e) {
                if (DataUtil.globalDebugFlag) {
                    e.printStackTrace();
                    LogUtils.warn2Log(("处理类" + clz.getName() + "时异常"));
                }
            }
        }
    }

    /**
     * 进行插桩前有些类会导致JVM直接出现段错误，因此过滤掉这些类
     *
     * @param clzName
     * @return
     */
    private static boolean blackListCheck(String clzName) {
        for (String bStr : clzNameBlackList) {
            if (clzName.contains(bStr)) {
                return true;
            }
        }
        return false;
    }

    private static String getCurrentTime() {
        Calendar currentDateTime = Calendar.getInstance();
        Date currentDate = currentDateTime.getTime();
        return currentDate.toString();
    }

}
