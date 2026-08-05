# B 클러스터 part2 — B-5(마킹 완료 브릿지) + B-7(VLM 위탁·콜백·보류/재개) 검증 결과

- 회차: 2026-08-03 3차 · 담당 범위: `docs/test-cases/B-batch-deidentify.md` **B-5(TC-BATCH-050~062, 13건)** + **B-7(TC-VLM-001~040, 36건)** = **49건**
- 검증 방식: 풀스택 실동작(backend :18081/api · mock-server :9400 · PostgreSQL) + 코드 정적 대조. 빌드/테스트 미실행, 프로덕션 코드 미수정.
- 신규 구동 데이터(이번 회차 생성): rawSn **103**(B-5 skip) · **104**(신고→보류→해소 재위탁) · **105**(미결 회수/readOnly 재현) · **106**(정상 VLM 왕복 + 콜백 케이스) · **109**(신고 구간 재처리) · **113**(비식별 경로 부재 fail-closed) · **115**(ASSIGNED 클레임). rawSn=101/102 는 조회만.

## 0. 판정 집계

| 판정 | 건수 |
|---|---:|
| PASS | 46 |
| FAIL | 1 |
| PARTIAL | 1 |
| BLOCKED | 1 |
| 합계 | **49** |

- FAIL 1건 = TC-VLM-032(1차 **B-ISSUE-61 미해소** — 실동작 재현)
- PARTIAL 1건 = TC-VLM-039(1차 **B-ISSUE-66 미해소** — 실동작 재현)
- BLOCKED 1건 = TC-BATCH-055(2노드 동시 INSERT 경합 필요)
- 카탈로그 정정: **11건**(B-5 6건 · B-7 5건, 아래 §4)

---

## 1. B-5. 마킹 완료 브릿지 (13건)

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-050 | PASS | [정적] `MarkingBatchBridge.java:113-119` findById empty → WARN + `skip()`. [실동작] 미존재 rawSn 마킹은 API 가 먼저 `NOT_FOUND`(REVIEWER) 로 막아 브릿지 가드는 방어심도 — `POST /v1/videos/99999999/markings` → `{"errorCode":"NOT_FOUND"}` |
| TC-BATCH-051 | PASS | [실동작] stage=COMPLETED 인 rawSn=115 재마킹 → **412** `이미 처리된 영상은 재마킹할 수 없습니다`(프리컨디션 선차단). [정적] `:91-92,125-130` SKIP_BATCH_STAGES={PROCESSING,COMPLETED} 가드 존재 |
| TC-BATCH-052 | PASS | [실동작] rawSn=103 을 `de_ident_yn='N'` 로 두고 마킹 → **412** `비식별이 완료된 영상에서만 마킹할 수 있습니다`(선차단, 원복 완료). [정적] `:134-139` deid 가드 + `skip(REASON_NOT_DEIDENTIFIED)` |
| TC-BATCH-053 | PASS | [실동작] rawSn=115(작업상태 **ASSIGNED**) 마킹 → 응답 `batchTriggered=true`, 로그 `[MarkingBatchBridge] enqueued rawSn=115` (tx1 `tryClaimBatchQueued` 성공 경로) |
| TC-BATCH-054 | PASS | [실동작] rawSn=106(**미배정 — 작업상태 row 부재**) 마킹 → `batchTriggered=true`, `ls_raw_data_status` 신규 행 생성 후 배치 진행(tx1 false → tx2 `tryCreateBatchQueuedRow`) |
| TC-BATCH-055 | BLOCKED | 단일 노드에서는 재현 불가 — 같은 rawSn 의 동시 마킹은 V142 부분 유니크가 먼저 409 로 막아 **이벤트가 1건만 발생**한다. `saveAndFlush` + 호출부 `catch(DataIntegrityViolationException)`(`:154-160`)은 정적 확인 |
| TC-BATCH-056 | PASS | [실동작] 클레임 SQL(`transitionToBatchQueuedIfNotSkipped`, `LsRawDataStatusRepository:63-68`)을 rawSn=115 에 그대로 2회 실행 → **UPDATE 1 / UPDATE 0**(check-and-set 직렬화 확인, ASSIGNED 로 원복) |
| TC-BATCH-057 | PASS | [실동작] rawSn=103(작업상태 **PENDING**=검수 소유) 마킹 → 201 + `batchTriggered=false`, `batchSkipReason="검수 진행/완료(또는 반려) 상태이거나 …"`, 로그 `batch already claimed/in-progress or review-owned rawSn=103 — skipping` |
| TC-BATCH-058 | PASS | [실동작] 같은 103 경로에서 tx1 false → tx2 `existsById`=true → false(멱등 스킵) 후 skip 로그 1회. `BatchTransitionService.java:299-314`(근거 드리프트 정정) |
| TC-BATCH-059 | PASS | [실동작] rawSn=106 에서 신규 row `saveAndFlush` 생성 성공(즉시 flush). UK 위반 전파 분기는 TC-BATCH-055 와 같은 사유로 2노드 필요(정적 확인) |
| TC-BATCH-060 | PASS | [정적] `sanitize()` `:196-198` 존재. **주입 표면 없음**을 실측 확인 — `de_ident_yn` 은 `character(1)`, `data_stts_cd` 는 비교 통과 시 고정 상수(PROCESSING/COMPLETED)라 CR/LF 값이 로그로 흐를 수 없다(방어심도) |
| TC-BATCH-061 | PASS | [정적] `MarkingBatchBridge:76-81` 이 `BatchTransitionService.REVIEW_OWNED_STATUSES`(`:77-81`)를 그대로 합성. [실동작] 103(PENDING)이 입구에서 차단된 것이 그 근거 |
| TC-BATCH-062 | PASS | [실동작] skip 시 `batchTriggered=false`+사유, 정상 시 `batchTriggered=true`+`batchSkipReason=null`. `MarkingService.java:108,135-142` |

