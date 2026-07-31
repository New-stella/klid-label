# 10. 라벨링

> 출처: R1 RQ-SFR-08-02/03, R2 KLID-AT-SS-006, CLAUDE.md(라벨링·버전관리), 코드(`label/`, `preset/`, `sysconfig/`, `frontend label/canvas`)
> 관련: [11 AI 보조](11-ai-assisted.md) · [12 검수](12-review-assignment.md) · [13 버전관리](13-version-control.md)

화면: `KLID-AT-SC-005`(라벨링 캔버스 `/label/:id`). 코드: `label/`(35 파일) + `frontend/src/features/label/`.

## 10.1 캔버스 (konva.js)

- **konva.js v9 + react-konva v18** (CVAT canvas-drawing 포팅)
- 라벨 타입: **바운딩박스 / 폴리곤 / 세그멘테이션(마스크) / SAM2 Track / 키포인트(COCO-17 휴먼 포즈)**
- 좌표 원칙: 모든 연산은 **원본 이미지 해상도·좌표계** 기준

### 캔버스 이미지 서빙 (`GET /v1/frames/{srcSn}/image`)

FE 는 `useImageBlob` 이 axios(Bearer)로 fetch → blob URL → konva Image 로 넘긴다(`<img src>` 직접 호출은 토큰 미첨부로 401).

| 항목 | 계약 |
|------|------|
| 기본 서빙 대상 | **비식별(DEID) 프레임**(`DE_IDNTF_SRC_FILE_PATH_NM`) — 라벨링은 비식별 영상의 프레임으로 수행한다. **단, 비식별 경로가 없는 레거시 ANONY 프레임은 원본으로 폴백된다**(아래 행 — 의도된 하위호환. 백필 전까지 이 프레임들은 원본이 서빙된다) |
| 원본(RAW) | **REVIEWER 가 `?raw=true` 를 명시**할 때만. WORKER 의 `raw=true` 는 무시하고 DEID 강제 |
| 비식별 경로가 없을 때 | PRVC/PSDO(민감) → **404**("비식별 처리 미완료", 원본 폴백 금지) / ANONY(레거시) → **원본 폴백** |
| 파생(증강·해상도) 프레임 | 원본 픽셀이 실재하지 않아 `SRC_FILE_PATH_NM=null` 이므로 **비식별 경로로 서빙**된다(구 구현은 무조건 404 → 캔버스 백지) |
| 신고 구간 | `DE_IDNTF_YN='F'` → **412**(역할 무관). 순서는 ①인가(403) → ②게이트(412) → ③경로 해석 고정 |
| 캐시 | `Cache-Control: no-store` (게이트가 매 요청 평가되어야 함) |

판정·검증은 `FrameImageService` **단일 원천**이며 `GET /v1/videos/{rawSn}/frames/{frameNo}/image` 와 같은 코드를 쓴다(컨트롤러는 위임만).
`GET /v1/frames/{srcSn}/deid-image` 는 **원본 폴백이 절대 없는 엄격 계약**으로 별도 유지된다.
FE 는 이미지 로드 실패 시 캔버스 영역에 안내를 표시한다(과거에는 아무 표시 없이 백지였다).

## 10.2 도구

| 도구 | 컴포넌트 | 기능 |
|------|----------|------|
| 바운딩박스 | `BboxTool` | 직사각형 영역 |
| 폴리곤 | `PolygonTool` | 다각형 경계 |
| 마스크 브러시 | `MaskBrushTool` | 손으로 칠하는 영역 |
| 마스크 지우개 | `MaskEraserTool` | 마스크 삭제 |
| SAM2 추적(VOS) | `Sam2TrackTool` | 시작 프레임 지정 후 후속 프레임 자동 추적·분할 → [11](11-ai-assisted.md). **Phase 9: 포털도 제공** — `portalMode`면 `/v1/portal/frames/{id}/sam2-track`(persist 없이 좌표만) |
| SAM2 분할(밀착) | 캔버스 API | 클릭/박스 프롬프트 → 외곽 폴리곤 자동 생성(단축키 G) → [11](11-ai-assisted.md). **즉시 그리기 옵션**: AI 분할 도구 활성 시 우측 객체 속성 패널(`ObjectAttributePanel`)의 "AI 분할 정밀도" 섹션에 있는 "즉시 그리기" 토글(기본 OFF)을 켜면 클릭할 때마다 누적 점 전체로 즉시 분할해 **프리뷰 폴리곤**이 갱신되고, Enter/더블클릭 확정 시 실제 라벨로 커밋(끄면 기존 "누적 후 확정 시 1회 요청" 동작). **Phase 9: 포털도 제공** — `portalMode`면 `/v1/portal/frames/{id}/sam2-segment`(persist 없이 좌표만) |
| 키포인트(포즈) | `OverlayLayer`/`LabelsLayer` | COCO-17 관절 순차 배치(단축키 K) + 관절별 드래그 이동 + 스켈레톤 19선 렌더. 관절 **Alt+클릭** 시 가시성 순환(가시 2→비가시 1→미표기 0). 삼중값 `[x,y,v]`로 저장(`SKELETON`). **Phase 9(ADR-013 override): 포털도 노출** — 포털은 `LS_PORTAL_USER_LABEL` 단방향 저장(17점 삼중값 검증) |
| 팬 / 선택 | `PanTool` / `SelectTool` | 이동 / 선택·편집 |

캔버스 구성: `CanvasShell`, `DarkFrameSlider`(프레임 타임라인), `LabelSidebar`, `ToolBar`, `ObjectClassTree`, `ObjectAttributePanel`.

