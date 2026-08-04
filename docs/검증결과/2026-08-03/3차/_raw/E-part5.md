# E 클러스터 part5 — E-6. 데이터셋 Export (3차, 2026-08-03)

> 담당: `docs/test-cases/E-augment-resolution-export-meta.md` **E-6. 데이터셋 Export**(243~269행) — **23건**
> 대상 코드: `DatasetExportService` / `DatasetExportBridge` / `AsyncDatasetExportRunner` / `DatasetExportTxService` / `DatasetExportFailureRecoverer` / `DatasetExportPathResolver` / `DatasetExportWriter`
> 검증 방식: **실동작 최우선** — 풀스택(`klid-postgres`·`klid-backend`:18081·`klid-mock-server`:9400·`klid-ai-server`·`klid-frontend`) 위에서 실제 API 호출 + DB 조회 + 백엔드/목업 로그 + 파일시스템 실측. 프로덕션 코드·설정 **미변경**(DB 데이터·파일 mtime 조작만 — 각 항목에 명시).

---

## 0. 이번 파트에서 사용한 실동작 시나리오

| # | 시나리오 | 대상 | 결과 |
|:-:|---------|------|------|
| S1 | 승인 완료 영상에 **라벨 수정** → 디바운스 flush → 재export | rawSn=101 (srcSn=468, labelSn=729) | v3 신규 생성 + v1·v2 보존 |
| S2 | **비식별 누락 신고** 접수 후 재export 트리거 | rawSn=94 (srcSn=448) | export 차단(행 미생성) + 통지 보류 |
| S3 | **신고 해소(resolve)** → 보류분 복구 | rawSn=94 | v7 재생성 + TASK_COMPLETED 재개 |
| S4 | **산출 base 를 허용 루트 밖으로**(`/etc/evil/clip.mp4`) 조작 → 재export | rawSn=94 | FORBIDDEN → v8 FAILED(path=null) + 통지 보류 |
| S5 | **재승인**(제출→시작→승인) 2회 — 2회차는 **무수정** | rawSn=94 | v9·v10 신규 생성(v10 은 v9 와 **동일 해시**인데도 생성) |
| S6 | 다른 에이전트가 만든 **입력 부재 영상** 재export 관측 | rawSn=906 | `NO_INPUT` → 통지 보류 |
| S7 | **파생영상(증강) export** 산출물 실측 | rawSn=18 (ORGNL_RAW_SN=4) | `v1/deid` 1벌만, `orgnl` 폴더 부재, SUCCEEDED |
| S8 | **실패 export 회수기** — 최신 FAILED 앵커 생성 후 주기 잡 관측 | rawSn=94 v11 | §5 참조 |

> 검증용 데이터 조작(코드/설정 무변경): ①`ls_data_raw.raw_file_path_nm` 임시 변경 후 **원복 확인**(S4·S8) ②비식별 산출물 `touch`(신고 resolve 게이트 통과용, 1차와 동일 방식) ③프레임 설명/라벨 좌표 수정(정상 API).

---

## 1. 케이스별 판정

