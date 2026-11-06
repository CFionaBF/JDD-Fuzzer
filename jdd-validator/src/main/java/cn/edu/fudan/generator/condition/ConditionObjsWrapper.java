package cn.edu.fudan.generator.condition;

import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import cn.edu.fudan.datastructure.OracleClassA;
import cn.edu.fudan.exception.validator.*;
import cn.edu.fudan.generator.seed.SeedGenerator;
import cn.edu.fudan.generator.seed.SeedWrapper;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.FuzzUtils;
import cn.edu.fudan.utils.LogUtils;
import cn.edu.fudan.utils.ReflectionUtils;
import datastructrue.IntWrapper;
import datastructrue.Pair;
import datastructrue.iocd.IOCD;
import datastructrue.iocd.unit.ConditionRecord;

import java.util.*;

/**
 * 这是一个全局对象，用于记录Condition相关信息和使用情况
 */
public class ConditionObjsWrapper {

    public int iocdHash;
    // Thinking: 这里是不是可以做类似于GlobalContainer那样的压缩
    public ArrayList<Pair<Integer, SingletonCondition>> conditionObjsList;
    // 非Basic的Condition的序列
    // key: 第几段序列
    // value: 对应conditionRecordMap的Condition的hash索引
    public HashMap<Integer, LinkedHashSet<Integer>> noneBscConditionObjsSegMap;
    // key: bscCondition的hash
    // value: 第几段序列
    public LinkedHashMap<Integer, Integer> bscConditionHashSegIndexMap;
    // 正序的ConditionRecord的Map
    public LinkedHashMap<Integer, ConditionRecord> conditionRecordMap;

    public ConditionObjsWrapper() {
        this.conditionObjsList = new ArrayList<>();
        this.conditionRecordMap = new LinkedHashMap<>();
    }

    public ConditionObjsWrapper(IOCD iocd) throws ConditionObjsGeneratorException {
        this.iocdHash = iocd.getHashCode();
        this.conditionRecordMap = GlobalDataContainer.getConditionMapByIOCDHash(iocdHash);
        if (this.conditionRecordMap == null) {
            // an IOCD without condition records (none were extracted for it): nothing to set
            this.conditionRecordMap = new LinkedHashMap<>();
        }
        this.noneBscConditionObjsSegMap = new HashMap<>();
        this.bscConditionHashSegIndexMap = new LinkedHashMap<>();
        this.conditionObjsList = ConditionObjsGenerator.genConditionObjsListAndSegInfo(
                this.conditionRecordMap, this.noneBscConditionObjsSegMap, this.bscConditionHashSegIndexMap
        );
    }

