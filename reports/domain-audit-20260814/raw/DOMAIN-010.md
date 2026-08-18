# DOMAIN-010 라벨링 — 도메인 점검 원본

> 스킬 `mc-logi-domain-review` v1.5.0 (read-only) · 10차원 · 재개 세션(2026-08-14)
> 인벤토리: **DFEAT 활성 8 / deprecated 2**(`DFEAT-013` 블러·`DFEAT-040` 연습장) · **API 활성 39 / deprecated 1**(`API-033`) ·
> 화면 4(`SCREEN-005` 라벨링 캔버스 · `SCREEN-010` 로드 버전 선택 · `SCREEN-026` 프리셋 관리 · `SCREEN-035` 라벨 관리) ·
> ERD 3(`ERD-010`·`ERD-019` 활성 + **`ERD-008`** 1차 MariaDB 원형) · MOD 8 · `CDIAG-004`
> **다이어그램 커버리지 63%** — missing=[DFEAT-050, DFEAT-051, DFEAT-052]
> `get_neighbors(DOMAIN-010)`: forward 4(D003·D004·D005 collaborates_with + LEGACY-004) / **backward 92건**

---

## coverage (10건 — P0 2 / P1 6 / P2 2)

### P0 2건

**D010-COV-006 (P0) — 활성 API 39건 중 23건 orphan**
★**cross-domain implements 를 39건 전건 `get_neighbors` 로 확인한 뒤 산출**(추론 아님) — 비-orphan 16건 중 4건이 타 도메인 DFEAT 소유(`API-125/126/127`←`DFEAT-020` 트랙 모드 · `API-032`←`DFEAT-048` 비식별 신고).
DFEAT 8건 중 **6건(`DFEAT-012`·`014`·`015`·`016`·`017`·`051`)이 `implemented_by_endpoints` 전건 빈 배열**이고, 채운 건 `DFEAT-050`(4건)·`DFEAT-052`(8건)뿐.
★**라벨링 핵심 저장·조회 경로가 통째로 orphan** — `API-018`(GET labels)·`API-019`(PUT labels)의 backward 가 **`SCREEN-005` 뿐**이다.

**D010-COV-009 (P0) — 도메인 UC 3건에 happy path SEQ 가 1건도 없다**
`UC-021`(라벨 편집·임시저장, **이 도메인 주 유스케이스로 DFEAT-012·017 을 realizes**) · `UC-028` · `UC-032` 전부 SEQ **0건**.
(대조군: `UC-007`←`SEQ-008`, `UC-008`←`SEQ-009` 는 존재하며 `scenario_type` 을 직접 확인함)

### P1 6건

| ID | 요지 |
|---|---|
| **D010-COV-001** | **책임 R3② 학습데이터 버전(스냅샷·diff·롤백)을 맡는 활성 DFEAT 0건.** `DFEAT-017` 은 R3①(작업 임시저장)만 다루며 스스로 *"버전 스냅샷은 이 시점이 아니라 검수 승인 시 생성된다"* 고 명시. `get_neighbors(FEAT-002)` backward 에 **API 8건·MOD-007·CDIAG-015 는 있는데 domain_feature 0건** → **버전관리가 FEAT↔API 로만 이어지고 DFEAT 층이 통째로 빔** |
| **D010-COV-002** | **책임 R4 프리셋을 맡는 활성 DFEAT 0건.** 자산은 실재(`API-037`~`041` 5건 + `SCREEN-026` + `UC-032`)하나 API backward 가 전부 SCREEN-026 뿐이고 `UC-032` forward 에 realizes DFEAT 0건. (구 `UC-025` 는 deprecated 라 대체 근거 아님) |
| **D010-COV-003** | **`세그멘테이션`이 어느 활성 DFEAT 에도 없다** — 도메인은 라벨 형태 6종을 열거하는데 `DFEAT-012` 는 *"바운딩박스/폴리곤/스켈레톤"* 3종만. (SAM2 2종은 D004 축이라 제외) |
| **D010-COV-007** | DFEAT 4건(`014` 캔버스도구·`015` 우측패널·`016` 단축키·`051` 촬영환경/개인정보 메타)이 **UC backing 0 + `specializes_feature` 키 부재**라 FEAT 경유 경로도 없다. **`DFEAT-051` 은 backward 완전 `[]`(고립)**. 전부 SCREEN-005 의 실제 UI 기능이라 백엔드 전용 예외 아님 |
| **D010-COV-008** | **DFEAT 8건 전건이 어떤 SCREEN 에도 인용되지 않았고**, 화면 4건 모두 `references_features`·`references_dfeats` **키 자체 부재** + `realizes_use_cases=[]`. 화면↔기능 추적 **전면 단절** |
| **D010-COV-010** | 도메인 귀속 UC **5건 전부 error path SEQ 없음**. 활성 SEQ 14건에 오류 흐름 전용이 0건 |

### P2 2건
- **D010-COV-004** 책임 R2 의 **'프레임 설명'** 담당 DFEAT 없음(`API-128`·`129` 실재, backward 는 SCREEN-005 뿐). `DFEAT-015` 는 *"객체·메타데이터·이슈사항·이력 탭"* 만 명시
- **D010-COV-005** ★**`DFEAT-050`(이벤트 어노테이션 VQA/CoT 검토·승인·반려)이 도메인 책임 인벤토리 R1~R5·X1 어디에도 없다.** 상위 FEAT 도 타 도메인 축(`FEAT-009` VLM 시계열 → UC-022·MOD-022). ⚠ 다만 *"라벨링 화면에서 caption 수동입력 … evidence 는 캔버스 선택 객체를 자동 연결"* 부분이 이 도메인 화면에서 일어나 **순수 오분류로 단정 못 해 P2**

### ★ 도메인 책임 인벤토리 (auditor 가 description 대괄호 섹션에서 추출 — COV-1 게이트 통과)
R1 [라벨 형태] 바운딩박·폴리곤·**세그멘테이션**·스켈레톤(COCO-17)·SAM2 분할·SAM2 Track
R2 [도구] 캔버스 도구·객체/메타/이슈 탭·단축키·전체 복사붙여넣기·**프레임 설명**·트랙 편집·선형보간
R3 [2계층 저장] ①작업 임시저장(`LS_DATA_LBL`) ②**학습데이터 버전**(`LS_LABEL_VERSION`·롤백)
R4 [라벨 마스터 단일 진실원] 수동=마스터 전체 / **프리셋=오토라벨 전용·`LBL_ID` join**
R5 [신고 구간 차단] 라벨 조회·이력·버전 diff/롤백 **412**
X1 (제외) *"비식별은 이 도메인 밖 — 라벨러 잔존 책임은 누락 발견 시 신고뿐"*
⚠ **번호 목록이 아니라 서술형이라 COV-8 판정에 해석 여지가 있다**(auditor 명시).
**X1 위반 0건** — `DFEAT-013`(블러) deprecated 로 정합, 신고는 `DFEAT-048`(D012)이 `API-032` 로 담당.

### ★ coverage 가 넘긴 단서 (다른 차원 소관)
- **★UC↔FEAT 축 어긋남** — `UC-007`·`UC-008` 이 **`FEAT-005`(개인정보 비식별 처리)를 realizes** 하는데 실제 책임은 **라벨 버전 저장·diff/롤백**이고 API 는 `FEAT-002`(라벨 버전관리)로 붙어 있다. **`FEAT-002` 로 옮겨야 할 것으로 보임** → links.
- **`UC-032` 는 `belongs_to_domain` 링크 자체가 없다**(forward = LEGACY-004, SCREEN-026 뿐) — 도메인 미귀속 UC.
- `SCREEN-005.consumes_apis` 39건 중 **17건이 타 도메인 소속**(API-020·093·102~105·012·123/124·168/170/172/173/177/178/183/184) — 소비 자체는 정상일 수 있으나 소유 확인 필요.
- **stale=true 목록**: `DFEAT-050`·`API-197`·`API-182`·`SEQ-009`·`SEQ-014` + **도메인 귀속 UC 5건 전부**.
- **`ERD-008`**(제목에 *"1차 MariaDB 원형, deprecated"* 명시)이 여전히 `belongs_to_domain=DOMAIN-010` 으로 활성 `ERD-010`·`ERD-019` 와 공존 → stale/policy.
- `DFEAT-012/014/015/016` 은 description 1~2문장 + `(1차 baseline, 화면 SKKLID-UI-02-02-XX)` 표기로 끝나며 `acceptance_rules`·`user_story`·`persists_in_tables` **전건 공란** → content.
- **6건이 `implemented/100` 인데 `modules`·`records` 공란**(DFEAT-050 만 planned/0). 도메인에 MOD 8건이 `implements_in` 으로 붙어 있는데 **DFEAT 쪽 records 와 연결 안 됨**.
- `CDIAG-004` 가 depicts 하는 DFEAT 는 **012/014/015/016/017 5건뿐**이고 신규 3건(050/051/052)은 **어떤 다이어그램에도 없다**.

### coverage 미확인 층
**COV-6 전면 skip**(1차 소스 grep OFF — LEGACY-004·065/068/070/072·017·056/057 링크만 확인) ·
**COV-10 대상 없음**(SVC/IAPI/LIB 프로젝트 전역 0) ·
⚠ **D010-COV-010 은 제목 기반 추론이 섞여 있다** — `scenario_type` 직접 확인은 SEQ-008·009 **2건뿐**, 나머지 12건은 제목·realizes 로만 배제 ·
`DFEAT-020` 소속 도메인 미조회(목록 부재로만 판정) ·
SCREEN 4건은 **projection 으로만** 읽음(`SCREEN-005` 는 `current_version 64` 대형 ITEM — 전체 통독 안 함) ·
`DFEAT-012/014/015/016` 의 `specializes_feature` 부재도 projection 판정(단 `get_neighbors` 에 specializes 링크 없음이 뒷받침).

