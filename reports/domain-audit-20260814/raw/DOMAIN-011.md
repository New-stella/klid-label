# DOMAIN-011 마킹 — 도메인 점검 원본

> 스킬 `mc-logi-domain-review` v1.5.0 (read-only) · 10차원 · 재개 세션(2026-08-14)
> 인벤토리(backward **20건** 전수): **DFEAT 1**(`DFEAT-039`) · **API 활성 4 / retired 3**(`API-048`·`049`·`050`) ·
> 화면 `SCREEN-006` · `SD-012` · **`EVT-001`** · `CDIAG-002` · `CMP-002` · `ERD-013` · `MOD-004` · `AC-027`·`AC-028` · NFR 3(011·018·019, 4도메인 공유)
> **forward 0건**(collaborates_with 없음). 도메인 귀속 UC = **`UC-019` 1건**(양방향 확인).

---

## coverage (6건 — P0 2 / P1 3 / P2 1)

### P0 2건

**D011-COV-001 (P0) — 활성 API 4건 중 3건 orphan**
**유일 DFEAT 인 `DFEAT-039` 의 `implemented_by_endpoints` 가 공란**(다른 링크 배열도 동반 공란: `invokes_apis`·`consumes`·`triggers`·`persists_in_tables`·`related_acceptances`·`acceptance_rules`).
★**`API-047 POST /v1/videos/{rawSn}/markings` 는 이 도메인의 핵심 쓰기 경로인데 그 도메인의 유일 DFEAT 가 자기 엔드포인트로 선언하지 않는다.**
(`API-091` 만 매핑 있음 — 단 **타 도메인 `DFEAT-048`** 소유)

**D011-COV-002 (P0) — `SEQ-001` 이 deprecated UC 를 가리킨다**
`UC-019`(활성·approved·**must**)를 realizes 하는 SEQ **0건**.
마킹 흐름을 담은 유일 SEQ 인 **`SEQ-001`**(*"영상수집 파이프라인 — 비식별·마킹부터 트랙 보간까지"*, **active·approved·stale=false**)의 `realizes_use_cases` 가 **`["UC-026"]`** 인데 **`UC-026` 은 `status=deprecated`** 다.
⇒ **활성 UC 에서 도달 불가.**

### P1 3건

| ID | 요지 |
|---|---|
| D011-COV-003 | `UC-019` 가 `alternate_flows` **3종**(비식별 누락 발견 / **신고 접수 조건 불충족 412** / **비식별 미완료 404**)을 보유하는데 **error path SEQ 0건** |
| D011-COV-004 | `SCREEN-006` 에 `references_features`·`references_dfeats` **키 자체 부재**(★같은 projection 에서 `realizes_use_cases` 는 빈 배열로 반환돼 **"값 없음"이 아니라 "키 부재"임이 대조로 입증**). 실제 backing 은 존재하나(belongs_to_domain·consumes API-047·`UC-019.related_screens`) **그래프 링크가 없다** |
| **D011-COV-005** | ★**`DFEAT-039` 가 책임 R5(VLM 위탁 연계)를 부분 실현** — *"잔여 배치를 트리거한다"* 수준이고 **위탁 입력 도출(`frame_policy`·`event_type`·`framerate`)·논블로킹 제출·신고 구간 보류/재위탁이 0회 등장**. `UC-019`(v12, 08-07)는 그 계약을 **이미 보유**하는데 **DFEAT(v4, 06-02)만 뒤처졌다** |

### P2 1건
- **D011-COV-006** 책임 R4(마킹 단계 신고)를 맡는 **DOMAIN-011 소속 DFEAT 가 없고 타 도메인 `DFEAT-048` 이 그 책임과 `API-091` 을 소유**한다. ⚠ **"미커버"가 아니라 귀속 불일치** — `API-091` 은 `belongs_to_domain=DOMAIN-011` 인데 그것을 implements 하는 DFEAT 는 타 도메인. **절대 부재가 아니라 P2**

### ★ 도메인 책임 인벤토리 (대괄호 영역 구획을 매트릭스로 인정 — COV-1 통과)
R1 자동(프레임 간격)/수동(단축키) 이벤트 시점 식별·표시 / R2 `MARKING_READY` 진입 게이팅 + 완료 시 잔여 배치 트리거(`deIdntfYn='Y'` 가드) /
R3 마킹 화면(비식별 스트리밍 Range·배속 0.25x~4x·단축키, **비식별 미완료 404**) / R4 마킹 단계 신고(rawSn) / R5 VLM 위탁 연계(논블로킹·보류·재위탁)
**명시적 "책임 제외" 섹션 없음.** **COV-8 gap 0**(DFEAT-039 책임이 R1·R2·R3 에 정확히 대응) · **COV-2 gap 0**(`UC-019.realizes_dfeats` 양방향 성립).

### ★★ coverage 가 넘긴 강한 단서 — **플래그 검출력 0 사례 재현**
★**`DOMAIN-011`(stale=false, v2, 08-04)과 `DFEAT-039`(stale=false, v4, 06-02)가 둘 다 폐기 모델을 본문에 보유**한다.
*"마킹 결과(**이벤트명 + 영상경로 + marks 배열**)를 외부 VLM 에 위탁"* ← `UC-019`(v12)가 *"★구 서술 …는 **폐기되었다** — verify 요청 규격에 그 필드들이 없고"* 로 **명시 폐기**한 것이다.
★`DFEAT-039` 는 *"(설계 타깃: 비식별 선두 재배치 … **현재 코드는 마킹(원본)이 전체 배치 트리거, planned**)"* 도 보유 — **같은 도메인 `EVT-001`(v4, 08-04)은 *"구 divergence 서술 폐기: 비식별 선두 파이프라인이 구현 완료됨"* 으로 이미 정정**했다.
★`DFEAT-039.implementation` 은 `implemented/100` 인데 `modules`·`records` 공란이고, **`UC-019.implementation` 은 `planned/0` 이라 서로 모순**.

### ★ 도메인 귀속 재검토 후보 (policy/links 소관)
`API-117 GET /v1/event-types/labels` 는 **`SCREEN-026`(프리셋 관리, D010)만** consumes · `API-181 GET /v1/event-types` 는 **`SCREEN-008`(영상 처리 현황)만** consumes.
**두 API 모두 마킹 화면 `SCREEN-006.consumes_apis`(API-047·043·091·114·084)에 없다** — `belongs_to_domain=DOMAIN-011` 귀속이 **실사용과 어긋난다**.

### ★ 주입 전제 대조 결과 — **역전 없음**
오히려 반대로, 주입한 확정 정책(`frame_policy`·`framerate`=`FRME_INTV_NOCS`·`MARKING_READY` 한정 412·재개 시 `SKIPPED` 종결)이
**`UC-019`(v12)·`SCREEN-006`(v37)·`DFEAT-048`(v11)에는 반영돼 있고 `DOMAIN-011`·`DFEAT-039` 에만 미반영**이다.

### coverage 미확인 층
**COV-6 skip**(1차 소스 grep OFF — 단 `brownfield.status='new'`·*"1차 미존재"* 라 1차 대응물 자체가 없을 가능성) ·
**COV-10 N/A**(SVC/IAPI/LIB 전역 0) · 정적 렌더 미러·로컬 키트·위키·test-cases·코드 층 미확인 ·
`SCREEN-006` 은 **projection 판정**(전체 read 미수행) · **`DFEAT-048` 의 소속 도메인 미확정**(`get_item(fields=[belongs_to_domain])` 이 그 키를 반환하지 않음 — "DOMAIN-011 이 아니다"만 입증).

---

## links (16건 — P0 1 / P1 12 / P2 3)

### D011-LINK-004 (P0) — 활성·approved `UC-019` 가 **deprecated `SCREEN-007` 을 마킹 진입 동선의 근거로 인용**(본문 2곳)
description: *"**영상 목록 화면(SCREEN-007)** 의 미배정 행 액션이 '배정' 버튼에서 '마킹' 버튼 … 으로 통합됐다"* / `brownfield.notes`: *"마킹 진입 UX 팝업 개편(**SCREEN-007**)"*
`SCREEN-007` = **`status=deprecated`, title `[폐기] 영상 목록 화면`** → **폐기 화면이 살아 있는 사양으로 읽힌다.**