### ★ 1차 B-ISSUE-41(영구 고아 활성 마킹 → 재마킹 409 영구 잠금) — **해소 확인**

```
POST /v1/videos/103/markings  → 201 markingSn=71, batchTriggered=false
로그 : [MarkingBatchBridge] batch already claimed/in-progress or review-owned rawSn=103 — skipping
       [MarkingSkip] marking terminated as SKIPPED rawSn=103 markingSn=71
DB   : ls_marking(71, raw_sn=103, stts_cd='SKIPPED')      ← 활성 집합에서 제거됨
POST /v1/videos/103/markings (재시도) → 201 markingSn=72  ← 구 동작은 영구 409
```
`MarkingBatchBridge.skip()`(`:187-190`) → `MarkingSkipTxService.terminateSkipped`(REQUIRES_NEW, PENDING 한정)가 신설되어 **모든 skip 분기**가 마킹을 종결한다. 1차 수정 제안 ②(출구 회수)가 채택된 형태이며 실동작으로 확인했다.

---

## 2. B-7. VLM 위탁 Step + 콜백 + 보류/재개 (36건)

### 2-1. Step (TC-VLM-001~013, 031~033, 036~040)

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-VLM-001 | PASS | [실동작] rawSn=106 `[Batch][VlmTimeseries] describe submit rawSn=106 request_id=13523b3c… hasMarking=true` (`execute` → `runWithMarking(markings.get(0))`) |
| TC-VLM-002 | PASS | [실동작] rawSn=104/105(마킹 0건) dev trigger·재개 → `hasMarking=false` (run 분기) |
| TC-VLM-003 | PASS | [정적] `:269-273` + `BatchStatusService:58-62`. 로컬 실효값이 `VLM_CLIENT_ENABLED=true` 라 live 미검증(설정 변경은 §10 금지). ⚠ 이 경로도 재개(run)에서는 B-ISSUE-21 의 read-only 문제에 걸린다 |
| TC-VLM-004 | PASS | [정적] `:261-263`. 외부 진입점이 rawSn 을 항상 채워 실경로 도달 불가 |
| TC-VLM-005 | PASS | [정적] `:276-278`. dev trigger 는 오케스트레이터가 먼저 NOT_FOUND |
| TC-VLM-006 | PASS | [실동작] rawSn=113 의 최신 SUCCEEDED procLog 경로를 NULL 로 두고 dev trigger → `ls_batch_proc_log` `VLM/FAILED/"비식별 영상 경로가 없어 VLM describe 위탁을 진행할 수 없습니다 rawSn=113"`, **mock-server 로 113 관련 describe 0건**(원본 경로 전송 코드 부재) |
| TC-VLM-007 | PASS | [정적] `:318-325` recordIssued 실패 → EXTERNAL_API_ERROR abort(전송 이전). 예외 주입 불가로 live 미검증 |
| TC-VLM-008 | PASS | [실동작] 원장 행 `reg_dt=00:27:45.913` < submit 로그 `00:27:45.916` — describe **전** 독립 커밋 확인. 콜백이 그 키로 역조회 성공(`result applied … rawSn=106`) |
| TC-VLM-009 | PASS | [정적] `switchIfEmpty(Mono.error(...))` `:364-365` → err 핸들러 `:369-370` → `VlmSubmitOutcomeRecorder.onSubmitFailed:104-123`. 빈 응답 주입은 mock 수정이 필요해 미재현(근거 라인 정정 §4) |
| TC-VLM-010 | PASS | [실동작] **논블로킹 확증** — `00:27:45.916 [batch-async-2] describe submit` → `.919 [vlm-submit-1] accepted`(전용 풀 스레드) → `.953 [batch-async-2]` 프레임 추출 계속 → `.259` 파이프라인 completed, 벤더 콜백은 **2초 뒤** `00:27:47.993` 도착. 소스에 `BLOCK_TIMEOUT`/`.block(` 없음 |
| TC-VLM-011 | PASS | [실동작] 제출 전 선커밋 — 콜백 수신부 로그 `markingsTransitioned=1`, 마킹 최종 `VLM_COMPLETED`. `:327-337` · `VlmMarkingTxService:49-59` |
| TC-VLM-012 | PASS | [실동작] rawSn=106(마킹 `VLM_COMPLETED`) 재트리거 → 재제출은 되지만 마킹 상태는 **VLM_COMPLETED 유지**(no-op, durable 역행 없음) |
| TC-VLM-013 | PASS | [실동작] mock 로그 `describe accepted … callback_url=http://klid-backend:8080/api/v1/vlm/callback` — 고정 base + `PATH_VLM`, 사용자 입력 미반영 |
| TC-VLM-031 | PASS | [실동작] rawSn=104/105/109 신고 구간 → `withheld — deident report open` + `[Batch] stage skipped recorded stage=VLM`, `ls_batch_proc_log` `VLM/SKIPPED/"비식별 누락 신고 구간 — VLM 위탁 보류(재비식별 대기)"`, **외부 호출 0건**(경로 해석 이전 게이트) |
| TC-VLM-032 | **FAIL** | [실동작] rawSn=109(마킹 보유·`'F'`) 재처리 → VLM 은 보류(예외 0)했으나 `FRAME_EXTRACT/FAILED/"비식별이 완료되지 않은 영상입니다 rawSn=109"` → `[BatchRetry] enqueued rawSn=109 attempt=1 delaySec=60` + 배치 FAILED. **1차 B-ISSUE-61 미해소** → [B-ISSUE-22] |
| TC-VLM-033 | PASS | [정적] `:302-303` — `isUnderDeidentReport` 는 조회 실패를 삼키지 않고 전파(`DeidentReportGate` javadoc 명시), 호출부에 catch 없음 |
| TC-VLM-034 | PASS | [실동작] rawSn=104 resolve → `[VlmResumeBridge] deident gate reopened rawSn=104` → `[VlmResume] resuming withheld VLM submit rawSn=104` → `describe submit … hasMarking=false` → `[vlm-submit-1] accepted` (재위탁 성공) |
| TC-VLM-035 | PASS | [실동작] rawSn=109/106 → `[VlmResume] resume skipped — timeseries meta already present rawSn=109 count=1` (멱등) |
| TC-VLM-036 | PASS | [정적] `RESUMABLE_SKIP_REASONS`(`:150-152`)에 `SKIP_REASON_DISABLED` 미포함. [실동작] `SKIP_REASON_ACK_MISSING` 기록 건(105/106)이 재개 대상으로 실제 트리거됨 → 3종 확장 확인 |
| TC-VLM-037 | PASS | [실동작] rawSn=105 재개가 `JpaSystemException` 으로 실패했으나 `WARN [VlmResume] withheld VLM resume failed rawSn=105 cause=JpaSystemException` 만 남고 예외 미전파(@Async best-effort) |
| TC-VLM-038 | PASS | [실동작] `ls_batch_proc_log(106).resp_payload_cn = {"requestId":"13523b3c…","status":"accepted"}` — 스텝 tx 밖(`vlm-submit-1`)의 REQUIRES_NEW 로 커밋됨 |
| TC-VLM-039 | **PARTIAL** | [정적] REQUIRED 유지 자체는 코드와 일치(`:58-62`). 그러나 근거로 든 "위험 부재"가 재개 경로에서 성립하지 않음 — `run()` 의 `readOnly=true` 와 결합해 **INSERT 실패**(실동작 재현) → [B-ISSUE-21] |
| TC-VLM-040 | PASS | [실동작] 오케스트레이터가 프록시 `execute` 호출 → 파이프라인 경로의 보류 감사 행이 정상 커밋(104/105/109). 내부 run/runWithMarking 은 자기호출이라 중첩 없음 |

