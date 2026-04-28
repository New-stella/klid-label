# 비동기 작업 상태 추적

## 개요
Export, Import, 오토라벨링 등 모든 장시간 작업은 RQ(Redis Queue, django-rq) 기반 비동기로 처리된다.
클라이언트는 `GET /api/requests/{rq_id}` 폴링으로 상태를 추적한다.

## 핵심 코드 위치
| 역할 | 파일 경로 |
|------|---------|
| RequestViewSet | `cvat/apps/redis_handler/views.py` |
| URL 등록 | `cvat/apps/redis_handler/urls.py` |
| 직렬화 / RequestStatus | `cvat/apps/redis_handler/serializers.py` |
| RQ Job 커스텀 클래스 | `cvat/apps/redis_handler/rq.py` |

## RequestStatus 상태 값

```python
# cvat/apps/redis_handler/serializers.py:RequestStatus
class RequestStatus(TextChoices):
    QUEUED   = "queued"    # 큐에 대기 중
    STARTED  = "started"   # 실행 중
    FAILED   = "failed"    # 실패
    FINISHED = "finished"  # 완료
```

## 비동기 작업 흐름

```
1. 요청 → 202 Accepted
   응답 헤더: X-Request-Id: <rq_id>
   (또는 응답 바디 rq_id 필드)

2. 클라이언트 폴링: GET /api/requests/{rq_id}
   → {
       "id": "<rq_id>",
       "status": "queued|started|finished|failed",
       "progress": 0~100,
       "enqueued": "2024-01-01T00:00:00Z",
       "started": "2024-01-01T00:00:01Z",
       "ended": null,
       "operation": {
           "type": "export",
           "target": "task",
           "task_id": 5,
           "format": "YOLO 1.0"
       }
     }

3. status == "finished" → 결과 URL로 다운로드
   status == "failed"   → exc_info 필드에 오류 정보
```

## 필터 파라미터 (GET /api/requests)

```
status      → queued | started | finished | failed
task_id     → 태스크 ID
job_id      → 잡 ID
project_id  → 프로젝트 ID
action      → export | import | autoannotate 등
target      → task | job | project
format      → 포맷명 (예: YOLO 1.0)
```

## 적용 대상 API

| API | 비동기 여부 |
|-----|:---------:|
| GET /api/tasks/{id}/dataset | ✅ |
| GET /api/jobs/{id}/dataset | ✅ |
| GET /api/projects/{id}/dataset | ✅ |
| POST /api/tasks/{id}/data | ✅ |
| PUT /api/tasks/{id}/annotations | ✅ (Import) |
| POST /api/lambda/requests | ✅ |
| GET /api/tasks/{id}/annotations (대용량) | ✅ |
| POST /api/quality/reports | ✅ |
| GET /api/events | ✅ |

## RQ Job ID 형식

CVAT의 RQ Job ID는 단순 UUID가 아닌 구조화된 문자열이다.

```
# RequestId 형식 예시
export/task/5/dataset/YOLO+1.0
autoannotate/task/5/job/3/onnx-wongkinyiu-yolov7
import/task/5/annotations/COCO+1.0
```

`RequestId.parse_and_validate_queue()` 로 action/target/subresource/format 필드를 파싱한다.

## 핵심 의사결정

- **RQ(Redis Queue) 채택**: Celery 대신 가벼운 RQ를 사용한다. Django와의 통합이 단순하고 운영 부담이 적다.
- **상태 4종 단순화**: queued / started / finished / failed로 단순화. progress는 0~100으로 별도 필드.
- **구조화된 Job ID**: 단순 UUID가 아닌 `export/task/5/dataset/YOLO+1.0` 같은 의미 있는 ID로 중복 방지 및 필터링 지원.
- **상태 휘발성**: RQ Job 상태는 Redis에만 저장되며 일정 기간 후 자동 삭제. 늦게 폴링하면 404 반환.
- **워커 큐 분리**: 8개 워커로 큐별 격리(import/export/chunks/annotation/webhooks/quality_reports/consensus/utils).
- **UI 폴링 주기**: CVAT UI는 2초 간격으로 폴링.
- **워커 타임아웃**: 대형 영상 Export나 오토라벨링은 수십 분 소요 가능 → 워커 타임아웃을 충분히 길게 설정해야 한다.

