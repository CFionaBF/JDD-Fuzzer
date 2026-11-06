package datastructrue.inst;

import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.data.distance.data.BasicBlock;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.LogUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;

/**
 * 用于插桩到条件分支中
 * 记录Gadget的反馈信息
 */
public class JDDFuzzRecord {

    public static String crtProtocol = "";
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
    public static int maxArrivedMtdIndex = -1;

    // public static ArrayList<Integer> arrivedBBHashArr = new ArrayList<>();
    public static LinkedHashSet<Integer> arrivedBBHashArr = new LinkedHashSet<>();
    public static LinkedList<String> crtClzMtdWithSigSet = new LinkedList<>();

    /**
     * IMPORTANT: This method must be called before the next stage starts.
     */
    public static void resetRuntimeFeedbackRecord() {
        arrivedBBHashArr = new LinkedHashSet<>();
        arriveSiteHashCode = -1;
    }

    // 刷新所有的记录
    public static void resetFeedBackRecord() {
        maxArrivedMtdIndex = -1;
        arriveSiteHashCode = -1;
        arriveMtdHashCode = -1;
        crtArrivedMtdName = "";
        crtArrivedMtdIndex = -1;
        isArriveSink = false;
        arrivedBBHashArr = new LinkedHashSet<>();
        crtClzMtdWithSigSet = new LinkedList<>();
        isStart = false;
    }

    public static void updateArrivedBasicBlock(int bbHash, int conditionHash, int arrivedMtdHash) {
        // 没有开始exploit验证或者不是Gadget就直接跳过
        if (!isStart || !isGadgetCallStack()) {
            return;
        }
        if (bbHash != -1) {
            JDDFuzzRecord.arrivedBBHashArr.add(bbHash);
        }
        if (conditionHash != -1) {
            arriveSiteHashCode = conditionHash;
        }
        if (arrivedMtdHash != -1) {
            String tmpMtdName = getArriveMethodName();
            String tmpMtdClzBelongTo = getCrtArrivedMtdClzBelongTo();
            // Thinking：这个地方有个问题: 是否会重复调用某个方法(2023/11/27目前不会有这个问题)
            int tmpIndex = getIndexOfArrivedMethod(tmpMtdName, tmpMtdClzBelongTo);
            // 不记录比当前GadgetMtd方法前面的Hash
            if (tmpIndex > crtArrivedMtdIndex) {
                crtArrivedMtdIndex = tmpIndex;
                if (tmpIndex > maxArrivedMtdIndex) {
                    // furthest gadget method reached by any execution of this seed so far
                    maxArrivedMtdIndex = tmpIndex;
                    cn.edu.fudan.utils.LogUtils.info("Reached gadget method #" + tmpIndex + ": " + tmpMtdClzBelongTo + "#" + tmpMtdName);
                }
            } else {
                return;
            }
            arriveMtdHashCode = arrivedMtdHash;
        }

    }

    // The current IOCD's sink type has an effect oracle (FILE: the payload file is written, INVOKE: the
    // probe method is called). Its sink then counts only by that effect: an app-level sink method of the
    // IOCD (e.g. ThresholdingOutputStream.write) can be entered without the effect happening.
    public static boolean isEffectOracleSink = false;

    public static boolean hasEffectOracle(String sinkType) {
        // CUSTOM: its families that have a payload (groovy closure) fire the INVOKE probe; the others have no
        // payload yet and so cannot reach an effect, which keeps the generic entry hook from counting them
        return "FILE".equals(sinkType) || "INVOKE".equals(sinkType) || "CUSTOM".equals(sinkType);
    }

    // per IOCD: the CUSTOM families verified by the marker oracle (see hasMarkerOracle) have no effect oracle
    public static boolean hasEffectOracle(String sinkType, java.util.Collection<String> sinkCalls) {
        return hasEffectOracle(sinkType) && !hasMarkerOracle(sinkType, sinkCalls);
    }

