#!/bin/bash
# One-click reproduction: deploy the environment under test (JDK, build, dataset, jars), then run every application
# (JDD IOCD dataset + the jars it was detected in) through the fuzzer, replay what it found in an independent plain
# JVM, and count the distinct confirmed gadget chains per application.
#
#   ./reproduction/reproduce.sh                  all 20 applications, at most 6 hours of fuzzing each
#   APPS="Wicket JDK" ./reproduction/reproduce.sh  only these applications
#   APP_TIME_LIMIT=2h TRIES=1 ./reproduction/reproduce.sh  other limits
#   CLEAN=1 ./reproduction/reproduce.sh          wipe the dataset/jars cache, the build and any previous run first,
#                                                 then deploy and run from nothing (verifies the one-click setup
#                                                 itself, not only the fuzzing); plain re-runs reuse what is cached
#
# Needs: Oracle JDK 8u361 (JDD_JDK_TARBALL=/path/to/jdk-8u361-linux-x64.tar.gz, or an installed one: JDD_JAVA_HOME),
# a JDK 8 + Maven + git + python3 + network access (to build, fetch the dataset and resolve the jars). See README.md
# in this directory.
#
# Settings (environment):
#   APPS            applications to run, space separated        (default: all, small ones first)
#   APP_TIME_LIMIT  fuzzing time limit per application          (default: 6h; 90m, 300s or plain seconds also work)
#   TRIES           tries per IOCD, a try fuzzes up to FUZZ_TIME (default: 2; SUMMARY.md used 3)
#   FUZZ_TIME       seconds a try fuzzes an IOCD                 (default: 60)
#   SHARDS          IOCDs fuzzed in parallel (JVMs)              (default: number of CPU cores)
#   SEED            seed of the IOCD order: with the limit the run covers a reproducible random sample (default: 1)
#   OUT             result directory; run again with the same OUT to resume (default: reproduction/runs/<date-time>)
#   DATA_DIR        where the dataset and the jars are kept      (default: reproduction/data)
#   CLEAN           1: wipe DATA_DIR, the Maven build output and any OUT left from a previous run before starting
# The time limit covers the fuzzing only: it stops taking new IOCDs when reached, the replay and the analysis follow.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
SD=$REPO/src-deploy
ALL_APPS="JBossInterceptors javassistWeld Fileupload Wicket BeanShell JDK Myface click1 remo cc4 CC3_CommonsBeanutils_AspectJWeaver hibernate groovy clojure fastjson2-Hessian rometool-Hessian vaadin XBean-Hessian Resin-Hessian Spring-Hessian"
APPS=${APPS:-$ALL_APPS}
TRIES=${TRIES:-2}; FUZZ_TIME=${FUZZ_TIME:-60}; SHARDS=${SHARDS:-$(nproc)}; SEED=${SEED:-1}
DATA_DIR=${DATA_DIR:-$HERE/data}
OUT=${OUT:-$HERE/runs/$(date +%Y%m%d-%H%M%S)}

to_seconds() {
    case "$1" in
        *h) echo $(( ${1%h} * 3600 ));; *m) echo $(( ${1%m} * 60 ));; *s) echo "${1%s}";; *) echo "$1";;
    esac
}
LIMIT=$(to_seconds "${APP_TIME_LIMIT:-6h}")

if [ "${CLEAN:-0}" = 1 ]; then
    echo "[+] CLEAN=1: removing $DATA_DIR, ${OUT:+$OUT (if it already exists)}, and the Maven build output"
    rm -rf "$DATA_DIR" "$OUT"
    ( cd "$REPO" && mvn -q clean -Dmaven.multiModuleProjectDirectory="$REPO" ) 2>/dev/null || true
fi

mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/reproduce.log"; }

for tool in git mvn python3 java; do
    command -v $tool >/dev/null || { echo "[-] $tool not found" >&2; exit 1; }
done
for app in $APPS; do
    case " $ALL_APPS " in *" $app "*) ;; *) echo "[-] unknown application: $app (known: $ALL_APPS)" >&2; exit 1;; esac
done

# 1. the JDK the IOCDs were produced with, the fuzzer, the dataset and the jars
log "setup: JDK, build, dataset, jars (APPS: $APPS)"
if [ ! -x "$REPO/jdk-for-jdd/jdk1.8.0_361/bin/java" ] && [ -z "${JDD_JAVA_HOME:-}" ]; then
    "$SD/setup_jdk.sh" || exit 1
fi
( . "$SD/env.sh" ) || exit 1
if [ ! -f "$REPO/jdd-validator/target/jdd-validator-1.0-SNAPSHOT-jar-with-dependencies.jar" ]; then
    "$SD/build.sh" > "$OUT/build.log" 2>&1 || { echo "[-] build failed, see $OUT/build.log" >&2; exit 1; }
fi
"$SD/fetch_data.sh" "$DATA_DIR" $APPS 2>&1 | tee -a "$OUT/reproduce.log" | grep -E '^\[' ; [ "${PIPESTATUS[0]}" = 0 ] || { echo "[-] fetching the data failed" >&2; exit 1; }
DATA_DIR=$(cd "$DATA_DIR" && pwd)

# 2. fuzz, replay and analyze one application after the other
failed=0
for app in $APPS; do
    W=$OUT/$app; IOCD=$DATA_DIR/iocd/$app; JARS=$DATA_DIR/jars/$app
    if [ -e "$W/.done" ]; then log "$app: done already"; continue; fi
    mkdir -p "$W.d"; [ -e "$W.d/start" ] || date +%s > "$W.d/start"
    deadline=$(( $(cat "$W.d/start") + LIMIT ))
    resume=0; [ -d "$W/shard-0/pool" ] && resume=1
    log "$app: fuzzing (limit $(( LIMIT / 60 )) min, $TRIES tries x ${FUZZ_TIME}s, $SHARDS shards)$([ $resume = 1 ] && echo ', resuming')"
    JDD_DEADLINE=$deadline JDD_SHUFFLE_SEED=$SEED RESUME=$resume \
        "$SD/run_app.sh" "$W" "$JARS" "$IOCD" "$TRIES" "$SHARDS" "$FUZZ_TIME" > "$W.d/run_app.out" 2>&1 \
        || { log "$app: run_app.sh failed, see $W.d/run_app.out"; failed=1; continue; }
    log "$app: replay"
    "$SD/replay/replay_check.sh" "$W" "$IOCD" "$JARS" 2>&1 | grep -v Picked > "$W/replay.out"
    log "$app: analysis"
    python3 -I "$SD/analyze_run.py" "$W" "$IOCD" --csv "$W/per_iocd.csv" --chains "$W/chains.csv" > "$W/analyze.out" 2>&1
    touch "$W/.done"
    log "$app: $(grep -E 'Triggered chains' "$W/analyze.out" | head -1)"
done

# 3. the table
python3 -I "$SD/reproduce_summary.py" "$OUT" $APPS | tee -a "$OUT/reproduce.log"
log "results: $OUT (SUMMARY.md, <app>/analyze.out, <app>/replay.csv, <app>/chains.csv)"
exit $failed
