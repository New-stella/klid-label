#!/usr/bin/env python3
"""값 키에 폐기된 값이 새어 들어갔는지 본다.

왜 필요한가 — 이 디렉터리의 파일은 **함정을 설명하느라 폐기된 값을 인용한다.**
그래서 파일을 통째로 낱말 검색하면 폐기된 값이 잡히는데, 그것은 오염이 아니라 설명이다.
판정은 **어느 자리에 있는가**로 해야 하고, 그 판정을 사람이 매번 기억하는 대신 여기서 돌린다.

  값     레시피·원장으로 가는 키.        여기엔 값만 있어야 한다
  설명   `_` 접두 메타 키 · 아래 SIDE.  폐기된 옛 값이 인용되는 게 정상이다

★ 양성 대조가 함께 있다. 「폐기 값 0건」만 세면 **훑는 축이 틀렸을 때도 0건**이 나와
  조용히 통과한다. 그래서 현행 값이 실제로 잡히는지를 같은 훑기로 함께 확인한다 —
  안 잡히면 파일이 깨끗한 게 아니라 검사기가 눈먼 것이다.

  python3 docs/cbd-inputs/verify.py
"""
import json, pathlib, sys

HERE = pathlib.Path(__file__).parent

# 설명이 인용해도 되는 자리 — 값 키가 아니다.
#
# ⚠ 「`_` 로 시작하면 설명」이라는 규칙을 쓰지 않는다. 실제로 그렇게 짰다가 `_현재형상_갱신`
#    (값 컨테이너다)을 통째로 건너뛰어 **검사기가 값을 하나도 안 봤다.** 양성 대조가 그것을
#    잡았다 — 폐기 값 0건만 셌으면 조용히 통과했을 것이다.
#    그래서 설명 키를 **이름으로 열거**한다. 새 메타 키가 생기면 검사에 걸리는데, 그게
#    조용히 빠지는 것보다 낫다(fail-closed).
META = {"_사업", "_doc", "_출처", "_읽는법", "_주의", "_실측근거", "_판정방법"}
SIDE = {"제출본", "근거", "★주의", "항목", "구분", "대상"}

# (폐기된 값, 그 자리를 대신한 현행 값) — 현행 값은 양성 대조로도 쓴다.
PAIRS = [("Tom" + "cat", "JBoss")]


def values(node, key=None):
    """값 키에 실린 문자열만 낸다(`_` 접두 메타와 SIDE 는 건너뛴다)."""
    if isinstance(node, dict):
        for k, v in node.items():
            if isinstance(k, str) and (k in META or k in SIDE):
                continue
            yield from values(v, k)
    elif isinstance(node, list):
        for v in node:
            yield from values(v, key)
    elif isinstance(node, str):
        yield key, node


def main() -> int:
    files = sorted(p for p in HERE.glob("*.json"))
    if not files:
        print("!! 검사 대상 json 이 없다 — 경로가 맞는지 본다")
        return 2

    bad, seen_current, checked = [], set(), 0
    for f in files:
        doc = json.loads(f.read_text(encoding="utf-8"))
        for key, text in values(doc):
            checked += 1
            for old, cur in PAIRS:
                if old in text:
                    bad.append(f"{f.name} / {key}: {text[:90]}")
                if cur in text:
                    seen_current.add(cur)

    print(f"검사한 값 문자열 {checked}개 ({len(files)}개 파일)")

    ok = True

    # ★ 「0건」은 어디를 안 봐도 나오지만, **센 개수는 안 보면 줄어든다.**
    #    그래서 개수 자체에도 하한을 둔다.
    #
    #    ★ 두 장치가 잡는 것이 다르다 — 실측으로 확인했다.
    #      개수 하한 : 파일을 못 읽거나 구조가 **통째로** 깨진 경우
    #      양성 대조 : 값 컨테이너 **하나가** 검사에서 빠진 경우
    #    값 컨테이너 하나를 삼키는 변이를 심었더니 214→197 이라 하한(100)은 통과했고
    #    **양성 대조만 잡았다.** 하한을 정확한 개수로 올려 그것까지 잡게 만들지 말 것 —
    #    그러면 값이 하나 늘 때마다 숫자를 맞춰야 하고, 맞추는 순간 감시가 사라진다.
    #
    #    ⚠ 이 수는 **정확한 값 개수가 아니라 자릿수 감시**다. 무엇을 세는지 모른 채
    #    「숫자가 안 맞네」 하고 맞춰 올리면 감시가 사라진다. 값이 실제로 줄어든 게
    #    아니라면 **먼저 검사기가 눈멀었는지부터 본다.**
    FLOOR = 100
    if checked < FLOOR:
        ok = False
        print(f"!! 검사한 값이 {checked}개뿐이다(하한 {FLOOR}) — 값 컨테이너를 못 보고 있을 수 있다")
        print("     파일이 줄어든 게 아니라면 META/SIDE 열거가 값 키를 삼켰는지 본다")
    for old, cur in PAIRS:
        hit = [b for b in bad if old in b]
        if hit:
            ok = False
            print(f"!! 값 키에 폐기된 값 '{old}' 이 있다 — 설명이 아니라 값 자리다")
            for h in hit:
                print(f"     {h}")
        else:
            print(f"OK 값 키에 '{old}' 없음")

        if cur in seen_current:
            print(f"OK 양성 대조 — 같은 훑기로 '{cur}' 이 잡힌다")
        else:
            ok = False
            print(f"!! 양성 대조 실패 — '{cur}' 이 안 잡힌다.")
            print("     파일이 깨끗한 게 아니라 훑는 축이 틀렸다(키 이름·구조가 바뀌었는지 본다)")

    print("통과" if ok else "실패")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
