package cn.edu.fudan.utils;


import org.reflections.Reflections;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

public abstract class ClassUtils {

    public static final String DEFAULT_MAP = "java.util.HashMap";

    public static final String SERVLET_CONTEXT = "javax.servlet.ServletContext";
    public static final String SERVLET_REQUEST = "javax.servlet.ServletRequest";
    public static final String SERVLET_RESPONSE = "javax.servlet.ServletResponse";

    public static final String EL_VALUE_EXPRESSION = "javax.el.ValueExpression";
    public static final String EL_SOLVER = "javax.el.ELResolver";
    public static final String EL_CONTEXT = "javax.el.ELContext";
    public static final String EL_FACES_CONTEXT = "javax.faces.context.FacesContext";
    public static final String EL_EXPRESSION_FACTORY = "javax.el.ExpressionFactory";

    public static final String GROOVY_METHOD_CLOSURE = "org.codehaus.groovy.runtime.MethodClosure";
    public static final String GROOVY_GSTRING = "groovy.lang.GString";
    public static final String GROOVY_GSTRING_WRITE_TO = "<groovy.lang.GString: java.io.Writer writeTo(java.io.Writer)>";

    public static final HashSet<String> PRIMITIVE_CLASS_NAME = new HashSet<>(
            Arrays.asList("java.lang.Integer", "java.lang.Boolean",
                    "java.lang.Long", "java.lang.Short", "java.lang.Float",
                    "java.lang.Double", "java.lang.Character", "java.lang.Byte")
    );

    // 在反序列化漏洞检测的语境下，我们定义java.lang.String也为 "基本" 对象
    public static final HashSet<String> PRIMITIVE_OBJECT_CLASS_NAME = new HashSet<>(
            Arrays.asList("java.lang.Integer", "java.lang.Boolean",
                    "java.lang.Long", "java.lang.Short", "java.lang.Float",
                    "java.lang.Double", "java.lang.Character", "java.lang.Byte",
                    "java.lang.String")
    );

    public static final HashSet<String> JIMEPLE_PRIMITIVE_CLASS_NAME = new HashSet<>(
            Arrays.asList("int", "boolean",
                    "long", "short", "float",
                    "double", "char", "byte")
    );

    public static final HashMap<String, String> primitiveTypeMap;

    public static final HashSet<String> jvmStylePrimitiveType = new HashSet<>(
            Arrays.asList("Z", "C", "D", "F", "I", "J", "S", "B", "V")
    );

    static {
        primitiveTypeMap = new HashMap<>();
        primitiveTypeMap.put("boolean", "Z");
        primitiveTypeMap.put("char", "C");
        primitiveTypeMap.put("double", "D");
        primitiveTypeMap.put("float", "F");
        primitiveTypeMap.put("int", "I");
        primitiveTypeMap.put("long", "J");
        primitiveTypeMap.put("short", "S");
        primitiveTypeMap.put("byte", "B");
        primitiveTypeMap.put("void", "V"); // 相同情况，所以吧void也放这里
    }

    public static boolean isArrayTypeSoot(String type) {
        return type.endsWith("[]");
    }
    public static boolean isArrayType(String type) {
        return type.startsWith("[");
    }
    public static boolean isPrimitiveType(String type) {
        return jvmStylePrimitiveType.contains(type);
    }

    // 接口实现类的缓存
    public static HashMap<String, HashSet<String>> subClassSearchRecord = new HashMap<>();


    public static Class<?> wrapperClassForName(String className) throws ClassNotFoundException {
        Class<?> resClz = null;
        try {
            resClz = Class.forName(className);
        } catch (ClassNotFoundException e) {
            resClz = Class.forName(className, false, ClassLoader.getSystemClassLoader());
        }
        return resClz;
    }

    public static HashSet<String> getAllSubs(List<String> basicTypes) {
        HashSet<String> ret = new HashSet<>();
        for (String className : basicTypes) {
            SootClass sootClass = Scene.v().getSootClassUnsafe(className);
            if (sootClass != null) {
                for (SootClass subClassName : getAllSubs(sootClass))
                    ret.add(subClassName.getName());
            }
        }

        return ret;
    }