---

## links (14건 — P0 1 / P1 11 / P2 2)

### D010-LINK-001 (P0) — 버전관리 UC 2건이 **비식별 기능(FEAT-005)** 을 realizes
`UC-007`(라벨 버전 저장·이력 추적)·`UC-008`(버전 비교·복구)이 둘 다 `realizes_features=["FEAT-005"]`(**개인정보 비식별 처리**).
★**`get_neighbors(FEAT-002)`(라벨 버전관리·비교·복구) backward 에 use_case 가 단 1건도 없다** — 버전 API 7건·`MOD-007` 은 전부 `implements_features=["FEAT-002"]` 인데 **그 UC 만 FEAT-005 를 가리킨다**.
`FEAT-002.forward` = `REQ-009`(RQ-SFR-08-04 버전관리·변경이력)·`REQ-010`(RQ-SFR-08-05 비교·복구) → **UC-007/008 의 정확한 상위 기능** → auto_fixable

### P1 11건

| ID | 요지 |
|---|---|
| D010-LINK-002 | DFEAT 6건 `implemented_by_endpoints` 공란 → **API 27건 orphan**(coverage 는 23건으로 셌다 — **links 는 cross-domain 4건을 orphan 에 포함한 집계 차이**. 최종 dedupe 시 기준 통일 필요) |
| **D010-LINK-003** | ★`DFEAT-051` 이 **본문에 엔드포인트를 열거하고 `brownfield.notes` 가 *"코드 실재하나 이 작업 시점 LogiCraft api_endpoint 미등록"*** 이라 적었는데, **그 API 들이 지금은 실재한다**(`API-168`·`170`·`172`·`173`·`183`·`184`). 링크만 공란이라 **forward 가 `[DOMAIN-010]` 뿐인 고립** → auto_fixable |
| D010-LINK-004 | `DFEAT-050` 이 구현체로 지목한 `API-132`~`135` 의 `implements_features` 공란 → auto_fixable(FEAT-009) |
| **D010-LINK-005** | **활성 DFEAT 8건 중 7건에 `specializes_feature` 부재** → FEAT↔DFEAT 계보 단절 + **API 의 `implements_features` 도 도출 불가**(DFEAT-052 소유 `API-024`~`031` 8건이 그 때문에 공란) |
| D010-LINK-006 | `SEQ-008`·`SEQ-009` 가 본문에서 엔드포인트를 호출하는데 `invokes_apis` 공란(SEQ-009→API-034/035/036/182) → auto_fixable |
| D010-LINK-007 | 화면 4건 전건 `realizes_use_cases=[]` 인데 **UC 쪽 `related_screens` 는 채워진 단방향 비대칭**(`SCREEN-005` backward 에 UC 7건) → auto_fixable |
| D010-LINK-008 | **`SCREEN-010` 은 UC 축 완전 고립** — `realizes_use_cases=[]` + 어떤 UC 도 참조 안 함 + **`route` 가 빈 문자열** |
| D010-LINK-009 | `DFEAT-017` 이 본문에 *"(테이블 `LS_DATA_LBL`)"* 를 명시했는데 `persists_in_tables` 공란 → auto_fixable(⚠ `LS_LABEL_VERSION` 은 본문이 **명시적으로 배제**하므로 제외) |
| D010-LINK-010 | **ERD 테이블 6종에 소유 DFEAT 없음** — `LS_DATA_LBL_ATTR_VAL`·`LS_DATA_META`·`LS_DATA_META_REVIEW`·`LS_LABEL_PRESET`·`LS_LABEL_PRESET_CODE`·`LS_LABEL_VERSION`. 뒤 3종은 **책임질 DFEAT 자체가 없다** |
| **D010-LINK-011** | `UC-021`·`UC-032` 에 **`belongs_to_domain` 링크 자체가 없다** — `UC-021` 은 도메인 DFEAT 2건을 realizes 하는데도. **그 결과 DOMAIN-010 귀속 UC 가 3건으로만 집계돼 도메인 UC 인벤토리가 왜곡**된다 |
| **D010-LINK-012** | ★★**`code_module` 4건이 틀린 SD 를 인용** — `MOD-028`·`031`·`032`·`033` 이 SCREEN-026 의 디자인을 **`SD-007`** 로 적는데 **실제는 `SD-006`** 이고 **`SD-007` 은 `SCREEN-030`(게시판, 타 도메인)** 이다. **D009 와 동일 패턴의 재현** → auto_fixable |

### P2 2건
- **D010-LINK-013** deprecated `API-033` 이 활성 `FEAT-002` 로 `implements` 링크 유지 → **FEAT-002 구현체 목록에 폐기 엔드포인트가 섞인다**. (역방향 잔재는 0 — `ERD-008`·`DFEAT-013`·`DFEAT-040`·`UC-025` 도 backward 0건 확인) → auto_fixable
- **D010-LINK-014** `SCREEN-005.consumes_apis` 39건 중 **11건이 어느 섹션에서도 참조 안 됨**(API-012·022·023·168·170·172·173·177·178·183·184). ⚠ **역방향(섹션엔 있는데 consumes 에 없음)은 0건이라 LINK-5 의 P1 조건 미해당.** `SCREEN-026` 도 1건(API-117). **`SCREEN-010`·`035` 는 정확히 일치**

### ★★ 도메인 간 반복 패턴 확정 — `code_module` 의 SD ID 오인용
| 도메인 | 건수 | 형태 |
|---|---|---|
| D009 | 4건 | SD 번호가 **한 칸씩 밀림**, `MOD-035`→`SD-009`(타 도메인 SCREEN-024) |
| **D010** | **4건** | `MOD-028`·`031`·`032`·`033`→**`SD-007`**(타 도메인 SCREEN-030) |
★**공통 구조**: **SD↔MOD forward 링크가 없어 본문 인용이 유일한 추적 수단인데 그것이 틀렸다.**

### links 차원 단서
- ★**`ERD-019.LS_LABEL_VERSION.SAVE_REASON_CD` code_values 가 `{BATCH, MANUAL, ROLLBACK}`** 인데 확정 정책은 **`SAVE_REASON='APPROVED'`** 이고 **`ROLLBACK` 은 폐기**됐다. 같은 테이블 `ACTVTN_YN` description 도 *"롤백 시 대상 스냅샷을 **새 active 로 복원**"* 이라 **"행 재활성" 정책과 어긋난다** → **schema/stale 소관, 강한 단서**.
- `CMP-004`·`CMP-006`·`CDIAG-015` 의 `depicts_dfeats` **전부 공란**(`CDIAG-004` 만 5건 보유).
- `MOD-006` 만 `primary=true` 이고 **`MOD-007`·`MOD-008` 은 primary 도메인이 없다**.
- `DFEAT-051.brownfield.notes` 가 **로컬 조사 파일명(`gap-api.md`)과 작업 시점**을 담고 있다 → content 오염 후보.
- stale=true 활성 ITEM: `DFEAT-050`·`API-182`·`API-197`·`SEQ-009`·`CDIAG-015`·`MOD-006/007/008`·UC 5건. **다수가 *"FEAT-002의 4개 필드 변경"* 을 사유로 든다.**

### links 미확인 층
`links.unresolved` **측정 불가**(대리 측정 범위에선 미해결 참조 0 — SCREEN-005 39/39 · DFEAT-050 4/4 · DFEAT-052 8/8 · SCREEN-010 3/3 · SCREEN-026 6/6 · SCREEN-035 8/8) ·
**`sections[]` 본문 전문 미열람**(섹션 텍스트 안의 ID 인용 미검사 — LINK-5·LINK-9 의 sections 축 사각) ·
`screen_spec` 에 `references_features`/`references_dfeats` 가 **스키마상 존재하는지 미확인**(키 부재인지 스키마 부재인지 구분 못 함) ·
SEQ 12건 본문 미확인 · **`ERD-010.tables[]` 전문 미열람**(relationships 로만 테이블 식별 → **LINK-010 은 과소 집계일 수 있다**) ·
정적 렌더 미러 미확인 · `TEST-002`/`TEST-005` 필드 미열람 · ROLE granted_on 교집합 미대조(policy 소관).

---

## stale (14건 — P0 3 / P1 8 / P2 3)

> ★ 도메인 78~81 ITEM 전수 스캔. **stale 총 13건 = 입력 목록 10 + 신규 발견 3**(`MOD-006`·`MOD-007`·`MOD-008`).

### P0 3건 — 전부 **"본문은 정정됐는데 부속 배열·응답 계약이 미반영"**

**D010-STL-001 (P0) — `API-036`(롤백) 의 `responses.200` 이 폐기 모델 그대로**
같은 ITEM 의 description 은 **현행**: *"대상 스냅샷을 다시 활성 상태로 되돌린다. **새 버전 행을 쌓지 않는다**"*
그런데 응답 계약은 **정반대**: *"롤백 성공. … **새 active 버전이 생성된다**(LS_LABEL_VERSION 기록). data 에 새 버전 정보"* / `data.description` = *"롤백으로 **생성된 새 active 버전**"* / `versionHash` = *"**새 버전** 스냅샷 해시"*
부수 잔재: 400 이 *"입력값 검증 실패(**srcSn 누락** 등)"* 인데 **path 에 그 파라미터가 없다**. `stale=false`·2026-08-12 갱신.

