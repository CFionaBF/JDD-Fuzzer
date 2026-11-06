package cn.edu.fudan.generator.seed;

import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import cn.edu.fudan.datastructure.NoneObject;
import cn.edu.fudan.datastructure.OracleClassA;
import cn.edu.fudan.exception.validator.*;
import cn.edu.fudan.generator.condition.ConditionObjsWrapper;
import cn.edu.fudan.generator.fielddependency.FieldDependencyWrapper;
import cn.edu.fudan.generator.sink.SinkPayloadsWrapper;
import cn.edu.fudan.utils.*;
import com.pholser.junit.quickcheck.internal.generator.GeneratorRepository;
import com.pholser.junit.quickcheck.internal.generator.ServiceLoaderGeneratorSource;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import com.sun.istack.NotNull;
import datastructrue.IntWrapper;
import datastructrue.Pair;
import datastructrue.iocd.IOCD;
import datastructrue.iocd.unit.ClassRecord;
import datastructrue.iocd.unit.FieldRecord;
import datastructrue.iocd.unit.SourceRecord;

import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.*;

public class SeedGenerator {

    static final boolean isSinkPayloadSettingDebug = true;

    static final int NORMAL_SEED = 0;
    static final int COLLISION_SEED = 2;

    static final int TYPE1_COLLISION_START_INDEX = 3;
    static final int TYPE2_COLLISION_START_INDEX = 2;

    public static SourceOfRandomness randomness = new SourceOfRandomness(new Random(42));
    public static GeneratorRepository generatorRepository = new GeneratorRepository(randomness).register(new ServiceLoaderGeneratorSource());

    private static final ArrayList<String> COLLECTION_NODE_FIELD_NAME_LIST = new ArrayList<>(Arrays.asList(
            "key",
            "value",
            "val"
    ));
    private static HashSet<String> selectedNodeFieldNameCache = new HashSet<>();

    /**
     * seedType is used for distinguish if current seed has special structure. 
     *  e.g hash collision
     * 
     * @param iocd
     * @param seedType
     * @return
     * @throws GeneratorException
     * @throws FatalFuzzingException
     * @throws ReflectionException
     */
    /**
     * A chain through a lambda that the JDK creates as non-serializable (e.g. BinaryOperator.minBy/maxBy)
     * cannot be in a payload at all. soot models a lambda as a class (CommonUtils.isSootLambdaClass); at
     * run time the lambda object is serializable only if its declaring class has $deserializeLambda$ (the
     * method javac adds for serializable lambdas).
     */
    private static void checkLambdasSerializable(IOCD iocd) throws GeneratorException {
        for (Pair<Integer, String> g : iocd.getGadgetCallStack()) {
            String sig = g.getValue().replace("'", "");
            if (!CommonUtils.isSootLambdaClass(sig)) {
                continue;
            }
            String clz = CommonUtils.getDeclaredClzBySig(sig);
            String owner = clz.substring(0, clz.lastIndexOf("$lambda_"));
            boolean isSerializable;
            try {
                Class.forName(owner, false, SeedGenerator.class.getClassLoader())
                        .getDeclaredMethod("$deserializeLambda$", java.lang.invoke.SerializedLambda.class);
                isSerializable = true;
            } catch (NoSuchMethodException e) {
                isSerializable = false;
            } catch (Throwable e) {
                continue;   // cannot tell: let the fuzzer try
            }
            if (!isSerializable) {
                GeneratorException ex = new GeneratorException("In `checkLambdasSerializable`: the chain needs a lambda of "
                        + owner + " (" + clz + "), which " + owner + " creates as non-serializable");
                ex.printStackTrace();
                throw ex;
            }
        }
    }

    public static SeedWrapper generateSeed(IOCD iocd, short seedType)
            throws GeneratorException, FatalFuzzingException, ReflectionException {
        checkLambdasSerializable(iocd);

        SeedWrapper newSeed;
        LinkedHashMap<String, Object> resBackBoneObjectMap = new LinkedHashMap<>();
        HashMap<String, Object> resFldInFldObjectMap = new HashMap<>();
        HashSet<String> resImmutableFldSet = new HashSet<>();
        
        Object backBoneObj = generateBackboneObject(
                                iocd.getHashCode(), 
                                iocd.getGadgetClasses(),
                                seedType, 
                                iocd.getProtocol(), 
                                resBackBoneObjectMap, 
                                resFldInFldObjectMap, 
                                resImmutableFldSet);
        newSeed = new SeedWrapper(
                        backBoneObj, 
                        iocd.getHashCode(), 
                        resBackBoneObjectMap, 
                        resFldInFldObjectMap, 
                        resImmutableFldSet, 
                        seedType);
        
        HashMap<String, Object> conClzObjectMap = new HashMap<>();
        generateConClassObjects(iocd, conClzObjectMap);
        newSeed.setConClzObjectMap(conClzObjectMap);
        
        SinkPayloadsWrapper newSinkPayloadsWrapper = new SinkPayloadsWrapper(iocd);
        ValidatorGlobalDataContainer.iocdSinkPayloadsList.add(new Pair<>(iocd.getHashCode(), newSinkPayloadsWrapper)); // 缓存
        ValidatorGlobalDataContainer.iocdSinkPayloadsIndexMap.put(iocd.getHashCode(), ValidatorGlobalDataContainer.iocdSinkPayloadsIndexMap.size());
        boolean isSinkSettingSuccess = false;
        while(!isSinkSettingSuccess) {
            try {
                newSinkPayloadsWrapper.doSinkPayloadsSetting(newSeed);
                isSinkSettingSuccess = true;
            } catch (SinkPayloadsSettingsException e) {
                if (isSinkPayloadSettingDebug) {
                    StringBuilder errorMsg = new StringBuilder();
                    errorMsg.append("Sink Setting for seed ").append(iocd.hashCode).append(" failed with exception:\n");
                    LogUtils.errorWithException(errorMsg.toString(), e);
                } else {
                    LogUtils.warn("Sink Setting for seed " + iocd.hashCode + " failed, try again with another SinkRecord");
                }
                newSinkPayloadsWrapper.flushSinkPayloads();
            }
        }
        
        ConditionObjsWrapper newConditionObjsWrapper = new ConditionObjsWrapper(iocd);
        ValidatorGlobalDataContainer.iocdConditionList.add(new Pair<>(iocd.getHashCode(), newConditionObjsWrapper)); // 缓存
        ValidatorGlobalDataContainer.iocdConditionIndexMap.put(iocd.getHashCode(), ValidatorGlobalDataContainer.iocdConditionList.size());

        try {
            newConditionObjsWrapper.doBscConditionObjsSetting(newSeed);
        } catch (ConditionObjSettingException e) {
            LogUtils.warn("Basic Condition Setting for seed " + iocd.hashCode + " failed. Details are below:");
            LogUtils.warn(CommonUtils.constructExceptionStackTrace(e));
        }

        
        // generate and set field dependency objects
        FieldDependencyWrapper newFieldDependencyWrapper = new FieldDependencyWrapper(iocd);
        ValidatorGlobalDataContainer.iocdFldDepMap.put(iocd.getHashCode(), newFieldDependencyWrapper);
        // set dependency field
        newFieldDependencyWrapper.doDependecyFieldSetting(newSeed);
        
        // after all critical fields ars set, we generate the mutation sequense.
        newSeed.initInjectionObjectInputContainerOutter();

        return newSeed;
    }


    public static void generateConClassObjects(IOCD iocd, HashMap<String, Object> conClzObjectMap) throws ReflectionException {
        for (ClassRecord conClzRcd : iocd.getConClassNodes()) {
            String conClzName = conClzRcd.getClassName();
            Object conClzObj;
            try {
                // condition nodes can be abstract (e.g. MessageUpdatableContext), which cannot be
                // instantiated directly; generateObjectByName picks a concrete subclass for them.
                conClzObj = generateObjectByName(conClzName, iocd.getProtocol());
            } catch (GeneratorException | Error e) {
                // a condition object only helps to satisfy branch conditions; without it the seed is
                // still usable, so skip it instead of rejecting the whole IOCD
                LogUtils.warn("Skip condition object " + conClzName + " of " + iocd.getHashCode() + ": " + e);
                continue;
            }
            conClzObjectMap.put(conClzName, conClzObj);
        }
    }

    static Object type2CollisionObj1 = null;
    static Object type2CollisionObj2 = null;

    public static void generateAllBackboneRelatedObjects(int iocdHash,
                                                         List<ClassRecord> backboneClassRecordList,
                                                         @NotNull HashMap<String, Object> backBoneObjMap,
                                                         @NotNull HashMap<String, Object> fldInFldObjMap,
                                                         @NotNull HashSet<String> immutableFieldSet,
                                                         String protocol) throws GeneratorException, ReflectionException {
        int startIndex = 0, collisionType = -1;
        // if this is a collision-related seed. 
        // for convenient, we would use a template method to do generate it.
        // however, it is trival to generate these objects without template following the IOCD.
        // we use template just becuase it is a well-known pattern, 
        // and getting a valid and serialiazable collision-based injection object through mutation is unneccessary.
        if (GlobalDataContainer.getIOCDByHash(iocdHash).isHashCollisionFlag()) {
            collisionType = GlobalDataContainer.getIOCDByHash(iocdHash).getHashCollisionType();
            // we first need find out the collision class.
            if (collisionType == 1) {
                // for type1, we first three classes (tagged with top, firtst, second) in `gadgetClasses` are the collision-related.
                ClassRecord topClassRcd = backboneClassRecordList.get(0);
                ClassRecord firstClassRcd = backboneClassRecordList.get(1);
                ClassRecord secondClassRcd = backboneClassRecordList.get(2);
                // for this type of collision, we need to generate object for first and second class,
                // and then put them into the `table` field of top class object.
                Object firstObj, secondObj, topObj;
                firstObj = generateObjectByName(firstClassRcd.getClassName(), protocol);
                secondObj = generateObjectByName(secondClassRcd.getClassName(), protocol);
                type1CollisionObjList.add(firstObj);
                type1CollisionObjList.add(secondObj);
                try {
                    topObj = SeedUtils.generateCollisionObject1(topClassRcd, firstObj, secondObj, immutableFieldSet);
                } catch (Exception e) {
                    throw new GeneratorException("generate type1 collision object failed", e);
                }
                // put all three objects to the correct position in the backBoneObjMap.
                FuzzUtils.cacheObject(backBoneObjMap, topClassRcd.getId(), null, topClassRcd.getClassName(), topObj);
                FuzzUtils.cacheObject(backBoneObjMap, firstClassRcd.getId(), null, firstClassRcd.getClassName(), firstObj);
                FuzzUtils.cacheObject(backBoneObjMap, secondClassRcd.getId(), null, secondClassRcd.getClassName(), secondObj);
                // the field-in-field objects record in the ClassRecord would be handle too.
                generateAllFieldInFieldObjects(topClassRcd, topObj, null, fldInFldObjMap, protocol);
                generateAllFieldInFieldObjects(firstClassRcd, firstObj, null, fldInFldObjMap, protocol);
                generateAllFieldInFieldObjects(secondClassRcd, secondObj, null, fldInFldObjMap, protocol);
                // then we generate objects of the rest of the classes
                startIndex = TYPE1_COLLISION_START_INDEX;
            } else if (FuzzUtils.isType2Or3Collision(collisionType)) {
                // for type2, we also use a template to generate it. 
                // we regard the first two classes as the collision-related classes.
                ClassRecord topClassRcd = backboneClassRecordList.get(0);
                ClassRecord type2CollisionClassRcd = backboneClassRecordList.get(1);
                // we use 'C1' and 'C2' to distinguish the collision object with same type. 
                Object topObj, type2CollisionObj1, type2CollisionObj2, swapCachedObj;
                String collisionTag1 = "C1";
                String collisionTag2 = "C2";
                type2CollisionObj1 = generateObjectByName(type2CollisionClassRcd.getClassName(), protocol);
                type2CollisionObj2 = generateObjectByName(type2CollisionClassRcd.getClassName(), protocol);
                FuzzUtils.refineSpecialCollectionObjectBeforeSerialize(type2CollisionObj1);
                FuzzUtils.refineSpecialCollectionObjectBeforeSerialize(type2CollisionObj2);
                // hashCode()/equals() of collision classes typically dereference their String fields
                // (e.g. SerialArray.baseTypeName); left null they throw before the collision method runs
                fillNullStringFields(type2CollisionObj1);
                fillNullStringFields(type2CollisionObj2);
//                swapCachedObj = type2CollisionObj1;
//                type2CollisionObj1 = type2CollisionObj2;
//                type2CollisionObj2 = swapCachedObj;
                try {
                    topObj = SeedUtils.generateCollisionObject2(topClassRcd, type2CollisionObj1, type2CollisionObj2, immutableFieldSet);
                } catch (Exception e) {
                    throw new GeneratorException("generate type2 collision object failed", e);
                }
                // cache them
                FuzzUtils.cacheObject(backBoneObjMap, topClassRcd.getId(), null, topClassRcd.getClassName(), topObj);
                FuzzUtils.cacheObject(backBoneObjMap, type2CollisionClassRcd.getId(), collisionTag1, type2CollisionClassRcd.getClassName(), type2CollisionObj1);
                FuzzUtils.cacheObject(backBoneObjMap, type2CollisionClassRcd.getId(), collisionTag2, type2CollisionClassRcd.getClassName(), type2CollisionObj2);
                // generate field-in-field objects
                generateAllFieldInFieldObjects(topClassRcd, topObj, null, fldInFldObjMap, protocol);
                generateAllFieldInFieldObjects(type2CollisionClassRcd, type2CollisionObj1, "C1", fldInFldObjMap, protocol);
                generateAllFieldInFieldObjects(type2CollisionClassRcd, type2CollisionObj2, "C2", fldInFldObjMap, protocol);
                // reset the startIndex
                startIndex = TYPE2_COLLISION_START_INDEX;
            } else {
                // remove related codes below, check history on github for details.
                throw new GeneratorException("collision type " + collisionType + " is not supported yet.");
            }
        } 
        for (int crtIndex = startIndex; crtIndex < backboneClassRecordList.size(); crtIndex++) {
            ClassRecord clzRcd = backboneClassRecordList.get(crtIndex);
            Object backBoneObj;
            String clzName = clzRcd.getClassName();
            // generate a backBone Object
            backBoneObj = generateObjectByName(clzName, protocol);
            RhinoWorkaround.prepare(backBoneObj);
            // cache the backBone Object to the backBoneObjMap.
            FuzzUtils.cacheObject(backBoneObjMap, clzRcd.getId(), null, clzName, backBoneObj);

            // if this father gadget class of current class has field-in-field objects, we genenrate and cache them as well.
            generateAllFieldInFieldObjects(clzRcd, backBoneObj, null, fldInFldObjMap, protocol);
        }
    }

