# B-batch-deidentify 검증 (Part 5) — B-12 / B-13 / B-14

- 대상: `docs/test-cases/B-batch-deidentify.md` 242~294행 (TC-BATCH-150~162, TC-DEID-060~080, TC-BATCH-170~172)
- 검증일: 2026-07-25 / 1차
- 스택: klid-backend(:18081, SPRING_PROFILES_ACTIVE=local, KPST_DEID_ENABLED=true → `http://klid-mock-server:9400`) · klid-postgres(`klid_system`/`public`) · klid-mock-server(:9400)
- 실동작 관측 구간: backend 로그 10:36~10:46, mock 로그 동시간대, DB(`ls_deident_proc_log`·`ls_bat_rty_wtng`·`ls_data_raw`·`qrtz_*`) 실조회, `POST /v1/videos/{rawSn}/batch/retry` 실 curl

> **환경 제약(판정에 반영)**: `application-local.yml:50` `authoring.batch.enabled=false` → Quartz `batchPipelineJob`·`batchRetryJob`·`controlTrainingVideoScanJob` **트리거 미등록**(DB `qrtz_job_details` 3행: bootstrapJob / kpstDeidentPollJob / datasetExportPendingSweepJob). 배치 본체는 `MarkingBatchBridge`→`AsyncBatchRunner`(@Async)로 구동되므로 **재시도 큐 적재는 실동작 관측 가능**하나 **재시도 폴러 발화는 로컬에서 관측 불가**(→ 정적+IT 판정).

---

## B-12. 재처리 / 재시도 큐 (2노드 안전)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-150 | 재처리: rawSn null → INVALID_INPUT | PASS | 정적 `BatchReprocessService.java:58-60` ✓ / **실동작**: `POST /v1/videos/0/batch/retry` → **400** `retryBatch.rawSn: rawSn 은 1 이상이어야 합니다.`(컨트롤러 `@Min` 선행 차단, 서비스 null 가드는 방어심도) | — | HTTP 경로에서 null 은 도달 불가(PathVariable). 서비스 가드 유지 타당 |
| TC-BATCH-151 | 재처리: 영상 미존재 → NOT_FOUND | PASS | 정적 `:61-64` ✓ / **실동작**: rawSn=999999 → **404** `{"errorCode":"NOT_FOUND","message":"영상을 찾을 수 없습니다."}` | `BatchReprocessServiceTest:99` 존재하지_않는_영상_재처리시_NOT_FOUND_404 | |
| TC-BATCH-152 | 재처리: FAILED→PROCESSING 원자 클레임 성공 | PASS | 정적 `:66-79` ✓ → `tryClaimReprocessFromFailed`(REQUIRES_NEW 조건부 UPDATE) → `clearIfIdle` → `orchestrator.process` 순서 확인 | `BatchReprocessServiceTest:64` 배치재처리API_성공시_재배치가_기동된다 / `:76` verify(clearIfIdle) | 성공 경로 실행은 **상태 변경 유발**이라 실동작 미수행(제약 준수). 부정 경로 2건은 실동작 확인 |
| TC-BATCH-153 | 재처리: FAILED 아님/이미 클레임 → CONFLICT | PASS | 정적 `:68-72` ✓ / **실동작**: rawSn=17(COMPLETED) → **409** `CONFLICT` "배치가 실패(FAILED)한 영상만…" | `BatchReprocessServiceTest:47`, `:81` 배치재처리_동시요청시_한쪽만_기동된다 | 무인증 401 도 실측(`@PreAuthorize("hasRole('REVIEWER')")` `BatchReprocessController.java:50`) |
| TC-BATCH-154 | 재처리 클레임: raw 우선, 없으면 status FAILED→PROCESSING | PASS | 정적 `BatchTransitionService.java:268-281` ✓ — `videoRepository.claimReprocessFromFailed` 1 이면 즉시 true, 아니면 `rawDataStatusRepository.claimReprocessFromFailed` | `BatchReprocessServiceTest:81` (간접) | 두 리포지토리 모두 `claimReprocessFromFailed` 조건부 UPDATE 보유 확인 |
| TC-BATCH-155 | 재시도 등록: 최초 실패 PENDING(ON CONFLICT DO NOTHING) | **PASS (실동작)** | 정적 `BatchRetryQueue.java:66-92` ✓ + `LsBatRtyWtngRepository.java:44-49` `INSERT … ON CONFLICT (RAW_SN) DO NOTHING` / **실동작**: 로그 `[BatchRetry] enqueued rawSn=15 attempt=1 delaySec=60` → DB `ls_bat_rty_wtng` 4행 `rty_nmtm=1, stts_cd=PENDING` | `BatchRetryQueueIT:70`, `:158` | 실 DB 행 확인(rawSn 15/16/23/24) |
| TC-BATCH-156 | 재시도 등록: 동시 최초 실패 UK 경쟁 흡수 | PASS | 정적 `:71-74` ✓ — ① `insertIfAbsent`(원자 upsert) → ② `findByRawSnForUpdate`(`@Lock(PESSIMISTIC_WRITE)` = SELECT … FOR UPDATE) 2단 패턴. **PG unique 위반이 tx 전체를 abort 시키는 함정을 회피**(같은 tx 내 예외 재시도 없음, `REQUIRES_NEW` 즉시 커밋) | `BatchRetryQueueIT:133` 동시_최초등록시_UK위반이_전파되지_않는다 | 반증 시도: 예외 catch-후-재조회 패턴이 아님을 확인 → 함정 미해당 |
| TC-BATCH-157 | 재시도: 지수백오프(60,120,240…) shift 30 캡 | **PASS (실동작)** | 정적 `:85-88` ✓ `shift=min(attempt-1,30)`, `delay=initial×2^shift` / **실동작**: reg_dt `10:40:18.018` → rty_prnmnt_dt `10:41:18.028` = **정확히 60s**(attempt=1) | `BatchRetryQueueIT` (간접) | attempt≥2 백오프는 로컬 폴러 미발화로 미관측(정적 판정) |
| TC-BATCH-158 | 재시도: max 초과 → EXHAUSTED false | PASS | 정적 `:78-84` ✓ — `attempt>maxAttempts` → `markExhausted()`(삭제 아님, 이력 보존) + false. `pollReady` 는 PENDING 만 조회 → EXHAUSTED 는 dead-letter 로 영구 제외 | `BatchRetryQueueIT:111` 최대시도_초과시_소진마킹되고_재폴링되지_않는다 | `authoring.batch.retry.max-attempts` 기본 3 (application.yml:187) |
| TC-BATCH-159 | 폴링 클레임: 2노드 동시 폴링 직렬화 | **PARTIAL** | 정적 `:100-112` ✓ — `claimAtomically`(`UPDATE … SET STTS='RETRYING' WHERE STTS='PENDING'`) CAS, 영향행수 1 만 반환. 후보 10건 순회로 경쟁 패배 시 다음 후보. **클레임 자체는 원자적** / ⚠ **RETRYING 고아 행 복구 경로 없음** → B-ISSUE-83 | `BatchRetryQueueIT:84` 재시도_동시_폴링시_한_노드만_클레임한다 | 로컬 폴러 미등록으로 실발화 미관측 |
| TC-BATCH-160 | 재시도 등록 노드 ≠ 발화 노드(DB 영속) | PASS | 정적 `:16-31` Javadoc + `LsBatRtyWtng` 엔티티(`LS_BAT_RTY_WTNG` 영속) ✓ — 인메모리 `ConcurrentHashMap` 제거 확인. DB 실행 `ls_bat_rty_wtng` 행 실존 | `BatchRetryQueueIT:70` 재시도큐_등록후_다른_노드_폴링이_항목을_회수한다 | |
| TC-BATCH-161 | clearIfIdle: RETRYING 보존, PENDING/EXHAUSTED만 삭제 | PASS | 정적 `:133-146` + `LsBatRtyWtngRepository.java:79-81` `DELETE … WHERE RAW_SN=:rawSn AND STTS_CD <> 'RETRYING'` ✓ | `BatchReprocessServiceTest:60/76/95` verify clearIfIdle 호출/미호출 | |
| TC-BATCH-162 | clear: 성공 시 전체 삭제 | PASS | 정적 `:124-131` `deleteByRawSn` ✓ / 호출부 `BatchOrchestrator.java:125`(성공 시) 확인. **실동작**: rawSn=26 COMPLETED 후 `ls_bat_rty_wtng` 에 26 행 없음 | `BatchRetryQueueIT:158` 성공_clear시_항목이_제거된다 | 성공 시 카운터 리셋 = 의도된 정책 |

