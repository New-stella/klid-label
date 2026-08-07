#!/usr/bin/env python3
"""LogiCraft ITEM 정합 검증기 — 5종 검사.

사용:
  python3 verify-items.py loss     <staging_raw_dir> <before_raw_dir>   # 콘텐츠 감소(SCREEN)
  python3 verify-items.py uiloss   <staging_raw_dir> <before_raw_dir>   # 콘텐츠 감소(UI 카탈로그)
  python3 verify-items.py pollute  <staging_dir> [--no-brownfield]      # 본문 오염
  python3 verify-items.py hangul   <staging_dir> [ITEM-ID,...]          # 한글 손상(희귀음절)
  python3 verify-items.py wf       <generated_dir> <verify_screen_dir>  # 와이어프레임 대조

staging_dir 예: <kit>/.staging   (하위에 screen_spec/_raw, use_case/_raw ... 구조)

pollute/hangul 은 staging 하위에 존재하는 타입 디렉터리를 자동으로 모두 훑는다
(screen_spec·use_case·acceptance·test_scenario·ui_component·app_shell·navigation_tree…).
오염 패턴(PATS)·한글 검사를 타입별로 복제하지 않기 위한 단일 진실원 구조다.

⚠️ pollute 의 brownfield 포함은 **기본값이 포함**이다(2026-08-08).
   구 동작은 `data.brownfield` 를 통째로 건너뛰었고, 커밋 해시·리포트명·구현상태 오염이
   정확히 거기 쌓여 있어 "오염 0건" 이 나오고 있었다. `--no-brownfield` 로만 끌 수 있다.
"""
import sys, os, json, re, glob, html, collections

L = lambda p: json.load(open(p, encoding="utf-8"))["item"]


# ── 공통: ITEM 본문 텍스트 수집 ────────────────────────────────────────
# `change_summary` 는 **구조적으로** 제외된다 — item 최상위 필드라 `data` 만 훑는 이 함수의
# 시야에 애초에 들어오지 않는다. 거긴 오타·폐기를 *설명*하는 자리라 검사하면 오탐만 난다.
#
# ⚠️ `skip_brownfield` 는 **최상위 `data.brownfield` 만** 건너뛴다(경로 접두 매칭).
#    중첩된 `tables[0].brownfield.notes` 같은 건 예나 지금이나 검사 대상이다 — 즉 구 동작은
#    "brownfield 를 안 본다"가 아니라 **최상위만 안 보는 비대칭**이었다.
def texts(d, skip_brownfield=True):
    out = []
    def rec(v, path=""):
        if isinstance(v, str):
            if len(v) > 1:
                out.append((path, v))
        elif isinstance(v, dict):
            for k, x in v.items():
                rec(x, path + "/" + k)
        elif isinstance(v, list):
            for i, x in enumerate(v):
                rec(x, f"{path}[{i}]" if isinstance(x, (dict, list)) else path)
    rec(d.get("data") or {})
    return [(p, t) for p, t in out if not (skip_brownfield and p.startswith("/brownfield"))]


# 타입 목록을 하드코딩하면 새 타입(ui_component·app_shell…)이 검사에서 조용히 빠진다.
# staging 하위의 `*/_raw/*.json` 을 통째로 훑어 fail-open 이 아니라 fail-closed 로 만든다.
def all_item_files(staging):
    return sorted(glob.glob(os.path.join(staging, "*", "_raw", "*.json")))


# ── ① 콘텐츠 감소 ─────────────────────────────────────────────────────
def cmd_loss(raw, before):
    print(f"{'SCREEN':12s} {'ver':>9s} {'sec':>10s} {'comp':>12s}  판정")
    bad, tb, ta = [], 0, 0
    for f in sorted(glob.glob(os.path.join(raw, "SCREEN-*.json"))):
        a = L(f); sid = a["id"]
        a_s = len(a["data"].get("sections") or [])
        a_c = sum(len(s.get("components") or []) for s in (a["data"].get("sections") or []))
        ta += a_c
        bp = os.path.join(before, f"{sid}.json")
        if not os.path.exists(bp):
            print(f"{sid:12s} {'신규':>9s} {a_s:10d} {a_c:12d}  (before 없음)")
            continue
        b = L(bp)
        b_s = len(b["data"].get("sections") or [])
        b_c = sum(len(s.get("components") or []) for s in (b["data"].get("sections") or []))
        tb += b_c
        ok = a_s >= b_s and a_c >= b_c
        if not ok: bad.append(sid)
        print(f"{sid:12s} {b['current_version']}→{a['current_version']:<6} {b_s:4d}→{a_s:<4d} {b_c:5d}→{a_c:<5d}  {'✅' if ok else '❌ 감소'}")
    print(f"\n  컴포넌트 총계 {tb} → {ta} ({ta-tb:+d})")
    print("  " + ("✅ 감소 0건" if not bad else f"❌ 감소: {bad}"))
    return 1 if bad else 0


