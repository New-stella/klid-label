# DOMAIN-015 작업 배정 — 원본 감사 결과

> project_id `4ece2c3f-8e99-46f5-9580-71108a76e578` · 스킬 `mc-logi-domain-review` v1.5.0 · read-only · legacy grep OFF

## 인벤토리 (coverage 실측 — **120 ITEM 조회**)

| 타입 | 건수 |
|---|---|
| domain_feature | **1** (DFEAT-006) |
| api_endpoint | **8** (API-070·071·072·073·116·136·137·187) |
| screen_spec | 활성 1(SCREEN-012 `/task`) / **폐기 1**(SCREEN-013) |
| erd | ERD-014 — ★**`LS_TASK_ASSIGNMENT`(7컬럼·3인덱스)·`LS_TASK_EVENT_LOG`(8컬럼·2인덱스) 전부 커버, 갭 없음** |
| class_diagram | CDIAG-007 · code_module MOD-014(`stale=true`) |
| use_case | 활성 1(**UC-029**) / **폐기 1**(UC-020) |
| **acceptance / requirement / test_scenario / nfr / domain_event / diagram_sequence** | ★**전부 0건** |
| SVC/IAPI/LIB | **프로젝트 전량 0** |

---

## coverage (6건 — P0 2 / P1 2 / P2 2)

### ★★★ orphan — **두 기준 모두 8/8** (지금까지 최악)
**기준 A**(DFEAT 매핑) → **합집합이 `{}`** — `DFEAT-006` 의 `implemented_by_endpoints`·`invokes_apis`·`consumes` **전부 공란** ⇒ **8/8**
**기준 B**(API `implements_features`) → **8건 전부 `[]`** ⇒ **8/8**
★**cross-domain 가능성도 배제** — `get_neighbors(API-070)`·`(API-073)` backward 에 **`domain_feature` 0건**

### P0 2건
`COV-005` 위 orphan ·
`COV-004` ★**`UC-029` 실현 SEQ 0건** — **14건 전량 제목 열거**로 확인. ⇒ *"main_flow **7 step** + alternate_flows **3종**을 정의하는데 **happy·error 양쪽 모두 없다**"*

### P1 2건
`COV-002` ★★**유일 backing UC 가 deprecated** — `DFEAT-006` 을 realize 하는 건 **`UC-020`(폐기)** 하나뿐이고 **활성 `UC-029` 는 `realizes_dfeats`·`realizes_features` 둘 다 `[]`** ⇒ **활성 backing 0** ·
`COV-007` ★**책임 7개 중 3개가 활성 DFEAT 0건** — **R5 상태 소유권 · R6 정렬·필터 정책 · R7 등재 게이트**. ⚠★**R6 에는 전용 API 4건이 실재하는데 DFEAT 인벤토리엔 흔적이 없다**

### P2 2건
`COV-009` ★**`DFEAT-006` 이 자기모순** — description·`user_story.as` 가 **1차 actor "관리자"** 를 유지하는데 **같은 ITEM 의 `brownfield.diff_summary` 는 이미 *"REVIEWER가 WORKER에게 배정(역할 단일화)"* 로 정정**돼 있다. ★**`last_updated` 2026-06-01 로 도메인(08-13)보다 2.5개월 낡음** ·
`COV-011` (advisory) **NFR 귀속 0/14**. ★**대표 도메인 관례와 명시적으로 구분** — *"`NFR-011`·`018`·`019` 는 동일하게 4개만 열거하는 관례이므로 **이 관례를 근거로는 gap 을 만들지 않았다**"*. **`NFR-020`(역할별 접근제어)만 후보로** 남김

### ★★ 주입 정책 **10/10 정합** — 이번엔 어긋남 0
★*"특히 ②(`LS_TASK_ASSIGN_HISTORY` 제거)는 `DOMAIN-015` v4·`ERD-014` v8·`CDIAG-007` v3·`API-071` v4·`CMP-009` v5(**전부 2026-08-13**)에서 모두 **작업 이벤트 로그로 일원화 완료 확인**"*
⚠**단 `API-187`(그룹 매칭 EP)은 `implementation.status='planned'`** — 계약만 있고 구현 미착수

### ★ 책임 인벤토리 7개 추출 (COV-1 게이트 통과)
R1 배정·재배정 / R2 적재 / R3 이력 조회 / R4 TaskBoard / **R5 상태 소유권** / **R6 정렬·필터 정책** / **R7 등재 게이트**
★**책임 제외 섹션 부재** ⇒ *"COV-8 negative 기준 부재로 **명시적 위반(P0) 판정 불가**"*

### ★ coverage 가 넘긴 단서
- ★**`STATE-001` 이 이 도메인 R5 와 직결**인데 귀속 선언 없음. **06-02 로 도메인 확정(08-13)보다 낡음**
- ★**`SCREEN-013`(폐기)이 `API-070`·`071`·`073` 을 여전히 consumes** — *"폐기→활성 방향이라 POL 위반은 아니나"* link 확인 요망
- **REQ 0건은 결함이 아닐 가능성** — ★*"`UC-025` 폐기 사유가 'R1 전용 요구사항 없는 **운영 워크플로우 UC** 분리'였고 작업 배정도 같은 성격"*
- `DFEAT-006` 이 `implemented/100` 인데 **`modules=[]`·`records=[]`**

---

## links (10건 — P0 1 / P1 7 / P2 2)

### ★★ orphan 두 기준이 **극단으로 갈렸다** — coverage 와 다른 결과
| 기준 | 결과 |
|---|---|
| **A** DFEAT 매핑 | **8/8** (coverage 와 동일) |
| **B** ★**그래프 backward 소비자** | ★**0/8** — *"8건 모두 `SCREEN-012.consumes_apis` 에 등재"* |
| **B'** ★**섹션 수준으로 좁히면** | **2건**(`API-116`·`API-187` 이 `sections[].references_apis` 합집합에 없음) |

⇒ ★**세 층위가 다 다르다.** coverage 는 A 만 봤다.

