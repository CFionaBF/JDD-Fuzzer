Independent replay of the payloads the fuzzer saved (see replay_check.sh).

- ReplayTarget.java: the replay VM's main class; deserializes one payload file (jdk or hessian),
  nothing else. Classpath: the app's jars + the oracle classes (OracleClassA, the INVOKE probe).
- ReplayCheck.java: runs ReplayTarget under the JDK debugger interface (JDI, tools.jar) with
  breakpoints at the sink calls, so the chain runs exactly as in a plain JVM (no agent, no
  instrumentation, no SecurityManager, which would change e.g. BadAttributeValueExpException).
  - effect: FILE - FileOutputStream.write on a path containing jdd_evil_file;
            INVOKE - a probe method of OracleClassA is called
  - chain:  the call stack at that moment holds the IOCD's gadget methods, entry first, in order
  - triggered: the chain the payload really ran, with the rules of the fuzzer's TriggeredChain
- replay_check.sh <run_dir> <iocd_dir> <jar_dir>: replays outputs/exploitable/ (verified IOCDs) and
  the payloads of outputs/triggered_chains.jsonl (each distinct chain the fuzzer saw reach the sink,
  on or off the IOCD chain), checking that the replay runs the recorded chain; writes replay.csv
  and replay/<iocd>[-<key>].txt.
