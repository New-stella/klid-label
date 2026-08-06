# B 클러스터 part6 (B-13~B-17) 2차 검증 결과

> 대상: `docs/test-cases/B-batch-deidentify.md` 의 B-13(34) · B-14(10) · B-15(11) · B-16(6) · B-17(8) = **69건**
> 취소선(폐기) 행: **0건** (본 5개 섹션에는 폐기 표기가 없다 — 집계 제외 대상 없음)
> 환경: backend `localhost:18081`(HEAD `ca3c712b`, 컨테이너 기동 `2026-07-30T17:43:08Z`, restarts=0) · mock-server `:9400` · postgres `:5432` 스키마 `public`
> 수행 시각: KST 2026-07-31 03:20~03:35 (mock 로그는 UTC = KST-9h)
> **파일 수정 0건 / 빌드·테스트 실행 0건 / backend 재기동 0건** (검증 중 소스·설정·테스트 무수정, 본 문서 1개만 신규 작성)
> ⚠ 검증 중 다른 에이전트가 같은 스택에서 파이프라인을 구동 중이었다(rawSn 145~150 신규 적재). 아래 실동작 증거는 **호출자 IP `172.18.0.5`(backend 컨테이너)** 를 확인한 것만 인용했다.

---

## 집계

| 섹션 | 케이스 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| B-13 KPST 위탁·폴링 | 34 | 31 | 0 | 3 | 0 | 0 | 0 |
| B-14 Quartz/인프라/헬스 | 10 | 10 | 0 | 0 | 0 | 0 | 0 |
| B-15 배치 스텝 tx 경계 | 11 | 11 | 0 | 0 | 0 | 0 | 0 |
| B-16 오토라벨 일괄저장 | 6 | 6 | 0 | 0 | 0 | 0 | 0 |
| B-17 LS_DATA_RAW FK(V146) | 8 | 7 | 0 | 1 | 0 | 0 | 0 |
| **합계** | **69** | **65** | **0** | **4** | **0** | **0** | **0** |

- 실동작 판정 **28건** / 정적 판정 **41건**. BLOCKED 0건(재기동이 필요한 단 하나의 분기 — Quartz 기동 거부 — 는 부팅 로그로 배선을 실측하고 거부 분기만 코드 조건 + 컨텍스트 refresh 테스트로 판정했다. 아래 TC-BATCH-173 비고).
- **신규 결함 1건(HIGH)**: `ls_data_raw` 직접 자식은 V146 로 CASCADE 가 완비됐으나 **`ls_data_src` 를 참조하는 손자 테이블(라벨 포함) 14종에 FK 가 0건** → 부모 삭제 시 라벨 고아가 실제로 늘어난다(실측 64 → 86).
- 나머지 3건(PARTIAL)은 **KPST 논블로킹화(commit `862ca6d8`)가 카탈로그 최신화(`119ea148`) 이후에 머지**되어 케이스 기대값이 낡은 것으로, 코드 쪽이 의도적 개선이다.

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 실측 | 판정 |
|---|---|---|---|
| **B-ISSUE-81** | Quartz `isClustered` 전 프로파일 false + 온프렘 템플릿도 false → 2노드 중복 발화 | `application-stg.yml:11`·`application-prd.yml:13` = `${QUARTZ_CLUSTERED:true}`, 공통 `application.yml:95` = `:false`(local/dev), `deploy/onprem/config/backend/env.template:226` = `true`. `QuartzClusteringGuard`(`@PostConstruct`)가 allowlist(`containsAll`) + `ENV` 독립축으로 fail-closed. 부팅 로그 실측 `[Quartz] 단일 노드 모드(클러스터링 off) — 활성 프로파일=[local]` → 가드가 실제로 배선·실행됨 | **해소** |
| **B-ISSUE-82** | 폴링 대상 조회에 원자 클레임 없음 + `finishDownloadAndComplete` 멱등 가드 없음 + 상한 없음 | 3축 모두 해소 — ①`claimForPoll`(리스 조건부 UPDATE, `LsDeidentProcLogRepository:81-92`) ②`claimDownloadCompletion`(완료 전이 자체가 클레임, `:112-125`) ③`findByPollSttsCdIn(statuses, Pageable)` 무제한 오버로드 제거(`:55`). **2세션 동시 UPDATE 실측 1행/0행**, 완료 클레임 재호출 실측 1행/0행 | **해소** |
| **B-ISSUE-42** | YOLO/SAM2 루프 내 개별 `save()`, IDENTITY PK 로 JDBC 배치 무력 | 개별 `save()` **0건**(YOLO/SAM2 모두 `AutoLabelBatchPersister.saveAll` 1회씩). `LsDataLbl:55`·`LsDataLblAiInfo:30` 은 여전히 `GenerationType.IDENTITY` → JDBC 배치는 비활성 유지 | **부분 해소(현상 유지)** — TC-BATCH-196 이 이 현상을 기대값으로 고정 |
| PIPE-ISSUE-01 (2차 파이프라인 구동) | `dev-seed.sql:52` 가 부팅마다 `DEV-CLIP-%` 삭제, 자식 `LS_DATA_LBL` 은 FK 부재로 고아 | V146 은 **`ls_data_raw` 직접 자식만** 다룬다. `ls_data_lbl.src_sn → ls_data_src` FK 는 **여전히 없다**(전수 조회 결과 `ls_data_src` 를 참조하는 FK **0건**). 고아 라벨 64행 그대로 잔존 | **미해소** → B-ISSUE-101 |

---

## ★ `ls_data_raw` 참조 FK 전수표 (`pg_constraint` 실측)

`SELECT ... FROM pg_constraint WHERE contype='f' AND confrelid='ls_data_raw'::regclass` → **28행**
(= V146 `child_specs` 27 + V146 4)절이 CASCADE 로 재생성한 `FK_LS_EVNT_ANNO_RAW` 1)

| # | 자식 테이블 | 컬럼 | FK | ON DELETE | 고아 위험 |
|---:|---|---|:--:|---|---|
| 1 | ls_bat_rty_wtng | raw_sn | O | CASCADE | 없음 |
| 2 | ls_batch_proc_log | data_raw_sn | O | CASCADE | 없음 |
| 3 | ls_data_lbl_ai_info | data_raw_sn | O | CASCADE | 없음 |
| 4 | ls_data_meta | raw_sn | O | CASCADE | 없음 |
| 5 | ls_data_src | raw_sn | O | CASCADE | 없음(단 **손자 유발** — 아래) |
| 6 | ls_deident_proc_log | data_raw_sn | O | CASCADE | 없음 |
| 7 | ls_marking | raw_sn | O | CASCADE | 없음 |
| 8 | ls_auth_work_lock | data_raw_sn | O | CASCADE | 없음 |
| 9 | ls_data_issue | data_raw_sn | O | CASCADE | 없음 |
| 10 | ls_data_meta_review | data_raw_sn | O | CASCADE | 없음 |
| 11 | ls_deident_report | data_raw_sn | O | CASCADE | 없음 |
| 12 | ls_raw_data_enrollment | raw_data_id | O | CASCADE | 없음 |
| 13 | ls_raw_data_status | raw_data_id | O | CASCADE | 없음 |
| 14 | ls_task_assign_history | raw_data_id | O | CASCADE | 없음 |
| 15 | ls_task_assignment | raw_data_id | O | CASCADE | 없음 |
| 16 | ls_task_event_log | raw_data_id | O | CASCADE | 없음 |
| 17 | ls_data_aug_rvw | data_raw_sn | O | CASCADE | 없음 |
| 18 | ls_data_raw_hstry | raw_sn | O | CASCADE | 없음 |
| 19 | ls_dataset_export | data_raw_sn | O | CASCADE | 없음 |
| 20 | ls_dataset_video_meta | raw_sn | O | CASCADE | 없음 |
| 21 | ls_label_version | data_raw_sn | O | CASCADE | 없음 |
| 22 | ls_control_notify_fallback | raw_sn | O | CASCADE | 없음 |
| 23 | ls_meta_repl_outbox | raw_sn | O | CASCADE | 없음 |
| 24 | ls_mon_noti_acml | raw_sn | O | CASCADE | 없음 |
| 25 | ls_portal_user_label | src_raw_sn | O | CASCADE | 없음 |
| 26 | ls_tus_upload | raw_sn | O | **SET NULL** | 없음(원장 보존, 의도) |
| 27 | ls_webhook_idempotency | raw_sn | O | **SET NULL** | 없음(원장 보존, 의도) |
| 28 | ls_evnt_anno | raw_sn | O | CASCADE | 없음(V146 4절이 NO ACTION→CASCADE 통일) |

