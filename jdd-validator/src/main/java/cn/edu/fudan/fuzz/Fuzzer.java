package cn.edu.fudan.fuzz;


import cn.edu.fudan.container.ConfigContainer;
import cn.edu.fudan.container.GlobalDataContainer;
import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import cn.edu.fudan.datastructure.NoneObject;
import cn.edu.fudan.datastructure.OracleClassA;
import cn.edu.fudan.datastructure.OracleClassB;
import cn.edu.fudan.exception.validator.*;
import cn.edu.fudan.generator.mutate.MutationObjectGenerator;
import cn.edu.fudan.generator.seed.SeedGenerator;
import cn.edu.fudan.generator.seed.SeedWrapper;
import cn.edu.fudan.utils.ConfigUtils;
import cn.edu.fudan.utils.FileUtils;
import cn.edu.fudan.utils.FuzzUtils;
import cn.edu.fudan.utils.LogUtils;
import cn.edu.fudan.utils.ObjectDumper;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import datastructrue.Pair;
import datastructrue.inst.JDDFuzzRecord;
import datastructrue.inst.TriggeredChain;
import datastructrue.iocd.IOCD;
import datastructrue.iocd.unit.SinkRecord;
import edu.berkeley.cs.jqf.fuzz.guidance.Guidance;
import edu.berkeley.cs.jqf.fuzz.guidance.GuidanceException;
import edu.berkeley.cs.jqf.fuzz.guidance.Result;
import edu.berkeley.cs.jqf.fuzz.guidance.StreamBackedRandom;
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.FastSourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.FuzzStatement;
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.NonTrackingGenerationStatus;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;

public class Fuzzer {

    private static final int FUZZ_TIMEOUT = ConfigContainer.fuzzTime == null ? 60 : ConfigContainer.fuzzTime.intValue();

    public static JDDFuzzStatement crtFuzzStatement = null;
    public static MutationObjectGenerator mutationObjectGenerator = new MutationObjectGenerator();

    private static String badSeedDir;
    private static final String baseFinishedDir = ConfigContainer.projectsInputDir == null ? "/tmp" : ConfigContainer.projectsInputDir + File.separator + "finished" + File.separator;

    public static void main(String[] args) {
        try {
            banner();
            String userInput = "";
            if (args.length < 1) {
                LogUtils.warn("Usage: java [-javaagent:path/to/jdd-instrumentor.jar=0,false] -jar path/to/jdd-validator.jar <setup-dir|fuzz|seeds-filter> [badseeds-dir]");
                System.exit(1);
            } else {
                userInput = args[0];
                if (userInput.equals("setup-dir")) {
                    LogUtils.info("Setting up the directories need for fuzzing...");
                    ConfigUtils.setAndLoadConfigFile();
                    ConfigUtils.initAllDirectory();
                    LogUtils.info("Directories setting up success!");
                    System.exit(0);
                } else if (userInput.equals("fuzz") || userInput.equals("seeds-filter")) {
                    if (!GlobalDataContainer.getInstance().isStartWithAgent()) {
                        LogUtils.warn("Usage: java -javaagent:path/to/jdd-instrumentor.jar=0,false -jar path/to/jdd-validator.jar <fuzz|seeds-filter> [badseeds-dir]");
                        System.exit(0);
                    }
                }
            }

            if (args.length == 2) {
                badSeedDir = baseFinishedDir + args[1] + File.separator;
            } else {
                badSeedDir = baseFinishedDir + "badseed" + File.separator;
            }

            ConfigUtils.init();
            ValidatorGlobalDataContainer.init();

            // For Speed up the instrumentation process during debug mode.
            // The start file is global by default; pass -Djdd.startFile=<path> to give each
            // instance its own, so parallel instances do not consume each other's start file.
            String startFile = System.getProperty("jdd.startFile", "/tmp/jdd_start_test");
            LogUtils.info("Instrumentation Done! Waiting for the start file (`" + startFile + "`) to be created...");
            Path startFilePath = Paths.get(startFile);
            while (!Files.deleteIfExists(startFilePath)) {
                Thread.sleep(50);
            }

            // Generate all seeds regardless of the runtime-mode of jdd-fuzzer
            doSeedGeneration();
            if (userInput.equals("seeds-filter")) {
                LogUtils.success("Seeds filtering done!");
            } else if (userInput.equals("fuzz")) {
                // fuzz-loop starts here
                doFuzzLoop();
            } else {
                LogUtils.error("Invaid user input: " + userInput);
                System.exit(0);
            }
            
        } catch (Exception | Error e) {
            LogUtils.error("Unkown error happened during fuzzing, details as below:");
            e.printStackTrace();
            // if fatal error happened during fuzzing, clear all cache. 
            FuzzUtils.deleteCache();
        }
        // a class loaded for a seed can leave a non-daemon thread behind (e.g. an AWT event thread), which
        // kept the JVM alive after the fuzz loop and stalled fuzz.sh
        System.exit(0);
    }

