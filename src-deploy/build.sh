#!/bin/bash
# Build jdd-fuzzer from source with JDK 8.
# Usage: src-deploy/build.sh
set -e
REPO=$(cd "$(dirname "$0")/.." && pwd)
# Compiling only needs some JDK 8 (bytecode is target 8); the IOCD-matching JDK is a runtime
# requirement enforced by env.sh in prepare.sh/fuzz.sh.
export JAVA_HOME=${JDD_BUILD_JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}
export PATH=$JAVA_HOME/bin:$PATH

# jdd-validator depends on the customized junit-quickcheck 1.1-SNAPSHOT;
# install it into the local maven repo if missing (QC_REBUILD=1 forces a rebuild).
# QUICKCHECK_VERSION=1.0 builds against upstream junit-quickcheck 1.0 from Maven Central instead
# (for when the customized fork cannot be fetched).
QUICKCHECK_VERSION=${QUICKCHECK_VERSION:-1.1-SNAPSHOT}
if [ "$QUICKCHECK_VERSION" = 1.1-SNAPSHOT ] && { [ -n "$QC_REBUILD" ] || [ ! -f ~/.m2/repository/com/pholser/junit-quickcheck-core/1.1-SNAPSHOT/junit-quickcheck-core-1.1-SNAPSHOT.jar ]; }; then
    QC=${QC_DIR:-$REPO/../quick-check-for-jdd}
    [ -d "$QC" ] || git clone -q https://github.com/CFionaBF/quick-check-for-jdd.git "$QC"
    (cd "$QC" && mvn -q -B clean install -DskipTests -Dmaven.javadoc.skip=true -Dgpg.skip=true)
fi

cd "$REPO"
mvn -B -q clean install -Dmaven.test.skip=true -Dquickcheck.version="$QUICKCHECK_VERSION"
echo "[+] built: $REPO/jdd-validator/target/jdd-validator-1.0-SNAPSHOT-jar-with-dependencies.jar"
