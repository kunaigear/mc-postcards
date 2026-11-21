#!/usr/bin/env python3
"""Compares the render cost of two builds with the render benchmark. See docs/development.md."""

import argparse
import csv
import math
import os
import shutil
import signal
import socket
import statistics
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

# Two-sided 95% quantiles of Student's t, by degrees of freedom.
T95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228,
       12: 2.179, 15: 2.131, 20: 2.086, 30: 2.042}
METRICS = [("cpu", "render thread CPU"), ("alloc_mb", "allocated"), ("time", "wall time")]
GATED = {"cpu"}


def git(*args, cwd, **kw):
    return subprocess.run(["git", *args], cwd=cwd, check=True, text=True, capture_output=True, **kw).stdout


def running_benchmarks():
    pids = []
    for proc in Path("/proc").iterdir():
        try:
            if proc.name.isdigit() and b"-Dpostcards.benchmark=true" in (proc / "cmdline").read_bytes():
                pids.append(int(proc.name))
        except OSError:
            pass
    return pids


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


created = []


def add_worktree(repo, path, ref):
    if path.exists():
        sys.exit(f"{path} exists, maybe kept by --keep; remove it with git worktree remove")
    git("worktree", "add", "--detach", str(path), ref, cwd=repo)
    created.append(path)


def snapshot_working_tree(repo, path):
    """The candidate is the working tree as it is now, so it can keep changing while the benchmark runs."""
    add_worktree(repo, path, "HEAD")
    diff = subprocess.run(["git", "diff", "--binary", "HEAD"], cwd=repo, check=True, capture_output=True).stdout
    if diff:
        subprocess.run(["git", "apply", "--whitespace=nowarn"], cwd=path, input=diff, check=True)
    untracked = git("ls-files", "--others", "--exclude-standard", "-z", cwd=repo).split("\0")
    for name in filter(None, untracked):
        (path / name).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(repo / name, path / name)


def t95(df):
    return T95[max(k for k in T95 if k <= df)]


def interval(logs):
    """Geometric mean of the ratios and its 95% interval, over pairs."""
    mean = statistics.fmean(logs)
    if len(logs) < 2:
        return math.exp(mean), None, None
    half = t95(len(logs) - 1) * statistics.stdev(logs) / math.sqrt(len(logs))
    return math.exp(mean), math.exp(mean - half), math.exp(mean + half)


def summarise(runs, warmup, tolerance):
    """runs: one {side: rows} per pair. Returns the report text and whether a gated metric got worse."""
    scenes = list(dict.fromkeys((r["camera"], r["shader"]) for r in runs[0]["base"]))
    lines, regressed = [], False
    lines.append(f"{len(runs)} pairs; per pair, the mean of rounds after the first {warmup} of each scene.")
    lines.append("Ratio is candidate / base: below 1 is faster or less. 95% interval over pairs.\n")
    for metric, title in METRICS:
        lines.append(f"{title} ({'MB' if metric == 'alloc_mb' else 's'})")
        lines.append(f"  {'scene':<20} {'base':>9} {'candidate':>9} {'ratio':>7}  {'95% interval':<15}")
        totals = [{"base": 0.0, "candidate": 0.0} for _ in runs]
        for scene in scenes + [("all", "renders")]:
            logs, means = [], {"base": [], "candidate": []}
            for i, run in enumerate(runs):
                m = {}
                for side in ("base", "candidate"):
                    if scene[0] == "all":
                        m[side] = totals[i][side]
                    else:
                        vals = [float(r[metric]) for r in run[side]
                                if (r["camera"], r["shader"]) == scene and int(r["round"]) > warmup]
                        m[side] = statistics.fmean(vals)
                        totals[i][side] += m[side]
                    means[side].append(m[side])
                logs.append(math.log(m["candidate"] / m["base"]))
            ratio, lo, hi = interval(logs)
            ci = f"{lo:.3f}-{hi:.3f}" if lo else "need 2+ pairs"
            flag = ""
            if lo and lo > 1 and ratio > 1 + tolerance:
                flag = "  WORSE" if metric in GATED else "  higher"
                regressed |= metric in GATED
            elif lo and hi < 1:
                flag = "  better"
            name = " ".join(scene)
            lines.append(f"  {name:<20} {statistics.fmean(means['base']):9.2f} {statistics.fmean(means['candidate']):9.2f}"
                         f" {ratio:7.3f}  {ci:<15}{flag}")
        lines.append("")
    lines.append(f"WORSE: render CPU is higher with 95% confidence, and the ratio is above {1 + tolerance:.2f}. Allocation"
                 " and wall time aren't judged: some servers settle into JIT code that allocates more for the same"
                 " work, and servers side by side share the disk.")
    return "\n".join(lines), regressed


