package cn.edu.fudan.utils;

import org.junit.Assert;
import org.junit.Test;

public class CommonUtilsTest {

    @Test
    public void generateRandomIntTest() {
        for (int i = 0; i < 100; i++) {
            int randomInt = CommonUtils.generateRandomInt(0, 1);
            System.out.println(randomInt);
        }
    }

}