**D010-STL-002 (P0) — `ERD-019.SAVE_REASON_CD.code_values` 에 현행 유일 정상값이 없다**
`{BATCH, MANUAL, ROLLBACK}` — **`APPROVED` 가 아예 없고** 담긴 둘은 폐기값이다.
현행(`CDIAG-015.SaveReason`): `["APPROVED","BATCH","DEIDENT_REPORT"]` + *"**ROLLBACK 은 폐기** — 롤백이 새 버전을 적층하지 않기 때문"*.
`MANUAL`(=라벨 저장 시 버전 생성)은 `UC-007` 이 **명시적으로 폐기**: *"라벨 저장 시점에는 버전을 생성하지 않으며"*.

**D010-STL-003 (P0) — `CDIAG-004` 가 자기 description 에서 폐기 선언한 값을 `classes[]` 에 유지**
description: *"[★롤백 시맨틱 정정 — SaveReason='ROLLBACK' **폐기**] 구 서술은 … 두 값이 있다고 적었으나 ROLLBACK 코드는 폐기됐다"*
`classes[].enum_values`: **`["APPROVED", "ROLLBACK"]`** + *"ROLLBACK=과거 검수완료 버전 복구"*
→ **전형적 "본문 정정, 부속 배열 미반영"이고 `stale=false` 라 플래그로 안 잡힌다.**

### P1 8건

| ID | 요지 |
|---|---|
| D010-STL-004 | `API-035` 가 폐기된 **구 3축**을 서술(*"MODIFIED=양쪽 id 존재+**shape/label 차이**"*). ★**비교기를 공유하는 자매 `API-182` 는 이미 *"비교축 차이"* 로 정정**돼 있다 |
| **D010-STL-005** | 2026-08-10~11 확정으로 **폐기된 "이력 패널 + 2건 체크 + 즉시 되돌리기" 화면 동선이 API 3건에 잔존**. `UC-008`: *"구 동작(버전 2건을 골라 … 즉시 되돌리기)은 화면에서 **폐기**됐다 … **그 엔드포인트는 화면 동선이 없다**"*. `SCREEN-010`: *"**저장 단위 변경 이력은 이 화면에서 다루지 않는다**"* |
| D010-STL-006 | `UC-032` 가 옛 route `/manage/preset` 보유 — SCREEN-026 이 **2026-08-14 에 `/manage/presets` 로 확정**. ★**전 도메인 grep 결과 옛 route 잔존은 이 1건뿐** |
| **D010-STL-007** | `UC-021.alternate_flows["APPROVED 영상 수정"]` 이 **재검수 게이트 없이 즉시 재생성·통지**하는 2026-07-27 폐기 모델. **같은 ITEM 의 description 은 정정본**(*"재검수 대상이 되고, 검수자가 다시 승인한 시점에 통지"*) |
| D010-STL-008 | `DFEAT-017` 이 저장 시맨틱을 **`upsert`** 로 표기 — `ADR-033` 이 *"**부분 upsert 아님**"* 으로 폐기. 형제 `UC-021`·`DOMAIN-010`·`CMP-004` 는 전부 **full-replace** 로 정정됨 |
| D010-STL-009 | `ERD-010.description` 은 *"AI 메타를 담던 **별도 테이블은 두지 않는다**"* 로 정정됐는데 **같은 ITEM `brownfield.notes` 는 *"AI_INFO … 신규"*** 유지(실제 `tables[]` 에 AI_INFO 없음) |
| D010-STL-010 | `DFEAT-052` 가 **존재하지 않는 식별자 `SC-036`** 인용(2곳). 실제는 `SCREEN-035` 이며 **`sub_id` 가 null 이라 별칭도 아니다**. `UC-028` 은 정정본 보유 |
| D010-STL-011 | `modified` 인데 `legacy_source` 부재 **6건**(API-034/035/036·MOD-006/007·UC-021). ★**같은 도메인의 UC-032·DFEAT-012~016·ERD-008/010/019 는 갖고 있어** 이탈이 6건에 한정. `MOD-006` 은 `decided_by` 도 공란 |

### P2 3건
- **D010-STL-012** stale=true 11건 **본문 전수 열람 결과 잔재 0** → status-only 재확인만 필요(경과 4~7일, 30일 미만). ★**`MOD-006/007/008` 3건은 입력 목록에 없던 신규 발견**
- **D010-STL-013** ★**`implemented`/100 인데 `modules`·`records` 공란이 43건**(대표 14건만 열거). ⚠**반대로 `DFEAT-050`·`API-197` 은 실제 구현이 있는데 `planned`/0 이라 양방향으로 어긋난다**
- **D010-STL-014** `ERD-010`·`ERD-019` 가 **2026-08-13 스쿼시로 소멸한 마이그레이션 번호**를 진실원으로 인용(V4·V23·V53·V116~V119 등 — 지금 `db/migration` 엔 없고 `test/resources/db-archive` 로 이관됨)

### ★★ 방법론 발견 — **deprecated ITEM 은 배치 export 기반 검사에서 구조적으로 빠진다**
`API-033`·`DFEAT-013`·`ERD-008` 이 REST `kit-export` 에서 제외돼 **개별 `get_item` 으로만 확인**됐다.
⇒ **`kit-export` 로 모집단을 만드는 검사(content 의 오염·한글 스캔 포함)는 deprecated ITEM 을 못 본다.** D009 content 도 같은 이유로 `ERD-006` 을 모집단에서 제외했다.
**최종 리포트에 "검사 사각"으로 명시할 것.**

### ★ stale 이 grep 으로 확인한 "잔재 0건" (재조사 불필요)
**superseded ADR(005/011/016/017/028) 인용 0건** · `LS_DATA_LBL_AI_INFO` 0 · `LS_TASK_ASSIGN_HISTORY` 0 · **자석/올가미 0** · `LABEL_CLASS_DEFS` 0 · *"Export 범위 외"* 0 · change_summary 기본값 0.
`API-018`·`API-022` 가 `ERD-008`(deprecated)을 인용하는 것은 **1차 provenance 라 STL-2 대상 아님**.

### stale 미확인 층
1차 소스 grep OFF(STL-011 을 `auto_fixable=false` 로 남긴 이유) · **`DFEAT-040` 미열람**(deprecated 추정, export 제외) ·
★**정적 렌더 미러 미확인 — 이 도메인에서 stale 이 가장 남기 쉬운 층**(SCREEN-005 v64·SCREEN-026 v21·SCREEN-035 v15·SCREEN-010 v34 의 게시 렌더가 **2026-08-14 route 변경과 로드 버전 선택 모달 반영본인지 미판정**) ·
**로컬 키트 last sync 2026-08-11 < SCREEN-005 v64(08-14)** 라 낡았을 가능성 ·
위키·테스트케이스 층 미확인(UC-008 의 08-11 화면 동선 반전 반영 여부).

---

## schema (13건 — P0 5 / P1 7 / P2 1)

> ★ **기계 대조 결과 컬럼 집합 자체는 완전 일치** — ERD-010/019 의 12개 테이블 컬럼이 `V1__baseline.sql`+`V6` 와 **전량 일치(ERD에만/DB에만 = 0)**, **가공 컬럼·가공 테이블 인용 0건**.
> **어긋난 축은 제약·FK·code_values·nullability·누락 테이블 1종**이다.

### P0 5건

**D010-SCH-001 (P0) — ERD 가 공표한 UNIQUE 는 없고, 실재하는 UNIQUE 는 미기재 (두 방향 모두 반대)**
ERD: `UK_LS_LABEL_NAME (LBL_NM)` 전역 UNIQUE + `LBL_NM.unique=true`
실물: **`uk_ls_label_nm_ci ON (lower(TRIM(lbl_nm))) WHERE use_yn='Y'`** — `uk_ls_label_name` 은 **DDL grep 0건**.
엔티티 javadoc: *"all-rows exact 제약(UK_LS_LABEL_NAME)은 **V121 에서 제거**됨 — soft-delete 된 이름은 재사용 허용"*
⇒ **ERD 대로 구현하면 ①허용된 동작(soft-delete 후 이름 재사용)을 잘못 거부하고 ②실제로 차단되는 값(대소문자·공백만 다른 중복)을 통과시킨다.**

**D010-SCH-002 (P0) — `LS_LABEL_VERSION.VER_NO` 의 nullability·의미가 폐기 사양**
ERD: `NOT NULL` + *"버전 일련 번호(증가)"*
실물: `ver_no integer`(**NULL 허용**) + COMMENT *"**영상 단위 산출 버전 번호** — `LS_DATASET_EXPORT.OUTPUT_VER_NO` 와 같은 번호 … NULL=알 수 없음. **구 의미(프레임별 승인 순번)는 폐기**(V181 에서 기존 값 전량 무효화)"*
같은 도메인 `API-197` 은 이미 새 의미로 규정 → **같은 컬럼에 대해 도메인 내부에서 정의가 갈린다.**

**D010-SCH-003 (P0) — 버전관리 판정의 단일 원천 테이블이 ERD 에 통째로 없다**
**`LS_OUTPUT_VER_SNPSH`**(V183) — 실물 TABLE COMMENT: *"「시작 버전 선택」(R6)이 되돌릴 스냅샷을 고르는 **판정의 단일 원천**이다. `LS_LABEL_VERSION.VER_NO` 는 조회·표시용으로 남되 **판정 원천이 아니다**"*
`LS_LABEL_VERSION` 을 FK(CASCADE)로 참조하고 구현 5개 파일이 실재하는데 **ERD-019 에도 D9 설계서에도 0건**(schema.sql 엔 23건).