| ID | 판정 | 근거 확인 |
|----|:--:|----------|
| TC-EXPORT-001 | PASS | [실동작] rawSn=94 재승인 커밋 직후 `01:51:00.030 [DatasetExportBridge] … → AsyncDatasetExportRunner - async approval export starting rawSn=94` → v9 산출. AFTER_COMMIT 이후에만 발화(승인 실패 시나리오 `REVIEW_NO_LABEL` 400 에서는 export 로그 0건 — 01:50:36 승인 차단 시 러너 미발화 실측). [정적] `DatasetExportBridge.java:36-43` `runner.runApprovalAsync(rawSn)`(force=true) — **근거 정확** |
| TC-EXPORT-002 | PASS | [실동작] **무수정 재승인**(S5 2회차) → `export_ver_no=10` 신규 생성. `content_hash` 가 직전 baseline(v9 PARTIAL, `e82c89554414affc…`)과 **완전 동일한데도** skip 되지 않고 v10 폴더에 파일 20개 신규 기록. [정적] `:154-160` `if (!forceRegenerate && prep.isUnchangedFromLastExport())` — 승인 경로는 조건 자체를 건너뜀. **근거 정확** |
| TC-EXPORT-003 | PASS | [정적] `:154-160`(멱등 skip) + `DatasetExportTxService.java:144-154` baseline 조회가 `List.of(STATUS_SUCCEEDED, STATUS_PARTIAL)` IN 필터 → PARTIAL 포함 확인. 행 미INSERT + `outcome=idempotent_skip` metric+log 만. ⚠ **실동작 도달 불가** — 유일한 트리거 `DatasetReExportEvent` 의 **발행처가 0건**(`DatasetExportBridge.java:50-53` javadoc "휴면 리스너", `EvntAnnoReviewService.java:224-227` 이 이중 export 때문에 제거). 런타임 metric `dataset.export.result{outcome=idempotent_skip}` **태그 자체가 미생성**(availableTags 에 없음)으로 교차 확인. → **카탈로그에 휴면 사실 + baseline 근거 추가 정정**(§3) |
| TC-EXPORT-004 | PASS | [실동작] rawSn=101 v3: `frames written kind=ORIGINAL written=10 skipped=0` + `kind=DEIDENTIFIED written=10 skipped=0` → `export succeeded rawSn=101 version=3 written=20`, `export_stts_cd=SUCCEEDED`. `EXPORT_PATH_NM=/app/storage/raw/seed/101` = `dirname('/app/storage/raw/seed/clip-9101.mp4')/101` — **버전 루트 아님**(v1·v2·v3·deid 가 모두 이 아래 형제). [정적] `DatasetExportPathResolver.java:45-47 resolveVideoRoot` — **근거 정확** |
| TC-EXPORT-005 | PASS | [실동작] rawSn=94(유령 프레임 frm_no=99 보유) → `frame image missing skipped … frameNo=99`(ORIGINAL/DEID 각 1) → `partial export rawSn=94 version=7 written=10 skipped=2` → `export_stts_cd=PARTIAL`. metric `dataset.export.skipped_frames` 가 **2.0 → 6.0**(PARTIAL 3회 × 2)으로 증가 확인. [정적] `:215-226` — **근거 정확** |
| TC-EXPORT-006 | PASS | [정적] `:209-214` `if (totalWritten == 0) { txService.markFailed(...); outcome = FAILED; }` — `throw` 없음 → 승인 롤백 경로 없음(@Async 분리). 단위테스트 `DatasetExportServiceTest.아무것도_산출못하면_FAILED로_전이한다 — totalWritten==0` 커버(baseline 실패 0건). 실동작은 "전 프레임 이미지 부재" 상태를 만들어야 해 미유도. ⚠ 이 분기가 남기는 **빈 `v{n}` 디렉터리**는 정리되지 않는다 → **E-ISSUE-83** |
| TC-EXPORT-007 | PASS | [실동작] 파생영상 rawSn=18(`ORGNL_RAW_SN=4`) — `ls_data_src` 30행 중 `src_file_path_nm` **0건**/`de_idntf_src_file_path_nm` 30건. 산출물 트리: `/app/storage/deidentified/videos/augment/4/18/18/v1/deid/` **딱 하나**(파일 60개=30jpg+30json), `v1/orgnl` **디렉터리 자체가 없음**. `export_stts_cd=SUCCEEDED`(PARTIAL 강등 아님), `frame_cnt=30`. [정적] `:191-203`(originalAbsent 분기) + `:336-342 hasNoOriginalFrames` — **근거 정확** |
| TC-EXPORT-008 | PASS | [정적] `:373-383 insertWithRetry` — `for (attempt=1..MAX_VERSION_RETRY=3)` + `catch (DataIntegrityViolationException)` 재채번. `DatasetExportTxService.insertNextVersion` 이 `REQUIRES_NEW` + `saveAndFlush` 라 UK 위반이 그 트랜잭션 안에서 즉시 터지고 rollback-only 가 격리됨(`:173-183`). 테스트 `동시_승인_UK위반시_재시도로_다음버전_채번된다` + IT `LS_DATASET_EXPORT_UK_중복_버전_삽입시_제약위반`. **근거 정확** |
| TC-EXPORT-009 | PASS | [정적] `:177-182` `inserted == null` → `log.error(version numbering exhausted)` + `outcome = VERSION_EXHAUSTED` + `return`(abort). `DatasetExportOutcome.VERSION_EXHAUSTED` 의 `notifiable=false`(`:52`) → 통지도 보류. 테스트 `UK위반이_재시도_상한_초과하면_산출을_중단한다` + `재채번_소진시_result_version_exhausted_1회`. **근거 정확** |
| TC-EXPORT-010 | PASS | [정적] `:238-246` `catch (RuntimeException e) { txService.markFailed(inserted.exportSn()); log.warn(… cause={}, e.getClass().getSimpleName()); outcome = FAILED; }` — 예외 미전파(승인 불변), 로그에 **예외 클래스명만**(메시지·스택·경로 원문 없음, CWE-209/359). 테스트 `파일산출_실패해도_승인은_롤백되지_않는다`. **근거 정확** |
| TC-EXPORT-011 | PASS | [실동작] rawSn=906 재export flush 시 `01:42:36.066 DatasetExportTxService - no active video meta — skip export rawSn=906` → `AsyncDatasetExportRunner - async export not notifiable — notify withheld rawSn=906 outcome=NO_INPUT`. `ls_dataset_export` 에 906 행 **0건**(행 미INSERT), metric `outcome=no_input` 카운트 4→5 증가. [정적] `:147-151` — **근거 정확** |
| TC-EXPORT-012 | PASS | [실동작] 런타임 metric 실측 — `dataset.export.result` COUNT **33.0**, `dataset.export.duration` COUNT **33.0**(정확히 1:1). 태그별 합 `completed 21 + partial 3 + failed 2 + no_input 5 + deident_blocked 2 = 33` 로 **배타 1회** 확인. **예외 이탈 경로(deident_blocked 2건)도 누락 없이 계상**(`finally` 단일 지점). [정적] `:110`(startSample) + `:261-269`(finally) — **근거 정확** |
| TC-EXPORT-013 | PASS | [정적] `DatasetExportBridge.java:29-30` `@ConditionalOnProperty(prefix="authoring.dataset-export", name="enabled", havingValue="true", matchIfMissing=true)`. `DatasetExportBridgeBeanConditionTest` 3건(true 로드 / false 미로드 / 미설정 matchIfMissing 로드) 커버·통과. 현 환경은 기본(미설정)이라 브릿지 활성 — 실동작(S1·S5)이 이를 뒷받침. **근거 정확** |
| TC-EXPORT-014 | PASS | [실동작] `find /app/storage/raw/seed/101` → `101/deid/clip-9101-mask.mp4` · `101/v1|v2|v3/{orgnl,deid}/0000.jpg~0009.jpg + 0000.json~0009.json`. 고정 `labeling_root` 흔적 0건. 파생영상도 동일 규약(`…/augment/4/18/18/v1/deid`). [정적] `DatasetExportPathResolver.java:58-68 resolve` → `resolveUnder(videoRoot, "v"+version, kind.segment())`. **근거 정확** |
| TC-EXPORT-015 | PASS | [실동작] `UPDATE ls_data_raw SET raw_file_path_nm='/etc/evil/clip.mp4' WHERE raw_sn=94` 후 재export → `01:48:46.358 ERROR [DatasetExport] export base rejected — marked FAILED rawSn=94 reason=FORBIDDEN`. DB: `export_ver_no=8, export_stts_cd=FAILED, export_path_nm=NULL`(레코드는 남음 — 흔적 없이 사라지지 않음). **기본 루트로의 조용한 폴백 0건**(`/app/storage/**` 어디에도 v8 디렉터리 미생성). 로그에 경로 원문 없음(`reason=FORBIDDEN` 만, CWE-209). 통지도 `notify withheld … outcome=FAILED` 로 보류. [정적] `:166-173`(try/catch → markBaseRejected) + `:351-366` + `VideoArtifactRootResolver`(allowlist → normalize → toRealPath 3단). **근거 정확** |
| TC-EXPORT-016 | PASS | [실동작] rawSn=94 에 `POST /v1/labels/448/deident-report` → `DE_IDENT_YN='F'`. 이후 승인후수정 flush(`01:45:06.170 flush rawSn=94 regen=true`) → `01:45:06.171 WARN [DatasetExport] export blocked — deident report open rawSn=94` → `async export failed rawSn=94 cause=CustomException`. **`ls_dataset_export` 행 미생성**(6행 그대로, FAILED 행도 없음), metric `outcome=deident_blocked` +1, **mock-server 인바운드 0건**(통지 함께 보류). [정적] `DatasetExportService.java:115-145`(진입부 단일 게이트) + `AsyncDatasetExportRunner.java:128-149`. **근거 정확** |
| TC-EXPORT-017 | PASS | [정적] `:184-259` — `finalizeUnlessUnderDeidentReport` 가 false → `deidentBlocked=true` → `:252-259` `purgeThisRunVersionDir` + `outcome=DEIDENT_BLOCKED` + `throw CustomException`(통지 보류). `:291-310` 삭제 범위가 `resolveVideoRoot`→`resolveUnder(videoRoot,"v"+version)`→`verifyRealPathUnder` 3중 가드 통과 시에만, 실패하면 **아무것도 삭제 안 함**(fail-secure). `DatasetExportTxService.java:224-239` 가 RAW 잠금 하 재판정 + PENDING 행 **삭제**(FAILED 아님). IT `DatasetExportDeidentReportGateIT.export_중_신고가_접수되면_산출물이_기록되지_않고_통지도_나가지_않는다` / `export_중_신고시_이번_실행이_만든_버전폴더가_삭제되고_이전_버전은_보존된다` 커버(baseline 실패 0건). ⚠ 실동작 재현 미실시 — 로컬 export 가 **5ms 내 완료**(실측 01:42:26.054→.072)라 쓰기 도중 신고 커밋을 끼워 넣을 창이 없음. **근거 정확** |
| TC-EXPORT-018 | PASS | [실동작] **성공 경로 순서 실증** — rawSn=101: `01:42:26.072 export succeeded version=3` → `01:42:26.076 ControlNotifyService - TASK_MODIFIED sent rawSn=101` → mock `16:42:26,075 notify-updated accepted job_id=101` (export 마감 후 통지, 동기 순서). rawSn=94 resolve 경로: `01:45:40.854 partial export version=7` → `.857 TASK_COMPLETED sent`. **반증(실패 시 보류) 3회 관측** — ①NO_INPUT(906) ②FAILED/base 거부(94 v8) ③DEIDENT_BLOCKED(94) 모두 `notify withheld` + mock 인바운드 0건. [정적] `AsyncDatasetExportRunner.java:67-76`(doExport true 일 때만 `DatasetExportCompletedEvent`) + `:134-143`(`outcome.notifiable()` 단일 판정, `null` fail-closed). **근거 정확** |
| TC-EXPORT-019 | PASS | [실동작] 디바운스 flush → `01:42:26.054 async re-export(+notify) starting rawSn=101 forceRegenerate=true` → export 성공 → 콜백 실행(TASK_MODIFIED). 실패 시(906 NO_INPUT) 콜백 미실행. [정적] `:90-106` — `boolean succeeded = doExport(...); if (succeeded && afterExport != null) afterExport.run();`, 콜백 타입이 `Runnable` 이라 통지 패키지 컴파일 의존 없음(import 목록에 controlnotify 0건). **근거 정확** |
| TC-EXPORT-040 | PASS | [실동작] `02:11:20.121 [DatasetExportRecovery] retriggered failed exports count=1 rawSns=[94] maxAttempts=3` → `runApprovalAsync` 재산출 v12 PARTIAL → 통지 재개(409 자기치유 → `notify-updated` 202). 앵커 행 v11 의 `RTY_NMTM 0→1`·`RTY_DT=02:11:20` 클레임 확인. **재시도 유예도 실증** — 앵커 생성 3분 후 tick 은 `no retryable failed export`(cutoff=now-10m), 18분 후 tick 에서 클레임. 상세 §5 |
| TC-EXPORT-041 | PASS | [실동작] `POST /v1/deident-reports/36/resolve` → `01:45:40.843 resolved-manually` → `01:45:40.846 [DatasetExportBridge] deident report resolved rawSn=94 — re-triggering withheld export/notify` → `async approval export starting`(=force=true 전량 재생성) → `partial export version=7` → `TASK_COMPLETED sent` → mock `notify-completed accepted job_id=94` 202. `DE_IDENT_YN` 이 `'F'→'Y'` 복원됨. **팬아웃 0건**(같은 tick 에 다른 rawSn export 로그 없음 — 범위는 그 영상 하나). [정적] `DatasetExportBridge.java:81-87`. **근거 정확** |
| TC-EXPORT-042 | PASS | [정적] 현 환경은 `CONTROL_NOTIFY_ENABLED=true`(컨테이너 env 실측)라 토글 off 실동작 불가 → 정적+테스트 판정. 보장 지점 3곳 확인 — ①`TaskModifiedAccumulateListener.java:27-36` **`@Component` 만, 조건부 어노테이션 0건**(javadoc: "구 구현은 통지 토글 종속 리스너 안에 뒀다가 dev/stg/prd 에서 재생성이 전혀 일어나지 않았다") ②`ControlNotifyDebouncer.java:85` `@Component`(무조건) + `:334-357 send()` 가 `notifyService==null` 여부와 무관하게 `exportRunner.runReExportThenNotify(rawSn, true, notifyCallback)` 호출(콜백만 null) ③반대로 `ControlNotifyEventListener.java:27` 만 `@ConditionalOnProperty(control-notify.enabled)`. 테스트 `ControlNotifyDebouncerTest.재export_트리거는_control_notify_토글과_무관하게_동작한다` + `HIGH-E_통지_토글_off여도_승인후_수정_재생성_윈도우는_export를_트리거한다` 커버. ⚠ **카탈로그 근거가 엉뚱한 토글을 가리키고 있었음 → 정정**(§3, 1차 E-ISSUE-82 이월분) |
| TC-EXPORT-043 | PASS | [실동작] **전 버전 보존 확인** — rawSn=94: `v1 v2 v4 v6 v7 v9 v10` 디렉터리 전부 잔존(v3·v5·v8·v11 은 base 거부/수동 FAILED 라 디렉터리 자체가 생성된 적 없음). rawSn=101: `v1 v2 v3`(+ 타 에이전트가 만든 v4·v5) 전부 잔존, 각 40 파일. **v1 의 라벨 좌표가 그 시점 값 그대로 동결**(bbox v1=`[50,50,150,150]` / v2=`[55,55,…]` / v3=`[60,60,…]`) — 과거 버전 덮어쓰기 0건. 삭제 잡 미구현 확인(retention 관련 스케줄러/서비스 0건). [정적] `:45-46` javadoc + `:175-176` TODO. **근거 정확** |

