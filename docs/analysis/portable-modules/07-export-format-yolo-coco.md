# YOLO/COCO 변환 모듈

## 한 줄 요약
어노테이션을 가장 널리 쓰이는 두 표준 포맷(YOLO normalized cxcywh, COCO JSON)으로 양방향 변환하는 모듈. CVAT는 datumaro에 위임하지만 핵심 좌표 변환 공식과 구조는 단독으로 떼어 사용 가능.

## 추출 가치
**왜 떼어 쓸 가치가 있는가**
- YOLO와 COCO는 머신러닝 학습 데이터의 사실상 표준. 어떤 어노테이션 도구를 만들든 결국 이 두 포맷으로 export/import 지원이 필요.
- YOLO 좌표 변환(픽셀 절대값 → 정규화 cxcywh)은 단순하지만 한 번 잘못 구현하면 모델 학습이 어긋남. CVAT 공식이 검증되어 있다.
- COCO JSON 구조(categories/images/annotations)는 사양이 명확하지만 segmentation 형식이 polygon vs RLE 두 가지라 헷갈리기 쉽다.
- datumaro 의존성을 분리하고 핵심 변환만 추출하면 100~200라인의 단독 모듈이 된다.

## 원본 코드 위치

| 역할 | 파일:라인 | 핵심 함수/클래스 |
|------|---------|--------------|
| YOLO export 진입점 | `cvat/apps/dataset_manager/formats/yolo.py:30-48` | `_export_common`, `_export_yolo`, `_export_yolo_ultralytics_*` |
| YOLO import 진입점 | `cvat/apps/dataset_manager/formats/yolo.py:51-96` | `_import_common`, `_import_yolo` |
| COCO export | `cvat/apps/dataset_manager/formats/coco.py:27-71` | `_export_instances`, `_export_keypoints` |
| COCO import | `cvat/apps/dataset_manager/formats/coco.py:37-106` | `_import_instances`, `_import_keypoints` |
| MASK ↔ COCO RLE 변환 | `cvat/apps/dataset_manager/formats/transformations.py:54-101` | `MaskConverter` (모듈 02 참조) |
| Mask → Polygon 자동 변환 | `cvat/apps/dataset_manager/formats/transformations.py:173-187` | `MaskToPolygonTransformation` |
| Datumaro ↔ CVAT 바인딩 | `cvat/apps/dataset_manager/bindings.py` (300+줄) | `GetCVATDataExtractor`, `import_dm_annotations`, `CommonData` |
| 포맷 레지스트리 | `cvat/apps/dataset_manager/formats/registry.py` | `@exporter`, `@importer` 데코레이터 |
| 회전된 BBOX → Polygon | `cvat/apps/dataset_manager/formats/transformations.py:15-51` | `RotatedBoxesToPolygons` |

CVAT는 datumaro의 `yolo`, `yolo_ultralytics_detection`, `yolo_ultralytics_segmentation`, `yolo_ultralytics_oriented_boxes`, `yolo_ultralytics_pose`, `yolo_ultralytics_classification`, `coco_instances`, `coco_person_keypoints` 8개 datumaro 플러그인을 호출.

## 알고리즘/프로토콜 핵심

### YOLO (classic) 디렉토리 구조

```
yolo_export/
├── obj.names                  # 라벨 한 줄당 하나
├── obj.data                   # train.txt 등 메타
├── train.txt                  # 이미지 경로 한 줄당 하나
└── obj_train_data/
    ├── frame_000001.jpg       # 이미지 (save_images=True 시)
    └── frame_000001.txt       # 어노테이션 (한 객체당 1줄)
```

### YOLO 좌표 형식 (txt 한 줄)

```
<class_id> <cx> <cy> <w> <h>
```

- 모든 값 공백 구분
- `cx, cy, w, h`는 **이미지 너비/높이로 나눈 정규화 값** [0, 1]
- `cx, cy`는 BBOX 중심점 (좌상단이 아님!)
- `w, h`는 BBOX의 너비/높이 (반지름이 아님)

### YOLO 변환 공식

```
# CVAT (xtl, ytl, xbr, ybr) → YOLO (cx, cy, w, h)
cx = (xtl + xbr) / (2 * image_width)
cy = (ytl + ybr) / (2 * image_height)
w  = (xbr - xtl) / image_width
h  = (ybr - ytl) / image_height

# YOLO → CVAT
xtl = (cx - w/2) * image_width
ytl = (cy - h/2) * image_height
xbr = (cx + w/2) * image_width
ybr = (cy + h/2) * image_height
```

