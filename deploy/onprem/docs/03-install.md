# 03. [대상 서버] 오프라인 설치

폐쇄망 **레드햇 엔터프라이즈 리눅스 8.9** 대상 서버에서 root 로 실행한다. **외부 네트워크 호출이 전혀 없다**(모든 의존성은 번들).

> ★ **이 스크립트만으로는 설치가 끝나지 않는다** (배포 형상 = 외부 WAS 에 WAR 반입,
> @design DEPLOY-001 · RUNBOOK-001). backend 는 `api.war` 를 배치만 하고 **기동하지 않는다** —
> WAS 배포와 **WAS 설정 이관**은 아래 「설치 후 필수 단계」의 사람 작업이다.
> 자바 런타임은 반입하지 않는다(대상 WAS 가 Java 17 로 이미 돌고 있다).

## 서버 2대 — 무엇을 어디에 올리나

대상 장비는 **2대**다. **매체는 하나이고, 설치할 때 역할만 고른다**(패키지를 물리적으로 쪼개지 않는다).

| | **서버 A (`--role=app`)** | **서버 B (`--role=ai`)** |
|---|---|---|
| 구성 | httpd(정적 서빙 + `/api` 프록시) · 외부 WAS 에 `api.war` · (옵션)PostgreSQL·DB 초기화 | ai-server(번들 파이썬 + 오프라인 휠 + YOLOX/SAM2 모델) |
| 도는 단계 | 10 · 12 · 14 · 15 · 16 · 17 · 19 | 11 · 13 |
| systemd 유닛 | `httpd` — **백엔드는 유닛이 아니다**(외부 WAS 가 기동 주체) | `klid-ai-server` |
| 쓰는 반입물 | `artifacts/backend` · `artifacts/frontend/dist` · `syspkgs/{rpm,gpg,postgresql,ffmpeg,ffmpeg-src}` | `vendor/wheels` · `models/weights` · `runtimes/python` · `syspkgs/{rpm,gpg}` |
| ffmpeg | **전제조건** — 관제지원시스템 팀이 설치한다. 우리는 **검증만** 한다(19단계) | 쓰지 않는다(ai-server 에 `ffmpeg`/`ffprobe` 호출 0건) |
| GPU | 해당 없음 | **장비에 GPU 가 있으나 이번 반입은 CPU 전용**이다(torch CPU 휠) |

> `syspkgs/rpm` 이 **양쪽 모두**인 이유: 서버 A 는 `httpd`·`policycoreutils-python-utils`,
> 서버 B 는 `mesa-libGL`·`libglvnd-glx`·`glib2` 를 같은 로컬 저장소에서 꺼내 쓴다.
>
> ⚠ **역할을 주지 않으면 종전대로 전체가 설치된다**(`--role=all` 이 기본). 기존 단일 서버 절차는
> 그대로 유효하며, 이 분리로 깨지지 않는다.

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
# 외부(기존) PostgreSQL 사용 — 번들 PG16 설치 생략
sudo USE_BUNDLED_POSTGRES=0 ./scripts/install.sh --role=app

# DB 자동 생성 단계 생략(DBA 가 이미 준비한 경우)
sudo SKIP_DB_INIT=1 ./scripts/install.sh --role=app

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

## 단계별 동작

