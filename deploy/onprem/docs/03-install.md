# 03. [대상 서버] 오프라인 설치

폐쇄망 **레드햇 엔터프라이즈 리눅스 8.9** 대상 서버에서 root 로 실행한다. **외부 네트워크 호출이 전혀 없다**(모든 의존성은 번들).

> ★ **이 스크립트만으로는 설치가 끝나지 않는다** (배포 형상 = 외부 WAS 에 WAR 반입,
> @design DEPLOY-001 · RUNBOOK-001). backend 는 `api.war` 를 배치만 하고 **기동하지 않는다** —
> WAS 배포와 **WAS 설정 이관**은 아래 「설치 후 필수 단계」의 사람 작업이다.
> 자바 런타임은 반입하지 않는다(대상 WAS 가 Java 17 로 이미 돌고 있다).

## ★ 이미 설치된 시스템 패키지는 건드리지 않는다 (2026-08-31 신설)
반입 확인 요청서에서 상대에게 약속한 것이다. **두 겹으로 막는다.**

| 겹 | 동작 |
|:-:|------|
| 1 | 설치 전에 `rpm -q` 로 확인해 **이미 있는 것은 목록에서 뺀다.** 전부 있으면 **단계를 통째로 건너뛴다**(장비 무변경) |
| 2 | 남은 것을 설치하기 전에 **미리보기**를 돌려, 기존 패키지를 **올리거나 내리려 하면 설치하지 않고 중단**한다 |

왜 필요한가 — `dnf install` 은 **이미 설치돼 있어도** 저장소에 더 신 버전이 있으면 그것으로
**업그레이드한다.** 반입 매체는 수집 시점의 최신 보안 패치로 모이므로, **배포판 판이 같아도**
장비가 그 패치를 아직 안 받았으면 기반 라이브러리가 올라간다.

2겹에 걸리면 **무엇이 바뀌는지 이름과 판을 찍고** 멈춘다:

```
[WARN] 이 설치는 <이미 설치된 패키지를 변경>하려 합니다 — 중단합니다.
       설치하려던 것 : httpd policycoreutils-python-utils
       바뀌는 패키지 :
         - Upgrading: audit-libs 3.1.2-1.el8_10.1
         - Upgrading: libselinux 2.9-11.el8_10
         - Upgrading: libsemanage 2.9-12.el8_10
```

> ⚠ `KLID_ALLOW_PKG_CHANGE=1` 로 넘길 수 있으나 **기본이 아니다** — 켜면 위 약속이 깨진다.
> 넘기기 전에 반드시 그 장비 담당과 합의할 것.

### 실측 — 어느 단계가 실제로 걸리나 (2026-08-31, Rocky 8.6/8.9/8.10 컨테이너 검증)

| 단계 | 결과 | 바뀌려던 것 |
|---|---|---|
| `11` mesa·glib2 (ai 서버) | **통과** — 세 판 모두 무변경 설치 | 없음 |
| `10` 번들 PostgreSQL 16 | **중단** | `openssl-libs` **1건** |
| `14` 번들 httpd | **중단** | `audit-libs`·`libselinux`·`libsemanage` **3건** |

- 뒤 둘은 **8.10(수집 판과 같은 판)에서도 똑같이 걸린다.** 판 불일치가 아니라 컨테이너 기준
  이미지가 그 보안 패치를 아직 안 받은 상태이기 때문이다. 실제 장비가 최신 패치를 받았다면
  1겹에서 걸러져 그냥 통과한다.
- **그러나 이 둘은 실제 배포에서 대개 타지 않는 경로다** — 아래를 보라.

### 실제 배포에서는 이 둘을 타지 않는다 (권장 실행형)

| 대상 | 왜 안 타나 | 어떻게 |
|---|---|---|
| PostgreSQL | **전제조건**이다. 빈 데이터베이스 1개(control)와 앱 계정을 현장 담당이 준비한다 | 설치에 PG 단계가 **아예 없다**(2026-09-05 — 구 `10` 단계·`USE_BUNDLED_POSTGRES` 토글 제거) |
| httpd | 관제와 공동 배치라 **이미 깔려 있을 가능성이 높다** | `14` 단계가 `command -v httpd` 로 확인해 **RPM 설치를 건너뛴다**(설정·정적 자산 배치는 그대로 수행) |

```bash
# 권장 — 장비 역할별 (2026-09-04 현장 형상: 웹 2 · WAS 4 · AI 2)
sudo ./scripts/install-was.sh ...   # WAS 장비
sudo ./scripts/install-web.sh ...   # 웹 장비
sudo ./scripts/install-ai.sh        # AI 장비

# 단일 서버 구성(하위호환)
sudo ./scripts/install.sh --role=app
```

번들 PG 를 꼭 써야 하고 위 1건이 걸린다면, `openssl-libs` 를 **장비 담당이 먼저 갱신**한 뒤
다시 실행하는 것이 우선이다(그러면 1겹에서 걸러진다). 그게 불가하면 `rpm -q openssl-libs`
결과를 알려 주면 그 판으로 다시 수집한다.

> ★ **미리보기가 아예 못 돌면 "변경 없음"이 아니라 "변경된다"로 본다**(fail-closed).
> 번들 GPG 공개키가 등록되지 않으면 `dnf` 는 트랜잭션 요약을 **만들지 않고** 죽는데,
> 그때 목록이 빈 것만 보고 판정하면 **안전장치가 통과시킨다.** 실측으로 확인해 막았다.


## 서버 2대 — 무엇을 어디에 올리나

대상 장비는 **2대**다. **매체는 하나이고, 설치할 때 역할만 고른다**(패키지를 물리적으로 쪼개지 않는다).

| | **서버 A (`--role=app`)** | **서버 B (`--role=ai`)** |
|---|---|---|
| 구성 | httpd(정적 서빙 + `/api` 프록시) · 외부 WAS 에 `api.war` · (옵션)PostgreSQL·DB 초기화 | ai-server(번들 파이썬 + 오프라인 휠 + YOLOX/SAM2 모델) |
| 도는 단계 | 10 · 12 · 14 · 15 · 16 · 17 · 19 | 11 · 13 |
| systemd 유닛 | `httpd` — **백엔드는 유닛이 아니다**(외부 WAS 가 기동 주체) | `klid-ai-server` |
| 쓰는 반입물 | `artifacts/backend` · `artifacts/frontend/dist/{control,portal}`(**향 하나만 배치**) · `syspkgs/{rpm,gpg,postgresql,ffmpeg,ffmpeg-src}` | `vendor/wheels` · `models/weights` · `runtimes/python` · `syspkgs/{rpm,gpg}` |
| ffmpeg | **전제조건** — 관제지원시스템 팀이 설치한다. 우리는 **검증만** 한다(19단계) | 쓰지 않는다(ai-server 에 `ffmpeg`/`ffprobe` 호출 0건) |
| GPU | 해당 없음 | **장비에 GPU 가 있으나 이번 반입은 CPU 전용**이다(torch CPU 휠) |