    private static void resetAllFuzzGlobalValue() {
        JDDFuzzRecord.resetFeedBackRecord();
        MutationObjectGenerator.crtSeedNotNullFiled = new HashSet<>();
        MutationObjectGenerator.crtSeedSpecificCastFiled = new HashSet<>();
        MutationObjectGenerator.crtInjectObjectWrapper = null;
        SeedWrapper.GLOBAL_CURRENT_ID = 0;
        OracleClassA.feedBackCode = -1;
        OracleClassB.feedBackCode = -1;
        JDDFuzzStatement.isExceedMaxTry = false;
        JDDFuzzStatement.isTimeOut = false;
    }

    private static void doSeedGeneration() {
        for (Map.Entry<Integer, IOCD> iocdEntry : GlobalDataContainer.getAllIOCDEntry()) {
            IOCD iocd = iocdEntry.getValue();
            short type = iocd.isHashCollisionFlag() ? (short) 2 : (short) 0;
            try {
                SeedWrapper seed = SeedGenerator.generateSeed(iocd, type);
                if (Boolean.getBoolean("jdd.dumpSeed")) {
                    // debug: show the initial seed to compare it with the IOCD's gadget chain
                    LogUtils.info("Initial seed of " + iocd.getHashCode() + ":\n"
                            + ObjectDumper.dump(seed.injectionObj, Integer.getInteger("jdd.dumpSeedDepth", 12)));
                }
                ValidatorGlobalDataContainer.iocdInitSeedList.add(new Pair<>(iocdEntry.getKey(), seed));
            } catch (GeneratorException | FatalFuzzingException | ReflectionException | Error e) {
                LogUtils.error(String.format("Invalid iocd and seed %d, find details belowd:", iocd.getHashCode()));
                LogUtils.error(e.getMessage());
                if (e instanceof Error) {
                    e.printStackTrace();    // not a generator exception: where it came from is the useful part
                }
                FuzzUtils.moveToWrongSeedDir(ConfigContainer.gadgetInfosDir + File.separator +  iocd.hashCode + ".json",
                badSeedDir + iocd.hashCode + ".json");
            }
        }
    }

    private static void doFuzzLoop() throws ReflectionException, GeneratorException, FatalFuzzingException {
        for (Pair<Integer, SeedWrapper> seedEntry : ValidatorGlobalDataContainer.iocdInitSeedList) {
            // feedback collection utils setup
            IOCD crtIocd = GlobalDataContainer.getIOCDByHash(seedEntry.getKey());
            LinkedList<Integer> gdtMtdHashList = new LinkedList<>();
            LinkedList<String> gdtMtdSigList = new LinkedList<>();
            for (Pair<Integer, String> integerStringPair : crtIocd.getGadgetCallStack()) {
                gdtMtdHashList.add(integerStringPair.getKey());
                gdtMtdSigList.add(integerStringPair.getValue());
            }
            JDDFuzzRecord.crtProtocol = crtIocd.getProtocol();
            List<String> sinkCalls = new ArrayList<>();
            for (SinkRecord sinkRecord : crtIocd.getSinkRecords()) {
                sinkCalls.add(sinkRecord.jimpleUnitInfo);
            }
            JDDFuzzRecord.isEffectOracleSink = JDDFuzzRecord.hasEffectOracle(crtIocd.getSinkType(), sinkCalls);
            JDDFuzzRecord.isMarkerOracleSink = JDDFuzzRecord.hasMarkerOracle(crtIocd.getSinkType(), sinkCalls);
            JDDFuzzRecord.setCurrentSeedCS(gdtMtdSigList);
            JDDFuzzRecord.setCurrentSeedCSHash(gdtMtdHashList);
            // reset global state of fuzzer
            resetAllFuzzGlobalValue();
            ValidatorGlobalDataContainer.flushCurrentDict(seedEntry.getKey());
            // the FUZZ_TIMEOUT can be specified on `config.propertis`, 60s by defalut.
            JDDFuzzGuidance jddFuzzGuidance = new JDDFuzzGuidance(Duration.ofSeconds(FUZZ_TIMEOUT), null, new Random());
            crtFuzzStatement = new JDDFuzzStatement(jddFuzzGuidance, crtIocd.getProtocol());
            crtFuzzStatement.doFuzz(seedEntry.getValue());
        }
    }


