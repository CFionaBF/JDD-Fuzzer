package cn.edu.fudan.generator.fielddependency;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import cn.edu.fudan.datastructure.OracleClassA;
import cn.edu.fudan.exception.validator.ObjectNotFoundException;
import cn.edu.fudan.exception.validator.ReflectionException;
import cn.edu.fudan.generator.seed.SeedWrapper;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.FuzzUtils;
import cn.edu.fudan.utils.LogUtils;
import cn.edu.fudan.utils.ReflectionUtils;
import datastructrue.Pair;
import datastructrue.iocd.IOCD;
import datastructrue.iocd.markers.DependenceType;
import datastructrue.iocd.unit.DependenceRecord;
import datastructrue.iocd.unit.FieldRecord;

/**
 * Field dependecy is the strongest one, make sure there is one check before
 * execute a seed.
 */
public class FieldDependencyWrapper {

    public int iocdHash;

    List<DependenceRecord> dependenceRecordList;
    HashMap<Integer, Pair<Object, Object>> dependenceObjectMap; // left field obj -> right field obj

    public FieldDependencyWrapper(IOCD iocd) {
        this.iocdHash = iocd.hashCode;
        // get related dependency records
        Set<DependenceRecord> fieldDepSet = iocd.getDependenceRecords();
        if (fieldDepSet == null) {
            LogUtils.warn("In `FieldDependencyWrapper.<init>`: " + iocdHash + " has no dependence record.");
        } else {
            dependenceRecordList = new ArrayList<>();
            dependenceRecordList = new ArrayList<>(fieldDepSet);
        }
        // generate dependence object list
        this.dependenceObjectMap = new HashMap<>();
        FieldDependencyGenerator.genFieldDependencyObj(dependenceRecordList, dependenceObjectMap, iocdHash);
    }

    /*
     * check if seed satisfied with the dependence record
     * 
     */
    public boolean isDependencySatisfied(SeedWrapper seed, DependenceRecord dependenceRecord) {
        DependenceType depType = dependenceRecord.getType();
        FieldRecord leftFiled = dependenceRecord.leftRecordF;
        FieldRecord rightFiled = dependenceRecord.rightRecordF;

        DependencyChecker checker = new DependencyChecker(this.iocdHash, depType);

        boolean isSatisfied = false;
        isSatisfied = checker.handleAssign(seed, leftFiled, rightFiled)
                || checker.handleArrayElement(seed, leftFiled, rightFiled)
                || checker.handleArraySize(seed, leftFiled, rightFiled)
                || checker.handleClassMethod(seed, leftFiled, rightFiled)
                || checker.handleClassName(seed, leftFiled, rightFiled)
                || checker.handleMethodName(seed, leftFiled, rightFiled);

        return isSatisfied;
    }

