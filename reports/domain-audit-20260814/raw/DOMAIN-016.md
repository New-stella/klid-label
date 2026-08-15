# DOMAIN-016 관제 통지 — 원본 감사 결과

> 스킬 `mc-logi-domain-review` v1.5.0 / read-only / 10차원

## 인벤토리 (coverage 실측 — **24 ITEM**, active 23 · deprecated 1)

| 타입 | 건수 | ID |
|---|---:|---|
| domain | 1 | `DOMAIN-016`(draft, v6) |
| domain_feature | 2 | `DFEAT-046`(outbound 통지, approved, **stale=true**) · `DFEAT-047`(inbound 조회 API, approved) |
| api_endpoint | 4 | active 3 `API-074`/`075`/`076`(전부 draft) · **deprecated 1 `API-077`**(GET /v1/batch/status) |
| domain_event | 4 | `EVT-003`(TaskCompleted) · `EVT-004`(TaskModified) · `EVT-009`(DatasetExportCompleted) · `EVT-010`(DatasetReExport — **발행처 0 휴면**) |
| erd | 2 | `ERD-021`(v8) · `ERD-027`(v2) |
| code_module | 1 | `MOD-015` ⚠ 링크가 `belongs_to_domain` 이 아니라 **`implements_in`** |
| use_case | 1 | `UC-009`(approved, v13, **stale=true**) — ★domain_id 필터가 **정상 응답** |
| class_diagram | 1 | `CDIAG-013` — ⚠ `get_neighbors` backward 로 확정(필터 아님) |
| diagram_c4_component | 1 | `CMP-009` — 3도메인 공유 |
| integration_point | 1 | `INT-007`(approved, v5, **2026-08-12 = 도메인 최신**) |
| acceptance | 1 | `AC-009` — `UC-009.covered_by_acceptances` + `DFEAT-046.related_acceptances` 로 확정 |
| diagram_sequence | 1 | `SEQ-010`(happy_path) — `realizes_use_cases` 로 확정 |
| test_scenario | 1 | `TEST-004`(approved, v7, **stale=true**) |
| external_system | 1 | `EXTSYS-005`(관제서버) |
| screen_spec | **0** | 정상(시스템 대 시스템 도메인) |

**도메인 미귀속이나 내용상 소관 2건** — `INT-010`(데이터마트 View 제공, draft v3, **belongs_to_domain 없음 · backward 0**) · `INTSPEC-004`(export NIA JSON 규격, 귀속 판정 보류)
**근거 ADR** — `ADR-007`(양방향 M2M 폐기·단방향 outbound 채택, `UC-009.brownfield.decided_by` 로 **실제 링크됨**) · `ADR-001` · `ADR-037` · `ADR-020`
**0건 확정** — `service_interface`/`module_api`/`library_api`/`data_pipeline`(프로젝트 전체 0 → COV-10 **검사 대상 부재**) · `glossary`/`constant`/`risk`
⚠ **조회하지 않은 타입 33종** — 그 타입들의 "0건"은 주장하지 않는다. **`nfr` 은 `applies_to_domains` 축 미대조**(귀속 미확정).

## coverage (3건 — P1 3)

- P1 D016-COV-007 **데이터마트 View 4종 제공 책임을 맡는 DFEAT 가 없다** — 도메인 description 이 `[관제 조회 패턴]`·`[★파생영상 픽업 경로]`·`[★검수 완료·통지 건의 관제 접근은 무조건 보장]` **3개 구획**으로 자기 책임을 선언하고 `ubiquitous_language` 에도 *"데이터마트 적재용 View"* 를 등재했는데, 활성 DFEAT 2건 description 에 **View 가 0회** 등장한다. 이 책임을 실제로 모델링한 것은 **`INT-010` 하나뿐인데 도메인 귀속이 없고 backward 0** — 즉 **전 프로젝트 어느 DFEAT 도 이 책임을 claim 하지 않는다.** 근거 `ADR-037` 은 실재하는데 실현 DFEAT 가 없다.
- P1 D016-COV-009 **구속 정책 2건이 DFEAT 에 없다** — `[★발송 시점 = export SUCCEEDED 이후]`·`[★토글 경계]` 가 `DFEAT-046` 에 **export·SUCCEEDED·보류·토글 어느 낱말도 0회**. 링크로도 미반영(`triggers=["EVT-003","EVT-004"]` 뿐, 게이트 이벤트 **`EVT-009` 미연결**). ★**DFEAT 만 보고 구현하면 통지가 export 를 앞질러 관제가 구 버전 폴더를 픽업한다.** 같은 정책이 `UC-009`·`SEQ-010` 에는 이미 반영돼 있어 **DFEAT 만 뒤처진 층간 드리프트**.
- P1 D016-COV-004 SEQ 는 `SEQ-010` **1건뿐이고 happy_path**(error_path 0 / edge_case 0). `UC-009.alternate_flows` 2건(통지 전송 실패 → dead-letter·재등록 큐 / 디바운스)이 미시각화. ★부수 — **happy path 도 절반만 덮인다**: `SEQ-010` 은 제목·본문 모두 **TASK_MODIFIED 전용**이고 **TASK_COMPLETED 경로를 그린 SEQ 는 14건 어디에도 없다.**

### ★★★ 층간 드리프트 — `UC-009`·`SEQ-010` 이 폐기된 페이로드 계약을 그대로 들고 있다

| 층 | TASK_COMPLETED | TASK_MODIFIED |
|---|---|---|
| **확정 계약** | required 8 + optional 1 = **9필드** | **3필드**(`job_id`·`changed_items`·`ver_expln`) |
| `INT-007` v5 (08-12) | ✅ 정정 완료 — *"6필드로 적고 있었으나 실제 계약은 required 9필드이며, 그 수로 보내면 수신측이 전량 거부한다"* | — |
| **`UC-009`** (v13) | ❌ **구 6필드** — `evnt_cls_cd`·`evnt_ctgry_cd`·`gen_ai_yn` 3필드 없음 | ❌ *"변경 프레임 목록(SRC_SN+변경 종류)·요약 카운트"* |
| **`SEQ-010`** | — | ❌ *"마지막 수정 일시 + 변경 프레임 목록 + 변경 요약 카운트 + 요청 ID"* |

**이대로 구현하면 `TASK_COMPLETED` 는 전량 `422 VALIDATION_FAILED`** 이고, `TASK_MODIFIED` 는 관제가 받지 않는 필드를 조립하게 된다.

### ★ coverage 가 판정 근거를 남긴 것 (오탐 방지)

- **COV-8 negative 기준 부재** — description 에 "책임 제외" 섹션이 없어 **명시적 위반(P0) 판정이 구조적으로 불가능**했다. P0 0건은 "깨끗함"이 아니다.
- **COV-3 백엔드 전용 예외 적용** — 두 DFEAT 다 description 이 행위자를 외부 시스템으로 못박아 자명하나, *"백엔드 전용"* 문구 자체는 없어 **문자 그대로 룰을 적용하면 P1 2건**이 된다(판정 근거 명시).
- **COV-5 orphan API 0건** — `DFEAT-047.implemented_by_endpoints` 와 완전 일치. `DFEAT-046` 이 공란인 것은 **outbound push 라 우리 쪽 엔드포인트가 없는 것이 정상**.
- **COV-2** — `UC-009.realizes_dfeats` 는 **비어 있지만** `FEAT-003` 경유로 backing 성립(직접 링크 부재는 LINK 소관).

## stale (15건 — P0 6 / P1 4 / P2 5) · 25 ITEM 조회

> 인벤토리 개수 해소: coverage 의 **24** = 귀속 22 + 미귀속 2(`INT-010`·`INTSPEC-004`).

### ★★★★ 주입 전제 오류 10건째 — **이번엔 프로젝트 CLAUDE.md 쪽이 의심된다**

내가 준 `TASK_COMPLETED` 산문과 **`INT-007` v5 실제 필드 목록이 집합 자체로 다르다.**

| | 필드 |
|---|---|
| **`INT-007` v5 원문 (required 9)** | `job_id`·`event_type_cd`·`evnt_cls_cd`·`evnt_ctgry_cd`·**`lclgv_cd`**·**`lclgv_nm`**·`duration_sec`·`image_count`·`gen_ai_yn` + 선택 **`output_ver_no`** |
| **내가 준 산문(= CLAUDE.md)** | 이벤트 타입 + 작업 ID + 영상 메타(파일명·길이·채널) + **검수 완료 일시** + 프레임 개수 + **결과 요약 카운트** + **요청 ID** + `evnt_cls_cd`·`evnt_ctgry_cd`·`gen_ai_yn` |

