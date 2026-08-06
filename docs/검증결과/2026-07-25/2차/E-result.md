# E 클러스터 (증강/해상도/Export/메타) — 2차 검증 결과

> 247건 · 기준 실동작(HEAD ca3c712b + mock genai 실왕복 + export 실산출물) · 2026-07-31


---

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

---

# E 클러스터 part2 (E-3·E-3B) 2차 검증 결과

> 대상: `docs/test-cases/E-augment-resolution-export-meta.md`
> §E-3 증강 결과 웹훅 `POST /v1/genai/callback` (29행) · §E-3B 증강 외부 위탁 (22행) = **51행**
> 환경: 로컬 도커 스택 · backend `localhost:18081`(HEAD `ca3c712b`) · mock `:9400` · PG `:5432`(스키마 `public`)
> 검증 일시: 2026-07-31 03:53 ~ 04:05 KST · backend 재기동 0회

## 집계

| 구분 | 행 수 | 폐기(집계 제외) | 검증 | PASS | PARTIAL | FAIL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| E-3 (증강 웹훅) | 29 | 5 | 24 | 23 | 1 | 0 | 0 | 0 | 0 |
| E-3B (외부 위탁) | 22 | 0 | 22 | 21 | 1 | 0 | 0 | 0 | 0 |
| **합계** | **51** | **5** | **46** | **44** | **2** | **0** | **0** | **0** | **0** |

> 폐기 5건(`~~취소선~~`): TC-AUG-054(augType 대조 제거) · 056(신고 'F' 게이트 폐기 — ★확정 정책) ·
> 058(`raw_file_path_nm` 필드 삭제) · 059(`AugmentResultRequest` 제거) · 062(동상). 집계 제외.

**신규 이슈 5건** — HIGH 1 / MEDIUM 1 / LOW 3.
실동작 판정 비율: E-3 24건 중 **20건**, E-3B 22건 중 **9건**(나머지는 250장 분할·4xx 강제·noop 모드 등
현 형상에서 물리적으로 재현 불가 → `[정적]`+테스트 커버).

### 이번 회차 핵심

1. **1차 CRITICAL 4건(E-ISSUE-01/04/05/32)은 전부 해소**됐다. 특히 1차 FAIL 이던 **TC-AUG-053(동시/오배송
   UNIQUE 흡수)** 은 이번에 **PostgreSQL 실 DB 에서 409 로 정상 종결**(500 아님, 트랜잭션 오염 없음)을
   실측 확인했다 — 1차 위양성(Mockito 스텁으로 25P02 미재현)의 실체가 코드 수정으로 실제 닫혔다.
2. **외부 산출물 반입은 self-fill 이 아니다** — 파생 프레임 md5 가 mock 산출 파일과 **바이트 동일**,
   파생 비디오 md5 가 부모 비식별 영상과 **바이트 동일**(경로는 `LS_DEIDENT_PROC_LOG` 값 읽기).
3. **신규 HIGH `E-ISSUE-21`** — 벤더 산출 경로의 **읽기 허용 루트에 우리 원본(비-비식별) 저장소가 포함**돼,
   콜백 하나로 **원본 PII 프레임이 파생영상의 "비식별 프레임"(`DE_IDNTF_YN='Y'`)으로 반입**된다.
   실증: rawSn **167** 의 비식별 프레임 md5 = rawSn 152 **원본 프레임** md5(`fae1cc00…`).
   G-ISSUE-44(checksum 미검증)와 결합해 내용 진정성 방어가 0이다.

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 실측 | 판정 |
|---|---|---|---|
| **E-ISSUE-01** (CRITICAL) | HMAC 웹훅 필터 경로 변형 우회 | 신 계약은 무서명 3계층(IP allowlist → rate limit·size cap → **request_id 발급 게이트**)으로 전면 교체. `WebhookGateInterceptor` 가 "필터 통과 증거" 재확인. A-part2 가 경로 변형 25종 전건 401 실측 | **✅ 해소(구조 교체)** |
| **E-ISSUE-04** (CRITICAL) | 시크릿 빈 값 → 정상 콜백 전건 401 | `WEBHOOK_HMAC_SECRET_AUGMENT` 는 존재하나 `SIGNATURE_REQUIRED = List.of()` 라 서명 요구 경로 0개. genai 콜백은 계약상 무서명이며 **정상 콜백이 200 으로 완주**(실측 rawSn 159·172·175 생성) | **✅ 무효화(전제 소멸)** |
| **E-ISSUE-05** (CRITICAL) | UNIQUE 위반 후 멱등 흡수가 PG 에서 500 | **쓰기 이전 선점 검사**(`requireJobIdNotOwnedByOtherAug`)로 회피. 실측: 다른 증강(aug 30)이 보유한 `job_id` 로 aug 39 롤업 → **409 `이미 다른 증강 결과에 인계된 작업 ID 입니다.`**, job 10 `RECEIVED` 유지 · aug 39 `PENDING` 유지(트랜잭션 롤백 정상) | **✅ 해소(실동작)** |
| **E-ISSUE-32** (MEDIUM) | `markDeadLetter()` 프로덕션 호출자 0건 | `AugmentResultService.markProcessingFailure(:317-320)` 가 `incrementRetryCount()+markDeadLetter()` 를 실패 인계 단일 깔때기에서 호출. 실측 dead-letter 기록 **5건**(aug 35·38·50·52·39·53·56) | **✅ 해소(실동작)** |
| TC-AUG-053 (1차 FAIL) | 동시/오배송 UNIQUE 흡수 실패 | 위 E-ISSUE-05 + **동시 콜백 4발 병렬** 실측(1×`applied:true` + 3×`applied:false`, 파생영상 **정확히 1건**=rawSn 172) | **✅ 해소(실동작)** |
| — (신규) | `markAccepted()` 프로덕션 호출자 0건 | 같은 dead-code 형태가 **`AugmentJobRecorder.markAccepted`(:60-62)** 에 재발 → `E-ISSUE-23` | ⚠ 신규 |

---

## ★ 콜백 이상 시나리오 실측

> 모두 `POST http://localhost:18081/api/v1/genai/callback` 실호출. 동시 검증 중인 타 에이전트와 구분하려고
> 요청 ID 를 전부 내가 발급받은 `AUG-…` 청크 키 또는 `e3-` 접두 위조값으로 썼다.

| # | 시나리오 | 요청 | 기대 | 실측 | DB 결과 |
|---|---|---|---|---|---|
| 1 | 위조 request_id | `request_id=e3-forged-0001` | 401 | **401** `발급되지 않은 request_id 입니다.` + 필터 `downstream auth rejected … status=401`(rate limit 집계) | 변화 0 |
| 2 | 미지 필드 추가 | `brand_new_field`,`another` | 수신 성공 | **401**(= 역직렬화 통과 후 발급 게이트에서 거부 → 미지 필드 무시 확인) | 변화 0 |
| 3 | status 화이트리스트 | `CANCELED` / `succeeded` | 400 | **400** `status 는 RUNNING\|SUCCEEDED\|FAILED 중 하나여야 합니다.` | 변화 0 |
| 4 | request_id 형식·길이 | `"e3 bad!"` / 129자 | 400 | **400** 패턴 위반 / `size must be between 0 and 128` | 변화 0 |
| 5 | job_id 형식·길이 | `job/../x` / 201자 | 400 | **400** 패턴 위반 / `size must be between 0 and 200` | 변화 0 |
| 6 | 필수 누락 | `{}` / 전부 blank | 400 | **400** `requestId/jobId/status: must not be blank` | 변화 0 |
| 7 | results 101건 | 101개 배열 | 400 | **400** `results 는 100건을 초과할 수 없습니다.` | 변화 0 |
| 8 | media_type 위반 | `AUDIO` | 400 | **400** `results[0].mediaType: media_type 은 IMAGE\|VIDEO 중 하나여야 합니다.` | 변화 0 |
| 9 | progress 범위 | `101` | 400 | **400** `must be less than or equal to 100` | 변화 0 |
| 10 | **job_id 오배송** | 발급 request_id + `job_id=e3-wrong-job-id` | 409 | **409** `job_id 가 일치하지 않습니다.` + WARN(expected/received 로그) | job 1 상태·mdfcn_dt 불변 |
| 11 | **replay(동일 콜백 2회)** | 종결 job 에 동일 SUCCEEDED | 200 `applied:false` | **200** `{"applied":false}` + `duplicate callback absorbed … state=SUCCEEDED` | 불변 |
| 12 | replay — FAILED 상태로 | 종결 job 에 FAILED | 200 `applied:false` | **200** 동일 | 불변 |
| 13 | **진행률 역전** | RUNNING 50 → RUNNING 10 | (계약상 무순서 허용) | **200 both, `applied:true`** — 순서 검증 없음(설계상 상태만 갱신) | `JOB_STTS_CD=RUNNING` |
| 14 | SUCCEEDED + results 없음/빈배열 | `results` 누락 / `[]` | 400 | **400** `SUCCEEDED 콜백에는 results 가 필요합니다.` + WARN + metric | job 상태 불변(`RUNNING` 유지) |
| 15 | output 경로 밖 | `/etc/passwd` | 400 | **400** `output_file_path 가 허용된 저장 경로가 아닙니다.` + WARN(경로 원문 미노출) | job 상태 불변 |
| 16 | output 경로순회 | `/app/genai-out/../etc/passwd` | 400 | **400** 동일 | 불변 |
| 17 | **위탁 3건 vs 수신 1건** | job 9(tot_nocs=3)에 results 1건 | job FAILED + 실패 롤업 | **200 `applied:true`**, job 9 `FAILED/RESULT_COUNT_MISMATCH` "위탁 3건 대비 수신 1건" | aug 38 `REJECTED`, `RTRY_NMTM=1`, `DEAD_LETTER_AT` 기록, 신규 영상 0 |
| 18 | **FAILED 상태 콜백** | `error_code=MODEL_EXECUTION_FAILED`, message 에 개행 포함 | 실패 롤업 | **200**, job 14 FAILED, 로그 `message=vendor side failure_line2`(CR/LF sanitize) | aug 50 `REJECTED`+dead-letter, 신규 영상 0 |
| 19 | **부모 비식별 미완료('N')** | 콜백 직전 부모 152 를 `'N'` 로 주입(직후 원복) | REJECTED+dead-letter | **200**, WARN `result failed — parent unavailable … reason=parent has no deident artifact` | aug 53 `REJECTED`+dead-letter, 신규 영상 0 |
| 20 | **부모 신고 구간('F')** | 콜백 직전 부모 152 를 `'F'` 로 주입(직후 원복) | **파생 생성**(★확정 정책) | **200 `applied:true`** | aug 55 `ACCEPTED`, **파생 rawSn 175 생성** — 정책대로 |
| 21 | **동시 콜백 4발 병렬** | 동일 SUCCEEDED × 4 (job 13) | 롤업 1회 | **200×4** — `applied:true` 1건 + `applied:false` 3건 | aug 49 `ACCEPTED`, 파생 **rawSn 172 단 1건** |
| 22 | **오배송(타 증강 보유 job_id)** | job 10 의 `otsd_job_id` 를 aug 30 소유값으로 주입 후 콜백 | 409(쓰기 이전) | **409** `이미 다른 증강 결과에 인계된 작업 ID 입니다.` + WARN `ownerDataAugSn=30` | job 10 `RECEIVED` · aug 39 `PENDING` (**500 아님, tx 오염 없음**) |
| 23 | **REJECTED 증강에 성공 결과 도착** | aug 37 반려 후 SUCCEEDED 콜백 | 200 `applied:false` + WARN | **200 `applied:false`** + WARN `success result discarded — aug already terminal(REJECTED) dataAugSn=37 …` | aug 37 `REJECTED` 유지, `otsd_job_id` 미적재 |
| 24 | ACK 미수신 job 에 임의 job_id | job 4(`otsd_job_id` null)에 `job_id=anything-goes` | — | **200 `applied:false`** — 오배송 검사가 **스킵**됨(`externalJobId != null` 가드) | 불변 → `E-ISSUE-24` |
| 25 | **만료 스윕(비종결 job)** | job 16 의 `mdfcn_dt` 를 10h 전으로 주입 | 조건부 UPDATE 클레임 → EXPIRED | 04:03:31 tick: `job expired (non-terminal reclaimed) augJobSn=16 dataAugSn=52 rollup=APPLIED`, `count=1 candidates=1` | job 16 `FAILED/EXPIRED`, aug 52 `REJECTED`+dead-letter |
| 26 | **고아 PENDING 스윕(job 0건)** | aug 39 의 job 행 삭제 + `reg_dt` 10h 전 주입 | REJECTED+dead-letter | 동 tick: `orphan pending augment reclaimed (job 0건 · 깨울 주체 없음) dataAugSn=39 result=APPLIED` | aug 39 `REJECTED`+dead-letter |
| 27 | 위탁 0건(비식별 경로 부재) | rawSn 20012(deid 경로 없음) 증강 요청 | 위탁 0 + 즉시 실패 롤업 | mock job 수 **20→20**(HTTP 0건), `위탁 거부 — 비식별 프레임 경로 부재 … missingCount=1` → `no job accepted — rolled up to REJECTED` | job 19 `FAILED/DEID_PATH_MISSING`, aug 56 `REJECTED`+dead-letter |

**정상 완주 기준선(TC-AUG-050)**: rawSn 146 RAIN 요청 → mock `job accepted … inputs=3` → webhook 10/50/90/100
→ `job succeeded outputCount=3` → **파생 rawSn 159**(`ORGNL_RAW_SN=146`, `RAW_FILE_PATH_NM=/app/storage/deidentified/videos/augment/146/159/RAIN.mp4`)
→ `ExtractA plan ready (external outputs)` → `ExtractB deid video copied` → `ingested frames=3 (external outputs)`
→ `ExtractC persisted frames=3 labels=13 metas=21 metaReviews=15`.

---

## ★ checksum 미검증 파급 실측 (G-ISSUE-44 의 E 클러스터 확증)

> G-part3 가 "BE 가 `results[].checksum` 을 선언만 하고 검증 0건"을 정적으로 확인했다. E-3 에서 **실제 파급**을 재현했다.

| 실험 | 투입 | 결과 |
|---|---|---|
| **A. 타 작업 산출물 치환(형식 불일치)** | job 6(aug 35, 부모 152, 1프레임)에 `output_file_path=/app/storage/raw/seed/146/deid/sample-cctv-1080p-mask.mp4`(다른 영상의 **MP4**), checksum **미첨부** | **200 `applied:true`** → job 6 SUCCEEDED → aug 35 ACCEPTED → 파생 rawSn 165 생성. Phase B 의 **해상도 실측 3중 검증에서 거부** → rawSn 165 `FAILED`, aug 35 dead-letter. **경로/내용 진정성이 아니라 "이미지로 읽히는가" 만이 최종 방어선**이다 |
| **B. 원본(비-비식별) 프레임 치환(형식 일치)** | job 7(aug 36, 부모 152)에 `output_file_path=/app/storage/raw/frames/raw/152/frame-0.jpg`(**마스킹 전 원본**), `checksum="0000…0000"`(명백한 위조값) | **200 `applied:true`** → 전 단계 통과 → **파생 rawSn 167 생성, `DATA_STTS_CD=COMPLETED`, `DE_IDNTF_YN='Y'`**. `LS_DATA_SRC(167).DE_IDNTF_SRC_FILE_PATH_NM = /app/storage/deidentified/frames/deid/167/frame-0.jpg` 의 실제 내용 md5 = **`fae1cc00a40111d8869d4fecc200dd5c` = rawSn 152 의 원본 프레임 md5**(비식별본은 `e0df599d…` 로 상이) → **`E-ISSUE-21`** |
| **C. checksum 대조 여부** | 위 B 의 위조 checksum | 어떤 로그·메트릭·상태 변화도 없음. `grep -n "checksum" GenAiCallbackService.java AugmentResultService.java AugmentFrameProducer.java` → **소비 지점 0건**(DTO 선언 `GenAiCallbackRequest.java:114-116` 뿐) → **`E-ISSUE-22`** |
| **D. 순서 의존 짝짓기의 실제 성립성** | `LS_DATA_AUG_JOB_FILE`(FILE_SEQ 오름차순) ↔ `results[]` 순서 대응 | 실측: job 5 의 3개 파일이 `001_frame-0/002_frame-1/003_frame-2` 순으로 되붙음. **방어는 "건수 일치" 하나뿐**(TC-AUG-071). 건수가 같고 순서만 뒤바뀐 페이로드는 **검출 수단이 전혀 없다** — 프레임 N 에 프레임 M 의 증강본이 붙어도 라벨 좌표는 그대로 복사되므로 학습데이터가 조용히 오손된다 |

> ⚠ 실험 B 의 파생 rawSn 167 은 **의도적으로 만든 오염 데이터**다. 후속 회차에서 마트뷰/export 검증에
> 쓰이지 않도록 주의(부모 152, `AUG_TYPE_CD=NIGHT`, `data_aug_sn=36`).

---

## E-3 결과표 (증강 결과 웹훅 — 29행 / 검증 24)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---|---|
| TC-AUG-050 | 전 job SUCCEEDED 롤업 → 신규 증강영상 생성 | PASS | [실동작] rawSn 146 RAIN 실왕복 완주 → 파생 **rawSn 159**(`ORGNL_RAW_SN=146`, 동기 커밋 시 `dataStts=PENDING` 로그, `RAW_FILE_PATH_NM=…/videos/augment/146/159/RAIN.mp4`), AFTER_COMMIT `AsyncAugmentFrameRunner` 기동 로그. [정적] `GenAiCallbackService.java:97-164`·`AugmentJobRollup.java:64-70`·`AugmentResultService.java:405-436` [테스트] `GenAiCallbackServiceTest:233`·`AugmentResultServiceTest:339` | 근거 1줄 드리프트(96→97) |
| TC-AUG-051 | 실패 롤업 REJECTED·영상 미생성 + dead-letter | PASS | [실동작] 시나리오 18 — job 14 FAILED → aug 50 `REJECTED`/`RTRY_NMTM=1`/`DEAD_LETTER_AT=04:01:42`, 신규 영상 0. [정적] `AugmentJobRollup.java:72-77`·`AugmentResultService.java:226-232,317-320` [테스트] `AugmentResultServiceTest:161` | — |
| TC-AUG-052 | 재전송 멱등(1차 앵커) non-PENDING skip | PASS | [실동작] 시나리오 11·23 — 200 `{applied:false}`, 중복 영상 0. [정적] `AugmentResultService.java:186-202` [테스트] `AugmentResultServiceTest:180,224` | `AugmentApplyResult.DUPLICATE` 실제 라인 23(카탈로그 22) |
| TC-AUG-053 | otsd_job_id 선점 — 재수신 200 / 타 증강 409 | PASS | [실동작] ① 시나리오 11 → 200 `applied:false` ② 시나리오 22 → **409**, job/aug 상태 불변(**500·25P02 미발생**). [정적] `AugmentResultService.java:204-207,267-302` [테스트] `AugmentResultServiceTest:256,282` | **1차 FAIL 해소 확증** |
| ~~TC-AUG-054~~ | ~~augType 불일치 차단~~ | — | 폐기(2026-07-30) | 집계 제외 |
| TC-AUG-055 | 미발급 request_id 401 / aug 행 부재 404 | PASS | [실동작] ① `e3-forged-0001` → **401** + `HmacWebhookFilter … downstream auth rejected status=401`(rate limit 집계 실측). [정적] `GenAiCallbackService.java:100-111` [테스트] `GenAiCallbackServiceTest:136` | ② "job 은 있으나 aug 행 없음=404" 는 `fk_ldaj_data_aug ON DELETE CASCADE` 때문에 **스키마상 도달 불가한 방어 코드** — 실동작 재현 대상 아님 |
| ~~TC-AUG-056~~ | ~~부모 'F' PII 게이트~~ | — | 폐기(★확정 정책) | 시나리오 20 이 정책 준수(파생 생성)를 **역으로 실증** |
| TC-AUG-057 | 부모 프레임 0건 = 실패 확정 | PASS | [정적] `AugmentResultService.java:389-392`(`parent has no frames` → FAIL) + `:213-219`(FAIL→REJECTED+dead-letter). 동일 `evaluateParentGate` 의 형제 분기(`parent has no deident artifact`)는 **시나리오 19 로 실동작 확인**. [테스트] `AugmentResultServiceTest:611` | 정상 흐름에서는 위탁측(`DEID_PATH_MISSING`)이 먼저 막아 콜백 도달 불가 = 다층 방어 |
| ~~TC-AUG-058~~ | ~~rawFilePathNm SSRF~~ | — | 폐기 | — |
| ~~TC-AUG-059~~ | ~~augTypeCd 화이트리스트~~ | — | 폐기 | — |
| TC-AUG-060 | status 화이트리스트 | PASS | [실동작] 시나리오 3 — `CANCELED`·`succeeded` 모두 400. [정적] `GenAiCallbackRequest.java:60-64` | — |
| TC-AUG-061 | request_id / job_id 패턴·길이 | PASS | [실동작] 시나리오 4·5 — 패턴 위반·129자·201자 전건 400. [정적] `GenAiCallbackRequest.java:44-58` | — |
| ~~TC-AUG-062~~ | ~~dataAugSn null·rawFilePathNm>1000~~ | — | 폐기 | — |
| TC-AUG-063 | 동시 콜백 직렬화(FOR UPDATE) | PASS | [실동작] 시나리오 21 — 4발 병렬 → `applied:true` 1 + `false` 3, 파생영상 정확히 1건(rawSn 172). [정적] `GenAiCallbackService.java:108-114`(job 갱신 **전** aug 잠금)·`AugmentResultService.java:182-184` [테스트] `GenAiCallbackRollupConcurrencyIT:119` | — |
| TC-AUG-064 | 부모 비식별 미완('N') = 실패 확정 | PASS | [실동작] 시나리오 19 — WARN `parent has no deident artifact`, aug 53 REJECTED+dead-letter, 영상 0건. [정적] `AugmentResultService.java:375-393`(`LsDataRaw.hasDeidentArtifact()` 단일 헬퍼) [테스트] `AugmentResultServiceTest:531` | — |
| TC-AUG-065 | IP allowlist 미설정 = 전면 차단 | PASS | [정적] `GenAiWebhookIpAllowlist.java:46-47,63-66` — `isNone()`(빈값·`none`) → `allowed=List.of()` → `isAllowed()` 무조건 false. 배선 `HmacWebhookFilter.java:487`. [실동작] 부팅 로그 `생성형 AI 콜백 IP allowlist 활성 count=1`(현 형상 `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0` = 전면 허용 **명시**) | 403 분기 실동작 재현은 설정 변경→**backend 재기동 필요(금지 규칙)** 이라 미수행 |
| TC-AUG-066 | 위탁↔수신 배선 짝 기동 가드 | PASS | [실동작] 부팅 로그 `[GenAi] 위탁(http) ↔ 콜백 IP allowlist 짝 확인 완료`(통과 분기 실증). [정적] `GenAiIntegrationWiringGuard.java:65-76`(`IllegalStateException` fail 분기) | fail 분기는 순수함수 `verify()` 라 단위 검증 대상 |
| TC-AUG-067 | job_id 오배송 차단 | PASS | [실동작] 시나리오 10 — **409** `job_id 가 일치하지 않습니다.`, 상태 미변경, WARN 에 expected/received 기록. [정적] `GenAiCallbackService.java:121-126` [테스트] `GenAiCallbackServiceTest:326` | ⚠ ACK 미수신 job 은 스킵 → `E-ISSUE-24` |
| TC-AUG-068 | RUNNING 진행 갱신 = 롤업 없음 | PASS | [실동작] 시나리오 13 — `JOB_STTS_CD=RUNNING`, 200 `applied:true`, 롤업 로그 0. 실왕복에서도 10/50/90 3회 관측. [정적] `GenAiCallbackService.java:136-142` | 진행률 **순서 검증 없음**(설계상 상태만 반영) |
| TC-AUG-069 | output_file_path 허용 밖 = 400·상태 미변경 | PASS | [실동작] 시나리오 15·16 — 400 `output_file_path 가 허용된 저장 경로가 아닙니다.`(경로 원문 미노출), job 상태 `RUNNING` 유지 = **재전송 여지 보존**, WARN 기록. [정적] `GenAiCallbackService.java:214-237`·`VideoArtifactRootResolver.java:373-374` [테스트] `GenAiCallbackServiceTest:286` | 메트릭 `augment.callback.rejected{reason=output_path}` 는 코드 확인(엔드포인트 미노출로 값 미확인) |
| TC-AUG-070 | 읽기 루트 기본 빈값 = 쓰기축과 동일 fail-closed | **PARTIAL** | [실동작] 읽기 루트 = **쓰기 allowlist ∪ external-read-roots** 동작 확인 — `/app/storage/raw/…`(쓰기 allowlist) 경로가 **200 수용**됨. [정적] `VideoArtifactRootResolver.java:159-177` | 합집합 동작 자체는 기대와 일치하나, **쓰기 allowlist 에 원본(비-비식별) 저장소가 들어 있어** "쓰기 base 를 넓히지 않고 PII 격리 축을 지킨다"는 설계 목표가 **읽기 축에서 무너진다** → `E-ISSUE-21` |
| TC-AUG-071 | 위탁 건수 ↔ 수신 건수 불일치 = job FAILED | PASS | [실동작] 시나리오 17 — job 9 `FAILED/RESULT_COUNT_MISMATCH` "위탁 3건 대비 수신 1건" → aug 38 REJECTED+dead-letter, 영상 0건. [정적] `GenAiCallbackService.java:175-193`·`LsDataAugJob.java:74` [테스트] `GenAiCallbackServiceTest:200` | 순서 뒤바뀜은 이 방어로 검출 불가 → `E-ISSUE-22` |
| TC-AUG-072 | SUCCEEDED 인데 results 없음 = 400 | PASS | [실동작] 시나리오 14 — 누락·빈배열 모두 400 `SUCCEEDED 콜백에는 results 가 필요합니다.` + WARN `succeeded without results`. [정적] `GenAiCallbackService.java:214-221` [테스트] `GenAiCallbackServiceTest:311` | — |
| TC-AUG-073 | 롤업 보류 = applied:false | PASS | [실동작] 위탁 시퀀스 종료 롤업에서 `rollup deferred dataAugSn=30 pendingJobSeqs=[1]` 관측(DEFERRED 반환 실증). [정적] `GenAiCallbackService.java:155-163`(롤업 결과 그대로 회신) + `AugmentApplyResult.java:26,29-30`(`applied()` = APPLIED 만) [테스트] `GenAiCallbackServiceTest:346,364` | 웹훅 응답 `applied:false` 의 2청크 실동작 재현은 **불가** — 최대 프레임 12장 < 청크 상한 100, `max-input-files` 변경은 재기동 필요 |
| TC-AUG-074 | 비종결 job 만료 스윕 회수 | PASS | [실동작] 시나리오 25 — 04:03:31 tick 에서 `job expired (non-terminal reclaimed) augJobSn=16 … rollup=APPLIED`, `count=1 candidates=1 idleTimeoutMinutes=360`. job 16 `FAILED/EXPIRED` + 사유문 적재. [정적] `AugmentJobExpirySweeper.java:206-232`·`AugmentJobExpiryTxService.java:63-88`(`claimExpired` 조건부 UPDATE) | 부팅 로그 `sweep scheduled intervalMs=900000 idleTimeoutMinutes=360` |
| TC-AUG-075 | job 0건 고아 PENDING 증강 회수 | PASS | [실동작] 시나리오 26(내 주입) + **03:48:31 tick 의 타 에이전트 데이터 자연 회수 3건**(`dataAugSn=12,14,15`). 신고 구간 제외 술어 없음도 코드 확인. [정적] `AugmentJobExpirySweeper.java:174-198`·`AugmentJobExpiryTxService.java:106-122` | — |
| TC-AUG-076 | 필수 필드 누락 400 | PASS | [실동작] 시나리오 6·7 — blank 3종 400, results 101건 400. [정적] `GenAiCallbackRequest.java:44-83` | — |
| TC-AUG-077 | 미지 필드 무시 | PASS | [실동작] 시나리오 2 — 미지 필드 2개 포함 페이로드가 **역직렬화·검증 통과**(400 아님) 후 발급 게이트 401 도달. [정적] `GenAiCallbackRequest.java:41,95` | — |
| TC-AUG-078 | 강등된 PENDING 의 성공 콜백 폐기 관측 | PASS | [실동작] 시나리오 23 — 반려 후 성공 콜백 → 200 `applied:false` + **WARN `success result discarded — aug already terminal(REJECTED) dataAugSn=37 otsdJobId=… (요청 중 강등/반려 추정 — 필요 시 재요청)`** 문구 그대로. [정적] `AugmentResultService.java:188-196` | 계약 문구 완전 일치 |

---

