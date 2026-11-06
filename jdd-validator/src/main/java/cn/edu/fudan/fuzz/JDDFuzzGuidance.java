package cn.edu.fudan.fuzz;

import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import cn.edu.fudan.data.distance.GadgetCFGWrapper;
import cn.edu.fudan.data.distance.data.BasicBlock;
import cn.edu.fudan.exception.inst.CFGException;
import cn.edu.fudan.exception.validator.*;
import cn.edu.fudan.generator.mutate.ExceptionBasedMutator;
import cn.edu.fudan.generator.mutate.MutationObjectGenerator;
import cn.edu.fudan.generator.seed.SeedWrapper;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.LogUtils;
import cn.edu.fudan.utils.ReflectionUtils;
import datastructrue.inst.JDDFuzzRecord;
import edu.berkeley.cs.jqf.fuzz.ei.ZestGuidance;
import edu.berkeley.cs.jqf.fuzz.guidance.Guidance;
import edu.berkeley.cs.jqf.fuzz.guidance.GuidanceException;
import edu.berkeley.cs.jqf.fuzz.guidance.Result;
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

public class JDDFuzzGuidance implements Guidance {

    // 伪随机数生成器
    protected Random random;
    // Guidance开始的时间
    protected final Date startTime = new Date();
    // Fuzzer运行的超时时间
    protected final long maxDurationMillis;
    // Fuzzer最大尝试次数
    protected final long maxTrials;
    // 尝试次数
    protected long numTrials = 0;
    protected double maxMetSeedDistance = -1; // 直接设置成Entry到达Sink点的距离
    protected double minMetSeedDistance = -1;
    protected double maxCov = -1;
    protected double maxArrivedMtdNum = -1;
    protected double minArrivedMtdNum = -1;

    protected HashSet<Integer> coveragedBscBlock = new HashSet<>();

    // 遍历出来变异
    protected PriorityQueue<SeedWrapper> favoredQueue = new PriorityQueue<>(new SeedWrapper.SeedWrapperComparator());


    // 是否能够到达Sink
    public boolean isReachableSink = false;

    // Fuzzer当前正在运行的input
    protected JDDInputContainer currentInputContainer;
    // Fuzzer当前正在运行的SeedWrapper
    public SeedWrapper currentSeedWrapper;
    // Fuzzer当前轮次正在运行的SeedWrapper
    public SeedWrapper currentRoundSeedWrapper;
    // 根据论文中的算法，每次针对一个种子进行变异
    public boolean isRoundFinished = true;

    boolean isNextRandomMutateClzDebug = true;


    /**
     * @param duration
     * @param trials
     * @param sourceOfRandomness
     */
    public JDDFuzzGuidance(Duration duration, Long trials, Random sourceOfRandomness) {
        this.maxDurationMillis = duration != null ? duration.toMillis() : Long.MAX_VALUE;
        this.maxTrials = trials != null ? trials : Long.MAX_VALUE;
        this.random = sourceOfRandomness;
        this.isRoundFinished = false;
    }

    public void addToFavorQueue(SeedWrapper sw) {
        this.favoredQueue.add(sw);
    }


    // 直接拿Zest的过来用
    protected InputStream createParameterStream() {
        // Return an input stream that reads bytes from a linear array
        return new InputStream() {
            int bytesRead = 0;

            @Override
            public int read() throws IOException {
                assert currentInputContainer instanceof JDDInputContainer : "JDDFuzzGuidance should only mutate JDDLinearInput(s)";

                // For linear inputs, get with key = bytesRead (which is then incremented)
                JDDLinearInput linearInput = (JDDLinearInput) currentInputContainer.currentInput;
                // Attempt to get a value from the list, or else generate a random value
                int ret = linearInput.getOrGenerateFresh(bytesRead++, random);
                // infoLog("read(%d) = %d", bytesRead, ret);
                return ret;
            }
        };
    }

