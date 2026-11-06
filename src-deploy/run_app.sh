#!/bin/bash
# Fuzz all IOCDs of one application, split across parallel shards (one work dir each).
# Usage: src-deploy/run_app.sh <out_dir> <jar_dir> <iocd_dir> [tries=3] [shards=nproc] [fuzz_time_sec=60]
#   e.g. src-deploy/run_app.sh ~/jdd-work/CC3 ~/EvaluationJars/CC3_... JDD/IOCDDatasets/detected/CC3_...
# Layout: <out_dir>/shard-<i>/ (prepare.sh work dirs), <out_dir>/summary.txt,
#         <out_dir>/{verified,failed,badseed}.txt (IOCD hashes)
# JDD_DEADLINE=<unix time> / JDD_SHUFFLE_SEED=<n>: see fuzz.sh (time cap; reproducible random IOCD order). The
#         summary then also counts the IOCDs not run.
# RESUME=1: continue an interrupted run in <out_dir> (same arguments): finished IOCDs are kept, the
#         ones being fuzzed when it stopped are fuzzed again from their first try.
set -e
[ $# -ge 3 ] || { sed -n 2,6p "$0"; exit 1; }
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$(mkdir -p "$1" && cd "$1" && pwd); JARS=$2; IOCD=$3
TRIES=${4:-3}; SHARDS=${5:-$(nproc)}; FT=${6:-60}

mapfile -t ALL < <(find -H "$IOCD" -maxdepth 1 -name '*.json' ! -name 'IfStmtRecord*.json' -printf '%f\n' | sort)
if [ "${RESUME:-0}" = 1 ]; then
    for i in $(seq 0 $((SHARDS - 1))); do
        W=$OUT/shard-$i
        [ -d "$W/pool" ] || { echo "[-] RESUME=1: $W is not a work dir of this run"; exit 1; }
        # the IOCD a shard was fuzzing when the run stopped goes back into its queue
        for g in "$W"/inputs/gadgets/*.json; do [ -e "$g" ] && mv "$g" "$W/pool/"; done
        echo "[+] resume shard-$i: $(ls "$W"/pool/*.json 2>/dev/null | wc -l) IOCDs left" | tee -a "$OUT/runner-resume.log"
    done
    echo "[+] resumed $(date)" >> "$OUT/summary.txt"
else
    echo "[+] $(basename "$IOCD"): ${#ALL[@]} IOCDs, $SHARDS shards, $TRIES tries, ${FT}s per try" | tee "$OUT/summary.txt"
    for i in $(seq 0 $((SHARDS - 1))); do
        W=$OUT/shard-$i
        rm -rf "${W:?}"
        "$HERE/prepare.sh" "$W" "$JARS" "$IOCD" "$FT" >/dev/null
        rm -f "$W"/pool/*.json
    done
    # round-robin split
    for k in "${!ALL[@]}"; do cp "$IOCD/${ALL[$k]}" "$OUT/shard-$((k % SHARDS))/pool/"; done
fi

start=$(date +%s)
for i in $(seq 0 $((SHARDS - 1))); do
    "$HERE/fuzz.sh" "$OUT/shard-$i" "$TRIES" >> "$OUT/shard-$i/fuzz.out" 2>&1 &
done
wait

cat "$OUT"/shard-*/logs/runner.log 2>/dev/null > "$OUT/runner.log" || true
ls "$OUT"/shard-*/inputs/finished/success/ 2>/dev/null | grep '\.json$' | sed 's/.json$//' | sort > "$OUT/verified.txt" || true
ls "$OUT"/shard-*/inputs/finished/badseed/ 2>/dev/null | grep '\.json$' | sed 's/.json$//' | sort > "$OUT/badseed.txt" || true
ls "$OUT"/shard-*/tmp/ 2>/dev/null | grep '\.json$' | sed 's/.json$//' | sort > "$OUT/failed.txt" || true
{
    echo "wall time: $(( ($(date +%s) - start) / 60 )) min"
    echo "verified (exploitable object generated): $(wc -l < "$OUT/verified.txt")"
    echo "not verified within $TRIES tries:        $(wc -l < "$OUT/failed.txt")"
    echo "invalid seed (seed generation failed):  $(wc -l < "$OUT/badseed.txt")"
    echo "not run (time cap reached):             $(ls "$OUT"/shard-*/pool/*.json 2>/dev/null | wc -l)"
    echo "total:                                  ${#ALL[@]}"
} | tee -a "$OUT/summary.txt"
