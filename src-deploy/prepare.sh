#!/bin/bash
# Create a fuzzing work dir for one application.
# Usage: src-deploy/prepare.sh <work_dir> <jar_dir> <iocd_dir> [fuzz_time_sec]
#   jar_dir  : flat directory of the application's jars (e.g. EvaluationJars/<app>/)
#   iocd_dir : JDD output dir with <hash>.json IOCDs and IfStmtRecord.json
#              (e.g. JDD/IOCDDatasets/detected/<app>/); set IFSTMT=<file> if
#              IfStmtRecord.json lives elsewhere
# IOCDs are copied into <work_dir>/pool/; fuzz.sh feeds them one at a time.
set -e
[ $# -ge 3 ] || { sed -n 2,8p "$0"; exit 1; }
REPO=$(cd "$(dirname "$0")/.." && pwd)
W=$(mkdir -p "$1" && cd "$1" && pwd); JARS=$2; IOCD=$3; FT=${4:-60}
. "$(dirname "$0")/env.sh"

mkdir -p "$W"/{config,files,logs,tmp,pool,inputs/lib}
cat > "$W/config/config.properties" <<EOF
fuzzTime = $FT
work_dir = $W
projectsInputDir = inputs
gadgetInfos_dir = gadgets
ignoreListPath = config/ignoreClasses
outputDir = outputs
exploitable_dir = exploitable
withAllJdk = false
inputClassPath = lib/
EOF
# the fuzzer reuses an existing lib/tmp/ (extracted classes) instead of re-extracting,
# so start from an empty lib/ to never analyze stale jars
rm -rf "${W:?}/inputs/lib" && mkdir -p "$W/inputs/lib"
if ls "$JARS"/*.jar >/dev/null 2>&1; then
    cp "$JARS"/*.jar "$W/inputs/lib/"
else
    # JDK-only apps: the agent refuses an empty class dir, so add a one-class placeholder jar
    P=$(mktemp -d)
    mkdir -p "$P/jddplaceholder" && echo 'package jddplaceholder; public class Empty {}' > "$P/jddplaceholder/Empty.java"
    "$(dirname "$JAVA")/javac" -d "$P" "$P/jddplaceholder/Empty.java"
    "$(dirname "$JAVA")/jar" cf "$W/inputs/lib/jdd-placeholder.jar" -C "$P" jddplaceholder/Empty.class
    rm -rf "${P:?}"
fi
cp "${IFSTMT:-$IOCD/IfStmtRecord.json}" "$W/files/IfStmtRecord.json"
find -H "$IOCD" -maxdepth 1 -name '*.json' ! -name 'IfStmtRecord*.json' -exec cp {} "$W/pool/" \;
(cd "$W" && "$JAVA" -jar "$REPO/jdd-validator/target/jdd-validator-1.0-SNAPSHOT-jar-with-dependencies.jar" setup-dir >/dev/null)
echo "[+] $W ready: $(ls "$W/inputs/lib" | wc -l) jars, $(ls "$W/pool" | wc -l) IOCDs"