    private static void generateAndAssembleSpecialDynamicProxyBackboneObject(String dynamicProxyClzName,
                                                                             @NotNull HashMap<String, Object> backBoneObjMap,
                                                                             @NotNull HashMap<String, Object> fldInFldObjMap,
                                                                             @NotNull HashSet<String> immutableFieldSet) throws ReflectionException {
        Class<?> dynamicProxyClz = ClassUtils.getClassByName(dynamicProxyClzName);
        if (dynamicProxyClz == null) {
            LogUtils.error("can not find the class " + dynamicProxyClzName);
            System.exit(0);
        }
        // G. Generate the Injection Object
        Object specialDynamicObj = ReflectionUtils.createWithNoArgsConstructor(dynamicProxyClz); // the sink object information (i.e., Sink Object) is set here (In field `interceptorHandlerInstances`'s value).
        FuzzUtils.cacheObject(backBoneObjMap, 0, null, dynamicProxyClzName, specialDynamicObj);


        // Generate the rest of backbone objects
        List<String> otherBackboneClzNameList = FuzzUtils.getSpecialDynamicProxyRelatedClassList(dynamicProxyClzName);
        String clzMetaDataName = otherBackboneClzNameList.get(0);
        String clzMetaDataItcptRefName = otherBackboneClzNameList.get(1);
        String mtdMetaDataName = otherBackboneClzNameList.get(2);
        String itcptMetadataName = otherBackboneClzNameList.get(3);
        String itcptModelName = otherBackboneClzNameList.get(4);
        String itcptTypeName = otherBackboneClzNameList.get(5);
        String ivCtxFactoryName = otherBackboneClzNameList.get(6);

        Class<?> clzToProxy = ClassUtils.getClassByName(ClassUtils.DEFAULT_MAP);

        // A. ClassMetaData
        Object clzMetaData = ReflectionUtils.createWithNoArgsConstructor(clzMetaDataName);
        FuzzUtils.cacheObject(backBoneObjMap, 0, null, clzMetaDataName, clzMetaData);
        ReflectionUtils.setFieldValue(clzMetaData, "clazz", clzToProxy);
        immutableFieldSet.add(FuzzUtils.getDefaultClassNameKey(clzMetaDataName) + "#" + "clazz");

        // B. ClassMetaDataInterceptorReference
        Object clzMetaDataItcptRef = ReflectionUtils.createWithNoArgsConstructor(clzMetaDataItcptRefName);
        FuzzUtils.cacheObject(backBoneObjMap, 0, null, clzMetaDataItcptRefName, clzMetaDataItcptRef);
        ReflectionUtils.setFieldValue(clzMetaDataItcptRef, "classMetadata", clzMetaData);
        immutableFieldSet.add(FuzzUtils.getDefaultClassNameKey(clzMetaDataItcptRefName) + "#" + "classMetadata");

        // C. MethodMetaData
        Object mtdMetaData = ReflectionUtils.createWithNoArgsConstructor(mtdMetaDataName);
        FuzzUtils.cacheObject(backBoneObjMap, 0, null, mtdMetaDataName, mtdMetaData);

        // D. InterceptorMetadata
        Object itcptMetadata = ReflectionUtils.createWithNoArgsConstructor(itcptMetadataName);
        FuzzUtils.cacheObject(backBoneObjMap, 0, null, itcptMetadataName, itcptMetadata);
        ReflectionUtils.setFieldValue(itcptMetadata, "interceptorReference", clzMetaDataItcptRef);
        ReflectionUtils.setFieldValue(itcptMetadata, "targetClass", true);
        Map<Object, Object> mtdListMap = new HashMap<>();
        List<Object> mtdList = new ArrayList<>();
        mtdList.add(mtdMetaData);
        mtdListMap.put(ReflectionUtils.getEnumValue(ClassUtils.getClassByName(itcptTypeName), "POST_ACTIVATE"), mtdList);
        ReflectionUtils.setFieldValue(itcptMetadata, "interceptorMethodMap", mtdListMap);
        immutableFieldSet.add(FuzzUtils.getDefaultClassNameKey(itcptMetadataName) + "#" + "interceptorReference");
        immutableFieldSet.add(FuzzUtils.getDefaultClassNameKey(itcptMetadataName) + "#" + "targetClass");
        immutableFieldSet.add(FuzzUtils.getDefaultClassNameKey(itcptMetadataName) + "#" + "interceptorMethodMap");

        // E. InterceptionModel
        Object itcptModel = ReflectionUtils.createWithNoArgsConstructor(itcptModelName);
        FuzzUtils.cacheObject(backBoneObjMap, 0, null, itcptModelName, itcptModel);

        // F. InvocationContextFactory
        Object ivCtxFactory = ReflectionUtils.createWithNoArgsConstructor(ivCtxFactoryName);
        FuzzUtils.cacheObject(backBoneObjMap, 0, null, ivCtxFactoryName, ivCtxFactory);

        ReflectionUtils.setFieldValue(specialDynamicObj, "targetInstance", new HashMap<>());
        ReflectionUtils.setFieldValue(specialDynamicObj, "invocationContextFactory", ivCtxFactory);
        ReflectionUtils.setFieldValue(specialDynamicObj, "interceptionModel", itcptModel);
        ReflectionUtils.setFieldValue(specialDynamicObj, "interceptorHandlerInstances", new HashMap<>());
        immutableFieldSet.add(FuzzUtils.getDefaultClassNameKey(dynamicProxyClzName) + "#" + "targetInstance");
        immutableFieldSet.add(FuzzUtils.getDefaultClassNameKey(dynamicProxyClzName) + "#" + "invocationContextFactory");
        immutableFieldSet.add(FuzzUtils.getDefaultClassNameKey(dynamicProxyClzName) + "#" + "interceptionModel");
        immutableFieldSet.add(FuzzUtils.getDefaultClassNameKey(dynamicProxyClzName) + "#" + "interceptorHandlerInstances");

    }

    private static Object generateExpressionInjectionObject(String clzName, String protocol) throws GeneratorException {
        String expr = "";
        
        List<String> specialExpressionInjectionClassList = FuzzUtils.getSpecialExpressionInjectionClassList(clzName);
        

        String facesContextImplName = specialExpressionInjectionClassList.get(0);
        String compositeELResolverName = specialExpressionInjectionClassList.get(1);
        String facesELContextName = specialExpressionInjectionClassList.get(2);

        Object res = null;
        try {
            // Create objects using reflection
            Class<?> facesContextImplClass = ClassUtils.getClassByName(facesContextImplName);
            Object fc = ReflectionUtils.createWithConstructor(facesContextImplClass,
                    new Class<?>[] {ClassUtils.getClassByName(ClassUtils.SERVLET_CONTEXT),
                            ClassUtils.getClassByName(ClassUtils.SERVLET_REQUEST),
                            ClassUtils.getClassByName(ClassUtils.SERVLET_RESPONSE)},
                    new Object[] {null, null, null});

            // Create ELContext object
            Class<?> compositeELResolverClass = ClassUtils.getClassByName(compositeELResolverName);
            Object compositeELResolver = ReflectionUtils.createWithNoArgsConstructor(compositeELResolverClass);

            Class<?> facesELContextClass = ClassUtils.getClassByName(facesELContextName);
            Object elContext = ReflectionUtils.createWithConstructor(facesELContextClass,
                    new Class<?>[] {ClassUtils.getClassByName(ClassUtils.EL_SOLVER), ClassUtils.getClassByName(ClassUtils.EL_FACES_CONTEXT)},
                    new Object[] {compositeELResolver, fc});

            // Set _elContext field
            ReflectionUtils.setFieldValue(fc, "_elContext", elContext);

            // The wrapped ValueExpression: MyFaces' literal-text expression, not one made by an EL engine
            // (upstream used org.apache.el.ExpressionFactoryImpl, which the Myfaces jars do not contain, so
            // this failed with a NullPointerException). The sink payload replaces it with the marker one.
            Object ve1 = ReflectionUtils.createWithConstructor(
                    ClassUtils.wrapperClassForName("org.apache.myfaces.view.facelets.el.ELText$LiteralValueExpression"),
                    new Class<?>[] {String.class}, new Object[] {expr});

            // Create ValueExpressionMethodExpression
            Class<?> valueExpressionMethodExpressionClass = ClassUtils.getClassByName(clzName);
            res = ReflectionUtils.createWithConstructor(valueExpressionMethodExpressionClass,
                    new Class<?>[] {ClassUtils.getClassByName(ClassUtils.EL_VALUE_EXPRESSION)},
                    new Object[] {ve1});
        } catch (Exception e) {
            throw new GeneratorException("generate expression injection object failed", e);
        }

        return res;
    }

    private static Object generateAndAssembleClojureSinkClassObject(String clzName,
                                                         @NotNull HashMap<String, Object> backBoneObjMap,
                                                         @NotNull HashMap<String, Object> fldInFldObjMap,
                                                         @NotNull HashSet<String> immutableFieldSet,
                                                         int clazzId,
                                                         String hashColTag) throws GeneratorException {

        Object clojureSinkObj = null;
        try {
            clojureSinkObj = ReflectionUtils.createWithNoArgsConstructor(clzName);
            clojureSinkObj = assembleClojureSinkClassObject(clojureSinkObj, backBoneObjMap, fldInFldObjMap, immutableFieldSet, clazzId, hashColTag);
        } catch (Exception e) {
            throw new GeneratorException("generate and assemble clojure sink class object failed", e);
        }

        return clojureSinkObj;

    }

