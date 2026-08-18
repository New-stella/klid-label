# DOMAIN-007 데이터 증강 — 도메인 점검 원본

> 스킬 `mc-logi-domain-review` v1.5.0 (read-only) · 10차원 · 재개 세션(2026-08-14)
> 도메인 인벤토리: 활성 DFEAT 2(`DFEAT-029`·`DFEAT-030`) / deprecated 1(`DFEAT-031`) ·
> API 활성 10 + deprecated 2(`API-064`·`API-180`) · 화면 `SCREEN-022`·`SCREEN-023` · UC `UC-001`~`UC-003` ·
> SEQ `SEQ-002`~`SEQ-004` · CDIAG-010 · MOD-013(augment)·MOD-018(webhook) · FEAT-004
> 다이어그램 커버리지 100% · deprecated 참조 0 · dangling 0

---

## coverage (7건 — P0 1 / P1 5 / P2 1)

### D007-COV-001 (P0) — 활성 API 10건 **전부 orphan**
`DFEAT-029`·`DFEAT-030` 의 `implemented_by_endpoints` 가 **둘 다 빈 배열**이라 도메인 API 10건이 어떤 기능에도 매핑되지 않는다.
동시에 `invokes_apis`·`persists_in_tables`·`triggers`·`related_acceptances`·`implemented_by_module_apis`·`implemented_by_service_interfaces` 도 전부 빈 배열.

활성 10건: `API-059` GET /v1/augments · `API-060` POST /v1/augments/request · `API-061` GET /v1/augments/{jobId}/result ·
`API-062` accept · `API-063` reject · `API-165` POST /v1/genai/callback · `API-179` GET /v1/videos/{rawSn}/resolution ·
`API-188` progress · `API-189` cancel · `API-190` restore

역방향 확인: `API-060.backward` = SCREEN-022(consumes·references)만 / `API-165.backward` = **완전 0건** / `API-179.backward` = SCREEN-022 만.
→ DFEAT 본문은 API 가 다루는 동작을 **산문으로만** 열거해, 계약↔기능 추적 경로가 문서 서술에만 존재한다.
**D001 8 · D003 33 · D004 9 · D005 12 · D006 4 에 이어 6개 도메인 연속 재현** — 프로젝트 레벨 구조 결함.

### D007-COV-002 (P1) — `DFEAT-030` UC backing 0
`specializes_feature` 키 자체가 없다(`DFEAT-029` 는 `FEAT-004` 보유). `backward` 는 `CDIAG-010` 뿐.
UC-001·002·003 은 전부 `realizes_features=[FEAT-004]` / `realizes_dfeats=[]` 라 FEAT 경유 간접 backing 도 `DFEAT-029` 에만 성립.
→ `DFEAT-030` 은 UC·SCREEN 어느 쪽에서도 도달되지 않는 고립 기능.

### D007-COV-003 (P1) — 화면 backing 0 (`references_features`·`references_dfeats` **키 부재**)
`SCREEN-022`·`SCREEN-023` 둘 다 두 키가 없고 `realizes_use_cases=[]`. `consumes_apis` 만 채워져 있다.
백엔드 전용이 아님이 화면 자신의 purpose 로 입증된다 — SCREEN-022: *"REVIEWER 가 검수 완료(승인) 영상 1건에 처리 종류 하나(겨울/야간/우천 증강 또는 해상도 변경)를 단일 선택해 요청하는 화면"*.

### D007-COV-004 (P1) — UC 3건 전부 error path SEQ 부재
SEQ 전역 14건 전수 확인 결과 **`scenario_type="error_path"` 가 프로젝트 전체 0건**. 그런데 이 UC 들은 실패 분기가 명시된 계약이다:
UC-001 *"[거부 조건] ①파생본(ORGNL_RAW_SN non-null)은 증강 요청 대상이 될 수 없다 — 400 … ②비식별 누락 신고 구간은 외부 위탁이 막혀 412"* ·
UC-002 *"재수신 시 200 no-op(멱등)/409(상태 충돌) 구분 … 고아 PENDING 만료 스윕·DEAD_LETTER"* · UC-003 *"finalize 성공 시 ACCEPTED 확정(실패 시 예약행 삭제)"*.

### D007-COV-005 (P1) — 책임 R7 **[미사용 파생 폐기]** 를 맡는 활성 DFEAT 없음
도메인 책임 매트릭스 R1~R7 중 R7(*"반려된 파생은 작업 대상에서 빠지고 유예 7일 후 배치가 DB 행과 파일까지 실삭제한다(ADR-045). 삭제 대상은 파생·반려·유예경과 3조건 동시 충족만이며 조건을 최종 DELETE SQL 에 리터럴로 박는다"*)에 매칭되는 활성 DFEAT 0건.
활성 DFEAT 2건 본문에 폐기·삭제·유예 서술이 한 글자도 없다. **비가역 실삭제 책임인데 기능 산출물에 소유자가 없다.**

### D007-COV-006 (P1) — `DFEAT-030` 이 배정된 책임 R5 를 미실현 + 폐기 상태모델 서술
본문 전문: *"증강 상태 흐름(가능→대기→진행→완료/부분성공/실패)을 관리하고, 실패/부분성공 시 자동 재처리한다. (1차 baseline)"*
확정 생성 결과축 값(ACCEPTED/REJECTED/CANCELED)과 **하나도 일치하지 않고**, 검수 결정축·등재 게이트·해상도 예외를 다루지 않는다.
`current_version=2` / `2026-05-30` 이후 개정 없음인데 **`stale=false`**. 실제 R5 서술은 `SCREEN-023.purpose` 에만 존재.

### D007-COV-007 (P2) — `DFEAT-029` 가 R4·R6 제약 정책 미포함
파생 깊이 1 고정(400 거부) · 중복 요청 허용·비차단 · `prompt` 5필드 — 셋 다 description 에 없다(UC-001·SCREEN-022 에만 존재).
또 `user_story.i_want` 가 *"원본에 5종 증강을 적용하기를"* 로 남아, 같은 ITEM 의 description 이 폐기 선언한 구 5종 모델을 유지하는 **내부 자기모순**.

### coverage 차원 단서 (다른 차원 이관 — 중복 보고 금지)
- **★도메인 경계 불일치**: 같은 리소스의 GET/POST 가 다른 도메인 — `API-179 GET /v1/videos/{rawSn}/resolution` 은 DOMAIN-007, **`API-092 POST /v1/videos/{rawSn}/resolution` 은 DOMAIN-003**(approved). UC-003·SCREEN-022 는 POST 를 이 도메인 책임 R2 의 주 진입점으로 서술 → 도메인 API 인벤토리가 실질 불완전. **policy/links 차원 소관.**
- `API-165`(genai 콜백)는 backward **완전 0건** — SEQ-003 이 콜백 흐름을 도해하는데도 링크 없음. links 차원.
- `DFEAT-029/030` 둘 다 `implementation.status=implemented`/`progress=100` 인데 `modules`·`records`·`subtasks` 전부 `[]`(MOD-013·MOD-018 이 도메인에 붙어 있어 백필 후보).
- COV-10(비-HTTP 경계 계약) **N/A** — 프로젝트 전역에 `service_interface`·`module_api`·`library_api` 가 **0건**.
- COV-1(책임 인벤토리 게이트) 통과 — 다만 번호 목록이 아니라 산문 섹션이라 R↔DFEAT 매칭은 본문 해석에 의존.

### coverage 미확인 층
1차 소스 grep 미수행(COV-6 판정 불가) · MOD-013·MOD-018 본문 미조회(구현 100% 주장 검증 안 됨) ·
api_endpoint 전역 전수 스캔 아님(DOMAIN-007·003 두 도메인만) · `FEAT-004` 본문 미조회 ·
정적 렌더 미러·로컬 키트·위키·테스트케이스 층 미확인.

---

## links (14건 — P1 11 / P2 3)

| ID | sev | 요지 |
|---|:--:|---|
| D007-LINK-001 | P1 | DFEAT 2건의 링크 배열 **전건 공란** → 활성 API 10/10 orphan (COV-001 과 같은 사안, dedupe) |
| D007-LINK-002 | P1 | **나중에 신설된 API 5건만** `implements_features` 공란 — `API-165`·`179`·`188`·`189`·`190`. 형제 `API-059`~`063` 은 `[FEAT-004]` 보유 → **auto_fixable** |
| D007-LINK-003 | P1 | SEQ 본문이 경로를 인용하는데 `invokes_apis` 누락 — SEQ-002→API-060 / SEQ-003→API-165 / SEQ-011→API-059·062·063·190 / SEQ-004→API-179 → **auto_fixable** |
| D007-LINK-004 | P1 | 화면 `realizes_use_cases` 공란인데 UC 는 `related_screens` 로 이미 지목(단방향) — SCREEN-022↔UC-001·003 / SCREEN-023↔UC-002·010 → **auto_fixable** |
| D007-LINK-005 | P2 | `SCREEN-022.consumes_apis` 의 `API-179` 가 어느 섹션·컴포넌트에도 없음 — 잔재이거나 기재 누락 |
| D007-LINK-006 | P2 | `SCREEN-023` 섹션 note 는 경로를 인용하는데 `references_apis` 누락 — 진행/취소 섹션에 API-188·189, 활용결정 카드에 API-190 → **auto_fixable** |
| D007-LINK-007 | P1 | `ERD-011` 테이블 4종(`LS_DATA_AUG`·`_RVW`·`_LBL_MAP`·`_DSCD`)을 `persists_in_tables` 로 지목하는 DFEAT **0건** — 테이블 책임 주체 부재 |
| D007-LINK-008 | P1 | UC-001·002·003 의 `realizes_dfeats` 공란(FEAT-004 만) — FEAT-004 specialize DFEAT 는 `DFEAT-029` **1건뿐**이라 후보 유일 → **auto_fixable** |
| D007-LINK-009 | P1 | `EVT-011` 이 `documented_emitters/consumers` 에만 기재돼 **그래프 링크 0건**(backward 완전 공란). 링크는 `DFEAT-029.triggers`·`DFEAT-030.consumes` 에 적어야 생김 — **D005 `EVT-006` 과 동일 패턴** → auto_fixable |
| D007-LINK-010 | P1 | `INT-006`(inbound `/v1/genai/callback`)·`INT-008`(outbound 위탁)의 `triggers_apis`·`invoked_by_apis`·`related_events`·`referenced_in_sequences` **전건 공란** + 짝 필드 `SEQ-002/003.referenced_integrations` 도 공란 → 외부 연동 추적 전면 단절 |
| **D007-LINK-011** | **P1** | ★**신규 오지목** — `API-065`(POST /v1/vlm/callback, VLM 시계열)가 `implements_features=[FEAT-004]`(증강) 선언. description 에 증강·파생·`LS_DATA_AUG` 언급 0건이고 도메인 귀속도 다름. **D005 `MOD-010`→FEAT-004 와 동일 계열이며 둘 다 VLM 계열이라 원인이 같을 가능성** |
| D007-LINK-012 | P1 | `DFEAT-030` 에 `specializes_feature` **키 자체 부재** → FEAT 계층 고립 |
| D007-LINK-013 | P2 | `CMP-007.depicts` 공란(+`brownfield` 키도 부재) — 프로젝트 전반 CMP-001~009 전건 동일, dedupe |
| D007-LINK-014 | P1 | `TEST-003` 은 `covers_use_cases=[UC-001,UC-002,UC-010]` 로 **이 도메인 귀속 확정**인데 `related_domains`·`related_apis`·`verifies_*` 전건 공란 → `related_domains=[DOMAIN-007]` 은 auto_fixable |

