# B 클러스터 part4 — `B-7. VLM 위탁 Step + 콜백 수신 + 신고 보류/재개` (36건)

- 검증일: 2026-08-01 (1차)
- 코드 기준: qa-0801 워크트리 `56d30478`
- 스택: backend(:18081, `/api`) · mock-server(:9400) · postgres(`public` 스키마) — 40시간 가동 중인 기존 컨테이너
- **환경 버전 격차 영향 없음**: B-7 관련 소스(`VlmTimeseriesStep`·`VlmResultService`·`VlmResumeBridge`·`VlmWithheldResumeRunner`·`BatchStatusService`·`DeidentReportGate`·`VlmClient`)는 `git diff --name-only b2b44f0e..HEAD` 에 **1건도 포함되지 않는다.** 논블로킹 전환 커밋 `862ca6d8`(2026-07-30)은 `git merge-base --is-ancestor 862ca6d8 b2b44f0e` = true 로 **배포된 V146 이미지에 포함**돼 있다 → 이 섹션은 전 케이스 실동작 검증 가능(BLOCKED 0건).
- 실동작 검증에 사용한 테스트 데이터(기존 데이터 미변경, rawSn=26 미접촉):
  - `rawSn=28`(QA0801-B7-01) — 신고 보류/해소 재개
  - `rawSn=35`(QA0801-B7-02) — 마킹 동반 위탁·전이·retry no-op
  - `rawSn=37`(QA0801-B7-NODEID, SQL INSERT) — 비식별 경로 부재 fail-closed
  - `rawSn=39`(QA0801-B7-RONLY, SQL INSERT) — 미결 회수 재개 경로
  - `rawSn=40`(QA0801-B7-03) — 마킹 보유 + 신고 구간 재처리
  - `ls_webhook_idempotency` 테스트 키 `QA-B7-*` 6건(SQL INSERT)

---

## 1. 판정 요약

| 판정 | 건수 |
|---|---:|
| PASS | 30 |
| PARTIAL | 4 |
| N/A | 1 |
| FAIL | 1 |
| BLOCKED | 0 |
| 확인필요 | 0 |
| **합계** | **36** |

**self-fill 결함 0건** — 시계열 메타(`LS_DATA_META`)·검수큐(`LS_DATA_META_REVIEW`)·`RESP_PAYLOAD_CN` 어느 값도 외부 왕복 없이 생성되지 않았다. mock-server 인바운드 로그(`POST /v1/videovlm/describe` → `POST /api/v1/vlm/callback`)로 왕복 실증.

---

## 2. 케이스별 판정

