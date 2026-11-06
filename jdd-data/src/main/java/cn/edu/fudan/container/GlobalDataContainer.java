package cn.edu.fudan.container;

import cn.edu.fudan.data.distance.GadgetCFGWrapper;
import cn.edu.fudan.data.distance.data.BasicBlock;
import cn.edu.fudan.data.distance.data.CFG;
import cn.edu.fudan.data.distance.data.InstNode;
import cn.edu.fudan.exception.inst.BuildCFGException;
import cn.edu.fudan.exception.inst.CFGException;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.FileUtils;
import cn.edu.fudan.utils.FuzzUtils;
import cn.edu.fudan.utils.LogUtils;
import datastructrue.Pair;
import datastructrue.iocd.IOCD;
import datastructrue.iocd.unit.*;

import java.io.File;
import java.util.*;

public class GlobalDataContainer {

    public boolean isStartWithAgent;

    // 存储IOCD的hash和其本身，<IOCDHash, [IOCDHash]>
    public HashMap<Integer, IOCD> hashIOCDMap;
    // 存储IOCD的hash和其对应的ICFG
    public HashMap<Integer, CFG> hashICFGMap;
    // 存储IOCD的hash和对应的Gadget中method的hash <IOCDHash, [<gdtMtdHash>]>
    public HashMap<Integer, LinkedList<Integer>> gdtMtdHashMap;
    // IOCDHash - 所有SinkRecord
    public HashMap<Integer, LinkedHashSet<Pair<SinkRecord, Boolean>>> gdtSinkRecordHashMap;
    // IOCDHash - Basic Condition
    public HashMap<Integer, LinkedHashMap<Integer, ConditionRecord>> ifStmtBscConditionHashMap;
    // IOCDHash - All Condition
    public HashMap<Integer, LinkedHashMap<Integer, ConditionRecord>> ifStmtAllConditionHashMap;
    // IOCDHash - <所有Condition的hash - 是否是basic>
    public HashMap<Integer, LinkedHashMap<Integer, Boolean>> ifStmtConditionStatusLHMap;
    // IOCDHash - <所有存在关联关系的fld>
    public HashMap<Integer, HashSet<String>> gdtDepFldHashMap;
    // IOCDHash - <All Field Dependecy Record>
    public HashMap<Integer, HashSet<DependenceRecord>> gdtDepFldRecordHashMap;
    // IOCDHash - 所有的ConClassNode
    public HashMap<Integer, HashSet<ClassRecord>> gdtConClassNodeHashMap;
    // IOCDHash - 对应的CollisionRecord
    public HashMap<Integer, CollisionRecord> gdtCollisionRecordHashMap;
    // IOCDHash - class used for collision
    public HashMap<Integer, HashSet<Pair<String, Integer>>> gdtCollisionClassHashMap;
    // 文件名 - IOCDHash
    public HashMap<Integer, String> gdtHashFileNameMap;
    // IOCDHash - Gadget Class related field signature set
    // for preventing change the injection object sturcture during setting sink/condition value
    public HashMap<Integer, HashSet<String>> gdtHashGadgetClzFieldSig;
    // 主干Gadget Class中为抽象类的记录，IOCDHash - <abstractClzName, [<ImplAbstractClzName, >]>
    public HashMap<Integer, LinkedHashMap<String, HashSet<Pair<String, Boolean>>>> gdtHashAbstractImplClzMap;
    // 所有用到的field的信息，IOCDHash - <<记录了使用字段的clzName,[使用的行号]>>>
    public HashMap<Integer, HashMap<String, HashSet<Integer>>> gdtHashUsedFieldClassMap;

    // <IOCDHash, CFG在list中的索引>
    public HashMap<Integer, Integer> cfgWrapperHashMap;
    public ArrayList<GadgetCFGWrapper> cfgWrapperList;
    // <类名，对应的基本块插桩点>所有的基本块插桩点，包含了自制CFG的，也包含来自波妃给的IfStmtRecord里的
    public HashMap<String, HashSet<InstNode>> bbInstInfoMap;
    public HashMap<Integer, InstNode> onlyBBInstInfoMap;
    // <类名，对应的sink插桩点>所有的Sink插桩点，全部来源于波妃给的IfStmtRecord
    public HashMap<String, HashSet<String>> sinkInstMtdInfoMap;
    // Family-scoped, separate from the whole-app sink list above: for a sink method whose argument carries
    // the reachability marker (JNDI/CUSTOM/CLOJURE families), className -> ("name#desc" -> 0-based index of
    // the Object/array argument to read). Only these methods get the extra marker-read hook; the generic
    // entry hook (doSinkArrivedFeedBack) is left on every sink method unchanged. Empty until a family fills it.
    public HashMap<String, HashMap<String, Integer>> sinkMarkerArgMap;

