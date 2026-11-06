package cn.edu.fudan.data;

import cn.edu.fudan.container.ConfigContainer;
import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.data.distance.GadgetCFGWrapper;
import cn.edu.fudan.data.distance.data.BasicBlock;
import cn.edu.fudan.data.distance.data.CFG;
import cn.edu.fudan.data.distance.data.InstNode;
import cn.edu.fudan.exception.inst.BuildCFGException;
import cn.edu.fudan.utils.*;
import datastructrue.inst.IfStmtInstRecord;
import datastructrue.inst.InstrumentInfoRecord;
import datastructrue.inst.MethodInstRecord;
import datastructrue.inst.SinkInstRecord;
import com.google.gson.Gson;
import datastructrue.Pair;
import datastructrue.iocd.IOCD;
import datastructrue.iocd.unit.SinkRecord;
import fj.Hash;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 存储Agent的一些全局变量
 */
public class AgentGlobalDataContainer {

    static boolean isSubSinkClassDebug = true;
    static boolean isClojureSinkDebug = true;

    static boolean isClojureSinkSet = false;

    public static String DEBUG_IFSTMTRCD_PATH = "";

    public static GlobalDataContainer gContainer;

    public static InstrumentInfoRecord instInfo;
    public static HashMap<String, HashSet<MethodInstRecord>> sinkInstInfo;
    // <类名, <方法, 参数列表>>
    public static HashMap<String, HashSet<Pair<String, String>>> allSinkMtdMap;
    // <gadget类型，[对应CallStack的Hash]>
    public static HashMap<String, HashSet<Integer>> pocGadgetCallStackHash_Map;
    // <gadget的hash, 对应的全量CallStack>
    public static HashMap<Integer, LinkedList<Pair<String, Integer>>> pocHashFQGadgetCallStackMap;
    // <gadget的hash, 对应的CallStack>
    public static HashMap<Integer, LinkedList<String>> pocHashGadgetCallStackMap;
    // <gadget类型, [对应类型的方法名称]>
    public static HashMap<String, HashSet<String>> sinkMtdTypeMap;
    // IfStmt的hash - 对应的IfStmt
    public static HashMap<Integer, IfStmtInstRecord> allHashIfStmtMap;
    // 用于静态部分计算距离时使用，<gdtClzName, [mtdName:mtdDescriptor]>
    public static HashMap<String, HashSet<String>> gadgetClzAndMtdMap;
    // oddfuzz测试用的类
    public static HashSet<String> oddfuzzClzSet = new HashSet<>(Arrays.asList("java.util.PriorityQueue",
            "org.apache.commons.collections4.comparators.TransformingComparator",
            "org.apache.commons.collections4.functors.InvokerTransformer"));

    // 该Agent除了用于AEG部分的插桩外还需要用于前期的PoC信息搜集
    //  - 0表示AEG模式
    //  - 1表示前期PoC信息搜集模式
    //  - 2表示ODDFuzz使用的插桩器
    @Deprecated
    public static int mode = 0;


