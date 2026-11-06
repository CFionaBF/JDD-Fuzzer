"""Summarize a run_app.sh run: per-IOCD outcome and how far along the gadget chain it got.

usage: python3 analyze_run.py <run_dir> <iocd_dir> [--csv out.csv] [--chains chains.csv]

Outcomes: verified (exploitable object generated, sink reached along the IOCD chain, and the independent
replay of its payload shows the sink effect with the whole IOCD chain on the stack), unconfirmed (the fuzzer
reported it verified but the replay did not: not counted as verified), fuzzing-failed
(fuzzed, but no success within the tries), badseed (initial seed could not be built). Fuzzing-failed IOCDs whose
payload did reach the sink, but through another route than the IOCD chain, are reported apart
(off-chain). The other fuzzing-failed IOCDs are grouped by the chain link they stop at: the furthest
gadget method reached ("Reached gadget method #N" in the log) and the next one, which was never
reached, and within that by the exception the chain's own code threw where it stopped ("blocked_by",
empty when it threw nothing): a chain can stop because the seed does not match what the next method
expects, or because that method needs something of the run-time environment that the seed, being an
object graph, does not establish (a thread-local, an entered context). The two look the same in the
outcome and are told apart by this exception. Badseed IOCDs are grouped by the root-cause exception.

Triggered chains: every chain a payload really ran to the sink (outputs/triggered_chains.jsonl of
each work dir, recorded by the fuzzer, IOCD chain or not), counted once each across IOCDs, tries
and shards, and compared with the gadget chains of the IOCDs in <iocd_dir>.

distinct_chains/<key>.txt (in <run_dir>): one file per replay-confirmed distinct chain, named by its key in
chains.csv. After header lines starting with "#" (key, sink, length, IOCDs, paths of the payloads that ran the
chain, relative to <run_dir>) it lists the chain as soot method signatures, one per line, entry first. A signature
is read with javap from the application's jars (<shard>/inputs/lib) and the JDK of the run; overloaded methods are
told apart by the source line of the frame in the replay's stack. Frames of classes generated at run time (dynamic
proxies, ASM writers) have no bytecode to read; they are written as <class: ? method(?)>.
"""
import collections
import csv
import glob
import json
import os
import re
import shutil
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
run_dir, iocd_dir = sys.argv[1], sys.argv[2]
csv_out = sys.argv[sys.argv.index("--csv") + 1] if "--csv" in sys.argv else None
chains_out = sys.argv[sys.argv.index("--chains") + 1] if "--chains" in sys.argv else None
ANSI = re.compile(r"\x1b\[[0-9;]*m")


def is_soot_lambda(sig):
    """soot's class for a lambda ("<owner>$lambda_<name>_<n>__<id>"), listed in IOCD chains before the lambda
    body; it does not exist at run time, so it is never reached nor on a triggered chain."""
    return re.search(r"\$lambda_[^:]*__\d+:", sig) is not None


def short(sig):
    m = re.match(r"<([\w.$]+): \S+ ([\w$<>]+)\(", sig or "")
    return f"{m.group(1).split('.')[-1]}.{m.group(2)}" if m else str(sig)


def short_frame(f):
    cls, _, m = f.rpartition(".")
    return f"{cls.split('.')[-1]}.{m}"


def outcome_of(h):
    for kind, pat in (("verified", "inputs/finished/success"), ("badseed", "inputs/finished/badseed"),
                      ("fuzzing-failed", "tmp")):
        if glob.glob(os.path.join(run_dir, "shard-*", pat, h + ".json")):
            return kind
    return "unfinished"


def log_of(h):
    logs = glob.glob(os.path.join(run_dir, "shard-*", "logs", h + ".log"))
    return ANSI.sub("", open(logs[0], errors="ignore").read()) if logs else ""