# ── ①-b 콘텐츠 감소 (UI 카탈로그) ─────────────────────────────────────
# SCREEN 은 sections/components 2축이지만 UI 카탈로그는 축이 다르다.
# ⚠️ 이 검사는 "감소를 차단"하지 않고 **사라진 원소를 원문 그대로 나열**한다.
#    감소가 전부 나쁜 건 아니다(예: 틀린 분류가 된 tag 제거는 의도된 감소).
#    다만 지난 라운드에서 "감소 없음" 자기보고가 실제 안전 사양 소실을 덮은 적이 있어,
#    사람이 **삭제된 내용을 한 건씩 눈으로 보고** 의도를 확인하도록 강제한다.
UI_ARRAYS = ["props_schema", "variants", "tags", "referenced_by_screen_ids",
             "implements_in_module_ids"]
UI_TEXTS = ["description", "usage_example", "accessibility_notes", "code_snippet"]

def _elem_key(e):
    return e.get("name") if isinstance(e, dict) else e

def cmd_uiloss(raw, before):
    bad, shown = [], 0
    for f in sorted(glob.glob(os.path.join(raw, "UI-*.json"))):
        a = L(f); uid = a["id"]
        bp = os.path.join(before, f"{uid}.json")
        if not os.path.exists(bp):
            print(f"  {uid:9s} 신규 (before 없음)")
            continue
        b = L(bp)
        da, db = a["data"], b["data"]
        drops = []
        for k in UI_ARRAYS:
            va, vb = da.get(k) or [], db.get(k) or []
            ka = {_elem_key(e) for e in va}
            gone = [e for e in vb if _elem_key(e) not in ka]
            if gone:
                drops.append((k, [_elem_key(e) for e in gone]))
        for k in UI_TEXTS:
            ta_, tb_ = (da.get(k) or ""), (db.get(k) or "")
            if len(ta_) < len(tb_):
                drops.append((k, [f"{len(tb_)}자 → {len(ta_)}자 (−{len(tb_)-len(ta_)})"]))
        if drops:
            bad.append(uid); shown += 1
            print(f"\n  ⚠️ {uid} ({db.get('name')}) v{b['current_version']}→{a['current_version']}")
            for k, items in drops:
                print(f"       {k}: 사라짐 {items}")
    print(f"\n  대상 {len(glob.glob(os.path.join(raw,'UI-*.json')))}건 — " +
          ("✅ 감소 0건" if not bad else
           f"⚠️ 감소 {shown}건 — 위 목록을 한 건씩 의도된 삭제인지 확인할 것: {bad}"))
    return 1 if bad else 0


# ── ② 본문 오염 ───────────────────────────────────────────────────────
# 판정 기준: "이 문장이 이 저장소를 모르는 제3자에게도 참인가?"
PATS = [
    ("레포명",     r"klid-label-frontend|upload-ui|테스트베드|납품\s*FE"),
    ("코드파일명", r"[A-Za-z][A-Za-z0-9_]*\.(tsx|ts|jsx|java|py|sql)\b"),
    ("진실원태그", r"진실원[:：]"),
    ("finding코드", r"\b(ERR|GAP|STALE|CONFLICT|LINK|COVER)-[A-Z]\d{2}\b|[\(（]\s*B[0-9]\s*[\)）]|\bB[0-9]\s*(?:이슈|:|：)"),
    ("리포트명",   r"FIXED-[A-Z]\b|[A-Z]-(labeling|manage|augment|scenarios)\.md"),
    ("구현상태",   r"미구현|아직 구현|구현되지 않|코드에서 제거|코드에 존재|후속 구현 예정|핸들러가 연결|미할당"),
    ("감사이력",   r"2차 감사|감사에서 발견|배치 [A-H]\b"),
    ("커밋해시",   r"커밋 [0-9a-f]{7,}"),
]
# 오탐 제외 — 라이브러리명은 기술 스택 사양이다
ALLOW = [r"konva\.js"]

