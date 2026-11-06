"""Collect the verified IOCDs of a finished run into GroundTruth/<app>/<iocd_id>/<iocd_id>.json, copied from the
dataset directory. The payloads the fuzzer saved are not kept.

usage: python3 collect_ground_truth.py <run_dir> [--iocd-root DIR] [--out GroundTruth]
<run_dir> is the result directory of reproduction/reproduce.sh (or the work_dir of run_single_app.sh): <run_dir>/<app>/
per_iocd.csv lists the outcome of every IOCD; those with outcome "verified" are collected. --iocd-root holds the IOCDs,
<iocd-root>/<app>/<iocd_id>.json (default reproduction/data/iocd, made by src-deploy/fetch_data.sh). Run from the repo root.
"""
import argparse
import csv
import os
import shutil
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_dir")
    ap.add_argument("--iocd-root", default="reproduction/data/iocd")
    ap.add_argument("--out", default="GroundTruth")
    args = ap.parse_args()

    total_v = total_copied = total_missing = 0
    for app in sorted(os.listdir(args.run_dir)):
        per_iocd = os.path.join(args.run_dir, app, "per_iocd.csv")
        if not os.path.isfile(per_iocd):
            if os.path.isdir(os.path.join(args.run_dir, app, "shard-0")):
                print(f"[-] {app}: no per_iocd.csv (run not finished)", file=sys.stderr)
            continue
        verified = [r["iocd"] for r in csv.DictReader(open(per_iocd)) if r["outcome"] == "verified"]
        total_v += len(verified)
        copied = missing = 0
        for iocd_id in verified:
            src_json = os.path.join(args.iocd_root, app, f"{iocd_id}.json")
            if not os.path.exists(src_json):
                print(f"[-] {app} {iocd_id}: missing IOCD", file=sys.stderr)
                missing += 1
                continue
            dest = os.path.join(args.out, app, iocd_id)
            os.makedirs(dest, exist_ok=True)
            shutil.copy(src_json, os.path.join(dest, f"{iocd_id}.json"))
            copied += 1
        total_copied += copied
        total_missing += missing
        print(f"{app:36s} verified={len(verified):4d}  copied={copied:4d}" + (f"  missing={missing}" if missing else ""))
    print(f"\ntotal: verified={total_v} copied={total_copied} missing={total_missing}")


if __name__ == "__main__":
    main()