    public static boolean isInit = false;
    public static boolean isDebug = true;

    public static boolean isLoadIOCDDebug = true;

    private static GlobalDataContainer globalDataContainer = new GlobalDataContainer();

    private GlobalDataContainer() {
    }

    // Notice: 设计成单例而不是纯静态类，是考虑到Fuzz过程中插桩器和Fuzz器会同时使用，避免数据公用时出现问题，同时这样设计也方便在Fuzz过程中对数据进行持久化
    public static GlobalDataContainer getInstance() {
        if (isInit) {
            return globalDataContainer;
        } else {
            globalDataContainer.init();
            isInit = true;
        }
        return globalDataContainer;
    }

    public void init() {

        hashIOCDMap = new HashMap<>();
        gdtMtdHashMap = new HashMap<>();
        gdtSinkRecordHashMap = new HashMap<>();
        ifStmtBscConditionHashMap = new HashMap<>();
        ifStmtAllConditionHashMap = new HashMap<>();
        ifStmtConditionStatusLHMap = new HashMap<>();
        gdtDepFldHashMap = new HashMap<>();
        gdtHashFileNameMap = new HashMap<>();
        gdtHashAbstractImplClzMap = new LinkedHashMap<>();
        gdtConClassNodeHashMap = new LinkedHashMap<>();
        gdtHashUsedFieldClassMap = new HashMap<>();
        gdtCollisionRecordHashMap = new HashMap<>();
        gdtCollisionClassHashMap = new HashMap<>();
        cfgWrapperHashMap = new HashMap<>();
        bbInstInfoMap = new HashMap<>();
        onlyBBInstInfoMap = new HashMap<>();
        sinkInstMtdInfoMap = new HashMap<>();
        sinkMarkerArgMap = new HashMap<>();
        cfgWrapperList = new ArrayList<>();
        gdtDepFldRecordHashMap = new HashMap<>();
        gdtHashGadgetClzFieldSig = new HashMap<>();

        this.loadGadgetInfos();
    }

    public void setStartWithAgent() {
        this.isStartWithAgent = true;
    }

    public boolean isStartWithAgent() {
        return this.isStartWithAgent;
    }

    /**
     *  check if a class is a collision related class
     * 
     * @param hash
     * @param clzName
     * @return -1 means not a collision-related gadget, 0 means type2 collsion, 1 means other type of collision
     */
    public int isType2CollisionClass(int hash, String clzName, int id) {
        // if the gadget is not a collision type, then we directly return false
        if (!hashIOCDMap.get(hash).hashCollisionFlag) {
            return -1;
        }
        HashSet<Pair<String, Integer>> collisionClzSet = gdtCollisionClassHashMap.get(hash);
        if (collisionClzSet != null && !collisionClzSet.isEmpty()) {
            boolean isFound = false;
            // just double check it. 
            for (Pair<String, Integer> clzPair : collisionClzSet) {
                if (clzPair.getKey().equals(clzName) && clzPair.getValue() == id) {
                    isFound = true;
                    break;
                }
            }
            if (isFound) {
                int collisionType = gdtCollisionRecordHashMap.get(hash).type;
                return FuzzUtils.isType2Or3Collision(collisionType) ? 0 : 1;
            }
        }
        return -1;
    }

    /**
     * would be called when we can not access SeedWrapper
     * 
     * @return
     */
    public boolean isFieldSettableByFieldSig(String fieldFullSig) {
        return !gdtHashGadgetClzFieldSig.values().stream().anyMatch(sigSet -> sigSet.contains(fieldFullSig));
    }