### Ultralytics YOLOv8 디렉토리 구조 (data.yaml)

```yaml
# data.yaml
path: ./dataset
train: images/train
val: images/val

names:
  0: person
  1: car
  2: bicycle
```

```
dataset/
├── data.yaml
├── images/
│   └── train/
│       └── frame_000001.jpg
└── labels/
    └── train/
        └── frame_000001.txt
```

### Ultralytics YOLO Segmentation (정규화 polygon)

```
<class_id> <x0> <y0> <x1> <y1> <x2> <y2> ...
```
모든 좌표가 [0, 1] 정규화. 점 개수는 가변.

### Ultralytics YOLO OBB (Oriented Bounding Box)

```
<class_id> <x0> <y0> <x1> <y1> <x2> <y2> <x3> <y3>
```
회전된 사각형의 4개 꼭짓점. 모두 정규화.

### COCO JSON 구조

```json
{
  "info": {"description": "...", "version": "1.0", "year": 2024},
  "licenses": [],
  "categories": [
    {"id": 1, "name": "person", "supercategory": ""}
  ],
  "images": [
    {
      "id": 1,
      "file_name": "frame_000001.jpg",
      "width": 1920,
      "height": 1080,
      "license": 0
    }
  ],
  "annotations": [
    {
      "id": 1,
      "image_id": 1,
      "category_id": 1,
      "bbox": [100, 200, 200, 150],            // [x, y, width, height] (xywh)
      "area": 30000,
      "segmentation": [[100,200,300,200,300,350,100,350]],  // polygon
      "iscrowd": 0
    },
    {
      "id": 2,
      "image_id": 1,
      "category_id": 1,
      "bbox": [400, 100, 50, 50],
      "area": 2500,
      "segmentation": {                        // RLE 형식 (iscrowd=1)
        "counts": [...],
        "size": [height, width]
      },
      "iscrowd": 1
    }
  ]
}
```

### COCO bbox vs YOLO bbox 차이

| | 형식 | 의미 |
|---|------|------|
| COCO | `[x, y, w, h]` (xywh) | 좌상단 + 너비/높이, 픽셀 절대값 |
| YOLO | `<cx> <cy> <w> <h>` | **중심점** + 너비/높이, **정규화** |

### COCO segmentation 두 형식

```python
# 1) Polygon (iscrowd=0) — 일반 객체
"segmentation": [[x0, y0, x1, y1, ...]]   # 폴리곤 1개 (또는 여러 폴리곤 list)

# 2) RLE (iscrowd=1) — crowd (사람 무리 등) 또는 mask
"segmentation": {
    "counts": "<encoded_string>",          # compressed RLE (pycocotools)
    "size": [height, width]
}
```

### 라벨 ID 매핑 (YOLO 0-based vs COCO 1-based)

- **YOLO**: 클래스 ID는 0부터 시작 (`obj.names`의 라인 번호)
- **COCO**: `category_id`는 1부터 시작 (관행), 0은 보통 background

```python
# CVAT (1-based) → YOLO (0-based)
yolo_class_id = cvat_label_id - 1   # 또는 매핑 테이블 사용

# CVAT → COCO
coco_category_id = cvat_label_id    # 그대로 사용
```

CVAT은 라벨 순서를 `obj.names` 파일/`data.yaml`의 names 순서로 export → import 시 같은 순서가 유지되어야 정확한 매핑.

### POLYGON → YOLO 변환 (정보 손실)

YOLO classic은 BBOX만 지원 → 폴리곤은 외접 BBOX로 변환:
```python
xs = points[0::2]
ys = points[1::2]
xtl, ytl = min(xs), min(ys)
xbr, ybr = max(xs), max(ys)
# 이후 cxcywh 변환
```

Ultralytics YOLO Segmentation은 polygon 그대로 유지.

### MASK → YOLO/COCO 변환

YOLO: 정보 손실 → 외접 BBOX. 또는 segmentation export 시 polygon으로 변환 필요.
```python
# OpenCV findContours로 mask → polygon
contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
polygons = [c.reshape(-1).tolist() for c in contours]
```

COCO: RLE 또는 polygon으로 직접 인코딩 가능 (모듈 02 참조).

## 의존성

### 외부 라이브러리

