# E 클러스터 part1 — E-1 증강 요청(18) + E-2 증강 검수(23) = 41건

> 검증일 2026-08-02 · 대상 `docs/test-cases/E-augment-resolution-export-meta.md` §E-1, §E-2
> 스택: `klid-backend`(:18081`/api`) · `klid-mock-server`(:9400) · `klid-postgres`(schema `public`) · 코드 `56d30478`
> ⚠ **stack-bringup.md 의 "컨테이너가 V146 구버전" 기록은 이 시점에 무효**다 — `flyway_schema_history` 실측 결과
> **V158 까지 2026-08-01 23:10 에 적용 완료**(V153~V158 포함)이며 backend 컨테이너도 그 스택으로 재기동돼 있다.
> 따라서 E 클러스터의 "환경 버전 격차 BLOCKED" 지침은 적용하지 않고 **전건 실동작 검증**했다.

## 0. 검증 환경·근거 수집 방식

| 항목 | 실측 |
|---|---|
| Flyway | `V158 add ls data aug dscd lookup index` (2026-08-01 23:10:15) — V153 `add_ls_data_aug_prompt_and_drop_active_unique` 적용됨 |
| 외부 위탁 배선 | `AUGMENT_EXTERNAL_MODE=http`, `AUGMENT_API_BASE_URL=http://klid-mock-server:9400` |
| 외부 왕복 실증 | mock-server 로그 `POST /api/genai/jobs 202` → `job_id=37ccff39af18…` → `POST http://klid-backend:8080/api/v1/genai/callback 200` (self-fill 아님) |
| 자동테스트 baseline | backend 4,755 tests / 실패 0 (`test-baseline.md`) — 아래 인용 테스트는 전부 통과분 |

**self-fill 판정**: 증강 요청 경로에 self-fill 결함 **없음**. `OTSD_JOB_ID` 는 요청 시점 NULL 로 적재되고
mock-server 의 202 응답이 발급한 `9d4e286c28d4483595383617f1ef5b06` 류 값으로만 채워진다(DB 실측).
`LS_DATA_AUG_JOB.TOT_NOCS`(5/30) 도 외부 응답 기준이다.

### 이번 회차 최대 발견 — 중복 증강 차단 정책이 **반전**됐다

의뢰 프롬프트의 "★중복 증강 요청 차단(409·부분 유니크 인덱스)" 확증 지시는 **2026-07-30 시점 카탈로그/문서에 근거**한 것인데,
2026-07-31 사용자 확정으로 **정반대로 뒤집혔다**. 현행 정본은 워크스페이스 `CLAUDE.md:243`:

> **★중복 증강 요청은 BE 가 차단하지 않는다 — 구 "요청 1회 = 파생영상 1건" 계약 폐기 (2026-07-31 사용자 확정, 구속)**
> … ①서비스 사전 조회 가드 ②409 응답·안내 문구 ③부분 유니크 인덱스 `UK_LS_DATA_AUG_ACTVTN` ④`LsDataAug.ACTIVE_STATUSES` 를 **모두 제거**했다.

실동작·DB 로 확증:
```
# 이미 ACCEPTED WINTER 가 있는 rawSn=4 에 같은 WINTER 재요청
POST /api/v1/augments/request {"videoIds":[4],"types":["WINTER"],"prompt":{...}}
→ HTTP 200 {"jobId":1785593419430,"videoCount":1,"typeCount":1,"createdCount":1}   # 409 아님

# 인덱스 실측 (pg_indexes where tablename='ls_data_aug')
ix_ls_data_aug_new_raw_sn / ls_data_aug_pkey / idx_ls_data_aug_src / idx_ls_data_aug_stts /
idx_ls_data_aug_src_regdt / uk_aug_idempotency_key / uk_aug_external_job_id / uk_ls_data_aug_resl
→ uk_ls_data_aug_actvtn **부재**, uk_ls_data_aug_resl(해상도 전용)만 존치
```
따라서 **TC-AUG-012 / 013 / 014 는 무효(폐기 대상)** 이며 결함으로 보고하지 않는다.
`VERIFY-PROMPT.md §2`의 우선순위(케이스표 < UNCERTAINTIES < CLAUDE.md)에 따라 CLAUDE.md 를 정본으로 판정했다.
회귀 가드도 반대 방향으로 이미 서 있다 — `AugmentRequestConflictTest`
(`같은_영상_같은_종류로_두_번_요청해도_모두_성공한다`, `채택된_증강이_있어도_같은_종류로_다시_요청할_수_있다`,
`해상도파생_유니크_UK_LS_DATA_AUG_RESL_은_유지되고_활성중복_유니크만_사라진다`, `동시_증강_요청_2건이_모두_성공한다`).

---

## 1. E-1. 증강 요청 (18건)

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|:--:|---|---|
| TC-AUG-001 | PASS | [실동작] | `POST /v1/augments/request {videoIds:[4],types:["WINTER"],prompt:{5필드}}` → **200** `{"jobId":1785593419430,"requestedAt":"2026-08-02T08:43:37.729","videoCount":1,"typeCount":1,"createdCount":1}`. DB: `data_aug_sn=14, src_sn=1, aug_type_cd=WINTER, idmp_key=AUG-8486e35e-2c27-492d-90e6-8962a4cbe81b`, 요청 시점 `otsd_job_id=NULL`(외부 202 후 `37ccff39…` 로 채워짐). ⚠ **입력 계약 드리프트** — 카탈로그 입력열에 없는 `prompt` 5필드가 **필수**다(없으면 400). → E-ISSUE-02 |
| TC-AUG-002 | PASS | [실동작] | rawSn=50(미승인) → **400** `{"data":{"blockedVideoIds":[50]},"errorCode":"NOT_REVIEWED"}`, aug 행 0건. 근거 라인 `:127-137` → 실제 `AugmentRequestService.java:167-177` |
| ~~TC-AUG-003~~ | — | — | **폐기 케이스**(2026-07-30) — 검증 대상 아님, 분모 제외 |
| TC-AUG-004 | PASS | [실동작] | rawSn=43(APPROVED·프레임 0건) → **412** `{"data":{"skippedVideoIds":[43]},"message":"프레임이 추출되지 않은 영상은 증강을 요청할 수 없습니다."}`. `AugmentRequestService.java:196-204` |
| TC-AUG-005 | PASS | [실동작] | WORKER 토큰 → **403** FORBIDDEN(컨트롤러 `@PreAuthorize("hasRole('REVIEWER')")` `AugmentController.java:122` 1차). 서비스 2차 `requireReviewer` `:447-454` 정적 확인 |
| TC-AUG-006 | PASS | [실동작] | 토큰 없음 → **401** UNAUTHORIZED |
| TC-AUG-007 | PASS | [실동작+정적] | `IDMP_KEY=AUG-<uuid>`(40자 ≤64, `^[A-Za-z0-9_-]+$` 충족) 3건 모두 확인. `OTSD_JOB_ID` 는 `createRequested(..., null, prompt)` 로 **null 적재**(`:375-376`) 후 mock 202 값으로 반전. 형식 위반 시 INTERNAL_ERROR `:411-417` |
| TC-AUG-008 | PASS | [정적] | `createOneAugmentRequest` generic catch → `false` → 호출자 `INTERNAL_ERROR("증강 요청을 생성하지 못했습니다.") + skippedVideoIds`(`:212-215`, `:402-407`). 200 으로 삼키지 않음. 테스트 `AugmentRequestConflictTest#제약_위반이_아닌_적재_실패는_종전대로_INTERNAL_ERROR`. ⚠ 단 `DataIntegrityViolationException` 은 **409** 로 분기(`:384-401`) — 이 분기는 중복차단이 아니라 `IDMP_KEY` UNIQUE·FK 충돌용 |
| TC-AUG-009 | PASS | [정적+실동작] | `AugmentRequestBridge.java:101-103` `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`. `LS_WEBHOOK_IDEMPOTENCY` 선기록 **없음**(코드 주석 `:30` 및 grep 무결과), 발급 원장은 `LS_DATA_AUG_JOB.IDMP_KEY`(실측 `AUG-…-1`). 테스트 `AugmentRequestServiceTest#요청트랜잭션_롤백시_증강행도_위탁도_남지_않는다` |
| TC-AUG-010 | PASS | [정적+실동작] | `AugmentCallbackUrlResolver.resolve()` — blank→기본, trailing slash 제거 후 `+ CALLBACK_PATH`. `WebhookProtectedPaths.java:50 PATH_GENAI_CALLBACK="/v1/genai/callback"`. mock 로그: `webhook sent url=http://klid-backend:8080/api/v1/genai/callback status=200` |
| TC-AUG-011 | PASS | [실동작] | 연속 3요청 jobId `…430 / …431 / …432` — `AtomicLong`(base=기동시각 ms) 단조증가, DB 미영속(어느 컬럼에도 없음). ⚠ 이 값은 `/{jobId}/result` 가 기대하는 jobId(=원본 RAW_SN)와 **다른 값 공간** → E-ISSUE-05 |
| TC-AUG-012 | N/A | [실동작] | **정책 반전으로 무효.** PENDING 중복 존재 시 409 안내를 내지 않는다. 사전 조회 가드·안내 문구 코드 전량 삭제(`AugmentRequestService.java:68-73` javadoc 이 폐기를 명시). 실측: 동일 (영상×종류) 재요청 200 |
| TC-AUG-013 | N/A | [실동작] | **정책 반전으로 무효.** ACCEPTED WINTER(aug 1·9)가 있는 상태에서 WINTER 재요청 → 200, aug 14 생성 |
| TC-AUG-014 | N/A | [실동작] | **정책 반전으로 무효.** `UK_LS_DATA_AUG_ACTVTN` 은 V153 에서 `DROP INDEX IF EXISTS` — `pg_indexes` 실측 부재. 최종 방어 자체가 설계상 존재하지 않는다 |
| TC-AUG-015 | PASS | [실동작] | REJECTED 후 재요청 200 확인(src 45 RAIN REJECTED 상태에서 동종 재요청 성공). ⚠ 단 지금은 PENDING·ACCEPTED 도 허용되므로 이 케이스의 **변별력이 소멸**했다(`ACTIVE_STATUSES` 상수도 제거됨 — `LsDataAug.java:65` 주석) |
| TC-AUG-016 | PASS | [실동작] | rawSn=8(`DE_IDENT_YN='F'`, APPROVED) → **412** `{"data":{"blockedVideoIds":[8]},"message":"비식별 재처리 대기 중인 영상은 증강을 요청할 수 없습니다."}`. 판정 단일 원천 `DeidentReportGate::isUnderDeidentReport`(`:183-193`). **게이트 순서 반증**: rawSn=8 은 프레임도 0건인데 신고 사유가 먼저 나옴 → 2-1(신고) < 3(프레임) 순서 정합 |
| TC-AUG-017 | PASS | [실동작] | `videoIds:[4,26]` → 400 `"videoIds: 영상은 한 번에 1건만…"`(DTO 1차). `types:["NIGHT","RAIN"]` → 400. 서비스 2차 `requireSingleSelection`(`:307-312`) `size()!=1` fail-closed 정적 확인 |
| TC-AUG-018 | PASS | [정적] | `V143__add_ls_data_aug_active_unique.sql:45` `RAISE EXCEPTION 'V143 중단: … ACCEPTED 증강이 2건 이상인 조합이 %건 …'` + `WITH ranked` PENDING 만 종결. `flyway_schema_history` 에 V143 적용 성공. ⚠ **런타임 효력은 V153 DROP 으로 소멸**(케이스는 과거 1회성 마이그레이션 검증이라 성립 자체는 유지) |

### E-1 반증 시도로 확인한 **카탈로그 미수록 동작**(전부 정상 동작 — 갭은 카탈로그 쪽)

| 추가 확인 | 실동작 |
|---|---|
| 파생영상 증강 요청 차단(파생 깊이 1 고정, `CLAUDE.md:245`) | rawSn=18(`ORGNL_RAW_SN=4`) → **400** `"파생 영상은 증강 요청 대상이 아닙니다."` + `skippedVideoIds`. `requireNotDerivative` `:335-345` |
| 오라클 방지 순서 | WORKER 가 파생(18) 요청 → **403**(파생 사유 노출 없음). 인가 → 파생 → 미검수 순서 정합 |
| 미존재 rawSn 응답 계약 보존 | rawSn=999999 → **400 NOT_REVIEWED**(404 아님). `requireNotDerivative` 가 `video==null` 을 통과시키는 의도된 설계(`:336-339`) |
| prompt 누락 | `prompt` 없음 → 400 `"prompt: prompt 는 필수입니다."` |
| prompt 공백 필드 | `time:"  "` → 400 `"prompt.time: prompt.time 은 필수입니다."` |
| prompt 길이 상한 | `time` 51자 → 400 `"prompt.time 은 50자를 넘을 수 없습니다."` |
| prompt 원문 보관 | `PROMPT_CN = {"time":"NIGHT","season":"WINTER","weather":"RAIN","terrain":"ROAD","severity":"HIGH"}` — 외부 전송본과 동일 dict 직렬화(`:279-286`) |
| types allowlist | `types:["RESL_720P"]` → 400(enum 바인딩 실패). `RESL_` 네임스페이스 침범 차단 확인 |

---

## 2. E-2. 증강 검수 (23건)

> ⚠ **축 분리(2026-07-31 확정, `CLAUDE.md:252`)를 판정에 반영했다.**
> `LS_DATA_AUG.AUG_PROC_STTS_CD` = **생성 결과 축**(웹훅/finalize 소유),
> `LS_DATA_AUG_RVW.RVW_STTS_CD` = **사람의 사용/폐기 결정 축**(검수 소유).
> accept/reject 는 더 이상 `AUG_PROC_STTS_CD` 를 건드리지 않는다(실측: aug 14·22 는 검수 전후 모두 `ACCEPTED`).
> 카탈로그 기대결과의 "PENDING→ACCEPTED"·"`applyReviewStatus`" 서술은 구 구조 기준이다 → E-ISSUE-03.

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|:--:|---|---|
| TC-AUG-020 | PASS | [실동작] | `POST /v1/augments/14/accept` → **200**. `LS_DATA_AUG_RVW` 신규 1행 `(rvw_sn=2, data_aug_sn=14, data_raw_sn=4, data_src_sn=1, rvw_stts_cd=ACCEPTED, rvw_id=1001)`. `LS_DATA_AUG.AUG_PROC_STTS_CD` 는 ACCEPTED 유지(축 분리로 **의도된 무변경**). `syncDecision` best-effort try/catch(`:366-371`). 응답 `augProcSttsCd` 는 검수행 상태를 싣는 별도 매핑(`AugmentSummaryResponse:38-39`) |
| TC-AUG-021 | PASS | [실동작] | 승인 후 재승인 → **409** `"이미 처리된 증강 검수입니다. status=ACCEPTED"`. 재반려도 동일 409. ⚠ 기제·메시지 변경 — 근거 `LsDataAug.java:264 applyReviewStatus` → 실제 `LsDataAugRvw.java:241 ensurePending` |
| TC-AUG-022 | PASS | [실동작] | `reason:""` → 400 `"reason: 반려 사유는 필수입니다."`(DTO 1차). `reason` 미포함(`{}`) → 400 동일. 서비스 2차 `:391-393` 정적 확인 |
| TC-AUG-023 | PASS | [실동작] | `POST /v1/augments/22/reject {"reason":"QA 검증용 반려"}` → 200. RVW `rvw_stts_cd=REJECTED, rjct_rsn='QA 검증용 반려'`. **추가(카탈로그 미수록)**: `LS_DATA_AUG_DSCD` 폐기 표식 1행 `(data_aug_sn=22, new_raw_sn=79, orgnl_raw_sn=26, dscd_rsn=…)` 동일 tx 기록(`:397`) |
| TC-AUG-024 | PASS | [실동작] | dataAugSn=5(`RESL_1080P`) accept → **400** `"해상도 파생 결과는 검수 대상이 아닙니다."`. `loadOrThrow` `:474-476` |
| TC-AUG-025 | PASS | [실동작] | 동일 행 reject → 400 동일 메시지(`loadOrThrow` 공용 게이트) |
| TC-AUG-026 | PASS | [실동작] | dataAugSn=99999 accept → **404** `"증강 결과를 찾을 수 없습니다."` |
| TC-AUG-027 | PASS | [실동작] | backend 로그 `[Augment] decision sync skipped (외부 계약 미정의) dataAugSn=22 decision=REJECTED` — `HttpExternalAugmentClient.syncDecision` 이 no-op(로그 후 true). throw 분기는 **프로덕션 도달 불가**(카탈로그 ⚠ 그대로 확인). 본 tx 영향 없이 200 |
| TC-AUG-028 | PASS | [실동작] | WORKER accept → 403 / 미인증 accept → 401. 서비스 2차 `requireReviewer` `:522-529` |
| TC-AUG-029 | PASS | [실동작] | `GET /v1/augments?page=0&size=20` → `totalElements=3`, content 3그룹(jobId 26 / 18 / 4). SRC_SN 그룹 MIN(REG_DT) DESC. 일괄 조회 4종(`findBySrcSnIn` / `findRawSnBySrcSnIn` / `findCctvNamesByRawSns` / `findByDataAugSnIn`) 정적 확인 → 그룹당 추가 쿼리 없음 |
| TC-AUG-030 | PASS | [실동작] | jobId 26 → `types:["RAIN"]`, `resolutionTypes:["RESL_1080P","RESL_720P","RESL_480P"]` 분리 노출. `isResolutionDerivative` `:225-227` |
| TC-AUG-031 | PASS | [실동작] | jobId 18 → `["WINTER","NIGHT","RAIN"]`(AUG_ORDER 1·2·3). resolutionTypes → `1080P→720P→480P`(5·6·7). 미정의 99 폴백 정적 확인 `:220` |
| TC-AUG-032 | PASS | [실동작] | jobId 4 그룹에 `dead_letter_at IS NOT NULL`(aug 3) → `"status":"FAILED"`. **실경로 도달 확인** — 카탈로그 E-ISSUE-06 해소 상태 유지 |
| TC-AUG-033 | PASS | [실동작] | jobId 18 → COMPLETED, `completedAt=2026-07-31T03:23:18.713868`(=max RVW_DT). jobId 26(반려 직후 전 행 terminal) → COMPLETED, `completedAt=2026-08-02T08:45:14.978815`(=aug22 RVW_DT) |
| TC-AUG-034 | PASS | [실동작] | **IN_PROGRESS**: jobId 26 에 aug23(PENDING) 유입 시점 `"status":"IN_PROGRESS","completedAt":null`. **REQUESTED**: DB 주입 `(src_sn=2, WINTER, PENDING)` 단독 그룹 → `?srcSn=2` → `"status":"REQUESTED"` |
| TC-AUG-035 | PASS | [실동작] | DB 주입 `(src_sn=3, RESL_720P, PENDING)` → `?srcSn=3` → `types:[]`, `resolutionTypes:["RESL_720P"]`, `"status":"REQUESTED"` — **COMPLETED 아님**. `markResolutionGenerated` 로 ACCEPTED 전이 후에만 terminal(`LsDataAug.java:340-349`) |
| TC-AUG-036 | PASS | [실동작] | `/4/result` → `"status":"FAILED"` (**FAILED 분기 실도달**), `/26/result` → `"PROCESSING"`, 전부 terminal 그룹 → `"COMPLETED"`. 매핑 `:133-137` |
| TC-AUG-037 | PASS | [실동작] | `/99999/result` → `"status":"PROCESSING"`, `results:[]`, `totalElements:0`. `findByOriginalRawSn` 무결과 → `findBySrcSnIn` 폴백 → 전무 시 PROCESSING(`:125-132`) |
| TC-AUG-038 | PASS | [실동작+정적] | 전 그룹 `cctvName` non-null(`"CCTV-강남구-001"`). 폴백 `CCTV_NAME_FALLBACK="(이름 없음)"` + blank 검사 `:288-291` |
| TC-AUG-039 | PASS | [실동작] | `?srcSn=999999` → `totalElements:0, content:[], empty:true`(200) |
| TC-AUG-040 | PASS | [실동작] | `?size=101`(srcSn 미지정) → **400** `"size 한도 초과 (max=100)"`. `?srcSn=1&size=101` → **200**(srcSn 분기가 `AugmentController.java:94-98` 에서 size 검증보다 앞섬) — 카탈로그 ⚠ 그대로 재현. 추가 관찰: `findBySource` 는 page/size 를 아예 무시하고 해당 srcSn 전체 row 반환(1 srcSn 당 행 수로 한정되어 실질 위험 낮음) → E-ISSUE-08 |
| TC-AUG-041 | PASS | [실동작] | DB 주입 `(data_aug_sn=900003, src_sn=99999999(미존재 프레임), ACCEPTED)` → accept → **409** `"증강 결과의 원본 영상 정보를 확인할 수 없어 검수를 기록할 수 없습니다."`. `resolveRawSnOrThrow` `:435-445`. 하드코딩 `0L` 센티널 없음(RVW 행 미생성 확인) |
| TC-AUG-042 | PASS | [실동작] | DB 주입 `(aug 900004, src_sn=99999998(미존재))` + **선재 PENDING 검수행** → accept → **200**(역해석 미수행). RVW 행 재사용되어 `rvw_stts_cd=ACCEPTED, data_raw_sn=4` 그대로. `loadOrCreateReview` `:415-421` `findLatestByDataAugSn` 우선 |

### E-2 반증 시도로 확인한 **카탈로그 미수록 동작**

| 추가 확인 | 실동작 |
|---|---|
| 생성 미완료 증강의 결정 차단(`requireGeneratedResult`, 2026-07-31 DEV_FIX HIGH) | 생성 실패/취소 종결 행(aug 13·3) accept → **409** `"생성에 실패했거나 취소된 증강은 사용 여부 결정 대상이 아닙니다. 필요하면 다시 요청해 주세요."`. 생성 중(PENDING)은 별도 문구 409(`:503-520`) |
| accept/reject 동시성 직렬화 | `findByDataAugSnForUpdate`(행 잠금)으로 read-then-act 직렬화(`:468-470`). 회귀 가드 `AugmentDecisionConcurrencyIT#같은_증강에_동시_채택과_반려가_들어와도_검수행은_1건이고_결정도_1건이다` (baseline 통과) |
| 반려 로그 PII | `[Augment] rejected dataAugSn=22 actor=1001 reasonLen=9` — 사유 원문 미기록(CWE-359 방어) ✔ |
| 목록 인가 | `GET /v1/augments` 는 WORKER 도 200(설계상 `hasAnyRole('REVIEWER','WORKER')`). 노출 필드는 cctvName/types/status 로 PII 없음 |

---

## 3. 집계

| 구간 | 대상 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| E-1 (폐기 TC-AUG-003 제외) | 17 | 14 | 0 | 0 | 0 | **3** | 0 |
| E-2 | 23 | 23 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **40** | **37** | **0** | **0** | **0** | **3** | **0** |

- 폐기 1건(TC-AUG-003)은 분모 제외.
- N/A 3건(TC-AUG-012/013/014)은 **미구현 갭이 아니라 사용자 확정 정책 반전**이므로 FAIL 승격하지 않는다.
- **기능 결함 0건.** 아래 이슈는 전부 **카탈로그·문서 정합** 축이다.

---

## 4. 이슈

### [E-ISSUE-01] TC-AUG-012 / TC-AUG-013 / TC-AUG-014 (+015·018 변별력) — 중복 증강 차단 케이스가 폐기된 정책을 검증하고 있다
- **심각도**: HIGH (카탈로그 정합 — 다음 회차에서 거짓 FAIL 3건을 만든다)
- **기대 동작(기대효과)**: 케이스 카탈로그는 현행 확정 정책만 검증해야 한다. 폐기된 정책을 검증하면 다음 검증자가 "409 가 안 나온다 → 결함"으로 오판하고, 이 프로젝트가 명시적으로 금지한 **"철회된 정책 재시도"** 사이클(조상/자손 전파 4라운드 전례)이 재발한다.
- **현재 동작(이슈 내용)**: 카탈로그(2026-07-30)는 409 차단 + DB 부분 유니크 최종 방어를 기대하나, 2026-07-31 사용자 확정으로 전량 제거됐다.
  - `CLAUDE.md:243` — *"★중복 증강 요청은 BE 가 차단하지 않는다 … ①서비스 사전 조회 가드 ②409 응답·안내 문구 ③부분 유니크 인덱스 `UK_LS_DATA_AUG_ACTVTN` ④`LsDataAug.ACTIVE_STATUSES` 를 모두 제거했다. … ⚠ 되돌리기 금지"*
  - `V153__add_ls_data_aug_prompt_and_drop_active_unique.sql` — `DROP INDEX IF EXISTS UK_LS_DATA_AUG_ACTVTN;`
  - `AugmentRequestService.java:68-73` javadoc — *"사전 조회 가드·안내 문구·제약 위반 409 분기·인덱스(V153 DROP)를 모두 제거했다."*
  - `LsDataAug.java:65` — *"폐기 이력 — 구 ACTIVE_STATUSES(PENDING·ACCEPTED) 상수는 제거됐다 (2026-07-31)."*
- **재현/확인 경로**:
  ```bash
  # 1) 이미 ACCEPTED WINTER 가 있는 rawSn=4 에 동일 요청 → 200
  curl -s -X POST localhost:18081/api/v1/augments/request -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' \
    -d '{"videoIds":[4],"types":["WINTER"],"prompt":{"time":"A","season":"B","weather":"C","terrain":"D","severity":"E"}}'
  # 2) 인덱스 부재 확인
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select indexname from pg_indexes where tablename='ls_data_aug';"   # uk_ls_data_aug_actvtn 없음
  ```
- **영향**: 기능 영향 없음(현행 동작이 정본과 일치). 검증 프로세스 영향 — 다음 회차 거짓 FAIL 3건 + 정책 되돌리기 위험.
- **수정 방향(제안)**: `E-augment-resolution-export-meta.md` §E-1 에서 TC-AUG-012/013/014 를 `~~취소선~~` + `**[폐기 2026-07-31]** 중복 차단 정책 폐기(CLAUDE.md:243 / V153 DROP), 대체 케이스 = "동일 (영상×종류) 재요청 200"` 으로 전환. TC-AUG-015 는 "REJECTED 후 재요청 허용"에서 "**모든 상태에서 재요청 허용**"으로 기대결과 갱신. TC-AUG-018 기대결과에 "V153 이후 인덱스 부재 — 과거 마이그레이션 가드 검증용" 각주 추가. `UNCERTAINTIES.md` ★ 절에 4번째 확정 정책으로 등재 권장(되돌리기 금지 관례 명문화).

### [E-ISSUE-02] TC-AUG-001 — 요청 입력 계약에 필수 `prompt` 5필드가 누락돼 있다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 케이스의 입력열이 그대로 실행 가능해야 한다. 그렇지 않으면 검증자가 400 을 받고 "요청 API 결함"으로 오판한다.
- **현재 동작(이슈 내용)**: 2026-07-31 부터 `prompt`(time/season/weather/terrain/severity) 5필드가 **전부 필수**다. `AugmentRequestRequest.java` `@NotNull(message="prompt 는 필수입니다.") @Valid PromptFields prompt` + 각 필드 `@NotBlank @Size(max=50)`. 서비스도 `buildPrompt`(`AugmentRequestService.java:247-258`)로 fail-closed 재확인하고 `VisibleTextNormalizer` 로 보이지 않는 문자까지 제거한다. 카탈로그 입력열 `{videoIds:[N], types:["WINTER"]}` 로 보내면 **400**.
- **재현/확인 경로**: `curl … -d '{"videoIds":[4],"types":["WINTER"]}'` → `400 {"message":"prompt: prompt 는 필수입니다.","errorCode":"INVALID_INPUT"}`
- **영향**: 기능 영향 없음. 카탈로그 재현성 훼손.
- **수정 방향(제안)**: TC-AUG-001 입력열에 `prompt` 5필드 추가, 기대결과에 `PROMPT_CN` 원문 적재 단언 추가. `CLAUDE.md:242` 가 이미 정본을 서술하고 있으므로 케이스만 맞추면 된다. 아울러 E-ISSUE-04 의 신규 케이스 6건과 함께 반영.

