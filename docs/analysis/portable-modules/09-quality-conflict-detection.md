# 어노테이션 품질 충돌 감지

## 한 줄 요약
Ground Truth(정답) 어노테이션과 작업자 어노테이션을 IoU 기반으로 매칭(헝가리안 알고리즘)하고 8가지 충돌 유형(missing/extra/mismatching_label/low_overlap/...)으로 분류하는 자동 검수 알고리즘.

## 추출 가치
**왜 떼어 쓸 가치가 있는가**
- 검수 자동화의 핵심 — 수십 명의 라벨러 결과를 자동 점수화하여 우수/저조 라벨러 구분, 작업자 교육에 활용 가능.
- 단순 IoU > threshold 매칭으로는 다대일/일대다 매칭에서 잘못된 결과가 나온다. CVAT은 헝가리안 알고리즘(`scipy.optimize.linear_sum_assignment`)으로 최적 매칭을 보장.
- 8가지 충돌 유형은 실무에서 발견된 실제 케이스의 분류 (모두 만들기는 어려움) — missing/extra/label_wrong/low_overlap/direction(polyline)/attributes/groups/covered.
- BBOX/Polygon/Mask/Points/Skeleton 모든 shape 타입에 대해 통일된 매칭 인터페이스 (`distance` 함수 + `linear_sum_assignment`).

## 원본 코드 위치

| 역할 | 파일:라인 | 핵심 함수/클래스 |
|------|---------|--------------|
| 충돌 타입 enum | `cvat/apps/quality_control/models.py:23-50` | `AnnotationConflictType`, `AnnotationConflictSeverity` |
| 매칭 알고리즘 (general) | `cvat/apps/quality_control/quality_reports.py:982-1042` | `match_segments(...)` (헝가리안) |
| OKS (점 유사도) | `cvat/apps/quality_control/quality_reports.py:1045-1076` | `oks(a, b, sigma, ...)` |
| Mask/Polygon IoU | `cvat/apps/quality_control/quality_reports.py:1117-1140` | `segment_iou(a, b, img_h, img_w)` |
| Polyline 매칭 | `cvat/apps/quality_control/quality_reports.py:1136-1280` | `LineMatcher` |
| Distance Comparator | `cvat/apps/quality_control/quality_reports.py:1283-1900` | `DistanceComparator.match_boxes/match_segmentations/match_points/...` |
| 비교 파라미터 | `cvat/apps/quality_control/quality_reports.py:248-342` | `ComparisonParameters` (iou_threshold=0.4, low_overlap_threshold=0.8 등) |
| 충돌 객체 | `cvat/apps/quality_control/quality_reports.py:206-244` | `AnnotationConflict(frame_id, type, annotation_ids)` |
| Confusion Matrix | `cvat/apps/quality_control/quality_reports.py:345-441` | `ConfusionMatrix` (precision/recall/accuracy/jaccard) |
| Comparator | `cvat/apps/quality_control/quality_reports.py:1910-2073` | `_Comparator`, `DatasetComparator` |
| QualityReport DB 모델 | `cvat/apps/quality_control/models.py:91-228` | `QualityReport`, `QualitySettings`, `AnnotationConflict` |
| Job 데이터 로더 | `cvat/apps/quality_control/quality_reports.py:874-980` | `JobDataProvider` |

## 알고리즘/프로토콜 핵심

### 8가지 충돌 유형 (AnnotationConflictType)

```python
class AnnotationConflictType(str, Enum):
    MISSING_ANNOTATION    = "missing_annotation"     # GT에는 있는데 작업자가 누락
    EXTRA_ANNOTATION      = "extra_annotation"       # 작업자가 추가했는데 GT에 없음
    MISMATCHING_LABEL     = "mismatching_label"      # 매칭은 되었지만 label이 다름
    LOW_OVERLAP           = "low_overlap"            # IoU 낮음 (0.4 < IoU < 0.8 — warning)
    MISMATCHING_DIRECTION = "mismatching_direction"  # polyline 방향 반대
    MISMATCHING_ATTRIBUTES = "mismatching_attributes" # 속성 값 다름
    MISMATCHING_GROUPS    = "mismatching_groups"     # 그룹 ID 다름
    COVERED_ANNOTATION    = "covered_annotation"     # z-order로 가려진 주석 (visible_area < threshold)
```

