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

> ⚠⚠ **`docker restart` 로는 `app.env` 변경이 반영되지 않는다** (2026-09-07 실측).
> 환경변수는 컨테이너 **생성 시점**에 굳고 `restart` 는 그 값을 그대로 유지한다. `app.env` 를 고쳐도
> `docker exec ... sh -c 'echo $VAR'` 로 보면 **옛 값**이다 — 파일만 보고 반영됐다고 오인하기 쉽다.
> 실제로 `STORAGE_RAW_MOUNT_ROOTS` 를 고치고 재기동했는데 컨테이너는 옛 목록을 들고 있었다.
>
> **WAR 만 바꾸는 배포는 `restart` 로 충분하다** — 위 절차 그대로다. env 를 바꿔야 하면
> **컨테이너 재생성**이 필요하다. ⚠ 이 컨테이너에는 compose 라벨이 없어(`docker run` 으로 만든 것으로
> 보인다) `docker compose up -d` 로 되살릴 수 없다 — 재생성 전에 `docker inspect` 로 마운트·네트워크·
> 포트·env 를 전부 떠 두고, 되살릴 명령을 확인한 뒤에 지울 것.
>
> ⚠ **바꾼 값이 안 먹은 채로 시험하면 「기능이 안 된다」로 오판한다.** env 를 건드렸으면 반영 여부를
> 파일이 아니라 **컨테이너 안에서** 확인한다: `docker exec klid-authoring-jboss sh -c 'echo $VAR'`.

- **env(app.env) 핵심값**(관제 채널):
  - `JWT_SECRET` = **관제서버와 동일 시크릿**(관제 발급 JWT 검증용). 레포에 커밋 금지, env 로만.
  - `SPRING_DATASOURCE_CONTROL_JDBC_URL` = `jdbc:postgresql://postgis-klid:5432/klid`,
    `CONTROL_DB_USERNAME=cudo`, `CONTROL_DB_PASSWORD=***`.
    ⚠ 이 명시 JDBC URL 이 `CONTROL_DB_NAME=klid_system`(잔여값)보다 **우선**한다 — 실제 접속 DB 는 `klid`.
  - `ADMIN_CLAIM_PASSWORD_HASH` = **BCrypt('admin')** (관리자 부트스트랩 기본 비밀번호 `admin`, 전 환경 공통).
  - `SPRING_PROFILES_ACTIVE=dev`.
  - `authoring.control-notify.*` = **246 에 적용됨(ON)** — §3.1 참조. dev/stg/prd 는 코드 기본 ON
    (`application-{dev,stg,prd}.yml`), url·token 은 환경변수로 주입.
- Flyway 는 기동 시 `klid_at` 스키마에 적용된다(현재 V32). 마이그레이션 확인:
  `docker logs klid-authoring-jboss | grep -i flyway`.

### 3.1 관제 완료/수정 통지(control-notify) 연동

저작도구 → 관제 **아웃바운드** 통지(TASK_COMPLETED/TASK_MODIFIED). 검수 승인·수정 시 관제 SPI 로 push.

- **엔드포인트**: `POST {url}/api/data-set/v2/jobs/{jobId}/notify-completed` · `.../notify-updated`.
- **인증 헤더**: `x-access-token`. 관제가 **공유 시크릿 서명 JWT** 로 검증한다(정적키→401, 서명+만료 검증).
- **설정 키**(env):
  - `CONTROL_NOTIFY_ENABLED=true`
  - `CONTROL_NOTIFY_URL=http://apache` (JBoss→apache 컨테이너, apache 가 `/api/data-set/` 를 관제로 프록시. 호스트IP `http://192.168.102.246:8088` 도 가능)
  - `CONTROL_NOTIFY_TOKEN=<정적 서비스 JWT>` — **선택(override)**. 아래 토큰 정책 참조.
  - `CONTROL_NOTIFY_TOKEN_TTL_SECONDS`(선택, 기본 300) · `CONTROL_NOTIFY_TOKEN_ISSUER`(선택, 기본 klid-auth) · `CONTROL_NOTIFY_TOKEN_SUBJECT`(선택, 기본 klid-authoring-notify) — 동적 발급 파라미터.
  - `JWT_SECRET` — **동적 발급 서명키로 재사용**(인바운드 검증과 동일 시크릿). 이미 설정돼 있으면 동적 발급이 곧바로 성립.