### 2-2. 콜백 수신 (TC-VLM-014~025, 030)

전부 실 HTTP 호출(`POST /api/v1/vlm/callback`)로 검증.

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-VLM-014 | PASS | 미발급 request_id → **401** `{"errorCode":"UNAUTHORIZED","message":"발급되지 않은 request_id 입니다."}` |
| TC-VLM-015 | PASS | PROCESSED 키(`13523b3c…`) 재전송 → 200 `{"applied":false}`, 메타 미증가 |
| TC-VLM-016 | PASS | `raw_sn=NULL` 원장(`QA3RD-CB-NORAW`) → **401** `request_id 에 매핑된 rawSn 이 없습니다.` |
| TC-VLM-017 | PASS | `status:"done"` → **400**(DTO `@Pattern` 1차 차단). 해당 원장 키는 **ISSUED 유지**(멱등 미마킹 → 재전송 허용) 확인 |
| TC-VLM-018 | PASS | `status:"failed"` → 200 applied=true, 원장 **PROCESSED**, `[Webhook][Vlm] describe failed … code=E1` 기록 |
| TC-VLM-019 | PASS | [정적] `:112-115`. 실경로 도달 불가 — 원장 FK(`fk_ls_webhook_idempotency_raw`, ON DELETE SET NULL)라 영상 삭제 시 rawSn 이 null 이 되어 401 로 먼저 걸린다(방어심도) |
| TC-VLM-020 | PASS | 한 콜백 내 `0-5` 2건 → **400** `한 콜백 내 중복 구간(start_sec-end_sec)은 허용되지 않습니다.` |
| TC-VLM-021 | PASS | 기존 `0-5` 갱신 + 신규 `5-10` → `ls_data_meta` 0-5 값만 `UPDATED-BY-QA` 로 변경, 신규 meta_sn=262 생성. `ls_data_meta_review` 는 **신규 meta 에만** PENDING 행 추가(기존 metaSn 225 의 검수행 중복 생성 없음) |
| TC-VLM-022 | PASS | [실동작] `markingsTransitioned=1`. [정적] 조회 범위는 `ACTIVE_STATUSES`+`issuedAt` 필터(카탈로그 기대결과 보완 §4) |
| TC-VLM-023 | PASS | [정적] `:63` 단일 `@Transactional` + `:164` `markProcessedInTx`(REQUIRED). 중간 실패 주입 불가로 live 미검증 |
| TC-VLM-024 | PASS | [실동작] **동일 request_id 3건 동시 전송** → `applied` **true 1건 / false 2건**, `ls_data_meta` 신규 1행, 원장 PROCESSED 1행(비관적 락 직렬화) |
| TC-VLM-025 | PASS | [실동작] `error.message="line1\nINJECTED-FAKE-LOG\r\tTAB"` → 로그 `message=line1_INJECTED-FAKE-LOG__TAB`(CR/LF/tab 치환) |
| TC-VLM-030 | PASS | [실동작] HMAC 없이 콜백 직접 호출 시 발급 게이트가 401 로 차단(TC-VLM-014 와 동일 응답) |

