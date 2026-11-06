"""Check that an application's jar set matches what its IOCDs were generated from.

For every non-JDK class referenced by the IOCDs (<hash>.json, DetectedGadgetChains.txt,
IfStmtRecord.json) report:
  classes : referenced classes found in the jars
  sigs    : Soot signatures <C: ret m(args)> / <C: type f> declared by those classes
  lines   : IfStmtRecord branch lines that fall inside the method's LineNumberTable
Misses are listed with -v. Compiler-generated lambda classes (e.g. Foo$bar__12) never exist in
jars and are ignored.

usage: python3 validate.py <iocd_dir> <jar_dir> [-v] [--javap PATH]
"""
import collections, glob, json, os, re, subprocess, sys

args = [a for a in sys.argv[1:] if not a.startswith("-")]
verbose = "-v" in sys.argv
javap = sys.argv[sys.argv.index("--javap") + 1] if "--javap" in sys.argv else "javap"
if "--javap" in sys.argv:
    args.remove(javap)
iocd_dir, jar_dir = args

JDK = ("java.", "javax.", "sun.", "com.sun.", "jdk.", "org.xml.", "org.w3c.", "org.omg.", "org.ietf.",
       "org.jcp.", "com.oracle.")
LAMBDA = re.compile(r"\$\w+__\d+$|\$\$Lambda\$")
jars = sorted(glob.glob(os.path.join(glob.escape(jar_dir), "*.jar")))
cp = os.pathsep.join(jars)


def in_jars(cls):
    """True if the class file exists in one of the jars (lets com.sun.syndication etc. through)."""
    return cls in jar_classes


jar_classes = set()
for j in jars:
    out = subprocess.run(["unzip", "-Z1", j], capture_output=True, text=True).stdout
    for n in out.splitlines():
        if n.endswith(".class"):
            jar_classes.add(n[:-6].replace("/", "."))


def third_party(cls):
    return not LAMBDA.search(cls) and (not cls.startswith(JDK) or in_jars(cls))


sigs = collections.defaultdict(set)
branches = collections.defaultdict(set)
for f in glob.glob(os.path.join(glob.escape(iocd_dir), "*")):
    txt = open(f, errors="ignore").read().replace("\\u003c", "<").replace("\\u003e", ">")
    for c, member in re.findall(r"<([\w.$]+): ([^<>]+?)>", txt):
        if third_party(c):
            sigs[c].add(member)
    if f.endswith("IfStmtRecord.json"):
        for recs in json.loads(open(f).read())["classIfStmtsMap"].values():
            for r in recs:
                # line -1: no debug info (e.g. generated clojure proxies), nothing to compare
                if third_party(r["className"]) and r["lineNumber"] >= 0:
                    branches[r["className"]].add((r["methodName"], r["lineNumber"]))

classes = sorted(set(sigs) | set(branches))

MODS = {"public", "private", "protected", "static", "final", "abstract", "synchronized", "native",
        "transient", "volatile", "default", "strictfp"}


PRIM = {"Z": "boolean", "B": "byte", "C": "char", "S": "short", "I": "int", "J": "long",
        "F": "float", "D": "double", "V": "void"}


def desc_types(desc):
    """Erased JVM descriptor -> list of Soot type names."""
    out, i = [], 0
    while i < len(desc):
        dims = 0
        while desc[i] == "[":
            dims += 1; i += 1
        if desc[i] == "L":
            j = desc.index(";", i)
            t = desc[i + 1:j].replace("/", "."); i = j + 1
        else:
            t = PRIM[desc[i]]; i += 1
        out.append(t + "[]" * dims)
    return out


def member_name(line, cls):
    """Method/field name from a javap member line (generics stripped)."""
    line = re.sub(r"\s+throws\s+.*$", "", line.strip().rstrip(";"))
    line = re.sub(r"<[^()]*?>", "", line)
    if line == "static {}":
        return "<clinit>"
    name = line.split("(")[0].split()[-1]
    if name in (cls, cls.split(".")[-1]) or cls.endswith("$" + name):
        return "<init>"
    return name


# Soot signatures are built from erased descriptors, so match on `javap -s` descriptors
# rather than the generic source-level types javap prints on the member line.
have, lines = {}, collections.defaultdict(set)
for i in range(0, len(classes), 40):
    out = subprocess.run([javap, "-p", "-l", "-s", "-cp", cp] + classes[i:i + 40],
                         capture_output=True, text=True).stdout
    cur = mtd = None
    for line in out.splitlines():
        m = re.match(r"^(?:[\w ]+ )?(?:class|interface|enum) ([\w.$]+)", line)
        if m and line.rstrip().endswith("{"):
            cur, mtd = m.group(1), None
            have[cur] = set()
            continue
        if cur is None:
            continue
        if line.startswith("  ") and not line.startswith("   "):
            mtd = member_name(line, cur)
            continue
        m = re.match(r"^\s+descriptor: (\S+)", line)
        if m and mtd:
            d = m.group(1)
            if d.startswith("("):
                params, ret = d[1:].split(")")
                have[cur].add(f"{desc_types(ret)[0]} {mtd}({','.join(desc_types(params))})")
            else:
                have[cur].add(f"{desc_types(d)[0]} {mtd}")
            continue
        m = re.match(r"^\s+line (\d+): \d+", line)
        if m and mtd:
            lines[(cur, mtd)].add(int(m.group(1)))

miss_cls = [c for c in classes if c not in have]
s_tot = sum(len(v) for v in sigs.values())
s_miss = [(c, s) for c, ms in sigs.items() for s in ms if c not in have or s not in have[c]]
b_tot = sum(len(v) for v in branches.values())
b_miss = [(c, n, l) for c, rs in branches.items() for n, l in rs if l not in lines.get((c, n), ())]
print(f"classes {len(classes) - len(miss_cls)}/{len(classes)}  "
      f"sigs {s_tot - len(s_miss)}/{s_tot}  lines {b_tot - len(b_miss)}/{b_tot}")
if verbose:
    for c in miss_cls:
        print("   MISS class", c)
    for c, s in sorted(s_miss):
        if c in have:
            print("   MISS sig  ", c, s)
    for c, n, l in sorted(b_miss):
        print("   MISS line ", c, n, l)
