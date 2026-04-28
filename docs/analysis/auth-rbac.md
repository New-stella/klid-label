# 인증/권한 관리

## 개요
CVAT은 두 가지 인증 방식(BASIC 세션 기반, LDAP)과 OPA(Open Policy Agent)를 통한 역할 기반 접근 제어를 사용한다. 권한 검사는 OPA 서버에 Rego 정책으로 위임된다.

## 핵심 코드 위치
| 역할 | 파일 경로 | 비고 |
|------|---------|------|
| IAM URL/뷰 | `cvat/apps/iam/urls.py`, `views.py` | 로그인/로그아웃/회원가입 |
| OPA 권한 검사 | `cvat/apps/iam/permissions.py` | OpenPolicyAgentPermission |
| Rego 정책 | `cvat/apps/iam/rules/` | 도메인별 .rego 파일 |
| Organization 모델 | `cvat/apps/organizations/models.py` | Membership, 역할 4종 |
| 엔진 권한 | `cvat/apps/engine/permissions.py` | Task/Job 권한 클래스 |
| 설정 | `cvat/cvat/settings/base.py:L229` | IAM_TYPE, IAM_OPA_HOST |

## 역할(Role) 목록 {#roles}

### 조직(Organization) 내 역할

```python
# cvat/apps/organizations/models.py
class Membership:
    WORKER     = "worker"      # 일반 작업자 — 자신에게 할당된 Job만 작업
    SUPERVISOR = "supervisor"  # 감독자 — 작업 관리, 라벨러 할당
    MAINTAINER = "maintainer"  # 관리자 — 멤버 관리 포함
    OWNER      = "owner"       # 오너 — 전체 권한
```

### 글로벌 역할 (조직 외)
- **Staff**: Django is_staff=True (관리자 패널 접근)
- **Superuser**: Django is_superuser=True (전체 권한)
- **Anonymous**: 로그인하지 않은 사용자 (읽기 전용 공개 데이터)

## OPA 기반 권한 시스템 {#opa}

```
[API 요청]
    ↓
[Django DRF Permission]
    ↓ OpenPolicyAgentPermission.has_permission()
[OPA 서버 HTTP 요청]
    POST http://opa:8181/v1/data/cvat/{resource}/allow
    Body: {
        "input": {
            "auth": {
                "user": {"id": 1, "privilege": "worker"},
                "organization": {"id": 5, "role": "supervisor"}
            },
            "scope": "view|create|update|delete",
            "resource": {"id": 123, "owner": {"id": 1}, ...}
        }
    }
[OPA 응답]
    {"result": true}  또는  {"result": false}
```

### OPA 번들 로딩
```yaml
# docker-compose.yml
cvat_opa:
  command:
    - --set=services.cvat.url=http://cvat-server:8080
    - --set=bundles.cvat.service=cvat
    - --set=bundles.cvat.resource=/api/auth/rules  # CVAT 서버에서 번들 다운로드
```

OPA는 CVAT 서버의 `/api/auth/rules` 엔드포인트에서 Rego 정책을 5~15초 주기로 업데이트한다.

### 권한 컨텍스트 구조

```python
# cvat/apps/iam/permissions.py
def build_iam_context(request):
    return {
        "user": {
            "id": request.user.id,
            "privilege": "worker|admin|...",
        },
        "organization": {
            "id": org.id,
            "role": membership.role,  # owner|maintainer|supervisor|worker
        } if org else None,
    }
```

## 주요 권한 클래스

```python
# cvat/apps/engine/permissions.py

class TaskPermission(OpenPolicyAgentPermission):
    # GET /api/tasks → scope = "list"
    # POST /api/tasks → scope = "create"
    # GET /api/tasks/{id} → scope = "view"
    # PATCH /api/tasks/{id} → scope = "update"
    # DELETE /api/tasks/{id} → scope = "delete"

class JobPermission(OpenPolicyAgentPermission):
    # PATCH /api/jobs/{id}/annotations → scope = "update"
    # 이 때 Job의 assignee가 현재 사용자인지도 확인

class AnnotationPermission(OpenPolicyAgentPermission):
    # GET, PUT, PATCH /api/jobs/{id}/annotations → scope 에 따라
```

## IAM 설정

```python
# cvat/settings/base.py
IAM_TYPE = "BASIC"      # "BASIC" (기본) 또는 "LDAP"
IAM_OPA_HOST = "http://opa:8181"
IAM_OPA_DATA_URL = f"{IAM_OPA_HOST}/v1/data"

# DRF 인증 클래스
REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": [
        "cvat.apps.iam.authentication.TokenAuthentication",
        "cvat.apps.iam.authentication.SignatureAuthentication",
        "rest_framework.authentication.BasicAuthentication",
        "rest_framework.authentication.SessionAuthentication",
    ],
    "DEFAULT_PERMISSION_CLASSES": [
        "rest_framework.permissions.IsAuthenticated",
        "cvat.apps.access_tokens.permissions.PolicyEnforcer",
    ],
}
```

## 액세스 토큰 (Personal Access Token)

```python
# cvat/apps/access_tokens/ 앱
# API Key 형태의 영구 토큰 발급/관리
# POST /api/auth/token → 토큰 발급
# 헤더: Authorization: Token {token_value}
```

## 핵심 의사결정