### Severity 자동 분류 (quality_reports.py:212-230)

```python
@property
def severity(self):
    if self.type in [MISSING, EXTRA, MISMATCHING_LABEL]:
        return ERROR
    elif self.type in [LOW_OVERLAP, MISMATCHING_ATTRIBUTES, MISMATCHING_DIRECTION,
                       MISMATCHING_GROUPS, COVERED_ANNOTATION]:
        return WARNING
```

### 핵심 알고리즘: match_segments (quality_reports.py:982-1042)

```python
def match_segments(a_segms, b_segms, *, distance, dist_thresh=1.0, label_matcher):
    # 1. 거리 행렬 생성 (1 - IoU)
    max_anns = max(len(a_segms), len(b_segms))
    distances = np.array([
        [
            1 - distance(a, b) if a is not None and b is not None else 1
            for b, _ in itertools.zip_longest(b_segms, range(max_anns), fillvalue=None)
        ]
        for a, _ in itertools.zip_longest(a_segms, range(max_anns), fillvalue=None)
    ])
    distances[~np.isfinite(distances)] = 1
    distances[distances > 1 - dist_thresh] = 1   # threshold 미달은 매칭 불가능

    # 2. 헝가리안 알고리즘 (최적 매칭)
    if a_segms and b_segms:
        a_matches, b_matches = linear_sum_assignment(distances)
    else:
        a_matches, b_matches = [], []

    # 3. 매칭 결과를 4개 카테고리로 분류
    matches = []      # 라벨 일치 + IoU 통과
    mispred = []      # IoU 통과했지만 라벨 불일치 (mismatching_label)
    a_unmatched = []  # GT에서 매칭 실패 → missing_annotation
    b_unmatched = []  # DS에서 매칭 실패 → extra_annotation

    for a_idx, b_idx in zip(a_matches, b_matches):
        dist = distances[a_idx, b_idx]
        if dist > 1 - dist_thresh or dist == 1:
            if a_idx < len(a_segms): a_unmatched.append(a_segms[a_idx])
            if b_idx < len(b_segms): b_unmatched.append(b_segms[b_idx])
        else:
            a_ann = a_segms[a_idx]; b_ann = b_segms[b_idx]
            if label_matcher(a_ann, b_ann):
                matches.append((a_ann, b_ann))
            else:
                mispred.append((a_ann, b_ann))

    return matches, mispred, a_unmatched, b_unmatched
```

핵심 통찰: **라벨 비교는 매칭 후에**. 거리 행렬은 라벨과 무관하게 IoU만으로 만들고, 매칭된 쌍이 라벨까지 같으면 valid match, 아니면 mismatching_label.

### Shape 타입별 distance 함수

| 타입 | distance 함수 | 라이브러리 |
|------|-------------|----------|
| BBOX | `bbox_iou(a, b)` | datumaro 내장 (`max(0, x_overlap) * max(0, y_overlap) / (area_a + area_b - intersection)`) |
| Polygon/Mask | `segment_iou(a, b, img_h, img_w)` | pycocotools (RLE 변환 후 `mask_utils.iou`) |
| Polyline | `LineMatcher.distance` (line torso radius 기반) | 자체 구현 |
| Points | `oks(a, b, sigma=0.09, bbox=...)` | COCO OKS 공식 |
| Skeleton | element 별 OKS 평균 | KeypointsMatcher |

### segment_iou (polygon/mask 통합 — quality_reports.py:1117-1140)

```python
def segment_iou(a: dm.Annotation, b: dm.Annotation, *, img_h: int, img_w: int) -> float:
    """Generic IoU for masks and polygons. -1 if no intersection."""
    from pycocotools import mask as mask_utils

    a = to_rle(a, img_h=img_h, img_w=img_w)   # RLE로 통일
    b = to_rle(b, img_h=img_h, img_w=img_w)
    # mask_utils.iou expects (dt, gt, iscrowd_array)
    return mask_utils.iou(b, a, [0])[0][0]
```

### OKS (Object Keypoint Similarity)

