package cn.edu.fudan.data.distance;

import cn.edu.fudan.container.GlobalDataContainer;

import cn.edu.fudan.data.distance.analyzer.ControlFlowEdgeAnalyzer;
import cn.edu.fudan.data.distance.analyzer.ControlFlowEdgeAnalyzer2;
import cn.edu.fudan.data.distance.data.*;
import cn.edu.fudan.exception.inst.BuildCFGException;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.ConfigUtils;
import cn.edu.fudan.utils.LogUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;

import java.io.IOException;
import java.util.*;

public abstract class GraphUtils {

    @Deprecated
    public static final boolean IS_DEBUG = false;

    private static HashSet<String> needClzPath = new HashSet<>();

    // 构建某一个特定的CFG
    public static CFG buildIntraCFG(String clzName, String mtdName, String mtdDesc, int index, LinkedList<String> gadgetClzMtdWithSigSet) throws BuildCFGException {

        // 获取类的字节码
        byte[] bytes = null;
        try {
            if (clzName.contains("'")) {
                clzName = clzName.replace("'", "");
            }
            bytes = ClassUtils.getSpecificClassByteCodes(clzName, ConfigUtils.analysisClzPath);
        } catch (IOException e) {
            e.printStackTrace();
            LogUtils.error("读取类" + clzName + "字节码时出错");
            throw new BuildCFGException("读取类" + clzName + "字节码时出错", e);
        }

        if (bytes == null) {
            throw new IllegalArgumentException("Could not find class:" + clzName);
        }

        // 将类转换为控制流图
        // 拿到MethodNode，从而对字节码进行分析
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        int parsingOptions = ClassReader.SKIP_FRAMES;
        cr.accept(cn, parsingOptions);
        MethodNode targetNode = null;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(mtdName) && mn.desc.equals(mtdDesc)) {
                targetNode = mn;
                break;
            }
        }
        if (targetNode == null) {
            LogUtils.error("Can not find " + clzName + " method: " + mtdName + " with desc: " + mtdDesc);
            throw new BuildCFGException("Can not find " + clzName + " method: " + mtdName + " with desc: " + mtdDesc);
        }

        // 构造基本块
        InsnBlock[] blocks;
        try {
            ControlFlowEdgeAnalyzer<BasicValue> analyzer = new ControlFlowEdgeAnalyzer2<>(new BasicInterpreter());
            analyzer.analyze(cn.name, targetNode);
            // 拿到所有的分好组的基本块
            blocks = analyzer.getBlocks();
        } catch (AnalyzerException e) {
            throw new BuildCFGException("构建CFG时出错", e);
        }

        // Debug模式下，图输出
        if (GlobalDataContainer.isDebug && IS_DEBUG) {
            TextGraph textGraph = new TextGraph(blocks);
            textGraph.draw(0, 0);
        }

        // 构造cfg
        CFG graph = new CFG();
        String nextMtdNameWithDesc = gadgetClzMtdWithSigSet.get(index + 1);
        String nextMtdName = nextMtdNameWithDesc.split("#")[1];
        nextMtdName = nextMtdName.split(":")[0].replace("()", "");
        graph.nextSinkMethodName = nextMtdName;
        // 首先构建所有的BasicBlock
        for (InsnBlock ib : blocks) {
            graph.createAndAddNewBasicBlock(ib, false);
            // BasicBlock bb = graph.allBBs.get(ib.ibId);
            // 构造边。无论是顺序执行的下一条还是jump，都是边
            for (InsnBlock nextIb : ib.nextBlockList) {
                // 对于每个后继，都需要创建BB
                graph.createAndAddNewBasicBlock(nextIb, true);
                // 创建并添加新边
                graph.createAndAddNewEdge(ib.ibId, nextIb.ibId);
            }
            for (InsnBlock jumpIb : ib.jumpBlockList) {
                // 对于每个后继，都需要创建BB
                graph.createAndAddNewBasicBlock(jumpIb, true);
                // 创建并添加新边
                graph.createAndAddNewEdge(ib.ibId, jumpIb.ibId);
            }
        }
        return graph;
    }



    // 功能方法，用于计算过程间BB到sink的距离
    public static void doInterBasicBlockDistanceCalculation(LinkedList<CFG> cfgList) {

        // 对应最后一个CFG来说，他的intraDistance就是interDistance
        CFG lastCFG = cfgList.get(cfgList.size() - 1);
        for (BasicBlock bb : lastCFG.allBBs.values()) {
            bb.interDistance = bb.intraDistance;
        }
        // 更新剩下的CFG中节点的interDistance
        for (int i = cfgList.size() - 2; i >= 0; i--) {
            CFG crtCFG = cfgList.get(i);
            // 获取下一个CFG的entry bb到target bb的距离
            CFG nextCFG = cfgList.get(i + 1);
            double nextEntryDistance = nextCFG.allBBs.get(nextCFG.entryNodeId).interDistance;
            for (BasicBlock bb : crtCFG.allBBs.values()) {
                bb.interDistance = bb.intraDistance + nextEntryDistance;
            }
        }

        if (GlobalDataContainer.isDebug) {
            for (CFG cfg : cfgList) {
                LogUtils.debug2Log("---------------------------------------------------------------------");
                for (BasicBlock bb : cfg.allBBs.values()) {
                    LogUtils.debug2Log(bb.bbId + " => " + bb.interDistance);
                }
                LogUtils.debug2Log("---------------------------------------------------------------------");
            }
        }

    }

    // 入口点BB的距离就是最远的距离
    public static double getEntryBBDistance(LinkedList<CFG> cfgList) {
        CFG entryCFG = cfgList.get(0);
        BasicBlock entryBB = entryCFG.allBBs.get(entryCFG.entryNodeId);
        return entryBB.interDistance;
    }

    public static void breadthFirstTraversal(CFG cfg) {
        // Set to keep track of visited basic blocks
        Set<Integer> visited = new HashSet<>();

        // Queue to store the basic blocks to be processed
        Queue<BasicBlock> queue = new LinkedList<>();

        // Add the entry basic block to the queue
        BasicBlock entryBlock = cfg.allBBs.get(0);
        queue.add(entryBlock);
        visited.add(entryBlock.bbId);

        // Perform breadth-first traversal
        while (!queue.isEmpty()) {
            BasicBlock currentBlock = queue.poll();

            // Process the current basic block
            processBasicBlock(currentBlock);
            LogUtils.debug2Log(String.valueOf(currentBlock.bbId));
            if (currentBlock.isRet) {
                LogUtils.debug2Log("[RET]");
            }
            if (currentBlock.isEntry) {
                LogUtils.debug2Log("[ENTRY]");
            }
            // Get the outgoing edges of the current basic block
            Set<CFGEdge> outEdges = currentBlock.allOutEdges;
            LogUtils.debug2Log("--sons-->");
            // Visit the target basic blocks of the outgoing edges
            for (CFGEdge edge : outEdges) {
                BasicBlock targetBlock = cfg.allBBs.get(edge.destId);
                LogUtils.debug2Log(targetBlock.bbId + "   ");
                if (targetBlock.isRet) {
                    LogUtils.debug2Log("[RET]");
                }
                if (targetBlock.isEntry) {
                    LogUtils.debug2Log("[ENTRY]");
                }
                if (targetBlock.isSink) {
                    LogUtils.debug2Log("[SINK]");
                }
                if (!visited.contains(targetBlock.bbId)) {
                    queue.add(targetBlock);
                    visited.add(targetBlock.bbId);
                }
            }
        }
    }

    private static void processBasicBlock(BasicBlock basicBlock) {
        // NOTICE: Implement your logic to process the basic block
    }

}