    /**
     * 进行Basic Condition对应对象的设置
     *
     * @param seed
     * @throws ConditionObjSettingException
     */
    public void doBscConditionObjsSetting(SeedWrapper seed) throws ConditionObjSettingException {
        SingletonCondition condition = null;
        try {
            for (Pair<Integer, SingletonCondition> conditionPair : this.conditionObjsList) {
                condition = conditionPair.getValue();
                if (!condition.isBasic) {
                    continue;
                }
                // 取出相应的对象
                if (ValidatorGlobalDataContainer.isCollisionClassWrapper(iocdHash, condition.className, condition.classId) == 0) {
                    String finalClzName = FuzzUtils.getRealClassNameKey(condition.classId, "C1", condition.className);
                    String finalClzName2 = FuzzUtils.getRealClassNameKey(condition.classId, "C2", condition.className);
                    try {
                        Object objToSet1 = seed.getObjFromInjectionObjByName(finalClzName);
                        Object objToSet2 = seed.getObjFromInjectionObjByName(finalClzName2);
                        String conditionField = condition.fieldName;
                        if (!seed.isMutatableField(finalClzName, conditionField)) {
                            // if the field is immutable, then we pass it
                            continue;
                        }
                        checkAndSetCondition(seed, objToSet1, condition);
                        checkAndSetCondition(seed, objToSet2, condition);
                    } catch (ObjectNotFoundException e) {
                        // if we can not find the collision object, that probably means the collison objects have the same type of top object. try to get it here.
                        String topObjClzName = FuzzUtils.getRealClassNameKey(condition.classId, null, condition.className);
                        Object topObj = seed.getObjFromInjectionObjByName(topObjClzName); // if we can not find the top object, the exception will be thrown from here.
                        if (!seed.isMutatableField(topObjClzName, condition.fieldName)) {
                            // if the field is immutable, then we pass it
                            continue;
                        }
                        checkAndSetCondition(seed, topObj, condition);
                    }
                } else {
                    String finalClzName = FuzzUtils.getRealClassNameKey(condition.classId, null, condition.className);
                    String conditionField = condition.fieldName;
                    if (!seed.isMutatableField(finalClzName, conditionField)) {
                        // if the field is immutable, then we pass it
                        continue;
                    }
                    Object objToSet = seed.getObjFromInjectionObjByName(finalClzName);
                    checkAndSetCondition(seed, objToSet, condition);
                }

            }
        } catch (JDDFuzzingException | ReflectionException | GeneratorException e) {
            e.printStackTrace();
            throw new ConditionObjSettingException("设置" + condition.className +
                    "对象的" + condition.fieldType +
                    "类型的字段" + condition.fieldName +
                    "时出错：" + e.getMessage());
        }

    }

    /**
     * 检查是否满足条件，如果不满足条件就取值并进行设置
     * Thinking: 这个部分需要进行记录吗？理论上并不需要
     *
     * @param seed
     * @param objToSet
     * @param condition
     * @throws ReflectionException
     * @throws GeneratorException
     */
    // Bucket nodes of JDK hash containers. Their serialized form is decided by the container's
    // writeObject and they are rebuilt by readObject, so conditions found on them (e.g. the
    // `p.next != null` loop over the temporary node list in ConcurrentHashMap.readObject) never apply
    // to the seed's table nodes. Setting them anyway corrupts the table: an empty node chained via
    // `next` is written as a null key, which ends ConcurrentHashMap.readObject early.
    private static final java.util.Set<String> JDK_BUCKET_NODE_CLASSES = new java.util.HashSet<>(java.util.Arrays.asList(
            "java.util.concurrent.ConcurrentHashMap$Node", "java.util.concurrent.ConcurrentHashMap$TreeNode",
            "java.util.concurrent.ConcurrentHashMap$TreeBin", "java.util.concurrent.ConcurrentHashMap$ForwardingNode",
            "java.util.HashMap$Node", "java.util.HashMap$TreeNode", "java.util.LinkedHashMap$Entry",
            "java.util.Hashtable$Entry", "java.util.WeakHashMap$Entry"));

    private void checkAndSetCondition(SeedWrapper seed, Object objToSet, SingletonCondition condition) throws ReflectionException, GeneratorException {
        if (objToSet != null && JDK_BUCKET_NODE_CLASSES.contains(objToSet.getClass().getName())) {
            return;
        }
        // 判断是否满足条件
        if (!isConditionSatisfied(objToSet, condition.fieldName, condition.conditionType, condition.baseLineValue)) {
            // 不满足条件，则设置对应的值
            LogUtils.debug2Log(iocdHash + "中ClassId为" + condition.classId + "的类"
                    + objToSet.getClass().getName() + "的字段"
                    + condition.fieldName + "不满足条件"
                    + condition.conditionType + " "
                    + condition.baseLineValue + "，进行设置");
            Object valueToSet = genSatisfiedObjWithCondition(seed, condition);
            java.lang.reflect.Field condFld = ReflectionUtils.getField(objToSet.getClass(), condition.fieldName);
            if (!ReflectionUtils.isAssignableToField(condFld, valueToSet)) {
                // The static analysis attached this condition to a field it does not fit (e.g. an int comparison
                // recorded on a Set field), so it cannot be satisfied. Skip this one: throwing aborted the loop
                // over all the conditions of the seed, and none of the remaining ones was set.
                LogUtils.warn("In `checkAndSetCondition`: skip the condition on " + objToSet.getClass().getName() + "#"
                        + condition.fieldName + " (field type " + condFld.getType().getName() + "): its value is a "
                        + (valueToSet == null ? "null" : valueToSet.getClass().getName())
                        + ", maybe the inaccurancy of the static analysis.");
                return;
            }
            ReflectionUtils.setFieldValue(objToSet, condition.fieldName, valueToSet);
        }
    }

