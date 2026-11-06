#!/bin/bash
# Resolve apps.txt (and apps-e2e.txt) into flat jar directories: <out_dir>/<app>/*.jar
# Usage: src-deploy/evaluation-jars/build.sh <out_dir> [app ...]
# A dependency written as <group>:<artifact>:<version>! is taken alone, without its transitive dependencies.
set -e
[ $# -ge 1 ] || { sed -n 2,3p "$0"; exit 1; }
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$(mkdir -p "$1" && cd "$1" && pwd); shift
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

cat "$HERE/apps.txt" "$HERE/apps-e2e.txt" 2>/dev/null | sed 's/#.*//' | while read -r app deps; do
    [ -n "$app" ] || continue
    if [ $# -gt 0 ] && ! printf '%s\n' "$@" | grep -qxF "$app"; then continue; fi
    rm -rf "$OUT/$app"; mkdir -p "$OUT/$app"
    [ -n "$deps" ] || { echo "[+] $app: no jars"; continue; }
    {
        echo '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
        echo '<groupId>eval</groupId><artifactId>app</artifactId><version>1</version><dependencies>'
        for d in $deps; do
            excl=""
            case "$d" in *!) d=${d%!}; excl='<exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>';; esac
            IFS=: read -r g a v <<< "$d"
            echo "<dependency><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version>$excl</dependency>"
        done
        echo '</dependencies><repositories>'
        # extra repositories declared by ysoserial's pom (e.g. javax.interceptor-api:3.1)
        echo '<repository><id>ysoserial-m2-repo</id><url>https://raw.githubusercontent.com/frohoff/ysoserial-m2-repo/master</url></repository>'
        echo '<repository><id>jenkins</id><url>https://repo.jenkins-ci.org/public/</url></repository>'
        echo '</repositories></project>'
    } > "$WORK/pom.xml"
    mvn -B -q -f "$WORK/pom.xml" dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$OUT/$app"
    mvn -B -q -f "$WORK/pom.xml" dependency:tree -DoutputFile="$OUT/$app.tree.txt" >/dev/null
    echo "[+] $app: $(ls "$OUT/$app" | wc -l) jars"
done