**D010-SCH-004 (P0) — `SAVE_REASON_CD.code_values` 가 실재하지 않는 값만 열거**
`{BATCH, MANUAL, ROLLBACK}` — **실제 유일 신규 적재값 `APPROVED` 누락**. `MANUAL` 은 *"MANUAL 자동 커밋 폐기"*, `ROLLBACK` 은 *"D-ISSUE-21 — 폐기했다(도달 불가 분기였음)"*. (stale D010-STL-002 와 동일 사안, **두 차원 독립 검출**)

**D010-SCH-005 (P0) — `API-196` 계약대로 보내면 전건 400**
`edits[].items` 가 **required 아님**인데 구현은 `@NotNull("items 는 필수입니다")` → **계약이 정상 동선으로 규정한 `{"srcSn":501,"dscdYn":"Y"}`(폐기만 하는 편집)가 400 으로 거부**된다.
`edits[].items[].label` **필드 자체가 없음**인데 구현은 `@NotBlank` → 계약대로 보내면 전건 400. **자매 `API-019` 는 required 에 `label` 포함**돼 있어 API-196 만 어긋난다.
추가: `example` 의 `points` 가 **평탄 배열**(`[120,80,260,400]`)인데 구현은 `List<List<Double>>` 이고 **같은 ITEM 의 `change_summary` 가 *"평탄 배열이 아니라 중첩 배열이다"* 라고 적어 자기모순**.

### P1 7건

| ID | 요지 |
|---|---|
| **D010-SCH-006** | **쓰기 엔드포인트 9건에 `request_body` 가 통째로 없다**(API-023·025·026·029·030·032·036·039·067) — 전부 `@Valid @RequestBody` 필수. ★**비대칭 증거**: 같은 리소스의 POST(`API-038`)는 있는데 **PUT(`API-039`)만 없다**. API-026/029/030 의 change_summary 는 *"응답 스키마 백필"* 만 기록 → **요청 축이 한 번도 채워진 적 없다**. ⚠**대조군 `API-041`(clone)은 구현도 본문을 안 받아 부재가 정상** |
| D010-SCH-007 | `API-196` 의 `trackId`=**integer**(ERD `varchar(30)`·구현 `String` + 패턴) / `lblTypeCd` enum **2값**(ERD 4값·구현 5값) / `id`=**string**(구현 `Long`·자매 API-019 는 int64). ★**같은 스키마 안의 `points.description` 이 *"SKELETON 은 [[x,y,v]]×17"* 이라 자기 enum 과도 모순** |
| D010-SCH-008 | 두 ERD 의 `LBL_TYPE_CD` code_values 에 **`SKELETON`(COCO-17) 누락** — 구현·계약엔 있다. 컬럼이 `varchar(16)` 이라 값은 들어가므로 **순수 문서 결손** |
| D010-SCH-009 | 실재 UNIQUE·인덱스 2건 미기재(`uq_ls_data_meta_review_meta_type` = **upsert 키**, `idx_ls_label_preset_code_label`) + **`LS_EVNT_ANNO.RAW_SN` 의 `on_delete` 가 실물과 정반대**(ERD `no_action` / 실물 **CASCADE**) + 실재 FK 6건 references 메타 없음 + **선언한 관계에 FK 가 없음** 1건 |
| D010-SCH-010 | **실재하지 않는 물리명 3종을 7개 지점에서 인용**(`LABEL_ID`·`LABEL_NM`·`LABEL_PAYLOAD` → 실제 `LBL_*`). ★**그중 하나가 조인 축을 정하는 구조 필드 `relationships[].via_column`** |
| D010-SCH-011 | `API-067` 응답이 **3목록 중 `items` 만** 선언 — **같은 ITEM description 이 *"readOnlyMeta·technicalMeta 목록의 키는 이 엔드포인트로 수정할 수 없다"* 로 두 목록의 존재를 전제**해 자기모순. `API-066` 은 3목록 다 있어 **같은 반환 타입인데 갈린다**. 둘 다 `dataMetaReviewSn`·`reviewStatus` 누락 |
| D010-SCH-012 | `DFEAT-017`·`012`·`051` 의 `persists_in_tables` 공란이라 **SCH-1 대조 자체가 불성립**. ⚠ 단 **가공 테이블 인용은 8건 전체에서 0건** |

### P2 1건
- **D010-SCH-013** 표준용어 드리프트 — `VER_NO`(행안부 도메인 **번호V50** vs 실물 integer) · `CHG_DTL_CN`(**내용V4000** vs text) · `DEAD_LETTER_AT`(**DEAD·LETTER·AT 모두 표준단어 0건**, 일시 표준약어는 `DT`) · `VERSION_HASH`/`LBL_VERSION_SN`(**VERSION 은 단어 0건, 표준은 `VER`**).
  ★**실물 DB COMMENT 는 그 드리프트를 인정한다** — *"컬럼명은 표준 약어 VER 을 쓴다 — 참조 대상의 VERSION 표기는 **선존 드리프트**이며 미러하지 않는다"*. **ERD-019 에는 그 표기가 없다.**

### ★★ 핵심 관찰 — `ERD-019` 는 **V180·V181·V183 이후 한 번도 갱신되지 않았다**
`current_version 17` / `last_updated 2026-08-07` 인데 VER_NO 의미 재정의(V180)·값 무효화(V181)·`LS_OUTPUT_VER_SNPSH` 신설(V183, 08-11)이 **그 뒤에 일어났다**.
**그런데 서버 `stale` 플래그는 `false`** — **플래그만 믿으면 P0 3건을 통째로 놓친다.**

### ★ CSV 정본 grep 실적
6종 로드(공통 3,284/123/13,176 · 사업 471/45/1,373). **대조 컬럼 55개** + 단어 축 14개.
★**출처 컬럼 확인** — `LBL_PAYLOAD`·`VERSION_HASH`·`POINT_CN` 등 **27개 용어가 출처 `KLID-저작도구 ERD-010/019` 인 자기 등록 항목이라 독립 근거로 쓰지 않았다.**
★**단어 축 단독 미등록 판정 금지 준수** — `ADD_CNT`·`MDFCN_CNT`·`DEL_CNT`·`DTCT_TYPE_CD`·`ANNO_CN`·`EVNT_ANNO_SN` 은 용어 등록이 없으나 **전 세그먼트가 표준단어라 위반 미보고**.
**정상 확인**: `RTRY_NMTM`·`SORT_SEQ`·`USE_YN`·`ACTVTN_YN`·`DSCD_YN`·`RJCT_RSN`·`ATRB_NM`·`LBL_NM`·`MDL_NM`·`RVW_SN` 물리명·도메인 모두 일치.

### schema 미확인 층
1차 소스 grep OFF(**SCH-7 검증 불가** — 단 `v2_*` 접두 인용 0건이라 위반 징후 없음) ·
**SCH-5 미수행**(SEQ 도메인 귀속 확정 불가 — 0건을 "없다"로 단정 안 함) · **SCH-9 미수행**(SCREEN sections 본문 미열람) ·
**API 전수 대조 아님** — 쓰기 14건 + 조회 2건 **표본** 대조(API-018·021·022·024·027·028·031·034·035·040·125~129·132~135·175·176·182·195 응답 스키마 미대조) ·
`LS_DATA_SRC`(`DSCD_YN`·`LBL_VER` 보유, API-019/196 이 인용)는 **`ERD-012` 소속이라 범위 밖** · 정적 렌더 미러 미확인.

---

## policy (9건 — P0 5 / P1 3 / P2 1)

### P0 5건

| ID | 요지 |
|---|---|
| **D010-POL-001** | `SAVE_REASON_CD.code_values` 에 **폐기 `ROLLBACK` + 근거 없는 `MANUAL`**, 확정 유일 생성 사유 **`APPROVED` 누락**. ★`MANUAL` 은 ADR-009·CDIAG-015·UC-007 **어디에도 근거가 없고** 폐기된 수동 커밋 경로(`API-033`, deprecated)의 잔재로 보인다 |
| **D010-POL-002** | `LS_LABEL_VERSION` 테이블 description 이 *"**라벨 저장 시** … 스냅샷을 직접 보관"* — 확정은 **검수 승인 시점에만**. `VERSION_HASH` 도 *"멱등 **재커밋**"* 이라는 폐기 경로 어휘 유지 |
| **D010-POL-004** | `API-036` **ITEM 내부 자기모순** — description 은 *"새 버전 행을 쌓지 않는다"* 인데 `responses.200` **3곳이 *"새 active 버전이 생성된다"***. ★**응답 계약만 보고 구현하면 새 행 INSERT → `(DATA_SRC_SN, VERSION_HASH)` UNIQUE 위반.** 확정 정책의 **멱등 no-op 도 계약에 전혀 없다** |
| **D010-POL-005** | `API-021.description` 이 **`PORTAL_USER` 접근을 명시**하는데 `security` 는 REVIEWER/WORKER 뿐. ★**같은 리비전의 `change_summary` 가 그 제거를 선언**했다 — *"포털 회원이 호출할 수 있다고 적으면 **구현할 수 없는 계약**이 된다"*. 형제 `API-022` 는 본문까지 지웠다 |
| **D010-POL-006** | ★**라벨링 캔버스 이미지 계약에 비식별 정책이 통째로 없다** — description 전문이 *"라벨링 캔버스에 표시할 프레임 이미지를 반환한다"* 한 줄이고 `parameters` 에 **`raw` 도 없다**. **기본 비식별본 / `raw=true` 는 REVIEWER 한정 / WORKER raw 무시 / 404 폴백** 어느 것도 없어 **정의서대로 구현하면 원본이 서빙된다** |