### P0 1건
**`LNK-006`** ★**활성 `MOD-014` 가 폐기 `SCREEN-013` 을 `realizes_screens` 로 물고 있다** — ★★**그 cascade 가 실제로 돌고 있다는 증거**: `MOD-014.stale_reason` = *"**SCREEN-013의 7개 필드 변경** — realizes"*. `SCREEN-013` backward 의 **유일한 잔존 참조원**.
★**정밀 판정**: *"'[폐기]' 라벨을 단 **이력 서술이 아니라 살아 있는 link 배열 원소**이므로 잔재로 판정"*

### P1 7건
`LNK-001` `DFEAT-006` **6개 링크 필드 전부 공란** ·
`LNK-002` `persists_in_tables=[]` — **ERD 두 테이블 책임 주체 0** ·
`LNK-003` `SCREEN-012.realizes_use_cases=[]` ·
`LNK-004` `UC-029` **`realizes_dfeats`·`realizes_features` 둘 다 `[]`** ⇒ DFEAT 의 유일 역참조가 **폐기 `UC-020`** ·
`LNK-005` ★**`UC-029` `belongs_to_domain` 미설정 — D012·D013·D014 에 이어 4연속** ·
`LNK-007` ★**`MOD-014` 가 화면만 realizes** — DFEAT·API 8건 전부 미연결. ★**`brownfield.diff_summary` 는 `DFEAT-006` 을 인용하는데 링크 배열은 공란** ·
**`LNK-008`** ★★**같은 오인용이 두 곳에 복제** — 배정 이력 API 는 `API-116` 인데 `SCREEN-012` 섹션이 `API-072`(목록 조회)를 `references_apis`·`triggers_api` 로 지정. **`UC-029.alternate_flows[2]` 에도 복제**: *"`GET /v1/assignments/{id}/history` **= API-072**"* ⇒ ★**경로와 ID 가 자기모순** ⇒ **`API-116` 은 어느 섹션에서도 참조되지 않는다**

### P2 2건
`LNK-009` ★**`API-187` 이 폐기 화면 목적으로 신설돼 실 소비처가 없다** — `summary` 가 *"**배정 화면** 이벤트유형 필터 옵션 조회"* 인데 **그 화면(`SCREEN-013`)은 폐기**됐고 `SCREEN-012` 는 **동일 목적 `API-137` 을 이미 사용** ⇒ *"엔드포인트 **존치 여부 자체가 검토 대상**"* ·
`LNK-010` `specializes_feature` 미지정 — ★**활성 FEAT 9건 전량 열거로 "배정 축 FEAT 후보 0건" 확인** ⇒ **기존 ID 로는 연결 불가**

### ★★ `get_item_schema` **3회 호출로 오보고 3건 방지**
★*"`code_module` 정식 필드가 `realizes_dfeats` 가 아니라 **`realizes_domain_features`** 임을 확인하고 재조회했다(**첫 부분 read 의 빈 결과는 필드명 오류였음**)"* ·
★*"`api_endpoint` 의 `required_roles`·`emits_events` 는 **스키마에 없는 필드**(실제는 `security`·`triggers`)임을 확인해 **'필드 누락 gap' 으로 오보고하지 않았다**"*

### ★ deprecated 판정 — **잔재 1건 / 아님 3건** (방향까지 구분)
**아님**: ①`UC-020`→`DFEAT-006` 은 **deprecated→활성 방향**이라 활성 ITEM 의 잔재 아님 ②`change_summary` 의 *"재배정 이력 테이블을 두지 않는다"* 는 **설계 결정 이력 서술** ③`SCREEN-013` **자신의** consumes 는 **deprecated 내부 링크**

### ★ `unresolved` 대리 측정 — **후보 0건**
`SCREEN-012` 10+2 · `MOD-014` 2+1 · `UC-029` 1 · `CDIAG-007` 1 · `LEGACY-112` **전부 materialize**.
★**`ADR-038` 실재·`approved` 확인** — *"폐기 정책 ADR 5건 인용은 이 도메인에서 **발견되지 않음**"*

### ★ links 가 넘긴 단서
★**등재 게이트가 읽는 `LS_DATA_AUG_RVW` 는 이 도메인 ERD 에 없다** ⇒ *"**게이트 판정축의 데이터 근거가 도메인 밖에 있다**"*

## stale (12건 — P0 3 / P1 7 / P2 2)

- **P0 D015-STL-001** `DFEAT-006` — stale=false·75일 무갱신인데 `user_story.as="관리자"` + description "관리자가 데이터를 작업자에게 배정" = 폐기된 ADMIN 주체. **같은 ITEM 의 `brownfield.diff_summary` 는 반대로 "2차 REVIEWER가 WORKER에게 배정(역할 단일화)"** 이라 자기모순. `ADR-003`(ADMIN 폐기) 기록 **1시간 뒤** 편집인데도 ADMIN 잔존.
- **P0 D015-STL-002** `API-137`·`API-073` — 이벤트유형 필터를 폐기된 단일 코드 eq 로 서술. `API-137` 은 **"이벤트 마스터 테이블이 없어 코드값이 곧 표시명이다"** 로 정책과 정반대 사실을 계약에 못박음. ★그 리비전(08-07, 확정 **이후**)의 change_summary 가 *"잘린 것을 원문 전체로 복원한다"* — **확정 이후에 확정 이전 본문을 복원**했다(덮어확정).
- **P0 D015-STL-003** `MOD-014`→`SCREEN-013`(deprecated) `realizes_screens` 활성 참조. `get_neighbors` backward 로 링크 실체 확인.
- P1 D015-STL-004 `API-187` — 08-07 **신설**인데 이틀 앞선 08-05 확정 미반영("중복 없이" = 코드축 dedupe).
- P1 D015-STL-005 `CDIAG-007` — 08-13 이력 단일화 라운드가 **메서드 반환형만 고치고** `TaskAssignmentService.description` 의 "이력 기록 + 이벤트 로그 적재를 조합"(이중 기록)을 놓침. 같은 라운드에서 `ERD-014`·`API-071` 은 정정됨.
- P1 D015-STL-006 `MOD-014` stale=true 8일 — 일괄 스탬프가 "바꿀 내용이 없어"로 확정했으나 **stale_reason 이 지목한 참조를 그대로 둠**(쓰기가 no-op).
- P1 D015-STL-007 구속 정책 3건의 근거 ADR(`ADR-038`·`ADR-045`·`ADR-003`)이 **전부 완전 고립(forward 0 / backward 0)**, 2건은 ADR 자체가 없음.
- P1 D015-STL-008 `SCREEN-012` 미러 stale — ITEM 08-14 v33 vs 렌더 08-13. 게시본이 폐기 경로 `/tasks` 를 계속 노출(v33 이 고친 바로 그 값). **`source_hash` 는 `sections` 만 덮어 이 축을 구조적으로 못 잡는다.**
- P1 D015-STL-009 brownfield status=modified + legacy_source 전무 **7건**.
- P1 D015-STL-010 `SCREEN-012`(WORKER 클라이언트 필터 유지) ↔ `API-187`(필터 3종 전제) **동시에 참일 수 없음**.
- P2 D015-STL-011 implementation 불일치 — `ERD-014`/`CDIAG-007` planned 0% 인데 본문은 산출물 실재 단언, 폐기 화면 `SCREEN-013` 이 implemented 100%.
- P2 D015-STL-012 `SD-003` data 가 `{status:"draft"}` 뿐(purpose·screen_id 전무), `API-116` brownfield 키 부재, `API-072` description 21자.

