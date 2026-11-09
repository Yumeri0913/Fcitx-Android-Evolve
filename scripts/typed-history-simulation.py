#!/usr/bin/env python3
"""Simulate how the typed code table (typedcodehistory.h) ranks words over time.

Words committed for a code before always come before libime's own candidates, so the order
among them decides the first candidate for codes in the table. This replays a stream of commits
drawn from a Google Pinyin user dictionary (by count, starting from the dictionary as imported)
and reports how often the word the user wanted came first:

- stay: codes whose use does not change. Recency boosts cost here: a word picked once in a
  while comes first for a while after.
- sw1-10, sw11-50, sw51+: codes where the user switched to their second word at the start,
  by uses since then. Recency boosts help here.

--burst repeats one of the last 200 commits with that probability, like typing on one topic.

Usage:
  typed-history-simulation.py DICT [--boost 3 --half-life 5000 --last-boost 2 --last-half-life 100]
  typed-history-simulation.py DICT --grid
"""
import argparse
import importlib.util
import itertools
import math
import os
import random
from collections import defaultdict, deque
from multiprocessing import Pool

spec = importlib.util.spec_from_file_location(
    'benchmark', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'google-pinyin-benchmark.py'))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)

ENTRIES = []
BY_CODE = defaultdict(list)


def load(path):
    ENTRIES[:] = [e for e in benchmark.read_dict(path) if e[2] > 0]
    BY_CODE.clear()
    for _, word, count, code in ENTRIES:
        BY_CODE[code].append((word, count))


def first(words, clock, boost, half_life, last_boost, last_half_life):
    """word lookup() puts first: log10(count) + boosts, then most recent"""
    latest = max(stamp for _, stamp in words.values())
    best, best_key = None, None
    for word, (count, stamp) in words.items():
        score = math.log10(count)
        if stamp > 0:
            age = clock - stamp
            score += boost * 2.0 ** (-age / half_life)
            if stamp == latest:
                score += last_boost * 2.0 ** (-age / last_half_life)
        key = (score, stamp)
        if best_key is None or key > best_key:
            best, best_key = word, key
    return best


def run(cfg):
    boost, half_life, last_boost, last_half_life, burst, seed, total, warmup = cfg
    rnd = random.Random(seed)
    # imported: stamp 0, no boost
    table = {code: {w: [c, 0] for w, c in ws} for code, ws in BY_CODE.items()}
    candidates = sorted(code for code, ws in BY_CODE.items() if len(ws) >= 2 and sum(c for _, c in ws) >= 60)
    switched = {}
    for code in random.Random(1234).sample(candidates, min(300, len(candidates))):
        switched[code] = sorted(BY_CODE[code], key=lambda p: -p[1])[1][0]
    picks = rnd.choices(range(len(ENTRIES)), weights=[e[2] for e in ENTRIES], k=total)
    recent = deque(maxlen=200)
    uses = defaultdict(int)
    hits, counts = defaultdict(int), defaultdict(int)
    clock = 0
    for t in range(total):
        if recent and rnd.random() < burst:
            code, word = recent[rnd.randrange(len(recent))]
        else:
            _, word, _, code = ENTRIES[picks[t]]
        if code in switched and t >= warmup:
            word = switched[code]
        words = table[code]
        if t >= warmup:
            ok = first(words, clock, boost, half_life, last_boost, last_half_life) == word
            if code in switched:
                n = uses[code]
                uses[code] += 1
                group = 'sw1-10' if n < 10 else 'sw11-50' if n < 50 else 'sw51+'
            else:
                group = 'stay'
            counts[group] += 1
            hits[group] += ok
        clock += 1
        entry = words.setdefault(word, [0, 0])
        entry[0] += 1
        entry[1] = clock
        recent.append((code, word))
    return cfg, {g: hits[g] / counts[g] for g in counts}


def main():
    p = argparse.ArgumentParser()
    p.add_argument('dict')
    p.add_argument('--boost', type=float, default=3.0)
    p.add_argument('--half-life', type=float, default=5000)
    p.add_argument('--last-boost', type=float, default=2.0)
    p.add_argument('--last-half-life', type=float, default=100)
    p.add_argument('--burst', type=float, nargs='+', default=[0.0, 0.3])
    p.add_argument('--seed', type=int, default=7)
    p.add_argument('--commits', type=int, default=500000)
    p.add_argument('--warmup', type=int, default=30000)
    p.add_argument('--grid', action='store_true')
    a = p.parse_args()
    load(a.dict)
    if a.grid:
        params = [(0, 1, 0, 1)] + list(itertools.product(
            (1.0, 2.0, 3.0), (1000, 3000, 5000, 10000), (0, 1.0, 2.0, 3.0), (100, 300)))
    else:
        params = [(0, 1, 0, 1), (a.boost, a.half_life, a.last_boost, a.last_half_life)]
    cfgs = [(*q, burst, a.seed, a.commits, a.warmup) for q in params for burst in a.burst]
    with Pool(initializer=load, initargs=(a.dict,)) as pool:
        for cfg, res in pool.imap(run, cfgs):
            print(f'boost={cfg[0]}/{cfg[1]:g} last={cfg[2]}/{cfg[3]:g} burst={cfg[4]} '
                  + ' '.join(f'{g}={res[g]:.3f}' for g in ('stay', 'sw1-10', 'sw11-50', 'sw51+')),
                  flush=True)


if __name__ == '__main__':
    main()
