#!/bin/bash
# One application, end to end: JDD (static gadget chain detection) -> IOCDs -> JDD-Fuzzer (dynamic verification and
# independent replay) -> how many of JDD's gadget chains were verified and replayed, and which ones.
#
#   e2e/run_app_e2e.sh <app> [--work_dir <dir>] [--skip_jdd] [--skip_fuzz]
#
#   <app>              a row of e2e/apps.tsv (jar set, jars JDD analyses, protocol, sinks, JDD settings)
#   --work_dir <dir>   default: $E2E_RUNS/<app> (see e2e/env.sh). Run again with the same dir to resume the fuzzing.
#   --skip_jdd         reuse the JDD output already in <work_dir>/jdd
#   --skip_fuzz        reuse the fuzzer output already in <work_dir>/fuzz (only rewrite the result)
#
# Steps and output:
#   1. e2e/run_jdd.sh            <work_dir>/jdd/outputs/gadgets/<app>/   IOCDs (<hash>.json) + IfStmtRecord.json,
#                                                                         DetectedGadgetChains.txt; jdd.log
#   2. run_single_app.sh         <work_dir>/fuzz/<app>/   per_iocd.csv, replay.csv, chains.csv, analyze.out, payloads
#                                <work_dir>/fuzz/SUMMARY.md   the reproduction summary table for this app
#   3. e2e/e2e_report.py         <work_dir>/RESULT.md, <work_dir>/result.json: counts and IOCD ids of the chains
#                                verified by the fuzzer and confirmed by the independent replay
#
# Settings (environment): the columns of apps.tsv can be overridden with JARS_DIR (a directory), ANALYZE (globs),
# PROTOCOL, SINK_RULES, JDD_SET, JDD_TIMEOUT; JDD_XMX (default 8g); the fuzzer's APP_TIME_LIMIT (6h), TRIES (2),
# FUZZ_TIME (60), SHARDS (CPU cores), SEED (1), see run_single_app.sh. Paths: e2e/env.sh.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/env.sh"
usage() { sed -n '2,/^set -euo/p' "$0" | sed '$d' >&2; exit "${1:-1}"; }

APP=""; W=""; SKIP_JDD=0; SKIP_FUZZ=0
while [ $# -gt 0 ]; do
    case "$1" in
        --work_dir)  [ $# -ge 2 ] || usage; W=$2; shift 2;;
        --skip_jdd)  SKIP_JDD=1; shift;;
        --skip_fuzz) SKIP_FUZZ=1; shift;;
        -h|--help)   usage 0;;
        -*)          echo "[-] unknown option: $1" >&2; usage;;
        *)           [ -z "$APP" ] || usage; APP=$1; shift;;
    esac
done
[ -n "$APP" ] || usage