    private static Object assembleClojureSinkClassObject(Object obj,
                                                         @NotNull HashMap<String, Object> backBoneObjMap,
                                                         @NotNull HashMap<String, Object> fldInFldObjMap,
                                                         @NotNull HashSet<String> immutableFieldSet,
                                                         int clazzId,
                                                         String hashColTag) throws GeneratorException {

        Map<String, Object> expModelMap = new HashMap<>();
        String expModelMapKey = "hashCode";
        String payload = "DUMMY";

        Class<?> objClass = obj.getClass();
        String clzName = objClass.getName();

        List<String> clojureSinkClassList = FuzzUtils.getSpecialClojureSinkClassList(clzName);

        try {
            FuzzUtils.cacheObject(backBoneObjMap, clazzId, hashColTag, clzName, obj);

            String coreCompClzName = clojureSinkClassList.get(0);
            Object coreCompObj = ReflectionUtils.createWithNoArgsConstructor(coreCompClzName);

            String mainEvalOptClzName = clojureSinkClassList.get(1);
            Object mainEvalOptObj = ReflectionUtils.createWithNoArgsConstructor(mainEvalOptClzName);

            String coreConstantlyClzName = clojureSinkClassList.get(2);
            Object coreConstantlyObj = ReflectionUtils.createWithNoArgsConstructor(coreConstantlyClzName);

            Object expConstantlyObj = ReflectionUtils.invokeMethodByName(coreConstantlyObj, "invoke", payload);
            Object expCompObj = ReflectionUtils.invokeMethodByName(coreCompObj, "invoke", mainEvalOptObj, expConstantlyObj);
            String persistenceArrMapClzName = clojureSinkClassList.get(3);
            expModelMap.put(expModelMapKey, expCompObj);
            Object presistenceArrMapObj = ReflectionUtils.invokeMethodByName(ClassUtils.wrapperClassForName(persistenceArrMapClzName), "create", expModelMap);
            ReflectionUtils.invokeMethodByName(obj, "__initClojureFnMappings", presistenceArrMapObj);
            immutableFieldSet.add(FuzzUtils.getDefaultClassNameKey(clzName) + "#" + "__clojureFnMap");

            return obj;
        } catch (Exception e) {
            throw new GeneratorException("fail to create clojure sink class obj", e);
        }

    }

    private static boolean isWorkAroundClassFineGrained(String clzName) {
        return false;
    }

    private static void generateAllFieldInFieldObjects(ClassRecord clzRcd, Object relatedObj, String colTag, @NotNull HashMap<String, Object> fldInFldObjMap, String protocol) throws GeneratorException, ReflectionException {
        List<FieldRecord> fldInFldList;
        if ((fldInFldList = clzRcd.getFields()) != null && fldInFldList.size() != 0) {
            for (FieldRecord fr : fldInFldList) {
                String fldSig = fr.getSig();
                // if the field is not null, we just cache it.
                Object fieldObj = ReflectionUtils.getFieldValue(relatedObj, CommonUtils.getFieldNameBySig(fldSig));
                if (fieldObj != null) {
                    // cant get the Field Object.
                    if (fieldObj instanceof NoneObject) {
                        LogUtils.logWarnOnce("can not find field object for " + clzRcd.getClassName() + "#" + fldSig + ", maybe the inaccurancy of the static analysis.");
                        continue;
                    } 
                    // if the field is an ARRAY, we only cache its first element.
                    if (fieldObj.getClass().isArray()) {
                        // if the array is empty, we just skip it.
                        Object arrItem = null;
                        try {
                            arrItem = Array.get(fieldObj, 0);
                        } catch (Exception e) {
                            continue;
                        }
                        String arrItemClzName = fieldObj.getClass().getComponentType().getName();
                        FuzzUtils.cacheObject(fldInFldObjMap, clzRcd.getId(), colTag, arrItemClzName, arrItem);
                    } else {
                        FuzzUtils.cacheObject(fldInFldObjMap, clzRcd.getId(), colTag, CommonUtils.getFieldTypeBySig(fldSig), fieldObj);
                    }
                    continue;
                } else {
                    String fldClzName = CommonUtils.getFieldTypeBySig(fldSig);
                    Object fldInFldObj = generateObjectByName(fldClzName, protocol);
                    FuzzUtils.cacheObject(fldInFldObjMap, clzRcd.getId(), colTag, fldClzName, fldInFldObj);
                }
            }
        }
    }

    /**
     * BackboneClass is the non-utils Class on the gadget chain
     *
     * @param backboneClassRecordList 
     * @param seedType                
     * @return 
     */
    public static Object generateBackboneObject(int iocdHash,
                                                List<ClassRecord> backboneClassRecordList,
                                                short seedType, String protocol,
                                                @Nullable HashMap<String, Object> resBackBoneObjMap,
                                                @Nullable HashMap<String, Object> resFldInFldObjMap,
                                                @NotNull HashSet<String> immutableFieldSet) throws GeneratorException {

        Object backboneObj = null;
        HashMap<String, Object> backBoneObjMap = null;
        HashMap<String, Object> fldInFldObjMap = null;
        // Initialize a HashMap to store all backbone objects
        if (resBackBoneObjMap != null) {
            backBoneObjMap = resBackBoneObjMap;
        } else {
            backBoneObjMap = new HashMap<>();
        }
        // Initialize a HashMap to store all field-in-field objects of backbone objects
        if (resFldInFldObjMap != null) {
            fldInFldObjMap = resFldInFldObjMap;
        } else {
            fldInFldObjMap = new HashMap<>();
        }
        String firstClzName = backboneClassRecordList.get(0).getClassName();
        try {
            if (FuzzUtils.isSpecialDynamicProxyClass(firstClzName)) {
                // STEP0: Handle some special dynamic proxy classes.
                // Some classes can not be constructed based on the call stack.
                //  We need to handle them specially.
                generateAndAssembleSpecialDynamicProxyBackboneObject(firstClzName, backBoneObjMap, fldInFldObjMap, immutableFieldSet);
            } else {
                // STEP1: Generate all backbon objects and field-in-field obejcts, all of the objects will be stored in backBoneObjMap and fldInFldObjMap.
                generateAllBackboneRelatedObjects(iocdHash, backboneClassRecordList, backBoneObjMap, fldInFldObjMap, immutableFieldSet, protocol);
                primeFastjsonProbe(iocdHash, backBoneObjMap);
                // STEP2: assemble the backbone object in a correct structrue.
                if (seedType == NORMAL_SEED) {
                    // LogUtils.info("generate normal seed");
                    assembleBackboneObject0(backboneClassRecordList, backBoneObjMap, fldInFldObjMap, immutableFieldSet, protocol);
                } else if (seedType == COLLISION_SEED) {
                    // LogUtils.info("generate collision seed");
                    int collisionType = GlobalDataContainer.getIOCDByHash(iocdHash).getHashCollisionType();
                    // for type-1 collision seed
                    if (collisionType == 1) {
                        assembleBackboneObject1(backboneClassRecordList, backBoneObjMap, fldInFldObjMap, immutableFieldSet, protocol);
                    } else if (FuzzUtils.isType2Or3Collision(collisionType)) {
                        assembleBackboneObject2(backboneClassRecordList, backBoneObjMap, fldInFldObjMap, immutableFieldSet, protocol, iocdHash);
                    } else {
                        throw new GeneratorException("Unsupported collision type: " + collisionType);
                    }
                } else {
                    throw new GeneratorException("In `generateBackboneObject`: Invalid seedType " + seedType + " in seed " + iocdHash);
                }
            }
            primeMapEqualsOperands(iocdHash, backBoneObjMap);
            VaadinItemSorterWorkaround.primeSortedMapOperands(iocdHash, backBoneObjMap);
            backboneObj = backBoneObjMap.get(firstClzName);
        } catch (Exception e) {
            e.printStackTrace();
            throw new GeneratorException("Generate backbone object failed(" + iocdHash + ")", e);
        }
        return backboneObj;

    }

    /**
     * fastjson2 (CUSTOM, sink ObjectWriter.write): JSONObject.toString / JSONPObject.toString write their values with
     * ObjectWriter.write, which calls a bean's getters. The IOCDs name no sink field, so the probe bean (whose getter fires
     * it, a real but inert effect) goes into the chain's JSON containers (JSONObject, JSONArray, JSONPObject) as a value. Done before assembly: a
     * container's hashCode can take part in a hash collision that assembly computes.
     */
    private static void primeFastjsonProbe(int iocdHash, HashMap<String, Object> backBoneObjMap) throws ReflectionException {
        IOCD iocd = GlobalDataContainer.getIOCDByHash(iocdHash);
        boolean isFastjson = false;
        if (iocd != null && "CUSTOM".equals(iocd.getSinkType())) {
            for (datastructrue.iocd.unit.SinkRecord r : iocd.getSinkRecords()) {
                if (r.jimpleUnitInfo != null && r.jimpleUnitInfo.contains("<com.alibaba.fastjson2.writer.ObjectWriter: ")) {
                    isFastjson = true;
                }
            }
        }
        if (!isFastjson) {
            return;
        }
        // the IOCD's route can write an array's elements as JSON objects (JSONWriter.write(List) ->
        // JSONWriter.write(JSONObject) -> ObjectWriter.write): the array then holds a JSONObject holding the bean
        boolean isArrayOfObjects = false;
        for (Pair<Integer, String> mtd : iocd.getGadgetCallStack()) {
            if (mtd.getValue().contains("fastjson2.JSONWriter") && mtd.getValue().contains("write(com.alibaba.fastjson2.JSONObject)")) {
                isArrayOfObjects = true;
            }
        }
        for (Object obj : backBoneObjMap.values()) {
            String name = obj == null ? "" : obj.getClass().getName();
            if (name.equals("com.alibaba.fastjson2.JSONObject")) {
                ((Map<String, Object>) obj).put("jdd", new cn.edu.fudan.datastructure.OracleGetterBean());
            } else if (name.equals("com.alibaba.fastjson2.JSONArray")) {
                Object element = new cn.edu.fudan.datastructure.OracleGetterBean();
                if (isArrayOfObjects) {
                    Map<String, Object> jsonObject = (Map<String, Object>) ReflectionUtils.createWithNoArgsConstructor("com.alibaba.fastjson2.JSONObject");
                    jsonObject.put("jdd", element);
                    element = jsonObject;
                }
                ((List<Object>) obj).add(element);
            } else if (name.equals("com.alibaba.fastjson2.JSONPObject")) {
                ReflectionUtils.invokeMethodByName(obj, "addParameter", new cn.edu.fudan.datastructure.OracleGetterBean());
            }
        }
    }

    /**
     * prepare a valid wrapper object to be set to last backbone object
     *
     * @param mayObj
     * @param fldType
     * @param protocol
     * @param backBoneObjMap
     * @param fldInFldObjMap
     * @return
     * @throws GeneratorException
     */
    private static Object getFldObjBeSetted(Object mayObj, 
                                            String crtRealClzNameKey,
                                            String fldType, 
                                            String protocol, 
                                            int lastClzId,
                                            String lastColTag,
                                            HashMap<String, Object> backBoneObjMap,
                                            HashMap<String, Object> fldInFldObjMap, 
                                            HashSet<String> immutableFldSet) throws GeneratorException {


        if (FuzzUtils.isSpecialFldInFld(fldType)) {
            // some special field need be modeled. 
            mayObj = createObjectWithReflection(fldType, protocol);
            Object elementObj = backBoneObjMap.get(crtRealClzNameKey);
//            if (lastColTag != null && !lastColTag.isEmpty() && FuzzUtils.isWorkAroundClass(crtRealClzNameKey)) {
//                elementObj = assembleWorkAroundClassObject(backBoneObjMap.get(crtRealClzNameKey), backBoneObjMap, fldInFldObjMap, immutableFldSet, 0, null);
//            } else {
//                elementObj = backBoneObjMap.get(crtRealClzNameKey);
//            }
            fillSpecialCollectionFieldRandomly(mayObj, elementObj);
            
            // record field-in-field object to the fldInFldObjMap.
            fldInFldObjMap.put(
                FuzzUtils.getRealClassNameKey(lastClzId, lastColTag, fldType),
                 mayObj); // NOTICE: we need to distinguish the same type of object in the fldInFldObjMap, so the `FuzzUtils.getRealClassNameKey` is neccessary here.
            
            // if the field-in-field is an array, we get the first item. 
            if (mayObj.getClass().isArray()) {
                Object arrItem = Array.get(mayObj, 0);
                fldInFldObjMap.put(
                    FuzzUtils.getRealClassNameKey(lastClzId, lastColTag, arrItem.getClass().getName()), 
                    arrItem);
            }
        } else {
            // if the field is not a special field and the mayObj is null, we just get it from the backBoneObjMap.
            mayObj = mayObj == null ? backBoneObjMap.get(crtRealClzNameKey) : mayObj;
        }
        if (mayObj == null) {
            throwBackboneObjAssembleException(fldType, backBoneObjMap);
        }
        return mayObj;
    }