산문의 **`검수 완료 일시`·`결과 요약 카운트`·`요청 ID` 는 9필드에 하나도 없고**, 반대로 **`lclgv_cd`·`lclgv_nm`·`event_type_cd` 는 산문에 없다.** 즉 **"6→9" 가 아니라 필드 집합 불일치**다. `output_ver_no` 도 산문 페이로드 목록에 없다(CLAUDE.md 다른 절에는 등장).

⇒ **최종 리포트 「프로젝트 CLAUDE.md 갱신 필요」 최상위 항목.** ⚠ 어느 쪽이 정본인지는 **관제 규격 원문 대조가 선행**돼야 하며 이 감사로는 확정 불가.

### P0 6건

- **P0 D016-STL-001** 완료 페이로드 구 서술 3건 — `UC-009` step2 · `EVT-003` description · `TEST-004` step3. **required 키 누락 = 수신측 전량 거부(422)**.
- **P0 D016-STL-002** 수정 페이로드 구 서술 4건 — `UC-009`·`SEQ-010`(description **+ mermaid source 양쪽**)·`AC-009`·`EVT-004`. ★`AC-009` 는 **한 문장 안에 신·구가 섞인다**(구 6요소 열거 + `ver_expln` NON_NULL 비대칭은 정확).
- **P0 D016-STL-003** ★**`TEST-004` 가 존재하지 않는 값을 합격 조건으로 못박았다** — *"각 항목은 프레임 식별자와 변경 종류가 짝지어져 있어야 한다"*, input_data `[5001]`·`[LABEL_UPDATED]`. 실제 페이로드는 **파일명 리스트**라 **확정 계약대로 구현하면 이 시험에서 불합격**한다.
- **P0 D016-STL-004** 도달 불가 흐름 — `DOMAIN-016` *"신고 접수 시 TASK_MODIFIED 발행·해소 시 재산출+재통지로 흐름을 닫는다"* + `AC-009.and_examples` 동일. **승인 이력 영상은 신고 접수가 412 로 거부**(2026-08-10 구속)라 그 분기는 제거됐다. **이 서술을 근거로 구현하면 정책이 금지한 분기를 되살린다.**
- **P0 D016-STL-005** 발송 트리거 반전 미반영 3건 — `CDIAG-013` *"디바운스 윈도우에 축적해 **만료 flush 시 발송**"* · `SEQ-010` mermaid *"디바운스 flush → export 재산출 요청"*(재검토 표식·재승인 게이트·보류가 **한 단계도 없다**) · `CMP-009`. ★**같은 SEQ 가 realize 하는 `UC-009` 는 이미 반영**돼 UC↔SEQ 상충.
- **P0 D016-STL-006** 이벤트 어노테이션 예외 폐기 미반영 — `INTSPEC-004` 가 *"⚠ 예외다 — 재동결 배선이 없어 … (미확정, 배선 선행 필요)"* 유지. 재산출 트리거 목록도 `AC-009` 6종 / `SEQ-010` 7종 / `INTSPEC-004` 7종으로 **`EvntAnnoService` 누락**. `EVT-004` 만 반영.

### P1 4건 · P2 5건

- P1 D016-STL-007 폐기된 *"미확정·협의중 — 확정 계약처럼 취급하지 말 것"* 경고 3건(`DFEAT-046`·`DFEAT-047`·`UC-009`). `INT-007` v5·`EXTSYS-005` 는 **"경로·페이로드 정합 완료, 인증만 미확정"** 으로 좁혔다. ★`DFEAT-047`(inbound)이 **outbound 전용 사유(x-access-token 부재)** 를 달아 사유 자체가 오배치.
- P1 D016-STL-008 stale=true 3건(`DFEAT-046`·`UC-009`·`TEST-004`, 8일).
- **P1 D016-STL-009** ★`EVT-004` 가 **"발행처 12곳 — 실측"** 이라 단언하는데 확정 실측은 **14개 클래스·16개 지점**. 누락 **`TrackMergeService`·`VlmResultService`**. ★`TrackMergeService` 는 **같은 도메인 `AC-009`·`SEQ-010` 이 수정 경로로 명시**해 도메인 내부 상충.
- P1 D016-STL-010 `INT-007`·`EXTSYS-005` status=modified 인데 `legacy_source` 키 자체 부재.
- P2 D016-STL-011 brownfield prominent 공란 5건 / D016-STL-012 implementation 축 신뢰 불가(`INT-007`·`SEQ-010` 은 **implemented 인데 progress 0**) / D016-STL-013 일괄 스탬프 / D016-STL-014 `ADR-037` 완전 고립 / D016-STL-015 B-번호 체계 충돌

### ★★★★ stale 플래그가 **탐지기로 전혀 기능하지 않았다**

- 플래그 true **3건 — 전부 본문 실영향 있음**(플래그만 0건)
- ★ **역방향(`stale=false` + 본문 폐기 모델) 8건** — `SEQ-010`·`AC-009`·`EVT-003`·`EVT-004`·`CDIAG-013`·`CMP-009`·`INTSPEC-004`·`DOMAIN-016`
- ⇒ **P0 6건 중 플래그가 짚어준 것은 0건.** D009~D015 **7연속**이 D016 에서 **8건으로 늘었다.**
- ★ `UC-009`·`TEST-004` 는 **플래그가 가리키는 축(SCREEN-019 참조)과 실제 손상 축(페이로드)이 다르다.**

### ★★★ 컷오프 선이 뚜렷하다 — 2026-08-07 06:51

**일괄 스탬프 12건 / 도메인 22건 중 55%**(06:32:16~06:51:15, 19분). **덮어확정형 7건** — 특히 `CDIAG-013`(06:38)·`SEQ-010`(06:39)은 같은 날 **15:37 `EVT-004` 확정보다 9시간 앞서** *"바꿀 내용 없음"* 도장을 받았다.

**스탬프 이후 도달한 확정 3건이 하나도 반영되지 않았다** — `EVT-004` v3(08-07 15:37) · `INT-007` v5(08-12) · 이벤트 어노테이션(08-13).

⇒ **확정이 도달한 최종 지점은 `INT-007` 하나뿐이고, 그 계약을 소비해야 할 8개 ITEM 이 전부 08-07 06:51 이전에 멈춰 있다.**

★ **반례도 기록** — 2026-08-07 **05:49~06:11 라운드는 성공했다**: V174 뷰 출력명 6종의 구 이름 잔존이 **0건**이고 등장하는 것은 전부 *"(개명 전 X)"* 병기다. **같은 날 아침이어도 내용 정합 라운드와 스탬프 라운드의 결과가 정반대**다.

★ **`CMP-009` v5(08-13)는 도메인 최신 갱신인데도 통지 축을 손대지 않았다** — **최신 타임스탬프가 정합을 뜻하지 않는다**는 사례 반복.

## links (12건 — P1 6 / P2 6) · 26 ITEM 조회

- P1 D016-LINK-001 **발송 게이트 이벤트 미연결** — `EVT-009`·`EVT-010` 은 `documented_emitters`/`documented_consumers` 에 `DFEAT-046` 을 적는데 `DFEAT-046.triggers` 는 `[EVT-003, EVT-004]` 뿐이고 `consumes: []`. ★스키마가 *"documented_* 는 표시 전용 — 링크 생성 안 함"* 이라 명시하고, **`EVT-003`·`EVT-004` 는 실제로 materialize 돼 있어 플랫폼 한계가 아니라 선언 누락**임이 대조로 입증된다.
- P1 D016-LINK-002 `UC-009.realizes_dfeats: []` — ★**하류 영향 실측**: `AC-009` 의 `derived_domain` 이 *"출처 UC 의 DFEAT 도메인, 자동 계산"* 인데 출처가 비어 **AC 가 도메인에 귀속되지 않는다**(`list_items(acceptance, domain_id=DOMAIN-016)` → 0건). **D015 와 같은 수정 순서 의존.**
- P1 D016-LINK-003 `SEQ-010.invokes_apis: []` — 게다가 **`messages[]`·`participants[]` 가 둘 다 빈 배열**이라 흐름 전체가 `source` 문자열에만 있고 `messages[].item_ref` 축 검사가 **구조적으로 성립하지 않는다**.
- P1 D016-LINK-004 ERD 3테이블 전부 `persists_in_tables` 미주장(두 DFEAT 합집합 0). ⚠ `LS_WEBHOOK_IDEMPOTENCY` 는 자체 서술상 **외부 위탁 멱등 원장이라 타 도메인 귀속일 수 있다**(책임 재배치는 판정 요청).
- P1 D016-LINK-005 `INT-010` 도메인 간선 0 — ★**같은 타입 `INT-007` 은 보유**해 도메인 내부 비대칭. `used_by_domains=["DOMAIN-016"]` 선언은 있으나 **이 프로젝트에서 그 필드는 어떤 INT 에서도 간선을 만들지 않는다**(대조군 `INT-001` 실측) ⇒ 실제 결손은 **ITEM 레벨 `domain_id` 미설정**.
- P1 D016-LINK-006 `INT-007` 본문이 inbound 조회 API 3종을 연동 표면으로 명시하는데 `triggers_apis`·`invoked_by_apis` 공란이고 API 쪽 `served_via_integration` 도 부재 — **양방향 0**.
- P2 6건: `INT-007.related_events`·`referenced_in_sequences` 공란 / `SEQ-010.publishes_events` 공란 / 다이어그램 `referenced_items` 공란 2건 / `DOMAIN-016` 의 **forward 간선 0건**(연동 전담 도메인인데 `uses_integrations`·`integrates_with` 전부 공란) / `MOD-015` DFEAT·API 축 링크 부재(**표본 3/3 전역 패턴 가능성**) / NFR→DOMAIN 0건

