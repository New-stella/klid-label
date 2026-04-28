# 어노테이션 데이터 모델

## 개요
CVAT의 어노테이션은 세 가지 최상위 타입(tags/shapes/tracks)으로 분류되며, Job 단위로 저장된다.
좌표는 원본 이미지/프레임 해상도 기준 **픽셀 절대값**으로 저장된다.

## 핵심 코드 위치
| 역할 | 파일 경로 | 비고 |
|------|---------|------|
| DB 모델 정의 | `cvat/apps/engine/models.py` | LabeledShape, LabeledTrack 등 |
| 어노테이션 직렬화 | `cvat/apps/engine/serializers.py:L3474` | LabeledDataSerializer |
| 어노테이션 IR(중간 표현) | `cvat/apps/dataset_manager/annotation.py` | AnnotationIR 클래스 |
| Job 어노테이션 조회/저장 | `cvat/apps/dataset_manager/task.py` | JobAnnotation 클래스 |

## DB 스키마 {#db-schema}

### 좌표 저장 타입별 형식 {#coordinate-format}

```python
# cvat/apps/engine/models.py:L1365
class ShapeType(str, Enum):
    RECTANGLE = 'rectangle'  # points: (x0, y0, x1, y1) — 좌상단, 우하단
    POLYGON   = 'polygon'    # points: (x0, y0, ..., xn, yn) — 순서대로 꼭짓점
    POLYLINE  = 'polyline'   # points: (x0, y0, ..., xn, yn)
    POINTS    = 'points'     # points: (x0, y0, ..., xn, yn)
    ELLIPSE   = 'ellipse'    # points: (cx, cy, rx, ty) — 중심, 반지름x, 상단y
    CUBOID    = 'cuboid'     # points: (x0,y0,...,x7,y7) — 8개 꼭짓점 16값
    MASK      = 'mask'       # points: (rle_mask..., left, top, right, bottom)
    SKELETON  = 'skeleton'   # points: [] (elements로 관리)
```

**모든 좌표는 픽셀 절대값** — 화면 스케일 적용 없이 원본 해상도 기준으로 저장.

### 핵심 어노테이션 테이블

```
# Annotation (추상 기반 클래스)
- id: BigAutoField (PK)
- job_id: FK → Job
- label_id: FK → Label
- frame: PositiveIntegerField  (0-based 프레임 번호)
- group: PositiveIntegerField | null
- source: CharField (manual | auto | semi-auto | file | consensus)
#   manual: 수동 라벨링
#   auto: 오토라벨링 결과 (YOLO/SAM 등)
#   semi-auto: 보간으로 자동 생성된 키프레임
#   file: 파일 Import로 생성
#   consensus: 합의 어노테이션 (Quality 기능)

# Shape (추상 믹스인)
- type: CharField (ShapeType enum)
- occluded: BooleanField
- outside: BooleanField
- z_order: IntegerField
- points: FloatArrayField  (콤마 구분 텍스트 필드로 DB 저장!)
- rotation: FloatField

# LabeledImage(tag) = Annotation
# LabeledShape = Annotation + Shape
#   - parent: FK → LabeledShape (SKELETON의 elements)
#   - score: FloatField (0~1, 오토라벨링 신뢰도)

# LabeledTrack = Annotation (비디오 트랙)
#   - parent: FK → LabeledTrack (SKELETON elements)
# TrackedShape = Shape (트랙의 키프레임)
#   - id: BigAutoField
#   - track_id: FK → LabeledTrack
#   - frame: PositiveIntegerField

# AttributeVal (추상)
#   - id: BigAutoField
#   - spec_id: FK → AttributeSpec
#   - job_id: FK → Job
#   - value: SafeCharField(4096)
# LabeledImageAttributeVal, LabeledShapeAttributeVal,
# LabeledTrackAttributeVal, TrackedShapeAttributeVal — 각각 별도 테이블
```

### MASK 타입 특이사항
MASK는 RLE(Run-Length Encoding) 형식으로 저장된다.
`points` 필드의 마지막 4개 값이 `left, top, right, bottom` 바운딩박스이고,
앞부분이 RLE 데이터다.

```python
# points 예시: [1, 5, 2, 3, 10, 20, 30, 40]
# → RLE: [1, 5, 2, 3]  (픽셀 on/off 런 길이)
# → bbox: left=10, top=20, right=30, bottom=40
```