### 2-3. ★ 반증 항목 — 미결 스위퍼·원자 클레임·디스패치 (카탈로그 케이스 없음, 실동작 확인)

1. **미결 스위퍼가 실제로 회수한다** — `ls_webhook_idempotency` 에 `ISSUED`+`mdfcn_dt` 경과 행을 심고 스윕 tick 대기:
   ```
   00:44:20.011 [vlm-submit-reclaim] [Batch] stage skipped recorded rawSn=105 stage=VLM
   00:44:20.017 [vlm-submit-reclaim] [Batch] stage skipped recorded rawSn=106 stage=VLM
   00:44:20.021 [vlm-submit-reclaim] WARN [Vlm][Reclaim] pending VLM submit reclaimed=2 ackWindowMin=30 callbackWindowMin=360
   ```
   `ls_batch_proc_log` 에 `VLM/SKIPPED/"VLM describe 수락 응답·콜백 미수신 — 미결 회수 후 재개"` 2행 적재, 원장 행은 `ISSUED→FAILED`(회수 표식)로 전이됨. **ACK 창 30분 / 콜백 창 360분이 서로 다른 설정키**(`stale-timeout-minutes` / `callback-timeout-minutes`)로 로그에 그대로 노출된다(`VlmSubmitPendingSweeper:118-124,192-200`).
2. **원자 클레임이 동시 재위탁을 막는다** — 회수 클레임 SQL(`claimStale`: `... WHERE idmp_key=? AND stts_cd='ISSUED' AND mdfcn_dt<=cutoff`)을 같은 키에 2회 실행 → **UPDATE 1 / UPDATE 0**. 콜백이 먼저 도착해 PROCESSED 가 되면 0행이 되어 도착한 결과를 덮지 않는다(`claimAccepted` 도 `sttsCd='ACCEPTED'` 조건 동봉).
3. **완료 신호는 `publishOn` 이 아니라 명시적 디스패치로 전용 풀에서 실행된다** — ACK/실패 기록 로그의 스레드가 항상 **`vlm-submit-1`**(`AsyncConfig:77-88` `threadNamePrefix="vlm-submit-"`, AbortPolicy)이고 reactor-netty 이벤트 루프(`reactor-http-nio-*`)나 `batch-async-*` 가 아니다. 호출부는 `SubmitSignalDispatch.run(...)`(`:45-58`)으로 감싸며 풀 거부 시 기록을 포기하고 스위퍼에 위임한다.
4. **ISSUED→ACCEPTED 전이 존재** — `VlmSubmitOutcomeRecorder.onAccepted:65-86` 이 `ledger.recordAckReceived`(조건부 UPDATE `stts_cd='ISSUED'`)를 호출. 정상 왕복 건은 최종 `PROCESSED` 로 관측됐고(콜백이 즉시 도착하는 mock 특성), ACK-만-수신 상태의 `ACCEPTED` 잔존은 mock 이 2초 내 콜백을 보내 재현되지 않았다(콜백 창 패스는 정적 확인).

---

## 3. 이슈

