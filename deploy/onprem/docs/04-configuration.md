# 04. 환경설정

## ★ 어느 파일이 정본인가 — 배포 형상마다 다르다 (먼저 읽을 것)

확정 배포 형상은 **외부 WAS(JBoss EAP 8.1, standalone) 에 `api.war` 반입**이다(@design DEPLOY-001 · RUNBOOK-001).
⚠ 구 서술 폐기(2026-09-04): *"Tomcat 10.1"* — 현장 실측으로 정정.
그 형상에서 **백엔드가 실제로 읽는 파일은 `backend.env` 가 아니다.**

| 형상 | 백엔드 설정 정본 | 템플릿 | 읽는 주체 |
|---|---|---|---|
| **WAR 반입 (확정 형상)** | **`/etc/klid/application.properties`** | `config/backend/application.properties.template` | **WAS** — 기동 옵션 `-Dspring.config.additional-location=file:/etc/klid/` |
| 베어메탈 토글(`INSTALL_BACKEND_SYSTEMD_UNIT=1`) | `/etc/klid/backend.env` | `config/backend/env.template` | systemd — `EnvironmentFile=/etc/klid/backend.env` |
| ai-server (형상 무관) | `/etc/klid/ai-server.env` | `config/ai-server/env.template` | systemd — `EnvironmentFile=` |

- 설치 스크립트는 **두 백엔드 파일을 모두 배치**한다(형상을 나중에 바꿀 수 있게). **쓰이지 않는 쪽을
  고치고 "반영이 안 된다"고 하는 것이 이 형상의 대표 함정**이므로, 고치기 전에 위 표를 확인한다.
- 형식은 두 파일 모두 `KEY=VALUE`(한 줄 1개, 따옴표/셸확장 없음). 비밀번호 포함 → `chmod 640`, `root:klid`.
- **아래 A 절의 항목표는 두 파일에 공통**이다. 표의 「변수」는 앱 고유 키 기준이며, `application.properties`
  에서 이름이 달라지는 예외는 바로 아래 「키 이름 변환 규칙」이 전부다.
- WAS 유닛명·경로 등 **현장값**은 `/etc/klid/was.env` 에 적어 둔다
  (→ [09-operations-runbook.md](09-operations-runbook.md) §0-1. 운영 관례이며 프로그램이 읽는 설정이 아니다).

### ★ 키 이름 변환 규칙 (`backend.env` → `application.properties`)

**환경변수일 때만** 스프링이 `SPRING_FLYWAY_ENABLED` → `spring.flyway.enabled` 로 이름을 변환한다.
`application.properties` 는 환경변수가 아니라 **설정 파일**이라 그 변환이 일어나지 않는다.

| 키의 성격 | `backend.env`(환경변수) | `application.properties`(설정 파일) | 비고 |
|---|---|---|---|
| **앱 고유 키** (`CONTROL_DB_HOST` · `JWT_SECRET` · `KPST_*` · `STORAGE_*` · `ENV` …) | `CONTROL_DB_HOST=...` | **그대로** `CONTROL_DB_HOST=...` | 프로파일 yml 의 자리표시자가 이 이름으로 찾으므로 정상 해석 |
| **스프링 자체 설정** (`SPRING_` 접두) | `SPRING_FLYWAY_ENABLED=false` | **점 표기** `spring.flyway.enabled=false` | 대문자·밑줄로 적으면 **조용히 무시된다** |
| **활성 프로파일** | `SPRING_PROFILES_ACTIVE=prd` | **파일에 적지 않는다** → WAS 기동 옵션 `-Dspring.profiles.active=prd` | 설정 파일이 로드되는 시점을 프로파일이 정하므로 |
| **JVM 옵션** | `JAVA_OPTS=...`(systemd 가 `ExecStart` 에 전개) | **파일에 적지 않는다** → WAS 의 **`JAVA_OPTS`**(EAP `bin/standalone.conf`) | JVM 기동 옵션이라 스프링이 읽지 않는다. ⚠ 톰캣의 `CATALINA_OPTS` 가 아니다 |

> ⚠ **`SPRING_FLYWAY_ENABLED=false` 를 `application.properties` 에 적은 사고가 실제로 있었다** —
> 조용히 무시되어 DBA 가 선적용한 스키마 위에서 마이그레이션이 그대로 돌았다.
> `spring.flyway.enabled=false` 로 고치니 실행 0건이 됐다. **오류가 아니라 무시**라 로그에도 안 남는다.
>
> ⚠ **`server.*` 는 어느 파일에 적어도 WAR 형상에서 적용되지 않는다**(내장 서버 전용).
> 업로드 본문 한도·스레드 예산·비동기 타임아웃은 **WAS 커넥터 설정**으로 옮겨야 한다 —
> → [10-was-settings.md](10-was-settings.md), 예시 파일 `config/was/`.

### ★ 두 템플릿의 키 집합은 사람이 맞춘다 — 어긋나면 조용히 기본값이 된다

`env.template` 과 `application.properties.template` 은 **같은 키 목록을 각자 들고 있는 사본 관계**다.
한쪽에만 키를 추가하면 다른 형상에서 그 키가 빠진 채 배포되고, **빠진 키는 오류가 아니라 기본값으로
동작**한다(부팅 차단 대상 키가 아니면 아무 신호가 없다).

- **키 목록의 진실원은 `config/backend/env.template`** 이다. `application.properties.template` 은 그것을
  옮겨 적은 사본이며 머리말에도 그렇게 적혀 있다. **키를 추가·삭제하면 두 파일을 같은 커밋에서 고친다.**
- **어긋났는지 확인하는 법** — 주석·빈 줄을 걷어내고 키 이름만 뽑아 대조한다.

```bash
cd deploy/onprem/config/backend
keys() { grep -oE '^[A-Za-z_][A-Za-z0-9_.]*=' "$1" | tr -d '=' | sort -u; }
diff <(keys env.template) <(keys application.properties.template)
# 출력이 비어 있으면 동기 상태.
# 나오는 것이 정상인 <의도된 차이>는 아래 넷뿐이다:
#   < SPRING_PROFILES_ACTIVE   (properties 쪽은 WAS 기동 옵션으로 준다)
#   < SPRING_FLYWAY_ENABLED    (properties 쪽은 spring.flyway.enabled 로 표기)
#   < JAVA_OPTS                (properties 쪽은 WAS 의 JAVA_OPTS 로 옮긴다 — EAP bin/standalone.conf)
#   > spring.flyway.enabled    (위 SPRING_FLYWAY_ENABLED 의 점 표기 짝)
# 그 밖의 줄이 나오면 한쪽에만 추가된 키다 — 반드시 양쪽을 맞춘다.
```

> ⚠ **이 대조는 자동화돼 있지 않다**(빌드·설치·CI 어디에도 검사가 없다). 키를 만지는 사람이
> 위 명령을 직접 돌리는 것이 현재의 유일한 방어다. 값까지 같은지는 검사하지 않는다 — 두 파일의
> **값은 형상마다 다를 수 있고**(예 주소), 맞춰야 하는 것은 **키의 존재**와 **운영 값의 일치**다.

---

---

## ★ 주소 한 표 — 이 시스템이 가리키는 모든 주소 (장비 2대 기준)

대상 장비는 2대다 — **서버 A(app)** = 프론트(httpd) + 백엔드(WAR) + DB, **서버 B(ai)** = ai-server.

「어디에」 칸의 파일은 **그 값을 읽는 장비**에 있는 파일이다. 다른 장비에서 같은 이름의 파일을
고쳐도 아무 일도 일어나지 않는다 — 2대 구성에서 가장 흔한 착오다.

| 가리키는 대상 | 키 | 어디에 (장비 · 파일) | 기본값 | 2대 구성에서 |
|---|---|---|---|---|
| **AI 추론 서버** | `AI_SERVER_URL` | **A** · `/etc/klid/application.properties` | `http://127.0.0.1:9300` | ★ **반드시 서버 B 주소로 바꾼다** |
| **AI 서버가 받을 주소** | `AI_BIND_HOST` | **B** · `/etc/klid/ai-server.env` | `127.0.0.1` | ★ **반드시 바꾼다** (+ 방화벽 9300/tcp) |
| control DB | `CONTROL_DB_HOST` · `CONTROL_DB_PORT` | A · `application.properties` | `127.0.0.1` · `5432` | 외부 DB 를 쓰면 그 주소 |
| 비식별(KPST) 서버 | `KPST_DEID_BASE_URL` | A · `application.properties` | `https://127.0.0.1:9201` | ★ 동거 KPST 주소로 교체(스킴에 따라 CA 필요) |
| 비식별 헬스 핑 | `DEIDENTIFY_API_URL` | A · `application.properties` | `http://127.0.0.1:9200` | 헬스 인디케이터 전용 — 실 비식별 호출에는 쓰이지 않는다 |
| 관제 통지 수신처 | `CONTROL_NOTIFY_URL` | A · `application.properties` | `http://127.0.0.1:8090` | **통지 전용** — `CONTROL_NOTIFY_ENABLED=true` 일 때만 쓰인다. 관제 웹서버 주소, 또는 데이터셋 창구(`/api/data-set/`)를 가진 WAS 주소 |
| **관제 계정 창구** | `CONTROL_ACCOUNT_URL` | A · `application.properties` | **(빈 값)** | ★ **관제 채널 배포본은 반드시 채운다** — 비우면 세션 연장·로그아웃 중계가 관제를 부르지 않고 실패한다(통지 수신처 주소로 대체하지 않는다). 관제 웹서버 주소, 또는 계정 창구(`/api/account/`)를 가진 WAS 주소. ⚠ 관제는 계정 창구와 데이터셋 창구를 **서로 다른 WAS** 에 두므로 WAS 를 직접 가리키면 두 칸에 서로 다른 WAS 를 넣는다(관제 웹서버 주소면 같은 값이어도 된다). 확인: `curl -s -X POST <주소>/api/account/auth/refresh` → **401 JSON 이면 맞는 주소, 404 HTML 이면 틀린 주소** |
| 외부 시계열 분석 벤더 | `VLM_SERVICE_URL` | A · `application.properties` | **(빈 값)** | ⚠ **비워 두는 것이 정상** — 아래 ② |
| 외부 생성형 AI 증강 벤더 | `AUGMENT_API_BASE_URL` | A · `application.properties` | (빈 값 = 미연동) | 주소가 곧 연동 여부다. 채우면 `WEBHOOK_GENAI_ALLOWED_IP_CIDRS` 도 **함께** 채운다 |
| 증강 콜백이 되돌아올 우리 주소 | `WEBHOOK_CALLBACK_BASE_URL` | A · `application.properties` | `http://127.0.0.1:8080/api` | 외부가 <우리를> 부를 수 있는 주소여야 한다 |
| httpd → 백엔드(WAS) 프록시 대상 | `BACKEND_ORIGIN` (설치 시 환경변수) | A · `/etc/httpd/conf.d/klid-frontend.conf` | `http://127.0.0.1:8080` | WAS 가 같은 장비면 그대로 |
| 백엔드 주소(운영 런북용 메모) | `WAS_BACKEND_ORIGIN` | A · `/etc/klid/was.env` | `http://127.0.0.1:8080` | 앱 설정이 아니다 — 런북 명령이 읽는 기록값 |
| 프론트 → API | `VITE_API_BASE_URL` | A · `/etc/klid/frontend.env` | `/api/v1` | 동일 origin 프록시면 그대로 |
| 상위 시스템 로그인 2종 | `VITE_CONTROL_LOGIN_URL` · `VITE_PORTAL_LOGIN_URL` | A · `/etc/klid/frontend.env` | **(빈 값)** | ★ 비면 **설치가 멈춘다**(20 단계) |
| ai-server CORS allowlist | `CORS_ALLOW_ORIGINS` | **B** · `/etc/klid/ai-server.env` | `http://127.0.0.1:8080` | ⚠ **바꿀 필요 없다** — 아래 ③ |

> 베어메탈 형상(`INSTALL_BACKEND_SYSTEMD_UNIT=1`)에서는 위 「A · `application.properties`」를
> 전부 `/etc/klid/backend.env` 로 읽는다. **키 이름은 같다**(위 「키 이름 변환 규칙」의 넷만 예외).

