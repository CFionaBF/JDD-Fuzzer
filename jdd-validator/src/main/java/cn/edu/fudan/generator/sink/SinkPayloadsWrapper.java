package cn.edu.fudan.generator.sink;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import cn.edu.fudan.datastructure.OracleClassA;
import cn.edu.fudan.exception.validator.GeneratorException;
import cn.edu.fudan.exception.validator.JDDFuzzingException;
import cn.edu.fudan.exception.validator.NoUsableSinkRecordException;
import cn.edu.fudan.exception.validator.ObjectNotFoundException;
import cn.edu.fudan.exception.validator.ReflectionException;
import cn.edu.fudan.exception.validator.SinkPayloadsGeneratorException;
import cn.edu.fudan.exception.validator.SinkPayloadsSettingsException;
import cn.edu.fudan.generator.seed.SeedWrapper;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.FuzzUtils;
import cn.edu.fudan.utils.LogUtils;
import cn.edu.fudan.utils.ReflectionUtils;
import datastructrue.Pair;
import datastructrue.inst.JDDFuzzRecord;
import datastructrue.iocd.IOCD;
import datastructrue.iocd.unit.FieldRecord;
import datastructrue.iocd.unit.SinkRecord;

/**
 * 该数据结构是一个全局的数据结构，用于跟踪记录使用过的sinkrecord
 */
public class SinkPayloadsWrapper {

    public Integer iocdHash;
    public SinkTypeEnum.SinkType sinkType;
    // 用于存储sink field的签名和payload对应的Object
    public ArrayList<Pair<String, Object>> fldSigAndPayloadList;
    // 当前iocd中包含的所有SinkRecord
    // Thinking: 考虑使用有限队列进行排序
    public List<SinkRecord> sinkRecordList;
    public LinkedHashSet<Integer> usedSinkRecordIndexSet;
    private int lastSinkRecordIndex;
    private boolean isSpecialDynamicProxyClassSinkDone;

    public SinkPayloadsWrapper() {
        this.iocdHash = -1;
        this.fldSigAndPayloadList = new ArrayList<>();
        this.sinkRecordList = new ArrayList<>();
    }

    public SinkPayloadsWrapper(IOCD iocd) throws GeneratorException, ReflectionException {
        this.sinkType = SinkTypeEnum.transformSinkType(iocd.getSinkType());
        this.iocdHash = iocd.getHashCode();
        this.sinkRecordList = iocd.getSinkRecords();
        this.usedSinkRecordIndexSet = new LinkedHashSet<>();
        boolean isSinkPayloadsGenerated = false;
        while (!isSinkPayloadsGenerated) {
            try {
                flushSinkPayloads();
                isSinkPayloadsGenerated = true;
            } catch (GeneratorException e) {
                if (e instanceof NoUsableSinkRecordException) {
                    LogUtils.warn("In `SinkPayloadsWrapper.<init>`: no usable SinkRecord, can not generate SinkPayloads");
                    throw e;
                } else if (e instanceof SinkPayloadsGeneratorException) {
                    LogUtils.warn2Log("In `SinkPayloadsWrapper.<init>`: SinkPayloads generation failed, try to generate again by switching to another SinkRecord");
                } else {
                    throw e;
                }
            }
        }
    }

    public void doSinkPayloadsSetting(SeedWrapper seed) throws SinkPayloadsSettingsException {
        try {
            if (isSpecialDynamicProxyClass(this.iocdHash)) {
                doSinkPayloadSettingForSpecialDynamicProxyClass(seed);
            } else {
                int sinkIndex = CommonUtils.getLastItemOfLinkedHashSet(this.usedSinkRecordIndexSet);
                seed.sinkIndex = sinkIndex;
                doSinkPayloadSetting0Wrapper(seed, this.fldSigAndPayloadList);
            }
        } catch (JDDFuzzingException | ReflectionException | GeneratorException e) {
            throw new SinkPayloadsSettingsException(e.getMessage(), e);
        }
    }

