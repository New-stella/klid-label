# MASK ↔ RLE ↔ Polygon 변환

## 한 줄 요약
픽셀 마스크(2D binary array)와 CVAT 자체 RLE 형식, COCO RLE 형식, 폴리곤 사이의 양방향 변환 모듈. SAM/SAM2 등 segmentation 모델 결과를 저장 가능한 형식으로 인코딩하는 핵심 코드.

## 추출 가치
**왜 떼어 쓸 가치가 있는가**
- segmentation 모델(SAM, Mask R-CNN, YOLOv8-seg 등) 결과를 표준 형식으로 저장하려면 RLE 인코딩이 필수다. 처음 만들 때 알고리즘 자체보다 "정확히 같은 인코딩 규칙으로 디코딩"이 어렵다.
- CVAT은 **자체 RLE(시작이 0이라고 가정하는 변형)** + **COCO RLE(pycocotools 호환)** 두 형식의 변환 매트릭스를 가진다.
- 같은 알고리즘이 Python(서버)과 TypeScript(브라우저 캔버스) 양쪽에 구현되어 있어, 직렬화 호환성이 검증되어 있다.

## 원본 코드 위치

| 역할 | 파일:라인 | 핵심 함수/클래스 |
|------|---------|--------------|
| Python: CVAT RLE ↔ Datumaro RLE | `cvat/apps/dataset_manager/formats/transformations.py:54-120` | `MaskConverter.cvat_rle_to_dm_rle`, `dm_mask_to_cvat_rle`, `rle` |
| Python: 마스크 → 폴리곤 변환 옵션 | `cvat/apps/dataset_manager/formats/transformations.py:173-187` | `MaskToPolygonTransformation` (datumaro `masks_to_polygons` 호출) |
| TypeScript: ImageData ↔ RLE | `cvat-canvas/src/typescript/shared.ts:408-464` | `imageDataToRLE`, `RLEToImageData` |
| TypeScript: MASK 핸들러 (브러시) | `cvat-canvas/src/typescript/masksHandler.ts:333-726` | `MasksHandler` 내부의 RLE 인코딩 호출 |
| 마스크 슬라이싱 | `cvat/apps/dataset_manager/formats/cvat.py:740-791` | `open_mask`, `close_mask` (CVAT XML 직렬화) |
| ShapeType.MASK 정의 | `cvat/apps/engine/models.py:1372` | `MASK = 'mask'  # (rle mask, left, top, right, bottom)` |

## 알고리즘/프로토콜 핵심

### CVAT RLE 형식 정의

```
points = [run0, run1, run2, ..., runN, left, top, right, bottom]
```

- 마지막 4개 값이 **타이트 BBOX** (left, top, right, bottom — 모두 inclusive integer pixel)
- 나머지가 **RLE run-length 시퀀스**, **첫 값은 항상 0(off)에서 시작**한다는 가정
- 실제 마스크 크기 = `(right - left + 1) × (bottom - top + 1)`
- 마스크는 BBOX로 잘려서 저장 (전체 이미지 크기 X)

### CVAT RLE 인코딩 규칙 (transformations.py:104-120)

```python
@classmethod
def rle(cls, arr: np.ndarray) -> list[int]:
    """Computes RLE for a flat array (CVAT는 항상 0에서 시작한다고 가정)"""
    n = len(arr)
    if n == 0:
        return []
    pairwise_unequal = arr[1:] != arr[:-1]
    rle = np.diff(np.nonzero(pairwise_unequal)[0], prepend=-1, append=n - 1)
    cvat_rle = rle.tolist()
    if arr[0] != 0:
        cvat_rle.insert(0, 0)   # arr[0]이 1이면 맨 앞에 0 길이 0 run 삽입
    return cvat_rle
```

### TypeScript RLE 인코딩 (shared.ts:408-426)

```typescript
export function imageDataToRLE(imageData: Uint8ClampedArray): number[] {
    const rle = [];
    let prev = 0;          // 항상 0에서 시작 (off)
    let summ = 0;
    // RGBA 4채널 중 alpha(채널 3)만 본다
    for (let i = 3; i < imageData.length; i += 4) {
        const alpha = imageData[i] > 0 ? 1 : 0;
        if (prev !== alpha) {
            rle.push(summ);
            prev = alpha;
            summ = 1;
        } else {
            summ++;
        }
    }
    rle.push(summ);
    return rle;
}
```

