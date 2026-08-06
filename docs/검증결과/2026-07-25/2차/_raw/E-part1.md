# E 클러스터 part1 (E-1·E-2) 2차 검증 결과

> 대상: `docs/test-cases/E-augment-resolution-export-meta.md` §E-1(증강 요청 18건) · §E-2(증강 검수 23건) = **표 행 41건**
> 환경: 로컬 도커 스택 — backend `localhost:18081`(HEAD `ca3c712b`) · mock-server `:9400` · postgres `:5432`(스키마 `public`)
> 방식: **실동작 우선**(실제 API 호출 + `LS_DATA_AUG`/`LS_DATA_AUG_JOB`/`LS_DATA_AUG_RVW` DB 관측 + mock-server 인바운드 로그 + backend 로그) → 정적 대조 → 테스트 커버(실행 금지, Grep 대조)
> 검증 시각: 2026-07-31 03:43~03:52 KST

## 집계

| 판정 | 건수 | 비고 |
|---|---:|---|
| PASS | 39 | 그중 **34건이 실동작 근거**(HTTP 응답 + DB 행 + 외부 목 로그) |
| PARTIAL | 1 | TC-AUG-033 (`completedAt` 실동선 미충족) |
| FAIL | 0 | |
| BLOCKED | 0 | |
| N/A | 1 | TC-AUG-003 (`~~취소선~~` 폐기 — 집계 제외 대상) |
| 확인필요 | 0 | |
| **표 행 합계** | **41** | 검증 대상 실질 **40건**(폐기 1건 제외) |

- 이슈 3건 — **MEDIUM 2 / LOW 1**
- **근거 드리프트 1건** (TC-AUG-009)
- **self-fill 0건** — 외부 실패 주입 시 BE 가 자체 성공 처리하지 않음을 실측으로 반증 확인(아래 §self-fill 점검)

> ※ 각주(폐기 행): `TC-AUG-003`(videoIds/types 중복 정규화)은 단건 계약 확정(E-ISSUE-08)으로 distinct 로직이 삭제돼 **[폐기 2026-07-30]** 처리된 행이다. 검증 대상이 아니며 집계에서 제외했다(대체 케이스 TC-AUG-017 은 PASS).

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 실측 | 판정 |
|---|---|---|---|
| **ENV-ISSUE-01** | 증강 외부 연동이 mock-server 를 전혀 호출하지 않고 내부 시뮬레이터가 자체 완료 처리(`NoopExternalAugmentClient` 로그만, 구현 부재) | **해소.** 활성 구현체가 `HttpExternalAugmentClient` 임을 backend 로그로 실증(`k.c.c.a.a.i.HttpExternalAugmentClient - [Augment] genai submit originAugSn=27 augType=WINTER jobSeq=1/1 inputCount=6`). mock 이 `POST /api/genai/jobs` 202 로 수신하고(`[MOCK][GENAI] job accepted job_id=a17f5cabe6e94d0381da40c2b8225d0d request_id=AUG-ffd90212-…-1 mode=I2I inputs=6`) 웹훅 4회를 backend 로 되돌림. `NoopExternalAugmentClient` 는 `@ConditionalOnProperty(mode=noop)` 로 비활성 | ✅ 해소 |
| **E-ISSUE-02** | TC-AUG-001/027 증강 외부 연동 미구현 | 위와 동일 근거로 해소. `syncDecision` 만 여전히 no-op(외부 계약 미정의 — 카탈로그 TC-AUG-027 이 이미 그렇게 명시) | ✅ 해소(027 은 설계상 no-op) |
| **E-ISSUE-05** | TC-AUG-053 — UNIQUE 위반 후 "멱등 흡수" 경로가 PostgreSQL 에서 동작 불가(tx abort → 500) | **요청 입구(E-1)에서는 해소 확인.** 6병렬 동시 요청 실측에서 패자 5건이 **전부 409**(500 누수 0건). 코드가 `DataIntegrityViolationException` 을 삼키지 않고 409 로 tx 를 종결(`AugmentRequestService.java:324-335`). ※ TC-AUG-053 본체(웹훅 수신 경로)는 E-3 담당 구간 | ✅ 해소(E-1 범위) |
| **E-ISSUE-06** | dead-letter·retry 가 프로덕션 도달 불가(테스트 위양성) | **해소 — 실경로 도달 실측.** 위탁 거부 시나리오에서 `AugmentResultService.markProcessingFailure` → `markDeadLetter()` 가 실제로 찍힘(`ls_data_aug.data_aug_sn=28 dead_letter_at=2026-07-31 03:49:37.92`). 프로덕션 호출자 2곳(`AugmentResultService.java:319`, `AugmentExtractPersist.java:199`) | ✅ 해소 |
| **E-ISSUE-07** | AUGMENT 멱등 원장이 write-only | **해소.** `LS_WEBHOOK_IDEMPOTENCY` 실측 = `VLM` 24행뿐, **`AUGMENT` 채널 0행**(선기록 제거 확인). 발급 원장은 `LS_DATA_AUG_JOB.IDMP_KEY`(실측 `AUG-<uuid>-1` 청크 접미) | ✅ 해소 |
| **E-ISSUE-08** | 단건 계약과 다건 distinct 로직 불일치 | **해소.** DTO `@Size(max=1)` 1차 + `requireSingleSelection` 2차 모두 400 실측 | ✅ 해소 |
| **E-ISSUE-09** | 생성 0건이어도 200(silent no-op) | **해소.** 프레임 0건 영상(rawSn=151) 요청이 **412 + `skippedVideoIds:[151]`**, aug 행 0건 | ✅ 해소 |
| **E-ISSUE-10** | 콜백 base URL 기본값이 자기 자신(localhost:8080) | **로컬 형상에서는 해소** — `WEBHOOK_CALLBACK_BASE_URL=http://klid-backend:8080/api` 로 주입되고 mock 이 그 URL 로 되돌림. 코드 기본값(`AugmentCallbackUrlResolver:23`)은 여전히 `http://localhost:8080/api`(주입 누락 시 자기 자신) — 배포 형상 점검은 E-part2/배포 담당 | 🔶 부분(환경 주입 의존) |
| **E-ISSUE-12** | 실동작 미재현 분기(TC-AUG-027/038/004) | **038/004 는 이번에 실동작 재현 완료**(cctvName 폴백 `(이름 없음)` · APPROVED+프레임0 412). 027(syncDecision throw)은 활성 구현이 throw 하지 않아 여전히 프로덕션 도달 불가 — 카탈로그 기대값과 일치 | ✅ 대부분 해소 |
| **확증편향 주의(1차 Mockito 위양성)** | 중복 차단 테스트가 Mockito 스텁이라 PG tx abort(25P02) 미재현 | **테스트 자산 자체가 실 DB 기반으로 교체됨** — `AugmentRequestServiceTest:693 partialUniqueIndexRejectsActiveDuplicateInsert`(`saveAndFlush` 로 실제 인덱스 위반 유도) · `:725 concurrentRequests_onlyOneActiveAugRow`(스레드 2개 + `CountDownLatch`). 그럼에도 **테스트 결과를 근거로 쓰지 않고 6병렬 실 API 로 독립 재현**함 | ✅ |