| ID | 판정 | 근거 확인 | 비고 |
|---|:--:|---|---|
| TC-VLM-001 | PASS | [실동작] rawSn=35: `[Batch][VlmTimeseries] describe submit rawSn=35 request_id=5ff293d4… hasMarking=true` — ctx 마킹 1건 → `runWithMarking(markings.get(0))` | 근거 드리프트: `:135-142` → 실제 `VlmTimeseriesStep.java:220-227`(`execute`) |
| TC-VLM-002 | PASS | [실동작] rawSn=28 재개 시 마킹 0건 → `hasMarking=false` 로 `run(rawSn)` 경로 | 드리프트: `:139-141` → `:224-226` |
| TC-VLM-003 | PASS | [정적] `VlmTimeseriesStep.java:269-273` → `batchStatusService.recordVlmSkipped(rawSn, SKIP_REASON_DISABLED)` 후 `skipped` 반환(외부 호출 이전). `BatchStatusService.java:58-62`. 테스트 `VlmTimeseriesStepTest#VLM_비활성일_때_LS_BATCH_PROC_LOG_에_VLM_SKIPPED_행이_사유와_함께_남는다`(baseline PASS) | 로컬 스택은 `VLM_CLIENT_ENABLED=true` 라 실동작 재현 불가(설정 변경 금지). 드리프트: `:182-186` → `:269-273` |
| TC-VLM-004 | PASS | [정적] `:261-263` — `rawSn == null` → `INVALID_INPUT`, enabled 체크보다 **앞** | 드리프트: `:174-176` → `:261-263` |
| TC-VLM-005 | PASS | [정적] `:276-278` — `existsById` false → `NOT_FOUND`. enabled 체크(269) 이후 순서 유지 | 드리프트 |
| TC-VLM-006 | PASS | [실동작] rawSn=37(성공 procLog 0건) 배치 트리거 → `ls_batch_proc_log`: `VLM/FAILED / "비식별 영상 경로가 없어 VLM describe 위탁을 진행할 수 없습니다 rawSn=37"`. 같은 시각 mock-server `describe` 인바운드 **0건**. 코드 `:390-400` 에 원본 경로 폴백 자체가 없음 | 드리프트: `:280-290` → `:390-400` |
| TC-VLM-007 | PASS | [정적] `:318-325` — `ledger.recordIssued` 예외 시 `EXTERNAL_API_ERROR` 로 abort, `vlmClient.submitTimeseries` 는 그 아래(361) | 드리프트 |
| TC-VLM-008 | PASS | [정적] `PersistentWebhookIdempotencyLedger.java:57` `@Transactional(REQUIRES_NEW)` — 독립 커밋. 실동작으로도 콜백 역조회 전건 성공 | 드리프트: `:232` → `:319` |
| TC-VLM-009 | PARTIAL | [정적+실동작] **기대결과 무효.** 논블로킹 전환 후 빈 응답은 `switchIfEmpty(Mono.error(...))`(`:364-365`) → `err` 핸들러 → `outcomeRecorder.onSubmitFailed`(SKIPPED + `SKIP_REASON_SUBMIT_FAILED` 기록)로 처리되고 **`doSubmit` 은 예외를 던지지 않고 `submitted` 를 반환**한다. "무흔적 유실 없음" 이라는 취지는 충족(테스트 `VlmTimeseriesStepTest#빈_응답도_무흔적_유실되지_않고_제출실패로_기록된다`) | → B-ISSUE-62 |
| TC-VLM-010 | N/A | [정적] **전제가 코드에 없다.** VLM 경로의 `BLOCK_TIMEOUT=45s` 상수는 `862ca6d8` 논블로킹 전환에서 삭제됐다(`grep BLOCK_TIMEOUT` → `ControlNotifyClient:52`(15s) 만 잔존). 현재 단일 타임아웃은 `vlm.client.timeout-seconds`(기본 10s, `VlmClient.java:71,108`) 하나뿐이고 `.block()` 호출 자체가 없다 | → B-ISSUE-63 |
| TC-VLM-011 | PARTIAL | [실동작] rawSn=35 마킹 `PENDING→VLM_REQUESTED→VLM_COMPLETED` 관측. 단 **전이 시점·주체가 다르다** — "위탁 성공 시 persistMarkingTransition" 이 아니라 **제출 전 선커밋**(`:337 markingTxService.persistVlmRequested`)이고, 별도 빈 `VlmMarkingTxService`(REQUIRES_NEW)가 `save(=merge)` 를 수행한다 | → B-ISSUE-64 |
| TC-VLM-012 | PASS | [실동작] rawSn=35 재트리거(19:15:59) 후에도 `ls_marking.stts_cd=VLM_COMPLETED`, `mdfcn_dt=19:15:32.389` **불변** → `markVlmRequested()` no-op, save 미호출(durable 역행 차단). 코드 `VlmMarkingTxService.java:51-60` | 드리프트: `VlmTimeseriesStep:300-308` → `VlmMarkingTxService:51-60` |
| TC-VLM-013 | PASS | [실동작+정적] mock-server 인바운드 `[MOCK][VLM] describe accepted request_id=415f1fa9… callback_url=http://klid-backend:8080/api/v1/vlm/callback` — 고정 base + `HmacWebhookFilter.PATH_VLM`. 코드 `:402-411` 에 사용자 입력 반영 지점 없음 | 드리프트: `:311-319` → `:402-411` |
| TC-VLM-014 | PASS | [실동작] `POST /v1/vlm/callback {"request_id":"QA-UNKNOWN-0001"}` → **401** `{"errorCode":"UNAUTHORIZED","message":"발급되지 않은 request_id 입니다."}` |  |
| TC-VLM-015 | PASS | [실동작] PROCESSED 키 재전송 → **200** `{"applied":false}`, `ls_data_meta` 중복 적재 0건 |  |
| TC-VLM-016 | PASS | [실동작] `QA-B7-NORAW`(raw_sn NULL, ISSUED) → **401** "request_id 에 매핑된 rawSn 이 없습니다." |  |
| TC-VLM-017 | PASS | [실동작] `status:"done"` → **400** `"status 는 completed\|failed 중 하나여야 합니다."`(DTO `@Pattern` 1차 차단). 서비스 분기(`VlmResultService.java:94-102`)도 화이트리스트 후 `INVALID_INPUT` + 멱등 미마킹 |  |
| TC-VLM-018 | PASS | [실동작] `QA-B7-ACCEPTED` failed 콜백 → 200 `applied=true`, ledger `PROCESSED`, WARN `describe failed … code=E1`. 코드 `:105-109`, `handleFailed :210-232` | 드리프트: `:168-186` → `:210-232` |
| TC-VLM-019 | PASS | [정적] `:112-116` — completed + `existsById` false → `NOT_FOUND`. FK(`fk_ls_webhook_idempotency_raw`) 때문에 실행 중 스택에서는 "존재하지 않는 rawSn 매핑" 자체를 만들 수 없어 라이브 재현 불가(미구현 갭 아님) |  |
| TC-VLM-020 | PASS | [실동작] `QA-B7-DUPKEY` 로 `0-5` 두 번 → **400** "한 콜백 내 중복 구간…". 직후 ledger `stts_cd` 는 **ISSUED 유지**(PROCESSED 미마킹 = 재전송 허용) | 드리프트: `:192-204` → `:238-250` |
| TC-VLM-021 | PASS | [실동작] `QA-B7-UPSERT` 로 기존 `0-5` + 신규 `5-9` 동시 전송 → `ls_data_meta` 0-5 값만 갱신(`UPDATED-EXISTING`), 5-9 신규(meta_sn=134). `ls_data_meta_review` 는 **신규분만 1행 추가**(sn=19), 기존 0-5 리뷰행(sn=16) 미증가 |  |
| TC-VLM-022 | PASS | [실동작] rawSn=35 콜백 로그 `markingsTransitioned=1`, `ls_marking.stts_cd=VLM_COMPLETED` | 드리프트: `:156` → `:158-161` |
| TC-VLM-023 | PASS | [실동작+정적] 단일 `@Transactional`(`:63`) + `markProcessedInTx`(`:164`, REQUIRED). TC-VLM-020 에서 중간 예외 후 ledger 가 PROCESSED 로 넘어가지 않음을 DB 로 확인. 테스트 `VlmResultServiceTest#검수큐_적재_중_예외_시_원장_PROCESSED_전이도_함께_롤백_재전송_복구가능` | 드리프트: `:160` → `:164` |
| TC-VLM-024 | PASS | [실동작] `QA-B7-RACE` 동일 request_id 2건 **동시** POST → 응답 `applied=true` / `applied=false`, `ls_data_meta`(raw_sn=37) 1행·`ls_data_meta_review` 1행. `lookupForProcessing` 비관적 락으로 직렬화 |  |
| TC-VLM-025 | PASS | [실동작] `message":"vendor failed\nsecond line"` 전송 → 로그 `message=vendor failed_second line`(CR/LF/TAB → `_`) |  |
| TC-VLM-030 | PASS | [실동작] HMAC 헤더 없이 콜백 → 필터가 size cap 만 적용하고 통과시키되 `request_id` 발급 게이트가 **401** 로 차단(TC-VLM-014 와 동일 응답). `HmacWebhookFilter.java:110 PATH_VLM` |  |
| TC-VLM-031 | PASS | [실동작] rawSn=28 `DE_IDENT_YN='F'` 상태로 배치 트리거 → `[Batch][VlmTimeseries] withheld — deident report open rawSn=28` + `ls_batch_proc_log`(`VLM/SKIPPED/"비식별 누락 신고 구간 — VLM 위탁 보류(재비식별 대기)"`). 같은 시각 mock-server `describe` 인바운드 **0건**. 게이트는 `resolveDeidentifiedPath`(`:309`) **이전**(`:303`) | 드리프트: `:216-220,222` → `:303-307` |
| TC-VLM-032 | FAIL | [실동작] rawSn=40(마킹 1건 보유, `'F'`) 재처리 → VLM 은 정상 보류(예외 0)였으나 **직후 `FRAME_EXTRACT` 가 같은 `'F'` 조건을 예외로 처리**해 배치가 FAILED 전이 + `BatchRetryQueue enqueued attempt=1 delaySec=60` + `ls_data_raw.data_stts_cd=FAILED`. 기대("배치 FAILED 미전이, 재시도 큐 미등록, 시도 상한 미소진")는 **신고 구간 재처리 시나리오에서 성립하지 않는다** | → B-ISSUE-61 |
| TC-VLM-033 | PASS | [정적] `DeidentReportGate.isUnderDeidentReport`(`:66-71`)는 조회 예외를 삼키지 않고 전파, `VlmTimeseriesStep:303` 이 그 앞이라 전송 코드 도달 불가(fail-closed) | DB 오류 주입 불가 — 정적 판정 |
| TC-VLM-034 | PASS | [실동작] `POST /v1/deident-reports/8/resolve` 200 → `[VlmResumeBridge] deident gate reopened rawSn=28` → `[VlmResume] resuming withheld VLM submit rawSn=28` → `describe submit … request_id=415f1fa9…` → mock accepted → 콜백 `result applied … new=1`. `VlmResumeBridge.java:34-38`(AFTER_COMMIT), `VlmWithheldResumeRunner.java:57-72` | 드리프트: Runner `:54-70` → `:57-72` |
| TC-VLM-035 | PASS | [실동작] 같은 영상에 2차 신고(rprtSn=10) → resolve → `[VlmResume] resume skipped — timeseries meta already present rawSn=28 count=1`, mock-server `describe` 인바운드 **0건**. `VlmWithheldResumeRunner.java:88-101` | 드리프트: `:79-93` → `:88-101` |
| TC-VLM-036 | PASS | [정적] `SKIP_REASON_DISABLED` 는 `RESUMABLE_SKIP_REASONS`(`VlmTimeseriesStep.java:150-152`)에 **미포함** → `isStageSkippedWithAnyReason` false → 재개 대상 아님. 테스트 `VlmWithheldResumeRunnerTest#보류_기록이_없으면_재위탁하지_않는다`·`재개_사유_목록에_비동기_제출실패와_ACK_미수신이_포함된다` | **단일 원천이 확장됨**: 이제 사유가 4종(신고 보류·제출실패·ACK 미수신·콜백 미수신)이고 판정 API 가 `isStageSkippedWithReason`(`BatchStatusService:100-105`) → `isStageSkippedWithAnyReason`(`:114-119`) 로 바뀜 |
| TC-VLM-037 | PASS | [정적] `VlmWithheldResumeRunner.java:73-78` — `catch (RuntimeException)` → `log.warn(… cause={클래스명만})`, 보류 기록·메타0 조건은 그대로 남음 | 드리프트: `:71-76` → `:73-78` |
| TC-VLM-038 | PASS | [정적] `BatchStatusService.java:149-156` `@Transactional(REQUIRES_NEW) recordVlmTimeseriesResult` — 스텝 롤백과 무관하게 커밋 | 드리프트: `:109-116` → `:149-156` |
| TC-VLM-039 | PASS | [정적] `recordVlmSkipped`(`:58-62`)는 여전히 REQUIRED. 다만 비동기 완료 핸들러 전용으로 **형제 메서드 `recordVlmSkippedInNewTx`(REQUIRES_NEW, `:78-82`)가 신설**됐고 공통 로직은 무트랜잭션 private 헬퍼(`:85-88`)로 공유 | 드리프트: `:104-108` → `:78-82` |
| TC-VLM-040 | PARTIAL | [정적+실동작] `execute` 에 `@Transactional(REQUIRES_NEW)`(`:218-227`) 확인, 내부 분기는 자기호출이라 중첩 없음 — 여기까지는 기대대로이며 `execute` 경유 SKIPPED 감사 행 정상 커밋(rawSn=28/40). **그러나 `run`(`:236`)의 `readOnly=true` 가 프록시 경유 호출자(`VlmWithheldResumeRunner:69`)에서 발효돼 `recordVlmSkipped` INSERT 가 `cannot execute INSERT in a read-only transaction` 으로 실패함을 실동작 재현**(rawSn=39) | 드리프트: `:133-142,150-168` → `:218-227,236-255`. → B-ISSUE-66 |

