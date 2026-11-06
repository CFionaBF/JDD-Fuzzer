package cn.edu.fudan.utils;

public enum ArgumentEnum {

    FUZZ_TIME("fuzzTime"),
    WORK_DIR("work_dir"),
    WITH_ALL_JDK("withAllJdk"),
    BASIC_CLASSES_LOAD("basicClassesLoad"),
    INPUT_CLASS_PATH("inputClassPath"),
    OUTPUT_DIR("outputDir"),
    INPUT_PROJECTS_DIR("projectsInputDir"),
    GADGET_INFO_DIR("gadgetInfos_dir"),
    IGNORE_LIST_PATH("ignoreListPath"),

    EXPLOITABLE_DIR("exploitable_dir"),

    IS_PRE_SOOT_LOAD("isPreSootLoad");

    private String key = "";
    ArgumentEnum(String key) { this.key = key; }

    @Override
    public String toString() {
        return key;
    }
}
