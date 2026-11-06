package cn.edu.fudan.container;

import cn.edu.fudan.datastructure.OracleClassA;
import cn.edu.fudan.exception.validator.GeneratorException;
import cn.edu.fudan.exception.validator.ReflectionException;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.LogUtils;
import cn.edu.fudan.utils.ReflectionUtils;
import datastructrue.iocd.IOCD;
import datastructrue.iocd.unit.ClassRecord;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 用于存储单个GadgetFuzz过程中要用的字典
 */
public class JDDFuzzDictContainer {


    private static final HashSet<String> ORACLE_CLASSES = new HashSet<>(Arrays.asList(
            "cn.edu.fudan.datastructure.OracleClassA",
            "cn.edu.fudan.datastructure.OracleClassB"
    ));

    public HashSet<String> strDict = new HashSet<>(); // 方法名、字段名、类名 Thinking: 可能需要拆分
    public HashMap<String, Object>  objDict = new HashMap<>(); // ClassName - 对象
    public HashSet<JDDMethodWrapper> jddMtdDict = new HashSet<>(); // Method对象
    public HashSet<JDDClassWrapper> serializableJDDClassDict = new HashSet<>(); // 可以被序列化的Class对象
    public HashMap<String, HashSet<JDDClassWrapper>> superClzDict = new HashMap<>(); // clzName -> superClzSet(in JDDClassWrapper form)
    public HashMap<String, HashSet<JDDClassWrapper>> subClzDict = new HashMap<>(); // clzName -> subClzSet(in JDDClassWrapper form)
    public HashMap<String, HashSet<JDDClassWrapper>> nonConcreteClzAndImplClzDict = new HashMap<>(); // clzName -> implClzSet(in JDDClassWrapper form)
    public HashSet<String> visistedClz = new HashSet<>(); // 已经访问过的类名

    private boolean isSerializableNeed;

    public void doDictGeneration(IOCD iocd) throws GeneratorException {
        if (iocd == null) {
            throw new GeneratorException("传入的iocd为null，无法生成对应的字典");
        }
        // 提取Gadget相关的类名作为信息
        iocd.getGadgetClasses().forEach(clzRcd -> {
            String clzName = clzRcd.getClassName();
            // Class信息处理
            try {
                if (isSerializableNeed
                        && !ClassUtils.isImplements(ClassUtils.getClassByName(clzName), "java.io.Serializable")) {
                    return;
                }
                updateClassRelatedDict(clzRcd);
            } catch (ClassNotFoundException | GeneratorException e) {
                throw new RuntimeException(e);
            }
            // Method信息处理 Thinking: 是否考虑将子类、父类的Method都放入其中
            updateMethodRelatedDict(clzName);
            // Field信息处理
            updateFieldRelatedDict(clzName);
        });
        // Oracle类和Oracle方法的添加
        updateOracleRelatedDict(ORACLE_CLASSES);
    }

    public void flushDict(IOCD iocd) throws GeneratorException {
        strDict.clear();
        objDict.clear();
        jddMtdDict.clear();
        serializableJDDClassDict.clear();
        superClzDict.clear();
        subClzDict.clear();

        doDictGeneration(iocd);
        isSerializableNeed = iocd.isNeedSerialize();
    }

    private void updateClassRelatedDict(ClassRecord clzRcd) throws GeneratorException, ClassNotFoundException {
        String clzName = clzRcd.getClassName();
        // 更新serializableJDDClassDict & strDict
        serializableJDDClassDict.add(new JDDClassWrapper(clzRcd));
        strDict.add(clzName);
        // 更新子类、父类等相关信息
        updateClassRelatedDict(clzName);
    }

    public void updateClassRelatedDictOut(Class<?> clz) throws GeneratorException, ClassNotFoundException {
        String clzName = clz.getName();
        updateClassRelatedDict(clzName);
    }

    private void updateClassRelatedDict(String clzName) throws GeneratorException, ClassNotFoundException {
        if (visistedClz.contains(clzName)) return;
        visistedClz.add(clzName);
        if (clzName.equals("java.lang.Object")) return;
        Class<?> clz = ClassUtils.getClassByName(clzName);
        // 更新superClzDict & strDict
        HashSet<JDDClassWrapper> superClzSet = new HashSet<>();
        for (String superClzName : ClassUtils.getAllSuperClassNative(clz)) {
            if (superClzName.equals("java.lang.Object")) continue;
            if (isSerializableNeed
                    && !ClassUtils.isImplements(ClassUtils.getClassByName(superClzName), "java.io.Serializable")) {
                continue;
            }
            // visistedClz.add(superClzName);
            superClzSet.add(new JDDClassWrapper(superClzName, ClassUtils.getClassByName(superClzName)));
            strDict.add(superClzName);
            if (ClassUtils.isConcreteByClzNameStrict(superClzName)) {
                updateObjectRelatedDict(superClzName);
            }
        }
        superClzDict.put(clzName, superClzSet);
        // 更新subClzDict & strDict
        HashSet<JDDClassWrapper> subClzSet = new HashSet<>();
        HashSet<JDDClassWrapper> concreteSubClzSet = new HashSet<>();
        List<String> clzNameList = new LinkedList<>();
        clzNameList.add(clzName);
        boolean isConcrete = ClassUtils.isConcreteByClzNameStrict(clzName);
        for (String subClzName : ClassUtils.getAllSubs(clzNameList)) {
            try {
                if (isSerializableNeed
                        && !ClassUtils.isImplements(ClassUtils.getClassByName(subClzName), "java.io.Serializable")) {
                    continue;
                }
            } catch (Exception | Error e) {
                continue;
            }
            // visistedClz.add(subClzName);
            subClzSet.add(new JDDClassWrapper(subClzName, ClassUtils.getClassByName(subClzName)));
            strDict.add(subClzName);
            boolean isSubClzConcrete = ClassUtils.isConcreteByClzNameStrict(subClzName);
            if (!isConcrete && isSubClzConcrete) {
                concreteSubClzSet.add(new JDDClassWrapper(subClzName, ClassUtils.getClassByName(subClzName)));
            }
            if (isSubClzConcrete) updateObjectRelatedDict(subClzName);
        }
        subClzDict.put(clzName, subClzSet);
        nonConcreteClzAndImplClzDict.put(clzName, concreteSubClzSet);
    }