### 반드시 짚고 갈 세 가지

**① `AI_SERVER_URL` 이 틀려도 아무 신호가 없다.**
백엔드는 정상 기동하고 헬스체크도 통과한다. 실패하는 것은 **오토라벨링(YOLO 탐지 · SAM2 분할/추적)뿐**이고,
그것도 사용자가 그 기능을 부를 때 비로소 드러난다. 기본값이 loopback 이라 **2대 구성에서는 반드시 틀리다.**
그래서 설치 끝에 전용 검사(`install/21-verify-ai-server-url.sh`)를 두어 역할이 `app` 일 때 경고한다.
고친 뒤 다시 확인하려면 그 스크립트만 단독으로 돌리면 된다:

```bash
sudo KLID_ROLE=app /경로/deploy/onprem/scripts/install/21-verify-ai-server-url.sh
```

⚠ **이 검사는 설치를 실패시키지 않는다.** 이 시점은 운영자가 설정을 편집하기 **전**이라
DB 비밀번호조차 비어 있기 때문이다 — 주소 하나만 골라 기동을 막으면 일관성이 없다.
**차단이 아니라 드러내기**가 이 검사의 역할이다.

⚠ **주소를 바꾸는 것만으로는 부족하다.** ai-server 는 기본이 loopback 바인드라 서버 A 에서 닿지 않는다.
서버 B 에서 `AI_BIND_HOST` 를 바꾸고 방화벽 9300/tcp 을 **서버 A 에서만** 오도록 여는 두 가지를 함께 해야 한다.
(ai-server 에는 인증이 없다 — 넓게 열면 누구나 추론을 호출할 수 있다.)

**② `VLM_SERVICE_URL` 은 비워 두는 것이 정상이다 — 미리 채우지 마라.**
이 연동에는 on/off 토글이 없다. **주소가 있느냐 없느냐**가 곧 연동 여부 판정이다.
비어 있으면 URL 검증을 건너뛰어 기동이 정상이고, 위탁은 조용히 넘어가지 않고 실패로 기록된다.
반대로 `http://127.0.0.1:...` 같은 값을 미리 채우면 "주소가 있다"로 판정되어 **기동은 그대로 되고**
위탁만 그 엉뚱한 주소로 나가 실패한다. 벤더 연동이 확정될 때 실제 주소를 넣는다.

⚠ **구 서술 폐기(2026-09-01)** — *"운영 정책(HTTPS 전용 + 사설망 차단)에 걸려 기동이 막힌다"* 는
사실이 아니다. 그 정책은 폐기됐고 **평문 `http` 와 사설·루프백 대역이 전 프로파일에서 통과**한다.
이 서술대로 알고 있으면 정상 주소를 못 넣는다고 오판하게 된다. 남아 있는 거부 축은
**예약 대역**(클라우드 메타데이터 `169.254.169.254`·링크로컬·ULA·CGNAT 등)과 스킴·형식뿐이다.
그럼에도 **비워 두라는 권고 자체는 그대로 유효하다** — 이유가 "기동이 막혀서"에서
"조용히 잘못된 곳으로 위탁이 나가서"로 바뀐 것이며, 후자가 더 나쁘다(실패가 늦게 드러난다).

**③ ai-server 의 `CORS_ALLOW_ORIGINS` 는 2대 구성이라고 바꿀 필요가 없다.**
그 값은 **브라우저가 ai-server 를 직접 호출할 때만** 쓰인다. 백엔드 → ai-server 는 서버 간 호출이라
`Origin` 헤더가 없고 CORS 심사 대상이 아니다. 2대로 나눌 때 실제로 바꿔야 하는 것은
**`AI_BIND_HOST`(+방화벽)와 `AI_SERVER_URL`** 둘뿐이다.

---

## ★ 값을 바꾼 뒤 무엇을 다시 해야 반영되나

값마다 반영 방법이 다르다. **재기동이 필요 없는 값에 재기동을 하면 불필요한 중단**이고,
**필요한 값에 안 하면 고쳤는데 그대로**다.

| 고친 파일 | 반영에 필요한 것 | 서비스 중단 |
|---|---|---|
| `/etc/klid/frontend.env` (프론트 런타임 설정) | `sudo /opt/klid/bin/klid-frontend-config` **한 줄** | **없음** — 웹 서버 재기동도 불필요 |
| `/etc/klid/application.properties` (WAR 형상 백엔드) | **WAS 재기동** — `source /etc/klid/was.env; was_restart` | 있음(백엔드) |
| `/etc/klid/backend.env` (베어메탈 형상 백엔드) | `sudo systemctl restart klid-backend` | 있음(백엔드) |
| `/etc/klid/ai-server.env` (`AI_BIND_HOST` 포함) | `sudo systemctl restart klid-ai-server` | 있음(추론만) |
| `config/systemd/*.service` 를 다시 설치한 경우 | `sudo systemctl daemon-reload` **후** 해당 서비스 재기동 | 있음 |
| `/etc/httpd/conf.d/klid-frontend.conf` (웹 서버) | `sudo systemctl reload httpd` | 없음(무중단 재적재) |
| `/etc/klid/was.env` | **없음** — 앱이 읽지 않는다. 다음 런북 명령부터 적용된다 | 없음 |

> `was_restart` 는 `/etc/klid/was.env` 가 정의하는 함수다 — WAS 가 systemd 로 뜨는지
> 스크립트로 뜨는지의 차이를 그 안에서 흡수한다(`09-operations-runbook.md` §0-1).
> ⚠ 현장은 systemd 가 아니므로 `systemctl restart` 를 직접 쓰지 말 것.

> ⚠ **일부 값은 재기동만으로 반영되지 않는다** — 애플리케이션 <설정 화면>에서 연동 주소를 덮어쓴
> 이력이 있으면 그 override 가 배포 기본값보다 우선한다. 파일을 고쳤는데 동작이 그대로면
> 설정 화면의 override 를 먼저 확인한다.

---

## ★ 설치 <전>에 값을 미리 넣어 두기 (사전 주입)

값을 이미 아는 현장이라면, 설정을 먼저 놓아 두고 설치를 **한 번에 완주**시킬 수 있다.
**설치 스크립트는 이미 있는 설정 파일을 덮어쓰지 않는다** — 이건 이 패키지의 확정 규칙이고
아래 파일 전부에 적용된다(재설치가 현장값을 날리지 않게 하기 위함).

| 미리 놓을 파일 | 이 파일을 놓는 단계 | 이미 있으면 |
|---|---|---|
| `/etc/klid/application.properties` | 12 | **보존**(덮지 않음) |
| `/etc/klid/backend.env` | 12 | **보존** |
| `/etc/klid/was.env` | 12 | **보존** |
| `/etc/klid/ai-server.env` | 13 | **보존** |
| `/etc/klid/frontend.env` | 14 | **보존** |

### 방법 1 — 대상 서버에 파일을 미리 놓는다

```bash
# 설치 전에 (매체를 풀어 둔 디렉터리에서)
sudo install -d -m 750 /etc/klid
sudo install -m 640 config/backend/application.properties.template /etc/klid/application.properties
sudo install -m 640 config/ai-server/env.template                  /etc/klid/ai-server.env
sudo install -m 644 config/frontend/frontend.env.template          /etc/klid/frontend.env
sudo $EDITOR /etc/klid/application.properties     # 주소·비밀번호를 채운다
sudo $EDITOR /etc/klid/frontend.env               # 상위 로그인 주소 2종을 채운다
# 그 다음 설치 — 놓아 둔 파일을 그대로 쓰고 멈추지 않는다
sudo ./scripts/install.sh --role=app
```

### 방법 2 — 매체에서 채운 채로 반입한다

빌드머신에서 `config/**` 템플릿을 채운 상태로 매체에 담으면, 설치가 그 값을 그대로 배치한다.
⚠ **비밀값을 채운 매체는 그 자체가 비밀이다** — 반출·보관 규정을 따르고, 형상관리에 커밋하지 않는다.
⚠ 두 백엔드 템플릿(`env.template` · `application.properties.template`)을 채울 때는 **키 이름 변환 규칙**
(위 절)을 지킨다. `spring.` 으로 시작하는 값을 대문자로 적으면 조용히 무시된다.

### 방법 3 — 설치 명령에 환경변수로 준다 (프론트 로그인 주소 전용)

```bash
sudo VITE_CONTROL_LOGIN_URL=https://<관제 로그인 주소> \
     VITE_PORTAL_LOGIN_URL=https://<포털 로그인 주소> \
     ./scripts/install.sh --role=app
```

> ⚠ **httpd 드롭인(`/etc/httpd/conf.d/klid-frontend.conf`)만은 매번 다시 생성된다**(보존 대상이 아니다).
> 프록시 대상을 바꾸려면 설치할 때 `BACKEND_ORIGIN` 을 환경변수로 주거나, 설치 후 파일을 고치고
> `systemctl reload httpd` 한다. **설치를 다시 돌리면 손으로 고친 내용이 사라진다.**

## A. 백엔드 설정 항목표 (두 형상 공통)

**WAR 형상은 `/etc/klid/application.properties`, 베어메탈 토글은 `/etc/klid/backend.env` 에 적는다** —
표의 「변수」는 두 파일에 같은 이름으로 쓰인다(예외는 위 「키 이름 변환 규칙」의 넷뿐).
⚠ 구 제목 폐기(2026-08-30) — *"A. backend.env 항목표"*. 확정 형상에서 그 파일은 쓰이지 않는다.

출처: `backend/src/main/resources/application.yml` + `application-prd.yml`(+ local).
★=필수, ·=선택/기본값 사용 가능.

### 기본 / DB / 보안

| 변수 | 필수 | 의미 / 기본 |
|------|:----:|-------------|
| `SPRING_PROFILES_ACTIVE` | ★ | 운영 권장 `prd`. local 은 LocalProfileGuard 가 비-local 호스트에서 거부 |
| `ENV` | ★(stg·prd) | **배포 환경 표식**(`stg`/`prd`) — 프로파일과 독립된 축. 서버 잔존 `.env`·셸 환경이 `SPRING_PROFILES_ACTIVE` 를 `dev` 로 덮어도 이 값이 배포 표식이면 dev 편의 엔드포인트(`DevProfileGuard`)·Quartz 단일노드 허용(`QuartzClusteringGuard`)이 모두 **거부**된다. **배포 서버에서 비우면 이 방어축이 통째로 무력해진다** |
| `CONTROL_DB_HOST/PORT/NAME` | ★ | control DB(klid_system). prd 가 jdbc-url 조립. 스키마는 `db/schema.sql` 1회 로드로 준비(D 절) |
| `CONTROL_DB_USERNAME/PASSWORD` | ★ | control DB 자격 |
| `DB_SCHEMA` | · | **저작도구 스키마. 기본 `klid_at`** — 보통 바꾸지 않는다. 커넥션 `currentSchema` / Quartz `tablePrefix` / JPA `default_schema`(그리고 Flyway 를 켠 개발 환경이면 `schemas`·`default-schema`)가 **이 값 하나**를 함께 읽는다. 설치 스크립트(`gen-schema-sql.sh`·`16-load-schema.sh`)도 **같은 변수명**을 쓴다 — 앱과 설치가 갈리면 "설치는 됐는데 앱이 빈 스키마를 본다"가 된다. ⚠ 구 서술 폐기: *"portal DB 는 대상 아님"* — 포털 데이터소스 자체가 2026-08-31 에 철거됐다 |
| `JWT_SECRET` | ★ | HS256 검증 시크릿(≥32B). 미설정 시 부팅 실패 |
| `JWT_ISSUER` / `JWT_ALLOWED_ISSUERS` | · | 기본 `klid-auth` / `klid-auth,klid,klid-portal` |
| `PORTAL_CLEANUP_API_KEY` | · | **포털 정리 삭제 트리거 창구의 사전 공유 키**(기본 빈 값). 포털이 학습데이터셋의 새 버전을 받으면 그 창구(`/v1/portal-system/dataset-cleanups`)를 부르는데, 부르는 쪽이 사람이 아니라 포털 서버라 **토큰이 아니라 사전에 나눠 가진 고정 문자열**로 가른다. **비면 그 창구가 닫힌다** — 어떤 요청도 통과하지 못한다(fail-closed). 값이 없을 때 열리는 것이 아니라 **닫히는** 방향이라 안전하다. ⚠ 포털이 호출을 시작했는데 이 값이 비어 있으면 **전건 거부**가 되고 포털에는 「키가 틀렸다」로 보인다 — 연동을 켤 때 반드시 채울 것. ⚠ **관제지원 수신 채널이 쓰는 키와 섞지 않는다**(연동 축마다 별도 키). 저작도구가 생성해 안전한 채널로 포털에 공유한다 |
| `STREAM_SIGN_SECRET` | ★ | 영상 스트림 서명 시크릿(JWT_SECRET 과 다른 ≥32B). 미설정 시 스트리밍 fail-closed |
| `STREAM_URL_TTL_SECONDS` | · | 기본 60 (5~600) |
| `STREAM_COOKIE_SECURE` | · | 스트림 nonce 쿠키(`klid_stream_nonce`)에 `Secure` 를 붙일지. **기본 `false`** 이고 이 배포는 프런트가 평문 HTTP(`:80`)라 그대로 두는 것이 맞다 — `true` 면 브라우저가 쿠키를 저장하지 않아 스트림이 **전건 401**(영상 재생 불가)이 된다. 앞단에 사내 TLS 종단을 두어 HTTPS 로 서비스하면 `true`. ⚠ **빈 값 금지** — 비우면 기동 실패(`Invalid boolean value []`). `true` 또는 `false` 만 사용 |
| `ADMIN_CLAIM_PASSWORD_HASH` | · | 관리자 공유 패스워드 BCrypt 해시(cost≥12). 평문 금지. ★**기본값은 평문 `admin` 의 해시**(2026-09-01) — 비워 두면 아무도 관리자가 되지 못하는데 그 상태에 신호가 없어서다. ⚠⚠ **널리 알려진 값이라 첫 진입 직후 화면에서 반드시 교체한다.** ⚠ **최초 판정에만 쓰인다** — 운영 중 화면에서 패스워드를 한 번 바꾸면 그 값이 `klid_at.ls_mngr_pswd` 로 저장되고, 그 뒤로는 이 변수를 고쳐도 반영되지 않는다(저장소 우선). 되돌리는 절차는 [09-operations-runbook.md](09-operations-runbook.md) §4-2 |