### [B-ISSUE-21] TC-VLM-039 / TC-VLM-003 — 재개 경로의 `run()` 이 `readOnly=true` 라 보류 감사 기록이 read-only INSERT 로 실패한다 (1차 B-ISSUE-66 **미해소**, 3차 실동작 재현)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 재개 러너/미결 스위퍼가 부른 위탁이 **다시 보류**(게이트가 아직 닫힘)되거나 비활성으로 끝나면 그 사실이 `LS_BATCH_PROC_LOG` 에 사유와 함께 남아야 한다(B-ISSUE-24 규약 — "재처리 대상 식별이 애플리케이션 로그 보존기간에 종속되면 운영에서 복구 불가"). `VlmWithheldResumeRunner` javadoc(`:50`)도 "게이트가 아직 닫혀 있으면 스스로 다시 보류된다"를 전제한다.
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep.run` 이 `@Transactional(REQUIRES_NEW, readOnly = true)`(`VlmTimeseriesStep.java:236`)인데 그 안에서 부르는 `BatchStatusService.recordVlmSkipped` 는 `REQUIRED`(`BatchStatusService.java:58-62`)라 read-only 트랜잭션에 참여해 PostgreSQL 이 INSERT 를 거부한다.
  ```java
  // VlmTimeseriesStep.java:236
  @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public VlmTimeseriesResponse run(Long rawSn) { return doSubmit(rawSn, null); }
  // → doSubmit:271(DISABLED) · :305(DEIDENT_REPORT) 에서 recordVlmSkipped(REQUIRED, INSERT)
  ```
  **3차 실동작 재현**(rawSn=105, `DE_IDNTF_YN='F'` 유지 + 미결 스위퍼가 재개 트리거):
  ```
  00:44:20.011 [vlm-submit-reclaim] [Batch] stage skipped recorded rawSn=105 stage=VLM   ← 스위퍼(REQUIRES_NEW) 성공
  00:44:20.018 [batch-async-2]      [VlmResume] resuming withheld VLM submit rawSn=105
  00:44:20.020 [batch-async-2]      [Batch][VlmTimeseries] withheld — deident report open rawSn=105
  00:44:20.021 [batch-async-2] ERROR ERROR: cannot execute INSERT in a read-only transaction
  00:44:20.022 [batch-async-2] WARN  [VlmResume] withheld VLM resume failed rawSn=105 cause=JpaSystemException
  ```
  `ls_batch_proc_log(105)` 에는 스위퍼가 쓴 `ACK_MISSING` 행만 있고 **재보류(`DEIDENT_REPORT`) 행은 적재되지 않았다**.
- **재현/확인 경로**:
  ```sql
  -- 게이트가 닫힌(F) 영상 + 시계열 메타 0건 + ISSUED 원장(ACK 창 경과)을 만든 뒤 스윕 tick(기본 15분) 대기
  insert into ls_webhook_idempotency (idmp_key,chnl_cd,stts_cd,raw_sn,reg_dt,mdfcn_dt)
    values ('QA-RO','VLM','ISSUED', <rawSn>, now()-interval '90 minutes', now()-interval '90 minutes');
  select proc_step_cd, proc_stts_cd, err_msg_cn from ls_batch_proc_log where data_raw_sn=<rawSn>;
  ```
- **영향**: 데이터정합/운영. ①재보류 사실이 DB 에 남지 않아 "로그 보존기간 비종속" 보장이 이 경로에서만 깨진다 ②ERROR 스택이 남아 실장애와 구분이 어렵다 ③`vlm.client.enabled=false` 인 dev/stg/prd 기본 형상에서는 **모든** 재개(run 분기)가 이 예외로 끝나 `SKIP_REASON_DISABLED` 행도 남지 않는다. 기존 보류 기록 + "메타 0건" 조건이 남아 다음 회수에서 재시도되므로 영구 정체는 아니다(`max-reclaims=3` 소진까지).
- **수정 방향(제안)**: ①`run` 의 `readOnly=true` 제거(= `runWithMarking` 과 경계 속성 일치) 또는 ②`doSubmit` 의 `recordVlmSkipped` 호출을 이미 존재하는 `recordVlmSkippedInNewTx`(`BatchStatusService:78-82`)로 교체. ②가 비동기 완료 핸들러·스위퍼·`ledger.recordIssued` 와 규약이 같다. 회귀 가드는 **프록시 경유 `run` 호출 시 SKIPPED 감사 행이 실제로 커밋된다**는 IT(현행 `VlmTimeseriesStepTest` 는 mock 기반이라 이 경계를 못 잡는다).

### [B-ISSUE-22] TC-VLM-032 — 신고 구간 배치 재처리가 VLM 은 보류하지만 FRAME_EXTRACT 에서 실패해 배치 FAILED + 재시도 예산을 소진한다 (1차 B-ISSUE-61 **미해소**, 3차 실동작 재현)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 비식별 누락 신고는 정책적 차단이므로 "실패가 아니라 보류"여야 한다. TC-VLM-032 는 "예외 미발생 → 배치 FAILED 미전이, 재시도 큐 미등록, 시도 상한 미소진"을 요구한다.
- **현재 동작(이슈 내용)**: VLM 은 규약대로 보류하지만 바로 다음 단계가 같은 `'F'` 를 예외로 처리한다.
  ```java
  // FfmpegFrameExtractor.java:185-188
  if (!DEIDENTIFIED.equals(raw.getDeIdntfYn())) {   // "Y" 만 통과 → 'F' 도 실패
      throw new CustomException(ErrorCode.INVALID_INPUT, "비식별이 완료되지 않은 영상입니다 rawSn=" + ...);
  }
  ```
  **3차 실동작**(rawSn=109 — 정상 배치 1회 완료 → 신고 접수 → 재처리):
  ```
  00:30:38.330 [Batch][VlmTimeseries] withheld — deident report open rawSn=109     ← 보류(정상)
  00:30:38.331 [Batch] stage skipped recorded rawSn=109 stage=VLM
  00:30:38.335 [BatchRetry] enqueued rawSn=109 attempt=1 delaySec=60                ← 기대 위반
  00:30:38.336 [BatchOrchestrator] failed rawSn=109 willRetry=true cause=CustomException
  ls_batch_proc_log: FRAME_EXTRACT / FAILED / "비식별이 완료되지 않은 영상입니다 rawSn=109"
  ```
- **재현/확인 경로**: `POST /v1/videos/{rawSn}/deident-report` → `POST /v1/dev/batch/trigger?rawSn={rawSn}` → `select proc_step_cd, proc_stts_cd, err_msg_cn from ls_batch_proc_log where data_raw_sn={rawSn}` + `select * from ls_bat_rty_wtng where raw_sn={rawSn}`.
- **영향**: 기능/운영. 신고 구간 자동 재시도가 반드시 실패하며 예산(3회, 60/120/240초)을 태우고, 영상이 FAILED 로 표시돼 장애로 오인된다. 해소 시 자동 복구는 VLM 재개뿐이라 프레임추출 이후 단계는 수동 `batch/retry` 가 필요하다. 외부 유출은 없다(전송은 정상 차단).
- **수정 방향(제안)**: `FfmpegFrameExtractor` 의 비식별 선행 가드를 `'N'` 만 실패로 두고 `'F'` 는 VLM 과 동일하게 보류(SKIPPED + 사유 적재)로 분기하거나, `BatchStep.isEnabled(ctx)` 로 신고 구간에서 post-marking 단계를 통째로 건너뛰고 재시도 큐에 넣지 않는다. 어느 쪽이든 해소 시 `DeidentGateReopenedEvent` 소비자를 늘려 프레임추출 이후도 재개돼야 한다.

### [B-ISSUE-23] 카탈로그 정합 — B-5 skip 케이스 4건이 1차 B-ISSUE-41 해소(마킹 `SKIPPED` 종결)를 반영하지 않았다
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 기대결과가 현재 구현의 계약을 담아야, 다음 회차가 "skip 인데 마킹이 PENDING 으로 남는다"를 결함으로 재발견하거나 반대로 종결 누락을 놓치지 않는다.
- **현재 동작(이슈 내용)**: TC-BATCH-050/051/052/057 의 기대결과가 "WARN, 미트리거"에 머물러 있었고, 신설된 `MarkingBatchBridge.skip()`(`:187-190`) → `MarkingSkipTxService.terminateSkipped`(REQUIRES_NEW·PENDING 한정) 계약이 어디에도 없었다. 또 050~052 가 마킹 API 경로에서 **프리컨디션에 선차단되어 도달 불가**(방어심도)라는 사실도 미기재였다.
- **재현/확인 경로**: `docs/test-cases/B-batch-deidentify.md` 109~121행 대조 · 위 §1 실동작 로그.
- **영향**: 카탈로그 정합(다음 회차 오판 위험).
- **수정 방향(제안)**: **이번 회차에서 정정 완료**(TC-BATCH-050/051/052/057 기대결과 + 근거 `MarkingBatchBridge.java:187-190`·`MarkingSkipTxService` 추가). `UNCERTAINTIES.md` 및 1차 ISSUES 의 B-ISSUE-41 을 **해소 처리**할 것.

### [B-ISSUE-24] 카탈로그 정합 — B-7 근거·기대결과 드리프트 4건(TC-VLM-009 라인 · TC-VLM-022 범위 · TC-BATCH-058/059 라인)
- **심각도**: LOW
- **기대 동작(기대효과)**: `file:line` 근거로 다음 회차가 대조 가능해야 하고, 기대결과가 실제 판정 로직을 담아야 한다.
- **현재 동작(이슈 내용)**: ①TC-VLM-009 근거 `VlmTimeseriesStep.java:373-377` 은 **동기 조립 실패 catch** 구간이고 케이스가 말하는 `switchIfEmpty(Mono.error(...))` 는 `:364-365`, err 핸들러는 `:369-370` 이다. ②TC-VLM-022 기대결과가 "VLM_REQUESTED→VLM_COMPLETED"로만 적혀 있어, 실제 조회 범위인 **`ACTIVE_STATUSES`(PENDING 포함) + 발급시각 이후 생성 PENDING 제외(L6)** 를 담지 못했다(`VlmResultService:152-161,186-208`) — 이 상태로는 "콜백 선행 레이스" 회귀를 잡을 수 없다. ③TC-BATCH-058/059 근거 `:299-320`/`:299-332` 가 다음 메서드 javadoc 까지 걸쳐 있어 실제 메서드(`:299-314`)와 어긋났다.
- **재현/확인 경로**: 각 파일 해당 라인 Read 대조.
- **영향**: 카탈로그 정합.
- **수정 방향(제안)**: **이번 회차에서 정정 완료**(4건 모두). ⚠ **`## 변경 이력` 표의 회차 행(5회차)은 병렬 파트 에이전트 간 번호 충돌을 피하려 추가하지 않았다** — 병합 단계에서 B 클러스터 정정 총계를 합산해 1행으로 추가할 것.