### [E-ISSUE-03] TC-AUG-020 / TC-AUG-021 — 검수 축 분리(2026-07-31) 이전 구조를 기대·근거로 삼고 있다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 기대결과가 실제 소유 컬럼을 가리켜야 한다. 지금 표현대로면 검증자가 `LS_DATA_AUG.AUG_PROC_STTS_CD` 의 무변경을 "전이 실패 = 결함"으로 오판한다.
- **현재 동작(이슈 내용)**: accept/reject 는 더 이상 `aug.applyReviewStatus(...)` 를 부르지 않는다. `AugmentReviewService.java:344-350` javadoc — *"★ 생성 결과 컬럼(`AUG_PROC_STTS_CD`)은 건드리지 않는다 (2026-07-31 확정) … 두 축을 다시 합치지 말 것"*. 재결정 차단은 `LsDataAugRvw.ensurePending`(`:241`)이 담당하며 메시지도 `"이미 처리된 증강 검수입니다. status=ACCEPTED"` 로 바뀌었다. 실측: aug 14 accept 전후 모두 `aug_proc_stts_cd=ACCEPTED`, aug 22 reject 후에도 `ACCEPTED`.
  근거 라인도 전부 이동: `AugmentReviewService.java:336-354 → 359-374`, `LsDataAug.java:264 → LsDataAugRvw.java:241`.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST localhost:18081/api/v1/augments/22/reject -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"reason":"x"}'
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select aug_proc_stts_cd from ls_data_aug where data_aug_sn=22;          -- ACCEPTED (무변경)
        select rvw_stts_cd from ls_data_aug_rvw where data_aug_sn=22;"          -- REJECTED
  ```
- **영향**: 기능 영향 없음. 오판 위험 + 근거 드리프트.
- **수정 방향(제안)**: TC-AUG-020 기대결과를 *"`LS_DATA_AUG_RVW.RVW_STTS_CD` PENDING→ACCEPTED, `LS_DATA_AUG.AUG_PROC_STTS_CD` **무변경**(생성 결과 축)"* 으로, TC-AUG-021 근거를 `LsDataAugRvw.ensurePending` 으로 교체. 응답 `augProcSttsCd` 가 검수행 상태를 싣는다는 점(`AugmentSummaryResponse.from`)도 명시.

### [E-ISSUE-04] E-1/E-2 — 2026-07-31~08-01 신설 동작 8종이 카탈로그에 없다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 신설 보안·정합 가드는 회귀 감시 대상이어야 한다. 케이스가 없으면 다음 회차에 조용히 사라져도 아무도 모른다.
- **현재 동작(이슈 내용)**: 아래 8종이 실동작으로 확인되나 E-1/E-2 어느 행에도 없다.
  1. **파생영상 증강 요청 차단** — `requireNotDerivative`(`AugmentRequestService.java:335-345`) → 400 `"파생 영상은 증강 요청 대상이 아닙니다."` (`CLAUDE.md:245` 파생 깊이 1 고정)
  2. **가드 순서 계약** — 인가(403) → 파생(400) → 미검수(400) → 신고(412) → 프레임(412). 파생 가드가 APPROVED 검증보다 **앞**이어야 한다는 명시 계약(`:157-164`)
  3. **미존재 rawSn 응답 계약 보존** — 404 아닌 400 NOT_REVIEWED(`:328-334`)
  4. **prompt 필수/공백/50자/보이지 않는 문자** 4종 검증
  5. **PROMPT_CN 원문 보관 = 전송본과 동일 dict**(`:276-286`)
  6. **types enum 이 prompt 로부터 파생되지 않음** (CWE-22 · `RESL_` 침범 차단)
  7. **`requireGeneratedResult`** — 생성 미완료/실패·취소 증강의 accept·reject 차단(409, 2문구 분기, `AugmentReviewService.java:503-520`). 이 가드가 없으면 파생영상 등재 게이트가 무력화된다
  8. **reject → `LS_DATA_AUG_DSCD` 폐기 표식** 동일 tx 기록(`:397`)
- **재현/확인 경로**: 본 문서 §1·§2 "카탈로그 미수록 동작" 표의 각 명령.
- **영향**: 회귀 감시 공백. 특히 7번은 "사람이 이미지를 보고 결정해야 작업목록에 올라간다"는 상위 요구를 지키는 가드라 유실 시 요구 위반이 조용히 발생한다.
- **수정 방향(제안)**: E-1 에 5건(1·2·3·4묶음·5/6), E-2 에 2건(7·8) 신규 케이스 추가. 7번은 우선순위 High.

### [E-ISSUE-05] TC-AUG-011 — 요청 응답 `jobId` 와 결과 조회 `{jobId}` 가 서로 다른 값 공간이다
- **심각도**: LOW
- **기대 동작(기대효과)**: 같은 이름의 식별자는 같은 값 공간이어야 한다. 아니면 클라이언트가 요청 응답으로 결과를 폴링할 수 없고, 잘못 쓰면 **다른 영상의 결과**를 보게 된다.
- **현재 동작(이슈 내용)**: 요청 응답 `jobId` 는 `AugmentRequestService:127 jobIdSeq = new AtomicLong(System.currentTimeMillis())` 의 미영속 placeholder(실측 `1785593419430`)인 반면, `GET /v1/augments/{jobId}/result` 의 경로변수는 **원본 RAW_SN**(`aggregateResultStatus` → `findByOriginalRawSn(jobId)`, `AugmentReviewService.java:121-138`)이다. 실측 `/1785593419430/result` 계열은 `PROCESSING` + `results:[]` 로 조용히 빈 결과를 준다(404 아님).
- **재현/확인 경로**: 요청 응답의 jobId 로 `GET /v1/augments/{그 값}/result` → `{"status":"PROCESSING","results":[],"totalElements":0}`
- **영향**: 기능 오작동은 현재 없음 — FE 가 이미 명시적으로 회피한다(`frontend/src/pages/AugmentRequestPage.tsx:189-192` *"⚠ 응답의 `jobId` 로 이동하지 않는다 (Critical — 다시 되돌리지 말 것)"*). 그러나 계약상 이름이 같아 신규 소비자(관제·타 FE)가 반복해서 밟을 함정이며, 오사용 시 **다른 영상의 결과 조회**로 이어질 수 있다(CWE-639 유사).
- **수정 방향(제안)**: ①요청 응답 필드명을 `requestSeq` 등으로 바꾸거나 ②응답에 `resultJobId`(=rawSn)를 추가해 폴링 대상 식별자를 명시. 코드 변경 없이 가면 최소한 Swagger 설명과 TC-AUG-011 기대결과에 "이 jobId 로는 결과 조회 불가"를 못박을 것.

### [E-ISSUE-06] 문서 — `CLAUDE.md` 의 증강 마이그레이션 번호가 실제와 다르다
- **심각도**: LOW
- **기대 동작(기대효과)**: 정본 문서의 마이그레이션 번호는 실제 파일과 일치해야 한다(되돌리기 절차의 근거로 참조되므로).
- **현재 동작(이슈 내용)**: `CLAUDE.md:242` *"`LS_DATA_AUG.PROMPT_CN`(**V147**)"*, `CLAUDE.md:243` *"`UK_LS_DATA_AUG_ACTVTN`(V143 → **V147 에서 DROP**)"*, *"절차는 V143/**V147** 주석"* — 실제 두 변경은 모두 **`V153__add_ls_data_aug_prompt_and_drop_active_unique.sql`** 에 있고, `V147` 은 `create_ls_data_ingest.sql`(관제 인입 테이블)로 무관하다.
- **재현/확인 경로**: `ls backend/src/main/resources/db/migration/ | grep -E "V147|V153"` + `grep -n "PROMPT_CN\|DROP INDEX" backend/src/main/resources/db/migration/V153*.sql`
- **영향**: 되돌리기 절차 참조 시 엉뚱한 파일을 열게 된다.
- **수정 방향(제안)**: `CLAUDE.md:242-243` 의 `V147` 3곳을 `V153` 으로 정정.

### [E-ISSUE-07] E-1/E-2 전 행 — 근거 `file:line` 드리프트
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 라인이 실제 코드를 가리켜야 검증 비용이 낮아진다.
- **현재 동작(이슈 내용)**: 확인한 주요 드리프트 — `AugmentRequestService.java` `127-137→167-177`(미검수), `143-153→183-193`(신고), `156-164→196-204`(프레임), `176-181→212-215`·`336-341→402-407`(적재 실패), `285-290→307-312`(단건), `311-317,344-351→371-376,411-417`(멱등키), `381-388→447-454`(RBAC). `AugmentReviewService.java` `336-354→359-374`, `363-365→391-393`, `421-431→468-479`, `389-395→415-421`, `409-419→435-445`, `433-440→522-529`, `255-259→257-270`, `81-89,148-170→83-91,150-172`. `LsDataAug.java:264 applyReviewStatus` → **메서드 자체가 `applyGenerationResult`(:412)로 개명**되고 재결정 가드는 `LsDataAugRvw.java:241` 로 이동.
- **재현/확인 경로**: 각 파일 Read 후 대조.
- **영향**: 검증 효율 저하. 특히 `applyReviewStatus` 는 이름조차 사라져 grep 이 무결과다.
- **수정 방향(제안)**: E-1/E-2 근거열 일괄 갱신.

### [E-ISSUE-08] TC-AUG-040 — `?srcSn=` 분기가 size 한도 검증과 페이징을 모두 우회한다
- **심각도**: LOW
- **기대 동작(기대효과)**: 목록 API 는 어떤 분기로 들어와도 페이징·상한이 적용돼야 한다(`rules/api-design.md` "페이징 없는 목록 전체 조회 금지").
- **현재 동작(이슈 내용)**: `AugmentController.java:94-98` 이 `srcSn != null` 을 **size 검증보다 먼저** 처리하고, `AugmentReviewService.findBySource`(`:101-108`)는 `findBySrcSnOrderByAugTypeCd(srcSn)` 로 해당 srcSn 의 **전 행을 무제한 조회**한 뒤 `PageRequest.of(0, jobs.size())` 로 감싼다. 실측 `?srcSn=1&size=101` → 400 이 아니라 200.
- **재현/확인 경로**: `curl "localhost:18081/api/v1/augments?srcSn=1&size=101" -H "Authorization: Bearer $REV"` → 200
- **영향**: 실질 위험은 낮다 — 반환량이 "한 대표프레임에 걸린 증강 행 수"로 제한된다. 다만 **중복 요청 허용(2026-07-31)으로 같은 (영상×종류) 행이 무한히 누적될 수 있게 된 이상, 이 상한 부재의 성격이 달라졌다**(과거엔 `UK_LS_DATA_AUG_ACTVTN` 이 사실상 상한이었다). CWE-770.
- **수정 방향(제안)**: `srcSn` 분기에도 size 검증을 선행 적용하고 `findBySource` 에 `Pageable` 을 전달. 카탈로그 TC-AUG-040 의 ⚠ 각주에 "중복 허용 이후 누적 상한이 사라졌다"는 배경을 추가.

---

## 5. 작업 흔적 · 원복

| 항목 | 처리 |
|---|---|
| 수동 DB 주입 (`data_aug_sn` 900001~900004, `data_aug_rvw_sn` 900004) | **삭제 완료** — `DELETE FROM ls_data_aug_rvw WHERE data_aug_rvw_sn>=900000` / `DELETE FROM ls_data_aug WHERE data_aug_sn>=900000`, 잔여 0건 확인. ⚠ 삭제 시 `ls_data_aug` 가 5행 반환(주입은 4행) — 900000+ 구간에 다른 병렬 검증 에이전트의 테스트 행이 1건 섞여 있었을 가능성. 900000 미만 실데이터는 무영향 |
| API 로 생성된 데이터 (`ls_data_aug` 14·22·23, 파생 RAW 75·79·82 및 그 프레임/검수행, `ls_data_aug_dscd` 1행) | **보존** — 정상 시나리오 실구동 산출물이며 다른 클러스터(E-3/E-3B/D) 검증이 참조 중일 수 있어 삭제하지 않음 |
| 코드·설정·테스트 파일 | **무수정** (본 결과 파일 1개만 생성) |
| 빌드/테스트 실행 | **미실행** (baseline 인용만) |

### 병렬 실행 주의
검증 중 `data_aug_sn=23`(rawSn 26, WINTER, 08:45:49)이 본 에이전트가 보내지 않은 요청으로 생성됐다 —
다른 병렬 검증 에이전트가 같은 스택에 증강 요청을 보내고 있다. 목록·집계 스냅샷은 그 시점 값이며
재실행 시 숫자가 달라질 수 있다(판정 근거인 **상태 규칙**은 불변).
# E 클러스터 part2 — 증강 결과 웹훅(E-3) + 증강 외부 위탁(E-3B)

- **담당 범위**: `docs/test-cases/E-augment-resolution-export-meta.md` § **E-3**(TC-AUG-050~078, 29행) + § **E-3B**(TC-AUG-100~121, 22행) = **51행**
- **검증 일자**: 2026-08-02 (회차 폴더는 2026-08-01/1차)
- **검증 기준 커밋**: `56d30478` (워킹트리 qa-0801)
- **이슈 ID 범위**: E-ISSUE-21 ~ E-ISSUE-40

## 0. 환경 실측 — stack-bringup.md 의 "구버전 스택" 단서는 **무효**(스택이 최신으로 갱신됨)

`stack-bringup.md` 말미는 "klid-backend 가 2026-07-31 빌드(Flyway V146)라 증강 폐기/복구 등 V153~V158 기능은 BLOCKED" 로 기록돼 있으나, **본 검증 시점의 실측은 다르다**:

| 항목 | 실측 | 명령 |
|---|---|---|
| backend jar | `/app/app.jar` **Aug 1 14:05** 빌드 | `docker exec klid-backend ls -la /app/app.jar` |
| Flyway | **V158** 까지 success (`add ls data aug dscd lookup index`) | `select version,description,success from flyway_schema_history order by installed_rank desc` |
| V153+ 신규 API 노출 | `/v1/augments/{id}/cancel`·`/progress`·`/restore` 전부 존재 | `GET /api/v3/api-docs` |
| 신규 소스 반영 | `AugmentJobSuccessApplier`·`AugmentSubmitOutcomeRecorder` 등 Phase C-3 빈이 런타임에서 동작(로그 `augment-submit-1` 스레드 관측) | `docker logs klid-backend` |

→ **E-3/E-3B 전 범위를 실동작으로 검증했다.** BLOCKED 는 0건이다.

### 외부 연동 실경유 확인 (self-fill 아님)

```
AUGMENT_EXTERNAL_MODE=http                       (noop 아님 — 실 HTTP 위탁)
AUGMENT_API_BASE_URL=http://klid-mock-server:9400
WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0         (명시 — fail-closed 기본값을 의도적으로 열어 둠)
STORAGE_EXTERNAL_READ_ROOTS=/app/genai-out       (읽기 축 별도 설정)
STORAGE_RAW_MOUNT_ROOTS=/app/storage/raw,/app/storage/deidentified
```

실측 왕복 로그(mock-server):
```
[MOCK][GENAI] job accepted job_id=9d4e286c28d4483595383617f1ef5b06 request_id=AUG-5c247d0a-...-1 mode=I2I inputs=5
POST /api/genai/jobs HTTP/1.1 202 Accepted
POST http://klid-backend:8080/api/v1/genai/callback "HTTP/1.1 200"   ← 4회(RUNNING×3 + SUCCEEDED)
```
→ 증강 위탁·콜백이 **전부 mock-server(:9400) 를 실제로 경유**했고, 파생 프레임 바이트가 목업 산출물과 **완전 동일**(아래 TC-AUG-114 근거)하므로 **self-fill 결함 0건**이다.

### 정상 시나리오 실구동 (본 검증의 기준 데이터)

`POST /v1/augments/request {videoIds:[26], types:["WINTER"], prompt:{...}}` (REVIEWER) → 200
→ `LS_DATA_AUG` #23 PENDING → 위탁 1청크(`request_id=AUG-5c247d0a-...-1`) → mock 202(`job_id=9d4e...`)
→ 콜백 RUNNING×3 → SUCCEEDED → 롤업 → **aug #23 ACCEPTED, `NEW_RAW_SN=82`**
→ 파생 `LS_DATA_RAW` 82 (`ORGNL_RAW_SN=26`, `AUG_TYPE_CD=WINTER`) + 프레임 5건 + 비디오 복사본 생성 완료.

---

## 1. 판정 요약

| 구분 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 | 폐기(분모 제외) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| E-3 (TC-AUG-050~078) | 29 | 23 | 0 | 1 | 0 | 0 | 0 | **5** |
| E-3B (TC-AUG-100~121) | 22 | 22 | 0 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **51** | **45** | **0** | **1** | **0** | **0** | **0** | **5** |

- 유효 분모 46건 기준 PASS율 **97.8%** (PASS 45 / PARTIAL 1)
- **실동작[실동작] 판정 26건**, 정적+단위테스트 근거[정적] 20건
- **근거 file:line 드리프트 13건** — Phase C-3(논블로킹 제출) 리팩터 + `AugmentJobSuccessApplier` 분리로 카탈로그의 근거 위치가 어긋났다(§4 참조). 동작 결함은 아니나 카탈로그 정합성 결함으로 집계한다.
- **self-fill 결함 0건** — 파생 프레임 바이트가 목업 산출물과 완전 일치, 외부 `job_id` 를 자체 발급하지 않음, `Idempotency-Key` 실전송을 목서버 원장으로 역검증.
- **케이스 판정과 별개로 발견한 결함 2건**(E-ISSUE-25 만료 회수 영구 고착 / E-ISSUE-26 앱·DB 시계 9시간 불일치) — 어느 케이스의 기대결과에도 없던 축이라 반증 과정에서 드러났다.

---

## 2. E-3. 증강 결과 웹훅 — `POST /v1/genai/callback` (29행)

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|:--:|---|---|
| TC-AUG-050 | PASS | [실동작] | 실요청→목서버 위탁→콜백 SUCCEEDED 롤업으로 `LS_DATA_AUG` #23 `ACCEPTED`, 신규 `LS_DATA_RAW` **82** 생성(`ORGNL_RAW_SN=26`). `RAW_FILE_PATH_NM=/app/storage/deidentified/videos/augment/26/82/WINTER.mp4` = **자기 비식별 사본 경로**(부모 원본 경로 폴백 없음). AFTER_COMMIT `AsyncAugmentFrameRunner` 기동 로그 `starting augment frame re-extraction rawSn=82` 확인. ⚠ 관측 시점 `DATA_STTS_CD=COMPLETED`/`DE_IDNTF_YN='Y'` 인데, 이는 케이스가 적은 동기 커밋 시점 값(PENDING/'N')이 async 확정으로 정상 전이된 결과다(코드 주석·`AugmentExtractPersist` 계약과 일치) |
| TC-AUG-051 | PASS | [실동작] | job 2건 중 seq1=FAILED, seq2 성공 콜백 → 롤업 `rollup failed (partial failure — fail-closed) dataAugSn=770001 jobCount=2 failedJobSeqs=[1]` → aug **REJECTED + `RTRY_NMTM`=1 + `DEAD_LETTER_AT`=2026-08-02 08:48:32**, `NEW_RAW_SN` null(영상 미생성) |
| TC-AUG-052 | PASS | [실동작] | ①job 레벨 멱등: 종결(SUCCEEDED) job 재전송 → `200 {"applied":false}` ②aug 레벨 1차 앵커: 종결 aug 에 전 job 성공 롤업 → `200 {"applied":false}`, 중복 영상 0건 |
| TC-AUG-053 | PASS | [실동작] | ② **다른 증강 보유** → `409 CONFLICT "이미 다른 증강 결과에 인계된 작업 ID 입니다."`. 로그 `otsd_job_id 선점 충돌(다른 증강 보유) — 409 종결 dataAugSn=770003 ownerDataAugSn=770001`. **쓰기 이전** 선점검사라 500(PG 25P02) 아님 — E-ISSUE-05 해소 확인. ① 자기 자신 보유 재수신은 1차 앵커가 200 으로 흡수(위 052) |
| ~~TC-AUG-054~~ | — | — | **폐기(분모 제외)** |
| TC-AUG-055 | **PARTIAL** | [실동작]+[정적] | ① 미발급 request_id → **401** `"발급되지 않은 request_id 입니다."` 실동작 확인 ② **`job 은 있으나 aug 행 없음 = 404` 분기는 실행 불가능한 dead branch** — `fk_ldaj_data_aug ... ON DELETE CASCADE` 로 aug 삭제 시 job 이 함께 삭제되므로 그 상태가 성립하지 않는다 → **E-ISSUE-21** |
| ~~TC-AUG-056~~ | — | — | **폐기(분모 제외)** — 정책 반전(★1) |
| TC-AUG-057 | PASS | [실동작] | 부모 판정 실패(`origin frame not found`) → `ParentGate.FAIL` → aug **REJECTED + `RTRY_NMTM`=1 + `DEAD_LETTER_AT`**, 파생영상 0건. 로그 `result failed — parent unavailable dataAugSn=770006 reason=origin frame not found`. ※ "프레임 0건" 그 자체는 srcSn 이 실재하면 성립할 수 없어(프레임이 있어야 srcSn 이 있다) 같은 `FAIL` 분기의 도달 가능한 형태로 검증했다 |
| ~~TC-AUG-058~~ | — | — | **폐기(분모 제외)** |
| ~~TC-AUG-059~~ | — | — | **폐기(분모 제외)** |
| TC-AUG-060 | PASS | [실동작] | `status:"CANCELED"` → `400 "status 는 RUNNING\|SUCCEEDED\|FAILED 중 하나여야 합니다."` |
| TC-AUG-061 | PASS | [실동작] | `request_id:"AUG-bad!key"` → 400 / `job_id:".."` → 400(`^(?!\.+$)[A-Za-z0-9_.:-]+$` — 카탈로그 표기보다 **강한** 점-전용 세그먼트 차단이 추가돼 있다, CWE-22) |
| ~~TC-AUG-062~~ | — | — | **폐기(분모 제외)** |
| TC-AUG-063 | PASS | [실동작] | 마지막 job 콜백 **동시 3건** 발사 → 응답 `applied:true` **정확히 1건** + `applied:false` 2건. `LS_DATA_RAW where ORGNL_RAW_SN=26` 5→**6건**(증가 1) — 증강영상 정확히 1건. `findByDataAugSnForUpdate` 선잠금 + PENDING 앵커 이중 방어 실증 |
| TC-AUG-064 | PASS | [실동작] | 부모 `DE_IDNTF_YN='N'`(raw 50) → `ParentGate.FAIL` → aug REJECTED + dead-letter, 파생 0건. 로그 `reason=parent has no deident artifact`. 판정 단일 헬퍼 `LsDataRaw.hasDeidentArtifact()`(`LsDataRaw.java:444-446` — `"Y"\|"F"` 통과) 확인 |
| TC-AUG-065 | PASS | [정적] | `GenAiWebhookIpAllowlist.java:44-77` — 미설정/`none` → `allowed` 빈 리스트 → `isAllowed()` **항상 false**(fail-closed, VLM 과 반대). 단위테스트 4건 전량 통과(baseline). ※ 런타임 실효값이 `0.0.0.0/0` 이라 live 403 재현은 설정 변경이 필요해 수행하지 않음(§10 절대규칙) |
| TC-AUG-066 | PASS | [정적] | `GenAiIntegrationWiringGuard.java:65-76` `verify()` — `mode=http` + allowlist `none`/빈값 → `IllegalStateException`(기동 실패). 단위테스트 6건(문서 docker 형상·env.template 회귀 포함) 통과 |
| TC-AUG-067 | PASS | [실동작] | 보유 `externalJobId`≠콜백 `job_id` → `409 "job_id 가 일치하지 않습니다."`, job 상태 미변경 |
| TC-AUG-068 | PASS | [실동작] | `status:"RUNNING", progress:50` → `200 {"applied":true}`, job `JOB_STTS_CD` `RECEIVED→RUNNING`, aug 는 PENDING 유지(결과처리·롤업 미수행) |
| TC-AUG-069 | PASS | [실동작] | `/etc/passwd` · `/app/genai-out/../../etc/passwd` · **`/app/genai-outEVIL/x.jpg`(접두사 형제)** · **상대경로 `genai-out/../../etc/passwd`** 4형태 전부 `400 "output_file_path 가 허용된 저장 경로가 아닙니다."` + **job 상태 `RECEIVED` 그대로**(재전송 여지 보존) + 응답에 경로 원문 미노출. 메트릭 `AugmentMetrics.REASON_OUTPUT_PATH`(`AugmentMetrics.java:26`) 배선 확인. ⚠ **근거 드리프트**(§4) |
| TC-AUG-070 | PASS | [정적]+[실동작] | `VideoArtifactRootResolver.java:100,108` — `external-read-roots` 기본 빈값 → `readableRoots = allowedRoots`(쓰기 축과 동일, fail-closed). 실효값은 `/app/genai-out` 로 **명시** 확장돼 있고, 그 밖 경로는 위 069 실동작대로 전부 400 |
| TC-AUG-071 | PASS | [실동작] | 위탁 `LS_DATA_AUG_JOB_FILE` 2건 vs `results` 1건 → job `FAILED` / `ERR_CD=RESULT_COUNT_MISMATCH` / `ERR_MSG_CN="위탁 2건 대비 수신 1건"`. 이어진 롤업이 부분실패 규칙으로 증강도 실패 확정(위 051) |
| TC-AUG-072 | PASS | [실동작] | `results` 필드 부재 · `results:[]` 양쪽 **400**, 확정 안 됨. ⚠ 메시지 문구가 카탈로그 기대값과 다름(`"SUCCEEDED 콜백에는 results 가 필요합니다."` → 실제 `"SUCCEEDED 결과에는 results 가 필요합니다."`) → **E-ISSUE-22**(LOW, 카탈로그 정정 대상) |
| TC-AUG-073 | PASS | [실동작] | 2청크 중 1청크만 종결 → `200 {"applied":false}` + 로그 `rollup deferred dataAugSn=910001 pendingJobSeqs=[2]`. job 상태 갱신(FAILED)은 그대로 커밋됨 |
| TC-AUG-074 | PASS | [실동작] | 임계 초과 비종결 job 을 만료 스윕(15분 주기, 09:00:20 tick)이 회수: `claimExpired` 1행 클레임 → job 770011 `FAILED`/`ERR_CD=EXPIRED`/`ERR_MSG_CN="외부 응답 없음 — 무갱신 경과 임계 초과로 만료 종결(…)"` → 전 job 종결이므로 롤업 → aug 770011 `REJECTED` + `RTRY_NMTM`=1 + `DEAD_LETTER_AT`. 로그 `job expired (non-terminal reclaimed) augJobSn=770011 dataAugSn=770011 rollup=APPLIED` / `expired non-terminal jobs count=1 candidates=2`. 후보 2건 중 클레임·확정된 것은 1건뿐(no-op 격리 동작) — 나머지 1건의 실패는 **E-ISSUE-25** 로 별도 기록. 메트릭은 `countJobExpiredAfterCommit()`(`AugmentJobExpiryTxService.java:73`)로 **커밋 이후에만** 증가 |
| TC-AUG-075 | PASS | [실동작] | job 행 0건 + 임계 초과 PENDING 증강(770009) → `expireOrphanPending` → `REJECTED` + `RTRY_NMTM`=1 + `DEAD_LETTER_AT=2026-08-02 09:00:20`. 로그 `orphan pending augment reclaimed (job 0건 · 깨울 주체 없음) dataAugSn=770009 result=APPLIED`. 신고 구간 제외 술어가 없음은 `LsDataAugRepository.findOrphanPendingAugSns`(:125-128) + 단위테스트 `고아_증강_후보는_외부증강_PENDING_조건으로만_조회된다(신고구간_제외_없음)` 로 확인 |
| TC-AUG-076 | PASS | [실동작] | `request_id`/`job_id`/`status` blank → 400(`@NotBlank` 6건 동시 위반 메시지) / `results` **101건** → `400 "results 는 100건을 초과할 수 없습니다."` |
| TC-AUG-077 | PASS | [실동작] | 최상위 `brand_new_field` + `results[].future` 추가 → **200 정상 수신**(`@JsonIgnoreProperties(ignoreUnknown=true)`, `GenAiCallbackRequest.java:41,102`) |
| TC-AUG-078 | PASS | [실동작] | aug=REJECTED 인데 전 job 성공 롤업 → `200 {"applied":false}` + **WARN** `success result discarded — aug already terminal(REJECTED) dataAugSn=770005 otsdJobId=qa0802jobF (강등/반려/취소 — 필요 시 재요청)`. 파생영상 0건 |

---

## 3. E-3B. 증강 외부 위탁 (22행)

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|:--:|---|---|
| TC-AUG-100 | PASS | [정적]+[실동작] | 상한 상수 `CONTRACT_MAX_INPUT_FILES=100`(`AugmentJobSubmitService.java:141`) + `clampChunkSize`(180-185)가 설정값을 **100 초과로 올릴 수 없게** 고정. 청크 `request_id = {augIdmpKey}-{jobSeq}`(`chunkRequestId`, 516-518) — 실동작에서 `AUG-5c247d0a-...-1` 로 확인. 250장→3분할은 단위테스트 `프레임_250장이면_100_100_50_으로_3개_job_으로_분할_위탁됨` 통과(실환경 최대 프레임이 30장이라 live 다중청크는 미구성) |
| TC-AUG-101 | PASS | [실동작]+[정적] | 외부(mock)가 202 로 발급한 `job_id=9d4e286c28d4483595383617f1ef5b06` 이 `LS_DATA_AUG_JOB.OTSD_JOB_ID`·`LS_DATA_AUG.OTSD_JOB_ID` 에 그대로 적재됨(자체 발급 아님). 응답 검증 3축은 `HttpExternalAugmentClient.validate()` 546-560 — request_id echo 동등비교 + `status=RECEIVED` + `GenAiContract.isValidJobId` (non-blank 를 넘어 **형식까지** 검증). 단위테스트 3건 통과 |
| TC-AUG-102 | PASS | [실동작] | **역검증**: backend 가 쓴 `request_id` 를 `Idempotency-Key` 로 목서버에 직접 재전송 → `[MOCK][GENAI] idempotent replay job_id=9d4e286c... key=AUG-5c247d0a-...-1` + 동일 `job_id` 반환(신규 job 미생성). 목서버 멱등 원장에 그 키가 있다는 것은 **backend 가 실제로 그 헤더를 보냈다**는 뜻이다 |
| TC-AUG-103 | PASS | [정적] | `HttpExternalAugmentClient.java:182-183` `onStatus(is4xxClientError → toNonRetryable4xx)`; 526-534 `releaseBody()` 로 본문 소비·해제(누수 방지) 후 **상태코드만** 실은 `NonRetryableExternalException`(응답 본문 원문 미노출, CWE-209). Retry/CircuitBreaker `ignore-exceptions` 등록. 단위테스트 `4xx_응답은_재시도하지_않는다`/`5xx_응답은_재시도한다`/`연결실패는_재시도한다` 통과. 추가로 디코딩 실패(`isDecodingFailure`, 452-463)도 재시도·서킷 집계에서 제외 |
| TC-AUG-104 | PASS | [정적] | `issueAllChunks`(375-394) 가 **전 청크를 위탁 전에** 선기록하고, 하나라도 실패하면 `failIssuedJobs(..., ERR_ISSUE_RECORD_FAILED, ...)` 로 이미 기록된 앞 청크까지 terminal 종결 후 `Optional.empty()` 반환 → `submit()` 237-240 이 **한 건도 위탁하지 않고** `SubmitOutcome.of(0)`. 단위테스트 4건 통과. ⚠ 근거 드리프트 |
| TC-AUG-105 | PASS | [정적] | `resolveDeidInputFiles`(471-494) — `DE_IDNTF_SRC_FILE_PATH_NM` 이 null/blank 인 프레임이 1건이라도 있으면 `DeidPathMissingException` → `recordRejected(..., ERR_DEID_PATH_MISSING, ...)` + `SubmitOutcome.of(0)`. **원본 경로 폴백 코드 자체가 없다**. 단위테스트 `비식별_경로가_null_인_프레임이_있으면_요청이_거부되고_원본경로가_외부로_나가지_않음`·`빈문자열도_fail_closed`·`input_files_의_모든_file_path_가_비식별_경로다` 통과 |
| TC-AUG-106 | PASS | [정적] | `submit()` **최상단**(209-217)에서 `deidentReportGate.isUnderDeidentReport(rawSn)` → `recordRejected(..., ERR_DEID_REPORT_OPEN, ...)` + `SubmitOutcome.of(0)` → 브릿지(`AugmentRequestBridge.java:124-126`)가 즉시 실패 롤업(REJECTED). **프레임 경로 조회 이전** 차단이 코드 순서로 보장됨. `WITHHELD_*` 는 `AugmentApplyResult` 에서 완전히 제거됨(enum 3값만 존재). 단위테스트 2건 통과 |
| TC-AUG-107 | PASS | [정적] | `submitChunkAsync`(311-357)의 `Mono.defer` 안에서 `index>0` 일 때마다 **무잠금 재판정** → 관측 시 `abortRemainingChunks`(406-415)가 남은 job 을 `FAILED/ERR_DEIDENT_REPORT` 로 종결(비종결 방치 시 롤업 영구 보류 방지). `concatMap` 직렬화(268-299)가 이 방어의 전제이며 `AugmentSubmitSerializationGuardTest.실제_스케줄러에서도_청크는_동시에_나가지_않는다_concatMap_직렬_보장` 로 고정 |
| TC-AUG-108 | PASS | [정적] | `onErrorResume`(339-354) 가 청크 실패를 흡수하고 `outcomeRecorder.onSubmitFailed(...)` 로 `FAILED/SUBMIT_FAILED` 기록 후 `Mono.empty()` → **다음 청크 계속**. 집계 판정은 수신부 롤업 책임. 단위테스트 `분할_위탁_중_2번째_job_실패해도_3번째_가_계속_위탁되고_실패가_기록됨` 통과. ⚠ 근거 드리프트(기록 주체가 `AugmentSubmitOutcomeRecorder` 로 분리) |
| TC-AUG-109 | PASS | [정적] | `SubmitOutcome.requiresFailureRollup()`(451-453) → `AugmentRequestBridge.rollUpFailureIfNothingInFlight`(140-155): **미종결 job 이 하나라도 있으면 롤업하지 않고 return**(동시 재요청 보호), 아니면 `handleInNewTransaction(failed)` 로 REJECTED + dead-letter. Phase C-3 로 "개시 후 전 청크 실패" 는 `AugmentSubmitRollupTxService` 로 이관돼 사각지대 없음(`AugmentAsyncSubmitBoundaryIT` 8건). ⚠ 근거 드리프트 |
| TC-AUG-110 | PASS | [정적] | `AugmentJobSubmitService` 클래스에 **트랜잭션 애너테이션 없음**(136-138) — `@Transactional(readOnly)`·`NOT_SUPPORTED` 재부착 금지 사유가 클래스 Javadoc 82-134 에 실측 근거와 함께 명시. 회귀 가드 `AugmentRequestServiceTest.외부_위탁_HTTP_왕복중에는_트랜잭션_동기화와_EntityManager를_잡지_않는다`(381-)가 관측축을 `isSynchronizationActive()` + `getResource(controlEmf)` 로 두어 `isActualTransactionActive()` 위양성 함정을 회피 — 카탈로그의 ⚠ 경고와 정확히 일치. ⚠ 근거 드리프트 |
| TC-AUG-111 | PASS | [정적] | `EVNT_TYPE_FALLBACK="ETC"`(144) + `resolveEventType`(497-502)가 null/blank 를 `ETC` 로 대체. 단위테스트 `이벤트유형이_없는_영상은_ETC_로_대체된다` 통과 |
| TC-AUG-112 | PASS | [정적]+[실동작] | 위탁 로그(`HttpExternalAugmentClient.java:173-175`)는 `originAugSn/augType/jobSeq/inputCount` 만 — **파일 절대경로 없음**. sanitize 는 CR/LF/TAB 에 더해 `U+2028/U+2029` 까지(106) + 길이 상한 50(`logValue`, 574-581, CWE-770). 실동작 로그 `[Augment] genai submit originAugSn=... inputCount=5` 로 경로 미노출 확인. 거부 로그(`AugmentJobSubmitService` 214-215, 227-228)도 개수만 기록 |
| TC-AUG-113 | PASS | [정적] | `NoopExternalAugmentClient` `@ConditionalOnProperty(havingValue="noop")`(30) ↔ `HttpExternalAugmentClient` `havingValue="http", matchIfMissing=true`(58-59) — 상호배타. 만료 스윕은 자기 토글(`authoring.augment.job-expiry.enabled`)만 보므로(`AugmentJobExpirySweeper.java:86,103-108`) noop 환경에서도 후보 0건으로 무해. 단위테스트 `ExternalAugmentClientBeanConditionTest`·`NoopExternalAugmentClientTest`·`스윕이_남의_스케줄러_활성원에_의존하지_않는다` 통과 |
| TC-AUG-114 | PASS | [실동작]+[정적] | **①읽기 허용 루트**: `verifyUnderAllowedRoots`(`AugmentFrameProducer.java:171`)가 lexical + `toRealPath()` 양쪽 검증(CWE-59) **②정규 파일 + size>0**: 134-150 **③해상도 동일**: 152-158 (다르면 CONFLICT). 하나라도 어긋나면 all-or-nothing 실패. 단위테스트 8건 통과. **실동작 결정적 근거** — 파생 프레임과 목서버 산출물의 바이트 크기가 **완전 일치**: `/app/genai-out/genai/9d4e.../001_frame-0_genai.jpg` 13164B ↔ `/app/storage/deidentified/frames/deid/82/frame-0.jpg` 13164B (5장 전부 일치) → **외부 산출물 실반입, self-fill 아님** |
| TC-AUG-115 | PASS | [정적] | `PART_SUFFIX=".part"`(55) + `copyAtomically`(219-233): 소스를 `Files.newInputStream(src, NOFOLLOW_LINKS)` 로 열고(CWE-59/367) `.part` 에 쓴 뒤 `Files.move(..., ATOMIC_MOVE)`. 단위테스트 `프레임_반입_중_실패해도_반쯤_채워진_프레임셋이_남지_않는다`·`허용루트_안의_심링크가_밖을_가리켜도_반영되지_않는다` 통과 |
| TC-AUG-116 | PASS | [실동작] | 파생 비디오 `/app/storage/deidentified/videos/augment/26/82/WINTER.mp4` **50,854B** = 부모 비식별 영상 `/app/storage/raw/autolabel-test/26/deid/95b05485-...-mask.mp4` **50,854B** → **실제 복사** 확인. 소스 경로는 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 값을 읽어 정함(`AugmentExtractSnapshot`) — 파일명이 KPST 규약 `{stem}-mask.mp4` 라 조합·추측으로는 나올 수 없는 값이다. 소스 부재 시 NOT_FOUND(원본 폴백 없음)는 `copyDeidVideo`(108-119) |
| TC-AUG-117 | PASS | [실동작] | 파생 raw 82 의 5개 프레임 전부 `SRC_FILE_PATH_NM` **null(공백)**, `DE_IDNTF_SRC_FILE_PATH_NM=/app/storage/deidentified/frames/deid/82/frame-N.jpg`. 두 컬럼 동일값 아님 → 마트 뷰 "두 경로 상이" 불변식 유지 |
| TC-AUG-118 | PASS | [실동작]+[정적] | 증강 파생 라벨맵 실측: `data_aug_sn=1/2/14` 전부 `COORD_RECALC_YN='N'`, `SCALE_X/Y` null. (대조군: 해상도 파생 `data_aug_sn=5/6/7` 은 `'Y'` + `SCALE 4.5/3.0/2.0`) — 축이 정확히 갈린다. 단위테스트 `라벨_복사시_LS_DATA_AUG_LBL_MAP이_COORD_RECALC_N으로_저장된다` 통과. 해상도 불일치는 TC-AUG-114 가 선차단 |
| TC-AUG-119 | PASS | [실동작] | 존재하지 않는 `output_file_path` 로 Phase B 를 실패시킴 → ①`cleanup` 확인: `videos/augment/26/` 에 실패분(96·99) 디렉터리 **미잔존**, `frames/deid/` 에도 96·99 없음 ②`markRawDataFailed`: `LS_DATA_RAW` 99 `DATA_STTS_CD=FAILED` ③`markAugProcessingFailed`: aug 770010 `DEAD_LETTER_AT=2026-08-02 08:53:41` 이면서 `AUG_PROC_STTS_CD=ACCEPTED` **미변경**(멱등 앵커 무충돌) — 케이스 기대와 정확히 일치. 로그 `augment frame re-extraction failed rawSn=99 dataAugSn=770010 cause=CustomException` |
| TC-AUG-120 | PASS | [실동작] | 파생 raw 82 의 `LS_DATA_META`: `video.filesize=50854`(= 복사된 사본 크기), `video.resolution=320x240`, `video.fps=10.0`, `video.codec=h264`, `video.bit_rate=79688`, `video.duration_ms=5000` → **사본 확정(Phase C) 이후** 그 사본을 probe 한 값. `AsyncAugmentFrameRunner.java:106-121` 이 확정 블록 **밖** 별도 catch 로 기동(레이스 제거·메타 실패가 확정을 되돌리지 않음). 단위테스트 `사본_생성_전에는_probe_하지_않는다`·`기술메타_트리거_실패가_확정된_파생본을_되돌리지_않는다` 등 4건 통과 |
| TC-AUG-121 | PASS | [정적] | `AsyncAugmentFrameRunner.java:92-97` — Phase C `SKIPPED`(중복 트리거 패자)면 cleanup·FAILED 전이 모두 skip(파일이 승자와 동일 경로). 단위테스트 `PhaseC가_SKIPPED_중복트리거패자면_cleanup도_FAILED전이도_하지않는다`·`중복트리거_패자면_기술메타_추출도_트리거하지_않는다` 통과 |

---

## 4. 근거(file:line) 드리프트 — 카탈로그 정합성 결함 13건

2026-07-30 카탈로그 작성 이후 **Phase C-3(논블로킹 제출 전환)** 과 **`AugmentJobSuccessApplier` 분리(S15)** 로 구현이 이동했다. 동작은 전부 케이스 기대와 일치하므로 판정은 PASS 지만, 근거 위치가 어긋나 다음 회차 대조가 불가능하다.

| TC | 카탈로그 근거 | 실제 위치 |
|---|---|---|
| TC-AUG-069 | `GenAiCallbackService.java:214-237` | `AugmentJobSuccessApplier.java:64-86` (`verifyOutputPaths`) |
| TC-AUG-071 | `GenAiCallbackService.java:175-193` | `AugmentJobSuccessApplier.java:101-121` (`applySucceeded`) |
| TC-AUG-072 | `GenAiCallbackService.java:214-221` | `AugmentJobSuccessApplier.java:64-70` |
| TC-AUG-050 | `GenAiCallbackService.java:96-164` | `GenAiCallbackService.java:90-158`(handle) + `AugmentResultService.java:409-452` |
| TC-AUG-051 | `AugmentResultService.java:226-232,317-320` | `AugmentResultService.java:230-236, 321-324` |
| TC-AUG-053 | `AugmentResultService.java:204-207,267-302` | `AugmentResultService.java:208-211, 271-306` |
| TC-AUG-063 | `GenAiCallbackService.java:108-114,37-43` | `GenAiCallbackService.java:102-114` |
| TC-AUG-073 | `GenAiCallbackService.java:155-163` | `GenAiCallbackService.java:149-157` |
| TC-AUG-100 | `AugmentJobSubmitService.java:112,143-148,385-399` | `:141, 180-185, 504-518` |
| TC-AUG-104 | `AugmentJobSubmitService.java:198-260` | `:236-241, 375-394` (`issueAllChunks`) |
| TC-AUG-106 | `AugmentJobSubmitService.java:170-180` | `:207-217` |
| TC-AUG-107 | `AugmentJobSubmitService.java:207-219,262-281` | `:311-357`(defer 재판정) + `:406-415`(abort) |
| TC-AUG-108 | `AugmentJobSubmitService.java:324-344` | `:339-354` + `AugmentSubmitOutcomeRecorder.onSubmitFailed` |
| TC-AUG-109 | `AugmentJobSubmitService.java:304-314` | `:444-453`(`SubmitOutcome`) + `AugmentSubmitRollupTxService` |
| TC-AUG-110 | `AugmentJobSubmitService.java:53-106,160-166` | `:82-138`(Javadoc) + `:136-138`(무애너테이션) |

→ **E-ISSUE-23**

---

## 5. 이슈

### [E-ISSUE-21] TC-AUG-055 — "job 은 있으나 aug 행 없음 = 404" 분기가 도달 불가능한 dead branch
- **심각도**: LOW
- **기대 동작(기대효과)**: 위탁 job 원장(`LS_DATA_AUG_JOB`)에 키는 있는데 대상 증강 행이 사라진 상태에서 콜백이 오면 404 로 종결해, NPE/500 대신 명확한 실패를 회신해야 한다.
- **현재 동작(이슈 내용)**: 그 상태 자체가 스키마상 성립하지 않는다.
  ```
  Foreign-key constraints:
    "fk_ldaj_data_aug" FOREIGN KEY (data_aug_sn) REFERENCES ls_data_aug(data_aug_sn) ON DELETE CASCADE
  ```
  aug 행이 지워지면 job 행도 같이 지워지므로 `GenAiCallbackService.java:103-105`
  ```java
  augRepository.findByDataAugSnForUpdate(dataAugSn)
          .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "증강 행을 찾을 수 없습니다: dataAugSn=" + dataAugSn));
  ```
  의 `orElseThrow` 는 실행될 수 없다(다층 방어로서는 정당하나 **케이스로는 검증 불가**).
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "\d ls_data_aug_job"` → FK CASCADE 확인. `DELETE FROM ls_data_aug WHERE data_aug_sn=N` 후 `SELECT * FROM ls_data_aug_job WHERE data_aug_sn=N` → 0행.
- **영향**: 기능/보안 영향 없음. **테스트케이스 카탈로그의 검증 불가 항목**이라 다음 회차에도 "확인 못 함" 이 반복된다.
- **수정 방향(제안)**: 코드는 그대로 둔다(fail-closed 다층 방어). **카탈로그**에서 TC-AUG-055 를 ①401(도달 가능)만 남기고 ②404 는 "FK CASCADE 로 도달 불가 — 방어 코드로만 존치" 비고로 강등한다.