    /**
     * check if a ClassRecord is the first one at the Gadget ClassRecord List. 
     *
     * @param clzRcd
     * @return
     */
    private static boolean isFirstGadgetClass(ClassRecord clzRcd) {
        return (clzRcd.getSourceRecord() == null) || (clzRcd.getPreAccessPath() == null
                || clzRcd.getPreAccessPath().isEmpty())
                && clzRcd.getPredecessorRecord() == null;
    }

    /**
     * assemble the field-in-field object of a backbone object
     * 
     * @param clzRcd current backbone object's ClassRecord to help get the accesspath of the field-in-field object of last backbone object.
     * @param lastClzRcd
     * @param colTag
     * @param backBoneObjMap
     * @param fldInFldObjMap
     * @param immutableFldSet
     * @return
     * @throws ReflectionException
     * @throws GeneratorException 
     */
    private static Object assembleFldInFldObj(ClassRecord clzRcd, 
                                                ClassRecord lastClzRcd, 
                                                String colTag, 
                                                HashMap<String, Object> backBoneObjMap,
                                                HashMap<String, Object> fldInFldObjMap,
                                                HashSet<String> immutableFldSet) throws ReflectionException, GeneratorException {
        LinkedList<String> fldInFldList;
        Object lastObj = null;
        // Notice: AccessPath is the field-sensitive datastructure comes from flowdroid. 
        if ((fldInFldList = clzRcd.getPreAccessPath()) != null && clzRcd.getPreAccessPath().size() != 1) {
            boolean isFist = true;
            for (String fldSig : fldInFldList) {
                Object tmpObj;
                String fldType = CommonUtils.getFieldTypeBySig(fldSig);
                String fldName = CommonUtils.getFieldNameBySig(fldSig);
                if (isFist) {
                    isFist = false;
                    lastObj = FuzzUtils.getCachedObject(lastClzRcd.getId(), colTag, fldType, backBoneObjMap); // the field-in-field is from lastClzRcd
                }
                tmpObj = FuzzUtils.getCachedObject(lastClzRcd.getId(), colTag, fldType, fldInFldObjMap); // the field-in-field is from lastClzRcd
                if (lastObj == null) {
                    lastObj = tmpObj;
                } else {
                    updateLastObject0(lastObj, fldName, tmpObj);
                    immutableFldSet.add(FuzzUtils.getRealClassNameKey(lastClzRcd.getId(), colTag, fldType) + "#" + fldName); 
                }
            }
        }

        return lastObj;
    }

    /**
     * normal seed assemble.
     *
     * @param backboneClassRecordList
     * @param backBoneObjMap
     * @param fldInFldObjMap
     * @return
     * @throws GeneratorException
     * @throws ReflectionException
     */
    public static void assembleBackboneObject0(List<ClassRecord> backboneClassRecordList,
                                               HashMap<String, Object> backBoneObjMap,
                                               HashMap<String, Object> fldInFldObjMap,
                                               HashSet<String> immutableFldSet,
                                               String protocol)
            throws GeneratorException, ReflectionException {
        Object lastBackboneObj = null;
        ClassRecord lastClassRecord = null;
        for (int index = 0; index < backboneClassRecordList.size(); index++) {
            
            ClassRecord clzRcd = backboneClassRecordList.get(index);
            String crtClzName = clzRcd.getClassName();
            String crtRealClzNameKey = FuzzUtils.getRealClassNameKey(clzRcd.getId(), null, crtClzName);
            if (isFirstGadgetClass(clzRcd)) {
                lastBackboneObj = FuzzUtils.getCachedObject(clzRcd.getId(), null, crtClzName, backBoneObjMap);
                lastClassRecord = clzRcd;
                continue;
            }
            if (VaadinItemSorterWorkaround.assemble(backboneClassRecordList, index, backBoneObjMap, immutableFldSet)) {
                lastClassRecord = clzRcd;
                lastBackboneObj = FuzzUtils.getCachedObject(clzRcd.getId(), null, crtClzName, backBoneObjMap);
                continue;
            }
            if (RhinoWorkaround.assemble(backboneClassRecordList, index, backBoneObjMap, immutableFldSet)) {
                lastClassRecord = clzRcd;
                lastBackboneObj = FuzzUtils.getCachedObject(clzRcd.getId(), null, crtClzName, backBoneObjMap);
                continue;
            }

            FuzzUtils.handleSpecialClass(backBoneObjMap, immutableFldSet, crtClzName);

            Object fldObj = null;
            Pair<String, String> predecessorPair = clzRcd.getPredecessorRecord();
            String fldSig = predecessorPair.getValue();
            String fldType = CommonUtils.getFieldTypeBySig(fldSig);
            String lastClzName = predecessorPair.getKey();
            // check if the class name is match, if not, we need to update the lastClassRecord and lastBackboneObj.
            if (!lastClzName.equals(lastClassRecord.getClassName())) {
                // try to find the last matched class record.
                boolean isFound = false;
                for (int jndex = index - 1; jndex >= 0; jndex--) {
                    ClassRecord tmpClzRcd = backboneClassRecordList.get(jndex);
                    if (tmpClzRcd.getClassName().equals(lastClzName)) {
                        lastClassRecord = tmpClzRcd;
                        isFound = true;
                        break;
                    }
                }
                boolean inherited = false;
                if (!isFound) {
                    // no class record of that name before this one: the field may be declared by a superclass of
                    // the last backbone class (UnicastRef.ref of a UnicastRef2); then that object holds the field
                    try {
                        ClassLoader loader = ClassLoader.getSystemClassLoader();
                        inherited = Class.forName(lastClzName, false, loader)
                                .isAssignableFrom(Class.forName(lastClassRecord.getClassName(), false, loader));
                    } catch (Throwable ignored) {
                        // not loadable: no such relation
                    }
                    if (!inherited) {
                        throw new GeneratorException("In `SeedGenerator.assembleBackboneObject0`: can not find the matched class record for " + lastClzName);
                    }
                }
                if (!inherited) {
                    // update the last backbone object
                    lastBackboneObj = FuzzUtils.getCachedObject(lastClassRecord.getId(), null, lastClzName, backBoneObjMap);
                }
            }

            if (FuzzUtils.isSpecialClojureSinkClass(crtClzName)) {
                fldObj = generateAndAssembleClojureSinkClassObject(crtClzName, backBoneObjMap, fldInFldObjMap, immutableFldSet, clzRcd.getId(), null);
                index = backboneClassRecordList.size() - 1; // just skip the other classes.
                fldObj = getFldObjBeSetted(fldObj,
                        crtRealClzNameKey,
                        fldType,
                        protocol,
                        lastClassRecord.getId(),
                        null,
                        backBoneObjMap,
                        fldInFldObjMap,
                        immutableFldSet);
            } else {
                // handle the field-in-field object.
                fldObj = assembleFldInFldObj(clzRcd, lastClassRecord, null, backBoneObjMap, fldInFldObjMap, immutableFldSet);
                // handle the special collection field.
                fldObj = getFldObjBeSetted(fldObj,
                        crtRealClzNameKey,
                        fldType,
                        protocol,
                        lastClassRecord.getId(),
                        null,
                        backBoneObjMap,
                        fldInFldObjMap,
                        immutableFldSet);
            }

            // update the field of the last backbone object
            String fldName = CommonUtils.getFieldNameBySig(fldSig);

            String realPredecessorClzName = predecessorPair.getKey();
            if (!lastBackboneObj.getClass().getName().equals(realPredecessorClzName)) {
                LogUtils.warn("Predecessor not match, try to find correct one! lastBackboneObj: "
                        + lastBackboneObj.getClass().getName()
                        + ", realPredecessorClzName: " + realPredecessorClzName);
                Object trueLastObj = backBoneObjMap.get(realPredecessorClzName);
                if (trueLastObj != null) {
                    lastBackboneObj = trueLastObj;
                } else {
                    LogUtils.warn("Can not find the correct predecessor object for " + crtClzName + ", remind the same.");
                }
            }

            setFieldOfLastObject(lastBackboneObj, fldName, proxyIfHandler(clzRcd, lastBackboneObj, fldName, fldObj),
                    FuzzUtils.getRealClassNameKey(lastClassRecord.getId(), null, lastBackboneObj.getClass().getName()), immutableFldSet);
            lastClassRecord = clzRcd; // NOTICE: we update the lastClassRecord here.
            lastBackboneObj = FuzzUtils.getCachedObject(clzRcd.getId(), null, crtRealClzNameKey, backBoneObjMap);
        }
        // objects a chain needs that the field-by-field assembly above cannot build; no-op for other applications
        RhinoWorkaround.installAdapter(backboneClassRecordList, backBoneObjMap, immutableFldSet);
    }