    /**
     * set the field value with dependency for the seed
     * 
     * @param seed
     */
    public void doDependecyFieldSetting(SeedWrapper seed) {
        for (Map.Entry<Integer, Pair<Object, Object>> entry : dependenceObjectMap.entrySet()) {
            // find related field record by index
            int index = entry.getKey();
            DependenceRecord depRecord = dependenceRecordList.get(index);
            
            // if the dependency has been satisfied, then return
            if (isDependencySatisfied(seed, depRecord)) continue;
            FieldRecord leftField = depRecord.leftRecordF;
            FieldRecord rightField = depRecord.rightRecordF;

            Pair<Object, Object> fieldObjects = entry.getValue();

            // if the container class is a collision class(same class situation), we should
            // handle it specially
            try {
                if (ValidatorGlobalDataContainer.isCollisionClassWrapper(seed.iocdHash, leftField.classBelongTo, leftField.classId) == 0) {
                    Pair<Object, Object> collisionObjPair = seed.getSameNameCollisionObjectPair(leftField.classBelongTo, leftField.classId, leftField.classId);
                    if (leftField.classBelongTo.equals(leftField.classBelongTo)) {
                        // situation1: both left and right field are collision-related
                        ReflectionUtils.setFieldValue(collisionObjPair.getKey(), leftField.fieldName, fieldObjects.getKey());
                        ReflectionUtils.setFieldValue(collisionObjPair.getValue(), rightField.fieldName, fieldObjects.getValue());

                        FuzzUtils.refineSpecialCollectionObject(collisionObjPair.getKey());
                        FuzzUtils.refineSpecialCollectionObject(collisionObjPair.getValue());
                        return;
                    } else {
                        try {
                            // set the field value with the same field name in different collision object
                            // situation2: left field is collision-related, right field is not
                            ReflectionUtils.setFieldValue(collisionObjPair.getKey(), leftField.fieldName, fieldObjects.getKey());
                            ReflectionUtils.setFieldValue(collisionObjPair.getValue(), leftField.fieldName, fieldObjects.getKey());
                            Object rightObject = seed.getObjFromInjectionObjByName(FuzzUtils.getRealClassNameKey(rightField.classId, null, rightField.classBelongTo));
                            ReflectionUtils.setFieldValue(rightObject, rightField.fieldName, fieldObjects.getValue());

                            FuzzUtils.refineSpecialCollectionObject(collisionObjPair.getKey());
                            FuzzUtils.refineSpecialCollectionObject(collisionObjPair.getValue());
                            FuzzUtils.refineSpecialCollectionObject(rightObject);
                        } catch (ObjectNotFoundException e) {
                            LogUtils.warn("In `doDependecyFieldSetting`: can not find proper object for "
                                    + rightField.classBelongTo + " with class id " + rightField.classId);
                            return;
                        }
                    }
                } else {
                    if (ValidatorGlobalDataContainer.isCollisionClassWrapper(seed.iocdHash, rightField.classBelongTo, rightField.classId) == 0) {
                        // situation3: left field is not collision-related, while right field is collision-related
                        try {
                            Pair<Object, Object> collisionObjPair = seed.getSameNameCollisionObjectPair(rightField.classBelongTo, rightField.classId, rightField.classId);
                            ReflectionUtils.setFieldValue(collisionObjPair.getKey(), rightField.fieldName, fieldObjects.getValue());
                            ReflectionUtils.setFieldValue(collisionObjPair.getValue(), rightField.fieldName, fieldObjects.getValue());
                            Object leftObject = seed.getObjFromInjectionObjByName(FuzzUtils.getRealClassNameKey(leftField.classId, null, leftField.classBelongTo));
                            ReflectionUtils.setFieldValue(leftObject, leftField.fieldName, fieldObjects.getKey());

                            FuzzUtils.refineSpecialCollectionObject(collisionObjPair.getKey());
                            FuzzUtils.refineSpecialCollectionObject(collisionObjPair.getValue());
                            FuzzUtils.refineSpecialCollectionObject(leftObject);
                        } catch (ObjectNotFoundException e) {
                            LogUtils.warn("In `doDependecyFieldSetting`: can not find proper object for "
                                    + leftField.classBelongTo + " with class id " + leftField.classId);
                            return;
                        }
                    } else {
                        // situation4: both left and right field are not collision-related
                        try {
                            Object leftObject = seed.getObjFromInjectionObjByName(FuzzUtils.getRealClassNameKey(leftField.classId, null, leftField.classBelongTo));
                            Object rightObject = seed.getObjFromInjectionObjByName(FuzzUtils.getRealClassNameKey(rightField.classId, null, rightField.classBelongTo));
                            ReflectionUtils.setFieldValue(leftObject, leftField.fieldName, fieldObjects.getKey());
                            ReflectionUtils.setFieldValue(rightObject, rightField.fieldName, fieldObjects.getValue());

                            FuzzUtils.refineSpecialCollectionObject(leftObject);
                            FuzzUtils.refineSpecialCollectionObject(rightObject);
                        } catch (ObjectNotFoundException e) {
                            LogUtils.warn("In `doDependecyFieldSetting`: can not find proper object for "
                                    + leftField.classBelongTo + " or " + rightField.classBelongTo);
                            return;
                        }
                    }
                }
            } catch (ReflectionException e) {
                LogUtils.warn("In `doDependecyFieldSetting`: can not set field value for " + leftField.fieldName
                        + " or " + rightField.fieldName + " in " + leftField.classBelongTo + " or " + rightField.classBelongTo);
            }
        }
            
    }