def badseed_reason(log):
    lines = log.splitlines()
    for i, line in enumerate(lines):
        if "Invalid iocd and seed" not in line:
            continue
        for j in range(i - 1, max(0, i - 80), -1):
            if re.match(r"^(Caused by: )?[\w.$]+(Exception|Error)\b", lines[j]):
                frame = lines[j + 1].strip() if j + 1 < len(lines) else ""
                cause = re.sub(r"^Caused by: ", "", lines[j])
                cause = re.sub(r"<[^<>]*>", "<..>", cause)[:150]
                at = re.sub(r"\(.*", "", frame[3:]) if frame.startswith("at ") else ""
                return cause + (f"  @{at}" if at else "")
        nxt = lines[i + 1] if i + 1 < len(lines) else "?"
        return re.sub(r"^\[\w+\]-[\d-]+-", "", nxt)[:150]
    return "?"


ONGOING = re.compile(r"([\w.$]+(?:Exception|Error)) is ongoing, but not handled yet\."
                     r" the class-linenum info <([^,>]+),(\d+)>")
FRAME_TAIL = re.compile(r"\s*[\w.$]+\([\w.$]+\.java:\d+\)\s*$")


def blocked_by(log):
    """The exception the chain's own code threw where it stopped, from the fuzzer's last try, or ""."""
    lines = log.splitlines()
    found = ""
    for i, line in enumerate(lines):
        m = ONGOING.search(line)
        if not m:
            continue
        msg = ""
        for j in range(i + 1, min(len(lines), i + 4)):
            if "exceptions details" in lines[j]:
                if j + 1 < len(lines):
                    msg = FRAME_TAIL.sub("", lines[j + 1].strip())
                break
        where = m.group(2).rsplit(".", 1)[-1] + ":" + m.group(3)
        found = m.group(1).rsplit(".", 1)[-1] + (": " + msg[:110] if msg else "") + "  @" + where
    return found


# The independent replay (replay_check.sh runs before this script and writes replay.csv). Chains and verified
# IOCDs only count when it reproduced them; without replay.csv the fuzzer's own verdict is all there is.
replay_path = os.path.join(run_dir, "replay.csv")
has_replay = os.path.exists(replay_path)
replay_all = list(csv.DictReader(open(replay_path))) if has_replay else []
verified_replay = {r["iocd"]: r for r in replay_all if r["kind"] == "verified"}


def replay_verdict(h):
    """"ok" if the replay of the exploitable payload of IOCD h shows the sink effect with the whole IOCD chain on
    the stack (the criterion replay_check.sh prints as "effect ok and the whole IOCD chain on the stack"),
    else why not."""
    r = verified_replay.get(h)
    if r is None:
        return "no payload was replayed"
    a, _, b = r["chain"].partition("/")
    if r["effect"] == "ok" and a == b and a != "":
        return "ok"
    return f"effect={r['effect']}, chain {r['chain'] or '-'}" + (f", first missing {r['miss']}" if r["miss"] else "")


rows = []
for path in sorted(glob.glob(os.path.join(iocd_dir, "*.json"))):
    h = os.path.basename(path)[:-5]
    if h.startswith("IfStmtRecord"):   # also IfStmtRecord_DynamicProxy.json (groovy): an instrumentation record, not an IOCD
        continue
    iocd = json.load(open(path))
    chain = [g["value"] for g in iocd["gadgetCallStack"]]
    coll = (iocd.get("hashCollisionRecord") or {}).get("type", "-") if iocd.get("hashCollisionFlag") else "-"
    log = log_of(h)
    reached = [int(x) for x in re.findall(r"Reached gadget method #(\d+)", log)]
    mx = max(reached) if reached else -1
    # the method after the furthest reached one; soot's lambda classes are never reached (is_soot_lambda)
    nxt = next((i for i in range(mx + 1, len(chain)) if not is_soot_lambda(chain[i])), len(chain))
    out = outcome_of(h)
    replay = ""
    if out == "verified" and has_replay:
        replay = replay_verdict(h)
        if replay != "ok":
            out = "unconfirmed"      # the fuzzer's verdict that the replay did not reproduce
    rows.append({
        "iocd": h, "outcome": out, "replay": replay, "sink": iocd.get("sinkType"), "collision": coll,
        "chain_len": len(chain), "max_reached": mx,
        "stuck_at": short(chain[mx]) if 0 <= mx < len(chain) else "(not entered)",
        "next_unreached": short(chain[nxt]) if nxt < len(chain) else "(sink)",
        "badseed_reason": badseed_reason(log) if out == "badseed" else "",
        "blocked_by": blocked_by(log) if out == "fuzzing-failed" else "",
        "offchain": (lambda m: m[-1] if m else "")(re.findall(r"off the IOCD chain \(stack matches ([^)]*)\)", log)),
        "tries": len(re.findall(r"Load seed " + h + " success", log)),
    })

