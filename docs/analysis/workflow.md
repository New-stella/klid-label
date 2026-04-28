# Task/Job 워크플로우

## 개요
CVAT의 작업 단위는 `Project → Task → Segment → Job` 4계층이다. Job은 실제 작업의 최소 단위이며, `stage`(단계)와 `state`(상태) 두 필드로 현재 진행상황을 표현한다.

## 핵심 코드 위치
| 역할 | 파일 경로 | 비고 |
|------|---------|------|
| Task/Job/Segment 모델 | `cvat/apps/engine/models.py:L744` | Task, Segment, Job 클래스 |
| StageChoice/StateChoice | `cvat/apps/engine/models.py:L103` | 상태 열거형 |
| JobViewSet | `cvat/apps/engine/views.py:L1653` | Job CRUD + 상태 변경 |
| TaskViewSet | `cvat/apps/engine/views.py:L836` | Task CRUD + 데이터 업로드 |
| Task 생성 로직 | `cvat/apps/engine/task.py` | _create_thread |

## 모델 계층 구조

```
Project (선택)
  └── Task (영상/이미지 묶음)
        └── Segment (프레임 범위 분할)
              └── Job (실제 작업 단위)
                    ├── LabeledShape (어노테이션)
                    ├── LabeledTrack (트랙)
                    └── LabeledImage (태그)
```

## 상태 전이 {#state-transition}

### Stage (단계) — 관리자/매니저가 변경
```python
class StageChoice(str, Enum):
    ANNOTATION  = 'annotation'   # 라벨링 중
    VALIDATION  = 'validation'   # 검수 중
    ACCEPTANCE  = 'acceptance'   # 완료/승인
```

### State (상태) — 작업자가 변경
```python
class StateChoice(str, Enum):
    NEW         = 'new'          # 미시작
    IN_PROGRESS = 'in progress'  # 진행 중
    COMPLETED   = 'completed'    # 완료
    REJECTED    = 'rejected'     # 반려
```

### 상태 전이 다이어그램

```
[Stage: annotation, State: new]        ← Job 생성 초기 상태
         ↓ 라벨러 작업 시작
[Stage: annotation, State: in progress]
         ↓ 라벨러 완료
[Stage: annotation, State: completed]
         ↓ 매니저/인스펙터 검수 시작 (stage 변경)
[Stage: validation, State: in progress]
         ├─ 승인 → [Stage: acceptance, State: completed]
         └─ 반려 → [Stage: annotation, State: rejected]
                          ↓ 라벨러 재작업
                   [Stage: annotation, State: in progress]

# Job 타입별 추가 상태
JobType.GROUND_TRUTH    → GT Job (품질 평가용, Task당 1개만)
JobType.ANNOTATION      → 일반 작업 Job
JobType.CONSENSUS_REPLICA → 합의 어노테이션용 복제 Job
```

### 레거시 status 필드
`status` 필드는 Deprecated이며, `(stage, state)` 조합으로 자동 계산된다.

```python
# 대응 관계:
# annotation + new/in_progress → StatusChoice.ANNOTATION
# validation + * → StatusChoice.VALIDATION
# acceptance + completed → StatusChoice.COMPLETED
```

## Task → Job 생성 로직

```python
# cvat/apps/engine/task.py:_generate_segment_params
# 영상 길이와 segment_size, overlap에 따라 Segment 자동 생성

def _generate_segment_params(db_task, data_size=None, job_file_mapping=None):
    if job_file_mapping:
        # 파일별 Job 매핑 (job_file_mapping 명시 시)
        # 각 파일 그룹이 하나의 Segment/Job이 됨
    else:
        # 기본: segment_size 프레임씩 분할, overlap 프레임 겹침
        segment_size = db_task.segment_size or data_size
        overlap = db_task.overlap  # 인접 Job 간 겹치는 프레임 수
```

### Segment 타입

```python
class SegmentType(str, Enum):
    RANGE           = 'range'           # start_frame ~ stop_frame 연속 범위
    SPECIFIC_FRAMES = 'specific_frames' # 특정 프레임 번호 목록 (GT Job용)
```

## 라벨러 할당 방식 {#role-structure}

CVAT의 역할 구조 (조직 내):

```python
# cvat/apps/organizations/models.py
class Membership:
    WORKER     = "worker"      # 일반 작업자 (라벨러)
    SUPERVISOR = "supervisor"  # 감독자
    MAINTAINER = "maintainer"  # 관리자
    OWNER      = "owner"       # 오너
```

Job 할당:
```python
# Job.assignee: ForeignKey(User)
# Job.assignee_updated_date: 할당 시각

# API:
# PATCH /api/jobs/{id} {"assignee": {"id": 5}}
```

Task 소유자/할당자:
```python
# Task.owner: 생성자 (변경 불가)
# Task.assignee: 담당 매니저 (변경 가능)
```

## 검수 플로우 {#review-flow}

