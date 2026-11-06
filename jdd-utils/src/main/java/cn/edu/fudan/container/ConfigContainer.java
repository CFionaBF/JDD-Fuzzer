package cn.edu.fudan.container;

public class ConfigContainer {
    public static Long fuzzTime;

    public static String workDir;
    public static String withAllJdk;
    // public static String basicClassesLoad; // = "inPuts/loadBasicClasses/basicClasses";

    public static String projectsInputDir; // = "inPuts";

    public static String gadgetInfosDir; // = projectsInputDir+ File.separator + "Gadgets";
    public static String invokeGadgetInfosDir = "inPuts/invGadgets";

    public static String outputDir; // = "outPuts";

    public static String exploitableDir; // ="exploitable_dir";

    public static String inputClassPath;
    public static String sootClassPath;
    public static String ignoreListPath;

    public static String inputClassPathOrigin;

    public static boolean isPreSootLoad;
}
