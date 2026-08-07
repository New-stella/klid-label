#!/usr/bin/env python3
"""LogiCraft screen_spec -> 와이어프레임 HTML 결정론적 생성기.

data.sections 만 입력으로 받아 wf-* 표준 클래스로 렌더한다.
- 섹션당 wf-card + 메타 칩(role/layout/API) + '섹션 상세' 링크
- body 끝에 섹션마다 wf-sec-detail-modal (CSS-only :target)
- wireframe.css link 는 넣지 않는다(서버 sanitize 가 자동 주입)
LLM 개입 0 — 같은 입력이면 항상 같은 출력.
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


def build(item):
    d = item.get("data") or {}
    title = d.get("title") or item.get("title") or item["id"]
    purpose = d.get("purpose") or ""
    route = d.get("route") or ""
    secs = [s for s in (d.get("sections") or []) if isinstance(s, dict)]

    out = ['<!DOCTYPE html>', '<html lang="ko">', '<head><meta charset="utf-8"></head>',
           '<body class="wf-app">', '  <main class="wf-content">']
    # page header
    out.append('    <div class="wf-page-header">')
    out.append(f'      <div class="wf-breadcrumb"><span>{e(item["id"])}</span><span class="sep">/</span><span>{e(title)}</span></div>')
    out.append(f'      <h1>{e(title)}</h1>')
    head = purpose if len(purpose) <= 400 else purpose[:400] + "…"
    if route:
        head = f"{head} · route {route}" if head else f"route {route}"
    if head:
        out.append(f'      <p class="wf-muted">{e(head)}</p>')
    out.append('    </div>')

    for i, s in enumerate(secs):
        nm = s.get("name") or f"섹션 {i+1}"
        out.append('    <div class="wf-card">')
        out.append('      <div class="wf-cluster">')
        out.append(f'        <h2 class="wf-card-title">{num(i)} {e(nm)}</h2>')
        if s.get("role"):
            out.append(f'        <span class="wf-badge">role: {e(s["role"])}</span>')
        if s.get("layout"):
            out.append(f'        <span class="wf-badge">layout: {e(s["layout"])}</span>')
        for a in (s.get("references_apis") or []):
            out.append(f'        <span class="wf-badge wf-badge-primary">{e(a)}</span>')
        out.append(f'        <a href="#wf-sec-{i+1}" class="wf-detail-btn">섹션 상세</a>')
        out.append('      </div>')
        comps = [c for c in (s.get("components") or [])]
        if comps:
            out.append('      <div class="wf-stack wf-mt">')
            for c in comps:
                out.append(f'        {render_component(c)}')
            out.append('      </div>')
        out.append('    </div>')
    out.append('  </main>')

    # 섹션 상세 모달
    for i, s in enumerate(secs):
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