### 집계

| 판정 | 건수 | 케이스 |
|------|:--:|------|
| PASS | 23 | 001~019, 040~043 |
| FAIL | 0 | — |
| PARTIAL | 0 | — |
| BLOCKED / N/A / 확인필요 | 0 | — |
| **합계** | **23** | 실측 행 수 `grep -cE '^\| *~*TC-'`(243~269행) = 23, 폐기 케이스 0건 |

> ⚠ **"전건 PASS" 를 그대로 신뢰하지 말 것** — 케이스 단위 기대결과는 모두 충족했으나, **케이스가 다루지 않는 인접 계약에서 결함 3건**(§4)이 나왔다. 특히 E-ISSUE-81 은 "export 실패 시 통지 보류"(TC-EXPORT-016/018/019 PASS)의 **부작용**으로, 보류된 통지의 변경 프레임 목록이 복구되지 않는 문제다.

---

## 2. 이전 회차 이슈 대조

| 이전 이슈 | 케이스 | 이번 회차 판정 |
|---|---|---|
| **D-ISSUE-61 (1차, CRITICAL)** — export 가 예외 없이 실패로 마감돼도 TASK_COMPLETED/MODIFIED 발송 | TC-EXPORT-018/019 | **✅ 해소 확정(실동작 3회 실증).** `DatasetExportOutcome.notifiable()` 단일 판정 + `null` fail-closed(`AsyncDatasetExportRunner.java:134-143`). 예외 없는 실패 2경로(**NO_INPUT** rawSn=906 / **FAILED-base거부** rawSn=94 v8)와 예외 경로(**DEIDENT_BLOCKED** rawSn=94)에서 모두 `notify withheld` + mock-server 인바운드 0건. D-part3 의 rawSn=147 실험과 **독립적으로 재확인**됨 |
| **E-ISSUE-81 (1차, HIGH)** — PARTIAL export 가 통지는 되는데 `V_COMPLETED_VIDEO` 에서 배제 | TC-EXPORT-005/019 | **✅ 해소 확정(라이브 DB).** `pg_get_viewdef` 실측: LATERAL 조인 조건이 `ex.export_stts_cd = ANY (ARRAY['SUCCEEDED','PARTIAL'])`(V160). rawSn=94 의 최신 산출이 **PARTIAL(v7→v9→v10)** 인데 `v_completed_video` 가 `export_path_nm=/app/storage/raw/autolabel-test/94, frame_cnt=10` 로 정상 노출. 뷰에 `export_stts_cd` 컬럼도 신설돼 관제가 부분산출을 식별 가능. 멱등 baseline(`DatasetExportTxService.java:144-154`)·통지 판정(`DatasetExportOutcome.PARTIAL notifiable=true`)·뷰 **3곳이 PARTIAL 로 일치** |
| **E-ISSUE-82 (1차, LOW)** — TC-EXPORT-042/040 근거 file:line 드리프트 | TC-EXPORT-042/040 | **부분 해소 → 이번 회차 잔여분 정정.** TC-EXPORT-040 근거(`DatasetExportTxService.java:247-261`)는 이미 정정돼 있었고 실측(`claimForRetry` javadoc 247-256 + 메서드 257-261)과 일치. **TC-EXPORT-042 는 미정정 상태로 남아 있었음** — `DatasetExportBridge.java:29-30 (자체 토글만 참조)` 는 `authoring.dataset-export.enabled`(=TC-EXPORT-013 근거)이지 control-notify 토글과의 무관성을 보장하는 코드가 아니다. **이번 회차에 카탈로그 직접 정정**(§3) → **E-ISSUE-82** 로 재등록(추적용) |
| **E-ISSUE-83 (1차, MEDIUM)** — 신고/실패로 export 가 보류되면 디바운스 윈도우가 이미 `complete` 처리돼 변경 프레임 목록이 유실 | TC-EXPORT-016/019 | **❌ 미해소 이월(실동작 재확인).** `ControlNotifyDebouncer.java:303-306` 가 여전히 `send(window); store.complete(window.acmlSn());` 이고, `send()` 의 `runReExportThenNotify` 는 `@Async` 라 즉시 반환한다. 실측: `01:45:06.170 flush rawSn=94 regen=true frames=448=[META_UPDATED]` → `.171 export blocked` → 이후 `ls_mon_noti_acml` 의 rawSn=94 행 **0건**(재클레임 없이 소멸). resolve 후 나간 통지는 M1 경로의 **영상 단위 `TASK_COMPLETED`** 라 `frames=448` 변경 목록을 담지 않음 → **E-ISSUE-81** 로 재등록 |

