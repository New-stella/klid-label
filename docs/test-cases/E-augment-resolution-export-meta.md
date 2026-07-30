# E. 증강 + 해상도 파생 + Export + 메타 입력 — 테스트 케이스

> 247 케이스(표 행 실측) · 계층: unit / integration / security · [← README](README.md)

## 변경 이력

| 회차 | 일자 | 정정 | 신규 | 폐기 | 요약 |
|:--:|------|:--:|:--:|:--:|------|
| 1 | 2026-07-30 | 39건(+근거 전 행 재확인) | 92건 | 8건 | Phase 7·8 전체 반영 — 증강 외부 위탁 실배선(`HttpExternalAugmentClient` · job_id 외부 발급 · 100장 청크 · `POST /v1/genai/callback` 신설, 구 `/v1/aug/callback`·`AugmentResultRequest` 폐기) · 만료 스윕/dead-letter 집계축 · 중복 증강 409(V143) · **파생 생성 ↔ 비식별 신고 분리**('F' 통과, 'N'만 차단) · `ParentDeidArtifactGuard` · stale 창 판정축 교체(복사 원자성) · 레터박스 종횡비 보존 좌표 변환 · 파생 비디오 실제 복사 · export co-locate/재생성/통지 순서 · 촬영환경 self-fill 제거 · export JSON 키 `event_annotation`→`event` · **E-5B 신설**(해상도 파생 산출물 비식별 저장소 이관 백필 — 순서 계약·실패 지점별 원본 유실 0·경로 가드·캐시 무효화·유예 삭제 스윕) |

> **표기 규칙** — 케이스명 끝 ` (신규)` = 이번 회차 추가, 케이스명 `~~취소선~~` + 기대결과 `**[폐기 2026-07-30]**` = 정책 변경으로 무효화(추적성 위해 행 보존).

---

## E-1. 증강 요청 (AugmentRequestService / AugmentController / AugmentRequestBridge)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-AUG-001 | 검수완료 영상 증강 요청 성공 (단건 계약) | APPROVED 영상 1건, 대표프레임 존재 | REVIEWER, `{videoIds:[N], types:["WINTER"]}` | 200, `{jobId, requestedAt, videoCount:1, typeCount:1, createdCount:1}`. `LS_DATA_AUG` PENDING 1행 INSERT(`IDMP_KEY=AUG-<uuid>`, `OTSD_JOB_ID`는 **null** — 외부 202 에서 발급) | integration | High | AugmentRequestService.java:114-188 |
| TC-AUG-002 | 미검수 영상 거부 | 대상이 APPROVED 아님 | REVIEWER | 400 `NOT_REVIEWED` + `data.blockedVideoIds`, aug 행 0건 | integration | High | AugmentRequestService.java:127-137 |
| TC-AUG-003 | ~~videoIds/types 중복 정규화~~ | — | `videoIds:[13,13]` | **[폐기 2026-07-30]** 단건 계약 확정(E-ISSUE-08)으로 distinct 정규화 로직이 **삭제**됐다. DTO `@Size(max=1)` + 서비스 `requireSingleSelection` 이 2건 이상을 400 으로 거부하므로 distinct 가 성립할 입력 자체가 없다 → 대체 케이스는 TC-AUG-017 | unit | — | AugmentRequestService.java:285-290; AugmentRequestRequest.java:27-33 |
| TC-AUG-004 | **프레임 미추출 영상 = 412 거부** | 대표프레임(MIN SRC_SN) 없음 | REVIEWER | **412 PRECONDITION_FAILED** + `data.skippedVideoIds=[rawSn]`, aug 행 0건. 구 "건별 스킵 + 200" 폐기(silent no-op 제거) | integration | High | AugmentRequestService.java:156-164 |
| TC-AUG-005 | WORKER 증강 요청 차단 | WORKER 토큰 | POST /v1/augments/request | 403 (Controller `@PreAuthorize` 1차 + 서비스 `requireReviewer` 2차) | security | High | AugmentRequestService.java:381-388; AugmentController.java:109 |
| TC-AUG-006 | 인증 토큰 없음 | actor=null | 요청 | 401 UNAUTHORIZED | security | High | AugmentRequestService.java:382-384 |
| TC-AUG-007 | **idempotencyKey만 발급 · externalJobId 미발급** | 정상 요청 | 내부 발급 | `IDMP_KEY=AUG-<uuid>`(≤64, `^[A-Za-z0-9_-]+$`), 형식 위반 시 INTERNAL_ERROR. `OTSD_JOB_ID`는 요청 시점 **null**(외부 202 응답 job_id 로 반전 — 구 `JOB-<uuid>` 자체 발급 폐기) | unit | High | AugmentRequestService.java:311-317,344-351 |
| TC-AUG-008 | 적재 실패 시 실패 회신 | `augRepository.save` 예외 | REVIEWER | 500 INTERNAL_ERROR("증강 요청을 생성하지 못했습니다.") + `skippedVideoIds`. 예외를 삼키고 200 을 주지 않는다 | unit | Med | AugmentRequestService.java:176-181,336-341 |
| TC-AUG-009 | AFTER_COMMIT — 롤백 시 행·위탁 미발생 | 요청 tx 롤백 | — | aug 행 0건 + 외부 위탁 0회. `LS_WEBHOOK_IDEMPOTENCY` 선기록은 **제거**됨(발급 원장은 `LS_DATA_AUG_JOB.IDMP_KEY`) | integration | High | AugmentRequestBridge.java:29-35,92-96 |
| TC-AUG-010 | 콜백 URL 조립 | base trailing slash/blank | 요청 | `AugmentCallbackUrlResolver.resolve()` = base 정규화 + **`/v1/genai/callback`**(구 `/v1/aug/callback` 폐기) | unit | Med | AugmentCallbackUrlResolver.java; WebhookProtectedPaths.java:50 |
| TC-AUG-011 | jobId 동시성 유일성 | 다중 동시 요청 | REVIEWER | AtomicLong 충돌 없음(응답 placeholder — 미영속) | unit | Low | AugmentRequestService.java:102,183 |
| TC-AUG-012 | **중복 활성 요청 차단 — PENDING 안내 (신규)** | 같은 (대표프레임 × 종류) PENDING 존재 | 동일 요청 재전송 | 409 CONFLICT, 메시지 "이미 요청되어 진행 중인 증강입니다. 결과가 도착해 완료되거나 검수에서 반려된 뒤 다시 요청하세요.", `data.duplicatedRequests[{videoId,type,status:"PENDING"}]` | integration | High | AugmentRequestService.java:212-276 |
| TC-AUG-013 | **중복 활성 요청 차단 — ACCEPTED 안내 (신규)** | 같은 (대표프레임 × 종류) ACCEPTED 존재 | 동일 요청 | 409, 메시지 "이미 채택된 증강입니다. …같은 영상·종류로는 다시 요청할 수 없습니다." — `applyReviewStatus` 가 PENDING 에서만 전이하므로 "반려 후 재요청" 안내를 하지 않는다 | unit | High | AugmentRequestService.java:264-276; LsDataAug.java:264 |
| TC-AUG-014 | **동시 요청 최종 방어 = DB 부분 유니크 (신규)** | 두 tx 가 서로의 미커밋 행 미관측 | 동시 동일 요청 | `UK_LS_DATA_AUG_ACTVTN`(V143) 위반 → `DataIntegrityViolationException` 을 **삼키지 않고** 409 로 종결(요청 tx 롤백 — PG 는 제약 위반 시 tx 전체 abort) | integration | High | AugmentRequestService.java:324-335; V143__add_ls_data_aug_active_unique.sql |
| TC-AUG-015 | **REJECTED 후 재요청 허용 (신규)** | 같은 (프레임 × 종류) REJECTED 만 존재 | 재요청 | 200 정상 접수 — `ACTIVE_STATUSES`(PENDING/ACCEPTED)에 REJECTED 미포함, 인덱스 술어와 동일 | integration | High | LsDataAug.java:60; V143 (인덱스 WHERE 절) |
| TC-AUG-016 | **비식별 누락 신고 구간 요청 차단 (신규)** | 대상 영상 `DE_IDNTF_YN='F'` | REVIEWER 요청 | 412 PRECONDITION_FAILED("비식별 재처리 대기 중인 영상은 증강을 요청할 수 없습니다.") + `blockedVideoIds`. 판정은 `DeidentReportGate` 단일 원천 | security | High | AugmentRequestService.java:143-153 |
| TC-AUG-017 | **단건 계약 위반 거부 (신규)** | — | `videoIds:[1,2]` 또는 `types` 2건 | 400 INVALID_INPUT — DTO `@Size(max=1)` 1차 + 서비스 `requireSingleSelection` fail-closed 2차(초과분 조용한 절단 금지) | unit | High | AugmentRequestRequest.java:27-33; AugmentRequestService.java:285-290 |
| TC-AUG-018 | **V143 선행 정리 — ACCEPTED 2건이면 마이그레이션 중단 (신규)** | 같은 (SRC_SN, AUG_TYPE_CD) ACCEPTED 2건 | Flyway V143 실행 | `RAISE EXCEPTION` 으로 중단(기동 실패), 강등하지 않음. PENDING 중복만 REJECTED 로 종결하고 행·검수이력은 보존 | integration | High | V143__add_ls_data_aug_active_unique.sql (DO $$ 가드 + WITH ranked UPDATE) |

