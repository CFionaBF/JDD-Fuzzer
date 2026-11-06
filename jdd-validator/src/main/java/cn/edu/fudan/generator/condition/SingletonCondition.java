package cn.edu.fudan.generator.condition;

/**
 * SingletonCondition用于封装一个Condition包括
 *  - Condition类型
 *  - 具体的相关类名
 *  - 具体的类名中的字段名
 *  - 应当被设置的值
 */
public class SingletonCondition {
    public boolean isBasic;
    public ConditionTypeEnum.ConditionType conditionType;
    public int classId;
    public String className;
    public String fieldType;
    public String fieldName;
    public Object baseLineValue;
}