## schema (14건 — P0 4 / P1 10)

- **P0 D015-SCH-001** `ERD-014.LS_TASK_EVENT_LOG.EVNT_TYPE_CD` code_values **5종 ↔ 실물 11종** — 누락 6종(`CANCEL_SUBMIT`·`PRIVACY_META_UPDATE`·`PRIVACY_META_RESET`·`FRAME_DISCARD`·`FRAME_RESTORE`·`START_VERSION_APPLY`)이 검증축 밖. 5종 열거 서술이 **3곳**(컬럼·테이블·ERD description)에 복제.
- **P0 D015-SCH-002** `ERD-014.relationships: []` 인데 실물은 두 테이블 모두 `ls_data_raw` 로 **ON DELETE CASCADE** — 영상 삭제 시 **감사 로그(OWASP A09)까지 소멸**하는 파괴적 시맨틱이 설계서에 0.
- **P0 D015-SCH-003** `API-070`(POST) `request_body` **필드 자체 부재** — 실물 필수 3필드 미공표. ★응답은 `items` 배열(다건)을 공표하는데 **그 다건성의 원천 `rawDataIds` 가 계약에 없다**.
- **P0 D015-SCH-004** `API-071`(PATCH) `request_body` 부재 — ★400 응답이 *"현재 배정된 작업자와 동일합니다"* 로 **body 값 기반 검증을 이미 서술**하는데 그 body 계약이 없다(계약 내부 자기모순).
- P1 D015-SCH-005 `API-072.parameters: []` ↔ 실물 7종 + `SortAllowlist.ASSIGNMENT` 6키·strict 400·개수 상한. **그 공백이 `SCREEN-012` 에 "서버 필터 미지원"이라는 반대 사실로 복제**됨.
- P1 D015-SCH-006 `API-072` 400 미명세(실물 발생 경로 5종). 같은 도메인 `API-073`·`API-136`·`API-137` 은 전부 명세 — **API-072 만 빠짐**.
- P1 D015-SCH-007 `API-116` eventTypeCd 5종 열거인데 구현은 **타입 필터 없이 전 행 반환**, `subjectUserNo`·`subjectUserName` nullable 누락.
- P1 D015-SCH-008 `API-116` 400·404 미명세 — 같은 리소스 `API-071` 은 404 를 이미 명세(계약 갈림).
- P1 D015-SCH-009 `API-137` "마스터 테이블 없음" ↔ 실물 3층 존재(`LsEvntType`·`EventTypeDisplayNamePolicy`·`EventTypeGroupIndex`).
- P1 D015-SCH-010 `API-073`·`API-136` "양쪽 trim 비교" = 폐기된 eq. 실물은 `EventTypeFilterSupport.matchCodesFor` 그룹 IN.
- P1 D015-SCH-011 `DFEAT-006.persists_in_tables: []` — **SCH-1 검사가 영구 통과**(대조할 값 부재). `invokes_apis`·`implemented_by_endpoints`·`consumes` 도 공란.
- P1 D015-SCH-012 표준용어 미등록 약어 5종 — `ASSIGNMENT`(→ 행안부 배정 `ALTMNT`)·`ACTOR`(→`ACTR`)·`SUBJECT`(→`TRGT`)·`PREV`(→`BFR`)·`EVENT`(→`EVNT`). ★**같은 ERD 안에서 테이블명 `EVENT` vs 컬럼 `EVNT_ID` 표기 갈림**. 자기등록 6건은 출처 확인해 근거에서 배제.
- P1 D015-SCH-013 표준도메인 타입 불일치 — `USER_NO` 번호V10 ↔ bigint, `EVNT_ID` 식별자V50 ↔ bigint, `VER` 명V20 ↔ bigint. **사업표준도메인에 일련번호B20(BIGINT) 이 실재**하므로 "담을 도메인이 없어서"가 아니라 연결 오류.
- P1 D015-SCH-014 `ERD-014` 진실원이 스쿼시로 아카이브 이동된 `V36` — 라이브 경로엔 V1~V6 6개뿐. **D014 `ERD-016` 과 동일 유형 2연속**.

## policy (10건 — P0 4 / P1 5 / P2 1)

