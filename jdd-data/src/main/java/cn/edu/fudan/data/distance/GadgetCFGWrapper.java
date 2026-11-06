package cn.edu.fudan.data.distance;

import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.data.distance.data.BasicBlock;
import cn.edu.fudan.data.distance.data.CFG;
import cn.edu.fudan.data.distance.data.CFGEdge;
import cn.edu.fudan.data.distance.data.InstNode;
import cn.edu.fudan.exception.inst.BuildCFGException;
import cn.edu.fudan.exception.inst.CFGException;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.FuzzUtils;
import cn.edu.fudan.utils.LogUtils;
import datastructrue.Pair;
import datastructrue.inst.JDDFuzzRecord;
import datastructrue.iocd.IOCD;
import datastructrue.iocd.unit.ClassRecord;

import java.util.*;

public class GadgetCFGWrapper {
    public int iocdHash;
    // 所有生成的CFG
    public LinkedList<CFG> cfgList = new LinkedList<>();
    // 所有的Basic Block
    public HashMap<Integer, BasicBlock> bscBlockMap = new HashMap<>();
    // 静态部分扫出的Gadget信息，包括类和调用的方法LINENUM
    // 格式 fullClzName#mtdName:mtdSig
    public LinkedList<String> gadgetClzMtdWithSigSet = new LinkedList<>();
    public HashMap<String, Integer> gadgetClzMtdWithSigAndHash = new HashMap<>();
    // 出现过的GadgetClass，用于缓存需要插桩的类
    public LinkedHashSet<String> gadgetClzNameSet = new LinkedHashSet<>();
    // 动态插桩时需要使用的信息
    // 需要知道插桩的类，方法、Label/行号和需要插入hash
    public HashMap<String, LinkedHashSet<InstNode>> instInfoMap = new HashMap<>();

    public GadgetCFGWrapper() {
    }

    public GadgetCFGWrapper(IOCD iocd) throws BuildCFGException {
        // Notice: 方法的调用顺序务必不能错
        this.iocdHash = iocd.getHashCode();
        this.extractGadgetClzMtdWithSigInfo(iocd.getGadgetCallStack());
        this.buildAllGadgetIntraCFG();
        GraphUtils.doInterBasicBlockDistanceCalculation(cfgList);
        this.doBasicBlockHashCalculation();
        this.calculateIntraBasicBlockDistance();
        this.extractDynamicInstrumentInfo();
        this.extractAllBscBlocks();
        this.extractAllGadgetClass(iocd.getGadgetClasses());
    }

    private void extractAllGadgetClass(LinkedList<ClassRecord> classRecords) {
        HashSet<String> colTagSet = new HashSet<>();
        for (ClassRecord classRecord : classRecords) {
            if (GlobalDataContainer.getInstance().isType2CollisionClass(iocdHash, classRecord.getClassName(), classRecord.getId()) == 0
                    && colTagSet.isEmpty()) {
                gadgetClzNameSet.add(FuzzUtils.getRealClassNameKey(classRecord.getId(), "C1", classRecord.getClassName()));
                gadgetClzNameSet.add(FuzzUtils.getRealClassNameKey(classRecord.getId(), "C2", classRecord.getClassName()));
            } else {
                gadgetClzNameSet.add(FuzzUtils.getRealClassNameKey(classRecord.getId(), null, classRecord.getClassName()));
            }

        }
    }

    private void extractGadgetClzMtdWithSigInfo(LinkedList<Pair<Integer, String>> gadgetCallStack) {
        for (Pair<Integer, String> stackPair : gadgetCallStack) {
            String mtdSig = stackPair.getValue();
            String clzName = CommonUtils.getDeclaredClzBySig(mtdSig);
            String mtdName = CommonUtils.getMethodNameBySig(mtdSig);
            String mtdDesc = CommonUtils.getMtdDescriptorBySubSig(CommonUtils.getSubSigBySig(mtdSig));
            String clzMtdWithSig = clzName + "#" + mtdName + "():" + mtdDesc;
            if (GlobalDataContainer.isDebug) {
                LogUtils.debug2Log("调用栈转换结果为：" + clzMtdWithSig);
            }
            gadgetClzMtdWithSigSet.add(clzMtdWithSig);
            gadgetClzMtdWithSigAndHash.put(clzMtdWithSig.replace("():", ":"), stackPair.getKey());
        }
        if (GlobalDataContainer.isDebug) {
            LogUtils.debug2Log("-----------------------------------------------------------");
        }
    }