| 단계 | 역할 | 스크립트 | 동작 |
|------|:----:|----------|------|
| 0 | 공통 | `install.sh` | `klid` 사용자/그룹 + 디렉토리 생성, **역할별** SHA256 무결성 검증 |
| 1 | app | `install/10-install-postgresql.sh` | **(옵션·기본 ON)** 번들 PG16 RPM 오프라인 설치 + initdb + `postgresql.conf`/`pg_hba.conf` + `postgresql-16` 기동. `USE_BUNDLED_POSTGRES=0` 이면 전체 스킵(외부 PG) |
| 2 | **ai** | `install/11-install-runtimes.sh` | **Python**(ai-server 용) + **RPM**(mesa-libGL/libglvnd-glx/glib2) 오프라인 설치. ⚠ 구 동작 폐기(2026-08-30): "**ffmpeg RPM 도 여기서 자동 설치**" — ffmpeg 는 서버 A 의 전제조건이라 자동 설치하지 않는다 |
| 3 | app | `install/12-install-backend.sh` | **`api.war` 배치**(`/opt/klid/app/api.war`) + `application.properties`/`backend.env` + **`was.env`**(WAS 현장값 기록 파일). **기동하지 않는다** — WAS 배포·WAS 설정은 사람 작업. 베어메탈 형상(jar + systemd 유닛)은 `INSTALL_BACKEND_SYSTEMD_UNIT=1` 일 때만 |
| 4 | **ai** | `install/13-install-ai-server.sh` | venv + `pip --no-index` 설치 + 모델 배치 + 유닛(`klid-ai-server`) |
| 5 | app | `install/14-install-frontend.sh` | dist 배치 + **런타임 설정 정본(`/etc/klid/frontend.env`) 배치 + `klid-config.js` 생성**(★ 상위 로그인 주소가 비면 **여기서 설치가 멈춘다**) + httpd RPM 설치 + `conf.d` 드롭인 + SELinux 문맥·불리언 + `httpd` 기동 |
| 6 | app | `install/15-init-db.sh` | (옵션) control/portal **DB·유저** 생성 안내 또는 수행 (테이블 생성 아님) |
| 7 | app | `install/16-load-schema.sh` | (옵션) control DB 에 `db/schema.sql` 로드. `SCHEMA_LOAD_RUN=1` 일 때만 실제 로드, 아니면 수동 안내만. 테이블이 이미 있으면 **로드 생략**(멱등 가드) |
| 8 | app | `install/17-load-portal-schema.sh` | (옵션) portal DB 에 `db/portal-schema.sql` 로드. 조건·가드는 7 과 같다. 빠뜨리면 메타 복제가 **조용히 0건**으로 유지된다. ⚠ **포털을 반입하지 않아 portal DB 가 없으면 이 단계가 접속 실패로 설치를 중단시킨다** — `SKIP_PORTAL_SCHEMA_LOAD=1` 로 생략하고 `META_REPLICATION_ENABLED=false` 를 둔다([04-configuration.md](04-configuration.md) D-4) |
| 9 | app | `install/19-verify-ffmpeg.sh` | **ffmpeg·ffprobe 전제조건 검증**. 없으면 **설치를 중단한다**(아래 「ffmpeg」 절) |
| 10 | app | `install/20-verify-frontend-config.sh` | 프론트 런타임 설정 최종 게이트. 상위 로그인 주소가 비면 **설치를 실패로 종결한다** |
| 11 | app | `install/21-verify-ai-server-url.sh` | **AI 추론 서버 주소 확인.** 2대 구성인데 기본값(loopback)이 남아 있으면 경고한다. ★ **설치를 실패시키지 않는다**(경고만) — 04 「주소 한 표」 ① 참고 |

> **`install/install-ffmpeg.sh` 는 이 표에 없다** — 번호 접두가 없는 것이 그 표식이며,
> `install.sh` 가 **호출하지 않는다**. 사람이 명시적으로 부를 때만 도는 수동 명령이다.

