package cn.edu.fudan.generator.seed;

import cn.edu.fudan.container.ConfigContainer;
import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import cn.edu.fudan.exception.validator.*;
import cn.edu.fudan.fuzz.JDDFuzzGuidance;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.FileUtils;
import cn.edu.fudan.utils.FuzzUtils;
import cn.edu.fudan.utils.LogUtils;
import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.SerializerFactory;
import datastructrue.Pair;
import datastructrue.iocd.IOCD;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;

import java.lang.reflect.Field;

public class SeedWrapper {

    // Notice: 记得重置
    public static int GLOBAL_CURRENT_ID = 0;
    public int iocdHash;
    public int id;
    public short seedType;
    public int power = 1; // 当前这个种子的能量，初始值设为1
    public double seedDistance;
    public double gadgetCoverage;
    public int coveredGdtNum;
    public int coveredBscIfStmtName;
    // 最外层的InjectionObject
    public Object injectionObj;
    // 所有的BackBone Injection Object，clzName - 对象值
    // 注意，这里的和SeedGenerator里的backBoneObjectMap不太一样，key是实际的类名
    public LinkedHashMap<String, Object> backBoneObjectMap;
    // 构造backboneObject过程中连接时必须的字段中的对象，key是字段名，value是对象值，可能会涉及到多层
    // 比如对于Gadget Chain A.ma()->D.md()，在ma中存在a.b.c.d.md()的调用
    public HashMap<String, Object> fldInFldObjectMap;
    // Thinking: 是否需要预先生成？
    public HashMap<String, Object> conObjectMap;

    // 带classId和colTag的类名的集合
    public HashSet<String> immutableFieldInfoSet;

    // <位置(带id和tag的类名#字段名)，对象>
    public int sinkIndex = -1;
    public HashMap<String, Object> crtSinkObjectMap;

    // 记录已经完成过Mutate的非Basic Condition的序列
    // key: 第几段序列
    // value: 正序的已经变异过的序列集合
    public HashMap<Integer, HashSet<Integer>> mutatedNoneBscConditionObjsSegMap;
    public JDDFuzzGuidance.JDDInputContainer input;

    // 用于判断该种子是否已经被取出并变异过
    public boolean isMutated = false;


    // Notice: 使用这个构造方法需要后续添加input和injectionObj
    public SeedWrapper() {
        this.id = GLOBAL_CURRENT_ID++;
        this.seedDistance = -1;
        this.gadgetCoverage = -1;
        this.coveredGdtNum = -1;
        this.coveredBscIfStmtName = 0;
        this.backBoneObjectMap = new LinkedHashMap<>();
        this.crtSinkObjectMap = new HashMap<>();
    }

    public SeedWrapper(IOCD iocd) throws FatalFuzzingException, GeneratorException {
        this.id = GLOBAL_CURRENT_ID++;
        this.seedDistance = -1;
        this.gadgetCoverage = -1;
        this.coveredGdtNum = -1;
        this.coveredBscIfStmtName = 0;
        // this.coveredBBHash = new HashSet<>();
        this.backBoneObjectMap = new LinkedHashMap<>();
        this.fldInFldObjectMap = new HashMap<>();
        this.immutableFieldInfoSet = new HashSet<>();
        this.crtSinkObjectMap = new HashMap<>();
        this.mutatedNoneBscConditionObjsSegMap = new HashMap<>();
        this.injectionObj = SeedGenerator.generateBackboneObject(iocdHash, iocd.getGadgetClasses(), (short) 0, iocd.getProtocol(), this.backBoneObjectMap, this.fldInFldObjectMap, this.immutableFieldInfoSet);
        // this.originalInjectionObject = iObj;
        // this.lastMutateIdentifierIndex = -1;
        // 顺序不能错，不然会空指针，因为getInjectionObjectInput会用到上面的。
        this.input = initInjectionObjectInputContainer();
    }