---

## B-13. KPST 비식별 폴링 (완료감지·타임아웃)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-DEID-060 | 위탁: createProject → procLog WAITING, 미전이 | **PASS (실동작)** | 정적 `KpstDeidentService.java:161-210` ✓ / **실동작**: mock `POST /project` 4회 → 로그 `[KpstDeid] submitted rawSn=23 prjId=3` … `rawSn=26 prjId=6`, 다음 tick `polling targets count=3` (= WAITING 조회됨) | `KpstDeidentServiceTest:127/147/164/184` | self-fill 없음 — prjId 는 mock 응답값(1~6 순증) |
| TC-DEID-061 | 위탁: 원본경로 부모 없음 → INVALID_INPUT(CWE-22) | PASS | 정적 `:177-182` ✓ | `KpstDeidentServiceTest:216` 원본경로_부모디렉터리가_null이면_F마킹하고_예외전파_createProject미호출 | |
| TC-DEID-062 | 위탁: 실패 → 'F' 마킹+EXTERNAL_API_ERROR | PASS | 정적 `:211-217` ✓ — `procLog.fail` + `markDeidentified("F")` + `EXTERNAL_API_ERROR` 전파, CWE-209 준수(클래스명만) | `KpstDeidentServiceTest:202` | |
| TC-DEID-063 | 위탁: export 디렉터리 정리(stale 오회수 방지) | PASS | 정적 `:238-269` ✓ — base 하위 단언 → `Files.list`(비재귀) → `isRegularFile(NOFOLLOW_LINKS)` 만 삭제 | `KpstDeidentServiceTest:234/255/298` (stale 정리·심링크 미삭제·빈디렉터리 noop 3건) | |
| TC-DEID-064 | 폴링: procState=2 전체완료(AND) → 다운로드+완료 | **PASS (실동작)** | 정적 `:326-369`(AND 판정 `allDatasetsCompleted:610-622`) ✓ / **실동작**: mock `GET /retrieve_progress` → `[KpstDeid] poll completed rawSn=23 prjId=3 datasetId=3`(24/25/26 동일) → DB `poll_stts_cd=DOWNLOADED, proc_stts_cd=SUCCEEDED`, `ls_data_raw.de_ident_yn='Y'` | `KpstDeidentServiceTest:593`, `KpstDeidentPollIntegrationTest:152` | **회귀 없음**: 과거 완료를 `'F'` 로 오종결하던 결함 재발 안 함(4건 모두 SUCCEEDED/Y) |
| TC-DEID-065 | 폴링: 터미널 실패(3/4/99) 우선 → 즉시 'F' | PASS | 정적 `:313-323` ✓ — `anyDatasetFailed` 를 `allDatasetsCompleted` **앞에** 호출(우선순위 확인) | `KpstDeidentServiceTest:617/636/655/693` (99·3·4·혼합) | |
| TC-DEID-066 | 폴링: procState=99(오류 sentinel) 종결 | PASS | 정적 `:87-91` (문서 `88-91` 대비 1행 오차) ✓ `PROC_STATE_TERMINAL_FAILED={3,4,99}` | `KpstDeidentServiceTest:617`, `:674`(5는 도메인 밖 → 실패 아님) | |
| TC-DEID-067 | 폴링: prjId null(위탁 미완) → 타임아웃 검사만 | PASS | 정적 `:283-287` ✓ | `KpstDeidentServiceTest:767` prjId_미상이면_타임아웃검사만_수행하고_외부호출하지_않는다 | |
| TC-DEID-068 | 폴링: retrieveProgress 예외 → 타임아웃 평가 | PASS | 정적 `:288-302` ✓ — K1 수정(예외 경로에서도 `markTimeoutIfExpired`) 확인 | `KpstDeidentServiceTest:782`, `:802` (경과 전 skip / 초과 시 F) | 무기한 stuck 차단 |
| TC-DEID-069 | 폴링: 완료지만 fileName bad → 터미널 처리 | PASS | 정적 `:337-351` ✓ — `downloadResult` 예외를 `pollOne` 내부에서 잡아 REDEIDENT/비REDEIDENT 분기 terminal | `KpstDeidentServiceTest:349/366/384/398` | |
| TC-DEID-070 | 폴링: 완료지만 산출물 0바이트/미존재 → 'F' | PASS | 정적 `:352-366` ✓ (`isUsableDeidFile:391-402`, NOFOLLOW_LINKS) / **실동작 이력**: `ls_deident_proc_log` proc_log_sn=13 `err_cd=DEIDENT_INCOMPLETE, poll_stts_cd=FAILED, attempt=201` — 'Y' 위장 없음 | `KpstDeidentServiceTest:455/472/490/510/531` | |
| TC-DEID-071 | 폴링: 진행중 → 시도 증가+타임아웃 검사 | PASS | 정적 `:383-387` ✓ / **실동작 이력**: proc_log_sn=12 `poll_atmpt_cnt=240`(=`poll-max-attempts` 기본값) `err_cd=DEIDENT_TIMEOUT` → 시도 카운터 실증가 확인 | `KpstDeidentServiceTest:737/752`, `KpstDeidentTxServiceTest:189/202/218` | |
| TC-DEID-072 | fileName 회수: {stem}-mask{ext} 변환(실측 계약) | **PARTIAL** | 정적 `:424-459` ✓ (`toMaskName`, `MASK_SUFFIX="-mask"`) / ⚠ **실동작으로는 미검증** — mock 이 `fileName=ds.name`(= 산출물 basename `{stem}_{yyyyMMddHHmm}_mask{ext}`)을 반환해 **실서버 계약(fileName=원본 입력 절대경로, 산출물=`{stem}-mask{ext}`)과 불일치**. 1차 경로 `clip-9101_202607250139_mask-mask.mp4` 는 항상 miss → 폴백으로만 회수됨 → **B-ISSUE-84** | `KpstDeidentServiceTest:314` toMaskName_…, `:896` 경로형_fileName이면_basename추출후_mask접미사경로로_회수한다 | 단위 테스트로는 실계약 커버됨. 회귀(과거 'F' 오종결)의 E2E 감시망만 공백 |
| TC-DEID-073 | fileName 회수 폴백: 단일 산출물 스캔 | **PASS (실동작)** | 정적 `:470-492` ✓ / **실동작**: `/app/storage/deidentified/videos/23/` 에 산출물 1개 → `scanSingleUsable` 회수 → DB `de_idntf_file_path_nm=/app/storage/deidentified/videos/23/clip-9101_202607250139_mask.mp4` | `KpstDeidentServiceTest:916`(1개), `:933`(2개↑ 모호 → failPolling) | 0개→null·2개↑→INVALID 규칙 정적 확인 |
| TC-DEID-074 | sanitizeFileName: basename만(CWE-22) | PASS | 정적 `:513-534` ✓ — `Paths.get`→`getFileName()`, `/`·`\`·`..` 잔존 거부, `InvalidPathException`→INVALID_INPUT 정규화(원문 미노출) | `KpstDeidentServiceTest:415`(NUL), `:950`(상위참조 섞임) | |
| TC-DEID-075 | 폴링 잡: 대상 없으면 noop | **PASS (실동작)** | 정적 `KpstDeidentPollJob.java:50-54` ✓ / **실동작**: 10:36:32~10:39:32 매 30s `[KpstDeidPoll] no pending poll target — skipping tick` DEBUG, 동시간대 mock 로그에 `retrieve_progress` **0건** | `KpstDeidentPollJobTest:67` 대상이_없으면_외부호출없이_즉시종료한다 | 외부 미호출 실증 |
| TC-DEID-076 | 폴링 잡: 건별 try/catch 격리 | PASS | 정적 `:56-64` ✓ | `KpstDeidentPollJobTest:77` 한_작업_폴링실패가_다른_작업을_막지_않는다 | |
| TC-DEID-077 | 폴링 잡: 동시 실행 금지 | **PARTIAL** | 정적 `:31-32` `@DisallowConcurrentExecution` ✓ / **실동작**: DB `qrtz_job_details.is_nonconcurrent='t'`(kpstDeidentPollJob) — **단일 노드 직렬화 확인** / ⚠ 2노드 A-A 에서는 클러스터링 OFF(→B-ISSUE-81) + 폴링 대상 조회(`findByPollSttsCdIn`)에 **원자 클레임 없음** → 중복 폴링·중복 완료 가능 → **B-ISSUE-82** | `KpstDeidentPollJobTest` | |
| TC-DEID-078 | 폴링 잡: 재기동 복원(DB 조회) | PASS | 정적 `:38-50` + `LsDeidentProcLogRepository.java:47 findByPollSttsCdIn` ✓ (인메모리 상태 없음) / **실동작**: backend 재기동(Up 6m) 이후 tick 이 DB 에서 대상 조회 재개(`polling targets count=3`) | `KpstDeidentPollJobTest:51`, `KpstDeidentPollIntegrationTest:230` 통합_재기동_WAITING건이_재폴링_조회된다 | |
| TC-DEID-079 | completeDeidentification: 파일무효 F-마킹 보정 | PASS | 정적 `KpstDeidentService.java:542-555` + `KpstDeidentTxService.java:116-120 markRawDeidentFailed`(별도 REQUIRES_NEW) ✓ — 롤백에 F-마킹이 휩쓸리지 않음 | `KpstDeidentServiceTest:828/843`, `KpstDeidentTxServiceTest:444` | |
| TC-DEID-080 | KPST 조건부 빈: kpst.deid.enabled=false 미등록 | PASS | 정적 `:62 @ConditionalOnProperty(prefix="kpst.deid", name="enabled", havingValue="true")` ✓ (`KpstDeidentTxService:39`, `KpstDeidentPollTriggerConfig:20` 동일 게이팅) / **실동작**: 컨테이너 `KPST_DEID_ENABLED=true` → 빈·트리거 등록 확인(`qrtz_triggers.kpstDeidentPollTrigger`) | — | false 케이스는 재기동 필요라 미실증(정적) |

---

## B-14. Quartz 클러스터링 / 인프라

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-170 | Quartz JobStore = controlDataSource(PG) | **PASS (실동작)** | 정적 `QuartzConfig.java:30-37` ✓ (`SchedulerFactoryBeanCustomizer` + `@Qualifier("controlDataSource")`, `DataSourceAutoConfiguration` exclude 확인 `application.yml:74-77`) / **실동작**: `klid_system` DB 에 `qrtz_*` 11개 테이블 실존, `qrtz_job_details` 3행·`qrtz_triggers` 2행 등록, `driverDelegateClass=PostgreSQLDelegate`(`application.yml:66`), `useProperties=true` | `SchedulerHealthControllerTest:50` bootstrapJobRegistered | |
| TC-BATCH-171 | 2노드 A-A: 동일 잡 중복 실행 방지 | **PARTIAL** | ⚠ **실동작 반증**: `qrtz_scheduler_state` **0행**, `qrtz_fired_triggers.instance_name = "NON_CLUSTERED"` → **런타임 클러스터링 비활성 확정**. 설정 `application.yml:72 org.quartz.jobStore.isClustered: ${QUARTZ_CLUSTERED:false}` — **기본값 false**, 4개 프로파일 yml 어디에도 override 없음, 온프렘 배포 템플릿 `deploy/onprem/config/backend/env.template:175 QUARTZ_CLUSTERED=false`. `qrtz_locks` 는 2행(TRIGGER_ACCESS/STATE_ACCESS) 존재하나 non-clustered 모드에서는 클러스터 조율에 쓰이지 않음 → **B-ISSUE-81** | — | 근거 라인 드리프트 1건(문서 `QuartzConfig.java:31-37` → 실제 `application.yml:69-73`) |
| TC-BATCH-172 | AsyncBatchRunner: 예외 삼킴(@Async) | PASS | 정적 `AsyncBatchRunner.java:20-28` ✓ `try{process}catch(Exception){log.error}` — 재던짐 없음 / **실동작**: `[AsyncBatchRunner] starting batch rawSn=15/16/23/24/26` (`batch-async-1/2` 스레드) 관측. 예외 삼킴 분기는 `BatchOrchestrator` 가 내부에서 catch 하므로 미도달(로그 `[BatchOrchestrator] failed … willRetry=true` 로 정상 종료) | **없음** (다른 테스트에서 mock 으로만 사용) | 전용 단위테스트 부재 |

---

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [B-ISSUE-81] TC-BATCH-171 — Quartz 클러스터링이 기본 비활성이며 온프렘 배포 템플릿도 false → 2노드 Active-Active 시 스케줄러 잡 중복 실행
- **심각도**: HIGH (운영/구성)
- **기대 동작(기대효과)**: CLAUDE.md "배치 성능" — *"주 서버 2노드 Active-Active 이중화 … **Quartz 클러스터링 적용**(PostgreSQL JobStore 락으로 잡 중복 방지)"*. 2노드 동시 기동 시 `kpstDeidentPollJob`·`batchPipelineJob`·`batchRetryJob`·`controlTrainingVideoScanJob`·`datasetExportPendingSweepJob` 각 트리거가 **정확히 1회만** 발화해야 한다.
- **현재 동작(이슈 내용)**:
  - `backend/src/main/resources/application.yml:69-73`
    ```yaml
    # 이중화(HA) 노드만 true — local/dev 단일 인스턴스는 false 유지.
    org.quartz.jobStore.isClustered: ${QUARTZ_CLUSTERED:false}
    org.quartz.jobStore.clusterCheckinInterval: ${QUARTZ_CHECKIN_MS:20000}
    ```
    → **기본값 false**. `application-{local,dev,stg,prd}.yml` 어디에도 `QUARTZ_CLUSTERED` override 없음(grep 0건).
  - `deploy/onprem/config/backend/env.template:175` → `QUARTZ_CLUSTERED=false` (배포 산출물 기본값도 false)
  - **런타임 실측**: `qrtz_scheduler_state` 0행 (클러스터 모드에서만 체크인 행이 생성됨) · `qrtz_fired_triggers.instance_name = "NON_CLUSTERED"`
  - 결과: 2노드를 띄우면서 두 노드 모두 `QUARTZ_CLUSTERED=true` 를 **명시 설정하지 않으면** 두 노드가 서로를 모른 채 동일 트리거를 각자 발화한다. `@DisallowConcurrentExecution` 은 **JVM/스케줄러 인스턴스 내부**에서만 유효하므로 노드 간 중복을 막지 못한다.
  - 파급: 관제 학습영상 스캔 이중 적재, KPST 폴링 이중 완료 처리(→ B-ISSUE-82), 배치 재시도 이중 실행, export sweep 이중 쓰기.
  - 문서 충돌: `CLAUDE.md`("클러스터링 적용") vs `docs/design/D5-아키텍처설계서.md:126`("기본은 단일 인스턴스, 이중화 시 옵션 지원") — **D5 가 코드와 일치**, CLAUDE.md 서술이 앞서감.
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select * from qrtz_scheduler_state;"          # 0 rows
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select instance_name from qrtz_fired_triggers;" # NON_CLUSTERED
  grep -rn "QUARTZ_CLUSTERED" backend/src/main/resources deploy/onprem/config/backend/env.template
  ```