    /**
     * 查找 className 的所有非抽象/接口子类
     *
     * @param className          父类的名称
     * @param isNeedSerializable 查找的子类是否需要实现Serializable接口
     */
    public static HashSet<String> getAllConcreteSubClass(String className, boolean isNeedSerializable) {

        HashSet<String> ret = new HashSet<>();
        // 首先获取对应ClassName的SootClass
        SootClass sootClass = Scene.v().getSootClassUnsafe(className);
        if (sootClass == null) {
            return ret;
        }
        // 而后获取所有的子类，并进行筛选
        for (SootClass subClass : getAllSubs(sootClass)) {
            // 如果是抽象类或者接口类不记录
            if (!isLoadableConcreteClass(subClass.getName())) {
                continue;
            }
            // 根据isNeedSerializable的要求检测是否实现了Serializable接口
            if (isNeedSerializable) {
                try {
                    if (!ClassUtils.isImplements(getClassByName(subClass.getName()),
                            "java.io.Serializable")) {
                        continue;
                    }
                } catch (ClassNotFoundException e) {
                    // 如果isImplements方法抛出异常，认为是false
                    LogUtils.warn("");
                    continue;
                }

            }
            ret.add(subClass.getName());
        }
        // 用Reflections库保个底
        for (String clzName : getAllSubclassesAndImplementations(className)) {
            if (!isLoadableConcreteClass(clzName)) {
                continue;
            }
            ret.add(clzName);
        }
        return ret;
    }

    /**
     * A concrete class that can be loaded and initialized in this JVM. A class whose loading fails with a
     * LinkageError (a missing optional dependency, e.g. dom4j's jaxen/msv classes, or a native library, e.g.
     * X11's in a headless container) cannot be a candidate implementation: choosing it made the whole seed fail.
     */
    private static boolean isLoadableConcreteClass(String clzName) {
        try {
            return isConcreteByClzName(clzName);
        } catch (LinkageError e) {
            LogUtils.logWarnOnce("In `ClassUtils.getAllConcreteSubClass`: skip " + clzName + ", it can not be loaded: " + e);
            return false;
        }
    }

    /**
     * 给定一个Class对象，返回所有子类Class对象，包括非Concrete的类
     *
     * @param clazz
     * @return
     */
    public static HashSet<Class<?>> getAllSubClassWithoutAbstract(Class<?> clazz) {
        HashSet<String> subClassNames = getAllConcreteSubClass(clazz.getName(), false);
        HashSet<Class<?>> ret = new HashSet<>();
        for (String className : subClassNames) {
            ret.add(getClassByName(className));
        }
        return ret;
    }

    /**
     * 判断clz是否是InterfaceName的实现类
     *
     * @param clz           需要判断的类
     * @param interfaceName 需要判断的接口
     * @return 是否是接口实现关系
     */
    public static boolean isImplements(Class<?> clz, String interfaceName) throws ClassNotFoundException {

        if (!ClassUtils.isInterfaceByClzName(interfaceName)) {
            LogUtils.warn2Log("In `ClassUtils.isImplements`" + interfaceName + "is not a interface, return flase");
            return false;
        }
        // 从缓存中查找
        if (subClassSearchRecord != null
                && subClassSearchRecord.containsKey(interfaceName)) {
            return subClassSearchRecord.get(interfaceName).contains(clz.getName());
        }
        // 缓存中如果不存在对应interfaceName的接口实现类，就全局搜索并且保存到缓存中去
        subClassSearchRecord.put(interfaceName, getAllSubs(Collections.singletonList(interfaceName)));
        return subClassSearchRecord.get(interfaceName).contains(clz.getName());

    }

