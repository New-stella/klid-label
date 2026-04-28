# 좌표 변환 / 이미지 회전 / 캔버스 viewBox

## 한 줄 요약
캔버스 어노테이션 도구의 기본기 — 화면 좌표 ↔ 원본 이미지 픽셀 좌표 양방향 변환, 임의 각도 회전, 줌/팬 상태 관리를 위한 TypeScript 유틸리티 함수 모음.

## 추출 가치
**왜 떼어 쓸 가치가 있는가**
- 모든 캔버스 기반 도구가 마주하는 공통 문제 — "사용자가 클릭한 화면 좌표를 원본 이미지 픽셀 좌표로 변환".
- DOM `SVGSVGElement.getScreenCTM()` 활용한 깔끔한 변환 매트릭스 패턴.
- 회전된 사각형의 꼭짓점 계산(`rotate2DPoints`)은 자주 필요하지만 구현 시 중심점 보정을 빼먹기 쉬움.
- 100~200줄 정도의 작은 유틸리티 모음이라 단독 추출 매우 용이.

## 원본 코드 위치

| 역할 | 파일:라인 | 핵심 함수/클래스 |
|------|---------|--------------|
| SVG 좌표 변환 | `cvat-canvas/src/typescript/shared.ts:65-93` | `translateFromSVG`, `translateToSVG` |
| 점 회전 | `cvat-canvas/src/typescript/shared.ts:159-174` | `rotate2DPoints(cx, cy, angle, points)` |
| 캔버스 오프셋 | `cvat-canvas/src/typescript/shared.ts:260-274` | `translateToCanvas`, `translateFromCanvas` |
| 점 ↔ 문자열 | `cvat-canvas/src/typescript/shared.ts:176-244` | `pointsToNumberArray`, `parsePoints`, `stringifyPoints`, `readPointsFromShape` |
| BBOX 계산 | `cvat-canvas/src/typescript/shared.ts:276-310` | `computeWrappingBox` |
| Geometry 인터페이스 | `cvat-canvas/src/typescript/canvasModel.ts:32-41` | `interface Geometry { image, canvas, top, left, scale, offset, angle }` |
| 줌 처리 | `cvat-canvas/src/typescript/canvasModel.ts:466-490` | scale 변경 + top/left 보정 |
| 회전 처리 | `cvat-canvas/src/typescript/canvasModel.ts` | `rotate(rotationAngle)` 메서드 |
| 외부 노출 | `cvat-canvas/src/typescript/canvas.ts:translateFromSVG` | Canvas 인터페이스의 외부 API |

## 알고리즘/프로토콜 핵심

### Geometry 자료 구조

```typescript
interface Size { width: number; height: number; }

interface Geometry {
    image: Size;       // 원본 이미지 크기
    canvas: Size;      // 캔버스 DOM 영역 크기
    grid: Size;        // 격자 그리드 간격
    top: number;       // 캔버스 내부 이미지 top offset (스크롤)
    left: number;      // 캔버스 내부 이미지 left offset
    scale: number;     // 줌 비율 (1.0 = 100%)
    offset: number;    // SVG 마진 (보통 100)
    angle: number;     // 회전 각도 (degree)
}
```

### SVG ↔ Client 좌표 변환 (shared.ts:65-93)

브라우저의 `SVGSVGElement.getScreenCTM()`이 SVG 좌표를 화면 좌표로 변환하는 행렬을 반환. 역변환은 `.inverse()`.

```typescript
export function translateFromSVG(svg: SVGSVGElement, points: ArrayLike<number>): number[] {
    const output = [];
    const transformationMatrix = svg.getScreenCTM() as DOMMatrix;
    let pt = svg.createSVGPoint();
    for (let i = 0; i < points.length - 1; i += 2) {
        pt.x = points[i];
        pt.y = points[i + 1];
        pt = pt.matrixTransform(transformationMatrix);   // SVG → screen
        output.push(pt.x, pt.y);
    }
    return output;
}

export function translateToSVG(svg: SVGSVGElement, points: ArrayLike<number>): number[] {
    const output = [];
    const transformationMatrix = (svg.getScreenCTM() as DOMMatrix).inverse();
    let pt = svg.createSVGPoint();
    for (let i = 0; i < points.length; i += 2) {
        pt.x = points[i];
        pt.y = points[i + 1];
        pt = pt.matrixTransform(transformationMatrix);   // screen → SVG
        output.push(pt.x, pt.y);
    }
    return output;
}
```