# 0. the application's settings
row=$(awk -F'\t' -v a="$APP" '!/^#/ && $1 == a' "$HERE/apps.tsv")
[ -n "$row" ] || { echo "[-] $APP is not in e2e/apps.tsv (apps: $(awk -F'\t' '!/^#/ && NR>1 && $1!="app"{printf "%s ", $1}' "$HERE/apps.tsv"))" >&2; exit 1; }
col() { local v; v=$(printf '%s\n' "$row" | cut -f"$1"); [ "$v" = "-" ] && v=""; printf '%s' "$v"; }
DEFAULT_SINKS=exec,invoke,jndi,classLoad,custom
JARS=${JARS_DIR:-$DATA_JARS/$(col 2)}
ANALYZE=${ANALYZE:-$(col 3)}
export PROTOCOL=${PROTOCOL:-$(col 4)}; PROTOCOL=${PROTOCOL:-jdk}
SINKS=${SINK_RULES:-$(col 5)}
case "$SINKS" in "") SINKS=$DEFAULT_SINKS;; +*) SINKS=$DEFAULT_SINKS,${SINKS#+};; esac
export SINK_RULES=$SINKS
export JDD_SET=${JDD_SET:-$(col 6)}
export JDD_TIMEOUT=${JDD_TIMEOUT:-$(col 7)}; JDD_TIMEOUT=${JDD_TIMEOUT:-3600}

[ -d "$JARS" ] && ls "$JARS"/*.jar >/dev/null 2>&1 || {
    echo "[-] no jar set for $APP at $JARS; build it with:" >&2
    echo "    JAVA_HOME=$JDK8_HOME $FUZZER_REPO/src-deploy/evaluation-jars/build.sh $DATA_JARS $(col 2)" >&2
    exit 1; }
JARS=$(cd "$JARS" && pwd)
W=${W:-$E2E_RUNS/$APP}; mkdir -p "$W"; W=$(cd "$W" && pwd)
IOCD=$W/jdd/outputs/gadgets/$APP
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$W/e2e.log"; }

# what this run used, for result.json
{
    echo "app=$APP"
    echo "jars=$JARS"
    echo "analyze=${ANALYZE:-*}"
    echo "protocol=$PROTOCOL"
    echo "sinks=$SINK_RULES"
    echo "jdd_set=$JDD_SET"
    echo "jdd_timeout=$JDD_TIMEOUT"
    echo "jdd_commit=$(git -C "$JDD_REPO" rev-parse --short HEAD 2>/dev/null || echo -)"
    echo "fuzzer_commit=$(git -C "$FUZZER_REPO" rev-parse --short HEAD 2>/dev/null || echo -)"
    echo "jdk=$JDK8_HOME"
    echo "fuzzer=TRIES=${TRIES:-2} FUZZ_TIME=${FUZZ_TIME:-60} APP_TIME_LIMIT=${APP_TIME_LIMIT:-6h} SHARDS=${SHARDS:-$(nproc)} SEED=${SEED:-1}"
} > "$W/settings.txt"

# 1. static analysis
if [ "$SKIP_JDD" = 1 ]; then
    [ -f "$IOCD/IfStmtRecord.json" ] || { echo "[-] --skip_jdd: no JDD output in $IOCD" >&2; exit 1; }
    log "JDD: reusing $IOCD"
else
    [ -f "$JDD_JAR" ] || { echo "[-] $JDD_JAR not built (cd $JDD_REPO && JAVA_HOME=$JDK8_HOME mvn package -DskipTests)" >&2; exit 1; }
    IN=$W/jdd-input; rm -rf "$IN"; mkdir -p "$IN"
    IFS=, read -r -a globs <<< "${ANALYZE:-*.jar}"
    for g in "${globs[@]}"; do
        for j in "$JARS"/$g; do [ -f "$j" ] && ln -sf "$j" "$IN/"; done
    done
    [ -n "$(ls "$IN")" ] || { echo "[-] ANALYZE='$ANALYZE' matches no jar in $JARS" >&2; exit 1; }
    log "JDD: $APP, analysing $(ls "$IN" | tr '\n' ' ')(protocol $PROTOCOL, sinks $SINK_RULES, limit ${JDD_TIMEOUT}s)"
    "$HERE/run_jdd.sh" "$APP" "$IN" "$W/jdd" 2>&1 | tee -a "$W/e2e.log" || true
    [ -f "$IOCD/IfStmtRecord.json" ] || { log "JDD produced no output, see $W/jdd/jdd.log"; exit 1; }
fi
n=$(find -H "$IOCD" -maxdepth 1 -name '*.json' ! -name 'IfStmtRecord*.json' | wc -l)
log "JDD: $n IOCD(s) in $IOCD"

# 2. dynamic verification of exactly those IOCDs
if [ "$n" -eq 0 ]; then
    log "nothing to fuzz"
elif [ "$SKIP_FUZZ" = 1 ]; then
    [ -f "$W/fuzz/$APP/per_iocd.csv" ] || { echo "[-] --skip_fuzz: no fuzzer output in $W/fuzz/$APP" >&2; exit 1; }
    log "fuzzer: reusing $W/fuzz/$APP"
else
    [ -f "$FUZZER_REPO/jdd-validator/target/jdd-validator-1.0-SNAPSHOT-jar-with-dependencies.jar" ] || {
        echo "[-] JDD-Fuzzer not built (JDD_BUILD_JAVA_HOME=$JDK8_HOME QUICKCHECK_VERSION=1.0 $FUZZER_REPO/src-deploy/build.sh)" >&2
        exit 1; }
    log "fuzzer: $n IOCD(s), run-time jars $JARS"
    APP_NAME=$APP "$FUZZER_REPO/run_single_app.sh" "$IOCD" --work_dir "$W/fuzz" --inputClassPath "$JARS" 2>&1 \
        | tee -a "$W/e2e.log"
fi

# 3. the result
python3 -I "$HERE/e2e_report.py" "$W" "$APP" | tee -a "$W/e2e.log"
