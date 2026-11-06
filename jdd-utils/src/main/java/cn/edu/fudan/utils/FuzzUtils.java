package cn.edu.fudan.utils;

import cn.edu.fudan.container.ConfigContainer;
import cn.edu.fudan.exception.validator.ReflectionException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class FuzzUtils {

    private static final HashSet<String> COLLECTION_FIELD_SET = new HashSet<>(Arrays.asList(
            "java.util.Hashtable$Entry[]",
            "java.util.HashMap$Node[]",
            "java.util.concurrent.ConcurrentHashMap$Node[]")
    );

    private static final HashSet<String> COLLECTION_FIELD_SIG_SET = new HashSet<>(Arrays.asList(
            "<java.util.concurrent.ConcurrentHashMap: java.util.concurrent.ConcurrentHashMap$Node[] table>"
    ));

    private static final HashSet<String> COLLECTION_FIELD_SIG_SET_CANDIDATE = new HashSet<>(Arrays.asList(
            "<java.util.concurrent.ConcurrentHashMap: java.util.concurrent.ConcurrentHashMap$Node[] nextTable>"
        )
    );

    private static final HashSet<String> JDK_FORM_COLLECTION_CLASS_SET = new HashSet<>(Arrays.asList(
            "[Ljava.util.Hashtable$Entry;",
            "[Ljava.util.HashMap$Node;",
            "[Ljava.util.concurrent.ConcurrentHashMap$Node;")
    );

    private static final HashSet<String> COLLECTION_CLASS_NODE_CLASS_SET = new HashSet<>(Arrays.asList(
            "java.util.Hashtable$Entry",
            "java.util.HashMap$Node",
            "java.util.concurrent.ConcurrentHashMap$Node"
    ));

    public static final HashSet<String> COLLECTION_CLASSIC_CLASS = new HashSet<>(Arrays.asList(
            "java.util.Hashtable",
            "java.util.HashMap",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.AbstractMap$SimpleEntry",
            "java.util.AbstractMap$SimpleImmutableEntry",
            "org.springframework.aop.target.HotSwappableTargetSource"
    ));

    public static final String ABS_MAP_SIMPLE_ENTRY = "java.util.AbstractMap$SimpleEntry";

    public static final String ABS_MAP_SIMPLE_IMMUTABLE_ENTRY = "java.util.AbstractMap$SimpleImmutableEntry";

    private static final Map<String, Set<String>> SPECIAL_CLASS_AND_FIELDS = new HashMap<String, Set<String>>() {
        {
            put("org.hibernate.engine.spi.CollectionKey", new HashSet<>(Arrays.asList("role")));
            put("org.hibernate.engine.spi.EntityUniqueKey", new HashSet<>(Arrays.asList("entityName", "uniqueKeyName")));
        }
    };

    private static final Set<String> SPECIAL_DYNAMIC_PROXY_CLASS = new HashSet<>(
            Arrays.asList("org.jboss.weld.interceptor.proxy.InterceptorMethodHandler", "org.jboss.interceptor.proxy.InterceptorMethodHandler")
    );
    private static final Map<String, List<String>> SPECIAL_DYNAMIC_PROXY_CLASS_MAP = new HashMap<String, List<String>>() {
        {
            put("org.jboss.weld.interceptor.proxy.InterceptorMethodHandler", new ArrayList<>());
            put("org.jboss.interceptor.proxy.InterceptorMethodHandler", new ArrayList<>());

            get("org.jboss.weld.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.weld.interceptor.reader.ReflectiveClassMetadata"); // 0
            get("org.jboss.weld.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.weld.interceptor.reader.ClassMetadataInterceptorReference"); // 1
            get("org.jboss.weld.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.weld.interceptor.reader.DefaultMethodMetadata"); // 2
            get("org.jboss.weld.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.weld.interceptor.reader.SimpleInterceptorMetadata"); // 3
            get("org.jboss.weld.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.weld.interceptor.builder.InterceptionModelImpl"); // 4
            get("org.jboss.weld.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.weld.interceptor.spi.model.InterceptionType"); // 5 POST_ACTIVATE
            get("org.jboss.weld.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.weld.interceptor.proxy.DefaultInvocationContextFactory"); // 6
            get("org.jboss.weld.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.weld.interceptor.builder.MethodReference"); // 7

            get("org.jboss.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.interceptor.reader.ReflectiveClassMetadata"); // 0
            get("org.jboss.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.interceptor.reader.ClassMetadataInterceptorReference"); // 1
            get("org.jboss.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.interceptor.reader.DefaultMethodMetadata"); // 2
            get("org.jboss.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.interceptor.reader.SimpleInterceptorMetadata"); // 3
            get("org.jboss.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.interceptor.builder.InterceptionModelImpl"); // 4
            get("org.jboss.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.interceptor.spi.model.InterceptionType"); // 5 POST_ACTIVATE
            get("org.jboss.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.interceptor.proxy.DefaultInvocationContextFactory"); // 6
            get("org.jboss.interceptor.proxy.InterceptorMethodHandler").add("org.jboss.interceptor.builder.MethodReference"); // 7

        }
    };

    // For Expression Injection, we model some necessary classes for the expression injection sink. 
    private static final Map<String, List<String>> SPECIAL_EXPRESSION_INJECTION_CLASS = new HashMap<String, List<String>>() {
        {
            put("org.apache.myfaces.view.facelets.el.ValueExpressionMethodExpression", new ArrayList<>());

            get("org.apache.myfaces.view.facelets.el.ValueExpressionMethodExpression").add("org.apache.myfaces.context.servlet.FacesContextImpl"); // 0
            get("org.apache.myfaces.view.facelets.el.ValueExpressionMethodExpression").add("org.apache.myfaces.el.CompositeELResolver"); // 1
            get("org.apache.myfaces.view.facelets.el.ValueExpressionMethodExpression").add("org.apache.myfaces.el.unified.FacesELContext"); // 2, to be set as the field `_elContext` of the instance of FacesContextImpl
            get("org.apache.myfaces.view.facelets.el.ValueExpressionMethodExpression").add("org.apache.el.ExpressionFactoryImpl"); // 3

        }
    };

    public static String CLOSURE_PREFIX = "clojure";

    private static final Map<String, List<String>> SPECIAL_CLOJURE_SINK_CLASS = new HashMap<String, List<String>>() {
        {
            put("clojure.inspector.proxy$javax.swing.table.AbstractTableModel$ff19274a", new ArrayList<>());

            get("clojure.inspector.proxy$javax.swing.table.AbstractTableModel$ff19274a").add("clojure.core$comp");
            get("clojure.inspector.proxy$javax.swing.table.AbstractTableModel$ff19274a").add("clojure.main$eval_opt");
            get("clojure.inspector.proxy$javax.swing.table.AbstractTableModel$ff19274a").add("clojure.core$constantly");
            get("clojure.inspector.proxy$javax.swing.table.AbstractTableModel$ff19274a").add("clojure.lang.PersistentArrayMap");

        }
    };

    public static final Set<String> CLOJURE_SINK_METHOD_SIG_SET = new HashSet<>(Arrays.asList(
            "<clojure.core$eval: java.lang.Object invokeStatic(java.lang.Object)>"
    ));


    // For JNDI related Sinks.
    public static final String JAVAX_NAMING_REF = "javax.naming.Reference";
    public static final String XBEAN_READ_ONLY_BINDING = "org.apache.xbean.naming.context.ContextUtil$ReadOnlyBinding";
    public static final String XBEAN_WRITABLE_CTX = "org.apache.xbean.naming.context.WritableContext";

    public static final String XSTRING = "com.sun.org.apache.xpath.internal.objects.XString";
    public static final String XSTRING_FSB = "com.sun.org.apache.xpath.internal.objects.XStringForFSB";
    public static final String JAVAX_AUDIO_FORMAT = "javax.sound.sampled.AudioFormat$Encoding";
    public static final String JAVAX_AUDIO_FORMAT_TYPE = "javax.sound.sampled.AudioFileFormat$Type";

    public static final String SPRING_AOP_HOT_SWAPPABLE_TARGET_SOURCE = "org.springframework.aop.target.HotSwappableTargetSource";

    public static final String JAVAX_CON_NOT_PROC_EXCEPTION = "javax.naming.CannotProceedException";
    public static final String JAVAX_NAMING_NAMING_EXCEPTION = "javax.naming.NamingException";

    public static boolean isXStringRelatedClass(String clzName) {
        return XSTRING.equals(clzName) || XSTRING_FSB.equals(clzName) || JAVAX_AUDIO_FORMAT.equals(clzName) || JAVAX_AUDIO_FORMAT_TYPE.equals(clzName);
    }

    public static void deleteCache() {
        // ConfigContainer.inputClassPath will have /tmp added during extraction, so we don't need to add /tmp here
        FileUtils.deleteFileOrDirectory(ConfigContainer.inputClassPath);
    }

    public static byte[] serializeJdk(Object o) throws Exception {
        byte[] resultBytes;
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(o);
            resultBytes = byteArrayOutputStream.toByteArray();
        } catch (Exception e) {
            throw e;
        }
        return resultBytes;
    }

    /**
     * 工具方法，用于得到真正的BackboneObjMap中key的类名
     *
     * @param clzId
     * @param colTag
     * @param clzName
     * @return
     */
    public static String getRealClassNameKey(int clzId, String colTag, String clzName) {
        String clzNameWithId = clzId == 0 ? clzName : clzName + "_" + clzId;
        return colTag == null ? clzNameWithId : clzNameWithId + "_" + colTag;
    }

    // This run's inert reachability marker token for an IOCD. Matches datastructrue.inst.JDDFuzzRecord.
    // MARKER_PREFIX + iocdHash so the embedded token and the oracle's expected value agree. Only
    // [A-Za-z0-9-] (iocdHash is a possibly-negative int), so it carries no ':' or '/' that a JNDI name
    // parser would treat specially, and is never code / a resolvable name / a loadable class.
    public static String getReachMarker(int iocdHash) {
        return "JDD-REACH-" + iocdHash;
    }

    // Inert host marker for host-name resolution sinks (InetAddress.getByName): an IPv6 literal from the
    // documentation range 2001:db8::/32 (RFC 3849), two hex groups carrying the iocd hash. A literal is parsed,
    // never looked up, so no name is resolved and nothing is contacted; the range is reserved so it can never
    // be a real host. Unique per IOCD, so the oracle's contains() cannot match another IOCD's marker.
    public static final String HOST_MARKER_PREFIX = "2001:db8::";

    public static String getReachHostMarker(int iocdHash) {
        return HOST_MARKER_PREFIX + Integer.toHexString(iocdHash >>> 16) + ":" + Integer.toHexString(iocdHash & 0xffff);
    }

    // Inert markers for the JRMP sinks. UnicastRef.newCall: the host the call goes to, a loopback address
    // 127.255.x.y carrying 16 bits of the iocd hash; nothing listens on the marker port, so the connection is
    // refused at once and nothing leaves the machine (an address of a documentation range could hang until the
    // connect times out). UnicastServerRef.exportObject: the port exported on, from 20000-39999 (below the
    // ephemeral ports); the export stops before it listens, as the exported object has no remote interface.
    public static final String JRMP_HOST_MARKER_PREFIX = "127.255.";
    public static final int JRMP_PORT_MARKER_BASE = 20000;
    public static final int JRMP_PORT_MARKER_RANGE = 20000;

    public static String getReachJrmpHostMarker(int iocdHash) {
        return JRMP_HOST_MARKER_PREFIX + ((iocdHash >>> 8) & 0xff) + "." + (iocdHash & 0xff);
    }

    public static int getReachJrmpPortMarker(int iocdHash) {
        return JRMP_PORT_MARKER_BASE + (int) ((iocdHash & 0xffffffffL) % JRMP_PORT_MARKER_RANGE);
    }

    public static String getDefaultClassNameKey(String clzName) {
        return getRealClassNameKey(0, null, clzName);
    }

    /**
     * 工具方法，用于缓存每次生成的backboneObject
     *
     * @param targetMap
     * @param clzId
     * @param hashColTag
     * @param clzName
     * @param obj
     */
    public static void cacheObject(HashMap<String, Object> targetMap, int clzId, String hashColTag, String clzName, Object obj) {
        String realClzKey = getRealClassNameKey(clzId, hashColTag, clzName);
        targetMap.remove(realClzKey);
        targetMap.put(realClzKey, obj);
    }

    /**
     * help to refine the special collection object
     * for generating correct object in high probability
     *
     * each time we generate a special collection object, we need to refine it
     *
     * @param obj
     */
    public static void refineSpecialCollectionObject(Object obj) throws ReflectionException {
        if (obj instanceof HashMap) {
            ReflectionUtils.setFieldValue(obj, "size", 1);
            ReflectionUtils.setFieldValue(obj, "threshold", 12);
            ReflectionUtils.setFieldValue(obj, "loadFactor", 0.75f);
            ReflectionUtils.setFieldValue(obj, "modCount", 1);
        } else if (obj instanceof Hashtable) {
            ReflectionUtils.setFieldValue(obj, "loadFactor", 0.75f);
            ReflectionUtils.setFieldValue(obj, "threshold", 8);
            ReflectionUtils.setFieldValue(obj, "count", 1);
            ReflectionUtils.setFieldValue(obj, "modCount", 1);
        } else if (obj instanceof ConcurrentHashMap) {
            ReflectionUtils.setFieldValue(obj, "sizeCtl", 12);
            ReflectionUtils.setFieldValue(obj, "baseCount", 1);
        }
    }

    public static void swapSpecialCollectionObjectItem(Object obj) throws ReflectionException {
        if (obj instanceof ConcurrentHashMap) {
            Object num = ReflectionUtils.getFieldValue(obj, "baseCount");
            if ((num instanceof Integer || num instanceof Long) && CommonUtils.convertObjectToInt(num) == 2) {
                int size = CommonUtils.convertObjectToInt(num);
                Object table = ReflectionUtils.getFieldValue(obj, "table");
                if (table != null) {
                    Object[] newTable = Arrays.copyOf((Object[]) table, size);
                    Object node1 = Array.get(table, 0);
                    Object node2 = Array.get(table, 1);
                    newTable[0] = node2;
                    newTable[1] = node1;
                    ReflectionUtils.setFieldValue(obj, "table", newTable);
                }
            }
        }
    }

    /**
     * Recompute the bookkeeping fields that JDK classes serialize next to their contents, for every
     * object reachable from `root`. The generator places gadget objects directly into backing arrays
     * and tables, and mutation randomizes int fields, so these counts are usually stale:
     * <ul>
     *   <li>SerialArray.readObject rejects the object unless len == elements.length;</li>
     *   <li>ArrayList/Vector/PriorityQueue.writeObject only write the first size elements, silently
     *       dropping a gadget object placed beyond them;</li>
     *   <li>Hashtable/HashMap write their count before the entries, and a wrong count corrupts the
     *       stream on reading.</li>
     * </ul>
     */
    public static void repairSerializationInvariants(Object root) {
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        java.util.ArrayDeque<Object> todo = new java.util.ArrayDeque<>();
        todo.add(root);
        while (!todo.isEmpty() && seen.size() < 20000) {
            Object obj = todo.poll();
            if (obj == null || seen.put(obj, Boolean.TRUE) != null) {
                continue;
            }
            Class<?> clz = obj.getClass();
            if (clz.isArray()) {
                if (!clz.getComponentType().isPrimitive()) {
                    for (Object item : (Object[]) obj) {
                        if (item != null) todo.add(item);
                    }
                }
                continue;
            }
            if (clz.isPrimitive() || obj instanceof String || obj instanceof Number || obj instanceof Class) {
                continue;
            }
            try {
                repairOne(obj);
            } catch (Exception | Error e) {
                LogUtils.debug("Can not repair " + clz.getName() + ": " + e);
            }
            for (Class<?> c = clz; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                        continue;
                    }
                    try {
                        ReflectionUtils.setAccessible(f);
                        Object v = f.get(obj);
                        if (v != null) todo.add(v);
                    } catch (Exception | Error ignored) {
                        // unreachable field, skip
                    }
                }
            }
        }
    }

    private static void repairOne(Object obj) throws ReflectionException {
        if ("org.mozilla.javascript.MemberBox".equals(obj.getClass().getName())
                && ReflectionUtils.getFieldValue(obj, "memberObject") == null) {
            // All of MemberBox's fields are transient and its writeObject derives what it writes from memberObject,
            // while its readObject calls init() with whatever readMember returns: a null memberObject serializes
            // without complaint and then makes readObject throw a NullPointerException, so the seed cannot be read
            // back and the chain looks as if it never started. Report it rather than let it fail silently.
            LogUtils.warn("Seed holds a MemberBox without a memberObject: it will not survive its own "
                    + "serialization round trip (MemberBox.readObject throws NullPointerException).");
        }
        if ("javax.sql.rowset.serial.SerialArray".equals(obj.getClass().getName())) {
            Object[] elements = (Object[]) ReflectionUtils.getFieldValue(obj, "elements");
            if (elements == null) {
                elements = new Object[0];
                ReflectionUtils.setFieldValue(obj, "elements", elements);
            }
            ReflectionUtils.setFieldValue(obj, "len", elements.length);
        } else if (obj instanceof ArrayList) {
            fixUsedLength(obj, "elementData", "size");
        } else if (obj instanceof Vector) {
            fixUsedLength(obj, "elementData", "elementCount");
        } else if (obj instanceof PriorityQueue) {
            fixUsedLength(obj, "queue", "size");
        } else if (obj instanceof Hashtable) {
            ReflectionUtils.setFieldValue(obj, "count", countChainedEntries(ReflectionUtils.getFieldValue(obj, "table")));
        } else if (obj instanceof HashMap) {
            ReflectionUtils.setFieldValue(obj, "size", countChainedEntries(ReflectionUtils.getFieldValue(obj, "table")));
        }
    }

    // size = index of the last non-null slot + 1, so placed elements are written out
    private static void fixUsedLength(Object obj, String arrField, String sizeField) throws ReflectionException {
        Object[] arr = (Object[]) ReflectionUtils.getFieldValue(obj, arrField);
        int used = 0;
        if (arr != null) {
            for (int i = arr.length - 1; i >= 0; i--) {
                if (arr[i] != null) {
                    used = i + 1;
                    break;
                }
            }
        }
        ReflectionUtils.setFieldValue(obj, sizeField, used);
    }

    // number of entries in a bucket table whose entries are linked through their `next` field
    private static int countChainedEntries(Object table) throws ReflectionException {
        if (table == null) {
            return 0;
        }
        int n = 0;
        for (Object e : (Object[]) table) {
            java.util.IdentityHashMap<Object, Boolean> chain = new java.util.IdentityHashMap<>();
            while (e != null && chain.put(e, Boolean.TRUE) == null) {
                n++;
                e = ReflectionUtils.getFieldValue(e, "next");
            }
        }
        return n;
    }

    public static void refineSpecialCollectionObjectBeforeSerialize(Object obj) throws ReflectionException {
        if (obj instanceof HashMap) {
            ReflectionUtils.setFieldValue(obj, "size", 2);
            ReflectionUtils.setFieldValue(obj, "threshold", 12);
            ReflectionUtils.setFieldValue(obj, "loadFactor", 0.75f);
            ReflectionUtils.setFieldValue(obj, "modCount", 2);
        } else if (obj instanceof Hashtable) {
            ReflectionUtils.setFieldValue(obj, "loadFactor", 0.75f);
            ReflectionUtils.setFieldValue(obj, "threshold", 8);
            ReflectionUtils.setFieldValue(obj, "count", 2);
            ReflectionUtils.setFieldValue(obj, "modCount", 2);
        } else if (obj instanceof ConcurrentHashMap) {
            ReflectionUtils.setFieldValue(obj, "sizeCtl", 12);
            ReflectionUtils.setFieldValue(obj, "baseCount", 2);
            Object table = ReflectionUtils.getFieldValue(obj, "table");
            if (table != null) {
                Object newTable = Arrays.copyOf((Object[]) table, 2);
                ReflectionUtils.setFieldValue(obj, "table", newTable);
            }
        } else if (obj instanceof PriorityQueue) {
            ReflectionUtils.setFieldValue(obj, "size", 2);
            Object queue = ReflectionUtils.getFieldValue(obj, "queue");
            Object newQueue = Arrays.copyOf((Object[]) queue, 2);
            ReflectionUtils.setFieldValue(obj, "queue", newQueue);
        }
    }

    public static void refineSpecialCollectionObjectAfterMutaion(Object obj) throws ReflectionException {
        if (obj instanceof HashMap) {
            ReflectionUtils.setFieldValue(obj, "size", 1);
            ReflectionUtils.setFieldValue(obj, "threshold", 12);
            ReflectionUtils.setFieldValue(obj, "loadFactor", 0.75f);
            ReflectionUtils.setFieldValue(obj, "modCount", 1);
            Object table = ReflectionUtils.getFieldValue(obj, "table");
            if (table == null || ((Object[]) table).length == 0) {
                try {
                    ((Map) obj).put("1", "1");
                } catch (Exception e) {
                    // ignore
                }   
            }
        } else if (obj instanceof Hashtable) {
            ReflectionUtils.setFieldValue(obj, "loadFactor", 0.75f);
            ReflectionUtils.setFieldValue(obj, "threshold", 8);
            ReflectionUtils.setFieldValue(obj, "count", 1);
            ReflectionUtils.setFieldValue(obj, "modCount", 1);
            Object table = ReflectionUtils.getFieldValue(obj, "table");
            if (table == null || ((Object[]) table).length == 0) {
                try {
                    ((Map) obj).put("1", "1");
                } catch (Exception e) {
                    // ignore
                }
            }
        } else if (obj instanceof ConcurrentHashMap) {
            ReflectionUtils.setFieldValue(obj, "sizeCtl", 12);
            ReflectionUtils.setFieldValue(obj, "baseCount", 1);
            Object table = ReflectionUtils.getFieldValue(obj, "table");
            if (table == null || ((Object[]) table).length == 0) {
                try {
                    ((Map) obj).put("1", "1");
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    public static Object getCachedObject (int clzId, String colTag, String clzName, HashMap<String, Object> targetMap) {
        return targetMap.get(getRealClassNameKey(clzId, colTag, clzName));
    }

    public static Object getCachedObject (String realClzKey, HashMap<String, Object> targetMap) {
        return targetMap.get(realClzKey);
    }

    public static List<String> getSpecialDynamicProxyRelatedClassList(String clzName) {
        return SPECIAL_DYNAMIC_PROXY_CLASS_MAP.get(clzName);
    }

    public static List<String> getSpecialExpressionInjectionClassList(String clzName) {
        return SPECIAL_EXPRESSION_INJECTION_CLASS.get(clzName);
    }


    public static List<String> getSpecialClojureSinkClassList(String clzName) {
        return SPECIAL_CLOJURE_SINK_CLASS.get(clzName);
    }

    public static boolean isSpecialFldInFld(String fldType) {
        return COLLECTION_FIELD_SET.contains(fldType);
    }

    public static boolean isJdkFormCollectionClass(String fldType) {
        return JDK_FORM_COLLECTION_CLASS_SET.contains(fldType);
    }

    public static boolean isType2Or3Collision(int type) {
        return type == 2 || type == 3;
    }

    public static void moveToWrongSeedDir(String originFilePath, String targetFilePath) {
        File oFile = new File(originFilePath);
        File tFile = new File(targetFilePath);
        try {
            FileUtils.copyFile(oFile, tFile);
            FileUtils.deleteFile(oFile.toPath());
        } catch (IOException e) {
            LogUtils.error("Moving" + originFilePath + "to" + targetFilePath + "failed!");
            // e.printStackTrace();
        }

    }

    public static boolean isSpecialDynamicProxyClass(String clzName) {
        return SPECIAL_DYNAMIC_PROXY_CLASS.contains(clzName); // Return true if the set contains the element
    }

    public static boolean isSpecialExpressionInjectionClass(String clzName) {
        return SPECIAL_EXPRESSION_INJECTION_CLASS.containsKey(clzName);
    }

    public static boolean isSpecialClojureSinkClass(String clzName) {
        return SPECIAL_CLOJURE_SINK_CLASS.containsKey(clzName);
    }


    public static boolean isCollectionFieldSigSetCandidate(String fldSig) {
        return COLLECTION_FIELD_SIG_SET_CANDIDATE.contains(fldSig);
    }


    public static void handleSpecialClass(Object o1, Object o2, String clzName) {
        if (SPECIAL_CLASS_AND_FIELDS.containsKey(clzName)) {
            Set<String> specialFields = SPECIAL_CLASS_AND_FIELDS.get(clzName);
            for (String field : specialFields) {
                try {
                    ReflectionUtils.setFieldValue(o1, field, "1");
                    ReflectionUtils.setFieldValue(o2, field, "1");
                } catch (ReflectionException e) {
                    e.printStackTrace();
                }
            }
        } else if (isSpecialExpressionInjectionClass(clzName)) {
            HashSet<String> immutableFldSet = (HashSet<String>) o2;
            immutableFldSet.add(getDefaultClassNameKey(clzName) + "#" + "valueExpression");
        } else if (clzName.equals("com.caucho.naming.QName")) {
            HashSet<String> immutableFldSet = (HashSet<String>) o2;
            immutableFldSet.add(getDefaultClassNameKey(clzName) + "#_items");
            try {
                ReflectionUtils.setFieldValue(o1, "_items", new ArrayList<>());
                ReflectionUtils.invokeMethodByName(o1, "add", "first");
                ReflectionUtils.invokeMethodByName(o1, "add", "rest");
            } catch (ReflectionException e) {
                e.printStackTrace();
            }

        }
    }

    public static String unhash(int hash) {
        int target = hash;
        StringBuilder answer = new StringBuilder();
        if (target < 0) {
            // String with hash of Integer.MIN_VALUE, 0x80000000
            answer.append("\\u0915\\u0009\\u001e\\u000c\\u0002");

            if (target == Integer.MIN_VALUE)
                return answer.toString();
            // Find target without sign bit set
            target = target & Integer.MAX_VALUE;
        }

        unhash0(answer, target);
        return answer.toString();
    }

    private static void unhash0(StringBuilder partial, int target) {
        int div = target / 31;
        int rem = target % 31;

        if (div <= Character.MAX_VALUE) {
            if (div != 0)
                partial.append((char) div);
            partial.append((char) rem);
        } else {
            unhash0(partial, div);
            partial.append((char) rem);
        }
    }

}
