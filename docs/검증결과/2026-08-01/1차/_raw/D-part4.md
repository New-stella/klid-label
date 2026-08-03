# D-part4 — D-6. 관제 통지 (TC-NOTIFY) 45건 검증 결과

- **대상**: `docs/test-cases/D-review-version-notify.md` `## D-6. 관제 통지 (TC-NOTIFY)` — TC-NOTIFY-001~025, 032~051 (45건, 폐기 0건)
- **검증일**: 2026-08-01 (KST 기준 실행, 일부 로그는 2026-08-02 새벽까지 연장)
- **스택**: 풀스택 기동 상태(`docker ps` 5컨테이너 healthy). `CONTROL_NOTIFY_ENABLED=true`, `CONTROL_NOTIFY_URL=http://klid-mock-server:9400` — 관제 통지가 **mock-server 를 실제로 왕복**한다(self-fill 아님).
- **판정 근거 원천**: ①mock-server 인바운드 로그(`docker logs klid-mock-server`) ②backend 애플리케이션 로그 ③`public.ls_control_notify_fallback`(실제 전송 페이로드 원문 보관) ④`public.ls_dataset_export` ⑤코드 file:line
- **주입한 테스트 데이터**: `ls_control_notify_fallback` 에 `idmp_key LIKE 'qa-d6-%'` 7행 INSERT (기존 행 보존, 코드/설정/테스트 무수정). 재시도 잡(5분 주기)이 이를 실제로 소비해 **폴백/재시도/dead-letter/job_id 가드 경로를 실동작으로 관측**했다.

## 0. 실동작 근거 핵심 (요약)

`ls_control_notify_fallback` 에 남은 **실제 전송 페이로드 원문**(queue_sn 12·15 = TASK_COMPLETED, 13·14·16~19 = TASK_MODIFIED):

```json
{"job_id":"26","event_type_cd":"EV02000201","lclgv_cd":"1168000000","lclgv_nm":null,"duration_sec":5,"image_count":5}
{"job_id":"26","changed_items":{"images":["0000.jpg","0001.jpg","0002.jpg","0003.jpg","0004.jpg"],
                                 "jsons":["0000.json","0001.json","0002.json","0003.json","0004.json"]}}
{"job_id":"26","changed_items":{"images":[],"jsons":[]}}
{"job_id":"26","changed_items":{"images":[],"jsons":["0000.json"]}}
```

