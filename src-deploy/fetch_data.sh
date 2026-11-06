#!/bin/bash
# Fetch what the evaluation runs on, into <data_dir> (default <repo>/reproduction/data):
#   <data_dir>/iocd/<app>/   the app's IOCDs + IfStmtRecord*.json: the dataset of github.com/fdu-sec/JDD
#                            (IOCDDatasets/detected/<app>), pinned to the commit the results were produced with
#   <data_dir>/jars/<app>/   the app's jars, resolved by Maven from evaluation-jars/apps.txt
# Usage: src-deploy/fetch_data.sh [data_dir] [app ...]      (no app: all 20; needs git, mvn and network access)
# The Spring app's directory name contains [ ] + , which break shell globs: it is also available as
# "Spring-Hessian" (a symlink), the name reproduction/reproduce.sh uses.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
D=$(mkdir -p "${1:-$HERE/../reproduction/data}" && cd "${1:-$HERE/../reproduction/data}" && pwd); shift || true
JDD_REPO=${JDD_REPO:-https://github.com/fdu-sec/JDD.git}
JDD_COMMIT=${JDD_COMMIT:-5fbda8110dbc09fd7e36020923531f9a1cc3ae59}
SPRING='Spring-Hessian[SpringAbstractBeanFactoryPointcutAdvisor1+SpringPartiallyComparableAdvisorHolder1]'

full_name() { [ "$1" = Spring-Hessian ] && echo "$SPRING" || echo "$1"; }
if [ $# -eq 0 ]; then
    mapfile -t APPS < <(sed 's/#.*//' "$HERE/evaluation-jars/apps.txt" | awk 'NF{print $1}' | sed "s/^Spring-Hessian\[.*/Spring-Hessian/")
else
    APPS=("$@")
fi

# --- IOCDs: a sparse, single-commit checkout of the wanted app directories only
if [ ! -d "$D/jdd-dataset/.git" ]; then
    git init -q "$D/jdd-dataset"
    git -C "$D/jdd-dataset" remote add origin "$JDD_REPO"
fi
cd "$D/jdd-dataset"
git sparse-checkout init --no-cone
{ for a in "${APPS[@]}"; do n=$(full_name "$a"); printf '/IOCDDatasets/detected/%s/\n' "$(printf %s "$n" | sed 's/[][*?\\]/\\&/g')"; done; } | git sparse-checkout add --stdin
[ "$(git rev-parse -q --verify HEAD 2>/dev/null)" = "$JDD_COMMIT" ] || {
    git fetch -q --depth 1 origin "$JDD_COMMIT"
    git checkout -q -f "$JDD_COMMIT"
}
mkdir -p "$D/iocd"
for a in "${APPS[@]}"; do
    n=$(full_name "$a")
    [ -d "$D/jdd-dataset/IOCDDatasets/detected/$n" ] || { echo "[-] no IOCD directory for $a in $JDD_REPO@$JDD_COMMIT" >&2; exit 1; }
    ln -sfn "$D/jdd-dataset/IOCDDatasets/detected/$n" "$D/iocd/$a"
done
echo "[+] IOCDs: $D/iocd ($(for a in "${APPS[@]}"; do ls "$D/iocd/$a"/*.json | wc -l; done | awk '{s+=$1} END{print s}') files for ${#APPS[@]} apps)"

# --- jars
need=()
for a in "${APPS[@]}"; do
    n=$(full_name "$a")
    [ -d "$D/jars-full/$n" ] || need+=("$n")
done
if [ ${#need[@]} -gt 0 ]; then
    "$HERE/evaluation-jars/build.sh" "$D/jars-full" "${need[@]}"
fi
mkdir -p "$D/jars"
for a in "${APPS[@]}"; do ln -sfn "$D/jars-full/$(full_name "$a")" "$D/jars/$a"; done
echo "[+] jars: $D/jars"