- **영향**: CWE-362 (Race Condition) — 노드 간 잡 중복 실행. 데이터 이중 적재/이중 상태전이. 배포 구성 실수 1건으로 조용히 발현되며 로그만으로는 감지 어려움.
- **수정 방향(제안)**: ① 온프렘 2노드 배포 프로파일에서 `QUARTZ_CLUSTERED=true` 를 **기본값으로** 하거나, 기동 시 "노드 수 ≥ 2인데 isClustered=false" 를 감지해 **fail-closed 부트 거부 또는 WARN 배너**(`DevToggleStartupWarner` 패턴 재사용). ② `env.template` 주석에 "2노드면 반드시 true" 를 체크리스트 항목으로 승격. ③ CLAUDE.md 를 D5 서술("옵션 지원")에 맞춰 정정하거나 반대로 기본값을 true 로 뒤집기 — 어느 쪽이든 **문서·기본값 단일화** 필요. ④ 클러스터 모드 필수 조건인 노드 클럭 동기화(NTP) 를 배포 체크리스트에 명시.

### [B-ISSUE-82] TC-DEID-077 — KPST 폴링 대상 조회에 원자 클레임이 없어, 비클러스터 2노드에서 동일 위탁 건 중복 폴링·중복 완료 처리 가능
- **심각도**: HIGH (B-ISSUE-81 이 선행 조건)
- **기대 동작(기대효과)**: 동일 `procLog` 를 두 노드가 동시에 완료 처리하지 않는다(중복 다운로드/전이 방지 — `KpstDeidentPollJob` Javadoc `:23` 이 명시한 방어 목표).
- **현재 동작(이슈 내용)**:
  - `KpstDeidentPollJob.java:50`
    ```java
    List<LsDeidentProcLog> targets = procLogRepository.findByPollSttsCdIn(POLL_TARGET_STATUSES);
    ```
    `LsDeidentProcLogRepository.java:47` — **평범한 파생 쿼리**. `BatchRetryQueue.claimAtomically`(PENDING→RETRYING CAS)에 해당하는 **소유권 클레임이 없다**. `POLLING` 상태는 "처리중" 이 아니라 단순 진행 표시라 다른 노드도 그대로 재조회한다.
  - `KpstDeidentTxService.finishDownloadAndComplete:59-70` 에도 멱등 가드(예: `poll_stts_cd='WAITING'/'POLLING'` 조건부 UPDATE)가 없어, 두 노드가 동시에 완료를 커밋하면 `markDownloaded`·`applyCompletion`(REDEIDENT 시 `deidentFrameAttacher.attachDeidentFrames`)이 **두 번** 수행된다.
  - 단일 노드에서는 `@DisallowConcurrentExecution`(DB `is_nonconcurrent='t'` 실확인)이 막으므로 현행 로컬/단일 배포에서는 발현하지 않음.
  - 부가: `findByPollSttsCdIn` 에 페이징/상한이 없어 대기 건이 누적되면 전건 로드(CWE-770 소지, LOW).