## 독립 포팅 가이드

### 추출 난이도
**하** — 단순 RQ + Redis 패턴. 모듈 08에 단독 추출 코드 포함. 8개 큐 분리 운영 패턴이 핵심.

### 핵심 추출 대상 (필수 파일)
| 역할 | 원본 경로 | 포팅 시 행수/조치 |
|------|----------|-----------------|
| CVAT_QUEUES enum + RQ_QUEUES 설정 | `cvat/cvat/settings/base.py:274-348` | 80줄 |
| RQJobMetaField + Meta 패턴 | `cvat/cvat/apps/engine/rq.py:32-350` | 350줄 — 메타 필드 표준화 |
| RequestId (구조화된 Job ID) | `cvat/cvat/apps/redis_handler/rq.py:37-210` | 200줄 |
| RequestViewSet (상태 조회) | `cvat/cvat/apps/redis_handler/views.py` | 200줄 |
| RequestStatus serializer | `cvat/cvat/apps/redis_handler/serializers.py` | 50줄 |
| Worker 시작 명령 | `cvat/supervisord/worker.conf` | 단순 supervisord 설정 |

### 외부 라이브러리 의존성
| 라이브러리 | 버전 | 대체 가능? | 비고 |
|----------|------|:--------:|------|
| rq | >=1.15 | 필수 | RQ 코어 |
| redis | >=5.0 | 필수 | Redis client |
| django-rq | >=2.10 | 가능 | 순수 rq만으로 대체 가능 (모듈 08) |

### 내부 CVAT 의존성과 분리 방법
| 의존 모듈 | 포팅 시 처리 |
|---------|-----------|
| Django settings | 환경변수 또는 자체 config |
| `cvat.apps.engine.types.ExtendedRequest` | FastAPI Request로 대체 |
| 8개 큐 (CVAT_QUEUES enum) | 본인 시스템 작업 종류로 재구성 |

### 최소 동작 단위 (MVP)
- Redis 1개 인스턴스
- 큐 1~3개 (작업 종류에 맞춰)
- worker 프로세스 (큐당 1개씩)
- 상태 조회 REST API 1개

### 포팅 단계 (체크리스트)
1. [ ] Redis 설치 (또는 Dragonfly/Kvrocks 등 호환 DB)
2. [ ] 큐 분리 정책 결정 (시간/자원/우선순위 기준)
3. [ ] worker_setup.py (모듈 08) 작성
4. [ ] systemd unit 작성 (큐당)
5. [ ] 작업 함수 구현 (job.meta["progress"] 보고)
6. [ ] 상태 조회 REST API
7. [ ] 클라이언트 폴링 (2초 간격 권장)

### 통합 지점 (이 모듈을 다른 시스템에 붙일 때)
- 입력: enqueue (큐 이름 + 함수 + args + meta)
- 출력: job_id 반환 → 클라이언트 폴링
- 외부 인터페이스: REST `POST /tasks/{id}/{action}` → 202 Accepted + X-Request-Id, `GET /requests/{id}` → 상태/진행률