---

## 3. 카탈로그 정정 (담당 라인범위 내 직접 Edit) — 2건

| # | 대상 | 정정 내용 |
|:-:|------|----------|
| 1 | **TC-EXPORT-042** 근거 | `DatasetExportBridge.java:29-30 (자체 토글만 참조)` → **`TaskModifiedAccumulateListener.java:27-36; ControlNotifyDebouncer.java:44-55,85,334-357; ControlNotifyEventListener.java:27 (토글 종속은 여기만)`**. 기대결과에 보장 지점 3곳(축적 리스너 무조건 활성 / 디바운서 `send()` 무조건 위임 / 통지 리스너만 토글 종속)을 명시. **사유**: 기존 근거는 `authoring.dataset-export.enabled` 토글(TC-EXPORT-013 근거와 동일 라인)이라, 근거를 따라가면 **다른 토글을 검사하게 되어 잘못된 PASS/FAIL 을 유발**한다(1차 E-ISSUE-82 미해소분) |
| 2 | **TC-EXPORT-003** 기대결과·근거 | ⚠ **유일한 발행 트리거 `DatasetReExportEvent` 가 휴면**(발행처 0건)이라 실동작 도달 불가라는 사실을 기대결과에 명시. 근거에 `DatasetExportBridge.java:50-61(휴면 사유)` 와 **PARTIAL baseline 의 실제 보장 지점 `DatasetExportTxService.java:144-154`** 추가. **사유**: 케이스가 "PARTIAL 도 멱등 baseline 에 포함"을 단언하는데 기존 근거 2곳 어디에도 그 IN 필터가 없었고, 검증자가 실동작으로 재현하려다 "미구현"으로 오판할 수 있다 |

