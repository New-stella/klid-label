# 트랙 보간 알고리즘 (Track Interpolation)

## 한 줄 요약
키프레임만 저장된 객체 트랙을 받아, 임의의 중간 프레임에 대해 추정된 shape 좌표(BBOX/POLYGON/POLYLINE/POINTS/ELLIPSE/CUBOID/SKELETON)를 생성하는 순수 알고리즘 모듈.

## 추출 가치
**왜 떼어 쓸 가치가 있는가**
- 비디오 어노테이션 도구의 핵심 기능. 처음부터 만들기 까다로운 부분이 두 가지 있다 — (1) POLYGON 보간(꼭짓점 수가 다른 두 폴리곤을 매칭), (2) 회전(rotation)의 최단 각도 경로(`(angle + 180) % 360 - 180`).
- CVAT 구현은 7년 이상 다듬어졌고, 코너 케이스(트랙 시작/종료 outside 키프레임, 마지막 키프레임 후 propagate 등)가 안정적으로 처리되어 있다.
- `scipy` + `numpy` + `shapely` + `pycocotools` 외에 Django/CVAT 내부 의존이 거의 없다 → 단독 추출이 매우 쉽다.

## 원본 코드 위치

| 역할 | 파일:라인 | 핵심 함수/클래스 |
|------|---------|--------------|
| 보간 알고리즘 본체 | `cvat/apps/dataset_manager/annotation.py:758-1176` | `TrackManager.get_interpolated_shapes(...)` (staticmethod) |
| 트랙 매니저 | `cvat/apps/dataset_manager/annotation.py:607-1196` | `TrackManager` (ObjectManager 상속) |
| AnnotationIR | `cvat/apps/dataset_manager/annotation.py:24-197` | tags/shapes/tracks 컨테이너 |
| ObjectManager 베이스 | `cvat/apps/dataset_manager/annotation.py:334-468` | 매칭/유사도 공통 로직 |
| ShapeType / DimensionType | `cvat/apps/engine/models.py:1365-1380` | enum |
| `faster_deepcopy` 유틸 | `cvat/apps/dataset_manager/util.py:49` | 보간 시 attribute 깊은 복사 |
| `make_getter_by_frame_for_annotation_stream` | `cvat/apps/dataset_manager/util.py:394` | SKELETON elements 매칭용 |

## 알고리즘/프로토콜 핵심

### 입력 트랙의 자료구조 (dict)

```python
track = {
    "id": 20,
    "frame": 0,                           # 트랙 시작 프레임
    "label_id": 3,
    "group": 0,
    "source": "manual",
    "shapes": [                           # 키프레임 시퀀스 (frame 오름차순)
        {
            "id": 100,
            "frame": 0,
            "type": "rectangle",
            "occluded": False,
            "outside": False,             # True면 "이 프레임 이후로 객체 사라짐"
            "z_order": 0,
            "rotation": 0.0,
            "points": [50.0, 50.0, 150.0, 150.0],
            "attributes": [{"spec_id": 1, "value": "blue"}],
        },
        # ...
    ],
    "attributes": [],                     # 트랙 레벨(immutable) 속성
    "elements": [],                       # SKELETON의 하위 element track 리스트
}
```

### 출력 (제너레이터로 생성되는 frame 별 shape dict)

```python
{
    "id": 100,                            # 보간된 shape는 원본 키프레임의 id 복사
    "frame": 5,                           # 이 보간 결과의 프레임 번호
    "type": "rectangle",
    "occluded": False,
    "outside": False,
    "z_order": 0,
    "rotation": 1.5,                      # 회전 보간 결과
    "points": [60.0, 56.0, 160.0, 156.0],
    "attributes": [{"spec_id": 1, "value": "blue"}],
    "keyframe": False,                    # 보간된 frame은 False, 원본 키프레임은 True
}
```

### 형태별 보간 분기 (annotation.py:1068-1090)

```
RECTANGLE / ELLIPSE / CUBOID / SKELETON  → simple_interpolation (선형)
POINTS                                    → points_interpolation
POLYGON / POLYLINE                        → polyshape_interpolation (꼭짓점 매칭)
3D 차원                                   → simple_3d_interpolation (오일러각 보간)
이종 타입 간 보간                         → NotImplementedError
```