- **재현/확인 경로**:
  ```bash
  grep -n "findByPollSttsCdIn" backend/src/main/java/kr/co/cudo/authoring/batch/repository/LsDeidentProcLogRepository.java
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select proc_log_sn,poll_stts_cd from ls_deident_proc_log where poll_stts_cd in ('WAITING','POLLING');"
  # 2노드 재현: QUARTZ_CLUSTERED 미설정 상태로 backend 2 인스턴스 기동 → 동일 procLogSn 에 대해 [KpstDeid] poll completed 로그 2회
  ```
- **영향**: CWE-362. 중복 프레임 attach/캐시 무효화, 알림 이중 발송, `LS_DATA_SRC` 갱신 경쟁.
- **수정 방향(제안)**: `BatchRetryQueue` 와 동일 패턴 적용 — ① `POLL_CLAIMED`(또는 `POLLING` 의미 강화) 상태로 조건부 원자 UPDATE 클레임 후 그 건만 폴링, ② 또는 `finishDownloadAndComplete` 진입 시 `UPDATE … SET poll_stts_cd='DOWNLOADED' WHERE proc_log_sn=? AND poll_stts_cd IN ('WAITING','POLLING')` 영향행수 1 검사로 멱등 게이트. ③ 조회에 `Pageable` 상한 추가. (B-ISSUE-81 해결 시 위험은 크게 낮아지나, 클러스터링을 켜도 잡 자체는 한 노드에서만 돌 뿐이므로 ①/② 는 방어심도로 유효.)