### ★★★★ 플랫폼 한계 신규 확정 — **EVT→EVT 체인은 한 건도 materialize 되지 않는다**

`EVT-003.consumes=[EVT-009]` · `EVT-009.triggers=[EVT-003]` · `EVT-009.consumes=[EVT-006]` **3건 선언, 간선 0건.** 타 도메인 대조군 `EVT-001` 도 동일(2건 선언, 0건).

⇒ **구속 정책 「`EVT-006` → `EVT-009` → `EVT-003`」 직렬화 체인이 `data` 선언으로만 존재하고 `analyze_impact` 로 전파되지 않는다.** 우리 ITEM 을 고쳐도 해소되지 않는 **플랫폼 이슈 보고 후보**다.

★ 같은 계열로 **`used_by_domains`(INT→DOMAIN)도 materialize 되지 않는다.** 반면 **`implements_domains`(MOD→DOMAIN)는 정상**이다. ⇒ **역방향 선언 필드가 축마다 동작이 다르므로 필드 하나만 보고 링크 부재를 결론내면 안 된다.**

### ★ orphan — 세 층위로 갈라 셌다 (하나의 숫자로 뭉치지 말 것)

| 층 | 기준 | 수 |
|---|---|---:|
| A | 도메인 그래프 backward | **17건** — 활성 23건 중 **4건이 밖**(`AC-009`·`SEQ-010`·`TEST-004`·`EXTSYS-005`, 전부 다른 축으로 귀속) + 미귀속 2 = **6** |
| B | DFEAT 매핑 | 9건 — **미매핑 14건** |
| C | backward 0(아무도 참조 안 함) | 6건 — `API-077`(deprecated 정상)·`MOD-015`(정상) 제외 **실질 고아 4건**(`EVT-009`·`EVT-010`·`SEQ-010`·`INT-010`) |

### ★ links 가 오탐을 피한 지점 (판정 근거 보존)

- **`DFEAT-046.implemented_by_endpoints` 공란은 결함이 아니다** — description 이 인용하는 `notify-completed`/`notify-updated` 는 **관제서버 측 수신 엔드포인트**라 우리 API ITEM 대상이 아니다.
- **`MOD-015` 의 `implements_in` 사용은 관례**다 — `MOD-009` 도 동일 형태이고 스키마가 *"`implements_domains` … `domain.implements_in_modules[]` 와 의미 동일"* 로 정의한다(내가 준 단서 4를 감사자가 검증해 기각).
- **`SCREEN-019` 참조는 정당한 cross-domain**(DOMAIN-005 화면).
- **`API-077`(deprecated) 잔재 0건** — backward 0 + 본문 grep 0.
- ⚠ **`NFR-004`(외부 API 연동 복원력)가 deprecated 인 것은 사용자 확정**이므로 되살리자는 제안이 아니다 — **도메인에 NFR 링크가 0** 이라는 사실만 보고.

## schema (18건 — P0 2 / P1 14 / P2 2) · 15 ITEM + **실물 코드 대조**

### ★★★★★ 전제 오류 10건째 **해소** — 구현이 `INT-007` 편이다. **CLAUDE.md 가 낡았다**

schema 감사자가 **실물 DTO 를 열었다**:

- **`dto/TaskCompletedPayload.java` = record 10 컴포넌트**: `job_id`·`event_type_cd`·`evnt_cls_cd`·`evnt_ctgry_cd`·`lclgv_cd`·`lclgv_nm`·`duration_sec`·`image_count`·`gen_ai_yn`·`output_ver_no`(`@JsonInclude NON_NULL`)
- **`dto/TaskModifiedPayload.java` = 4필드**: `job_id`·`changed_items{images[],jsons[]}`·`ver_expln`(NON_NULL)·**`output_ver_no`(NON_NULL)**

⇒ **`INT-007` v5 가 정본이고 프로젝트 CLAUDE.md 산문이 폐기본이다.** 확정 사항 3가지:

1. CLAUDE.md 가 든 **`검수 완료 일시`·`결과 요약 카운트`** 는 페이로드에 **없다**(각 0건).
2. **`요청 ID` 도 바디에 없다** — `ControlNotifyService` 가 `UUID.randomUUID()` 로 만들어 **fallback 큐 `IDMP_KEY` 로만** 쓴다.
3. **`TASK_MODIFIED` 는 3필드가 아니라 4필드다** — CLAUDE.md 에 **`output_ver_no` 가 빠졌다**.

★ `TaskModifiedPayload` javadoc 이 못박는다: *"내부 식별자(`SRC_SN`)가 아니라 산출 폴더의 실제 파일명"* — **`SRC_SN` 은 명시적 배제 대상**이다.

⇒ **최종 리포트 「프로젝트 CLAUDE.md 갱신 필요」 1순위 · 근거 확정.**

### P0 2건

- **P0 D016-SCH-001** `EVT-003` 이 완료 페이로드를 **폐기 6필드**로 열거 — 그대로 보내면 **전량 422**. `INT-007` 은 고쳤고 `EVT-003` 만 남았다.
- **P0 D016-SCH-012** ★**`API-075` 응답 봉투 구조 불일치** — 스펙은 `data` 를 **배열**로 선언(example 도 배열)인데 구현은 `ApiResponse<Page<TaskLabelsResponse>>` 라 실제로는 **`{content:[...], totalElements, ...}` 객체**다. **스펙대로 파싱하는 관제 클라이언트가 깨진다.**

### P1 14건 — 관제 계약면이 전반적으로 구현보다 좁다

- D016-SCH-002 `EVT-004` 도입부가 *"(변경 프레임 목록 SRC_SN + 변경종류)"* — **같은 ITEM 뒤쪽의 `changed_items` 서술과 충돌**
- D016-SCH-003 `SEQ-010` **mermaid + description 양쪽** 폐기 필드
- **D016-SCH-004** ★`EVT-003`·`EVT-004` 만 **`payload_schema` 가 `{}`** — 대조군 `EVT-009`·`EVT-010` 은 채워져 있다. **계약 본체를 가진 둘만 비어 있는 역전 구조**이고, 이 공란 때문에 위 드리프트가 **산문 대조로만** 발견된다
- D016-SCH-005 `DFEAT-046.persists_in_tables: []` — ERD 두 개가 *"outbound 통지의 영속 백킹 스토어"*·*"검수 후 수정 통지의 누적 창"* 이라고 **스스로 밝히는데도**. (`DFEAT-047` 공란은 조회 전용이라 정상)
- D016-SCH-006 진실원이 사라진 마이그레이션 — `V39`·`V44`·`V144`·`V146`. ★**D014·D015 에 이어 3연속 → 전역 스윕 후보**
- D016-SCH-007 `ERD-021.relationships: []` — 실물 FK 2건(`CASCADE`/`SET NULL`). ★`LS_WEBHOOK_IDEMPOTENCY` 는 **산문에만** FK 를 적고 `references` 구조 필드가 없다. **같은 도메인 `ERD-027` 은 제대로 선언**해 내부 비대칭
- **D016-SCH-008** ★`ERD-027.STTS_CD` **varchar(20)** ↔ 등록 표준용어 **16**. **같은 도메인 `ERD-021` 은 16** 이라 한 도메인에 두 크기. ★**2026-08-14(감사 하루 전) `ERD-020`·`ERD-026` 을 20→16 으로 고친 스윕에서 이것만 빠졌다.** ⚠ **실물도 20 이라 ERD 단독 수정 금지**(마이그레이션 동반)
- D016-SCH-009 `PAYLOAD_CN` — 표준단어 **`PYLD`** 미사용(`PAYLOAD` 는 양 사전 0건), 자기등록 도메인 `V4000` ↔ 실물 `text`. ★**`ERD-027` 은 같은 상황을 예외로 자백**했는데 `ERD-021` 엔 없다
- **D016-SCH-010** ★`APLY_DT`(논리명 **적용**일시) — **`APLY` 는 행안부 표준단어 '신청'** 이고 **'적용' 은 `APLCN`**. 독립 근거(`DE_IDNTF_APLCN_DT`)는 `APLCN` 을 쓴다 ⇒ **한 프로젝트에서 '적용'이 두 약어로 갈리고 `APLY` 는 '신청'과 충돌**
- D016-SCH-011 `SEND_RSLT_CD` 논리명 **'발송결과코드'** ↔ 등록 용어명 **'전송결과코드'**(약어·크기는 정합) → `auto_fixable`
- D016-SCH-013/014/015 API 3건 전부 **구현보다 스키마가 좁다** — `API-075` `frameIds`·`page`·`size` + 400 누락 / `API-076` **페이징 4필드 누락**(javadoc 이 *"총 건수는 totalElements 로 알린다"* 고 명시) / `API-074` **`reviewerName` 누락** — ★그 필드 javadoc 에 *"검수자는 **이름**만 노출하고 이메일·계정 ID 는 싣지 않는다"*(CWE-359) 제약이 있어 **스키마에서 빠지면 그 개인정보 제약이 설계 산출물에서 사라진다**
- **D016-SCH-016** ★**데이터마트 뷰 4종을 담은 ERD 가 0건** — 실물 `V1__baseline.sql` 에 4뷰 실재하고 `EVT-009`·`SEQ-010`·`INT-007` 이 `V_COMPLETED_VIDEO.OUTPUT_PATH_NM` 을 인용하는데 **대조할 스키마 정의가 그래프에 없다.** `EVT-009.change_summary` 가 *"컬럼명이 틀리면 계약 설명 자체가 성립하지 않는다"* 고 적은 바로 그 축이다

