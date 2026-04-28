# RQ Worker 분리 운영 패턴

## 한 줄 요약
단일 Redis 인스턴스를 8개의 독립된 큐(import/export/annotation/webhooks/notifications/quality_reports/chunks/consensus/cleaning)로 나누고 각 큐에 별도의 워커 프로세스를 운영해 "한 작업이 다른 작업을 블로킹하지 않도록" 격리하는 패턴.

## 추출 가치
**왜 떼어 쓸 가치가 있는가**
- 복잡한 시스템에서 "큰 파일 업로드 한 건이 알림 전송 전체를 멈추는" 문제는 매우 흔함. CVAT의 큐 분리는 이 문제의 실전 해결책.
- Celery 대신 **가벼운 RQ**를 선택한 것도 포인트 — django-rq/rq 의존성은 Redis + rq 2개 패키지로 충분.
- 큐별로 `DEFAULT_TIMEOUT` 다르게 (chunks: 5m, auto_annotation: 24h) 설정 → 장시간 작업과 빠른 작업을 운영 수준에서 분리.
- Job ID를 **구조화된 문자열**(`export&target=task&target_id=5&format=YOLO~1.0`)로 만들어 필터링/중복 방지.

## 원본 코드 위치

| 역할 | 파일:라인 | 핵심 |
|------|---------|------|
| CVAT_QUEUES enum | `cvat/cvat/settings/base.py:274-283` | 8개 큐 이름 정의 |
| RQ_QUEUES 설정 | `cvat/cvat/settings/base.py:303-348` | 큐별 Redis 연결 + DEFAULT_TIMEOUT + PARSED_JOB_ID_CLASS |
| RQ Job 메타 스키마 | `cvat/cvat/apps/engine/rq.py:32-350` | `RQJobMetaField`, `ImmutableRQMetaAttribute`, `RQMetaWithFailureInfo` |
| RequestId (구조화된 Job ID) | `cvat/cvat/apps/redis_handler/rq.py:37-210` | `RequestId`, `RequestIdWithSubresource` |
| RQ Job View | `cvat/cvat/apps/redis_handler/views.py` | `RequestViewSet`, 상태 조회/취소 |
| RQ Job Serializer | `cvat/cvat/apps/redis_handler/serializers.py` | `RequestStatus` (queued/started/finished/failed) |
| scheduler | `cvat/rqscheduler.py`, `cvat/cvat/apps/engine/cron.py` | 주기적 작업 |
| Worker 시작 명령 | `cvat/supervisord/worker.conf` (예시) | `python manage.py rqworker <queue_name>` |
| Exception 처리 | `cvat/cvat/settings/base.py:365-368` | `RQ_EXCEPTION_HANDLERS` |

## 알고리즘/프로토콜 핵심

### 8개 큐와 각각의 책임

| 큐 이름 | DEFAULT_TIMEOUT | 책임 | 워커 추천 갯수 |
|---------|-----------------|------|-------------|
| `import` | 4h | annotation import (YOLO/COCO 파싱 + DB 반영) | 1-2 |
| `export` | 4h | dataset export (ZIP 생성) | 1-2 |
| `annotation` | 24h | AI 오토라벨링 (Nuclio/lambda 호출) | GPU 수 (보통 1) |
| `webhooks` | 1h | 외부 HTTP 전송 (HMAC-SHA256 서명) | 1 |
| `notifications` | 1h | 사용자 알림 | 1 |
| `quality_reports` | 1h | IoU 매칭 + 충돌 감지 | 1-2 |
| `cleaning` | 2h | 임시 파일 삭제, 만료 세션 정리 | 1 |
| `chunks` | 5m | 영상 → 청크 생성 (PyAV) | CPU 수 / 2 |
| `consensus` | 1h | 합의 어노테이션 비교 | 1 |

### 큐 분리 기준

1. **시간 격리**: 24시간 짜리 annotation이 1분짜리 chunks를 블로킹하지 않도록.
2. **자원 격리**: CPU 집약적(chunks, quality_reports)과 I/O 집약적(webhooks) 분리.
3. **우선순위**: 사용자가 기다리는 작업(chunks)이 백그라운드 작업(cleaning)보다 빠르게 처리되어야 함.
4. **실패 격리**: 외부 시스템 장애(webhooks의 target_url 다운)가 다른 작업에 영향 없도록.

### RQ 설정 (settings/base.py)

