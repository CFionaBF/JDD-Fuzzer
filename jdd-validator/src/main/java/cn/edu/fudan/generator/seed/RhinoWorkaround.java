package cn.edu.fudan.generator.seed;

import cn.edu.fudan.datastructure.OracleClassA;
import cn.edu.fudan.exception.validator.GeneratorException;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.FuzzUtils;
import cn.edu.fudan.utils.LogUtils;
import cn.edu.fudan.utils.ReflectionUtils;

import java.lang.reflect.Method;
import datastructrue.Pair;
import datastructrue.iocd.unit.ClassRecord;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Mozilla Rhino (js 1.7R2): two parts of a seed that the field-by-field assembly cannot build. Local to this
 * checkout (not shared with the upstream), both only act on Rhino classes.
 * <p>
 * 1. prepare(): a NativeJavaObject rebuilds its JavaMembers while it is deserialized (readObject -> initMembers ->
 * JavaMembers.lookupClass -> ClassCache.get), which needs the top-level scope in its `parent` field to have a
 * ClassCache associated; without it, readObject throws "Can't find top level scope for ClassCache.get" before the
 * seed gets to the chain. Every NativeJavaObject object gets a plain top-level scope (NativeObject) with a ClassCache,
 * both built with Rhino's own API.
 * <p>
 * 2. assemble(): a getter of a NativeError is stored in a slot of its slot table (ScriptableObject.slots, a hash
 * table indexed by the hash of the property name, plus count/firstAdded/lastAdded): setting those private fields
 * one by one does not give a table that getSlot() finds. For the records
 * NativeError.lastAdded -> ScriptableObject$GetterSlot -> getter -> NativeJavaMethod the getter is installed with
 * the public ScriptableObject.setGetterOrSetter(name, 0, getter, false) instead. The name is the property the chain
 * reads: NativeError.js_toString reads "name".
 */
public class RhinoWorkaround {

    private static final String NATIVE_JAVA_OBJECT = "org.mozilla.javascript.NativeJavaObject";
    private static final String NATIVE_ERROR = "org.mozilla.javascript.NativeError";
    private static final String NATIVE_JAVA_METHOD = "org.mozilla.javascript.NativeJavaMethod";
    private static final String GETTER_SLOT = "org.mozilla.javascript.ScriptableObject$GetterSlot";
    private static final String NATIVE_OBJECT = "org.mozilla.javascript.NativeObject";
    private static final String CLASS_CACHE = "org.mozilla.javascript.ClassCache";
    private static final String GETTER_PROPERTY = "name";
    private static final String CONTEXT = "org.mozilla.javascript.Context";
    private static final String NATIVE_JAVA_ARRAY = "org.mozilla.javascript.NativeJavaArray";
    private static final String JAVA_ADAPTER = "org.mozilla.javascript.JavaAdapter";
    private static final String SCRIPTABLE = "org.mozilla.javascript.Scriptable";
    private static final String CONTEXT_FACTORY = "org.mozilla.javascript.ContextFactory";

    /** Rhino's Context class, looked up once; absent for every application that does not ship Rhino. */
    private static Class<?> contextClass;
    private static boolean contextLookupDone;

    /** What installAdapter built, re-applied by beforeSerialize after the sink payloads have been set. */
    private static Object builtAdapter;
    private static Object builtDelegee;

