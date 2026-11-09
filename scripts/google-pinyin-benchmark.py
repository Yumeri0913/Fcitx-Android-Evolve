#!/usr/bin/env python3
"""Compare fcitx pinyin with Google Pinyin on a Google Pinyin user dictionary.

A Google Pinyin user dictionary (Settings -> Dictionary -> Export, user-dictionary.txt) lists
every word the user picked, how often, and what they typed for it. That is Google Pinyin's
behavior on real typing, so it is the reference:

- order: type each code as is. The word picked most for a code should come first, and words
  should come in the order of their counts (Google Pinyin ranks user words by usage).
- typo: type each word with touch noise, a 2D Gaussian around the key center like the spatial
  model, and pass touch alternatives the same way the keyboard does. The word should survive.

Usage:
  google-pinyin-benchmark.py queries order DICT > queries.tsv
  google-pinyin-benchmark.py queries typo DICT [--trials N] [--noise K] [--seed S] > queries.tsv
  (run CandidateDumpBenchmark with queries.tsv and DICT in <external files>/benchmark)
  google-pinyin-benchmark.py report DICT queries.tsv candidates.tsv [--details FILE]
"""
import argparse
import math
import random
import re
import sys
from collections import defaultdict

# ---------------------------------------------------------------- dictionary

ENGLISH_CODE = re.compile(r'^\\0((?: [a-z])+)$')
PINYIN_CODE = re.compile(r"^[a-z]+(?: [a-z]+)*$")


def read_dict(path):
    """[(kind, word, count, keys)], kind 'zh' or 'en'"""
    raw = open(path, 'rb').read()
    text = raw[2:].decode('utf-16-le') if raw[:2] == b'\xff\xfe' else raw.decode('utf-8-sig')
    entries = []
    for line in text.splitlines():
        if not line or line.startswith('#'):
            continue
        fields = line.split('\t')
        if len(fields) < 3:
            continue
        word, count, code = fields[0], fields[1], fields[2]
        try:
            count = int(count)
        except ValueError:
            continue
        m = ENGLISH_CODE.match(code)
        if m:
            keys = m.group(1).replace(' ', '')
            if len(keys) >= 2 and word.lower() == keys:
                entries.append(('en', word, count, keys))
        elif PINYIN_CODE.match(code) and not re.search(r'[A-Za-z0-9]', word):
            entries.append(('zh', word, count, code.replace(' ', '')))
    return entries


# ---------------------------------------------------------------- keyboard and touch model

KEY_W = 6.52  # mm, 411dp wide phone, 10 keys a row
KEY_H = 9.37  # mm, Google Pinyin keyboard body 242dp, 6dp top padding, 4 rows
ROWS = [('qwertyuiop', 0.0), ('asdfghjkl', 0.5), ('zxcvbnm', 1.5)]


def key_rects():
    """touch rectangles in mm, a and l extended to the screen edges like the keyboard"""
    rects = {}
    for r, (letters, offset) in enumerate(ROWS):
        for i, c in enumerate(letters):
            left = (offset + i) * KEY_W
            right = left + KEY_W
            if c == 'a':
                left = 0.0
            if c == 'l':
                right = 10 * KEY_W
            rects[c] = (left, r * KEY_H, right, (r + 1) * KEY_H)
    return rects


RECTS = key_rects()
CENTERS = {c: ((l + r) / 2, (t + b) / 2) for c, (l, t, r, b) in RECTS.items()}
# visual key centers (where users aim): a and l are drawn one key wide
AIM = dict(CENTERS)
AIM['a'] = (1.0 * KEY_W, KEY_H * 1.5)
AIM['l'] = (9.0 * KEY_W, KEY_H * 1.5)


def rect_distance(rect, x, y):
    l, t, r, b = rect
    dx = max(l - x, 0.0, x - r)
    dy = max(t - y, 0.0, y - b)
    return math.hypot(dx, dy)