    /**
     * 从静态部分生成的包含Candidate Gadget的文件中获取这些Gadget信息
     */
    private void loadGadgetInfos() {
        HashSet<File> gadgetFiles = FileUtils.getJsonFilesFromDir(ConfigContainer.gadgetInfosDir);
        int total, finished = 0;
        total = gadgetFiles.size();
        for (File gadgetFile : gadgetFiles) {
            // int iocdHash = -1;
            IOCD iocd;
            try {
                iocd = (IOCD) FileUtils.readFromJson(gadgetFile, IOCD.class);
            } catch (Exception e) {
                LogUtils.warn("导入" + gadgetFile.getName() + "时出错：" + e.getMessage());
                LogUtils.warn2Log("导入" + gadgetFile.getName() + "时出错：" + e.getMessage());
                continue;
            }
            // 填充Hash-fileNameMap
            // gdtHashFileNameMap.put(iocd.hashCode(), gadgetFile.getName());
            // 利用hash索引iocd和对应的ConditionRecord
            try {
                this.addIOCD(iocd.getHashCode(), iocd);
                this.extractMtdIfStmtHash(iocd);
                this.extractDepFld(iocd);
                this.extractGadgetAbstractClzImpl(iocd);
                this.extractConClassNode(iocd);
                this.extractAllUsedFiled(iocd);
                this.addGadgetMtdHashRecord(iocd);
                this.extractCollisionRecord(iocd);
                this.extractGdtHashGadgetClzFieldSig(iocd);
                LogUtils.info("Load seed " + iocd.getHashCode() + " success: " + (++finished) + "/" + total);
            } catch (Exception e) {
                removeIOCD(iocd.getHashCode());
                FuzzUtils.moveToWrongSeedDir(ConfigContainer.gadgetInfosDir + File.separator +  iocd.hashCode + ".json",
                ConfigContainer.projectsInputDir + File.separator + "finished" + File.separator + "bad-seed-during-load" + File.separator + iocd.hashCode + ".json");
                LogUtils.warn("Load seed " + iocd.getHashCode() + " failed: <" + e.getMessage() + ">, check logs for details discard!");
                LogUtils.warn2Log("Load seed " + iocd.getHashCode() + "failed: <" + e.getMessage() + ">, check logs for details discard!");
                LogUtils.warn2Log(CommonUtils.constructExceptionStackTrace(e));
                LogUtils.info("Load seed " + iocd.getHashCode() + " success: " + (++finished) + "/" + total);
            }
        }
    }

    /**
     * 向hashIOCDMap添加新的IOCD
     *
     * @param iocd 从外部读取的IOCD
     */
    private void addIOCD(int fileHash, IOCD iocd) {

        hashIOCDMap.put(fileHash, iocd);

    }

    private void removeIOCD(int fileHash) {
        hashIOCDMap.remove(fileHash);
    }

    /**
     * 向gdtMtdHashMap添加Gadget方法和对应gdtHash的映射
     *
     * @param iocd 从外部读取的IOCD
     */
    private void addGadgetMtdHashRecord(IOCD iocd) {

        LinkedList<Pair<Integer, String>> tmpCS = iocd.getGadgetCallStack();
        LinkedList<Integer> gdtMtdList = new LinkedList<>();
        tmpCS.forEach(mtdPair -> gdtMtdList.add(mtdPair.getKey()));
        gdtMtdHashMap.put(iocd.getHashCode(), new LinkedList<>(gdtMtdList));

    }

    public void buildAllIOCDCFG() throws BuildCFGException {
        for (IOCD iocd : GlobalDataContainer.getAllIOCD()) {
            GadgetCFGWrapper gadgetICFG = new GadgetCFGWrapper(iocd);
            cfgWrapperHashMap.put(iocd.getHashCode(), cfgWrapperList.size());
            cfgWrapperList.add(gadgetICFG);
        }
    }

