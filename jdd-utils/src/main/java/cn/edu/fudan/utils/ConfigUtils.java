package cn.edu.fudan.utils;

import cn.edu.fudan.container.ConfigContainer;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Properties;

public abstract class ConfigUtils {

    private static Properties configProperties = new Properties();
    public static String debugConfigPath = "";
    private static boolean isLoaded = false;
    private static boolean isSootLoaded = false;
    public static HashSet<String> analysisClzPath = new HashSet<>();

    // 判断是否是类Unix系统
    public static boolean isOnServer() {
        return !(System.getProperty("os.name").contains("Windows") & System.getProperty("os.name").contains("MAC OS"));
    }

    private static String getWorkDir() {
        return System.getProperty("user.dir") + "/";
    }

    // 判断是否是绝对路径
    private static boolean isAbsolute(String path) {
        if (isOnServer()) {
            return path.startsWith("/");
        }
        return path.indexOf(":") > 0;
    }

    public static void setAndLoadConfigFile() {
        // 加载配置项并设置jdk依赖类型
        String finalConfigPath = debugConfigPath.isEmpty() ? "config/config.properties" : debugConfigPath;
        ConfigUtils.loadConfig(finalConfigPath);
    }

    public static void initConfig() {
        if (isLoaded) {
            return;
        }
        setAndLoadConfigFile();
        ConfigContainer.sootClassPath = ConfigUtils.getJdkDependencies(ConfigContainer.withAllJdk);
        // 解压inputClassPath下所有的jar/war包
        LogUtils.info2Log("解压目录" + ConfigContainer.inputClassPath + "的类信息");
        FileUtils.decompressJarFromDir(ConfigContainer.inputClassPath);
        ConfigContainer.inputClassPath = ConfigContainer.inputClassPath + "tmp/";
        LogUtils.info2Log("解压完成");

    }

    private static String getPathProperty(String pathKey) {

        // 获取当前的程序的运行目录
        String workDir = getWorkDir();
        // 判断是对path进行的配置
        String tmpValue = configProperties.getProperty(pathKey);
        if (Objects.equals(tmpValue, "") || Objects.equals(tmpValue, null)) {
            throw new IllegalArgumentException("Config property " + pathKey + " not found");
        }
        return isAbsolute(tmpValue) ?
                tmpValue :
                workDir + tmpValue;
    }

    public static void initInAgent() {
        initConfig();
        ClassUtils.loadClassInPath(ConfigContainer.inputClassPath);
        isLoaded = true;
        if (ConfigContainer.isPreSootLoad) {
            LogUtils.info("Init Soot in Agent");
            SootUtils.setSoot(ConfigContainer.inputClassPath);
            isSootLoaded = true;
        }

    }

    /**
     * Used to load configuration files and initialize Soot
     */
    public static void init() {
        initConfig();
        if (!isLoaded){
            ClassUtils.loadClassInPath(ConfigContainer.inputClassPath);
        }
        if (!isSootLoaded) {
            SootUtils.setSoot(ConfigContainer.inputClassPath);
        }
        isLoaded = true;
        isSootLoaded = true;
    }


    /**
     * Used to get JDK dependencies on the machine running the program. By default, we only use { lib/jce.jar, lib/rt.jar, lib/ext/nashron.jar }
     *
     * @param withAllJdk
     * @return jdkDependencies, a String containing classpath dependencies separated by File.pathSeparator
     */
    public static String getJdkDependencies(String withAllJdk) {
        String javaHome = System.getProperty("java.home");
        String[] jre;

        if ("true".equals(withAllJdk)) {
            jre = new String[]{"../lib/dt.jar", "../lib/sa-jdi.jar", "../lib/tools.jar",
                    "../lib/jconsole.jar", "lib/resources.jar", "lib/rt.jar", "lib/jsse.jar",
                    "lib/jce.jar", "lib/charsets.jar", "lib/ext/cldrdata.jar", "lib/ext/dnsns.jar",
                    "lib/ext/jaccess.jar", "lib/ext/localedata.jar", "lib/ext/nashorn.jar",
                    "lib/ext/sunec.jar", "lib/ext/sunjce_provider.jar", "lib/ext/sunpkcs11.jar",
                    "lib/ext/zipfs.jar", "lib/management-agent.jar"};
        } else {
            jre = new String[]{"lib/rt.jar", "lib/jce.jar", "lib/ext/nashorn.jar"};
        }

        StringBuilder tmpJdkDependencies = new StringBuilder();
        for (String cp : jre) {
            String path = String.join(File.separator, javaHome, cp);
            File file = new File(path);
            if (file.exists()) {
                tmpJdkDependencies.append(path);
                tmpJdkDependencies.append(File.pathSeparator);
            }
        }
        tmpJdkDependencies.deleteCharAt(tmpJdkDependencies.length() - 1);

        return tmpJdkDependencies.toString();
    }