## E-3B 결과표 (증강 외부 위탁 — 22행)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---|---|
| TC-AUG-100 | 100장 청크 분할 위탁 | PASS | [정적] `AugmentJobSubmitService.java:141`(`CONTRACT_MAX_INPUT_FILES=100`)·`:180-185`(`clampChunkSize` — 설정이 100 초과면 100 으로 clamp)·`:504-510`(`partition`)·`:516-518`(`chunkRequestId = {augIdmpKey}-{jobSeq}`). 설정 `application.yml:276 max-input-files: ${AUGMENT_MAX_INPUT_FILES:100}` [테스트] `AugmentJobSubmitServiceTest:104`(250장→100/100/50)·`:269`(clamp) [실동작] 청크키 형식 `AUG-{uuid}-1` 실측(job 1~19 전건) | **근거 드리프트** — 카탈로그 `:112,143-148,385-399` ↔ 실제 `:141,180-185,504-518`. 실동작 분할 재현은 최대 12프레임이라 불가 |
| TC-AUG-101 | job_id 는 외부가 발급 | **PARTIAL** | [실동작] mock 202 의 `job_id` 가 `LS_DATA_AUG_JOB.OTSD_JOB_ID` 에 적재됨(job 1·5·6~18 전건, 예: `bb65f8da…`). [정적] 3축 검증 `HttpExternalAugmentClient.java:164-181`(request_id echo / `status=RECEIVED` / job_id non-blank → `EXTERNAL_API_ERROR`) [테스트] `AugmentJobSubmitServiceTest:191` | 카탈로그가 지목한 **`markAccepted(외부 job_id)` 는 프로덕션 호출자 0건(dead code)** — 실제 경로는 `AugmentSubmitOutcomeRecorder.onAccepted → AugmentJobRecorder.markSubmitAccepted`(조건부 원자 UPDATE) → `E-ISSUE-23` |
| TC-AUG-102 | Idempotency-Key 헤더 | PASS | [실동작] mock 에 `Idempotency-Key: AUG-699af0c0-…-1` + **다른 request_id** 로 직접 제출 → mock 이 **기존 job `d6ead814…` 를 그대로 반환**(202, `request_id` 도 원본값) = BE 가 그 헤더를 실제로 보냈음을 역증명. [정적] `HttpExternalAugmentClient.java:58,114` | 근거 드리프트(`:57-58,99` → `:58,114`) |
| TC-AUG-103 | 4xx = 비재시도 | PASS | [정적] `HttpExternalAugmentClient.java:118`(`onStatus(is4xxClientError)`)·`:151-157`(`releaseBody()` 로 본문 소비·해제 후 `NonRetryableExternalException`, 상태코드만 로그 — 본문 원문 미노출 CWE-209). 연산자 순서상 Retry/CircuitBreaker 에서 제외됨 [테스트] `HttpExternalAugmentClientTest` | 실동작 4xx 강제 불가 — mock 이 400 을 내는 조건(경로 밖 입력·중복 sequence 등)을 BE 가 만들어낼 경로가 없음. 근거 드리프트(`:102-103,134-140`) |
| TC-AUG-104 | 전량 선기록 후 위탁 | PASS | [정적] `AugmentJobSubmitService.java:236-240`(`issueAllChunks` 실패 시 `SubmitOutcome.of(0)` 즉시 반환)·`:375-394`(실패 시 이미 선기록된 앞 청크를 `ERR_ISSUE_RECORD_FAILED` 로 종결) [테스트] `AugmentJobSubmitServiceTest:284,297,323,346` | 근거 드리프트(`:198-260` → `:236-240,375-394`) |
| TC-AUG-105 | 비식별 경로 부재 = 위탁 거부 | PASS | [실동작] 시나리오 27 — rawSn 20012 요청 시 **mock job 수 20→20(HTTP 0건)**, job 19 `FAILED/DEID_PATH_MISSING` "…missingCount=1", 원본 경로 폴백 0. 타 에이전트 생성 job 4(aug 28)도 동일 결과. [정적] `AugmentJobSubmitService.java:219-230,471-494` [테스트] `AugmentJobSubmitServiceTest:203,218,239` | 근거 드리프트(`:182-193,352-375`) |
| TC-AUG-106 | 위탁 전 신고 = 거부(보류 아님) | PASS | [정적] `AugmentJobSubmitService.java:208-217` — 프레임 경로 조회 **이전** `DeidentReportGate` 단일 원천 호출 → `recordRejected(ERR_DEID_REPORT_OPEN)` + `SubmitOutcome.of(0)`; `AugmentRequestBridge.java:124`(`requiresFailureRollup`) → 즉시 실패 롤업. `WITHHELD_*`·`onDeidentReportResolved` grep 0건(폐기 확인) [테스트] `AugmentJobSubmitServiceTest:372,391` | 실동작 단독 재현 불가 — 동일 `'F'` 신호를 요청 입구(`AugmentRequestService` 412)가 먼저 잡아 전송 게이트까지 도달시킬 방법이 없음(다층 방어) |
| TC-AUG-107 | 위탁 도중 신고 관측 = 남은 청크 중단 | PASS | [정적] `AugmentJobSubmitService.java:314-322`(`Mono.defer` 안에서 **청크마다 무잠금 재판정**, `index>0`)·`:406-415`(`abortRemainingChunks` → `ERR_DEIDENT_REPORT` terminal 종결)·`:268-286`(`concatMap` 직렬화 — 병렬 발사 금지) [테스트] `AugmentJobSubmitServiceTest:407` | 2청크 이상 구성 불가로 실동작 미재현. 근거 드리프트(`:207-219,262-281`) |
| TC-AUG-108 | 청크 위탁 실패 건별 격리 | PASS | [정적] `AugmentJobSubmitService.java:339-354`(`onErrorResume` → `onSubmitFailed` 기록 후 `Mono.empty()` = 다음 청크 계속) [테스트] `AugmentJobSubmitServiceTest:169` | 근거 드리프트(`:324-344`) |
| TC-AUG-109 | 위탁 0건 = 즉시 실패 롤업 | PASS | [실동작] 시나리오 27 — `[Augment] no job accepted — rolled up to REJECTED originAugSn=56` → aug 56 REJECTED + `RTRY_NMTM=1` + dead-letter. [정적] `AugmentJobSubmitService.java:444-453`(`requiresFailureRollup`)·`AugmentRequestBridge.java:124,130-149`(미종결 job 이 있으면 롤업 금지) [테스트] `AugmentRequestBridgeTest`·`AugmentJobSubmitServiceTest:437` | 근거 드리프트(`:304-314` → `:444-453`) |
| TC-AUG-110 | ★커넥션 풀 데드락 회귀 가드 | PASS | [정적] `AugmentJobSubmitService` 클래스·`submit()` 에 **트랜잭션 애너테이션 부재** 확인(`@Transactional` grep 0건), 클래스 javadoc `:82-134` 가 `readOnly`·`NOT_SUPPORTED` 양쪽 실측 근거 보존 [테스트] `AugmentRequestServiceTest:361-382` — `isSynchronizationActive()` + `getResource(controlEmf)` **두 축** 관측(카탈로그 지적대로 `isActualTransactionActive()` 미사용) [실동작] 04:01:05~06 에 **위탁 4건 연속**, 03:57:20 에 **7건 연속** — Hikari 타임아웃·`CannotCreateTransactionException` 0건 | 근거 드리프트(`:53-106,160-166` → `:82-134,207`) |
| TC-AUG-111 | evnt_type 폴백 | PASS | [정적] `AugmentJobSubmitService.java:144`(`EVNT_TYPE_FALLBACK="ETC"`)·`:497-502`(`resolveEventType` — null/blank 시 폴백) [테스트] `AugmentJobSubmitServiceTest:255` | 실동작 재현 불가 — `EVNT_TYPE_CD` 가 null 인 영상(150)은 프레임 0건이라 위탁 자체가 불가. 근거 드리프트(`:115,377-383`) |
| TC-AUG-112 | 위탁 로그에 절대경로 미출력 | PASS | [실동작] 위탁 로그 전건이 `originAugSn/augType/jobSeq/inputCount` 수준(`genai submit originAugSn=30 augType=RAIN jobSeq=1/1 inputCount=3`) — 파일 절대경로 0건. 개행 포함 외부 메시지가 `vendor side failure_line2` 로 sanitize 됨(시나리오 18). [정적] `HttpExternalAugmentClient.java:183-186`·`AugmentJobSubmitService.java:521-524`·`GenAiCallbackService.java:240-249` | 근거 드리프트(`:45,166-169`·`:401-405`) |
| TC-AUG-113 | `mode=noop` 시 위탁 미수행 | PASS | [정적] `HttpExternalAugmentClient.java:50-51`(`havingValue="http", matchIfMissing=true`) ↔ `NoopExternalAugmentClient.java:27`(`havingValue="noop"`) 상호배타. 만료 스윕은 자기 토글만 봄(`AugmentJobExpirySweeper.java:47-52,86`) [실동작] 현 형상은 `AUGMENT_EXTERNAL_MODE=http` + 부팅 로그 `active ExternalAugmentClient=HttpExternalAugmentClient` [테스트] `ExternalAugmentClientBeanConditionTest`·`NoopExternalAugmentClientTest` | noop 전환은 재기동 필요로 미검증 |
| TC-AUG-114 | 외부 산출 프레임 실반입 3중 검증 | PASS | [실동작] 실험 A — 다른 영상의 **MP4** 를 산출물로 위장 제출 → Phase B **거부** → rawSn 165 `FAILED`, `augment frame re-extraction failed … cause=CustomException`. [정적] `AugmentFrameProducer.java:123-199`(`verifyExternalReadablePath` 171 / `isRegularFile` 134 / 해상도 대조 154-158 → all-or-nothing) [테스트] `AugmentFrameProducerTest` | ⚠ **해상도가 같은 파일은 전부 통과** — 실험 B 로 원본 PII 프레임 반입 성립(`E-ISSUE-21`) |
| TC-AUG-115 | 부분 쓰기 방지(.part → atomic move) | PASS | [정적] `AugmentFrameProducer.java:55`(`PART_SUFFIX=".part"`)·`:224-238`(`copyAtomically` — `Files.newInputStream(src, NOFOLLOW_LINKS)` 로 열고 `Files.move(..., ATOMIC_MOVE)`) [실동작] 산출 디렉터리 `frames/deid/159|167|172|175` 에 `.part` 잔존 0건 | 중단 시나리오 자체는 미주입 |
| TC-AUG-116 | 파생 비디오 = 부모 비식별본 실제 복사 | PASS | [실동작] `md5 /app/storage/raw/seed/146/deid/sample-cctv-1080p-mask.mp4` = `md5 /app/storage/deidentified/videos/augment/146/159/RAIN.mp4` = **`1c2d2b2c39a5bae1d71ace03d4a83c69`**(34,654,319B). 소스 경로는 `LS_DEIDENT_PROC_LOG(146).DE_IDNTF_FILE_PATH_NM` 값 그대로 — **조합·추측 아님**(mock 형상인데 파일명이 `deidentified.mp4` 가 아니라 `…-mask.mp4` 임에도 정확히 찾음). [정적] `AugmentFrameProducer.java:107-119`·`AugmentExtractSnapshot.java:164-165,245-255` | 원본 폴백 0 |
| TC-AUG-117 | 파생 프레임은 비식별 컬럼에만 적재 | PASS | [실동작] `LS_DATA_SRC(159)` 3행 — `SRC_FILE_PATH_NM` **전부 null**, `DE_IDNTF_SRC_FILE_PATH_NM=/app/storage/deidentified/frames/deid/159/frame-{0,1,2}.jpg`(두 컬럼 동일값 0건). rawSn 167·172·175 도 동일. [정적] `AugmentExtractPersist.java:101-115` | — |
| TC-AUG-118 | 증강 라벨 복사는 좌표 그대로 | PASS | [실동작] `LS_DATA_AUG_LBL_MAP(data_aug_sn=30)` 13행 전건 `COORD_RECALC_YN='N'`, `SCALE_X/SCALE_Y` null, `ORGNL_DATA_LBL_SN`↔`DATA_LBL_SN` 매핑 존재. 로그 `ExtractC persisted … labels=13`. [정적] `AugmentExtractPersist.java:117-135,227-236` | — |
| TC-AUG-119 | async 추출 실패 = RAW FAILED + aug dead-letter | PASS | [실동작] 실험 A — `augment frame re-extraction failed rawSn=165 dataAugSn=35 cause=CustomException` → `ExtractC aug marked dead-letter after async extraction failure dataAugSn=35 status=ACCEPTED` → DB: rawSn 165 `DATA_STTS_CD=FAILED`·`DE_IDNTF_YN='N'`, aug 35 `RTRY_NMTM=1`·`DEAD_LETTER_AT` 기록, **`AUG_PROC_STTS_CD` 는 ACCEPTED 로 미변경**(멱등 앵커 무충돌 — 계약대로). [정적] `AsyncAugmentFrameRunner.java:133-159`·`AugmentExtractPersist.java:186-203` | 계약 3요소 모두 실동작 확인 |
| TC-AUG-120 | ffprobe 는 확정 성공 이후에만 | PASS | [정적] `AsyncAugmentFrameRunner.java:106-121` — 확정 블록 **밖** 별도 catch 에서 `AsyncVideoMetaRunner` 기동; `AugmentResultService.java:444-448` 주석이 콜백 시점 병렬 기동 금지를 명시하고 실제 트리거는 프레임 러너 1곳뿐 [실동작] 로그 순서 `ExtractC persisted` → 이후 메타 러너 [테스트] `AugmentResultServiceTest:579` | — |
| TC-AUG-121 | Phase C SKIPPED = cleanup 금지 | PASS | [정적] `AsyncAugmentFrameRunner.java:92-97` — `Result.SKIPPED` 시 cleanup·FAILED 전이 모두 skip(주석: 파일이 승자와 동일 경로) [테스트] `AsyncAugmentFrameRunnerTest` | 중복 트리거 레이스 실동작 재현 불가 |

---

## 근거 드리프트 / self-fill 점검

### 근거 file:line 드리프트 — **12건 (전부 E-3B)**

Phase C-3 논블로킹 제출 전환으로 `AugmentJobSubmitService`(555줄)·`HttpExternalAugmentClient`(188줄) 가
전면 재작성되면서 카탈로그 라인 참조가 무효화됐다. **E-3(웹훅 계열) 참조는 실질 정확**(1~2줄 오차만).

| TC | 카탈로그 근거 | 실제 위치 |
|---|---|---|
| TC-AUG-100 | `AugmentJobSubmitService.java:112,143-148,385-399` | `:141`(상한) · `:180-185`(clamp) · `:504-510`(partition) · `:516-518`(chunkRequestId) |
| TC-AUG-101 | `HttpExternalAugmentClient.java:90-164` | `:104-128`(requestAugment) · `:164-181`(validate 3축) |
| TC-AUG-102 | `HttpExternalAugmentClient.java:57-58,99` | `:58`(헤더 상수) · `:114`(헤더 세팅) |
| TC-AUG-103 | `HttpExternalAugmentClient.java:102-103,134-140` | `:118`(onStatus) · `:151-157`(toNonRetryable4xx) |
| TC-AUG-104 | `AugmentJobSubmitService.java:198-260` | `:236-240` · `:375-394`(issueAllChunks) |
| TC-AUG-105 | `AugmentJobSubmitService.java:182-193,352-375` | `:219-230` · `:471-494`(resolveDeidInputFiles) |
| TC-AUG-106 | `AugmentJobSubmitService.java:170-180` / `AugmentRequestBridge.java:116-118` | `:208-217` / `:124` |
| TC-AUG-107 | `AugmentJobSubmitService.java:207-219,262-281` | `:314-322`(재판정) · `:406-415`(abortRemaining) |
| TC-AUG-108 | `AugmentJobSubmitService.java:324-344` | `:339-354` |
| TC-AUG-109 | `AugmentJobSubmitService.java:304-314` / `AugmentRequestBridge.java:132-147` | `:444-453` / `:130-149` |
| TC-AUG-110 | `AugmentJobSubmitService.java:53-106,160-166` | `:82-134`(클래스 javadoc) · `:207`(submit) |
| TC-AUG-111 | `AugmentJobSubmitService.java:115,377-383` | `:144` · `:497-502` |
| TC-AUG-112 | `HttpExternalAugmentClient.java:45,166-169` / `AugmentJobSubmitService.java:401-405` | `:183-186` / `:521-524` |

경미(1~2줄): TC-AUG-050(`96`→`97`) · TC-AUG-052(`AugmentApplyResult:22`→`23`) · TC-AUG-065(`:34-73`→`:34-77`).

### self-fill 점검 — **위반 0건**

| 점검 항목 | 결과 | 근거 |
|---|---|---|
| 외부 응답 없이 산출물을 자체 생성하는가 | **없음** | 파생 프레임 md5 `064376b24753aaf61f950a89f66b0e96` = mock 산출 파일 `/app/genai-out/genai/bb65f8da…/001_frame-0_genai.jpg` 와 **바이트 동일**. 로그도 `ingested rawSn=159 frames=3 (external outputs)` |
| 파생 비디오를 임의 생성/재인코딩하는가 | **없음** | 부모 비식별 영상과 md5 동일(단순 복사) |
| 외부 실패 시 조용히 성공 처리(fail-open) 하는가 | **없음** | `results` 없는 SUCCEEDED → 400 / 건수 불일치 → job FAILED / 부모 미비식별 → REJECTED / 위탁 0건 → 즉시 REJECTED — 전부 실동작 확인 |
| mock 을 멈췄을 때 스스로 성공 처리하는가 | **없음** | mock job 을 CANCELED 로 동결한 job 6~18 은 **콜백을 내가 보내기 전까지 `RECEIVED` 로 그대로 정체**(자체 진행 0). 만료 스윕만이 회수 |
| 벤더 산출물의 **내용**을 검증하는가 | **하지 않음** | `checksum` 소비 지점 0건 → `E-ISSUE-22`(G-ISSUE-44 파급) |

### 검증 중 생성/변경된 데이터 (후속 회차 참고)

- 신규 증강 `LS_DATA_AUG`: 30·35·36·37·38·39·40·41·49·50·51·52·53·55·56
- 신규 `LS_DATA_AUG_JOB`: 5~19 (job 10 은 TC-AUG-075 검증을 위해 **삭제**)
- 신규 파생 `LS_DATA_RAW`: **159**(146/RAIN 정상) · **162**(156/WINTER) · **163**(153/RAIN) · **165**(152/WINTER, 의도적 FAILED)
  · **167**(152/NIGHT, ⚠ **원본 PII 프레임 오염 — 실험 B 산물**) · **172**(156/NIGHT) · **175**(152/RAIN, 신고 구간 생성 확인용)
- 일시 주입 후 원복: `ls_data_raw(152).de_ident_yn` `'N'`/`'F'` → **`'Y'` 복구 완료**
- 잔존 비종결: job 15(aug 51, `RECEIVED`) — 6시간 후 만료 스윕이 회수 예정

---

## 이슈 상세

### [E-ISSUE-21] TC-AUG-070 / 연관 TC-AUG-069·TC-AUG-114 — 벤더 산출 경로의 읽기 허용 루트에 **원본(비-비식별) 저장소**가 포함돼, 콜백 1건으로 원본 PII 프레임이 파생영상의 "비식별 프레임"으로 반입된다

- **심각도**: **HIGH**
- **기대 동작(기대효과)**: 벤더가 콜백으로 건네는 `results[].output_file_path` 는 **벤더 자신의 산출 트리**
  (`authoring.storage.external-read-roots`, 현 형상 `/app/genai-out`)만 가리킬 수 있어야 한다.
  `VideoArtifactRootResolver` 클래스 주석이 스스로 이 목적을 "쓰기 allowlist 에 추가하면 PII 격리 축
  (원본/비식별 산출 위치 통제)이 흐려진다" 로 명시하고 있다.
- **현재 동작(이슈 내용)**: 읽기 루트가 **쓰기 allowlist ∪ external-read-roots** 로 계산되는데,
  쓰기 allowlist 에 **원본 저장소**가 그대로 들어 있다.

  `common/storage/VideoArtifactRootResolver.java:159-161`
  ```java
  private static List<Path> buildReadableRoots(List<Path> writeRoots, String configured) {
      Set<Path> roots = new LinkedHashSet<>(writeRoots);   // ← 원본 저장소 포함
  ```
  런타임 형상: `STORAGE_RAW_MOUNT_ROOTS=/app/storage/raw,/app/storage/deidentified`,
  `STORAGE_EXTERNAL_READ_ROOTS=/app/genai-out` → 읽기 루트에 **`/app/storage/raw`**(마스킹 전 원본 프레임·영상)가 포함된다.

  `AugmentFrameProducer` 의 3중 검증(허용 루트 / 정규 파일·size>0 / **부모 비식별 프레임과 해상도 동일**)은
  원본 프레임을 걸러내지 못한다 — 원본과 비식별본은 **같은 영상에서 뽑은 같은 해상도**이기 때문이다.

  **실동작 재현 결과(실험 B)**: job 7(aug 36, 부모 rawSn 152)에
  `output_file_path=/app/storage/raw/frames/raw/152/frame-0.jpg`(마스킹 전 원본) + 위조 checksum 을 제출 →
  **200 `applied:true`** → 파생 **rawSn 167** 생성(`DATA_STTS_CD=COMPLETED`, `DE_IDNTF_YN='Y'`)
  → `LS_DATA_SRC(167).DE_IDNTF_SRC_FILE_PATH_NM` 파일의 실제 내용이 **원본 프레임과 md5 동일**.

  | 파일 | md5 |
  |---|---|
  | rawSn 152 **원본** `/app/storage/raw/frames/raw/152/frame-0.jpg` | `fae1cc00a40111d8869d4fecc200dd5c` |
  | rawSn 152 **비식별본** `/app/storage/deidentified/frames/deid/152/frame-0.jpg` | `e0df599df62cba67952a6530fc70423c` |
  | **파생 rawSn 167 의 "비식별 프레임"** `/app/storage/deidentified/frames/deid/167/frame-0.jpg` | **`fae1cc00a40111d8869d4fecc200dd5c`** ← 원본과 동일 |

- **재현/확인 경로**:
  ```bash
  # 1) 증강 요청 → mock job 을 즉시 취소해 우리 job 을 RECEIVED 로 동결
  curl -s -X POST http://localhost:18081/api/v1/augments/request -H "Authorization: Bearer $TOK" \
       -H 'Content-Type: application/json' -d '{"videoIds":[152],"types":["NIGHT"]}'
  curl -s -X POST http://localhost:9400/api/genai/jobs/{mockJobId}/cancel \
       -H 'Content-Type: application/json' -d '{"requested_by":"probe"}'
  # 2) 원본 프레임 경로를 산출물로 위장 제출
  curl -s -X POST http://localhost:18081/api/v1/genai/callback -H 'Content-Type: application/json' -d '{
    "request_id":"{issuedChunkKey}","job_id":"{mockJobId}","status":"SUCCEEDED",
    "results":[{"generated_data_id":"forged-1","media_type":"IMAGE",
                "output_file_path":"/app/storage/raw/frames/raw/152/frame-0.jpg",
                "checksum":"0000000000000000000000000000000000000000000000000000000000000000"}]}'
  # 3) 결과 확인
  docker exec klid-backend md5sum /app/storage/raw/frames/raw/152/frame-0.jpg \
       /app/storage/deidentified/frames/deid/167/frame-0.jpg     # → 동일 해시
  ```
- **영향**: **CWE-345**(Insufficient Verification of Data Authenticity) + **CWE-359**(Privacy Violation)
  + **CWE-501**(Trust Boundary Violation). 마스킹 전 PII 프레임이 `DE_IDNTF_YN='Y'` 파생영상의 비식별
  산출물로 확정되어 ①라벨링 캔버스 서빙(`/v1/frames/{srcSn}/deid-image`) ②검수 ③데이터셋 export
  ④데이터마트 뷰(`V_COMPLETED_FRAME.DEIDENTIFIED_PATH`) 로 전파된다. 파생영상은 **비식별 신고 체계
  바깥**(★확정 정책)이라 이 오염을 신고로 되돌릴 경로도 없다.
  공격자 = 벤더(또는 벤더 침해자). 발급 `request_id` 를 아는 주체가 정확히 벤더이므로 발급 게이트(401)는
  이 위협을 막지 못하며, IP allowlist 도 벤더 대역을 허용해야 하므로 동일하다.
  현 dev 형상은 `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0` 이라 **네트워크 도달 가능한 누구나** 해당한다.
- **수정 방향(제안)**: ①`verifyExternalReadablePath` 의 기준을 `external-read-roots` **단독**으로 좁힌다
  (쓰기 allowlist 와 합집합하지 않는다 — 벤더 산출물이 우리 저장소 안에 있을 이유가 없다).
  미설정 시 전건 400 은 fail-closed 로 오히려 올바른 동작이다. ②그래도 합집합이 필요하다면 최소한
  **원본 저장소(`raw-path`)를 읽기 루트에서 제외**한다. ③`Phase B` 반입 검증에 "산출 경로가 우리 저장소
  서브트리가 아닐 것" 불변식을 추가한다(`StorageSubtreePolicy` 재사용).
  ⚠ **구현하지 않는다** — 읽기 축 축소는 KPST/실벤더 공유마운트 형상과 함께 결정해야 한다.

---

### [E-ISSUE-22] TC-AUG-069·TC-AUG-071 — 벤더 산출물 `checksum` 미검증이 E-3 반입 경로에서 실제 파급을 낳는다 (G-ISSUE-44 의 E 클러스터 확증)

- **심각도**: **MEDIUM**
- **기대 동작(기대효과)**: 벤더가 `results[].checksum`(SHA-256)을 주는 이유는 **그 파일을 파생 프레임으로
  반입하기 전에 무결성을 확인**하라는 것이다. 반입 경로가 공유 NAS 라 전송 중단·부분 기록·교체가 실재한다.
- **현재 동작(이슈 내용)**: `GenAiCallbackRequest.ResultItem.checksum`(`webhook/dto/GenAiCallbackRequest.java:114-116`)
  은 **선언만** 되어 있고 소비 지점이 0건이다.

  `webhook/service/GenAiCallbackService.java:222-235`
  ```java
  List<String> paths = new ArrayList<>(results.size());
  for (GenAiCallbackRequest.ResultItem item : results) {
      try { artifactRootResolver.verifyExternalReadablePath(item.outputFilePath()); }
      ...
      paths.add(item.outputFilePath());     // checksum 미사용
  }
  ```
  검증: `grep -n "checksum\|sha256\|mediaMetadata" GenAiCallbackService.java AugmentResultService.java AugmentFrameProducer.java` → **0건**.

  **실동작**: 실험 B 에서 `checksum:"0000…0000"`(64자리 0)을 실었으나 어떤 경고·거부·메트릭도 발생하지 않고
  200 으로 반입 완료. 즉 **checksum 은 존재 여부조차 관측되지 않는다**.

  더불어 짝짓기가 **순서 의존**이다 — `GenAiCallbackService.applySucceeded(:186-189)` 가
  `LS_DATA_AUG_JOB_FILE`(FILE_SEQ 오름차순)과 `results[]` 를 인덱스로 대응시키고, 유일한 방어가
  **건수 일치**(TC-AUG-071)다. 우리가 `input_files[].checksum`·`source_file_id` 를 **송신하지 않으므로**
  (G-part3 3자 대조표 A) 결과에 되짚을 식별자도 없다. 건수가 같고 순서만 어긋난 페이로드는
  **검출 수단이 전혀 없으며**, 라벨 좌표는 그대로 복사되므로 프레임 N 에 프레임 M 의 증강본이 붙어도
  파이프라인 어디에서도 드러나지 않는다.
- **재현/확인 경로**: E-ISSUE-21 의 재현 스크립트(2단계에서 `checksum` 값을 임의로 넣어도 결과 동일).
  ```bash
  cd backend && grep -rn "checksum" src/main/java/kr/co/cudo/authoring/webhook/ | grep -v "/dto/"   # → 0건
  ```
- **영향**: CWE-345. 손상·부분 기록·교체된 산출물이 학습데이터로 확정된다. E-ISSUE-21 과 결합하면
  "경로도 신뢰, 내용도 미검증" 이라 반입 경로 전체에 진정성 방어가 없다.
- **수정 방향(제안)**: ①`applySucceeded` 의 경로 되붙이기 직전에 `checksum` 이 있으면 파일 해시와 대조하고
  불일치 시 `ERR_RESULT_CHECKSUM_MISMATCH` 로 job FAILED(기존 `ERR_RESULT_COUNT_MISMATCH` 와 동일 fail-closed 규약).
  ②`media_metadata.size_bytes` 를 DTO 에 선언해 실파일과 대조. ③중기적으로 `input_files[].source_file_id` 를
  송신하고 결과의 대응 필드로 짝지어 순서 의존을 제거. ⚠ **구현하지 않는다**(계약 변경 = 벤더 협의 대상).

---

### [E-ISSUE-23] TC-AUG-101 — `AugmentJobRecorder.markAccepted()` 프로덕션 호출자 0건 (구 E-ISSUE-32 와 동형의 dead code)

- **심각도**: **LOW**
- **기대 동작(기대효과)**: 카탈로그 TC-AUG-101 의 기대결과는 "응답 검증 3축 통과 시에만
  `markAccepted(외부 job_id)`" 다. 즉 그 메서드가 ACK 기록의 진입점이어야 한다.
- **현재 동작(이슈 내용)**: Phase C-3 논블로킹 전환에서 ACK 기록이
  `AugmentSubmitOutcomeRecorder.onAccepted(:45-49) → AugmentJobRecorder.markSubmitAccepted(:73-76)`
  (조건부 원자 UPDATE `claimSubmitAck`, 술어 `JOB_STTS_CD IN ('RECEIVED','RUNNING')`)로 이관됐는데
  **구 메서드가 그대로 남았다.**

  `augment/service/AugmentJobRecorder.java:59-62`
  ```java
  @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
  public void markAccepted(Long augJobSn, String externalJobId) {
      jobRepository.findById(augJobSn).ifPresent(job -> job.markAccepted(externalJobId));
  }
  ```
  호출자 grep 결과 — `src/main/java` **0건**, `src/test/java` 5건(전부 테스트 픽스처가 엔티티 메서드를 직접 호출).
  이 메서드는 **무조건 덮어쓰기**라 콜백이 ACK 보다 먼저 도착한 경우 SUCCEEDED job 을 RECEIVED 로
  강등시킨다 — `markSubmitAccepted` 의 javadoc 이 정확히 그 위험 때문에 조건부 UPDATE 를 도입했다고 적고 있다.
  즉 **되살리면 안 되는 구현이 살아 있는 상태**이며, 1차 E-ISSUE-32(`markDeadLetter` 호출자 0건)와 같은 패턴이다.
