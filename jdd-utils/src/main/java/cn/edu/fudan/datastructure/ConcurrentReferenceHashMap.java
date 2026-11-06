package cn.edu.fudan.datastructure;

import cn.edu.fudan.utils.CommonUtils;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentReferenceHashMap<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    private static final float DEFAULT_LOAD_FACTOR = 0.75F;
    private static final int DEFAULT_CONCURRENCY_LEVEL = 16;
    private static final ConcurrentReferenceHashMap.ReferenceType DEFAULT_REFERENCE_TYPE;
    private static final int MAXIMUM_CONCURRENCY_LEVEL = 65536;
    private static final int MAXIMUM_SEGMENT_SIZE = 1073741824;
    private final ConcurrentReferenceHashMap<K, V>.Segment[] segments;
    private final float loadFactor;
    private final ConcurrentReferenceHashMap.ReferenceType referenceType;
    private final int shift;
    private volatile Set<Map.Entry<K, V>> entrySet;

    public ConcurrentReferenceHashMap() {
        this(16, 0.75F, 16, DEFAULT_REFERENCE_TYPE);
    }

    public ConcurrentReferenceHashMap(int initialCapacity) {
        this(initialCapacity, 0.75F, 16, DEFAULT_REFERENCE_TYPE);
    }

    public ConcurrentReferenceHashMap(int initialCapacity, float loadFactor) {
        this(initialCapacity, loadFactor, 16, DEFAULT_REFERENCE_TYPE);
    }

    public ConcurrentReferenceHashMap(int initialCapacity, int concurrencyLevel) {
        this(initialCapacity, 0.75F, concurrencyLevel, DEFAULT_REFERENCE_TYPE);
    }

    public ConcurrentReferenceHashMap(int initialCapacity, ConcurrentReferenceHashMap.ReferenceType referenceType) {
        this(initialCapacity, 0.75F, 16, referenceType);
    }

    public ConcurrentReferenceHashMap(int initialCapacity, float loadFactor, int concurrencyLevel) {
        this(initialCapacity, loadFactor, concurrencyLevel, DEFAULT_REFERENCE_TYPE);
    }

    public ConcurrentReferenceHashMap(int initialCapacity, float loadFactor, int concurrencyLevel, ConcurrentReferenceHashMap.ReferenceType referenceType) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Initial capacity must not be negative");
        }
        if (loadFactor < 0.0F) {
            throw new IllegalArgumentException("Load factor must be positive");
        }
        if (concurrencyLevel < 0) {
            throw new IllegalArgumentException("Concurrency level must be positive");
        }
        if (referenceType == null) {
            throw new IllegalArgumentException("Reference type must not be null");
        }
        this.loadFactor = loadFactor;
        this.shift = calculateShift(concurrencyLevel, 65536);
        int size = 1 << this.shift;
        this.referenceType = referenceType;
        int roundedUpSegmentCapacity = (int)(((long)(initialCapacity + size) - 1L) / (long)size);
        int initialSize = 1 << calculateShift(roundedUpSegmentCapacity, 1073741824);
        ConcurrentReferenceHashMap<K, V>.Segment[] segments = (ConcurrentReferenceHashMap.Segment[])((ConcurrentReferenceHashMap.Segment[]) Array.newInstance(ConcurrentReferenceHashMap.Segment.class, size));
        int resizeThreshold = (int)((float)initialSize * this.getLoadFactor());

        for(int i = 0; i < segments.length; ++i) {
            segments[i] = new ConcurrentReferenceHashMap.Segment(initialSize, resizeThreshold);
        }

        this.segments = segments;
    }

    protected final float getLoadFactor() {
        return this.loadFactor;
    }

    protected final int getSegmentsSize() {
        return this.segments.length;
    }

    protected final ConcurrentReferenceHashMap<K, V>.Segment getSegment(int index) {
        return this.segments[index];
    }

    protected ConcurrentReferenceHashMap<K, V>.ReferenceManager createReferenceManager() {
        return new ConcurrentReferenceHashMap.ReferenceManager();
    }

    protected int getHash( Object o) {
        int hash = o != null ? o.hashCode() : 0;
        hash += hash << 15 ^ -12931;
        hash ^= hash >>> 10;
        hash += hash << 3;
        hash ^= hash >>> 6;
        hash += (hash << 2) + (hash << 14);
        hash ^= hash >>> 16;
        return hash;
    }

    
    public V get( Object key) {
        ConcurrentReferenceHashMap.Reference<K, V> ref = this.getReference(key, ConcurrentReferenceHashMap.Restructure.WHEN_NECESSARY);
        ConcurrentReferenceHashMap.Entry<K, V> entry = ref != null ? ref.get() : null;
        return entry != null ? entry.getValue() : null;
    }

    
    public V getOrDefault( Object key,  V defaultValue) {
        ConcurrentReferenceHashMap.Reference<K, V> ref = this.getReference(key, ConcurrentReferenceHashMap.Restructure.WHEN_NECESSARY);
        ConcurrentReferenceHashMap.Entry<K, V> entry = ref != null ? ref.get() : null;
        return entry != null ? entry.getValue() : defaultValue;
    }

    public boolean containsKey( Object key) {
        ConcurrentReferenceHashMap.Reference<K, V> ref = this.getReference(key, ConcurrentReferenceHashMap.Restructure.WHEN_NECESSARY);
        ConcurrentReferenceHashMap.Entry<K, V> entry = ref != null ? ref.get() : null;
        return entry != null && CommonUtils.nullSafeEquals(entry.getKey(), key);
    }

    
    protected final ConcurrentReferenceHashMap.Reference<K, V> getReference( Object key, ConcurrentReferenceHashMap.Restructure restructure) {
        int hash = this.getHash(key);
        return this.getSegmentForHash(hash).getReference(key, hash, restructure);
    }

    
    public V put( K key,  V value) {
        return this.put(key, value, true);
    }

    
    public V putIfAbsent( K key,  V value) {
        return this.put(key, value, false);
    }

    
    private V put( final K key,  final V value, final boolean overwriteExisting) {
        return this.doTask(key, new ConcurrentReferenceHashMap<K, V>.Task<V>(new ConcurrentReferenceHashMap.TaskOption[]{ConcurrentReferenceHashMap.TaskOption.RESTRUCTURE_BEFORE, ConcurrentReferenceHashMap.TaskOption.RESIZE}) {
            
            protected V execute( ConcurrentReferenceHashMap.Reference<K, V> ref,  ConcurrentReferenceHashMap.Entry<K, V> entry,  ConcurrentReferenceHashMap.Entries<V> entries) {
                if (entry != null) {
                    V oldValue = entry.getValue();
                    if (overwriteExisting) {
                        entry.setValue(value);
                    }

                    return oldValue;
                } else {
                    if (entries == null) {
                        throw new IllegalArgumentException("No entries segment");
                    }
                    entries.add(value);
                    return null;
                }
            }
        });
    }

    
    public V remove( Object key) {
        return this.doTask(key, new ConcurrentReferenceHashMap<K, V>.Task<V>(new ConcurrentReferenceHashMap.TaskOption[]{ConcurrentReferenceHashMap.TaskOption.RESTRUCTURE_AFTER, ConcurrentReferenceHashMap.TaskOption.SKIP_IF_EMPTY}) {
            
            protected V execute( ConcurrentReferenceHashMap.Reference<K, V> ref,  ConcurrentReferenceHashMap.Entry<K, V> entry) {
                if (entry != null) {
                    if (ref != null) {
                        ref.release();
                    }

                    return entry.value;
                } else {
                    return null;
                }
            }
        });
    }

    public boolean remove( Object key,  final Object value) {
        Boolean result = (Boolean)this.doTask(key, new ConcurrentReferenceHashMap<K, V>.Task<Boolean>(new ConcurrentReferenceHashMap.TaskOption[]{ConcurrentReferenceHashMap.TaskOption.RESTRUCTURE_AFTER, ConcurrentReferenceHashMap.TaskOption.SKIP_IF_EMPTY}) {
            protected Boolean execute( ConcurrentReferenceHashMap.Reference<K, V> ref,  ConcurrentReferenceHashMap.Entry<K, V> entry) {
                if (entry != null && CommonUtils.nullSafeEquals(entry.getValue(), value)) {
                    if (ref != null) {
                        ref.release();
                    }

                    return true;
                } else {
                    return false;
                }
            }
        });
        return Boolean.TRUE.equals(result);
    }

    public boolean replace( K key,  final V oldValue,  final V newValue) {
        Boolean result = (Boolean)this.doTask(key, new ConcurrentReferenceHashMap<K, V>.Task<Boolean>(new ConcurrentReferenceHashMap.TaskOption[]{ConcurrentReferenceHashMap.TaskOption.RESTRUCTURE_BEFORE, ConcurrentReferenceHashMap.TaskOption.SKIP_IF_EMPTY}) {
            protected Boolean execute( ConcurrentReferenceHashMap.Reference<K, V> ref,  ConcurrentReferenceHashMap.Entry<K, V> entry) {
                if (entry != null && CommonUtils.nullSafeEquals(entry.getValue(), oldValue)) {
                    entry.setValue(newValue);
                    return true;
                } else {
                    return false;
                }
            }
        });
        return Boolean.TRUE.equals(result);
    }

    
    public V replace( K key,  final V value) {
        return this.doTask(key, new ConcurrentReferenceHashMap<K, V>.Task<V>(new ConcurrentReferenceHashMap.TaskOption[]{ConcurrentReferenceHashMap.TaskOption.RESTRUCTURE_BEFORE, ConcurrentReferenceHashMap.TaskOption.SKIP_IF_EMPTY}) {
            
            protected V execute( ConcurrentReferenceHashMap.Reference<K, V> ref,  ConcurrentReferenceHashMap.Entry<K, V> entry) {
                if (entry != null) {
                    V oldValue = entry.getValue();
                    entry.setValue(value);
                    return oldValue;
                } else {
                    return null;
                }
            }
        });
    }

    public void clear() {
        ConcurrentReferenceHashMap.Segment[] var1 = this.segments;
        int var2 = var1.length;

        for(int var3 = 0; var3 < var2; ++var3) {
            ConcurrentReferenceHashMap<K, V>.Segment segment = var1[var3];
            segment.clear();
        }

    }

    public void purgeUnreferencedEntries() {
        ConcurrentReferenceHashMap.Segment[] var1 = this.segments;
        int var2 = var1.length;

        for(int var3 = 0; var3 < var2; ++var3) {
            ConcurrentReferenceHashMap<K, V>.Segment segment = var1[var3];
            segment.restructureIfNecessary(false);
        }

    }

    public int size() {
        int size = 0;
        ConcurrentReferenceHashMap.Segment[] var2 = this.segments;
        int var3 = var2.length;

        for(int var4 = 0; var4 < var3; ++var4) {
            ConcurrentReferenceHashMap<K, V>.Segment segment = var2[var4];
            size += segment.getCount();
        }

        return size;
    }

    public boolean isEmpty() {
        ConcurrentReferenceHashMap.Segment[] var1 = this.segments;
        int var2 = var1.length;

        for(int var3 = 0; var3 < var2; ++var3) {
            ConcurrentReferenceHashMap<K, V>.Segment segment = var1[var3];
            if (segment.getCount() > 0) {
                return false;
            }
        }

        return true;
    }

    public Set<java.util.Map.Entry<K, V>> entrySet() {
        Set<java.util.Map.Entry<K, V>> entrySet = this.entrySet;
        if (entrySet == null) {
            entrySet = new ConcurrentReferenceHashMap.EntrySet();
            this.entrySet = (Set)entrySet;
        }

        return (Set)entrySet;
    }

    
    private <T> T doTask( Object key, ConcurrentReferenceHashMap<K, V>.Task<T> task) {
        int hash = this.getHash(key);
        return this.getSegmentForHash(hash).doTask(hash, key, task);
    }

    private ConcurrentReferenceHashMap<K, V>.Segment getSegmentForHash(int hash) {
        return this.segments[hash >>> 32 - this.shift & this.segments.length - 1];
    }

    protected static int calculateShift(int minimumValue, int maximumValue) {
        int shift = 0;

        for(int value = 1; value < minimumValue && value < maximumValue; ++shift) {
            value <<= 1;
        }

        return shift;
    }

    static {
        DEFAULT_REFERENCE_TYPE = ConcurrentReferenceHashMap.ReferenceType.SOFT;
    }

    private static final class WeakEntryReference<K, V> extends WeakReference<Entry<K, V>> implements ConcurrentReferenceHashMap.Reference<K, V> {
        private final int hash;
        
        private final ConcurrentReferenceHashMap.Reference<K, V> nextReference;

        public WeakEntryReference(ConcurrentReferenceHashMap.Entry<K, V> entry, int hash,  ConcurrentReferenceHashMap.Reference<K, V> next, ReferenceQueue<Entry<K, V>> queue) {
            super(entry, queue);
            this.hash = hash;
            this.nextReference = next;
        }

        public int getHash() {
            return this.hash;
        }

        
        public ConcurrentReferenceHashMap.Reference<K, V> getNext() {
            return this.nextReference;
        }

        public void release() {
            this.enqueue();
            this.clear();
        }
    }

    private static final class SoftEntryReference<K, V> extends SoftReference<Entry<K, V>> implements ConcurrentReferenceHashMap.Reference<K, V> {
        private final int hash;
        
        private final ConcurrentReferenceHashMap.Reference<K, V> nextReference;

        public SoftEntryReference(ConcurrentReferenceHashMap.Entry<K, V> entry, int hash,  ConcurrentReferenceHashMap.Reference<K, V> next, ReferenceQueue<ConcurrentReferenceHashMap.Entry<K, V>> queue) {
            super(entry, queue);
            this.hash = hash;
            this.nextReference = next;
        }

        public int getHash() {
            return this.hash;
        }

        
        public ConcurrentReferenceHashMap.Reference<K, V> getNext() {
            return this.nextReference;
        }

        public void release() {
            this.enqueue();
            this.clear();
        }
    }

    protected class ReferenceManager {
        private final ReferenceQueue<ConcurrentReferenceHashMap.Entry<K, V>> queue = new ReferenceQueue();

        protected ReferenceManager() {
        }

        public ConcurrentReferenceHashMap.Reference<K, V> createReference(ConcurrentReferenceHashMap.Entry<K, V> entry, int hash,  ConcurrentReferenceHashMap.Reference<K, V> next) {
            return (ConcurrentReferenceHashMap.Reference)(ConcurrentReferenceHashMap.this.referenceType == ConcurrentReferenceHashMap.ReferenceType.WEAK ? new ConcurrentReferenceHashMap.WeakEntryReference(entry, hash, next, this.queue) : new ConcurrentReferenceHashMap.SoftEntryReference(entry, hash, next, this.queue));
        }

        
        public ConcurrentReferenceHashMap.Reference<K, V> pollForPurge() {
            return (ConcurrentReferenceHashMap.Reference)this.queue.poll();
        }
    }

    protected static enum Restructure {
        WHEN_NECESSARY,
        NEVER;

        private Restructure() {
        }
    }

    private class EntryIterator implements Iterator<Map.Entry<K, V>> {
        private int segmentIndex;
        private int referenceIndex;
        
        private ConcurrentReferenceHashMap.Reference<K, V>[] references;
        
        private ConcurrentReferenceHashMap.Reference<K, V> reference;
        
        private ConcurrentReferenceHashMap.Entry<K, V> next;
        
        private ConcurrentReferenceHashMap.Entry<K, V> last;

        public EntryIterator() {
            this.moveToNextSegment();
        }

        public boolean hasNext() {
            this.getNextIfNecessary();
            return this.next != null;
        }

        public ConcurrentReferenceHashMap.Entry<K, V> next() {
            this.getNextIfNecessary();
            if (this.next == null) {
                throw new NoSuchElementException();
            } else {
                this.last = this.next;
                this.next = null;
                return this.last;
            }
        }

        private void getNextIfNecessary() {
            while(this.next == null) {
                this.moveToNextReference();
                if (this.reference == null) {
                    return;
                }

                this.next = this.reference.get();
            }

        }

        private void moveToNextReference() {
            if (this.reference != null) {
                this.reference = this.reference.getNext();
            }

            while(this.reference == null && this.references != null) {
                if (this.referenceIndex >= this.references.length) {
                    this.moveToNextSegment();
                    this.referenceIndex = 0;
                } else {
                    this.reference = this.references[this.referenceIndex];
                    ++this.referenceIndex;
                }
            }

        }

        private void moveToNextSegment() {
            this.reference = null;
            this.references = null;
            if (this.segmentIndex < ConcurrentReferenceHashMap.this.segments.length) {
                this.references = ConcurrentReferenceHashMap.this.segments[this.segmentIndex].references;
                ++this.segmentIndex;
            }

        }

        public void remove() {
            if (this.last != null) {
                throw new IllegalArgumentException("No element to remove");
            }
            ConcurrentReferenceHashMap.this.remove(this.last.getKey());
            this.last = null;
        }
    }

    private class EntrySet extends AbstractSet<Map.Entry<K, V>> {
        private EntrySet() {
        }

        public Iterator<java.util.Map.Entry<K, V>> iterator() {
            return ConcurrentReferenceHashMap.this.new EntryIterator();
        }

        public boolean contains( Object o) {
            if (o instanceof java.util.Map.Entry) {
                java.util.Map.Entry<?, ?> entry = (java.util.Map.Entry)o;
                ConcurrentReferenceHashMap.Reference<K, V> ref = ConcurrentReferenceHashMap.this.getReference(entry.getKey(), ConcurrentReferenceHashMap.Restructure.NEVER);
                ConcurrentReferenceHashMap.Entry<K, V> otherEntry = ref != null ? ref.get() : null;
                if (otherEntry != null) {
                    return CommonUtils.nullSafeEquals(entry.getValue(), otherEntry.getValue());
                }
            }

            return false;
        }

        public boolean remove(Object o) {
            if (o instanceof java.util.Map.Entry) {
                java.util.Map.Entry<?, ?> entry = (java.util.Map.Entry)o;
                return ConcurrentReferenceHashMap.this.remove(entry.getKey(), entry.getValue());
            } else {
                return false;
            }
        }

        public int size() {
            return ConcurrentReferenceHashMap.this.size();
        }

        public void clear() {
            ConcurrentReferenceHashMap.this.clear();
        }
    }

    private interface Entries<V> {
        void add( V value);
    }

    private static enum TaskOption {
        RESTRUCTURE_BEFORE,
        RESTRUCTURE_AFTER,
        SKIP_IF_EMPTY,
        RESIZE;

        private TaskOption() {
        }
    }

    private abstract class Task<T> {
        private final EnumSet<ConcurrentReferenceHashMap.TaskOption> options;

        public Task(ConcurrentReferenceHashMap.TaskOption... options) {
            this.options = options.length == 0 ? EnumSet.noneOf(ConcurrentReferenceHashMap.TaskOption.class) : EnumSet.of(options[0], options);
        }

        public boolean hasOption(ConcurrentReferenceHashMap.TaskOption option) {
            return this.options.contains(option);
        }

        
        protected T execute( ConcurrentReferenceHashMap.Reference<K, V> ref,  ConcurrentReferenceHashMap.Entry<K, V> entry,  ConcurrentReferenceHashMap.Entries<V> entries) {
            return this.execute(ref, entry);
        }

        
        protected T execute( ConcurrentReferenceHashMap.Reference<K, V> ref,  ConcurrentReferenceHashMap.Entry<K, V> entry) {
            return null;
        }
    }

    protected static final class Entry<K, V> implements java.util.Map.Entry<K, V> {
        
        private final K key;
        
        private volatile V value;

        public Entry( K key,  V value) {
            this.key = key;
            this.value = value;
        }

        
        public K getKey() {
            return this.key;
        }

        
        public V getValue() {
            return this.value;
        }

        
        public V setValue( V value) {
            V previous = this.value;
            this.value = value;
            return previous;
        }

        public String toString() {
            return this.key + "=" + this.value;
        }

        public final boolean equals( Object other) {
            if (this == other) {
                return true;
            } else if (!(other instanceof java.util.Map.Entry)) {
                return false;
            } else {
                java.util.Map.Entry otherEntry = (java.util.Map.Entry)other;
                return CommonUtils.nullSafeEquals(this.getKey(), otherEntry.getKey()) && CommonUtils.nullSafeEquals(this.getValue(), otherEntry.getValue());
            }
        }

        public final int hashCode() {
            return CommonUtils.nullSafeHashCode(this.key) ^ CommonUtils.nullSafeHashCode(this.value);
        }
    }

    protected interface Reference<K, V> {
        
        ConcurrentReferenceHashMap.Entry<K, V> get();

        int getHash();

        
        ConcurrentReferenceHashMap.Reference<K, V> getNext();

        void release();
    }

    protected final class Segment extends ReentrantLock {
        private final ConcurrentReferenceHashMap<K, V>.ReferenceManager referenceManager = ConcurrentReferenceHashMap.this.createReferenceManager();
        private final int initialSize;
        private volatile ConcurrentReferenceHashMap.Reference<K, V>[] references;
        private final AtomicInteger count = new AtomicInteger();
        private int resizeThreshold;

        public Segment(int initialSize, int resizeThreshold) {
            this.initialSize = initialSize;
            this.references = this.createReferenceArray(initialSize);
            this.resizeThreshold = resizeThreshold;
        }

        
        public ConcurrentReferenceHashMap.Reference<K, V> getReference( Object key, int hash, ConcurrentReferenceHashMap.Restructure restructure) {
            if (restructure == ConcurrentReferenceHashMap.Restructure.WHEN_NECESSARY) {
                this.restructureIfNecessary(false);
            }

            if (this.count.get() == 0) {
                return null;
            } else {
                ConcurrentReferenceHashMap.Reference<K, V>[] references = this.references;
                int index = this.getIndex(hash, references);
                ConcurrentReferenceHashMap.Reference<K, V> head = references[index];
                return this.findInChain(head, key, hash);
            }
        }

        
        public <T> T doTask(final int hash,  final Object key, final ConcurrentReferenceHashMap<K, V>.Task<T> task) {
            boolean resize = task.hasOption(ConcurrentReferenceHashMap.TaskOption.RESIZE);
            if (task.hasOption(ConcurrentReferenceHashMap.TaskOption.RESTRUCTURE_BEFORE)) {
                this.restructureIfNecessary(resize);
            }

            if (task.hasOption(ConcurrentReferenceHashMap.TaskOption.SKIP_IF_EMPTY) && this.count.get() == 0) {
                return task.execute(null, null, null);
            } else {
                this.lock();

                T var10;
                try {
                    int index = this.getIndex(hash, this.references);
                    ConcurrentReferenceHashMap.Reference<K, V> head = this.references[index];
                    ConcurrentReferenceHashMap.Reference<K, V> ref = this.findInChain(head, key, hash);
                    ConcurrentReferenceHashMap.Entry<K, V> entry = ref != null ? ref.get() : null;
                    ConcurrentReferenceHashMap.Entries<V> entries = (value) -> {
                        ConcurrentReferenceHashMap.Entry<K, V> newEntry = new ConcurrentReferenceHashMap.Entry(key, value);
                        ConcurrentReferenceHashMap.Reference<K, V> newReference = this.referenceManager.createReference(newEntry, hash, head);
                        this.references[index] = newReference;
                        this.count.incrementAndGet();
                    };
                    var10 = task.execute(ref, entry, entries);
                } finally {
                    this.unlock();
                    if (task.hasOption(ConcurrentReferenceHashMap.TaskOption.RESTRUCTURE_AFTER)) {
                        this.restructureIfNecessary(resize);
                    }

                }

                return var10;
            }
        }

        public void clear() {
            if (this.count.get() != 0) {
                this.lock();

                try {
                    this.references = this.createReferenceArray(this.initialSize);
                    this.resizeThreshold = (int)((float)this.references.length * ConcurrentReferenceHashMap.this.getLoadFactor());
                    this.count.set(0);
                } finally {
                    this.unlock();
                }

            }
        }

        protected final void restructureIfNecessary(boolean allowResize) {
            int currCount = this.count.get();
            boolean needsResize = allowResize && currCount > 0 && currCount >= this.resizeThreshold;
            ConcurrentReferenceHashMap.Reference<K, V> ref = this.referenceManager.pollForPurge();
            if (ref != null || needsResize) {
                this.restructure(allowResize, ref);
            }

        }

        private void restructure(boolean allowResize,  ConcurrentReferenceHashMap.Reference<K, V> ref) {
            this.lock();

            try {
                int countAfterRestructure = this.count.get();
                Set<ConcurrentReferenceHashMap.Reference<K, V>> toPurge = Collections.emptySet();
                if (ref != null) {
                    for(toPurge = new HashSet(); ref != null; ref = this.referenceManager.pollForPurge()) {
                        ((Set)toPurge).add(ref);
                    }
                }

                countAfterRestructure -= ((Set)toPurge).size();
                boolean needsResize = countAfterRestructure > 0 && countAfterRestructure >= this.resizeThreshold;
                boolean resizing = false;
                int restructureSize = this.references.length;
                if (allowResize && needsResize && restructureSize < 1073741824) {
                    restructureSize <<= 1;
                    resizing = true;
                }

                ConcurrentReferenceHashMap.Reference<K, V>[] restructured = resizing ? this.createReferenceArray(restructureSize) : this.references;

                for(int i = 0; i < this.references.length; ++i) {
                    ref = this.references[i];
                    if (!resizing) {
                        restructured[i] = null;
                    }

                    for(; ref != null; ref = ref.getNext()) {
                        if (!((Set)toPurge).contains(ref)) {
                            ConcurrentReferenceHashMap.Entry<K, V> entry = ref.get();
                            if (entry != null) {
                                int index = this.getIndex(ref.getHash(), restructured);
                                restructured[index] = this.referenceManager.createReference(entry, ref.getHash(), restructured[index]);
                            }
                        }
                    }
                }

                if (resizing) {
                    this.references = restructured;
                    this.resizeThreshold = (int)((float)this.references.length * ConcurrentReferenceHashMap.this.getLoadFactor());
                }

                this.count.set(Math.max(countAfterRestructure, 0));
            } finally {
                this.unlock();
            }

        }

        
        private ConcurrentReferenceHashMap.Reference<K, V> findInChain(ConcurrentReferenceHashMap.Reference<K, V> ref,  Object key, int hash) {
            for(ConcurrentReferenceHashMap.Reference currRef = ref; currRef != null; currRef = currRef.getNext()) {
                if (currRef.getHash() == hash) {
                    ConcurrentReferenceHashMap.Entry<K, V> entry = currRef.get();
                    if (entry != null) {
                        K entryKey = entry.getKey();
                        if (CommonUtils.nullSafeEquals(entryKey, key)) {
                            return currRef;
                        }
                    }
                }
            }

            return null;
        }

        private ConcurrentReferenceHashMap.Reference<K, V>[] createReferenceArray(int size) {
            return new ConcurrentReferenceHashMap.Reference[size];
        }

        private int getIndex(int hash, ConcurrentReferenceHashMap.Reference<K, V>[] references) {
            return hash & references.length - 1;
        }

        public final int getSize() {
            return this.references.length;
        }

        public final int getCount() {
            return this.count.get();
        }
    }

    public static enum ReferenceType {
        SOFT,
        WEAK;

        private ReferenceType() {
        }
    }
}