| 라이브러리 | 버전 | 용도 | 대체 가능? |
|----------|------|------|---------|
| datumaro | >=1.0 | CVAT의 실제 변환 위임 | 직접 구현 시 불필요 |
| pycocotools | >=2.0 | COCO RLE | mask가 없으면 불필요 |
| opencv-python | >=4.5 | mask ↔ polygon 변환 | mask 사용 시 필수 |
| pyunpack | (선택) | YOLO ZIP 자동 압축 해제 | 직접 압축 해제로 대체 가능 |
| Pillow | >=9 | 이미지 저장 (save_images=True) | 필수 (이미지 export 시) |

### CVAT 내부 의존성

| 의존 모듈 | 분리 방법 |
|----------|---------|
| `dataset_manager.bindings.GetCVATDataExtractor` | 어노테이션 IR(LabeledShape, LabeledTrack)에서 직접 변환 |
| `dataset_manager.bindings.import_dm_annotations` | datumaro 데이터셋 → 자체 어노테이션 자료구조 |
| `formats.registry.exporter/importer` | 단순 함수 등록 패턴 → 직접 dispatch |
| `dataset_manager.util.make_zip_archive` | `zipfile.ZipFile` 표준 라이브러리로 대체 |

datumaro 의존성 제거 시 → BBOX/Polygon은 직접 변환 가능, MASK는 pycocotools만으로 변환 가능.

## 단독 추출 예시 코드

