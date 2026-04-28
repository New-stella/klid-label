# API 구조 참조

## 개요
CVAT의 REST API는 Django REST Framework + drf-spectacular(OpenAPI 3.0)로 구성된다. 모든 API는 `/api/` 접두사를 가지며, DRF DefaultRouter 기반의 RESTful 설계를 따른다.

## 핵심 코드 위치
| 역할 | 파일 경로 | 비고 |
|------|---------|------|
| 라우터 등록 | `cvat/apps/engine/urls.py` | DefaultRouter |
| 전체 URL 구성 | `cvat/urls.py` | 앱별 include |
| 핵심 ViewSet | `cvat/apps/engine/views.py` | Task/Job/Label 등 |
| OpenAPI 스펙 | `cvat/schema.yml` | 생성된 OpenAPI 3.0 스펙 |
| Swagger UI | `/api/swagger/` | 브라우저 접근 가능 |

## 주요 엔드포인트 목록 {#endpoints}

### 프로젝트/태스크/잡

```
# Projects
GET    /api/projects                      # 프로젝트 목록
POST   /api/projects                      # 프로젝트 생성
GET    /api/projects/{id}                 # 상세 조회
PATCH  /api/projects/{id}                 # 부분 수정
DELETE /api/projects/{id}                 # 삭제
GET    /api/projects/{id}/annotations     # 프로젝트 전체 어노테이션
GET    /api/projects/{id}/dataset         # 데이터셋 내보내기

# Tasks
GET    /api/tasks                         # 태스크 목록
POST   /api/tasks                         # 태스크 생성
GET    /api/tasks/{id}                    # 상세 조회
PATCH  /api/tasks/{id}                    # 부분 수정
DELETE /api/tasks/{id}                    # 삭제
POST   /api/tasks/{id}/data               # 미디어 데이터 업로드
GET    /api/tasks/{id}/data               # 미디어 데이터 접근 (chunk/frame/preview)
GET    /api/tasks/{id}/jobs               # 태스크의 잡 목록
GET    /api/tasks/{id}/annotations        # 어노테이션 조회
PUT    /api/tasks/{id}/annotations        # 어노테이션 전체 교체
PATCH  /api/tasks/{id}/annotations        # 어노테이션 부분 수정
DELETE /api/tasks/{id}/annotations        # 어노테이션 전체 삭제
GET    /api/tasks/{id}/dataset            # 데이터셋 내보내기 (비동기, 202 반환)

# Jobs
GET    /api/jobs                          # 잡 목록
POST   /api/jobs                          # GT Job 생성 (ground_truth 타입)
GET    /api/jobs/{id}                     # 상세 조회
PATCH  /api/jobs/{id}                     # 부분 수정 (stage/state/assignee 변경)
DELETE /api/jobs/{id}                     # 삭제 (GT Job만)
GET    /api/jobs/{id}/data                # 미디어 청크/프레임 접근
GET    /api/jobs/{id}/annotations         # 어노테이션 조회
PUT    /api/jobs/{id}/annotations         # 어노테이션 전체 교체
PATCH  /api/jobs/{id}/annotations?action= # 어노테이션 부분 수정 (create/update/delete)
DELETE /api/jobs/{id}/annotations         # 어노테이션 삭제
GET    /api/jobs/{id}/dataset             # 데이터셋 내보내기 (비동기, 202 반환)
```

### 라벨 / 속성

```
GET    /api/labels                        # 라벨 목록 (?task_id=N&project_id=N)
POST   /api/labels                        # 라벨 생성
GET    /api/labels/{id}                   # 라벨 상세
PATCH  /api/labels/{id}                   # 라벨 수정
DELETE /api/labels/{id}                   # 라벨 삭제
```

### 인증 / 사용자

```
POST   /api/login                         # 로그인 (세션 기반)
POST   /api/logout                        # 로그아웃
POST   /api/register                      # 회원가입 (BASIC 모드)
GET    /api/users                         # 사용자 목록
GET    /api/users/{id}                    # 사용자 상세
PATCH  /api/users/{id}                    # 사용자 수정
GET    /api/users/self                    # 현재 로그인 사용자 정보
```

### 이슈 / 코멘트

```
GET    /api/issues                        # 이슈 목록
POST   /api/issues                        # 이슈 생성
PATCH  /api/issues/{id}                   # 이슈 수정 (resolved 토글)
DELETE /api/issues/{id}                   # 이슈 삭제
GET    /api/comments                      # 코멘트 목록
POST   /api/comments                      # 코멘트 작성
```

### AI 람다 함수

```
GET    /api/lambda/functions              # 등록된 AI 함수 목록
GET    /api/lambda/functions/{id}         # 함수 상세 (spec, labels)
POST   /api/lambda/requests               # AI 함수 호출 (오토라벨링)
GET    /api/lambda/requests               # 실행 중인 요청 목록
GET    /api/lambda/requests/{id}          # 요청 상태 조회
DELETE /api/lambda/requests/{id}          # 요청 취소
```