### prd·stg 필수 webhook 콜백 보안 (★ 부팅 차단 주의)

| 변수 | 필수 | 의미 |
|------|:----:|------|
| `WEBHOOK_HMAC_SECRET_VLM` | · (미사용) | VLM describe 콜백은 벤더 v2.0.1 **무서명** 규격이라 HMAC 을 쓰지 않는다. 현재 코드에 **소비처가 없어** 설정해도 무시되고, 미설정이어도 부팅을 막지 않는다 |
| `WEBHOOK_HMAC_SECRET_AUGMENT` | ★(prd) | 미설정/빈 값이면 `BeanInitializationException` 으로 부팅 실패. 32B(256bit) 미만이거나 **리포에 커밋된 placeholder 값**이면 부팅 차단(공개 키 서명 위조 차단, DEV_FIX H-1) |
| `WEBHOOK_HMAC_TIMESTAMP_WINDOW` | · | 기본 300 |
| `WEBHOOK_TRUSTED_PROXY_CIDRS` | ★(prd·stg) | 신뢰 프록시 CIDR(CSV). 이 대역에서 온 요청의 `X-Forwarded-For` 만 해석. **직접 노출이면 `none` 명시**. 미설정 시 부팅 차단. **형식 오류(오타·호스트명)도 부팅 차단**(DEV_FIX N-4 — 조용히 무시하면 방어가 꺼진 채 기동) |
| `WEBHOOK_VLM_ALLOWED_IP_CIDRS` | ★(prd·stg) | VLM 무서명 콜백 허용 출처 CIDR(CSV). **미적용을 의도하면 `none` 명시**. 미설정 시 부팅 차단. 형식 오류도 부팅 차단 |

> 외부 증강 콜백을 받지 않더라도, prd 로 기동하려면 `WEBHOOK_HMAC_SECRET_AUGMENT` 에 **임의의 강한 시크릿**을 채워야 부팅된다.
> 생성: `openssl rand -hex 32`. 예시 문서의 값을 복사해 쓰지 말 것(공개 키 = 위조 가능).
>
> **stg 도 명시 필수(REDESIGN R-3)**: stg 는 온프렘 개발서버로 LB/Nginx 뒤에 배포되는데 과거에는 명시 강제가
> prd 에만 걸려 있어 stg 가 빈 값으로 조용히 기동했다(모든 콜백 IP 가 LB IP 로 수렴). 이제 stg 도 미설정 시 부팅이 차단된다.
>
> **`WEBHOOK_TRUSTED_PROXY_CIDRS` 를 비워 두면 안 되는 이유**: LB/Nginx 뒤 배포에서 미설정이면 모든 콜백의
> 클라이언트 IP 가 LB IP 하나로 수렴한다. 그 상태에서 누군가 인증 실패 5회를 유발하면 **정상 벤더 콜백까지
> 60초 동안 429** 가 되고, 공유 카운터를 통해 2노드 전체로 전파된다. 프록시가 없다면 `none` 을 명시해
> "XFF 무시" 를 의식적으로 선택한다.

### 이중화(HA) — Quartz 클러스터링 (★ 부팅 차단 주의)

배포 토폴로지는 **주 서버 2노드 Active-Active** 다. Quartz 클러스터링을 켜지 않으면 두 노드가
`QRTZ_LOCKS` 로 조율되지 않아 **같은 트리거를 각각 발화**한다(관제 학습용 스캔 60s · KPST 폴링 30s ·
export sweep 600s). `@DisallowConcurrentExecution` 은 **스케줄러 인스턴스 내부에서만** 유효해 이를 막지 못한다.

| 변수 | 필수 | 의미 / 기본 |
|------|:----:|-------------|
| `QUARTZ_CLUSTERED` | ★(prd·stg) | `true` 고정. stg/prd 프로파일 기본값이 `true` 이며 **`false` 면 기동 거부**(`QuartzClusteringGuard`). 양 노드 동일 값 |
| `QUARTZ_CHECKIN_MS` | · | 클러스터 체크인 주기(ms, 기본 20000). 노드 장애 감지 지연과 직결 — 30초 이내 권장 |

> **1노드로만 운영하더라도 끄지 않는다** — 클러스터 모드는 단일 노드에서도 정상 동작한다(락 경합 상대가 없을 뿐).
> `SPRING_PROFILES_ACTIVE` 를 dev 로 낮춰 우회할 수도 없다: 가드가 `ENV=stg|prd` 표식을 독립 축으로 함께 본다
> (`DevProfileGuard` 와 동일 기준). **단 이 축은 `ENV` 가 실제로 주입돼야 작동한다** — `env.template` 이
> `ENV=prd` 를 제공하고 systemd 가 `EnvironmentFile=/etc/klid/backend.env` 로 프로세스 환경에 넣는다.
> stg 노드는 설치 후 `ENV=stg` 로 바꾼다. 비워 두면 프로파일 축만 남아 `dev` 로 뜬 배포 노드가 통과한다.

**★ 설치 전 체크리스트 — 노드 간 시계 동기(NTP)**

클러스터링은 노드 클럭이 맞는다는 전제 위에서 동작한다. 오차가 크면 misfire 오판·중복 발화·락 스톰이 발생한다.

- [ ] 두 노드 모두 `chronyd`(또는 `ntpd`) **활성**: `timedatectl` → `NTP service: active`
- [ ] 두 노드가 **동일 NTP 서버**를 바라봄: `chronyc sources`
- [ ] 시계 오차 **1초 이내**: `chronyc tracking` → `System time` offset 확인
- [ ] 두 노드 타임존 동일(`Asia/Seoul` — `JAVA_OPTS` 의 `-Duser.timezone` 과 일치)
- [ ] 두 노드 `QUARTZ_CLUSTERED=true` 동일 설정 + 동일 DB(`CONTROL_DB_*`) 를 바라봄
- [ ] 기동 **전에** 스키마가 로드돼 있음(`db/schema.sql` 1회) — Flyway 를 쓰지 않으므로 노드 기동 순서를 가릴 필요는 없다. ⚠ 스키마가 없어도 **두 노드 모두 기동에는 성공하므로**(`ddl-auto=validate` 실동작 안 함 — 아래 D 절) 기동 성공을 로드 완료의 근거로 쓰지 말 것
- [ ] 기동 후 등록 노드 수 확인: `SELECT instance_name, last_checkin_time FROM qrtz_scheduler_state;` → 노드 수만큼 행

> `QRTZ_*` 테이블(11개)과 클러스터 락 행(`SCHED_NAME='KlidAuthoringScheduler'`)은 **`db/schema.sql` 안에
> 함께 들어 있다** — 그 파일을 로드하면 준비되며 별도 사전 적재는 불필요하다.

### 저장소 / ai-server / CORS / FFmpeg

