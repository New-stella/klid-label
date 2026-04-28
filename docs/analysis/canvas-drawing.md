# 캔버스 드로잉 도구 (cvat-canvas)

## 개요
`cvat-canvas` 패키지는 SVG.js 기반의 2D 어노테이션 캔버스 라이브러리다. MVC 패턴으로 설계되어 있으며, 각 드로잉 모드(BBOX, POLYGON, MASK 등)마다 별도의 Handler 클래스가 존재한다.

## 핵심 코드 위치
| 역할 | 파일 경로 | 비고 |
|------|---------|------|
| Canvas 공개 인터페이스 | `cvat-canvas/src/typescript/canvas.ts` | Canvas interface, CanvasImpl |
| 캔버스 모델 (상태/타입 정의) | `cvat-canvas/src/typescript/canvasModel.ts` | DrawData, Configuration 등 |
| 캔버스 뷰 (SVG 렌더링) | `cvat-canvas/src/typescript/canvasView.ts` | 실제 SVG 조작 |
| 캔버스 컨트롤러 | `cvat-canvas/src/typescript/canvasController.ts` | 이벤트 → 모델 업데이트 |
| 드로잉 핸들러 | `cvat-canvas/src/typescript/drawHandler.ts` | DrawHandlerImpl |
| 마스크 핸들러 | `cvat-canvas/src/typescript/masksHandler.ts` | 픽셀 마스크 브러시 |
| 편집 핸들러 | `cvat-canvas/src/typescript/editHandler.ts` | 기존 shape 수정 |
| 인터랙션 핸들러 | `cvat-canvas/src/typescript/interactionHandler.ts` | AI 모델 인터랙티브 호출 |
| 자동 경계 핸들러 | `cvat-canvas/src/typescript/autoborderHandler.ts` | 폴리곤 경계 자동 스냅 |
| 슬라이스 핸들러 | `cvat-canvas/src/typescript/sliceHandler.ts` | shape 절단 |
| 영역 선택기 | `cvat-canvas/src/typescript/regionSelector.ts` | 사각형 드래그 영역 선택 |
| 객체 선택기 | `cvat-canvas/src/typescript/objectSelector.ts` | 다중 객체 선택 |
| 공유 유틸리티 | `cvat-canvas/src/typescript/shared.ts` | translateToSVG 등 좌표 변환 |
| 상수 | `cvat-canvas/src/typescript/consts.ts` | SIZE_THRESHOLD 등 |

## 패키지 구조 및 의존성

```json
// cvat-canvas/package.json (주요 의존성)
{
  "dependencies": {
    "svg.js": "^2.x",      // SVG DOM 조작
    "svg.draw.js": "...",  // SVG 드로잉 플러그인
    "fabric": "...",       // MASK 브러시 (canvas 2d context)
    "point-in-polygon": "..."
  }
}
```

> **주의**: `fabric` 의존성은 `cvat-canvas/package.json`에 직접 명시되지 않고
> monorepo workspace 레벨에서 참조될 수 있다. 패키지를 독립적으로 포팅할 때는
> `fabric`을 명시적 의존성으로 추가해야 한다.

## MVC 아키텍처

```
canvasModel.ts (Model)
  - 상태(DrawData, EditData, MergeData 등) 저장
  - 비즈니스 로직 없음, 순수 상태 컨테이너

canvasController.ts (Controller)
  - 외부 API 메서드 (draw, edit, group, merge 등)
  - 모델 상태 변경 → 뷰에 알림

canvasView.ts (View)
  - SVG.js로 shape 렌더링
  - 마우스/터치 이벤트 처리
  - UpdateReasons에 따라 부분 업데이트

각 Handler:
  - DrawHandler: 새 shape 그리기
  - EditHandler: 기존 shape 점 편집
  - MasksHandler: 브러시로 마스크 그리기
  - InteractionHandler: AI 모델과 실시간 상호작용
  - GroupHandler: 여러 shape 그룹화
  - MergeHandler: 여러 shape를 track으로 합치기
  - SplitHandler: track을 두 개로 나누기
```

## 드로잉 구현 방식 {#drawing-handlers}

### DrawData 인터페이스

