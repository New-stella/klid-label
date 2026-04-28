# Docker 없이 CVAT 배포

## 개요
CVAT은 docker-compose 기반으로 설계되었지만, 모든 컴포넌트는 네이티브 설치가 가능합니다.
다만 두 컴포넌트는 Docker socket을 직접 사용하므로 대안 구성이 필요합니다:
- **Traefik** (리버스 프록시) → Nginx/Caddy로 대체
- **Nuclio Dashboard** (AI 함수 관리) → 직접 모델 서빙으로 대체

다른 모든 컴포넌트(PostgreSQL, Redis, Kvrocks, OPA, ClickHouse, Vector, Grafana, Django/Worker, cvat-ui)는 OS 패키지 매니저나 공식 바이너리로 네이티브 설치할 수 있습니다.

## 의존성 전체 매핑

| 서비스 | docker-compose 이미지 | 네이티브 설치 | Docker 필수 여부 |
|--------|---------------------|---------------|:----------------:|
| cvat_db | postgres:15-alpine | apt/dnf/brew | X |
| cvat_redis_inmem | redis:7.2.11-alpine | apt/dnf/brew | X |
| cvat_redis_ondisk | apache/kvrocks:2.12.1 | 공식 바이너리 / 빌드 | X |
| cvat_server | cvat/server (custom) | Python venv + uvicorn/gunicorn | X |
| cvat_worker_utils | cvat/server | systemd unit | X |
| cvat_worker_import | cvat/server | systemd unit | X |
| cvat_worker_export | cvat/server | systemd unit | X |
| cvat_worker_annotation | cvat/server | systemd unit | X |
| cvat_worker_webhooks | cvat/server | systemd unit | X |
| cvat_worker_quality_reports | cvat/server | systemd unit | X |
| cvat_worker_chunks | cvat/server | systemd unit | X |
| cvat_worker_consensus | cvat/server | systemd unit | X |
| cvat_ui | cvat/ui (custom) | npm build → Nginx 정적 호스팅 | X |
| **traefik** | **traefik:v3.6** | **Nginx/Caddy 대체 필수** | **O (대체 필요)** |
| cvat_opa | openpolicyagent/opa:1.12.2 | Go 단일 바이너리 | X |
| cvat_clickhouse | clickhouse/clickhouse-server:23.11-alpine | apt/dnf 공식 저장소 | X |
| cvat_vector | timberio/vector:0.26.0-alpine | curl 설치 / apt | X |
| cvat_grafana | grafana/grafana-oss:10.1.2 | apt/dnf 공식 저장소 | X |
| **nuclio** (옵션) | **quay.io/nuclio/dashboard:1.15.9** | **모델 직접 서빙 대체 필수** | **O (대체 필요)** |

## 네이티브 설치 순서

다음 순서로 설치하면 의존성 충돌 없이 진행할 수 있습니다.

1. 시스템 의존성 (ffmpeg, libxmlsec, libgeos 등)
2. PostgreSQL 15
3. Redis 7
4. Kvrocks 2.12 (또는 Redis 한 인스턴스로 통합)
5. ClickHouse (옵션, 분석 기능 사용 시)
6. Python 3.10 + 가상환경
7. CVAT backend 코드 클론 + pip install
8. DB 마이그레이션 (`manage.py migrate`)
9. 정적 파일 수집 (`manage.py collectstatic`)
10. uvicorn 또는 Gunicorn으로 backend 실행
11. RQ Worker 8개 systemd 등록
12. cvat-ui npm build 후 Nginx 정적 호스팅
13. OPA 바이너리 설치 + 정책 번들 자동 다운로드 설정
14. Nginx 리버스 프록시 설정
15. (옵션) Vector + Grafana 설치
16. (옵션) AI 모델 직접 서빙 (Nuclio 대체)

## OS별 시스템 패키지 요구사항

### Ubuntu 22.04 / Debian 12