CVAT의 검수는 Job stage를 `validation`으로 변경하고, 인스펙터가 Issues를 생성하거나 state를 변경하는 방식으로 진행된다.

```python
# Issue 모델 (cvat/apps/engine/models.py:L1465)
class Issue(TimestampedModel, AssignableModel):
    frame    = PositiveIntegerField  # 이슈가 발생한 프레임
    position = FloatArrayField       # 화면 내 이슈 마커 좌표
    job      = ForeignKey(Job)
    owner    = ForeignKey(User)      # 이슈 생성자
    resolved = BooleanField          # 해결 여부

class Comment(TimestampedModel):
    issue   = ForeignKey(Issue)
    owner   = ForeignKey(User)
    message = TextField
```

### 검수 API 흐름

```
1. 인스펙터가 stage를 validation으로 변경
   PATCH /api/jobs/{id} {"stage": "validation"}

2. 특정 프레임에 이슈 생성
   POST /api/issues {"frame": 10, "job": {id}, "position": [100, 200]}

3. 코멘트 추가
   POST /api/comments {"issue": {id}, "message": "..."}

4. 승인 시: stage를 acceptance, state를 completed로 변경
   PATCH /api/jobs/{id} {"stage": "acceptance", "state": "completed"}

5. 반려 시: stage를 annotation, state를 rejected로 변경
   PATCH /api/jobs/{id} {"stage": "annotation", "state": "rejected"}
```

## 통계 조회

```python
# TaskQuerySet.with_job_summary()
# Task.objects.with_job_summary() 호출 시 아래 필드 추가 어노테이션:
# - total_jobs_count
# - completed_jobs_count
# - validation_jobs_count
```

## 핵심 의사결정

- **stage + state 2단계 분리**: 단계(stage)는 매니저/관리자가 변경하고 상태(state)는 작업자가 변경한다. 책임 경계와 권한 검사 분리에 유리.
- **status 필드 Deprecated**: 단일 `status` 필드는 레거시이며, 신규 코드에서는 `(stage, state)` 조합으로 자동 계산한다.
- **Job 타입 분기**: ANNOTATION(일반) / GROUND_TRUTH(품질 평가, Task당 1개만) / CONSENSUS_REPLICA(합의 어노테이션) 3종.
- **Segment 자동 생성**: Task 생성 시 `segment_size`, `overlap` 파라미터로 Job을 자동 분할. 수동 분할 시 `job_file_mapping`으로 파일 그룹별 Job 정의 가능.
- **assignee + assignee_updated_date**: 할당 사실과 할당 시각을 함께 기록하여 SLA 계산 및 감사 가능.
- **Issue/Comment 구조**: 검수는 프레임별 이슈 마커 + 코멘트 스레드 형태로 진행한다.
- **with_job_summary() 집계**: TaskQuerySet에 어노테이션을 추가하여 단일 쿼리로 잡 통계를 집계(N+1 방지).
- **상태 전이 잠금**: `SELECT ... FOR UPDATE`로 동시 상태 전이 충돌을 방지한다.

## 독립 포팅 가이드

### 추출 난이도
**중** — 모델 자체는 단순하지만 Project→Task→Segment→Job 4계층 + GT/CONSENSUS 분기 + assignee 권한 체크가 얽혀 있다. 단일 task 단일 job 가정으로 단순화하면 200줄.

### 핵심 추출 대상 (필수 파일)
| 역할 | 원본 경로 | 포팅 시 행수/조치 |
|------|----------|-----------------|
| Task/Segment/Job 모델 | `cvat/apps/engine/models.py:744-1100` | 200줄 |
| StageChoice / StateChoice | `cvat/apps/engine/models.py:103-126` | 30줄 |
| JobType / SegmentType | `cvat/apps/engine/models.py:176-200` | 20줄 |
| Issue / Comment 모델 | `cvat/apps/engine/models.py:1465-1525` | 50줄 (검수 시 사용) |
| TaskQuerySet (with_job_summary) | `cvat/apps/engine/models.py` (검색: `def with_job_summary`) | annotate 패턴 |
| TaskViewSet | `cvat/apps/engine/views.py:836` | 큼. Task 생성/수정/할당 |
| JobViewSet | `cvat/apps/engine/views.py:1653` | 큼. Job 상태 전이 |

### 외부 라이브러리 의존성
| 라이브러리 | 버전 | 대체 가능? | 비고 |
|----------|------|:--------:|------|
| Django ORM | >=4.2 | 가능 | annotate (with_job_summary)는 SQLAlchemy로 재작성 필요 |
| DRF | >=3.14 | 가능 | Pydantic+FastAPI |

### 내부 CVAT 의존성과 분리 방법
| 의존 모듈 | 포팅 시 처리 |
|---------|-----------|
| Project 모델 | 단일 프로젝트 가정 시 제거 가능 |
| Organization/Membership | 단일 조직 가정 시 제거 |
| GT Job (JobType.GROUND_TRUTH) | 품질 평가 안 쓰면 제거. 모듈 09 참조 |
| ConsensusReplica | 합의 어노테이션 안 쓰면 제거 |
| Issue/Comment | 검수 워크플로우 단순화 시 제거. state=REJECTED 사유는 별도 컬럼 |

