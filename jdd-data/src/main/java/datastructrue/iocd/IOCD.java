package datastructrue.iocd;

import datastructrue.Pair;
import datastructrue.iocd.unit.*;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

@Getter
@Setter
public class IOCD {
    public int hashCode;
    public boolean hashCollisionFlag;
    public String sinkType; // sink类型
    public String protocol;
    public boolean needSerialize; // 记录 gadget chain 中是否都继承了序列化接口

    // 为了提高拼接效率, 记录一些反射拼接相关的约束信息
    public boolean publicEntry;
    public String entryType = "none";

    public LinkedList<Pair<Integer, String>> gadgetCallStack;
    public LinkedList<ClassRecord> gadgetClasses = new LinkedList<>();
    public HashSet<ClassRecord> conClassNodes = new HashSet<>();
    // 对应的Condition hashCode : 是否为必要 Condition
    public LinkedHashMap<Integer, Boolean> conditionsRecords = new LinkedHashMap<>();
    public HashSet<DependenceRecord> dependenceRecords = new HashSet<>(); // 记录依赖关系
    public CollisionRecord hashCollisionRecord; // 记录哈希碰撞信息
    public HashSet<UsedSiteRecord> usedFieldsRecords = new HashSet<>();
    public HashSet<ConditionRecord> supplementConditions = new HashSet<>(); // 所有非主干的conditions

    public List<SinkRecord> sinkRecords = new ArrayList<>();


    public int getHashCollisionType() {
        if (hashCollisionRecord == null)
            return -1;
        return hashCollisionRecord.type;
    }

    public String getFirstClzName() {
        return gadgetClasses.get(0).getClassName();
    }

}