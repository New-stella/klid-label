# 07. 배치 파이프라인

> 출처: CLAUDE.md(배치 파이프라인·배치 성능), R1 NFR-001, R2 KLID-AT-SS-003, 코드(`batch/` 52 파일)
> 관련: [06 마킹](06-marking.md) · [08 비식별](08-deidentification.md) · [09 VLM](09-vlm-timeseries.md) · [11 AI 보조](11-ai-assisted.md)

## 7.1 파이프라인 순서 (구현됨)

비식별 선두 재배치(R1 NFR-001 v1.5)가 **구현 완료**되었다. 단계 순서는 **선언적 파이프라인**으로 관리되며 두 시퀀스로 나뉜다.

```
[pre-marking 선두]  비식별화(전체 영상 무조건, 적재 직후 자동)
[마킹]              마킹(비식별 영상 대상, 자동/수동)
[post-marking 잔여] VLM 시계열(콜백 비동기)
                     → FFmpeg(마킹 위치 기반 원본+비식별 2벌 추출)
                     → YOLO(원본만) → SAM2 → 트랙 보간
```

- **순서 관리 단일 지점**: `batch/pipeline/BatchPipelineConfig` 의 `List.of(...)` 한 줄에서 단계 순서를 정의한다 — 재배치 시 오케스트레이터/스텝 구현은 손대지 않는다.
  - `preMarkingPipeline` = `[DEIDENTIFY]`
  - `postMarkingPipeline` = `[MARKING, VLM, FRAME_EXTRACT, YOLO, SAM2, INTERPOLATE]`
- 선언적 파이프라인 구성: `batch/pipeline/{BatchStep, BatchContext, BatchPipeline, BatchPipelineConfig}`. 각 `BatchStep` 은 `isEnabled(ctx)` 로 조건부 실행을 지원한다(dev 토글).

> 구 순서(`마킹(원본) → VLM → 비식별(PRVC/PSDO만) → 프레임추출 → 오토라벨링`)와 post-marking 단계 내 비식별은 **폐지**되었다.

## 7.2 오케스트레이션

- **선두 비식별 트리거(자동)**: 영상 적재(관리화면 TUS 업로드 + dev 경로) 직후 → `VideoIngestedEvent` → `IngestDeidentifyBridge`(`@TransactionalEventListener` AFTER_COMMIT) → `AsyncDeidentifyRunner`(@Async) → `DeidentifyStep.run` → 성공 시 `LsDataRaw.dataSttsCd = MARKING_READY` 전이(마킹 진입 허용). 비식별은 **ANONY 포함 전체 영상 무조건** 실행(게이팅 폐지).
- **잔여 배치 트리거**: 마킹 완료 → `MarkingCompletedEvent` → `MarkingBatchBridge`(AFTER_COMMIT) → `AsyncBatchRunner` → `BatchOrchestrator` @Async 시작. **deid 가드** — 마킹완료 시 `deIdntfYn='Y'` 가 아니면 잔여 배치를 트리거하지 않는다. + Quartz 스케줄(1건/분).
- **`BatchOrchestrator.process()`**: 시작 시 작업 상태 `→ PROCESSING`, 완료 시 작업 상태 `→ ASSIGNED 복귀`, 실패 `→ FAILED`. **두 테이블 책임 분리** — `LsDataRaw.dataSttsCd`(배치 단계: PENDING→MARKING_READY→COMPLETED)는 완료 시 `COMPLETED` 로 마감, `LsRawDataStatus.dataSttsCd`(작업/검수 워크플로우 상태)는 ASSIGNED 로 복귀시켜 라벨링/검수 플로우가 이어진다. 작업 상태 `COMPLETED` 는 **검수 승인(`ReviewService.approve`) 시점에만** 도달하는 종결 상태이므로 배치 완료가 점프시키지 않는다(점프 시 검수 제출 ASSIGNED→PENDING 차단).
- **dev 경로 = 운영 시나리오 1:1 고정 플로우**(2026-06-16 단순화): dev/autolabel-test 업로드는 `DevPipelineRunner.runAsync(rawSn)` 가 **선두 비식별(무조건, local mock) → `MARKING_READY` 까지만 수행하고 정지**한다. 단계 토글(FRAME/DEIDENT/YOLO/SAM2)·합성 마킹(createSyntheticMarking)·`manualMarking` 옵션·`DevPipelineRunner` 의 `BatchOrchestrator.process` 직접 호출은 **모두 제거**되었다. 이후 검수자가 마킹 화면에서 실제 마킹(자동/수동 모드)→완료하면 기존 `MarkingCompletedEvent → MarkingBatchBridge`(AFTER_COMMIT) 경로로만 잔여 배치가 진행된다. 비식별 실패 시 `MARKING_READY` 미전이(외부 수동 재비식별 정책) — 영상 목록에 그 상태가 노출된다. 제거된 엔드포인트: `POST /v1/dev/autolabel/run`·`run-full`. FE: 영상 업로드 화면(`/dev/autolabel-test`)은 체크박스 없이 업로드 후 "마킹 대기" 안내 + 마킹 화면 링크만 제공.