    public SeedWrapper(Object iObj, int iocdHash, LinkedHashMap<String, Object> backBoneObjectMap, HashSet<String> crtImmutableFieldSet, short type) throws FatalFuzzingException {
        this.iocdHash = iocdHash;
        this.id = GLOBAL_CURRENT_ID++;
        this.seedDistance = -1;
        this.gadgetCoverage = -1;
        this.coveredGdtNum = -1;
        this.coveredBscIfStmtName = 0;
        // this.coveredBBHash = new HashSet<>();
        this.injectionObj = iObj;
        this.backBoneObjectMap = backBoneObjectMap;
        this.fldInFldObjectMap = new HashMap<>();
        this.crtSinkObjectMap = new HashMap<>();
        this.mutatedNoneBscConditionObjsSegMap = new HashMap<>();
        this.conObjectMap = new HashMap<>();
        this.immutableFieldInfoSet = crtImmutableFieldSet;
        this.seedType = type;
        // this.originalInjectionObject = iObj;
        // this.lastMutateIdentifierIndex = -1;
        // 顺序不能错，不然会空指针，因为getInjectionObjectInput会用到上面的。
        this.input = initInjectionObjectInputContainer();
    }

    public SeedWrapper(Object iObj, int iocdHash, LinkedHashMap<String, Object> backBoneObjectMap,
                       HashMap<String, Object> fldInFldObjectMap, HashSet<String> crtImmutableFieldSet, short type) throws FatalFuzzingException {
        this.iocdHash = iocdHash;
        this.id = GLOBAL_CURRENT_ID++;
        this.seedDistance = -1;
        this.gadgetCoverage = -1;
        this.coveredGdtNum = -1;
        this.coveredBscIfStmtName = 0;
        // this.coveredBBHash = new HashSet<>();
        this.injectionObj = iObj;
        this.backBoneObjectMap = backBoneObjectMap;
        this.fldInFldObjectMap = fldInFldObjectMap;
        this.crtSinkObjectMap = new HashMap<>();
        this.immutableFieldInfoSet = crtImmutableFieldSet;
        this.mutatedNoneBscConditionObjsSegMap = new HashMap<>();
        this.conObjectMap = new HashMap<>();
        // this.originalInjectionObject = iObj;
        // this.lastMutateIdentifierIndex = -1;
        // 顺序不能错，不然会空指针，因为getInjectionObjectInput会用到上面的。
        // this.input = initInjectionObjectInputContainer();
        this.seedType = type;
    }

    public SeedWrapper(Object iObj, double seedDistance, double gadgetCoverage, int coveredGdtNum, int coveredBscIfStmtName,
                       LinkedHashMap<String, Object> backBoneObjectMap, HashMap<String, Object> fldInFldObjectMap, HashSet<String> immutableFieldInfoSet,
                       int iocdHash, JDDFuzzGuidance.JDDInputContainer input,
                       int sinkIndex, short type) {
        this.id = GLOBAL_CURRENT_ID++;
        this.iocdHash = iocdHash;
        this.seedDistance = seedDistance;
        this.gadgetCoverage = gadgetCoverage;
        this.coveredGdtNum = coveredGdtNum;
        this.coveredBscIfStmtName = coveredBscIfStmtName;
        this.injectionObj = iObj;
        this.backBoneObjectMap = backBoneObjectMap;
        this.fldInFldObjectMap = fldInFldObjectMap;
        this.crtSinkObjectMap = new HashMap<>();
        this.immutableFieldInfoSet = immutableFieldInfoSet;
        this.conObjectMap = new HashMap<>();
        this.input = input;
        this.sinkIndex = sinkIndex;
        this.seedType = type;
    }