    private static boolean isInstanceOf(Object obj, String className) {
        for (Class<?> c = obj == null ? null : obj.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    /** Called for every backbone object right after it is created. */
    static void prepare(Object obj) throws GeneratorException {
        if (!isInstanceOf(obj, NATIVE_JAVA_OBJECT)) {
            return;
        }
        try {
            Object scope = ReflectionUtils.createWithNoArgsConstructor(NATIVE_OBJECT);
            Object cache = ReflectionUtils.createWithNoArgsConstructor(CLASS_CACHE);
            ReflectionUtils.invokeMethodByName(cache, "associate", scope);
            ReflectionUtils.setFieldValue(obj, "parent", scope);
        } catch (Exception e) {
            throw new GeneratorException("can not give the " + obj.getClass().getName() + " a scope with a ClassCache", e);
        }
    }

    private static Class<?> contextClass() {
        if (!contextLookupDone) {
            contextLookupDone = true;
            try {
                contextClass = Class.forName(CONTEXT, false, ClassUtils.class.getClassLoader());
            } catch (Throwable e) {
                try {
                    contextClass = ClassUtils.getClassByName(CONTEXT);
                } catch (Throwable e2) {
                    contextClass = null;
                }
            }
        }
        return contextClass;
    }

    /**
     * Establishes the thread state the MozillaRhino1 chain needs before the seed is deserialized, and returns the
     * token that exitContext() has to be given; null means there is nothing to undo.
     * <p>
     * The chain reads a property of a NativeError and ends up in ScriptableObject.getImpl. For a getter that is a
     * Function - the case here - that method calls Context.getContext(), and Rhino throws "No Context associated
     * with current Thread" when the thread has none. A Context is thread state, not a part of the object graph, so
     * no seed can carry it; in a real target it is there because the application entered one around the code that
     * deserializes. The validator enters one around the deserialization and exits afterwards, so the run answers
     * whether the chain reaches its sink once that precondition holds, and the precondition is reported with the
     * result rather than being hidden in the verdict.
     * <p>
     * Does nothing for an application without Rhino on the classpath, and nothing when the thread already has a
     * Context (then the run is not the one that established it).
     */
    public static Object enterContext() {
        Class<?> cls = contextClass();
        if (cls == null) {
            return null;
        }
        try {
            Method current = cls.getMethod("getCurrentContext");
            if (current.invoke(null) != null) {
                return null; // already there, leave the thread as it is
            }
            Object entered = cls.getMethod("enter").invoke(null);
            LogUtils.info("RhinoWorkaround: entered a Rhino Context for the validation "
                    + "(precondition of the chain: ScriptableObject.getImpl calls Context.getContext())");
            return entered;
        } catch (Throwable e) {
            LogUtils.warn("RhinoWorkaround: can not enter a Rhino Context: " + e);
            return null;
        }
    }

    /** Undoes enterContext(); a null token means it did nothing. */
    public static void exitContext(Object entered) {
        if (entered == null) {
            return;
        }
        try {
            contextClass().getMethod("exit").invoke(null);
        } catch (Throwable e) {
            LogUtils.warn("RhinoWorkaround: can not exit the Rhino Context: " + e);
        }
    }

    private static Class<?> rhinoClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, contextClass().getClassLoader());
    }

    /** A top-level scope with a ClassCache, which JavaMembers.lookupClass / ClassCache.get need. */
    private static Object scopeWithClassCache() throws Exception {
        Object scope = ReflectionUtils.createWithNoArgsConstructor(NATIVE_OBJECT);
        Object cache = ReflectionUtils.createWithNoArgsConstructor(CLASS_CACHE);
        ReflectionUtils.invokeMethodByName(cache, "associate", scope);
        return scope;
    }

    /**
     * Sets up the delegee the chain actually runs on, and returns false if it cannot.
     * <p>
     * After readAdapterObject reads the delegee it hands it to getObjectFunctionNames, which walks
     * ScriptableObject.getPropertyIds(delegee) and reads each String id back with getProperty. Two things follow.
     * <p>
     * NativeJavaArray.getIds() returns only the array's Integer indices, and getPropertyIds collects the ids of the
     * prototype chain as well, so the String id has to come from the delegee's prototype; that prototype gets one
     * property, named after the oracle's bean property.
     * <p>
     * getProperty then reaches JavaMembers.get, which only calls MemberBox.invoke - the sink - for a BeanProperty.
     * JavaMembers is transient and rebuilt by NativeJavaObject.readObject (initMembers) by reflecting javaObject's
     * class, so a MemberBox put into the seed is thrown away: the member that is invoked is always a real bean getter
     * of javaObject's class. javaObject is therefore an OracleClassA, whose getOracleProbe() is a bean getter whose
     * property name no field of that class takes (getOracleField and getAnOracleMethod are both shadowed).
     */
    private static boolean prepareDelegee(Object delegee, String delegeeKey, HashSet<String> immutableFldSet)
            throws Exception {
        Class<?> scriptable = rhinoClass(SCRIPTABLE);
        String property = OracleClassA.ofOracleProbePropertyName();

        // the String id getObjectFunctionNames iterates to, supplied by the prototype
        Object proto = ReflectionUtils.createWithNoArgsConstructor(NATIVE_OBJECT);
        proto.getClass().getMethod("put", String.class, scriptable, Object.class)
                .invoke(proto, property, proto, "v");
        ReflectionUtils.setFieldValue(delegee, "prototype", proto);
        ReflectionUtils.setFieldValue(delegee, "parent", scopeWithClassCache());

        // the object whose bean getters initMembers reflects, i.e. what MemberBox.invoke ends up calling
        ReflectionUtils.setFieldValue(delegee, "javaObject", new OracleClassA());
        ReflectionUtils.setFieldValue(delegee, "staticType", null);

        if (ReflectionUtils.getFieldValue(delegee, "array") == null) {
            ReflectionUtils.setFieldValue(delegee, "array", new String[]{"v"});
            ReflectionUtils.setFieldValue(delegee, "length", 1);
            ReflectionUtils.setFieldValue(delegee, "cls", String.class);
        }
        for (String f : new String[]{"prototype", "parent", "javaObject", "staticType", "array", "length", "cls"}) {
            immutableFldSet.add(delegeeKey + "#" + f);
        }
        LogUtils.info("RhinoWorkaround: delegee " + NATIVE_JAVA_ARRAY + " wraps an OracleClassA and its prototype"
                + " carries the property \"" + property + "\" the chain reads");
        return true;
    }

