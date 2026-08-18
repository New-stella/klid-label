# DOMAIN-006 통계·대시보드 — 감사 결과 (⏸ 7/10 차원, 미완)

> **미실행 3차원**: `acceptance` · `requirement` · `test_scenario`. 사용자 지시로 세션 종료 — 재개 시 첫 작업.
> ⚠ 이 도메인은 **UC·AC·SEQ·TEST 가 전역 스캔 결과 0건**이므로 acceptance 는 *부재 자체가 갭*(SKIP 금지), requirement 는 모드 A SKIP 가능성이 높다.

- 완료 7차원 원시 갭 **55건** — P0 11 / P1 29 / P2 15
- 차원별: coverage 6 · links 4 · schema 6 · content 14 · stale 14 · policy 8 · diagram 3

## ★ 지배적 결함 1 — 「제목만 정정, 본문은 구 모델」 (P0, 4개 차원 독립 검출)

| ITEM | title (정정 완료) | description·user_story (미정정) | 위반 |
|---|---|---|---|
| `DFEAT-027` | "검수자/담당자 대시보드" (v3) | *"**관리자·프로젝트담당자**의 담당 **프로젝트** 현황"*, `as="관리자/담당자"` | `ADR-003`(ADMIN 폐기) + `ADR-001`(프로젝트 개념 폐기) **동시** |
| `DFEAT-028` | "작업 통계" (v3) | *"**프로젝트의** 전체/권한별/상태별"*, `as="관리자/담당자"`, **slug 도 `프로젝트-통계-…`** | 동일 |

각 ITEM 의 `change_summary` 가 *"title 정정: 관리자→검수자"* · *"title 정정: 프로젝트 통계→작업 통계"* 라고 **명시**하는데 본문만 남았다.
→ **한 ITEM 안에 title(정정본)과 body(구 모델)가 두 진실원을 이룬다.**
> 검출: COV-003 · CNT-001/002 · STL-004/005 · POL-001/002 (4개 차원)

## ★ 지배적 결함 2 — 「검수완료 주수치」 정책이 API 계약에 없다 (P0)

도메인이 **★로 확정 선언**: *"[★주수치는 검수완료 기준] … 검수완료 수치를 주수치로 쓰고 전체를 병기한다. **둘을 섞어 하나로 보이면 달성률이 부풀려진다.**"*

| 소비자(정합) | 생산자(부재) |
|---|---|
| `SCREEN-011` (v16) *"주 수치=`approvedImageCount`(검수완료 기준)"* · *"하단 이벤트 분포는 `approvedImageDistribution`"* | **`API-055` 응답에 `approved*` 4필드 전부 없음** (`API-055` 최종갱신 2026-06-15 — **60일 낙후**, `stale=false`) |
| `SCREEN-021` (v22) `binds_to="overall.approvedImageCount"` · *"검수완료 기준(`approvedEventDistribution`)"* | **`API-057` 응답에 `approved*` 3필드 + `dailyCounts` 없음** (2026-08-08 갱신했는데도 미반영) |

→ **`SCREEN-021` 의 일별 작업량 차트는 공급원(`dailyCounts`) 자체가 없다.**
⚠ 같은 도메인 `API-056` 은 2026-08-14 에 `approvedLabelCount` 를 반영 — **도메인 내부 비대칭**.
> 검출: SCH-001/002 · CNT(신규후보) · STL-007/008 · POL-005 (4개 차원)

## ★ 지배적 결함 3 — 도구가 못 잡는 그래프 단절 (**메타 발견**)

**`CDIAG-009` 는 `get_neighbors` 가 forward·backward 둘 다 빈 배열**(완전 고립)인데 `depicts_dfeats` 는 선언돼 있다.
→ **`list_diagram_coverage` 는 배열을 직접 읽으므로 100% 로 보고한다. 도구 사각.**