### [E-ISSUE-22] TC-AUG-072 — 카탈로그 기대 메시지와 실제 응답 문구 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 기대결과의 문구가 실제 응답과 일치해야 회차 간 대조가 성립한다.
- **현재 동작(이슈 내용)**: 카탈로그 기대 `400("SUCCEEDED 콜백에는 results 가 필요합니다.")` ↔ 실제 응답
  ```json
  {"success":false,"message":"SUCCEEDED 결과에는 results 가 필요합니다.","errorCode":"INVALID_INPUT"}
  ```
  `AugmentJobSuccessApplier.java:69` — 웹훅/결과조회 회수 **공용** 원천으로 분리되면서 "콜백" → "결과" 로 일반화된 것으로 보인다(코드가 의도적이며 정확하다).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/genai/callback -H 'Content-Type: application/json' \
    -d '{"request_id":"<발급된키>","job_id":"<보유jobid>","status":"SUCCEEDED"}'
  ```
- **영향**: 기능 영향 없음. 카탈로그 정합성.
- **수정 방향(제안)**: 카탈로그 TC-AUG-072 기대 문구를 `"SUCCEEDED 결과에는 results 가 필요합니다."` 로 정정.

### [E-ISSUE-23] E-3/E-3B 근거 file:line 드리프트 13건 (Phase C-3 + AugmentJobSuccessApplier 분리 미반영)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 케이스의 `근거(file:line)` 는 다음 회차 검증자가 곧바로 열어 대조할 수 있어야 한다. 카탈로그는 검증 산출물의 입력 문서다.
- **현재 동작(이슈 내용)**: §4 표의 13건이 어긋난다. 대표 2건:
  - TC-AUG-069/071/072 의 근거 `GenAiCallbackService.java:214-237` 에는 이제 그 로직이 없다. `GenAiCallbackService` 는 172-175 에서 `successApplier` 에 위임만 한다.
    ```java
    private void applySucceeded(LsDataAugJob target, GenAiCallbackRequest req, String requestId) {
        List<String> outputs = successApplier.verifyOutputPaths(outputFilePathsOf(req), requestId);
        successApplier.applySucceeded(target, req.jobId(), outputs, requestId);
    }
    ```
  - TC-AUG-100/104/106~110 의 근거는 **동기 `.block()` 제출** 시절 라인이다. 현재는 `Flux.concatMap` 기반 논블로킹 제출(`dispatchChunks` 268-299, `submitChunkAsync` 311-357)로 전면 교체됐고 결과 기록은 `AugmentSubmitOutcomeRecorder`/`AugmentSubmitRollupTxService` 로 이관됐다.
- **재현/확인 경로**: 각 케이스의 근거 경로를 Read 후 §4 표의 실제 위치와 대조.
- **영향**: 기능 영향 없음. 다음 회차 검증 비용 증가 + 오판(코드가 사라진 것으로 오인) 위험.
- **수정 방향(제안)**: `docs/test-cases/E-augment-resolution-export-meta.md` 의 E-3/E-3B 근거 컬럼을 §4 표대로 갱신. 아울러 **신규 빈 3종**(`AugmentJobSuccessApplier`·`AugmentSubmitOutcomeRecorder`·`AugmentSubmitRollupTxService`)에 대한 케이스 신설을 검토(현재 `AugmentAsyncSubmitBoundaryIT` 8건이 커버하는 "지각 ACK 가 콜백 상태를 강등하지 않는다" 축은 카탈로그에 대응 케이스가 없다).

### [E-ISSUE-24] `LS_DATA_RAW.DE_IDNTF_YN` 에 소문자 `'y'` 데이터 존재 — 파생 생성 게이트가 대소문자 민감
- **심각도**: LOW
- **기대 동작(기대효과)**: 비식별 산출물 보유 판정(`LsDataRaw.hasDeidentArtifact()`)은 파생 생성(증강·해상도)의 단일 진실원이므로, 컬럼 값 도메인이 `Y`/`F`/`N` 으로 닫혀 있어야 판정이 결정적이다.
- **현재 동작(이슈 내용)**: DB 에 `de_ident_yn='y'`(소문자) 행이 1건 있다.
  ```
  raw_sn=68 | de_ident_yn = y | data_stts_cd = MARKING_READY
  ```
  판정기는 대소문자 민감이다 (`LsDataRaw.java:444-446`):
  ```java
  public boolean hasDeidentArtifact() {
      return "Y".equals(this.deIdntfYn) || "F".equals(this.deIdntfYn);
  }
  ```
  즉 `'y'` 행은 **비식별 산출물 없음**으로 판정돼 증강 콜백이 오면 `ParentGate.FAIL`(REJECTED + dead-letter)로 끝난다. 방향은 fail-closed(안전)이지만, 운영자는 "비식별 완료된 영상인데 증강이 계속 실패" 로 보인다. 쓰기 경로(`markDeidentified`)는 `Y/F/N` 만 허용하므로 이 값은 **코드 외 경로(수동 SQL·검증 세션 등)** 로 들어온 것이다.
- **재현/확인 경로**:
  ```sql
  SELECT raw_sn, de_ident_yn FROM ls_data_raw WHERE de_ident_yn NOT IN ('Y','F','N');
  ```
- **영향**: 데이터 정합. 보안 노출 없음(안전한 방향으로 실패). 다만 컬럼에 DB 레벨 도메인 제약이 없어 같은 오염이 재발할 수 있다.
- **수정 방향(제안)**: ①오염 행 정정(`UPDATE ... SET de_ident_yn='Y' WHERE de_ident_yn='y'`) ②`LS_DATA_RAW.DE_IDNTF_YN` 에 `CHECK (DE_IDNTF_YN IN ('Y','F','N'))` 제약 추가 검토(Flyway). ⚠ 본 검증에서는 **수정하지 않았다**.

### [E-ISSUE-25] 만료 스윕의 롤업이 예외로 끝나면 클레임까지 롤백돼 그 job 이 **영구 회수 불가**(15분마다 무한 재시도)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 만료 스윕은 "비종결 job 은 반드시 종결된다"를 보장하는 최후 회수 장치다. `LsDataAugJobRepository.claimExpired` Javadoc·`GenAiCallbackService` 주석 모두 "재시도가 소진돼도 만료 스윕이 회수하므로 **무한 대기는 없다**"를 계약으로 명시한다. 따라서 회수 자체가 반복 실패하면 그 사실이 종결(dead-letter)되거나 최소한 격리돼야 한다.
- **현재 동작(이슈 내용)**: `AugmentJobExpiryTxService.expire`(63-89)는 **클레임 UPDATE 와 롤업이 같은 트랜잭션**이다.
  ```java
  @Transactional("controlTransactionManager")
  public boolean expire(Long augJobSn, Long dataAugSn, LocalDateTime cutoff) {
      ...
      if (jobRepository.claimExpired(augJobSn, cutoff, LsDataAugJob.ERR_EXPIRED, EXPIRY_REASON, LocalDateTime.now()) == 0) return false;
      ...
      AugmentApplyResult rolledUp = rollup.rollUpIfAllTerminal(dataAugSn, jobs, externalJobIdOf(jobs)); // ← 여기서 throw 하면
      ...                                                                                              //   위 클레임까지 롤백된다
  ```
  `rollUpIfAllTerminal` → `AugmentResultService.handle` 은 `requireJobIdNotOwnedByOtherAug`(271-285)에서 **다른 증강이 같은 `otsd_job_id` 를 보유하면 `CONFLICT`(409)를 던진다**. 스윕은 `RuntimeException` 을 잡아 ERROR 로그만 남기고 다음 후보로 넘어가므로(`AugmentJobExpirySweeper.java:219-224`) job 상태는 `RECEIVED` 그대로 → **다음 tick 의 후보 쿼리(`findExpirableAnchors`, `MDFCN_DT < cutoff`)에 다시 잡혀 같은 예외를 영구 반복**한다. 회수 실패에 대한 재시도 상한·dead-letter·별도 알림이 없다.
  실측(09:00:20 tick):
  ```
  WARN  AugmentResultService  otsd_job_id 선점 충돌(다른 증강 보유) — 409 종결 dataAugSn=770003 ownerDataAugSn=770001 otsdJobId=qa0802jobD
  ERROR AugmentJobExpirySweeper  [Augment][Expiry] expire failed augJobSn=770004 reason=CustomException
  ```
  ```
  aug_job_sn=770004 | job_stts_cd=RECEIVED | err_cd=(null)   ← 클레임이 롤백돼 회수 안 됨
  data_aug_sn=770003 | aug_proc_stts_cd=PENDING | dead_letter_at=(null)  ← 증강도 PENDING 영구 고착
  ```
- **재현/확인 경로**: 서로 다른 증강 2건의 job 이 **같은 `OTSD_JOB_ID`** 를 갖게 만든 뒤 한쪽을 만료시킨다. `LS_DATA_AUG_JOB.OTSD_JOB_ID` 에는 UNIQUE 가 없고(`idx_ldaj_otsd_job_id` 는 비유니크) `LS_DATA_AUG.OTSD_JOB_ID` 에만 UNIQUE(`uk_aug_external_job_id`)가 있어 이 상태가 스키마상 허용된다.
  ```sql
  -- 재현 데이터(본 검증에서 실제로 사용)
  INSERT INTO ls_data_aug_job(aug_job_sn,data_aug_sn,job_seq,idmp_key,otsd_job_id,job_stts_cd,tot_nocs)
  VALUES (770004, 770003, 1, 'QA0802-E2-C-1', 'qa0802jobD', 'RECEIVED', 1);  -- 'qa0802jobD' 는 aug 770001 이 이미 보유
  UPDATE ls_data_aug_job SET mdfcn_dt = CURRENT_TIMESTAMP - INTERVAL '2 days' WHERE aug_job_sn=770004;
  -- 다음 스윕 tick(최대 15분) 후 → ERROR expire failed, 상태 변화 없음. 이후 매 tick 반복.
  ```
- **영향**: 기능/데이터 정합(liveness). 트리거는 **벤더가 서로 다른 `request_id` 에 같은 `job_id` 를 반환**하는 계약 이상이라 발생 확률은 낮지만, 발생하면 그 증강은 사람이 DB 를 직접 손대기 전까지 `PENDING` 에서 나오지 못하고 15분마다 실패 트랜잭션 1건 + ERROR 로그를 영구히 생성한다. 같은 구조상 롤업이 던지는 **모든** 예외(`DataIntegrityViolationException` → 409 포함)가 동일 결과를 낳는다. 보안 노출은 없다.
- **수정 방향(제안)**: ①`expire()` 에서 **클레임 커밋과 롤업을 분리**한다(클레임을 `REQUIRES_NEW` 로 먼저 커밋하면 롤업이 실패해도 job 은 종결 상태로 남아 다음 tick 이 재선정하지 않고, 롤업 재시도는 "전 job 종결" 경로가 다시 집는다). ②또는 회수 실패 횟수를 `LS_DATA_AUG_JOB` 에 누적해 임계 초과 시 dead-letter 로 종결한다. ③부수적으로 `LS_DATA_AUG_JOB.OTSD_JOB_ID` 에 UNIQUE 를 걸어 이 데이터 이상 자체를 입구에서 막는 안도 검토(단, 벤더 중복 반환 시 위탁이 깨지므로 트레이드오프 확인 필요).

### [E-ISSUE-26] 앱(KST)과 DB DEFAULT(UTC)가 같은 `timestamp without time zone` 컬럼에 **9시간 어긋난 시계**로 기록 — 만료 판정의 잠재 오작동
- **심각도**: LOW
- **기대 동작(기대효과)**: 만료 스윕은 `LocalDateTime.now() - idleTimeoutMinutes` 를 `MDFCN_DT`/`REG_DT` 와 직접 비교한다. 두 값이 같은 시계여야 임계(기본 360분)가 의미를 갖는다.
- **현재 동작(이슈 내용)**: 앱은 `-Duser.timezone=Asia/Seoul`(`backend/Dockerfile:42`) + `hibernate...time_zone: Asia/Seoul`(`application.yml:48`)이라 `LocalDateTime.now()` 가 **KST 벽시계**를 그대로 적재하는 반면, DB 세션은 `Etc/UTC` 라 컬럼 DEFAULT(`ls_data_aug_job.reg_dt/mdfcn_dt`, `ls_data_aug.reg_dt` 등 `DEFAULT CURRENT_TIMESTAMP`)로 채워진 값은 **UTC** 다.
  ```
  DB : show timezone → Etc/UTC ;  current_timestamp → 2026-08-02 00:01:50
  APP: 같은 순간 JPA 가 적재한 mdfcn_dt → 2026-08-02 09:00:20   (Δ = 9h)
  ```
  실측: DB DEFAULT 로 만들어진 job 행(`mdfcn_dt` = UTC now)이 **생성 직후** 스윕의 `MDFCN_DT < now(KST) - 360분` 조건을 만족해 즉시 만료됐다(`augJobSn=770011`). 앱 경로(`LsDataAugJob.createIssued` 161-168 등)는 항상 `LocalDateTime.now()` 를 명시 대입하므로 **현재 운영 흐름에서 관측된 오작동은 없다** — 노출은 DB DEFAULT 에 의존하는 경로(Flyway 백필·운영 SQL·본 검증 같은 수동 INSERT)로 한정된다.
- **재현/확인 경로**:
  ```sql
  -- DB 기본값으로 job 을 만들고 스윕 tick(최대 15분)을 기다린다 → 임계 미도달인데 EXPIRED 로 종결된다
  INSERT INTO ls_data_aug_job(aug_job_sn,data_aug_sn,job_seq,idmp_key,otsd_job_id,job_stts_cd,tot_nocs)
  VALUES (…, 'RECEIVED', 1);   -- reg_dt/mdfcn_dt 는 DEFAULT CURRENT_TIMESTAMP(UTC)
  ```
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c "show timezone;"   # Etc/UTC
  docker exec klid-backend env | grep JAVA_OPTS                                     # -Duser.timezone=Asia/Seoul
  ```
- **영향**: 데이터 정합(시각 축). 현재는 잠복이며, 백필 마이그레이션이 이 컬럼들을 DEFAULT 로 채우면 **아직 살아 있는 위탁 job 이 즉시 만료 종결**돼 증강이 실패 확정될 수 있다.
- **수정 방향(제안)**: ①DB 세션/컨테이너 TZ 를 `Asia/Seoul` 로 통일하거나 ②컬럼 DEFAULT 를 제거해 시각 기록 주체를 앱 하나로 못박는다(권장 — 시계 단일 원천). ③장기적으로는 `timestamptz` 전환 검토. ⚠ 본 검증에서는 **아무것도 변경하지 않았다**.

---

## 6. 다른 파트에 넘길 관찰 (본 담당 범위 밖 — 참고용, 이슈 미발행)

1. **E-1 계약 변경 미반영**: `POST /v1/augments/request` 가 `prompt`(5필드 전부 필수)를 **필수**로 요구한다(`AugmentRequestRequest.java:52-55`, 2026-07-31 신설). 카탈로그 TC-AUG-001 의 요청 예시 `{videoIds:[N], types:["WINTER"]}` 는 이제 **400** 이다(실측). E-1 담당이 반영해야 한다.
2. **V143 활성 유니크 인덱스 소멸**: `\d ls_data_aug` 에 `UK_LS_DATA_AUG_ACTVTN` 이 **없다**. Flyway V153 `add ls data aug prompt and drop active unique` 가 드롭했다. TC-AUG-012/013/014(중복 활성 요청 409 + DB 최종 방어)의 전제가 바뀌었으므로 E-1 담당이 재판정해야 한다.
3. **병렬 에이전트 간섭 주의**: 검증 중 `data_aug_sn` 900001~900004 가 다른 에이전트에 의해 생성/삭제되는 것을 관측했다. 본 파트는 이를 피해 **770001~770011 / `src_sn` 770100** 대역만 사용했다.

## 7. 본 검증이 남긴 테스트 데이터 (정리 대상)

수정 금지 규칙에 따라 **삭제하지 않고 기록만** 한다. 전부 INSERT 로 만든 검증 전용 행이다.

| 테이블 | 키 | 비고 |
|---|---|---|
| `ls_data_aug` | 770001, 770003, 770005~770011 | 검증용 aug (REJECTED/PENDING/ACCEPTED 혼재) |
| `ls_data_aug_job` | 770001~770004, 770005~770011 | 검증용 job |
| `ls_data_aug_job_file` | 770002~770011 | |
| `ls_data_src` | 770100 (`raw_sn=50`) | TC-AUG-064 부모 `'N'` 게이트 재현용 |
| `ls_data_raw` | 82(정상 파생·유지 권장), 96·99(async 실패로 FAILED) | 82 는 정상 시나리오 산출물 |
| `ls_data_aug` | 23 (`NEW_RAW_SN=82`) | 정상 시나리오 산출물 |
# E-part3 — E-4. 해상도 파생 오케스트레이션 (VideoResolutionService / VideoController)

- 대상 섹션: `docs/test-cases/E-augment-resolution-export-meta.md` → `## E-4` (TC-RESL-001 ~ TC-RESL-021, **21건**)
- 검증 일자: 2026-08-02 (회차 폴더 2026-08-01/1차)
- 검증 커밋: `56d30478` / DB Flyway `V158` (스택 실행중 jar 와 스키마 정합 확인 — `ls_data_raw.aug_type_cd` 존재, `uk_ls_data_aug_resl` 부분유니크 존재)
- 스택: `klid-postgres`/`klid-backend`(:18081 `/api`)/`klid-ai-server`/`klid-mock-server`/`klid-frontend` 전부 healthy
- 검증 방식: **21건 전건 실동작(curl + DB + ffprobe)**. 정적 대조는 보조.

## 판정 요약

| 판정 | 건수 |
|------|---:|
| PASS | 19 |
| FAIL | 1 |
| PARTIAL | 1 |
| BLOCKED / N/A / 확인필요 | 0 |
| **합계** | **21** |

---

## 검증에 사용한 실데이터 (본 검증이 만든 것)

| rawSn | 용도 | 비고 |
|---|---|---|
| 26 | 기존 pipeline-drive 영상(APPROVED, 320×240) | 파생 76/77/78 생성됨 |
| 80 | 신규 업로드→비식별→마킹→배정→검수승인 완주 | TC-RESL-018/019/002 용. 파생 83/84/85 |
| 81 | 동상 | TC-RESL-003/004/005/007/012/016/017/021 용. 파생 86/87/95 |

- `de_ident_yn`·`de_idntf_src_file_path_nm`·`de_idntf_file_path_nm` 을 **본 검증이 만든 행에 한해** 일시 변경 후 **원복 완료**(최종 상태 표로 확인).
- TC-RESL-021 상태 매핑 검증용 합성 파생 3행(`QA0801-E4-SYN-*`)은 관측 후 **DELETE 로 원복 완료**.
- **기존 데이터(rawSn 4~71 및 그 파생 18~25)는 일절 변경하지 않았다.**

---

## 케이스별 결과

| ID | 판정 | 근거 확인 | 실측 내용 |
|----|:--:|------|------|
| TC-RESL-001 | PASS | [실동작] | `POST /v1/videos/26/resolution` 바디 생략 → **201**, `derivatives` 3건 전부 `CREATED` + 새 RAW_SN 76/77/78. DB: `ls_data_raw` 3행(`orgnl_raw_sn=26`, `aug_type_cd=RESL_1080P/720P/480P`), `ls_data_aug` 3행(`RESL_*`, ACCEPTED, `new_raw_sn=76/77/78`) |
| TC-RESL-002 | PASS | [실동작] | `{"presets":["RESL_1080P","RESL_480P"]}` (rawSn=80) → 201, **2건만** 생성(84/85). 720P 미생성 |
| TC-RESL-003 | PASS | [실동작] | `{"presets":["RESL_480P","RESL_480P"]}` (rawSn=81) → 201, 응답 **1건**(86). 중복 미제거였다면 2번째가 `UK_LS_DATA_AUG_RESL` 로 409→`FAILED` 항목이 추가로 보였을 것 — 나오지 않음 = distinct 동작 확인. 정적: `ResolutionChangeRequest.java:36` `stream().distinct()` |
| TC-RESL-004 | PASS | [실동작] | rawSn=81 첫 프레임을 1920×1080 이미지로 지정 후 바디 생략 → 응답에 **RESL_1080P 항목 자체가 없음**(스킵), 720P `CREATED`. 백엔드 로그 `preset skipped (same resolution) rawSn=81 preset=RESL_1080P 1920x1080` |
| TC-RESL-005 | PASS | [실동작] | 같은 조건 + `{"presets":["RESL_1080P"]}` → **400 INVALID_INPUT** `"원본과 동일하지 않은 적용 가능한 해상도 프리셋이 없습니다."` |
| TC-RESL-006 | PASS | [실동작] | rawSn=26 동일 요청 재전송(3종 전부 부분유니크 중복) → **500 INTERNAL_ERROR** `"요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."` — 내부 사유(제약명·스택) 미노출 확인. `{"presets":[]}` 로도 동일(빈 배열→기본 3종) |
| TC-RESL-007 | PASS | [실동작] | rawSn=81(1080P 스킵, 480P 이미 존재) 바디 생략 → **201** + `[{rawSn:87, RESL_720P, CREATED}, {rawSn:null, RESL_480P, FAILED}]` — 실패 프리셋 `rawSn:null` + CREATED 혼재, 성공분은 정상 확정 |
| TC-RESL-008 | PASS | [실동작] | 원본 320×240 → 1920×1080/1280×720/854×480 **전부 업스케일인데 거부 없이 201**. 산출 프레임 ffprobe 실측 `deid/76/frame-0.jpg=1920,1080` · `77=1280,720` · `78=854,480`. 구 `targetH>=srcH` 가드 부재 확인 |
| TC-RESL-009 | PASS | [실동작] | rawSn=18(`orgnl_raw_sn=4`, WINTER 파생) → **400** `"원본 영상에만 해상도 변경 가능"` |
| TC-RESL-010 | PASS | [실동작] | rawSn=5(비APPROVED) → **409 CONFLICT** `"검수 완료(APPROVED)된 영상만 해상도 변경할 수 있습니다."` |
| TC-RESL-011 | PASS | [실동작] | rawSn=999999 → **404 NOT_FOUND**. GET 경로도 동일 404 |
| **TC-RESL-012** | **FAIL** | [실동작] | 기대 `400 "원본 프레임 해상도를 확인할 수 없습니다."` — **실제는 500 INTERNAL_ERROR `"프레임 이미지를 읽을 수 없습니다."`**. 손상/비이미지 파일·파일 부재 두 케이스 모두 500. `Java2DImageResizer.readDimensions` 가 dim≤0 을 **반환하지 않고 예외를 던지므로** `VideoResolutionService.java:110-112` 의 400 분기는 도달 불가 사문화 → **E-ISSUE-41** |
| **TC-RESL-013** | **PARTIAL** | [실동작] | 문자열 축은 화이트리스트 강제 확인 — `"RESL_240P"`/`"resl_720p"`/`"RESL_720P "`/`""`/`true`/`{}`/비배열 전부 **400**. 그러나 **숫자(enum ordinal)는 통과** — `[0]`/`[1]` 은 역직렬화를 통과해 실제 프리셋으로 매핑되고(중복이라 500 all-failed 까지 진행), `[99]` 만 범위초과로 400. 계약(OpenAPI `allowableValues` 3종 문자열) 밖의 표현이 열려 있음 → **E-ISSUE-42** |
| TC-RESL-014 | PASS | [실동작] | POST: WORKER **403**(`FORBIDDEN`) / 토큰없음 **401**(`UNAUTHORIZED`). GET 도 동일(403/401). `VideoController` `@PreAuthorize("hasRole('REVIEWER')")` 양 엔드포인트 부착 |
| TC-RESL-015 | PASS | [실동작] | 201 본문 = `{"derivatives":[{"rawSn","goalResCd","targetW","targetH","status"}]}` 정확히 5필드. 파일경로·EXPORT_SN·예약 aug PK 등 내부 식별자 **미포함** |
| TC-RESL-016 | PASS | [실동작] | 프레임 경로를 `/etc/passwd` 및 `../../../../etc/passwd` 로 각각 지정 → 둘 다 **400** `"원본 프레임 경로가 허용된 저장 경로를 벗어납니다."`, 경로 원문 미반향 |
| TC-RESL-017 | PASS | [실동작] | ①deid 절대경로(`/app/storage/deidentified/frames/deid/{rawSn}/…`)로 정상 통과(001·002·007 전 케이스가 이 축) ②deid 경로를 null 로 비워 raw base 축(`/app/storage/raw/frames/raw/81/…`)으로 폴백시켜도 경로검증 통과(측정 이후 단계까지 진행). 두 base 합집합 허용 확인 |
| TC-RESL-018 | PASS | [실동작] | rawSn=8(`de_ident_yn='F'`, `ls_deident_proc_log` 는 FAILED 만 = 산출물 부재형) → **409 CONFLICT** `"원본의 비식별 산출물을 찾을 수 없어 해상도 파생을 만들 수 없습니다."` — 예약 이전 동기 거부, `ls_data_aug`·파생 RAW 미생성 확인 |
| TC-RESL-019 | PASS | [실동작] | rawSn=80 을 `de_ident_yn='F'`(신고형, SUCCESS procLog + 실파일 존재)로 두고 요청 → **201 CREATED**(rawSn=83), 이후 `COMPLETED` 확정까지 완주. ★2026-07-29 확정정책("신고는 파생 생성을 막지 않는다") 실동작 일치 — **결함 아님** |
| TC-RESL-020 | PASS | [실동작] | `ls_deident_proc_log.de_idntf_file_path_nm` 을 `/etc/passwd` 및 `../../../etc/passwd` 로 변조 → 둘 다 **400 INVALID_INPUT** `"경로가 허용된 비식별 저장 경로를 벗어납니다."`, 경로 원문 미노출 |
| TC-RESL-021 | PASS | [실동작] | `GET /v1/videos/81/resolution` → 200. 매핑 실측: `'Y'`+COMPLETED→**COMPLETED**(86/87) · `DATA_STTS_CD=FAILED`→**FAILED** · PENDING→**IN_PROGRESS**(합성행으로 확인). 비-해상도 파생(WINTER 합성행, 그리고 rawSn=4 의 실제 WINTER/NIGHT/RAIN 18·19·23)은 **전부 제외**(rawSn=4 GET 응답에 20/21/22 세 건만 노출) |

---

## 근거 드리프트 (카탈로그 file:line 정합)

| TC | 카탈로그 표기 | 실제 위치 | 편차 |
|----|------|------|:--:|
| TC-RESL-001 | VideoResolutionService.java:93-142 | 92-141 | -1 |
| TC-RESL-004 | :117-124 | 116-123 | -1 |
| TC-RESL-005 | :127-130 | 126-129 | -1 |
| TC-RESL-006 | :134-140 | 133-139 | -1 |
| TC-RESL-007 | :199-209 | `createOne` 205-215 | +6 |
| TC-RESL-008 | :107-124 | 106-123 | -1 |
| TC-RESL-009 | :224-226 | 230-232 | +6 |
| TC-RESL-010 | :229-234 | 235-240 | +6 |
| TC-RESL-011 | :219-221 | 225-227 | +6 |
| TC-RESL-012 | :111-113 | 110-112 | -1 |
| TC-RESL-016 | :256-268 | `resolveSafeSource` 262-274 | +6 |
| TC-RESL-017 | :260-265 | 266-272 | +6 |
| TC-RESL-018 | VideoResolutionService.java:101 | 100 | -1 |
| TC-RESL-021 | :154-194 / VideoController.java:349-353 | `listDerivatives` 153-168 · `presetOf` 178-189 · `statusOf` 191-200 / 357-361 | +8 |
| TC-RESL-014 | VideoController.java:322-325 | 해당 구간은 `@ApiResponses` 어노테이션. 실제 인가 지점은 332(`@PreAuthorize`) | 오지시 |

정합(드리프트 없음): TC-RESL-002/003(`ResolutionChangeRequest.java:32-37`) · TC-RESL-013(`ResolutionPreset.java:26-28`) · TC-RESL-015 · TC-RESL-018/019/020(`ParentDeidArtifactGuard.java` 65-94 / 70-93 / 100-124).

---

## 테스트 커버 대조 (`_raw/test-baseline.md` = backend 4,755 tests / 실패 0)

| TC | 커버 테스트 | 상태 |
|----|------|:--:|
| 001 | `VideoResolutionServiceTest#createsDerivativePerPreset` | 통과 |
| 002 | `VideoResolutionServiceTest#specifiedPresetsOnly` | 통과 |
| **003** | **없음** (`resolvePresets` distinct 전용 테스트 부재) | **커버 갭** |
| 004 | `#sameResolutionSkipped` | 통과 |
| 005 | `#allTargetPresetsSameResolutionRejected400` | 통과 |
| 006 | `#allFailedReturnsError`, `VideoResolutionControllerTest#모든_프리셋_생성이_실패하면_500이다` | 통과 |
| 007 | `#partialFailureIsolated` | 통과 |
| 008 | `#upscaleAllowedNo400` | 통과 |
| 009 | `#derivedParentRejected` | 통과 |
| 010 | `#notApprovedRejected`, `VideoResolutionControllerTest#미검수_영상_요청시_409` | 통과 |
| 011 | `#notFound404` | 통과 |
| **012** | **없음** (해상도 실측 실패 경로 미커버 — 그래서 500 회귀가 드러나지 않았다) | **커버 갭** |
| 013 | `VideoResolutionControllerTest#화이트리스트_외_preset_값_요청_400` (문자열 축만) | 통과(숫자 축 미커버) |
| 014 | `VideoResolutionControllerTest#미인증_401`, `#REVIEWER가_아니면_403이다` | 통과 |
| 015 | `#responseContainsRawSnsAndStatus`(서비스·컨트롤러 양쪽) | 통과 |
| 016 | `#traversalStillRejected`, `#outsideBothBasesRejected` | 통과 |
| 017 | `#deidFramePathPassesViaDeidBase`, `#rawFramePathPassesViaRawBase` | 통과 |
| 018 | `ParentDeidArtifactGuardTest#F가_비식별API실패인_경우…CONFLICT`, `#SUCCESS_비식별_이력이_없으면…`, `VideoResolutionServiceTest#missingParentDeidArtifactRejectedBeforeReservation` | 통과 |
| 019 | `ParentDeidArtifactGuardTest#신고F여도_비식별_산출물_파일이_실재하면_통과한다`, `VideoResolutionServiceTest#reportedParentWithArtifactStillCreatesDerivatives` | 통과 |
| 020 | `ParentDeidArtifactGuardTest#적재된_경로가_허용_비식별_저장경로_밖이면_거부된다_CWE22` | 통과 |
| **021** | `#listDerivativesResolvesPresetFromColumn`(COMPLETED 만), `#listDerivativesExcludesNonResolutionAndUnknown` | **부분** — `FAILED`/`IN_PROGRESS` 매핑 단언 부재 |

---

## 관찰 사항 (결함 아님 — 확정정책/설계 의도)

1. **★부모 게이트 `'N'`만 차단 / `'F'` 통과** — TC-RESL-018/019 실동작으로 재확인. `ParentDeidArtifactGuard:70`(`hasDeidentArtifact()`)이 `'N'`·null 만 막고, `'F'` 는 **산출물 실재 검증(fail-closed)** 으로 뒤를 받친다. 2026-07-29 구속 정책과 일치하며 **결함으로 재보고하지 않는다.**
2. **중복 재요청이 500 으로 보인다** — 전 프리셋이 `UK_LS_DATA_AUG_RESL` 중복(409)으로 실패해도 `createOne` 이 흡수 후 `all failed → 500` 으로 마감된다. `CLAUDE.md`·카탈로그 모두 "전부 실패=500 / 사유 미노출(CWE-209)"을 확정정책으로 못박고 있어 **정책 준수**로 판정했다. 다만 운영 관측상 "클라이언트 중복 요청"과 "진짜 내부 실패"가 구분되지 않는다는 점만 기록해 둔다(수정 제안 아님).
3. **좌표 배율 실측(E-5 TC-RESL-045 보강 근거)** — 320×240 원본 기준 `ls_data_aug_lbl_map` 실적재값: `RESL_1080P → scale_x=scale_y=4.500000`(=min(1920/320, 1080/240)) · `RESL_720P → 3.000000`(=min(4,3)) · `RESL_480P → 2.000000`(=min(2.669,2)), `coord_recalc_yn='Y'`. **축별 독립 배율이 아니라 균일 배율(레터박스)** 이 실제로 적용됨을 확인. 외부 증강(WINTER/NIGHT/RAIN)은 `coord_recalc_yn='N'` + scale null 로 정상 분리.
4. **파생 비디오는 부모 비식별본 복사 경로에 실재** — `/app/storage/deidentified/videos/resolution/{parent}/{newRawSn}/{PRESET}.mp4` (예: `.../26/76/RESL_1080P.mp4` 50,854 bytes). 원본 폴백 경로 미사용 확인.

---

## 이슈

### [E-ISSUE-41] TC-RESL-012 — 프레임 해상도 실측 실패가 400 이 아니라 500 으로 나가고, 400 가드는 도달 불가 사문화
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 원본 프레임 이미지가 손상/미지원 포맷/부재라 해상도를 확인할 수 없으면 **400 INVALID_INPUT `"원본 프레임 해상도를 확인할 수 없습니다."`** 로 응답해야 한다. 이는 서버 장애가 아니라 **요청 대상 데이터 상태의 전제 불충족**이므로 4xx 여야 FE 가 사용자에게 "이 영상은 프레임이 깨져 해상도 변경이 불가"라고 안내할 수 있고, 5xx 알람(운영 오탐)을 만들지 않는다.
- **현재 동작(이슈 내용)**: `VideoResolutionService.java:107-112`
  ```java
  int[] dim = measureFirstFrame(rawSn);
  int srcW = dim[0]; int srcH = dim[1];
  if (srcW <= 0 || srcH <= 0) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "원본 프레임 해상도를 확인할 수 없습니다.");
  }
  ```
  그런데 실측기 `Java2DImageResizer.java:81-98` 은 **dim≤0 을 반환하는 경로가 없다** — `ImageIO.read` 가 IOException 이거나 null 이면 그 자리에서 `INTERNAL_ERROR("프레임 이미지를 읽을 수 없습니다.")` 를 던진다. 따라서 위 400 분기는 **어떤 입력으로도 도달할 수 없는 사문화 코드**이고, 실제 사용자는 항상 500 을 받는다.
  실측(rawSn=81, 첫 프레임 경로만 조작):
  - 비이미지 파일(mp4) → `HTTP 500 {"errorCode":"INTERNAL_ERROR","message":"프레임 이미지를 읽을 수 없습니다."}`
  - 파일 자체 부재 → 동일하게 `HTTP 500`
- **재현/확인 경로**
  ```sql
  -- 첫 프레임 소스를 읽을 수 없는 파일로 지정(허용 base 안이어야 경로가드를 통과)
  UPDATE ls_data_src SET de_idntf_src_file_path_nm='/app/storage/deidentified/frames/deid/81/NOPE.jpg'
   WHERE raw_sn=81 AND frm_no=0;
  ```
  ```bash
  curl -i -X POST http://localhost:18081/api/v1/videos/81/resolution \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' \
    -d '{"presets":["RESL_1080P"]}'
  # → HTTP/1.1 500 ... "프레임 이미지를 읽을 수 없습니다."
  ```
- **영향**: 기능/운영. ①데이터 상태 문제가 서버 오류로 분류되어 5xx 알람·SLO 오염 ②FE 가 4xx/5xx 로 분기하는 표준 처리에서 "재시도하면 될 일시 장애"로 오안내 ③해당 400 분기가 테스트로도 커버되지 않아(커버 갭) 회귀 감지 불가. 보안 등급은 아님(메시지에 경로·스택 미노출은 유지됨).
- **수정 방향(제안)**: `Java2DImageResizer.readImage` 의 실패를 호출부에서 구분 가능하게 만든다 — 예) 포트 계약을 `Optional<int[]> readDimensionsQuietly(Path)` 로 넓히거나, 실측 실패를 `ErrorCode.INVALID_INPUT` 계열 전용 예외로 승격하고 `VideoResolutionService.measureFirstFrame` 에서 400 메시지로 재던진다. 어느 쪽이든 `srcW<=0` 사문화 분기는 제거하거나 실제 도달 가능하게 배선해야 하며, "손상 프레임 → 400" 테스트를 추가한다. (⚠ 구현은 하지 않는다)