```python
def oks(a, b, sigma=0.1, bbox=None, scale=None, ...):
    """
    OKS = Σ exp(-d²/(2*scale*(2σ)²)) / num_visible
    """
    p1 = np.array(a.points).reshape((-1, 2))
    p2 = np.array(b.points).reshape((-1, 2))
    if len(p1) != len(p2):
        return 0
    if not scale:
        bbox = mean_bbox([a, b])
        scale = bbox[2] * bbox[3]
    dists = np.linalg.norm(p1 - p2, axis=1)
    return np.sum(visibility * np.exp(...)) / np.sum(visibility | other_visibility)
```

### Confusion Matrix → 메트릭

```python
# quality_reports.py:359-387
matched_ann_counts = np.diag(confusion_matrix)        # 대각선 = TP
ds_ann_counts = np.sum(confusion_matrix, axis=1)      # 행 합계 = 작업자 총 어노테이션
gt_ann_counts = np.sum(confusion_matrix, axis=0)      # 열 합계 = GT 총 어노테이션

precision = matched / ds_count                         # TP / (TP + FP)
recall = matched / gt_count                            # TP / (TP + FN)
jaccard_index = matched / (ds_count + gt_count - matched)  # IoU
accuracy = (total - (ds_count - matched) - (gt_count - matched)) / total
```

### 핵심 파라미터 (ComparisonParameters)

| 파라미터 | 기본값 | 의미 |
|---------|------|------|
| `iou_threshold` | 0.4 | 매칭 vs unmatch 경계 |
| `low_overlap_threshold` | 0.8 | LOW_OVERLAP 경고 경계 (이 이하 + iou_threshold 이상이면 warning) |
| `oks_sigma` | 0.09 | 키포인트 매칭 표준편차 (% of bbox area) |
| `line_thickness` | 0.01 | polyline IoU 계산 시 굵기 (이미지 면적의 √의 1%) |
| `compare_line_orientation` | True | polyline 방향 검사 |
| `line_orientation_threshold` | 0.1 | 역방향 IoU 향상 임계 |
| `compare_groups` | True | 그룹 일치 검사 |
| `check_covered_annotations` | True | 가려진 주석 (z-order) 검사 |
| `object_visibility_threshold` | 0.05 | covered 판정 (visible area %) |
| `panoptic_comparison` | True | mask/polygon 비교 시 가려진 부분 제외 |
| `compare_attributes` | True | 속성 값 비교 |

### GT Job 구조

```python
# cvat/apps/engine/models.py
class JobType(str, Enum):
    ANNOTATION = "annotation"
    GROUND_TRUTH = "ground_truth"     # Task당 1개만 허용
    CONSENSUS_REPLICA = "consensus_replica"

class SegmentType(str, Enum):
    RANGE = "range"
    SPECIFIC_FRAMES = "specific_frames"   # GT Job에 사용 (특정 프레임 지정)

# Validation 모드
ValidationMode.GT       → 별도 GT 전용 segment
ValidationMode.GT_POOL  → Honeypot (일반 작업에 GT 프레임 섞기)
```

GT Job은 일반 Job과 동일 구조지만 `type='ground_truth'`. 어노테이션이 정답으로 사용된다.

### 비교 파이프라인

```
1. JobDataProvider.load_dataset(gt_job)  → datumaro Dataset (GT)
2. JobDataProvider.load_dataset(ds_job)  → datumaro Dataset (작업자)
3. DatasetComparator.compare(gt, ds)
   for frame in shared_frames:
     for ann_type in [bbox, polygon, mask, ...]:
       a_objs = gt.frame_anns
       b_objs = ds.frame_anns
       matches, mispred, a_unmatch, b_unmatch = match_segments(a_objs, b_objs, ...)
       # 충돌 생성
       for a, b in mispred:           AnnotationConflict(MISMATCHING_LABEL)
       for a, b in matches:
           if iou < low_overlap:      AnnotationConflict(LOW_OVERLAP)
           if attrs differ:           AnnotationConflict(MISMATCHING_ATTRIBUTES)
           if groups differ:          AnnotationConflict(MISMATCHING_GROUPS)
       for a in a_unmatch:            AnnotationConflict(MISSING_ANNOTATION)
       for b in b_unmatch:            AnnotationConflict(EXTRA_ANNOTATION)
4. ConfusionMatrix 누적 → precision/recall/accuracy
5. QualityReport.data = JSON serialize
```