cnt = collections.Counter(r["outcome"] for r in rows)
total = len(rows)
print(f"IOCDs: {total}")
for k in ("verified", "unconfirmed", "fuzzing-failed", "badseed", "unfinished"):
    if cnt[k]:
        # "unfinished": the IOCD was never fuzzed (time cap reached) or its run was interrupted
        label = "not run" if k == "unfinished" else k
        print(f"  {label:14s} {cnt[k]:4d}  ({100.0 * cnt[k] / total:.1f}%)")
if not has_replay:
    print("  (replay was not run: verified is the fuzzer's own verdict)")

uc = [r for r in rows if r["outcome"] == "unconfirmed"]
if uc:
    print(f"\nUnconfirmed ({len(uc)}): reported verified by the fuzzer, but the independent replay of the payload did not show "
          "the sink effect with the whole IOCD chain on the stack, so they are NOT counted as verified:")
    for r in uc:
        print(f"  {r['iocd']}  {r['replay']}")

print("\nBy collision type (verified / total):")
for ct in sorted({r['collision'] for r in rows}, key=str):
    sub = [r for r in rows if r["collision"] == ct]
    print(f"  type {ct!s:3s} {sum(r['outcome'] == 'verified' for r in sub):4d} / {len(sub)}")

oc = [r for r in rows if r["outcome"] == "fuzzing-failed" and r["offchain"]]
print(f"\nFuzzing failed, but the sink was reached off the IOCD chain ({len(oc)}), by the first gadget method missing on the stack:")
for miss, n in collections.Counter(re.sub(r"^\d+/\d+, first missing ", "", r["offchain"]) for r in oc).most_common():
    ex = [r["iocd"] for r in oc if r["offchain"].endswith(miss)][:3]
    print(f"  {n:4d}  {short(miss)}      e.g. {' '.join(ex)}")

nv = [r for r in rows if r["outcome"] == "fuzzing-failed" and not r["offchain"]]
print(f"\nFuzzing failed, sink not reached ({len(nv)}), by the link where the chain stops (last reached -> never reached):")
groups = collections.Counter((r["stuck_at"], r["next_unreached"]) for r in nv)
for (a, b), n in groups.most_common():
    sub = [r for r in nv if (r["stuck_at"], r["next_unreached"]) == (a, b)]
    print(f"  {n:4d}  {a}  ->  {b}      e.g. {' '.join(r['iocd'] for r in sub[:3])}")
    for why, m in collections.Counter(r["blocked_by"] for r in sub).most_common():
        if why:
            print(f"        {m:4d}  stopped by {why}")
blocked = [r for r in nv if r["blocked_by"]]
if blocked:
    print(f"\n  of those, {len(blocked)} stopped on an exception thrown by the chain's own code"
          f" (not on the seed not matching); by exception:")
    for why, n in collections.Counter(r["blocked_by"] for r in blocked).most_common():
        ex = [r["iocd"] for r in blocked if r["blocked_by"] == why][:3]
        print(f"  {n:4d}  {why}      e.g. {' '.join(ex)}")

bs = [r for r in rows if r["outcome"] == "badseed"]
print(f"\nBadseed ({len(bs)}), by root cause:")
for reason, n in collections.Counter(r["badseed_reason"] for r in bs).most_common():
    ex = [r["iocd"] for r in bs if r["badseed_reason"] == reason][:3]
    print(f"  {n:4d}  {reason}\n        e.g. {' '.join(ex)}")