    /** The object of the first record of this class, or null. */
    private static Object firstOf(List<ClassRecord> records, String className, HashMap<String, Object> backBoneObjMap) {
        for (ClassRecord rcd : records) {
            if (className.equals(rcd.getClassName())) {
                return FuzzUtils.getCachedObject(rcd.getId(), null, className, backBoneObjMap);
            }
        }
        return null;
    }

    private static String firstKeyOf(List<ClassRecord> records, String className) {
        for (ClassRecord rcd : records) {
            if (className.equals(rcd.getClassName())) {
                return FuzzUtils.getRealClassNameKey(rcd.getId(), null, className);
            }
        }
        return null;
    }

    /**
     * Makes the root NativeJavaObject of the MozillaRhino2 chain take the adapter branch of its readObject.
     * <p>
     * The chain's first hop is NativeJavaObject.readObject -> JavaAdapter.readAdapterObject, and readObject only
     * calls it when isAdapter is true. With isAdapter true the matching writeObject hands javaObject to
     * JavaAdapter.writeAdapterObject, which reads the public field `delegee` off it, so javaObject has to be an
     * instance of a class JavaAdapter generated. The field-by-field assembly cannot build one, so the seed stays at
     * isAdapter false and the chain stops at its first hop.
     * <p>
     * One is built here with Rhino's own generator (its private getAdapterClass and the generated
     * (ContextFactory, Scriptable, Scriptable) constructor), for a throw-away delegee, so that assembling the seed
     * does not already walk the chain's own objects. Its `delegee` is then pointed at the seed's NativeJavaArray:
     * that is what writeAdapterObject puts in the stream, and what readAdapterObject reads back and hands to
     * getAdapterClass -> getObjectFunctionNames, where the chain continues.
     * <p>
     * Does nothing without Rhino on the classpath, or for a seed that has no NativeJavaObject plus NativeJavaArray.
     */
    static void installAdapter(List<ClassRecord> records, HashMap<String, Object> backBoneObjMap,
                               HashSet<String> immutableFldSet) {
        if (contextClass() == null) {
            return;
        }
        Object root = firstOf(records, NATIVE_JAVA_OBJECT, backBoneObjMap);
        Object delegee = firstOf(records, NATIVE_JAVA_ARRAY, backBoneObjMap);
        String rootKey = firstKeyOf(records, NATIVE_JAVA_OBJECT);
        String delegeeKey = firstKeyOf(records, NATIVE_JAVA_ARRAY);
        if (root == null || delegee == null || rootKey == null || delegeeKey == null) {
            return;
        }
        Object entered = enterContext();
        try {
            if (!prepareDelegee(delegee, delegeeKey, immutableFldSet)) {
                return;
            }
            Class<?> scriptable = rhinoClass(SCRIPTABLE);
            Object scope = scopeWithClassCache();

            Object throwAway = ReflectionUtils.createWithNoArgsConstructor(NATIVE_OBJECT);
            java.lang.reflect.Method getAdapterClass = rhinoClass(JAVA_ADAPTER).getDeclaredMethod(
                    "getAdapterClass", scriptable, Class.class, Class[].class, scriptable);
            getAdapterClass.setAccessible(true);
            Class<?> adapterClass = (Class<?>) getAdapterClass.invoke(
                    null, scope, Object.class, new Class<?>[0], throwAway);
            Object adapter = adapterClass.getConstructor(rhinoClass(CONTEXT_FACTORY), scriptable, scriptable)
                    .newInstance(null, throwAway, scope);

            ReflectionUtils.setFieldValue(adapter, "delegee", delegee);
            builtAdapter = adapter;
            builtDelegee = delegee;
            ReflectionUtils.setFieldValue(root, "javaObject", adapter);
            ReflectionUtils.setFieldValue(root, "isAdapter", Boolean.TRUE);
            ReflectionUtils.setFieldValue(root, "parent", scope);
            ReflectionUtils.setFieldValue(root, "staticType", null);
            for (String f : new String[]{"javaObject", "isAdapter", "parent", "staticType"}) {
                immutableFldSet.add(rootKey + "#" + f);
            }
            LogUtils.info("RhinoWorkaround: gave the " + NATIVE_JAVA_OBJECT + " an adapter object ("
                    + adapterClass.getName() + ") whose delegee is the seed's " + NATIVE_JAVA_ARRAY);
        } catch (Throwable e) {
            LogUtils.warn("RhinoWorkaround: can not build the adapter object: " + e);
        } finally {
            exitContext(entered);
        }
    }