    /**
     * Load configuration items from the specified file path, default path is config/config.properties
     *
     * @param filePath configuration file path
     */
    public static void loadConfig(String filePath) {
        try {

            Reader configReader = new FileReader(filePath);
            configProperties.load(configReader);

            // Load configuration items
            ConfigContainer.fuzzTime = Long.valueOf(configProperties.getProperty(ArgumentEnum.FUZZ_TIME.toString(), "60"));
            System.out.println("fuzztime out:" + ConfigContainer.fuzzTime);
            ConfigContainer.workDir = configProperties.getProperty(ArgumentEnum.WORK_DIR.toString());
            // ConfigContainer.basicClassesLoad = ConfigContainer.workDir + File.separator + configProperties.getProperty(ArgumentEnum.BASIC_CLASSES_LOAD.toString());
            ConfigContainer.outputDir = ConfigContainer.workDir + File.separator + configProperties.getProperty(ArgumentEnum.OUTPUT_DIR.toString());
            ConfigContainer.projectsInputDir = ConfigContainer.workDir + File.separator + configProperties.getProperty(ArgumentEnum.INPUT_PROJECTS_DIR.toString());
            // Path containing classes needed for subsequent Exploit Object construction
            ConfigContainer.inputClassPathOrigin = ConfigContainer.projectsInputDir +  File.separator + configProperties.getProperty(ArgumentEnum.INPUT_CLASS_PATH.toString());
            ConfigContainer.inputClassPath = ConfigContainer.projectsInputDir +  File.separator + configProperties.getProperty(ArgumentEnum.INPUT_CLASS_PATH.toString());
            // Whether to use all JDK dependencies, invalid in fuzz scenarios
            ConfigContainer.withAllJdk = configProperties.getProperty(ArgumentEnum.WITH_ALL_JDK.toString(), "false");
            // Input directory for gadget
            String tmpGadgetInfoDir = configProperties.getProperty(ArgumentEnum.GADGET_INFO_DIR.toString());
            ConfigContainer.gadgetInfosDir = tmpGadgetInfoDir.startsWith("/") ? tmpGadgetInfoDir : ConfigContainer.projectsInputDir + File.separator + tmpGadgetInfoDir;
            // Output directory for exploitable objects
            String tmpExploitableDir = configProperties.getProperty(ArgumentEnum.EXPLOITABLE_DIR.toString());
            ConfigContainer.exploitableDir = tmpExploitableDir.startsWith("/") ? tmpExploitableDir : ConfigContainer.outputDir + File.separator + tmpExploitableDir;
            // Classes to be ignored during analysis
            ConfigContainer.ignoreListPath = ConfigContainer.projectsInputDir + File.separator + configProperties.getProperty(ArgumentEnum.IGNORE_LIST_PATH.toString());

            // Add JDK native dependencies
            analysisClzPath.addAll(Arrays.asList(getJdkDependencies(ConfigContainer.withAllJdk).split(File.pathSeparator)));
            // Add project dependencies to be analyzed
            analysisClzPath.add(ConfigContainer.inputClassPath);

            ConfigContainer.isPreSootLoad = Boolean.parseBoolean(configProperties.getProperty(ArgumentEnum.IS_PRE_SOOT_LOAD.toString(), "false"));

        } catch (IOException e) {
            throw new IllegalArgumentException("config file not found! Current work dir is:" + System.getProperty("user.dir"));
        }
    }

    public static void initAllDirectory() {
        FileUtils.ensureDirectoryExist(new File(ConfigContainer.workDir));
        FileUtils.ensureDirectoryExist(new File(ConfigContainer.projectsInputDir));
        FileUtils.ensureDirectoryExist(new File(ConfigContainer.gadgetInfosDir));
        FileUtils.ensureDirectoryExist(new File(ConfigContainer.inputClassPathOrigin));
        
        FileUtils.ensureFileExist(new File(ConfigContainer.ignoreListPath));

        FileUtils.ensureDirectoryExist(new File(ConfigContainer.outputDir));
        FileUtils.ensureDirectoryExist(new File(ConfigContainer.exploitableDir));
    }

    public static boolean checkInputDir() {
        File inputDir = new File(ConfigContainer.inputClassPath);
        if (!inputDir.exists() && !inputDir.isDirectory()) {
            LogUtils.error("analysis classes folder " + ConfigContainer.inputClassPath + " not found or not a folder, check it. if this is the first time using this tool, please run the tool with setup-dir option to set up neccessary direcotries!");
            return false;
        }
        if (inputDir.list().length == 0) {
            LogUtils.error("analysis folder " + ConfigContainer.inputClassPath + " is empty, check it. Notice: you need to put the jars you want to analyze in this folder <" + ConfigContainer.inputClassPathOrigin + ">. remember to delete the <" + ConfigContainer.inputClassPath + ">");
            return false;
        }
        return true;
    }

}