핵심: 직접 매트릭스 연산하지 않고 브라우저 내장 SVG 변환 사용 → CSS transform, viewBox, scale이 자동 반영.

### 임의 각도 회전 (shared.ts:159-174)

표준 2D 회전 행렬 (중심점 (cx, cy) 기준):
```
x' = (x - cx) * cos(θ) - (y - cy) * sin(θ) + cx
y' = (y - cy) * cos(θ) + (x - cx) * sin(θ) + cy
```

```typescript
export function rotate2DPoints(cx: number, cy: number, angle: number, points: ArrayLike<number>): number[] {
    const rad = (Math.PI / 180) * angle;
    const cos = Math.cos(rad);
    const sin = Math.sin(rad);
    const result = [];
    for (let i = 0; i < points.length; i += 2) {
        const x = points[i];
        const y = points[i + 1];
        result.push(
            (x - cx) * cos - (y - cy) * sin + cx,
            (y - cy) * cos + (x - cx) * sin + cy,
        );
    }
    return result;
}
```

### 줌 처리 (canvasModel.ts:466-490)

마우스 휠 줌 시 커서 위치를 기준점으로 유지해야 함.
```typescript
const oldScale = this.data.scale;
const scaleFactor = basicZoomCoef ** (-deltaY * adjustCoef);  // 휠 방향에 따라 +/-
const newScale = oldScale * scaleFactor;
this.data.scale = clamp(newScale, FrameZoom.MIN, FrameZoom.MAX);

// 커서 위치 보정 (커서 좌표가 줌 후에도 같은 이미지 픽셀 위에 있도록)
const topMultiplier = (x - imageW / 2) * (oldScale / this.data.scale - 1);
const leftMultiplier = (y - imageH / 2) * (oldScale / this.data.scale - 1);
this.data.top += multiplier * topMultiplier * this.data.scale;
this.data.left -= multiplier * leftMultiplier * this.data.scale;
```

### Rotation 90/180/270 vs 임의 각도

CVAT은 영상 회전(EXIF, 디스플레이 회전)을 0/90/180/270 4단계로 처리하고, shape 회전은 임의 각도(0-360)로 처리한다.
- **이미지 회전 (90/180/270)**: width/height swap + 좌표 변환 매트릭스 적용. `rotate_image()` 헬퍼.
- **Shape 회전 (임의 각도)**: shape 본체는 그대로 두고 SVG transform `rotate(angle, cx, cy)` 적용. 데이터 저장은 회전 전 좌표 + rotation 필드.

### 정수 vs 부동소수점 좌표

CVAT은 좌표를 **float**로 저장 (PointF). 사용자 입력은 정수 픽셀이지만 보간 결과는 fractional.

화면 표시 시:
```typescript
.map((coord: number): number => Math.round(coord))   // shared.ts:141
```

저장 시:
```typescript
.toFixed(1)   // 소수점 1자리
```

### 좌표 검증 (이미지 경계)

```typescript
export function clamp(x: number, min: number, max: number): number {
    return Math.min(Math.max(x, min), max);
}

// 사용:
const x = clamp(rawX, 0, imageWidth);
const y = clamp(rawY, 0, imageHeight);
```

CVAT은 일반적으로 이미지 경계 밖 좌표를 허용하나 (occluded object), drawing 단계에서는 clamp 적용.

## 의존성

### 외부 라이브러리

| 라이브러리 | 버전 | 용도 | 대체 가능? |
|----------|------|------|---------|
| svg.js | ^2.x | SVG DOM 조작 (`SVG.Shape` 타입) | 좌표 변환만 추출하면 불필요 |
| 브라우저 DOM API | (내장) | `SVGSVGElement.getScreenCTM`, `createSVGPoint`, `matrixTransform` | 필수 (브라우저 환경) |

