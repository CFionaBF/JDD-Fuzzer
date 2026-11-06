# Reproducing the evaluation

From the repository root, run `JDD_JDK_TARBALL=/path/to/jdk-8u361-linux-x64.tar.gz ./reproduction/reproduce.sh`: it deploys
the environment under test (JDK, build, dataset, jars), fuzzes the 20 evaluated applications one after the other (7,902
IOCDs, at most 6 h each), replays what it found in an independent plain JVM and prints the distinct confirmed gadget
chains per application, next to the numbers of the authors' full run.

Options are environment variables (`JDD_JDK_TARBALL` is only needed until the JDK is installed):
```bash
APPS="Wicket JDK" ./reproduction/reproduce.sh                       # only these applications
APP_TIME_LIMIT=2h TRIES=1 SHARDS=2 ./reproduction/reproduce.sh      # other time limit, tries per IOCD, parallel fuzzer JVMs
OUT=reproduction/runs/my-run ./reproduction/reproduce.sh            # result directory; run again with the same OUT to resume
CLEAN=1 ./reproduction/reproduce.sh                                 # wipe the cache and any previous run first, deploy and run from nothing
```

**One application only:** `./run_single_app.sh <iocd_dir> [--work_dir <work_dir>] [--inputClassPath <jar_dir>]` runs the same steps
(deploy, fuzz, replay, count the distinct chains) for one application, given the IOCDs and `IfStmtRecord.json` that the
static analysis module produced for it and, optionally, its jars (`--inputClassPath`) and a result directory
(`--work_dir`). It creates the `config.properties` of every shard itself and takes the same environment options as
`reproduce.sh`; see the [Example](../README.md#example) in the main README. For one of the 20 evaluated applications use
`APPS=Wicket ./reproduction/reproduce.sh`.

## Requirements
- **Software:** Linux (developed on Ubuntu), `git`, `python3`, Maven and a JDK 8 to build (default
  `/usr/lib/jvm/java-8-openjdk-amd64`, or `JDD_BUILD_JAVA_HOME`), and network access (Maven Central and GitHub: the
  dataset, the jars, the customized junit-quickcheck).
- **The same JDK version as the static analysis module.** JDD-Fuzzer must run on the JDK version the IOCDs were produced
  with. For example, the IOCDs we provide were produced with Oracle JDK 8u361, so JDD-Fuzzer has to be configured with
  Oracle JDK 8u361 as well (they match its sources line by line; OpenJDK builds do not). Oracle's license does not
  allow redistributing it: download `jdk-8u361-linux-x64.tar.gz` (sha256 `abee6d35…47b6a`) from Oracle's Java archive
  and pass it with `JDD_JDK_TARBALL`, or install it yourself and set `JDD_JAVA_HOME`. Another JDK needs
  `JDD_ALLOW_JDK_MISMATCH=1` and gives results that are not comparable. `CLEAN=1` keeps an installed JDK; delete
  `jdk-for-jdd/` in the repository root to re-verify that step too.
- **Hardware:** the minimum is the configuration of our tests: 4 CPU cores, 16 GB of RAM and 10 GB of free disk.
  `SHARDS` fuzzer JVMs run in parallel (default: the number of CPU cores, so 4); each takes one core and about 2 GB of
  RAM. **The more cores, the higher the efficiency:** the IOCDs are independent, so the run time falls roughly in
  proportion to the number of shards, and more IOCDs of the large applications fit in the 6 h limit (add about 2 GB of
  RAM per extra shard). Disk: 2.2 GB for the dataset, jars, JDK and build, plus about 1 MB per IOCD for the work
  directories of a complete run (7,902 IOCDs). A smaller machine needs a lower `SHARDS` (we have not run it that way).

## How this relates to the runner in the main README
The evaluation was run the way the README's recommended section describes (`src-deploy/run_app.sh`): the strategy of
the original runner (each IOCD on its own in fresh JVMs, up to `TRIES` tries of `FUZZ_TIME` seconds, stop at the first success),
but with **3 tries instead of 10** and **`SHARDS` IOCDs in parallel**. `reproduce.sh` defaults to **2 tries**: every IOCD
verified in our runs succeeded on its first try, so the third try only cost time on the IOCDs that are not verified
(`TRIES=3 ./reproduction/reproduce.sh` uses the exact setting of the authors' full run). Parallel is the recommended way:
use as many shards as CPU cores (the default); one try costs about 100 s, so a sequential run of the larger applications
takes days. For the original try count use `TRIES=10 ./reproduction/reproduce.sh` (a longer run: fewer IOCDs fit in the
time limit of the large applications).

## What it does
1. `src-deploy/setup_jdk.sh`, `src-deploy/build.sh`: installs the JDK, builds the fuzzer (`mvn install`). Skipped when
   already done, unless `CLEAN=1` wiped the build output (the JDK install is left alone either way, see above).
2. `src-deploy/fetch_data.sh`: the IOCDs our static module detected, from [fdu-sec/JDD](https://github.com/fdu-sec/JDD/tree/main/IOCDDatasets) (`IOCDDatasets/detected`, pinned to
   commit `5fbda81`) and each application's jars, resolved by Maven from `src-deploy/evaluation-jars/apps.txt`
   (the versions are listed there). Kept in `reproduction/data/`.
3. For each application, one after the other (`src-deploy/run_app.sh`): every IOCD is fuzzed for up to `TRIES` tries
   of `FUZZ_TIME` seconds, `SHARDS` IOCDs at a time, until `APP_TIME_LIMIT` is reached. The limit stops the run from
   taking new IOCDs (the ones being fuzzed finish their tries, so a run can overshoot by up to `TRIES` x (`FUZZ_TIME` + about 45 s)); the IOCDs left are reported as "not run". The IOCDs are
   taken in a random order (`SEED`), so a run cut by the limit is a uniform sample.
4. `src-deploy/replay/replay_check.sh`: replays every payload the fuzzer saved in a plain JVM (no agent, no
   instrumentation) under the JDK debugger, and checks that the sink effect happens along the recorded call chain.
5. `src-deploy/analyze_run.py`: per-IOCD outcome, where the chains stop, why seeds failed, and the number of distinct chains.
6. `src-deploy/reproduce_summary.py`: `SUMMARY.md` of the run.

A chain counts when the replay reproduces it, and an IOCD counts as verified when the replay of its payload shows the
sink effect with the whole IOCD chain on the stack; an IOCD the fuzzer reported verified but the replay did not reproduce
is counted under "fuzzing failed" and named below the table. What "effect" means depends on the sink type: a written file (FILE),
the fuzzer's no-op probe being called (INVOKE, groovy closures, fastjson2 getters), or, for the JNDI, Clojure, EL and
BeanShell sinks, the sink being entered with an inert marker (not code, not a resolvable name) in the argument the chain
controls. Nothing is looked up or evaluated. The JRMP sinks of the JDK (JRMPClient, JRMPListener) are entered with a marker
endpoint: a loopback host, so the connection is refused at once, or a marker port, which the export listens on while the
fuzzing or replay JVM runs.

## Results of a run (`reproduction/runs/<date-time>/` by default)
| path | content |
|---|---|
| `SUMMARY.md` | the result table of this run (plus the IOCDs not run), with the authors' full-run chain count next to it |
| `reproduce.log`, `build.log` | what was run when |
| `<app>/analyze.out` | per-IOCD outcomes, where unverified chains stop, why seeds failed, the distinct chains |
| `<app>/replay.csv`, `<app>/chains.csv`, `<app>/per_iocd.csv` | replay verdict per payload, one row per distinct chain, one row per IOCD |
| `<app>/distinct_chains/<key>.txt` | one file per replay-confirmed distinct chain (`<key>` as in `chains.csv`): the chain as soot method signatures, one per line, after header lines starting with `#` (key, sink, length, IOCDs, paths of the payloads that ran it) |
| `<app>/shard-*/outputs/` | the payloads (`exploitable/`, `chains/`) and `triggered_chains.jsonl` |
| `<app>/shard-*/logs/<iocd>.log` | the fuzzer's log of an IOCD |
| `<app>.d/` | start time (the limit counts from it, also when resuming), console output of the run |

`python3 src-deploy/collect_ground_truth.py <run_dir>` collects the verified IOCDs of a finished run into `GroundTruth/`.