# ---- distinct chains that really reached the sink
recs = []
for jl in sorted(glob.glob(os.path.join(run_dir, "shard-*", "outputs", "triggered_chains.jsonl"))
                 + glob.glob(os.path.join(run_dir, "outputs", "triggered_chains.jsonl"))):
    recs += [json.loads(line) for line in open(jl) if line.strip()]
iocd_chains = collections.defaultdict(list)    # "class.method" sequence -> IOCDs with that chain
sig_by_frame = collections.defaultdict(set)    # "class.method" -> soot signatures the IOCDs of this app use for it
for path in glob.glob(os.path.join(iocd_dir, "*.json")):
    if os.path.basename(path).startswith("IfStmtRecord"):
        continue
    sigs = [g["value"].replace("'", "") for g in json.load(open(path))["gadgetCallStack"]
            if not is_soot_lambda(g["value"])]
    seq = tuple(re.sub(r"^<([^:]+): \S+ ([^(]+)\(.*$", r"\1.\2", s) for s in sigs)
    iocd_chains[seq].append(os.path.basename(path)[:-5])
    for s, k in zip(sigs, seq):
        sig_by_frame[k].add(s)
chains = {}
for r in recs:
    c = chains.setdefault(r["key"], {"key": r["key"], "sink": r["sinkType"], "chain": r["chain"],
                                     "iocds": set(), "onchain_iocds": set()})
    c["iocds"].add(str(r["iocd"]))
    if r["onIocdChain"]:
        c["onchain_iocds"].add(str(r["iocd"]))
for c in chains.values():
    c["equals_iocds"] = iocd_chains.get(tuple(c["chain"]), [])
# Cross-check with the independent replay (loaded above).
# A chain only counts as available when the replay reproduced it. A chain the fuzzer recorded without a
# payload cannot be replayed at all, so it is reported apart instead of being counted silently.
confirmed_keys, replayed_keys = set(), set()
if has_replay:
    replay_rows = [r for r in replay_all if r["kind"] in ("onchain", "offchain")]
    replayed_keys = {r["key"] for r in replay_rows}
    confirmed_keys = {r["key"] for r in replay_rows if r["effect"] == "ok" and r["reproduced"] == "yes"}
confirmed = confirmed_keys & set(chains)
not_reproduced = (replayed_keys & set(chains)) - confirmed_keys
not_replayed = set(chains) - replayed_keys

print(f"\nTriggered chains (each counted once): {len(chains)}, from {len({r['iocd'] for r in recs})} IOCDs"
      + (f"; replay-confirmed: {len(confirmed)}" if has_replay else "; replay was not run"))
if has_replay and chains:
    print(f"  {len(confirmed):4d}  confirmed: the independent replay reproduced the chain")
    if not_reproduced:
        print(f"  {len(not_reproduced):4d}  replayed, but the effect did not happen along the chain")
    if not_replayed:
        print(f"  {len(not_replayed):4d}  NOT replayed, the fuzzer recorded no payload for them: UNVERIFIED")
        for k in sorted(not_replayed):
            print(f"        {k}  ({len(chains[k]['iocds'])} IOCD(s))")
if chains:
    on = [c for c in chains.values() if c["onchain_iocds"]]
    print(f"  {len(on):4d}  ran as the chain of the IOCD that produced the payload")
    print(f"  {len(chains) - len(on):4d}  only off the IOCD chain")
    eq = [c for c in chains.values() if c["equals_iocds"]]
    print(f"  {len(eq):4d}  identical (class.method sequence) to the chain of some IOCD of this app")
    for s, n in sorted(collections.Counter(c["sink"] for c in chains.values()).items()):
        print(f"  {n:4d}  {s} sink")
    print("  off-chain only:")
    for c in sorted(chains.values(), key=lambda c: (-len(c["iocds"]), c["key"])):
        if not c["onchain_iocds"]:
            src = " -> ".join(short_frame(f) for f in c["chain"][:4])
            print(f"    {c['key']}  {len(c['iocds']):3d} IOCD(s), {len(c['chain'])} methods: {src} ...")