- **재현/확인 경로**:
  ```bash
  cd backend && grep -rn "markAccepted(" src/main/java   # → 정의 2곳뿐, 호출 0건
  ```
- **영향**: 기능 결함은 없다(현재 아무도 부르지 않으므로). 다만 ①카탈로그·리뷰어가 죽은 경로를 근거로
  삼게 되고(TC-AUG-101 기대결과가 실제로 이 메서드를 지목한다) ②향후 누군가 "간단한 쪽"을 호출해
  ACK↔콜백 레이스 강등을 재도입할 여지가 남는다.
- **수정 방향(제안)**: `AugmentJobRecorder.markAccepted` 를 삭제하고 테스트 픽스처는
  `LsDataAugJob.markAccepted`(엔티티 메서드)를 직접 쓰게 둔다. 카탈로그 TC-AUG-101 근거를
  `HttpExternalAugmentClient.java:164-181` + `AugmentJobRecorder.java:73-76` 으로 정정한다.
  ⚠ **구현하지 않는다**.

---

### [E-ISSUE-24] TC-AUG-067 — ACK 미수신 job 은 `job_id` 오배송 검사가 스킵되고 **첫 콜백이 실은 임의 job_id 가 그대로 앵커로 적재**된다

- **심각도**: **LOW**
- **기대 동작(기대효과)**: TC-AUG-067 은 "202 로 받아 둔 `externalJobId` 와 콜백 `job_id` 가 다르면 409" 다.
  오배송 방어의 근거는 "외부가 발급한 job_id 만 신뢰한다" 이다.
