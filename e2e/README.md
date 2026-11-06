# End-to-end: JDD → JDD-Fuzzer

Runs JDD on one application, then JDD-Fuzzer on the IOCDs it produced, and reports the verified and replay-confirmed
chains with their IOCD ids.

```bash
e2e/run_app_e2e.sh <app> [--work_dir <dir>] [--skip_jdd] [--skip_fuzz]
```

`<app>` is a row of `apps.tsv`: jar set, analysed jars, protocol, sinks, JDD settings.

## Setup

Paths default to the parent of the JDD-Fuzzer directory: the JDD checkout is that directory or `JDD/` in it
(`JDD_REPO`), jar sets are in `data/jars` (`DATA_JARS`), runs go to `runs/e2e` (`E2E_RUNS`).

- Jar sets: `JAVA_HOME=$JDK8_HOME src-deploy/evaluation-jars/build.sh $DATA_JARS <app>`
- Builds: JDD `mvn package -DskipTests`; fuzzer `QUICKCHECK_VERSION=1.0 src-deploy/build.sh`
- JDD and the fuzzer run on the same JDK 8, `JDK8_HOME` (default `/usr/lib/jvm/java-8-openjdk-amd64`)

## Output

- `RESULT.md`, `result.json`: the IOCDs verified by the fuzzer, those confirmed by the independent replay (sink effect
  with the whole IOCD chain on the stack, in a plain JVM), and the distinct chains the replay confirmed
- `jdd/outputs/gadgets/<app>/`: IOCDs and `IfStmtRecord.json`; `jdd/outputs/analysisTimeouts.txt`: analyses cut by
  JDD's per-method time budget
- `fuzz/`: `SUMMARY.md`, `<app>/per_iocd.csv`, `replay.csv`, `chains.csv`
