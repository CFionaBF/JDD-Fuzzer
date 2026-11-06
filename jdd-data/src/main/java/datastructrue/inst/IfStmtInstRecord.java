package datastructrue.inst;


import cn.edu.fudan.utils.LogUtils;

import java.util.HashSet;

public class IfStmtInstRecord {
    public String className;    //  该条件分支所属的类名
    public String methodSubSig; // 该条件分支所属方法sub签名
    public String methodName;
    public Integer lineNumber;  // 在源代码文件中的位置
    public boolean basic = false;       // 是否为 Basic Condition
    public Integer hashCode;    // 用来标记对应哪个 ConditionRecord
    public HashSet<Integer> successor; // 该条件分支的后继
    public Integer basicSuccessor; // 如果是 Basic Condition，会记录后续应该进入的后继语句位置
//    public HashSet<Integer> precursor; // 该条件分支的前继


    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodSubSig() {
        return methodSubSig;
    }

    public void setMethodSubSig(String methodSubSig) {
        this.methodSubSig = methodSubSig;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(Integer lineNumber) {
        this.lineNumber = lineNumber;
    }

    public boolean isBasic() {
        return basic;
    }

    public void setBasic(boolean basic) {
        this.basic = basic;
    }

    public Integer getHashCode() {
        return hashCode;
    }

    public void setHashCode(Integer hashCode) {
        this.hashCode = hashCode;
    }

    public HashSet<Integer> getSuccessor() {
        return successor;
    }

    public void setSuccessor(HashSet<Integer> successor) {
        this.successor = successor;
    }

    public Integer getBasicSuccessor() {
        if (basicSuccessor == null) {
            return -1;
        }
        return basicSuccessor;
    }

    public void setBasicSuccessor(Integer basicSuccessor) {
        this.basicSuccessor = basicSuccessor;
    }

    public int getInstLineNum() {
        int res = 65535;
        for (int i : successor) {
            if (i > lineNumber && i < res) {
                res = lineNumber;
            }
        }
        // Thinking: 看起来有些多此一举
        for (int i : successor) {
            if (i == lineNumber) {
                res = lineNumber;
            }
        }
        if (res == 65535) {
            LogUtils.warn("getInstLineNum Error, " +
                    "cannot find the proper line number. " +
                    "Using current one. Current InstNode: " + this.toString());
            // System.exit(0);
            res = lineNumber;
        }
        return res;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("className: ").append(className).append("\n");
        sb.append("methodSubSig: ").append(methodSubSig).append("\n");
        sb.append("methodName: ").append(methodName).append("\n");
        sb.append("lineNumber: ").append(lineNumber).append("\n");
        sb.append("basic: ").append(basic).append("\n");
        sb.append("hashCode: ").append(hashCode).append("\n");
        sb.append("successor: ").append(successor).append("\n");
        sb.append("basicSuccessor: ").append(basicSuccessor).append("\n");
        return sb.toString();
    }
}