```bash
sudo apt update
sudo apt install -y \
    ca-certificates curl git \
    python3.10 python3.10-venv python3-pip python3-dev \
    build-essential pkg-config \
    libgeos-c1v5 libgeos-dev \
    libgl1 libgomp1 \
    libldap-2.5-0 libldap2-dev \
    libsasl2-2 libsasl2-dev \
    libxml2 libxml2-dev \
    libxmlsec1 libxmlsec1-dev libxmlsec1-openssl \
    libpq-dev \
    libhdf5-dev \
    libblas-dev liblapack-dev \
    nginx \
    p7zip-full poppler-utils unrar \
    supervisor \
    ffmpeg libavcodec-dev libavformat-dev libavutil-dev \
        libswscale-dev libswresample-dev libavfilter-dev \
    libgl1-mesa-glx libglib2.0-0
```

### RHEL 9 / Rocky Linux 9

```bash
# EPEL과 RPM Fusion 활성화 (ffmpeg)
sudo dnf install -y epel-release
sudo dnf install -y \
    https://mirrors.rpmfusion.org/free/el/rpmfusion-free-release-9.noarch.rpm

sudo dnf install -y \
    ca-certificates curl git \
    python3.11 python3-pip python3-devel \
    gcc gcc-c++ make pkgconfig \
    geos geos-devel \
    mesa-libGL libgomp \
    openldap-devel \
    cyrus-sasl-devel \
    libxml2-devel \
    xmlsec1 xmlsec1-devel xmlsec1-openssl \
    libpq-devel \
    hdf5-devel \
    blas-devel lapack-devel \
    nginx \
    p7zip poppler-utils \
    supervisor \
    ffmpeg ffmpeg-devel
```

### macOS (개발용)

```bash
brew install \
    python@3.10 \
    git \
    geos \
    openssl libxml2 libxmlsec1 \
    postgresql@15 \
    redis \
    ffmpeg \
    nginx
```

### 시스템 패키지 비교표

| 패키지 | Ubuntu/Debian | RHEL/Rocky | macOS |
|--------|---------------|-----------|-------|
| ffmpeg | `apt install ffmpeg` | `dnf install ffmpeg` (RPM Fusion) | `brew install ffmpeg` |
| PostgreSQL 15 | `apt install postgresql-15` | `dnf install postgresql-server` | `brew install postgresql@15` |
| Redis 7 | `apt install redis-server` (또는 redis.io repo) | `dnf install redis` | `brew install redis` |
| Nginx | `apt install nginx` | `dnf install nginx` | `brew install nginx` |
| Python 3.10+ | `apt install python3.10` | `dnf install python3.11` | `brew install python@3.10` |
| OpenLDAP dev | `libldap2-dev` | `openldap-devel` | (시스템 내장) |
| xmlsec | `libxmlsec1-dev` | `xmlsec1-devel` | `libxmlsec1` |
| GEOS (Shapely) | `libgeos-dev` | `geos-devel` | `geos` |
| HDF5 | `libhdf5-dev` | `hdf5-devel` | `hdf5` |

## PostgreSQL 15 설치 및 초기화

```bash
# Ubuntu (공식 PGDG 저장소)
sudo install -d /usr/share/postgresql-common/pgdg
sudo curl -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
    --fail https://www.postgresql.org/media/keys/ACCC4CF8.asc
sudo sh -c 'echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
    https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
    > /etc/apt/sources.list.d/pgdg.list'
sudo apt update && sudo apt install -y postgresql-15

# DB/사용자 생성
sudo -u postgres psql <<SQL
CREATE USER cvat WITH PASSWORD 'cvat_password';
CREATE DATABASE cvat OWNER cvat;
GRANT ALL PRIVILEGES ON DATABASE cvat TO cvat;
SQL
```

## Redis 7 + Kvrocks 2.12

