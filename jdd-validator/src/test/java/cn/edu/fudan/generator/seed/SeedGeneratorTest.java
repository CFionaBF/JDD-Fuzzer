package cn.edu.fudan.generator.seed;

import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import cn.edu.fudan.exception.validator.GeneratorException;
import cn.edu.fudan.utils.ConfigUtils;
import datastructrue.iocd.IOCD;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Map;

public class SeedGeneratorTest {


    @Test
    public void generateSeedTest() {
        ConfigUtils.debugConfigPath = "/Users/hn13/IdeaProjects/fuzzer/config/config.properties";
        ConfigUtils.init();
        ValidatorGlobalDataContainer.init();
        System.out.println(GlobalDataContainer.getAllIOCD().size());
        int success = 0;
        // 生成所有初始化的种子
        for (Map.Entry<Integer, IOCD> iocdEntry : GlobalDataContainer.getAllIOCDEntry()) {
            IOCD iocd = iocdEntry.getValue();
            short type = iocd.isHashCollisionFlag() ? (short) 2 : (short) 0;
            // 根据是否进行消融实验选择不同的方法
            try {
                System.out.println("处理" + iocd.getHashCode());
                SeedWrapper seed = SeedGenerator.generateSeed(iocd, type);
                StringBuilder sb = new StringBuilder();
                for (String backboneClzName : seed.immutableFieldInfoSet) {
                    sb.append(backboneClzName).append("  ");
                }
                System.out.println(("immutatedFieldInfoSet中有：" + sb.toString()));
                success++;
            } catch (Exception e) {
                System.out.println(iocd.getHashCode() + ".json出错");
                System.out.println(e.getMessage());
                e.printStackTrace();
            }
        }
        System.out.println("Sucess" + success);
    }

    @Test
    public void generateSeedAndCloneSeedTest() {
        ConfigUtils.debugConfigPath = "/Users/hn13/IdeaProjects/fuzzer/config/config.properties";
        ConfigUtils.init();
        ValidatorGlobalDataContainer.init();
        System.out.println(GlobalDataContainer.getAllIOCD().size());
        ArrayList<SeedWrapper> seedWrapperArrayList = new ArrayList<>();
        int success = 0;
        // 生成所有初始化的种子
        for (Map.Entry<Integer, IOCD> iocdEntry : GlobalDataContainer.getAllIOCDEntry()) {
            IOCD iocd = iocdEntry.getValue();
            short type = iocd.isHashCollisionFlag() ? (short) 2 : (short) 0;
            // 根据是否进行消融实验选择不同的方法
            try {
                System.out.println("处理" + iocd.getHashCode());
                SeedWrapper seed = SeedGenerator.generateSeed(iocd, type);
                seedWrapperArrayList.add(seed);
                success++;
            } catch (Exception e) {
                System.out.println(iocd.getHashCode() + ".json出错");
                System.out.println(e.getMessage());
                e.printStackTrace();
            }
        }
        System.out.println("Sucess" + success);
        success = 0;
        for (SeedWrapper seed : seedWrapperArrayList) {
            for (int i = 0; i < 10; i++) {
                seed.clone();
                StringBuilder sb = new StringBuilder();
                for (String backboneClzName : seed.immutableFieldInfoSet) {
                    sb.append(backboneClzName).append("  ");
                }
                System.out.println(("immutatedFieldInfoSet中有：" + sb.toString()));
                success++;
            }
            success++;
        }
        System.out.println("Clone-Sucess" + success);
    }

    @Test
    public void generateBackboneObjectTest() throws GeneratorException {
        ConfigUtils.debugConfigPath = "/Users/hn13/IdeaProjects/fuzzer/config/config.properties";
        ConfigUtils.init();
        ValidatorGlobalDataContainer.init();
        Object bbObj;
        System.out.println(GlobalDataContainer.getAllIOCD().size());
        int success = 0;
        for (IOCD iocd : GlobalDataContainer.getAllIOCD()) {
            short type = iocd.isHashCollisionFlag() ? (short) 2 : (short) 0;
            try {
                bbObj = SeedGenerator.generateBackboneObject(
                        iocd.getHashCode(),
                        iocd.getGadgetClasses(),
                        (short) type,
                        iocd.getProtocol(),
                        null,
                        null,
                        null
                );

                Assert.assertNotNull(bbObj);
                System.out.println(iocd.getHashCode() + ".json成功了！！");
                success++;
            } catch (Exception | AssertionError e) {
                System.out.println(iocd.getHashCode() + ".json出错");
            }

        }
        // FuzzUtils.deleteCache();
        System.out.println("Sucess" + success);
    }

//    @Test
//    public void generateSeed_AblationTest() {
//        ConfigUtils.debugConfigPath = "/Users/hn13/IdeaProjects/fuzzer/config/config.properties";
//        ConfigUtils.init();
//        ValidatorGlobalDataContainer.init();
//        Object bbObj;
//        System.out.println(GlobalDataContainer.getAllIOCD().size());
//        int success = 0;
//        for (IOCD iocd : GlobalDataContainer.getAllIOCD()) {
//            short type = 1;
//            short ablation_type = 1;
//            try {
//                bbObj = SeedGenerator.generateSeed_Ablation(iocd, type, ablation_type);
//                Assert.assertNotNull(bbObj);
//                System.out.println(iocd.getHashCode() + ".json成功了！！");
//                success++;
//            } catch (Exception | AssertionError e) {
//                System.out.println(iocd.getHashCode() + ".json出错");
//            }
//        }
//    }

}