### simple_interpolation (annotation.py:809)

```
distance = shape1.frame - shape0.frame
diff = shape1.points - shape0.points
for f in range(shape0.frame+1, shape1.frame):
    offset = (f - shape0.frame) / distance
    points = shape0.points + diff * offset
    # 회전: 최단 각도 경로
    angle_diff = ((shape1.rotation - shape0.rotation + 180) % 360) - 180
    rotation = (shape0.rotation + angle_diff * offset + 360) % 360
    yield copy_shape(shape0, f, points, rotation)
```

### polyshape_interpolation (annotation.py:849-1066)

POLYGON 보간 핵심 4단계:
1. **곡선 길이 정규화** — 양 폴리곤 각 꼭짓점의 누적 거리를 [0, 1] 범위로 변환 (`curve_to_offset_vec`).
2. **left → right 매칭** — 왼쪽 각 꼭짓점에 대해 가장 가까운 오른쪽 꼭짓점을 찾음 (`find_nearest_pair`).
3. **right → left 보완** — 매칭되지 않은 오른쪽 꼭짓점을 가장 가까운 왼쪽 꼭짓점에 추가 매칭.
4. **선형 보간 + 점 감소** — 매칭된 쌍을 선형 보간하고, 거리 기반 임계값으로 불필요한 중간 점 제거.

POLYGON은 닫힌 도형이라 `shape0.points + shape0.points[:2]`로 첫 꼭짓점을 끝에 복사한 뒤 보간하고, 결과에서 다시 떼어낸다.

### outside / propagate / deleted_frames 처리

- **outside=True 키프레임**: 직전 키프레임에서 이 프레임 직전까지 보간을 멈춘다. 단, `include_outside=True`면 outside shape도 전파.
- **마지막 키프레임 이후**: 마지막 키프레임이 outside가 아니면 `end_frame`까지 같은 좌표를 propagate.
- **deleted_frames**: 키프레임이라도 deleted면 보간 대상에서 제외.
- **included_frames**: GT Job처럼 특정 프레임 셋만 추출하는 모드. 출력에서 필터링.

### 회전 최단 경로 핵심 (annotation.py:799-807)

```python
def find_angle_diff(right_angle, left_angle):
    angle_diff = right_angle - left_angle
    angle_diff = ((angle_diff + 180) % 360) - 180
    if abs(angle_diff) >= 180:
        angle_diff = 360 - abs(angle_diff) * -1 if angle_diff > 0 else 1
    return angle_diff
```

## 의존성

### 외부 라이브러리

| 라이브러리 | 버전 | 용도 | 대체 가능? |
|----------|------|------|---------|
| numpy | >=1.22 | 좌표 벡터 연산 | 필수 |
| scipy | >=1.10 | `linear_sum_assignment` (헝가리안) — _objects_similarity에서 사용 | 보간 자체에는 불필요. 트랙 매칭 단계에서만 필요 |
| shapely | >=2.0 | `geometry` (간접) — IR에서 import만 | 보간에는 직접 사용하지 않음 |

순수 보간 함수 `get_interpolated_shapes()`만 추출하면 **numpy만으로 충분**하다.

### CVAT 내부 의존성

| 의존 모듈 | 분리 방법 |
|----------|---------|
| `cvat.apps.dataset_manager.util.faster_deepcopy` | `copy.deepcopy`로 대체 가능. 성능 최적화일 뿐. |
| `cvat.apps.dataset_manager.util.make_getter_by_frame_for_annotation_stream` | SKELETON element 매칭 헬퍼. SKELETON 보간이 필요 없으면 생략 가능. |
| `cvat.apps.engine.models.DimensionType` | `Enum("DimensionType", [("DIM_2D", "2d"), ("DIM_3D", "3d")])` 정의로 대체 |
| `cvat.apps.engine.models.ShapeType` | 위와 같은 방법으로 enum 자체 정의 |
| `cvat.apps.engine.serializers.LabeledDataSerializer` | `AnnotationIR.serialize()`에서만 사용. 추출 시 제거. |

## 단독 추출 예시 코드

