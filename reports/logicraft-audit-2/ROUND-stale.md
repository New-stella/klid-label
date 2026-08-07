# 라운드 4 — `stale` 플래그 해소 (2026-08-07, **완료**)

> 재개 순서: `RESUME.md` → **이 문서**. 앞 라운드는 `ROUND-api.md`(api_endpoint 축) 참조.
> ⚠ ITEM 을 쓰기 전에 `~/Documents/workspace/cc-forge/rules/logicraft-integration.md` 를 먼저 읽을 것.

---

## 0. 지금 상태 한 줄

**완료. `stale` 0** — 프로젝트 전체 **843 ITEM**(draft/approved 764 + deprecated 74 + superseded 5) 기준.

### ★ 이 라운드에서 두 번 틀렸다 (같은 실수를 다시 하지 않기 위해 남김)

| # | 무엇이 틀렸나 | 어떻게 드러났나 |
|---|---|---|
| 1 | **해소 기법**을 `update_item(data_mode='merge', data={})` 로 잡았다. 이건 **링크를 재계산해 하류를 다시 stale 시킨다.** 193회를 그렇게 돌려 파도를 키웠다 | 사용자가 *"아직 105개 남았어"* 라고 지적 |
| 2 | **검증을 로컬 `.staging` 으로 했다.** 다운로더 델타는 content_hash/version 기준이라 **stale 플래그만 바뀐 ITEM 은 파일을 다시 쓰지 않는다.** 게다가 `include_retired` 없이는 **deprecated 74 + superseded 5 가 통째로 안 보인다** | 서버 라이브 조회에서 105건 확인(로컬은 0건이라 말하고 있었다) |

**두 오류가 겹쳐 "0건 완료" 라고 보고했다. 실제로는 105건이 남아 있었다.**

### 결론 — 올바른 기법은 `status`-only 갱신이다

```
update_item(id, status=<그 ITEM 의 현재 status 그대로>, change_summary='...')
   # data / data_mode 를 아예 넘기지 않는다
```

**대조 실험(API-065 · 의존자 NFR-017)**: `status`-only 로 닫으니 API-065 의 stale 이 지워지고
**NFR-017 은 깨끗한 상태를 유지**, 총계는 정확히 105→104. 이어서 17건 배치도 104→87(정확히 −17).
반면 같은 축에서 `data={}` 는 NFR-017 을 `"API-065의 4개 필드 변경 — applies_to"` 로 되살렸었다.

> ⚠ **원래 memory `logicraft stale 일괄 해소 기법` 이 옳았다** — *"재저장은 협력 도메인을 다시 stale
> 시키므로 status-only 변경만이 깨끗하다"*. 라운드 4 가 그 기술을 표본 2건으로 뒤집은 것이 잘못이었다.
> **`status` 는 반드시 현재 값을 그대로 넘긴다**(폐기 ITEM 은 `deprecated`, 대체는 `superseded`).

### 종료 검증 (서버 라이브 기준 — 로컬 스냅샷으로 하지 말 것)

| 축 | 결과 |
|---|---|
| stale 총계 (`include_retired=true`) | **0 / 843** |
| ITEM 수 · status 분포 | 843 · `approved 194 · draft 570 · deprecated 74 · superseded 5` — **착수 시점과 동일**(유실 0) |
| `data` 본문 대조 (라이브 ↔ 로컬 764건, 재귀 정규화 후) | **변경 0** |
| `title`/`status`/`type`/`slug` | **변경 0** — 폐기 20건도 `deprecated` 유지 |
| `data={}` 회차의 문자 단위 diff | 기존 키 삭제 0 · 기존 값 변경 0. 14 ITEM 에서 서버 스키마 정규화로 **빈 배열 기본 필드만 추가**(`uses_constants` `applies_to_pipelines` `implemented_by_module_apis` `implemented_by_service_interfaces` `static_renders[].triggered_by`) |
| 폐기 용어 드리프트(§3-3) · 폐기된 통지 트리거 | 각각 **0** |
| `change_summary` 문자열 | 전건 정문과 문자 단위 일치(한글 손상 0) |

