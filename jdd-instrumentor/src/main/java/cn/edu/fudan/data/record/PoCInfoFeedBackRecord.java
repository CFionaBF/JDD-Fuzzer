package cn.edu.fudan.data.record;

import cn.edu.fudan.data.AgentGlobalDataContainer;
import cn.edu.fudan.data.DataUtil;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.LogUtils;
import datastructrue.Pair;

import java.util.LinkedList;
import java.util.Objects;

// A->B->C->D
// A->b->C-d
// A->a->d->c 12
// A->c->d->e->f 14
// A->a->d NoP ->c->d->B
public class PoCInfoFeedBackRecord {

    private static final int MAX_BIAS = 3;

    // 对于一个恶意的Object来说，我们是知道他的入口类是什么的(即调用readObject方法的类)
    // Thinking: 对于其他情形的反序列化入口点，这个还需要做细粒度的处理
    private static String crtEntryClassName;
    private static String proxyHandlerName;
    private static String crtSinkClassName;
    // 做两轮的记录，第一轮单纯记录CallStack，第二轮记录全量CallStack
    private static int recMode = -1;
    private static int crtGdtHash = -1;
    private static boolean isFQRecStart = false;
    private static int biasValue = 0;
    private static String crtGgtMtdName;
    private static String nextGdtMtdName;
    private static int crtMtdIndex;
    private static boolean isEntered = false;
    // 用于暂存全量Gadget调用栈
    private static LinkedList<Pair<String, Integer>> tmpFQCallStack = new LinkedList<>();
    // 在构造全量Gadget调用栈时，需要使用到真正的Gadget CallStack作为对照，因此缓存一个
    private static LinkedList<String> tmpCrtGdtCallStack = new LinkedList<>();
    // 用于记录当前的Gadget是什么类型
    private static String crtGadgetType = "-1";


    public static LinkedList<String> getTmpCrtGdtCallStack() {
        return tmpCrtGdtCallStack;
    }

    public static LinkedList<Pair<String, Integer>> getTmpFQCallStack() {
        return tmpFQCallStack;
    }

    public static String getCrtGadgetType() {
        return crtGadgetType;
    }

    /**
     * @param tmpSubStack 这个CallStack是在确认了当前的call stack确实是Gadget CallStack的时候才是有效的
     * @return 是否是Gadget的调用栈
     */
    private static boolean isRealGadgetCallStack(LinkedList<String> tmpSubStack) {
        // 获取到达isRealGadgetCallStack方法时候的调用栈
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        // 需要判断触发SinkMethod的是否是用户指定的SinkClass
        if (stack.length > 4) {
            String sinkInvokeStack = stack[4].toString();
            String sinkInvokeClzName = CommonUtils.extractClassNameFromStackEntry(sinkInvokeStack);
            if (crtSinkClassName != null && !sinkInvokeClzName.equals(crtSinkClassName)) {
                return false;
            }
        }
        // 遍历这个CallStack，检查是否符合我们所需要的CallStack的标准
        for (int j = 3; j < stack.length; j++) {
            String mtdName = stack[j].getMethodName() + "(";
            String clzName = stack[j].getClassName();
            tmpSubStack.add(stack[j].toString());
            // 判断方法和所属的类是否符合我们的要求
            // 如果是从一些readObject执行过程的分支方法进入invoke的话，那就掐掉
            if (mtdName.equals("invokeReadObject(")) {
                return false;
            }
            if (!mtdName.equals("readObject(") && !mtdName.equals("readExternal(")) {
                continue;
            }
            if (!clzName.equals(crtEntryClassName)
                    && !(proxyHandlerName != null && (clzName.equals(proxyHandlerName)
                    || ClassUtils.isSubOrImplClass(clzName, proxyHandlerName)))
                    && !ClassUtils.isSubOrImplClass(clzName, crtEntryClassName)) {
                continue;
            }
            return true;
        }
        return false;
    }

    @Deprecated
    private static boolean isNestedRecord(StackTraceElement[] stacks) {

        int num = 0;
        for (StackTraceElement stack : stacks) {
            if (stack.toString().contains("recordTmpFQGadgetCallStack")) {
                num++;
            }
            if (num > 1) {
                return true;
            }
        }
        return false;

    }