## 7.3 단계별 Step

| 시퀀스 | 단계 | Step 클래스 | 동작 |
|--------|------|------------|------|
| pre-marking | 비식별화 | `DeidentifyStep` | **전체 영상 무조건**(ANONY 포함) 외부 비식별 호출, `LS_DEIDENT_PROC_LOG` 저장, 성공 시 `MARKING_READY` 전이 (`kpst.deid.enabled=true` 시 **KPST 공유 마운트 폴링** — `DeidentifyStep` 위탁[project만, input_path=원본 디렉터리/export_path=우리 base, upload 없음] + `KpstDeidentPollJob`[Quartz] 완료감지 후 응답 `fileName` 으로 no-copy 회수 → `MARKING_READY`; local/dev 는 mock 복사) → [08](08-deidentification.md) / [22](22-deid-solution-api.md) |
| post-marking | 마킹 로드 | `MarkingLoadStep` | 마킹 결과(`LS_MARKING`)를 컨텍스트에 적재 |
| post-marking | VLM 시계열 | `VlmTimeseriesStep` | 외부 VLM 동기 호출(45s, Resilience4j), 결과 상세는 콜백 → [09](09-vlm-timeseries.md) |
| post-marking | 프레임 추출 | `FfmpegFrameExtractor` | 마킹 위치 기반 **원본+비식별 2벌** 추출. 비식별 경로는 `LsDeidentProcLog` 에서 self-lookup(호출자 인자 미전달) |
| post-marking | YOLO | `YoloAutolabelStep` | **원본만** 객체 탐지, 프리셋 필터, track_id 부여 |
| post-marking | SAM2 | `Sam2SegmentStep` | 세그멘테이션(polygon), BBOX/POLYGON_ONLY 분기 |
| post-marking | 트랙 보간 | `TrackInterpolationStep` | CVAT 선형보간(`TrackInterpolator`) → [11](11-ai-assisted.md) |
| 완료 | 완료 | `transitionService.markRawDataCompleted` | `LsDataRaw`(배치 단계) → COMPLETED, `LsRawDataStatus`(작업 상태) → ASSIGNED 복귀 |

> **오토라벨링은 원본 이미지에만 실행**. 라벨 좌표는 동일 해상도이므로 비식별본과 공유(별도 실행 없음).
> 비식별 단계는 **post-marking 시퀀스에서 제거**되어 적재 직후 선두(pre-marking)로 이동했다.

## 7.4 큐 · 스케줄러 (Quartz)

| 컴포넌트 | 역할 |
|----------|------|
| `BatchQuartzJob` | 메인 배치 스케줄 |
| `BatchRetryQueue` + `BatchRetryQuartzJob` | 실패 영상 재시도 대기 — **DB 영속(`LS_BAT_RTY_WTNG`, V116)**. 구 in-memory 큐는 실패 등록 노드 ≠ 재시도 발화 노드일 때(2노드 Active-Active) 재시도 유실 결함이 있어 DB 로 전환. 폴링은 조건부 원자 UPDATE(PENDING→RETRYING)로 동시 폴링 직렬화, 최초 등록 UK 경쟁은 `ON CONFLICT DO NOTHING`+FOR UPDATE 로 흡수 |
| `BatchReprocessController` + `BatchReprocessService` | 배치 재처리 API `POST /v1/videos/{rawSn}/batch/retry` (REVIEWER) — FAILED 고착 영상 수동 재기동. FAILED 아니면 409, WORKER 403 |
| `BootstrapSchedulerJob` / `QuartzConfig` | 부트스트랩·설정 |
| `LabelingBatchQueueService` | `MNG_CLIP_SCHEDULE_QUE` 관리 |
| `AsyncBatchRunner` | post-marking 잔여 배치 비동기 실행 |
| `AsyncDeidentifyRunner` | 적재 직후 선두 비식별 @Async 실행(성공 시 `MARKING_READY` 전이) |
| `IngestDeidentifyBridge` | `VideoIngestedEvent`(AFTER_COMMIT) → 선두 비식별 트리거 |
| `DevPipelineRunner` | dev 경로 선두 비식별(무조건) → `MARKING_READY` 정지 (잔여 배치는 마킹 완료로만 트리거) |
| `ControlTrainingVideoScanJob` + `TrainingVideoIngestService` | 관제 학습용 지정(`MNG_CLIP_MASTER.JOB_DMND_YN='Y'`) 클립 픽업 적재 — 아래 스캔 비용 규칙 적용 |

