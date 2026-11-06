package cn.edu.fudan.generator.condition;

import cn.edu.fudan.exception.validator.ConditionObjsGeneratorException;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.LogUtils;
import datastructrue.Pair;
import datastructrue.iocd.unit.ConditionRecord;
import datastructrue.iocd.unit.FieldRecord;

import java.util.*;

public class ConditionObjsGenerator {

    // condition value types genConditionObjsListAndSegInfo can turn into a SingletonCondition
    private static final Set<String> SUPPORTED_VALUE_TYPES = new HashSet<>(Arrays.asList(
            "null_type", "int", "class soot.RefType", "class soot.ArrayType"));

    /**
     * 按照ConditionRecord的顺序，生成所有的SingletonCondition对象
     *
     * @param conditionRecordMap
     * @param mutatedConditionObjsMap
     * @return
     * @throws ConditionObjsGeneratorException
     */
    public static ArrayList<Pair<Integer, SingletonCondition>> genConditionObjsListAndSegInfo(
            LinkedHashMap<Integer, ConditionRecord> conditionRecordMap,
            HashMap<Integer, LinkedHashSet<Integer>> mutatedConditionObjsMap,
            HashMap<Integer, Integer> bscConditionHashSegIndexMap) throws ConditionObjsGeneratorException {

        boolean segFlag = false;
        int setIndex = 0;
        ArrayList<Pair<Integer, SingletonCondition>> resList = new ArrayList<>();
        LinkedHashSet<Integer> tempNonBscConditionObjsSeg = new LinkedHashSet<>();
        for (Map.Entry<Integer, ConditionRecord> entry : conditionRecordMap.entrySet()) {
            ConditionRecord conditionRecord = entry.getValue();
            // a condition whose value type is not supported below (e.g. java.lang.Class) is skipped, before it
            // enters the segment information: it is not set, the fuzzer may still meet it by mutation. Before,
            // it made the whole seed fail.
            Iterator<String> valueTypes = conditionRecord.getConditionValue().keySet().iterator();
            String valueType = valueTypes.hasNext() ? valueTypes.next() : "";
            if (!valueType.isEmpty() && !SUPPORTED_VALUE_TYPES.contains(valueType)) {
                LogUtils.logWarnOnce("In `genConditionObjsListAndSegInfo`: skip condition " + conditionRecord.hashCode
                        + " (unsupported value type " + valueType + ")");
                continue;
            }
            // STEP1: 记录分段信息
            if (!conditionRecord.isBasic()) {
                segFlag = true;
                tempNonBscConditionObjsSeg.add(conditionRecord.hashCode);
            } else {
                segFlag = false;
            }
            // 当遇到一个新的bsc condition时且tempNonBscConditionObjsSeg非空时，则进行一次分段
            if (!segFlag && !tempNonBscConditionObjsSeg.isEmpty()) {
                mutatedConditionObjsMap.put(setIndex, tempNonBscConditionObjsSeg);
                bscConditionHashSegIndexMap.put(conditionRecord.hashCode, setIndex); // Notice: 这个地方记录的相当于是 nb nb nb b1中b1前面的序列，变异时候应该考虑setIndex + 1
                setIndex++;
                tempNonBscConditionObjsSeg = new LinkedHashSet<>();
            }

            // STEP2: 生成Condition节点并记录conditionObjsList
            // Thinking: ConditionValue被设计为LinkedHashMap，是否仍然有必要
            LinkedHashMap<String, String> conditionValue = conditionRecord.getConditionValue();
            String conditionValueKey, conditionValueValue;
            try {
                conditionValueKey = conditionValue.keySet().iterator().next();
                conditionValueValue = conditionValue.values().iterator().next();
            } catch (NoSuchElementException e) {
                throw new ConditionObjsGeneratorException("In `genConditionObjsListAndSegInfo`: ConditionValue is empty");
            }
            SingletonCondition condition = new SingletonCondition();
            // 确定是否是basic类型
            condition.isBasic = conditionRecord.isBasic();
            // 确定Condition类型
            ConditionTypeEnum.ConditionType conditionType = null;
            if (conditionValueKey.equals("null_type")) {
                if (conditionRecord.comparator.equals("==")) {
                    conditionType = ConditionTypeEnum.ConditionType.NULL;
                } else if (conditionRecord.comparator.equals("!=")) {
                    conditionType = ConditionTypeEnum.ConditionType.NOT_NULL;
                } else {
                    throw new ConditionObjsGeneratorException("Invalid Comparator:" + conditionRecord.comparator);
                }
            } else if (conditionValueKey.equals("int")) {
                if (conditionRecord.comparator.equals(">=")) {
                    conditionType = ConditionTypeEnum.ConditionType.GE_INT;
                } else if (conditionRecord.comparator.equals(">")) {
                    conditionType = ConditionTypeEnum.ConditionType.G_INT;
                } else if (conditionRecord.comparator.equals("<=")) {
                    conditionType = ConditionTypeEnum.ConditionType.LE_INT;
                } else if (conditionRecord.comparator.equals("<")) {
                    conditionType = ConditionTypeEnum.ConditionType.L_INT;
                } else if (conditionRecord.comparator.equals("==")) {
                    conditionType = ConditionTypeEnum.ConditionType.E_INT;
                } else if (conditionRecord.comparator.equals("!=")) {
                    conditionType = ConditionTypeEnum.ConditionType.NE_INT;
                } else {
                    throw new ConditionObjsGeneratorException("Invalid Comparator:" + conditionRecord.comparator);
                }
            } else if (conditionValueKey.equals("class soot.RefType") 
                        || conditionValueKey.equals("class soot.ArrayType")) {
                if (conditionRecord.comparator.equals("=="))
                    conditionType = ConditionTypeEnum.ConditionType.SPEC_REF;
                else if (conditionRecord.comparator.equals("!="))
                    conditionType = ConditionTypeEnum.ConditionType.NOT_SPEC_REF;
                else
                    throw new ConditionObjsGeneratorException("Invalid Comparator:" + conditionRecord.comparator);
            } else {
                throw new ConditionObjsGeneratorException("Unsupport Condition type:" + conditionValueKey);
            }
            condition.conditionType = conditionType;
            // 确定Condition的关联Value的所在类、字段类型和字段名
            LinkedHashMap<String, FieldRecord> conditionName = conditionRecord.conditionName;
            //  String conditionNameKey = conditionName.keySet().iterator().next();
            FieldRecord conditionNameValue  = conditionName.values().iterator().next();
            condition.classId = conditionNameValue.classId;
            condition.className = conditionNameValue.classBelongTo;
            condition.fieldType = conditionNameValue.fieldType;
            condition.fieldName = conditionNameValue.fieldName;
            // 确定关联的field的值
            if (conditionValueKey.equals("null_type")) {
                condition.baseLineValue = null;
            } else if (conditionValueKey.equals("int")) {
                condition.baseLineValue = Integer.parseInt(conditionValueValue);
            } else if (conditionValueKey.equals("class soot.RefType")) {
                condition.baseLineValue = ClassUtils.getClassByName(conditionValueValue);
            } else if (conditionValueKey.equals("class soot.ArrayType")) {
                // for array type, we record its String format.
                // and then handle it in the setting or mutation process
                condition.baseLineValue = conditionValueValue;
            } else {
                throw new ConditionObjsGeneratorException("Unsupport Condition type:" + conditionValueKey);
            }
            resList.add(new Pair<>(entry.getKey(), condition));
        }
        return resList;
    }


}
