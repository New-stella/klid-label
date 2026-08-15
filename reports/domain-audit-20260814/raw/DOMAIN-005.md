# DOMAIN-005 검수 — 감사 결과

- 10차원 완주 (SKIP 0). ⚠ 배치 A 1차 시도가 **인프라 장애로 5건 전부 실패**(`Agent stalled 600s`) → 동시 3건으로 낮춰 재시도해 전건 완주.
- 원시 갭 **106건** — P0 27 / P1 58 / P2 21 (현재까지 최다)
- 차원별: coverage 13 · links 13 · schema 11 · content 20 · diagram 6 · stale 17 · policy 10 · acceptance 6 · requirement 3 · test_scenario 7

## ★ 지배적 결함 1 — 「검수 종결 상태값」 이 `COMPLETED` 로 6곳에 잔존

현행 확정: **검수 종결 = `APPROVED`**. `LsRawDataStatus` 를 `COMPLETED` 로 전이하는 코드는 없다.
`EVT-006` 은 이미 정정 완료 — *"승인이 전이시키는 값은 COMPLETED 가 아니라 APPROVED 다. **COMPLETED 로 전이하면 승인된 영상이 데이터마트에 한 건도 노출되지 않으므로** 구 서술은 성립할 수 없다."*

| ITEM | 위치 | 검출 차원 |
|---|---|---|
| `DOMAIN-005.description` | "[★검수 완료 = 작업 완료] … `COMPLETED` 로 전이한다" | CNT·STL·POL |
| `UC-023` | description + `main_flow[3]` + `postconditions[0]` **3곳** | STL·ACC |
| `AC-022.scenario.then[0]` | "승인 시 LS_RAW_DATA_STATUS **COMPLETED** 전이" | ACC |
| `CDIAG-006` | description `[상태 소유권]` 절 | CNT·STL·POL |
| **`SCREEN-019`** | 검수 액션 섹션 "상태가 **완료(COMPLETED)** 이어도…" | **STL 신규 발견** |
| **`API-008`** | "승인 상태는 **APPROVED/COMPLETED** 로 유지" — **같은 필드 앞부분은 `COMPLETED` 를 "검수 대상이 아닌 상태"로 분류** → 자기모순 | **STL 신규 발견** |

> **경계 확정**: requirement 차원이 *"REQ 계층에는 잔재가 **없다**"* 를 명시 확인. `TEST-004.steps[1]` 도 `APPROVED` 로 **현행이 맞다**.
> ⚠ 단 acceptance auditor 가 *"3층이 모두 COMPLETED 라 **ITEM 내부 대조만으로는 확정 불가** — 코드(`ReviewService.approve`/`ReviewStateMachine`) 실측 확인 권장"* 이라고 한계를 표시했다.

## ★ 지배적 결함 2 — 「통지는 export SUCCEEDED 후」 순서 계약 미전파

현행: `EVT-006`(승인) → export **전량 재생성** → `EVT-009`(산출 완료) → `EVT-003`(통지). **"승인 즉시 통지"는 폐기.**

| ITEM | 잔재 |
|---|---|
| `CDIAG-006.classes[RawDataStatus]` | "APPROVED 전이 시 **관제서버 통지 트리거**" — 같은 ITEM 상단 `[★승인 이후 순서 정정]` 이 폐기 선언 |
| **`CMP-005` 부속 배열 3곳** | `components[ReviewApprovedEvent]`·`relationships`·`external_dependencies` 전부 "승인 → 관제 통지 **직결**". description 은 이미 *"실제 수신자는 `DatasetExportBridge` 다 … 통지가 먼저 나가면 관제가 **구 버전 폴더를 픽업**한다"* 로 폐기 선언 |
| `AC-022.then` | export 재생성 단계 자체가 없음 |
| `TEST-004.steps` | seq1 승인 → seq2 스냅샷 → seq3 통지. **export 성공 확인 단계 부재** |

