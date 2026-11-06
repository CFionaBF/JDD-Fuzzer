# Shared settings for src-deploy scripts (sourced).
#
# Runtime JDK must be the one the IOCDs were generated with: Oracle JDK 8u361
# (BUILD_TYPE="commercial"). The fuzzer instruments JDK classes by line number, and the
# IOCDs' JDK-class branch lines match Oracle 8u361's sources but no OpenJDK 8 build
# (8u212-8u504 checked: <=93.5%). Expected location is the repo's own
# jdk-for-jdd/jdk1.8.0_361 (populated by setup_jdk.sh from the Git LFS tarball).
# JDD_JAVA_HOME overrides the location; JDD_ALLOW_JDK_MISMATCH=1 allows another JDK
# (results are then not comparable with the IOCDs).
SRC_DEPLOY=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
JDD_JAVA_HOME=${JDD_JAVA_HOME:-$SRC_DEPLOY/../jdk-for-jdd/jdk1.8.0_361}
if [ ! -x "$JDD_JAVA_HOME/bin/java" ] || [ ! -f "$JDD_JAVA_HOME/jre/lib/rt.jar" ]; then
    echo "[-] no usable JDK at $JDD_JAVA_HOME; run src-deploy/setup_jdk.sh or set JDD_JAVA_HOME" >&2
    exit 1
fi
JDD_JAVA_HOME=$(cd "$JDD_JAVA_HOME" && pwd)
if ! grep -q 'JAVA_VERSION="1.8.0_361"' "$JDD_JAVA_HOME/release" 2>/dev/null \
   || ! grep -q 'BUILD_TYPE="commercial"' "$JDD_JAVA_HOME/release" 2>/dev/null; then
    if [ "$JDD_ALLOW_JDK_MISMATCH" = 1 ]; then
        echo "[!] $JDD_JAVA_HOME is not Oracle JDK 8u361; JDK-class line numbers will not match the IOCDs" >&2
    else
        echo "[-] $JDD_JAVA_HOME is not Oracle JDK 8u361 (the IOCDs' JDK); set JDD_ALLOW_JDK_MISMATCH=1 to run anyway" >&2
        exit 1
    fi
fi
export JAVA_HOME=$JDD_JAVA_HOME PATH=$JDD_JAVA_HOME/bin:$PATH
JAVA=$JDD_JAVA_HOME/bin/java