```bash
# Redis (인메모리, RQ 큐)
sudo apt install -y redis-server
sudo sed -i 's/^# *appendonly no/appendonly yes/' /etc/redis/redis.conf
sudo systemctl enable --now redis-server

# Kvrocks (디스크 기반, 청크 캐시)
# 공식 릴리즈 페이지: https://github.com/apache/kvrocks/releases
KVROCKS_VERSION=2.12.1
curl -L -o /tmp/kvrocks.tar.gz \
    https://archive.apache.org/dist/kvrocks/${KVROCKS_VERSION}/apache-kvrocks-${KVROCKS_VERSION}-linux-amd64.tar.gz
sudo tar xzf /tmp/kvrocks.tar.gz -C /opt/
sudo ln -s /opt/apache-kvrocks-${KVROCKS_VERSION}/bin/kvrocks /usr/local/bin/kvrocks

# 설정 파일 (/etc/kvrocks/kvrocks.conf)
sudo mkdir -p /etc/kvrocks /var/lib/kvrocks
cat <<'EOF' | sudo tee /etc/kvrocks/kvrocks.conf
bind 127.0.0.1
port 6666
dir /var/lib/kvrocks
log-dir /var/log/kvrocks
compact-cron 0 3 * * *
EOF
```

### Kvrocks systemd unit

```ini
# /etc/systemd/system/kvrocks.service
[Unit]
Description=Apache Kvrocks
After=network.target

[Service]
Type=simple
User=cvat
ExecStart=/usr/local/bin/kvrocks -c /etc/kvrocks/kvrocks.conf
Restart=always
RestartSec=5s

[Install]
WantedBy=multi-user.target
```

### Kvrocks 대체: Redis 단독

메모리가 충분하다면 Redis 한 인스턴스로 통합 가능. 단점은 청크 캐시(수 GB)가 RAM을 점유하므로 대용량 영상에는 부적합.

## Python 가상환경 + CVAT Backend

```bash
# 코드 클론
sudo git clone https://github.com/cvat-ai/cvat.git /opt/cvat
sudo chown -R cvat:cvat /opt/cvat
cd /opt/cvat

# 가상환경
python3.10 -m venv /opt/cvat/venv
source /opt/cvat/venv/bin/activate

# 환경변수 (DATUMARO_HEADLESS는 OpenCV GUI 비활성화)
export DATUMARO_HEADLESS=1

# Python 의존성 설치
pip install --upgrade pip
pip install -r cvat/requirements/production.txt
pip install -r utils/dataset_manifest/requirements.txt

# 환경 설정 파일 (/etc/cvat/cvat.env)
sudo mkdir -p /etc/cvat
cat <<'EOF' | sudo tee /etc/cvat/cvat.env
DJANGO_SETTINGS_MODULE=cvat.settings.production
CVAT_POSTGRES_HOST=127.0.0.1
CVAT_POSTGRES_PORT=5432
CVAT_POSTGRES_DBNAME=cvat
CVAT_POSTGRES_USER=cvat
CVAT_POSTGRES_PASSWORD=cvat_password
CVAT_REDIS_INMEM_HOST=127.0.0.1
CVAT_REDIS_INMEM_PORT=6379
CVAT_REDIS_INMEM_PASSWORD=
CVAT_REDIS_ONDISK_HOST=127.0.0.1
CVAT_REDIS_ONDISK_PORT=6666
CVAT_HOST=cvat.example.com
CVAT_BASE_URL=https://cvat.example.com
ALLOWED_HOSTS=*
CVAT_ANALYTICS=0
EOF

# DB 마이그레이션
set -a && source /etc/cvat/cvat.env && set +a
python manage.py migrate
python manage.py collectstatic --noinput
python manage.py createsuperuser  # 초기 관리자 계정
```

## systemd unit 예시

### cvat-server.service (uvicorn ASGI)

```ini
# /etc/systemd/system/cvat-server.service
[Unit]
Description=CVAT Django ASGI server
After=network.target redis-server.service postgresql.service
Requires=redis-server.service postgresql.service

[Service]
Type=simple
User=cvat
Group=cvat
WorkingDirectory=/opt/cvat
EnvironmentFile=/etc/cvat/cvat.env
ExecStart=/opt/cvat/venv/bin/uvicorn cvat.asgi:application \
    --uds /run/cvat/uvicorn.sock \
    --workers 4 \
    --forwarded-allow-ips='*' \
    --proxy-headers
RuntimeDirectory=cvat
RuntimeDirectoryMode=0775
Restart=always
RestartSec=5s

[Install]
WantedBy=multi-user.target
```

### cvat-worker-{queue}.service (8개)

