package cn.edu.fudan.container;

import cn.edu.fudan.exception.validator.GeneratorException;
import cn.edu.fudan.exception.validator.ReflectionException;
import cn.edu.fudan.generator.condition.ConditionObjsWrapper;
import cn.edu.fudan.generator.fielddependency.FieldDependencyWrapper;
import cn.edu.fudan.generator.seed.SeedWrapper;
import cn.edu.fudan.generator.sink.SinkPayloadsWrapper;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.LogUtils;
import datastructrue.Pair;
import datastructrue.iocd.IOCD;
import datastructrue.iocd.unit.ClassRecord;
import datastructrue.iocd.unit.CollisionRecord;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;

public abstract class ValidatorGlobalDataContainer {


    public static GlobalDataContainer gContainer = GlobalDataContainer.getInstance();

    // IOCDHash - 对应的初始种子Wrapper
    public static LinkedList<Pair<Integer, SeedWrapper>> iocdInitSeedList;
    // IOCDHash - 对应的iocd的SinkPayloadsList索引
    public static HashMap<Integer, Integer> iocdSinkPayloadsIndexMap;
    // IOCDHash - 对应的SinkPayloadsWrapper
    // Thinking: 这里的和下面的pair中的iocd Hash貌似没有啥必要
    public static LinkedList<Pair<Integer, SinkPayloadsWrapper>> iocdSinkPayloadsList;
    // IOCDHash - 对应的iocd的ConditionObjsList索引
    public static HashMap<Integer, Integer> iocdConditionIndexMap;
    // IOCDHash - 对应的ConditionObjsWrapper
    public static LinkedList<Pair<Integer, ConditionObjsWrapper>> iocdConditionList;
    // IOCDHash - dependency fields
    public static HashMap<Integer, FieldDependencyWrapper> iocdFldDepMap;
    // iocdHash - iocd中可能需要进行变异的所有field
    // public static HashMap<Integer, HashSet<Field>> allMutableFieldMap = new HashMap<>();

    // Notice: 对新的种子进行fuzz时需要更新的字段
    // 接口/抽象类的concrete子类的缓存，Boolean用于标识是否实现了Serializable接口
    public static HashMap<String, HashSet<Pair<String, Boolean>>> singletonConcreteSubClassRecord = new HashMap<>();
    public static FuzzDictContainer singletonFuzzDictContainer = new FuzzDictContainer();

    public static JDDFuzzDictContainer singletonJDDFuzzDictContainer = new JDDFuzzDictContainer();

    public static boolean isAblation = false;
    public static boolean isAblationWithoutIOCD = false;
    public static boolean isAblationWithoutStructure = false;
    public static boolean isValidationStart = false;

    public static void init() {

        iocdInitSeedList = new LinkedList<>();
        iocdSinkPayloadsIndexMap = new HashMap<>();
        iocdSinkPayloadsList = new LinkedList<>();
        iocdConditionIndexMap = new HashMap<>();
        iocdConditionList = new LinkedList<>();
        iocdFldDepMap = new HashMap<>();

    }

//    /**
//     * 用于在生成一个种子的Input Seq时判断一个Field是否是需要进行变异的
//     * 当进行不考虑结构的消融实验时，只对backbone obj中的field进行变异
//     *
//     * @param iocdHash
//     * @param fld
//     * @return
//     */
//    public static boolean isMutableField(int iocdHash, Field fld) {
//        HashSet<Field> backBoneFieldSet = allMutableFieldMap.get(iocdHash);
//        for (Field backBoneFld : backBoneFieldSet) {
//            if (backBoneFld.equals(fld)) {
//                return true;
//            }
//        }
//        return false;
//    }


    /**
     * 每次进行新chain的fuzz时复位全局数据结构
     */
    public static void reset() {
        // 复位所有使用过的concrete类
        singletonConcreteSubClassRecord.forEach((keyName, concreteClzPairSet) -> {
            concreteClzPairSet.forEach(clzPair -> {
                clzPair.setValue(true);
            });
        });
    }


    /**
     * @param clzName 抽象类或接口类类名
     * @param set     (子类类名-是否使用过)的集合
     */
    public static void addConcreteSubClassRecord(String clzName, HashSet<Pair<String, Boolean>> set) {
        singletonConcreteSubClassRecord.put(clzName, set);
    }


    public static void flushCurrentDict(int iocdHash) throws ReflectionException, GeneratorException {
        ValidatorGlobalDataContainer.singletonFuzzDictContainer.flushDict(GlobalDataContainer.getIOCDByHash(iocdHash));
        ValidatorGlobalDataContainer.singletonJDDFuzzDictContainer.flushDict(GlobalDataContainer.getIOCDByHash(iocdHash));
    }

    /**
     * @param clzName 抽象类或接口类类名
     * @return 没有被用过的实现类
     */
    // 遍历的效率应当和随机取效果差不多
    public static String getUnusedConcreteClzRandomly(String clzName) {
        Pair<String, Boolean> tmp = null;
        HashSet<Pair<String, Boolean>> clzSet = singletonConcreteSubClassRecord.get(clzName);
        int max = clzSet.size();
        int time = 0;
        while (time < max && ((tmp = CommonUtils.getRandomElementFromSet(clzSet)) != null)) {
            if (!tmp.getValue()) {
                break;
            }
            tmp = null;
        }
        return tmp != null ? tmp.getKey() : null;
    }

    public static SinkPayloadsWrapper getSinkPayloadsWrapperByIOCDHash(int hash) {
        int index = iocdSinkPayloadsIndexMap.get(hash);
        return iocdSinkPayloadsList.get(index).getValue();
    }

    public static ConditionObjsWrapper getConditionObjsWrapperByIOCDHash(int hash) {
        int index = iocdConditionIndexMap.get(hash);
        return iocdConditionList.get(index).getValue();
    }

    public static FieldDependencyWrapper getFieldDependencyWrapperByIOCDHash(int hash) {
        return iocdFldDepMap.get(hash);
    }

    public static CollisionRecord getHashCollisionRecordByIOCDHash(int hash) {
        return gContainer.hashIOCDMap.get(hash).getHashCollisionRecord();
    }

    public static HashMap<String, HashSet<Integer>> getGdtUsedFiledMap(int hash) {
        return gContainer.gdtHashUsedFieldClassMap.get(hash);
    }

    public static ClassRecord getGadgetClassRecordByClassName(String clzName, int iocdHash) {
        ClassRecord resCR = null;
        IOCD gdtInfoRcd = gContainer.getIOCDByHash(iocdHash);
        for (ClassRecord cr : gdtInfoRcd.getGadgetClasses()) {
            // 此处不能够使用contains，否则无法处理内部类情况
            if (!cr.getClassName().equals(clzName)
                    && !ClassUtils.isSubOrImplClass(clzName, cr.getClassName())
            ) {
                continue;
            }
            resCR = cr;
            break;
        }
        if (resCR == null) {
            // Utils.printStackTraceWithExit(new IllegalArgumentException("没有类型为" + clzName +"的GadgetClass"));
            LogUtils.warn("没有类型为" + clzName + "的GadgetClass");
        }
        return resCR;
    }

    /**
     * 
     * @param hash
     * @param clzName
     * @return -1 for non-hash-collision, 0 for type 2 collisoin，1 for other type of collision
     */
    public static int isCollisionClassWrapper(int hash, String clzName, int classId) {
        return gContainer.isType2CollisionClass(hash, clzName, classId);
    }

    public static boolean isFieldSettableByFieldSig(String fieldSig) {
        return gContainer.isFieldSettableByFieldSig(fieldSig);
    }

}
