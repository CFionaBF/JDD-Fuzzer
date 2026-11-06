package cn.edu.fudan.data.distance;

import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.exception.inst.BuildCFGException;
import cn.edu.fudan.utils.ConfigUtils;
import cn.edu.fudan.utils.LogUtils;
import datastructrue.iocd.IOCD;
import org.junit.Test;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;

public class GadgetCFGWrapperTest {

    public static void callMethod(Class clz, String methodName, Object target, Object... args) {
        Class[] typeList = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            typeList[i] = args[i].getClass();
        }
        Method method = ReflectionUtils.findMethod(clz, methodName, typeList);
        method.setAccessible(true);
        ReflectionUtils.invokeMethod(method, target, args);
    }

    @Test
    public void extractGadgetClzMtdWithSig() {
        ConfigUtils.debugConfigPath = "/Users/hn13/IdeaProjects/fuzzer/config/config.properties";
        ConfigUtils.initInAgent();
        GlobalDataContainer.getInstance();
        GadgetCFGWrapper gadgetCFGWrapper = new GadgetCFGWrapper();
        for (IOCD e : GlobalDataContainer.getAllIOCD()) {
            callMethod(GadgetCFGWrapper.class, "extractGadgetClzMtdWithSig", gadgetCFGWrapper, e.getGadgetCallStack());
        }
    }

    @Test
    public void buildAllGadgetIntraCFG() {
        ConfigUtils.debugConfigPath = "/Users/hn13/IdeaProjects/fuzzer/config/config.properties";
        ConfigUtils.initInAgent();
        GlobalDataContainer.getInstance();
        GadgetCFGWrapper gadgetCFGWrapper = new GadgetCFGWrapper();
        int len = 0;
        for (IOCD e : GlobalDataContainer.getAllIOCD()) {
            callMethod(GadgetCFGWrapper.class, "extractGadgetClzMtdWithSig", gadgetCFGWrapper, e.getGadgetCallStack());
            callMethod(GadgetCFGWrapper.class, "buildAllGadgetIntraCFG", gadgetCFGWrapper);
            len ++;
            LogUtils.success("len = " + len);
        }
    }

    @Test
    public void buildAllGadgetIntraCFG2() throws BuildCFGException {
        ConfigUtils.debugConfigPath = "/Users/hn13/IdeaProjects/fuzzer/config/config.properties";
        ConfigUtils.initInAgent();
        GlobalDataContainer.getInstance();
        int len = 0;
        for (IOCD e : GlobalDataContainer.getAllIOCD()) {
            new GadgetCFGWrapper(e);
            len ++;
            LogUtils.success("len = " + len);
        }
    }
}