귀결:
- `CDIAG-009` 는 **2026-06-02 생성 이래 무갱신·`stale=false`** — 정합의 근거가 아니라 **cascade 가 도달한 적이 없다는 뜻**
- 대조군 `CDIAG-006`·`CDIAG-008` 은 정상 materialize + 2026-08 재확인 갱신
- 그래서 이 다이어그램만 폐기 상태값을 그대로 보유: `WorkStatus` enum 에 **`REVIEW_REQUESTED`**(구 '확인요청', `ADR-003` 폐기) + 종결값이 **`COMPLETED`**(현행 `APPROVED`) + 구 식별자 `RawDataStatus.DataStatus`

> **`get_neighbors(DOMAIN-006).backward` 에 다이어그램이 0건**이다.

## ★ 지배적 결함 4 — 검수 종결 상태값 `COMPLETED` 잔존

`SCREEN-011` `sections[4]` **2곳**(`components[2].note` + `section.description`): *"**COMPLETED** 100·IN_PROGRESS 50·그 외 0"*
같은 화면 `sections[3]` 은 이미 `reviewStatusCd=APPROVED` 사용. `API-056`·`API-057` 도 APPROVED 축 정합.
→ **이 매핑대로면 검수완료 작업의 진행률이 항상 '그 외 0%'로 떨어진다(조용한 오표시).**
`CDIAG-009` 도 같은 축(위 결함 3).
> D005 에서 6곳 검출된 것과 **동일 유형** — 프로젝트 전체 축으로 묶어야 함

## P0 (11건)

| gap | 차원 | 대상 | 내용 |
|---|---|---|---|
| D006-COV-001 | coverage | API-055~058 | 활성 API 4건 **전건 orphan** (DFEAT `implemented_by_endpoints` 합집합 = ∅) |
| D006-SCH-001 / D006-STL-007 / D006-POL-005 | schema·stale·policy | API-055 | `approved*` 4필드 부재 (화면·구현엔 실재) |
| D006-SCH-002 / D006-STL-008 | schema·stale | API-057 | `approved*` 3필드 + `dailyCounts` 부재 |
| D006-CNT-001 / D006-STL-004 / D006-POL-001 | content·stale·policy | DFEAT-027 | 본문 '관리자·프로젝트담당자'·'프로젝트' |
| D006-CNT-002 / D006-STL-005 / D006-POL-002 | content·stale·policy | DFEAT-028 | 본문 '프로젝트'·'관리자/담당자' |
| D006-STL-002/003 | stale | API-055, API-056 | deprecated `ERD-003`·폐기 테이블 `LS_PJT_JOB_STATS` 인용 → **⚠ policy 차원이 "위반 아님"으로 판정(아래 §상충 판정)** |

## P1 (29건) — 축별 요약

- **추적성**: `DFEAT` 3건 `implemented_by_endpoints`·`persists_in_tables`·`invokes_apis`·`related_acceptances`·`acceptance_rules` 전건 `[]` / `API` 4건 `implements_features` `[]` / `SCREEN` 3건 `realizes_use_cases` `[]` + `references_dfeats` **키 부재** / **`CDIAG-009` 그래프 링크 0건**
- **이벤트 분포 "6종" 고정 서술** — `API-055`(2곳)·`API-057`. 화면은 *"고정 6개 슬롯이 아니라 … 개수 무관"*·*"개수 가변"* 으로 정합. **표시명 그룹 정책 위반** (미등록 코드가 통계에서 조용히 사라지는 계약)
- **`API-057.processing.description` = "처리 현황 **5카드**"** — `SCREEN-021`(v22)이 *"개별 카드 5개·grid-cols-5 레이아웃이 **아니다**"* 로 **명시 폐기**한 형태. **생산자가 소비자의 폐기 레이아웃을 서술** (STL 신규 검출)
- **`API-056`·`API-058` `parameters=[]`** — 실재하는 `workerId`·`period` 쿼리 파라미터 미선언. 정작 같은 ITEM 의 400 응답이 그 파라미터를 전제
- **`eventTypeCd` example `"FALL"`** — 구현은 표시명 그룹 대표코드(`EV02000201` 형식). **어느 코드 체계에도 없는 값**
- **본문 오염**: `SCREEN-011/020/021` 에 FE 훅(`useVideos`·`useTasks`)·`data-testid`·CSS 클래스(`grid-cols-5`)·차트 라이브러리(`recharts`) / `API-055` "V1.x 스텁" · `API-058` "placeholder" 구현 상태 / `@Pattern`·`ResponseEntity<String>` 어노테이션 / `API-056` 에 **"이 단위 비대칭은 의도이며 통일하지 않는다"** 지시문(2필드 중복)
- **`API-056` 신설 3필드(2026-08-14)가 구현에 없는데 `implementation.status='implemented'`/`progress=100`** — 설계 선행은 정상이나 메타가 사실과 충돌