```python
REDIS_INMEM_SETTINGS = {
    "HOST": redis_inmem_host,
    "PORT": redis_inmem_port,
    "DB": REDIS_INMEM_DATABASES.RQ,       # 0
    "PASSWORD": redis_inmem_password,
}

RQ_QUEUES = {
    CVAT_QUEUES.IMPORT_DATA.value: {
        **REDIS_INMEM_SETTINGS,
        "DEFAULT_TIMEOUT": "4h",
        "PARSED_JOB_ID_CLASS": "cvat.apps.engine.rq.ImportRequestId",
    },
    CVAT_QUEUES.EXPORT_DATA.value: {
        **REDIS_INMEM_SETTINGS,
        "DEFAULT_TIMEOUT": "4h",
        "PARSED_JOB_ID_CLASS": "cvat.apps.engine.rq.ExportRequestId",
    },
    # ... 8개
}
```

모든 큐가 같은 Redis DB를 사용하지만 큐 이름(`import`, `export`, ...)으로 분리된다. Redis 키는 `rq:queue:{name}` 패턴.

### RQ Job 메타데이터 스키마 (RQJobMetaField)

```python
class RQJobMetaField:
    # 공통
    REQUEST = "request"              # {uuid, timestamp}
    USER = "user"                    # {id, username, email}
    ORG_ID = "org_id"
    ORG_SLUG = "org_slug"
    PROJECT_ID = "project_id"
    TASK_ID = "task_id"
    JOB_ID = "job_id"
    STATUS = "status"                # queued | started | finished | failed
    PROGRESS = "progress"            # 0.0 ~ 1.0

    # import 전용
    TASK_PROGRESS = "task_progress"

    # export 전용
    RESULT_URL = "result_url"
    RESULT_FILENAME = "result_filename"

    # lambda (AI 오토라벨링)
    LAMBDA = "lambda"
    FUNCTION_ID = "function_id"

    # 실패 정보
    FORMATTED_EXCEPTION = "formatted_exception"
    EXCEPTION_TYPE = "exc_type"
    EXCEPTION_ARGS = "exc_args"
```

### 구조화된 Job ID (RequestId — redis_handler/rq.py)

```
export&target=task&target_id=5&format=YOLO~1.0
import&target=task&target_id=5&subresource=annotations
autoannotate&target=task&target_id=5&id=<uuid>
```

- `&` 구분 key=value 쌍
- action / target / target_id or id / (선택) subresource / (선택) format
- URL path parameter로 사용 가능하도록 RFC3986 unreserved 문자만 사용
- `.`은 `~`로, ` `은 `_`로 인코딩

### 진행률 보고 패턴

```python
# worker 내부:
from rq import get_current_job

def long_task():
    job = get_current_job()
    total = 1000
    for i in range(total):
        do_work(i)
        job.meta["progress"] = i / total
        job.save_meta()

# client (polling):
# GET /api/requests/{rq_id} → {"progress": 0.45, "status": "started"}
```

### 재시도 / 실패 처리

```python
# cvat/apps/engine/rq.py:196
class RQMetaWithFailureInfo(AbstractRQMeta):
    formatted_exception = MutableRQMetaAttribute(..., optional=True)
    exc_type = MutableRQMetaAttribute(..., validator=lambda x: issubclass(x, BaseException))
    exc_args = MutableRQMetaAttribute(...)

    @staticmethod
    def _get_resettable_fields() -> list[str]:
        return [
            RQJobMetaField.FORMATTED_EXCEPTION,
            RQJobMetaField.EXCEPTION_TYPE,
            RQJobMetaField.EXCEPTION_ARGS,
            ...
        ]
```

실패 시 exception 정보를 메타에 저장. 재시도 시 해당 필드는 리셋.

### Worker 시작 명령

```bash
python manage.py rqworker -v 3 {queue_name} \
    --worker-class cvat.rqworker.DefaultWorker \
    --scheduler-class cvat.rqworker.DefaultScheduler
```

- `queue_name`: `import`, `export`, `annotation` 등 8개 중 하나 (또는 여러 개를 공백 구분으로)
- `-v 3`: 로그 레벨
- `--worker-class`: 커스텀 Worker (meta 자동 저장 등 추가)

### systemd unit (워커당 1개)

```ini
[Unit]
Description=CVAT RQ worker (import)
After=redis-server.service postgresql.service

[Service]
Type=simple
User=cvat
WorkingDirectory=/opt/cvat
Environment="DJANGO_SETTINGS_MODULE=cvat.settings.production"
EnvironmentFile=/etc/cvat/cvat.env
ExecStart=/opt/cvat/venv/bin/python manage.py rqworker import
Restart=always
RestartSec=10s
MemoryMax=4G

[Install]
WantedBy=multi-user.target
```

### Supervisord 대안

CVAT 컨테이너는 supervisord를 사용:
```ini
[program:rqworker_import]
command=python manage.py rqworker import
numprocs=2
process_name=%(program_name)s_%(process_num)s
stderr_logfile=/var/log/supervisor/rqworker_import.err.log
```