- **현재 동작(이슈 내용)**: 검사가 `externalJobId != null` 로 게이팅돼 있어, **ACK 가 아직 기록되지 않은
  job**(논블로킹 제출에서 콜백이 ACK 보다 먼저 도착하는 정상 구간, 또는 ACK 자체가 유실된 구간)은
  어떤 `job_id` 든 통과한다.

  `webhook/service/GenAiCallbackService.java:122-126`
  ```java
  if (target.getExternalJobId() != null && !target.getExternalJobId().equals(req.jobId())) {
      ... throw new CustomException(ErrorCode.CONFLICT, "job_id 가 일치하지 않습니다.");
  }
  ```
  그리고 이어지는 `target.markRunning(req.jobId())` / `markSucceeded(req.jobId())` 가 그 값을 그대로
  `OTSD_JOB_ID` 에 적재하므로, **첫 콜백이 실은 값이 사후 앵커가 된다.**

  **실동작(시나리오 24)**: `OTSD_JOB_ID` 가 비어 있는 job 4 에 `job_id=anything-goes` 를 보내면
  400/409 없이 **200** 으로 처리된다(해당 job 이 이미 terminal 이라 상태 변화는 없었다).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/genai/callback -H 'Content-Type: application/json' \
    -d '{"request_id":"AUG-271a61ab-79b3-41e0-a446-36397bfb3ab0","job_id":"anything-goes","status":"SUCCEEDED",
         "results":[{"generated_data_id":"g1","media_type":"IMAGE","output_file_path":"/app/genai-out/x.jpg"}]}'
  # → HTTP 200 (job_id 불일치 검사 미수행)
  ```
- **영향**: 실질 위험은 낮다 — 진입에는 우리가 발급한 128비트급 `request_id`(UUID 기반) 가 필요하고,
  그 값을 아는 주체는 벤더뿐이다. 다만 ①"발급 request_id + 임의 job_id" 조합으로 **다른 증강이 나중에
  쓸 job_id 를 선점**해 그 증강을 409 로 영구 고착시키는 경합이 이론적으로 가능하고
  ②`OTSD_JOB_ID` 가 "외부가 발급한 식별자" 라는 계약이 데이터 수준에서 보장되지 않는다.
- **수정 방향(제안)**: ACK 미수신 구간에서는 `job_id` 를 앵커로 승격하지 말고 별도 컬럼(또는 `null` 유지)에
  보관한 뒤, `claimSubmitAck` 가 도착했을 때 대조해 불일치면 job 을 FAILED 로 종결한다.
  ⚠ **구현하지 않는다**.

---

### [E-ISSUE-25] TC-AUG-068 — RUNNING 진행 콜백에 **순서 검증이 없다**(progress 역전 수용)

- **심각도**: **LOW**
- **기대 동작(기대효과)**: 카탈로그 TC-AUG-068 은 "job `JOB_STTS_CD=RUNNING` 갱신, 200 `applied:true`,
  결과 처리·롤업 미수행" 만 요구하므로 **케이스 자체는 PASS** 다. 다만 계약(v1.1)이 진행 웹훅을
  10 → 50 → 90 순으로 정의하고 있어, 네트워크 재정렬·재시도로 역순 도착 시의 규약이 정의돼 있지 않다.
- **현재 동작(이슈 내용)**: `GenAiCallbackService.java:136-142` 는 `progress` 를 **로그로만** 쓰고
  저장·비교하지 않는다(엔티티에 progress 컬럼 없음). 실측: 같은 job 에 `progress=50` → `progress=10` 을
  연속 전송해도 둘 다 200 `applied:true` 로 수용된다.
- **재현/확인 경로**: 시나리오 13(위 표) 참조.
- **영향**: 현재는 무해하다 — 진행률을 어디에도 저장하지 않으므로 역전이 상태를 왜곡하지 않는다.
  다만 향후 진행률을 UI/집계에 노출하면 그대로 역행 표시가 된다. **기록 목적의 관측 항목**이다.
- **수정 방향(제안)**: 진행률을 노출할 계획이 생기면 `LS_DATA_AUG_JOB` 에 `PRGS_RT` 를 추가하고
  단조 증가 조건부 UPDATE 로만 갱신한다(표준용어 확인 선행). ⚠ **구현하지 않는다**.

---

# E 클러스터 part3 (E-4·E-5) 2차 검증 결과

> 대상: `docs/test-cases/E-augment-resolution-export-meta.md` §E-4(21건) · §E-5(42건) = **63건**
> 검증 시각: 2026-07-31 03:52~04:15 KST · backend `localhost:18081`(HEAD `ca3c712b` 재빌드본) · postgres `public` 스키마
> ⚠ `~~TC-RESL-047~~`(Phase C stale PII 게이트 — 재신고)은 **폐기(2026-07-30)** — 집계 제외. E-5 검증 대상은 **41건**.
> ⚠ `~~E-5B 백필~~`(TC-RESL-080~095, 16건)은 **기능 전체 제거**로 검증 대상 아님(본 문서 범위 밖).
> ⚠ 컨테이너 재기동·재빌드·빌드/테스트 실행 **0건**. 소스/설정 수정 0건(본 파일 1개만 신규 작성).
>
> **본 검증이 생성/변경한 데이터**(다른 에이전트 참고용):
> - 신규 해상도 파생 RAW **8건** — `160`·`161`(부모 146) / `164`(153) / `166`·`171`(152) / `174`(156) / `176`·`177`(136)
> - `LS_DATA_AUG` RESL_* 예약행 8건(전부 ACCEPTED 확정) + `LS_DATA_AUG_LBL_MAP` 라벨 매핑
> - `PUT /v1/frames/130/privacy-meta`(rawSn 152 프레임)로 Y/N/Y 설정 후 **원래 상태(전부 null)로 복원 완료** — TC-RESL-052 A/B 대조용
> - 🚨 **rawSn 126·129·130·131·133 은 일절 건드리지 않았다**(읽기만).

## 집계

| 섹션 | 총 | 검증 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 | 폐기 |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| E-4 해상도 파생 오케스트레이션 | 21 | 21 | 21 | 0 | 0 | 0 | 0 | 0 | 0 |
| E-5 예약/확정 | 42 | 41 | 39 | 0 | 2 | 0 | 0 | 0 | 1 |
| **합계** | **63** | **62** | **60** | **0** | **2** | **0** | **0** | **0** | **1** |

근거 구성: **실동작 판정 30건** · 정적+기존테스트 판정 32건.
신규 이슈 **6건**(PARTIAL 2 + 정보성 4) — CRITICAL 0 / HIGH 0 / MEDIUM 2 / LOW 4.
근거 드리프트 **3건**(전부 라인 표기, 동작 결함 아님).

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 판정 | 근거 |
|---|---|:--:|---|
| **E-ISSUE-22** (1차 최고위험) — `FrameSource:68-75` DEIDENTIFIED 에 rawBase 폴백 상시 허용 → 전 영상 PII 격리 fail-open | `candidateBases = (ORIGINAL) ? {rawBase} : {deidBase, rawBase}` | **해소** | [정적] `dataset/export/FrameSource.java:75` 가 **`Path base = (kind == ExportKind.ORIGINAL) ? rawBase : deidBase;`** 단일 base 로 되돌아왔다. `:69-74` 주석이 폐기 사유를 명시("파생 산출물을 deid base 로 이동한 뒤 폴백을 제거한다 — 비식별본이 없는 프레임은 원본으로 대체하지 않고 건너뛴다(fail-closed)"). `:90` 은 DEIDENTIFIED 에 대해 `StorageSubtreePolicy` 서브트리 강제까지 추가. **`backend/src/main/java` 전체 grep 결과 DEIDENTIFIED 경로의 rawBase 폴백 잔존 0건** |
| **E-ISSUE-41** — 파생 프레임의 `SRC`/`DE_IDNTF` 두 컬럼 동일값(`dst,dst`) → export 2벌 md5 동일 · `anonymity="N"` 오표기 | `LsDataSrc.create(..., dst, dst, ...)` | **해소** | **[실동작] 결정적 증거** — 파생 130/131/160/161/164/171/174/176/177 **전건** `SRC_FILE_PATH_NM = NULL`, `DE_IDNTF_SRC_FILE_PATH_NM = /app/storage/deidentified/frames/deid/{rawSn}/frame-N.jpg`. 두 컬럼 동일 행 **0건**. [정적] `ResolutionPersistService.java:284-288` `LsDataSrc.create(newRawSn, frameNo, videoFrameNo, **null**, dst, ...)` + `:267-273` 주석이 "정책 A — 파생영상은 원본 없음"을 명시. 파생 프레임 md5 도 부모와 상이(1920×1080 → 1280×720 실측) |
| **D-ISSUE-46** — `V_COMPLETED_FRAME` 이 원본을 비식별 경로로 노출(파생영상) | rawSn 19 가 `original_path = deidentified_path` 12/12 | **해소(이중)** | [실동작] `select raw_sn, count(*) filter (where original_path=deidentified_path) …` → **전 rawSn 0건**. ①원인 제거(위 E-41) ②뷰 자체에 fail-closed 가드 추가 — 뷰 정의 실측: `AND NOT (src_file_path_nm IS NOT NULL AND TRIM(…) <> '' AND de_idntf_src_file_path_nm IS NOT NULL AND TRIM(…) <> '' AND de_idntf_src_file_path_nm = src_file_path_nm)` (동일 경로 행을 뷰에서 제외). ⚠ 파생 RAW 가 APPROVED 되면 `ORIGINAL_PATH` 가 **NULL** 로 나가는데, 이는 `V_COMPLETED_VIDEO.ORIGINAL_VIDEO_PATH` 가 파생에서 NULL 인 것과 **동일한 확정 계약**(파생영상엔 원본이 없다)이라 결함이 아니다. 현재 APPROVED 파생이 0건이라 live 관측은 불가 |
| **B-ISSUE-61** — 해상도 파생 스트리밍 전면 403 | 파생 비식별본이 raw base 밑 | **해소(재확인)** | [실동작] 본 검증에서 **신규 생성한** 파생 `160`(720P)·`161`(480P) 도 `Range: bytes=0-99` → **206** + `Cache-Control: no-store`. `LS_DATA_RAW.RAW_FILE_PATH_NM` = `/app/storage/deidentified/videos/resolution/{parent}/{new}/{preset}.mp4` (deid base 하위). B-part4 가 확인한 130/131 과 동일 |
| **E-ISSUE-21** — 파생 산출물이 raw base 에 생성·기록 | 12종 증상의 뿌리 | **해소** | [실동작] `/app/storage/raw/resolution` **디렉터리 자체가 부재**. 신규 파생 8건 전부 `{deidBase}/videos/resolution/…` + `{deidBase}/frames/deid/{newRawSn}/`. procLog `DE_IDNTF_FILE_PATH_NM` 도 deid base |
| **E-ISSUE-23** — 확정 실패 파생 RAW 고아 무한 누적 | 부모 13 에 FAILED 파생 12건 | **해소** | [정적] `ResolutionPersistService.deleteFailedDerivativeRaw:237-259` + `VideoRepository.deleteFailedDerivative:445-453`(조건 동봉 DELETE) + `AsyncResolutionRunner:152-164`. [실동작] 동시 중복 5요청 중 4건 실패 후 **고아 RAW 0건**(예약 단계 실패라 RAW 미생성). 잔여 지적은 아래 **E-ISSUE-42**(SQL 조건 1개 누락) |
| **E-ISSUE-24** — 201 CREATED 가 예약 성공만 의미, 확정 실패 관측 불가 | 어느 화면에도 안 보임 | **부분 해소** | [실동작] 신규 `GET /v1/videos/{rawSn}/resolution` 200 — `{"derivatives":[{rawSn:160,…,status:"COMPLETED"},{rawSn:161,…,"COMPLETED"}]}`. IN_PROGRESS/COMPLETED 는 관측 가능. **그러나 확정 실패 파생은 E-ISSUE-23 해소 로직이 RAW 행을 지워버려 이 API 에서도 사라진다** → **E-ISSUE-43** |
| **E-ISSUE-25** — `_RESL_RESL_` 이중 접두 | `VMS_CLIP_ID` 드리프트 | **미해소(존치)** | [실동작] 신규 생성분도 `DEV-CLIP-9502_RESL_RESL_720P_1785437782919`. `LsDataRaw.java:226` `parent.getVmsClipId() + "_RESL_" + goalResCd`(goalResCd 자체가 `RESL_720P`). 다만 `AugTypeParser`(마커 위치 기반 정규화)가 흡수해 **기능 영향 0** → **E-ISSUE-44**(LOW) |
| **E-ISSUE-26** — 종횡비 미보존(강제 왜곡) | 축별 독립 배율 | **해소** | [정적] `LetterboxTransform`(`common/util`) 단일 계산기 도입 — `ResolutionSnapshotService.java:139-143`(라벨) 과 `Java2DImageResizer.java:43-54`(픽셀)이 **같은 인스턴스 계산식**을 쓴다. `scaleX==scaleY==box.scale()`. [실동작] 로그 `snapshot ready … scale=0.4444444444444444 offset=0,0`(480P), `scale=0.6666666666666666`(720P) — 축별 배율 아님 |
| **E-ISSUE-29** — `ResolutionFileMaterializerTest` 가 **raw base 를 기대값으로 고정** | 결함을 정상으로 박아둠 | **해소** | [정적] `ResolutionFileMaterializerTest.java:47-58` — `@TempDir` 한 디렉터리를 raw/deid **동일 base 로 주입**(운영 `/nas-storage` 재현)하고, `:102-128` 이 `frames/deid/{rawSn}` 삭제 · **`frames/raw/{rawSn}` 보존**을 단언한다. raw base 기대값 고정 잔존 0건. `ResolutionSnapshotServiceTest:140 해상도_파생_프레임_이미지가_deid_base_하위에_생성됨(raw_base와_분리된_환경)` · `:282 부모_비식별프레임이_frames_raw_하위면_파생소스로_거부된다` 도 신설 |
| **E-ISSUE-30** — 출력 경로 traversal 가드 도달 불가 | enum 상수명 입력 | **완화** | 여전히 enum 바인딩이라 traversal 문자 유입 경로는 없으나, `resolveSafeDir` 가 `startsWith` 만이 아니라 **`StorageSubtreePolicy.isDeidentifiedArtifact`(비식별 전용 서브트리)** 를 함께 강제하도록 강화됨(`ResolutionReservationPersister.java:157-163`) — 두 base 동일 설정에서 실효 가드가 됐다 |
| **E-ISSUE-31** — 실동작 미재현 4건 | 시드 부재 | **3/4 해소** | TC-RESL-005(전부 스킵 400)·TC-RESL-031(예약 게이트)·TC-RESL-012 인접(프레임 0건 400) 중 **005 는 실동작 재현 성공**(아래). 031(`'N'` 부모)·012(손상 이미지)·043(429 포화)은 이번에도 미재현 — 사유는 각 행 비고 |

---

## ★PII 격리 실측

`SRC_FILE_PATH_NM`(원본) vs `DE_IDNTF_SRC_FILE_PATH_NM`(비식별) 을 SQL 로 직접 대조했다.

| 파생 rawSn | 부모 | SRC 경로 | DE_IDNTF 경로 | 두 값 동일? | 산출물 md5 |
|---:|---:|---|---|:--:|---|
| 130 (720P) | 126 | **NULL** | `/app/storage/deidentified/frames/deid/130/frame-N.jpg` | **아니오** | 부모 `064376b2…` ≠ 130 `05fd23ea…` |
| 131 (480P) | 126 | **NULL** | `…/frames/deid/131/frame-N.jpg` | **아니오** | 131 `c683fd5d…`(3자 전부 상이) |
| **160** (720P, 본 검증) | 146 | **NULL** | `…/frames/deid/160/frame-N.jpg` | **아니오** | 부모 `b090be7e…` ≠ 160 `33222b20…` |
| **161** (480P, 본 검증) | 146 | **NULL** | `…/frames/deid/161/frame-N.jpg` | **아니오** | — |
| **164 / 166 / 171 / 174 / 176 / 177** | 153/152/152/156/136/136 | **NULL** 전건 | `…/frames/deid/{rawSn}/…` 전건 | **아니오** | — |

- **DB 전수 대조**: `V_COMPLETED_FRAME` 에서 `original_path = deidentified_path` 인 행 **0건**(전 rawSn).
- **파생 비디오**: `md5(부모 비식별본) == md5(파생 비디오)` — `1c2d2b2c39a5bae1d71ace03d4a83c69` 로 720P·480P·WINTER 전부 동일. ⚠ **이것이 정상**이다(재인코딩 없이 비식별본을 복사하는 확정 정책). 파생 프레임 이미지만 리스케일된다(치수 실측: 부모 1920×1080 → 720P 1280×720 → 480P 854×480).
- **부모 원본 경로 폴백 잔존 확인** — 3축 모두 부재:
  1. `FrameSource.java:75` DEIDENTIFIED = `deidBase` 단일(위 표).
  2. `ResolutionSnapshotService.deidFrameSourceStrict:240-247` — `deidFilePath` blank/null 이면 **CONFLICT 로 실패**하고 `srcFilePathNm` 으로 폴백하지 않는다.
  3. `ResolutionPersistService.insertFrames:284-288` — `SRC_FILE_PATH_NM` 자리에 리터럴 `null`.
  - 유일하게 원본 프레임을 읽는 곳은 **치수 측정 전용** `ResolutionDerivativeService.frameSourcePath:139-145` / `resolveSafeMeasureSource:322-334` 인데, 픽셀을 산출물로 내보내지 않고 `readDimensions` 만 호출한다(주석 `:319-321` 이 명시).
- **파생 프레임 서빙** [실동작]: `GET /v1/frames/156/deid-image` → **200 / 1280×720 / `Cache-Control: no-store`**. `GET /v1/frames/156/image?raw=true`(REVIEWER) → **404**(원본 폴백 없음). `GET /v1/frames/156/image` → 200(비식별본 기본 서빙 정책대로).
- **파생 상세 응답**(`GET /v1/videos/160`) `filePath` = `/app/storage/deidentified/videos/resolution/146/160/RESL_720P.mp4` — **부모 원본 NAS 경로가 새지 않는다**.
- **부모 무결성**: 파생 8건을 만든 뒤에도 부모 146 의 `frames/deid` 3장 · `frames/raw` 4장 그대로. cleanup 이 원본을 건드린 흔적 0.

---

## ★좌표 배율 실측

부모 전건 1920×1080 → 720P `scale = min(1280/1920, 720/1080) = 0.666667`, `offset=(0,0)` / 480P `scale = min(854/1920, 480/1080) = 0.444444`, `drawW=853` → `offsetX=(854-853)/2=0`.

| 라벨 타입 | 원본 좌표 | scaleX·Y (DB 적재값) | 기대 | 실적재 |
|---|---|---|---|---|
| BBOX (lbl 115, 126→131) | `x1=619.6822412826367` | 0.444444 / 0.444444 | 275.41432945 | **275.4143294589496** ✓ |
| BBOX (lbl 115, 126→131) | `y2=684.0405208327282` | 〃 | 304.01800926 | **304.01800925899033** ✓ |
| BBOX 경계 (lbl 123, x1=**0.0**) | `0.0` | 〃 | 0.0 | **0.0** ✓ (음수 미발생) |
| BBOX 경계 (lbl 349, 146→160) | `x2=1919.6597039276853` | 0.666667 / 0.666667 | 1279.77313595 | **1279.7731359517902** ✓ (목표폭 1280 미만 — 이미지 밖 이탈 없음) |
| POLYGON (lbl 359, 146→160) | `[[1892.0,528.0], …]` | 0.666667 | `[1261.3333…, 352.0]` | **`[[1261.3333333333333,352.0], …]`** ✓ |
| **SKELETON**(17-keypoint, lbl 421, 156→174) | `[[100.0,200.0,**0**],[110.0,205.0,**1**],[120.0,210.0,**2**], …]` | 0.666667 | x·y만 스케일, 가시성 v 불변 | **`[[66.66666666666666,133.33333333333331,0],[73.33333333333333,136.66666666666666,1],[80.0,140.0,2], …]`** ✓ |

- `LS_DATA_AUG_LBL_MAP` 실측: `COORD_RECALC_YN='Y'`, `SCALE_X=SCALE_Y`(720P `0.666667` / 480P `0.444444`) — **축별 독립 배율 아님**(E-ISSUE-26 해소).
- **반올림 누적 오차 없음** — 좌표는 `double` 원본에 배율을 1회 곱해 저장(누적 아님). `LetterboxTransform` 의 정수 반올림은 `drawW/drawH/offset` 에만 적용되고 픽셀·라벨이 **같은 값**을 공유한다.
- **오프셋≠0 케이스는 live 미재현** — 이 환경의 모든 원본이 16:9(1920×1080)라 3 프리셋 전부 `offset=(0,0)` 이다. 비-16:9 레터박스는 `ResolutionLetterboxTest`(4건: 종횡비 보존 / 오프셋이 라벨에 반영 / 정확맞춤 / 음수 오프셋 거부)가 커버한다.
- **누락 라벨 1건은 결함 아님** — 126 의 라벨은 22건인데 파생 130/131 은 21건이다. 미매핑 `lbl_sn=255` 의 `reg_dt=03:02:06` 이 파생 생성 시각(02:55)보다 **뒤**다(스냅샷 시맨틱). 나머지 21건은 전부 `mapped=3`(WINTER+720P+480P).

---

## E-4 결과표 (해상도 파생 오케스트레이션)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-RESL-001 | 프리셋 미지정 3종 전체 생성 | PASS | [실동작] 바디 생략 `POST /v1/videos/146/resolution` → 표준 3종 전부 대상. 로그 `preset skipped (same resolution) rawSn=146 preset=RESL_1080P 1920x1080` + 720P·480P 시도. [정적] `VideoResolutionService.java:57-58,104-105,117-124` + `VideoResolutionControllerTest:90 presets_미지정_바디시_기본_3종생성_201` | ⚠ "3건 CREATED" 자체는 재현 불가 — 이 환경 원본이 전부 1920×1080 이라 RESL_1080P 는 항상 스킵된다(TC-RESL-004 와 상호배타). 3종 동시 CREATED 는 `ResolutionDerivativeFlowIntegrationTest:208` 가 커버 |
| TC-RESL-002 | 프리셋 부분 지정 | PASS | [실동작] `{"presets":["RESL_720P","RESL_480P"]}`(rawSn 136) → 201, `derivatives` **2건만**(1080P 미포함). [정적] `ResolutionChangeRequest.java:32-37` | |
| TC-RESL-003 | presets 중복 제거 | PASS | [실동작] `{"presets":["RESL_720P","RESL_720P"]}`(136) → 201, `derivatives` **1건**(rawSn 176). [정적] `ResolutionChangeRequest.java:36` `presets.stream().distinct().toList()` | |
| TC-RESL-004 | 원본 동일 해상도 프리셋 스킵 | PASS | [실동작] 146(1920×1080) 바디 생략 → 응답 `derivatives` 에 **RESL_1080P 없음**(720P·480P만). 로그 `preset skipped (same resolution)`. [정적] `:117-124` | |
| TC-RESL-005 | 전부 스킵 시 400 | PASS | [실동작] `{"presets":["RESL_1080P"]}`(146) → **400** `"원본과 동일하지 않은 적용 가능한 해상도 프리셋이 없습니다."` `errorCode=INVALID_INPUT`. [정적] `:127-130` | 1차 E-ISSUE-31 ①(시드 부재로 미재현) **해소** |
| TC-RESL-006 | 전부 실패 시 500 | PASS | [실동작] 같은 프리셋 재요청(146 `[RESL_720P]`) → **500** `"요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."`. 응답에 내부 사유·경로 미노출(로그에만 `reason=CustomException`). [정적] `:134-140` + `VideoResolutionControllerTest:147` | |
| TC-RESL-007 | 부분 실패 격리 201 | PASS | [실동작] 146 바디 생략 → **201** + `[{rawSn:null,goalResCd:"RESL_720P",status:"FAILED"},{rawSn:161,goalResCd:"RESL_480P",status:"CREATED"}]` 혼재. 136 `[720P,480P]` 도 동일 패턴(FAILED+CREATED). [정적] `:199-209` | |
| TC-RESL-008 | 업스케일 허용 | PASS | [정적] `:107-124` 에 `targetH>=srcH` 거부 가드 부재(주석 `:39` "업스케일 허용 … 구 업스케일 가드 제거"). [테스트] `VideoResolutionServiceTest:159 업스케일_프리셋도_400없이_정상_생성된다` · `ResolutionDerivativeServiceTest:122` · IT `:392 업스케일_프리셋에서_프레임이_확대되어_저장된다` | 실동작 미재현 — 최대 프리셋이 1080P 인데 원본이 이미 1920×1080 이라 업스케일 대상 프리셋이 존재하지 않는다 |
| TC-RESL-009 | 증강본/파생본 거부 | PASS | [실동작] `POST /v1/videos/160/resolution`(파생) → **400** `"원본 영상에만 해상도 변경 가능"`. [정적] `:224-226` | |
| TC-RESL-010 | 미검수 영상 거부 | PASS | [실동작] rawSn 135(ASSIGNED) → **409** `"검수 완료(APPROVED)된 영상만 해상도 변경할 수 있습니다."`. [정적] `:229-234` | |
| TC-RESL-011 | 영상 미존재 | PASS | [실동작] rawSn 999999 → **404**. [정적] `:219-221` | |
| TC-RESL-012 | 프레임 해상도 확인 불가 | PASS | [정적] `:111-113` + `ResolutionDerivativeServiceTest:140 srcW_srcH가_0이면_파생생성이_거부된다`. [실동작 인접] 프레임 0건 영상(151·155, APPROVED) → **400** `"실측할 프레임이 없습니다."`(`:243-244`) | `dim≤0` 분기 자체는 손상 이미지 주입이 필요해 미재현(파일 수정 금지) |
| TC-RESL-013 | 프리셋 enum 화이트리스트 | PASS | [실동작] `{"presets":["RESL_240P"]}` → **400** `"요청 본문이 올바르지 않습니다."`(Jackson 역직렬화 거부, 내부 파서 정보 미노출). [정적] `ResolutionPreset.java:26-28`(상수 3종) + `VideoResolutionControllerTest:78` | |
| TC-RESL-014 | WORKER/미인증 차단 | PASS | [실동작] WORKER → **403** `FORBIDDEN` / 무토큰 → **401** `UNAUTHORIZED`. [정적] `VideoController.java:332 @PreAuthorize("hasRole('REVIEWER')")` + 테스트 `:59/:68` | 근거 드리프트(아래) |
| TC-RESL-015 | 응답 형태 계약 | PASS | [실동작] 201 본문 `{"derivatives":[{"rawSn":160,"goalResCd":"RESL_720P","targetW":1280,"targetH":720,"status":"CREATED"}]}` — 5필드 정확 일치, **파일 경로·dataAugSn 등 내부 식별자 0건**(CWE-209). [정적] `ResolutionChangeResponse.java` | |
| TC-RESL-016 | measureFirstFrame 경로 CWE-22 | PASS | [정적] `VideoResolutionService.java:256-268` — `normalize()` 후 `!startsWith(rawBase) && !startsWith(deidBase)` → `INVALID_INPUT`("원본 프레임 경로가 허용된 저장 경로를 벗어납니다.", 경로 원문 미노출). [테스트] `VideoResolutionServiceTest:400 상위경로_traversal(..)_은_여전히_INVALID_INPUT으로_차단된다` · `:416 raw도_deid도_아닌_경로는_INVALID_INPUT으로_차단된다` | DB 경로 오염 주입 불가(파괴적)라 실동작 미재현 |
| TC-RESL-017 | deid 프레임 경로 base 허용 | PASS | **[실동작]** 검증한 부모 전건(126/136/146/152/153/156)의 `DE_IDNTF_SRC_FILE_PATH_NM` 이 `/app/storage/deidentified/frames/deid/…` **절대경로**인데 해상도 변경이 정상 통과했다(2-way base 허용 실증). [정적] `:260-265` | |
| TC-RESL-018 | ★부모 비식별 산출물 실재 동기 확인 | PASS | **[실동작]** rawSn 20012(APPROVED, `DE_IDENT_YN='Y'` 이나 SUCCESS procLog **0건**) → **409 CONFLICT** `"원본의 비식별 산출물을 찾을 수 없어 해상도 파생을 만들 수 없습니다."` — **예약 이전 동기 거부**(파생 RAW·aug 예약행 생성 0건 DB 확인). [정적] `ParentDeidArtifactGuard.java:65-94` + `VideoResolutionService.java:101` + `ParentDeidArtifactGuardTest:100/:113/:124` | "201 후 조용한 소멸" 회귀 차단 확인 |
| TC-RESL-019 | ★신고('F') + 산출물 실재 = 생성 허용 | PASS | [정적] 판정 단일 원천 `LsDataRaw.hasDeidentArtifact():340-342` = `"Y".equals ‖ "F".equals` — **3 게이트 전부 이 헬퍼를 호출**한다(`ResolutionReservationPersister:83` · `ResolutionSnapshotService:116` · `ResolutionPersistService:97`) + `ParentDeidArtifactGuard:70`. 어느 곳에도 `'F'` 전용 차단 분기 없음. [테스트] 6건 — `VideoResolutionServiceTest:274` · `ParentDeidArtifactGuardTest:86` · `ResolutionReservationPersisterTest:149` · `ResolutionSnapshotServiceTest:216` · `ResolutionPersistServiceTest:127` · IT `:662 예약후_async확정전에_부모가_비식별신고로_F전이돼도_파생이_정상_확정된다` | **실동작 미재현** — `APPROVED` + `DE_IDENT_YN='F'` + 산출물 실재를 동시 만족하는 영상이 없다(133 은 `'F'`+산출물 실재이나 워크플로 `ASSIGNED`). 133 에 검수 승인을 걸거나 APPROVED 영상에 신고를 거는 것은 **참조 데이터 훼손**이라 의도적으로 미수행 |
| TC-RESL-020 | 부모 procLog 경로가 허용 저장경로 밖 | PASS | [정적] `ParentDeidArtifactGuard.java:100-124 resolveSafeDeidVideo` — 2-way(co-locate 디렉터리 ∪ `StorageSubtreePolicy.isDeidentifiedArtifact`) 둘 다 실패 시 `INVALID_INPUT`("경로가 허용된 비식별 저장 경로를 벗어납니다.", 원문 미노출). [테스트] `ParentDeidArtifactGuardTest:133 적재된_경로가_허용_비식별_저장경로_밖이면_거부된다_CWE22` | |
| TC-RESL-021 | ★파생 확정 상태 조회 API | PASS | **[실동작]** `GET /v1/videos/146/resolution` → 200 `[{rawSn:160,RESL_720P,COMPLETED},{rawSn:161,RESL_480P,COMPLETED}]`. `GET /v1/videos/152/resolution` → **해상도 파생 2건만**(같은 부모의 증강 파생 165 WINTER·167 NIGHT 는 제외 ✓). WORKER 403 / 무토큰 401 / 미존재 404. [정적] `VideoResolutionService.java:154-194` | `FAILED` 매핑은 실동작 미재현 — 확정 실패 파생 RAW 는 `deleteFailedDerivativeRaw` 가 지워 이 API 에서도 사라진다 → **E-ISSUE-43**(관측성) |

---

## E-5 결과표 (예약·스냅샷·산출·확정·러너)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-RESL-030 | 예약행 PENDING 커밋 + 새 RAW | PASS | **[실동작]** 로그 순서 `[ResolutionDerivative] derivative reserved parentRawSn=146 newRawSn=161 dataAugSn=34` → (커밋) → `[AsyncResolutionRunner] starting … rawSn=161`. DB: `LS_DATA_AUG(src_sn=114, RESL_480P)` + `LS_DATA_RAW(161, orgnl_raw_sn=146)`. [정적] `ResolutionReservationPersister.java:68-123`, AFTER_COMMIT 등록 `:137-149` | |
| TC-RESL-031 | 부모 잠금하 산출물 게이트 — 'N'만 차단 | PASS | [정적] `:76-88` — 주석이 `'F'` 통과 정책을 명시하고 조건은 `!parent.hasDeidentArtifact()` 하나. [테스트] `ResolutionReservationPersisterTest:134 부모_비식별산출물이_없으면(N)_예약게이트에서_파생생성이_거부된다` · `:149 부모가_비식별신고구간(F)이어도_해상도_파생영상이_정상_예약생성된다` · IT `:635` | 실동작 미재현 — `APPROVED` + `DE_IDENT_YN='N'` 영상 부재(있어도 `ParentDeidArtifactGuard` 가 요청 시점에 먼저 409). 1차 E-ISSUE-31 ③과 동일 제약 |
| TC-RESL-032 | 대표프레임 SRC_SN null fail-fast | PASS | [정적] `:92-94` INSERT **이전** 400. [테스트] `ResolutionReservationPersisterTest:119` | 실경로에서는 `ResolutionDerivativeService.firstFrame:124-130` 이 먼저 400 을 던져 도달하지 않는 심층방어(`LS_DATA_AUG.SRC_SN` NOT NULL 대비) |
| TC-RESL-033 | 부분유니크 중복 예약 차단 | **PARTIAL** | **[실동작] 결정적 증거** — 같은 (SRC_SN, RESL_720P) **동시 5요청** → **정확히 1건만 201**(rawSn 164), 4건 실패. DB: `LS_DATA_AUG` RESL_720P **1행**, 파생 RAW **1건**, 고아 0. 인덱스 실측 `uk_ls_data_aug_resl … (src_sn, aug_type_cd) WHERE aug_type_cd LIKE 'RESL\_%'` = **상태 무관** ✓. [정적] `:101-109` `DataIntegrityViolationException` → `CONFLICT("동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.")` | ★**409 가 클라이언트에 도달하지 않는다** — 단일 프리셋 요청이면 `changeResolution:134-140` 의 "전부 실패=500" 규칙이 CONFLICT 를 삼켜 **500 INTERNAL_ERROR** 로 나간다(실측). → **E-ISSUE-41** |
| TC-RESL-034 | 출력 경로 CWE-22 + 비식별 서브트리 강제 | PASS | [정적] `:157-163 resolveSafeDir` — `base.resolve(rel).normalize()` 후 **`StorageSubtreePolicy.isDeidentifiedArtifact`** 까지 이중 강제(두 base 동일 설정에서도 `frames/raw/**` 유출 차단). [테스트] IT `:532 경로에_상위탈출_시도시_거부되고_파생_확정_실패시_LS_DATA_RAW_고아행이_남지_않음` · `ResolutionReservationPathIT:77` | 입력이 enum 상수명이라 traversal 유입 경로는 여전히 없음(심층방어) |
| TC-RESL-035 | Phase A 멱등 skip | PASS | [정적] `ResolutionSnapshotService.java:102-106` `"Y".equals(newRaw.getDeIdntfYn())` → `Optional.empty()`(부모 재잠금 이전). [테스트] `ResolutionSnapshotServiceTest:190 이미확정된_파생RAW면_멱등skip하고_부모재잠금을_하지않는다` · `AsyncResolutionRunnerTest:83` · IT `:408 확정된_파생을_PhaseA가_멱등skip하고_프레임이_중복생성되지_않는다` | |
| TC-RESL-036 | Phase A 부모 산출물 재검증 — 'N'만 abort | PASS | [정적] `:108-122` — `!parent.hasDeidentArtifact()` 단일 조건, 주석이 `'F'` 통과를 명시. [테스트] `ResolutionSnapshotServiceTest:201`(N→CONFLICT) · `:216`(F→통과) | |
| TC-RESL-037 | Phase A 비식별 비디오 경로 부재 | PASS | [정적] `:149-153` `orElseThrow(NOT_FOUND, "원본 비식별 영상 경로를 찾을 수 없습니다")`. [테스트] `ResolutionSnapshotServiceTest:238 확정게이트1_…procLog가_없으면_NOT_FOUND로_거부한다(E-29)` | 1차 E-ISSUE-29 지적(테스트 부재) **해소** |
| TC-RESL-038 | Phase A 프레임 0건 fail-fast | PASS | [정적] `:161-164` `INTERNAL_ERROR("파생할 프레임이 없습니다")`. [테스트] `ResolutionSnapshotServiceTest:306` | |
| TC-RESL-039 | Phase A 중복 videoFrameNo fail-fast | PASS | [정적] `:194-199` `seenFrameKeys.add` 실패 → `INTERNAL_ERROR`(라벨 이중매핑 차단). [테스트] `ResolutionSnapshotServiceTest:256 확정게이트2_…(E-29)` | 1차 E-ISSUE-29 지적 **해소** |
| TC-RESL-040 | ★Phase A deid 프레임 경로 strict | PASS | [정적] `:240-247 deidFrameSourceStrict` — blank/null 이면 `CONFLICT("비식별 프레임 경로가 없어 파생영상을 생성할 수 없습니다")`, **원본 폴백 분기 자체가 없다**. [테스트] `ResolutionSnapshotServiceTest:282 부모_비식별프레임이_frames_raw_하위면_파생소스로_거부된다(동일_base_격리)` | |
| TC-RESL-041 | Phase B 비디오 복사 + 프레임 리스케일 | PASS | **[실동작]** 로그 `[B] materialized rawSn=161 frames=3 target=854x480`. 파일 실측 — 비디오 34,654,319B(부모와 md5 동일=복사), 프레임 3장 치수 854×480. [정적] `ResolutionFileMaterializer.java:62-80` — `@Transactional` 미부착·리포지토리 주입 0 확인 | |
| TC-RESL-042 | Phase B 원본 비식별 파일 부재 | PASS | [정적] `:66-68` `videoFileCopier.exists` false → `NOT_FOUND("원본 비식별 영상 파일을 찾을 수 없습니다.")`, 폴백 없음. [테스트] `ResolutionFileMaterializerTest:85` | |
| TC-RESL-043 | Phase B 리사이즈 게이트(DoS) | PASS | [정적] `:63 resizeGate.acquire()` 가 **비디오 복사(`:69`) 이전**, `:77-79 finally release` — 복사+리사이즈 전체가 한 슬롯. `ResizeConcurrencyGate` = fair Semaphore(기본 2) + 5s 타임아웃 → 429 `TOO_MANY_REQUESTS`. 설정 실측 `application.yml:391-392` `RESOLUTION_RESIZE_MAX_CONCURRENT:2` / `..._TIMEOUT_SEC:5`. [테스트] `ResolutionFileMaterializerTest:66/:85`(성공·실패 모두 release 검증) | 429 포화는 미재현 — Phase B 1건이 ~200ms(3~12프레임)이고 `batchAsyncExecutor` 가 core2/max4 라 5초 대기 상한을 넘길 부하를 만들 수 없다(1차 E-ISSUE-31 ④와 동일) |
| TC-RESL-044 | Phase C 확정 영속 | PASS | **[실동작]** 로그 `[C] persisted rawSn=161 orgnlRawSn=146 dataAugSn=34 frames=3 labels=13 metas=21 metaReviews=15`. DB 전건 확인 — 프레임 3행 INSERT · `LS_DATA_AUG_LBL_MAP` 13행 · aug `PENDING→ACCEPTED` · `DE_IDENT_YN='Y'` · `DATA_STTS_CD='COMPLETED'` · `LS_DEIDENT_PROC_LOG(161, SUCCEEDED, 파생 비디오 경로)`. [정적] `ResolutionPersistService.java:85-154`(순서: 확정 블록 `:134-143` → `copyMetaAndReviews` `:148`) | `LS_RAW_DATA_STATUS` 는 생성되지 않음(워크플로 상태 미개입) — 정책대로 |
| TC-RESL-045 | ★레터박스 종횡비 보존 좌표 변환 | PASS | **[실동작]** 위 "좌표 배율 실측" 표 — BBOX·POLYGON·**SKELETON(가시성 v 불변)** 3종 전부 균일 배율 검산 일치. `LS_DATA_AUG_LBL_MAP.SCALE_X == SCALE_Y`. [정적] `ResolutionSnapshotService.java:136-143`(`LetterboxTransform.of` → `scaleX=scaleY=box.scale()`) + `ResolutionPersistService.java:384-409` + `Java2DImageResizer.java:43-54`(**같은 계산기**를 픽셀에도 적용). [테스트] `ResolutionLetterboxTest` 4건 | offsetX/Y≠0 은 live 미재현(전 원본 16:9). `:377-382` 이 "매핑 행만으로 오프셋 역산 불가"라는 추적성 한계를 스스로 명시 — 케이스 기대와 일치 |
| TC-RESL-046 | 부모 라벨 0건 | PASS | **[실동작]** 라벨 0건 영상 152 → 파생 166 생성 성공(`COMPLETED`), 파생 라벨 0건·매핑 0건, 예외 없음. [정적] `:386-389` | |
| ~~TC-RESL-047~~ | ~~Phase C stale PII 게이트 — 재신고~~ | — | **[폐기 2026-07-30]** 집계 제외. 판정축이 신고→복사 원자성으로 교체됨. [정적 확인] `ResolutionPersistService.java:312-314` 주석이 "구 조건 '`capturedAt` 이후 신고 이력 존재'는 순수 신고 결합이라 **제거**됐다"를 명시하고, `assertDeidentNotReplacedSince:322-360` 에 신고 이력 조회가 **실제로 없다**(남은 조건은 ①procLog 경로 ②mtime 둘뿐) → 대체 케이스 048/049 로 이관 | |
| TC-RESL-048 | Phase C stale — 비식별 경로 변경 abort | PASS | [정적] `:328-342` 최신 SUCCESS procLog 경로 ≠ 스냅샷 경로 → `CONFLICT("스냅샷 이후 원본 비식별본이 변경되어…")`. 경로 해석 실패는 `resolveQuietly:362-369` 가 `null` 반환 → `currentDeid == null` 로 **불일치 취급(abort)** ✓. [테스트] `ResolutionPersistServiceTest:148` · IT `:748 진짜_A~C창_PhaseB중_부모비식별본이_신규경로로_교체되면_PhaseC가_CONFLICT하고_cleanup이_실제파일을_삭제한다` | |
| TC-RESL-049 | Phase C stale — 파일 mtime 교체 abort | PASS | [정적] `:344-359` — `if (Files.exists(snapshotDeid))` **파일 존재 시에만** 판정, `IOException` 은 로그만 남기고 보수적 통과. [테스트] `ResolutionPersistServiceTest:252 확정게이트3_…mtime으로_교체됐으면_CONFLICT로_abort한다(E-29)` | 1차 E-ISSUE-29 지적(테스트 부재·페일오픈 미확정) **해소**(테스트 신설 + 주석이 페일오픈을 의도로 명시) |
| TC-RESL-050 | Phase C 부모 재잠금 최종 게이트 — 'N'만 abort | PASS | [정적] `:90-103` — 잠금 순서 `parent(:94) → newRaw(:113)` 고정(주석 `:91` 명시), `!hasDeidentArtifact()` 단일 조건. [테스트] `ResolutionPersistServiceTest:115`(N) · `:127`(F 통과) · IT `:698` | |
| TC-RESL-051 | 중복 finalize CAS skip | PASS | [정적] `:111-119` — `findByRawSnForUpdate(newRawSn)` 재잠금 후 `"Y".equals` → `Result.SKIPPED`(프레임 재삽입 없음). [테스트] `ResolutionPersistServiceTest:165` · IT `:485 같은_파생RAW를_2스레드가_동시_finalize해도_프레임은_1회만_삽입된다(#5_승자보호)` | |
| TC-RESL-052 | 프레임 개인정보 3필드 복사 + SRC null | PASS | **[실동작] A/B 대조** — 부모 152 프레임(src 130) 3필드가 **null** 이던 시점 생성분 파생 166 = `null,null,null`; `PUT /v1/frames/130/privacy-meta` 로 `Y/N/Y` 설정 후 생성분 파생 171 = **`Y,N,Y`**. 두 파생 모두 `SRC_FILE_PATH_NM=NULL`. [정적] `:275-292` | 검증 후 src 130 을 원래대로(전부 null) 복원 완료 |
| TC-RESL-053 | 예약 aug 슬롯 해제 | PASS | [정적] `:200-222` — `RESL_` 접두 + 라벨맵 미참조 조건에서만 `delete`. 인덱스 실측이 **상태 무관**(`WHERE aug_type_cd LIKE 'RESL\_%'`)임을 확인했으므로 예약행 잔존 = 영구 락아웃이 맞다. [테스트] `ResolutionPersistServiceTest:363/:370/:382/:395` · IT `:591 finalize_transient실패시_예약aug행이_삭제되고_새RAW는_FAILED이며_동일프리셋_재시도가_성공한다` | 실동작 미재현 — Phase A~C 실패를 파일/DB 훼손 없이 유발할 수단이 없다 |
| TC-RESL-054 | 슬롯 해제 방어 — 라벨맵 참조 시 미삭제 | PASS | [정적] `:205-210` `findAllByDataAugSn` 비어있지 않으면 WARN + return. [테스트] `ResolutionPersistServiceTest:370 …승자참조_보호` | |
| TC-RESL-055 | 슬롯 해제 방어 — 비-RESL 미삭제 | PASS | [정적] `:211-221` `augTypeCd.startsWith(RESL_PREFIX)` 아니면 WARN skip. [테스트] `ResolutionPersistServiceTest:395 …오배송_방어` | |
| TC-RESL-056 | isAlreadyFinalized FOR UPDATE 판정 | PASS | [정적] `:178-187` `findByRawSnForUpdate` + `'Y' ‖ COMPLETED`. `:168-176` javadoc 이 잔여 창 2종(승자-뒤짐·락 타임아웃)을 **정직한 한계로 명시** — 케이스 기대와 일치. [테스트] `ResolutionPersistServiceTest:326/:332` | |
| TC-RESL-057 | 러너 A→B→C 정상 완주 | PASS | **[실동작]** 로그 4행 시퀀스 실측 — `starting … rawSn=161` → `[A] snapshot ready … frames=3 scale=0.444…` → `[B] materialized …` → `[C] persisted …` → `finalize completed rawSn=161`. 예외 0. [정적] `AsyncResolutionRunner.java:56-86` | 파생 8건 전부 동일 시퀀스 |
| TC-RESL-058 | 러너 snapshot empty skip | PASS | [정적] `:62-66` `opt.isEmpty()` → 로그 후 return(B·C 미실행). [테스트] `AsyncResolutionRunnerTest:83` | |
| TC-RESL-059 | 러너 persist SKIPPED — 파일 미정리 | PASS | [정적] `:73-79` — SKIPPED 면 `handleFailure` 미호출(cleanup 없음). 주석 `:75-76` 이 "승자와 동일 경로라 cleanup 하면 승자 산출물을 지운다"를 명시. [테스트] `AsyncResolutionRunnerTest:96` | |
| TC-RESL-060 | ★러너 실패 정리 — 승자 보호 선점검 | PASS | [정적] `:98-113` — `isAlreadyFinalized` 가 cleanup(`:127`)·`markRawDataFailed`(`:150`) **양쪽보다 먼저**. 재조회 예외는 보수적 false. [테스트] `AsyncResolutionRunnerTest:145 실패했지만_이미_승자가_확정(Y)했으면_cleanup도_FAILED전이도_aug해제도_스킵한다(M-1_승자산출물보호)` · `:234` | |
| TC-RESL-061 | 러너 실패 정리 — cleanup+슬롯해제+FAILED | PASS | [정적] `:115-150` 순서 — WARN(`:119`) → `resolutionMetrics.finalizeFailed()`(`:121`) → cleanup(`:129`) → `releaseReservedAug`(`:145`) → `markRawDataFailed`(`:150`). 예약 해제 예외도 FAILED 전이를 막지 않음(`:146-149`). [테스트] `AsyncResolutionRunnerTest:111/:128/:164/:182/:199/:218` | |
| TC-RESL-062 | cleanup 원본 미삭제 보장 | PASS | [정적] `ResolutionFileMaterializer.java:98-154` — 삭제 대상은 `frames/deid/{newRawSn}`(`:108`)·레거시 `resolution/{newRawSn}`(`:127`)·`videoDst` 뿐. `resolveSafeDir` 가 `isDeidentifiedArtifact` 를 강제해 `frames/raw/**` 는 애초에 해석되지 않는다. [테스트] `ResolutionFileMaterializerTest:102`(같은 base 에 둔 `frames/raw/{rawSn}` 보존 단언). **[실동작]** 파생 8건 생성 후 부모 146 의 `frames/deid` 3장·`frames/raw` 4장 무결 | |
| TC-RESL-063 | @Async 예외 삼킴 | PASS | [정적] `:47-50 runAsync` → `finalizeDerivative` 가 `:81 catch (RuntimeException)` 로 전건 흡수, 재throw 없음 | |
| TC-RESL-064 | createResolutionPending RESL_ 접두 강제 | PASS | [정적] `LsDataAug.java:214-215`(팩토리) → `:227-231 buildResolution` 가 `!augResTypeCd.startsWith(RESL_PREFIX)` 시 `INVALID_INPUT("해상도 파생 코드는 'RESL_' 접두여야 합니다.")` | 전용 단위 테스트 **부재**(호출 픽스처로만 간접 사용) → E-ISSUE-45 |
| TC-RESL-065 | markResolutionGenerated 이중전이 차단 | PASS | [정적] `LsDataAug.java:248-256` — `RESL_` 접두 가드(`:249-252`) + `!STTS_PENDING.equals` 시 `CONFLICT("이미 처리된 해상도 파생 행입니다.")`(`:253-256`). **[실동작 인접]** 확정된 RESL 행에 `POST /v1/augments/{id}/accept`·`/reject` → **400** `"해상도 파생 결과는 검수 대상이 아닙니다."`(aug 2·3·31·34 전건, 상태 불변 확인) | 전용 단위 테스트 부재 → E-ISSUE-45. 근거 드리프트(아래) |
| TC-RESL-066 | ★확정 실패 파생 RAW 고아 정리 | **PARTIAL** | [정적] `ResolutionPersistService.java:237-259` — 잠금 후 Java 4조건 재확인(파생임/`'Y'` 아님/FAILED/프레임 0건) ✓. 그러나 **DELETE 문(`VideoRepository.deleteFailedDerivative:445-453`)에 동봉된 조건은 3개**(`ORGNL_RAW_SN IS NOT NULL`·`DATA_STTS_CD='FAILED'`·`NOT EXISTS 프레임`)로 **`DE_IDENT_YN <> 'Y'` 가 빠져 있다**. javadoc `:232-233` 은 "최종 DELETE 문에도 동일 조건을 SQL 조건으로 함께 걸어"라고 서술. [테스트] `ResolutionPersistServiceTest:274/:289/:303` | 실효 위험은 낮다(같은 REQUIRES_NEW 트랜잭션의 `FOR UPDATE` 잠금 하에서 Java 검사→DELETE 가 이어지고, `'Y'` 확정은 항상 `COMPLETED` 를 동반해 `DATA_STTS_CD='FAILED'` 조건에서 이미 걸러진다). **문서·구현 불일치** → **E-ISSUE-42** |
| TC-RESL-067 | 파일 잔존 시 RAW 행 보존 | PASS | [정적] `AsyncResolutionRunner.java:126-141`(`artifactsClean` 산출·예외 시 false) + `:155-158` `if (!artifactsClean) { WARN; return; }` — `deleteFailedDerivativeRaw` 미호출. 주석 `:125,154` 가 "유일한 DB 포인터 소실 방지"를 명시 | `AsyncResolutionRunnerTest` 에 `deleteFailedDerivativeRaw` 호출/미호출을 단언하는 테스트가 **1건도 없다**(`:128` 은 메트릭만 검증) → E-ISSUE-45 |
| TC-RESL-068 | 레거시 raw base 파생 디렉터리 정리 | PASS | [정적] `ResolutionFileMaterializer.java:119-138` — `legacyDir.startsWith(legacyRoot) && !legacyDir.equals(legacyRoot)` 두 조건 통과 시에만 재귀 삭제, raw base 미설정은 `IllegalStateException` 으로 잡아 **잔존 판정에 미반영**(`:134-137` 주석). **[실동작]** `/app/storage/raw/resolution` 디렉터리 부재(정리 대상 소진) | `ResolutionFileMaterializerTest` 가 `storageRawPath` 를 주입하지 않아 이 분기가 **테스트에서 항상 skip 된다** → E-ISSUE-45 |
| TC-RESL-069 | ★파생 비디오 경로 키에 파생 RAW_SN 포함 | PASS | **[실동작]** DB `RAW_FILE_PATH_NM` 실측 — `/app/storage/deidentified/videos/resolution/**146**/**160**/RESL_720P.mp4`, `…/146/161/RESL_480P.mp4`(부모·파생 두 축 모두 경로에 포함). 같은 (부모,프리셋) 재시도로 만들어진 152 의 480P·720P 도 각각 `152/166/`·`152/171/` 로 분리. 파일시스템에 `.pending` 잔존 **0건**(`find` 실측) → 잠정 경로가 커밋되지 않음 ✓. [정적] `ResolutionReservationPersister.java:111-119,126-134` + `StorageSubtreePolicy.resolutionVideoFile`. [테스트] `ResolutionReservationPathIT:77` | |
| TC-RESL-070 | ★파생 메타 복사 + 미검수 검수행 | PASS | **[실동작]** 부모 146 메타 21건 → 파생 160·161 각 **21건**(전체 복사, `video.*` 포함). 검수행: 부모 146 = `APPROVED` 15건 → 파생 = **`PENDING` 15건**(APPROVED 미승계 ✓). 로그 `metas=21 metaReviews=15`. [정적] `DerivedMetaCopier.java:72-158`(배치 upsert 1회 `:97`, `(metaSn, metaTypeCd)` 선재 skip `:114-117,138-140`). [테스트] `DerivedMetaCopierTest` 6건 + `DerivedMetaCopierIT` 3건 | `video.*` 가 부모와 동일한 것이 정상(비디오 재인코딩 없음) — 확정 정책대로 결함 아님 |
| TC-RESL-071 | ★메타 복사 호출 순서 계약 | PASS | [정적] `ResolutionPersistService.java:134-148` — `markResolutionGenerated`(134-136) → `markDeidentified('Y')`(138) → `markCompleted`(139) → procLog save(143) → **`copyMetaAndReviews`(148)**. `DerivedMetaCopier.java:43-49` 가 역전 시 결과(dirty 유실→영구 미확정)를 명시. **[실동작 간접]** 파생 8건 전부 `DE_IDENT_YN='Y'`+`COMPLETED`+aug ACCEPTED 와 메타 21건이 **동시 성립** = 순서 계약이 실제로 지켜졌다는 증거. [테스트] IT `:350 …(순서계약_HIGH4)` | |

---

## 근거 드리프트

동작 결함이 아니라 **케이스표의 근거 표기가 현행 코드와 어긋난** 건이다.

| ID | 표기된 근거 | 실제 | 성격 |
|---|---|---|---|
| TC-RESL-014 | `VideoController.java:322-325` | `:330 @PostMapping("/{rawSn}/resolution")` · `:332 @PreAuthorize("hasRole('REVIEWER')")` (322-325 는 Swagger `@ApiResponses`) | 라인 |
| TC-RESL-021 | `VideoController.java:349-353` | `:357 @GetMapping("/{rawSn}/resolution")` · `:358 @PreAuthorize` · `:359-362` 핸들러 | 라인 |
| TC-RESL-065 | `LsDataAug.java:248-249` | `:248` 메서드 시그니처 · `:249-252` 는 **RESL_ 접두 가드**. 이중전이 409 는 **`:253-256`** | 라인(가리키는 가드가 다름) |

> 참고: 1차 **E-ISSUE-28**(TC-RESL-013 근거가 클래스 Javadoc)은 케이스표가 라인 없는 `ResolutionPreset.java (enum 상수 3종)` 로 바뀌어 **해소**됐다(실제 상수는 `:26-28`). 그 밖의 E-4/E-5 근거 **59건은 전부 정합**이다.

### 참고 관측 (판정에 영향 없음)

- **파생영상의 `AUG_TYPE_CD` 판별을 조회 API 가 문자열 파싱으로 한다** — `VideoResolutionService.presetOf:172-183` 이 `AugTypeParser.parse(vmsClipId)` 를 쓴다(`LS_DATA_AUG` 조인이 아님). 확정 실패로 예약행이 삭제된 파생도 목록에 남길 수 있는 이점이 있는 반면, CLAUDE.md 가 권한 "문자열 파싱 폐지" 방향과는 반대다. 파서가 이중/구형 접두를 모두 흡수하도록 강화(`AugTypeParser` 마커 위치 기반)돼 현재 오작동은 없다.
- **`AsyncResolutionRunner` 가 `batchAsyncExecutor`(core2/max4/queue50)를 배치 파이프라인과 공유**한다. 해상도 파생을 대량 요청하면 배치 스텝이 큐에서 대기한다. 본 검증(동시 5요청)에서는 지연만 관측되고 실패는 없었다.
- **`GET /v1/videos` 목록에서 파생 RAW 는 정상 제외**된다(실측: 160/161/164/166/171/174/176/177 미노출, 같은 시각 생성된 원본 168/169 는 노출).

---

## 이슈 상세

### [E-ISSUE-41] TC-RESL-033 — 중복 해상도 파생 요청이 409 CONFLICT 가 아니라 **500 INTERNAL_ERROR** 로 나간다

- **심각도**: MEDIUM (API 계약/운영 — 클라이언트가 "중복"과 "서버 장애"를 구분할 수 없고 5xx 알람이 오발화)
- **기대 동작(기대효과)**: 케이스 기대는 `409("동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.")` 이며, 부분 유니크 인덱스가 막은 "이미 존재" 는 클라이언트 정정 가능한 조건이므로 4xx 여야 한다(`api-design.md`: 409 = 충돌/중복).
- **현재 동작(이슈 내용)**:
  - `ResolutionReservationPersister.java:106-109` 는 정확히 409 를 던진다.
    ```java
    } catch (DataIntegrityViolationException e) {
        throw new CustomException(ErrorCode.CONFLICT, "동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.");
    }
    ```
  - 그런데 `VideoResolutionService.createOne:203-208` 이 **모든 `RuntimeException` 을 삼켜** `FAILED` 결과로 바꾸고, `changeResolution:134-140` 이 "CREATED 가 하나도 없으면" `INTERNAL_ERROR`(500)로 승격한다. 요청 프리셋이 1개면 중복 = 전부 실패 = **500**.
  - [실동작] `POST /v1/videos/146/resolution {"presets":["RESL_720P"]}` 재요청 → `500 {"errorCode":"INTERNAL_ERROR","message":"요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."}`. 동시 5요청 시에도 승자 1건 201 / **패자 4건 전부 500**.
  - 로그에는 원인이 남는다(`[Video][Resolution] derivative creation failed … reason=CustomException` + `all presets failed`), 즉 서버는 중복임을 알면서 5xx 로 응답한다.
  - 다중 프리셋 요청에서는 201 + 항목별 `FAILED` 로 나가므로(실측) 여전히 사유를 알 수 없다 — 응답에 사유 코드가 없다(CWE-209 회피 목적의 의도적 설계).
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/v1/videos/146/resolution \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' -d '{"presets":["RESL_720P"]}'   # 1회차 201
  # 동일 명령 재실행 → 500
  ```