```python
# yolo_coco.py — datumaro 의존성 없이 YOLO/COCO 변환만
import json
import os
from pathlib import Path
from typing import Iterable, NamedTuple


class Shape(NamedTuple):
    """단순 어노테이션 자료구조"""
    label_id: int           # 0-based or 1-based (export 시 결정)
    type: str               # "rectangle" | "polygon" | "mask"
    points: list[float]
    image_id: int           # 이미지 인덱스
    iscrowd: int = 0


class ImageInfo(NamedTuple):
    id: int                 # 1-based 권장
    file_name: str
    width: int
    height: int


# === YOLO ===

def export_yolo(
    shapes: Iterable[Shape],
    images: list[ImageInfo],
    label_names: list[str],
    output_dir: Path,
) -> None:
    """YOLO classic 디렉토리 구조 + cxcywh 정규화"""
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "obj.names").write_text("\n".join(label_names))
    (output_dir / "obj.data").write_text(
        f"classes = {len(label_names)}\ntrain = train.txt\nnames = obj.names\n"
    )
    (output_dir / "train.txt").write_text(
        "\n".join(f"obj_train_data/{img.file_name}" for img in images)
    )

    data_dir = output_dir / "obj_train_data"
    data_dir.mkdir(exist_ok=True)

    img_by_id = {img.id: img for img in images}
    shapes_by_image: dict[int, list[Shape]] = {}
    for s in shapes:
        shapes_by_image.setdefault(s.image_id, []).append(s)

    for img in images:
        lines = []
        for s in shapes_by_image.get(img.id, []):
            if s.type == "rectangle":
                xtl, ytl, xbr, ybr = s.points[:4]
            else:
                # polygon → 외접 BBOX
                xs = s.points[0::2]
                ys = s.points[1::2]
                xtl, ytl, xbr, ybr = min(xs), min(ys), max(xs), max(ys)

            cx = (xtl + xbr) / (2 * img.width)
            cy = (ytl + ybr) / (2 * img.height)
            w = (xbr - xtl) / img.width
            h = (ybr - ytl) / img.height
            lines.append(f"{s.label_id} {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}")

        txt_name = Path(img.file_name).stem + ".txt"
        (data_dir / txt_name).write_text("\n".join(lines))


def import_yolo(
    yolo_dir: Path,
    images: list[ImageInfo],
) -> tuple[list[Shape], list[str]]:
    """YOLO classic → Shape 리스트 + 라벨 이름 리스트"""
    label_names = (yolo_dir / "obj.names").read_text().strip().splitlines()

    shapes: list[Shape] = []
    img_by_filename = {img.file_name: img for img in images}

    data_dir = yolo_dir / "obj_train_data"
    for txt_path in data_dir.glob("*.txt"):
        img_filename = txt_path.stem
        # extension 매칭 (jpg/png/...)
        matched = None
        for img in images:
            if Path(img.file_name).stem == img_filename:
                matched = img
                break
        if matched is None:
            continue

        for line in txt_path.read_text().splitlines():
            if not line.strip():
                continue
            parts = line.split()
            cls = int(parts[0])
            cx, cy, w, h = map(float, parts[1:5])
            xtl = (cx - w / 2) * matched.width
            ytl = (cy - h / 2) * matched.height
            xbr = (cx + w / 2) * matched.width
            ybr = (cy + h / 2) * matched.height
            shapes.append(Shape(
                label_id=cls,
                type="rectangle",
                points=[xtl, ytl, xbr, ybr],
                image_id=matched.id,
            ))

    return shapes, label_names


# === COCO ===

def export_coco(
    shapes: Iterable[Shape],
    images: list[ImageInfo],
    label_names: list[str],
    output_path: Path,
) -> None:
    """COCO instances JSON 형식으로 export"""
    coco = {
        "info": {"description": "exported", "version": "1.0"},
        "licenses": [],
        "categories": [
            {"id": i + 1, "name": name, "supercategory": ""}
            for i, name in enumerate(label_names)
        ],
        "images": [
            {"id": img.id, "file_name": img.file_name,
             "width": img.width, "height": img.height, "license": 0}
            for img in images
        ],
        "annotations": [],
    }

    next_id = 1
    for s in shapes:
        if s.type == "rectangle":
            xtl, ytl, xbr, ybr = s.points[:4]
            bbox = [xtl, ytl, xbr - xtl, ybr - ytl]
            seg = [[xtl, ytl, xbr, ytl, xbr, ybr, xtl, ybr]]
            area = (xbr - xtl) * (ybr - ytl)
        elif s.type == "polygon":
            xs = s.points[0::2]; ys = s.points[1::2]
            xtl, ytl, xbr, ybr = min(xs), min(ys), max(xs), max(ys)
            bbox = [xtl, ytl, xbr - xtl, ybr - ytl]
            seg = [s.points]
            area = (xbr - xtl) * (ybr - ytl)  # 정확한 area는 shoelace 공식
        else:
            continue   # mask는 별도 RLE 인코딩 필요

        coco["annotations"].append({
            "id": next_id,
            "image_id": s.image_id,
            "category_id": s.label_id + 1,   # 0-based → 1-based
            "bbox": bbox,
            "area": area,
            "segmentation": seg,
            "iscrowd": s.iscrowd,
        })
        next_id += 1

    output_path.write_text(json.dumps(coco, indent=2))


def import_coco(coco_path: Path) -> tuple[list[Shape], list[ImageInfo], list[str]]:
    """COCO JSON → (shapes, images, label_names)"""
    coco = json.loads(coco_path.read_text())

    label_names = [c["name"] for c in sorted(coco["categories"], key=lambda c: c["id"])]
    images = [
        ImageInfo(id=i["id"], file_name=i["file_name"],
                  width=i["width"], height=i["height"])
        for i in coco["images"]
    ]

    cat_id_to_idx = {c["id"]: i for i, c in enumerate(sorted(coco["categories"], key=lambda c: c["id"]))}

    shapes: list[Shape] = []
    for ann in coco["annotations"]:
        x, y, w, h = ann["bbox"]
        seg = ann.get("segmentation")
        if isinstance(seg, list) and seg and isinstance(seg[0], list) and len(seg[0]) > 8:
            # polygon
            shapes.append(Shape(
                label_id=cat_id_to_idx[ann["category_id"]],
                type="polygon",
                points=seg[0],
                image_id=ann["image_id"],
                iscrowd=ann.get("iscrowd", 0),
            ))
        else:
            # bbox (또는 RLE — 별도 처리)
            shapes.append(Shape(
                label_id=cat_id_to_idx[ann["category_id"]],
                type="rectangle",
                points=[x, y, x + w, y + h],
                image_id=ann["image_id"],
                iscrowd=ann.get("iscrowd", 0),
            ))

    return shapes, images, label_names


# === 사용 예 ===
if __name__ == "__main__":
    images = [ImageInfo(1, "frame_000001.jpg", 1920, 1080)]
    shapes = [
        Shape(label_id=0, type="rectangle", points=[100, 200, 300, 400], image_id=1),
        Shape(label_id=1, type="polygon",
              points=[500, 500, 700, 500, 600, 700], image_id=1),
    ]
    label_names = ["car", "person"]

    export_yolo(shapes, images, label_names, Path("./yolo_out"))
    export_coco(shapes, images, label_names, Path("./coco_out.json"))
```

## 입력/출력 명세

### YOLO export
- **입력**: shapes 리스트 + images 리스트 + label_names + 출력 디렉토리
- **출력 디렉토리 구조**: `obj.names`, `obj.data`, `train.txt`, `obj_train_data/{img}.txt`

### COCO export
- **입력**: 동일
- **출력**: 단일 JSON 파일

### YOLO import
- **입력**: YOLO 디렉토리 경로 + image meta (width/height 필요)
- **출력**: Shape 리스트 + label_names

