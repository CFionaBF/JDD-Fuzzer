package cn.edu.fudan.data;

import datastructrue.inst.MethodInstRecord;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.LogUtils;

import java.io.File;
import java.util.*;

public class DataUtil {

    public static boolean globalDebugFlag = true;
    public static boolean isClassDebug = false;


    /**
     * 获取dir下所有的jar包
     *
     * @param dir
     * @return
     */
    public static List<File> getAllJarFiles(String dir) {

        List<File> files = new ArrayList<>();
        File dirFile = new File(dir);
        if (!dirFile.isDirectory()) {
            throw new IllegalArgumentException(dir + " is not a directory!");
        }

        File[] tmpFiles = dirFile.listFiles();
        List<File> jarFiles = new ArrayList<>();
        List<File> dirFiles = new ArrayList<>();
        // 获取当前目录下的jar文件
        for (File file : tmpFiles) {
            // 对于目录作暂存处理
            if (file.isDirectory()) {
                dirFiles.add(file);
                continue;
            }
            String fileName = file.getName();
            if (!fileName.endsWith("jar") && !fileName.endsWith(".war")) {
                continue;
            }
            jarFiles.add(file);
        }
        files.addAll(jarFiles);
        // 获取当前目录下的子目录中的jar文件
        for (File dFile : dirFiles) {
            List<File> tmpJarFiles = getAllJarFiles(dFile.getAbsolutePath());
            files.addAll(tmpJarFiles);
        }
        return files;
    }


    /**
     * 给定一个方法的hash，从InstrumentInfoRecord的classMethodsMap的SinkRecord查找对应方法的签名
     *
     * @param hash
     * @return
     */
    public static String getSinkMtdSigByHash(int hash) {

        String res = "notSinkMethod";
        // 遍历所有的Sink
        for (HashSet<MethodInstRecord> sinkMtdRcdSet : AgentGlobalDataContainer.sinkInstInfo.values()) {
            for (MethodInstRecord sinkMtdRcd : sinkMtdRcdSet) {
                int tmpHash = sinkMtdRcd.getHashCode();
                if (hash == tmpHash) {
                    return sinkMtdRcd.getSig();
                }
            }
        }
        return res;
    }

    /**
     * 通过callstack获取有序的Gadget Classes
     * @param callstack
     * @return
     */
    public static LinkedHashSet<String> getGadgetClassesByCallStack(LinkedList<String> callstack) {

        LinkedHashSet<String> gdtClzSet = new LinkedHashSet<>();
        for(String stack : callstack) {
            String clzName = CommonUtils.extractClassNameFromStackEntry(stack);
            if(gdtClzSet.contains(clzName)) {
                continue;
            }
            gdtClzSet.add(clzName);
        }
        return gdtClzSet;
    }

    /**
     * 根据GadgetCallStack获取这条Gadget对应的type是什么
     *
     * @param callStack
     * @return
     */
    public static String getGadgetTypeByCallStack(LinkedList<String> callStack) {
        String sinkMtdName = callStack.getFirst();
        String resType = "";
        for (String type : AgentGlobalDataContainer.sinkMtdTypeMap.keySet()) {
            HashSet<String> sinkMtdNameSet = AgentGlobalDataContainer.sinkMtdTypeMap.get(type);
            String mtdName = CommonUtils.extractMethodNameFromStackEntry(sinkMtdName);
            if (!sinkMtdNameSet.contains(mtdName)) {
                continue;
            }
            resType = type;
        }
        if (resType.equals("")) {
            LogUtils.warn("没有找到方法" + sinkMtdName + "对应于任何的Gadget类型");
        }
        return resType;

    }



}