**FK 가 없는 `raw_sn` 계열 컬럼 (전수)** — 전부 V146 이 명시 제외한 것이며 정상:

| 테이블/뷰 | 컬럼 | 제외 사유(V146:17-26) |
|---|---|---|
| ls_data_raw | raw_sn | 자기 PK |
| ls_data_raw | orgnl_raw_sn | 파생 계보 self-reference — 자동복구가 위험(파생본이 '원본'으로 승격) |
| ls_dataset_video_meta | orgnl_raw_sn | 승인 시점 **동결값** |
| mng_clip_schedule_que | raw_sn | **MNG_\* 관제 소유** — 선승인 필요 |
| v_completed_{video,frame,meta,label_change} | raw_sn / orgnl_raw_sn | 뷰 |

### ★ 진짜 구멍 — `ls_data_src` 를 참조하는 FK 는 **0건**

`SELECT ... WHERE confrelid='ls_data_src'::regclass` → **0행**. `src_sn`/`data_src_sn` 컬럼을 가진 테이블 **14종**(`ls_data_lbl` · `ls_data_lbl_ai_info` · `ls_data_lbl_hstry` · `ls_label_version` · `ls_data_aug` · `ls_data_aug_job_file` · `ls_data_aug_rvw` · `ls_data_issue` · `ls_data_meta_review` · `ls_auth_work_lock` · `ls_batch_proc_log` · `ls_data_src_hstry` · `ls_data_src` 자기참조)이 전부 무보호다.

**실증(트랜잭션 내 삭제 후 ROLLBACK — 데이터 무변경 확인 완료)**

```
BEGIN;
 before_src=3  before_lbl=22  before_orphan=64
 DELETE FROM ls_data_raw WHERE raw_sn=126;   -- DELETE 1
 after_src=0   after_status=0  after_export=0   ← 직접 자식은 CASCADE 정상
 after_orphan=86                               ← 고아 라벨 +22 (구조적 불가능 아님)
ROLLBACK;  → rollback_check_raw=1, rollback_check_src=3 (원상복구 확인)
```

---

## ★ 트랜잭션 경계 정적 스캔 결과

`BatchStep` 구현 **7종 전수**(`grep -rn "execute(BatchContext"`), `@Transactional` 실측:

| 빈 | `execute(BatchContext)` 위치 | 경계 | 내부 위임 | 자기호출? | 판정 |
|---|---|---|---|:--:|---|
| YoloAutolabelStep | `:156` | `@Transactional(controlTransactionManager, REQUIRES_NEW)` `:155` | `run(rawSn)` `:168` | 예(의도) | 정상 |
| Sam2SegmentStep | `:135` | REQUIRES_NEW `:134` | `run(rawSn, hints)` `:147` | 예(의도) | 정상 |
| TrackInterpolationStep | `:105` | REQUIRES_NEW `:104` | `run(rawSn)` `:116` | 예(의도) | 정상 |
| VlmTimeseriesStep | `:220` | REQUIRES_NEW `:219` — **readOnly 아님** | `runWithMarking` `:253` / `run` `:237`(readOnly) | 예(의도) | 정상 |
| FfmpegFrameExtractor | `:130` | REQUIRES_NEW `:129` | `extractByMarks` `:161`/`:176` | 예(의도) | 정상 |
| DeidentifyStep | `:240` | **무애노테이션(면제)** | `run(raw)` `:269` 를 **ObjectProvider 자기참조 프록시**로 호출 `:241` | 아니오(프록시) | 정상(중첩 방지) |
| MarkingLoadStep | `:50` | **무애노테이션(면제)** | 조회+JSON 파싱만, DML 0건 | — | 정상 |

- 1차 실사고 지점 `FfmpegFrameExtractor.execute()` 는 **경계가 복원**돼 있다(`:129`).
- 정적 드리프트 가드 `BatchStepTransactionBoundaryTest`(`:37-118`) 실재 — 클래스패스 스캔(`kr.co.cudo.authoring`) + 스캔 결과 ≥6 단언 + `execute` **미선언 시 `AssertionError`**(`:71-78`) + `propagation`·`value` 까지 단언. `DeidentifyStep` 에 애노테이션을 붙이면 **실패하는** 역방향 테스트(`:107-117`)까지 있다.
- 리포지토리 규약 확인: `LsDataSrcRepository.bumpLabelVersionIn`(`:292-295`)·`bumpLabelVersionByRawSn`(`:305-308`) 에 `@Transactional` **없음**(경계는 스텝이 제공) ✓
- **잘못된 자기호출 잔존 없음**: `KpstDeidentService.pollOne`/`completeDeidentification` 은 상태 전이를 전부 `txService.*`(별도 빈, REQUIRES_NEW) 로 cross-bean 위임한다. `DeidentFrameAttacher` 는 self-invoke 오버로드를 제거하고 3-arg 단일 진입점만 `@Transactional` 을 갖는다(`:93-94`).
  - ⚠ 다만 `KpstDeidentService:145-147` javadoc 이 *"`submit(...)` 자체가 REQUIRES_NEW 라"* 라고 적혀 있는데 **현재 `submit` 에는 `@Transactional` 이 없다**(논블로킹화 잔재). 실패 흔적은 `batchTransitionService.recordDeidentFailure`/`txService.failSubmit`(둘 다 cross-bean REQUIRES_NEW)로 커밋되므로 **동작상 문제는 없고 주석만 낡았다** — 결함으로 계수하지 않음.

---