    private static void banner() {
        String bannerInfo = 
        "     __    .___  .___           _____                                  \n" +
        "    |__| __| _/__| _/         _/ ____\\_ __________________ ___________ \n" +
        "    |  |/ __ |/ __ |   ______ \\   __\\  |  \\___   /\\___   // __ \\_  __ \\\n" +
        "    |  / /_/ / /_/ |  /_____/  |  | |  |  //    /  /    /\\  ___/|  | \\/\n" +
        "/\\__|  \\____ \\____ |           |__| |____//_____ \\/_____ \\\\___  >__|   \n" +
        "\\______|    \\/    \\/                            \\/      \\/    \\/       preview-version\n";
        System.out.println(bannerInfo);
    }

    public static class JDDFuzzStatement extends FuzzStatement {

        public static boolean isTimeOut = false;
        public static boolean isExceedMaxTry = false;

        private int runNum = 0;

        public SeedWrapper crtSeed;
        public String protocol;
        public boolean isFirstRun = true;

        // 内置一个静态变量来帮助外层的Fuzzer获取到FeedBack从而指导变异 <到达方法的hash, 到达if语句的hash>
        public static Pair<Integer, Integer> arriveMtdIfStmtPair;

        public JDDFuzzStatement(Guidance fuzzGuidance, String protocol) {
            this.guidance = fuzzGuidance;
            this.protocol = protocol;
            this.arriveMtdIfStmtPair = new Pair<>(-1, -1);
        }