if chains_out and chains:
    with open(chains_out, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["key", "sink", "length", "n_iocds", "iocds", "onchain_iocds", "equals_iocds", "chain"])
        for c in sorted(chains.values(), key=lambda c: (-len(c["iocds"]), c["key"])):
            w.writerow([c["key"], c["sink"], len(c["chain"]), len(c["iocds"]), " ".join(sorted(c["iocds"])),
                        " ".join(sorted(c["onchain_iocds"])), " ".join(sorted(c["equals_iocds"])), " -> ".join(c["chain"])])
    print(f"distinct chains table: {chains_out}")


# ---- distinct_chains/<key>.txt: one file per replay-confirmed distinct chain, the chain as soot method signatures
PRIM = {"B": "byte", "C": "char", "D": "double", "F": "float", "I": "int", "J": "long", "S": "short", "Z": "boolean",
        "V": "void"}


def desc_types(d):
    """JVM descriptor fragment -> list of source-style type names ("[Ljava/lang/String;I" -> java.lang.String[], int)"""
    out, i = [], 0
    while i < len(d):
        dims = 0
        while d[i] == "[":
            dims += 1
            i += 1
        if d[i] == "L":
            j = d.index(";", i)
            t, i = d[i + 1:j].replace("/", "."), j + 1
        else:
            t, i = PRIM[d[i]], i + 1
        out.append(t + "[]" * dims)
    return out


def soot_sig(cls, name, desc):
    params, ret = desc[1:].split(")")
    return f"<{cls}: {desc_types(ret)[0]} {name}({','.join(desc_types(params))})>"


def find_javap():
    home = os.environ.get("JDD_JAVA_HOME")
    for c in ([os.path.join(home, "bin", "javap")] if home else []) + \
             [os.path.join(REPO, "jdk-for-jdd", "jdk1.8.0_361", "bin", "javap")]:
        if os.path.isfile(c):
            return c
    return shutil.which("javap")


def javap_index(classes, classpath):
    """{class: {method name: [(soot signature, set of source lines)]}} of the given classes, read with javap from the
    application's jars and the JDK of the run (the signatures of overloaded methods are told apart by line)."""
    idx, javap = {}, find_javap()
    if not javap:
        return idx
    classes = sorted(classes)
    for b in range(0, len(classes), 40):
        out = subprocess.run([javap, "-p", "-s", "-l", "-cp", classpath] + classes[b:b + 40],
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, universal_newlines=True,
                             errors="replace").stdout.split("\n")
        cur, k = None, 0
        while k < len(out):
            line = out[k]
            m = None if line.startswith(" ") else re.match(r"^(?:[\w@]+ )*(?:class|interface|enum) ([\w.$]+)", line)
            if m:
                cur = m.group(1)
                idx.setdefault(cur, {})
                k += 1
            elif cur and line.startswith("  ") and not line.startswith("   ") and "(" in line \
                    and line.rstrip().endswith(";"):
                head = line[:line.index("(")].split()[-1]
                name = "<init>" if head == cur else head
                desc, lns = None, set()
                k += 1
                while k < len(out) and out[k].startswith("   "):
                    t = out[k].strip()
                    if t.startswith("descriptor:"):
                        desc = t.split(None, 1)[1]
                    elif re.match(r"line \d+:", t):
                        lns.add(int(t.split()[1].rstrip(":")))
                    k += 1
                if desc:
                    idx[cur].setdefault(name, []).append((soot_sig(cur, name, desc), lns))
            else:
                k += 1
    return idx


STACK_FRAME = re.compile(r"^\s+at ([\w.$<>]+)\.([\w$<>]+)\(([^)]*)\)\s*$")


