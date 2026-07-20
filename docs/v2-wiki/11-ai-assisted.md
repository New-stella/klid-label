# 11. AI 보조 · 오토라벨링

> 출처: R1 RQ-SFR-08-01/02, R2 KLID-AT-UC-004/005, CLAUDE.md, 코드(`batch/step`, `ai-server`, `common/util`)
> 관련: [07 배치 파이프라인](07-batch-pipeline.md) · [10 라벨링](10-labeling.md)

화면: 오토라벨 요약은 **영상 상세(SC-009)의 인라인 `AutoLabelTab`**으로 제공. (구 `SC-014` 오토라벨 요약 전용 페이지 `/auto/:videoId`는 진입점 없는 orphan으로 2026-06-17 deprecated·코드 제거 → [04 화면·IA](04-screens-ia.md))

## 11.1 ai-server (내부 추론 서버)

- Python FastAPI, **stateless GPU 추론 전용** (인증/DB 없음). 외부 아님 — 저작도구 내부 구성요소.
- 호출: `AiServerClient`(Resilience4j, 60s 타임아웃 + CircuitBreaker)
- 라우터: `yolo.py`(`/infer/yolo/predict`·`/track`), `sam2.py`(`/infer/sam2/segment`·`/track`), `vlm.py`(`/infer/vlm/verify-objects`)
- **다중 인스턴스 수평 확장 가능**

## 11.2 YOLO 오토라벨링

> **탐지 백엔드**: **YOLOX(ONNX Runtime, Apache-2.0) 단일 백엔드**. 구 ultralytics YOLOv8(AGPL-3.0)·RT-DETRv2(transformers) 백엔드는 제거됨(torch↔torchaudio ABI 불일치로 YOLOX 로 통합). HTTP 경로(`/infer/yolo/predict`·`/track`)·응답 스키마·배치 단계명(`YoloAutolabelStep`)은 불변(계약 호환). 트래킹은 ByteTrack(roboflow trackers).

- 배치 단계 `YoloAutolabelStep` — **원본 이미지에만** 객체 탐지
- 프리셋 필터(이벤트 유형별 라벨) 적용, `track_id` 부여
- 라벨 좌표는 동일 해상도이므로 **비식별본과 공유**(별도 실행 없음)
- 출처/신뢰도는 `LS_DATA_LBL_AI_INFO`(`CONF_SCORE`)

### 온디맨드 YOLO 객체 추적 — `POST /v1/frames/{srcSn}/yolo-track` (인터랙티브)
- 배치 자동라벨링과 **별개의 온디맨드 경로** — 라벨러가 정렬된 프레임 시퀀스(`srcSn` 시작 + `nextSrcSns` 후속, 최대 50)를 지정하면 ai-server `/infer/yolo/track`을 프레임별 프록시하여 검출(`label`/`points[x1,y1,x2,y2]`/`score`/`track_id`)을 프레임별로 반환
- **순수 조회(DB 미저장)** — 결과는 FE가 받아 기존 `PUT /v1/frames/{srcSn}/labels`로 저장(배치 `YoloAutolabelStep`과 중복 저장 방지)
- 트래커 격리: `clip_id = {rawSn}:{요청 UUID}`(요청 단위 격리 — 동일 영상 동시 추적 간섭 방지), `frame_index` 0-base(첫 프레임 트래커 리셋)
- 방어: 본인 미배정 IDOR 차단(시작+모든 후속 프레임), path/body `srcSn` 불일치 400(CWE-345), 시퀀스 교차 영상(RAW_SN) 혼입 400, `nextSrcSns` 상한 50(CWE-770), 응답 좌표 검증(CWE-20). ai-server 연동 실패 502
- REVIEWER/WORKER. 코드: `YoloTrackService`·`LabelController#yoloTrack` (LogiCraft `API-123`)

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
- **입력 방식(FE)**: 기본은 클릭으로 positive-point 를 누적한 뒤 Enter/더블클릭으로 1회 확정. AI Tool 팝업의 **"즉시 그리기"** 토글(기본 OFF)을 켜면 클릭마다 누적 점 전체로 즉시 분할해 **프리뷰 폴리곤**을 갱신(반복 정교화)하고, 확정 시 프리뷰를 커밋. 프리뷰 경로에도 mock(빈 폴리곤)/저신뢰 자동적용 차단이 동일 적용되며, 요청 세대 토큰·확정 큐잉으로 동일 프레임 in-flight 경합(마지막 클릭 누락·유령 프리뷰·조용한 소실)을 방지
- 코드: `Sam2SegmentService`(BE), `OverlayLayer`/`AiToolModal`/`CanvasShell`(FE 즉시 프리뷰·토글)

## 11.4 트랙 보간 (CVAT 포팅)

- `TrackInterpolationStep` + `batch/interpolation/TrackInterpolator` — 키프레임 기반 선형 보간(BBox)
- 빈 프레임을 `Map<frameNo, Bbox>`로 채움
- CVAT 알고리즘 Java 포팅 (`docs/analysis/portable-modules/01-track-interpolation.md`)
- MASK↔RLE↔Polygon 변환은 `common/util/MaskRleConverter` → [19](19-external-security-cvat.md#cvat-포팅)

## 11.5 정밀도 조절

YOLO conf/iou/imgsz, SAM2 폴리곤 epsilon은 시스템 설정 화이트리스트로 조절 → [10 §10.5](10-labeling.md#정밀도-설정-rq-sfr-08-03).

## 11.6 관련 데이터 (DB)

`LS_DATA_LBL_AI_INFO`(AI 출처·신뢰도), `LS_DATA_LBL`(트랙ID). → [18](18-database.md).