순수 좌표 변환 함수(`translateFromSVG`, `translateToSVG`, `rotate2DPoints`, `pointsToNumberArray`, `stringifyPoints`, `computeWrappingBox`, `clamp`)는 브라우저 DOM만 있으면 동작.

### CVAT 내부 의존성

| 의존 모듈 | 분리 방법 |
|----------|---------|
| `cvat-canvas/src/typescript/consts.ts` | `TEXT_MARGIN` 등 상수만 인라인하면 됨 |
| `cvat-canvas/src/typescript/canvasModel.ts` Geometry 타입 | 위에 인라인 정의된 Geometry 인터페이스 그대로 사용 |

→ 좌표 변환 모듈은 쉘로 200줄 이내, 외부 의존성 거의 없음.

## 단독 추출 예시 코드

```typescript
// coord_utils.ts — CVAT 좌표 변환 유틸리티 단독 모듈
//
// 사용 시나리오:
// - 캔버스에서 사용자 클릭 → 원본 이미지 픽셀 좌표 변환
// - 회전된 BBOX 꼭짓점 계산
// - shape의 외접 사각형 계산

export interface Point { x: number; y: number; }

export interface Box { xtl: number; ytl: number; xbr: number; ybr: number; }

export interface Geometry {
    image: { width: number; height: number };
    canvas: { width: number; height: number };
    top: number;
    left: number;
    scale: number;
    offset: number;
    angle: number;   // 0 | 90 | 180 | 270
}

// SVG 좌표 → 화면 좌표 (브라우저 환경)
export function translateFromSVG(svg: SVGSVGElement, points: ArrayLike<number>): number[] {
    const out: number[] = [];
    const m = svg.getScreenCTM() as DOMMatrix;
    let pt = svg.createSVGPoint();
    for (let i = 0; i < points.length - 1; i += 2) {
        pt.x = points[i]; pt.y = points[i + 1];
        pt = pt.matrixTransform(m);
        out.push(pt.x, pt.y);
    }
    return out;
}

// 화면 좌표 → SVG 좌표
export function translateToSVG(svg: SVGSVGElement, points: ArrayLike<number>): number[] {
    const out: number[] = [];
    const m = (svg.getScreenCTM() as DOMMatrix).inverse();
    let pt = svg.createSVGPoint();
    for (let i = 0; i < points.length; i += 2) {
        pt.x = points[i]; pt.y = points[i + 1];
        pt = pt.matrixTransform(m);
        out.push(pt.x, pt.y);
    }
    return out;
}

// 점 회전 (degree)
export function rotate2DPoints(cx: number, cy: number, angle: number, points: ArrayLike<number>): number[] {
    const rad = (Math.PI / 180) * angle;
    const cos = Math.cos(rad), sin = Math.sin(rad);
    const out: number[] = [];
    for (let i = 0; i < points.length; i += 2) {
        const x = points[i], y = points[i + 1];
        out.push(
            (x - cx) * cos - (y - cy) * sin + cx,
            (y - cy) * cos + (x - cx) * sin + cy,
        );
    }
    return out;
}

// 캔버스 offset 적용 (논리 좌표 → 캔버스 표시 좌표)
export function translateToCanvas(offset: number, points: ArrayLike<number>): number[] {
    const r: number[] = [];
    for (let i = 0; i < points.length; i++) r.push(points[i] + offset);
    return r;
}

export function translateFromCanvas(offset: number, points: ArrayLike<number>): number[] {
    const r: number[] = [];
    for (let i = 0; i < points.length; i++) r.push(points[i] - offset);
    return r;
}

// 점 배열 ↔ 객체 배열 변환
export function pointsToNumberArray(points: string | Point[]): number[] {
    if (Array.isArray(points)) {
        return points.reduce<number[]>((acc, p) => { acc.push(p.x, p.y); return acc; }, []);
    }
    return points.trim().split(/[,\s]+/g).map(Number);
}

export function parsePoints(source: string | number[]): Point[] {
    if (Array.isArray(source)) {
        return source.reduce<Point[]>((acc, _, i) => {
            if (i % 2) acc.push({ x: source[i - 1], y: source[i] });
            return acc;
        }, []);
    }
    return source.trim().split(/\s+/).map(p => {
        const [x, y] = p.split(",").map(Number);
        return { x, y };
    });
}

export function stringifyPoints(points: ArrayLike<number> | Point[]): string {
    if (typeof (points as ArrayLike<number>)[0] === "number") {
        const tmp: string[] = [];
        const arr = points as ArrayLike<number>;
        for (let i = 0; i < arr.length; i += 2) tmp.push(`${arr[i]},${arr[i + 1]}`);
        return tmp.join(" ");
    }
    return (points as Point[]).map(p => `${p.x},${p.y}`).join(" ");
}

// 점 집합의 외접 사각형
export function computeWrappingBox(points: ArrayLike<number>, margin = 0): Box {
    let xtl = Number.MAX_SAFE_INTEGER, ytl = Number.MAX_SAFE_INTEGER;
    let xbr = Number.MIN_SAFE_INTEGER, ybr = Number.MIN_SAFE_INTEGER;
    for (let i = 0; i < points.length; i += 2) {
        xtl = Math.min(xtl, points[i]);
        ytl = Math.min(ytl, points[i + 1]);
        xbr = Math.max(xbr, points[i]);
        ybr = Math.max(ybr, points[i + 1]);
    }
    return { xtl: xtl - margin, ytl: ytl - margin, xbr: xbr + margin, ybr: ybr + margin };
}

export function clamp(x: number, min: number, max: number): number {
    return Math.min(Math.max(x, min), max);
}

// === 응용: 마우스 클릭을 원본 이미지 좌표로 변환 ===
export function clickToImageCoord(
    event: MouseEvent,
    svg: SVGSVGElement,
    geometry: Geometry,
): Point {
    const [svgX, svgY] = translateToSVG(svg, [event.clientX, event.clientY]);
    // SVG 좌표는 이미 viewBox(이미지 픽셀) 기준이므로 그대로 사용
    return { x: clamp(svgX, 0, geometry.image.width), y: clamp(svgY, 0, geometry.image.height) };
}

// === 응용: 회전된 BBOX의 4개 꼭짓점 ===
export function rotatedRectCorners(box: Box, rotationDeg: number): number[] {
    const cx = (box.xtl + box.xbr) / 2;
    const cy = (box.ytl + box.ybr) / 2;
    const corners = [
        box.xtl, box.ytl,
        box.xbr, box.ytl,
        box.xbr, box.ybr,
        box.xtl, box.ybr,
    ];
    return rotate2DPoints(cx, cy, rotationDeg, corners);
}
```

