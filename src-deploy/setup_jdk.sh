#!/bin/bash
# Install Oracle JDK 8u361 (the IOCDs' JDK, see env.sh) into <repo>/jdk-for-jdd/jdk1.8.0_361.
# The tarball is not part of this repository (Oracle's license does not allow redistributing it): download
# jdk-8u361-linux-x64.tar.gz from Oracle (Java Archive, login required) and pass it in:
#   src-deploy/setup_jdk.sh /path/to/jdk-8u361-linux-x64.tar.gz      (or JDD_JDK_TARBALL=/path/to/...)
# Nothing is done when the JDK is already installed (or JDD_JAVA_HOME points to a usable one, see env.sh).
set -e
REPO=$(cd "$(dirname "$0")/.." && pwd)
DEST=$REPO/jdk-for-jdd
TGZ=${1:-$JDD_JDK_TARBALL}
SHA=abee6d353bcfeb8868cca7d8d7e2c6fb35ddc6469c13b05621a30c02d2c47b6a  # sha256 of jdk-8u361-linux-x64.tar.gz

[ -x "$DEST/jdk1.8.0_361/bin/java" ] && { echo "[+] already installed: $DEST/jdk1.8.0_361"; exit 0; }
if [ -z "$TGZ" ] || [ ! -f "$TGZ" ]; then
    echo "[-] Oracle JDK 8u361 not found at $DEST/jdk1.8.0_361." >&2
    echo "    Provide the tarball: src-deploy/setup_jdk.sh /path/to/jdk-8u361-linux-x64.tar.gz" >&2
    echo "    (or set JDD_JAVA_HOME to an installed Oracle JDK 8u361, JDD_ALLOW_JDK_MISMATCH=1 for another JDK:" >&2
    echo "     results are then not comparable with the IOCDs, see env.sh)" >&2
    exit 1
fi
echo "$SHA  $TGZ" | sha256sum -c --quiet || echo "[!] $TGZ differs from the tarball the results were produced with" >&2
mkdir -p "$DEST"
tar xzf "$TGZ" -C "$DEST"
grep -E 'JAVA_VERSION|BUILD_TYPE' "$DEST/jdk1.8.0_361/release"
echo "[+] installed: $DEST/jdk1.8.0_361"
