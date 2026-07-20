# 10. 라벨링

> 출처: R1 RQ-SFR-08-02/03, R2 KLID-AT-SS-006, CLAUDE.md(라벨링·버전관리), 코드(`label/`, `preset/`, `sysconfig/`, `frontend label/canvas`)
> 관련: [11 AI 보조](11-ai-assisted.md) · [12 검수](12-review-assignment.md) · [13 버전관리](13-version-control.md)

화면: `KLID-AT-SC-005`(라벨링 캔버스 `/label/:id`). 코드: `label/`(35 파일) + `frontend/src/features/label/`.

## 10.1 캔버스 (konva.js)

- **konva.js v9 + react-konva v18** (CVAT canvas-drawing 포팅)
- 라벨 타입: **바운딩박스 / 폴리곤 / 세그멘테이션(마스크) / SAM2 Track / 키포인트(COCO-17 휴먼 포즈)**
- 좌표 원칙: 모든 연산은 **원본 이미지 해상도·좌표계** 기준

## 10.2 도구

| 도구 | 컴포넌트 | 기능 |
|------|----------|------|
| 바운딩박스 | `BboxTool` | 직사각형 영역 |
| 폴리곤 | `PolygonTool` | 다각형 경계 |
| 마스크 브러시 | `MaskBrushTool` | 손으로 칠하는 영역 |
| 마스크 지우개 | `MaskEraserTool` | 마스크 삭제 |
| SAM2 추적(VOS) | `Sam2TrackTool` | 시작 프레임 지정 후 후속 프레임 자동 추적·분할 → [11](11-ai-assisted.md). **Phase 9: 포털도 제공** — `portalMode`면 `/v1/portal/frames/{id}/sam2-track`(persist 없이 좌표만) |
| SAM2 분할(밀착) | 캔버스 API | 클릭/박스 프롬프트 → 외곽 폴리곤 자동 생성(단축키 G) → [11](11-ai-assisted.md). **즉시 그리기 옵션**: AI Tool 팝업(`AiToolModal`)의 "즉시 그리기" 토글(기본 OFF)을 켜면 클릭할 때마다 누적 점 전체로 즉시 분할해 **프리뷰 폴리곤**이 갱신되고, Enter/더블클릭 확정 시 실제 라벨로 커밋(끄면 기존 "누적 후 확정 시 1회 요청" 동작). **Phase 9: 포털도 제공** — `portalMode`면 `/v1/portal/frames/{id}/sam2-segment`(persist 없이 좌표만) |
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

- 이벤트 유형별 라벨 자동 필터 (`PresetLabelLookupService`, BBOX/POLYGON 토글)
- `LS_LABEL_PRESET`(V13) + `LS_LABEL_PRESET_CODE` — 프리셋 마스터/내 라벨 코드
- 코드: `preset/PresetController`

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

## 10.6 관련 데이터 (DB)

§10.3 + `LS_SYSTEM_CONFIG`, `LS_LABEL_PRESET`. → [18](18-database.md).