## ★중복 증강 요청 차단 실측

| 항목 | 실측값 |
|---|---|
| **시나리오** | `POST /api/v1/augments/request` `{"videoIds":[136],"types":["NIGHT"]}` — rawSn=136(APPROVED, 프레임 12, 대표프레임 SRC_SN=93) |
| **병렬수** | **6** (동일 프로세스에서 `&` 백그라운드 6개 동시 발사, 사전 대기 없음) |
| **응답 분포** | **200 × 1 / 409 × 5** (5xx 0건) |
| **파생 생성수** | `LS_DATA_AUG` **활성 1행만 생성**(`data_aug_sn=5, src_sn=93, NIGHT`). PK 시퀀스는 4·6·7·8·9 를 소비하고 롤백 → **PG 가 요청 tx 전체를 abort 시켰음이 시퀀스 갭으로 확인**됨 |
| **차단 주체** | backend 로그가 5건 전부 **DB 부분 유니크 인덱스** 경로임을 명시: `[Augment] aug request rejected — active duplicate constraint srcSn=93 augType=NIGHT` ×5 (사전조회 1선이 아니라 **최종 방어가 실제로 작동**) |
| **인덱스 실존** | `pg_indexes` 실측 — `uk_ls_data_aug_actvtn UNIQUE, btree (src_sn, aug_type_cd) WHERE (aug_proc_stts_cd = ANY ('{PENDING,ACCEPTED}')) AND aug_type_cd !~~ 'RESL\_%'` ✔ (V143 술어 = `LsDataAug.ACTIVE_STATUSES` 와 일치) |
| **안내문구 — PENDING** | 사전조회 경로 실측: `409 {"duplicatedRequests":[{"videoId":156,"type":"WINTER","status":"PENDING"}]}` / msg = `"이미 요청되어 진행 중인 증강입니다. 결과가 도착해 완료되거나 검수에서 반려된 뒤 다시 요청하세요."` ✔ |
| **안내문구 — ACCEPTED** | 실측: `409 {"duplicatedRequests":[{"videoId":126,"type":"WINTER","status":"ACCEPTED"}]}` / msg = `"이미 채택된 증강입니다. 채택된 증강은 되돌릴 수 없어 같은 영상·종류로는 다시 요청할 수 없습니다."` — **구 "반려 후 다시 요청하세요"(수행 불가능한 안내) 없음** ✔ |
| **안내문구 — 인덱스 경로** | 동시 요청 패자에게는 `"이미 요청되어 진행 중인 증강입니다…"`(상대 건이 방금 만들어진 PENDING 이므로 정확) ✔ |
| **REJECTED 후 재요청** | `src_sn=77` 에 `WINTER/REJECTED` 존재 상태에서 rawSn=132 × WINTER 재요청 → **200 정상 접수**, 새 PENDING 행(`data_aug_sn=27`) 생성. REJECTED 행은 그대로 보존(2행 공존) ✔ 막히지 않음 |