```python
# track_interpolation.py — CVAT의 보간 알고리즘만 떼어낸 단독 모듈
from copy import deepcopy
from enum import Enum
from typing import Generator

import numpy as np


class ShapeType(str, Enum):
    RECTANGLE = "rectangle"
    POLYGON = "polygon"
    POLYLINE = "polyline"
    POINTS = "points"
    ELLIPSE = "ellipse"
    CUBOID = "cuboid"
    MASK = "mask"
    SKELETON = "skeleton"


def get_interpolated_shapes(
    track: dict,
    start_frame: int,
    end_frame: int,
    *,
    included_frames: list[int] | None = None,
    deleted_frames: list[int] | None = None,
    include_outside: bool = False,
) -> list[dict]:
    """키프레임 시퀀스만 가진 트랙을 받아 [start_frame, end_frame) 모든 프레임에 대해 보간된 shape를 생성."""
    deleted_frames = deleted_frames or []

    def copy_shape(source, frame, points=None, rotation=None):
        copied = source.copy()
        copied["attributes"] = deepcopy(source["attributes"])
        copied["keyframe"] = False
        copied["frame"] = frame
        if rotation is not None:
            copied["rotation"] = rotation
        if points is None:
            points = list(copied["points"])
        elif isinstance(points, np.ndarray):
            points = points.tolist()
        copied["points"] = points
        return copied

    def find_angle_diff(right, left):
        diff = ((right - left + 180) % 360) - 180
        return diff

    def simple_interpolation(s0, s1):
        distance = s1["frame"] - s0["frame"]
        diff = np.subtract(s1["points"], s0["points"])
        for f in range(s0["frame"] + 1, s1["frame"]):
            offset = (f - s0["frame"]) / distance
            rot = (s0["rotation"] + find_angle_diff(s1["rotation"], s0["rotation"]) * offset + 360) % 360
            pts = s0["points"] + diff * offset
            if included_frames is None or f in included_frames:
                yield copy_shape(s0, f, pts, rot)

    def points_interpolation(s0, s1):
        if len(s0["points"]) == 2 and len(s1["points"]) == 2:
            yield from simple_interpolation(s0, s1)
        else:
            for f in range(s0["frame"] + 1, s1["frame"]):
                if included_frames is None or f in included_frames:
                    yield copy_shape(s0, f)

    def interpolate(s0, s1):
        if s0["type"] != s1["type"]:
            raise NotImplementedError("Cannot interpolate between different shape types")
        t = s0["type"]
        if t in (ShapeType.RECTANGLE, ShapeType.ELLIPSE, ShapeType.CUBOID, ShapeType.SKELETON):
            yield from simple_interpolation(s0, s1)
        elif t == ShapeType.POINTS:
            yield from points_interpolation(s0, s1)
        else:
            # POLYGON / POLYLINE는 단독 모듈로는 polyshape_interpolation 추가 구현 필요
            raise NotImplementedError(f"{t} interpolation not yet ported")

    def propagate(shape, end_f):
        for i in range(shape["frame"] + 1, end_f):
            if included_frames is None or i in included_frames:
                yield copy_shape(shape, i)

    def gen_shapes():
        prev = None
        for s in sorted(track["shapes"], key=lambda x: x["frame"]):
            cur_f = s["frame"]
            if cur_f in deleted_frames:
                continue
            if prev:
                if not prev["outside"] or include_outside:
                    yield from interpolate(prev, s)
            s["keyframe"] = True
            yield s
            prev = s
        if prev and (not prev["outside"] or include_outside):
            yield from propagate(prev, end_frame)

    return [
        s for s in gen_shapes()
        if s["frame"] not in deleted_frames
        and track["frame"] <= s["frame"] < end_frame
        and (s.get("keyframe") or not s["outside"] or include_outside)
        and (included_frames is None or s["frame"] in included_frames)
    ]


# 사용 예
if __name__ == "__main__":
    track = {
        "id": 1, "frame": 0, "label_id": 1, "group": 0, "source": "manual",
        "shapes": [
            {"frame": 0, "type": "rectangle", "occluded": False, "outside": False,
             "z_order": 0, "rotation": 0.0, "points": [0.0, 0.0, 100.0, 100.0],
             "attributes": [], "id": 1},
            {"frame": 10, "type": "rectangle", "occluded": False, "outside": False,
             "z_order": 0, "rotation": 90.0, "points": [50.0, 50.0, 150.0, 150.0],
             "attributes": [], "id": 2},
        ],
        "attributes": [], "elements": [],
    }
    for shape in get_interpolated_shapes(track, 0, 11):
        print(shape["frame"], shape["points"], shape["rotation"], shape.get("keyframe"))
```