### FloatArrayField (LazyList 패턴)
`LabeledShape.points`는 DB에 콤마 구분 TEXT로 저장되는 커스텀 Django 필드다.
읽을 때 파이썬 `list`가 아닌 **LazyList** 로 반환되며, 실제 파싱은 첫 접근 시 지연 실행된다.
대량 shape 조회 시 points 문자열 파싱 누적이 병목이 될 수 있다.

## API 형식

### 어노테이션 조회/저장 API
```
GET  /api/jobs/{id}/annotations     → LabeledDataSerializer
PUT  /api/jobs/{id}/annotations     → LabeledDataSerializer (전체 교체)
PATCH /api/jobs/{id}/annotations    → LabeledDataSerializer (부분 update/create/delete)
```

### LabeledDataSerializer 구조 (`cvat/apps/engine/serializers.py:L3474`)
```json
{
  "version": 0,
  "tags": [
    {
      "id": 1,
      "frame": 0,
      "label_id": 5,
      "group": null,
      "source": "manual",
      "attributes": [{"spec_id": 3, "value": "blue"}]
    }
  ],
  "shapes": [
    {
      "id": 10,
      "frame": 5,
      "label_id": 2,
      "group": null,
      "source": "manual",
      "type": "rectangle",
      "occluded": false,
      "outside": false,
      "z_order": 0,
      "rotation": 0.0,
      "points": [100.0, 200.0, 300.0, 400.0],
      "score": 1.0,
      "attributes": [],
      "elements": []
    }
  ],
  "tracks": [
    {
      "id": 20,
      "frame": 0,
      "label_id": 3,
      "group": null,
      "source": "manual",
      "shapes": [
        {
          "id": 100,
          "frame": 0,
          "type": "rectangle",
          "occluded": false,
          "outside": false,
          "z_order": 0,
          "rotation": 0.0,
          "points": [50.0, 50.0, 150.0, 150.0],
          "attributes": []
        }
      ],
      "attributes": [],
      "elements": []
    }
  ]
}
```

### PATCH 액션 (부분 수정)
```
PATCH /api/jobs/{id}/annotations?action=create  → 어노테이션 추가
PATCH /api/jobs/{id}/annotations?action=update  → 어노테이션 수정
PATCH /api/jobs/{id}/annotations?action=delete  → 어노테이션 삭제
```

## 데이터 흐름

```
Client PATCH → JobViewSet.annotations()
                    → JobAnnotation.create/update/delete()
                    → DB 저장 (LabeledShape 등)
                    → handle_annotations_change() [이벤트 emit]
```

## 핵심 의사결정

- **픽셀 절대값**: 좌표는 항상 원본 해상도 기준으로 저장한다. 화면 스케일을 적용하지 않으므로 클라이언트가 줌/리사이즈해도 DB 값은 변하지 않는다.
- **세 가지 분류**: tags(이미지 레벨) / shapes(단일 프레임) / tracks(비디오 추적) — Job 단위로 묶어서 직렬화한다.
- **source 필드**: manual/auto/semi-auto/file/consensus 5종으로 어노테이션 출처를 표시. 오토라벨링 결과는 `source='auto'`로 저장된다.
- **score 필드**: 오토라벨링 신뢰도 0~1을 저장. 수동 라벨링은 1.0.
- **PATCH action 패턴**: create/update/delete 3가지 액션을 쿼리 파라미터로 분기하여 부분 업데이트를 지원한다.
- **outside 키프레임**: `outside=true`인 TrackedShape는 "해당 프레임에 객체가 없음"을 나타내는 특수 키프레임으로, 트랙 종료/재시작을 표시한다.
- **Skeleton 재귀**: Skeleton 타입은 `elements` 필드에 하위 LabeledShape/LabeledTrack 목록을 가지는 재귀 구조다.

## 독립 포팅 가이드

### 추출 난이도
**중** — 모델 정의는 단순하지만 LazyList(custom Field) + AttributeVal 다형성 구조 + Skeleton 재귀 + source enum 등 잔가지가 많아 그대로 옮기면 1500줄+. ShapeType enum과 핵심 5개 모델(LabeledShape/LabeledTrack/TrackedShape/Annotation/Shape)만 추출하면 200줄 이내.