    // ---- reachability marker oracle (JNDI / CUSTOM / CLOJURE families) ----------------------------------
    // A marker-oracle sink counts only when the relevant sink argument/receiver carries this run's inert
    // marker token AND the call stack runs the IOCD chain. The token is never code, a resolvable name, a
    // loadable class or a reachable host; after the sink is entered it is expected to fail. The generic
    // entry hook (doSinkArrivedFeedBack) must NOT set isArriveSink for these, or an unmarked entry would
    // count: isMarkerOracleSink makes it return early, exactly like isEffectOracleSink.
    public static final String MARKER_PREFIX = "JDD-REACH-";
    // this IOCD's expected token ("JDD-REACH-<iocdHash>"), set by the sink-payload generator in the same
    // JVM; left across resetSinkRecord() because it is a property of the IOCD, not of one execution.
    public static volatile String expectedMarker = null;
    public static boolean isMarkerOracleSink = false;
    // set only by the marker oracle, when the marker is seen at the sink: "the sink argument we control
    // carried our data". Distinct from isArriveSink so "entered" and "entered carrying our data" stay apart.
    public static boolean isSinkFieldControlled = false;

    // sink types verified by the marker oracle. A family adds its sink type here when its generator and
    // instrumentation hook are in place; an un-wired sub-family of the same sink type simply never sets
    // expectedMarker / never gets a marker hook, so it stays a miss (no false positive).
    private static final java.util.Set<String> MARKER_ORACLE_SINK_TYPES =
            new java.util.HashSet<>(java.util.Arrays.asList("JNDI", "CLOJURE"));

    // CUSTOM families verified by the marker oracle, by the sink call of the IOCD's sink records (jimpleUnitInfo):
    // EL ValueExpression.getValue (Myfaces) and BeanShell This.invokeMethod. The other CUSTOM families (groovy
    // closure call, fastjson2 ObjectWriter.write) are verified by the probe being called.
    private static final String[] MARKER_ORACLE_CUSTOM_CALLS = {"<javax.el.ValueExpression: ", "<bsh.This: ", "<bsh.XThis: "};

    public static boolean hasMarkerOracle(String sinkType) {
        return MARKER_ORACLE_SINK_TYPES.contains(sinkType);
    }

