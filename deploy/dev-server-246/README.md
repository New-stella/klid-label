# 개발서버(246) 배포 가이드 — 관제 채널 저작도구

> 대상: **192.168.102.246** 도커 기반 개발서버에 저작도구(백엔드 JBoss + 프론트 정적)를
> **온프렘과 동일 형상**(JBoss EAP + Apache 정적)으로 배포한다.
> 관제 시스템이 같은 서버(도커)에 떠 있고 **Apache·DB·NAS 를 공유**하므로, 관제를 건드리지 않고
> 저작도구만 격리 배포한다.
>
> ⚠ 이 문서는 **개발서버(도커+공유 Apache)** 전용이다. 순수 온프렘(WAR 반입) 절차는
> `deploy/onprem/docs/` 를 본다.

## 0. 접속 · 사전 조건

- 246 접속은 **NovaTerm CLI** 로 한다: `nt ssh cudo_246 "..."`, `nt sftp cudo_246 put <로컬> <원격>`.
- 이미 떠 있어야 하는 컨테이너: `apache`(klid-apache-test) · `klid-authoring-jboss`(전용 JBoss) ·
  `postgis-klid`(DB, database `klid`, user `cudo`).
- 저작도구 소스 레포는 246 의 **`/data/klid`**(git, `main`)에 있다.
- ⚠ **호스트 node 는 v12** 라 Vite 5 빌드 불가 → **`node:20-alpine` 도커 이미지로 빌드**한다(존재 확인).

## 1. 토폴로지 (경로·포트 진실원)

```
브라우저 → http://192.168.102.246:8088/label-studio/
                         │  (apache 컨테이너, :8088)
                         ├─ /label-studio/         → AliasMatch → 정적 FE (label-studio-web)
                         └─ /label-studio/api/**   → ProxyPass  → klid-authoring-jboss:18080/label-studio/**
                                                                    (JBoss EAP 8, context=/label-studio)
                                                                         │
                                                                         └─ DB: postgis-klid:5432/klid (user cudo, schema klid_at)
```

| 자원 | 위치 |
|---|---|
| apache 설정 | 호스트 `/data/klid/closed-net/apache-conf/klid.conf` → 컨테이너 `/etc/httpd/conf.d/klid.conf` **(RO 단일파일 마운트)** |
| FE 정적 | 호스트 `/data/klid/closed-net/label-studio-web/` → 컨테이너 `/local-storage/web/label-studio` **(RO 디렉터리 마운트)** |
| JBoss 홈 | 호스트 `/data/klid/closed-net/klid-authoring/jboss-eap-8` → 컨테이너 `/opt/jboss-eap-8` |
| WAR 배포 위치 | `.../jboss-eap-8/standalone/deployments/api.war` (핫디플로이, `api.war.deployed` 마커) |
| JBoss env | `/data/klid/closed-net/klid-authoring/app.env` |
| JBoss HTTP | 컨테이너 **18080** (호스트 매핑 38090) · 네트워크 `klid-net` + `klidnet`(apache 가 이름으로 도달) |
| 소스 레포 | `/data/klid` (git main) |

## 2. 경로 매핑 규칙 (빌드/설정에 직접 반영됨)

- Apache 가 **`/label-studio/api/` 를 벗겨** JBoss `/label-studio/` 로 전달한다.
  즉 `/label-studio/api/v1/me` → JBoss `/label-studio/v1/me`.
- JBoss WAR context 는 **`/label-studio`** 다 → 빌드 시 `-PklidWebContext=/label-studio`.
- FE 번들에 API base 를 **`/label-studio/api/v1`** 로 굽는다(`VITE_API_BASE_URL`).
- FE 자산 base 는 **`/label-studio/`** (`VITE_BASE_PATH`).

## 3. 백엔드(JBoss) 배포

```bash
# 3-1. 로컬(또는 246 /data/klid)에서 WAR 빌드 — context=/label-studio
export JAVA_HOME=/opt/homebrew/opt/openjdk@17   # (로컬 macOS 기준. 새 셸에서 필수)
cd <repo>/backend && ./gradlew bootWar -PklidWebContext=/label-studio
#   산출: backend/build/libs/*.war

# 3-2. WAR 를 246 JBoss deployments 로 전송(핫디플로이)
nt sftp cudo_246 put backend/build/libs/api.war \
  /data/klid/closed-net/klid-authoring/jboss-eap-8/standalone/deployments/api.war

# 3-3. JBoss 재기동(또는 핫디플로이 대기) 후 상태 확인
nt ssh cudo_246 "docker restart klid-authoring-jboss; sleep 20; \
  docker exec klid-authoring-jboss curl -s -o /dev/null -w '%{http_code}\n' \
  http://localhost:18080/label-studio/actuator/health"   # → 200
```