        private void doFuzz(SeedWrapper initSeed) throws FatalFuzzingException {
            this.crtSeed = initSeed;
            try {
                ((JDDFuzzGuidance) this.guidance).addToFavorQueue(crtSeed);
                while (this.guidance.hasInput() && !isTimeOut && !isExceedMaxTry) {
                    runNum++;
                    LogUtils.log2FuzzerStat(initSeed.iocdHash, "============do " + runNum + " round fuzz============");
                    ((JDDFuzzGuidance) this.guidance).isRoundFinished = true;
                    InputStream input = guidance.getInput();
                    if (input == null) {
                        LogUtils.warn("Can not get seed, the fuzzer routine of current Seed(" + this.crtSeed.iocdHash + ") will be terminated.");
                        throw new FatalFuzzingException("No interesting seed be found, stop fuzzing.");
                    }
                    StreamBackedRandom randomFile = new StreamBackedRandom(input, Long.BYTES);
                    SourceOfRandomness random; // = new FastSourceOfRandomness(randomFile);
                    GenerationStatus genStatus; // = new NonTrackingGenerationStatus(random);
                    ((JDDFuzzGuidance) guidance).isRoundFinished = false;
                    isFirstRun = true;
                    crtSeed = ((JDDFuzzGuidance) guidance).currentSeedWrapper;
                    for (int i = 1; i <= crtSeed.power; i++) {
                        if (!isFirstRun) {
                            input = guidance.getInput();
                            if (input == null) {
                                LogUtils.error("the returned InputStream is null, the Fuzzing routine of Seed(" + this.crtSeed.iocdHash + ") would stop");
                                throw new FatalFuzzingException("the returned InputStream is null, stop Fuzzing");
                            }
                            randomFile = new StreamBackedRandom(input, Long.BYTES);
                        } else {
                            isFirstRun = false;
                        }
                        random = new FastSourceOfRandomness(randomFile);
                        genStatus = new NonTrackingGenerationStatus(random);
                        // 此处进行变异
                        MutationObjectGenerator.isInjectionObjectMutate = true;
                        GenerationStatus finalGenStatus = genStatus;
                        SourceOfRandomness finalRandom = random;
                        Object mutatedInjectionObj = mutationObjectGenerator.generate(finalRandom, finalGenStatus);
                        if (mutatedInjectionObj instanceof NoneObject) {
                            LogUtils.warn("Current Seed(" + this.crtSeed.iocdHash + ") can not generate valid mutation obejct at its " + i + " time mutation, skip...");
                            continue;
                        }
                        Result result = Result.UNKNOWN;
                        boolean isExceptionHandled = false;
                        try {
                            ExploitabilityValidateTrigger.triggerExploitabilityValidate(mutatedInjectionObj, this.protocol);
                        } catch (ValidationException e) {
                            if (e instanceof UnsupportProtocolException) {
                                LogUtils.error("unsupport protocol for seed " + this.crtSeed.iocdHash + ", find details below:");
                                LogUtils.error(e.getMessage());
                                e.printStackTrace();
                            } else if (e instanceof ValidationExceptionWrapper) {
                                arriveMtdIfStmtPair = new Pair<>(JDDFuzzRecord.getArriveGdtMtdHash(), JDDFuzzRecord.getArriveBscIfStmtHash());
                                result = isGadget() ? Result.SUCCESS : Result.INVALID;
                                guidance.handleResult(result, e);
                                isExceptionHandled = true;
                            }
                        }
                        // if there is no exception, then we handle the result here (isGadget() once per execution)
                        if (!isExceptionHandled) {
                            result = isGadget() ? Result.SUCCESS : Result.INVALID;
                            guidance.handleResult(result, null);
                        }
                    }
                    isFirstRun = true;
                    ((JDDFuzzGuidance) guidance).isRoundFinished = true;

                }
                if (isTimeOut) {
                    throw new FuzzingTimeOutException(crtSeed.iocdHash + " Fuzzing Timetout, quit...");
                }
                if (isExceedMaxTry) {
                    throw new FuzzingTooManyTriesException(crtSeed.iocdHash + " Fuzzing exceeds max trials, quit...");
                }
            } catch (FatalFuzzingException e) {
                LogUtils.error("some thing wrong when fuzzing seed " + initSeed.iocdHash + ", find details below:");
                LogUtils.error(e.getMessage());
                e.printStackTrace();
            } catch (NormalFinishedFuzzingException e) {
                LogUtils.info("seed " + initSeed.iocdHash + " Fuzzing routine finished, quit...");
                if (isGadget()) moveToSuccessDir(ConfigContainer.gadgetInfosDir + File.separator +  initSeed.iocdHash + ".json",
                        ConfigContainer.projectsInputDir + File.separator + "finished" + File.separator + "success" + File.separator + initSeed.iocdHash + ".json");
                // crtSeed.recordExploitableSeedWrapper2File();
            } catch (GuidanceException e) {
                if (e.getCause() instanceof NormalFinishedFuzzingException) {
                    LogUtils.info("seed " + initSeed.iocdHash + " Fuzzing routine finished, quit...");
                    if (isGadget()) moveToSuccessDir(ConfigContainer.gadgetInfosDir + File.separator +  initSeed.iocdHash + ".json",
                            ConfigContainer.projectsInputDir + File.separator + "finished" + File.separator + "success" + File.separator + initSeed.iocdHash + ".json");
                    // crtSeed.recordExploitableSeedWrapper2File();
                } else {
                    LogUtils.error("some thing wrong when fuzzing seed " + initSeed.iocdHash + ", find details below:");
                    LogUtils.error(e.getMessage());
                    e.printStackTrace();
                }
            } catch (FuzzingTimeOutException e) {
                LogUtils.info(e.getMessage());
            } catch (FuzzingTooManyTriesException e) {
                LogUtils.info(e.getMessage());
            } finally {
                LogUtils.log2FuzzerStat(crtSeed.iocdHash, "============round " + runNum + " fuzz end============");
            }

        }

