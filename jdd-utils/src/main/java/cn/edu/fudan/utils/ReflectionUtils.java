package cn.edu.fudan.utils;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

import com.nqzero.permit.Permit;

import cn.edu.fudan.datastructure.ConcurrentReferenceHashMap;
import cn.edu.fudan.datastructure.NoneObject;
import cn.edu.fudan.exception.validator.ReflectionException;
import sun.reflect.ReflectionFactory;

public abstract class ReflectionUtils {

    private static final Map<Class<?>, Field[]> declaredFieldsCache = new ConcurrentReferenceHashMap(256);
    private static final Field[] EMPTY_FIELD_ARRAY = new Field[0];


    public static Field findField(Class<?> clazz, String name) throws ReflectionException {
        return findField(clazz, name, (Class) null);
    }


    public static Field findField(Class<?> clazz, String name, Class<?> type) throws ReflectionException {
        if (clazz == null) {
            throw new ReflectionException("Class must not be null");
        }
        if (name == null && type == null) {
            throw new ReflectionException("Either name or type of the field must be specified");
        }


        for (Class searchType = clazz; Object.class != searchType && searchType != null; searchType = searchType.getSuperclass()) {
            Field[] fields = getDeclaredFields(searchType);
            Field[] var5 = fields;
            int var6 = fields.length;

            for (int var7 = 0; var7 < var6; ++var7) {
                Field field = var5[var7];
                if ((name == null || name.equals(field.getName())) && (type == null || type.equals(field.getType()))) {
                    return field;
                }
            }
        }

        return null;
    }

    /**
     * 判断一个类（包括其父类）是否存在某个特定名字的field
     *
     * @param clz
     * @param fldName
     * @return
     */
    public static boolean isFieldExist(Class<?> clz, String fldName) {
        Object resFld;
        try {
            resFld = findField(clz, fldName);
        } catch (ReflectionException e) {
            // Thinking: 如果出现了反射错误，那么当做不存在处理应该问题不大
            return false;
        }
        return resFld != null;
    }

    /**
     * 获取包括自身和所有父类在内的所有field
     *
     * @param clazz
     * @return
     */
    public static Field[] getAllFields(Class<?> clazz) {
        HashSet<Field> fields = new HashSet<>();

        // 处理当前类的字段
        Field[] declaredFields = clazz.getDeclaredFields();
        fields.addAll(Arrays.asList(declaredFields));

        // 处理父类的字段
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null) {
            Field[] superFields = getAllFields(superClass);
            fields.addAll(Arrays.asList(superFields));
        }