    private static class DependencyChecker {
        int iocdHash;
        DependenceType crtType;

        public DependencyChecker(int iocdHash, DependenceType crtType) {
            this.iocdHash = iocdHash;
            this.crtType = crtType;
        }

        /**
         * check map-key dependency and array element dependency
         *
         * @param seed
         * @param leftField
         * @param rightField
         * @return
         */
        private boolean handleArrayElement(SeedWrapper seed, FieldRecord leftField, FieldRecord rightField) {
            if (!crtType.equals(DependenceType.ARRAY_ELEMENT))
                return false;
            // check if the left field and right field are not null
            Object leftFieldObj, rightFieldObj;
            Object leftFieldValue, rightFieldValue;

            // for left field
            try {
                if (ValidatorGlobalDataContainer.isCollisionClassWrapper(this.iocdHash, leftField.classBelongTo, leftField.classId) == 0) {
                    leftFieldObj = seed.getObjFromInjectionObjByName(FuzzUtils.getRealClassNameKey(leftField.classId, "C1", leftField.classBelongTo));
                    leftFieldValue = FieldDependencyUtils.getDeepestFieldValue(leftFieldObj, leftField);
                } else {
                    leftFieldObj = seed.getObjFromInjectionObjByName(FuzzUtils.getRealClassNameKey(leftField.classId, null, leftField.classBelongTo));
                    leftFieldValue = FieldDependencyUtils.getDeepestFieldValue(leftFieldObj, leftField);
                }
                if (leftFieldValue == null) {
                    return false;
                }
            } catch (ObjectNotFoundException e) {
                LogUtils.warn("In `isDependencySatisfied`: can not get field value of " + leftField.fieldName
                        + " from object " + FuzzUtils.getRealClassNameKey(leftField.classId, "C1", leftField.classBelongTo));
                return false;
            }

            // for right field
            try {
                if (ValidatorGlobalDataContainer.isCollisionClassWrapper(this.iocdHash, rightField.classBelongTo, rightField.classId) == 0) {
                    rightFieldObj = seed.getObjFromInjectionObjByName(FuzzUtils.getRealClassNameKey(rightField.classId, "C1", rightField.classBelongTo));
                    rightFieldValue = FieldDependencyUtils.getDeepestFieldValue(rightFieldObj, rightField);
                } else {
                    rightFieldObj = seed.getObjFromInjectionObjByName(FuzzUtils.getRealClassNameKey(rightField.classId, null, rightField.classBelongTo));
                    rightFieldValue = FieldDependencyUtils.getDeepestFieldValue(rightFieldObj, rightField);
                }
                if (rightFieldValue == null) {
                    return false;
                }
            } catch (ObjectNotFoundException e) {
                LogUtils.warn("In `isDependencySatisfied`: can not get field value of " + rightField.fieldName
                        + " from object " + FuzzUtils.getRealClassNameKey(rightField.classId, "C1", rightField.classBelongTo));
                return false;
            }

            // check if left value equlas right value

            // align with generator
            if (leftFieldValue instanceof Map) {
                try {
                    if (((Map) leftFieldValue).containsKey(rightFieldValue)) {
                        return true;
                    } else {
                        return false;
                    }
                } catch (Exception e) {
                    return false;
                }
                
            }
            return false;
        }

        private boolean handleArraySize(SeedWrapper seed, FieldRecord leftField, FieldRecord rightField) {
            if (!crtType.equals(DependenceType.ARRAY_SIZE))
                return false;
            return true;
        }

        private boolean handleClassMethod(SeedWrapper seed, FieldRecord leftField, FieldRecord rightField) {
            if (!crtType.equals(DependenceType.CLASS_METHOD))
                return false;
            return true;
        }