- **영향**: FE 가 "이미 만들어져 있음"을 안내하지 못하고 일반 오류로 처리한다. 운영 모니터링에서 정상 중복 클릭이 5xx 로 집계돼 알람 노이즈·SLO 오염. 보안 영향 없음.
- **수정 방향(제안)**: `createOne` 이 `CustomException` 의 `ErrorCode` 를 보존해(예: `CreatedDerivative` 에 추상 사유코드 필드 추가) `changeResolution` 이 "전부 실패이고 전부 CONFLICT" 면 409 로, 그 외 전부 실패면 500 으로 갈라 응답한다. 응답에는 경로·스택이 아닌 **사유 코드만** 싣는다. ⚠ **구현하지 않는다.**

### [E-ISSUE-42] TC-RESL-066 — `deleteFailedDerivative` DELETE 문에 `DE_IDENT_YN <> 'Y'` 조건이 빠져 javadoc 의 "동일 조건 동봉" 서술과 불일치

- **심각도**: LOW (문서·구현 불일치 / 심층방어 결손 — 현재 실피해 경로는 확인되지 않음)
- **기대 동작(기대효과)**: `ResolutionPersistService.java:230-233` javadoc — "다음 중 하나라도 어긋나면 삭제하지 않는다 — ①파생이 아님 ②이미 확정(deIdntfYn='Y') ③상태가 FAILED 가 아님 ④프레임이 이미 적재됨. **최종 DELETE 문에도 동일 조건을 SQL 조건으로 함께 걸어**, 검사~삭제 사이에 상태가 바뀐 행은 0건 삭제된다."
- **현재 동작(이슈 내용)**: `video/repository/VideoRepository.java:445-453`
  ```sql
  DELETE FROM LS_DATA_RAW
   WHERE RAW_SN = :rawSn
     AND ORGNL_RAW_SN IS NOT NULL
     AND DATA_STTS_CD = 'FAILED'
     AND NOT EXISTS (SELECT 1 FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn)
  ```
  4조건 중 **②`DE_IDENT_YN <> 'Y'` 가 없다.** 그 조건은 Java 선검사(`ResolutionPersistService.java:246-249`)에만 존재한다.
- **재현/확인 경로**: `grep -n "deleteFailedDerivative" -A10 backend/src/main/java/kr/co/cudo/authoring/video/repository/VideoRepository.java`
- **영향**: 현실적 위험은 낮다 — ①선검사와 DELETE 가 같은 `REQUIRES_NEW` 트랜잭션 안에서 `findByRawSnForUpdate` 잠금을 쥔 채 연속 실행되고 ②확정 경로(`persist:138-139`)가 `markDeidentified('Y')` 와 `markCompleted()` 를 항상 함께 수행하므로 `'Y'` 인 행은 `DATA_STTS_CD='FAILED'` 조건에서 이미 걸러진다. 다만 향후 상태 전이가 분리되면 조용히 깨지는 형태의 결손이고, **javadoc 이 사실과 다르다**는 점이 남는다.
- **수정 방향(제안)**: DELETE 문에 `AND (DE_IDENT_YN IS NULL OR DE_IDENT_YN <> 'Y')` 를 추가하거나, javadoc 을 "SQL 은 3조건, `'Y'` 는 FAILED 조건이 함의" 로 정정한다. ⚠ **구현하지 않는다.**

### [E-ISSUE-43] TC-RESL-021 — 확정 실패한 파생은 **상태 조회 API 에서도 사라진다**(E-ISSUE-24 잔여)

- **심각도**: MEDIUM (관측성 — 1차 지적의 핵심 시나리오가 여전히 남음)
- **기대 동작(기대효과)**: 1차 E-ISSUE-24 의 요지는 "REVIEWER 가 파생 생성 성공/실패를 확인할 수 있어야 한다" 이고, TC-RESL-021 은 `DATA_STTS_CD=FAILED` → 조회 응답 `FAILED` 매핑을 기대한다.
- **현재 동작(이슈 내용)**:
  - 조회 API 는 신설됐고 `IN_PROGRESS`/`COMPLETED` 는 실동작으로 확인된다(`VideoResolutionService.statusOf:185-194`).
  - 그러나 **정상적인 실패 경로에서는 그 행이 남지 않는다.** `AsyncResolutionRunner.handleFailure:150-164` 가 `markRawDataFailed` 직후 `deleteFailedDerivativeRaw` 를 호출하고, 그 조건(파생·FAILED·프레임 0건)은 Phase A/B 실패의 **전형적 상태와 정확히 일치**한다(Phase C 는 같은 트랜잭션이라 프레임이 롤백된다). 결과적으로 `FAILED` 는 **cleanup 이 실패해 파일이 잔존한 경우에만** 조회된다(`:155-158`).
  - 코드가 이 트레이드오프를 스스로 인정한다 — `:115-118` "여기서부터는 파생 RAW·예약행이 정리되어 **실패 흔적이 DB 에 남지 않는다** … WARN 로그 + 메트릭이 유일한 관측 수단이다".
  - 즉 E-ISSUE-23(고아 누적 제거)과 E-ISSUE-24(실패 가시화)가 **서로를 상쇄**한다. 예약 단계 실패(중복 등)는 요청 응답에 `FAILED` 로 즉시 보이지만(실측), 그 응답을 놓치면 사후 조회 수단이 없다.
- **재현/확인 경로**: `POST /v1/videos/{approvedRawSn}/resolution` 로 확정 실패를 유발한 뒤 `GET /v1/videos/{rawSn}/resolution` → 해당 프리셋이 목록에 없음. 로그만 `[AsyncResolutionRunner] derivative discarded — finalize failed, reservation released rawSn=… dataAugSn=…` + 메트릭 `resolution.finalize.failed`.
- **영향**: REVIEWER 가 "3종 요청했는데 2종만 있다"를 화면에서 인지할 수 없다. 운영자는 로그/메트릭 접근이 필요하다. 보안 영향 없음.
- **수정 방향(제안)**: ①고아 RAW 를 삭제하는 대신 **경량 실패 이력 테이블**(또는 `LS_BATCH_PROC_LOG` 재사용)에 `(parentRawSn, preset, 사유코드, 시각)` 을 남기고 조회 API 가 그 이력을 합쳐 응답하거나, ②`GET /v1/videos/{rawSn}/resolution` 응답에 "요청됐으나 산출물 없음" 을 표현할 수 있도록 프리셋 3종을 항상 나열하고 미존재를 `NOT_CREATED` 로 표기한다. ⚠ **구현하지 않는다.**

### [E-ISSUE-44] TC-RESL-001 — `VMS_CLIP_ID` 의 `_RESL_RESL_` 이중 접두 드리프트가 신규 생성분에도 그대로 남아 있다 (1차 E-ISSUE-25 미해소)

- **심각도**: LOW (데이터 품질 — 기능 영향 없음)
- **기대 동작(기대효과)**: 파생 식별자에 프리셋 코드가 1회만 들어간다.
- **현재 동작(이슈 내용)**: `video/entity/LsDataRaw.java:226`
  ```java
  raw.vmsClipId = parent.getVmsClipId() + "_RESL_" + goalResCd + "_" + System.currentTimeMillis();
  ```
  `goalResCd` 가 이미 `RESL_720P` 이므로 결과가 `…_RESL_RESL_720P_…` 가 된다. [실동작] 본 검증 신규 생성분 전건 — `DEV-CLIP-9502_RESL_RESL_720P_1785437782919`, `DEV-CLIP-9509_RESL_RESL_480P_…`.
- **재현/확인 경로**: `select vms_clip_id from ls_data_raw where orgnl_raw_sn is not null and vms_clip_id like '%RESL%';`
- **영향**: 기능 영향은 **없다** — `AugTypeParser`(`video/util/AugTypeParser.java:64-80`)가 "가장 오른쪽 마커 뒤 토큰" 규칙으로 이중/구형(`_RES_RES_`) 접두를 모두 정규화하고, 회귀 테스트(`AugmentResolutionResultTest:217/:244`)도 있다. 남는 것은 ①식별자 가독성 ②`VMS_CLIP_ID` 를 사람이 읽는 감사·운영 문서에서의 혼란 ③파서를 계속 유지해야 하는 부채다.
- **수정 방향(제안)**: 접두를 `"_"` 로 바꾸거나(신규 생성분만 정상화, 파서는 하위호환용으로 존치), 파생 종류 판별을 `LS_DATA_AUG.AUG_TYPE_CD` 조인으로 일원화해 문자열 파싱을 폐지한다. ⚠ **구현하지 않는다.**

### [E-ISSUE-45] TC-RESL-064/065/067/068 — 확정·정리 가드 4종에 전용 테스트가 없다 (1차 E-ISSUE-29 의 잔여 축)

- **심각도**: LOW (검증 신뢰도)
- **기대 동작(기대효과)**: 상태 전이·정리 가드마다 실행 경로를 고정하는 테스트가 있어야 회귀가 감지된다. 1차 E-ISSUE-29 가 지목한 3건(procLog 부재 404 / 중복 videoFrameNo / mtime 교체)은 이번 회차에 **전부 신설·해소**됐다(`ResolutionSnapshotServiceTest:238,:256` · `ResolutionPersistServiceTest:252`).
- **현재 동작(이슈 내용)**: 아래 4건은 여전히 단언하는 테스트가 없다.
  1. **TC-RESL-064** `LsDataAug.buildResolution:227-231` 의 `RESL_` 접두 400 — 테스트에서 팩토리는 정상값으로만 호출된다(`grep createResolutionPending` 결과 12곳 전부 `AUG_RESL_720P`).
  2. **TC-RESL-065** `LsDataAug.markResolutionGenerated:253-256` 의 이중 전이 409 — `@DisplayName` 전수 스캔 결과 대응 테스트 0건.
  3. **TC-RESL-067** `AsyncResolutionRunner:155-158` "파일 잔존 시 RAW 보존" — `AsyncResolutionRunnerTest` 에 `deleteFailedDerivativeRaw` 를 `verify`/`never()` 로 단언하는 케이스가 **0건**(`:128` 은 `cleanupFailed` 메트릭만 본다).
  4. **TC-RESL-068** `ResolutionFileMaterializer:119-138` 레거시 raw base 정리 — `ResolutionFileMaterializerTest` 가 `storageRawPath` 를 주입하지 않아 이 블록이 **항상 `IllegalStateException` 으로 skip** 된다(테스트에서 한 번도 실행되지 않는다).
- **재현/확인 경로**:
  ```bash
  grep -rn "deleteFailedDerivativeRaw" backend/src/test/java/kr/co/cudo/authoring/video/AsyncResolutionRunnerTest.java   # 0건
  grep -rn "storageRawPath" backend/src/test/java/kr/co/cudo/authoring/video/ResolutionFileMaterializerTest.java          # 0건
  grep -rn "markResolutionGenerated" backend/src/test/java/kr/co/cudo/authoring                                           # 단언 0건
  ```
- **영향**: 결함이 CI 를 통과한다. 특히 ③은 "고아 파일 방지"라는 안전 불변식이고 ④는 원본 저장소에 PII 사본이 잔존하는 것을 막는 정리 로직이라, 회귀 시 조용히 무력화된다.
- **수정 방향(제안)**: 각각 ①비-RESL 코드 400 ②ACCEPTED 재전이 409 ③`cleanup=false` 일 때 `verify(persistService, never()).deleteFailedDerivativeRaw(any())` ④`storageRawPath` 주입 후 `{rawBase}/resolution/{newRawSn}` 삭제·`{rawBase}/resolution` 루트 보존 단언을 추가한다. ⚠ **구현하지 않는다.**

### [E-ISSUE-46] TC-RESL-044/045 — 파생 라벨이 AI 메타(`LS_DATA_LBL_AI_INFO`)·등록자를 승계하지 않아 오토라벨 산출물이 수동 라벨처럼 보인다

- **심각도**: LOW (데이터 계보 — 현재 export/뷰 소비 경로에는 영향 없음)
- **기대 동작(기대효과)**: 파생영상은 부모 라벨의 **복사본**이므로, 그 라벨이 AI 가 만든 것인지(자동여부·신뢰도·출처)가 함께 따라가야 계보가 유지된다. 같은 리포의 버전 롤백 정책은 이 점을 명시적으로 요구한다(CLAUDE.md — "`LBL_SN`·AI 메타(`AUTO_LBL_YN`/신뢰도/출처)·`TRCK_ID` 까지 보존 복원").
- **현재 동작(이슈 내용)**: `batch/entity/LsDataLbl.java:333-348 copyForNewSrcScaled` 가 복사하는 필드는 `srcSn / lblTypeCd / labelId / labelNm / pointCn / trackId` 6개뿐이다.
  - `LS_DATA_LBL_AI_INFO`(별도 테이블)는 어느 파생 경로에서도 복사되지 않는다. [실동작] 부모 156 의 `ls_data_lbl_ai_info` **7행** ↔ 파생 174 **0행**.
  - `REG_USER_NO` 도 빌더에 없어 파생 라벨은 전부 `null`(실측).
  - 증강 경로(`webhook/service/AugmentExtractPersist.java:125` → `LsDataLbl.copyForNewSrc:300-307`)도 **동일하게 미복사**라 두 파생 경로 사이의 드리프트는 아니다. `:297-298` 주석은 "Transient 필드(autoLblYn, confScore, lblSrcCd)는 DB 미저장이므로 복사 대상 아님" 이라고만 서술하고, 별도 테이블인 `LS_DATA_LBL_AI_INFO` 는 언급하지 않는다.
- **재현/확인 경로**:
  ```sql
  select 'parent' , count(*) from ls_data_lbl_ai_info i join ls_data_lbl l on l.lbl_sn=i.data_lbl_sn
    join ls_data_src d on d.src_sn=l.src_sn where d.raw_sn=156
  union all select 'deriv', count(*) from ls_data_lbl_ai_info i join ls_data_lbl l on l.lbl_sn=i.data_lbl_sn
    join ls_data_src d on d.src_sn=l.src_sn where d.raw_sn=174;    -- 7 vs 0
  ```
- **영향**: 현재 export JSON 빌더는 자동여부·신뢰도를 내보내지 않으므로(`grep autoLblYn|confScore backend/.../dataset/export/` 0건) **학습데이터 산출물에는 영향이 없다.** 남는 영향은 ①파생영상 라벨링 화면에서 AI 라벨/수동 라벨 구분 불가 ②품질 통계(오토라벨 비율)에서 파생분이 전량 수동으로 집계 ③향후 export 계약에 자동여부가 추가되면 조용히 오표기된다.
- **수정 방향(제안)**: 파생 라벨 복사 시 `LS_DATA_LBL_AI_INFO` 를 함께 복제(신뢰도·출처 유지, `LBL_SRC_CD` 는 그대로 두거나 `DERIVED` 파생값 추가)하고 `REG_USER_NO` 를 승계한다. 증강·해상도 두 경로가 같은 헬퍼를 쓰므로 한 곳만 고치면 된다. **또는** "파생 라벨은 계보를 승계하지 않는다"를 명시적 계약으로 문서화한다. ⚠ **구현하지 않는다.**

---

# E 클러스터 part4 (E-6·E-7) 2차 검증 결과

> 대상: `docs/test-cases/E-augment-resolution-export-meta.md` §E-6(23) + §E-7(18) = **41건**
> 환경: 로컬 도커 스택 · backend `localhost:18081`(HEAD `ca3c712b`) · PG `:5432`(스키마 `public`)
> 검증 시각: 2026-07-31 04:00~04:15 KST · backend 재기동/재빌드/빌드·테스트 실행 **0회** · 소스 수정 **0건**
> 폐기(`~~취소선~~`) 행: E-6·E-7 구간에는 **없음**(폐기는 E-5 이전 TC-RESL 구간에만 존재 — 집계 영향 없음)

## 집계

| 섹션 | 검증 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| E-6 Export 오케스트레이션 | 23 | 22 | 1 | 0 | 0 | 0 | 0 |
| E-7 Export JSON 포맷 | 18 | 17 | 0 | 1 | 0 | 0 | 0 |
| **합계** | **41** | **39** | **1** | **1** | **0** | **0** | **0** |

근거 확인 내역: **[실동작] 30건 · [정적] 11건**
신규 이슈 **2건** — HIGH 1 / MEDIUM 1

---

## 1차 이슈 해소 대조

| 1차 항목 | 1차 판정 | 2차 실측 | 결론 |
|---|---|---|---|
| **`E-ISSUE-22` — `FrameSource` DEIDENTIFIED 의 rawBase 폴백(1차 최고위험, CWE-359 fail-open)** | HIGH 미해소 | `FrameSource.java:75` 가 `Path base = (kind == ORIGINAL) ? rawBase : deidBase;` **단일 base** 로 되돌아왔고(구 `candidateBases = {deidBase, rawBase}` 삭제), DEIDENTIFIED 분기는 `StorageSubtreePolicy.verifyDeidentifiedFile` 단일 판정기 + `toRealPath` 로 서브트리(`frames/deid`)까지 강제(`:89-101`). ORIGINAL 분기도 역방향 혼입을 `isDeidentifiedArtifact` 로 차단(`:126-130`). 회귀 가드 `FrameSourceTest` 19건(동일 base 문자열·심링크 바꿔치기·TOCTOU 포함) | **✅ 해소** |
| 〃 실측 반증 | — | **전 export 버전 폴더 md5 전수 스윕**: `orgnl/*.jpg` 와 `deid/*.jpg` 가 동일 해시인 쌍 **0건**(스캔 대상 = `/app/storage/raw/seed/*/v*/`, rawSn 126·132·136·146·152·153·156·173 의 전 버전) | **✅ PII 격리 실증** |
| **`UNCERTAINTIES #19` — `DatasetExportWriter`/`TxService` 미열람** | 미확인 | 전 메서드 `@Transactional(REQUIRES_NEW)` 실측(`DatasetExportTxService:100,173,186,192,224,242,257,280`), `finalizeUnlessUnderDeidentReport`(`:224-239`) 가 RAW 잠금 하 재판정 → 차단 시 export 행 `deleteById`, `claimForRetry` 조건부 UPDATE(`LsDatasetExportRepository` native UPDATE) | **✅ 해소** |
| **"E-32 dead-letter 프로덕션 호출자 0건"** | — | 1차 `ISSUES.md` 에 `E-ISSUE-32` 는 **존재하지 않음**. 지시의 취지에 해당하는 건은 `E-ISSUE-06`(증강 `markDeadLetter()` 호출자 0건 — **E-part1/E-2 스코프**). export 축의 대응물인 `DatasetExportFailureRecoverer` 는 **프로덕션 도달 확인**: Quartz `DatasetExportFailureRecoveryJob` 이 900초 주기로 실제 tick(`03:00:32 / 03:15:32 / 03:30:32 / 03:45:32 / 04:00:32` — `[DatasetExportRecovery] no retryable failed export`) | **본 파트 대상 아님 / export 축은 도달 가능** |
| `E-ISSUE-41` — 파생 orgnl·deid 프레임 경로 동일 → 2벌 바이트 동일·anonymity 오표기 | HIGH | 정책 A 로 전환됨. 파생(rawSn 178) 프레임은 `SRC_FILE_PATH_NM=null`, `DE_IDNTF_SRC_FILE_PATH_NM` 만 보유 → `hasNoOriginalFrames` 판정으로 **ORIGINAL 벌 미생성**(`deid/` 1벌만) → 오표기 경로 소멸 | **✅ 해소** |
| `E-ISSUE-43` — export FAILED 시 **빈 산출 디렉터리 잔존** + retention 미구현 | MEDIUM | ①retention: **의도된 미구현이 확정 정책**(TC-EXPORT-043) → 결함 아님 ②빈 디렉터리: `totalWritten==0` FAILED(rawSn 173 v5) 후 `/app/storage/raw/seed/173/v5/{orgnl,deid}` 가 **빈 디렉터리로 잔존**(실측) — `purgeThisRunVersionDir` 는 신고 차단 경로에서만 호출됨 | **부분 미해소(빈 디렉터리)** — 신규 이슈로 승격하지 않음(LOW·데이터 위생, 뷰는 최신 SUCCEEDED 조인이라 노출 안 됨) |
| `E-ISSUE-44` — 최상위 키 `event_annotation` ↔ `event` 이원화 | LOW~MED | 현 코드·현 산출물 **전부 `event`**(`NiaAnnotationDoc.java:32` `@JsonProperty("event")`). 이번 회차에 새로 만든 산출물 전수(126 v1·v2, 173 v1~v8, 178 v1)에 `event_annotation` 키 **0건**. 카탈로그 TC-EXPORT-020/021 도 `event` 로 정정 완료 | **✅ 해소**(구 v1 산출물은 이 스택에 부재) |

---

## ★export 산출물 실측

`docker exec klid-backend` 로 파일을 직접 열어 대조했다.

| rawSn | 성격 | 버전 폴더(디스크) | orgnl 파일 | deid 파일 | orgnl↔deid md5 | `EXPORT_PATH_NM` | 최상위 JSON 키 |
|---:|---|---|---:|---:|---|---|---|
| **126** | 완주 기준(참조 데이터, **무변경 유지**) | `v1`, `v2` | 3 jpg+3 json | 3 jpg+3 json | **전부 상이**(`c155a39c…`↔`064376b2…` 등 3쌍) | `/app/storage/raw/seed/126`(영상 루트) | info·dataset·licences·video·**event**·image·annotations·categories·type |
| **173** | 본 검증 전용 신규(`E6-CLIP-01`) | `v1 v2 v3 v4 v5 v7 v8`(v6=경로거부라 폴더 미생성) | 각 3(v4는 2) | 각 3 | 전부 상이 | `/app/storage/raw/seed/173` | 동일 9키 |
| **178** | 173 의 해상도 파생(RESL_720P) | `v1` | **없음(정책상 미생성)** | 3 jpg+3 json | N/A | `/app/storage/deidentified/videos/resolution/173/178/178` | 동일 9키 |
| 132·136·146·152·153·156 | 타 에이전트 생성분 | 최대 `v7`(156), `v6`(153) | — | — | 전수 스윕 결과 동일 md5 **0쌍** | 각 영상 루트 | — |

- **폴더 계약**: `{dirname(RAW_FILE_PATH_NM)}/{rawSn}/v{n}/{orgnl|deid}/` + 프레임별 `NNNN.jpg`/`NNNN.json`(4자리 zero-pad). 비식별 영상은 형제 `deid/` 에 co-locate(`126/deid/sample-cctv-1080p-mask.mp4`) — 관제가 **경로 1개**로 전 버전 + 비식별 영상을 본다.
- **파일명 조합·추측 없음**: 비식별 영상 파일명은 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 값을 읽어 쓴다(mock KPST 산출 `{stem}-mask.mp4`). export JSON `dataset.src_path`·`video.filename` 도 그 값의 basename(`sample-cctv-1080p-mask.mp4`)이며, 고정 `deidentified.mp4` 조합 흔적 0건.
- ⚠ **파생의 `EXPORT_PATH_NM` 은 `…/178/178` 로 한 단계 중첩**된다. 파생 `RAW_FILE_PATH_NM` 이 이미 `…/resolution/173/178/RESL_720P.mp4` 라 `dirname + rawSn` 규칙상 자연 도출된 결과이며 규약 위반 아님(관제는 그 경로 하위에서 `v1..vN` 을 본다).
- **전 버전 보존**: 173 은 8회 산출 중 6개 버전 폴더가 그대로 남아 있고 삭제 코드는 `purgeThisRunVersionDir`(신고 중단 전용)·tmp cleanup 둘뿐. retention 잡 **0건**.

---

## ★재생성 트리거 7경로 실측

확정 정책(승인 후 수정 7경로 → `v{n+1}` 전량 재생성)의 발행 플래그를 코드로 전수 확인하고, 그중 3경로는 실동작으로 `v{n+1}` 생성을 확인했다.

| # | 수정 경로 | `TaskModifiedEvent.exportRegenerated` | file:line | `v{n+1}` 생성 | 통지 |
|---:|---|:--:|---|---|---|
| 1 | 라벨 수정 `LabelService` | **true** | `LabelService.java:422-424` | [실동작] 타 에이전트 rawSn 156/132 flush `regen=true` → v{n+1} SUCCEEDED | TASK_MODIFIED |
| 2 | 트랙 편집 `TrackEditService` | **true** | `TrackEditService.java:326` | [정적] | — |
| 3 | 트랙 병합 `TrackMergeService` | **true** | `TrackMergeService.java:200-201` | [정적] | — |
| 4 | 버전 롤백 `VersionService` | **true** | `VersionService.java:518-519` | [정적] | — |
| 5 | 촬영환경 `EnvironmentMetaService` | **true** | `EnvironmentMetaService.java:125-126` | [실동작] 173 재동결 후 flush `regen=true`(신고 구간이라 산출은 게이트 차단 — 아래 TC-EXPORT-016) | 보류됨 |
| 6 | 프레임 설명 `FrameDescriptionService` | **true** | `FrameDescriptionService.java:60-61` | [실동작] **173 v2 생성**(04:05:18) + JSON `image.description="E6 verification frame description"` | TASK_MODIFIED 발송 |
| 7 | 프레임 PII 메타 `FramePrivacyMetaService` | **true**(single `:154-155` / bulk `:131-132`) | 〃 | [실동작] **173 v8 생성**(04:13:40) + `image.pseudonymity/privacy_included="Y"` | — |
| (예외) | `EvntAnnoService`(event_annotation 수정) | **false** | `EvntAnnoService.java:128-129` (4-arg 생성자) | 재생성 없음 | CLAUDE.md 예외 조항 그대로 — **결함 아님** |

- 디바운스 윈도우 60초 + flush tick 10초 → 실측 지연 **68초·82초**(04:04:10→04:05:18, 04:12:56→04:13:40).
- flush 스케줄러는 `ControlNotifyDebouncer` 자체 소유 데몬 스레드(`control-notify-debounce-flush`)로 tick — `@EnableScheduling`/타 토글 비의존 실측 확인.
- **export→통지 직렬화 실측**: `export succeeded rawSn=173 version=2`(04:05:18.187) → `TASK_MODIFIED sent`(04:05:18.208). 역순 0건.

---

