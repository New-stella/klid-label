# 트랙 및 보간

## 개요
CVAT의 Track은 비디오에서 동일 객체를 여러 프레임에 걸쳐 추적하는 구조다. 키프레임(keyframe)에만 실제 좌표를 저장하고, 중간 프레임은 서버/클라이언트에서 보간(interpolation)을 통해 계산한다.

## 핵심 코드 위치
| 역할 | 파일 경로 | 비고 |
|------|---------|------|
| Track 모델 | `cvat/apps/engine/models.py:L1436` | LabeledTrack, TrackedShape |
| 보간 알고리즘 | `cvat/apps/dataset_manager/annotation.py:L758` | TrackManager.get_interpolated_shapes |
| AnnotationIR | `cvat/apps/dataset_manager/annotation.py:L21` | tracks/shapes/tags 중간 표현 |
| TrackManager | `cvat/apps/dataset_manager/annotation.py` | 트랙 관련 연산 |

## Track 객체 구조 {#track-model}

### DB 모델

```python
# cvat/apps/engine/models.py:L1436
class LabeledTrack(Annotation):    # Annotation 상속
    parent = ForeignKey('self', null=True)  # SKELETON의 element track
    # frame: 트랙 시작 프레임 (Annotation.frame 상속)
    # job, label, group, source: Annotation에서 상속

class TrackedShape(Shape):         # Shape 상속
    id    = BigAutoField(primary_key=True)
    track = ForeignKey(LabeledTrack, related_name='shapes')
    frame = PositiveIntegerField   # 이 키프레임의 프레임 번호
    # type, points, occluded, outside, z_order, rotation: Shape에서 상속
```

### API 표현

```json
{
    "id": 20,
    "frame": 0,          // 트랙 시작 프레임
    "label_id": 3,
    "group": null,
    "source": "manual",
    "shapes": [          // 키프레임 목록 (시간 순)
        {
            "id": 100,
            "frame": 0,
            "type": "rectangle",
            "occluded": false,
            "outside": false,   // false = 객체 존재, true = 해당 프레임에 없음
            "z_order": 0,
            "rotation": 0.0,
            "points": [50.0, 50.0, 150.0, 150.0],
            "attributes": [{"spec_id": 1, "value": "blue"}]  // mutable 속성
        },
        {
            "id": 101,
            "frame": 10,    // 다음 키프레임
            "type": "rectangle",
            "outside": false,
            "points": [80.0, 60.0, 180.0, 160.0],
            "attributes": []
        },
        {
            "id": 102,
            "frame": 20,
            "outside": true    // 20번 프레임에서 객체 사라짐 (트랙 종료)
        }
    ],
    "attributes": [{"spec_id": 2, "value": "car"}],  // immutable 속성 (트랙 전체)
    "elements": []   // SKELETON의 하위 track 목록
}
```

## 키프레임 개념 {#keyframe}

- **키프레임**: TrackedShape가 저장된 프레임 — 실제 좌표값이 있음
- **보간 프레임**: 키프레임 사이 프레임 — 계산으로 얻는 임시 좌표
- **outside=true**: 해당 프레임에 객체가 화면에 없음을 나타내는 특수 키프레임

```
프레임:  0    1    2    3    4    5    6    7    8    9   10
키프레임: [K]            [outside]              [K]
보간:         [I]  [I]              [I]  [I]  [I]
표시:         Y    N    N    N      Y    Y    Y   Y
```
- 0번 키프레임에서 존재 → 3번 outside 키프레임 전까지 보간 (1, 2번)
- 3번 outside → 9번 키프레임 전까지 미표시 (3~8번: outside 상태)
- 9번 키프레임 → 이후 계속 표시

## 보간 알고리즘 {#interpolation-algorithm}