        /** 
         * check if the `rightFieldObj` is 
         *   - the class name of `leftFieldObj`
         *   - one of the field name of `leftFieldObj`
         *   - one of the method name of `leftFieldObj`
         */
        private boolean handleClassName(SeedWrapper seed, FieldRecord leftField, FieldRecord rightField) {
            if (!crtType.equals(DependenceType.CLASS_NAME))
                return false;

            String leftFieldClzBelongTo = leftField.classBelongTo;
            String rightFieldClzBelongTo = rightField.classBelongTo;

            try {
                Object leftFieldContainer = seed.getObjFromInjectionObjByName(leftFieldClzBelongTo);
                Object rightFieldContainer = seed.getObjFromInjectionObjByName(rightFieldClzBelongTo);

                Object leftFieldObj = ReflectionUtils.getFieldValue(leftFieldContainer, leftField.fieldName);
                Object rightFieldObj = ReflectionUtils.getFieldValue(rightFieldContainer, rightField.fieldName);
                if (leftFieldObj == null && rightFieldObj == null) {
                    ReflectionUtils.setFieldValue(leftFieldContainer, leftField.fieldName, OracleClassA.class);
                    ReflectionUtils.setFieldValue(rightFieldContainer, rightField.fieldName, OracleClassA.randomProperty());
                } else if (leftFieldObj != null) {
                    Field[] fields = ReflectionUtils.getAllFields((Class)leftFieldObj);
                    while (true) {
                        Field field = CommonUtils.getRandomItemFromList(Arrays.asList(fields));
                        if (!Modifier.isStatic(field.getModifiers())) {
                            ReflectionUtils.setFieldValue(leftFieldObj, rightField.fieldName, field.getName());
                            break;
                        }
                    }
                }
                seed.immutableFieldInfoSet.add(FuzzUtils.getRealClassNameKey(leftField.classId, null, leftField.classBelongTo) + "#" + leftField.fieldName);
                seed.immutableFieldInfoSet.add(FuzzUtils.getRealClassNameKey(rightField.classId, null, rightField.classBelongTo) + "#" + rightField.fieldName);
                return true;
            } catch (ObjectNotFoundException | ReflectionException | ClassCastException e) {
                // do nothing
                return true;
            }
        }

        private boolean handleMethodName(SeedWrapper seed, FieldRecord leftField, FieldRecord rightField) {
            if (!crtType.equals(DependenceType.METHOD_NAME))
                return false;
            return true;
        }

        /**
         * check if the left field has the same value with right field
         * if the left field is assigned, the right field should be assigned too
         * 
         * @param seed
         * @param leftField
         * @param rightField
         * @return
         */
        private boolean handleAssign(SeedWrapper seed, FieldRecord leftField, FieldRecord rightField) {

            if (!crtType.equals(DependenceType.ASSIGNED))
                return false;

            if (leftField.classBelongTo.equals(rightField.classBelongTo)
                    && ValidatorGlobalDataContainer.isCollisionClassWrapper(this.iocdHash, leftField.classBelongTo, leftField.classId) == 0) {
                Pair<Object, Object> objPair = seed.getSameNameCollisionObjectPair(leftField.classBelongTo, leftField.classId, rightField.classId);
                // check if the field is mutable or not
                if (!seed.isMutatableCollisionField(leftField.classBelongTo, leftField.fieldName, leftField.classId, rightField.classId))
                    return true;
                // obtain the field concrete value
                Object leftFieldObj, rightFieldObj;
                try {
                    leftFieldObj = ReflectionUtils.getFieldValue(objPair.getKey(), leftField.fieldName);
                    rightFieldObj = ReflectionUtils.getFieldValue(objPair.getValue(), rightField.fieldName);
                } catch (Exception e) {
                    String c1ClzKey = FuzzUtils.getRealClassNameKey(leftField.classId, "C1", leftField.classBelongTo);
                    String c2ClzKey = FuzzUtils.getRealClassNameKey(rightField.classId, "C2", rightField.classBelongTo);
                    LogUtils.warn("In `isDependencySatisfied`: can not get field value of " + leftField.fieldName
                            + " or " + rightField.fieldName
                            + " from object " + c1ClzKey + " or " + c2ClzKey + ".");
                    return true;
                }
                // check if the dependency has been satisfied
                if (leftFieldObj != null && rightFieldObj != null && leftFieldObj.equals(rightFieldObj)) {
                    return true;
                } else {
                    return false;
                }
            } else {
                return true;
            }
        }
    }
}