## E-6 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---|---|
| TC-EXPORT-001 | 검수승인 AFTER_COMMIT 강제 재생성 | PASS | [실동작] `[DatasetExportBridge] review approved rawSn=173 — triggering dataset export (force regenerate)` → `runApprovalAsync` (04:03:35.016~.017) | `DatasetExportBridge.java:36-43` 정합 |
| TC-EXPORT-002 | 승인 경로 멱등 skip 미적용 | PASS | [실동작] 무수정 재승인이 새 버전을 만든 3사례 — rawSn **152 v1~v4 전부 `content_hash=a00d637c…` 동일**, 153 v1/v2 동일(`f0c6e962…`), 156 v1/v5/v6/v7 동일(`27dc8f6d…`) | force=true 경로에 skip 미적용 확증 |
| TC-EXPORT-003 | 재동결 경로 멱등 skip | PASS | [정적] `DatasetExportService.java:152-156` + `ExportPreparation.isUnchangedFromLastExport()`, baseline=SUCCEEDED∪PARTIAL(`LsDatasetExportRepository.findFirstByDataRawSnAndExportSttsCdInOrderByExportVerNoDesc`). 테스트 `DatasetExportServiceTest:280,551`·`DatasetExportTxServiceTest:109,128`·`DatasetExportE2EIT:623` | `DatasetReExportEvent` 는 **발행처 0건(휴면 리스너)** — 실동작 재현 불가, 코드 주석에 사유 명시 |
| TC-EXPORT-004 | 정상 산출 SUCCEEDED + EXPORT_PATH_NM = 영상 루트 | PASS | [실동작] 173 v1 `SUCCEEDED, frame_cnt=6, EXPORT_PATH_NM=/app/storage/raw/seed/173`(버전 루트 아님). 그 아래에 `deid/`·`v1`·`v2`… 공존 | `DatasetExportTxService.java:174-183` 주석과 일치 |
| TC-EXPORT-005 | 일부 프레임 부재 PARTIAL | PASS | [실동작] 173 원본 프레임 1장 삭제 후 재산출 → `frames written kind=ORIGINAL written=2 skipped=1` → `partial export … written=5 skipped=1` → **v4 PARTIAL, frame_cnt=5**. 디스크 `v4/orgnl` 2쌍 / `v4/deid` 3쌍 | `dataset.export.skipped_frames` 증가 |
| TC-EXPORT-006 | 산출 0건 FAILED | PASS | [실동작] 173 전 프레임 이미지 삭제 후 재산출 → `nothing produced — marked FAILED rawSn=173 version=5` → **v5 FAILED, frame_cnt=null**, 작업상태 APPROVED 유지(승인 롤백 없음) | ⚠ 이 경로에서 **통지는 나갔다** → E-ISSUE-61(TC-EXPORT-018). 빈 `v5/{orgnl,deid}` 디렉터리 잔존(1차 E-ISSUE-43 부분 미해소) |
| TC-EXPORT-007 | 파생영상 = ORIGINAL 벌 미생성(PARTIAL 아님) | PASS | [실동작] 해상도 파생 rawSn **178** 승인 → `original kind skipped — derivative video has no original frames rawSn=178 version=1 reason=DERIVATIVE_NO_ORIGINAL` → deid 3건만 written → **SUCCEEDED**(PARTIAL 아님), 디스크에 `orgnl/` 폴더 자체 없음 | `DatasetExportService.java:187-199,330-336` |
| TC-EXPORT-008 | 버전 채번 UK 충돌 재시도 | PASS | [정적] `DatasetExportService.java:367-377` `insertWithRetry` MAX 3회 + `saveAndFlush` 로 UK 즉시 유발(`DatasetExportTxService.java:173-183`). 테스트 `DatasetExportServiceTest:345` | 동시 승인 실측: 152 가 0.17초 간격 4회 승인 → v1~v4 충돌 없이 채번 |
| TC-EXPORT-009 | 버전 채번 재시도 소진 | PASS | [정적] `:174-178` null → `OUTCOME_VERSION_EXHAUSTED` + abort. 테스트 `DatasetExportServiceTest:364,533` | 실동작 유발 불가(정상 경로에서 3회 연속 UK 위반 미발생) |
| TC-EXPORT-010 | 파일쓰기 실패 시 승인 불변 | PASS | [정적] `:234-241` inner catch → `markFailed` + `cause={클래스명}` 만 로깅(경로 원문 미노출), 예외 미전파. 테스트 `DatasetExportServiceTest:264`·`DatasetExportE2EIT:402` | 승인 불변은 TC-006/015 실동작으로도 확인 |
| TC-EXPORT-011 | 입력 부재 NO_INPUT | PASS | [실동작] 프레임 0건인 APPROVED 영상 rawSn **155·151** → `[DatasetExport] no frames — skip export rawSn=155/151`, `ls_dataset_export` 행 **미생성**, metric `outcome=no_input` 태그 존재 | `:143-147` |
| TC-EXPORT-012 | 메트릭 outcome 정확히 1회 | PASS | [실동작] `dataset.export.result` COUNT=**27** ↔ (INSERT 행 25 + no_input 2) 정확히 일치, `dataset.export.duration` COUNT=**27** 동일. 태그 값 실관측 `completed`/`no_input`/`deident_blocked` | `finally` 단일 지점(`:255-263`) — 조기 return·예외 이탈 모두 1회 |
| TC-EXPORT-013 | 브릿지 조건부 비활성화 | PASS | [정적] `DatasetExportBridge.java:29-30` `@ConditionalOnProperty(prefix="authoring.dataset-export", name="enabled", havingValue="true", matchIfMissing=true)`. 테스트 `DatasetExportBridgeBeanConditionTest` 3건 | 현 형상은 `DATASET_EXPORT_ENABLED:true` |
| TC-EXPORT-014 | 폴더 구조 계약 (co-locate) | PASS | [실동작] `/app/storage/raw/seed/173/v{n}/{orgnl,deid}/NNNN.{jpg,json}` + 형제 `deid/…-mask.mp4`. 고정 `labeling_root` 경로 **0건**(스토리지 전수 확인) | `base-strategy: co-locate` |
| TC-EXPORT-015 | 산출 base 3중 가드 + fail-secure | PASS | [실동작] `ls_data_raw(173).raw_file_path_nm` 을 `/etc/e6-not-allowed/x.mp4`(허용 마운트 밖)로 바꾼 뒤 산출 트리거 → `export base rejected — marked FAILED rawSn=173 reason=FORBIDDEN` → **v6 행 INSERT + FAILED + `EXPORT_PATH_NM=null`**, 기본 루트 폴백 0, 로그에 경로 원문 미노출. 검증 후 원복 | `:162-169,345-360`. ⚠ 이 경로에서도 통지 발송됨 → E-ISSUE-61 |
| TC-EXPORT-016 | 비식별 신고 게이트 = 산출 자체 skip | PASS | [실동작] 173 에 신고 OPEN(`DE_IDENT_YN='F'`) 상태에서 촬영환경 수정 → flush `regen=true` → `[DatasetExport] export blocked — deident report open rawSn=173`(04:06:48.247) → `async export failed … cause=CustomException` → **`ls_dataset_export` 행 미증가(FAILED 아님)**, metric 태그 `deident_blocked` 신규 출현, **TASK_MODIFIED 미발송** | `:136-141` 단일 진입부 게이트 |
| TC-EXPORT-017 | 쓰기 중 신고 접수 = 마감 차단 + v{n} 폴더 삭제 | PASS | [정적] `DatasetExportTxService.java:224-239`(RAW 잠금 하 재판정 → `deleteById` → false) + `DatasetExportService.java:247-254`(`purgeThisRunVersionDir` → 예외 이탈). 삭제 범위는 `v{version}` 단일이며 3중 경로가드(`resolveVideoRoot`→`resolveUnder`→`verifyRealPathUnder`) 통과 시에만. 테스트 `DatasetExportDeidentReportGateIT:237,258` | 쓰기 도중 신고 커밋 창을 실동작으로 좁히기 불가(산출 0.02초) |
| TC-EXPORT-018 | **export 성공 후에만 통지** | **FAIL** | [실동작] `nothing produced — marked FAILED rawSn=173 version=5`(04:10:01.352) **직후** `TASK_COMPLETED sent rawSn=173`(04:10:01.362). base 거부 FAILED(v6)에서도 동일(04:10:43.758→.770) | **E-ISSUE-61** — 성공 경로·게이트 차단 경로는 정상 |
| TC-EXPORT-019 | 승인 후 수정 = 재export 후 통지 | PASS | [실동작] `async re-export(+notify) starting rawSn=173 forceRegenerate=true` → `frames written`×2 → `export succeeded … version=2`(04:05:18.187) → `TASK_MODIFIED sent … reExport=true`(04:05:18.208). 콜백은 `Runnable`(`AsyncDatasetExportRunner.java:91`) | 성공 시에만 콜백 실행 — 실패 시 미실행은 E-ISSUE-61 참조 |
| TC-EXPORT-040 | 실패 export 회수 후 통지 재개 | PASS | [정적+실동작] Quartz `DatasetExportFailureRecoveryJob` 900초 주기 tick **실관측**(03:00·03:15·03:30·03:45·04:00 `no retryable failed export`). `claimForRetry` 는 `RTY_NMTM+1, RTY_DT=now WHERE EXPORT_STTS_CD='FAILED' AND RTY_NMTM<:max AND (RTY_DT IS NULL OR RTY_DT<:cutoff)` 조건부 UPDATE(2노드 중복 차단). 신고 구간 건은 **클레임 이전**에 제외(`DatasetExportFailureRecoverer.java:141-144`). 성공 시 `runApprovalAsync` 로 완료 이벤트 재발행(`:157`). 테스트 `DatasetExportFailureRecoveryIT` 4건 | 실제 재시도 발화는 미재현 — 유예 10분·주기 15분이라 대기 창 내 다른 트리거(신고 resolve)가 먼저 v7 SUCCEEDED 를 만들어 FAILED 앵커가 최신에서 밀려남 |
| TC-EXPORT-041 | 신고 해소 시 보류분 복구 | PASS | [실동작] 173 `'F'→'Y'` resolve(04:07:36) → `[DatasetExportBridge] deident report resolved rawSn=173 — re-triggering withheld export/notify` → `runApprovalAsync`(force=true) → **v3 SUCCEEDED** → `TASK_COMPLETED sent`. 총 4회 재현(v3·v7 등) | 팬아웃 없음(그 영상 하나) — 파생 178 은 무영향 |
| TC-EXPORT-042 | 재export 트리거는 control-notify 토글과 무관 | PASS | [정적] `ControlNotifyDebouncer` 에 `@ConditionalOnProperty` **없음**(항상 빈 등록), `notifyService`/`metrics` 만 `@Nullable` 주입. `send()`(`:294-306`)가 `exportRegenerated=true` 면 `notifyService==null` 이어도 `runReExportThenNotify(rawSn,true,null)` 를 항상 호출. flush 스케줄러는 `authoring.dataset-export.regen-flush.enabled`(자체 토글, 기본 true) | 현 형상 `CONTROL_NOTIFY_ENABLED=true` 라 off 상태 실증 불가 |
| TC-EXPORT-043 | retention 정리 로직 없음 | PASS | [실동작] rawSn **173 v1~v8**(v6 제외 6폴더)·**156 v1~v7**·**153 v1~v6** 전 버전 디스크 잔존. [정적] `dataset/` 하위 삭제 코드는 `purgeThisRunVersionDir`(신고 중단 전용)·tmp `deleteIfExists` 뿐, retention 잡·스케줄러 **0건**. TODO 주석 `DatasetExportService.java:45-46,171-172` 존치 | 확정 정책 준수 |

---

## E-7 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---|---|
| TC-EXPORT-020 | ★최상위 키는 `event` (rename) | PASS | [실동작] 신규 산출 전수(126 v1·v2, 173 v1~v8, 178 v1)에서 최상위 키가 `"event"`. `"event_annotation"` **0건**. 위치는 `video` 다음(`@JsonPropertyOrder` `{info,dataset,licences,video,event,image,annotations,categories,type}`) | `NiaAnnotationDoc.java:25-26,32`. 내부 필드명 `eventAnnotation` 유지 |
| TC-EXPORT-021 | event 값 null 처리 | PASS | [실동작] 동결 event 없는 영상(126·173·178) JSON 54행 `"event" : null` — 키 present, 값만 null | `@JsonInclude(ALWAYS)` `NiaAnnotationDoc.java:24` |
| TC-EXPORT-022 | anonymity=ExportKind 파생(수동 override 금지) | PASS | [실동작] **반증 실행** — `PUT /v1/frames/188/privacy-meta {"anonymity":"Y",…}` 로 `ls_data_src.anony_incl_yn='Y'` 저장 후 재산출(v8) → `v8/orgnl/0000.json` `image.anonymity="N"`, `v8/deid/0000.json` `="Y"`. 수동값이 덮지 않음 | `NiaJsonBuilder.java:150-153` — `src.getAnonyInclYn()` 참조 자체가 없음(CWE-359 방어) |
| TC-EXPORT-023 | pseudonymity/privacyIncluded 수동 우선 | PASS | [실동작] 같은 v8 에서 `image.pseudonymity="Y"`, `image.privacy_included="Y"`(수동값 반영, **2벌 모두**). 수동값 리셋 상태(v1~v7)에서는 파생 폴백 `"N"` | `NiaJsonBuilder.java:156-159` + `firstNonBlank`(`:203-206`, CHAR(1) 공백 패딩도 blank 취급) |
| TC-EXPORT-024 | deid 산출 경로 fail-secure | PASS | [정적] `NiaJsonBuilder.java:132-137`(`buildDataset` kind 분기, deid 미상이면 `path=null` → `name`/`src_path` 둘 다 null) + `VideoMetaMapper.java:61-64`(`kindVideoPath` → `basename` null). 테스트 `NiaJsonBuilderTest:499`·`VideoMetaMapperTest:173` | 실측 환경은 procLog 가 항상 존재해 null 케이스 미발생 |
| TC-EXPORT-025 | malformed 라벨 skip | PASS | [정적] `NiaJsonBuilder.java:178-201` — `CustomException` catch → `lblSn` 만 로깅(좌표 원문 미노출), 문서는 정상 생성. 테스트 `NiaJsonBuilderTest:410`·`:116` | 정상 데이터라 실동작 미발생 |
| TC-EXPORT-026 | 잉여키 제거/키 유지 정합 | PASS | [실동작] 산출 JSON `video` 33키·`image` 13키 전부 present, 미보유 필드는 값만 null(`type`·`location`·`license_id`·`pixel`·`og_cd`·`cctv_height`·`cctv_azimuth`·`cctv_mng_no`·`frames`·`event_log`). 잉여키 `orign_filename`·`orign_file_name`·`cto`·`vqa` **0건** | `NiaVideo.java:12`·`NiaImage.java:9` `@JsonInclude(ALWAYS)` |
| TC-EXPORT-027 | vd_description 필드 존재 | PASS | [실동작] 산출 JSON 52행 `"vd_description" : null` (키 존재·값 null) | `NiaVideo.java:46`; `VideoMetaMapper.java:101` 하드 null |
| TC-EXPORT-028 | weather/time_of_day/season — 수동값만 | PASS | [실동작] 173 v1·v2(수동 미입력) → 세 값 **모두 null**(`sht_dt` 가 있어도 `NGT`/`SUMMER` 파생 **미발생** = self-fill 제거 확인). 촬영환경 수동 입력 후 v3~v8 → `"weather":"맑음","time_of_day":"DAY","season":"SUMMER"`. orgnl/deid 2벌 동일 | `VideoMetaMapper.java:57-59`(raw→meta) + `DatasetVideoMetaSnapshotService.java:106-122`(수동값만 동결, `SHT_DT` 파생 폐기 주석 명시). `TimeOfDaySeasonDeriver` 는 조회 프리필(`EnvironmentMetaService.java:199,202`, `source=DERIVED` 명시)에만 잔존 |
| TC-EXPORT-029 | 촬영환경 blank→null 정규화 | PASS | [정적] `VideoMetaMapper.java:144-152` `firstNonBlank` — 공백만 있는 값도 미입력 취급, 전부 blank 면 null. 스냅샷 측 `nullIfBlank`(`DatasetVideoMetaSnapshotService.java:120-122`)와 표현 일치. 테스트 `VideoMetaMapperTest:271` | 빈 문자열 행 주입 불가(파일 수정 금지) |
| TC-EXPORT-030 | anonymity kind override(video) | PASS | [실동작] `v8/orgnl` `video.anonymity="N"` ↔ `v8/deid` `="Y"`, 같은 문서의 `weather/time_of_day/season` 은 2벌 동일 | `VideoMetaMapper.java:65` |
| TC-EXPORT-031 | meta null 방어 | PASS | [정적] `NiaJsonBuilder.java:88-91` → `INVALID_INPUT`("영상 메타가 null 입니다.") = HTTP 400. 테스트 `NiaJsonBuilderTest:514` | 상위(`loadPreparation`)가 활성 메타 부재를 먼저 skip 해 실경로 미도달 |
| TC-EXPORT-032 | FORMAT_VERSION/info 계약 | PASS | [실동작] 전 산출 JSON `info.version="1.3"`, `info.description="AI기반 CCTV 관제지원시스템 학습데이터"`, `info.year=2026`, `info.date_created="2026-07-31"`, 최상위 `"type":"instances"` | `NiaJsonBuilder.java:37,39,96` |
| TC-EXPORT-033 | image.file_name 은 ExportFileNaming 단일 지점 | PASS | [실동작] JSON `image.file_name="0000.jpg"/"0001.jpg"/"0002.jpg"` ↔ 같은 폴더 실제 파일명 **완전 일치**. 구 `frame-{n}.jpg` 형식 산출물 **0건** | `NiaJsonBuilder.java:145` → `ExportFileNaming.imageFileName`(`%04d`, 음수 fail-closed). 계약 테스트 `ExportNamingContractTest:85` |
| TC-EXPORT-034 | frame_num = VDO_FRM_NO, 미측정이면 null | PASS | [실동작] 173 프레임 `FRM_NO 0/1/2` ↔ `VDO_FRM_NO 0/1200/2400` → JSON `0000.json:frame_num=0`, `0001.json:frame_num=1200`, `0002.json:frame_num=2400`. **파일명(FRM_NO)과 값(VDO_FRM_NO)이 서로 다름** = FRM_NO 폴백 없음 실증. 126(interval 300)은 0/300/600 | `NiaJsonBuilder.java:146-149,169-170`. 테스트 `DatasetExportE2EIT:357,375` |
| TC-EXPORT-035 | 파생영상 video.filename 은 비식별 사본 | PARTIAL | [실동작] 케이스 기대는 **충족** — 파생 178 은 deid 1벌만 산출, `video.filename="RESL_720P.mp4"`, `dataset.src_path="/app/storage/deidentified/videos/resolution/173/178/RESL_720P.mp4"`(자기 비식별 사본), 부모 원본 경로 유출 0. 동결 `RAW_FILE_PATH_NM` 은 파생에서 null(`DatasetVideoMetaSnapshotService.java:132`) | ⚠ **같은 파생 문서에서 해상도 메타가 실제 산출물과 불일치** — JSON `image.width/height=1920×1080`·`video.width/height=1920×1080`·`resolution="1920x1080"` 인데 실제 `0001.jpg` 는 **1280×720**, 라벨 좌표도 720p 로 재계산돼 있음 → **E-ISSUE-62** |
| TC-EXPORT-036 | dataset 블록 kind 분기 | PASS | [실동작] 126 v2 `orgnl/0000.json` → `src_path=/app/storage/raw/seed/sample-cctv-1080p.mp4`, `name="sample-cctv-1080p"` / `deid/0000.json` → `src_path=/app/storage/raw/seed/126/deid/sample-cctv-1080p-mask.mp4`, `name="sample-cctv-1080p-mask"`. 비식별 산출물에 원본 경로 누출 0 | `NiaJsonBuilder.java:132-137` |
| TC-EXPORT-037 | frm_expln pass-through | PASS | [실동작] `PUT /v1/frames/189/description` → 재export(v2) → `0001.json` `image.description="E6 verification frame description"`. 미입력 프레임은 `"description":null` | `NiaJsonBuilder.java:174`(`NiaImage` 마지막 필드) |

---

## 근거 드리프트 / self-fill 점검

### 근거 file:line 드리프트 (경미 2건 — 판정 영향 없음)

| ID | 카탈로그 근거 | 실제 | 성격 |
|---|---|---|---|
| TC-EXPORT-040 | `DatasetExportTxService.java:257-278` | `claimForRetry` 는 **257-261**. 262-278 은 다음 메서드(`sweepStalePending`) javadoc | 범위 과다 |
| TC-EXPORT-016 | `AsyncDatasetExportRunner.java:115-129` | `doExport` 본문은 **121-130**(115-120 은 javadoc) | 1행 어긋남 |

그 외 E-6 21건 · E-7 18건의 근거는 **전부 정합**(라인 단위 대조 완료). 특히 E-7 은 `NiaAnnotationDoc.java:24,32` · `NiaJsonBuilder.java:150-153/156-159/132-137/142-145/146-149,169-170/174/88-91/178-201` · `NiaVideo.java:12,46` · `NiaImage.java:9` · `VideoMetaMapper.java:46-59,61-64,65,101,144-152` · `DatasetVideoMetaSnapshotService.java:126-133` 이 **행 단위로 정확**하다.

### self-fill 점검