## 의존성

### 외부 라이브러리

| 라이브러리 | 버전 | 용도 | 대체 가능? |
|----------|------|------|---------|
| rq | >=1.15 | RQ 코어 | 필수 |
| redis | >=5.0 | Redis Python client | 필수 |
| django-rq | >=2.10 | Django 통합 (설정 관리, 관리자 페이지) | 순수 rq만으로 대체 가능 |

### 인프라

| 컴포넌트 | 용도 |
|---------|------|
| Redis 7+ | RQ 큐 + 상태 저장 |
| systemd/supervisord | 워커 프로세스 관리 |

## 단독 추출 예시 코드 — 순수 RQ (Django 없이)

```python
# worker_setup.py — Django-RQ 없이 순수 RQ로 큐 분리 패턴
import os
from datetime import timedelta

from redis import Redis
from rq import Queue, Worker
from rq.job import Job


REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379/0")


# 8개 큐 정의 (CVAT의 CVAT_QUEUES enum과 동등)
QUEUES = {
    "import":          timedelta(hours=4),
    "export":          timedelta(hours=4),
    "annotation":      timedelta(hours=24),
    "webhooks":        timedelta(hours=1),
    "notifications":   timedelta(hours=1),
    "quality_reports": timedelta(hours=1),
    "cleaning":        timedelta(hours=2),
    "chunks":          timedelta(minutes=5),
    "consensus":       timedelta(hours=1),
}


def get_redis() -> Redis:
    return Redis.from_url(REDIS_URL)


def get_queue(name: str) -> Queue:
    assert name in QUEUES, f"Unknown queue: {name}"
    return Queue(
        name=name,
        connection=get_redis(),
        default_timeout=int(QUEUES[name].total_seconds()),
    )


# 구조화된 job_id 유틸
def make_job_id(action: str, target: str, target_id: int, **extra: str) -> str:
    parts = [f"action={action}", f"target={target}", f"target_id={target_id}"]
    for k, v in extra.items():
        parts.append(f"{k}={v}")
    return "&".join(parts)


# === 작업 함수 (어느 모듈에나 있을 수 있음) ===

def long_export_task(task_id: int, format_name: str) -> str:
    """4시간 걸릴 수 있는 export 작업"""
    from rq import get_current_job
    job = get_current_job()
    for i in range(100):
        # ... actual work ...
        job.meta["progress"] = i / 100
        job.save_meta()
    return f"/exports/{task_id}_{format_name}.zip"


# === 작업 등록 (request 핸들러에서 호출) ===

def enqueue_export(task_id: int, format_name: str):
    queue = get_queue("export")
    job_id = make_job_id("export", "task", task_id, format=format_name.replace(".", "~"))
    job = queue.enqueue_call(
        func=long_export_task,
        args=(task_id, format_name),
        job_id=job_id,
        timeout=int(QUEUES["export"].total_seconds()),
        meta={
            "request": {"timestamp": "..."},
            "user": {"id": 5, "username": "alice"},
            "task_id": task_id,
        },
    )
    return job.id


# === 상태 조회 (REST API에서 호출) ===

def get_job_status(job_id: str) -> dict | None:
    try:
        job = Job.fetch(job_id, connection=get_redis())
    except Exception:
        return None
    return {
        "id": job.id,
        "status": job.get_status(),
        "progress": job.meta.get("progress", 0),
        "enqueued_at": job.enqueued_at,
        "started_at": job.started_at,
        "ended_at": job.ended_at,
        "result": job.result if job.is_finished else None,
        "exception": job.exc_info if job.is_failed else None,
    }


# === 워커 실행 (CLI) ===

def run_worker(queue_names: list[str]):
    redis = get_redis()
    queues = [Queue(q, connection=redis, default_timeout=int(QUEUES[q].total_seconds()))
              for q in queue_names]
    worker = Worker(queues, connection=redis)
    worker.work()


if __name__ == "__main__":
    import sys
    # 예: python worker_setup.py import export
    queue_names = sys.argv[1:] or ["default"]
    run_worker(queue_names)
```

### systemd unit (순수 RQ용)

```ini
# /etc/systemd/system/app-worker@.service
[Unit]
Description=App RQ worker (%i)
After=redis.service

[Service]
Type=simple
User=app
WorkingDirectory=/opt/app
Environment="REDIS_URL=redis://localhost:6379/0"
ExecStart=/opt/app/venv/bin/python worker_setup.py %i
Restart=always
RestartSec=10s

[Install]
WantedBy=multi-user.target
```

시작:
```bash
systemctl enable --now app-worker@import app-worker@export app-worker@chunks
```