        private boolean isGadget() {
            // the INVOKE probe was called; its stacks end with the chain's final Method.invoke
            for (StackTraceElement[] stack : OracleClassA.fireStacks) {
                JDDFuzzRecord.recordSinkHit(stack, TriggeredChain.HIT_PROBE);
            }
            // FILE / INVOKE sinks count only by their effect (file written, probe called); reaching the
            // sink method (isRealGadget, the sink hook) is enough only for sink types without an effect oracle
            boolean isSinkReached = OracleClassA.feedBackCode == 1
                    || OracleClassB.feedBackCode == 1
                    || (!JDDFuzzRecord.isEffectOracleSink && !JDDFuzzRecord.isMarkerOracleSink && isRealGadget(arriveMtdIfStmtPair.getKey()))
                    || JDDFuzzRecord.isArriveSink;
            if (!isSinkReached) {
                return false;
            }
            // a marker-oracle sink additionally requires that the sink argument we control carried our
            // inert token this run; reaching the sink without it (isArriveSink via another path) is not a hit
            if (JDDFuzzRecord.isMarkerOracleSink && !JDDFuzzRecord.isSinkFieldControlled) {
                logOffChainHit();
                return false;
            }
            recordTriggeredChains();
            // call stack double check: the sink must be reached along this IOCD's gadget chain
            if (JDDFuzzRecord.isSinkRouteOk) {
                return true;
            }
            logOffChainHit();
            return !REQUIRE_CHAIN_ROUTE;
        }

        // -Djdd.requireChainRoute=false: count a sink hit as success whatever the route (old behavior)
        private static final boolean REQUIRE_CHAIN_ROUTE =
                Boolean.parseBoolean(System.getProperty("jdd.requireChainRoute", "true"));
        private boolean isOffChainHitLogged = false;

        /** The sink was reached, but not along the IOCD's chain: fuzzing goes on to look for one that is. */
        private void logOffChainHit() {
            if (isOffChainHitLogged) {
                return;
            }
            isOffChainHitLogged = true;
            LogUtils.info("Reached the sink of " + crtSeed.iocdHash + " off the IOCD chain (stack matches "
                    + JDDFuzzRecord.sinkRouteInfo + ")" + (REQUIRE_CHAIN_ROUTE ? ", keep fuzzing" : ""));
        }

        // keys of the chains already recorded for the current IOCD
        private final HashSet<String> recordedChainKeys = new HashSet<>();
        // keys of the sink hits not recorded for lack of a payload (warned about once)
        private final HashSet<String> unrecordableChainKeys = new HashSet<>();

