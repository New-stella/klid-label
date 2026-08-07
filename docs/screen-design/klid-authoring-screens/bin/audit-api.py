#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""api_endpoint ITEM 전수 감사 — 결정론적·LLM 0.

  python3 bin/audit-api.py endpoints  <api스테이징> [repo루트]
  python3 bin/audit-api.py roles      <api스테이징> [repo루트]
  python3 bin/audit-api.py symbol     <api스테이징>
  python3 bin/audit-api.py shape      <api스테이징>
  python3 bin/audit-api.py screenrefs <api스테이징> <screen스테이징>
  python3 bin/audit-api.py gate       <api스테이징> [repo루트]
  python3 bin/audit-api.py notify     <api스테이징>

판정 기준은 verify-items.py 와 같다 — "이 문장이 이 저장소를 모르는 제3자에게도 참인가?"
다만 축이 다르다. 화면은 sections/components 2축이지만 API 는 계약면(경로·메서드·권한·
파라미터·응답코드)이 축이고, 그 축은 **실제 구현과 대조 가능**하다는 점이 결정적이다.
"""
import glob
import json
import os
import re
import sys

REPO_DEFAULT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))


def L(f):
    d = json.load(open(f, encoding="utf-8"))
    return d.get("item", d)


def api_items(staging):
    out = {}
    for f in sorted(glob.glob(os.path.join(staging, "api_endpoint", "_raw", "*.json"))):
        it = L(f)
        out[it["id"]] = it
    return out


def norm(p):
    """경로변수명은 계약이 아니라 표기다 — {rawSn} 과 {id} 를 같은 경로로 본다."""
    return re.sub(r"\{[^}]*\}", "{}", (p or "").rstrip("/")) or "/"


# ── 구현 측 엔드포인트 수집 ────────────────────────────────────────────
PAT_CLS = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"')
PAT_M = re.compile(r"@(Get|Post|Put|Patch|Delete)Mapping\b(?:\(\s*([^)]*)\))?")
# TUS 는 HEAD·OPTIONS 를 쓰는데 전용 어노테이션이 없어 @RequestMapping(method=…) 로 붙는다.
# 이 형태를 안 보면 구현돼 있는 엔드포인트가 "정의서에만 있음"으로 오보고된다.
PAT_M_RM = re.compile(r"@RequestMapping\(\s*([^)]*RequestMethod\.[A-Z]+[^)]*)\)")
PAT_PRE = re.compile(r'@PreAuthorize\(\s*"([^"]*)"')


def _mapping_path(args):
    if not args:
        return ""
    m = re.search(r'(?:value\s*=\s*)?"([^"]*)"', args)
    return m.group(1) if m else ""


def backend_endpoints(repo):
    """(method, path) → {'file':..., 'roles': set|None}

    @PreAuthorize 는 메서드 바로 위 또는 클래스 선언 위에 붙는다. 매핑 어노테이션 앞
    600자를 보고 가장 가까운 것을 취하되, 없으면 클래스 레벨을 상속한다."""
    out = {}
    for f in glob.glob(os.path.join(repo, "backend/src/main/java/**/*Controller.java"), recursive=True):
        s = open(f, encoding="utf-8").read()
        m = PAT_CLS.search(s)
        base = m.group(1) if m else ""
        cls_pre = None
        head = s[: s.find("public class")] if "public class" in s else ""
        mp = PAT_PRE.search(head)
        if mp:
            cls_pre = mp.group(1)
        hits = [(mm.start(), mm.group(1).upper(), _mapping_path(mm.group(2))) for mm in PAT_M.finditer(s)]
        for mm in PAT_M_RM.finditer(s):
            args = mm.group(1)
            for verb in re.findall(r"RequestMethod\.([A-Z]+)", args):
                hits.append((mm.start(), verb, _mapping_path(args)))
        starts = sorted(h[0] for h in hits)
        for start, meth, sub in hits:
            path = base + sub
            # ⚠ 이 코드베이스는 @PreAuthorize 를 매핑 어노테이션 **뒤**에 단다.
            #   앞만 보면 직전 메서드 것을 가져와 역할이 통째로 한 칸 밀린다
            #   (실제로 submit=WORKER/start=REVIEWER 가 서로 바뀌어 보고됐다).
            #   같은 메서드의 어노테이션은 빈 줄 없이 붙어 있으므로,
            #   앞은 직전 빈 줄까지 · 뒤는 시그니처(public) 전까지만 본다.
            nxt = min([x for x in starts if x > start] or [len(s)])
            back = s[:start]
            cut = back.rfind("\n\n")
            win_back = back[cut:] if cut >= 0 else back
            fwd = s[start:nxt]
            sig = fwd.find("public ")
            win_fwd = fwd[:sig] if sig > 0 else fwd
            pres = PAT_PRE.findall(win_back + win_fwd)
            expr = pres[-1] if pres else cls_pre
            body = ("@RequestBody" in win_fwd or "MultipartFile" in win_fwd
                    or "@RequestPart" in win_fwd or "InputStream" in win_fwd)
            out[(meth, norm(path))] = {"file": os.path.basename(f), "auth": expr,
                                       "path": path, "has_body": body}
    return out


def aiserver_endpoints(repo):
    """FastAPI — main 의 include_router prefix + 라우터의 @router.<verb>("/x")"""
    out = {}
    prefixes = {}
    for f in glob.glob(os.path.join(repo, "ai-server/app/*.py")):
        s = open(f, encoding="utf-8").read()
        for mm in re.finditer(r"include_router\(\s*(\w+)[^)]*?prefix\s*=\s*\"([^\"]*)\"", s, re.S):
            prefixes[mm.group(1)] = mm.group(2)
    for f in glob.glob(os.path.join(repo, "ai-server/app/routers/*.py")):
        mod = os.path.basename(f)[:-3]
        pre = next((v for k, v in prefixes.items() if k in (mod, mod + "_router", "router_" + mod)), None)
        if pre is None:
            pre = next((v for k, v in prefixes.items() if mod in k), "/infer/" + mod)
        s = open(f, encoding="utf-8").read()
        for mm in re.finditer(r"@router\.(get|post|put|patch|delete)\(\s*\"([^\"]*)\"", s):
            full = pre + mm.group(2)
            out[(mm.group(1).upper(), norm(full))] = {"file": os.path.basename(f), "auth": None,
                                                      "path": full, "has_body": mm.group(1) != "get"}
    return out


def cmd_endpoints(staging, repo=REPO_DEFAULT):
    items = api_items(staging)
    impl = {}
    impl.update(backend_endpoints(repo))
    impl.update(aiserver_endpoints(repo))
    spec = {}
    for i, it in items.items():
        spec[(it["data"].get("method", "?").upper(), norm(it["data"].get("path")))] = i
    only_spec = sorted(k for k in spec if k not in impl)
    only_impl = sorted(k for k in impl if k not in spec)
    print(f"=== 경로·메서드 대조 — 정의서 {len(spec)} / 구현 {len(impl)} ===")
    print(f"\n  ① 정의서에만 있음 {len(only_spec)}건 (구현 부재 또는 폐기 잔존)")
    for k in only_spec:
        print(f"     {spec[k]:9s} {k[0]:7s} {k[1]}")
    print(f"\n  ② 구현에만 있음 {len(only_impl)}건 (정의서 누락 — 화면도 참조 못 한다)")
    for k in only_impl:
        print(f"     {'—':9s} {k[0]:7s} {k[1]:55s} {impl[k]['file']}")
    return 1 if (only_spec or only_impl) else 0


ROLE_PAT = re.compile(r"hasAnyRole\(([^)]*)\)|hasRole\(\s*'([^']*)'")


def _impl_roles(expr):
    if not expr:
        return None
    if "permitAll" in expr or "isAuthenticated" in expr:
        return set()
    roles = set()
    for m in ROLE_PAT.finditer(expr):
        if m.group(1):
            roles |= {r.strip().strip("'\"") for r in m.group(1).split(",")}
        elif m.group(2):
            roles.add(m.group(2))
    return roles or None


# SecurityConfig 의 **순서 있는** 매처 사다리. 인가의 1차 원천은 여기이고
# @PreAuthorize 는 그 위에서 더 좁히는 2차 게이트다. 메서드 어노테이션만 보면
# 대부분의 엔드포인트가 "무인가"로 오보고된다(실제로 101건 오탐이 났다).
ALL = {"REVIEWER", "WORKER", "PORTAL_USER"}
PERMIT = "PERMIT_ALL"   # 인증 불요 — 무매칭(None)과 반드시 구분해야 한다
INTERNAL = {"REVIEWER", "WORKER"}
LADDER = [
    ("*", "/v1/auth/role-claim", ALL),
    ("*", "/v1/me", ALL),
    ("*", "/health", PERMIT), ("*", "/actuator/health", PERMIT), ("*", "/actuator/health/**", PERMIT),
    ("*", "/actuator/info", PERMIT), ("*", "/swagger-ui.html", PERMIT), ("*", "/swagger-ui/**", PERMIT),
    ("*", "/v3/api-docs/**", PERMIT), ("*", "/v1/auth/**", PERMIT), ("*", "/v1/portal/auth/**", PERMIT),
    ("*", "/v1/vlm/callback", PERMIT), ("*", "/v1/genai/callback", PERMIT),
    ("*", "/v1/dev/tokens", PERMIT), ("*", "/v1/dev/tokens/**", PERMIT),
    ("*", "/v1/dev/**", {"REVIEWER"}),
    ("*", "/actuator/**", {"REVIEWER"}),
    ("*", "/v1/integration/**", set()), ("*", "/v1/export-api/**", set()),
    ("GET", "/v1/manage/labels", ALL), ("GET", "/v1/manage/labels/**", ALL),
    ("*", "/v1/manage/**", {"REVIEWER"}),
    ("*", "/v1/system/**", {"REVIEWER"}),
    ("*", "/v1/notices", INTERNAL), ("*", "/v1/notices/**", INTERNAL),
    ("*", "/v1/portal/**", {"PORTAL_USER"}),
    ("*", "/v1/**", INTERNAL),
]


def _match(pat, path):
    rx = "^" + re.escape(pat).replace(r"\*\*", ".*").replace(r"\*", "[^/]*") + "$"
    return re.match(rx, path) is not None


def effective_roles(method, path, pre_expr):
    """SecurityConfig 사다리 → 기본 역할집합, @PreAuthorize 가 있으면 교집합으로 좁힌다."""
    base = None
    for m, pat, roles in LADDER:
        if (m == "*" or m == method) and _match(pat, path):
            base = roles
            break
    if base is PERMIT:
        return PERMIT
    if base is None:  # 사다리 무매칭 → anyRequest().authenticated()
        base = ALL
    pre = _impl_roles(pre_expr)
    if pre:
        return base & pre if base else pre
    return base


def cmd_roles(staging, repo=REPO_DEFAULT):
    items = api_items(staging)
    impl = backend_endpoints(repo)
    print("=== 권한 대조 (security[].jwt ↔ SecurityConfig 사다리 ∩ @PreAuthorize) ===")
    bad, skipped = 0, 0
    for i, it in sorted(items.items(), key=lambda kv: int(kv[0].split("-")[1])):
        d = it["data"]
        key = (d.get("method", "?").upper(), norm(d.get("path")))
        if key not in impl:
            skipped += 1
            continue
        want = effective_roles(key[0], impl[key]["path"], impl[key]["auth"])
        got = set()
        for s in d.get("security") or []:
            for v in (s or {}).values():
                got |= set(v or [])
        if want is PERMIT:  # 인증 불요 — 정의서는 security 를 비워야 한다
            if got:
                bad += 1
                print(f"  ❌ {i:9s} 구현 인증불요(permitAll)인데 정의서는 {sorted(got)} — {key[0]} {key[1]}")
            continue
        if want != got:
            bad += 1
            print(f"  ❌ {i:9s} 구현 {sorted(want) or '(차단)'} ≠ 정의서 {sorted(got) or '(없음)'} — {key[0]} {key[1]}")
    print(f"\n  대조 {len(items)-skipped}건 / 구현 부재로 건너뜀 {skipped}건")
    print(f"  {'✅ 불일치 0건' if not bad else f'❌ 불일치 {bad}건'}")
    return 1 if bad else 0


# ── 내부 구현 심볼 오염 ────────────────────────────────────────────────
# 요청·응답 스키마명(…Request/…Response/…Dto)은 계약 어휘라 허용한다.
# 서비스·스텝·잡·러너·컨트롤러 등 내부 구조는 제3자에게 참이 아니다.
IMPL_SUFFIX = r"(Service|Step|Job|Runner|Bridge|Controller|Repository|Recorder|Sweeper|Guard|Gate|Policy|Filter|Listener|Publisher|Client|Config|Mapper|Builder|Hasher|Encoder|Persister)"
SYMBOL_PAT = re.compile(r"\b([A-Z][A-Za-z0-9]*" + IMPL_SUFFIX + r")\b")


def _texts(it):
    out = []

    def rec(v, p=""):
        if isinstance(v, str):
            if len(v) > 1:
                out.append((p, v))
        elif isinstance(v, dict):
            for k, x in v.items():
                rec(x, p + "/" + k)
        elif isinstance(v, list):
            for idx, x in enumerate(v):
                rec(x, f"{p}[{idx}]" if isinstance(x, (dict, list)) else p)

    rec(it.get("data") or {})
    return [(p, t) for p, t in out if not p.startswith("/brownfield") and not p.startswith("/implementation")]


def cmd_symbol(staging):
    items = api_items(staging)
    print("=== 내부 구현 심볼 오염 (…Request/…Response/…Dto 는 계약 어휘라 제외) ===")
    n = 0
    for i, it in sorted(items.items()):
        hits = []
        for p, t in _texts(it):
            for m in SYMBOL_PAT.finditer(t):
                seg = t[max(0, m.start() - 40):m.end() + 40]
                hits.append((m.group(1), p, seg))
        if hits:
            n += len(hits)
            print(f"\n  ⚠️ {i} — {len(hits)}건")
            for sym, p, seg in hits[:6]:
                print(f"       {sym:28s} {p[:26]:26s} …{seg}…")
    print(f"\n  {'✅ 0건' if not n else f'❌ 합계 {n}건'}")
    return 1 if n else 0


# ── 구조 완결성 ────────────────────────────────────────────────────────
def cmd_shape(staging, repo=REPO_DEFAULT):
    """계약 완결성 — 단, 구현과 대조해 판정한다.

    구현을 안 보고 형식만 세면 잡음이 지배한다. 204 는 본문이 없어 example 이
    없는 게 정상이고, 본문 없는 POST 에 request_body 를 요구하면 없는 결함이
    80건 만들어진다(실측). 결손은 **구현이 그것을 갖고 있는데 정의서에만 없을 때**만이다."""
    items = api_items(staging)
    impl = backend_endpoints(repo)
    impl.update(aiserver_endpoints(repo))
    print("=== 계약 완결성 (제3자가 이것만 보고 요청을 만들 수 있는가) ===")
    rows = []
    for i, it in sorted(items.items(), key=lambda kv: int(kv[0].split("-")[1])):
        d = it["data"]
        path = d.get("path") or ""
        meth = (d.get("method") or "").upper()
        e = impl.get((meth, norm(path)))
        miss = []
        pathvars = set(re.findall(r"\{([^}]*)\}", path))
        declared = {p.get("name") for p in (d.get("parameters") or []) if p.get("in") == "path"}
        if pathvars - declared:
            miss.append(f"경로변수 미선언{sorted(pathvars - declared)}")
        if not (d.get("description") or "").strip():
            miss.append("description 없음")
        if not (d.get("summary") or "").strip():
            miss.append("summary 없음")
        if not d.get("security"):
            # 추론 서버는 인증·DB 가 없는 내부 stateless 경로다 — security 를 요구하면
            # 없는 결함이 5건 생긴다(그 계약의 사실은 '인증 없음'이지 '미기재'가 아니다).
            from_ai = bool(e) and str(e.get("file", "")).endswith(".py")
            eff = effective_roles(meth, e["path"], e["auth"]) if e else None
            if e is not None and not from_ai and eff is not PERMIT:
                miss.append("security 없음")
        resp = d.get("responses") or {}
        if not resp:
            miss.append("responses 없음")
        else:
            if not [c for c in resp if c.startswith("2")]:
                miss.append("2xx 없음")
            # 4xx 를 요구하는 근거는 '거부될 수 있는 입력이나 인가가 있다'는 것이다.
            # 인증도 입력도 없는 프로브(예: /health)는 거부될 여지가 없어 대상이 아니다.
            has_input = bool(d.get("parameters")) or bool(d.get("request_body"))
            needs_auth = bool(d.get("security")) or (
                e is not None and effective_roles(meth, e["path"], e["auth"]) is not PERMIT)
            if (has_input or needs_auth) and not any(c.startswith("4") for c in resp):
                miss.append("4xx 없음")
            for c, r in resp.items():
                r = r or {}
                if not c.startswith("2") or c == "204":
                    continue
                ct = (r.get("content_type") or "")
                if "json" not in ct:          # 바이너리·스트림 응답은 example 대상이 아니다
                    continue
                sch = r.get("schema") or {}
                if not sch.get("properties") and not sch.get("type"):
                    # example 이 아니라 **스키마 자체**가 없는 것 — 응답을 파싱할 수 없다.
                    # example 검사만 두면 이 층이 통째로 안 보인다(실측 9건).
                    miss.append(f"{c} 스키마 없음")
                elif not r.get("example"):
                    miss.append(f"{c} example 없음")
        if meth in ("POST", "PUT", "PATCH") and not d.get("request_body"):
            if e and e.get("has_body"):
                miss.append("request_body 없음(구현은 본문을 받는다)")
        if miss:
            rows.append((i, meth, path, miss))
    for i, meth, path, miss in rows:
        print(f"  {i:9s} {meth:6s} {path[:46]:46s} {'; '.join(miss)}")
    print(f"\n  대상 {len(items)}건 중 결손 {len(rows)}건")
    return 1 if rows else 0


def cmd_screenrefs(api_staging, screen_staging):
    """화면 참조 무결성.

    ⚠ [폐기] 섹션 안의 참조는 결함이 아니다 — 채택하지 않은 설계와 그 설계가 쓰던
      엔드포인트는 '결정이 조용히 사라지지 않게' 함께 보존하는 기록이다. 이걸 구분하지
      않으면 폐기 API 를 가리키는 폐기 섹션이 매 라운드 '죽은 참조'로 재보고된다."""
    items = api_items(api_staging)
    ref, dead_live, dead_retired = {}, {}, {}
    for f in sorted(glob.glob(os.path.join(screen_staging, "screen_spec", "_raw", "*.json"))):
        it = L(f)
        d = it.get("data") or {}
        live, retired = set(), set()
        for a in (d.get("consumes_apis") or []):
            live.add(a)
        for sec in (d.get("sections") or []):
            blob = json.dumps(sec, ensure_ascii=False)
            found = set(re.findall(r"API-\d+", blob))
            (retired if str(sec.get("name", "")).startswith("[폐기]") else live).update(found)
        retired -= live
        for a in live | retired:
            ref.setdefault(a, []).append(it["id"])
        for a in sorted(live - set(items)):
            dead_live.setdefault(a, []).append(it["id"])
        for a in sorted(retired - set(items)):
            dead_retired.setdefault(a, []).append(it["id"])
    print(f"=== 화면 참조 무결성 — 참조 {len(ref)}종 / API {len(items)}건 ===")
    print(f"\n  ① 유효 섹션이 참조하는데 존재하지 않는 API {len(dead_live)}건 (결함)")
    for a, v in sorted(dead_live.items()):
        print(f"     {a} ← {v}")
    print(f"\n  ② [폐기] 섹션만 참조하는 부재 API {len(dead_retired)}건 (기록 — 결함 아님)")
    for a, v in sorted(dead_retired.items()):
        print(f"     {a} ← {v}")
    unref = sorted(set(items) - set(ref), key=lambda x: int(x.split("-")[1]))
    print(f"\n  ③ 어느 화면도 참조하지 않는 API {len(unref)}건")
    print("     " + ", ".join(unref))
    return 1 if dead_live else 0


# ── 비식별 누락 신고 게이트 → 엔드포인트 (호출 그래프) ────────────────────
# 게이트는 서비스에 있고 응답코드는 엔드포인트 계약이다. 어느 엔드포인트가
# 412(또는 404)를 내는지는 손으로 추정하지 않고 호출을 따라가 정한다.
# ⚠ 메서드 참조(`gate::isUnderDeidentReport`)는 뒤에 '(' 가 없다 —
#   '(' 를 요구하면 증강 요청 게이트처럼 stream().filter(...) 로 쓰는 호출을 통째로 놓친다.
GATE_CALL = re.compile(r"(?:isUnderDeidentReport|requireNotUnderDeidentReport)\b")
METH_DECL = re.compile(r"^\s*(?:public|private|protected)\s+[\w<>,\[\]\?\. ]+?\s+(\w+)\s*\(", re.M)
ERR_CODE = re.compile(r"ErrorCode\.(\w+)")


def _enclosing(src, pos):
    best = None
    for m in METH_DECL.finditer(src):
        if m.start() < pos:
            best = m
        else:
            break
    return best.group(1) if best else None


def gate_methods(repo):
    """게이트를 타는 메서드 → 응답코드.

    규칙을 둘로 나눈다 —
      · `isUnderDeidentReport(...)` = 판정 술어. 그 분기 안의 throw 가 이 게이트의 코드다.
        던지지 않으면(예: 산출 **보류**) 응답코드가 아니므로 제외한다.
      · `requireNotUnderDeidentReport(...)` = 이미 던지는 헬퍼 호출. 같은 파일에 그 헬퍼가
        정의돼 있으면 그 코드를, 없으면 공용 가드의 것(412)을 쓴다."""
    out = {}
    for f in glob.glob(os.path.join(repo, "backend/src/main/java/**/*.java"), recursive=True):
        s = open(f, encoding="utf-8").read()
        if "Controller.java" in f or not GATE_CALL.search(s):
            continue
        base = os.path.basename(f)
        direct, local_helper = {}, None
        for m in re.finditer(r"(isUnderDeidentReport|requireNotUnderDeidentReport)\b", s):
            line_start = s.rfind("\n", 0, m.start()) + 1
            if s[line_start:m.start()].lstrip().startswith(("*", "//")):
                continue
            name = _enclosing(s, m.start())
            if not name:
                continue
            if m.group(1) == "isUnderDeidentReport":
                blk = s[m.start():m.start() + 900]
                nxt = re.search(r"\n    (?:public|private|protected)\s", blk)
                if nxt:
                    blk = blk[:nxt.start()]
                codes = ERR_CODE.findall(blk)
                if not codes:
                    continue
                code = "404" if codes[0] == "NOT_FOUND" else "412"
                if re.search(r"private\s+void\s+" + re.escape(name) + r"\s*\(", s):
                    local_helper = (name, code)
                direct[name] = code
            else:
                code = local_helper[1] if local_helper else "412"
                direct.setdefault(name, code)
        for _ in range(3):
            for hname, code in list(direct.items()):
                for m in re.finditer(r"(?<![\w.])" + re.escape(hname) + r"\s*\(", s):
                    caller = _enclosing(s, m.start())
                    if caller and caller != hname and caller not in direct:
                        direct[caller] = code
        for k, v in direct.items():
            out[(base, k)] = v
    return out


def propagate_through_services(repo, gm, hops=2):
    """서비스→서비스 한 단계 더 — 컨트롤러가 게이트 서비스를 직접 부르지 않는 경우.

    프레임 이미지 3경로는 컨트롤러 → 서빙 서비스 → 조회 서비스 순으로 한 단계 안쪽에
    게이트가 있어, 직접 호출만 보면 통째로 누락된다."""
    gm = dict(gm)
    for _ in range(hops):
        added = 0
        by_cls = {}
        for (base, meth), code in gm.items():
            by_cls.setdefault(base[:-5], {})[meth] = code
        for f in glob.glob(os.path.join(repo, "backend/src/main/java/**/*.java"), recursive=True):
            if "Controller.java" in f:
                continue
            s2 = open(f, encoding="utf-8").read()
            base = os.path.basename(f)
            for cname, methods in by_cls.items():
                if cname == base[:-5]:
                    continue
                fld = re.search(r"\b" + re.escape(cname) + r"\s+(\w+)\s*;", s2)
                if not fld:
                    continue
                var = fld.group(1)
                for meth, code in methods.items():
                    for m in re.finditer(r"\b" + re.escape(var) + r"\s*\.\s*" + re.escape(meth) + r"\s*\(", s2):
                        caller = _enclosing(s2, m.start())
                        if caller and (base, caller) not in gm:
                            gm[(base, caller)] = code
                            added += 1
        if not added:
            break
    return gm


def cmd_gate(staging, repo=REPO_DEFAULT):
    """게이트를 타는 서비스 메서드를 **클래스에 묶어** 컨트롤러 호출과 잇는다.

    ⚠ 메서드명만으로 매칭하면 `update`·`list` 같은 흔한 이름이 전 컨트롤러로 번져
      전혀 무관한 엔드포인트까지 게이트 대상으로 오보고된다."""
    items = api_items(staging)
    gm = propagate_through_services(repo, gate_methods(repo))
    by_cls = {}
    for (base, meth), code in gm.items():
        by_cls.setdefault(base[:-5], {})[meth] = code
    hit = {}
    for f in glob.glob(os.path.join(repo, "backend/src/main/java/**/*Controller.java"), recursive=True):
        s2 = open(f, encoding="utf-8").read()
        cls = PAT_CLS.search(s2)
        cbase = cls.group(1) if cls else ""
        marks = [(mm.start(), mm.group(1).upper(), _mapping_path(mm.group(2))) for mm in PAT_M.finditer(s2)]
        for mm in PAT_M_RM.finditer(s2):
            for verb in re.findall(r"RequestMethod\.([A-Z]+)", mm.group(1)):
                marks.append((mm.start(), verb, _mapping_path(mm.group(1))))
        marks.sort()
        for cname, methods in by_cls.items():
            fld = re.search(r"\b" + re.escape(cname) + r"\s+(\w+)\s*;", s2)
            if not fld:
                continue
            var = fld.group(1)
            for meth, code in methods.items():
                for m in re.finditer(r"\b" + re.escape(var) + r"\s*\.\s*" + re.escape(meth) + r"\s*\(", s2):
                    prev = [x for x in marks if x[0] < m.start()]
                    if not prev:
                        continue
                    _, verb, sub = prev[-1]
                    hit.setdefault((verb, norm(cbase + sub)), set()).add(code)
    spec = {(it["data"].get("method", "?").upper(), norm(it["data"].get("path"))): i
            for i, it in items.items()}
    print(f"=== 비식별 신고 게이트 응답코드 — 게이트 통과 엔드포인트 {len(hit)}건 ===")
    bad = []
    for key, codes in sorted(hit.items(), key=lambda kv: kv[0][1]):
        i = spec.get(key)
        if not i:
            print(f"  ·  (정의서 없음)  {key[0]:6s} {key[1]}")
            continue
        have = set((items[i]["data"].get("responses") or {}).keys())
        want = sorted(codes)
        missing = [c for c in want if c not in have]
        if missing:
            bad.append((i, key, missing))
        print(f"  {'❌' if missing else '✅'} {i:9s} {key[0]:6s} {key[1][:44]:44s} "
              f"필요 {want} / 보유 {sorted(have)}")
    print(f"\n  {'✅ 누락 0건' if not bad else f'❌ 응답코드 누락 {len(bad)}건'}")
    return 1 if bad else 0




# ── 통지 축 ────────────────────────────────────────────────────────────
# 완료·수정 통지는 **검수 완료가 대상**이고, **산출(export)이 성공한 뒤에** 나간다.
# 이 둘은 한 세트이며 순서까지 계약이다 — 통지가 먼저 나가면 관제가 아직 만들어지지
# 않았거나 구버전인 산출 폴더를 픽업한다. 본문이 이 전제를 빼면 "저장할 때마다 통지가
# 나간다" 거나 "상태 전이 시점에 통지한다" 로 읽혀 구현이 어긋난다.
NOTIFY_PAT = re.compile(r"TASK_MODIFIED|TASK_COMPLETED|완료 ?통지|수정 ?통지|관제[^.]{0,8}통지|통지가 발행")
APPROVED_PAT = re.compile(r"검수 ?완료|검수를? ?승인|승인된|승인 ?=|APPROVED")
# 통지가 '발행된다'고 말하는 문장에만 순서를 요구한다(차단·미발행 서술은 대상 아님).
EMITS_PAT = re.compile(r"통지[^.\n]{0,12}(발행|발송|나간다|나감)")
ORDER_PAT = re.compile(r"(재생성|산출|export)[^.\n]{0,40}(뒤|후|이후)[^.\n]{0,24}통지"
                       r"|통지[^.\n]{0,30}(산출|재생성)[^.\n]{0,20}(성공|끝난|종결)[^.\n]{0,12}(뒤|후|이후)")
# 2026-08-07 사용자 확정 — 수정분의 산출·통지 트리거는 **재검수 승인**이다. 수정 시점이 아니다.
# "검수 완료된 영상을 수정하면 재생성된다" 같은 서술은 전제는 맞지만 **트리거가 틀렸다**.
RECHECK_PAT = re.compile(r"재검수|다시 ?승인|재승인|다시 ?검수")


NEGATED_PAT = re.compile(r"통지[^.\n]{0,16}(않는다|없다|아니다|일어나지|미발행)")


def cmd_notify(staging):
    """⚠ 두 가지를 구분한다.

    ①통지를 **발행하는** 쪽(상태를 바꾸는 엔드포인트)은 산출 선행 순서까지 적어야 한다.
    ②통지를 **받고 조회하는** 쪽(GET)은 검수 완료 기준만 적으면 된다 — 그쪽이 산출 순서를
      되풀이할 이유가 없다. 또 '통지가 일어나지 않는다' 는 부정 서술은 대상이 아니다."""
    items = api_items(staging)
    print("=== 통지 축 — 검수 완료 전제 + 산출 선행 순서 ===")
    bad = 0
    for i, it in sorted(items.items(), key=lambda kv: int(kv[0].split("-")[1])):
        mutating = (it["data"].get("method") or "").upper() in ("POST", "PUT", "PATCH", "DELETE")
        for p, t in _texts(it):
            if not NOTIFY_PAT.search(t) or NEGATED_PAT.search(t):
                continue
            emits = EMITS_PAT.search(t) is not None
            if not APPROVED_PAT.search(t):
                bad += 1
                print(f"  ❌ {i:9s} {p[:30]:30s} 검수 완료 전제 없음")
            elif mutating and emits and not ORDER_PAT.search(t):
                bad += 1
                print(f"  ❌ {i:9s} {p[:30]:30s} 산출 선행 순서 없음")
            elif mutating and emits and not RECHECK_PAT.search(t):
                bad += 1
                print(f"  ❌ {i:9s} {p[:30]:30s} 재검수 승인 트리거 없음(수정 시점으로 읽힘)")
    print(f"\n  {'✅ 0건' if not bad else f'❌ {bad}건'}")
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    c, rest = sys.argv[1], sys.argv[2:]
    fn = {"endpoints": cmd_endpoints, "roles": cmd_roles, "symbol": cmd_symbol,
          "shape": cmd_shape, "screenrefs": cmd_screenrefs,
          "gate": cmd_gate, "notify": cmd_notify}.get(c)
    if not fn:
        print(__doc__)
        sys.exit(2)
    sys.exit(fn(*rest))
