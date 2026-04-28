# 웹훅 및 이벤트 시스템

## 개요
CVAT은 두 개의 독립적인 이벤트 시스템을 가진다.
- **Webhook 시스템** (`cvat/apps/webhooks/`): 외부 서버로 이벤트 HTTP 전송
- **Events 시스템** (`cvat/apps/events/`): ClickHouse에 사용자 활동 로깅 + 클라이언트 이벤트 수집

## 핵심 코드 위치

| 역할 | 파일 경로 |
|------|---------|
| Webhook 모델 | `cvat/apps/webhooks/models.py` |
| 이벤트 타입 목록 | `cvat/apps/webhooks/event_type.py` |
| Webhook 전송 로직 | `cvat/apps/webhooks/signals.py` |
| Events 이벤트 스코프 | `cvat/apps/events/event.py` |
| ClickHouse 내보내기 | `cvat/apps/events/export.py` |
| 이벤트 핸들러 | `cvat/apps/events/handlers.py` |

---

## 웹훅 시스템

### Webhook 모델 필드

```python
# cvat/apps/webhooks/models.py:Webhook
class Webhook(TimestampedModel):
    target_url    = URLField(max_length=8192)         # 전송 대상 URL
    description   = CharField(max_length=128)         # 설명
    events        = CharField(max_length=4096)        # 구독 이벤트 목록 (쉼표 구분)
    type          = CharField                         # "organization" | "project"
    content_type  = CharField                         # 항상 "application/json"
    secret        = CharField(max_length=64)          # HMAC-SHA256 서명 키 (선택)
    is_active     = BooleanField(default=True)        # 활성화 여부
    enable_ssl    = BooleanField(default=True)        # SSL 인증서 검증 여부
    owner         = FK(User)                          # 생성자
    project       = FK(Project, nullable)             # 프로젝트 웹훅 시
    organization  = FK(Organization, nullable)        # 조직 웹훅 시
```

DB 제약: `type=project` 이면 `project_id IS NOT NULL`, `type=organization` 이면 `project_id IS NULL, organization_id IS NOT NULL`

### WebhookDelivery 모델 (전송 이력)

```python
class WebhookDelivery(TimestampedModel):
    webhook        = FK(Webhook, related_name="deliveries")
    event          = CharField(max_length=64)          # 이벤트 타입 (예: "create:task")
    status_code    = PositiveIntegerField(nullable)    # HTTP 응답 코드
    redelivery     = BooleanField(default=False)       # 재전송 여부
    changed_fields = CharField(max_length=4096)        # 변경된 필드 목록
    request        = JSONField                         # 전송한 payload 전체
    response       = JSONField                         # 수신한 응답 전체
```

### 지원하는 이벤트 타입

```python
# cvat/apps/webhooks/event_type.py:Events.RESOURCES
RESOURCES = {
    "project":      ["create", "update", "delete"],
    "task":         ["create", "update", "delete"],
    "job":          ["create", "update", "delete"],
    "issue":        ["create", "update", "delete"],
    "comment":      ["create", "update", "delete"],
    "organization": ["update", "delete"],
    "invitation":   ["create", "delete"],
    "membership":   ["create", "update", "delete"],
}

# 이벤트 이름 형식: "{action}:{resource}"
# 예: "create:task", "update:job", "delete:project"
```

### Webhook 적용 범위

| 타입 | 구독 가능한 이벤트 |
|------|-----------------|
| `project` | task, job, label, issue, comment, project(update/delete) |
| `organization` | 모든 이벤트 (AllEvents) |

### 이벤트 발행 → Webhook 전송 흐름

```
Django signal (post_save, post_delete)
    ↓ cvat/apps/webhooks/signals.py (receiver)
    ↓ 관련 Webhook 목록 조회
    ↓ RQ 비동기 큐에 전송 작업 등록 (django-rq)
    ↓ send_webhook(webhook, payload)
       - HMAC-SHA256 서명 (secret 설정 시)
       - X-Signature-256 헤더 추가
       - POST {target_url} (timeout: 10초)
       - WebhookDelivery 저장
```

### HMAC 서명 헤더

```
X-Signature-256: sha256=<hex_digest>
# payload = JSON.dumps(event_payload)
# key = webhook.secret.encode('utf-8')
```

---

## Events 시스템 (ClickHouse 이벤트 로깅)

### 이벤트 스코프 목록