def compare_pictures(runs, out):
    """Each build must render a scene the same in every round and server, or the comparison means nothing."""
    scenes = list(dict.fromkeys((r["camera"], r["shader"]) for r in runs[0]["base"]))
    lines, differ, unstable = ["Pictures, by a hash of their pixels"], False, False
    for scene in scenes:
        hashes, files = {}, {}
        for side in ("base", "candidate"):
            rows = [r for run in runs for r in run[side] if (r["camera"], r["shader"]) == scene]
            hashes[side], files[side] = {r["picture"] for r in rows}, rows[0]["file"]
        name = " ".join(scene)
        if len(hashes["base"]) > 1 or len(hashes["candidate"]) > 1:
            unstable = True
            lines.append(f"  {name:<20} changes between renders of the same build")
        elif hashes["base"] != hashes["candidate"]:
            differ = True
            (out / "pictures").mkdir(exist_ok=True)
            for side, file in files.items():
                shutil.copy2(file, out / "pictures" / f"{scene[0]}-{scene[1]}-{side}.png")
            lines.append(f"  {name:<20} differs, both are in pictures/")
        else:
            lines.append(f"  {name:<20} identical")
    return "\n".join(lines), differ, unstable


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--base", default="main", help="git ref to compare against (default: main)")
    p.add_argument("--candidate", help="git ref to measure (default: the working tree, uncommitted changes included)")
    p.add_argument("--pairs", type=int, default=3, help="benchmark servers per side, run in pairs side by side (default: 3)")
    p.add_argument("--rounds", type=int, default=3, help="renders of each scene per server (default: 3)")
    p.add_argument("--warmup", type=int, default=1, help="rounds per server left out of the comparison (default: 1)")
    p.add_argument("--cores", type=int, default=2, help="CPU cores pinned to each side (default: 2)")
    p.add_argument("--heap", default="3G", help="maximum heap per server (default: 3G)")
    p.add_argument("--cpu-share", default="1", help="renderCpuShare during the renders (default: 1, no throttling)")
    p.add_argument("--tolerance", type=float, default=0.02, help="smallest slowdown that fails the comparison (default: 0.02)")
    p.add_argument("--pictures-may-differ", action="store_true", help="don't fail when the pictures differ")
    p.add_argument("--keep", action="store_true", help="keep the two worktrees afterwards")
    args = p.parse_args()
    if args.rounds <= args.warmup:
        sys.exit("--rounds must be larger than --warmup")

    repo = Path(git("rev-parse", "--show-toplevel", cwd=Path.cwd()).strip())
    template = repo / "run/benchmark/world"
    if not (template / "postcards-benchmark-prepared").exists():
        sys.exit(f"No prepared benchmark world in {template}; run ./gradlew runBenchmark once first")
    if pids := running_benchmarks():
        sys.exit(f"Another benchmark server is running (PID {', '.join(map(str, pids))}); results would be skewed")
    cpus = sorted(os.sched_getaffinity(0))
    if len(cpus) < 2 * args.cores + 1:
        sys.exit(f"Need {2 * args.cores + 1} CPUs for two sides of {args.cores} cores plus one for the rest, have {len(cpus)}")
    # The highest CPUs, one set per side; the rest is left to the OS and Gradle.
    sets = [cpus[-2 * args.cores:-args.cores], cpus[-args.cores:]]

    out = repo / "run/benchmark/compare" / datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    out.mkdir(parents=True)
    trees = {"base": repo.parent / f"{repo.name}-bench-base", "candidate": repo.parent / f"{repo.name}-bench-candidate"}
    procs = []
    try:
        add_worktree(repo, trees["base"], args.base)
        if args.candidate:
            add_worktree(repo, trees["candidate"], args.candidate)
        else:
            snapshot_working_tree(repo, trees["candidate"])
        if "benchFreeze" not in (trees["base"] / "build.gradle.kts").read_text():
            sys.exit(f"{args.base} predates the benchmark options this script uses; compare against a newer base")
        # Both sides are measured by the candidate's benchmark.
        shutil.rmtree(trees["base"] / "src/devtest")
        shutil.copytree(trees["candidate"] / "src/devtest", trees["base"] / "src/devtest")
        refs = {side: git("describe", "--always", "--dirty", cwd=tree).strip() for side, tree in trees.items()}
        print(f"base {refs['base']}, candidate {refs['candidate']}{'' if args.candidate else ' + working tree'}")

        gradle = ["./gradlew", "--offline", "--no-daemon", "-q"]
        for side, tree in trees.items():
            print(f"Building {side}...", flush=True)
            subprocess.run(gradle + ["classes", "devtestClasses"], cwd=tree, check=True)

        runs = []
        for pair in range(1, args.pairs + 1):
            started = time.monotonic()
            # Swap the CPU sets every pair, so neither side always gets the same cores.
            assign = {"base": sets[pair % 2], "candidate": sets[1 - pair % 2]}
            procs = []
            for side, tree in trees.items():
                world = tree / "run/benchmark/world"
                shutil.rmtree(world, ignore_errors=True)
                shutil.copytree(template, world)
                label = f"compare-{side}-{pair}"
                cmd = ["taskset", "-c", ",".join(map(str, assign[side])), *gradle, "runBenchmark",
                       f"-PbenchCores={args.cores}", f"-PbenchHeap={args.heap}", f"-PbenchRounds={args.rounds}",
                       f"-PbenchCpuShare={args.cpu_share}", "-PbenchFreeze", f"-PbenchPort={free_port()}", f"-PbenchLabel={label}"]
                log = open(out / f"{label}.log", "w")
                procs.append((side, label, subprocess.Popen(cmd, cwd=tree, stdout=log, stderr=subprocess.STDOUT,
                                                                start_new_session=True)))
            print(f"Pair {pair}/{args.pairs}: base on CPUs {assign['base']}, candidate on CPUs {assign['candidate']}", flush=True)
            run = {}
            for side, label, proc in procs:
                if proc.wait() != 0:
                    sys.exit(f"{label} failed, see {out / (label + '.log')}")
                report = next((trees[side] / "run/benchmark/reports").glob(f"*_{label}.csv"))
                shutil.copy2(report, out / report.name)
                shutil.copy2(report.with_suffix(".txt"), out / report.with_suffix(".txt").name)
                with open(report) as f:
                    run[side] = list(csv.DictReader(f))
            procs = []
            runs.append(run)
            print(f"  done in {time.monotonic() - started:.0f} s", flush=True)

        text, regressed = summarise(runs, args.warmup, args.tolerance)
        pictures, differ, unstable = compare_pictures(runs, out)
        text += "\n\n" + pictures
        if unstable:
            text += "\nA build rendered the same scene differently, so the world isn't frozen and nothing can be compared."
        header = (f"base {refs['base']}, candidate {refs['candidate']}{'' if args.candidate else ' + working tree'}\n"
                  f"{args.cores} cores and {args.heap} heap per side, renderCpuShare {args.cpu_share}, {args.rounds} rounds\n\n")
        (out / "summary.txt").write_text(header + text + "\n")
        print("\n" + text + f"\n\nSaved in {out}")
        return 1 if regressed or unstable or (differ and not args.pictures_may_differ) else 0
    finally:
        # Gradle starts the server as a child, so stop the whole process group of each run that's still going.
        for _, _, proc in procs:
            if proc.poll() is None:
                os.killpg(proc.pid, signal.SIGTERM)
                proc.wait(timeout=60)
        if not args.keep:
            for tree in created:
                git("worktree", "remove", "--force", str(tree), cwd=repo)


if __name__ == "__main__":
    sys.exit(main())