→ Python `rle()`과 같은 규칙: 첫 run은 0(off) 픽셀 개수.

### TypeScript RLE → ImageData 디코딩 (shared.ts:428-464)

```typescript
export function RLEToImageData(r, g, b, encoded): Uint8ClampedArray {
    const left = encoded[encoded.length - 4];
    const top = encoded[encoded.length - 3];
    const right = encoded[encoded.length - 2];
    const bottom = encoded[encoded.length - 1];
    return rle2Mask(encoded, right - left + 1, bottom - top + 1);
}
```

### CVAT RLE → COCO RLE 변환 (transformations.py:56-84)

```python
@staticmethod
def cvat_rle_to_dm_rle(shape, img_h: int, img_w: int) -> dm.RleMask:
    left, top, right, bottom = [math.trunc(v) for v in shape.points[-4:]]
    h = bottom - top + 1
    w = right - left + 1
    cvat_as_coco_rle_uncompressed = {
        "counts": shape.points[:-4],   # CVAT RLE는 그대로 COCO uncompressed RLE
        "size": [w, h],                # 주의: width, height 순서
    }
    cvat_as_coco_rle_compressed = mask_utils.frPyObjects(
        [cvat_as_coco_rle_uncompressed], h=h, w=w
    )[0]
    tight_mask = mask_utils.decode(cvat_as_coco_rle_compressed).transpose()
    full_mask = np.zeros((img_h, img_w), dtype=np.uint8)
    full_mask[top : bottom + 1, left : right + 1] = tight_mask
    coco_rle = mask_utils.encode(np.asfortranarray(full_mask))
    return dm.RleMask(rle=coco_rle, ...)
```

핵심 통찰: **CVAT의 raw RLE는 COCO uncompressed RLE와 동일한 의미의 시퀀스**다. 두 형식의 차이는 (1) BBOX vs full-image 좌표계, (2) compressed/uncompressed 인코딩.

### bbox 추출 (Datumaro mask → CVAT RLE — transformations.py:87-101)

```python
@classmethod
def dm_mask_to_cvat_rle(cls, dm_mask) -> list[int]:
    x, y, w, h = dm_mask.get_bbox()
    top, left = int(y), int(x)
    bottom = int(max(y, y + h - 1))
    right = int(max(x, x + w - 1))
    tight_binary_mask = dm_mask.image[top:bottom+1, left:right+1]
    cvat_rle = cls.rle(tight_binary_mask.reshape(-1))   # row-major flatten
    cvat_rle += [left, top, right, bottom]
    return cvat_rle
```

- 입력 `dm_mask.image`는 **row-major (numpy 기본)** 2D binary array.
- `.reshape(-1)`로 row-major 플래튼 → run-length 인코딩.
- BBOX는 inclusive 좌표.

### Mask → Polygon 변환

`MaskToPolygonTransformation`은 datumaro의 `masks_to_polygons` 변환을 호출한다. 내부 알고리즘은 OpenCV의 `findContours()` 기반.

```python
@classmethod
def convert_dataset(cls, dataset, **kwargs):
    if kwargs.get("conv_mask_to_poly", True):
        dataset.transform("masks_to_polygons")
    return dataset
```

직접 구현 시 OpenCV로 한 줄:
```python
contours, _ = cv2.findContours(binary_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
polygons = [c.reshape(-1, 2).tolist() for c in contours]
```

## 의존성

### 외부 라이브러리

| 라이브러리 | 버전 | 용도 | 대체 가능? |
|----------|------|------|---------|
| numpy | >=1.22 | 마스크 연산 | 필수 |
| pycocotools | >=2.0 | COCO RLE 인코딩/디코딩 | COCO 호환이 필요 없으면 생략 가능 |
| opencv-python | >=4.5 | findContours (mask→polygon) | 필수 (mask→polygon만 필요할 때) |
| datumaro | >=1.0 | RleMask/Mask 자료형 | 자체 구조체로 대체 가능 |