워커 8종 각각 별도 systemd unit으로 운영. 큐 이름만 다른 동일 패턴.

```ini
# /etc/systemd/system/cvat-worker-import.service (다른 큐도 동일 패턴)
[Unit]
Description=CVAT RQ worker (import)
After=network.target redis-server.service postgresql.service
Requires=redis-server.service postgresql.service

[Service]
Type=simple
User=cvat
Group=cvat
WorkingDirectory=/opt/cvat
EnvironmentFile=/etc/cvat/cvat.env
ExecStart=/opt/cvat/venv/bin/python manage.py rqworker -v 3 import \
    --worker-class cvat.rqworker.DefaultWorker
Restart=always
RestartSec=10s
TimeoutStopSec=60s
MemoryMax=4G

[Install]
WantedBy=multi-user.target
```

다음 큐 이름으로 8개 unit 파일 생성 (큐 이름과 unit 이름만 다름):

| Unit 파일 | 큐 이름 | 권장 인스턴스 수 |
|-----------|--------|----------------|
| cvat-worker-utils.service | `notifications cleaning` | 1 |
| cvat-worker-import.service | `import` | 2 |
| cvat-worker-export.service | `export` | 2 |
| cvat-worker-annotation.service | `annotation` | GPU 수 |
| cvat-worker-webhooks.service | `webhooks` | 1 |
| cvat-worker-quality-reports.service | `quality_reports` | 1 |
| cvat-worker-chunks.service | `chunks` | 2 |
| cvat-worker-consensus.service | `consensus` | 1 |

여러 인스턴스가 필요하면 systemd 인스턴스 unit(`@`) 사용:

```ini
# /etc/systemd/system/cvat-worker-import@.service
[Service]
ExecStart=/opt/cvat/venv/bin/python manage.py rqworker -v 3 import \
    --worker-class cvat.rqworker.DefaultWorker
```

```bash
systemctl enable --now cvat-worker-import@1.service cvat-worker-import@2.service
```

### cvat-opa.service

```ini
# /etc/systemd/system/cvat-opa.service
[Unit]
Description=Open Policy Agent for CVAT
After=network.target cvat-server.service
Requires=cvat-server.service

[Service]
Type=simple
User=opa
ExecStart=/usr/local/bin/opa run --server --addr=:8181 --log-level=error \
    --set=services.cvat.url=http://127.0.0.1:8080 \
    --set=bundles.cvat.service=cvat \
    --set=bundles.cvat.resource=/api/auth/rules \
    --set=bundles.cvat.polling.min_delay_seconds=5 \
    --set=bundles.cvat.polling.max_delay_seconds=15
Restart=always
RestartSec=5s

[Install]
WantedBy=multi-user.target
```

### kvrocks.service

위 Kvrocks 섹션 참조.

### cvat-ui (정적 호스팅, systemd 불필요)

cvat-ui는 npm build 후 정적 파일로 변환되므로 별도 프로세스 불필요. Nginx의 `root` 설정으로 호스팅한다.

```bash
cd /opt/cvat/cvat-ui
yarn install --frozen-lockfile
yarn run build
sudo cp -r dist/* /var/www/cvat-ui/
```

## Nginx 설정 예시

```nginx
# /etc/nginx/sites-available/cvat
upstream cvat_backend {
    server unix:/run/cvat/uvicorn.sock;
}

server {
    listen 80;
    server_name cvat.example.com;

    # HTTPS 리다이렉트 (Certbot 설치 후)
    # return 301 https://$server_name$request_uri;

    client_max_body_size 5G;  # TUS 업로드 대비 (CVAT 기본 5GB)

    # cvat-ui (React SPA)
    location / {
        root /var/www/cvat-ui;
        try_files $uri $uri/ /index.html;

        # CSP/보안 헤더는 Django nginx.conf 참고
        add_header X-Content-Type-Options "nosniff" always;
        add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    }

    # Django API (uvicorn ASGI)
    location ~ ^/(api|static|admin|django-rq)/ {
        proxy_pass http://cvat_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # 대용량 업로드/다운로드 대비
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
        client_body_timeout 600s;
        proxy_buffering off;

        # WebSocket 지원
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # Grafana (분석 활성화 시)
    location /analytics/ {
        proxy_pass http://127.0.0.1:3000/;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/cvat /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

### HTTPS (Let's Encrypt + Certbot)

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d cvat.example.com
# 자동 갱신은 certbot.timer가 처리
```

