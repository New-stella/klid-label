# 품질 검수 기능

## 개요
CVAT의 품질 관리는 Ground Truth(GT) Job을 기준으로 작업자의 어노테이션을 비교하여 점수를 계산한다. `quality_control` 앱이 담당하며, 충돌 유형 감지와 정밀도/재현율/정확도 지표를 제공한다.

## 핵심 코드 위치
| 역할 | 파일 경로 | 비고 |
|------|---------|------|
| 품질 모델 | `cvat/apps/quality_control/models.py` | QualityReport, AnnotationConflict |
| 품질 리포트 생성 | `cvat/apps/quality_control/quality_reports.py` | 핵심 알고리즘 (~1,500줄) |
| 품질 ViewSet | `cvat/apps/quality_control/views.py` | REST API |
| 품질 URL 라우팅 | `cvat/apps/quality_control/urls.py` | /api/quality/ |

## Ground Truth Job {#gt-job}

```python
# cvat/apps/engine/models.py:L176
class JobType(str, Enum):
    ANNOTATION      = 'annotation'       # 일반 작업 Job
    GROUND_TRUTH    = 'ground_truth'     # GT Job (품질 평가 기준)
    CONSENSUS_REPLICA = 'consensus_replica'

# Task당 GT Job은 1개만 허용
class TaskGroundTruthJobsLimitError(ValidationError):
    def __init__(self):
        super().__init__("A task can have only 1 ground truth job")
```

GT Job은 일반 Job과 동일한 구조지만, `type='ground_truth'`로 구분된다. GT Job에 입력된 어노테이션이 정답 기준이 된다.

### GT Job 생성 방식

```
ValidationMode.GT           → 특정 프레임을 GT 전용 Segment로 분리
ValidationMode.GT_POOL      → Honeypot 방식: GT 프레임을 일반 작업에 섞어서 라벨러가 모르게 평가
```

## 품질 점수 계산 방식 {#quality-score}

```python
# cvat/apps/quality_control/models.py
class QualityTargetMetricType(str, Enum):
    ACCURACY  = "accuracy"   # 정확도 (TP / (TP + FP + FN))
    PRECISION = "precision"  # 정밀도 (TP / (TP + FP))
    RECALL    = "recall"     # 재현율 (TP / (TP + FN))
```

### IoU (Intersection over Union) 기반 매칭

```python
# quality_reports.py 내부
# 1. GT 어노테이션 ↔ 작업자 어노테이션을 IoU로 매칭
#    헝가리안 알고리즘(linear_sum_assignment)으로 최적 매칭
# 2. IoU > threshold (기본 0.4)이면 매칭 성공 (TP)
# 3. 매칭 실패 GT → FN (누락 어노테이션)
# 4. 매칭 실패 작업자 → FP (불필요 어노테이션)

# Shape 타입별 IoU 계산:
# - BBOX: 교집합/합집합 사각형 비율
# - POLYGON: Shapely 폴리곤 교집합/합집합
# - MASK: 픽셀 단위 IoU
# - POINTS: 거리 기반 유사도
```

## 충돌 유형 {#conflict-types}

```python
# cvat/apps/quality_control/models.py
class AnnotationConflictType(str, Enum):
    MISSING_ANNOTATION    = "missing_annotation"    # 작업자가 놓친 어노테이션
    EXTRA_ANNOTATION      = "extra_annotation"      # 작업자가 추가한 불필요 어노테이션
    MISMATCHING_LABEL     = "mismatching_label"     # 라벨 불일치
    LOW_OVERLAP           = "low_overlap"           # IoU가 낮음
    MISMATCHING_DIRECTION = "mismatching_direction" # 방향 불일치 (polyline)
    MISMATCHING_ATTRIBUTES = "mismatching_attributes" # 속성 불일치
    MISMATCHING_GROUPS    = "mismatching_groups"    # 그룹 불일치
    COVERED_ANNOTATION    = "covered_annotation"    # 가려진 어노테이션 (z-order)

class AnnotationConflictSeverity(str, Enum):
    WARNING = "warning"  # 경고 (LOW_OVERLAP, 속성 불일치 등)
    ERROR   = "error"    # 오류 (누락, 불필요 어노테이션, 라벨 불일치)
```

## QualityReport 모델

```python
# cvat/apps/quality_control/models.py
class QualityReport(models.Model):
    job     = ForeignKey(Job, null=True)      # Job 레벨 리포트
    task    = ForeignKey(Task, null=True)     # Task 레벨 리포트 (집계)
    project = ForeignKey(Project, null=True)  # Project 레벨 리포트 (집계)

    gt_last_updated   = DateTimeField()  # GT Job 마지막 업데이트 시각
    data              = JSONField()      # 리포트 상세 데이터
    created_date      = DateTimeField()

    # data 필드 구조:
    # {
    #   "mean_accuracy": 0.85,
    #   "mean_precision": 0.88,
    #   "mean_recall": 0.83,
    #   "jobs_count": 5,
    #   "frames": {
    #     "total": 100,
    #     "valid": 85,
    #     "invalid": 15
    #   }
    # }
```