---

## 3. 실동작 재현 로그 (요약)

```
# 1) 신고 → 보류
POST /api/v1/videos/28/deident-report          → 201, rprtSn=8, ls_data_raw.de_ident_yn='F'
POST /api/v1/dev/batch/trigger?rawSn=28        → [VlmTimeseries] withheld — deident report open rawSn=28
ls_batch_proc_log: VLM / SKIPPED / "비식별 누락 신고 구간 — VLM 위탁 보류(재비식별 대기)"
mock-server describe 인바운드: 0건

# 2) 해소 → 재위탁 (mock 왕복 실증)
POST /api/v1/deident-reports/8/resolve         → 200
[VlmResumeBridge] deident gate reopened rawSn=28
[VlmResume] resuming withheld VLM submit rawSn=28
[Vlm] describe submit request_id=415f1fa9-…    → [MOCK][VLM] describe accepted … callback_url=http://klid-backend:8080/api/v1/vlm/callback
[MOCK][VLM] media duration probed file=2dc0d17d-…-mask.mp4   ← 비식별본(-mask) 경로만 전송됨(PII 원본 미전송)
[Webhook][Vlm] result applied request_id=415f1fa9-… rawSn=28 new=1
ls_data_meta(meta_sn=125, meta_key='0-5') + ls_data_meta_review(sn=16, PENDING) 적재

# 3) 재개 멱등
2차 신고(rprtSn=10) → resolve → [VlmResume] resume skipped — timeseries meta already present rawSn=28 count=1 (describe 0건)
```