- **OPA 분리 운영**: 권한 정책을 Rego DSL로 분리하여 CVAT 코드와 독립적으로 정책을 변경할 수 있다.
- **번들 자동 갱신**: OPA는 CVAT 서버의 `/api/auth/rules`에서 5~15초 주기로 정책 번들을 다운로드한다(폴링).
- **앱별 rules 디렉토리**: 도메인별로 .rego 파일이 분리되어 있다 — `cvat/apps/{engine,iam,organizations,quality_control,...}/rules/*.rego`.
- **번들 파이프라인**: `add_opa_rules_path()` 헬퍼로 각 Django 앱이 자신의 rules 경로를 등록하고, `get_opa_bundle()`이 모든 .rego를 tar.gz로 묶어 ETag와 함께 제공한다.
- **scope 기반 권한**: list/view/create/update/delete 5가지 scope로 모든 ViewSet 권한을 통일한다.
- **filter rule 분리**: 권한 검사(`/allow`)와 쿼리 필터(`/filter`)를 분리하여 list 조회 시 권한이 있는 객체만 반환한다.
- **IAM_TYPE 고정**: `BASIC` 또는 `LDAP` 두 가지만 지원. SSO/OAuth는 `cvat/apps/iam/adapters.py`의 allauth 어댑터로 확장 가능.
- **OBJECTS_NOT_RELATED_WITH_ORG**: user, lambda_function, server, request, access_token 등은 조직 컨텍스트 없이 동작.

## Docker 미사용 대응

### 결론: OPA는 Docker 없이도 운영 가능

OPA는 Go 단일 바이너리이므로 Docker 의존성이 없다. systemd로 직접 실행 가능.

```bash
# 1. OPA 바이너리 다운로드 (https://www.openpolicyagent.org/docs/latest/#1-download-opa)
curl -L -o /usr/local/bin/opa https://openpolicyagent.org/downloads/v1.12.2/opa_linux_amd64_static
chmod +x /usr/local/bin/opa

# 2. systemd unit 작성
cat > /etc/systemd/system/cvat-opa.service <<'EOF'
[Unit]
Description=Open Policy Agent for CVAT
After=network.target cvat-server.service

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

[Install]
WantedBy=multi-user.target
EOF

systemctl enable --now cvat-opa.service
```

### 정책 번들 호스팅

CVAT 서버가 `/api/auth/rules` 엔드포인트로 정책 번들을 제공한다. OPA는 이 URL에서 5~15초 주기로 번들을 다운로드(`bundles.cvat.polling.*` 설정). 번들 생성 코드는 `cvat/apps/iam/utils.py:get_opa_bundle()` 참조.

```python
# cvat/apps/iam/utils.py
@functools.lru_cache(maxsize=None)
def get_opa_bundle() -> tuple[bytes, str]:
    bundle_file = io.BytesIO()
    with tarfile.open(fileobj=bundle_file, mode="w:gz") as tar:
        for p in _OPA_RULES_PATHS:
            for f in p.glob("*.rego"):
                if not f.name.endswith(".gen.rego"):
                    tar.add(name=f, arcname=f.relative_to(p.parent))
    bundle = bundle_file.getvalue()
    etag = hashlib.blake2b(bundle).hexdigest()
    return bundle, etag
```

### IAM_TYPE 환경변수 — OPA 의존성은 우회 불가

`IAM_TYPE` 설정값은 인증 방식(BASIC vs LDAP)만 분기한다. 권한 검사 자체는 OPA에 항상 위임되며, 다른 IAM_TYPE 값은 코드상 지원되지 않는다.

```python
# cvat/apps/iam/signals.py — 분기는 인증 어댑터 등록에만 사용
if settings.IAM_TYPE == "BASIC":
    ...
elif settings.IAM_TYPE == "LDAP":
    ...
```

권한 검사 코드 `cvat/apps/iam/permissions.py:OpenPolicyAgentPermission.check_access()`는 항상 OPA 서버에 HTTP POST를 보낸다.

### OPA 우회: Python 코드로 직접 구현

`OpenPolicyAgentPermission` 클래스의 `check_access()`/`filter()` 메서드를 오버라이드하여 OPA 호출 대신 Python 코드로 정책을 구현할 수 있다. 단, .rego 파일을 모두 Python으로 재작성해야 한다.

```python
# 우회 예시 (cvat/apps/iam/permissions.py 수정 필요)
class OpenPolicyAgentPermission:
    def check_access(self) -> PermissionResult:
        # 기존: OPA HTTP 호출
        # 변경: 인라인 권한 매트릭스
        from cvat.apps.iam.python_policy import evaluate
        allow, reasons = evaluate(self.scope, self.payload)
        return PermissionResult(allow=allow, reasons=reasons)
```

장단점:

| 항목 | OPA 유지 | Python 직접 구현 |
|------|----------|-----------------|
| 정책 변경 배포 | 번들 재배포만 (재시작 불필요) | 코드 변경 + 재배포 |
| 런타임 오버헤드 | HTTP 호출(localhost) 1회/요청 | 함수 호출 |
| 정책 분석/시뮬레이션 | OPA Playground 활용 가능 | 별도 도구 필요 |
| 의존성 | OPA 바이너리 필요 | 없음 |

CVAT의 권한 정책은 .rego 파일 11개 도메인에 분산되어 있어 전체 Python 변환은 상당한 작업량이다. **OPA 유지를 강력히 권장**한다.

자세한 통합 배포는 [deployment-without-docker.md](deployment-without-docker.md) 참고.