        return fields.toArray(new Field[0]);
    }

    private static Field[] getDeclaredFields(Class<?> clazz) throws ReflectionException {
        if (clazz == null) {
            throw new ReflectionException("Class must not be null");
        }
        Field[] result = (Field[]) declaredFieldsCache.get(clazz);
        if (result == null) {
            try {
                result = clazz.getDeclaredFields();
                declaredFieldsCache.put(clazz, result.length == 0 ? EMPTY_FIELD_ARRAY : result);
            } catch (Throwable var3) {
                throw new IllegalStateException("Failed to introspect Class [" + clazz.getName() + "] from ClassLoader [" + clazz.getClassLoader() + "]", var3);
            }
        }

        return result;
    }

    /**
     * 给定一个class，获取其中包含的指定fieldName的Field对象
     *
     * @param clazz
     * @param fieldName
     * @return
     */
    public static Field getField(final Class<?> clazz, final String fieldName) throws ReflectionException {
        Field field = null;
        try {
            field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            // return field;
        } catch (NoSuchFieldException e) {
            if (!clazz.getSuperclass().equals(Object.class)) {
                return getField(clazz.getSuperclass(), fieldName);
            } else {
                throw new ReflectionException("获取" + clazz.getName() + "中的" + fieldName + "失败：" + e.getMessage());
            }
        }
        return field;
    }

    // Notice: getFieldByType和setFieldByType都是用于消融实验的，非JDD的核心方法
    /**
     * 获取clazz中类型为type的field
     *
     * @param clazz
     * @param type
     * @return
     * @throws ReflectionException
     */
    public static Field getFieldByType(Class<?> clazz, Class<?> type) throws ReflectionException {
        Field fld = findField(clazz, (String) null, type);
        // 不考虑层级结构，那就直接找到一个java.lang.Object的类返回就行
        if (fld == null) {
            fld = findField(clazz, (String) null, java.lang.Object.class);
        }
        if (fld == null) {
            throw new ReflectionException("获取" + clazz.getName() + "中的" + type.getName() + "失败");
        } else {
            return fld;
        }
    }

    public static void setFieldByType(Object obj, Class<?> type, Object value) throws ReflectionException {
        Field fld = getFieldByType(obj.getClass(), type);
        setFieldValue(obj, fld, value);
    }

    /**
     * 根据fld获取其对应的obj中的值
     *
     * @param obj
     * @param fld
     * @return
     */
    public static Object getFieldValue(Object obj, Field fld) throws ReflectionException {

        Object resObj = null;
        try {
            fld.setAccessible(true);
            resObj = fld.get(obj);
        } catch (IllegalAccessException e) {
            throw new ReflectionException("获取" + obj.getClass().getName() + fld.getName() + "时候出错");
        }
        return resObj;

    }

    public static Object getFieldValue(Object obj, String fieldName) throws ReflectionException {

        Object res = null;
        Field resFld = ReflectionUtils.findField(obj.getClass(), fieldName);
        if (resFld == null) {
            return new NoneObject();
        }
        res = getFieldValue(obj, resFld);
        return res;

    }

    public static Constructor getAccessiableConstructor(Class clz, Class... argTypes) throws NoSuchMethodException {
        Constructor constructor = clz.getDeclaredConstructor(argTypes);
        constructor.setAccessible(true);
        return constructor;
    }

    public static Constructor<?>[] getAllConstructors(Class<?> clz) {
        return clz.getDeclaredConstructors();
    }


    /**
     * 给定一个class，获取其中包含的指定fieldName并且可以被直接修改的Field对象
     *
     * @param clz
     * @param fName
     * @return
     */
    public static Field getModifiableField(Class clz, String fName) throws ReflectionException {

        Field field = null;
        try {
            field = getField(clz, fName);
            Field fModifers = Field.class.getDeclaredField("modifiers");
            fModifers.setAccessible(true);
            fModifers.setInt(field, field.getModifiers() & -17);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ReflectionException("无法获取" + clz.getName() + "中的" + fName + "字段");
        }
        return field;
    }

    public static Object getEnumValue(Class<?> enumClz, String name) {
        return Enum.valueOf((Class<Enum>) enumClz, name);
    }

    public static void setAccessible(AccessibleObject member) {
        String versionStr = System.getProperty("java.version");
        int javaVersion = Integer.parseInt(versionStr.split("\\.")[0]);
        if (javaVersion < 12) {
            // quiet runtime warnings from JDK9+
            Permit.setAccessible(member);
        } else {
            // not possible to quiet runtime warnings anymore...
            // see https://bugs.openjdk.java.net/browse/JDK-8210522
            // to understand impact on Permit (i.e. it does not work
            // anymore with Java >= 12)
            member.setAccessible(true);
        }
    }

    public static void setFieldValue(Object obj, String fieldName, Object value) throws ReflectionException {
        try {
            Field field = getModifiableField(obj.getClass(), fieldName);
            field.set(obj, value);
        } catch (IllegalAccessException | IllegalArgumentException | ReflectionException e) {
            // The JDK says "to null value" whenever the type of the value does not fit the field, whatever the
            // value is, so name the value type: it is what tells the cause apart.
            throw new ReflectionException("设置" + obj.getClass().getName() + "中的" + fieldName + "失败: " + e.getMessage()
                    + " [value type: " + (value == null ? "null" : value.getClass().getName()) + "]");
        }

    }

    /**
     * Whether {@code Field.set} accepts the value for the field: a reference field takes null or an instance of
     * its type; a primitive field takes the wrapper of its type or of a type that widens to it (an Integer for a
     * long field), never null.
     */
    public static boolean isAssignableToField(Field field, Object value) {
        Class<?> type = field.getType();
        if (!type.isPrimitive()) {
            return value == null || type.isInstance(value);
        }
        if (value == null) {
            return false;
        }
        // primitive widening, as in the JLS: the types a value of the given class may be stored as
        String widens;
        if (value instanceof Byte) {
            widens = "byte short int long float double";
        } else if (value instanceof Short) {
            widens = "short int long float double";
        } else if (value instanceof Character) {
            widens = "char int long float double";
        } else if (value instanceof Integer) {
            widens = "int long float double";
        } else if (value instanceof Long) {
            widens = "long float double";
        } else if (value instanceof Float) {
            widens = "float double";
        } else if (value instanceof Double) {
            widens = "double";
        } else if (value instanceof Boolean) {
            widens = "boolean";
        } else {
            return false;
        }
        return Arrays.asList(widens.split(" ")).contains(type.getName());
    }

    public static void setFieldValue(Object obj, Field fld, Object value) throws ReflectionException {
        try {
            setAccessible(fld);
            fld.set(obj, value);
        } catch (Exception e) {
            String detailMsg = e.getMessage();
            if (obj == null) {
                detailMsg += "，具体原因是obj为null";
            }
            throw new ReflectionException("设置" + (obj == null ? "null(要设置的obj)" : obj.getClass().getName()) + "中的" + fld.getName() + "失败: " + detailMsg);
        }

    }

    /**
     * Precisely invoke a method by its name and parameter types using reflection
     * @param obj The target object
     * @param methodName The name of the method
     * @param parameterTypes Array of parameter types
     * @param args Array of parameter values
     * @return The return value of the method invocation
     * @throws ReflectionException If the method doesn't exist or invocation fails
     */
    public static Object invokeMethodByNameAndParameterTypes(Object obj, String methodName, Class<?>[] parameterTypes, Object[] args) throws ReflectionException {
        if (obj == null) {
            throw new ReflectionException("Target object cannot be null");
        }
        
        Class<?> clazz;
        if (obj instanceof Class) {
            clazz = (Class<?>) obj;
            obj = null; // If it's a static method call, obj should be null
        } else {
            clazz = obj.getClass();
        }
        
        try {
            Method method = clazz.getMethod(methodName, parameterTypes);
            setAccessible(method);
            return method.invoke(obj, args);
        } catch (NoSuchMethodException e) {
            throw new ReflectionException("Method " + methodName + " not found in " + clazz.getName() + ", parameter types don't match");
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new ReflectionException("Failed to invoke method " + methodName + " in " + clazz.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Finds and invokes a method by name, attempting to match parameter types
     * If multiple methods with the same name exist, tries to find the best match based on parameter types
     * @param obj The target object to invoke the method on
     * @param methodName The name of the method to invoke
     * @param args The arguments to pass to the method
     * @return The result of the method invocation
     * @throws ReflectionException If no matching method is found or method invocation fails
     */
    public static Object invokeMethodByName(Object obj, String methodName, Object... args) throws ReflectionException {
        if (obj == null) {
            throw new ReflectionException("Target object cannot be null");
        }
        
        Class<?> clazz = null;
        if (obj instanceof Class) {
            clazz = (Class<?>) obj;
        } else {
            clazz = obj.getClass();
        }
        Method[] methods = clazz.getMethods();
        
        // First try to find an exact match for parameter types
        for (Method method : methods) {
            if (method.getName().equals(methodName)) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length == args.length) {
                    boolean match = true;
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (args[i] != null && !paramTypes[i].isAssignableFrom(args[i].getClass())) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        try {
                            setAccessible(method);
                            return method.invoke(obj, args);
                        } catch (Exception e) {
                            throw new ReflectionException("Failed to invoke method " + methodName + " in " + clazz.getName() + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
        
        // If no exact match is found, try to find a method with matching parameter count
        for (Method method : methods) {
            if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
                try {
                    setAccessible(method);
                    return method.invoke(obj, args);
                } catch (Exception e) {
                    // Continue trying other methods
                }
            }
        }
        
        throw new ReflectionException("No matching method " + methodName + " found in " + clazz.getName());
    }

    /**
     * 使用constructorClass的构造器构造一个classToInstantiate类
     * !!注意：这个方法能够解决类在没有无参构造器的情况下的类构造问题，但是对于存在默认的无参构造器，这样子做会出问题
     *
     * @param classToInstantiate 要初始化的类类型
     * @param constructorClass   构造器类
     * @param consArgTypes       参数类
     * @param consArgs           参数数值
     * @param <T>
     * @return
     * @throws ReflectionException 如果创建对象失败
     */
    @SuppressWarnings({"unchecked"})
    public static <T> T createWithConstructor(Class<T> classToInstantiate, Class<? super T> constructorClass,
                                              Class<?>[] consArgTypes, Object[] consArgs)
            throws ReflectionException {
        Constructor<? super T> objCons;
        Constructor<?> sc;
        T resObj = null;

        try {
            objCons = constructorClass.getDeclaredConstructor(consArgTypes);
            setAccessible(objCons);
            sc = ReflectionFactory.getReflectionFactory().newConstructorForSerialization(classToInstantiate, objCons);
            setAccessible(sc);
            resObj = (T) sc.newInstance(consArgs);
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
            throw new ReflectionException("创建对象失败: " + e.getMessage());
        }

        return resObj;
    }

    public static Object createWithConstructor(Class classToInstantiate, Class[] consArgTypes, Object[] consArgs)
            throws ReflectionException {
        ensureClojureRuntime(classToInstantiate);
        Constructor objCons;
        Object resObj = null;
        try {
            objCons = classToInstantiate.getDeclaredConstructor(consArgTypes);
            setAccessible(objCons);
            resObj = objCons.newInstance(consArgs);
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
            throw new ReflectionException("创建对象失败: " + e.getMessage());
        }
        return resObj;
    }

    public static <T> T createWithObjectNoArgsConstructor(Class<T> clzToInstantiate) 
            throws ReflectionException {
        T resObject = null;
        resObject = createWithConstructor(clzToInstantiate, Object.class, new Class[0], new Object[0]);
        return resObject;
    }

    public static Object createWithNoArgsConstructor(String clzName) throws ReflectionException {
        Class<?> clzToInstantiate = ClassUtils.getClassByName(clzName);
        return createWithNoArgsConstructor(clzToInstantiate);
    }

    /**
     * 使用类自身的无参构造器或父类的无参构造器生成对象，实在不行最终会使用Object.class作为父类生成构造器并new出对象
     *
     * @param clzToInstantiate
     * @param <T>
     * @return
     */
    private static volatile boolean clojureRuntimeReady = false;

    /**
     * Clojure classes must not be initialized before clojure.lang.RT: e.g. PersistentTreeSet's static init
     * starts RT, which loads clojure.core while PersistentTreeSet/PersistentTreeMap are half-initialized and
     * fails ("contains? not supported on type: clojure.lang.PersistentList"). An application using Clojure has
     * RT initialized already; do the same before creating the first clojure object.
     */
    private static void ensureClojureRuntime(Class<?> clz) {
        if (clojureRuntimeReady || clz == null || !clz.getName().startsWith("clojure.")) {
            return;
        }
        clojureRuntimeReady = true;
        try {
            Class.forName("clojure.lang.RT", true, clz.getClassLoader());
        } catch (Throwable e) {
            LogUtils.warn("Can not initialize the clojure runtime: " + e);
        }
    }

    public static <T> T createWithNoArgsConstructor(Class<T> clzToInstantiate) throws ReflectionException {
        ensureClojureRuntime(clzToInstantiate);

        T resObj = null;
        try {
            // 默认情况下取该类的无参构造器
            Constructor<?> constructor = clzToInstantiate.getDeclaredConstructor();
            constructor.setAccessible(true);
            resObj = (T) constructor.newInstance();
        } catch (NoSuchMethodException e) {
            try {
                // 如果当前的类不存在无参构造器，那尝试使用其父类的无参构造器
                Class<? super T> fatherClass = clzToInstantiate.getSuperclass();
                if (fatherClass == null) {
                    throw new InnerReflectionException("无法使用其父类无参方法生成" + clzToInstantiate.getName() + "的空对象，因为其父类为null");
                }
                resObj = createWithConstructor(clzToInstantiate, fatherClass,
                        new Class[0], new Object[0]);
            } catch (ReflectionException | IllegalAccessError eInner) {
                // 如果父类也没有无参构造器，那就只能够使用Object的了
                try {
                    resObj = createWithObjectNoArgsConstructor(clzToInstantiate);
                } catch (Exception | Error innerException) {
                    throw new ReflectionException("无法生成" + clzToInstantiate.getName() + "的空对象" + (e.getMessage() != null ? ": " + e.getMessage() : ""));
                }

            }
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException | IllegalAccessError | NoClassDefFoundError e) {
            // e.printStackTrace();
            String warnMsg = "Can not generate object of type " + clzToInstantiate.getName() + ": " + e.getMessage() + " check logs for more details.";
             LogUtils.warn(warnMsg);
             LogUtils.warn2Log(warnMsg); 
             LogUtils.warn2Log(CommonUtils.constructExceptionStackTrace(e));
        }

        return resObj;
    }

}

/**
 * 该方法应当在内部处理，而不是像ReflectionException直接抛出
 */
class InnerReflectionException extends ReflectionException {
    public InnerReflectionException(String msg) {
        super(msg);
    }
}