### ★ FEAT-004 역참조 전수 (18건) — 재조사 불필요
오지목 **정확히 2건**: `MOD-010`(VLM 메타 패키지, D005 기보고) · `API-065`(신규).
정상 16건: UC-001/002/003/010 · API-059/060/061/062/063/064/092 · DFEAT-029 · MOD-013 · MOD-018 · ROLE-001 · SCREEN-022.

### links 차원 단서 (다른 차원 이관)
- **`API-092` POST /v1/videos/{rawSn}/resolution 이 DOMAIN-007 에 미귀속** — `implements_features=[FEAT-004]` 이고 SCREEN-022 의 해상도 제출 대상이자 SEQ-004 의 유일한 `invokes_apis` 인데 도메인 목록 12건에 없다. **coverage/policy 소관.**
- **`UC-010`(증강 영상 활용 여부 검수)이 DOMAIN-007 backward 에 없다** — SCREEN-023·SEQ-011·TEST-003 이 전부 이 도메인 흐름을 서술. 귀속 재판정 대상.
- **★상태값 축 불일치**: `ERD-011.LS_DATA_AUG.AUG_PROC_STTS_CD` code_values 와 `CDIAG-010.AugProcStatus` enum 이 **{PENDING, ACCEPTED, REJECTED} 3값**인데, SEQ-003·CDIAG-010 본문·API-061 change_summary 는 **CANCELED 를 4번째 값으로 명시**. → schema/stale 소관.
- `DOMAIN-008`(폐기)이 `collaborates_with` 로 남아 있으나 **링크 소유 필드가 DOMAIN-008 쪽**이라 이 도메인 갭 아님 — DOMAIN-008 정리 시 처리.
- `CMP-007.components`(AugmentResultController·VideoResolutionPersister)와 SEQ-003/004 참가자(AsyncAugmentFrameRunner·ResolutionReservationPersister·AsyncResolutionRunner·ResolutionPersistService) 명칭 축 드리프트 가능 — stale 소관.
- **LINK-9(활성 ITEM 의 deprecated 인용) 0건** — 검사 대상 명시적 열거함. `ERD-011.description` 의 *"1차 MariaDB 원형은 ERD-004(deprecated) 참조"* 는 brownfield 출처 인용이라 오탐 제외.
- UC-001/002/003/010·MOD-013·TEST-003 의 `stale=true` 는 사유가 전부 *"SCREEN-022/023 변경 — references"* 라 플래그만 — links 에서 미보고.

### links 미확인 층
**LINK-8 `unresolved_links` 는 측정 불가**(쓰기 응답에만 노출 — 0건이 아니라 미측정) ·
정적 렌더 미러 미확인(SCREEN-022 v31 15.4KB·SCREEN-023 v34 — LINK-005/006 은 미러 층에서 다른 결과 가능) ·
AC 역방향 미확인(acceptance 소관) · `MOD-013`·`MOD-018` 의 `realizes_dfeats` 는 필드 부재/값 공란 구분 못 해 미보고 · 1차 소스 grep 미수행.

---

## schema (15건 — P0 3 / P1 10 / P2 2)

### P0 3건 — 전부 "ERD 가 공표한 것이 실물과 다르다"

**D007-SCH-001 (P0) — `LS_DATA_AUG.REJECT_RSN` 은 실재하지 않는다**
ERD-011 이 *"엔티티 @Transient — 검수 상세는 `LS_DATA_AUG_RVW.RJCT_RSN` 으로 이관, **물리 컬럼(V7)은 잔존**"* 이라 공표하는데,
`V1__baseline.sql` 의 `ls_data_aug` 는 **15컬럼이고 `reject_rsn` 이 없으며** 마이그레이션 전수 grep 0건. DB 설계서 컬럼표에도 없다.
엔티티 주석도 이름을 **`RJCT_RSN`** 으로 적는다. 표준 CSV 상 `REJECT_RSN` 은 양쪽 미등록이고 정본은 `RJCT_RSN`(반려사유, 내용V4000).

**D007-SCH-002 (P0) — 도메인이 실제 소유한 테이블 2종이 ERD 에 통째로 없다**
`LS_DATA_AUG_JOB`(11컬럼)·`LS_DATA_AUG_JOB_FILE`(7컬럼)이 `V1__baseline.sql` 에 실재하고 CASCADE FK 2건까지 있는데
ERD-011.tables 는 4건뿐이다. 그런데 **API 계약이 그 컬럼을 직접 인용**한다 —
`API-165.request_id.description` = *"우리가 발급한 job 단위 멱등키(= **`LS_DATA_AUG_JOB.IDMP_KEY`**)의 echo"*,
`API-188` 진행률 = 청크 job 파일 수 가중 평균, `SCREEN-023.AugmentProgressPanel.options` = `LsDataAugJob.STTS_*` 5종.
⚠ SEQ-002/003/004/011 **네 시퀀스도 이 테이블을 한 번도 언급하지 않아** 같은 누락이 시퀀스 축에서 재현된다.

**D007-SCH-003 (P0) — `LS_DATA_AUG.SRC_SN` 의 참조 대상이 틀렸다 (조인하면 다른 행을 집는다)**
ERD 는 *"`LS_DATA_RAW.RAW_SN` 참조"* 라 적었으나 실제 저장값은 **대표프레임 `LS_DATA_SRC.SRC_SN`** 이다.
근거 5중: 리포지토리 javadoc(*"대표프레임(LS_DATA_SRC.SRC_SN)이므로 … 서브쿼리로 환원한다"*) · `AugmentRequestService` 가 `rawSn`/`representativeSrcSn` 을 **별개 인자**로 전달 ·
`API-059.parameters[srcSn]` = *"값은 `LS_DATA_SRC.SRC_SN`(대표프레임 ID)이며 응답 videoId(RAW_SN)와 다름"* · `SEQ-002` *"대표 프레임(MIN SRC_SN) 조회"* ·
자매 컬럼 `LS_DATA_AUG_JOB_FILE.SRC_SN` 은 DB 설계서에 *"논리 FK(→LS_DATA_SRC)"* 로 **옳게** 적혀 있다.
⚠ **같은 오기가 `docs/design/KLID_AT_데이터베이스설계서.md`(KLID-AT-TB-030)에도 복제**돼 있어 ITEM 만 고치면 D8 산출물과 어긋난다.

### P1 10건