    /**
     * 根据iocd中的信息，按照执行的顺序导出Hash-BscCondition和Hash-AllCondition的值
     *
     * @param iocd 从外部读取的IOCD
     */
    private void extractMtdIfStmtHash(IOCD iocd) {

        LinkedList<ClassRecord> gdtClzList = iocd.getGadgetClasses();
        int recHash = iocd.getHashCode();
        // 处理所有Condition的执行顺序和是否是basic
        LinkedHashMap<Integer, Boolean> conditionLHMap = iocd.getConditionsRecords();
        this.ifStmtConditionStatusLHMap.put(recHash, conditionLHMap);
        // 遍历每个Class中的Condition并记录到对应的容器中
        for (ClassRecord clzRecord : gdtClzList) {
            // 处理All Condition
            List<ConditionRecord> allConList = clzRecord.getAllConditions();
            List <ConditionRecord> filteredRecords = new LinkedList<>();
            for (ConditionRecord conditionRecord : allConList) {
                if (conditionRecord.getConditionName() == null || conditionRecord.getConditionName().isEmpty()) {
                    //if (!conditionRecord.isBasic()) {
                    LogUtils.warn("In `extractMtdIfStmtHash`: " + iocd.getHashCode() + " has empty conditionName(can be ignored)");
                    if (conditionRecord.isBasic()) {
                        LogUtils.warn("    this is a basic condition, it may lead to fuzz exception!");
                    }
                    continue;
                    // } else {
                    //     throw new RuntimeException("In `extractMtdIfStmtHash`: " + iocd.getHashCode() + " has empty conditionName, and it is basic condition!!!");
                    // }
                }
                if (conditionRecord.getConditionValue() == null || conditionRecord.getConditionValue().isEmpty()) {
                    //if (!conditionRecord.isBasic()) {
                    LogUtils.warn("In `extractMtdIfStmtHash`: " + iocd.getHashCode() + " has empty conditionValue(can be ignored)");
                    if (conditionRecord.isBasic()) {
                        LogUtils.warn("    this is a basic condition, it may lead to fuzz exception!");
                    }
                    continue;
                    // } else {
                    //     throw new RuntimeException("In `extractMtdIfStmtHash`: " + iocd.getHashCode() + " has empty conditionValue, and it is basic condition!!!");
                    // }
                }
                filteredRecords.add(conditionRecord);
            }
            fillConditionMap(recHash, filteredRecords, this.ifStmtAllConditionHashMap);
            // 获取所有的Basic Condition
            List<ConditionRecord> bscConList = new LinkedList<>();
            for (ConditionRecord allCon : filteredRecords) {
                if (allCon.basic) {
                    bscConList.add(allCon);
                }
            }
            fillConditionMap(recHash, bscConList, this.ifStmtBscConditionHashMap);
        }

    }

    /**
     * 提取record所有的依赖fld信息
     *
     * @param iocd 从外部读取的IOCD
     */
    private void extractDepFld(IOCD iocd) {

        int gdtHash = iocd.getHashCode();
        HashSet<String> sigSet = new HashSet<>();
        Set<DependenceRecord> deps = iocd.getDependenceRecords();
        gdtDepFldRecordHashMap.put(gdtHash, new HashSet<>(deps));
        for (DependenceRecord dep : deps) {
            sigSet.add(dep.getRightRecordF().getSig());
            sigSet.add(dep.getLeftRecordF().getSig());
        }
        gdtDepFldHashMap.put(gdtHash, sigSet);

    }

    /**
     * 提取所有的哈希碰撞相关信息，以便快速访问
     *
     * @param iocd
     */
    private void extractCollisionRecord(IOCD iocd) {

        int gdtHash = iocd.getHashCode();
        CollisionRecord collisionRecord = iocd.getHashCollisionRecord();
        if (iocd.isHashCollisionFlag() && collisionRecord != null && collisionRecord.type != 1) {
            // cache CollisionRecord
            gdtCollisionRecordHashMap.put(gdtHash, collisionRecord);
            // cache the collision related class
            HashSet<Pair<String, Integer>> tmpSet = new HashSet<>();
            if (collisionRecord.first != null && !collisionRecord.first.isEmpty()
                    && collisionRecord.second != null && !collisionRecord.second.isEmpty()) {
                tmpSet.add(
                        getGdtCollisionClassPair(collisionRecord.first.get(0).getClassBelongTo(),
                                iocd)
                );
                tmpSet.add(
                        getGdtCollisionClassPair(collisionRecord.second.get(0).getClassBelongTo(),
                                iocd)
                );
            } else {
                // In some cases, the static analysis can not extract the collision class.
                //  We treat the Class in the `firstHashCodeMtd` and `secondHashCodeMtd` as the collision classes.
                String firstCollisionClassName = collisionRecord.firstHashCodeMtd.getClassBelongTo();
                String secondCollisionClassName = collisionRecord.secondHashCodeMtd.getClassBelongTo();
                tmpSet.add(
                        getGdtCollisionClassPair(firstCollisionClassName, iocd)
                );
                tmpSet.add(
                        getGdtCollisionClassPair(secondCollisionClassName, iocd)
                );
            }

            gdtCollisionClassHashMap.put(gdtHash, tmpSet);
        } else {
            gdtCollisionRecordHashMap.put(gdtHash, null);
        }

    }