    public SeedWrapper clone() {
        IOCD crtIOCD = GlobalDataContainer.getIOCDByHash(iocdHash);
        Object clonedBackBoneObj = null;
        LinkedHashMap<String, Object> clonedBackBoneObjectMap = new LinkedHashMap<>();
        HashMap<String, Object> clonedFldInFldObjectMap = new HashMap<>();
        HashSet<String> clonedImmutatedFieldInfoSet = new HashSet<>();
        SeedWrapper clonedSeedWrapper = null;
        try {
            // 首先生成骨干类
            clonedBackBoneObj = SeedGenerator.generateBackboneObject(
                    iocdHash,
                    crtIOCD.getGadgetClasses(),
                    this.seedType,
                    crtIOCD.getProtocol(),
                    clonedBackBoneObjectMap,
                    clonedFldInFldObjectMap,
                    clonedImmutatedFieldInfoSet
            );
            clonedSeedWrapper = new SeedWrapper(
                    clonedBackBoneObj,
                    seedDistance,
                    gadgetCoverage,
                    this.coveredGdtNum,
                    this.coveredBscIfStmtName,
                    clonedBackBoneObjectMap,
                    clonedFldInFldObjectMap,
                    clonedImmutatedFieldInfoSet,
                    this.iocdHash,
                    this.input.clone(),
                    this.sinkIndex,
                    this.seedType);

            // The following statements should be in the correct order
            // make sure con-class objects generation always at first
            SeedGenerator.generateConClassObjects(GlobalDataContainer.getIOCDByHash(iocdHash), clonedSeedWrapper.conObjectMap);
            ValidatorGlobalDataContainer.getSinkPayloadsWrapperByIOCDHash(iocdHash).doSinkPayloadSettingInClone(clonedSeedWrapper);
            

        } catch (GeneratorException e) {
            return new DummySeedWrapper(new SeedCloneException("Seed clone failed", e));
        } catch (ReflectionException e) {
            throw new RuntimeException(e);
        }
        return clonedSeedWrapper;
    }

    /**
     * 判断一个field是否是可以变异的，如果可以变异就加入到序列中去
     *
     * @param clzNameWithIdColTag
     * @param fieldName
     * @return
     */
    public boolean isMutatableField(String clzNameWithIdColTag, String fieldName) {
        // 如果不可以变异的set中包含这个field，那就return false
        if (immutableFieldInfoSet.contains(clzNameWithIdColTag + "#" + fieldName)) {
            return false;
        }
        return true;
    }

    public boolean isMutatableCollisionField (String clzName, String fieldName, int clzId1, int clzId2) {
        String c1ClzKey = FuzzUtils.getRealClassNameKey(clzId1, "C1", clzName);
        String c2ClzKey = FuzzUtils.getRealClassNameKey(clzId2, "C2", clzName); // left for future adjustment
        // check if the field is mutable or not
        return isMutatableField(c1ClzKey, fieldName);
    }

    /**
     * check if a field is mutatable without col tag
     * 
     * @param clzNameId
     * @param fieldName
     * @return
     */
    public boolean isMutatableFieldWithoutColTag(String clzName, int clzId, String fieldName) {
        if (ValidatorGlobalDataContainer.isCollisionClassWrapper(iocdHash, clzName, clzId) != 0) {
            return isMutatableField(FuzzUtils.getRealClassNameKey(clzId, null, clzName) , fieldName);
        } else {
            // According to our current design, C1 and C2 is the same
            return isMutatableField(FuzzUtils.getRealClassNameKey(clzId, "C1", clzName), fieldName);
        }
    }

    /**
     * 判断一个noneBscCondition序列是否已经变异过了
     *
     * @param indexOfSeq
     * @param conditionSeqNum
     * @return
     */
    public boolean isMutatedConditionSeq(int indexOfSeq, int conditionSeqNum) {
        if (!mutatedNoneBscConditionObjsSegMap.containsKey(indexOfSeq)) {
            return false;
        }
        return mutatedNoneBscConditionObjsSegMap.get(indexOfSeq).contains(conditionSeqNum);
    }


    /**
     * 根据类名，获取相关的backBone Object
     *
     * @param clzName
     * @return
     */
    public Object getBackBoneObjByName(String clzName) {
        return backBoneObjectMap.get(clzName);
    }

    /**
     * 根据类名，获取相关的fldInFld Object
     *
     * @param clzName
     * @return
     */
    public Object getFldInFldObjByName(String clzName) {
        return fldInFldObjectMap.get(clzName);
    }

