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