| 값 | 판정 | 근거 |
|---|:--:|---|
| 비식별 영상 파일명/경로(`dataset.src_path`·`video.filename`) | **self-fill 아님** | `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 실적재값(`…/sample-cctv-1080p-mask.mp4` — mock KPST 가 정한 이름)의 basename. 고정 `deidentified.mp4` 조합 흔적 0. `DatasetExportTxService.java:133-135` |
| 촬영환경 3필드(`weather`·`time_of_day`·`season`) | **self-fill 제거 확인** | 수동 미입력 상태(173 v1·v2)에서 `sht_dt` 가 있어도 **전부 null**. 1차 `E-ISSUE-42`(`SHT_DT`→NGT/SUMMER 추정)의 동결·산출 경로 파생은 폐기됨 |
| `image.anonymity` | **파생(정상)** | `ExportKind` 단일 원천. 수동 `Y` 저장 상태에서도 orgnl=`N` 유지 실증 |
| `image.pseudonymity`/`privacy_included` | **수동값 우선(정상)** | 저장값 반영 실증, 미저장 시 meta 파생 폴백 |
| `video.length` | **self-fill 아님 — 단 출처 혼재 재확인(PIPE-ISSUE-03)** | `VideoMetaMapper.java:42` 가 `meta.getVdoLenSec()`(= `LS_DATA_RAW.VDO_LEN_SEC`, **관제 `MNG_CLIP_MASTER.VDO_LEN_SEC` 신고값**)을 쓴다. 프로그램이 값을 지어내지 않으므로 self-fill 아님. **재확인 결과**: rawSn **126** 은 관제 신고값 30000ms → `"length":"30"` 인데 같은 JSON 의 `fps=29.97`·`width/height`·`filesize` 는 ffprobe 실측이고 `LS_DATA_META.video.duration_ms=112679`(112.7초) → **한 레코드 안에 30초/112.7초 공존**. 반면 rawSn **173/132/133/153/156** 은 관제 신고값이 113000ms 라 `"length":"113"` 로 ffprobe 와 일치 → **결함이 아니라 "관제 신고값을 그대로 쓴다"는 구조**이며, 신고값이 부정확하면 그대로 전파된다. 126 의 30초는 dev 시드 아티팩트. **PIPE-ISSUE-03 을 MEDIUM 으로 존치**(본 파트는 신규 이슈로 중복 등록하지 않음) |

### 파괴적 변경 회피 / 검증용 데이터 생성 기록

- **rawSn 126**(참조 기준)·**133**(신고 OPEN)은 **일절 건드리지 않았다** — 최종 확인: 126 `DE_IDENT_YN='Y'`, export `v1/v2` 그대로 2행 / 133 `DE_IDENT_YN='F'`, 신고 sn=3 `OPEN` 유지.
- 파괴 검증(PARTIAL·FAILED·base 거부·신고 게이트)은 **본 검증 전용으로 새로 만든 rawSn 173**(관제 클립 `E6-CLIP-01` 신규 시드 → `/v1/dev/batch/scan` → 비식별 → 마킹 → 배치 → 승인, 전 구간 정규 API)과 그 해상도 파생 **178** 에서만 수행했다.
- 173 의 프레임 이미지 삭제/복원, `raw_file_path_nm` 변경/원복은 검증 직후 원상 복구했다(현재 `raw_file_path_nm=/app/storage/raw/seed/sample-cctv-1080p.mp4`, 프레임 3장 복원 완료).
- 비식별 영상 파일 `touch` 는 **외부 비식별 솔루션의 제자리 교체를 모사**한 것이다(resolve 게이트 `DeidentReportService:567-576` 가 mtime>신고시각을 요구). DB 상태를 UPDATE 로 위조한 구간은 base-거부 검증 1건(즉시 원복)뿐이다.

---

## 이슈 상세

### [E-ISSUE-61] TC-EXPORT-018 — export 가 **FAILED 로 마감된 경우에도** 관제 통지가 발송된다 (통지 보류 계약 파손)

- **심각도**: HIGH
- **기대 동작(기대효과)**: 확정 정책 — *"통지는 export 성공(SUCCEEDED) 후 발송한다. **export 가 실패하면 통지를 보류**하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다."* 즉 산출이 실패하면 관제가 통지를 받고 `V_COMPLETED_VIDEO.EXPORT_PATH_NM`(최신 SUCCEEDED)을 조회해 **구 버전 폴더를 픽업**하는 일이 없어야 한다. TC-EXPORT-018 기대결과도 `doExport` 가 true 일 때만 완료 이벤트를 발행하고 "실패면 통지 보류"다.
- **현재 동작(이슈 내용)**: `DatasetExportService.export()` 의 **FAILED 종결 3분기가 예외를 던지지 않고 정상 return** 한다. 따라서 `AsyncDatasetExportRunner.doExport` 가 `true` 를 반환하고 통지가 그대로 나간다.

  ```java
  // DatasetExportService.java:205-210  (totalWritten == 0)
  txService.markFailed(inserted.exportSn());
  log.warn("[DatasetExport] nothing produced — marked FAILED rawSn={} version={}", ...);
  outcome = OUTCOME_FAILED;          // ← throw 없음

  // DatasetExportService.java:234-241 (writer.write 예외)
  } catch (RuntimeException e) {
      txService.markFailed(inserted.exportSn());
      log.warn("[DatasetExport] export failed — approval unaffected ...");
      outcome = OUTCOME_FAILED;      // ← 삼키고 정상 흐름 복귀
  }

  // DatasetExportService.java:162-169 (산출 base 거부)
  } catch (RuntimeException e) {
      markBaseRejected(rawSn, prep.contentHash(), e);
      outcome = OUTCOME_FAILED;
      return;                        // ← 정상 return
  }

  // AsyncDatasetExportRunner.java:121-130
  private boolean doExport(Long rawSn, boolean forceRegenerate) {
      try { exportService.export(rawSn, forceRegenerate); return true; }   // ← FAILED 도 true
      catch (Exception e) { ...; return false; }
  }
  ```

  **실동작 근거(rawSn 173, 두 경로 모두 재현)**
  ```
  04:10:01.352 WARN  [DatasetExport] nothing produced — marked FAILED rawSn=173 version=5
  04:10:01.360 INFO  [ControlNotify] completed conflicted -> resend as updated rawSn=173
  04:10:01.362 INFO  [ControlNotify] TASK_COMPLETED sent rawSn=173 actual=TASK_MODIFIED
  ---
  04:10:43.758 ERROR [DatasetExport] export base rejected — marked FAILED rawSn=173 reason=FORBIDDEN
  04:10:43.767 INFO  [ControlNotify] completed conflicted -> resend as updated rawSn=173
  04:10:43.770 INFO  [ControlNotify] TASK_COMPLETED sent rawSn=173 actual=TASK_MODIFIED
  ```
  대조군(정상 보류): 신고 게이트 차단은 예외로 이탈하므로 `async export failed rawSn=173 cause=CustomException` 이 찍히고 통지가 **나가지 않는다**(04:06:48). 즉 **보류가 동작하는 유일한 경로는 신고 게이트뿐**이고, 정작 회수기가 다루도록 설계된 `FAILED` 유형이 전부 새고 있다.

  **왜 테스트가 못 잡았나 (거짓 GREEN)**: `AsyncDatasetExportRunnerTest:87 승인_export_실패시_완료이벤트를_발행하지_않는다 (HIGH-D)` 와 `:113 재산출_실패시_통지콜백을_실행하지_않는다` 는 둘 다 `doThrow(new RuntimeException("io")).when(exportService).export(...)` 로 **예외를 스텁**한다. 그런데 실제 서비스는 같은 상황에서 예외를 던지지 않도록 `DatasetExportServiceTest:264 파일산출_실패해도_승인은_롤백되지_않는다 — 예외 미전파` 가 명시적으로 보장한다. 두 테스트가 **서로 모순된 전제** 위에서 각자 GREEN 이라 결합 지점이 비어 있다.

- **재현/확인 경로**
  ```bash
  # 1) 승인된 영상의 프레임 원천 이미지를 전부 제거 → 산출 0건 유도
  docker exec klid-backend sh -c 'rm -f /app/storage/raw/frames/raw/173/*.jpg \
                                        /app/storage/deidentified/frames/deid/173/*.jpg'
  # 2) 산출 트리거(신고→해소 또는 승인 후 수정 디바운스)
  # 3) 로그에서 markFailed 직후 통지가 나가는지 확인
  docker logs klid-backend 2>&1 | grep -E "nothing produced|TASK_COMPLETED sent|async export failed"
  ```
  ```sql
  SELECT export_ver_no, export_stts_cd, frame_cnt FROM ls_dataset_export WHERE data_raw_sn=173 ORDER BY 1;
  -- 5|FAILED|(null)  ← 이 시점에 통지가 이미 발송됨
  SELECT export_path_nm, frame_cnt FROM v_completed_video WHERE raw_sn=173;
  -- 관제는 통지 수신 후 여기서 '최신 SUCCEEDED' = 구 버전(v3)을 픽업한다
  ```

- **영향**
  - 관제서버가 **산출되지 않은/실패한 버전에 대해 통지를 받고, 뷰에서는 직전 성공 버전(구 내용)을 픽업**한다 — 확정 정책이 막으려던 바로 그 시나리오("파일이 옛 내용이면 라벨링 정보 동기화 요구가 성립하지 않는다").
  - 최초 승인이 실패한 영상(직전 SUCCEEDED 없음)이면 `EXPORT_PATH_NM=NULL` 인 행을 통지받아 관제 배치가 빈 경로/NULL 을 픽업한다.
  - `DatasetExportFailureRecoverer` 의 "성공 시점으로 지연" 설계가 무의미해진다(이미 통지가 나간 뒤라 재통지가 정정이 아니라 중복이 된다).
  - 데이터 무결성 계열(CWE-670 부적절한 제어 흐름 구현). PII 유출 방향은 아니다.

- **수정 방향(제안)** ⚠ **구현하지 않는다**
  1. `DatasetExportService.export()` 를 `boolean`(또는 `ExportOutcome`) 반환으로 바꾸고 `AsyncDatasetExportRunner.doExport` 가 예외 유무가 아니라 **outcome 으로** 성공을 판정한다(`COMPLETED`·`PARTIAL`·`IDEMPOTENT_SKIP` = 통지, `FAILED`·`VERSION_EXHAUSTED`·`DEIDENT_BLOCKED` = 보류, `NO_INPUT` 은 정책 결정 필요).
  2. 시그니처를 바꾸기 어렵다면 FAILED 3분기에서 **전용 예외**(`ExportFailedSilentlyException` 등)를 던지고 러너가 그것만 잡아 `false` 를 반환한다. 단 "승인 불변(예외 미전파)" 계약은 러너가 여전히 삼키므로 유지된다.
  3. 회귀 가드는 **스텁 예외가 아니라 실제 FAILED 경로**로 작성한다 — 예: `DatasetExportE2EIT` 에서 원천 이미지 0건 상태로 승인 → `verify(eventPublisher, never()).publishEvent(DatasetExportCompletedEvent)` 를 단언.

---

### [E-ISSUE-62] TC-EXPORT-035 — 해상도 파생 export JSON 의 `image.width/height`(및 `video.width/height/resolution`)가 **실제 산출 이미지·라벨 좌표계와 불일치**

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 학습데이터 JSON 의 `image.width`/`image.height` 는 같은 폴더에 실제로 놓인 프레임 이미지의 픽셀 크기여야 한다. `annotations[].bbox`/`polygon` 좌표계와도 일치해야 COCO 계열 소비자가 정규화·검증을 할 수 있다. (`video.*` 기술메타가 부모와 같은 것은 CLAUDE.md 확정 정책상 정상이나, 그 정책은 **비디오 파일 기준**을 말한다.)
- **현재 동작(이슈 내용)**: 해상도 파생(rawSn 178, `AUG_TYPE_CD=RESL_720P`)의 export JSON 이 부모 해상도를 그대로 싣는다.

  ```java
  // NiaJsonBuilder.java:161-164  — image 블록의 크기 원천이 "영상 메타"다
  return new NiaImage(
          imageId,
          fileName,
          meta.getVdoWdth(),      // ← 1920 (부모 비디오 폭)
          meta.getVdoHgt(),       // ← 1080 (부모 비디오 높이)
          ...
  ```
  ```java
  // VideoMetaMapper.java:81-83 — video 블록도 동일 원천
          meta.getVdoWdth(), meta.getVdoHgt(), meta.getResl(),
  ```

  **실동작 근거**
  | 항목 | 값 |
  |---|---|
  | 실제 파일 `…/178/178/v1/deid/0001.jpg` 크기 | **1280 × 720** (JPEG SOF 파싱) |
  | 같은 폴더 `0001.json` `image.width/height` | **1920 / 1080** |
  | 〃 `video.width/height`, `video.resolution` | **1920 / 1080**, `"1920x1080"` |
  | 〃 `annotations[].bbox` (id 734) | `[1223.94, 352.50, 55.84, 74.18]` — x 최댓값 1279.x = **720p 좌표계** |
  | 부모 173 의 대응 라벨 (id 608) | `[1835.91, 528.76, 83.75, 111.27]` — **1080p 좌표계** (배율 1.5 정확히 대응) |
  | DB | `ls_data_aug.AUG_TYPE_CD='RESL_720P'`, `targetW/H=1280/720`, `LS_DATA_AUG_LBL_MAP` 좌표 재계산 적용 |

  즉 **이미지와 라벨은 720p 로 서로 정합**한데 **선언된 크기만 1080p** 다. `LS_DATASET_VIDEO_META` 에는 목표 해상도를 담을 필드가 없고(`DerivedMetaCopier` 가 부모 `video.*` 를 복사), 목표값은 `LS_DATA_AUG.AUG_TYPE_CD`/예약행에만 존재한다.

- **재현/확인 경로**
  ```bash
  BASE=http://localhost:18081/api
  curl -s -X POST "$BASE/v1/videos/{승인영상}/resolution" -H "Authorization: Bearer $REVIEWER" \
       -H 'Content-Type: application/json' -d '{"presets":["RESL_720P"]}'
  # 파생 rawSn 배정→검수제출→승인 후
  docker exec klid-backend sh -c 'grep -nE "\"width\"|\"height\"|resolution" \
       /app/storage/deidentified/videos/resolution/{parent}/{derived}/{derived}/v1/deid/0001.json'
  docker exec klid-backend python3 -c '...JPEG SOF 파싱...'   # 실제 1280x720
  ```
  ```sql
  SELECT aug_type_cd FROM ls_data_aug WHERE src_sn IN (SELECT src_sn FROM ls_data_src WHERE raw_sn={parent});
  SELECT point_cn FROM ls_data_lbl WHERE src_sn={파생 프레임};  -- 720p 좌표
  ```

- **영향**
  - 학습데이터셋 소비자가 `image.width/height` 로 좌표를 정규화하면 **1.5배 어긋난 박스**를 얻는다(x/1920 vs x/1280).
  - 해상도 변경 산출물(SFR-06-03)의 본래 목적인 "여러 해상도 학습데이터"가 메타상 구분되지 않는다 — 3종 파생 모두 `resolution="1920x1080"` 로 나간다.
  - `V_COMPLETED_VIDEO.RESL/VDO_WDTH/VDO_HGT` 도 같은 동결값을 쓰므로 관제 데이터마트에도 동일하게 전파된다.
  - 증강 파생(WINTER/NIGHT/RAIN)은 해상도를 바꾸지 않아 영향 없음 — **해상도 파생 전용 결함**이다.
  - CWE-1188(부정확한 초기화) 계열. PII 유출 방향 아님.

- **수정 방향(제안)** ⚠ **구현하지 않는다** — 정책 확정이 선행돼야 한다.
  1. **`image.width/height` 를 프레임 실측으로 분리**한다. `LS_DATA_SRC` 에 프레임 폭/높이 컬럼(표준용어 조합 필요)을 두고 추출·리스케일 시 적재해 `NiaJsonBuilder.buildImage` 가 그것을 쓰게 한다. 미측정이면 현행 video 메타 폴백(하위호환).
  2. **`video.width/height/resolution` 은 "비디오 파일 기준"이라는 확정 정책을 유지**하되(파생 비디오는 실제로 부모 복사본이므로 1920×1080 이 맞다), 관제 계약 문서에 그 의미를 명시하고 **목표 해상도를 별도 필드로 노출**하는 안을 검토한다(예: `AUG_TYPE_CD` 를 뷰/JSON 으로 전달).
  3. 회귀 가드: 해상도 파생 export IT 에서 `image.width == ffprobe(실파일).width` 와 `max(bbox.x) <= image.width` 를 단언.

---

# E 클러스터 part5 (E-8·E-9) 2차 검증 결과

> 대상: `docs/test-cases/E-augment-resolution-export-meta.md` §E-8(촬영환경 메타 20건) · §E-9(프레임 개인정보 메타 15건)
> 환경: 로컬 도커 스택 · backend `localhost:18081`(HEAD `ca3c712b`) · PG `klid_system`/`public`
> 실행: 2026-07-31 04:08~04:19 KST · **backend 무재기동** · 파일 수정 0건 · 빌드/테스트 실행 0건

## 집계

| 구분 | 검증 | PASS | PARTIAL | FAIL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| E-8 촬영환경 메타 | 19 | 17 | 2 | 0 | 0 | 0 | 0 |
| E-9 프레임 개인정보 메타 | 15 | 14 | 1 | 0 | 0 | 0 | 0 |
| **합계** | **34** | **31** | **3** | **0** | **0** | **0** | **0** |

- **폐기 1건 집계 제외**: `TC-META-009`(재동결 시 export 미재생성) — 2026-07-30 정책 반전(C-1b)으로 폐기, 대체는 `TC-META-017`. E-9 에는 폐기 행 없음.
- 이슈 6건 — HIGH 2 / MEDIUM 1 / LOW 3.
- **사용 영상**: 168(APPROVED, 실동작 주무대) · 145/20012(미승인·스냅샷부재 대조) · 133(신고 OPEN 게이트) · 181(168의 720P 파생, 본 검증이 생성).
  - ⚠ **rawSn 126 은 손대지 않았다**(읽지도 쓰지도 않음).
  - ⚠ **rawSn 133 은 resolve 하지 않았다**. 신고 구간 쓰기 실측분(프레임 78 개인정보 3필드·프레임 설명·영상 촬영환경)은 **전부 원복 확인**(DB 재조회로 null 복귀 확증). `DE_IDNTF_YN='F'` · `LS_DEIDENT_REPORT` sn=3 `OPEN` · 작업락 `LOCKED` 유지.
  - **rawSn 168 은 검증 산출물이 남는다**(원복 시 export 버전이 더 쌓여 노이즈가 커지므로 최종 상태를 남김): `LS_DATA_RAW` 촬영환경=안개/DAY/FALL · 프레임 180=(Y,Y,Y)/182=(null,null,Y) · `LS_DATASET_VIDEO_META` 활성 스냅샷 갱신 · `LS_DATASET_EXPORT` v2~v5 추가 · 파생 rawSn=181 생성. rawSn 145/20012/133 은 전부 원복.
  - ⚠ **첫 시도(rawSn 169)는 타 에이전트 간섭으로 무효**: 04:11:27 에 D-part4 가 169 에 비식별 신고를 접수해 1초 뒤 내 디바운스 flush 의 export 가 `export blocked — deident report open rawSn=169` 로 차단됐다. 제품 결함 아님. 168 로 재수행해 완결.

## 1차 이슈 해소 대조

| 1차 이슈 | 내용 | 2차 판정 | 근거 |
|---|---|:--:|---|
| **★E-ISSUE-42** | `time_of_day`/`season` 이 원천 없이 `SHT_DT` 규칙으로 생성돼 export·마트뷰에 **파생 표시 없이** 실림 (self-fill CRITICAL 급) | **해소 (핵심축)** | [실동작] rawSn 168·169 `LS_DATA_RAW.SHT_DT` non-null(2026-07-30 18:59) + 수동값 미입력 상태에서 ①`LS_DATASET_VIDEO_META` 활성 스냅샷 `day_ngt_cd`/`sesn_cd`/`wthr_nm` **전부 NULL** ②export `168/v1/orgnl/0000.json` → `video.weather=null, time_of_day=null, season=null`. [정적] `DatasetVideoMetaSnapshotService.java:106-123` 이 `nullIfBlank(src.getDayNgtCd())` 로 **수동값만** 동결하고 `TimeOfDaySeasonDeriver` 를 호출하지 않는다. `TimeOfDaySeasonDeriver.java:12-15` 주석에 "동결/산출 경로에서는 쓰지 않는다(E-ISSUE-42) … 여기에 파생 폴백을 다시 배선하지 말 것" 명시 |
| E-ISSUE-42 부속 ①: 조회 프리필 | 화면 프리필이 파생값을 준다 | **정책상 유지 + 투명** | [실동작] `GET /v1/videos/145/environment-meta` → `{"timeOfDay":"NGT","season":"SUMMER","weather":null,"timeOfDaySource":"DERIVED","seasonSource":"DERIVED","weatherSource":null}`. 항목별 출처가 응답에 동반된다. 카탈로그 TC-META-002 의 "조회 프리필은 유지" 와 정합 |
| E-ISSUE-42 부속 ②: 레거시 동결행 정정 | 파생 폐기 **이전**에 NGT/SUMMER 로 동결된 행 잔존 | **경로 구현 확인 / 대상 0건** | [실동작] `GET /v1/dev/dataset-video-meta/shooting-env-correction-targets` → `{"targetCount":0}`. DB 판별식 직접 실행도 0건(현 스택은 07-31 재구축분이라 레거시 행 부재). 정정 실행 경로는 정적+테스트로만 확인 → **TC-META-020 PARTIAL** |
| E-ISSUE-42 부속 ③: 관제 원천 매핑 | `MNG_CLIP_EVNT_LST.WTHR_CD/SESN_CD/HR_TYPE_CD/PRVC_TYPE_CD` 매핑 추가 | **미해소(의도적 보류) — 단 self-fill 은 아님** | [정적] `MngClipEvntLst.java:23-27` 이 "코드도메인 확정 후 별건 — 지금 매핑하면 그 해석 자체가 추정(self-fill)이 된다" 로 보류를 명문화하고, 대신 **미입력을 null 로 동결**해 추정 배포를 끊었다. 즉 "정답을 안 쓰는" 상태이지 "추정을 정답인 척 내보내는" 상태는 아니다 |
| E-ISSUE-42 부속 ④: `PRVC_TYPE_CD` 하드코딩 | 적재가 `ANONY` 상수 고정 | **미해소** | [실동작] `ls_data_raw` 전 영상 `prvc_type_cd='ANONY'`. [정적] `TrainingVideoIngestTx.java:67,118` `DEFAULT_PRVC_TYPE`. → **E-ISSUE-83** |
| **E-ISSUE-45** | `CONTROL_NOTIFY_ENABLED=false` 로 TASK_MODIFIED 통지 실동작 미검증(검증 한계) | **해소** | 현 컨테이너 `CONTROL_NOTIFY_ENABLED=true`. [실동작] `[ControlNotify] TASK_MODIFIED sent rawSn=168 frames=0 videoLevel=1 reExport=true actual=TASK_MODIFIED`(env 경로) · `frames=2 videoLevel=0`(privacy 벌크 경로) 모두 실왕복 관측 |
| **B-ISSUE-81** | `PUT /v1/frames/{srcSn}/privacy-meta` 신고 게이트·작업락 미배선 | **미해소(독립 재확인)** | 아래 §신고 게이트 실측표. → **E-ISSUE-81** |
| **B-ISSUE-82** | `PUT /v1/frames/{srcSn}/description` 작업락 무시 | **미해소(독립 재확인)** | 동상. E-ISSUE-82 에 병기 |

## ★self-fill 실측

**대전제 판정: E-8/E-9 의 산출 경로에 self-fill 없음.** 원천(수동입력)이 없으면 null 이 그대로 나간다.

| 필드 | 관제 원천값(`MNG_CLIP_EVNT_LST`) | 저작도구 출력값 (동결/export) | 일치 | MANUAL/DERIVED 표기 |
|---|---|---|:--:|---|
| `weather` / `WTHR_NM` | **컬럼 부재** — 로컬 `MNG_CLIP_EVNT_LST` 는 V63 stub(`EVNT_ID`,`EVNT_TYPE_CD`,`SHT_DT` 3컬럼)이라 `WTHR_CD` 자체가 없다 | 수동 미입력 → `null`(스냅샷·export 모두). 수동 입력 시 그 값 그대로(안개) | 대조 불가(원천 부재) | export/뷰 **표기 없음** — 단 non-null=수동값 단언이 성립하므로 불필요. 조회 API 는 `weatherSource=MANUAL/null` |
| `time_of_day` / `DAY_NGT_CD` | **컬럼 부재**(`HR_TYPE_CD` 미존재) | 수동 미입력 → `null`. **`SHT_DT` 규칙 파생 안 함**(실측: 168/169 `SHT_DT` 있어도 스냅샷·`v1` JSON 모두 null) | 대조 불가(원천 부재) | export/뷰 표기 없음(수동값만 실림). 조회 API `timeOfDaySource=DERIVED` 로 프리필 구분 |
| `season` / `SESN_CD` | **컬럼 부재**(`SESN_CD` 미존재) | 동상 — `null` | 대조 불가 | 동상 |
| `event` / `EVNT_TYPE_CD` | `INTRUSION`/`LOITERING` (실재) | export `video.event_id="LOITERING"`(rawSn 169) — 관제 실값 pass-through | **일치** | — |
| `anonymity`(image) | — (산출 종류 파생이 정본) | `ExportKind` 파생: orgnl=`N` / deid=`Y`. **프레임 수동값이 덮지 않음** | 설계대로 | `NiaJsonBuilder.java:150-154` 주석 명시 |
| `pseudonymity`(image) | `PRVC_TYPE_CD` (관제에 실재하나 **미매핑**) | 프레임 수동값 우선 → 없으면 `PRVC_TYPE_CD==PSDO?Y:N` = **항상 `N`**(적재가 ANONY 하드코딩) | **불일치 가능** | 없음 → **E-ISSUE-83** |
| `privacy_included`(image) | 동상 | 프레임 수동값 우선 → 없으면 `PRVC_YN` = **항상 `N`** | 동상 | 동상 |
| `vd_description`/`event_log`/`coordinates`/`cctv_*` | 미보유 | `null` 유지 | 정상(미보유 필드) | — |

- **판정 근거(반증 시도)**: ①`SHT_DT` 가 있는데 동결값이 채워지는가 → **아니오**(168·169 실측 null) ②규칙 파생 코드가 동결 경로에 남아 있는가 → **아니오**(`grep TimeOfDaySeasonDeriver` 참조처 = `EnvironmentMetaService`(조회) + `ShootingEnvironmentVocabulary`(상수 재사용) 뿐, 스냅샷 서비스 미참조) ③수동 입력이 실제로 산출까지 도달하는가 → **예**(168 `v2`/`v5` JSON `weather=안개, time_of_day=DAY, season=FALL`).
- **잔존 self-fill 성격의 값은 `pseudonymity`/`privacy_included` 하나뿐**이며, 그 뿌리는 E-8/E-9 가 아니라 **적재 단계의 `PRVC_TYPE_CD='ANONY'` 상수**다(E-ISSUE-83).

## ★신고 게이트 메타 경로 실측

대상 = **rawSn 133**(`DE_IDNTF_YN='F'`, `LS_DEIDENT_REPORT` sn=3 `OPEN`, `LS_AUTH_WORK_LOCK` `LOCKED`, 프레임 srcSn 78) / 토큰 = REVIEWER(1001).

| # | 엔드포인트 | 기대(정책상) | 신고구간 응답 | DB 변경 | 판정 |
|--:|---|:--:|:--:|:--:|:--:|
| 0 | `GET /v1/frames/78/labels` (대조 기준선) | 412 | **412** | — | 게이트 정상 |
| 1 | `GET /v1/frames/78/privacy-meta` | (미명시) | **200** — `{anonymity:"Y",pseudonymity:"N",privacyIncluded:"N"}` (파생 프리필) | — | 정보(판단값 Y/N 이라 PII 위치 아님) |
| 2 | `PUT /v1/frames/78/privacy-meta` | **차단(412 또는 409)** | **200** | **예** — `ls_data_src(78)` 3필드 `NULL→Y/Y/Y` | **미배선 → E-ISSUE-81** |
| 3 | `PUT /v1/frames/78/description` | **차단(409 작업락)** | **200** | **예** — `frm_expln` 설정됨 | **미배선 → E-ISSUE-82(병기)** |
| 4 | `PUT /v1/videos/133/environment-meta` | **차단(412 또는 409)** | **200** | **예** — `ls_data_raw(133)` `wthr_nm/day_ngt_cd/sesn_cd` = 비/DAY/FALL | **미배선(신규) → E-ISSUE-82** |
| 5 | `PUT /v1/frames/78/labels` (대조군) | 409 | **409** | 아니오 | 작업락 정상 |
| — | 위 #2~#4 **전건 원복 완료** | — | — | `ls_data_src(78)` 3필드+`frm_expln` NULL 복귀 · `ls_data_raw(133)` 3필드 NULL 복귀 · `de_ident_yn='F'`·신고 `OPEN` 유지 | 확인 |

- **핵심**: 비식별 신고는 개인정보 3필드를 "재판정 대상"으로 리셋하는데(`DeidentReportService` `privacyReset=6`), 그 값을 **신고 구간에 200 으로 즉시 되돌릴 수 있다**. `TC-DEID-035` 의 명시 목적(stale PII 방지)이 성립하지 않는다 — B-part5 판정과 독립 일치.
- **확장 발견**: 같은 결함이 **영상 단위 촬영환경 메타에도 있다**(#4). B-part5 는 프레임 축 2개(#2·#3)만 관측했다. 이쪽은 부작용이 더 크다 — APPROVED 영상이면 이 PUT 이 **동결 스냅샷을 즉시 재동결**(→ `V_COMPLETED_VIDEO` 값 변경)하면서 뒤따르는 export 는 `export blocked — deident report open` 으로 차단돼, **뷰와 export 폴더가 갈린다**(실제 로그로 rawSn 169 사례 관측).
- 확정 정책 ★1(신고 게이트=자기 rawSn 행 하나) 과 무관한 별개 표면이다 — 여기서 문제는 판정 **범위**가 아니라 판정 자체의 **미호출**이다.

## E-8 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-META-001 | 조회 — 수동값 우선 프리필 | PASS | [실동작] 145 에 `{"weather":"비","timeOfDay":"DAY","season":"WINTER"}` PUT 후 GET → 3필드 그대로 + `weatherSource/timeOfDaySource/seasonSource` 전부 `MANUAL`. [정적] `EnvironmentMetaService.java:193-211` `toResponse`(192행은 주석) | |
| TC-META-002 | 조회 — 파생 프리필(화면 전용) | PASS | [실동작] 수동값 없는 145·169 GET → `timeOfDay:"NGT", season:"SUMMER", weather:null` + `timeOfDaySource/seasonSource="DERIVED"`, `weatherSource=null`. 같은 영상의 동결 스냅샷·export 는 3필드 null → **조회만 파생, 산출은 null** 이 실측으로 분리 확인. [정적] `:198-210`; `DatasetVideoMetaSnapshotService.java:118-123` | ★E-ISSUE-42 해소 핵심 증거 |
| TC-META-003 | PUT 전체 교체 저장 | PARTIAL | [실동작] 145 PUT 3필드 → `ls_data_raw` 의 `wthr_nm/day_ngt_cd/sesn_cd` **만** 변경, `prvc_type_cd`·`prvc_yn`·`de_ident_yn`·`data_stts_cd` 불변(배치 컬럼 미간섭 확인). [정적] `:110-112` dirty checking(`changeShootingEnvironment`), 테스트 `EnvironmentMetaServiceTest:378`. **그러나 이 PUT 에 상태 게이트가 전무**하다 — 비식별 신고 OPEN + 작업락 LOCKED 인 133 에서도 200 으로 기록된다(§게이트 실측 #4) | **E-ISSUE-82** |
| TC-META-004 | null 필드 = 수동값 삭제 | PASS | [실동작] 145 에 `{"weather":"눈"}` 만 PUT → 응답 `weather:"눈"(MANUAL)`, `timeOfDay:"NGT"(DERIVED)`, `season:"SUMMER"(DERIVED)`; DB `day_ngt_cd`/`sesn_cd` NULL 로 삭제됨. [정적] `:106-112,174-185` | |
| TC-META-005 | 허용값 화이트리스트 weather | PASS | [실동작] `"폭우"` → **400** `INVALID_INPUT` "촬영환경 weather 값이 허용 목록에 없습니다." / `"맑음","흐림","비","눈","안개"` 는 200. [정적] `ShootingEnvironmentVocabulary.java:23` | |
| TC-META-006 | 허용값 timeOfDay/season | PASS | [실동작] `timeOfDay:"NIGHT"` 400 · `"day"`(소문자) 400 · `season:"여름"` 400 · `"summer"` 400. `DAY/NGT`·`SPRING/SUMMER/FALL/WINTER` 만 통과. [정적] `:26-32` | 대소문자 관대성 없음 |
| TC-META-007 | 길이 상한 | PASS | [실동작] 300자 weather → **400** `"weather: size must be between 0 and 20"`. 5,000자도 동일 | ⚠ 실제 선차단은 화이트리스트가 아니라 DTO `@Size(max=20)` 다(카탈로그 기대문 "화이트리스트 검사에 함께 걸림"과 경로가 다름). 결과(400)는 동일하므로 PASS |
| TC-META-008 | APPROVED 후 수정 재동결 + 통지 | PASS | [실동작] rawSn **168**(APPROVED) PUT `안개/DAY/FALL` → 로그 `materialized rawSn=168 hashPrefix=bec503b5 inserted=true` → `re-freeze triggered rawSn=168`. `ls_dataset_video_meta` 신규 활성행(DAY/FALL/안개) + 구행 `active_yn='N'`, **`rvw_cmpl_dt` 양쪽 모두 `2026-07-31 04:02:52.804331` 로 동일(승계 확인, 편집 시각으로 덮이지 않음)**. 이어 `TASK_MODIFIED sent rawSn=168 … reExport=true`. [정적] `:120-127,156-167` | 1차 E-ISSUE-45(통지 미검증) 해소 |
| ~~TC-META-009~~ | ~~재동결 시 export 미재생성~~ | — | **폐기 2026-07-30**(집계 제외) | 대체 TC-META-017 |
| TC-META-010 | 미검수 영상 수정 — 재동결·통지 없음 | PASS | [실동작] 145(`data_stts_cd`=배치 COMPLETED, 검수 미승인) PUT → `ls_dataset_video_meta` 0행 유지, `ls_dataset_export` 0행 유지, 로그에 re-freeze/TaskModified 없음. [정적] `:120,221-227` | |
| TC-META-011 | 재동결 시 활성 스냅샷 부재 fail-safe | PASS | [실동작] rawSn **20012**(`APPROVED` + 활성 스냅샷 0건) PUT → **200**, 로그 `WARN [EnvironmentMeta] re-freeze skipped — no active snapshot rawSn=20012`, 예외 없음. 이후 디바운스 flush 도 `no active video meta — skip export rawSn=20012` 로 안전 스킵 후 통지만 발송. [정적] `:157-161` | 원복 완료 |
| TC-META-012 | 동시성 — env저장 vs 승인 materialize | PASS | [정적] `:115-118` `videoRepository.flush()` → `videoMetaRepository.acquireRawLock(rawSn)` → `:120` `isReviewApproved` 순서 고정. `materialize` 도 동일 advisory 락을 첫 단계로 잡아(`DatasetVideoMetaSnapshotService` 1) 잠금 순서 단방향. 테스트 `EnvironmentMetaConcurrencyIT:80`("촬영환경_저장이_배치의_다른_컬럼_갱신을_덮어쓰지_않음") | 실동작 동시 승인 재현은 미수행(다른 에이전트 간섭 위험) |
| TC-META-013 | WORKER 본인배정 아닌 영상 | PASS | [실동작] WORKER **2001**(`ls_task_assignment`에 168·169·133 만 보유) → `GET/PUT /v1/videos/145/environment-meta` **403** "본인에게 배정되지 않은 영상입니다.". 미배정 WORKER 2099 → 403. 대조: 2001 의 168 PUT 은 200(정상 배정) | 배정표 실조회로 오판 회피 |
| TC-META-014 | 미인증/포털 채널 | PASS | [실동작] 토큰 없음 → **401** `UNAUTHORIZED`, PORTAL_USER 토큰 → **403** `FORBIDDEN`(채널 격리) | |
| TC-META-015 | 영상 미존재 | PASS | [실동작] `GET`/`PUT /v1/videos/9999999/environment-meta` **404** "영상을 찾을 수 없습니다.". [정적] `:187-190` | |
| TC-META-016 | 로그 PII 미출력 | PASS | [실동작] 허용값 외 3종 투입 후 로그 = `WARN [EnvironmentMeta] rejected value field=weather` / `field=timeOfDay` / `field=season` — **입력 원문 0건**. `<script>alert(1)</script>`·300자 입력도 로그·응답 어디에도 원문 미노출(응답은 필드명만). 성공 로그도 `updated rawSn=168` 식별자만 | CWE-117/209 방어 확인 |
| TC-META-017 | ★APPROVED 후 수정 = export 새 버전 전량 재생성 | PASS | [실동작] 168 PUT(04:12:00) → 디바운스 flush(04:13:08) `flush rawSn=168 regen=true` → `async re-export(+notify) starting rawSn=168 forceRegenerate=true` → `frames written kind=ORIGINAL version=2 written=2` + `kind=DEIDENTIFIED version=2 written=2` → `export succeeded rawSn=168 version=2`(04:13:08.731) → **그 뒤** `TASK_MODIFIED sent … reExport=true`(04:13:08.754). **순서 = export 성공 후 통지** 확증. 산출물 대조: `168/v2/orgnl/0000.json` `video.weather="안개", time_of_day="DAY", season="FALL"` ↔ `v1` 은 3필드 전부 `null` 로 **불변 보존**. [정적] `:122-127`; `AsyncDatasetExportRunner.java:91-106` | 최종 상태는 v5(재수정분까지 반영, 안개/DAY/FALL) |
| TC-META-018 | ★동결값은 수동값만 (self-fill 폐기) | PASS | [실동작] 168·169(수동 미입력, `SHT_DT`=2026-07-30 18:59) → 동결 스냅샷 `day_ngt_cd/sesn_cd/wthr_nm` 전부 NULL, export `v1` JSON 3필드 null. 판별식 SQL(라이브 raw null ↔ 동결 non-null) 전수 실행 결과 **0건**. [정적] `DatasetVideoMetaSnapshotService.java:106-123` `nullIfBlank` 3회. 테스트 `DatasetVideoMetaSnapshotServiceIT:230`("수동값_미저장_영상은_촬영환경_3필드가_모두_null로_동결된다") | ★E-ISSUE-42 해소 |
| TC-META-019 | 레거시 파생 동결값 정정 백필 — dry-run | PASS | [실동작] `GET /v1/dev/dataset-video-meta/shooting-env-correction-targets` REVIEWER **200** `{"targetCount":0}` — 호출 후 `ls_dataset_video_meta` 행 수·해시 불변, `ls_dataset_export` 신규 0, 통지 로그 0. WORKER **403** / 미인증 **401**. [정적] `DatasetVideoMetaBackfillDevController.java:80-85` `@Profile("!prd")` + `@PreAuthorize("hasRole('REVIEWER')")` | |
| TC-META-020 | 레거시 정정 백필 — 실행 + 상한 + 멱등 | PARTIAL | [실동작] `POST …/shooting-env-corrections` REVIEWER **200** `{"corrected":0,"remaining":0,"completed":true}`, 재호출도 동일(멱등 형태 확인). WORKER 403. **그러나 현 스택에 대상 0건이라 "정정 1건마다 재동결→TaskModifiedEvent→export 재생성" 과 `max-per-run` 상한 소진 경로에 도달하지 못했다.** [정적] `DatasetVideoMetaBackfillService.java:161-215`(상한 루프·`attempted` 무한루프 차단), `DatasetVideoMetaEnvCorrectionTx.correct:60-88`(advisory 락 → materialize → `TaskModifiedEvent(...,true)`), 설정 `application.yml:245 max-per-run: ${DATASET_ENV_CORRECTION_MAX_PER_RUN:200}`. 테스트 `DatasetVideoMetaEnvCorrectionIT` 6건(정정/보존/멱등/재export통지 축적) | **E-ISSUE-86** (검증 한계) |

## E-9 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-META-030 | 조회 — 수동값 우선/파생 폴백 | PASS | [실동작] 미저장 프레임 117 GET → `{anonymity:"Y", pseudonymity:"N", privacyIncluded:"N"}` (영상 `PRVC_TYPE_CD='ANONY'`·`PRVC_YN='N'` 파생과 정확히 일치). 수동 저장 프레임 180 GET → 저장값 `Y/Y/Y` 그대로. 필드 혼합(182: `anonymity` 미저장 + `privacyIncluded='Y'` 저장) → `{"anonymity":"Y"(파생), "pseudonymity":"N"(파생), "privacyIncluded":"Y"(수동)}` 로 **필드별 폴백** 확인. [정적] `FramePrivacyMetaService.java:66-70,160-183` | |
| TC-META-031 | 단건 PUT 전체 교체 | PARTIAL | [실동작] `PUT /v1/frames/182/privacy-meta` `{anonymity:null, pseudonymity:null, privacyIncluded:"Y"}` → DB `anony_incl_yn/psdo_incl_yn` NULL 로 삭제, `prvc_incl_yn='Y'`; 응답은 삭제분을 파생으로 폴백해 반환. [정적] `:73-78,144-157`. **그러나 이 PUT 은 비식별 신고·작업락 어느 게이트도 통과하지 않는다** — 133/프레임 78 에서 200 으로 3필드 재설정 성공(§게이트 실측 #2) | **E-ISSUE-81** |
| TC-META-032 | 값 화이트리스트 `^[YN]$` | PASS | [실동작] `"1"`·`"true"`·`"y"`(소문자)·5,000자 `"YYY…"` **전부 400** `"anonymity 는 Y 또는 N 이어야 합니다."`. [정적] `FramePrivacyMetaUpdateRequest`/`FramePrivacyBulkItem` `@Pattern(regexp="^[YN]$")` | |
| TC-META-033 | path/body srcSn 불일치 | PASS | [실동작] path 117 + body `srcSn:120` → **400** "path 의 srcSn 과 body 의 srcSn 이 다릅니다.". body `srcSn` 누락 → 400 `"srcSn: must not be null"`. [정적] `FramePrivacyMetaController.java:85-88` | CWE-345 |
| TC-META-034 | ★anonymity 는 export 미덮음 | PASS | [실동작] 프레임 180 수동 `anonymity="Y"`, 182 수동 `anonymity="N"` 저장 후 재생성된 `168/v3` 대조 → `orgnl/0000.json` `anonymity:"N"`(수동 Y 무시) · `deid/0001.json` `anonymity:"Y"`(수동 N 무시). 같은 파일의 `pseudonymity/privacy_included` 는 수동값 `Y/Y` 반영 → **anonymity 만 산출종류 파생** 확증. [정적] `NiaJsonBuilder.java:150-154` | TC-EXPORT-022 와 쌍 |
| TC-META-035 | 벌크 저장 N+1 제거 | PASS | [실동작] 4프레임 벌크 PUT 의 hibernate SQL 실측 = `select … from LS_DATA_SRC`(findAllById) **1회** + `select … from LS_RAW_DATA_STATUS` **1회** + `LS_DATA_RAW` 조회 1회 + `update LS_DATA_SRC` 4회. 프레임 수만큼의 findById 없음. 로그 `bulk-updated count=4 rawSns=1`(인가 rawSn 1회). [정적] `:100-140` | update 4회는 행 단위 dirty flush(불가피) |
| TC-META-036 | 벌크 미존재 프레임 404 | PASS | [실동작] `items=[{117,Y/Y/Y},{9999999,N}]` → **404** "프레임을 찾을 수 없습니다."; 직후 DB 재조회로 srcSn 117 **3필드 NULL 유지**(첫 항목도 미반영 = 전체 롤백). [정적] `:101-118` | |
| TC-META-037 | 벌크 타 영상 403 | PASS | [실동작] WORKER 2001 로 `items=[{117(raw145, 미배정)},{180(raw168, 배정)}]` → **403** "본인에게 배정되지 않은 영상입니다."; 두 프레임 모두 DB 불변. 순서 검증: `[180(미인가 아님)…]` 대신 `[존재하는 미인가, 미존재]`·`[미존재, 존재하는 미인가]` 양방향 모두 **404 우선**(항목 순회 중 미존재 판정이 인가보다 앞) → 카탈로그의 "404先→403後" 보존 확인. [정적] `:114-123` | |
| TC-META-038 | 벌크 원자성 | PASS | [실동작] TC-META-036·037 의 실패 케이스에서 선행 항목 DB 미반영 확인(위). [정적] `:99` `@Transactional("controlTransactionManager")` | |
| TC-META-039 | APPROVED 후 수정 = 재export + 통지 디바운스 | PASS | [실동작] 168 프레임 180·182 벌크 PUT(04:13:35) → 축적 테이블 `ls_mon_noti_acml` **1행**(`export_rprcs_yn='Y'`, `chg_dtl_cn={"frames":{"180":["META_UPDATED"],"182":["META_UPDATED"]}}`) = rawSn 단위 코얼레스. flush(04:14:38) `flush rawSn=168 regen=true frames=180=[META_UPDATED],182=[META_UPDATED]` → `export succeeded rawSn=168 version=3`(.788) → `TASK_MODIFIED sent rawSn=168 frames=2 videoLevel=0 reExport=true`(.811) — **export 선행, 통지 후행**. [정적] `:126-135,153-155` | |
| TC-META-040 | 벌크 항목 수 초과/빈 목록 | PASS | [실동작] `items:[]` → **400** "items 는 1건 이상이어야 합니다."; 5,001건 → **400** "items 는 5000건 이하여야 합니다.". [정적] `FramePrivacyBulkRequest MAX_ITEMS=5000`, `FramePrivacyMetaController.java:102-108` | |
| TC-META-041 | WORKER 본인배정/미인증 | PASS | [실동작] 미인증 GET/PUT **401**; PORTAL_USER 단건·벌크 **403**; 미배정 WORKER 2099 **403**; 미존재 프레임 **404**. 배정된 WORKER 2001 의 자기 프레임(180) 은 정상 처리. [정적] `:66-75,119-123` | |
| TC-META-042 | 로그 판단값 미출력 | PASS | [실동작] 단건 로그 `[FramePrivacyMeta] updated srcSn=182 rawSn=168`, 벌크 로그 `[FramePrivacyMeta] bulk-updated count=4 rawSns=1` — **Y/N 판단값·본문 0건**. 400/403/404 응답도 필드명·일반 메시지만 | CWE-359 |
| TC-META-043 | 단건 수정도 재export 트리거 | PASS | [실동작] 168 프레임 182 **단건** PUT(04:15:06) → 축적 `{"frames":{"182":["META_UPDATED"]}}` `export_rprcs_yn='Y'` → flush(04:16:08) `regen=true` → `export succeeded rawSn=168 version=4` → `TASK_MODIFIED … frames=1 reExport=true`. `v4/orgnl/0001.json` `privacy_included:"Y"` 반영 확인. [정적] `:153-155` | 재export 7경로 중 "개인정보 메타 수정" 확증 |
| TC-META-044 | 파생영상 프레임의 개인정보 3필드 상속 | PASS | [실동작] 부모 168(프레임 180=`Y/Y/Y`, 182=`null/null/Y`)에서 `POST /v1/videos/168/resolution {"presets":["RESL_720P"]}` → 파생 rawSn=181 생성. 파생 프레임 `src_sn=231 → Y/Y/Y`, `232 → null/null/Y` 로 **부모값 그대로 복사, 부모 null 은 파생도 null**. 파생 프레임 GET 은 파생 폴백 적용(`231` → `Y/Y/Y`). [정적] 해상도 `ResolutionPersistService.java:276-292`, 증강 `AugmentExtractPersist.java:96-115` — 두 경로 동일 코드형(`parent==null?null:parent.getXxx()`) | 증강 경로는 정적(외부 위탁 왕복 불필요 판단) |

## 근거 드리프트

**실질 드리프트 0건.** 아래 3건은 인용 범위가 주석/메서드 시작 1~4행 어긋나는 수준으로 대상 코드는 정확히 지목한다.

| 케이스 | 카탈로그 인용 | 실제 | 비고 |
|---|---|---|---|
| TC-META-001 | `EnvironmentMetaService.java:192-211` | `toResponse` 본문 193-211 (192는 Javadoc) | 무해 |
| TC-META-017 | `AsyncDatasetExportRunner.java:90-106` | `runReExportThenNotify` 91-106 (90은 `@Async`) | 무해 |
| TC-META-034 | `NiaJsonBuilder.java:150-153` | ★#1 주석 150-153 + 결정문 154 | 무해 |

- ⚠ **기대문 경로 드리프트 1건(판정 무영향)**: `TC-META-007` 은 "허용값이 모두 컬럼 길이 이내라 **화이트리스트 검사에 함께 걸림**"으로 적었으나 실제 선차단은 DTO `@Size(max=20)` 다(서비스 화이트리스트에 도달하지 않음). 결과 코드·메시지 톤 모두 400 이라 PASS 유지. 차기 최신화 시 기대문 정정 권고.

---

## 이슈 상세

### [E-ISSUE-81] TC-META-031 / TC-DEID-035 — `PUT /v1/frames/{srcSn}/privacy-meta` 가 비식별 신고 게이트·작업락 어디에도 걸리지 않아, 신고가 리셋한 개인정보 3필드를 신고 구간에 즉시 되돌릴 수 있다
- **심각도**: HIGH (B-ISSUE-81 독립 재확인 — 본 part 가 결함의 본진)
- **기대 동작(기대효과)**: 비식별 누락 신고는 "그 영상의 비식별이 잘못됐다"는 신호이며, `DeidentReportService` 가 개인정보 3필드(익명/가명/PII 포함여부)를 **재판정 대상으로 리셋**한다(`CLAUDE.md` 개인정보 보호 절). 따라서 신고가 열려 있는 동안(`DE_IDNTF_YN='F'` + 작업락 `LOCKED`) 그 3필드에 대한 **쓰기는 차단**되어야 한다 — 라벨 저장이 409 로 막히는 것과 동일 축이다. 최소한 작업락 409, 정책상으로는 신고 게이트 412 가 맞다.
- **현재 동작(이슈 내용)** [실동작]:
  - rawSn 133(`DE_IDNTF_YN='F'`, `LS_DEIDENT_REPORT` sn=3 `OPEN`, `LS_AUTH_WORK_LOCK` `LOCKED`)의 프레임 srcSn 78 에 대해
    `PUT /v1/frames/78/privacy-meta {"srcSn":78,"anonymity":"Y","pseudonymity":"Y","privacyIncluded":"Y"}` → **200**.
  - DB 즉시 확인: `ls_data_src(78)` 의 `anony_incl_yn/psdo_incl_yn/prvc_incl_yn` 이 `NULL,NULL,NULL` → `Y,Y,Y` 로 **실제 기록**됨(신고 시 `privacyReset=6` 로 지운 값의 복귀).
  - 같은 시각 대조군: `GET /v1/frames/78/labels` = **412**, `PUT /v1/frames/78/labels` = **409**(작업락). 즉 **라벨 축만 닫혀 있고 메타 축은 열려 있다.**
  - 코드 근거 — `FramePrivacyMetaService.java:74-78`:
    ```java
    public FramePrivacyMetaResponse update(Long srcSn, FramePrivacyMetaUpdateRequest req, TokenClaims actor) {
        LsDataSrc src = guard.verifyAndGet(srcSn, actor);   // 인가만 수행
        applyAndNotify(src, req.anonymity(), req.pseudonymity(), req.privacyIncluded(), actor);
    ```
    `DeidentReportGate`(판정 단일 원천) 주입 자체가 없고, `updateBulk:100-140` 에도 없다. `grep DeidentReportGate` → `dataset` 패키지 전체 0건.
- **재현/확인 경로**:
  ```bash
  TOK=$(curl -s -XPOST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"REVIEWER","channel":"INTERNAL"}' | jq -r .data.token)
  curl -s -o /dev/null -w '%{http_code}\n' -XPUT localhost:18081/api/v1/frames/78/privacy-meta \
    -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d '{"srcSn":78,"anonymity":"Y","pseudonymity":"Y","privacyIncluded":"Y"}'   # => 200
  ```
  ```sql
  SELECT src_sn, anony_incl_yn, psdo_incl_yn, prvc_incl_yn FROM ls_data_src WHERE src_sn=78;
  ```
- **영향**: `TC-DEID-035` 의 명시 목적("stale PII 방지")이 성립하지 않는다. 재비식별 전의 낡은 개인정보 판정이 그대로 복원되어 승인·export·데이터마트 뷰로 전파될 수 있다(APPROVED 영상이면 `pseudonymity`/`privacy_included` 로 export JSON 에 직접 실린다). **CWE-359**(개인정보 오표기 노출) / **CWE-362**(신고 처리 중 상태 변경). 작업락을 무시한다는 점에서 **CWE-863**(부정확한 인가) 성격도 있다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `FramePrivacyMetaService.update`/`updateBulk` 진입부(인가 직후)에 `DeidentReportGate` 판정을 추가한다 — 라벨 저장과 동일하게 **작업락 409 우선, 신고 412** 규약을 따르면 응답 코드 오라클(CWE-209) 위험 없이 기존 규약과 정합한다. 벌크는 rawSn distinct 집합 기준 1회 판정으로 N+1 없이 가능하다.

### [E-ISSUE-82] TC-META-003 — `PUT /v1/videos/{rawSn}/environment-meta` 도 같은 게이트 미배선. APPROVED 영상이면 신고 구간에 **동결 스냅샷만 갱신되고 export 는 차단**되어 뷰와 산출물이 갈린다
- **심각도**: HIGH (신규 — B-part5 미탐지 표면)
- **기대 동작(기대효과)**: 신고 구간에는 그 영상의 산출 계열 상태를 바꾸는 쓰기가 차단되어야 한다. 특히 촬영환경 수정은 APPROVED 영상에서 **동결 스냅샷 재동결(→ `V_COMPLETED_VIDEO` 즉시 변경) + export 새 버전 전량 재생성 + 관제 통지**를 연쇄 유발하는 무거운 경로라, 신고로 산출이 보류된 구간에서는 시작 자체가 막혀야 한다.
- **현재 동작(이슈 내용)** [실동작]:
  - rawSn 133(신고 OPEN + 작업락 LOCKED)에 `PUT /v1/videos/133/environment-meta {"weather":"비","timeOfDay":"DAY","season":"FALL"}` → **200**, `ls_data_raw(133)` 3필드 실제 기록.
  - 코드 근거 — `EnvironmentMetaService.java:100-112`: `accessGuard.verifyRawAccess(rawSn, actor)` → 값 검증 → `raw.changeShootingEnvironment(...)`. 게이트 판정 호출 없음.
  - **APPROVED 영상에서의 파생 피해(실측 관측)**: rawSn 169 에서 `PUT environment-meta` 로 재동결이 커밋된 뒤(04:10:18 `materialized … inserted=true`), 60초 디바운스 flush 시점(04:11:28)에 그 사이 접수된 신고 때문에 `[DatasetExport] export blocked — deident report open rawSn=169` → `async export failed rawSn=169`. 결과적으로 **①`LS_DATASET_VIDEO_META`(=`V_COMPLETED_VIDEO`)는 새 촬영환경으로 갱신되고 ②export 폴더는 옛 값 그대로** 남았다. 관제 계약상 뷰와 폴더는 같은 승인분을 가리켜야 하는데 갈린다.
  - **형제 결함 동시 재확인**: `PUT /v1/frames/78/description` 도 신고 구간에 **200**(작업락 무시) — B-ISSUE-82 미해소. `FrameDescriptionService` 역시 `exportRegenerated=true` 로 발행하므로(`:59-62`) 위와 같은 갈림을 만든다.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -XPUT localhost:18081/api/v1/videos/133/environment-meta \
    -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d '{"weather":"비","timeOfDay":"DAY","season":"FALL"}'    # => 200
  ```
  ```sql
  SELECT raw_sn, wthr_nm, day_ngt_cd, sesn_cd, de_ident_yn FROM ls_data_raw WHERE raw_sn=133;
  ```
- **영향**: ①신고 구간 산출 보류 정책의 우회 ②뷰(즉시 갱신) ↔ export 폴더(보류) 불일치로 관제가 서로 다른 촬영환경을 본다 — `CLAUDE.md` "통지는 export 성공 후" 직렬화가 지키려던 불변식이 이 경로에서 깨진다. **CWE-362**(TOCTOU/상태 경합) / **CWE-863**. `EnvironmentMetaService` 는 `CLAUDE.md` 의 재export 7경로 중 하나로 명시된 서비스라 정책적 비중이 크다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `EnvironmentMetaService.update`(및 `FrameDescriptionService.update`) 진입부에 `DeidentReportGate` 판정을 추가한다. 게이트 배선을 서비스마다 손으로 다는 방식이 반복 누락의 원인이므로(메모리 `state-gate-single-entry-point-rule`), **`TaskModifiedEvent(exportRegenerated=true)` 를 발행하는 모든 서비스에 게이트를 강제하는 구조적 장치**(공통 진입점 또는 클래스패스 스캔 가드 테스트)를 함께 검토할 것.

### [E-ISSUE-83] TC-META-030 / TC-META-034 — 적재가 `PRVC_TYPE_CD='ANONY'` 상수를 쓰고 관제 원천을 읽지 않아 export `pseudonymity`/`privacy_included` 가 항상 `N`, 프레임 프리필 `anonymity` 가 항상 `Y` 로 고정
- **심각도**: MEDIUM (1차 E-ISSUE-42 의 잔여 축 — 코드에 "의도적 미해소"로 명시되어 있으나 산출물 오염은 실재)
- **기대 동작(기대효과)**: 개인정보 유형의 원천은 관제 `MNG_CLIP_EVNT_LST.PRVC_TYPE_CD` 다(메모리 `control-clip-meta-source-of-truth`). 원천을 읽지 못하면 `null`(미상)이어야 하고, 상수로 특정 값을 단정해서는 안 된다.
- **현재 동작(이슈 내용)**:
  - [실동작] `ls_data_raw` 전 영상 `prvc_type_cd='ANONY'`, `prvc_yn='N'`(47건 전수).
  - [정적] `TrainingVideoIngestTx.java:64-67,116-119`:
    ```java
    /** 비식별 유형 기본값. 전체 비식별 정책상 ANONY 로 적재한다(파이프라인이 무조건 비식별 수행). */
    private static final String DEFAULT_PRVC_TYPE = LsDataRaw.PRVC_TYPE_ANONY;
    ...
    LsDataRaw.createFromIngest(vmsClipId, clip.getVmsCctvId(), evntTypeCd, clip.getLclgvCd(), DEFAULT_PRVC_TYPE, ...)
    ```
  - 파급 — [실동작] 프레임 수동 입력이 없는 모든 프레임에서 `GET privacy-meta` → `{anonymity:"Y", pseudonymity:"N", privacyIncluded:"N"}`(srcSn 117 실측), export `image.pseudonymity="N"`, `image.privacy_included="N"`(rawSn 169 `v1` 실측). 관제 실값이 `PSDO`(가명)여도 그대로 `N` 이 나간다.
  - [정적] `MngClipEvntLst.java:28-32` 가 이 사실을 **미해소로 명기**: "관제 원천이 여기 실재하는데도 적재는 `DEFAULT_PRVC_TYPE` 하드코딩을 쓴다 … 코드도메인 확정 전에는 매핑 자체가 추정이 되므로 의도적으로 미해소로 둔다".
  - **본 환경 한계**: 로컬 `MNG_CLIP_EVNT_LST` 는 V63 stub(`EVNT_ID`,`EVNT_TYPE_CD`,`SHT_DT` 3컬럼)이라 `PRVC_TYPE_CD` 컬럼 자체가 없다 → 관제 실값과의 1:1 대조는 이 환경에서 **수행 불가**(값 불일치를 실증하지 못했고, 원천 미독 사실만 확증).
- **재현/확인 경로**:
  ```sql
  SELECT DISTINCT prvc_type_cd, prvc_yn, count(*) FROM ls_data_raw GROUP BY 1,2;   -- ANONY|N 만
  SELECT column_name FROM information_schema.columns WHERE table_name='mng_clip_evnt_lst';  -- 3컬럼(stub)
  ```
  운영 DB 에서는 `SELECT prvc_type_cd FROM MNG_CLIP_EVNT_LST WHERE evnt_id=?` 로 실값 대조 필요.
- **영향**: 학습데이터 속성(가명/개인정보 포함여부)이 사실과 다를 수 있고, 데이터마트가 이 속성으로 필터링하면 오염이 전파된다. 촬영환경 3필드는 "미상=null" 로 정리됐는데 이 축만 "상수 단정"이 남아 self-fill 금지 원칙의 유일한 잔존 위반이다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①관제 코드도메인(ERD-024) 확보 → `MngClipEvntLst` 에 `PRVC_TYPE_CD` 매핑 추가 → 적재 시 관제 실값 우선, 부재면 `null` ②확보 전이라면 `DEFAULT_PRVC_TYPE` 을 `null` 로 바꾸고 파생 프리필도 `null`(미상)로 내려 "상수 단정"을 제거(촬영환경 3필드와 동일 처방) ③둘 다 어려우면 최소한 export 소비자가 구분할 수 있도록 출처 표기 추가.

### [E-ISSUE-84] TC-META-017 — `EnvironmentMetaController` Swagger 설명이 폐기된 구 정책("편집은 export 재생성을 트리거하지 않음")을 그대로 유지해 실동작과 정면 배치
- **심각도**: LOW (문서 드리프트 — 외부 계약 문서에 노출)
- **기대 동작(기대효과)**: 공개 API 문서(Swagger)는 실동작과 일치해야 한다. 2026-07-27 확정 정책(C-1b)은 "APPROVED 후 촬영환경 수정 = export 새 버전 `v{n+1}` 전량 재생성"이다.
- **현재 동작(이슈 내용)** [정적] `EnvironmentMetaController.java:66-68`:
  ```java
  + "검수 완료 후 수정 시 동결 스냅샷만 재동결(데이터마트 뷰에 최신값 반영)되고 관제 "
  + "TASK_MODIFIED(META_UPDATED) 통지가 발행된다. 편집은 export 파일 재생성을 트리거하지 않으며(라벨 수정과 동일 정책), "
  + "export 폴더는 다음 검수 승인 시점에 전량 재산출된다. WORKER 는 본인 배정 영상만.")
  ```
  [실동작] 실제로는 rawSn 168 PUT 이 60초 내에 `v2` 를 전량 재생성했다(TC-META-017 근거). 즉 **"트리거하지 않는다"·"다음 검수 승인 시점에 재산출"이 둘 다 거짓**이다. 서비스 Javadoc(`EnvironmentMetaService.java:76-78,145-150`)은 신정책으로 정확히 갱신돼 있어 컨트롤러 설명만 뒤처졌다.
- **재현/확인 경로**: `GET /swagger-ui/index.html` → EnvironmentMeta → PUT 설명 문구 ↔ `ls_dataset_export` 버전 증가 실측 대조.
- **영향**: FE·관제·감리가 Swagger 를 계약 정본으로 읽으면 "수정해도 파일은 안 바뀐다"고 오판해, 데이터마트 동기화 요구의 충족 여부를 잘못 판단한다. 폐기 케이스 `TC-META-009` 와 같은 문장이라 폐기 정책이 문서에 살아남은 형태다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 해당 3줄을 "검수 완료 후 수정 시 동결 스냅샷 재동결 + **export 새 버전 폴더로 전량 재생성** 후 관제 `TASK_MODIFIED(META_UPDATED)` 통지(통지는 export 성공 후)" 로 교체.

### [E-ISSUE-85] TC-META-002 — 조회 프리필(DERIVED)의 MANUAL 승격을 BE 가 막지 못해, FE 이외 클라이언트는 추정값을 출처 구분자 없이 동결·export 로 밀어넣을 수 있다
- **심각도**: LOW (코드에 "알려진 한계"로 명시 · 현 FE 는 규율 준수 확인)
- **기대 동작(기대효과)**: self-fill 금지의 보증은 서버가 져야 한다. 동결·export 에는 출처 구분자가 없으므로("동결된 non-null = 전부 수동값" 단언에 의존), 추정값이 수동값으로 승격되는 경로가 서버 차원에서 닫혀 있어야 한다.
- **현재 동작(이슈 내용)**:
  - [정적] `EnvironmentMetaService.java:85-91` 이 한계를 명문화: "BE 는 전송값의 출처를 알 수 없어 DERIVED 프리필의 MANUAL 승격을 **막지 못한다** … 이 API 는 공개 계약이라 다른 클라이언트는 프리필을 그대로 되돌려 보내 추정값을 수동값으로 승격시킬 수 있다".
  - [실동작] 재현 확인: 145 GET → `timeOfDay:"NGT"(DERIVED)` → 같은 값을 그대로 PUT → 응답 `timeOfDaySource:"MANUAL"`, DB `day_ngt_cd='NGT'` 저장. 이후 승인되면 그 추정값이 그대로 동결·export 된다.
  - 완화 확인 — FE 는 규율을 지킨다: `frontend/src/features/label/components/EnvironmentMetaPanel.tsx:64-112` `resolveField(…, source)` 가 손대지 않은 DERIVED 필드를 `null` 로 전송.
- **재현/확인 경로**: 위 GET→그대로 PUT→`SELECT day_ngt_cd FROM ls_data_raw WHERE raw_sn=145;`
- **영향**: E-ISSUE-42 가 닫은 self-fill 경로가 클라이언트 규율에만 의존해 재개방될 수 있다. 현 시점 실제 오염은 관측되지 않았다(모든 동결 스냅샷 3필드 null).
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①요청에 항목별 `source` 축을 추가해 `MANUAL` 만 저장(계약 변경) ②또는 저장 시 "전송값 == 현재 파생 프리필값 && 기존 수동값 없음" 이면 수동값으로 승격하지 않고 null 유지 — 둘 다 하위호환 영향이 있으므로 FE 계약과 함께 결정.

### [E-ISSUE-86] TC-META-020 — 촬영환경 정정 백필의 **실행 경로**(재동결→재export→통지, `max-per-run` 상한)가 대상 0건이라 실동작으로 확증되지 않음
- **심각도**: LOW (검증 커버리지 공백 — 제품 결함 아님)
- **기대 동작**: `POST /v1/dev/dataset-video-meta/shooting-env-corrections` 1회 실행이 대상 최대 200건을 정정하고, 정정 1건마다 재동결 → `TaskModifiedEvent(exportRegenerated=true)` → export 새 버전 재생성 → 통지가 이어져야 한다. 재호출은 자연 멱등(0건).
- **현재 상태** [실동작]: 현 스택은 2026-07-31 재구축분이라 "파생 폐기 이전에 동결된 레거시 행"이 없다 — `GET .../shooting-env-correction-targets` → `{"targetCount":0}`, 판별식 SQL 직접 실행도 0건. 따라서 `POST` 는 `{"corrected":0,"remaining":0,"completed":true}` 만 반환하고 루프 본문에 진입하지 않았다(로그도 `no target`). API 형태(sub-resource 분리 · REVIEWER 전용 · 멱등 응답)와 인가(403/401)는 실동작 확인 완료.
- **재현/확인 경로**: 레거시 상태를 인위로 만들려면 `ls_dataset_video_meta` 활성행의 `day_ngt_cd/sesn_cd` 를 직접 UPDATE 해야 하는데, ①`SNPSHT_HASH` 멱등 계약이 깨지고 ②동시 검증 중인 타 에이전트의 영상을 오염시키므로 **의도적으로 수행하지 않았다**.
- **영향**: 상한 소진·부분 진행·실패 격리 동작이 실환경에서 미확증. 정적 근거(`DatasetVideoMetaBackfillService.java:161-215`, `DatasetVideoMetaEnvCorrectionTx.correct:60-88`)와 IT 6건(`DatasetVideoMetaEnvCorrectionIT`)은 갖춰져 있다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 레거시 동결행이 실재하는 dev(246) 스냅샷에서 별도 회차로 재검증하거나, 검증 전용 시드(파생값으로 동결된 영상 1건)를 `dev-seed.sql` 에 추가하는 방안 검토.