## E-2. 증강 검수 (AugmentReviewService)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-AUG-020 | 증강 결과 승인 PENDING→ACCEPTED | PENDING 행 | REVIEWER accept | 200, ACCEPTED, `LS_DATA_AUG_RVW` accept, syncDecision best-effort | integration | High | AugmentReviewService.java:336-354 |
| TC-AUG-021 | 이미 처리된 결과 재승인 차단 | ACCEPTED/REJECTED | accept | 409("이미 처리된 증강 결과") — `applyReviewStatus` 가 PENDING 에서만 전이 | unit | High | LsDataAug.java:264 |
| TC-AUG-022 | 반려 사유 누락 거부 | PENDING | reject reason=null/blank | 400("반려 사유는 필수입니다.") — DTO 1차 + 서비스 2차 | unit | High | AugmentReviewService.java:363-365 |
| TC-AUG-023 | 반려 정상 PENDING→REJECTED | PENDING | REVIEWER reject+사유 | 200, REJECTED, RVW reject | integration | High | AugmentReviewService.java:360-381 |
| TC-AUG-024 | **해상도 파생 accept 차단** | `AUG_TYPE_CD=RESL_720P` | accept | 400("해상도 파생 결과는 검수 대상이 아닙니다.") | unit | High | AugmentReviewService.java:421-431 |
| TC-AUG-025 | 해상도 파생 reject 차단 | RESL_ 행 | reject | 400(`loadOrThrow` 게이트 동일) | unit | High | AugmentReviewService.java:427-429 |
| TC-AUG-026 | 존재하지 않는 증강행 검수 | 미존재 dataAugSn | accept | 404 | unit | Med | AugmentReviewService.java:422-423 |
| TC-AUG-027 | 외부 sync 실패 best-effort | `syncDecision` throw | accept | 본 tx 영향 없음(warn), 200. ⚠ 현 활성 구현 `HttpExternalAugmentClient.syncDecision` 은 외부 계약 미정의라 **no-op(로그 후 true)** — throw 분기는 프로덕션 도달 불가(단위 스텁으로만 검증 가능) | unit | Med | AugmentReviewService.java:346-351; HttpExternalAugmentClient.java:83-88 |
| TC-AUG-028 | 검수 WORKER/미인증 차단 | WORKER·null | accept/reject | 403/401 | security | High | AugmentReviewService.java:433-440 |
| TC-AUG-029 | 잡카드 전체 페이징 — SRC_SN 그룹 최신순 | 다수 행 | `listAll(page,size)` | Page, 영상단위 그룹핑, 일괄 조회 4종(row/rawSn/cctv/review)로 N+1 없음 | integration | Med | AugmentReviewService.java:81-89,148-170 |
| TC-AUG-030 | **types/resolutionTypes 분리 노출** | WINTER+RESL_720P 혼재 | 잡카드 | `types=[WINTER]`, `resolutionTypes=[RESL_720P]`(FE accept/reject 숨김) | unit | High | AugmentReviewService.java:185-190 |
| TC-AUG-031 | AUG_ORDER 정렬 | 여러 종류 | 잡카드 | WINTER→NIGHT→RAIN→RESOLUTION→RESL_1080P/720P/480P, 미정의 99 | unit | Low | AugmentReviewService.java:58-66 |
| TC-AUG-032 | **aggregateStatus — dead-letter→FAILED (실경로 도달)** | `DEAD_LETTER_AT` 존재 | 집계 | FAILED. E-ISSUE-06 해소 — `markDeadLetter()` 프로덕션 호출자 2곳 확보(`AugmentResultService.markProcessingFailure` 실패 인계 단일 깔때기, `AugmentExtractPersist.markAugProcessingFailed` async 추출 실패) | integration | High | AugmentReviewService.java:255-259; AugmentResultService.java:317-320; AugmentExtractPersist.java:186-203 |
| TC-AUG-033 | aggregateStatus — 전부 terminal→COMPLETED | 전 행 ACCEPTED/REJECTED, dead-letter 없음 | 집계 | COMPLETED, `completedAt=max(RVW_DT)` | unit | Med | AugmentReviewService.java:260-267 |
| TC-AUG-034 | aggregateStatus — 일부 종료→IN_PROGRESS / 전부 PENDING→REQUESTED | 혼합 / 전 PENDING | 집계 | IN_PROGRESS / REQUESTED | unit | Med | AugmentReviewService.java:260-267 |
| TC-AUG-035 | **해상도 in-flight 집계 정합** | RESL 예약 PENDING만 | 집계 | COMPLETED 아님(`markResolutionGenerated` 로 ACCEPTED 전이된 뒤에만 terminal) | integration | High | AugmentReviewService.java:243-250; LsDataAug.java:248 |
| TC-AUG-036 | **aggregateResultStatus 매핑 — FAILED 분기 도달 가능** | dead-letter 행 존재 | `/{jobId}/result` | COMPLETED / **FAILED** / PROCESSING. 구 "FAILED 도달 불가"(E-ISSUE-06)는 TC-AUG-032 배선으로 해소 | unit | Med | AugmentReviewService.java:119-136 |
| TC-AUG-037 | aggregateResultStatus SRC_SN 폴백/무데이터 | RAW_SN 매핑 없음 / 집계 0 | result | `findBySrcSnIn` 폴백, 전무 시 PROCESSING | unit | Low | AugmentReviewService.java:123-130 |
| TC-AUG-038 | cctvName 비-옵셔널 폴백 | 매핑/시드 부재 또는 blank | 잡카드 | `"(이름 없음)"` 폴백(항상 non-null) | unit | Low | AugmentReviewService.java:277-283 |
| TC-AUG-039 | findBySource(srcSn) 필터 | 매핑 row 없음 | `?srcSn=` | 빈 Page | unit | Low | AugmentReviewService.java:99-106 |
| TC-AUG-040 | list size 한도 초과 | `size>100`, srcSn 미지정 | GET /v1/augments | 400("size 한도 초과 (max=100)"). ⚠ `srcSn` 지정 시 분기가 앞서 size 검증 미적용 | unit | Med | AugmentController.java:81-88 |
| TC-AUG-041 | **검수 이력 rawSn 역해석 실패 = 409 (신규)** | `LS_DATA_AUG.SRC_SN` 이 삭제된 프레임을 가리킴 | accept/reject | 409 CONFLICT("증강 결과의 원본 영상 정보를 확인할 수 없어…"). 구 하드코딩 `0L`(존재하지 않는 영상 참조) 제거 — V146 FK 이후 500 이 되던 경로 | integration | High | AugmentReviewService.java:409-419; V146__add_ls_data_raw_child_fk.sql |
| TC-AUG-042 | **검수행 선재 재사용 (신규)** | 이미 `LS_DATA_AUG_RVW` 존재 | accept | 기존 행 재사용(역해석 미수행) — `findLatestByDataAugSn` 우선 | unit | Med | AugmentReviewService.java:389-395 |

## E-3. 증강 결과 웹훅 — `POST /v1/genai/callback` (GenAiCallbackController / GenAiCallbackService / AugmentJobRollup / AugmentResultService)

> **계약 전면 교체**: 구 `POST /v1/aug/callback` + `AugmentResultRequest`(증강 1건 = 콜백 1건, HMAC 서명)는 **제거**됐다. 신 계약은 「생성형 AI API 연동명세서 v1.1」 정합 — **job 단위** 웹훅(무서명 3계층 방어), 증강 1건은 100장 상한 때문에 여러 job 으로 분할 위탁되므로 **전 job 종결 후에만** 롤업으로 증강 1건을 확정한다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-AUG-050 | **전 job SUCCEEDED 롤업 → 신규 증강영상 생성** | job 전량 종결·전부 SUCCEEDED, 부모 `hasDeidentArtifact()` true, 프레임 존재 | 마지막 job `status=SUCCEEDED` | 200 `{applied:true, requestId}`, aug ACCEPTED, 새 `LS_DATA_RAW`(`ORGNL_RAW_SN`=부모, `DATA_STTS_CD=PENDING`, `DE_IDNTF_YN='N'`), `RAW_FILE_PATH_NM`=**자기 비식별 사본 경로**, AFTER_COMMIT `AsyncAugmentFrameRunner` 기동 | integration | High | GenAiCallbackService.java:96-164; AugmentJobRollup.java:64-70; AugmentResultService.java:405-436 |
| TC-AUG-051 | 실패 롤업 REJECTED·영상 미생성 + dead-letter | job 중 1건 이상 FAILED | 마지막 job 콜백 | aug REJECTED + `RTRY_NMTM`+1 + `DEAD_LETTER_AT` 기록, `createAugmentedVideo` 미호출. 부분 실패 = 전체 실패(fail-closed) | integration | High | AugmentJobRollup.java:72-77; AugmentResultService.java:226-232,317-320 |
| TC-AUG-052 | **재전송 멱등(1차 앵커) non-PENDING skip** | aug 이미 ACCEPTED | 동일 롤업 재진입 | `DUPLICATE` → 200 `{applied:false}`, 중복 영상 미생성 | integration | High | AugmentResultService.java:186-202; AugmentApplyResult.java:22 |
| TC-AUG-053 | **otsd_job_id 선점 — 재수신 200 / 타 증강 409** | ①자기 자신 보유 ②다른 증강 보유 | 롤업 | ① 1차 앵커/선점검사로 흡수 → 200 `applied:false` ② **쓰기 이전** 선점검사에서 409(트랜잭션 오염 없음). E-ISSUE-05(PG 25P02 500) 해소 — 위반 후 소유자 재조회는 `AugmentJobIdOwnerLookup`(REQUIRES_NEW)에서만 | integration | High | AugmentResultService.java:204-207,267-302 |
| TC-AUG-054 | ~~augType 불일치 차단~~ | row=WINTER, req=RAIN | 콜백 | **[폐기 2026-07-30]** augType 대조 제거 — 새 계약은 `request_id → LS_DATA_AUG_JOB → dataAugSn` 으로 대상을 역산하므로 외부가 종류를 잘못 실어 다른 행에 인계할 경로 자체가 없다 → 대체 오배송 방어는 TC-AUG-067(job_id mismatch) | unit | — | AugmentResultService.java:221-225 (주석) |
| TC-AUG-055 | **미발급 request_id = 401 / job 대비 aug 행 부재 = 404** | ①`LS_DATA_AUG_JOB.IDMP_KEY` 미존재 ②job 은 있으나 aug 행 없음 | 콜백 | ① 401 UNAUTHORIZED("발급되지 않은 request_id 입니다.") ② 404. 401 은 필터 rate-limit 에 집계돼 탐색 공격 차단 | security | High | GenAiCallbackService.java:100-111 |
| TC-AUG-056 | ~~부모 비식별 미완('F') PII 게이트~~ | 부모 `DE_IDNTF_YN='F'` | SUCCESS 롤업 | **[폐기 2026-07-30]** ★정책 반전 — "파생영상은 비식별 신고 체계 바깥"(2026-07-29 확정). 신고(`'F'`) 구간에도 **파생을 생성**한다. 신고가 막는 것은 외부 위탁뿐(TC-AUG-016 / TC-AUG-072) → 대체 케이스는 TC-AUG-064 | security | — | AugmentResultService.java:364-393; LsDataRaw.hasDeidentArtifact() |
| TC-AUG-057 | **부모 프레임 0건 = 실패 확정** | 부모 프레임 없음 | SUCCESS 롤업 | `ParentGate.FAIL` → aug **REJECTED + dead-letter**(구 "생성 보류" 폐기 — 재개 트리거가 없어 PENDING 영구 고착이 되므로). 영상 0건 | unit | High | AugmentResultService.java:389-392,213-219 |
| TC-AUG-058 | ~~rawFilePathNm SSRF 차단~~ | 내부망/metadata URL | 콜백 | **[폐기 2026-07-30]** 신 콜백 페이로드(`GenAiCallbackRequest`)에 `raw_file_path_nm` 필드가 없고 `AugmentOutcome.succeeded/failed` 가 항상 null 을 넣으므로 웹훅 경로로는 도달 불가. `validateFilePath`(SSRF+allowlist)는 다층 방어로 코드에 존치 → 대체는 TC-AUG-069(output_file_path 허용루트) | security | — | AugmentOutcome.java:25-31; AugmentResultService.java:515-533 |
| TC-AUG-059 | ~~augTypeCd 화이트리스트~~ | WINTER/NIGHT/RAIN 외 | 콜백 | **[폐기 2026-07-30]** `AugmentResultRequest` 자체가 제거됨(`aug_type_cd` 필드 부재) | unit | — | GenAiCallbackRequest.java:42-92 |
| TC-AUG-060 | **status 화이트리스트** | `status` 값 | 콜백 | `^(RUNNING\|SUCCEEDED\|FAILED)$` 외 400. `CANCELED` 는 웹훅으로 발신되지 않는다(취소는 만료 스윕이 회수 — TC-AUG-073) | unit | Med | GenAiCallbackRequest.java:60-64 |
| TC-AUG-061 | **request_id / job_id 패턴·길이** | 특수문자·초과 | 콜백 | `request_id` `^[A-Za-z0-9_-]+$` ≤128, `job_id` `^[A-Za-z0-9_.:-]+$` ≤200 위반 시 400 | security | Med | GenAiCallbackRequest.java:44-58 |
| TC-AUG-062 | ~~dataAugSn null·rawFilePathNm>1000~~ | 필수 누락/초과 | 콜백 | **[폐기 2026-07-30]** 두 필드 모두 신 페이로드에 없음 → 대체 필수값 검증은 TC-AUG-076 | unit | — | GenAiCallbackRequest.java |
| TC-AUG-063 | 동시 콜백 직렬화(FOR UPDATE) | 마지막 job 콜백 동시 2건 | 콜백 | ①`GenAiCallbackService` 가 **job 갱신 이전에** `findByDataAugSnForUpdate` 로 aug 행 잠금(순서 뒤집으면 롤업 통째 유실) ②`AugmentResultService.handle` 이 같은 행 잠금 + PENDING 앵커로 재방어 → 증강영상 정확히 1건 | integration | High | GenAiCallbackService.java:108-114,37-43; AugmentResultService.java:182-184 |
| TC-AUG-064 | **부모 비식별 미완('N') = 실패 확정 (신규)** | 부모 `DE_IDNTF_YN='N'`/null | SUCCESS 롤업 | `ParentGate.FAIL` → aug REJECTED + dead-letter, 영상 0건. 판정은 `LsDataRaw.hasDeidentArtifact()`(`'Y'`\|`'F'` 통과) 단일 헬퍼 — 해상도 파생 경로와 동일 축 | security | High | AugmentResultService.java:375-393 |
| TC-AUG-065 | **IP allowlist 미설정 = 전면 차단 (신규)** | `webhook.genai.allowed-ip-cidrs` 미설정/`none` | 콜백 | 403 (VLM allowlist 와 반대로 **fail-closed**) | security | High | GenAiWebhookIpAllowlist.java:34-73 |
| TC-AUG-066 | **위탁↔수신 배선 짝 기동 가드 (신규)** | `authoring.augment.external.mode=http` + allowlist 미설정 | 부팅 | `IllegalStateException` 으로 **기동 실패**. 위탁만 열리고 콜백이 전건 403 이면 aug 가 PENDING 영구 고착되므로 경고가 아니라 차단 | integration | High | GenAiIntegrationWiringGuard.java:33-75 |
| TC-AUG-067 | **job_id 오배송 차단 (신규)** | job 이 202 로 받아 둔 `externalJobId` 와 콜백 `job_id` 상이 | 콜백 | 409 CONFLICT("job_id 가 일치하지 않습니다."), 상태 미변경 | security | High | GenAiCallbackService.java:121-126 |
| TC-AUG-068 | **RUNNING 진행 갱신 = 롤업 없음 (신규)** | 비종결 job | `status=RUNNING` | job `JOB_STTS_CD=RUNNING` 갱신, 200 `applied:true`, 결과 처리·롤업 미수행 | unit | Med | GenAiCallbackService.java:136-142 |
| TC-AUG-069 | **output_file_path 허용 읽기루트 밖 = 400·상태 미변경 (신규)** | `results[].output_file_path` 가 `raw-mount-roots ∪ external-read-roots` 밖 | SUCCEEDED 콜백 | 400("output_file_path 가 허용된 저장 경로가 아닙니다."), **job 상태 미변경**(재전송 여지 보존), 메트릭 `augment.callback.rejected{reason=output_path}` + WARN. 경로 원문 미노출 | security | High | GenAiCallbackService.java:214-237; VideoArtifactRootResolver.java:373-374 |
| TC-AUG-070 | **읽기 루트 기본 빈값 = 쓰기축과 동일 fail-closed (신규)** | `authoring.storage.external-read-roots` 미설정 | 콜백 산출 경로 검증 | 읽기 허용 루트 = 쓰기 allowlist 만 → 벤더 전용 경로는 전부 400. 쓰기 base 를 넓히지 않고 축을 분리한다 | security | High | VideoArtifactRootResolver.java:63-64,90-108 |
| TC-AUG-071 | **위탁 건수 ↔ 수신 건수 불일치 = job FAILED (신규)** | `LS_DATA_AUG_JOB_FILE` N건 vs `results` M건(N≠M) | SUCCEEDED 콜백 | 짝짓기 불성립 → job `FAILED(ERR_CD=RESULT_COUNT_MISMATCH)`, 롤업 부분실패 규칙으로 증강 1건도 실패 | integration | High | GenAiCallbackService.java:175-193; LsDataAugJob.java:74 |
| TC-AUG-072 | **SUCCEEDED 인데 results 없음 = 400 (신규)** | `results` null/빈 배열 | SUCCEEDED 콜백 | 400("SUCCEEDED 콜백에는 results 가 필요합니다."), 메트릭 `reason=missing_results`. 빈 증강본 확정 차단 | unit | High | GenAiCallbackService.java:214-221 |
| TC-AUG-073 | **롤업 보류 = applied:false (신규)** | 2청크 중 1청크만 SUCCEEDED | 콜백 | `DEFERRED` → 200 `{applied:false}`. 구 "무조건 `applied:true`" 폐기 — 외부가 인계 완료로 오해하던 것 차단(job 상태 갱신은 그대로 커밋) | integration | High | GenAiCallbackService.java:155-163; AugmentApplyResult.java:25-31 |
| TC-AUG-074 | **비종결 job 만료 스윕 회수 (신규)** | job 이 무갱신 경과 임계(기본 360분, 하한 5분) 초과 | 스윕 tick | 조건부 UPDATE `claimExpired` 로 1행 클레임 → `FAILED(ERR_CD=EXPIRED)` → 전 job 종결 시 롤업(실패 확정). 클레임 0행이면 no-op(2노드 중복 회수 방지). 메트릭 `augment.job.expired` 는 **커밋 이후**에만 증가 | integration | High | AugmentJobExpirySweeper.java:206-232; AugmentJobExpiryTxService.java:62-88 |
| TC-AUG-075 | **job 0건 고아 PENDING 증강 회수 (신규)** | 위탁 전 롤업이 예외로 끝나 job·콜백 0건, PENDING 장기 잔존 | 스윕 tick | `expireOrphanPending` → REJECTED + dead-letter. ★신고 구간(`'F'`)도 **회수 대상**(구 제외 술어 제거 — 깨울 주체가 없어 PENDING 영구 고착) | integration | High | AugmentJobExpirySweeper.java:174-198; AugmentJobExpiryTxService.java:105-122 |
| TC-AUG-076 | **필수 필드 누락 400 (신규)** | `request_id`/`job_id`/`status` blank | 콜백 | 400 `@NotBlank`. `results` 100건 초과도 400(계약 상한) | unit | Med | GenAiCallbackRequest.java:44-83 |
| TC-AUG-077 | **미지 필드 무시 (신규)** | 외부가 신규 필드 추가 | 콜백 | `@JsonIgnoreProperties(ignoreUnknown=true)` 로 수신 성공(계약 확장 내성) | unit | Low | GenAiCallbackRequest.java:41,95 |
| TC-AUG-078 | **강등된 PENDING 의 성공 콜백 폐기 관측 (신규)** | aug 가 REJECTED(V143 정리·위탁 0건 롤업·반려)인데 성공 결과 도착 | 롤업 | 200 `applied:false` + **WARN** `success result discarded — aug already terminal(REJECTED)`. "요청했는데 파생영상이 없다"의 유일 단서 | unit | Med | AugmentResultService.java:188-196 |