### P1 3건 / P2 1건
- **D010-POL-003** `ACTVTN_YN` = *"롤백 시 대상 스냅샷을 **새 active 로 복원**"* — *"새 active"* 는 **새 행 생성으로 읽혀 UNIQUE 위반 구현을 유발**
- **D010-POL-007** 프리셋 3건(`SCREEN-026`·`API-037`·`API-038`)이 `status=preserved` + **`decided_by` 미지정**인데 **`ADR-034` 가 저장모델·요청 계약을 반전**시켰다(같은 `API-038` 의 change_summary 가 *"코드 문자열이 아니라 마스터 PK(labelId)로 지정"* 이라 선언). ★**같은 도메인 `SCREEN-035` 는 ADR-034 를 정상 보유**해 프리셋 3건만 이탈
- **D010-POL-008** `DFEAT-015` 가 우측 패널을 **4탭(이력 탭 포함)** 으로 서술 — **같은 ITEM 의 title 은 이미 3탭**이고 `SCREEN-005` v64 는 *"'객체/메타/이슈' 3탭"* 확정
- **D010-POL-009 (P2)** `DFEAT-051.brownfield.notes` 에 **금지 3종이 한 문장에** — 로컬 파일명 `gap-api.md` + *"이 작업 시점"* + *"미등록 — 별도 등록 작업 대상"*

### ★★★ 정책 버전 역전 — **제가 주입한 전제가 ITEM 보다 낡았다** (auditor 가 gap 으로 올리지 않고 보고)
제가 domain_context 에 넣은 **「1건 클릭 = 작업본 비교 / 2건 체크 = 두 버전 비교」는 2026-08-05 확정**인데,
**ITEM 은 그보다 새로운 2026-08-11 사용자 확정을 반영해 그 화면 동선을 폐기**했다:
- `UC-008` v8: *"★되돌리기는 고른 버전을 **불러온 뒤 저장으로 확정**한다 (**2026-08-11 사용자 확정**)"* + *"구 동작(버전 2건을 골라 … 즉시 되돌리기)은 화면에서 폐기됐다"*
- `SCREEN-010` v34(08-14): *"두 버전을 골라 서로 비교하는 방식은 **두지 않는다**"*
- `SCREEN-005` v64: *"어느 버전에서 편집을 시작할지 고르는 **모달을 먼저 띄운다**"*

⇒ **ITEM 쪽이 최신이므로 되돌리면 안 되며, 뒤처진 것은 프로젝트 `CLAUDE.md` 의 구속 정책 서술 쪽일 가능성이 높다.**
⚠ 단 **백엔드 축은 그대로 유효**하다 — 롤백 시맨틱·비교축 R7·손상 스냅샷 400·기존 `/diff` 장애격리는 `CDIAG-015`·`CMP-006`·`API-035`·`API-182` 에 **정확히 반영**돼 있다.
**이것은 D007 의 `ADR-005`→`ADR-020` 건에 이은 두 번째 "주입 전제 오류"다. 최종 리포트에 별도 섹션으로 올린다.**

### ★ policy 가 명시한 "정합 양호" (교차 입증)
`CDIAG-015`·`CMP-006`·`SCREEN-010`·`API-033`(deprecated)·`API-034`·`API-035`·`API-182`·`API-196`·`DFEAT-017`·`DFEAT-052`·`ERD-010`·`ERD-008` **어긋남 0건**.
★**특히 `CDIAG-015`/`CMP-006` 이 롤백 시맨틱·R7·SAVE_REASON 코드셋을 정확히 담고 있어, `ERD-019` 와 `API-036` 이 같은 결정의 미갱신 사본임을 교차 입증**한다.

### ★ POL-2~POL-7 미적용 — **ADR 번호를 실제로 조회해 확인**
`ADR-045`=증강 검수축 분리(BFF 아님) · **`ADR-051`=미존재**(총 45건, 최대 ADR-045) · `ADR-028`=superseded 증강 dedup(지자체 scope 아님) · `ADR-027`=재비식별 강제 재생성(x-access-token 아님) · `ADR-036`=event_annotation 메타탭(중계서버 방향 아님) · `ADR-038`=목록 정렬·필터(EV99999999 아님).
적용한 룰은 **POL-1·POL-8·POL-9 + 주입된 확정 정책**뿐.

### policy 차원 단서
- `API-036.responses` 의 `lblHstrySn` 이 **`LS_LABEL_VERSION.LABEL_VERSION_SN`** 을 인용(실 컬럼 `LBL_VERSION_SN`) + **필드명이 "라벨 이력 SN" 인데 버전 PK 를 담아 의미가 어긋남** → schema.
- ★**`API-036` 은 description(정확)과 responses(폐기)가 갈린 전형적 merge 잔재** — **같은 도메인의 다른 api_endpoint responses 도 같은 방식으로 낡았을 가능성이 높으니 responses 축 전수 스캔 권장**.
- ★**links 결과 보강**: `UC-007`·`UC-008` 의 `FEAT-005` 오지목은 **링크만 잘못됐고 본문은 어긋나지 않는다**(description·main_flow·decided_by=ADR-009·diff_summary 전부 버전 축, 비식별 서술 0줄). ⇒ **"링크만 고치면 되는 건"이지 반쪽 정합이 아니다.**
- `DFEAT-050` 의 `decided_by=ADR-020` 인데 notes 는 승격 근거를 `ADR-036` 이라 적어 **축이 갈린다**(둘 다 활성이라 위반은 아님).
- `DFEAT-012/014/015/016` 은 **2026-05-30 이후 무갱신**(v2)이라 이후 확정된 **ADR-035(VOS)·ADR-040(AI 추적 출력형태)·COCO-17·ADR-034(색상 마스터 단일화)가 하나도 반영 안 됨**. 4건 전부 `decided_by` 미보유.

### policy 미확인 층 (auditor 가 "정합 완료가 아니다" 라고 명시)
**7개 층 중 2개만 확인**(①ITEM 본문 ③링크). **미확인: ②정적 렌더 미러(`list_static_renders` 0회 호출)·④로컬 키트·⑤위키·⑥테스트케이스·⑦코드.**
`SCREEN-005` **부분 read 만** — **①색상 두 축 분리 유지 ②프레임 폐기 400 서술 ③히스토리 탭 잔존 ④하드코딩 색상표 잔재 전부 미판정**.
API `responses` 전문은 **`API-036` 만** 열람 — 나머지 412 게이트 실재는 `change_summary` 인용 의존(**`API-019`·`035`·`182`·`133~135` 미확인**).
superseded ADR 인용 0건은 **`decided_by` 구조 필드 기준** — **본문 free-text 는 전수 grep 안 함**.

---

## acceptance (8건 — P1 3 / P2 5)

> ★ 도메인 귀속 AC = **7건**(2축 판정). (a) `derived_domain` 링크 5건: `AC-017`·`020`·`021`·`023`·`024` (b) UC 역추적 2건: `UC-007`→`AC-007`, `UC-008`→`AC-008` — **뒤 둘은 `derived_domain_ids` 공란이라 (a)로는 안 잡힌다**.
> ⚠ `AC-004`·`005`·`006` 은 라벨링처럼 보이나 **`derived_domain_ids=["DOMAIN-004"]`** 로 AI 보조 라벨링 소속 — 제외.

### ★★ 확정 정책 10종 × AC 커버리지 매트릭스 (이 감사의 핵심 산출물)

| # | 정책 | AC 커버 | 판정 |
|---|---|---|---|
| 1 | 2계층 분리 — **full-replace 실삭제** / 버전은 승인 시점만 | **후반부만** `AC-007` | ⚠ **full-replace 실삭제는 어느 AC 에도 없다** |
| 2 | diff 1건=작업본 / **R7 6축** / AI메타·color 제외 | `AC-008` **전부** | ✅ |
| 3 | 작업본 payload 동일 방식 생성 + **1MB 폴리곤 단순화 양쪽 재현** | `AC-008.then②` **명시** | ✅ |
| 4 | 손상 400 + **`/diff` 장애격리 유지(의도된 차이)** | `AC-008.and_examples` **두 축 함께 명시** | ✅ |
| 5 | 롤백 **행 재활성**·`LBL_SN`/AI메타/`TRCK_ID` 보존·**멱등 no-op** | **0건** | → ACC-008 |
| 6 | 프리셋 `LBL_ID` join / **`DTCT_TYPE_CD` 축** / 미매핑 선택불가 | **0건** | → ACC-001·002 |
| 7 | **색상 단일 진실원 + `labelId` 가 실체** | **0건** | → ACC-005 |
| 8 | 신고 구간 **412** | **버전 경로만** `AC-008` | ⚠ **라벨 조회·저장·속성값 축 미검증** |
| 9 | 승인 이력 프레임 폐기 **400** | **0건**(회차 적용 축만 `AC-008.then⑦`) | → ACC-005 |
| 10 | 캔버스 **비식별 프레임 서빙** | **0건** | → ACC-005 |

★**정책 7 에 대한 auditor 의 지적이 특히 날카롭다** — *"이 정책은 '**저장 전에는 정상으로 보인다**'는 성질 때문에 `manual_test` 로는 잡히지 않아 **automated AC 가 특히 필요**하다."*