- **P0 D015-POL-001** 08-05 그룹 축이 옵션 조회 2건(`API-137`·`API-187`)에 0 — `categoryKey`·`memberCodes` 계약 부재, 절단도 **접기 전 판정**으로 읽힘.
- **P0 D015-POL-002** 조회 축(`API-073`·`API-136`) 단일코드 eq 잔존 — 대표코드 선택 시 그룹 나머지가 사라짐. KPI 집계도 "목록과 동일 적용"으로 같은 오답 위임.
- **P0 D015-POL-003** `SCREEN-012` 가 확정 정책 "FE 클라이언트 필터 금지"를 정면 위반 선언, 근거로 든 `API-072` 는 parameters 0개.
- **P0 D015-POL-004** `API-116` 이 `ADR-042`(approved, 08-04)가 **전면 삭제 결정한 `MNG_ACCT_USER`** 를 조달처로 인용(인용 시점 08-07 = 승인 **뒤**).
- P1 D015-POL-005 `ADR-038` 인용 0건 — `list_items(brownfield_decided_by=ADR-038)` → total 0. 정책 4·5·6·7 이 본문에만 흩어짐.
- P1 D015-POL-006 `ADR-045` 인용 0건 — 등재 게이트 3요소(리뷰 축·RESL_* 예외·그랜드퍼더링)가 ADR 에 실재하는데 링크만 끊김.
- P1 D015-POL-007 `DFEAT-006` decided_by 공란(status=modified·actor-change) — 대응 `ADR-003` 실재.
- P1 D015-POL-008 `API-116` brownfield 블록 전무(도메인 api 8건 중 유일).
- P1 D015-POL-009 재배정 이력 단일화(08-13) **대응 ADR 0건** — 최신 ADR 신설이 그보다 앞섬.
- P2 D015-POL-010 `API-187` change_kind·decided_by 부재.

### ★ policy 가 밝힌 것 — D014 와 정반대 형태

D014 는 **ADR 자산 자체가 없었고**, D015 는 **ADR 자산은 있는데 연결이 전부 끊겼다**. 10개 정책 중 대응 ADR 보유 **8**, 그중 실제 인용은 **1건(정책 3 = `ADR-001`)뿐**. 재도입 위험은 ADR 이 아예 없는 2건(재배정 이력 단일화 · **이벤트유형 그룹 축**)에 집중되며, 특히 그룹 축은 **ADR 부재 + 본문 위반 4건이 겹친 유일한 축**이다.

### ★ 룰 SKIP 6건 — 스킬 룰의 ADR 번호가 이 프로젝트와 불일치

policy 감사자가 룰 POL-2~POL-7 을 **전부 SKIP** 했다. 이유가 "해당 없음"이 아니라 **룰이 지목한 ADR 번호가 다른 프로젝트 것**이다 — 룰의 `ADR-051` 은 이 프로젝트에 **존재하지 않고**(최대 ADR-045), `ADR-045`·`ADR-028`·`ADR-027`·`ADR-036` 은 전부 이 프로젝트에서 **다른 내용**이다. 감사자가 번호를 그대로 믿었다면 없는 정책 위반을 6건 만들어 냈을 것이다. **스킬 룰 파일이 다른 프로젝트에서 왔다는 신호**이므로 최종 리포트의 「검사 사각」에 올린다.

### ★ 주입 전제 오류 — 8건째

`API-187.implementation.status='planned'` 를 근거로 미구현으로 다뤘으나, schema 감사자가 **실물 구현을 찾았다**(`AssignmentController.eventTypes` + `AssignmentService.listEventTypeOptions`, 계약도 `EventTypeOptionsResponse` 와 정확히 일치). ITEM 의 implementation 메타가 낡은 것이며, **"planned = 미구현"으로 읽으면 안 된다**.

### ★ 한글 손상 — `ADR-038` 본문 2건 (policy 가 발견)

`어려워다`(→어려웠다) · **`주은다`(→죽는다)**. 후자는 **읽히는 다른 한글**이라 통독으로 안 잡힌다. ADR 은 도메인 축이 없어 도메인 스코프 감사에서 구조적으로 빠지는데, 이번엔 정책 근거로 본문을 통째 열어 본 덕에 잡혔다.

## acceptance (3건 — P1 2 / P2 1) · 68 ITEM 조회

- P1 D015-ACC-001 `UC-029`(도메인 **유일 활성 UC**) `covered_by_acceptances: []` + backward 0건 → **이 도메인 인수기준 0건**. `SCREEN-012` 도 `covered_by_acceptances: []`·`realizes_use_cases: []`.
- P1 D015-ACC-002 `DFEAT-006`(priority=must, implemented 100%) — `acceptance_rules`·`related_acceptances` 공란 + **UC 경유 경로도 차단**. 유일한 realize UC 인 `UC-020` 이 **deprecated** 이고 그마저 AC 0건, 활성 `UC-029` 는 `realizes_dfeats: []` 라 **활성 realize UC 자체가 0건**.
- P2 D015-ACC-003 negative AC 0건 — `UC-029.alternate_flows` 가 검증돼야 할 분기를 **스스로 2건 선언**(정렬 키 strict 400 · 이벤트유형 옵션 절단)하고 goal 에 인가 축을 명시하는데 검증 AC 가 없다. ★특히 strict/lenient 비대칭은 *"일관성을 이유로 통일하지 말 것"* 으로 못박힌 항목이라 **회귀 방지 AC 부재 = 무단 통일 위험**.

### ★★ 수정 순서 의존 — AC 를 먼저 만들면 헛수고가 된다

AC 의 도메인 귀속은 **서버가 `derived_from_use_cases → UC → DFEAT → domain` 으로 자동 계산**한다(`AC-022.derived_domain_ids=["DOMAIN-005"]` 가 실증). 그런데 `UC-029.realizes_dfeats: []` 라, **지금 상태에서 AC 를 `UC-029` 에 붙여도 `derived_domain_ids` 가 빈 채로 남아 DOMAIN-015 에 귀속되지 않는다.**

→ **AC 신설보다 `UC-029`→`DFEAT-006` realize 연결이 선행**돼야 한다. 최종 리포트의 「수정 순서 의존 클러스터」에 올린다.

### ★★★ acceptance 감사자의 "전제 오류 9건째" 주장은 **기각한다** — 층을 잘못 봤다

acceptance 감사자는 `get_item_schema(use_case).allowed_fields` 25개에 `domain_id`·`belongs_to_domain` 이 없고 `hints.domain_required=false`, `level='project'` 임을 근거로 **"필터 0건은 누락이 아니라 스키마 구조상 필연"** 이라고 보고하며, D012·D013·D014 의 같은 판정을 오탐으로 재검토하라고 요구했다. **그 둘 다 성립하지 않는다.**

**반증 2건 — 이미 이 감사 안에 있다:**