| 변수 | 필수 | 의미 / 기본 |
|------|:----:|-------------|
| `STORAGE_RAW_PATH` | ★ | 원본 영상·프레임 저장 베이스. **NAS 마운트 루트와 일치 필수**(기본 `/nas-storage`). v2 가 DB 절대경로를 이 베이스 기준 startsWith 가드로 검증해 서빙 — 관제 적재·v1 이관본(`/nas-storage/...`)이 이 베이스로 시작해야 정상 서빙(불일치 시 NOT_FOUND). **★ 운영 제약 — `{STORAGE_RAW_PATH}/data/upload/v2` 는 저작도구 내부 업로드 전용 디렉터리이며 관제가 클립을 놓아서는 안 된다**(아래 ⚠ 참조) |
| `STORAGE_DEIDENTIFIED_PATH` | ★ | 비식별 영상·프레임 저장 베이스(기본 `/nas-storage`). 위와 동일 — NAS 마운트 루트와 일치 |
| `STORAGE_RAW_MOUNT_ROOTS` | ★ | **산출물 쓰기 allowlist**(콤마 구분, 기본 `/nas-storage`). 검수 승인 산출물·비식별 영상은 `dirname(RAW_FILE_PATH_NM)/{RAW_SN}/` 에 생성되며, 그 base 가 이 목록 하위일 때만 허용된다(위반 시 폴백 없이 실패, CWE-22). `/` 등 파일시스템 루트를 넣으면 **기동 차단**. 실제 클립 서브트리를 확인해 최대한 좁게 지정 권장(예: `/nas-storage/data/clip/gov,/nas-storage/label-studio/src`). **★ 좁힐 때는 `STORAGE_RAW_PATH` 를 반드시 포함할 것 — 미포함 시 관리 화면 영상 업로드 기능이 비활성**(기동은 정상, `POST /v1/uploads` 등 TUS 엔드포인트가 503 + 기동 로그에 ERROR 1회). 업로드 파일은 `{STORAGE_RAW_PATH}/data/upload/v2/` 에 저장되는데 그 경로가 allowlist 밖이면 인입이 매 건 `REJECTED` 로 영구 종결되므로, 조용히 성공시키는 대신 기능을 닫는다 |
| `STORAGE_EXTERNAL_READ_ROOTS` | · | **외부 벤더 산출물 읽기 allowlist**(콤마 구분, 기본 빈 값). 생성형 AI(증강) 벤더가 결과 이미지를 자기 트리에 쓰고 그 절대경로를 콜백으로 주므로, 그 경로를 **쓰기 allowlist 에 넣지 않고** 이 읽기 축에만 추가한다(우리는 읽어서 파생 프레임으로 복사만 하며 이 트리에 쓰지 않는다 — 벤더 마운트는 `ro` 권장). 미설정이면 읽기 허용 범위 = `STORAGE_RAW_MOUNT_ROOTS` 와 동일이라 벤더 경로 콜백이 400 으로 거부되고 증강이 `PENDING` 에 머문다(fail-closed). `/` 지정 시 **기동 차단** |
| `AI_SERVER_URL` | ★ | 기본 `http://127.0.0.1:9300` |
| `AI_SERVER_FAIL_THRESHOLD` / `AI_SERVER_RECOVER_THRESHOLD` | · | **AI 장비 상태점검 임계(추론 축)** — 각 기본 3. 장비를 여러 대 등록했을 때 주기 점검이 **연속 몇 번**을 보고 목록에서 빼고 되돌릴지다. 한 번 흔들렸다고 내리지 않게 하는 값이다. 0·음수는 1 로 흡수되어 「0회 실패로 즉시 배제」는 만들 수 없다. ★**시계열 축과 자리가 갈려 있어 여기를 바꿔도 시계열은 움직이지 않는다** |
| `VLM_HEALTH_FAIL_THRESHOLD` / `VLM_HEALTH_RECOVER_THRESHOLD` | · | **AI 장비 상태점검 임계(시계열 축)** — 각 기본 3. 위와 같은 뜻이고 자리만 다르다. ⚠ **이 축은 오판의 대가가 크다** — 고를 장비가 하나도 없으면 위탁이 **폴백 없이 거부**되므로 멀쩡한 벤더를 내리면 그 계통이 통째로 멈춘다. 그래서 판정도 추론보다 관대하다(오류 응답·해석 못 하는 본문은 살아 있는 것으로 본다 — 벤더 정책은 우리가 고칠 수 없다). 장비를 등록하지 않았으면 아무것도 하지 않는다 |
| `AI_SERVER_POLL_INTERVAL_SECONDS` | · | **상태점검 주기(추론 축)** — 기본 5초. ★시계열 축과 **자리가 갈려 있어** 여기를 바꿔도 시계열 주기는 움직이지 않는다. ⚠ 줄일 때는 **한 번의 점검 상한 × 장비 수**가 주기를 넘지 않는지 먼저 따진다(넘으면 틱이 밀린다) |
| `VLM_HEALTH_POLL_INTERVAL_SECONDS` | · | **상태점검 주기(시계열 축)** — 기본 **10초**로 추론보다 **길다**. 우리 서버는 우리가 부담을 감당하지만 **외부 벤더에게는 우리가 보내는 만큼이 그대로 비용**이기 때문이다. ⚠ **대가** — 주기가 길수록 **죽은 벤더가 목록에 남는 시간**이 길어진다(연속 실패 임계와 곱해진다). 그 구간의 위탁은 죽은 주소로 나갔다 실패한다. ⚠ 이 기본값은 **벤더 규격의 호출 빈도 제한을 확인하지 못한 상태**에서 정한 값이다 — 제한이 확인되면 그 값에 맞춘다 |
| `QUARTZ_THREAD_COUNT` | · | **배치 스케줄러 동시 처리 여력** — 기본 3. 주기 작업들이 나눠 쓰는 **공유 자원**이다. 지금 반복 작업은 **여덟**(상태점검 둘 · 비식별 폴링 · 배치 파이프라인 · 관제 인입 스캔 · 배치 재시도 · 산출 미결 스윕 · 산출 실패 회수)이고 **60초의 배수마다 다섯이 동시에 발화**한다. ⚠ 모자라면 밀린 틱은 **몰아서 발화하지 않고 건너뛴다** — **죽은 장비 배제가 그만큼 늦어지고 오류로 드러나지 않는다.** 「배치가 밀린다」를 진단할 때 먼저 볼 값이다. ★ 기본값 3 은 **반복 작업이 하나뿐이던 시절**에 정해져 갱신되지 않은 값이다 — 올릴 때는 **DB 커넥션 여력과 함께** 판단한다(작업 하나가 도는 동안 커넥션을 하나 잡는다) |
| `CORS_ALLOWED_ORIGINS` | · | 동일 출처면 비움. 다른 도메인 호출 시 allowlist. ★**빈 값이 지금은 정답이지만 언제 틀려지는지 알아 둘 것** — 저작도구는 화면과 서버를 **같은 출처**로 배포해 교차 출처 검사에 걸릴 요청이 애초에 없다(그래서 비운다). 그런데 **포털이 저작도구 화면을 자기 화면 안에서 실행하는 임베딩(`INT-013`)이 들어오면** 스크립트가 **포털 쪽 출처**에서 돌아 우리 서버가 다른 출처가 된다 — 그때 이 값에 **포털 주소를 넣지 않으면 포털 안의 저작도구 화면이 통째로 403** 이다. ⚠ 임베딩을 넣는 사람은 이 값을 의심할 이유가 없다(그때까지 한 번도 문제가 되지 않았으므로). **임베딩 착수 시 이 줄을 먼저 볼 것** |
| `FFMPEG_BIN`/`FFPROBE_BIN`/`FFMPEG_THREADS` | · | 기본 `ffmpeg`/`ffprobe`/2 |
| `BATCH_ENABLED`/`BATCH_INTERVAL_SEC` | · | 기본 true/60 |
| `TRAINING_SCAN_ENABLED` | ★ | **인입 폴링**(`LS_DATA_INGEST` 픽업 → `LS_DATA_RAW` 적재). 기본 true — **반드시 켜 둘 것**. 적재 주체 반전 이후 관제 인입분과 관리 화면 자체 업로드분이 **모두** 이 잡을 통해서만 적재된다(구 안내 "공유 DB 없으면 false 권장"은 폐기). false 면 업로드는 200 을 받고도 인입 행이 영원히 `PENDING` 에 머물러 영상이 목록에 나타나지 않는다(기동 시 ERROR 로그 + 업로드 완료 응답 `X-Ingest-Status: PENDING_SCAN_DISABLED`) |
| `JAVA_OPTS` | · | 기본 `-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Duser.timezone=Asia/Seoul`. ⚠ **베어메탈 토글 전용 키다** — WAR 형상에서는 `application.properties` 에 적어도 아무 효과가 없다(JVM 기동 옵션이라 스프링이 읽지 않는다). WAS 의 `CATALINA_OPTS` 로 옮긴다(예시 `config/was/setenv.sh.example`). 같은 WAS 에 다른 애플리케이션이 있으면 `MaxRAMPercentage` 를 그대로 옮기기 전에 영향을 확인할 것 |

> ⚠ **`{STORAGE_RAW_PATH}/data/upload/v2` 는 저작도구 내부 업로드 전용이다 — 관제가 이 디렉터리에 클립을 놓지 말 것.**
> 저작도구는 관제가 INSERT 한 인입 행(`LS_DATA_INGEST`)과 자기가 만든 인입 행을 **`RAW_FILE_PATH_NM` 의 부모 디렉터리가 이 경로인가**로만 구분한다(관제 행은 관제 NAS 경로를 가리킨다). 이 판별자는 위 운영 규약에만 기대며 코드로 강제되지 않는데, 소비자가 **되살리기**(취소한 클립 ID 재사용)와 **업로드 완료 시 기술메타 back-fill** 둘이라, 관제가 이 디렉터리에 클립을 놓으면 그 관제 인입 행이 저작도구의 쓰기 대상이 될 수 있다(관제 수신 원장 변조 — CWE-915). 클립은 반드시 이 디렉터리 **밖**에 둘 것.
>
> ⚠ **`STORAGE_RAW_PATH`/`STORAGE_DEIDENTIFIED_PATH` 변경 시 — 형상에 따라 할 일이 다르다.**
> **베어메탈 토글**에서는 이 경로가 systemd 유닛의 `ReadWritePaths`(`ProtectSystem=full` 하 쓰기 허용
> 목록)에도 박히므로, 설정만 바꾸면 쓰기가 차단된다. **반드시 재설치(또는 유닛 갱신) 후 재로드**:
> ```
> sudo INSTALL_BACKEND_SYSTEMD_UNIT=1 STORAGE_RAW_PATH=/새경로 ./scripts/install.sh   # 유닛 ReadWritePaths 재치환
> sudo systemctl daemon-reload && sudo systemctl restart klid-backend
> ```
> **WAR 형상에는 그 유닛이 없으므로 `ReadWritePaths` 제약도 없다** — `application.properties` 를 고치고
> WAS 를 재기동하면 된다. 대신 **WAS 프로세스를 돌리는 OS 계정이 새 경로에 쓸 수 있어야 한다**
> (베어메탈은 `klid` 계정이지만 WAS 계정은 다를 수 있다 — 이쪽이 이 형상의 실제 실패 지점이다).
> ```
> sudo -u <WAS 실행 계정> test -w /새경로 && echo OK || echo '쓰기 불가 — 소유권/권한을 조정할 것'
> source /etc/klid/was.env
> was_restart
> ```
> 또한 NAS 를 해당 경로로 **미리 마운트**해야 한다(설치는 부재해도 warn 후 계속되나, 서비스가 영상을 못 쓴다). DB 에 저장된 절대경로가 이 베이스로 시작해야 서빙된다(startsWith 가드).
>
> **저장소 루트 4종(`STORAGE_RAW_PATH`·`STORAGE_DEIDENTIFIED_PATH`·`STORAGE_RAW_MOUNT_ROOTS`·`STORAGE_EXTERNAL_READ_ROOTS`)은 심링크여도 된다** (권고는 실경로).
>
> ✅ **구 규약 폐기 (2026-09-07 · `ADR-065`)** — *"심링크가 아닌 실경로로 지정한다 … 판정기를 고치는
> 대신 **운영 규약으로 고정**한 항목이다"* 는 **더 이상 사실이 아니다.** **판정기가 허용 루트의 표기와
> 그 루트가 실제로 가리키는 자리를 둘 다 허용 범위의 시작점으로 인정**하도록 고쳐졌다.
> **이 문장을 근거로 판정기를 되돌리지 말 것.**
>
> ⚠ **그 규약이 성립하지 않았던 이유** — 규약대로 루트를 실경로로 바꾸면, 이미 원장에 적재된 **표기 기준
> 절대경로**들이 같은 판정 단계에서 거부되어 산출물 쓰기 축이 통째로 막힌다. 한쪽을 맞추면 다른 쪽이
> 깨지므로 규약만으로는 닫히지 않는 결함이었다.
>
> **아래 두 증상은 `ADR-065` 이전 형상의 것이다** — 지금은 나지 않는다. 낡은 배포본을 진단할 때만 참고한다.
> - 「상위로」가 **항상 비활성**된다(응답의 `parent` 가 늘 비어서 돌아온다).
> - 탐색이 돌려준 위치를 **그대로 입력칸에 넣으면 400**(`허용된 저장소 범위 밖의 경로입니다`) —
>   화면에서 고른 폴더가 곧바로 거부되는 형태라, 설정 문제가 아니라 기능 고장으로 보인다.
>
> ⚠ **넓어진 것은 시작점을 읽는 방식뿐이다** — 상위로 거슬러 올라가는 표기, 허용 범위 밖을 가리키는
> 위치, **허용 범위 안의 이름이라도 실제로는 범위 밖을 가리키는 바로가기**는 그대로 거부한다.
>
> **권고(필수 아님)**: 진단을 단순하게 하려면 실경로로 적어 두는 편이 낫다.
> `readlink -f $STORAGE_RAW_PATH` 로 실경로를 확인할 수 있다.

---

## B. 외부 연동 경로 (★ 비식별은 KPST 동거 연동으로 확정)

backend 는 외부 시스템과 연동한다. **비식별(KPST)은 폐쇄망 동거 연동이 확정 정책**이고,
나머지(관제통지/VLM/증강)는 온프렘에 외부가 없으면 토글로 끈다.