    /**
     * 用于设置没有进行过处理的Condition
     *
     * @param seed
     * @throws ConditionObjSettingException
     */
    public void doNonBscConditionObjsSetting(SeedWrapper seed, LinkedHashSet<Integer> nonBscConditionIndexSet, IntWrapper indexOfSeq) throws ConditionObjSettingException {
        SingletonCondition condition = null;
        try {
            for (int index : nonBscConditionIndexSet) {
                // 通过随机的方式获取需要进行设置的Condition
                int shuffleIndex = this.shuffleConditionIndexSet(nonBscConditionIndexSet);
                while (seed.isMutatedConditionSeq(indexOfSeq.value, shuffleIndex)) {
                    shuffleIndex = this.shuffleConditionIndexSet(nonBscConditionIndexSet);
                }
                Pair<Integer, SingletonCondition> conditionPair = this.conditionObjsList.get(index);
                condition = conditionPair.getValue();
                // Thinking: classId为什么不准？
                int clzId = condition.classId;
                String clzName = condition.className;
                // Thinking: 是否需要考虑设置Immutable位置
                if (ValidatorGlobalDataContainer.isCollisionClassWrapper(seed.iocdHash, clzName, clzId) == 0) {
                    String realClzNameKey1 = FuzzUtils.getRealClassNameKey(clzId, "C1", clzName);
                    String realClzNameKey2 = FuzzUtils.getRealClassNameKey(clzId, "C2", clzName);
                    Object conditionRelatedObj1 = seed.getObjFromInjectionObjByName(realClzNameKey1);
                    Object conditionRelatedObj2 = seed.getObjFromInjectionObjByName(realClzNameKey2);
                    checkAndSetCondition(seed, conditionRelatedObj1, condition);
                    checkAndSetCondition(seed, conditionRelatedObj2, condition);
                } else {
                    String realClzNameKey = FuzzUtils.getRealClassNameKey(clzId, null, clzName);
                    Object conditionRelatedObj = seed.getObjFromInjectionObjByName(realClzNameKey);
                    checkAndSetCondition(seed, conditionRelatedObj, condition);
                }
            }

        } catch (JDDFuzzingException | GeneratorException | ReflectionException e) {
            throw new ConditionObjSettingException("设置" + condition.className +
                    "对象的" + condition.fieldType +
                    "类型的字段" + condition.fieldName +
                    "时出错：" + e.getMessage());
        }

    }

    public LinkedHashSet<Integer> getMutationNonConditionSeq (int arrivedConditionHash, IntWrapper mutationIndex) {
        if (arrivedConditionHash == -1) {
            int index0 = this.bscConditionHashSegIndexMap.values().iterator().next();
            mutationIndex = new IntWrapper(index0);
            return this.noneBscConditionObjsSegMap.get(index0);
        } else {
            int index1 = this.bscConditionHashSegIndexMap.get(arrivedConditionHash) + 1;
            mutationIndex = new IntWrapper(index1);
            LinkedHashSet<Integer> nonBscConditionIndexSet = this.noneBscConditionObjsSegMap.get(index1);
            if (nonBscConditionIndexSet == null) {
                return new LinkedHashSet<>();
            } else {
                return nonBscConditionIndexSet;
            }
        }
    }

