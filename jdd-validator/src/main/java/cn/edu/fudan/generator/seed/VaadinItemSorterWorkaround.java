package cn.edu.fudan.generator.seed;

import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.exception.validator.GeneratorException;
import cn.edu.fudan.utils.FuzzUtils;
import cn.edu.fudan.utils.LogUtils;
import cn.edu.fudan.utils.ReflectionUtils;
import datastructrue.Pair;
import datastructrue.iocd.IOCD;
import datastructrue.iocd.unit.ClassRecord;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * vaadin: DefaultItemSorter.compare(o1, o2) does not hold the Items it compares. It gets them from its
 * `container` field (container.getItem(o1)), reads the property named by `sortPropertyIds` from each Item
 * (PropertysetItem.map) and calls Property.getValue() on it. As the Items flow in as method return values,
 * the IOCDs record the property as hanging off PropertysetItem.map, but have no node for the Item, nor for
 * the container (the upstream workaround for PropertysetItem in FuzzUtils only covers IOCDs where
 * PropertysetItem is a node).
 * <p>
 * For a record whose predecessor is PropertysetItem.map, with no PropertysetItem node in the IOCD and a
 * DefaultItemSorter record right before it, this class builds that part: a BeanItem (a PropertysetItem)
 * holding the property, and a BeanItemContainer whose item map gives this Item for the objects compare()
 * is called with; the sorter sorts by that property. Other records are left to the generic assembly.
 */
public class VaadinItemSorterWorkaround {

    static final String ITEM_SORTER = "com.vaadin.data.util.DefaultItemSorter";
    static final String PROPERTYSET_ITEM = "com.vaadin.data.util.PropertysetItem";
    static final String PROPERTYSET_ITEM_MAP = "<com.vaadin.data.util.PropertysetItem: java.util.HashMap map>";
    private static final String BEAN_ITEM = "com.vaadin.data.util.BeanItem";
    private static final String BEAN_ITEM_CONTAINER = "com.vaadin.data.util.BeanItemContainer";
    private static final String PROPERTY_ID = "jddProperty";
    private static final int MAX_WALK = 100000;