## 의존성

### 외부 라이브러리

| 라이브러리 | 버전 | 용도 | 대체 가능? |
|----------|------|------|---------|
| numpy | >=1.22 | 거리 행렬 + IoU 연산 | 필수 |
| scipy | >=1.10 | `linear_sum_assignment` | 필수 |
| pycocotools | >=2.0 | mask IoU (RLE 기반) | mask 사용 시 필수 |
| shapely | >=2.0 | polygon 정확한 IoU | 자체 구현 가능하나 비효율 |
| opencv-python | >=4.5 | mask 전처리 | 마스크 사용 시 필수 |
| datumaro | >=1.0 | DatasetItem, Annotation 자료형 + matcher 베이스 | 단독 추출 시 자체 자료구조로 대체 가능 |

### CVAT 내부 의존성 (단독 추출 시 처리)

| 의존 모듈 | 분리 방법 |
|----------|---------|
| `quality_control.models.QualitySettings` | 위 ComparisonParameters를 dataclass로 정의 |
| `quality_control.models.AnnotationConflict` (DB) | 단순 dataclass로 대체 |
| `engine.models.Job, ShapeType` | enum 자체 정의 |
| `JobDataProvider` (CVAT 어노테이션 → datumaro Dataset) | 자체 어노테이션 → 자체 자료구조 어댑터 작성 |

⚠️ **단독 추출 난이도 (중간)**: GT Job 인프라 자체는 CVAT 모델에 깊게 묶여 있어서 GT Job 모델 + Segment(SPECIFIC_FRAMES) + Job 할당까지 동시에 설계해야 한다. 알고리즘만 가져와 자체 어노테이션 모델에 적용하는 것은 가능.

## 단독 추출 예시 코드 — match_segments + IoU만