> 한글 손상 위험은 이 라운드에 **구조적으로 0**이었다 — 본문(`data`)에 한글을 한 글자도 쓰지 않았고,
> 한글이 들어간 곳은 `change_summary` 뿐인데 전부 이스케이프 없이 그대로 입력했다.

---

## 1. `stale` 이 무엇인가 (오해 방지)

**데이터 오류가 아니다.** "내 이웃이 나보다 나중에 갱신됐다"는 **의존성 추적 플래그**다.
여러 연결 ITEM 을 함께 고치면 cascade 로 무더기 발생한다. `stale_reason` 에 사유가 붙는다
(예: `SCREEN-005의 5개 필드 변경 — references`).

**닫는다 = "확인했고 바꿀 내용 없다"고 선언하는 것**이므로, 무작정 닫으면 신호를 확인 없이 지우는 셈이다.
그래서 이 라운드는 **원인별로 먼저 검사**했고 실제로 결함 3종이 나왔다(§3).

## 2. 해소 기법 (실측 검증 완료)

### ★ 확정 기법 — `status`-only (위 §0 참조)

```
update_item(id, status=<현재 status 그대로>, change_summary='...')   # data/data_mode 미전달
```
전파가 없으므로 **처리 순서를 따질 필요가 없다.** 한 메시지에 14~20건씩 병렬로 넣는다.

### 현황 조회는 **서버 라이브**로 — 로컬 스냅샷 금지

```bash
export LOGICRAFT_API_KEY=$(python3 -c "import json,os;d=json.load(open(os.path.expanduser('~/.claude.json'),encoding='utf-8'));print(d['projects'][os.path.expanduser('~/Documents/workspace/klid/klid-label')]['mcpServers']['logicraft']['headers']['Authorization'].split()[1])")
node docs/screen-design/klid-authoring-screens/bin/stale-live.mjs [--reasons]
```

- 이 스크립트가 **`include_retired=true`** 를 붙인다(안 붙이면 deprecated 74 + superseded 5 가 안 보인다).
- `stale_reason` 은 `include_bodies=true` 응답의 **`raw_json` 안**에 있다(최상위 필드 아님).
- `include_bodies=true` 응답에는 **`links.forward` / `links.backward`** 도 들어 있어 의존 그래프를 그대로 얻을 수 있다.

---

### 아래는 폐기된 구 기법 — 기록으로만 남긴다 (`data={}` 는 쓰지 말 것)

- `data_mode='merge', data={}` 도 stale 을 지우기는 한다. **그러나 링크를 재계산해 하류를 다시 stale 시킨다.**

#### 전파 방향 (그 기법을 썼을 때 관측된 것 — `status`-only 에는 해당 없음)

> 구 서술 *"이웃으로 전파되지 않는다 — `MOD-020`·`DOMAIN-001` 로 확인, 총계가 1씩만 줄었다"* 는
> **표본이 하류 의존자가 없는 ITEM 두 개뿐이었다.** 일반화가 틀렸다.

`data={}` 무변경 저장도 **하류 의존자를 다시 stale 시킨다.** 서버 로그는 이를
`"{상류ID}의 3개 필드 변경 — derived_from"` 처럼 **필드 변경으로 기록**한다(내용은 그대로인데도).

실측: `screen_spec` 28건은 하류가 깨끗해 **정확히 28건만** 줄었으나(187→159),
`use_case` 22건을 닫자 **`acceptance` 20건이 통째로 되살아났다**(예상 94 → 실제 114).

**관측된 전파 방향** (앞 스냅샷 187건의 `stale_reason` 을 타입별로 집계 — 이 표가 처리 순서의 근거다):

```
RFP    → requirement, feature          REQ  → api_endpoint, domain_feature, code_module
FEAT   → api_endpoint, acceptance, class_diagram, code_module
API    → screen_spec, use_case, domain_feature, class_diagram, diagram_sequence, nfr
SCREEN → use_case, code_module, test_scenario, permission_role, app_shell, navigation_tree
ROLE   → screen_spec, navigation_tree  UC   → acceptance, diagram_sequence, domain_feature
DFEAT  → diagram_c4_component, class_diagram        DOMAIN → nfr, code_module, domain
INTSPEC ↔ INT → api_endpoint           CONST → erd      ADR → adr      AC → use_case
```

