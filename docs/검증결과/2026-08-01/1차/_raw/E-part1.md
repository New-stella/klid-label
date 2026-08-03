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
