# 06. 마킹

> 출처: CLAUDE.md(마킹 단계·마킹 화면), R2 KLID-AT-SS-002, 코드(`marking/`)
> 관련: [07 배치 파이프라인](07-batch-pipeline.md) · [09 VLM 시계열](09-vlm-timeseries.md)

화면: `KLID-AT-SC-006`(마킹 `/marking/:rawSn`). 코드: `marking/`(9 파일) — v2 신설 기능(v1 없음).

## 6.1 마킹이란

영상에서 **이벤트(관심 시점)를 자동/수동으로 표시**하는 단계. 마킹 결과는 외부 VLM 시계열 콜백 트리거가 되고, FFmpeg 프레임 추출 위치의 기준이 된다.

- 마킹 결과 = **이벤트명 + 영상 경로 + marks 배열** (`LS_MARKING.MARK_CN` JSON)
- 마킹 대상: **비식별 영상** (구현됨, NFR-001 v1.5). 적재 직후 선두 비식별이 완료(`LsDataRaw.dataSttsCd=MARKING_READY`, `deIdntfYn='Y'`)된 영상만 마킹 진입 → [08](08-deidentification.md)

## 6.2 자동 / 수동 모드

영상별 모드 설정 (`LS_MARKING.MARK_MODE_CD`):

| 모드 | 동작 |
|------|------|
| **자동(AUTO)** | **프레임 간격**(`FRME_INTV_NOCS`, intervalFrames) 기반 자동 마킹 |
| **수동(MANUAL)** | 작업자가 키보드 단축키로 이벤트 시점 마킹 |

## 6.3 마킹 화면

- **비식별 영상** 스트리밍 재생 (`GET /v1/videos/{rawSn}/stream`, HTTP Range — 항상 비식별 영상 서빙, 비식별 미완료 시 NOT_FOUND 로 원본 노출 차단) → [05](05-video-management.md#53-영상-스트리밍)
- **배속 설정 0.25x ~ 4x**
- **키보드 단축키**: `Space`(마킹), `Del`(삭제), `Enter`(완료)
- 비식별 누락 신고: 라벨링 단계(srcSn 기준)는 구현됨, **마킹 단계(rawSn 기준 `POST /v1/videos/{rawSn}/deident-report`)는 planned(미구현)** → [08](08-deidentification.md#84-누락-신고-rq-sfr-09-03-uc-016)

## 6.4 마킹 완료 → 배치 자동 시작

```
마킹 완료
  → MarkingCompletedEvent 발행
  → MarkingBatchBridge (AFTER_COMMIT)
  → deid 가드(deIdntfYn='Y' 검증) → @Async post-marking 배치 자동 시작
```

- **deid 가드**: 비식별 미완료(`deIdntfYn != 'Y'`) 영상은 잔여 배치를 트리거하지 않는다(선두 비식별이 필수 선행 조건).
- 이후 배치는 비식별 단계를 제외한 post-marking 시퀀스(VLM→프레임추출→YOLO→SAM2→보간).

코드: `MarkingCompletedEvent`, `MarkingBatchBridge`. 이후 파이프라인 → [07](07-batch-pipeline.md).

## 6.5 관련 데이터 (DB)

`LS_MARKING` (V45) — `EVNT_NM`(이벤트명), `MARK_MODE_CD`(AUTO/MANUAL), `FRME_INTV_NOCS`(프레임 간격), `MARK_CN`(marks JSON), `STTS_CD`(상태). → [18](18-database.md).