⚠ **타입 수준에는 순환이 있다**(`API→screen_spec→use_case`, `AC↔UC`, `INT↔INTSPEC`). 그래서
타입만으로 완전 정렬은 불가능하고 **ITEM 수준 반복(fixpoint)** 이 필요하다. 실제로 1차 187건 뒤
6건이 되살아났고(`ROLE-002`←FEAT · `DFEAT-041/042/045`←AC · `CDIAG-003`·`CMP-011`←AC 2-hop)
그 6건을 하류 순서로 닫아 0 이 됐다.

**그 기법을 쓸 경우의 순서** (참고용 — 지금은 쓰지 않는다):
`ROLE·ADR·CONST·DOMAIN·INTSPEC → REQ → FEAT → API → INT → DFEAT → CDIAG/CMP → SEQ → AC → NFR/TEST/NAV/SHELL`
그리고 끝난 뒤 되살아난 것을 한 번 더 닫아야 한다.

⚠ **`links.forward` 로 본 실제 그래프에는 상호 의존 쌍이 72개 있다**(`AC-001↔UC-001` 등 전 AC/UC 쌍).
갱신 필요 폐포 401건 중 **378건이 순환에 갇혀** 위상정렬이 성립하지 않는다 —
즉 `data={}` 로는 원리적으로 0 에 수렴시킬 수 없다. **이것이 그 기법을 폐기한 결정적 이유다.**

- `change_summary` 는 필수다. 정직하게 적는다:
  `"연결 ITEM 갱신에 따른 재확인 — 바꿀 내용이 없어 내용 변경 없이 확인만 기록한다."`

> ⚠⚠ **위 로컬 스냅샷 방식은 stale 판정에 쓰지 말 것**(§0 오류 2). 다운로더 델타가 content_hash 기준이라
> **stale 플래그만 바뀐 ITEM 은 파일이 갱신되지 않고**, `--types` 없이 받아도 **deprecated/superseded 는 빠진다**.
> 이 블록은 *본문 대조*(내용 손상 검증)용으로만 쓴다. stale 판정은 위 `stale-live.mjs`.

## 3. 닫기 전에 한 검사 — 실제로 결함 3종이 나왔다

원인 ITEM 79개의 변경 성격을 확인하고, **폐기·개명된 용어를 전 코퍼스(764 ITEM) grep** 했다.

### 3-1. 정정 완료 (21 ITEM)

| 결함 | 대상 | 무엇이 문제였나 |
|---|---|---|
| **VLM 위탁이 폐기된 구 규격** | `INTSPEC-002`(전면 재작성) · `API-065`(summary) · `ERD-021`(컬럼 설명) | 결과를 구간별 **배열**로 받는다고 기술. 그대로 구현하면 배열 파서를 만들어 벤더 페이로드를 통째로 거부한다. 실제 계약은 `{accuracy, description}` **객체 하나** |
| **폐기된 통지 트리거**(수정 시점 → **재승인 시점**) | `UC-009` `AC-009` `FEAT-003` `DFEAT-046` `TEST-004` `SCREEN-005` `API-134` `UC-021` `ADR-032` `ADR-020` `DOMAIN-016` `EXTSYS-005` `INTSPEC-004` | 검수 통과하지 않은 내용이 관제로 나가는 흐름으로 읽혔다 |
| **관제 계약면 컬럼 개명 미반영** | `EXPORT_PATH_NM`→`OUTPUT_PATH_NM` 8건 · `ORIGINAL_VIDEO_PATH`→`ORGNL_VDO_PATH_NM` 4건 · `FRAME_CNT`→`FRME_CNT` · `NEXT_RTRY_DT`→`NXTM_RTRY_DT` 3건 · `MNG_RESOURCE_CCTV` 검증(`API-152`) | 관제가 **존재하지 않는 컬럼을 SELECT** 하게 된다 |