### [B-ISSUE-25] 커버리지 갭 — 미결 스위퍼(`VlmSubmitPendingSweeper`) 기계에 대응하는 케이스가 B-7 에 0건 (1차 B-ISSUE-65 부분 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 논블로킹 제출의 **유일한 회수 경로**(노드 사망 시 in-flight subscription 유실 대비)가 카탈로그로 검증 가능해야 한다. 1차 B-ISSUE-65 는 근거 라인 재산출과 함께 `TC-VLM-041~` 신규 케이스 추가를 제안했고, 라인은 4회차에서 재산출됐으나 **케이스 추가는 이뤄지지 않았다**.
- **현재 동작(이슈 내용)**: B-7 36건 어디에도 ①ACK 창(`stale-timeout-minutes` 30분)과 콜백 창(`callback-timeout-minutes` 360분)의 **2종 임계 분리** ②하한 clamp(`MIN_STALE_TIMEOUT_MINUTES=10` / `MIN_CALLBACK_TIMEOUT_MINUTES=60`) ③원자 클레임(`claimStale`/`claimStaleAccepted`) ④회수 예산(`max-reclaims=3`) ⑤`SubmitSignalDispatch` 풀 거부 시 기록 포기 ⑥원장 `ISSUED→ACCEPTED` 전이(`recordAckReceived`)에 대응하는 행이 없다. 이번 회차에 이 경로가 **실제로 동작함**을 확인했다(§2-3) — 즉 검증 가능한 산 코드인데 카탈로그만 비어 있다.
- **재현/확인 경로**: `grep -c 'TC-VLM-0(4[1-9])' docs/test-cases/B-batch-deidentify.md` → 0 · `ls backend/src/test/java/kr/co/cudo/authoring/batch/vlm/`(`VlmSubmitAckWindowIT`·`VlmSubmitPendingSweeperTest`·`VlmSubmitReclaimAtomicClaimIT` 등 기존 테스트 자산 존재).
- **영향**: 커버리지 갭 — 회수 임계를 잘못 좁히면 "정상 분석 중인 위탁을 뺏어 같은 비식별 영상을 중복 위탁"(H1)하는 회귀가 카탈로그로 감지되지 않는다.
- **수정 방향(제안)**: `TC-VLM-041~048` 을 신설해 위 ①~⑥ + "예산 초과 시 재개 억제(기록만)" + "rawSn 매핑 없는 원장 행 제외"를 덮는다. 기존 `batch/vlm/*` 테스트와 1:1 매핑 가능. (본 파트는 담당 라인범위 밖 행 추가를 하지 않아 제안만 남긴다.)