## E-3B. 증강 외부 위탁 (AugmentJobSubmitService / HttpExternalAugmentClient / AugmentJobRecorder)

> Phase 7-A1 신설 영역. `LS_DATA_AUG_JOB`(V140) · `LS_DATA_AUG_JOB_FILE`(V141).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-AUG-100 | **100장 청크 분할 위탁 (신규)** | 비식별 프레임 250장 | AFTER_COMMIT 위탁 | 3 청크로 분할, 청크별 `request_id = {augIdmpKey}-{jobSeq}`, `LS_DATA_AUG_JOB` 3행 + `LS_DATA_AUG_JOB_FILE` 250행. 상한은 계약 100 을 넘길 수 없다(설정으로 낮추기만 가능) | integration | High | AugmentJobSubmitService.java:112,143-148,385-399 |
| TC-AUG-101 | **job_id 는 외부가 발급 (신규)** | 정상 위탁 | `POST /api/genai/jobs` 202 | 응답 검증 3축 통과 시에만 `markAccepted(외부 job_id)` — `request_id` echo 일치 + `status=RECEIVED` + `job_id` non-blank. 하나라도 어긋나면 `EXTERNAL_API_ERROR` | integration | High | HttpExternalAugmentClient.java:90-164 |
| TC-AUG-102 | **Idempotency-Key 헤더 (신규)** | 재시도 | 위탁 | `Idempotency-Key: {request_id}` 전송 — 동일 키 재요청 시 외부가 기존 job 반환(중복 job 미생성) | unit | Med | HttpExternalAugmentClient.java:57-58,99 |
| TC-AUG-103 | **4xx = 비재시도 (신규)** | 외부 400/404 | 위탁 | `NonRetryableExternalException` 으로 Retry/CircuitBreaker 에서 제외, 본문 소비·해제(누수 방지), 응답 본문 원문 미노출(CWE-209). 연결 실패·타임아웃·5xx 만 재시도 | integration | High | HttpExternalAugmentClient.java:102-103,134-140 |
| TC-AUG-104 | **전량 선기록 후 위탁 (신규)** | 2번째 청크 선기록 실패 | 위탁 | **한 건도 위탁하지 않고** 이미 선기록된 앞 청크까지 `FAILED(ERR_CD=ISSUE_RECORD_FAILED)` 로 종결 → 롤업이 부분 프레임셋을 전량으로 오인해 성공 확정하는 경로 제거 | integration | High | AugmentJobSubmitService.java:198-260 |
| TC-AUG-105 | **비식별 경로 부재 = 위탁 거부 (신규)** | 프레임 중 `DE_IDNTF_SRC_FILE_PATH_NM` blank 존재 | 위탁 | 한 건도 위탁하지 않고 `LS_DATA_AUG_JOB`(FAILED/`DEID_PATH_MISSING`) 기록. **원본 경로 폴백 금지** | security | High | AugmentJobSubmitService.java:182-193,352-375 |
| TC-AUG-106 | **위탁 전 신고 = 거부(보류 아님) (신규)** | 대상 영상 `'F'` | 위탁 진입 | 프레임 경로 조회조차 하기 전 차단 → `LS_DATA_AUG_JOB`(FAILED/`DEID_REPORT_OPEN`) + `SubmitOutcome.of(0)` → 브릿지가 즉시 실패 롤업(REJECTED). 구 `WITHHELD_*` 정책 보류·재개 리스너 **폐기** | security | High | AugmentJobSubmitService.java:170-180; AugmentRequestBridge.java:116-118 |
| TC-AUG-107 | **위탁 도중 신고 관측 = 남은 청크 중단 (신규)** | 2/3 청크 전송 후 신고 커밋 | 청크 루프 | 청크마다 무잠금 재판정 → 남은 job 을 `FAILED(ERR_CD=DEIDENT_REPORT)` 로 종결(비종결 방치 시 롤업 영구 보류). 이미 나간 청크는 되돌릴 수 없으므로 증강 1건은 실패로 마감 | security | High | AugmentJobSubmitService.java:207-219,262-281 |
| TC-AUG-108 | **청크 위탁 실패 건별 격리 (신규)** | 2번째 청크 HTTP 실패 | 위탁 | 해당 job `FAILED(SUBMIT_FAILED)` + 사유 기록, 3번째 청크는 계속 위탁. 집계 판정은 수신부 롤업 책임 | integration | Med | AugmentJobSubmitService.java:324-344 |
| TC-AUG-109 | **위탁 0건 = 즉시 실패 롤업 (신규)** | 전 청크 위탁 실패 | 브릿지 | `requiresFailureRollup()` → `handleInNewTransaction(failed)` 로 REJECTED + dead-letter. 단 **미종결 job 이 하나라도 있으면 롤업하지 않는다**(동시 재요청 보호) | integration | High | AugmentRequestBridge.java:116-118,132-147; AugmentJobSubmitService.java:304-314 |
| TC-AUG-110 | **★커넥션 풀 데드락 회귀 가드 (신규)** | 위탁 동시 2건 | `submit()` 호출 스택 | 외부 HTTP 왕복 시점에 `TransactionSynchronizationManager.isSynchronizationActive()`=false **그리고** `getResource(controlEmf)`=null. `@Transactional(readOnly)` 도 `NOT_SUPPORTED` 도 재부착 금지(둘 다 커넥션 고갈 실측). ⚠ `isActualTransactionActive()` 는 관측축으로 부적합 | integration | High | AugmentJobSubmitService.java:53-106,160-166 |
| TC-AUG-111 | **evnt_type 폴백 (신규)** | 영상 `EVNT_TYPE_CD` null/blank | 위탁 바디 | `"ETC"` 로 채움(계약 필수 필드) | unit | Low | AugmentJobSubmitService.java:115,377-383 |
| TC-AUG-112 | **위탁 로그에 절대경로 미출력 (신규)** | 위탁/거부 | 로그 | 개수·식별자 수준만 기록, 파일 절대경로 미노출 + CR/LF sanitize(CWE-117/209) | security | Med | HttpExternalAugmentClient.java:45,166-169; AugmentJobSubmitService.java:401-405 |
| TC-AUG-113 | **`mode=noop` 시 위탁 미수행 (신규)** | `authoring.augment.external.mode=noop` | 요청 | `NoopExternalAugmentClient` 활성(HTTP 0건). 만료 스윕은 자기 토글만 보므로 후보 0건으로 무해하게 동작 | unit | Med | HttpExternalAugmentClient.java:50-51; NoopExternalAugmentClient.java; AugmentJobExpirySweeper.java:47-52 |
| TC-AUG-114 | **외부 산출 프레임 실반입 3중 검증 (신규)** | Phase B | 반입 | ①읽기 허용 루트(lexical + 실경로, 심링크 CWE-59) ②정규 파일 + size>0 ③**부모 비식별 프레임과 해상도 동일**. 하나라도 어긋나면 반입 거부 → all-or-nothing 실패 | security | High | AugmentFrameProducer.java:131-199 |
| TC-AUG-115 | **부분 쓰기 방지 (.part → atomic move) (신규)** | 복사 중 중단 | 반입 | 목적 경로에 반쯤 쓰인 파일 미잔존. 소스는 `NOFOLLOW_LINKS` 로 열어 검증~복사 사이 심링크 교체 시 실패(fail-closed, CWE-367) | security | High | AugmentFrameProducer.java:224-238 |
| TC-AUG-116 | **파생 비디오 = 부모 비식별본 실제 복사 (신규)** | Phase B | 반입 | `{deidBase}/videos/augment/{parentRawSn}/{newRawSn}/{augType}.mp4` 로 복사. 소스 경로는 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` **값을 읽어** 정한다(mock=`deidentified.mp4` / KPST=`{stem}-mask{ext}` — 조합·추측 금지). 소스 부재 시 NOT_FOUND(원본 폴백 없음) | integration | High | AugmentFrameProducer.java:107-120; AugmentExtractSnapshot.java:163-165,246-254 |
| TC-AUG-117 | **파생 프레임은 비식별 컬럼에만 적재 (신규)** | Phase C | 영속 | `SRC_FILE_PATH_NM`=**null**, `DE_IDNTF_SRC_FILE_PATH_NM`=산출 경로. 두 컬럼 동일값 금지(마트 뷰 "두 경로 상이" 불변식 + export ORIGINAL 벌 anonymity 오표기 차단) | integration | High | AugmentExtractPersist.java:101-115 |
| TC-AUG-118 | **증강 라벨 복사는 좌표 그대로 (신규)** | 해상도 동일 전제 | Phase C | `LsDataLbl.copyForNewSrc` + `LS_DATA_AUG_LBL_MAP(COORD_RECALC_YN='N')`. 해상도 불일치는 TC-AUG-114 가 사전 차단 | integration | High | AugmentExtractPersist.java:117-135 |
| TC-AUG-119 | **async 추출 실패 = RAW FAILED + aug dead-letter (신규)** | Phase A~C 예외 | 러너 | ①Phase B 산출 cleanup(+잔존 시 ERROR·`augment.cleanup.failed`) ②`markRawDataFailed` ③`markAugProcessingFailed`(REQUIRES_NEW, `AUG_PROC_STTS_CD` **미변경** — 멱등 앵커 무충돌). ③ 없으면 파생 RAW 는 FAILED 인데 집계는 성공으로 보인다 | integration | High | AsyncAugmentFrameRunner.java:133-159; AugmentExtractPersist.java:186-203 |
| TC-AUG-120 | **ffprobe 는 확정 성공 이후에만 (신규)** | Phase C PERSISTED | 러너 | `AsyncVideoMetaRunner` 를 확정 블록 **밖**에서 별도 catch 로 기동 — 사본 확정 전 probe 레이스 제거, 메타 실패가 확정 결과를 되돌리지 않음. ⚠ `video.*` 가 부모와 동일한 것이 정상(재인코딩 없음) | integration | High | AsyncAugmentFrameRunner.java:106-121 |
| TC-AUG-121 | **Phase C SKIPPED = cleanup 금지 (신규)** | 중복 트리거 패자 | 러너 | 파일이 승자와 동일 경로라 cleanup·FAILED 전이 모두 skip | integration | High | AsyncAugmentFrameRunner.java:92-97 |

## E-4. 해상도 파생 오케스트레이션 (VideoResolutionService / VideoController)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-RESL-001 | 프리셋 미지정 3종 전체 생성 | APPROVED 원본, 원본≠모든 프리셋 | REVIEWER, body 생략 | 201, `derivatives` 3건 CREATED, 각 새 RAW_SN | integration | High | VideoResolutionService.java:93-142 |
| TC-RESL-002 | 프리셋 부분 지정 | `presets:[RESL_720P,RESL_480P]` | REVIEWER | 201, 2건만 | integration | High | ResolutionChangeRequest.java:32-37 |
| TC-RESL-003 | presets 중복 제거 | `[720P,720P]` | 요청 | distinct 1건 | unit | Low | ResolutionChangeRequest.java:33-37 |
| TC-RESL-004 | **원본 동일 해상도 프리셋 스킵** | 원본=1920×1080 | 3종 요청 | RESL_1080P skip(결과 목록 제외), 720/480만 | integration | High | VideoResolutionService.java:117-124 |
| TC-RESL-005 | **전부 스킵 시 400** | 원본이 모든 프리셋과 동일 | 요청 | 400("원본과 동일하지 않은 적용 가능한 해상도 프리셋이 없습니다.") | integration | High | VideoResolutionService.java:127-130 |
| TC-RESL-006 | **전부 실패 시 500** | 모든 `createOne` 예외 | 요청 | 500("요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."), 내부 사유 미노출 | integration | High | VideoResolutionService.java:134-140 |
| TC-RESL-007 | **부분 실패 격리 201** | 1프리셋 실패 | 요청 | 201, FAILED(rawSn null)+CREATED 혼재 | integration | High | VideoResolutionService.java:199-209 |
| TC-RESL-008 | **업스케일 허용** | 원본 480p, 목표 1080p | 요청 | 거부 없이 생성(구 `targetH>=srcH` 가드 제거) | integration | High | VideoResolutionService.java:107-124 |
| TC-RESL-009 | 증강본/파생본 거부 | `ORGNL_RAW_SN≠null` | 요청 | 400("원본 영상에만 해상도 변경 가능") | unit | High | VideoResolutionService.java:224-226 |
| TC-RESL-010 | 미검수 영상 거부 | APPROVED 아님 | 요청 | 409("검수 완료(APPROVED)된 영상만 해상도 변경할 수 있습니다.") | unit | High | VideoResolutionService.java:229-234 |
| TC-RESL-011 | 영상 미존재 | 미존재 rawSn | 요청 | 404 | unit | Med | VideoResolutionService.java:219-221 |
| TC-RESL-012 | 프레임 해상도 확인 불가 | 첫 프레임 dim≤0 | 요청 | 400("원본 프레임 해상도를 확인할 수 없습니다.") | unit | Med | VideoResolutionService.java:111-113 |
| TC-RESL-013 | 프리셋 enum 화이트리스트 | `["RESL_240P"]` | 요청 바디 | 400 Jackson 거부 | security | High | ResolutionPreset.java (enum 상수 3종) |
| TC-RESL-014 | WORKER/미인증 차단 | WORKER·null | 요청 | 403/401 | security | High | VideoController.java:322-325 |
| TC-RESL-015 | 응답 형태 계약 | 성공 | 201 | `{derivatives:[{rawSn,goalResCd,targetW,targetH,status}]}`, 내부경로 미노출 | unit | Med | ResolutionChangeResponse.java |
| TC-RESL-016 | measureFirstFrame 경로 CWE-22 | base 밖 경로 | 요청 | 400("원본 프레임 경로가 허용된 저장 경로를 벗어납니다.") | security | High | VideoResolutionService.java:256-268 |
| TC-RESL-017 | deid 프레임 경로 base 허용 | 비식별 절대경로 | 요청 | raw base ∪ deid base 두 축 허용 통과 | unit | Med | VideoResolutionService.java:260-265 |
| TC-RESL-018 | **★부모 비식별 산출물 실재 동기 확인 (신규)** | `DE_IDNTF_YN='F'` 인데 산출물 부재(비식별 API 실패형) | 요청 | **409 CONFLICT**("원본의 비식별 산출물을 찾을 수 없어 해상도 파생을 만들 수 없습니다.") — 예약 이전 동기 거부. 통과시키면 201 후 async 확정이 반드시 실패하고 cleanup 이 흔적을 지워 "성공으로 보이는 소멸"이 된다 | security | High | ParentDeidArtifactGuard.java:65-94; VideoResolutionService.java:101 |
| TC-RESL-019 | **★신고('F') + 산출물 실재 = 생성 허용 (신규)** | `DE_IDNTF_YN='F'`(신고형, 비식별본 존재) | 요청 | 201 정상 생성. 해상도 파생은 외부 위탁이 전혀 없는 내부 ffmpeg 리스케일이라 신고 구간 생성이 외부 유출을 만들지 않는다(2026-07-29 구속 정책) | security | High | ParentDeidArtifactGuard.java:70-93; LsDataRaw.hasDeidentArtifact() |
| TC-RESL-020 | **부모 procLog 경로가 허용 저장경로 밖 (신규)** | `DE_IDNTF_FILE_PATH_NM` 이 2-way 검증 실패 | 요청 | 400 INVALID_INPUT("경로가 허용된 비식별 저장 경로를 벗어납니다."), 경로 원문 미노출 | security | High | ParentDeidArtifactGuard.java:100-124 |
| TC-RESL-021 | **파생 확정 상태 조회 API (신규)** | 파생 3건(확정/진행/실패 혼재) | `GET /v1/videos/{rawSn}/resolution` | 200 `{derivatives:[...]}` — `'Y'`+COMPLETED→COMPLETED / `DATA_STTS_CD=FAILED`→FAILED / 그 외→IN_PROGRESS. 비-해상도 파생(외부 증강)은 제외. E-ISSUE-24(비동기 확정 실패 미가시) 해소 | integration | High | VideoResolutionService.java:154-194; VideoController.java:349-353 |

## E-5. 해상도 파생 예약/확정 (Reservation·Snapshot·Materialize·Persist·Runner)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-RESL-030 | 예약행 PENDING 커밋 + 새 RAW | 정상 | `reserveAndCreate` | `LS_DATA_AUG(RESL_*, PENDING)` flush + `createFromResolution`, AFTER_COMMIT 러너 트리거 | integration | High | ResolutionReservationPersister.java:68-123 |
| TC-RESL-031 | **부모 잠금하 산출물 게이트 — 'N'만 차단** | 부모 `DE_IDNTF_YN='N'`/null | 예약 | 409("비식별 산출물이 있는 원본 영상만 해상도 파생영상을 만들 수 있습니다."), 파생 미생성. ★`'F'`(신고)는 **통과**(2026-07-29 확정) | security | High | ResolutionReservationPersister.java:76-88 |
| TC-RESL-032 | 대표프레임 SRC_SN null fail-fast | `firstFrameSrcSn=null` | 예약 | 400("대표 프레임을 확인할 수 없습니다.") — INSERT 이전 거부 | unit | Med | ResolutionReservationPersister.java:92-94 |
| TC-RESL-033 | **부분유니크 중복 예약 차단** | 동일 (SRC_SN, RESL_*) 재/동시요청 | 예약 flush | 409("동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.") — `UK_LS_DATA_AUG_RESL`(V125, **상태 무관**) | integration | High | ResolutionReservationPersister.java:101-109; V125 |
| TC-RESL-034 | 출력 경로 CWE-22 + 비식별 서브트리 강제 | traversal 상대경로 | 예약 | 400("출력 경로가 허용된 비식별 저장 경로를 벗어납니다.") — base 하위 + `StorageSubtreePolicy.isDeidentifiedArtifact` 이중 강제(두 base 동일 설정에서도 원본 서브트리 유출 차단) | security | Med | ResolutionReservationPersister.java:157-163 |
| TC-RESL-035 | Phase A 멱등 skip | 파생 RAW 이미 `'Y'` | snapshot | `Optional.empty` → 러너 skip | unit | High | ResolutionSnapshotService.java:102-106 |
| TC-RESL-036 | **Phase A 부모 산출물 재검증 — 'N'만 abort** | 예약~확정 창 부모 `'N'` | snapshot | CONFLICT → 러너 FAILED. ★`'F'` 는 **통과**(예약 게이트와 동일 정책, 구 "부모 'F' → CONFLICT" 폐기) | security | High | ResolutionSnapshotService.java:108-122 |
| TC-RESL-037 | Phase A 비식별 비디오 경로 부재 | SUCCESS procLog 없음 | snapshot | 404("원본 비식별 영상 경로를 찾을 수 없습니다") | unit | Med | ResolutionSnapshotService.java:149-153 |
| TC-RESL-038 | Phase A 프레임 0건 fail-fast | 부모 프레임 없음 | snapshot | INTERNAL_ERROR("파생할 프레임이 없습니다") | unit | Med | ResolutionSnapshotService.java:161-164 |
| TC-RESL-039 | Phase A 중복 videoFrameNo fail-fast | 부모 중복 프레임키 | snapshot | INTERNAL_ERROR(라벨 이중매핑 차단) | unit | Med | ResolutionSnapshotService.java:194-199 |
| TC-RESL-040 | **Phase A deid 프레임 경로 strict** | `deidFilePath` blank/null | snapshot | CONFLICT("비식별 프레임 경로가 없어 파생영상을 생성할 수 없습니다") — 원본 픽셀 복제 + `'Y'` 위장 차단, **원본 폴백 없음** | security | High | ResolutionSnapshotService.java:240-247 |
| TC-RESL-041 | Phase B 비디오 복사 + 프레임 리스케일 | 스냅샷 확정 | materialize | 비식별 비디오 복사 → `videoDst`, 전 프레임 `targetW×targetH` 리스케일. DB 접근 0 · 부모 잠금 0 | integration | High | ResolutionFileMaterializer.java:62-80 |
| TC-RESL-042 | Phase B 원본 비식별 파일 부재 | `deidVideoSrc` 미존재 | materialize | 404("원본 비식별 영상 파일을 찾을 수 없습니다.") — 원본 폴백 없음 | unit | Med | ResolutionFileMaterializer.java:66-68 |
| TC-RESL-043 | Phase B 리사이즈 게이트(DoS) | 동시 다수 | materialize | `ResizeConcurrencyGate` 세마포어가 **복사+리사이즈 전체**를 한 슬롯으로 통제 | integration | Med | ResolutionFileMaterializer.java:63,77-79 |
| TC-RESL-044 | Phase C 확정 영속 | Phase B 산출 | persist | PERSISTED — 프레임 INSERT + `copyScaledLabels` + `markResolutionGenerated`(PENDING→ACCEPTED) + `markDeidentified('Y')` + `markCompleted` + SUCCESS procLog + `DerivedMetaCopier`(순서 고정: 확정 블록 **이후**) | integration | High | ResolutionPersistService.java:85-154 |
| TC-RESL-045 | **★레터박스 종횡비 보존 좌표 변환** | 부모 라벨(BBOX/POLYGON/세그/키포인트), 비-16:9 원본 | `copyScaledLabels` | `LetterboxTransform` 균일 배율(`scaleX==scaleY==box.scale`) + 중앙 오프셋 → `x' = x*scale + offsetX`. 구 축별 독립 배율(강제 왜곡, E-ISSUE-26) **폐기**. 매핑행은 기존 컬럼(`COORD_RECALC_YN='Y'`, `SCALE_X/SCALE_Y`)만 기록(오프셋 컬럼 미신설 — 추적성 한계 명시) | integration | High | ResolutionSnapshotService.java:136-143; ResolutionPersistService.java:384-409 |
| TC-RESL-046 | 부모 라벨 0건 | 없음 | `copyScaledLabels` | 0 반환 | unit | Low | ResolutionPersistService.java:386-389 |
| TC-RESL-047 | ~~Phase C stale PII 게이트 — 재신고~~ | capturedAt 이후 부모 신고 | persist | **[폐기 2026-07-30]** ★판정축 교체 — "capturedAt 이후 신고 이력 존재" 조건이 **제거**됐다(순수 신고 결합, 파생 생성은 원본 신고와 무관). 남은 두 조건은 신고가 아니라 **복사 원자성**을 방어한다 → TC-RESL-048 / TC-RESL-049 | security | — | ResolutionPersistService.java:306-360 (주석) |
| TC-RESL-048 | **Phase C stale — 비식별 경로 변경 abort** | 최신 SUCCESS procLog 경로 ≠ 스냅샷 경로 | persist | CONFLICT("스냅샷 이후 원본 비식별본이 변경되어…") → 러너가 cleanup + FAILED. 경로 해석 불가도 불일치로 취급(abort) | security | High | ResolutionPersistService.java:328-342,362-369 |
| TC-RESL-049 | **Phase C stale — 파일 mtime 교체 abort** | 같은 경로, `mtime > capturedAt` | persist | CONFLICT(제자리 교체 방어). **파일 존재 시에만** 판정하고 `stat` 실패는 보수적 통과(로그만) | security | Med | ResolutionPersistService.java:344-359 |
| TC-RESL-050 | **Phase C 부모 재잠금 최종 게이트 — 'N'만 abort** | 부모 `'N'`/null | persist | CONFLICT abort. ★`'F'` 통과. 잠금 순서는 항상 parent → newRaw(교착 방지) | security | High | ResolutionPersistService.java:90-103 |
| TC-RESL-051 | **중복 finalize CAS skip** | 파생 newRaw 이미 `'Y'` | persist | `Result.SKIPPED`(프레임 재삽입 없음) — newRaw 재잠금으로 두 finalize 직렬화 | integration | High | ResolutionPersistService.java:111-119 |
| TC-RESL-052 | 프레임 개인정보 3필드 복사 | 부모 anony/psdo/prvc | `insertFrames` | 파생에 복사, 부모 null 이면 null. **`SRC_FILE_PATH_NM`=null**(파생엔 원본 픽셀이 실재하지 않음, 정책 A) | unit | Med | ResolutionPersistService.java:275-292 |
| TC-RESL-053 | 예약 aug 슬롯 해제 | finalize 실패 | `releaseReservedAug` | `RESL_` 접두 + 라벨맵 미참조일 때만 삭제(예약행을 남기면 `UK_LS_DATA_AUG_RESL` 이 상태 무관이라 재요청 영구 락아웃) | integration | High | ResolutionPersistService.java:200-222 |
| TC-RESL-054 | 슬롯 해제 방어 — 라벨맵 참조 시 미삭제 | `LS_DATA_AUG_LBL_MAP` 참조 | `releaseReservedAug` | 삭제 skip(승자 보호) + WARN | unit | Med | ResolutionPersistService.java:205-210 |
| TC-RESL-055 | 슬롯 해제 방어 — 비-RESL 미삭제 | 오배송 non-RESL | `releaseReservedAug` | 삭제 skip + WARN | unit | Med | ResolutionPersistService.java:211-221 |
| TC-RESL-056 | isAlreadyFinalized FOR UPDATE 판정 | newRaw `'Y'` 또는 COMPLETED | 조회 | true(cleanup·FAILED 스킵 근거). `findByRawSnForUpdate` 로 승자 Phase C 커밋과 부분 직렬화 — 잔여 창(승자-뒤짐·락 타임아웃)은 미폐쇄(정직한 한계 명시) | unit | Med | ResolutionPersistService.java:178-187 |
| TC-RESL-057 | 러너 A→B→C 정상 완주 | 전 단계 성공 | `finalizeDerivative` | 로그 완료, 예외 없음 | integration | High | AsyncResolutionRunner.java:56-86 |
| TC-RESL-058 | 러너 snapshot empty skip | Phase A empty | 러너 | skip, 후속 미실행 | unit | Med | AsyncResolutionRunner.java:62-66 |
| TC-RESL-059 | 러너 persist SKIPPED — 파일 미정리 | Phase C SKIPPED | 러너 | cleanup 미실행(승자 산출물 보호) | integration | High | AsyncResolutionRunner.java:73-79 |
| TC-RESL-060 | **러너 실패 정리 — 승자 보호 선점검** | 예외 + 승자 확정 | `handleFailure` | `isAlreadyFinalized` 를 **cleanup 보다 먼저** 확인 → cleanup·FAILED 전이 모두 skip | integration | High | AsyncResolutionRunner.java:98-113 |
| TC-RESL-061 | 러너 실패 정리 — cleanup+슬롯해제+FAILED | 예외 + 미확정 | `handleFailure` | WARN + `resolution.finalize.failed` 메트릭 → 아티팩트 cleanup → `releaseReservedAug` → `markRawDataFailed` | integration | High | AsyncResolutionRunner.java:115-150 |
| TC-RESL-062 | cleanup 원본 미삭제 보장 | `videoDst`=파생경로만 | cleanup | `frames/deid/{newRawSn}/` + 파생 비디오 파일만 삭제. 원본 프레임(`frames/raw/**`)·원본 영상 미삭제. 경로 키에 파생 RAW_SN 포함이라 타 파생과 미공유 | security | High | ResolutionFileMaterializer.java:98-154 |
| TC-RESL-063 | @Async 예외 삼킴 | 러너 예외 | `runAsync` | 예외 전파 없음 | unit | Med | AsyncResolutionRunner.java:47-50 |
| TC-RESL-064 | createResolutionPending RESL_ 접두 강제 | `augTypeCd` 비-RESL | 엔티티 | 400 | unit | Med | LsDataAug.java:214,228 |
| TC-RESL-065 | markResolutionGenerated 이중전이 차단 | 이미 ACCEPTED | 전이 | 409 | unit | Med | LsDataAug.java:248-249 |
| TC-RESL-066 | **확정 실패 파생 RAW 고아 정리 (신규)** | FAILED 파생 RAW, 프레임 0건, cleanup 성공 | `deleteFailedDerivativeRaw` | 잠금 + 4조건 재확인(파생임 · `'Y'` 아님 · FAILED · 프레임 0건) 통과 시에만 DELETE, DELETE 문에도 동일 조건 동봉(검사~삭제 창 0건 삭제). E-ISSUE-23(고아 무한 누적) 해소 | integration | High | ResolutionPersistService.java:237-259 |
| TC-RESL-067 | **파일 잔존 시 RAW 행 보존 (신규)** | cleanup 후에도 파일 잔존 | `handleFailure` | `deleteFailedDerivativeRaw` **미호출** — 유일한 DB 포인터를 지우면 추적 불가 고아 파일이 되므로 FAILED 상태로 보존 + WARN | integration | High | AsyncResolutionRunner.java:126-141,155-158 |
| TC-RESL-068 | **레거시 raw base 파생 디렉터리 정리 (신규)** | 구 스킴 `{rawBase}/resolution/{newRawSn}/` 잔존 | cleanup | 함께 재귀 삭제. `legacyRoot` 하위 + 루트 자기자신 아님 두 조건 통과 시에만. raw base 미설정은 "정리 대상 없음"이라 잔존 판정에 미반영 | unit | Med | ResolutionFileMaterializer.java:119-138 |
| TC-RESL-069 | **파생 비디오 경로 키에 파생 RAW_SN 포함 (신규)** | 같은 (부모, 프리셋) 재시도 | 경로 산정 | `StorageSubtreePolicy.resolutionVideoFile(parent, newRawSn, preset)` — 구 `(부모,프리셋)` 키에서 cleanup 이 타 파생 참조 파일을 지우던 실패 클래스 구조적 제거. INSERT 시 잠정 `.pending/` 경로 → 확정 RAW_SN 으로 즉시 교체(잠정값 커밋 경로 없음) | integration | High | ResolutionReservationPersister.java:111-119,126-134 |
| TC-RESL-070 | **파생 메타 복사 + 미검수 검수행 (신규)** | 부모 메타 N건(일부 검수행 보유) | `DerivedMetaCopier` | 메타 값 **전체 복사**(`video.*` 포함, 배치 upsert 1회) + 부모에 검수행이 있던 메타키만 파생에 **PENDING** 검수행 생성(APPROVED 승계 안 함). `(metaSn, metaTypeCd)` 선재 skip 으로 UNIQUE 위반 원천 차단 | integration | High | DerivedMetaCopier.java:72-158 |
| TC-RESL-071 | **메타 복사 호출 순서 계약 (신규)** | Phase C | persist | `copyMetaAndReviews` 는 확정 블록(`markResolutionGenerated`/`markDeidentified`/`markCompleted`/procLog) **이후**에 호출. 앞에 두면 배치 upsert 의 `clear()` 로 dirty 변경이 유실돼 영구 미확정 고착 | integration | High | ResolutionPersistService.java:145-148; DerivedMetaCopier.java:43-49 |

