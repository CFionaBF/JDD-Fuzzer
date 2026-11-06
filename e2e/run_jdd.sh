#!/bin/bash
# Run JDD (static gadget chain detection) on one application, in its own work directory.
#
#   e2e/run_jdd.sh <app_name> <jar_dir> [work_dir]
#
#   <app_name>  name of the result directory (outPutDirName)
#   <jar_dir>   directory with the jars/wars to analyse (inputPath); it is copied, never modified
#   [work_dir]  default: $E2E_RUNS/<app_name>/jdd (see e2e/env.sh)
#
# Output = input of JDD-Fuzzer: <work_dir>/outputs/gadgets/<app_name>/
#   <hash>.json               one IOCD per gadget chain
#   IfStmtRecord.json         the instrumentation record
#   DetectedGadgetChains.txt  the chains in readable form
#
# Settings (environment):
#   PROTOCOL           jdk | hessian | json                      (default: jdk)
#   SINK_RULES         sinks to detect                           (default: exec,invoke,jndi,classLoad,custom)
#                      available: classLoad,invoke,jndi,exec,secondDes,custom,file
#   NEED_SERIALIZABLE  true | false                              (default: true)
#   JDD_SET            further config.properties overrides, e.g. "fragmentLenLimit=8 chainLimit=20"
#   JDD_TIMEOUT        wall-clock limit of the analysis, seconds (default: 3600)
#   JDD_XMX            JVM heap                                  (default: 8g)
# The other keys keep the values of JDD/config/config.properties (see JDD/document/UsageGuide.md).
set -euo pipefail
. "$(dirname "$0")/env.sh"

[ $# -ge 2 ] || { sed -n '2,/^set -euo/p' "$0" | sed '$d' >&2; exit 1; }
APP=$1; JARS=$2; W=${3:-$E2E_RUNS/$APP/jdd}
[ -d "$JARS" ] || { echo "[-] jar_dir not found: $JARS" >&2; exit 1; }
[ -n "$(find -H "$JARS" -maxdepth 1 \( -name '*.jar' -o -name '*.war' \) -print -quit)" ] \
    || { echo "[-] no *.jar/*.war in $JARS" >&2; exit 1; }
[ -f "$JDD_JAR" ] || { echo "[-] $JDD_JAR not built (cd JDD && mvn package -DskipTests with JDK 8)" >&2; exit 1; }

mkdir -p "$W"; W=$(cd "$W" && pwd)
OUT=$W/outputs/gadgets/$APP
# JDD reuses an existing <inputPath>/tmp/ (extracted classes) and appends to an existing result dir:
# start from a clean input and output every time
rm -rf "${W:?}/lib" "${OUT:?}" "$W/outputs/gadgets/interInfos"
mkdir -p "$W/lib" "$W/config" "$W/inPuts"
find -H "$JARS" -maxdepth 1 \( -name '*.jar' -o -name '*.war' \) -exec cp {} "$W/lib/" \;
# rules/ignore lists (JDD appends unparsable classes to ignoreInfo, so each work dir has its own copy)
[ -d "$W/inPuts/config" ] || cp -r "$JDD_REPO/inPuts/config" "$W/inPuts/"
# the reflective-edge / super-call tables are read-only for JDD (unlike ignoreInfo): always take the current ones
for t in reflectionEdges superCalls; do
    if [ -f "$JDD_REPO/inPuts/config/$t" ]; then cp -f "$JDD_REPO/inPuts/config/$t" "$W/inPuts/config/"; fi
done

C=$W/config/config.properties
cp "$JDD_REPO/config/config.properties" "$C"
setprop() {   # setprop <key> <value>: replace the key's line, or append it
    if grep -qE "^$1[[:space:]]*=" "$C"; then
        python3 -I -c 'import re,sys; p,k,v=sys.argv[1:]; s=open(p).read(); open(p,"w").write(re.sub(r"(?m)^"+re.escape(k)+r"\s*=.*$", lambda m: k+" = "+v, s))' "$C" "$1" "$2"
    else
        echo "$1 = $2" >> "$C"
    fi
}
setprop inputPath lib/
setprop configPath inPuts/config
setprop outputDir outputs
setprop outPutDirName "$APP"
setprop outPutIOCD true
setprop protocol "${PROTOCOL:-jdk}"
setprop sinkRules "${SINK_RULES:-exec,invoke,jndi,classLoad,custom}"
setprop needSerializable "${NEED_SERIALIZABLE:-true}"
for kv in ${JDD_SET:-}; do setprop "${kv%%=*}" "${kv#*=}"; done

echo "[+] JDD: $APP, $(ls "$W/lib" | wc -l) jar(s), protocol=${PROTOCOL:-jdk}, sinks=${SINK_RULES:-exec,invoke,jndi,classLoad,custom}"
echo "    work dir: $W  (log: $W/jdd.log)"
start=$(date +%s)
set +e
(cd "$W" && timeout -k 30 "${JDD_TIMEOUT:-3600}" "$JDK8_HOME/bin/java" "-Xmx${JDD_XMX:-8g}" -Dfile.encoding=UTF-8 \
    -Djdd.config="$C" -jar "$JDD_JAR") > "$W/jdd.log" 2>&1
rc=$?
set -e
n=$(find "$OUT" -maxdepth 1 -name '*.json' ! -name 'IfStmtRecord*.json' 2>/dev/null | wc -l)
echo "[+] JDD finished in $(( $(date +%s) - start ))s (exit $rc): $n IOCD(s), $(grep -c . "$OUT/DetectedGadgetChains.txt" 2>/dev/null || echo 0) line(s) in DetectedGadgetChains.txt"
[ "$rc" -eq 124 ] && echo "[!] stopped by JDD_TIMEOUT=${JDD_TIMEOUT:-3600}s; results may be incomplete" >&2
[ -f "$OUT/IfStmtRecord.json" ] || { echo "[-] no IfStmtRecord.json in $OUT, see $W/jdd.log" >&2; exit 1; }
echo "$OUT"
