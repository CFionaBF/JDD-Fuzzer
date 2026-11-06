"""Result of one e2e run (e2e/run_app_e2e.sh): how many of JDD's gadget chains the fuzzer verified and the independent
replay confirmed, and which ones.

usage: python3 -I e2e/e2e_report.py <work_dir> <app>      writes <work_dir>/RESULT.md and <work_dir>/result.json

Reads what the existing tools wrote, with their meanings unchanged:
  <work_dir>/jdd/outputs/gadgets/<app>/*.json   JDD's IOCDs (one per detected gadget chain)
  <work_dir>/fuzz/<app>/per_iocd.csv            analyze_run.py: one row per IOCD; "outcome" is
        verified        the fuzzer generated an exploitable object AND the independent replay of its payload showed the
                        sink effect with the whole IOCD chain on the stack
        unconfirmed     the fuzzer reported it verified, the replay did not reproduce it
        fuzzing-failed / badseed / unfinished   not verified / no initial seed / not run (time limit)
  <work_dir>/fuzz/<app>/replay.csv, chains.csv  the replay verdicts and the distinct chains the fuzzer saw reach a
                                                sink; "verified distinct chains" as in reproduce_summary.py
"""
import csv
import glob
import json
import os
import sys

work, app = sys.argv[1], sys.argv[2]
iocd_dir = os.path.join(work, "jdd", "outputs", "gadgets", app)
fuzz_dir = os.path.join(work, "fuzz", app)


def read_csv(path):
    return list(csv.DictReader(open(path))) if os.path.exists(path) else None


def settings():
    s = {}
    p = os.path.join(work, "settings.txt")
    if os.path.exists(p):
        for line in open(p):
            k, _, v = line.rstrip("\n").partition("=")
            s[k] = v
    return s


iocds = sorted(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(iocd_dir, "*.json"))
               if not os.path.basename(p).startswith("IfStmtRecord"))
chain_of = {}
for h in iocds:
    try:
        chain_of[h] = [g["value"] for g in json.load(open(os.path.join(iocd_dir, h + ".json")))["gadgetCallStack"]]
    except Exception:
        chain_of[h] = []
cut = os.path.join(work, "jdd", "outputs", "analysisTimeouts.txt")
jdd_cut = sum(1 for _ in open(cut)) if os.path.exists(cut) else 0

per = read_csv(os.path.join(fuzz_dir, "per_iocd.csv"))
rep = read_csv(os.path.join(fuzz_dir, "replay.csv"))
chains = read_csv(os.path.join(fuzz_dir, "chains.csv")) or []

res = {"app": app, "settings": settings(), "jdd": {"iocds": len(iocds), "analyses_cut_by_time_budget": jdd_cut}}
if per is None:
    res["fuzz"] = None
else:
    by = lambda *o: sorted(r["iocd"] for r in per if r["outcome"] in o)
    fuzz_verified = by("verified", "unconfirmed")      # the fuzzer's own verdict
    confirmed = by("verified")                         # ... and reproduced by the independent replay
    ok = lambda r: r["effect"] == "ok" and r["reproduced"] == "yes"
    keys = sorted({r["key"] for r in (rep or []) if r["kind"] in ("onchain", "offchain") and ok(r)})
    by_key = {c["key"]: c for c in chains}
    res["fuzz"] = {
        "replay_run": rep is not None,
        "fuzzer_verified": {"count": len(fuzz_verified), "iocds": fuzz_verified},
        "replay_confirmed": {"count": len(confirmed), "iocds": confirmed},
        "unconfirmed": by("unconfirmed"),
        "fuzzing_failed": len(by("fuzzing-failed", "not-verified")),
        "badseed": len(by("badseed")),
        "not_run": len(by("unfinished")),
        "distinct_chains_confirmed": [
            {"key": k, "sink": by_key.get(k, {}).get("sink", ""), "iocds": by_key.get(k, {}).get("iocds", ""),
             "chain": by_key.get(k, {}).get("chain", "")} for k in keys],
    }
json.dump(res, open(os.path.join(work, "result.json"), "w"), indent=2)


def short(sig):
    # "<a.b.C: T m(A)>" -> "C.m"
    s = sig.strip("<>")
    cls, _, rest = s.partition(": ")
    return cls.split(".")[-1] + "." + rest.split(" ")[1].split("(")[0] if " " in rest else s


L = [f"# {app}: JDD -> JDD-Fuzzer", ""]
st = res["settings"]
if st:
    L += [f"JDD {st.get('jdd_commit', '-')}, JDD-Fuzzer {st.get('fuzzer_commit', '-')}; analysed {st.get('analyze', '-')} "
          f"of `{st.get('jars', '-')}`; protocol {st.get('protocol', '-')}, sinks {st.get('sinks', '-')}"
          + (f", JDD settings `{st['jdd_set']}`" if st.get("jdd_set") else "") + f"; fuzzer {st.get('fuzzer', '-')}.", ""]
L += [f"| step | count |", "|---|---:|",
      f"| JDD: detected gadget chains (IOCDs) | {len(iocds)} |"]
f = res["fuzz"]
if f is None:
    L += ["", "The fuzzer has not run (no IOCD, or no per_iocd.csv yet)."]
else:
    L += [f"| JDD-Fuzzer: verified by the fuzzer | {f['fuzzer_verified']['count']} |",
          f"| independent replay: confirmed | {f['replay_confirmed']['count']} |",
          f"| verified by the fuzzer, not confirmed by the replay | {len(f['unconfirmed'])} |",
          f"| fuzzing failed / no seed / not run (time limit) | {f['fuzzing_failed']} / {f['badseed']} / {f['not_run']} |",
          f"| distinct chains confirmed by the replay (any IOCD) | {len(f['distinct_chains_confirmed'])} |"]
    if not f["replay_run"]:
        L += ["", "**The replay did not run**: no IOCD counts as confirmed."]
    L += ["", f"## Confirmed by the replay ({f['replay_confirmed']['count']})", ""]
    L += [f"- `{h}`  {' -> '.join(short(s) for s in chain_of.get(h, []))}" for h in f["replay_confirmed"]["iocds"]] or ["(none)"]
    if f["unconfirmed"]:
        L += ["", f"## Verified by the fuzzer, not confirmed by the replay ({len(f['unconfirmed'])})", ""]
        L += [f"- `{h}`  {' -> '.join(short(s) for s in chain_of.get(h, []))}" for h in f["unconfirmed"]]
    if f["distinct_chains_confirmed"]:
        L += ["", f"## Distinct chains confirmed by the replay ({len(f['distinct_chains_confirmed'])})", "",
              "Every chain a payload ran to the sink and the replay reproduced, whether or not it is the chain of the "
              "IOCD that produced the payload (columns of chains.csv).", ""]
        L += [f"- `{c['key']}` ({c['sink']}; IOCDs {c['iocds']}): {c['chain']}" for c in f["distinct_chains_confirmed"]]
L += ["", f"Details: `fuzz/SUMMARY.md`, `fuzz/{app}/analyze.out`, `fuzz/{app}/per_iocd.csv`, `fuzz/{app}/replay.csv`; "
      f"JDD: `jdd/jdd.log`" + (f" ({jdd_cut} method analyses cut by the time budget, `jdd/outputs/analysisTimeouts.txt`)"
                              if jdd_cut else "") + "."]
text = "\n".join(L) + "\n"
open(os.path.join(work, "RESULT.md"), "w").write(text)
print(text)