```python
# cvat/apps/dataset_manager/annotation.py:L758
@staticmethod
def get_interpolated_shapes(
    track: dict,
    start_frame: int,
    end_frame: int,
    dimension: DimensionType,
    *,
    included_frames=None,
    deleted_frames=None,
    include_outside=False,
    streaming=False,
) -> Generator[dict, None, None]:
```

### 형태별 보간 방식

```python
# 1. RECTANGLE / ELLIPSE / CUBOID — 선형 보간 (simple_interpolation)
def simple_interpolation(shape0, shape1):
    distance = shape1["frame"] - shape0["frame"]
    diff = np.subtract(shape1["points"], shape0["points"])
    for frame in range(shape0["frame"] + 1, shape1["frame"]):
        offset = (frame - shape0["frame"]) / distance
        points = shape0["points"] + diff * offset
        # rotation도 선형 보간 (최단 각도 경로 사용)
        yield copy_shape(shape0, frame, points, rotation)

# 2. POINTS — 포인트 수가 같으면 선형 보간, 다르면 이전 키프레임 복사
def points_interpolation(shape0, shape1):
    if len(shape0["points"]) == 2 and len(shape1["points"]) == 2:
        yield from simple_interpolation(shape0, shape1)
    else:
        for frame in range(shape0["frame"] + 1, shape1["frame"]):
            yield copy_shape(shape0, frame)

# 3. POLYGON / POLYLINE — 꼭짓점 매칭 후 경로 최소화 보간
def polygon_interpolation(shape0, shape1):
    # 헝가리안 알고리즘(linear_sum_assignment)으로 꼭짓점 대응
    # 대응된 꼭짓점 쌍 사이를 선형 보간
    # 꼭짓점 수가 다르면 가상 꼭짓점 추가하여 수 맞춤

# 4. 3D CUBOID — 위치는 선형 보간, 각도(오일러각)는 최단 회전 경로 보간
```

### 보간 제외 대상
- `deleted_frames`: 삭제된 프레임 (실제로 없는 프레임)
- 키프레임이 outside=true인 구간
- `included_frames`(GT Job의 특정 프레임 선택) 기준 필터링

## 서버 사이드 vs 클라이언트 사이드

| 처리 위치 | 사용 시점 |
|-----------|-----------|
| **서버 사이드** | Export 시, GT 품질 계산 시, 검수 데이터 준비 시 |
| **클라이언트 사이드** | 어노테이션 에디터에서 실시간 프레임 이동 시 |

클라이언트(`cvat-core`)도 동일한 보간 로직을 JavaScript로 구현하여 실시간 반응성을 제공한다.

## TrackedShape의 keyframe 필드

API 응답에서 보간된 shape는 `keyframe: false`가 추가된다.

```python
# annotation.py:L782
copied["keyframe"] = False  # 보간 shape에 표시
# 실제 TrackedShape는 keyframe=True (명시하지 않아도 DB에 있는 것은 키프레임)
```

## 핵심 의사결정

- **서버/클라이언트 동일 로직 이중 구현**: 동일한 보간 로직을 서버(Python)와 클라이언트(TypeScript) 양쪽에 구현하여 실시간 반응성과 Export 일관성을 동시에 확보한다.
- **outside=True 패턴**: 트랙 중단을 명시적으로 표현하는 특수 키프레임. 객체가 다시 등장할 때 새 키프레임을 추가하면 트랙이 재시작된다.
- **회전 최단 경로**: `rotation`은 단순 선형 보간이 아닌 `find_angle_diff()`로 최단 각도 경로를 계산한다. 359° → 1°로 갈 때 -358°가 아닌 +2° 이동.
- **POLYGON 보간 의존성**: 헝가리안 알고리즘(`scipy.optimize.linear_sum_assignment`)으로 꼭짓점 대응을 찾고 보간한다.
- **SKELETON 재귀**: SKELETON Track은 부모 Track + 하위 element Tracks 구조로, 각 element를 재귀적으로 보간한다.
- **GT Job 필터링**: `included_frames`가 있는 경우(GT Job의 specific frames), 보간된 모든 프레임이 아닌 included_frames에 포함된 프레임만 반환한다.

