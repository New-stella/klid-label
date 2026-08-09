#!/usr/bin/env python3
"""LogiCraft screen_spec -> 와이어프레임 HTML 결정론적 생성기.

data.sections 만 입력으로 받아 wf-* 표준 클래스로 렌더한다.
- 섹션의 role 을 실제 배치로 반영 (아래 § 배치 규칙)
- 섹션당 wf-card + 메타 칩(role/layout/API) + '섹션 상세' 링크
- body 끝에 섹션마다 wf-sec-detail-modal (CSS-only :target)
- wireframe.css link 는 넣지 않는다(서버 sanitize 가 자동 주입)
LLM 개입 0 — 같은 입력이면 항상 같은 출력(바이트 단위 동일).

§ 배치 규칙 (role -> 구역, 결정론적 매핑)
  header                  → 상단 밴드 (가로 전체)
  hero / filter           → 상단 밴드 2 (가로 전체, header 아래)
  navigation | main | side → 본문 행 (좌 레일 / 중앙 / 우 패널 3열)
  footer                  → 하단 밴드 (가로 전체)
  modal                   → 오버레이 구역 (본문 밖, 떠 있는 표면으로 구분)
  role 없음 / 미지 값      → main 취급 (섹션 누락 금지)
같은 role 이 여러 개면 원래 배열 순서대로 그 구역 안에 쌓는다.
빈 구역·빈 열은 렌더하지 않는다.

§ 스타일 제약
  색/테두리/그림자/폰트는 wf-* 클래스 또는 var(--wf-*) 만.
  레이아웃 수치(flex/gap/min-width/max-width 등)만 inline style 로 준다.
  셸 클래스(wf-app/wf-topbar/wf-body/wf-sidenav/wf-footer)는 쓰지 않는다
  — 이 산출물은 화면 '본문'이고 전역 셸이 따로 감싼다.

§ 알려진 한계
  role=navigation 이 좌측 세로 레일과 본문 위 가로 옵션바 두 용도로 쓰이는데
  현재 스펙 데이터(role/layout)로는 구분이 불가능하다. 추측으로 가르지 않고
  전부 좌측 레일로 렌더한다. 구분이 필요하면 스펙에 축을 추가해야 한다.
"""
import json, sys, html, os, glob

CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳"

def num(i):
    return CIRCLED[i] if i < len(CIRCLED) else f"({i+1})"

def e(s):
    return html.escape(str(s if s is not None else ""), quote=True)

VARIANT = {"primary": " wf-btn-primary", "secondary": " wf-btn-secondary",
           "destructive": " wf-btn-destructive", "outline": " wf-btn-outline",
           "ghost": " wf-btn-ghost"}

def state_chip(c):
    st = c.get("state")
    if not st or st == "default":
        return ""
    tone = {"error": " wf-badge-danger", "loading": " wf-badge-warning",
            "disabled": "", "readonly": "", "hidden": ""}.get(st, "")
    return f'<span class="wf-badge{tone}">{e(st)}</span>'

def field(c, inner):
    lab = c.get("label") or c.get("custom_name") or ""
    help_ = c.get("validation") or ""
    h = ['<div class="wf-field">']
    if lab:
        h.append(f'<label class="wf-label">{e(lab)}</label>')
    h.append(inner)
    if help_:
        h.append(f'<p class="wf-help">{e(help_)}</p>')
    h.append("</div>")
    return "".join(h)

