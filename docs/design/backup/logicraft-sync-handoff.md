# LogiCraft 동기화 — 진행 핸드오프 (재개용)

> 최종 갱신: 2026-05-30 · project_id: `4ece2c3f-8e99-46f5-9580-71108a76e578` (KLID 저작도구 2차)
> 정본 진행기록: `docs/design/logicraft-sync-2026-05-29.md` §3 B항. 본 파일은 **다음 세션 재개용 작업노트**.
> (이전엔 /tmp 휘발성에 있었음 — git 추적 위해 docs/로 이전)

## 현재 상태 (2026-05-30 종료 시점, coverage 검증됨)

프로젝트 전체 implementation coverage = **92% (176/192 implemented)**, planned 16 = 폐기 항목과 정확히 일치 → **활성 항목 전부 100% 구현 반영**.

| 타입 | 등록 | 상태 |
|------|:---:|------|
| domain | 16 | 활성 13 + 폐기 3 (DOMAIN-011~016 D8 신규: 마킹·비식별화·포털·시스템설정·작업배정·관제통지) |
| feature | 7 | 7/7 implemented |
| domain_feature | 40 | 활성 27/27 implemented (planned 13 = 폐기) |
| api_endpoint | 89 | API-001~089, 89/89 implemented (운영 엔드포인트 + TUS 5 + stream) |
| screen_spec | 29 | SCREEN-001~029, 29/29 implemented (consumes_apis→API 링크) |
| code_module | 21 | MOD-001~021 (BE 도메인 패키지 18+common+ai-server+frontend, implements_domains/realizes_* 링크) |
| erd | 9 | 활성 7 implemented (폐기 ERD-005·006 제외) |
| requirement | 18 | 활성 17 implemented (폐기 REQ-002 제외) |
| legacy_artifact | 129 | 1차 카탈로그(원본). 38 매핑 / 91 미매핑 |

**완료된 추적 체인**: 도메인 → 기능(FEAT/DFEAT) → API → 화면(SCREEN) → 코드모듈(MOD) + ERD/REQ 구현상태. 전 계층 연결.

## 커밋 이력 (design 브랜치, 미push)
- 350780d D8 ERD/REQ 구현상태 / c556f36 D8 code_module / b473b27 D8 api+screen 수치정정 / bb60580·9bf9b27 D8 api+screen / f2a7de8 Phase3 D1·D6·D7 / 94daa74 Phase1~3 / b04b1e8 폴리곤기능

---

## 내일 할 일 (잔여, 모두 선택 — 정부문서 보강)

### 1. LEGACY 폐기매핑 — removed 31개 완료 (2026-05-30), 나머지 잔여
1차 카탈로그 129개 중 91 미매핑. 2차 폐기/유지 결정을 `deprecation_status`에 반영해 1차↔2차 추적 완결.

**✅ 완료: 명백한 폐기 31개 → removed** (update_item data_mode=merge data={deprecation_status:"removed"}, 전부 version2):
LEGACY-050,052,054,055,058,059(/projects API) · 062,063(프로젝트 작업리스트) · 086,087,126(게시판) · 089,090,097,123(생성형AI/업로더) · 099,100,101,103,104,105,106,107,108,109,111,113,115,116,117,118(03-02-* 프로젝트관리 일체).

**✅ 검증된 사실 (서브에이전트 보고 정정)**:
- `update_item`은 LEGACY-* ID **정상 처리**(get_item도 동작). 서브에이전트가 "get_item이 LEGACY 못 받음"이라 한 건 오류.
- `find_legacy_artifact(deprecation_status="removed", limit, offset)`로 **페이징 조회 가능**(items_truncated + offset). 즉 91 전수 식별이 "막힘"이 아니라 **offset 페이징으로 가능** — 서브에이전트의 "42개 식별 불가"는 도구 사용법 문제였음.