### [E-ISSUE-42] TC-RESL-013 — `presets` enum 이 숫자(ordinal)로도 바인딩돼 문자열 화이트리스트 계약을 우회한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `presets` 원소는 **`RESL_1080P`/`RESL_720P`/`RESL_480P` 세 문자열만** 허용되고 그 외 값·형식은 Jackson 역직렬화 단계에서 400 으로 거부돼야 한다(`ResolutionChangeRequest.java:16-17` 의 명시 계약, OpenAPI `allowableValues` 3종, "자유 입력 해상도 차단 CWE-20").
- **현재 동작(이슈 내용)**: Jackson 기본 동작상 **JSON 정수는 enum ordinal 로 해석**된다. 프로젝트에 `FAIL_ON_NUMBERS_FOR_ENUMS` 류 하드닝 설정이 없어(`application*.yml` 에 `spring.jackson` 블록 자체가 없음) 다음이 성립한다:
  - `{"presets":[0]}` → `RESL_1080P`, `{"presets":[1]}` → `RESL_720P`, `{"presets":[2]}` → `RESL_480P` 로 **정상 바인딩되어 서비스 로직까지 진입**
  - `{"presets":[99]}` → 400 (ordinal 범위 초과일 때만 거부)
  실측(rawSn=81, 해당 프리셋들이 이미 존재해 중복 실패로 마감되는 상태):
  ```
  {"presets":[0]}   -> HTTP 500 "요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."   ← 역직렬화 통과 + 실측 통과 + createOne 시도까지 진행
  {"presets":[1]}   -> HTTP 500 (동상)
  {"presets":[99]}  -> HTTP 400 "요청 본문이 올바르지 않습니다."
  {"presets":["RESL_240P"|"resl_720p"|"RESL_720P "|""|true|{}] } -> 전부 HTTP 400
  ```
  즉 **문자열 축만 화이트리스트가 서고, 숫자 축은 열려 있다.**
- **재현/확인 경로**
  ```bash
  # 파생이 아직 없는 APPROVED 원본에 대해 실행하면 ordinal 이 실제 프리셋으로 생성되는 것까지 관측된다
  curl -i -X POST http://localhost:18081/api/v1/videos/{approvedRawSn}/resolution \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' -d '{"presets":[2]}'
  # → RESL_480P 파생이 생성됨 (문서화되지 않은 표현)
  ```
- **영향**: 입력검증/계약(CWE-20). **값 공간이 넓어지지는 않으므로**(0~2 는 동일한 3개 프리셋) 권한상승·자유해상도 주입은 아니다. 실질 위험은 ①**계약 밖 표현이 허용돼 OpenAPI·연동규격과 실제 수용 입력이 어긋남** ②**순서 의존 취약** — 향후 `ResolutionPreset` 에 상수를 앞/중간에 추가하면 기존 숫자 페이로드가 **조용히 다른 프리셋으로 매핑**되어 잘못된 해상도 파생이 생성된다(무증상 데이터 오류) ③보안 스캐너/감리 관점의 "enum 화이트리스트 강제" 주장이 성립하지 않음.
- **수정 방향(제안)**: 전역 Jackson 설정에 `spring.jackson.deserialization.fail-on-numbers-for-enums: true` 를 켜거나(영향 범위가 넓으므로 전 DTO 회귀 확인 필요), 국소적으로 `ResolutionPreset` 에 `@JsonCreator` 문자열 전용 팩토리(미지값 → `IllegalArgumentException`)를 두어 숫자 입력을 400 으로 떨어뜨린다. 아울러 `VideoResolutionControllerTest` 에 `{"presets":[0]}` → 400 케이스를 추가한다. (⚠ 구현은 하지 않는다)

---

## 남은 커버 갭 (이슈 아님 — 테스트 자산 보강 제안)

- TC-RESL-003: `ResolutionChangeRequest.resolvePresets` 중복 제거 단위 테스트 부재
- TC-RESL-012: 프레임 실측 실패 경로 테스트 부재 (E-ISSUE-41 이 드러나지 않은 직접 원인)
- TC-RESL-021: `statusOf` 의 `FAILED`/`IN_PROGRESS` 매핑 단언 부재 (COMPLETED 만 커버)
# E 클러스터 검증 — part4: E-5. 해상도 파생 예약/확정 (Reservation·Snapshot·Materialize·Persist·Runner)

> 대상: `docs/test-cases/E-augment-resolution-export-meta.md` `## E-5` — **TC-RESL-030 ~ TC-RESL-071 (42건)**
> (그중 **TC-RESL-047 은 폐기** → 검증 대상 **41건**)
> 검증일 2026-08-02 · 코드 기준 `qa-0801` 워크트리 · 실행 스택 `localhost:18081`(`/api`) + `klid-postgres`(`public` 스키마)

---

## 0. 검증 환경·실동작 근거 (요약)