## OPA 바이너리 설치

```bash
OPA_VERSION=1.12.2
sudo curl -L -o /usr/local/bin/opa \
    https://openpolicyagent.org/downloads/v${OPA_VERSION}/opa_linux_amd64_static
sudo chmod +x /usr/local/bin/opa
sudo useradd -r -s /usr/sbin/nologin opa
```

OPA는 자체 정책 파일을 호스팅할 필요가 없다. CVAT 서버의 `/api/auth/rules` 엔드포인트가 모든 .rego 정책을 tar.gz 번들로 제공하며, OPA가 5~15초 주기로 자동 다운로드한다(`bundles.cvat.polling.*`).

번들 생성 코드: `cvat/apps/iam/utils.py:get_opa_bundle()`

## ClickHouse + Vector + Grafana (옵션)

분석 기능이 필요한 경우만 설치. `CVAT_ANALYTICS=0`(기본값)이면 생략 가능.

### ClickHouse

```bash
# Ubuntu 22.04
sudo apt-get install -y apt-transport-https ca-certificates dirmngr
GNUPGHOME=$(mktemp -d)
sudo GNUPGHOME="$GNUPGHOME" gpg --no-default-keyring \
    --keyring /usr/share/keyrings/clickhouse-keyring.gpg \
    --keyserver hkp://keyserver.ubuntu.com:80 \
    --recv-keys 8919F6BD2B48D754
echo "deb [signed-by=/usr/share/keyrings/clickhouse-keyring.gpg] \
    https://packages.clickhouse.com/deb stable main" \
    | sudo tee /etc/apt/sources.list.d/clickhouse.list
sudo apt update && sudo apt install -y clickhouse-server clickhouse-client
sudo systemctl enable --now clickhouse-server

# 초기화 스크립트 실행
python /opt/cvat/components/analytics/clickhouse/init.py
```

### Vector

```bash
# 공식 설치 스크립트
curl --proto '=https' --tlsv1.2 -sSf https://sh.vector.dev | bash

# 설정 복사
sudo cp /opt/cvat/components/analytics/vector/vector.toml /etc/vector/vector.toml
sudo systemctl enable --now vector
```

### Grafana

```bash
sudo add-apt-repository "deb https://packages.grafana.com/oss/deb stable main"
sudo apt install -y grafana
sudo grafana-cli plugins install grafana-clickhouse-datasource
sudo cp -r /opt/cvat/components/analytics/grafana/dashboards/* /var/lib/grafana/dashboards/
sudo systemctl enable --now grafana-server
```

## Nuclio 우회: AI 모델 직접 서빙

CVAT의 Nuclio Dashboard는 `/var/run/docker.sock`을 마운트하여 함수 컨테이너를 동적 생성한다. **Docker 데몬 없이는 동작하지 않는다.**

### function.yaml 분석

`serverless/{runtime}/{author}/{model}/nuclio/` 구조에 다음 파일이 있다:

| 파일 | 역할 |
|------|------|
| `function.yaml` | Nuclio 함수 메타데이터 (라벨 spec, 베이스 이미지, 빌드 명령) |
| `main.py` | Nuclio HTTP 핸들러 (`init_context()`, `handler()`) |
| `model_handler.py` | 모델 로드 + 추론 로직 (Nuclio 의존성 없음) |

`model_handler.py`는 Nuclio와 무관한 순수 Python 모듈이므로 그대로 재사용 가능하다.

### Flask 변환 예시 (YOLO v7)

원본 `serverless/onnx/WongKinYiu/yolov7/nuclio/main.py`:

```python
def init_context(context):
    with open("/opt/nuclio/function.yaml", "rb") as function_file:
        functionconfig = yaml.safe_load(function_file)
    labels_spec = functionconfig["metadata"]["annotations"]["spec"]
    labels = {item["id"]: item["name"] for item in json.loads(labels_spec)}
    model = ModelHandler(labels)
    context.user_data.model = model

def handler(context, event):
    data = event.body
    buf = io.BytesIO(base64.b64decode(data["image"]))
    threshold = float(data.get("threshold", 0.5))
    image = Image.open(buf).convert("RGB")
    results = context.user_data.model.infer(image, threshold)
    return context.Response(body=json.dumps(results), status_code=200)
```

Flask 변환:

```python
# /opt/cvat-ai/yolov7_server.py
import base64
import io
import json
import yaml
from flask import Flask, request, jsonify
from PIL import Image
from model_handler import ModelHandler  # 그대로 재사용

app = Flask(__name__)

# 기동 시 1회만 모델 로드
with open("/opt/cvat-ai/yolov7/function.yaml", "rb") as f:
    functionconfig = yaml.safe_load(f)
labels_spec = functionconfig["metadata"]["annotations"]["spec"]
labels = {item["id"]: item["name"] for item in json.loads(labels_spec)}
model = ModelHandler(labels)


@app.route("/", methods=["POST"])
def invoke():
    data = request.get_json()
    buf = io.BytesIO(base64.b64decode(data["image"]))
    threshold = float(data.get("threshold", 0.5))
    image = Image.open(buf).convert("RGB")
    results = model.infer(image, threshold)
    return jsonify(results)


if __name__ == "__main__":
    app.run(host="127.0.0.1", port=8081, threaded=False)
```

### CVAT 서버 측 설정

`CVAT_NUCLIO_INVOKE_METHOD=direct`로 설정하면 CVAT은 Nuclio Dashboard 대신 함수 URL로 직접 HTTP POST를 보낸다. 함수 메타데이터(라벨 spec, 핸들러 정보)는 별도로 등록해야 한다.

```bash
# /etc/cvat/cvat.env에 추가
CVAT_NUCLIO_INVOKE_METHOD=direct
CVAT_NUCLIO_HOST=127.0.0.1
CVAT_NUCLIO_PORT=8081  # 모델별로 다른 포트
```

단, CVAT은 함수 목록을 Nuclio Dashboard API에서 조회하므로(`GET /api/functions`), Nuclio 없이 사용하려면 `cvat/apps/lambda_manager/views.py:LambdaGateway`를 수정하여 함수 목록을 정적 JSON 파일에서 로드하는 방식으로 변경해야 한다.

### 장단점

| 항목 | Nuclio + Docker | Flask 직접 서빙 | Backend 임베딩 |
|------|----------------|----------------|----------------|
| 격리 | 함수별 컨테이너 | 함수별 프로세스 | 격리 없음 |
| 의존성 충돌 | 없음 | 가상환경 분리 | 충돌 가능 |
| GPU 공유 | Nuclio 관리 | 수동 관리 | Backend와 공유 |
| 배포 복잡도 | 높음 | 중간 | 낮음 |
| 디버깅 | 컨테이너 로그 | 표준 stdout | 표준 stdout |
| 권장 시나리오 | 다중 모델, 다중 호스트 | 단일 GPU 서버 + 모델 격리 | 단일 GPU + 1~2개 모델 |

## OPA 우회: Python 권한 검사

OPA는 Go 단일 바이너리로 Docker 의존성이 없으므로 **우회할 필요 없음.** systemd로 직접 실행 가능하다.

### IAM_TYPE 환경변수

`IAM_TYPE` 설정값(`BASIC` 또는 `LDAP`)은 인증 방식만 분기하며, 권한 검사 자체는 항상 OPA에 위임된다. 다른 IAM_TYPE 값은 코드상 지원되지 않는다.

```python
# cvat/apps/iam/signals.py
if settings.IAM_TYPE == "BASIC":
    ...
elif settings.IAM_TYPE == "LDAP":
    ...
```

### OPA 완전 제거(고급)

OPA를 완전히 제거하려면 `cvat/apps/iam/permissions.py`의 `OpenPolicyAgentPermission.check_access()`/`filter()` 메서드를 오버라이드하여 인라인 Python 검사로 대체해야 한다. .rego 파일 11개 도메인을 모두 Python으로 재작성해야 하므로 작업량이 크다.

