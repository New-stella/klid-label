# E. 증강 / 해상도 / Export / 메타 — 1차 검증 결과

> 검증일 2026-07-25 · 기준: **실동작**(풀스택+목업서버, backend V130)

# E-part1 — 증강 요청·검수·콜백 웹훅 실동작 검증 (E-1 / E-2 / E-3)

> 대상: `docs/test-cases/E-augment-resolution-export-meta.md` 5~65행
> 환경: 로컬 풀스택(klid-backend :18081/api · klid-postgres · klid-ai-server · klid-mock-server :9400 · klid-frontend), profile=local
> 원칙: 외부 연동은 mock-server 대행 · self-fill(외부 응답 없이 자체 생성) = FAIL · "코드가 그래 보인다" 금지

---

## E-1. 증강 요청

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-AUG-001 | 검수완료 영상 증강 요청 성공 | PARTIAL | [실동작] `POST /v1/augments/request {videoIds:[13],types:["WINTER"]}` → 200 `{jobId:1784944791014,videoCount:1,typeCount:1}`, DB `ls_data_aug` #8 PENDING(src_sn=358) INSERT. [정적] AugmentRequestService.java:94-145 일치 | AugmentRequestServiceTest `createsPendingAugPerVideoAndType`, AugmentRequestControllerTest `증강요청_WINTER_단일_정상_200` | ①케이스 기대 "types(3종)·영상 N건"은 DTO `@Size(max=1)`(AugmentRequestRequest.java:28,32)로 400 거부 — 계약 드리프트(E-ISSUE-08) ②**외부 시스템 호출 0건** — Noop 클라이언트라 PENDING 행이 영구 정체(E-ISSUE-02) |
| TC-AUG-002 | 미검수 영상 포함 시 전체 거부 | PASS | [실동작] `videoIds:[4]`(ASSIGNED) → 400 `{"data":{"blockedVideoIds":[4]},"errorCode":"NOT_REVIEWED"}`, aug 행 0건. 로그 `request blocked — not reviewed blockedCount=1` | `rejectsWhenSomeVideosNotApproved`, `rejectsWhenStatusRowMissing` | 단건 계약이라 "일부만 미검수" 혼합 시나리오는 API로 재현 불가 |
| TC-AUG-003 | videoIds/types 중복 정규화 | PARTIAL | [실동작] `videoIds:[13,13]` → **400 INVALID_INPUT**("영상은 한 번에 1건만") — distinct 로직(:99-103) 도달 전 DTO 차단. 기대(distinct 후 정상 진행)와 불일치 | `distinctVideoIds`/`distinctTypes` (서비스 직접 호출 — API 경로 미검증) | distinct 코드가 API 경로에서 **도달 불가(dead)**. 단위테스트만 GREEN (E-ISSUE-08) |
| TC-AUG-004 | 프레임 없는 영상 건별 스킵 | 확인필요 | [정적] :126-138 skip + continue. [실동작 불가] APPROVED & 프레임 0건 데이터 부재(현 DB: 0프레임 영상 9·10·20~22는 모두 미APPROVED) | `videoWithoutFrameIsIsolated` | 스킵돼도 응답은 200 + `videoCount:1` — 요청자가 실패를 인지 못함(E-ISSUE-09) |
| TC-AUG-005 | WORKER 증강 요청 차단 | PASS | [실동작] WORKER 토큰 → 403 `{"errorCode":"FORBIDDEN"}` (Controller `@PreAuthorize` 1차 차단). [정적] 서비스 이중검증 :234-241 | `AugmentRequestController_WORKER_권한으로_요청시_403`, `workerCannotRequest` | |
| TC-AUG-006 | 인증 토큰 없음 | PASS | [실동작] 토큰 미첨부 → 401 `{"errorCode":"UNAUTHORIZED"}` | — | |
| TC-AUG-007 | idempotencyKey/externalJobId 형식 강제 | PASS | [실동작] DB `idmp_key=AUG-453e0b33-…`(40자, `^[A-Za-z0-9_-]+$`), `otsd_job_id=JOB-51c916c1-…`(40자). [정적] :178-193 | `issuesIdempotencyKeyPerAug`, `augSavedWithIdempotencyKeyInSingleSave` | |
| TC-AUG-008 | 건별 격리 — 1건 실패 전체 미영향 | PARTIAL | [정적] :154-175 try/catch + false. [실동작] 단건 계약이라 "나머지 진행" 자체가 성립 불가 — 유일 항목 실패 시 200 + createdCount 0(무통보) | `externalFailureDoesNotBlockSuccess` | E-ISSUE-08 / E-ISSUE-09 |
| TC-AUG-009 | AFTER_COMMIT — 롤백 시 키·행 미생성 | PASS | [실동작] 요청 5건 커밋 후 `ls_webhook_idempotency` 에 AUGMENT/ISSUED 5행만 존재(고아 없음), 400 거부건은 0행. [정적] AugmentRequestBridge.java:43-51 `@TransactionalEventListener(AFTER_COMMIT)` 별도 빈 | `noOrphanLedgerKeyOnRollback`, `ledgerKeyIssuedAfterCommit` | |
| TC-AUG-010 | 콜백 URL 정규화 | PASS | [실동작] 로그 `callbackUrl=http://localhost:8080/api/v1/aug/callback` (base trailing-slash 제거 + `HmacWebhookFilter.PATH_AUGMENT` 결합). [정적] :195-203 | `CALLBACK_PATH가_필터_요청측_시뮬_3곳에서_동일하다` | base 기본값이 **자기 자신(localhost:8080)** — 외부 미override 시 자족 호출(E-ISSUE-10) |
| TC-AUG-011 | jobId 동시성 유일성 | PASS | [실동작] 연속 요청 jobId `…014/…015/…016` 중복 없음. [정적] :84,140 AtomicLong | — | jobId 미영속·다운스트림 미사용(FE는 잡카드 videoId=RAW_SN 사용). 2노드 Active-Active 시 base(부팅 ms) 접근 충돌 가능하나 영향 없음 |

## E-2. 증강 검수

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-AUG-020 | 증강 결과 승인 PENDING→ACCEPTED | PASS | [실동작] `POST /v1/augments/14/accept` → 200 `{augProcSttsCd:"ACCEPTED",decisionUserNo:"1001",decisionAt:…}`, DB ACCEPTED. 로그 `[Augment] accepted dataAugSn=14` | `REVIEWER_accept시_…_RVW_row_INSERT` | syncDecision 은 Noop 로그만(외부 통보 실제 없음) |
| TC-AUG-021 | 이미 처리된 결과 재승인 차단 | PASS | [실동작] 동일 행 재accept → 409 "이미 처리된 증강 결과입니다. status=ACCEPTED"; accept 후 reject 도 409(양방향 차단). [정적] LsDataAug.java:243-254 | `이미_ACCEPTED인_AUG_재처리시_409_CONFLICT` | |
| TC-AUG-022 | 반려 사유 누락 거부 | PASS | [실동작] `reason:"   "` → 400 "reason: 반려 사유는 필수입니다.", `{}`(null) → 400 동일. DTO(RejectRequest)에서 1차, 서비스 :351-353 2차 | `반려_사유_누락시_INVALID_INPUT` | |
| TC-AUG-023 | 반려 정상 PENDING→REJECTED | PASS | [실동작] `POST /15/reject {"reason":"품질 불량"}` → 200 `{augProcSttsCd:"REJECTED",rejectReason:"품질 불량"}` | `REJECTED_시_사유_…_저장` | |
| TC-AUG-024 | **해상도 파생 accept 차단** | PASS | [실동작] `POST /3/accept`(RESL_720P) → 400 "해상도 파생 결과는 검수 대상이 아닙니다." [정적] :385-395 | `해상도파생_행에_accept_또는_reject호출시_INVALID_INPUT` | |
| TC-AUG-025 | 해상도 파생 reject 차단 | PASS | [실동작] `POST /3/reject` → 400 동일 메시지(loadOrThrow 게이트) | 동상 | |
| TC-AUG-026 | 존재하지 않는 증강행 검수 | PASS | [실동작] `POST /999999/accept` → 404 "증강 결과를 찾을 수 없습니다." | `NOT_FOUND_404_미존재_dataAugSn` | |
| TC-AUG-027 | 외부 sync 실패 best-effort | PARTIAL | [정적] :334-339 try/catch. [실동작] 정상 accept 200 확인. 단 현 구현(`NoopExternalAugmentClient.syncDecision`)은 **절대 throw 하지 않음** → throw 분기는 프로덕션 도달 불가, 단위테스트도 부재 | 없음 | 외부 미연동(E-ISSUE-02)의 파생 |
| TC-AUG-028 | 검수 WORKER/미인증 차단 | PASS | [실동작] WORKER → 403, 토큰 없음 → 401. [정적] :397-404 이중검증 | `WORKER가_accept_호출시_403_FORBIDDEN` | |
| TC-AUG-029 | 잡카드 전체 페이징 — SRC_SN 그룹 최신순 | PASS | [실동작] `GET /v1/augments?page=0&size=20` → total=5, 그룹 순서 19→5→13→17→14 (MIN(REG_DT) desc 정합), 각 그룹 1 videoCount. [정적] :81-89 + 일괄조회 4종(rawBySrc/cctv/review) — 루프 내 조회 없음 | `listAll_…` 6종 | |
| TC-AUG-030 | **types/resolutionTypes 분리 노출** | PASS | [실동작] videoId=17 → `types:["WINTER"]`, `resolutionTypes:["RESL_720P","RESL_480P"]` 분리. [정적] :185-190 | `해상도_이력항목은_검수액션_불가로_식별된다` | |
| TC-AUG-031 | AUG_ORDER 정렬 | PASS | [실동작] 혼합 그룹 `types:["WINTER","RAIN"]`(1→3), `resolutionTypes:["RESL_720P","RESL_480P"]`(6→7). [정적] :58-66 | `이력_정렬에_RESL_프리셋이_반영된다` | |
| TC-AUG-032 | aggregateStatus — dead-letter→FAILED | PARTIAL | [정적] :244-247 로직 정상. **[실동작 불가]** `LsDataAug.markDeadLetter()`(:276)·`incrementRetryCount()`(:271) **프로덕션 호출자 0건**(grep 결과 entity 자기 정의뿐) → FAILED 상태는 운영에서 도달 불가 | `listAll_그룹내_dead_letter_1건이면_status_FAILED` — 픽스처가 `markDeadLetter()` 직접 호출 = **실경로 미검증** | E-ISSUE-06 |
| TC-AUG-033 | aggregateStatus — 전부 terminal→COMPLETED | PASS | [실동작] videoId=17 전 행 terminal → `status:"COMPLETED"`, `completedAt:2026-07-25T11:39:30.077295` = accept 시각(max RVW_DT) 일치 | `listAll_전부_종료_상태면_status_COMPLETED_completedAt_채워짐` | |
| TC-AUG-034 | 일부 종료→IN_PROGRESS / 전부 PENDING→REQUESTED | PASS | [실동작] 그룹 408 에 PENDING 1건 추가 → `IN_PROGRESS`, `completedAt:null`. 신규 그룹(src 3, PENDING만) → `REQUESTED` | `listAll_일부만_종료면_status_IN_PROGRESS` | |
| TC-AUG-035 | **해상도 in-flight 집계 정합** | PARTIAL | [실동작 근사] RESL 2건 ACCEPTED + PENDING 1건 그룹 → COMPLETED 아님(IN_PROGRESS) 확인 = PENDING 이 terminal 로 세지 않음 입증. 다만 "RESL 예약 PENDING만 존재하는 in-flight 창"은 타이밍상 재현 못함(E-4 범위) | `해상도_파생_예약직후_finalize전에는_PENDING이라_집계가_COMPLETED가_아니다` | |
| TC-AUG-036 | aggregateResultStatus 매핑 | PARTIAL | [실동작] `/13/result`·`/17/result` → COMPLETED, `/99999999/result` → PROCESSING. **FAILED 분기는 dead-letter 도달 불가로 미검증**(TC-AUG-032 연동) | `aggregateResultStatus_…` 4종 | E-ISSUE-06 |
| TC-AUG-037 | aggregateResultStatus SRC_SN 폴백/무데이터 | PASS | [실동작] `/408/result`(=SRC_SN, RAW_SN 매핑 없음) → PROCESSING (findBySrcSnIn 폴백으로 그룹 집계 성립), `/99999999/result` → PROCESSING(무데이터). [정적] :123-130 | `집계대상_aug가_전무하면_PROCESSING_반환` | |
| TC-AUG-038 | cctvName 비-옵셔널 폴백 | 확인필요 | [실동작] 전 그룹 실명 반환(`CCTV-강남구-001/002/003`) — 매핑·시드가 모두 존재해 `"(이름 없음)"` 폴백 미재현. [정적] :264-271 | 없음 | 폴백 분기 미검증 |
| TC-AUG-039 | findBySource(srcSn) 필터 | PASS | [실동작] `?srcSn=99999999` → `{totalElements:0,content:[],empty:true}`; `?srcSn=408` → 해당 영상 1건만 | `findBySource_srcSn_필터시_…` | |
| TC-AUG-040 | list size 한도 초과 | PASS | [실동작] `?size=101` → 400 "size 한도 초과 (max=100)". [정적] AugmentController.java:80-82 | 없음 | `?size=101&srcSn=408` 은 200 — srcSn 분기가 size 검증보다 앞(케이스 전제 "srcSn 미지정"이라 범위 내) |

## E-3. 증강 콜백 웹훅

> ⚠ **전제**: 정상 경로 `POST /api/v1/aug/callback` 은 현 환경에서 시크릿 미설정 fail-closed 로 **전건 401**(E-ISSUE-04). 아래 실동작 검증은 실증된 필터 우회 경로 `POST /api/v1/%61ug/callback`(E-ISSUE-01) 으로 컨트롤러/서비스에 도달시켜 수행했다. 즉 **아래 PASS 들은 "인증만 통과하면 비즈니스 로직은 규격대로"라는 의미이며, 인증 계층은 별건 CRITICAL 결함**이다.

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-AUG-050 | 성공 콜백 신규 증강영상 생성 | PASS | [실동작] aug#8(WINTER, 부모 rawSn=13 `de_ident_yn='Y'`·11프레임) SUCCESS → 200 `{applied:true}`, aug ACCEPTED, DB 신규 `ls_data_raw` **rawSn=29 orgnl_raw_sn=13 de_ident_yn='N'**, 로그 `new video created (pending, async extraction) … dataStts=PENDING` + `AsyncAugmentFrameRunner starting … rawSn=29` | `Phase11_증강_SUCCESS_시_새_RAW는_PENDING_deIdntfYn_N으로만_커밋되고_프레임러너_트리거` | async 추출은 테스트 페이로드의 파일 경로가 실재하지 않아 실패(→FAILED) — 테스트 인공물, 동기 계약은 전부 충족 |
| TC-AUG-051 | 실패 콜백 REJECTED·영상 미생성 | PASS | [실동작] aug#10 `FAILED` → 200 `{applied:true}`, DB REJECTED, `orgnl_raw_sn=14` 신규 영상 0건 | `Phase11_증강_FAILED_시_새_영상_미생성_프레임러너_미트리거` | |
| TC-AUG-052 | **재전송 멱등(1차 앵커) non-PENDING skip** | PASS | [실동작] 종결된 aug#8 재콜백(다른 otsdJobId `EXT-OK-2` / 동일 `EXT-OK-1`) 모두 200 `{applied:false}`, 로그 `duplicate result skipped … state=ACCEPTED`, 중복 영상 0건 | `종결행_재전송_시_200_OK_멱등스킵`, `멱등_재전송_스킵_시_프레임러너는_한번만_트리거된다` | |
| TC-AUG-053 | 동시/오배송 UNIQUE(otsd_job_id) 흡수 | **FAIL** | [실동작] PENDING aug#16 에 이미 사용된 `otsd_job_id=EXT-OK-1` 전송 → 기대 `false` 멱등흡수, **실제 500 INTERNAL_ERROR**. 로그: `duplicate key value violates unique constraint "uk_aug_external_job_id"` → 이어서 `SQLState 25P02 current transaction is aborted, commands ignored until end of transaction block` → `JpaSystemException`(findByExternalJobId 재조회 실패) | `동시_콜백_UNIQUE위반_시_externalJobId_재조회_멱등흡수` — **Mockito 스텁으로 GREEN(위양성)** | E-ISSUE-05 |
| TC-AUG-054 | augType 불일치 차단 | PASS | [실동작] row=WINTER 에 `RAIN` 전송 → 409 "augType 불일치: row=WINTER request=RAIN" | `augType_불일치_시_409_CONFLICT` | |
| TC-AUG-055 | 대상 행 없음 | PASS | [실동작] `data_aug_sn:987654` → 404 "증강 행을 찾을 수 없습니다: dataAugSn=987654" | `미존재_dataAugSn_시_404` | |
| TC-AUG-056 | **부모 비식별 미완('F') PII 게이트** | PASS | [실동작] aug#9(부모 rawSn=5, `de_ident_yn='F'`) SUCCESS → 로그 `parent not deidentified — blocking augmented video parentRawSn=5 deIdntfYn=F`, `orgnl_raw_sn=5` 신규 영상 **0건** | `부모_deIdntfYn_F면_증강본_생성보류_…`, AugmentDeidentConcurrencyIT | 단, aug 행은 ACCEPTED 로 종결 + 응답 `applied:true` → 결과 조용히 유실(E-ISSUE-11) |
| TC-AUG-057 | 부모 프레임 0건 고아 RAW 방지 | 확인필요 | [정적] AugmentResultService.java:175-182 존재 확인. [실동작 불가] APPROVED & 프레임 0건 부모 데이터 부재 | `부모_프레임이_없으면_증강본_생성보류_프레임러너_미트리거` | |
| TC-AUG-058 | rawFilePathNm SSRF 차단 | PASS | [실동작] `http://169.254.169.254/latest/meta-data/` → 400 "…내부/사설 네트워크를 가리킵니다: 169.254.169.254 … CWE-918 SSRF 차단"; `http://127.0.0.1:8080/x.mp4` → 400 동일 | (ExternalUrlValidator 테스트) | |
| TC-AUG-059 | augTypeCd 화이트리스트 | PASS | [실동작] `RESOLUTION` → 400 "aug_type_cd 는 WINTER\|NIGHT\|RAIN 중 하나여야 합니다." | `증강요청_RESOLUTION_타입_400_거부` | |
| TC-AUG-060 | augProcStsCd 화이트리스트 | PASS | [실동작] `BOGUS` → 400 "aug_proc_sts_cd 는 SUCCESS\|FAILED\|PARTIAL 중 하나여야 합니다." | 없음 | |
| TC-AUG-061 | otsdJobId 패턴/길이 | PASS | [실동작] `"bad id!"` → 400 "otsd_job_id 는 영숫자/대시/언더스코어만 허용됩니다." | WebhookRequestSizeLimitTest | |
| TC-AUG-062 | dataAugSn null·rawFilePathNm>1000 | PASS | [실동작] `data_aug_sn` 누락 → 400 "dataAugSn: must not be null"; 1207자 경로 → 400 "rawFilePathNm: size must be between 0 and 1000" | WebhookRequestSizeLimitTest | |
| TC-AUG-063 | 동시 콜백 직렬화(FOR UPDATE) | PASS | [실동작] 동일 `data_aug_sn=16` 에 서로 다른 otsdJobId 3건 동시 POST → `applied:true` **정확히 1건**(CONC-1), 나머지 2건 `applied:false`, 신규 영상 **1건만**(rawSn=31) | AugmentDeidentConcurrencyIT `같은_dataAugSn_서로다른_otsdJobId_동시콜백_시_증강영상은_정확히_1건만_생성된다` | |

