# Phase 4: 프레임 추출 변경 + 오토라벨 원본 전용 — 테스트 결과

> 테스트 결과: 전체 PASS (138개 테스트 클래스, 신규 10건 포함)

## 변경 요약

| 파일 | 변경 | 설명 |
|------|------|------|
| `FfmpegFrameExtractor.java` | 메서드 추가 | `extractByMarks()` 마킹 위치 기반 프레임 추출 |
| `YoloAutolabelStep.java` | 메서드 수정 | `resolveImagePath()` → 원본 프레임만 사용 |
| `Sam2SegmentStep.java` | 메서드 수정 | `resolveImagePath()` → 원본 프레임만 사용 |
| `BatchOrchestrator.java` | 로직 추가 | 마킹 유무에 따라 extractByMarks/extractBoth 분기 |

## 신규 테스트 결과

### FfmpegFrameExtractorTest (6건 추가)

| # | 테스트 | 결과 |
|---|--------|------|
| 1 | V2_마킹_위치_기반_프레임_추출_정확한_frameIndex | PASS |
| 2 | V2_마킹_기반_추출_비식별_영상_포함_2벌 | PASS |
| 3 | V2_마킹_비식별_영상_없으면_RAW_만_graceful | PASS |
| 4 | V2_마킹_빈_배열_시_INVALID_INPUT | PASS |
| 5 | V2_마킹_null_시_INVALID_INPUT | PASS |
| 6 | V2_마킹_기반_추출_manifest_jsonl_생성 | PASS |

### YoloAutolabelStepTest (1건 추가)

| # | 테스트 | 결과 |
|---|--------|------|
| 1 | V2_YOLO_원본_프레임만_실행_비식별_경로_존재해도_원본_사용 | PASS |

### Sam2SegmentStepTest (1건 추가)

| # | 테스트 | 결과 |
|---|--------|------|
| 1 | V2_SAM2_원본_프레임만_실행_비식별_경로_존재해도_원본_사용 | PASS |

### BatchOrchestratorMarkingTest (3건 추가)

| # | 테스트 | 결과 |
|---|--------|------|
| 1 | V2_마킹_있을때_extractByMarks_호출_extractBoth_미호출 | PASS |
| 2 | V2_마킹_없을때_기존_extractBoth_폴백_extractByMarks_미호출 | PASS |
| 3 | V2_마킹_marks_JSON이_extractByMarks에_MarkItem_리스트로_전달 | PASS |

## 기존 테스트 회귀 확인

| 테스트 클래스 | 결과 |
|-------------|------|
| FfmpegFrameExtractorTest (기존 14건) | PASS |
| YoloAutolabelStepTest (기존 19건) | PASS |
| Sam2SegmentStepTest (기존) | PASS |
| BatchOrchestratorTest (기존 17건) | PASS |
| BatchOrchestratorMarkingTest (기존 3건) | PASS |

## 수용 기준 달성

- [x] 마킹 위치의 프레임만 추출됨 (원본+비식별 2벌)
- [x] YOLO/SAM2는 원본만 실행
- [x] 비식별 프레임은 동일 FRAME_NO로 원본 라벨 공유
- [x] 마킹 0건 시 기존 간격 추출 폴백