### 품질 관리

```
GET    /api/quality/reports               # 품질 리포트 목록
GET    /api/quality/reports/{id}          # 리포트 상세
POST   /api/quality/reports               # 리포트 생성 요청
GET    /api/quality/conflicts             # 충돌 목록
GET    /api/quality/settings              # 품질 설정 조회
PATCH  /api/quality/settings/{id}         # 품질 설정 수정
```

### 조직

```
GET    /api/organizations                 # 조직 목록
POST   /api/organizations                 # 조직 생성
GET    /api/organizations/{slug}          # 조직 상세
PATCH  /api/organizations/{slug}          # 조직 수정
GET    /api/organizations/{slug}/members  # 멤버 목록
POST   /api/invitations                   # 초대 발송
```

### 비동기 요청 상태

```
GET    /api/requests                      # 비동기 요청 목록 (필터: status/task_id/job_id/action 등)
GET    /api/requests/{rq_id}              # 특정 요청 상태 조회
# RequestStatus: queued | started | finished | failed
# 소속 앱: cvat/apps/redis_handler/ (RequestViewSet)
```

### 이벤트 / 웹훅

```
POST   /api/events                        # 클라이언트 이벤트 일괄 기록
GET    /api/events                        # 이벤트 내보내기 (ClickHouse 조회, CSV)

GET    /api/webhooks                      # 웹훅 목록
POST   /api/webhooks                      # 웹훅 생성
GET    /api/webhooks/{id}                 # 웹훅 상세
PATCH  /api/webhooks/{id}                 # 웹훅 수정
DELETE /api/webhooks/{id}                 # 웹훅 삭제
POST   /api/webhooks/{id}/ping            # 테스트 이벤트 전송
GET    /api/webhooks/{id}/deliveries      # 전송 이력 목록
GET    /api/webhooks/{id}/deliveries/{did} # 전송 이력 상세
POST   /api/webhooks/{id}/deliveries/{did}/redelivery # 재전송
```

### 서버 정보

```
GET    /api/server/about                  # 서버 버전 정보
GET    /api/server/formats                # 지원 포맷 목록
GET    /api/server/plugins                # 활성화된 플러그인 목록
GET    /api/server/health/                # 헬스체크
```

## 공통 응답 형식 {#response-format}

CVAT은 DRF 기본 응답을 사용하므로, 별도의 공통 래퍼 없이 데이터를 직접 반환한다.

```json
// 단일 리소스
{
    "id": 1,
    "name": "my_task",
    "status": "annotation",
    "created_date": "2024-01-01T00:00:00.000Z",
    "updated_date": "2024-01-02T00:00:00.000Z"
}

// 목록 (페이지네이션 포함)
{
    "count": 100,
    "next": "https://example.com/api/tasks?page=2",
    "previous": null,
    "results": [ {...}, {...} ]
}

// 오류 응답
{
    "detail": "Authentication credentials were not provided."
    // 또는
    "field_name": ["This field is required."]
}
```

## 페이지네이션, 필터링 패턴

### 페이지네이션

```
GET /api/tasks?page=1&page_size=20
GET /api/tasks?page=2&page_size=50
```

기본 page_size는 settings에서 설정 (`REST_FRAMEWORK.PAGE_SIZE`).

### 필터링 (SearchFilter + OrderingFilter)

```
# 검색 (search_fields에 정의된 필드에서 검색)
GET /api/tasks?search=my_task

# 정렬
GET /api/tasks?ordering=-created_date   # 최신순
GET /api/tasks?ordering=name             # 이름 오름차순

# Jobs 필터 예시
GET /api/jobs?task_id=5&assignee=john&stage=annotation&state=in+progress
```

### Job 필터 파라미터 (`cvat/apps/engine/views.py:L1673`)

```
search_fields: task_name, project_name, assignee, state, stage
ordering_fields: id, task_id, project_id, assignee, state, stage, updated_date
filter_fields: task_id, project_id, assignee_id, state, stage, type
```

## 인증 방식

```
# 세션 기반 (기본)
POST /api/login {"username": "user", "password": "pass"}
→ Set-Cookie: sessionid=...

# 모든 후속 요청
Cookie: sessionid=...
# 또는
Authorization: Token {token}
```

## 미디어 데이터 접근 API

```
# 청크 (프레임 그룹, ZIP 또는 MP4)
GET /api/jobs/{id}/data?type=chunk&number=0&quality=compressed

# 단일 프레임
GET /api/jobs/{id}/data?type=frame&number=5&quality=original

# 썸네일
GET /api/tasks/{id}/data?type=preview
GET /api/jobs/{id}/data?type=preview
```