    public void doSinkPayloadSettingInClone(SeedWrapper seed) throws GeneratorException, ReflectionException {
        try {
            if (isSpecialDynamicProxyClass(this.iocdHash)) {
                doSinkPayloadSettingForSpecialDynamicProxyClass(seed);
            } else {
                SinkRecord sinkRecord = this.sinkRecordList.get(seed.sinkIndex);
                ArrayList<Pair<String, Object>> tmpFldSigAndPayloadList = SinkPayloadsGenerator.genSinkPayloadsList(this.sinkType, sinkRecord, this.iocdHash);
                doSinkPayloadSetting0Wrapper(seed, tmpFldSigAndPayloadList);
            }
        } catch (JDDFuzzingException | ReflectionException e) {
            throw new SinkPayloadsSettingsException(e.getMessage(), e);
        }
    }

    private void doSinkPayloadSetting0Wrapper(SeedWrapper seed, ArrayList<Pair<String, Object>> fldSigAndPayloadList) throws ReflectionException, ObjectNotFoundException, GeneratorException {
        if (this.sinkType.equals(SinkTypeEnum.SinkType.EXPRESSION_INJECTION)) {
        } else {
            doSinkPayloadSetting0(seed, fldSigAndPayloadList);
        }
    }

    private void doSinkPayloadSetting0(SeedWrapper seed, ArrayList<Pair<String, Object>> fldSigAndPayloadList) throws ObjectNotFoundException, ReflectionException, GeneratorException {
        // JDDFuzzRecord.expectedMarker (what the marker oracle compares the sink argument with) is set by the
        // family's payload generator, since the token differs per family (name, class name, host literal)
        for (Pair<String, Object> fldSigAndPayload : fldSigAndPayloadList) {
            String fldSigWithId = fldSigAndPayload.getKey();
            String fldSig = fldSigWithId.substring(0, fldSigWithId.indexOf("#"));
            // the part after the sig is "<classId>" or, when the payload names the target object explicitly,
            // "<classId>#<classBelongTo>" (used when the field's declaring class is not the object's class,
            // e.g. a receiver field declared on a superclass the seed has no object for).
            String rest = fldSigWithId.substring(fldSigWithId.indexOf("#") + 1);
            String fldClzBelongToId;
            String classBelongToOverride = null;
            int h2 = rest.indexOf("#");
            if (h2 >= 0) {
                fldClzBelongToId = rest.substring(0, h2);
                classBelongToOverride = rest.substring(h2 + 1);
            } else {
                fldClzBelongToId = rest;
            }
            String fldClzBelongTo = classBelongToOverride != null ? classBelongToOverride : CommonUtils.getDeclaredClzBySig(fldSig);
            String fldName = CommonUtils.getFieldNameBySig(fldSig);
            // Thinking: 这里为了简单处理，将相同CollisionClass对应的field都设置了，但是是否合理？
            if (ValidatorGlobalDataContainer.isCollisionClassWrapper(seed.iocdHash, fldClzBelongTo, Integer.parseInt(fldClzBelongToId)) == 0) {
                String finalClzName1 = FuzzUtils.getRealClassNameKey(Integer.parseInt(fldClzBelongToId), "C1", fldClzBelongTo);
                String finalClzName2 = FuzzUtils.getRealClassNameKey(Integer.parseInt(fldClzBelongToId), "C2", fldClzBelongTo);
                Object objToSet1 = seed.getObjFromInjectionObjByName(finalClzName1);
                Object objToSet2 = seed.getObjFromInjectionObjByName(finalClzName2);
                Object fieldObj1 = null, fieldObj2 = null;
                // For hash-collision cases, we make the field in different `collision object` different
                if (SinkPayloadsGenerator.crtSinkClassBelongToObjKey.containsKey(fldSigWithId)) {
                    FieldRecord sinkClassFieldRecord = SinkPayloadsGenerator.crtSinkClassBelongToObjKey.get(fldSigWithId);
                    fieldObj1 = SinkPayloadsGenerator.assembleSinkClassBelongToPayload(sinkClassFieldRecord);
                    fieldObj2 = SinkPayloadsGenerator.assembleSinkClassBelongToPayload(sinkClassFieldRecord);
                } else {
                    fieldObj1 = fldSigAndPayload.getValue();
                    fieldObj2 = fldSigAndPayload.getValue();
                }
                ReflectionUtils.setFieldValue(objToSet1, fldName, fieldObj1);
                ReflectionUtils.setFieldValue(objToSet2, fldName, fieldObj2);
                // 记录用了那个sink进行设置，以及对应的类标签和field名字
                seed.crtSinkObjectMap.put(finalClzName1 + "#" + fldName, fieldObj1);
                seed.crtSinkObjectMap.put(finalClzName2 + "#" + fldName, fieldObj2);
                seed.immutableFieldInfoSet.add(finalClzName1 + "#" + fldName);
                seed.immutableFieldInfoSet.add(finalClzName2 + "#" + fldName);
            } else {
                if (fldClzBelongTo.equals(FuzzUtils.JAVAX_CON_NOT_PROC_EXCEPTION) ||
                        fldClzBelongTo.equals(FuzzUtils.JAVAX_NAMING_NAMING_EXCEPTION)) {
                    Object cpe = ReflectionUtils.createWithNoArgsConstructor(FuzzUtils.JAVAX_CON_NOT_PROC_EXCEPTION);
                    ReflectionUtils.setFieldValue(cpe, "cause", null);
                    ReflectionUtils.setFieldValue(cpe, "stackTrace", null);
                    ReflectionUtils.setFieldValue(cpe, "suppressedExceptions", null);
                    setSinkPointWithWrapMethod(cpe, fldName, fldSigAndPayload.getValue());
                    // set cpe to the related backbone object
                    for (String bbObjKey : seed.backBoneObjectMap.keySet()) {
                        if (bbObjKey.startsWith("javax.naming.spi.ContinuationContext")) {
                            Object obj = seed.getObjFromInjectionObjByName(bbObjKey);
                            ReflectionUtils.setFieldValue(obj, "cpe", cpe);
                            ReflectionUtils.setFieldValue(obj, "env", new Hashtable<>());
                            seed.immutableFieldInfoSet.add(bbObjKey + "#cpe");
                            seed.immutableFieldInfoSet.add(bbObjKey + "#env");
                        }
                    }
                } else {
                    String finalClzName = FuzzUtils.getRealClassNameKey(Integer.parseInt(fldClzBelongToId), null, fldClzBelongTo);
                    Object objToSet = seed.getObjFromInjectionObjByName(finalClzName);
                    ReflectionUtils.setFieldValue(objToSet, fldName, fldSigAndPayload.getValue());
                    seed.crtSinkObjectMap.put(finalClzName + "#" + fldName, fldSigAndPayload.getValue());
                    seed.immutableFieldInfoSet.add(finalClzName + "#" + fldName);
                    handleJNDISinkObj(objToSet, finalClzName, seed);
                    handleCustomSinkObj(objToSet, finalClzName, seed);
                    handleClojureSinkObj(objToSet, finalClzName, fldName, fldSigAndPayload.getValue(), seed);
                }

            }

        }
        if (this.sinkType == SinkTypeEnum.SinkType.CLOJURE && JDDFuzzRecord.expectedMarker != null) {
            handleClojureProxies(seed, SinkPayloadsGenerator.getClojureMarkerLiteral(JDDFuzzRecord.expectedMarker));
        }
    }