```python
# 우회 예시 (cvat/apps/iam/permissions.py 수정)
class OpenPolicyAgentPermission:
    def check_access(self) -> PermissionResult:
        # OPA HTTP 호출 대신 인라인 평가
        from cvat.apps.iam.python_policy import evaluate
        allow, reasons = evaluate(self.scope, self.payload)
        return PermissionResult(allow=allow, reasons=reasons)
```

장단점:

| 항목 | OPA 유지 (권장) | Python 직접 구현 |
|------|----------------|-----------------|
| 정책 변경 배포 | 번들 재배포만 (재시작 X) | 코드 변경 + 재배포 |
| 런타임 오버헤드 | HTTP 호출(localhost) 1회/요청 | 함수 호출 |
| 정책 분석 | OPA Playground 활용 가능 | 별도 도구 필요 |
| 의존성 | OPA 바이너리 | 없음 |
| 작업량 | 거의 없음 | 매우 큼(.rego 11개 도메인 변환) |

**결론: OPA를 systemd로 유지하는 것을 강력히 권장한다.**

## 한계 및 트레이드오프

| 항목 | docker-compose | 네이티브 + systemd |
|------|----------------|-------------------|
| 격리 | 컨테이너 격리 | 프로세스 격리 (cgroups로 보강 가능) |
| 자동 스케일링 | Compose 한계, K8s 필요 | systemd 인스턴스로 수동 확장 |
| 의존성 충돌 | 이미지 별 격리 | venv + 시스템 패키지 충돌 가능 |
| OOM 영향 | 컨테이너 단위 격리 | 한 워커 OOM 시 호스트 영향 가능 |
| 배포 자동화 | `compose up` 한 명령 | 여러 unit 파일 + 패키지 관리 |
| 롤백 | 이미지 태그 변경 | 코드 + DB 마이그레이션 수동 |
| AI 모델 격리 | 함수별 컨테이너 | 동일 호스트에서 모델 충돌 가능 |
| 학습 곡선 | Docker 학습 | systemd/Nginx/Linux 운영 지식 |

## 권장 운영 시나리오

| 시나리오 | 권장 |
|---------|------|
| 단일 호스트, 소규모 (< 10명 동시 사용) | 네이티브 + systemd |
| 단일 호스트, 중규모 (수십 명) | docker-compose 권장 |
| 다중 호스트 / 클러스터 | Helm Chart (CVAT 공식 제공: `helm-chart/`) |
| 클라우드 매니지드 | EKS/GKE + Helm + S3 + Cloud SQL |
| AI 모델 격리 필수 | docker-compose + Nuclio (Docker 데몬 필요) |
| GPU 단일 서버 + 1~2개 모델 | 네이티브 + Backend 내 모델 임베딩 |

## 참고 코드 위치

| 항목 | 경로 |
|------|------|
| Docker Compose 정의 | `docker-compose.yml` |
| Nuclio 함수 모음 | `serverless/{onnx,pytorch,openvino,tensorflow}/` |
| Nuclio 추가 compose | `components/serverless/docker-compose.serverless.yml` |
| 분석 스택 설정 | `components/analytics/{clickhouse,vector,grafana}/` |
| supervisord 설정 | `supervisord/server.conf`, `supervisord/worker.conf` |
| Nginx 컨테이너 내부 설정 | `cvat/nginx.conf` |
| Helm Chart | `helm-chart/` |
| OPA 번들 생성 | `cvat/apps/iam/utils.py:get_opa_bundle()` |
| OPA 정책 (도메인별) | `cvat/apps/{engine,iam,organizations,...}/rules/*.rego` |
| 권한 검사 진입점 | `cvat/apps/iam/permissions.py:OpenPolicyAgentPermission.check_access()` |
| Lambda 게이트웨이 | `cvat/apps/lambda_manager/views.py:LambdaGateway` |
| 환경 설정 진입점 | `cvat/settings/base.py`, `cvat/settings/production.py` |
| Python 의존성 | `cvat/requirements/production.txt`, `utils/dataset_manifest/requirements.txt` |