부수 정정: 기존 한글 손상 2건(`되도로`→`별도로` `DOMAIN-016`, `흘름`→`흐름` `TEST-004`),
내부 구현 심볼·커밋 해시 제거(`ADR-020` `ADR-023` `ADR-032` `EXTSYS-005` `INTSPEC-004` `DFEAT-046`).

### 3-2. 정상이라 손대지 않은 것 (다시 고치려 들지 말 것)

- `ADR-018` `ADR-042` `ERD-012` `UI-015` `EXTSYS-005` `CDIAG-010` `ERD-011` — 전부 **"구 X → 신 Y" 개명·폐기 이력 병기**다. 지우면 결정이 증발한다.
- `ERD-021.NEXT_RTRY_DT` — 이건 **`LS_CONTROL_NOTIFY_FALLBACK`** 의 컬럼이라 개명 대상이 아니다.
  개명된 것은 **`LS_DATA_INGEST`** 쪽 하나뿐. 두 테이블에 같은 이름이 공존하므로 **전역 치환 금지**.
- `ADR-013` `CDIAG-011` `DOMAIN-013` 의 "부분 override" — 전부 **폐기 기록**.

### 3-3. 재검사 명령 (드리프트 0 확인용)

```bash
python3 - <<'EOF'
import json,glob,re
items={}
for f in glob.glob('docs/screen-design/klid-authoring-screens/.staging-all/*/_raw/*.json'):
    it=json.load(open(f,encoding='utf-8'))['item']; it['_s']=json.dumps(it.get('data'),ensure_ascii=False); items[it['id']]=it
HIST=re.compile(r'(구 |폐기|개명|rename|→|이전|옛 )')     # 이력 병기는 정상이므로 제외
for term in ['EXPORT_PATH_NM','ORIGINAL_VIDEO_PATH','FRAME_CNT','MNG_RESOURCE_CCTV','EXPORT_STTS_CD','DURATION_SEC','REVIEW_COMPLETED_AT']:
    bad=[i for i,it in items.items() for m in re.finditer(re.escape(term), it['_s'])
         if not HIST.search(it['_s'][max(0,m.start()-70):m.start()+len(term)+30])]
    print(f'{term:22s} {len(set(bad))} {sorted(set(bad))}')
# 폐기된 통지 트리거
pat=re.compile(r'[^"]{0,110}(수정\s*(시|하면|할 때|즉시|되면|마다)[^"]{0,80}?(통지|재생성|TASK_MODIFIED)|TASK_MODIFIED[^"]{0,70}?수정\s*(시|하면|할 때|즉시))[^"]{0,90}')
for i,it in items.items():
    for m in pat.finditer(it['_s']):
        t=m.group(0)
        if '재승인' in t or '재검수' in t or '폐기' in t: continue
        print('통지트리거', i, t[:120]); break
EOF
```

**마지막 실행 결과: 전 항목 0건.**

## 4. ★★ 한글 이스케이프 — 이 라운드에만 8건 손상

`API-106` 이후 규칙 파일을 읽고 착수했는데도 **계속 났다**:

`갭→겝`(INT-010) · `싣→십`(UC-009, DOMAIN-016) · `엔→앤`(EXTSYS-005) · `돈→돌`(UC-009) ·
`푼→푸는`(INTSPEC-002) · `막혀→막혐`(ADR-023) · **`컬럼→컴럼`(ADR-023)**

마지막 것은 글로벌 `CLAUDE.md` 가 **실사례로 못박은 바로 그 오타**다.

### 잡은 방법 — 희귀음절 검사가 아니라 **기준선 대비 문자 단위 diff**

```python
# 쓰기 전에 .staging-all 을 통째로 백업해 두고, 쓴 뒤 재다운로드해서 비교
import difflib
for tag,i1,i2,j1,j2 in difflib.SequenceMatcher(None, before, after).get_opcodes():
    if tag != 'equal': print(tag, repr(before[i1:i2]), '->', repr(after[j1:j2]))
# 의도한 치환 외의 opcode 가 하나라도 나오면 손상이다. 특히 한글 1~2자 replace.
```