### [B-ISSUE-83] TC-BATCH-159 — 재시도 큐 `RETRYING` 클레임 후 노드 사멸 시 복구 경로가 없어 해당 재시도가 영구 유실
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 재시도 항목을 클레임한 노드가 처리 중 죽어도, 다른 노드/다음 tick 이 그 항목을 회수해 재시도를 이어간다(2노드 A-A 내구성 — 이 큐가 DB 로 옮겨진 애초 목적).
- **현재 동작(이슈 내용)**:
  - `BatchRetryQueue.pollReady:100-112` 가 `PENDING → RETRYING` 으로 CAS 클레임한다.
  - 이후 `RETRYING → PENDING` 복귀는 **오직** `BatchRetryQuartzJob.execute` 가 정상적으로 예외를 받아 `enqueueIfRetryable` 을 다시 부를 때만 일어난다(`BatchRetryQuartzJob.java:70`).
  - 프로세스 kill / OOM / 노드 장애로 그 사이가 끊기면 행은 `RETRYING` 으로 **영구 고착**된다:
    - `pollReady` 는 `STTS_CD='PENDING'` 만 조회 → 다시 안 잡힘
    - `clearIfIdle`(`LsBatRtyWtngRepository.java:80`)은 `STTS_CD <> 'RETRYING'` 이라 **의도적으로 보존** → 수동 재처리로도 리셋되지 않음
    - **stale RETRYING 을 되살리는 sweeper/타임아웃 잡이 코드베이스에 없음** (`grep -rn "RETRYING" backend/src/main/java` → `BatchRetryQueue`/엔티티 상수와 무관한 `controlnotify` 폴백만 매칭)
  - 결과: 해당 rawSn 은 `FAILED` 로 남고 자동 재시도가 무음 중단된다(REVIEWER 수동 재처리 전까지). 이는 in-memory 큐를 DB 로 옮기며 없앴다던 "재시도 무음 유실" 이 다른 형태로 잔존하는 것.