### 최소 동작 단위 (MVP)
- Task + Segment + Job 3개 테이블 (Project 없이)
- API 5개: `POST /tasks`, `GET /tasks/{id}`, `GET /jobs`, `PATCH /jobs/{id}` (state/stage 변경), `PATCH /jobs/{id}/assignee`
- 상태 전이 로직: stage(annotation→validation→acceptance) + state(new→in_progress→completed/rejected)

### 포팅 단계 (체크리스트)
1. [ ] StageChoice/StateChoice/JobType enum 정의
2. [ ] Task/Segment/Job 모델 (Project 없이)
3. [ ] _generate_segment_params 알고리즘 (영상 길이 → 자동 분할)
4. [ ] Job 상태 전이 검증 (annotation+completed → validation 등)
5. [ ] assignee 변경 + assignee_updated_date 자동 갱신
6. [ ] Issue/Comment (선택)

### 통합 지점 (이 모듈을 다른 시스템에 붙일 때)
- 입력: Task 생성 요청 + 영상 메타 (frame_count, fps)
- 출력: Task → 자동 생성된 Segment 리스트 + Job 리스트
- 외부 인터페이스: 상태 변경 API (관리자/작업자별 권한 검사 별도)

### 알려진 함정
- **status 필드 deprecated**: 신규 코드는 (stage, state) 조합만 사용. 레거시 status 필드는 자동 계산.
- **assignee_updated_date 누락**: assignee를 업데이트할 때 매번 timestamp도 같이 수정해야 함. signal 또는 명시적 set.
- **상태 전이 잠금**: `SELECT ... FOR UPDATE` 누락 시 두 사용자가 동시에 다른 state로 변경 시 race condition.
- **Segment overlap 처리**: overlap > 0이면 인접 Job이 같은 프레임을 공유 → 동일 어노테이션이 두 Job에 표시될 수 있음. 일관성 정책 필요.
- **GT Job 단일성**: Task당 GROUND_TRUTH job은 1개만. unique constraint를 DB 레벨에서 강제.

## Docker 미사용 대응

이 모듈은 데이터 모델과 상태 전이 로직이므로 직접적인 Docker 의존성 없음. 다만 워커 분리 정책 상, RQ 워커 8종이 별도 컨테이너로 운영된다(아래 참조).

### 워커 분리 이유

CVAT의 워커 8개는 각각 독립된 큐를 처리한다. Docker 미사용 시에도 같은 분리 패턴을 유지하는 것이 좋다.

| 큐 | 분리 이유 |
|----|----------|
| import | 대용량 import가 다른 작업을 막지 않도록 분리 |
| export | export는 시간이 길어 별도 워커로 격리 |
| annotation | 오토라벨링은 GPU/CPU 점유율이 높음 |
| chunks | 청크 생성은 영상 디코딩으로 CPU 집약적 |
| webhooks | 외부 HTTP 호출은 타임아웃 위험이 있어 격리 |
| quality_reports | IoU 계산이 CPU 집약적 |
| consensus | 합의 어노테이션 비교 로직 |
| utils | 알림, 청소 등 가벼운 작업 |

### systemd 기반 워커 분리

Docker 없이는 systemd unit 8개(워커 종류별)로 동등 격리한다. 각 unit에 `MemoryMax`, `CPUQuota`로 자원 제한을 두면 컨테이너 격리에 근접한 효과를 얻을 수 있다.

```ini
# /etc/systemd/system/cvat-worker-import.service 예시
[Unit]
Description=CVAT RQ worker (import)
After=redis-server.service postgresql.service

[Service]
Type=simple
User=cvat
WorkingDirectory=/opt/cvat
Environment="DJANGO_SETTINGS_MODULE=cvat.settings.production"
ExecStart=/opt/cvat/venv/bin/python manage.py rqworker -v 3 import \
    --worker-class cvat.rqworker.DefaultWorker
Restart=always
MemoryMax=4G

[Install]
WantedBy=multi-user.target
```

### nproc 기반 워커 수

단일 호스트 운영 시 `NUMPROCS` 환경변수에 다음 값을 권장한다(docker-compose.yml 기본값 기준):

| 워커 | NUMPROCS 기본 | 권장 |
|------|------|------|
| server (uvicorn) | 2 | `min(nproc, 4)` |
| import | 2 | `max(2, nproc / 4)` |
| export | 2 | `max(2, nproc / 4)` |
| chunks | 2 | `max(2, nproc / 2)` |
| annotation | 1 | GPU 수 |
| webhooks | 1 | 1 |
| quality_reports | 1 | 1 |
| consensus | 1 | 1 |
| utils | 1 | 1 |

자세한 systemd unit 작성은 [deployment-without-docker.md](deployment-without-docker.md) 참고.