## ★ 지배적 결함 3 — 「재생성·통지 트리거 = 검수 승인 한 곳」 미전파 (P0)

2026-08-07 확정. 구 정책 *"수정 즉시 재생성·즉시 통지"* 폐기. 수정 시엔 **재검토 표시(`REVLT_YN='Y'`)만** 세우고 통지 보류.

- `DOMAIN-005.description` `[승인 후 수정]` = *"수정 경로는 export 를 새 버전 폴더로 재생성하고 `TASK_MODIFIED` 를 보낸다"* ← 구 정책
- `UC-023.alternate_flows[검수완료 후 수정]` = *"동일 작업 ID 유지, `TASK_MODIFIED` 통지 발행"* ← 구 정책
- **`TEST-004.steps[5]`** = *"수정 발생 → **디바운스 만료** → 통지"* ← **구 정책을 검증**. 정작 같은 ITEM 의 `objective` 는 *"그 수정이 **재검수에서 승인될 때**"* 라 **한 ITEM 안에 두 진실원 공존**
- `EVT-004`·`UC-009` 는 **이미 정정 완료**("재검토 대기 중에는 디바운스 flush 를 보류")

## ★ 지배적 결함 4 — `ADR-002`·`ADR-003` 폐기 모델이 본문에 잔존

| ITEM | 잔재 | 비고 |
|---|---|---|
| `DFEAT-021` | description "**1차 검수자**가…" · `user_story.as` "**검수자(1차)**" | **제목·`brownfield.diff_summary` 는 이미 현행** → 본문만 뒤처짐 |
| `DFEAT-024` | **제목·description·user_story 3곳 모두 "관리자 확인 요청"** | `brownfield.diff_summary` 는 이미 "REVIEWER 흡수" |
| `DFEAT-025` | `user_story.as` = "**검수자/관리자**" | |
| `DFEAT-023` / `CDIAG-006.classes[FrameColor]` | "**빨강=관리자확인요청**" | 도메인 용어사전은 **연두·주황 2색만** 정의 |
| **`DOMAIN-005.brownfield`** | `status="preserved"` + notes "1차/2차 2단계. **Pass 2에서 단순화 예정**" + `decided_by` 공란 | **하위 `DFEAT-021`·`024` 는 이미 `modified`** — 상위 도메인만 뒤처짐 |

> **반증됨(재보고 금지)**: `DFEAT-022`('2차 검수')·`ERD-002`(검수 ERD) 는 **둘 다 이미 `deprecated`** 이고 backward 0건 — 활성 위반 아님.

## ★ 한글 표기 손상 2건 (프로젝트 규칙이 경고한 유형)

| ITEM | 손상 | 정정 |
|---|---|---|
| `CMP-005.description` | "`TASK_COMPLETED` 를 **보람다**" | 보낸다 |
| `API-008.description` | "WARN 로그를 **남린다**" | 남긴다 |

⚠ 두 ITEM 모두 **과거 같은 유형을 이미 겪었다** — `CMP-005` change_summary *"곀→곧 1건"*, `API-012/013/015` *"냙관적→낙관적"*. **`API-008` 만 그 스윕에서 누락**됐다.
> stale auditor 가 자기 열람 범위에서 추가 스캔 → **신규 0건**. 단 `SCREEN-018`·`ERD-015`·`ERD-023`·`CDIAG-014`·API 12건 본문은 **미스캔**.

## ★ VLM 메타 서브시스템이 3개 도메인에 분산 + 상호 링크 0

| 자산 | 소유 |
|---|---|
| `CDIAG-014` VLM 시계열 메타 **클래스도** | DOMAIN-005 |
| `CMP-008` VLM 메타 **컴포넌트도** | DOMAIN-004 |
| `API-066`·`API-067` 메타 조회·수정 | DOMAIN-010 |
| `API-016`·`API-017` 메타 검수 승인·반려 | DOMAIN-005 |