> 프로덕션 코드는 수정하지 않았다. `## 변경 이력` 표(파일 5~18행)는 담당 라인범위(243~269) 밖이라 **병렬 에이전트 충돌 방지를 위해 손대지 않았다** — 병합 시 위 2건을 회차 행에 반영 필요.

---

## 4. 신규/이월 이슈

### [E-ISSUE-81] TC-EXPORT-016 / TC-EXPORT-019 — export 가 보류·실패해도 디바운스 윈도우가 `complete` 처리돼 **그 수정 통지의 변경 프레임 목록이 영구 유실**된다 (1차 E-ISSUE-83 이월, 실동작 재확인)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `ControlNotifyDebouncer.claimAndSendIsolated` 의 자체 계약 — *"실패한 윈도우는 `complete` 를 호출하지 않아 저장소에 FLUSHING 으로 남는다 … 통지가 **소실되지 않고 지연**된다"*(`ControlNotifyDebouncer.java:284-286`). 또 `CLAUDE.md` 는 TASK_MODIFIED 페이로드에 **변경 프레임 목록**(`SRC_SN` + 변경 종류)을 담도록 규정한다. 즉 export 가 보류되면 그 윈도우의 축적분도 함께 보류됐다가 재개돼야 한다.
- **현재 동작(이슈 내용)**: 재생성 동반 윈도우(`exportRegenerated=true`)의 실제 산출·통지는 `@Async` 러너에 위임되고 **즉시 반환**하므로, 그 뒤의 export 차단/실패가 `send()` 의 예외로 관측되지 않는다. 결과적으로 윈도우가 무조건 `complete` 로 마감돼 축적분이 사라진다.
  ```java
  // ControlNotifyDebouncer.java:344-351  (send)
  if (window.exportRegenerated()) {
      Runnable notifyCallback = notifyService == null ? null
              : () -> notifyService.sendModified(rawSn, frameChanges, videoLevelChangeTypes, true);
      exportRunner.runReExportThenNotify(rawSn, true, notifyCallback);   // @Async — 즉시 반환
      return;
  }
  // ControlNotifyDebouncer.java:303-306  (claimAndSendIsolated)
  send(window);
  store.complete(window.acmlSn());   // ← export/통지 결과와 무관하게 마감
  ```
  **실측(2026-08-04, rawSn=94)**:
  ```
  01:45:06.170 [ControlNotifyDebounce] flush rawSn=94 regen=true frames=448=[META_UPDATED]
  01:45:06.171 WARN [DatasetExport] export blocked — deident report open rawSn=94
  01:45:06.171 WARN [DatasetExport] async export failed rawSn=94 cause=CustomException
  ```
  ```sql
  -- flush 직후: 해당 rawSn 의 축적 윈도우가 상태 불문 0건 (FLUSHING 잔존 아님 = 재클레임 경로 없음)
  SELECT * FROM ls_mon_noti_acml WHERE raw_sn = 94;   -- (0 rows)
  ```
  신고 해소(01:45:40) 시 나간 통지는 M1 재트리거(`runApprovalAsync`)의 **영상 단위 `TASK_COMPLETED`**(mock: `notify-completed accepted job_id=94`)라 `frames=448` 변경 목록을 담지 않는다. **base 거부(FAILED) 경로도 동일** — `01:48:46.358 export base rejected` 직후 rawSn=94 윈도우 0건.
- **재현/확인 경로**:
  ```bash
  # APPROVED 영상에 신고 접수 → 신고 구간에 승인 후 수정 → 60초 뒤 flush
  curl -X POST localhost:18081/api/v1/labels/{srcSn}/deident-report -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"reason":"probe"}'
  curl -X PUT  localhost:18081/api/v1/frames/{srcSn}/description   -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"srcSn":{srcSn},"description":"x"}'
  # 로그: flush(regen=true, frames=…) → export blocked → async export failed
  ```
  ```sql
  SELECT noti_acml_sn, stts_cd, chg_dtl_cn FROM ls_mon_noti_acml WHERE raw_sn = :rawSn;  -- 0 rows = 축적분 소실
  ```