- **길이 대조만으로는 못 잡는다** — 같은 길이의 1자 치환이 그대로 통과한다.
- **희귀음절 검사도 놓칠 수 있다** — 눈으로 훑는 형식이라 `곲`·`십` 같은 게 목록에 있어도 지나친다.
- 이 교훈은 cc-forge `rules/logicraft-integration.md` §2-C·§6 에 반영해 뒀다.

**결론: 도구 인자에 한글을 쓸 때는 이스케이프를 만들지 말고 한글을 그대로 입력한다.**
불가피하면 셸로 생성+왕복검증:
```bash
python3 -c 'import sys,json;e="".join("\\u%04x"%ord(c) for c in sys.argv[1]);assert json.loads(chr(34)+e+chr(34))==sys.argv[1];print(e)' '변환할 한글'
```

## 5. 처리 목록 (완료 — 착수 시점 스냅샷)

앞 세션 6건: `MOD-020` `DOMAIN-001` `DOMAIN-003` `DOMAIN-004` `DOMAIN-006` `DOMAIN-007`
이번 회차 187건(아래 표) + 재-stale 6건(`ROLE-002` `DFEAT-041` `DFEAT-042` `DFEAT-045` `CDIAG-003` `CMP-011`) = **총 193 호출**.

| 타입 | 건수 | ID |
|---|---:|---|
| `screen_spec` | 28 | SCREEN-002 SCREEN-005 SCREEN-006 SCREEN-008 SCREEN-009 SCREEN-010 SCREEN-011 SCREEN-012 SCREEN-018 SCREEN-019 SCREEN-020 SCREEN-021 SCREEN-022 SCREEN-023 SCREEN-024 SCREEN-025 SCREEN-026 SCREEN-027 SCREEN-028 SCREEN-029 SCREEN-030 SCREEN-031 SCREEN-032 SCREEN-034 SCREEN-035 SCREEN-036 SCREEN-037 SCREEN-038 |
| `acceptance` | 23 | AC-001 AC-002 AC-003 AC-004 AC-005 AC-006 AC-007 AC-008 AC-009 AC-010 AC-011 AC-013 AC-016 AC-017 AC-018 AC-019 AC-020 AC-021 AC-022 AC-023 AC-024 AC-027 AC-028 |
| `use_case` | 22 | UC-001 UC-002 UC-003 UC-004 UC-005 UC-006 UC-007 UC-008 UC-010 UC-011 UC-013 UC-016 UC-018 UC-019 UC-022 UC-023 UC-024 UC-027 UC-028 UC-029 UC-030 UC-032 |
| `code_module` | 20 | MOD-001 MOD-002 MOD-003 MOD-004 MOD-005 MOD-006 MOD-007 MOD-008 MOD-009 MOD-010 MOD-011 MOD-012 MOD-013 MOD-014 MOD-015 MOD-016 MOD-017 MOD-018 MOD-021 MOD-022 |
| `domain_feature` | 16 | DFEAT-018 DFEAT-019 DFEAT-020 DFEAT-029 DFEAT-038 DFEAT-041 DFEAT-042 DFEAT-043 DFEAT-044 DFEAT-045 DFEAT-047 DFEAT-048 DFEAT-049 DFEAT-050 DFEAT-052 DFEAT-053 |
| `requirement` | 15 | REQ-001 REQ-003 REQ-004 REQ-005 REQ-006 REQ-007 REQ-008 REQ-009 REQ-010 REQ-012 REQ-013 REQ-014 REQ-015 REQ-016 REQ-026 |
| `diagram_sequence` | 13 | SEQ-002 SEQ-003 SEQ-004 SEQ-005 SEQ-006 SEQ-007 SEQ-008 SEQ-009 SEQ-010 SEQ-011 SEQ-012 SEQ-013 SEQ-014 |
| `nfr` | 11 | NFR-008 NFR-009 NFR-010 NFR-011 NFR-012 NFR-013 NFR-015 NFR-017 NFR-018 NFR-019 NFR-020 |
| `api_endpoint` | 8 | API-034 API-059 API-062 API-063 API-068 API-069 API-112 API-182 |
| `class_diagram` | 7 | CDIAG-001 CDIAG-003 CDIAG-005 CDIAG-008 CDIAG-011 CDIAG-013 CDIAG-015 |
| `feature` | 5 | FEAT-001 FEAT-004 FEAT-005 FEAT-007 FEAT-009 |
| `test_scenario` | 4 | TEST-001 TEST-002 TEST-003 TEST-005 |
| `integration_point` | 3 | INT-002 INT-003 INT-005 |
| `diagram_c4_component` | 2 | CMP-010 CMP-011 |
| `adr` | 2 | ADR-021 ADR-042 |
| `permission_role` | 2 | ROLE-002 ROLE-003 |
| `navigation_tree` | 2 | NAV-001 NAV-002 |
| `integration_spec` | 1 | INTSPEC-001 |
| `app_shell` | 1 | SHELL-002 |
| `erd` | 1 | ERD-019 |
| `domain` | 1 | DOMAIN-010 |