def render_component(c):
    if not isinstance(c, dict):
        return f'<span class="wf-muted">{e(c)}</span>'
    t = (c.get("type") or "Custom")
    lab = c.get("label") or c.get("custom_name") or ""
    ph = c.get("placeholder") or ""
    opts = c.get("options") or []
    cols = c.get("columns") or []
    chip = state_chip(c)

    if t == "Heading":
        return f'<span class="wf-bold">{e(lab)}</span>{chip}'
    if t in ("Text", "Skeleton", "Tooltip", "Icon", "Image", "Avatar"):
        return f'<span class="wf-muted">{e(lab or t)}</span>{chip}'
    if t == "Divider":
        return '<div class="wf-divider"></div>'
    if t == "Badge":
        return f'<span class="wf-badge">{e(lab)}</span>{chip}'
    if t == "Button":
        v = VARIANT.get(c.get("variant") or "", "")
        api = c.get("triggers_api")
        extra = f'<span class="wf-badge wf-badge-primary">{e(api)}</span>' if api else ""
        return f'<button class="wf-btn{v}">{e(lab)}</button>{chip}{extra}'
    if t == "IconButton":
        return f'<button class="wf-icon-btn" aria-label="{e(lab)}">{e(lab[:1] or "•")}</button>{chip}'
    if t == "Link":
        return f'<a href="#_" class="wf-btn wf-btn-ghost">{e(lab)}</a>{chip}'
    if t == "Input":
        return field(c, f'<input class="wf-input" placeholder="{e(ph)}" />') + chip
    if t == "Textarea":
        return field(c, f'<textarea class="wf-textarea" placeholder="{e(ph)}"></textarea>') + chip
    if t in ("Select", "DatePicker"):
        o = "".join(f"<option>{e(x)}</option>" for x in (opts or [ph or "선택"]))
        return field(c, f'<select class="wf-select">{o}</select>') + chip
    if t == "RadioGroup":
        r = "".join(f'<label class="wf-radio"><input type="radio" name="{e(lab)}" /> {e(x)}</label>'
                    for x in (opts or ["옵션"]))
        return field(c, f'<div class="wf-cluster">{r}</div>') + chip
    if t == "Checkbox":
        return f'<label class="wf-checkbox"><input type="checkbox" /> {e(lab)}</label>{chip}'
    if t == "Switch":
        return f'<label class="wf-checkbox"><input type="checkbox" /> {e(lab)}</label>{chip}'
    if t == "FileUpload":
        return field(c, f'<input class="wf-input" type="file" />') + chip
    if t == "Table":
        th = "".join(f"<th>{e(x)}</th>" for x in (cols or ["컬럼"]))
        td = "".join("<td>···</td>" for _ in (cols or ["컬럼"]))
        return (f'<div class="wf-stack"><span class="wf-bold">{e(lab)}</span>'
                f'<table class="wf-table"><thead><tr>{th}</tr></thead>'
                f'<tbody><tr>{td}</tr><tr>{td}</tr></tbody></table></div>{chip}')
    if t in ("List", "Tree", "Timeline", "Menu", "Accordion"):
        li = "".join(f"<li>{e(x)}</li>" for x in (opts or ["항목 1", "항목 2"]))
        return f'<div class="wf-stack"><span class="wf-bold">{e(lab)}</span><ul class="wf-list">{li}</ul></div>{chip}'
    if t == "Tabs":
        tb = "".join(f'<div class="wf-tab{" active" if i==0 else ""}">{e(x)}</div>'
                     for i, x in enumerate(opts or [lab or "탭"]))
        return f'<div class="wf-tabs">{tb}</div>{chip}'
    if t == "Card":
        return f'<div class="wf-card"><h3 class="wf-card-title">{e(lab)}</h3></div>{chip}'
    if t == "KeyValue":
        rows = "".join(f'<div class="wf-row"><span class="wf-muted">{e(x)}</span><span>···</span></div>'
                       for x in (opts or [lab or "항목"]))
        return f'<div class="wf-stack">{rows}</div>{chip}'
    if t == "Stat":
        return (f'<div class="wf-stat"><div class="wf-stat-label">{e(lab)}</div>'
                f'<div class="wf-stat-value">···</div></div>{chip}')
    if t == "Chart":
        bars = "".join(f'<div class="wf-chart-bar" style="height:{h}%"></div>' for h in (40, 70, 55, 85, 30))
        return f'<div class="wf-stack"><span class="wf-bold">{e(lab)}</span><div class="wf-chart">{bars}</div></div>{chip}'
    if t == "Alert":
        return f'<div class="wf-alert wf-alert-info">{e(lab)}</div>{chip}'
    if t == "Toast":
        return f'<div class="wf-alert wf-alert-success">{e(lab)}</div>{chip}'
    if t == "Dialog":
        return f'<div class="wf-dialog"><div class="wf-dialog-title">{e(lab)}</div></div>{chip}'
    if t == "Drawer":
        return f'<div class="wf-card"><h3 class="wf-card-title">{e(lab)} (drawer)</h3></div>{chip}'
    if t == "Progress":
        return f'<div class="wf-stack"><span class="wf-muted">{e(lab)}</span><div class="wf-chart"><div class="wf-chart-bar" style="height:100%"></div></div></div>{chip}'
    if t == "Pagination":
        pg = "".join(f'<button class="wf-page-btn{" active" if i==0 else ""}">{i+1}</button>' for i in range(3))
        return f'<div class="wf-pagination">{pg}</div>{chip}'
    if t == "Stepper":
        st = "".join(f'<span class="wf-step-num{" active" if i==0 else ""}">{i+1}</span>' for i in range(len(opts) or 3))
        return f'<div class="wf-stepper">{st}</div>{chip}'
    if t == "Breadcrumb":
        return f'<div class="wf-breadcrumb"><span>{e(lab)}</span></div>'
    # Custom / Stack / Grid / 기타
    nm = c.get("custom_name") or lab or t
    note = c.get("note") or ""
    tip = f' <span class="wf-faint">{e(note[:60])}</span>' if note else ""
    return f'<span class="wf-muted">{e(nm)}</span>{tip}{chip}'