```typescript
// canvasModel.ts
interface DrawData {
    enabled: boolean;
    continue?: boolean;           // 연속 그리기 (폴리곤 연속 추가)
    shapeType?: string;           // 'rectangle' | 'polygon' | 'polyline' | ...
    rectDrawingMethod?: RectDrawingMethod; // 'By 2 points' | 'By 4 points'
    cuboidDrawingMethod?: CuboidDrawingMethod;
    skeletonSVG?: SVGSVGElement;
    numberOfPoints?: number;      // 고정 포인트 수 (points 타입)
    initialState?: any;           // 초기 상태 (오토라벨링 결과 편집 시)
    crosshair?: boolean;
    brushTool?: BrushTool;        // MASK 브러시 도구 설정
    redraw?: number;              // shape ID (재그리기)
    onDrawDone?: (data: object) => void;
}
```

### Shape별 제약 조건 검사

```typescript
// drawHandler.ts:checkConstraint()
function checkConstraint(shapeType, points, box) {
    if (shapeType === 'rectangle') {
        // width >= SIZE_THRESHOLD && height >= SIZE_THRESHOLD
    }
    if (shapeType === 'polygon') {
        // 최소 3개 꼭짓점 (6개 좌표값)
        // width >= SIZE_THRESHOLD || height >= SIZE_THRESHOLD
    }
    if (shapeType === 'polyline') {
        // 최소 2개 점 (4개 좌표값)
    }
    if (shapeType === 'ellipse') {
        // cx, cy, rx, ry 형식, width/height >= SIZE_THRESHOLD
    }
}
// SIZE_THRESHOLD = 2 (consts.ts)
```

### MASK 드로잉 {#mask-drawing}

MASK는 SVG.js가 아닌 HTML5 Canvas 2D Context + Fabric.js를 사용한다.

```typescript
// masksHandler.ts
interface BrushTool {
    type: 'brush' | 'eraser' | 'polygon-plus' | 'polygon-minus';
    color: string;      // 마스크 색상
    form: 'circle' | 'square'; // 브러시 형태
    size: number;       // 브러시 크기 (픽셀)
    onBlockUpdated: ...
}

// 브러시로 그린 픽셀 마스크를 RLE로 인코딩하여 points 필드에 저장
// mask → canvas ImageData → RLE encoding → [rle_data..., left, top, right, bottom]
```

## 이벤트 처리 패턴

```typescript
// canvasModel.ts - UpdateReasons (뷰 업데이트 이유)
enum UpdateReasons {
    IMAGE_CHANGED       = 'image_changed',    // 프레임 변경
    IMAGE_ZOOMED        = 'image_zoomed',
    OBJECTS_UPDATED     = 'objects_updated',  // shape 목록 갱신
    DRAW_MODE_CHANGED   = 'draw_mode_changed',
    SELECT_REGION       = 'select_region',
    DRAG_CANVAS         = 'drag_canvas',
    ...
}

// 뷰는 UpdateReasons를 받아 필요한 부분만 업데이트
// (전체 리렌더링 없이 SVG 요소만 교체)
```

## 좌표 변환

```typescript
// shared.ts
function translateToSVG(svg: SVGSVGElement, points: [number, number][]): [number, number][]
// 이미지 픽셀 좌표 → SVG 좌표계 변환 (scale, offset 적용)

function translateToCanvas(geometry: Geometry, points: number[]): number[]
// 논리 좌표 → 캔버스 표시 좌표 (geometry.scale 배율 적용)
```

**중요**: 캔버스에 표시되는 좌표는 scale이 적용된 화면 좌표지만, 저장 시에는 항상 원본 픽셀 좌표로 변환해서 서버에 전송한다.

## 상태 관리 방식

```typescript
// cvat-ui/src/reducers/ (Redux 패턴)

// annotationReducer.ts — 어노테이션 상태
interface AnnotationState {
    job: { id, frame, labels, ... }
    annotations: { states: ObjectState[] }  // 현재 프레임의 shape 목록
    canvas: { instance: Canvas, ready: boolean }
}

// canvasReducer.ts — 캔버스 UI 상태
interface CanvasState {
    activeModel: ActiveControl  // CURSOR | DRAW_RECTANGLE | DRAW_POLYGON | ...
    brushTools: { ...settings }
}
```

## InteractionHandler (AI 인터랙티브)

