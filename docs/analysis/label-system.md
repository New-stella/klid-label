# 라벨 타입 및 속성 시스템

## 개요
CVAT의 라벨 시스템은 Label(라벨 정의) → AttributeSpec(속성 스펙) → AttributeVal(실제 값) 3계층으로 구성된다. 라벨은 Task 또는 Project에 소속되며, 타입별로 허용 ShapeType이 다르다.

## 핵심 코드 위치
| 역할 | 파일 경로 | 비고 |
|------|---------|------|
| Label/AttributeSpec 모델 | `cvat/apps/engine/models.py:L1253` | Label, AttributeSpec, AttributeType |
| LabelType 열거형 | `cvat/apps/engine/models.py:L80` | 10종 라벨 타입 |
| AttributeType 열거형 | `cvat/apps/engine/models.py:L1324` | 5종 속성 입력 타입 |
| 라벨 시리얼라이저 | `cvat/apps/engine/serializers.py` | LabelSerializer |
| 라벨 ViewSet | `cvat/apps/engine/views.py:L2285` | LabelViewSet |

## 라벨 타입 목록 {#label-types}

```python
# cvat/apps/engine/models.py:L80
class LabelType(str, Enum):
    ANY       = 'any'       # 제한 없음 (모든 shape 타입 허용)
    CUBOID    = 'cuboid'    # 3D 큐보이드
    ELLIPSE   = 'ellipse'   # 타원
    MASK      = 'mask'      # 픽셀 마스크 (RLE)
    POINTS    = 'points'    # 포인트(들)
    POLYGON   = 'polygon'   # 다각형
    POLYLINE  = 'polyline'  # 폴리라인
    RECTANGLE = 'rectangle' # 사각형(BBOX)
    SKELETON  = 'skeleton'  # 스켈레톤 (keypoint 집합)
    TAG       = 'tag'       # 이미지 레벨 태그 (좌표 없음)
```

**LabelType vs ShapeType 관계**: LabelType은 라벨 정의에 사용하고, ShapeType은 실제 어노테이션 인스턴스에 사용한다. `LabelType.ANY`면 모든 ShapeType을 허용한다.

## 속성(Attribute) 타입 {#attribute-types}

```python
# cvat/apps/engine/models.py:L1324
class AttributeType(str, Enum):
    CHECKBOX = 'checkbox'  # 참/거짓
    RADIO    = 'radio'     # 단일 선택
    NUMBER   = 'number'    # 숫자 (min, max, step으로 range 정의)
    TEXT     = 'text'      # 자유 텍스트
    SELECT   = 'select'    # 다중 선택 가능 드롭다운
```

### AttributeSpec 구조

```python
# cvat/apps/engine/models.py:L1338
class AttributeSpec(models.Model):
    label      = ForeignKey(Label)
    name       = CharField(max_length=64)
    mutable    = BooleanField()       # True면 프레임별로 다른 값 허용
    input_type = CharField(choices=AttributeType.choices())
    default_value = CharField(blank=True, max_length=128)
    values     = CharField(blank=True, max_length=4096)  # 선택지 목록 (','로 구분)
```

- `mutable=True`: 트랙에서 프레임마다 다른 값을 가질 수 있음 (TrackedShapeAttributeVal)
- `mutable=False`: 트랙 전체에 하나의 값만 허용 (LabeledTrackAttributeVal)

### 숫자 속성의 values 형식
```
# values 필드 예시: "0,100,1" → min=0, max=100, step=1
# CHECKBOX values: "false" 또는 "true"
# SELECT/RADIO values: "옵션1,옵션2,옵션3"
```

## Label 모델 구조

```python
# cvat/apps/engine/models.py:L1253
class Label(models.Model):
    task    = ForeignKey(Task, null=True)     # task 소속
    project = ForeignKey(Project, null=True)  # 또는 project 소속
    name    = SafeCharField(max_length=64)
    color   = CharField(max_length=8)  # 16진수 색상코드 (#RRGGBB)
    type    = CharField(choices=LabelType.choices(), default=LabelType.ANY)
    parent  = ForeignKey('self', null=True)   # SKELETON의 sublabel 구조

# Skeleton 추가 정보
class Skeleton(models.Model):
    root = OneToOneField(Label)
    svg  = TextField(null=True)  # 스켈레톤 연결선 SVG 정의
```

### UniqueConstraint 규칙
- Project 레벨: (project, name) 쌍 유일
- Task 레벨: (task, name) 쌍 유일
- Skeleton sublabel: (project, name, parent) 또는 (task, name, parent) 유일

## 프리셋(spec) 구조

CVAT에는 별도의 "프리셋" 테이블이 없다. 대신 라벨은 Project 레벨 또는 Task 레벨에 소속된다.

**Project 레벨 라벨**: Project에 속한 모든 Task가 공유 → Task 생성 시 자동 복사  
**Task 레벨 라벨**: 해당 Task에만 적용

```python
# cvat/apps/engine/views.py:L2285 LabelViewSet
# GET /api/labels?task_id=N → 해당 Task의 라벨 목록
# GET /api/labels?project_id=N → 해당 Project의 라벨 목록
```

## API 예시

### 라벨 생성 요청
```json
POST /api/labels
{
  "name": "pedestrian",
  "color": "#ff0000",
  "type": "rectangle",
  "task_id": 5,
  "attributes": [
    {
      "name": "gender",
      "input_type": "select",
      "mutable": false,
      "default_value": "unknown",
      "values": ["male", "female", "unknown"]
    },
    {
      "name": "occluded_percent",
      "input_type": "number",
      "mutable": true,
      "default_value": "0",
      "values": "0,100,10"
    }
  ]
}
```