    /**
     * hashCode() of a collision object, computed on a serialized copy.
     * <p>
     * For gadget objects hashCode() usually runs part of the chain (e.g. TiedMapEntry.hashCode ->
     * LazyMap.get -> factory.transform). Calling it on the seed itself both fails on half-built
     * objects and leaves side effects behind (LazyMap.get stores the key in its inner map, so the
     * deserialized LazyMap would never call its factory again).
     */
    private static int sideEffectFreeHashCode(Object obj) {
        Object target = obj;
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
                oos.writeObject(obj);
            }
            try (java.io.ObjectInputStream ois = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bos.toByteArray()))) {
                target = ois.readObject();
            }
        } catch (Throwable t) {
            // e.g. not java.io.Serializable (Hessian payloads): fall back to the object itself
            LogUtils.warn("Can not copy " + obj.getClass().getName() + " to compute its hash safely: " + t);
            target = obj;
        }
        try {
            return target.hashCode();
        } catch (Throwable t) {
            // the hash computation itself reaches the chain; the collision string does not matter then
            LogUtils.warn("hashCode() of " + obj.getClass().getName() + " throws (" + t + "), use 0 for the collision string");
            return 0;
        }
    }

    static List<Object> type1CollisionObjList = new ArrayList<>();

    /**
     * type-1 collision seed assemble.
     *
     * @param backboneClassRecordList
     * @param backBoneObjMap
     * @param fldInFldObjMap
     * @param protocol
     * @throws GeneratorException
     * @throws ReflectionException
     */
    public static void assembleBackboneObject1(List<ClassRecord> backboneClassRecordList,
                                               HashMap<String, Object> backBoneObjMap,
                                               HashMap<String, Object> fldInFldObjMap,
                                               HashSet<String> immutableFldSet,
                                               String protocol) throws GeneratorException, ReflectionException {
        // for type-1 collision seed, we start assemble the backbone object from the fourth class.
        Object lastBackBoneObj = null;
        String topCollisionClzName = backboneClassRecordList.get(0).getClassName();
        for (int index = TYPE1_COLLISION_START_INDEX; index < backboneClassRecordList.size(); index++) {
            if (VaadinItemSorterWorkaround.assemble(backboneClassRecordList, index, backBoneObjMap, immutableFldSet)) {
                continue;
            }
            ClassRecord clzRcd = backboneClassRecordList.get(index);
            String crtClzName = clzRcd.getClassName();
            String crtRealClzName = FuzzUtils.getRealClassNameKey(clzRcd.getId(), null, crtClzName);

            Pair<String, String> predecessorPair = clzRcd.getPredecessorRecord();
            String fldSig = predecessorPair.getValue();
            String fldName = CommonUtils.getFieldNameBySig(fldSig);
            // sometimes, there will be same type of object in the backbone object list.
            // if we find the type of a 'father' object of a backbone object is same with the 'top' object,
            // we would find the last same type backbone object behind the 'top' object in the backbone object list,
            // and use it as the 'father' object of the current object.
            String lastBackboneClzName = clzRcd.getPredecessorRecord().getKey();
            String realLastBackboneClzName = "";
            ClassRecord realLastBackboneClassRecord = null;
            if (lastBackboneClzName.equals(topCollisionClzName)) {
                for (int i = index - 1; i >= 0; i--) {
                    realLastBackboneClassRecord = backboneClassRecordList.get(i);
                    if (realLastBackboneClassRecord.getClassName().equals(topCollisionClzName)) {
                        realLastBackboneClzName = FuzzUtils.getRealClassNameKey(realLastBackboneClassRecord.getId(), null, lastBackboneClzName);
                        break;
                    }
                }
            } else {
                realLastBackboneClzName = FuzzUtils.getRealClassNameKey(0, null, lastBackboneClzName);
                realLastBackboneClassRecord = backboneClassRecordList.get(index - 1);
            }

            Object fldObj = null;
            {
                // if there is a field-in-field object in the field of the current class, we need to assemble it first.
                // set the `fldObj` to its father object's field
                fldObj = assembleFldInFldObj(clzRcd, realLastBackboneClassRecord, null, backBoneObjMap, fldInFldObjMap, immutableFldSet);

                String fldType = CommonUtils.getFieldTypeBySig(fldSig);
                // if there is no field-in-field object, then we would assemble the real fldObj here
                fldObj = getFldObjBeSetted(fldObj,
                        crtRealClzName,
                        fldType,
                        protocol,
                        realLastBackboneClassRecord.getId(),
                        null,
                        backBoneObjMap,
                        fldInFldObjMap,
                        immutableFldSet);
                if (FuzzUtils.COLLECTION_CLASSIC_CLASS.contains(lastBackboneClzName)) {
                    type1CollisionObjList.add(backBoneObjMap.get(FuzzUtils.getRealClassNameKey(clzRcd.getId(), null, crtClzName)));
                }
            }


            lastBackBoneObj = FuzzUtils.getCachedObject(realLastBackboneClzName, backBoneObjMap);
            // update the last object
            setFieldOfLastObject(lastBackBoneObj, fldName, proxyIfHandler(clzRcd, lastBackBoneObj, fldName, fldObj),
                    realLastBackboneClzName, immutableFldSet);
        }

        if (type1CollisionObjList != null && type1CollisionObjList.size() == 2) {
            Object obj1 = type1CollisionObjList.get(0);
            Object obj2 = type1CollisionObjList.get(1);

            String obj1Type = obj1.getClass().getName();
            String obj2Type = obj2.getClass().getName();

            String unhashStr = "";
            Object xStrObj = null, nonXStrObj = null;
            if (FuzzUtils.isXStringRelatedClass(obj1Type) || FuzzUtils.isXStringRelatedClass(obj2Type)) {
                if (FuzzUtils.isXStringRelatedClass(obj1Type)) {
                    FuzzUtils.handleSpecialClass(obj2, immutableFldSet, obj2Type);
                    unhashStr = FuzzUtils.unhash(sideEffectFreeHashCode(obj2));
                    xStrObj = obj1;
                } else if (FuzzUtils.isXStringRelatedClass(obj2Type)) {
                    FuzzUtils.handleSpecialClass(obj1, immutableFldSet, obj1Type);
                    unhashStr = FuzzUtils.unhash(sideEffectFreeHashCode(obj1));
                    xStrObj = obj2;
                }

                if (FuzzUtils.XSTRING.equals(xStrObj.getClass().getName())) {
                    ReflectionUtils.setFieldValue(xStrObj, "m_obj", unhashStr);
                } else if (FuzzUtils.XSTRING_FSB.equals(xStrObj.getClass().getName())) {
                    ReflectionUtils.setFieldValue(xStrObj, "m_obj", unhashStr);
                    ReflectionUtils.setFieldValue(xStrObj, "m_strCache", unhashStr);
                } else if (FuzzUtils.JAVAX_AUDIO_FORMAT.equals(xStrObj.getClass().getName())
                        || FuzzUtils.JAVAX_AUDIO_FORMAT_TYPE.equals(xStrObj.getClass().getName())) {
                    ReflectionUtils.setFieldValue(xStrObj, "name", unhashStr);
                }
            }
        }

        type1CollisionObjList = new ArrayList<>();
        
    }


    /**
     * the nearest record before `index` of class `clzName` (falls back to the previous record)
     */
    private static ClassRecord findPredecessorRecord(List<ClassRecord> records, int index, String clzName) {
        for (int i = index - 1; i >= 0; i--) {
            if (records.get(i).getClassName().equals(clzName)) {
                return records.get(i);
            }
        }
        return records.get(index - 1);
    }

    /**
     * the record right after `index` if it is reached through the same field of the same class as
     * `clzRcd`, i.e. the other operand of the collision method; null otherwise
     */
    private static ClassRecord findCollisionPairRecord(List<ClassRecord> records, int index, ClassRecord clzRcd) {
        if (index + 1 >= records.size()) {
            return null;
        }
        Pair<String, String> pred = clzRcd.getPredecessorRecord();
        Pair<String, String> nextPred = records.get(index + 1).getPredecessorRecord();
        if (pred == null || nextPred == null) {
            return null;
        }
        return pred.getKey().equals(nextPred.getKey()) && pred.getValue().equals(nextPred.getValue())
                ? records.get(index + 1) : null;
    }

    /**
     * Whether, when the seed is deserialized, the C1 collision object is the one whose equals() is
     * called. This depends on the container holding C1/C2 and on their order in it, so it is measured:
     * probes are put into `fldName` of C1 and C2, a copy of the top object is deserialized, and the
     * probe whose equals() runs tells the receiver side. Defaults to C1 when it cannot be measured
     * (e.g. objects that are not java.io.Serializable).
     */
    private static boolean isReceiverC1(Object topObj, Object c1, Object c2, String fldName) {
        byte[] bytes;
        try {
            Object old1 = ReflectionUtils.getFieldValue(c1, ReflectionUtils.getField(c1.getClass(), fldName));
            Object old2 = ReflectionUtils.getFieldValue(c2, ReflectionUtils.getField(c2.getClass(), fldName));
            try {
                updateLastObject0(c1, fldName, new CollisionSideProbe("C1"));
                updateLastObject0(c2, fldName, new CollisionSideProbe("C2"));
                FuzzUtils.repairSerializationInvariants(topObj);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
                    oos.writeObject(topObj);
                }
                bytes = bos.toByteArray();
            } finally {
                ReflectionUtils.setFieldValue(c1, fldName, old1);
                ReflectionUtils.setFieldValue(c2, fldName, old2);
            }
        } catch (Throwable t) {
            LogUtils.warn("Can not probe the receiver side of the collision, assume C1: " + t);
            return true;
        }
        CollisionSideProbe.lastReceiver = null;
        try (java.io.ObjectInputStream ois = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes))) {
            ois.readObject();
        } catch (Throwable t) {
            // only the probe's record matters
            LogUtils.debug("Collision probe deserialization ended with " + CommonUtils.constructExceptionStackTrace(t));
        }
        String receiver = CollisionSideProbe.lastReceiver;
        LogUtils.debug("Collision receiver side: " + (receiver == null ? "unknown, assume C1" : receiver));
        return !"C2".equals(receiver);
    }

    static List<Object> type2CollisionObjList = new ArrayList<>();
    /**
     * type-2 collision seed assemble.
     */
    public static void assembleBackboneObject2(List<ClassRecord> backboneClassRecordList,
                                               HashMap<String, Object> backBoneObjMap,
                                               HashMap<String, Object> fldInFldObjMap,
                                               HashSet<String> immutableFldSet,
                                               String protocol, int iocdHash)
            throws GeneratorException, ReflectionException {

        String topCollisionClzName = backboneClassRecordList.get(0).getClassName();
        for (int startIndex = TYPE2_COLLISION_START_INDEX; startIndex < backboneClassRecordList.size(); startIndex++) {
            if (VaadinItemSorterWorkaround.assemble(backboneClassRecordList, startIndex, backBoneObjMap, immutableFldSet)) {
                continue;
            }
            ClassRecord clzRcd = backboneClassRecordList.get(startIndex);
            String crtClzName = clzRcd.getClassName();
            
            // prepare some basic information about current class
            Pair<String, String> predecessorPair = clzRcd.getPredecessorRecord();
            String fldSig = predecessorPair.getValue();
            if (FuzzUtils.isCollectionFieldSigSetCandidate(fldSig)) {
                HashSet<SourceRecord> srcRecordSet = clzRcd.getCandidateSources();
                for (SourceRecord srcRecord : srcRecordSet) {
                    if (srcRecord.predecessorRecord.getValue().equals(fldSig)) {
                        continue;
                    }
                    fldSig = srcRecord.predecessorRecord.getValue();
                    predecessorPair = srcRecord.predecessorRecord;
                    break;
                }
            }

            String fldType = CommonUtils.getFieldTypeBySig(fldSig);
            String fldName = CommonUtils.getFieldNameBySig(fldSig);
            String lastObjType = predecessorPair.getKey();// Notice: must access the class name directly.  
            String realLastBackboneClzName = lastObjType;



            // check if the last object is a collision object
            // we use a 'fuzzy' way to check, cause we dont known the exact class id here.
            boolean isType2Collision = 
                ValidatorGlobalDataContainer.isCollisionClassWrapper(iocdHash, realLastBackboneClzName, 0) == 0 
                || ValidatorGlobalDataContainer.isCollisionClassWrapper(iocdHash, realLastBackboneClzName, 1) == 0;
            ClassRecord realLastBackboneClassRecord = findPredecessorRecord(backboneClassRecordList, startIndex, lastObjType);

            // the col-tag would be triggered only when the last object is a collision object.
            // if triggered, we need to update the object with C1 and C2 tag concurrently, 
            // or we just update as normal.
            if (isType2Collision) {
                // check if the type of the last object is same with the top object
                if (realLastBackboneClzName.equals(topCollisionClzName)) {
                    for (int i = startIndex - 1; i >= 0; i--) {
                        realLastBackboneClassRecord = backboneClassRecordList.get(i);
                        if (realLastBackboneClassRecord.getClassName().equals(topCollisionClzName)) {
                            realLastBackboneClassRecord = backboneClassRecordList.get(i);
                            break;
                        }
                    }
                }
                Object lastBackboneObj1, lastBackboneObj2;
                lastBackboneObj1 = FuzzUtils.getCachedObject(realLastBackboneClassRecord.getId(), "C1", lastObjType, backBoneObjMap);
                lastBackboneObj2 = FuzzUtils.getCachedObject(realLastBackboneClassRecord.getId(), "C2", lastObjType, backBoneObjMap);
                FuzzUtils.handleSpecialClass(lastBackboneObj1, lastBackboneObj2, lastObjType);

                if (FuzzUtils.COLLECTION_CLASSIC_CLASS.contains(realLastBackboneClzName)) {
                    type2CollisionObj1 = lastBackboneObj1;
                    type2CollisionObj2 = lastBackboneObj2;
                }

                Object fldObj1 = null, fldObj2 = null;
                ClassRecord pairRcd;
                // handle field-in-field situation
//                if (FuzzUtils.isWorkAroundClass(crtClzName)) {
//                    fldObj1 = assembleWorkAroundClassObject(backBoneObjMap.get(FuzzUtils.getRealClassNameKey(clzRcd.getId(), null, crtClzName)), backBoneObjMap, fldInFldObjMap, immutableFldSet, clzRcd.getId(), "C1");
//                    fldObj2 = assembleWorkAroundClassObject(backBoneObjMap.get(FuzzUtils.getRealClassNameKey(clzRcd.getId(), null, crtClzName)), backBoneObjMap, fldInFldObjMap, immutableFldSet, clzRcd.getId(), "C2");
//                } else {
                fldObj1 = assembleFldInFldObj(clzRcd, realLastBackboneClassRecord, "C1", backBoneObjMap, fldInFldObjMap, immutableFldSet);
                fldObj2 = assembleFldInFldObj(clzRcd, realLastBackboneClassRecord, "C2", backBoneObjMap, fldInFldObjMap, immutableFldSet);

                if (FuzzUtils.COLLECTION_CLASSIC_CLASS.contains(realLastBackboneClzName)) {
                    type2CollisionObjList.add(backBoneObjMap.get(
                            FuzzUtils.getRealClassNameKey(clzRcd.getId(), null, crtClzName)
                    ));
                    // the other operand of the collision method hangs off the same collision field;
                    // collect it here too (it would otherwise look for its own predecessor and find the
                    // wrong one when it shares a class name with the receiver, e.g. CHM in CHM)
                    if ((pairRcd = findCollisionPairRecord(backboneClassRecordList, startIndex, clzRcd)) != null) {
                        type2CollisionObjList.add(backBoneObjMap.get(
                                FuzzUtils.getRealClassNameKey(pairRcd.getId(), null, pairRcd.getClassName())));
                        startIndex++;
                    }
                } else if ((pairRcd = findCollisionPairRecord(backboneClassRecordList, startIndex, clzRcd)) != null) {
                    // Two consecutive classes hanging off the same field of the collision class are the
                    // two operands of the collision method, e.g. SerialArray.equals -> Arrays.equals(elements,
                    // other.elements) -> elements[0].equals(other.elements[0]): the first one (here clzRcd)
                    // is the receiver and must sit in the collision object whose equals() runs, the second
                    // one (pairRcd) is the argument and goes into the other collision object.
                    Object topObj = FuzzUtils.getCachedObject(backboneClassRecordList.get(0).getId(), null, topCollisionClzName, backBoneObjMap);
                    boolean receiverIsC1 = isReceiverC1(topObj, lastBackboneObj1, lastBackboneObj2, fldName);
                    String receiverTag = receiverIsC1 ? "C1" : "C2";
                    String argumentTag = receiverIsC1 ? "C2" : "C1";
                    Object receiverObj = getFldObjBeSetted(
                            assembleFldInFldObj(clzRcd, realLastBackboneClassRecord, receiverTag, backBoneObjMap, fldInFldObjMap, immutableFldSet),
                            FuzzUtils.getRealClassNameKey(clzRcd.getId(), null, crtClzName),
                            fldType, protocol, realLastBackboneClassRecord.getId(), receiverTag,
                            backBoneObjMap, fldInFldObjMap, immutableFldSet);
                    Object argumentObj = getFldObjBeSetted(
                            assembleFldInFldObj(pairRcd, realLastBackboneClassRecord, argumentTag, backBoneObjMap, fldInFldObjMap, immutableFldSet),
                            FuzzUtils.getRealClassNameKey(pairRcd.getId(), null, pairRcd.getClassName()),
                            fldType, protocol, realLastBackboneClassRecord.getId(), argumentTag,
                            backBoneObjMap, fldInFldObjMap, immutableFldSet);
                    Object receiverHolder = receiverIsC1 ? lastBackboneObj1 : lastBackboneObj2;
                    Object argumentHolder = receiverIsC1 ? lastBackboneObj2 : lastBackboneObj1;
                    updateLastObject0(receiverHolder, fldName, proxyIfHandler(clzRcd, receiverHolder, fldName, receiverObj));
                    updateLastObject0(argumentHolder, fldName, proxyIfHandler(pairRcd, argumentHolder, fldName, argumentObj));
                    startIndex++; // pairRcd is assembled
                } else {
                    fldObj1 = getFldObjBeSetted(fldObj1,
                            FuzzUtils.getRealClassNameKey(clzRcd.getId(), null, crtClzName), // the current ClassRecord wont be a collision object, so the colTag is null.
                            fldType, // NOTICE: we can not use `FuzzUtils.getRealClassNameKey` here as we need the raw type in `getFldObjBeSetted` method.
                            protocol,
                            realLastBackboneClassRecord.getId(),
                            "C1",
                            backBoneObjMap,
                            fldInFldObjMap,
                            immutableFldSet);
                    fldObj2 = getFldObjBeSetted(fldObj2,
                            FuzzUtils.getRealClassNameKey(clzRcd.getId(), null, crtClzName),
                            fldType,
                            protocol,
                            realLastBackboneClassRecord.getId(),
                            "C2",
                            backBoneObjMap,
                            fldInFldObjMap,
                            immutableFldSet);
                    // }

                    // update both collision-related backbone object
                    updateLastObject0(lastBackboneObj1, fldName, proxyIfHandler(clzRcd, lastBackboneObj1, fldName, fldObj1));
                    updateLastObject0(lastBackboneObj2, fldName, proxyIfHandler(clzRcd, lastBackboneObj2, fldName, fldObj2));
                }

                immutableFldSet.add(FuzzUtils.getRealClassNameKey(realLastBackboneClassRecord.getId(), "C1", lastObjType) + "#" + fldName);
                immutableFldSet.add(FuzzUtils.getRealClassNameKey(realLastBackboneClassRecord.getId(), "C2", lastObjType) + "#" + fldName);
            } else {
                Object lastBackboneObj;
                lastBackboneObj = FuzzUtils.getCachedObject(realLastBackboneClassRecord.getId(), null, lastObjType, backBoneObjMap);
                Object fldObj = null;
                {
                    fldObj = assembleFldInFldObj(clzRcd, realLastBackboneClassRecord, null, backBoneObjMap, fldInFldObjMap, immutableFldSet);
                    fldObj = getFldObjBeSetted(fldObj,
                            FuzzUtils.getRealClassNameKey(clzRcd.getId(), null, crtClzName),
                            fldType,
                            protocol,
                            realLastBackboneClassRecord.getId(),
                            null,
                            backBoneObjMap,
                            fldInFldObjMap,
                            immutableFldSet);
                }

                realLastBackboneClzName = FuzzUtils.getRealClassNameKey(realLastBackboneClassRecord.getId(), null, lastObjType);
                setFieldOfLastObject(lastBackboneObj, fldName, proxyIfHandler(clzRcd, lastBackboneObj, fldName, fldObj),
                        realLastBackboneClzName, immutableFldSet);
            }
        }

        if (type2CollisionObj1 != null && type2CollisionObj2 != null) {
            String type2CollisionObjType = type2CollisionObj1.getClass().getName();
            if (type2CollisionObjList != null && !type2CollisionObjList.isEmpty()) {
                if (type2CollisionObjList.size() == 2) {
                    Object obj1 = type2CollisionObjList.get(0);
                    Object obj2 = type2CollisionObjList.get(1);

                    String obj1Type = obj1.getClass().getName();
                    String obj2Type = obj2.getClass().getName();

                    String unhashStr = "";
                    Object xStrObj = null, nonXStrObj = null;
                    if (FuzzUtils.isXStringRelatedClass(obj1Type) || FuzzUtils.isXStringRelatedClass(obj2Type)) {
                        if (FuzzUtils.isXStringRelatedClass(obj1Type)) {
                            FuzzUtils.handleSpecialClass(obj2, immutableFldSet, obj2Type);
                            unhashStr = FuzzUtils.unhash(sideEffectFreeHashCode(obj2));
                            xStrObj = obj1;
                        } else if (FuzzUtils.isXStringRelatedClass(obj2Type)) {
                            FuzzUtils.handleSpecialClass(obj1, immutableFldSet, obj1Type);
                            unhashStr = FuzzUtils.unhash(sideEffectFreeHashCode(obj1));
                            xStrObj = obj2;
                        }

                        if (FuzzUtils.XSTRING.equals(xStrObj.getClass().getName())) {
                            ReflectionUtils.setFieldValue(xStrObj, "m_obj", unhashStr);
                        } else if (FuzzUtils.XSTRING_FSB.equals(xStrObj.getClass().getName())) {
                            ReflectionUtils.setFieldValue(xStrObj, "m_obj", unhashStr);
                            ReflectionUtils.setFieldValue(xStrObj, "m_strCache", unhashStr);
                        } else if (FuzzUtils.JAVAX_AUDIO_FORMAT.equals(xStrObj.getClass().getName())
                                    || FuzzUtils.JAVAX_AUDIO_FORMAT_TYPE.equals(xStrObj.getClass().getName())) {
                            ReflectionUtils.setFieldValue(xStrObj, "name", unhashStr);
                        }
                    }

                    if (FuzzUtils.ABS_MAP_SIMPLE_ENTRY.equals(type2CollisionObjType)
                            || FuzzUtils.ABS_MAP_SIMPLE_IMMUTABLE_ENTRY.equals(type2CollisionObjType)) {
                        ReflectionUtils.setFieldValue(type2CollisionObj1, "key", type2CollisionObjList.get(1));
                        ReflectionUtils.setFieldValue(type2CollisionObj1, "value", type2CollisionObjList.get(0));
                        ReflectionUtils.setFieldValue(type2CollisionObj2, "key", type2CollisionObjList.get(0));
                        ReflectionUtils.setFieldValue(type2CollisionObj2, "value", type2CollisionObjList.get(1));
                    } else if (FuzzUtils.SPRING_AOP_HOT_SWAPPABLE_TARGET_SOURCE.equals(type2CollisionObjType)) {
                        ReflectionUtils.setFieldValue(type2CollisionObj1, "target", type2CollisionObjList.get(1));
                        ReflectionUtils.setFieldValue(type2CollisionObj2, "target", type2CollisionObjList.get(0));
                    } else {
                        ReflectionUtils.invokeMethodByName(type2CollisionObj1, "put", "xx", type2CollisionObjList.get(1));
                        ReflectionUtils.invokeMethodByName(type2CollisionObj1, "put", "yy", type2CollisionObjList.get(0));
                        ReflectionUtils.invokeMethodByName(type2CollisionObj2, "put", "xx", type2CollisionObjList.get(0));
                        ReflectionUtils.invokeMethodByName(type2CollisionObj2, "put", "yy", type2CollisionObjList.get(1));
                    }
                }
            }

        }
        type2CollisionObj1 = null;
        type2CollisionObj2 = null;
        type2CollisionObjList = new ArrayList<>();
    }

    /**
     * @param clzName  该类名可以输入soot风格的
     * @param protocol gadget的协议
     * @return
     * @throws GeneratorException
     */
    public static Object generateObjectByName(String clzName, String protocol) throws GeneratorException {
        Object resObj = null;
        clzName = ClassUtils.transformTypeForJimple(clzName);
        Class clz = ClassUtils.getClassByName(clzName);
        if (clz == null) {
            throw new GeneratorException("In `generateObjectByName`: Cant find " + clzName + " in current JVM context");
        }
        if (FuzzUtils.isSpecialExpressionInjectionClass(clzName)) {
            // For Expression Injection related classes, we need to generate the object with the special way. 
            resObj = generateExpressionInjectionObject(clzName, protocol);
        } else {
            if (ClassUtils.isConcreteByClzNameStrict(clzName) || clz.isArray()) {
                // concrete类的情况，直接生成
                resObj = createObjectWithReflection(clzName, protocol);
            } else {
                // 非concrete类的情况，找到所有的concrete子类，记录后生成
                HashSet<String> tmpConcreteClzNameSet;
                HashSet<Pair<String, Boolean>> concreteClzRecord;
                if (!ValidatorGlobalDataContainer.singletonConcreteSubClassRecord.containsKey(clzName)) {
                    tmpConcreteClzNameSet = ClassUtils.getAllConcreteSubClass(clzName, CommonUtils.isSerializableNeed(protocol));
                    if (tmpConcreteClzNameSet.isEmpty() && CommonUtils.isSerializableNeed(protocol)) {
                        // No Serializable implementation exists. The IOCD still names this class as a chain node,
                        // typically one held in a transient field that the stream drops and the reading class
                        // rebuilds (e.g. java.net.URL.handler, a URLStreamHandler that readObject recreates from the
                        // protocol). Take any concrete implementation instead of failing the seed; if such an
                        // object does end up in a serialized field, writing it fails and the seed is simply
                        // not verified, as before.
                        tmpConcreteClzNameSet = ClassUtils.getAllConcreteSubClass(clzName, false);
                    }
                    concreteClzRecord = new HashSet<>();
                    tmpConcreteClzNameSet.forEach(tmpClzName -> concreteClzRecord.add(new Pair<>(tmpClzName, false)));
                    ValidatorGlobalDataContainer.addConcreteSubClassRecord(clzName, concreteClzRecord);
                }
                String concreteClzName = ValidatorGlobalDataContainer.getUnusedConcreteClzRandomly(clzName);
                if (concreteClzName == null) {
                    throw new GeneratorException("In `generateObjectByName`: Seed generation failed, can not find suitable concrete class ( " + clzName + ") or all of its concrete class are invalid.");
                }
                resObj = createObjectWithReflection(concreteClzName, protocol);
            }
        }
        return resObj;
    }

    /**
     * 通过反射生成特定类型的对象
     *
     * @return 结果对象
     */
    public static Object createObjectWithReflection(String clzName, String protocol) throws GeneratorException {
        String finalProtocol = protocol == null ? "jdk" : protocol;
        String realClzName = ClassUtils.transformTypeForJimple(clzName);
        Class<?> clz = ClassUtils.getClassByName(realClzName);
        Object resObj;
        if (clz.isPrimitive() || ClassUtils.isPrimitiveObjectStrict(clz)) {
            // 由于对于基本类型会自动的装箱、解箱的特性，直接创建就行
            resObj = genPrimitiveTypeValRandomly(clz, randomness);
        } else if (clz.isArray()) {
            int arrSize = CommonUtils.generateRandomInt(2, 10);
            Class itemType = clz.getComponentType();
            resObj = Array.newInstance(itemType, arrSize);
            // 考虑到数组的类型有可能是非Concrete类型，因此使用generateObjectByName方法
            Object arrItemObj = generateObjectByName(itemType.getName(), finalProtocol);
            for (int j = 0; j < arrSize; j++) {
                Array.set(resObj, j, arrItemObj);
            }
        } else {
            try {
                resObj = ReflectionUtils.createWithNoArgsConstructor(clz);
            } catch (Exception e) {
                throw new GeneratorException(e);
            }
            fillNullContainerFields(resObj);
        }
        return resObj;
    }

    // interface type -> empty concrete instance, for container fields left null by the
    // deserialization constructor
    private static final LinkedHashMap<Class<?>, java.util.function.Supplier<Object>> EMPTY_CONTAINERS = new LinkedHashMap<>();
    static {
        EMPTY_CONTAINERS.put(Map.class, HashMap::new);
        EMPTY_CONTAINERS.put(java.util.SortedMap.class, java.util.TreeMap::new);
        EMPTY_CONTAINERS.put(java.util.NavigableMap.class, java.util.TreeMap::new);
        EMPTY_CONTAINERS.put(Collection.class, ArrayList::new);
        EMPTY_CONTAINERS.put(List.class, ArrayList::new);
        EMPTY_CONTAINERS.put(Set.class, HashSet::new);
        EMPTY_CONTAINERS.put(java.util.SortedSet.class, java.util.TreeSet::new);
        EMPTY_CONTAINERS.put(java.util.NavigableSet.class, java.util.TreeSet::new);
        EMPTY_CONTAINERS.put(java.util.Queue.class, java.util.ArrayDeque::new);
        EMPTY_CONTAINERS.put(java.util.Deque.class, java.util.ArrayDeque::new);
    }

    /**
     * Objects built without running a real constructor (e.g. commons-collections' LazyMap via
     * AbstractMapDecorator()) keep their wrapped container null, so any use of the object
     * (LazyMap.get -> map.containsKey) throws a NullPointerException before the gadget chain is
     * reached. Give such fields an empty container, as a real constructor would. Only fields declared
     * in non-JDK classes whose type is exactly a container interface are touched: JDK collections use
     * null container fields as lazily-created views (e.g. Hashtable.entrySet), which must stay null.
     * Fields the gadget chain needs are overwritten later during assembly, and mutation can still set
     * them to null.
     */
    private static void fillNullStringFields(Object obj) {
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field fld : c.getDeclaredFields()) {
                if (fld.getType() != String.class || java.lang.reflect.Modifier.isStatic(fld.getModifiers())) {
                    continue;
                }
                try {
                    ReflectionUtils.setAccessible(fld);
                    if (fld.get(obj) == null) {
                        fld.set(obj, "");
                    }
                } catch (Exception e) {
                    LogUtils.debug("Can not fill String field " + fld + ": " + e.getMessage());
                }
            }
        }
    }

    private static final java.util.regex.Pattern MAP_EQUALS_SIG =
            java.util.regex.Pattern.compile("<([\\w.$]+): boolean equals\\(java\\.lang\\.Object\\)>");
    private static final java.util.regex.Pattern MAP_GET_SIG =
            java.util.regex.Pattern.compile("<([\\w.$]+): java\\.lang\\.Object get\\(java\\.lang\\.Object\\)>");
    private static final Set<String> MAP_EQUALS_RECEIVERS = new HashSet<>(Arrays.asList(
            "java.util.Hashtable", "java.util.HashMap", "java.util.concurrent.ConcurrentHashMap"));

    /**
     * For a chain link `M.equals(Object) -> D.get(Object)` (e.g. ConcurrentHashMap.equals -> LazyMap.get),
     * M.equals only calls get() on its argument once per own entry, so an empty receiver map never
     * reaches D.get. Give empty receiver maps an entry (s, s): an entry with key == value adds
     * s.hashCode() ^ s.hashCode() == 0 to the map's hash, so hash collisions built around the map are
     * kept. Hashtable/HashMap (AbstractMap) equals compare sizes first, so the argument decorator's
     * inner map gets one such entry too, with another key so that get(s) still misses it and calls the
     * decorator's factory.
     */
    private static void primeMapEqualsOperands(int iocdHash, Map<String, Object> backBoneObjMap) {
        IOCD iocd = GlobalDataContainer.getIOCDByHash(iocdHash);
        if (iocd == null || iocd.getGadgetCallStack() == null) {
            return;
        }
        List<String> sigs = new ArrayList<>();
        for (Pair<Integer, String> p : iocd.getGadgetCallStack()) {
            sigs.add(p.getValue());
        }
        for (int i = 0; i + 1 < sigs.size(); i++) {
            java.util.regex.Matcher eq = MAP_EQUALS_SIG.matcher(sigs.get(i));
            java.util.regex.Matcher get = MAP_GET_SIG.matcher(sigs.get(i + 1));
            if (!eq.matches() || !get.matches() || !MAP_EQUALS_RECEIVERS.contains(eq.group(1))) {
                continue;
            }
            String receiverClz = eq.group(1), argumentClz = get.group(1);
            boolean sizeChecked = !receiverClz.equals("java.util.concurrent.ConcurrentHashMap");
            for (Object obj : backBoneObjMap.values()) {
                if (obj == null) {
                    continue;
                }
                try {
                    if (obj.getClass().getName().equals(receiverClz) && ((Map) obj).isEmpty()) {
                        ((Map) obj).put("jddReceiverKey", "jddReceiverKey");
                    } else if (sizeChecked && obj.getClass().getName().equals(argumentClz)) {
                        Object inner = ReflectionUtils.getFieldValue(obj, "map");
                        if (inner instanceof Map && ((Map) inner).isEmpty()) {
                            ((Map) inner).put("jddArgumentKey", "jddArgumentKey");
                        }
                    }
                } catch (Exception | Error e) {
                    LogUtils.debug("Can not prime " + obj.getClass().getName() + " for " + sigs.get(i) + ": " + e);
                }
            }
        }
    }

    private static boolean isJdkClassName(String clzName) {
        return clzName.startsWith("java.") || clzName.startsWith("javax.") || clzName.startsWith("sun.")
                || clzName.startsWith("com.sun.") || clzName.startsWith("jdk.");
    }

    private static void fillNullContainerFields(Object obj) {
        if (obj == null) {
            return;
        }
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (isJdkClassName(c.getName())) {
                continue;
            }
            for (Field fld : c.getDeclaredFields()) {
                java.util.function.Supplier<Object> empty = EMPTY_CONTAINERS.get(fld.getType());
                if (empty == null || java.lang.reflect.Modifier.isStatic(fld.getModifiers())) {
                    continue;
                }
                try {
                    ReflectionUtils.setAccessible(fld);
                    if (fld.get(obj) == null) {
                        fld.set(obj, empty.get());
                    }
                } catch (Exception e) {
                    LogUtils.debug("Can not fill container field " + fld + ": " + e.getMessage());
                }
            }
        }
    }

    public static Object genPrimitiveTypeValRandomly(Class<?> fldType, SourceOfRandomness sourceOfRandomness) {
        Object mutatedObj = null;
        switch (ClassUtils.getPrimitiveTypeString(fldType)) {
            case "int":
                mutatedObj = sourceOfRandomness.nextInt(0, 10);
                break;
            case "double":
                mutatedObj = sourceOfRandomness.nextDouble(0, 10);
                break;
            case "boolean":
                mutatedObj = (sourceOfRandomness.nextInt() % 2 == 0);
                break;
            case "char":
                mutatedObj = sourceOfRandomness.nextChar(' ', 'z');
                break;
            case "byte":
                mutatedObj = sourceOfRandomness.nextByte((byte) -128, (byte) 127);
                break;
            case "short":
                mutatedObj = (short) sourceOfRandomness.nextInt();
                break;
            case "long":
                mutatedObj = sourceOfRandomness.nextLong();
                break;
            case "float":
                mutatedObj = sourceOfRandomness.nextFloat();
                break;
            default:
                LogUtils.warn2Log("In `SeedGenerator.genPrimitiveTypeValRandomly`: " + fldType.getName() + "The value is not a basic seedType");
                break;
        }

        return mutatedObj;
    }


    /**
     * use for construct a injection object in correct hirearchy.
     *
     * @param lastObj   object of last layer
     * @param fieldName the field name of the last object
     * @param fldObj    the object be set to the field
     * @return we wont use the return value currently
     * @throws GeneratorException 
     */
    /**
     * The value a backbone object is set to in its predecessor's field. A class the IOCD marks isProxy is an
     * InvocationHandler the chain reaches through a dynamic proxy (e.g. PriorityQueue.comparator -> a Comparator
     * proxy -> CompositeDataInvocationHandler.invoke), so the field gets a proxy over the handler implementing the
     * interfaces the IOCD lists (addProxyInterface) and the field's type when that is an interface. The handler
     * itself stays the backbone object: later ClassRecords set its fields.
     */
    private static Object proxyIfHandler(ClassRecord clzRcd, Object lastObj, String fieldName, Object fldObj) {
        if (clzRcd == null || !clzRcd.isProxy || lastObj == null
                || !(fldObj instanceof InvocationHandler) || Proxy.isProxyClass(fldObj.getClass())) {
            return fldObj;
        }
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        LinkedHashSet<Class<?>> interfaces = new LinkedHashSet<>();
        for (String name : new TreeSet<>(clzRcd.addProxyInterface)) {
            try {
                Class<?> itf = Class.forName(name, false, loader);
                if (itf.isInterface()) {
                    interfaces.add(itf);
                }
            } catch (Throwable e) {
                LogUtils.warn("In `proxyIfHandler`: can not load proxy interface " + name + ": " + e);
            }
        }
        try {
            Field fld = ReflectionUtils.findField(lastObj.getClass(), fieldName);
            Class<?> fldType = fld == null ? null : (fld.getType().isArray() ? fld.getType().getComponentType() : fld.getType());
            if (fldType != null && fldType.isInterface()) {
                interfaces.add(fldType);
            }
        } catch (ReflectionException ignored) {
            // the field is reported by updateLastObject0
        }
        if (interfaces.isEmpty()) {
            return fldObj;
        }
        try {
            return Proxy.newProxyInstance(loader, interfaces.toArray(new Class<?>[0]), (InvocationHandler) fldObj);
        } catch (IllegalArgumentException e) {
            // e.g. non-public interfaces from different packages
            LogUtils.warn("In `proxyIfHandler`: can not proxy " + fldObj.getClass().getName() + " as " + interfaces + ": " + e.getMessage());
            return fldObj;
        }
    }

    /**
     * Sets {@code value} into field {@code fldName} of {@code owner} and protects what was set from later assembly
     * steps. When the field is a Map/Collection of a library class and {@code value} is an element of it, the element
     * goes in through the owner's own mutator (OwnerMutatorAssembly) and every field that call changed is protected;
     * otherwise the field is written as before and that one field is protected.
     */
    private static void setFieldOfLastObject(Object owner, String fldName, Object value, String ownerKey,
                                             HashSet<String> immutableFldSet) throws ReflectionException, GeneratorException {
        List<String> changed = OwnerMutatorAssembly.tryInsert(owner, fldName, value);
        if (changed != null) {
            for (String f : changed) {
                immutableFldSet.add(ownerKey + "#" + f);
            }
            return;
        }
        updateLastObject0(owner, fldName, value);
        immutableFldSet.add(ownerKey + "#" + fldName);
    }

    private static Object updateLastObject0(Object lastObj, String fieldName, Object fldObj) throws ReflectionException, GeneratorException {
        if (lastObj == null) {
            throw new GeneratorException("In `updateLastObject0`: lastObj is null, when setting field <" + fieldName + ">");
        }
        Field lastObjFld;
        try {
            lastObjFld = ReflectionUtils.getField(lastObj.getClass(), fieldName);
        } catch (Exception e) {
            throw new GeneratorException("In `updateLastObject0`: can not find the field <" + fieldName + "> in the object <" + lastObj.getClass().getName() + ">, maybe the inaccurancy of the static analysis.");
        }
        if (Modifier.isStatic(lastObjFld.getModifiers())) {
            // static fields are not part of the serialized object, and setting one (e.g. the JDK constant
            // UIDefaults.PENDING) would only change this JVM's global state, so leave it alone
            LogUtils.warn("In `updateLastObject0`: skip static field <" + fieldName + "> of <" + lastObj.getClass().getName() + ">");
            return fldObj;
        }
        Object lastObjFldValue = ReflectionUtils.getFieldValue(lastObj, lastObjFld);
        if (lastObjFld.getType().isArray()) {
            // if the field is an array
            if (ClassUtils.isArrayTypeMatch(lastObjFld.getType(), fldObj.getClass())) {
                // if the type is matched, check if the field is empty or not
                if (lastObjFldValue != null && Array.getLength(lastObjFldValue) != 0) {
                    // if the field is not empty, we need to carefully handle the original element.
                    Object newArr = Array.newInstance(lastObjFldValue.getClass().getComponentType(), 2);
                    // we have 50% probability to exchange the two elements.
                    newArr = shuffleArrayItemOrNot0(newArr, lastObjFldValue, fldObj);
                    ReflectionUtils.setFieldValue(lastObj, fieldName, newArr);
                } else {
                    // if the field is empty, set it directly
                    ReflectionUtils.setFieldValue(lastObj, lastObjFld, fldObj);
                }
            } else {
                // if the type is not matched, check if the fldObj is a Array or not.
                if (fldObj.getClass().isArray()) {
                    throw new GeneratorException("In `updateLastObject0`: the type of the field <" + fieldName + "> is not matched with the type of the object to be set.");
                } else {
                    // if the fldObj is not an array, we try to set it to the array.
                    if (lastObjFldValue != null) {
                        IntWrapper notNullIndex = new IntWrapper(-1);
                        int tmpLen = getNotNullArrItemLen(lastObjFldValue, notNullIndex);
                        if (tmpLen == 0) {
                            Object newArr = Array.newInstance(lastObjFldValue.getClass().getComponentType(), 1);
                            Array.set(newArr, 0, fldObj);
                            ReflectionUtils.setFieldValue(lastObj, fieldName, newArr);
                        } else if (tmpLen == 1) {
                            Object newArr = Array.newInstance(lastObjFldValue.getClass().getComponentType(), 2);
                            newArr = shuffleArrayItemOrNot1(newArr, lastObjFldValue, fldObj);
                            ReflectionUtils.setFieldValue(lastObj, fieldName, newArr);
                        } else {
                            shuffleArrayItemOrNot(lastObjFldValue, fldObj);
                        }
                    } else {
                        Object newArr = Array.newInstance(lastObjFld.getType().getComponentType(), 1);
                        Array.set(newArr, 0, fldObj);
                        ReflectionUtils.setFieldValue(lastObj, fieldName, newArr);
                    }
                    
                    
                }
            }
        } else {
            // if the field is not an array, we set it directly.
            ReflectionUtils.setFieldValue(lastObj, fieldName, fldObj);
        }    
        return fldObj;
    }

    private static Object updateLastObject2(Object lastObj, String fieldName, Object fldObj) throws ReflectionException {
        Field lastObjFld = ReflectionUtils.getField(lastObj.getClass(), fieldName);
        // Notice: 对于hash碰撞的那几个field进行特殊的处理
        if (FuzzUtils.isJdkFormCollectionClass(lastObjFld.getType().getName())) {
            Object lastObjFldValue = ReflectionUtils.getFieldValue(lastObj, fieldName);
            // 如果数组为空，那就直接设置
            // 如果不为空，那就从数组中前两个位置中随机选一个位置设置
            if (lastObjFldValue == null) {
                ReflectionUtils.setFieldValue(lastObj, fieldName, fldObj);
            } else {
                // 这块的处理是担心数组中的元素只有一个，那么就会出现数组越界的问题或者直接覆盖的
                int index = CommonUtils.generateRandomInt(0, 1);
                IntWrapper notNullIndex = new IntWrapper(-1);
                int tmpLen = getNotNullArrItemLen(lastObjFldValue, notNullIndex);
                // 如果长度只有1进行扩容，而后设置
                if (tmpLen == 1) {
                    Object newArr = Array.newInstance(lastObjFldValue.getClass().getComponentType(), 2);
                    if (index == 0) {
                        Array.set(newArr, 1, Array.get(lastObjFldValue, notNullIndex.value));
                        Array.set(newArr, 0, Array.get(fldObj, 0));
                    } else {
                        Array.set(newArr, 0, Array.get(lastObjFldValue, notNullIndex.value));
                        Array.set(newArr, 1, Array.get(fldObj, 0));
                    }
                    // 扩容后是一个新的数组，因此应当进行更新
                    ReflectionUtils.setFieldValue(lastObj, fieldName, newArr);
                } else {
                    // 长度不是1那就直接设置
                    Array.set(lastObjFldValue, index, Array.get(fldObj, 0));
                }
            }
        } else {
            // 不是那几个hash碰撞相关的直接进行设置便可
            if (lastObjFld.getType().isArray()) {
                // 数组的情况，如果为空，新建一个进行设置
                // 如果不为空，那就覆盖掉第一个元素
                Object lastObjFldValue = ReflectionUtils.getFieldValue(lastObj, fieldName);
                if (lastObjFldValue == null) {
                    Object newArr = Array.newInstance(lastObjFld.getType().getComponentType(), 1);
                    Array.set(newArr, 0, fldObj);
                    ReflectionUtils.setFieldValue(lastObj, fieldName, newArr);
                } else {
                    Array.set(lastObjFldValue, 0, fldObj);
                }
            } else {
                ReflectionUtils.setFieldValue(lastObj, fieldName, fldObj);
            }
        }
        return fldObj;
    }


    private static int getNotNullArrItemLen(Object arr, IntWrapper notNullIndex) {
        int res = 0;
        for (int i = 0; i < Array.getLength(arr); i++) {
            if (Array.get(arr, i) != null) {
                res++;
                notNullIndex.value = i;
            }
        }
        return res;
    }


    /**
     * 对于java.util.HashMap$Node[]和java.util.Hashtable$Entry[]这样的，无法确定是应该把Object放到key还是放到value
     * 基于Fuzz使用随机的方式进行填充
     *
     * @param collectionArrField
     * @param target
     * @return
     */
    public static void fillSpecialCollectionFieldRandomly(Object collectionArrField, Object target) throws GeneratorException {
        try {
            Object arrItem;
            if (collectionArrField.getClass().isArray()) {
                arrItem = Array.get(collectionArrField, 0);
            } else {
                arrItem = collectionArrField;
            }
            int selectTimes = 0;
            String fldName = "val";
            if (CommonUtils.generateWith90PercentTrue()) {
                if (!ReflectionUtils.isFieldExist(arrItem.getClass(), fldName)) {
                    fldName = "value";
                }
            } else {
                fldName = selectNodeFieldRandomly();
                while (!ReflectionUtils.isFieldExist(arrItem.getClass(), fldName)) {
                    selectTimes++;
                    fldName = selectNodeFieldRandomly();
                    LogUtils.debug2Log("selectTimes: " + selectTimes + ", fldName: " + fldName);
                }
            }

            selectedNodeFieldNameCache = new HashSet<>(); // 挑选完成后，清空

            if (!fldName.equals("key")) {
                // if (CommonUtils.generateRandomBoolean()) {
                // 放到key
                ReflectionUtils.setFieldValue(
                        arrItem,
                        "key",
                        // CommonUtils.generateRandomBoolean() ?
                                CommonUtils.generateRandomString()
                                // : target
                );
                    // return;
                // }
            } else {
                // if (CommonUtils.generateRandomBoolean()) {
                String valueFldName = ReflectionUtils.isFieldExist(arrItem.getClass(), "val") ? "val" : "value";
                // 放到value
                ReflectionUtils.setFieldValue(arrItem,
                        valueFldName,
                        // CommonUtils.generateRandomBoolean() ?
                                CommonUtils.generateRandomString()
                                // : target
                );
                    // return;
                // }
            }
            ReflectionUtils.setFieldValue(arrItem, fldName, target);
        } catch (Exception e) {
            throw new GeneratorException("In `fillSpecialCollectionFieldRandomly`: setting " + collectionArrField.getClass().getName() + "failed: " + e.getMessage());
        }
    }

    // this method is only used on field dependency model.
    public static Object genAndSetSpecialCollectionFieldConcretely(Object collectionArrField, String fldName) {
        Object arrItem;
        String randomStr;
        if (collectionArrField.getClass().isArray()) {
            arrItem = Array.get(collectionArrField, 0);
        } else {
            arrItem = collectionArrField;
        }
        if (!ReflectionUtils.isFieldExist(arrItem.getClass(), fldName)) {
            LogUtils.warn("In `genSpecialCollectionFieldConcretely`: " + fldName + " is not exist in " + arrItem.getClass().getName());
            return "HN13-INVALID";
        }
        randomStr = CommonUtils.generateRandomString();
        try {
            // ReflectionUtils.setFieldValue(arrItem, fldName, randomStr + "2");
            ReflectionUtils.setFieldValue(arrItem, fldName, new OracleClassA());
            String valueFldName = ReflectionUtils.isFieldExist(arrItem.getClass(), "val") ? "val" : "value";
            ReflectionUtils.setFieldValue(arrItem, valueFldName, randomStr + "1");
        } catch (Exception e) {
            LogUtils.warn("In `genSpecialCollectionFieldConcretely`: set field value failed" + e.getMessage());
            return "HN13-INVALID";
        }
        return randomStr;
    }

    /**
     * 工具方法，在填充HashMap、Hashtable的Node或Entry数组时，他们的key和value对应的field名不是确定的
     * 因此用随机的手法挑选字典里合适的
     *
     * @return
     * @throws GeneratorException 如果COLLECTION_NODE_FIELD_NAME_LIST被挑完了都没有，说明有新的field出现
     */
    private static String selectNodeFieldRandomly() throws GeneratorException {
        String res = CommonUtils.getRandomItemFromList(COLLECTION_NODE_FIELD_NAME_LIST);
        while (selectedNodeFieldNameCache.contains(res)) {
            if (selectedNodeFieldNameCache.size() == COLLECTION_NODE_FIELD_NAME_LIST.size()) {
                throw new GeneratorException("In `selectNodeFieldRandomly`: no suitable field name can be selected.");
            }
            res = CommonUtils.getRandomItemFromList(COLLECTION_NODE_FIELD_NAME_LIST);
        }
        selectedNodeFieldNameCache.add(res);
        return res;
    }

    private static Object shuffleArrayItemOrNot0(Object newArr, Object lastObjFldValue, Object fldObj) {
        if (CommonUtils.generateWithProbability(0.5)) {
            Array.set(newArr, 0, Array.get(lastObjFldValue, 0));
            Array.set(newArr, 1, Array.get(fldObj, 0)); 
        } else {
            Array.set(newArr, 0, Array.get(fldObj, 0));
            Array.set(newArr, 1, Array.get(lastObjFldValue, 0)); 
        }
        return newArr;
    }

    private static Object shuffleArrayItemOrNot1(Object newArr, Object lastObjFldValue, Object fldObj) {
        if (CommonUtils.generateWithProbability(0.5)) {
            Array.set(newArr, 0, Array.get(lastObjFldValue, 0));
            Array.set(newArr, 1, fldObj);
        } else {
            Array.set(newArr, 0, fldObj);
            Array.set(newArr, 1, Array.get(lastObjFldValue, 0));
        }
        return newArr;
    }

    private static void shuffleArrayItemOrNot(Object arr, Object objToSet) {
        if (CommonUtils.generateWithProbability(0.5)) {
            Array.set(arr, 0, objToSet);
        } else {
            Array.set(arr, 1, objToSet);
        }
    }

    public static Object generateJVMStylePrimitiveArr(String type) {
        String itemType = type.substring(1);
        int arrSize = CommonUtils.generateRandomInt(2, 10);
        if (itemType.equals("B")) {
            return new byte[arrSize];
        } else if (itemType.equals("C")) {
            return new char[arrSize];
        } else if (itemType.equals("D")) {
            return new double[arrSize];
        } else if (itemType.equals("F")) {
            return new float[arrSize];
        } else if (itemType.equals("I")) {
            return new int[arrSize];
        } else if (itemType.equals("J")) {
            return new long[arrSize];
        } else if (itemType.equals("S")) {
            return new short[arrSize];
        } else {
            LogUtils.warn("In `generateJVMStylePrimitiveArr`: " + type + " is not support yet");
        }
        return new byte[arrSize];
    }

    private static void throwBackboneObjAssembleException(String fldType, HashMap<String, Object> backBoneObjMap) throws GeneratorException {
        StringBuilder sb = new StringBuilder();
        for (String backboneClzName : backBoneObjMap.keySet()) {
            sb.append(backboneClzName).append("  ");
        }
        throw new GeneratorException("can not get "
                + fldType
                + "object from backboneObjMap, the Map only contains "
                + sb);
    }

}