    /**
     * 0表示只记录Gadget CallStack，1表示记录全量Gadget CallStack
     *
     * @param mode
     */
    public static void setRecMode(int mode) {
        recMode = mode;
    }

    /**
     * 被插桩到Sink Method的方法，记录Gadget CallStack
     */
    public static void recordGadgetCallStack() {
        if (!(recMode == 0)) {
            return;
        }
        LinkedList<String> tmpCallStack = new LinkedList<>();
        if (isRealGadgetCallStack(tmpCallStack)) {
            AgentGlobalDataContainer.addPocGadgetCallStack(tmpCallStack);
            crtGadgetType = DataUtil.getGadgetTypeByCallStack(tmpCallStack);
            crtGdtHash = tmpCallStack.hashCode();
            tmpCrtGdtCallStack = tmpCallStack;
            setRecMode(-1);
        }
    }

    /**
     * 插桩到所有的方法开头用于记录全量调用栈的方法(暂存)
     */
    public static synchronized void recordTmpFQGadgetCallStack(int methodEnterLineNum) {
        try {
            // 如果还没有开始全量Gadget的搜集，就直接返回
            if (!(recMode == 1)) {
                return;
            }
            // 避免重复进入
            if (isEntered) {
                return;
            }
            isEntered = true;
            // 如果遇到entry类的readObject方法，那么就开始进行记录
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            String crtStack = stack[2].toString();
            String invokeClzName = stack[2].getClassName();
            String invokeMtdName = stack[2].getMethodName();
            if (invokeClzName.equals(crtEntryClassName) && invokeMtdName.equals("readObject")) {
                isFQRecStart = true;
                crtMtdIndex = tmpCrtGdtCallStack.size() - 1;
                crtGgtMtdName = tmpCrtGdtCallStack.get(crtMtdIndex);
                nextGdtMtdName = tmpCrtGdtCallStack.get(crtMtdIndex - 1);
            }
            // 如果没有开始，就返回
            if (!isFQRecStart) {
                isEntered = false;
                return;
            }
            // 开始了，就往里面添加调用的方法了
            if (!isStackEquals(crtStack, crtGgtMtdName)
                    && !isStackEquals(crtStack, nextGdtMtdName)) {
                // Proxy会造成crtGgtMtdName和nextGdtMtdName无法正常迭代，因此要做特殊处理
                if (isSubGadgetCallStack(stack)) {
                    biasValue = 0;
                    crtMtdIndex = getStackIndex(crtStack);
                    if (crtMtdIndex == -1) {
                        LogUtils.warn("In `PoCInfoFeedBackRecord.recordTmpFQGadgetCallStack`: error!");
                    }
                    crtGgtMtdName = crtStack;
                    // 如果已经到达了最后一个Sink Method那就直接记录后返回
                    if (crtGgtMtdName.equals(tmpCrtGdtCallStack.getFirst())) {
                        recMode = -1;
                        String crtStackWithLineNum = crtStack + "[" + methodEnterLineNum + "]";
                        tmpFQCallStack.add(new Pair<>(crtStackWithLineNum, biasValue, false));
                        isEntered = false;
                        return;
                    }
                    nextGdtMtdName = tmpCrtGdtCallStack.get(crtMtdIndex - 1);
                } else {
                    biasValue = calculateBiasValue(stack, crtGgtMtdName);
                    if (biasValue == -1) {
                        isEntered = false;
                        return;
                    }
                }
            } else if (isStackEquals(crtStack, nextGdtMtdName)) {
                biasValue = 0;
                crtMtdIndex--;
                crtGgtMtdName = tmpCrtGdtCallStack.get(crtMtdIndex);
                // 如果已经到达了最后一个Sink Method那就直接记录后返回
                if (crtGgtMtdName.equals(tmpCrtGdtCallStack.getFirst())) {
                    recMode = -1;
                    String crtStackWithLineNum = crtStack + "[" + methodEnterLineNum + "]";
                    tmpFQCallStack.add(new Pair<>(crtStackWithLineNum, biasValue, false));
                    isEntered = false;
                    return;
                }
                isEntered = false;
                nextGdtMtdName = tmpCrtGdtCallStack.get(crtMtdIndex - 1);
            }
            if (biasValue <= MAX_BIAS) {
                // 因为限制了偏移量+偏移次数不一定只有一次，因此需要区分每次的偏移
                // 应当认为每次偏移量为1时，在主干gadget方法中调用了新方法
                if (biasValue == 1 && !tmpFQCallStack.getLast().equals(crtGgtMtdName)) {
                    tmpFQCallStack.add(new Pair<>("NOP", -1, false));
                }
                String crtStackWithLineNum = crtStack + "[" + methodEnterLineNum + "]";
                tmpFQCallStack.add(new Pair<>(crtStackWithLineNum, biasValue, false));
            }
        } catch (Error | Exception e) {
            e.printStackTrace();
        }
        isEntered = false;

    }

