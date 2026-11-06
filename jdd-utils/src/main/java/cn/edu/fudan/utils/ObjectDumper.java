package cn.edu.fudan.utils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Debug helper: renders an object graph (instance fields, array items) as an indented tree,
 * so a generated seed can be compared with the gadget chain it is supposed to realize.
 */
public abstract class ObjectDumper {

    private static final int MAX_ARRAY_ITEMS = 8;

    public static String dump(Object root, int maxDepth) {
        StringBuilder sb = new StringBuilder();
        dump(sb, "", root, 0, maxDepth, new IdentityHashMap<>());
        return sb.toString();
    }

    private static void dump(StringBuilder sb, String label, Object obj, int depth, int maxDepth,
                             Map<Object, Integer> seen) {
        indent(sb, depth).append(label);
        if (obj == null) {
            sb.append("null\n");
            return;
        }
        Class<?> clz = obj.getClass();
        if (isLeaf(clz)) {
            sb.append(clz.getSimpleName()).append(' ').append(quote(obj)).append('\n');
            return;
        }
        Integer ref = seen.get(obj);
        if (ref != null) {
            sb.append(clz.getName()).append(" @").append(ref).append(" (seen)\n");
            return;
        }
        int id = seen.size();
        seen.put(obj, id);
        sb.append(clz.getName()).append(" @").append(id);
        if (depth >= maxDepth) {
            sb.append(" ...\n");
            return;
        }
        sb.append('\n');
        if (clz.isArray()) {
            int len = Array.getLength(obj);
            for (int i = 0; i < len && i < MAX_ARRAY_ITEMS; i++) {
                Object item = Array.get(obj, i);
                if (item != null || !clz.getComponentType().isPrimitive()) {
                    dump(sb, "[" + i + "] ", item, depth + 1, maxDepth, seen);
                }
            }
            if (len > MAX_ARRAY_ITEMS) {
                indent(sb, depth + 1).append("... (").append(len).append(" items)\n");
            }
            return;
        }
        for (Class<?> c = clz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Object val;
                try {
                    ReflectionUtils.setAccessible(f);
                    val = f.get(obj);
                } catch (Exception e) {
                    val = "<" + e.getClass().getSimpleName() + ">";
                }
                String name = (c == clz ? "" : c.getSimpleName() + ".") + f.getName() + " = ";
                dump(sb, name, val, depth + 1, maxDepth, seen);
            }
        }
    }

    private static boolean isLeaf(Class<?> clz) {
        return clz.isPrimitive() || clz == String.class || Number.class.isAssignableFrom(clz)
                || clz == Boolean.class || clz == Character.class || clz.isEnum() || clz == Class.class;
    }

    private static String quote(Object obj) {
        String s = obj instanceof Class ? ((Class<?>) obj).getName() : String.valueOf(obj);
        if (s.length() > 80) {
            s = s.substring(0, 77) + "...";
        }
        return obj instanceof String ? "\"" + s + "\"" : s;
    }

    private static StringBuilder indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        return sb;
    }
}
