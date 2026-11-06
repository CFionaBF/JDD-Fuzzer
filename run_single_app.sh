#!/bin/bash
# Run the fuzzer on ONE application, end to end: deploy the environment under test (JDK, build), fuzz every IOCD of the
# application, replay what it found in an independent plain JVM, and count the distinct confirmed gadget chains.
# (reproduction/reproduce.sh does the same for the 20 evaluated applications.)
#
#   ./run_single_app.sh <iocd_dir> [--work_dir <work_dir>] [--inputClassPath <jar_dir>]
#
#   <iocd_dir>                  the IOCDs (<hash>.json) and IfStmtRecord.json the static analysis module (jdd-detector)
#                               produced for the application
#   --work_dir <work_dir>       optional; "work_dir" of config.properties: where the script works and writes its results
#                               (default: reproduction/runs/<date-time>). Run again with the same <work_dir> to resume.
#   --inputClassPath <jar_dir>  optional; "inputClassPath" of config.properties: the directory with the jars of the
#                               application. They are copied to <shard>/inputs/lib/, which is what
#                               "inputClassPath = lib/" refers to. Without it only the JDK classes are analysed.
#
# config.properties is created by the script: one per shard, <work_dir>/<application>/shard-<i>/config/config.properties,
# with "work_dir" set to that shard's directory, "inputClassPath = lib/" and these defaults: fuzzTime = FUZZ_TIME (60),
# projectsInputDir = inputs, gadgetInfos_dir = gadgets, ignoreListPath = config/ignoreClasses, outputDir = outputs,
# exploitable_dir = exploitable, withAllJdk = false.
#
# Needs: Oracle JDK 8u361 (JDD_JDK_TARBALL=/path/to/jdk-8u361-linux-x64.tar.gz, or an installed one: JDD_JAVA_HOME),
# a JDK 8 + Maven + git + python3 + network access (to build). See reproduction/README.md.
#
# Settings (environment), as for reproduce.sh:
#   APP_TIME_LIMIT  fuzzing time limit                           (default: 6h; 90m, 300s or plain seconds also work; 0: none)
#   TRIES           tries per IOCD, a try fuzzes up to FUZZ_TIME (default: 2)
#   FUZZ_TIME       seconds a try fuzzes an IOCD                 (default: 60)
#   SHARDS          IOCDs fuzzed in parallel (JVMs)              (default: number of CPU cores)
#   SEED            seed of the IOCD order                       (default: 1)
#   APP_NAME        name of the application in the results       (default: the name of <iocd_dir>)
# Results: <work_dir>/<application>/ as described in reproduction/README.md, <work_dir>/SUMMARY.md, <work_dir>/run.log.
set -u
REPO=$(cd "$(dirname "$0")" && pwd)
SD=$REPO/src-deploy
TRIES=${TRIES:-2}; FUZZ_TIME=${FUZZ_TIME:-60}; SHARDS=${SHARDS:-$(nproc)}; SEED=${SEED:-1}

to_seconds() {
    case "$1" in
        *h) echo $(( ${1%h} * 3600 ));; *m) echo $(( ${1%m} * 60 ));; *s) echo "${1%s}";; *) echo "$1";;
    esac
}
usage() { sed -n '2,/^set -u$/p' "$0" | sed '$d' >&2; exit "${1:-1}"; }
LIMIT=$(to_seconds "${APP_TIME_LIMIT:-6h}")

# 0. the arguments (checked before anything is installed or built)
IOCD=""; WORK_DIR=""; JARS=""
while [ $# -gt 0 ]; do
    case "$1" in
        --work_dir)            [ $# -ge 2 ] || usage; WORK_DIR=$2; shift 2;;
        --work_dir=*)          WORK_DIR=${1#*=}; shift;;
        --inputClassPath)      [ $# -ge 2 ] || usage; JARS=$2; shift 2;;
        --inputClassPath=*)    JARS=${1#*=}; shift;;
        -h|--help)             usage 0;;
        -*)                    echo "[-] unknown option: $1" >&2; usage;;
        *)                     [ -z "$IOCD" ] || { echo "[-] more than one iocd_dir: $IOCD and $1" >&2; usage; }
                               IOCD=$1; shift;;
    esac