## E-5B. 해상도 파생 산출물 비식별 저장소 이관 백필 (ResolutionBackfillService / TxService / SweepJob)

> E-ISSUE-21/22/41 후속 **데이터 정정 운영 배치**. Flyway 는 파일을 옮길 수 없어 스키마·뷰(V133) 변경과 분리했다. 이미 raw 저장소에 만들어진 파생 비디오·프레임을 비식별 서브트리로 옮기고 DB 경로를 정정한다. **PII 저장소 이관 + 원본 삭제**를 수반하므로 순서·실패 안전성·경로 가드가 핵심 축이다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-RESL-080 | **★순서 계약 — 원천검증 → Copy → Verify → DB 커밋 → 유예 등록 (삭제 아님) (신규)** | 이관 대상 파생 1건 | `migrateOne` | 순서가 정확히 ①`validateMigrationSource` ②`Files.copy`(move 아님 — 원본 보존) ③크기 일치 verify ④`txService.commitRelocation`(REQUIRES_NEW) ⑤`markStale`(대기열 등록) ⑥레거시 등록 ⑦`streamMetaCacheEvictor.evict`. **`migrateOne` 은 어떤 파일도 삭제하지 않는다** — 삭제는 유예 경과 후 스윕만 수행. 역전 시 깨지는 것: 삭제 선행이면 중간 실패에서 복구 불가, 커밋 선행-복사 후행이면 DB 가 없는 파일을 가리켜 스트리밍/export 전면 404 | integration | High | ResolutionBackfillService.java:330-404; ResolutionBackfillTxService.java:44-67 |
| TC-RESL-081 | **Verify 실패 = DB 미커밋 + 원본 유실 0 (신규)** | 복사본 크기 불일치 또는 목적지 부재 | `copyAndVerify` | `INTERNAL_ERROR("이관 파일 검증에 실패했습니다.")` → `migrateOne` 이탈 → **커밋·유예 등록 미실행**, 구 파일 그대로. DB 경로는 raw 그대로 유지(NULL 로 만들지 않는다 — 정상 서빙 중인 파생이 즉시 404 되는 것 방지). 해당 rawSn 만 `failures[{rawSn, reasonCode}]` 로 격리 반환되고 나머지 대상은 계속 처리 | integration | High | ResolutionBackfillService.java:453-471,198-209 |
| TC-RESL-082 | **DB 커밋 실패 = 삭제 0 + 재실행 멱등 (신규)** | `commitRelocation` 예외 | `migrateOne` | 예외가 `run()` 의 건별 catch 로 격리 → `markStale` 미도달(**유예 대기열 등록 0 = 구 파일 삭제 후보에 오르지 않음**). 재실행 시 `copyAndVerify` 가 "원본 존재" 경로로 재복사(REPLACE_EXISTING)해 정상 진행 | integration | High | ResolutionBackfillService.java:388-390,204-209 |
| TC-RESL-083 | **삭제 실패 = 마커 보존 + 다음 스윕 재시도 (신규)** | `Files.delete(target)` IOException | `sweepOne` | `false` 반환(삭제 실패) + **마커를 지우지 않는다** → 다음 스윕이 재시도. WARN 로그(경로 원문 미노출) | integration | High | ResolutionBackfillService.java:668-677 |
| TC-RESL-084 | **멱등 재실행 — 이미 이관 완료면 skip, 단 레거시 등록은 매 실행 재시도 (신규)** | 이미 목표 위치를 가리킴 | `migrateOne` | 프레임은 `src.equals(dst)` 로 **개별 판정** skip. `frameUpdates` 비고 `newVideoPath` null 이면 `skippedRawSns` 에 담기되 **`markLegacyArtifactsStale` 는 그때도 수행**(M-3 — 등록 실패를 삼킨 뒤 다음 실행이 skip 으로 빠지면 구 파일이 영구 잔존) | integration | High | ResolutionBackfillService.java:350-353,380-386 |
| TC-RESL-085 | **★원천(복사소스=삭제대상) 경로 가드 4종 (신규)** | 오염/레거시 DB 경로 | `validateMigrationSource` | ①두 base(raw/deid) 밖 → 400("원천 경로가 허용된 저장소를 벗어납니다.") ②`frames/raw/**`(원본 프레임 서브트리) → **409 거부**(부모 원본을 비식별 벌로 복제한 뒤 원본을 삭제하는 최악 시나리오 원천 차단) ③존재하는데 정규 파일 아님(심링크·디렉토리, `NOFOLLOW_LINKS`) → 409 ④해석 불가 → 400. **원본 폴백 없음** | security | High | ResolutionBackfillService.java:418-444 |
| TC-RESL-086 | **목적 경로 가드 — 비식별 서브트리 강제 (신규)** | 상대경로 traversal | `safeDeidPath` | base 하위 + `StorageSubtreePolicy.isDeidentifiedArtifact` 동시 충족 아니면 400("이관 목적 경로가 비식별 저장 경로를 벗어납니다.") — 두 base 가 동일 경로인 운영 형상에서도 원본 서브트리로 새지 않는다(CWE-22/359) | security | High | ResolutionBackfillService.java:474-480 |
| TC-RESL-087 | **★캐시 무효화 이중 (신규)** | 백필 전 이미 스트리밍된 rawSn | 이관 | ①`commitRelocation` 이 `evictAfterCommit(rawSn)` — **커밋 후** 발화(커밋 전 evict 는 동시 요청이 옛 경로를 재캐싱), 롤백 시 미실행 ②`migrateOne` 말미에 `evict(rawSn)` 한 번 더(`@Cacheable` get→load→put 비원자로 커밋 전 읽은 요청이 옛 값을 재설치한 창 축소). 무효화 없으면 stream-meta TTL(5분) 동안 구 경로 해석 → `FileNotFoundException` 500 | integration | High | ResolutionBackfillTxService.java:54-63; ResolutionBackfillService.java:398-402; CacheConfig.java:63 |
| TC-RESL-088 | **★유예 < stream-meta TTL 이면 기동 거부 (신규)** | `authoring.resolution-backfill.stale-grace-minutes` < 5 | 부팅 | `@PostConstruct` 에서 `IllegalStateException` — **fail-closed**. 유예의 존재 이유가 "다른 노드의 stale 캐시가 TTL 동안 옛 경로를 해석해도 파일이 살아 있게" 하는 것이라, 짧은 유예는 보증을 조용히 무력화(500 재발). WARN 이 아니라 차단 | integration | High | ResolutionBackfillService.java:150-160 |
| TC-RESL-089 | **유예 마커 계약 — SHA-256 파일명·mtime 리셋 금지 (신규)** | 같은 파일 반복 등록 | `markStale` | 마커명 = 대상 절대경로의 SHA-256 hex + `.pending`(경로 1:1, 파일명 제약 회피). **이미 있으면 덮어쓰지 않는다**(유예 시작시각이 뒤로 밀려 영영 삭제되지 않는 것 방지). `CREATE_NEW` + `FileAlreadyExistsException` 무시로 동시 실행 안전. 등록 실패는 **구 파일 보존**으로 귀결(안전측) + 다음 실행 재시도 | unit | High | ResolutionBackfillService.java:535-557,744-757 |
| TC-RESL-090 | **★스윕 삭제 4조건 + 실패 시 보존 (신규)** | 마커 1건 | `sweepOne` | 모두 충족해야 삭제 — ①`marker mtime + grace ≤ now` ②`countReferencesToFilePath == 0`(RAW·procLog·프레임 경로 4컬럼 합산) ③삭제 가능 위치(두 base 하위 + `frames/raw/**` 아님, **마커 내용을 신뢰하지 않고 삭제 직전 재판정**) ④정규 파일(`NOFOLLOW_LINKS`). **mtime 조회 실패 → 미경과(보존)**, **참조 조회 예외 → 보존**. 저장소 밖·비정규 파일은 삭제하지 않고 마커만 버려 무한 재시도 방지 | security | High | ResolutionBackfillService.java:620-705; VideoRepository.java:countReferencesToFilePath |
| TC-RESL-091 | **스윕 2노드 안전성 + 조회 상한 (신규)** | 두 노드 동시 발화 | 스윕 | 안전성 근거가 **DB 조건부 UPDATE 클레임이 아니라 파일시스템 멱등**이다 — 마커 단위 조건부 처리 + `Files.deleteIfExists`(이미 없으면 조용히 통과) + `discardMarker` 멱등. 1회 처리 상한 `SWEEP_MAX_MARKERS=5000`(정렬 후 limit), 잔여는 다음 스윕이 이어받는다. ⚠ 최악의 경우 같은 파일에 대한 삭제 시도가 두 노드에서 겹칠 수 있으나 결과는 동일(파일 1회 삭제) | integration | High | ResolutionBackfillService.java:575-617,123 |
| TC-RESL-092 | **dry-run 계약 (신규)** | `dryRun=true` | `POST /v1/videos/resolution-backfill?dryRun=true` | 파일 복사·DB 변경·삭제 **전부 0**. 대상 집계·감사·뷰 제외 목록만 산출하고 `staleDeletedCount=0` + `stalePendingCount`=현재 마커 수(`countPendingOnly`). `migrated`/`skipped`/`failures` 는 비어 있다(루프가 `continue`) | integration | High | ResolutionBackfillService.java:195-197,212-213,596-603 |
| TC-RESL-093 | **API 인가 + 응답 정보노출 통제 (신규)** | WORKER·미인증 / 정상 | `POST /v1/videos/resolution-backfill` | WORKER 403 / 미인증 401 / REVIEWER 200. 응답 `ResolutionBackfillResponse` 에 **파일 경로 원문 없음** — 식별자(rawSn)·건수·추상 사유코드(예외 클래스명·`Verdict` 이름)만(CWE-209). ⚠ 이 엔드포인트는 `/v1/dev/**` 가 아니라 일반 `/v1/videos` 이며 `@Profile` 제한이 **없다**(운영 포함 노출) | security | High | VideoController.java:372-376; ResolutionBackfillResponse.java |
| TC-RESL-094 | **감사 부분 스캔 플래그 (신규)** | 비식별 경로 보유 프레임 20만 행 초과 | `auditDeidentifiedFramePaths` | `AUDIT_MAX_ROWS=200,000` 도달 시 1행 프로브로 잔여 확인 → `auditTruncated=true` + WARN. **`auditViolations=[]` 를 "영향 없음"의 근거로 쓸 수 없다**. 판정기는 서빙과 동일한 `StorageSubtreePolicy.verifyDeidentifiedFile`(SQL `POSITION` 근사 폐기), 커서 페이징 500행 | integration | High | ResolutionBackfillService.java:113,246-289 |
| TC-RESL-095 | **프리셋 미확정 파생 — 프레임만 이관 + 노출 (신규)** | 확정 실패로 `LS_DATA_AUG`(RESL_*, ACCEPTED) 예약행이 삭제된 파생 | `migrateOne` | 발견은 **파생 축**(`ORGNL_RAW_SN` + `VMS_CLIP_ID LIKE '%_RESL_%'`)으로 하므로 대상에 **포함**된다. 영상 파일 경로 규약을 만들 수 없어 **영상은 손대지 않고 프레임만 이관**하고, 해당 rawSn 을 `unresolvedPresetRawSns` + WARN 으로 드러낸다(침묵 skip 아님) | integration | High | ResolutionBackfillService.java:184-194,360-378; VideoRepository.java:findResolutionDerivativeTargets |

