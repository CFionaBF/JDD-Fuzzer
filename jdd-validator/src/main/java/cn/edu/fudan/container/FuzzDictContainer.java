package cn.edu.fudan.container;

import cn.edu.fudan.datastructure.OracleClassA;
import cn.edu.fudan.exception.validator.GeneratorException;
import cn.edu.fudan.exception.validator.ReflectionException;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.LogUtils;
import cn.edu.fudan.utils.ReflectionUtils;
import cn.edu.fudan.utils.SootUtils;
import datastructrue.iocd.IOCD;
import datastructrue.iocd.unit.ClassRecord;
import soot.Scene;
import soot.SootClass;
import soot.SootField;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

public class FuzzDictContainer {

    public HashSet<String> stringDic = new HashSet<>();
    public HashSet<Method> methodDic = new HashSet<>();
    public List<Object> objectDic = new LinkedList<>();
    public HashSet<Class<?>> classDic = new HashSet<>();

    public static HashMap<String, HashSet<String>> superClasses = new HashMap<>();
    public static HashMap<String, HashSet<String>> subClasses = new HashMap<>();
    public static HashSet<String> serClassNames = new HashSet<>();

    public void addInfos(Class<?> clazz, SootClass sootClass) throws ReflectionException {
        for (Field field : clazz.getDeclaredFields()) {
            stringDic.add(field.getName()); // field name

            // 记录field的类型信息
            SootField sootField = SootUtils.getSootFieldByName(sootClass, field.getName());
            SootClass fieldClass = SootUtils.getSootClassForSootField(sootField);
            if (!superClasses.containsKey(fieldClass.getName())) {
                superClasses.put(fieldClass.getName(), new HashSet<>());
                for (SootClass superClazz : ClassUtils.getAllSupersBySoot(fieldClass)) {
                    superClasses.get(fieldClass.getName()).add(superClazz.getName());
                }
            }
            if (!subClasses.containsKey(fieldClass.getName()) & !fieldClass.getName().equals("java.lang.Object")) {
                subClasses.put(fieldClass.getName(), new HashSet<>());
                for (SootClass subClass : ClassUtils.getAllSubs(sootClass)) {
                    subClasses.get(fieldClass.getName()).add(subClass.getName());
                }
            }

        }

        for (Method method : clazz.getMethods()) {
            stringDic.add(method.getName());
            if (method.getParameterCount() == 0)
                methodDic.add(method);
        }

        if (!ClassUtils.isConcreteByClzNameStrict(clazz.getName())) {
            Object obj = ReflectionUtils.createWithNoArgsConstructor(clazz);
            if (obj != null) {
                // clazz -> 5,6 C1,C2,C3 :C1: clazz // .get()
                // list<> field ->
                if (serClassNames.contains(clazz.getName()))
                    objectDic.add(obj);
            }
        }
        objectDic.add(new byte[]{1,2,3,4});
        objectDic.add(new OracleClassA());
    }

    public void flushDict(IOCD iocd) throws ReflectionException, GeneratorException {
        stringDic.clear();
        methodDic.clear();
        objectDic.clear();
        classDic.clear();

        makeDictionary(iocd);
    }

    public void makeDictionary(IOCD iocd) throws ReflectionException, GeneratorException {
        if (iocd == null) {
            throw new GeneratorException("传入的iocd为null，无法生成对应的字典");
        }

        // 遍历每个Backbone Object的Class
        for (ClassRecord clzRcd : iocd.getGadgetClasses()) {
            Class<?> clazz = ClassUtils.getClassByName(clzRcd.getClassName());
            SootClass sootClass = Scene.v().getSootClassUnsafe(clazz.getName());
            if (sootClass == null) {
                LogUtils.warn("SootClass is null for class: " + clazz.getName());
                return;
            }
            // 记录 super class - class - sub class 关系
            // 如果原本不存在就缓存
            if (!superClasses.containsKey(clazz.getName())) {
                superClasses.put(clazz.getName(), new HashSet<>());
                for (SootClass superClazz : ClassUtils.getAllSupersBySoot(sootClass)) {
                    superClasses.get(clazz.getName()).add(superClazz.getName());
                    stringDic.add(superClazz.getName());

                    try {
                        Class superClazzTmp = ClassUtils.getClassByName(superClazz.getName());
                        if (superClazzTmp != null) {
                            classDic.add(superClazzTmp);
                            addInfos(superClazzTmp, superClazz);
                        }
                    } catch (Exception | Error e) {
                    }

                }
            }

            if (!subClasses.containsKey(clazz.getName()) && !clazz.getName().equals("java.lang.Object")) {
                subClasses.put(clazz.getName(), new HashSet<>());
                for (SootClass subClass : ClassUtils.getAllSubs(sootClass)) {
                    subClasses.get(clazz.getName()).add(subClass.getName());
                    stringDic.add(subClass.getName());

                    try {
                        Class subClazzTmp = ClassUtils.getClassByName(subClass.getName());
                        if (subClazzTmp != null) {
                            classDic.add(subClazzTmp);
                            addInfos(subClazzTmp, subClass);
                        }
                    } catch (Exception | Error error) {
                    }

                }
            }

            classDic.add(clazz);
            // 类名
            stringDic.add(clazz.getName());
            // 属性名
            for (Field field : clazz.getFields()) {
                stringDic.add(field.getName());

                SootField sootField = SootUtils.getSootFieldByName(sootClass, field.getName());
                if (sootField == null) {
                    LogUtils.warn("SootField is null for field: " + field.getName() + ", skip it.");
                    continue;
                }
                SootClass fieldClass = SootUtils.getSootClassForSootField(sootField);
                if (!superClasses.containsKey(fieldClass.getName())) {
                    superClasses.put(fieldClass.getName(), new HashSet<>());
                    for (SootClass superClazz : ClassUtils.getAllSupersBySoot(fieldClass)) {
                        superClasses.get(fieldClass.getName()).add(superClazz.getName());
                    }
                }
                if (!subClasses.containsKey(fieldClass.getName()) & !fieldClass.getName().equals("java.lang.Object")) {
                    subClasses.put(fieldClass.getName(), new HashSet<>());
                    for (SootClass subClass : ClassUtils.getAllSubs(sootClass)) {
                        subClasses.get(fieldClass.getName()).add(subClass.getName());
                    }
                }

            }
            // 方法名 & 方法
            for (Method method : clazz.getMethods()) {
                stringDic.add(method.getName());
                if (method.getParameterCount() == 0)
                    methodDic.add(method);
            }

            // 实例对象
            // if (!ClassUtils.isConcreteByClzNameStrict(clazz.getName())) {
            //     Object object = ReflectionUtils.createWithNoArgsConstructor(clazz);
            //     if (object != null) {
            //         // clazz -> 5,6 C1,C2,C3 :C1: clazz // .get()
            //         // list<> field ->'
            //         if (serClassNames.contains(clazz.getName()))
            //             objectDic.add(object);

            //     }
            // }
        }
    }

}