    /**
     * 记录全量Gadget CallStack
     */
    public static void recordFQGadgetCallStack() {
        AgentGlobalDataContainer.addPocFQGadgetCallStack(tmpFQCallStack, crtGdtHash);
    }


    /**
     * 清空当前静态类里的所有全局变量
     */
    public static void reset() {
        crtEntryClassName = "";
        crtGdtHash = -1;
        recMode = -1;
        isFQRecStart = false;
        biasValue = 0;
        crtGgtMtdName = "";
        nextGdtMtdName = "";
        tmpFQCallStack = new LinkedList<>();
        tmpCrtGdtCallStack = new LinkedList<>();
    }

    /**
     * 更新当前正在收集的Gadget的Entry Class是什么
     *
     * @param clzName
     */
    public static void updateCrtEntryClassName(String clzName, String handlerName) {
        crtEntryClassName = clzName;
        proxyHandlerName = handlerName;
    }

    public static void updateCrtSinkClassName(String clzName) {
        crtSinkClassName = clzName;
    }

    /**
     * 用于计算调用crtGdtMtd偏离主干Gadget的偏移量是多大
     *
     * @param stack
     * @param crtGdtMtd
     */
    public static int calculateBiasValue(StackTraceElement[] stack, String crtGdtMtd) {

        int bias = 0;
        boolean isFound = false;
        for (int i = 3; i < stack.length; i++) {
            bias++;

            if (!isStackEquals(stack[i].toString(), crtGdtMtd)) {
                continue;
            }
            isFound = true;
            break;
        }
        if (!isFound) {
            return -1;
        }
        return bias;

    }

    /**
     * 判断两个Gadget Stack是否是同一个
     * 本质是判断类名和方法名是否相等
     *
     * @param stack
     * @param gdtStack
     * @return
     */
    private static boolean isStackEquals(String stack, String gdtStack) {

        String crtClzName = CommonUtils.extractClassNameFromStackEntry(stack);
        String crtMtdName = CommonUtils.extractMethodNameFromStackEntry(stack);
        String crtGdtClzName = CommonUtils.extractClassNameFromStackEntry(gdtStack);
        String crtGdtMtdName = CommonUtils.extractMethodNameFromStackEntry(gdtStack);

        return Objects.equals(crtClzName, crtGdtClzName) && Objects.equals(crtMtdName, crtGdtMtdName);

    }

    private static boolean isSubGadgetCallStack(StackTraceElement[] stack) {

        LinkedList<String> tmpSubStack = new LinkedList<>();
        for (int i = 0, j = 2; i < tmpCrtGdtCallStack.size(); i++) {
            String crtStack = stack[j].toString();
            String gdtMtdSig = tmpCrtGdtCallStack.get(i);
            if (isStackEquals(crtStack, gdtMtdSig)) {
                tmpSubStack.add(gdtMtdSig);
                j++;
            } else {
                // 如果tmpSubStack不为空的情况到达了这个分支说明调用栈中断了，所以不是符合要求的调用栈
                if (!tmpSubStack.isEmpty()) {
                    return false;
                }
            }
        }
        // 显然，如果没有一个subStack说明不是符合要求的调用栈
        return !tmpSubStack.isEmpty();

    }

    private static int getStackIndex(String stack) {
        int res = -1;
        for (int i = 0; i < tmpCrtGdtCallStack.size(); i++) {
            String gdtStack = tmpCrtGdtCallStack.get(i);
            if (isStackEquals(stack, gdtStack)) {
                res = i;
                break;
            }
        }
        return res;
    }


}