## E-6. 데이터셋 Export (DatasetExportService / Bridge / Runner / TxService / Recoverer)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-EXPORT-001 | 검수승인 AFTER_COMMIT 강제 재생성 | `ReviewApprovedEvent` | 브릿지 | `runner.runApprovalAsync(rawSn)` — force=true | integration | High | DatasetExportBridge.java:36-43 |
| TC-EXPORT-002 | 승인 경로 멱등 skip 미적용 | force=true, 무수정 재승인 | export | 매 승인 새 버전 폴더 + 산출물 전량 재생성 | integration | High | DatasetExportService.java:150-156 |
| TC-EXPORT-003 | 재동결 경로 멱등 skip | force=false, 동일 해시 | export | `IDEMPOTENT_SKIP`(행 미INSERT, metric+log만). PARTIAL 도 멱등 baseline 에 포함 | integration | High | DatasetExportService.java:150-156; DatasetExportBridge.java:55-61 |
| TC-EXPORT-004 | **정상 산출 SUCCEEDED + EXPORT_PATH_NM = 영상 루트** | 프레임·메타 존재 | export | ORIGINAL+DEIDENTIFIED write, `skipped=0` → SUCCEEDED, `EXPORT_PATH_NM` = **`{dirname(RAW_FILE_PATH_NM)}/{rawSn}`**(버전 루트 아님 — 관제가 `v1`·`v2`·`deid/` 를 한 경로 아래에서 본다) | integration | High | DatasetExportService.java:162-233; DatasetExportPathResolver.java:resolveVideoRoot |
| TC-EXPORT-005 | **일부 프레임 부재 PARTIAL** | 원천 이미지 일부 부재 | export | `totalSkipped>0` → PARTIAL, `dataset.export.skipped_frames` 증가 | integration | High | DatasetExportService.java:211-222 |
| TC-EXPORT-006 | 산출 0건 FAILED | `totalWritten==0` | export | `markFailed`, 승인 롤백 없음 | integration | Med | DatasetExportService.java:205-210 |
| TC-EXPORT-007 | **파생영상 = ORIGINAL 벌 미생성(PARTIAL 아님)** | 해상도/증강 파생 RAW export | export | 전 프레임 `SRC_FILE_PATH_NM` 이 비어 있으면 ORIGINAL 벌을 **아예 만들지 않고** PARTIAL 판정에서도 제외 → deid 1벌만 SUCCEEDED. 구 동작(ORIGINAL 전 프레임 skip → 항상 PARTIAL 강등) 폐기 | integration | High | DatasetExportService.java:183-199,330-336 |
| TC-EXPORT-008 | 버전 채번 UK 충돌 재시도 | 동시 승인 동일 버전 | `insertWithRetry` | 재채번 최대 3회(UK 백스톱) | integration | High | DatasetExportService.java:367-377 |
| TC-EXPORT-009 | 버전 채번 재시도 소진 | 3회 UK 위반 | export | null → `VERSION_EXHAUSTED` outcome, abort | unit | Med | DatasetExportService.java:174-178 |
| TC-EXPORT-010 | 파일쓰기 실패 시 승인 불변 | `writer.write` throw | export | `markFailed` 만, 승인 롤백 없음(@Async 삼킴), 예외 클래스명만 로깅 | integration | High | DatasetExportService.java:234-241 |
| TC-EXPORT-011 | 입력 부재 NO_INPUT | `loadPreparation` empty | export | 조기 return, `no_input` metric(행 미INSERT) | unit | Med | DatasetExportService.java:143-147 |
| TC-EXPORT-012 | 메트릭 outcome 정확히 1회 | 모든 종결 분기 | export | `dataset.export.result` counter 1회 + `dataset.export.duration` timer 1회 stop(finally 단일 지점, 조기 return·예외 이탈 포함) | unit | Med | DatasetExportService.java:107-109,255-263 |
| TC-EXPORT-013 | 브릿지 조건부 비활성화 | `authoring.dataset-export.enabled=false` | 부트 | 브릿지 빈 미생성 | unit | Low | DatasetExportBridge.java:29-30 |
| TC-EXPORT-014 | **폴더 구조 계약 (co-locate)** | 승인 export | 폴더 | `{dirname(원본영상)}/{rawSn}/v{n}/{orgnl\|deid}/` + 프레임별 JSON. 고정 `labeling_root` 폐기 | integration | Med | DatasetExportPathResolver.java:resolve |
| TC-EXPORT-015 | **산출 base 3중 가드 + fail-secure (신규)** | `RAW_FILE_PATH_NM` blank 또는 허용 마운트 루트 밖 | export | 기본 루트로 조용히 새지 않고 export 레코드 INSERT 후 즉시 FAILED(`EXPORT_PATH_NM`=null) + ERROR 로그(경로 원문 미노출). allowlist → normalize → `toRealPath` 3단 | security | High | DatasetExportService.java:162-169,345-360 |
| TC-EXPORT-016 | **비식별 신고 게이트 = 산출 자체 skip (신규)** | `DE_IDNTF_YN='F'` | 승인/수정/재동결/회수 어느 트리거든 | `export()` 진입부 단일 게이트에서 예외 이탈 → `LS_DATASET_EXPORT` **행 미생성**(FAILED 아님), metric `outcome=deident_blocked` + WARN, **통지도 함께 보류** | security | High | DatasetExportService.java:136-141; AsyncDatasetExportRunner.java:115-129 |
| TC-EXPORT-017 | **쓰기 중 신고 접수 = 마감 차단 + v{n} 폴더 삭제 (신규)** | 산출 도중 신고 커밋 | 마감 직전 | `finalizeUnlessUnderDeidentReport` 가 RAW 잠금 하 재판정 → false → export 행 **삭제** + 이번 실행이 만든 `v{n}` 만 재귀 삭제(전 버전·`deid/`·원본 미영향, 3중 경로가드 통과 시에만) + 예외 이탈로 통지 보류 | security | High | DatasetExportService.java:180-254,266-304; DatasetExportTxService.java:224-240 |
| TC-EXPORT-018 | **export 성공 후에만 통지 (신규)** | 승인 경로 | `runApprovalAsync` | `doExport` true 일 때만 `DatasetExportCompletedEvent` 발행(동기 리스너 → export→통지 순서 보장). 실패면 **통지 보류** — 관제가 구 버전 폴더를 픽업하지 않는다 | integration | High | AsyncDatasetExportRunner.java:67-76,121-130 |
| TC-EXPORT-019 | **승인 후 수정 = 재export 후 통지 (신규)** | 디바운스 flush | `runReExportThenNotify` | export 성공 시에만 `afterExport`(통지) 실행. 콜백은 `Runnable` 로 받아 통지 패키지 컴파일 의존 없음 | integration | High | AsyncDatasetExportRunner.java:90-106 |
| TC-EXPORT-040 | **실패 export 회수 후 통지 재개 (신규)** | FAILED 행 존재, `RTY_NMTM` < 상한 | 회수기 | `claimForRetry` 조건부 UPDATE(2노드 중복 방지) 통과분만 `runApprovalAsync` 재산출 → 성공 시 완료 이벤트 재발행(통지 유실이 아니라 성공 시점으로 지연). 신고 구간 건은 시도 상한 소진 방지를 위해 제외 | integration | High | DatasetExportFailureRecoverer.java:119-167; DatasetExportTxService.java:257-278 |
| TC-EXPORT-041 | **신고 해소 시 보류분 복구 (신규)** | `'F'→'Y'` resolve + APPROVED | `DeidentReportResolvedEvent` | `runApprovalAsync` 재트리거 → force=true 전량 재생성 + 완료 이벤트로 통지 재개. 신고 차단은 export 행을 남기지 않아 회수기가 집지 못하므로 **유일한 복구 경로**. 범위는 그 영상 하나(파생 팬아웃 없음) | integration | High | DatasetExportBridge.java:81-87 |
| TC-EXPORT-042 | **재export 트리거는 control-notify 토글과 무관 (신규)** | `authoring.control-notify.enabled=false` | 승인 후 수정 | 재export 는 정상 수행됨(토글은 통지 발송만 게이팅) | integration | High | DatasetExportBridge.java:29-30 (자체 토글만 참조) |
| TC-EXPORT-043 | **retention 정리 로직 없음 (신규)** | 여러 번 재승인 | 저장소 | `v1..vN` 전 버전 **보존**(삭제 잡 미구현이 확정 정책 — 롤백/복구 요구 충족). 저장소 증폭은 감수 | unit | Med | DatasetExportService.java:45-46,171-172 (TODO 주석) |