두 다이어그램이 **동일 클래스 집합**을 그리는데 `depicts_dfeats`·`referenced_items` 양쪽 다 공란. `API-016/017` 은 **backward 링크 0건**(호출 화면·DFEAT 없음).
`CDIAG-014` 본문에 **폐기 VLM 계약 3건**(D004 인계 → 확인됨): `VlmResultRequest`("마킹별 자연어 서술 항목 **배열**"·`vlmMetaItems[]`) · `VlmTimeseriesRequest`(폐기 필드 `eventName`·`marks`) · `VlmClient`("비동기 위탁(**수락만 동기 확인**)").

## P0 (27건) — 주요 항목

| gap | 차원 | 대상 | 내용 |
|---|---|---|---|
| D005-SCH-001 | schema | ERD-015, ERD-023 | **같은 테이블 `LS_DATA_ISSUE` 가 두 ERD 에 다른 컬럼 집합으로 중복 정의** (6 vs 10). 소비 API 는 10컬럼판에 의존 → **스키마 진실원이 둘** |
| D005-SCH-004 / D005-POL-008 | schema·policy | API-178 | 삭제된 **`MNG_ACCT_USER`** 현재형 인용(`ADR-042`). **2026-08-13 갱신본인데 잔존** |
| D005-SCH-003 | schema | API-103, API-104 | 존재하지 않는 컬럼명 **`ISSUE_COMMENT_SN`** (ERD 정본·표준용어는 `CMNT_SN`) |
| D005-CNT-016 / D005-STL-009 | content·stale | API-009 | description "**REVIEWER 전용**" ↔ 같은 ITEM `change_summary`(v5) "**검수자 전용이 아니다**" → **이대로 만들면 작업자에게 403** |
| D005-CNT-001~007 | content | 다수 | 위 지배적 결함 1~4 |
| D005-STL-001 | stale | MOD-010, SCREEN-015 | 활성 모듈이 **deprecated 화면을 realizes** |
| D005-COV-002 | coverage | API 12건 | orphan (DFEAT 매핑 0) |
| D005-COV-005 | coverage | UC-023 | 도메인 핵심 UC 에 **happy path SEQ 0건** |
| D005-TST-003 | test_scenario | TEST-004 | 폐기된 "수정 즉시 통지" 검증 + objective 와 자기모순 |
| D005-POL-009 | policy | CDIAG-014 | 폐기 `describe` 배열 규격 |

## P1 (58건) — 축별 요약

**추적성**: DFEAT 4건의 `implemented_by_endpoints`·`invokes_apis`·`persists_in_tables`·`related_acceptances` 전건 `[]` / API **16건 전부** `implements_features` `[]` / `SCREEN-018`·`019` `realizes_use_cases` `[]` / `CMP-005`·`CDIAG-014` `depicts_dfeats` `[]` / `EVT-006` 발행자 링크 0건(`triggers` 미기재 — `documented_emitters` 는 표시 전용) / `LS_RAW_DATA_STATUS` 를 claim 하는 DFEAT 0건

**오배선**: `MOD-010.realizes_features = FEAT-004`(증강) → 올바른 값 **`FEAT-009`**(VLM 메타). `diff_summary` 에도 같은 오류 복제 / `CDIAG-006.realizes_features` 에 **FEAT 가 아닌 DFEAT ID** (D004 `CDIAG-005` 와 동일 패턴)

**표준용어**: `UPD_DT`(논리명 "수정일시") → 행안부 표준은 **`MDFCN_DT`** (D003 과 동일 유형) / 논리명 사전 드리프트 5건(`REPORTED_USER_NO`·`ISSUE_RSN`·`SRC_SN`·`IGI_CYCL`·`STP_CYCL`) / `VER` 타입이 등록 도메인(명V20 문자형)과 불일치 — **낙관적 잠금 카운터라 숫자가 맞으므로 사전 보완이 필요한 축**