### COCO import
- **입력**: JSON 파일
- **출력**: Shape + ImageInfo + label_names (이미지 메타까지 포함)

## 통합 가이드 (다른 시스템에 붙이는 법)

1. **자체 어노테이션 모델 → Shape 변환**: 자체 모델의 어노테이션을 위 `Shape` NamedTuple 형식으로 변환하는 어댑터 작성. 한 번만 만들면 yolo/coco 양방향 가능.
2. **이미지 메타 필요**: import 시 정규화 좌표를 픽셀 절대값으로 변환하려면 image width/height가 필수. images 리스트를 별도로 관리하거나 EXIF에서 추출.
3. **Track 처리**: YOLO/COCO 둘 다 비디오 트랙 개념이 약함. CVAT은 `Ultralytics YOLO Detection Track` 포맷에서 `track_id`를 추가 컬럼으로 export. 표준 YOLO/COCO에서는 트랙 정보 손실 가정.
4. **rotation**: COCO는 OBB 미지원. CVAT은 `RotatedBoxesToPolygons` 변환으로 회전된 BBOX를 폴리곤으로 변환 후 export.

## 검증 방법

1. **Round-trip BBOX**: shapes → export YOLO → import → 좌표 일치 (오차 < 0.5px, 정규화 인코딩 후 round-off).
2. **이미지 크기 매칭**: import 시 image width/height가 다르면 좌표가 비례 확장됨 → 잘못된 크기 전달 시 visible artifact.
3. **클래스 수 일치**: obj.names 라인 수 == 라벨 수, COCO categories 길이 == 라벨 수.
4. **빈 어노테이션**: shape이 없는 이미지의 .txt 파일도 빈 파일로 생성되어야 함 (YOLO).
5. **POLYGON 변환**: polygon shape → YOLO export → BBOX로 변환 후 다시 polygon으로 import → 외접 BBOX (원래 polygon 정보 손실 확인).

## 알려진 한계와 함정

- **부동소수점 정밀도**: YOLO 정규화 시 6자리 소수점이 표준 (`f"{cx:.6f}"`). 너무 적으면 1px 이상 오차.
- **filename stem 매칭**: YOLO는 .jpg ↔ .txt를 stem으로 매칭. 한 디렉토리에 같은 stem의 .png와 .jpg가 있으면 충돌.
- **점 개수 가변 polygon**: Ultralytics YOLO Segmentation은 폴리곤마다 점 개수가 다름. COCO segmentation도 마찬가지.
- **iscrowd=1**: COCO에서 segmentation이 RLE면 iscrowd=1. 일반 polygon이면 iscrowd=0. 잘못 설정하면 학습 시 sample 가중치 달라짐.
- **bbox 형식 혼동**: COCO `bbox`는 `[x, y, w, h]` (xywh), YOLO는 `[cx, cy, w, h]` 정규화. RetinaNet 등 일부 모델은 `[x1, y1, x2, y2]` (xyxy) 사용 → 변환 헬퍼 명확히.
- **area 정확도**: COCO `area`는 정확한 폴리곤 면적이 권장 (shoelace 공식). 단순 bbox 면적 대체 시 학습 sampling이 부정확. 위 예제는 단순 bbox 면적 사용.
- **categories 순서**: COCO에서 `category_id` 정수 자체에 의미 있음 (예: COCO 80 클래스는 1=person, 2=bicycle, ...). 임의로 재정렬하면 사전 학습 모델과 호환 안 됨.
- **YOLO 정규화 0~1 범위**: 좌표가 이미지 경계 밖으로 나가면 정규화 값이 0 미만 또는 1 초과 → YOLO 학습 코드가 무시하거나 에러. clamp 처리 권장.
- **Negative bbox**: width/height가 0 또는 negative이면 학습 시 NaN. 필터링 필수.
- **회전된 BBOX → COCO**: COCO는 회전 미지원 → CVAT은 `RotatedBoxesToPolygons` 변환 후 polygon segmentation으로 export. 모델이 box를 기대하면 정보 손실.
- **segmentation 비어있는 경우**: COCO segmentation이 빈 list면 일부 학습 코드가 KeyError. 최소 [bbox 4점 polygon] 형식으로 채우는 게 안전.

## 라이선스 주의사항

CVAT MIT, datumaro MIT, pycocotools BSD-2-Clause. 추출 시:
```python
# Adapted from CVAT (https://github.com/cvat-ai/cvat) dataset_manager/formats
# Copyright (C) 2018-2022 Intel Corporation
# Copyright (C) CVAT.ai Corporation
# SPDX-License-Identifier: MIT
```