    /**
     * 判断clzB是否是clzA的子类或者实现类
     *
     * @param clzA
     * @param clzB
     * @return
     */
    public static boolean isSubOrImplClass(String clzA, String clzB) {

        try {
            Class<?> classA = wrapperClassForName(clzA);
            Class<?> classB = wrapperClassForName(clzB);
            return isSubOrImplClass(classA, classB);
        } catch (ClassNotFoundException e) {
            LogUtils.warn2Log("In 'ClassUtils.isSubOrImplClass' Class not found: " + clzA + " or " + clzB);
        }
        return false;
    }

    public static boolean isSubOrImplClass(Class<?> clzA, Class<?> clzB) {
        if (clzA.isAssignableFrom(clzB)) {
            return true;
        }
        return false;
    }

    /**
     * 给定一个类名，返回是否是Concrete的类
     * 这里的定义是严格符合JVM规范的
     *
     * @param clzName
     * @return
     */
    public static boolean isConcreteByClzNameStrict(String clzName) {
        try {
            Class<?> clazz = wrapperClassForName(clzName);
            // 直接使用原生的反射API进行判断
            return !clazz.isInterface() &&
                    !Modifier.isAbstract(clazz.getModifiers());
        } catch (ClassNotFoundException e) {
            // 如果类没有存在于JVM以及ClassPath中，那么直接返回false
            LogUtils.warn2Log("In 'ClassUtils.isConcreteByClzName' Class not found: " + clzName + " return false");
            return false;
        }
    }

    /**
     * 给定一个类名，返回是否是Concrete的类
     * 这里的定义是非接口、非抽象类、非枚举类、非数组类、非int.class等基本类型才算枚举类
     *
     * @param clzName
     * @return
     */
    public static boolean isConcreteByClzName(String clzName) {

        try {
            Class<?> clazz = wrapperClassForName(clzName);
            // 直接使用原生的反射API进行判断
            return !clazz.isInterface() &&
                    !Modifier.isAbstract(clazz.getModifiers()) &&
                    !clazz.isEnum() &&
                    !clazz.isArray() &&
                    !clazz.isPrimitive(); // 如果是Integer，这里为false
        } catch (ClassNotFoundException e) {
            // 如果类没有存在于JVM以及ClassPath中，那么直接返回false
            LogUtils.warn2Log("In 'ClassUtils.isConcreteByClzName' Class not found: " + clzName + " return false");
            return false;
        }

    }