## E-1 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---|---|
| TC-AUG-001 | 검수완료 영상 증강 요청 성공 (단건 계약) | PASS | [실동작] `POST /v1/augments/request {videoIds:[132],types:["WINTER"]}` → `200 {"jobId":1785433411713,"requestedAt":"2026-07-31T03:47:33.47","videoCount":1,"typeCount":1,"createdCount":1}`. DB: `data_aug_sn=27, src_sn=77, WINTER, PENDING, idmp_key=AUG-ffd90212-6e69-40e9-92f2-89a54467f6f4, otsd_job_id=NULL` | 응답 5필드 계약 정확. `OTSD_JOB_ID` 는 이후 외부 202 로 채워짐(TC-AUG-007 참조) |
| TC-AUG-002 | 미검수 영상 거부 | PASS | [실동작] rawSn=135(ASSIGNED) → `400 NOT_REVIEWED {"blockedVideoIds":[135]}`. aug 행 0건 증가. 로그 `request blocked — not reviewed actor=1001 blockedCount=1` | `AugmentRequestService.java:127-137` 일치 |
| TC-AUG-003 | ~~videoIds/types 중복 정규화~~ | N/A | — | **폐기 행**(취소선). 대체 케이스 TC-AUG-017 PASS |
| TC-AUG-004 | 프레임 미추출 영상 = 412 거부 | PASS | [실동작] rawSn=151(APPROVED·프레임 0) → `412 PRECONDITION_FAILED {"skippedVideoIds":[151]}` msg="프레임이 추출되지 않은 영상은 증강을 요청할 수 없습니다." aug 행 0건. 로그 `request blocked — no frame extracted rawSn=151` | 1차 E-ISSUE-12 "데이터 부재로 미재현" → 이번에 실데이터 확보해 재현 |
| TC-AUG-005 | WORKER 증강 요청 차단 | PASS | [실동작] WORKER 토큰 → `403 FORBIDDEN`. PORTAL_USER 토큰도 `403`. Controller `@PreAuthorize("hasRole('REVIEWER')")`(`AugmentController.java:109`)가 1차 차단(서비스 2차 `:381-388` 존재) | 1차 차단이라 서비스 메시지("REVIEWER 권한이 필요합니다.")는 미노출 — 정상 |
| TC-AUG-006 | 인증 토큰 없음 | PASS | [실동작] 무토큰 → `401 UNAUTHORIZED` | |
| TC-AUG-007 | idempotencyKey만 발급 · externalJobId 미발급 | PASS | [실동작] INSERT 직후 `idmp_key=AUG-e0542532-13ca-41e5-893f-69bcda9cb7ef`(40자 ≤64, `^[A-Za-z0-9_-]+$` 충족), `otsd_job_id=NULL`. 이후 외부 202 수신 시 `otsd_job_id=6e094b96030547d8a22573d818d1a153` 로 반전 — **mock 이 발급한 job_id 와 문자열 일치**(`[MOCK][GENAI] job accepted job_id=6e094b96…`) | **self-fill 반증 핵심 근거**: job_id 를 BE 가 만들지 않고 외부 응답값을 그대로 적재 |
| TC-AUG-008 | 적재 실패 시 실패 회신 | PASS | [정적] `AugmentRequestService.java:176-181`(`created==false` → `INTERNAL_ERROR` + `skippedDetails`) + `:336-341`(예외를 로그 후 false 반환, 200 회신 없음). [테스트] `AugmentRequestContractTest#zeroCreatedIsNotSuccess` · `#singleItemFailureIsIsolatedAndReported` · `#skippedVideoIsIdentifiable` | `augRepository.save` 강제 실패를 실환경에서 유발할 안전한 수단 없음(스키마 변조 필요) → 정적+테스트 대조 |
| TC-AUG-009 | AFTER_COMMIT — 롤백 시 행·위탁 미발생 | PASS | [실동작 부분] `LS_WEBHOOK_IDEMPOTENCY` 채널별 실측 = `VLM 24` 뿐 **AUGMENT 0행** → 선기록 제거 확인. [정적] `AugmentRequestBridge.java:100-103` `@Async("batchAsyncExecutor")` + `@TransactionalEventListener(AFTER_COMMIT)`. [테스트] `AugmentRequestBridgeTest#noOrphanLedgerKeyOnRollback` | ⚠ **근거 드리프트** — 카탈로그 `:29-35,92-96` 은 javadoc 구간, 실제 리스너는 **100-103**(§근거 드리프트 참조) |
| TC-AUG-010 | 콜백 URL 조립 | PASS | [실동작] `WEBHOOK_CALLBACK_BASE_URL=http://klid-backend:8080/api`(컨테이너 env) → mock 이 되돌린 URL 이 정확히 `http://klid-backend:8080/api/v1/genai/callback`(`[MOCK][GENAI] webhook sent url=…` ×8). [정적] `AugmentCallbackUrlResolver.resolve()` 후행 슬래시 제거 + `WebhookProtectedPaths.java:50 PATH_GENAI_CALLBACK="/v1/genai/callback"` | 구 `/v1/aug/callback` 미사용 확인 |
| TC-AUG-011 | jobId 동시성 유일성 | PASS | [실동작] 연속 요청의 jobId `1785433411712 → …713 → …714` 단조 증가, 중복 0. 6병렬 요청에서도 성공 1건뿐이라 충돌 없음. `LS_DATA_AUG` 에 jobId 컬럼 부재 = **미영속 placeholder** 확인 | `AugmentRequestService.java:102,183` |
| TC-AUG-012 | 중복 활성 요청 차단 — PENDING 안내 | PASS | [실동작] `409 CONFLICT` + `data.duplicatedRequests=[{videoId:156,type:"WINTER",status:"PENDING"}]`, msg 원문 일치. 로그 `request blocked — duplicate active augment duplicatedCount=1` | `:212-276` |
| TC-AUG-013 | 중복 활성 요청 차단 — ACCEPTED 안내 | PASS | [실동작] rawSn=126 × WINTER(기존 `data_aug_sn=1` ACCEPTED) → `409` + `status:"ACCEPTED"`, msg = "이미 채택된 증강입니다. 채택된 증강은 되돌릴 수 없어 같은 영상·종류로는 다시 요청할 수 없습니다." | "반려 후 재요청" 같은 **수행 불가능 안내 없음** ✔ `:264-276` |
| TC-AUG-014 | 동시 요청 최종 방어 = DB 부분 유니크 | PASS | [실동작] 6병렬 → 200×1 / 409×5, aug 활성 1행. backend 로그 5건 모두 `aug request rejected — active duplicate constraint`(=`DataIntegrityViolationException` 경로). `uk_ls_data_aug_actvtn` 실존 확인. **500 0건** | 시퀀스 갭(4,6~9)으로 tx 전체 abort 실증 |
| TC-AUG-015 | REJECTED 후 재요청 허용 | PASS | [실동작] `(src 77, WINTER, REJECTED)` 존재 상태에서 rawSn=132×WINTER → `200 createdCount=1`, 새 PENDING 행 생성(REJECTED 행 보존, 2행 공존) | `LsDataAug.java:60 ACTIVE_STATUSES` ∌ REJECTED, V143 술어 동일 |
| TC-AUG-016 | 비식별 누락 신고 구간 요청 차단 | PASS | [실동작] rawSn=132 를 `DE_IDENT_YN='F'` 로 전환 → `412 PRECONDITION_FAILED {"blockedVideoIds":[132]}` msg="비식별 재처리 대기 중인 영상은 증강을 요청할 수 없습니다.", aug 행 0건. 로그 `request blocked — deident report open`. 검증 후 `'Y'` 로 **원복 완료** | `:143-153` + `DeidentReportGate` 단일 원천(`DE_IDNTF_YN` 단일 컬럼). rawSn=133(신고 OPEN 보존 대상)은 건드리지 않음 |
| TC-AUG-017 | 단건 계약 위반 거부 | PASS | [실동작] `videoIds:[146,132]` → `400 INVALID_INPUT "videoIds: 영상은 한 번에 1건만…"` / `types:["NIGHT","RAIN"]` → `400 "types: 증강 종류는…"` / `types:["RESOLUTION"]` → `400`(enum allowlist WINTER·NIGHT·RAIN) | DTO 1차가 먼저 걸림. 서비스 2차 `requireSingleSelection`(`:285-290`)은 정적 확인 + 테스트 `AugmentRequestServiceTest#multiSelectionRejectedAtServiceLayer` |
| TC-AUG-018 | V143 선행 정리 — ACCEPTED 2건이면 마이그레이션 중단 | PASS | [정적] `V143__add_ls_data_aug_active_unique.sql` 전문 확인 — ①`DO $$` 가드가 ACCEPTED 중복 조합 카운트 후 `RAISE EXCEPTION` ②`WITH ranked … UPDATE … AND a.AUG_PROC_STTS_CD='PENDING'` 로 **PENDING 만** 종결 강등(ACCEPTED 강등 구조적 불가) ③행·검수이력 삭제 없음 ④인덱스 `IF NOT EXISTS` | 마이그레이션 적용 후에는 인덱스가 중복 ACCEPTED 생성 자체를 막아 **재현 불가**(재현하려면 인덱스 DROP = 파괴적) → 정적 판정 |