### P1 12건

| ID | 요지 |
|---|---|
| D011-LINK-001 | `DFEAT-039` 링크 배열 **7종 전부 공란** → 활성 API **4/4 orphan**. ★`API-047`·`API-091` 은 **`SCREEN-006` 의 "마킹 완료"·"비식별 누락 신고" 버튼 `triggers_api`** 로 직접 구현 엔드포인트임이 본문으로 입증 → auto_fixable |
| D011-LINK-002 | `API-117`·`API-181`(이벤트유형 조회)이 **DOMAIN-011 소속인데 소비 화면이 전부 타 도메인**(SCREEN-026·SCREEN-008). ★**`SCREEN-006.sections[2]`: *"이벤트명은 화면에서 입력받지 않고 관제 인입값에서 자동 소싱"*** — **마킹 화면은 이 API 를 쓰지 않는다** |
| D011-LINK-003 | `DFEAT-039` 에 **`specializes_feature` 키 부재** → FEAT 계보 단절 → API 3건의 `implements_features` **도출 근거 자체가 없다**. `UC-019.realizes_features` 도 공란. **대조군: `API-091` 만 `[FEAT-005]` 보유** |
| **D011-LINK-005** | ★**도메인 핵심 이벤트 `EVT-001`(MarkingCompleted)의 backward 가 0건** — `documented_emitters=[DFEAT-039]` 는 **표시 전용**이고 `DFEAT-039.triggers=[]`, `API-047` 에 `emits_events` 키 부재, `MOD-004` 에 이벤트 링크 없음. **마킹→잔여배치 트리거 경로가 그래프에서 완전 단절**(D005 `EVT-006`·D007 `EVT-011` 3번째 재현) → auto_fixable |
| D011-LINK-006 | `ERD-013`(`LS_MARKING`) backward **0건** — 테이블 책임 주체가 그래프에 없다 |
| D011-LINK-007 | `SEQ-014` 가 본문에서 **2채널 신고를 명시**하는데 `invokes_apis` 에 **마킹 채널(`API-091`)만 빠졌다**(라벨링 `API-032`·해소 `API-094` 는 있음) → auto_fixable |
| D011-LINK-008 | `SEQ-001` 의 링크 배열 **전부 공란**인데 `source` 는 `POST /v1/videos/{rawSn}/deident-report`·`MarkingCompletedEvent`·**논블로킹 제출**·`POST /v1/vlm/callback` 을 명시 → auto_fixable(API-091·EVT-001·INT-002) |
| D011-LINK-009 | `SEQ-001` 이 **"③ 마킹 단계" 독립 구획**을 상세 서술하는데 `realizes_use_cases=["UC-026"]`(폐기)뿐이고 **`UC-019` 없음** → auto_fixable |
| D011-LINK-010 | `SCREEN-006` `realizes_use_cases=[]` + `references_features`·`references_dfeats` **키 부재** — UC 쪽은 `related_screens` 보유(**단방향**). `get_neighbors` 상 **`references`(weak)만 있고 `realizes` 강링크 없음** |
| D011-LINK-012 | `CMP-002.depicts_dfeats`·`referenced_items` 공란 → **도메인 유일 컴포넌트도가 그래프상 아무 기능도 안 그린다**. ★**description 은 `EVT-001`·경로를 ID 로 직접 인용**. 대조군 `CDIAG-002` 는 정상 |
| D011-LINK-013 | `MOD-004` 에 **`realizes_domain_features`·`implements_apis` 키 부재**(스키마엔 존재) — 추적이 `realizes_screens` 축으로만. ★**단 D009·D010 의 "MOD 본문 틀린 SD ID" 패턴은 이 도메인에서 재현 안 됨** |
| D011-LINK-014 | `DOMAIN-011` 의 `uses_integrations`·`integrates_with`·`collaborators` **전부 공란**인데 description 은 VLM 위탁을 명시. **`INT-002`(VLM 시계열 위탁) 쪽도 도메인·DFEAT 역링크 0건** |

### P2 3건
- **D011-LINK-011** `SCREEN-006` 5섹션 중 3개 `references_apis` 공란 — ★**`sections[0]` description 이 `API-114`·`API-084` 를 명시 사용**(*"`<video>` 태그는 Authorization 헤더를 붙일 수 없어 인증이 걸린 /stream 을 직접 재생할 수 없다"*)하는데 **그 둘만 `consumes` 만 있고 `references` 없다** → **잔재가 아니라 섹션 링크 누락**
- **D011-LINK-015** `UC-019` 가 본문에서 **`UC-022` 를 ID 로 인용**하는데 `includes_use_cases`·`extends_use_cases` 공란
- **D011-LINK-016** deprecated `SCREEN-007` 이 활성 `API-047` 에 **consumes·references 링크 유지** → 마킹 API 소비자 목록에 폐기 화면이 계속 뜬다

### ★ auditor 가 스스로 잡은 오판 (방법론 가치)
`CDIAG-002` 를 **잘못된 필드명(`depicts_domain_features`)으로 부분 read 해 "ERD 링크 없음"으로 오판할 뻔했고, `get_item_schema` 확인으로 정정**했다(정식명 `depicts_dfeats`, **CDIAG-002 는 정상 링크 보유**).
⇒ **"부분 read 의 빈 결과를 필드 부재로 단정 금지" 지침이 실제로 작동한 사례.**

### ★★ links 가 넘긴 핵심 — **폐기된 VLM 위탁 규격이 4개 ITEM 에 잔존**
`DOMAIN-011.description` · `DFEAT-039.description` · `CDIAG-002.description` · **`ERD-013` 의 `LS_MARKING` 테이블 description 과 `EVNT_NM`·`VIDEO_FILE_PATH_NM` 컬럼 description**(*"VLM 콜백 페이로드에 포함된다"*).
★**`UC-019`(v12, 08-07)·`AC-028`(08-07)은 이미 신정책으로 갱신** → **주입 정책이 낡은 게 아니라 위 4건이 실제 stale.**

### ★ links 가 넘긴 추가 단서
- **마킹 상태 enum 불일치** — `ERD-013.STTS_CD.code_values` 와 `CDIAG-002.MarkingStatus` 가 `PENDING/VLM_REQUESTED/**VLM_COMPLETED**` 인데 확정 전이는 `PENDING→VLM_REQUESTED→**VLM_FAILED**` 이고 신고 해소 시 **`SKIPPED`** 종결이 있다. **`VLM_FAILED`·`SKIPPED` 가 두 ITEM 어디에도 없다.**
- **`INT-002` 가 references 하는 `INTSPEC-003` 의 제목이 "VLM **describe** 위탁 요청 규격"** — 확정은 `POST /v1/videovlm/verify` 이고 **`describe` 는 폐기**(우리는 호출하지 않는다). ⚠ INTSPEC-003 은 D011 소속이 아니라 미계상.
- `API-091.implements_features=[FEAT-005]` 는 **오지목으로 보고하지 않음** — FEAT-005(개인정보 비식별 처리)가 신고 API 의 기능 축과 **의미상 정합**. **D010 의 FEAT-005 오지목과는 성격이 다르다**고 판단(사용자 확인 여지).
- **TEST 5건 중 마킹을 커버하는 것 0건**(`UC-019.backward` 에 test_scenario 0) — 프로젝트 레벨 dedupe 라 미계상.

### links 미확인 층
`links.unresolved` **측정 불가** · 1차 소스 grep OFF · **정적 렌더 미러 미확인**(SCREEN-006 v37·SD-012 — *"D009·D010 에서 이 층이 통째로 빠져 뚫린 전례"*) ·
로컬 키트·위키·test-cases·코드 층 미확인 · `MOD-004` 의 `stale_reason` 이 가리키는 **3개 필드 특정 불가**.

---

## stale (12건 — P0 6 / P1 4 / P2 2)

