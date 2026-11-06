package cn.edu.fudan.utils;

import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import datastructrue.Pair;
import datastructrue.iocd.IOCD;
import datastructrue.iocd.unit.FieldRecord;
import datastructrue.iocd.unit.UsedSiteRecord;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MutateUtils {
    /**
     * 给定异常信息resMsg，提取和种子变异相关的信息
     *
     * @param resMsg
     * @return
     */
    public static Pair<String, String> getExceptionClzLineNumPair(String resMsg, int iocdHash) {

        Pair<String, String> resPair;
        // 利用正则表达式提取类名和对应的行号
        List<String> stackList = new ArrayList<>();
        String regex = "\\(([\\w\\W]*?)\\)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(resMsg);
        while (matcher.find()) {
            stackList.add(matcher.group());
        }
        int indexExcepClz, indexStackList;
        boolean isFound = false;
        // java.util.PriorityQueue.siftDownUsingComparator(PriorityQueue.java:721) -> java.util.PriorityQueue
        List<String> excepClzList = getExceptionClassNameList(resMsg);// 类名
        indexExcepClz = indexStackList = 0;
        HashMap<String, HashSet<Integer>> crtGdtUsedFiledMap
                = ValidatorGlobalDataContainer.getGdtUsedFiledMap(iocdHash);
        while (!isFound && !excepClzList.isEmpty()) {
            if (indexExcepClz == excepClzList.size() - 1 || indexStackList == stackList.size() - 1) {
                break;
            }
            String crtClzName = excepClzList.get(indexExcepClz);
            String excepLine = stackList.get(indexStackList);
            while ((excepLine.equals("()") || !excepLine.contains(":")) && indexStackList < stackList.size() - 1) {
                excepLine = stackList.get(++indexStackList);
            }
            if (excepLine.equals("()") || !excepLine.contains(":")) {
                break;   // no frame with a line number left (e.g. a stack trace cut at its last frames)
            }
            // java.util.PriorityQueue.siftDownUsingComparator(PriorityQueue.java:721) -> PriorityQueue.java:721
            excepLine = excepLine.replace("(", "");
            excepLine = excepLine.replace(")", "");
            String crtSite = excepLine.split(":")[1];
            // 如果当中包含这个类，说明有可能是这个类中的字段，因此遍历查找
            if (crtGdtUsedFiledMap.containsKey(crtClzName)) {
                HashSet<Integer> tmpSites = crtGdtUsedFiledMap.get(crtClzName);
                if (tmpSites.contains(Integer.parseInt(crtSite))) {
                    isFound = true;
                }
            }
            indexExcepClz++;
            indexStackList++;
        }

        if (!isFound) {
            LogUtils.warn2Log("In `getExceptionClzLineNumPair`: (Ignored it) Can not extract useful information from stacktraces.");
            // System.exit(0);
            return null;
        }

        resPair = new Pair<>(excepClzList.get(indexExcepClz-1),
                stackList.get(indexStackList-1).split(":")[1].replace(")", ""));

        return resPair;

    }

    public static List<String> getExceptionClassNameList(String resMsg) {
        Pattern CLASS_PATTERN = Pattern.compile("^([\\w$.]+)\\.");
        List<String> classes = new ArrayList<>();

        String[] lines = resMsg.split("\\r?\\n");

        for (String line : lines) {
            Matcher matcher = CLASS_PATTERN.matcher(line);
            if (matcher.find()) {
                classes.add(matcher.group(1));
            }
        }
        return classes;

    }

    /**
     * 根据clzName, line num的pair获取对应hash的gdtRecord中符合要求的FieldRecord
     *
     * @param pair
     * @return 务必保证返回的List是有顺序的，否则会有导致空指针的风险
     */
    public static LinkedList<FieldRecord> getUsedFieldRecord(Pair<String, String> pair, int iocdHash) {

        LinkedHashSet<FieldRecord> tmpHashSet = new LinkedHashSet<>();
        LinkedHashSet<UsedSiteRecord> nonGadgetUSR = new LinkedHashSet<>();
        LinkedHashSet<UsedSiteRecord> nonFoundUSR = new LinkedHashSet<>();

        IOCD tmpGdtRecord = GlobalDataContainer.getIOCDByHash(iocdHash);
        String clzName = pair.getKey();
        String lineNum = pair.getValue();
        // 首先获取所有对应类和行号中使用到的fld
        HashSet<UsedSiteRecord> allUsedSiteSet = tmpGdtRecord.getUsedFieldsRecords();
        HashSet<UsedSiteRecord> resUsedSiteSet = new HashSet<>();
        for (UsedSiteRecord usr : allUsedSiteSet) {
            if (!usr.inClassName.equals(clzName) || !usr.site.toString().equals(lineNum)) {
                continue;
            }
            resUsedSiteSet.add(usr);
        }
        for (UsedSiteRecord usedSiteRecord : resUsedSiteSet) {
            tmpHashSet.addAll(usedSiteRecord.usedFields);
        }
        if (tmpHashSet.isEmpty()) {
            LogUtils.warn2Log("In `MutateUtils.getUsedFieldRecord`: 非法的Pair：" + pair);
            // Utils.printStackTraceWithExit(new IllegalArgumentException("非法的Pair：" + pair));
        }
        return new LinkedList<>(tmpHashSet);

    }

//    /**
//     * 一个递归函数，用于获取fldInfld Record
//     *
//     * @param frSet
//     * @param useClz
//     * @param line
//     * @return 务必保证返回的Set是有顺序的，否则会有导致空指针的风险
//     */
//    private static LinkedHashSet<FieldRecord> getSubUsedFieldRecord(HashSet<FieldRecord> frSet, String useClz, Integer line) {
//
//        LinkedHashSet<FieldRecord> tmpSet = new LinkedHashSet<>();
//        for (FieldRecord fr : frSet) {
//            Set<Pair<String, Integer>> frInFRUsedSites = fr.getUsedSites();
//            for (Pair<String, Integer> usPair : frInFRUsedSites) {
//                if (!usPair.getKey().equals(useClz) || !usPair.getValue().equals(line)) {
//                    continue;
//                }
//                tmpSet.add(fr);
//                // 处理fld中fld的情况
//                if (fr.getFields() == null) {
//                    continue;
//                }
//                tmpSet.addAll(getSubUsedFieldRecord(fr.getFields(), useClz, line));
//            }
//        }
//        return tmpSet;
//
//    }

}