> ⚠ **이 목록은 스냅샷이다.** 재개 시 §2 의 조회 명령으로 **다시 뽑아서** 쓸 것 —
> 다른 세션이 ITEM 을 고쳤으면 목록이 달라진다.

### 절차 (다음에 또 stale 이 쌓이면 이대로)

1. `node bin/stale-live.mjs --reasons` 로 **서버 라이브** 현황을 뽑는다(로컬 스냅샷 금지 · retired 포함).
2. §3-3 재검사 명령으로 **폐기 용어 드리프트가 0인지 확인**한다(0이 아니면 그것부터 고친다).
   ⚠ stale 을 검사 없이 닫지 않는다 — 이 라운드에 실제 결함 3종 21 ITEM 이 나왔다(§3-1).
3. **`status`-only** 로 닫는다. 각 ITEM 의 **현재 status 를 그대로** 넘긴다(폐기는 `deprecated`).
   순서 무관 · 한 메시지에 14~20건 병렬.
4. 배치마다 라이브 총계를 확인한다. **줄어든 수 = 호출 수**여야 한다(아니면 기법이 잘못된 것).
5. 0 이 되면 **라이브 `data` 본문 ↔ 로컬 스냅샷 재귀 정규화 비교** + status 분포 대조로
   내용·메타가 하나도 안 바뀌었음을 확인한다.

### 왜 자동화가 안 되나

`update_item` 은 MCP 도구라 셸 스크립트로 반복 호출할 수 없다. 서버에 bulk 엔드포인트도 없다.
`kit-export` 는 **읽기 전용**이다. 그래서 개별 호출이 유일한 경로다.

## 6. 이 라운드가 남긴 판단 (되돌리지 말 것)

- **해소 기법은 `status`-only 다.** `data_mode='merge', data={}` 는 링크를 재계산해 하류를 되살리고,
  상호 의존 쌍 72개(전 AC↔UC 등) 때문에 **원리적으로 0 에 수렴하지 않는다.** 다시 시도하지 말 것.
- **stale 판정은 서버 라이브로만.** 로컬 `.staging` 은 ①stale 플래그만 바뀌면 갱신되지 않고
  ②retired 79건이 빠진다. **"0건"을 로컬로 확인하고 보고하지 말 것.**
- **stale 을 검사 없이 일괄로 닫지 않는다.** 이번에 실제 결함 3종 21 ITEM 이 나왔다(§3-1).
  다만 검사는 **원인 ITEM 단위**로 하면 된다(폐기 용어 grep 으로 환원).
- **개명 이력 병기는 지우지 않는다.** "구 X → 신 Y" 는 결정 보존 장치다.
- **표본 2건으로 일반화하지 않는다.** 하류 의존자가 없는 ITEM 만 골라 시험하면 어떤 전파 규칙이든
  통과한다 — 라운드 4 가 그 함정에 걸려 검증된 기법(`status`-only)을 잘못된 기법으로 갈아치웠다.
- **검사기가 무엇을 못 보는지 함께 물을 것.** 이번 사각 두 개(플래그-only 미갱신 · retired 제외)는
  둘 다 "숫자는 나왔는데 그 숫자가 전수가 아니었다" 유형이며, 이 저장소에서 네 번째다.