# ── 배치 ────────────────────────────────────────────────────────────────
# role -> 구역 키. 미등록/누락 role 은 main 으로 흡수한다(섹션 누락 금지).
ROLE_ZONE = {
    "header": "header",
    "hero": "top", "filter": "top",
    "navigation": "nav", "main": "main", "side": "side",
    "footer": "footer", "modal": "modal",
}
ZONE_KEYS = ("header", "top", "nav", "main", "side", "footer", "modal", "dropped")

# 이름이 이 접두로 시작하는 섹션 = 채택하지 않기로 한 구성.
# role 은 그대로 들고 있으므로 그냥 배치하면 폐기안이 실제 화면처럼 그려진다.
# → 본문 배치에서 빼고 하단 별도 구역으로 옮긴다. 삭제하지 않는다(카드·상세 모달 모두 유지).
DROPPED_PREFIX = "[폐기]"

# 열 비율 — 좌 레일은 좁은 고정폭, 중앙은 남는 폭 전부, 우 패널은 중간 고정폭.
COL_STYLE = {
    "nav": "flex:0 0 168px;min-width:168px;max-width:168px",
    "main": "flex:1 1 auto;min-width:0",
    "side": "flex:0 0 320px;min-width:320px;max-width:320px",
}
COL_LABEL = {"nav": "좌측 레일", "main": "본문", "side": "우측 패널"}
# 우측 패널이 이 개수 이상이면 '실제로는 탭일 수 있다' 주석을 단다.
SIDE_TAB_HINT_MIN = 3
# 밴드(가로 전체) 구역은 컴포넌트를 가로로 늘어놓아 '바'처럼 보이게 한다.
HORIZONTAL_ZONES = ("header", "top", "footer")


def is_dropped(s):
    return (s.get("name") or "").strip().startswith(DROPPED_PREFIX)


def zone_of(s):
    if is_dropped(s):
        return "dropped"
    return ROLE_ZONE.get((s.get("role") or "").strip().lower(), "main")


def group_sections(secs):
    """원래 배열 순서를 유지한 채 구역별로 (index, section) 를 모은다."""
    g = {k: [] for k in ZONE_KEYS}
    for i, s in enumerate(secs):
        g[zone_of(s)].append((i, s))
    return g


def render_section(i, s, horizontal=False, card_style=""):
    """섹션 1개를 wf-card 로 렌더. 마크업 구성은 구역과 무관하게 동일하다."""
    nm = s.get("name") or f"섹션 {i+1}"
    st = f' style="{card_style}"' if card_style else ""
    out = [f'<div class="wf-card"{st}>']
    out.append('  <div class="wf-cluster">')
    out.append(f'    <h2 class="wf-card-title">{num(i)} {e(nm)}</h2>')
    if s.get("role"):
        out.append(f'    <span class="wf-badge">role: {e(s["role"])}</span>')
    if s.get("layout"):
        out.append(f'    <span class="wf-badge">layout: {e(s["layout"])}</span>')
    for a in (s.get("references_apis") or []):
        out.append(f'    <span class="wf-badge wf-badge-primary">{e(a)}</span>')
    out.append(f'    <a href="#wf-sec-{i+1}" class="wf-detail-btn">섹션 상세</a>')
    out.append('  </div>')
    # 폐기 컴포넌트는 그리지 않는다 — 섹션과 같은 이유다.
    # 화면에 없을 컨트롤을 그리면 이 그림을 보고 만드는 사람이 그걸 만든다.
    # 기록은 아래 섹션 상세의 구성 목록이 그대로 들고 있으므로 사라지지 않는다.
    def _live(c):
        lab = c.get("label") if isinstance(c, dict) else str(c)
        return DROPPED_PREFIX not in (lab or "")
    comps = [c for c in (s.get("components") or []) if _live(c)]
    if comps:
        if horizontal:
            out.append('  <div class="wf-cluster wf-mt" style="align-items:flex-end">')
        else:
            out.append('  <div class="wf-stack wf-mt">')
        for c in comps:
            out.append(f'    {render_component(c)}')
        out.append('  </div>')
    out.append('</div>')
    return out