| ID | 요지 |
|---|---|
| D007-SCH-004 | `API-060`(증강 요청)에 **`request_body` 가 통째로 없다** — 실제로는 `videoIds`·`types`·`prompt`5필드를 `@Valid @RequestBody` 로 받는다 |
| D007-SCH-005 | `API-063`(reject)에 `request_body` 없음 — `RejectRequest(reason)` 필수라 **계약대로 호출하면 400**. 대조군 accept 는 본문이 없어 정합하므로 누락은 reject 한 건 |
| D007-SCH-006 | `API-061` 경로변수 `jobId` 가 **`type: string`** 인데 실제는 `Long`(=원본 `RAW_SN`). 화면은 오늘 `/augment/result/:rawSn` 으로 이미 정정됐고 형제 API 는 전부 integer → **auto_fixable** |
| D007-SCH-007 | `LS_DATA_AUG_DSCD` 인덱스 3건 **구성 컬럼이 전부 다름** — UNIQUE 가 `NEW_RAW_SN`(실물 `DATA_AUG_SN` 부분유니크) / `FILE_DEL_RTRY_NMTM`(실물 `DEL_DT`) / LOOKUP 1컬럼(실물 2컬럼). 파생으로 `relationships` 의 1:1 도 틀림(폐기→복구→재폐기 이력이 쌓여 1:N) |
| D007-SCH-008 | `LS_DATA_AUG` 실물 인덱스 2건 미기재 — `IDX_..._SRC_REGDT`, `IX_..._NEW_RAW_SN`(폐기 실삭제 스윕의 조회 인덱스라 사양상 의미 있음) |
| D007-SCH-009 | **없는 FK 를 선언 + 있는 FK 는 미기재** — `LS_DATA_AUG_RVW`·`_LBL_MAP` 의 `DATA_AUG_SN` 이 `references(on_delete=restrict)` 인데 같은 컬럼 description 은 *"FK 미설정"* 자기모순. 실재 FK 는 `LS_DATA_AUG_RVW.DATA_RAW_SN → LS_DATA_RAW`(CASCADE) 하나이며, **이 FK 배치가 곧 "폐기 삭제 시 CASCADE 로 정리되지 않는다"의 근거**다 |
| D007-SCH-010 | `AUG_PROC_STTS_CD.code_values` 에 **`CANCELED` 누락**(실재 4번째 값, `API-189` 가 만드는 계약). 컬럼 description 도 *"본체가 검수(PENDING/ACCEPTED/REJECTED)"* 라 같은 ERD 상위 서술(축 분리)과 모순 → **auto_fixable** |
| D007-SCH-011 | `SCREEN-023` 항목 탭 options 가 **레거시 `RESOLUTION`** 을 게재 — `CONTRACT_AUG_TYPES` 에서 제외돼 **화면에 절대 도달하지 않고**(`AugmentResultViewService` 가 warn 후 skip) 정작 도달하는 `RESL_*` 3종이 없다. SCREEN-022 는 옳게 적혀 있음 → **auto_fixable** |
| D007-SCH-012 | `LS_DATA_AUG_DSCD` 컬럼 **6종이 표준용어 미등록**(`DATA_AUG_DSCD_SN`·`RSTR_RSN`·`FILE_DEL_DT`·`FILE_DEL_RTRY_NMTM`·`FILE_DEL_FAIL_DT`·`FILE_DEL_FAIL_RSN`). 구성 단어는 전부 표준 등록어라 조합은 적법 — **등록 절차만 미완** |
| D007-SCH-013 | `REG_USER_NO` 도메인 불일치 — 사업표준용어는 **N19** 인데 ERD·구현·자매 컬럼 3자가 **V(50)**. 엔티티가 *"토큰 sub(문자열) 저장 … 의도적으로 VARCHAR"* 로 명시 → **어긋난 쪽이 사전일 가능성**(선례 `NXTM_RTRY_DT`). 기계적 변경 금지, 소유권 확인 필요 |

### P2 2건
- **D007-SCH-014** `ORGNL_RAW_SN` 논리명 *"원본원시일련번호"* → 등록 용어명 *"원본원시**영상**일련번호"*. 자매 `NEW_RAW_SN` 은 정합 → auto_fixable
- **D007-SCH-015** DFEAT 2건 `persists_in_tables` 공란이라 **SCH-1 이 "위반 0"이 아니라 "판정 불가"**. 실물 테이블은 6종

### ★ ERD-011 이 확정 정책을 옳게 반영한 항목 (오탐 방지 — 재보고 금지)
①구 전용 테이블 `LS_RESOLUTION_EXPORT`/`_LBL_MAP` 을 활성 사양으로 들고 있지 않음(V126 DROP 명시)
②`UK_LS_DATA_AUG_ACTVTN` 을 활성 인덱스로 들고 있지 않음(V153 DROP·409 폐기 명시)
③`PROMPT_CN`·`NEW_RAW_SN`·`ORGNL_RAW_SN` 존재 ④신규 배율 컬럼 없이 `COORD_RECALC_YN`/`SCALE_X`/`SCALE_Y` 재사용
⑤`VMS_CLIP_ID` 유일화가 `DATA_AUG_SN` 기반(SEQ-003 Note)
⑥`UK_LS_DATA_AUG_RESL` 의 부분 predicate 미표기는 **선언된 의도**(메타 미지원 — brownfield.notes 명시)라 갭 아님

### schema 차원 단서 (다른 차원 이관)
- **STL**: `ERD-011.implementation.status='planned'/progress=0` 이고 전 컬럼도 `planned` 인데 **실제로는 V1 베이스라인에 전량 반영·가동 중**. `PROMPT_CN`·`NEW_RAW_SN`·`LS_DATA_AUG_DSCD` 전 컬럼엔 implementation 블록 자체가 없어 같은 ERD 안에서 메타 형식 불균일.
- **STL**: `SCREEN-022.JobCard` note 가 여전히 *"`/augment/result/{jobId}` 로 이동"* — SCREEN-023 은 오늘 `:rawSn` 으로 개명됐고, 같은 화면 하단 액션바는 이미 `videoIds[0]` 로 옳게 적혀 **한 화면 안에서 두 서술이 갈린다**.
- **STL**: `API-064`·`API-180` 이 deprecated 인 채 DOMAIN-007 backward 에 잔존.
- **GLOSSARY**: SCH-012·013 은 `mc-logi-glossary-align` 대상 — 수정 전 `createdBy` 로 KLID-BM 2026-05-28 배포분 여부 확인 필수.
- **SCH-2(논리·물리 ERD 페어)**: 활성 ERD 17건이 **전부 physical** 이라 페어 관례 자체가 없음 → 프로젝트 전역 관례라 갭 미보고.
- **SCH-3** 위반 0(59개 컬럼 슬롯 전수 육안) · **SCH-5** 위반 0 · **SCH-10** N/A(SVC/IAPI/LIB 0건).

### ★ CSV 정본 grep 실적 (재수행 불필요)
공통표준용어 13,176 · 사업표준용어 1,373 · 단어 3,284/471 전수 대조(python `os.path.join`, utf-8-sig).
ERD-011 4테이블 **59개 컬럼 슬롯 → 중복 제거 45종** 양축 대조, 개별 확인 약어 65개.
**오탐 회피 실적**: `DEAD_LETTER_AT`·`IDMP_KEY`·`LBL_INTGRT_PCT`·`SCALE_X/Y`·`COORD_RECALC_YN`·`VDO_FILE_PATH`·`DATA_AUG_LBL_MAP_SN` 은
단어 축만 보면 미등록으로 보이나 **전부 사업표준'용어'에 복합용어로 등록**돼 있어 위반 아님 — 단어 축 단독 판정으로 갭을 만들지 않았다.

### schema 미확인 층
1차 소스 grep 미수행 · `deploy/onprem/db/schema.sql` 미대조(V1 이 pg_dump 산출물이라 동치로 봄) · frontend·ai-server 범위 밖 ·
SCH-6 은 열람 범위에서만 유효(도메인 32 ITEM 전수 아님).

---

## stale (15건 — P0 2 / P1 7 / P2 6)

> ★ 이 차원의 핵심 소득은 **`stale=false` 인데 본문이 폐기 모델인 사례**다. 플래그를 신뢰하지 말라는 지침이 정확히 적중했다.

### P0 2건

**D007-STL-001 (P0) — `API-062`·`API-063` 이 폐기된 단일 상태축 모델을 응답 계약으로 들고 있다**
두 API 의 `responses.200` 이 `augProcSttsCd`(*"검수 상태 코드 (승인 후 ACCEPTED)"*)·`decisionAt`·`decisionUserNo`·`rejectReason`·`lblIntgrtPct`
— **전부 `LS_DATA_AUG` 축**이다. `API-062.description` 은 한술 더 떠 *"증강 결과를 수락해 **새 영상(RAW_SN)으로 생성**한다"*.
소비 화면 `SCREEN-023`(v34, 오늘)은 정반대를 명시한다 — *"★결정은 새 영상을 만드는 행위가 아니다 … 이 화면의 accept/reject 는 검수 행(`LS_DATA_AUG_RVW.RVW_STTS_CD`)만 쓴다 … `AUG_PROC_STTS_CD` 는 생성 결과 전용(웹훅 소유)이라 … 이 화면의 결정은 그 값을 바꾸지 않는다"*.
`CDIAG-010` 도 *"검수 전이는 PENDING 에서만 허용돼 REVIEWER 의 승인·반려가 영구히 409 로 막혀 있었다(워크플로 자체가 도달 불가)"* 라고 그 결함을 기록한다.
**두 ITEM 에 정정 문구가 한 줄도 없어, 이 계약대로 구현하면 ADR-045 가 되돌린 결함이 재현된다.** `stale=false`·`2026-08-07`.

**D007-STL-002 (P0) — `DFEAT-030` 이 1차 baseline 에 고착**
본문 전문이 *"증강 상태 흐름(가능→대기→진행→완료/부분성공/실패)을 관리하고, 실패/부분성공 시 자동 재처리한다. **(1차 baseline)**"* 뿐이고
확정 모델의 값이 **하나도 등장하지 않는다**. `user_story` 도 같은 축. `brownfield.diff_summary`·`notes`·`decided_by` 가 **전부 공란**이라 1차→2차 변화가 어디에도 없다.
(coverage 의 D007-COV-006 과 같은 대상 — dedupe 대상이나 이 차원이 본문 인용으로 확정)

### P1 7건