```typescript
// interactionHandler.ts
interface InteractionData {
    enabled: boolean;
    command?: 'draw_points' | 'draw_box' | 'put_shapes' | 'refine';
    payload?: {
        shapes: { shapeType: string; points: ArrayLike<number> }[];
    };
    settings?: {
        crosshair?: boolean;
        points_type?: 'any' | 'positive' | 'negative'; // SAM prompt용
        removalStrategy?: 'any' | 'last';
        appendCursorPositionAsPoint?: boolean;
    };
}

// SAM 사용 시 흐름:
// 1. 사용자가 positive/negative 포인트 클릭
// 2. InteractionHandler → API 호출 (POST /api/lambda/requests)
// 3. 서버 SAM 모델 → 세그먼테이션 결과 반환
// 4. 결과를 임시 shape으로 캔버스에 표시
// 5. 사용자 확인 → 저장
```

## 추가 핸들러 상세

### AutoborderHandler (자동 경계 스냅)

```typescript
// autoborderHandler.ts
export interface AutoborderHandler {
    autoborder(enabled: boolean, currentShape?: SVG.Shape, excludedClientId?: number): void;
    configure(configuration: Configuration): void;
    transform(geometry: Geometry): void;
    updateObjects(): void;
}
```

폴리곤 드로잉 시 인접한 다른 shape의 경계선에 자동으로 스냅(snap)하는 기능.
`collectSegmentPoints()`로 두 경계점 사이의 세그먼트를 수집해 현재 폴리곤에 자동 추가한다.

### SliceHandler (shape 절단)

```typescript
// sliceHandler.ts
export interface SliceHandler {
    slice(sliceData: SliceData): void;  // SliceData.enabled 로 활성화
    transform(geometry: Geometry): void;
    configure(config: Configuration): void;
    cancel(): void;
}

// 지원 타입: shapeType = 'mask' | 'polygon'
// 동작: 사용자가 자른 선을 그리면 shape를 두 부분으로 분리
// 내부적으로 OffscreenCanvas를 사용해 마스크 영역을 분할
```

### RegionSelector (사각형 영역 선택)

```typescript
// regionSelector.ts
export interface RegionSelector {
    select(enabled: boolean): void;   // 드래그 선택 모드 활성화
    cancel(): void;
    transform(geometry: Geometry): void;
}
// 드래그로 사각형 영역을 선택하면 onRegionSelected 콜백 호출
// 반환값: [xtl, ytl, xbr, ybr] 픽셀 좌표
```

### ObjectSelector (다중 객체 선택)

```typescript
// objectSelector.ts
export interface SelectionFilter {
    objectType?: string[];           // 'shape' | 'track' | 'tag'
    shapeType?: string[];            // 'rectangle' | 'polygon' 등
    maxCount?: number;               // 최대 선택 가능 수
    restrictToFirstSelectedType?: boolean;
}

export interface ObjectSelector {
    enable(callback: (selected: ObjectState[]) => void, filter?: SelectionFilter): void;
    transform(geometry: Geometry): void;
    push(state: ObjectState): void;  // 클릭한 객체를 선택 목록에 추가
    disable(): void;
    resetSelected(): void;
}
```

## Canvas 외부 인터페이스 API (통합 인터페이스)

`canvas.ts`에 정의된 `Canvas` 인터페이스가 외부에서 사용하는 유일한 공개 API다.

```typescript
// canvas.ts:L22
interface Canvas {
    // 렌더링
    html(): HTMLDivElement;                                          // 캔버스 DOM 루트
    setup(frameData: any, objectStates: any[], zLayer?: number): void; // 프레임/shape 세팅
    setupIssueRegions(issueRegions: Record<number, { hidden: boolean; points: number[] }>): void;
    translateFromSVG(points: number[]): number[];                    // SVG 좌표 → 이미지 좌표

    // 뷰 제어
    activate(clientID: number | null, attributeID?: number): void;   // shape 활성화
    highlight(clientIDs: number[] | null, severity: HighlightSeverity | null): void;
    rotate(rotationAngle: number): void;
    focus(clientID: number, padding?: number): void;
    fit(): void;                                                      // 이미지를 창에 맞춤
    grid(stepX: number, stepY: number): void;

    // 드로잉/편집 모드
    interact(interactionData: InteractionData): void;   // AI 인터랙티브 (SAM 등)
    draw(drawData: DrawData): void;                     // shape 그리기 모드
    edit(editData: MasksEditData | PolyEditData): void; // shape 편집 모드
    group(groupData: GroupData): void;
    join(joinData: JoinData): void;
    slice(sliceData: SliceData): void;   // shape 절단
    split(splitData: SplitData): void;  // track 분리
    merge(mergeData: MergeData): void;  // shapes → track 합치기
    select(objectState: any): void;

    // 캔버스 조작
    fitCanvas(): void;
    bitmap(enable: boolean): void;
    selectRegion(enable: boolean): void;
    dragCanvas(enable: boolean): void;
    zoomCanvas(enable: boolean): void;

    // 상태/설정
    mode(): Mode;
    cancel(): void;           // 현재 모드 취소
    configure(configuration: Configuration): void;
    isAbleToChangeFrame(): boolean;
    destroy(): void;

    readonly geometry: Geometry;  // 현재 스케일/오프셋 등 기하 정보
}
```