## E-2 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---|---|
| TC-AUG-020 | 증강 결과 승인 PENDING→ACCEPTED | PASS | [실동작] PENDING 행(`data_aug_sn=10, src 127`) accept → `200 {augProcSttsCd:"ACCEPTED", decisionUserNo:"1001", decisionAt:…}`. `LS_DATA_AUG_RVW` 신규 행 생성(`data_raw_sn=153, data_src_sn=127, rvw_stts_cd=ACCEPTED, rvw_id=1001`). `syncDecision` best-effort 로그 `decision sync skipped (외부 계약 미정의) dataAugSn=10 decision=ACCEPTED` | ⚠ 별건 관찰 **E-ISSUE-01**(in-flight accept ↔ 콜백 충돌) — 케이스 자체는 명세대로 통과 |
| TC-AUG-021 | 이미 처리된 결과 재승인 차단 | PASS | [실동작] 동일 행 재-accept → `409 "이미 처리된 증강 결과입니다. status=ACCEPTED"`. ACCEPTED 행 reject 시도도 동일 409 | `LsDataAug.java:264 applyReviewStatus`(PENDING 에서만 전이) |
| TC-AUG-022 | 반려 사유 누락 거부 | PASS | [실동작] `{"reason":"   "}` → `400 "reason: 반려 사유는 필수입니다."` / `{}` → 동일 400 | DTO 1차(`RejectRequest`)가 먼저 걸림. 서비스 2차 `AugmentReviewService.java:363-365` 정적 확인 |
| TC-AUG-023 | 반려 정상 PENDING→REJECTED | PASS | [실동작] `data_aug_sn=11` reject(사유 "품질 미흡") → `200 {augProcSttsCd:"REJECTED", rejectReason:"품질 미흡"}`. RVW 행 `rvw_stts_cd=REJECTED, rjct_rsn=품질 미흡` 적재 | |
| TC-AUG-024 | 해상도 파생 accept 차단 | PASS | [실동작] `RESL_720P` 행(`data_aug_sn=2`) accept → `400 INVALID_INPUT "해상도 파생 결과는 검수 대상이 아니습니다"`(원문 "해상도 파생 결과는 검수 대상이 아닙니다.") | `:421-431 loadOrThrow` |
| TC-AUG-025 | 해상도 파생 reject 차단 | PASS | [실동작] `RESL_480P` 행(`data_aug_sn=3`) reject → `400` 동일 메시지(`loadOrThrow` 공통 게이트) | `:427-429` |
| TC-AUG-026 | 존재하지 않는 증강행 검수 | PASS | [실동작] `POST /v1/augments/9999999/accept` → `404 NOT_FOUND "증강 결과를 찾을 수 없습니다."` | `:422-423` |
| TC-AUG-027 | 외부 sync 실패 best-effort | PASS | [정적+실동작] 활성 구현 `HttpExternalAugmentClient.java:83-88 syncDecision` 은 **로그 후 무조건 `true`** — throw 분기 프로덕션 도달 불가. 실동작에서 accept/reject 3회 모두 `decision sync skipped` INFO 만 남고 본 tx 정상 커밋(200) | 카탈로그 ⚠ 주석과 정확히 일치. 예외 분기는 단위 스텁 전용 |
| TC-AUG-028 | 검수 WORKER/미인증 차단 | PASS | [실동작] WORKER accept → `403 FORBIDDEN` / 무토큰 accept → `401 UNAUTHORIZED` | Controller `@PreAuthorize`(138·162행) 1차 + 서비스 `:433-440` 2차 |
| TC-AUG-029 | 잡카드 전체 페이징 — SRC_SN 그룹 최신순 | PASS | [실동작] `GET /v1/augments?page=0&size=20` → `totalElements=9`, 9개 잡카드가 `MIN(REG_DT)` 최신순으로 반환. WORKER 도 200(`hasAnyRole('REVIEWER','WORKER')`). [정적] `buildJobs`(`:148-170`)가 일괄 조회 4종(`findBySrcSnIn` / `findRawSnBySrcSnIn` / `findCctvNamesByRawSns` / `findByDataAugSnIn`)만 사용 — 그룹 수와 무관한 고정 쿼리 수 = N+1 없음 | |
| TC-AUG-030 | types/resolutionTypes 분리 노출 | PASS | [실동작] src 65 그룹(WINTER + RESL_720P + RESL_480P 혼재) → `"types":["WINTER"], "resolutionTypes":["RESL_720P","RESL_480P"]` 로 정확히 분리 | `:185-190` |
| TC-AUG-031 | AUG_ORDER 정렬 | PASS | [실동작] 7종 혼재 픽스처 그룹 → `types:["WINTER","NIGHT","RAIN","RESOLUTION","FOG"]`(미정의 `FOG`=99 로 최후미) / `resolutionTypes:["RESL_1080P","RESL_720P","RESL_480P"]` | `:58-66 AUG_ORDER` 값과 완전 일치 |
| TC-AUG-032 | aggregateStatus — dead-letter→FAILED (실경로 도달) | PASS | [실동작] ①`DEAD_LETTER_AT` 보유 그룹 → 잡카드 `status:"FAILED"` ②**실경로 도달 실증** — rawSn=20012(대표프레임 비식별 경로 NULL) 요청 시 `AugmentJobSubmitService` 위탁 거부 → `AugmentRequestBridge` 실패 롤업 → `AugmentResultService.markProcessingFailure` → `dead_letter_at` 적재(`data_aug_sn=28`). 로그 3단 연쇄 확인 | E-ISSUE-06 해소 확증. `:255-259` / `AugmentResultService.java:319` |
| TC-AUG-033 | aggregateStatus — 전부 terminal→COMPLETED | **PARTIAL** | [실동작] COMPLETED 집계는 정확(전 행 terminal + dead-letter 없음 → COMPLETED). `completedAt=max(RVW_DT)` 도 RVW 행이 있는 그룹에서는 성립(`completedAt:"2026-07-31T03:45:41.409933"` = 실제 `rvw_dt`). **그러나 실동선(콜백 종결) 그룹은 RVW 행이 아예 없어 `completedAt` 이 항상 `requestedAt` 폴백**(rawSn 126·136 실측 두 값 동일) | **E-ISSUE-02** |
| TC-AUG-034 | 일부 종료→IN_PROGRESS / 전부 PENDING→REQUESTED | PASS | [실동작] 전 PENDING 그룹 → `"status":"REQUESTED", completedAt:null` / PENDING+ACCEPTED 혼합 그룹 → `"status":"IN_PROGRESS", completedAt:null` | `:260-267` |
| TC-AUG-035 | 해상도 in-flight 집계 정합 | PASS | [실동작] `RESL_720P` **PENDING** 단독 그룹 → `types:[], resolutionTypes:["RESL_720P"], status:"REQUESTED"` — COMPLETED 아님. 반면 `RESL_*` 전부 ACCEPTED 인 그룹은 COMPLETED | `markResolutionGenerated`(`LsDataAug.java:248`)로 전이된 뒤에만 terminal ✔ |
| TC-AUG-036 | aggregateResultStatus 매핑 — FAILED 분기 도달 가능 | PASS | [실동작] `GET /v1/augments/{jobId}/result` — 136→`COMPLETED` / dead-letter 그룹→**`FAILED`** / PENDING 그룹→`PROCESSING` / 혼합→`PROCESSING` | `:119-136` 3값 매핑 전 분기 도달 확인 |
| TC-AUG-037 | aggregateResultStatus SRC_SN 폴백/무데이터 | PASS | [실동작] RAW_SN 매핑 없는 jobId(합성 SRC_SN)도 `findBySrcSnIn` 폴백으로 정확 집계(FAILED/PROCESSING 산출). 데이터 전무한 `jobId=99999999` → `PROCESSING` | `:123-130` |
| TC-AUG-038 | cctvName 비-옵셔널 폴백 | PASS | [실동작] RAW_SN 매핑 부재 그룹 → `"cctvName":"(이름 없음)"`(항상 non-null). 매핑 있는 그룹은 `"CCTV-강남구-001"` | 1차 E-ISSUE-12 "전 그룹 매핑 존재로 미재현" → 이번에 재현 |
| TC-AUG-039 | findBySource(srcSn) 필터 | PASS | [실동작] `?srcSn=999999` → `200 {"totalElements":0,"content":[],"empty":true}`. `?srcSn=65` → 해당 잡카드 1건만 | `:99-106` |
| TC-AUG-040 | list size 한도 초과 | PASS | [실동작] `?size=101` → `400 "size 한도 초과 (max=100)"`. **`?srcSn=65&size=101` → 200**(srcSn 분기가 size 검증보다 앞서 return) — 카탈로그 ⚠ 주석대로 | `AugmentController.java:81-86` |
| TC-AUG-041 | 검수 이력 rawSn 역해석 실패 = 409 | PASS | [실동작] `SRC_SN=8888888`(실재하지 않는 프레임) 증강행 accept → `409 CONFLICT "증강 결과의 원본 영상 정보를 확인할 수 없어 검수를 기록할 수 없습니다."`. 500 아님, `LS_DATA_AUG_RVW` 에 `data_raw_sn=0` 센티널 행 미생성 확인 | 로그 `review blocked — rawSn unresolved dataAugSn=12 srcSn=8888888`. `:409-419` + `fk_ls_data_aug_rvw_raw`(V146) 실존 |
| TC-AUG-042 | 검수행 선재 재사용 | PASS | [실동작] 동일하게 `SRC_SN=8888888` 이지만 **RVW 행을 선재**시킨 증강행 accept → `200 ACCEPTED`(역해석 미수행이라 409 안 남). RVW 행 수 1건 유지, `data_raw_sn=153` 보존 | `findLatestByDataAugSn` 우선(`:389-395`). TC-AUG-041 과 **동일 SRC_SN 대조군**이라 판별력 확보 |

