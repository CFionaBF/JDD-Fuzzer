package cn.edu.fudan.generator.seed;

import cn.edu.fudan.utils.LogUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Puts an element into a Map- or Collection-typed field of a library object through the object's own public
 * mutator, instead of writing the field.
 * <p>
 * An IOCD can record an object as reached through a field whose declared type is a Map or a Collection (the
 * element is <i>inside</i> that field). Field-by-field assembly has no notion of "an element of this field": for a
 * field typed like java.util.HashMap it ends up writing the element object into the field itself, which does not
 * even type-check. The JDK's own collections are reached through their internal node arrays instead and are
 * repaired generically (FuzzUtils.repairOne), so this only concerns fields of library classes.
 * <p>
 * Writing such a field directly is wrong in a second way: a library class can keep several fields that have to
 * agree. A common shape is an object that stores id -> value in a map and the ids, in order, in a separate list,
 * with methods that iterate the list and look each id up in the map; a map filled on its own is then never reached,
 * because the list it is iterated through is empty. The class's own adder keeps both in step.
 * <p>
 * The mutator is chosen by signature, never by name: a public instance method of the owner (declared by the owner
 * or by a non-JDK superclass), not one of Object's methods, with one parameter per element component - (key,
 * value) for a Map, (element) for a Collection - where the element parameter accepts the element and, for a Map,
 * the key parameter accepts a String. It is used only when exactly one method qualifies, and only if after the call
 * the target field holds the element (compared by identity, so no method of the element runs). Every field the call
 * changed - a different reference, or a Collection/Map whose size changed - is returned, so the caller can protect
 * all of them from later assembly steps. In every other case nothing is returned and the caller assembles the field
 * as before.
 */
public final class OwnerMutatorAssembly {

    /** Map key used when the element goes into a map: only its presence matters to code that iterates the ids. */
    static final String KEY = "jdd";

    private static final Set<String> OBJECT_METHODS = new HashSet<>(Arrays.asList(
            "equals", "hashCode", "toString", "clone", "finalize", "getClass", "notify", "notifyAll", "wait"));

    private OwnerMutatorAssembly() {
    }

    private static boolean isJdk(Class<?> c) {
        String n = c.getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.")
                || n.startsWith("sun.") || n.startsWith("com.sun.");
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // declared further up
            }
        }
        return null;
    }

    /** Public instance methods declared by the owner or one of its non-JDK superclasses. */
    private static List<Method> libraryMethods(Class<?> c) {
        List<Method> res = new ArrayList<>();
        for (Method m : c.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || isJdk(m.getDeclaringClass())
                    || OBJECT_METHODS.contains(m.getName()) || m.isBridge() || m.isSynthetic()) {
                continue;
            }
            res.add(m);
        }
        return res;
    }

    private static List<Field> instanceFields(Class<?> c) {
        List<Field> res = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    res.add(f);
                }
            }
        }
        return res;
    }

    /** Field name -> {reference, size or -1}. */
    private static Map<String, Object[]> snapshot(Object owner, List<Field> fields) {
        Map<String, Object[]> res = new LinkedHashMap<>();
        for (Field f : fields) {
            try {
                f.setAccessible(true);
                Object v = f.get(owner);
                int size = v instanceof Collection ? ((Collection<?>) v).size()
                        : v instanceof Map ? ((Map<?, ?>) v).size() : -1;
                res.put(f.getDeclaringClass().getName() + "." + f.getName(), new Object[]{v, size});
            } catch (Throwable ignored) {
                // an inaccessible field is not compared
            }
        }
        return res;
    }

    /** Whether the collection holds this very object (identity: no equals() of the element is called). */
    private static boolean holds(Object container, Object element) {
        Iterable<?> items = container instanceof Map ? ((Map<?, ?>) container).values()
                : container instanceof Collection ? (Collection<?>) container : null;
        if (items == null) {
            return false;
        }
        for (Object o : items) {
            if (o == element) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts {@code element} into the Map/Collection field {@code fieldName} of {@code owner} through the owner's own
     * mutator. Returns the names of every field of the owner that changed, or null when the rule does not apply (the
     * caller then assembles the field as before).
     */
    public static List<String> tryInsert(Object owner, String fieldName, Object element) {
        if (owner == null || element == null || isJdk(owner.getClass())) {
            return null;
        }
        Field target = findField(owner.getClass(), fieldName);
        if (target == null || Modifier.isStatic(target.getModifiers())) {
            return null;
        }
        Class<?> type = target.getType();
        boolean isMap = Map.class.isAssignableFrom(type);
        if (!isMap && !Collection.class.isAssignableFrom(type)) {
            return null;
        }
        if (type.isInstance(element)) {
            return null; // a whole collection for the field, not an element of it: assemble as before
        }

        int arity = isMap ? 2 : 1;
        List<Method> candidates = new ArrayList<>();
        for (Method m : libraryMethods(owner.getClass())) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != arity || !p[arity - 1].isInstance(element)) {
                continue;
            }
            if (isMap && !p[0].isAssignableFrom(String.class)) {
                continue;
            }
            candidates.add(m);
        }
        String where = owner.getClass().getName() + "." + fieldName;
        if (candidates.size() != 1) {
            if (candidates.size() > 1) {
                LogUtils.info("OwnerMutatorAssembly: " + where + ": " + candidates.size()
                        + " mutators accept " + element.getClass().getName() + ", not choosing one");
            }
            return null;
        }

        Method mutator = candidates.get(0);
        List<Field> fields = instanceFields(owner.getClass());
        Map<String, Object[]> before = snapshot(owner, fields);
        try {
            mutator.setAccessible(true);
            if (isMap) {
                mutator.invoke(owner, KEY, element);
            } else {
                mutator.invoke(owner, element);
            }
        } catch (Throwable e) {
            LogUtils.warn("OwnerMutatorAssembly: " + where + ": " + mutator + " threw " + e);
            return null;
        }
        try {
            target.setAccessible(true);
            if (!holds(target.get(owner), element)) {
                LogUtils.warn("OwnerMutatorAssembly: " + where + ": " + mutator.getName()
                        + " ran but the field does not hold the element");
                return null;
            }
        } catch (Throwable e) {
            return null;
        }

        Map<String, Object[]> after = snapshot(owner, fields);
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : after.entrySet()) {
            Object[] b = before.get(e.getKey());
            Object[] a = e.getValue();
            if (b == null || b[0] != a[0] || !b[1].equals(a[1])) {
                String qualified = e.getKey();
                changed.add(qualified.substring(qualified.lastIndexOf('.') + 1));
            }
        }
        LogUtils.info("OwnerMutatorAssembly: " + where + " filled through " + mutator.getName()
                + Arrays.toString(mutator.getParameterTypes()) + "; fields changed: " + changed);
        return changed;
    }
}