### P2 2건

- D016-SCH-017 `API-077`(deprecated) `parameters: []` 인데 400 이 *"limit 범위 위반(1~500)"* — **선언 안 된 파라미터의 검증 실패를 규정**
- D016-SCH-018 자기인증 물리명 — `QUEUE`·`DLQ`·`NEXT` 는 양 사전 **0건**, `CHNL_CD(32)` 는 **대응 표준도메인이 없다**(코드 그룹은 C1~C12 / V10 / V20 뿐). ⚠ **`NEXT_RTRY_DT` 는 CLAUDE.md 가 "전역 치환 금지"로 못박은 의도된 공존이라 정정 대상 아님**(감사자가 확인)

### ★ schema 가 오탐을 피한 지점

- **쓰기 EP `request_body` 부재 — 해당 없음 확인**(활성 4건 전부 GET, outbound POST 2경로는 `api_endpoint` ITEM 이 아니라 `INT-007.transport_meta` 표현)
- **`IDMP_KEY`·`DLQ` 는 자기등록이 아니다** — 출처가 *"KLID 저작도구 표준화 2026-05-30(gov 미존재 기술용어)"* 라 프로젝트 단위 등록 ⇒ 용어 축에서는 참
- **`ERD-027` 의 TEXT 예외 서술은 사실로 검증됐다**(공통표준용어 `CHG_DTL_CN`/내용V4000 실재)
- ERD↔실물 컬럼 **14/14 · 8/8 · 7/7 전부 일치**, 인덱스 2/2·2/2·3/3 일치 — **부분 UNIQUE 조건이 ERD 구조에 표현 불가한 것은 산문 명시로 갈음**해 gap 처리 안 함

### ★ 책임 범위 의문 (coverage 로 라우팅)

`ERD-021` 이 담은 **`LS_WEBHOOK_IDEMPOTENCY` 는 외부 위탁(VLM/AUGMENT/DEIDENTIFY) 멱등 원장**이라 관제 통지 도메인 책임 밖으로 보인다 — `code_values` 에 **통지와 무관한 외부 채널 3종**이 열거돼 있다.

## acceptance (7건 — P0 2 / P1 3 / P2 2)

- **P0 D016-ACC-001** `AC-009` 가 **폐기 계약을 합격 조건으로 못박았다** — `when[0]`·`then[2]` 이 `SRC_SN`·변경종류·수정일시·요약카운트·요청ID 를 규정. **계약에 실재하는 `changed_items`·`output_ver_no` 는 AC 에 없다.** ★같은 AC `then[3]` 은 `ver_expln` NON_NULL 비대칭을 **정확히** 적어 **한 시나리오 안에 신·구가 공존**한다.
- **P0 D016-ACC-002** `and_examples[3]` 이 **도달 불가 흐름을 합격 예시로** 유지 — *"검수 완료 영상의 비식별 누락 신고 접수 → TASK_MODIFIED(META_UPDATED) 발행"*. ★**이중으로 폐기**됐다: 접수가 412 로 막히고, `META_UPDATED` 는 내부 전용 ChangeType 이라 페이로드에 실리지도 않는다.
- P1 D016-ACC-003 `then[0]` 재생성 경로 **6종 ⊂ 현행 8경로/12발행처** — 이벤트 주석 저장·영상 단위 개인정보 판정·메타 수동 편집 누락. ★**같은 AC 의 `statement` 는 트리거를 재승인으로 적는데 `then[0]` 은 "수정 경로가 재생성한다"** 로 어긋난다(재검토 표식·flush 보류 단계 없음).
- P1 D016-ACC-004 **`TASK_COMPLETED` 를 검증하는 AC 가 0건** — `UC-009`(priority=**must**)는 두 통지를 다 담는데 `AC-009` 는 TASK_MODIFIED 전용. **required 9필드·null 키 유지·export 성공 후 발송 순서가 어떤 AC 로도 고정되지 않았다.**
- P1 D016-ACC-005 `DFEAT-047`(inbound 조회 API) **AC 경유·직접 둘 다 0** — `related_acceptances: []` + realize UC 0건. ★미검증으로 남은 계약면: *"bearer JWT + `@PreAuthorize(hasAnyRole('REVIEWER','WORKER'))` + `LabelAccessGuard.verifyRawAccess`(IDOR 방지) — **방향별 인증 체계가 다르다**"*.
- P2 D016-ACC-006 **negative AC 0건**(`is_negative=true` 검사함, 대상 부재 아님) — `INT-007` 이 명시한 위험 분기가 전부 미단언: ★*"**아웃바운드 통지에는 인증 헤더가 없다** … 관제 정본은 `x-access-token` 을 필수로 요구하므로 현재 상태로는 401 이 된다"* · ★*"`ignore-exceptions` 미설정이라 **4xx 도 5회 재시도 후 DEAD_LETTER**  — 관제 409 기반 자기치유 설계와 충돌"*.
- P2 D016-ACC-007 `AC-009.verification_method = manual_test`, `evidence`·`verification_status` 키 부재 ⇒ **관제 계약면의 유일한 합격 기준인데 페이로드 드리프트를 검증 축이 잡지 못했다**(2026-08-07 최종 갱신 ↔ 계약 확정 2026-08-12).

### ★★ 수정 순서 의존 — D015 와 같은 함정, 이번엔 대조군까지 확보

`AC-009.derived_from_use_cases=["UC-009"]`·`UC-009.covered_by_acceptances=["AC-009"]` 로 **양방향 링크는 정상**인데 `UC-009.realizes_dfeats: []` 라 **`derived_domain_ids: []`** 이고 `list_items(acceptance, domain_id=DOMAIN-016)` → **0건**.

★**대조군으로 플랫폼 한계가 아님이 입증됐다** — `AC-022.derived_from_use_cases=["UC-023"]` → `derived_domain_ids=["DOMAIN-005"]` **정상 계산**. ⇒ **우리 링크 결손**이다.

⇒ **`UC-009.realizes_dfeats` 를 먼저 채우지 않으면 신규 AC 3건(ACC-004/005/006)을 만들어도 도메인에 붙지 않는다.**

### ★ 도메인 밖 발견 — D005 소급 항목

`AC-022`(derived_domain_ids=`["DOMAIN-005"]`) `then`: *"승인 시 `LS_RAW_DATA_STATUS` **COMPLETED** 전이 + 라벨 버전 스냅샷 + TASK_COMPLETED 통지가 발행되고"* — **두 곳이 현행과 어긋난다**: ①승인 전이 상태값은 **`APPROVED`**(CLAUDE.md 가 자기 드리프트로 명시 정정한 항목) ②통지는 승인 즉시가 아니라 **export SUCCEEDED 이후**. → **DOMAIN-005 소급 등재.**

## policy (8건 — P0 1 / P1 4 / P2 3) · 24 ITEM 조회