## 근거 드리프트 / self-fill 점검

### 근거 드리프트 (1건)

| TC | 카탈로그 근거 | 실제 위치 | 영향 |
|---|---|---|---|
| TC-AUG-009 | `AugmentRequestBridge.java:29-35,92-96` | 리스너 본체 = **`:100-103`**(`@Async("batchAsyncExecutor")` + `@TransactionalEventListener(AFTER_COMMIT)` + `onAugmentRequested`), 실패 롤업 가드 = **`:120-138`**. 카탈로그가 가리킨 29-35·92-96 은 **클래스/메서드 javadoc 구간** | LOW — 판정에는 영향 없음(동일 파일 내). 카탈로그 라인 갱신 필요 |

> 그 외 E-1·E-2 40건의 `file:line` 근거는 전수 대조 결과 **전부 유효**했다(`AugmentRequestService` 12곳 · `AugmentReviewService` 16곳 · `LsDataAug` 3곳 · `AugmentController` 2곳 · `AugmentRequestRequest` 1곳 · `HttpExternalAugmentClient` 1곳 · `WebhookProtectedPaths:50` · `V143` · `V146` · `AugmentResultService:317-320` · `AugmentExtractPersist:186-203`).

### self-fill 점검 — **0건 (반증 실행 완료)**