`%i`에 각 큐 이름이 치환되어 8개 unit이 한 파일로 관리됨.

## 입력/출력 명세

### Enqueue 시
- **입력**: 큐 이름 + 작업 함수 + args + (선택) meta dict + (선택) custom job_id
- **출력**: RQ Job 객체 (job.id로 폴링)

### 상태 조회 시
- **입력**: job_id
- **출력**:
  ```json
  {
    "id": "export&target=task&target_id=5&format=YOLO~1.0",
    "status": "queued|started|finished|failed",
    "progress": 0.45,
    "enqueued_at": "...",
    "started_at": null,
    "ended_at": null,
    "result": null,
    "exception": null
  }
  ```

## 통합 가이드 (다른 시스템에 붙이는 법)

1. **큐 이름 결정**: 본인 시스템의 작업 종류를 분석. 비슷한 SLA/자원 프로파일의 작업끼리 묶어 4~10개 큐로 나눔.
2. **구조화된 Job ID**: `{action}&{target}&{target_id}` 패턴을 도입하면 중복 방지 + REST endpoint로 조회 가능.
3. **progress 보고**: 작업 함수에서 `job.meta["progress"]`를 수시로 업데이트 + `job.save_meta()`. 클라이언트는 2초 폴링.
4. **실패 처리**: `RQ_EXCEPTION_HANDLERS`에 커스텀 handler 등록 → 예외 타입/메시지를 meta에 저장 → REST 응답에 포함.
5. **만료 정책**: 기본적으로 RQ Job은 완료 후 500초 보관. `result_ttl=86400`으로 1일 보관 권장 (클라이언트가 늦게 폴링해도 결과 확보).

## 검증 방법

1. **Isolation 테스트**: `annotation` 큐에 10분짜리 작업 1개 + `chunks` 큐에 5초 작업 10개. 10개가 10분 안에 완료되는지 (순서대로 처리되지 않고 병렬).
2. **타임아웃 동작**: `export` 큐에 4시간+1초 동안 sleep하는 작업 → 4시간 시점에 자동 실패되는지.
3. **재시작 복구**: 워커 SIGKILL → 시스템d가 재시작 → 미완료 Job이 `failed` 상태로 전환되는지.
4. **Redis 다운**: Redis 재시작 → 워커가 자동 재연결하는지 (RestartSec 이후).
5. **Meta 업데이트**: 작업 중 `job.meta["progress"]` 변경 → 다른 프로세스에서 Job.fetch() 시 최신 값 읽히는지.

## 알려진 한계와 함정

- **글로벌 락 없음**: 같은 job_id로 동시에 enqueue하면 2개 Job이 생성될 수 있음 (rq의 기본 정책). `enqueue_call(job_id=..., dependency=...)` 또는 자체 락 필요.
- **RQ Job TTL**: 기본 `result_ttl=500s`, `ttl=None`(무한). 완료된 Job의 결과를 오래 보관하려면 `result_ttl=86400` 등 명시.
- **메타 크기**: job.meta는 Redis hash에 저장. 너무 크면 (수 MB) Redis 성능 저하. 대용량 결과는 파일/S3에 저장하고 URL만 meta에 기록.
- **직렬화**: RQ 기본은 pickle. 보안상 JSONSerializer 권장 (`connection=redis, serializer=JSONSerializer`). 단, 함수 인자가 JSON-safe해야 함.
- **long polling 없음**: rq는 polling 기반. 클라이언트가 즉시 알림을 받으려면 별도 pubsub 구현 필요.
- **워커 메모리 누수**: annotation 워커가 모델을 로드하면 GPU/RAM 누적 가능. `MAX_JOBS_PER_WORKER` 또는 주기적 재시작 필요.
- **워커 개수 조정**: `chunks` 큐는 CPU bound → 워커 수 = CPU 수. `annotation`은 GPU bound → GPU 수. 잘못 설정하면 OOM 또는 idle 자원.
- **job_id 인코딩**: URL path parameter로 사용하려면 `.`, `/`, `+`, `&` 등 특수 문자 처리. CVAT은 `.` → `~`로 인코딩 (RequestId.ENCODE_MAPPING).
- **Scheduler**: 주기적 작업은 `rq-scheduler` 또는 `rqscheduler` (django-rq 포함). 별도 프로세스로 실행 필요.

## 라이선스 주의사항

CVAT MIT, rq BSD-3-Clause, django-rq MIT. 추출 시:
```python
# Adapted from CVAT (https://github.com/cvat-ai/cvat) apps/engine/rq.py, apps/redis_handler
# Copyright (C) CVAT.ai Corporation
# SPDX-License-Identifier: MIT
```