1. **D014 감사자가 대조군을 실측했다** — *"★전역 관례 아님 대조 확인(`DOMAIN-010` 3건·`DOMAIN-005` 1건 보유)"*. UC 가 이 링크를 **실제로 가질 수 있다** — 구조상 불가능했다면 보유 건이 0 이어야 한다.
2. **D013 감사자가 이미 정확히 짚어냈다** — *"`get_item_schema` 로 확인: **`data` 패치가 아니라 `domain_id` 설정**이어야 한다"*.

⇒ `allowed_fields` 는 **`data` 페이로드의 허용 키** 목록이고, `domain_id` 는 **`data` 밖 ITEM 레벨 필드**다. 감사자는 **다른 층을 보고 부재를 결론**했다 — 이 감사가 내내 경고해 온 **"부분 read 의 빈 결과 ≠ 필드 부재"** 와 같은 함정이다.

**✅ 따라서 D012·D013·D014·D015 의 `belongs_to_domain` 미설정 보고는 유효하며 소급 재검토 대상이 아니다.** 단 수정 방법은 D013 이 확인한 대로 **ITEM 레벨 `domain_id` 설정**이지 `data` 패치가 아니다 — 최종 리포트의 처방에 이 구분을 명시한다.

⚠ 다만 감사자가 짚은 **스키마 자기모순 자체는 사실**이다 — `allowed_fields` 에는 없는데 `link_types_from` 에는 `belongs_to_domain(to_type=domain)` 이 선언돼 있어 **스키마만 읽은 사람이 정확히 이 오판을 하게 된다.** 플랫폼 축 사안이라 gap 으로 계상하지 않고 「검사 사각」에 올린다.

### (통과된 전제 오류 집계 — 여전히 9건이 아니라 8건)

## requirement (5건 — P1 2 / P2 3) · 47 ITEM 조회

- P1 D015-RQ-001 **도메인 귀속 REQ 0건** — `list_items(type=requirement, include_retired=true)` 26건(활성 22) 전수 스캔에서 `DOMAIN-015` 귀속 0. FEAT 9건에도 배정 축 없음. `DFEAT-006` 의 상위 추적은 **1차 화면 `LEGACY-112` 하나뿐**.
  ★내용이 일치하는 REQ 는 **있다** — `REQ-023` 이 *"활용 여부가 채택된 파생영상만 **작업목록·배정** 대상으로 등재한다"* 로 도메인 등재 게이트 책임을 그대로 인용하는데, 링크는 `derived_from_rfp:["RFP-005"]` 뿐이고 도메인·DFEAT 어느 쪽도 가리키지 않는다.
- **P1 D015-RQ-003** `NFR-011` — description 이 대상에 *"라벨링·검수·마킹·**목록** 등"* 을 명시하는데 `applies_to_domains` 는 `[010, 005, 011, 006]` 로 **"목록"에 대응하는 도메인만 빠졌다.** 그 목록 화면(`SCREEN-012`) 소유가 DOMAIN-015 다. → `auto_fixable`
- P2 D015-RQ-002 RFP 근거 부재 advisory — 이 도메인은 RFP 파생이 아니라 **1차 계승 지원 도메인**으로 보인다(`brownfield.diff_summary` = *"1차 프로젝트 단위 배정 → 2차 영상 단위 배정"*). ⚠ **"우리 영역 아님"이 아니라 그 반대** — 우리가 만드는데 발주처 요구 계층에 대응 항목이 안 보인다는 뜻. **사용자 판정 요청 대상**.
- P2 D015-RQ-004 활성 NFR 14건 전수 `applies_to_domains` 실측 — **DOMAIN-015 귀속 0건**. 후보 3건(`NFR-018` 장시간 작업 사전안내 ↔ UC-029 일괄 배정 / `NFR-019` 입력오류 3초 ↔ 정렬키 strict 400 / `NFR-020` 역할 접근제어 ↔ WORKER·REVIEWER 인가 축). **본문이 이 도메인을 명시 지목하진 않아** 감사자가 단정하지 않음 → 판정 요청.
- P2 D015-RQ-005 `REQ-023.acceptance_criteria: []` — ★**과대 계상 방지**: 본문을 실제로 연 REQ 12건 **전부** 공란이었다. **도메인별로 곱해 세지 말 것**(전역 패턴).

### ★ requirement 가 독립 확인한 것 — 내 정정이 맞았다

`UC-020`(deprecated, `realizes_dfeats=[DFEAT-006]`)과 `UC-029`(active, `related_screens=[SCREEN-012]`)가 **실재하며, 둘 다 `belongs_to_domain` 링크가 없어 필터에 안 잡힌다**는 것을 requirement 감사자가 **독립적으로** 확인했다. 즉 위 acceptance 감사자의 "스키마 구조상 필연" 주장은 기각이 맞고, **`belongs_to_domain` 미설정은 실재하는 결함**이다.

### ★ 추적표는 존재하지 않는다 (실측 확정)

별도 ITEM·note 어디에도 없다. `list_notes` 5건은 전부 플랫폼 system-feedback. 매트릭스에 해당하는 유일한 필드는 **`RFP-003.related_requirements`(6건)** 하나뿐이고 나머지 RFP 6건엔 그 필드 자체가 없다. → D011·D014 에서 내가 "추적표"를 근거로 인용했던 것이 무엇이었는지 **최종 리포트 작성 전 재확인 대상**.

### ★★ 도메인 밖 발견 — D012 소급 항목

`REQ-014.constraints` 에 *"검수 완료(승인) 영상을 신고하면 상위 시스템에 수정 통지를 발행하고, 해소 시 산출물을 재산출해 재통지한다"* 가 남아 있다. 확정 정책은 **2026-08-10 이후 "승인 이력이 있는 영상은 신고 접수 자체를 412 로 거부"** 라 이 서술은 **도달 불가 경로**다. → DOMAIN-012 소급 항목으로 등재.

## content (12건 — P1 6 / P2 6) · 검사 기반 32,671자

### 한글 손상 5건 / 4 ITEM (사전 통지 2 + **신규 3**)

