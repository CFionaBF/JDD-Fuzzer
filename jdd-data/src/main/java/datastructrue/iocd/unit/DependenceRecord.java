package datastructrue.iocd.unit;

import datastructrue.iocd.markers.DependenceType;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
/**
 * Field-dep不是强满足的条件
 * 因为没有做path-sensitive
 */
public class DependenceRecord {
    // E.g. ARRAY_SIZE: 左边是array,右边是size; Class_MethodName: 左边是class, 右边是其方法名
    // ASSIGNED_REAL: 针对writeObject中检测出的fields之间赋值和被赋值关系
    public String methodName = "";
    public DependenceType type;
    public FieldRecord leftRecordF;
    public FieldRecord rightRecordF;
}
