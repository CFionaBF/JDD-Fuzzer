#!/bin/bash
# Independently replay the payloads a run saved, outside the fuzzer: the replay VM is a plain JVM
# (no agent, no instrumentation; classpath = the app's jars + the oracle classes) run under the JDK
# debugger interface, with breakpoints at the sink calls. Per payload (ReplayCheck.java) it checks
#   effect:    FILE sinks - FileOutputStream.write on a path containing jdd_evil_file
#              INVOKE sinks - a probe method of OracleClassA is called
#   chain:     the call stack at that moment passes through the IOCD's gadget methods, in order
#   triggered: the chain the payload really ran (same rules as the fuzzer's TriggeredChain)
# Payloads: outputs/exploitable/ (verified IOCDs) and the ones of outputs/triggered_chains.jsonl
# (every distinct chain the fuzzer saw reach the sink); for the latter the replayed chain must be
# the recorded one (column "reproduced").
# Writes <out.csv> and <run_dir>/replay/<iocd>[-<key>].txt (debuggee output + the matched stack).
# Usage: src-deploy/replay/replay_check.sh <run_dir> <iocd_dir> <jar_dir> [out.csv]
set -e
[ $# -ge 3 ] || { sed -n 2,13p "$0"; exit 1; }
HERE=$(cd "$(dirname "$0")" && pwd); REPO=$(cd "$HERE/../.." && pwd)
RUN=$1; IOCD=$2; JARS=$3; OUT=${4:-$RUN/replay.csv}
. "$HERE/../env.sh"
BIN=$(mktemp -d); trap 'rm -rf "${BIN:?}"' EXIT
TOOLS=$JDD_JAVA_HOME/lib/tools.jar
"$JDD_JAVA_HOME/bin/javac" -nowarn -cp "$TOOLS" -d "$BIN" "$HERE/ReplayCheck.java" "$HERE/ReplayTarget.java"
APPCP=$(ls "$JARS"/*.jar 2>/dev/null | tr '\n' ':')
ORACLECP=$REPO/jdd-utils/target/jdd-utils-1.0-SNAPSHOT.jar:$REPO/jdd-data/target/jdd-data-1.0-SNAPSHOT.jar:$REPO/jdd-exceptions/target/jdd-exceptions-1.0-SNAPSHOT.jar
# hessian payloads are read with the Hessian the fuzzer validated them with (jdd-validator's dependency,
# com.caucho:hessian:4.0.38): none of the evaluated apps brings its own. JDD_HESSIAN_JAR overrides it.
HESSIAN_JAR=${JDD_HESSIAN_JAR:-$HOME/.m2/repository/com/caucho/hessian/4.0.38/hessian-4.0.38.jar}
mkdir -p "$RUN/replay"

# "<kind> <iocd> <payload> <expected chain key>" per payload; kind: verified | onchain | offchain
python3 -I - "$RUN" > "$BIN/payloads.txt" <<'EOF'
import glob, json, os, sys
run = sys.argv[1]
skipped = []
for p in sorted(glob.glob(os.path.join(run, "shard-*", "inputs/finished/success/*.json"))
                + glob.glob(os.path.join(run, "inputs/finished/success/*.json"))):
    h = os.path.basename(p)[:-5]
    e = (glob.glob(os.path.join(run, "shard-*", "outputs/exploitable", h + "exp"))
         + glob.glob(os.path.join(run, "outputs/exploitable", h + "exp")))
    print("verified", h, e[0] if e else "-", "-")
seen = set()
for out in sorted(glob.glob(os.path.join(run, "shard-*", "outputs")) + glob.glob(os.path.join(run, "outputs"))):
    jl = os.path.join(out, "triggered_chains.jsonl")
    for line in (open(jl) if os.path.exists(jl) else []):
        r = json.loads(line)
        if (r["iocd"], r["key"]) in seen:
            continue
        seen.add((r["iocd"], r["key"]))
        if not r["payload"]:
            # The fuzzer recorded this chain without keeping the payload (e.g. the sink fired while the
            # seed was still being serialized, before the bytes were stored), so it cannot be replayed.
            # Report it instead of dropping it silently; analyze_run.py counts these as unverified.
            skipped.append((r["iocd"], r["key"]))
            continue
        print("onchain" if r["onIocdChain"] else "offchain", r["iocd"], os.path.join(out, r["payload"]), r["key"])
for iocd, key in skipped:
    print("[!] chain %s of IOCD %s has no recorded payload: cannot be replayed (counts as unverified)"
          % (key, iocd), file=sys.stderr)
EOF

echo "iocd,kind,protocol,sink,effect,chain,miss,key,reproduced" > "$OUT"
while read -r kind h exp key; do
    [ "$exp" = - ] && exp=
    # chain file: "<class> <method>" per gadget method of the IOCD, entry first
    read -r proto sink < <(python3 -I - "$IOCD/$h.json" "$BIN/chain.txt" <<'EOF'
import json, re, sys
d = json.load(open(sys.argv[1]))
with open(sys.argv[2], "w") as fh:
    for g in d["gadgetCallStack"]:
        m = re.match(r"<([^:]+): \S+ ([^(]+)\(", g["value"].replace("'", ""))
        fh.write(f"{m.group(1)} {m.group(2)}\n")
print(d["protocol"], d["sinkType"])
EOF
)
    if [ -z "$exp" ] || [ ! -e "$exp" ]; then echo "$h,$kind,$proto,$sink,no-artifact,,,$key," >> "$OUT"; continue; fi
    log=$RUN/replay/$h.txt; [ "$key" = - ] || log=$RUN/replay/$h-$key.txt
    res=$(cd "$BIN" && timeout 300 env -u JAVA_TOOL_OPTIONS "$JDD_JAVA_HOME/bin/java" -cp "$BIN:$TOOLS" ReplayCheck \
          "$JDD_JAVA_HOME/jre" "$BIN:$APPCP$ORACLECP$([ "$proto" = hessian ] && echo ":$HESSIAN_JAR")" "$exp" "$proto" "$sink" "$BIN/chain.txt" 2>"$log" < /dev/null | grep '^RESULT') || true
    eff=$(echo "$res" | sed -n 's/.*effect=\([^ ]*\).*/\1/p'); ch=$(echo "$res" | sed -n 's/.*chain=\([^ ]*\).*/\1/p')
    miss=$(echo "$res" | sed -n 's/.*miss=\([^ ]*\).*/\1/p'); trig=$(echo "$res" | sed -n 's/.*triggered=\([^ ]*\).*/\1/p')
    rep=
    if [ "$key" != - ]; then case ",$trig," in *",$key,"*) rep=yes;; *) rep=no;; esac; fi
    echo "$h,$kind,$proto,$sink,${eff:-error},$ch,$miss,$key,$rep" >> "$OUT"