- **재현/확인 경로**:
  ```sql
  -- 고아 판별 쿼리 (현재 로컬엔 RETRYING 행 없음 — 폴러 미등록이라 클레임 자체가 미발생)
  select bat_rty_sn, raw_sn, rty_nmtm, stts_cd, mdfcn_dt
    from ls_bat_rty_wtng
   where stts_cd = 'RETRYING' and mdfcn_dt < now() - interval '30 minutes';
  ```
  재현: 폴러 활성 환경에서 `pollReady` 클레임 직후 backend 컨테이너 `docker kill` → 재기동 후 해당 행이 `RETRYING` 유지되고 재시도 미발화.
- **영향**: CWE-459 (Incomplete Cleanup) / 가용성. 실패 영상이 자동 복구 대상에서 조용히 이탈.
- **수정 방향(제안)**: ① `MDFCN_DT` 기준 stale 임계(예: 배치 최대 실행시간 ×2)를 넘긴 `RETRYING` 행을 조건부 UPDATE 로 `PENDING` 복귀시키는 sweeper 를 재시도 잡 앞단에 추가(`UPDATE … SET STTS_CD='PENDING' WHERE STTS_CD='RETRYING' AND MDFCN_DT < :cutoff`, 원자 CAS). ② 또는 `Quartz` `requestRecovery(true)` 를 재시도 잡에 부여해 노드 사망 시 복구 발화(단, 클러스터링 활성 전제). ③ `EXHAUSTED`/장기 `RETRYING` 깊이를 Micrometer 게이지로 노출(현재 `ControlNotifyMetrics` 만 유사 게이지 보유 — 배치 재시도 큐는 미계측).