## E-7. Export JSON 포맷 (NiaJsonBuilder / NiaAnnotationDoc / NiaVideo / NiaImage / VideoMetaMapper)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-EXPORT-020 | **★최상위 키는 `event` (rename)** | 동결 `eventAnnotation` JsonNode | build | 프레임 문서 최상위 키가 **`event`**(구 `event_annotation` 폐기, E-ISSUE-44 확정). 내부 필드/클래스명은 `eventAnnotation` 유지. 위치는 `video` 다음 고정. `JsonNode` pass-through 로 키 순서·형태 보존 | unit | High | NiaAnnotationDoc.java:24-37 |
| TC-EXPORT-021 | event 값 null 처리 | 동결 event 없음 | build | `"event": null` (키는 항상 present — 클래스 `@JsonInclude(ALWAYS)`) | unit | Med | NiaAnnotationDoc.java:24,32 |
| TC-EXPORT-022 | **anonymity=ExportKind 파생(수동 override 금지)** | 프레임 수동 `ANONY_INCL_YN='Y'` | ORIGINAL build | `image.anonymity="N"`(원본), DEIDENTIFIED="Y" — 수동값이 덮지 않는다(CWE-359) | unit | High | NiaJsonBuilder.java:150-153 |
| TC-EXPORT-023 | pseudonymity/privacyIncluded 수동 우선 | 프레임 psdo/prvc 저장 | build | 수동값 우선, blank/null 이면 파생 폴백(CHAR(1) 공백 패딩도 blank 취급) | unit | High | NiaJsonBuilder.java:156-159,203-206 |
| TC-EXPORT-024 | **deid 산출 경로 fail-secure** | `deidVideoPath=null` | DEIDENTIFIED build | `dataset.src_path`/`dataset.name`·`video.filename` 모두 **null**(원본 절대경로·파일명 미노출) | security | High | NiaJsonBuilder.java:132-137; VideoMetaMapper.java:61-64 |
| TC-EXPORT-025 | malformed 라벨 skip | 라벨 매핑 예외 | `buildAnnotations` | 해당 1건 skip, `lblSn` 만 로깅, 문서 전체는 정상 생성 | unit | Med | NiaJsonBuilder.java:178-201 |
| TC-EXPORT-026 | 잉여키 제거/키 유지 정합 | 미보유 필드 | 직렬화 | `@JsonInclude(ALWAYS)` 로 키 유지, 값 null | unit | Med | NiaVideo.java:12; NiaImage.java:9 |
| TC-EXPORT-027 | **vd_description 필드 존재** | video 블록 | 직렬화 | `vd_description` 키 포함(값은 null — 데이터 출처 없음) | unit | Med | NiaVideo.java:46; VideoMetaMapper.java:101 |
| TC-EXPORT-028 | **weather/time_of_day/season — 수동값만** | raw 수동값 / meta 동결값 | `toVideo` | raw(최신 수동값) → meta(동결값) 우선순위. ★self-fill 제거로 **동결값도 수동값만**(미입력은 null) — `SHT_DT` 파생은 export·동결 경로에서 완전 폐기. blank→null 정규화. `ExportKind` 로 분기하지 않아 2벌 동일 | unit | High | VideoMetaMapper.java:46-59; DatasetVideoMetaSnapshotService.java:106-124 |
| TC-EXPORT-029 | 촬영환경 blank→null 정규화 | 빈 문자열 | `toVideo` | `firstNonBlank` null 정규화(스냅샷 표현과 일치) | unit | Med | VideoMetaMapper.java:144-152 |
| TC-EXPORT-030 | anonymity kind override(video) | ORIGINAL/DEIDENTIFIED | `toVideo` | N/Y 오버라이드, 2벌 촬영환경 동일 | unit | Med | VideoMetaMapper.java:65 |
| TC-EXPORT-031 | meta null 방어 | meta=null | `prepareContext` | 400("영상 메타가 null 입니다.") | unit | Low | NiaJsonBuilder.java:88-91 |
| TC-EXPORT-032 | FORMAT_VERSION/info 계약 | 정상 | `prepareContext` | `info.version="1.3"`, `type="instances"` | unit | Low | NiaJsonBuilder.java:36-40,96 |
| TC-EXPORT-033 | **image.file_name 은 ExportFileNaming 단일 지점 (신규)** | 프레임 문서 | `buildImage` | `ExportFileNaming.imageFileName(frameNo)` — 구 `frame-{n}.jpg` 직접 조립 금지(같은 폴더에 실재하지 않는 파일을 가리키던 드리프트) | unit | High | NiaJsonBuilder.java:142-145 |
| TC-EXPORT-034 | **frame_num = VDO_FRM_NO, 미측정이면 null (신규)** | `VDO_FRM_NO` null | `buildImage` | `frame_num=null` — `FRM_NO`(추출 순번) 폴백 금지(의미가 다른 값을 영상 내 위치로 위장) | unit | High | NiaJsonBuilder.java:146-149,169-170 |
| TC-EXPORT-035 | **파생영상 video.filename 은 비식별 사본 (신규)** | 파생 RAW export | DEIDENTIFIED build | 파생은 deid 1벌만 산출되며 `filename`/`dataset.src_path` 는 파생 자신의 비식별 사본 경로 basename. 동결 스냅샷의 `RAW_FILE_PATH_NM` 은 파생에서 null 이므로 ORIGINAL 벌 경로가 실릴 여지가 없다 | integration | High | DatasetVideoMetaSnapshotService.java:126-133; DatasetExportService.java:183-199 |
| TC-EXPORT-036 | **dataset 블록 kind 분기 (신규)** | ORIGINAL/DEIDENTIFIED | `buildDataset` | ORIGINAL=`meta.RAW_FILE_PATH_NM`, DEIDENTIFIED=`deidVideoPath`. 비식별 산출물에 원본 경로가 새지 않는다 | unit | High | NiaJsonBuilder.java:132-137 |
| TC-EXPORT-037 | **frm_expln pass-through (신규)** | 프레임 설명 입력 | `buildImage` | `NiaImage` 마지막 필드로 `src.getFrmExpln()` 전달(프레임 설명 수정도 재export 7경로 중 하나) | unit | Med | NiaJsonBuilder.java:174 |