- **영향**: 기능/데이터정합 — 관제가 받는 수정 통지에서 **어느 프레임이 바뀌었는지**가 빠진다(관제는 전량 재조회로만 복구 가능). 영상 단위 정합 자체는 M1 재통지·회수기 재산출로 회복되므로 CRITICAL 은 아니나, 디바운서가 문서화한 "지연될 뿐 소실 없음" 보증이 **재생성 경로에서만 깨져 동작이 자기 문서와 다르다**. 통지 토글이 꺼진 형상(dev/stg/prd 기본)에서는 `notifyCallback` 이 애초에 null 이라 영향 없음.
- **수정 방향(제안)**: ⓐ `runReExportThenNotify` 가 결과를 상위에 돌려주도록 하고(콜백 `onFailure` 또는 `CompletableFuture`), **비통지 종결(`notifiable()==false`)이면 윈도우를 `complete` 하지 않고 FLUSHING 으로 재개방**해 임차 만료 후 재클레임되게 한다 — 이미 존재하는 lease 복구 machinery 를 그대로 재사용하므로 신규 메커니즘이 필요 없다. 또는 ⓑ 재생성 윈도우는 러너 스레드에서 `complete` 를 호출하도록 소유권을 넘긴다. 최소 조치로 ⓒ 차단·실패 시 WARN 에 유실된 `frameChanges` 요약을 남겨 감사 가능하게 한다. D-part3 의 **D-ISSUE-41**(NO_INPUT 보류분 복구 경로 단절)과 **같은 축**("러너의 성공/실패를 상위가 알 수 있게 한다")이므로 함께 처리하는 것이 좋다. **구현은 하지 않는다.**

### [E-ISSUE-82] TC-EXPORT-042 — 카탈로그 근거(file:line)가 **다른 토글**을 가리킨다 (1차 E-ISSUE-82 미해소분, 이번 회차 정정 완료)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스의 `근거` 컬럼은 그 단언을 실제로 보장하는 코드 위치를 가리켜야 한다(다음 회차 재검증·수정 작업의 진입점).
- **현재 동작(이슈 내용)**: TC-EXPORT-042("재export 트리거는 control-notify 토글과 무관")의 근거가 `DatasetExportBridge.java:29-30 (자체 토글만 참조)` 였는데, 이 라인은 `authoring.dataset-export.enabled` 토글(= TC-EXPORT-013 의 근거와 **동일 라인**)이며 `authoring.control-notify.enabled` 와의 무관성을 보장하는 코드가 아니다. 실제 보장 지점은 ①`TaskModifiedAccumulateListener.java:27-36`(조건부 어노테이션 0건 = 항상 활성) ②`ControlNotifyDebouncer.java:44-55`(HIGH-E javadoc "이 빈은 control-notify.enabled 로 게이팅하지 않는다") + `:85`(`@Component`) + `:334-357`(`send()` 가 `notifyService` null 여부와 무관하게 `runReExportThenNotify` 호출) ③`ControlNotifyEventListener.java:27`(반대로 통지 리스너만 토글 종속).
- **재현/확인 경로**: `docs/test-cases/E-augment-resolution-export-meta.md` TC-EXPORT-042 행과 위 파일들을 대조. 1차 ISSUES.md `E-ISSUE-82` 에 동일 지적이 있었으나 카탈로그에 반영되지 않은 채 남아 있었다.
- **영향**: 기능 영향 없음. 카탈로그 자체의 정합성 결함 — 근거를 따라가면 다른 토글을 검사하게 되어 **잘못된 PASS/FAIL 판정**을 유발할 수 있다(3차 검증 착수 시 실제로 혼선 발생).
- **수정 방향(제안)**: **이번 회차에 카탈로그를 직접 정정 완료**(§3-1). 추가 조치는 `## 변경 이력` 표에 회차 행 반영뿐. **프로덕션 코드 변경 불필요.**

### [E-ISSUE-83] TC-EXPORT-006 / TC-EXPORT-004 — 실패한 export 가 남긴 `v{n}` 디렉터리가 정리되지 않는데, 뷰에 **버전 식별자가 없어** 관제가 최신 유효 버전을 판별할 수 없다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md` — *"`EXPORT_PATH_NM` 은 **영상 루트**를 가리킨다 — 관제가 `v1`·`v2` 를 한 경로 아래에서 보고 골라야 비교·복구가 가능하다"*. 즉 관제는 영상 루트 아래에서 **어느 `v{n}` 이 이번 통지가 가리키는 산출물인지**를 판별할 수 있어야 한다. 또 실패로 마감된 산출은 관제가 집을 수 있는 상태로 남아서는 안 된다.
- **현재 동작(이슈 내용)**: 두 사실이 겹친다.
  1. **뷰에 버전 컬럼이 없다.** `v_completed_video` 실측 컬럼 41개 중 export 관련은 `export_path_nm`(영상 루트)·`frame_cnt`·`export_stts_cd` 뿐이고 **`export_ver_no` 가 없다**. 통지 페이로드(6필드 평면: `job_id`·`event_type_cd`·`lclgv_cd`·`lclgv_nm`·`duration_sec`·`image_count`)에도 버전이 없다. 따라서 관제는 디렉터리명을 스캔해 `max(v)` 를 고르는 수밖에 없다.
  2. **실패 종결이 `v{n}` 디렉터리를 남긴다.** `DatasetExportWriter.java:83-89` 가 프레임 루프 **이전에** `Files.createDirectories(dir)` 를 무조건 수행한다.
     ```java
     Path dir = pathResolver.resolve(rawSn, rawFilePathNm, kind, version);
     createExportDir(dir, rawSn, kind, version);          // ← 프레임 0건이어도 생성됨
     …
     for (FrameContext frameCtx : frames) { … }
     ```
     그런데 `DatasetExportService.java:209-214`(산출 0건 → FAILED)와 `:238-246`(쓰기 중 예외 → FAILED) 어느 쪽도 `purgeThisRunVersionDir` 를 호출하지 않는다 — 삭제는 **신고 차단 경로(`:252-259`)에만** 배선돼 있다. retention 정리 잡도 없다(TC-EXPORT-043 확정 정책).
  - 결과: 최신 SUCCEEDED/PARTIAL 이 `v10` 인데 그 뒤 실패한 산출이 **비어 있거나 반쯤 찬 `v11`** 을 남기면, `max(v)` 로 고르는 관제는 **깨진 폴더를 최신 학습데이터로 픽업**한다. 뷰의 `frame_cnt`(=v10 기준)와 디스크 실체(v11)가 어긋나도 관제가 검출할 단서가 없다.
  - ⚠ 이번 실동작에서 남은 FAILED(rawSn=94 v8·v11)는 **base 거부** 유형이라 `pathResolver.resolve` 단계에서 막혀 디렉터리가 생성되지 않았다(`ls -d …/94/v*` → v1 v2 v4 v6 v7 v9 v10, v8·v11 없음). 즉 **base 거부는 안전하고, 산출 0건·쓰기 중 예외 두 유형만 해당**한다.