    /**
     * Puts the adapter back on the root right before the seed is serialized.
     * <p>
     * The sink payloads are set after the backbone has been assembled, and this chain's sink field is
     * NativeJavaObject.javaObject, the very field that has to hold the adapter: the payload overwrites it, and then
     * NativeJavaObject.writeObject hands a non-adapter to JavaAdapter.writeAdapterObject, which cannot read a
     * `delegee` off it and throws, so the seed never reaches the stream and the chain looks as if it never started.
     * The field is restored here, as the last step before serialization. A no-op without Rhino, and for a seed this
     * class did not build an adapter for.
     */
    public static void beforeSerialize(Object seedRoot) {
        if (builtAdapter == null || seedRoot == null || !NATIVE_JAVA_OBJECT.equals(seedRoot.getClass().getName())) {
            return;
        }
        try {
            Object current = ReflectionUtils.getFieldValue(seedRoot, "javaObject");
            if (current != builtAdapter) {
                LogUtils.info("RhinoWorkaround: javaObject of the root was "
                        + (current == null ? "null" : current.getClass().getName())
                        + " before serialization; putting the adapter back");
                ReflectionUtils.setFieldValue(seedRoot, "javaObject", builtAdapter);
            }
            ReflectionUtils.setFieldValue(seedRoot, "isAdapter", Boolean.TRUE);
            if (builtDelegee != null) {
                Object dj = ReflectionUtils.getFieldValue(builtDelegee, "javaObject");
                if (!(dj instanceof OracleClassA)) {
                    LogUtils.info("RhinoWorkaround: javaObject of the delegee was "
                            + (dj == null ? "null" : dj.getClass().getName()) + "; putting an OracleClassA back");
                    ReflectionUtils.setFieldValue(builtDelegee, "javaObject", new OracleClassA());
                }
            }
        } catch (Throwable e) {
            LogUtils.warn("RhinoWorkaround: can not restore the adapter before serialization: " + e);
        }
    }

    private static boolean isSlotRecord(ClassRecord rcd) {
        Pair<String, String> pred = rcd.getPredecessorRecord();
        return pred != null && GETTER_SLOT.equals(rcd.getClassName()) && NATIVE_ERROR.equals(pred.getKey())
                && pred.getValue() != null && pred.getValue().endsWith(" lastAdded>");
    }

    private static boolean isGetterRecord(ClassRecord rcd) {
        Pair<String, String> pred = rcd.getPredecessorRecord();
        return pred != null && NATIVE_JAVA_METHOD.equals(rcd.getClassName()) && GETTER_SLOT.equals(pred.getKey())
                && pred.getValue() != null && pred.getValue().endsWith(" getter>");
    }

    /**
     * Assembles the record at `index` if it belongs to the getter slot of a NativeError. Returns false, without
     * changing anything, for any other record.
     */
    static boolean assemble(List<ClassRecord> records, int index, HashMap<String, Object> backBoneObjMap,
                            HashSet<String> immutableFldSet) throws GeneratorException {
        ClassRecord rcd = records.get(index);
        if (isGetterRecord(rcd)) {
            return true; // installed together with the slot record
        }
        if (!isSlotRecord(rcd)) {
            return false;
        }
        ClassRecord errorRcd = null, getterRcd = null;
        for (int i = 0; i < records.size(); i++) {
            if (i < index && NATIVE_ERROR.equals(records.get(i).getClassName())) {
                errorRcd = records.get(i);
            }
            if (i > index && getterRcd == null && isGetterRecord(records.get(i))) {
                getterRcd = records.get(i);
            }
        }
        if (errorRcd == null || getterRcd == null) {
            return false;
        }
        Object error = FuzzUtils.getCachedObject(errorRcd.getId(), null, NATIVE_ERROR, backBoneObjMap);
        Object getter = FuzzUtils.getCachedObject(getterRcd.getId(), null, NATIVE_JAVA_METHOD, backBoneObjMap);
        if (error == null || getter == null) {
            return false;
        }
        try {
            ReflectionUtils.invokeMethodByName(error, "setGetterOrSetter", GETTER_PROPERTY, 0, getter, Boolean.FALSE);
        } catch (Exception e) {
            throw new GeneratorException("can not install the getter of " + NATIVE_ERROR, e);
        }
        immutableFldSet.add(FuzzUtils.getRealClassNameKey(errorRcd.getId(), null, NATIVE_ERROR) + "#lastAdded");
        LogUtils.info("RhinoWorkaround: installed the getter \"" + GETTER_PROPERTY + "\" on " + NATIVE_ERROR);
        return true;
    }
}