## E-8. 촬영환경 메타 (EnvironmentMetaService — 영상 단위)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-META-001 | 조회 — 수동값 우선 프리필 | 수동 저장값 존재 | GET environment-meta | 저장값 + `source=MANUAL` | integration | High | EnvironmentMetaService.java:192-211 |
| TC-META-002 | 조회 — 파생 프리필(화면 전용) | 수동값 없음, `SHT_DT` 존재 | GET | `timeOfDay`/`season` 파생 + `source=DERIVED`, `weather=null`. ★조회 프리필은 **유지**(응답에 출처를 함께 내려 투명) — 폐기된 것은 **동결·산출 경로의 파생**이다 | unit | High | EnvironmentMetaService.java:193-219; DatasetVideoMetaSnapshotService.java:118-123 |
| TC-META-003 | PUT 전체 교체 저장 | 3필드 전송 | update | `changeShootingEnvironment` dirty checking — 3필드만 UPDATE(배치 컬럼 미간섭) | integration | High | EnvironmentMetaService.java:100-129 |
| TC-META-004 | **null 필드 = 수동값 삭제** | weather만 값 | PUT | 나머지 수동값 삭제 → 조회 시 파생 폴백 | unit | High | EnvironmentMetaService.java:106-112,174-185 |
| TC-META-005 | 허용값 화이트리스트 weather | "폭우" 등 | PUT | 400 — 맑음/흐림/비/눈/안개만 | security | High | ShootingEnvironmentVocabulary.java:23 |
| TC-META-006 | 허용값 timeOfDay/season | DAY/NGT · 4계절 외 | PUT | 400 | unit | High | ShootingEnvironmentVocabulary.java:26-32 |
| TC-META-007 | 길이 상한 | 허용값 초과 길이 | PUT | 400(허용값이 모두 컬럼 길이 이내라 화이트리스트 검사에 함께 걸림) | unit | Med | EnvironmentMetaService.java:174-185 |
| TC-META-008 | **APPROVED 후 수정 재동결 + 통지** | 검수완료 영상 수정 | PUT | 동결 스냅샷 재동결(`RVW_CMPL_DT` **승계** — 편집 시각으로 덮지 않음) + `TaskModifiedEvent(META_UPDATED, exportRegenerated=true)` | integration | High | EnvironmentMetaService.java:120-127,156-167 |
| TC-META-009 | ~~재동결 시 export 미재생성~~ | APPROVED 수정 | PUT | **[폐기 2026-07-30]** ★정책 반전(C-1b, 2026-07-27 확정) — 재동결만 하고 파일이 옛 촬영환경으로 남으면 "데이터마트 학습데이터셋 동기화" 요구가 미충족. 이제 **새 버전 폴더로 전량 재생성**한다 → 대체는 TC-META-017 | integration | — | EnvironmentMetaService.java:122-127,145-150 |
| TC-META-010 | 미검수 영상 수정 — 재동결·통지 없음 | PENDING | PUT | 재동결·통지 모두 미수행(이후 최초 승인의 materialize 가 수동값을 캡처) | unit | Med | EnvironmentMetaService.java:120,221-227 |
| TC-META-011 | 재동결 시 활성 스냅샷 부재 fail-safe | APPROVED인데 스냅샷 없음 | PUT | skip(warn), 예외 없음 | unit | Low | EnvironmentMetaService.java:157-161 |
| TC-META-012 | **동시성 — env저장 vs 승인 materialize** | PUT 과 approve 동시 | PUT | `videoRepository.flush()`(raw 행 락) → `acquireRawLock(rawSn)`(advisory) **순서 고정** → 상태 판정. 잠금 순서 단방향(교착 없음), 양방향 창 폐쇄 | integration | High | EnvironmentMetaService.java:115-121 |
| TC-META-013 | WORKER 본인배정 아닌 영상 | 타 영상 | GET/PUT | 403(`LabelAccessGuard.verifyRawAccess` — IDOR) | security | High | EnvironmentMetaService.java:67,102 |
| TC-META-014 | 미인증/포털 채널 | null / PORTAL | 요청 | 401 / 채널 격리 403 | security | High | EnvironmentMetaController.java |
| TC-META-015 | 영상 미존재 | 미존재 rawSn | GET/PUT | 404 | unit | Med | EnvironmentMetaService.java:187-190 |
| TC-META-016 | 로그 PII 미출력 | 허용값 외 입력 | PUT | 필드명만 로깅, 입력 원문·PII 미노출(CWE-117/209) | security | Med | EnvironmentMetaService.java:179-184 |
| TC-META-017 | **APPROVED 후 수정 = export 새 버전 전량 재생성 (신규)** | 검수완료 영상 촬영환경 수정 | PUT | `exportRegenerated=true` → 디바운스 flush 가 **export(force=true) 먼저** 수행 후 통지 → `v{n+1}` JSON `video.weather/time_of_day/season` 에 새 값 반영 | integration | High | EnvironmentMetaService.java:122-127; AsyncDatasetExportRunner.java:90-106 |
| TC-META-018 | **★동결값은 수동값만 (self-fill 폐기) (신규)** | 수동값 미입력 + `SHT_DT` 존재 | 승인 materialize | `DAY_NGT_CD`/`SESN_CD`/`WTHR_NM` **모두 null 동결** — `SHT_DT` 규칙 파생 폐기(여름 18:00 이 NGT 로 오분류되던 실증). blank 는 null 로 정규화해 해시 표현 일치 | integration | High | DatasetVideoMetaSnapshotService.java:106-124 |
| TC-META-019 | **레거시 파생 동결값 정정 백필 — dry-run (신규)** | NGT/SUMMER 파생 동결 행 잔존 | `GET /v1/dev/dataset-video-meta/shooting-env-correction-targets` | 200, 대상 건수만 반환. **재동결·export 재생성·통지 미발생**. REVIEWER 전용 + `@Profile("!prd")` | integration | High | DatasetVideoMetaBackfillDevController.java (GET) |
| TC-META-020 | **레거시 정정 백필 — 실행 + 상한 + 멱등 (신규)** | 대상 다수 | `POST /v1/dev/dataset-video-meta/shooting-env-corrections` | 1회 실행당 `env-correction.max-per-run`(기본 200) 상한, 응답에 `corrected`+`remaining`. 정정된 영상은 판별식에서 빠져 재호출 시 0건(자연 멱등). 정정 1건마다 재동결 → `TaskModifiedEvent` → export 재생성 | integration | High | DatasetVideoMetaBackfillDevController.java (POST); DatasetVideoMetaBackfillService.java:56-72 |