def replay_stack(key):
    """frames (class, method, line) of the replay of a payload of this chain, innermost first, class names normalized
    like the chain frames ($Proxy12 -> $Proxy)"""
    for f in sorted(glob.glob(os.path.join(run_dir, "replay", "*-" + key + ".txt"))):
        frames = []
        for line in open(f, errors="ignore"):
            m = STACK_FRAME.match(line)
            if m:
                ln = m.group(3).rsplit(":", 1)[-1]
                cls = re.sub(r"\$\$Lambda\$.*", lambda _: "$$Lambda", re.sub(r"\$Proxy\d+", "$Proxy", m.group(1)))
                frames.append((cls, m.group(2), int(ln) if ln.lstrip("-").isdigit() else -1))
        if frames:
            return frames
    return []


def frame_lines(chain, stack):
    """the source line of every chain frame (entry first) in the replay stack: the chain is an ordered subset of it"""
    res, pos = [None] * len(chain), 0
    for i in range(len(chain) - 1, -1, -1):
        cls, _, meth = chain[i].rpartition(".")
        for j in range(pos, len(stack)):
            if stack[j][0] == cls and stack[j][1] == meth:
                res[i], pos = stack[j][2], j + 1
                break
    return res


def resolve_sig(cls, meth, ln, idx):
    cands = idx.get(cls, {}).get(meth, [])
    known = sig_by_frame.get(cls + "." + meth, set())
    if len(cands) == 1:
        return cands[0][0]
    if len(cands) > 1:
        if ln is not None and ln > 0:
            hit = [s for s, lns in cands if ln in lns]
            if len(hit) == 1:
                return hit[0]
            near = [s for s, lns in cands if lns and min(lns) <= ln <= max(lns)]
            if len(near) == 1:
                return near[0]
        both = [s for s, _ in cands if s in known]
        return both[0] if len(both) == 1 else None
    return next(iter(known)) if len(known) == 1 else None      # generated classes ($Proxy, lambdas): the IOCDs' own


def write_distinct_chains():
    out_dir = os.path.join(run_dir, "distinct_chains")
    shutil.rmtree(out_dir, ignore_errors=True)
    if not has_replay:
        print("\ndistinct_chains/ not written: replay was not run, so no chain is confirmed")
        return
    if not confirmed:
        return
    os.makedirs(out_dir)
    libs = sorted(glob.glob(os.path.join(run_dir, "shard-*", "inputs", "lib", "*.jar")))
    libs = [j for j in libs if os.path.dirname(j) == os.path.dirname(libs[0])] if libs else []
    idx = javap_index({fr.rpartition(".")[0] for k in confirmed for fr in chains[k]["chain"]},
                      os.pathsep.join(libs) or ".")
    unresolved = []
    for k in sorted(confirmed):
        c = chains[k]
        sigs = []
        for fr, ln in zip(c["chain"], frame_lines(c["chain"], replay_stack(k))):
            cls, _, meth = fr.rpartition(".")
            s = resolve_sig(cls, meth, ln, idx)
            if s is None:
                s = f"<{cls}: ? {meth}(?)>"
                unresolved.append(f"{k} {fr}")
            sigs.append(s)
        payloads = sorted(os.path.relpath(p, run_dir) for p in
                          glob.glob(os.path.join(run_dir, "shard-*", "outputs", "chains", "*-" + k + "exp")))
        with open(os.path.join(out_dir, k + ".txt"), "w") as fh:
            fh.write(f"# key: {k}\n# sink: {c['sink']}\n# length: {len(sigs)}\n# IOCDs: {' '.join(sorted(c['iocds']))}\n")
            fh.writelines(f"# payload: {p}\n" for p in payloads)
            fh.write("\n".join(sigs) + "\n")
    print(f"\nreplay-confirmed distinct chains, one file each (soot method signatures, one per line): {out_dir}"
          f"\n  {len(confirmed)} files; {len(unresolved)} frame(s) without a resolvable signature"
          + ("" if not unresolved else " (written as <class: ? method(?)>): " + "; ".join(unresolved[:5])))


write_distinct_chains()

if csv_out:
    with open(csv_out, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print(f"\nper-IOCD table: {csv_out}")