> ★ 작은 도메인(backward 20건)인데 **P0 6건** — 폐기 모델이 **도메인 최상위부터 ERD 컬럼까지 수직으로 관통**한다.

### ★★★ 근본 원인 — **활성 `ADR-008` 자체가 폐기 모델을 담고 있다** (D011-STL-005, P0)
`ADR-008`(**`status=approved`**, v1, 06-01, `stale=false`)의 `context` 원문:
> *"고도화는 배치 파이프라인(**마킹→VLM 시계열→비식별→프레임추출→오토라벨링**)의 **시작점으로 '마킹' 단계**를 신규 도입했다. … 마킹 결과(**이벤트명+영상경로+marks**)를 VLM에 **콜백으로 전달**한다."*
`decision_drivers[0]`·`diff_summary` 도 *"배치 파이프라인 **시작점**"*.
★**`EVT-001` 이 이 ADR 을 `decided_by` 로 인용하는데, 같은 `EVT-001` 본문은 정반대를 말한다**(*"그 분기는 해소됐다 … 적재 직후 `VideoIngested`(EVT-005)가 선두 비식별을 돌리고 본 이벤트는 **그 이후의 잔여 배치만** 트리거한다"*).
⇒ **`ADR-008` 은 supersede 표시가 없어 제3자에겐 현행 결정으로 읽힌다.** 이것이 아래 4건의 공급원으로 보인다.

### P0 나머지 5건 — 폐기 VLM 모델의 수직 전파

| ID | 위치 | 잔존 원문 |
|---|---|---|
| **D011-STL-001** | `DOMAIN-011.description`(v2, 08-04) | *"마킹 결과(**이벤트명 + 영상경로 + marks 배열**)를 외부 VLM 에 위탁한다"* ⚠**같은 문단의 "논블로킹 제출"은 현행과 일치** — 폐기는 **입력 3필드 부분 하나** |
| **D011-STL-002** | `DFEAT-039`(v4, **06-02**) | 같은 3필드 + *"(설계 타깃 … **현재 코드는 마킹(원본)이 전체 배치 트리거, planned**)"*. ★**`EVT-001`(08-04)이 그 divergence 를 명시 폐기한 뒤 73일간 미갱신** |
| **D011-STL-003** | `CDIAG-002` description + **`classes[0].methods[3]`** | 부속 배열에 **`toVlmCallbackPayload` 메서드**가 그대로. ★`UC-019` 판정: *"'콜백'이라는 표현도 **방향이 반대**다 — 마킹→VLM 은 위탁/제출이고 VLM→저작도구만 콜백"* |
| **D011-STL-004** | `ERD-013` 테이블 + **컬럼 2건** | `EVNT_NM`·`VIDEO_FILE_PATH_NM` 이 *"**VLM 콜백 페이로드에 포함된다**"* + description 이 *"**배치 파이프라인 첫 단계**"*. ★**`DOMAIN-011` 이 직접 반박**(*"구 서술은 마킹을 '파이프라인의 출발점'으로 적었으나 **선두는 비식별화**"*). ⚠**정작 `FRME_INTV_NOCS` 가 framerate 원천이라는 현행 사실은 이 ERD 어디에도 없다 — 잔존이 아니라 결손** |
| **D011-STL-006** | `SEQ-001` | `realizes_use_cases=["UC-026"]` 인데 **그 UC 는 `deprecated` 이고 `data={}` 로 본문이 비어 있다**. ★**이 도메인 유일 시퀀스이고 realizes 링크가 이것 하나뿐** |

### P1 4건
- **D011-STL-007** `CMP-002.components[4]`·`relationships` 에 **`BATCH_QUEUED` 전이** — 현행(`SEQ-001`·`EVT-001`)은 *"`LS_RAW_DATA_STATUS → PROCESSING`"*. ⚠**같은 낱말이 폐기 모델을 보유한 `ADR-008.context` 에도 있어 출처가 구 모델로 추정**되나 1차 grep OFF 라 **P0 아닌 P1**
- **D011-STL-008** `CMP-002` 가 *"마킹 **CRUD**"* 라 적는데 **조회·삭제 3건이 deprecated**(`API-048`·`049`·`050`) — **활성은 `API-047`(POST) 하나뿐**. ⚠ 세 ID 를 인용한 활성 ITEM 은 없어 **범위 서술만 뒤처짐**
- **D011-STL-009** **스쿼시로 사라진 마이그레이션 번호 인용** — `SCREEN-006`·`SEQ-001` 의 **`(V171)`**, `ERD-013` 의 *"진실원: `db/migration/V45__create_ls_marking.sql`"*·**`V96`**
- **D011-STL-010** `API-091` 이 **`planned`/0 + *"(설계 타겟/planned)"*** 인데 **`UC-019` 는 *"= API-091, **구현 완료**"*, `DOMAIN-011` 은 *"둘 다 구현됐다"***

### P2 2건
- **D011-STL-011** `implementation` 메타가 **도메인 내에서 갈린다** — `DFEAT-039` 만 `implemented/100`(근거 배열은 공란)이고 **`UC-019`·`SCREEN-006`·`ERD-013`·`SEQ-001`·`AC-027`·`AC-028`·`CMP-002.components` 5건·`CDIAG-002.attributes` 12건이 전부 `planned/0`**. 반면 `API-047`·`117`·`181`·`EVT-001` 은 `implemented/100`
- **D011-STL-012** 핵심 ITEM **8건의 `decided_by` 공란**(보유는 `EVT-001`·`UC-019` 2건뿐). ⚠★**그런데 이 도메인의 결정 ADR 인 `ADR-008` 이 D011-STL-005 대상이라, 정정·supersede 없이 연결하면 폐기된 근거가 8건으로 전파된다** — **수정 순서 의존성**

### ★ stale 이 보고하지 않은 것 (근거 명시)
- **stale=true 2건**(`UC-019`·`MOD-004`, 둘 다 *"SCREEN-006의 3개 필드 변경"*)은 **본문에 구 모델 잔존이 없어 미보고**(severity 정규화 지침 준수).
- **superseded ADR(005/011/016/017/028) 인용 0건**(열람 범위 내).
- `SCREEN-006` 의 **'30fps' 고정 서술**이 `ERD-013.FPS` 의 *"30 폴백으로 frameIndex 를 계산하던 TOCTOU 제거"* 와 어긋나 보이나 **화면 표시용 근사 가능성**이 있어 미보고 → screen/content 확인 요망.

### stale 미확인 층
1차 소스 grep OFF(**`BATCH_QUEUED` 실재·`FRME_INTV_NOCS`→framerate 배선·`API-091` 실구현 미확인** — STL-007 을 P1 로 낮춘 이유) ·
**정적 렌더 미러 미확인**(`SCREEN-006` v37·`SD-012` 에 폐기 모델이 남았는지 — `source_hash` 대조 필요) · `SD-012` 는 description 만 부분 read ·
ADR 전수 스캔 미수행(**열람 범위 한정**).

---

## schema (18건 — P0 6 / P1 9 / P2 3)

> ★ 인벤토리가 작은데(테이블 1개) **18건** — 대부분 **ERD·API 계약이 실물과 어긋나는** 유형이다.

### P0 6건