    @Override
    public InputStream getInput() throws NormalFinishedFuzzingException {

        // 开启下一轮fuzz
        if (this.isRoundFinished) {
            this.isRoundFinished = false;
            // 只考虑通过现有的种子作为输入
            if (!favoredQueue.isEmpty()) {
                // 取出种子，并进行克隆
                this.currentRoundSeedWrapper = selectSeed();
                if (this.currentRoundSeedWrapper == null) {
                    LogUtils.info("All seeds have been mutated, existing the fuzzing process");
                    throw new NormalFinishedFuzzingException("The gadget with hash " + this.currentSeedWrapper.iocdHash + " has been fuzzed completely, existing the fuzzing process");
                }
                this.currentRoundSeedWrapper.isMutated = true;
                if (this.currentRoundSeedWrapper.coveredGdtNum == -1) {
                    this.favoredQueue.add(currentRoundSeedWrapper);
                }
                SeedWrapper clonedSeedWrapper = this.currentRoundSeedWrapper.clone();
                if (clonedSeedWrapper instanceof SeedWrapper.DummySeedWrapper) {
                    LogUtils.error("In `JDDFuzzGuidance.getInput`: seed clone failed, return null");
                    ((SeedWrapper.DummySeedWrapper) clonedSeedWrapper).e.printStackTrace();
                    return null;
                }
                this.currentSeedWrapper = clonedSeedWrapper;
                // 赋值给生成器/变异器
                MutationObjectGenerator.crtInjectObjectWrapper = currentSeedWrapper;
                this.currentInputContainer = this.currentSeedWrapper.input;
                // Notice: 消融实验也用度量
                if (ValidatorGlobalDataContainer.isAblationWithoutIOCD) {
                    this.calculateSeedPower(this.currentSeedWrapper);
                } else {
                    this.calculateSeedPower(this.currentSeedWrapper);
                }

            } else {
                LogUtils.warn("In `JDDFuzzGuidance.getInput`: Can not find any interesting seed in favoredQueue, return null");
                return null;
            }
        } else { // 进行当前轮变异
            this.currentSeedWrapper = this.currentRoundSeedWrapper.clone();
            // 赋值给生成器/变异器
            MutationObjectGenerator.crtInjectObjectWrapper = this.currentSeedWrapper;
            JDDInputContainer mutatedInputs = ((JDDInputContainer) this.currentInputContainer).fuzz();
            this.currentSeedWrapper.input = mutatedInputs;
            this.currentInputContainer = this.currentSeedWrapper.input;

            logSeqAfterMutation(this.currentSeedWrapper.input.currentInput.values);
        }

        return createParameterStream();
    }

    private void logSeqAfterMutation(ArrayList<Integer> seqs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Sequence AFTER mutation: ");
        for (int b : seqs) {
            sb.append(b + " ");
        }
        sb.append("\n");
        sb.append("\n===========seed-mutated==============\n");
        LogUtils.log2FuzzerStat(this.currentSeedWrapper.iocdHash, sb.toString());
    }

    public void calculateSeedPower(SeedWrapper sw) {
        if (sw.gadgetCoverage == -1 && sw.seedDistance == -1) {
            sw.power = 1;
        } else {
            // 根据论文中的公式计算能量
            // (sw.seedDistance - minMetSeedDistance) 为 种子距离 - 当前遇到的最小的种子距离
            // (maxMetSeedDistance - minMetSeedDistance) 为 Gadget从entry到达目标的距离 - 当前遇到的最小的种子距离
            // (1 - (sw.seedDistance - minMetSeedDistance) / (maxMetSeedDistance - minMetSeedDistance)) 为 归一化的距离
            // sw.gadgetCoverage为种子当前的覆盖率
            sw.power = (int) ((sw.gadgetCoverage * (1 - (sw.seedDistance - minMetSeedDistance) / (maxMetSeedDistance - minMetSeedDistance))) * 100);
        }
        LogUtils.log2FuzzerStat(sw.iocdHash, "\n****The power of seed " + sw.id + "is " + sw.power + "****\n");
    }

    @Override
    public boolean hasInput() {
        Date now = new Date();
        long elapsedMilliseconds = now.getTime() - startTime.getTime();
        // 超时或者达到尝试次数上限时结束Fuzzing
        if (elapsedMilliseconds < maxDurationMillis
                && numTrials < maxTrials) {
            return true;
        }
        if (elapsedMilliseconds > maxDurationMillis) Fuzzer.JDDFuzzStatement.isTimeOut = true;
        if (numTrials > maxTrials) Fuzzer.JDDFuzzStatement.isExceedMaxTry = true;
        return false;
    }

