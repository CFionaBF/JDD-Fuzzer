package cn.edu.fudan.instrumentor.visitor;

import datastructrue.inst.IfStmtInstRecord;
import datastructrue.inst.MethodInstRecord;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.LogUtils;
import datastructrue.Pair;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

@Deprecated
public class InstClassVisitor extends ClassVisitor {

    // 需要被插桩的类中的所有的条件分支的插桩点
    HashSet<IfStmtInstRecord> allIfStmtInstSite;
    // 需要被插桩的类中的Gadget方法
    HashSet<MethodInstRecord> allGadgetMtdInstSite;
    // <插桩方法-方法描述符>-[<插桩位置-插桩hash>]
    HashMap<Pair<String, String>, HashMap<Integer, Integer>> allIfStmtInstMtdMap;
    // <插桩方法-方法描述符>-插桩hash
    HashMap<Pair<String, String>, Integer> allGadgetInstMtdMap;

    public InstClassVisitor(ClassVisitor cv, HashSet<IfStmtInstRecord> instSite, HashSet<MethodInstRecord> mtdInstSite) {

        super(Opcodes.ASM9, cv);
        this.allIfStmtInstSite = instSite;
        this.allIfStmtInstMtdMap = extractAllInstIfStmtInfo();
        this.allGadgetMtdInstSite = mtdInstSite;
        this.allGadgetInstMtdMap = extractAllGadgetInstInfo();
    }

    @Override
    public MethodVisitor visitMethod(int access, String name,
                                     String descriptor, String signature,
                                     String[] exceptions) {

        MethodVisitor mv;
        mv = cv.visitMethod(access, name, descriptor, signature, exceptions);
        // 如果方法是需要进行插桩的方法，则进入IfStmtMethodVisitor和ArriveMtdMethodVisitor进行处理
        Pair<String, String> tmpMtdNameDesPair = new Pair<>(name, descriptor, false);
        if (allIfStmtInstMtdMap.containsKey(tmpMtdNameDesPair)) {
            HashMap<Integer, Integer> instSiteMap = allIfStmtInstMtdMap.get(tmpMtdNameDesPair);
            mv = new IfStmtMethodVisitor(mv, instSiteMap, name);
        }
        if (allGadgetInstMtdMap.containsKey(tmpMtdNameDesPair)) {
            Integer hashCode = allGadgetInstMtdMap.get(tmpMtdNameDesPair);
            // mv = new ArriveMtdMethodVisitor(mv, hashCode, name);
            mv = new ArriveMtdMethodAdapter(mv, access, name, descriptor, hashCode);
        }

        // 不是就直接返回
        return mv;

    }


    /**
     * 根据this.allGadgetMtdInstSite中的数据，抽取出需要进行插桩的方法
     *
     * @return
     */
    private HashMap<Pair<String, String>, Integer> extractAllGadgetInstInfo() {

        if (allGadgetMtdInstSite == null) {
            return new HashMap<>();
        }

        HashMap<Pair<String, String>, Integer> resMap = new HashMap<>();

        for (MethodInstRecord record : allGadgetMtdInstSite) {
            // 构造对应方法的Pair
            String mtdName = record.getName();
            String mtdSubSig = CommonUtils.getMtdDescriptorBySubSig(record.getSubSig());
            Pair<String, String> mtdPair = new Pair<>(mtdName, mtdSubSig, false);
            // 获取对应的hash
            int hashCode = record.getHashCode();
            resMap.put(mtdPair, hashCode);
        }

        return resMap;

    }

    /**
     * 根据this.allIfStmtInstSite中的数据，抽取出需要进行插桩的方法
     *
     * @return
     */
    private HashMap<Pair<String, String>, HashMap<Integer, Integer>> extractAllInstIfStmtInfo() {

        if (allIfStmtInstSite == null) {
            LogUtils.warn2Log("allIfStmtInstSite为null，直接返回空Map");
            return new HashMap<>();
        }

        HashMap<Pair<String, String>, HashMap<Integer, Integer>> resMap = new HashMap<>();

        for (IfStmtInstRecord record : allIfStmtInstSite) {
            // 构造对应方法的Pair
            String mtdName = record.getMethodName();
            String mtdSubSig = CommonUtils.getMtdDescriptorBySubSig(record.getMethodSubSig());
            Pair<String, String> mtdPair = new Pair<>(mtdName, mtdSubSig, false);
            // 构造插桩位置-hash的Map
            Set<Integer> instLineNumSet = record.getSuccessor(); // 往条件判断的后继中插桩
            int basicSuccessorLineNum = record.getBasicSuccessor();
            int instHash = record.getHashCode();
            // 如果有BasicSuccessor，就只用它
            if (basicSuccessorLineNum != -1) {
                if (!resMap.containsKey(mtdPair)) {
                    HashMap<Integer, Integer> tmpInstMap = new HashMap<>();
                    tmpInstMap.put(basicSuccessorLineNum, instHash);
                    resMap.put(mtdPair, tmpInstMap);
                } else {
                    resMap.get(mtdPair).put(basicSuccessorLineNum, instHash);
                }
                continue;
            }
            // 如果没有BasicSuccessor，那就把后继都插桩了
            for (Integer lineNum : instLineNumSet) {
                // 如果Pair不存在于Map，就向里面进行填充新的HashSet
                if (!resMap.containsKey(mtdPair)) {
                    HashMap<Integer, Integer> tmpInstMap = new HashMap<>();
                    tmpInstMap.put(lineNum, instHash);
                    resMap.put(mtdPair, tmpInstMap);
                    continue;
                }
                // 如果Pair存在于Map，就向HashSet中新增数据
                resMap.get(mtdPair).put(lineNum, instHash);
            }
        }

        return resMap;

    }


}