| ID | 요지 |
|---|---|
| **D011-SCH-001** | ★**재마킹 409 를 강제하는 부분 UNIQUE 가 ERD 에 없다** — 실물 `uk_ls_marking_raw_actvtn ON (raw_sn) WHERE stts_cd IN ('PENDING','VLM_REQUESTED')`. `indexes[]` 엔 `IDX_LM_RAW` 1건뿐. ★**구현이 이걸 최종 방어로 명시**(*"동시 요청은 서로의 미커밋 행을 보지 못하므로 이 조회만으로는 막을 수 없고, **최종 방어는 부분 유니크 인덱스**"*). **D9 설계서엔 있고 ERD 만 누락** |
| **D011-SCH-002** | `RAW_SN` FK `on_delete` 가 **`restrict`(ERD) ↔ `CASCADE`(실물 3개 소스 전부)**. ⇒ **설계대로 만들면 파생영상 폐기 배치의 영상 삭제가 FK 위반으로 실패** |
| **D011-SCH-003** | `STTS_CD` 에 **`VLM_FAILED`·`SKIPPED` 2종 누락**(ERD·CDIAG 양쪽). 실물 5종이며 **`VLM_FAILED` 는 *"VLM_REQUESTED 고착(dead-lock)을 해제하는 종결 실패 상태"***. ⇒ **설계대로면 두 종결 상태를 못 다뤄 마킹이 영구 고착** |
| **D011-SCH-004** | `API-047`(마킹 생성)에 **`request_body` 통째 부재** — 실물은 `mode`(필수 enum)·`intervalFrames`·`marks`(상한 20000) 필수. ★**같은 ITEM 의 400 이 *"입력값 검증 실패 (@Valid)"* 라 400 을 전제하면서 검증 대상이 없다** |
| **D011-SCH-005** | `API-181` 이 **실재하지 않는 물리명·값 형식 공표** — `categoryKey` example `"020002"`·*"= **EVNT_CLS_CD**+EVNT_CTGRY_CD"*. **실컬럼은 `EVNT_CLSF_CD`**(관제 스펙명과 다름)이고 **실제 값은 대표 유형코드**(`EV02000201`). ★**같은 ITEM 의 description(v3, 08-07)은 이미 정정돼 스키마만 뒤처졌다** |
| **D011-SCH-006** | ★★**혼동 금지로 명시된 두 컬럼을 뒤바꿈** — `SCREEN-006` 이 이벤트명 조달처를 **`VRFC_EVNT_TYPE_CD`** 라 적으나 실제는 **`LS_DATA_RAW.EVNT_TYPE_CD`**. **DB 주석이 대체·유도를 명시적으로 금지**한다(*"…와 **서로 다른 값이다 — 대체·통합하거나 한쪽에서 유도하지 않는다**"*) |

### P1 9건
`SCH-007` `EVNT_NM`·`VIDEO_FILE_PATH_NM` 이 *"VLM 콜백 페이로드에 포함된다"* — ★**구현 javadoc: *"eventName/marks 원문은 벤더 규격 밖이므로 전송하지 않는다"*** 이고 `media.path` 는 **`LS_DEIDENT_PROC_LOG` 에서 도출**(LS_MARKING 아님) ·
`SCH-008` **`EVNT_NM` 에 저장되는 값은 이름이 아니라 코드**(`EV01000101` 형식)인데 논리명·설명이 *"이벤트명"* ·
**`SCH-009`** ★**표준용어 위반** — **`VIDEO` 는 두 사전 어디에도 등록된 단어가 아니고**(등록 약어는 **`VDO`**) 행안부에 **`VDO_FILE_PATH_NM`(명V300)이 실재**한다. ⚠**사업사전의 `VIDEO_FILE_PATH_NM` 은 출처가 `KLID-저작도구 ERD-013` 인 자기등록이라 독립 근거 아님**. **같은 스키마의 다른 영상 컬럼은 이미 `VDO` 를 쓴다**(`VDO_LEN_SEC`·`VDO_FRM_NO`·`ORGNL_VDO_PATH_NM`) ·
`SCH-010` ★**폐기 블록이 살아 있는 블록보다 정확한 역전** — `batchTriggered`·`batchSkipReason` 이 **`[폐기]` 표기된 200 에만** 있고 **실제 201 스키마엔 없다**(실물 11필드) ·
`SCH-011` `API-091` 이 **실재하지 않는 `frameIndex`** 공표 — DTO 는 `reason` 단일(*"Mass Assignment 방어"*)이고 테이블에도 컬럼 없음. ★**Spring 기본값상 400 도 안 나고 값만 조용히 사라진다** ·
`SCH-012` `CDIAG-002` 속성 **11개 ↔ ERD 12컬럼**(`fps` 누락). ★**ERD 는 08-06 에 FPS 추가, CDIAG 최종 갱신은 08-04** ·
`SCH-013` `DFEAT-039.persists_in_tables` 공란(★**ERD-013 은 반대로 이미 `"2차 신규 마킹 도메인 (DFEAT-039)"` 로 가리킨다**) ·
`SCH-014` `API-047` responses 에 **403·409·412 없음** — 실물 가드가 6단계로 낸다. ★**대조군 `API-091` 은 세 코드를 모두 문서화** ·
`SCH-018` **`EVT-001.payload_schema` 가 빈 객체** — 실물은 `(rawSn, markingSn)` 2필드 record

### P2 3건
`SCH-015` `API-047`·`API-048` 예시의 `status: "ACTIVE"` — **실재 5종에 없다**. ★**폐기 표기된 200 예시는 `PENDING` 으로 올바른데 살아 있는 예시만 틀렸다** ·
`SCH-016` `MARK_CN` 이 사전엔 **V(4000)** 인데 ERD·실물은 `text`. ★**`marks` 상한이 20000 건이라 4000자로는 못 담아 사전 쪽이 낡았을 가능성** ·
`SCH-017` **스쿼시로 사라진 `V45`·`V96` 파일을 진실원으로 인용**

### ★ CSV 정본 grep 실적
4파일 전량 로드(공통 3,284/13,176 · 사업 471/1,373), **컬럼 13개 + 단어 축 23개 + 한글 역검색 9종**.
★**출처 확인으로 자기등록 6건 배제**(`MARKING_SN`·`MARK_MODE_CD`·`MARK_CN`·`STTS_CD`·`FRME_INTV_NOCS`·`VIDEO_FILE_PATH_NM`).
**표준 통과 판정**: `MODE`·`INTV`(간격, *"gov 미존재 기술용어"*)·`FPS`(프레임재생속도, **KLID-BM 배포분**)·`EVNT_NM` 명V200·`REG_DT`/`MDFCN_DT` 연월일시분초D.

### ★ schema 가 넘긴 단서
- ★**`SCREEN-006` 의 30fps 하드코딩이 사양에 남아 있다** — 구현은 이를 **결함으로 판정하고 고쳤다**: *"FE 가 30fps 를 하드코딩해 … **영상 뒤 16.7% 구간을 마킹할 수 없었다**. 근본 수정은 FE 가 서버가 내려준 실 fps 를 쓰는 것"*. ⇒ **폐기된 동작이 사양에 남음**(stale 이 보류했던 건을 schema 가 실측으로 확정).
- `API-048` 은 **메타 `status=deprecated` ↔ `data.deprecated=false`** 로 두 표식이 어긋난다.
- `ERD-013.implementation` 이 `planned/0` 인데 **실물 테이블이 베이스라인에 존재**한다.
- ★`API-181` 은 **같은 ITEM 안에서 description(정정) ↔ 스키마·example(구판)이 갈렸다** — **"설명은 고쳤는데 스키마는 안 고침" 유형이 다른 API 에도 있는지 content 전수 확인 권장**.

### schema 미확인 층
`API-049` 본문 미조회(**같은 성질 갭이 더 있을 수 있다**) · 1차 소스 grep OFF(**SCH-7 판정 안 함** — 단 `brownfield.status=new`·`legacy_source` 부재라 **SCH-6 위반 0**) ·
**SCH-2 미판정**(활성 ERD 17건 전부 physical + 논리층을 `logical_*` 로 담는 관례라 이 도메인만의 누락 아님) ·
**SCH-5 대상 없음**(backward 에 SEQ 0건 — **타 도메인 SEQ 가 `LS_MARKING` 을 인용하는지는 미확인**).

---

## acceptance (5건 — P0 0 / P1 0 / **P2 5**)

> ★ **P0·P1 0건.** 이 도메인 AC 2건은 **내용 자체는 최신 정책을 정확히 반영**하고 있어 (`frame_policy`·`event_type` 비차단·`framerate`=`FRME_INTV_NOCS`), 갭은 **커버 범위·측정 가능성** 축에만 있다.
> ★★**방향 역전 관측**: `DFEAT-039` 가 구 모델인데 **`AC-028` 은 신정책으로 이미 갱신돼 있다** — 즉 **AC 가 최신, DFEAT 가 stale**. (이중 인용 룰상 AC stale 로 보고 불가)

