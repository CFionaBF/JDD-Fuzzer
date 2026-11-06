package cn.edu.fudan.data.distance.data;

public class CFGEdge {

    // 源基本块和目标基本块的ID
    public int srcId;
    public int destId;

    // 边权重，固定为1
    public int weight = 1;

    public CFGEdge(int src, int destId) {
        this.srcId = src;
        this.destId = destId;
    }

}