def cmd_pollute(staging, skip_brownfield=False):
    files = all_item_files(staging)
    docs = [(L(f)["id"], p, t)
            for f in files for p, t in texts(L(f), skip_brownfield=skip_brownfield)]
    scope = "제외(--no-brownfield)" if skip_brownfield else "포함"
    print(f"=== 오염 검사 — ITEM {len(files)}건 / 텍스트 {len(docs)} / brownfield {scope} ===")
    n = 0
    for nm, pat in PATS:
        hits = []
        for sid, p, t in docs:
            for m in re.finditer(pat, t, re.I):
                seg = t[max(0, m.start()-45):m.end()+45]
                if any(re.search(a, m.group(0), re.I) for a in ALLOW):
                    continue
                hits.append((sid, p, seg))
        if hits:
            n += len(hits)
            print(f"\n  ⚠️ [{nm}] {len(hits)}건")
            for sid, p, seg in hits[:8]:
                print(f"     {sid:11s} {p[:28]:28s} …{seg}…")
    print("\n  " + ("✅ 오염 0건" if n == 0 else f"❌ 합계 {n}건"))
    print(_POLLUTE_BLIND_SPOTS)
    return 1 if n else 0


# 이 저장소 규칙: **수치를 보고할 때마다 그 검사가 무엇을 못 보는지 함께 적는다.**
# "0건" 이 세 번 뚫린 이유가 전부 여기에 안 적혀 있던 사각이었다.
_POLLUTE_BLIND_SPOTS = """
  ── 이 검사가 못 보는 것 (0건이어도 남는 사각) ──
   · `data` 밖: item 최상위 `title`·`stale_reason`·`change_summary` 는 훑지 않는다.
   · PATS 에 없는 축: `SFR-`·`R1 v`·`SCR-*` 같은 외부 문서 코드 참조와 `PR #123` 은
     **패턴이 아예 없어** 몇 건이 있든 0건으로 나온다.
     (`ADR-NNN`·`API-NNN` 등은 LogiCraft ITEM ID 라 참조 자체가 정당하다 — 패턴을 만들면
      오탐 폭증이므로 일부러 두지 않는다. `[폐기]` 표기도 사양 정보라 오염이 아니다.)
   · 업로드된 정적 렌더 HTML 파일 자체는 대상이 아니다(본문의 미러라 따로 굳는다).
     → 렌더는 `wf` 로 생성물과 대조하거나 HTML 을 직접 grep 할 것.
   · 맨 심볼명(`LabelController` 등)은 '코드파일명' 패턴이 확장자를 요구해 안 잡힌다.
     → 그 축은 별도 `symbol` 검사 담당.
"""


# ── ③ 한글 손상 (희귀 음절) ───────────────────────────────────────────
# 이스케이프로 한글을 쓰면 '깨진 글자'가 아니라 '읽히는 다른 한글'이 되어
# 육안·알려진오타 검색을 통과한다. 1회 등장 음절을 문맥과 함께 봐야 잡힌다.
def cmd_hangul(staging, only=None):
    files = all_item_files(staging)
    ids = set(only.split(",")) if only else None
    docs = []
    for f in files:
        d = L(f)
        if ids and d["id"] not in ids:
            continue
        for p, t in texts(d):
            docs.append((d["id"], p, t))
    allt = " ".join(t for _, _, t in docs)
    syl = collections.Counter(ch for ch in allt if "가" <= ch <= "힣")
    rare = sorted([c for c, n in syl.items() if n == 1])
    print(f"=== 한글 손상 검사 — 음절 {sum(syl.values()):,} / 고유 {len(syl)} / 1회 등장 {len(rare)}개 ===")
    print("  (아래를 눈으로 훑어 어색한 낱말이 있는지 본다. 정상 어휘가 대부분이다.)\n")
    for ch in rare:
        for sid, p, t in docs:
            i = t.find(ch)
            if i >= 0:
                print(f"   '{ch}' {sid:11s} …{t[max(0,i-16):i+16]}…")
                break
    return 0