> **PG vs DB/유저 vs 테이블 — 역할 분담**: 단계 1(`10`)은 **PG 엔진 설치+기동**, 단계 6(`15`)은
> **control/portal DB·앱 유저 생성**, 단계 7·8(`16`·`17`)은 **테이블/스키마 준비**다.
> 셋은 중복 없이 연계된다.
>
> **★ 테이블은 전체 스키마 SQL 로드가 만든다 — 온프렘은 Flyway 를 쓰지 않는다.**
> 경로는 **하나뿐이다.** 설치 단계 `16`·`17` 이 `db/schema.sql`·`db/portal-schema.sql` 을 **1회 로드**하고,
> 앱은 마이그레이션을 **수행하지 않는다**. 이것은 **의도적 결정**이며, 전체 스키마 SQL 을 따로
> 만들어 둔 이유가 그것이다(반입 명세 `DEPLOY-001` — DBA 가 배포 전에 스키마 정의 파일을 1회 적용하고
> 애플리케이션은 마이그레이션을 수행하지 않는다). 2노드 동시 기동 시의 Flyway 락 경합·최초 부팅 지연도
> 함께 사라진다.
>
> ⚠ **앱 코드 자체의 기본값은 켬(`true`)이다.** 꺼진 상태는 **매체가 만든다** — `config/backend/` 의 두
> 템플릿이 끄는 줄을 갖고 있고 설치(`12-install-backend.sh`)가 그대로 `/etc/klid/` 에 복사한다
> (WAR 형상은 `application.properties` 의 `spring.flyway.enabled=false`, 베어메탈 형상은 `backend.env` 의
> `SPRING_FLYWAY_ENABLED=false`). **그 한 줄을 지우면 앱 기본값이 되살아나 켜진다 — 지우지 말 것.**
> 형상마다 키 이름이 다르고 한쪽에서는 조용히 무시되는 함정은
> [04-configuration.md 「키 이름 변환 규칙」](04-configuration.md) 이 정본이다(여기서 되풀이하지 않는다).
>
> **★ 그런데 그 유일한 경로가 옵트인 플래그 뒤에 있다 — 누가 언제 만드는지 확인할 것.**
>
> | 구성 | 테이블을 만드는 주체 | 언제·무엇으로 |
> |---|---|---|
> | 외부 기존 PG (`USE_BUNDLED_POSTGRES=0`) | **DBA**(설치 전) | `psql -f db/schema.sql` 을 사람이 1회 실행. `16` 단계는 그 절차를 **안내만** 한다 |
> | 번들 PG16 (`USE_BUNDLED_POSTGRES=1`, 기본) | **설치 실행자**(설치 중 또는 직후) | 같은 파일을 `16` 단계가 로드한다 — 단 **`SCHEMA_LOAD_RUN=1` 을 줄 때만**. 안 주면 안내만 출력하고 넘어간다 |
>
> ⚠ **번들 PG 를 써도 자동으로 로드되지 않는다.** `15`(DB·유저 생성)도 `16`·`17`(스키마 로드)도 전부
> 옵트인이다(`DB_INIT_RUN=1` / `SCHEMA_LOAD_RUN=1`, 그리고 `psql` 이 있을 때만). 기본 실행은 **수동 절차를
> 출력하고 성공으로 끝난다.** 그래서 "설치는 끝났는데 테이블이 하나도 없는" 상태가 조용히 만들어질 수
> 있고, **Flyway 가 없으므로 뒤에서 대신 만들어 주는 것이 없다.** 그대로 WAR 를 올리면 앱은
> **기동에는 성공한다** — 그리고 화면·배치가 DB 를 처음 건드릴 때 전부 깨진다.
> `SKIP_DB_INIT=1` 로 설치하면 `15`~`17` 이 **실행되지도 않으므로**(안내 출력조차 없다)
> 더 조용하다 — 어느 경우든 아래 확인이 유일한 신호다.
>
> ⚠⚠ **`ddl-auto=validate` 가 대신 막아 주지 않는다.** `application.yml` 에 `validate` 가
> 선언돼 있지만 이 저장소에서는 **실동작하지 않는다**(듀얼 데이터소스라 `JpaBuilderConfig` 가
> `EntityManagerFactory` 를 직접 만들고 `spring.jpa.hibernate.ddl-auto` 가 Hibernate 까지
> 전달되지 않는다). 그래서 **기동 로그가 깨끗해도 스키마가 비어 있을 수 있다.**
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

# 한 단계만 실행 — 번호로 부른다
sudo KLID_ROLE=app ./scripts/install-step.sh 14