    private int shuffleConditionIndexSet(LinkedHashSet<Integer> conditionIndexSet) {
        int res = 0;
        for (int i = 0; i < conditionIndexSet.size(); i++) {
            int ifMutate = CommonUtils.generateRandomInt(0, 1);
            res |= (ifMutate << i);
        }
        return res;
    }

    /**
     * 生成一个可以满足条件的对象
     *
     * @return
     * @throws GeneratorException
     * @throws ReflectionException
     */
    private Object genSatisfiedObjWithCondition(SeedWrapper seed, SingletonCondition condition) throws GeneratorException, ReflectionException {

        ConditionTypeEnum.ConditionType type = condition.conditionType;
        Object baseLineObj = condition.baseLineValue;
        String fldType = condition.fieldType;
        Object res = null;
        if (type.equals(ConditionTypeEnum.ConditionType.NULL)) {
            res = null;
        } else if (type.equals(ConditionTypeEnum.ConditionType.NOT_NULL)) {
            if (fldType.equals("java.lang.Object")) {
                res = new OracleClassA();
            } else {
                res = SeedGenerator.createObjectWithReflection(fldType, null);
            }
        } else if (type.equals(ConditionTypeEnum.ConditionType.E_INT)) {
            // in soot ir, the boolean would be represented as int
            // so we need to double check with the field type
            if (fldType.equals("boolean")) {
                Integer baseLineInt = (Integer) baseLineObj;
                if (baseLineInt == 1) {
                    res = true;
                } else {
                    res = false;
                }
            } else {
                res = SeedGenerator.createObjectWithReflection(fldType, null);
                res = baseLineObj;
            }
        } else if (type.equals(ConditionTypeEnum.ConditionType.NE_INT)) {
            if (fldType.equals("boolean")) {
                Integer baseLineInt = (Integer) baseLineObj;
                if (baseLineInt == 1) {
                    res = false;
                } else {
                    res = true;
                }
            } else 
            if (CommonUtils.generateRandomBoolean()) {
                res = CommonUtils.generateRandomInt((int) baseLineObj - 10, (int) baseLineObj - 1);
            } else {
                res = CommonUtils.generateRandomInt((int) baseLineObj + 1, (int) baseLineObj + 10);
            }
        } else if (type.equals(ConditionTypeEnum.ConditionType.G_INT)) {
            res = CommonUtils.generateRandomInt((int) baseLineObj + 1, (int) baseLineObj + 10);
        } else if (type.equals(ConditionTypeEnum.ConditionType.GE_INT)) {
            res = CommonUtils.generateRandomInt((int) baseLineObj, (int) baseLineObj + 10);
        } else if (type.equals(ConditionTypeEnum.ConditionType.L_INT)) {
            res = CommonUtils.generateRandomInt((int) baseLineObj - 10, (int) baseLineObj - 1);
        } else if (type.equals(ConditionTypeEnum.ConditionType.LE_INT)) {
            res = CommonUtils.generateRandomInt((int) baseLineObj - 10, (int) baseLineObj);
        } else if (type.equals(ConditionTypeEnum.ConditionType.NOT_SPEC_REF)) {
            if (fldType.equals("java.lang.Object")) {
                res = new OracleClassA();
            } else {
                res = SeedGenerator.createObjectWithReflection(fldType, null);
            }
        } else if (type.equals(ConditionTypeEnum.ConditionType.SPEC_REF)) {
            String className = ((Class<?>) baseLineObj).getName();
            try {
                if (ValidatorGlobalDataContainer.isCollisionClassWrapper(iocdHash, className, condition.classId) == 0) {
                    String colTag = "";
                    // Thinking: 是否真的不知道应该设置什么？
                    if (CommonUtils.generateRandomBoolean()) {
                        colTag = "C1";
                    } else {
                        colTag = "C2";
                    }
                    String finalClzName = FuzzUtils.getRealClassNameKey(condition.classId, colTag, className);
                    res = seed.getObjFromInjectionObjByName(finalClzName);
                } else {
                    res = seed.getObjFromInjectionObjByName(FuzzUtils.getRealClassNameKey(condition.classId, null, className));
                }
            } catch (JDDFuzzingException e) {
                res = SeedGenerator.createObjectWithReflection(className, null);
            }
        }
        return res;
    }