| ID | 요지 |
|---|---|
| D007-STL-003 | `DFEAT-029` **ITEM 내부 자기모순** — description 은 *"구 5종 … 정책(1차 baseline)은 폐기됐다"* 인데 `user_story.i_want` 는 *"원본에 **5종 증강**을 적용하기를"*. `brownfield.notes` 가 재작성 대상을 *"제목·본문"* 으로만 한정해 user_story 가 빠졌다 → auto_fixable |
| D007-STL-004 | **부속 배열 cascade 누락** — `ERD-011.columns[AUG_PROC_STTS_CD]` 와 `CDIAG-010.classes[AugProcStatus]` 가 여전히 *"본체가 검수(PENDING/ACCEPTED/REJECTED)"* 이고 **`CANCELED` 결손**. 두 ITEM 의 **최상위 description 은 이미 축 분리를 옳게 적고 있어** 부속 배열만 뒤처졌다 |
| D007-STL-005 | `ERD-011.tables[LS_DATA_AUG].description`·`brownfield.notes` 가 **구 4종(WINTER/NIGHT/RAIN/RESOLUTION)** — 같은 테이블 `AUG_TYPE_CD` 컬럼 설명은 *"RESL_* 값이 정식 판별자 … 구 RESOLUTION 은 레거시"* 로 이미 정정 → auto_fixable |
| D007-STL-006 | `CMP-007.components[AugmentRequestService]` = *"외부 증강 요청 (WINTER/NIGHT/RAIN/**RESOLUTION**)"* — 같은 ITEM description 의 *"해상도 파생 … **외부 위탁이 없는 내부 ffmpeg 리스케일**"* 및 `INT-008` *"해상도 변경은 외부 위탁 대상이 아니다"* 와 **정면 충돌**. 2026-08-06 정합에서 `external_dependencies` 만 고치고 `components[]` 가 빠짐 → auto_fixable |
| D007-STL-007 | `INT-008` 이 prompt 를 **증강 종류에서 자동 매핑**하는 구 모델로 서술(*"`AugmentPrompts.of(augType)` 로 WINTER/NIGHT/RAIN → time/season/… 매핑"*). 하루 뒤 확정된 정책은 **REVIEWER 입력 5필드 원문 전송**이며 `EVT-011` 은 *"위탁 측에서 DB 를 다시 읽어 재조립하면 두 벌이 되어 어긋난다"* 고 경고한다. INT-008 본문엔 `PROMPT_CN`·사용자 입력 언급이 **한 곳도 없다** |
| D007-STL-008 | `API-061` 이 여전히 `jobId` = *"증강 작업 식별자"* — `SCREEN-023` 은 오늘 `:rawSn` 으로 확정하며 *"과거 이 어긋남 때문에 … 존재하지 않는 주소로 이어져 화면이 영구히 처리 중으로 보이던 **사고가 있었다**"* 고 기록. **화면 축만 정정되고 계약 축이 남아 재발 여지** |
| D007-STL-009 | `brownfield.status=modified` 인데 **`legacy_source` 가 없는 ITEM 9건** — DOMAIN-007·MOD-013·API-059~063·SCREEN-022·023. 같은 도메인의 DFEAT-029/030·ERD-011 은 `legacy_source` 를 갖춰 **규약이 도메인 안에서 갈린다** |

### P2 6건
- **D007-STL-010** `stale=true` 4건(UC-001·002·003·MOD-013, 전부 *"SCREEN-022/023 변경 — references"*) — **본문 확인 결과 전부 현행 정책 정합**이라 플래그 해소(재확인 기록)만 필요. ⚠ 단 `MOD-013` 의 *"수락(새 영상 생성)"* 표현은 STL-001 계열이라 함께 볼 것
- **D007-STL-011** `MOD-013.brownfield.decided_by=ADR-004`(생성형 AI 본체 범위 외)인데 `diff_summary` 는 **내보내기 제거**를 서술 → 근거는 `ADR-005` 여야 함(DOMAIN-007·DFEAT-031 은 ADR-005 로 정합)
- **D007-STL-012** `ERD-011.LS_DATA_AUG_LBL_MAP`·`CDIAG-010.DataAugmentationLabelMap` 이 좌표 재계산을 **"해상도 저하"** 로만 한정 — 같은 ERD 의 `SCALE_X`(*"업스케일 시 >1 허용"*)·`COORD_RECALC_YN`(*"업스케일 포함"*)와 어긋남 → auto_fixable
- **D007-STL-013** `API-060.responses.200` = *"**외부 미연동 단계이므로** jobId 는 placeholder"* — `INT-008` 이 2026-07-30 에 *"이전 등록 '미연동'은 사실과 다름 — 실HTTP 클라이언트 + Retry/CB"* 로 정정했고 환경별(local 실배선 / dev·stg·prd noop)로 갈려 단일 전제가 성립하지 않음. **jobId 가 임시값이라는 사실 자체는 유효** → auto_fixable
- **D007-STL-014** `implementation` 이 `planned/0%` 인 5건(ERD-011·CMP-007·CDIAG-010·UC-001·UC-002)인데 같은 흐름의 DFEAT-029·UC-003·INT-006·INT-008·API-060/062/063/165 는 `implemented/100`. ⚠ API-188/189/190 의 planned 는 2026-08-07 신설이라 정상으로 보아 제외
- **D007-STL-015** 같은 컬럼의 마이그레이션 번호가 갈림 — `ERD-011` 은 `PROMPT_CN` 신설·`UK_LS_DATA_AUG_ACTVTN` DROP 을 **V153**, `UC-001`·`CDIAG-010` 은 **V147**

### ★ content 차원으로 이관 — 한글 표기 손상 2건 관측
- `API-179.description` — *"이 조회가 그 **개**을 메운다"* (→ 갭을)
- `API-165.description` — *"앞단을 **비콜간** 요청도 거부된다"* (→ 비껴간)
이스케이프 유래 음절 오류 패턴. content 차원이 전수 스캔으로 확정할 것.

### stale 미확인 층
1차 소스 grep OFF(STL-007 의 `AugmentPrompts` 잔존 형태 확인 못 해 **P1 로 낮춰 잡음**) ·
**정적 렌더 미러 미확인**(SCREEN-022/023 미러에 단일 상태축·5종 증강 잔재가 있는지 안 셌다) ·
SCREEN-022/023 의 `data.sections` 본문 미열람(purpose·route·brownfield 만) ·
`API-059/061/165/179/188/189/190` 의 responses/request_body 전문 미열람(**API-062/063 에서 잡힌 응답 스키마 잔재가 이들에도 있는지 미확인**) ·
AC 5건·NFR-009 는 메타만 · ADR 본문 미조회(참고 목록 기준 판정).

---

## policy (13건 — P0 2 / P1 9 / P2 2)

### P0 2건

**D007-POL-001 (P0) — `API-062`·`API-063` 이 ADR-045 축 분리를 정면 위반** (stale STL-001 과 같은 대상, **정책 축 판정**)
`ADR-045` 원문: *"`AUG_PROC_STTS_CD` 는 생성 결과 전용(웹훅 소유: 성공 ACCEPTED / 실패 REJECTED / 취소 CANCELED)이고, REVIEWER 의 사용·폐기 결정은 `LS_DATA_AUG_RVW.RVW_STTS_CD` 가 단독으로 소유한다 … `accept()/reject()` 는 `aug.applyReviewStatus` 를 호출하지 않고 리뷰 행만 쓴다. **두 축을 다시 합치지 않는다.**"*
`UC-010.main_flow[3]` 도 *"(생성 결과 축 `AUG_PROC_STTS_CD` 는 건드리지 않는다)"*.
→ **이 두 API 가 도메인에서 유일하게 폐기된 병합축을 유지**하며, ADR-045.context 가 기록한 *"REVIEWER 승인·반려가 영구히 409 로 막히던"* 결함을 그대로 재생산한다.

**D007-POL-002 (P0) — `INT-008` 의 prompt 조달이 확정 정책과 정반대**
INT-008: *"`prompt`(구조화 객체, **`AugmentPrompts.of(augType)` 로 WINTER/NIGHT/RAIN → time/season/… 매핑**)"*
UC-001: *"5필드 … REVIEWER 가 입력하면 명세서 §4.1 prompt 로 **가공 없이** 전송되고 `PROMPT_CN` 에 원문(JSON)으로 보관"*
EVT-011: *"위탁 측에서 DB 를 다시 읽어 재조립하면 **두 벌이 되어 어긋난다**"*
→ 이대로 구현하면 **REVIEWER 의 필수 입력이 버려지고 종류별 고정값이 벤더로 나가며, `PROMPT_CN` 원문과 전송값이 두 벌**이 된다. INT-008 은 `2026-07-30` = 반전(07-31) **직전판**.

### P1 9건

| ID | 요지 |
|---|---|
| D007-POL-003 | `DFEAT-029.user_story` 의 구 5종 잔재 (STL-003 과 동일 — dedupe) |
| D007-POL-004 | `DFEAT-030` 에 축 분리·등재 게이트·폐기 라이프사이클 전무 (STL-002 와 동일 — dedupe) |
| D007-POL-005 | `DFEAT-029.brownfield.status="preserved"` 인데 같은 ITEM `notes` 가 *"**전면 재작성**"*, description 이 *"폐기됐다"* — **status 오분류**. 같은 변화를 ERD-011·API-059/060 은 `modified` 로 분류 |
| D007-POL-006 | `CDIAG-010.classes[AugProcStatus]` = *"외부 생성 결과 **검수** 상태"* + `CANCELED` 결손 — **같은 ITEM description 이 축 분리를 선언**하는데 클래스 정의가 자기모순 (STL-004 와 대상 중복) |
| **D007-POL-007** | ★`CDIAG-010.classes[DataAugmentation]`(aggregate_root, LS_DATA_AUG)이 **`accept()`/`reject()` 메서드를 보유** — 검수 행위 메서드는 이미 `DataAugmentationReview(approve/reject)` 에 있으므로 **모델 수준의 폐기 잔재**. 같은 ITEM 이 *"aug 행의 상태를 건드리지 않고 리뷰 행만 쓴다"* 고 서술 |
| D007-POL-008 | `CDIAG-010.DataAugmentationLabelMap` 이 *"해상도 **저하** 증강 시"* — `UC-002.alternate_flows` 가 *"구 서술 '…다운스케일한 이미지셋 … 라벨 좌표는 제공하지 않는다'는 **폐기됐다**(ADR-018/ADR-023)"* 로 명시 |
| **D007-POL-009** | `API-092`(해상도 파생 생성)의 400 설명에 **파생 깊이 1 거부가 없다** — `UC-003.alternate_flows` 와 `API-060` 은 담고 있어 **같은 정책의 두 진입점이 비대칭**. `CMP-007` 이 *"해상도 경로는 이미 막고 있었고 증강 경로만 안 막혀 있던 비대칭을 해소한 것"* 이라 서술하는데 **계약 축에서는 거꾸로 해상도만 빠져 있다** |
| **D007-POL-010** | ★★`DOMAIN-007` 이 **superseded 된 `ADR-005`** 를 `decided_by` 이자 범위 외 근거로 인용 — **`ADR-020` 이 export 산출을 저작도구 범위로 되돌렸다**(아래 ★정정 참조) |
| D007-POL-011 | `EVT-011.example.callbackUrl` 이 `/v1/augments/callback` — **활성·폐기 어느 ITEM 에도 없는 경로**(활성은 `API-165 /v1/genai/callback`, 구 계약은 `API-064 /v1/augments/result`) |