> `syspkgs/rpm` 이 **양쪽 모두**인 이유: 서버 A 는 `httpd`·`policycoreutils-python-utils`,
> 서버 B 는 `mesa-libGL`·`libglvnd-glx`·`glib2` 를 같은 로컬 저장소에서 꺼내 쓴다.
>
> ⚠ **역할을 주지 않으면 종전대로 전체가 설치된다**(`--role=all` 이 기본). 기존 단일 서버 절차는
> 그대로 유효하며, 이 분리로 깨지지 않는다.

> ★★ **화면 산출물은 배포 향(`KLID_DEPLOY_FLAVOR`)이 고른다** — 매체에는 관제향·포털향 두 벌이
> 실리고 서버 A 설치가 **그중 하나만** `/opt/klid/web/dist` 에 배치한다. 관제 연동 배포는
> **기본값이라 아무것도 주지 않아도 된다**; 포털 연동 배포만 `sudo KLID_DEPLOY_FLAVOR=portal
> ./scripts/install.sh` 로 넘긴다. 향에 맞는 산출물이 매체에 없으면 **다른 향을 대신 깔지 않고
> 즉시 실패**한다. 자세히는 [04-configuration.md](04-configuration.md) D-4.

## ★★ 실행 계정 — 기본값(`klid`)을 그대로 쓰면 앱이 기동하지 못한다 (2026-09-04 현장 확정)

현장의 실제 실행 계정은 **`jboss`**(WAS)와 **`apache`**(httpd)다. 그런데 설치 스크립트의 기본값은
`KLID_USER=klid` / `KLID_GROUP=klid` 라, 그대로 돌리면 설정·데이터·로그가 전부 `klid` 소유가 되고
**WAS 계정 `jboss` 가 그것들을 읽거나 쓰지 못한다.**

⚠ **이 실패는 조용하다** — WAS 는 정상으로 뜨고 배포도 성공하며, **애플리케이션만** 설정을 못 읽어
기동에 실패한다. "WAS 는 살아 있는데 `/api` 가 응답하지 않는다"로만 나타난다.

### 서버 A — 설치 시 계정을 넘긴다

```bash
sudo KLID_USER=jboss KLID_GROUP=jboss ./scripts/install.sh --role=app
```

그러면 이렇게 잡힌다.

| 대상 | 소유·권한 | 왜 |
|---|---|---|
| `/etc/klid/application.properties` | `root:jboss` `0640` | WAS(`jboss`)가 읽어야 한다. **여기가 제일 자주 걸린다** |
| `/etc/klid/*.env` · `was.env` | `root:jboss` | 〃 |
| `/var/lib/klid` · `/var/log/klid` | `jboss:jboss` | 백엔드가 쓴다 |
| `/opt/klid/app/api.war` | `jboss:jboss` | 배포 원본 |

**`apache` 는 따로 넘길 것이 없다.** httpd 가 읽는 것은 정적 dist(`/opt/klid/web/dist`)와
`klid-config.js` 인데 둘 다 **world-readable(`0644`/`0755`)** 이고, 14단계가 SELinux 문맥
(`httpd_sys_content_t`)까지 부여한다. 그래서 소유자를 바꿀 필요가 없다.

### 이미 기본값으로 설치했다면 — 사후 교정

```bash
sudo chgrp -R jboss /etc/klid && sudo chmod 640 /etc/klid/*.properties /etc/klid/*.env
sudo chown -R jboss:jboss /var/lib/klid /var/log/klid /opt/klid/app

# 확인 — jboss 가 실제로 읽히는지 (권한 표만 보지 말고 직접 읽어 본다)
sudo -u jboss cat /etc/klid/application.properties > /dev/null && echo "읽기 OK"
sudo -u jboss test -w /var/lib/klid && echo "쓰기 OK"
```

### ⚠ NAS 저장소는 별도다

영상·프레임은 로컬이 아니라 **NAS 마운트**(`STORAGE_RAW_PATH`, 기본 `/nas-storage`)에 쌓인다.
그 경로의 소유·권한은 **스토리지 운영 주체가 정하므로 설치 스크립트가 손대지 못한다.**
`jboss` 가 그 아래에 **쓸 수 있는지** 반드시 별도로 확인한다.

```bash
sudo -u jboss test -w /nas-storage && echo "NAS 쓰기 OK" || echo "★ NAS 쓰기 불가 — 스토리지 담당 협의 필요"
```

못 쓰면 비식별·프레임 추출·export 가 전부 실패하는데, **기동과 조회는 정상이라** 한참 뒤에야 드러난다.

---

## 실행

```bash
cd deploy/onprem

# 서버 A (프론트 + 백엔드)
sudo ./scripts/install.sh --role=app

# 서버 B (ai-server)
sudo ./scripts/install.sh --role=ai

# 단일 서버 구성(종전 동작) — 역할 미지정
sudo ./scripts/install.sh
```

옵션:

```bash
# 스키마가 이미 준비된 경우 — 적재 단계 생략
sudo SKIP_SCHEMA_LOAD=1 ./scripts/install.sh --role=app

# 기존 nginx 사용(httpd 대신 nginx.conf 템플릿만 배치)
sudo USE_NGINX=1 ./scripts/install.sh

# 설치 경로/사용자 변경(기본 /opt/klid, klid)
sudo KLID_PREFIX=/opt/klid KLID_USER=klid ./scripts/install.sh
```

### ★ 상위 시스템 로그인 주소를 함께 준다 (안 주면 5단계에서 멈춘다)

프론트엔드 산출물은 **환경 무관**이라 로그인 주소를 갖고 있지 않다. 그 값은 설치 시점에 대상
서버의 정본(`/etc/klid/frontend.env`)으로 들어간다. 첫 설치에서 한 번에 채우려면:

```bash
sudo VITE_CONTROL_LOGIN_URL=https://control.example.local/login \
     VITE_PORTAL_LOGIN_URL=https://portal.example.local/login \
     ./scripts/install.sh --role=app
```

주지 않으면 정본이 빈 값으로 놓이고 **그 자리에서 설치가 멈춘다**(fail-closed — 저작도구는 자체
로그인 UI 가 없어 이 값이 비면 세션 만료 시 갈 곳이 없다). 값을 채우고
`sudo /opt/klid/bin/klid-frontend-config` 실행 후 `install.sh` 를 다시 돌리면 이어진다.
설치 후 값 변경은 재빌드·재설치 없이 그 명령 한 줄이다 — `04-configuration.md` C-1 절.

## 웹 컨텍스트 — 두 향 중 하나를 고른다 (2026-09-05)

현장 웹(httpd)이 `/label-studio/api/` 로 들어온 요청을 WAS 로 넘길 때 **그 대상 경로에 `/api` 를
남기느냐 걷어내느냐**에 따라 WAS 가 받는 경로가 갈린다. **브라우저가 부르는 주소는 두 향에서
똑같다**(`/label-studio/api/v1`) — 다른 것은 httpd 가 넘기는 모양뿐이다.

| 향 | 현장 httpd | WAS 가 받는 경로 | WAR 컨텍스트 |
|---|---|---|---|
| **passthrough** | `→ balancer://…/label-studio/api/` | `/label-studio/api/v1` | `/label-studio/api` |
| **strip** | `→ balancer://…/label-studio/` | `/label-studio/v1` | `/label-studio` |

### 고르는 법 — 빌드 인자 하나

```bash
./gradlew bootWar                                   # 기본값(passthrough)
./gradlew bootWar -PklidWebContext=/label-studio    # strip 향
KLID_WEB_CONTEXT=/label-studio ./gradlew bootWar    # 같음
```

잘못된 값은 **빌드가 시작 전에 거부**한다(`/` 로 시작·`/` 로 끝나지 않음·경로 문자만).
만들어진 향은 `artifacts/backend/BUILD-INFO.txt` 의 `web_context` 에 기록된다.

### 어느 향인지 판정 — 브라우저 한 줄

```
https://<사이트>/label-studio/api/actuator/health/liveness
```

이 경로는 **인증 없이 열려 있다**(`permitAll`). JSON 이 나오면 지금 배포된 향이 맞고, **404 면 다른 향**이다.

⚠ **틀린 향을 올려도 아무 신호가 없다.** WAS 는 정상 기동하고 배포도 성공이며 로그도 조용하고
**화면까지 뜬다**(정적 자산은 `/label-studio/` 에서 따로 받아 온다). **API 만 전건 404** 다.
그래서 설치(`17-deploy-jboss.sh` · `site-install.sh`)가 배포할 WAR 에서 컨텍스트를 **읽어** 확인한다 — 추측하지 않는다.

### 바꿀 때 교체할 것 — WAR 하나뿐이다

- **프론트는 재빌드하지 않는다** — 브라우저가 부르는 주소가 두 향에서 같다.
- **httpd 설정은 건드리지 않는다** — 현장 공용 설정이다.
- **`/etc/klid/frontend.env` 도 그대로다** — `VITE_API_BASE_URL=/label-studio/api/v1`.

즉 다른 향으로 WAR 을 다시 만들어 배포 디렉터리의 파일만 갈아 끼우면 된다.

⚠ 현장 httpd conf **원본이 우리 저장소에 없다** — 위 ProxyPass 는 2026-09-04 에 사람이 옮겨 적은
한 줄이 유일한 근거다. 그래서 한쪽으로 못박지 않았다. 원본을 확인하면 그때 기본값을 확정한다.


## 단계별 동작

| 단계 | 역할 | 스크립트 | 동작 |
|------|:----:|----------|------|
| 0 | 공통 | `install.sh` | `klid` 사용자/그룹 + 디렉토리 생성, **역할별** SHA256 무결성 검증 |
| 2 | **ai** | `install/11-install-runtimes.sh` | **Python**(ai-server 용) + **RPM**(mesa-libGL/libglvnd-glx/glib2) 오프라인 설치. ⚠ 구 동작 폐기(2026-08-30): "**ffmpeg RPM 도 여기서 자동 설치**" — ffmpeg 는 서버 A 의 전제조건이라 자동 설치하지 않는다 |
| 3 | **was** | `install/12-install-backend.sh` | **`api.war` 배치**(`/opt/klid/app/api.war`) + `application.properties`/`backend.env` + **`was.env`**(WAS 현장값 기록 파일). **기동하지 않는다** — WAS 배포·WAS 설정은 사람 작업. 베어메탈 형상(jar + systemd 유닛)은 `INSTALL_BACKEND_SYSTEMD_UNIT=1` 일 때만 |
| 4 | **ai** | `install/13-install-ai-server.sh` | venv + `pip --no-index` 설치 + 모델 배치 + 유닛(`klid-ai-server`) |
| 5 | **web** | `install/14-install-frontend.sh` | dist 배치 + **런타임 설정 정본(`/etc/klid/frontend.env`) 배치 + `klid-config.js` 생성**(★ 상위 로그인 주소가 비면 **여기서 설치가 멈춘다**) + httpd RPM 설치 + `conf.d` 드롭인 + SELinux 문맥·불리언 + `httpd` 기동 |
| 6 | **was** | `install/16-load-schema.sh` | **스키마 적재 — 조건부.** 비어 있으면 `db/schema.sql` 을 적재하고, 이미 있고 개수가 기대와 같으면 건너뛰며, **기대와 다르면 덮어쓰지 않고 멈춘다**. `SKIP_SCHEMA_LOAD=1` 로 생략. ⚠ 데이터베이스·계정 **생성은 하지 않는다**(현장 선행 조건) |
| 9 | **was** | `install/19-verify-ffmpeg.sh` | **ffmpeg·ffprobe 전제조건 검증**. 없으면 **설치를 중단한다**(아래 「ffmpeg」 절) |
| 10 | **web** | `install/20-verify-frontend-config.sh` | 프론트 런타임 설정 최종 게이트. 상위 로그인 주소가 비면 **설치를 실패로 종결한다** |
| 11 | **was** | `install/21-verify-ai-server-url.sh` | **AI 추론 서버 주소 확인.** 2대 구성인데 기본값(loopback)이 남아 있으면 경고한다. ★ **설치를 실패시키지 않는다**(경고만) — 04 「주소 한 표」 ① 참고 |