| 항목 | 실측 |
|------|------|
| backend | `GET /api/actuator/health` → `{"status":"UP"}` |
| DB 마이그레이션 | `flyway_schema_history` **V158 까지 적용**(2026-08-01 23:10) — E-5 관련 V125/V155 모두 반영됨. (`pipeline-drive.md` 의 "V146 스테일 jar" 기록은 그 이후 재빌드로 해소된 상태) |
| `UK_LS_DATA_AUG_RESL` | `\d ls_data_aug` → `"uk_ls_data_aug_resl" UNIQUE, btree (src_sn, aug_type_cd) WHERE aug_type_cd::text ~~ 'RESL\_%'::text` — **술어에 상태 조건 없음(상태 무관)** ✅ |
| 실구동 성공 파생 | `rawSn 76/77/78`(부모 26, 3프리셋 — E-part3 담당 에이전트가 08:44:20 에 생성) · `rawSn 88`(부모 900, `'F'` 게이트 검증용) |
| 실구동 실패 파생 | `rawSn 89`(deid 프레임 경로 null) · `90`(중복 videoFrameNo) · `97`(Phase C mtime stale) — 전부 정리 후 RAW 삭제됨 |
| 메트릭 | `GET /api/actuator/metrics/resolution.finalize.failed` → `COUNT=2.0` (내 실패 유도 2건과 일치) |
| 자동테스트 baseline | `_raw/test-baseline.md` — backend 4,755 tests / 실패 0 / skip 5. E-5 관련 테스트 8파일(아래 §3) 전량 통과 |

### 검증용 투입 데이터 (INSERT-only, 기존 데이터 미수정 — 후속 회차 참고용으로 남김)

| rawSn | 용도 | 특징 |
|--:|------|------|
| 900 | `'F'`(신고) 게이트 통과 실증 | `de_ident_yn='F'`, 26의 프레임/deid경로 복제, SUCCESS procLog |
| 901 | `'N'` 게이트 차단 실증 | `de_ident_yn='N'` |
| 902 | Phase A deid 프레임 strict | frm_no=1 의 `de_idntf_src_file_path_nm=NULL` |
| 903 | Phase A 중복 videoFrameNo | frm 0/1 모두 `vdo_frm_no=7` |
| 905 | Phase C stale(mtime) | procLog 가 미래 mtime(2030-01-01) 파일 `/app/storage/deidentified/videos/qa-e5/stale.mp4` 를 가리킴 |
| 906 | 목적지 파일명 충돌(적대검증) | frm 0/1 의 deid 경로 basename 이 둘 다 `frame-0.jpg` |

> 생성 파일: `/app/storage/deidentified/videos/qa-e5/stale.mp4`(mtime 2030) — 검증 픽스처. 그 외 파일 생성/수정 없음. **코드·설정·테스트 파일은 일절 수정하지 않았다.**

---

## 1. 판정 결과표 (41건)

| ID | 판정 | 근거 확인 | 비고 |
|----|:----:|-----------|------|
| TC-RESL-030 | PASS | [실동작] 예약 로그 `derivative reserved parentRawSn=900 newRawSn=88 dataAugSn=30 preset=RESL_720P src=320x240 target=1280x720`(08:47:27.760, http 스레드) 직후 **같은 밀리초에** `batch-async-1` 에서 `AsyncResolutionRunner starting ... rawSn=88` → AFTER_COMMIT 트리거 확인. `ls_data_aug`(dataAugSn=30) 는 `RESL_720P`, `ls_data_raw` 88 은 `orgnl_raw_sn=900`·`aug_type_cd=RESL_720P` | 예약 시점 PENDING 은 `ResolutionDerivativeFlowIntegrationTest`("예약직후 finalize 전에는 aug 가 PENDING") 가 커버. 근거 라인 `68-123` → 실제 `68-130` (경미 드리프트) |
| TC-RESL-031 | PASS | [실동작] `POST /v1/videos/901/resolution`(`'N'`) → **409** `{"message":"비식별 산출물이 있는 원본 영상만 파생영상을 만들 수 있습니다.","errorCode":"CONFLICT"}` + 로그 `[Video][DeidArtifact] parent has no deident artifact — reject at request rawSn=901 deIdntfYn=N`. `POST /v1/videos/900/resolution`(`'F'`) → **201 CREATED** `rawSn=88` → **★`'F'` 통과 실증** | 요청 입구 `ParentDeidArtifactGuard`(같은 `hasDeidentArtifact()` 판정, 메시지 축 동일)가 먼저 걸러 예약 게이트 문구("…해상도 파생영상을 만들 수 있습니다.")는 잠금 하 재검증용. 판정 헬퍼는 `LsDataRaw.hasDeidentArtifact()` 단일 원천(`'Y'\|'F'`, LsDataRaw.java:444-446) — 3게이트 + `AugmentResultService:390` 이 동일 사용 |
| TC-RESL-032 | PASS | [정적] `ResolutionReservationPersister.java:92-94` — `firstFrameSrcSn==null` → `INVALID_INPUT("대표 프레임을 확인할 수 없습니다.")`, `augRepository.save`(101) **이전**. 테스트 `ResolutionReservationPersisterTest`("srcSn_null이면_LS_DATA_AUG_INSERT전에_INVALID_INPUT을_던진다") | API 경로에서는 `VideoResolutionService.measureFirstFrame` 이 먼저 "실측할 프레임이 없습니다"로 거부하므로 이 분기는 방어적 재검증 |
| TC-RESL-033 | PASS | [실동작] 이미 파생이 있는 `rawSn=26`·`900` 에 동일 프리셋 재요청 → PG `ERROR: duplicate key value violates unique constraint "uk_ls_data_aug_resl" Detail: Key (src_sn, aug_type_cd)=(296, RESL_720P) already exists.` → `CustomException: 동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.`(`ResolutionReservationPersister.java:107`) | 인덱스 술어에 상태 조건 없음 = **상태 무관** ✅. 오케스트레이터(E-4)가 "전 프리셋 실패"를 500 으로 매핑하므로 HTTP 는 500 — E-4 확정 정책과 정합(예약 계층 판정은 409) |
| TC-RESL-034 | PASS | [정적] `ResolutionReservationPersister.resolveSafeDir` — `base.resolve(relative).normalize()` 후 `StorageSubtreePolicy.isDeidentifiedArtifact(base, resolved)` 실패 시 `INVALID_INPUT("출력 경로가 허용된 비식별 저장 경로를 벗어납니다.")`. `isDeidentifiedArtifact` 내부가 `relativeUnder`(=`startsWith(base)`) + `frames/deid`\|`videos` 세그먼트 접두를 **둘 다** 요구 → 두 base 동일 설정에서도 `frames/raw/**` 차단 | **근거 드리프트**: 카탈로그 `157-163` → 실제 **164-170**. 이 경로에 사용자 입력이 도달하지 않아(경로는 전부 내부 상수 + rawSn) 실동작 반증은 불가 |
| TC-RESL-035 | PASS | [정적] `ResolutionSnapshotService.java:102-106` — `"Y".equals(newRaw.getDeIdntfYn())` → `Optional.empty()` (부모 재잠금 이전). 테스트 `ResolutionSnapshotServiceTest`("이미확정된_파생RAW면_멱등skip하고_부모재잠금을_하지않는다") + IT("확정된_파생을_PhaseA가_멱등skip하고_프레임이_중복생성되지_않는다") | |
| TC-RESL-036 | PASS | [실동작] 부모 900(`'F'`)에서 Phase A 통과 실증 — `[Video][ResolutionDerivative][A] snapshot ready rawSn=88 parentRawSn=900 frames=5 scale=3.0 offset=160,0`. [정적] `'N'` 차단은 `ResolutionSnapshotService.java:116-122` `CONFLICT("비식별 산출물이 있는 원본 영상만 파생영상을 확정할 수 있습니다.")` + 테스트 2건 | ★`'F'` 통과 = 2026-07-29 확정 정책과 일치. 결함 아님 |
| TC-RESL-037 | PASS | [정적] `ResolutionSnapshotService.java:149-153` — `findLatestSuccessByDataRawSn(...).map(getDeIdntfFilePathNm).filter(non-blank).orElseThrow(NOT_FOUND "원본 비식별 영상 경로를 찾을 수 없습니다: parentRawSn=…")`. 테스트 "확정게이트1_부모의_비식별_비디오_procLog가_없으면_NOT_FOUND로_거부한다(E-29)" | API 경로에선 `ParentDeidArtifactGuard` 가 먼저 409 로 거름 → Phase A 분기는 예약~확정 창 방어 |
| TC-RESL-038 | PASS | [정적] `ResolutionSnapshotService.java:161-164` `INTERNAL_ERROR("파생할 프레임이 없습니다: parentRawSn=…")`. 테스트 "부모_프레임이_0건이면_fail_fast로_거부한다(#9)" | `measureParentDimensions`(216-221)가 **동일 코드·동일 메시지**로 더 앞에서 던진다 — 기대 단언은 어느 경로로도 성립 |
| TC-RESL-039 | PASS | [실동작] 부모 903(frm 0/1 모두 `vdo_frm_no=7`) → 예약 성공(`newRawSn=90`) 후 `finalize failed rawSn=90 cause=CustomException` → `reserved aug slot released dataAugSn=32` → `failed derivative RAW removed rawSn=90`. 903 은 프레임 경로·파일이 전부 정상이라 **중복 videoFrameNo 외 실패 요인이 없음**(차분 실증). [정적] `ResolutionSnapshotService.java:195-199` `INTERNAL_ERROR("부모 프레임에 중복 videoFrameNo 가 있습니다: …")` | |
| TC-RESL-040 | PASS | [실동작] 부모 902(frm 1 의 `de_idntf_src_file_path_nm=NULL`) → `newRawSn=89` 예약 후 finalize 실패 → RAW 삭제. **원본(`frames/raw/**`) 폴백으로 진행하지 않음** 확인(파생 프레임 0건, 디렉터리 미생성). [정적] `ResolutionSnapshotService.java:240-247` `CONFLICT("비식별 프레임 경로가 없어 파생영상을 생성할 수 없습니다: srcSn=…")` | 원본 픽셀 복제 + `'Y'` 위장 차단 실증 |
| TC-RESL-041 | PASS | [실동작] 부모 26 비식별본 `95b05485-…-mask.mp4` **50,854 bytes** ↔ 파생 3본 `videos/resolution/26/{76,77,78}/RESL_*.mp4` 전부 **50,854 bytes**(재인코딩 없는 복사). `ffprobe` 프레임 실측 — 부모 `320x240` → 76 `1920x1080` / 77 `1280x720` / 78 `854x480`. [정적] `ResolutionFileMaterializer` 는 필드가 `ImageResizer`·`VideoFileCopier`·`ResizeConcurrencyGate` 3개뿐 → **리포지토리 주입 0 · `@Transactional` 0 · 부모 잠금 0** | |
| TC-RESL-042 | PASS | [정적] `ResolutionFileMaterializer.java:66-68` — `!videoFileCopier.exists(deidVideoSrc)` → `NOT_FOUND("원본 비식별 영상 파일을 찾을 수 없습니다.")`, 원본 폴백 분기 부재. 테스트 "비식별_비디오원본이_없으면_NOT_FOUND로_실패하고_게이트를_반환한다" | |
| TC-RESL-043 | PASS | [정적] `materialize`(62-80) 가 `resizeGate.acquire()`(63) → **비디오 복사(69) + 전 프레임 리사이즈(72-74)** → `finally { resizeGate.release(); }`(77-79). `ResizeConcurrencyGate` 는 단일 `@Component` 의 fair `Semaphore(max=2)` + `tryAcquire(5s)` → 초과 시 `TOO_MANY_REQUESTS(429)` | 세마포어를 서비스마다 두지 않아 상한 배수화 없음 확인 |
| TC-RESL-044 | PASS | [실동작] `[C] persisted rawSn=88 orgnlRawSn=900 dataAugSn=30 frames=5 labels=0 metas=0 metaReviews=0`. DB: `ls_data_src` 파생 5행 / `ls_data_aug`(30) `ACCEPTED`+`new_raw_sn=88` / `ls_data_raw`(88) `de_ident_yn='Y'`·`data_stts_cd='COMPLETED'` / `ls_deident_proc_log` `SUCCEEDED`·`reg_id='resolution-derivative'`·경로=파생 비디오. [정적] 순서 = 프레임(122) → 라벨(126) → 확정블록(134-143) → `copyMetaAndReviews`(148) | 확정 블록 **이후** 메타 복사 ✅(TC-RESL-071 과 동일 근거) |
| TC-RESL-045 | PASS | [실동작] `ls_data_aug_lbl_map` — dataAugSn 5/6/7(부모 4, 1080P/720P/480P): `scale_x==scale_y` = **4.5/4.5 · 3.0/3.0 · 2.0/2.0**, `coord_recalc_yn='Y'`. 좌표 실측(aug 5, 320×240→1920×1080, scale 4.5·offsetX 240·offsetY 0): BBOX `[[10,10],[50,50]]`→`[[285,45],[465,225]]`(=10·4.5+240 / 10·4.5), SKELETON `[0,0,0]`→`[240,0,0]`(가시성 플래그 보존), POLYGON 26점 전부 동일 변환. 로그 `offset=160,0`(1280×720) · `offset=107,0`(854×480) 가 `LetterboxTransform` 계산과 일치 | **축별 독립 배율 아님**(구 E-ISSUE-26 폐기) 실증. 매핑행에 오프셋 컬럼 없음(추적성 한계 — 카탈로그 명시대로) |
| TC-RESL-046 | PASS | [실동작] 부모 26 은 라벨 0건(`ls_data_lbl` 0) → `[C] persisted … labels=0`, `ls_data_aug_lbl_map`(15/16/17) 0행. [정적] `ResolutionPersistService.java:386-389` `parentLabels.isEmpty() → return 0` | |
| ~~TC-RESL-047~~ | — | **[폐기 2026-07-30]** 검증 대상 아님 | 분모 제외 |
| TC-RESL-048 | PASS | [정적] `ResolutionPersistService.java:328-342` — Phase C 가 `findLatestSuccessByDataRawSn` 재조회 후 `currentDeid==null \|\| !currentDeid.equals(snapshotDeid)` → `CONFLICT("스냅샷 이후 원본 비식별본이 변경되어 파생영상을 확정할 수 없습니다.")`. `resolveQuietly`(362-369)가 예외 시 `null` 반환 → **해석 불가도 불일치로 abort** ✅. 테스트 "stale창_최신비식별procLog경로가_스냅샷과_다르면_CONFLICT로_abort한다(H-1_①경로게이트)" + IT "진짜_A~C창_PhaseB중_부모비식별본이_신규경로로_교체되면_PhaseC가_CONFLICT하고_cleanup이_실제파일을_삭제한다" | A→C 창이 실측 ~40ms 라 수동 레이스 주입 불가 → IT 로 커버 |
| TC-RESL-049 | PASS | [실동작] 부모 905 의 비식별본 mtime 을 2030-01-01 로 만든 뒤 요청 → `[C] parent deident file replaced since snapshot (mtime) — abort (…) parentRawSn=905 newRawSn=97` → 러너 cleanup + FAILED + RAW 삭제. [정적] `344-359` — `Files.exists` **일 때만** 판정, `IOException` 은 WARN 후 통과 | `CustomException` 은 `RuntimeException` 이라 `catch (IOException)` 에 삼켜지지 않음(확인) |
| TC-RESL-050 | PASS | [실동작] 부모 900(`'F'`)에서 Phase C 통과 → `[C] persisted rawSn=88 orgnlRawSn=900`. [정적] `90-103` — `findByRawSnForUpdate(parentRawSn)`(94) → `findByRawSnForUpdate(newRawSn)`(113) 로 **parent → newRaw 잠금 순서 고정**, `'N'`/null 만 `CONFLICT` | |
| TC-RESL-051 | PASS | [정적] `111-119` — newRaw 재잠금 후 `"Y".equals(deIdntfYn)` → `Result.SKIPPED`(프레임 INSERT 이전). 테스트 IT "같은_파생RAW를_2스레드가_동시_finalize해도_프레임은_1회만_삽입된다(#5_승자보호)" | |
| TC-RESL-052 | PASS | [실동작] 파생 77 의 `ls_data_src` 5행 — `src_file_path_nm` **전부 NULL**, `de_idntf_src_file_path_nm=/app/storage/deidentified/frames/deid/77/frame-N.jpg`, `vdo_frm_no` 0/30/60/90/120(부모 보존), 개인정보 3필드 전부 NULL(부모가 NULL). [정적] `275-292` `LsDataSrc.create(newRawSn, frameNo, videoFrameNo, **null**, dst, shtDt, parent?.anony/psdo/prvc)` | 정책 A(파생엔 원본 픽셀 부재) 실증 |
| TC-RESL-053 | PASS | [실동작] 3회 실패 전부에서 `[C] reserved aug slot released dataAugSn=31/32/43 augTypeCd=RESL_720P/RESL_720P/RESL_480P` + `ls_data_aug` 잔존 0행 확인 → 동일 프리셋 재요청 락아웃 없음. [정적] `200-222` | |
| TC-RESL-054 | **PARTIAL** | [정적] `205-210` — `lblMapRepository.findAllByDataAugSn(dataAugSn)` 비어있지 않으면 `WARN` + 삭제 skip ✅ (**케이스의 문자적 단언은 충족**). 그러나 **부모 라벨이 0건이면 승자도 라벨맵을 만들지 않아 이 보호가 무효**다 — 실측으로 부모 26 은 라벨 0건이고 성공 파생(76/77/78)의 라벨맵도 0행이었다 | → **E-ISSUE-61** |
| TC-RESL-055 | PASS | [정적] `211-221` — `augTypeCd.startsWith(LsDataAug.RESL_PREFIX)` 아니면 `WARN`("reserved aug is not a resolution reservation — skip release") 후 미삭제. 테스트 "releaseReservedAug_RESL_접두가_아니면_삭제하지않는다(오배송_방어)" | |
| TC-RESL-056 | PASS | [정적] `178-187` — `findByRawSnForUpdate`(PESSIMISTIC_WRITE) 로 `deIdntfYn=='Y' \|\| dataSttsCd==COMPLETED` 판정, `readOnly` 미지정(PG 제약 대응). 잔여 창(승자-뒤짐 / 락 타임아웃 페일오픈)이 javadoc 161-176 에 명시돼 카탈로그 서술과 일치. 테스트 2건 | 카탈로그의 "정직한 한계 명시" 그대로 |
| TC-RESL-057 | PASS | [실동작] rawSn 88 로그 시퀀스: `starting …`(08:47:27.760) → `[A] snapshot ready`(…764) → `[B] materialized`(…854) → `[C] persisted`(…858) → `resolution derivative finalize completed rawSn=88`(…859). 예외 없음 | 76/77/78 도 동일 |
| TC-RESL-058 | PASS | [정적] `AsyncResolutionRunner.java:62-66` — `opt.isEmpty()` → INFO + `return`(B·C 미실행). 테스트 "PhaseA가_멱등skip이면_B_C_정리_전이_모두_수행하지않는다" | |
| TC-RESL-059 | PASS | [정적] `73-79` — `Result.SKIPPED` 시 INFO 후 `return`, `handleFailure`/cleanup 미호출. 테스트 "PhaseC가_SKIPPED_중복finalize패자면_정리도_FAILED전이도_하지않는다" | 승자 산출물 보호 |
| TC-RESL-060 | PASS | [정적] `98-113` — `handleFailure` 첫 블록이 `persistService.isAlreadyFinalized(newRawSn)` 이고 true 면 **cleanup(126-141)·release(145)·FAILED(150) 이전에** `return`. 재조회 예외는 보수적 false. 테스트 "실패했지만_이미_승자가_확정(Y)했으면_cleanup도_FAILED전이도_aug해제도_스킵한다(M-1_승자산출물보호)" | |
| TC-RESL-061 | PASS | [실동작] 실패 3건 전부 — `WARN [AsyncResolutionRunner] derivative discarded — finalize failed, reservation released rawSn=…` → `resolution.finalize.failed` **COUNT=2.0**(내 유도 2건; 나머지 1건은 동일 세션 후속) → (스냅샷 있는 97 은) 아티팩트 cleanup → `reserved aug slot released` → `markRawDataFailed` | `[BatchTransition] raw data status not found rawSn=… target=FAILED` WARN 은 파생에 `LS_RAW_DATA_STATUS` 행이 없어서 나는 정상 로그 — `ls_data_raw.data_stts_cd` 는 FAILED 로 전이됨(고아 삭제가 그 조건으로 성공한 것이 증거) |
| TC-RESL-062 | PASS | [실동작] rawSn 97 실패 후 — `frames/deid/97/` **디렉터리째 삭제**, `videos/resolution/905/97/RESL_480P.mp4` 삭제. 반면 부모 26 의 `frames/deid/26/` **5장 온전**, 부모 비식별 영상(`videos/qa-e5/stale.mp4`) 온전, `frames/raw/**` 무변동. [정적] cleanup 대상 키가 `deidFramesDir(newRawSn)` + `videoDst`(파생 RAW_SN 포함 경로) | 잔여: 빈 디렉터리 `videos/resolution/905/97/` 는 남는다(파일만 삭제) → **E-ISSUE-65**(LOW) |
| TC-RESL-063 | PASS | [실동작] Phase A/C 예외가 발생한 3건 모두 HTTP 응답은 **201 CREATED** 였고 async 예외가 호출자에 전파되지 않음. [정적] `runAsync`(47-50) → `finalizeDerivative` 가 `catch (RuntimeException)`(81-85)로 삼킴 | |
| TC-RESL-064 | PASS | [정적] `LsDataAug.buildResolution` — `augResTypeCd == null \|\| !startsWith(RESL_PREFIX)` → `INVALID_INPUT("해상도 파생 코드는 'RESL_' 접두여야 합니다.")` | **근거 드리프트**: 카탈로그 `LsDataAug.java:214` → 실제 **320-323**(`createResolutionPending` 은 306). 전용 단위테스트 없음(커버리지 갭) |
| TC-RESL-065 | PASS | [정적] `LsDataAug.markResolutionGenerated` — RESL 접두 가드(341-343) + `!STTS_PENDING.equals(augProcSttsCd)` → `CONFLICT("이미 처리된 해상도 파생 행입니다. status=…")` | **근거 드리프트**: 카탈로그 `248-249` → 실제 **340-348**(이중전이 가드 344-347) |
| TC-RESL-066 | PASS | [실동작] `[C] failed derivative RAW removed rawSn=89 / 90 / 97` + `ls_data_raw` 에 해당 행 부재 확인(고아 무한 누적 해소). [정적] `237-259` 잠금 후 4조건 재확인 + `VideoRepository.deleteFailedDerivative` 네이티브 DELETE 에 `ORGNL_RAW_SN IS NOT NULL AND DATA_STTS_CD='FAILED' AND NOT EXISTS(LS_DATA_SRC)` 동봉 | DELETE 문에는 `DE_IDENT_YN <> 'Y'` 만 빠져 있다(카탈로그 "동일 조건 동봉" 문구와 미세 불일치) — 같은 tx 의 `FOR UPDATE` 로 행이 잠겨 있고 확정 경로가 `'Y'` 와 `COMPLETED` 를 항상 동시 설정하므로 기능적 갭 없음 → **E-ISSUE-64**(LOW, 문서/방어 일치성) |
| TC-RESL-067 | PASS | [정적] `AsyncResolutionRunner.java:126-141`(cleanup 결과를 `artifactsClean` 에 수집, 잔존 시 ERROR + `resolution.cleanup.failed`) + `155-158`(`if (!artifactsClean) { WARN "keep FAILED derivative RAW — artifacts remain on disk"; return; }` → `deleteFailedDerivativeRaw` 미호출). 테스트 "cleanup이_잔존false를_반환하면_cleanupFailed_메트릭을_올린다" | 실동작으로 cleanup 실패를 유도하지 못함(파일 삭제가 항상 성공) |
| TC-RESL-068 | PASS | [정적] `ResolutionFileMaterializer.java:119-138` — `legacyRoot = {rawBase}/resolution`, `legacyDir = legacyRoot/{newRawSn}`, `legacyDir.startsWith(legacyRoot) && !legacyDir.equals(legacyRoot)` 통과 시에만 재귀 삭제. raw base 미설정은 `IllegalStateException` → catch 에서 로그만(=`clean` 불변, 잔존 판정 미반영) | **전용 테스트 없음**(`ResolutionFileMaterializerTest` 에 레거시 케이스 부재) — 커버리지 갭 |
| TC-RESL-069 | PASS | [실동작] `ls_data_raw.raw_file_path_nm` — `…/videos/resolution/26/76/RESL_1080P.mp4`, `…/26/77/RESL_720P.mp4`, `…/26/78/RESL_480P.mp4`, `…/videos/resolution/900/88/RESL_720P.mp4` → **키에 파생 RAW_SN 포함** ✅. 디스크에도 파생별 개별 파일 3개 존재(공유 0). [정적] 잠정 `.pending/` 경로(137-141)는 같은 트랜잭션에서 `assignDerivativeVideoPath`(119)로 즉시 교체 — 커밋된 값에 `.pending` 이 관측된 사례 0건(DB 전수 확인) | |
| TC-RESL-070 | PASS | [실동작] 부모 26 메타(metaSn=123, key `0-5`) → 파생 77 에 metaSn=161 로 값 동일 복사. 검수행: 부모 `(123, VLM, APPROVED, AI_SERVER)` → 파생 `(161, VLM, **PENDING**, AI_SERVER)` — **유형·출처 승계, APPROVED 미승계** ✅. 로그 `[C] persisted rawSn=87 … metas=1 metaReviews=1`. [정적] `DerivedMetaCopier.java:72-158` — `upsertMetaBatch` 단일 배치, `(metaSn, metaTypeCd)` 선재 skip(114-117,138-140) | `video.*` 전체 복사 정책은 UNCERTAINTIES #18 확정과 일치 |
| TC-RESL-071 | PASS | [정적] `ResolutionPersistService.java:145-148` — `copyMetaAndReviews` 가 확정 블록(`markResolutionGenerated` 135 / `markDeidentified` 138 / `markCompleted` 139 / procLog 143) **이후** 호출. `LsDataMetaRepositoryImpl.upsertMetaBatch:34-56` 이 `entityManager.flush()`(39) → 배치 → `entityManager.clear()`(55) 로 순서 계약 실체 확인. [실동작] 파생 77 이 **확정(`'Y'`+COMPLETED+ACCEPTED)** 과 **메타 복사** 를 동시에 만족 = 순서가 뒤바뀌지 않았음의 종단 증거. 테스트 IT "해상도_finalize_실DB에서_부모메타_전건복사_VLM검수행만_PENDING신규_이면서_파생RAW는_확정유지된다(순서계약_HIGH4)" | |

### 집계

| 판정 | 건수 |
|------|--:|
| PASS | **40** |
| PARTIAL | **1** (TC-RESL-054) |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| (폐기 제외) | TC-RESL-047 |
| **합계(검증 대상)** | **41** |

---

## 2. 이슈 대장 (E-ISSUE-61 ~ 65)

### [E-ISSUE-61] TC-RESL-054 — 파생 프레임 목적지 파일명이 부모 프레임 basename 에서 파생되어 **서로 다른 프레임이 같은 파일로 덮어써진다**(무경고 확정)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 부모 프레임 N개는 파생 프레임 N개의 **서로 다른** 파일로 산출돼야 한다. Phase A 는 이미 "라벨 이중매핑"을 막으려고 중복 `videoFrameNo` 를 fail-fast 하는데(`ResolutionSnapshotService:195-199`), 같은 급의 위험인 **목적지 경로 충돌**에도 동일한 fail-fast 가 있어야 한다. 충돌을 허용하면 파생 프레임 i 의 라벨이 프레임 j 의 픽셀 위에 얹히는 데이터 정합 붕괴가 생기고, 그 산출물이 export→관제로 나간다.
- **현재 동작(이슈 내용)**: 목적지 파일명이 부모 `deidFilePath` 의 basename 그대로다. 중복 검사 없음.
  ```java
  // ResolutionSnapshotService.java:205-208
  Path fdst = resolveSafeDir(base,
          StorageSubtreePolicy.deidFramesDir(newRawSn) + "/" + fileNameOf(deidFrameSrc, pf));
  specs.add(new ResolutionSnapshot.FrameSpec(
          pf.getSrcSn(), pf.getFrameNo(), pf.getVideoFrameNo(), pf.getShtDt(), fsrc, fdst));
  // fileNameOf(231-234): Paths.get(frameSrc).getFileName()  ← 부모 파일명 그대로, 유일성 미검증
  ```
  실측(부모 906: frm0=`…/deid/26/frame-0.jpg`, frm1=`…/deid/27/frame-0.jpg`) → 파생 98 확정 **성공**(aug `ACCEPTED`), 그런데:
  ```
  src_sn | raw_sn | frm_no | vdo_frm_no | de_idntf_src_file_path_nm
     466 |     98 |      0 |          0 | /app/storage/deidentified/frames/deid/98/frame-0.jpg
     467 |     98 |      1 |         30 | /app/storage/deidentified/frames/deid/98/frame-0.jpg   ← 동일 경로
  $ ls /app/storage/deidentified/frames/deid/98/   →   frame-0.jpg  (1개뿐)
  ```
  두 프레임 행이 한 파일을 가리키고, 나중 리사이즈가 앞 프레임을 덮어썼다. 경고 로그도 없다.