### P2 2건
- **D007-POL-012** 해상도 파생 accept/reject 차단이 확정 정책이고 `SCREEN-023`·`API-059`·`UC-010` 이 명시하는데, **정작 차단을 수행하는 `API-062` 에는 400 응답 자체가 없고** `API-063` 400 에도 그 사유가 없다
- **D007-POL-013** `decided_by` 누락·오지목 5건 — DFEAT-029(→ADR-018/023) · DFEAT-030·API-190(→ADR-045) · API-189(→ADR-004) · UC-010(현 ADR-004 → ADR-045)

### ★★ 정정 — 이 라운드의 전제였던 "Export 범위 외"는 **폐기된 서술**이다
`ADR-005`(Export·데이터마트 범위 외)는 **`ADR-020` 으로 superseded** 됐고, ADR-020 은 *"export 산출을 저작도구 범위로 포함 + 승인 후 7개 수정경로까지 전량 재생성 확대(채택)"* 이다.
남은 범위 외는 **데이터마트 구축·검색·다운로드**뿐이다.
⚠ **후속 도메인 프롬프트에서 "Export 는 범위 외"를 확정 정책으로 주입하지 말 것.** `DFEAT-031`(내보내기·데이터마트) 이 deprecated 인 것 자체는 유지하되, 그 근거를 ADR-005 로 적은 곳은 재검토 대상이다.

### ★ 이관 사안 판정 (policy 차원 결론)

**① `API-092 POST /v1/videos/{rawSn}/resolution` 의 소유 도메인 = DOMAIN-007** (DOMAIN-003 → 재배치 권고)
근거 5중: ①저장모델이 이 도메인 소유(`ERD-011` 의 `LS_DATA_AUG`/`_LBL_MAP` 에 `AUG_TYPE_CD=RESL_*` 적재)이고 **응답이 그 모델을 그대로 노출**(`goalResCd`=AUG_TYPE_CD / 409=`UK_LS_DATA_AUG_RESL` / status=aug 라이프사이클)
②같은 리소스의 GET(`API-179`)·백필(`API-180`)이 이미 DOMAIN-007 이고, **`API-179` 는 자기 존재 이유를 "API-092 의 201 예약성공과 비동기 확정 결과를 분리 노출"로 정의** — 한 계약의 앞뒤라 쪼개면 안 됨
③도메인 책임 서술·`ubiquitous_language` 에 해상도 파생 3종 등재 ④상위 UC-003·SCREEN-022 가 DOMAIN-007 ⑤`CMP-007` 이 `VideoResolutionService` 를 자기 소관으로 서술
반대 근거(path prefix `/v1/videos/**`)는 **귀속 기준이 아니다**(API-179·180 도 같은 prefix 인데 DOMAIN-007).
⚠ `API-092` 는 `status=approved` 라 재배치는 **사용자 승인 필요**. 재배치와 무관하게 POL-009 는 별도로 고쳐야 한다.

