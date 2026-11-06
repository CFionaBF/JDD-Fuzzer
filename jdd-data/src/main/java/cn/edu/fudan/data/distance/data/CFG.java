package cn.edu.fudan.data.distance.data;

import java.util.HashMap;
import java.util.HashSet;

/**
 * Intra-CFG
 */
public class CFG {

    // 该CFG的序号
    public int gId;
    // 该CFG对应的方法名
    public String clzName;
    // 带签名的方法名
    public String mtdNameWithDesc;

    // 正在跑的CFG的sink方法，本质上这里的sink方法指的是方法调用
    public String nextSinkMethodName;

    // 基本块数量
    public int blockSize;
    // 边数量
    public int edgeSize;
    // 该Intra-CFG当中entry BB和sink BB的距离
    public int sinkNodeId;
    public int entryNodeId;

    // 方法起始BB的hash标识
    public int entryNodeHash;

    // 所有的边
    public HashSet<CFGEdge> allEdges = new HashSet<>();
    // 所有的基本块
    public HashMap<Integer, BasicBlock> allBBs = new HashMap<>();

    public void createAndAddNewEdge(int srcId, int destId) {
        CFGEdge edge = new CFGEdge(srcId, destId);
        this.edgeSize++;
        this.allEdges.add(edge);
        // BB中也要记录自己的出边
        BasicBlock srcBB = this.allBBs.get(srcId);
        srcBB.allOutEdges.add(edge);
    }

    public void createAndAddNewBasicBlock(InsnBlock insnBlock, boolean isAddEdgeStage) {
        // 如果之前包含，就不作处理
        if (allBBs.containsKey(insnBlock.ibId)) {
            return;
        }
        // 创建
        BasicBlock basicBlock = new BasicBlock();
        basicBlock.bbId = insnBlock.ibId;
        basicBlock.crtInstSet = insnBlock;
        if (insnBlock.ibId == 0) {
            basicBlock.isEntry = true;
            this.entryNodeId = insnBlock.ibId;
        }
        if (isSinkBasicBlock(insnBlock)) {
            basicBlock.isSink = true;
            this.sinkNodeId = insnBlock.ibId;
        }
        // NOTICE: 由于没有对异常进行处理，因此会出现游离的BasicBlock，对于这些，暂时直接返回不作处理
        if (insnBlock.nextBlockList.isEmpty()
                && insnBlock.jumpBlockList.isEmpty()) {
            // 显然，只有之前被访问过，同时还没有后继的节点才是真正的返回点
            // 或者之前没有被访问过，但是在边构建过程中被访问了
            if (basicBlock.crtInstSet.lines.contains("return")) {
                basicBlock.isRet = true;
            } else if (this.allBBs.containsKey(basicBlock.bbId) || isAddEdgeStage) {
                // 一般是抛出异常，doNothing
            } else if (basicBlock.isEntry) {
                // 这种情况说明整个方法只有一个基本块
                basicBlock.isRet = true;
            } else  {
                return;
            }
        }
        // 添加
        this.allBBs.put(insnBlock.ibId, basicBlock);
        this.blockSize++;

    }

    /**
     * 判断一个基本块是否是sink基本块
     * 对于一个CFG来说，sink基本块就是对下个方法的调用
     *
     * @param ib
     * @return
     */
    public boolean isSinkBasicBlock(InsnBlock ib) {
        for (String instruct : ib.lines) {
            if (instruct.startsWith("invoke")
                    && instruct.endsWith("." + nextSinkMethodName)) {
                return true;
            }
        }
        return false;
    }

}