    public static boolean hasMarkerOracle(String sinkType, java.util.Collection<String> sinkCalls) {
        if (hasMarkerOracle(sinkType)) {
            return true;
        }
        if (!"CUSTOM".equals(sinkType) || sinkCalls == null) {
            return false;
        }
        for (String call : sinkCalls) {
            for (String prefix : MARKER_ORACLE_CUSTOM_CALLS) {
                if (call != null && call.contains(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Marker oracle for sinks whose marked argument is a String (name lookups, host resolution, clojure
     * eval). A pure read: compares the argument to this run's token and, on a match, records that the sink
     * was reached carrying our data, along the stack it was reached by (recordSinkHit checks the IOCD
     * chain). Never changes what the payload does.
     */
    public static void sinkMarkerOracle(Object arg) {
        if (!isStart || isInObserve || expectedMarker == null) {
            return;
        }
        startObserve();
        try {
            // the marked argument is either the String itself (name lookups) or a javax.naming.Reference
            // carrying it as its class name (getObjectInstance); both are plain reads
            String seen = null;
            if (arg instanceof String) {
                seen = (String) arg;
            } else if (arg instanceof javax.naming.Reference) {
                seen = ((javax.naming.Reference) arg).getClassName();
            } else if (arg != null) {
                // a marked receiver: an EL expression's text (getExpressionString) or a BeanShell object's
                // namespace name (getNameSpace().getName()); plain getters, read reflectively since the app
                // classes are not on this module's classpath
                seen = readMarkerText(arg);
            }
            if (seen != null && seen.contains(expectedMarker)) {
                isSinkFieldControlled = true;
                isArriveSink = true;
                recordSinkHit(new Throwable().getStackTrace(), TriggeredChain.HIT_SINK);
            }
        } catch (Throwable ignored) {
            // the oracle must never change what the payload does
        } finally {
            endObserve();
        }
    }

    private static String readMarkerText(Object obj) throws Exception {
        Class<?> c = obj.getClass();
        if (hasClassNamed(c, "sun.rmi.transport.LiveRef")) {
            // JRMP: the endpoint of a LiveRef, "host:port". LiveRef.getChannel (JRMPClient) is entered on a LiveRef whose
            // host is the marker, LiveRef.exportObject (JRMPListener) on one whose port is the marker; the payload
            // generator sets expectedMarker to "<host>:" or ":<port>" accordingly. Only while a stream is being read: the
            // DGC client also renews the refs it registered from its own thread (RenewCleanThread), which is a
            // consequence of the read, not a gadget chain rooted in it.
            if (!isReadingStream()) {
                return null;
            }
            Class<?> live = c;
            while (!live.getName().equals("sun.rmi.transport.LiveRef")) {
                live = live.getSuperclass();
            }
            Object ep = accessible(live.getDeclaredMethod("getEndpoint")).invoke(obj);    // package-private
            if (ep == null) {
                return null;
            }
            return accessible(ep.getClass().getMethod("getHost")).invoke(ep) + ":"
                    + accessible(ep.getClass().getMethod("getPort")).invoke(ep);
        }
        if (hasClassNamed(c, "javax.el.ValueExpression")) {
            return (String) accessible(c.getMethod("getExpressionString")).invoke(obj);
        }
        if (hasClassNamed(c, "bsh.This")) {
            Object ns = accessible(c.getMethod("getNameSpace")).invoke(obj);
            return ns == null ? null : (String) accessible(ns.getClass().getMethod("getName")).invoke(ns);
        }
        return null;
    }

    // whether this thread is deserializing (a Java or Hessian input stream is on its stack)
    private static boolean isReadingStream() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String clz = e.getClassName();
            if (clz.startsWith("java.io.ObjectInputStream") || clz.startsWith("com.caucho.hessian.io.")) {
                return true;
            }
        }
        return false;
    }

    // public methods of a non-public class (e.g. MyFaces' ELText$LiteralValueExpression)
    private static java.lang.reflect.Method accessible(java.lang.reflect.Method m) {
        m.setAccessible(true);
        return m;
    }

    private static boolean hasClassNamed(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    public static void doSinkArrivedFeedBack() {
        if (isEffectOracleSink || isMarkerOracleSink) {
            return;
        }
        if (isStart && isGadgetCallStack()) {
            if (!isArriveSink) {
                cn.edu.fudan.utils.LogUtils.debug("Reached the sink");
            }
            isArriveSink = true;
            // isGadgetCallStack() has just matched the stack against the IOCD chain
            if (!isSinkRouteOk) {
                isSinkRouteOk = true;
                List<String> chain = TriggeredChain.extract(new Throwable().getStackTrace(), TriggeredChain.HIT_SINK);
                sinkHits.add(new SinkHit(chain, true, currentSeedCS.size() + "/" + currentSeedCS.size()));
            }
        }
    }

    // 设置当前正在变异的种子实际的callstack
    public static void setCurrentSeedCS(LinkedList<String> cs) {
        currentSeedCS = cs;
    }

    public static void setCurrentSeedCSHash(LinkedList<Integer> csHash) {
        currentSeedCSHash = csHash;
    }

    // 判定此时的callStack是否和Gadget的一致
    public static boolean isGadgetCallStack() {

        // 为空不用做比较直接返回
        if (currentSeedCS == null || currentSeedCS.isEmpty()) {
            return false;
        }
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        LinkedList<String> tmpSubStack = new LinkedList<>();
        for (int i = currentSeedCS.size() - 1, j = 3; i >= 0; i--) {
            if (CommonUtils.isSootLambdaClass(currentSeedCS.get(i))) {
                continue;   // never on a stack, see CommonUtils.isSootLambdaClass
            }
            // 避免比较put和readObject(java.lang.In"PUT"Stream)时出错
            String mtdName = stack[j].getMethodName() + "(";
            if (crtProtocol.equals("hessian") && mtdName.equals("put(")) {
                String lastMtdName = stack[j + 1].getMethodName() + "(";
                if (!lastMtdName.equals("readMap(")) {
                    return false;
                }
            }
            String clzNameOfMtd = stack[j].getClassName();
            if (isArrivedClojureSink(mtdName, clzNameOfMtd)) {
                return true;
            }
            String gdtMtdSig = currentSeedCS.get(i);
            gdtMtdSig = gdtMtdSig.replace("'", "");
            String gdtMtdDecClzName = CommonUtils.getDeclaredClzBySig(gdtMtdSig);
            if (gdtMtdSig.contains(mtdName)
                    && (gdtMtdDecClzName.equals(clzNameOfMtd)
                    || ClassUtils.isSubOrImplClass(gdtMtdDecClzName, clzNameOfMtd))) {
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

    public static double getCurrentSeedDistance(HashMap<Integer, BasicBlock> bscBlockMap) {

        double distance = 0;
        for (int hash : arrivedBBHashArr) {
            BasicBlock crtBB = bscBlockMap.get(hash);
            if (Double.isInfinite(crtBB.interDistance)) {
                continue;
            }
            distance += crtBB.interDistance;
        }
        return distance/arrivedBBHashArr.size();

    }

    public static int getArriveGdtMtdHash() {
        return crtArrivedMtdIndex + 1;
    }
    public static int getArriveBscIfStmtHash() {
        return arriveSiteHashCode;
    }

    public static String getArriveMethodName() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        return stack[3].getMethodName();
    }

    public static String getCrtArrivedMtdClzBelongTo() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        return stack[3].getClassName();
    }

    public static int getIndexOfArrivedMethod(String mtdName, String clzNameOfMtd) {
        int index = 0;
        for (String gdtStack : currentSeedCS) {
            if (gdtStack.contains(mtdName+"(")) {
                if (gdtStack.contains(clzNameOfMtd)) {
                    // 考虑内部类的情况，避免FP
                    if (gdtStack.contains("$")) {
                        if (clzNameOfMtd.contains("$")) {
                            return index;
                        }
                    } else {
                        return index;
                    }
                }
            }
            index++;
        }
        return -1;
    }

    static boolean isInObserve = false;

    private static void startObserve() {
        isInObserve = true;
    }

    private static void endObserve() {
        isInObserve = false;
    }

    public static void fileOracle(String msg, Object[] args) {
        if (!isStart) return;
        if (isInObserve) {
            return;
        }
        startObserve();
        if (msg.contains("java.io.File#<init>#")) {
            // only a candidate: the file has to be written for real, see confirmFileOracle()
            checkFile(msg, args);
        } else {
            // 默认情况下触发就等于observe
            LogUtils.success2Log("[+] OracleObserver: Observed a file operation: " + msg);
            JDDFuzzRecord.isArriveSink = true;
        }

        endObserve();
    }

    // FILE oracle, write side: FileOutputStream.write* calls JDDFuzzRecord.fileWriteOracle(this) on entry
    // (JDDHInstrumentor). The stack of the first write to each payload path is the chain the payload
    // really ran up to the write (TriggeredChain.HIT_WRITE); confirmFileOracle() uses it for the files
    // the execution did change.
    private static final ArrayList<String> writtenEvilPaths = new ArrayList<>();
    private static final ArrayList<StackTraceElement[]> writtenEvilStacks = new ArrayList<>();
    private static java.lang.reflect.Field fosPathField;

    public static void fileWriteOracle(Object fos) {
        if (!isStart || isInObserve) {
            return;
        }
        startObserve();
        try {
            if (fosPathField == null) {
                java.lang.reflect.Field f = java.io.FileOutputStream.class.getDeclaredField("path");
                f.setAccessible(true);
                fosPathField = f;
            }
            Object p = fosPathField.get(fos);
            if (p instanceof String && ((String) p).contains(EVIL_FILE_MARKER)) {
                String path = new File((String) p).getAbsolutePath();
                if (!writtenEvilPaths.contains(path)) {
                    writtenEvilPaths.add(path);
                    writtenEvilStacks.add(new Throwable().getStackTrace());
                }
            }
        } catch (Throwable ignored) {
            // the oracle must never change what the payload does
        } finally {
            endObserve();
        }
    }

    private static boolean checkFile(String message, Object[] args) {
        String path = "";
        // 获取路径
        if (message.contains("(Ljava/lang/String;)V")) {
            // public File(String pathname)
            if (args[1] instanceof String) {
                path = (String) args[1];
            }
        } else if (message.contains("(Ljava/lang/String;Ljava/lang/String;)V")) {
            // public File(String parent, String child)
            if (args[1] instanceof String) {
                path = (String) args[1];
            }
            if (args[2] instanceof String) {
                path += File.separator + (String) args[2];
            }
        } else if (message.contains("(Ljava/io/File;Ljava/lang/String;)V")) {
            // public File(File parent, String child)
            if (args[1] instanceof File) {
                path = ((File) args[1]).getPath() + File.separator;
            }
            if (args[2] instanceof String) {
                path += (String) args[2];
            }
        }
        if (path.contains(EVIL_FILE_MARKER)) {
            System.out.println("[+] OracleObserver: Found a file path containing '" + EVIL_FILE_MARKER + "': " + path);
            observedEvilPaths.add(path);
            observedEvilStacks.add(new Throwable().getStackTrace());
            // state of the file before the payload could touch it (a File is also built to read a file)
            File before = new File(path);
            observedEvilStates.add(before.exists() ? before.lastModified() + ":" + before.length() : "");
            return true;
        }
        return false;
    }

    // FILE oracle. A java.io.File built from a path containing the marker only shows that the payload
    // path reached a file API; the open/write can still fail (e.g. the payload path resolves to a
    // directory), and the File may be built to read the file. So the path is only recorded during the
    // execution, and the sink counts as reached once the file was really created or changed by it.
    // (A file can already exist: writeObject() methods of the chain run parts of it on the in-memory
    // seed while it is serialized, which can write the payload file before the execution starts.)
    public static final String EVIL_FILE_MARKER = "jdd_evil_file";
    // directory the FILE payloads write into (SinkPayloadsGenerator), emptied before each execution.
    // -Djdd.evilFileDir gives each parallel fuzzer its own (an absolute path containing the marker).
    public static final String EVIL_FILE_DIR = evilFileDir();

    private static String evilFileDir() {
        String dir = System.getProperty("jdd.evilFileDir", "");
        return dir.startsWith("/") && dir.contains(EVIL_FILE_MARKER) ? dir : "/tmp/" + EVIL_FILE_MARKER;
    }
    private static final ArrayList<String> observedEvilPaths = new ArrayList<>();
    private static final ArrayList<StackTraceElement[]> observedEvilStacks = new ArrayList<>();
    // "<lastModified>:<length>" of the file when the path was seen, "" if it did not exist
    private static final ArrayList<String> observedEvilStates = new ArrayList<>();
    private static long execStartMillis = 0;

    /**
     * Call before an execution, while isStart is still false (the File objects made here must not
     * be observed).
     */
    public static void prepareFileOracle() {
        observedEvilPaths.clear();
        observedEvilStacks.clear();
        observedEvilStates.clear();
        writtenEvilPaths.clear();
        writtenEvilStacks.clear();
        // a clean directory, as on a target system: files left by earlier executions, or written while
        // the seed was serialized, must not be taken for this execution's writes, nor be read by it
        File dir = new File(EVIL_FILE_DIR);
        if (dir.isFile()) {
            dir.delete();
        }
        deleteContents(dir);
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        // File.lastModified() has whole-second precision on JDK 8 / Linux
        execStartMillis = System.currentTimeMillis() / 1000 * 1000;
    }

    private static void deleteContents(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory() && !java.nio.file.Files.isSymbolicLink(child.toPath())) {
                deleteContents(child);
            }
            child.delete();
        }
    }

    /**
     * Call after an execution, with isStart false again. Sets isArriveSink if a recorded path is now a
     * regular file that the execution created or changed, then deletes it so later executions start clean.
     */
    public static void confirmFileOracle() {
        for (int i = 0; i < observedEvilPaths.size(); i++) {
            File f = new File(observedEvilPaths.get(i));
            boolean isChanged = !(f.lastModified() + ":" + f.length()).equals(observedEvilStates.get(i));
            if (f.isFile() && isChanged && f.lastModified() >= execStartMillis) {
                LogUtils.success2Log("[+] OracleObserver: Observed a file written by the payload: " + f.getAbsolutePath());
                isArriveSink = true;
                int wi = writtenEvilPaths.indexOf(f.getAbsolutePath());
                if (wi >= 0) {
                    recordSinkHit(writtenEvilStacks.get(wi), TriggeredChain.HIT_WRITE);
                } else {
                    recordSinkHit(observedEvilStacks.get(i), TriggeredChain.HIT_FILE);
                }
                f.delete();
            }
        }
        observedEvilPaths.clear();
        observedEvilStacks.clear();
        observedEvilStates.clear();
        writtenEvilPaths.clear();
        writtenEvilStacks.clear();
    }

    // Whether the sink of the current execution was reached along the IOCD's gadget chain. The
    // oracles above only show that the sink effect happened; the payload can also get there through
    // another route (e.g. TiedMapEntry.hashCode() while a Hashtable is rebuilt, instead of the
    // XStringForFSB.equals() call of the IOCD), which does not verify this IOCD's chain.
    public static boolean isSinkRouteOk = false;
    // best match of an off-chain sink hit, for logging: "<matched>/<chain length>, first missing <method>"
    public static String sinkRouteInfo = "";
    private static int sinkRouteBest = -1;

    /** A sink hit of the current execution: the chain the payload really ran (TriggeredChain). */
    public static final class SinkHit {
        public final List<String> chain;
        public final boolean isOnIocdChain;
        // "<gadget methods of the IOCD found on the stack>/<IOCD chain length>"
        public final String iocdMatch;

        SinkHit(List<String> chain, boolean isOnIocdChain, String iocdMatch) {
            this.chain = chain;
            this.isOnIocdChain = isOnIocdChain;
            this.iocdMatch = iocdMatch;
        }
    }

    public static final ArrayList<SinkHit> sinkHits = new ArrayList<>();

    /** Clear the sink records; call before each execution. */
    public static void resetSinkRecord() {
        isArriveSink = false;
        isSinkRouteOk = false;
        isSinkFieldControlled = false;   // expectedMarker is NOT cleared: it is a property of the IOCD
        sinkRouteInfo = "";
        sinkRouteBest = -1;
        sinkHits.clear();
    }

    /**
     * Record a sink hit seen with the given stack (kind: TriggeredChain.HIT_*): the chain it really
     * ran, and whether that stack passes through every gadget method of the current IOCD, entry
     * first, in order (other frames in between are fine). A gadget method matches a frame with the
     * same method name whose class is the gadget's class, a subtype of it (the IOCD names an interface
     * or a super class) or a super type of it (the gadget class inherits the method). A FILE stack
     * is taken before the chain's final call (the write), so that gadget method is not looked for.
     */
    public static void recordSinkHit(StackTraceElement[] stack, int kind) {
        if (stack == null || currentSeedCS == null || currentSeedCS.isEmpty() || sinkHits.size() >= 16) {
            return;
        }
        int total = currentSeedCS.size() - (kind == TriggeredChain.HIT_FILE ? 1 : 0);
        int ci = 0;
        for (int fi = stack.length - 1; fi >= 0 && ci < total; fi--) {
            // soot's lambda classes are never on a stack (CommonUtils.isSootLambdaClass)
            while (ci < total && CommonUtils.isSootLambdaClass(currentSeedCS.get(ci))) {
                ci++;
            }
            if (ci < total && isFrameOfGadgetMethod(stack[fi], currentSeedCS.get(ci).replace("'", ""))) {
                ci++;
            }
        }
        while (ci < total && CommonUtils.isSootLambdaClass(currentSeedCS.get(ci))) {
            ci++;
        }
        if (ci == total) {
            isSinkRouteOk = true;
        } else if (ci > sinkRouteBest) {
            sinkRouteBest = ci;
            sinkRouteInfo = ci + "/" + total + ", first missing " + currentSeedCS.get(ci).replace("'", "");
        }
        sinkHits.add(new SinkHit(TriggeredChain.extract(stack, kind), ci == total, ci + "/" + total));
    }

    private static final HashMap<String, Boolean> typeRelationCache = new HashMap<>();

    private static boolean isFrameOfGadgetMethod(StackTraceElement frame, String gadgetSig) {
        String gadgetClz = CommonUtils.getDeclaredClzBySig(gadgetSig);
        if (!gadgetSig.contains(" " + frame.getMethodName() + "(")) {
            return false;
        }
        String frameClz = frame.getClassName();
        if (frameClz.equals(gadgetClz) || frameClz.contains("$Proxy")) {
            return true;
        }
        String key = gadgetClz + "|" + frameClz;
        Boolean related = typeRelationCache.get(key);
        if (related == null) {
            related = ClassUtils.isSubOrImplClass(gadgetClz, frameClz) || ClassUtils.isSubOrImplClass(frameClz, gadgetClz)
                    || isGeneratedImplOf(frameClz, gadgetClz);
            typeRelationCache.put(key, related);
        }
        return related;
    }

    // A frame whose class cannot be found by name was generated into a private class loader (e.g. fastjson2's
    // ASM ObjectWriters). The stack does not tell its type, so it is taken as an implementation of an interface /
    // abstract gadget class whose method it runs (the method name was already compared). The independent replay
    // checks the receiver's real type.
    private static boolean isGeneratedImplOf(String frameClz, String gadgetClz) {
        try {
            Class.forName(frameClz, false, ClassLoader.getSystemClassLoader());
            return false;
        } catch (Throwable notFound) {
            try {
                Class<?> g = Class.forName(gadgetClz, false, ClassLoader.getSystemClassLoader());
                return g.isInterface() || java.lang.reflect.Modifier.isAbstract(g.getModifiers());
            } catch (Throwable e) {
                return false;
            }
        }
    }

    private static boolean isArrivedClojureSink(String mtdNameWithParent, String clzName) {
        return mtdNameWithParent.equals("invokeStatic(") && clzName.equals("clojure.core$eval");
    }


}
