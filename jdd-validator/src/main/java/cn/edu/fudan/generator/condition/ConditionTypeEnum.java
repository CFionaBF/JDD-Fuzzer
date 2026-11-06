package cn.edu.fudan.generator.condition;

public class ConditionTypeEnum {
    enum ConditionType {
        NOT_NULL, // 要求不为null
        NULL, // 要求为null
        E_INT, // 等于null
        NE_INT, // 要求不等于某个特定的值
        GE_INT, // 要求大于等于某个特定的值
        LE_INT, // 要求小于等于某个特定的值
        G_INT, // 要求大于某个特定的值
        L_INT, // 要求等于某个特定的值
        SPEC_REF, // 要求是某种特定类型的对象
        NOT_SPEC_REF // 要求不是某种特定类型的对象
    }
}