    /**
     * @param clz
     * @return
     */
    public static boolean isPrimitiveObjectStrict(Class<?> clz) {
        String crtFldClzName = clz.getName();
        if (!crtFldClzName.equals("java.lang.String") && PRIMITIVE_OBJECT_CLASS_NAME.contains(crtFldClzName)) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean isJdkObject(String clzName) {
        return clzName.equals("java.lang.Object");
    }

    /**
     * @param clz
     * @return
     */
    public static boolean isPrimitiveObject(Class<?> clz) {
        String crtFldClzName = clz.getName();
        if (PRIMITIVE_OBJECT_CLASS_NAME.contains(crtFldClzName)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * 给定一个类名，判断其对应的类是否是接口
     *
     * @param clzName
     * @return
     */
    public static boolean isInterfaceByClzName(String clzName) throws ClassNotFoundException {

        // 显然，数组类型的值不是接口
        if (clzName.startsWith("[")) {
            return false;
        }
        Class<?> clz = getClassByName(clzName);
        if (clz == null) {
            throw new ClassNotFoundException(clzName);
        }
        return clz.isInterface();

    }

    /**
     * 判断一个field是否是static final的
     * @param fld
     * @return
     */
    public static boolean isFinalAndStaticField(Field fld) {
        return Modifier.isFinal(fld.getModifiers()) && Modifier.isStatic(fld.getModifiers());
    }

    /**
     * @param clzName
     * @return
     */
    public static boolean isInvocationHandlerByClzName(String clzName) throws ClassNotFoundException {
        Class<?> clz = getClassByName(clzName);
        if (clz == null) {
            throw new ClassNotFoundException(clzName);
        }
        if (InvocationHandler.class.isAssignableFrom(clz)) {
            return true;
        }
        return false;
    }

    /**
     * check if arrayType2 is the same type as arrayType1,
     * or arrayType2 is the sub class(component type) of arrayType1
     * 
     * @param arrayType1
     * @param arrayType2
     * @return
     */
    public static boolean isArrayTypeMatch (Class<?> arrayType1, Class<?> arrayType2) {
        if (arrayType1.isArray() && arrayType2.isArray()) {
            return arrayType1.getComponentType().isAssignableFrom(arrayType2.getComponentType());
        }
        return false;
    }

    /**
     * 该方法主要是为了处理soot解析过程中出现的各种不符合Java原生规范的类
     *
     * @param className
     * @return
     */
    public static Class<?> getClassByName(String className) {

        Class resClz = null;
        className = className.replaceAll("\"", "");
        className = className.replace("'", "");
        if (className.contains("[]")) {
            className = className.replace("[]", "");
            className = "[L" + className + ";";
        }
        try {
            resClz = wrapperClassForName(className);
        } catch (ClassNotFoundException e) {
            // Utils.printStackTraceWithExit(e);
            // throw e;
            LogUtils.warn2Log("In `ClassUtils.getClassByName`" + className);
        }

        return resClz;
    }

    /**
     * 由于Soot的解析可能会有bug，因此基于反射再实现一个
     *
     * @param clzName 需要获取父类的类的名字
     * @return clzName对应类的所有的父类，包括实类、抽象类和接口类
     */
    public static HashSet<String> getAllSupersNative(String clzName) {
        HashSet<String> res = new HashSet<>();
        try {
            Class<?> clazz = wrapperClassForName(clzName);
            res = getAllSuperClassNative(clazz);
            // getAllSuperClassNative方法每次会将自身加进set中，因此要删除最初的基类
            res.remove(clzName);
        } catch (ClassNotFoundException e) {
            LogUtils.warn2Log("In `ClassUtils.getAllSupersNative`: can not find " + clzName + " in current context of JVM and classpath");
        }
        return res;
    }

    /**
     * 反射获取所有父类
     *
     * @param clazz 需要获取父类的
     * @return 所有的父类的名字集合，包括抽象类与接口
     */
    public static HashSet<String> getAllSuperClassNative(Class<?> clazz) {
        HashSet<String> res = new HashSet<>();
        // 添加当前类
        res.add(clazz.getName());

        // 获取直接父类
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null) {
            res.addAll(getAllSuperClassNative(superClass)); // 递归获取父类的父类
        }

        // 获取实现的接口
        Class<?>[] interfaces = clazz.getInterfaces();
        for (Class<?> anInterface : interfaces) {
            res.add(anInterface.getName());
            res.addAll(getAllSuperInterfaceNative(anInterface)); // 获取接口的父接口
        }

        return res;
    }

    /**
     * 获取所有的父接口
     *
     * @param intf 接口类
     * @return 所有父接口的名字
     */
    public static HashSet<String> getAllSuperInterfaceNative(Class<?> intf) {
        HashSet<String> res = new HashSet<>();

        Class<?>[] superInterfaces = intf.getInterfaces();
        for (Class<?> superIntf : superInterfaces) {
            res.add(superIntf.getName());
            res.addAll(getAllSuperInterfaceNative(superIntf)); // 递归获取父接口的父接口
        }

        return res;
    }

    /**
     * 基于Soot来帮助查找某个类所有的父类和实现的接口类
     *
     * @param sootClass 需要查找父类的原始类
     * @return sootClass所有的父类，包括实类、抽象类和接口类
     */
    public static HashSet<SootClass> getAllSupersBySoot(SootClass sootClass) {
        HashSet<SootClass> res = new HashSet<>();
        getAllSuperClassAndInterfacesBySoot(sootClass, res);
        return res;
    }

    /**
     * 被getAllSupers封装的方法
     *
     * @param sootClass 需要查找父类的原始类
     * @param result    sootClass所有的父类，包括抽象类和接口类
     */
    private static void getAllSuperClassAndInterfacesBySoot(SootClass sootClass, HashSet<SootClass> result) {
        if (result.contains(sootClass)) {
            return;
        }
        result.add(sootClass);
        if (sootClass.isInterface()) {
            for (SootClass superClass : Scene.v().getActiveHierarchy().getSuperinterfacesOf(sootClass)) {
                getAllSuperClassAndInterfacesBySoot(superClass, result);
            }
        } else {
            for (SootClass superClass : Scene.v().getActiveHierarchy().getSuperclassesOf(sootClass)) {
                getAllSuperClassAndInterfacesBySoot(superClass, result);
            }
        }
    }

    public static HashSet<SootClass> getAllSubs(SootClass sootClass) {
        HashSet<SootClass> res = new HashSet<>();
        getAllSubClassAndInterfaces(sootClass, res);
        return res;
    }

    /**
     * 查找实现了sootClass的所有子类
     */
    private static void getAllSubClassAndInterfaces(SootClass sootClass, HashSet<SootClass> result) {
        if (result.contains(sootClass)) return;
        result.add(sootClass);
        if (sootClass.isInterface()) {
            for (SootClass superClass : Scene.v().getActiveHierarchy().getSubinterfacesOf(sootClass)) {
                getAllSubClassAndInterfaces(superClass, result);
            }
            for (SootClass superClass : Scene.v().getActiveHierarchy().getImplementersOf(sootClass)) {
                getAllSubClassAndInterfaces(superClass, result);
            }
        } else {
            try {
                for (SootClass superClass : Scene.v().getActiveHierarchy().getSubclassesOf(sootClass)) {
                    getAllSubClassAndInterfaces(superClass, result);
                }
            } catch (Exception e) {
//                e.printStackTrace(); 报错即是没有该类的sub class，所以会报NullPointerException
            }
        }
    }

    /**
     * 基于Soot的getAllSubClassAndInterfaces过于不可靠，因此使用Reflections库再写一个
     *
     * @param fullClassName
     */
    public static Set<String> getAllSubclassesAndImplementations(String fullClassName) {

        HashSet<String> subClzNameSet = new HashSet<>();
        try {
            // Load the class
            Class<?> clazz = ClassUtils.wrapperClassForName(fullClassName);

            // Get the package name
            String packageName = clazz.getPackage().getName();

            // Use Reflections to scan the classpath for subclasses and implementing classes
            Reflections reflections = new Reflections(packageName);
            Set<Class<?>> subTypes = reflections.getSubTypesOf((Class<Object>) clazz);

            for (Class<?> subClass : subTypes) {
                if (ClassUtils.isConcreteByClzName(subClass.getName())) {
                    subClzNameSet.add(subClass.getName());
                }
            }
        } catch (ClassNotFoundException e) {
            LogUtils.warn2Log("In 'ClassUtils.getAllSubclassesAndImplementations' Class not found: " + fullClassName);
        }

        return subClzNameSet;

    }

    /**
     * 广度优先的顺序逐层得到所有父类,大部分情况下（不考虑接口Default方法），他应该是一条链
     */
    public static List<SootClass> getAllSupers_BFS(SootClass sootClass) {
        List<SootClass> ret = new LinkedList<>();
        Queue<SootClass> waiting = new LinkedList<>();
        waiting.add(sootClass);
        while (!waiting.isEmpty()) {
            SootClass clazz = waiting.poll();
            ret.add(clazz);
            if (!sootClass.isInterface()) {
                if (sootClass.hasSuperclass() && !waiting.contains(sootClass.getSuperclass()) && !ret.contains(sootClass.getSuperclass()))
                    waiting.add(sootClass.getSuperclass());
            }
        }
        return ret;
    }

    /**
     * 广度优先的顺序逐层得到所有子类
     */
    public static List<SootClass> getAllSubs_BFS(SootClass sootClass) {
        List<SootClass> ret = new LinkedList<>();
        Queue<SootClass> waiting = new LinkedList<>();
        waiting.add(sootClass);
        while (!waiting.isEmpty()) {
            SootClass clazz = waiting.poll();
            ret.add(clazz);
            if (sootClass.isInterface()) {
                for (SootClass subClass : Scene.v().getActiveHierarchy().getDirectSubinterfacesOf(sootClass)) {
                    if (!waiting.contains(subClass) && !ret.contains(subClass)) waiting.add(subClass);
                }
                for (SootClass subClass : Scene.v().getActiveHierarchy().getDirectImplementersOf(sootClass)) {
                    if (!waiting.contains(subClass) && !ret.contains(subClass)) waiting.add(subClass);
                }
            } else {
                for (SootClass subClass : Scene.v().getActiveHierarchy().getDirectSubclassesOf(sootClass)) {
                    if (!waiting.contains(subClass) && !ret.contains(subClass)) waiting.add(subClass);
                }
            }
        }
        return ret;
    }

    public static Field[] getAllDeclaredField(Class<?> targetClz) {
        return ReflectionUtils.getAllFields(targetClz);
    }

    /**
     * 从用户指定的classPath中获取路径，仅支持jar包和包含jar包的文件夹
     *
     * @param clzName
     * @param classPath
     * @return
     * @throws IOException
     */
    public static byte[] getSpecificClassByteCodes(String clzName, HashSet<String> classPath) throws IOException {

        byte[] bytecode = null;
        for (String path : classPath) {
            Path crtPath = Paths.get(path);
            if (path.endsWith(".jar")) {
                JarFile jarFile = new JarFile(path);
                bytecode = getSpecificClassByteCodes0(jarFile, clzName);
            } else if (Files.isDirectory(crtPath)) {
                List<File> jarFiles = FileUtils.getAllJarFiles(path);
                for (File jarFile : jarFiles) {
                    bytecode = getSpecificClassByteCodes0(new JarFile(jarFile), clzName);
                    if (bytecode != null) {
                        break;
                    }
                }
            }
            if (bytecode != null) {
                break;
            }
        }
        return bytecode;
    }

    public static byte[] getSpecificClassByteCodes0(JarFile jarFile, String clzName) throws IOException {
        byte[] bytecode = null;
        JarEntry jarEntry = jarFile.getJarEntry(clzName.replace('.', '/') + ".class");
        if (jarEntry != null) {
            InputStream inputStream = jarFile.getInputStream(jarEntry);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

            int bufferSize = 4096;
            byte[] buffer = new byte[bufferSize];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer, 0, bufferSize)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }

            bytecode = byteArrayOutputStream.toByteArray();
            byteArrayOutputStream.close();
            inputStream.close();
        }
        return bytecode;
    }
    /**
     * 加载指定路径下所有Class文件
     *
     * @param classPath
     */
    public static void loadClassInPath(String classPath) {
        // 设置class文件所在根路径
        // 例如/usr/java/classes下有一个test.App类，则/usr/java/classes即这个类的根路径，
        // 而.class文件的实际位置是/usr/java/classes/test/App.class
        File clazzPath = new File(classPath);

        // 记录加载.class文件的数量
        int clazzCount = 0;

        if (clazzPath.exists() && clazzPath.isDirectory()) {
            // 获取路径长度
            int clazzPathLen = clazzPath.getAbsolutePath().length() + 1;

            Stack<File> stack = new Stack<>();
            stack.push(clazzPath);

            // 遍历类路径
            while (stack.isEmpty() == false) {
                File path = stack.pop();
                File[] classFiles = path.listFiles(pathname -> pathname.isDirectory() || pathname.getName().endsWith(".class"));
                for (File subFile : classFiles) {
                    if (subFile.isDirectory()) {
                        stack.push(subFile);
                    } else {
                        try {
                            if (clazzCount++ == 0) {
                                Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                                boolean accessible = method.isAccessible();
                                try {
                                    if (accessible == false) {
                                        method.setAccessible(true);
                                    }
                                    // 设置类加载器
                                    URLClassLoader classLoader = (URLClassLoader) ClassLoader.getSystemClassLoader();
                                    // 将当前类路径加入到类加载器中
                                    method.invoke(classLoader, clazzPath.toURI().toURL());
                                    // the extracted tmp/ dir holds only .class files (FileUtils.decompressJar skips
                                    // META-INF and other resources), so put the original jars after it: classes still
                                    // come from tmp/, resources such as groovy's META-INF/dgminfo from the jars
                                    File[] jars = clazzPath.getAbsoluteFile().getParentFile().listFiles(
                                            f -> f.isFile() && f.getName().endsWith(".jar"));
                                    if (jars != null) {
                                        Arrays.sort(jars);
                                        for (File jar : jars) {
                                            method.invoke(classLoader, jar.toURI().toURL());
                                        }
                                    }
                                } catch (IllegalAccessException | InvocationTargetException | MalformedURLException e) {
                                    LogUtils.warn2Log("In `ClassUtils.loadClassInPath`: 加载类路径" + classPath + "过程中出错:" + e);
                                } finally {
                                    method.setAccessible(accessible);
                                }
                            }
                            // 文件名称
                            String className = subFile.getAbsolutePath();
                            className = className.substring(clazzPathLen, className.length() - 6);
                            className = className.replace(File.separatorChar, '.');
                            // 加载Class类
                            try {
                                Class.forName(className, false, ClassLoader.getSystemClassLoader());
                            } catch (ClassNotFoundException e) {
                                LogUtils.warn("ClassNotFoundException:" + e);
                            } catch (Error error) {
                                LogUtils.warn("In `ClassUtils`: 加载" + className +  "过程中出错: " + error);
                            }
                            // LogUtils.debug2Log("读取应用程序类文件[class=" + className + "]");
                        } catch (NoSuchMethodException e) {
                            LogUtils.warn2Log("In `ClassUtils.loadClassInPath`: 加载类" + subFile.getAbsolutePath() + "过程中出错:" + e);
                        }


                    }
                }
            }
        }
    }