---

## 이슈 상세

### [E-ISSUE-01] TC-AUG-050~063 (전 콜백 케이스) — HMAC 웹훅 필터 경로 우회 (A-ISSUE-13 실증 확정)
- **심각도**: CRITICAL
- **기대 동작(기대효과)**: `/v1/aug/callback` 로 라우팅되는 **모든** 요청은 `HmacWebhookFilter` 의 서명·타임스탬프·본문크기·rate-limit 검증을 반드시 통과해야 한다.
- **현재 동작(이슈 내용)**: 필터 적용 여부를 `HttpServletRequest.getRequestURI()`(=**미디코딩 raw URI**)의 **정확일치**로 판정한다.
  - `HmacWebhookFilter.java:138-141` `shouldNotFilter` → `stripContext(request)`(:338-346, `getRequestURI()` 기반) → `!pathToSecret.containsKey(path)` 이면 필터 스킵
  - 반면 Spring MVC 라우팅은 **디코딩된 경로**로 매칭 → `/v1/%61ug/callback` 은 필터를 건너뛰고 `AugmentResultController`(permitAll)에 도달
  - 실증(무서명·무타임스탬프·시크릿 미설정 상태):
    - `POST /api/v1/aug/callback` → **401** `{"message":"Webhook 시크릿이 설정되지 않았습니다."}` (필터 적용)
    - `POST /api/v1/%61ug/callback` → **404** `{"message":"증강 행을 찾을 수 없습니다: dataAugSn=999999","errorCode":"NOT_FOUND"}` ← **서비스까지 도달 = 필터 우회**
  - 실제 상태 변조까지 성공: 무인증으로 `data_aug_sn=8/9/10/16` 을 ACCEPTED/REJECTED 로 전이시키고 신규 증강 영상 `rawSn=29,31` 을 생성했다(본 검증의 E-3 전건이 이 경로로 수행됨).
  - 2차 방어선도 없음 — AUGMENT 채널 멱등 원장은 **write-only**(E-ISSUE-07)라 콜백 시 조회되지 않고, 공격자는 `data_aug_sn`(순차 정수) + `aug_type_cd`(3택1)만 맞히면 된다.
  - `X-Timestamp` replay 윈도우·본문 1MB 캡·실패 rate-limit(5/분)도 전부 함께 무력화된다(우회 경로는 필터를 통째로 건너뜀).
- **재현/확인 경로**:
  ```bash
  # 필터 적용(대조군)
  curl -i -X POST 'http://localhost:18081/api/v1/aug/callback' -H 'Content-Type: application/json' \
    -d '{"data_aug_sn":999999,"otsd_job_id":"probe","aug_type_cd":"RAIN","aug_proc_sts_cd":"SUCCESS"}'   # 401
  # 우회(실험군)
  curl -i -X POST 'http://localhost:18081/api/v1/%61ug/callback' -H 'Content-Type: application/json' \
    -d '{"data_aug_sn":999999,"otsd_job_id":"probe","aug_type_cd":"RAIN","aug_proc_sts_cd":"SUCCESS"}'   # 404 (컨트롤러 도달)
  # 실제 변조(대상은 PENDING 행이면 무엇이든)
  curl -X POST 'http://localhost:18081/api/v1/%61ug/callback' -H 'Content-Type: application/json' \
    -d '{"data_aug_sn":<PENDING_PK>,"otsd_job_id":"EVIL-1","aug_type_cd":"<row와 동일>","aug_proc_sts_cd":"SUCCESS","raw_file_path_nm":"/nas-storage/evil.mp4"}'
  ```
  ```sql
  select data_aug_sn, aug_proc_stts_cd, otsd_job_id from public.ls_data_aug order by data_aug_sn desc;
  select raw_sn, data_stts_cd, orgnl_raw_sn, raw_file_path_nm from public.ls_data_raw where raw_sn >= 29;
  ```
- **영향**: **CWE-288** (Authentication Bypass Using an Alternate Path/Channel), **CWE-289**(Authentication Bypass by Alternate Name), 파생 **CWE-347**(서명 검증 부재)·**CWE-639**(임의 `data_aug_sn` 지정으로 타인 증강 결과 조작, IDOR)·**CWE-307 우회**(rate-limit 무력화)·**CWE-770 우회**(본문 캡 무력화). 공격자는 무인증으로 ①증강 결과 상태 위조(승인/반려) ②임의 `raw_file_path_nm` 을 가진 신규 RAW 행 대량 생성 ③외부 결과 인계 유실을 유발할 수 있다.
- **수정 방향(제안)**: 필터 적용 판정을 서블릿 컨테이너가 디코딩·정규화한 경로(Spring `ServletRequestPathUtils.getCachedPathValue` / `UrlPathHelper.getPathWithinApplication` 또는 `PathPatternParser` 매칭)로 바꾸고, "필터 적용 대상 판정"과 "컨트롤러 매핑"이 **동일한 경로 표현**을 쓰도록 단일화한다. 추가로 `SecurityConfig` 의 permitAll 매처도 동일 표현으로 좁히고, 컨트롤러 진입 시 "필터를 통과했다"는 마커(request attribute) 부재 시 fail-closed 로 거부하는 이중 게이트를 둔다. 회귀 테스트에 `%61ug`·대소문자·`;param`·`//` 변형 케이스를 추가.

### [E-ISSUE-02] TC-AUG-001/027 — 증강 외부 연동 미구현(ENV-ISSUE-01 재확인, 실측)
- **심각도**: HIGH
- **기대 동작**: 증강 요청이 목업서버(mock-server)를 포함한 외부 증강 시스템으로 실제 전달되고, 그 응답/콜백으로 결과가 채워져야 한다.
- **현재 동작**:
  - `ExternalAugmentClient` 의 유일 활성 구현이 `NoopExternalAugmentClient`(`NoopExternalAugmentClient.java:31-46`) — HTTP 호출 없이 로그만 찍고 `true` 반환. 실동작 로그로 확정:
    `[Augment] external request (noop) originAugSn=8 augType=WINTER externalJobId=JOB-… callbackUrl=http://localhost:8080/api/v1/aug/callback`
  - `docker logs klid-mock-server` 전체 기간 인바운드: `/project`·`/retrieve_progress`(KPST 비식별)·`/health` **뿐** — `/v1/augment` **0건**.
  - mock-server 측도 `mock-server/app/routers/augment.py:44-48` `POST /v1/augment` 가 **501 Not Implemented 스텁**이라 애초에 왕복이 성립하지 않음.
  - 결과: 증강 요청 행은 외부 응답 없이 **영구 PENDING**(재시도·타임아웃·dead-letter 없음, E-ISSUE-06 참조).
- **재현/확인 경로**: `docker logs klid-mock-server 2>&1 | grep augment` → 0건 / `docker logs klid-backend | grep "external request"` → noop 로그
- **영향**: SFR-07 외부 증강 위탁 플로우가 **엔드투엔드로 성립하지 않음**. 본 클러스터에서 외부 왕복에 의존하는 결과값은 전부 미검증 상태로 남는다.
- **수정 방향(제안)**: WebClient + Resilience4j 기반 실 구현체를 추가하고 `authoring.augment.external.mode` 로 noop/real/mock 을 전환. mock-server `/v1/augment` 를 접수→비동기 HMAC 서명 콜백 발신까지 구현해 로컬 왕복을 성립시킨다.

### [E-ISSUE-03] TC-AUG-001 — `application-local.yml` 키 오중첩으로 dev 콜백 시뮬레이터 영구 비활성
- **심각도**: HIGH
- **기대 동작**: local 프로파일에서 `authoring.augment.external.mode=dev` 가 되어 `DevAugmentCallbackSimulator` 가 로드되고 자족 콜백이 발신되어야 한다(주석에 명시된 의도).
- **현재 동작**: `backend/src/main/resources/application-local.yml:95-105` 에서 `augment:` 블록이 **`kpst:` 하위에 중첩**돼 있어 실제 프로퍼티 키는 `kpst.augment.external.mode` 다.
  ```yaml
  kpst:
    deid:
      enabled: ...
      base-url: ...
    augment:            # ← authoring: 이 아니라 kpst: 하위
      external:
        mode: ${AUGMENT_EXTERNAL_MODE:dev}
  ```
  `DevAugmentCallbackSimulator.java:46` 은 `authoring.augment.external.mode=dev` 를, `NoopExternalAugmentClient.java:26` 은 같은 키의 `noop`(matchIfMissing=true)을 본다 → 키가 미설정이므로 **항상 Noop** 이 활성. 실동작 로그가 `NoopExternalAugmentClient` 를 찍는 것으로 확정.
- **재현/확인 경로**: `docker logs klid-backend | grep "external request"` → `NoopExternalAugmentClient` (DevAugmentCallbackSimulator 로그 `[Augment][dev-sim] callback sent` 은 0건)
- **영향**: 로컬 자족 콜백 검증 수단이 침묵 상태로 죽어 있음. 콜백 관련 회귀가 로컬에서 전혀 감지되지 않는다.
- **수정 방향(제안)**: `augment:` 블록을 `authoring:` 하위로 이동. 아울러 `@ConditionalOnProperty(matchIfMissing=true)` 대신 기동 시 활성 구현체를 INFO 로 명시 로깅해 오설정이 침묵하지 않게 한다.

### [E-ISSUE-04] TC-AUG-050~063 — 정상 콜백 경로가 시크릿 빈 값으로 전건 401 (우회 경로만 열린 최악 조합)
- **심각도**: HIGH
- **기대 동작**: local 은 `application-local.yml:113` 의 기본 시크릿(32B)으로 정상 콜백이 통과해야 한다.
- **현재 동작**: `.env:45` 에 `WEBHOOK_HMAC_SECRET_AUGMENT=`(**빈 값**)가 정의돼 있고 컨테이너 env 로 주입된다(`docker inspect klid-backend` 확인). Spring 플레이스홀더는 "값이 빈 문자열"을 "미정의"로 보지 않으므로 yml 기본값이 적용되지 않고 시크릿 = `""` → `HmacWebhookFilter.java:156-161` fail-closed 로 **모든 정상 콜백 401**.
  로그: `[Webhook] secret missing path=/v1/aug/callback`
- **재현/확인 경로**: `docker inspect klid-backend --format '{{range .Config.Env}}{{println .}}{{end}}' | grep WEBHOOK` → `WEBHOOK_HMAC_SECRET_AUGMENT=` / 위 curl 대조군 401
- **영향**: 인증을 지키는 경로는 죽고(정상 콜백 불가), 인증을 건너뛰는 경로만 살아있는(E-ISSUE-01) 상태. 실 연동을 붙여도 콜백이 전부 401로 실패한다.
- **수정 방향(제안)**: `.env` 에서 빈 값 항목을 제거하거나 실제 값을 채운다. 부팅 시 "augment webhook 시크릿 미설정 → 콜백 비활성" 경고를 WARN 로 1회 노출(현재는 요청이 올 때만 로깅되어 배포자가 인지 못함).