    @Override
    public void handleResult(Result result, Throwable error) throws GuidanceException {

        // 增加测试次数
        this.numTrials++;
        boolean valid = result == Result.SUCCESS;
        int stuckClzIndex, identifierIndex = 0;
        // 测试成功
        if (valid) {
            this.isReachableSink = true;
            LogUtils.success("The gadget with hash " + this.currentSeedWrapper.iocdHash + " verfied successfully");
            this.currentSeedWrapper.recordExploitableSeedWrapper2File();
            throw new GuidanceException(new NormalFinishedFuzzingException("The gadget with hash " + this.currentSeedWrapper.iocdHash + " verfied successfully"));
        } else if (result == Result.INVALID) {
            // Exception-based的Mutation
            try {
                if (error != null) ExceptionBasedMutator.handleException(currentSeedWrapper, ((ValidationExceptionWrapper)error).coreCause);
            } catch (ObjectNotFoundException | GeneratorException | ReflectionException | ClassNotFoundException e) {
                // throw new GuidanceException(e);
                e.printStackTrace();
                System.exit(0);
            }
            try {
                GadgetCFGWrapper gadgetICFG = GlobalDataContainer.getCFGWrapperByIOCDHash(this.currentSeedWrapper.iocdHash);
                double crtSeedDistance = JDDFuzzRecord.getCurrentSeedDistance(gadgetICFG.bscBlockMap);
                // double crtSeedCov = (double) JDDFuzzRecord.arrivedBBHashArr.size() / (double) gadgetICFG.bscBlockMap.size();
                double crtSeedCov = calculateCovInfo(gadgetICFG.bscBlockMap);
                SeedWrapper newAddedSeed = MutationObjectGenerator.crtInjectObjectWrapper;
                // 记录种子执行状态
                displayStats(currentSeedWrapper.id,
                        currentSeedWrapper.iocdHash,
                        currentSeedWrapper.gadgetCoverage,
                        maxCov,
                        currentSeedWrapper.seedDistance,
                        minMetSeedDistance,
                        currentInputContainer.currentInput.values,
                        crtSeedCov,
                        crtSeedDistance);
                // 对于距离为NaN的，毫无记录的意义
                if (Double.isNaN(crtSeedDistance)) {
                    logDiscardWithReason(newAddedSeed.iocdHash, "Distance-NaN");
                    return;
                }
                // 获取卡住的Class的索引，并判断该种子是否合法
                // Thinking: hash碰撞的索引怎么解决？
                stuckClzIndex = gadgetICFG.getArriveClassIndex();
                if (stuckClzIndex == -1) {
                    LogUtils.warn("Can not find the stuck class index(can be ignored)");
                    // the stuck class could be utils class, which is not in the gadget classes
                    // so, we can not simple discard the seed.
                    // logDiscardWithReason(newAddedSeed.iocdHash, "Stuck-Class-Not-Found");
                    // return;
                } else {
                    identifierIndex = newAddedSeed.input.identifierList.get(stuckClzIndex);
                    // 对于step-forward的变异，如果出现了倒退，应该不能被接受
                    // 之所以会倒退，是由于对于BB Distance为infinity的基本块的处理不当
                    if (identifierIndex < newAddedSeed.input.lastMutateIdentifierIndex) {
                        LogUtils.warn("出现了倒退的情况，认定为坏死种子，抛弃");
                        logDiscardWithReason(newAddedSeed.iocdHash, "Distance-Backward");
                        return;
                    }
                }
                // 更新最短距离
                boolean isNewInDis = false;
                if (crtSeedDistance < this.minMetSeedDistance || this.minMetSeedDistance == -1) {
                    this.minMetSeedDistance = crtSeedDistance;
                    isNewInDis = true;
                }
                // 更新Coverage
                boolean isNewInCov = false;
                if (crtSeedCov > this.maxCov) {
                    this.maxCov = crtSeedCov;
                    isNewInCov = true;
                }
                // for (int bbHash : JDDFuzzRecord.arrivedBBHashArr) {
                //     if (this.coveragedBscBlock.contains(bbHash)) {
                //         continue;
                //     }
                //     this.coveragedBscBlock.add(bbHash);
                //     this.maxCov = (double) this.coveragedBscBlock.size() / (double) gadgetICFG.bscBlockMap.size();
                //     isNewInCov = true;
                // }
                if (isNewInCov || isNewInDis) {
                    // 记录当前种子的距离和Distance
                    newAddedSeed.seedDistance = crtSeedDistance;
                    newAddedSeed.gadgetCoverage = crtSeedCov;
                    // 记录种子为corpus
                    newAddedSeed.recordSeedWrapper2File();
                    // 设置下一个需要进行变异的类的Identifier
                    (newAddedSeed.input.currentInput).setMutateClassIndex(identifierIndex);
                    if (isNextRandomMutateClzDebug) {
                        LogUtils.debug("next mutate class identifier index: "
                                + identifierIndex + ", class name: "
                                + newAddedSeed.input.identifierAndClzMap.get(identifierIndex));
                    }
                    // 将已经变异好的Class的Identifier进行复位
                    if (newAddedSeed.input.lastMutateIdentifierIndex != -1
                            && newAddedSeed.input.lastMutateIdentifierIndex != identifierIndex) {
                        (newAddedSeed.input.currentInput).unsetMutateClassIndex(newAddedSeed.input.lastMutateIdentifierIndex);
                    }
                    newAddedSeed.input.lastMutateIdentifierIndex = identifierIndex;
                    logSeqAfterIdentifierUpdate(newAddedSeed.iocdHash, newAddedSeed.input.currentInput.values);
                    // 重置变异过程中的访问位
                    (newAddedSeed.input.currentInput).reset();
                    this.favoredQueue.add(newAddedSeed);
                } else {
                    if (!isNewInCov && !isNewInDis) {
                        logDiscardWithReason(newAddedSeed.iocdHash, "No-Significent-Change-In-Cov-Dis");
                    } else {
                        if (!isNewInDis) {
                            logDiscardWithReason(newAddedSeed.iocdHash, "No-Change-In-Min-Cov-Dis");
                        } else {
                            logDiscardWithReason(newAddedSeed.iocdHash, "No-Change-In-Max-Cov-Dis");
                        }
                    }
                }
            } catch (CFGException e) {
                e.printStackTrace();
            } finally {
                // 每跑完一个种子，应当清空所有记录的值
                // JDDFuzzRecord.arrivedBBHashArr = new ArrayList<>();
                JDDFuzzRecord.arrivedBBHashArr = new LinkedHashSet<>();
            }
        } else {
            LogUtils.error("[OTHER_FEEDBACK_SITUATION]!!!!!!");
        }

    }