### PII 축 반증 결과
- `media.path` 는 **항상 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`** 값이다. mock-server 가 실제로 연 파일명이 `…-mask.mp4`(KPST 산출물 규격)임을 3건 모두에서 확인했고, 코드에는 원본 경로 폴백 분기 자체가 없다(`VlmTimeseriesStep.java:390-400`).
- 신고 구간에서 외부로 나간 요청 **0건**(rawSn=28·40 두 케이스 모두 mock-server 인바운드 없음).

### self-fill 반증 결과
| 값 | 위치 | 판정 |
|---|---|---|
| 시계열 메타 텍스트 | `ls_data_meta.meta_vl` | self-fill 아님 — `POST /v1/videovlm/describe` → `POST /v1/vlm/callback` 실 HTTP 왕복 후 적재(mock-server 로그 대조) |
| `RESP_PAYLOAD_CN`(requestId/status) | `ls_batch_proc_log` | self-fill 아님 — ACK 수신(`VlmSubmitOutcomeRecorder.onAccepted`) 시에만 기록, 미수신 시 공란 유지 |
| 검수큐 PENDING 행 | `ls_data_meta_review` | self-fill 아님 — 콜백으로 신규 생성된 meta 에 대해서만 생성(기존 meta 갱신 시 미생성 실측) |

---

## 4. 이슈

### [B-ISSUE-61] TC-VLM-032 — 신고 구간 배치 재처리가 VLM 은 보류하지만 FRAME_EXTRACT 에서 실패해 배치 FAILED + 재시도 예산을 소진한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 비식별 누락 신고는 **정책적 차단**이므로 "실패가 아니라 보류"여야 한다(`CLAUDE.md` — "예외로 실패시키면 ①정책적 차단이 장애로 오분류 ②BatchRetryQueue 가 반드시 다시 막힐 재시도로 시도 상한을 소진 ③작업 상태가 FAILED 로 내려가 라벨링·검수 동선이 끊긴다"). TC-VLM-032 도 "예외 미발생 → 배치 FAILED 미전이, 재시도 큐 미등록, 시도 상한 미소진"을 요구한다.
- **현재 동작(이슈 내용)**: VLM 스텝은 규약대로 보류(SKIPPED, 예외 0)한다. 그러나 **바로 다음 단계인 `FfmpegFrameExtractor` 가 동일한 `'F'` 상태를 예외로 처리**한다.
  ```java
  // backend/.../batch/step/FfmpegFrameExtractor.java:185-188
  if (!DEIDENTIFIED.equals(raw.getDeIdntfYn())) {          // DEIDENTIFIED = "Y" → 'F' 도 실패
      throw new CustomException(ErrorCode.INVALID_INPUT,
              "비식별이 완료되지 않은 영상입니다 rawSn=" + raw.getRawSn());
  }
  ```
  실측(rawSn=40, 마킹 1건 보유, `DE_IDENT_YN='F'`):
  ```
  [Batch][VlmTimeseries] withheld — deident report open rawSn=40      ← 보류(정상)
  [BatchRetry] enqueued rawSn=40 attempt=1 delaySec=60                ← 재시도 큐 등록(기대 위반)
  [BatchOrchestrator] failed rawSn=40 willRetry=true cause=CustomException
  ls_batch_proc_log: FRAME_EXTRACT / FAILED / "비식별이 완료되지 않은 영상입니다 rawSn=40"
  ls_data_raw.data_stts_cd: MARKING_READY → FAILED
  ```
  `authoring.batch.retry.max-attempts:3` + 지수 백오프(60/120/240초)이므로 **신고가 약 7분 이상 열려 있으면 재시도 예산이 전량 소진(EXHAUSTED)** 되고, 해소(resolve) 시 자동 복구 트리거는 VLM 재개(`DeidentGateReopenedEvent`)뿐이라 프레임추출·오토라벨은 되살아나지 않는다(수동 `POST /v1/videos/{rawSn}/batch/retry` 필요 — `BatchReprocessService:84 retryQueue.clearIfIdle`).
- **재현/확인 경로**:
  ```bash
  # 마킹을 보유한 MARKING_READY 영상에 신고를 걸고 재처리
  curl -X POST .../v1/videos/{rawSn}/deident-report -d '{"reason":"..."}'
  curl -X POST ".../v1/dev/batch/trigger?rawSn={rawSn}"   # 또는 POST /v1/videos/{rawSn}/batch/retry
  psql -c "select proc_step_cd, proc_stts_cd, err_msg_cn from ls_batch_proc_log where data_raw_sn={rawSn};"
  psql -c "select * from ls_bat_rty_wtng where raw_sn={rawSn};"
  ```
- **영향**: 기능/운영. ①신고 구간에 자동 재시도가 반드시 실패하며 예산을 태운다 ②영상이 `FAILED` 로 표시돼 운영자가 장애로 오인한다 ③해소 후 프레임추출~오토라벨이 자동 복구되지 않아 수동 개입이 필요하다. 보안 유출은 없다(외부 전송은 정상 차단됨).
- **수정 방향(제안)**: `FfmpegFrameExtractor` 의 비식별 선행 가드를 **`'N'`(미수행)만 실패**로 두고 `'F'`(신고)는 VLM 과 동일하게 **보류(SKIPPED + 사유 적재)** 로 분기하거나, `BatchOrchestrator`/`BatchPipelineConfig` 의 `isEnabled(ctx)` 로 신고 구간에서는 post-marking 단계를 통째로 건너뛰고 재시도 큐에 넣지 않게 한다. 어느 쪽이든 해소 시 `DeidentGateReopenedEvent` 소비자를 늘려 프레임추출 이후 단계도 재개돼야 한다(현재는 VLM 재개만 배선).

### [B-ISSUE-62] TC-VLM-009 — 빈 describe 응답의 기대결과가 논블로킹 전환으로 무효화됨(카탈로그 정합)
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그가 실제 코드의 유일한 진실원과 일치해야 다음 회차 판정이 성립한다.
- **현재 동작(이슈 내용)**: 케이스는 "결과 null → `doSubmit` 이 `EXTERNAL_API_ERROR`" 를 요구하나, `862ca6d8`(2026-07-30, 논블로킹 전환) 이후 코드는
  ```java
  // VlmTimeseriesStep.java:362-370
  vlmClient.submitTimeseries(req)
      .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR, ...)))
      .subscribe(resp -> …onAccepted(…), err -> …onSubmitFailed(rawSn, markingSn, err));
  return VlmTimeseriesResponse.submitted(requestId);   // 예외 미전파
  ```
  로 바뀌어, 빈 응답은 **비동기 실패 기록(`SKIP_REASON_SUBMIT_FAILED`)** 이 되고 스텝은 `submitted` 를 반환한다.
- **재현/확인 경로**: `grep -n "switchIfEmpty" VlmTimeseriesStep.java` / 테스트 `VlmTimeseriesStepTest#빈_응답도_무흔적_유실되지_않고_제출실패로_기록된다`.
- **영향**: 카탈로그 정합(거짓 FAIL 유발).
- **수정 방향(제안)**: 기대결과를 "예외 미전파 + `VlmSubmitOutcomeRecorder.onSubmitFailed` 로 `LS_BATCH_PROC_LOG` 에 `SKIP_REASON_SUBMIT_FAILED` 적재 + 재개 대상 편입" 으로 갱신.

