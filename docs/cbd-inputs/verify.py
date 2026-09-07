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
import hashlib, json, os, pathlib, sys

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
#
# ⚠ 대소문자를 무시해 비교한다. 구분해서 비교했더니 **대문자 변이를 놓쳤다**(실측:
#    값 자리에 대문자로 심으니 통과했다). 실제 표기가 문서마다 갈리므로(제품명·환경변수·
#    경로가 각각 다른 표기를 쓴다) 구분 비교는 오염을 조용히 통과시킨다.
#
# ⚠⚠ 양성 대조로 고르는 값은 **「잡힐 것 같은 것」이 아니라 「반드시 잡혀야 하는 것」**이어야
#    한다. 잡히지 않는 값을 고르면 **양성 대조 자체가 거짓 0건**이 되어, 감시가 있는 척만 한다.
#    새 짝을 넣으면 그 자리에 심어 보고 실제로 발화하는지 먼저 확인한다.
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
            low = text.lower()
            for old, cur in PAIRS:
                if old.lower() in low:
                    # ⚠ 어느 짝에 걸렸는지 **여기서 함께 담는다.** 아래에서 문자열을 다시
                    #    검색해 가르면 그 검색이 또 대소문자에 걸린다 — 실제로 수집만 고치고
                    #    판정을 안 고쳐 대문자 변이를 놓쳤다(값이 두 군데 있으면 한쪽만 고쳐진다).
                    bad.append((old, f"{f.name} / {key}: {text[:90]}"))
                if cur.lower() in low:
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
        hit = [msg for o, msg in bad if o == old]
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

    ok = check_draft_twin() and ok

    print("통과" if ok else "실패")
    return 0 if ok else 1


# 레포 밖에 같은 초안이 있으면 갈렸는지 본다.
# 왜: 생성기는 --draft 로 준 경로를 읽는다. 레포 밖 사본을 고치면 산출물은 바뀌는데
#     레포는 그대로라, 다른 기계에서 체크아웃하면 그 변경이 통째로 없다.
#     ⚠ 없으면 실패가 아니다 — 다른 기계에는 애초에 그 사본이 없는 것이 정상이다.
TWIN = os.path.expanduser(
    "~/Documents/workspace/klid/docs/design/"
    "D11-R1-단위시험절차서-기능정상-컴포넌트기준-UI절차-정합수정.md"
)
DRAFT = "D11-단위시험절차서-초안.md"


def _sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def check_draft_twin():
    here = os.path.join(os.path.dirname(os.path.abspath(__file__)), DRAFT)
    if not os.path.exists(here):
        print(f"!! {DRAFT} 이 레포에 없다 — 생성기가 물 정본이 사라졌다")
        return False
    mine = _sha(here)
    # 양성 대조: 자기 자신과는 반드시 같아야 한다. 다르면 해시 계산이 깨진 것이다.
    if _sha(here) != mine:
        print("!! 양성 대조 실패 — 같은 파일을 두 번 재는데 값이 다르다")
        return False
    if not os.path.exists(TWIN):
        print(f"OK {DRAFT} — 레포 밖 사본 없음(이 기계에는 정본만 있다)")
        return True
    if _sha(TWIN) == mine:
        print(f"OK {DRAFT} — 레포 밖 사본과 같다")
        return True
    print(f"!! {DRAFT} 이 레포 밖 사본과 갈렸다")
    print(f"     레포   {mine[:16]}  {here}")
    print(f"     레포 밖 {_sha(TWIN)[:16]}  {TWIN}")
    print("     둘 중 어느 쪽이 최신인지 확인해 맞춘다. 레포 쪽이 정본이다")
    return False


if __name__ == "__main__":
    sys.exit(main())