- **재현/확인 경로**:
  ```sql
  INSERT INTO ls_data_raw (raw_sn,vms_clip_id,vms_cctv_id,prvc_type_cd,prvc_yn,de_ident_yn,raw_file_path_nm,data_stts_cd)
   VALUES (906,'QA','C','ANONY','N','Y','/app/storage/raw/seed/clip-9103.mp4','COMPLETED');
  INSERT INTO ls_raw_data_status VALUES (906,'APPROVED',0,0,now(),0);
  INSERT INTO ls_data_src (raw_sn,frm_no,vdo_frm_no,src_file_path_nm,de_idntf_src_file_path_nm,sht_dt) VALUES
   (906,0,0,'/app/storage/raw/frames/raw/26/frame-0.jpg','/app/storage/deidentified/frames/deid/26/frame-0.jpg',now()),
   (906,1,30,'/app/storage/raw/frames/raw/27/frame-0.jpg','/app/storage/deidentified/frames/deid/27/frame-0.jpg',now());
  INSERT INTO ls_deident_proc_log (data_raw_sn,orgnl_file_path_nm,de_idntf_file_path_nm,proc_stts_cd)
   VALUES (906,'/x','/app/storage/deidentified/videos/resolution/26/77/RESL_720P.mp4','SUCCEEDED');
  ```
  ```bash
  curl -X POST localhost:18081/api/v1/videos/906/resolution -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"presets":["RESL_480P"]}'   # → 201, 이후 확정 성공
  ```
- **영향**: 데이터 정합(라벨↔픽셀 불일치) · export/데이터마트로 잘못된 프레임 쌍 유출. CWE-706(경로 → 리소스 잘못된 해석) / CWE-345(데이터 진정성 미검증). **실환경 도달성은 낮다** — 정상 추출은 `frames/deid/{rawSn}/frame-{n}.jpg` 로 raw 별 유일하고 `(RAW_SN, FRM_NO)` UK 가 basename 중복을 사실상 막는다. 다만 방어 없이 "관행"에만 의존하고 있고, 코드베이스 자체가 같은 급의 위험(`videoFrameNo` 중복)엔 fail-fast 를 둔 것과 비대칭이다.
- **수정 방향(제안)**: `ResolutionSnapshotService.buildFrameSpecs` 에서 ①`seenFrameKeys` 와 동일한 방식으로 `Set<Path> seenDst` 유일성 검사를 추가해 충돌 시 `INTERNAL_ERROR` fail-fast, 또는 ②더 근본적으로 목적지 파일명을 부모 basename 이 아니라 **파생 자신의 프레임 번호**(`frame-{frameNo}.{ext}`)로 결정론 생성. ②가 부모 경로 형식에 대한 의존을 없애 낫다.

### [E-ISSUE-62] TC-RESL-054 — `releaseReservedAug` 의 "승자 보호"가 **부모 라벨 0건 영상에서 무효**
- **심각도**: LOW
- **기대 동작(기대효과)**: 중복 finalize 패자의 실패 정리가 승자의 확정 결과(`LS_DATA_AUG` ACCEPTED 행)를 지우면 안 된다. 지워지면 ①증강 이력에서 파생이 사라지고 ②`UK_LS_DATA_AUG_RESL` 슬롯이 풀려 같은 (영상 × 프리셋) 재요청이 **두 번째 파생 RAW** 를 만들어 "요청 1회 = 파생영상 1건" 계약이 깨진다.
- **현재 동작(이슈 내용)**: 승자 보호 판정을 라벨맵 참조 유무 **하나에만** 의존한다.
  ```java
  // ResolutionPersistService.java:205-210
  List<LsDataAugLblMap> refs = lblMapRepository.findAllByDataAugSn(dataAugSn);
  if (!refs.isEmpty()) { log.warn("… skip release …"); return; }
  ```
  그런데 `copyScaledLabels`(386-389)는 **부모 라벨이 0건이면 매핑 행을 하나도 만들지 않는다**. 실측: 부모 26 은 라벨 0건이고 성공 파생 76/77/78 의 `ls_data_aug_lbl_map` 도 0행(`select data_aug_sn,count(*) … group by` 결과에 15/16/17 부재). 즉 라벨 없는 영상의 승자 aug 는 이 방어를 전혀 받지 못한다.
- **재현/확인 경로**: 라벨 0건 부모로 파생 확정 후 `select * from ls_data_aug_lbl_map where data_aug_sn = {승자 augSn}` → 0행. 이 상태에서 `handleFailure` 의 `isAlreadyFinalized` 가 false 로 떨어지는 잔여 창(`ResolutionPersistService` javadoc 168-175 가 명시한 "승자-뒤짐" / `lock_timeout` 페일오픈)에 진입하면 승자 aug 가 삭제된다.
- **영향**: 데이터 정합/이력 유실. 현 트리거가 단일 러너(AFTER_COMMIT 1회 등록)라 실제 재현 난도는 매우 높음 → LOW. 다만 `LS_DATA_AUG` 는 증강 이력·집계의 진실원이라 유실 시 조용하다.
- **수정 방향(제안)**: 참조 검사 축을 라벨맵에서 **`LS_DATA_AUG.NEW_RAW_SN` / `AUG_PROC_STTS_CD`** 로 옮긴다 — `augProcSttsCd != PENDING`(=이미 ACCEPTED 로 확정 전이됨)이면 삭제하지 않는 조건을 추가하면 라벨 유무와 무관하게 승자가 보호된다(V155 `new_raw_sn` 이 이미 승자 파생을 가리키므로 그것과 `newRawSn` 일치 여부도 함께 쓸 수 있다).

### [E-ISSUE-63] TC-RESL-049 — Phase C stale 게이트의 `IOException` 폴백 주석이 **이미 폐기된 신고 게이트**를 근거로 든다
- **심각도**: LOW
- **기대 동작(기대효과)**: 보수적 통과(페일오픈)를 선택한 근거는 실재하는 다른 방어를 가리켜야 한다. 없는 방어를 근거로 적어두면 다음 수정자가 "다른 게이트가 막아준다"고 오판해 실제 폐쇄를 미룬다.
- **현재 동작(이슈 내용)**:
  ```java
  // ResolutionPersistService.java:355-359
  } catch (IOException e) {
      // stat 실패는 결정적 게이트(①②)가 이미 통과했으므로 보수적으로 통과(로그만). PII 는 ②신고 게이트로 닫힘.
      log.warn("[Video][ResolutionDerivative][C] deident mtime check skipped …");
  }
  ```
  같은 메서드의 javadoc(312-314)은 **"구 조건 'capturedAt 이후 신고 이력 존재'는 제거됐다"** 고 명시한다. 즉 "②신고 게이트"는 더 이상 존재하지 않는다. 남은 게이트는 ①경로 불일치와 ②mtime 뿐이고, 이 catch 는 바로 그 ②를 스킵하는 자리다.
- **재현/확인 경로**: `ResolutionPersistService.java:306-360` 정독 — 주석 내 게이트 번호(①②)가 javadoc 의 ①② 와 의미가 어긋난다.
- **영향**: 문서 정합/유지보수 리스크(기능 영향 없음).
- **수정 방향(제안)**: 주석을 사실대로 정정 — "stat 실패 시에는 경로 게이트(①)만으로 판정하며 제자리 교체는 검출하지 못한다(정직한 한계)". 게이트 번호도 javadoc 과 통일.

### [E-ISSUE-64] TC-RESL-066 — 고아 파생 RAW DELETE 문에 `DE_IDENT_YN <> 'Y'` 조건이 빠져 있어 카탈로그의 "동일 조건 동봉"과 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: "검사~삭제 창 0건 삭제"를 위해 Java 사전검사 4조건(①파생임 ②`'Y'` 아님 ③FAILED ④프레임 0건)이 **그대로** DELETE 술어에 실려야 한다(카탈로그 TC-RESL-066 기대결과 문구).
- **현재 동작(이슈 내용)**: 4조건 중 3개만 SQL 에 있다.
  ```sql
  -- VideoRepository.deleteFailedDerivative (396-401)
  DELETE FROM LS_DATA_RAW
   WHERE RAW_SN = :rawSn
     AND ORGNL_RAW_SN IS NOT NULL
     AND DATA_STTS_CD = 'FAILED'
     AND NOT EXISTS (SELECT 1 FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn)
  -- ② DE_IDENT_YN <> 'Y' 부재
  ```
- **재현/확인 경로**: `ResolutionPersistService.java:242-254` 의 Java 검사와 `VideoRepository.java:394-402` SQL 술어 대조.
- **영향**: **실질 기능 갭은 없다** — 삭제는 같은 트랜잭션이 `findByRawSnForUpdate` 로 잡은 행 잠금 하에서 수행되고, 확정 경로(`ResolutionPersistService:138-139`)가 `markDeidentified("Y")` 와 `markCompleted()` 를 **항상 함께** 수행하므로 `DATA_STTS_CD='FAILED'` 술어가 `'Y'` 행을 이미 배제한다. 방어 다중화 관점의 불일치이자 카탈로그 문구 부정확.
- **수정 방향(제안)**: SQL 에 `AND (DE_IDENT_YN IS NULL OR DE_IDENT_YN <> 'Y')` 를 추가하거나, 카탈로그 기대결과를 "3조건 동봉 + `'Y'` 는 상태 조건으로 간접 배제"로 정정.

### [E-ISSUE-65] TC-RESL-062 — cleanup 이 파생 비디오 **파일**만 지우고 빈 디렉터리 트리를 남긴다
- **심각도**: LOW
- **기대 동작(기대효과)**: 실패한 파생의 산출물 흔적이 저장소에 누적되지 않아야 한다(경로 키에 파생 RAW_SN 이 들어가므로 재시도마다 새 디렉터리가 생긴다).
- **현재 동작(이슈 내용)**: `cleanup` 은 프레임은 디렉터리째 재귀 삭제하지만(`deleteRecursivelyQuietly(framesDir)`, 108-112) 비디오는 파일 단위 삭제만 한다.
  ```java
  // ResolutionFileMaterializer.java:141-152
  if (videoDst != null) { deleteFileQuietly(videoDst); if (Files.exists(videoDst)) clean = false; }
  ```
  실측(rawSn 97 실패 후):
  ```
  $ ls -la /app/storage/deidentified/videos/resolution/905/
  drwxr-xr-x 2 app app 4096 … 97      ← 파일은 사라졌지만 디렉터리 잔존
  ```
- **재현/확인 경로**: 위 §0 의 905 시나리오 후 `ls /app/storage/deidentified/videos/resolution/905/`.
- **영향**: 저장소 위생(파일시스템 inode 누적). 기능·보안 영향 없음.
- **수정 방향(제안)**: `deleteFileQuietly(videoDst)` 이후 부모 디렉터리(`videoDst.getParent()`)가 비어 있으면 `Files.deleteIfExists` 로 함께 제거(루트 `videos/resolution` 자기자신 보호 조건 포함). 잔존 판정(`clean`)에는 반영하지 않는다.

---

## 3. 자동테스트 커버리지 대조

| 테스트 파일 | E-5 커버 케이스 |
|------|------|
| `video/ResolutionReservationPersisterTest.java` | 030 · 031 · 032 · 033 |
| `video/ResolutionSnapshotServiceTest.java` | 035 · 036 · 037 · 038 · 039 · 040 (+ deid base 격리) |
| `video/ResolutionPersistServiceTest.java` | 044 · 048 · 049 · 050 · 051 · 052 · 053 · 054 · 055 · 056 · 066 |
| `video/ResolutionFileMaterializerTest.java` | 041 · 042 · 043 · 062 · 067 |
| `video/AsyncResolutionRunnerTest.java` | 057 · 058 · 059 · 060 · 061 · 063 · 067 |
| `video/ResolutionLetterboxTest.java` | 045 |
| `video/ResolutionReservationPathIT.java` | 069 |
| `video/ResolutionDerivativeFlowIntegrationTest.java` | 030 · 033 · 035 · 044 · 045 · 048 · 050 · 051 · 052 · 053 · 069 · 070 · 071 |
| `meta/DerivedMetaCopierTest.java` · `DerivedMetaCopierIT.java` | 070 · 071 |

- baseline(`_raw/test-baseline.md`) 기준 위 전부 **통과**(backend 실패 0건).
- **커버리지 갭(전용 테스트 없음)**: **TC-RESL-034**(예약 출력경로 CWE-22) · **TC-RESL-064**(`RESL_` 접두 강제) · **TC-RESL-065**(이중전이 409) · **TC-RESL-068**(레거시 raw base 디렉터리 정리). 네 건 모두 정적 대조로는 구현이 확인되나 회귀 가드가 없다.

---

## 4. 근거(file:line) 드리프트

| TC | 카탈로그 근거 | 실제 위치 | 비고 |
|----|------|------|------|
| TC-RESL-030 | `ResolutionReservationPersister.java:68-123` | `68-130` | 메서드 끝 확장(경미) |
| TC-RESL-034 | `ResolutionReservationPersister.java:157-163` | **`164-170`** | `resolveSafeDir` 위치 이동 |
| TC-RESL-064 | `LsDataAug.java:214,228` | **`306`(createResolutionPending) · `320-323`(접두 가드)** | 214 는 무관한 빌더 생성자 |
| TC-RESL-065 | `LsDataAug.java:248-249` | **`340-348`(가드 341-343 · 344-347)** | |

그 외 37건은 근거 라인이 실제 코드와 일치했다.

---

## 5. ★ 확증편향 반증 결과 (핵심 위험 4축)

1. **3곳 부모 게이트가 `'N'`만 막고 `'F'`는 통과하는가** → **개별 실증 완료.**
   - 예약(`ResolutionReservationPersister:83-88`) — 부모 900(`'F'`) 요청이 **201** 로 예약됨.
   - Phase A(`ResolutionSnapshotService:116-122`) — `[A] snapshot ready rawSn=88 parentRawSn=900` 로그.
   - Phase C(`ResolutionPersistService:97-103`) — `[C] persisted rawSn=88 orgnlRawSn=900` 로그.
   - `'N'`(부모 901) 은 **409**. 세 게이트 모두 `LsDataRaw.hasDeidentArtifact()`(`'Y'|'F'`) 단일 헬퍼를 호출(grep 전수: 예약/A/C + `AugmentResultService:390` + `ParentDeidArtifactGuard:70` + `DeidentReportService:330`) → 축 드리프트 없음.
2. **Phase A 산출물 실재 검증이 fail-closed 인가** → procLog 부재 `NOT_FOUND`(149-153) · deid 프레임 경로 blank/null `CONFLICT`(240-247) 확인. **후자는 실동작으로도 실증**(부모 902 → 확정 실패, 원본 프레임 폴백 흔적 0). 두 경로 어디에도 `frames/raw/**` 폴백 분기가 없다(`resolveSafeDeidSource` 가 `isDeidentifiedArtifact` 로 거부).
3. **Phase C stale 창 게이트의 판정축이 신고가 아니라 복사 원자성인가** → **맞다.** `assertDeidentNotReplacedSince`(322-360) 에 신고(`DE_IDNTF_YN`) 참조가 **없고**, 조건은 ①최신 SUCCESS procLog 경로 불일치 ②파일 mtime > capturedAt 둘뿐. ②는 미래 mtime 주입으로 **실동작 실증**(rawSn 97 abort + cleanup).
4. **예약 동시성(같은 프리셋 중복 예약 방지)** → DB 부분 유니크 인덱스가 **상태 무관** 술어(`WHERE aug_type_cd ~~ 'RESL\_%'`)로 실재하고, 응용은 `save`+`flush` 로 **새 RAW/파일 생성 이전에** 위반을 감지해 `CONFLICT` 로 변환(101-109). 실동작으로 PG 23505 → 409 변환 확인. 동시(2스레드) 경합은 IT("같은영상_같은프리셋_동시요청시_1건성공_1건CONFLICT")가 커버.

**추가 반증에서 나온 것**: 위 4축은 전부 방어가 성립했고, 실제 결함은 **방어가 아예 없는 축**에서 나왔다 — 목적지 파일명 유일성(E-ISSUE-61). Phase A 가 `videoFrameNo` 중복은 fail-fast 하면서 목적지 경로 중복은 검사하지 않는 비대칭이 그 지점이다.
# E 클러스터 part5 — E-6. 데이터셋 Export (23건)

- 대상 섹션: `docs/test-cases/E-augment-resolution-export-meta.md` → `## E-6. 데이터셋 Export (DatasetExportService / Bridge / Runner / TxService / Recoverer)`
- 검증 일시: 2026-08-02 08:45~09:05 (KST)
- 검증 스택: 실행 중 풀스택(`klid-backend` 2026-08-01 23:10 빌드, Flyway **V158** 적용 실측 — `stack-bringup.md` 의 "V146 구버전" 기술은 그 뒤 재빌드로 해소됨), mock-server(:9400) 경유
- 판정 근거 표기: `[실동작]` = 실제 요청·배치 구동 + DB/로그/메트릭 실측 / `[정적]` = 코드 대조 + 기존 자동테스트 커버

## 0. 이번 검증에서 실제로 구동한 시나리오 (재현 가능)

| # | 시각 | 조작 | 관측 |
|--:|------|------|------|
| 1 | 08:47 | `POST /v1/dev/autolabel-test` (신규 rawSn=**94**) → 선두 비식별(mock KPST) → AUTO 마킹(30f) → 배정 → 검수제출 → 검수시작 | `de_ident_yn N→Y`, 프레임 5건(448~452) |
| 2 | 08:49:16 | `POST /v1/reviews/94/approve` | 브릿지 AFTER_COMMIT → `runApprovalAsync` → **v1 SUCCEEDED**(`export_sn=37`, `frame_cnt=10`) → **그 다음** `TASK_COMPLETED sent`(+3ms) |
| 3 | 08:49:37 | 유령 프레임 1건 INSERT(존재하지 않는 이미지 경로, `src_sn=453`) + `PUT /v1/frames/448/description` | 60s 디바운스 flush(08:50:38) → 재export → **v2 PARTIAL**(written=10, skipped=2) + `skipped_frames` 메트릭 +2 |
| 4 | 08:51:13 | `POST /v1/labels/448/deident-report` (신고 접수) | `LS_DATA_RAW.DE_IDENT_YN='F'`, `rprtSn=23` |
| 5 | 08:51:24 | `PUT /v1/frames/449/description` (신고 구간 수정) | flush(08:52:19) → **`export blocked — deident report open rawSn=94`** → `LS_DATASET_EXPORT` 행 미생성 · `outcome=deident_blocked` 메트릭 1 · **통지 미발송** |
| 6 | 08:57:44 | `POST /v1/deident-reports/23/resolve`(비식별 산출물 mtime 갱신 후) | `'F'→'Y'` → `DeidentReportResolvedEvent` → `runApprovalAsync` → **v4 전량 재생성** → 통지 재개 |
| 7 | 08:58/09:12 | 회수기 앵커용 FAILED 행 INSERT(`export_sn=42` rawSn=94 v5, `reg_dt` 60분 전) → 09:12:19 Quartz tick | `retriggered failed exports count=1 rawSns=[94]` → `RTY_NMTM 0→1` → **v6 재산출** → 통지 재개 |
| 8 | (병행 관측) | 다른 세션 에이전트의 `PUT /v1/videos/26/environment-meta`(08:47:26) | flush(08:48:28) → 재export **v9 SUCCEEDED** → TASK_MODIFIED — 촬영환경 수정 경로 실동작 확인 |

> ⚠ 이 스택은 다른 QA 에이전트가 동시에 사용 중이었다(rawSn 4·26·80·81 등). 위 표의 rawSn=94 는 **이번 검증이 새로 만든 전용 영상**이며, 타 rawSn 근거는 로그 타임스탬프로 귀속을 확인한 것만 인용했다.

## 1. 케이스별 판정

| ID | 케이스명 | 판정 | 근거 확인 | 상세 |
|----|---------|:----:|------|------|
| TC-EXPORT-001 | 검수승인 AFTER_COMMIT 강제 재생성 | PASS | [실동작] | `08:49:16.209 [DatasetExportBridge] review approved rawSn=94 — triggering dataset export (force regenerate)` → `08:49:16.209 [batch-async-1] async approval export starting rawSn=94`. 코드: `DatasetExportBridge.java:36-43`(`@TransactionalEventListener(AFTER_COMMIT)` → `runner.runApprovalAsync`) |
| TC-EXPORT-002 | 승인 경로 멱등 skip 미적용 | PASS | [실동작]+[정적] | `DatasetExportService.java:152-156` 의 멱등 skip 은 `!forceRegenerate` 조건, 승인은 `force=true` 라 무조건 우회. 실측: rawSn=4 가 v1~v9, rawSn=26 이 v1~v10 으로 **매 트리거마다 새 버전 채번**(동일 내용 재승인/재수정 포함). 메트릭 `outcome=idempotent_skip` 0건. 테스트: `DatasetExportServiceTest#무수정_재승인도_승인경로는_새버전_생성한다 (force=true, R6)`, `DatasetExportE2EIT#무수정_재승인(forceTrue)은_동일해시여도_v2폴더와_JSON을_디스크에_새로생성한다` |
| TC-EXPORT-003 | 재동결 경로 멱등 skip | PASS | [정적] | `DatasetExportService.java:150-156`(`!forceRegenerate && prep.isUnchangedFromLastExport()` → `OUTCOME_IDEMPOTENT_SKIP`, 행 미INSERT). 멱등 baseline 에 PARTIAL 포함은 `DatasetExportTxService.java:150-153`(`STATUS_SUCCEEDED, STATUS_PARTIAL` IN 필터)로 확인. 테스트: `DatasetExportTxServiceTest#baseline_조회는_SUCCEEDED와_PARTIAL을_모두_포함한다`, `#직전_PARTIAL해시가_현재해시와_같으면_멱등skip된다`. ⚠ 이 경로의 유일한 트리거 `DatasetReExportEvent` 는 **발행처가 0건인 휴면 리스너**(`DatasetExportBridge.java:50-54` 자체 주석)라 운영에서 도달하지 않는다 → E-ISSUE-83(관측) |
| TC-EXPORT-004 | 정상 산출 SUCCEEDED + EXPORT_PATH_NM = 영상 루트 | PASS | [실동작] | rawSn=94 승인 → `frames written kind=ORIGINAL version=1 written=5 skipped=0` + `kind=DEIDENTIFIED version=1 written=5 skipped=0` → `export succeeded rawSn=94 version=1 written=10`. DB: `export_stts_cd=SUCCEEDED`, `export_path_nm=/app/storage/raw/autolabel-test/94`(= `dirname(RAW_FILE_PATH_NM)/{rawSn}` — **버전 루트 아님**), `frame_cnt=10`. 디스크: `94/{deid,v1,v2}` 가 한 부모 아래 공존 |
| TC-EXPORT-005 | 일부 프레임 부재 PARTIAL | PASS | [실동작] | 유령 프레임(frm_no=99, 존재하지 않는 이미지 경로) 삽입 후 재export → `[FrameSource] frame path unresolved skipped rawSn=94 frameNo=99` ×2벌 → `partial export rawSn=94 version=2 written=10 skipped=2`, DB `export_stts_cd=PARTIAL`. 메트릭 `dataset.export.skipped_frames` COUNT **정확히 2** / `result{outcome=partial}` 1. ⚠ 단 PARTIAL 은 데이터마트 뷰에서 배제되고 회수기 대상도 아니다 → **E-ISSUE-81(HIGH)** |
| TC-EXPORT-006 | 산출 0건 FAILED | PASS | [정적] | `DatasetExportService.java:205-210`(`totalWritten==0` → `txService.markFailed` + WARN, 승인 롤백 없음 — `@Async` 분리). 메트릭 `result{outcome=failed}`=11 로 실경로 다수 관측. D 클러스터 part4 가 rawSn=72 로 실측 재현(`nothing produced — marked FAILED`). 테스트: `DatasetExportServiceTest#아무것도_산출못하면_FAILED로_전이한다` |
| TC-EXPORT-007 | 파생영상 = ORIGINAL 벌 미생성(PARTIAL 아님) | PASS | [실동작] | 증강 파생 rawSn=18(`ORGNL_RAW_SN=4`)의 export: DB `export_sn=11 v1 SUCCEEDED frame_cnt=30`, 디스크 `…/augment/4/18/18/v1/` 아래에 **`deid` 만 존재(`orgnl` 없음)**. `hasNoOriginalFrames`(`:330-336`)가 전 프레임 `SRC_FILE_PATH_NM` blank 를 데이터 사실로 판정해 `:190-193` 에서 ORIGINAL 벌을 건너뛰고 skip 집계에도 넣지 않음 → PARTIAL 강등 없음 |
| TC-EXPORT-008 | 버전 채번 UK 충돌 재시도 | PASS | [정적] | `DatasetExportService.java:367-377` `insertWithRetry` 가 `MAX_VERSION_RETRY=3`(`:54`) 회 `DataIntegrityViolationException` 을 잡아 재채번. `insertNextVersion` 은 REQUIRES_NEW + `saveAndFlush`(`DatasetExportTxService.java:173-183`)라 PG 의 tx abort 가 승인/다른 시도로 번지지 않음. 테스트: `#동시_승인_UK위반시_재시도로_다음버전_채번된다` |
| TC-EXPORT-009 | 버전 채번 재시도 소진 | PASS | [정적] | `:174-178` `inserted == null` → ERROR 로그 + `OUTCOME_VERSION_EXHAUSTED` + return(파일쓰기 미진입). 테스트: `#UK위반이_재시도_상한_초과하면_산출을_중단한다`, `#재채번_소진시_result_version_exhausted_1회` |
| TC-EXPORT-010 | 파일쓰기 실패 시 승인 불변 | PASS | [정적] | `:234-241` `catch (RuntimeException e)` → `txService.markFailed(exportSn)` + WARN(예외 **클래스명만** `e.getClass().getSimpleName()`, 경로 원문·PII 미출력) + 예외 미전파. 테스트: `#파일산출_실패해도_승인은_롤백되지_않는다`, `DatasetExportE2EIT#산출_디렉터리_생성_실패시_승인은_롤백되지_않고_export만_FAILED` |
| TC-EXPORT-011 | 입력 부재 NO_INPUT | PASS | [실동작] | `:143-147` `loadPreparation` empty → `OUTCOME_NO_INPUT` + return(**행 미INSERT**). 사유 로깅은 `DatasetExportTxService.java:104,111`. 메트릭 `result{outcome=no_input}`=3 관측(대응 `LS_DATASET_EXPORT` 행 증가 없음) |
| TC-EXPORT-012 | 메트릭 outcome 정확히 1회 | PASS | [실동작] | `:107-109`(`startSample`, outcome fail-secure 기본 `failed`) + `:255-263` `finally` 단일 기록. 실측: `dataset.export.result` COUNT **26** == `dataset.export.duration` COUNT **26**(태그 no_input/completed/failed/partial). 조기 return(no_input 3건)·예외 이탈(deident_blocked 1건)도 포함해 1:1 유지 |
| TC-EXPORT-013 | 브릿지 조건부 비활성화 | PASS | [정적] | `DatasetExportBridge.java:29-30` `@ConditionalOnProperty(prefix="authoring.dataset-export", name="enabled", havingValue="true", matchIfMissing=true)`. 테스트: `DatasetExportBridgeBeanConditionTest` 3건(true 로드 / false 미로드 / 미설정 matchIfMissing 로드) |
| TC-EXPORT-014 | 폴더 구조 계약 (co-locate) | PASS | [실동작] | 디스크 실측 `/app/storage/raw/seed/4/{deid,v1..v9}`, `…/4/v8/{orgnl,deid}/NNNN.jpg + NNNN.json`. `DatasetExportPathResolver.resolve`(`:58-68`)가 `{영상루트}/v{n}/{orgnl|deid}` 를 조립하며 고정 `labeling_root` 는 롤백 플래그(`base-strategy=labeling-root`)로만 접근 |
| TC-EXPORT-015 | 산출 base 3중 가드 + fail-secure | PASS | [정적] | `DatasetExportService.java:162-169`(`resolveVideoRoot` RuntimeException → `markBaseRejected` → `OUTCOME_FAILED` + return, 기본 루트 폴백 없음) + `:345-360`(export 행 INSERT 후 `markFailed`, `EXPORT_PATH_NM=null`, ERROR 로그에 rawSn·ErrorCode 만). 3단 가드 실체는 `VideoArtifactRootResolver.verifiedBaseUnder`(`:387-414`) — ①blank/InvalidPath → `INVALID_INPUT` ②lexical `normalize()+startsWith(allowlist)` → `FORBIDDEN` ③`realOrNearest` 실경로 재검증(CWE-59) → `FORBIDDEN`. 추가로 `coLocateVideoRoot:253-255` 가 산출 루트==원본 파일 자체인 경우도 거부 |
| TC-EXPORT-016 | 비식별 신고 게이트 = 산출 자체 skip | PASS | [실동작] | 신고 접수(`DE_IDENT_YN='F'`) 후 승인 후 수정 트리거 → `08:52:19.031 WARN [DatasetExport] export blocked — deident report open rawSn=94` → `08:52:19.032 WARN async export failed rawSn=94 cause=CustomException`. 결과: `LS_DATASET_EXPORT` **행 미생성**(rawSn=94 는 v1/v2 + 수동삽입 v3 그대로), 메트릭 `result{outcome=deident_blocked}`=**1**, 그 뒤 `TASK_MODIFIED` 로그 **없음**(통지 동반 보류). 게이트 위치는 `:136-141` 단일 진입점 |
| TC-EXPORT-017 | 쓰기 중 신고 접수 = 마감 차단 + v{n} 폴더 삭제 | PASS | [정적] | `DatasetExportService.java:214/224` 가 마감을 `txService.finalizeUnlessUnderDeidentReport` 로만 수행하고, 그 안(`DatasetExportTxService.java:225-239`)에서 `isUnderDeidentReportLocked`(RAW 잠금) 재판정 → 차단 시 **export 행 `deleteById`** 후 false. false 면 `:247-254` 가 `purgeThisRunVersionDir`(`:285-304`, 3중 경로가드 `resolveVideoRoot`→`resolveUnder`→`verifyRealPathUnder` 통과 시에만 `v{n}` 재귀 삭제) 후 예외 이탈 → 통지 보류. 테스트: `DatasetExportDeidentReportGateIT#export_중_신고가_접수되면_산출물이_기록되지_않고_통지도_나가지_않는다`, `#export_중_신고시_이번_실행이_만든_버전폴더가_삭제되고_이전_버전은_보존된다`. (실동작 재현은 수 ms 내 쓰기 완료 + 잠금 경합이 필요해 로컬 단일 노드에서 유도 불가 — IT 로 커버) |
| TC-EXPORT-018 | export 성공 후에만 통지 | PARTIAL | [실동작] | **순서는 성립**: 08:49:16.215 `export succeeded rawSn=94 version=1` → 08:49:16.218 `TASK_COMPLETED sent rawSn=94`(동기 리스너, +3ms). 신고 차단 시 통지 미발송도 실측(위 016). **그러나 "실패면 보류" 는 예외 경로에서만 성립** — `AsyncDatasetExportRunner.doExport`(`:121-130`)가 예외 유무로만 판정하는데 `export()` 에는 무예외 실패 종결이 4종(`NO_INPUT :143-147` · base 거부 `:162-169` · `VERSION_EXHAUSTED :174-178` · `nothing produced :205-210`) → 이 경우 `true` 반환 → `DatasetExportCompletedEvent` 발행 → TASK_COMPLETED 발송. **D-ISSUE-61 재확인**(신규 등록 안 함) |
| TC-EXPORT-019 | 승인 후 수정 = 재export 후 통지 | PARTIAL | [실동작] | **순서·콜백 계약 성립**: 08:50:38.972 `partial export rawSn=94 version=2` → 08:50:38.977 `TASK_MODIFIED sent`; 08:48:28.875 `export succeeded rawSn=26 version=9` → .879 `TASK_MODIFIED sent`. 콜백은 `Runnable`(`AsyncDatasetExportRunner.java:91`)로 받아 통지 패키지 의존 없음 확인. **그러나 성공 판정이 018 과 동일한 `doExport` 를 공유**하므로 무예외 실패 4경로에서 `afterExport`(통지)가 그대로 실행됨 — **D-ISSUE-61 재확인** |
| TC-EXPORT-040 | 실패 export 회수 후 통지 재개 | PASS | [실동작] | §2-A 참조 — 신고 구간 제외(`skipped under deident report (retry budget preserved) count=1`, `RTY_NMTM` 0 유지) + 클레임 통과분 재산출·완료 이벤트 재발행 모두 실측 |
| TC-EXPORT-041 | 신고 해소 시 보류분 복구 | PASS | [실동작] | §2-B 참조 — `POST /v1/deident-reports/23/resolve` → `'F'→'Y'` → `DeidentReportResolvedEvent`(AFTER_COMMIT) → `runApprovalAsync` → **v4 전량 재생성** → 완료 이벤트 → 통지 재개(`completed conflicted -> resend as updated` 자기치유) |
| TC-EXPORT-042 | 재export 트리거는 control-notify 토글과 무관 | PASS | [정적] | 승인 후 수정의 재export 경로는 ①`TaskModifiedAccumulateListener`(`@Component`, **조건부 어노테이션 없음** — "항상 활성" 명시) → ②`ControlNotifyDebouncer`(`@Component`, 조건부 없음; `ControlNotifyService`/`ControlNotifyMetrics` 를 `@Nullable` 주입) → ③`send()`(`:293-307`)에서 `exportRunner.runReExportThenNotify(rawSn, true, notifyCallback)` 를 **토글과 무관하게 항상** 호출하고 `notifyCallback` 만 `notifyService==null` 이면 null. 통지 전용 `ControlNotifyEventListener` 만 `@ConditionalOnProperty(authoring.control-notify.enabled)`(`:27`). ⚠ 카탈로그 근거 `DatasetExportBridge.java:29-30` 은 **다른 토글**(`authoring.dataset-export.enabled`)이라 이 케이스의 근거가 아니다 → E-ISSUE-82(근거 드리프트) |
| TC-EXPORT-043 | retention 정리 로직 없음 | PASS | [실동작] | 디스크 실측: rawSn=4 에 `v1`(07-30 18:04) ~ `v9` 전부 존재, 최초 버전까지 삭제 없이 보존. 코드에 정리 잡 부재 + TODO 명시(`DatasetExportService.java:45-46`, `:171-172`). 저장소 전역 grep 으로 retention/purge 스케줄러 0건(`purgeThisRunVersionDir` 는 신고 차단 전용이라 전 버전 미영향) |