```python
# cvat/apps/events/event.py:EventScopes.RESOURCES
RESOURCES = {
    "accesstoken":   ["create", "update", "delete"],
    "project":       ["create", "update", "delete"],
    "task":          ["create", "update", "delete"],
    "job":           ["create", "update", "delete"],
    "organization":  ["create", "update", "delete"],
    "membership":    ["create", "update", "delete"],
    "invitation":    ["create", "delete"],
    "user":          ["create", "update", "delete"],
    "cloudstorage":  ["create", "update", "delete"],
    "issue":         ["create", "update", "delete"],
    "comment":       ["create", "update", "delete"],
    "annotations":   ["create", "update", "delete"],
    "label":         ["create", "update", "delete"],
    "dataset":       ["export", "import"],
    "function":      ["call"],
    "webhook":       ["create", "update", "delete"],
}
```

### ClickHouse 이벤트 로깅 구조

```python
# cvat/apps/events/export.py
# clickhouse-connect 클라이언트로 CLICKHOUSE["events"] DB에 연결
# 이벤트는 ClickHouse 테이블에 INSERT (분석용 이벤트 로그)
# CSV 형식으로 내보내기 지원: GET /api/events (비동기, RQ 처리)

CLICKHOUSE_SETTINGS = settings.CLICKHOUSE["events"]
# HOST, NAME(DB명), PORT, USER, PASSWORD 환경변수로 구성
```

### 클라이언트 이벤트 수집

프론트엔드에서 사용자 액션(클릭, 그리기 등)을 일괄 수집하여 서버로 전송한다.

```
POST /api/events
Body: [
    {
        "scope": "draw:shape",
        "task_id": 5,
        "job_id": 3,
        "timestamp": "2024-01-01T00:00:00Z",
        ...
    }
]
→ 202 Accepted (RQ로 ClickHouse에 비동기 저장)
```

---

## 핵심 의사결정

- **두 시스템 분리**: 외부 알림(Webhook)과 내부 활동 로깅(Events)을 별도 앱으로 분리하여 책임을 격리한다.
- **Django signal → RQ 비동기 전송**: 모델 변경(post_save/post_delete) 시 signal로 RQ 큐에 등록 → 워커가 HTTP POST. 트랜잭션 외부에서 처리하여 본 트랜잭션을 막지 않는다.
- **HMAC-SHA256 서명**: `secret` 설정 시 payload를 서명하여 `X-Signature-256: sha256=<hex>` 헤더로 전송. 수신 서버가 위변조 검증 가능.
- **타임아웃 10초 고정**: `WEBHOOK_TIMEOUT = 10`. 수신 서버 응답 지연 시 재전송 큐로 이동.
- **응답 크기 제한**: `RESPONSE_SIZE_LIMIT = 1MB`. 초과 시 잘라냄.
- **Webhook 범위 2종**: project / organization 범위로 구독 범위 제어.
- **WebhookDelivery 이력**: request/response JSON 전체를 저장하여 디버깅/감사 가능.
- **ClickHouse 사용 이유**: 이벤트 로그는 INSERT 위주 + 분석 쿼리(GROUP BY) 위주 → PostgreSQL보다 ClickHouse가 효율적. CSV export 시에도 빠르다.
- **클라이언트 이벤트 수집**: 프론트엔드 사용자 액션(클릭, 드로잉)을 일괄 수집해 `POST /api/events`로 전송 → RQ로 ClickHouse에 비동기 저장.
- **SSL 검증 옵션**: `enable_ssl=False` 옵션은 개발용. 운영 환경에서는 활성화 필수.

## 독립 포팅 가이드

### 추출 난이도
**하** (Webhook) — Webhook 모델 + send_webhook 함수만 추출. 단순 모델 + HTTP POST + HMAC. 200줄 이내.
**중** (Events) — ClickHouse 의존성. 단순 ClickHouse INSERT 패턴.

### 핵심 추출 대상 (필수 파일)
| 역할 | 원본 경로 | 포팅 시 행수/조치 |
|------|----------|-----------------|
| Webhook / WebhookDelivery 모델 | `cvat/apps/webhooks/models.py` | 100줄 |
| send_webhook 함수 (HMAC-SHA256) | `cvat/apps/webhooks/signals.py:40-85` | 50줄 |
| Django signal receiver | `cvat/apps/webhooks/signals.py` | 200줄 — 모델 변경 감지 |
| event_type 정의 | `cvat/apps/webhooks/event_type.py` | 100줄 |
| RQ webhooks 워커 | (모듈 08) | 큐 1개 |
| ClickHouse 클라이언트 | `cvat/apps/events/export.py` | clickhouse-connect 사용 |
| EventScopes | `cvat/apps/events/event.py` | 50줄 |