- **재현/확인 경로**:
  ```sql
  -- 1) 뷰에 버전 식별자가 없음
  \d+ v_completed_video      -- export_path_nm / frame_cnt / export_stts_cd 만, export_ver_no 없음
  -- 2) 산출 0건 유도: APPROVED 영상의 전 프레임 이미지 경로를 실재하지 않는 값으로 바꾼 뒤 재승인
  UPDATE ls_data_src SET src_file_path_nm = '/app/storage/raw/nope.jpg',
                         de_idntf_src_file_path_nm = '/app/storage/deidentified/nope.jpg'
   WHERE raw_sn = :rawSn;
  ```
  ```bash
  # 로그에 "nothing produced — marked FAILED rawSn=… version=N" 후
  docker exec klid-backend ls -R {영상루트}/v{N}     # orgnl/ deid/ 빈 디렉터리 잔존
  ```
- **영향**: 데이터정합 — 관제/데이터마트가 **깨진(빈·부분) 버전 폴더를 최신 학습데이터로 픽업**할 수 있다(사업 요구 *"데이터마트 학습데이터셋의 라벨링 정보 동기화"* 미충족). 부수적으로 빈 디렉터리가 무한 누적된다(retention 미구현 확정 정책이라 자연 정리되지 않음). 보안 영향은 없다(신고 차단 경로는 이미 purge 배선됨).
- **수정 방향(제안)**: ⓐ **뷰에 `EXPORT_VER_NO` 를 노출**(`V_COMPLETED_VIDEO` LATERAL 조인에 컬럼 추가)해 관제가 `max(v)` 추정 대신 명시값을 쓰게 한다 — 관제 계약 변경이므로 협의 필요하나 가장 근본적이다. ⓑ 실패 종결(`totalWritten==0`·쓰기 중 예외) 분기에서도 `purgeThisRunVersionDir(rawSn, prep.rawFilePathNm(), inserted.version())` 를 호출해 이번 실행이 만든 폴더만 정리한다(신고 차단 경로와 동일한 3중 경로가드를 그대로 재사용 — 신규 메커니즘 0). ⓒ 최소 조치로 `DatasetExportWriter` 가 **첫 프레임 쓰기 직전에** 디렉터리를 만들도록 지연시켜 산출 0건이면 디렉터리 자체가 생기지 않게 한다. **ⓐ+ⓑ 조합 권장. 구현은 하지 않는다.**

---

## 5. TC-EXPORT-040 — 실패 export 회수기 실동작 (PASS)

**시나리오(S8)**: rawSn=94 의 `raw_file_path_nm` 을 다시 허용 루트 밖으로 돌려 재export → **최신 export 가 FAILED 인 앵커**(`export_ver_no=11`, `REG_DT=01:52:46`) 생성 → 즉시 경로 원복 → 주기 잡(`interval-sec=900`, `retry-delay-minutes=10`) 관측.

**① 재시도 유예가 실제로 걸린다** — 앵커 생성 3분 34초 뒤 tick 은 후보로 집지 않았다.
```
01:56:20.122 DEBUG DatasetExportFailureRecoverer - [DatasetExportRecovery] no retryable failed export
```
(`findRetryableFailedAnchors` 의 `COALESCE(e.RTY_DT, e.REG_DT) < :cutoff`, cutoff=now-10m → 01:46:20 > 01:52:46 이라 제외)

**② 유예 경과 후 원자 클레임 + 재산출 + 통지 재개** — 다음 tick(18분 34초 경과)에서:
```
02:11:20.121 WARN  DatasetExportFailureRecoverer - [DatasetExportRecovery] retriggered failed exports count=1 rawSns=[94] maxAttempts=3
02:11:20.121 INFO  AsyncDatasetExportRunner      - [DatasetExport] async approval export starting rawSn=94
02:11:20.131 INFO  DatasetExportWriter           - frames written rawSn=94 kind=ORIGINAL      version=12 written=5 skipped=1
02:11:20.131 INFO  DatasetExportWriter           - frames written rawSn=94 kind=DEIDENTIFIED version=12 written=5 skipped=1
02:11:20.133 WARN  DatasetExportService          - partial export rawSn=94 version=12 written=10 skipped=2
02:11:20.137 INFO  ControlNotifyService          - completed conflicted -> resend as updated rawSn=94
02:11:20.140 INFO  ControlNotifyService          - TASK_COMPLETED sent rawSn=94 actual=TASK_MODIFIED
```
mock-server 인바운드: `POST /api/data-set/v2/jobs/94/notify-completed → 409` → **자기치유** → `notify-updated → 202 Accepted`.

**③ 시도 이력이 앵커 행에 기록됐다**(상한이 실제로 걸림 — H7① 계약):
```
 export_ver_no | export_stts_cd |           export_path_nm           | frame_cnt | rty_nmtm |           rty_dt
---------------+----------------+------------------------------------+-----------+----------+----------------------------
            12 | PARTIAL        | /app/storage/raw/autolabel-test/94 |        10 |        0 |
            11 | FAILED         |                                    |           |        1 | 2026-08-04 02:11:20.120095   ← 클레임됨
            10 | PARTIAL        | /app/storage/raw/autolabel-test/94 |        10 |        0 |
```
재산출이 새 행(v12)을 만들어 앵커가 바뀌어도 누적치는 "마지막 성공 이후 전 행 `SUM(RTY_NMTM)`" 로 계산되므로 리셋되지 않는다(`LsDatasetExportRepository.java:135-142`).

