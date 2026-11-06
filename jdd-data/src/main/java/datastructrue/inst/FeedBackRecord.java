package datastructrue.inst;

import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.CommonUtils;

import java.util.LinkedList;

/**
 * 用于插桩到条件分支中
 * 记录Gadget的反馈信息
 */
@Deprecated
public class FeedBackRecord {

    public static boolean isStart = false;
    // Gadget执行过程中到达的条件分支语句的hashcode
    public static int arriveSiteHashCode = -1;
    // Gadget执行过程中到达的方法的hashcode
    public static int arriveMtdHashCode = -1;
    // 当前正常AEG的种子Call Stack的方法名
    public static LinkedList<String> currentSeedCS;
    public static LinkedList<Integer> currentSeedCSHash;
    // 记录是否已经到达了Gadget的sink点
    public static boolean isArriveSink = false;
    // Sink方法的hash
    public static int sinkHash = -1;
    // 当前到达的Gadget的方法名
    public static String crtArrivedMtdName = "";
    // 当前到达的Gadget的索引
    public static int crtArrivedMtdIndex = -1;
    
    public static String getArriveMethodName() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        return stack[3].getMethodName();
    }

    public static String getCrtArrivedMtdClzBelongTo() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        return stack[3].getClassName();
    }

}


