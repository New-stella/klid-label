# 11. AI 보조 · 오토라벨링

> 출처: R1 RQ-SFR-08-01/02, R2 KLID-AT-UC-004/005, CLAUDE.md, 코드(`batch/step`, `ai-server`, `common/util`)
> 관련: [07 배치 파이프라인](07-batch-pipeline.md) · [10 라벨링](10-labeling.md)

화면: `KLID-AT-SC-014`(오토라벨 요약 `/auto/:videoId`).

## 11.1 ai-server (내부 추론 서버)

- Python FastAPI, **stateless GPU 추론 전용** (인증/DB 없음). 외부 아님 — 저작도구 내부 구성요소.
- 호출: `AiServerClient`(Resilience4j, 60s 타임아웃 + CircuitBreaker)
- 라우터: `yolo.py`(`/infer/yolo/predict`·`/track`), `sam2.py`(`/infer/sam2/segment`·`/track`), `vlm.py`(`/infer/vlm/verify-objects`)
- **다중 인스턴스 수평 확장 가능**

## 11.2 YOLO 오토라벨링

> **탐지 백엔드**: `detector_backend` 설정으로 전환 — 기본 **YOLOX(ONNX Runtime, Apache-2.0)**, 대안 RT-DETRv2(transformers). 구 ultralytics YOLOv8(AGPL-3.0)은 제거됨. HTTP 경로(`/infer/yolo/predict`·`/track`)·응답 스키마·배치 단계명(`YoloAutolabelStep`)은 불변(계약 호환). 트래킹은 ByteTrack(roboflow trackers).

- 배치 단계 `YoloAutolabelStep` — **원본 이미지에만** 객체 탐지
- 프리셋 필터(이벤트 유형별 라벨) 적용, `track_id` 부여
- 라벨 좌표는 동일 해상도이므로 **비식별본과 공유**(별도 실행 없음)
- 출처/신뢰도는 `LS_DATA_LBL_AI_INFO`(`CONF_SCORE`)

## 11.3 SAM2 — VOS(추적) + 분할

> **분할 백엔드**: **Meta 공식 sam2(Apache-2.0)** `SAM2ImagePredictor`(HF `facebook/sam2-hiera-tiny`). 구 ultralytics SAM(AGPL-3.0)은 제거됨. ai-server에서 `set_image`+`predict`(point/box) → 마스크를 `cv2.findContours`로 외곽 폴리곤 변환. HTTP 경로(`/infer/sam2/segment`·`/track`)·polygon 응답 스키마 불변(계약 호환).

### 객체 자동 추적 (RQ-SFR-08-01, UC-004)
- 시작 프레임에서 객체를 박스/시드로 지정 → **SAM2 VOS**가 후속 프레임 위치(BBox)·경계(폴리곤) 자동 추적·갱신
- `POST /v1/frames/{srcSn}/sam2-track` (path/body srcSn 불일치 400, 본인 미배정 IDOR 차단)
- 자동 라벨 저장(`AUTO_LBL_YN='Y'`) + 트랙 보간으로 빈 프레임 보충
- **현 구현은 프레임별 bbox 전파 근사 추적**, 메모리 기반 고품질 VOS는 설계 타깃(planned)
- 코드: `Sam2TrackTool`, `Sam2SegmentStep`, `AiServerClient`

### 객체 외곽 경계 자동 밀착 (RQ-SFR-08-02, UC-005)
- 캔버스에서 클릭(포인트)/박스로 객체 지목(단축키 G) → SAM2 분할이 외곽 폴리곤+신뢰도 산출
- `POST /v1/frames/{srcSn}/sam2-segment` (points 또는 box 정확히 1개, @AssertTrue 배타 검증)
- 응답 폴리곤 좌표 검증(CWE-20) 후 Douglas-Peucker(`POLYGON_SIMPLIFY_TOLERANCE`) 단순화
- 이미지 상한 20MB(413), mock 응답은 자동 적용 차단
- 코드: `Sam2SegmentService`

## 11.4 트랙 보간 (CVAT 포팅)

- `TrackInterpolationStep` + `batch/interpolation/TrackInterpolator` — 키프레임 기반 선형 보간(BBox)
- 빈 프레임을 `Map<frameNo, Bbox>`로 채움
- CVAT 알고리즘 Java 포팅 (`docs/analysis/portable-modules/01-track-interpolation.md`)
- MASK↔RLE↔Polygon 변환은 `common/util/MaskRleConverter` → [19](19-external-security-cvat.md#cvat-포팅)

## 11.5 정밀도 조절

YOLO conf/iou/imgsz, SAM2 폴리곤 epsilon은 시스템 설정 화이트리스트로 조절 → [10 §10.5](10-labeling.md#정밀도-설정-rq-sfr-08-03).

## 11.6 관련 데이터 (DB)

`LS_DATA_LBL_AI_INFO`(AI 출처·신뢰도), `LS_DATA_LBL`(트랙ID). → [18](18-database.md).