**잔여 (재개 시)**:
- **deprecated 11** (다른 방식 대체, 미반영): LEGACY-046,048,049(1차 오토라벨 API→내부 흡수) · 069,081,082,083,084(스켈레톤/빈상태/로딩 보조화면→2차 흡수) · 120,121,122(03-03-* 영상이미지관리→영상단위 재설계). → update_item data={deprecation_status:"deprecated"}.
- **확인필요 9** (사용자 판단 필요): LEGACY-060(UI가이드) · 064(작업리스트+배정 혼재) · 066(SAM AI Tool) · 071(객체/라벨 패널) · 073(메타입력) · 074(이슈입력) · 075(작업이력) · 076(메타+이슈상세) · 080(검수 메타상세).
- **나머지 미분류**: `find_legacy_artifact(deprecation_status="active", limit=200)` 페이징으로 전수 조회 후 02-02-* 작업/검수 화면(067~080 등)의 매핑누락 여부 판정. 검수 화면(077·078·079)·캔버스(070)·패널(067·068·072)은 has_brownfield_refs=true라 이미 매핑됨(active 유지 정상).

### 2. brownfield 보완 (legacy_source / ADR)
API·화면·모듈 다수가 `BROWNFIELD_REUSE_WITHOUT_SOURCE`(legacy_source.identifier 없음)·`MODIFIED_WITHOUT_DECISION`(decided_by ADR 없음) warning. 날조 금지로 비워둠. 1차 LEGACY ID·ADR 확정 후 update로 채움.

### 3. 정부 제출문서 export (★ 핵심 도구)
`generate_brownfield_report(project_id, format="markdown"|"csv")` — **"한국 공공 SI K-DOC 데이터 변경 명세서 / 운영팀 인계자료 입력용"이라 명시됨.** 정부 제출문서는 이걸로 export하는 게 정석. LEGACY 매핑 완료 후 실행하면 매핑표+통계+ADR추적+legacy repo분포 포함.

### 4. 선택 타입 0건 (정부문서 필요시)
nfr · adr · glossary · use_case · domain_event · infra_component = 0건. 필요하면 실제 근거 기반으로 추가(추측 금지).

### 5. SCREEN-004 로그인 모순 확인
CLAUDE.md "독립 로그인 없음" ↔ 코드 LoginPage 존재. dev-only로 등록함. 운영 노출 여부 코드 확인 잔여.

---

## ★★ LogiCraft create_item 함정 (반복 실수 방지 — 메모리에도 기록됨)
- `title`·`change_summary`는 **최상위 인자** (data 안 아님). domain-required 타입은 `domain_id`도 최상위.
- **타입별 스키마 추측 금지** — 등록 전 `get_item_schema(type)`로 allowed_fields 확인(크면 서브에이전트 추출). 추측 키 하나라도 있으면 객체 전체 E_VALIDATION 거부.
  - domain: ubiquitous_language=[{term, **meaning**}] (definition 아님)
  - screen_spec(id=SCREEN): purpose/route/device/consumes_apis/required_roles. summary·description·screen_type·permissions·implements_features = **무효**. FEAT 직접링크 불가(purpose 텍스트로).
  - code_module(id=MOD): kind(component/hook/util/type/schema/api_client/service/constant_group)+file_path+name required. 도메인=implements_domains[{domain_id,responsibility,primary}]. implementation 필드 없음(brownfield.status로).
- domain은 mark_implementation 불가(E_NOT_TRACKABLE). ERD·REQ·feature·domain_feature·api_endpoint·screen_spec는 가능.
- **거짓 완료 커밋 주의**: 등록 결과(coverage) 확인 전에 "완료" 문서/커밋을 같은 배치에 넣지 말 것 — 이번 세션에 screen_spec 0건인데 "29/29 완료" 커밋이 나갈 뻔함(분류기가 차단). 항상 get_implementation_coverage / list_items로 검증 후 문서·커밋.
- 플랫폼 도구결과 유실 빈발 → 소량 배치, 호출 후 검증.