```python
# quality_match.py — CVAT의 핵심 매칭 알고리즘만 추출
from dataclasses import dataclass
from enum import Enum
from typing import Callable, NamedTuple, TypeVar

import itertools
import numpy as np
from scipy.optimize import linear_sum_assignment


_S = TypeVar("_S")


class ConflictType(str, Enum):
    MISSING = "missing_annotation"
    EXTRA = "extra_annotation"
    MISMATCHING_LABEL = "mismatching_label"
    LOW_OVERLAP = "low_overlap"


@dataclass
class Conflict:
    type: ConflictType
    gt_id: int | None
    ds_id: int | None
    iou: float | None = None


@dataclass
class Shape:
    id: int
    label: str
    points: list[float]   # [xtl, ytl, xbr, ybr] (rectangle만 가정)


def bbox_iou(a: Shape, b: Shape) -> float:
    ax0, ay0, ax1, ay1 = a.points
    bx0, by0, bx1, by1 = b.points
    ix0 = max(ax0, bx0)
    iy0 = max(ay0, by0)
    ix1 = min(ax1, bx1)
    iy1 = min(ay1, by1)
    iw = max(0.0, ix1 - ix0)
    ih = max(0.0, iy1 - iy0)
    inter = iw * ih
    area_a = max(0.0, (ax1 - ax0) * (ay1 - ay0))
    area_b = max(0.0, (bx1 - bx0) * (by1 - by0))
    union = area_a + area_b - inter
    return inter / union if union > 0 else 0.0


def match_segments(
    a_segms: list[_S],          # GT
    b_segms: list[_S],          # 작업자(DS)
    *,
    distance: Callable[[_S, _S], float],
    dist_thresh: float = 0.4,
    label_matcher: Callable[[_S, _S], bool],
):
    """CVAT의 match_segments를 단순화한 버전. 헝가리안 매칭."""
    max_anns = max(len(a_segms), len(b_segms))
    distances = np.array([
        [
            1 - distance(a, b) if a is not None and b is not None else 1
            for b, _ in itertools.zip_longest(b_segms, range(max_anns), fillvalue=None)
        ]
        for a, _ in itertools.zip_longest(a_segms, range(max_anns), fillvalue=None)
    ])
    distances[~np.isfinite(distances)] = 1
    distances[distances > 1 - dist_thresh] = 1

    if a_segms and b_segms:
        a_matches, b_matches = linear_sum_assignment(distances)
    else:
        a_matches = []
        b_matches = []

    matches = []      # 매칭 + 라벨 일치
    mispred = []      # 매칭 + 라벨 불일치
    a_unmatched = []
    b_unmatched = []

    for ai, bi in zip(a_matches, b_matches):
        dist = distances[ai, bi]
        if dist > 1 - dist_thresh or dist == 1:
            if ai < len(a_segms): a_unmatched.append(a_segms[ai])
            if bi < len(b_segms): b_unmatched.append(b_segms[bi])
        else:
            a, b = a_segms[ai], b_segms[bi]
            (matches if label_matcher(a, b) else mispred).append((a, b, 1 - dist))

    if not len(a_matches) and not len(b_matches):
        a_unmatched = list(a_segms)
        b_unmatched = list(b_segms)

    return matches, mispred, a_unmatched, b_unmatched


def detect_conflicts(
    gt_shapes: list[Shape],
    ds_shapes: list[Shape],
    *,
    iou_threshold: float = 0.4,
    low_overlap_threshold: float = 0.8,
) -> list[Conflict]:
    """GT와 DS 어노테이션 비교 → 충돌 리스트"""
    matches, mispred, a_unmatch, b_unmatch = match_segments(
        gt_shapes,
        ds_shapes,
        distance=bbox_iou,
        dist_thresh=iou_threshold,
        label_matcher=lambda a, b: a.label == b.label,
    )

    conflicts: list[Conflict] = []

    for gt, ds, iou in matches:
        if iou < low_overlap_threshold:
            conflicts.append(Conflict(ConflictType.LOW_OVERLAP, gt.id, ds.id, iou))

    for gt, ds, iou in mispred:
        conflicts.append(Conflict(ConflictType.MISMATCHING_LABEL, gt.id, ds.id, iou))

    for gt in a_unmatch:
        conflicts.append(Conflict(ConflictType.MISSING, gt.id, None))

    for ds in b_unmatch:
        conflicts.append(Conflict(ConflictType.EXTRA, None, ds.id))

    return conflicts


def compute_metrics(gt_count: int, ds_count: int, valid_count: int) -> dict:
    """precision/recall/accuracy"""
    return {
        "valid_count": valid_count,
        "gt_count": gt_count,
        "ds_count": ds_count,
        "precision": valid_count / ds_count if ds_count else 0,
        "recall": valid_count / gt_count if gt_count else 0,
        "accuracy": valid_count / (gt_count + ds_count - valid_count)
                    if (gt_count + ds_count - valid_count) else 0,
    }


# === 사용 예 ===
if __name__ == "__main__":
    gt = [
        Shape(id=1, label="car", points=[100, 100, 200, 200]),
        Shape(id=2, label="person", points=[300, 300, 350, 400]),
    ]
    ds = [
        # 정답에 가까운 car (IoU ~0.85, valid)
        Shape(id=10, label="car", points=[105, 105, 205, 205]),
        # person 위치는 비슷하지만 label이 dog (mismatching_label)
        Shape(id=11, label="dog", points=[295, 295, 355, 405]),
        # GT에 없는 추가 객체 (extra_annotation)
        Shape(id=12, label="bicycle", points=[500, 500, 600, 600]),
    ]

    conflicts = detect_conflicts(gt, ds)
    for c in conflicts:
        print(c)

    metrics = compute_metrics(
        gt_count=len(gt), ds_count=len(ds),
        valid_count=sum(1 for c in conflicts if False) + 1,  # car 1개 valid
    )
    print(metrics)
```

POLYGON/MASK 비교가 필요하면 `bbox_iou`를 `pycocotools.mask.iou` 호출로 교체.

## 입력/출력 명세

### 입력
- `gt_shapes`: 정답 어노테이션 리스트 (각 shape은 id, label, points 필드)
- `ds_shapes`: 작업자 어노테이션 리스트
- 비교 파라미터 (iou_threshold, low_overlap_threshold 등)

### 출력
- 충돌 리스트 (각 conflict는 type, gt_id, ds_id, iou)
- (선택) 메트릭 dict (precision, recall, accuracy)

## 통합 가이드 (다른 시스템에 붙이는 법)