# ── ④ 와이어프레임 대조 ───────────────────────────────────────────────
# ⚠️ 구조 지표(카드·모달 수)만 보면 문자 치환을 놓친다.
#    실제로 '추가·삭제할' → '추가/삭제할' 오전사가 음절 수 동일이라 통과한 적 있다.
#    반드시 정규화 후 문자 단위로 비교할 것.
def _core(h):
    h = re.sub(r'<link[^>]*wireframe\.css[^>]*>', '', h)
    h = re.sub(r'<head>.*?</head>', '', h, flags=re.S)
    h = re.sub(r'<!DOCTYPE[^>]*>', '', h, flags=re.I)
    h = re.sub(r'<html[^>]*>|</html>|<body[^>]*>|</body>', '', h)
    h = re.sub(r'placeholder=""', '', h)   # 서버가 빈 placeholder 제거
    h = html.unescape(h)                    # 서버가 엔티티 디코딩
    return re.sub(r'\s', '', h)             # 공백 축약 흡수

def cmd_wf(gen, verify_dir):
    bad = []
    files = sorted(glob.glob(os.path.join(verify_dir, "SCREEN-*", "main.html")))
    for f in files:
        sid = os.path.basename(os.path.dirname(f))
        src = os.path.join(gen, f"{sid}.html")
        if not os.path.exists(src):
            print(f"  {sid:12s} ⚠️ 생성 원본 없음"); continue
        a = _core(open(src, encoding="utf-8").read())
        b = _core(open(f, encoding="utf-8").read())
        ok = a == b
        if not ok: bad.append(sid)
        print(f"  {sid:12s} {'✅ 내용 일치' if ok else f'❌ 불일치 ({len(a)} vs {len(b)})'}")
    print(f"\n  대상 {len(files)}건 — " + ("✅ 변형·절단 0건" if not bad else f"❌ {bad}"))
    print("  (차이 허용: 서버 sanitize 의 엔티티 디코딩·빈 placeholder 제거·공백 축약)")
    return 1 if bad else 0


# ── ⑤ 심볼 오염 (화면 사양이 구현 심볼을 지목하는가) ──────────────────
# ⚠️ `pollute` 의 '코드파일명' 패턴은 **확장자를 요구**해서 맨 심볼명을 구조적으로 못 잡는다.
#    실제로 `VideoListPage`·`LabelController`·`PortalLabelService` 가 오염 0건을 통과했다.
#    제3자는 그 이름을 알 수 없으므로 화면 사양에 있으면 안 된다.
#
# 오탐을 가르는 기준은 **어느 타입의 ITEM 인가**다:
#   · ui_component 본문의 PascalCase = 그 카탈로그가 **정의하는 컴포넌트명 자체** → 정당
#   · screen_spec 본문의 PascalCase   = 카탈로그에 등재된 이름을 참조할 때만 정당,
#                                       미등재면 구현 심볼 지목 → 오염
# 기술 표준·라이브러리 이름은 사양이다(구현 심볼이 아니라 어휘).
# 웹 표준 API 는 특히 오탐이 잦다 — 제3자도 아는 이름이므로 지목이 아니다.
SYMBOL_ALLOW = {
    "PostgreSQL", "JavaScript", "TypeScript", "FFmpeg", "WebSocket",
    "OpenAPI", "LogiCraft", "MediaSource", "WebClient",
    # 웹 표준 API
    "ResizeObserver", "IntersectionObserver", "MutationObserver",
    "FileReader", "FormData", "AbortController", "URLSearchParams",
    "SharedWorker", "ServiceWorker", "IndexedDB",
    # 라이브러리·제품명
    "TanStack", "ReactQuery", "OpenLayers",
    # 아이콘 이름 — 어떤 글리프를 보일지 지정하는 **설계 어휘**라 구현 심볼이 아니다.
    # ⚠️ 이 축은 정규식으로 일관되게 잡히지 않는다(낙타혹 2개 규칙상 `Hourglass`·`XCircle`·
    #    `CheckCircle2` 는 애초에 안 걸리고 `ClipboardCheck` 만 걸린다). 한쪽만 지우면
    #    같은 성격의 표기가 화면마다 달라지므로 통과시키는 쪽으로 통일한다.
    "ClipboardCheck", "CheckCircle", "AlertTriangle", "ChevronRight", "ChevronLeft",
}
SYMBOL_PAT = re.compile(r"\b[A-Z][a-z]+(?:[A-Z][a-z]+)+\b")   # 낙타혹 2개 이상