def indent(lines, pad):
    return [pad + l for l in lines]


def build(item):
    d = item.get("data") or {}
    title = d.get("title") or item.get("title") or item["id"]
    purpose = d.get("purpose") or ""
    route = d.get("route") or ""
    secs = [s for s in (d.get("sections") or []) if isinstance(s, dict)]
    g = group_sections(secs)

    out = ['<!DOCTYPE html>', '<html lang="ko">', '<head><meta charset="utf-8"></head>',
           '<body>', '  <main class="wf-content">']
    # page header
    out.append('    <div class="wf-page-header">')
    out.append(f'      <div class="wf-breadcrumb"><span>{e(item["id"])}</span><span class="sep">/</span><span>{e(title)}</span></div>')
    out.append(f'      <h1 style="white-space:nowrap">{e(title)}</h1>')
    out.append('    </div>')
    # 목적 서술·route 는 page-header 의 flex 형제로 두면 h1 폭을 잠식해 제목이 세로로 쪼개진다.
    # 내용은 그대로 두고 헤더 아래 한 줄로 분리한다.
    head = purpose if len(purpose) <= 400 else purpose[:400] + "…"
    if route:
        head = f"{head} · route {route}" if head else f"route {route}"
    if head:
        out.append(f'    <p class="wf-muted" style="margin:-8px 0 16px">{e(head)}</p>')

    # ① 상단 밴드 (header) / ② 상단 밴드 2 (hero·filter) — 가로 전체
    for zone in ("header", "top"):
        for i, s in g[zone]:
            out += indent(render_section(i, s, horizontal=True), "    ")

    # ③ 본문 행 — 좌 레일 / 중앙 / 우 패널
    cols = [k for k in ("nav", "main", "side") if g[k]]
    if cols:
        multi = len(cols) > 1
        row_style = "align-items:flex-start;gap:12px"
        if multi:
            row_style += ";min-height:520px"
        out.append(f'    <div class="wf-row" style="{row_style}">')
        for k in cols:
            cs = COL_STYLE[k] + ";display:flex;flex-direction:column;gap:0"
            if multi:
                cs += ";align-self:stretch"
            out.append(f'      <div style="{cs}">')
            if multi:
                out.append(f'        <p class="wf-faint" style="margin:0 0 4px">{COL_LABEL[k]}</p>')
            # 우측 패널이 여러 건이면 실제로는 탭으로 나뉘어 한 번에 하나만 보이는 경우가 있다.
            # 탭 소속을 표현하는 필드가 스펙에 없어 묶을 근거가 없으므로, 추측하지 않고
            # '그림이 실제보다 길어 보일 수 있다'는 사실만 알린다.
            if k == "side" and len(g[k]) >= SIDE_TAB_HINT_MIN:
                out.append('        <p class="wf-muted" style="margin:0 0 8px">'
                           '이 열의 항목들은 실제 화면에서는 탭으로 나뉘어 한 번에 하나만 보일 수 있다. '
                           '아래는 전부 펼친 그림이라 실제보다 길다 — 탭 구성은 각 섹션 설명 참조.</p>')
            for i, s in g[k]:
                out += indent(render_section(i, s), "        ")
            out.append('      </div>')
        out.append('    </div>')

    # ④ 하단 밴드 (footer) — 가로 전체, 낮은 띠
    for i, s in g["footer"]:
        out += indent(render_section(i, s, horizontal=True), "    ")

    # ⑤ 오버레이 구역 (modal) — 본문 밖, 화면 위에 뜨는 표면
    if g["modal"]:
        out.append('    <div class="wf-divider"></div>')
        out.append('    <div class="wf-cluster" style="margin-bottom:8px">')
        out.append('      <span class="wf-badge wf-badge-warning">오버레이</span>')
        out.append('      <span class="wf-muted">화면 위에 겹쳐 뜨는 표면 — 본문 흐름에는 자리를 차지하지 않는다</span>')
        out.append('    </div>')
        out.append('    <div style="padding:24px;border:2px dashed var(--wf-border-strong);'
                   'border-radius:var(--wf-radius-lg);background:var(--wf-bg-alt)">')
        for i, s in g["modal"]:
            out += indent(render_section(
                i, s, card_style="max-width:560px;margin-left:auto;margin-right:auto"), "      ")
        out.append('    </div>')

    # ⑥ 두지 않기로 한 구성 — **그리지 않고 한 줄로만 알린다**
    # 이 산출물의 목적은 "화면이 어떻게 생겼는지 미리 보기"다. 채택하지 않은 안을 카드로 그리면
    # 옅게 처리해도 자리를 차지하고 "이건 뭐지" 하는 판단 비용을 만든다.
    # 결정 기록은 사양(ITEM 의 sections 배열)이 갖고 있으므로 그림이 대신 짊어질 이유가 없다.
    # 다만 사양의 섹션 수와 그림의 카드 수가 달라 누락으로 오해받는 것만 한 줄로 막는다.
    if g["dropped"]:
        names = " · ".join(
            e((s.get("name") or "").replace(DROPPED_PREFIX, "").strip()) for _, s in g["dropped"])
        out.append('    <div class="wf-divider"></div>')
        out.append(f'    <p class="wf-faint">두지 않기로 한 구성 {len(g["dropped"])}건은 '
                   f'그림에 포함하지 않았다 — {names}. 사양 본문에는 기록으로 남아 있다.</p>')

    out.append('  </main>')

    # 섹션 상세 모달 — 그려진 섹션만. 폐기분은 카드가 없어 열 수단이 없다.
    for i, s in enumerate(secs):
        if (s.get("name") or "").startswith(DROPPED_PREFIX):
            continue
        nm = s.get("name") or f"섹션 {i+1}"
        out.append(f'  <div id="wf-sec-{i+1}" class="wf-sec-detail-modal">')
        out.append('    <a href="#_" class="wf-sec-detail-modal-backdrop"></a>')
        out.append('    <div class="wf-sec-detail-modal-box">')
        out.append('      <a href="#_" class="wf-sec-detail-modal-close">×</a>')
        out.append(f'      <h3 class="wf-sec-detail-modal-title">{num(i)} {e(nm)}</h3>')
        desc = s.get("description") or ""
        if desc:
            out.append(f'      <div class="wf-sec-detail-modal-section"><h4>설명</h4><p>{e(desc)}</p></div>')
        comps = [c for c in (s.get("components") or []) if isinstance(c, dict)]
        if comps:
            li = []
            for c in comps:
                t = c.get("type") or "Custom"
                lab = c.get("label") or c.get("custom_name") or ""
                bits = []
                if c.get("triggers_api"): bits.append(f'→ {c["triggers_api"]}')
                if c.get("state") and c["state"] != "default": bits.append(str(c["state"]))
                if c.get("binds_to"): bits.append(f'binds {c["binds_to"]}')
                if c.get("validation"): bits.append(f'검증: {c["validation"]}')
                suffix = (" · " + " · ".join(str(b) for b in bits)) if bits else ""
                li.append(f"<li>{e(t)}({e(lab)}){e(suffix)}</li>")
            out.append(f'      <div class="wf-sec-detail-modal-section"><h4>Components</h4><ul>{"".join(li)}</ul></div>')
        apis = s.get("references_apis") or []
        if apis:
            out.append(f'      <div class="wf-sec-detail-modal-section"><h4>API</h4><ul>{"".join(f"<li>{e(a)}</li>" for a in apis)}</ul></div>')
        feats = s.get("references_features") or []
        if feats:
            out.append(f'      <div class="wf-sec-detail-modal-section"><h4>Feature</h4><ul>{"".join(f"<li>{e(x)}</li>" for x in feats)}</ul></div>')
        out.append('    </div>')
        out.append('  </div>')

    out.append('</body>')
    out.append('</html>')
    return "\n".join(out)


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    os.makedirs(dst, exist_ok=True)
    n = 0
    for f in sorted(glob.glob(os.path.join(src, "SCREEN-*.json"))):
        item = json.load(open(f, encoding="utf-8"))["item"]
        h = build(item)
        open(os.path.join(dst, f"{item['id']}.html"), "w", encoding="utf-8").write(h)
        n += 1
        print(f"  {item['id']:12s} {len(h):6,d} bytes  sections={len(item['data'].get('sections') or [])}")
    print(f"\n생성 {n}건")