- **학습용 클립 스캔 비용 규칙 (B-ISSUE-04)** — 이 잡은 60초마다 **관제와 공유하는 DB(MNG_*)** 를 친다. 구 구현은 미적재 필터도 상한도 없어 매 tick 전량 SELECT 후 전량 skip 을 반복했다(실측 `scanned=3 ingested=0` 무한 반복).
  - 후보 조회에 `NOT EXISTS (LS_DATA_RAW WHERE VMS_CLIP_ID = CLIP_ID)` 를 걸어 **미적재 클립만** 가져온다(`LsDataRaw` 도 `@ControlRepo` 라 같은 EntityManager — 단일 SQL 상관 서브쿼리).
  - tick 당 처리 상한 **100건**(`TrainingVideoIngestService.INGEST_SCAN_LIMIT`, `Pageable`). 잔여분은 **다음 tick 이 이어서 처리**한다(의도된 이월 — `scan finished ... carriedOver=true` 로그로 관측). 정렬은 복합 PK 오름차순 고정이라 특정 클립이 굶지 않는다.
  - 이벤트리스트(`MNG_CLIP_EVNT_LST`)는 클립당 개별 조회 대신 후보 EVNT_ID **IN 조회 1회**로 배치화(구 구현은 후보 N 건에 매 tick 2N 쿼리).
  - **멱등 가드는 유지**한다 — 위 필터는 1차 필터일 뿐이고, 2노드 Active-Active 에서 조회~적재 사이 경합이 있으므로 `TrainingVideoIngestTx` 의 이중 멱등(사전 조회 skip + UK 위반 catch-skip)을 대체하지 않는다.

- **Quartz PostgreSQL JobStore**(`QRTZ_*`, `PostgreSQLDelegate`, BYTEA), **2노드 Active-Active + 클러스터링 적용**(`QRTZ_LOCKS` 행 락으로 동일 트리거를 1노드만 발화) → [02 §2.7](02-architecture.md)
  - 공통 기본값 `isClustered=${QUARTZ_CLUSTERED:false}`(단일 노드 기준)이고 **stg/prd 프로파일이 `true` 로 override** 한다. 그 두 환경에서 꺼져 있으면 **기동 거부**(`QuartzClusteringGuard` — 프로파일 allowlist + `ENV` 배포 표식 두 축). local/dev 는 단일 노드라 off 허용
  - 클러스터링은 **트리거 중복 발화**만 막는다 — 잡 내부에서 여러 노드가 같은 행을 집는 레이스는 각 잡의 **원자 클레임**(조건부 UPDATE)이 별도로 막는다(둘은 대체 관계가 아니다)
  - 락 행 시드 `V76`(`SCHED_NAME='KlidAuthoringScheduler'`) ↔ `org.quartz.scheduler.instanceName` 일치 필수. 노드 간 시계 동기(NTP) 전제
- 처리율 목표 **≥ 1건/분** (NFR-001)

## 7.5 재처리 정책

- 배치 실패 시 재시도 대기 + 최대 재시도 횟수 + 실패 알림. 재시도 대기는 **DB 영속(`LS_BAT_RTY_WTNG`)** — 최대 초과 시 `EXHAUSTED` 소진(삭제 아님, 이력 보존)
- 최대 재시도 초과로 FAILED 고착된 영상은 REVIEWER 가 `POST /v1/videos/{rawSn}/batch/retry` 로 수동 재기동(자동 재시도와 동일한 `BatchOrchestrator.process` 경로 재사용)
- 비식별 API 실패 시 `DE_IDENT_YN='F'` 마킹 + 재시도 큐, **원본 절대 삭제 금지**
- ai-server GPU 자원 모니터링 포인트 확보

## 7.6 관련 데이터 (DB)

`LS_BATCH_PROC_LOG`(단계별 로그·`STAGE_CD`·`RESP_PAYLOAD_CN`), `LS_DATA_RAW.dataSttsCd`(PENDING→MARKING_READY→COMPLETED), `LS_DEIDENT_PROC_LOG`(비식별 경로 self-lookup 출처), `LS_RAW_DATA_STATUS`. → [18](18-database.md).

> **비식별 완료 분기 (REQ_KND_CD)**: KPST 비식별 완료 처리는 `LS_DEIDENT_PROC_LOG.REQ_KND_CD`로 분기한다 — 기존 **BATCH**(신규 영상 파이프라인)는 `MARKING_READY` 전이(현행), **REDEIDENT**(검수완료 영상 재비식별)는 상태 전이 없이 비식별 프레임 attach만(APPROVED 유지). → [08 §8.6](08-deidentification.md).