## 10.3 라벨 마스터 · 속성

| 테이블 | 역할 |
|--------|------|
| `LS_LABEL` (V31) | 라벨 마스터 — `LABEL_NM`, `COLR_VL`(색상), `LABEL_TYPE_CD`(BBOX/POLYGON/POINT/SKELETON) |
| `LS_LABEL_ATTR` (V33) | 라벨 속성 정의 — `INPUT_TYPE_CD`(SELECT/CHECKBOX/RADIO/NUMBER/TEXT), `VALUES_CN`, `MUTABLE_YN` |
| `LS_DATA_LBL` | 라벨(좌표·트랙ID·LABEL_NM) — 작업 중 임시저장 |
| `LS_DATA_LBL_ATTR_VAL` (V33) | 라벨 속성값 |
| `LS_DATA_LBL_AI_INFO` (V23) | AI 라벨 출처(YOLO/SAM2/VLM)·신뢰도(`CONF_SCORE`) |

> 라벨 저장은 작업 중 `LS_DATA_LBL` upsert만, **버전 스냅샷은 검수 승인 시점**에만 생성 → [13](13-version-control.md).

## 10.4 라벨 프리셋

화면: `KLID-AT-SC-026`(프리셋 관리 `/manage/presets`, REVIEWER)

- 이벤트 유형별 라벨 자동 필터 (`PresetLabelLookupService`)
- `LS_LABEL_PRESET`(V13) + `LS_LABEL_PRESET_CODE` — 프리셋 마스터/내 라벨 코드
- 코드: `preset/PresetController`
- **라벨 마스터 단일 진실원 연동 (V117~V119, 2026-07-21)**: 프리셋 코드는 라벨명·형태를 스냅샷하지 않고 `LBL_ID`(FK→`LS_LABEL.LBL_ID`, nullable)로 라벨 마스터(`LS_LABEL`)를 실시간 참조한다. 조회·표시·오토라벨 사용 시점에 마스터에서 join하므로 **마스터에서 라벨명/형태를 바꾸면 신규·기존 프리셋 모두에 즉시 반영**된다. **형태는 마스터 `LBL_TYPE_CD`가 소유**(BBOX→bbox·POLYGON→polygon·POINT/SKELETON→도형 오토라벨 미적용) — 프리셋에서 형태 개별 토글은 불가(구 `BBOX_ENABLED`/`POLYGON_ENABLED` 컬럼 제거). 마스터에 매칭 안 되는 기존 코드는 오류 없이 **'미연결'**로 표시(자동 생성/삭제 없음). FE 프리셋 편집은 라벨을 **라벨 마스터 목록에서 선택**(하드코딩 라벨 제거)하고 요청은 labelId 기반, 형태는 마스터 기준 읽기전용 표시 + 미연결 배지. 오토라벨(AI 탐지/AI 분할) 경로도 프리셋↔검출 라벨 매칭을 마스터 라벨명 축으로 일원화 → [11](11-ai-assisted.md). 스키마 상세 → [18](18-database.md).

## 10.5 정밀도 설정 (RQ-SFR-08-03)

화면: `KLID-AT-SC-025`(시스템 설정 `/manage/settings`, REVIEWER)

시스템 설정 화이트리스트 키(`LS_SYSTEM_CONFIG`, Caffeine TTL 60s):

| 키 | 범위 | 적용 |
|----|------|------|
| `YOLO_CONF_THRESHOLD` | 25~80 | YOLO 인식 민감도 |
| `YOLO_IOU` | 30~80 | YOLO IoU 임계값 |
| `YOLO_IMGSZ` | 320~1920 | YOLO 입력 크기 |
| `POLYGON_SIMPLIFY_TOLERANCE` | 0.0~50.0 | SAM2 폴리곤 단순화(Douglas-Peucker epsilon) |

- 미등록 키/범위 초과는 400 거부 (CWE-20)
- 코드: `sysconfig/SystemConfigController`, `ConfigKeys`

> **라벨링 화면 per-실행 수동 조절**(2026-07-22) — 시스템 설정값은 **기본값**이고, 라벨러가 라벨링 화면에서 AI 실행 직전 이번 호출에 한해 조절할 수 있다(세션 한정, DB 미저장). **AI 탐지**(AI Tool 팝업)에 **인식 민감도**(`YOLO_CONF_THRESHOLD` 축, 0.25~0.80) + **경계 세밀함**(`POLYGON_SIMPLIFY_TOLERANCE`, 0~50px, **폴리곤 형태일 때만**) 슬라이더, **AI 분할** 도구에 **경계 세밀함**만 노출(SAM2는 신뢰도 임계값 미수용 → 인식 민감도 미노출). 시스템 설정값으로 프리필되며, 조절하지 않으면 요청 body에 파라미터를 넣지 않아 시스템 설정 기본값으로 동작한다(무회귀). YOLO 온라인 폴리곤 출력에도 이때 Douglas-Peucker 단순화가 신규 적용된다. 요청 필드: `POST /v1/frames/{srcSn}/autolabel`(`confThreshold`·`simplifyTolerance`, optional), `POST /v1/frames/{srcSn}/sam2-segment`(`simplifyTolerance`, optional). YOLO `imgsz`(로더 640 고정 무시)·max detections·SAM2 신뢰도는 라벨링 화면 미노출. → [11 §11.5](11-ai-assisted.md).

## 10.6 관련 데이터 (DB)

§10.3 + `LS_SYSTEM_CONFIG`, `LS_LABEL_PRESET`. → [18](18-database.md).