    public void displayStats(int id, int iocdHash, double cov, double bestCov, double distance, double bestDis, ArrayList<Integer> seq, double afterCov, double afterDistance) {

        StringBuilder sb = new StringBuilder();

        sb.append("current seed id: ").append(id).append("\n");
        sb.append("Cov BEFORE execute: ").append(cov).append("\n");
        sb.append("Cov AFTER execute: ").append(afterCov).append("\n");
        sb.append("Best Cov: ").append(bestCov).append("\n");
        sb.append("Distance BEFORE execute: ").append(distance).append("\n");
        sb.append("Distance AFTER execute: ").append(afterDistance).append("\n");
        sb.append("Best Distance: ").append(bestDis).append("\n");
        sb.append("Sequence: ");
        for (int i : seq) {
            sb.append(i).append(" ");
        }
        sb.append("\n");
        LogUtils.log2FuzzerStat(iocdHash , sb.toString());

    }

    private void logSeqAfterIdentifierUpdate(int iocd, ArrayList<Integer> seq) {
        StringBuilder sb = new StringBuilder();
        sb.append("Sequence AFTER update identifier: ");
        for (int b : seq) {
            sb.append(b + " ");
        }
        sb.append("\n");
        sb.append("\n===========seed-favored==============\n");
        LogUtils.log2FuzzerStat(iocd, sb.toString());
    }

    private void logDiscardWithReason(int iocd, String reason) {
        LogUtils.log2FuzzerStat(iocd, "\n===========" + reason + "-seed-discard==============\n");
    }