| ID | 요지 |
|---|---|
| `D011-ACC-001` | **negative AC 0건** — `AC-027`·`AC-028` 모두 `is_negative=false`. **미검증 분기**: 신고 해소 후 `MARKING_READY` 되감기 + 활성 마킹 `SKIPPED` 종결 / `VLM_FAILED` 확정 실패 비재시도. ★**프로젝트 전역 신고 축 AC 는 `AC-019` 하나인데 거기에도 되감기·SKIPPED 없음** |
| `D011-ACC-002` | `AC-028` 이 *"논블로킹 제출"* 을 **statement 에 낱말로만** 두고 **scenario 관측 단언 0** — then 3줄이 전부 **요청 조립 입력값** 축. ★그런데 `verification_method=automated_test` (단언 없는 축을 자동 테스트로 표기) |
| `D011-ACC-003` | `AC-027.then[2]` = *"마킹 완료 **다음 단계로** 전이된다"* — **목표 상태값 부재로 통과·실패 판정 불가**. (⚠ `UC-019.main_flow[4]` 도 같아서 **AC↔UC 어긋남이 아니라 측정 가능성 결손**) |
| `D011-ACC-004` | **배속(0.25x~4x)·HTTP Range·`Cache-Control: no-store` 미검증** — 문자열 0건. ★**`no-store` 는 신고 직후에도 클라이언트 캐시로 마스킹 실패 영상이 재생되는 축이라 단순 UI 요건이 아니다** |
| `D011-ACC-005` | AC 2건 모두 `verifies=[]` + **경유 `UC-019.realizes_features=[]`** ⇒ **직접·간접 어느 경로로도 AC→REQ 추적 불성립**. (schema hints 가 명시한 옵션 2 전제 불충족) |

### ★ 정책 커버리지 매트릭스 (11개 확정 정책)
**완전 6** — MARKING_READY 진입 · 자동/수동 방식 · 비식별본 404 · `frame_policy` 도출 · `framerate` 비-FPS · `event_type` 비차단 · AFTER_COMMIT
**부분 4** — 배속(✘) · `no-store`(✘) · *"미지 모드→frame_interval"* 문면 없음 · *"mode 무관 필수"* 미명시 · ★**벤더 요청 `framerate` 상한 축에 단언 없음**(우리 입력 `intervalFrames` 축만 있음 — **`le=240` 실사고가 난 바로 그 축**) · 가드 `deIdntfYn='Y'` 는 함의될 뿐
**미커버 3** — 논블로킹 관측점 · 신고 **해소 후 재개** · 마킹 상태 전이/비재시도
**판정 보류 1** — 승인 이력 신고 거부(마킹 단계는 `MARKING_READY` 한정이라 **정책 9 에 구조적 흡수**)

### acceptance 미확인 층
정적 렌더 · 로컬 키트 · v2-wiki · test-cases · 코드 **5개 층 미개봉** — *"마킹 AC 는 2건뿐"* 판정은 **ITEM 본문 층 한정**.
**ACC-003 룰 skip** — 활성 REQ 22건에 **마킹 전담 REQ 부재**(근접 후보 `REQ-019`·`REQ-026` 도 마킹 미명시)라 룰대로 보류.

---

## policy (12건 — P0 5 / P1 4 / P2 3)

### ★★★ 근본 원인 확정 — stale 의 `ADR-008` 가설이 policy 에서 **독립 재확인**됐다 (D011-POL-009, P0)

`ADR-008`(`status=approved`, **supersede 한 ADR 없음**, `references=[DOMAIN-011, DFEAT-039]`)의 `decision.justification` 원문:
> *"**배치 파이프라인의 시작점으로** 마킹 도메인(DOMAIN-011, LS_MARKING)을 신설하고, 마킹 완료 이벤트로 배치를 자동 기동한다."*

`context` 에는 **폐기 순서 + 폐기 VLM 규격 + 미정의 상태값 `BATCH_QUEUED`** 3종이 함께 들어 있다.
⇒ **`POL-001`~`003`·`007`·`008` 이 전부 이 ADR 의 하위 전파**다. ★**ADR 을 먼저 정정하지 않으면 다음 라운드에 같은 서술이 "근거를 갖고" 되살아난다.**
★선례: **`ADR-013` 은 같은 유형의 역전 모순을 "신규 supersede 대신 본문 직접 수정"으로 해소**한 바 있다(사용자 선택).

### P0 5건

| ID | 요지 |
|---|---|
| `D011-POL-001` | `DOMAIN-011`·`DFEAT-039` description 이 폐기 규격 *"이벤트명 + 영상경로 + marks 배열을 VLM 에 위탁"* 보유. ★**정본이 같은 프로젝트에 이미 있다** — `AC-028` 이 `frame_policy`·`framerate`·`event_type`·`verify` 를 전부 명시 ⇒ **두 번째 진실원** |
| `D011-POL-002` | `CDIAG-002` 는 여기에 더해 **콜백 방향이 뒤집혀 있다** — `Marking.toVlmCallbackPayload()` 메서드 보유. **콜백은 벤더→저작도구 방향**이고 우리가 조립하는 건 verify **요청 바디**다 |
| `D011-POL-003` | `ERD-013` 이 두 컬럼을 **"VLM 콜백 페이로드 구성요소"로 규정** ⇒ 폐기 규격이 **DB 계약면에 고착**. + *"배치 파이프라인 **첫 단계**"* (도메인 본문은 이미 *"시작점이 아니다"* 로 정정됨) |
| `D011-POL-004` | `VLM_FAILED`·`SKIPPED` 부재. ★**`SKIPPED` 는 선택이 아니라 구조적 필수** — 없으면 **신고 해소 후 재마킹 경로가 성립 불가**(부분 유니크 409 를 풀 수단이 없다) |
| `D011-POL-005` | `API-091` responses 에 **412 없음**(`201/401/403/404` 뿐). ★**description 은 거부 정책을 정확히 서술**하고 `AC-028` 도 412 를 기대결과로 규정하는데 **계약면에만 없다** ⇒ OpenAPI 만 읽는 구현·FE 가 코드를 임의로 정한다 |

### P1 4건
`POL-006` `API-091` 201 설명에 **폐기된 *"재비식별 큐 등록"***  — ★**같은 ITEM 본문이 *"자동 재비식별 큐는 없다"* 라고 정면으로 부정**(ITEM 내부 모순) ·
`POL-007` `DFEAT-039` 가 구 순서를 **"현재 코드"로 기술**. ★**`EVT-001` 은 2026-08-04 에 *"그 분기는 해소됐다"* 로 이미 정정** ⇒ **같은 결정이 한쪽에만 반영된 층 누락** ·
`POL-008` `CMP-002` 의 **`BATCH_QUEUED`** — 두 상태 축 **어디에도 없는 값**(`EVT-001` 은 축 중립 표현으로 정정 완료) ·
`POL-010` `API-047` 201 예시 `status: "ACTIVE"` (**schema `SCH-015` 와 동일 건 — 중복 계상 주의**)

### P2 3건
`POL-011` `SCREEN-006`·`API-117` **brownfield 메타 통째 부재**(같은 도메인 12 ITEM 은 전부 보유) ·
`POL-012` **`decided_by` 가 `EVT-001` 한 건에만** — `ADR-008` 이 `DOMAIN-011`·`DFEAT-039` 를 명시 참조하는데 역방향이 없다. ★**단 `POL-009` 해소 후 연결할 것**(폐기 ADR 을 근거로 박으면 오염 전파). ⚠`SCREEN-006` 은 **구속 규칙상 제외**(screen_spec 본문·메타에 `ADR-*` 금지)

### ★ POL-2~POL-7 룰 전량 SKIP — **근거 있는 skip**
타 프로젝트(관제지원) ADR 번호 체계라 대응 정책 없음. **활성 45건 실측 대조**로 확인:
`ADR-045`=증강 2축 분리(BFF 아님) · **`ADR-051` 미존재**(최대 `ADR-045`) · `ADR-027`=재비식별 게이트(토큰 아님) · `ADR-038`=목록 정렬 정책.
**BFF·8대 이벤트·지자체 data scope·x-access-token·EV99999999 정책은 이 프로젝트에 존재하지 않는다.**