### 알려진 함정
- **RQ Job TTL**: 기본 result_ttl=500s. 클라이언트 늦게 폴링하면 404. result_ttl=86400 권장.
- **메타 직렬화**: pickle 기본 → JSONSerializer 권장 (보안). 단, 함수 인자가 JSON-safe해야 함.
- **워커 메모리 누수**: GPU 모델 등 큰 객체는 워커 프로세스에 누적. MAX_JOBS_PER_WORKER 또는 주기 재시작.
- **Job ID 인코딩**: URL path parameter로 사용 시 `.`, `/` 등 특수 문자 처리 (CVAT은 `.` → `~` 인코딩).
- **scheduler 별도 프로세스**: 주기 작업은 rq-scheduler 또는 rqscheduler를 별도로 실행.
- **redis 다운 복구**: 워커는 자동 재연결되지만 진행 중 Job은 timeout 후 failed. retry 정책 명시.

## Docker 미사용 대응

### Redis (인메모리, RQ 큐)

Redis는 모든 OS에 네이티브 패키지로 제공된다.

| OS | 설치 |
|----|------|
| Ubuntu/Debian | `apt install redis-server` (또는 redis.io APT 저장소로 7.x 설치) |
| RHEL/Rocky | `dnf install redis` |
| macOS | `brew install redis` |
| Alpine | `apk add redis` |

`/etc/redis/redis.conf`에서 `appendonly yes`(AOF 영속화) 설정 권장. CVAT은 `--save 60 100 --appendonly yes`를 사용한다.

### Kvrocks (온디스크, 청크 캐시)

Kvrocks는 Apache의 Redis 호환 디스크 영구 저장소다. Docker 없이 사용하려면:

1. **공식 바이너리 다운로드**: `https://github.com/apache/kvrocks/releases`에서 바이너리 다운로드 후 `/usr/local/bin/kvrocks`에 배치.
2. **빌드**: `git clone` 후 `./x.py build`로 빌드.
3. **Redis로 대체**: 메모리가 충분하면 Redis 한 인스턴스로 통합. 단점은 청크 캐시(수 GB)가 RAM을 점유.

```ini
# /etc/systemd/system/kvrocks.service 예시
[Unit]
Description=Apache Kvrocks
After=network.target

[Service]
Type=simple
User=kvrocks
ExecStart=/usr/local/bin/kvrocks -c /etc/kvrocks/kvrocks.conf
Restart=always

[Install]
WantedBy=multi-user.target
```

CVAT은 Kvrocks를 포트 6666으로 설정하므로 `kvrocks.conf`의 `port`를 6666으로 맞춘다.

### RQ Worker — systemd 분리 운영

각 큐별 워커를 별도 systemd unit으로 운영한다(워커 8개).

```ini
# /etc/systemd/system/cvat-worker-annotation.service 예시
[Unit]
Description=CVAT RQ worker (annotation)
After=redis-server.service postgresql.service

[Service]
Type=simple
User=cvat
WorkingDirectory=/opt/cvat
Environment="DJANGO_SETTINGS_MODULE=cvat.settings.production"
EnvironmentFile=/etc/cvat/cvat.env
ExecStart=/opt/cvat/venv/bin/python manage.py rqworker -v 3 annotation \
    --worker-class cvat.rqworker.DefaultWorker
Restart=always
RestartSec=10s
TimeoutStopSec=60s

[Install]
WantedBy=multi-user.target
```

`NUMPROCS` 대신 systemd 인스턴스(`@`)를 사용하면 워커 N개를 1개 unit 파일로 관리할 수 있다:

```ini
# cvat-worker-import@.service
[Service]
ExecStart=/opt/cvat/venv/bin/python manage.py rqworker import \
    --worker-class cvat.rqworker.DefaultWorker

# 시작 명령
systemctl enable --now cvat-worker-import@1.service cvat-worker-import@2.service
```

### 대체: supervisord

CVAT 컨테이너는 내부적으로 `supervisord` + `wait_for_deps.sh`를 사용한다(`supervisord/worker.conf`). 동일한 supervisord 설정을 호스트에 직접 적용하면 컨테이너 내부와 같은 프로세스 트리를 얻을 수 있다.

자세한 통합 배포는 [deployment-without-docker.md](deployment-without-docker.md) 참고.
