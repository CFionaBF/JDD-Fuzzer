package cn.edu.fudan.generator.mutate;

import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import cn.edu.fudan.exception.validator.ConditionObjSettingException;
import cn.edu.fudan.generator.condition.ConditionObjsWrapper;
import cn.edu.fudan.generator.seed.SeedWrapper;
import datastructrue.IntWrapper;

import java.util.LinkedHashSet;

public abstract class ConditionBasedMutator {


    public static void handleConditionBasedMutate(SeedWrapper seed, int crtArrivedBscHash) throws ConditionObjSettingException {
        // 找到对应的ConditionObjsWrapper
        ConditionObjsWrapper conditionObjsWrapper = ValidatorGlobalDataContainer.getConditionObjsWrapperByIOCDHash(seed.iocdHash);
        // 获取用于指导变异的non-basic condition的索引序列
        IntWrapper indexOfSeq = new IntWrapper(-1);
        LinkedHashSet<Integer> nonBscConditionIndexSet = conditionObjsWrapper.getMutationNonConditionSeq(crtArrivedBscHash, indexOfSeq);
        // 若不为空，说明可以进行变异
        if (!nonBscConditionIndexSet.isEmpty()) {
            conditionObjsWrapper.doNonBscConditionObjsSetting(seed, nonBscConditionIndexSet, indexOfSeq);
        }
    }
}
