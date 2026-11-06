# Shared settings of the e2e scripts (sourced by run_jdd.sh and run_app_e2e.sh).
#
# Layout (each path can be overridden from the environment):
#   WORKSPACE   the parent of the JDD-Fuzzer directory      default: the JDD checkout that contains JDD-Fuzzer/
#   JDD_REPO    the JDD checkout (static analysis)          default: $WORKSPACE when it is the JDD checkout,
#                                                           else $WORKSPACE/JDD (two checkouts side by side)
#   DATA_JARS   <DATA_JARS>/<jar set>/*.jar per application default: $WORKSPACE/data/jars
#               (built with src-deploy/evaluation-jars/build.sh, see e2e/README.md)
#   E2E_RUNS    where runs are written                      default: $WORKSPACE/runs/e2e
#
# JDD and JDD-Fuzzer must run on the SAME JDK 8: JDD records the line numbers of the JDK classes' branches into
# the IOCDs / IfStmtRecord.json, and the fuzzer instruments those lines in the JDK it runs on. So the fuzzer is
# pointed at JDK8_HOME as well, and its "not Oracle 8u361" check is expected to fail (JDD_ALLOW_JDK_MISMATCH=1);
# 8u361 only matters for the pre-generated IOCDs in JDD/IOCDDatasets.
E2E_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
FUZZER_REPO=${FUZZER_REPO:-$(cd "$E2E_DIR/.." && pwd)}
WORKSPACE=${WORKSPACE:-$(cd "$FUZZER_REPO/.." && pwd)}
if [ -z "${JDD_REPO:-}" ]; then
    if [ -d "$WORKSPACE/src/jdd" ]; then JDD_REPO=$WORKSPACE; else JDD_REPO=$WORKSPACE/JDD; fi
fi
DATA_JARS=${DATA_JARS:-$WORKSPACE/data/jars}
E2E_RUNS=${E2E_RUNS:-$WORKSPACE/runs/e2e}
JDK8_HOME=${JDK8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}
JDD_JAR=${JDD_JAR:-$JDD_REPO/target/JDD-1.0-SNAPSHOT-jar-with-dependencies.jar}

[ -x "$JDK8_HOME/bin/java" ] && [ -f "$JDK8_HOME/jre/lib/rt.jar" ] \
    || { echo "[-] no JDK 8 at $JDK8_HOME (set JDK8_HOME)" >&2; exit 1; }

export JDD_JAVA_HOME=$JDK8_HOME
export JDD_ALLOW_JDK_MISMATCH=1