    /**
     * calculate the coverage of current round seeds
     * 
     * @param bscBlockMap the basic block map of the current gadget(seed).
     */
    private double calculateCovInfo(HashMap<Integer, BasicBlock> bscBlockMap) {
        int size = 0;
        double res = 0.0;
        for (int bbHash : JDDFuzzRecord.arrivedBBHashArr) {
            if (bscBlockMap.containsKey(bbHash))
            size ++;
            res = (double) size / (double) bscBlockMap.size(); 
        }
        if (res > 1) {
            for (int bbHash : JDDFuzzRecord.arrivedBBHashArr) {
                if (bscBlockMap.containsKey(bbHash)) {
                    System.out.println("bbHash: " + bbHash);
                }
            }
        }
        return res;
    }

    public SeedWrapper selectSeed() {

        SeedWrapper resSeed = null;
        Iterator<SeedWrapper> itr = this.favoredQueue.iterator();
        while (itr.hasNext()) {
            SeedWrapper tmpSW = itr.next();
            if (tmpSW.isMutated) {
                continue;
            }
            resSeed = tmpSW;
            break;
        }
        if (resSeed != null) {
            this.minArrivedMtdNum = resSeed.coveredGdtNum;
//            this.minMetSeedDistance = resSeed.seedDistance;
//            this.coveragedBscBlock = new HashSet<>(coveragedBscBlock);
        } else {
            for (SeedWrapper sw : this.favoredQueue) {
                sw.isMutated = false;
            }
            resSeed = selectSeed();
        }
        return resSeed;

    }

    /**
     * 三段式的变异序列
     */
    public static class JDDInputContainer {

        public JDDLinearInput currentInput;
        public LinkedList<JDDInputContainer> fldSeqList;
        public boolean isBackBoneSeq;
        public String clzBelongTo;
        public String fldType;
        public String fldName;
        public int layer;

        // identifier: should this backbone object be mutated
        public LinkedList<Integer> identifierList;
        public Map<Integer, String> identifierIndexAndClzMap;
        public Map<Integer, String> identifierAndClzMap;
        // backboneObject's class name in the call chain
        public LinkedList<String> backBoneObjClzName;
        // field index - Field object (can be mutated)
        public HashMap<Integer, Field> seedPropertiesMap;
        // property index in inputStream Sequence - field index
        public HashMap<Integer, Integer> propIndexInSeq;
        // if the field is finished mutating
        public HashMap<Integer, Boolean> isPropertyFinished;
        // last mutated backbone object index
        public int lastMutateIdentifierIndex = -1;


        public JDDInputContainer() {

        }

        public JDDInputContainer(SeedWrapper seed) throws FatalFuzzingException {

            this.identifierList = new LinkedList<>();
            this.identifierAndClzMap = new HashMap<>();
            this.identifierIndexAndClzMap = new HashMap<>();
            this.seedPropertiesMap = new HashMap<>();
            this.propIndexInSeq = new HashMap<>();
            this.isPropertyFinished = new HashMap<>();
            this.fldSeqList = new LinkedList<>();
            this.backBoneObjClzName = new LinkedList<>();

            int propSqIndex = 0;
            int fldIndex = 0;
            LinkedHashMap<String, Object> backBoneObjMap = seed.backBoneObjectMap;
            ArrayList<Integer> newInput = new ArrayList<>();
            try {
                // with 'one-layer' BFS, we can generate an int array for every backbone object in the gadget chains. the int value span from 0 to 127. 
                for (Map.Entry<String, Object> entry : backBoneObjMap.entrySet()) {
                    String crtRealClassName = entry.getKey();
                    backBoneObjClzName.add(crtRealClassName);
                    Object crtObject = entry.getValue();
                    // identifier for mutation, 0 means should mutate, 1 maens should not mutate
                    newInput.add(0);
                    Integer identifier = newInput.size() - 1;
                    this.identifierList.add(identifier);
                    this.identifierAndClzMap.put(identifier, crtRealClassName);
                    this.identifierIndexAndClzMap.put(identifierList.size() - 1, crtRealClassName);
                    propSqIndex += 1;
                    Field[] crtObjFields = ReflectionUtils.getAllFields(crtObject.getClass());
                    List<Field> mutatableFields = new ArrayList<>();
                    
                    // the property number of current backbone object
                    int realLength = 0;
                    for (Field fld : crtObjFields) {
                        // Static fields are not part of the serialized object state, so mutating them
                        // cannot change the payload; `static final` ones cannot even be set by
                        // reflection (Field.set throws), which used to abort every mutation round.
                        if (Modifier.isStatic(fld.getModifiers())) {
                            continue;
                        }
                        if (seed.isMutatableField(crtRealClassName, fld.getName())) {
                            // some fields are not mutatable. e.g field for sink object, field for basic condition object and so on.
                            realLength++;
                            mutatableFields.add(fld);
                        }
                    }
                    byte[] lenBytes = CommonUtils.transformInt2Bytes(realLength);
                    addIntToInput(newInput, lenBytes);
                    
                    // generate int sequence for each field.
                    propSqIndex += 4;
                    for (Field fld : mutatableFields) {
                        fld.setAccessible(true);
                        addIntToInput(newInput, CommonUtils.transformInt2Bytes(0));
                        this.seedPropertiesMap.put(fldIndex, fld);  // field index -> Field object
                        this.propIndexInSeq.put(propSqIndex, fldIndex); // the index of property in the sequence -> field index
                        propSqIndex += 4;
                        this.isPropertyFinished.put(fldIndex, false); // field index -> is this field should stop mutating.
                        fldIndex++;
                    }
                }
            } catch (Exception e) {
                LogUtils.error("Fatal error happened when generating mutation sequence: " + e.getMessage());
                e.printStackTrace();
                throw new FatalFuzzingException("Fatal error happened when generating mutation sequence");
            }
            this.currentInput = new JDDLinearInput(newInput);
        }