- P1 D015-CNT-001 `ADR-038` **2건** — `어려워다`(→어려웠다) · **`주은다`(→죽는다)**
- P1 D015-CNT-002 `API-073` — `영상 캐프처 일시`(→캡처). **종성 ㅂ 이 별도 음절 `프` 로 분해**. 같은 ITEM 다른 곳은 `캡` 을 정상 사용
- P1 D015-CNT-003 `API-136` — `0 으로 고정도던 결함`(→고정되던)
- P2 D015-CNT-004 `SCREEN-012` — `검수자에는`(→검수자에게는). **같은 ITEM 형제 섹션은 `검수자에게는` 로 정상**(내부 불일치). ★**정적 렌더 미러에 2회 복제** — ITEM 만 고치면 미러가 즉시 stale

### ★★★★ 7축이 전부 놓친 것을 감사자가 만든 ⑧축이 잡았다 — 상설 편입 권고

축별 실제 검출: **①0 ②1 ③0 ④2 ④b0 ④d1 ⑦0 ⑧2**

| 손상 | 잡은 축 | 다른 축이 놓친 이유 |
|---|---|---|
| `캐프처` | **②단독** | 3음절이라 ④ 미달 · 정상형 `캡처` 와 길이 달라 ⑦ 미달 |
| `고정도던` | **④단독** | 고·정·도·던 전부 상용이라 ② 미달 |
| `검수자에는` | **④d단독** | `검수자에게는`·`검수자에` 와 접두/접미가 겹쳐 ④ 본검사에서 탈락 |
| **`주은다`** | **⑧단독** | 주(5)·은(97)·다(242) 전부 상용 → ② 미달 / 3음절 → ④ 미달 / `죽는다` 와 **거리 2** → ⑦ 미달 |

**⑧ = 다-종결 어절 전수 census(95종) + 빈도.** `주은다` 는 **규정된 7축 중 어느 것도 잡지 못했고**, 사전 통지가 없었다면 놓쳤을 건이다. → **7축을 8축으로 확장**한다.

★ 부수 확정 — **⑦Hamming 은 이 규모(32,671자)에서 사실상 무력**하다. 57쌍 전량 오탐이었고, 손상 5건 중 0건. 정상형 출현이 0~2회라 치환쌍이 성립하지 않기 때문. ①도 신규 0건(유일 매치 `스템` 은 `시스템` 부분일치 오탐).

### 본문 오염 — 사전 통지 4 + **신규 4클러스터**

- P1 D015-CNT-005 `ADR-038.references` 4건 전부 `https://example.invalid/commit/<hash>` + title 에 **PR#57 + 커밋 해시**
- P1 D015-CNT-006 `ListApiBackwardCompatibilityIT` **3곳** — ADR-038 2곳(통지분) + ★**`API-073.description` 1곳 신규**
- P1 D015-CNT-007 `ERD-014` 코드 경로 3곳(`.sql`×1 `.java`×2) + ★**마이그레이션 버전 인용 신규**(`V36`×2 · `V134`×1)
- P2 D015-CNT-008 ★신규 `ERD-014.VER` 컬럼에 **finding 코드 `(D-ISSUE-02)`** — 나머지 서술(2노드 Active-Active 라 JVM 락으로 방어 안 됨)은 **사양이라 보존**
- P2 D015-CNT-009 ★신규 `API-136`·`API-137` `diff_summary` 접두 **`PR #57 신설`** — 같은 도메인 `API-187` 은 `2차 신설` 로 저장소 비의존 표기(정본 대조군 실재)
- P2 D015-CNT-010 ★신규 내부 위키 화면 코드 **`SCR-TASK-003`** 3곳(`API-116`·`CDIAG-007`·`ERD-014`). 정본 ID `SCREEN-012` 가 실재. ⚠ 동일 화면인지 **확정 못 해 P2** (추정 금지)

### 서술 품질

- P2 D015-CNT-011 `DFEAT-006` description 95자 중 실질 48자가 **title·user_story 동어반복**. 대상을 "데이터"로만 지칭해 **영상 1건 단위를 서술하지 못한다**(도메인·ERD·CDIAG 는 전부 명시). ★도메인 description 1,013자가 담은 **확정 정책 3종 중 어느 것도 DFEAT-006 에 없다**
- P2 D015-CNT-012 `ADR-038.justification` 494자·줄바꿈 0·최장 문장 149자 이중괄호 중첩 — **`주은다` 가 통독으로 안 잡힌 바로 그 자리**. 형제 ITEM 은 전부 블록 구조(`API-136` 빈줄 6 / `API-073` 4 / `DOMAIN-015` 3)라 **이 ADR 만 관례 이탈**

### ★★★ 주입 전제 오류 — 이번엔 진짜 9건째 (내가 stale 보고를 검증 없이 넘겼다)

내가 *"`SD-003` 의 `data` 가 `{status:"draft"}` 뿐(purpose·screen_id 전무)"* 이라고 주입했으나 **사실이 아니다.**

실측 `SD-003.data` 는 **키 8개**(title·device·status·renders·designer·description·design_source·designs_screen) / JSON 679자이고, description 97자는 실질 내용을 담는다:
> `KRDS Public(DS-001) 토큰 기반 고충실 디자인. 오류배너+필터+KPI 5카드+일괄배정 액션바+작업 목록 테이블(6종 상태 배지)+작업배정 모달+배정이력 드로어.`

`renders` 도 main.html/main.css 1건이 2026-08-13 업로드로 **실재**한다. **`data` 최상위에 `status:"draft"` 키가 있는 것을 "data 가 그것뿐"으로 오독**한 것으로 보인다.

⚠ **이건 내가 stale 감사자의 보고를 검증 없이 다음 차원 프롬프트에 전제로 넘긴 결과다** — D011 에서 똑같은 실수를 하고 규칙까지 세웠는데 재발했다. → **`D015-STL-012` 의 SD-003 부분은 철회**하고, `API-072` description 도 21자가 아니라 **24자**로 정정한다.

## test_scenario (3건 — P1 2 / P2 1)