    // the sorters linked in this JVM -> the Item their container gives. Weak: seeds are rebuilt on every
    // clone. DefaultItemSorter does not override hashCode()/equals().
    private static final Map<Object, Object> LINKED = Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean isMissingItemRecord(List<ClassRecord> records, int index) {
        Pair<String, String> pred = records.get(index).getPredecessorRecord();
        if (index == 0 || pred == null || !PROPERTYSET_ITEM.equals(pred.getKey()) || !PROPERTYSET_ITEM_MAP.equals(pred.getValue())
                || !ITEM_SORTER.equals(records.get(index - 1).getClassName())) {
            return false;
        }
        for (ClassRecord r : records) {
            if (PROPERTYSET_ITEM.equals(r.getClassName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Assembles the record at `index` if it is a property hanging off the missing Item of the sorter right
     * before it. Returns false, without changing anything, for any other record.
     */
    static boolean assemble(List<ClassRecord> records, int index, HashMap<String, Object> backBoneObjMap,
                            HashSet<String> immutableFldSet) throws GeneratorException {
        if (!isMissingItemRecord(records, index)) {
            return false;
        }
        ClassRecord rcd = records.get(index), sorterRcd = records.get(index - 1);
        Object sorter = FuzzUtils.getCachedObject(sorterRcd.getId(), null, ITEM_SORTER, backBoneObjMap);
        Object property = FuzzUtils.getCachedObject(rcd.getId(), null, rcd.getClassName(), backBoneObjMap);
        if (sorter == null || property == null) {
            return false;
        }
        try {
            Object item = ReflectionUtils.createWithNoArgsConstructor(BEAN_ITEM); // runs PropertysetItem()
            ReflectionUtils.invokeMethodByName(item, "addItemProperty", PROPERTY_ID, property);
            Object container = ReflectionUtils.createWithNoArgsConstructor(BEAN_ITEM_CONTAINER);
            ReflectionUtils.setFieldValue(container, "itemIdToItem", new HashMap<>()); // filled by registerItemIds
            ReflectionUtils.setFieldValue(sorter, "container", container);
            ReflectionUtils.setFieldValue(sorter, "sortPropertyIds", new Object[]{PROPERTY_ID});
            ReflectionUtils.setFieldValue(sorter, "sortDirections", new boolean[]{true});
            LINKED.put(sorter, item);
        } catch (Exception e) {
            throw new GeneratorException("can not link the Item of " + rcd.getClassName() + " to " + ITEM_SORTER, e);
        }
        String sorterKey = FuzzUtils.getRealClassNameKey(sorterRcd.getId(), null, ITEM_SORTER);
        for (String fld : new String[]{"container", "sortPropertyIds", "sortDirections"}) {
            immutableFldSet.add(sorterKey + "#" + fld);
        }
        return true;
    }

    private static boolean isLinkedSorter(Object o) {
        // check the class first: LINKED.containsKey calls hashCode(), which gadget objects must not run here
        return o != null && o.getClass().getName().equals(ITEM_SORTER) && LINKED.containsKey(o);
    }

    private static final Pattern MAP_EQUALS_SIG = Pattern.compile(
            "<(java\\.util\\.Hashtable|java\\.util\\.HashMap|java\\.util\\.concurrent\\.ConcurrentHashMap): boolean equals\\(java\\.lang\\.Object\\)>");
    private static final Pattern SORTED_MAP_LOOKUP_SIG = Pattern.compile(
            "<(java\\.util\\.TreeMap|java\\.util\\.concurrent\\.ConcurrentSkipListMap): \\S+ (get|containsKey)\\(java\\.lang\\.Object\\)>");

    /**
     * For a chain link `M.equals(Object) -> S.get/containsKey(Object)` where S is a sorted map using a linked
     * sorter: M.equals only looks up its own keys in S (Hashtable/HashMap check the sizes first), and S only
     * calls its comparator when it has an entry. Give an empty S and empty receivers M one entry (s, s); an
     * entry with key == value adds nothing to the map's hash, so hash collisions built around them are kept.
     * S gets its entry without its comparator, so that the sorter does not run now.
     */
    static void primeSortedMapOperands(int iocdHash, Map<String, Object> backBoneObjMap) {
        IOCD iocd = GlobalDataContainer.getIOCDByHash(iocdHash);
        if (iocd == null || iocd.getGadgetCallStack() == null || LINKED.isEmpty()) {
            return;
        }
        List<String> sigs = new ArrayList<>();
        for (Pair<Integer, String> p : iocd.getGadgetCallStack()) {
            sigs.add(p.getValue());
        }
        for (int i = 0; i + 1 < sigs.size(); i++) {
            Matcher eq = MAP_EQUALS_SIG.matcher(sigs.get(i));
            Matcher lookup = SORTED_MAP_LOOKUP_SIG.matcher(sigs.get(i + 1));
            if (!eq.matches() || !lookup.matches()) {
                continue;
            }
            boolean primed = false;
            for (Object obj : backBoneObjMap.values()) {
                if (obj == null || !obj.getClass().getName().equals(lookup.group(1))) {
                    continue;
                }
                try {
                    Field cmpFld = ReflectionUtils.getField(obj.getClass(), "comparator");
                    Object cmp = cmpFld.get(obj);
                    if (isLinkedSorter(cmp) && ((Map) obj).isEmpty()) {
                        ReflectionUtils.setFieldValue(obj, "comparator", null);
                        try {
                            ((Map) obj).put("jddSortedKey", "jddSortedKey");
                        } finally {
                            ReflectionUtils.setFieldValue(obj, "comparator", cmp);
                        }
                    }
                    primed |= isLinkedSorter(cmp);
                } catch (Exception | Error e) {
                    LogUtils.debug("Can not prime " + obj.getClass().getName() + ": " + e);
                }
            }
            if (!primed) {
                continue;
            }
            for (Object obj : backBoneObjMap.values()) {
                if (obj != null && obj.getClass().getName().equals(eq.group(1)) && ((Map) obj).isEmpty()) {
                    ((Map) obj).put("jddReceiverKey", "jddReceiverKey");
                }
            }
        }
    }

    /**
     * Before serialization: map, in the container of each linked sorter reachable from `root`, every object
     * that compare() may be called with to the sorter's Item. These are the keys of the JDK maps and the
     * elements of the PriorityQueues in the payload (and the bounds of sorted sub maps); the seed's own may
     * have changed by mutation. Only keys whose hashCode() has no side effect are used (String, boxed
     * primitives, enums, and classes keeping Object.hashCode()), as the container's HashMap hashes them.
     */
    public static void registerItemIds(Object root) {
        if (root == null || LINKED.isEmpty()) {
            return;
        }
        List<Object> sorters = new ArrayList<>();
        List<Object> ids = new ArrayList<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Object> todo = new ArrayDeque<>();
        todo.push(root);
        while (!todo.isEmpty() && seen.size() < MAX_WALK) {
            Object o = todo.pop();
            if (seen.put(o, Boolean.TRUE) != null || isLeaf(o)) {
                continue;
            }
            if (isLinkedSorter(o)) {
                sorters.add(o);
                continue;
            }
            Class<?> clz = o.getClass();
            if (clz.isArray()) {
                if (!clz.getComponentType().isPrimitive()) {
                    for (Object e : (Object[]) o) {
                        if (e != null) {
                            todo.push(e);
                        }
                    }
                }
                continue;
            }
            boolean jdkCollection = clz.getName().startsWith("java.util.");
            for (Class<?> c = clz; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                        continue;
                    }
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(o);
                    } catch (Exception | Error e) {
                        continue;
                    }
                    if (v == null) {
                        continue;
                    }
                    todo.push(v);
                    if (jdkCollection) {
                        String n = f.getName();
                        if (n.equals("key") || (clz.getName().contains("SubMap") && (n.equals("lo") || n.equals("hi")))) {
                            ids.add(v);
                        } else if (n.equals("queue") && o instanceof PriorityQueue && v instanceof Object[]) {
                            for (Object e : (Object[]) v) {
                                if (e != null) {
                                    ids.add(e);
                                }
                            }
                        }
                    }
                }
            }
        }
        for (Object sorter : sorters) {
            try {
                Object container = ReflectionUtils.getField(sorter.getClass(), "container").get(sorter);
                Object item = LINKED.get(sorter);
                if (container == null || item == null || !container.getClass().getName().equals(BEAN_ITEM_CONTAINER)) {
                    continue;
                }
                Map<Object, Object> itemIdToItem = (Map<Object, Object>) ReflectionUtils.getField(container.getClass(), "itemIdToItem").get(container);
                itemIdToItem.clear();
                for (Object id : ids) {
                    if (hasPlainHashCode(id)) {
                        itemIdToItem.put(id, item);
                    }
                }
            } catch (Exception | Error e) {
                LogUtils.logWarnOnce("Can not register the item ids of " + ITEM_SORTER + ": " + e);
            }
        }
    }

    private static boolean isLeaf(Object o) {
        return o instanceof Class || o instanceof String || o instanceof Number || o instanceof Character
                || o instanceof Boolean || o instanceof Enum || o instanceof ClassLoader || o instanceof Thread
                || o instanceof java.lang.reflect.AccessibleObject;
    }

    private static boolean hasPlainHashCode(Object id) {
        if (id instanceof String || id instanceof Character || id instanceof Boolean || id instanceof Enum) {
            return true;
        }
        if (id instanceof Number) {
            return id.getClass().getName().startsWith("java.");
        }
        try {
            Method m = id.getClass().getMethod("hashCode");
            return m.getDeclaringClass() == Object.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