| 반증 항목 | 방법 | 결과 |
|---|---|---|
| 외부 job_id 자체 발급 여부 | 요청 직후 `OTSD_JOB_ID` 관측 → 외부 202 이후 재관측 | 요청 시 **NULL**, 외부 202 이후 `a17f5cabe6e94d0381da40c2b8225d0d` — **mock 이 로그에 찍은 job_id 와 문자열 완전 일치**. BE 자체 생성 아님 ✔ |
| 외부 위탁이 실패해도 성공 처리하는가 | rawSn=20012(대표프레임 `DE_IDNTF_SRC_FILE_PATH_NM` NULL) 로 요청 → 위탁 불가 상황 강제 | `LS_DATA_AUG_JOB` = `job_stts_cd=FAILED, err_cd=DEID_PATH_MISSING, err_msg_cn="비식별 프레임 경로가 없는 프레임이 있어 외부 위탁을 거부합니다. missingCount=1"`, `LS_DATA_AUG` = **REJECTED + dead_letter_at 적재**. **성공 위장 0건** ✔ |
| 내부 목 모드 우회 여부 | 활성 `ExternalAugmentClient` 구현 확인 | backend 로그 실체가 `HttpExternalAugmentClient`. `NoopExternalAugmentClient` 는 `@ConditionalOnProperty(havingValue="noop")` 로 미활성 ✔ |
| 콜백이 실제 외부에서 오는가 | mock → backend 웹훅 | `[MOCK][GENAI] webhook sent url=http://klid-backend:8080/api/v1/genai/callback status=200 job_id=… attempt=1` ×8 (10/50/90/100 진행률) ✔ |

### 테스트 커버 대조 (실행 없음 · Grep)

| 구간 | 파일:메서드 |
|---|---|
| 요청 성공/PENDING/키발급 | `AugmentRequestServiceTest#requestSucceedsWhenVideoApproved` · `#createsPendingAug` · `#issuesIdempotencyKeyPerAug` |
| 412(프레임 0)·NOT_REVIEWED | `AugmentRequestServiceTest#videoWithoutFrameIsRejected` · `#rejectsWhenVideoNotApproved` · `#rejectsWhenStatusRowMissing` |
| 중복 409(PENDING/ACCEPTED/REJECTED) | `AugmentRequestServiceTest:614` · `:645` · `:666` |
| **DB 부분 유니크 최종 방어** | `AugmentRequestServiceTest:693 partialUniqueIndexRejectsActiveDuplicateInsert`(실 DB `saveAndFlush`) · `:725 concurrentRequests_onlyOneActiveAugRow`(실 스레드 2개) — **1차 Mockito 위양성 해소** |
| 단건 계약 | `AugmentRequestContractTest#multiSelectionIsRejected` · `AugmentRequestControllerTest#multipleVideoIdsReturns400` · `#multipleTypesReturns400` · `#resolutionTypeRejected400` |
| 생성 0건 실패 회신 | `AugmentRequestContractTest#zeroCreatedIsNotSuccess` · `#skippedVideoIsIdentifiable` · `#responseCarriesActualCreatedCount` |
| AFTER_COMMIT/롤백/Async | `AugmentRequestBridgeTest#afterCommitListenersAreAsync` · `#handOffUsesNewTransactionEntryPoint` · `AugmentCallbackFlowIntegrationTest#noOrphanLedgerKeyOnRollback` · `#submitJobRecordedAfterCommit` |
| 검수 전 구간 | `AugmentReviewServiceTest`(29 테스트 — accept/reject/409/404/RESL 차단/rawSn 미해석 409/집계 4상태/해상도 in-flight/정렬) |
| dead-letter 가시화 | `AugmentFailureVisibilityTest`(6) · `AugmentExtractPersistTest:389` |

> 미커버 확인: **TC-AUG-018(V143 가드)의 `RAISE EXCEPTION` 분기를 검증하는 테스트 없음**(마이그레이션 SQL 단위 테스트 부재). 정적 판정으로 갈음했으며 잔여 리스크는 LOW(가드 미발화 시에도 인덱스 생성이 실패해 기동이 멈춘다).

### 검증 중 DB 변경 및 원복

| 항목 | 조치 |
|---|---|
| rawSn=132 `DE_IDENT_YN` | TC-AUG-016 위해 `'Y'→'F'` 후 **`'Y'` 로 원복 완료** |
| 합성 픽스처 `LS_DATA_AUG` 17행(`data_aug_sn` 10~26, src_sn 127·128·4444444~8888888·77) + `LS_DATA_AUG_RVW` 3행 | 검증 종료 후 **전량 DELETE 완료** |
| 실 API 흐름으로 생성된 행 | `data_aug_sn=5`(rawSn 136 NIGHT ACCEPTED) · `27`(rawSn 132 WINTER ACCEPTED) · `28`(rawSn 20012 RAIN REJECTED/dead-letter) — 실동작 산물이므로 보존. 최종 `LS_DATA_AUG` 6행 |
| rawSn 126·129·133 | **미변경**(126 은 TC-AUG-013 에서 읽기·409 거부만, 133 은 미접근) |

## 이슈 상세