## 참고 코드 위치

- 서버 사이드 보간 진입점: `cvat/apps/dataset_manager/annotation.py:L758` (`TrackManager.get_interpolated_shapes()`)
- 클라이언트 사이드 보간: `cvat-core/src/` (TypeScript 구현)

## 독립 포팅 가이드

### 추출 난이도
**하** — 순수 알고리즘. Django/CVAT 의존성이 거의 없다. 모듈 01에 단독 추출 코드 포함.

### 핵심 추출 대상 (필수 파일)
| 역할 | 원본 경로 | 포팅 시 행수/조치 |
|------|----------|-----------------|
| TrackManager.get_interpolated_shapes | `cvat/apps/dataset_manager/annotation.py:758-1176` | 420줄 — 그대로 추출 가능 |
| TrackManager 클래스 | `cvat/apps/dataset_manager/annotation.py:607-1196` | to_shapes()는 SKELETON elements 처리 시 필요 |
| ShapeType / DimensionType | `cvat/apps/engine/models.py:1365` | enum 자체 정의로 대체 |
| LabeledTrack / TrackedShape DB 모델 | `cvat/apps/engine/models.py:1436-1451` | 자체 트랙 모델로 대체 |
| faster_deepcopy | `cvat/apps/dataset_manager/util.py:49` | `copy.deepcopy`로 대체 가능 |

### 외부 라이브러리 의존성
| 라이브러리 | 버전 | 대체 가능? | 비고 |
|----------|------|:--------:|------|
| numpy | >=1.22 | 필수 | 좌표 연산 |
| scipy | >=1.10 | 폴리곤 보간만 필요 | RECTANGLE/ELLIPSE/CUBOID 보간만 쓰면 numpy로 충분 |

### 내부 CVAT 의존성과 분리 방법
| 의존 모듈 | 포팅 시 처리 |
|---------|-----------|
| `LabeledDataSerializer` | 보간만 사용 시 불필요 |
| `AnnotationIR` | 단순 dict로 대체 |
| `make_getter_by_frame_for_annotation_stream` | SKELETON 보간 안 쓰면 불필요 |

### 최소 동작 단위 (MVP)
- 함수 1개 (`get_interpolated_shapes`) + numpy
- DB 테이블 0개 (입력 트랙 dict만 있으면 동작)
- API 0개 — 라이브러리로 import해서 사용

### 포팅 단계 (체크리스트)
1. [ ] numpy 설치
2. [ ] 모듈 01의 단독 추출 코드 복사
3. [ ] (선택) 폴리곤 보간 추가 시 scipy 설치 + polyshape_interpolation 추가
4. [ ] (선택) SKELETON 보간 추가 시 to_shapes() 패턴 참고

### 통합 지점 (이 모듈을 다른 시스템에 붙일 때)
- 입력: 트랙 dict (frame, shapes 리스트, attributes)
- 출력: 프레임별 보간된 shape 리스트
- 외부 인터페이스: `get_interpolated_shapes(track, start_frame, end_frame)` 함수 1개

### 알려진 함정
- **이종 타입 보간 불가**: rectangle ↔ polygon 키프레임 간 보간 시 NotImplementedError
- **outside=True 처리**: 트랙 종료를 표현. 보간 시 직전 키프레임에서 멈춤
- **회전 wrap-around**: `find_angle_diff`로 최단 각도 계산. 359°→1° 갈 때 +2°가 정상.
- **POLYGON 매칭 비용**: 100점+ 폴리곤 보간은 O(N²)로 느려짐
- **MASK 트랙은 보간 안 됨**: propagate(좌표 복사)만 가능. 모듈 01 참조.

## Docker 미사용 대응

이 모듈은 순수 알고리즘이므로 Docker 의존성 없음. `scipy` Python 패키지만 pip로 설치하면 동작한다.