> **`install/install-ffmpeg.sh` 는 이 표에 없다** — 번호 접두가 없는 것이 그 표식이며,
> `install.sh` 가 **호출하지 않는다**. 사람이 명시적으로 부를 때만 도는 수동 명령이다.

> **데이터베이스 vs 스키마 — 역할 분담**: 데이터베이스 서버 설치와 데이터베이스·계정 생성은
> **우리 일이 아니다**(현장 선행 조건). 우리 설치가 하는 것은 **스키마 적재**(단계 6·`16`)와
> **WAS 배포**(단계 7·`17`)다.
> ⚠ **「데이터베이스를 만들지 않는다」를 「스키마도 적재하지 않는다」로 읽지 말 것.** 다른 일이다.
>
> ⚠ **구 서술 폐기(2026-09-05)** — *"단계 1(`10`)은 PG 엔진 설치+기동, 단계 6(`15`)은 control/portal
> DB·앱 유저 생성"*. 두 단계와 `USE_BUNDLED_POSTGRES` 토글이 **모두 제거**됐다. 되살리지 말 것.
>
> **★ 테이블은 전체 스키마 SQL 적재가 만든다 — 온프렘은 Flyway 를 쓰지 않는다.**
> 경로는 **하나뿐이다.** 설치 단계 `16` 이 `db/schema.sql` 을 **1회 적재**하고,
> 앱은 마이그레이션을 **수행하지 않는다**. 2노드 동시 기동 시의 Flyway 락 경합·최초 부팅 지연도
> 함께 사라진다.
>
> ⚠ **앱 코드 자체의 기본값은 켬(`true`)이다.** 꺼진 상태는 **매체가 만든다** — `config/backend/` 의 두
> 템플릿이 끄는 줄을 갖고 있고 설치(`12-install-backend.sh`)가 그대로 `/etc/klid/` 에 복사한다
> (WAR 형상은 `application.properties` 의 `spring.flyway.enabled=false`, 베어메탈 형상은 `backend.env` 의
> `SPRING_FLYWAY_ENABLED=false`). **그 한 줄을 지우면 앱 기본값이 되살아나 켜진다 — 지우지 말 것.**
> 형상마다 키 이름이 다르고 한쪽에서는 조용히 무시되는 함정은
> [04-configuration.md 「키 이름 변환 규칙」](04-configuration.md) 이 정본이다.
>
> **★ 적재는 조건부다 — 현장 담당 선적용 우선 + 우리가 채움** (2026-09-05 확정)
>
> | 대상 스키마 상태 | `16` 단계가 하는 일 |
> |---|---|
> | 비어 있음 | `db/schema.sql` 을 **적재한다** |
> | 이미 있고 개수가 기대와 같음 | **건너뛴다**(현장 담당이 미리 적용해 둔 경우) |
> | 이미 있는데 기대와 다름 | **덮어쓰지 않고 멈춘다** — 현장 데이터가 사라질 수 있다 |
>
> 어느 쪽이 먼저 했든 결과가 같다. 여러 대가 같은 데이터베이스 한 벌을 보므로 **첫 대에서만** 적재한다.
>
> ⚠ **구 서술 폐기(2026-09-05)** — *"그 유일한 경로가 옵트인 플래그 뒤에 있다 … `SCHEMA_LOAD_RUN=1` 을
> 줄 때만. 안 주면 안내만 출력하고 넘어간다"*. 기본이 「안 함」이면 **아무도 적재하지 않은 채 넘어간다.**
> 이제 기본이 적재이고, 빠져나갈 문은 `SKIP_SCHEMA_LOAD=1` 하나다.
>
> ⚠ **그래도 「적재됐는지」는 반드시 세어서 확인한다.** `SKIP_SCHEMA_LOAD=1` 로 건너뛰거나,
> 세 번째 갈래(개수 불일치)로 멈췄는데 그것을 넘겼다면 **테이블이 하나도 없는 상태가 조용히
> 남을 수 있고, Flyway 가 없으므로 뒤에서 대신 만들어 주는 것이 없다.** 그대로 WAR 를 올리면
> 앱은 **기동에는 성공한다** — 그리고 화면·배치가 DB 를 처음 건드릴 때 전부 깨진다.
> 아래 확인이 유일한 신호다.
>
> ⚠⚠ **`ddl-auto=validate` 가 대신 막아 주지 않는다.** `application.yml` 에 `validate` 가
> 선언돼 있지만 이 저장소에서는 **실동작하지 않는다** — `JpaBuilderConfig` 가
> `EntityManagerFactory` 를 직접 만들면서 `spring.jpa.properties.*` 만 넘기므로
> `spring.jpa.hibernate.ddl-auto` 가 Hibernate 까지 전달되지 않는다.
> ⚠ 구 서술 폐기 — *"듀얼 데이터소스라"*. 포털 데이터소스는 2026-08-31 에 철거됐고,
> 그럼에도 이 성질은 그대로다(EMF 를 직접 만드는 것이 원인이지 데이터소스가 둘이어서가 아니다). 그래서 **기동 로그가 깨끗해도 스키마가 비어 있을 수 있다.**
> 근거·상세는 [09-operations-runbook.md](09-operations-runbook.md) §2-5-2 「왜 조용히 실패하나」.
> ⚠ 구 서술 폐기(2026-08-30) — *"그대로 WAR 를 올리면 앱은 `ddl-auto=validate` 에서 기동에
> 실패한다"*. 그 기대에 기대면 빈 스키마인 채로 운영에 넘어간다.
>
> ```bash
> psql -h <HOST> -p <PORT> -U <APP_USER> -d klid_system \
>      -c "select count(*) from information_schema.tables where table_schema='klid_at';"
> # 0 이면 로드가 안 된 것이다 — WAR 를 올리기 전에 db/schema.sql 을 먼저 넣는다.
> ```
>
> `db/schema.sql` 은 `LS_*` 62개 · `QRTZ_*` 11개 · 뷰 4개 와 시드 66행(7 테이블)을 담은 통합 DDL 이다
> (`MNG_*` 는 들어 있지 않고, `CREATE TABLE IF NOT EXISTS` 도 쓰지 않는다 — 빈 스키마 전제다).
> 로드는 **멱등**이다 — 대상 스키마에 테이블이 이미 있으면 로드하지 않고 기존 스키마를 유지한다
> (그래서 DBA 가 선적용한 DB 에 `16` 을 다시 돌려도 덮어쓰지 않는다).