## 2. 회수기·복구 경로 실측 상세

### 2-A. TC-EXPORT-040 — 실패 export 회수 (`DatasetExportFailureRecoverer`)

설정 실효값: `retry-delay-minutes=10`, `max-attempts=3`, `batch-size=20`(`application.yml:232-236`). Quartz 트리거는 15분 주기(로그 `:12/:27/:42/:57`).

**(a) 신고 구간 제외 — 재시도 예산 보존 [실동작]**
- 준비: `LS_DATASET_EXPORT` 에 앵커 행 수동 INSERT(`export_sn=39`, rawSn=94, v3, `FAILED`, `reg_dt` 40분 전, `rty_nmtm=0`). 이 시점 rawSn=94 는 `DE_IDENT_YN='F'`(신고 OPEN).
- 08:57:19 tick 결과:
  ```
  [DatasetExportRecovery] skipped under deident report (retry budget preserved) count=1
  [DatasetExportRecovery] all candidates already claimed candidates=1
  ```
- DB 확인: `export_sn=39` 의 `rty_nmtm=0`, `rty_dt=null` — **클레임 자체를 안 해서 시도 예산이 소진되지 않음**. 코드: `DatasetExportFailureRecoverer.java:141-144`(클레임 **이전** `deidentReportGate.isUnderDeidentReport` 확인).

**(b) 클레임 → 재산출 → 완료 이벤트 재발행 [실동작]**
- 준비: 신고 해소 후 앵커 행 INSERT(`export_sn=42`, rawSn=94, v5, `FAILED`, `reg_dt` 60분 전).
- 09:12:19 tick 결과: §2-C 참조 — `retriggered failed exports count=1 rawSns=[94] maxAttempts=3`.
- 코드 근거: `DatasetExportFailureRecoverer.java:125`(`findRetryableFailedAnchors` — 영상별 **최신 버전이 FAILED** + `COALESCE(RTY_DT,REG_DT) < cutoff` + 마지막 성공 이후 `SUM(RTY_NMTM) < maxAttempts`) → `:147 txService.claimForRetry`(조건부 UPDATE `RTY_NMTM+1, RTY_DT=now WHERE STTS='FAILED' AND RTY_NMTM<max AND (RTY_DT IS NULL OR RTY_DT<cutoff)` — PG 가 UPDATE 시 WHERE 를 재평가해 2노드 중복 클레임 차단) → `:157 runner.runApprovalAsync(rawSn)`(성공 시 `DatasetExportCompletedEvent` 재발행 = 통지 재개).
- ⚠ 회수는 `runApprovalAsync` 로만 하므로 **보류됐던 통지가 TASK_MODIFIED 였어도 TASK_COMPLETED 로 재발행**된다. 이는 `ControlNotifyService` 의 409 자기치유(`:206-232`, `completed conflicted -> resend as updated`)가 흡수하며, §2-B 에서 그 자기치유가 실제로 도는 것을 관측했다 → 결함 아님.

### 2-B. TC-EXPORT-041 — 신고 해소 시 보류분 복구 [실동작]

```
08:57:44  POST /api/v1/deident-reports/23/resolve  → 200
08:57:44.513 [batch-async-1] AsyncDatasetExportRunner - async approval export starting rawSn=94
08:57:44.517 DatasetExportWriter - frames written rawSn=94 kind=ORIGINAL      version=4 written=5 skipped=1
08:57:44.517 DatasetExportWriter - frames written rawSn=94 kind=DEIDENTIFIED version=4 written=5 skipped=1
08:57:44.518 DatasetExportService - partial export rawSn=94 version=4 written=10 skipped=2
08:57:44.522 ControlNotifyService - completed conflicted -> resend as updated rawSn=94
08:57:44.525 ControlNotifyService - TASK_COMPLETED sent rawSn=94 actual=TASK_MODIFIED
```
- `LS_DATA_RAW.DE_IDENT_YN` 이 `F→Y` 로 복원되고, `DeidentReportService.publishResolvedForExportRecovery`(`:487-495`)가 `DeidentGateReopenedEvent`(항상) + `DeidentReportResolvedEvent`(APPROVED 일 때만)를 발행 → `DatasetExportBridge.onDeidentReportResolved`(`:81-87`, AFTER_COMMIT)가 `runApprovalAsync` 로 위임.
- **force=true 전량 재생성** 확인(v3 이 FAILED 였는데도 멱등 skip 없이 v4 산출) + **통지 재개** 확인.
- 범위는 그 영상 하나 — 파생영상 팬아웃 로그·export 없음(★1 확정 정책 준수).
- ⚠ 재현 시 주의: `resolve` 는 **비식별 산출물 재검증 게이트**를 먼저 통과해야 한다(`DeidentReportService.java:557-580`). 최초 시도는 `409 CONFLICT "비식별 산출물이 확인되지 않습니다"` 로 거부됐고, 외부 솔루션의 제자리 교체를 모사해 비식별 mp4 의 mtime 을 갱신(`touch`)한 뒤에야 200 이 됐다. (이 게이트 자체는 B 클러스터 소관)

### 2-C. 09:12:19 tick 실측 — 클레임 → 재산출 → 통지 재개 [실동작]

```
09:12:19.603 WARN  DatasetExportFailureRecoverer - [DatasetExportRecovery] retriggered failed exports count=1 rawSns=[94] maxAttempts=3
09:12:19.603 INFO  AsyncDatasetExportRunner      - [DatasetExport] async approval export starting rawSn=94
09:12:19.610 INFO  DatasetExportWriter           - frames written rawSn=94 kind=ORIGINAL      version=6 written=5 skipped=1
09:12:19.611 INFO  DatasetExportWriter           - frames written rawSn=94 kind=DEIDENTIFIED version=6 written=5 skipped=1
09:12:19.612 WARN  DatasetExportService          - partial export rawSn=94 version=6 written=10 skipped=2
09:12:19.617 INFO  ControlNotifyService          - completed conflicted -> resend as updated rawSn=94
09:12:19.619 INFO  ControlNotifyService          - TASK_COMPLETED sent rawSn=94 actual=TASK_MODIFIED
```
DB(클레임 흔적):
```
 export_sn | ver | stts      | frame_cnt | rty_nmtm | rty_dt
        39 |   3 | FAILED    |           |        0 |                            ← 최신 버전이 아니라 앵커 아님(미클레임)
        42 |   5 | FAILED    |           |        1 | 2026-08-02 09:12:19.601536 ← 클레임됨(RTY_NMTM 0→1, RTY_DT 기록)
        43 |   6 | PARTIAL   |        10 |        0 |                            ← 회수 재산출 결과
```
확인된 단언 4가지:
1. **앵커는 "영상별 최신 버전이 FAILED" 인 행 하나** — 같은 영상의 더 오래된 FAILED(v3)는 집히지 않았다.
2. **`claimForRetry` 조건부 UPDATE 가 시도 이력을 남긴다** — `RTY_NMTM 0→1` + `RTY_DT` 기록. 산출 결과와 무관하게 기록되므로 `max-attempts=3` 상한이 실제로 걸린다(2노드 중복 클레임 차단의 DB 레벨 보장).
3. **재산출은 `runApprovalAsync`(force=true)** — v6 를 새로 채번해 전량 재생성.
4. **통지 재개** — 완료 이벤트 재발행 → `sendCompleted` → 관제 409 → `resend as updated` 자기치유로 TASK_MODIFIED 발송(`ControlNotifyFallbackService send success recorded`). "통지 유실이 아니라 성공 시점으로 지연" 계약 성립.

> ⚠ 이번 재산출도 v6 **PARTIAL** 로 끝났다(유령 프레임 때문). 즉 통지는 나갔는데 `V_COMPLETED_VIDEO` 는 여전히 v1(SUCCEEDED)만 본다 — E-ISSUE-81 이 회수 경로에서도 그대로 재현된다.

## 3. 관측 사항 (결함 아님 — 기록용)

1. **재동결 멱등 skip 경로(TC-EXPORT-003)는 운영에서 도달 불가.** 유일한 트리거 `DatasetReExportEvent` 의 발행처가 0건이고(`DatasetExportBridge.java:50-54` 자체 주석 — "휴면 리스너, event_annotation 지연 승인 경로가 `TaskModifiedEvent(regen=true)` 단일 축으로 통일됨"), `runAsync(rawSn,false)` 호출부도 그 리스너뿐이다. 실측 메트릭 `dataset.export.result{outcome=idempotent_skip}` = **0**. 의도된 확장점 존치이므로 결함으로 등록하지 않되, "멱등 skip 이 살아 있다"는 전제로 후속 설계를 하면 안 된다.
2. **승인 후 수정 7경로는 모두 `TaskModifiedEvent(..., exportRegenerated=true)` 로 발행된다** — 정적 전수 확인 + 4경로 실동작 확인:

   | 경로 | 코드 | 실동작 확인 |
   |------|------|------|
   | 라벨 수정 `LabelService` | `:421-424` (`toChangeTypes` 루프, 5-arg `true`) | ✅ 08-01 23:19:00 flush `regen=true frames=296=[LABEL_ADDED, LABEL_DELETED]` → rawSn=26 **v3** |
   | 트랙 편집 `TrackEditService` | `:326` 5-arg `true` | (정적) |
   | 트랙 병합 `TrackMergeService` | `:200-201` 5-arg `true` | (정적) |
   | 버전 롤백 `VersionService` | `:517-519` 5-arg `true`(APPROVED 일 때만) | (정적) |
   | 촬영환경 `EnvironmentMetaService` | `:125-126` 5-arg `true` + 선행 `reFreezeApprovedSnapshot` | ✅ 08:47:26 수정 → 08:48:28 flush `regen=true` → rawSn=26 **v9 SUCCEEDED** → TASK_MODIFIED |
   | 프레임 설명 `FrameDescriptionService` | `:60-62` 5-arg `true` | ✅ 08:45:38/08:49:37 수정 → rawSn=26 **v7**, rawSn=94 **v2** |
   | 프레임 개인정보 메타 `FramePrivacyMetaService` | `:131-132`(bulk) · `:154-155`(single) 5-arg `true` | ✅ 08:52:58 수정 → 08:53:59 flush `regen=true frames=297=[META_UPDATED]` → rawSn=26 **v10 SUCCEEDED** |

   반면 `EvntAnnoService:128-129` · `MetaService:82-84` 는 **4-arg(=regen false)** 로 발행해 재export 를 트리거하지 않는다 — `EvntAnnoService` 는 `CLAUDE.md` 가 명시한 예외(재동결 미배선)와 일치하고, 시계열 메타(`MetaService`)는 7경로 목록에 없다. 정합.
3. **전 버전 보존 실증**: rawSn=4 → `v1`(07-30 18:04)~`v9`, rawSn=26 → `v1`~`v10`(수동 삽입한 v8 은 물리 폴더 없음), rawSn=94 → `v1`,`v2`,`v4`,`v6`. 삭제 잡 없음. `purgeThisRunVersionDir` 는 "이번 실행이 만든 `v{n}`" 만 대상이라 전 버전 보존과 충돌하지 않는다.
4. **`EXPORT_PATH_NM` 은 버전을 가리키지 않는다**(영상 루트 고정). 따라서 관제는 뷰만으로 "몇 번 버전을 픽업할지" 알 수 없고 `FRAME_CNT` 만이 간접 단서다. 이는 확정 정책(비교·복구를 위해 한 경로 아래 전 버전 노출)이지만, 아래 E-ISSUE-81 과 결합하면 관제가 최신 산출을 식별할 방법이 사실상 없다.

## 4. 이슈

### [E-ISSUE-81] TC-EXPORT-005 / TC-EXPORT-019 — PARTIAL export 는 통지되지만 데이터마트 뷰에서 배제되고 회수기 대상도 아니다 (관제 동기화 사각지대)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `CLAUDE.md` — *"통지는 export 성공 후 발송한다"* + *"기 구축된 데이터 마트 연계 관리기능 … 데이터마트 학습데이터셋의 라벨링 정보 동기화"*. 관제는 통지를 받으면 `V_COMPLETED_VIDEO.EXPORT_PATH_NM`/`FRAME_CNT` 로 산출물을 픽업한다. 따라서 **통지가 나간 산출은 반드시 뷰에서 보여야** 하고, 보이지 않는 산출은 재산출 회수 대상이 되어야 한다.
- **현재 동작(이슈 내용)**: `PARTIAL` 종결은 ①통지는 정상 발송되고 ②뷰에서는 **배제**되며 ③회수기 대상도 **아니다** — 세 판정 기준이 서로 어긋난다.
  - 뷰(실측 `pg_get_viewdef('v_completed_video')`):
    ```sql
    LEFT JOIN LATERAL (SELECT ex.export_path_nm, ex.frame_cnt
                         FROM ls_dataset_export ex
                        WHERE ex.data_raw_sn = m.raw_sn
                          AND ex.export_stts_cd::text = 'SUCCEEDED'::text   -- ← PARTIAL 제외
                        ORDER BY ex.export_ver_no DESC LIMIT 1) e ON true
    ```
  - 통지: `DatasetExportService.java:211-222` 가 PARTIAL 을 **정상 마감**(`finalizeUnlessUnderDeidentReport(..., partial=true)`)하고 예외 없이 반환 → `AsyncDatasetExportRunner.doExport:121-130` 이 `true` → 완료 이벤트/`afterExport` 통지 실행.
  - 회수기: `LsDatasetExportRepository.findRetryableFailedAnchors` 는 `e.EXPORT_STTS_CD = 'FAILED'` 만 앵커로 삼는다 → 최신이 PARTIAL 이면 **영원히 재시도되지 않는다**.
  - 멱등 baseline 은 PARTIAL 을 **포함**(`DatasetExportTxService.java:150-153`)하므로 재동결 경로에서는 "이미 산출됨"으로 skip 된다.
  - **실측(2026-08-02 08:50, rawSn=94)**:
    ```
    08:50:38.972 WARN  DatasetExportService - partial export rawSn=94 version=2 written=10 skipped=2
    08:50:38.977 INFO  ControlNotifyService - TASK_MODIFIED sent rawSn=94 frames=1 videoLevel=0 reExport=true
    ```
    디스크에는 `…/94/v2/{orgnl,deid}/0000.jpg…` 가 실재하는데,
    ```
    klid_system=# select raw_sn, export_path_nm, frame_cnt from v_completed_video where raw_sn=94;
     94 | /app/storage/raw/autolabel-test/94 | 10     ← v1(SUCCEEDED)의 값. v2 는 뷰에 전혀 반영 안 됨
    ```
    `EXPORT_PATH_NM` 이 영상 루트 고정이라 경로는 우연히 같지만, **`FRAME_CNT` 는 구 버전 값**이고 최신 산출의 존재/버전은 관제가 알 방법이 없다.
  - **더 심각한 경계**: 영상의 **최초 export 가 PARTIAL** 이면 SUCCEEDED 행이 하나도 없어 `EXPORT_PATH_NM`·`FRAME_CNT` 가 **NULL** 이다. 관제는 TASK_COMPLETED 를 받고 뷰를 조회했는데 산출물 경로를 못 찾고, 회수기도 집지 않아 **영구 미동기화**가 된다. 이는 `DatasetExportFailureRecoverer` javadoc 이 만들어진 계기(*"EXPORT_PATH_NM 이 NULL 인 행이 영구히 노출"*)와 정확히 같은 증상인데 FAILED 축만 막혀 있고 PARTIAL 축은 열려 있다.
- **재현/확인 경로**:
  ```sql
  -- 1) APPROVED 영상 한 건에 원천 이미지가 없는 프레임을 1건 추가(부분 산출 유도)
  INSERT INTO ls_data_src (raw_sn, frm_no, src_file_path_nm, de_idntf_src_file_path_nm, vdo_frm_no)
  VALUES (:rawSn, 99, '/app/storage/raw/.../missing.jpg', '/app/storage/deidentified/.../missing.jpg', 990);
  ```
  ```bash
  # 2) 승인 후 수정 트리거(재export) — 60초 디바운스 후 flush
  curl -X PUT localhost:18081/api/v1/frames/{srcSn}/description -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"srcSn":{srcSn},"description":"partial probe"}'
  ```
  ```sql
  -- 3) PARTIAL 로 마감됐는데 통지는 나갔고, 뷰에는 반영되지 않음
  SELECT export_ver_no, export_stts_cd, frame_cnt FROM ls_dataset_export WHERE data_raw_sn = :rawSn ORDER BY 1;
  SELECT raw_sn, export_path_nm, frame_cnt FROM v_completed_video WHERE raw_sn = :rawSn;
  ```
- **영향**: 데이터정합 — 관제/데이터마트가 **최신 학습데이터 버전을 픽업하지 못한다**(사업 요구 "라벨링 정보 동기화" 미충족). 최초 export 가 PARTIAL 인 영상은 산출물이 디스크에 있는데도 데이터마트에서 **미산출로 보인다**. 자동 회수 경로가 없어 운영자가 수동 개입해야 하며, 관측 지표(`result{outcome=partial}`)를 보지 않으면 무증상으로 누적된다.
- **수정 방향(제안)**: 셋 중 하나로 **판정 기준 3곳을 일치**시킬 것 — ⓐ 뷰(`V_COMPLETED_VIDEO` LATERAL 조인)의 상태 필터를 `IN ('SUCCEEDED','PARTIAL')` 로 넓히고 부분 산출임을 알리는 컬럼(예: `EXPORT_STTS_CD`)을 함께 노출, 또는 ⓑ `findRetryableFailedAnchors` 의 앵커 조건에 `PARTIAL` 을 포함해 회수 대상으로 삼되 `max-attempts` 로 상한(원천 이미지가 영구 부재면 무한 재시도가 되므로 필수), 또는 ⓒ PARTIAL 을 "통지하지 않는 종결"로 재정의(통지 보류 + 회수 대상화). ⓐ+ⓑ 조합이 요구(비교·복구)와 가장 정합적이며, 어느 쪽이든 `DatasetExportTxService` 의 멱등 baseline(PARTIAL 포함)과 함께 일관되게 조정해야 한다. **구현은 하지 않는다.**

### [E-ISSUE-82] TC-EXPORT-042 / TC-EXPORT-040 — 카탈로그 근거(file:line) 드리프트
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스의 `근거` 컬럼은 그 단언을 실제로 보장하는 코드 위치를 가리켜야 한다(다음 회차 재검증·수정 작업의 진입점).
- **현재 동작(이슈 내용)**:
  1. **TC-EXPORT-042** 근거가 `DatasetExportBridge.java:29-30 (자체 토글만 참조)` 인데, 이 라인은 `authoring.dataset-export.enabled` 토글(= TC-EXPORT-013 의 근거)이며 **`control-notify` 토글과의 무관성을 보장하는 코드가 아니다**. 실제 보장 지점은 ①`controlnotify/listener/TaskModifiedAccumulateListener.java`(조건부 어노테이션 없음 — "항상 활성") ②`ControlNotifyDebouncer.java:71-79`(클래스 javadoc "HIGH-E — export 재생성 트리거는 통지 토글과 분리한다") + `:293-307`(`send()` 가 `notifyService==null` 여부와 무관하게 `runReExportThenNotify` 호출) ③반대로 `ControlNotifyEventListener.java:27` 만 `@ConditionalOnProperty(authoring.control-notify.enabled)`.
  2. **TC-EXPORT-040** 근거 `DatasetExportTxService.java:257-278` 중 `claimForRetry` 는 실제 `:257-261` 이고 `:263-291` 은 무관한 `sweepStalePending` 이다.
- **재현/확인 경로**: `docs/test-cases/E-augment-resolution-export-meta.md` 의 해당 행과 위 파일들을 대조.
- **영향**: 기능 영향 없음. 카탈로그 자체의 정합성 결함 — 근거를 따라가면 다른 토글을 검사하게 되어 **잘못된 PASS/FAIL 판정**을 유발할 수 있다.
- **수정 방향(제안)**: 카탈로그 근거를 위 실제 위치로 교체. **구현은 하지 않는다.**

### D-ISSUE-61 재확인 (신규 등록 없음) — TC-EXPORT-018 / TC-EXPORT-019
- 본 파트가 담당하는 `DatasetExportService`/`AsyncDatasetExportRunner` 에서 **동일 결함을 정적으로 재확인**했다. `AsyncDatasetExportRunner.doExport`(`:121-130`)가 `try { export(...); return true; } catch { return false; }` 로 **예외 유무로만** 성공을 판정하는데, `DatasetExportService.export` 에는 예외 없이 실패로 종결하는 경로가 4종이다:
  | 경로 | 라인 | 종결 |
  |------|------|------|
  | 입력 부재 | `:143-147` | `OUTCOME_NO_INPUT` + `return` |
  | 산출 base 거부 | `:162-169` | `markBaseRejected`(FAILED 행) + `OUTCOME_FAILED` + `return` |
  | 버전 채번 소진 | `:174-178` | `OUTCOME_VERSION_EXHAUSTED` + `return` |
  | 산출 0건 | `:205-210` | `markFailed` + `OUTCOME_FAILED`(throw 없음) |
  | (추가) 쓰기 중 예외 | `:234-241` | `markFailed` 후 **정상 반환** |
- 결과: `runApprovalAsync:73-75` 가 `DatasetExportCompletedEvent` 를 발행하고 `runReExportThenNotify:97-105` 가 `afterExport`(TASK_MODIFIED) 를 실행한다 → *"export 가 실패하면 통지를 보류"* 구속 정책 위반.
- 반면 **신고 게이트 차단(`:136-141`)과 쓰기 중 신고(`:247-254`)는 `CustomException` 을 던지므로 통지가 실제로 보류**된다(08:52:19 실측). 즉 규약은 **예외 경로에서만** 성립한다.
- 수정 방향은 D-ISSUE-61 에 기록된 것과 동일(권장: `export()` 가 종결 outcome 을 반환하고 러너가 `COMPLETED`/`PARTIAL` 등 성공 집합으로만 통지). 본 파트에서는 **중복 이슈를 등록하지 않는다.**