    /**
     * 给定一个Jimple格式或者普通Java类格式的类名，返回Class.forName中需要的类型
     * 如java.util.HashMap$Node[] -> [Ljava.util.HashMap$Node
     * 如int -> java.lang.Integer
     *
     * @param jimpleType
     * @return
     */
    public static String transformTypeForJimple(String jimpleType) {

        String formatFldTypeStr;
        if (jimpleType.contains("[]")) {
            formatFldTypeStr = getClassDescriptorBySig(jimpleType);
            formatFldTypeStr = formatFldTypeStr.replace("/", ".");
        } else {
            formatFldTypeStr = transformPrimitiveTypeForJimple(jimpleType);
        }

        return formatFldTypeStr;

    }

    /**
     * 给定一个Jimple格式或者普通Java类格式的类名（通常是fld/mtd的描述符），返回符合JVM规范的方法签名中需要的字段
     * 如java.util.HashMap$Node[] -> [Ljava/util/HashMap$Node
     *
     * @param filedSig
     * @return
     */
    public static String getClassDescriptorBySig(String filedSig) {

        StringBuilder resSB = new StringBuilder();

        // 如果是空字符串说明没有传入任何的参数
        if (filedSig.equals("")) {
            return "";
        }

        // 判断是否是数组
        if (filedSig.contains("[")) {
            int oldCnt = filedSig.length();
            int newCnt = filedSig.replace("[", "").length();
            int num = oldCnt - newCnt;
            // 如果num不是0，说明返回值是数组，于是根据对应的数量添加[
            if (num != 0) {
                for (int i = 0; i < num; i++) {
                    resSB.append("[");
                }
            }
        }
        filedSig = filedSig.replace("[", "");
        filedSig = filedSig.replace("]", "");
        // 转换类型
        if (primitiveTypeMap.containsKey(filedSig)) {
            resSB.append(primitiveTypeMap.get(filedSig));
        } else {
            resSB.append("L");
            resSB.append(filedSig.replace(".", "/"));
            resSB.append(";");
        }

        return resSB.toString();
    }