### 핵심 추출 대상 (필수 파일)
| 역할 | 원본 경로 | 포팅 시 행수/조치 |
|------|----------|-----------------|
| Shape/Annotation 추상 모델 | `cvat/apps/engine/models.py:1396-1452` | 60줄 — 그대로 사용 가능 |
| ShapeType / SourceType enum | `cvat/apps/engine/models.py:1365-1394` | 30줄 — 그대로 사용 |
| LabeledImage/Shape/Track | `cvat/apps/engine/models.py:1421-1452` | 30줄 |
| AttributeVal 패밀리 | `cvat/apps/engine/models.py:1432-1451` | LabeledShape/Track 별 분리 모델 → 단일 polymorphic 모델로 단순화 가능 |
| LabeledDataSerializer 구조 | `cvat/apps/engine/serializers.py:3474+` | DRF → Pydantic 변환 (~100줄) |
| FloatArrayField (LazyList) | `cvat/apps/engine/lazy_list.py` | 단순 `JSONField` 또는 `TextField` + `json.loads`로 대체 |

### 외부 라이브러리 의존성
| 라이브러리 | 버전 | 대체 가능? | 비고 |
|----------|------|:--------:|------|
| Django ORM | >=4.2 | 필수 시 | SQLAlchemy로 대체 시 모델 전체 재작성 |
| DRF (rest_framework) | >=3.14 | 가능 | Pydantic + FastAPI로 대체 |

### 내부 CVAT 의존성과 분리 방법
| 의존 모듈 | 포팅 시 처리 |
|---------|-----------|
| `LazyList` (커스텀 컨테이너) | 표준 `list`로 대체. 직렬화 시 `","`.join(map(str, ...))로 충분 |
| `cvat.apps.organizations` | 단일 조직 가정 시 organization_id 컬럼 제거 |
| `cvat.apps.iam` (권한) | 함수형 권한 검사 stub (`def has_perm(...): return True`) |
| `cvat.apps.engine.models.User` (Django auth) | 자체 User 모델로 대체 |
| `Skeleton` 모델 | Skeleton이 필요 없으면 LabelType.SKELETON 분기 제거 |

### 최소 동작 단위 (MVP)
- 추출 파일: `models.py`(어노테이션 모델 200줄) + serializer 1개 + view 1개
- 필요한 최소 DB 테이블: 5개 — `Annotation 추상 → LabeledImage(tag) + LabeledShape + LabeledTrack + TrackedShape + AttributeVal`
- 필요한 최소 API 엔드포인트: 3개 — `GET/PUT/PATCH /api/jobs/{id}/annotations`

### 포팅 단계 (체크리스트)
1. [ ] ShapeType/SourceType enum 복사
2. [ ] Shape/Annotation 추상 클래스 복사 (Django Meta abstract=True)
3. [ ] LabeledImage/Shape/Track + AttributeVal 4종 복사
4. [ ] FloatArrayField → JSONField(json) 또는 TextField로 단순화
5. [ ] LabeledDataSerializer 패턴을 Pydantic으로 재작성
6. [ ] PATCH action=create/update/delete 라우터 작성
7. [ ] 트랙 보간 (모듈 01) 별도 적용

### 통합 지점 (이 모듈을 다른 시스템에 붙일 때)
- 입력: `LabeledDataSerializer` 형식의 JSON `{tags, shapes, tracks, version}`
- 출력: 동일 JSON 형식 (조회 시) — 픽셀 절대값 좌표 보장
- 외부에 노출되는 인터페이스: `GET /annotations`, `PUT /annotations`(전체 교체), `PATCH /annotations?action=create|update|delete`

### 알려진 함정
- **LazyList 함정**: `points` 필드를 그대로 List로 처리하면 N+1 직렬화 누적 → 큰 폴리곤(1000점+) 1000개 조회 시 수 초 소요. 큰 데이터는 stream 직렬화 권장.
- **MASK points 4개 꼬리**: `points[-4:]` 가 BBOX(left,top,right,bottom)이고 앞이 RLE — 이 형식을 모르고 단순 좌표 배열로 처리하면 마스크가 깨진다.
- **outside=True 키프레임**: `LabeledTrack.shapes`의 마지막 요소가 outside=True이면 트랙 종료를 의미. 보간 알고리즘이 이를 인식해야 함.
- **score=1.0 기본**: 수동 라벨링은 score=1.0, 오토라벨링 결과만 < 1.0. 필터링 시 헷갈리지 말 것.
- **PostgreSQL JSONField**: Django ORM의 `JSONField`는 PostgreSQL 전용 → MySQL 사용 시 Django의 generic JSONField (Django 3.1+)나 자체 TextField로 변경.

## Docker 미사용 대응

이 모듈은 데이터 모델 정의이므로 Docker 의존성 없음. PostgreSQL이 네이티브로 동작하기만 하면 된다(자세한 배포는 [deployment-without-docker.md](deployment-without-docker.md) 참고).