### [E-ISSUE-83] TC-EXPORT-016 / TC-EXPORT-019 — 신고 게이트로 export 가 보류되면 디바운스 윈도우가 이미 `complete` 처리돼 그 수정 통지(변경 프레임 목록)가 영구 유실된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `ControlNotifyDebouncer.claimAndSendIsolated` javadoc 의 자체 계약 — *"실패한 윈도우는 `complete` 를 호출하지 않아 저장소에 FLUSHING 으로 남는다 … 통지가 **소실되지 않고 지연**된다"*. 또 `CLAUDE.md` 는 TASK_MODIFIED 페이로드에 **변경 프레임 목록**(`SRC_SN` + 변경 종류)을 담도록 규정한다.
- **현재 동작(이슈 내용)**: 재생성 동반 윈도우(`exportRegenerated=true`)의 실제 전송은 `@Async` 러너에 위임되고 **즉시 반환**하므로, 그 뒤의 export 차단/실패가 `send()` 의 예외로 관측되지 않는다. 그 결과 윈도우가 `complete` 로 마감돼 축적분이 사라진다.
  ```java
  // ControlNotifyDebouncer.java:293-303  (send)
  if (window.exportRegenerated()) {
      Runnable notifyCallback = notifyService == null ? null
              : () -> notifyService.sendModified(rawSn, frameChanges, videoLevelChangeTypes, true);
      exportRunner.runReExportThenNotify(rawSn, true, notifyCallback);   // @Async — 즉시 반환
      return;
  }
  // ControlNotifyDebouncer.java:254-256  (claimAndSendIsolated)
  send(window);
  store.complete(window.acmlSn());   // ← export/통지 결과와 무관하게 마감
  ```
  **실측(2026-08-02, rawSn=94)**: `08:52:19.030 flush rawSn=94 regen=true frames=448=[META_UPDATED],449=[META_UPDATED]` → `08:52:19.031 export blocked — deident report open` → `08:52:19.032 async export failed cause=CustomException`. 이후 어떤 tick 에서도 이 윈도우가 재클레임되지 않았고(재flush 로그 0건), 신고 해소(08:57:44) 시 나간 통지는 M1 경로의 **영상 단위 `sendCompleted`**(409 자기치유로 updated 전환)라 **`frames=448,449` 변경 목록을 담지 않는다**.
- **재현/확인 경로**:
  ```bash
  # APPROVED 영상에 신고 접수 → 신고 구간에 승인 후 수정 → 60초 뒤 flush
  curl -X POST localhost:18081/api/v1/labels/{srcSn}/deident-report -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"reason":"probe"}'
  curl -X PUT  localhost:18081/api/v1/frames/{srcSn}/description   -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"srcSn":{srcSn},"description":"x"}'
  # 로그: flush(regen=true, frames=...) → export blocked → async export failed
  #      이후 같은 rawSn 의 재flush 없음 = 축적분 소실
  ```
- **영향**: 기능/데이터정합 — 관제가 받는 수정 통지에서 **어느 프레임이 바뀌었는지**가 빠진다(전량 재조회로만 복구 가능). 정합 자체는 M1 재통지로 회복되므로 CRITICAL 은 아니나, 디바운서가 문서화한 "지연될 뿐 소실 없음" 보증이 재생성 경로에서만 깨져 **동작이 문서와 다르다**. 통지 토글이 꺼진 형상에서는 `notifyCallback` 이 애초에 null 이라 영향 없음.
- **수정 방향(제안)**: ⓐ `runReExportThenNotify` 가 실패했을 때 윈도우를 되돌리도록, `complete` 를 **콜백 이후**(러너 스레드)로 옮기거나 실패 시 `store` 를 `FLUSHING` 으로 재개방하는 배선 추가, 또는 ⓑ 재생성 윈도우는 러너가 성공했을 때만 `complete` 하도록 `AsyncDatasetExportRunner` 가 결과를 콜백(`onFailure`)으로 돌려주기, 또는 ⓒ 최소 조치로 **차단 시 WARN 에 유실된 `frameChanges` 요약을 남겨 감사 가능하게** 하기. E-ISSUE-81 / D-ISSUE-61 과 함께 "러너의 성공/실패를 상위가 알 수 있게 한다"는 같은 축의 수정이다. **구현은 하지 않는다.**

## 6. 집계

| 판정 | 건수 | 케이스 |
|------|:--:|------|
| PASS | 21 | 001~017, 040~043 |
| PARTIAL | 2 | **018**, **019** (둘 다 D-ISSUE-61 재확인 — 무예외 실패 4경로에서 통지 보류 미작동) |
| FAIL / BLOCKED / N/A / 확인필요 | 0 | — |
| **합계** | **23** | (카탈로그 실측 행 수 `grep -cE '^\| *~*TC-'` = 23, 폐기 케이스 0건) |

### 신규 등록 이슈
| ID | 심각도 | 요지 |
|----|:--:|------|
| E-ISSUE-81 | HIGH | PARTIAL export 는 통지되지만 `V_COMPLETED_VIDEO` 에서 배제 + 회수기 앵커도 아님 → 관제 동기화 사각지대 |
| E-ISSUE-82 | LOW | TC-EXPORT-042 / TC-EXPORT-040 근거 file:line 드리프트(카탈로그 정합) |
| E-ISSUE-83 | MEDIUM | 신고 게이트로 export 보류 시 디바운스 윈도우가 `complete` 돼 변경 프레임 목록 유실(디바운서 자체 "소실 없음" 계약 위반) |

### 기존 이슈 재확인 (신규 등록 없음)
- **D-ISSUE-61 (CRITICAL)** — TC-EXPORT-018 / TC-EXPORT-019. `AsyncDatasetExportRunner.doExport` 가 예외 유무로만 성공을 판정 → 무예외 실패 4경로(NO_INPUT · base 거부 · 버전 소진 · 산출 0건)에서 TASK_COMPLETED/TASK_MODIFIED 가 그대로 발송된다. 상세는 §4 말미.

### self-fill 관점
본 파트 범위(Export)에는 외부 연동 응답으로 채워야 할 값이 없다 — 산출 JSON 의 값은 전부 DB(라벨·프레임·동결 메타)에서 오고, 유일한 외부 의존은 비식별 영상 경로(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`, mock KPST 응답에서 적재된 실측값 `…/94/deid/62e1aea1-…-mask.mp4` = `{stem}-mask{ext}` 규약)뿐이며 문자열 조합·추측이 아님을 확인했다. **self-fill 결함 0건.**

### 검증 중 생성한 데이터 (이후 회차 참고 — 정리하지 않음)
| 대상 | 값 | 성격 |
|------|----|------|
| `ls_data_raw` | rawSn=**94**(vmsClipId `QA0802-E5-EXPORT`) | 이번 검증 전용 신규 영상(파이프라인 정상 완주 → APPROVED) |
| `ls_data_src` | src_sn=**453**(rawSn=94, frm_no=99, 존재하지 않는 이미지 경로) | PARTIAL 유도용 유령 프레임 — 남겨두면 rawSn=94 의 향후 export 는 계속 PARTIAL 이 된다 |
| `ls_dataset_export` | export_sn=**35**(rawSn=26 v8), **39**(rawSn=94 v3), **42**(rawSn=94 v5) | 회수기 앵커용 수동 FAILED 행(물리 폴더 없음) |
| `ls_deident_report` | rprtSn=**23**(rawSn=94) | 신고→해소까지 완결(RESOLVED, `DE_IDENT_YN='Y'` 복원) |
| 파일 | `/app/storage/raw/autolabel-test/94/deid/…-mask.mp4` mtime 갱신(`touch`) | 신고 해소 게이트(외부 솔루션 제자리 교체) 모사 |
# E클러스터 — Part6 (E-7 Export JSON 포맷, E-8 촬영환경 메타, E-9 프레임 개인정보 메타)

검증자: 담당 에이전트(E-part6) · 대상: `docs/test-cases/E-augment-resolution-export-meta.md` §E-7(18건) + §E-8(20건, TC-META-009 폐기 제외 실질 19건) + §E-9(15건) = 53건(폐기 제외 실질 52건)
방법: 실동작 최우선(HTTP 요청 + 컨테이너 내부 export JSON 파일 직접 열람 + DB 직접 SELECT + backend 로그 tail), 근거 file:line 정적 대조 병행. 스택은 기존 기동 상태(backend :18081, `local` 프로파일, V158) 그대로 사용. `/v1/dev/tokens`로 REVIEWER(sub=1001)/WORKER(sub=2001) 토큰 발급. 코드/설정/테스트 파일은 수정하지 않았다.

## 사용한 실증 데이터
- **rawSn=26**(base video, APPROVED, WORKER 2001/REVIEWER 1001 배정) — 기존 완주 파이프라인(pipeline-drive.md) 산출물. export v1~v9 누적 관찰(본 검증 중 env-meta/privacy-meta PUT으로 v6까지 직접 트리거 후 다른 병행 클러스터 활동으로 v9까지 추가 관찰됨 — 디바운스 재export 자체가 반복 가능함을 방증).
- **rawSn=18**(증강 파생, `ORGNL_RAW_SN=4`, AUG_TYPE_CD=WINTER, APPROVED, export v1 SUCCEEDED) — 파생영상 export 실증에 사용(생성은 본 검증 이전 다른 세션 산출물, 코드 수정 없이 기존 파일만 열람).
- DB 변경: `ls_data_raw.wthr_nm/day_ngt_cd/sesn_cd`(rawSn=26,7), `ls_data_src.anony/psdo/prvc_incl_yn`(srcSn=296,297) — 모두 정상 API 경로(PUT)를 통한 실 데이터이며 원복하지 않음(검증 자체가 실사용 시나리오와 동일한 저장 동작이라 되돌릴 필요가 없다고 판단, 다른 클러스터의 export 버전 누적에 영향 없음 — export/재export는 멱등 append 방식이라 무해).

## E-7. Export JSON 포맷 — 18건

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-EXPORT-020 | PASS | [실동작]+[정적] | rawSn=26 export v1/v6 JSON 실측: 최상위 키 순서 `info,dataset,licences,video,event,image,annotations,categories,type` — `event`가 `video` 다음. 값 없으면 `"event":null`. `NiaAnnotationDoc.java:24-37` `@JsonInclude(ALWAYS)` + `@JsonPropertyOrder` 코드와 정확히 일치 |
| TC-EXPORT-021 | PASS | [실동작] | 동결 event 없는 rawSn=26에서 전 프레임 JSON `"event" : null` 확인(키 always present) |
| TC-EXPORT-022 | PASS | [실동작] | srcSn=296에 수동 `ANONY_INCL_YN='Y'` 저장된 상태에서 orgnl(v6) `image.anonymity="N"`, deid(v6) `="Y"` — 수동값이 덮지 않음(CWE-359 방어 실증). `video.anonymity`도 동일 패턴(orgnl N / deid Y) |
| TC-EXPORT-023 | PASS | [실동작] | srcSn=297에 `PSDO_INCL_YN='Y'` 저장 → orgnl/deid 양쪽 JSON `image.pseudonymity="Y"` 반영(수동 우선 확인). `NiaJsonBuilder.java:156-159` `firstNonBlank(src값, 파생폴백)` 일치 |
| TC-EXPORT-024 | PASS | [정적] | `NiaJsonBuilder.buildDataset`(132-137)이 `kind==DEIDENTIFIED`일 때 `ctx.deidVideoPath()`가 null이면 `path=null`→`baseNameNoExt(null)=null`이 그대로 `NiaDataset(id, null, null, null)`로 흘러 dataset.src_path/name 모두 null. `VideoMetaMapper.toVideo`(61-65 실측 라인 63-65)도 `kindVideoPath=deidVideoPath(null)`→`basename(null)=null`→filename null. 본 환경엔 deid경로 결측 상태로 export된 실사례가 없어 라이브 재현은 못했으나 로직은 결정론적 null 전파라 코드 근거로 충분 |
| TC-EXPORT-025 | PASS | [정적] | `buildAnnotations`(178-201) `try{labelMapper.toAnnotation}catch(CustomException){skipped++; log.warn(lblSn만)}` — 1건 skip해도 문서 전체 정상 생성. 라이브 환경엔 malformed 라벨 유발 데이터가 없어 실행은 미관찰, catch 로직 자체는 명확 |
| TC-EXPORT-026 | PASS | [실동작]+[정적] | rawSn=26 JSON 실측: `type/format/filesize/location/pixel/cctv_height` 등 미보유 필드가 `null` 값으로 키 유지(생략 안 됨). `NiaVideo.java:12`/`NiaImage.java:9` `@JsonInclude(ALWAYS)` 일치 |
| TC-EXPORT-027 | PASS | [실동작]+[정적] | JSON `"vd_description":null` 키 존재 확인. `NiaVideo.java:46` 필드 선언, `VideoMetaMapper.java:101` `null // vd_description(미보유 — 데이터 출처 없음)` 정확히 일치 |
| TC-EXPORT-028 | PASS | [실동작]+[정적] | rawSn=26 초기(수동 미입력) export에서 `weather/time_of_day/season` 전부 `null`(SHT_DT 기반 자동파생 없음, self-fill 폐기 실증). PUT으로 수동값 저장 후 재export(v6)에서 `weather:"비", time_of_day:"NGT", season:"WINTER"` 정확 반영. `VideoMetaMapper.java:57` `firstNonBlank(raw, meta)` — raw(최신 수동값) 우선 코드 일치 |
| TC-EXPORT-029 | PASS | [정적] | `VideoMetaMapper.firstNonBlank`(145-152, catalog 144-152와 근사) 모두 blank→null 정규화 |
| TC-EXPORT-030 | PASS | [실동작]+[정적] | orgnl/deid 양쪽 export에서 촬영환경(weather/time_of_day/season) 값 동일(kind로 분기 안 함) 확인, `anonymity`만 N/Y로 갈림. `VideoMetaMapper.java:65` `anonymity=(kind==ORIGINAL)?NO:YES` 정확 일치 |
| TC-EXPORT-031 | PASS | [정적] | `NiaJsonBuilder.prepareContext`(88-91) `if(meta==null) throw CustomException(INVALID_INPUT,"영상 메타가 null 입니다.")` — 정확 일치(400은 CustomException→GlobalExceptionHandler 매핑) |
| TC-EXPORT-032 | PASS | [실동작]+[정적] | 전 export JSON `info.version="1.3"`, `type="instances"` 확인. `NiaJsonBuilder.java:37`(FORMAT_VERSION="1.3") / `:39`(TYPE_INSTANCES) / `:96`(info 조립) 정확 일치 |
| TC-EXPORT-033 | PASS | [실동작]+[정적] | rawSn=26 image.file_name="0000.jpg"~"0004.jpg"(FRM_NO 기준), 파생 rawSn=18은 "0007.jpg" 등. `ExportFileNaming.imageFileName(long)` 단일 지점 사용 확인(`NiaJsonBuilder.java:145`), FRM_NO 4자리 zero-pad 규칙과 실측 파일명 정확 일치 |
| TC-EXPORT-034 | PASS | [실동작] | DB `ls_data_src`(rawSn=26) `vdo_frm_no`=0/30/60/90/120(FRM_NO는 0~4 순번)이며, export JSON `image.frame_num`도 정확히 0/30/60/90/120 — VDO_FRM_NO 사용, FRM_NO(추출순번) 아님을 실측 확정. 파일명(0001.jpg=FRM_NO)과 frame_num(30=VDO_FRM_NO)이 서로 다른 값임을 srcSn=297에서 동시 확인 |
| TC-EXPORT-035 | PASS | [실동작]+[정적] | rawSn=18(파생, ORGNL_RAW_SN=4) export 실폴더: `v1/deid/`만 존재(`v1/orgnl/` 디렉터리 자체 없음). JSON `dataset.src_path`/`video.filename`이 파생 자신의 비식별 사본 basename("WINTER.mp4") — 부모 경로 아님. `DatasetExportService.java:184-195` `original kind skipped — derivative video has no original frames reason=DERIVATIVE_NO_ORIGINAL` 로그 문구로 코드상 ORIGINAL 벌 자체를 생성 안 함이 확정 |
| TC-EXPORT-036 | PASS | [실동작]+[정적] | rawSn=26: orgnl `dataset.src_path`=`RAW_FILE_PATH_NM`(.../95b05485-....mp4), deid `dataset.src_path`=deidVideoPath(.../95b05485-....-mask.mp4) — kind별로 서로 다른 실제 경로 확인. `NiaJsonBuilder.java:132-137` 정확 일치 |
| TC-EXPORT-037 | PASS | [정적] | `NiaJsonBuilder.java:174` `src.getFrmExpln()`이 `NiaImage` 마지막 인자로 정확히 전달됨(라이브 관측된 JSON들은 `description:null` — 프레임 설명 미입력 상태와 일치, 필드 자체는 항상 존재) |

## E-8. 촬영환경 메타 — 20건(TC-META-009는 2026-07-30 폐기, 판정 대상 제외)

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-META-001 | PASS | [실동작] | rawSn=26 PUT 저장 후 GET → `weatherSource/timeOfDaySource/seasonSource` 전부 `"MANUAL"`, 저장값 그대로 프리필. `EnvironmentMetaService.java:192-211 toResponse` 일치 |
| TC-META-002 | PASS | [실동작] | 수동값 저장 전 초기 GET(rawSn=26) → `weather:null, timeOfDay:"DAY", season:"SUMMER", timeOfDaySource:"DERIVED", seasonSource:"DERIVED"` — SHT_DT 기반 파생 프리필 확인(조회 전용, 동결과는 별개 정책) |
| TC-META-003 | PASS | [실동작] | PUT 3필드 전송 → `ls_data_raw.wthr_nm/day_ngt_cd/sesn_cd` 3컬럼만 UPDATE 확인(dirty checking). `EnvironmentMetaService.java:100-129 update()` 일치 |
| TC-META-004 | PASS | [실동작] | weather만 전송(`{"weather":"눈"}`) → 응답 `timeOfDay:"DAY"(DERIVED), season:"SUMMER"(DERIVED)` — timeOfDay/season 수동값 삭제 후 파생 폴백 확인 |
| TC-META-005 | PASS | [실동작] | `weather:"폭우"` PUT → 400 `"촬영환경 weather 값이 허용 목록에 없습니다."`. `ShootingEnvironmentVocabulary.java:23` WEATHERS 5종 화이트리스트와 일치 |
| TC-META-006 | PASS | [실동작] | `timeOfDay:"NIGHT"`(오표기, 실제 허용값은 "NGT") PUT → 400 확인 / `season:"MONSOON"` PUT → 400 확인. `TimeOfDaySeasonDeriver.NIGHT="NGT"` 상수 재사용 확인 |
| TC-META-007 | PASS | [정적] | 허용값 전부 20자 이내라 화이트리스트 검사(`validate`, 174-185)에서 함께 걸림 + DTO `@Size(max=ShootingEnvironmentVocabulary.MAX_LENGTH=20)` 이중 방어(`EnvironmentMetaUpdateRequest.java:28,32,36`) 확인 |
| TC-META-008 | PASS | [실동작] | rawSn=26(APPROVED) PUT 후 백엔드 로그 `[EnvironmentMeta] re-freeze triggered rawSn=26` 확인 + export v6가 새 촬영환경 값을 반영해 재생성됨(TC-META-017과 동일 증거로 교차확인). `RVW_CMPL_DT` 승계는 `reFreezeApprovedSnapshot`(156-167)에서 `active.get(0).getRvwCmplDt()`를 그대로 `materialize`에 전달하는 코드로 확인(정적) |
| TC-META-009 | N/A | — | 2026-07-30 폐기 케이스(대체: TC-META-017). 판정 대상 제외 |
| TC-META-010 | PASS | [실동작] | 미검수(PENDING, export 0건) rawSn=7에 PUT → 로그에 `updated rawSn=7`만 있고 `re-freeze triggered` 로그 없음(30초 tail 확인) — 재동결·통지 모두 미수행 확인 |
| TC-META-011 | PASS | [정적] | `reFreezeApprovedSnapshot`(156-167) `if(active.isEmpty()){log.warn(...); return;}` — fail-safe skip, 예외 미발생. 라이브 재현(APPROVED인데 스냅샷 없는 이례 상태)은 본 환경에 없어 코드 근거만 |
| TC-META-012 | PASS | [정적] | `update()`(115-121) `videoRepository.flush()`(raw 행 락) → `videoMetaRepository.acquireRawLock(rawSn)`(advisory) → `isReviewApproved` 판정 순서가 소스에 그대로 구현됨. 실제 동시 요청(PUT vs approve) 경합 재현은 타이밍 조작이 필요해 본 검증에서는 미수행 — 코드·주석(Javadoc 93-98)의 순서 보장 논리로 판정 |
| TC-META-013 | PASS | [실동작] | WORKER(2001)로 미배정 rawSn=5 GET/PUT → 둘 다 403 `"본인에게 배정되지 않은 영상입니다."`(IDOR 방어 확인) |
| TC-META-014 | PASS | [실동작] | 토큰 없이 rawSn=26 GET → 401 `"인증이 필요합니다."` 확인 |
| TC-META-015 | PASS | [실동작] | rawSn=999999999 GET → 404 `"영상을 찾을 수 없습니다."`. `findRaw`(187-190) 일치 |
| TC-META-016 | PASS | [실동작] | `weather:"폭우"`(허용값 외) PUT 요청 후 백엔드 로그 확인 — `[EnvironmentMeta] rejected value field=weather`만 남고 입력 원문("폭우")은 로그에 없음(CWE-117/209 방어 실증) |
| TC-META-017 | PASS | [실동작] | PUT(weather=비/NGT/WINTER) → 약 75초 후 디바운스 flush 로그 `[ControlNotifyDebounce] flush rawSn=26 regen=true` → `[DatasetExport] async re-export... forceRegenerate=true` → export v6 SUCCEEDED → v6 JSON `video.weather="비", time_of_day="NGT", season="WINTER"` 정확 반영 — end-to-end 완전 실증 |
| TC-META-018 | PASS | [정적]+[실동작 정황] | `DatasetVideoMetaSnapshotService.java:120-123` `nullIfBlank(src.getDayNgtCd()/getSesnCd()/getWthrNm())` — SHT_DT 파생 없이 수동값만 동결(주석에 "여름 18:00이 NGT로 오분류되던 실증" 명시). rawSn=18(2026-07-31 생성) 최초 export JSON에서 `weather/time_of_day/season` 전부 null이었던 것도 self-fill 폐기 정황과 부합 |
| TC-META-019 | PASS | [실동작] | `GET /v1/dev/dataset-video-meta/shooting-env-correction-targets`(REVIEWER) → 200 `{"targetCount":0}`(본 환경엔 레거시 파생 동결행 없음, 정정 미수행 확인) |
| TC-META-020 | PASS | [실동작] | `POST /v1/dev/dataset-video-meta/shooting-env-corrections`(REVIEWER) → 200 `{"corrected":0,"remaining":0,"completed":true}`, 재호출도 동일(0,0) — 멱등 확인. WORKER 토큰으로 GET 호출 시 403 `"권한이 없습니다."`(REVIEWER 전용 확인). 대상 0건 환경이라 상한(200건/회) 초과 케이스의 실동작은 미관찰, 설정키(`env-correction.max-per-run`)·페이징 로직은 코드로 확인 |

## E-9. 프레임 개인정보 메타 — 15건

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-META-030 | PASS | [실동작] | 저장값 없는 srcSn GET → 파생값(`ANONY_INCL_YN` 등 영상 타입 기준) 반환, 저장 후 GET → 저장값 그대로. `toEffective`(FramePrivacyMetaService) 일치 |
| TC-META-031 | PASS | [실동작] | srcSn=296 PUT(anonymity/pseudonymity/privacyIncluded 3필드) → 200 저장 확인, DB `ls_data_src` 즉시 반영 |
| TC-META-032 | PASS | [실동작] | `anonymity:"true"` PUT → 400 `"anonymity 는 Y 또는 N 이어야 합니다."`(`@Pattern("^[YN]$")`) |
| TC-META-033 | PASS | [실동작] | path srcSn=296, body srcSn=297 PUT → 400 `"path 의 srcSn 과 body 의 srcSn 이 다릅니다."`(CWE-345 방어 확인) |
| TC-META-034 | PASS | [실동작] | srcSn=296에 `ANONY_INCL_YN='Y'` 저장돼 있어도 export JSON `image.anonymity`는 여전히 kind파생(orgnl N/deid Y) — 프레임 수동값이 export를 덮지 않음(TC-EXPORT-022와 쌍으로 실증) |
| TC-META-035 | PASS | [실동작] | 벌크 PUT(srcSn 296,297) 200 성공. `findAllById`(1회)+rawSn당 인가 1회+`saveAll`(1회) 구조는 `updateBulk`(100-136) 코드로 확인(SQL 로그 별도 카운트는 미측정이나 구조상 N+1 아님이 명확) |
| TC-META-036 | PASS | [실동작] | 벌크 PUT에 미존재 srcSn=9999999 포함 → 404 `"프레임을 찾을 수 없습니다."`(전체 거부) |
| TC-META-037 | PASS | [실동작] | WORKER(2001) 벌크 PUT(본인 srcSn=296 + 미배정 srcSn=31) → 403 `"본인에게 배정되지 않은 영상입니다."`. 404/403 혼재 케이스(srcSn=9999999 + srcSn=31)는 **404가 먼저** 반환됨 — "404先→403後 순서 보존" 실증 일치 |
| TC-META-038 | PASS | [실동작] | 벌크 PUT(srcSn=297 유효값 변경 + srcSn=9999999 무효) → 404 실패 후 DB 재조회 시 srcSn=297 값이 **변경 전 그대로**(Y/Y/N) — `@Transactional` 전체 롤백 확인(부분 반영 없음) |
| TC-META-039 | PASS | [실동작] | 벌크 PUT(srcSn 296,297, rawSn=26 APPROVED) 후 디바운스 flush 로그에 `frames=296=[META_UPDATED],297=[META_UPDATED]` 코얼레스 확인 → export force 재생성(v6) → 통지 순서로 실행됨(env-meta 변경과 동시에 1회로 묶여 처리된 것까지 실측) |
| TC-META-040 | PASS | [실동작] | 벌크 PUT `{"items":[]}` → 400 `"items 는 1건 이상이어야 합니다."` |
| TC-META-041 | PASS | [실동작] | WORKER(2001) 미배정 srcSn=31 GET → 403. 토큰 없이 srcSn=296 GET → 401 |
| TC-META-042 | PASS | [정적] | `applyAndNotify` 로그 `log.info("...updated srcSn={} rawSn={}", ...)` — anonymity/pseudonymity/privacyIncluded 판단값은 로그 인자에 없음(코드상 원천 배제). 벌크 로그도 `count={} rawSns={}`뿐, 개별 판단값 미포함 |
| TC-META-043 | PASS | [실동작] | 단건 PUT(srcSn=296, APPROVED 영상)도 디바운스 로그에 `META_UPDATED` 반영되어 재export 트리거됨(TC-META-039와 동일 flush 이벤트로 교차 실증) |
| TC-META-044 | PASS | [정적] | `AugmentExtractPersist.java:104-115`/`ResolutionPersistService.java`(insertFrames) 둘 다 `parent==null?null:parent.getAnonyInclYn()/getPsdoInclYn()/getPrvcInclYn()`로 부모 프레임 값을 파생 프레임 INSERT 시 그대로 복사(부모 null이면 파생도 null). ⚠ 이 복사는 **파생 생성 시점의 스냅샷**이라 이후 부모 값이 바뀌어도 기존 파생에 소급 반영되지 않음(라이브 확인: rawSn=18의 부모 rawSn=4 srcSn=1이 나중에 `anony_incl_yn='Y'`로 설정됐지만 이미 생성된 파생 srcSn=45는 `null`로 남아있음 — 이는 "확정 시점 1회 복사" 설계와 일치하는 정상 동작이며 결함 아님, 라이브 재동기화를 요구하는 근거는 카탈로그에도 없음) |

## 요약

- **합계 53건**(TC-META-009 폐기 제외 실질 52건 판정 대상): **PASS 52 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 1(폐기) / 확인필요 0**
- 이슈(E-ISSUE-101~120 범위) **발견 0건** — 결함으로 기록할 FAIL/PARTIAL 없음.
- 핵심 실증(확증편향 방지 포커스):
  - **anonymity 수동override 금지**(TC-EXPORT-022/TC-META-034): srcSn=296에 실제로 `ANONY_INCL_YN='Y'`를 저장한 뒤에도 export JSON은 kind파생(N/Y)만 실었다 — 코드만이 아니라 "저장값이 있는데도 안 먹힘"을 라이브로 반증 시도해 통과 확인.
  - **frame_num=VDO_FRM_NO vs 파일명=FRM_NO 분리**(TC-EXPORT-033/034): DB 원본 컬럼과 JSON 출력을 나란히 대조해 두 값이 실제로 다르다는 것(0/30/60/90/120 vs 0001~0004)을 직접 확인.
  - **재export 트리거 end-to-end**(TC-META-008/017/039/043): PUT → 60초 디바운스 → force export → 새 버전 폴더 JSON 값 갱신까지 전 구간을 시간차를 두고 재확인, 심지어 env-meta와 frame-meta 두 변경이 하나의 통지로 코얼레스되는 것까지 로그로 실증.
  - **벌크 원자성**(TC-META-038): 성공할 값과 실패할 값을 섞어 보내 실패 시 DB에 부분 반영이 없는지 직접 대조.
  - **파생영상 ORIGINAL 벌 미생성**(TC-EXPORT-035): 파일시스템에서 `orgnl/` 디렉터리 자체가 없음을 직접 확인.
- self-fill 의심 점검: weather/time_of_day/season 전부 수동 입력 전 null(자동 파생 없음), VLM 없이 촬영환경 채워지는 경로 없음 — self-fill 결함 0건.
- 근거 file:line 드리프트: 없음(catalog의 모든 file:line이 실제 라인과 정확히 일치하거나 ±3라인 이내 근접 — 주석 삽입에 의한 자연스러운 오차 수준).