> **★ 스키마**: 저작도구 객체는 **`klid_at`**(`DB_SCHEMA`, 기본값)에 만들어진다. portal DB 는 대상이
> 아니며 `public` 을 그대로 쓴다(별개 물리 DB) — **두 DB 가 다른 것이 정상**이다.
> **저작도구 객체가 `public` 에 있는 기존 DB 는 설치 전에 이관**해야 한다
> (`09-operations-runbook.md` §2-5-1). 이관 없이 로드하면 빈 `klid_at` 이 생기고 데이터는 `public` 에
> 남아 앱이 조용히 빈 스키마를 보므로, `16-load-schema.sh` 는 그 상태를 감지하면 **로드를 거부**한다.

설치는 **멱등**하다(재실행 안전). 이미 존재하는 `*.env` 는 덮어쓰지 않아 사용자 편집을 보존한다.
번들 PG 단계도 멱등하다(이미 설치/initdb 된 경우 해당 작업을 건너뛴다).

## ★ 단계별 수동 실행 — 막힌 지점부터 이어서 돌리기

`install.sh` 일괄 실행은 **그대로 남아 있다.** 값을 다 아는 현장에서는 그쪽이 빠르다.
아래는 **중간에 막혔을 때** 원인을 고치고 그 단계부터 이어가기 위한 경로다.

### 되는 것과 전제

- **모든 단계는 단독으로 실행할 수 있다.** 각 스크립트가 스스로 `lib/common.sh` 를 읽고,
  거기서 설치 공통 변수(`KLID_PREFIX`·`KLID_ETC`·`KLID_USER` …)의 기본값을 받는다.
- **모든 단계는 재실행이 안전하다(멱등).** 이미 끝난 작업은 건너뛰고, 이미 있는 설정 파일은 덮지 않는다.
- **역할을 반드시 함께 준다.** 단독 실행에서는 `KLID_ROLE` 이 `all` 로 잡히므로, 2대 구성이라면
  `KLID_ROLE=app` 또는 `KLID_ROLE=ai` 를 명시한다. 그러지 않으면 그 장비에서 돌면 안 되는 단계가 돈다.

```bash
cd /매체를_푼_경로/deploy/onprem

# 단계 목록 보기(권한 불필요)
./scripts/install-step.sh list

# 역할에 따라 <실제로 도는> 단계만 보기(권한 불필요·장비 무변경)
./scripts/install.sh --role=app --list

# 한 단계만 실행 — 번호로 부른다
sudo KLID_ROLE=app ./scripts/install-step.sh 14

# 스크립트를 직접 불러도 결과는 같다(러너는 얇은 껍데기다)
sudo KLID_ROLE=app ./scripts/install/14-install-frontend.sh
```

### 일괄 실행에서 일부만 빼거나 고르기

한 단계만 돌릴 때는 위 `install-step.sh` 가 간단하다. **여러 단계를 빼고 나머지를 순서대로
돌리려면** `install.sh` 쪽 플래그를 쓴다 — 역할 게이트·요약·끝단 검증이 그대로 붙는다.

```bash
sudo ./scripts/install.sh --role=app --skip=10,15,16   # DB 관련만 빼고 나머지 전부
sudo ./scripts/install.sh --role=ai  --only=13         # 그 단계만(여러 개면 --only=12,14)
```

- 번호(`13`)·번호접두(`13-`)·전체 파일명 아무거나 받는다.
- `--only` 와 `--skip` 은 **함께 쓸 수 없다**(무엇이 도는지 모호해진다).
- 그 역할에 없는 단계를 `--only` 로 주면 **도는 단계 목록을 보여주며 거부**한다.
- 부분 실행이면 경고를 남긴다 — 빠진 결과물이 있으면 그 자리에서 "무엇이 없다"고 멈춘다.

> `install-step.sh` 는 **단계 목록을 자기가 들고 있지 않다.** `install/` 디렉터리에서 그때그때
> 찾고 설명도 각 스크립트 머리말에서 읽는다 — 목록을 한 번 더 적으면 `install.sh` 와 어긋나는
> 두 번째 진실원이 되기 때문이다. 따라서 **러너를 거치든 스크립트를 직접 부르든 동작이 같다.**

### 막혔을 때의 동선

```
install.sh 가 N 단계에서 실패
  → 로그에서 원인 확인 (06-troubleshooting.md)
  → 원인 조치
  → sudo KLID_ROLE=<역할> ./scripts/install/<N 단계 스크립트>   # N 단계만 다시
  → 성공하면 그 다음 단계부터 순서대로 이어서 실행
```

앞 단계로 되돌아갈 필요는 없다. 앞 단계는 이미 끝나 있고 멱등이라 다시 돌려도 무해하다.

### 단계 목록 (단독 실행 기준)

`sudo KLID_ROLE=<역할> ./scripts/install/<스크립트>` 형태로 실행한다.

| 순서 | 스크립트 | 역할 | 하는 일 | 선행 조건 | 재실행 |
|:--:|---|:--:|---|---|:--:|
| 0 | `install.sh` (앞부분) | 공통 | `klid` 사용자·그룹, `/opt/klid`·`/etc/klid`·`/var/lib/klid`·`/var/log/klid` 생성, 무결성 검증 | 없음 | 안전 |
| 2 | `11-install-runtimes.sh` | ai | Python 런타임 + RPM 의존성. `runtime/runtime.env` 기록 | 0 | 안전(대상 디렉터리 교체) |
| 3 | `12-install-backend.sh` | **was** | `api.war` 배치 + 설정 템플릿 3종 | 0 | 안전(설정 파일 보존) |
| 4 | `13-install-ai-server.sh` | ai | venv + 오프라인 휠 + 모델 + 유닛 | **2**(`runtime.env` 필요) | 안전(설정 보존, 앱 소스 교체) |
| 5 | `14-install-frontend.sh` | **web** | dist 배치 + httpd + 드롭인 + 설정 생성 | 0 | 안전. ⚠ **httpd 드롭인은 매번 재생성**(손편집 소실) |
| 6 | `16-load-schema.sh` | **was** | 스키마 적재(조건부 — 비었으면 적재·같으면 건너뜀·다르면 멈춤) | 현장이 빈 DB·앱 계정 준비 | 안전(멱등) |
| 9 | `19-verify-ffmpeg.sh` | **was** | ffmpeg 전제조건 검증 | 없음 | 안전(검증만) |
| 10 | `20-verify-frontend-config.sh` | **web** | 프론트 설정 게이트 | **5** | 안전(검증·생성) |
| 11 | `21-verify-ai-server-url.sh` | **was** | AI 서버 주소 확인 | **3** | 안전(검증만) |

