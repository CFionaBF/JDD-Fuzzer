package datastructrue.iocd.markers;

/**
 * 变异过程中出问题了，设置 *non-basic Condition来这里查找*
 * 1、报错信息
 * 2、non-basic Condition
 * 3、度量 bb + coverage
 * 不是一个"强"约束，而是在变异过程中用
 */
public enum DependenceType {
    CLASS_NAME, // 左边是Class 右边是Class的名字(1、本身名字；2、方法名字；3、Class当中某个Field名字)
    CLASS_METHOD, // 左边是Class  右边是Method
    METHOD_NAME, // 左边是Method  右边是Method的名字
    ARRAY_SIZE, // 左边是数组  右边是size的field，数组的大小应该设置在哪
    ARRAY_ELEMENT, // 右值中fldInFld中最里面的对象   是左值数组中的一个元素
    ASSIGNED // 右值赋给左值   transient类型会用
}