- **★토큰 정책 (2026-09-05 확정 · CO-20260905 · ADR-063 ⑥ — 발송 시점 동적 발급으로 전환)**: 통지 `x-access-token` 은 **발송 시점에 앱이 동적 발급**한다(정적 장수명 토큰 폐기).
  - 통지는 **백그라운드(디바운서·재시도 잡·복구기)** 에서 나가 사용자 런타임 토큰(localStorage)을 실을 수 없다(컨텍스트 없음 + 만료) → **서비스 토큰을 앱이 발급**한다.
  - 발급 규칙 = 관제 규칙 정합: **alg=HS256**(`Jwts.SIG.HS256` 명시 — 91B 시크릿이라 자동선택은 HS512로 샌다) · **iss=klid-auth** · **exp=now+`token-ttl-seconds`**(기본 300s) · **공유 `JWT_SECRET` HMAC 서명** · sub=서비스 식별자. jti 로 발급마다 고유.
  - **override**: `CONTROL_NOTIFY_TOKEN` 을 설정하면 그 정적 값이 **우선**(관제팀이 별도 토큰을 주는 경우). 미설정이 기본이며 동적 발급이 작동한다. 정적·시크릿 모두 없으면 헤더 미부착 fail-safe.
  - ⚠ **현재 246 은 정적 `CONTROL_NOTIFY_TOKEN` 을 제거**(백업 `jboss-effective.env.bak-20260905-notoken`)해 동적 발급으로 동작한다. 구 임시 상태(HS512·exp 2046 정적 토큰)는 폐기됐다.
  - ⚠ 토큰·시크릿은 **credential** — 레포/로그 노출 금지. env-file 에만 둔다(앱도 값 미출력, 존재/길이/exp 만 로깅).
- ⚠ **env 추가/변경은 컨테이너 재생성 필요**(`docker restart` 는 `--env-file` 재독 안 함). §3.2 절차로 재생성.
- **검증**(2026-09-05 실측 통과): ① 기동 로그 `[ControlNotifyDebounce] flush scheduler started` + `[ControlNotify] … 동적 x-access-token 이 나갑니다`(동적 경로 배선 확인) ② 실왕복 — 공유 시크릿으로 HS256 테스트 토큰 발급 후 `POST {url}/api/data-set/v2/jobs/SELFTEST/notify-completed`(x-access-token) → **notify-completed=422**(인증 통과·업무검증 정상), 무토큰 대조 → **401**(인증 실제 작동). health `http://localhost:18080/label-studio/actuator/health`=200.

### 3.2 JBoss 컨테이너 재생성 (env 변경 시)

`--env-file` 은 생성 시점에만 읽히므로 env 추가/변경은 재생성한다. **현재 유효 env 를 덤프**해 손실 0 으로:

```bash
# 1) 현재 컨테이너 유효 env 덤프 + 통지 키 추가 (HOSTNAME 제외, 토큰은 값 미출력)
#    (prepenv.py 예시: docker inspect .Config.Env → env-file, JWT_SECRET 로 장수명 JWT 발급)
# 2) 재생성 (mounts·networks·ports·entrypoint·cmd 동일)
docker rm -f klid-authoring-jboss
docker run -d --name klid-authoring-jboss --restart no --network klidnet \
  --env-file /data/klid/closed-net/klid-authoring/jboss-effective.env \
  -v /data/klid/nas-storage:/nas-storage \
  -v /data/klid/genai-out:/app/genai-out \
  -v /data/klid/closed-net/klid-authoring/jboss-eap-8:/opt/jboss-eap-8 \
  -v /data/klid/storage:/app/storage \
  -p 38090:18080 --entrypoint /__cacert_entrypoint.sh \
  eclipse-temurin:17-jdk /opt/jboss-eap-8/bin/standalone.sh -b 0.0.0.0 -bmanagement 0.0.0.0
docker network connect klid-net klid-authoring-jboss   # 2번째 네트워크
```

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
# ★ apache 는 <컨테이너 안에서 80> 을 듣고 호스트 8088 로 매핑돼 있다(`0.0.0.0:8088->80/tcp`).
#   컨테이너 안에서 8088 을 부르면 <전건 000> 이 나와 배포 실패로 오판한다(2026-09-06 실측).
#   그래서 부르는 자리마다 포트가 다르다.

# 컨테이너 안에서 (:80)
nt ssh cudo_246 "docker exec apache curl -s -o /dev/null -w 'FE=%{http_code}\n'  http://localhost/label-studio/"
nt ssh cudo_246 "docker exec apache curl -s -o /dev/null -w 'API=%{http_code}\n' http://localhost/label-studio/api/actuator/health"   # 200
nt ssh cudo_246 "docker exec apache curl -s -o /dev/null -w 'me=%{http_code}\n'  http://localhost/label-studio/api/v1/me"             # 401(무토큰) = 앱 도달

# 호스트에서 (:8088)
nt ssh cudo_246 "curl -s -o /dev/null -w 'FE=%{http_code}\n'  http://localhost:8088/label-studio/"
nt ssh cudo_246 "curl -s -o /dev/null -w 'API=%{http_code}\n' http://localhost:8088/label-studio/api/actuator/health"   # 200
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