### [B-ISSUE-63] TC-VLM-010 — `BLOCK_TIMEOUT=45s` 이중 타임아웃 구조가 코드에서 사라짐(카탈로그 정합)
- **심각도**: LOW
- **기대 동작(기대효과)**: 동상.
- **현재 동작(이슈 내용)**: VLM 경로에 `.block()` 호출과 `BLOCK_TIMEOUT` 상수가 모두 없다. `grep -rn "BLOCK_TIMEOUT" backend/src/main` → `ControlNotifyClient.java:52`(15s) 1곳뿐. 현재 VLM 의 유일한 시간 상한은 `vlm.client.timeout-seconds`(기본 10s, `VlmClient.java:71` → `.timeout(timeout)` `:108`)이고, 그 위에 Resilience4j Retry/CircuitBreaker 가 걸린다.
- **재현/확인 경로**: `grep -rn "BLOCK_TIMEOUT\|\.block(" backend/src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java` → 0건.
- **영향**: 카탈로그 정합. 겸하여 `UNCERTAINTIES.md` #13 의 "타임아웃 이중 구조(45s↔10s)" 기술도 갱신 대상이다.
- **수정 방향(제안)**: 케이스를 "단일 실효 타임아웃 10s + Retry/CB, 파이프라인 스레드 미점유" 로 재작성하고 UNCERTAINTIES #13 을 함께 정정.

