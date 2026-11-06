package cn.edu.fudan.generator.fielddependency;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import cn.edu.fudan.datastructure.OracleClassA;
import cn.edu.fudan.exception.validator.GeneratorException;
import cn.edu.fudan.generator.seed.SeedGenerator;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.FuzzUtils;
import cn.edu.fudan.utils.LogUtils;
import datastructrue.Pair;
import datastructrue.iocd.markers.DependenceType;
import datastructrue.iocd.unit.DependenceRecord;
import datastructrue.iocd.unit.FieldRecord;

public class FieldDependencyGenerator {
    
    /**
     * Generate field dependency object
     * 
     * @param depRecords dependencyRecords from iocd
     * @param depObjMap results
     * @param iocdHash
     */
    public static void genFieldDependencyObj(List<DependenceRecord> depRecords, HashMap<Integer, Pair<Object, Object>> depObjMap, int iocdHash) {
        int index = 0;
        for (DependenceRecord depRcd : depRecords) {
            DependenceType depType = depRcd.getType();
            Pair<Object, Object> resPair = new Pair<Object, Object>(null, null);
            switch (depType) {
                case ASSIGNED:
                    resPair = handleAssigned(depRcd, iocdHash);
                    break;
                case ARRAY_ELEMENT:
                    resPair = handleArrayElement(depRcd, iocdHash);
                    break;
                case ARRAY_SIZE:
                    // do something
                    break;
                case CLASS_METHOD:
                    // do something
                    break;
                case CLASS_NAME:
                    resPair = handleClassName(depRcd, iocdHash);
                    break;
                case METHOD_NAME:
                    // do something
                    break;
                default:
                    break;
            }
            if (resPair.getKey() != null && resPair.getValue() != null) depObjMap.put(index, resPair);
            index++;
        }
    }

    private static Pair<Object, Object> handleAssigned(DependenceRecord depRcd, int iocdHash) {
        FieldRecord leftFieldRcd = depRcd.leftRecordF;
        FieldRecord rightFieldRcd = depRcd.rightRecordF;

        String leftFieldType = leftFieldRcd.getFieldType();
        String rightFieldType = rightFieldRcd.getFieldType();

        Pair<Object, Object> res = new Pair<Object, Object>(null, null);

        // check if type is matched
        if (!leftFieldType.equals(rightFieldType) 
                && !leftFieldType.equals("java.lang.Object") 
                && !ClassUtils.isSubOrImplClass(leftFieldType, rightFieldType)) {
            LogUtils.warn("In `handleAssigned`: type unmatched: " + leftFieldType + " and " + rightFieldType);
            return res;
        }

        // generate obj
        try {
            Object rightFieldObj = SeedGenerator.generateObjectByName(rightFieldType, GlobalDataContainer.getSerializeProtolByIOCDHash(iocdHash));
            // THINKING: should we generate leftFieldObj?
            res.setKey(rightFieldObj);
            res.setValue(rightFieldObj);
        } catch (GeneratorException e) {
            LogUtils.warn("In `handleAssigned`: can not generate obj for " + rightFieldType);
        }
        
        return res;
    }

    private static Pair<Object, Object> handleArrayElement(DependenceRecord depRcd, int iocdHash) {
        FieldRecord leftFieldRcd = depRcd.leftRecordF;
        FieldRecord rightFieldRcd = depRcd.rightRecordF;

        String leftFieldType = leftFieldRcd.getFieldType();
        String rightFieldType = rightFieldRcd.getFieldType();

        Object leftFieldObj = null;
        Object rightFieldObj = null;

        Pair<Object, Object> resPair = new Pair<>(null, null);

        if (leftFieldType.equals("java.util.Map")) {
            // we use HashMap here
            leftFieldObj = new HashMap<Object, Object>();
            if (rightFieldType.contains("[]")) {
                if (FuzzUtils.isSpecialFldInFld(rightFieldType)) {
                    try {
                        rightFieldObj = SeedGenerator.createObjectWithReflection(rightFieldType, GlobalDataContainer.getSerializeProtolByIOCDHash(iocdHash));
                        String fldName = FieldDependencyUtils.getDeepestFieldName(rightFieldRcd);
                        Object depKey = SeedGenerator.genAndSetSpecialCollectionFieldConcretely(rightFieldObj, fldName);
                        if (depKey != null && !depKey.equals("HN13-INVALID")) {
                            ((Map)leftFieldObj).put(depKey + "1", new OracleClassA());
                        }
                    } catch (GeneratorException e) {
                        LogUtils.warn("In `handleArrayElement`: can not generate obj for " + rightFieldType);
                    }
                } else {
                    LogUtils.warn("In `handleArrayElement-right-field`: unsupported type: " + rightFieldType);
                }
            }
        } else {
            LogUtils.warn("In `handleArrayElement`-left-field: unsupported type: " + leftFieldType);
        }
        resPair.setKey(leftFieldObj);
        resPair.setValue(rightFieldObj);
        return resPair;
    }

    private static Pair<Object, Object> handleArraySize(DependenceRecord depRcd) {
        // do something
        return new Pair<Object,Object>(null, null);
    }

    private static Pair<Object, Object> handleClassMethod(DependenceRecord depRcd) {
        // do something
        return new Pair<Object,Object>(null, null);
    }

    /**
     * we do not generate class-name dependency object
     * 
     */
    private static Pair<Object, Object> handleClassName(DependenceRecord depRcd, int iocdHash) {
        return new Pair<Object,Object>("Dummy", "Dummy");
    }

    private static Pair<Object, Object> handleMethodName(DependenceRecord depRcd) {
        // do something
        return new Pair<Object,Object>(null, null);
    }

}