### ★★ `API-117`·`API-181` 은 이 도메인 귀속이 부적절 (coverage/links 로 이관)
**근거 4종**: ①`SCREEN-006.consumes_apis` 에 **둘 다 없다** ②★**마킹 화면엔 이벤트유형 선택 UI 자체가 없다**(인입값 자동 소싱이라 조회하지 않고 받아 쓰기만) ③자기 서술이 다른 축(*"필터 드롭다운 옵션"* / *"라벨 표시·코드 해석 공통"*) ④**태그가 `[event-type]`·`[EventType, labels, code-map]`** 인데 실제 마킹 API 4종은 전부 `[marking]`.
⇒ **재귀속은 사용자 결정 사항**이라 policy gap 으로 계상하지 않음.

### policy 미확인 층
**정적 렌더 미러 미점검** — `SCREEN-006`(v37)·**`SD-012`(v7)** 게시 렌더에 폐기 규격이 복제됐는지 `source_hash` 대조 안 함 ·
코드 실측 없음(★**`VLM_COMPLETED` 존치 여부는 코드 확인 없이 확정 불가**라 "삭제" 판정을 내리지 않았다 — 진단은 *"3값 중 하나가 틀렸다"* 가 아니라 ***"집합이 불완전하다"***)

---

## content (17건 — P0 0 / P1 11 / P2 6)

### ★ 한글 손상 **0건** — 5개 축 전부 클린 (문자열 853개)

| 축 | 결과 |
|---|---|
| ① 알려진 오타 15종 | **0건** |
| ② 희귀 음절(≤2회) | 음절종수 416 중 **114종** 전 출현 문맥 확인 → **0건**. 경계 사례 `겟`(설계 타겟)·`텝`(스텝)·`잭`/`랜`(트랜잭션)·`컬`(로컬)·`뀐`(바뀐다) **전부 정상어** |
| ③ 1음절+목적격 | 3건 **전부 오탐** — `찾을`×2(표준 오류 메시지)·`개를`(*"16개를"*, 수량명사) |
| ④ 낱말 고립도 | **288건 플래그**(접두 2음절 휴리스틱이라 잡음 다수) → **전량 육안 확인, 실손상 0** |
| **★④b 절단-빈도비** | 후보 5건(`않는`⊂`않는다` 등) → 전부 **정당한 활용형**. ★D010 유형 재확인: 이 도메인 `바운딩박스` 는 **1회 출현·절단형 없음** |

### P1 11건 — 전부 **본문 오염**(CNT-008/005)

| 대상 | 오염 토큰 |
|---|---|
| `EVT-001` | ★**4종 동시** — 파일경로 `marking/event/…java` + **자기 리비전**(*"이전 본문은 … 라고 적었으나"*, `[★구 divergence 폐기 — 2026-08-04 정정]`) + 구현상태 + `@Async` |
| `ERD-013` ×2 | *"**진실원**: db/migration/**V45**__…sql + … **LsMarking.java**"* / **FPS 컬럼**에 `V96`×2 + `VideoFpsResolver.resolveFps`. ★**같은 표의 다른 11개 컬럼엔 이런 토큰이 없어 이 행만 튄다** |
| `DOMAIN-011` | 자기 리비전(*"구 서술은 … 적었으나"*) + *"둘 다 **구현됐다**"* + `@Async` |
| `DFEAT-039` | *"설계 타깃 … **현재 코드는** … **planned**"* — ★**형제 `EVT-001`·`DOMAIN-011` 은 이미 그 분기가 해소됐다고 서술** |
| `SCREEN-006` ×2 | `(V171)` — ★**발주처 산출물 규약 위반** / **FE 내부 심볼 6종**(`videoRef`·`getCurrentTime`·`getCurrentFrame`·`seekTo`·`selectMark`·`clearMarks`) |
| `CMP-002` | ★**도출 경위** *"컴포넌트·관계는 **실제 생성자 의존성에서 도출**"* + `@Async`·`@TransactionalEventListener` |
| `AC-027`·`AC-028` | ★**`CLAUDE.md` 를 근거로 직접 인용** + *"판정 지점: `VlmTimeseriesStep.resolveEventType` …"* |
| `SEQ-001` | *"**진실원: CLAUDE.md** 「배치 파이프라인」… + **코드(BatchPipelineConfig, DeidentifyStep, VlmTimeseriesStep, VlmResultController, DeidentReportService)**"* + `(V171)`×2(**mermaid `source` 내부 포함**) |

### P2 6건
`CNT-008` `SCREEN-006` 배치단계 섹션에 **다른 섹션의 변경이력 메모**(*"최근 300프레임으로 조정됨"*) — 그 사양은 이미 `sections[2]` note 에 있다 ·
`CNT-012` `API-047` 400 의 `(@Valid)` ·
`CNT-013` `API-117` 의 `@PreAuthorize`·`SecurityConfig` — ★**형제 `API-181` 은 같은 내용을 오염 없이 서술**(*"내부 채널의 검수자·작업자가 공통으로 쓰며 포털 채널은 차단된다"*) ·
`CNT-014` `API-091` 의 `(설계 타겟/planned)` ·
`CNT-016` **`API-047` description 이 40자 한 줄** — ★**응답이 `batchTriggered`·`batchSkipReason` 을 계약으로 내보내는데 본문이 그 개념을 전혀 설명하지 않는다**. 사전 조건(`MARKING_READY`·본인 배정)도 없다 ·
`CNT-017` `SD-012` 말미가 **작업 경위**(*"…더하거나 빼지 않고 유지한 채"*)

### ★ content 가 넘긴 단서
- ★**`API-117` 이 자기모순**: description 은 *"**별도 역할 제한 없이**"* 인데 `security=[REVIEWER, WORKER]`. **형제 `API-181` 은 v3 에서 같은 문구를 이미 교정**했고 `API-117` 만 구 문구 잔존.
- **`SEQ-001` 이 `DOMAIN-011` backward 20건에 없는데** 마킹 단계 사양을 상세 보유 ⇒ 귀속·링크 누락 가능성(**`CNT-015` 는 귀속 확인 후 반영 필요**).

### ★ 스캔 실적 — deprecated 제외를 **실증**
모집단 **22건** = 활성 19(kit-export) + **deprecated 3(개별 `get_item`)**. ★**22 요청 → kit-export 19 반환**으로 `API-048`·`049`·`050` 누락을 직접 확인.
**deprecated 3건 본문 오염 0건 — 활성으로의 전이 없음**(D010 의 `API-033` 전이 사례와 대조).
순회 필드 **30종 재귀 전수**(`source` mermaid 원문·`sections[].components[].note`·`tables[].columns[].code_values` 포함).
**오탐 제외**: `implementation.status` · 렌더 `url`/`generated_by`(UUID 조각이 커밋 해시로 오검) · `MOD-004.file_path`(`code_module` 전용) · `components[].name`·`classes[].name`(사양 어휘).

### content 미확인 층
**정적 렌더 미러**(`SCREEN-006` main.html · **`SD-012` main.html/main.css**) — `source_hash` 대조·HTML 다운로드 미수행 · 로컬 키트 · v2-wiki · test-cases **4개 층 미개봉**(7층 중 **ITEM 본문 1개 층만** 판정).

---

## requirement (3건 — P0 0 / P1 2 / P2 1)

### ★★★ 주입 전제 2건이 반증됐다 (내 오류 — 3·4번째)

**① *"근접 후보 `REQ-019`·`REQ-026` 도 마킹을 명시하지 않는다"* → `REQ-019` 에 대해 틀렸다.**
`REQ-019.description` 원문: *"…적재된 영상을 비식별·**마킹**·오토라벨링 배치 후"* — 명시적으로 호명한다.
**활성 22건 전수 통독 결과 마킹을 호명하는 REQ 는 4건**: `REQ-019`(파이프라인 경유) · **`REQ-012`**(비식별 선두 순서 2회) · **`REQ-014`**(마킹 단계 신고 정책 **4개 조항**) · `REQ-021`(작업 중 누락 신고).
⚠ **이 전제는 acceptance auditor 보고를 내가 검증 없이 다음 프롬프트로 넘긴 것**이다.