- **P0 D016-POL-001** 승인영상 신고 412 미반영(`DOMAIN-016`·`AC-009`). ⚠**앞 문장(정책 7 "관제 경계에 게이트 미적용 = 의도된 설계")은 정확하다 — 정정 대상은 마지막 절 하나**다.
- **P1 D016-POL-002** ★**outbound 통지에 보호수단이 없다** — `DFEAT-046` 은 *"인계토큰 또는 IP 화이트리스트로 보호"* 라 **단언**하는데 `INT-007` v5 는 `auth_type: "none"` + *"`doPost()` 는 Content-Type 만 설정하며 Authorization 등을 부착하지 않는다. ⚠관제 정본은 `x-access-token` 을 필수로 요구하므로 **현재 상태로는 401**"*. 도메인 24 ITEM 전수에서 *"IP 화이트리스트"* 는 `DFEAT-046` **1회뿐**이고 실현 수단·적용 계층을 기술한 ITEM·NFR 은 **0건**. ⚠ inbound 3건은 `security=[{jwt:[REVIEWER,WORKER]}]` 로 **정상** — 갭은 outbound 단독.
- P1 D016-POL-003 재생성 대상 경로 목록 축소 — ★`SEQ-010` 은 *"수정은 **7종이다**"* 로 **닫힌 열거**를 쓴다. `EVT-004` v3 는 이벤트 주석 저장 포함으로 정확해 **도메인 안에서 목록이 갈린다**.
- P1 D016-POL-004 `ADR-037` 완전 고립 — 3건이 **`V114` 로만 근거를 대고 ADR ID 를 한 번도 지목하지 않는다**. ★같은 도메인 `ADR-007` 은 5건이 인용하므로 **인용 관행 자체는 있는데 `ADR-037` 만 빠졌다**.
- P1 D016-POL-005 `INT-010` **brownfield 블록 자체 부재**(같은 타입 `INT-007` 은 4필드 보유).
- P2 D016-POL-006 정책 12·13("결함 아님" 선언)이 `API-076`·`INT-010` 에 **없다** ⇒ **다음 감사가 결함으로 재분류해 뷰·API 를 좁힐 위험**. ★`API-075` 는 같은 계열 정책을 본문에 명시해 **이 두 건만 빠졌다**.
- P2 D016-POL-007 `ADR-001`·`ADR-007` 인용 누락(`DFEAT-046`·`DFEAT-047`·`DOMAIN-016`).
- P2 D016-POL-008 추정 placeholder 잔존 2건 — `DOMAIN-016` *"[추정 — 1차 통합 도메인과의 관계 확인 필요]"* · `API-077` *"[추정 — 1차 대응 확인 필요]"*.

### ★★★★ 정책↔ADR 매트릭스 — **17축 중 ADR 실재 5, 실제 인용 2, ADR 부재 12**

| | 축 | ADR | 인용 |
|---|---|---|---|
| ✅ | 정책2 양방향 M2M 폐기 | `ADR-007` | **5건**(도메인에서 유일하게 건강) |
| △ | export 범위 포함 | `ADR-020` | 본문 1건(스키마상 링크 필드 부재 — **결함 아님**) |
| ❌ | 정책1 작업 단위=영상 1건 | `ADR-001` | **0** |
| ❌ | 데이터마트 뷰 슬림화 | `ADR-037` | **0**(완전 고립) |
| ❌ | 정책15 이벤트 어노테이션 | `ADR-036` 부분 대응 | **0** |
| **❌** | **정책 3·4·5·6·8·9·10·11·12·13·14 + 2-b(보호수단)** | **대응 ADR 자체가 없다** | — |

★**근본 원인 진단**: *"정책5(재생성·통지 트리거 = 검수 승인)는 2026-08-07 구속인데 ADR 로 정형화되지 않아, `CDIAG-013`·`SEQ-010`·`CMP-009` 가 구 트리거를 유지해도 **막을 근거 문서가 없다**"* — stale 이 잡은 P0 의 **뿌리가 여기 있다.**
★**정책8(신고 412)도 대응 ADR 없음** — `ADR-022`(비식별 신고 게이트)가 인접하나 2026-07-30 등록이라 2026-08-10 확정을 담지 못한다. **`D016-POL-001` 의 근본 원인.**

⇒ **세 도메인 대비**: D014 = **ADR 0건** / D015 = **ADR 8건 보유·인용 1건(고립)** / D016 = **인용 관행은 있으나(`ADR-007`) 최근 구속 정책 대부분이 ADR 로 정형화되지 않음.**

### ★★★ 룰 파일 ADR 번호 불일치 — **두 도메인 연속 확인**

D015 에 이어 D016 도 `POL-2`~`POL-7` **6개 전부 SKIP**. 감사자가 실제 ADR 내용을 대조해 기각한 근거:

| 룰 지목 | 룰의 주장 | 이 프로젝트 실제 |
|---|---|---|
| `ADR-051` | 8대 이벤트 범위 | **존재하지 않음**(활성 최대 `ADR-045`). ★게다가 확정 정책은 **정반대**(*"이벤트 유형 허용목록 폐기, 형식 검증만"*) |
| `ADR-045` | BFF 제거 | 증강 검수 결정축 분리 |
| `ADR-028` | 지자체 data scope | 증강 요청 dedup(`ADR-044` 로 superseded) |
| `ADR-027` | x-access-token 통일 | 재비식별 강제 재생성 |
| `ADR-036` | outbound/inbound 통신 방향 | event_annotation 메타탭 |
| `ADR-038` | 이그노어 코드 `EV99999999` | 목록 정렬·필터 정책 |

★**`POL-5` 는 "부분 SKIP" 으로 처리한 것이 정확했다** — 룰의 ADR 근거는 기각하되 *"인증 필요 API 에 토큰 헤더 없음"* 하위 조항은 **outbound 축에 실질 대응**해 `D016-POL-002` 로 보고했다(근거는 룰이 아니라 프로젝트 확정 정책 2).

⇒ **적용된 룰은 `POL-8`·`POL-9` 2개뿐이고, 나머지 6건은 전부 프로젝트 CLAUDE.md 확정 정책과의 직접 대조에서 나왔다.** 최종 리포트 「검사 사각」에 **"스킬 룰 파일이 이 프로젝트용이 아니다"** 를 확정 등재.

## requirement (4건 — P1 1 / P2 3) · 11 ITEM 조회

### ★★★ D015 와 **정반대 형태** — 링크는 살아 있고 REQ 본문이 비어 있다

D015 는 *"내용이 일치하는 REQ 가 있는데 링크만 없었다"* 였다. D016 은 **반대**다 — 상위 추적 체인이 **실재**한다(`FEAT-003.implements_requirements=[REQ-009, REQ-010]` + `DFEAT-046`/`047` specializes + `UC-009` realizes). 그런데 **그 REQ 두 건의 전문에 통지·관제·외부 동기화 서술이 전무**하다.

- **P1 D016-RQ-001** RFP-003(SFR-08) 세 번째 요구 *"기 구축된 데이터 마트 연계 관리기능"* 의 유지분(단방향 통지)을 서술한 **활성 REQ 0건**.
  - 그 요구를 유일하게 서술한 **`REQ-011` 은 결번(deprecated)** 이고 `get_neighbors` backward **0**.
  - ★**결번인데 `implementation = {status: implemented, progress: 100}`** — 범위 외 결정과 구현 기록이 상충.
  - ★**`TASK_COMPLETED` 축은 더 희박하다** — 납품 산출물은 그 문장을 `RQ-SFR-11-10` 에 두고 추적표가 *"KLID-AT-II-007 | 검수 완료 통지 | 송신(단방향)"* 으로 잇는데, 대응 ITEM `REQ-024` 전문에 **'통지' 0회**이고 링크도 `FEAT-008`(검수)뿐이라 `FEAT-003`·`DOMAIN-016` 과 무관하다.
- P2 D016-RQ-002 범위 축소(divergence)가 **`FEAT-003`·납품 문서에만** 기록되고 책임을 넘겨받은 `REQ-009`·`REQ-010` 의 rationale 엔 흔적이 없다.
- P2 D016-RQ-003 **inbound 조회 API 제공 책임의 요구 근거 부재** — 도메인 2대 책임 중 하나인데 `FEAT-003.main_flow` 4단계도 수정 통지 축만 서술한다.
- P2 D016-RQ-004 (advisory) 비책임 명시가 REQ 층에서 **함께 사라졌다** — 결번 처리로 문장 자체가 없어져 활성 REQ 22건 어디에도 *"데이터마트 본체는 외부 위임"* 이 없다. ⚠ **비책임을 위한 신규 REQ 신설은 감사자가 권장하지 않음** — 판정 요청.

### ★★ 근본 원인 — 결번 처리 때 **상위와 하위가 함께 증발**했다