### 사용 예

```typescript
// 1. 마우스 클릭 → 이미지 좌표
const svg = document.querySelector("svg") as SVGSVGElement;
canvas.addEventListener("click", (e) => {
    const { x, y } = clickToImageCoord(e, svg, geometry);
    console.log("Clicked image pixel:", x, y);
});

// 2. 30도 회전된 100x50 BBOX 꼭짓점
const corners = rotatedRectCorners({ xtl: 0, ytl: 0, xbr: 100, ybr: 50 }, 30);
// → [x0, y0, x1, y1, x2, y2, x3, y3]
```

## 입력/출력 명세

### translateFromSVG
- **입력**: `SVGSVGElement`, `[x0, y0, x1, y1, ...]` 점 배열 (SVG viewBox 좌표)
- **출력**: `[x0, y0, x1, y1, ...]` (브라우저 화면 좌표, CSS 픽셀)

### translateToSVG
- **입력**: 화면 좌표 (브라우저 픽셀)
- **출력**: SVG viewBox 좌표 = CVAT의 경우 원본 이미지 픽셀 좌표 (viewBox가 이미지 크기와 일치할 때)

### rotate2DPoints
- **입력**: 회전 중심 (cx, cy), 각도(degree), 점 배열
- **출력**: 회전된 점 배열 (좌표 순서 동일)

