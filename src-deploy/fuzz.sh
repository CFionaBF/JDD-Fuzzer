#!/bin/bash
# Fuzz every IOCD in <work_dir>/pool one at a time (the original runner's strategy),
# using the jars built in this repo, so a code change only needs build.sh + fuzz.sh.
# Usage: src-deploy/fuzz.sh <work_dir> [max_tries=10]
# JDD_DEADLINE=<unix time>: take no new IOCD after this moment (the one being fuzzed finishes its tries); the
#   IOCDs left stay in pool/ and count as "not run". JDD_SHUFFLE_SEED=<n>: take the IOCDs in a pseudo-random but
#   reproducible order instead of by file name, so a run cut by the deadline is a uniform sample of the IOCDs.
# Results: inputs/finished/{success,...}/, outputs/exploitable/, logs/
# Extra JVM options: JDD_JAVA_OPTS (e.g. "-Djdd.dumpSeed=true" to log each initial seed,
# "-Djdd.debug=true" for the [DEBUG] lines). The per-try stats and fuzzer logs of an IOCD are gzipped
# once it is done (logs/<iocd>.log, which analyze_run.py reads, is kept as is).
# FILE payloads write into a directory of this work dir's own (/tmp/jdd_evil_file-<id>), which the
# fuzzer empties before each execution, so parallel work dirs do not see each other's files.
set -e
[ $# -ge 1 ] || { sed -n 2,6p "$0"; exit 1; }
REPO=$(cd "$(dirname "$0")/.." && pwd)
W=$(cd "$1" && pwd); TRIES=${2:-10}
. "$(dirname "$0")/env.sh"
G=$W/inputs/gadgets
EVIL_DIR=/tmp/jdd_evil_file-$(printf %s "$W" | md5sum | cut -c1-8)

next_iocd() {
    if [ -n "$JDD_SHUFFLE_SEED" ]; then
        ls pool/*.json 2>/dev/null | shuf -n 1 --random-source=<(yes "$JDD_SHUFFLE_SEED")
    else
        ls pool/*.json 2>/dev/null | head -n 1
    fi
}

cd "$W"
while :; do
    if [ -n "$JDD_DEADLINE" ] && [ "$(date +%s)" -ge "$JDD_DEADLINE" ]; then
        echo "[!] time cap reached: $(ls pool/*.json 2>/dev/null | wc -l) IOCDs not run" | tee -a logs/runner.log
        break
    fi
    f=$(next_iocd)
    [ -n "$f" ] || break
    h=$(basename "$f" .json); mv "$f" "$G/"
    for i in $(seq 1 "$TRIES"); do
        echo "[+] $h try $i/$TRIES" | tee -a logs/runner.log
        touch "$W/.jdd_start"   # per-work-dir start file, see Fuzzer.java
        # hard limit per try (instrumentation + fuzzing + margin), in case the JVM hangs anyway
        timeout -k 30 "${JDD_TRY_LIMIT:-1800}" "$JAVA" $JDD_JAVA_OPTS -Djdd.startFile="$W/.jdd_start" -Djdd.evilFileDir="$EVIL_DIR" -javaagent:"$REPO/jdd-instrumentor/target/jdd-instrumentor-1.0-SNAPSHOT.jar=0,false" \
            -jar "$REPO/jdd-validator/target/jdd-validator-1.0-SNAPSHOT-jar-with-dependencies.jar" fuzz \
            >> "logs/$h.log" 2>&1 || true
        [ -e "$G/$h.json" ] || break
    done
    if [ -e "$G/$h.json" ]; then mv "$G/$h.json" tmp/; echo "[-] $h not verified" | tee -a logs/runner.log; fi
    find logs -maxdepth 1 -type f \( -name "$h-stats-*" -o -name 'jdd-fuzzer-log-*' \) ! -name '*.gz' -exec gzip -q {} + || true
done
rm -rf "${EVIL_DIR:?}"
echo "[+] verified: $(ls inputs/finished/success 2>/dev/null | wc -l)  exploitable: $(ls outputs/exploitable | wc -l)"