`RQ-SFR-08-06`(데이터마트 동기화)을 범위 외로 결번 처리하면서 **REQ 층의 통지 요구**와 **DFEAT 층의 View 제공 책임**(coverage `D016-COV-007`)이 **양쪽에서 동시에 사라졌다.** 두 발견은 별개가 아니라 같은 뿌리다.

### ★ requirement 가 판정 근거를 남긴 것

- **모드 A(전체 SKIP) 후보였는데 진행한 근거를 밝혔다** — `belongs_to_domain` requirement 는 0건이지만 **2차 식별로 상위 체인이 실재**함을 확인했기 때문.
- ★**"활성 REQ 22건 중 통지 서술 0건"은 프록시 판정이다** — ITEM 전문을 실제로 연 것은 **5건**이고, 나머지 16건은 **납품 정본 문서를 grep** 해 대체했다. 감사자가 *"문서가 ITEM 보다 낡았을 가능성은 배제하지 못한다"* 고 명시.
- **NFR** — 복원력 계약을 담은 활성 NFR **없음**을 확인하되, `NFR-004` deprecated 사유가 **사용자 확정**(*"요구사항이 아니라 기술 방침"*)임을 change_summary 원문으로 확인하고 **gap 으로 계상하지 않았다**(부활 제안도 안 함).
- `RQ-001`(REQ↔RFP 미연결) **해당 없음** — 관련 REQ 4건 전부 `derived_from_rfp` 보유.
- ⚠ **`RFP-003.related_requirements` 가 결번 `REQ-011` 을 승계처 표기 없이 유지** — RFP 는 불변 진실원천이라 이력 보존으로 정상일 수 있어 **판단 보류**(links 로 라우팅).

## test_scenario (9건 — P0 2 / P1 6 / P2 1) · 12 ITEM + **코드·저장소 카탈로그 대조**

- **P0 D016-TST-001** `TEST-004` 가 **존재하지 않는 값을 합격 조건으로** 못박음(재확인). 수정 통지에 **`SRC_SN`·`ChangeType` 필드가 없고**(파일명 리스트뿐), 완료 통지 10 컴포넌트에 **"라벨·메타 결과 요약 카운트" 대응 필드 0건**.
- **P0 D016-TST-002** ★**같은 ITEM 안에서 `objective` 와 `steps` 가 모순** — objective 는 *"재검수에서 승인될 때"* 로 **반전을 반영**했는데 steps 5단계에 **재승인 단계가 없고** `steps[5]` 전제가 *"순번4 수정 발생"* 이다. **구현은 수정 시 `REVLT_YN='Y'` 로 flush 를 보류하므로 이 시나리오대로는 통지가 영영 나가지 않는다**(`LsMonNotiAcmlRepository` L91·L126 실측).
- P1 D016-TST-003 **`EVT-009`(산출 완료)가 5개 step 어디에도 없다** — `steps[3]` 전제가 *"순번2 스냅샷 완료"* 뿐이라 **통지가 export 를 앞지르는 결함을 이 시나리오는 통과시킨다.**
- P1 D016-TST-004 완료 통지 required 필드 미검증 — `TEST-004` 가 다루는 것은 `job_id`·`event_type_cd`·`image_count` **3종뿐**이고 나머지 **7종 검증 step 0건**.
- P1 D016-TST-005 **요청 ID 를 페이로드 입력자료로 기재** — 실제로는 바디에 없고 폴백 큐 `IDMP_KEY` 로만 쓰인다.
- P1 D016-TST-006 `UC-009.main_flow[6]`(관제가 조회 API 로 상세를 가져가 UPSERT)에 **대응 step 0건**. ★`related_apis` 가 **TEST 5건 전부 `[]`** 라 **inbound 조회 3종을 exercise 하는 시나리오가 프로젝트 전역 0건**.
- P1 D016-TST-007 실패·dead-letter·재등록 큐 시나리오 0건 — ★`TEST-004` 가 스스로 *"핵심 테이블 … `LS_CONTROL_NOTIFY_FALLBACK`"* 이라 선언해 놓고 그것을 검증하는 step 이 **0건**이고 괄호 언급 1회뿐이다.
- P2 D016-TST-009 `TEST-004.related_domains: []` → `auto_fixable`

### ★★★★ 두 검증 체계 갈림 — **6연속. 이번엔 회차 번호까지 특정됐다**

저장소 `docs/test-cases/D-review-version-notify.md`(738행 · NOTIFY 66케이스)가 **LogiCraft 에 없는 최신 계약을 12건 이상** 보유한다:

| 회차 | 날짜 | 내용 | LogiCraft |
|---|---|---|---|
| 13 | 08-05 | 완료 **6→9필드** · `ver_expln` 신설 — *"`TC-NOTIFY-003` 의 '6필드 평면'은 **폐기**"* | 미반영 |
| 16 | 08-08 | **트리거 반전** — `REVLT_YN`(V177 신설) 표시 후 **재승인 시점** 발송 | 미반영 |
| 23 | 08-13 | **`output_ver_no`** `TC-NOTIFY-063~066` 신설 | 미반영 |

`TEST-004` 는 **v7 / 2026-08-07T06:48:56 / change_summary = "바꿀 내용이 없어 내용 변경 없이 확인만 기록한다"** ⇒ **일괄 스탬프가 세 회차를 전부 차단했다.**

★ 저장소 카탈로그는 우리가 놓친 함정까지 케이스로 갖고 있다 — `TC-NOTIFY-055`: *"`evnt_cls_cd` 키명은 **관제 스펙명(CLS)이지 우리 컬럼명(CLSF)이 아니다**"*, `TC-NOTIFY-063~066`: *"`output_ver_no` null 처리(required 와 **정반대 비대칭**)"*.

⇒ **프로젝트 차원의 「검증 산출물 동기화 경로 부재」로 총평 승격**(도메인별 gap 아님).

### ★ test_scenario 가 오탐을 피한 지점

- `steps[1]` 의 `IN_REVIEW→APPROVED` 는 **실재 상태값**(`LsRawDataStatus.STTS_IN_REVIEW` 확인 후 제외).
- *"happy path 만 수록"* 선언은 **범위 선언이라 그 자체를 결함으로 보지 않고**, 실패 경로를 덮는 별도 시나리오가 **전역 0건**이라는 사실만 보고.
- deprecated 인용 **0건**(UC 32건 include_retired 대조 + `SCREEN-019` 활성 확인 — **검사 후 0건, 대상 부재 아님**).
- envelope/data status 불일치·`verifies_*` 공란·`related_apis` 공란은 **전역 1건으로 위임**하고 도메인에 곱하지 않음.

## diagram (11건 — P1 6 / P2 5) · 18 ITEM 조회

### ★★★ `CMP-009`·`CMP-005` 공통 패턴 — **description 만 갱신되고 구조화 배열이 안 따라갔다**

- P1 D016-DIAG-003 `CMP-009.description` 은 *"★export 직렬화가 끼어 있다. `ReviewApprovedEvent`(EVT-006) → `DatasetExportBridge` → `DatasetExportCompletedEvent`(EVT-009) → `ControlNotifyEventListener`"* 라고 서술하는데 **components 13건·external_dependencies 6건 어디에도 그 3개 노드가 없다.** ⇒ **`relationships` 17건 중 `to: ControlNotifyEventListener` 인 엣지가 0건**이라 **리스너가 무엇으로 깨어나는지 추적 불가**.
- ★같은 자기모순이 **포털 축에도** 있다(4개 컴포넌트 서술만 있고 배열엔 없음) ⇒ CMP-009 는 **구조적으로 description-only 갱신 상태**.
- ★**`CMP-005`(D005 소유)도 같다** — description 이 *"구 본문은 이 이벤트가 곧 관제 통지로 이어진다고 적었으나 **실제 수신자는 `DatasetExportBridge` 다**"* 라고 **스스로 부정**하는데 `relationships` 에 `ReviewApprovedEvent → ControlNotifyEventListener` 간선이 **그대로 남아 있다**.

### ★★ `CDIAG-013` ↔ ERD 정합 (2건)