### computeWrappingBox
- **입력**: 점 배열, (선택) margin
- **출력**: `{xtl, ytl, xbr, ybr}` (max margin 포함)

## 통합 가이드 (다른 시스템에 붙이는 법)

1. **viewBox 설정**: `<svg viewBox="0 0 ${imageWidth} ${imageHeight}">` 으로 설정하면 SVG 좌표 = 이미지 픽셀 좌표가 됨.
2. **CTM 자동 갱신**: `getScreenCTM()`은 매번 호출 시 최신 CTM 반환 → 줌/팬/회전 후 별도 갱신 코드 불필요.
3. **저장 좌표 정책**: 항상 원본 이미지 픽셀 좌표로 저장. 저장 시 `translateToSVG()` 결과를 그대로 사용 (단, viewBox가 이미지 크기와 같다는 가정).
4. **Canvas 2D vs SVG**: Canvas 2D context를 사용한다면 `getBoundingClientRect()` + 수동 매트릭스 계산 필요. SVG는 `getScreenCTM()`으로 자동.

## 검증 방법

1. **Round-trip**: 임의 SVG 좌표 → translateFromSVG → translateToSVG → 원본과 일치 (오차 < 0.1px).
2. **회전 90°**: `rotate2DPoints(0, 0, 90, [10, 0])` → `[0, 10]`.
3. **회전 360°**: `rotate2DPoints(50, 50, 360, [10, 20])` → `[10, 20]` (원래 위치 복귀).
4. **줌 후 클릭**: 2x 줌 상태에서 캔버스 중앙 클릭 → 이미지 중앙 픽셀 좌표 반환.
5. **외접 BBOX**: 폴리곤 점 배열 → computeWrappingBox → 모든 점이 box 안에 포함.

## 알려진 한계와 함정

- **getScreenCTM null**: SVG가 DOM에 마운트되지 않으면 `getScreenCTM()`이 null 반환. 컴포넌트 마운트 후 호출 필요.
- **CSS transform과 충돌**: SVG 자체에 CSS `transform: rotate(...)`을 적용하면 `getScreenCTM()`이 그 변환까지 포함. CVAT은 SVG 내부 group의 transform 속성으로만 변환.
- **회전 + scale 순서**: 회전 후 scale인지, scale 후 회전인지에 따라 결과 다름. SVG transform attribute는 오른쪽에서 왼쪽으로 적용 (`transform="scale(2) rotate(45)"` → 회전 먼저, 스케일 나중).
- **부동소수점 누적**: 줌/팬/회전을 반복하면 누적 오차 발생. CVAT은 5자리에서 반올림 (`+rotation.toFixed(5)`, shared.ts:117).
- **iOS Safari getScreenCTM**: 일부 iOS 버전에서 `getScreenCTM()`이 부정확. 폴리필 또는 수동 매트릭스 계산 필요.
- **Retina 디스플레이**: `getScreenCTM`은 CSS 픽셀 단위 반환 (devicePixelRatio 고려 안 함). canvas pixelRatio를 별도로 관리 필요한 경우 추가 곱셈.
- **클릭 좌표 vs 이동 좌표**: `event.clientX`는 viewport 기준, `event.pageX`는 document 기준. SVG `getScreenCTM`은 viewport와 호환 → `clientX/Y` 사용 권장.
- **점 개수 짝수 가정**: `pointsToNumberArray`, `rotate2DPoints` 등 모든 함수가 점 개수 짝수 가정. 홀수 개 입력 시 마지막 값 무시 또는 NaN.
- **rotate2DPoints 각도 단위**: degree (CVAT 컨벤션). radian 사용 시 변환 필수.

## 라이선스 주의사항

CVAT MIT. 추출 시:
```typescript
// Adapted from CVAT cvat-canvas (https://github.com/cvat-ai/cvat)
// Copyright (C) 2019-2022 Intel Corporation
// Copyright (C) CVAT.ai Corporation
// SPDX-License-Identifier: MIT
```
