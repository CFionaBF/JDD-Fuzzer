package cn.edu.fudan.mutate;

import cn.edu.fudan.generator.condition.ConditionObjsWrapper;
import cn.edu.fudan.generator.mutate.ConditionBasedMutator;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.ReflectionUtils;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;

public class ConditionBasedMutatorTest {

    @Test
    public void shuffleConditionIndexSetTest() throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        ConditionObjsWrapper conditionObjsWrapper = new ConditionObjsWrapper();
        Method shuffle = ConditionObjsWrapper.class.getDeclaredMethod("shuffleConditionIndexSet", LinkedHashSet.class);
        shuffle.setAccessible(true);

        for (int j = 0; j < 10; j++) {
            LinkedHashSet<Integer> nonBscConditionIndexSet = new LinkedHashSet<>();
            for (int i = 0; i < CommonUtils.generateRandomInt(5, 10); i++) {
                nonBscConditionIndexSet.add(i);
            }
            int z = (int) shuffle.invoke(conditionObjsWrapper, nonBscConditionIndexSet);
            for (int k = 0; k < nonBscConditionIndexSet.size(); k++) {
                System.out.println(((1 << k) & z) >> k);
            }
            System.out.println("_______________________________________________________________");
        }


    }

}