        /**
         * Record every distinct chain a payload of this IOCD really ran to the sink, the IOCD's chain or
         * another one (TriggeredChain): one line in outputs/triggered_chains.jsonl, and the payload in
         * outputs/chains/<iocd>-<chain key>exp. analyze_run.py counts a run's distinct chains.
         * Per execution: the first sink hit (later ones, e.g. the cache index the same sink method
         * writes next, are side effects of it), and the first hit along the IOCD chain if that is another.
         */
        private void recordTriggeredChains() {
            File outDir = new File(ConfigContainer.exploitableDir).getParentFile();
            byte[] payload = ExploitabilityValidateTrigger.lastPayload;
            List<JDDFuzzRecord.SinkHit> hits = new ArrayList<>();
            for (JDDFuzzRecord.SinkHit hit : JDDFuzzRecord.sinkHits) {
                if (hits.isEmpty() || (hit.isOnIocdChain && !hits.get(0).isOnIocdChain)) {
                    hits.add(hit);
                }
                if (hits.size() == 2 || (hits.size() == 1 && hits.get(0).isOnIocdChain)) {
                    break;
                }
            }
            for (JDDFuzzRecord.SinkHit hit : hits) {
                String key = TriggeredChain.key(hit.chain);
                if (hit.chain.isEmpty() || !recordedChainKeys.add(key)) {
                    continue;
                }
                if (payload == null) {
                    // No bytes were deserialized in this execution, so the sink was not reached by
                    // deserializing a payload (e.g. the probe fired while the seed was being built).
                    // Such a hit is not a gadget chain and cannot be replayed: do not record it.
                    if (unrecordableChainKeys.add(key)) {
                        LogUtils.warn("Sink hit of " + crtSeed.iocdHash + " with no deserialized payload"
                                + " (chain " + key + "): not recorded");
                    }
                    recordedChainKeys.remove(key);
                    continue;
                }
                String payloadName = "chains/" + crtSeed.iocdHash + "-" + key + "exp";
                LogUtils.info("Triggered chain " + key + (hit.isOnIocdChain ? " (the IOCD chain)" : " (off the IOCD chain)")
                        + ": " + String.join(" -> ", hit.chain));
                try {
                    File pf = new File(outDir, payloadName);
                    FileUtils.ensureDirectoryExist(pf.getParentFile());
                    Files.write(pf.toPath(), payload);
                    StringBuilder sb = new StringBuilder("{\"iocd\": ").append(crtSeed.iocdHash)
                            .append(", \"protocol\": ").append(jsonString(protocol))
                            .append(", \"sinkType\": ").append(jsonString(GlobalDataContainer.getIOCDByHash(crtSeed.iocdHash).getSinkType()))
                            .append(", \"onIocdChain\": ").append(hit.isOnIocdChain)
                            .append(", \"iocdMatch\": ").append(jsonString(hit.iocdMatch))
                            .append(", \"key\": ").append(jsonString(key))
                            .append(", \"payload\": ").append(jsonString(payloadName))
                            .append(", \"chain\": [");
                    for (int i = 0; i < hit.chain.size(); i++) {
                        sb.append(i > 0 ? ", " : "").append(jsonString(hit.chain.get(i)));
                    }
                    sb.append("]}\n");
                    Files.write(new File(outDir, "triggered_chains.jsonl").toPath(), sb.toString().getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    LogUtils.warn("Failed to record the triggered chain " + key + " of " + crtSeed.iocdHash + ": " + e);
                }
            }
        }

        private static String jsonString(String s) {
            return "\"" + String.valueOf(s).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        private void moveToSuccessDir(String originFilePath, String targetFilePath) {
            File oFile = new File(originFilePath);
            File tFile = new File(targetFilePath);
            try {
                FileUtils.copyFile(oFile, tFile);
                FileUtils.deleteFile(oFile.toPath());
            } catch (IOException e) {
                LogUtils.error("Moving" + originFilePath + "to" + targetFilePath + "failed!");
                // e.printStackTrace();
            }

        }

        /**
         * 运行完一次seed后，根据插桩的feedBack判断是否是一条有效的Gadget
         * 如果到达了sink点，那么就认为是一条有效的
         *
         * @param mtdHash
         * @return
         */
        private boolean isRealGadget(int mtdHash) {

            IOCD gdtRecord = GlobalDataContainer.getIOCDByHash(this.crtSeed.iocdHash);
            // 获取record中sinkMethod的hash
            int gdtRecordHash = gdtRecord.getHashCode();
            String type = gdtRecord.getSinkType();
            // 获取gdt中的最后一个方法的hash，即sink方法的hash
            LinkedList<Integer> gdtMtdHashList = GlobalDataContainer.getGadgetHashListByIOCDHash(gdtRecordHash);
            if (gdtMtdHashList == null) {
                throw new IllegalArgumentException("不存在对应hashCode为" + gdtRecordHash + "的gadget method HashSet");
            }
            int sinkMtdHash = gdtMtdHashList.getLast();
            // 比对feedback中的方法和sink方法的hash是否相等
            if (sinkMtdHash == mtdHash) {
                return true;
            }
            // 还有一种情况是Gadget中方法是具体实际的父方法
//            else {
//                String sinkMtdSig = DataUtil.getSinkMtdSigByHash(sinkMtdHash);
//                String crtArriveMtdSig = DataUtil.getSinkMtdSigByHash(mtdHash);
//                if (crtArriveMtdSig.equals("notSinkMethod")) {
//                    return false;
//                } else {
//                    Class<?> sinkClz = null;
//                    Class<?> crtMtdClz = null;
//                    try {
//                        sinkClz = ClassUtils.getClassByName(CommonUtils.getDeclaredClzBySig(sinkMtdSig));
//                        crtMtdClz = ClassUtils.getClassByName(CommonUtils.getDeclaredClzBySig(crtArriveMtdSig));
//                    } catch (ClassNotFoundException e) {
//                        Utils.printStackTraceWithExit(e);
//                    }
//                    // 通过判断crtMtdClz是否可以被赋值到sinkClz类型的值进行判断是否存在父子类关系
//                    if (sinkClz.isAssignableFrom(crtMtdClz)) {
//                        return true;
//                    }
//                }
//
//            }
            return false;
        }

    }


    // Notice: For Agent testing
    public static class FakeFuzzer {
        public static void main(String[] args) {
            ConfigUtils.init();
            ValidatorGlobalDataContainer.init();
        }
    }

}