## 품질 설정

```python
# QualitySettings 모델 (task당 하나)
# cvat/apps/quality_control/models.py
class QualitySettings(models.Model):
    task = OneToOneField(Task)

    iou_threshold           = FloatField(default=0.4)
    oks_sigma               = FloatField(default=0.09)  # 포즈 키포인트용
    point_size_base         = FloatField()
    line_thickness          = FloatField()
    low_overlap_threshold   = FloatField(default=0.8)
    compare_line_orientation = BooleanField(default=True)
    line_orientation_threshold = FloatField(default=0.1)
    compare_groups          = BooleanField(default=False)
    group_match_threshold   = FloatField(default=0.5)
    check_covered_annotations = BooleanField(default=True)
    object_visibility_threshold = FloatField(default=0.05)
    panoptic_comparison     = BooleanField(default=True)
    compare_attributes      = BooleanField(default=True)
```

## API 엔드포인트

```
GET  /api/quality/reports                   # 리포트 목록 (?job_id=N&task_id=N)
GET  /api/quality/reports/{id}              # 리포트 상세
GET  /api/quality/reports/{id}/data         # 리포트 원시 데이터
POST /api/quality/reports                   # 리포트 생성 요청 (비동기)
GET  /api/quality/conflicts                 # 충돌 목록 (?report_id=N)
GET  /api/quality/settings/{id}             # 품질 설정 조회
PATCH /api/quality/settings/{id}            # 품질 설정 수정
```

## 핵심 의사결정

- **GT Job 단일 인스턴스**: Task당 GT Job은 1개로 제한 (`TaskGroundTruthJobsLimitError`). 정답 데이터의 일관성을 강제.
- **GT_POOL Honeypot 모드**: GT 프레임을 일반 작업에 섞어 라벨러가 모르게 평가하는 옵션을 제공. 작업자 의식적 편향 방지.
- **IoU + 헝가리안 매칭**: GT vs 작업자 어노테이션을 IoU 기반으로 헝가리안 알고리즘(`scipy.optimize.linear_sum_assignment`)으로 매칭. 다대일/일대다 충돌 자동 해소.
- **Shape 타입별 IoU 계산**: BBOX(사각형 IoU), POLYGON(Shapely), MASK(픽셀 IoU), POINTS(거리 기반 유사도)로 각 타입에 적합한 매칭 방식 사용.
- **충돌 유형 8종 분류**: missing/extra/mismatching_label/low_overlap/mismatching_direction/mismatching_attributes/mismatching_groups/covered.
- **Severity 2단계**: warning(낮은 IoU 등) / error(누락, 라벨 불일치 등)로 자동 분류.
- **JSON 통계 캐싱**: `QualityReport.data` 필드에 전체 리포트를 JSON으로 저장하여 재계산 없이 조회 가능.
- **IoU 기본값 0.4**: 매칭 임계값 기본 0.4. low_overlap 경고 임계값은 0.8.

## 독립 포팅 가이드

### 추출 난이도
**중** (알고리즘) — 매칭 로직 자체는 단독 추출 가능 (모듈 09 참조). **상** (전체) — GT Job 인프라 + Segment(SPECIFIC_FRAMES) + Honeypot(GT_POOL) 모드까지 옮기려면 워크플로우 모델까지 같이 변경 필요.

### 핵심 추출 대상 (필수 파일)
| 역할 | 원본 경로 | 포팅 시 행수/조치 |
|------|----------|-----------------|
| 충돌 타입 enum | `cvat/apps/quality_control/models.py:23-50` | 30줄 |
| QualityReport / QualitySettings 모델 | `cvat/apps/quality_control/models.py:91-228` | 100줄 |
| 매칭 알고리즘 (match_segments) | `cvat/apps/quality_control/quality_reports.py:982-1042` | 60줄 — 모듈 09 |
| Mask/Polygon IoU | `cvat/apps/quality_control/quality_reports.py:1117-1140` | 25줄 |
| OKS (점) | `cvat/apps/quality_control/quality_reports.py:1045-1076` | 30줄 |
| ConfusionMatrix | `cvat/apps/quality_control/quality_reports.py:345-441` | 100줄 |
| DistanceComparator | `cvat/apps/quality_control/quality_reports.py:1283-1900` | 600줄 — datumaro Comparator 상속 |
| ComparisonParameters | `cvat/apps/quality_control/quality_reports.py:248-342` | 100줄 |
| GT Job 모델 | `cvat/apps/engine/models.py` (`JobType.GROUND_TRUTH`) | enum + Task당 1개 unique |
| 통계 ViewSet | `cvat/apps/quality_control/views.py` | REST API |