1. **GT 어노테이션 인프라**: 단순히 같은 task에 두 종류의 어노테이션(작업자 vs GT)을 저장할 수 있는 모델 필요. CVAT처럼 "Job 단위로 type 분리"가 깔끔.
2. **frame 단위 매칭**: 같은 프레임의 어노테이션끼리만 비교. 다른 프레임 비교는 의미 없음.
3. **결과 시각화**: 충돌 정보에 좌표 정보(`gt.points`, `ds.points`)를 포함하면 캔버스에 highlight로 표시 가능.
4. **점수 캐싱**: 매번 계산하지 않고 `QualityReport.data` JSONField에 저장. GT가 변경되면 dirty 플래그로 재계산.
5. **batched 처리**: 100개 이상의 작업자 결과를 배치로 비교 시 RQ 워커(`quality_reports` 큐)에 위임 (모듈 08 참조).

## 검증 방법

1. **완벽 매칭**: 같은 어노테이션 두 개 비교 → 충돌 0개, accuracy=1.0.
2. **완전 불일치**: GT 5개 + DS 0개 → 5개 missing, recall=0, precision=0.
3. **라벨 swap**: GT [car, person] vs DS [person, car] (위치 같음) → 2개 mismatching_label 충돌.
4. **다대일 매칭**: GT 1개 + DS 3개 (모두 위치 비슷) → 1개 매치 + 2개 extra (헝가리안이 잘 골라야 함).
5. **threshold 동작**: iou_threshold=0.5에서 매칭되던 두 shape이 iou_threshold=0.9에서는 unmatch.

## 알려진 한계와 함정

- **헝가리안 시간복잡도**: `linear_sum_assignment`는 O(N³). 한 프레임에 100+ 어노테이션이면 느려진다. CVAT은 frame당 분리 처리.
- **거리 행렬 메모리**: NxN float64 → 1000개 어노테이션이면 8MB. 큰 데이터셋은 메모리 주의.
- **label_matcher 기본**: `a.label == b.label` 사용. 다른 시스템에서는 label_id로 비교 필요.
- **outside 처리**: CVAT은 `attributes.get("outside", False)`인 어노테이션은 매칭 대상에서 제외. 단순 추출 시 별도 필터링 필요.
- **distance vs similarity**: distance는 `1 - IoU`. similarity 0~1 범위 함수를 distance로 변환할 때 부호 헷갈리기 쉬움.
- **dist_thresh 의미**: `distances[distances > 1 - dist_thresh] = 1`. dist_thresh=0.4면 IoU < 0.4인 모든 쌍은 매칭 후보에서 배제 (거리 1로 강제). 이후 헝가리안은 이런 거리 1 쌍은 절대 선택 안 함.
- **POLYGON IoU 비용**: shapely 폴리곤 IoU는 점 개수에 따라 O(N²). 100개 점 폴리곤 1000개 매칭 → 매우 느림. mask로 변환 후 pycocotools 사용이 보통 더 빠름.
- **OKS sigma 튜닝**: 0.09는 COCO person 기준. 다른 객체 타입은 별도 sigma 필요. 너무 작으면 매칭 불가, 너무 크면 모든 점이 매칭.
- **Mask vs Polygon IoU 일관성**: 같은 모양을 mask로 표현 vs polygon으로 표현 시 IoU 미세 차이 가능 (rasterization 정밀도). 동일 type끼리만 비교 권장.
- **빈 비교**: GT나 DS가 0개일 때 metric은 0/0. CVAT은 0으로 처리(`empty_is_annotated=True` 옵션 있음).
- **z-order/covered**: COVERED_ANNOTATION은 z-order로 가려진 주석을 감지. 단순 IoU만으로는 가려진 부분의 visible area를 알 수 없음 → mask 기반 panoptic comparison 필요.
- **polyline 방향**: polyline은 시작점/끝점 의미가 있음. CVAT은 양방향 IoU 비교 후 차이로 MISMATCHING_DIRECTION 판정.

## 라이선스 주의사항

CVAT MIT, scipy BSD-3-Clause, pycocotools BSD-2-Clause, shapely BSD-3-Clause. 추출 시:
```python
# Adapted from CVAT (https://github.com/cvat-ai/cvat) apps/quality_control
# Copyright (C) CVAT.ai Corporation
# SPDX-License-Identifier: MIT
```