---

## 4. 카탈로그 정정 내역 (이번 회차 직접 수정, 11건)

| 행 | 수정 내용 |
|---|---|
| TC-BATCH-050 | 기대결과에 마킹 `SKIPPED` 종결 추가 · 근거 `:113-119` → `:113-119,187-190` |
| TC-BATCH-051 | 기대결과에 마킹 종결 + "마킹 API 경로 도달 불가(방어심도)" 명시 |
| TC-BATCH-052 | 동상 · 근거 `:134-139` → `:134-139,187-190` |
| TC-BATCH-057 | 기대결과에 **1차 B-ISSUE-41 해소**(마킹 `SKIPPED` 종결 → 재마킹 409 잠금 방지) + 201 응답 필드 명시 · 근거에 `:187-190`·`MarkingSkipTxService` 추가 · 계층 unit → integration, 우선 P1 → **P0**(고아 마킹이 영상을 영구 잠그던 회귀의 가드) |
| TC-BATCH-058 | 근거 `BatchTransitionService.java:299-320` → `:299-314` |
| TC-BATCH-059 | 근거 `:299-332` → `:299-314` |
| TC-VLM-009 | 근거 `VlmTimeseriesStep.java:373-377` → `:361-377`(switchIfEmpty `:364-365` · 동기 catch `:371-377`) |
| TC-VLM-022 | 케이스명·기대결과를 `ACTIVE_STATUSES` + `issuedAt` 이후 PENDING 제외(L6)로 보완 · 근거 `:158-161` → `:152-161,186-208` |
| TC-VLM-032 | 기대결과에 "**1차 B-ISSUE-61 미해소**(3차 실동작 재현) — 파손 지점 `FfmpegFrameExtractor:185-188`" 경고 추가 |
| TC-VLM-034 | 근거 `VlmResumeBridge.java:34-38` → `:34-40` |
| TC-VLM-039 | 기대결과에 "재개 경로에서 전제가 깨짐(B-ISSUE-21)" 경고 + `VlmTimeseriesStep.java:236` 근거 추가 |