**④ 신고 구간 제외** — `DatasetExportFailureRecoverer.java:141-144` 가 **클레임 이전에** `deidentReportGate.isUnderDeidentReport(rawSn)` 로 건너뛴다(시도 상한 미소진). 이번 회차엔 신고 구간 FAILED 앵커가 없어 실동작 미유도 — IT `DatasetExportDeidentReportGateIT.회수기가_신고_구간에_재시도_상한을_소진하지_않는다` 로 커버(baseline 실패 0건).

**⑤ 2노드 중복 방지** — `claimForRetry` 가 `RTY_NMTM +1, RTY_DT=now WHERE EXPORT_SN=? AND EXPORT_STTS_CD='FAILED' AND RTY_NMTM < ? AND (RTY_DT IS NULL OR RTY_DT < ?)` 단일 조건부 UPDATE(`LsDatasetExportRepository.java:172-185`). 단일 노드 환경이라 실동작 미검증 — IT `DatasetExportFailureRecoveryIT.Quartz_클러스터링이_꺼져있어도_같은_tick_중복_실행시_한_번만_재산출된다` 로 커버.

**판정: PASS** — 근거(`DatasetExportFailureRecoverer.java:119-170`; `DatasetExportTxService.java:247-261`) **정확**.

> ⚠ 부수 관측(E-ISSUE-81 보강): 회수 재개 통지도 `runApprovalAsync` 경로라 **영상 단위 TASK_COMPLETED**(409 자기치유로 updated 전환)다. 원래 보류됐던 수정 통지의 **변경 프레임 목록은 회수 경로로도 복원되지 않는다**.

---

## 6. self-fill 관점

본 파트(E-6 Export) 범위에는 **외부 연동 응답으로 채워야 할 값이 없다** — 산출 JSON 의 값은 전부 DB(라벨·프레임·동결 메타)에서 오고, 유일한 외부 의존은 비식별 영상 경로다. 그 경로도 문자열 조합·추측이 아니라 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 값을 그대로 읽는다(`DatasetExportTxService.java:133-135` `findLatestSuccessByDataRawSn(...).map(LsDeidentProcLog::getDeIdntfFilePathNm).orElse(null)`, 미상이면 **null 로 fail-secure**). 실측으로도 rawSn=101 의 비식별 사본이 mock KPST 가 정한 `clip-9101-mask.mp4`(=`{stem}-mask{ext}`)로 기록·복사돼 있었다(우리가 `deidentified.mp4` 로 조합하지 않음). **self-fill 결함 0건.**

관제 통지도 self-fill 이 아니다 — mock-server 인바운드에 `notify-updated accepted job_id=101 images=10 jsons=10`, `notify-completed accepted job_id=94` 가 실제로 도달했고 값이 DB 실측치와 일치했다.

---

## 7. 검증 중 생성/변경한 데이터 (이후 회차 참고 — 정리하지 않음)

| 대상 | 값 | 성격 |
|------|----|------|
| `ls_data_lbl` | rawSn=101 / srcSn=468 / labelSn=729 의 BBOX 좌표 `[55,55]-[205,205]` → **`[60,60]-[210,210]`** | 재export 유도용 라벨 수정(정상 API). `labelVersion` 2→3 |
| `ls_dataset_export` | rawSn=101 **v3**(SUCCEEDED) / rawSn=94 **v7·v9·v10·v12**(PARTIAL)·**v8·v11**(FAILED, path=null, v11 은 `RTY_NMTM=1` 클레임됨) | 본 검증이 만든 산출 이력 |
| 파일 | `/app/storage/raw/seed/101/v3/` · `/app/storage/raw/autolabel-test/94/v7,v9,v10,v12/` | 산출물(전 버전 보존 정책상 유지) |
| `ls_deident_report` | deident_report_sn=**36**(rawSn=94) — OPEN → **RESOLVED** 완결, `DE_IDENT_YN='Y'` 복원 | 신고 게이트 검증용 |
| `ls_data_src` | rawSn=94 / srcSn=448 의 `frm_expln` = `"E-part5 recoverer anchor"` | 재export 트리거용 |
| `ls_data_raw` | rawSn=94 `raw_file_path_nm` 을 `/etc/evil/clip.mp4` 로 2회 임시 변경 후 **원복 완료**(현재 `/app/storage/raw/autolabel-test/62e1aea1-0b7e-40d6-b806-121a370fe746.mp4`) | base 가드 검증용 — **원복 확인함** |
| 파일 mtime | `/app/storage/raw/autolabel-test/94/deid/62e1aea1-…-mask.mp4` `touch` | 신고 resolve 게이트(외부 솔루션 제자리 교체) 모사, 1차와 동일 |
| `ls_raw_data_status` | rawSn=94 검수 사이클 2회 재순회(APPROVED→PENDING→IN_REVIEW→APPROVED), 현재 **APPROVED** | 재승인 경로 검증용 — 최종 상태 복원됨 |

> ⚠ **rawSn=94 는 유령 프레임(`src_sn=453`, `frm_no=99`, 실재하지 않는 이미지 경로)을 계속 보유**한다(1차에서 생성). 이 영상의 향후 export 는 **항상 PARTIAL** 이다 — 정상 SUCCEEDED 시나리오가 필요하면 rawSn=101 을 쓸 것.
> ⚠ rawSn=101 은 본 파트 외에도 **다른 에이전트가 동시에 수정 중**이었다(검증 중 v4·v5 가 타 경로에서 생성됨). 이 영상의 export 이력을 단독 근거로 쓸 때 주의.