        public JDDInputContainer clone() {
            JDDInputContainer newContainer = new JDDInputContainer();
            newContainer.isBackBoneSeq = this.isBackBoneSeq;
            newContainer.clzBelongTo = this.clzBelongTo;
            newContainer.fldType = this.fldType;
            newContainer.fldName = this.fldName;
            newContainer.layer = this.layer;
            newContainer.identifierList = new LinkedList<>(this.identifierList);
            newContainer.identifierAndClzMap = new HashMap<>(this.identifierAndClzMap);
            newContainer.identifierIndexAndClzMap = new HashMap<>(this.identifierIndexAndClzMap);
            newContainer.seedPropertiesMap = new HashMap<>(this.seedPropertiesMap);
            newContainer.propIndexInSeq = new HashMap<>(this.propIndexInSeq);
            newContainer.isPropertyFinished = new HashMap<>(this.isPropertyFinished);
            newContainer.currentInput = new JDDLinearInput(this.currentInput.values);
            newContainer.fldSeqList = new LinkedList<>();
            newContainer.backBoneObjClzName = new LinkedList<>(this.backBoneObjClzName);

            for (JDDInputContainer container : this.fldSeqList) {
                newContainer.fldSeqList.add(container.clone());
            }

            return newContainer;
        }

        public JDDInputContainer fuzz() {
            // 新的Container只是序列会变，别的属性继承旧的Container
            JDDInputContainer newContainer = new JDDInputContainer();
            newContainer.isBackBoneSeq = this.isBackBoneSeq;
            newContainer.clzBelongTo = this.clzBelongTo;
            newContainer.fldType = this.fldType;
            newContainer.fldName = this.fldName;
            newContainer.layer = this.layer;
            newContainer.identifierList = new LinkedList<>(this.identifierList);
            newContainer.identifierAndClzMap = new HashMap<>(this.identifierAndClzMap);
            newContainer.identifierIndexAndClzMap = new HashMap<>(this.identifierIndexAndClzMap);
            newContainer.seedPropertiesMap = new HashMap<>(this.seedPropertiesMap);
            newContainer.propIndexInSeq = new HashMap<>(this.propIndexInSeq);
            newContainer.isPropertyFinished = new HashMap<>(this.isPropertyFinished);
            newContainer.fldSeqList = new LinkedList<>();
            newContainer.backBoneObjClzName = new LinkedList<>(this.backBoneObjClzName);

            newContainer.currentInput = (JDDLinearInput) currentInput.fuzz(this.identifierList);
            // 如果存在fld序列，那么就处理fld序列
            for (JDDInputContainer container : fldSeqList) {
                JDDInputContainer newFldContainer = container.fuzz();
                newContainer.fldSeqList.add(newFldContainer);
            }
            return newContainer;
        }

