package cn.edu.fudan.utils;

import cn.edu.fudan.container.ConfigContainer;
import cn.edu.fudan.exception.inst.InvalidCFGStyleSigException;
import datastructrue.Pair;
import datastructrue.iocd.IOCD;
import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedList;

public class CommonUtilsTest {
    @Test
    public void isStackTraceLineValidTest() {
        String[] stackTraceLines = {
                "java.util.PriorityQueue#readObject():(Ljava/io/ObjectInputStream;)V",
                "java.util.PriorityQueue#heapify():()V",
                "java.util.PriorityQueue#siftDown():(ILjava/lang/Object;)V",
                "java.util.PriorityQueue#siftDownUsingComparator():(ILjava/lang/Object;)V",
                "org.apache.commons.collections4.comparators.TransformingComparator$abc#compare():(Ljava/lang/Object;Ljava/lang/Object;)I",
                "org.apache.commons.collections4.functors.InvokerTransformer#transform():(Ljava/lang/Object;)Ljava/lang/Object;",
                "java.lang.reflect.Method#invoke():(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                "Invalid Format" // 非法格式
        };

        for (String line : stackTraceLines) {
            if (CommonUtils.isStackTraceLineValid(line)) {
                System.out.println("有效格式: " + line);
            } else {
                System.out.println("无效格式: " + line);
            }
        }
    }

    @Test
    public void getCFGStyleMethodSigBySootSig() {
        HashSet<File> gadgetFiles = FileUtils.getJsonFilesFromDir("/Users/hn13/IdeaProjects/DesAEG_new/inputs/gadgets");
        for (File gadgetFile : gadgetFiles) {
            // int iocdHash = -1;
            IOCD iocd;
            try {
                iocd = (IOCD) FileUtils.readFromJson(gadgetFile, IOCD.class);
            } catch (Exception e) {
                LogUtils.warn("导入" + gadgetFile.getName() + "时出错：" + e.getMessage());
                continue;
            }
            LinkedList<Pair<Integer, String>> gadgetCallStack = iocd.getGadgetCallStack();
            gadgetCallStack.forEach(pair -> {
                String sootSig = pair.getValue();
                String cfgSig = null;
                try {
                    cfgSig = CommonUtils.getCFGStyleMethodSigBySootSig(sootSig);
                } catch (InvalidCFGStyleSigException e) {
                    e.printStackTrace();
                }
                System.out.println("sootSig: " + sootSig);
                System.out.println("cfgSig: " + cfgSig);
                System.out.println("--------------------------------------------------");
            });

        }
    }

}