- P1 D015-TST-001 **4개 귀속축 전부 0건** — `covers_use_cases` 합집합에 `UC-029` 없음 / `exercises_screens` 에 `SCREEN-012`·`SCREEN-013` 없음 / `related_apis` 5건 **전부 `[]`** / `verifies_requirements`·`verifies_nfrs` 5건 **전부 `[]`**. `UC-029` 가 스스로 선언한 분기 2종(정렬키 strict 400 · 이벤트유형 옵션 절단)도 미검증.
- P1 D015-TST-002 **증강→작업배정 교차 지점이 검증되지 않는다** — `TEST-003` 이 등재 게이트를 **서술로만** 적고(*"채택된 파생만 작업목록·배정 대상이 된다"*) **단언 step 이 없다**. steps seq 1~5 전량에 작업목록·배정 API/화면을 거치는 step 0건. `RESL_*` 예외·그랜드퍼더링 축도 시나리오 0건.
- P2 D015-TST-003 `TEST-001`~`005` **전부 `related_domains: []`** — 도메인 횡단 추적 불성립. ★부분 read 오탐 아님(`get_item_schema` 로 필드 실재 확인 + `data_projected_to` 에 포함돼 반환).

### ★★★ 소급 항목 `TEST-001~005 status 불일치` 실체 확정 — 중복 계상 금지

**envelope `status: "approved"` ↔ `data.status: "draft"`** 다(5건 전부). D012 감사자가 판정한 *"`TEST-001` 이 옳고 `UC-011` 이 낡았다"* 는 **본문 내용 축**이고, 이 status 불일치는 **필드 축의 별개 문제**다 — **합치지 말 것.**

정본은 envelope 으로 보인다(5건 모두 리비전 3~7 까지 갱신되며 change_summary 가 승인 상태 유지를 전제). `data.status` 는 스키마 default `draft` 가 남은 것으로 보이나 **확정은 사용자 판단**.

⚠ **같은 패턴이 `UC-029` 에도 있다**(envelope approved ↔ data.status draft) — D015 소속이라 이 도메인 항목으로도 등재.
⚠ **프로젝트 전역 1건**으로 세고 도메인별로 곱하지 말 것.

### ★★ 전역 미착수 축 — 도메인별로 곱하지 말 것

프로젝트 전체에 **`kind=system` TEST 가 0건**(5건 전부 `integration`)이고 `verifies_requirements`·`verifies_nfrs` 가 **5/5 전부 공란**이다. D015 고유 결손이 아니라 **전역 미착수**다.

### ★★★★ 두 검증 체계 갈림 — **5연속** 확정. 단일 결정 사안으로 승격

D011·D012·D013·D014 에 이어 D015 도 같다. 이번엔 **저장소 쪽이 훨씬 풍부하다** — `docs/test-cases/` 에 **`TC-ASSIGN` 38건 이상 + `TC-SORT-001~003` + `TC-FE-236`** 이 실재하고, LogiCraft 에 없는 최신 정책을 이미 반영하고 있다:

| 저장소 케이스 | 반영된 확정 정책 | LogiCraft |
|---|---|---|
| `TC-ASSIGN-009` | *"⚠ 구 `LsTaskAssignHistory` 이중 쓰기는 **폐기**(V4 제거 — 적재처 1곳)"* | 없음 |
| `TC-ASSIGN-030` **P0** | *"대표코드로 필터하면 그룹 전체 작업이 조회된다 … 단일 코드 동등비교면 그룹 나머지가 사라진다"* | 없음 |
| `TC-ASSIGN-034` | *"WORKER 는 그룹 필터로도 본인 배정분만 — IDOR 방지(CWE-639)"* | 없음 |
| `TC-SORT-002` | *"★strict/lenient 차이는 '변경 전 그 엔드포인트가 200 이었는가' 기준이며 **통일 금지**"* | 없음 |

⇒ **"이 도메인 검증 케이스가 없다"가 아니라 "LogiCraft test_scenario 층에만 없다."**

**5연속이면 도메인별 산발 보고가 아니라 「검증 산출물 층을 LogiCraft 로 올릴 것인가」라는 단일 결정 사안이다.** 최종 리포트에서 도메인별 P1 5건이 아니라 **판정 요청 1건**으로 묶는다.

## diagram (11건 — P1 7 / P2 4)

### `STATE-001` — 사각이 실재를 확인했다 (4건)

- **P1 D015-DIAG-007 현행과 폐기가 뒤집혀 있다.** 본문이 *"▣ 신 설계(타깃, **planned**): 비식별화가 마킹 선행 자동 단계로 이동 … **현재 코드는** 마킹(원본) 트리거→VLM→비식별 순(**구 순서**)"* 라고 적는데, 확정 정책은 정반대다 — 비식별 선두가 **구현 완료**이고 저 "현재 코드"가 **폐기 완료된 구 순서**다. status=approved · stale=false · 2026-06-02 무갱신.
- P1 D015-DIAG-008 **두 축 혼재 + 존재하지 않는 전이** — `PROCESSING → COMPLETED` 는 *"`LsRawDataStatus.STTS_COMPLETED` 로 전이하는 코드는 한 곳도 없다"* 고 CLAUDE.md 가 **명시 정정**한 경로이고, `COMPLETED → ASSIGNED` 는 *"배치 완료가 점프시키지 않음"* 이라 못박은 바로 그 경로다. ★**이 도메인 진입 상태 `ASSIGNED` 가 오직 `COMPLETED` 에서만 도달 가능하게 그려져** 배정 도메인 상태 모델이 잘못된 선행 조건 위에 서 있다.
- P1 D015-DIAG-009 **이 도메인 1급 조작인 재배정 전이가 없다**(ASSIGNED 자기전이 부재) + `terminal_states=["APPROVED"]`·invariant *"APPROVED 는 최종 상태 — 이후 전이 불가"* 가 확정 재승인 정책(`REVLT_YN='Y'` 시 `APPROVED→APPROVED` 예외 허용)과 충돌. **`REVLT` 문자열 0건.**
- P2 D015-DIAG-010 ★**구조적 사각의 기전 확정** — `get_item_schema(diagram_state)` 실측: **`depicts_dfeats` 필드가 스키마에 없고**(CDIAG·CMP 와 다름), `belongs_to_domain` 은 **가용한데 미설정**(`hints.domain_required=false`). `referenced_items` 도 `[]`. ⇒ **어느 도메인 감사에도 2개월 넘게 안 걸린 원인**이 이것이다.