    public static void readSinkFileAndStore() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("files/sink-methods"));
            String line;
            String currentKey = null;
            while ((line = reader.readLine()) != null) {
                if (line.equals("&")) {
                    currentKey = null;
                } else if (line.startsWith("#")) {
                    // 增加对单行注释的支持，此时不做任何事
                } else if (line.equals(":")) {
                    currentKey = line;
                } else if (currentKey == null) {
                    String[] splitLine = line.split(",");
                    setAllSinkMtdMap(splitLine[0], splitLine[1], splitLine[2]);
                } else {
                    String[] splitLine = line.split("\\|");
                    String[] methods = splitLine[1].split(",");
                    sinkMtdTypeMap.put(splitLine[0], new HashSet<>(Arrays.asList(methods)));
                }
            }
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void initCFGAndInstInfo() throws BuildCFGException {
        // Notice: 务必注意不要改动先后顺序
        gContainer = GlobalDataContainer.getInstance();
        extractAllBBInstInfo();
        extractAllIfStmtRecordInStandardForm();
        extractAllSinkInstInfoInStandardForm();
    }

    /**
     * 用于根据instInfo中的信息，事先将所有需要被插桩的类加载进Jvm
     */
    public static void loadAllInstClasses() {

        // 获取所有需要插桩的类
        Set<String> instClzSet = new HashSet<>(instInfo.classIfStmtsMap.keySet());
        instClzSet.addAll(instInfo.classMethodsMap.keySet());
        instClzSet.addAll(sinkInstInfo.keySet());

        // 加载这些类到JVM中
        loadClassBySet(instClzSet);

    }

    public static void loadAllJDDInstClasses() {
        HashSet<String> instClzSet = new HashSet<>();
        instClzSet.addAll(gContainer.bbInstInfoMap.keySet());
        instClzSet.addAll(sinkInstInfo.keySet());
    }

    /**
     * 加载所有PoC所需要的SinkClass到JVM中以便后续进行插桩
     */
    public static void loadAllPoCSinkClasses() {

        // 获取所有的SinkClass
        Set<String> instSinkClzSet = new HashSet<>(allSinkMtdMap.keySet());
        // 加载类到JVM中
        loadClassBySet(instSinkClzSet);

    }

    /**
     * 加载指定路径的类到JVM中用于插桩
     */
    public static void loadLibClasses() {

        List<File> libJarFiles = DataUtil.getAllJarFiles("lib/");
        Set<String> classNames = new HashSet<>();
        for (File jarFile : libJarFiles) {
            try {
                JarFile jFile = new JarFile(jarFile);
                for (JarEntry entry : Collections.list(jFile.entries())) {
                    if (entry.getName().endsWith(".class")) {
                        String className = entry.getName().replaceAll("/", "\\.")
                                .substring(0, entry.getName().length() - 6);
                        classNames.add(className);
                    }
                }
                jFile.close();
            } catch (IOException e) {
                LogUtils.info2Log("load lib classes failed");
            }
        }
        loadClassBySet(classNames);

    }

    /**
     * 加载所有jdk的类到JVM中用于做插桩
     */
    public static void loadAllJdkClasses() {

        String javaHome = System.getProperty("java.home");
        String[] jre = new String[]{"lib/rt.jar", "lib/jce.jar", "lib/ext/nashron.jar"};

        Set<String> classNames = new HashSet<>();
        for (String jdkDepPth : jre) {
            try {
                JarFile jarFile = new JarFile(new File(javaHome + File.separator + jdkDepPth));
                for (JarEntry entry : Collections.list(jarFile.entries())) {
                    if (entry.getName().endsWith(".class")) {
                        String className = entry.getName().replaceAll("/", "\\.")
                                .substring(0, entry.getName().length() - 6);
                        classNames.add(className);
                    }
                }
                jarFile.close();
            } catch (IOException e) {
                LogUtils.warn2Log("导入路径下的类出错" + jdkDepPth + "，跳过");
            }
        }
        loadClassBySet(classNames);

    }

    /**
     * 加载所有ClassPath中的类到JVM中用于做插桩
     */
    public static void loadAllClassPathClasses() {

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
        URL[] urls = ((URLClassLoader) systemClassLoader).getURLs();
        URLClassLoader classLoader = new URLClassLoader(urls, contextClassLoader);
        try {
            Enumeration<URL> enumeration = classLoader.getResources("");
            while (enumeration.hasMoreElements()) {
                URL url = enumeration.nextElement();
                File file = new File(url.toURI());
                if (file.isDirectory()) {
                    String rootDirPath = file.getPath() + "/";
                    traverseDirectory(file, classLoader, rootDirPath);
                }
            }
        } catch (IllegalArgumentException e) {
            // 不做任何处理
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void loadOddClasses() {

    }

    private static void traverseDirectory(File dir, ClassLoader classLoader, String rootPth) {
        for (File file : dir.listFiles()) {
            if (file.isDirectory()) {
                traverseDirectory(file, classLoader, rootPth);
            } else if (file.getName().endsWith(".class")) {
                String className = file.getPath().replace(rootPth, "");
                className = className.replace(File.separator, ".").substring(0, className.length() - 6);
                if (DataUtil.globalDebugFlag) {
                    LogUtils.info2Log("load Class: " + className);
                }
                try {
                    classLoader.loadClass(className);
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 给定一个clzNameSet的String Set
     * 将里面所有对应名字的类都加入到JVM中
     *
     * @param clzNameSet
     */
    private static void loadClassBySet(Set<String> clzNameSet) {
        for (String instClz : clzNameSet) {
            try {
                if (GlobalDataContainer.isDebug) {
                    LogUtils.info2Log("加载类" + instClz);
                }
                ClassLoader cl = ClassLoader.getSystemClassLoader();
                cl.loadClass(instClz);
            } catch (Error e) {
                if (GlobalDataContainer.isDebug) {
                    LogUtils.warn2Log("In `AgentGlobalDataContainer.loadClassBySet`: 加载类时错误:" + e.getMessage());
                }
            } catch (Exception e) {
                if (GlobalDataContainer.isDebug) {
                    LogUtils.warn2Log("In `AgentGlobalDataContainer.loadClassBySet`: 加载类时异常:" + e.getMessage());
                }
            }
        }
    }

    /**
     * 临时方法，加载ODDFuzz
     */
    public static void loadODDFuzzGadgetClass() {
        loadClassBySet(oddfuzzClzSet);
    }

    /**
     * 导入所有的ConditionRecord信息
     */
    public static void loadConditions() {

        // 硬编码存储所有插桩信息的文件
        File conditionFile = new File(ConfigContainer.workDir + File.separator + "files/IfStmtRecord.json");
        if (!conditionFile.exists()) {
            LogUtils.error("Please make a folder under the runtime folder of jdd-fuzzer (" + ConfigContainer.workDir + File.separator + "), and put `IfStmtRecord.json` under it.");
            System.exit(0);
        }
        try {

            instInfo = loadConditionsFromFile(conditionFile);

        } catch (IOException e) {
            e.printStackTrace();
            System.exit(0);
        }

    }

    /**
     * 读取插桩信息文件内容，转json为BranchConditions对象
     *
     * @param conditionFile
     * @return
     * @throws IOException
     */
    private static InstrumentInfoRecord loadConditionsFromFile(File conditionFile) throws IOException {
        String jsonStr = FileUtils.readFileToString(conditionFile, StandardCharsets.UTF_8);
        Gson gson = new Gson();
        return gson.fromJson(jsonStr, InstrumentInfoRecord.class);
    }

    /**
     * 用于提取出所有Sink方法的ClassName - MethodInstRecord
     */
    public static void extractAllSinkInstInfo() {
        sinkInstInfo = new HashMap<>();
        Set<SinkInstRecord> sinkRecordSet = instInfo.sinkRecords;
        for (SinkInstRecord record : sinkRecordSet) {
            MethodInstRecord tmpMtdRecord = record.getMethodInstRecord();
            String tmpClzName = tmpMtdRecord.getClassName();
            if (!sinkInstInfo.containsKey(tmpClzName)) {
                HashSet<MethodInstRecord> tmpSet = new HashSet<>();
                tmpSet.add(tmpMtdRecord);
                sinkInstInfo.put(tmpClzName, tmpSet);
                continue;
            }
            sinkInstInfo.get(tmpClzName).add(tmpMtdRecord);
        }
    }

    public static void extractAllSinkInstInfoInStandardForm() {
        for (SinkInstRecord sinkRecord : instInfo.sinkRecords) {
            String clzName = sinkRecord.methodInstRecord.className;
            String mtdName = sinkRecord.methodInstRecord.name;
            String mtdDesc = CommonUtils.getMtdDescriptorBySubSig(sinkRecord.methodInstRecord.subSig);
            String mtdNameDesc = mtdName + "#" + mtdDesc;

            if (!gContainer.sinkInstMtdInfoMap.containsKey(clzName)) {
                gContainer.sinkInstMtdInfoMap.put(clzName, new HashSet<>());
            }
            gContainer.sinkInstMtdInfoMap.get(clzName).add(mtdNameDesc);

            if (!ClassUtils.isConcreteByClzName(clzName) && clzName.equals(ClassUtils.EL_VALUE_EXPRESSION)) {
                if (isSubSinkClassDebug) {
                    LogUtils.debug("Found that " + clzName + " is a concrete class, extracting its subclasses");
                }
                HashSet<String> subClasses = ClassUtils.getAllConcreteSubClass(clzName, true);
                for (String subClass : subClasses) {
                    if (isSubSinkClassDebug) {
                        LogUtils.debug("Found that " + subClass + " is a subclass of " + clzName + " while extracting Sink information, recording it");
                    }
                    if (!gContainer.sinkInstMtdInfoMap.containsKey(subClass)) {
                        gContainer.sinkInstMtdInfoMap.put(subClass, new HashSet<>());
                    }
                    gContainer.sinkInstMtdInfoMap.get(subClass).add(mtdNameDesc);
                }
            }

            if (clzName.startsWith(FuzzUtils.CLOSURE_PREFIX) && !isClojureSinkSet) {
                // if the clojure related classes are detected, we add the corresponded sinks into the instrumentation classes list.
                for (String clojureSinkSig : FuzzUtils.CLOJURE_SINK_METHOD_SIG_SET) {
                    String clojureClzName = CommonUtils.getDeclaredClzBySig(clojureSinkSig);

                    String clojureSinkMtdName = CommonUtils.getMethodNameBySig(clojureSinkSig);
                    String clojureSinkMtdDesc = CommonUtils.getMtdDescriptorBySubSig(CommonUtils.getSubSigBySig(clojureSinkSig));
                    String clojureSinkMtdNameDesc = clojureSinkMtdName + "#" + clojureSinkMtdDesc;
                    if (!gContainer.sinkInstMtdInfoMap.containsKey(clojureClzName)) {
                        gContainer.sinkInstMtdInfoMap.put(clojureClzName, new HashSet<>());
                    }
                    gContainer.sinkInstMtdInfoMap.get(clojureClzName).add(clojureSinkMtdNameDesc);

                    LogUtils.debug("Add clojure sink " + clojureClzName + " to " + clojureSinkSig);
                }
                isClojureSinkSet = true;
            }
        }
        registerMarkerArgSinks();
    }

    // Family-scoped marker-read hooks: className -> ("name#desc" -> 0-based index of the argument that
    // carries the reachability marker. Only these exact methods get the extra inert marker read; registering
    // a method whose class is absent from the app is harmless (the class never loads, so it is never hooked).
    private static void registerMarkerArgSinks() {
        // Spring JNDI lookup: JndiTemplate.lookup(String name, Class requiredType) — the marker is the name.
        gContainer.sinkMarkerArgMap
                .computeIfAbsent("org.springframework.jndi.JndiTemplate", k -> new java.util.HashMap<>())
                .put("lookup#(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", 0);
        // NamingManager.getObjectInstance(Object refInfo, Name, Context, Hashtable) — static; the marker is the
        // Reference passed as argument 0. Only this class: the other getObjectInstance methods in the app sink
        // list (com.sun.jndi.url.*, DirectoryManager, ...) are not hooked for the marker.
        gContainer.sinkMarkerArgMap
                .computeIfAbsent("javax.naming.spi.NamingManager", k -> new java.util.HashMap<>())
                .put("getObjectInstance#(Ljava/lang/Object;Ljavax/naming/Name;Ljavax/naming/Context;Ljava/util/Hashtable;)Ljava/lang/Object;", 0);
        // Host-name resolution: InetAddress.getByName(String host) — static, the marker is the host string. Only
        // this overload; getByName(String, InetAddress) and the other InetAddress methods are not hooked.
        gContainer.sinkMarkerArgMap
                .computeIfAbsent("java.net.InetAddress", k -> new java.util.HashMap<>())
                .put("getByName#(Ljava/lang/String;)Ljava/net/InetAddress;", 0);
        // clojure.main/eval-opt: static invokeStatic(Object) — the marker is the source text in argument 0. The
        // payload makes it a single string literal, which reads and evaluates to that same string.
        gContainer.sinkMarkerArgMap
                .computeIfAbsent("clojure.main$eval_opt", k -> new java.util.HashMap<>())
                .put("invokeStatic#(Ljava/lang/Object;)Ljava/lang/Object;", 0);
        // EL ValueExpression.getValue(ELContext) (Myfaces): abstract, so the hook sits on the concrete expression
        // the payload uses, MyFaces' literal-text expression; the marker is its text, read from the receiver (-1).
        gContainer.sinkMarkerArgMap
                .computeIfAbsent("org.apache.myfaces.view.facelets.el.ELText$LiteralValueExpression", k -> new java.util.HashMap<>())
                .put("getValue#(Ljavax/el/ELContext;)Ljava/lang/Object;", -1);
        // BeanShell This.invokeMethod(String, Object[]) — the receiver (-1) is the scripted object; the marker is
        // the name of its namespace, which the payload leaves without any methods.
        gContainer.sinkMarkerArgMap
                .computeIfAbsent("bsh.This", k -> new java.util.HashMap<>())
                .put("invokeMethod#(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", -1);
        // JRMP client (ysoserial JRMPClient): the IOCD's sink call site is the LiveRef.getChannel() inside
        // UnicastRef.newCall, so the stack that must run the IOCD chain is the one at LiveRef.getChannel. The receiver
        // (-1) is the LiveRef; the marker is the host of its endpoint (ep.host).
        gContainer.sinkMarkerArgMap
                .computeIfAbsent("sun.rmi.transport.LiveRef", k -> new java.util.HashMap<>())
                .put("getChannel#()Lsun/rmi/transport/Channel;", -1);
        // JRMP listener (ysoserial JRMPListener): the IOCD chain ends at LiveRef.exportObject(Target), called by
        // UnicastServerRef.exportObject. The receiver (-1) is the LiveRef; the marker is the port of its endpoint (ep.port).
        gContainer.sinkMarkerArgMap
                .computeIfAbsent("sun.rmi.transport.LiveRef", k -> new java.util.HashMap<>())
                .put("exportObject#(Lsun/rmi/transport/Target;)V", -1);
    }

    /**
     * 用于提取所有IfStmt的hashCode - IfStmtInstRecord
     */
    public static void extractAllIfStmtRecord() {
        allHashIfStmtMap = new HashMap<>();
        for (Set<IfStmtInstRecord> stmtRcdSet : instInfo.classIfStmtsMap.values()) {
            for (IfStmtInstRecord rcd : stmtRcdSet) {
                allHashIfStmtMap.put(rcd.getHashCode(), rcd);
            }
        }
    }

    public static void extractAllIfStmtRecordInStandardForm() {
        for (Set<IfStmtInstRecord> stmtRcdSet : instInfo.classIfStmtsMap.values()) {
            for (IfStmtInstRecord rcd : stmtRcdSet) {
                String clzName = rcd.getClassName();
                String mtdName = rcd.getMethodName();
                String mtdDesc = CommonUtils.getMtdDescriptorBySubSig(rcd.getMethodSubSig());
                // String clzMtdWithSig = clzName + "#" + mtdName + "():" + mtdDesc;
                int lineNum = rcd.getInstLineNum();
                int instNodeHash = (clzName + mtdName + ":" + mtdDesc + lineNum).hashCode();
                if (gContainer.onlyBBInstInfoMap.containsKey(instNodeHash)) {
                    LogUtils.debug2Log("在导出IfStmtRecord的时候发现，存在一样的InstNode，进行合并");
                    InstNode tmpInstNode = gContainer.onlyBBInstInfoMap.get(instNodeHash);
                    tmpInstNode.isConditionInstMerge = true;
                    tmpInstNode.isConditionBB = true;
                    tmpInstNode.conditionHash = rcd.getHashCode();
                } else {
                    // Notice: 此处instNodehash不能设置为-1
                    InstNode newInstNode = new InstNode(mtdName, mtdDesc, instNodeHash, lineNum);
                    newInstNode.conditionHash = rcd.getHashCode();
                    newInstNode.isConditionBB = true;
                    if (gContainer.bbInstInfoMap.containsKey(clzName)) {
                        HashSet<InstNode> tmpSet = gContainer.bbInstInfoMap.get(clzName);
                        tmpSet.add(newInstNode);
                    } else {
                        HashSet<InstNode> newSet = new HashSet<>();
                        newSet.add(newInstNode);
                        gContainer.bbInstInfoMap.put(clzName, newSet);
                    }
                }
            }
        }
    }

    public static void extractAllBBInstInfo() {
        for (Integer index : gContainer.cfgWrapperHashMap.values()) {
            GadgetCFGWrapper cfgWrapper = gContainer.cfgWrapperList.get(index);
            for (Map.Entry<String, LinkedHashSet<InstNode>> entry : cfgWrapper.instInfoMap.entrySet()) {
                if (gContainer.bbInstInfoMap.containsKey(entry.getKey())) {
                    Set<InstNode> nodes = gContainer.bbInstInfoMap.get(entry.getKey());
                    nodes.addAll(entry.getValue());
                } else {
                    gContainer.bbInstInfoMap.put(entry.getKey(), entry.getValue());
                }
                for (InstNode instNode : entry.getValue()) {
                    gContainer.onlyBBInstInfoMap.put(instNode.hashCode(), instNode);
                }
            }
        }
    }



    /**
     * 向allSinkMtdMap添加对应类、对应Sink方法名字和类型
     *
     * @param clzName
     * @param mtdName
     * @param mtdDesc
     */
    private static void setAllSinkMtdMap(String clzName, String mtdName, String mtdDesc) {
        Pair<String, String> sinkMtdPair = new Pair<>(mtdName, mtdDesc, false);
        if (!allSinkMtdMap.containsKey(clzName)) {
            HashSet<Pair<String, String>> tmpSet = new HashSet<>();
            allSinkMtdMap.put(clzName, tmpSet);
        }
        HashSet<Pair<String, String>> tmpSet = allSinkMtdMap.get(clzName);
        tmpSet.add(sinkMtdPair);
    }

    /**
     * 记录这些PoC Gadget
     *
     * @param callStack 需要记录的CallStack
     */
    public static void addPocGadgetCallStack(LinkedList<String> callStack) {

        // 如果找不到对应type，就直接返回不做任何记录
        String type = DataUtil.getGadgetTypeByCallStack(callStack);
        if (type.equals("")) {
            return;
        }
        // 首先记录类型和对应的Hash
        if (!pocGadgetCallStackHash_Map.containsKey(type)) {
            HashSet<Integer> tmpSet = new HashSet<>();
            pocGadgetCallStackHash_Map.put(type, tmpSet);
        }
        int callStackHash = callStack.hashCode();
        HashSet<Integer> tmpSet = pocGadgetCallStackHash_Map.get(type);
        tmpSet.add(callStackHash);
        // 而后记录hash和真正的call stack
        pocHashGadgetCallStackMap.put(callStackHash, callStack);

    }

    /**
     * 全量Gadget Call Stack的记录
     *
     * @param callStack
     * @param gdtHash
     */
    public static void addPocFQGadgetCallStack(LinkedList<Pair<String, Integer>> callStack, int gdtHash) {
        pocHashFQGadgetCallStackMap.put(gdtHash, callStack);
    }

}