    private void updateMethodRelatedDict(String clzName) {
        // 更新需要的方法名字
        Class<?> clz = ClassUtils.getClassByName(clzName);
        for (Method mtd : clz.getDeclaredMethods()) {
            if (mtd.getParameterCount() != 0) continue; // 只取方法参数为0个的方法
            String mtdName = mtd.getName();
            String mtdSig = mtd.toString();
            jddMtdDict.add(new JDDMethodWrapper(clzName, mtdName, mtdSig, mtd));
            strDict.add(mtdName);
        }
    }

    private void updateFieldRelatedDict(String clzName) {
        // 更新需要的字段名字
        Class<?> clz = ClassUtils.getClassByName(clzName);
        for (Field field : clz.getDeclaredFields()) {
            String fieldName = field.getName();
            strDict.add(fieldName);
        }
    }

    private void updateObjectRelatedDict(String clzName) throws GeneratorException {
        if (objDict.containsKey(clzName)) return;
        // 更新需要的对象
        Class<?> clz = ClassUtils.getClassByName(clzName);
        try {
            Object obj = ReflectionUtils.createWithObjectNoArgsConstructor(clz);
            objDict.put(clzName, obj);
        } catch (ReflectionException | InstantiationError e) {
            throw new GeneratorException(e);
        }
    }

    private void updateOracleRelatedDict(HashSet<String> oracleClzNames) {
        // 更新Oracle类相关的字典值
        oracleClzNames.forEach(clzName -> {
            try {
                updateClassRelatedDict(clzName);
            } catch (GeneratorException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            updateMethodRelatedDict(clzName);
            updateFieldRelatedDict(clzName);
        });
    }

    public Object getAConcreteClzObjFromDictRandomly() throws GeneratorException, ClassNotFoundException {
        Object resObject = CommonUtils.getRandomValueFromMap(objDict);
        return resObject;
    }

    public Object getAConcreteClzObjFromDict(String clzName) throws GeneratorException, ClassNotFoundException {
        if (clzName.equals("java.lang.String")) {
            return CommonUtils.getRandomElementFromSet(strDict);
        } else if (clzName.equals("java.lang.Class")) {
            return OracleClassA.class;
        }
        if (!visistedClz.contains(clzName)) {
            updateClassRelatedDict(clzName);
        }
        return objDict.get(clzName);
    }
    public Object getANonConcreteClzImplOBjFromDict(String clzName) throws GeneratorException, ClassNotFoundException {

        if (!nonConcreteClzAndImplClzDict.containsKey(clzName)) {
            updateClassRelatedDict(clzName);
        }
        HashSet<JDDClassWrapper> implClzSet = nonConcreteClzAndImplClzDict.get(clzName);
        JDDClassWrapper clzWrap = CommonUtils.getRandomElementFromSet(implClzSet);
        if (clzWrap == null) {
            LogUtils.logWarnOnce("Can not get a concrete class " + clzName + " from dict, return null");
            return null;
        }
        Object res = objDict.get(clzWrap.clzName);
        if (res == null) {
            LogUtils.warn("Can not get a concrete object of " + clzName + " from dict, return null");
        }
        return res;
    }

    public static class JDDMethodWrapper {
        public String declaredClzName;
        public String mtdName;
        public String mtdSig;
        public Method mtd;

        public JDDMethodWrapper(String declaredClzName, String mtdName, String mtdSig, Method mtd) {
            this.declaredClzName = declaredClzName;
            this.mtdName = mtdName;
            this.mtdSig = mtdSig;
            this.mtd = mtd;
        }

        public String toString() {
            return this.mtdSig;
        }

        public boolean equals(Object obj) {
            if (obj instanceof JDDMethodWrapper) {
                JDDMethodWrapper jddMtdWrapper = (JDDMethodWrapper) obj;
                return this.mtdSig.equals(jddMtdWrapper.mtdSig);
            }
            return false;
        }

        public int hashCode() {
            return this.mtdSig.hashCode();
        }
    }

    public static class JDDClassWrapper {
        public String clzName;
        public Class<?> clz;

        public JDDClassWrapper(ClassRecord clzRcd) {
            this.clzName = clzRcd.getClassName();
            this.clz = ClassUtils.getClassByName(clzName);
        }

        public JDDClassWrapper(String clzName, Class<?> clz) {
            this.clzName = clzName;
            this.clz = clz;
        }

        public String toString() {
            return this.clzName;
        }

        public boolean equals(Object obj) {
            if (obj instanceof JDDClassWrapper) {
                JDDClassWrapper jddClzWrapper = (JDDClassWrapper) obj;
                return this.clzName.equals(jddClzWrapper.clzName);
            }
            return false;
        }

        public int hashCode() {
            return this.clzName.hashCode();
        }
    }

}