# 스크립트를 직접 불러도 결과는 같다(러너는 얇은 껍데기다)
sudo KLID_ROLE=app ./scripts/install/14-install-frontend.sh
```

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
| 1 | `10-install-postgresql.sh` | app | 번들 PG16 설치 + initdb + 기동 | 0 | 안전(설치·initdb 각각 가드) |
| 2 | `11-install-runtimes.sh` | ai | Python 런타임 + RPM 의존성. `runtime/runtime.env` 기록 | 0 | 안전(대상 디렉터리 교체) |
| 3 | `12-install-backend.sh` | app | `api.war` 배치 + 설정 템플릿 3종 | 0 | 안전(설정 파일 보존) |
| 4 | `13-install-ai-server.sh` | ai | venv + 오프라인 휠 + 모델 + 유닛 | **2**(`runtime.env` 필요) | 안전(설정 보존, 앱 소스 교체) |
| 5 | `14-install-frontend.sh` | app | dist 배치 + httpd + 드롭인 + 설정 생성 | 0 | 안전. ⚠ **httpd 드롭인은 매번 재생성**(손편집 소실) |
| 6 | `15-init-db.sh` | app | control/portal DB·유저 생성 | 1 또는 외부 PG 접속 가능 | 안전(존재 시 생략) |
| 7 | `16-load-schema.sh` | app | control 스키마 로드 | 6 · `SCHEMA_LOAD_RUN=1` | 안전(테이블 있으면 생략) |
| 8 | `17-load-portal-schema.sh` | app | portal 스키마 로드 | 6 · `SCHEMA_LOAD_RUN=1` | 안전(테이블 있으면 생략). **portal DB 자체가 없으면 중단** → `SKIP_PORTAL_SCHEMA_LOAD=1` |
| 9 | `19-verify-ffmpeg.sh` | app | ffmpeg 전제조건 검증 | 없음 | 안전(검증만) |
| 10 | `20-verify-frontend-config.sh` | app | 프론트 설정 게이트 | **5** | 안전(검증·생성) |
| 11 | `21-verify-ai-server-url.sh` | app | AI 서버 주소 확인 | **3** | 안전(검증만) |

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
   ⚠ **`ADMIN_CLAIM_PASSWORD_HASH` 는 빠뜨려도 아무 신호가 없다.** 다른 비밀값은 비면 해당
   기능이 곧바로 실패하거나(스트림 서명) 부팅이 막히는데(prd 의 webhook HMAC), 이것만은 기동도
   헬스체크도 스모크도 전부 통과하고 **관리자 권한 부여 창구만 항상 401** 이라 아무도 관리자가
   되지 못한다. BCrypt 해시(cost 12 이상)만 넣는다 — 평문을 넣으면 기동이 실패한다.
   생성은 04 의 치트시트: `htpasswd -bnBC 12 "" '평문' | tr -d ':\n'`
   WAS 기동 옵션에 `-Dspring.config.additional-location=file:/etc/klid/` 와
   `-Dspring.profiles.active=prd` 를 넣고, JVM 옵션(MaxRAMPercentage·G1GC·egd)은 `CATALINA_OPTS` 로 옮긴다.
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

   ⚠ `VLM_SERVICE_URL`(외부 시계열 분석)은 **비워 두는 것이 정상**이다. 미리 채우면 기동이 막힌다.
2. `/etc/klid/ai-server.env` 편집 — 보통 기본값으로 충분(yolox CPU).
   ★ **2대 구성이면 `AI_BIND_HOST` 를 서버 B 주소로 바꾸고 방화벽 9300/tcp 을 연다.**
   기본값 `127.0.0.1` 로는 서버 A 에서 닿지 않는다(연결 거부).
3. **★ WAS 설정 이관 — [10-was-settings.md](10-was-settings.md) 의 점검 체크리스트를 끝까지 수행한다.**

   **설정 예시 파일은 [`../config/was/`](../config/was/) 에 있다** — 그대로 베껴 쓰지 말고 현장값에 맞춰 조정한다:
   [`README.md`](../config/was/README.md) ·
   [`setenv.sh.example`](../config/was/setenv.sh.example) ·
   [`context-api.xml.example`](../config/was/context-api.xml.example) ·
   [`server-connector.xml.example`](../config/was/server-connector.xml.example)

   | 옮길 것 | 안 옮기면 |
   |---|---|
   | 업로드 본문 한도 2종(`max-swallow-size`·`max-http-form-post-size`) | **대용량 업로드만** 실패 |
   | 요청 스레드 예산(`threads.max`) | 동시 요청 상한이 어디에도 적혀 있지 않음 |
   | 비동기 요청 타임아웃 | 긴 스트리밍이 중간에 끊김 |
   | 프록시 IP 치환 밸브(`RemoteIpValve`) **부재 확인** | 신뢰 프록시 대조·시도 횟수 제한이 헤더 한 줄로 우회됨 |

   ⚠ **이 단계는 기동 성공으로 검증되지 않는다.** `server.*` 는 내장 서버 전용이라 WAR 배포에서는
   적용되지 않는데, **적용되지 않아도 기동과 일반 요청은 정상**이다. 배포 시점에 아무 신호가 없고
   현장에서 특정 기능만 깨진다 — 그래서 **대용량 업로드를 실제로 1회 수행**해 통과를 확인한다.
4. **★ WAR 배포** — `/opt/klid/app/api.war` 를 WAS 배포 디렉터리로 **사람이** 복사한다.
   `install.sh` 는 `/opt/klid/app` 에 **두기만** 하고 WAS 배포 디렉터리로 옮기지 않는다.
   **파일명을 바꾸지 말 것** — WAR 배포에서 컨텍스트는 파일명이 정하고, 현재 API 주소가 전부
   `/api` 하위다(`server.servlet.context-path` 는 적용되지 않는다).
5. **★ `/etc/klid/was.env` 에 WAS 현장값을 적는다** — 유닛명(`WAS_UNIT`) · `WAS_HOME` ·
   배포 디렉터리 · 로그 경로 · 실행 계정. 설치가 **빈 값 템플릿으로 만들어 둔다**(이미 있으면 보존).

   왜 필요한가: 백엔드는 **systemd 유닛이 아니다**(외부 WAS 가 기동 주체). 그래서 "백엔드를
   재기동하라"·"백엔드 로그를 보라"가 장비마다 다른 명령이 되고,
   [운영 런북](09-operations-runbook.md)은 그 차이를 이 파일로 흡수한다:

   ```bash
   source /etc/klid/was.env 2>/dev/null || WAS_UNIT='<WAS 유닛명>'
   sudo systemctl restart "${WAS_UNIT}"
   ```

   **비워 두면 런북의 백엔드 조작 명령이 전부 자리표시자로 남는다.**

   ⚠ **앱 설정 파일이 아니다.** 앱도 WAS 도 이 파일을 읽지 않는다 — 셸이 읽는다.
   DB 비밀번호·`JWT_SECRET` 같은 **비밀값을 여기 넣지 말 것**(운영자가 읽고 고쳐야 하는
   파일이라 권한을 조이지 않는다). 앱 설정은 `application.properties`(WAR 형상) ·
   `backend.env`(베어메탈 형상)다.
6. DB 준비 확인 — `15-init-db.sh` 출력의 DDL 또는 DBA 준비 결과.
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
- **PG16 RPM 누락(`syspkgs/postgresql/` 비어 있음)**: 타깃에 이미 PG 가 있으면
  `sudo USE_BUNDLED_POSTGRES=0 ./scripts/install.sh` 로 외부 PG 를 쓴다. 번들이 필요하면
  el8 컨테이너에서 PGDG(EL-8) repo 추가 후 `dnf download --resolve --archlist=x86_64,noarch` 로 받아
  `syspkgs/postgresql/` 에 채워 재실행한다(02-build-package.md / 55-collect-postgresql.sh 참고).

ffmpeg/ffprobe 가 없으면 backend FFmpegStep(프레임추출·duration)이, libGL.so.1 이 없으면 ai-server opencv 가 실패한다.
⚠ `mesa-libGL`·`libglvnd-glx` 를 "opencv headless 로 바꾸면 필요 없다"며 빼지 말 것 —
`supervision`·`trackers` 가 `opencv-python`(GUI 판)을 직접 의존해 되끌어오므로 `libGL.so.1` 의존이 남는다(실측).