### [B-ISSUE-64] TC-VLM-011 — 마킹 전이 시점이 "위탁 성공 후" → "제출 전 선커밋" 으로 반전(카탈로그 정합)
- **심각도**: LOW
- **기대 동작(기대효과)**: 동상.
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep.java:337` 이 **외부 제출 이전**에 `markingTxService.persistVlmRequested(marking)`(별도 빈, `REQUIRES_NEW`)를 호출해 선커밋한다. 이유는 논블로킹 제출에서 콜백이 ACK 보다 먼저 도착하는 레이스(수신부가 전이 대상을 못 찾아 `VLM_REQUESTED` 영구 고착)를 닫기 위함이며, 수신부도 `ACTIVE_STATUSES`(PENDING 포함)로 범위를 넓혀 양단 방어한다(`VlmResultService.java:153-158`). 카탈로그의 `persistMarkingTransition`(`VlmTimeseriesStep:300-308`)은 존재하지 않는다.
- **재현/확인 경로**: `VlmTimeseriesStep.java:327-338`, `VlmMarkingTxService.java:47-60`.
- **영향**: 카탈로그 정합.
- **수정 방향(제안)**: 기대결과를 "제출 전 `VlmMarkingTxService.persistVlmRequested` 로 독립 커밋(detached → merge), PENDING 에서만 전이" 로 갱신.

### [B-ISSUE-65] B-7 섹션 전체가 논블로킹 전환(`862ca6d8`) 이전 코드 기준 — 근거 드리프트 34/36 + 신규 기계 케이스 부재
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그의 `file:line` 근거로 다음 회차가 대조 가능해야 하고, 실제 운영 중인 코드 경로에 케이스가 존재해야 한다.
- **현재 동작(이슈 내용)**: B-7 은 `HEAD 11c3e1b8` 기준으로 작성됐는데, 같은 날(2026-07-30) 병합된 `862ca6d8 refactor(integration): 외부연동 제출 3곳 논블로킹화` 가 이 파일들을 크게 재작성했다. 그 결과 **36건 중 34건의 근거 라인이 어긋난다**(위 표 비고 열). 더해 신설된 아래 기계에 대응하는 케이스가 **0건**이다:
  - `VlmSubmitOutcomeRecorder`(ACK 기록 + 원장 `ISSUED→ACCEPTED` 전이 + 상태 강등 금지) — `batch/step/VlmSubmitOutcomeRecorder.java`
  - `VlmSubmitPendingSweeper`(미결 회수, `ackWindowMin=30` / `callbackWindowMin=360`, `max-reclaims=3`) — `batch/vlm/`
  - 신규 재개 사유 3종 `SKIP_REASON_SUBMIT_FAILED` / `SKIP_REASON_ACK_MISSING` / `SKIP_REASON_CALLBACK_MISSING` 과 `RESUMABLE_SKIP_REASONS` 목록
  - 원장 3상태(`ISSUED`/`ACCEPTED`/`PROCESSED`)와 "비-PROCESSED 는 전부 발급됨으로 매핑" 불변식
  - `SubmitSignalDispatch`(전용 풀 거부 시 이벤트 루프 오염 차단)
  실동작 중 이 경로가 살아있음을 확인했다 — 로그 `[Vlm][Reclaim] pending VLM submit reclaimed=2 ackWindowMin=30 callbackWindowMin=360` 및 `ls_batch_proc_log` 의 `VLM/SKIPPED/"VLM describe 수락 응답·콜백 미수신 — 미결 회수 후 재개"` 2행(rawSn=28).
- **재현/확인 경로**: `git log --oneline -- backend/.../VlmTimeseriesStep.java` → `862ca6d8`(2026-07-30); `ls backend/src/test/java/kr/co/cudo/authoring/batch/vlm/` → `VlmSubmitAckWindowIT`·`VlmSubmitPendingSweeperTest`·`VlmSubmitReclaimAtomicClaimIT`(전부 baseline PASS인데 카탈로그 케이스가 없음).
- **영향**: 카탈로그 정합 + 커버리지 갭. 논블로킹 전환의 핵심 회수 경로가 검증 카탈로그에서 누락돼 있다.
- **수정 방향(제안)**: B-7 근거 라인을 `56d30478` 기준으로 일괄 재산출하고, 위 5개 기계에 대한 신규 케이스(TC-VLM-041~ )를 추가한다. 기존 테스트 자산(`batch/vlm/*`·`VlmTimeseriesStepNonBlockingTest`·`VlmSubmitOutcomeRecorderTest`)과 1:1 매핑 가능하다.

### [B-ISSUE-66] `VlmTimeseriesStep.run` 의 `readOnly=true` 때문에 재개 경로에서 보류 감사 기록이 `cannot execute INSERT in a read-only transaction` 으로 실패한다 (실동작 재현)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 재개 러너가 호출한 위탁이 **다시 보류**(게이트가 아직 닫혀 있음)되거나 비활성으로 끝나면 그 사실이 `LS_BATCH_PROC_LOG` 에 사유와 함께 남아야 한다(B-ISSUE-24 규약 — "재처리 대상 식별이 애플리케이션 로그 보존기간에 종속되면 운영에서 복구 불가"). 코드 주석도 `VlmWithheldResumeRunner:45` 에서 "게이트가 아직 닫혀 있으면 스스로 다시 보류된다" 를 전제한다.
- **현재 동작(이슈 내용)**: `run` 은 `@Transactional(REQUIRES_NEW, readOnly = true)`(`VlmTimeseriesStep.java:236`)이고 그 안에서 호출되는 `BatchStatusService.recordVlmSkipped` 는 `REQUIRED`(`:58-62`)라 **읽기전용 트랜잭션에 참여**한다. Spring/Hibernate 가 JDBC 커넥션을 read-only 로 설정하므로 PostgreSQL 이 INSERT 를 거부한다. 파이프라인 경로(`execute` → 자기호출, `execute` 는 readOnly 아님)는 영향이 없지만(rawSn=28/40 감사 행 정상 커밋 확인), **`VlmWithheldResumeRunner` 는 프록시 경유로 `run` 을 직접 호출**(`VlmWithheldResumeRunner.java:69`)하므로 이 경로에서만 읽기전용 경계가 실제로 발효된다.
  ```java
  // VlmTimeseriesStep.java:236
  @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public VlmTimeseriesResponse run(Long rawSn) { return doSubmit(rawSn, null); }
  // → doSubmit:271(DISABLED), :305(DEIDENT_REPORT) 에서 batchStatusService.recordVlmSkipped(...) (REQUIRED, INSERT)
  ```
  **실동작 재현 로그**(rawSn=39, `DE_IDENT_YN='F'` 유지 상태에서 미결 스위퍼가 재개 트리거):
  ```
  19:31:22.196 [vlm-submit-reclaim] [Batch] stage skipped recorded rawSn=39 stage=VLM      ← 스위퍼(REQUIRES_NEW) 는 성공
  19:31:22.201 [batch-async-1] [VlmResume] deident report resolved — resuming withheld VLM submit rawSn=39
  19:31:22.202 [batch-async-1] [Batch][VlmTimeseries] withheld — deident report open rawSn=39
  19:31:22.203 [batch-async-1] ERROR SqlExceptionHelper - ERROR: cannot execute INSERT in a read-only transaction
  19:31:22.204 [batch-async-1] WARN  [VlmResume] withheld VLM resume failed rawSn=39 cause=JpaSystemException
  ```
  결과적으로 **재보류 감사 행이 적재되지 않았다**(`ls_batch_proc_log` where data_raw_sn=39 → 스위퍼가 쓴 `ACK_MISSING` 1행만 존재, `DEIDENT_REPORT` 행 0건).
- **재현/확인 경로**:
  ```sql
  -- 게이트가 닫힌(F) 영상 + 시계열 메타 0건 + 재개 대상 SKIPPED 행이 있는 상태를 만든 뒤 재개를 트리거
  insert into ls_data_raw (...) values (..., de_ident_yn='F', ...);          -- rawSn=N
  insert into ls_webhook_idempotency (idmp_key,chnl_cd,stts_cd,reg_dt,mdfcn_dt,raw_sn)
         values ('QA-RO','VLM','ISSUED', now(), now(), N);                    -- ACK 창(30분) 경과로 보이게
  -- VlmSubmitPendingSweeper tick(기본 15분) 또는 POST /v1/deident-reports/{n}/resolve 로 재개 트리거
  select proc_step_cd, proc_stts_cd, err_msg_cn from ls_batch_proc_log where data_raw_sn = N;
  ```
- **영향**: 데이터정합/운영. ①재보류 사실이 DB 에 남지 않아 B-ISSUE-24 가 세운 "로그 보존기간 비종속" 보장이 이 경로에서 깨진다 ②재개가 `JpaSystemException` 으로 중단되며 ERROR 스택이 남아 실장애와 구분이 어렵다 ③`vlm.client.enabled=false` 인 dev/stg/prd 기본 형상에서는 **모든** 재개(`run` 분기)가 이 예외로 끝난다 — 그 경우 `SKIP_REASON_DISABLED` 행도 남지 않는다. 다만 기존 보류 기록과 "메타 0건" 조건이 남아 다음 회수 때 재시도되므로 영구 정체는 아니다(`max-reclaims=3` 소진 시까지).
- **수정 방향(제안)**: ①`run` 의 `readOnly=true` 를 제거해 `runWithMarking`(쓰기 가능)과 경계 속성을 일치시키거나, ②`doSubmit` 의 `recordVlmSkipped` 호출을 이미 존재하는 `recordVlmSkippedInNewTx`(`BatchStatusService:78-82`, REQUIRES_NEW)로 교체한다. ②가 비동기 완료 핸들러·`ledger.recordIssued` 와 규약이 같아 일관적이다. 회귀 가드로 "프록시 경유 `run` 호출 시 SKIPPED 감사 행이 실제로 커밋된다" 는 IT 를 추가할 것(현행 `VlmTimeseriesStepTest` 는 mock 기반이라 이 경계를 잡지 못한다).

---

## 5. 근거 드리프트 목록(요약 — 상세는 §2 비고)

| 케이스 | 카탈로그 근거 | 실제 위치 |
|---|---|---|
| TC-VLM-001/002 | `VlmTimeseriesStep.java:135-142` | `:218-227` |
| TC-VLM-003 | `:182-186` · `BatchStatusService:57-64` | `:269-273` · `:58-62` |
| TC-VLM-004/005 | `:174-176` · `:189-191` | `:261-263` · `:276-278` |
| TC-VLM-006 | `:280-290` | `:390-400` |
| TC-VLM-007/008 | `:231-238` · `:232` | `:318-325` · `:319` |
| TC-VLM-009/010 | `:247-250` · `:78,246` | 소멸(§4 B-ISSUE-62/63) |
| TC-VLM-011/012 | `:262,300-308` | `:337` · `VlmMarkingTxService:47-60` |
| TC-VLM-013 | `:311-319` | `:402-411` |
| TC-VLM-018 | `VlmResultService:168-186` | `:210-232` |
| TC-VLM-020 | `VlmResultService:192-204` | `:238-250` |
| TC-VLM-022/023 | `:156` · `:160` | `:158-161` · `:164` |
| TC-VLM-031/032/033 | `:216-220,222` · `:206-220` · `:215-216` | `:303-307` |
| TC-VLM-034/035/037 | Runner `:54-70` · `:79-93` · `:71-76` | `:57-72` · `:88-101` · `:73-78` |
| TC-VLM-036 | Runner `:80-84` · `BatchStatusService:74-78` | `:89-90` · `:114-119`(API 교체) |
| TC-VLM-038/039 | `BatchStatusService:109-116` · `:104-108` | `:149-156` · `:78-82` |
| TC-VLM-040 | `:133-142,150-168` | `:218-227,236-255` |

> TC-VLM-014~017·019·021·024·025·030 의 `VlmResultService` 근거는 대체로 유효(±2줄 이내).

---

## 6. 남긴 테스트 데이터(정리 참고)

| 대상 | 키 |
|---|---|
| `ls_data_raw` | 28(QA0801-B7-01) · 35(QA0801-B7-02) · 37(QA0801-B7-NODEID) · 39(QA0801-B7-RONLY) · 40(QA0801-B7-03) |
| `ls_webhook_idempotency` | `QA-B7-DUPKEY` · `QA-B7-NORAW` · `QA-B7-UPSERT` · `QA-B7-FAILED` · `QA-B7-ACCEPTED` · `QA-B7-RACE` · `QA-B7-RO` |
| `ls_deident_report` | rprtSn 8·10(rawSn=28, RESOLVED) · 14(rawSn=40, OPEN) |
| 기타 | rawSn=28 비식별 산출물 mtime 을 `touch` 로 갱신(외부 재비식별 시뮬레이션 — resolve 게이트 통과 목적) |

> ⚠ 위 데이터는 검증 재현을 위해 남겨 두었다. rawSn=26 및 기존 데이터는 일절 변경하지 않았다.