| 외부 시스템 | 토글(env) | 설정 |
|-------------|-----------|------|
| **KPST 비식별(폴링)** | `KPST_DEID_ENABLED` | **항상 true(확정)** + base-url(+https면 CA). 끄거나 mock 우회 미지원. 단 `DEIDENTIFY_EXCLUDED_SRC_TYPES`(기본 `GENERATED`)에 든 출처유형은 위탁 없이 원본 복사로 완료(아래 표) |
| 관제 outbound 통지 | `CONTROL_NOTIFY_ENABLED` | 외부 있으면 `true` + `CONTROL_NOTIFY_URL`, 없으면 `false`(기본) |
| 관제 계정 창구(세션 연장·로그아웃 중계) | (토글 없음 — 통지 토글과 무관) | 관제 채널 배포본은 `CONTROL_ACCOUNT_URL` **필수**. 비우면 세션 연장이 동작하지 않는다. 포털 채널 배포본은 이 창구가 닫혀 있어 비워 둔다 |
| 외부 VLM 시계열 | `VLM_SERVICE_URL` | 외부 있으면 실제 주소 + `VLM_SERVICE_TOKEN`, 없으면 **빈 값**(기본). 활성/비활성 토글은 폐지됐다 — 비우면 기동은 정상이고 위탁만 실패하므로, 연동 전 구간에는 시계열 묶음을 화면에서 스킵한다 |
| 외부 증강 | (콜백 수신, HMAC 시크릿만) | 콜백 안 받아도 HMAC 시크릿만 채워 부팅 통과 |

### ★ 비식별(KPST) — 동거 설치·연동 (확정)

비식별은 파이프라인 **선두 필수 단계**다. 운영 정책은 **① KPST 비식별 서버를 폐쇄망에 함께
설치하고 backend 가 폴링으로 연동**하는 것으로 확정됐다.

- **전제**: KPST 비식별 서버 자체는 **외부 시스템 — 본 설치 패키지에 포함되지 않는다.**
  고객이 폐쇄망에 KPST 를 별도 설치하고, 본 패키지는 backend ↔ KPST **연동 설정·절차만** 제공한다.
  (01-prerequisites.md "대상 서버 요구사항"에 KPST 접근 가능 전제가 추가돼 있다.)

- **연동 env**:

  | 변수 | 의미 / 기본 |
  |------|-------------|
  | `KPST_DEID_ENABLED` | **항상 `true`**(기본). 폴링 위탁 단일 경로 |
  | `DEIDENTIFY_EXCLUDED_SRC_TYPES` | 기본 **`GENERATED`**. 쉼표 구분 복수. 여기 든 출처유형(`LS_DATA_RAW.SRC_TYPE`) 영상은 **KPST 에 위탁하지 않고 원본을 비식별 영상 자리에 복사**해 비식별 단계를 끝낸다 — 이력은 `LS_DEIDENT_PROC_LOG` 성공 행 + 요청종류 `EXCLUDED`(비식별 제외), 이후 마킹·프레임 추출·관제 조회는 일반 영상과 같다. ⚠ **원천 CCTV 출처유형 `ORIGINAL` · `RELAY` · `IMPORTED` 를 넣지 말 것** — 그 유형 전체가 마스킹 없이 흐른다. **기동 시 값 검사는 하지 않으며** 적용 목록은 기동 로그 INFO 한 줄로만 남는다(설치 후 로그로 확인). 비우면 모든 영상 위탁(안전측). 판정은 영상마다 처리 시점 1회·**소급 없음**. **관리자 화면 설정이 아니다**(설정 저장 API 도 이 키를 거부) — 이 파일에서 바꾸고 재기동. `KPST_DEID_ENABLED` 와 무관하게 동작. 잘못 인입된 영상은 **검수 중 비식별 누락 신고 → 외부 재비식별**로 회수한다(승인 이후는 신고 불가 — 기존 정책) |
  | `KPST_DEID_BASE_URL` | 동거 KPST 주소. `https://IP:PORT`(CA 필수) **또는** `http://IP:PORT`(격리망 평문, CA 불요). 본 패키지 템플릿 기본값 `https://127.0.0.1:9201`(Spring 코드 기본은 `localhost`이나 `env.template`을 진실원으로 봄) → 실주소로 교체 |
  | `KPST_DEID_CA_CERT_PATH` | **https 일 때만 필수**. KPST 사설 CA(ca.crt) 경로. https 인데 비었거나 못 읽으면 **위탁·산출이 거부**된다(CWE-295 — 검증할 수 없으면 보내지 않는다). ⚠ **구 서술 폐기(2026-09-03)**: *"부팅 fail-closed"* 는 사실이 아니다 — 설정 한 줄로 앱 전체가 멈추지 않도록 **막는 자리를 기동 → 전송 시점으로 옮겼다**(막는 규칙·강도는 그대로, 우회 없음). 기동 시 ERROR 로그가 남는다. http 면 비워둔다 |
  | `KPST_DEID_CREATOR_ID` | 기본 `authoring`. `/project` 호출 기본값 |
  | `KPST_DEID_REQ_USER_ID` | 기본 `authoring`. `/retrieve_progress` 호출 기본값 |
  | ~~`KPST_DEID_EXPORT_PATH_BASE`~~ | **제거됨(2026-09-09)** — 읽는 코드가 0건이라 무엇을 넣든 동작이 같았다. 비식별 결과가 쓰이는 자리(KPST 에 넘기는 `export_path`)는 설정이 아니라 **원본 경로에서 도출**한다 — co-locate 기본 `dirname(원본영상)/{rawSn}/deid/`, 롤백 전략 `{STORAGE_DEIDENTIFIED_PATH}/videos/{rawSn}/`. 그 자리를 가두는 것은 `STORAGE_RAW_MOUNT_ROOTS` 다. **파일명은 KPST 가 정한다**(실측 `{원본stem}-mask{확장자}`) — 조합하지 말고 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 을 읽을 것 |
  | `KPST_DEID_POLL_INTERVAL_SEC` | 기본 30. 폴링 주기(초) |
  | `KPST_DEID_POLL_MAX_ATTEMPTS` | 기본 240. 시도 횟수 타임아웃 |
  | `KPST_DEID_POLL_TIMEOUT_MINUTES` | 기본 180. 경과 시간 타임아웃(분) |
  | `DEIDENTIFY_API_URL` | 기본 `http://localhost:9200`. 실 비식별 호출엔 안 쓰이고 **헬스 인디케이터가 핑**하는 주소 — KPST 헬스 엔드포인트 또는 무해한 기본값으로 둔다 |

  > **https / http 분기**: base-url 스키마로 전송 방식이 자동 분기된다(KpstWebClientConfig).
  > `https://` → `KPST_DEID_CA_CERT_PATH` **필수**(미설정·읽기실패 시 부팅 실패). `http://`(격리망 평문) → CA **생략**.

- **⚠ 비식별 없이 부팅(mock/disable)은 prd 비권장·미지원**: prd 에는 mock/disable 우회 경로가 없다.
  `mock-mode=true` 자족 비식별은 application-local.yml 전용이고 `local` 프로파일은 `LocalProfileGuard`
  가 운영 호스트에서 거부한다. `KPST_DEID_ENABLED=false` 이고 mock 도 아니면 `DeidentifyStep` 이
  설정오류로 거부한다. **비식별을 끄면 PII 노출 + 마킹/프레임추출 차단**이므로 KPST 동거 연동을 반드시 갖춘다.

- **출처유형 비식별 제외는 우회가 아니라 대상 판정 예외다 (2026-09-14 확정)**: `DEIDENTIFY_EXCLUDED_SRC_TYPES`
  에 든 출처유형(기본 생성형 영상 `GENERATED`)은 실제 인물이 없어 KPST 위탁 없이 원본 복사로 비식별 단계를
  완료한다. 비식별 단계 자체를 건너뛰는 것이 아니며(비식별 여부 `Y` · 마킹 대기로 같은 흐름) 그 밖의 출처유형은
  여전히 KPST 연동이 필수다. 관제 조회 뷰의 비식별 여부 `Y` 가 제외 영상에도 서지만 관제는 비식별 작업을 하지 않아
  영향이 없다(2026-09-14 관제 회신 — 관제 수정 사항 없음).

- **미연동 시 증상·진단**: 영상이 비식별 실패(`DE_IDENT_YN='F'`) 상태로 남고, 비식별 미완료라 마킹
  스트리밍/프레임추출이 차단된다. 부팅 거부·연결 실패 진단은
  [06-troubleshooting.md "비식별 설정오류 / KPST 연동"](06-troubleshooting.md) 참고.

---

## C. ai-server.env 항목표

출처: `ai-server/app/config.py`. ⚠ **전부 거기서 오는 것은 아니다** — `AI_BIND_HOST` 는
systemd 유닛(`config/systemd/klid-ai-server.service`)이 `uvicorn --host` 로 읽고,
`HF_HOME`·`HF_HUB_OFFLINE`·`TRANSFORMERS_OFFLINE` 은 HuggingFace 라이브러리가 직접 읽는다.
셋 다 `config.py` 에는 없으므로 그 파일만 보고 "없는 설정"이라 판단하지 말 것.

| 변수 | 필수 | 의미 / 기본 |
|------|:----:|-------------|
| `AI_BIND_HOST` | ★ | 기본 `127.0.0.1`. systemd 유닛의 uvicorn `--host` 로 들어간다. **2대 구성이면 반드시 서버 B 의 내부망 IP(또는 `0.0.0.0`)로 바꾸고 방화벽 9300/tcp 을 서버 A 에서만 열어야 한다** — loopback 이면 서버 A 가 원격에서 닿지 못한다(A 절) |
| `AI_MOCK_MODE` | · | 기본 false(실제 추론). true=고정 mock. ⚠ `ENV` 가 `stg` 또는 `prd` 일 때 true 면 **기동이 실패한다**(`app/startup_guard.py`) |
| `ENV` | ★ | 배포 환경 표식(`local` / `dev` / `stg` / `prd`). backend 의 `ENV` 와 같은 신호이며 위험한 설정 조합을 기동 시점에 차단하는 fail-closed 가드의 판정 축이다. 운영 설치는 `prd` |
| `AI_DEVICE` | ★ | `cpu` — **이번 반입이 CPU 전용**이기 때문이다(torch CPU 휠 + onnxruntime CPU). ⚠ 구 서술 폐기(2026-08-30): *"`cpu` (GPU 없음)"* — **장비에는 GPU 가 있을 수 있다.** CPU 인 것은 장비가 아니라 반입한 휠이라, 여기만 `cuda` 로 바꾸면 아무 일도 안 일어나거나 조용히 CPU 로 돈다 → [11-gpu-migration.md](11-gpu-migration.md) |
| `YOLOX_WEIGHTS_PATH` | · | 기본 `/opt/klid/ai/weights/yolox_s.onnx` (탐지 YOLOX 단일 백엔드, ONNX Runtime) |
| `SAM2_MODEL_ID` | · | 기본 `facebook/sam2-hiera-tiny`(SAM2 사용 시 HF 캐시 필요) |
| `VLM_MODEL_NAME` | · | 기본 `openai/clip-vit-base-patch32`. ⚠ **이 이름의 모델을 적재하지 않는다** — 시계열 추론 본체는 외부 서비스이고 이 서버의 vlm 라우터는 어댑터라, 로더는 목 응답으로 떨어지며 이 값을 **로그에만** 남긴다. "실제로 쓰는 모델"로 읽지 말 것 |
| `HF_HOME` | · | 오프라인 HF 캐시(기본 `/opt/klid/ai/.hf-cache`) |
| `HF_HUB_OFFLINE`/`TRANSFORMERS_OFFLINE` | · | 폐쇄망 권장 1 (SAM2 캐시 완비 시) |
| `MAX_IMAGE_SIZE_MB` | · | 기본 10 (1~100) |
| `CORS_ALLOW_ORIGINS` | · | 기본 `http://127.0.0.1:8080`(env.template, 동일 호스트 backend). 다른 호스트 분리 시 해당 주소로 조정 |

> 포트(9300)는 코드 고정(uvicorn `--port 9300`). config.py 에 PORT 설정 없음.

---

## C-1. frontend 런타임 설정 (`/etc/klid/frontend.env`) — ★ 설치 차단 주의

**정본은 `/etc/klid/frontend.env` 다.** 백엔드가 DB 접속정보를 산출물이 아니라 `/etc/klid` 에서
읽는 것과 같은 관례이고, 값을 바꾸는 데 **재빌드도 재설치도 필요 없다**.