> **0 단계는 스크립트가 따로 없다** — `install.sh` 의 앞부분에 인라인으로 들어 있다.
> 그래서 **완전 수동으로 처음부터 갈 때는 `install.sh` 를 한 번 돌려 두는 것이 가장 확실하다.**
> (한 번 돌면 사용자·디렉터리가 만들어지고, 이후에는 개별 단계만 다시 돌리면 된다.)
> 정직하게 적자면 **이 한 가지는 단계로 분리돼 있지 않다.**

> **4 단계는 2 단계에 의존한다.** `11` 이 기록한 `/opt/klid/runtime/runtime.env` 에서 파이썬 경로를
> 읽기 때문이다. 값은 **디스크 파일로 넘어가므로** 두 단계를 다른 시점에 따로 돌려도 된다
> (같은 셸에서 연달아 돌릴 필요가 없다). `11` 이 안 돌았으면 `13` 이 그 사실을 명시하며 멈춘다.

> **`install/install-ffmpeg.sh` 와 `install/build-from-source.sh` 는 이 표에 없다** —
> 번호 접두가 없는 것이 그 표식이며 `install.sh` 가 호출하지 않는다. 사람이 명시적으로 부르는 명령이다.

## 오프라인 설치 보장

- ai-server: `pip install --no-index --find-links vendor/wheels` + sam2 로컬 소스. PyPI/인터넷 접근 0.
- 런타임: 번들 tar.gz 압축 해제만.
- ffmpeg: **설치하지 않는다**(아래 「ffmpeg」 절). 번들(`syspkgs/ffmpeg/*.rpm`)은 예비물이며
  필요할 때 `install/install-ffmpeg.sh` 로만 오프라인 설치한다 → `/usr/bin/{ffmpeg,ffprobe}`.
  ⚠ 구 서술 폐기(2026-08-30) — "번들 RPM 을 11단계가 **자동으로** 오프라인 설치".
  ⚠ 구 서술 폐기(2026-08-28) — "번들 정적 tarball 을 `/opt/klid/runtime/ffmpeg/bin/` 로 풀어 배치".
- RPM: `dnf install -y --disablerepo='*' syspkgs/rpm/*.rpm`(폴백 `rpm -Uvh --replacepkgs`)로 로컬 설치.
- **PostgreSQL 16**: `dnf install -y --disablerepo='*' --setopt=gpgcheck=0 syspkgs/postgresql/*.rpm`
  (폴백 `rpm -Uvh`)로 로컬 설치. PGDG 미러/인터넷 미접근. 전이 의존성은 번들 RPM 으로 해소.

## ffmpeg — 세 경로 (서버 A 전용)

`ffmpeg`·`ffprobe` 는 **대상 장비의 전제조건**이며 **관제지원시스템 팀이 백엔드가 도는 서버에 설치한다.**
우리 설치 스크립트는 **자동으로 설치하지 않는다.**

> **왜 자동이 아닌가**: 이 장비는 관제지원시스템과 **공동 배치**다. 관제가 이미 깔아 둔 ffmpeg 를
> 우리 설치가 덮어쓰면 **관제 기능이 깨진다.** 그 위험이 "자동이라 편하다"보다 크다.
> ⚠ 편의를 이유로 `install.sh` 의 단계 목록에 `install-ffmpeg.sh` 를 넣지 말 것 — 바로 그 사고가 난다.

| | 상황 | 무슨 일이 일어나나 |
|---|---|---|
| ① | **관제가 이미 설치** (기본·정상) | 19단계가 검증만 하고 통과한다. 우리는 **아무것도 설치하지 않는다.** |
| ② | **설치되어 있지 않다** | 19단계가 **설치를 중단시킨다**(`die`). 관제팀에 요청하거나, 관제가 설치하지 않는 것이 확인되면 아래 명령으로 **사람이** 설치한다. |
| ③ | **있는데 교체가 필요하다** | 기본 동작은 **건너뛰기**다. 굳이 교체하려면 `--force` 를 명시해야 하고, 그때 현재 버전 → 대상 버전을 출력하고 확인을 받는다. **관제 영향 검토가 선행**되어야 한다. |

```bash
# ② 없을 때만 설치(이미 있으면 아무것도 하지 않고 현재 버전을 알린다)
sudo ./scripts/install/install-ffmpeg.sh

# 무엇을 할지만 확인
sudo ./scripts/install/install-ffmpeg.sh --dry-run

# ③ 이미 있어도 번들 판으로 교체 — 관제와 합의된 경우에만
sudo ./scripts/install/install-ffmpeg.sh --force
```

**검증(19단계)이 보는 것** — 존재 확인만으로는 부족하므로 세 가지를 순서대로 본다.

1. `FFMPEG_BIN`·`FFPROBE_BIN` **설정값이 가리키는 경로**가 실행 가능한가
   (경로를 하드코딩하지 않는다 — 운영자가 설정을 바꾸면 검증 대상도 따라간다)
2. `-version` 이 **0 을 반환**하는가
3. **아주 작은 입력으로 실동작** — 1프레임 `.jpg` 를 만들고 `ffprobe` 로 되읽는다.
   backend 가 프레임을 `.jpg` 로 쓰므로(mjpeg 인코더) 그 경로를 그대로 태운다.

> ⚠ **`ffmpeg` 만 있고 `ffprobe` 가 없는 것도 실패다.** 일부 최소 설치는 `ffmpeg` 만 깔리는데,
> 우리는 영상 길이·해상도 조회에 `ffprobe` 를 **별도로** 쓴다(`authoring.ffmpeg.ffprobe-binary`).
>
> ⚠ **왜 `warn` 이 아니라 중단인가**: ffmpeg 가 없어도 **서버는 정상 기동하고 헬스체크도 통과한다.**
> 프레임 추출·영상 메타 조회는 **배치를 실제로 돌려야** 깨진다 — 즉 운영 중에 드러난다.
> 그래서 설치 시점에 멈춘다. 19단계는 **맨 마지막**이라 앞 단계는 이미 끝나 있고,
> ffmpeg 를 마련한 뒤 `sudo ./scripts/install.sh --role=app` 을 다시 돌리면 된다(멱등).