## P2 (15건) — 주요 항목

- `MOD-012` `stale=true` **플래그만**(본문 정합, 7일 경과)
- `DFEAT` 3건 `implementation` 100% 주장인데 `modules`·`records` 전부 `[]` — 근거가 2026-05-30 감사 1건뿐
- **활성 ERD 0건** — 유일 `ERD-003` 이 deprecated(`LS_PJT_*`). **영상 단위 통계 테이블 미정의**
- `brownfield.decided_by` 공란인데 `status="preserved"` — 실제로는 `ADR-003`·`ADR-001` 근거 정정 이력 존재
- **`ADR-038` 인용 0건** — 도메인 description 이 결정 문구를 거의 그대로 옮겨 적으면서 ID 미인용(같은 문단이 `ADR-003` 은 인용해 **비대칭**)
- `API-055.notices` "V1.x 스텁 — 항상 빈 배열"인데 **이 도메인에 소비 화면 0건**(DOMAIN-009 귀속 확인 필요)
- `SD-014` 에 와이어프레임 대조 메모 *"더하거나 뺀 곳은 없다"*

## ★ auditor 간 상충 — 판정 완료

| 사안 | schema | links | **policy(판정)** |
|---|---|---|---|
| `API-055`·`API-056` 의 `brownfield.diff_summary` 가 deprecated `ERD-003`·`LS_PJT_JOB_STATS` 인용 | 정정 대상 P1 | 청소 대상 아님 | **위반 아님 — links 지지** |

**판정 근거**: 두 인용 모두 `brownfield`(legacy provenance) 필드이고 인용문이 **"1차"를 명시**하며, `ERD-003` 자신이 description 에 *"고도화에서 폐기 — 프로젝트 단위 통계 폐기…이력 보존용"* 으로 그 1차를 기록한 ITEM이다. **폐기 개념을 활성 사양으로 인용한 것이 아니라 폐기 사실을 출처로 가리킨 것.**
→ **`D006-STL-002`·`D006-STL-003` 는 기각.** `ERD-003` 의 `belongs_to_domain` 잔존도 policy 위반 아님(schema/links 축 판단 사안).

## ★ 한글 표기 손상 검사 — **0건** (이 라운드에서 가장 엄밀한 스캔)

- **범위**: 13 ITEM 본문 문자열 **252건**, 서버 원문 덤프(전사 없음)
- **필드**: `description`·`purpose`·`goal`·`user_story`·`note`·`notes`·`summary`·`rationale`·`meaning`·`label`·`diff_summary` — **중첩 `responses[].description`·`schema.properties[].description`·`sections[].components[].note`·`ubiquitous_language[].meaning`·`tables[].columns[].description` 포함**. `change_summary` 제외
- **방법**: ①알려진 오타 15종 grep ②**희귀 음절 빈도 분석** — 음절 334종 중 **2회 이하 등장 104종 전수 문맥 확인**
- **결과 0건.** 희귀 음절은 전부 정상 어절(`람`=범람, `린`=달린, `뺀`=뺀 곳, `컴`=컴포넌트)
- ⚠ *"미검출을 부재로 읽지 말 것 — 정적 렌더 HTML 본문은 스캔 대상이 아니었다"*

