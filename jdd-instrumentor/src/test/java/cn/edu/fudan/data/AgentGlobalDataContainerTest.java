package cn.edu.fudan.data;

import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.exception.inst.BuildCFGException;
import cn.edu.fudan.utils.ConfigUtils;
import org.junit.Test;

public class AgentGlobalDataContainerTest {

    @Test
    public void initCFGAndInstInfoTest() throws BuildCFGException {
        ConfigUtils.debugConfigPath = "/Users/hn13/IdeaProjects/fuzzer/config/config.properties";
        ConfigUtils.initInAgent();
        GlobalDataContainer.getInstance();
        AgentGlobalDataContainer.loadConditions();
        AgentGlobalDataContainer.extractAllSinkInstInfo();
        AgentGlobalDataContainer.extractAllIfStmtRecord();
        // GlobalDataContainer.getInstance();
        AgentGlobalDataContainer.initCFGAndInstInfo();
    }

}