### CVAT 내부 의존성

| 의존 모듈 | 분리 방법 |
|----------|---------|
| `dm.RleMask`, `dm.Mask` (datumaro) | `np.ndarray`나 dict로 대체 |
| `shape.points`, `shape.label` 등 datumaro Shape | 단순 dict로 변환해서 전달 |

## 단독 추출 예시 코드

```python
# mask_rle.py — CVAT RLE 인코딩/디코딩만 떼어낸 단독 모듈
import numpy as np


def encode_cvat_rle(binary_mask: np.ndarray) -> list[int]:
    """2D 0/1 mask (numpy) → CVAT RLE [run0, run1, ..., left, top, right, bottom]"""
    if binary_mask.size == 0:
        return [0, 0, 0, 0]
    ys, xs = np.where(binary_mask > 0)
    if len(ys) == 0:
        # 빈 마스크
        return [binary_mask.size, 0, 0, 0, 0]
    top, bottom = int(ys.min()), int(ys.max())
    left, right = int(xs.min()), int(xs.max())
    tight = binary_mask[top:bottom + 1, left:right + 1]
    flat = tight.reshape(-1)

    # RLE 계산 (CVAT 규칙: 첫 run은 항상 0 픽셀)
    n = len(flat)
    pairwise_unequal = flat[1:] != flat[:-1]
    rle = np.diff(np.nonzero(pairwise_unequal)[0], prepend=-1, append=n - 1)
    runs = rle.tolist()
    if flat[0] != 0:
        runs.insert(0, 0)
    return runs + [left, top, right, bottom]


def decode_cvat_rle(rle_with_bbox: list[int], img_h: int, img_w: int) -> np.ndarray:
    """CVAT RLE → 전체 이미지 크기 2D 0/1 mask"""
    left, top, right, bottom = rle_with_bbox[-4:]
    runs = rle_with_bbox[:-4]
    h, w = bottom - top + 1, right - left + 1

    tight = np.zeros(h * w, dtype=np.uint8)
    idx = 0
    val = 0  # 항상 0에서 시작
    for run in runs:
        tight[idx:idx + run] = val
        idx += run
        val = 1 - val

    tight = tight.reshape(h, w)
    full = np.zeros((img_h, img_w), dtype=np.uint8)
    full[top:bottom + 1, left:right + 1] = tight
    return full


def mask_to_polygons(binary_mask: np.ndarray) -> list[list[int]]:
    """2D 0/1 mask → polygon 리스트 (각 polygon은 [x0, y0, x1, y1, ...])"""
    import cv2
    contours, _ = cv2.findContours(
        binary_mask.astype(np.uint8),
        cv2.RETR_EXTERNAL,
        cv2.CHAIN_APPROX_SIMPLE,
    )
    return [c.reshape(-1).tolist() for c in contours if len(c) >= 3]


def polygon_to_mask(polygon: list[int], img_h: int, img_w: int) -> np.ndarray:
    """polygon [x0, y0, x1, y1, ...] → 2D 0/1 mask"""
    import cv2
    mask = np.zeros((img_h, img_w), dtype=np.uint8)
    pts = np.array(polygon, dtype=np.int32).reshape(-1, 2)
    cv2.fillPoly(mask, [pts], 1)
    return mask


# 사용 예
if __name__ == "__main__":
    # 가짜 마스크 (10x10 이미지의 (2,2)-(7,7) 원)
    import cv2
    m = np.zeros((10, 10), dtype=np.uint8)
    cv2.circle(m, (5, 5), 3, 1, -1)

    rle = encode_cvat_rle(m)
    print("CVAT RLE:", rle)
    # [..., left, top, right, bottom]

    decoded = decode_cvat_rle(rle, 10, 10)
    assert (decoded == m).all(), "round-trip failed"
    print("round-trip OK")

    polys = mask_to_polygons(m)
    print(f"{len(polys)} polygon(s), first has {len(polys[0])//2} points")
```

## 입력/출력 명세

### encode_cvat_rle
- **입력**: `binary_mask` — 0/1 값을 가진 2D `np.ndarray` (dtype은 무관, > 0이면 1로 처리)
- **출력**: `list[int]` — `[run0, run1, ..., runN, left, top, right, bottom]`. 빈 마스크면 `[size, 0, 0, 0, 0]`.