## ★★ 메인이 전파한 오탐 — 정정 (다른 도메인 소급 필요)

**`realizes_features` 에 DFEAT ID 가 들어간 것은 결함이 아니다.**
> `get_item_schema(class_diagram).hints.workflow_notes` 원문: **`realizes_features : 이 다이어그램이 실현하는 FEAT/DFEAT id 목록`** — DFEAT 명시 허용.
> 게다가 `link_types_from` 4종(`references`·`belongs_to_domain`·`migrated_from`·`depicts`)에 없어 **그래프 링크를 만들지 않는 정보성 필드**다.

→ **소급 기각 대상**: `D004-LINK`(CDIAG-005) · **`D005-LINK-004`**(CDIAG-006). 메인이 D004·D005 프롬프트에 "반복 결함"으로 주입한 것이 원인.

## 미확인 층

정적 렌더 미러(`SCREEN-011/020/021` main.html — `source_hash` 보유) · `SD-014` 디자인 HTML · 로컬 키트 · 위키 · 테스트케이스 — 전 차원 미확인.
1차 소스 grep 미수행. 구현 코드는 `schema` 차원만 부분 대조(`DashboardSummaryResponse`·`OverallStatSummaryResponse`·`StatsController`·`StatsQueryRepository`).
`D006-DIAG-002` 의 근본 원인(`domain_id` 미설정 vs 서버측 인덱싱 누락)은 read-only 로 확정 불가.

---

# 잔여 3차원 (재개 세션 — acceptance · requirement · test_scenario)

> 앞선 7차원과 합쳐 **D006 = 10/10 완료**. 잔여분에서 8건 추가 → 도메인 누계 **63건**(P0 11 / P1 33 / P2 19).
> 이 도메인은 UC·AC·REQ·TEST 귀속이 **전부 0건**이라, 세 차원 모두 "부재 자체"가 판정 대상이었다.

## acceptance (4건 — P1 3 / P2 1)

- **D006-ACC-001 (P1)** 도메인 귀속 활성 UC 0건 → UC→AC 추적 경로 자체가 미성립.
  근거: 활성 UC 25건 전수 제목 확인 결과 통계 축 0건 · `get_neighbors(DFEAT-026/027/028).backward` 3건 전부 `[]`.
- **D006-ACC-002 (P1)** 활성 DFEAT 3건이 (a)UC 경유 AC (b)직접 verifies AC **양 경로 모두 0**.
  3건 공통으로 `acceptance_rules`·`related_acceptances`·`implemented_by_endpoints`·`invokes_apis` 전부 빈 배열.
  활성 AC 25건 중 통계 축 0건(표본 AC-020·023·024 는 `derived_domain_ids=DOMAIN-010` 로 타 도메인 확정).
  priority 가 3건 모두 `should` 라 P0 승격 조건(must/critical) 미해당.
- **D006-ACC-003 (P1)** 도메인 description 이 명시한 **확정 정책 3건을 검증하는 AC 가 없다** —
  ①검수완료 주수치 + 전체 병기 ②집계는 BE 전체 기준(페이지 단위 집계는 오답) ③증강·해상도 파생 포함.
  위반해도 감지 수단이 없는 상태. `DFEAT-028.acceptance_rules=[]` 라 DFEAT 본문에도 규칙이 없다.
- **D006-ACC-004 (P2)** REQ 22건 `acceptance_criteria` 전건 공란 — **프로젝트 레벨, dedupe 대상**(도메인 귀속 REQ 0건이라 고유 영향 없음).

## requirement (1건 — P2)