## B-13 결과표 (KPST 비식별 위탁·폴링) — 34건

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-DEID-060 | 위탁: createProject → procLog WAITING, 미전이 | PASS | [실동작] mock `POST /project` → `prj_id=20/21 name=raw149/raw150`(172.18.0.5). 같은 시각 `ls_deident_proc_log` 139/140 = `POLL_STTS=WAITING→POLLING`, `ls_data_raw(149,150).de_ident_yn='N'`·`data_stts_cd=PENDING` 유지. backend 로그 `[KpstDeid] submitted rawSn=149 prjId=20`(스레드 `kpst-submit-1`) | Phase C-2 로 **선커밋(`markKpstSubmitPending`) + 비동기 ACK(`claimSubmitAck`)** 로 분리됨. 케이스가 적은 `markKpstSubmitted(prjId)` 동기 호출은 없으나 결과(WAITING·prjId 기록·미전이)는 동일 |
| TC-DEID-061 | 위탁: 원본경로 부모 없음 → INVALID_INPUT(CWE-22) | **PARTIAL** | [정적] 가드는 존재(`KpstDeidentService:319-323` `parent==null` → `INVALID_INPUT`)하나 호출측 `:294-303` 이 **모든 `RuntimeException` 을 `EXTERNAL_API_ERROR`(502)로 재포장** → 관측 결과는 400 이 아님 | B-ISSUE-102 |
| TC-DEID-062 | 위탁: 실패 → 'F' 마킹+EXTERNAL_API_ERROR | **PARTIAL** | [정적] 논블로킹화로 `createProject` 실패는 **동기 예외를 던지지 않는다**(`:421-431` subscribe → `KpstSubmitOutcomeRecorder.onSubmitFailed` → `txService.failSubmit` = procLog FAILED + `'F'`). 동기 `EXTERNAL_API_ERROR` 는 **제출 이전 사전조건 실패**에만 남음(`:302`) | B-ISSUE-103 (카탈로그 기대값이 낡음) |
| TC-DEID-063 | 위탁: export 디렉터리 정리(stale 오회수 방지) | PASS | [정적] `cleanExportDir :488-527` — ①리졸버 **재계산 경로와 `equals`** 일 때만 진행(`:494-502`) ②`Files.list` 비재귀(`:508`) ③`isRegularFile(NOFOLLOW_LINKS)` 로 심링크·디렉터리 제외(`:511`) ④미존재/비디렉터리 no-op(`:504`) ⑤IOException 은 경로 미노출 WARN 후 위탁 진행 | 4중 가드 전부 확인 |
| TC-DEID-064 | 폴링: procState=2 전체완료(AND) → 다운로드+완료 | PASS | [실동작] procLog 135~138(rawSn 145~148) `WAITING→POLLING→DOWNLOADED/SUCCEEDED`, `ls_data_raw.de_ident_yn N→Y`·`data_stts_cd=MARKING_READY`. mock `GET /retrieve_progress` 반복 + `production completed prj_id=16/17`. [정적] `allDatasetsCompleted :966-978` 은 `allMatch(procState==2)` | AND 판정 확인 |
| TC-DEID-065 | 폴링: 터미널 실패(3/4/99) 우선 → 즉시 'F' | PASS | [정적] `anyDatasetFailed` 호출 `:587` 이 `allDatasetsCompleted` 호출 `:600` **앞**. REDEIDENT 는 `failRedeidentCompletion`(락 해제 포함), 그 외 `failPolling` | 타임아웃 대기 없이 종결 |
| TC-DEID-066 | 폴링: procState=99(오류 sentinel) 종결 | PASS | [정적] `PROC_STATE_TERMINAL_FAILED = {3,4,99}` `:94-98` | |
| TC-DEID-067 | 폴링: prjId null(위탁 미완) → 타임아웃 검사만 | **PARTIAL** | [정적] `:541-561` — 현재는 **ACK 대기 유예 skip**(외부 호출 0건·카운터 미소모 ✓)이고, 유예 초과 시 `failSubmit(ACK_MISSING_CODE)` 로 회수한다. `markTimeoutIfExpired` 는 **호출하지 않는다**(코드가 "구 코드처럼 부르면 예산만 소모"라고 명시) | B-ISSUE-104. "외부 미호출"은 충족, "markTimeoutIfExpired"는 의도적으로 폐기 |
| TC-DEID-068 | 폴링: retrieveProgress 예외 → 타임아웃 평가 | PASS | [정적] `:562-576` catch 안에서 `markTimeoutIfExpired` 호출 + 클래스명만 로그(CWE-209) | 무기한 stuck 차단 확인 |
| TC-DEID-069 | 폴링: 완료지만 fileName bad → 터미널 처리 | PASS | [정적] `:611-625` REDEIDENT=`failRedeidentCompletion`(락 해제) / 비REDEIDENT=`failPolling` | |
| TC-DEID-070 | 폴링: 산출물 무결성 실패 → 'F' | PASS | [정적] `:626-648` — `isUsableDeidFile` false → 유예 재확인 → 여전히 false 면 `failPolling`/`failRedeidentCompletion`. **'Y' 전이 경로 없음**(fail-open 아님) | |
| TC-DEID-071 | 폴링: 진행중 → 시도 증가+타임아웃 검사 | PASS | [실동작] `poll_atmpt_cnt` 관측 1→2→4→5 (30s 주기), `poll_last_dt` 매 틱 갱신. [정적] `:666-668` | |
| TC-DEID-072 | fileName 회수: {stem}-mask{ext} 변환(실측 계약) | PASS | [실동작] `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 8건 전부 `.../{rawSn}/deid/sample-cctv-1080p-mask.mp4`, 디스크 실재(126: 34,654,319B). [정적] `downloadResult :764-791`, `toMaskName :812-822`(이미 `-mask` 면 재부여 안 함) | `deidentified.mp4` 조합 아님 — 응답 기반 회수 확인 |
| TC-DEID-073 | fileName 회수 폴백: 단일 산출물 스캔 | PASS | [정적] `scanSingleUsable :833-857` — 1개=회수(base 하위 단언 `:847`) / 0개=null / 2개↑=`INVALID_INPUT`(모호) | |
| TC-DEID-074 | sanitizeFileName: basename만(CWE-22) | PASS | [정적] `:869-890` — `Paths.get` → `getFileName()`, `InvalidPathException`(NUL 등) 정규화, `/`·`\`·`..` 잔존 거부, **원문 미노출** | |
| TC-DEID-075 | 폴링 잡: 대상 없으면 noop | PASS | [정적] `KpstDeidentPollJob:95-98` DEBUG 후 return. [테스트] `KpstDeidentPollJobTest:대상이_없으면_외부호출없이_즉시종료한다` | |
| TC-DEID-076 | 폴링 잡: 건별 try/catch 격리 | PASS | [정적] `:110-114` 클래스명만. [테스트] `KpstDeidentPollJobTest:한_작업_폴링실패가_다른_작업을_막지_않는다` | |
| TC-DEID-077 | 폴링 잡: 동시 실행 금지는 같은 노드 한정 | PASS | [실동작] `qrtz_job_details.kpstDeidentPollJob.is_nonconcurrent='t'`. 노드 간 방어는 원자 클레임(TC-DEID-085)이 담당 — 본 검증에서 실증됨 | 1차 B-ISSUE-82 대체 방어 확인 |
| TC-DEID-078 | 폴링 잡: 재기동 복원(DB 조회) | PASS | [실동작] backend 로그 `[KpstDeidPoll] polling targets count=6` (인메모리 상태 없이 DB `findByPollSttsCdIn(statuses, page)` 로 재조회). [정적] `:93-94` · `LsDeidentProcLogRepository:55` | |
| TC-DEID-079 | completeDeidentification: 파일무효 F-마킹 보정 | PASS | [정적] `KpstDeidentService:898-911`(catch → `isRedeidentLog` 아니면 `txService.markRawDeidentFailed`) · `KpstDeidentTxService:269-273`(REQUIRES_NEW) | 메인 tx 롤백에 안 휩쓸림 |
| TC-DEID-080 | KPST 조건부 빈: kpst.deid.enabled=false 미등록 | PASS | [정적] `@ConditionalOnProperty` 3곳 — `KpstDeidentService:69` · `KpstDeidentTxService:37` · `KpstDeidentPollTriggerConfig:20`. `application.yml:462` = `${KPST_DEID_ENABLED:true}` | 로컬은 true(빈 활성) — false 형상은 `DeidentifyStepKpstDisabledIntegrationTest` 커버 |
| TC-DEID-081 | ★위탁 전 원본 실재 가드 | PASS | [실동작] rawSn 127/128 = `ls_deident_proc_log` 117/118 `PROC_STTS=FAILED, ERR_CD=KPST_SOURCE_MISSING`(**커밋됨**), `ls_data_raw.de_ident_yn='F'`, `data_stts_cd` 는 MARKING_READY 미전이. mock 로그에 raw127/raw128 프로젝트 생성 **없음**(createProject 미호출). [정적] `:286`(호출) · `:446-466`(`verifySourceOrFail`, `recordDeidentFailure` REQUIRES_NEW 후 `INVALID_INPUT`) — 로그·예외에 경로 원문 없음 | 구 결함(18B 스텁 'Y') 회귀 없음 |
| TC-DEID-082 | 원본 가드 비활성 시 침묵 금지 | PASS | [정적] `:447-451` WARN 1줄(rawSn 만) 후 통과 | 런타임은 `verify-source-exists=true`(기본)라 이 분기 미실행 |
| TC-DEID-083 | ★산출물 무결성 판정 단일 원천 | PASS | [정적] `KpstDeidentService:692-694` → `DeidentArtifactIntegrity.isValidVideoArtifact`(`:87-110`) = 정규파일(NOFOLLOW) + `MIN_VIDEO_BYTES=512`(`:48`) + 컨테이너 시그니처. [테스트] `DeidentArtifactIntegrityTest` **11건**(18B placeholder 거부·심링크 거부·MPEG-TS 오판 거부·`junk` 박스 제외 등) | 구 "존재+>0바이트" 폐기 확인 |
| TC-DEID-084 | 무결성 실패 시 1회 유예 재확인 | PASS | [정적] `recheckAfterGrace :707-730` — `fileExists` false(=파일 자체 부재)면 **유예 없이 즉시 null**(`:709`), 있으면 `Math.min(설정, MAX_RESULT_RECHECK_DELAY_MS=5000)` 대기 후 **1회** 재산출·재판정 | clamp 상한 확인(`:138`, `:713`) |
| TC-DEID-085 | ★폴링 대상 원자 클레임(리스) | PASS | [실동작] **2세션 동시 실행 재현** — 세션A `UPDATE 1` / 세션B `UPDATE 0`(A 커밋 대기 후 갱신본으로 WHERE 재평가). 쿼리는 `claimForPoll`(`LsDeidentProcLogRepository:81-92`) 원문 그대로. 잡 배선 `KpstDeidentPollJob:101-109`, tx `KpstDeidentTxService:60-66`(REQUIRES_NEW) | `FOR UPDATE SKIP LOCKED` 미채택 사유(외부 I/O 가 tx 밖)도 주석에 명시 |
| TC-DEID-086 | 클레임 술어 fail-closed | PASS | [실동작] 같은 행을 `POLL_STTS_CD='FAILED'` 로 전이시킨 뒤 클레임 시도 → **0행**. 술어 `POLL_STTS_CD IN ('WAITING','POLLING')`(`:87`) | |
| TC-DEID-087 | 리스 길이 = 폴링 주기 − 5s(하한 1s) | PASS | [실동작] `poll-interval-sec=30` → 리스 25s, 단일 노드가 **매 틱(30s) 재클레임** 관측(`poll_last_dt` 03:24:02 → 03:24:32, `poll_atmpt_cnt` +1). [정적] `KpstDeidentPollJob:72-74, 126-128` | |
| TC-DEID-088 | ★완료 전이 자체가 클레임 | PASS | [실동작] `claimDownloadCompletion` SQL 을 같은 tx 에서 2회 실행 → **1행 / 0행**(ROLLBACK). 후처리(프레임 attach·Y 전이·락 해제·알림)는 1행 얻은 호출만 진입(`KpstDeidentTxService:209-213`) | 프레임 이중 attach 창 없음 |
| TC-DEID-089 | 완료 후처리 실패 시 클레임도 롤백 | PASS | [정적] 클레임 UPDATE 가 완료 트랜잭션(`finishDownloadAndComplete`, REQUIRES_NEW `:206`) **안**에 있어 후처리 예외 시 함께 롤백 → 재폴링 대상 유지 | |
| TC-DEID-090 | 틱당 대상 상한 + 기아 방지 정렬 | PASS | [정적] `pollPage() :119-123` — `poll-batch-size` 기본 200(`application.yml:475`), `<1` 이면 `DEFAULT_BATCH_SIZE`, `Sort.asc(pollLastDt).nullsFirst()` + `procLogSn asc`. [실동작] `polling targets count=6` | 무제한 오버로드 자체가 리포지토리에 없음 |
| TC-DEID-091 | 회수 디렉터리 2-way | PASS | [정적] `recoveryDirs :797-802` — `deidVideoDirQuietly`(신, Optional) 우선 + `{deid_base}/videos/{rawSn}`(구) 항상 추가, `LinkedHashSet` 중복 제거 | 신 위치 도출 실패해도 구 위치 시도 확인 |
| TC-DEID-092 | 1차 mask 경로 miss → 폴백 회수 시 WARN | PASS | [정적] `:785` `primary mask path miss — recovered by fallback scan rawSn={}`(경로 원문 없음) | |
| TC-DEID-093 | DeidentFrameAttacher 도 동일 무결성 판정 사용 | PASS | [정적] `DeidentFrameAttacher:160-162` `isUsable()` → `DeidentArtifactIntegrity.isValidVideoArtifact`, 호출 `:99` | 판정 두 벌 없음 |

---

## B-14 결과표 (Quartz 클러스터링 / 인프라 / 헬스) — 10건

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-170 | Quartz JobStore = controlDataSource(PG) | PASS | [실동작] `qrtz_*` 테이블이 `klid_system` 에 실재하고 `qrtz_triggers` 3행(`kpstDeidentPollTrigger`=ACQUIRED, `datasetExportPendingSweepTrigger`·`datasetExportFailureRecoveryTrigger`=WAITING). [정적] `QuartzConfig:30-37` `setDataSource(controlDataSource)` · `application.yml:78,85-86` `job-store-type: jdbc` + `PostgreSQLDelegate` + `useProperties:'true'` | |
| TC-BATCH-171 | ★2노드 A-A: 동일 잡 중복 발화 방지 | PASS | [정적] `application-stg.yml:11` · `application-prd.yml:13` = `${QUARTZ_CLUSTERED:true}` / 공통 `application.yml:95` = `:false` / `env.template:226`=true. [테스트] `QuartzClusteringConfigGuardTest`(yml·템플릿 5건) | 로컬은 단일 노드 형상이라 `isClustered=false`·`instance_name=NON_CLUSTERED` 가 **정상**(UNCERTAINTIES #8) — 결함 아님 |
| TC-BATCH-172 | AsyncBatchRunner: 예외 삼킴(@Async) | PASS | [정적] `AsyncBatchRunner:21-35` — `@Async("batchAsyncExecutor")`, catch 에서 ERROR 로그 후 정상 종료. `SKIPPED` 는 WARN 으로 별도 노출 | |
| TC-BATCH-173 | ★클러스터링 fail-closed — 기동 거부 | PASS | [실동작] 부팅 로그 `[Quartz] 단일 노드 모드(클러스터링 off) — 활성 프로파일=[local]`(02:43:31, `QuartzClusteringGuard`) → `@PostConstruct` 가 실제 배선·실행됨을 확인. [정적] `:64-75`(check) → `:85-95`(verify) 가 `IllegalStateException` 을 던짐(WARN 아님) | ⚠ **거부 분기 자체는 재기동이 필요해 실행하지 않았다**(임무 규칙: backend 재기동 금지). `QuartzClusteringBootGuardTest` 가 컨텍스트 refresh 로 5건 커버(prd/stg/ENV=prd 기동 실패, local/dev 정상) |
| TC-BATCH-174 | 판정은 allowlist `containsAll` | PASS | [정적] `SINGLE_NODE_PROFILES={local,dev}` `:53`, `singleNodeAllowed :98-103` = `!activeProfiles.isEmpty() && SINGLE_NODE_PROFILES.containsAll(activeProfiles)` → `local,prd`·`prd1`·`LOCAL`·미지정 전부 거부. [테스트] `QuartzClusteringGuardTest:프로파일이_혼합되거나_오타면_엄격하게_판정한다` | denylist 아님 확인 |
| TC-BATCH-175 | ENV 배포 표식이 독립 축 | PASS | [정적] `DEPLOYED_ENV_MARKERS={stg,prd}` `:56`, `deployedEnvMarker != null` 이면 즉시 거부(`:99-101`, `:106-112`, trim+lowercase). [테스트] `QuartzClusteringBootGuardTest:dev_프로파일이라도_배포표식_ENV가_prd면_기동이_실패한다` | 프로파일 하향 우회 불가 |
| TC-BATCH-176 | 값 출처는 Quartz 실 프로퍼티 | PASS | [정적] `KEY_CLUSTERED = "spring.quartz.properties.org.quartz.jobStore.isClustered"` `:50` 을 `environment.getProperty(...)` 로 직접 읽음 `:66` | 별도 플래그 발명 없음 |
| TC-BATCH-177 | 클러스터링 ≠ 잡 내부 레이스 방어 | PASS | [실동작] 로컬은 `isClustered=false` 인데도 **원자 클레임이 동시 2세션을 1/0 으로 분리**(TC-DEID-085/088 실증) → 두 방어가 독립임을 실측. [정적] `QuartzClusteringGuard:40-41` javadoc | 상호 대체 불가 확인 |
| TC-BATCH-178 | ⚠ batchAsyncExecutor 는 공유 싱글턴 | PASS | [정적] `AsyncConfig:35-51` core2/max4/queue50/CallerRunsPolicy. `@Async("batchAsyncExecutor")` 사용처 **9빈** — `AsyncDeidentifyRunner` · `AsyncBatchRunner` · `AsyncAugmentFrameRunner` · `AsyncDatasetExportRunner`(3메서드) · `AsyncResolutionRunner` · `AugmentRequestBridge` · `AsyncVideoMetaRunner` · `VlmWithheldResumeRunner` · `DevPipelineRunner` | 케이스 서술(비식별·배치·증강·export·해상도·VLM재개·영상메타)과 정확히 일치 |
| TC-BATCH-179 | 비식별 헬스체크 3분기 · root 핑 | PASS | [실동작] `GET /api/actuator/health`(REVIEWER) → `"deidentifyHealth":{"status":"UP","details":{"service":"deidentify","mode":"kpst"}}`. 같은 시각 mock 인바운드 `172.18.0.5 - "GET / HTTP/1.1" 200 OK`(`/health` 아님). [정적] 3분기 `:78-112`, 예외는 `e.getClass().getSimpleName()` 만 | mock 분기(mode=mock)·unconfigured(DOWN) 는 정적 확인 |

---

## B-15 결과표 (배치 스텝 트랜잭션 경계) — 11건

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-180 | YOLO execute 경계 — 프록시 호출 시 트랜잭션 개시 | PASS | [실동작] rawSn 126 전 프레임 `ls_data_src.lbl_ver=2`(65/66/67) — `bumpLabelVersionIn`(`@Modifying`) 이 무-트랜잭션이면 "Executing an update/delete query" 로 실패했을 것. [정적] `YoloAutolabelStep:155-158`. [테스트] `YoloStepTransactionBoundaryIntegrationTest`(**`@Transactional` 미부착** `@SpringBootTest`) | 1차 실사고 회귀 없음 |
| TC-BATCH-181 | SAM2 execute 경계 | PASS | [정적] `Sam2SegmentStep:134-136` REQUIRES_NEW. [실동작] 폴리곤 라벨이 실제 커밋됨(126 라벨 19건 중 SAM2 분) | |
| TC-BATCH-182 | TrackInterpolation execute 경계 | PASS | [정적] `TrackInterpolationStep:104-106` REQUIRES_NEW | stale 삭제+재생성이 한 tx |
| TC-BATCH-183 | VLM execute 경계는 쓰기 가능(readOnly 아님) | PASS | [정적] `VlmTimeseriesStep:219-227` — `@Transactional(controlTransactionManager, REQUIRES_NEW)` **readOnly 없음**. `run` 만 `readOnly=true`(`:236`), `runWithMarking` 은 쓰기(`:252`). [실동작] `ls_marking(126).status=VLM_COMPLETED` 전이 저장됨 | 상한 규약 충족 |
| TC-BATCH-184 | FRAME_EXTRACT execute 경계 | PASS | [실동작] `LS_DATA_SRC` 126: 3행 INSERT(원본·비식별 2벌 경로 상이). [정적] `FfmpegFrameExtractor:129-131` | 1차 실사고 지점 복원 확인 |
| TC-BATCH-185 | 내부 위임은 자기호출 — 중첩 없음 | PASS | [정적] `FfmpegFrameExtractor:120-128` javadoc + `:143` `extractByMarks(...)` **`this.` 자기호출** → 어드바이스 미적용 → 스텝 1건=tx 1건. VLM/YOLO/SAM2/보간 동일 패턴 | 프록시 경유로 바꾸면 REQUIRES_NEW 2회 |
| TC-BATCH-186 | typed 메서드의 REQUIRES_NEW 는 보존 | PASS | [정적] `YoloAutolabelStep:167-168` · `Sam2SegmentStep:146-147` · `TrackInterpolationStep:115-116` · `VlmTimeseriesStep:236-237,252-253` · `FfmpegFrameExtractor:160-161,175-176` 전부 애노테이션 유지 | dev 트리거 직접 호출 진입점 보호 |
| TC-BATCH-187 | DeidentifyStep 면제(자기참조 프록시) | PASS | [정적] `DeidentifyStep.execute :240` 무애노테이션 + `:241` ObjectProvider 프록시로 `run()` 호출. `BOUNDARY_EXEMPT`(`BatchStepTransactionBoundaryTest:53`) 에 등재. **역방향 테스트**(`:107-117`)가 애노테이션 추가를 실패시킴 | 중첩 방지 확인 |
| TC-BATCH-188 | MarkingLoadStep 면제(DML 0건) | PASS | [정적] `MarkingLoadStep:50` 무애노테이션, `BOUNDARY_EXEMPT` 등재 | |
| TC-BATCH-189 | ★정적 드리프트 가드 — 신규 스텝 경계 누락 차단 | PASS | [정적] `BatchStepTransactionBoundaryTest:37-118` — `ClassPathScanningCandidateComponentProvider` + `AssignableTypeFilter(BatchStep)` 로 `kr.co.cudo.authoring` 전수 스캔(`:55-69`), 스캔 결과 **≥6 단언**(`:85`, 공허 단언 방지), `execute` **미선언 시 `AssertionError`**(`:71-78`), `propagation`·`value` 까지 단언 | 실행 결과는 `test-baseline.md`(backend 4,367 / 실패 0)로 대조 — **본 검증에서 테스트 실행 안 함** |
| TC-BATCH-190 | `bumpLabelVersionIn` 에 `@Transactional` 미부착 | PASS | [정적] `LsDataSrcRepository:283-295` — 규약 주석 + `@Modifying`+`@Query` 만, `@Transactional` 없음. 동일 규약 `bumpLabelVersionByRawSn:305-308` | |

---

## B-16 결과표 (오토라벨 일괄저장) — 6건

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-191 | 프레임 단위 2단 saveAll(라벨→AI메타) | PASS | [정적] `AutoLabelBatchPersister:61-85` = `lblRepository.saveAll` 1회(`:72`) + `aiInfoRepository.saveAll` 1회(`:83`). 호출부 `YoloAutolabelStep:328` · `Sam2SegmentStep:207`. **`lblRepository.save(` / `aiInfoRepository.save(` 개별 호출 grep 0건**(Yolo·Sam2·YoloLabelPersister·TrackInterpolation 전부) | [실동작] 126 오토라벨 19건 정상 적재 |
| TC-BATCH-192 | AI 메타는 저장된 라벨의 자기 lblSn/srcSn 사용 | PASS | [정적] `:78-82` — `saved.get(i)` 에서 `getLblSn()`/`getSrcSn()` 직접 읽어 `LsDataLblAiInfo.create` | 인덱스 대응 계약 주석 `:30-34` |
| TC-BATCH-193 | size 불일치 즉시 실패 | PASS | [정적] `:73-76` `saved == null \|\| saved.size() != pending.size()` → `INTERNAL_ERROR("자동 라벨 일괄 저장 결과 개수 불일치")` | 조용한 오염 대신 실패 |
| TC-BATCH-194 | pending 비면 리포지토리 미호출 | PASS | [정적] `:65-67` early return 0 | |
| TC-BATCH-195 | 신뢰도는 PendingLabel 로 별도 전달 | PASS | [정적] `record PendingLabel(LsDataLbl label, BigDecimal score)` `:47`, AI 메타에 `pending.get(i).score()`(원본) 사용 `:81` — 엔티티 `clampScore` 보정값 되읽기 아님 | |
| TC-BATCH-196 | IDENTITY PK 유지 — 실제 JDBC 배치는 여전히 비활성 | PASS | [정적] `LsDataLbl:55` · `LsDataLblAiInfo:30` 모두 `@GeneratedValue(strategy = GenerationType.IDENTITY)` → `hibernate.jdbc.batch_size` 무효. 클래스 주석 `:22-28` 이 이 한계를 명시 | B-ISSUE-42 **부분 해소** 상태를 기대값으로 고정한 케이스 — 현상 일치 |

---

## B-17 결과표 (LS_DATA_RAW 참조 무결성 FK · V146) — 8건

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-200 | FK 27개 생성(CASCADE 25 / SET NULL 2) | PASS | [실동작] `pg_constraint` 조회 = **28행**(V146 `child_specs` 27 + `FK_LS_EVNT_ANNO_RAW`). 27건 내역: 배치 7 · 작업/검수 9 · 버전/증강/데이터셋 5 · 관제 3 · 포털 1 · 원장/세션 2 = 25 CASCADE + 2 SET NULL. 명명 규칙 `fk_{테이블}_raw` 전건 일치. `flyway_schema_history` V146 `success=t` | 위 전수표 참조 |
| TC-BATCH-201 | 부모 삭제 시 자식 CASCADE | **PARTIAL** | [실동작] 직접 자식은 정상 CASCADE(`ls_data_src` 3→0, `ls_raw_data_status`·`ls_dataset_export` 0). 그러나 **고아 라벨 64 → 86 (+22)** — `ls_data_lbl.src_sn → ls_data_src` FK 부재. "고아가 구조적으로 불가능" 미성립 | **B-ISSUE-101** |
| TC-BATCH-202 | 원장·세션은 SET NULL | PASS | [실동작] `fk_ls_tus_upload_raw`·`fk_ls_webhook_idempotency_raw` = `confdeltype='n'`(SET NULL). [정적] V146:73-74, 근거 주석 `:147-150` | 행 보존 확인 |
| TC-BATCH-203 | ★뷰 공급 7테이블 고아 시 중단 | PASS | [정적] `view_feed_tables` 7종(`:77-85` = `ls_dataset_video_meta`·`ls_raw_data_status`·`ls_dataset_export`·`ls_deident_proc_log`·`ls_data_src`·`ls_data_meta`·`ls_data_meta_review`), `RAISE EXCEPTION`(`:112-117`)이 **삭제 전 1패스**에서 발생. [테스트] `LsDataRawOrphanCleanupIT:데이터마트_뷰_공급_테이블에_고아가_있으면_자동삭제하지_않고_중단한다` | 현재 DB 고아 0건이라 분기 미발현 |
| TC-BATCH-204 | 한 테이블 고아 1000건 초과 시 중단 | PASS | [정적] `max_orphans CONSTANT BIGINT := 1000`(`:87`), `IF cnt > max_orphans THEN RAISE EXCEPTION`(`:107-110`) — **뷰 공급 검사보다 앞**에서 평가 | 테스트 미커버(해당 IT 2건은 뷰 공급 분기만) |
| TC-BATCH-205 | MNG_* · ORGNL_RAW_SN 제외 | PASS | [실동작] `mng_clip_schedule_que.raw_sn` FK 없음, `ls_data_raw.orgnl_raw_sn`·`ls_dataset_video_meta.orgnl_raw_sn` FK 없음(전수 조회 확인). [정적] V146:17-26 제외 근거 | 관제 선승인 경계 준수 |
| TC-BATCH-206 | 3패스 구조(실태조사 → 고아 정리 → FK 생성) | PASS | [정적] 1패스 `:98-119`(`RAISE NOTICE 'V146 고아 발견'` + 2종 EXCEPTION) → 2패스 `:126-143`(SET NULL 은 UPDATE, 나머지 DELETE + `RAISE NOTICE`) → 3패스 `:152-162`(멱등 ADD CONSTRAINT). [실동작] 27 spec 전건 고아 재조사 결과 **TOTAL ORPHANS=0** | |
| TC-BATCH-207 | 멱등 — 재실행 시 기존 FK 재생성 안 함 | PASS | [실동작] 3패스 로직(`NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname=fk)`)을 트랜잭션 안에서 재실행 → `recreated=0` 후 ROLLBACK, 오류 0건 | |

---

## 근거 드리프트

카탈로그 최신화 커밋 `119ea148`(07-30) **이후**에 `862ca6d8`(외부연동 제출 3곳 논블로킹화)·`46f47cee` 등이 머지되어 `KpstDeidentService` 가 약 150줄 늘었다. B-13 의 `file:line` 은 그 이전 스냅샷 기준이라 **대부분 어긋난다**(동작 판정에는 영향 없음 — 코드는 Grep 으로 재추적했다).

**명확한 드리프트 29건** (5줄 이상 어긋나거나 다른 구문을 가리키는 것만 계수)

| TC | 카탈로그 표기 | 실제 위치 |
|---|---|---|
| TC-DEID-060 | KpstDeidentService.java:218-278 | `:247-306`(submit) |
| TC-DEID-061 | :235-241 | `:318-323`(parent null) |
| TC-DEID-062 | :279-283 | 동기분 `:294-303` / 비동기 실패는 `KpstSubmitOutcomeRecorder` |
| TC-DEID-063 | :342-390 | `:488-527`(cleanExportDir) |
| TC-DEID-064 | :438-490 | `:600-664` |
| TC-DEID-065 | :425-435 | `:587-598` |
| TC-DEID-067 | :395-399 | `:541-561` |
| TC-DEID-068 | :401-415 | `:562-576` |
| TC-DEID-069 | :445-462 | `:611-625` |
| TC-DEID-070 | :464-486 | `:626-648` |
| TC-DEID-071 | :505-506 | `:666-668` |
| TC-DEID-072 | :636-655,693-720 | `:764-791`(downloadResult), `:812-822`(toMaskName) |
| TC-DEID-073 | :657-690 | `:833-857` |
| TC-DEID-074 | :693-720 | `:869-890` |
| TC-DEID-079 | :722-746 · KpstDeidentTxService:116-120 | `:898-911` · TxService `:269-273` |
| TC-DEID-081 | :225,300-320 | `:286` · `:446-466` |
| TC-DEID-082 | :302-305 | `:447-451` |
| TC-DEID-083 | :516-518 | `:692-694` (DeidentArtifactIntegrity 43-108 → `:47-110`, 경미) |
| TC-DEID-084 | :464-486,531-556 | `:626-633`, `:707-730` |
| TC-DEID-088 | KpstDeidentTxService:78-95 | `:195-223` |
| TC-DEID-089 | KpstDeidentTxService:78-95 | `:206-223` |
| TC-DEID-091 | :621-634 | `:797-802` |
| TC-DEID-092 | :601-616 | `:782-786` |
| TC-DEID-093 | DeidentFrameAttacher.java:73-93 | `:99`(호출) · `:152-162`(isUsable) |
| TC-BATCH-179 | DeidentifyHealthIndicator.java:14-118 | 파일 전체가 **114줄** — 클래스 `:41-113`, health() `:75-113` |
| TC-BATCH-183 | VlmTimeseriesStep.java:128-135 | `:219-227`(execute) |
| TC-BATCH-186 | VlmTimeseriesStep.java:150-166 | `:236-257` |
| TC-BATCH-206 | V146...sql:96-145 | `:95-162` |
| TC-BATCH-207 | V146...sql:118-145 | `:152-162` |

**정확했던 근거(참고)**: TC-DEID-085/086/087/090(`KpstDeidentPollJob`·`LsDeidentProcLogRepository` 전건 일치), TC-DEID-075~078, TC-BATCH-171·174·175·176·189·190·191~196·200·201·202·204·205, B-15 의 YOLO/SAM2/보간/FRAME_EXTRACT 경계 라인.
**경미 오차(1~4줄, 미계수)**: TC-DEID-066(92-95→94-98) · TC-DEID-080(67→69) · TC-DEID-086(84-89→86-88) · TC-BATCH-170(31-38→30-37) · TC-BATCH-172(22-34→21-35) · TC-BATCH-178(32-47→35-51) · TC-BATCH-187(232-241→236-241) · TC-BATCH-202(71-73→73-74) · TC-BATCH-203(76-84,111-114→77-85,112-117).

---

## 이슈 상세

### [B-ISSUE-101] TC-BATCH-201 — `ls_data_src` 를 참조하는 손자 테이블에 FK 가 0건이라 부모 영상 삭제 시 라벨이 고아로 남는다

- **심각도**: HIGH (데이터 정합 — 조용히 누적되고 집계·마이그레이션·통계를 틀리게 만든다)
- **기대 동작(기대효과)**: TC-BATCH-201 — *"자식 행이 함께 삭제되어 **고아가 구조적으로 불가능**해진다."* V146 의 선언 목적(`V146__add_ls_data_raw_child_fk.sql:5-7`)도 *"영상 원본 행이 사라져도 자식(마킹·프레임·상태·비식별로그·작업배정 등)이 고아로 잔존했다 … 고아는 조용히 집계·뷰·export 를 틀리게 만드는 유형"* 이다.
- **현재 동작(이슈 내용)**: V146 은 **`ls_data_raw` 를 직접 참조하는 27개 테이블만** 다룬다. 그런데 라벨 도메인의 실제 부모는 `ls_data_src`(프레임)이고, **`ls_data_src` 를 참조하는 FK 는 DB 전체에 0건**이다.

  ```sql
  -- 0행
  SELECT cl.relname, con.conname FROM pg_constraint con
    JOIN pg_class rf ON rf.oid=con.confrelid JOIN pg_class cl ON cl.oid=con.conrelid
   WHERE con.contype='f' AND rf.relname='ls_data_src';
  ```

  `src_sn`/`data_src_sn` 컬럼을 보유하고도 FK 가 없는 테이블 **14종**: `ls_data_lbl` · `ls_data_lbl_ai_info` · `ls_data_lbl_hstry` · `ls_label_version` · `ls_data_aug` · `ls_data_aug_job_file` · `ls_data_aug_rvw` · `ls_data_issue` · `ls_data_meta_review` · `ls_auth_work_lock` · `ls_batch_proc_log` · `ls_data_src_hstry` · `ls_data_src`(자기참조).

  따라서 `DELETE FROM ls_data_raw` → `ls_data_src` 는 CASCADE 로 사라지지만 **그 프레임에 달린 라벨은 그대로 남는다.**
- **재현/확인 경로** (실행 완료 — 트랜잭션 ROLLBACK 으로 데이터 무변경 확인):

  ```sql
  BEGIN;
    SELECT count(*) FROM ls_data_lbl l
      WHERE NOT EXISTS (SELECT 1 FROM ls_data_src s WHERE s.src_sn=l.src_sn);   -- 64
    DELETE FROM ls_data_raw WHERE raw_sn=126;                                    -- DELETE 1
    SELECT count(*) FROM ls_data_src WHERE raw_sn=126;                           -- 0  (CASCADE 정상)
    SELECT count(*) FROM ls_data_lbl l
      WHERE NOT EXISTS (SELECT 1 FROM ls_data_src s WHERE s.src_sn=l.src_sn);   -- 86 (+22 고아)
  ROLLBACK;
  ```

  현행 DB 의 고아 라벨 **64행**은 `PIPE-ISSUE-01`(`backend/src/main/resources/db/seed/dev-seed.sql:52` 의 `DELETE FROM LS_DATA_RAW WHERE VMS_CLIP_ID LIKE 'DEV-CLIP-%'`)이 부팅마다 남긴 실물이며, V146 적용 이후에도 **줄지 않았다**(V146 의 고아 정리는 `ls_data_raw` 직접 자식만 스캔한다 — 27 spec 재조사 결과 `TOTAL ORPHANS=0`).
- **영향**:
  - 라벨 통계·품질검사·버전 스냅샷 집계가 삭제된 영상의 라벨을 계속 센다.
  - `LBL_SN` 을 참조하는 12+ 모듈(증강 라벨맵·해상도 파생·포털·export 해시·버전 롤백의 LBL_SN 보존 복원)이 존재하지 않는 프레임을 가리키는 행을 만날 수 있다.
  - 장차 `ls_data_src` 에 FK 를 추가하려 할 때 고아가 누적된 만큼 마이그레이션이 중단(V146 과 같은 fail-closed)되어 배포가 막힌다 — 지금 64행이 매 부팅 증가한다.
  - CWE 해당 없음(무결성/데이터 품질). 보안 영향은 없다.
- **수정 방향(제안)**: ① V146 과 같은 3패스 구조로 `ls_data_src` 자식 FK(`ON DELETE CASCADE`) 마이그레이션을 별건 신설하되, 뷰 공급 테이블(`ls_data_lbl_hstry` → `V_COMPLETED_LABEL_CHANGE`, `ls_data_meta_review` → `V_COMPLETED_META`)은 V146 과 동일하게 **고아 발견 시 중단**. ② 선행 조건으로 `PIPE-ISSUE-01`(부팅 시드 전삭제)을 먼저 없애야 고아가 다시 쌓이지 않는다. ⚠ **구현하지 않는다.**

### [B-ISSUE-102] TC-DEID-061 — 위탁 사전조건 위반(CWE-22 경로 가드 포함)이 `INVALID_INPUT`(400) 대신 `EXTERNAL_API_ERROR`(502) 로 마스킹된다

- **심각도**: LOW (오류 분류 정확도 — 실경로 도달 가능성이 낮다)
- **기대 동작(기대효과)**: TC-DEID-061 — 원본 경로에 부모 디렉터리가 없으면 `INVALID_INPUT`. `ErrorCode.INVALID_INPUT` = `HttpStatus.BAD_REQUEST`(`ErrorCode.java:6`). 입력 문제이므로 400 이어야 하고, 외부 API 장애(502)와 구분돼야 운영이 원인을 가른다.
- **현재 동작(이슈 내용)**: 가드 자체는 존재하나 호출측이 **모든 `RuntimeException` 을 재포장**한다.

  ```java
  // KpstDeidentService.java:291-303
  try {
      projectReq = buildProjectRequest(raw, rawSn);          // 내부에서 INVALID_INPUT / INTERNAL_ERROR 를 던짐
  } catch (RuntimeException e) {
      txService.failSubmit(procLogSn, rawSn, SUBMIT_FAILED_CODE, e.getClass().getSimpleName());
      log.error("[KpstDeid] submit prepare failed rawSn={} errType={}", rawSn, e.getClass().getSimpleName());
      throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "비식별 위탁 실패", e);   // ← 502 로 덮임
  }
  ```

  `buildProjectRequest` 안의 `INVALID_INPUT` 3종(빈 경로 `:315-317`, 부모 없음 `:320-323`, 디렉터리 생성 실패 `INTERNAL_ERROR` `:333-336`)이 전부 502 로 나온다. 대비되게 **원본 실재 가드**(TC-DEID-081)는 `try` **이전**(`:286`)에 있어 `INVALID_INPUT` 이 그대로 보존된다 — 같은 성격의 두 가드가 서로 다른 코드로 나가는 비대칭이다.
- **재현/확인 경로**: `ls_data_raw.raw_file_path_nm` 을 부모 없는 상대명(예: `video.mp4`)으로 두고 위탁. 단 `verifySourceOrFail` 이 먼저 파일 실재를 보므로, 이 분기에 도달하려면 프로세스 CWD 에 같은 이름의 파일이 실재해야 한다 → **실경로 도달 가능성 낮음**(그래서 LOW).
- **영향**: 운영 알림/대시보드에서 "입력 오류"가 "외부 비식별 서버 장애"로 분류된다. 또한 사전조건 실패인데 `ERR_CD=KPST_SUBMIT_FAILED`(외부 호출 실패 코드)로 원장에 남아 `KPST_SOURCE_MISSING` 과 달리 원인 식별이 안 된다. 보안 영향 없음(경로 원문은 노출되지 않는다).
- **수정 방향(제안)**: `catch (CustomException ce)` 를 분리해 사전조건 계열은 원래 `ErrorCode` 로 재전파하고(원장 코드도 `KPST_PREPARE_FAILED` 등으로 분리), 그 외만 `EXTERNAL_API_ERROR` 로 감싼다. ⚠ **구현하지 않는다.**

### [B-ISSUE-103] TC-DEID-062 — 논블로킹 제출 전환으로 `createProject` 실패의 **동기 예외 전파가 사라졌다**(케이스 기대값 무효)

- **심각도**: LOW (정보 — 코드가 의도적 개선, 카탈로그가 낡음)
- **기대 동작(기대효과)**: TC-DEID-062 — `createProject` 예외 시 `procLog.fail` + `markDeidentified("F")` + **예외 전파**(클래스명만).
- **현재 동작(이슈 내용)**: `862ca6d8`(카탈로그 최신화 `119ea148` **이후** 머지) 이후 제출은 구독만 하고 즉시 반환한다.

  ```java
  // KpstDeidentService.java:421-431 — subscribeSubmit
  kpstClient.createProject(projectReq)
      .subscribe(resp -> ... outcomeRecorder.onAccepted(...),
                 err  -> ... outcomeRecorder.onSubmitFailed(rawSn, procLogSn, err));
  ```

  외부 호출 실패는 전용 풀(`kpstSubmitScheduler`)에서 `KpstSubmitOutcomeRecorder.onSubmitFailed` → `KpstDeidentTxService.failSubmit`(`:163-184`) 로 처리되어 **procLog FAILED + `DE_IDNTF_YN='F'` + (REDEIDENT면) 작업락 해제**가 커밋된다. 즉 *부수효과 2종은 충족*하고 **동기 예외 전파만 성립하지 않는다**. 동기 `EXTERNAL_API_ERROR` 는 제출 **이전** 사전조건 실패에만 남는다(`:302`).
  아무 신호도 오지 않는 경우(노드 사망 등)는 폴러가 ACK 유예 만료로 `ACK_MISSING_CODE` 회수한다(`:557-560`).
- **재현/확인 경로**: `KPST_DEID_BASE_URL` 을 닫힌 포트로 두고 적재 → 호출 응답은 성공(202/201)이고, 수 초 뒤 `ls_deident_proc_log.err_cd='KPST_SUBMIT_FAILED'` + `ls_data_raw.de_ident_yn='F'` 로 관측된다. 관련 테스트: `KpstSubmitOutcomeRecorderTest` · `KpstSubmitAsyncCommitIT` · `KpstSubmitSignalDispatchGuardTest`.
- **영향**: 없음(설계 의도 — 메모리 `external-integrations-must-be-async` 정책 준수). 다만 카탈로그 기대값을 고치지 않으면 다음 회차에서 같은 PARTIAL 이 반복된다.
- **수정 방향(제안)**: TC-DEID-062 의 기대결과를 "비동기 실패 신호 → `failSubmit`(procLog FAILED + `'F'` + REDEIDENT 락 해제) 커밋, **동기 예외 없음**"으로 개정하고, 동기 전파 케이스는 `TC-DEID-061`(사전조건) 쪽으로 분리. ⚠ **구현하지 않는다(문서 개정 제안).**

### [B-ISSUE-104] TC-DEID-067 — `prjId == null` 경로가 `markTimeoutIfExpired` 대신 **ACK 대기 유예 + 회수**로 대체되었다(케이스 기대값 무효)

- **심각도**: LOW (정보 — 코드가 의도적 개선, 카탈로그가 낡음)
- **기대 동작(기대효과)**: TC-DEID-067 — `prjId` 가 null 이면 `markTimeoutIfExpired` 만 수행하고 외부는 호출하지 않는다.
- **현재 동작(이슈 내용)**:

  ```java
  // KpstDeidentService.java:541-561 (pollOne)
  if (prjId == null) {
      if (withinSubmitAckGrace(procLog)) {          // REQ_DT + kpst.deid.submit-ack-grace-sec(기본 180)
          log.debug("[KpstDeid] skip poll — awaiting submit ack rawSn={}", rawSn);
          return;                                    // ← 외부 호출 0건, 시도 카운터 미소모
      }
      boolean reclaimed = txService.failSubmit(procLogSn, rawSn, ACK_MISSING_CODE, "submit ack not received");
      log.warn("[KpstDeid] submit ack missing — reclaimed rawSn={} applied={}", rawSn, reclaimed);
      return;
  }
  ```

  코드 주석이 사유를 명시한다 — *"구 코드처럼 `markTimeoutIfExpired` 를 부르면 시도 카운터/경과 타임아웃 예산만 헛되이 소모"*. 종결은 `claimSubmitFailure`(WAITING + prjId null) 조건부 UPDATE 라 **지각 ACK 를 강등하지 않는다**(`LsDeidentProcLogRepository:168-184`).
  → 케이스의 "외부 미호출"은 충족하나 "markTimeoutIfExpired"는 성립하지 않는다.
- **재현/확인 경로**: `ls_deident_proc_log` 에 `POLL_STTS_CD='WAITING'` + `DE_IDNTF_PJT_ID IS NULL` 행을 두고 폴링 틱 관측 → 유예 내에는 `poll_atmpt_cnt` 가 증가하지 않고 mock 인바운드도 없다. 본 검증에서 만든 합성 행(procLogSn=141)도 유예 구간이라 외부 호출 0건이었다(검증 후 삭제 완료).
- **영향**: 없음(개선). 유예 기본 180초는 클라이언트 타임아웃 45s × 재시도 3회 + 백오프 최악값(≈138s)을 덮도록 산정돼 있다(`:202-210`).
- **수정 방향(제안)**: TC-DEID-067 기대결과를 "ACK 유예 내 skip(외부 호출 0·카운터 미소모) / 유예 초과 시 `failSubmit(ACK_MISSING)` 회수"로 개정. ⚠ **구현하지 않는다(문서 개정 제안).**

---

## 부기 — 검증 중 관측된 참고 사항 (케이스 판정 대상 아님)

1. **`KpstDeidentService:145-147` 주석 낡음** — *"`submit(LsDataRaw, boolean)` 자체가 REQUIRES_NEW 라"* 라고 적혀 있으나 현재 `submit` 에는 `@Transactional` 이 없다. 실패 흔적 커밋은 `batchTransitionService.recordDeidentFailure` / `txService.failSubmit`(둘 다 cross-bean REQUIRES_NEW)가 담당하므로 **동작 결함은 없다**. 문서 정합만 필요.
2. **검증 환경은 단일 노드**라 `qrtz_scheduler_state` 0행 / `qrtz_fired_triggers.instance_name='NON_CLUSTERED'` 다. UNCERTAINTIES #8 확정대로 **정상**이며 결함으로 보고하지 않았다(1차 B-ISSUE-81 은 stg/prd 기본값 + 부트 가드로 해소).
3. **검증 중 사용한 DB 개입 2건은 모두 원복 완료**: ①`rawSn=126` 삭제 실증(BEGIN…ROLLBACK, 원복 확인 `raw=1/src=3`) ②원자 클레임 실증용 합성 `ls_deident_proc_log` 1행(procLogSn=141, `data_raw_sn=144`) 생성 후 `DELETE` (잔존 0 확인). 그 외 `UPDATE`/`INSERT` 없음.
4. **self-fill 없음** — KPST 비식별은 mock 실왕복이 backend 컨테이너 IP(`172.18.0.5`)에서 관측됐고(`POST /project` → `GET /retrieve_progress` → `production completed`), 산출 파일명이 우리가 조합할 수 없는 `{원본stem}-mask{ext}` 이며 디스크에 실물(34MB)이 있다. 헬스체크도 mock 루트(`GET /`)를 실제로 쳤다.
