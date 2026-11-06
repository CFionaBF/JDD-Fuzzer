package cn.edu.fudan.utils;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.concurrent.ConcurrentHashMap;

import datastructrue.iocd.unit.ClassRecord;

public abstract class SeedUtils {

    private static final String CONCURRENT_HASHMAP = "java.util.concurrent.ConcurrentHashMap";
    private static final String HASHTABLE = "java.util.Hashtable";
    private static final String HASHMAP = "java.util.HashMap";

    
    /**
     * generate type 1 collision Object
     */
    public static Object generateCollisionObject1 (ClassRecord topClzRcd, Object firstObj, Object secondObj, HashSet<String> immutableFieldSet) throws Exception {
        String topClzName = topClzRcd.getClassName();
        String realTopClzNameKey = FuzzUtils.getRealClassNameKey(topClzRcd.getId(), null, topClzName);
        Object result = null;
        if (topClzName.equals(CONCURRENT_HASHMAP)) {
            immutableFieldSet.add(realTopClzNameKey + "#" + "sizeCtl");
            result = genConcurrentHashMapCollision1(firstObj, secondObj);
        } else if (topClzName.equals(HASHTABLE)) {
            immutableFieldSet.add(realTopClzNameKey + "#" + "count");
            result = genHashtableCollision1(firstObj, secondObj);
        } else if (topClzName.equals(HASHMAP)) {
            immutableFieldSet.add(realTopClzNameKey + "#" + "size");
            result = genHashMapCollision1(firstObj, secondObj);
        } else {
            throw new RuntimeException("In `generateCollisionObject1`: Unsupported top class type: " + topClzName);
        }
        immutableFieldSet.add(realTopClzNameKey + "#" + "table");
        return result;
    }

    /**
     * generate type 2 collision Object
     */
    public static Object generateCollisionObject2(ClassRecord topClzRcd, Object firstObj, Object secondObj, HashSet<String> immutableFieldSet) throws Exception {
        // notice that the consturcture of type 1 and type 2 are similar.
        return generateCollisionObject1(topClzRcd, firstObj, secondObj, immutableFieldSet);
    }

    public static void generateCollisionObject3() {

    }

    public static <K, V> Object genConcurrentHashMapCollision1(Object v1, Object v2) throws Exception {
        ConcurrentHashMap<K,V> s = new ConcurrentHashMap<>();
        ReflectionUtils.setFieldValue(s, "sizeCtl", 2);
        Class<?> nodeType = getConcurrentHashMapTableElementType();
        Object tbl = assembleTable1(nodeType, v1, v2);        
        ReflectionUtils.setFieldValue(s, "table", tbl);
        return s;
    }

    public static Object genHashtableCollision1(Object v1, Object v2) throws Exception {
        Hashtable s = new Hashtable();
        ReflectionUtils.setFieldValue(s, "count", 2);
        Class<?> nodeType = getHashtableTableElementType();
        Object tbl = assembleTable1(nodeType, v1, v2);
        ReflectionUtils.setFieldValue(s, "table", tbl);

        return s;
    }

    public static Object genHashMapCollision1(Object v1, Object v2) throws Exception {
        HashMap s = new HashMap();
        ReflectionUtils.setFieldValue(s, "size", 2);
        Class<?> nodeType = getHashMapTableElementType();
        Object tbl = assembleTable1(nodeType, v1, v2);
        ReflectionUtils.setFieldValue(s, "table", tbl);

        return s;
    }

    private static Object assembleTable1(Class<?> nodeType, Object v1, Object v2) throws Exception {
        Constructor<?> nodeConstruct = ReflectionUtils.getAccessiableConstructor(nodeType, int.class, Object.class, Object.class, nodeType);
        Object tbl = Array.newInstance(nodeType, 2);
        Array.set(tbl, 0, nodeConstruct.newInstance(0, v2, v1, null));
        Array.set(tbl, 1, nodeConstruct.newInstance(0, v1, v2, null));
        return tbl;
    }

    private static Class<?> getConcurrentHashMapTableElementType() throws ClassNotFoundException {
        Class<?> nodeType;
        try {
            nodeType = ClassUtils.wrapperClassForName("java.util.concurrent.ConcurrentHashMap$Node");
        } catch (ClassNotFoundException e) {
            nodeType = ClassUtils.wrapperClassForName("java.util.concurrent.ConcurrentHashMap$Entry");
        }
        return nodeType;
    }

    private static Class<?> getHashtableTableElementType() throws ClassNotFoundException {
        Class<?> nodeType;
        try {
            nodeType = ClassUtils.wrapperClassForName("java.util.Hashtable$Node");
        } catch (ClassNotFoundException e) {
            nodeType = ClassUtils.wrapperClassForName("java.util.Hashtable$Entry");
        }
        return nodeType;
    }

    private static Class<?> getHashMapTableElementType() throws ClassNotFoundException {
        Class<?> nodeType;
        try {
            nodeType = ClassUtils.wrapperClassForName("java.util.HashMap$Node");
        } catch (ClassNotFoundException e) {
            nodeType = ClassUtils.wrapperClassForName("java.util.HashMap$Entry");
        }
        return nodeType;
    }
    

}