    /**
     * 给定一个Class，得到其非包装类型名
     *
     * @param value
     * @return
     */
    public static String getPrimitiveTypeString(Object value) {
        Class clz;
        if (value instanceof Class) {
            clz = (Class)value;
        } else {
            clz = value.getClass();
        }
        if (clz.equals(Integer.class) || value == int.class) {
            return "int";
        } else if (clz.equals(Double.class) || value == double.class) {
            return "double";
        } else if (clz.equals(Boolean.class) || value == boolean.class) {
            return "boolean";
        } else if (clz.equals(Character.class) || value == char.class) {
            return "char";
        } else if (clz.equals(Byte.class) || value == byte.class) {
            return "byte";
        } else if (clz.equals(Short.class) || value == short.class) {
            return "short";
        } else if (clz.equals(Long.class) || value == long.class) {
            return "long";
        } else if (clz.equals(Float.class) || value == float.class) {
            return "float";
        } else {
            return "unknown";
        }
    }

    /**
     * 给定Jimple版本的基本类型名，返回其装箱版本的类
     *
     * @param jimpleType
     * @return
     */
    public static String transformPrimitiveTypeForJimple(String jimpleType) {

        if (!JIMEPLE_PRIMITIVE_CLASS_NAME.contains(jimpleType)) {
            return jimpleType;
        }
        String resClzStr = null;
        switch (jimpleType) {
            case ("int"):
                resClzStr = "java.lang.Integer";
                break;
            case ("boolean"):
                resClzStr = "java.lang.Boolean";
                break;
            case ("long"):
                resClzStr = "java.lang.Long";
                break;
            case ("short"):
                resClzStr = "java.lang.Short";
                break;
            case ("float"):
                resClzStr = "java.lang.Float";
                break;
            case ("double"):
                resClzStr = "java.lang.Double";
                break;
            case ("char"):
                resClzStr = "java.lang.Character";
                break;
            case ("byte"):
                resClzStr = "java.lang.Byte";
        }
        return resClzStr;

    }