### `CDIAG-007` ↔ `ERD-014` (3건)

- P1 D015-DIAG-002 **자기 선언 위반** — description 이 *"ERD-014 의 물리 컬럼을 반영한다"* 는데 3건 불일치: ①`EVENT_TYPE_CD` ↔ 실제 **`EVNT_TYPE_CD`** ②`eventSeq` ↔ PK **`EVNT_ID`** ③**`VER` 누락**. ★③은 *"이 다이어그램만 보고 엔티티를 만들면 `@Version` 이 빠져 2노드 Active-Active 동시 재배정 방어가 사라진다."*
- P1 D015-DIAG-003 **다중도 오류** — `TaskAssignment 1 → TaskEventLog 0..*` 인데 `LS_TASK_EVENT_LOG` 에 `ASSIGNMENT_ID` 컬럼이 **없고** 연결 키가 `RAW_DATA_ID` 하나뿐이며, UNIQUE 가 `(RAW_DATA_ID, USER_NO, TASK_TYPE_CD)` 라 **영상 1건에 배정 행이 최대 2건**(LABELER·REVIEWER) 공존한다. 게다가 `SUBJECT_USER_NO` 가 *"승인/반려 시 NULL"* 이라 **귀속 배정 행이 아예 없는 이벤트**도 있어 `from="1"` 은 성립 불가.
- P2 D015-DIAG-004 `findAssignHistory` / `findEventTimeline` — **params·return_type 이 완전 동일**해 구분 불가. v3 change_summary 가 경위를 밝힌다: 원래 전자는 폐기된 전용 이력 테이블을 읽던 것인데 **반환형만 기계적으로 통일되며 구분이 소멸**했다.

### `CMP-009`·`SD-003` (4건)

- P1 D015-DIAG-006 **보드 조회 노드가 없다** — description 은 정렬·필터 정책을 서술하는데 components 13종에 `/v1/tasks/board` 담당 노드가 0. ★게다가 `TaskQueryController.technology` 가 **`/v1/tasks`** 로 적혀 있어 **관제 inbound 조회 컨트롤러가 보드를 소유하는 것으로 오독**된다(실제로는 전혀 다른 축).
- P2 D015-DIAG-005 `CMP-009.depicts_dfeats: []`·`referenced_items: []` — 본문은 `AssignmentController`·`AssignmentService`·두 Repository 를 그리는데 그래프상 미선언. ⚠ **3개 서브시스템 묶음**이라 D015 단독으로 `DFEAT-006` 만 넣으면 나머지가 미선언으로 남는다.
- P2 D015-DIAG-011 `SD-003` 렌더(23,887 bytes 실물 GET)가 **REVIEWER 시각만** 그린다 — 버튼 전수에 **"작업"·"마킹" 0건**, KPI 도 REVIEWER 5카드뿐. `SCREEN-012` 는 *"KPI 카드(REVIEWER 5종 / WORKER 4종)"* 로 역할 분기를 명시하고 마킹 버튼에 *"비식별 완료 후 마킹 가능"* 비활성 규칙까지 서술한다. ★v33 의 route 변경은 렌더에 route 문자열이 0건이라 **드리프트 아님**을 확인.
- P1 D015-DIAG-001 `UC-029` 를 그리는 SEQ **0건** — project-level 14건 전량 제목 판정(배정·재배정·작업목록 축 없음).

### ★★ 같은 값이 세 곳에서 5 / 8 / 11 로 갈린다

`EVNT_TYPE_CD` 이벤트 유형: **`ERD-014.code_values` 5종** / **`SCREEN-012` 배정 이력 Drawer 가 조회한다고 명시한 8종** / **실물 11종**. `CDIAG-007.enum_values` 도 5종이다. ERD 수정 시 **CDIAG·SCREEN 동시 cascade** 필요.

---

## D015 최종 집계

| 차원 | 건수 | P0 | P1 | P2 |
|---|---:|---:|---:|---:|
| coverage | 6 | 2 | 2 | 2 |
| links | 10 | 1 | 7 | 2 |
| stale | 12 | 3 | 7 | 2 |
| schema | 14 | 4 | 10 | 0 |
| policy | 10 | 4 | 5 | 1 |
| acceptance | 3 | 0 | 2 | 1 |
| requirement | 5 | 0 | 2 | 3 |
| content | 12 | 0 | 6 | 6 |
| test_scenario | 3 | 0 | 2 | 1 |
| diagram | 11 | 0 | 7 | 4 |
| **합계** | **86** | **14** | **50** | **22** |

### ★ 이 도메인의 3대 근본 원인

1. **ADR 자산은 있는데 배선이 전부 끊겼다** — 10개 정책 중 8개가 ADR 보유, 실제 인용은 **1건**. `ADR-038`·`ADR-045`·`ADR-003` 완전 고립. (D014 는 ADR 자체가 0건이었다 — **정반대 형태**)
2. **검증 계층이 통째로 없다** — AC 0 · 귀속 REQ 0 · 귀속 NFR 0 · SEQ 0 · TEST 0. 그런데 `DFEAT-006` 은 priority=must, implemented 100%.
3. **`STATE-001` 이 도메인 축 없이 2개월간 방치** — 스키마에 `depicts_dfeats` 가 없고 `belongs_to_domain` 미설정이라 **어느 도메인 감사에도 걸리지 않았다**. 그 사이 폐기 파이프라인·존재하지 않는 전이·폐기 불변식 3중 드리프트.

### ★ 소급 확인 항목 (최종 리포트 반영)

- `D015-STL-012` 의 **`SD-003` 부분 철회** — 내 전제가 틀렸다(키 8개·description 97자·renders 실재). `API-072` description 은 24자.
- `TEST-001`~`005`·`UC-029` **envelope `approved` ↔ `data.status` `draft`** — 전역 1건으로 계상.
- **추적표 부재 확정** — D011·D014 에서 내가 인용한 "추적표"의 정체 재확인 필요.
- `REQ-014.constraints` 도달 불가 경로 → **D012 소급**.