done < "$BIN/payloads.txt"

python3 -I - "$OUT" <<'EOF'
import csv, collections, sys
rows = list(csv.DictReader(open(sys.argv[1])))
def full(r):
    a, _, b = r["chain"].partition("/")
    return r["effect"] == "ok" and a == b and a != ""
v = [r for r in rows if r["kind"] == "verified"]
if v:
    print(f"verified IOCDs: {len(v)}")
    for (s, e), n in sorted(collections.Counter((r["sink"], r["effect"]) for r in v).items()):
        print(f"  {s:7s} effect={e:12s} {n}")
    print("  effect ok and the whole IOCD chain on the stack:", sum(full(r) for r in v))
    for r in v:
        if not full(r):
            print(f"    effect {r['effect']}, chain {r['chain']} (first missing {r['miss']}): {r['iocd']}")
t = [r for r in rows if r["kind"] != "verified"]
if t:
    print(f"recorded triggered chains (per IOCD): {len(t)}, distinct chains: {len({r['key'] for r in t})}")
    for kind in ("onchain", "offchain"):
        sub = [r for r in t if r["kind"] == kind]
        if sub:
            print(f"  {kind:8s} {len(sub):4d}  replayed with effect: {sum(r['effect'] == 'ok' for r in sub)}"
                  f"  same chain reproduced: {sum(r['reproduced'] == 'yes' for r in sub)}")
    for r in t:
        if r["reproduced"] != "yes":
            print(f"    not reproduced: {r['iocd']} {r['key']} ({r['kind']}, effect {r['effect']})")
EOF
echo "[+] $OUT"
