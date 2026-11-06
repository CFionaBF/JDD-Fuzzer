package cn.edu.fudan.data.distance.data;

import java.util.HashSet;

public class BasicBlock {

    // 用于标识这个基本块的id
    public int bbId;
    // 用来标识这个基本块的hash
    public int bbHash;

    // 过程内Basic Block距离
    public double intraDistance;
    // 过程间Basic Bblock距离
    public double interDistance;

    // 是否是Entry
    public boolean isEntry = false;
    // 是否是返回语句
    public boolean isRet = false;
    // Thinking: 不够准确，后续应该结合行号来完成精确定位
    // 是否是Sink点，比如在该基本块中调用了最后的Sink方法Method#invoke，就认为该方法是Sink点
    public boolean isSink = false;

    // BasicBlock当中的一系列指令集合
    public InsnBlock crtInstSet;
    // 所有的出边集合
    public HashSet<CFGEdge> allOutEdges = new HashSet<>();

    public String getFirstLineNum() {
        String res = "";
        for (String line : crtInstSet.lines) {
            if (line.startsWith("LINENUM: ")) {
                res = line;
                break;
            }
        }
        return res;
    }

    public String getFirstLabelName() {
        String res = "";
        for (String line : crtInstSet.lines) {
            if (line.startsWith("LABEL-")) {
                res = line;
                break;
            }
        }
        return res;
    }

}