def _symbol_scan_targets(dd):
    """검사할 산문을 (경로, 텍스트) 로 전부 모은다.
    ⚠️ 최상위 필드만 보면 안 된다 — `sections[].description` 과 `static_renders` 안에
       `LabelController`·`PortalSam2RemovedTest`·`DarkToolbar` 가 살아남은 실사고가 있다.
       정적 렌더는 본문의 미러라 같은 오염이 복제되고, 본문만 고치면 미러가 뒤에 남는다."""
    out = []
    for key in ("purpose", "description", "notes"):
        v = dd.get(key)
        if isinstance(v, str): out.append((key, v))
    for i, s in enumerate(dd.get("sections") or []):
        v = s.get("description")
        if isinstance(v, str): out.append((f"sections[{i}].description", v))
    for r in dd.get("static_renders") or []:
        rid = r.get("id", "?")
        v = r.get("description")
        if isinstance(v, str): out.append((f"renders[{rid}].description", v))
        for j, s in enumerate(r.get("sections") or []):
            v = s.get("description")
            if isinstance(v, str): out.append((f"renders[{rid}].sections[{j}]", v))
    return out

def _ui_catalog(*stagings):
    """ui_component 로 등재된 이름 = 설계 어휘. 어느 스테이징에 있든 모아 쓴다."""
    names = set()
    for st in stagings:
        for f in glob.glob(os.path.join(st, "ui_component", "_raw", "*.json")):
            n = (L(f).get("data") or {}).get("name")
            if n: names.add(n)
    return names

def cmd_symbol(staging, *ui_stagings):
    cat = _ui_catalog(staging, *ui_stagings)
    if not cat:
        print("  ⚠️ ui_component 카탈로그를 찾지 못했다 — 등재 이름을 오탐으로 걸러낼 수 없어"
              "\n     결과가 과다 보고된다. ui_component 를 받아둔 스테이징 경로를 인자로 더 줄 것.")
    files = sorted(glob.glob(os.path.join(staging, "screen_spec", "_raw", "*.json")))
    n = 0
    print(f"=== 심볼 오염 검사 — 화면 {len(files)}건 / 카탈로그 등재 {len(cat)}종 ===")
    for f in files:
        d = L(f); dd = d.get("data") or {}
        for key, t in _symbol_scan_targets(dd):
            for m in SYMBOL_PAT.finditer(t):
                s = m.group(0)
                if s in SYMBOL_ALLOW or s in cat: continue
                n += 1
                print(f"  {d['id']:12s} [{key}] «{s}»")
                print(f"       …{t[max(0, m.start()-60):m.end()+60]}…")
    print("\n  " + ("✅ 심볼 오염 0건" if n == 0 else f"❌ {n}건"))
    return 1 if n else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(2)
    c = sys.argv[1]
    if c == "loss":     sys.exit(cmd_loss(sys.argv[2], sys.argv[3]))
    if c == "uiloss":   sys.exit(cmd_uiloss(sys.argv[2], sys.argv[3]))
    if c == "pollute":
        sys.exit(cmd_pollute(sys.argv[2], skip_brownfield="--no-brownfield" in sys.argv[3:]))
    if c == "hangul":   sys.exit(cmd_hangul(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None))
    if c == "wf":       sys.exit(cmd_wf(sys.argv[2], sys.argv[3]))
    if c == "symbol":   sys.exit(cmd_symbol(sys.argv[2], *sys.argv[3:]))
    print(__doc__); sys.exit(2)
