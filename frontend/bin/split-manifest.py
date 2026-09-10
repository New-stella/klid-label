#!/usr/bin/env python3
"""증적 원장을 CBD 산출물 **벌마다** 쪼갠다.

왜 필요한가
    D11 의 케이스 집합이 벌마다 다르다(포털 포함 48 · 포털 제외 44 · 포털향 4).
    전건 원장을 그대로 주면 생성기가 「원장의 케이스ID 가 D11 에 없다」며 멈춘다.
    멈추는 것이 옳다 — 안 멈추면 그 캡처가 소리 없이 빠진 산출물이 나간다.

순서
    ① 벌마다 한 번 생성한다. I2 에서 멈춰도 **D11 은 이미 나와 있다.**
    ② 이 스크립트로 원장을 쪼갠다(각 벌의 D11 에서 케이스ID 를 읽는다 — 손으로 적지 않는다).
    ③ 다시 생성한다.

사용
    python3 frontend/bin/split-manifest.py \
        --captures ~/Desktop/CBD-캡처루트-YYYYMMDD \
        --out-root ~/Desktop/CBD-생성본-YYYYMMDDx
"""
import argparse
import json
import os
import re
import sys

VARIANTS = (('포털제외', 'manifest-포털제외.json'), ('포털향', 'manifest-포털향.json'))
CASE_RE = re.compile(r'KLID-AT-UT-\d{3}-\d{2}')


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--captures', required=True, help='캡처 루트(capture-manifest.json 이 있는 폴더)')
    ap.add_argument('--out-root', required=True, help='생성본 루트(벌 폴더들이 있는 곳)')
    a = ap.parse_args()

    root = os.path.expanduser(a.captures)
    outroot = os.path.expanduser(a.out_root)
    full_path = os.path.join(root, 'capture-manifest.json')
    with open(full_path, encoding='utf-8') as fh:
        full = json.load(fh)

    bad = 0
    for variant, out in VARIANTS:
        d11 = os.path.join(outroot, variant, 'KLID_AT_단위시험 케이스.final.md')
        if not os.path.isfile(d11):
            print(f'  !! {variant}: D11 이 없다 — 먼저 그 벌을 한 번 생성하라 ({d11})')
            bad += 1
            continue
        with open(d11, encoding='utf-8') as fh:
            ids = set(CASE_RE.findall(fh.read()))
        sub = {k: v for k, v in full.items() if k in ids}
        missing = sorted(ids - set(full))
        with open(os.path.join(root, out), 'w', encoding='utf-8') as fh:
            json.dump(sub, fh, ensure_ascii=False, indent=2)
            fh.write('\n')
        mark = '' if not missing else f'  ← ★증적 없는 케이스 {len(missing)}건: {missing[:5]}'
        print(f'  {variant}: D11 케이스 {len(ids)} · 원장 {len(sub)}건{mark}')
        if missing:
            # 원장을 줄여서 맞추지 마라 — 그 케이스를 찍어야 한다.
            bad += 1
    print(f'  포털포함: 원장 {len(full)}건 (전건 — 쪼개지 않는다)')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