    public Object getConClassObjByName(String clzName) {
        Object res = conObjectMap.get(clzName);
        if (res == null) {
            for (String key : conObjectMap.keySet()) {
                if (ClassUtils.isSubOrImplClass(clzName, key)) {
                    res = conObjectMap.get(key);
                }
            }
        }
        return res;
    }

    public Object getObjFromInjectionObjByName(String clzName) throws ObjectNotFoundException {
        Object resObj = getBackBoneObjByName(clzName);
        if (resObj == null) {
            resObj = getFldInFldObjByName(clzName);
        }
        if (resObj == null) {
            resObj = getConClassObjByName(clzName);
        }
        if (resObj == null) {
            StringBuilder sb = new StringBuilder();
            StringBuilder sb2 = new StringBuilder();
            for (String backboneClzName : this.backBoneObjectMap.keySet()) {
                sb.append(backboneClzName).append("  ");
            }
            for (String fldInFldClzName : this.fldInFldObjectMap.keySet()) {
                sb2.append(fldInFldClzName).append("  ");
            }
            throw new ObjectNotFoundException("没能在" + iocdHash + "中找到" + clzName + "相关的对象，" +
                    "backBoneObjectMap中的类有：" + sb.toString() +
                    "，fldInFldObjectMap中的类有：" + sb2.toString());
        }
        return resObj;
    }

    public Pair<Object, Object> getSameNameCollisionObjectPair(String clzName, int id1, int id2) {
        // get the key to obtain the objects
        String c1ClzKey = FuzzUtils.getRealClassNameKey(id1, "C1", clzName);
        String c2ClzKey = FuzzUtils.getRealClassNameKey(id2, "C2", clzName);

        // obtain the objects
        Object obj1, obj2;
        try {
            obj1 = this.getObjFromInjectionObjByName(c1ClzKey);
            obj2 = this.getObjFromInjectionObjByName(c2ClzKey);
        } catch (ObjectNotFoundException onf) {
            LogUtils.warn("In `getSameNameCollisionObjectPair` with exception: Object with key " + c1ClzKey + " or "
                    + c2ClzKey + " not found.");
            return new Pair<Object,Object>(null, null);
        }
        if (obj1 == null || obj1 == null) {
            LogUtils.warn(
                    "In `getSameNameCollisionObjectPair`: Object with key " + c1ClzKey + " or " + c2ClzKey + " not found.");
            return new Pair<Object,Object>(null, null);
        }
        return new Pair<Object,Object>(obj1, obj2);
    }

    // intialize a int sequence for mutation
    public JDDFuzzGuidance.JDDInputContainer initInjectionObjectInputContainer() throws FatalFuzzingException {
        JDDFuzzGuidance.JDDInputContainer container = new JDDFuzzGuidance.JDDInputContainer(this);
        return container;
    }

    public void initInjectionObjectInputContainerOutter() throws FatalFuzzingException {
        this.input = new JDDFuzzGuidance.JDDInputContainer(this);
    }

    /**
     * return backbone object identifier list for mutation
     */
    public LinkedList<Integer> getMutationIdentifiers() {
        return input.identifierList;
    }

    /**
     * return the int sequence for mutation
     */
    public ArrayList<Integer> getMuataionSequence() {
        return input.currentInput.values;
    }

    /**
     * get a four byte sequence from the mutation sequence with a given 'startIndex'
     * 
     * @param startIndex
     * @return
     */
    public byte[] getMutationSequenceBytes(int startIndex) {
        byte[] resBytes = new byte[4];
        for (int k = startIndex, p = 0; k < startIndex + 4; k++, p++) {
            if (k < input.currentInput.size())
                resBytes[p] = input.currentInput.values.get(k).byteValue();
        }
        return resBytes;
    }

    public int getMutationFieldIndexWithSequanceIndex(int sequenceIndex) {
        return input.propIndexInSeq.get(sequenceIndex);
    }

    public Field getMutaionFieldWithIndex(int fieldIndex) {
        return input.seedPropertiesMap.get(fieldIndex);
    }

    public String getBackboneClassNameKeyWithIndex(int index) {
        return input.backBoneObjClzName.get(index);
    }