### [E-ISSUE-05] TC-AUG-053 — UNIQUE 위반 후 "멱등 흡수" 경로가 PostgreSQL 에서 동작 불가 (500)
- **심각도**: HIGH
- **기대 동작**: 동시/오배송으로 `otsd_job_id` UNIQUE 가 충돌하면 `DataIntegrityViolationException` 을 잡아 `findByExternalJobId` 재조회 후 `false`(멱등 흡수)를 반환해야 한다(`AugmentResultService.java:119-131`).
- **현재 동작**: PostgreSQL 은 제약 위반 시 **트랜잭션 전체를 abort** 한다. catch 블록 내 재조회가 같은 트랜잭션에서 실행되므로 즉시 `SQLState 25P02 (current transaction is aborted…)` → `JpaSystemException` 전파 → **500 INTERNAL_ERROR**.
  ```
  ERROR ... duplicate key value violates unique constraint "uk_aug_external_job_id"
  WARN  ... SQL Error: 0, SQLState: 25P02
  ERROR ... current transaction is aborted, commands ignored until end of transaction block
  ERROR ... GlobalExceptionHandler - [Exception] unhandled exception  org.springframework.orm.jpa.JpaSystemException: ... select ... from LS_DATA_AUG lda1_0 where lda1_0.OTSD_JOB_ID=?
  ```
  대상 행(#16)은 변경 없이 PENDING 으로 롤백되어 데이터 손상은 없으나, 외부 시스템은 5xx 를 받고 **재전송을 반복**하게 된다.
  기존 테스트 `AugmentResultServiceTest:180-199` 는 Mockito 로 `save` 가 throw 하고 `findByExternalJobId` 가 값을 반환하도록 스텁 → **실 DB 의 aborted-transaction 을 전혀 재현하지 않는 위양성 GREEN**.
- **재현/확인 경로**:
  ```bash
  # 1) 임의 PENDING 행에 이미 사용된 otsd_job_id 를 전송
  curl -i -X POST 'http://localhost:18081/api/v1/%61ug/callback' -H 'Content-Type: application/json' \
    -d '{"data_aug_sn":<PENDING_PK>,"otsd_job_id":"<이미 존재하는 값>","aug_type_cd":"<row와 동일>","aug_proc_sts_cd":"SUCCESS"}'
  # → 500 INTERNAL_ERROR (기대: 200 {"applied":false})
  ```
- **영향**: 동시 콜백/오배송 시 멱등 계약 위반 + 5xx 노출. 외부 재시도 폭주 유발(가용성). 신뢰성 결함.
- **수정 방향(제안)**: ①선점 검사(`findByExternalJobId`)를 **저장 이전**에 수행해 위반을 회피하거나, ②충돌 처리를 `REQUIRES_NEW` 별도 트랜잭션 또는 서비스 바깥(재조회 전용 트랜잭션)으로 분리한다. ③테스트는 Testcontainers 기반 실 DB IT 로 교체(현 IT `AugmentDeidentConcurrencyIT` 는 서로 다른 otsd_job_id 케이스만 커버).

### [E-ISSUE-06] TC-AUG-032 / TC-AUG-036 — 증강 dead-letter·retry 가 프로덕션 도달 불가(테스트 위양성)
- **심각도**: MEDIUM
- **기대 동작**: 증강 처리가 영구 실패하면 `DEAD_LETTER_AT` 이 기록돼 잡 상태가 FAILED 로 집계되고, 재시도 횟수(`RTRY_NMTM`)가 누적돼야 한다.
- **현재 동작**: `LsDataAug.markDeadLetter()`(`LsDataAug.java:275-278`)·`incrementRetryCount()`(`:270-273`) 의 **호출자가 `main/` 전체에 0건**(grep: 정의부와 `LsDataMeta` 의 동명 메서드만 검출). 즉 증강 채널에는 재시도/데드레터 파이프라인 자체가 없다. 그 결과 `AugmentReviewService.java:244-247` 의 FAILED 분기와 `aggregateResultStatus` 의 `FAILED` 매핑은 **운영에서 도달 불가**. 관련 테스트(`listAll_그룹내_dead_letter_1건이면_status_FAILED`, `aggregateResultStatus_dead_letter_aug면_FAILED_반환`)는 픽스처가 `markDeadLetter()` 를 직접 호출해 GREEN 이 된다.
- **재현/확인 경로**: `grep -rn "markDeadLetter()\|incrementRetryCount()" backend/src/main/java` → 정의부만 / `select count(*) from public.ls_data_aug where dead_letter_at is not null;` → 0
- **영향**: 외부 증강 실패가 화면에 "실패"로 표시되지 않고 REQUESTED/IN_PROGRESS 로 영구 정체 → 운영 관측성 결함. E-ISSUE-02 와 결합 시 모든 증강 요청이 이 상태가 된다.
- **수정 방향(제안)**: 요청 후 무응답 감시(타임아웃 스케줄러) + 재시도 카운트 증가 + 임계 초과 시 dead-letter 마킹을 배치/스케줄러로 배선한다. 테스트는 그 배선 경로를 통해 상태가 만들어지는지로 검증한다.

### [E-ISSUE-07] TC-AUG-007/052 — AUGMENT 멱등 원장이 write-only (콜백에서 조회되지 않음)
- **심각도**: MEDIUM
- **기대 동작**: 요청 시 발급한 멱등 키(allowlist)가 콜백 수신 시 조회되어 무단/미발급 콜백을 2차 차단해야 한다(VLM 채널과 동일 패턴).
- **현재 동작**: `AugmentRequestBridge.java:50-51` 이 `ledger.recordIssued(...)` 로 AUGMENT 채널 키를 적재하지만, `AugmentResultService` 는 `WebhookIdempotencyLedger` 를 **주입조차 하지 않는다**(grep: 소비처는 `VlmResultService`·`VlmTimeseriesStep` 뿐). 콜백 DTO 에서 `idempotency_key` 필드도 제거됨(`AugmentResultRequest.java:21-23`). 실 DB 확인: `ls_webhook_idempotency` 의 AUGMENT 행 5건이 모두 `stts_cd='ISSUED'`·`aply_dt=NULL` 로 **영원히 소비되지 않음**.
- **재현/확인 경로**: `select idmp_key, chnl_cd, stts_cd, aply_dt from public.ls_webhook_idempotency where chnl_cd='AUGMENT';`
- **영향**: 콜백 인증이 HMAC 단일 계층에 전적으로 의존 → E-ISSUE-01 우회 시 남는 방어선이 0. 또한 원장 테이블이 무한 증가한다.
- **수정 방향(제안)**: 콜백에 발급 키를 되돌려받아 `lookupForProcessing`/`markProcessed` 로 게이팅하거나(VLM 패턴), 그럴 계획이 없다면 AUGMENT 채널의 `recordIssued` 를 제거해 사문화 상태를 없앤다.

### [E-ISSUE-08] TC-AUG-001/003/008 — 단건 요청 계약(@Size max=1) 과 다건 distinct·건별격리 로직/테스트케이스 불일치
- **심각도**: MEDIUM
- **기대 동작(테스트케이스 기준)**: 영상 N건 × 종류 3종을 한 요청으로 보내면 distinct 후 (영상×종류) PENDING 이 생성되고, 1건 실패는 격리된다.
- **현재 동작**: `AugmentRequestRequest.java:27-33` 이 `videoIds`·`types` 모두 `@Size(max=1)` 로 강제 → 2건 이상은 400. 따라서 `AugmentRequestService.java:99-103`(distinct)·`:126-138`(영상 루프)·`:133-137`(종류 루프)·`:154-175`(건별 격리)는 **API 경로에서 항상 1×1 로만 실행**되어 실질적 사문 코드다. 단위테스트 `distinctVideoIds`/`distinctTypes` 는 서비스를 직접 호출하므로 GREEN 이지만 실제 API 로는 도달 불가.
- **재현/확인 경로**: `curl -X POST …/v1/augments/request -d '{"videoIds":[13,13],"types":["WINTER"]}'` → 400 "영상은 한 번에 1건만 증강 요청할 수 있습니다."
- **영향**: 테스트케이스 카탈로그(TC-AUG-001/003/008)의 기대값이 현행 계약과 불일치 → 판정 왜곡. 코드 유지보수 부담(사문 분기).
- **수정 방향(제안)**: 계약을 정본으로 확정(단건 유지)한 뒤 테스트케이스 기대값을 단건 기준으로 갱신하고, 서비스의 다건 분기/단위테스트를 정리하거나 다건을 다시 허용한다.

### [E-ISSUE-09] TC-AUG-004/008 — 생성 0건이어도 200 성공 응답(silent no-op)
- **심각도**: MEDIUM
- **기대 동작**: 프레임 부재 스킵·건별 저장 실패로 PENDING 이 하나도 생성되지 않으면 호출자가 그 사실을 알 수 있어야 한다.
- **현재 동작**: `AugmentRequestService.java:126-145` 는 `createdCount` 를 로그에만 남기고, 응답 `AugmentRequestResponse(jobId, requestedAt, videoIds.size(), types.size())` 에는 **요청한 개수**를 그대로 담아 200 을 반환한다. 단건 계약(E-ISSUE-08)에서는 "요청 성공했으나 아무것도 생성되지 않음"이 곧 전체 실패인데도 성공으로 보인다.
- **재현/확인 경로**: 프레임 0건 APPROVED 영상으로 요청 → 로그 `no frame for video — skip rawSn=…` + `createdAugCount=0`, 응답은 200 `videoCount:1`
- **영향**: 운영자가 실패를 인지하지 못해 증강 누락이 조용히 발생.
- **수정 방향(제안)**: 응답에 `createdCount`/`skippedVideoIds` 를 포함하고, 생성 0건이면 4xx 또는 명시적 경고 필드로 구분한다.

### [E-ISSUE-10] TC-AUG-010 — 콜백 base URL 기본값이 자기 자신(localhost:8080)
- **심각도**: LOW
- **기대 동작**: 외부 시스템이 도달 가능한 주소가 콜백 URL 로 전달되어야 한다.
- **현재 동작**: `AugmentRequestService.java:77` `@Value("${authoring.webhook.callback-base-url:http://localhost:8080/api}")` 기본값이 컨테이너 내부 자기 자신을 가리키며, local 에서 override 되지 않는다(실측 로그 `callbackUrl=http://localhost:8080/api/v1/aug/callback`). 검증·경고 없음.
- **재현/확인 경로**: `docker logs klid-backend | grep callbackUrl`
- **영향**: 실 연동 시 외부가 도달할 수 없는 URL 을 전달해 콜백 유실(현재는 Noop 이라 증상이 드러나지 않음). 자족(self-referential) 설정이 기본값인 점은 self-fill 성 리스크.
- **수정 방향(제안)**: 기본값 제거(미설정 시 부팅 경고 또는 요청 차단) + `localhost/127.0.0.1` 기본값 사용 시 non-local 프로파일에서 부팅 실패 처리.

### [E-ISSUE-11] TC-AUG-056 — PII 게이트로 영상 생성이 보류돼도 aug 행은 ACCEPTED 종결 + applied:true
- **심각도**: MEDIUM
- **기대 동작**: 부모 비식별 미완으로 증강본 생성을 보류했다면, 그 증강 결과는 재처리 가능한 상태로 남거나 최소한 실패로 식별돼야 한다.
- **현재 동작**: `AugmentResultService.java:116-136` 이 상태 전이를 먼저 커밋한 뒤 `createAugmentedVideo` 안에서 게이트에 걸려 `return` 한다. 실측: aug#9 는 `AUG_PROC_STTS_CD='ACCEPTED'`, 응답 `{"applied":true}`, 그러나 `orgnl_raw_sn=5` 신규 영상 0건, 로그는 WARN 1줄뿐. 이후 이 행은 non-PENDING 이라 재콜백도 멱등 스킵(:100-105)되어 **영구 유실**된다. `originSrc`/`parentRaw` 미존재(:153-165)·부모 프레임 0건(:175-182) 경로도 동일하다.
- **재현/확인 경로**:
  ```sql
  select data_aug_sn, aug_proc_stts_cd from public.ls_data_aug where data_aug_sn=9;      -- ACCEPTED
  select count(*) from public.ls_data_raw where orgnl_raw_sn=5;                          -- 0
  ```
- **영향**: 증강 산출물 유실이 통계상 "완료(COMPLETED)"로 집계됨(잡카드 status). 운영 오판.
- **수정 방향(제안)**: 게이트 성립 시 상태를 종결시키지 말고 보류 상태(PENDING 유지 또는 별도 HELD 코드)로 남기고 응답을 `applied:false` + 사유로 회신, 부모 비식별 재완료 시 재처리 큐로 넘긴다.

### [E-ISSUE-12] TC-AUG-027/038/057/004 — 실동작 미재현 분기(정보)
- **심각도**: LOW
- **기대/현재**: 아래 분기는 현 환경 데이터·구현으로 재현 불가하여 정적 확인에 머물렀다.
  - `syncDecision` 예외 분기(TC-AUG-027): Noop 구현이 절대 throw 하지 않음 → 단위테스트도 부재
  - `cctvName "(이름 없음)" 폴백`(TC-AUG-038): 전 그룹 CCTV 매핑 존재
  - 부모 프레임 0건 보류(TC-AUG-057) / 요청 시 프레임 없는 영상 스킵(TC-AUG-004): APPROVED & 프레임 0건 데이터 부재
- **수정 방향(제안)**: 해당 픽스처를 시드에 추가하거나 실 DB IT 로 커버.

---

## A-ISSUE-13 (HMAC 우회) 실증 결과

| 항목 | 내용 |
|------|------|
| **가설** | `HmacWebhookFilter.shouldNotFilter` 가 raw URI 정확일치로 판정 → 퍼센트 인코딩 변형이 필터를 스킵하고 permitAll 컨트롤러에 도달 |
| **시도 요청 원문** | `POST /api/v1/%61ug/callback HTTP/1.1` · `Content-Type: application/json` · 서명/타임스탬프 헤더 **없음** · body `{"data_aug_sn":999999,"otsd_job_id":"probeA","aug_type_cd":"RAIN","aug_proc_sts_cd":"SUCCESS"}` |
| **응답** | `HTTP/1.1 404` · `{"success":false,"data":null,"message":"증강 행을 찾을 수 없습니다: dataAugSn=999999","errorCode":"NOT_FOUND"}` |
| **대조군(정규 경로)** | `POST /api/v1/aug/callback` (동일 body, 동일 무헤더) → `HTTP/1.1 401` · `WWW-Authenticate: HMAC` · `{"message":"Webhook 시크릿이 설정되지 않았습니다."}` |
| **필터 적용 여부** | **미적용(우회 성공)**. 백엔드 로그에 우회 요청에 대한 `[Webhook] …` 필터 로그가 전혀 남지 않음(정규 경로는 `secret missing path=/v1/aug/callback` 기록) |
| **다른 변형 결과** | `/v1/aug/callback/`(trailing slash) → 401(Spring Security), `/api//v1/aug/callback` → 401, `/v1/aug;x=1/callback` → 401, `/v1/AUG/callback` → 401 — **퍼센트 인코딩 변형만 관통** |
| **영향 실증** | 우회 경로로 무인증 상태에서 실 PENDING 행 4건(#8·#9·#10·#16)의 상태를 ACCEPTED/REJECTED 로 전이시키고, 신규 증강 영상 `ls_data_raw.raw_sn=29, 31`(임의 `raw_file_path_nm` 포함)을 생성 성공 |
| **기존 테스트** | `AugmentCallbackFlowIntegrationTest:390` `잘못된_서명_콜백은_401로_거부된다` 는 **정규 경로만** 검증. `common/security` 하위에 `HmacWebhookFilter` 테스트 자체가 없음(`HmacSignerTest` 만 존재), 인코딩 변형 테스트 0건 |
| **판정** | **확정(CONFIRMED) — CRITICAL**. A-ISSUE-13 은 이론적 가능성이 아니라 **재현 가능한 실동작 결함**이며, E-ISSUE-04(정상 경로 401 fail-closed)와 결합해 "정규 경로는 막히고 우회 경로만 열린" 상태다. E-ISSUE-01 로 등재 |

---

## 요약

- 총 **48건** / PASS **34** / FAIL **2** / PARTIAL **9** / BLOCKED **0** / N/A **0** / 확인필요 **3**
  - FAIL: TC-AUG-053(UNIQUE 멱등흡수 500), 그리고 **E-3 전 케이스에 걸친 인증 계층 결함**(E-ISSUE-01)은 케이스 단위가 아닌 클러스터 횡단 결함으로 별도 등재 — 케이스 표의 판정은 "인증 통과 가정 하의 비즈니스 로직"만 반영했다
  - PARTIAL: TC-AUG-001/003/008/027/032/035/036 + (004·038·057 은 확인필요)
- 근거 라인 드리프트: **0건** (표에 인용된 27개 `file:line` 전부 실 코드와 정합 — TC-AUG-001 의 `95-145` 만 실제 `94-145` 로 1행 오차, 무시 가능)
- **self-fill 결함: 3건**
  1. **E-ISSUE-02** — 외부 증강 클라이언트가 HTTP 호출 없이 `true` 를 자체 반환(`NoopExternalAugmentClient`), mock-server 인바운드 0건
  2. **E-ISSUE-03** — 자족 콜백 시뮬레이터(`DevAugmentCallbackSimulator`, 스스로 SUCCESS 를 만들어 자기 콜백으로 되쏘는 구조)가 yml 키 오중첩으로 비활성이나 **설계상 self-fill 경로가 코드베이스에 상주**
  3. **E-ISSUE-10** — 콜백 base URL 기본값이 자기 자신(`http://localhost:8080/api`)
- 테스트 위양성(테스트는 GREEN 인데 실경로 미검증/도달불가): **2건** — TC-AUG-053(Mockito 스텁으로 PG tx abort 미재현), TC-AUG-032(픽스처가 `markDeadLetter()` 직접 호출, 프로덕션 호출자 0)
- 변경된 데이터(참조용): `ls_data_aug` #8·9·10·14·15·16·17 신규/전이, `ls_data_raw` #29·#31 신규. **rawSn=26 은 조회조차 하지 않았고 상태 변경 없음.**

# E-part2 — 해상도 파생 오케스트레이션 / 예약·확정 (E-4 · E-5)

> 대상: `docs/test-cases/E-augment-resolution-export-meta.md` 66~128행 (TC-RESL-001~017, TC-RESL-030~065, 53건)
> 환경: 로컬 풀스택(klid-backend:18081 `/api`, klid-postgres, klid-ai-server, klid-mock-server, klid-frontend), DB 스키마 `public`
> 방식: 실제 API 호출(REVIEWER dev 토큰) + DB/파일시스템 실측 + 정적 대조 + 테스트 커버 확인
> 실동작 대상: 신규 파생 생성 12건(rawSn 30,32~39 / 부모 13), 기존 파생 실측(15,16,18,19 / 부모 14,17). **rawSn=26 은 미사용**(E-6 참조 데이터 보호)

## E-4. 해상도 파생 오케스트레이션 (VideoResolutionService / VideoController)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-RESL-001 | 프리셋 미지정 3종 전체 생성 | PASS | [실동작] `POST /v1/videos/13/resolution` 바디 생략 → 201, derivatives 3건 CREATED(rawSn 33/34/35), DB `ls_data_raw` 3행 `orgnl_raw_sn=13`·`PENDING`·`de_ident_yn='N'` INSERT 확인 | `VideoResolutionServiceTest`·`VideoResolutionControllerTest` "presets_미지정_바디시_기본_3종생성_201" | 응답 CREATED = **예약 성공**이며 비동기 확정 결과가 아님(E-ISSUE-24) |
| TC-RESL-002 | 프리셋 부분 지정 | PASS | [실동작] `{"presets":["RESL_480P"]}` → 1건(rawSn 32) / `["RESL_1080P","RESL_720P"]` → 2건(36/37) | "특정_프리셋_목록_지정시_그_목록만_생성된다" | — |
| TC-RESL-003 | presets 중복 제거 | PASS | [실동작] `["RESL_1080P","RESL_1080P"]` → derivatives 1건(rawSn 30). `ResolutionChangeRequest.java:32-37` distinct | 간접(서비스 테스트) | — |
| TC-RESL-004 | 원본 동일 해상도 프리셋 스킵 | PASS | [실동작] rawSn=17(실측 1920×1080) 3종 요청 → 로그 `preset skipped (same resolution) rawSn=17 preset=RESL_1080P 1920x1080`, 결과 목록에서 제외 | "원본과_동일_해상도_프리셋은_스킵된다" | 원본 해상도는 JPEG SOF0 파싱으로 실측 대조 |
| TC-RESL-005 | 전부 스킵 시 400 | PARTIAL | [정적] `VideoResolutionService.java:113-117` 확인. **실동작 미재현** — 3 프리셋과 모두 동일 해상도인 영상이 환경에 부재 | "모든_대상_프리셋이_원본과_동일해상도면_400_INVALID_INPUT" | 프리셋이 3종뿐이라 논리적으로 재현 불가에 가까움 |
| TC-RESL-006 | 전부 실패 시 500 | PASS | [실동작] rawSn=17 3종 → 500 `{"errorCode":"INTERNAL_ERROR","message":"요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."}`, 내부 사유 미노출. 로그 `all presets failed rawSn=17 attempted=2` | "모든_프리셋_생성이_실패하면_500이다" | — |
| TC-RESL-007 | 부분 실패 격리 201 | PASS | [실동작] 동시 2요청(A=[480P], B=[480P,1080P]) → B 응답 201 + `[{rawSn:null,goalResCd:"RESL_480P",status:"FAILED"},{rawSn:39,...,status:"CREATED"}]` 혼재 확인 | "한_프리셋_생성실패가_다른_프리셋_생성을_막지않는다" | 실패 항목 rawSn=null 계약 준수 |
| TC-RESL-008 | 업스케일 허용 | PASS | [실동작] rawSn=13(실측 1080×1920 세로) → RESL_1080P/720P 모두 거부 없이 예약. 로그 `src=1080x1920 target=1920x1080`(scaleX=1.78 업스케일) | "업스케일_프리셋도_400없이_정상_생성된다" | 종횡비 미보존 → E-ISSUE-26 |
| TC-RESL-009 | 증강본/파생본 거부 | PASS | [실동작] rawSn=19(파생, orgnlRawSn=17) → 400 "원본 영상에만 해상도 변경 가능" | "증강본_orgnlRawSn_null아님_원본은_거부된다" | — |
| TC-RESL-010 | 미검수 영상 거부 | PASS | [실동작] rawSn=27(ASSIGNED) → 409 "검수 완료(APPROVED)된 영상만 해상도 변경할 수 있습니다." | "미검수_영상_요청시_409" | — |
| TC-RESL-011 | 영상 미존재 | PASS | [실동작] rawSn=999999 → 404 | "영상_미존재_시_404" | — |
| TC-RESL-012 | 프레임 해상도 확인 불가 | PARTIAL | [정적] `VideoResolutionService.java:97-100`. **실동작 미재현** — `readDimensions` 가 0/음수를 반환하는 손상 이미지 주입 불가(파일 수정 금지) | "srcW_srcH가_0이면_파생생성이_거부된다"(mock 기반) | mock 반환값 테스트라 실제 이미지 디코더 경로는 미검증 |
| TC-RESL-013 | 프리셋 enum 화이트리스트 | PASS | [실동작] `{"presets":["RESL_240P"]}` → 400 "요청 본문이 올바르지 않습니다."(Jackson 역직렬화 거부) | "화이트리스트_외_preset_값_요청_400" | 근거 라인 드리프트 → E-ISSUE-28 |
| TC-RESL-014 | WORKER/미인증 차단 | PASS | [실동작] 토큰 없음 → 401 / WORKER 토큰 → 403 `FORBIDDEN`. `VideoController.java:286 @PreAuthorize("hasRole('REVIEWER')")` | "미인증_401","REVIEWER가_아니면_403이다" | — |
| TC-RESL-015 | 응답 형태 계약 | PASS | [실동작] `{"derivatives":[{"rawSn":32,"goalResCd":"RESL_480P","targetW":854,"targetH":480,"status":"CREATED"}]}` — 내부 파일경로·dataAugSn·EXPORT_SN 미노출 | "응답에_생성된_rawSn목록과_상태가_포함된다" | — |
| TC-RESL-016 | measureFirstFrame 경로 CWE-22 | PASS | [실동작] rawSn=5/6(프레임 경로가 `/Users/ck/...` = 컨테이너 base 밖) → 400 "원본 프레임 경로가 허용된 저장 경로를 벗어납니다." | "상위경로_traversal(..)_은_여전히_INVALID_INPUT으로_차단된다","raw도_deid도_아닌_경로는_INVALID_INPUT" | — |
| TC-RESL-017 | deid 프레임 경로 base 허용 | PASS | [실동작] rawSn=17 프레임 `de_idntf_src_file_path_nm=/app/storage/deidentified/frames/deid/17/frame-0.jpg`(deid base 절대경로)로 실측 통과 → 파생 생성 진행. `VideoResolutionService.java:195-201` 두 base 허용 | "비식별_프레임_경로는_deid_base로_통과하여_해상도변경이_성공한다" | — |

## E-5. 해상도 파생 예약/확정 (Reservation·Snapshot·Materialize·Persist·Runner)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-RESL-030 | 예약행 PENDING 커밋+새 RAW | PASS | [실동작] 로그 `derivative reserved parentRawSn=13 newRawSn=30 dataAugSn=13 preset=RESL_1080P` → 직후 `[AsyncResolutionRunner] starting ... rawSn=30`(AFTER_COMMIT). DB `ls_data_aug` PENDING 행 + `ls_data_raw` 신규행 확인 | `ResolutionReservationPersisterTest`, `ResolutionDerivativeFlowIntegrationTest` | — |
| TC-RESL-031 | 부모 잠금하 PII 게이트 | PARTIAL | [정적] `ResolutionReservationPersister.java:69-74`. **실동작 미재현** — APPROVED + `de_ident_yn≠'Y'` + base 내 프레임을 동시에 만족하는 영상 부재(5/6은 경로 가드가 선행 차단) | "부모가_비식별신고로_F전이되면_동기게이트에서_파생생성이_거부된다","비식별미완료_deIdntfYn_아님_부모면_예약단계에서_거부된다" | 단위/IT 커버 있음 |
| TC-RESL-032 | 대표프레임 SRC_SN null fail-fast | PASS | [정적] `:78-80` INSERT 이전 fail-fast | "srcSn_null이면_LS_DATA_AUG_INSERT전에_INVALID_INPUT을_던진다" | 상위 `firstFrame()` 이 항상 non-null 반환이라 방어적 |
| TC-RESL-033 | 부분유니크 중복 예약 차단 | PASS | [실동작] ①rawSn=17 720/480 재요청 → `DataIntegrityViolation`→CONFLICT(스택 `ResolutionReservationPersister.java:95`) ②동시 2요청 중 1건만 성공(A=500, B=201). DB 인덱스 실측 `uk_ls_data_aug_resl UNIQUE btree (src_sn, aug_type_cd) WHERE aug_type_cd ~~ 'RESL\_%'` (V125) | "동일_parent_preset_예약이_UK위반이면_CONFLICT로_거부된다","같은영상_같은프리셋_동시요청시_1건성공_1건CONFLICT" | — |
| TC-RESL-034 | 출력 경로 CWE-22 | PASS | [정적] `:122-128` 가드 존재. 단 `preset.name()` 은 enum 바인딩이라 traversal 문자 유입 **경로가 도달 불가**(방어적 잔존) | 전용 테스트 없음 | E-ISSUE-30 |
| TC-RESL-035 | Phase A 멱등 skip | PASS | [정적] `ResolutionSnapshotService.java:88-92` | IT "확정된_파생을_PhaseA가_멱등skip하고_프레임이_중복생성되지_않는다" | — |
| TC-RESL-036 | Phase A 부모 PII 재검증 | PASS | [정적] `:94-106` 부모 `findByRawSnForUpdate` + `'Y'` 재검증 → CONFLICT | IT "예약후_async확정전에_부모가_비식별신고로_F전이되면 ... 파생이_FAILED된다" | — |
| TC-RESL-037 | Phase A 비식별 비디오 경로 부재 | PARTIAL | [정적] `:126-130` NOT_FOUND. 실동작 미재현(대상 부모 전부 SUCCESS procLog 보유) | **전용 테스트 미발견** | E-ISSUE-29 |
| TC-RESL-038 | Phase A 프레임 0건 fail-fast | PASS | [정적] `:137-140` | "부모_프레임이_0건이면_fail_fast로_거부한다(#9)" | — |
| TC-RESL-039 | Phase A 중복 videoFrameNo fail-fast | PARTIAL | [정적] `:166-171` `seenFrameKeys` 중복 감지 | **전용 테스트 미발견** | E-ISSUE-29 |
| TC-RESL-040 | Phase A deid 프레임 경로 strict | PASS | [실동작] rawSn=13(프레임 11건 전부 `de_idntf_src_file_path_nm` NULL) 파생 요청 → Phase A `deidFrameSourceStrict` CONFLICT → Phase B 미실행(파일 0건 생성), 파생 RAW `de_ident_yn='N'`·`FAILED` 유지. **원본 픽셀 복제 + 'Y' 위장 차단 실증** | 전용 DisplayName 미발견(실동작으로 대체 검증) | 강한 실증 |
| TC-RESL-041 | Phase B 비디오 복사+프레임 리스케일 | PARTIAL | [실동작] rawSn=18/19(부모 17) — 비디오 1개 + 프레임 12개 산출, JPEG SOF0 파싱 실측 `18=1280×720`, `19=854×480`(목표 정확 일치). **단 산출물이 deid base 가 아닌 raw base(`/app/storage/raw/resolution/...`) 아래 생성** → E-ISSUE-21 | `ResolutionFileMaterializerTest` | 기능 정상 / 저장 위치 결함 |
| TC-RESL-042 | Phase B 원본 비식별 파일 부재 | PASS | [정적] `ResolutionFileMaterializer.java:49-51` NOT_FOUND + `finally` 게이트 반환 | "비식별_비디오원본이_없으면_NOT_FOUND로_실패하고_게이트를_반환한다" | — |
| TC-RESL-043 | Phase B 리사이즈 게이트(DoS) | PARTIAL | [정적] `ResizeConcurrencyGate` — `Semaphore(fair)`, `resize-max-concurrent:2`, `tryAcquire(5s)` 초과 시 429 TOO_MANY_REQUESTS. **실동작 동시부하 미재현** | acquire/release 호출 검증만(슬롯 포화·429 경로 미검증) | 초과 시 "대기 후 거부" 동작 자체는 미실증 |
| TC-RESL-044 | Phase C 확정 영속 | PARTIAL | [실동작] rawSn=18/19 — `ls_data_src` 12행 INSERT, `ls_data_aug` ACCEPTED 전이, `ls_data_raw.de_ident_yn='Y'`·`data_stts_cd='COMPLETED'`, `ls_deident_proc_log` SUCCEEDED 생성 전부 확인. **단 procLog `DE_IDNTF_FILE_PATH_NM`·`LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM` 에 raw base 경로가 기록** → E-ISSUE-21 | IT "해상도파생_finalize성공시_파생RAW_배치상태_COMPLETED_이고 ..." | 메타복사는 18/19 생성 시점 이후 커밋(27b6bb0d)이라 미실증 |
| TC-RESL-045 | 좌표 배율 재계산 라벨 복사 | PASS | [실동작] 부모17 라벨 13건 → 18/19 각 13건 복사. 실측 대조: `619.6822→413.1215`(×0.66667=1280/1920), `507.7863→338.5242`(×0.66667), 480P `→275.6295`(×0.444792=854/1920)·`→225.6828`(×0.44444). `ls_data_aug_lbl_map` `coord_recalc_yn='Y'`, `scale_x/scale_y=0.666667` 적재 확인 | `LabelCoordinateScalerTest` 21케이스(BBOX/POLYGON/SEGMENTATION/SKELETON 삼중값·업스케일·기형 JSON 거부) | 라벨 유형별은 단위테스트 커버, 실동작은 BBOX만 |
| TC-RESL-046 | 부모 라벨 0건 | PASS | [정적] `ResolutionPersistService.java:350-353` early return 0 | 전용 테스트 미발견(로직 자명) | — |
| TC-RESL-047 | Phase C stale PII 게이트 — 재신고 | PASS | [정적] `:286-294` capturedAt 이후 신고 존재 시 CONFLICT | IT "진짜_A~C창_PhaseB가_실제파일산출_후_스냅샷이후_부모_재비식별신고시_PhaseC가_CONFLICT하고_cleanup이_실제파일을_삭제하며 ..." | 실파일 산출까지 검증하는 강한 IT |
| TC-RESL-048 | Phase C stale — 비식별 경로 변경 | PASS | [정적] `:296-309` | "stale창_최신비식별procLog경로가_스냅샷과_다르면_CONFLICT로_abort한다(H-1_①경로게이트)" | — |
| TC-RESL-049 | Phase C stale — 파일 mtime 교체 | PARTIAL | [정적] `:311-321`. `IOException` 시 **보수적 통과(페일오픈)** — 로그만 남기고 진행 | **mtime 경로 전용 테스트 미발견** | E-ISSUE-29 |
| TC-RESL-050 | Phase C 부모 재잠금 PII 최종게이트 | PASS | [정적] `:96-105` parent→newRaw 잠금 순서 고정 | "부모가_비식별미완료(F)면_PII최종게이트에서_CONFLICT로_abort한다" | — |
| TC-RESL-051 | 중복 finalize CAS skip | PASS | [정적] `:116-122` newRaw 재잠금 + `'Y'` CAS → SKIPPED | IT "같은_파생RAW를_2스레드가_동시_finalize해도_프레임은_1회만_삽입된다(#5_승자보호)" | — |
| TC-RESL-052 | 프레임 개인정보 3필드 복사 | PASS | [실동작] 부모17 12프레임 `anony/psdo/prvc_incl_yn` 전부 NULL → 파생 18/19 12프레임 전부 NULL(부모 null→파생 null 계약 준수) | "부모프레임_개인정보3필드가_해상도파생_프레임에_복사되어_INSERT된다" | 값이 채워진 부모 실동작은 미재현 |
| TC-RESL-053 | 예약 aug 슬롯 해제 | PASS | [실동작] 로그 `reserved aug slot released dataAugSn=13 augTypeCd=RESL_1080P`(및 17). DB `ls_data_aug` 에서 RESL 예약행 삭제 확인 + **동일 프리셋 재요청이 다시 201 성공**(락아웃 해소 실증) | "finalize_transient실패시_예약aug행이_삭제되고_새RAW는_FAILED이며_동일프리셋_재시도가_성공한다" | — |
| TC-RESL-054 | 슬롯 해제 방어 — 라벨맵 참조 시 미삭제 | PASS | [정적] `:208-213` | "releaseReservedAug_라벨맵이_참조중이면_삭제하지않는다(승자참조_보호)" | — |
| TC-RESL-055 | 슬롯 해제 방어 — 비-RESL 미삭제 | PASS | [정적] `:214-224` `RESL_` 접두 확인 | "releaseReservedAug_RESL_접두가_아니면_삭제하지않는다(오배송_방어)" | — |
| TC-RESL-056 | isAlreadyFinalized FOR UPDATE 판정 | PASS | [정적] `:181-190` `findByRawSnForUpdate` + `'Y' \|\| COMPLETED` | "isAlreadyFinalized_deIdntfYn_Y면_true_COMPLETED면_true_그외_false다" | 코드 주석이 잔여 경합(승자-뒤짐·락 타임아웃 페일오픈)을 정직하게 명시 |
| TC-RESL-057 | 러너 A→B→C 정상 완주 | PASS | [실동작] rawSn=18/19 완주 결과(프레임·라벨·aug ACCEPTED·procLog·COMPLETED) 실측 | "정상확정시_A_B_C를_순차호출하고_FAILED전이나_정리를_하지않는다" | 현 세션에서의 신규 완주는 조건 부재로 미재현 |
| TC-RESL-058 | 러너 snapshot empty skip | PASS | [정적] `AsyncResolutionRunner.java:62-66` | "PhaseA가_멱등skip이면_B_C_정리_전이_모두_수행하지않는다" | — |
| TC-RESL-059 | 러너 persist SKIPPED — 파일 미정리 | PASS | [정적] `:73-79` | "PhaseC가_SKIPPED_중복finalize패자면_정리도_FAILED전이도_하지않는다" | — |
| TC-RESL-060 | 러너 실패 정리 — 승자 보호 선점검 | PASS | [정적] `:98-113` cleanup 이전에 `isAlreadyFinalized` 선점검 | "실패했지만_이미_승자가_확정(Y)했으면_cleanup도_FAILED전이도_aug해제도_스킵한다(M-1_승자산출물보호)" | — |
| TC-RESL-061 | 러너 실패 정리 — cleanup+슬롯해제+FAILED | PASS | [실동작] 파생 30/32/33~39 전건: `releaseReservedAug` 로그 + `ls_data_raw.data_stts_cd='FAILED'`·`de_ident_yn='N'` 전이 확인. Phase A 실패라 cleanup 은 스냅샷 부재로 정상 생략(파일 0건, `resolution/13` 디렉토리 미생성 확인) | "PhaseA_예외시_스냅샷이없어_cleanup은_생략하고_예약aug해제_및_FAILED전이한다","PhaseB_실패시_아티팩트정리후 ..." | — |
| TC-RESL-062 | cleanup 원본 미삭제 보장 | PASS | [정적] `ResolutionFileMaterializer.java:76-110` 삭제 대상은 `resolution/{newRawSn}/` + `videoDst` 만. 파생 id 와 부모 id 는 상호배타(파생은 부모가 될 수 없음)라 네임스페이스 충돌 발생 불가 | "cleanup은_파생_비디오와_프레임디렉토리를_삭제하고_무관파일은_보존하며_true를_반환한다" | 다만 비디오/프레임 디렉토리 스코프 비대칭 → E-ISSUE-27 |
| TC-RESL-063 | @Async 예외 삼킴 | PASS | [실동작] 파생 30/32~39 비동기 실패가 API 응답(201)·서버 기동에 전파되지 않고 WARN 로그로만 종결 | "예약aug_해제가_예외를_던져도_FAILED전이는_수행된다" | — |
| TC-RESL-064 | createResolutionPending RESL_ 접두 강제 | PASS | [정적] `LsDataAug.buildResolution` `RESL_PREFIX` 미충족 시 INVALID_INPUT | 간접 | — |
| TC-RESL-065 | markResolutionGenerated 이중전이 차단 | PASS | [정적] `LsDataAug.markResolutionGenerated` `RESL_` + `PENDING` 가드, 비-PENDING 시 CONFLICT | 간접(IT 상태전이) | — |

---

## ★ 해상도 파생 raw base 뿌리 — 증상 전수 조사 결과

**뿌리**: 파생 산출물의 **출력 base 를 raw base 로만 강제**하는 설계.
`ResolutionReservationPersister.java:82-84`(비디오 목적지) · `ResolutionSnapshotService.java:177-178`(프레임 목적지) · 동 `resolveSafeFile/resolveSafeDir`(출력은 raw base 만 허용, 주석 227행 "파생 산출물은 raw base 하위에만 쓴다") · `ResolutionFileMaterializer.java:80`(cleanup base).
그 결과 **비식별 산출물이 비식별 저장소 밖(raw 저장소)에 존재**하며, 이를 참조하는 하위 시스템마다 fail-closed/fail-open 이 갈린다.

| # | 증상 | 실측 근거 | 심각도 | 관련 기확정 이슈 |
|--:|------|----------|:--:|------|
| 1 | **파생 비디오 파일이 raw base 아래 생성** | `docker exec ls -R /app/storage/raw/resolution` → `/app/storage/raw/resolution/14\|17/{RESL_720P,RESL_480P}/video/*.mp4`. deid base(`/app/storage/deidentified`)에는 `frames`·`videos` 만 있고 resolution 없음 | HIGH | B-ISSUE-61 |
| 2 | **파생 프레임 이미지셋이 raw base 아래 생성** | `/app/storage/raw/resolution/{15,16,18,19}/frames/frame-*.jpg` 각 12장 | HIGH | D-ISSUE-46 |
| 3 | `LS_DATA_RAW.RAW_FILE_PATH_NM` = raw base 파생 비디오 경로 | `SELECT raw_file_path_nm ... raw_sn IN (15,16,18,19)` → `/app/storage/raw/resolution/{14,17}/{preset}/video/{preset}.mp4` | HIGH | B-61 |
| 4 | `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`(파생) = raw base 경로 | `SELECT data_raw_sn, de_idntf_file_path_nm FROM ls_deident_proc_log` → 15/16/18/19 전부 `/app/storage/raw/resolution/...`. 일반 영상(7,11~14,17,23~28)은 `/app/storage/deidentified/videos/...` | HIGH | B-61 |
| 5 | **파생 영상 스트리밍 전면 403** | [실동작] `GET /v1/videos/18\|19/stream` → **403** `{"message":"허용되지 않은 영상 경로입니다.","errorCode":"FORBIDDEN"}` / 부모 17 → 206. 원인: `VideoStreamService.resolveStreamMeta` 가 `baseDir=deidentifiedPath` 로만 `resolveSafe` → FORBIDDEN | HIGH | B-ISSUE-61 |
| 6 | `LS_DATA_SRC.SRC_FILE_PATH_NM` == `DE_IDNTF_SRC_FILE_PATH_NM` (동일 raw base 경로) | `ResolutionPersistService.java:243-247` `LsDataSrc.create(..., dst, dst, ...)` — 같은 값을 원본·비식별 두 컬럼에 저장. DB 실측: raw 15/16/18/19 각 12행 두 컬럼 동일 | HIGH | D-ISSUE-46 |
| 7 | **데이터마트 뷰 `V_COMPLETED_FRAME` 오노출** | [실동작] `SELECT raw_sn, count(*), sum(original_path=deidentified_path)` → **rawSn=19: 12행 중 12행이 ORIGINAL==DEIDENTIFIED**. 다른 영상(14,17,26)은 0. 관제는 "원본 경로"로 비식별 산출물을, "비식별 경로"로 raw base 파일을 받는다 | HIGH | D-ISSUE-46 |
| 8 | **`FrameSource` 가 DEIDENTIFIED 에 rawBase 폴백을 상시 허용** | `dataset/export/FrameSource.java:68-75` — `candidateBases = kind==ORIGINAL ? {rawBase} : {deidBase, rawBase}`, 주석에 "해상도 파생 = ResolutionPersistService 가 파생 비식별을 raw base 에 기록" 명시. **해상도 파생 우회용 예외가 전 영상 export 에 적용**되어 PII 격리가 fail-open | HIGH | (신규, D-46 확장) |
| 9 | 파생 export 2벌이 동일 소스의 중복 | [실동작] `/app/storage/labeling/19/v2\|v3` 각 `orgnl=24, deid=24` 파일. 파생은 원본(비-비식별) 프레임이 애초에 존재하지 않아 ORIGINAL 벌이 사실상 비식별본 사본 | MED | D-46 |
| 10 | CLAUDE.md 데이터마트 계약 위반 | 문서: "신규 추출은 `{base}/frames/raw\|deid/{rawSn}` 로 분기 저장돼 **두 경로가 항상 상이**(원본 덮어쓰기 0)" — 파생 경로가 이 불변식을 깬다 | MED | D-46 |
| 11 | 비디오/프레임 디렉토리 스코프 비대칭 | 비디오=`resolution/{parentRawSn}/{preset}/video/`, 프레임=`resolution/{newRawSn}/frames/`. cleanup 은 `resolution/{newRawSn}` + `videoDst` 만 삭제 → 실패 후 빈 부모 디렉토리 잔존(`resolution/14`,`17` 실측) | LOW | (신규) |
| 12 | 프레임 이미지 서빙은 **통과** (비대칭) | [실동작] `GET /v1/frames/420/image`(파생 19 프레임) → **200 image/jpeg 7107B**. `FrameImageController` 는 raw base 로만 검증하므로 파생이 우연히 통과. 스트리밍(403)과 서빙(200)이 엇갈림 | MED | B-61 |

**통합 수정 방향(제안 — 구현 금지)**

- B-61(파생 스트리밍 403, fail-closed)과 D-46(마트 비식별 경로 오노출, fail-open)은 **같은 뿌리의 앞뒤 면**이다. B-61 을 "`VideoStreamService` 가 raw base 도 허용"으로 고치면 파생·비파생 구분 없이 raw base 비디오가 비식별 스트림으로 서빙돼 D-46 이 확대된다. 반대로 D-46 만 "뷰에서 파생 제외"로 막으면 파생이 마트에서 사라져 요구(파생도 마트 대상)를 어긴다.
- **정공법: 파생 산출물의 출력 base 를 deid base 하위로 이동**한다.
  1. `ResolutionReservationPersister.java:82-84` 의 `base` 를 `storageDeidentifiedPath` 로, 경로를 `deid base + videos/{newRawSn}/...`(일반 영상 규약과 동일)로 변경.
  2. `ResolutionSnapshotService.buildFrameSpecs` 의 `fdst` 를 `deid base + frames/deid/{newRawSn}/`(일반 영상 규약과 동일)로 변경하고 `resolveSafeFile/resolveSafeDir` 의 출력 base 를 deid base 로 교체.
  3. `ResolutionPersistService.insertFrames` 의 `LsDataSrc.create(..., dst, dst, ...)` 를 분리 — `SRC_FILE_PATH_NM` 은 (파생에 원본이 없으므로) **NULL 또는 명시적 파생 원본 정책**을 정하고, `DE_IDNTF_SRC_FILE_PATH_NM` 만 deid 경로로 채운다. 두 컬럼 동일 저장은 금지.
  4. `ResolutionFileMaterializer.cleanup` 의 base 도 동반 이동(현 raw base 삭제 로직이 새 경로를 못 지움 → 고아 파일 회귀 위험).
  5. `FrameSource.java:73-75` 의 **DEIDENTIFIED rawBase 폴백을 제거**(1~3 이 선행돼야 안전). 제거하지 않으면 PII 격리가 계속 fail-open.
  6. `VideoStreamService` 는 **손대지 않는다**(deid base 강제 유지가 정답). 1~4 완료 시 파생 스트리밍 403 이 자동 해소된다.
  7. 기존 파생(15,16,18,19)은 파일 이동 + `LS_DATA_RAW.RAW_FILE_PATH_NM`·`LS_DATA_SRC` 2컬럼·`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 백필 마이그레이션 필요.
  8. 회귀 테스트 추가: **확정 후(deIdntfYn='Y') 파생 스트리밍 200** + **`V_COMPLETED_FRAME` 에서 ORIGINAL≠DEIDENTIFIED** 2건. 현 테스트는 "확정 **전** N 상태 스트리밍 NOT_FOUND"만 검증해 이 결함을 통과시킨다.

---

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [E-ISSUE-21] TC-RESL-041 / TC-RESL-044 — 해상도 파생 산출물이 비식별 저장소(deid base) 밖 raw base 에 생성·기록됨
- **심각도**: HIGH (보안 CWE-359 정보 격리 / 데이터 무결성)
- **기대 동작**: 파생 비디오·프레임은 비식별 산출물이므로 `STORAGE_DEIDENTIFIED_PATH` 하위(`videos/{rawSn}/`, `frames/deid/{rawSn}/`)에 생성되고, `DE_IDNTF_*` 컬럼에는 deid base 경로가, `SRC_FILE_PATH_NM` 에는 원본 경로가(파생은 원본 부재 → 별도 정책) 기록되어 마트·스트리밍·export 가 일관되게 동작해야 한다.
- **현재 동작**: `ResolutionReservationPersister.java:82-84`(비디오), `ResolutionSnapshotService.java:177-178` + `:244-254 resolveSafeFile`(프레임)이 출력 base 를 raw base 로만 강제. `ResolutionPersistService.java:243-247` 이 같은 경로를 `SRC_FILE_PATH_NM`·`DE_IDNTF_SRC_FILE_PATH_NM` 두 컬럼에 동일 저장. `:143-146` 이 procLog `DE_IDNTF_FILE_PATH_NM` 에도 raw base 경로 기록.
  실측: `/app/storage/raw/resolution/{15,16,18,19}/frames/*.jpg`(각 12장), `/app/storage/raw/resolution/{14,17}/{preset}/video/*.mp4`, `ls_deident_proc_log` 15/16/18/19 전부 raw base.
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT raw_sn, count(*), sum(CASE WHEN original_path=deidentified_path THEN 1 ELSE 0 END) FROM v_completed_frame GROUP BY raw_sn"` → rawSn=19 가 12/12. / `curl -H 'Authorization: Bearer <REVIEWER>' localhost:18081/api/v1/videos/19/stream` → 403.
- **영향**: ①파생 영상 재생 전면 불가(B-61) ②관제 데이터마트가 raw 저장소 경로를 "비식별 경로"로 수신(D-46) ③디렉토리 단위 접근제어·보존·백업 정책이 파생 비식별본을 raw 로 취급 ④`FrameSource` 우회 폴백을 강제해 전 영상 PII 격리 약화(E-ISSUE-22). CWE-359(Privacy Violation) / CWE-668(Exposure of Resource to Wrong Sphere).
- **수정 방향(제안)**: 위 "통합 수정 방향" 1~8. **B-61 을 raw base 허용으로 고치면 안 됨**(D-46 확대).

### [E-ISSUE-22] TC-RESL-041 파생 — `FrameSource` 의 DEIDENTIFIED rawBase 폴백이 전 영상 PII 격리를 fail-open 으로 만듦
- **심각도**: HIGH (보안 CWE-359)
- **기대 동작**: export 의 DEIDENTIFIED 벌은 deid base 하위 파일만 허용해야 한다(ORIGINAL 이 rawBase 단일 강제인 것과 대칭).
- **현재 동작**: `backend/src/main/java/kr/co/cudo/authoring/dataset/export/FrameSource.java:68-75`
  `Path[] candidateBases = (kind == ExportKind.ORIGINAL) ? new Path[]{rawBase} : new Path[]{deidBase, rawBase};`
  주석(69-71행)에 "해상도 파생 = ResolutionPersistService 가 파생 비식별을 raw base 에 기록"이라고 **우회 목적이 명시**되어 있다. 즉 해상도 파생 하나를 살리려고 **모든 영상**의 DEIDENTIFIED export 가 raw base 파일을 수용하게 됐다.
- **재현/확인 경로**: 임의 영상의 `LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM` 이 raw base 를 가리키도록 오염돼도 export 가 이를 "비식별본"으로 기록한다(정적).
- **영향**: 비식별 미적용 원본 프레임이 DEIDENTIFIED export 벌에 섞여 관제/데이터마트로 유출될 수 있는 경로가 상시 열림.
- **수정 방향(제안)**: E-ISSUE-21 의 1~4 선행 후 `candidateBases` 를 `{deidBase}` 단일로 되돌린다. 단독 제거 시 기존 파생 export 가 즉시 PARTIAL 로 회귀하므로 **반드시 경로 이동·백필과 동일 릴리스**여야 한다.

### [E-ISSUE-23] TC-RESL-061 — 파생 확정 실패 시 `LS_DATA_RAW` 고아 행이 영구 잔존·무한 누적
- **심각도**: MEDIUM (데이터 위생)
- **기대 동작**: 확정 실패한 파생은 예약 aug 슬롯 해제와 함께 사용자/운영자가 상태를 인지하거나 정리할 수 있어야 한다.
- **현재 동작**: `AsyncResolutionRunner.handleFailure` 는 파일 cleanup + aug 삭제 + `markRawDataFailed` 만 수행하고 `LS_DATA_RAW` 행은 남긴다(`ResolutionPersistService`/`AsyncResolutionRunner` 전체에 파생 RAW 삭제 경로 없음). 실측: 부모 13 에 대한 FAILED 파생 RAW 가 **12건**(20,21,22,30,32~39) 누적. `GET /v1/videos` 는 파생을 제외하므로 화면·증강 이력 어디에도 노출되지 않는 **침묵 쓰레기**.
- **재현/확인 경로**: `POST /v1/videos/13/resolution` 반복 → `SELECT orgnl_raw_sn, data_stts_cd, count(*) FROM ls_data_raw WHERE orgnl_raw_sn IS NOT NULL AND raw_file_path_nm LIKE '%resolution%' GROUP BY 1,2`
- **영향**: 재시도마다 RAW 행 증가(파일은 미생성), 통계·마이그레이션·감사 시 노이즈. RAW_SN 시퀀스 소모.
- **수정 방향(제안)**: ①실패 파생 RAW 를 soft-delete/삭제하거나 ②FAILED 파생을 조회 가능한 운영 화면·API 에 노출하고 수동 정리 제공. ③최소한 동일 (부모,프리셋) 재요청 시 기존 FAILED 파생 RAW 를 재사용하도록 변경.

### [E-ISSUE-24] TC-RESL-001/007 — 201 `CREATED` 가 "예약 성공"만 의미하며 비동기 확정 실패를 알 방법이 없음
- **심각도**: MEDIUM (기능/UX)
- **기대 동작**: 사용자가 파생 생성 성공/실패를 확인할 수 있어야 한다.
- **현재 동작**: [실동작] `POST /v1/videos/13/resolution` → 201 `derivatives:[{rawSn:33,status:"CREATED"},...]` 반환. 그러나 ~20ms 뒤 3건 전부 Phase A CONFLICT 로 FAILED 전이. 응답은 CREATED 그대로이고, 파생 RAW 는 `GET /v1/videos` 에서 제외, 예약 aug 는 삭제되어 `GET /v1/augments` 의 `resolutionTypes` 에도 미노출 → **어느 화면에서도 실패를 볼 수 없다**.
- **재현/확인 경로**: 위 요청 후 `SELECT raw_sn,data_stts_cd FROM ls_data_raw WHERE raw_sn IN (33,34,35)` → 전부 FAILED.
- **영향**: REVIEWER 가 파생이 생성됐다고 오인. 부모 13 처럼 비식별 프레임이 없는 영상은 매번 조용히 실패.
- **수정 방향(제안)**: ①응답 상태값을 `RESERVED`/`ACCEPTED` 로 명확화 ②파생 상태 조회 API 또는 증강 이력에 FAILED 파생 노출 ③가능하면 동기 단계에서 "부모 비식별 프레임 보유" 선검증을 추가해 예약 전에 400 으로 거부.

### [E-ISSUE-25] TC-RESL-001 — 파생 `VMS_CLIP_ID` 의 `RESL_RESL_` 이중 접두 드리프트
- **심각도**: LOW (데이터 품질)
- **기대 동작**: 파생 식별자에 프리셋 코드가 1회만 들어간다.
- **현재 동작**: `LsDataRaw.createFromResolution` (`LsDataRaw.java:226`) `vmsClipId = parent + "_RESL_" + goalResCd + "_" + millis` 이고 `goalResCd` 자체가 `RESL_720P` → 실측 `test-1784791814270_RESL_RESL_720P_1784792022000`.
- **재현/확인 경로**: `SELECT vms_clip_id FROM ls_data_raw WHERE orgnl_raw_sn IS NOT NULL`
- **영향**: 파생 종류를 `VMS_CLIP_ID` 마커 파싱으로 식별하는 코드/화면의 드리프트(기존 메모 `reviewpage-augmented-list-facts` 의 `RESL_RESL`/`RES_RES` 이슈와 동일 뿌리).
- **수정 방향(제안)**: 접두를 `"_"` 로 바꾸거나 파생 종류를 `LS_DATA_AUG.AUG_TYPE_CD` 조인으로만 판별(문자열 파싱 폐지).

### [E-ISSUE-26] TC-RESL-008 — 종횡비 보존 미구현(Javadoc 과 실제 불일치), 비-16:9 원본이 강제 왜곡됨
- **심각도**: MEDIUM (기능)
- **기대 동작**: `ResolutionPreset` Javadoc — "실제 다운스케일은 원본 종횡비를 보존하므로 목표 세로(height)를 기준으로 비율을 산정하고 가로는 종횡비에 맞춰 계산한다."
- **현재 동작**: `ResolutionSnapshotService.java:111-121` 이 `targetW=preset.width()`, `targetH=preset.height()` 를 그대로 쓰고 `ResolutionFileMaterializer.java:56` 이 `imageResizer.resize(src, dst, targetW, targetH)` 로 **고정 W×H 강제 스케일**. [실동작] 부모 13(1080×1920 세로) → RESL_1080P 요청 시 로그 `src=1080x1920 target=1920x1080` (scaleX=1.778, scaleY=0.5625) — 세로 영상이 가로로 눌린다. 라벨 좌표도 동일 배율로 왜곡 복사.
- **재현/확인 경로**: `POST /v1/videos/13/resolution` 로그 확인.
- **영향**: 세로/비표준 종횡비 원본의 파생 영상·라벨이 왜곡된 학습데이터로 산출. 문서·주석과 구현 불일치(감리 지적 소지).
- **수정 방향(제안)**: 정책 확정 필요 — ①Javadoc 대로 종횡비 보존(목표 높이 기준, 가로는 계산)으로 구현 정정하거나 ②정책이 고정 W×H 라면 Javadoc·설계서를 실제에 맞게 고치고 비-16:9 원본 처리(레터박스/거부) 규칙을 명시.

### [E-ISSUE-27] TC-RESL-062 — 파생 비디오/프레임 저장 스코프 비대칭 + 빈 부모 디렉토리 잔존
- **심각도**: LOW (설계 견고성)
- **기대 동작**: 한 파생의 모든 산출물이 파생 스코프 한 디렉토리에 모여 cleanup 1회로 완전 정리된다.
- **현재 동작**: 비디오=`resolution/{parentRawSn}/{preset}/video/{preset}.mp4`(`ResolutionReservationPersister.java:83-84`), 프레임=`resolution/{newRawSn}/frames/`(`ResolutionSnapshotService.java:177-178`). `cleanup(newRawSn, videoDst)` 은 프레임 디렉토리와 비디오 파일만 지우고 `resolution/{parentRawSn}/{preset}/video/` 빈 디렉토리는 남는다(실측 `resolution/14`, `resolution/17`).
- **재현/확인 경로**: `docker exec klid-backend ls -R /app/storage/raw/resolution`
- **영향**: 현재 id 상호배타성(파생은 부모가 될 수 없음) 덕분에 삭제 충돌은 없으나, 규약 변경 시 교차 삭제 위험. 빈 디렉토리 누적.
- **수정 방향(제안)**: E-ISSUE-21 경로 이동 시 비디오·프레임 모두 `{deid base}/resolution/{newRawSn}/` 단일 스코프로 통일.

### [E-ISSUE-28] TC-RESL-013 — 근거 라인 드리프트 (`ResolutionPreset.java:7-8`)
- **심각도**: LOW (문서)
- **기대 동작**: 케이스 근거가 실제 enum 정의 위치를 가리킨다.
- **현재 동작**: 테스트케이스 근거 `ResolutionPreset.java:7-8` 은 클래스 Javadoc. 실제 화이트리스트 enum 상수는 **14~16행**(`RESL_1080P(1920,1080)` 등).
- **영향**: 근거 추적 오류. (E-4/E-5 범위 다른 44개 근거는 전부 정합 — 드리프트 1건)
- **수정 방향(제안)**: 근거를 `ResolutionPreset.java:14-16` 으로 정정.

### [E-ISSUE-29] TC-RESL-037/039/049 — 확정 게이트 3종 전용 테스트 부재 + "테스트 GREEN = 안전" 착각 지점
- **심각도**: MEDIUM (검증 신뢰도)
- **기대 동작**: 보안·정합 게이트마다 실행 경로를 검증하는 테스트가 있어야 한다.
- **현재 동작**: backend 3013 테스트 전건 GREEN(`_raw/test-baseline.md`)임에도 —
  ①`ResolutionSnapshotService.java:126-130`(비식별 비디오 procLog 부재 404) ②동 `:166-171`(중복 videoFrameNo fail-fast) ③`ResolutionPersistService.java:311-321`(mtime 교체 게이트, `IOException` 시 **페일오픈 통과**) 3건에 대응하는 `@DisplayName` 이 `Resolution*Test`/`*IT` 전체에 없다.
  더 중요한 것은 **확정 후 파생 스트리밍**을 검증하는 테스트가 없다는 점이다 — 존재하는 테스트는 "확정**전**_파생RAW는_deIdntfYn_N이라_스트리밍이_NOT_FOUND로_거부된다(#2_PII_TOCTOU)" 뿐이라, 확정 후 403(E-ISSUE-21 증상5)이 전 테스트를 통과한다. 마찬가지로 어떤 테스트도 산출물 base 가 deid base 인지 단언하지 않고 raw base 를 기대값으로 고정하고 있다(`ResolutionFileMaterializerTest`).
- **재현/확인 경로**: `grep -h "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/video/Resolution*.java`
- **영향**: 결함이 CI 를 통과. 회귀 감지 불가.
- **수정 방향(제안)**: ①위 3게이트 단위 테스트 추가 ②`deIdntfYn='Y'` 확정 후 파생 스트리밍 200 IT 추가 ③`V_COMPLETED_FRAME` 에서 파생의 ORIGINAL≠DEIDENTIFIED 를 단언하는 IT 추가 ④mtime 게이트의 `IOException` 페일오픈이 의도된 정책인지 확정.

### [E-ISSUE-30] TC-RESL-034 — 출력 경로 traversal 가드가 도달 불가 코드
- **심각도**: LOW (정보)
- **기대 동작**: 케이스는 "preset명 traversal → 400" 을 기대.
- **현재 동작**: `ResolutionReservationPersister.java:122-128` `resolveSafeDir` 는 존재하나 입력이 `preset.name()`(enum 상수명)이라 traversal 문자가 유입될 경로가 없다. 즉 **가드는 실행되지만 위반 분기는 도달 불가**.
- **영향**: 없음(방어적 코드). 다만 케이스가 실제 위협을 검증하지 않는다는 착각을 준다.
- **수정 방향(제안)**: 케이스 기대값을 "enum 바인딩으로 traversal 원천 차단(가드는 심층방어)"으로 재기술. 코드 변경 불필요.

### [E-ISSUE-31] TC-RESL-005/012/031/043 — 실동작 미재현 4건(환경 제약)
- **심각도**: LOW (검증 커버리지)
- **내용**: ①TC-RESL-005(전 프리셋 동일 해상도) — 3 프리셋과 모두 일치하는 영상 부재 ②TC-RESL-012(dim≤0) — 손상 이미지 주입 불가(파일 수정 금지) ③TC-RESL-031(예약 단계 PII 게이트) — `APPROVED` + `de_ident_yn≠'Y'` + base 내 프레임을 동시 만족하는 영상 부재(rawSn 5/6 은 경로 가드가 선행 400) ④TC-RESL-043(리사이즈 게이트 포화 429) — 동시 부하 미생성.
- **수정 방향(제안)**: 검증용 시드(비식별 미완 APPROVED 영상, 프리셋 동일 해상도 영상)를 dev 시드에 추가하면 이후 회차에서 실동작 재현 가능.

---

## 요약

- **총 53건 / PASS 44 / FAIL 0 / PARTIAL 9 / BLOCKED 0 / N/A 0 / 확인필요 0**
  - E-4(17건): PASS 15 / PARTIAL 2 (TC-RESL-005, 012)
  - E-5(36건): PASS 29 / PARTIAL 7 (TC-RESL-031, 037, 039, 041, 043, 044, 049)
- **근거 라인 드리프트: 1건** (TC-RESL-013 `ResolutionPreset.java:7-8` → 실제 14-16). 나머지 44개 근거는 전부 정합.
- **self-fill 결함: 0건** — 외부 응답 없이 값을 자체 생성하는 경로 없음. 파생 소스는 `deidFrameSourceStrict`(`ResolutionSnapshotService.java:212-219`)로 **비식별 프레임만** 허용하며, 부재 시 원본 폴백 없이 CONFLICT 로 실패함을 rawSn=13 실동작으로 실증(파생 RAW `de_ident_yn='N'` 유지, 파일 0건).
- **★ 최대 결함**: 해상도 파생 산출물 raw base 생성(E-ISSUE-21) — 증상 12종 전수 식별. B-ISSUE-61(fail-closed 403)·D-ISSUE-46(fail-open 마트 오노출)·신규 E-ISSUE-22(FrameSource 폴백)를 **한 릴리스에서 함께** 고쳐야 하며, 정공법은 "파생 산출물을 deid base 하위로 이동 + `SRC`/`DE_IDNTF` 컬럼 분리 + FrameSource 폴백 제거 + 기존 4건 백필"이다. `VideoStreamService` 의 deid base 강제는 유지해야 한다.
- 신규 이슈 11건: E-ISSUE-21 ~ E-ISSUE-31 (HIGH 2 / MEDIUM 5 / LOW 4)

# E-part3 — 데이터셋 Export · Export JSON 포맷 · 촬영환경/개인정보 메타 (E-6~E-9)

> 담당 범위: `docs/test-cases/E-augment-resolution-export-meta.md` **129~205행**
> 검증 일자: 2026-07-25 / 스택: klid-postgres · klid-backend(V130, :18081) · klid-ai-server · klid-mock-server(:9400) · klid-frontend
> DB 스키마: `public` / 참조 데이터: **rawSn=26**(프레임 16·라벨 131·APPROVED·export SUCCEEDED), 보조로 rawSn=13/17/19/4/5/6
> 로컬 설정: `authoring.control-notify.enabled=false`(ControlNotifyEventListener 빈 미생성), `VLM_CLIENT_ENABLED=false`

---

## E-6. 데이터셋 Export

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-EXPORT-001 | 검수승인 AFTER_COMMIT 강제 재생성 | PASS | [실동작] rawSn=26 재승인 → 로그 `[DatasetExportBridge] review approved rawSn=26 — triggering dataset export (force regenerate)` → `AsyncDatasetExportRunner ... forceRegenerate=true`. 정적 `DatasetExportBridge.java:35-41` 일치 | DatasetExportBridgeTest(2) | AFTER_COMMIT 후에만 발화 확인(승인 트랜잭션 커밋 로그 뒤에 브릿지 로그) |
| TC-EXPORT-002 | 승인 경로 멱등 skip 미적용 | PASS | [실동작] rawSn=26 **무수정 재승인** → `v2` 신규 채번(export_sn=12, SUCCEEDED, frame_cnt=32). rawSn=17도 v1→v2, rawSn=19는 v1/v2/v3 누적. 정적 `DatasetExportService.java:104-108` | DatasetExportServiceTest "무수정_재승인도_승인경로는_새버전_생성한다 (force=true, R6)" | 승인 1회 = 새 버전 1개 확정 |
| TC-EXPORT-003 | 재동결 경로 멱등 skip | PASS | [정적] `DatasetExportService.java:104-108` + `DatasetExportBridge.java:48-54`(force=false). 로컬에 `DatasetReExportEvent` 트리거 경로(EvntAnno 지연 승인) 미발생 → **실동작 미관측** | DatasetExportServiceTest "재동결_onReExport_경로는_동일해시면_멱등_skip_유지한다" | 실동작 미검증(정적+유닛만) |
| TC-EXPORT-004 | 정상 2벌 산출 SUCCEEDED | PASS | [실동작] rawSn=26 v2: `frames written kind=ORIGINAL written=16 skipped=0` / `kind=DEIDENTIFIED written=16 skipped=0` → `export succeeded version=2 written=32`. `ls_dataset_export.export_path_nm=/app/storage/labeling/26/v2`, `frame_cnt=32`. 폴더 실체 orgnl/deid 각 32파일 | DatasetExportServiceTest·DatasetExportE2EIT | |
| TC-EXPORT-005 | 일부 프레임 부재 PARTIAL | PASS | [실동작] rawSn=13 export_sn=3 `PARTIAL frame_cnt=11`. 실측 원인: `ls_data_src`(raw_sn=13) 11행 전부 `de_idntf_src_file_path_nm`=NULL → deid 폴더 실체 **비어 있음**(orgnl만 11쌍) | DatasetExportServiceTest "일부프레임_skip시_PARTIAL로_전이한다" | rawSn 4~13 구간은 deid 경로 NULL 잔존(기보고 회귀의 잔재) |
| TC-EXPORT-006 | 산출 0건 FAILED | PASS | [실동작] rawSn=4(v1,v2)·5·6 → export_sn 6/7/9/10 모두 `FAILED, frame_cnt=NULL`. 폴더는 생성되나 orgnl/deid 둘 다 빈 디렉터리. 정적 `DatasetExportService.java:126-131` | DatasetExportServiceTest "아무것도_산출못하면_FAILED로_전이한다" | 승인은 그대로 유지 = 기보고 `D-ISSUE-04` 실증(아래 E-ISSUE-43) |
| TC-EXPORT-007 | deid 프레임 export — 해상도 파생 정합 | PASS | [실동작] rawSn=19(=17의 RESL_480P 파생) `ls_data_src` 12/12 `de_idntf_src_file_path_nm` non-null → export v2/v3 **SUCCEEDED frame_cnt=24**(PARTIAL 회귀 없음). 정적 `ResolutionPersistService.java:243-247` 6-arg 아닌 **9-arg** `LsDataSrc.create(..., dst, dst, ...)` INSERT 시점 저장 | — | ★ 부수 결함 별건: orgnl/deid **경로가 동일**(E-ISSUE-41) |
| TC-EXPORT-008 | 버전 채번 UK 충돌 재시도 | PASS | [정적] `DatasetExportService.java:170-180` insertWithRetry(최대 3회) + `DatasetExportTxService` 각 시도 REQUIRES_NEW. 동시 승인 미재현 | DatasetExportServiceTest "동시_승인_UK위반시_재시도로_다음버전_채번된다" | 실동작 미검증 |
| TC-EXPORT-009 | 버전 채번 재시도 소진 | PASS | [정적] `DatasetExportService.java:112-117` null→VERSION_EXHAUSTED abort | DatasetExportServiceTest "UK위반이_재시도_상한_초과하면_산출을_중단한다" | |
| TC-EXPORT-010 | 파일쓰기 실패 시 승인 불변 | PASS | [실동작·간접] rawSn=4/5/6 export FAILED 상태에서 `ls_raw_data_status` 승인 상태 유지(롤백 없음). 정적 `DatasetExportService.java:146-153`(RuntimeException 삼킴 + markFailed) | DatasetExportServiceTest "파일산출_실패해도_승인은_롤백되지_않는다" | writer.write 예외 자체는 미재현(0건 산출 경로로 간접 확인) |
| TC-EXPORT-011 | 입력 부재 NO_INPUT | PASS | [정적] `DatasetExportService.java:95-99` + `DatasetExportTxService:98,105` (no frames / no active meta 로깅) | DatasetExportServiceTest "프레임_또는_활성메타_부재시_산출을_skip한다" | 메트릭 태그에 `no_input` 미출현(이번 세션 미발생) |
| TC-EXPORT-012 | 메트릭 outcome 정확히 1회 | PASS | [실동작] `/actuator/metrics/dataset.export.result` COUNT=**7.0**, tags outcome=[failed, completed]. `dataset.export.duration` COUNT=**7.0**. 세션 내 실제 export 건수(export_sn 6~12) = 7 로 **정확히 일치**(이중계상·누락 0) | DatasetExportServiceTest 메트릭 6건 | 메트릭명 실제는 `dataset.export.skipped_frames`(밑줄) |
| TC-EXPORT-013 | 브릿지 조건부 비활성화 | PASS | [정적] `DatasetExportBridge.java:28-29` `@ConditionalOnProperty(..., matchIfMissing=true)` | DatasetExportBridgeBeanConditionTest(3) | |
| TC-EXPORT-014 | 폴더 구조 계약 | PASS | [실동작] `/app/storage/labeling/26/v1`·`v2` → 각 `orgnl/`·`deid/`, 내부 `frame-{0..15}.jpg` + 동명 `.json`. 정적 `DatasetExportPathResolver.java:57-66`(labelingRoot 하위 startsWith 검증, 세그먼트 전부 타입안전 long/int/enum → CWE-22 표면 없음) | DatasetExportPathResolverTest(5) | 경로에 사용자 입력 미혼입 확인 |

---

## E-7. Export JSON 포맷

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-EXPORT-020 | event(구 event_annotation) pass-through | PASS | [실동작] rawSn=17 재승인 → v2 `orgnl/frame-0.json` 최상위 **`"event"`** 블록에 `ls_evnt_anno.anno_cn`(answer/caption.c1.cot/evidence.c1/question/event_class) **원문 그대로** 직렬화. 위치는 `video` 다음 | NiaJsonBuilderTest "event가_각_프레임_최상위에_c1cn_형태로_pass_through된다" / "최상위_VLM블록키는_event이고_event_annotation키는_부재_위치는_video다음" | **근거 드리프트**: TC 표 기대값은 `event_annotation` 이나 확정 결정(cudo local 이슈④)에 따라 `event` 로 rename 완료. TC 문서가 stale |
| TC-EXPORT-021 | event null 처리 | PASS | [실동작] rawSn=26 v1/v2 · rawSn=19 v3 → `"event" : null`(키 유지). VLM 비활성 상태에서 **값을 만들어내지 않음** = self-fill 없음 | NiaJsonBuilderTest "동결_event가_없으면_event키는_null이다" | `NiaAnnotationDoc` `@JsonInclude(ALWAYS)` |
| TC-EXPORT-022 | anonymity=ExportKind 파생(수동 override 금지) | PASS | [실동작] 프레임 446에 수동 `anonymity="Y"` 저장 후 재승인 → v2 `orgnl/frame-0.json` `image.anonymity="N"`, `deid/frame-0.json` `image.anonymity="Y"`. **수동값이 덮지 않음**(CWE-359 방어 유효). 정적 `NiaJsonBuilder.java:142-145` | NiaJsonBuilderTest 2건(원본 N 유지 / 비식별 Y 유지) | |
| TC-EXPORT-023 | pseudonymity/privacyIncluded 수동 우선 | PASS | [실동작] 프레임 446 수동 `pseudonymity=Y, privacyIncluded=Y` → v2 orgnl·deid 모두 `image.pseudonymity="Y"`, `privacy_included="Y"`. 미저장 프레임(frame-1)은 파생 `N/N` 폴백. 정적 `NiaJsonBuilder.java:148-151` | NiaJsonBuilderTest 2건 | |
| TC-EXPORT-024 | deid 산출 경로 fail-secure | PASS | [실동작·부분] rawSn=26 deid JSON `dataset.src_path`·`video.filename` = `/app/storage/deidentified/videos/26/..._mask.mp4`(원본 경로 미노출), orgnl 은 raw 경로. `deidVideoPath=null` 케이스는 실환경 부재 → 정적 `NiaJsonBuilder.java:131-136` / `VideoMetaMapper.java:56-59` | VideoMetaMapperTest "DEIDENTIFIED인데_deid경로_null이면_filename도_null_원본미노출" 외 4건 | |
| TC-EXPORT-025 | malformed 라벨 skip | PASS | [정적] `NiaJsonBuilder.java:170-193` try/catch → skip + `lblSn` 만 로깅(좌표·PII 미출력) | NiaJsonBuilderTest "malformed라벨은_문서조립시_skip되고_정상라벨만_남는다" | |
| TC-EXPORT-026 | 잉여키 제거/키 유지 정합 | PASS | [실동작] v2(신규 코드) JSON 에 `orign_file_name`/`orign_filename`/`cto`/`vqa` **전부 부재**. 미보유 필드(type/format/filesize/fps/frames/pixel 등)는 **키 유지 + 값 null**. 대조: rawSn=17 **v1**(구 코드, 07-23 산출)에는 `image.orign_file_name` 존재 → 제거가 실제로 반영됨을 2세대 비교로 확인 | NiaJsonBuilderTest·VideoMetaMapperTest 3건 | |
| TC-EXPORT-027 | vd_description 필드 존재 | PASS | [실동작] `video.vd_description : null` 키 존재. 정적 `NiaVideo.java:46` | NiaJsonBuilderTest | 데이터 출처 없음 → 항상 null(값 날조 없음 = 정상) |
| TC-EXPORT-028 | weather/time_of_day/season 수동값 우선 | PASS | [실동작] rawSn=26 수동 `weather=비, timeOfDay=DAY, season=SPRING` 저장 후 재승인 → v2 JSON `weather:"비"`, `time_of_day:"DAY"`, `season:"SPRING"`(파생값 NGT/SUMMER 를 **수동값이 이김**). 정적 `VideoMetaMapper.java:52-54`(raw→meta 역순 우선) | VideoMetaMapperTest 3건 | 검증 후 수동값 null 복원 완료 |
| TC-EXPORT-029 | 촬영환경 blank→null 정규화 | PASS | [정적] `VideoMetaMapper.java:139-147` firstNonBlank / `EnvironmentMetaService.java:160-163` blank→null. DB 직접 blank 주입 미재현 | VideoMetaMapperTest | |
| TC-EXPORT-030 | anonymity kind override(video) | PASS | [실동작] v2 orgnl `video.anonymity="N"` / deid `"Y"`, **촬영환경 3필드는 2벌 동일**(비/DAY/SPRING). 정적 `VideoMetaMapper.java:49-60` | VideoMetaMapperTest "ORIGINAL_DEIDENTIFIED_두_export의_촬영환경_동일" | |
| TC-EXPORT-031 | meta null 방어 | PASS | [정적] `NiaJsonBuilder.java:87-90` INVALID_INPUT("영상 메타가 null 입니다.") | NiaJsonBuilderTest "meta가_null이면_INVALID_INPUT" | |
| TC-EXPORT-032 | FORMAT_VERSION/info 계약 | PASS | [실동작] 모든 산출 JSON `info.version="1.3"`, `type="instances"`, `info.description="AI기반 CCTV 관제지원시스템 학습데이터"`, `licences[0]={id:1,name:"Private Use"}` | NiaJsonBuilderTest "최상위_8키_info부터_type까지_모두_존재한다" | 실제 최상위 키는 9개(info/dataset/licences/video/event/image/annotations/categories/type) |

---

## E-8. 촬영환경 메타 (영상 단위)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-META-001 | 조회 — 수동값 우선 프리필 | PASS | [실동작] PUT(비/DAY/SPRING) 후 GET → `{"weather":"비","timeOfDay":"DAY","season":"SPRING","weatherSource":"MANUAL","timeOfDaySource":"MANUAL","seasonSource":"MANUAL"}` | EnvironmentMetaServiceTest(17) | |
| TC-META-002 | 조회 — 파생 프리필 | PASS | [실동작] 수동값 없는 rawSn=26 → `timeOfDay:"NGT"(DERIVED)`, `season:"SUMMER"(DERIVED)`, `weather:null, weatherSource:null`. `ls_data_raw` 3컬럼 전부 NULL 실측 | EnvironmentMetaServiceTest·TimeOfDaySeasonDeriverTest | ★ 파생 자체의 원천 문제는 E-ISSUE-42 |
| TC-META-003 | PUT 전체 교체 저장 | PASS | [실동작] 3필드 PUT → `ls_data_raw` 3컬럼만 갱신(다른 컬럼 불변). 정적 `EnvironmentMetaService.java:100-102` dirty checking | EnvironmentMetaServiceTest | |
| TC-META-004 | null 필드 = 수동값 삭제 | PASS | [실동작] `{"weather":null,"timeOfDay":null,"season":null}` PUT → DB 3컬럼 NULL 복귀, GET 이 다시 DERIVED 폴백. 정적 `EnvironmentMetaService.java:160-171` | EnvironmentMetaServiceTest | |
| TC-META-005 | 허용값 화이트리스트 weather | PASS | [실동작] `"폭우"` → **400** `촬영환경 weather 값이 허용 목록에 없습니다.` 정적 `ShootingEnvironmentVocabulary.java:23`(맑음/흐림/비/눈/안개) | EnvironmentMetaServiceTest | |
| TC-META-006 | 허용값 timeOfDay/season | PASS | [실동작] `"MORNING"`→400, `"MONSOON"`→400. 정적 `ShootingEnvironmentVocabulary.java:26-32` | EnvironmentMetaServiceTest | 파생 상수 재사용으로 코드집합 드리프트 차단 확인 |
| TC-META-007 | 길이 상한 20 | PASS | [실동작] 21자 weather → 400 `weather: size must be between 0 and 20`(@Size 선차단). 정적 `EnvironmentMetaUpdateRequest.java:28` | EnvironmentMetaControllerTest | |
| TC-META-008 | APPROVED 후 수정 재동결+통지 | PARTIAL | [실동작] 재동결 O — rawSn=26 PUT 후 `ls_dataset_video_meta` 신규 active 행(비/DAY/SPRING) 생성, 이전 행 `active_yn=N`, **`rvw_cmpl_dt`=10:45:47 원값 보존**(reg_dt만 11:39:32). 로그 `[Dataset] materialized ... inserted=true` + `[EnvironmentMeta] re-freeze triggered`. **통지 X — `authoring.control-notify.enabled=false` 로 `ControlNotifyEventListener` 빈 미생성(`ControlNotifyEventListener.java:21`) → TASK_MODIFIED 실발행 미관측** | EnvironmentMetaReFreezeIT(1) | 통지부는 정적/IT 만. 로컬 설정 제약 |
| TC-META-009 | 재동결 시 export 미재생성 | PASS | [실동작] PUT 직후 `/app/storage/labeling/26/` = `v1` 만(신규 폴더 없음), `ls_dataset_export` 신규행 없음. 정적 `EnvironmentMetaService.java:132-137` | — | 의도된 정책(디스크 증폭 방지) |
| TC-META-010 | 미검수 영상 수정 — 통지 없음 | PASS | [실동작] rawSn=11(미승인) PUT 2회 → 로그에 `[EnvironmentMeta] updated rawSn=11` 만, `materialized`/`re-freeze` 로그 **부재**. 정적 `EnvironmentMetaService.java:110,207-213` | EnvironmentMetaServiceTest | |
| TC-META-011 | 재동결 시 활성 스냅샷 부재 fail-safe | PASS | [정적] `EnvironmentMetaService.java:143-147` warn 후 return(예외 없음) | EnvironmentMetaServiceTest | 실환경 재현 불가(APPROVED+스냅샷 부재 조합 없음) |
| TC-META-012 | 동시성 — env저장 vs 승인 materialize | PASS | [정적] `EnvironmentMetaService.java:105-114` — `videoRepository.flush()`(raw 행락) → `videoMetaRepository.acquireRawLock(rawSn)`(advisory) → 상태 판정 순서. 잠금 순서 raw→advisory 단방향(교착 없음) | EnvironmentMetaConcurrencyIT(1) | 실동작 동시 재현 미수행 |
| TC-META-013 | WORKER 본인배정 아닌 영상 | PASS | [실동작] WORKER(2001) → rawSn=**10**(미배정) GET/PUT 모두 **403** `본인에게 배정되지 않은 영상입니다.` (rawSn=11 은 `ls_task_assignment` 상 2001 배정이라 200 — 정상) | EnvironmentMetaServiceTest | CWE-639 방어 유효 |
| TC-META-014 | 미인증/포털 채널 | PASS | [실동작] 토큰 없음 → **401** `인증이 필요합니다.` / PORTAL 채널 토큰 → **403** `권한이 없습니다.` | EnvironmentMetaControllerTest | |
| TC-META-015 | 영상 미존재 | PASS | [실동작] rawSn=9999 GET → **404** `영상을 찾을 수 없습니다.` | EnvironmentMetaServiceTest | |
| TC-META-016 | 로그 PII 미출력 | PASS | [실동작] 허용값 외 입력 3회 → 로그 `[EnvironmentMeta] rejected value field=weather/timeOfDay/season` — **입력 원문 미노출**. 성공 로그도 `updated rawSn=26` 만 | — | CWE-117/209 준수 |

---

## E-9. 프레임 개인정보 메타 (프레임 단위)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-META-030 | 조회 — 수동값 우선/파생 폴백 | PASS | [실동작] 미저장 프레임 446 GET → `{"anonymity":"Y","pseudonymity":"N","privacyIncluded":"N"}` (raw `prvc_type_cd=ANONY`→Y, PSDO 아님→N, `prvc_yn=N`). 수동 저장 후 GET → 저장값 반영. 정적 `FramePrivacyMetaService.java:154-177` | FramePrivacyMetaServiceTest(8) | |
| TC-META-031 | 단건 PUT 전체 교체 | PASS | [실동작] `{Y,Y,Y}` 저장 → DB 3컬럼 반영, 이후 `{null,null,null}` PUT → 3컬럼 NULL(파생 폴백 복귀) | FramePrivacyMetaServiceTest | |
| TC-META-032 | 값 화이트리스트 ^[YN]$ | PASS | [실동작] `"1"`→400 `anonymity 는 Y 또는 N 이어야 합니다.`, `"true"`→400. 정적 `FramePrivacyMetaUpdateRequest.java:30-32` | FramePrivacyMetaControllerTest | |
| TC-META-033 | path/body srcSn 불일치 | PASS | [실동작] path=446, body srcSn=447 → **400** `path 의 srcSn 과 body 의 srcSn 이 다릅니다.` 정적 `FramePrivacyMetaController.java:85-88` | FramePrivacyMetaControllerTest | CWE-345 |
| TC-META-034 | anonymity는 export 미덮음 | PASS | [실동작] 프레임 446 `anonymity="Y"` 저장 상태에서 재승인 → export orgnl `image.anonymity="N"`, deid `"Y"`. **API 조회는 Y(표시용)·export 는 kind 파생** 이중 값이 설계대로 분리됨 | NiaJsonBuilderTest 2건 | TC-EXPORT-022 와 동일 실측 |
| TC-META-035 | 벌크 저장 N+1 제거 | PASS | [실동작+정적] 2건 벌크 PUT 200, 로그 1행 `bulk-updated count=2 rawSns=1`. 정적 `FramePrivacyMetaService.java:100-136` — `findAllById` 1회 · rawSn distinct 인가 1회(`authorizedRawSns.add`) · `saveAll` 1회 | — | |
| TC-META-036 | 벌크 미존재 프레임 404 | PASS | [실동작] `[446, 999999]` 벌크 → **404** `프레임을 찾을 수 없습니다.` + 446 DB **미변경**(롤백) | FramePrivacyMetaServiceTest | |
| TC-META-037 | 벌크 타 영상 403 | PASS | [실동작] WORKER 로 `[446(본인), 384(타영상)]` 벌크 → **403** `본인에게 배정되지 않은 영상입니다.` + 두 행 모두 DB 미변경. 404先→403後 순서도 TC-META-036 과 대조해 보존 확인 | FramePrivacyMetaServiceTest | |
| TC-META-038 | 벌크 원자성 | PASS | [실동작] 위 404/403 두 케이스 모두 선행 정상 항목(446)이 **미반영** = `@Transactional` 전체 롤백. 정적 `FramePrivacyMetaService.java:99` | — | |
| TC-META-039 | APPROVED 후 수정 통지 디바운스 | PARTIAL | [정적] `FramePrivacyMetaService.java:126-129` 프레임별 `TaskModifiedEvent(META_UPDATED)` 발행 → 다운스트림 `ControlNotifyDebouncer` 코얼레스. **로컬은 `control-notify.enabled=false` 로 소비 리스너 빈 미생성 → 통지·디바운스 실동작 미관측** | — | 로컬 설정 제약 |
| TC-META-040 | 벌크 항목 수 초과/빈 목록 | PASS | [실동작] `items:[]` → **400** `items 는 1건 이상이어야 합니다.` 상한은 정적 `FramePrivacyBulkRequest.MAX_ITEMS=5000`(@Size) — 5000 초과 미재현 | FramePrivacyMetaControllerTest | 근거 드리프트: TC 표는 `FramePrivacyMetaController.java:97-99`(=@ApiResponses 주석 블록), 실 검증은 `FramePrivacyBulkRequest.java:20-24` |
| TC-META-041 | WORKER 본인배정/미인증 | PASS | [실동작] WORKER → 타 영상 프레임 384 GET/PUT **403**, 토큰 없음 **401**, PORTAL 채널 **403**, 미존재 srcSn **404** | FramePrivacyMetaServiceTest | |
| TC-META-042 | 로그 판단값 미출력 | PASS | [실동작] 로그 `[FramePrivacyMeta] updated srcSn=446 rawSn=26` / `bulk-updated count=2 rawSns=1` — **Y/N 판단값 미노출** | — | CWE-359 준수 |

---

## ★ export JSON 실물 대조 (rawSn=26)

### 확인한 파일 경로 (컨테이너 `klid-backend` 내부)
- `/app/storage/labeling/26/v1/orgnl/frame-0.json` · `/app/storage/labeling/26/v1/deid/frame-0.json` (승인 시 최초 산출, 각 16쌍 = 32파일)
- `/app/storage/labeling/26/v2/orgnl/frame-0.json` · `/app/storage/labeling/26/v2/deid/frame-0.json` (**수동 메타 입력 후 재승인** 산출)
- 대조군: `/app/storage/labeling/17/v1/orgnl/frame-0.json`(구 코드 07-23) vs `/app/storage/labeling/17/v2/orgnl/frame-0.json`(현 코드) · `/app/storage/labeling/13/v1/deid/`(빈 폴더, PARTIAL) · `/app/storage/labeling/4/v1/`(양쪽 빈 폴더, FAILED)

### 필드별 3자 대조 (DB ↔ JSON ↔ 기대스펙)

| JSON 경로 | DB 원천 (실측값) | JSON 값 (v1 / v2) | 기대 스펙 | 판정 |
|-----------|-----------------|-------------------|-----------|:--:|
| `info.version` | (상수) | `1.3` | xlsx v1.3 | 일치 |
| `dataset.src_path`(orgnl) | `ls_dataset_video_meta.raw_file_path_nm` | `/app/storage/raw/autolabel-test/a59263dc….mp4` | 원본 raw 경로 | 일치 |
| `dataset.src_path`(deid) | `ls_deident_proc_log.de_idntf_file_path_nm` = `/app/storage/deidentified/videos/26/a59263dc…_202607250142_mask.mp4` | 동일 문자열 | 비식별 경로(원본 미노출) | 일치 |
| `video.id` / `image.video_id` | `raw_sn=26` | `"26"` | rawSn | 일치 |
| `video.length` | `vdo_len_sec=32` | `"32"` | 초 문자열 | 일치 |
| `video.weather` | `ls_data_raw.wthr_nm` = NULL / (v2 시점) `"비"` | `null` / **`"비"`** | 수동값, 없으면 null | 일치(수동 우선 확인) |
| `video.time_of_day` | raw=NULL, `ls_dataset_video_meta.day_ngt_cd`=`NGT` / (v2) raw=`DAY` | `"NGT"` / **`"DAY"`** | 수동 우선, 없으면 파생 | 일치 |
| `video.season` | raw=NULL, meta=`SUMMER` / (v2) raw=`SPRING` | `"SUMMER"` / **`"SPRING"`** | 동상 | 일치 |
| `video.anonymity` | (DB 원천 없음 — ExportKind 파생) | orgnl `"N"` / deid `"Y"` | kind 파생, 수동 미덮음 | 일치 |
| `video.pseudonymity` | `prvc_type_cd=ANONY`(≠PSDO) | `"N"` | 파생 | 일치 |
| `video.privacy_included` | `prvc_yn='N'` | `"N"` | 그대로 | 일치 |
| `video.event_id` / `event_name` | `evnt_type_cd=EV02000201` / `evnt_nm=쓰러짐` | 동일 | 그대로 | 일치 |
| `video.cctv_name` | `cctv_nm=CCTV-강남구-001` | 동일 | 그대로 | 일치 |
| `video.vd_description` | (원천 없음) | `null` | 키 유지·값 null | 일치 |
| `event` | `ls_evnt_anno`(rawSn=26 부재) | `null` | 없으면 null | 일치 (**self-fill 없음**) |
| `image.id` / `frame_num` | `ls_data_src.src_sn=446` / `frm_no=0` | `446` / `0` | 그대로 | 일치 |
| `image.date_captured` | `ls_data_src.sht_dt=2026-07-25T18:00` | `"2026-07-25T18:00"` | 그대로 | 일치 |
| `image.anonymity` | `anony_incl_yn` NULL /(v2) `Y` | orgnl `"N"` / deid `"Y"` — **v2 도 동일** | kind 파생 고정 | 일치 |
| `image.pseudonymity` / `privacy_included` | `psdo_incl_yn`/`prvc_incl_yn` NULL /(v2) `Y`/`Y` | `"N"/"N"` / **`"Y"/"Y"`** | 수동 우선 | 일치 |
| `image.width/height` | `ls_dataset_video_meta.vdo_wdth/vdo_hgt` = NULL | `null` | 미보유 시 null | 일치(원천 없음 → 날조 안 함) |
| `annotations[]` | `ls_data_lbl` (rawSn=26 총 131건) | frame-0 에 bbox 4 + polygon 1 | 라벨 매핑 | 일치 |
| `categories[]` | `ls_label` 사용분 | person(1)/car(2)/bus(5), `type:"bbox"` | 사용 라벨만 | 일치 |

### 원천 없이 채워진 필드(self-fill 의심) 목록

| 필드 | 실측 값 | 원천 | 판정 |
|------|--------|------|:--:|
| `video.time_of_day` | `NGT` | **없음** — `ls_data_raw.day_ngt_cd` NULL, 관제 `MNG_CLIP_EVNT_LST.HR_TYPE_CD` 미독. `SHT_DT.hour>=18 → NGT` 규칙으로 코드가 생성 | **self-fill (E-ISSUE-42)** |
| `video.season` | `SUMMER` | **없음** — `sesn_cd` NULL, 관제 `SESN_CD` 미독. `SHT_DT.month` 규칙으로 생성 | **self-fill (E-ISSUE-42)** |
| `video.weather` | `null` | 없음 → **null 유지** | 정상 (날조 안 함) |
| `video/image.anonymity` | `N`/`Y` | ExportKind(산출 종류) — 실제 산출물 성격과 1:1 대응 | 정상 (의도된 파생, DB 근거 있음) |
| `video.pseudonymity`, `image.pseudonymity`/`privacy_included` | `N` | `ls_data_raw.prvc_type_cd`/`prvc_yn` 실값 | 정상 |
| `event`, `vd_description`, `width/height/fps/frames/pixel` 등 | `null` | 없음 → null | 정상 |

> 결론: **날씨·event·미보유 필드는 self-fill 하지 않는다**(정상). 단 **시간대·계절 2필드만 원천 없이 코드 규칙으로 생성**되며, 이 값이 export JSON·데이터마트 뷰에 `MANUAL/DERIVED` 구분자 없이 실린다.

---

## 이슈 상세

### [E-ISSUE-41] TC-EXPORT-007 — 해상도 파생 영상의 orgnl/deid 프레임 경로가 **동일**해 2벌 산출이 바이트 동일하고 anonymity 가 오표기됨
- **심각도**: HIGH (개인정보 메타 오표기 · 데이터마트 계약 위반 · 저장소 2배 낭비)
- **기대 동작(기대효과)**: `V_COMPLETED_FRAME` 계약대로 `ORIGINAL_PATH`(원본)와 `DEIDENTIFIED_PATH`(비식별)가 **항상 상이**하고, `orgnl/` 산출물(anonymity="N")은 비식별 처리되지 않은 원본 픽셀, `deid/` 산출물(anonymity="Y")은 비식별 픽셀이어야 한다.
- **현재 동작(이슈 내용)** [실동작]:
  - `ls_data_src`(raw_sn=18,19 — 해상도 파생) 실측: `src_file_path_nm` == `de_idntf_src_file_path_nm` = `/app/storage/raw/resolution/19/frames/frame-N.jpg` (완전 동일 문자열, 12/12행).
  - 산출물 md5 동일: `/app/storage/labeling/19/v3/orgnl/frame-0.jpg` = `/app/storage/labeling/19/v3/deid/frame-0.jpg` = `ae0d1773889308f3435a5ecb122f5523`.
  - 코드 근거 — `ResolutionPersistService.java:243-247` `LsDataSrc.create(newRawSn, frameNo, videoFrameNo, dst, dst, ...)` (주석 "파생본은 비식별 산출 → src=deid 경로 동일" 로 **의도적**).
  - 결과: `orgnl/frame-0.json` 이 `video.anonymity="N"`, `image.anonymity="N"` 으로 나가지만 픽셀 실체는 비식별본이다(`NiaJsonBuilder.java:145` 가 kind 로만 결정). 역으로 `deid/` 는 정상.
  - 또한 `FrameSource.java:71-78` 이 DEIDENTIFIED 에 대해 `deidBase` 실패 시 `rawBase` fallback 을 허용해 이 경로가 통과한다(설계상 해상도 파생 수용 목적).
- **재현/확인 경로**:
  1) `SELECT src_file_path_nm, de_idntf_src_file_path_nm FROM ls_data_src WHERE raw_sn=19;` → 두 컬럼 동일
  2) `docker exec klid-backend md5sum /app/storage/labeling/19/v3/{orgnl,deid}/frame-0.jpg` → 동일 해시
  3) `grep '"anonymity"' /app/storage/labeling/19/v3/orgnl/frame-0.json` → `"N"`
- **영향**: 학습데이터 라벨 메타 오표기(비식별본을 "익명화 안 됨"으로 배포). 데이터마트 `V_COMPLETED_FRAME` 의 "두 경로 항상 상이" 불변식 파손. 해상도 파생 1건마다 동일 바이트를 버전당 2벌 복사(디스크 2배). 보안 CWE-1188(부정확한 보안 속성 초기화) 성격 — PII 유출 방향은 아니나 **역방향 오표기**.
- **수정 방향(제안, 구현 금지)**: ①해상도 파생 프레임의 `SRC_FILE_PATH_NM` 을 부모 **원본** 프레임을 리스케일한 별도 산출로 두고 deid 경로를 분리하거나, ②파생 영상은 원천이 비식별본임을 `LsDataRaw`(예: `orgnl_raw_sn` + `de_ident_yn`) 로 판정해 **ORIGINAL 산출을 스킵하거나 `anonymity="Y"` 로 표기**하도록 `NiaJsonBuilder`/`VideoMetaMapper` 의 kind 파생을 보정. ③최소 조치로 export 시 두 경로 동일이면 `deid` 1벌만 산출.

### [E-ISSUE-42] TC-META-002 / TC-EXPORT-028 — `time_of_day`·`season` 이 원천 없이 촬영일시 규칙으로 생성되어 export·데이터마트에 **파생 표시 없이** 실림 (self-fill)
- **심각도**: MEDIUM (데이터 정확성 · 관제 원천 미활용)
- **기대 동작(기대효과)**: 촬영환경 3필드의 원천은 ①작업자 수동입력 또는 ②관제 공유 `MNG_CLIP_EVNT_LST.WTHR_CD/SESN_CD/HR_TYPE_CD` 실값이어야 하고, 어느 쪽도 없으면 `null`(미상) 이거나 최소한 파생임이 소비자에게 식별 가능해야 한다.
- **현재 동작(이슈 내용)** [실동작]:
  - rawSn=26: `ls_data_raw.day_ngt_cd/sesn_cd` 모두 NULL 인데 `ls_dataset_video_meta` 에 `NGT`/`SUMMER` 가 동결되고 export JSON `video.time_of_day="NGT"`, `season="SUMMER"` 로 출력.
  - 생성 규칙 — `TimeOfDaySeasonDeriver.java:49-55`(hour ∈ [6,18) → DAY, else NGT), `:63-74`(월 3-3-3-3). 호출 지점 `DatasetVideoMetaSnapshotService.java:109-110`.
  - **정확성 결함 실증**: rawSn=26 의 `sht_dt = 2026-07-25 18:00`(한국 7월 일몰 ≈ 19:50) → 실제로는 주간이나 규칙상 `NGT`. 경계 18:00 고정이 계절과 무관해 여름 저녁은 항상 야간으로 오분류된다.
  - **원천 미활용 실증**: 저작도구 엔티티 `MngClipEvntLst.java:20` 주석이 `SESN_CD/WTHR_CD/HR_TYPE_CD/PRVC_TYPE_CD` 를 **명시적으로 매핑 생략**. 코드 전체에 `WTHR_CD`/`HR_TYPE_CD` 참조 0건(grep).
  - **소비자 구분 불가**: 조회 API 는 `timeOfDaySource:"DERIVED"` 를 주지만(투명), **export JSON·`LS_DATASET_VIDEO_META`·데이터마트 뷰에는 MANUAL/DERIVED 구분자가 없다** → 관제/데이터마트는 파생 추정값을 관측값과 동일하게 소비한다.
- **재현/확인 경로**: `SELECT sht_dt, day_ngt_cd, sesn_cd FROM ls_data_raw WHERE raw_sn=26;`(전부 NULL) ↔ `SELECT day_ngt_cd, sesn_cd FROM ls_dataset_video_meta WHERE raw_sn=26 AND active_yn='Y';`(NGT/SUMMER) ↔ `grep time_of_day /app/storage/labeling/26/v1/orgnl/frame-0.json`
- **영향**: 학습데이터 속성(주야간·계절)이 사실과 다를 수 있고, 이 속성으로 필터링/증강 유형 매칭을 하면 오염이 전파된다. 관제에 이미 존재하는 정답 값을 두고 추정값을 배포한다(메모리 `control-clip-meta-source-of-truth` 의 확정 방향과 배치).
- **수정 방향(제안, 구현 금지)**: ①`MngClipEvntLst` 에 `WTHR_CD/SESN_CD/HR_TYPE_CD/PRVC_TYPE_CD` 매핑 추가 → 적재/동결 시 **관제 실값 우선**(우선순위: 수동 > 관제 > 파생 > null). ②관제 코드도메인↔`WTHR_NM` 매핑표 확보(ERD-024). ③파생만 남는 경우 `LS_DATASET_VIDEO_META` 에 출처 컬럼(예: `ENV_SRC_CD` MANUAL/CTRL/DERIVED)을 추가해 export·뷰로 전파하거나, ④파생을 **중단하고 null** 로 두어 self-fill 을 제거.

### [E-ISSUE-43] TC-EXPORT-006 — 라벨/프레임 산출 불가 영상이 승인은 되고 export 만 FAILED 로 남아 학습데이터 0건인 채 "검수 완료"로 노출됨
- **심각도**: MEDIUM (기보고 `D-ISSUE-04` 의 E 구간 실증 — 중복 아님, 산출물 관점 보강)
- **기대 동작(기대효과)**: 검수 승인 = 학습데이터 확정이므로, 산출 가능한 프레임이 0건이면 승인 자체가 차단되거나 최소한 승인 후 재시도/알림 경로가 있어야 한다.
- **현재 동작(이슈 내용)** [실동작]: rawSn=4(v1,v2)·5·6 → `ls_dataset_export` 4행 `FAILED, frame_cnt=NULL`. 대응 폴더 `/app/storage/labeling/4/v1/{orgnl,deid}` 는 **생성되었으나 비어 있음**. `ls_raw_data_status` 는 `APPROVED` 유지. 코드 근거 `DatasetExportService.java:126-131`(`markFailed` 후 예외 미전파 — 승인 불변은 의도된 계약).
  - 부수: 실패해도 빈 디렉터리가 남고 정리(cleanup)되지 않는다(`DatasetExportWriter.java:80-87` 에서 선생성).
  - 부수: `retention` 미구현이 코드 TODO 로 명시(`DatasetExportService.java:110-111`) — 승인 반복마다 v1..vN 이 무한 누적(rawSn=19 는 이미 v3).
- **재현/확인 경로**: `SELECT * FROM ls_dataset_export WHERE export_stts_cd='FAILED';` → `docker exec klid-backend ls -R /app/storage/labeling/4`
- **영향**: 데이터마트가 `V_COMPLETED_VIDEO.EXPORT_PATH_NM` 로 픽업하면 빈 폴더/NULL 을 얻는다. 운영 알림 없이 무산출 승인이 축적.
- **수정 방향(제안, 구현 금지)**: 승인 전 `프레임 수>0 && 산출 가능 이미지>0` 선검증(reject 대신 경고+차단), export FAILED 시 재시도 잡/운영 알림 연결, 빈 산출 디렉터리 cleanup, retention 잡 도입.

### [E-ISSUE-44] TC-EXPORT-020 — export 최상위 VLM 키가 `event` 로 rename 되었으나 테스트케이스 문서·기존 산출물(v1)은 `event_annotation` — 계약 이원화 + `cot` 배열/객체 혼재
- **심각도**: LOW~MEDIUM (다운스트림 파서 이원화)
- **기대 동작(기대효과)**: 산출 JSON 의 VLM 블록 키와 내부 구조가 단일 계약이어야 한다.
- **현재 동작(이슈 내용)** [실동작]:
  - 현 코드: `NiaAnnotationDoc.java:33` `@JsonProperty("event")` → rawSn=17 **v2**(오늘 산출) = `"event"`, rawSn=19 v3 = `"event": null`.
  - 과거 산출물: rawSn=17 **v1**(07-23) = `"event_annotation"` + `image.orign_file_name` 잉여키 존재. **두 포맷이 같은 스토리지에 공존**(v1/v2 폴더).
  - `cot`: 정본은 객체(`{"1단계":..}`) 이나(`EventAnnotationPayload.java:88-96`) 동결 소스는 `ls_evnt_anno.anno_cn` **원문 JsonNode pass-through** 이므로 기존 저장분(`evnt_anno_sn=1,2`)의 **배열** 형태가 그대로 export 된다 — 실측 v2 JSON `"cot" : [ "111", "222", "3333" ]`. 코드 주석이 "배열 동결본 백필은 out of scope" 로 명시(의도된 잔존).
  - 테스트케이스 문서(TC-EXPORT-020/021)는 여전히 `event_annotation` 표기 → **근거 드리프트**.
- **재현/확인 경로**: `grep -n '"event' /app/storage/labeling/17/v1/orgnl/frame-0.json` vs `.../17/v2/orgnl/frame-0.json`
- **영향**: 관제/데이터마트 파서가 두 키와 두 `cot` 형태를 모두 다뤄야 한다.
- **수정 방향(제안, 구현 금지)**: ①구 버전 산출물 재생성 또는 폐기 정책 명시, ②`cot` 배열 동결본 백필(또는 export 시 배열→`n단계` 객체 정규화 — 이미 `CotDeserializer` 로직 재사용 가능), ③테스트케이스 문서 `event_annotation`→`event` 정정.

### [E-ISSUE-45] TC-META-008 / TC-META-039 — 로컬 설정으로 TASK_MODIFIED 통지 경로 실동작 미검증 (검증 한계)
- **심각도**: INFO (제품 결함 아님 — 검증 커버리지 공백)
- **기대 동작**: 촬영환경/프레임 개인정보 메타를 APPROVED 후 수정하면 관제로 `TASK_MODIFIED(META_UPDATED)` 가 rawSn 단위 1회 코얼레스되어 발행.
- **현재 상태**: `ControlNotifyEventListener.java:21` `@ConditionalOnProperty(name="authoring.control-notify.enabled", havingValue="true")` + 로컬 `false` → **소비 리스너 빈 자체가 없다.** 이벤트 발행부(`EnvironmentMetaService.java:112-114`, `FramePrivacyMetaService.java:126-129,147-150`)는 정적 확인만 완료. 로그에 `TaskModified`/`Debounce` 출력 0건.
- **영향**: TC-META-008 의 통지 절반, TC-META-039 전체가 실동작 미검증 → 두 케이스 PARTIAL.
- **수정 방향(제안)**: 목업 관제 inbound 엔드포인트(mock-server)로 `CONTROL_NOTIFY_ENABLED=true` 를 켠 별도 회차에서 재검증.

### [E-ISSUE-46] 검증 한계 — 목업 비식별이 원본을 복사하므로 orgnl/deid 산출물의 **픽셀 차이**를 실증할 수 없음
- **심각도**: INFO (환경 제약)
- **현재 상태**: rawSn=26 의 `orgnl/frame-0.jpg` 와 `deid/frame-0.jpg` md5 동일(`f7d5e59c…`). 단 **경로는 상이**(`/app/storage/raw/frames/raw/26/…` vs `/app/storage/deidentified/frames/deid/26/…`) 이고, mock-server 비식별이 원본 파일을 그대로 복사하는 구현이므로 **코드 결함이 아니다**(E-ISSUE-41 의 rawSn=19 와는 다름 — 그쪽은 경로 자체가 동일).
- **영향**: "비식별 픽셀이 실제로 마스킹되었는가"는 이번 회차에서 판정 불가.
- **수정 방향(제안)**: 실 KPST 연동 환경에서 재확인.

---

## 요약

- 총 **56건** / PASS **53** / FAIL **0** / PARTIAL **2**(TC-META-008, TC-META-039) / BLOCKED 0 / N/A 0 / 확인필요 0
- 실동작 검증: 44건 / 정적·테스트만: 12건 (TC-EXPORT-003·008·009·011·013·024(부분)·025·029·031, TC-META-011·012, TC-META-039)
- **근거 라인 드리프트: 4건**
  1. TC-EXPORT-020/021 근거 `NiaJsonBuilder.java:66-100` / `:59-62` — 실제 pass-through 는 `:121`(`ctx.eventAnnotation()`), null 키 유지는 `NiaAnnotationDoc.java:26`(`@JsonInclude ALWAYS`)
  2. TC-EXPORT-020/021 **기대 키명** `event_annotation` → 실제 `event`(문서 stale, E-ISSUE-44)
  3. TC-EXPORT-026 근거 `NiaVideo.java:12` → 실제 `@JsonInclude` 는 `:13`
  4. TC-META-040 근거 `FramePrivacyMetaController.java:97-99`(=@ApiResponses 주석) → 실 검증 지점 `FramePrivacyBulkRequest.java:20-24`
  - (부수) TC-EXPORT-007 근거 `ResolutionPersistService.java:234-251` 은 **9-arg** `LsDataSrc.create` 이며 파일 경로는 `video/service/`(TC 표는 경로 미기재)
- **self-fill 결함: 1건** — `video.time_of_day` / `video.season` (E-ISSUE-42). 반대로 `weather`·`event`·`vd_description`·미보유 필드는 **원천 없을 때 null 유지**로 정상.
- 신규 이슈 6건: E-ISSUE-41(HIGH) · 42(MED) · 43(MED) · 44(LOW~MED) · 45(INFO) · 46(INFO)

### 검증 중 수행한 상태 변경 및 복원
| 대상 | 변경 | 복원 |
|------|------|:--:|
| rawSn=26 촬영환경 3필드 | 비/DAY/SPRING 저장 → 재승인(v2 산출) | **null 복원 완료** (`ls_data_raw` 3컬럼 NULL) |
| 프레임 446/447/448 개인정보 3필드 | Y/Y/Y 등 저장 | **NULL 복원 완료** |
| rawSn=11 weather | 맑음 저장(IDOR 오판 테스트) | **null 복원 완료** |
| rawSn=26 / rawSn=17 검수 상태 | submit→start→approve 1사이클 | **APPROVED 복귀 확인**(라벨 131건 불변) |
| 잔존 부산물 | `labeling/26/v2`, `labeling/17/v2` export 폴더 신규 생성(삭제 안 함) | 승인 경로 R6 계약상 정상 누적 |