    /**
     * 判断是否满足条件
     *
     * @param obj
     * @param fldName
     * @param conditionType
     * @param relatedObj
     * @return
     * @throws ReflectionException
     */
    private boolean isConditionSatisfied(Object obj, String fldName, ConditionTypeEnum.ConditionType conditionType, Object relatedObj) throws ReflectionException {
        Object fldObj = ReflectionUtils.getFieldValue(obj, fldName);
        if (fldObj == null) return false;
        if (conditionType.equals(ConditionTypeEnum.ConditionType.NULL)) {
            if (fldObj != relatedObj) {
                return false;
            }
        } else if (conditionType.equals(ConditionTypeEnum.ConditionType.NOT_NULL)) {
            if (fldObj == relatedObj) {
                return false;
            }
        } else if (conditionType.equals(ConditionTypeEnum.ConditionType.E_INT)) {
            if (fldObj != relatedObj) {
                return false;
            }
        } else if (conditionType.equals(ConditionTypeEnum.ConditionType.NE_INT)) {
            if (fldObj == relatedObj) {
                return false;
            }
        } else if (conditionType.equals(ConditionTypeEnum.ConditionType.G_INT)) {
            if ((int) fldObj <= (int) relatedObj) {
                return false;
            }
        } else if (conditionType.equals(ConditionTypeEnum.ConditionType.GE_INT)) {
            if ((int) fldObj < (int) relatedObj) {
                return false;
            }
        } else if (conditionType.equals(ConditionTypeEnum.ConditionType.L_INT)) {
            if ((int) fldObj >= (int) relatedObj) {
                return false;
            }
        } else if (conditionType.equals(ConditionTypeEnum.ConditionType.LE_INT)) {
            if ((int) fldObj > (int) relatedObj) {
                return false;
            }
        } else if (conditionType.equals(ConditionTypeEnum.ConditionType.SPEC_REF)) {
            if (relatedObj instanceof String) {
                // Array-type handling 
                String strRelatedObj = (String) relatedObj;
                Class<?> fldObject = fldObj.getClass();
                if (!fldObject.isArray()) { 
                    return false;
                } else {
                    Class<?> fldComponentType = fldObject.getComponentType();
                    if (fldComponentType.getName().equals(strRelatedObj.substring(0, strRelatedObj.length() - 2))) {
                        return true;
                    } else {
                        return false;
                    }
                }
            } else {
                // Class-type handling
                Class<?> clz = (Class<?>) relatedObj;
                if (!clz.isAssignableFrom(fldObj.getClass())) {
                    return false;
                }
            }
        } else if (conditionType.equals(ConditionTypeEnum.ConditionType.NOT_SPEC_REF)) {
            if (relatedObj instanceof String) {
                // Array-type handling 
                String strRelatedObj = (String) relatedObj;
                Class<?> fldObject = fldObj.getClass();
                if (!fldObject.isArray()) {
                    return true;
                } else {
                    Class<?> fldComponentType = fldObject.getComponentType();
                    if (fldComponentType.getName().equals(strRelatedObj.substring(0, strRelatedObj.length() - 2))) {
                        return false;
                    } else {
                        return true;
                    }
                }
            } else {
                // Class-type handling
                Class<?> clz = (Class<?>) relatedObj;
                if (clz.isAssignableFrom(fldObj.getClass())) {
                    return false;
                }
            }  
        }

        return true;
    }

}