## E-9. 프레임 개인정보 메타 (FramePrivacyMetaService — 프레임 단위)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-META-030 | 조회 — 수동값 우선/파생 폴백 | 저장값 유무 | GET privacy-meta | 수동 우선, 미저장 시 파생(`PRVC_TYPE_CD=ANONY→Y` 등) | integration | High | FramePrivacyMetaService.java:66-71 |
| TC-META-031 | 단건 PUT 전체 교체 | 3필드 | update | `updatePrivacyMeta`, null=수동값 삭제 | integration | High | FramePrivacyMetaService.java:73-78 |
| TC-META-032 | 값 화이트리스트 `^[YN]$` | "1"/"true" | PUT | 400(`@Pattern`) | security | High | FramePrivacyMetaUpdateRequest.java |
| TC-META-033 | path/body srcSn 불일치 | 상이 | PUT | 400(CWE-345) | security | High | FramePrivacyMetaController.java:85-88 |
| TC-META-034 | **anonymity 는 export 미덮음** | 프레임 `ANONY_INCL_YN='Y'` | export 대조 | 화면·기록용만 — export `image.anonymity` 는 `ExportKind` 파생 유지(TC-EXPORT-022 와 쌍) | unit | High | FramePrivacyMetaService.java:46-50; NiaJsonBuilder.java:150-153 |
| TC-META-035 | 벌크 저장 N+1 제거 | 다수 프레임 | PUT /privacy-meta | `findAllById` 1회 + rawSn당 인가 1회 + `saveAll` 1회 | integration | Med | FramePrivacyMetaService.java:100-137 |
| TC-META-036 | 벌크 미존재 프레임 404 | 미존재 srcSn 포함 | 벌크 PUT | 404(전체 롤백) | unit | High | FramePrivacyMetaService.java:101-118 |
| TC-META-037 | 벌크 타 영상 403 | 타 배정 rawSn 포함 | 벌크 PUT | 403(404先→403後 순서 보존) | security | High | FramePrivacyMetaService.java:119-123 |
| TC-META-038 | 벌크 원자성 | 부분 실패 | 벌크 PUT | `@Transactional` 전체 롤백 | integration | Med | FramePrivacyMetaService.java:99 |
| TC-META-039 | **APPROVED 후 수정 = 재export + 통지 디바운스** | 검수완료 다프레임 | 벌크 PUT | 프레임별 `TaskModifiedEvent(META_UPDATED, exportRegenerated=true)` → rawSn 1회 코얼레스 → export(force) 먼저, 그 뒤 통지 | integration | High | FramePrivacyMetaService.java:126-135,153-155 |
| TC-META-040 | 벌크 항목 수 초과/빈 목록 | 초과·empty | 벌크 PUT | 400 | unit | Med | FramePrivacyMetaController.java:102-108 |
| TC-META-041 | WORKER 본인배정/미인증 | 타 프레임·null | GET/PUT | 403/401 | security | High | FramePrivacyMetaService.java:66-75 |
| TC-META-042 | 로그 판단값 미출력 | 저장 | PUT | `srcSn`·`rawSn` 만 로깅(CWE-359) | security | Med | FramePrivacyMetaService.java |
| TC-META-043 | **단건 수정도 재export 트리거 (신규)** | APPROVED 영상 프레임 1건 | 단건 PUT | `exportRegenerated=true` 로 `TaskModifiedEvent` 발행 → 새 버전 폴더 전량 재생성 후 통지(재export 7경로 중 "개인정보 메타 수정") | integration | High | FramePrivacyMetaService.java:153-155 |
| TC-META-044 | **파생영상 프레임의 개인정보 3필드 상속 (신규)** | 부모 프레임 anony/psdo/prvc 입력 | 파생 확정 | 증강·해상도 파생 프레임이 부모 값을 복사, 부모 null 이면 파생도 null(파생 폴백 적용). 두 경로 동일 규칙 | integration | Med | ResolutionPersistService.java:281-289; AugmentExtractPersist.java:104-115 |

---

> **불확실 항목 해소 (이번 회차)**
> - **#16 `ExternalAugmentClient` 실연동** → **해소**. `HttpExternalAugmentClient`(`POST /api/genai/jobs`, 202 → 외부 job_id) 가 `mode=http`(기본) 에서 활성, `NoopExternalAugmentClient` 는 `mode=noop` 전용. `syncDecision` 은 양 구현 모두 no-op(외부 계약 미정의).
> - **#17 `AsyncAugmentFrameRunner`** → **해소**. A(`AugmentExtractSnapshot`, REQUIRES_NEW) / B(`AugmentFrameProducer`, 트랜잭션 밖 파일 I/O — 외부 산출물 **반입**, 구 ffmpeg 재추출 폐기) / C(`AugmentExtractPersist`, REQUIRES_NEW) 3빈 분리. 실패 시 cleanup + RAW FAILED + aug dead-letter.
> - **#18 `DerivedMetaCopier`** → **해소**. `video.*` 포함 **전체 복사**가 정상(파생 비디오는 부모 비식별본 복사라 재인코딩 없음) · 부모에 검수행이 있던 메타키만 **PENDING** 검수행 신설 · 배치 upsert 라 **확정 블록 이후 호출** 계약.
> - **#19 `DatasetExportWriter`/`DatasetExportTxService`** → **해소**. Tx 경계는 전부 REQUIRES_NEW, 마감은 `finalizeUnlessUnderDeidentReport`(RAW 잠금 재판정 → 차단 시 export 행 삭제), 회수기는 `claimForRetry` 조건부 UPDATE.
> - **#20 event_annotation 동결 소스(cot 배열→객체)** → **부분 해소**. export 최상위 키가 **`event`** 로 확정(`NiaAnnotationDoc`), 동결 소스는 `LS_DATASET_VIDEO_META.EVNT_ANNO_CN`(`resolveApprovedEventAnnotation`, 승인 시점 payload 원문, 멱등 해시 포함). `JsonNode` pass-through 라 배열/객체 양형이 그대로 보존된다. ⚠ `EvntAnnoService`(event_annotation 수정)는 **재동결 미배선**이라 승인 시점 동결본이 그대로 나가는 것이 현재 사실(CLAUDE.md 예외 조항).