### P1 3건
- **D010-ACC-001** `UC-028`(should) `covered_by_acceptances=[]` — 마스터 실시간 join·`DTCT_TYPE_CD` 게이팅·**미연결 표시**·WORKER 403 전부 미검증
- **D010-ACC-002** `UC-032`(should) `covered_by_acceptances=[]` — 프리셋 CRUD·**409(이벤트 1:1·이름 중복)**·**미연결 자동 제외**(*"오류로 상승시키지 않는다"*) 미검증
- **D010-ACC-003** **활성 DFEAT 8건 중 6건이 AC 도달 0**(`DFEAT-015` 는 **`must`**). ★**`DFEAT-050` 이 `AC-024` 로 덮인다고 볼 수 없다** — AC-024 는 `LS_DATA_META_REVIEW` 축인데 DFEAT-050 은 *"`LS_DATA_META_REVIEW` 와 **자매 관계의 독립 검수 워크플로우**"* 라고 **두 축을 명시적으로 분리**한다

### P2 5건
- **D010-ACC-004** `UC-021`(**must**)의 AC 5건이 **전부 happy** — `alternate_flows` 4종(**403 IDOR**·재검수 통지·**412 신고구간**·값 검증 실패) 어느 것도 검증 안 됨. ⚠ 신고 412 는 **버전 경로만** `AC-008` 이 덮고 **라벨 축(API-018/019/022/023)은 공백**. 타 도메인 `AC-019` 도 **신고 접수·해소만 다뤄 라벨 게이트를 덮지 않는다**
- **D010-ACC-005** AC 5건이 **given/when/then 각 1줄 요구 재진술** — `AC-017.then` 전문이 *"라벨이 LS_DATA_LBL에 영속되고…"* 라 **full-replace 로 실제 삭제되는지 부분 upsert 인지 구분 못 한다**. **5건 모두 `verification_method=manual_test`** 라 자동 회귀로 고정되지도 않음
- **D010-ACC-006** `AC-017.given` 이 **폐지된 관리 화면(TUS) 적재 모델** 전제(*"산불 영상이 관리 화면으로 적재되어"*) — 현행은 `AC-025` 의 관제 인입·폴링. ⚠ **dev 한정 잔존이라 완전 폐기 인용은 아니고, 어긋난 곳이 when/then 이 아니라 given 이라 P2**
- **D010-ACC-007** `AC-007`·`AC-008` 의 `derived_domain_ids` 공란 → **도메인 AC 인벤토리가 5건으로 과소 집계**. ★**역대조로 계산 축이 흔들린 게 드러난다** — `AC-017` 은 `UC-021`(belongs_to_domain **없음**)에서 파생인데도 `derived_domain_ids=["DOMAIN-010"]` 가 채워져 있다
- **D010-ACC-008** `UC-008` 이 *"★백엔드 롤백 시맨틱은 **그대로 유지된다**"* 고 명시했는데 **유일 AC 인 `AC-008` 이 그것을 명시적으로 제외**(change_summary: *"화면 동선이 없어 수용 기준에서 뺐다"*). ⚠ **제외 사유는 '화면 동선 부재'이지 '계약 검증 불필요'가 아니며, 보존 복원이 깨지면 diff 가 '전량 교체'로 오분류되는 비가역 축**

### acceptance 미확인 층
1차 소스 grep OFF · **ACC-003 전 룰 skip**(이 도메인 AC 가 verifies 로 잡은 REQ 5건은 전부 이미 AC 보유, 그 밖은 귀속 모호로 **억지 갭 금지** 준수) ·
확정 정책 1·7·9·10 은 **"검증 AC 부재"까지만** 판정(**구현이 그 정책을 지키는지는 범위 밖**).

---

## requirement (5건 — P1 1 / P2 4) · 검증 모드 **C** (SKIP 없음)

> **도메인 귀속 REQ = `REQ-009`(RQ-SFR-08-04 버전관리·변경이력) · `REQ-010`(RQ-SFR-08-05 비교·복구) 2건.**
> 귀속 근거: REQ 는 프로젝트 전역에 `belongs_to_domain` 필드가 **0건**이라 링크로 판정 — `FEAT-002 → implements → REQ-009/010` 이고 FEAT-002 의 실현 ITEM(API 8건·MOD-007·CDIAG-015)이 전부 D010 backward 에 있다.
> ⚠ **`REQ-006/007/008`(SFR-08-01/02/03)은 제외** — 셋 다 `FEAT-001`(AI 보조 라벨링)이 implements 하며 그건 **D004 축**이다.

- **D010-RQ-001 (P1)** ★**존재하지 않는 `DFEAT-069` 를 인용** — `REQ-009`·`REQ-010` 의 `constraints[0]` 이 동일 문자열 *"버전관리 도메인기능(**DFEAT-069**) 연계"*. `get_item` → **`E_NOT_FOUND`**. 도메인 활성 DFEAT 10건에 **버전관리 축이 한 건도 없어 재지목할 대상 자체가 부재**하다(deprecated `REQ-011` 에도 같은 phantom ID 복제)
- **D010-RQ-002 (P2)** **REQ 계층이 하류보다 낡았다** — `FEAT-002`(v8, **08-12**)가 *"과거 산출 회차를 불러온 뒤 저장으로 확정하는 흐름은 **버전 축을 바꾸지 않는다**"* 를 담았는데, `REQ-009`·`REQ-010`(**08-07**) 어디에도 *회차·불러오기·확정 저장* 어휘가 없다. 그 흐름의 API(`API-195`·`196`)는 **이 도메인 소속이고 FEAT-002 를 implements** 한다
- **D010-RQ-003 (P2)** ★**상하류가 서로를 설명하지 못한다** — `FEAT-003`(데이터마트 동기화 통지)이 `implements_requirements=[REQ-009, REQ-010]` 인데, **두 REQ 의 rationale 에 *통지·동기화·데이터마트·외부* 어휘가 0건**이다. divergence 는 **FEAT-003 에만** 기록돼 있다(*"RQ-SFR-08-06은 요구사항 범위 외(deprecated)로 처리됐으나 … 통지 기능 자체는 버전관리 흐름과 연계되어 운영 유지된다"*)
- **D010-RQ-004 (P2, advisory)** RFP-003 heading 3(데이터마트 연계)의 **실현 ITEM 9건이 전부 관제 연동 자산**이고 **D010 backward 92건에 0건 포함** — 이 도메인 책임이 아닌 것으로 보이나 그 비책임이 REQ 에 없다. ★**신규 REQ 를 만들지 말고 「책임 경계」 1줄만** 권고
- **D010-RQ-005 (P2)** `acceptance_criteria` 빈 배열 — **프로젝트 전역 현상, dedupe 대상**. 단 **별도 AC 5건이 derived_domain 으로 연결돼 있어 전무는 아니다**(버전관리·비교/복구 축 AC 는 미확인)

### ★ requirement 가 확인한 "위반 0건"
- **RQ-001 0건** — `REQ-006`~`011` 전건 `derived_from_rfp=[RFP-003]` 보유 + materialize 확인.
- **RQ-004 0건** — RFP-003 heading 1 → REQ-006/007/008, heading 2 → REQ-009/010 **전량 하향**. heading 3 은 D010 책임이 아니라 판단해 **RQ-004 가 아니라 RQ-006 advisory 로 분류**.
- **주입 전제 ①(SFR-08-01=VOS) 반영 완료** — `REQ-006`: *"…연속 영상 프레임에서 추적하여 … **단일 프레임 경계 그리기가 아니라 연속 영상 프레임 추적 기반이다**"*. `FEAT-001` title 도 VOS 로 개명. **자석 올가미 잔재 0**.
- **주입 전제 ②(버전 확정 3항) 전부 정합** — `REQ-009`: *"작업 중 임시저장(작업본)은 버전이 아니다"* / `REQ-010`: *"복구는 대상 스냅샷을 **다시 활성화**하는 방식이며 **새 버전을 적층하지 않는다**"* + *"**멱등 복구는 no-op**"*.
- **주입 전제 ③(Export 범위 외 폐기) 잔재 0건** — REQ 5건 전문에 *export·내보내기·범위 외* 0회.
- **비교축 R7 은 REQ 에 없지만 구 3축도 없어** *"이중 인용 없으면 stale 보고 금지"* 룰대로 **갭 미보고**(구현 세부 판정축으로 판단).

### ★★ 주입 전제 보강 1건 (auditor 지적)
제가 준 REQ 축은 `FEAT-002 → REQ-009·010` 만 명시했는데, **실측상 `FEAT-003`(데이터마트 동기화 통지)도 같은 두 REQ 를 implements** 한다.
⇒ **이 도메인 REQ 2건은 타 도메인 feature 의 상위이기도 하다** — 그것이 D010-RQ-003 의 근거다.

### ⚠ 메인이 추가 확인할 항목 (auditor 판정 범위 밖)
`REQ-010`(08-07)이 *"**2건 선택은 두 버전 간 비교다**"* 를 담고 있는데, **`SCREEN-010`(v34, 08-14)은 *"두 버전을 골라 서로 비교하는 방식은 **두지 않는다**"*** 고 한다.
auditor 는 "주입 전제 대비 역전 없음"으로 판정했으나 **그 비교 대상이 08-05 판 주입문이었다** — **`REQ-010` 자체가 08-11 결정 대비 stale 일 가능성**이 남는다.
⚠ 단 **API 축은 살아 있다**(`API-035 /diff?compareWith=` 활성) — **화면 동선만 폐기**된 것이라 REQ 서술이 어느 축을 말하는지에 따라 갈린다. **최종 리포트에서 사용자 확인 항목으로 올린다.**

