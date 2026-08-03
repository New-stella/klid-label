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
