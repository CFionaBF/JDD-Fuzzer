"""Table of a reproduction/reproduce.sh run, with the columns of reproduction/SUMMARY.md.

usage: python3 reproduce_summary.py <out_dir> <app> [app ...]     writes <out_dir>/SUMMARY.md and prints it
Reads <out_dir>/<app>/{per_iocd.csv, replay.csv, chains.csv} (written by analyze_run.py and replay_check.sh).
A chain counts when the independent replay reproduced it; the columns are defined as in reproduction/SUMMARY.md.
"""
import csv
import os
import sys

# verified distinct chains in reproduction/SUMMARY.md (full runs, 3 tries x 60 s)
REFERENCE = {
    "JBossInterceptors": 1, "javassistWeld": 1, "Fileupload": 1, "Wicket": 1, "BeanShell": 3, "JDK": 5, "Myface": 6,
    "click1": 1, "remo": 36, "cc4": 43, "CC3_CommonsBeanutils_AspectJWeaver": 67, "hibernate": 13, "groovy": 29,
    "clojure": 11, "fastjson2-Hessian": 8, "rometool-Hessian": 25, "vaadin": 51, "XBean-Hessian": 20,
    "Resin-Hessian": 0, "Spring-Hessian": 55,
}

out_dir, apps = sys.argv[1], sys.argv[2:]


# "unconfirmed": reported verified by the fuzzer, but the replay of its payload did not reproduce it (see
# analyze_run.py); it is not verified, so it is counted here and named in the note below the table.
# "not-verified" is the old name of "fuzzing-failed" in csv files of older runs.
FAILED = ("fuzzing-failed", "not-verified", "unconfirmed")


def read(path):
    return list(csv.DictReader(open(path))) if os.path.exists(path) else None


rows = []
for app in apps:
    d = os.path.join(out_dir, app)
    per, rep, ch = read(f"{d}/per_iocd.csv"), read(f"{d}/replay.csv"), read(f"{d}/chains.csv")
    if per is None or rep is None:
        rows.append((app, None))
        continue
    ok = lambda r: r["effect"] == "ok"
    confirmed = {r["key"] for r in rep if r["kind"] in ("onchain", "offchain") and ok(r) and r["reproduced"] == "yes"}
    verified = {r["iocd"] for r in per if r["outcome"] == "verified"}
    failed = {r["iocd"] for r in per if r["outcome"] in FAILED}
    deviated = {r["iocd"] for r in rep if r["kind"] == "offchain" and ok(r) and r["reproduced"] == "yes"} & failed
    rows.append((app, dict(
        recorded=len(ch or []), total=len(per), chains=len(confirmed), verified=len(verified), deviated=len(deviated),
        unconfirmed=sum(r["outcome"] == "unconfirmed" for r in per),
        failed=len(failed) - len(deviated), seed=sum(r["outcome"] == "badseed" for r in per),
        not_run=sum(r["outcome"] not in ("verified", "badseed") + FAILED for r in per))))

lines = ["# Reproduction summary", "",
         "| application | detected gadget chains (IOCDs) | verified distinct chains | verified IOCDs | deviated IOCDs | fuzzing failed | seed generation failed | not run (time limit) | reproduction/SUMMARY.md |",
         "|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
keys = ("total", "chains", "verified", "deviated", "failed", "seed", "not_run")
tot = dict.fromkeys(keys, 0)
ref_total = 0
for app, r in rows:
    ref = REFERENCE.get(app, "-")
    if r is None:
        lines.append(f"| {app} | not finished | | | | | | | {ref} |")
        continue
    lines.append(f"| {app} | " + " | ".join(str(r[k]) for k in keys[:6]) + f" | {r['not_run']} | {ref} |")
    for k in keys:
        tot[k] += r[k]
    ref_total += REFERENCE.get(app, 0)
lines.append("| **total** | " + " | ".join(str(tot[k]) for k in keys[:6]) + f" | {tot['not_run']} | {ref_total} |")
lines += ["", "Columns as in reproduction/SUMMARY.md. IOCDs not run because the time limit was reached are in none of the outcome columns.",
          "**verified distinct chains** and **verified IOCDs** count only what the independent replay reproduced."]
# IOCDs the fuzzer reported verified but the replay did not reproduce: counted as fuzzing failed
unc = [(app, r["unconfirmed"]) for app, r in rows if r and r["unconfirmed"]]
if unc:
    lines.append("Reported verified by the fuzzer but not reproduced by the replay, so counted under fuzzing failed: "
                 + ", ".join(f"{app} {n}" for app, n in unc)
                 + f" (total {sum(n for _, n in unc)}); see <app>/analyze.out for which IOCDs.")
# chains the fuzzer recorded but the replay did not confirm (replay failed, or no payload was kept for them)
gap = [(app, r["recorded"] - r["chains"]) for app, r in rows if r and r["recorded"] > r["chains"]]
if gap:
    lines.append("Recorded by the fuzzer but not confirmed by the replay, so not counted above: "
                 + ", ".join(f"{app} {n}" for app, n in gap)
                 + f" (total {sum(n for _, n in gap)}); see <app>/analyze.out for which chains.")
text = "\n".join(lines) + "\n"
open(os.path.join(out_dir, "SUMMARY.md"), "w").write(text)
print(text)