### requirement 차원 단서
- ★**`DFEAT-069` phantom 은 `constraints` 의 자유 텍스트라 `links.unresolved` 로 안 잡힌다** — **링크 검사기의 사각**. links 차원이 텍스트 내 ID 인용까지 스캔하는지 확인 필요.
- **`RFP-003.related_requirements` 에 deprecated `REQ-011` 이 잔존**(`[REQ-006..REQ-011]`) — RFP 는 D010 소속이 아니라 미보고, **프로젝트 레벨 검토 요망**.
- `REQ-011`(SFR-08-06)은 deprecated + **backward 0건**이라 활성 설계에서 분리 완료.

---

## test_scenario (8건 — P0 1 / P1 6 / P2 1)

> 귀속 **3건** — `TEST-004`(covers `UC-007`) · `TEST-002`·`TEST-005`(exercises `SCREEN-005`). **12 steps 전문 열람.**

### D010-TST-001 (P0) — 도메인의 **정의적 must UC 인 `UC-021` 이 통합시험 0건**
전역 TEST 5건의 `covers_use_cases` 합집합 9개에 **`UC-021` 미등장**.
함께 미검증: **full-replace 실삭제**·SAM2 Track/분할·**트랙 편집(병합·분할·삭제)·선형보간**·프레임 폐기·복원 — **어떤 TEST steps 에도 없다**.

### P1 6건

| ID | 요지 |
|---|---|
| D010-TST-002 | `UC-008`(버전 비교·복구) **covers 0건**. `UC-007` 은 `TEST-004` 가 covers 하나 **steps 는 생성 축만**(seq2 하나) — **롤백·diff·회차 불러오기 step 이 없다**. 확정 동선(*"불러오기는 서버에 쓰지 않으며 저장을 눌러야 확정된다"*)·**멱등 no-op**·**R7 6축** 전부 미검증 |
| D010-TST-003 | `NFR-011`(**must**, 3초)·`NFR-018`·`NFR-019` 가 **`applies_to_domains` 에 DOMAIN-010 명시**인데 **kind=system TEST 전역 0건** + 3건 모두 `verified_by_acceptance=[]`. `measurement_method` 가 *"성능테스트로 3초 이하 확인"* 을 요구하는데 그 산출물이 없다 |
| **D010-TST-004** | ★`TEST-004` seq4 가 **"검수 완료 후 라벨 수정"을 `SCREEN-019` 에서 검증**하는데 **그 화면의 캔버스는 읽기 전용**이다(*"중앙의 **읽기 전용** 라벨 캔버스"*). 실제 수정 화면은 `SCREEN-005` |
| **D010-TST-005** | ★`TEST-004` 가 seq4(수정)→seq5(통지)로 **재검수·재승인 게이트를 건너뛴다** — **자기 objective 는 *"그 수정이 **재검수에서 승인될 때**"*** 라고 적었고 `SCREEN-019` 에 *"재검토 필요로 표시되며 … **재승인할 수 있다**"* 가 실재한다. **재승인 시 새 버전 스냅샷 적층 검증도 없다** |
| **D010-TST-006** | ★`TEST-004` 가 **폐기된 통지 페이로드**를 검증 — notes: *"각 항목은 **프레임 식별자와 변경 종류가 짝지어져** 있어야 한다"*, input: `【변경 프레임 목록】[5001] 【변경 종류 목록】[LABEL_UPDATED]`. **확정 규격은 `job_id`+`changed_items{images,jsons}`+`ver_expln` 3필드**이고 **`ChangeType` 은 관제로 전송되지 않는다** → **관제가 받지 않는 필드를 검증 대상으로 삼는다** |
| **D010-TST-007** | ★`TEST-005` 가 포털 흐름 3 step 전부를 `SCREEN-005` 로 검증하며 *"⚠ **포털 전용 화면 미확인** — 공용 SC-005 재사용"* 이라 적었으나 **`SCREEN-029`(포털 라벨링 화면, 활성 v31)가 실재**한다. ⇒ **그 오배선이 이 TEST 가 D010 으로 집계된 원인**이다 |

### P2 1건
- **D010-TST-008** ★**스냅샷 입도 상충** — `TEST-004` seq2: *"라벨이 있는 **프레임마다** 스냅샷이 1건씩(프레임 단위) … **영상 1건이 아니라** 라벨 보유 프레임 수만큼"* ↔ `UC-007`: *"**영상 단위** 라벨 전체 스냅샷"*.
  ⚠ **어느 쪽이 stale 인지 확정 불가라 P2** — **롤백 UNIQUE 키가 `(DATA_SRC_SN, VERSION_HASH)` 인 점은 프레임 입도를 지지**하고 UC-007 은 영상 입도를 말한다. **사용자 결정 사항.**

### ★ 폐기 모델 대조 10항 결과
**위반 0**: #2 SAVE_REASON 폐기값 인용 0 · #6 화면 동선(*"이력 탭"·"2건 체크"·"즉시 되돌리기" 인용 0*) · #10 `LS_DATA_LBL_AI_INFO` 0.
**미검증(위반 아님)**: #3 롤백·#4 R7·#5 full-replace·#9 비식별 서빙.
⚠ **#7 신고 412·#8 폐기 400 은 갭 처리 안 함** — **TEST 5건 전부 notes 에 *"정상 흐름(happy path)만 수록"* 을 명시한 의도적 스코프**(checklist 「의도적 상태는 gap 아님」).

### ★★★ 새로운 스코프 함정 — **NFR 은 `domain_id` 필터로 귀속을 판정하면 안 된다**
`list_items(type=nfr, domain_id=DOMAIN-010)` → **0건**. 실제 귀속은 **`applies_to_domains` 배열**로만 판정 가능하다.
⇒ **다른 도메인 auditor 도 같은 함정에 빠질 수 있다**(auditor 경고). **남은 도메인 프롬프트에 추가한다.**

### ★★ 타 도메인 이관 (완료 도메인 소급 포함)
1. **→ D003(완료)**: `TEST-001.exercises_screens=[SCREEN-007]` 인데 **`SCREEN-007` 은 `status=deprecated`("[폐기] 영상 목록 화면")** — **TST-004 급 P0** 인데 그 도메인 감사에서 안 잡혔다.
2. **→ D004/D005(완료)**: `TEST-002.steps[2]` 가 **`META_KEY='activity'`·`META_VL='보행 다수'`** 를 적재하고 검수큐에 넣는다. **확정 metaKey 는 `vlm.description`/`vlm.accuracy`/레거시 구간 키/`video.*`** 이고 **검수큐 진입은 `vlm.description` 1건만** → **폐기 모델 가능성 높음**.
3. **→ stale 재점검**: `TEST-004` 가 `stale=true` 인데 `change_summary` 는 *"바꿀 내용이 없어 **내용 변경 없이 확인만 기록**"* 이다. **실제로는 SCREEN-019 읽기 전용·재검토 표시와 어긋나 있어 그 "확인만" 처리가 오판이었다.** `TEST-002`·`TEST-005` 도 **동일 문구로 stale 해소** — **"무변경 확인 리비전"의 신뢰도를 재점검할 것.**

### test_scenario 미확인 층
1차 소스 grep OFF(**D010-TST-008 방향 미확정이 이 제약에서 나왔다**) · 정적 렌더 미러 미확인 ·
**`TEST-001`·`TEST-003` steps 미열람**(카탈로그 링크로만 배제 — *"두 건의 steps 안에 SCREEN-005·UC-007 이 본문 인용으로 숨어 있을 가능성은 배제하지 못했다"*) · `UC-028`·`UC-032` 본문 미열람.

---

## content (19건 — P0 1 / P1 10 / P2 8)

> 모집단 **86건**(kit-export 90 요청 → 86 반환) · 문자열 **7,049개** 추출 / 판정 대상 **6,250개**.
> ★**deprecated 4건이 `kit-export` 에서 구조적으로 제외됨을 실측 재확인**(2건 요청 시 count=1) — 개별 `get_item` 으로 **활성 오염 전이만** 점검.

### D010-CNT-001 (P0) — `API-034`·`035`·`036` 이 **존재하지 않는 '이력 패널' UI 동선**을 현행처럼 서술
`API-036`: *"이력 패널에서 되돌릴 버전을 고르고 확인 단계를 거치면 호출된다"* ↔ `UC-008`: *"**그 엔드포인트는 화면 동선이 없다**"*
`SCREEN-010`: *"두 버전을 골라 서로 비교하는 방식은 **두지 않는다**"* / `SCREEN-005.sections[5]` 는 **"로드 버전 선택 모달"**(4 컴포넌트, **롤백 확인 단계 없음**)
★**`SCREEN-010.consumes_apis=[API-197, API-182, API-195]` — 세 API 중 어느 것도 없다.**