### decode_cvat_rle
- **입력**: `rle_with_bbox` — encode 결과, `img_h, img_w` — 복원할 전체 이미지 크기
- **출력**: 전체 이미지 크기의 2D `np.ndarray` (uint8, 0/1)

### COCO RLE와의 호환
CVAT의 raw RLE 시퀀스(`points[:-4]`)는 BBOX 크기로 cropped된 COCO uncompressed RLE의 `counts`와 같은 의미다. 단, COCO는 column-major(transpose)를 가정 — `pycocotools`로 변환 시 transpose 처리 필요(`tight_mask.transpose()` 부분).

## 통합 가이드 (다른 시스템에 붙이는 법)

1. **DB 컬럼 설계**: CVAT처럼 `points` 단일 필드(콤마 구분 텍스트 또는 JSON 배열)에 저장 가능. 또는 `rle` JSON + `bbox` 4컬럼 분리 저장.
2. **AI 모델 결과 처리**: SAM의 `mask` 출력(np.ndarray) → `encode_cvat_rle()` → DB 저장 한 줄.
3. **클라이언트 표시**: 서버에서 RLE 그대로 보내고, 브라우저에서 `RLEToImageData(r, g, b, rle)`로 ImageData 변환 후 Canvas에 그림.
4. **외부 SDK 호환**: COCO 형식으로 export 시 `cvat_rle_to_dm_rle` 패턴으로 pycocotools를 거쳐 표준 COCO RLE 생성.

## 검증 방법

1. **Round-trip 테스트**: 임의 mask → encode → decode → 원본과 비교 (위 예시의 assert).
2. **빈 마스크**: 전부 0인 mask가 정상 인코딩되는지 (`is_empty` 체크용 `len(rle) < 6` 패턴 사용 — masksHandler.ts:641).
3. **단일 픽셀 마스크**: 1픽셀만 켜진 mask가 `[0, 1, 0, 0, x, y, x, y]` 형태로 인코딩되는지.
4. **POLYGON 변환 정확도**: 원형 마스크 → mask_to_polygons → polygon_to_mask → IoU 계산. CHAIN_APPROX_SIMPLE 사용 시 95% 이상이면 정상.

## 알려진 한계와 함정

- **첫 run이 0이라는 가정**: CVAT은 항상 첫 run이 off(0) 픽셀 개수. arr[0]이 1이면 맨 앞에 `0`을 삽입한다 — 이 규칙을 어기면 디코딩 결과가 invert된다.
- **BBOX inclusive**: `right`, `bottom`은 마지막 픽셀의 좌표 (`width = right - left + 1`). 라이브러리에 따라 exclusive를 가정하는 경우가 있어 변환 시 주의.
- **메모리 비용**: 1080p 마스크의 RLE는 보통 수 KB지만, 매우 fragmented한 마스크(체스보드 패턴 등)는 수십 KB까지 늘 수 있다. 일반 객체 마스크는 압축 효과가 크다.
- **POLYGON 변환 손실**: `findContours`는 단순 외곽선만 추출 → 구멍(hole)이 있는 마스크는 정보 손실. RLE→폴리곤은 일방향 변환으로 가정.
- **transpose 함정**: COCO RLE는 column-major (Fortran order)지만 CVAT/numpy는 row-major. 둘을 섞을 때 `np.asfortranarray()` 또는 `.transpose()` 호출 위치를 잘못 잡으면 회전된 결과가 나온다.
- **부동소수점 BBOX**: `points[-4:]`가 float로 저장돼 있어도 `math.trunc()`로 정수 변환. negative 좌표는 직접 처리 필요.

## 라이선스 주의사항

CVAT는 MIT, pycocotools는 BSD-2-Clause, OpenCV는 Apache 2.0. 추출 시:
```python
# Adapted from CVAT (https://github.com/cvat-ai/cvat)
# Copyright (C) 2021-2022 Intel Corporation
# Copyright (C) CVAT.ai Corporation
# SPDX-License-Identifier: MIT
```