### 이벤트 리스너 패턴

Canvas는 DOM 이벤트로 결과를 전달한다. 직접 콜백 대신 canvas DOM 요소에서 CustomEvent를 수신한다.

```typescript
// 사용 예시
const canvasInstance = new Canvas();
const canvasElement = canvasInstance.html();

canvasElement.addEventListener('canvas.drawn', (e: CustomEvent) => {
    const { state } = e.detail;
    // state: { shapeType, points, label, occluded, ... }
    // → 서버에 저장
});

canvasElement.addEventListener('canvas.edited', (e: CustomEvent) => {
    const { state } = e.detail;
});

canvasElement.addEventListener('canvas.canceled', () => {
    // 그리기/편집 취소
});
```

## 핵심 의사결정

- **MVC + Handler 분리**: Model(상태), Controller(API), View(렌더) 3계층에 더해 드로잉 모드별 Handler 클래스를 둬서 상태와 인터랙션 로직을 격리한다.
- **저장 좌표는 항상 픽셀 절대값**: 캔버스 표시는 scale이 적용된 화면 좌표지만 서버 전송 시 원본 픽셀 좌표로 변환한다.
- **MASK 이중 렌더링**: MASK는 SVG.js가 아닌 HTML5 Canvas 2D Context + Fabric.js를 사용하므로 SVG 레이어와 z-index를 병렬 관리한다.
- **DOM 이벤트 기반 결과 전달**: 직접 콜백 대신 `canvas.drawn`, `canvas.edited` 같은 CustomEvent를 통해 외부 코드와 통신한다.
- **UpdateReasons 기반 부분 갱신**: 전체 리렌더링 없이 변경된 SVG 요소만 교체하여 성능을 확보한다.
- **InteractionHandler 비동기 패턴**: AI 모델 호출 → 결과 임시 표시 → 사용자 확인 → 저장의 4단계 흐름을 통일.
- **svg.js v2.x 고정**: `cvat-canvas`는 `svg.js v2.x`를 사용하며, v3.x(현재 `@svgdotjs/svg.js`)와 API가 다르다.
- **viewBox scale**: CSS transform이 아닌 SVG viewBox 조작으로 줌을 구현하므로 retina 디스플레이 대응을 직접 처리한다.

## 독립 포팅 가이드

### 추출 난이도
**상** — `cvat-canvas`는 MVC + 다수 Handler가 강하게 결합된 구조. 단순 좌표 변환(모듈 06)이나 RLE 인코딩(모듈 02)만 떼는 것은 쉽지만, 캔버스 전체를 떼어 내려면 svg.js v2.x 의존 + Configuration + UpdateReasons 등 다수 인터페이스 파일을 같이 옮겨야 함.

### 핵심 추출 대상 (필수 파일)
| 역할 | 원본 경로 | 포팅 시 행수/조치 |
|------|----------|-----------------|
| Canvas 외부 인터페이스 | `cvat-canvas/src/typescript/canvas.ts` | 250줄 — 이 인터페이스만 보존하고 내부 재구현도 가능 |
| 좌표 변환 유틸 | `cvat-canvas/src/typescript/shared.ts` | 800줄 — 모듈 06으로 추출 |
| RLE 인코딩 (MASK) | `cvat-canvas/src/typescript/shared.ts:408-464` | 60줄 — 모듈 02로 추출 |
| Geometry 인터페이스 | `cvat-canvas/src/typescript/canvasModel.ts:32-41` | 단순 dataclass |
| DrawHandler/EditHandler/MasksHandler | `cvat-canvas/src/typescript/drawHandler.ts` 등 | 각 1000~2000줄. 그리기 로직 재구현이 더 빠를 수 있음 |
| InteractionHandler (AI) | `cvat-canvas/src/typescript/interactionHandler.ts` | SAM 등 인터랙티브 호출 패턴만 참고 |