### [B-ISSUE-84] TC-DEID-072 — mock-server 의 `retrieve_progress.fileName` 계약이 실서버와 달라, 1차 회수 경로(`{stem}-mask{ext}`)가 로컬 E2E 로 전혀 검증되지 않음
- **심각도**: MEDIUM (검증 커버리지 갭 — 프로덕션 코드 결함 아님)
- **기대 동작(기대효과)**: 목업 경유 E2E 가 실서버 계약을 재현해, 과거 회귀(“`fileName` 을 결과 파일명으로 오해 → 완료를 `'F'` 로 오종결”, 커밋 74be3a8/PR#27)가 다시 발생하면 로컬에서 잡힌다.
- **현재 동작(이슈 내용)**:
  - **실서버 계약(2026-07-21 curl/ll 실측)**: `fileName` = **원본 입력파일 절대경로**(예 `/nas-.../raw/001.mp4`), 산출물 = `export_path/001-mask.mp4` (`-` 하이픈).
  - **mock 구현**: `mock-server/app/routers/deid.py:86` `"fileName": ds.name` — `ds.name` 은 `services/deid_sim.py:157-159 MASK_SUFFIX="_mask"` 로 만든 **산출물 basename** `{stem}_{yyyyMMddHHmm}_mask{ext}`. 즉 (a) 경로가 아니라 basename 이고 (b) 접미사가 `_mask`(언더스코어)다.
  - **실동작 결과**: `sanitizeFileName("clip-9101_202607250139_mask.mp4")` → 동일 → `toMaskName` 이 `-mask` 로 끝나지 않는다고 판단(`KpstDeidentService.java:458`) → 1차 경로 `clip-9101_202607250139_mask-mask.mp4` (**존재하지 않음**) → `isUsableDeidFile` false → **폴백 `scanSingleUsable` 로만 회수**. DB 실적재 경로가 이를 증명한다.
  - 따라서 로컬 파이프라인이 아무리 돌아도 **`toMaskName` 정상 경로는 한 번도 실행되지 않으며**, 폴백을 제거하거나 export 디렉터리에 파일이 2개가 되는 순간 완료 회수가 깨진다(그때만 발현).
  - 단위 테스트(`KpstDeidentServiceTest:896` 경로형_fileName…, `:314` toMaskName_…)는 실계약을 커버하므로 코드 자체는 정상.
- **재현/확인 경로**:
  ```bash
  grep -n "fileName" mock-server/app/routers/deid.py                 # :86 "fileName": ds.name
  grep -n "MASK_SUFFIX" mock-server/app/services/deid_sim.py         # "_mask"
  grep -n "MASK_SUFFIX" backend/src/main/java/kr/co/cudo/authoring/batch/service/KpstDeidentService.java  # "-mask"
  docker exec klid-backend ls /app/storage/deidentified/videos/23/   # clip-9101_202607250139_mask.mp4
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select de_idntf_file_path_nm from ls_deident_proc_log where proc_log_sn=23;"
  ```
- **영향**: 회귀 감시 공백. "목업 경유 실동작 PASS" 가 실서버 정상 동작을 보증하지 못하는 구간이 생긴다(거짓 PASS 위험).
- **수정 방향(제안)**: mock `retrieve_progress` 의 `fileName` 을 **원본 입력 절대경로**(`input_path + 원본 basename`)로 바꾸고 산출물 파일명을 `{stem}-mask{ext}` 로 통일해 실계약을 재현. 타임스탬프 부착이 mock 고유 요구라면 별도 토글(`KPST_MOCK_FILENAME_CONTRACT=real|legacy`)로 두 계약을 모두 돌릴 수 있게 한다. 대안으로 폴백 회수 시 WARN 로그를 남겨 "1차 경로 miss" 를 운영에서 관측 가능하게 한다.

### [B-ISSUE-85] TC-BATCH-159/160 (참고) — local 프로파일에서 재시도 폴러가 미등록이라 적재된 재시도 항목이 발화되지 않음
- **심각도**: LOW (환경 구성 — dev/stg/prd 무영향)
- **기대 동작(기대효과)**: 검증 환경에서 재시도 큐의 등록→발화 전 사이클을 관측할 수 있다.
- **현재 동작(이슈 내용)**: `BatchRetryTriggerConfig.java:19` 가 `authoring.batch.enabled` 로 게이팅되는데 `application-local.yml:50` 이 `false`. 반면 배치 본체는 `MarkingBatchBridge → AsyncBatchRunner`(@Async) 로 구동되어 **실패 시 재시도 적재는 계속 일어난다**. 실측: `ls_bat_rty_wtng` 에 rawSn 15/16/23/24 4행이 `PENDING`, `rty_prnmnt_dt` 가 10:41~10:44 로 이미 도래했으나 검증 시각(10:49+)까지 발화 0건. 즉 로컬에서는 재시도 항목이 **적재만 되고 영구 대기**한다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select raw_sn,rty_nmtm,stts_cd,rty_prnmnt_dt from ls_bat_rty_wtng;"
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select job_name from qrtz_job_details;"  # batchRetryJob 없음
  ```
- **영향**: 검증 커버리지 한정(운영 영향 없음 — `application.yml:184 BATCH_ENABLED:true` 가 dev/stg/prd 기본).
- **수정 방향(제안)**: 재시도 폴러 토글을 배치 파이프라인 토글과 분리(`authoring.batch.retry.enabled`)하거나, 검증 회차에서 `BATCH_ENABLED=true` 로 임시 기동해 폴러 발화 구간을 실증한다.

---

## UNCERTAINTIES 확정 결과

### #8 Quartz 클러스터링 실제 활성 — **확정: 코드 지원 O · 기본값 false · 런타임 비활성**
- 설정 지점 존재: `backend/src/main/resources/application.yml:72` `org.quartz.jobStore.isClustered: ${QUARTZ_CLUSTERED:false}` (+ `:73 clusterCheckinInterval`). 즉 **"application.yml 에 isClustered 가 있는가" → 있다. 단 기본값은 false.**
- 프로파일 override: `application-{local,dev,stg,prd}.yml` 4개 파일 모두 `QUARTZ_CLUSTERED`/`quartz` 키 **0건**(grep 확인).
- 배포 산출물: `deploy/onprem/config/backend/env.template:175` → `QUARTZ_CLUSTERED=false` (주석 `:170` "이중화로 2노드를 띄울 때만 두 노드 모두 true").
- **런타임 실측(결정적 증거)**: `qrtz_scheduler_state` **0행**(클러스터 모드에서만 인스턴스 체크인 행 생성) · `qrtz_fired_triggers.instance_name = "NON_CLUSTERED"` · `org.quartz.scheduler.instanceId: AUTO`(`:64`) 임에도 AUTO 생성 ID 대신 NON_CLUSTERED 사용 → JobStore 가 비클러스터 모드로 동작 중.
- 부수 확정: `PostgreSQLDelegate`(`:66`) 실사용 ✓ · `useProperties=true`(→ BYTEA JobDataMap 회피) ✓ · `QRTZ_LOCKS` 2행(TRIGGER_ACCESS/STATE_ACCESS) 실존 ✓ · `misfireThreshold=60000`(`:68`), 등록 트리거 `misfire_instr=0`(SMART_POLICY) ✓ · 잡 중첩 방지 `@DisallowConcurrentExecution` 은 `BatchQuartzJob`/`BatchRetryQuartzJob`/`KpstDeidentPollJob`/`ControlTrainingVideoScanJob`/`DatasetExportPendingSweepJob` **5종 전부** 보유, DB `is_nonconcurrent='t'` 확인 ✓
- **판정 귀결**: TC-BATCH-171 = PARTIAL, B-ISSUE-81 발행. CLAUDE.md("클러스터링 적용")와 D5(`:126` "옵션 지원") 서술 충돌도 함께 확인 — **D5 가 코드 정본**.

### #21 KpstDeidentTxService 상태전이·락해제 원자성 — **확정: 트랜잭션 경계 설계는 의도적·정합, 단 폴링 대상 클레임만 공백**
- 모든 공개 메서드가 `@Transactional("controlTransactionManager", propagation=REQUIRES_NEW)` — self-invocation 프록시 미적용 함정을 **별도 빈(cross-bean) 분리로 회피**(클래스 Javadoc `:31-33` 이 명시, `BatchTransitionService` 와 동일 패턴).
- 원자화 범위:
  - `finishDownloadAndComplete:59-70` — `verifyDeidFile` + `markDownloaded` + `applyCompletion`(Y/MARKING_READY/락해제/신고해소/알림 또는 REDEIDENT 프레임 attach)을 **단일 tx** 로 묶음 → 구 2분리(DOWNLOADED→Y) 의 stuck 창 제거 확인.
  - `failRedeidentCompletion:99-109` / `markRawDeidentFailed:116-120` — 메인 tx **롤백 이후** 별도 REQUIRES_NEW 로 커밋되도록 호출부(`KpstDeidentService:340-351, 367-382, 542-555`)에서 분리 → "롤백에 F-마킹이 휩쓸리는 조용한 실패" 회피 확인.
  - 락 해제: 성공 경로(`applyBatchCompletion:201-203`, `applyRedeidentCompletion:249-251`)·후처리 실패 경로(`failRedeidentCompletion:104-106`)·타임아웃 경로(`markTimeoutIfExpired:154-156`) **3경로 모두** `isRawLocked` 가드 후 `releaseRaw` → REDEIDENT 작업락 영구 잔존 차단 확인. `failPolling:76-82` 만 락 해제 없음이나, 비-REDEIDENT 는 위탁 시 락을 잡지 않으므로 정합(주석 `KpstDeidentService:314-315` 와 코드 일치).
  - `markTimeoutIfExpired:136-160` — `POLL_DOWNLOADED` 이면 조기 return(완료건 오-F 방지) · 시도초과 OR 경과초과 OR 판정 · `fail()` 이 terminal 상태로 전이해 `findByPollSttsCdIn([WAITING,POLLING])` 재조회 대상에서 제외 → 무한 폴링 차단 확인.
- **잔여 공백 1건**: 폴링 대상 **선점(claim)** 만 원자성이 없음(`findByPollSttsCdIn` 평문 조회, `finishDownloadAndComplete` 멱등 가드 없음) → B-ISSUE-82. 단일 노드에서는 `@DisallowConcurrentExecution` 으로 무해.
- 테스트 커버리지: `KpstDeidentTxServiceTest` 22 케이스(Y전이/파일무효/타임아웃 REDEIDENT·비REDEIDENT/락해제/APPROVED 유지/회귀) — `_raw/test-baseline.md` 기준 전건 GREEN.

---

## 요약

- 총 **37건** / PASS **33** / FAIL **0** / PARTIAL **4** / BLOCKED **0** / N/A **0** / 확인필요 **0**
  - PARTIAL: TC-BATCH-159(재시도 RETRYING 고아) · TC-DEID-072(1차 회수 경로 E2E 미검증) · TC-DEID-077(2노드 중복 폴링) · TC-BATCH-171(클러스터링 비활성)
- **근거 라인 드리프트: 1건** — TC-BATCH-171 근거 `QuartzConfig.java:31-37` 은 클러스터링과 무관(DataSource 주입부). 실제 근거는 `application.yml:69-73`. (TC-DEID-066 `88-91`→실제 `87-91` 은 1행 오차로 비드리프트 처리)
- **self-fill 결함: 0건** — KPST prjId·datasetId·fileName·procState 전부 mock 응답 유래(mock 로그 `POST /project` 4건 / `GET /retrieve_progress` 4건 ↔ backend `submitted`/`poll completed` 로그·DB 적재값 1:1 대응). 비식별 산출물도 mock 이 생성한 실파일(18바이트) 기준으로 `isUsableDeidFile` 통과.
- **회귀 확인**: 과거 "KPST 완료를 `'F'` 로 오종결" 결함(PR#27) **재발 없음** — rawSn 23/24/25/26 4건 모두 `poll_stts_cd=DOWNLOADED, proc_stts_cd=SUCCEEDED, de_ident_yn='Y'`. 다만 회수는 폴백 경로로 이뤄져 1차 경로 감시망은 공백(B-ISSUE-84).
- **신규 이슈 5건**: B-ISSUE-81(HIGH) · 82(HIGH) · 83(MEDIUM) · 84(MEDIUM) · 85(LOW)
- **테스트 커버리지 공백**: `AsyncBatchRunner` 전용 단위테스트 부재(TC-BATCH-172) · `QuartzConfig` 클러스터링 설정 검증 테스트 부재(TC-BATCH-171) · `deleteIdleByRawSn`(RETRYING 보존) DB 레벨 IT 부재(TC-BATCH-161 은 mock verify 만)