### 외부 라이브러리 의존성
| 라이브러리 | 버전 | 대체 가능? | 비고 |
|----------|------|:--------:|------|
| requests | >=2.28 | 필수 | HTTP POST |
| Django signals | >=4.2 | 가능 | 자체 이벤트 시스템 (FastAPI Depends 등) |
| django-rq | (선택) | 가능 | 모듈 08 |
| clickhouse-connect | >=0.7 | 가능 | PostgreSQL/SQLite로 대체 (성능 트레이드오프) |

### 내부 CVAT 의존성과 분리 방법
| 의존 모듈 | 포팅 시 처리 |
|---------|-----------|
| `cvat.utils.http.PROXIES_FOR_UNTRUSTED_URLS` | 환경변수에서 직접 |
| `cvat.utils.http.make_requests_session` | 표준 `requests.Session` |
| `cvat.apps.events.handlers.get_instance_diff` | 모델 변경 diff 추출 — 자체 구현 필요 |
| Django ORM (Webhook 모델) | SQLAlchemy 등으로 대체 |

### 최소 동작 단위 (MVP)
- Webhook 모델 1개 (target_url, secret, events) — `name=foo&events=create:task,update:task`
- send_webhook 함수 (HTTP POST + HMAC-SHA256)
- 비동기 큐 (모듈 08의 webhooks 큐)
- 모델 변경 감지 후 enqueue (Django signal 또는 ORM hook)

### 포팅 단계 (체크리스트)
1. [ ] Webhook 모델 (target_url, secret, events 구독 list, is_active, enable_ssl)
2. [ ] WebhookDelivery 모델 (status_code, request, response, redelivery)
3. [ ] HMAC-SHA256 서명 (X-Signature-256 헤더)
4. [ ] HTTP POST + 타임아웃 10초 + 응답 1MB 제한
5. [ ] 모델 변경 시 자동 발행 (signal 또는 hook)
6. [ ] RQ 비동기 큐 (webhooks 큐 분리)
7. [ ] (이벤트 로깅 시) ClickHouse 또는 PostgreSQL INSERT

### 통합 지점 (이 모듈을 다른 시스템에 붙일 때)
- 입력: 모델 변경 (Django post_save/post_delete) → payload 생성 → enqueue
- 출력: 외부 URL HTTP POST + 응답을 WebhookDelivery에 기록
- 외부 인터페이스: `POST /api/webhooks` (구독 등록), `GET /api/webhooks/{id}/deliveries` (이력 조회)

### 알려진 함정
- **HMAC payload**: `json.dumps(payload).encode("utf-8")`로 서명. 직렬화 옵션(separators, sort_keys)이 다르면 서명 검증 실패.
- **enable_ssl=False**: 개발용. 운영에서는 절대 비활성화 금지 (man-in-the-middle 공격).
- **타임아웃 10초**: 수신 서버 응답 지연 시 BAD_GATEWAY/GATEWAY_TIMEOUT으로 기록. 재시도 정책 별도.
- **응답 크기 제한 1MB**: `RESPONSE_SIZE_LIMIT`. 초과 시 잘라냄. 큰 응답이 예상되면 한도 조정.
- **post_save signal 트랜잭션 외부**: signal은 트랜잭션 commit 전에 발화 → DB 변경이 아직 적용 안 된 상태로 webhook 전송 가능. `transaction.on_commit()`으로 감싸야 안전.
- **secret 평문 저장**: CVAT은 secret을 평문으로 DB 저장. 운영 환경에서는 암호화 권장.
- **ClickHouse 23.11**: CVAT은 23.11. 더 새로운 버전과는 일부 SQL 호환성 차이 가능.
- **클라이언트 이벤트 PII**: 사용자 액션 로그에 PII 포함 가능. GDPR 등 데이터 보존/삭제 정책 필요.

## Docker 미사용 대응

### ClickHouse 네이티브 설치

ClickHouse는 공식 APT/DNF 저장소를 제공하므로 Docker 없이 설치 가능.