**② `UC-010`(증강 영상 활용 여부 검수)은 링크 누락이 아니라 `DOMAIN-005` 에 귀속돼 있다** → DOMAIN-007 재배치 권고
실측: `get_neighbors(UC-010).forward` 에 `{DOMAIN-005, belongs_to_domain, strong}`. **스코프 함정(#2) 케이스가 아니다**(UC-001·002·003 은 DOMAIN-007 로 정상 연결).
근거: ①검수 대상 데이터·불변식이 전부 이 도메인 소유(`LS_DATA_AUG_RVW`·등재 게이트·유예 폐기/복구 배치 ADR-045) ②흐름 구성물 전부 DOMAIN-007(SCREEN-023·API-062/063/190·SEQ-011·TEST-003) ③DOMAIN-005 의 검수 대상은 **라벨링 작업 검수**이고 증강 결과 '활용 여부'는 별도 축 — **REVIEWER 가 행위자라는 사실만으로 귀속이 정해지지 않는다**.
⚠ 도메인 책임 경계 조정이라 사용자 결정 사항.

### policy 미확인 층 / 미적용 룰
1차 소스 grep OFF · **코드 실측 미수행**(POL-002 가 현재 코드 실태인지 반전 이전 잔재인지 ITEM 대조로만 판정 — **실제 위탁 페이로드 확인 필요**) ·
`dimensions/policy.md` 의 POL-2~POL-7 은 **타 프로젝트(관제지원) ADR 체계 기반이라 미적용**(BFF 제거·8대 이벤트·지자체 data scope·x-access-token·중계서버 방향·EV99999999) — 판정은 도메인 확정 정책 + 본 프로젝트 ADR 본문으로 수행 ·
**POL-5(인증 누락) 위반 0** — 콜백 2종만 `security=[]` 이나 `ADR-031`(무서명 + IP allowlist·rate limit·request_id 3계층)이 명시적 결정이라 위반 아님.

---

## acceptance (8건 — P1 3 / P2 5)

| ID | sev | 요지 |
|---|:--:|---|
| **D007-ACC-001** | P1 | `UC-001` 이 선언한 alternate_flows 2건(**파생본 400 · 신고 구간 412**)을 검증하는 AC **0건**. ★**형제 `AC-003` 은 같은 정책을 `and_examples` 로 이미 인코딩**(*"파생영상 대상 요청 → 400 — 파생 깊이 1 고정(영구 조건이라 재시도 여지 없음)"*) → **해상도 경로만 검증되고 증강 요청 경로는 비어 있는 비대칭** |
| D007-ACC-002 | P2 | `AC-001` 이 **prompt 5필드 필수·`PROMPT_CN` 원문 보관·중복 요청 허용(409 미발생)** 을 전혀 검증하지 않음. `AC-010` 은 조회 축에서 `PROMPT_CN` 표시만 검증 |
| D007-ACC-003 | P1 | `DFEAT-030` 검증 경로 **완전 부재** — realize UC 0 + `acceptance_rules`·`related_acceptances` 공란 + 도메인 AC 5건 어디에도 **상태 전이·실패 자동 재처리·부분성공** 검증 문장 없음 |
| D007-ACC-004 | P2 | `DFEAT-029` 도 AC 추적 경로 단절(realize UC 0 · related_acceptances 0) — **다만 내용은 AC-001/002/003 이 실질 커버**하므로 검증 공백은 아니고 **추적 경로만** 끊김 |
| **D007-ACC-005** | P1 | 도메인이 자기 선언한 REQ 4건(`REQ-001` SFR-06-03 · `REQ-003` 07-01 · `REQ-004` 07-02 · `REQ-005` 07-03)이 **어떤 AC 의 `verifies` 에도 없다**. 도메인 AC 4건은 `verifies` **키 자체가 없고**, 키를 가진 건 AC-018·AC-021(둘 다 SFR-11 계열)뿐 |
| D007-ACC-006 | P2 | `AC-018.given` = *"검수완료 영상이 있다"* — `UC-001.preconditions` 는 *"원본 영상 존재 · REVIEWER 인증"* 뿐이고 요청 게이트는 파생 여부·신고 여부 **2축**이다. **검수 상태는 요청 게이트가 아니다** |
| D007-ACC-007 | P2 | `UC-002` 가 ADR-031 로 확정한 콜백 견고성 3요소(**IP allowlist·rate limit 거부 / 고아 PENDING 만료 스윕 / DEAD_LETTER**)를 검증하는 AC 없음. `AC-002` 는 멱등(200/409)만 검증. UC `must` + AC `critical` 인데 **접근 통제 축 negative 0건** |
| D007-ACC-008 | P2 | 도메인 AC 5건 전부 **`derived_domain_ids` 공란**인데 `derived_from_use_cases` 는 채워져 있음 — **서버 자동 계산 필드**라 직접 채우면 안 되고, 대조군 `AC-021` 은 계산돼 있어 **DOMAIN-007 귀속 UC 계열에서만 미반영**으로 관측됨 → 플랫폼 이슈 후보 |

### ★ `UC-010` 귀속 — acceptance 차원 독립 확인 (policy 판정과 일치)
`get_neighbors(UC-010).forward` 에 **`DOMAIN-005 / belongs_to_domain / strong`**, DOMAIN-007 링크 없음.
그런데 `related_screens=[SCREEN-023]`(D007) · `SEQ-011`(realizes UC-010) · `TEST-003` 이 전부 D007 흐름 → **실질 D007 / 형식 D005 의 이중 귀속**.
그 결과 **`AC-010` 은 형식상 DOMAIN-005 소속**이라 **D005 acceptance 감사와 dedupe 필요**.
⚠ `AC-010` 본문은 정독했고 **확정 정책을 모두 검증하고 있어 결손 없음** — 2축 분리·CANCELED·등재 게이트=리뷰 축·해상도 예외·7일 유예 3조건 실삭제·복구=반려 되돌리기·409 재결정 금지·사유 누락 400·그랜드퍼더링.

### acceptance 차원 단서
- `API-188`(progress)·`API-189`(cancel)는 **UC 4건의 main_flow 어디에도 없어** AC 검증 대상에서 **구조적으로 빠져 있다**(취소=CANCELED 축). `API-190`(restore)만 UC-010·AC-010 에 존재. → 원인이 UC 결손이라 acceptance gap 으로 계상 안 함(coverage 소관).
- **ACC-004/005(deprecated 인용) 위반 0건** — AC-002 의 *"구 `PARENT_RAW_SN`"*·AC-003 의 *"`LS_RESOLUTION_EXPORT` 폐기"* 는 **개명·폐기를 명시한 서술**이라 위반 아님.
- AC 전건 25 활성 / 3 retired(AC-012·014·015 — 전부 비식별·개인정보 축이라 무관). **도메인 귀속 6건만 본문 정독**, 나머지 19건은 제목·slug 로 배제.

### acceptance 미확인 층
1차 소스 grep OFF · **코드 층 미대조**(AC 문구가 실제 400/412·리뷰 축 게이트와 일치하는지 설계 층만 봄) · 정적 렌더 미러 미확인.

---

## requirement (4건 — P1 1 / P2 3)

> 검증 모드 **C**(REQ + RFP + 도메인 모두 가용) — RQ-001~006 전 룰 수행, SKIP 없음.
> **도메인 귀속 REQ 6건**: `REQ-001`(SFR-06-03 해상도) · `REQ-003`(07-01 자동 생성) · `REQ-004`(07-02 라벨 무결성) · `REQ-005`(07-03 활용 여부 선택) · `REQ-020`(11-05 외부 증강) · `REQ-023`(11-09 생성 영상 라벨링).
> 귀속 근거는 `get_neighbors(FEAT-004).forward` 의 implements 6건(REQ 는 `domain_id` 미보유라 2차 경로로 판정).

- **D007-RQ-001 (P1)** `REQ-020`·`REQ-023` 이 **스스로 "REQ-003 과 동일 기능"이라 선언**하면서 `implementation` 은 `planned/0%` — 같은 FEAT-004 를 구현하는 `REQ-003`·`DFEAT-029` 는 `implemented/100%` 다. **잔존값**.
- **D007-RQ-002 (P2)** **영상 축 규모 기여가 추적되지 않는다** — 이미지 축 `REQ-025` 는 *"재난분야 이미지는 **외부 증강으로 보강**한다"* 로 명시하는데, 영상 축 `REQ-026` 본문·constraints 에 *증강*·*파생영상*·*ORGNL_RAW_SN* 어휘가 **0회**다. 도메인은 *"[★파생은 새 영상이다] 증강·해상도 모두 새 RAW_SN 을 만든다"* 고 선언하므로 **한쪽만 끊긴 비대칭**.
- **D007-RQ-003 (P2)** RFP-001(SFR-06) bullet 5 *"프롬프트 편의성 개선"* 의 **외부 위임 흔적이 소실**됐다 — 유일 대응 `REQ-002` 가 deprecated 됐고 활성 REQ 어디에도 없다. ⚠ 게다가 **`RFP-001.related_requirements` 가 폐기된 `REQ-002` 를 여전히 인용**한다(links 축 단서). bullet 1·2·3 의 비책임은 `REQ-001.rationale`·`constraints[4]` 에 이미 명시돼 있어 **bullet 5 만 빠졌다**.
- **D007-RQ-004 (P2)** 도메인 REQ 6건 전부 `acceptance_criteria` 빈 배열 — **프로젝트 레벨 dedupe 대상**. 다만 `constraints` 는 반대로 충실해(REQ-005 7건·REQ-001 8건·REQ-003 6건) **검증 기준의 소재가 constraints 로 대체된 상태**다.

### ★ requirement 차원이 확인한 "위반 0" (재조사 불필요)
- **RQ-001 갭 0** — 6건 전량 `derived_from_rfp` 보유(REQ-001→RFP-001 / REQ-003·004·005→RFP-002 / REQ-020·023→RFP-005).
- **RQ-003(divergence 미명시) 갭 0** — RFP-002 *"…자동으로 생성"* ↔ 본체 외부화(ADR-004) 의 divergence 가 `REQ-003.constraints[0]`·`REQ-020.constraints[0]` 에 명시.
- **확정 정책 7종 전건 REQ 본문 대조 — 모순 0건**(파생 깊이 1 / 중복 요청 허용 / prompt 5필드 / 두 상태축 / 등재 게이트=리뷰 행 / 유예 7일 / 업스케일 허용).
- **폐기 `ADR-005`·"Export 범위 외" 서술을 REQ 6건 + REQ-025/026 에서 전수 grep — 인용 0건**(도메인 본문에만 존재). `REQ-025.constraints` 의 *"데이터마트 구축 제외"* 는 **여전히 유효한 범위**라 갭 아님.

### requirement 차원 단서
- `API-188`·`API-189`·`API-190` 은 **대응 REQ 서술이 없다**(RFP-002 3개 bullet 어디에도 취소·진행조회 요구 없음). **RFP 미요구 설계 추가**라 RQ-004 로 올리지 않음 — coverage 소관.

---

## diagram (7건 — P0 1 / P1 5 / P2 1)

> `list_diagram_coverage` 가 확정한 DIAG-001~004(depicts 축)는 재조회하지 않았다(D007 100%·missing 없음).
> 이 차원은 **DIAG-005 본문 stale** 에 집중했고, **SEQ 4건의 mermaid `source` 원문을 전문 정독**했다.

### P0 1건

**D007-DIAG-001 (P0) — `SEQ-003` 이 폐기된 HMAC 콜백 인증을 그린다**
mermaid 원문: `EX->>ARC: 증강 결과 콜백 (**HMAC**, idempotencyKey, originAugSn, augType, status)`
`ADR-031`(approved): *"VLM 콜백·증강 콜백 모두 **무서명으로 전환한다(HMAC 제거)**. 대신 IP allowlist + rate limit + request_id 발급 게이트 3계층으로 방어하며, 증강은 IP allowlist 미설정 시 fail-closed"*
`API-165`: *"서명 필수였던 구 경로는 외부 실물 규격과 맞지 않아 **폐기**됐고 …"*
→ 같은 source 의 원장 게이트(`isIssued` → 401)는 ③ request_id 축을 **이미 정확히 그리고 있어 HMAC 토큰만 구 규격 잔재**다. description 에는 HMAC 언급이 없고 **source 한 곳에만** 남았다.

### P1 5건

| ID | 요지 |
|---|---|
| D007-DIAG-002 | `CMP-007.components[AugmentResultController].technology = **"/v1/augments"**` — 활성 콜백은 `API-165 /v1/genai/callback` 이고 `/v1/augments/result`(API-064)는 deprecated. 같은 배열의 `AugmentController` 의 `/v1/augments` 는 정상이므로 **이 한 필드만** 정정 대상 |
| D007-DIAG-003 | `CMP-007.relationships` 가 **구 동기 위탁 + ADR-045 이전 검수 모델** — `AugmentRequestService→ExternalAugmentClient` 직결, `AugmentReviewService→ExternalAugmentClient`. **같은 ITEM description 은 정반대**(EVT-011→Bridge(AFTER_COMMIT)→JobSubmitService 경유 / *"리뷰 행만 쓴다"*). SEQ-002 도 *"요청 응답 시점에 동기 외부 호출을 하지 않는다"* 를 명시 |
| D007-DIAG-004 | `CMP-007.components` 12건이 **현행 핵심 컴포넌트를 하나도 담지 않는다** — SEQ 참가자 7종(`AugmentJobSubmitService`·`AsyncAugmentFrameRunner`·`ResolutionReservationPersister`·`AsyncResolutionRunner`·`ResolutionPersistService`·`AugmentDiscardService`·`AugmentDiscardPurgeSweeper`) 중 **CMP-007 에 있는 것 0건**. 해상도 축은 SEQ-004 에 없는 이름(`VideoResolutionPersister`)으로만 남아 명칭 드리프트 |
| D007-DIAG-005 | `CDIAG-010` 에 **`LS_DATA_AUG_DSCD` 대응 클래스 없음** — ERD-011 은 그 테이블(21컬럼)과 관계(*"폐기된 증강 요청(소프트삭제 원장)"*)를 선언하고, CDIAG-010 자신의 description·SEQ-011 이 폐기 라이프사이클을 상세히 그리는데 **클래스 모델에만 없다**(엔티티 3 vs 테이블 4) |
| D007-DIAG-006 | `CDIAG-010.DataAugmentation.attributes` 14건에 **`promptCn`·`newRawSn` 누락** — 같은 ITEM description 이 *"결과물 구분의 **유일한 축**은 PROMPT_CN"*, *"NEW_RAW_SN 이 NULL 인 그랜드퍼더링 증강은 실삭제 대상에서 완전 제외"* 라 **그 두 컬럼에 의존**하는데 모델에 없다 |

### P2 1건
- **D007-DIAG-007** `CDIAG-010.description` 이 모델 범위 근거로 **superseded `ADR-005`** 를 인용 — *"(LS_DATA_SET 은 **Export 범위 외 확정**으로 제외)"*. **결론(테이블 제외)은 ERD-011 과 일치하나 근거가 뒤집힌 서술**이라 읽는 사람이 export 산출을 범위 밖으로 오독한다. ⚠ **같은 문구가 `ERD-011.description` 에도 복제**돼 있다

### ★ diagram 차원이 보류·기각한 것 (판단 근거 기록)
- **`LS_DATA_AUG_JOB` 이 SEQ 4건·CMP-007 에 없다** — 입력 단서대로 재현 확인했으나 **ERD-011 에도 그 테이블이 없어 다이어그램 축의 진실원이 성립하지 않는다** → 다이어그램 갭으로 계상 안 함. **schema 차원의 `D007-SCH-002`(테이블 미등재)가 상류**다.
- **`SEQ-011` 이 취소(CANCELED) 축을 안 그린다** — `API-189` 가 실재하나 CANCELED 가 **리뷰 축인지 생성 결과 축인지 ERD code_values 로 판별되지 않아**(양쪽 다 3값) 오탐 위험으로 **보류**.
- 기보고 6건(AugProcStatus 검수 상태·CANCELED / accept·reject 메서드 / "해상도 저하" / attribute planned / RESOLUTION / depicts 공란)은 **전부 재확인했으나 중복 계상 안 함**.

### ★★ 도메인 밖으로 나가는 스윕 권고 — **ADR-031 HMAC 잔재 전 범위 확인**
`SEQ-003` 에서 HMAC 이 나왔으므로 **다른 도메인의 콜백 시퀀스에도 같은 잔재가 있을 수 있다**.
ADR-031 은 **VLM 콜백(`/v1/vlm/callback`)까지 함께** 무서명으로 전환했으므로 **이미 완료된 D004·D005(VLM 축)와 앞으로 볼 D016(관제 통지)** 에서 재확인이 필요하다. → 최종 REPORT 의 소급 확인 항목.

### diagram 차원 단서
- `CDIAG-010.referenced_items=[ADR-018]` 뿐인데 본문은 ADR-023·044·045 인용 / `CMP-007.referenced_items` 는 **빈 배열**인데 본문이 ADR-023·044·045·EVT-011 인용 → **참조 링크 미선언**(links 소관).
- `CMP-007.components` 12건 **전부 `implementation.status=planned`/0%** 인데 DFEAT-029·SEQ 4건은 implemented — CDIAG-010 attribute 에서 이미 보고된 패턴이 **CMP-007 에도 재현**(별개 ITEM 이라 diagram 축에서 미계상).
- PROMPT_CN 신설 버전 표기가 **CDIAG-010 = V147 / ERD-011 = V153** 로 갈림(STL-015 와 같은 사안).

### diagram 열람 범위 (미검출을 부재로 읽지 않도록)
`CDIAG-010`(v6, 전체 — classes 6 · attributes 14/12/9 · methods 4/2/1 · enum_values 6/3/3 · relationships 5) ·
`CMP-007`(v3, 전체 — components 12 + 각 implementation · relationships 19 · external_dependencies 5) ·
**`SEQ-002`·`SEQ-003`·`SEQ-004`·`SEQ-011` 의 mermaid `source` 원문 전문 정독** ·
보조: ERD-011 전체 · ADR-031/005/020 · API-165 · API-092 · EVT-011 · SEQ 전건 14 목록.
⚠ `API-165` 를 projection 으로 조회했더니 `request` 필드가 반환되지 않아 **`request_id` ↔ `LS_DATA_AUG_JOB.IDMP_KEY` 인용 관계는 직접 확인 못 함**.

---

## content (17건 — P0 2 / P1 9 / P2 6)

> 방법: **REST `kit-export` 로 서버 원문 26 ITEM 을 디스크에 직접 덤프(420,704 bytes)** 후 전 필드 재귀 평탄화 기계 스캔.
> projection 미사용. **문자열 3,002건**(skeleton 미러·`change_summary` 제외), 한글 포함 897건, **중첩 배열 경로 1,468건**.

### P0 2건 — 본문이 확정 사양과 정면 모순

**D007-CNT-001 (P0) — `API-062.description` = *"증강 결과를 수락해 **새 영상(RAW_SN)으로 생성**한다"*** (전문 44자)
`SCREEN-023.purpose`: *"★결정은 새 영상을 만드는 행위가 아니다 — 파생영상은 **웹훅 수신 시점에 이미 생성**되어 있고 … 검수 행만 쓴다"*
`UC-002.main_flow[2]`: 생성 주체가 **콜백(API-165)** 임을 확정.

**D007-CNT-002 (P0) — `API-062`·`API-063` 의 `augProcSttsCd` 를 *"검수 상태 코드"* 로 서술** — 도메인이 *"합치지 말 것"* 이라 못박은 두 축을 본문이 그대로 합침.
`CDIAG-010` 이 그 혼동의 실제 결과를 기록: *"웹훅이 항상 먼저 도착해 aug 행을 ACCEPTED 로 옮기는데, 검수 전이는 PENDING 에서만 허용돼 REVIEWER 의 승인·반려가 **영구히 409 로 막혀 있었다**"*.

### P1 9건

| ID | 요지 |
|---|---|
| **D007-CNT-003** | **한글 표기 손상 2건 확정** — `API-179` *"그 **개**을 메운다"*(→갭을, `change_summary` 에는 *"메우는 **갭**"* 이 보존돼 있어 대조로 확정) · `API-165` *"앞단을 **비콜간** 요청도"*(→비껴간). 도메인 전체에서 정확히 2건 |
| D007-CNT-004 | `API-061.parameters[0]` = *"증강 작업 식별자"* 인데 **같은 ITEM 응답이** *"요청한 jobId 와 같은 값 … 원본(부모) 영상 식별자"* 라 규정. 형제 `API-059` 는 *"잡 ID (= videoId = 원본 영상 RAW_SN)"* 로 이미 명시 |
| D007-CNT-005 | `DFEAT-030` 이 **도메인 어디에도 없는 상태명**으로 서술 — *"가능→대기→진행→완료/부분성공/실패"*. 실제 축은 `AUG_PROC_STTS_CD`(ACCEPTED/REJECTED/CANCELED)·`RVW_STTS_CD`·API enum(REQUESTED/IN_PROGRESS/COMPLETED/FAILED)이며 *"가능·대기·진행·부분성공"* 은 **어떤 enum·컬럼·화면에도 없다** |
| D007-CNT-007 | `UC-001` 에 **자기 리비전 서술 2 + 지시문 1 + V번호 8 + 날짜메모 1 + 개정메모 2** 누적(*"이전 본문은 … 적었으나 하루 뒤 전면 철회됐다"*, *"되살리지 말 것"*). **동일 내용이 이미 `brownfield.notes` 에 정상 보관**돼 본문 중복. `main_flow[1].action` 에 내부 클래스명 |
| D007-CNT-008 | `UC-002` — V82 2 · 날짜메모 3 · 구 서술 대조 1 · 개정메모 2. **중첩 배열까지**(`main_flow[2].action`, `alternate_flows[4].steps[1]`) |
| D007-CNT-009 | `ERD-011.description` **첫 문장이 레포 경로 + V번호 15개**(*"진실원: backend/db/migration(V7·…·V158)"*) — **설계서가 스스로 자립 불가를 선언**하는 서술. 중첩 포함 **V번호 44건** · `@Transient` 4 · 구현상태(*"빌드 PASS"*) |
| D007-CNT-010 | `API-060.responses.200` = *"**외부 미연동 단계이므로** jobId 는 placeholder"* — 현재 사실도 아니다(`INT-008` 이 구현 완료 기록). **`SCREEN-022.purpose` 가 같은 값을 구현상태가 아닌 계약으로 정확히 서술**하고 있어 그것이 정본 |
| D007-CNT-011 | ★`API-165.change_summary` 가 *"내부 클래스명과 감사 이력을 걷어낸다"* 고 **선언했는데 중첩 필드에 잔존** — `responses.401`·`request_body.…output_file_path`·`responses.400`. **D001 `API-006`·D004 `API-123` 과 동일 축의 재발** |
| D007-CNT-012 | `CMP-007.description` **산문** 안에 내부 클래스명 10종 + `external_dependencies` 에 패키지 경로. ⚠ **`components[].name`·`relationships.from/to` 는 사양 어휘라 오염 아님**(경로별로 기계 분리해 판정) |
| D007-CNT-013 | `INT-006`·`INT-008`·`EVT-011`·`CDIAG-010` 에 클래스명·**코드 파일경로**(*"코드: augment/event/….java (record)"*)·**파일:라인**(*"application.yml:542-547"*)·자기 리비전 대조·V번호 |

### P2 6건
`D007-CNT-006` API-061/062/063 만 **한 줄 일반론**(28·44·25자, 형제 7건은 206~837자 `[헤더] 설명` 구조) — 특히 API-061 은 **페이징 2축·resultState 8종**이라는 가장 복잡한 계약인데 본문에 전무 ·
`D007-CNT-014` `SCREEN-022` note 에 **`data-testid` 4건**(형제 SCREEN-023 은 0건이라 관례도 어긋남. ⚠ 같은 note 의 `aria-checked`·로빙 tabindex 는 **접근성 사양이라 유지**) ·
`D007-CNT-015` DOMAIN-007(V126) · UC-003(날짜범위·구 가드 제거·개정메모) ·
`D007-CNT-016` `API-190.status` 가 **어느 축인지 안 밝혀** 바로 위 *"미결정으로 돌아간다"* 와 모순처럼 읽힘 ·
`D007-CNT-017` `API-063.responses.400` 에 `(@NotBlank/@Size)` — 형제 API-189/190 은 어노테이션 없이 사유만 적어 관례 어긋남

### ★★★ 한글 손상 검사 — **①② 만으로는 이 도메인의 실제 손상 2건을 둘 다 놓친다** (방법론 정정)
- ① 알려진 오타 15종 grep → **유효 검출 0건**(원시 hit 2건은 `API-190` *"표식이 찍힌"* 정상 어휘 오탐)
- ② 희귀 음절 빈도 분석(음절 23,313자 / 고유 514종 중 **2회 이하 116종 전수 문맥 확인**) → **유효 검출 0건**
- **구조적 사각**: 손상 결과가 **고빈도 음절**(`개`·`콜`)이면 희귀도에 걸리지 않는다. *갭→개* · *비껴간→비콜간* 이 정확히 그 경우다.
- **실제로 잡은 보강 스캔 2종**:
  **(a) 1음절 어간 + 목적격 조사(을/를) 패턴** — 정당 단음절 명사 whitelist 제외 → `개을` 검출
  **(b) 낱말 고립도 분석**(단발 출현 + 동족 어형 0건) → `개을`·`비콜간` 둘 다 검출
- ⇒ **이후 전 도메인의 content 차원에 (a)(b)를 필수로 추가할 것.**

### 오염 스캔 실적 (경로 기반 오탐 제거 포함)
유효: V번호 **58건**(ERD-011 44 · UC-001 8 · UC-002 2 · INT-006 2 · CDIAG-010 1 · DOMAIN-007 1) · 산문 내 클래스명(CMP-007 10 · INT-006 5 · INT-008 4 · EVT-011 4 · API-165 2 · CDIAG-010 1) ·
어노테이션 7 · 구현상태 4 · 코드 파일경로 3 · 자기 리비전 3 · 지시문 1 · 개정메모 12 · `data-testid` 4 · **외부 IP/포트 0**.
**오탐 기각**: 커밋해시 후보 27건 = 전부 `project_id` UUID 조각 · `CMP-007.components[].name` 12·`relationships` 32 = 사양 어휘 · `SCREEN-022` *"PII 를 입력하지 말 것"* = **사용자 안내 문구이지 편집 지시문 아님** · `MOD-013/018.file_path` = 그 타입의 정규 필드.

### content 미확인 층
1차 소스 grep OFF · **정적 렌더 미러 미확인**(SCREEN-022/023 렌더에 같은 오염·손상이 복제됐는지 — `source_hash` 대조 필요) ·
retired 본문이 활성 ITEM 을 오염시켰는지 미대조 · `MOD-013/018` 의 코드 식별자는 **code_module 타입 특성상 오염으로 판정 안 함**(기준이 다르면 재판정) ·
AC 5건 본문 미스캔(도메인 귀속 미확정).

---

## test_scenario (9건 — P1 6 / P2 3)

> 도메인 귀속 TEST 는 **`TEST-003` 1건**. `steps[]` 전문(seq 1~5) 정독 완료.

### ★★ 폐기 모델 대조 9항 — **전부 현행. 폐기 모델을 검증하는 step 0건** (P0 축 해당 없음)
①콜백 경로 `POST /v1/genai/callback` 현행, **HMAC 인용 0건** ②두 상태축을 **정확히 분리 검증**(`steps[4].expected`: *"RVW_STTS_CD=ACCEPTED … 생성 결과 축은 불변 — 콜백이 남긴 ACCEPTED 그대로(검수로 전이되지 않는다)"*)
③새 영상 등록은 seq3(콜백 시점)이고 seq5 accept 는 리뷰 행만 씀 ④`steps[0].note` *"중복 요청은 차단하지 않는다(ADR-044)"* — 409 기대 0건 ⑤prompt 5필드 원문 + PROMPT_CN 보관 검증
⑥파생 깊이 1·RESL 차단·업스케일은 happy path 라 미포함(폐기 인용 아님) ⑦구 `WITHHELD` 인용 0건 ⑧`ORGNL_RAW_SN` 명시 사용 ⑨"Export 범위 외" 서술 0건
→ **`SEQ-003` 은 HMAC 잔재가 있었는데 TEST-003 은 깨끗하다** — 같은 도메인 안에서도 정정 스윕이 산출물마다 갈렸다.

### P1 6건

| ID | 요지 |
|---|---|
| D007-TST-001 | **해상도 변경(`UC-003`) 통합시험 0건** — `TEST-003.covers_use_cases` 에 없고, `notes` 가 오히려 *"좌표를 배율로 재계산하는 것은 해상도 파생(RESL_*) 축이며 **이 시나리오 범위 밖**"* 이라 명시적으로 밀어냈다. 좌표 배율 재계산·업스케일·전부 스킵 400 어느 것도 시험 산출물에 없음 |
| D007-TST-002 | **반려→폐기→유예 7일 실삭제→복구의 비가역 파괴 흐름이 명시적으로 제외** — `steps[4].note` *"활용 검수 UC-010(**반려는 본 편 제외**)"*. `UC-010`(must)은 반려 이후를 본류로 규정하고 전용 원장 `LS_DATA_AUG_DSCD` 도 실재하는데 검증 0 |
| D007-TST-003 | **진행조회·취소가 구조적으로 검증 대상 밖** — `SCREEN-023` 에 전용 절이 있고 `consumes_apis` 에 API-188·189 가 있는데 **UC 4건의 flow 어디에도 progress/cancel 언급 0건** |
| **D007-TST-004** | ★**콜백 step 페이로드가 현행 계약과 어긋나 그대로 수행하면 400** — `status="SUCCESS"`(현행 enum 은 `RUNNING/SUCCEEDED/FAILED`) + **단일 영상 경로 문자열**(현행은 `results[]` 배열, SUCCEEDED 시 필수) |
| D007-TST-005 | **실사고 반전 지점 2단계가 미검증** — `UC-002.main_flow[4]`(부모 비식별본 **실제 복사** + `DE_IDNTF_FILE_PATH_NM` 값을 읽어 경로 결정, **부모 원본 경로로 대체 금지**)·`[6]`(메타 1회 계승). `steps[2].expected` 는 `RAW_SN·ORGNL_RAW_SN·DATA_STTS_CD` 3컬럼만 확인 → **파일 부재·PII 원본 경로 유입(CWE-359)을 잡는 지점이 없다** |
| D007-TST-006 | `NFR-009`(품질관리 기준, D004·D005·**D007**·D010 공유)를 검증하는 **kind=system TEST 0건**(전역 5건 전부 integration) — 프로젝트 레벨 dedupe |

### P2 3건
- **D007-TST-007** `steps` 가 4개 엔드포인트를 인용하는데 `related_apis` 공란(API-060·165·062·059) → auto_fixable
- **D007-TST-008** `preconditions` **선행 순번이 한 칸씩 밀렸다** — steps[2](seq3)가 *"순번1 검증 통과"*(실제 선행 seq2), steps[3](seq4)가 *"순번2 새 영상 등록"*(실제 seq3). steps[1] 은 올바르게 seq1 을 가리켜 **내부 불일치임이 드러남** → auto_fixable
- **D007-TST-009** step 제목이 *"진위 검증"* 인데 `expected` 에 **ADR-031 3계층 중 무엇도 없다**(200·ACCEPTED·멱등 흡수만). 응답 계약의 `applied` 필드도 미확인. **폐기 모델은 아니고 검증 항목 미상세**

### test_scenario 차원 단서
- `TEST-003.stale=true`(사유 *"SCREEN-023 data.sections 변경"*)인데 그 뒤 **SCREEN-023 이 2026-08-14 v34 로 route 를 재변경**해 **stale 재발** 가능(TEST-003 은 08-07 이 최신).
- **`item.status='approved'` ↔ `data.status='draft'` 두 축이 어긋난다** — TEST-003 + UC-001/002/003/010 **동일 패턴**. 전역 규약 확인 필요.
- content 축: `TEST-003.notes`·`steps[0].expected` 에 1차 화면 코드 `SC-022`·`SC-023` 과 `V82` 잔존.
- `API-165` 가 `request_id` 를 `LS_DATA_AUG_JOB.IDMP_KEY` 로 규정하나 **그 테이블이 ERD-011 에 없다** — 단 TEST-003 의 `LS_DATA_AUG WHERE IDMP_KEY` 는 **실재 컬럼이라 유효**하므로 TST 갭 아님(schema 소관).

---

# ✅ DOMAIN-007 완료 — 10/10 차원 · **109건 (P0 11 / P1 66 / P2 32)**

| 차원 | 건수 | P0 | P1 | P2 |
|---|---:|---:|---:|---:|
| coverage | 7 | 1 | 5 | 1 |
| links | 14 | 0 | 11 | 3 |
| schema | 15 | 3 | 10 | 2 |
| stale | 15 | 2 | 7 | 6 |
| policy | 13 | 2 | 9 | 2 |
| acceptance | 8 | 0 | 3 | 5 |
| requirement | 4 | 0 | 1 | 3 |
| diagram | 7 | 1 | 5 | 1 |
| content | 17 | 2 | 9 | 6 |
| test_scenario | 9 | 0 | 6 | 3 |
| **계** | **109** | **11** | **66** | **32** |

## D007 최종 요약 — P0 11건의 공통 뿌리

**① "설계 문서가 공표한 것이 실물과 다르다" (schema 3)** — 없는 컬럼을 "잔존"으로 공표 / 실제 소유 테이블 2종이 ERD 에 통째 부재 / `SRC_SN` 참조 대상 오기(그대로 조인하면 다른 행)
**② "폐기된 두 상태축 병합이 계약·본문에 그대로" (stale 1 · policy 1 · content 2)** — `API-062`·`API-063`. **ADR-045 가 되돌린 결함(REVIEWER 승인이 영구 409)을 재생산하는 계약**
**③ "1차 baseline 고착" (stale 1)** — `DFEAT-030` 이 도메인 어디에도 없는 상태명으로 서술
**④ "폐기 인증 방식이 다이어그램에 잔존" (diagram 1)** — `SEQ-003` 의 HMAC
**⑤ "활성 API 전건 orphan" (coverage 1)** — 6개 도메인 연속 재현, 프로젝트 레벨 구조 결함

## D007 소급 확인 항목 (다른 도메인·완료분으로 나감)
- **ADR-031 HMAC 잔재 전 범위 스윕** — VLM 콜백도 함께 전환됐으므로 **완료된 D004·D005 소급 + D016 확인**
- **`API-092` 를 DOMAIN-003 → DOMAIN-007 재배치** (사용자 결정, `status=approved`)
- **`UC-010` 을 DOMAIN-005 → DOMAIN-007 재배치** (사용자 결정) — 그 결과 `AC-010` 판정도 D005 와 dedupe 필요
- **`API-065`(VLM 콜백)의 `implements_features=[FEAT-004]` 오지목** — D005 `MOD-010` 과 같은 계열, 원인 공통 가능성