    /**
     * 给定一个装箱的基本类型类，返回其拆箱版本
     *
     * @param clz
     * @return
     * @throws ClassNotFoundException
     */
    public static Class<?> transformPrimitiveTypeToUnbox(Class<?> clz) {

        String clzTypeName = clz.getName();
        // 不是基本类型直接返回
        if (!PRIMITIVE_CLASS_NAME.contains(clzTypeName)) {
            return clz;
        }
        Class<?> resClz = null;
        switch (clzTypeName) {
            case ("java.lang.Integer"):
                resClz = int.class;
                break;
            case ("java.lang.Boolean"):
                resClz = boolean.class;
                break;
            case ("java.lang.Long"):
                resClz = long.class;
                break;
            case ("java.lang.Short"):
                resClz = short.class;
                break;
            case ("java.lang.Float"):
                resClz = float.class;
                break;
            case ("java.lang.Double"):
                resClz = double.class;
                break;
            case ("java.lang.Character"):
                resClz = char.class;
                break;
            case ("java.lang.Byte"):
                resClz = byte.class;
        }
        return resClz;
    }

    /**
     * 将String类型的值转为对应type的primitive object
     *
     * @param target
     * @param type
     * @return
     */
    public static Object transformStringToPrimitive(String target, Class<?> type) {

        Object res = null;
        if (type.equals(boolean.class)) {
            res = Boolean.parseBoolean(target);
        } else if (type.equals(char.class)) {
            res = target.charAt(0);
        } else if (type.equals(short.class)) {
            res = Short.parseShort(target);
        } else if (type.equals(long.class)) {
            res = Long.parseLong(target);
        } else if (type.equals(int.class)) {
            res = Integer.parseInt(target);
        } else if (type.equals(float.class)) {
            res = Float.parseFloat(target);
        } else if (type.equals(double.class)) {
            res = Double.parseDouble(target);
        } else if (type.equals(byte.class)) {
            res = Byte.parseByte(target);
        }
        return res;

    }

    public static boolean isSootMethodSigmatchRule(SootMethod sootMethod, String rule) {
        return Pattern.matches(rule, sootMethod.getName());
    }

}