## Nuclio function.yaml의 라벨 spec 형식

```yaml
# serverless/onnx/WongKinYiu/yolov7/nuclio/function.yaml
annotations:
  spec: |
    [
      { "id": 0, "name": "person", "type": "rectangle" },
      { "id": 2, "name": "car",    "type": "rectangle",
        "attributes": [
          {"name": "color", "input_type": "select", "values": ["red", "blue"]}
        ]
      }
    ]
```

## 핵심 의사결정

- **LabelType vs ShapeType 분리**: 라벨 정의(LabelType)와 실제 어노테이션 인스턴스(ShapeType)를 분리한다. `LabelType.ANY`는 모든 ShapeType을 허용하는 와일드카드다.
- **AttributeType 5종**: checkbox/radio/number/text/select 입력 타입으로 속성을 정의한다. 숫자 속성의 values는 "min,max,step" 3개 값으로 구성된다.
- **mutable 분기**: `mutable=True`면 프레임마다 다른 속성값을 허용(TrackedShapeAttributeVal), `mutable=False`면 트랙 전체에 하나의 값만 허용(LabeledTrackAttributeVal)한다.
- **소속 분기**: 라벨은 Project 또는 Task 중 하나에만 소속된다. Project 레벨 라벨은 모든 Task가 공유하며, Task 레벨 라벨은 해당 Task에만 적용된다.
- **물리 삭제**: 라벨 삭제는 Cascade로 관련 어노테이션도 모두 삭제한다(논리 삭제 컬럼 없음).
- **Skeleton parent 계층**: Skeleton은 root 라벨 + sublabel(`parent` 가리킴) 구조로 keypoint 집합을 정의한다.

## 독립 포팅 가이드

### 추출 난이도
**하** — 라벨/속성은 단순한 모델. Skeleton(parent 재귀)만 빼면 200줄 이내.

### 핵심 추출 대상 (필수 파일)
| 역할 | 원본 경로 | 포팅 시 행수/조치 |
|------|----------|-----------------|
| Label 모델 | `cvat/apps/engine/models.py:1253` | 30줄 |
| AttributeSpec 모델 | `cvat/apps/engine/models.py:1338` | 20줄 |
| LabelType enum | `cvat/apps/engine/models.py:80-101` | 20줄 |
| AttributeType enum | `cvat/apps/engine/models.py:1324-1336` | 15줄 |
| Skeleton 모델 (선택) | `cvat/apps/engine/models.py` (Skeleton 검색) | Skeleton 사용 안 하면 생략 |
| LabelSerializer | `cvat/apps/engine/serializers.py` | 50줄 |
| LabelViewSet | `cvat/apps/engine/views.py:2285` | 80줄 (CRUD + ?task_id/?project_id 필터) |

### 외부 라이브러리 의존성
| 라이브러리 | 버전 | 대체 가능? | 비고 |
|----------|------|:--------:|------|
| Django ORM | >=4.2 | 가능 | SQLAlchemy 대체 시 모델 재작성 |
| DRF | >=3.14 | 가능 | Pydantic+FastAPI |

### 내부 CVAT 의존성과 분리 방법
| 의존 모듈 | 포팅 시 처리 |
|---------|-----------|
| `Project`/`Task` 모델 (Label의 FK) | 자체 부모 엔티티로 변경. 단일 task 가정 시 project_id FK 제거 가능 |
| `Skeleton` 별도 모델 | Skeleton 불필요하면 LabelType.SKELETON 분기 제거 |
| `SafeCharField` | 표준 `CharField` + 검증 로직 별도 |

### 최소 동작 단위 (MVP)
- Label + AttributeSpec 2개 테이블만으로 기본 동작
- API 4개: `GET /labels`, `POST /labels`, `PUT /labels/{id}`, `DELETE /labels/{id}`

### 포팅 단계 (체크리스트)
1. [ ] LabelType / AttributeType enum 복사
2. [ ] Label / AttributeSpec 모델 복사 (parent FK는 Skeleton 사용 시만 유지)
3. [ ] Label CRUD ViewSet
4. [ ] LabelType.ANY 와일드카드 처리 검증
5. [ ] mutable=True/False 분기 (속성 저장 위치)

### 통합 지점 (이 모듈을 다른 시스템에 붙일 때)
- 입력: `{name, color, type, attributes}` 형식의 라벨 정의 JSON
- 출력: 동일 + DB id 추가
- 외부에 노출되는 인터페이스: REST `/labels` CRUD, `?task_id` / `?project_id` 필터링

### 알려진 함정
- **mutable 의미**: `mutable=True`이면 트랙 안에서 프레임마다 다른 값을 가질 수 있고(TrackedShapeAttributeVal에 저장), `mutable=False`이면 트랙 전체에 한 값(LabeledTrackAttributeVal). 잘못 설정하면 어노테이션 저장 시 에러.
- **values 형식 통일성 결여**: NUMBER는 `"0,100,1"` (min,max,step), CHECKBOX는 `"true"`/`"false"`, SELECT/RADIO는 `"a,b,c"`. 단일 컬럼에 여러 의미 → 파싱 시 input_type별 분기 필요.
- **물리 삭제 cascade**: 라벨 삭제 시 관련 어노테이션 모두 cascade 삭제. 운영 환경에서는 `is_active` flag로 soft delete 권장.
- **UniqueConstraint 충돌**: (task, name) 또는 (project, name) 유일. 같은 라벨 이름을 task에서 재사용 시 IntegrityError.

## Docker 미사용 대응

이 모듈은 라벨 정의 데이터 모델이므로 Docker 의존성 없음. function.yaml을 직접 파싱해 모델 라벨 spec을 임포트하는 방식만 별도 구현하면 된다.
