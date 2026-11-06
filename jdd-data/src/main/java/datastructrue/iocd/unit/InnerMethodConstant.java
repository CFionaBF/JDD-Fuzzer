package datastructrue.iocd.unit;

import datastructrue.Pair;

import java.util.HashSet;

public class InnerMethodConstant {
    public MethodRecord methodRecord;
    public HashSet<Pair<Object, ConstantRecord.constantType>> constants = new HashSet<>();
}