- P1 D016-DIAG-001 **`ERD-027`(디바운스 누적 창) 대응 클래스가 통째로 없다** — classes 6건에 `ACML`·`Debounce`·`NotiAcml` 어느 이름도 없고 *"ERD-021 기반"* 으로만 선언. ★**타임라인이 "놓쳤다"를 확정**한다: `ERD-027` 신설(08-06 02:46) **이후** `CDIAG-013` 이 08-07 06:38 에 *"바꿀 내용이 없어"* 로 재확인했다.
- P1 D016-DIAG-002 *"ERD-021 기반"* 자기 선언인데 **물리명 드리프트 4건**(`evntTypeCd`→`eventTypeCd` · `rtryNmtm`→`rtryCnt` · `maxRtryNmtm`→`maxRtryCnt` · `lastErrMsgCn`→`lastErrMsg`) + **ERD 컬럼 2건 누락**. ★누락된 `SEND_RSLT_CD` 는 ERD 가 *"큐 처리상태와 **분리된 축**"* 이라 못박은 컬럼이라 **발송 성공 여부 추적이 모델에서 소멸**했고, `RAW_SN`(V78)은 *"콜백 바디에 `rawSn` 이 없어 `IDMP_KEY` 로 역조회"* 하는 **기전 자체가 빠졌다**.
  ⚠ 감사자가 자기 판정 한계를 명시: **`eventTypeCd` 1건은 다른 6개 속성의 직역 규칙에서 역산한 것이라 프로젝트가 `event` 접두를 허용하면 오탐**(나머지 3건은 표준용어 약어 축이라 확신도 높음).

### ★★ 상위 C4 층·상태 머신

- P1 D016-DIAG-004 **관제 통지 축만 저장소 컴포넌트가 0개** — 배정 2건·포털 1건은 있는데. ⇒ **디바운서가 DB 간선 없이 그려져 `ERD-027` 이 명시적으로 폐기한 인메모리 윈도우 모델로 읽힌다**(*"윈도우가 JVM 인메모리면 … 산출 재생성·통지가 2회 나가고 … 축적분이 통째로 유실된다"*).
- P1 D016-DIAG-005 **소비측 채널 2종이 상위 C4 에 없다** — `CTX-001` 관제 축 간선 2건이 **전부 HTTP** 이고 DB·파일 축 0건, `CNT-001` 에도 `Rel(control, db, …)`·`Rel(control, fs, …)` 0건. 하위(`SEQ-010`)는 *"산출 폴더는 `OUTPUT_PATH_NM` 으로 픽업"* 을 명시하는데 **상위에서 추적 불가**. ⚠ `CNT-001` 은 구조화 배열이 **전부 빈 배열**이고 내용이 `source` 문자열에만 있다(`CTX-001` 은 구조화됨 — **`CNT-001` 만 예외**).
- P1 D016-DIAG-006 ★**`STATE-001` 이 `TASK_MODIFIED` 를 원천 봉쇄한다** — `terminal_states=["APPROVED"]` + invariant *"APPROVED 는 최종 상태 — 이후 전이 불가(CONFLICT)"*. 그런데 `DFEAT-046` 은 *"완료된 영상의 라벨/메타 수정이 **재검수에서 승인될 때** TASK_MODIFIED"* 다. **STATE-001 대로면 그 통지는 영원히 발행될 수 없다.** `REVLT`·재검토·재승인 문자열 **0건**.

### P2 5건

- D016-DIAG-007 `depicts_dfeats` 공란 — ★**`CMP`·`CTX`·`CNT` 전 타입 전역**(표본 6건 확인). `class_diagram` 축은 채워져 있다. **도메인별로 곱하지 말고 프로젝트 1건으로 롤업.**
- D016-DIAG-008 `TaskQueryController.technology = "/v1/tasks"` — ★D015 가 제기한 오독 위험을 **D016 축에서 재판정**: *"틀린 경로가 아니라 **과대선언**"*(실제 표면은 `/v1/tasks/{rawSn}/…` 3건).
- D016-DIAG-009 ★**증강 채널 자기모순 — 어느 쪽이 정본인지 확정 없이 고치면 반대로 틀린다.** `CDIAG-013.description` 은 *"증강 채널(CHANNEL_AUGMENT) 선행 write 는 … 제거됐고"* 인데 같은 ITEM 의 `enum_values` 에 `AUGMENT` 유지, **진실원 `ERD-021.CHNL_CD.code_values` 도 `AUGMENT` 유지**. ⇒ *"코드값 폐기"인지 "write 경로만 폐기"인지 모호 → **판정 요청**.
- D016-DIAG-010 ★**한글 손상 신규 1건** — `CMP-005.description` 의 **`보람다`(→보낸다)**. 같은 ITEM v3 가 **이미 동일 유형 1건(`곀 관제 통지로`→`곧`)을 정정한 이력**이 있어 **같은 문단의 잔여 손상**이다. (D005 소유이나 D016 통지 순서를 규정하는 문장이라 이 축에서 검출)
- D016-DIAG-011 페이로드 값 객체 클래스 0건 — 계약이 `payloadCn: String` 한 덩어리로만 표현. ⚠ 감사자가 *"`DFEAT-046` 이 미확정이라 명시하므로 **필드를 추정해 채우지 말 것**"* 이라고 fix 범위를 스스로 제한.

### ★ diagram 이 앞선 차원을 **정정·보강한 것**

- ★**`SEQ-010.invokes_apis`·`publishes_events` 공란은 전역이 아니다** — `SEQ-014` 는 `invokes_apis=[API-032, API-094]` 를 **채우고 있다**. ⇒ links 가 보고한 그 건은 **D016 고유 결손으로 볼 근거가 있다**(반면 `participants`·`messages`·`fragments` 공란은 표본 3건 전부 동일해 **전역**).
- ★**stale 의 `CDIAG-013` 상충 근거를 강화** — 상충 상대가 `UC-009` 만이 아니라 **`CDIAG-013` 이 depicts 하는 `DFEAT-046` 자신**이 이미 현행이다(**depicts 대상 ↔ 다이어그램 본문 직접 상충**).
- ★**`STATE-001` 은 D016 축 외에도 낡았다** — *"▣ 신 설계(타깃, planned): 비식별화가 마킹 선행 자동 단계로 이동 … 현재 코드는 (구 순서)"*. ★**`CMP-001` v4 는 같은 사유로 이미 정정됐다** ⇒ STATE 만 남았다.
- ★**타 도메인 폐기 모델 3건 발견**(D016 소관 아니라 미계상): `CTX-001`·`CNT-001` 의 **VLM 동기 45s**(논블로킹 확정과 충돌) · `CTX-001` **"비식별 요청(PRVC/PSDO)"**(전체 영상 비식별·게이팅 폐지와 충돌) · **`MNG_* 공유`/`MNG_* validate`**(관제 2차 실측과 재대조 필요).
- ★**신규 STATE 후보** — D016 이 소유한 **두 상태 머신**(`LS_CONTROL_NOTIFY_FALLBACK`: PENDING→RETRYING→SUCCEEDED/DEAD_LETTER · `LS_MON_NOTI_ACML`: PENDING→FLUSHING→행 삭제)이 **어느 상태 다이어그램에도 없다.**
- `ERD-021`(16) vs `ERD-027`(20) 의 `STTS_CD` 는 **서로 다른 테이블의 다른 컬럼이라 다이어그램 축에선 충돌 아님** — schema 에 남김(중복 계상 방지).

⚠ **`list_diagram_coverage` MCP 도구가 없다** — 전 수치는 수동 fallback 차집합이며, 도구가 산출하는 `deprecated_refs`·`dangling_refs`·`undepicted_diagrams` 는 감사자가 직접 계산했다.

## content (10건 — P1 6 / P2 4) · 26 ITEM · **47,978자 / 한글 15,033자**

### ★★★★ 한글 8축 전부 실행 — **신규 손상 0건**. 그리고 **전력이 완결됐음을 검증했다**

축별 후보와 판정: ①10건(전부 `시스템`의 `스템` 부분일치 **오탐**) ②96개 음절 전수 문맥 → 0 ③12건 → 0 ④216건 → 0 ④b 15건 → 0 ④d 171건 재검사 → 0 ⑦**232쌍 전수** → 0 ⑧141 distinct / freq==1 89건 → 0.

★**과거 손상 정정이 완결됐다** — `ERD-027` v2 가 정정했다고 적은 3건(`공바로→곧바로`·`나뉜어→나뉘어`·`통째→통째로`)이 현재 본문에서 **전부 정상형으로 실측**됐고, 그 뒤 유일한 편집인 **`INT-007` v5(08-12)도 8축 전부 통과** — **최신 편집이 새 손상을 만들지 않았다.**

★⑦Hamming 이 **232쌍 전량 오탐**(추정/수정 · 통짜/통지 · 레벨/라벨 · 버튼/버전 · 통보/통지 · 규약/계약)이라 **D015 에 이어 무력함이 재확인**됐다.

### ★★★ content 가 schema 감사자를 **정정했다** — 「검사 사각」 사례

내가 프롬프트에 실은 schema 소견(*"`ERD-021`·`ERD-027` 진실원이 `backend/src/main/resources/db/migration/V44…`·`V39…` 형태"*) 중 **`ERD-021` 부분은 사실이 아니다.** content 감사자가 **전 필드 정규식 grep(경로·확장자 7패턴) → 매치 0건**을 확인했고 실제 원문은 경로가 아니라 **버전 번호 + 엔티티명**이다:

> `ERD-021.description`: *"진실원: **V44**(control_notify_fallback)·**V39**(webhook_idempotency) 마이그레이션 + JPA 엔티티(@Column)."*

⇒ **파일 경로 오염은 `ERD-027` 한 곳**이고 `deploy/onprem/db/schema.sql` 인용도 거기뿐이다. **schema 소견의 `ERD-021` 부분은 철회.**
★ 단 **"사라진 V번호를 진실원으로 인용"** 하는 문제(`V39`·`V44`)는 **그대로 유효**하다 — 오염 축과 stale 축을 갈라야 한다.

### 본문 오염 (4건)

- P1 D016-CNT-004 `ADR-037.references` **4건 전부** `https://example.invalid/commit/<hash>` + `PR#23` ⇒ **URL 이 placeholder 라 아무 데도 닿지 않고 남는 건 커밋 해시뿐**. `INT-007` 은 **표제 자체**에 *"(Phase 5B, **커밋 e2bc7c09**)"*.
  ★**판정 근거를 남겼다** — 마이그레이션 **버전 번호 단독**(`V44`·`V114`·`V138` 등 **24건**)은 오염으로 보지 **않았다**: 그 번호가 `V_COMPLETED_VIDEO`·`DE_IDNTF_FILE_PATH_NM` 처럼 **관제가 실제로 SELECT 하는 스키마 계약면의 세대 식별자**로 쓰여 외부 독자에게도 참이기 때문. 커밋 해시는 저장소 밖에서 무의미.
- P1 D016-CNT-005 코드 파일 경로·확장자 — `ERD-027` 6건 + `EVT-004`/`009`/`010` 의 *"코드: …java (record)"* + ★`SEQ-010` 의 *"진실원: **CLAUDE.md** 「…」"*(저장소 내부 문서를 사양 진실원으로 지목).
- P1 D016-CNT-006 ★**조사 이력이 최신 ITEM 과 정면 모순** — `DFEAT-046`·`DFEAT-047`·`UC-009` 가 *"(2026-07-27 조사에서 … 교집합이 0에 가까웠던 이력)"* 를 유지하는데 `INT-007` v5·`EXTSYS-005` v5 는 *"그 서술은 **해소됨**"* 이라 못박는다. `ADR-037.brownfield.notes` 의 *"**이 조사 범위**에서 미확인"* 도 작업 맥락.
- P2 D016-CNT-007 테스트 코드 구문·구현 상태를 설계 근거로 사용 — ★`EVT-010` 의 **존치 근거 2개 중 하나가 통째로 테스트 코드**(*"여러 서비스 테스트가 `verify(never()).publishEvent(...)` 로 … 회귀 방어한다"*).

### 서술 명료성 (6건)

- **P1 D016-CNT-001** ★**같은 사실이 네 값으로 갈린다** — 수정 경로 열거가 `AC-009` **6** / `SEQ-010` **7** / `INTSPEC-004` **7** / `EVT-004` **12**. 게다가 **`EVT-004` 는 본문 자체가 자기모순**이다: *"[발행처 **12곳 — 실측**]"* 목록에 **`TrackMergeService` 가 없는데** 바로 아래 절이 *"라벨 저장, **트랙 편집·병합**, …"* 을 대상으로 든다.
- P1 D016-CNT-002 ★**`§6 B-2/B-3` 라벨이 두 ITEM 에서 서로 다른 갭을 가리키고 상태까지 상반**된다 — `INT-007` *"해소된 갭(구 B-2/B-3)"* ↔ `EXTSYS-005`·`INT-010` *"B-2/B-3: … **미회신**"*. **B-1·B-4 는 일치하는데 B-2/B-3 만 갈려** 오탈자가 아니라 **라벨 재사용**이다. ⚠ `§6` 자체가 외부 문서 절 번호라 제3자 해석 불가.
- P1 D016-CNT-003 `SEQ-010.description` **2,447자 · 단락 1개(`\n\n` 0) · 최장 문장 584자**. ★**길이가 아니라 구조 부재가 문제임이 대조로 입증**된다 — 같은 분량 `INT-007`(2,450자)은 **10단락 + 표**다.
- P2 D016-CNT-008 `UC-009` — ★**`main_flow` step4 만 actor 가 "검수자(REVIEWER)"** 라 *"페이로드를 구성한다"* 는 시스템 행위가 사람에게 귀속됐고(step2·3·5 는 "시스템"), **`preconditions` 두 번째 항이 "수정이 발생했다"** 라 **step1~3(최초 승인 → TASK_COMPLETED)이 영원히 성립하지 않는다.**
- P2 D016-CNT-009 `TEST-004` objective↔steps 자기모순(**통과 기준을 본문만으로 정할 수 없다**) + `SC-019`/`SCREEN-019` 표기 혼용.
- P2 D016-CNT-010 ★**제목이 두 번째 책임을 가린다** — `ERD-021` 제목은 *"관제 통지 ERD"* 인데 두 번째 테이블 `LS_WEBHOOK_IDEMPOTENCY` 는 *"외부 시스템(비식별 SW/VLM/증강) 위탁 … 멱등 원장"* 으로 **관제 경로에 등장하지 않는다.** 제목만 보고 찾는 사람은 증강 멱등 원장이 여기 있는 줄 모른다.

### ★ content 가 오탐을 피한 지점

- 내부구조 클래스명 **119건 검출했으나** `CMP-009`·`CDIAG-013`·`SEQ-010.source`(mermaid participants) 소재분은 **그 ITEM 타입의 서술 실체 자체**라 오염으로 보지 않음.
- `TEST-004` 의 `IT` 1건은 **`KLID-AT-IT-TS-004` 내부 부분일치**라 테스트 클래스명 아님.
- `API-074~076` 말미의 *"REVIEWER/WORKER."* 단편은 **deprecated `API-077` 도 동일 패턴**이라 전역 관례로 확인 → 곱하지 않음.
- `CMP-009` 는 **제목이 3개 서브시스템을 정직하게 선언**해 CNT-001 아님.

---

## D016 최종 집계

| 차원 | 건수 | P0 | P1 | P2 |
|---|---:|---:|---:|---:|
| coverage | 3 | 0 | 3 | 0 |
| links | 12 | 0 | 6 | 6 |
| stale | 15 | 6 | 4 | 5 |
| schema | 18 | 2 | 14 | 2 |
| policy | 8 | 1 | 4 | 3 |
| acceptance | 7 | 2 | 3 | 2 |
| requirement | 4 | 0 | 1 | 3 |
| content | 10 | 0 | 6 | 4 |
| diagram | 11 | 0 | 6 | 5 |
| test_scenario | 9 | 2 | 6 | 1 |
| **합계** | **97** | **13** | **53** | **31** |

### ★ 이 도메인의 3대 근본 원인

1. **2026-08-07 06:32~06:51 일괄 스탬프(12건 / 55%)가 컷오프를 만들었다** — 이후 도달한 확정 **3건**(`EVT-004` v3 08-07 15:37 · `INT-007` v5 08-12 · 이벤트 어노테이션 08-13)이 **하나도 반영되지 않았다.** 확정이 도달한 최종 지점은 **`INT-007` 하나뿐**이고 그 계약을 소비할 **8개 ITEM 이 전부 그 시각 이전에 멈춰 있다.**
2. **구속 정책 17축 중 12축에 ADR 이 없다** — 그래서 폐기 모델이 남아도 **막을 근거 문서가 없다.** (D014=ADR 0건 / D015=보유하나 고립 / **D016=인용 관행은 있는데 최근 정책이 정형화 안 됨**)
3. **결번 처리로 상위·하위가 함께 증발했다** — `RQ-SFR-08-06` 을 범위 외로 결번하며 **REQ 층 통지 요구**와 **DFEAT 층 View 제공 책임**이 동시에 사라졌다.

### ★ 소급·전역 항목

- **`ERD-021` 파일 경로 오염 소견 철회**(content 가 grep 으로 반증) — 단 사라진 V번호 인용은 유효.
- **`CMP-005`(D005) 한글 손상 `보람다`** · **`AC-022`(D005) `COMPLETED` 전이·승인 즉시 통지** → **D005 소급**.
- **`CTX-001`·`CNT-001` 폐기 모델 3건**(VLM 동기 45s · 비식별 PRVC/PSDO 게이팅 · `MNG_*` 공유) → **전역**.
- **`depicts_dfeats` 공란**(CMP·CTX·CNT 전 타입) · **`participants`/`messages` 공란**(SEQ 전역) → **프로젝트 1건 롤업**.
- **6연속 "두 검증 체계 갈림"** → **판정 요청 1건**.
