package cn.edu.fudan.utils;

import cn.edu.fudan.container.ConfigContainer;
import org.apache.commons.io.FileUtils;
import soot.*;
import soot.options.Options;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public abstract class SootUtils {

    // 此处将同一个包下无法分析的类合并的阈值设置为10，比如a.b.c.D1-D11会被合并为a.b.c
    public static int loadClassCounter = 0, maxPackageRecordTime = 10;
    public static HashMap<String, Integer> packageRecordTime = new HashMap<>();
    public static List<String> basicClasses = new LinkedList<>();
    public static List<String> ignoreClasses = getIgnoreClass();
    private static boolean isSootLoad = false;

    public static void setSoot(String inputClassPath) {
        if (isSootLoad) {
            return;
        }
        G.reset();
        Options.v().set_src_prec(Options.src_prec_class);
        Options.v().set_process_dir(Collections.singletonList(((inputClassPath))));
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_whole_program(true);
        Options.v().set_prepend_classpath(true);
        Options.v().set_output_format(Options.output_format_jimple);
        Options.v().set_output_dir(ConfigContainer.outputDir + "/JimpleOutput/framework1");
        Options.v().set_drop_bodies_after_load(false);
        // Options.v().set_no_bodies_for_excluded(true);
        Options.v().setPhaseOption("cg", "all-reachable:true");
        // addBasicClasses();
        Scene.v().setSootClassPath(Scene.v().getSootClassPath() + File.pathSeparator + ConfigContainer.sootClassPath);
//        Scene.v().loadNecessaryClasses();
        Scene.v().loadDynamicClasses();
        loadTargetClass();
        isSootLoad = true;
        // Scene.v().loadBasicClasses();

    }

    // 向soot中逐个加入需要被分析的类
    private static void loadTargetClass() {
        final HashSet hashSet = new HashSet();
        String[] jdkPaths = ConfigContainer.sootClassPath.split(":");
        List<String> paths = new ArrayList<>(Arrays.asList(jdkPaths));
        paths.add(ConfigContainer.inputClassPath);
        for (String path : paths) {
            for (final String cl : SourceLocator.v().getClassesUnder(path)) {
                if (checkIgnore(cl)) {
                    continue;
                }
                try {
                    // 限制load一个类的超时阈值为100秒
                    (new TimeOutTask() {
                        @Override
                        protected void task() {
                            SootClass theClass = Scene.v().loadClassAndSupport(cl);
                            if (!theClass.isPhantom()) {
                                // 这里存在类数量不一致的情况，是因为存在重复的对象
                                theClass.setApplicationClass();
                                hashSet.add(theClass);
                                if (loadClassCounter % 10000 == 0) {
                                    LogUtils.info2Log("Collected " + loadClassCounter + " classes.");
                                }
                                loadClassCounter++;
                            }
                        }

                        @Override
                        protected void timeoutHandler() {
                            LogUtils.warn("将类" + cl + "导入到soot过程中超时，跳过处理");
                        }
                    }).run(1800);

                } catch (Exception e) {
                    LogUtils.warn("In `SootUtils`: 加载类" + cl + "过程中出错：" + e.getMessage());
                }
            }
        }
        LogUtils.info2Log("共加载：" + hashSet.size() + "个类，App.Size" + Scene.v().getApplicationClasses().size());
    }

    // 用于检查导入的类是否被认为忽略了
    public static boolean checkIgnore(String clazzName) {

        for (String ignoreClass : ignoreClasses) {
            if (ignoreClass.startsWith("#")) {
                continue;
            }
            if (clazzName.contains(ignoreClass)) {
                return true;
            }
        }
        return false;

    }

    // 获取外部用户指定的列表中的ignore classes
    private static List<String> getIgnoreClass() {

        List<String> lines = new LinkedList<>();
        try {
            File ignoreFile = new File(ConfigContainer.ignoreListPath);
            lines = org.apache.commons.io.FileUtils.readLines(ignoreFile, StandardCharsets.UTF_8);
            // 统计出现的包的次数，处理超过阈值的类，并将新的内容写入到文件中
            String packageName;
            List<String> removePackageList = new ArrayList<>();
            for (String l : lines) {
                if (l.startsWith("#") || l.equals("")) {
                    continue;
                }
                packageName = getPackageName(l);
                updatePackageRecordTime(packageName);
            }
            if (maxPackageRecordTime <= 0) {
                maxPackageRecordTime = 10;
            } // 避免出现异常情况
            for (String key : packageRecordTime.keySet()) {
                int currentTime = packageRecordTime.get(key);
                if (currentTime > maxPackageRecordTime) {
                    removePackageList.add(key);
                    lines.removeIf(line -> line.contains(key));
                    lines.add(key);
                    LogUtils.info2Log(key + "包下有超过" + maxPackageRecordTime + "个类无法处理，忽略整个包");
                }
            }
            // 避免java.util.ConcurrentModificationException
            for (String rmPackage : removePackageList) {
                packageRecordTime.remove(rmPackage);
            }
            FileUtils.writeLines(new File(ConfigContainer.ignoreListPath), lines, false);
            // 如果以注释符#开始或者本身是空字符串就删除掉
            lines.removeIf(line -> line.startsWith("#") || line.equals(""));
        } catch (IOException e) {
            LogUtils.error("Load ignoreClasses from " + ConfigContainer.ignoreListPath + " failed!");
            System.exit(0);
        }
        return lines;

    }

    // 根据实际的情况更新packageRecordTime中的包出现的次数
    private static void updatePackageRecordTime(String packageName) {
        if (packageRecordTime == null) {
            packageRecordTime = new HashMap<>();
        }
        if (packageRecordTime.containsKey(packageName)) {
            int time = packageRecordTime.get(packageName);
            packageRecordTime.put(packageName, ++time);
        } else {
            packageRecordTime.put(packageName, 1);
        }
    }

    // 工具类，给定一个用.分隔的完整类名，获取其包名
    private static String getPackageName(String className) {

        if (className.equals("") || className.startsWith("#")) {
            throw new IllegalArgumentException("className非法，当前值为：" + className);
        }
        String[] classNameSplit = className.split("\\.");
        className = className.replace("." + classNameSplit[classNameSplit.length - 1], "");
        return className;
    }

    /**
     * 根据 fieldName 取出对应的 sootField
     * @param sootClass
     * @param fieldName
     * @return
     */
    public static SootField getSootFieldByName(SootClass sootClass, String fieldName){
        SootField sootField = sootClass.getFieldByNameUnsafe(fieldName);
        if (sootField != null)
            return sootField;
        // getAllDirectInterfaceAbstractClz
        for (SootClass superClass : ClassUtils.getAllSupersBySoot(sootClass)){
            sootField = superClass.getFieldByNameUnsafe(fieldName);
            if (sootField != null)
                return sootField;
        }
        return null;
    }

    public static SootClass getSootClassForSootField(SootField sootField){
        String className = sootField.getType().toString();
        if (className.endsWith("[]"))
            className = className.replaceAll("\\[\\]","");
        return Scene.v().getSootClassUnsafe(className);
    }

}