    private Pair<String, Integer> getGdtCollisionClassPair(String clzName, IOCD iocd) {
        // before update, we need to check if the class name is same with the top class
        String topClassName = iocd.getGadgetClasses().getFirst().className;
        // for type-2/3 collisions the collision class is the second gadget class (see
        // SeedGenerator.generateAllBackboneRelatedObjects), even when later classes share its name
        if (iocd.getHashCollisionRecord() != null && iocd.getHashCollisionRecord().type != 1
                && iocd.getGadgetClasses().size() > 1
                && iocd.getGadgetClasses().get(1).getClassName().equals(clzName)) {
            return new Pair<>(clzName, iocd.getGadgetClasses().get(1).getId());
        }
        if (clzName.equals(topClassName)){
            // find the collision class record, we regard the last one we meet as the collision class
            ClassRecord neededClassRecord = null;
            for (int i = iocd.getGadgetClasses().size() - 1; i >= 0; i--) {
                ClassRecord cr = iocd.getGadgetClasses().get(i);
                if (cr.getClassName().equals(clzName)) {
                    neededClassRecord = cr;
                    break;
                }
            }
            if (neededClassRecord != null) {
                return new Pair<>(topClassName, neededClassRecord.getId());
            } else {
                throw new RuntimeException("In `getGdtCollisionClassPair`: " + iocd.getHashCode() + " has collision class " + clzName + " but not found in the gadget classes");
            }
        } else {
            return new Pair<String,Integer>(clzName, 0);
        }
    }

    /**
     * this field are immutable
     */
    private void extractGdtHashGadgetClzFieldSig(IOCD iocd) {

        int gdtHash = iocd.getHashCode();
        HashSet<String> sigSet = new HashSet<>();
        LinkedList<ClassRecord> gdtClzList = iocd.getGadgetClasses();
        for (ClassRecord clzRecord : gdtClzList) {
            List<String> prePath = clzRecord.getPreAccessPath();
            sigSet.addAll(prePath);
        }
        gdtHashGadgetClzFieldSig.put(gdtHash, sigSet);

    }


    /**
     * 如果GadgetClass中出现了抽象类，那么就从当前运行环境的Class Path中提取所有的实现类备用，用于变异
     *
     * @param iocd 从外部读取的IOCD
     */
    private void extractGadgetAbstractClzImpl(IOCD iocd) {

        LinkedList<ClassRecord> gadgetClzList = iocd.getGadgetClasses();
        // 构造一个存储抽象类名-具体实现类Pair的Map
        LinkedHashMap<String, HashSet<Pair<String, Boolean>>> tmpMap = new LinkedHashMap<>();
        for (ClassRecord cr : gadgetClzList) {
            String gdtClzName = cr.getClassName();
            if (ClassUtils.isConcreteByClzName(gdtClzName)) {
                continue;
            }
            // 不同协议的Gadget，不同处理
            Set<String> implClzSet = ClassUtils.getAllConcreteSubClass(gdtClzName, CommonUtils.isSerializableNeed(iocd.getProtocol()));
            // 添加到gdtHashAbstractImplClzMap中去
            HashSet<Pair<String, Boolean>> tmpSet = new HashSet<>();
            for (String implClz : implClzSet) {
                // 先构造Pair
                Pair<String, Boolean> tmpPair = new Pair<>(implClz, false);
                // 后放到tmpSet中去
                tmpSet.add(tmpPair);
            }
            // 添加到Map中
            tmpMap.put(gdtClzName, tmpSet);
        }
        // 最后将tmpMap存储到全局数据结构中去
        this.gdtHashAbstractImplClzMap.put(iocd.getHashCode(), tmpMap);


    }

    /**
     * 提取不是主干类但是和Gadget构造相关的类
     *
     * @param iocd 需要被提取的IOCD
     */
    private void extractConClassNode(IOCD iocd) {
        this.gdtConClassNodeHashMap.put(iocd.getHashCode(), iocd.getConClassNodes());

    }

