package datastructrue.iocd.unit;

import datastructrue.Pair;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Objects;

@Getter
@Setter
public class ConditionRecord {

    // if( a = 1 && b = 2 )
    // Jimple IR?
    public MethodRecord methodBelongTo;
    public LinkedHashMap<String, FieldRecord> conditionName = new LinkedHashMap<>();    //  可控对象？
    public LinkedHashMap<String, String> conditionValue = new LinkedHashMap<>();   //  值
    public String comparator;
    public String ifStmt;
    public boolean basic = false;
    public int hashCode;
    public Pair<String, Integer> usedSite; // 被使用的位置信息记录
    public int type = SINGLE;


    public static int SINGLE = 0; // = and
    public static int OR = 1;
    public static int UNCONTROLLABLE =2;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConditionRecord that = (ConditionRecord) o;
        return basic == that.basic &&
                hashCode == that.hashCode &&
                type == that.type &&
                Objects.equals(methodBelongTo, that.methodBelongTo) &&
                Objects.equals(conditionName, that.conditionName) &&
                Objects.equals(conditionValue, that.conditionValue) &&
                Objects.equals(comparator, that.comparator) &&
                Objects.equals(ifStmt, that.ifStmt) &&
                Objects.equals(usedSite, that.usedSite);
    }

    @Override
    public int hashCode() {
        return Objects.hash(methodBelongTo, conditionName, conditionValue, comparator, ifStmt, basic, hashCode, usedSite, type);
    }
}