### P1 10건 (요약)
`CNT-002` `DFEAT-015` **title 3탭 ↔ description 4탭**(이력 탭) — SCREEN-005 전문에 *'이력 탭'* **0건** ·
`CNT-003` `API-024`~`027` 이 라벨 마스터를 **'코드'로 오칭**(★`LS_LABEL` 에 **코드 컬럼이 없고** '코드'는 프리셋 축 용어) ·
`CNT-004` `UC-008.actor` 는 **REVIEWER 단독**인데 본문은 라벨링 화면 전 과정을 서술하고 그 화면들은 **REVIEWER·WORKER 양쪽** ·
`CNT-005` ★`SCREEN-005.sections[7]` 에 **FE 라이브러리 API·훅·캐시 상수 13곳**(`removeQueries`·`LABEL_KEYS`·`staleTime 30초`·`gcTime 5분`·`zod`) — **도메인 내 다른 ITEM 은 0건** ·
`CNT-006` **자기 리비전 서술 11곳 / 7 ITEM** + 구현 상태(*"메서드도 코드에서 제거됐다"*) + **finding 코드 `D-ISSUE-25`** ·
`CNT-007` ERD 2건이 *"진실원: `backend/src/main/resources/db/migration`…"* + **V번호 63건**(ERD-010 34 · ERD-019 29) + **커밋 해시 `fef2a7d` + 장애 이력** ·
`CNT-008` `UC-007` 에 **커밋 `28db2ee`** — ★**같은 해시가 deprecated `API-033` 본문에도 있어 폐기 ITEM 서술이 활성으로 전이** ·
`CNT-009` `SCREEN-026` 이 **상충하는 두 변형을 병기**(부제 정적/동적 · 9건3열/10건2열 · Skeleton 6/4)인데 **같은 ITEM `sections[2]` 는 9건으로 단정**하고 SD-006·UC-032 도 9건3열 ·
`CNT-010` `CDIAG-015`(1,647자)에서 **6블록 중 2블록(약 600자)이 폐기 경위**에 소비되고 현행 규칙은 맨 끝 한 문장 ·
`CNT-011` **한글 손상**(아래 별도)

### P2 8건
`CNT-012` DFEAT 4건 말미 `(1차 baseline, 화면 SKKLID-UI-…)` — **`legacy_source.identifier` 에 이미 있는 중복** ·
`CNT-013` DFEAT 4건 서술 빈약(89~131자 vs 형제 442~713자). ⚠**입력 전제 정정: `user_story` 는 4건 다 채워져 있다** — 공란은 `acceptance_rules` 와 링크 3종 ·
`CNT-014` `DFEAT-051` 의 `gap-api.md`·V145·감사 결론 문장 · `CNT-015` **규칙 문단 복제**(신고 412 **10 ITEM/12회**, 롤백 6 ITEM/7회 — ★**실제 드리프트 징후: no-op 조항이 6 중 3 에만**) ·
`CNT-016` `responses[].description` 에 **어노테이션 16건 / 11 API**(대조군 `API-032` 는 실제 조건을 적음) ·
`CNT-017` `API-025`·`032` 의 200 이 *"아래 스키마·예시는 … **잔존물이다**"* 자기 참조 · `CNT-018` `SCREEN-005` 에 V163 2곳 + *"BE 는/FE 는"* 지시문체 · `CNT-019` `DFEAT-052` 의 `SC-036` 표기(형제 UC-028 은 SCREEN-035)

### ★★★ 한글 손상 검사 — **①②③④ 가 전부 놓친 손상을 ④b 가 잡았다**
- ① 15종 grep → **1 hit 전건 오탐**(*"지금 **찍**어야 할 관절"* 은 정상 어간)
- ② 희귀 음절(590 distinct 중 **95 후보 전수 문맥 확인**) → **0건**
- ③ 1음절+목적격(**43 후보**) → **전건 오탐**
- ④ 낱말 고립도 → **0건**
- **④b 절단-빈도비 검사 → `바운딩박` ×3 ⊂ `바운딩박스` ×21 (ratio 7) 검출**

★**④가 놓친 이유**: 손상어가 **3회 출현해 "단발 출현" 필터에 안 걸렸고**, 손상 결과 음절 `박`이 **고빈도라 ②도 통과**했다.
⇒ **auditor 권고: ④를 "단발" 대신 "동족 어형 대비 빈도비"로 돌릴 것.**
**손상 위치 3곳 중 하나가 `DOMAIN-010.ubiquitous_language[0].term` — 도메인 용어사전 항목명 자체다.**

---

## diagram (5건 — P1 5)

> ★**SEQ 14건 전건 `source`(mermaid 원문) 확인 완료** — 다른 차원이 남긴 사각을 메웠다.
> 도메인 귀속 SEQ = `SEQ-005`·`006`·`007`·`008`·`009` 5건. **폐기 모델 대조 결과 SEQ-005/006/007/008 정합**(특히 `SEQ-006` 은 *"자석 올가미 해석 폐기·VOS 분할 축"* 을 본문에 명시). **갭은 `SEQ-009` 하나뿐.**

| ID | 요지 |
|---|---|
| **D010-DIAG-001** | DFEAT 3건 미도해. ★**성질이 다르다** — `DFEAT-052` 는 **본문(Label·LabelAttr 클래스, LabelMaster/AttrController)이 이미 있어 `depicts` 선언만 누락**(저비용) / `DFEAT-050`·`051` 은 **본문 노드 자체가 0건**이라 **depicts 만 채우면 dangling 서술**이 된다 |
| **D010-DIAG-002** | `CMP-004.components[LabelService]` = *"라벨 CRUD·**임시저장 upsert**"* ↔ **같은 ITEM 최상위는 *"full-replace 로 영속"*** 자기모순. ⚠**실제 메서드명이 `bulkUpsert` 이고 `SEQ-010` 이 *"full-replace upsert"* 로 병기해 P0 아닌 P1 로 보수 판정** |
| **D010-DIAG-003** | `CDIAG-015` 가 **프레임 단위 버전 모델에 고착** — `VersionController` 가 엔드포인트 4종만 확정 열거하고 **영상 단위 `API-195`·`196`·`197` 이 없다**. ★**전 본문에 `LS_OUTPUT_VER_SNPSH` 문자열 0건**(확정 정책 12 의 판정 단일 원천) |
| **D010-DIAG-004** | `CMP-006`·`SEQ-009` 가 복구를 **"즉시 롤백" 단일 경로**로만 그림. `SEQ-009.source` 가 **폐기된 선택 건수 분기**(*"alt 버전 1건 선택 … else 버전 2건 선택"*)를 그대로 그린다 |
| **D010-DIAG-005** | `CDIAG-004` 가 **구 물리명 인용** — `LABEL_ID`(실제 `LBL_ID`) · `LS_LABEL.LABEL_TYPE_CD`(실제 `LBL_TYPE_CD`) · `(LABEL_ID, ATTR_NM)`(실제 `(LBL_ID, ATRB_NM)`) |

### ★ diagram 이 넘긴 타 도메인 라우팅
**→ 관제 통지 도메인(D016)**: `SEQ-010.source` 가 *"수정 → 디바운스 flush → export v{n+1} 재생성 → TASK_MODIFIED"* 를 **재검토 표시(`REVLT_YN`) 보류 없이** 그린다 — **2026-08-07 확정(*"재생성·통지 트리거는 검수 승인 한 곳, 재승인까지 flush 보류"*)과 어긋난다.** D010 소유가 아니라 미계상.

---

# ✅ DOMAIN-010 완료 — 10/10 차원 · **105건 (P0 18 / P1 60 / P2 27)**

| 차원 | 건수 | P0 | P1 | P2 |
|---|---:|---:|---:|---:|
| coverage | 10 | 2 | 6 | 2 |
| links | 14 | 1 | 11 | 2 |
| schema | 13 | 5 | 7 | 1 |
| stale | 14 | 3 | 8 | 3 |
| policy | 9 | 5 | 3 | 1 |
| acceptance | 8 | 0 | 3 | 5 |
| requirement | 5 | 0 | 1 | 4 |
| content | 19 | 1 | 10 | 8 |
| diagram | 5 | 0 | 5 | 0 |
| test_scenario | 8 | 1 | 6 | 1 |
| **계** | **105** | **18** | **60** | **27** |

## D010 최종 요약 — **P0 18건은 두 뿌리로 수렴**

**① 「2026-08-10~14 대전환이 일부 ITEM 에만 닿았다」** — V180·V181·V183(08-11)과 화면 동선 확정(08-11)·route 변경(08-14) 이후
`ERD-019`(08-07 최종)·`API-034/035/036`·`CDIAG-015`·`CMP-006`·`SEQ-009` 가 **미갱신**. **그런데 `stale` 플래그는 전부 `false`.**
⇒ **버전관리 판정의 단일 원천 테이블(`LS_OUTPUT_VER_SNPSH`)이 ERD·클래스도·컴포넌트도 어디에도 없다.**

**② 「응답 계약이 description 과 반대」** — `API-036` 이 대표(description *"새 버전 행을 쌓지 않는다"* ↔ responses **3곳 *"새 active 버전이 생성된다"***).
**응답 계약만 보고 구현하면 `(DATA_SRC_SN, VERSION_HASH)` UNIQUE 위반.**
`API-196` 도 같은 계열 — **계약대로 보내면 전건 400**.

## D010 소급/확인 항목
- **`REQ-010`(08-07)의 *"2건 선택은 두 버전 간 비교"*** 가 08-11 결정 대비 stale 인지 — **API 축(`/diff?compareWith=`)은 살아 있어 화면/API 축 구분 필요** (사용자 확인)
- **스냅샷 입도** — `TEST-004`(프레임 단위 N행) ↔ `UC-007`(영상 단위 1행). **롤백 UNIQUE 키가 `(DATA_SRC_SN, …)` 인 점은 프레임 입도를 지지** (사용자 결정)
- **`DFEAT-069` phantom** — `constraints` 자유 텍스트라 `links.unresolved` 로 안 잡힘
- **→ D003**: `TEST-001` 이 **deprecated `SCREEN-007`** 을 exercises (**P0 급, 그 도메인 감사에서 미검출**)
- **→ D004/D005**: `TEST-002` 의 `META_KEY='activity'` 가 확정 metaKey 규격 밖
- **→ D016**: `SEQ-010` 이 재검토 보류 없이 통지를 그린다
