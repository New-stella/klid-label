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
- **dev 경로 수렴**: dev/autolabel-test 의 별도 경량 경로(AutolabelTestService)는 폐기되고, `DevPipelineRunner` 가 선두 비식별 → 합성 마킹 → 프로덕션 `BatchOrchestrator` 를 순차 실행한다. 4단계 토글(FRAME/DEIDENT/YOLO/SAM2)은 파이프라인 조건부 step 으로 동작한다. 제거된 엔드포인트: `POST /v1/dev/autolabel/run`·`run-full`.

## 7.3 단계별 Step

| 시퀀스 | 단계 | Step 클래스 | 동작 |
|--------|------|------------|------|
| pre-marking | 비식별화 | `DeidentifyStep` | **전체 영상 무조건**(ANONY 포함) 외부 비식별 호출, `LS_DEIDENT_PROC_LOG` 저장, 성공 시 `MARKING_READY` 전이 → [08](08-deidentification.md) |
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
| `BatchRetryQueue` + `BatchRetryQuartzJob` | 실패 영상 재시도 큐 |
| `BootstrapSchedulerJob` / `QuartzConfig` | 부트스트랩·설정 |
| `LabelingBatchQueueService` | `MNG_CLIP_SCHEDULE_QUE` 관리 |
| `AsyncBatchRunner` | post-marking 잔여 배치 비동기 실행 |
| `AsyncDeidentifyRunner` | 적재 직후 선두 비식별 @Async 실행(성공 시 `MARKING_READY` 전이) |
| `IngestDeidentifyBridge` | `VideoIngestedEvent`(AFTER_COMMIT) → 선두 비식별 트리거 |
| `DevPipelineRunner` | dev 경로 단일 파이프라인 수렴(선두 비식별 → 합성 마킹 → BatchOrchestrator) |

- **Quartz PostgreSQL JobStore**(`QRTZ_*`, `PostgreSQLDelegate`, BYTEA), **단일 인스턴스**(클러스터 미적용)
- 처리율 목표 **≥ 1건/분** (NFR-001)

## 7.5 재처리 정책

- 배치 실패 시 재시도 큐 + 최대 재시도 횟수 + 실패 알림
- 비식별 API 실패 시 `DE_IDENT_YN='F'` 마킹 + 재시도 큐, **원본 절대 삭제 금지**
- ai-server GPU 자원 모니터링 포인트 확보

## 7.6 관련 데이터 (DB)

`LS_BATCH_PROC_LOG`(단계별 로그·`STAGE_CD`·`RES_PAYLOAD_CN`), `LS_DATA_RAW.dataSttsCd`(PENDING→MARKING_READY→COMPLETED), `LS_DEIDENT_PROC_LOG`(비식별 경로 self-lookup 출처), `LS_RAW_DATA_STATUS`. → [18](18-database.md).