    /**
     * 将recHash对应的iocd里的Condition信息填充到targetMap中
     *
     * @param recHash   IOCD的hash
     * @param conList   从Record中取出来的ConditionRecord
     * @param targetMap 存储用的Map
     */
    private void fillConditionMap(int recHash, List<ConditionRecord> conList,
                                  Map<Integer, LinkedHashMap<Integer, ConditionRecord>> targetMap) {

        // 如果之前没记录过对应recHash的Condition信息，就新建一个条目
        if (!targetMap.containsKey(recHash)) {
            targetMap.put(recHash, null);
        }
        // 遍历所有ConditionRecord并进行处理
        for (ConditionRecord bscCon : conList) {
            // NOTICE: 此处做临时处理，对于ConditionName为空/ConditionName为0的情况，不记录
            if (bscCon.type == 2) {
                continue;
            }
            if (bscCon.conditionName == null || bscCon.conditionName.isEmpty()) {
                continue;
            }
            String tmpConditionNameKey = bscCon.getConditionName().keySet().iterator().next();
            if (CommonUtils.startWithNumber(tmpConditionNameKey)) {
                continue;
            }

            int tmpBscHash = bscCon.getHashCode();
            LinkedHashMap<Integer, ConditionRecord> currentMap = targetMap.get(recHash);
            // 如果之前条目为空那就新建一个
            if (currentMap == null) {
                LinkedHashMap<Integer, ConditionRecord> tmpMap = new LinkedHashMap<>();
                tmpMap.put(tmpBscHash, bscCon);
                targetMap.put(recHash, tmpMap);
                continue;
            }
            // 如果已存在条目，那就put新的进去
            currentMap.put(tmpBscHash, bscCon);
        }

    }

    /**
     * 提取IOCD中所有的usedField信息，主要从两个地方找：
     * 1、主干Gadget Class列表
     * 2、单独的usedFieldRecord里面
     *
     * @param iocd 从外部读取的IOCD
     */
    public void extractAllUsedFiled(IOCD iocd) {

        int hash = iocd.getHashCode();
        // 创建某个record的项目
        if (!gdtHashUsedFieldClassMap.containsKey(hash)) {
            HashMap<String, HashSet<Integer>> clzNameSitesMap = new HashMap<>();
            gdtHashUsedFieldClassMap.put(hash, clzNameSitesMap);
        }
        HashMap<String, HashSet<Integer>> tmpSitesMap = gdtHashUsedFieldClassMap.get(hash);
        // 从record中拿
        for (UsedSiteRecord usedSiteRcd : iocd.usedFieldsRecords) {
            String tmpClzName = usedSiteRcd.inClassName;
            HashSet<Integer> tmpSites;
            if (tmpSitesMap.containsKey(tmpClzName)) {
                tmpSites = tmpSitesMap.get(tmpClzName);
            } else {
                tmpSites = new HashSet<>();
                tmpSitesMap.put(tmpClzName, tmpSites);
            }
            tmpSites.add(usedSiteRcd.site);
        }

    }



    public static IOCD getIOCDByHash(int hash) {
        return GlobalDataContainer.getInstance().hashIOCDMap.get(hash);
    }

    public static String getSerializeProtolByIOCDHash(int hash) {
        IOCD iocd = getIOCDByHash(hash);
        if (iocd != null) {
            return iocd.getProtocol();
        }
        LogUtils.warn("In `getSerializeProtolByIOCDHash`: iocd " + hash + " do not exist!");
        return null;
    }

    public static LinkedHashMap<Integer, ConditionRecord> getConditionMapByIOCDHash(int hash) {
        return GlobalDataContainer.getInstance().ifStmtAllConditionHashMap.get(hash);
    }

    public static HashSet<DependenceRecord> getDependenceRecordsByIOCDHash(int hash) {
        return GlobalDataContainer.getInstance().gdtDepFldRecordHashMap.get(hash);
    }

    public static LinkedList<Integer> getGadgetHashListByIOCDHash(int hash) {
        return GlobalDataContainer.getInstance().gdtMtdHashMap.get(hash);
    }

    public static Set<Map.Entry<Integer, IOCD>> getAllIOCDEntry() {
        return GlobalDataContainer.getInstance().hashIOCDMap.entrySet();
    }

    public static Collection<IOCD> getAllIOCD() {
        return GlobalDataContainer.getInstance().hashIOCDMap.values();
    }

    public static GadgetCFGWrapper getCFGWrapperByIOCDHash(int hash) {
        int index = GlobalDataContainer.getInstance().cfgWrapperHashMap.get(hash);
        return GlobalDataContainer.getInstance().cfgWrapperList.get(index);
    }

    /**
     * 根据IOCDHash获取一条Gadget上的(bbHash, bb)Map
     *
     */
    public static HashMap<Integer, BasicBlock> getAllGadgetBscBlockMapByIOCDHash(int iocdHash) {
        return getCFGWrapperByIOCDHash(iocdHash).bscBlockMap;
    }

}