DB 대조 — `ls_data_raw(raw_sn=26)`: `evnt_type_cd=EV02000201`, `lclgv_cd=1168000000`, `vdo_len_sec=5` / `count(ls_data_src where raw_sn=26)=5` / `mng_ex_local_gov` 0행 → `lclgv_nm=null`.
**6필드 전부 DB 실측 일치, 상수 self-fill 0건.** 구 기대값(`totalFrames`/`labeledFrames`/`reviewerName`)은 페이로드에 **존재하지 않음**(UNCERTAINTIES #3 대로 정상).

mock-server 실왕복 로그(자기치유 2방향 모두 실측):
```
notify-updated  job_id=26 → 404 Not Found     →  notify-completed job_id=26 → 202  (404 self-heal)
notify-completed job_id=26 → 409 Conflict     →  notify-updated   job_id=26 → 202 images=5 jsons=5 (409 self-heal)
```

---

## 1. 판정 표

| ID | 판정 | 근거 확인 | 요지 |
|----|:--:|------|------|
| TC-NOTIFY-001 | PARTIAL | [실동작] backend log 2026-08-01 23:54:53 · `ls_dataset_export#18` | 트리거는 `DatasetExportCompletedEvent`(승인 이벤트 직후 아님) → **순서 보장은 성립**. 그러나 export 가 `FAILED` 로 마감된 경우에도 완료 이벤트가 발행돼 **"SUCCEEDED 이후"** 전제가 깨진다 → D-ISSUE-61 |
| TC-NOTIFY-002 | PASS | [정적] `DatasetExportBridge.java:36-37` | `@TransactionalEventListener(AFTER_COMMIT)` — 승인 tx 롤백 시 리스너 미호출 → export·통지 모두 미발생. 테스트 `DatasetExportBridgeTest` |
| TC-NOTIFY-003 | PASS | [실동작] `ls_control_notify_fallback#12,15` payload + `ls_data_raw`/`ls_data_src` 대조 | 6필드 평면 snake_case, 전 필드 DB 실측(§0). 라벨·PII·토큰·원본 경로 없음. `TaskCompletedPayload.java:24-32` / `ControlNotifyPayloadFactory.java:78-92` |
| TC-NOTIFY-004 | PASS | [정적] `ControlNotifyService.java:130-134` | catch → `enqueueQuietly(...)` + `metrics.incrementCompletedFailed()`. 테스트 `ControlNotifyServiceTest#TASK_COMPLETED_실패시_폴백큐_적재됨` |
| TC-NOTIFY-005 | PASS | [실동작] backend log 23:19:00.561 / 23:22:14.187 + DB queue_sn 15·16 | 409→updated 치유 시 관찰행 `eventType=TASK_MODIFIED`, 404→completed 치유 시 `TASK_COMPLETED` — **요청 종류가 아니라 실제 전송분** 기록. `ControlNotifyService.java:126-129,318-325` |
| TC-NOTIFY-006 | PASS | [정적] `ControlNotifyService.java:318-325` | `recordSendSuccess` try/catch → warn 만, 통지 성공 유지. 테스트 `관찰_적재_실패해도_통지성공_metrics는_유지된다` |
| TC-NOTIFY-007 | PASS | [실동작] DB payload 원문(§0) | `{job_id, changed_items:{images[],jsons[]}}`, 항목은 `%04d.jpg/.json`. SRC_SN·좌표·메타 본문·절대경로 **없음**. 두 리스트 항상 non-null(빈 리스트 실측). `TaskModifiedPayload.java:42-53` · `ExportFileNaming.java:44-59` |
| TC-NOTIFY-008 | PASS | [실동작] `\d ls_mon_noti_acml` + backend flush log | `LS_MON_NOTI_ACML`(V144) 실재, 부분 유니크 `uk_lmna_raw_pending`. flush 로그가 DB 왕복 후 복원된 페어를 출력: `flush rawSn=26 regen=true frames=296=[LABEL_ADDED, LABEL_DELETED]`, 다른 tick 은 `videoLevel=1 reExport=false`. 구현체는 `JpaControlNotifyDebounceStore` 단일(인메모리 없음) |
| TC-NOTIFY-009 | PASS | [정적] `ControlNotifyDebouncer.java:81,191-204` | `@PreDestroy flushAll` — `shutdownNow()` 로 새 tick 차단 후 `windowCutoff=now` 로 만료 무관 drain, `MAX_DRAIN_ROUNDS=20`. 미처리분은 DB 잔존 |
| TC-NOTIFY-010 | PASS | [실동작] backend log 23:10:19 기동 로그 + flush 스레드명 | `[ControlNotifyDebounce] flush scheduler started intervalMs=10000 leaseMs=300000`, flush 는 스레드 `control-notify-debounce-flush` 에서 실행(= `@Scheduled` 비의존). `regen-flush.enabled=false` 시 미기동은 `:144-147` |
| TC-NOTIFY-011 | PASS | [실동작+정적] `ChangeType.java:18-28` + flush 로그 | 계약값 4종만 상수화. 페이로드에는 미포함(§0 payload 확인) 하고 flush 요약 로그로만 관측(`:292-295,312-321`) |
| TC-NOTIFY-012 | PASS | [정적] `LabelService.java:412-426` | `if (!changes.isEmpty() && isReviewApproved(rawSn))` — 검수 전 저장은 통지 미발행 |
| TC-NOTIFY-013 | PASS | [정적] `LabelService.java:397-412` | `changes` 비면 이력 저장·`bumpLabelVersion`·통지 모두 skip |
| TC-NOTIFY-014 | PASS | [정적] 7파일 grep 실측 | 7경로 전부 `exportRegenerated=true`: `LabelService:423-424`·`TrackEditService:326`·`TrackMergeService:201`·`VersionService:518-519`·`EnvironmentMetaService:125-126`·`FrameDescriptionService:60-61`·`FramePrivacyMetaService:131-132,154-155`. (추가로 `DatasetVideoMetaEnvCorrectionTx:79-80`·`EvntAnnoReviewService:230-231` 도 true — 카탈로그 7건의 상위집합) |
| TC-NOTIFY-015 | PASS | [실동작] 중복 INSERT 시도 → `duplicate key value violates unique constraint "uk_lcnf_idempotency"` | DB UK 가 최종 방어, 서비스는 `DataIntegrityViolationException` 삼키고 멱등 반환(`ControlNotifyFallbackService.java:91-95`) |
| TC-NOTIFY-016 | PASS | [정적] `ControlNotifyFallbackService.java:36,74-82` | `MAX_QUEUE_DEPTH=10000`, `countBySttsCdIn(PENDING,RETRYING)` ≥ 상한이면 `IllegalStateException` + `control.notify.fallback.queue.full` 카운터(CWE-770). 관찰행(SUCCEEDED)은 깊이 산입 제외 — 정상 |
| TC-NOTIFY-017 | PASS | [정적] `ControlNotifyFallbackService.java:71-73,113-115` | `enabled=false` 면 enqueue/record 모두 `Optional.empty()` |
| TC-NOTIFY-018 | PASS | [실동작+정적] `LsControlNotifyFallbackRepository.java:45-48` + 재시도 잡 로그 | `UPDATE ... SET STTS='RETRYING' WHERE QUEUE_SN=? AND STTS='PENDING'` 조건부 CAS, `updated!=1 → empty`(`:137-143`). 실행 시 5행 클레임 성공 관측 |
| TC-NOTIFY-019 | PASS | [실동작] queue_sn 22·24·25 | 1회 실패 → `PENDING`, `next_rtry_dt = 23:56:20 + 2분 = 23:58:20`(=2^1분). cap 60 은 `Math.min(60, 1<<min(n,6))`(`LsControlNotifyFallback.java:208`). ⚠ 같은 메서드 javadoc `:190` 은 "(1, 2, 4, 8, 16)" 이라 실제(2,4,8,16,32)와 어긋남 → D-ISSUE-64(LOW, 문서) |
| TC-NOTIFY-020 | PASS | [실동작] queue_sn 23 | `rtry_nmtm=5`→6 (> max 5) → `stts_cd=DEAD_LETTER`, `dlq_dt=2026-08-01 23:56:20.957678`, `next_rtry_dt=NULL`. backend log `dead-letter queueSn=23 retryCount=6` |
| TC-NOTIFY-021 | PASS | [실동작] backend log 23:56:20 `[scheduling-6]` | due 5건 → claim→processOne→(성공 1건 markSucceeded / 실패 4건 재스케줄), `retry processed count=1`. `BATCH_SIZE=20`(`ControlNotifyFallbackRetryJob.java:31`) |
| TC-NOTIFY-022 | PASS | [실동작] queue_sn 22·24·25 | `processOne` 예외 → `markFailedAndSchedule` → `retry scheduled queueSn=NN retryCount=1 nextRetryAt=...` |
| TC-NOTIFY-023 | PASS | [실동작] `qa-d6-sanitize-2/3` 주입 → 저장된 `LAST_ERR_MSG_CN` | 토큰/URL/제어문자를 실제로 태워 확인: 입력 `http://a.b/x\nTOK` → 저장 **`URL_REDACTED TOK`**(URL 치환 + 개행→공백), 입력 `token=SEKRET123` → 저장 **`token***`**(값 마스킹). `sanitizeError`(`LsControlNotifyFallback.java:246-253`) = 제어문자→공백 → `(?i)(authorization\|token\|bearer\s+)[^\s,;]*`→`$1***` → `https?://…`→`URL_REDACTED` → 1900자 절단. 추가로 Jackson `INCLUDE_SOURCE_IN_LOCATION` 비활성이라 역직렬화 실패 메시지의 원문도 이미 `REDACTED`(이중 방어) |
| TC-NOTIFY-024 | PASS | [정적] `ControlNotifyFallbackService.java:108-129` | `recordImmediateSuccess` 가 `DataIntegrityViolationException` 삼키고 멱등 반환. UK 실재는 TC-NOTIFY-015 로 실증 |
| TC-NOTIFY-025 | PASS | [정적] `@ConditionalOnProperty` 전수 확인 | 토글 종속: `ControlNotifyClient:42`·`ControlNotifyService:53`·`ControlNotifyPayloadFactory:60`·`ControlNotifyEventListener:27`·`ControlNotifyFallbackRetryJob:27`·`TaskQueryController:46`·`ControlNotifyMetrics:30`·`ControlNotifySchedulingConfig:13`. **무조건 등록**: `ControlNotifyDebouncer:73-75`·`TaskModifiedAccumulateListener:27`·`DatasetExportBridge:29-30`(`matchIfMissing=true`) |
| TC-NOTIFY-032 | PASS | [실동작] mock-server 액세스 로그 | `POST /api/data-set/v2/jobs/26/notify-completed` · `.../notify-updated` 실왕복, mock 이 **202** 를 돌려줘도 성공 처리(`is2xxSuccessful`, `ControlNotifyClient.java:96-99`). `contentType(APPLICATION_JSON)` `:93` |
| TC-NOTIFY-033 | PASS | [실동작] mock 409 → updated 202 + backend log `completed conflicted -> resend as updated rawSn=26` + `TASK_COMPLETED sent rawSn=26 actual=TASK_MODIFIED` | 반환 SendOutcome=TASK_MODIFIED 가 관찰행에 반영(queue_sn 16). `selfHealCompletedToUpdated` 카운터 `:227` |
| TC-NOTIFY-034 | PASS | [실동작] mock 404 → completed 202 + backend log `updated not-found -> fallback to completed rawSn=26` + `actual=TASK_COMPLETED` | 1회 폴백, `selfHealUpdatedToCompleted` `:257` |
| TC-NOTIFY-035 | PASS | [정적] `ControlNotifyService.java:221-232,251-262` | 치유 분기 안의 2차 호출은 `client.send*` 직접 호출이라 재귀 진입점이 없다(2단계 종료 → 상위 catch → 폴백 큐). 테스트 `폴백은_재귀하지_않는다`·`completed_409_후_updated_도_실패하면_재귀하지_않고_폴백큐로_간다` |
| TC-NOTIFY-036 | PASS | [실동작+정적] `ControlNotifyClient.java:115-120` + `ControlNotifyStatusException.java:17` | 4xx→`ControlNotifyStatusException extends NonRetryableExternalException` → `resilience4j.retry/circuitbreaker.controlNotify.ignore-exceptions`(application.yml:544,616) 로 재시도·서킷 제외되나 예외는 호출자까지 전파(mock 에 409 가 1회만 도달한 것으로 실증). 5xx→`IllegalStateException`(집계 대상) |
| TC-NOTIFY-037 | PASS | [실동작] queue_sn 22 (`job_id="../../etc/passwd"`) | `IllegalArgumentException: 허용되지 않는 job_id 형식입니다.` 로 차단, **mock-server 액세스 로그에 해당 경로 요청 0건**(HTTP 자체가 안 나감). `JOB_ID_PATTERN=\d{1,19}` + `matches()` 전체매칭(`:73,275-279`) |
| TC-NOTIFY-038 | PASS | [실동작] queue_sn 21 (`payload_cn=''`) | 재시도 잡이 `PAYLOAD_REBUILD_REQUIRED` 를 인식해 `dispatchCompleted(null, 26)` → `buildCompleted` 재조립 → 409 → updated 치유 → `succeeded queueSn=21` / `stts_cd=SUCCEEDED`. `ControlNotifyService.java:112-123` · `ControlNotifyFallbackRetryJob.java:85-101` |
| TC-NOTIFY-039 | PASS | [정적] `ControlNotifyService.java:170-188` | regen=true → `PAYLOAD_REBUILD_REQUIRED` / regen=false → `ChangedItems.empty()` 로 **확정 적재**(재시도가 전 프레임 blast 안 함). 테스트 MED2 2건 |
| **TC-NOTIFY-040** | **FAIL** | [실동작] backend log 23:54:53.215→.219 · `ls_dataset_export#18` | export 가 **FAILED 로 마감됐는데 TASK_COMPLETED 가 발송**됨. `DatasetExportService` 는 예외 없이 정상 반환하는 실패 종결 경로가 4종 있어 `AsyncDatasetExportRunner.doExport` 가 `true` 를 돌려준다 → D-ISSUE-61 |
| TC-NOTIFY-041 | PARTIAL | [정적] `DatasetExportFailureRecoverer.java:119-170` | 회수 로직(조건부 UPDATE 클레임 → `runApprovalAsync` → 성공 시 완료 이벤트 재발행)은 코드상 정확. 다만 D-ISSUE-61 로 **실패 시점에 이미 통지가 나가 있어** "통지 유실이 아니라 지연" 이라는 전제가 성립하지 않는다(관제는 이미 구/부재 폴더를 픽업한 상태). 실동작 관측은 15분 주기 Quartz + 10분 유예로 이번 창에서 미도달(§3) |
| TC-NOTIFY-042 | PASS | [정적] `DatasetExportFailureRecoverer.java:135-144` | `deidentReportGate.isUnderDeidentReport(rawSn)` 를 `txService.claimForRetry` **이전**에 평가해 `continue` → `RTY_NMTM` 미증가 + INFO 집계 로그 |
| TC-NOTIFY-043 | PASS | [정적] `TaskModifiedAccumulateListener.java:27-37` · `ControlNotifyDebouncer.java:286-309` | 축적 리스너·디바운서·`DatasetExportBridge` 모두 토글 무조건 등록, `exportRunner.runReExportThenNotify` 는 `notifyService==null` 이어도 호출(콜백만 null). 테스트 HIGH-E 2건. (본 환경은 토글 on 이라 off 형상은 정적 판정) |
| TC-NOTIFY-044 | PASS | [정적] `LsMonNotiAcmlRepository#claimForFlush` + `JpaControlNotifyDebounceStore.java:104-110` | 상태전이 자체가 조건부 원자 UPDATE(`updated!=1 → empty`) → 한 노드만 스냅샷 획득. 테스트 `ControlNotifyDebounceCrossNodeIT`. 단일 노드 환경이라 크로스노드 실동작은 BLOCKED 성격(2노드 필요) |
| TC-NOTIFY-045 | PASS | [실동작+정적] 기동 로그 `leaseMs=300000` + `ControlNotifyDebouncer.java:84,242-270` | 발송 실패 시 `complete()` 미호출 → FLUSHING 잔존, `findFlushableAnchors` 가 `STTS='FLUSHING' AND MDFCN_DT<=leaseCutoff` 축으로 재클레임. `MIN_LEASE_MILLIS=60_000` 하한 clamp, `dropped` 카운터+ERROR 후 루프 계속 |
| TC-NOTIFY-046 | PASS | [정적] `ControlNotifyService.java:301-309` | `enqueueQuietly` 가 모든 예외 흡수 + `metrics.incrementDropped()` + ERROR. 테스트 `폴백큐가_가득_차도_예외가_호출자로_전파되지_않는다` |
| TC-NOTIFY-047 | PASS | [실동작+정적] backend log `local gov not found lclgvCd=1168000000` + payload `"lclgv_nm":null` | 마스터 미존재 → null(값 미생성) **실측**. `USE_YN<>'Y'` → null(`:160-163`), 100자 초과 절단(`:66,171-173`). 테스트 3건 |
| TC-NOTIFY-048 | PASS | [정적] `ControlNotifyPayloadFactory.java:185-219` + `LsDataSrcRepository:135-139` | `findBothVelExportableFrameNoByRawSnAndSrcSnIn(rawSn, srcSns)` 로 타 영상 srcSn·한쪽 벌만 보유 프레임 제외, 식별자만 WARN + `control.notify.modified.frame.unresolved` 카운터 |
| TC-NOTIFY-049 | PASS | [실동작+정적] payload(5프레임) vs `ls_dataset_export.frame_cnt=10`(5프레임×2벌) | `buildModifiedForAllFrames` 는 `findExportableFrameNosByRawSn`(원천 경로 둘 중 하나라도 보유) 결과만 사용(`:120-126`, repo `:158-161`) |
| TC-NOTIFY-050 | PASS | [정적] `DatasetExportBridge.java:81-88` | `DeidentReportResolvedEvent` AFTER_COMMIT → `runApprovalAsync` → force 재산출 + 완료 이벤트로 보류 통지 재개 |
| TC-NOTIFY-051 | 확인필요 | [정적] `WebClientConfig#controlNotifyWebClient` (baseUrl 만 설정, `defaultHeader` 없음) · `ControlNotifyClient.java:90-108` | 인증 헤더 미부착 확정(케이스 기대와 일치). 관제 계약 `x-access-token` 미배선 = **연동 갭**(UNCERTAINTIES #26, 협의 대상) → D-ISSUE-62 |

### 집계

| 판정 | 건수 |
|------|--:|
| PASS | 41 |
| FAIL | 1 |
| PARTIAL | 2 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 1 |
| **합계** | **45** |

---

## 2. 이슈

### [D-ISSUE-61] TC-NOTIFY-040 / TC-NOTIFY-001 / TC-NOTIFY-041 — export 가 FAILED 로 마감돼도 TASK_COMPLETED 통지가 발송된다 (예외 없는 실패 종결 4경로)
- **심각도**: CRITICAL
- **기대 동작(기대효과)**: `CLAUDE.md` 구속 정책 — *"통지는 export 성공(SUCCEEDED) 후 발송한다… **export 가 실패하면 통지를 보류**하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다(통지 유실이 아니라 성공 시점으로 지연)."* 관제는 통지를 받으면 `V_COMPLETED_VIDEO.EXPORT_PATH_NM`(최신 SUCCEEDED)을 픽업하므로, 실패 상태에서 통지가 나가면 **구 버전 폴더를 픽업하거나(기존 SUCCEEDED 존재 시) 아무 폴더도 못 찾는다(최초 export 실패 시)**.
- **현재 동작(이슈 내용)**: `AsyncDatasetExportRunner.doExport` 는 **예외 발생 여부**로만 성공을 판정한다.
  ```java
  // AsyncDatasetExportRunner.java:121-130
  private boolean doExport(Long rawSn, boolean forceRegenerate) {
      try { exportService.export(rawSn, forceRegenerate); return true; }
      catch (Exception e) { log.warn(...); return false; }
  }
  ```
  그런데 `DatasetExportService.export` 는 **예외 없이 정상 반환하면서 실패로 종결하는 경로가 4종**이다:
  ```java
  // DatasetExportService.java:205-210  ← 실측 재현된 경로
  if (totalWritten == 0) {
      txService.markFailed(inserted.exportSn());
      log.warn("[DatasetExport] nothing produced — marked FAILED rawSn={} version={}", ...);
      outcome = OUTCOME_FAILED;      // ← throw 없음 → doExport 는 true 반환
  }
  ```
  나머지 3종도 동일하게 `return`/정상 종료다 — `markBaseRejected`(경로 거부, `:167-171`), `OUTCOME_VERSION_EXHAUSTED`(`:176-180`), `OUTCOME_NO_INPUT`(`:141-145`), 그리고 쓰기 중 `catch (RuntimeException)` → `markFailed` 후 정상 반환(`:230-237`).
  결과적으로 `runApprovalAsync` 가 `DatasetExportCompletedEvent` 를 발행하고 `ControlNotifyEventListener.onExportCompleted` → `sendCompleted` 로 통지가 나간다.
  **실측 재현(2026-08-01, rawSn=72)** — 4ms 간격의 backend 로그:
  ```
  23:54:53.215 WARN  DatasetExportService  - [DatasetExport] nothing produced — marked FAILED rawSn=72 version=1
  23:54:53.219 INFO  ControlNotifyService  - [ControlNotify] TASK_COMPLETED sent rawSn=72 actual=TASK_COMPLETED
  ```
  DB: `ls_dataset_export{export_sn=18, data_raw_sn=72, export_ver_no=1, export_stts_cd='FAILED'}` / `v_completed_video where raw_sn=72` → **0행**. 즉 관제는 존재하지 않는 산출물에 대한 완료 통지를 받았다.
- **재현/확인 경로**:
  ```sql
  -- 실패 export 와 그 직후 발송된 완료 통지의 공존
  SELECT export_sn, data_raw_sn, export_ver_no, export_stts_cd, reg_dt
    FROM ls_dataset_export WHERE data_raw_sn = 72;         -- FAILED
  SELECT queue_sn, evnt_type_cd, raw_sn, send_rslt_cd, reg_dt
    FROM ls_control_notify_fallback WHERE raw_sn = 72;      -- TASK_COMPLETED / SUCCESS
  SELECT count(*) FROM v_completed_video WHERE raw_sn = 72; -- 0
  ```
  ```bash
  docker logs klid-backend 2>&1 | grep -E "nothing produced|TASK_COMPLETED sent rawSn=72"
  ```
  ⚠ 위 SQL 3건은 **검증 직후(15:0x UTC) 다른 QA 세션이 `ls_data_raw#72` 를 삭제**해 현재는 0행을 돌려준다(FK CASCADE 로 export·notify 행 동반 삭제). 위 DB 스냅샷은 삭제 전 실측이며, **영속 근거는 backend 로그**(위 `grep`)다.
  일반 재현: 프레임 원천 이미지가 실재하지 않는(=writer 가 전량 skip 하는) 영상을 검수 승인한다 — `[DatasetExport] nothing produced — marked FAILED` 직후 `[ControlNotify] TASK_COMPLETED sent` 이 이어지면 재현된 것이다.
- **영향**: 데이터정합 CRITICAL — 관제가 ①최초 승인 실패 시 경로 없는 job 을 등록하고 ②재승인 실패 시 **구 버전 폴더를 최신으로 오인**해 라벨 동기화가 영구 stale 이 된다(사업 요구 "데이터마트 학습데이터셋의 라벨링 정보 동기화" 위반). 회수기(`DatasetExportFailureRecoverer`)가 나중에 성공시켜도 그 사이 관제 보유본은 잘못돼 있고, 통지 정합성 전제(TC-NOTIFY-041)가 무너진다.
- **왜 자동테스트가 못 잡았나**: `AsyncDatasetExportRunnerTest` 의 실패 케이스 3건이 전부 `doThrow(new RuntimeException(...)).when(exportService).export(...)` — **예외 경로만** 검증한다(`:87`, `:113`). 예외 없이 FAILED 로 마감되는 실제 다수 경로에 대한 단언이 없다.
- **수정 방향(제안)**: `DatasetExportService.export` 가 결과(성공/실패 outcome)를 **반환값 또는 예외로 상위에 알리게** 한다. 최소 변경안은 ①`export` 의 시그니처를 `boolean`(또는 outcome enum) 으로 바꿔 `doExport` 가 그대로 관통시키거나, ②비-차단 실패 종결(`OUTCOME_FAILED`/`VERSION_EXHAUSTED`)도 신고 게이트처럼 예외로 이탈시키는 것. `NO_INPUT`/`IDEMPOTENT_SKIP` 은 "성공은 아니지만 통지해도 되는가" 를 별도 판단해야 하므로 outcome enum 방식(①)이 안전하다. 아울러 `AsyncDatasetExportRunnerTest` 에 "예외 없이 FAILED 로 마감되면 완료 이벤트 미발행" 회귀 테스트를 추가한다. ⚠ 본 검증에서는 구현하지 않음.

### [D-ISSUE-62] TC-NOTIFY-051 — 관제 통지 요청에 인증 헤더(`x-access-token`)가 부착되지 않는다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 관제 inbound SPI 계약(API-251/API-285)이 `x-access-token` 을 요구한다. 실서버 연동 시 인증 헤더가 없으면 전 통지가 401 로 거부되고, 폴백 큐가 5회 재시도 후 전량 dead-letter 로 고착된다(관제 동기화 전면 중단).
- **현재 동작(이슈 내용)**: 통지 전용 WebClient 가 base-url 만 설정한다.
  ```java
  // WebClientConfig#controlNotifyWebClient
  @Bean(name = "controlNotifyWebClient")
  public WebClient controlNotifyWebClient(@Value("${authoring.control-notify.url:http://localhost:8090}") String baseUrl) {
      return WebClient.builder().baseUrl(baseUrl).build();   // defaultHeader 없음
  }
  ```
  `ControlNotifyClient.doPost`(`:90-108`)도 `contentType(APPLICATION_JSON)` 만 설정한다. 비교 대상인 `vlmWebClient` 는 `vlm.client.token` 이 있으면 `Authorization: Bearer` 를 붙이는 배선이 있어 **관제 쪽만 누락**이다. 목서버는 인증을 요구하지 않아 로컬에서는 202 로 통과한다(로컬 통과가 실환경 계약을 보증하지 못하는 전형).
- **재현/확인 경로**: `docker logs klid-mock-server` 의 통지 요청은 전부 202 — 목서버가 인증을 검사하지 않기 때문. 코드상 헤더 주입 지점 부재는 `grep -n "defaultHeader" backend/src/main/java/kr/co/cudo/authoring/common/config/WebClientConfig.java` 로 확인(관제 빈에는 없음).
- **영향**: 기능/연동 — 실환경 401 로 통지 전량 실패. 보안 관점에서는 반대로 **인증 없는 outbound 를 관제가 수용하면** 통지 위조 표면이 되므로 양쪽 모두 협의가 필요(CWE-306 계열, 수신측 책임).
- **수정 방향(제안)**: `authoring.control-notify.token`(환경변수 주입, 평문 커밋 금지) 설정키를 신설하고 `controlNotifyWebClient` 에 `defaultHeader("x-access-token", token)` 를 조건부 배선한다(빈 값이면 미부착 + 기동 WARN). 실제 헤더명·값 형식은 **관제팀 확정 필요**(UNCERTAINTIES #26). 목서버에도 헤더 검사 옵션을 추가해 계약을 실왕복으로 고정하는 것이 바람직하다. ⚠ 본 검증에서는 구현하지 않음.

### [D-ISSUE-63] TC-NOTIFY-003 — `event_type_cd` 가 매핑 없이 pass-through 되어 서로 다른 코드체계가 섞여 나간다
- **심각도**: MEDIUM (확인필요 — 관제 코드값 목록 미수령)
- **기대 동작(기대효과)**: 관제 `datasets.event_type_cd` 는 관제 8대 이벤트 코드값 도메인을 따라야 한다. 저작도구 보유값이 그대로 나가면 관제가 미지의 코드로 적재하거나 거부한다.
- **현재 동작(이슈 내용)**:
  ```java
  // ControlNotifyPayloadFactory.java:139-141
  private String toControlEventTypeCd(String authoringEventTypeCd) {
      return authoringEventTypeCd;   // 매핑표 미수령 → pass-through
  }
  ```
  실측 결과 **DB 안에 이미 두 계통의 값이 공존**한다: `ls_data_raw.evnt_type_cd` = `INTRUSION`(raw 4·5·18, 영문 의미어) / `EV02000201`(raw 26, 관제 코드형). 그대로 통지되어 `ls_control_notify_fallback` 페이로드에도 두 계통이 그대로 남아 있다(queue_sn 1·3·9 = `INTRUSION`, 12·15 = `EV02000201`).
- **재현/확인 경로**:
  ```sql
  SELECT DISTINCT evnt_type_cd FROM ls_data_raw;                    -- INTRUSION, EV02000201 …
  SELECT payload_cn FROM ls_control_notify_fallback WHERE evnt_type_cd='TASK_COMPLETED';
  ```
- **영향**: 외부연동 계약 — 관제 적재 실패 또는 이벤트 유형 오분류. 데이터 자체의 정합(적재 소스별 코드체계 혼재)도 함께 드러난다.
- **수정 방향(제안)**: 관제 8대 코드 매핑표 수령 후 `toControlEventTypeCd` **한 곳**에서 allowlist 매핑(미매핑 값은 fail-closed 또는 지정 기본값)으로 교체. 동시에 적재 경로(`TrainingVideoIngestService` 등)에서 `EVNT_TYPE_CD` 코드체계를 하나로 정규화할지 별도 판단 필요. ⚠ 본 검증에서는 구현하지 않음. UNCERTAINTIES #27 과 동일 항목.

### [D-ISSUE-64] TC-NOTIFY-019 — 백오프 javadoc(1,2,4,8,16)이 실제 동작(2,4,8,16,32)과 어긋난다
- **심각도**: LOW
- **기대 동작(기대효과)**: 운영자가 재시도 스케줄을 문서로 예측할 수 있어야 한다(장애 대응 시 "언제 다시 시도되나" 판단 근거).
- **현재 동작(이슈 내용)**:
  ```java
  // LsControlNotifyFallback.java:188-210
  /** <p>백오프: 2^retryCount 분 (1, 2, 4, 8, 16). cap 60분. */
  public boolean failAndSchedule(String error) {
      this.rtryCnt += 1;                                            // ← 증가 후 계산
      long backoffMin = Math.min(60L, 1L << Math.min(this.rtryCnt, 6));   // n=1 → 2분
  ```
  실측: 첫 실패의 `next_rtry_dt` 가 `23:56:20 → 23:58:20` = **2분**(javadoc 은 1분). 실제 수열은 2,4,8,16,32,(60 cap).
- **재현/확인 경로**: `SELECT rtry_nmtm, mdfcn_dt, next_rtry_dt FROM ls_control_notify_fallback WHERE idmp_key='qa-d6-badjobid-1';`
- **영향**: 문서 정합만(동작 결함 아님). 기대 총 재시도 소요시간이 실제의 절반으로 오인된다.
- **수정 방향(제안)**: javadoc 을 `(2, 4, 8, 16, 32 …cap 60)` 으로 정정하거나, 의도가 1분 시작이면 `1L << (rtryCnt-1)` 로 코드를 맞춘다(둘 중 무엇이 의도인지 확인 필요). ⚠ 본 검증에서는 구현하지 않음.

---

## 3. 미완결·제약 사항

| 항목 | 내용 |
|------|------|
| TC-NOTIFY-041 실동작 | 실패 export(`export_sn=18`) 회수는 `retry-delay-minutes=10` + Quartz `interval-sec=900`(15분) 조건이라 회수 tick 도달 전에 **다른 QA 세션이 `ls_data_raw#72` 를 삭제**해 관측 창이 사라졌다(회수기 로그는 계속 `no retryable failed export`). 회수 **로직 자체**는 `DatasetExportFailureRecoveryIT` 가 커버. |
| TC-NOTIFY-044 | 크로스노드는 2노드 기동이 필요해 실동작 미검증(조건부 원자 UPDATE 정적 확인 + `ControlNotifyDebounceCrossNodeIT` 로 대체). |
| TC-NOTIFY-043 | 토글 off 형상(dev/stg/prd 기본)은 본 스택이 `CONTROL_NOTIFY_ENABLED=true` 라 실동작 미관측(정적 + 테스트 2건). |
| 병행 간섭 | 검증 중 다른 QA 세션이 같은 스택에서 rawSn 26·72·900001 을 구동했다. 본 문서의 로그·DB 근거에는 그 트래픽이 섞여 있으나, 각 근거는 rawSn·queue_sn·타임스탬프로 특정되어 판정에 영향 없음. |
| 잔여 테스트 데이터 | `ls_control_notify_fallback` 의 `idmp_key LIKE 'qa-d6-%'` 7행은 검증 흔적으로 남겨 둠(기존 행 미변경). 정리 필요 시 `DELETE FROM ls_control_notify_fallback WHERE idmp_key LIKE 'qa-d6-%';` |

## 4. 근거 드리프트 (카탈로그 file:line 정합성)

| TC | 카탈로그 표기 | 실제 위치 | 비고 |
|----|------|------|------|
| TC-NOTIFY-008 | `ControlNotifyDebouncer.java:174-176` | `:175-177` | +1 |
| TC-NOTIFY-009 | `:80,190-203` | `:81,191-204` | +1 |
| TC-NOTIFY-010 | `:100-103,141-166` | `:101-104,142-167` | +1 |
| TC-NOTIFY-011 | `:289-320` | `:292-295,312-321` | 요약 로그 위치 이동 |
| TC-NOTIFY-025 | `ControlNotifyDebouncer.java:72-74` | `:73-75` | +1 |
| TC-NOTIFY-043 | `ControlNotifyDebouncer.java:285-308` | `:286-309` | +1 |
| TC-NOTIFY-044/045 | `:241-269` / `:82-83` | `:242-270` / `:84` | +1 |
| TC-NOTIFY-019/020/023 | `LsControlNotifyFallback.java:failAndSchedule` / `:sanitize` | `:194-213` / **`sanitizeError` `:246-253`** | 메서드명 드리프트(`sanitize`→`sanitizeError`) |
| TC-NOTIFY-047 | `ControlNotifyPayloadFactory.java:65-66,150-174` | 동일 | 정합 |

> 그 외 인용 라인(ControlNotifyService·ControlNotifyClient·PayloadFactory·DTO·AsyncDatasetExportRunner·DatasetExportBridge·DatasetExportFailureRecoverer)은 **전부 정합**.

## 5. UNCERTAINTIES 갱신 제안

- **#3 TASK_COMPLETED payload** → **확정 종결 가능**. 6필드 평면 snake_case + 경로 `/api/data-set/v2/jobs/{job_id}/notify-*` 를 실왕복 페이로드 원문으로 확증, 상수 self-fill 0건.
- **#26 관제 통지 인증 헤더 미부착** → **사실 확정**(코드상 미배선). 관제팀 협의 항목으로 유지 + D-ISSUE-62.
- **#27 관제 `event_type_cd` 코드값 목록 미수령** → **사실 확정 + 악화 근거 추가**. DB 안에 `INTRUSION`/`EV02000201` 두 계통이 공존한 채 그대로 전송된다(D-ISSUE-63).
- **신규**: `AsyncDatasetExportRunner` 의 "export 성공 후에만 통지" 규약이 **예외 경로에서만** 성립한다는 사실(D-ISSUE-61) — 5C HIGH-D 항목의 재검토 필요.