> 합계 11행(기대결과 실질 변경 6건 + 근거 `file:line`-only 5건). ⚠ `## 변경 이력` 표의 회차 행은 **병렬 파트 간 번호 충돌 방지를 위해 추가하지 않았다** — 병합 단계에서 B 클러스터 전체 정정 총계를 합산해 5회차 1행으로 기재할 것.

## 5. 이전 회차 이슈 대조

| 이슈 | 상태 | 근거 |
|---|---|---|
| 1차 **B-ISSUE-41**(브릿지 skip → 영구 고아 마킹 → 재마킹 409 영구 잠금) | **✅ 해소** | `MarkingSkipTxService.terminateSkipped` 신설, rawSn=103 실동작(마킹 71/72 모두 `SKIPPED`, 재마킹 201) |
| 1차 **B-ISSUE-61**(신고 구간 재처리 FRAME_EXTRACT 실패 → FAILED + 재시도 소진) | ❌ 미해소(이월) | rawSn=109 실동작 재현 → [B-ISSUE-22] |
| 1차 **B-ISSUE-66**(`run()` readOnly + REQUIRED recordVlmSkipped) | ❌ 미해소(이월) | rawSn=105 실동작 재현 → [B-ISSUE-21] |
| 1차 **B-ISSUE-65**(B-7 근거 드리프트 34/36 + 신규 기계 케이스 부재) | 🔶 부분 해소 | 근거 라인은 4회차 최신화로 대체로 정합(잔여 4건 이번 회차 정정). **신규 케이스 추가는 미이행** → [B-ISSUE-25] |
| 1차 **B-ISSUE-43**(브릿지 가드 4종 마킹 API 경로 도달 불가) | 사실 유지 | 050/051/052 모두 프리컨디션 412/404 선차단 실측(방어심도로 카탈로그에 명시함) |
| 1차 **B-ISSUE-62/63/64**(TC-VLM-009/010/011 카탈로그 무효) | ✅ 해소 | 4회차 최신화로 기대결과가 Phase C-1 기준으로 재작성됨(이번 회차 실동작과 일치) |

## 6. 부수 관측 (본 파트 범위 밖 — 기록만)

- **완료 영상 재처리 시 FRAME_EXTRACT 중복키 실패**: rawSn=106(COMPLETED) 을 dev trigger 로 재실행하면 `FRAME_EXTRACT/FAILED/"duplicate key value violates ..."` + 재시도 큐 등록. VLM 은 정상 재제출됐다. B-8/B-4 담당 범위.
- **`POST /v1/deident-reports/{rprtSn}/resolve` 가 재비식별 없이 통과한 사례**: rawSn=109 는 비식별 산출물을 갱신하지 않았는데 resolve 200(1차 B-ISSUE-102 의 스큐 관용). rawSn=104 는 같은 조건에서 409 로 막혔고 `touch` 후 통과 — B-11/B-12 담당 범위.
- **수동 삽입 원장 행의 시각 함정**: DB `now()` 는 UTC, 애플리케이션은 KST `LocalDateTime` 을 쓴다. 수동으로 `now()` 를 넣은 원장 행은 앱 기준 9시간 과거로 보여 스위퍼가 즉시 회수 대상으로 잡는다(이번 회차에서 rawSn=106 의 `QA3RD-CB-DUP` 이 그 사례 — 테스트 아티팩트이며 결함 아님).

## 7. 남긴 검증 데이터 (정리 참고)

| 대상 | 키 |
|---|---|
| `ls_data_ingest` | rcptn_sn 3~6, 9, 13, (B5-CLAIM) — 전부 DONE |
| `ls_data_raw` | 103(QA3RD-B5-SKIP) · 104(QA3RD-B7-GATE) · 105(QA3RD-B7-RO, `'F'` 유지) · 106(QA3RD-B7-NORMAL) · 109(QA3RD-B7-WITHHELD) · 113(QA3RD-B7-NOPATH, procLog 경로 NULL) · 115(QA3RD-B5-CLAIM) |
| `ls_webhook_idempotency` | `QA3RD-B7-RO-STALE` · `QA3RD-CB-NORAW` · `QA3RD-CB-DUP` · `QA3RD-CB-UPSERT` · `QA3RD-CB-FAILED` · `QA3RD-CB-RACE` · `QA3RD-CB-LOGINJ` · `QA3RD-CAS-DEMO` |
| `ls_deident_report` | rprtSn 24(rawSn=104, RESOLVED) · 25(rawSn=105, **OPEN 유지**) · 27(rawSn=109, RESOLVED) |
| 원복 완료 | 103 `de_ident_yn` 'N'→'Y' · 115 작업상태 BATCH_QUEUED→ASSIGNED |

> rawSn=101/102 및 그 이전 데이터는 일절 변경하지 않았다.