> **라이선스**: 번들 ffmpeg 는 RPM Fusion 의 **GPLv3+** 빌드다. 매체에 담아 반입하는 것 자체가
> 재배포이므로 **대응 소스(SRPM)를 `syspkgs/ffmpeg-src/` 에 함께 반입**한다(설치 대상은 아니다).
> 그 디렉터리가 비어 있으면 매체 구성이 불완전한 것이다.

## 설치 후 필수 단계 (사람이 한다 — 건너뛸 수 없다)

1. **`/etc/klid/application.properties` 편집** — DB 비밀번호, JWT_SECRET, STREAM_SIGN_SECRET,
   webhook HMAC, **`ADMIN_CLAIM_PASSWORD_HASH`** 등 (키 목록은 04 참고).
   **WAR 형상에서 WAS 가 읽는 파일은 이것이다.**
   ★ **`ADMIN_CLAIM_PASSWORD_HASH` 에는 이제 기본값이 있다(2026-09-01) — 평문 `admin`.**
   비워 두면 아무도 관리자가 되지 못해 관리 기능에 손을 댈 수 없었기 때문이며, 그 상태는
   기동도 헬스체크도 스모크도 전부 통과해 **아무 신호도 나지 않았다**(관리자 권한 부여 창구만
   항상 401). 그래서 기본값을 두어 첫 진입이 막히지 않게 했다.
   ⚠⚠ **그 값은 널리 알려진 값이다 — 첫 진입 직후 관리 화면에서 반드시 바꾼다.**
   바꾸면 새 값이 저장소(`klid_at.ls_mngr_pswd`)로 옮겨가고 이 설정값은 더 이상 판정에 쓰이지
   않는다(그 전이는 09 운영 런북의 판정 순서 표를 볼 것).
   다른 값을 쓰려면 BCrypt 해시(cost 12 이상)만 넣는다 — 평문을 넣으면 기동이 실패한다.
   생성은 04 의 치트시트: `htpasswd -bnBC 12 "" '평문' | tr -d ':\n'`
   WAS 기동 옵션에 `-Dspring.config.additional-location=file:/etc/klid/` 와
   `-Dspring.profiles.active=prd` 를 넣고, JVM 옵션(MaxRAMPercentage·G1GC·egd)은 **`JAVA_OPTS`**(EAP `bin/standalone.conf`)로 옮긴다.
   ⚠ 톰캣의 `CATALINA_OPTS` 가 아니다 — EAP 에는 그 변수가 없다(2026-09-04 정정).
   ⚠ **`SPRING_` 으로 시작하는 스프링 자체 설정은 이 파일에서 이름 변환이 일어나지 않는다** —
   `spring.flyway.enabled=false` 처럼 **점 표기**로 적어야 한다(실측: `SPRING_FLYWAY_ENABLED=false`
   는 조용히 무시되어 DBA 가 선적용한 스키마 위에서 마이그레이션이 그대로 돌았다).
   같은 위치의 `/etc/klid/backend.env` 는 **베어메탈 형상용**이라 WAS 는 읽지 않는다(값은 서로 같게 유지).
1-1. **★ 주소 항목을 먼저 맞춘다** — 전체 목록은 [04-configuration.md 「주소 한 표」](04-configuration.md) 참고.
   특히 **`AI_SERVER_URL`** 은 기본값이 loopback 이라 **2대 구성에서 반드시 틀리다.** 틀려도
   기동·헬스체크는 정상이고 **오토라벨링만 조용히 실패**한다. 고친 뒤 주소만 다시 확인:

   ```bash
   sudo KLID_ROLE=app ./scripts/install/21-verify-ai-server-url.sh
   ```

   ⚠ `VLM_SERVICE_URL`(외부 시계열 분석)은 **비워 두는 것이 정상**이다. 미리 채우면 기동은 그대로 되고
   **위탁만 그 엉뚱한 주소로 나가 실패**한다(구 서술 「기동이 막힌다」는 폐기 — 2026-09-03 · ADR-062).
   상세는 [04-configuration.md](04-configuration.md) §`VLM_SERVICE_URL` 참조.
2. `/etc/klid/ai-server.env` 편집 — 보통 기본값으로 충분(yolox CPU).
   ★ **2대 구성이면 `AI_BIND_HOST` 를 서버 B 주소로 바꾸고 방화벽 9300/tcp 을 연다.**
   기본값 `127.0.0.1` 로는 서버 A 에서 닿지 않는다(연결 거부).
3. **★ WAS 설정 이관 — [10-was-settings.md](10-was-settings.md) 의 점검 체크리스트를 끝까지 수행한다.**

   **설정 예시 파일은 [`../config/was/`](../config/was/) 에 있다** — 그대로 베껴 쓰지 말고 현장값에 맞춰 조정한다:
   [`README.md`](../config/was/README.md) ·
   [`standalone.conf.example`](../config/was/standalone.conf.example) ·
   [`standalone-undertow.xml.example`](../config/was/standalone-undertow.xml.example)

   | 옮길 것 | 안 옮기면 |
   |---|---|
   | 업로드 본문 한도 2종(`max-swallow-size`·`max-http-form-post-size`) | **대용량 업로드만** 실패 |
   | 요청 스레드 예산(`threads.max`) | 동시 요청 상한이 어디에도 적혀 있지 않음 |
   | 비동기 요청 타임아웃 | 긴 스트리밍이 중간에 끊김 |
   | 프록시 IP 치환(`proxy-address-forwarding=false`) **확인** — ⚠ 톰캣의 `RemoteIpValve` 와 이름이 다르다 | 신뢰 프록시 대조·시도 횟수 제한이 헤더 한 줄로 우회됨 |

   ⚠ **이 단계는 기동 성공으로 검증되지 않는다.** `server.*` 는 내장 서버 전용이라 WAR 배포에서는
   적용되지 않는데, **적용되지 않아도 기동과 일반 요청은 정상**이다. 배포 시점에 아무 신호가 없고
   현장에서 특정 기능만 깨진다 — 그래서 **대용량 업로드를 실제로 1회 수행**해 통과를 확인한다.