- **D006-RQ-001 (P2)** RFP-006/007 의 산출물 목표(이미지 10만장·영상 5,000건)를 도메인이 직접 인용해 집계 기준을 세웠는데,
  그 목표를 하향받은 `REQ-025`·`REQ-026` 에는 **달성 현황 집계·표시 요구가 0건**이라 "규모 요구 → 달성 측정" 체인이 REQ 계층에서 끊겼다.
  덧붙여 두 REQ 는 `belongs_to_domain` 미지정이라 어느 도메인과도 그래프 연결이 없다.

**★ "REQ 귀속 0" 자체는 갭으로 올리지 않았다** (auditor 판정, 근거 3):
① 이 도메인은 1차 보존 도메인(`brownfield.status=preserved`, DFEAT 3건 전부 1차 화면 `SKKLID-UI-*` 계승)이라 2차 RFP 파생 산출물이 아니다.
② RFP 7건 어디에도 통계·대시보드 요구 항목이 없다 — 잔존 책임이 흡수된 RFP-003(SFR-08)의 3개 heading 은 라벨링 정확도·버전 관리·데이터마트 연계뿐.
③ `context_kind=supporting` 으로 core 책임 도메인이 아니다.

## test_scenario (3건 — P1 1 / P2 2)

- **D006-TST-001 (P1)** 도메인 핵심 흐름(대시보드 요약·전체 통계·기간/이벤트유형 집계·작업자별 현황) 통합시험 0건.
  TEST 5건 전수 링크 실측 결과 `exercises_screens` 에 `SCREEN-011`·`020`·`021` 이 없고, 세 화면 backward 에도 test_scenario 0건.
  UC 축으로도 귀속 불가(통계 UC 자체가 없음) → **UC 신설이 TEST 신설의 전제**.
- **D006-TST-002 (P2)** 도메인에 `applies_to` 로 붙은 `NFR-011`(화면 응답시간)·`NFR-018`(지연 사전 안내)·`NFR-019`(오류 응답 속도)를 검증하는 **system 시험 0건**
  (프로젝트 전역 `kind=system` 자체가 0건). 세 NFR 이 D005·D006·D010·D011 **4도메인 공유**라 P2 로 낮췄고, 횡단 1건 설계를 권고.
- **D006-TST-006 (P2)** TEST 5건 전부 `related_domains`·`verifies_requirements`·`verifies_nfrs`·`related_apis` 공란 — **프로젝트 레벨 dedupe 대상**.

### TEST 5건 귀속 판정 (재조사 불필요 — 다음 도메인에서 재사용)
| ID | 제목 | covers_use_cases | exercises_screens | 귀속 |
|---|---|---|---|---|
| TEST-001 | 관제 학습용 영상 적재 후 선두 비식별 | UC-018, UC-011 | SCREEN-007 | 영상수집·비식별 |
| TEST-002 | 외부 VLM 시계열 메타 위탁·콜백 | UC-022 | SCREEN-005 | 라벨링·VLM |
| TEST-003 | 외부 생성형 AI 증강 위탁·콜백·검수 | UC-001, UC-002, UC-010 | SCREEN-022, SCREEN-023 | 증강 |
| TEST-004 | 검수 완료·수정 관제 단방향 통지 | UC-009, UC-007 | SCREEN-019 | 검수·통지 |
| TEST-005 | 포털 채널 데이터 읽기 Load | UC-024 | SCREEN-005 | 포털 |

## 미확인 층 (잔여 3차원 공통)

1차 소스 grep 미수행(`legacy_grep_enabled=false`) — `LEGACY-061`·`098`·`114`(1차 대시보드/통계 화면)의 실제 집계 규칙 대조 불가.
TEST 5건의 `steps[]` 본문은 조회하지 않았다(링크 3축 + 화면 역참조로 귀속 없음 판정) — 통계 흐름이 부수적으로 포함됐을 가능성은 배제하지 못한다.
정적 렌더 미러·디자인 HTML·로컬 키트·위키·테스트케이스 층은 전 차원 미확인.