### [E-ISSUE-01] TC-AUG-020 / TC-AUG-021 — in-flight 증강에 대한 REVIEWER accept 가 도착할 성공 콜백을 INFO 로 조용히 흡수(파생영상 미생성)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 외부 위탁이 진행 중(`LS_DATA_AUG_JOB` 비종결)인 증강행은 아직 결과가 없으므로 검수 대상이 아니다. REVIEWER 가 그 행에 accept 를 시도하면 거부(409)되거나, 최소한 뒤늦게 도착한 성공 콜백이 **폐기됐다는 사실이 운영자에게 드러나야** 한다(WARN/알림). "요청은 했는데 파생영상이 없다"가 조용히 성립해서는 안 된다.
- **현재 동작(이슈 내용)**:
  - `AugmentReviewService.loadOrThrow`(`AugmentReviewService.java:421-431`)는 **`RESL_` 접두만** 차단하고 위탁 진행 여부(`LS_DATA_AUG_JOB.JOB_STTS_CD`)를 전혀 보지 않는다.
    ```java
    private LsDataAug loadOrThrow(Long dataAugSn) {
        LsDataAug aug = repository.findById(dataAugSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "증강 결과를 찾을 수 없습니다."));
        if (aug.getAugTypeCd() != null && aug.getAugTypeCd().startsWith(LsDataAug.RESL_PREFIX)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "해상도 파생 결과는 검수 대상이 아닙니다.");
        }
        return aug;      // ← in-flight(PENDING + 비종결 job) 여부 미판정
    }
    ```
  - `LS_DATA_AUG.AUG_PROC_STTS_CD` 는 **성공/실패 콜백이 스스로 종결시키는 축**이다(`AugmentResultService.java:227` `String newStatus = success ? STTS_ACCEPTED : STTS_REJECTED;` → `:498 applyReviewStatus`). 실측으로도 `data_aug_sn=1·5·27` 모두 **`LS_DATA_AUG_RVW` 행 없이** ACCEPTED 로 종결됐다(검증 시작 시점 `ls_data_aug_rvw` = 0행).
  - 따라서 REVIEWER 가 in-flight PENDING 행을 accept 하면, 뒤늦게 도착한 성공 콜백은 non-PENDING 앵커에 막혀 `AugmentResultService.java:186-201` 로 흡수된다. 이때 **REJECTED 로 종결된 경우만 WARN 으로 가시화**하고, **ACCEPTED 로 종결된 경우는 `else` 분기의 INFO** 로 떨어진다.
    ```java
    if (!LsDataAug.STTS_PENDING.equals(aug.getAugProcSttsCd())) {
        if (outcome.success() && LsDataAug.STTS_REJECTED.equals(aug.getAugProcSttsCd())) {
            log.warn("[Webhook][Augment] success result discarded — aug already terminal(REJECTED) ...");
        } else {
            log.info("[Webhook][Augment] duplicate result skipped dataAugSn={} ... state={}", ...);  // ← ACCEPTED 경로
        }
        return AugmentApplyResult.DUPLICATE;
    }
    ```
  - 결과: **파생 RAW 영상은 만들어지지 않는데** 증강 이력 화면은 `status=COMPLETED`(전 행 terminal)로 표시되고, 로그에는 INFO 한 줄만 남는다. 코드 주석 스스로 "운영에서 '요청했는데 파생영상이 없다'의 유일한 단서"라고 적어 놓은 신호가 **accept 경로에만 없다.**
  - 창의 크기: 목 서버는 요청→콜백 약 2~8초라 실측 재현이 어렵지만, 실제 벤더(생성형 AI I2I, 프레임 N장)에서는 수 분~수십 분이며 그 사이 잡카드는 `REQUESTED/IN_PROGRESS` 로 노출되고 accept/reject 버튼이 활성이다(FE 는 `resolutionTypes` 만 숨긴다 — `AugmentReviewService.java:185-190`).
- **재현/확인 경로**:
  ```bash
  # 1) 요청 → 즉시(콜백 도착 전) PENDING 행 확인
  curl -s -X POST http://localhost:18081/api/v1/augments/request \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' \
    -d '{"videoIds":[<APPROVED rawSn>],"types":["RAIN"]}'
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select data_aug_sn, aug_proc_stts_cd from ls_data_aug order by data_aug_sn desc limit 1;"
  # 2) 콜백 도착 전 accept  (mock 은 ~2s 라 벤더 지연 시뮬레이션 필요)
  curl -s -X POST http://localhost:18081/api/v1/augments/<dataAugSn>/accept -H "Authorization: Bearer $RT"
  # 3) 이후 backend 로그 — WARN 이 아니라 INFO "duplicate result skipped" 만 남는지 확인
  docker logs klid-backend 2>&1 | grep -a "duplicate result skipped"
  # 4) 파생 RAW 미생성 확인
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select raw_sn, orgnl_raw_sn from ls_data_raw where orgnl_raw_sn=<rawSn>;"
  ```
  ※ 정적 근거는 위 3개 스니펫으로 완결되며, 실측으로는 "콜백이 `LS_DATA_AUG` 를 자체 종결시킨다"(RVW 행 0개인 ACCEPTED 3행)와 "`loadOrThrow` 에 job 상태 판정이 없다"를 각각 확인했다.
- **영향**: 증강 산출물 무단 유실(요청 1건이 파생 0건으로 끝남) + 운영 가시성 결손. 보안 취약점은 아니나 **CWE-778(Insufficient Logging)** 성격의 관측 결손이며, 잘못된 `COMPLETED` 표시로 운영자가 재요청 시점을 놓친다. 게다가 ACCEPTED 는 `applyReviewStatus` 상 되돌릴 수 없어(TC-AUG-013 안내대로) **같은 (영상 × 종류) 재요청이 409 로 영구 차단**된다 — 복구 동선이 없다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**:
  ①`loadOrThrow` 에 "비종결 `LS_DATA_AUG_JOB` 존재 시 409(위탁 진행 중)" 게이트를 추가해 in-flight accept/reject 자체를 막거나,
  ②최소 조치로 `AugmentResultService` 의 `else` 분기 중 `outcome.success() && ACCEPTED && LS_DATA_AUG_RVW` 행이 존재(=사람이 먼저 종결)하는 경우를 **WARN 으로 승격**해 REJECTED 경로와 대칭을 맞춘다.
  ③또는 FE 잡카드에서 `status ∈ {REQUESTED, IN_PROGRESS}` 인 항목의 accept/reject 를 비활성화(현재 `resolutionTypes` 숨김과 같은 방식).

### [E-ISSUE-02] TC-AUG-033 — 실동선(콜백 종결) 증강의 `completedAt` 이 항상 `requestedAt` 으로 표시됨

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 잡카드가 `COMPLETED` 일 때 `completedAt` 은 실제 종결 시각을 나타내야 한다(카탈로그 기대값 `completedAt=max(RVW_DT)`). 요청 시각과 같은 값이 나오면 화면상 "요청 즉시 완료"로 보여 처리 소요시간·SLA·지연 탐지가 전부 무의미해진다.
- **현재 동작(이슈 내용)**: `AugmentReviewService.java:200-207`
  ```java
  LocalDateTime completedAt = null;
  if (status == AugmentJobStatus.COMPLETED) {
      completedAt = group.stream()
              .map(a -> reviewDtByAug.get(a.getDataAugSn()))
              .filter(java.util.Objects::nonNull)
              .max(Comparator.naturalOrder())
              .orElse(requestedAt); // 검수 일시 유실 시 요청 일시로 폴백(null 회피)
  }
  ```
  `reviewDtByAug` 는 `LS_DATA_AUG_RVW.RVW_DT` 에서만 채워진다(`loadReviewDates`, `:315-330`). 그런데 **정상 동선의 증강은 REVIEWER 가 아니라 성공 콜백이 종결시키므로 `LS_DATA_AUG_RVW` 행이 생성되지 않는다** — 검증 시작 시점 DB 실측에서 `ls_data_aug` 의 ACCEPTED 3행(`data_aug_sn=1,2,3`)에 대해 `ls_data_aug_rvw` 는 **0행**이었다. 결과적으로 `orElse(requestedAt)` 이 **예외가 아니라 기본 경로**가 된다.
  - 실측(2차, 목 실왕복 산물):
    - rawSn=126 잡카드 → `"requestedAt":"2026-07-31T02:54:36.675253", "completedAt":"2026-07-31T02:54:36.675253"` (완전 동일)
    - rawSn=136 잡카드 → `"requestedAt":"2026-07-31T03:44:25.589713", "completedAt":"2026-07-31T03:44:25.589713"` (완전 동일). 실제 콜백 종결은 03:44:33 경로 최소 8초 이후였다(mock 웹훅 로그).
  - 반대로 내가 합성 검수(REVIEWER accept/reject)를 넣은 그룹만 `completedAt`(`03:45:41.409933`) ≠ `requestedAt` 으로 정상 산출됐다 → **폴백이 정상 동선을 덮고 있음이 A/B 로 확인**됨.