    public void setConClzObjectMap(HashMap<String, Object> conObjectMap) {
        this.conObjectMap = conObjectMap;
    }

    public void recordExploitableSeedWrapper2File() {
        try {
            // the bytes of the validation that succeeded (jdk or hessian, the IOCD's protocol)
            byte[] payload = cn.edu.fudan.fuzz.ExploitabilityValidateTrigger.lastPayload;
            FileUtils.writeByteArrayToFile(ConfigContainer.exploitableDir + File.separator + iocdHash + "exp",
                    payload != null ? payload : FuzzUtils.serializeJdk(injectionObj));
        } catch (Exception e) {
            LogUtils.warn("Failed to serialize seed object to file: " + iocdHash);
            e.printStackTrace();
        }
    }

    /**
     * if the seed is interesting(Coverage become higher or Distance to sink become shorter), then record it to file
     * This method would ouput 2 files
     *  - meta data of seed named `<iocd_hash>_<seed_id>_mata`, which including
     *      - ioch hash
     *      - gadget chain
     *      - seed distance
     *      - seed converage
     *      - last arrived dominator
     *  - the binary form of injection object named same with its `<iocd_hash>_<seed_id>`
     */
    public void recordSeedWrapper2File() {
        String iocdHashStr = String.valueOf(this.iocdHash);
        String seedFileName = iocdHashStr + "_" + String.valueOf(id);
        String metaFileName = iocdHashStr + "_" + String.valueOf(id) + "_meta";
        
        // construct and store metadata
        StringBuilder sb = new StringBuilder();
        sb.append("hash: ").append(iocdHashStr).append("\n");
        sb.append("gadget chain: \n");
        GlobalDataContainer.getIOCDByHash(iocdHash)
                            .getGadgetCallStack()
                            .forEach(gdtPair -> sb.append("    ").append(gdtPair.getValue()).append("\n"));
        sb.append("seedgadget coverage: ").append(gadgetCoverage).append("\n");
        sb.append("seed distance: ").append(seedDistance).append("\n");
        FileUtils.writeStringToFile(constructCorpusFileName(metaFileName, iocdHashStr), sb.toString());
        
        // save exploit object binary
        try {
            IOCD iocd = GlobalDataContainer.getIOCDByHash(iocdHash);
            byte[] expBytes = iocd.getProtocol().equals("hessian") ? serializeHessian(injectionObj) : FuzzUtils.serializeJdk(injectionObj);
            FileUtils.writeByteArrayToFile(constructCorpusFileName(seedFileName, iocdHashStr), expBytes);
        } catch (Exception e) {
            LogUtils.warn("Failed to serialize seed object to file: " + seedFileName);
            e.printStackTrace();
        }
        
    }

    private String constructCorpusFileName(String fileName, String iocdHashStr) {
        return ConfigContainer.outputDir + File.separator + iocdHashStr + File.separator + "corpus" + File.separator + fileName;
    }

    /**
     * 优先将seedDistance小且gadgetCoverage大的排在前面
     */
    public static class SeedWrapperComparator implements Comparator<SeedWrapper> {
        @Override
        public int compare(SeedWrapper wrapper1, SeedWrapper wrapper2) {
            // 先比较seedDistance
            int seedDistanceComparison = Double.compare(wrapper1.seedDistance, wrapper2.seedDistance);
            if (seedDistanceComparison != 0) {
                return seedDistanceComparison;
            }

            // 如果seedDistance相等，则比较gadgetCoverage
            return Double.compare(wrapper2.gadgetCoverage, wrapper1.gadgetCoverage);
        }
    }


    public static class DummySeedWrapper extends SeedWrapper {
        public Throwable e;

        public DummySeedWrapper(Throwable e) {
            this.e = e;
        }
    }

    public static byte[] serializeHessian(Object o) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Hessian2Output hessian2Output = new Hessian2Output(outputStream);
        SerializerFactory sf = new SerializerFactory();
        sf.setAllowNonSerializable(true);
        hessian2Output.setSerializerFactory(sf);
        hessian2Output.writeObject(o);
        hessian2Output.flush();
        return outputStream.toByteArray();
    }

}