        private void addIntToInput(ArrayList<Integer> input, byte[] bytesOfInt) {
            for (int i = 0; i < bytesOfInt.length; i++) {
                input.add((int) bytesOfInt[i]);
            }
        }


    }


    public static class JDDLinearInput extends ZestGuidance.LinearInput {

        public JDDLinearInput() {
            super();
            this.values = new ArrayList<>();
        }

        // 对于每个Gadget种子，按照JDDFuzz的要求，我们生成一个Parameters Sequence，然后记录进来
        public JDDLinearInput(ArrayList<Integer> values) {
            super(values);
        }

        public JDDLinearInput(ZestGuidance.LinearInput other) {
            super(other);
            this.values = new ArrayList<>(other.values);
        }

        @Override
        public int getOrGenerateFresh(Integer key, Random random) {
            // 沿用LinearInput中的方法即可
            return super.getOrGenerateFresh(key, random);
        }

        public void reset() {
            this.requested = 0;
        }

        /**
         * 设置需要变异的Class的Identifier为1
         *
         * @param index 需要变异的Class的Identifier
         */
        public void setMutateClassIndex(int index) {
            this.values.set(index, 1);
        }

        /**
         * 设置不需要变异的Class的Identifier为3，注意！！！该方法仅在某个类变异完后才进行调用
         *
         * @param index
         */
        public void unsetMutateClassIndex(int index) {
            this.values.set(index, 3);
        }

        /**
         * 用于变异
         */
        public ZestGuidance.Input fuzz(LinkedList<Integer> identifierList) {

            ArrayList<Integer> newValues = new ArrayList<>(this.values);

            // 遍历每个需要变异的Object，并随机地进行替换
            for (int identifierIndex : identifierList) {
                // 在handleRequest处，应该决定是否需要变异
                int isNeedMutate = newValues.get(identifierIndex);
                if (isNeedMutate == 0 || isNeedMutate == 3) {
                    continue;
                } else if (isNeedMutate == 1) {
                    // 更新索引，读取现在的size
                    int crtIndex = identifierIndex + 1;
                    int size = CommonUtils.transformBytes2Int(readFourBytesFromIndex(crtIndex));
                    crtIndex += 4;
                    for (int i = 0; i < size; i++, crtIndex += 4) {
                        mutateAtIndex(crtIndex, newValues);
                    }
                } else {
                    System.out.println("[ERROR] Identifier Index Invalid!");
                    System.exit(0);
                }
            }

            return new JDDLinearInput(newValues);

        }

        /**
         * 从索引开始向后读取四个bytes
         *
         * @param index
         * @return
         */
        public byte[] readFourBytesFromIndex(int index) {
            if (index < 0 || index + 3 >= values.size()) {
                throw new IndexOutOfBoundsException("Invalid index");
            }

            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                bytes[i] = (byte) (values.get(index + i) & 0xFF);
            }

            return bytes;
        }

        /**
         * 随机地确定是否变异该index及之后的4个bytes，如果是，就变异
         *
         * @param index
         */
        public void mutateAtIndex(int index, ArrayList<Integer> newValues) {
            if (index < 0 || index + 3 >= newValues.size()) {
                throw new IndexOutOfBoundsException("Invalid index");
            }

            Random random = new Random();
            boolean performSecondAction = random.nextBoolean();
            boolean isReset = random.nextBoolean(); // 是否要重置这个field

            if (performSecondAction) {
                if (!isReset) {
                    byte[] newBytes = generateRandomBytes();
                    for (int i = 0; i < 4; i++) {
                        newValues.set(index + i, newBytes[i] & 0xFF);
                    }
                } else {
                    // byte[] newBytes = generateRandomBytes();
                    byte[] newBytes = CommonUtils.transformInt2Bytes(Integer.MAX_VALUE);
                    for (int i = 0; i < 4; i++) {
                        newValues.set(index + i, newBytes[i] & 0xFF);
                    }
                }

            }
        }

        private byte[] generateRandomBytes() {
            byte[] bytes = new byte[4];
            new Random().nextBytes(bytes);
            return bytes;
        }

    }

    @Override
    public Consumer<TraceEvent> generateCallBack(Thread thread) {
        return null;
    }

}