    // clojure.lang.Iterate: first() calls f on prevSeed only while _seed is the class's private "unrealized"
    // sentinel, which a deserialized Iterate never holds; next() calls f on the realized _seed instead. So the
    // marker literal set on prevSeed goes into _seed too.
    private void handleClojureSinkObj(Object objToSet, String finalClzName, String fldName, Object payload, SeedWrapper seed) throws ReflectionException {
        if (this.sinkType != SinkTypeEnum.SinkType.CLOJURE || !"clojure.lang.Iterate".equals(objToSet.getClass().getName())
                || !"prevSeed".equals(fldName)) {
            return;
        }
        ReflectionUtils.setFieldValue(objToSet, "_seed", payload);
        seed.immutableFieldInfoSet.add(finalClzName + "#_seed");
    }

    // A clojure proxy (proxy$...) runs the function its __clojureFnMap holds for the called method. For a CLOJURE
    // IOCD every method of the chain's proxies maps to (comp eval-opt (constantly <marker literal>)): eval-opt gets
    // the inert string literal whatever the method's arguments are (upstream did this for one proxy class and
    // method only, with a fixed placeholder text).
    private void handleClojureProxies(SeedWrapper seed, String literal) throws ReflectionException {
        Object fn = null;
        for (Map.Entry<String, Object> e : seed.backBoneObjectMap.entrySet()) {
            Object obj = e.getValue();
            if (obj == null || !hasMethodNamed(obj.getClass(), "__initClojureFnMappings")) {
                continue;
            }
            try {
                if (fn == null) {
                    Object evalOpt = ReflectionUtils.createWithNoArgsConstructor("clojure.main$eval_opt");
                    Object constantly = ReflectionUtils.invokeMethodByName(
                            ReflectionUtils.createWithNoArgsConstructor("clojure.core$constantly"), "invoke", literal);
                    fn = ReflectionUtils.invokeMethodByName(
                            ReflectionUtils.createWithNoArgsConstructor("clojure.core$comp"), "invoke", evalOpt, constantly);
                }
                Map<String, Object> fnMap = new HashMap<>();
                for (Method m : obj.getClass().getDeclaredMethods()) {
                    if (!m.getName().startsWith("__")) {
                        fnMap.put(m.getName(), fn);
                    }
                }
                Object persistentMap = ReflectionUtils.invokeMethodByName(
                        ClassUtils.wrapperClassForName("clojure.lang.PersistentArrayMap"), "create", fnMap);
                ReflectionUtils.invokeMethodByName(obj, "__initClojureFnMappings", persistentMap);
                seed.immutableFieldInfoSet.add(e.getKey() + "#__clojureFnMap");
            } catch (Exception ex) {
                LogUtils.warn("In `handleClojureProxies`: can not set the functions of " + obj.getClass().getName() + ": " + ex);
            }
        }
    }