## OpenAPI 스펙 위치

```
/api/schema/              # OpenAPI 3.0 YAML 다운로드
/api/swagger/             # Swagger UI
/api/docs/                # ReDoc UI

# 파일 직접 확인
cvat/schema.yml           # 생성된 OpenAPI 스펙 파일 (~15,000줄)
```

## 핵심 의사결정

- **DRF DefaultRouter**: 모든 ViewSet을 단일 라우터에 등록하여 OpenAPI 자동 생성을 일관되게 유지한다.
- **공통 응답 래퍼 없음**: CVAT은 DRF 기본 응답을 그대로 사용하며 별도의 공통 래퍼(success/code/message/data) 없음.
- **PATCH의 action 파라미터**: 어노테이션은 PATCH 시 전체 교체가 아닌 `?action=create/update/delete`로 부분 수정한다(DRF 기본 동작과 다름).
- **API Base URL**: 모든 엔드포인트는 `/api/` 접두사. 버전 prefix 없음.
- **세션 + 토큰 + Signature**: TokenAuthentication, SignatureAuthentication, BasicAuthentication, SessionAuthentication 4가지 인증을 모두 지원.
- **데이터 접근 단일 엔드포인트**: `/data?type=chunk|frame|preview&number=N&quality=compressed|original`로 미디어 접근을 통일.
- **비동기 응답**: 대용량 작업은 202 Accepted + `X-Request-Id`로 비동기 처리 후 `/api/requests/{rq_id}` 폴링.
- **Job 단위 기본 + Task/Project 집계**: 어노테이션 API는 Job 단위가 기본이지만 Task/Project 단위 API도 있음(Segment 경계 처리 자동).

## Docker 미사용 대응

### Traefik은 Docker socket 의존 → 대체 필수

```yaml
# docker-compose.yml:302
traefik:
  image: traefik:v3.6
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock:ro  # ← Docker 의존
  environment:
    TRAEFIK_PROVIDERS_DOCKER_EXPOSEDBYDEFAULT: "false"
    TRAEFIK_PROVIDERS_DOCKER_NETWORK: cvat
```

Traefik은 Docker socket을 통해 컨테이너 라벨(`traefik.http.routers.cvat.rule: ...`)을 자동 검색하여 라우팅 규칙을 동적 생성한다. **Docker 데몬 없이는 사용 불가.**

### 대체: Nginx 또는 Caddy 정적 설정

#### Nginx 예시 (정적 파일 + Django proxy_pass + WebSocket)

```nginx
# /etc/nginx/sites-available/cvat
upstream cvat_backend {
    server unix:/tmp/uvicorn.sock;
    # 또는 server 127.0.0.1:8080;
}

server {
    listen 80;
    server_name cvat.example.com;

    client_max_body_size 5G;  # TUS 업로드 대비

    # cvat-ui (정적 파일)
    location / {
        root /opt/cvat-ui/dist;
        try_files $uri $uri/ /index.html;
    }

    # Django API
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

        # WebSocket (필요 시)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # Grafana (analytics 활성화 시)
    location /analytics/ {
        proxy_pass http://127.0.0.1:3000/;
        proxy_set_header Host $host;
    }
}
```

#### Caddy 예시 (자동 HTTPS)

```caddy
# /etc/caddy/Caddyfile
cvat.example.com {
    handle_path /api/* {
        reverse_proxy unix//tmp/uvicorn.sock
    }
    handle_path /static/* {
        reverse_proxy unix//tmp/uvicorn.sock
    }
    handle_path /admin/* {
        reverse_proxy unix//tmp/uvicorn.sock
    }
    handle {
        root * /opt/cvat-ui/dist
        try_files {path} /index.html
        file_server
    }
}
```

Caddy는 Let's Encrypt를 자동 처리하므로 단순 운영에 유리.

### HTTPS 설정 (Let's Encrypt + Certbot)

```bash
# Nginx + Certbot
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d cvat.example.com
# 자동 갱신은 systemd timer로 활성화됨 (certbot.timer)
```

### Django 자체 실행 옵션

CVAT의 supervisord 설정(`supervisord/server.conf`)은 uvicorn(ASGI) + nginx(컨테이너 내부) 조합을 사용한다. Docker 없이도 동일하게:

```bash
# uvicorn ASGI 서버 (Unix socket)
uvicorn cvat.asgi:application \
    --uds /tmp/uvicorn.sock \
    --workers 4 \
    --forwarded-allow-ips='*'
```

또는 uWSGI/Gunicorn:

```bash
gunicorn cvat.wsgi:application \
    --bind unix:/tmp/uvicorn.sock \
    --workers 4 \
    --worker-class gthread \
    --threads 2 \
    --timeout 600
```

자세한 통합 배포는 [deployment-without-docker.md](deployment-without-docker.md) 참고.