    // 功能方法，读取指定目录下的jar包，找到Gadget当中的类并进行intra-CFG的构建
    public void buildAllGadgetIntraCFG() throws BuildCFGException {

        int index = 0;
        for (String clzMtdInfo : gadgetClzMtdWithSigSet) {

            String tmp = clzMtdInfo.split("#")[1];
            // 拿到类名
            String clzName = clzMtdInfo.split("#")[0];
            // 拿到方法名
            String mtdName = tmp.split(":")[0].replace("()", "");
            // 拿到描述符名
            String mtdDesc = tmp.split(":")[1];
            // soot's class for a lambda has no bytecode (CommonUtils.isSootLambdaClass); the previous gadget
            // calls the functional interface method, which leads to the lambda body, the next gadget method
            if (CommonUtils.isSootLambdaClass(clzName)) {
                index++;
                continue;
            }

            try {
                CFG crtGadgetCFG = GraphUtils.buildIntraCFG(clzName, mtdName, mtdDesc, index, gadgetClzMtdWithSigSet);
                crtGadgetCFG.gId = index;
                crtGadgetCFG.clzName = clzName;
                crtGadgetCFG.mtdNameWithDesc = mtdName + ":" + mtdDesc;
                cfgList.add(crtGadgetCFG);
                index++;
                // Debug模式下输出后继和距离
                if (GlobalDataContainer.isDebug) {
                    for (BasicBlock bb : crtGadgetCFG.allBBs.values()) {
                        doIntraBasicBlockDistanceCalculation(crtGadgetCFG, bb.bbId);
                        LogUtils.debug2Log("---------------------------------------------------------------------");
                        LogUtils.debug2Log(bb.bbId + " => " + bb.intraDistance);
                        LogUtils.debug2Log("---------------------------------------------------------------------");
                    }
                    GraphUtils.breadthFirstTraversal(crtGadgetCFG);
                    LogUtils.debug2Log("---------------------------------------------------------------------");
//            for (BasicBlock bb : graph.allBBs.values()) {
//                graph.getBBDistance(bb.bbId);
//            }
                    LogUtils.debug2Log("---------------------------------------------------------------------");
                }
            } catch (BuildCFGException e) {
                e.printStackTrace();
                LogUtils.error("构建Gadget" + clzMtdInfo + "对应的CFG时出错");
                throw new BuildCFGException("构建Gadget" + clzMtdInfo + "对应的CFG时出错", e);
            }
            // 对于Sink不做任何处理
            if (index == gadgetClzMtdWithSigSet.size() - 1) {
                break;
            }

        }
    }

    private void calculateIntraBasicBlockDistance() {
        for (CFG cfg : cfgList) {
            for (BasicBlock bb : cfg.allBBs.values()) {
                // 对每个基本块，都需要记录其距离
                doIntraBasicBlockDistanceCalculation(cfg, bb.bbId);
            }
        }
    }

    // 功能方法，用于计算所有BB的hash值
    // 计算方式: String.hashCode(className+mtdName+mtdDesc+labelName[LABEL-x])
    private void doBasicBlockHashCalculation() {
        for (CFG cfg : cfgList) {
            for (BasicBlock bb : cfg.allBBs.values()) {
                bb.bbHash = (cfg.clzName + cfg.mtdNameWithDesc + bb.getFirstLineNum()).hashCode();
                if (bb.isEntry) {
                    cfg.entryNodeHash = bb.bbHash;
                }
            }
        }
    }

    // 功能方法，用于计算单个方法从各个BB到sink/下一个方法调用的最短距离
    // 基于广度优先遍历
    private void doIntraBasicBlockDistanceCalculation(CFG cfg, int entryBBId) {

        // 到达Sink的所有路径的距离
        ArrayList<Integer> sinkDistanceArr = new ArrayList<>();

        // 用于存储计算出来的距离的Map，bbId - 距离
        Map<Integer, Integer> distances = new HashMap<>();

        // Set to keep track of visited basic blocks
        Set<Integer> visited = new HashSet<>();
        // Queue to store the basic blocks to be processed
        Queue<BasicBlock> queue = new LinkedList<>();
        // Add the entry basic block to the queue
        BasicBlock entryBlock = cfg.allBBs.get(entryBBId);
        queue.add(entryBlock);
        visited.add(entryBlock.bbId);
        // 初始距离为0
        distances.put(entryBlock.bbId, 0);
        while (!queue.isEmpty()) {

            BasicBlock currentBlock = queue.poll();
            int crtDistance = distances.get(currentBlock.bbId);
            if (currentBlock.isSink) {
                sinkDistanceArr.add(crtDistance);
            }
            Set<CFGEdge> outEdges = currentBlock.allOutEdges;
            for (CFGEdge edge : outEdges) {
                BasicBlock targetBlock = cfg.allBBs.get(edge.destId);
                if (!visited.contains(targetBlock.bbId)) {
                    queue.add(targetBlock);
                    visited.add(targetBlock.bbId);
                    distances.put(targetBlock.bbId, crtDistance + 1);
                }
            }

        }
        double bbDistance = 0.0;
        for (Integer distance : sinkDistanceArr) {
            bbDistance += (double) 1 / (double) distance;
        }
        bbDistance = 1 / bbDistance;
        entryBlock.intraDistance = bbDistance;
    }