    private static boolean hasMethodNamed(Class<?> c, String name) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }


    // groovy.lang.GString.writeTo walks getStrings() before it reaches the closure in `values`. A GString made
    // without running its constructor has no strings array (or, for the anonymous subclasses, a captured one that
    // is null), so it throws before the sink. Give it the one-element array its constructor would have been
    // given, in whichever class of the hierarchy declares it, and keep it out of mutation.
    private void handleCustomSinkObj(Object objToSet, String finalClzName, SeedWrapper seed) throws ReflectionException {
        boolean isGString = false;
        for (Class<?> c = objToSet.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("groovy.lang.GString")) {
                isGString = true;
                break;
            }
        }
        if (!isGString) {
            return;
        }
        for (Class<?> c = objToSet.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == String[].class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    ReflectionUtils.setAccessible(f);
                    try {
                        if (f.get(objToSet) == null) {
                            ReflectionUtils.setFieldValue(objToSet, f, new String[] {""});
                            seed.immutableFieldInfoSet.add(finalClzName + "#" + f.getName());
                        }
                    } catch (IllegalAccessException e) {
                        throw new ReflectionException("can not read " + f + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    private void handleJNDISinkObj(Object objToSet, String finalClzName, SeedWrapper seed) throws ReflectionException {
        if (objToSet.getClass().getName().equals(FuzzUtils.XBEAN_READ_ONLY_BINDING)) {
            Object ctx = ReflectionUtils.createWithNoArgsConstructor(FuzzUtils.XBEAN_WRITABLE_CTX);
            ReflectionUtils.setFieldValue(objToSet, "context", ctx);
            ReflectionUtils.setFieldValue(objToSet, "name", "xyz");
            seed.immutableFieldInfoSet.add(finalClzName + "#context");
            seed.immutableFieldInfoSet.add(finalClzName + "#name");
        } else if (objToSet.getClass().getName().equals("org.springframework.jndi.support.SimpleJndiBeanFactory")) {
            // getAdvice() first calls isSingleton(adviceBeanName) == shareableResources.contains(name); when
            // false it synchronizes on adviceMonitor, which a Hessian round trip leaves null -> NPE before
            // the lookup. Prime shareableResources with the same marker so isSingleton is true and the chain
            // reaches the sink. singletonObjects must stay an empty Map so doGetSingleton falls through to
            // lookup. Pin the receiver JndiTemplate's environment null so no initial context factory is read.
            String marker = FuzzUtils.getReachMarker(seed.iocdHash);
            java.util.Set<String> share = new java.util.HashSet<>();
            share.add(marker);
            ReflectionUtils.setFieldValue(objToSet, "shareableResources", share);
            Object jt = ReflectionUtils.getFieldValue(objToSet, "jndiTemplate");
            if (jt != null && !(jt instanceof cn.edu.fudan.datastructure.NoneObject)) {
                ReflectionUtils.setFieldValue(jt, "environment", null);
            }
            seed.immutableFieldInfoSet.add(finalClzName + "#shareableResources");
            seed.immutableFieldInfoSet.add(finalClzName + "#jndiTemplate");
        }
    }

    private void doSinkPayloadSettingForSpecialDynamicProxyClass(SeedWrapper seed) throws GeneratorException, ReflectionException {
        String specialClzName = GlobalDataContainer.getIOCDByHash(this.iocdHash).getFirstClzName();
        List<String> specialDynamicProxyClassList = FuzzUtils.getSpecialDynamicProxyRelatedClassList(specialClzName);
        String mtdMetadataName = specialDynamicProxyClassList.get(2);
        String itcptMetadataName = specialDynamicProxyClassList.get(3);
        String itcptModelName = specialDynamicProxyClassList.get(4);
        String itcptTypeName = specialDynamicProxyClassList.get(5);

        // Setting sink method to be invoked. 
        Object mtdMetadata = FuzzUtils.getCachedObject(FuzzUtils.getDefaultClassNameKey(mtdMetadataName), seed.backBoneObjectMap);
        Method oracleMethod = OracleClassA.ofGetOracleField();
        Object postActivate = ReflectionUtils.getEnumValue(ClassUtils.getClassByName(itcptTypeName), "POST_ACTIVATE");
        Set<Object> interceptTypeSet = new HashSet<>();
        interceptTypeSet.add(postActivate);
        ReflectionUtils.setFieldValue(mtdMetadata, "supportedInterceptorTypes", interceptTypeSet);
        ReflectionUtils.setFieldValue(mtdMetadata, "javaMethod", oracleMethod);
        seed.immutableFieldInfoSet.add(FuzzUtils.getDefaultClassNameKey(mtdMetadataName) + "#javaMethod");
        seed.immutableFieldInfoSet.add(FuzzUtils.getDefaultClassNameKey(mtdMetadataName) + "#supportedInterceptorTypes");


        // Setting sink object. 
        Object specialProxyObj = FuzzUtils.getCachedObject(FuzzUtils.getDefaultClassNameKey(specialClzName), seed.backBoneObjectMap);
        Object itcptMetadata = FuzzUtils.getCachedObject(FuzzUtils.getDefaultClassNameKey(itcptMetadataName), seed.backBoneObjectMap);
        Object oracleObj = OracleClassA.ofOracleClassA();
        Object itcptHandlerInstances = ReflectionUtils.getFieldValue(specialProxyObj, "interceptorHandlerInstances");
        ReflectionUtils.invokeMethodByName(itcptHandlerInstances, "put", itcptMetadata, oracleObj);
        seed.immutableFieldInfoSet.add(FuzzUtils.getDefaultClassNameKey(specialClzName) + "#interceptorHandlerInstances");

        Object itcptModel = FuzzUtils.getCachedObject(FuzzUtils.getDefaultClassNameKey(itcptModelName), seed.backBoneObjectMap);
        List<Object> itcptMetadataList = new ArrayList<>();
        itcptMetadataList.add(itcptMetadata);
        Map<Object, Object> tmpMap = new HashMap<>();
        tmpMap.put(postActivate, itcptMetadataList);
        Set<Object> allInterceptors = new HashSet<>();
        allInterceptors.addAll(itcptMetadataList);
        ReflectionUtils.setFieldValue(itcptModel, "globalInterceptors", tmpMap);
        ReflectionUtils.setFieldValue(itcptModel, "allInterceptors", allInterceptors);
        seed.immutableFieldInfoSet.add(FuzzUtils.getDefaultClassNameKey(itcptModelName) + "#globalInterceptors");
        seed.immutableFieldInfoSet.add(FuzzUtils.getDefaultClassNameKey(itcptModelName) + "#allInterceptors");
        
    }

    private void setSinkPointWithWrapMethod(Object objToSet, String fldName, Object payload) throws ReflectionException {
        Field fld = ReflectionUtils.getField(objToSet.getClass(), fldName);
        if (fld.getType().isArray()) {

        } else {
            ReflectionUtils.setFieldValue(objToSet, fldName, payload);
        }
    }

    public void flushSinkPayloads() throws GeneratorException, ReflectionException {
        boolean isGenerated = false;
        while(!isGenerated) {
            try {
                if (isSpecialDynamicProxyClass(this.iocdHash)) {
                    // For special dynamic proxy class, we try to use custom method to generate sink payloads. 
                    //  So, we do nothing here.
                    if (!this.isSpecialDynamicProxyClassSinkDone) {
                        this.isSpecialDynamicProxyClassSinkDone = true;
                        return;
                    } else {
                        throw new NoUsableSinkRecordException("No usable sink record left");
                    }
                } else {
                    if (this.usedSinkRecordIndexSet.size() == this.sinkRecordList.size()) {
                        throw new NoUsableSinkRecordException("No usable sink record left");
                    }
                    int newSinkRcdIndex = -1;
                    for (int i = 0; i < sinkRecordList.size(); i++) {
                        if (usedSinkRecordIndexSet.contains(i)) {
                            continue;
                        }
                        newSinkRcdIndex = i;
                        break;
                    }
                    if (newSinkRcdIndex == -1) {
                        throw new NoUsableSinkRecordException("No usable sink record left");
                    }
                    usedSinkRecordIndexSet.add(newSinkRcdIndex);
                    this.fldSigAndPayloadList = SinkPayloadsGenerator.genSinkPayloadsList(
                            this.sinkType,
                            this.sinkRecordList.get(newSinkRcdIndex),
                            this.iocdHash
                    );
                }
                
                isGenerated = true;
            } catch (SinkPayloadsGeneratorException e) {
                if (e instanceof NoUsableSinkRecordException) throw e;
                LogUtils.warn("In `SinkPayloadsWrapper.flushSinkPayloads`: seed " + iocdHash + "'s SinkPayloads generation failed, try to generate again by switching to another SinkRecord");
            }
        }
        
    }

    private boolean isSpecialDynamicProxyClass(int iocdHash) {
        IOCD iocd = GlobalDataContainer.getIOCDByHash(iocdHash);
        if (iocd == null) {
            return false;
        }
        return FuzzUtils.isSpecialDynamicProxyClass(iocd.getFirstClzName());
    }

}