- **재현/확인 경로**:
  ```bash
  # 증강 요청 → 콜백 완료 대기 → 잡카드 조회
  curl -s -H "Authorization: Bearer $RT" "http://localhost:18081/api/v1/augments?page=0&size=20"
  #  → 해당 그룹의 requestedAt 과 completedAt 이 동일한지 확인
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select a.data_aug_sn, a.aug_proc_stts_cd, r.rvw_dt from ls_data_aug a
        left join ls_data_aug_rvw r on r.data_aug_sn=a.data_aug_sn;"
  #  → 콜백 종결분은 rvw_dt 가 NULL
  ```
- **영향**: 증강 이력 화면(SCR-AUG-002)의 완료 일시가 **전 건 오표시**. 외부 위탁 소요시간을 화면·통계 어디서도 알 수 없고, 장기 지연 위탁을 완료 건과 구분할 수 없다. 데이터 유실/보안 문제는 아니며 표시·운영 지표 결함이다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 종결 시각의 진실원을 `LS_DATA_AUG_RVW.RVW_DT` 단일에 두지 말고, ①콜백 종결 시에도 `LS_DATA_AUG` 에 종결 시각을 적재하거나(현재 `DCSN_DT` 컬럼이 존재하나 어느 경로에서도 채워지지 않는다 — `applyReviewStatus` 는 상태만 바꾼다), ②`LS_DATA_AUG_JOB.MDFCN_DT`(SUCCEEDED 전이 시각)를 폴백 순위 1로 올리고 `requestedAt` 폴백은 최후로 내린다. 어느 쪽이든 "폴백이 기본 경로"인 현 구조를 깨는 것이 요지다.

### [E-ISSUE-03] TC-AUG-009 — 카탈로그 근거 라인 드리프트 (`AugmentRequestBridge.java:29-35,92-96`)

- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 근거 `file:line` 이 판정 대상 코드(리스너 선언·롤업 가드)를 직접 가리켜야 검증자가 즉시 대조할 수 있다.
- **현재 동작(이슈 내용)**: 카탈로그가 가리킨 `:29-35` 와 `:92-96` 은 모두 **javadoc 서술 구간**이다(`:29-35` = "request_id 발급 원장은 LS_DATA_AUG_JOB.IDMP_KEY 다" 절, `:92-96` = 메서드 javadoc). 실제 판정 대상은:
  ```java
  100:    @Async("batchAsyncExecutor")
  101:    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  102:    public void onAugmentRequested(AugmentRequestedItemEvent event) {
  103:        submitAndRollUpIfNothingSent(event);
  ```
  이며, "위탁 0건 → 실패 롤업" 가드는 `:120-138`(`rollUpFailureIfNothingInFlight`)이다. 파일이 Phase 7~8 주석 대폭 보강으로 밀린 것으로 보인다.
- **재현/확인 경로**: `grep -n "onAugmentRequested\|@Async\|TransactionalEventListener" backend/src/main/java/kr/co/cudo/authoring/augment/listener/AugmentRequestBridge.java`
- **영향**: 판정 결과에는 영향 없음(동일 파일 · 동일 클래스). 카탈로그 신뢰도·검증 속도만 저하.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 카탈로그 TC-AUG-009 근거를 `AugmentRequestBridge.java:100-103,120-138` 로 갱신.

---

### 참고 — 결함으로 보고하지 않은 관찰 (확정 정책·픽스처 산물)

| 관찰 | 왜 결함이 아닌가 |
|---|---|
| 신고 구간(`'F'`)에 요청은 412 로 거부되지만 콜백 인계는 계속 진행 | **확정 정책**(2026-07-29) — 파생 생성은 원본 신고와 무관, 차단 대상은 외부 위탁(요청 입구 + 전송 진입점)뿐. 실측에서도 `AugmentApplyResult.WITHHELD_*` 흔적·PENDING 고착 0건 확인 |
| 해상도 파생(130·131 계열 `RESL_*`)이 accept/reject 400 으로 막히면서 `GET /v1/augments`·집계에는 포함 | **확정 정책** — 내부 생성물이라 검수 대상 아님 / 통계 반영은 운영 결정. 실측 일치 |
| `LS_DATA_AUG.DCSN_USER_NO`·`DCSN_DT` 가 accept/reject 후에도 NULL | 코드 주석(`LsDataAug.java:262`)이 "상세 audit 컬럼은 `LS_DATA_AUG_RVW` 에서 관리"로 **명시 위임**. 응답 `decisionUserNo`/`decisionAt` 은 RVW 행에서 채워짐(실측 `1001`/타임스탬프) |
| 목록 응답에 동일 `jobId`(=RAW_SN) 잡카드 2장이 나온 사례 | **내 합성 픽스처 산물** — 실제 요청 경로는 항상 대표프레임 `MIN(SRC_SN)` 만 쓰므로(`findFirstSrcSn`) 한 RAW_SN 당 SRC_SN 그룹이 1개다. 정리 후 재현되지 않음 |
| 만료 스윕이 검증 중 내 합성 PENDING 행을 REJECTED 로 회수 | 정상 동작 — `AugmentJobExpiryTxService` 가 `job 0건 · 깨울 주체 없음` 고아 PENDING 을 회수(`reclaimed orphan pending augments count=3`). 신고 구간 제외 술어가 제거된 현 정책과 일치 |