    private void extractDynamicInstrumentInfo() {

        for (CFG cfg : cfgList) {
            String clzName = cfg.clzName;
            LinkedHashSet<InstNode> tmpSet;
            if (!instInfoMap.containsKey(clzName)) {
                tmpSet = new LinkedHashSet<>();
                instInfoMap.put(clzName, tmpSet);
            } else {
                tmpSet = instInfoMap.get(clzName);
            }
            int tmpInstNum = 0;
            for (BasicBlock bb : cfg.allBBs.values()) {
                InstNode newNode = new InstNode();
                newNode.isMethodEntry = bb.isEntry;
                if (newNode.isMethodEntry) {
                    newNode.isArriveMtdInstMerge = true;
                    try {
                        newNode.arrMtdHash = gadgetClzMtdWithSigAndHash.get(cfg.clzName + "#" + cfg.mtdNameWithDesc);
                    } catch (NullPointerException npe) {
                        LogUtils.error("在导出" + cfg.clzName + "#" + cfg.mtdNameWithDesc + "的hash时候出现空指针");
                        System.exit(0);
                    }

                }
                newNode.hash = bb.bbHash;
                newNode.mtdName = cfg.mtdNameWithDesc.split(":")[0];
                newNode.mtdDesc = cfg.mtdNameWithDesc.split(":")[1];
                String lineNumInfo = bb.getFirstLineNum();
                String labelInfo = bb.getFirstLabelName();
                if (!lineNumInfo.isEmpty()) {
                    newNode.lineNum = Integer.parseInt(lineNumInfo.split(": ")[1]);
                } else if (labelInfo.isEmpty()) {
                    // 如果没有那就记录这是第几条字节码指令
                    newNode.instNum = tmpInstNum;
                } else {
                    newNode.label = Integer.parseInt(labelInfo.split("-")[1]);
                }
                tmpSet.add(newNode);
                tmpInstNum++;
            }
        }

        if (GlobalDataContainer.isDebug) {
            for (Map.Entry<String, LinkedHashSet<InstNode>> entry : instInfoMap.entrySet()) {
                LogUtils.debug2Log(entry.getKey());
                for (InstNode val : entry.getValue()) {
                    LogUtils.debug2Log("--------------------");
                    LogUtils.debug2Log("    " + val.mtdName);
                    LogUtils.debug2Log("    " + val.mtdDesc);
                    LogUtils.debug2Log("    " + val.hash);
                    LogUtils.debug2Log("    " + val.lineNum);
                    LogUtils.debug2Log("    " + val.label);
                    LogUtils.debug2Log("    " + val.instNum);
                    LogUtils.debug2Log("--------------------");
                }
            }
        }
    }

    private void extractAllBscBlocks() {

        for (CFG cfg : cfgList) {
            for (BasicBlock bb : cfg.allBBs.values()) {
                bscBlockMap.put(bb.bbHash, bb);
            }
        }

        if (GlobalDataContainer.isDebug) {
            for (Map.Entry<Integer, BasicBlock> entry : bscBlockMap.entrySet()) {
                LogUtils.debug2Log(entry.getKey().toString());
            }
        }
    }

    // 通过判断到达的hash中包含哪个到达的最后一个方法的hash来判断到达了哪个方法
    public int getArriveClassIndex() throws CFGException {
        //int lastEntryNodeHash = -1;
        int index = -1;
        for (CFG cfg : cfgList) {
            if (JDDFuzzRecord.arrivedBBHashArr.contains(cfg.entryNodeHash)) {
                //lastEntryNodeHash = cfg.entryNodeHash;
                index++;
            } else {
                break;
            }
        }
        if (index == -1) {
            // LogUtils.warn("无法获取最后到达的方法的hash的索引");
            // System.exit(0);
            return -1;
        }
        if (index < JDDFuzzRecord.crtArrivedMtdIndex) {
            LogUtils.debug("arrive method index from bb is "
                    + index + " and from mtd is " + JDDFuzzRecord.crtArrivedMtdIndex
                    + ", using the index from mtd");
            index = JDDFuzzRecord.crtArrivedMtdIndex;
        }
        String gadgetClassWithMethodSig = gadgetClzMtdWithSigSet.get(index);
        String clzName = gadgetClassWithMethodSig.split("#")[0];
        int resIndex = -1;
        int tmpIndex = 0;
        for (String tmpClzName : gadgetClzNameSet) {
            if (tmpClzName.equals(clzName)) {
                resIndex = tmpIndex;
                break;
            } else if ((tmpClzName.contains("C1") || tmpClzName.contains("C2"))
                        && (GlobalDataContainer.getInstance().isType2CollisionClass(iocdHash, clzName, 0) == 0 || GlobalDataContainer.getInstance().isType2CollisionClass(iocdHash, clzName, 1) == 0)){
                resIndex = tmpIndex;
                break;
            }
            tmpIndex++;
        }
        if (resIndex == -1) {
            String msg = "In `getArriveClassIndex`: Can not get the index of Gadget Class <" + clzName + "> of gadget " + iocdHash + ", may be not gadget class";
            LogUtils.warn(msg);
            LogUtils.warn2Log(msg);
            // throw new CFGException("无法获取到达的Gadget Class的索引");
        }
        return resIndex;
        // return tmpMsg.split("#")[0];
    }


}