> ⚠ **구 서술 폐기(2026-08-30)** — *"frontend 는 env 파일을 런타임에 읽지 않는다. Vite 가 빌드
> 시점에 정적 치환하므로 대상 서버에서 바꿀 수 없다"*. 그 형상에서는 **환경마다 다시 빌드**해야
> 했고, 폐쇄망 반입(빌드머신이 고객 환경의 실주소를 모른 채 매체를 만든다)에서는 배포 가능한
> 산출물 자체를 만들 수 없었다 — 실제로 예시 주소가 구워진 `dist` 가 반입 대상으로 놓여 있었다.
> 지금 빌드 산출물은 **환경 무관**이고, 아래 값들은 설치 시점에 주입된다.

### 값을 바꾸는 절차 (한 줄)

```bash
sudo vi /etc/klid/frontend.env          # 1) 정본 편집
sudo /opt/klid/bin/klid-frontend-config # 2) 반영 (웹 서버 재기동 불필요)
```

2)가 정본을 읽어 문서 루트에 `klid-config.js` 를 **생성**한다. 브라우저는 그 파일을 번들보다 먼저
읽는다. 웹 서버 설정이 이 파일에 `Cache-Control: no-store` 를 걸어 두므로 **새로고침이면 즉시 반영**된다.

- `klid-config.js` 는 **생성물이다 — 직접 고치지 말 것.** 다음 생성에서 덮인다.
- ★★ **이 파일의 내용은 브라우저로 그대로 내려간다. 비밀값을 넣지 마라.** 생성기는 아래 표의
  키만 내보내지만(allowlist), 그것이 "여기에 비밀값을 둬도 된다"는 뜻은 아니다. DB 비밀번호·JWT
  시크릿·웹훅 서명값은 `application.properties` / `backend.env` 에 둔다.
- 정본에 표에 없는 키를 적으면 **내보내지 않고 무시**하며 키 이름만 로그에 남긴다(값은 찍지 않는다).

| 변수 | 필수 | 의미 / 기본 |
|------|:----:|-------------|
| `VITE_CONTROL_LOGIN_URL` | ★ | **관제서버 로그인 페이지 절대 URL.** 예 `https://control.example.local/login` |
| `VITE_PORTAL_LOGIN_URL` | 향 의존 | **포털 로그인 페이지 절대 URL.** 예 `https://portal.example.local/login`. ⚠ **관제 연동 배포에서는 쓰이지 않는다** — `KLID_DEPLOY_FLAVOR=control` 이면 요구하지 않는다(D-4 절) |
| `KLID_DEPLOY_FLAVOR` | ★ | **배포 향 선언** — `control`(관제 연동) \| `portal`(포털 연동). 어느 상위 로그인 주소가 필수인지를 정한다. 비우면 둘 다 요구, 그 밖의 값이면 생성기가 즉시 실패. **브라우저로 내려가지 않는다**(설치 시점 판정 전용) |
| `VITE_API_BASE_URL` | · | 기본 `/api/v1` (프록시가 backend 로 넘김) |
| `VITE_TOKEN_INGRESS` | · | 기본 `localStorage` (토큰 인계 채널). 값: `url`\|`cookie`\|`localStorage`\|`both`\|`all`. **기본값을 바꾸지 말 것** — `url`/`both`/`all` 은 JWT 를 URL 쿼리로 받는 채널을 열어 접근 로그·리퍼러 헤더·브라우저 히스토리에 토큰이 잔존한다(CWE-598). 관제/포털은 동일 origin 브라우저 저장소로 인계한다 |
| `VITE_DEV_UPLOAD_ENABLED` | · | 기본 true. **수동 업로드(`/admin/uploads`)는 운영 상시 기능**이며 backend 도 기본 ON 이다(A 절 `DEV_UPLOAD_ENABLED`). 끄려면 **이 값과 backend 토글을 함께** — I 절 |
| `VITE_DEV_LOGIN_ENABLED` | · | 기본 true. **backend 는 기본 OFF** 라 `/v1/dev/tokens` 는 404 다. **브링업이 끝나면 `false` 로 바꾸고 위 반영 명령을 다시 실행할 것** — H 절 |

- **두 로그인 URL 은 `http://` 또는 `https://` 스킴을 포함한 완전한 URL**이어야 한다. 스킴이
  없으면 브라우저가 상대경로로 해석해 저작도구 자기 자신으로 되돌아온다.
- **미설정이면 설치가 중단된다(fail-closed).** 저작도구는 자체 로그인 UI 가 없어 토큰 없음·만료
  (401) 시 상위 시스템으로 redirect 하는 것이 유일한 복귀 경로다. 값이 비면 화면은 **"로그인
  주소가 설정되지 않았습니다" + 해당 설정 항목 이름**을 띄운다(막다른 화면이 아니다).

  | 시점 | 가드 |
  |------|------|
  | 설치 `scripts/install/14-install-frontend.sh` | 생성기 실패 → `die` (설치 중단) |
  | 반영 `/opt/klid/bin/klid-frontend-config` | 필수 값 없음·스킴 누락 → 생성 거부(기존 생성물 보존) |

  > ⚠ 이 가드는 **예전에 빌드에 있던 것**(`require_upstream_login_urls`)을 옮겨 온 것이다.
  > 빌드 스크립트에 되살리지 말 것 — 되살리면 산출물이 다시 환경 종속이 된다.

### 첫 설치에서 한 번에 채우기

설치 시 환경변수로 주면 정본이 처음부터 완성된 상태로 놓인다(종전 빌드 인자와 같은 사용감).

```bash
sudo VITE_CONTROL_LOGIN_URL=https://control.example.local/login \
     VITE_PORTAL_LOGIN_URL=https://portal.example.local/login \
     ./scripts/install.sh --role=app
```

주지 않으면 정본이 **빈 값으로** 놓이고 그 자리에서 설치가 멈춘다. 값을 채우고
`sudo /opt/klid/bin/klid-frontend-config` 를 실행한 뒤 `install.sh` 를 다시 돌리면 이어진다.
**재설치는 기존 정본을 덮어쓰지 않는다**(현장값 보존 — backend 설정과 같은 규칙).

### 빌드 시점 변수는 무엇이 되었나 (폴백)

같은 이름의 `VITE_*` 를 빌드에 주면 **런타임 설정이 없을 때의 폴백**으로만 쓰인다. 해석 순서는
**런타임 → 빌드 → 없음**이다. 로컬 개발(`npm run dev`)과 컨테이너 이미지가 종전처럼 동작하는
것이 이 폴백 덕분이다. 빌드에 어떤 값이 들어갔는지는 산출물 옆
`artifacts/frontend/BUILD-INFO.txt` 에 기록된다(예전에는 로그로만 찍혀 `dist` 만 보고는 알 수 없었다).

---

## D. DB 준비

### PostgreSQL 엔진 — 현장 제공 (설치 토글 없음)

**데이터베이스는 현장이 제공한다** — 우리가 설치하지 않으므로 고를 토글이 없다(2026-09-05 확정).
`CONTROL_DB_*` 가 그 데이터베이스를 가리키게 두는 것이 전부다.

⚠ **구 절 폐기(2026-09-05)** — *"PostgreSQL 엔진 — 번들 vs 외부(설치 토글 `USE_BUNDLED_POSTGRES`)"*.
그 토글과 실행 주체(`10-install-postgresql.sh`)가 **모두 제거**됐다. **되살리지 말 것.**
`pg_hba.conf`·`listen_addresses`·`PG_HBA_EXTRA_CIDR`·`PG_LISTEN_ADDRESSES` 는 전부
그 데이터베이스 운영 주체(현장)의 소관이다.

### ★ 스키마는 `db/schema.sql` 1회 로드로 만든다 — 온프렘은 Flyway 를 쓰지 않는다

- **테이블/스키마를 만드는 경로는 하나뿐이다** — `db/schema.sql`(전체 통합 DDL)을 빈 control DB 에
  **1회 로드**한다. 앱은 마이그레이션을 돌리지 않는다
  (`spring.flyway.enabled=false` — 설정 템플릿이 그렇게 배포된다). **의도적 결정**이며, 전체 스키마
  SQL 을 따로 만들어 둔 이유가 그것이다(반입 명세 `DEPLOY-001`).
- **앱 코드 자체의 기본값은 켬(`true`)이다.** 꺼진 상태는 매체의 설정 템플릿이 만든다 —
  **그 한 줄을 지우면 되살아나 켜진다**(형상별 키 표기는 위 「키 이름 변환 규칙」).
- `db/schema.sql` 은 `LS_*` 62개 · `QRTZ_*` 11개 · 뷰 4개 + 시드 66행이다. **`MNG_*` 는 들어 있지
  않고** `CREATE TABLE IF NOT EXISTS` 도 쓰지 않는다(빈 스키마 전제). 구 서술 "Flyway V2 가
  `MNG_*` 까지 `IF NOT EXISTS` 로 자동 생성한다" 는 **폐기** — `MNG_*` 공유 테이블 자체가 이미 제거됐다.
- **현장 담당이 준비할 것은 빈 데이터베이스 1개(control, 예 `klid_system`) + 앱 계정·비밀번호**뿐이다.
  ⚠ 구 서술 폐기(2026-09-05) — *"DBA(또는 `15-init-db.sh`)가 준비할 것은 빈 DB 2개 …
  control / portal"*. 포털 데이터소스는 2026-08-31 에 철거됐고 `15-init-db.sh` 도 제거됐다.
  **`klid_at` 스키마는 미리 만들지 않아도 된다** — `db/schema.sql` 이 `CREATE SCHEMA` 를 포함한다.
  앱 계정이 그 데이터베이스의 소유자면 충분하다.
- **★ 적재는 조건부다 — 현장 담당 선적용 우선 + 우리가 채움**(2026-09-05 확정).
  `16-load-schema.sh` 가 대상 스키마를 보고 갈린다: **비었으면 적재**하고, **이미 있고 개수가
  기대와 같으면 건너뛰며**, **기대와 다르면 덮어쓰지 않고 멈춘다**. 어느 쪽이 먼저 했든 결과가 같다.
  여러 대가 같은 데이터베이스 한 벌을 보므로 **첫 대에서만** 적재한다.
  ⚠ 구 서술 폐기(2026-09-05) — *"로드는 자동이 아니다. `SCHEMA_LOAD_RUN=1` 이고 `psql` 이 있을 때만
  실제로 넣고 평시엔 수동 절차만 출력한다"*. 기본이 「안 함」이면 아무도 적재하지 않은 채 넘어간다.
  이제 기본이 적재이고 빠져나갈 문은 `SKIP_SCHEMA_LOAD=1` 하나다.
- ⚠⚠ **★ 그래도 기동은 성공한다 — `ddl-auto=validate` 가 대신 막아 주지 않는다.**
  `application.yml` 에 `validate` 가 선언돼 있지만 이 저장소에서는 **실동작하지 않는다**:
  `JpaBuilderConfig` 가 `EntityManagerFactory` 를 직접 만드는데(⚠ 구 서술 *"듀얼 데이터소스라"* 는
  폐기 — 포털 데이터소스는 철거됐고 원인은 EMF 를 직접 만드는 것이다), 거기에 넘기는
  것은 `spring.jpa.properties.*` 뿐이라 `spring.jpa.hibernate.ddl-auto` 가 Hibernate 까지
  전달되지 않는다. 그래서 **테이블이 하나도 없어도 기동 로그는 깨끗하고**, 실패는 그 테이블을
  처음 건드리는 요청·배치에서 `relation ... does not exist` 로 나타난다.
  ⇒ **"기동됐으니 됐다"로 넘어가지 말 것.** 로드 여부는 아래 카운트 쿼리로만 판정한다.
  근거·상세는 `09-operations-runbook.md` §2-5-2 「왜 조용히 실패하나」.
  ⚠ 구 서술 폐기(2026-08-30) — *"아무도 넣지 않으면 앱이 `ddl-auto=validate` 에서 기동에 실패한다"*.
  ```bash
  psql -h <HOST> -p <PORT> -U <APP_USER> -d klid_system \
       -c "select count(*) from information_schema.tables where table_schema='klid_at';"
  # 0 이면 로드가 안 된 것이다.
  ```
- **★ 저작도구 객체는 `klid_at` 스키마에 생성된다**(`DB_SCHEMA`, 위 표).
  psql 로 조회할 때는 `klid_at.` 로 한정하거나 `set search_path to klid_at;` 를 먼저 실행한다.