**② *"`RFP-003`(SFR-08) 이 유력 후보"* → 추적표 정본과 어긋난다.**
**추적표가 `UC-019` 의 상위를 `RQ-SFR-11-04`·`RQ-SFR-17` 로 지정**하고, 그 둘의 `derived_from` 은 **`RFP-005`(SFR-11)·`RFP-007`(SFR-17)** 다.
`RFP-003.details` 3개 heading 어디에도 마킹·시계열 위탁 bullet 이 없고 `related_requirements=[REQ-006~011]` 에도 마킹 REQ 가 없다.
⇒ ★**CLAUDE.md 의 "SFR-08 흡수" 서술은 마킹 축에서 그래프·문서 어느 쪽에도 반영돼 있지 않다.**

### ★ 반대로 — **이 도메인 REQ 는 확정 정책과 정확히 합치한다** (positive)
차원 룰의 일반 전제(*"REQ 가 가장 stale"*)와 **반대 결과**:
`REQ-012.constraints` *"비식별은 처리 흐름의 **선두 단계**로 마킹·라벨링보다 먼저"* · `REQ-014.constraints` *"마킹 단계 신고는 **마킹 직전 상태에서만 접수**"* / *"마킹 단계 신고는 **마킹부터 다시**"*
⇒ **폐기 모델 잔존 0건.** 이 도메인에서 **REQ 층만 유일하게 깨끗하다.**

### 갭 3건

| ID | 요지 |
|---|---|
| `D011-REQ-001` (P1) | 추적표가 상위로 지정한 두 REQ 에 **마킹 요구 실질 내용이 없다** — `REQ-019` 는 경유 언급 1구절, **`REQ-026` 은 마킹 낱말 0회**(constraints 3건도 무관). 자동/수동 방식·이벤트 트리거·위탁 입력 도출이 **REQ 계층으로 하향되지 않았다** |
| `D011-REQ-002` (P1) | ★★**진실원과 파생물의 역전** — 이 프로젝트 추적 기전은 `UC.realizes_features → FEAT.implements → REQ` 인데 **마킹 FEAT 이 0건**(활성 9건 전량 확인). 그래서 `UC-019.realizes_features=[]` 는 **채울 대상 자체가 없다.** 반면 **문서 추적표에는 귀속이 적혀 있다** ⇒ **다음 재생성 때 이 귀속이 조용히 사라질 수 있다** |
| `D011-REQ-003` (P2) | (advisory) `event_type` 값의 **생성·정확성이 관제 책임**인데 어느 REQ 에도 비책임으로 명시 안 됨 |

### ★ NFR — 함정 회피 확인 + 부수 발견
`applies_to_domains` 로 조회: **`NFR-011`·`NFR-018`·`NFR-019` 3건 귀속**(활성 14건 중).
⚠ **부수 발견**: **`NFR-008`**(학습데이터 단계별 품질관리)이 description 에 *"제작(비식별→**마킹**→오토라벨링) 단계별 수행기준"* 으로 **마킹을 명시하는데** `applies_to_domains=[DOMAIN-003, DOMAIN-010, DOMAIN-005]` 로 **DOMAIN-011 이 빠져 있다.** (같은 근거로 `NFR-011` 은 포함하므로 **관례 이탈**)

### ★ 오탐 억제 2건 (근거 있는 skip)
`RQ-002`(REQ stale) — 위 positive 대로 **갭 없음** ·
`RQ-005`(inline `acceptance_criteria` 빈약) — **전량 `[]` 이고 검증은 별도 AC ITEM 이 담당하는 전역 모델**(`AC-017`→`REQ-019`, `AC-024`→`REQ-026`)이라 관례로 판정 ·
`RQ-003`(divergence 미명시) — **양쪽 다 명시돼 있다**(`REQ-026` 시계열 본체 외부 / `REQ-019` 실영상 수집 외부)

### ★ requirement 가 넘긴 stale 신규 후보
`DOMAIN-011.description` 의 *"이벤트명+영상경로+marks 위탁"* 을 **`UC-019.description` 이 명시적으로 "폐기"로 선언**했는데 도메인 본문엔 잔존 — (**policy `POL-001` 과 동일 대상**, 중복 계상 주의)

---

## diagram (9건 — P0 2 / P1 4 / P2 3)

### ★★★ 새 검사 사각 발견 — `diagram_state`
**`STATE-001` 은 내 프롬프트 스코프에 없었다.** auditor 가 *"누락 방지 위해 project-level 조회"* 로 **자발적으로 포함**해 P0 를 찾아냈다.
⇒ **`diagram_state` 는 D001~D010 열 도메인에서 한 번도 열리지 않았다.** 최종 REPORT 「검사 사각」에 등재.

### ★ 최대 쟁점에 대한 판정 — **갈렸다**

**결백**: ★**`SEQ-001` 은 폐기 모델을 그리지 않는다.** 비식별이 선두(②블록), 마킹은 비식별 영상 대상(③블록), VLM 은 `BO->>VLM` **논블로킹 제출**이고 콜백은 `VLM--)BO` **벤더 발신**. `ADR-008` 의 3요소가 **하나도 없다**.

**유죄 2건** — 갱신 시각이 정확히 갈린다:

| ITEM | 버전·일자 | 상태 |
|---|---|---|
| `SEQ-001` | v7 · **2026-08-07** | ✅ 현행 |
| `CDIAG-002` | v2 · 2026-08-04 | ❌ 폐기 모델 |
| **`STATE-001`** | v3 · **2026-06-02** | ❌❌ **2개월 뒤처짐** |

### P0 2건

**`D011-DIA-001`** — `CDIAG-002` 가 폐기 모델을 **3곳**에 보유. ★가장 구체적인 증거: `classes[Marking].methods` 에 **`toVlmCallbackPayload(): VlmCallbackPayload`** 가 실재한다. ⇒ **그대로 구현하면 벤더 규격 밖 필드를 담은 페이로드를 만들어 연동이 실패한다.**

**`D011-DIA-002`** — ★★**활성·`approved`·`stale=false` 인 `STATE-001` 이 정반대를 단정**:
> *"▣ 신 설계(**타깃, planned**): 비식별화가 마킹 선행 자동 단계로 이동 … **현재 코드는 마킹(원본) 트리거→VLM→비식별 순(구 순서)**"*

**현행이 planned 로, 폐기가 현행으로 뒤집혀 있다.** `ADR-008` 과 같은 뿌리이며 **supersede 시 함께 cascade 대상**.

### P1 4건
`DIA-003` **두 다이어그램이 같은 전이를 다르게 그린다** — `STATE-001` 은 `PROCESSING → COMPLETED`, `SEQ-001` 은 *"`ASSIGNED` 복귀 · 작업 상태의 COMPLETED 는 검수 승인에서만"* ·
`DIA-004` `SEQ-001` 콜백을 *"**마킹별** 자연어 서술"*(다건)로 표기 — ★**현행은 전문 1건 객체 `{accuracy, description}`** 이고 **벤더가 구 배열을 보내면 400** 이다. 위탁 메시지에 `verify` 엔드포인트도 미명시 ·
`DIA-005` `CMP-002` 가 *"마킹 **CRUD**"* — R/U/D 3종이 **전부 deprecated(코드 제거)** 이고 활성은 POST 1건 ·
`DIA-007` ★**`SEQ-014` 가 mermaid 본문엔 `alt 마킹 단계` 로 `API-091` 을 그려 놓고 `invokes_apis` 엔 라벨링·해소만 선언** ⇒ 마킹 신고 경로가 그래프에서 끊김