def hit(x, y):
    """letter key under (x, y), clamped into the letter area"""
    row = min(max(int(y // KEY_H), 0), 2)
    letters, offset = ROWS[row]
    xs = [(RECTS[c][0], RECTS[c][2], c) for c in letters]
    x = min(max(x, xs[0][0]), xs[-1][1] - 1e-6)
    for l, r, c in xs:
        if l <= x < r:
            return c
    return letters[-1]


class SpatialModel:
    """InsightKey's SpatialModel.kt, with the vertical variance rule as a parameter"""

    def __init__(self, aspect_vertical=True):
        self.aspect_vertical = aspect_vertical
        self.radius = KEY_W  # min(most common width, most common height)

    def var(self, c):
        l, t, r, b = RECTS[c]
        w, h = r - l, b - t
        var_x = 0.0075 * (1.5 * w) ** 2 + 1.68
        var_y = 0.0108 * (1.5 * h) ** 2 + 1.33
        if self.aspect_vertical:
            var_y = max(var_y, var_x * (h / w) ** 2)
        return var_x, var_y

    def score(self, c, x, y):
        var_x, var_y = self.var(c)
        cx, cy = CENTERS[c]
        norm = math.log(1.0 / (2 * math.pi * math.sqrt(var_x * var_y)))
        return norm - 0.5 * ((x - cx) ** 2 / var_x + (y - cy) ** 2 / var_y)

    def alternatives(self, touched, x, y):
        rect = RECTS[touched]
        neighbors = []
        for c, (cx, cy) in CENTERS.items():
            if c == touched:
                continue
            if rect_distance(rect, cx, cy) > 1.2 * self.radius:
                continue
            if rect_distance(RECTS[c], x, y) > self.radius:
                continue
            neighbors.append((c, self.score(c, x, y)))
        neighbors.sort(key=lambda p: -p[1])
        return [(touched, self.score(touched, x, y))] + neighbors


# ---------------------------------------------------------------- queries

def order_queries(entries):
    seen = set()
    for kind, word, count, keys in entries:
        if keys not in seen:
            seen.add(keys)
            yield keys, keys, None


def typo_queries(entries, trials, noise, seed, model, offset_y, sample):
    rnd = random.Random(seed)
    if sample:
        # typing events: words drawn by how often they were used
        weights = [max(e[2], 0) for e in entries]
        picks = rnd.choices(range(len(entries)), weights=weights, k=sample)
        jobs = [(idx, t) for t, idx in enumerate(picks)]
    else:
        jobs = [(idx, t) for idx in range(len(entries)) for t in range(trials)]
    for idx, t in jobs:
        kind, word, count, keys = entries[idx]
        if True:
            typed = []
            alts = []
            for c in keys:
                ax, ay = AIM[c]
                var_x, var_y = SpatialModel(False).var(c)  # Google's own touch spread
                x = rnd.gauss(ax, noise * math.sqrt(var_x))
                y = rnd.gauss(ay + offset_y, noise * math.sqrt(var_y))
                h = hit(x, y)
                typed.append(h)
                alts.append(model.alternatives(h, x, y))
            qid = f'{idx}:{t}:{"".join(typed)}'
            alt_field = ';'.join(','.join(f'{c}{s:.3f}' for c, s in a) for a in alts)
            yield qid, ''.join(typed), alt_field


# ---------------------------------------------------------------- report

def read_candidates(path):
    result = {}
    for line in open(path, encoding='utf-8'):
        fields = line.rstrip('\n').split('\t')
        result[fields[0]] = fields[1:]
    return result


def rank(cands, word, kind):
    if kind == 'en':
        lowered = [c.lower() for c in cands]
        return lowered.index(word.lower()) if word.lower() in lowered else None
    return cands.index(word) if word in cands else None


def report_order(entries, cands, details):
    by_code = defaultdict(list)
    for e in entries:
        by_code[e[3]].append(e)
    stats = defaultdict(lambda: defaultdict(float))
    rows = []
    for keys, group in by_code.items():
        if keys not in cands:
            continue
        got = cands[keys]
        # Google Pinyin's order among the user's words: by count
        group = sorted(group, key=lambda e: -e[2])
        for ideal, (kind, word, count, _) in enumerate(group):
            r = rank(got, word, kind)
            for weight_name, w in (('weighted', count), ('entries', 1)):
                s = stats[(kind, weight_name)]
                s['total'] += w
                s['top1'] += w * (r == 0)
                s['top3'] += w * (r is not None and r < 3)
                s['found'] += w * (r is not None)
                s['ideal_top1'] += w * (ideal == 0)
                s['same_order'] += w * (r == ideal)
                s['pos'] += w * (r if r is not None else len(got) + 1)
                s['ideal_pos'] += w * ideal
            if r != ideal:
                rows.append((count, kind, keys, word, ideal, r, got[:5]))
    for (kind, weight_name), s in sorted(stats.items()):
        t = s['total'] or 1
        print(f'[order {kind} {weight_name}] n={s["total"]:.0f} '
              f'top1={s["top1"] / t:.1%} (google order {s["ideal_top1"] / t:.1%}) '
              f'top3={s["top3"] / t:.1%} found={s["found"] / t:.1%} '
              f'same_rank_as_google={s["same_order"] / t:.1%} '
              f'avg_pos={s["pos"] / t:.2f} (google {s["ideal_pos"] / t:.2f})')
    if details:
        with open(details, 'w', encoding='utf-8') as f:
            for row in sorted(rows, key=lambda r: -r[0]):
                f.write('\t'.join(map(str, row)) + '\n')


def report_typo(entries, cands, details):
    stats = defaultdict(lambda: defaultdict(float))
    rows = []
    for qid, got in cands.items():
        idx, _, typed = qid.split(':')
        kind, word, count, keys = entries[int(idx)]
        r = rank(got, word, kind)
        # vertical if any wrong key is in another row
        row = {c: i for i, (letters, _) in enumerate(ROWS) for c in letters}
        wrong = [(k, t) for k, t in zip(keys, typed) if k != t]
        if not wrong:
            group = 'clean'
        elif any(row[k] != row[t] for k, t in wrong):
            group = 'missed-row'
        else:
            group = 'missed-same-row'
        for g in (group, 'all'):
            s = stats[(kind, g)]
            s['total'] += 1
            s['top1'] += r == 0
            s['top3'] += r is not None and r < 3
        if r is None or r > 0:
            rows.append((count, kind, keys, typed, word, r, got[:5]))
    for (kind, group), s in sorted(stats.items()):
        t = s['total'] or 1
        print(f'[typo {kind} {group}] n={s["total"]:.0f} '
              f'top1={s["top1"] / t:.1%} top3={s["top3"] / t:.1%}')
    if details:
        with open(details, 'w', encoding='utf-8') as f:
            for row in sorted(rows, key=lambda r: -r[0]):
                f.write('\t'.join(map(str, row)) + '\n')


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest='cmd', required=True)
    q = sub.add_parser('queries')
    q.add_argument('mode', choices=['order', 'typo'])
    q.add_argument('dict')
    q.add_argument('--trials', type=int, default=1)
    q.add_argument('--sample', type=int, default=0, help='typing events drawn by count')
    q.add_argument('--noise', type=float, default=1.0)
    q.add_argument('--offset-y', type=float, default=0.0, help='mean touch offset, mm down')
    q.add_argument('--seed', type=int, default=1)
    q.add_argument('--google-vertical', action='store_true', help="Google's vertical variance")
    q.add_argument('--no-touch', action='store_true', help='noisy keys without alternatives')
    r = sub.add_parser('report')
    r.add_argument('mode', choices=['order', 'typo'])
    r.add_argument('dict')
    r.add_argument('candidates')
    r.add_argument('--details')
    a = p.parse_args()
    entries = read_dict(a.dict)
    if a.cmd == 'queries':
        if a.mode == 'order':
            gen = order_queries(entries)
        else:
            model = SpatialModel(aspect_vertical=not a.google_vertical)
            gen = typo_queries(entries, a.trials, a.noise, a.seed, model, a.offset_y, a.sample)
        for qid, keys, alts in gen:
            if a.mode == 'typo' and a.no_touch:
                alts = None
            sys.stdout.write(f'{qid}\t{keys}' + (f'\t{alts}' if alts else '') + '\n')
    else:
        cands = read_candidates(a.candidates)
        (report_order if a.mode == 'order' else report_typo)(entries, cands, a.details)


if __name__ == '__main__':
    main()
