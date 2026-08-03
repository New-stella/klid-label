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
| 폴리곤 | `PolygonTool` | 다각형 경계. 클릭(또는 `F`)으로 정점 추가 → **같은 자리 더블클릭** 또는 `Q` 로 완성(3점 이상), 시작점 근접 클릭 시 자동 닫힘. **완성/취소 조작 외에는 그리던 정점이 지워지지 않는다**(2026-08-03 결함 수정 — Konva 의 dblclick 합성이 시간 400ms + 같은 shape 만 보고 **이동 거리를 보지 않아**, 서로 다른 위치를 빠르게 클릭하면 합성 dblclick 이 드래프트를 통째로 삭제하거나 조기 커밋했다. 이제 구성 클릭 두 지점의 거리로 의도를 판정하고, 합성된 것이면 무시한다 → `canvas/utils/doubleClickGuard.ts`). 그리기 취소는 `Esc`(선택 도구 전환) |
| 마스크 브러시 | `MaskBrushTool` | 손으로 칠하는 영역 |
| 마스크 지우개 | `MaskEraserTool` | 마스크 삭제 |
| SAM2 추적(VOS) | `Sam2TrackTool` | 시작 프레임 지정 후 후속 프레임 자동 추적·분할 → [11](11-ai-assisted.md). **내부(INTERNAL) 전용** — 포털은 미제공(2026-08-02 제거, ADR-013 정합 → [16](16-portal.md)) |
| SAM2 분할(밀착) | 캔버스 API | 클릭/박스 프롬프트 → 외곽 폴리곤 자동 생성(단축키 G) → [11](11-ai-assisted.md). **즉시 그리기 옵션**: AI 분할 도구 활성 시 우측 객체 속성 패널(`ObjectAttributePanel`)의 "AI 분할 정밀도" 섹션에 있는 "즉시 그리기" 토글(기본 OFF)을 켜면 클릭할 때마다 누적 점 전체로 즉시 분할해 **프리뷰 폴리곤**이 갱신되고, Enter/더블클릭 확정 시 실제 라벨로 커밋(끄면 기존 "누적 후 확정 시 1회 요청" 동작). **내부(INTERNAL) 전용** — 포털은 미제공(2026-08-02 제거, ADR-013 정합 → [16](16-portal.md)) |
| 키포인트(포즈) | `OverlayLayer`/`LabelsLayer` | COCO-17 관절 순차 배치(단축키 K) + 관절별 드래그 이동 + 스켈레톤 19선 렌더. 관절 **Alt+클릭** 시 가시성 순환(가시 2→비가시 1→미표기 0). 삼중값 `[x,y,v]`로 저장(`SKELETON`). **내부(INTERNAL) 전용** — 포털은 도구 미노출(2026-08-02) + **서버 저장·조회도 제거**(2026-08-03 — 포털 `lblTypeCd` allowlist=BBOX/POLYGON, SKELETON 은 400. ADR-013 정합 → [16](16-portal.md)) |
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

## 10.6 장시간 작업 중 편집 차단 · 진행 표시 · 취소 (2026-07-31)

라벨링 화면의 장시간 작업이 도는 동안 **편집이 전면 차단되고, 무엇이 진행 중인지 화면에 보이며, 취소로 즉시 빠져나올 수 있다.** FE 전용이며 API·DB 변경은 없다.

### 대상 작업(busy) — 5종 단일 축

| 종류 | 표시 문구 | 트리거 |
|------|----------|--------|
| `AI_DETECT` | AI 탐지 진행 중 | AI 도구 팝업 실행 |
| `AI_SEGMENT` | AI 분할 진행 중 | 클릭/박스 프롬프트 · 즉시 그리기 프리뷰 |
| `AI_TRACK` | AI 추적 진행 중 | 추적 실행 |
| `SAVE` | 저장 중 | 라벨 저장(내부·포털·포털 업로드) |
| `LOAD` | 불러오는 중 | 라벨 재조회(불러오기) |

- 다섯 종류가 **하나의 배타 축**을 공유한다 — 하나가 도는 동안 다른 하나는 시작되지 않는다.
- 문구는 `features/label/busyPolicy.ts` **단일 소스**에서 파생한다. 사용자 문구에 모델명(YOLO/SAM/SAM2)은 쓰지 않는다.
- busy 는 **작업이 속한 프레임**에 묶인다. 프레임/영상을 옮기면 이전 프레임의 작업이 새 화면을 잠그지 않고, 그 뒤 도착한 응답도 반영되지 않는다.

### 차단 — "요청 거부"가 아니라 **입력 차단**

판정은 `useIsEditBlocked`(렌더) / `isEditBlockedNow`(실시간) **한 쌍**이며, 두 값이 갈리면 **막히는 쪽**을 택한다(fail-closed). 배선된 진입점:

| 영역 | 차단 대상 |
|------|----------|
| 캔버스 | 새 도형 그리기, 기존 라벨 선택·이동·삭제(입력 단계에서 차단 — 드래그가 시작되지 않는다) |
| 툴바 | 도구 전환·삭제·실행취소·저장 버튼 비활성 |
| 프레임 이동 | 슬라이더 · 필름스트립 · 이전/다음 버튼 · 단축키 전부 |
| 실행 버튼 | 저장 · 검수제출 · AI 실행(팝업 포함) |
| 우측 패널 | 객체 속성 편집, 객체 목록의 선택·삭제 |
| 단축키 | 전 키맵. **ESC 만 예외이며 "취소"로 동작**한다 |
| 그 밖 | 되돌리기(revert) · 버전 롤백 · 비식별 누락 신고 |