**계약 결손**: `DATA_STTS_CD.code_values` 에 **도달 경로 없는 `COMPLETED`** 존치 / 동일 DTO(`ReviewResponse`)가 엔드포인트마다 **14필드 vs 13필드** — `API-009`(검수 상세)에서 **재검토 표시를 읽을 수 없음** / `API-011` 응답이 `ERD-023` 확장 이전 **6필드 고착**(`ISSUE_TYPE_CD`·`ISSUE_STTS_CD`·`SRC_SN` 부재)인데 `SCREEN-019` 가 지금도 소비

**검증 산출물**: `DFEAT-023`·`DFEAT-049` **AC 도달 경로 구조적 부재**(realize UC 0건) / **반려→재작업→재제출 반복** 통합시험 0건 / NFR **6건** 전부 시스템시험 0건(프로젝트 `kind=system` 0)

**brownfield**: `MOD-009`·`UC-023`·`SCREEN-019` 가 `modified` 인데 `legacy_source` 부재 / `MOD-011` 에 **"[추정 — 1차 대응 확인 필요]" placeholder 존치**

**본문 오염**: `ERD-015` 가 **존재하지 않는 마이그레이션 번호 V5/V36/V38** 인용(이력이 베이스라인으로 접힘) / `API-008` 에 결함 경위·테스트 클래스명·"통일하지 말 것" 지시문 / `EVT-006` 에 `.java` 파일 경로 / `UC-010` 에 경위 문단이 `brownfield.notes` 와 중복 / `SCREEN-018.purpose` 에 변경 경위·지시문

## P2 (21건) — 주요 항목

- `stale=true` **플래그만** 잔존(본문 정합): `DFEAT-049`·`UC-010`·`MOD-009`·`SCREEN-019`
- `implementation.status=planned/0%` 고착: `CMP-005` 컴포넌트 9건 · `CDIAG-006` 속성 12건 · `UC-023`
- `AC-010` 만 `derived_domain_ids` 공란 (서버 자동 계산 미반영)
- `API-104` 입력 상한(1000) ↔ `CMNT_CN` 컬럼(4000) 불일치
- `TEST-004`·`TEST-003` `related_domains` 공란 (**프로젝트 레벨 — dedupe 대상**)
- `TEST-004.data.status='draft'` ↔ ITEM `status='approved'` 불일치
- `RFP-003` heading3(데이터마트 연계)을 담은 **활성 REQ 0건** (유일 대응 `REQ-011` 이 deprecated)

## 도메인 경계 재검토 (사용자 결정)

- **`UC-010`(증강 영상 활용 여부 검수)이 `DOMAIN-005` 귀속** — 그런데 도메인이 *"[검수 대상 밖] 증강 결과의 사용·폐기 결정은 별도 축"* 이라 **명시 배제**했고, 실현 FEAT·화면도 전부 증강 축(`FEAT-004`/`SCREEN-023`). ⚠ 단 UC-010 **본문 자체는 `ADR-045` 정합**이라 정책 위반은 아님
- **VLM 메타 축**(`API-016`·`017`·`CDIAG-014`·`MOD-010`)이 도메인 소유인데 **책임 선언·DFEAT 0건**
- 도메인 책임 R3(승인 연쇄)·R4(재검수·재승인)를 맡는 **활성 DFEAT 0건** — API 계약에는 이미 반영돼 있는데(`API-014`·`API-008` 의 `needsRecheck`) 기능 계층만 비어 있음

## 미확인 층

정적 렌더 미러(`source_hash`) · 로컬 키트 스냅샷 · 위키 · 테스트케이스 — 전 차원 미확인. 1차 소스 grep 미수행.
한글 손상 스캔은 **열람한 본문에 한정** — `SCREEN-018`·`ERD-015`·`ERD-023`·`CDIAG-014`·API 12건 미스캔.
`TASK_COMPLETED` 페이로드 필드 수(7 vs 관제 계약 9)는 TEST·UC 두 층이 같은 서술이라 **이중 인용 요건 미충족으로 미확정** — content/schema 차원 재판정 필요.