### P2 3건
`DIA-006` `CMP-002.depicts_dfeats=[]`(형제 `CDIAG-002` 는 정상) ·
`DIA-008` `SEQ-001` 의 **정형 필드 전량 공란**(`messages`·`participants`·`invokes_apis`·`publishes_events`) — 정보가 **mermaid 원문에만** 있어 영향분석에서 추적 불가 ·
`DIA-009` **`UC-019` 를 realize 하는 SEQ 가 없다** — 14건 전수 확인. 마킹 저장·완료·`frame_policy` 도출 흐름이 **어디에도 그려지지 않았다**(신고 흐름은 `SEQ-014` 가 정확히 커버)

### diagram 미확인
**`list_diagram_coverage` 도구가 이 서버에 없다** → 수동 차집합으로 판정(DFEAT 1/1 100%, undepicted=`CMP-002`) ·
`STATE-001` **도메인 귀속 미확정**(`diagram_state` 에 `domain_id` 선언 없음, 프로젝트 전체 1건) — **배치·검수 도메인과 공유**되므로 `DIA-002`·`003` 은 **중복 계상 확인 필요** ·
`CMP-002` 의 `BatchStatusService` 가 실물명과 맞는지 **코드 grep 없이 확정 불가**라 보고하지 않음

---

## test_scenario (5건 — P0 0 / P1 3 / P2 2)

### ★★★ "테스트 0건"이 아니라 **두 체계가 갈렸다** (D011-TST-005)

| 축 | 실측 |
|---|---|
| **LogiCraft** | `test_scenario` **전체 5건**(TEST-001~005) · 마킹 귀속 **0건** |
| **레포 카탈로그** | 고유 TC **3,308건** · **`TC-MARK` 46건**(`C-marking-labeling.md` §C-1) + `TC-STREAM` 12건 |

**내가 제시한 8개 핵심 축 대조 결과 — 7축이 레포에만 있다:**

| 축 | 레포 커버 |
|---|---|
| ①진입 게이트 6단 | `TC-MARK-14`(401)·`15`(403 IDOR)·`17`(404)·`18`(412 비식별)·`19`(412 MARKING_READY)·`20`(400) ✅ |
| ②**동시 요청 409** | ★`TC-MARK-37`(**동시 3요청 → 1×201 + 409, 부분 유니크**) ✅ |
| ③**`frame_policy` 수동 경로** | ❌❌ **0건 — 양 체계 공통 공백** |
| ④`framerate` 경계 | `TC-AIMOCK-54`(**300 수락 — 구 `le=240` 폐기**)·`55`(0 거부) ✅ |
| ⑤`event_type` 비차단 | `TC-VLM-079~084` ✅ |
| ⑥AFTER_COMMIT | `TC-MARK-29~33`·`46` ✅ |
| ⑦신고 412·단계 | `TC-LABEL-127~130`·`150~153` ✅ |
| ⑧스트리밍 404·`no-store`·배속 | `TC-STREAM-B18`(**200·206 양쪽 `no-store`**)·`TC-FE-205`(배속 6단) ✅ |

⇒ **감리 산출물(LogiCraft)과 실검증 자산(레포)이 서로를 참조하지 않는다.**

### 갭 5건

| ID | 요지 |
|---|---|
| `D011-TST-001` (P1) | ★**이음매 공백** — `TEST-001` 은 *"`MARKING_READY` 전이"* 로 **끝나고** `TEST-002` 는 *"마킹 완료된 비식별 영상이 존재한다"* 로 **시작**한다. **그 사이 `UC-019` main_flow 5단계가 어떤 TEST 에도 없다** |
| `D011-TST-002` (P1) | ★★**`frame_policy` 수동 경로가 양 체계 모두 0건** — `frame_selected`·`selected_frames` grep **전 카탈로그 0건**. **상한 8 절단은 벤더 계약 경계값**이고, **같은 계열 오해(`framerate` 를 FPS 로)가 실제로 위탁을 죽인 전례**가 있다 |
| `D011-TST-003` (P1) | ★**`TEST-002` 가 폐기 규격을 검증한다** — `input_data` 가 【이벤트명】【비식별 영상 경로】【마킹 시점 목록】으로 **`UC-019` 가 명시적으로 폐기한 3항목과 1:1 대응**. 현행 필수(`frame_policy`·`framerate`·`event_type`) **하나도 없고** `verify` 엔드포인트도 없다 |
| `D011-TST-004` (P2) | `TEST-001`·`002` 가 **`LS_MARKING` 을 직접 검증하면서** `related_domains=[]` ⇒ 도메인 축 역추적 경로 0 |
| `D011-TST-005` (P2) | 위 체계 이원화 |

### ★ 근거 있는 skip 3건
`TST-002` — DFEAT backward 에 REQ·NFR **0건**이라 룰대로 보류 ·
**시스템시험 축** — `kind='system'` TEST 가 **프로젝트 전체 0건**이고 5건 전부 `verifies_requirements=[]` ⇒ **도메인 결손 아닌 전역 미도입** ·
전역 커버리지 **활성 UC 25건 중 9건(36%)** — 라벨링·작업배정·사용자권한·게시판도 0건이라 **"대외 연동 경계 5축만 작성된 부분 도입"** 패턴. 단 **작성된 상태이므로 SKIP 이 아닌 P1 로 판정**.

### ★ test_scenario 가 넘긴 단서
**`TEST-001~005` 전량 `stale=true` + `data.status='draft'` ↔ item `status='approved'`** 로 두 표식이 어긋난다.

---

# DOMAIN-011 마킹 — 최종 집계

| 차원 | 건수 | P0 | P1 | P2 |
|---|---:|---:|---:|---:|
| coverage | 6 | 2 | 3 | 1 |
| links | 16 | 1 | 9 | 6 |
| stale | 12 | 6 | 4 | 2 |
| schema | 18 | 6 | 9 | 3 |
| policy | 12 | 5 | 4 | 3 |
| acceptance | 5 | 0 | 0 | 5 |
| requirement | 3 | 0 | 2 | 1 |
| content | 17 | 0 | 11 | 6 |
| diagram | 9 | 2 | 4 | 3 |
| test_scenario | 5 | 0 | 3 | 2 |
| **합계** | **103** | **22** | **49** | **32** |

## ★ 이 도메인의 단일 근본 원인 — **`ADR-008`**
`stale`·`policy`·`diagram` **세 차원이 독립 경로로 같은 결론**에 도달했다. 활성·`approved`·supersede 표시 없는 `ADR-008` 이 **폐기 파이프라인 순서 + 폐기 VLM 규격 + 미정의 상태값 `BATCH_QUEUED`** 를 함께 담고 `DOMAIN-011`·`DFEAT-039` 를 `references` 로 직접 가리킨다.
⇒ **수정 순서 의존성**: `ADR-008` 정정/supersede → 그 다음 하위 ITEM. **역순으로 하면 근거가 남아 되살아난다.**

## ★ 갱신 시각이 정확히 갈린다
| 현행 ✅ | 구판 ❌ |
|---|---|
| `SEQ-001` v7(08-07) · `AC-028`(08-06) · `REQ-012`·`014`·`019`·`021` · `EVT-001`(08-04 정정) · `UC-019` v12(08-07) | `CDIAG-002` v2(08-04) · **`STATE-001` v3(06-02)** · `ADR-008` v1(06-01) · `ERD-013` · `DFEAT-039` · `TEST-002` |

## ★ 소급 확인 항목 (완료 도메인 재점검)
1. **`diagram_state`(`STATE-001`)** — D001~D010 **전부 미개봉**. 1건뿐이라 D012~D016 중복 보고 예상 → **dedupe 대상**
2. **`integration_spec`** — 같은 성격 사각(`INTSPEC-003` 발견)
3. **`TEST-001~005` 의 `status`/`data.status` 불일치** — 5건 전부라 **전역 건**
4. **`NFR-008.applies_to_domains` 에 DOMAIN-011 누락** — description 은 마킹 명시

## D011 미확인 층
**정적 렌더 미러**(`SCREEN-006` · **`SD-012`**) — `source_hash` 대조 **전 차원 미수행** · 로컬 키트 · v2-wiki **미개봉** ·
`docs/test-cases` 는 **test_scenario 차원만** 열었다(다른 차원은 미대조) · 코드는 schema·content 만 부분 대조
