package cn.edu.fudan.data.distance.analyzer;


import cn.edu.fudan.data.distance.data.InsnBlock;
import cn.edu.fudan.data.distance.data.InsnText;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.*;

import java.util.HashMap;
import java.util.List;

public class ControlFlowEdgeAnalyzer<V extends Value> extends Analyzer<V> {
    private AbstractInsnNode[] nodeArray;
    public InsnBlock[] blocks;
    // id - InsnBlock
    public HashMap<Integer, InsnBlock> idBlocksMap = new HashMap<>();

    public ControlFlowEdgeAnalyzer(Interpreter<V> interpreter) {
        super(interpreter);
    }

    @Override
    public Frame<V>[] analyze(String owner, MethodNode method) throws AnalyzerException {
        // 将一条条指令转换为一个个AbstractInsnNode
        nodeArray = method.instructions.toArray();
        int length = nodeArray.length;
        blocks = new InsnBlock[length];
        InsnText insnText = new InsnText();
        // 对所有的InsnBlock进行初始化
        for (int i = 0; i < length; i++) {
            blocks[i] = getBlock(i);
            blocks[i].ibId = i;
            idBlocksMap.put(blocks[i].ibId, blocks[i]);
            AbstractInsnNode node = nodeArray[i];
            // 针对LookupSwitchInsnNode和TableSwitchInsnNode做特殊处理，其他的直接单个加入到lines中去
            List<String> lines = insnText.toLines(node);
            blocks[i].addLines(lines);
        }

        return super.analyze(owner, method);
    }

    @Override
    protected void newControlFlowEdge(int insnIndex, int successorIndex) {
        // 首先，处理自己的代码逻辑
        AbstractInsnNode insnNode = nodeArray[insnIndex];
        int insnOpcode = insnNode.getOpcode();
        int insnType = insnNode.getType();

        if (insnType == AbstractInsnNode.JUMP_INSN) {
            if ((insnIndex + 1) == successorIndex) {
                addNext(insnIndex, successorIndex);
            }
            else {
                addJump(insnIndex, successorIndex);
            }
        }
        else if (insnOpcode == LOOKUPSWITCH) {
            addJump(insnIndex, successorIndex);
        }
        else if (insnOpcode == TABLESWITCH) {
            addJump(insnIndex, successorIndex);
        }
        else if (insnOpcode == RET) {
            addJump(insnIndex, successorIndex);
        }
        else if (insnOpcode == ATHROW || (insnOpcode >= IRETURN && insnOpcode <= RETURN)) {
            assert false : "should not be here";
            removeNextAndJump(insnIndex);
        }
        else {
            addNext(insnIndex, successorIndex);
        }

        // 其次，调用父类的方法实现
        super.newControlFlowEdge(insnIndex, successorIndex);
    }

    private void addNext(int fromIndex, int toIndex) {
        InsnBlock currentBlock = getBlock(fromIndex);
        InsnBlock nextBlock = getBlock(toIndex);
        currentBlock.addNext(nextBlock);
    }

    private void addJump(int fromIndex, int toIndex) {
        InsnBlock currentBlock = getBlock(fromIndex);
        InsnBlock nextBlock = getBlock(toIndex);
        currentBlock.addJump(nextBlock);
    }

    private void removeNextAndJump(int insnIndex) {
        InsnBlock currentBlock = getBlock(insnIndex);
        currentBlock.nextBlockList.clear();
        currentBlock.jumpBlockList.clear();
    }

    private InsnBlock getBlock(int insnIndex) {
        InsnBlock block = blocks[insnIndex];
        if (block == null){
            block = new InsnBlock();
            blocks[insnIndex] = block;
        }
        return block;
    }

    public InsnBlock[] getBlocks() {
        return blocks;
    }

}