- **env(app.env) 핵심값**(관제 채널):
  - `JWT_SECRET` = **관제서버와 동일 시크릿**(관제 발급 JWT 검증용). 레포에 커밋 금지, env 로만.
  - `SPRING_DATASOURCE_CONTROL_JDBC_URL` = `jdbc:postgresql://postgis-klid:5432/klid`,
    `CONTROL_DB_USERNAME=cudo`, `CONTROL_DB_PASSWORD=***`.
    ⚠ 이 명시 JDBC URL 이 `CONTROL_DB_NAME=klid_system`(잔여값)보다 **우선**한다 — 실제 접속 DB 는 `klid`.
  - `ADMIN_CLAIM_PASSWORD_HASH` = **BCrypt('admin')** (관리자 부트스트랩 기본 비밀번호 `admin`, 전 환경 공통).
  - `SPRING_PROFILES_ACTIVE=dev`.
  - `authoring.control-notify.*` = **미설정 → 완료/수정 통지 off**. 관제로 실제 통지하려면
    `url`·`token`(관제 SPI 기대값, `x-access-token` 헤더로 감)·`enabled=true` 설정.
- Flyway 는 기동 시 `klid_at` 스키마에 적용된다(현재 V32). 마이그레이션 확인:
  `docker logs klid-authoring-jboss | grep -i flyway`.

## 4. 프론트엔드(정적) 배포

```bash
# 4-1. 246 레포의 frontend 를 origin/main 최신으로 (선택적)
nt ssh cudo_246 "cd /data/klid && git fetch origin && git checkout origin/main -- frontend/"

# 4-2. node:20-alpine 도커로 빌드 (호스트 node 는 v12라 불가)
nt ssh cudo_246 "cd /data/klid/frontend && docker run --rm \
  -v /data/klid/frontend:/app -w /app \
  -e VITE_BASE_PATH=/label-studio/ \
  -e VITE_API_BASE_URL=/label-studio/api/v1 \
  -e VITE_DEV_LOGIN_ENABLED=true \
  -e VITE_BUILD_CHANNEL=control \
  node:20-alpine sh -c 'npm ci && npm run build'"
#   산출: /data/klid/frontend/dist/

# 4-3. dist 를 apache 정적 디렉터리로 배포 (디렉터리 마운트 → 즉시 반영, apache 재시작 불필요)
nt ssh cudo_246 "cd /data/klid/closed-net/label-studio-web && \
  rm -rf assets index.html klid-config.js && cp -r /data/klid/frontend/dist/* ."

# 4-4. ★klid-config.js 런타임 설정 재적용 (dist 의 빈 기본형이 덮어쓰므로 매 배포 후 필수)
nt ssh cudo_246 "cat > /data/klid/closed-net/label-studio-web/klid-config.js <<'EOF'
window.__KLID_RUNTIME_CONFIG__ = {
  \"VITE_CONTROL_LOGIN_URL\": \"http://192.168.102.246:8088/auth/login\"
};
EOF"
```

- **빌드 플래그 의미**:
  - `VITE_BASE_PATH=/label-studio/` — 자산·라우터 basename.
  - `VITE_API_BASE_URL=/label-studio/api/v1` — axios base (apache 가 /api 벗겨 JBoss 로).
  - `VITE_DEV_LOGIN_ENABLED=true` — `/label-studio/dev/login` 노출(개발 진입 편의). 켜면
    토큰 없이 접근 시 관제 로그인 대신 dev-login 으로 간다(코드상 dev-login 우선).
  - `VITE_BUILD_CHANNEL=control` — **관제향 빌드**. 안내 문구가 "관제서버"로 단일화된다
    (포털향은 `portal` → "포털"). 기본값도 control.
- **klid-config.js** 는 런타임 오버라이드(재빌드 없이 환경값 주입). `VITE_CONTROL_LOGIN_URL` =
  세션 만료·토큰 없음 시 이동할 상위(관제) 로그인 주소.

## 5. Apache 라우팅 (klid.conf)

vhost `*:80`(ServerName klid-server-dev-01, DocumentRoot /local-storage/web/platform)에 아래가 있어야 한다:

```apache
# FE 정적 — /label-studio/api 는 Alias 에서 제외(그래야 아래 ProxyPass 가 잡는다)
AliasMatch "^/label-studio(?!/api/)(.*)$" "/local-storage/web/label-studio/$1"

# API — /label-studio/api/ 를 JBoss 로 (전역 ProxyPass 블록에)
ProxyPass        /label-studio/api/ http://klid-authoring-jboss:18080/label-studio/
ProxyPassReverse /label-studio/api/ http://klid-authoring-jboss:18080/label-studio/
```

- SPA 폴백 RewriteRule 은 `!^/label-studio` 로 이미 label-studio 를 제외한다(정적 Directory 에
  `FallbackResource /label-studio/index.html` 존재).

### ★★★ 치명적 함정 — 편집 후 반드시 `docker restart apache`

`klid.conf` 는 **RO 단일파일 바인드 마운트**라, 호스트 파일을 편집하면 **inode 가 바뀌어
컨테이너는 옛 내용을 계속 본다**. `apachectl -k graceful`·`httpd -t` 는 **stale inode** 를 읽으므로
"Syntax OK"·reload 가 성공해도 **편집이 반영되지 않는다**(이 프로젝트에서 수 시간을 잡아먹은 원인).

```bash
# klid.conf 편집 후:
nt ssh cudo_246 "docker restart apache"    # 마운트를 현재 호스트 inode 로 재바인딩 — 유일한 반영 수단
```

- 재시작 전 **문법 검증**(공유 apache 라 실패 시 관제까지 내려감):
  ```bash
  nt ssh cudo_246 "docker run --rm \
    -v /data/klid/closed-net/apache-conf/klid.conf:/etc/httpd/conf.d/klid.conf:ro \
    -v /data/klid/closed-net/webroot:/local-storage/web/platform:ro \
    -v /data/klid/closed-net/label-studio-web:/local-storage/web/label-studio:ro \
    -v /nas-storage:/nas-storage klid-apache-test httpd -t"
  #   'Configuration check failed' 가 ErrorLog 디렉터리 부재만이면 문법은 OK(환경 문제)
  ```

## 6. DB — 관제 관리자 신원

- 관제 admin 은 토큰에 `userId=admin` 을 싣는다. `klid_at.ls_acnt_user` 의 **9001 행이
  `user_id='admin', user_nm='시스템관리자'` + ADMIN 역할**이면 provisioning 없이 9001 로 해석된다.
- 신규 관제 진입자(미등록)는 진입 시 `ls_acnt_user_no_seq`(START 9,000,000,000)에서 userNo 를
  자동 발급받고 **role=null**(권한 매핑 안 함) → `/role-claim` 안내. 관리자 0명일 때만 부트스트랩
  (관리자 비밀번호 `admin`)로 첫 ADMIN 자가부여.

## 7. 검증

```bash
# apache 경유
nt ssh cudo_246 "docker exec apache curl -s -o /dev/null -w 'FE=%{http_code}\n'  http://localhost:8088/label-studio/"
nt ssh cudo_246 "docker exec apache curl -s -o /dev/null -w 'API=%{http_code}\n' http://localhost:8088/label-studio/api/actuator/health"   # 200
nt ssh cudo_246 "docker exec apache curl -s -o /dev/null -w 'me=%{http_code}\n'  http://localhost:8088/label-studio/api/v1/me"             # 401(무토큰) = 앱 도달
```

- 브라우저: `http://192.168.102.246:8088/label-studio/`
  - 토큰 없이 접근 → `/label-studio/dev/login`(dev-login 활성 시) 또는 관제 로그인.
  - dev-login → ADMIN(9001, 시스템관리자) → **대시보드**(실데이터).
  - 관제에서 로그인 후 진입 → 관제 JWT(`localStorage['klid-jwt-token']`) 인계 →
    role 있으면 대시보드, role=null 이면 `/role-claim`.

## 8. 알려진 주의

- **채널 인계 키는 `localStorage['klid-jwt-token']` 고정**(관제가 같은 키로 저장 — 확인됨).
  FE 는 channel 클레임 부재를 INTERNAL 로 수용한다(관제 토큰엔 channel 없음).
- **공유 DB**: postgis-klid/klid 는 도커 klid-backend 와 공유. dev-seed(`@Profile(local)`)는
  246 JBoss(dev)에서 안 돌지만, 도커 klid-backend 재시드가 9001 의 `user_nm` 을 되돌릴 수 있다
  (`ON CONFLICT DO UPDATE`는 user_nm 갱신, user_id 는 미변경). 영구 고정하려면 `dev-seed.sql`
  반영본으로 klid-backend 도 재빌드.
- **통지(control-notify)는 기본 off** — 관제로 완료/수정 통지하려면 §3 env 설정 필요.