| OS | 설치 |
|----|------|
| Ubuntu/Debian | `apt-key + apt repo` 추가 후 `apt install clickhouse-server clickhouse-client` |
| RHEL/Rocky | `dnf install` (공식 yum repo 추가) |
| macOS | `brew install clickhouse` |

```bash
# Ubuntu 22.04 예시
sudo apt-get install -y apt-transport-https ca-certificates dirmngr
GNUPGHOME=$(mktemp -d)
sudo GNUPGHOME="$GNUPGHOME" gpg --no-default-keyring \
    --keyring /usr/share/keyrings/clickhouse-keyring.gpg \
    --keyserver hkp://keyserver.ubuntu.com:80 \
    --recv-keys 8919F6BD2B48D754
echo "deb [signed-by=/usr/share/keyrings/clickhouse-keyring.gpg] https://packages.clickhouse.com/deb stable main" \
    | sudo tee /etc/apt/sources.list.d/clickhouse.list
sudo apt update && sudo apt install -y clickhouse-server clickhouse-client
sudo systemctl enable --now clickhouse-server
```

CVAT은 ClickHouse 23.11을 사용하지만 최신 LTS도 호환된다.

### Vector 네이티브 설치

Vector(timberio/vector)는 Rust 단일 바이너리로 배포된다.

```bash
# 공식 설치 스크립트
curl --proto '=https' --tlsv1.2 -sSf https://sh.vector.dev | bash

# 또는 패키지 매니저
# Ubuntu/Debian: apt repo 추가 후 apt install vector
# RHEL/Rocky: dnf install vector (공식 repo)
```

systemd unit으로 등록하고 `components/analytics/vector/vector.toml` 설정 파일을 그대로 사용한다.

### 분석 비활성화 옵션 (CVAT_ANALYTICS=0)

ClickHouse/Vector/Grafana가 필요 없다면 `CVAT_ANALYTICS` 환경변수를 비활성으로 둘 수 있다.

```python
# cvat/settings/base.py:182
ANALYTICS_ENABLED = to_bool(os.getenv("CVAT_ANALYTICS", False))

if ANALYTICS_ENABLED:
    INSTALLED_APPS += ["cvat.apps.log_viewer"]
```

`CVAT_ANALYTICS=0`(기본값)이면:

- `cvat.apps.log_viewer` 비활성화
- Grafana 대시보드(log_viewer 경유) 사용 불가

단, `cvat.apps.events` 자체는 항상 활성화 상태로 남으므로 ClickHouse 연결 정보(`CLICKHOUSE_HOST` 등)가 필요할 수 있다. 이벤트 로깅까지 비활성화하려면 events 앱도 INSTALLED_APPS에서 제외하거나 ClickHouse 호스트를 mock으로 설정한다(docker-compose 기본 설정에서는 `CVAT_ANALYTICS: 1`로 활성화되어 있음).

### 대안: PostgreSQL에 이벤트 저장

분석 트래픽이 적다면 ClickHouse 대신 PostgreSQL의 별도 스키마(예: `events_log`)에 INSERT할 수 있다. 한계:

- INSERT 성능: ClickHouse 대비 1/10 수준
- 분석 쿼리: GROUP BY가 느려 대시보드용으로 부적합 (수십만 행 이상에서)
- 권장 시나리오: 일일 이벤트 수만 건 미만, 감사 로그 용도

PostgreSQL TimescaleDB 확장을 사용하면 시계열 쿼리 성능을 개선할 수 있다.

### Grafana 네이티브 설치

Grafana는 공식 APT/DNF 저장소를 제공한다.

```bash
# Ubuntu/Debian
sudo apt-get install -y software-properties-common
sudo add-apt-repository "deb https://packages.grafana.com/oss/deb stable main"
sudo apt update && sudo apt install -y grafana
sudo systemctl enable --now grafana-server

# RHEL/Rocky
sudo dnf install -y grafana
```

ClickHouse 데이터소스 플러그인 설치:

```bash
sudo grafana-cli plugins install grafana-clickhouse-datasource
sudo systemctl restart grafana-server
```

대시보드 JSON은 `components/analytics/grafana/dashboards/` 디렉토리에 있으므로 Grafana의 dashboard provisioning 디렉토리(`/etc/grafana/provisioning/dashboards/`)에 복사하여 자동 등록 가능.

자세한 통합 배포는 [deployment-without-docker.md](deployment-without-docker.md) 참고.
