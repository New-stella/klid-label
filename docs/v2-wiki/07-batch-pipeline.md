# 07. 배치 파이프라인

> 출처: CLAUDE.md(배치 파이프라인·배치 성능), R1 NFR-001, R2 KLID-AT-SS-003, 코드(`batch/` 52 파일)
> 관련: [06 마킹](06-marking.md) · [08 비식별](08-deidentification.md) · [09 VLM](09-vlm-timeseries.md) · [11 AI 보조](11-ai-assisted.md)

## 7.1 파이프라인 순서

### 현재 코드 순서 (CLAUDE.md 기준)
```
⭐마킹(자동/수동) → VLM 시계열(콜백 비동기) → 비식별화(PRVC/PSDO만)
  → FFmpeg(마킹 위치 기반 원본+비식별 2벌 추출)
  → YOLO(원본만) → SAM2 → 트랙 보간
```

### 설계 타깃 순서 (R1 NFR-001 v1.5 — 비식별 선두 재배치)
```
비식별 → 마킹(비식별 영상) → VLM → FFmpeg(마킹위치) → YOLO/SAM2 → 트랙 보간
```

> 코드 `BatchStage` enum: `VLM_TIMESERIES → DEIDENTIFY → FRAME_EXTRACT → YOLO → SAM2 → INTERPOLATE → COMPLETED`. 비식별 선두 재배치는 planned.

## 7.2 오케스트레이션

- **트리거**: 마킹 완료 → `MarkingCompletedEvent` → `MarkingBatchBridge` → BATCH_QUEUED + @Async 시작. + Quartz 스케줄(1건/분).
- **`BatchOrchestrator.process()`**: 시작 시 상태 `→ PROCESSING`, 완료 `→ COMPLETED`, 실패 `→ FAILED`. `LsDataRaw.dataSttsCd`(배치 단계)와 `LsRawDataStatus.dataSttsCd`(작업 상태) 양쪽 갱신.

## 7.3 단계별 Step

| 단계 | Step 클래스 | 동작 |
|------|------------|------|
| VLM 시계열 | `VlmTimeseriesStep` | 외부 VLM 동기 호출(45s, Resilience4j), 결과 상세는 콜백 → [09](09-vlm-timeseries.md) |
| 비식별화 | `DeidentifyStep` | `PRVC/PSDO`만 외부 비식별 호출, `LS_DEIDENT_REPORT` 저장 → [08](08-deidentification.md) |
| 프레임 추출 | `FfmpegFrameExtractor` | 마킹 위치 기반 **원본+비식별 2벌** 추출 |
| YOLO | `YoloAutolabelStep` | **원본만** 객체 탐지, 프리셋 필터, track_id 부여 |
| SAM2 | `Sam2SegmentStep` | 세그멘테이션(polygon), BBOX/POLYGON_ONLY 분기 |
| 트랙 보간 | `TrackInterpolationStep` | CVAT 선형보간(`TrackInterpolator`) → [11](11-ai-assisted.md) |
| 완료 | `statusService.markCompleted` | COMPLETED 전이 |

> **오토라벨링은 원본 이미지에만 실행**. 라벨 좌표는 동일 해상도이므로 비식별본과 공유(별도 실행 없음).

## 7.4 큐 · 스케줄러 (Quartz)

| 컴포넌트 | 역할 |
|----------|------|
| `BatchQuartzJob` | 메인 배치 스케줄 |
| `BatchRetryQueue` + `BatchRetryQuartzJob` | 실패 영상 재시도 큐 |
| `BootstrapSchedulerJob` / `QuartzConfig` | 부트스트랩·설정 |
| `LabelingBatchQueueService` | `MNG_CLIP_SCHEDULE_QUE` 관리 |
| `AsyncBatchRunner` | 비동기 배치 실행 |

- **Quartz PostgreSQL JobStore**(`QRTZ_*`, `PostgreSQLDelegate`, BYTEA), **단일 인스턴스**(클러스터 미적용)
- 처리율 목표 **≥ 1건/분** (NFR-001)

## 7.5 재처리 정책

- 배치 실패 시 재시도 큐 + 최대 재시도 횟수 + 실패 알림
- 비식별 API 실패 시 `DE_IDNTF_YN='F'` 마킹 + 재시도 큐, **원본 절대 삭제 금지**
- ai-server GPU 자원 모니터링 포인트 확보

## 7.6 관련 데이터 (DB)

`LS_BATCH_PROC_LOG`(단계별 로그·`STAGE_CD`·`RES_PAYLOAD_CN`), `LS_DATA_RAW.dataSttsCd`, `LS_RAW_DATA_STATUS`. → [18](18-database.md).