POLYGON / 3D 보간이 필요하면 원본 `polyshape_interpolation`/`simple_3d_interpolation`을 그대로 가져와서 같은 패턴으로 추가하면 된다.

## 입력/출력 명세

**입력 트랙(dict)** 필수 필드:
- `frame`: 트랙 시작 프레임 (int)
- `shapes`: 키프레임 리스트 (각 shape는 `frame`, `type`, `points`, `outside`, `rotation`, `attributes` 필드 포함)
- `elements`: SKELETON일 때만 의미 있음 (하위 트랙 리스트, 보통 `[]`)

**출력**: 프레임별 shape dict의 리스트 (또는 streaming=True일 때 제너레이터). 각 shape는 입력 shape 구조 + `keyframe: bool` 필드 추가.

## 통합 가이드 (다른 시스템에 붙이는 법)

1. 이 함수의 호출자는 단일 트랙을 처리한다. 여러 트랙을 한 번에 처리하고 싶으면 `heapq.merge()`로 결과를 frame 기준 병합 (CVAT의 `TrackManager.to_shapes()` 참고).
2. **저장 정책**: 보간 결과는 보통 DB에 저장하지 않고 export/뷰어 시점에 재계산한다. 클라이언트(JS)에서도 같은 알고리즘을 구현하면 실시간 프레임 이동 시 보간 결과를 즉시 표시 가능.
3. 노출할 인터페이스: `get_interpolated_shapes(track, start_frame, end_frame)` 함수 1개로 충분.

## 검증 방법

1. **수동 케이스**: 두 키프레임(frame=0과 frame=10)에 BBOX를 두고, frame=5에서 좌표가 정확히 중간값인지 확인.
2. **회전 최단 경로**: rotation=350° → rotation=10°일 때, 중간 프레임 회전이 0°를 지나가는지 확인 (180° 반대로 도는지가 아닌).
3. **outside 처리**: 키프레임 시퀀스 [(f=0, outside=False), (f=5, outside=True), (f=10, outside=False)]에 대해 f=1~4만 보간되고 f=5~9는 출력되지 않는지 확인.
4. **deleted_frames**: deleted_frames=[3] 전달 시 f=3 결과가 누락되는지 확인.

## 알려진 한계와 함정

- **회전 wrap-around 코너 케이스**: `find_angle_diff()` 마지막 if문(`abs(angle_diff) >= 180`)은 **이론상 도달 불가능**한 코드 (이미 위에서 normalize 했으므로 절대값 < 180). 단, 부동소수점 오차로 정확히 180°일 때 분기 진입 가능 → 회전 정확도 1° 이내가 중요한 경우 별도 검증 필요.
- **이종 타입 보간 불가**: 키프레임끼리 type이 다르면 (예: rectangle ↔ polygon) `NotImplementedError` 발생. 라벨 변경은 트랙을 분리하거나 새 트랙으로 처리해야 함.
- **POLYGON 보간 정확도**: 꼭짓점 수가 크게 다른 두 폴리곤은 매칭 단계에서 정보 손실 가능. 시각적으로 어색해 보일 수 있음.
- **MASK 트랙 보간 불가**: MASK 타입은 보간 분기가 없다. 트랙으로 등록되어도 propagate(직전 키프레임 좌표 복사)만 가능.
- **`outside` 키프레임의 attributes**: outside=True 키프레임의 attributes는 직전 키프레임에서 propagate된 값을 사용한다. 명시적으로 변경하려면 outside=False 키프레임을 별도 추가 필요.
- **시간 복잡도**: POLYGON 보간은 꼭짓점 매칭에 O(N²) — 100개 이상 꼭짓점 폴리곤은 느릴 수 있음.

## 라이선스 주의사항

CVAT는 MIT 라이선스. 추출 시 파일 헤더에 다음 attribution을 권장:
```
# Adapted from CVAT (https://github.com/cvat-ai/cvat)
# Copyright (C) 2019-2022 Intel Corporation
# Copyright (C) CVAT.ai Corporation
# SPDX-License-Identifier: MIT
```