4. **★ WAR 배포** — `/opt/klid/app/api.war` 를 WAS 배포 디렉터리로 **사람이** 복사한다.
   `install.sh` 는 `/opt/klid/app` 에 **두기만** 하고 WAS 배포 디렉터리로 옮기지 않는다.
   ⚠ **구 규칙 폐기(2026-09-04): "파일명이 곧 컨텍스트이므로 rename 금지".** EAP 에서는 WAR 안
   `WEB-INF/jboss-web.xml` 이 컨텍스트를 `/api` 로 고정하므로 파일명은 무엇이든 된다(현장은
   `klid-at-api.war`). `server.servlet.context-path` 는 여전히 적용되지 않는다.
   ⚠ EAP 밖의 컨테이너에 올린다면 그때는 파일명이 컨텍스트를 정한다 — `api.war` 를 유지할 것.
   상세: [10-was-settings.md](10-was-settings.md) §7.
5. **★ `/etc/klid/was.env` 에 WAS 현장값을 적는다** — **기동 방식(`WAS_CTL`)** · `WAS_HOME` ·
   배포 디렉터리 · 로그 경로 · 실행 계정. 설치가 **빈 값 템플릿으로 만들어 둔다**(이미 있으면 보존).

   왜 필요한가: 백엔드는 **systemd 유닛이 아니다**(외부 WAS 가 기동 주체). 그래서 "백엔드를
   재기동하라"·"백엔드 로그를 보라"가 장비마다 다른 명령이 되고,
   [운영 런북](09-operations-runbook.md)은 그 차이를 이 파일의 함수로 흡수한다:

   ```bash
   source /etc/klid/was.env
   was_restart          # 그 밖에 was_start · was_stop · was_status
   ```

   ⚠⚠ **`WAS_CTL` 부터 판정한다 — WAS 가 systemd 로 뜬다고 전제하지 말 것.** 현장
   (`klid-ai-gen-was-01`~`04`)은 `kill` + `<JBOSS_HOME>/bin/start.sh` 로 띄우고 있어
   `WAS_CTL=script` 다(2026-09-09 실측). 판정 절차는 런북 §0-1 ①.

   ⚠ **이 파일을 `tee` 로 통째로 새로 쓰지 말 것** — 위 함수 정의가 함께 들어 있어 덮어쓰면
   런북의 백엔드 명령이 전부 죽는다. **편집기로 값만 채운다.**

   **비워 두면 런북의 백엔드 조작 명령이 무엇이 비었는지 알리고 멈춘다.**

   ⚠ **앱 설정 파일이 아니다.** 앱도 WAS 도 이 파일을 읽지 않는다 — 셸이 읽는다.
   DB 비밀번호·`JWT_SECRET` 같은 **비밀값을 여기 넣지 말 것**(운영자가 읽고 고쳐야 하는
   파일이라 권한을 조이지 않는다). 앱 설정은 `application.properties`(WAR 형상) ·
   `backend.env`(베어메탈 형상)다.
6. DB 준비 확인 — 현장이 준비한 빈 데이터베이스·앱 계정에 접속되는지, 그리고 16 단계가 스키마를
   적재했는지(또는 이미 적재돼 있어 건너뛰었는지) **테이블 개수로** 확인한다. 기동 성공은 근거가 아니다.
7. **ffmpeg 전제조건** — 19단계가 통과했는지 확인한다(위 「ffmpeg」 절). 중단됐다면 그 절을 따른다.
8. 05-run-verify.md 로 기동·검증. **유닛은 `klid-ai-server`(서버 B)와 `httpd`(서버 A) 둘뿐**이며
   백엔드는 WAS 가 띄운다.

> ⚠ 구 제목 폐기(2026-08-30) — "설치 후 즉시 할 일". 3·4·5 는 "즉시 할 일"이 아니라
> **하지 않으면 형상이 성립하지 않는 설치 단계**다.

## 시스템 의존성이 번들에 없을 때 (수동 보강)

정상 패키지라면 시스템 RPM 이 번들되어 있어 자동 설치된다. 번들이 비어 install 이 경고만 내면:
(⚠ 구 서술 폐기(2026-08-28) — "ffmpeg(정적)")

- **ffmpeg 예비물 누락(`syspkgs/ffmpeg/` 비어 있음)**: 빌드머신에서 `50-collect-syspkgs.sh` 를
  다시 실행해 채운다(`SKIP_FFMPEG=1` 로 껐다면 그것을 빼고 실행).
  ⚠ **ffmpeg 는 원래 자동 설치 대상이 아니다** — 예비물이 없다는 것과 대상 장비에 ffmpeg 가
  없다는 것은 다른 문제다. 후자는 관제팀 설치 또는 `install/install-ffmpeg.sh` 로 해결한다.
  설치 경로가 표준과 다르면 `application.properties`(또는 `backend.env`)의
  `FFMPEG_BIN`/`FFPROBE_BIN` 에 절대경로를 지정한다 — **19단계 검증도 그 값을 따라간다.**
- **RPM(libGL/glib2) 누락**: 사내 미러가 있으면 `sudo dnf install -y mesa-libGL libglvnd-glx glib2`.
  폐쇄망이면 el8 컨테이너에서 `dnf download --resolve --archlist=x86_64,noarch` 로 받아 `syspkgs/rpm/` 에 채워 재설치.
- ⚠ **구 항목 폐기(2026-09-05)** — *"PG16 RPM 누락(`syspkgs/postgresql/` 비어 있음)"*.
  **데이터베이스는 반입물이 아니다.** 매체에 그 디렉터리가 없는 것이 정상이며, 있어도 설치가 쓰지 않는다.
  데이터베이스는 현장이 제공한다 — 접속이 안 되면 `CONTROL_DB_*` 주소·계정을 현장 담당과 확인한다.

ffmpeg/ffprobe 가 없으면 backend FFmpegStep(프레임추출·duration)이, libGL.so.1 이 없으면 ai-server opencv 가 실패한다.
⚠ `mesa-libGL`·`libglvnd-glx` 를 "opencv headless 로 바꾸면 필요 없다"며 빼지 말 것 —
`supervision`·`trackers` 가 `opencv-python`(GUI 판)을 직접 의존해 되끌어오므로 `libGL.so.1` 의존이 남는다(실측).