- **기존 DB(저작도구 객체가 `public` 에 있는 DB)는 배포 전에 스키마 이관이 필요**하다 →
  `09-operations-runbook.md` §2-5-1. 이관하지 않으면 앱이 빈 `klid_at` 을 보고 데이터는 `public` 에 남는다
  (오류가 아니라 조용한 분기). `16-load-schema.sh` 는 이 상태를 감지하면 로드를 거부한다.
- ⚠ **구 항목 폐기(2026-09-05)** — *"DB·유저 자동 생성 보조: `15-init-db.sh`"*. 그 스크립트는 제거됐다.
  외부 제공 데이터베이스에 `CREATE DATABASE`/`CREATE ROLE` 을 할 권한이 우리에게 없다 —
  **빈 데이터베이스와 앱 계정 준비는 현장 선행 조건**이다.

### ★ D-4. 배포 향 — 이 매체는 「관제 연동 배포」다

같은 산출물이 **관제 연동 배포**와 **포털 연동 배포** 두 벌로 나가고, 두 배포는
**웹·WAS·DB 가 전부 다른 별개 인프라**에 놓인다. 서로의 DB 에 접근하지 않으며 서로를 직접
호출하지도 않는다 — 검수 완료 자산은 **관제가 중계**한다.

| | 관제 연동 배포 *(이 매체)* | 포털 연동 배포 |
|---|---|---|
| DB | control PostgreSQL(관제지원 소유·이중화) | 포털 PostgreSQL(별도 서버) |
| NAS | 공유 NAS(관제지원 소유) | 포털 NAS |
| ai-server | 있음 | 없음 |

#### 설정에서 할 일은 하나뿐이다

`/etc/klid/frontend.env` 의 **`KLID_DEPLOY_FLAVOR`** — 출고 기본값이 이미 **`control`** 이라
보통은 손댈 것이 없다.

이 값이 정하는 것은 **어느 상위 로그인 주소가 필수인가**다. 관제 연동 배포에 들어오는 사용자는
내부 사용자(관리자·검수자·작업자)뿐이고 **포털 회원은 별도 배포의 임베딩 화면으로 들어가므로**,
`VITE_PORTAL_LOGIN_URL` 은 이 배포에서 **한 번도 쓰이지 않는다.** 선언이 없으면 설치 20 단계가
그 값을 계속 요구해, 쓰이지도 않는 주소를 채워야 설치가 끝난다.

- 값은 `control` 또는 `portal` 뿐이고, 그 밖의 값은 생성기가 **즉시 실패**시킨다(오타 방치 금지).
- **비워 두면 필수 로그인 URL 은 둘 다 요구한다** — 모르는 형상을 느슨하게 통과시키지 않기
  위한 기본값이다. 다만 **화면 산출물은 `control` 을 배치**한다(무엇을 복사할지는 반드시 하나를
  골라야 하므로, 설치 템플릿의 출고 기본값과 같은 쪽으로 떨어진다).
- 이 값은 **브라우저로 내려가지 않는다**(생성물 allowlist 밖, 설치 시점 판정 전용).

#### ★ 이 값은 「어느 화면 산출물을 까느냐」도 가른다 (2026-08-31 구현)

**화면 산출물(dist)은 향마다 따로 만들어 매체에 두 벌 싣는다.** 화면 쪽 채널 값은
**빌드 시점에 굳어** 반대 향의 화면 코드를 산출물에서 **통째로 걷어낸다** — 관제 산출물에는
`/portal` 라우트가 **0건**이고, 포털 산출물에는 내부 화면(`/dashboard`·`/admin/*`·`/manage/*`)이
**0건**이다. 그래서 다른 향으로 만든 dist 에 이 선언만 바꾸면 **머리 영역이 겹치고 화면이 뜨지
않거나, 있어야 할 메뉴가 통째로 없다.**

| 매체 안 | 무엇 |
|---|---|
| `artifacts/frontend/dist/control/` | 관제 연동 배포용 (`npm run build:control`) |
| `artifacts/frontend/dist/portal/` | 포털 연동 배포용 (`npm run build:portal`) |

- **운영자가 할 일**: 첫 설치에서 향을 넘긴다. 관제향은 **아무것도 하지 않아도 된다**(기본값).

  ```bash
  sudo ./scripts/install.sh                              # 관제 연동 배포(기본)
  sudo KLID_DEPLOY_FLAVOR=portal ./scripts/install.sh     # 포털 연동 배포
  ```

- **재설치는 `/etc/klid/frontend.env` 의 선언을 따른다.** 그 파일은 재설치 때 **덮이지 않으므로**,
  이미 향이 적힌 장비는 환경변수를 주지 않아도 같은 향이 다시 깔린다. 반대로 환경변수로 다른
  향을 줘도 **파일이 이긴다**(경고를 찍는다) — 향을 바꾸려면 그 파일의 `KLID_DEPLOY_FLAVOR` 를
  고친 뒤 다시 설치한다. ⚠ **파일만 고치고 `klid-frontend-config` 만 다시 돌리면 화면은 그대로다**
  — 그 명령은 설정 생성물만 다시 쓴다.
- **★ 향에 맞는 dist 가 매체에 없으면 설치가 즉시 실패한다**(fail-closed). 다른 향의 산출물로
  **대신 깔지 않는다** — 잘못 깔면 빌드도 설치도 httpd 도 모두 성공해 **조용히** 어긋나고,
  현장에서 원인 추적이 가장 어려운 형태가 되기 때문이다.
- 지금 깔린 것이 어느 향인지는 위 파일의 선언과 설치 로그(`[frontend] 정적 자산 배치 (배포 향: …)`)로
  확인한다. 매체에 어느 향들이 들어 있는지는 `artifacts/frontend/BUILD-INFO.txt` 의
  `build_flavors=` 줄에 적힌다.

즉 `KLID_DEPLOY_FLAVOR` 가 가르는 것은 **① 어느 화면 산출물을 배치하는가 ② 어느 상위 로그인
주소를 필수로 요구하는가** 둘이다. ⚠ 구 서술 폐기 — *"즉 `KLID_DEPLOY_FLAVOR` 는 **서버 배선만**
가른다"*. 화면 산출물이 향별로 갈리기 전의 서술이며, 지금은 이 값이 배치 대상까지 정한다.

#### 포털 DB 설정은 더 이상 없다

옛 반입물에는 `PORTAL_DB_*` 다섯 키와 `META_REPLICATION_ENABLED`, 그리고 포털 스키마를 넣는
설치 단계가 있었다. **전부 사라졌다** — 저작도구 → 포털 DB 메타 단방향 복제가 폐기되면서
듀얼 데이터소스·복제 워커·발신함이 함께 철거됐기 때문이다.

- 포털향 배포본은 **복제가 아니라 관제가 중계한 자산**으로 자기 DB 를 채운다.
- ⚠ **옛 값을 되살리지 말 것** — 읽는 코드가 없다. 되살리면 설정만 늘고 아무 동작도 하지 않는다.
- 이 철거로 함께 사라진 증상: 포털 DB 가 없으면 **기동이 막히던 것**, 설치가 **중단되던 것**,
  전체 상태 조회가 **DOWN(503)** 이던 것.


## E. 시크릿 생성 치트시트

```bash
openssl rand -base64 48          # JWT_SECRET / STREAM_SIGN_SECRET
openssl rand -hex 32             # WEBHOOK_HMAC_SECRET_*
htpasswd -bnBC 12 "" '평문' | tr -d ':\n'   # ADMIN_CLAIM_PASSWORD_HASH (BCrypt)
```

## F. 프록시 설정

- 기본: `config/frontend/httpd-klid.conf.template` → 설치 시 `/etc/httpd/conf.d/klid-frontend.conf`. `:80` SPA + `/api/*`→127.0.0.1:8080(`BACKEND_ORIGIN` 으로 변경).
  폐쇄망이라 TLS 는 걸지 않는다. 외부 노출 시 사내 TLS 종단을 앞단에.
  ★ 프록시 응답 버퍼링을 끈다(`flushpackets=on`) — 기본값이면 스트리밍 응답이 클라이언트에 아무것도 가지 않다가 끊긴다.
- 대안: `USE_NGINX=1` 설치 시 `config/frontend/nginx.conf.template`(proxy_pass 127.0.0.1:8080) 배치.
- ★ **앞단(httpd)과 WAS 는 한도를 각자 갖는다 — 둘 중 작은 쪽이 실제 상한**이다. 업로드 본문 한도·
  프록시 타임아웃을 한쪽만 키우면 다른 쪽에서 잘린다. WAS 쪽 값과 예시는
  [10-was-settings.md](10-was-settings.md) · `config/was/` 를 함께 본다.

---

## G. 최초 관리자(ADMIN) 만들기 (운영 부트스트랩)

신규 설치 직후에도, 이미 운영 중인 시스템을 올린 직후에도 저작도구에 **관리자가 한 명도 없다.** 최초
**관리자(ADMIN)** 는 아래 **정규 경로**로 만든다 — dev 토글(H 절)은 이 용도로 쓰지 않는다.

> ⚠ **「역할이 없는 사람」을 찾지 말 것.** 신규 설치에서는 누구든 진입하는 순간 작업자로 자동
> 등록되고, 기존 시스템에는 애초에 검수자·작업자만 있다. **이미 역할을 가진 사람이 그대로 이
> 절차를 밟으면 된다** — 창이 열려 있는 동안에는 역할 보유 여부를 보지 않는다.

> ⚠ **2026-08-28 변경(`ADR-055`)** — 이 절차가 만드는 것은 **검수자가 아니라 관리자**다. 그리고 이
> 창구는 **관리자가 0명일 때만 열린다** — 관리자가 한 명이라도 생기면 닫히고, 그 뒤에는 관리자
> 패스워드를 알아도 역할을 받을 수 없다. 이후 역할은 **관리자가 사용자 관리 화면에서 부여**한다.

> 저작도구는 자체 로그인 UI 가 없다. 사용자는 **관제서버가 발급한 JWT** 를 인계받아 진입하고,
> 그 토큰에 역할이 없으면 화면이 `/role-claim`(권한 부여) 으로 안내한다.

1. **백엔드 설정 정본**(WAR 형상 `/etc/klid/application.properties` · 베어메탈 토글 `/etc/klid/backend.env`)에
   **관리자 공유 패스워드 해시**를 설정하고 재기동한다(E 절 치트시트로 생성).
   ```
   ADMIN_CLAIM_PASSWORD_HASH=$2b$12$....   # BCrypt cost≥12. 평문이면 기동 실패
   ```
   미설정이면 이 API 는 **항상 401** 이라 아무도 관리자가 되지 못한다.
2. 관제서버에 로그인한 뒤 저작도구로 진입한다(관제 토큰 인계).
   > ⚠ **진입만으로 작업자 역할이 붙는다.** 그래도 창이 열려 있으면 이 절차는 그대로 성립하지만,
   > 화면이 「역할 없음」을 조건으로 권한 부여 화면을 띄운다면 **그 화면이 뜨지 않을 수 있다.**
   > 그때는 `/role-claim` 주소로 직접 들어간다.
3. **평문** 관리자 패스워드를 입력 → 제출. 성공하면 **관리자(ADMIN)** 토큰이 즉시 발급되고 사용자
   마스터 행(`LS_ACNT_USER`)도 이때 자동 등록된다(DBA 수동 INSERT 불필요).
   ⚠ **역할을 고르는 화면이 아니다** — 부여 역할은 관리자 고정이며, 요청이 다른 역할을 지정해도
   결과가 바뀌지 않는다.
4. 이후 진입자는 **인증을 통과한 시점에 작업자(WORKER)로 자동 등록**된다. 자가부여를 거치지 않아도
   되며, 관리자가 사용자 관리 화면(`/admin/users`)에서 **관리자·검수자·작업자** 중 하나로 바꾼다.
   - 부수 효과로 **배정이 쉬워진다** — 종전에는 대상자가 먼저 자가부여를 해야 배정 목록에 떴다.