### 외부 라이브러리 의존성
| 라이브러리 | 버전 | 대체 가능? | 비고 |
|----------|------|:--------:|------|
| numpy | >=1.22 | 필수 | 거리 행렬 |
| scipy | >=1.10 | 필수 | linear_sum_assignment |
| pycocotools | >=2.0 | mask 사용 시 필수 | RLE IoU |
| shapely | >=2.0 | polygon 정확 IoU에 권장 | datumaro가 사용 |
| opencv-python | >=4.5 | 필수 | mask 전처리 |
| datumaro | >=1.0 | 직접 구현 시 불필요 | DatasetItem, AnnotationType, matcher 베이스 |

### 내부 CVAT 의존성과 분리 방법
| 의존 모듈 | 포팅 시 처리 |
|---------|-----------|
| `JobDataProvider` (DB → datumaro Dataset) | 자체 어노테이션 → 자체 자료구조 어댑터 |
| QualitySettings (Task FK) | 단일 설정으로 단순화 |
| GT Job (engine.models.Job 분기) | 별도 ground_truth 테이블 또는 같은 테이블 + flag |
| RQ worker (cvat_worker_quality_reports) | → 모듈 08 |

### 최소 동작 단위 (MVP)
- BBOX 매칭만 (모듈 09 단독 추출 코드 참조) — 200줄
- 충돌 4종(MISSING/EXTRA/MISMATCHING_LABEL/LOW_OVERLAP) 만
- precision/recall/accuracy 메트릭

### 포팅 단계 (체크리스트)
1. [ ] 충돌 타입 enum 정의 (8종)
2. [ ] match_segments 알고리즘 (헝가리안)
3. [ ] BBOX IoU 함수
4. [ ] (mask 시) pycocotools.mask.iou + RLE 변환
5. [ ] (polygon 시) shapely.geometry.Polygon.intersection/union
6. [ ] ConfusionMatrix → precision/recall/accuracy
7. [ ] GT 어노테이션 입력 인프라 (별도 Job 또는 별도 테이블)
8. [ ] 비동기 워커 (모듈 08)
9. [ ] 결과를 JSONField에 캐싱

### 통합 지점 (이 모듈을 다른 시스템에 붙일 때)
- 입력: GT 어노테이션 + 작업자 어노테이션 (frame 단위 매칭)
- 출력: 충돌 리스트 + 메트릭 (precision/recall/accuracy)
- 외부 인터페이스: `POST /api/quality/reports` 시작, `GET /api/quality/reports/{id}/data`로 결과 조회

### 알려진 함정
- **GT Job 단일성**: Task당 GROUND_TRUTH job은 1개로 제한 (TaskGroundTruthJobsLimitError). DB unique constraint로 강제.
- **헝가리안 시간복잡도**: O(N³). frame당 100+ 어노테이션이면 느려짐.
- **outside 어노테이션 제외**: `attributes.get("outside", False)`인 어노테이션은 매칭 대상 제외 (CVAT 컨벤션).
- **POLYGON IoU 비용**: shapely O(N²). mask 변환 후 pycocotools가 보통 빠름.
- **OKS sigma 튜닝**: 0.09는 COCO person 기준. 다른 객체에는 별도 sigma.
- **점수 캐싱**: 매번 재계산하지 않고 QualityReport.data JSONField에 저장. GT가 변경되면 dirty 플래그.
- **빈 비교 케이스**: GT 0개 + DS 0개의 accuracy는 0/0 모호. CVAT은 `empty_is_annotated` 옵션 제공.
- **panoptic_comparison**: True면 가려진 부분(z-order 아래) 제외하고 IoU 계산. 다른 도구와 결과 다를 수 있음.

## Docker 미사용 대응

품질 리포트 생성은 별도 RQ 워커(`cvat_worker_quality_reports`)에서 실행되지만, 단순 Python 프로세스이므로 Docker 의존성 없음. systemd unit으로 동등 실행 가능.

```ini
# /etc/systemd/system/cvat-worker-quality-reports.service
[Unit]
Description=CVAT RQ worker (quality_reports)
After=redis-server.service postgresql.service

[Service]
Type=simple
User=cvat
WorkingDirectory=/opt/cvat
Environment="DJANGO_SETTINGS_MODULE=cvat.settings.production"
EnvironmentFile=/etc/cvat/cvat.env
ExecStart=/opt/cvat/venv/bin/python manage.py rqworker -v 3 quality_reports \
    --worker-class cvat.rqworker.DefaultWorker
Restart=always
# 품질 리포트는 CPU 집약적이므로 메모리 제한 권장
MemoryMax=4G

[Install]
WantedBy=multi-user.target
```

### 주의사항

- 품질 리포트 생성은 CPU 집약적이므로 워커 수를 호스트 nproc에 맞게 조정.
- Shapely(POLYGON IoU)와 scipy(linear_sum_assignment)는 시스템 BLAS 라이브러리에 의존. `apt install libblas-dev liblapack-dev` 필요.
- GT Job이 없으면 품질 리포트를 생성할 수 없음 — 먼저 GT 데이터 준비 필요.

자세한 통합 배포는 [deployment-without-docker.md](deployment-without-docker.md) 참고.