done
[ -n "$IOCD" ] || usage
[ -d "$IOCD" ] || { echo "[-] iocd_dir not found: $IOCD" >&2; exit 1; }
IOCD=$(cd "$IOCD" && pwd)
[ -n "$(find -H "$IOCD" -maxdepth 1 -name '*.json' ! -name 'IfStmtRecord*.json' -print -quit)" ] \
    || { echo "[-] no IOCD (<hash>.json) in $IOCD" >&2; exit 1; }
[ -f "$IOCD/IfStmtRecord.json" ] || [ -n "${IFSTMT:-}" ] \
    || { echo "[-] $IOCD/IfStmtRecord.json is missing (or set IFSTMT=<file>)" >&2; exit 1; }
if [ -n "$JARS" ]; then
    [ -d "$JARS" ] || { echo "[-] inputClassPath not found: $JARS" >&2; exit 1; }
    JARS=$(cd "$JARS" && pwd)
    ls "$JARS"/*.jar >/dev/null 2>&1 || { echo "[-] no *.jar in $JARS (--inputClassPath takes a directory of jars)" >&2; exit 1; }
fi
NAME=$(printf %s "${APP_NAME:-$(basename "$IOCD")}" | tr -c 'A-Za-z0-9._-' '_')
OUT=${WORK_DIR:-$REPO/reproduction/runs/$(date +%Y%m%d-%H%M%S)}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/run.log"; }

for tool in git mvn python3 java; do
    command -v $tool >/dev/null || { echo "[-] $tool not found" >&2; exit 1; }
done

# 1. the JDK the IOCDs were produced with, and the fuzzer
log "setup: JDK, build"
if [ ! -x "$REPO/jdk-for-jdd/jdk1.8.0_361/bin/java" ] && [ -z "${JDD_JAVA_HOME:-}" ]; then
    "$SD/setup_jdk.sh" || exit 1
fi
( . "$SD/env.sh" ) || exit 1
if [ ! -f "$REPO/jdd-validator/target/jdd-validator-1.0-SNAPSHOT-jar-with-dependencies.jar" ]; then
    "$SD/build.sh" > "$OUT/build.log" 2>&1 || { echo "[-] build failed, see $OUT/build.log" >&2; exit 1; }
fi

# 2. fuzz, replay and analyze
W=$OUT/$NAME
if [ -e "$W/.done" ]; then
    log "$NAME: done already (use another --work_dir to run it again)"
else
    mkdir -p "$W.d"; [ -e "$W.d/start" ] || date +%s > "$W.d/start"
    if [ -z "$JARS" ]; then
        JARS=$W.d/no-jars; mkdir -p "$JARS"
        log "$NAME: no --inputClassPath, only the JDK classes are analysed"
    fi
    deadline=""; [ "$LIMIT" -gt 0 ] && deadline=$(( $(cat "$W.d/start") + LIMIT ))
    resume=0; [ -d "$W/shard-0/pool" ] && resume=1
    log "$NAME: fuzzing (limit $([ "$LIMIT" -gt 0 ] && echo "$(( LIMIT / 60 )) min" || echo none), $TRIES tries x ${FUZZ_TIME}s, $SHARDS shards)$([ $resume = 1 ] && echo ', resuming')"
    JDD_DEADLINE=$deadline JDD_SHUFFLE_SEED=$SEED RESUME=$resume \
        "$SD/run_app.sh" "$W" "$JARS" "$IOCD" "$TRIES" "$SHARDS" "$FUZZ_TIME" > "$W.d/run_app.out" 2>&1 \
        || { log "$NAME: run_app.sh failed, see $W.d/run_app.out"; exit 1; }
    log "$NAME: replay"
    "$SD/replay/replay_check.sh" "$W" "$IOCD" "$JARS" 2>&1 | grep -v Picked > "$W/replay.out"
    log "$NAME: analysis"
    python3 -I "$SD/analyze_run.py" "$W" "$IOCD" --csv "$W/per_iocd.csv" --chains "$W/chains.csv" > "$W/analyze.out" 2>&1
    touch "$W/.done"
    log "$NAME: $(grep -E 'Triggered chains' "$W/analyze.out" | head -1)"
fi

# 3. the table
python3 -I "$SD/reproduce_summary.py" "$OUT" "$NAME" | tee -a "$OUT/run.log"
log "results: $OUT (SUMMARY.md, $NAME/analyze.out, $NAME/replay.csv, $NAME/chains.csv; payloads in $NAME/shard-*/outputs/)"