> ### ★ 배포 직후 구간에 주의 (운영 절차)
>
> 이 변경이 나간 직후에는 **관리자가 0명**이다. 기존 검수자는 그대로 검수자로 남지만, 관리 기능
> (사용자 관리 · 연동 서버 주소 · 파일 업로드 · 산출물 가져오기 · 패스워드 교체 · 위험 작업)은
> **최초 관리자가 생길 때까지 아무도 쓰지 못한다.**
>
> **그 사이 자가부여 창구는 열려 있고, 관리자 패스워드를 아는 사람이면 누구나 최초 관리자가 될 수
> 있다.** 창을 누가 먼저 차지하느냐는 경쟁이므로 **배포 직후 운영자가 곧바로 1번 항목을 수행**한다.
> 창은 그 한 번으로 닫힌다.

> ### ★ 마지막 관리자는 내릴 수 없다 — 복구는 저장소 직접 수정뿐
>
> 관리자가 한 명뿐일 때 그 사람의 역할을 내리려는 요청은 **409 로 거부**된다.
> (계정 활성 여부는 관제서버 소유값이라 저작도구에 쓰기 경로가 없다 — 그 축은 여기서 막을 대상 자체가 없다.)
> 관리자가 0명이 되면 위 자가부여 창이 다시 열려 「패스워드를 아는 사람이 관리자」 상태로
> 되돌아가기 때문이다.
>
> 그럼에도 관리자 계정 자체를 잃었다면(퇴사·관제 계정 삭제 등) **복구 경로는 저장소 직접 수정
> 하나뿐이다.** 대상 사용자 번호는 사용자 마스터에서 확인한다.
>
> ```sql
> -- 1) 현재 역할 분포와 대상 사용자 확인
> SELECT r.role_cd, count(*) FROM klid_at.ls_user_role r GROUP BY r.role_cd;
> SELECT u.user_no, u.user_id, u.user_nm, r.role_cd
>   FROM klid_at.ls_acnt_user u
>   LEFT JOIN klid_at.ls_user_role r ON r.user_no = u.user_no
>  WHERE u.user_id = '<대상 계정>';
>
> -- 2) 그 사용자를 관리자로 (행이 없으면 INSERT, 있으면 UPDATE)
> INSERT INTO klid_at.ls_user_role (user_no, role_cd, reg_dt)
> VALUES (<USER_NO>, 'ADMIN', now())
> ON CONFLICT (user_no) DO UPDATE SET role_cd = 'ADMIN', upd_dt = now();
> ```
>
> ⚠ **역할 해석에 캐시가 걸려 있다.** 저장소를 직접 고쳐도 이미 캐시된 판정은 바뀌지 않으므로,
> 반영하려면 **backend 를 재기동**한다(앱 경로로 바꿀 때는 캐시가 자동으로 비워지지만 직접 수정은
> 그 경로를 타지 않는다).
>
> ⚠ **이 SQL 은 최후 수단이다.** 평상시 역할 변경은 반드시 사용자 관리 화면으로 한다 — 화면 경로에는
> 마지막 관리자 보호와 감사 기록이 걸려 있고 직접 수정에는 없다.

> ### ⚠ 보안 — 인지·수용된 잔여 위험
>
> **최초 부트스트랩 1회 동안에는** 관리자 공유 패스워드를 아는 사람이 관리자가 된다. 즉 그 순간만큼은
> **이 패스워드의 관리 수준이 시스템 전체의 권한 경계**다 — 설치 담당자 외에 공유하지 말 것.
> 창이 닫힌 뒤에는 패스워드만으로 역할을 얻을 수 없다.
>
> 패스워드는 사라지지 않는다 — **관리자 페이지 진입 유효창**에 계속 쓰인다. 유효창은 역할을 올리지
> 않고 발급받은 사람에게 묶이므로 **감사 기록이 개인 단위로 남는다.**
>
> ⚠ **구 서술 폐기(2026-08-04)** — *"이 패스워드를 아는 사람은 누구나 REVIEWER 가 된다 / 자가부여를
> WORKER 전용으로 되돌리지 말 것"*. 자가부여는 이제 **관리자 부트스트랩 전용**이며 상시 열려 있지
> 않다. 그때의 「되돌리지 말 것」은 *"좁히면 검수자가 될 길이 없다"* 를 근거로 한 것인데, 지금은
> 관리자가 역할을 부여하므로 그 근거가 성립하지 않는다. **다시 「항상 자가부여」로 되돌리지 말 것.**

함께 유지되는 방어(하나라도 빼면 위 부트스트랩이 안전하지 않다):

| 방어 | 내용 |
|------|------|
| **관리자 0명 게이트** | 관리자가 한 명이라도 있으면 이 창구는 **누구에게도** 열리지 않는다 |
| 시도 횟수 제한 | 계정당 **5회/분** + 엔드포인트 전역 **50회/분**, 초과 시 429. 카운터는 DB 공유 저장소(`LS_AUTHRT_GRANT_ATMPT`)라 **2노드 합산**으로 강제된다 |
| 패스워드 비교 | BCrypt 상수시간 비교(타이밍 관측 차단). 평문은 저장·로그 어디에도 남지 않는다 |
| 대상 게이트 | **INTERNAL 채널**만 허용(포털 채널은 400). ⚠ **역할 보유 여부는 창이 열려 있는 동안 따지지 않는다** — 검수자·작업자도 이 창구로 최초 관리자가 될 수 있다. 그러지 않으면 이미 운영 중인 시스템에서 최초 관리자를 만들 사람이 한 명도 없다(전원 역할 보유자다). 창이 닫힌 뒤에는 **관리자 0명 게이트**가 전원을 거절한다 |
| 채널 격리 | `PORTAL_USER` 는 400 으로 거절 — 포털 계정이 내부 역할로 상승할 수 없다 |

---

## H. 관제서버 미기동 브링업 (개발/임시)

관제서버가 아직 안 떠 **토큰 인입(JWT) 자체가 불가능**한 초기 브링업 단계에서만, dev 토글로 임시
로그인과 테스트 영상 업로드를 켜 파이프라인을 검증한다. 관제서버가 떠 있으면 이 절은 건너뛰고
**G 절**을 쓴다.

> ⚠ **dev 로그인은 인증 없이 임의 role 토큰을 발급한다.** 운영 정상화 후 반드시 `DEV_LOGIN_ENABLED` 를
> 미설정(OFF)으로 원복하고 backend 를 재기동한다. 온프렘 FE 번들은 dev 라우트를 dist 에 포함하되
> 실제 게이팅은 BE 런타임 토글이 결정하므로(BE off 면 `/v1/dev/*` 404), env 원복만으로 차단된다.

1. 백엔드 설정 정본(WAR 형상 `/etc/klid/application.properties` · 베어메탈 토글 `/etc/klid/backend.env`)에
   dev 로그인 토글만 설정 후 재기동:
   ```
   DEV_LOGIN_ENABLED=true
   ```
   > ★ **2026-08-24 변경(CO-008)**: `DEV_UPLOAD_ENABLED` 는 이제 **운영 기본 ON** 인 상시 기능이라
   > 여기서 켤 필요가 없다(I 절). `SPRING_SERVLET_MULTIPART_ENABLED` 도 공통 설정이 이미 `true` 라
   > 지정이 무의미하다 — 구 기재는 사실과 달랐다.
2. 브라우저에서 `/dev/login` 진입 → 임시 토큰 발급(파이프라인 검증에 필요한 역할 선택).
   **이 토큰은 브링업 검증용이며 최초 REVIEWER 를 만드는 정규 절차가 아니다** — 정규 절차는 G 절.
3. `/admin/uploads` 에서 테스트 영상 업로드 → 파이프라인(선두 비식별 → 마킹대기) 진행 확인.
   ⚠ 이 화면은 **관리자 페이지 소속**이라 검수자 역할만으로는 못 들어간다 — `/admin` 에서 관리자
   패스워드 확인을 거쳐야 도달하며, 업로드 **시작**에도 그때 열린 유효창이 실린다(G 절).
4. **운영 정상화 후**: `DEV_LOGIN_ENABLED` 를 백엔드 설정 정본에서 미설정(삭제/주석)하고 재기동
   → `/v1/dev/*` 중 **로그인 경로만** 차단된다. 이후 실제 사용자 권한은 G 절 경로로 부여한다.
   ⚠ 수동 업로드는 이 원복 대상이 **아니다** — 상시 기능이므로 계속 열려 있다(I 절).
5. **화면에서도 지운다(2026-08-30 추가)**: `/etc/klid/frontend.env` 의 `VITE_DEV_LOGIN_ENABLED=false`
   → `sudo /opt/klid/bin/klid-frontend-config`. backend 를 끄면 API 는 404 가 되지만 **화면은 남아**
   운영자가 "왜 안 되나"를 묻게 된다. 예전에는 이 축이 빌드에 굳어 있어 끌 방법이 재빌드뿐이었다.

---

## I. 수동 업로드 (`/admin/uploads`) — 운영 상시 노출

2026-08-24(CO-008)부터 **수동 업로드는 운영에서 기본으로 켜져 있다.** 외부에서 받은 영상을
운영자가 직접 올리는 동선이 실제로 있어 그 입구를 열어 둔 것이다. 화면 표시 명칭은 **「수동 업로드」**다.

| 축 | 값 | 어디서 |
|---|---|---|
| backend 토글 | `DEV_UPLOAD_ENABLED` — **기본 `true`** | `application-prd.yml` 의 `authoring.dev.upload.enabled` |
| frontend 토글 | `VITE_DEV_UPLOAD_ENABLED` — **기본 `true`** | 런타임 설정 `/etc/klid/frontend.env` (C-1 절) |
| 업로드 크기 | 개당 **500MB** / 요청 총량 1100MB | `application-prd.yml` 의 `spring.servlet.multipart` |

- **인가는 REVIEWER 로 그대로 걸린다.** 화면·API 모두 검수자만 접근한다.
- **화면은 관리자 페이지(`/admin/*`) 소속이라 관리자 패스워드 확인을 거쳐야 도달한다.** 검수자
  역할 **위에** 단기 유효창이 가산되며, 그 유효창은 업로드 **시작**에만 실린다(청크 이어보내기·
  취소에는 실리지 않아 대용량 영상이 유효창을 넘겨도 끊기지 않는다).
  ⚠ **서버 창구 경로는 그대로 `POST /api/v1/dev/upload` 다** — 옮겨간 것은 화면 주소뿐이다.
- **dev 로그인은 별개다.** `DEV_LOGIN_ENABLED` 는 여전히 기본 OFF 이고 `/v1/dev/tokens` 는 404 다.
  업로드를 켜는 것이 인증 우회 표면을 열지 않는다.
- 켜져 있다는 사실은 기동 로그의 dev 토글 WARN 으로도 남는다(의도된 것 — 지우지 말 것).

### 되돌리는 법 (비노출로 전환) ★ 둘을 반드시 함께

```
# 1) backend — WAR 형상: /etc/klid/application.properties · 베어메탈 토글: /etc/klid/backend.env
DEV_UPLOAD_ENABLED=false      # 재기동 필요(WAR 형상이면 WAS 재기동)

# 2) frontend — /etc/klid/frontend.env 편집 후 반영 명령 한 줄 (재빌드·재설치 불필요)
VITE_DEV_UPLOAD_ENABLED=false
#    sudo /opt/klid/bin/klid-frontend-config
```

> ★ **2026-08-30 변경** — 구 기재 *"frontend 는 재빌드 필요(빌드타임 치환이라 런타임 토글이
> 아니다)"* 는 폐기됐다. 이 토글은 이제 런타임 설정이라 새로고침이면 반영된다(C-1 절).

> ⚠ **backend 만 끄면 안 된다.** 화면과 LNB 항목은 그대로 보이는데 API 만 404 가 되어
> 운영자가 원인을 못 찾는다. 실제로 CO-008 이전 형상이 정확히 그 상태였다 —
> frontend 는 기본 true 로 노출돼 있는데 backend 만 기본 false 였다.
>
> ⚠ **frontend 만 끄는 것도 반쪽이다.** 화면은 사라지지만 API 는 열려 있다.

되돌린 뒤 확인: LNB 에 「수동 업로드」가 없고, `/admin/uploads` 직접 진입이 막히며,
`POST /api/v1/dev/upload` 가 404 다.