- **예외 1 — AI 분할 클릭 누적**: 오버레이가 아직 뜨지 않은 지연 창(<300ms)에서는 클릭이 그대로 누적되고 확정은 큐에 들어간다(작업이 끝나면 누적점 전체로 실행). 화면에 아무 단서가 없는 구간에서 조작을 삼키면 이유 없이 사라진 것으로 보이기 때문이다. 오버레이가 뜬 뒤에는 누적하지 않는다.
- **예외 2 — 메타 편집은 busy 와 독립**(의도된 설계): 촬영환경 · 개인정보 메타 · 프레임 설명 · 이벤트 어노테이션 · 시계열 메타 검수는 진행 중에도 편집·저장된다. 라벨 작업본(`labels`/dirty)과 공유 상태가 없고 각자 별도 쿼리 키만 무효화하므로 정합성이 깨지는 경로가 없다. 회귀 테스트로 고정돼 있으며, 이 테스트가 깨지면 회귀가 아니라 **정책 변경**이다.

### 진행 표시 — 300ms 초과부터

- 캔버스 위 오버레이(`BusyOverlay`)에 **작업명 + 경과 초 + 취소 버튼**을 표시한다.
- **짧은 작업(<300ms)에는 표시하지 않는다** — 즉시 그리기처럼 클릭마다 짧은 요청이 나가는 경로에서 오버레이가 깜빡이면 화면이 고장난 것처럼 보인다.
- 접근성: `role="status" aria-live="polite" aria-busy="true"`, 오버레이가 뜨면 취소 버튼으로 포커스 이동(포커스 트랩은 만들지 않음), 경과 초는 라이브 리전에서 제외(매초 낭독 방지). 모달이 열려 있으면 포커스를 가져가지 않는다.
- 백드롭이 포인터 이벤트를 흡수한다 — 시각적 차단과 물리적 차단이 일치한다.

### 취소 시맨틱 (중요)

- **취소는 클라이언트에서 결과를 폐기하는 것이고, 서버 처리를 중단시키지 않는다.** 오버레이 안내 문구도 그대로 적는다("서버 처리가 즉시 중단되지는 않습니다").
- 취소 후 도착한 응답은 세대 토큰으로 폐기된다 — 같은 프레임이어도 반영되지 않는다.
- 수단: 취소 버튼 **마우스 클릭** · 버튼 포커스에서 **Enter/Space** · **ESC**. ESC 는 오버레이·캔버스·전역 단축키 어디서 눌려도 **같은 헬퍼 하나**를 거쳐 1회만 취소·안내한다(중복 안내 없음). 단, 전역 ESC 는 라벨링 단축키가 배선된 화면(내부·포털 라벨링) 기준이고, **포털 업로드 라벨링에는 라벨링 단축키가 없어 ESC 는 오버레이에 포커스가 있을 때만 동작**한다. 오버레이가 뜨면 취소 버튼으로 포커스가 이동하지만, 그 뒤 백드롭이나 화면 다른 곳을 클릭하면 포커스가 빠져 ESC 가 듣지 않는다 — 그때는 취소 버튼 클릭으로 취소한다.
- 오버레이가 뜨기 전(지연 창)의 ESC 취소는 화면에 흔적이 없으므로 **토스트로 안내**한다.
- ESC 취소는 대기 중인 AI 분할 **확정 큐도 함께 비운다** — 취소했는데 그 작업이 뒤늦게 발사되면 정반대 동작이다. 누적점 자체는 지우지 않는다(사용자가 지운 적이 없다).
- 해제 누락 방지: 성공·실패·예외 어느 경로로 끝나도 해제되고, **최대 5분** fail-safe 로 자동 해제된다(화면 영구 잠금 방지).

### 범위 밖

- **크로스탭·다중 사용자 동시성은 이 차단의 대상이 아니다.** FE busy 는 **같은 탭 안**에서만 성립하며, 다른 탭·다른 사용자와의 충돌은 서버 낙관적 잠금(라벨 저장 409 → 충돌 다이얼로그)이 담당한다.
- 배치 파이프라인 진행 표시, 마킹·검수 화면은 이 규칙의 대상이 아니다.

### 적용 화면 — 세 화면의 경계

| 화면 | 경로 | 데이터 | busy 종류 |
|------|------|--------|----------|
| 내부 라벨링 | `/label/:id` | 내부 파이프라인 프레임 | 5종 전부 |
| **포털 라벨링** | `/portal/label/:id`(PortalRoute) | **데이터마트 영상** | AI 분할·추적 + 저장/불러오기(AI 탐지 미제공) |
| **포털 업로드 라벨링** | `/portal/uploads/:uldSn/label` | **본인 업로드 자산**(`LS_PORTAL_*`, ADR-013 별도 경로) | **저장뿐** — AI 작업이 없다 |

> **"포털 라벨링"과 "포털 업로드 라벨링"은 다른 화면이다.** 경로도 데이터도 분리돼 있다(전자는 데이터마트 영상, 후자는 ADR-013 예외로 신설된 포털 자산 업로드 경로). 차단·진행 표시·취소는 **두 화면 모두** 동일하게 적용되지만, 포털 업로드에는 오토라벨·분할·추적 진입점 자체가 없어 AI busy 가 발생하지 않는다. → [16 포털](16-portal.md)

코드: `features/label/busyPolicy.ts`(문구·오버레이 경계·ESC 판정 단일 소스) · `features/label/hooks/useBusyTask.ts`(배타 실행·세대 토큰·fail-safe) · `features/label/components/BusyOverlay.tsx` · `stores/useLabelStore.ts`(busy 상태). AI 작업 쪽 관점 → [11 §11.6](11-ai-assisted.md).

## 10.7 관련 데이터 (DB)

§10.3 + `LS_SYSTEM_CONFIG`, `LS_LABEL_PRESET`. → [18](18-database.md).