### 외부 라이브러리 의존성
| 라이브러리 | 버전 | 대체 가능? | 비고 |
|----------|------|:--------:|------|
| svg.js | 2.x | 어려움 | v3.x(`@svgdotjs/svg.js`)와 API 다름. v2.x 고정 사용 |
| svg.draw.js | 2.x | 가능 | 그리기 플러그인 |
| fabric | 5.x | 가능 | MASK 브러시 도구 — Canvas 2D context 직접 사용도 가능 |
| point-in-polygon | 1.x | 가능 | 단순 알고리즘 |
| martinez-polygon-clipping | 0.x | 가능 | 폴리곤 boolean ops |
| lru-cache | 7.x | 가능 | 표준 |

### 내부 CVAT 의존성과 분리 방법
| 의존 모듈 | 포팅 시 처리 |
|---------|-----------|
| `cvat-core` (어노테이션 도메인 모델) | 캔버스는 단순 shape 데이터만 받는 인터페이스로 변경 |
| `cvat-ui` (Redux 리듀서) | 상태 관리는 호출자에 위임. 캔버스 자체는 controlled component |

### 최소 동작 단위 (MVP)
- 좌표 변환 + 회전 (모듈 06)으로 시작
- BBOX/POLYGON 그리기만 SVG로 직접 구현 (300~500줄)
- RLE 인코딩 (모듈 02) 별도 적용
- AI 인터랙션은 별도 단계

### 포팅 단계 (체크리스트)
1. [ ] svg.js v2.x 또는 자체 SVG 조작
2. [ ] 좌표 변환 유틸 추출 (모듈 06)
3. [ ] BBOX 그리기 (rectangle drawing handler)
4. [ ] Polygon 그리기 + 점 추가/제거
5. [ ] MASK 브러시 (Canvas 2D + RLE 인코딩 — 모듈 02)
6. [ ] zoom/pan/rotate 기본 동작
7. [ ] CustomEvent로 결과 전달

### 통합 지점 (이 모듈을 다른 시스템에 붙일 때)
- 입력: 이미지 + shape 리스트 + 모드 변경 명령 (`canvas.draw(drawData)` 등)
- 출력: DOM CustomEvent (`canvas.drawn`, `canvas.edited` 등)
- 외부 인터페이스: `Canvas` interface (canvas.ts:22) — html(), setup(), draw(), edit() 등

### 알려진 함정
- **svg.js 버전 호환성**: v2.x와 v3.x(`@svgdotjs/svg.js`)는 import 경로/API 모두 다름. CVAT은 v2.x 고정.
- **fabric monorepo 의존성**: cvat-canvas package.json에 명시 안 되어 있을 수 있음. 단독 추출 시 명시 필요.
- **저장 좌표 변환 누락**: 캔버스 표시는 scale 적용된 좌표지만 저장은 원본 픽셀. 한 군데에서라도 변환 빼먹으면 좌표 어긋남.
- **MASK z-index**: MASK는 SVG 위에 별도 Canvas 2D 레이어. z-index 동기화 직접 관리 필요.
- **getScreenCTM null**: 마운트 직전 호출 시 null. ResizeObserver 등으로 마운트 완료 시점 처리.
- **viewBox 스케일**: CSS transform이 아닌 viewBox 조작으로 줌 → retina 디스플레이 대응 별도 (devicePixelRatio).
- **InteractionHandler 콜백 누수**: AI 모델 호출 결과 대기 중 사용자가 모드 변경 시 listener leak 가능. cleanup 필수.

## Docker 미사용 대응

이 모듈은 프론트엔드 라이브러리이므로 Docker 의존성 없음. `cvat-canvas`는 MIT 라이선스 npm 패키지로 단독 빌드/배포가 가능하다.
