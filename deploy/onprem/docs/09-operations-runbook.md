# 09. 운영 런북 (관리자 매뉴얼 — 장애 조치·프로세스 상태 확인)

> 이 문서는 **설치 완료 후 운영 단계**의 관리자용 조치 절차다. 설치 시점의 실패 대응은
> [06-troubleshooting.md](06-troubleshooting.md)(설치 트러블슈팅)를 보고, 여기서는 **가동 중 시스템의
> 프로세스 상태 확인**과 **장애 발생 시 조치사항**을 다룬다. 아키텍처 레벨의 장애 대응 원리
> (감지→차단→복구→통지·기록)는 `docs/design/D5-아키텍처설계서-Rev2.md §3 [가용성·장애복구]` 를 따른다.

## 대상 서비스·프로세스 요약

| 서비스(유닛) | 역할 | 포트 | 헬스 | 로그 |
|---|---|---|---|---|
| `postgresql-16` | 데이터베이스(번들 PG16) | 5432 | `systemctl is-active postgresql-16` | `journalctl -u postgresql-16` |
| `klid-ai-server` | 내부 추론 서버(YOLO/SAM2) | 9300 | `curl http://127.0.0.1:9300/health` | `journalctl -u klid-ai-server` |
| **`<WAS 유닛명>`** | **애플리케이션(backend)·배치 스케줄러 — 외부 WAS 가 `api.war` 를 기동** | 8080(`/api`) | `curl http://127.0.0.1:8080/api/actuator/health/liveness` | **WAS 로그**(`journalctl -u <WAS 유닛명>` + `<WAS_LOG_DIR>/catalina.*`) |
| `httpd` | 웹서버(Apache httpd·정적 서빙 + `/api` 프록시) | 80 | `curl -o /dev/null -w '%{http_code}' http://127.0.0.1/` | `journalctl -u httpd` |

- 기동 의존 순서: **PostgreSQL → ai-server → backend(WAS) → frontend(httpd)**.
  ⚠ 앞의 셋과 달리 **backend 의 기동 순서는 우리 유닛이 갖고 있지 않다** — WAS 소관이다(0절).
- 설치 경로: `/opt/klid/{app,ai,web,runtime}` · 환경설정 `/etc/klid/` · 데이터 `/var/lib/klid` · 로그 `/var/log/klid`.

---

## 0. 이 문서를 읽기 전에 — 백엔드만 조작 방식이 다르다

확정 배포 형상은 **외부 WAS(Tomcat 10.1) 에 `api.war` 반입**이다(@design DEPLOY-001 · RUNBOOK-001).
**백엔드에는 `klid-backend.service` 가 없다.** 나머지(PostgreSQL·ai-server·httpd)는 종전대로 systemd 다.

| 구성요소 | 기동 주체 | 재기동 | 로그 |
|---|---|---|---|
| PostgreSQL | systemd | `sudo systemctl restart postgresql-16` | `journalctl -u postgresql-16` |
| **ai-server** | **systemd**(그대로) | `sudo systemctl restart klid-ai-server` | `journalctl -u klid-ai-server` |
| **httpd** | **systemd**(그대로) | `sudo systemctl restart httpd` | `journalctl -u httpd` |
| **backend** | **외부 WAS** | `sudo systemctl restart <WAS 유닛명>` | WAS 로그(아래 0-2) |

> ⚠ **셋이 같은 방식이라고 읽지 말 것.** 이 문서의 명령 중 `klid-ai-server`·`httpd`·`postgresql-16`
> 은 그대로 유효하고, **백엔드에 대한 것만** 아래 규칙으로 치환된다.

### 0-1. 현장값을 먼저 적어 둔다 (`/etc/klid/was.env`)

이 문서의 `<WAS 유닛명>`·`<WAS_HOME>`·`<WAS_BASE>`·`<WAS_LOG_DIR>` 은 **자리표시자**다. WAS 는 고객이
이미 운영 중인 것이라 우리가 값을 모른다. **설치 시점에 그 값을 한 곳에 적어 두지 않으면, 장애 대응
때마다 사람이 WAS 를 뒤져 찾아야 한다.**

```bash
# 설치 담당이 1회 작성한다. 이후 이 문서의 명령은 이 값을 읽어 쓴다.
sudo tee /etc/klid/was.env >/dev/null <<'EOF'
WAS_UNIT=<WAS 유닛명>          # systemctl 로 다루는 WAS 서비스 유닛명 (예: tomcat)
WAS_HOME=<WAS_HOME>            # CATALINA_HOME — bin/setenv.sh 가 놓이는 곳
WAS_BASE=<WAS_BASE>            # CATALINA_BASE — webapps/·conf/·logs/ 가 있는 곳(분리 안 했으면 WAS_HOME 과 동일)
WAS_LOG_DIR=<WAS_LOG_DIR>      # 보통 <WAS_BASE>/logs
EOF
sudo chmod 0644 /etc/klid/was.env      # 비밀값 아님 — 경로·유닛명만 적는다. 시크릿을 넣지 말 것
```

```bash
# 사용 예 — 이 문서의 backend 조작은 전부 이 형태로 쓸 수 있다
source /etc/klid/was.env
sudo systemctl restart "$WAS_UNIT"
```

> ⚠ **이 파일은 설치 스크립트가 만들지 않는다** — WAS 가 설치 대상이 아니라 전제이기 때문이다.
> **운영 관례이지 프로그램이 읽는 설정이 아니다**(애플리케이션·`install.sh` 어느 쪽도 참조하지 않는다).
> 작성돼 있지 않으면 이 문서의 자리표시자를 눈으로 치환해 쓴다.
> 설치 기록(작업 대장·인수인계 문서)에도 같은 4개 값을 남긴다 — 서버가 재구축되면 이 파일은 사라진다.

### 0-2. 백엔드 로그는 두 군데다

| 무엇 | 어디 |
|---|---|
| WAS 자신의 기동·배포·컨텍스트 오류 | `<WAS_LOG_DIR>/catalina.out` · `catalina.<날짜>.log` · `localhost.<날짜>.log` |
| WAS 유닛이 표준출력을 journald 로 넘기는 구성이면 그쪽에도 | `journalctl -u <WAS 유닛명>` |
| 애플리케이션 파일 로그(파일 로깅 활성 시) | `/var/log/klid` |

**애플리케이션 기동 실패(설정 누락·DB 접속 실패)는 대개 `catalina.*` 가 아니라 `localhost.*` 에 남는다** —
컨텍스트 초기화 실패이기 때문이다. `catalina.*` 만 보고 "로그에 아무것도 없다"고 판단하지 말 것.

### 0-3. 베어메탈 토글(`java -jar` + systemd)로 운영하는 경우

`install.sh` 를 `INSTALL_BACKEND_SYSTEMD_UNIT=1` 로 돌린 형상에서는 백엔드가 `klid-backend.service` 로
뜬다. **그 형상에서는 이 문서의 옛 명령(`systemctl restart klid-backend` · `journalctl -u klid-backend`)이
그대로 유효**하므로, 아래 각 절에 「베어메탈 토글일 때」로 병기해 보존했다.

> ⚠ 그 형상은 **자바가 따로 필요하다** — 패키지는 2026-08-30 부터 JRE 를 반입하지 않아
> 유닛의 `ExecStart` 가 가리키는 `/opt/klid/runtime/jre` 가 **없는 경로**다. 되살리는 방법은
> `config/systemd/klid-backend.service` 헤더 주석 참조.

---

## 1. 프로세스 상태 확인 (일상 점검)

### 1-1. 서비스 상태 한눈에

```bash
source /etc/klid/was.env 2>/dev/null || WAS_UNIT='<WAS 유닛명>'
systemctl status postgresql-16 klid-ai-server httpd "$WAS_UNIT" --no-pager
# 각 유닛이 active (running) 인지 확인. 실패 시 Active: failed / activating 로 표시된다.
# ★ backend 는 WAS 프로세스 안에 있다 — WAS 유닛이 active 여도 애플리케이션 배포는 실패해 있을 수
#   있으므로, 1-2 의 liveness 까지 통과해야 backend 가 살아 있다고 판정한다.
#   (베어메탈 토글일 때는 "$WAS_UNIT" 대신 klid-backend 를 넣는다.)
```

### 1-2. 헬스체크(스모크)

```bash
curl -fsS http://127.0.0.1:9300/health                              # ai-server → {"status":"ok"}
curl -fsS http://127.0.0.1:8080/api/actuator/health/liveness        # backend  → {"status":"UP"}
curl -fsS http://127.0.0.1:8080/api/actuator/health                 # backend  전체(readiness·연동 상태)
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1/        # frontend → 200
curl -fsS http://127.0.0.1/api/actuator/health/liveness             # 프론트→백엔드 프록시 경로
```

> `/api/actuator/health` 응답의 `components` 에 DB·연동별 HealthIndicator 상태가 포함된다.
> `DOWN` 컴포넌트가 있으면 해당 연동(2절)을 점검한다.

### 1-3. DB 접속·스키마 확인

```bash
systemctl is-active postgresql-16
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system -c '\conninfo'
# 저작도구 스키마 존재·객체 수 (기본 klid_at — DB_SCHEMA 로 변경 가능)
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "select count(*) from information_schema.tables where table_schema='klid_at';"
# Flyway 마이그레이션 이력(스키마 정상 반영 여부) — Flyway 부트스트랩 구성일 때만 존재
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "select version, description, success from klid_at.flyway_schema_history order by installed_rank desc limit 5;"
```

> **★ 저작도구 객체는 `klid_at` 스키마에 있다.** psql 기본 `search_path` 는 `"$user", public` 이라
> 스키마를 명시하지 않은 조회는 `relation ... does not exist` 로 실패한다. 이 문서의 모든 조회는
> `klid_at.` 로 한정하거나, 세션에서 `set search_path to klid_at;` 를 먼저 실행한다.
> `SPRING_FLYWAY_ENABLED=false`(온프렘 기본) 구성에서는 `flyway_schema_history` 자체가 없다 —
> 스키마는 설치 시 `db/schema.sql` 로드로 준비되며 앱은 마이그레이션을 돌리지 않는다.
> ⚠ `ddl-auto=validate` 는 선언만 돼 있고 **실동작하지 않는다**(§2-5-2 「왜 조용히 실패하나」) —
> **기동 성공은 스키마 정합의 근거가 아니다.**

> **⚠ 아래는 온프렘 형상의 절차가 아니다** — 온프렘은 Flyway 를 쓰지 않는다(위 문단). Flyway 를 켠
> 환경(개발·검증 DB)을 다룰 때만 해당하며, 온프렘 운영 중에 이 상태가 나타나면 그 자체가
> **설정 사고**(끄는 한 줄이 지워졌다는 신호)다.
>
> **Flyway 부트스트랩 모드(`SPRING_FLYWAY_ENABLED=true`) 첫 기동 주의** — 앱은 `baseline-on-migrate=true` +
> **`baseline-version=0`** 으로 동작한다. 관제/인프라가 `MNG_*`·`QRTZ_*` 를 앱보다 먼저 provisioning 한
> **비어있지 않은(non-empty)·flyway 이력 없는** DB 에 첫 기동해도 `V1` 부터 전부 적용되도록 보장하기 위함이다.
> baseline 은 **이력 없는 DB 첫 기동 시에만** 발동하므로 이미 `flyway_schema_history` 가 있는 환경에는 영향이 없다.
>
> **★ 2026-08-13 스쿼시 이후 이 값의 의미가 더 커졌다** — 마이그레이션 180개(V0~V185)가 단일
> **`V1__baseline.sql`(스키마 전량 + 시드 14행)** 로 접혔다. 따라서 baseline-version 을 기본값 1 로 두면
> 위 상황에서 **스키마가 통째로 생성되지 않은 채** `V2` 만 적용돼, 테이블이 하나도 없는 DB 로 뜬다.
> - 증상: 첫 기동 로그에 `Migrating schema ... to version "2"` 만 있고 `V1` SQL row 부재 +
>   `flyway_schema_history` 에 `<< Flyway Baseline >>` row(version=1).
>   ⚠ **기동은 성공한다** — `ddl-auto=validate` 가 실동작하지 않아(§2-5-2) 부팅이 막히지 않고,
>   화면·배치가 DB 를 처음 건드릴 때 `relation ... does not exist` 로 드러난다.
>   구 서술 폐기(2026-08-30): *"직후 `ddl-auto=validate` 가 전면 실패 / validate 가 「table not
>   found」 로 실패"*.
> - 이미 잘못된 baseline 이력으로 멈춘 DB 는 설정만으론 복구되지 않는다 → 해당 DB 의
>   `flyway_schema_history` 를 비우고 재기동(무이력 재적용)하거나 DBA 가 수동 정정한다.
> - **스쿼시 이전에 만들어진 기존 DB** 는 재기동 전에 §2-5-2 이력 이관을 먼저 수행한다.

### 1-4. 배치 파이프라인 상태 확인

배치는 스케줄러가 1건/분으로 유입한다. 적재·전이 상태를 DB로 확인한다.

```bash
# 상태별 영상 건수 (PENDING 적체/PROCESSING 정체/FAILED 누적 감시)
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "select data_stts_cd, count(*) from klid_at.ls_raw_data_status group by data_stts_cd order by 2 desc;"

# 비식별 미완료('F')·대기 영상
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "select raw_sn, de_idnt_yn, data_stts_cd from klid_at.ls_data_raw where de_idnt_yn <> 'Y' order by raw_sn desc limit 20;"
```

### 1-4-1. 이중화(2노드) 스케줄러 클러스터 상태

2노드 Active-Active 는 Quartz 클러스터링(`QUARTZ_CLUSTERED=true`)으로 트리거 중복 발화를 막는다.
클러스터가 성립하지 않으면 배치가 **노드마다 중복 실행**된다(로그·헬스는 정상으로 보인다).

```bash
# 등록된 스케줄러 노드 — 기동한 노드 수만큼 행이 있어야 한다(1노드면 1행)
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "select instance_name, checkin_interval, to_timestamp(last_checkin_time/1000) as last_checkin from qrtz_scheduler_state;"

# 클러스터 락 행 — TRIGGER_ACCESS / STATE_ACCESS 2행
#   ★ 시드 위치는 db/schema.sql(및 V1__baseline.sql)이다. 구 주석의 "V76 시드" 는 폐기 —
#     2026-08-13 스쿼시로 그 번호의 파일 자체가 없다(아카이브에만 있다).
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "select sched_name, lock_name from qrtz_locks;"

# 노드 간 시계 동기(NTP) — 클러스터링의 전제. 두 노드 모두 확인한다.
timedatectl            # NTP service: active
chronyc tracking       # System time offset 이 1초 이내인지
```

- 노드가 떠 있는데 `qrtz_scheduler_state` 에 행이 **0개** → 클러스터링이 꺼져 있다(설정 확인).
  stg/prd 는 `QUARTZ_CLUSTERED=false` 면 기동 자체가 거부되므로, 프로파일/`ENV` 표식부터 확인한다.
- `last_checkin` 이 `checkin_interval` 의 수 배 이상 정체된 행 → 죽은 노드의 잔여 행(다른 노드가 곧 회수).
- 시계 오차가 크면 misfire 오판·중복 발화가 생긴다 → NTP 부터 교정한다.

### 1-5. 자원 상태 (CPU/메모리/GPU/디스크)

```bash
source /etc/klid/was.env 2>/dev/null || WAS_UNIT='<WAS 유닛명>'
systemctl status "$WAS_UNIT" --no-pager | grep -E 'Memory|Tasks'    # backend = WAS 프로세스 메모리
#   ⚠ 이 수치는 WAS 전체다. 같은 WAS 에 다른 애플리케이션이 함께 올라가 있으면 그 몫이 섞인다.
#   (베어메탈 토글일 때: systemctl status klid-backend --no-pager | grep -E 'Memory|Tasks')
df -h /opt/klid /var/lib/pgsql /var/log/klid "$STORAGE_RAW_PATH"     # 디스크 여유(앱·DB·로그·저장소)
# 저장소 경로는 설정값 STORAGE_RAW_PATH(기본 /nas-storage), DB 데이터는 /var/lib/pgsql/16/data
#   설정 파일은 WAR 형상이 /etc/klid/application.properties, 베어메탈 토글이 /etc/klid/backend.env
nvidia-smi                                                          # GPU 사용률(추론 서버, 해당 시)
```

> ⚠ **저장소 루트는 실경로여야 한다 — 점검 항목.**
> ```bash
> for v in STORAGE_RAW_PATH STORAGE_DEIDENTIFIED_PATH; do
>   printf '%s: 설정=%s 실경로=%s\n' "$v" "${!v}" "$(readlink -f "${!v}")"
> done   # 두 값이 다르면 설정 파일(application.properties · 베어메탈 토글은 backend.env)을 실경로로 고친다
> ```
> 설정값이 심링크면 **외부 산출물 이관의 위치 탐색이 고장 난다** — 「상위로」가 항상 비활성되고,
> 탐색이 돌려준 폴더를 그대로 입력칸에 넣어도 `허용된 저장소 범위 밖의 경로입니다`(400)로 거부된다.
> 경로 판정기는 표기 기준으로 먼저 검사하는데 탐색 응답은 실경로를 싣기 때문이며, 판정기를 고치지
> 않고 운영 규약으로 고정한 항목이다. 상세·대상 4종은 `04-configuration.md` §A 참조.

---

## 2. 장애 유형별 조치사항

### 2-1. 서비스 다운 / 비정상 종료

**증상**: `systemctl status` 가 `failed`, 헬스체크 무응답.

```bash
source /etc/klid/was.env 2>/dev/null || { WAS_UNIT='<WAS 유닛명>'; WAS_LOG_DIR='<WAS_LOG_DIR>'; }

# 1) 최근 로그로 원인 확인 — backend 는 WAS 로그다(0-2). 두 군데를 다 본다.
sudo tail -n 200 "$WAS_LOG_DIR"/catalina.out                     # WAS 자신의 기동/배포
sudo tail -n 200 "$WAS_LOG_DIR"/localhost.$(date +%F).log        # ★ 애플리케이션 컨텍스트 초기화 실패는 이쪽
journalctl -u "$WAS_UNIT" -n 200 --no-pager                      # WAS 유닛이 journald 로 넘기는 구성일 때

# 2) 재기동 — backend 재기동 = WAS 재기동
sudo systemctl restart "$WAS_UNIT"                 # 또는 klid-ai-server / httpd / postgresql-16
#    ⚠ 같은 WAS 에 다른 애플리케이션이 있으면 그것도 함께 내려간다. 그 경우 컨텍스트 단위 재배포
#      (WAS 관리 콘솔 또는 api.war 재배치)로 좁힐 수 있는지 WAS 운영 주체와 확인한다.

# 3) 반복 실패 시 환경설정·의존 서비스 확인
systemctl is-active postgresql-16 klid-ai-server   # backend 는 이 둘에 의존
cat /etc/klid/application.properties               # DB 접속·JWT 시크릿·연동 주소 확인(비밀번호 노출 주의)
```

> ⚠ **WAR 형상에는 우리가 건 의존성이 없다.** `Requires=klid-ai-server` + `After=postgresql` 는
> 베어메탈 유닛의 설정이고, 그 유닛은 이 형상에서 설치되지 않는다. **DB·ai-server 가 죽어 있어도
> WAS 는 그냥 뜨고**, 애플리케이션이 기동 중 실패하거나 런타임에 오류를 낸다. 기동 순서(DB →
> ai-server → backend)는 **사람이 지키거나 WAS 유닛에 `After=`/`Requires=` 를 거는 WAS 설정 소관**이다.
>
> 기동이 오래 걸릴 수 있다(스프링 컨텍스트 로딩 · 커넥션 풀 · 2노드 동시 기동 시 `QRTZ_LOCKS`
> 락 조율). WAS 의 배포 타임아웃이 짧으면 정상 기동을 실패로 처리하므로, 컨텍스트 기동 타임아웃
> 여유를 WAS 쪽에서 확인한다.
> ⚠ 구 근거 폐기(2026-08-30) — *"스키마 검증"*. 마이그레이션도 `ddl-auto=validate` 도 기동
> 경로에서 돌지 않으므로(§2-5-2 「왜 조용히 실패하나」) 그 둘은 기동 시간의 요인이 아니다.
>
> **베어메탈 토글일 때**: 위 3줄은 `journalctl -u klid-backend -n 200 --no-pager` /
> `sudo systemctl restart klid-backend` / `cat /etc/klid/backend.env` 이고, 유닛이
> `Requires=klid-ai-server` + `After=postgresql` 를 걸어 두므로 의존 서비스가 죽으면 backend 도 뜨지 않는다
> (`TimeoutStartSec=600`).

### 2-2. 배치 적체 / 정체 (파이프라인이 진행되지 않음)

**증상**: `ls_raw_data_status` 의 `PENDING`/`PROCESSING` 건수가 계속 증가.

- **원인 1 — 스케줄러 정지**: backend 재기동. 잡 상태는 PostgreSQL JobStore(`QRTZ_*`)에 영속되어 재기동 시 미완료 잡을 이어 처리한다.
- **원인 2 — 선두 비식별 실패로 정체**: 2-4 참조(비식별이 막히면 이후 단계로 진행 못 함).
- **원인 3 — 추론 서버 병목**: `nvidia-smi` GPU 포화 확인 → ai-server 인스턴스 수평 확장 검토(무상태라 증설 가능).

```bash
source /etc/klid/was.env 2>/dev/null || WAS_LOG_DIR='<WAS_LOG_DIR>'
sudo grep -iE 'batch|schedul|retry|pipeline' "$WAS_LOG_DIR"/catalina.out | tail -50
# (베어메탈 토글일 때: journalctl -u klid-backend --no-pager | grep -iE '...' | tail -50)
```

### 2-3. 외부 연동 실패 / 서킷 브레이커 OPEN

**증상**: `/api/actuator/health` 의 연동 컴포넌트 `DOWN`, 로그에 서킷 OPEN·타임아웃.

- 저작도구는 원격·프로세스 경계 호출에 **타임아웃·재시도(3회·지수 백오프)·서킷 브레이커**를 적용한다
  (비식별·추론 60초, VLM 45초, 관제통지 10초). 서킷 OPEN 은 **장애 전파 차단**이 정상 동작한 것이다.
- **조치**: 상대 시스템(비식별·VLM·증강·관제) 자체 상태를 먼저 확인한다. 상대가 복구되면 서킷은 30초 후 half-open→close 로 자동 회복하고, 실패분은 재처리·재등록 큐로 자동 재시도된다.

```bash
# 연동 대상 도달 확인(설치값 IP/PORT 로 치환)
curl -fsS http://<대상_IP>:<PORT>/        # 또는 대상 제공 헬스 경로
source /etc/klid/was.env 2>/dev/null || WAS_LOG_DIR='<WAS_LOG_DIR>'
sudo grep -iE 'circuit|timeout|resilience|controlnotify|vlm|kpst' "$WAS_LOG_DIR"/catalina.out | tail -50
# (베어메탈 토글일 때: journalctl -u klid-backend --no-pager | grep -iE '...' | tail -50)
```

- **관제 통지 실패**: 실패분은 재등록 큐(dead-letter)에 fallback 저장 후 스케줄 재시도(멱등·요청 ID)된다. 상대 복구만 확인하면 별도 수동 조치 불필요.

### 2-4. 비식별(KPST) 실패

**증상**: 영상이 계속 `DE_IDENT_YN='F'`, 이후 단계 진행 안 됨.

```bash
# https + 사설 CA 도달 확인
curl -fsS --cacert /etc/klid/kpst-ca.crt https://<KPST_IP>:<PORT>/
```

- **원본은 절대 삭제되지 않는다.** 실패 영상은 상태만 `F` 로 표시된다.
- 연동·주소·CA 를 점검(→ [06-troubleshooting.md](06-troubleshooting.md))하고, 필요 시 **외부 비식별 솔루션으로 수동 재비식별** 후 재적재 경로로 처리한다.

### 2-5. 데이터베이스 장애

> **DB 는 외부 인프라 제공(저작도구 미운영)** — DB 서버 장애·재기동·백업은 **DB 운영(인프라) 주체** 소관이다.
> 저작도구 측 조치는 **접속 확인 + backend 커넥션 풀 회복**이다. 아래 `systemctl restart postgresql-16` 은
> **번들 PG 로 단독 구성한 경우에만** 해당한다(외부 DB 는 인프라 주체에 장애 전파·복구 요청).

```bash
# 접속 확인(저작도구 측) — 외부 DB 주소로 치환
PGPASSWORD='<앱_비밀번호>' psql -h "$CONTROL_DB_HOST" -p "$CONTROL_DB_PORT" -U klid_user -d klid_system -c '\conninfo'

# --- 아래는 번들 PG 단독 구성 한정 ---
systemctl status postgresql-16 --no-pager
journalctl -u postgresql-16 -n 100 --no-pager
df -h /var/lib/pgsql/16/data   # 디스크 풀이 흔한 원인(번들 PG 데이터 디렉토리)
sudo systemctl restart postgresql-16
```

- DB 복구 후 backend 를 재기동해 커넥션 풀을 회복시킨다(외부/번들 공통).

### 2-5-1. 스키마 이관 (`public` → `klid_at`) — 기존 DB 1회 작업

> **대상**: 저작도구 객체가 `public` 에 있는 **기존 DB**. 신규 설치는 `db/schema.sql` 로드로 처음부터
> `klid_at` 에 만들어지므로 해당 없다. 설치 스크립트(`16-load-schema.sh`)는 이 상태를 감지하면
> **로드를 거부**한다 — 그대로 로드하면 빈 `klid_at` 이 생기고 데이터는 `public` 에 남기 때문이다.
>
> **이관하지 않고 신 버전을 올리면**: 앱이 `klid_at` 을 빈 스키마로 보고 Flyway 가 `V1` 부터 전량
> 재적용한다. 데이터는 `public` 에 남고 앱은 빈 `klid_at` 을 본다 — **오류가 아니라 조용한 분기**다.

**전제**: backend(2노드 모두) 정지. 작업 중 앱이 붙어 있으면 안 된다.

```bash
source /etc/klid/was.env 2>/dev/null || WAS_UNIT='<WAS 유닛명>'
sudo systemctl stop "$WAS_UNIT"     # 2노드 모두. backend 정지 = WAS 정지(0절)
#   ⚠ WAS 를 통째로 내릴 수 없으면 api.war 컨텍스트만 언디플로이/정지시킨다 —
#     이 절차의 전제는 "앱이 DB 에 붙어 있지 않다" 이지 "WAS 프로세스가 없다" 가 아니다.
#   (베어메탈 토글일 때: sudo systemctl stop klid-backend)

# ① 덤프 백업 (되돌릴 수 있는 지점 확보 — 생략 금지)
sudo -u postgres pg_dump -Fc -d klid_system -f /backup/klid_system.pre-klid_at.dump
```

**② 이동** — `ALTER ... SET SCHEMA`. 앱 유저(소유자)로 실행한다.

```sql
CREATE SCHEMA IF NOT EXISTS klid_at AUTHORIZATION klid_user;

-- 2-pass: 테이블·뷰를 먼저 옮기고(인덱스·제약·트리거·소유 시퀀스가 함께 따라온다),
--         그래도 남은 <독립> 시퀀스만 뒤이어 옮긴다.
--   ★ 시퀀스를 한 루프에서 같이 돌리면 안 된다 — IDENTITY 컬럼 시퀀스는 소유 테이블에 묶여 있어
--     개별 ALTER SEQUENCE 가 "cannot move an owned sequence into another schema" 로 실패한다.
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT c.relname, c.relkind FROM pg_class c
             JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r','v','m')
            ORDER BY c.relkind DESC          -- 뷰(v)보다 테이블(r)을 먼저
  LOOP
    EXECUTE format('ALTER %s public.%I SET SCHEMA klid_at',
                   CASE r.relkind WHEN 'r' THEN 'TABLE' WHEN 'v' THEN 'VIEW'
                                  ELSE 'MATERIALIZED VIEW' END, r.relname);
  END LOOP;

  FOR r IN SELECT c.relname FROM pg_class c
             JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind = 'S'
  LOOP
    EXECUTE format('ALTER SEQUENCE public.%I SET SCHEMA klid_at', r.relname);
  END LOOP;
END $$;

-- flyway_schema_history 는 위 루프(relkind='r')에 포함된다. 안전망으로 한 번 더 확인한다.
ALTER TABLE IF EXISTS public.flyway_schema_history SET SCHEMA klid_at;
```

> **실측 확인(PostgreSQL 16)**: 구 형상(마이그레이션 180건을 `public` 에 전량 적용)에 위 SQL 을 실행하면
> 테이블 77(= 저작도구 76 + `flyway_schema_history`) · 뷰 4 · 시퀀스 49 · 인덱스 212 가 전부 `klid_at`
> 으로 이동하고 `public` 은 0 이 된다. 제약도 함께 이동한다(FK 49 · PK 77 · UNIQUE 26 · CHECK 3,
> `public` 잔존 0). 위 수치는 **스쿼시 이전 형상 기준**이며 이동 자체는 개수를 바꾸지 않는다.
>
> ⚠ **이동 직후에는 아직 신규 설치와 같지 않다** — 이동한 DB 에는 사용처 0 테이블 **7종**
> (`V3` 대상 `LS_DEADLINE`·`LS_META`·`LS_RAW_DATA_ENROLLMENT` + `V4` 대상 `LS_COM_CD`·
> `LS_DATA_META_HSTRY`·`LS_DATA_RAW_HSTRY`·`LS_TASK_ASSIGN_HISTORY`)이 남아 있고, 신규 설치본
> (`db/schema.sql`)에는 애초에 없다. 두 경로는 §2-5-2 의 ③에서 **`V3`·`V4` 가 이 7종을 DROP 하고
> 그 뒤 마이그레이션까지 전부 적용한 뒤** 수렴한다. 그 시점의 객체 집합은 신규 설치와 완전히
> 동일하며 차이는 `flyway_schema_history` 하나뿐이다(덤프에서 의도적으로 제외한 테이블).
>
> ⚠ **중간까지만 적용하고 멈추면 수렴하지 않는다.** `V3`·`V4` 이후로도 테이블을 만드는
> 마이그레이션이 계속 들어왔다(`V14`·`V18`·`V20`·`V21` 등). **현재 `db/schema.sql` 의 실측값은
> 테이블 73개(`LS_*` 62 + `QRTZ_*` 11) · 뷰 4개**(2026-08-30)이며, 구 서술의 *"저작도구 **69**개"*
> 는 그 시점 값이라 **폐기**한다. 개수를 인용하기 전에 매체에서 다시 세라 —
> `grep -c '^CREATE TABLE' db/schema.sql`.

> ### ★★ 복사가 아니라 **이동**이다 — `public` 에 사본을 남기지 마라
>
> `CREATE TABLE klid_at.x AS SELECT * FROM public.x` 처럼 **복사**한 뒤 `public` 원본을 남겨 두면
> 마이그레이션이 **조용히 망가진다**. `V79`·`V110`·`V146` 은 제약 존재 여부를
> `SELECT ... FROM pg_constraint WHERE conname = '...'` 로 확인하는데, **`pg_constraint` 조회는
> `search_path` 를 타지 않는다**(카탈로그 전체를 본다). 즉 `public` 에 남은 사본의 동명 제약을 보고
> **"이미 있다"로 판정해 `klid_at` 의 FK·UNIQUE 를 통째로 건너뛴다.**
> **오류 없이 제약만 누락**되므로 마이그레이션은 성공으로 보이고, 결함은 한참 뒤 데이터 정합성
> 문제로 드러난다. 반드시 `SET SCHEMA` 로 **옮기고**, 사본을 만들었다면 `public` 쪽을 지워라.

> ### ★ `flyway_schema_history` 를 안 옮기면 기동이 실패한다
>
> 이력이 `public` 에 남으면 Flyway 는 `klid_at` 을 **이력 없는 DB** 로 보고 `V1` 부터 재적용한다.
> 그 재적용은 **`V34` 의 `DROP COLUMN PJT_ID`(IF EXISTS 없음)** 에서 확정 실패한다.
> (이력을 옮기지 않는 편이 "조용한 손상" 대신 "즉시 실패"라는 점은 다행이지만, 정상 경로가 아니다.)

**③ Flyway 체크섬 재정렬** — `V62`·`V63`·`V71` 이 스키마 중립(`current_schema()` 기반)으로 바뀌어
이미 적용된 DB 는 체크섬 불일치로 **기동이 거부**된다. 1회 정정한다.

```sql
UPDATE klid_at.flyway_schema_history SET checksum = -1105638733 WHERE version = '62';
UPDATE klid_at.flyway_schema_history SET checksum =  -706850408 WHERE version = '63';
UPDATE klid_at.flyway_schema_history SET checksum =  1678444126 WHERE version = '71';
```

> 구 값은 각각 `-712380907` / `399485742` / `971618886` 이다. 위 값과 다르면 **이미 정정됐거나
> 다른 버전**이니 그대로 두고 확인한다. `flyway repair` 도 동등한 수단이다.
> 온프렘 기본 구성(`SPRING_FLYWAY_ENABLED=false`)에는 이력 테이블 자체가 없으므로 ③은 해당 없다.
>
> **★ 2026-08-13 스쿼시 이후 배포본으로 이관한다면 ③은 건너뛴다** — 그 경우 §2-5-2 가 이력 180행을
> **베이스라인 1행으로 통째로 교체**하므로 개별 행의 체크섬을 맞출 대상 자체가 없다. ③은 스쿼시
> 이전 배포본으로 이관하는 경우에만 필요하다.

**④ 검증 후 기동**

```bash
sudo -u postgres psql -d klid_system -c \
  "select (select count(*) from information_schema.tables where table_schema='klid_at') as klid_at,
          (select count(*) from information_schema.tables where table_schema='public')  as public_left;"
# klid_at 에 테이블이 모이고 public_left 가 0 이어야 한다(사본이 남으면 위 ★★ 결함이 발생).

sudo systemctl start "$WAS_UNIT"
sudo grep -iE 'flyway|schema|validat' "$WAS_LOG_DIR"/catalina.out | tail -100
# (베어메탈 토글일 때: sudo systemctl start klid-backend
#                     journalctl -u klid-backend -n 100 --no-pager | grep -iE 'flyway|schema|validat')
```

> **롤백**: ①에서 뜬 덤프를 빈 DB 에 `pg_restore` 하고 **이전 버전 `api.war` 로 되돌린다**
> (WAS 배포 디렉터리의 `api.war` 를 교체하고 WAS 가 풀어 둔 `<WAS_BASE>/webapps/api/` 를 함께 정리한 뒤
> 컨텍스트 재기동 — 절차는 `07-uninstall-rollback.md`).
> ⚠ 구 서술 폐기(2026-08-30) — *"구 버전 jar 로 되돌린다"*. 이 형상의 산출물은 jar 가 아니라 WAR 다.
> (베어메탈 토글일 때만 구 버전 jar 교체 + `systemctl restart klid-backend` 가 맞다.)
>
> **⚠ 관제팀 협의 필요** — 데이터마트 뷰 4종(`V_COMPLETED_VIDEO`/`_FRAME`/`_LABEL_CHANGE`/`_META`)이
> `public` 에서 `klid_at` 으로 옮겨간다. 관제서버가 이 뷰를 직접 SELECT 하므로 **관제 측 조회도
> 함께 바뀌어야 한다.** 이관 시점을 관제팀과 맞추지 않으면 관제의 데이터마트 적재가 멈춘다.

### 2-5-2. Flyway 스쿼시 — 기존 DB 이력 이관 (1회 작업, 2026-08-13)

> **대상**: `SPRING_FLYWAY_ENABLED=true` 로 운영되며 **2026-08-13 이전에 만들어진** DB(로컬·dev).
> **비대상**: 온프렘 기본 구성(`SPRING_FLYWAY_ENABLED=false`) — 이력 테이블 자체가 없고 스키마는
> `db/schema.sql` 로드로 준비되므로 **아무 조치도 필요 없다.** 신규 설치도 대상이 아니다.

**무엇이 바뀌었나** — 마이그레이션 180개(V0~V185)가 단일 `V1__baseline.sql` 로 접혔고,
`CM_CODE` 가 `LS_COM_CD` 로 개명됐으며(`V2`), 사용처 0 테이블이 두 차례에 걸쳐 제거됐다
(`V3` — `LS_DEADLINE`·`LS_META`·`LS_RAW_DATA_ENROLLMENT` / `V4` — `LS_COM_CD`·`LS_DATA_META_HSTRY`·
`LS_DATA_RAW_HSTRY`·`LS_TASK_ASSIGN_HISTORY`). 기존 DB 의 이력 180행은 이제 배포본에
**대응 파일이 없어**, 그대로 두고 기동하면 Flyway 가 `Detected applied migration not resolved locally`
로 **기동을 거부**한다.

**조치는 이력 테이블만 손댄다 — 스키마·데이터는 건드리지 않는다.**

---

#### ⚠ 선행조건 — 대상 DB 가 **구 `V185` 까지 적용된 상태**여야 한다 (건너뛰면 조용히 깨진다)

**이 절차는 "이력을 지우고 베이스라인 1 로 표시"한다. 베이스라인 1 = `V1__baseline.sql` = `V0~V185`
전체 스키마가 이미 적용됐다는 선언이다.** 따라서 DB 가 그보다 뒤처져 있으면(예: `V177`),
못 따라온 마이그레이션이 **"이미 적용됨"으로 표시된 채 영영 실행되지 않는다.** 그 파일들은 스쿼시로
배포본에서 사라졌으므로 **나중에 저절로 만회되지도 않는다.**

실제로 dev 가 `V177` 이었다. 그대로 밟았다면 아래 8건이 통째로 누락됐다:

| 누락됐을 마이그레이션 | 내용 |
|---|---|
| `V178` | KPST 비식별 옵션 설정 시드 |
| `V179` | `LS_DATA_SRC.DSCD_YN` (프레임 폐기여부) 컬럼 |
| `V180`·`V181` | `LS_LABEL_VERSION.VER_NO` 재정의·레거시 무효화 |
| `V182` | 데이터마트 뷰 4종에서 폐기 프레임 제외 |
| `V183` | `LS_OUTPUT_VER_SNPSH` 테이블 신설 |
| `V184` | `LS_DEIDENT_PROC_LOG` 검출 리포트 컬럼 6종 |
| `V185` | 관제 인입 계약 정합(`OG_CD` 제거·`VMS_CCTV_ID` NOT NULL 해제) |

**★ 왜 조용히 실패하나 — 기동은 성공하고, 그 기능을 처음 쓰는 순간 터진다**

`application.yml` 에 `ddl-auto: validate` 가 있어 "엔티티와 스키마가 어긋나면 기동이 막힌다"고
기대하기 쉽지만, **이 저장소에서 그 설정은 실동작하지 않는다.** 듀얼 데이터소스라
`JpaBuilderConfig` 가 `EntityManagerFactory` 를 직접 만드는데, 거기에 넘기는 것은
`JpaProperties.getProperties()`(= `spring.jpa.properties.*`)뿐이고 `ddl-auto` 는 별도 바인딩
대상(`spring.jpa.hibernate.*`)이라 **Hibernate 까지 전달되지 않는다.** 결과적으로 스키마 검증이
전혀 일어나지 않는다.

그래서 컬럼이 없어도 **기동 로그는 깨끗하다.** 실패는 그 컬럼을 처음 읽는 요청·배치에서
`column ... does not exist` 로 나타난다. **"기동됐으니 됐다"로 넘어가지 말 것** — 아래 확인 단계가
유일한 방어선이다.

**확인 — 이력의 최대 SQL 버전이 `185` 인가**

```sql
SELECT max(version::numeric) AS max_applied,
       count(*)              AS sql_rows
  FROM klid_at.flyway_schema_history
 WHERE type = 'SQL' AND success;
-- 기대: max_applied = 185
```

- **`185` 이면** → 그대로 아래 ① 로 진행한다.
- **`185` 미만이면 여기서 중단한다.** 먼저 **스쿼시 직전 배포본**(마이그레이션 180개를 그대로 들고 있는
  jar — 스쿼시 커밋의 부모 리비전 빌드)으로 기동해 Flyway 가 밀린 마이그레이션을 **끝까지 적용**하게
  한 뒤, `185` 도달을 위 SQL 로 다시 확인하고 돌아온다.
- **`185` 초과이면** 스쿼시 이후에 추가된 버전이 이미 적용된 것이다 — 이 절차 대상이 아니니
  중단하고 확인한다.

**전체 순서 (dev 에서 실제로 밟은 순서)**

| # | 단계 | 위치 |
|---|---|---|
| 1 | **스쿼시 직전 배포본으로 기동 → `V185` 도달 확인** | 위 선행조건 |
| 2 | 앱 정지 · 백업 | 아래 ① |
| 3 | 스키마 이관 (`public` → `klid_at`) — 아직 안 했다면 | §2-5-1 |
| 4 | 이력 180행 → 베이스라인 1행 교체 | 아래 ② |
| 5 | **현재 배포본**으로 기동 → `V2`·`V3` 만 적용되는지 확인 | 아래 ③ |
| 6 | 기능 검증 | 아래 ④ |

> 3(스키마 이관)과 4(이력 교체)는 둘 다 "기존 DB 1회 작업"이라 한 번의 정지 구간에서 이어서 한다.
> 이미 `klid_at` 로 옮겨진 DB 라면 3 은 건너뛴다.

---

**① 백업(필수)**

```bash
sudo -u postgres pg_dump -Fc -d klid_system -n klid_at -f /var/backups/klid_at_$(date +%F).dump
```

**② 앱 정지 → 이력 180행을 베이스라인 1행으로 교체**

```bash
source /etc/klid/was.env 2>/dev/null || WAS_UNIT='<WAS 유닛명>'
sudo systemctl stop "$WAS_UNIT"         # 2노드면 양쪽 모두. backend 정지 = WAS 정지(0절)
# (베어메탈 토글일 때: sudo systemctl stop klid-backend)
```

```sql
BEGIN;
-- 안전 가드: 스쿼시 이전 이력이 실제로 있는 DB 에서만 수행한다(중복 실행·오적용 방지).
DO $$
DECLARE n int;
BEGIN
    SELECT count(*) INTO n FROM klid_at.flyway_schema_history
     WHERE type = 'SQL' AND version::numeric > 2;
    IF n = 0 THEN
        RAISE EXCEPTION '스쿼시 이전 이력이 없다 — 이 DB 는 이관 대상이 아니다';
    END IF;
END $$;

DELETE FROM klid_at.flyway_schema_history;

INSERT INTO klid_at.flyway_schema_history
    (installed_rank, version, description, type, script, checksum,
     installed_by, installed_on, execution_time, success)
VALUES
    (1, '1', 'squash baseline (gu V0-V185)', 'BASELINE', '<< Flyway Baseline >>', NULL,
     CURRENT_USER, CURRENT_TIMESTAMP, 0, TRUE);
COMMIT;
```

> **왜 `BASELINE` 타입인가**: Flyway 는 baseline 행의 버전 **이하**를 "이미 적용됨"으로 보고 건너뛴다.
> 버전 1 로 두면 `V1`(스키마 전량)은 건너뛰고 `V2`(개명)만 적용된다 — 기존 DB 에 정확히 필요한 동작이다.
> `SQL` 타입으로 넣으면 체크섬 검증 대상이 되어 `NULL` 체크섬에서 걸린다.
>
> **⚠ 이력만 지우고 이 행을 넣지 않으면** 재기동 시 `baseline-on-migrate` 가 version 0 으로 발동해
> `V1` 이 기존 테이블 위에서 `already exists` 로 **실패**한다. 조용히 스킵되는 것보다 안전한 방향이라
> 의도적으로 그렇게 두었다 — 실패를 보면 이 절차를 안 밟은 것이다.

**③ 현재 배포본으로 기동 → 베이스라인 이후 버전만 적용되는지 확인**

```bash
source /etc/klid/was.env 2>/dev/null || { WAS_UNIT='<WAS 유닛명>'; WAS_LOG_DIR='<WAS_LOG_DIR>'; }
sudo systemctl start "$WAS_UNIT"
sudo grep -iE 'flyway|migrating|baseline' "$WAS_LOG_DIR"/catalina.out | tail -200
# (베어메탈 토글일 때: sudo systemctl start klid-backend
#                     journalctl -u klid-backend -n 200 --no-pager | grep -iE 'flyway|migrating|baseline')
# 기대: Current version of schema "klid_at": 1
#       → Migrating schema "klid_at" to version "2 - rename cm code to ls com cd"
#       → Migrating schema "klid_at" to version "3 - drop unused tables"
#       → Migrating schema "klid_at" to version "4 - drop unused tables round2"
#       → Migrating schema "klid_at" to version "5 - rename queue outbox columns to std"
#       → … (베이스라인 이후 전 버전이 번호순으로 이어진다)
#       → Migrating schema "klid_at" to version "21 - add ls mngr pswd"
# V1 은 베이스라인 이하라 건너뛴다(로그에 Migrating 이 뜨지 않는 것이 정상).
#
# ★ 마지막 번호를 문서에서 읽지 말고 <매체에서 세라> — 마이그레이션은 계속 늘어난다.
#   2026-08-30 실측: 파일 21개(V1 베이스라인 + 베이스라인 이후 V2~V21 = 20개).
#     ls backend/src/main/resources/db/migration/ | wc -l
#   구 주석 폐기(2026-08-30): 기대 목록이 "2·3·4·5" 에서 끝나는 것으로 읽혔다 —
#   그 뒤 버전이 함께 뜨는 것을 <이상>으로 오인하게 된다.
#
# V3·V4 는 무엇을 지웠는지 NOTICE 로 알린다(기존 DB 에서만 뜬다. 신규 설치는 애초에 만들지
# 않으므로 no-op 이라 한 줄도 뜨지 않는 것이 정상):
#       → 사용처 0 테이블 제거: ls_deadline / ls_meta / ls_raw_data_enrollment
#       → 사용처 0 테이블 제거: ls_data_meta_hstry / ls_data_raw_hstry / ls_com_cd
#                              / ls_task_assign_history
#
# V5 는 NOTICE 를 <13줄> 낸다 — 개명 11 + 폭 정합 2. 전부 정상이며, V3·V4 와 달리
# <신규 설치에서도 똑같이 뜬다>(V1 이 옛 이름으로 만들고 V5 가 개명하는 구조라 no-op 이 아니다):
#       → 표준용어 개명: ls_clip_schedule_que.job_type → job_type_cd        (외 6줄)
#       → 표준용어 개명: ls_meta_repl_outbox.payload   → payload_cn         (외 3줄)
#       → 표준도메인 폭 정합: ls_clip_schedule_que.job_type_cd → varchar(20)
#       → 표준도메인 폭 정합: ls_meta_repl_outbox.stts_cd      → varchar(16)
# 이 13줄이 <한 줄도 없다면> V5 가 돌지 않았거나 이미 개명된 DB 다 — 아래 이력 조회로 구분한다.
```

```sql
-- 이력은 BASELINE 1행 + 베이스라인 이후 SQL 행들만 남아야 한다
-- (2026-08-30 기준 V2~V21 = 20행. ★ 이 개수는 늘어난다 — 매체의 db/migration 파일 수와 맞춰 본다).
-- 구 주석 폐기(2026-08-30): "(현재: 2, 3, 4, 5)".
SELECT installed_rank, version, description, type, success
  FROM klid_at.flyway_schema_history ORDER BY installed_rank;

-- ★ 개명(V2) 후 제거(V4) — 같은 기동에서 연달아 일어나므로 <최종 상태는 둘 다 NULL> 이다.
--   V2 는 CM_CODE 를 LS_COM_CD 로 개명하고, V4 가 그 LS_COM_CD 를 DROP 한다(상태코드 5행은
--   아무도 읽지 않는 두 번째 진실원이라 LsRawDataStatus 자바 상수로 일원화했다).
--   ⚠ 구 절차는 여기서 "새 이름에 5행이 남아 있어야 한다"고 안내했다 — 지금 그 기대로 보면
--     정상 이관을 <실패로 오인>한다. V2 가 실제로 돌았는지는 위 이력 조회(version=2 행)로 본다.
SELECT to_regclass('klid_at.cm_code')   AS old_should_be_null,
       to_regclass('klid_at.ls_com_cd') AS new_should_also_be_null;

-- V3 확인: 사용처 0 테이블 3종이 사라져야 한다(셋 다 NULL).
SELECT to_regclass('klid_at.ls_deadline')            AS deadline_should_be_null,
       to_regclass('klid_at.ls_meta')                AS meta_should_be_null,
       to_regclass('klid_at.ls_raw_data_enrollment') AS enrollment_should_be_null;

-- V4 확인: 사용처 0 테이블 4종이 사라져야 한다(넷 다 NULL. ls_com_cd 는 위에서 확인).
SELECT to_regclass('klid_at.ls_data_meta_hstry')      AS meta_hstry_should_be_null,
       to_regclass('klid_at.ls_data_raw_hstry')       AS raw_hstry_should_be_null,
       to_regclass('klid_at.ls_task_assign_history')  AS assign_hstry_should_be_null;

-- V5 확인: 개명된 11종이 새 이름으로만 존재해야 한다(옛 이름 0 · 새 이름 11).
SELECT count(*) FILTER (WHERE (table_name::text, column_name::text) IN (
         ('ls_clip_schedule_que','job_type'),      ('ls_clip_schedule_que','status'),
         ('ls_clip_schedule_que','retry_count'),   ('ls_clip_schedule_que','registered_at'),
         ('ls_clip_schedule_que','started_at'),    ('ls_clip_schedule_que','completed_at'),
         ('ls_clip_schedule_que','last_error'),
         ('ls_meta_repl_outbox','payload'),        ('ls_meta_repl_outbox','status'),
         ('ls_meta_repl_outbox','retry_cnt'),      ('ls_meta_repl_outbox','proc_dt')))
                                                        AS old_names_should_be_0,
       count(*) FILTER (WHERE (table_name::text, column_name::text) IN (
         ('ls_clip_schedule_que','job_type_cd'),   ('ls_clip_schedule_que','stts_cd'),
         ('ls_clip_schedule_que','rtry_nmtm'),     ('ls_clip_schedule_que','reg_dt'),
         ('ls_clip_schedule_que','bgng_dt'),       ('ls_clip_schedule_que','cmptn_dt'),
         ('ls_clip_schedule_que','last_err_msg_cn'),
         ('ls_meta_repl_outbox','payload_cn'),     ('ls_meta_repl_outbox','stts_cd'),
         ('ls_meta_repl_outbox','rtry_nmtm'),      ('ls_meta_repl_outbox','prcs_dt')))
                                                        AS new_names_should_be_11
  FROM information_schema.columns
 WHERE table_schema='klid_at'
   AND table_name IN ('ls_clip_schedule_que','ls_meta_repl_outbox');

-- 최종 형상: 저작도구 소유 테이블은 58개여야 한다(신규 설치와 같은 수 — 두 경로 수렴 확인).
SELECT count(*) AS ls_tables_should_be_58
  FROM information_schema.tables
 WHERE table_schema='klid_at' AND table_type='BASE TABLE' AND table_name LIKE 'ls\_%';
```

> **⚠ V4 가 기동을 멈췄다면 그건 버그가 아니라 fail-closed 다.** V4 는 제거 전제(이력 테이블 0행 /
> `LS_COM_CD` 시드 5행 외 없음 / 배정이력 전 행이 대응 이벤트 로그의 REASSIGN 행 보유 — V4 는 그
> 시점의 물리명 `LS_TASK_EVENT_LOG` 로 확인한다. V9 개명 후의 이름은 `LS_TASK_EVNT_LOG` 이며,
> V4 가 V9 보다 **먼저** 돌므로 순서상 어긋나지 않는다)를
> 검사해, 하나라도 깨지면 DROP 하지 않고 **예외로 중단**한다. 조용히 지워 비가역 손실을 내는 대신
> 사람이 판단하게 하는 것이다. 메시지에 어느 테이블·몇 행인지 찍히므로 그 데이터를 확인하고
> 백업·정리 후 재기동한다. 판단 근거는 `V4__drop_unused_tables_round2.sql` 헤더에 있다.

**④ 기능 검증 — 선행조건을 지켰는지 실제로 확인한다**

`ddl-auto=validate` 가 실동작하지 않으므로(위 선행조건 참조) **기동 성공은 스키마 정합의 근거가
아니다.** 마지막 구간 마이그레이션이 실제로 반영됐는지 스키마로 직접 확인한다.

```sql
-- V179·V183·V184·V185 반영 여부 (선행조건을 건너뛰었다면 여기서 드러난다)
SELECT to_regclass('klid_at.ls_output_ver_snpsh') AS v183_should_exist,
       (SELECT count(*) FROM information_schema.columns
         WHERE table_schema='klid_at' AND table_name='ls_data_src'
           AND column_name='dscd_yn')             AS v179_should_be_1,
       (SELECT count(*) FROM information_schema.columns
         WHERE table_schema='klid_at' AND table_name='ls_deident_proc_log'
           AND column_name IN ('face_dtct_cnt','noplt_dtct_cnt','frme_cnt',
                               'prcs_bgng_dt','prcs_end_dt','rpt_file_path_nm'))
                                                  AS v184_should_be_6,
       (SELECT count(*) FROM information_schema.columns
         WHERE table_schema='klid_at' AND table_name='ls_data_ingest'
           AND column_name='og_cd')               AS v185_should_be_0;
```

하나라도 기대와 다르면 **선행조건 확인을 건너뛴 것**이다. ①의 덤프로 되돌린 뒤 선행조건부터 다시 밟는다.
그다음 화면에서 영상 목록·라벨링·검수 승인을 한 번씩 돌려 실제 동작을 확인한다.

> **`V2`·`V3`·`V4` 는 모두 조건부라 두 번 돌아도 안전하다** — 대상이 없으면 아무것도 하지 않는다(멱등).
> 신규 설치에서는 `V1` 이 `CM_CODE`·`LS_COM_CD` 어느 이름으로도 만들지 않으므로 `V2` 가 no-op 이고,
> `V3`·`V4` 대상 7종도 `V1` 이 애초에 만들지 않아 역시 no-op 이다. 그래서 두 경로가 **수동 개입 없이
> 같은 스키마로 수렴**한다(스키마 덤프 기계 비교로 확인 — 차이 0).
>
> ⚠ `V2` 파일 헤더의 *"신규 설치: `V1` 이 이미 `LS_COM_CD` 로 만든다"* 라는 서술은 **낡았다**(지금은
> 만들지 않는다). 결론(no-op)은 그대로이며, 이미 적용된 이력이라 체크섬 때문에 고칠 수 없다.

> **롤백**: ①의 덤프를 빈 DB 에 복원하고 구 버전 jar 로 되돌린다. **이것이 권장 경로다.**
>
> ⚠ 스키마만 되돌리는 약식 경로는 **더 이상 `V2` 의 RENAME 만으로 끝나지 않는다** — `V4` 가
> `LS_COM_CD` 를 DROP 했으므로 되돌릴 대상 테이블 자체가 없다. `V2` 파일 헤더의 `RENAME` 2줄은
> `V4` 이전 형상에서만 성립한다. 약식으로 가려면 `V4` 가 지운 4종을 **먼저 재생성**해야 하고,
> 그 원문 위치·조각 순서·주의사항은 `V4__drop_unused_tables_round2.sql` 헤더 「롤백 절차」에 있다
> (아카이브는 독자 번호 체계라 현행 `V1~V4` 와 파일명이 겹친다 — 반드시 파일명 전체로 찾을 것).

> **옛 마이그레이션 원문이 필요할 때**: 180개 파일은 지워지지 않았다 —
> `backend/src/test/resources/db-archive/migration/` 에 원문 그대로 보존돼 있다(Flyway 는 이 경로를
> 읽지 않는다). 각 파일의 배경·판단 근거·롤백 절차 주석이 그대로 있으므로 과거 조치를 추적할 때 참조한다.

### 2-6. 디스크 부족 (저장소·로그)

```bash
df -h /opt/klid /var/lib/pgsql /var/log/klid "$STORAGE_RAW_PATH"   # 앱·DB·로그·저장소
du -sh "$STORAGE_RAW_PATH"/* 2>/dev/null | sort -h | tail          # 저장소 상위 용량
journalctl --disk-usage                                           # journald 사용량
sudo journalctl --vacuum-time=14d                                 # 오래된 로그 정리(정책에 맞게)

# ★ backend 로그는 journald 가 아니라 WAS 의 파일이다 — 위 vacuum 으로 줄지 않는다(0-2·3절)
source /etc/klid/was.env 2>/dev/null || WAS_LOG_DIR='<WAS_LOG_DIR>'
sudo du -sh "$WAS_LOG_DIR" && sudo ls -lhS "$WAS_LOG_DIR" | head
```

> ⚠ **`catalina.out` 은 WAS 기본 설정에서 회전되지 않고 무한히 커진다.** 디스크 부족의 원인이
> 저장소가 아니라 이 파일인 경우가 있다. logrotate 또는 WAS 자체 회전 설정이 걸려 있는지
> WAS 운영 주체와 함께 확인한다. **가동 중 파일을 그냥 지우면 공간이 돌아오지 않는다**(WAS 가 열어
> 둔 상태라 inode 가 살아 있다) — 비우려면 `: > catalina.out` 처럼 truncate 하거나 WAS 를 재기동한다.

- 영상/프레임 저장소(`STORAGE_RAW_PATH`·`STORAGE_DEIDENTIFIED_PATH` 설정값, 기본 `/nas-storage` 또는 마운트 NAS)는 용량 산정(이미지 10만 장·영상 5,000건 기준)을 초과하지 않도록 주기 점검한다. 앱 런타임 데이터 `/var/lib/klid`, DB 데이터 `/var/lib/pgsql/16/data` 는 별도 경로다.

---

## 3. 로그 확인 (공통)

**backend 로그는 WAS 로그다**(0-2). ai-server·httpd 는 종전대로 journald 다.

```bash
source /etc/klid/was.env 2>/dev/null || { WAS_UNIT='<WAS 유닛명>'; WAS_LOG_DIR='<WAS_LOG_DIR>'; }

# 실시간 추적
sudo tail -f "$WAS_LOG_DIR"/catalina.out                    # backend(WAS)
sudo tail -f "$WAS_LOG_DIR"/localhost.$(date +%F).log       # backend 컨텍스트(기동 실패는 이쪽)
journalctl -u klid-ai-server -f                             # ai-server (systemd 그대로)
journalctl -u httpd -f                                      # httpd     (systemd 그대로)

# WAS 유닛이 표준출력을 journald 로 넘기는 구성이면 backend 도 이쪽에서 보인다
journalctl -u "$WAS_UNIT" -f
journalctl -u "$WAS_UNIT" --since '1 hour ago' -p err --no-pager

# 특정 시간대 / 에러만 — 파일 로그에서는 시간 문자열로 좁힌다
sudo grep -E "$(date '+%Y-%m-%d %H')" "$WAS_LOG_DIR"/catalina.out | grep -iE 'error|warn' | tail -50

# 앱 파일 로그(파일 로깅 활성 시)
ls -al /var/log/klid
```

> **베어메탈 토글일 때**: `journalctl -u klid-backend -f` ·
> `journalctl -u klid-backend --since '1 hour ago' -p err --no-pager` 가 그대로 유효하다.
>
> ⚠ **WAS 로그는 journald 가 아니라 파일이라 회전 정책이 다르다.** `journalctl --vacuum-time` 은
> 이 파일들을 줄이지 못한다 — `catalina.out` 은 WAS 기본 설정에서 무한히 커질 수 있으므로
> logrotate 또는 WAS 자체 회전 설정을 확인한다(→ 2-6).

> 로그에는 민감정보 마스킹이 적용된다(개인정보·토큰·JWT 평문 미출력). 로그를 외부로 반출할 때도
> 마스킹 정책을 확인한다. 추적ID(`traceId`)로 하나의 요청 흐름을 로그·외부 호출에 걸쳐 추적할 수 있다.

---

## 4. 재시작 / 정지 / 설정 반영

```bash
source /etc/klid/was.env 2>/dev/null || WAS_UNIT='<WAS 유닛명>'

# 단일 재시작 — backend 재기동 = WAS 재기동
sudo systemctl restart "$WAS_UNIT"

# 전체 정지(역순 권장: httpd → backend(WAS) → ai-server)
sudo systemctl stop httpd; sudo systemctl stop "$WAS_UNIT"; sudo systemctl stop klid-ai-server

# 전체 기동(의존 순서: ai-server → backend(WAS) → httpd)
sudo systemctl start klid-ai-server; sudo systemctl start "$WAS_UNIT"; sudo systemctl start httpd

# 환경설정 변경 반영
#   · backend : /etc/klid/application.properties 수정 후 WAS 재기동
#   · ai-server: /etc/klid/ai-server.env 수정 후 systemctl restart klid-ai-server
sudo systemctl restart "$WAS_UNIT"
```

> ⚠ **`klid-frontend` 유닛은 없다.** 프론트엔드는 배포판 **httpd** 가 정적 서빙한다
> (구 서술 폐기 — `systemctl stop klid-frontend`).
>
> ⚠ **WAS 를 통째로 재기동하면 같은 WAS 의 다른 애플리케이션도 함께 내려간다.** 설정 반영만
> 필요하다면 `api.war` **컨텍스트 단위 재기동**으로 좁힐 수 있는지 WAS 운영 주체와 확인한다
> (스프링 설정 파일은 컨텍스트 기동 시점에 읽히므로 컨텍스트 재기동으로 충분하다).
> 단 **`CATALINA_OPTS`(JVM 옵션·`-Dspring.config.additional-location`·`-Dspring.profiles.active`)를
> 바꿨다면 컨텍스트 재기동으로는 반영되지 않는다** — JVM 기동 옵션이라 WAS 프로세스를 다시 띄워야 한다.
>
> **베어메탈 토글일 때**: `sudo systemctl restart klid-backend` /
> `sudo systemctl stop httpd klid-backend klid-ai-server` /
> `sudo systemctl start klid-ai-server klid-backend httpd` 이고, 설정 파일은 `/etc/klid/backend.env` 다.

### 4-0. V5(배치 큐·메타복제 발신함 컬럼 개명) 배포 시 주의 — 롤링 재기동이 무해하지 않다

**V5 는 하위호환 개명이 아니다.** 컬럼 11종의 이름이 바뀌므로, **V5 가 적용된 스키마와 아직 구 jar 인
노드가 공존하는 창** 동안 그 노드의 아래 4경로가 전부 실패한다
(`ERROR: column "payload" does not exist` · `column "status" does not exist`).

| 실패 경로 | 사용자에게 보이는 증상 |
|---|---|
| **검수 승인** | 승인 트랜잭션 안에서 발신함 INSERT 가 실패해 **승인 전체가 500** — 사용자 대면 |
| **영상 인입** | 인입 트랜잭션 안에서 큐 INSERT 가 실패해 **인입째 롤백**(영상이 들어오지 않음) |
| **배치 큐 폴링** | 매 tick 실패 — 파이프라인이 진행되지 않음 |
| **포털 메타 복제** | 매 tick 실패 — 포털 복제본 미갱신 |

> **데이터는 잃지 않는다.** 실패가 전부 트랜잭션 롤백이라 부분 기록이 남지 않고, 이미 발행된 발신함
> 행은 `PENDING` 으로 남아 신 jar 노드가 이어 처리한다. **두 노드 재기동이 끝나면 자기치유**된다.
> 잃는 것은 그 창 동안의 **가용성**이다.

배포 방식은 **운영 정책 결정**이며 배포 담당이 고른다.

| | 절차 | 대가 |
|---|---|---|
| **(a) 정지 후 배포 (권장)** | 양쪽 노드를 먼저 내리고 배포·기동한다 — 공존 창이 없다 | 짧은 **다운타임** |
| **(b) 롤링 재기동** | 한 노드씩 교체해 무중단을 유지한다 | 창 동안 **검수 승인이 사용자 대면 500** |

사고 영향이 사용자 대면이라 **(a) 를 권장**한다. 무중단 요구가 우선이면 (b) 도 성립한다
(자기치유되고 데이터 유실이 없다) — 다만 **검수 담당자에게 그 창을 미리 공지**하라.

```bash
# (a) 정지 후 배포 — 2노드면 양쪽 모두
source /etc/klid/was.env 2>/dev/null || WAS_UNIT='<WAS 유닛명>'
sudo systemctl stop httpd; sudo systemctl stop "$WAS_UNIT"; sudo systemctl stop klid-ai-server
#   … 패키지 교체(install.sh) …
#   … ★ api.war 를 WAS 배포 디렉터리로 다시 복사 — install.sh 는 /opt/klid/app 에 두기만 한다 …
#     (WAS 가 풀어 둔 <WAS_BASE>/webapps/api/ 가 남아 있으면 함께 지워야 새 WAR 가 반영된다)
sudo systemctl start klid-ai-server; sudo systemctl start "$WAS_UNIT"; sudo systemctl start httpd
#   ★ DDL(V5)은 <DBA 가 수동 적용>한다 — 온프렘은 Flyway 를 쓰지 않으므로 기동해도 적용되지 않는다.
#     적용 SQL 은 V5 마이그레이션 파일, 반영 확인은 §2-5-2 ③ 의 「V5 확인」 쿼리.
#   (베어메탈 토글일 때: stop/start 의 "$WAS_UNIT" 자리에 klid-backend)
```

> **롤백(구버전으로 되돌리기)에도 같은 비호환이 있다** — 방향만 반대다. 되돌릴 때는 스키마를 함께
> 되돌려야 한다. 절차는 `07-uninstall-rollback.md` 「알려진 비호환 — V5」 참조.

### 4-0-1. V8(웹훅 멱등 원장 적용일시 컬럼 개명) 배포 시 주의 — V5 와 같은 부류다

**V8 도 하위호환 개명이 아니다.** `LS_WEBHOOK_IDEMPOTENCY.APLY_DT` 가 `APLCN_DT` 로 바뀌므로,
**V8 이 적용된 스키마와 아직 구 jar 인 노드가 공존하는 창** 동안 그 노드의 아래 2경로가 실패한다
(`ERROR: column "aply_dt" does not exist`).

| 실패 경로 | 증상 |
|---|---|
| **웹훅 콜백 처리** | 외부 시계열 결과 콜백이 원장을 갱신하지 못해 실패 — **가용성** 손실 |
| **위탁 제출** | 외부 호출 직전 상관키 적재가 실패하고 그 예외가 승격돼 **파이프라인이 `FAILED` 로 전이** — 상태 전이 + 재시도 예산 소모 |

> ⚠ **두 번째 경로를 빠뜨리지 마라.** 엔티티에 `@DynamicInsert` 가 없어 INSERT 가 전 컬럼을 명시하므로
> 읽기 축만 깨지는 것이 아니다. 가용성만 잃는 첫 번째와 달리 **작업 상태가 실제로 바뀐다.**

> **데이터는 잃지 않고 중복 위탁도 열리지 않는다.** 실패는 전부 트랜잭션 롤백이고 원장 행은 미결로
> 남아 미결 스위퍼가 회수·재위탁한다. 상관키 적재가 외부 호출보다 **앞**이라 실패 시 제출 자체가
> 중단된다(fail-closed) — 원장 없는 위탁이 나갈 수 없다.

#### ★ 창을 여는 것은 Flyway 가 아니다 (V5 절과 다른 점)

**온프렘은 어떤 구성에서도 Flyway 를 쓰지 않는다**(`SPRING_FLYWAY_ENABLED=false` — 스키마는
`db/schema.sql` 1회 로드). 노드가 1대든 2대든 마찬가지이므로, 온프렘에서 창을 여는 것은
**언제나 DBA 의 수동 DDL** 이다.

> ⚠ 구 서술 폐기(2026-08-30) — *"2노드 이중화 구성은 Flyway 가 꺼져 있고 반대로 Flyway 가 켜진
> 구성은 단일 노드다 / 이 배포 형상에서 2노드와 Flyway 는 상호배타"*. **Flyway 를 켠 온프렘 구성이
> 있는 것처럼 읽혔다.** Flyway 를 켠 구성은 개발·검증 환경뿐이며 온프렘 반입 형상에는 존재하지
> 않는다. 노드 수는 이 축과 무관하다.

⇒ 수동 DDL 적용 시점과 노드 재기동 순서를 맞추는 것이 절차의 핵심이다. 적용 시점에 따라
**두 노드가 동시에 구 jar 인 구간**이 생길 수 있어 "한 노드만 구 jar" 가정보다 불리하다.

> ⚠ **V5 도 마찬가지다** — 구 서술 *"먼저 기동한 노드가 Flyway 로 V5 를 적용한다"* 는 **폐기**했다
> (§4-0 `(a)` 를 「DBA 수동 적용」으로 정정). 온프렘은 Flyway 를 쓰지 않으므로 어떤 버전의 DDL 도
> 기동만으로 적용되지 않는다.

#### 배포 절차

| | 절차 | 대가 |
|---|---|---|
| **(a) 정지 후 배포 (권장)** | 양쪽 노드를 내리고 → DDL 적용 → 배포·기동 — 공존 창이 없다 | 짧은 **다운타임** |
| **(b) 롤링 재기동** | 한 노드씩 교체해 무중단 유지 | 창 동안 콜백 실패 + **일부 영상이 `FAILED` 로 전이** |

V5 와 달리 **사용자 대면 500 은 없으나**, 위 두 번째 경로가 작업 상태를 바꾸므로 **(a) 를 권장**한다.
(b) 를 고르면 창 이후 `FAILED` 로 떨어진 영상의 재처리가 필요하다.

```bash
# (a) 정지 후 배포 — 2노드면 양쪽 모두
source /etc/klid/was.env 2>/dev/null || WAS_UNIT='<WAS 유닛명>'
sudo systemctl stop httpd; sudo systemctl stop "$WAS_UNIT"; sudo systemctl stop klid-ai-server
#   … DDL 적용(온프렘은 Flyway 를 쓰지 않으므로 <DBA 가 수동 적용>) …
#   … 패키지 교체(install.sh) …
#   … ★ api.war 를 WAS 배포 디렉터리로 다시 복사(+ 풀린 webapps/api/ 정리) …
sudo systemctl start klid-ai-server; sudo systemctl start "$WAS_UNIT"; sudo systemctl start httpd
#   (베어메탈 토글일 때: stop/start 의 "$WAS_UNIT" 자리에 klid-backend)
```

반영 확인:

```sql
-- 개명이 끝났으면 1행, 아직이면 0행
SELECT column_name FROM information_schema.columns
 WHERE table_schema = current_schema()
   AND table_name   = 'ls_webhook_idempotency'
   AND column_name  = 'aplcn_dt';

-- 옛 이름이 남아 있으면 미적용 — 0행이어야 정상
SELECT column_name FROM information_schema.columns
 WHERE table_schema = current_schema()
   AND table_name   = 'ls_webhook_idempotency'
   AND column_name  = 'aply_dt';
```

> **롤백(구버전으로 되돌리기)에도 같은 비호환이 있다** — 방향만 반대다. 구버전 엔티티는 옛 컬럼명으로
> 매핑하므로 개명을 되돌리지 않으면 위 2경로가 계속 깨진다. 되돌리는 DDL 과 Flyway 이력 정리 절차는
> **V8 마이그레이션 파일 헤더의 「롤백 절차」 절**에 있다.

### 4-0-2. V9(작업 배정·이벤트 로그 테이블 개명) 배포 시 주의 — 이 계열에서 영향이 가장 넓다

**V9 도 하위호환 개명이 아니며, V5·V8 과 달리 테이블 자체의 이름이 바뀐다.**

| 옛 물리명 | 새 물리명 |
|---|---|
| `LS_TASK_ASSIGNMENT` | `LS_TASK_ALTMNT` (배정 = 행안부 공통표준단어 `ALTMNT`) |
| `LS_TASK_EVENT_LOG` | `LS_TASK_EVNT_LOG` (이벤트 = 사업표준단어 `EVNT`) |

**V9 가 적용된 스키마와 아직 구 jar 인 노드가 공존하는 창** 동안 그 노드의 아래 경로가 실패한다
(`ERROR: relation "ls_task_assignment" does not exist`).

| 실패 경로 | 사용자에게 보이는 증상 |
|---|---|
| **작업 배정·재배정** | 배정 INSERT/UPDATE 와 이벤트 적재가 같은 트랜잭션이라 **배정 전체가 500** |
| **검수 제출·승인·반려** | 상태 전이와 이벤트 적재가 같은 트랜잭션이라 **검수 전체가 500** — 사용자 대면 |
| **작업 목록·검수 목록·영상 목록** | 배정 조인/EXISTS 가 깨져 **목록 조회 500** |
| **통계** | 작업자별 집계가 배정을 조인하므로 실패 |
| **개인정보 선언 변경** | 감사 INSERT 실패로 **해당 PUT 전체가 롤백** |

> ⚠ **V5·V8 과 달리 자기치유되지 않는다.** 두 앞선 개명은 실패분이 미결 큐·발신함에 남아 신 jar
> 노드가 이어 처리했지만, 여기서 실패하는 것은 **사람이 방금 누른 조작**이다. 데이터는 잃지 않지만
> (전부 트랜잭션 롤백) **실패한 배정·검수는 사용자가 다시 시도해야 한다.**

> **컬럼은 하나도 바뀌지 않았다.** `ASSIGNMENT_ID`·`ACTOR_USER_NO` 등은 전부 표준용어 등록분이라
> 그대로다. 애플리케이션 API 경로·응답 필드도 불변이라 **프론트엔드 배포 순서 제약은 없다.**

#### ★ 창을 여는 것은 Flyway 가 아니다 (§4-0-1 과 동일)

**온프렘은 어떤 구성에서도 Flyway 를 쓰지 않는다**(`SPRING_FLYWAY_ENABLED=false` — 스키마는
`db/schema.sql` 1회 로드). 노드 수와 무관하게 창을 여는 것은 **언제나 DBA 의 수동 DDL** 이며,
적용 시점에 따라 **두 노드가 동시에 구 jar 인 구간**이 생길 수 있다.

#### 배포 절차

| | 절차 | 대가 |
|---|---|---|
| **(a) 정지 후 배포 (강력 권장)** | 양쪽 노드를 내리고 → DDL 적용 → 배포·기동 — 공존 창이 없다 | 짧은 **다운타임** |
| **(b) 롤링 재기동** | 한 노드씩 교체해 무중단 유지 | 창 동안 **배정·검수가 사용자 대면 500**, 회수 큐 없음 |

영향 경로가 이 계열에서 가장 넓고 자기치유도 되지 않으므로 **(a) 를 강력 권장**한다.

```bash
# (a) 정지 후 배포 — 2노드면 양쪽 모두
source /etc/klid/was.env 2>/dev/null || WAS_UNIT='<WAS 유닛명>'
sudo systemctl stop httpd; sudo systemctl stop "$WAS_UNIT"; sudo systemctl stop klid-ai-server
#   … DDL 적용(온프렘은 Flyway 를 쓰지 않으므로 <DBA 가 수동 적용>) …
#   … 패키지 교체(install.sh) …
#   … ★ api.war 를 WAS 배포 디렉터리로 다시 복사(+ 풀린 webapps/api/ 정리) …
sudo systemctl start klid-ai-server; sudo systemctl start "$WAS_UNIT"; sudo systemctl start httpd
#   (베어메탈 토글일 때: stop/start 의 "$WAS_UNIT" 자리에 klid-backend)
```

반영 확인 — **테이블만 보면 안 된다.** `ALTER TABLE ... RENAME TO` 는 시퀀스·제약·인덱스 이름을
따라오게 하지 않으므로, 개명 대상 **13개 객체를 전수로** 센다:

```sql
-- 옛 이름이 남아 있으면 미적용 또는 부분 적용 — 0행이어야 정상
-- ⚠ 이건 육안 확인용 나열이라 아래 카운트 쿼리와 축이 다르다. PK 2 · UNIQUE 1 은
--    pg_class(인덱스)·pg_constraint(제약) 양쪽에 다 걸려 중복 등장하므로, 미적용 상태에서는
--    13행이 아니라 16행이 나온다. 여기서 세지 말고 **0행인지만** 보라.
SELECT 'rel' AS kind, relname AS name FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'klid_at'
   AND relname IN ('ls_task_assignment','ls_task_event_log',
                   'ls_task_assignment_assignment_id_seq','ls_task_event_log_event_seq_seq',
                   'ls_task_assignment_pkey','ls_task_event_log_pkey','uk_ls_task_assignment',
                   'ix_ls_task_assignment_raw','ix_ls_task_assignment_user',
                   'ix_ls_task_event_log_actor','ix_ls_task_event_log_raw')
UNION ALL
SELECT 'con', conname FROM pg_constraint c
  JOIN pg_namespace n ON n.oid = c.connamespace
 WHERE n.nspname = 'klid_at'
   AND conname IN ('fk_ls_task_assignment_raw','fk_ls_task_event_log_raw',
                   'ls_task_assignment_pkey','ls_task_event_log_pkey','uk_ls_task_assignment');

-- 개명이 끝났으면 13행 — 테이블 2 · 시퀀스 2 · 인덱스(PK 2 · UNIQUE 1 · 일반 4) 7 · FK 2
SELECT count(*) AS renamed_objects_should_be_13 FROM (
  SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = 'klid_at'
     AND relname IN ('ls_task_altmnt','ls_task_evnt_log',
                     'ls_task_altmnt_assignment_id_seq','ls_task_evnt_log_evnt_id_seq',
                     'ls_task_altmnt_pkey','ls_task_evnt_log_pkey','uk_ls_task_altmnt',
                     'ix_ls_task_altmnt_raw','ix_ls_task_altmnt_user',
                     'ix_ls_task_evnt_log_actor','ix_ls_task_evnt_log_raw')
  UNION ALL
  SELECT conname FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
   WHERE n.nspname = 'klid_at'
     AND conname IN ('fk_ls_task_altmnt_raw','fk_ls_task_evnt_log_raw')
) t;
```

> ⚠ **시퀀스 하나는 단순 접두 치환이 아니다.** 옛 이름 `ls_task_event_log_event_seq_seq` 는
> **존재하지 않는 `event_seq` 컬럼**을 달고 있던 잔재이며(실제 컬럼은 `evnt_id`), V9 에서
> `ls_task_evnt_log_evnt_id_seq` 로 바로잡았다. 기계적으로 치환한 이름을 기대하면 어긋난다.

> **롤백(구버전으로 되돌리기)에도 같은 비호환이 있다** — 방향만 반대다. 되돌리는 DDL 13줄과
> Flyway 이력 정리 절차는 **V9 마이그레이션 파일 헤더의 「롤백 절차」 절**에 있다.

### 4-0-3. V10(이슈 댓글 참조 무결성 FK) 배포 시 주의 — 앞 셋과 성질이 다르다

**V10 은 개명이 아니다. 컬럼·테이블 이름이 하나도 바뀌지 않으므로 V5·V8·V9 같은 "구 jar 가 SQL 을
못 만드는" 전진 창이 없다.** 바뀌는 것은 참조 무결성 제약 하나뿐이다.

| 대상 | 내용 |
|---|---|
| 테이블·컬럼 | `LS_ISSUE_COMMENT.DATA_ISSUE_SN` (이슈 댓글 → 소속 이슈) |
| 추가되는 제약 | `fk_ls_issue_comment_issue` → `LS_DATA_ISSUE(DATA_ISSUE_SN)` |
| 삭제 규칙 | **`ON DELETE RESTRICT`** (설계 `ERD-023` 이 규정) |

설계에는 처음부터 있던 제약인데 DB 에만 빠져 있었다. 그동안 영상이 삭제되면 이슈는
`fk_ls_data_issue_raw`(CASCADE)로 함께 사라지는데 **댓글은 존재하지 않는 이슈를 가리킨 채 조용히
남았다**(FK 위반으로 시끄럽게 실패하지 않아 아무도 몰랐다).

#### ★ 적용 시 데이터가 삭제된다 — 적용 로그에서 건수를 반드시 확인하라

FK 를 걸기 **전에** 마이그레이션이 위 경위로 생긴 **고아 댓글을 삭제**한다. 정리하지 않으면
`ALTER TABLE ... ADD CONSTRAINT` 가 기존 행 검증에서 실패해 **마이그레이션 전체가 멈춘다**(= 앱 기동 불가).

```
NOTICE:  고아 댓글 정리(부모 이슈 부재): 12 건 — 되살릴 부모가 없어 복원 대상이 아니다
```

- 이 줄이 **적용 로그에 남는 유일한 기록**이다. 지나치지 말고 건수를 **인수인계 기록에 남길 것.**
- 삭제되는 것은 **부모가 이미 사라진 댓글**뿐이다. 조회는 전부 `DATA_ISSUE_SN` 으로 들어가므로
  화면·API 어디에도 노출되지 않았고, **되살릴 부모가 없어 복원 대상이 아니다.**
- 신규 설치(빈 DB)에서는 0건이며 NOTICE 자체가 나오지 않는다. 두 번 돌아도 안전하다(멱등).
- ⚠ **댓글 본문은 로그에 남기지 않는다**(사람이 쓴 자유 텍스트라 개인정보가 섞일 수 있다).
  건수만 남으므로 **적용 전에 내용을 보존해야 한다면 DDL 적용 전에 따로 백업**해야 한다:
  ```sql
  -- (선택) 적용 전 고아 댓글 백업 — 필요할 때만
  CREATE TABLE klid_at.bk_orphan_issue_comment_v10 AS
  SELECT c.* FROM klid_at.ls_issue_comment c
   WHERE NOT EXISTS (SELECT 1 FROM klid_at.ls_data_issue i
                      WHERE i.data_issue_sn = c.data_issue_sn);
  ```

#### ★ 창을 여는 것은 Flyway 가 아니다 (§4-0-1 · §4-0-2 와 동일)

**온프렘은 어떤 구성에서도 Flyway 를 쓰지 않는다**(`SPRING_FLYWAY_ENABLED=false` — 스키마는
`db/schema.sql` 1회 로드. 그 파일에는 이 FK 가 이미 포함돼 있으므로 **신규 온프렘 설치는 이 절의
대상이 아니다**). 이 절이 다루는 것은 **이미 운영 중인 DB 에 DBA 가 수동 DDL 을 적용하는 경우**다.

#### 구 jar 공존 구간의 유일한 증상 — 장애로 오인하지 말 것

DDL 이 적용된 뒤 아직 구 jar 인 노드가 있으면 **딱 하나**가 영향을 받는다.

| 경로 | 증상 | 성질 |
|---|---|---|
| **파생영상 폐기 스윕**(유예 경과분 실삭제 배치) | 댓글이 달린 이슈를 가진 파생영상을 지울 때 RAW 삭제가 FK 위반 | **트랜잭션 전체 롤백 → 다음 tick 재시도** |

> ⚠ **데이터 손실이 아니다.** 이 배치는 원래 원자 클레임 + 롤백 구조라 부분 삭제가 남지 않는다.
> 신 jar 배포가 끝나면(선삭제 단계가 들어 있다) **자동으로 해소**된다.
> **운영자가 장애로 오인해 수동으로 행을 지우거나 제약을 떼어내는 것이 훨씬 위험하다** — 그러면
> 이 FK 가 막으려던 고아가 그대로 되살아난다.

사용자 대면 경로(댓글 등록·조회, 검수, 배정, 목록)는 **구/신 jar 모두 정상**이다. 프론트엔드 배포
순서 제약도 없다.

#### 배포 절차

| | 절차 | 대가 |
|---|---|---|
| **(a) 같은 창에서 DDL + 배포 (권장)** | DDL 적용과 앱 교체를 한 작업 창에서 끝낸다 | 사실상 없음 |
| **(b) 스키마만 먼저 적용하고 방치** | — | 그 기간 동안 위 폐기 스윕이 매 tick 실패(재시도로 회수되나 실패 로그가 쌓인다) |

V9 처럼 사용자 대면 500 이 나지는 않으므로 **다운타임 없이 롤링 재기동해도 된다.** 다만 스키마만
올려 두고 배포를 미루지는 말 것 — 그것이 (b) 다.

```bash
# 같은 창에서 DDL + 배포
source /etc/klid/was.env 2>/dev/null || WAS_UNIT='<WAS 유닛명>'
#   … DDL 적용(온프렘은 Flyway 를 쓰지 않으므로 <DBA 가 수동 적용>) …
#   … 패키지 교체(install.sh) → api.war 를 WAS 배포 디렉터리로 복사 → 노드별 재기동 …
sudo systemctl restart "$WAS_UNIT"        # backend 재기동 = WAS 재기동
# (베어메탈 토글일 때: sudo systemctl restart klid-backend)
```

반영 확인 — 제약 1건이 `RESTRICT`(`confdeltype = 'r'`)로 붙었는지 본다:

```sql
-- 1행 · delete_rule = 'r'(RESTRICT) 이어야 정상. 0행이면 미적용.
SELECT conname, confdeltype AS delete_rule
  FROM pg_constraint c
  JOIN pg_namespace n ON n.oid = c.connamespace
 WHERE n.nspname = 'klid_at'
   AND conname = 'fk_ls_issue_comment_issue';

-- 고아가 남아 있지 않은지(적용 후에는 구조적으로 0이어야 한다)
SELECT count(*) AS orphan_comments_should_be_0
  FROM klid_at.ls_issue_comment c
 WHERE NOT EXISTS (SELECT 1 FROM klid_at.ls_data_issue i
                    WHERE i.data_issue_sn = c.data_issue_sn);
```

> **롤백(구버전으로 되돌리기)** — 되돌리는 DDL 과 Flyway 이력 정리 절차는 **V10 마이그레이션 파일
> 헤더의 「롤백 절차」 절**에 있다(`backend/src/main/resources/db/migration/V10__add_ls_issue_comment_issue_fk.sql`).
> 여기에 옮겨 적지 않는 이유는 두 번째 진실원을 만들지 않기 위해서다 — **절차는 그 헤더가 정본**이다.
> 다만 성질 하나만 미리 알아 둘 것: **위에서 삭제된 고아 댓글은 롤백해도 돌아오지 않는다**(부모가 없어
> 되살릴 대상 자체가 없다). 제약을 떼는 것만으로 되돌아가는 것은 스키마뿐이다.

### 4-0-4. V21(관리자 공유 패스워드 저장소 이관) 배포 시 주의 — 배포 시점엔 무해하고, 첫 교체 이후가 다르다

V21 은 `klid_at.ls_mngr_pswd` **한 행짜리** 테이블을 만든다(`mngr_pswd_sn = 1` 체크 제약). 앞의
V5·V8·V9 처럼 롤링 재기동을 깨뜨리는 개명이 아니라 **새 테이블 추가**라, 배포 자체는 특별한 순서가
필요 없다. 배포 직후에는 행이 **비어 있고**, 그동안 쓰던 `ADMIN_CLAIM_PASSWORD_HASH` 가 그대로
판정을 맡는다 — **설치 작업은 없다.**

★ 달라지는 것은 **운영자가 화면에서 패스워드를 한 번 바꾼 뒤**다. 판정 순서가 이렇게 고정돼 있다:

| 순서 | 상태 | 판정 |
|---|---|---|
| 1 | `ls_mngr_pswd` 에 행이 **있다** | **그 값이 판정**한다. 배포 설정값으로 되돌아가지 않는다 |
| 2 | 행이 **없다** | `ADMIN_CLAIM_PASSWORD_HASH`(배포 설정값)로 판정 |
| 3 | 둘 다 없다 | **어떤 패스워드도 통과하지 않는다** |

> ★ **3번은 이제 사실상 나오지 않는다(2026-09-01)** — `ADMIN_CLAIM_PASSWORD_HASH` 에 기본값이 생겼다
> (평문 `admin`). 비워 두면 아무도 관리자가 되지 못하는데 그 상태에 **아무 신호가 없어** 첫 진입이
> 조용히 막히던 것을 없앤 것이다. 3번은 그 기본값까지 명시적으로 지운 경우에만 남는다.
>
> ⚠⚠ **널리 알려진 값이다.** 최초 관리자를 만든 직후 화면에서 반드시 교체하고, 교체하면 1번으로
> 넘어가 이 설정값은 더 이상 판정에 쓰이지 않는다.

> ⚠ **저장소를 읽지 못하는 장애도 3번과 같다** — 배포 설정값으로 되돌아가지 **않는다**. 되돌아가면
> DB 를 못 읽게 만들 수 있는 자가 이미 교체된 자격을 옛 배포 설정값으로 되돌리는 것과 같아지기
> 때문이다. 즉 **DB 장애 = 관리 기능 잠김**이며 이는 의도된 방향이다(→ 2-5).

> ⚠ **패스워드를 바꾸면 그 전에 발급된 관리자 유효창이 전부 무효**가 된다. 유효창 서명 키가 현재
> 자격 해시에서 유도되기 때문이며, 별도 폐기 목록이 없어도 그렇게 동작한다(→ 4-1).

### 4-0-5. V24(ai-server 노드 대기건수 컬럼 이동) 배포 시 주의 — 이 계열 중 유일하게 **컬럼이 사라진다**

앞의 셋(V5·V8·V9)은 이름이 바뀌는 개명이었고 V10 은 제약 추가, V21 은 저장소 이관이었다. **V24 는 부모 테이블에서 컬럼을 지운다** —
`LS_AI_SRVR.WTNG_NOCS` 를 노드 × 용도 자식 표(`LS_AI_SRVR_USG`)로 옮겼다.

구 버전 jar 는 그 컬럼을 엔티티에 매핑하고 있어, 컬럼이 사라지면 **기동 검증(`ddl-auto=validate`)에서
실패한다.** 앞 셋은 특정 경로만 실패했지만 **이건 그 노드가 아예 뜨지 않는다.**

| | 개명 계열(V5·V8·V9) | **V24** |
|---|---|---|
| 구 jar 노드의 증상 | 해당 경로만 실패, 프로세스는 산다 | **기동 자체가 실패** |
| 자기치유 | 재기동이 끝나면 됨 | 해당 없음 — 뜨지 않는다 |

⇒ **롤링 재기동으로 배포할 수 없다.** 전 노드를 교체한 뒤 마이그레이션을 적용한다. 구 버전과 신 버전이
동시에 뜨는 구간이 있으면 구 버전이 죽는다. 절차는 §4-0 의 **(a) 정지 후 배포**를 그대로 쓰되,
**DDL 적용 시점이 전 노드 교체 이후**라는 점만 다르다.

> ★ **이 제약은 같은 배포 창의 다른 변경에도 번진다.** 마이그레이션이 없어 단독으로는 무중단이 되는
> 변경이라도, 이 변경과 **한 창에 묶으면 그 성질을 잃는다** — 제약이 창 전체를 지배하기 때문이다.
> ⚠ **파일이 안 겹쳐도 배포 창은 겹친다.** 이 구분을 놓치기 쉬우니, 무중단이 필요한 변경은
> **배포 창을 나눠** 내보낸다.

> 근거: PR #169(`lb-test`) 병합. ⚠ 그 PR 본문과 병합 커밋에는 이 제약이 실려 있지 않다 —
> 도구로 본문을 고치지 못해 **이 문서가 유일한 기록**이다.

#### 이 배포 창의 마이그레이션 전수 — 셋 중 문제는 하나뿐이다

| | 무엇 | 성질 |
|---|---|---|
| **V23** | `LS_AI_SRVR` · `LS_AI_SRVR_ALTMNT` 신설 | 순수 가산 — 구 jar 무해 |
| **V24** | `LS_AI_SRVR.WTNG_NOCS` → `LS_AI_SRVR_USG` 로 이동 | ★**컬럼 삭제 — 위 제약의 원인** |
| **V25** | `LS_WEBHOOK_IDEMPOTENCY.SRVR_ID` 컬럼 추가 | 순수 가산 — 구 jar 무해 |

⇒ **V23·V25 만 먼저 적용하는 분할은 의미가 없다.** 셋이 한 배포에 묶여 있고 문제는 V24 하나이므로,
가르는 축은 마이그레이션이 아니라 **노드 교체 순서**다.

#### dev(단일 노드)에서 먼저 확인했다

2026-09-01 dev 서버에 같은 커밋을 올려 **V23·V24·V25 가 2.1초에 정상 적용**됐고
(`Successfully applied 3 migrations … now at version v25`), 세 컨테이너 전부 healthy,
노드 원장에 기존 설정값으로 1건이 자동 등록돼 **기존 동작이 그대로 유지**되는 것까지 확인했다.

⚠ **그것이 이 제약을 무효화하지 않는다.** dev 는 **노드가 하나**라 정지 후 기동이고,
구 jar 와 신 스키마가 공존하는 창 자체가 없었다. 이 제약은 **2노드 구성에서만** 발현한다.


### 4-1. 관리자 세션 토큰 비상 무효화

연동 서버 주소를 바꿀 때 쓰는 관리자 단기 유효창 토큰(`X-Admin-Session`)은 **무상태 서명 토큰**이라
개별 폐기 목록이 없다(설계 제약 — 공유 저장소·새 테이블을 두지 않기 위한 선택). 유출이 의심되면
**아래 둘 중 하나로 즉시 전량 무효화**한다. 어느 쪽이든 이미 발급된 토큰이 전부 검증에 실패한다.

| 수단 | 방법 | 영향 범위 |
|---|---|---|
| 토큰 페이로드 버전 상향 | `AdminSessionTokenService` 의 `PAYLOAD_VERSION` 을 올려 재배포 | 관리자 세션 토큰만 무효화 (로그인 세션 영향 없음) |
| JWT 서명 키 회전 | `/etc/klid/application.properties` 의 `JWT_SECRET` 교체 후 **WAS 재기동**(`sudo systemctl restart "$WAS_UNIT"`) | 관리자 세션 토큰 + **관제/포털 인계 JWT 전부** 무효화 — 발급 주체와 협의 필요 |

> 토큰 유효기간이 기본 10분·상한 30분이라 **방치해도 그 시간 안에 자연 만료**된다. 위 조치는
> 그 시간을 기다릴 수 없을 때만 쓴다. 무효화 후에는 운영자가 관리자 패스워드로 창을 다시 연다.


### 4-2. 관리자 자격을 잃었을 때 (패스워드 · 계정)

두 가지가 서로 다른 문제인데 증상이 «관리 화면에 못 들어간다» 로 같아 헷갈린다. **먼저 어느 쪽인지
가른다.**

```bash
# ① 자격(패스워드)이 어디에 있는가 — 행이 있으면 .env 값은 이미 무시되고 있다
sudo -u postgres psql -d klid_system -tAc \
  "SELECT count(*) AS rows_in_storage, max(pswd_mdfcn_dt) AS last_change
     FROM klid_at.ls_mngr_pswd;"

# ② 관리자 계정이 몇 명인가 — 0 이면 계정 쪽 문제다
sudo -u postgres psql -d klid_system -tAc \
  "SELECT role_cd, count(*) FROM klid_at.ls_user_role GROUP BY role_cd ORDER BY 1;"
```

**A. 설정 파일의 `ADMIN_CLAIM_PASSWORD_HASH` 를 바꿨는데 반영되지 않는다**
(WAR 형상은 `/etc/klid/application.properties`, 베어메탈 토글은 `/etc/klid/backend.env`)
①의 행이 1 이면 정상 동작이다 — 저장소가 우선이라 배포 설정값은 판정에 쓰이지 않는다(→ 4-0-4).
설정 파일을 고치는 것으로는 되돌릴 수 없고, 아래 B 로 저장소 값을 비우거나 화면에서 다시 바꾼다.

**B. 화면에서 바꾼 패스워드를 잊어 관리 기능에 들어갈 수 없다**
저장소 행을 지우면 **배포 설정값으로 되돌아간다**. 화면에서 바꿀 수 없는 상태이므로 이때만 DB 를
직접 고친다.

```bash
# 되돌리기 전에 현재 값을 남긴다 (해시만 있고 평문은 어디에도 없다)
sudo -u postgres psql -d klid_system -tAc \
  "SELECT pswd_hash, mdfr_id, pswd_mdfcn_dt FROM klid_at.ls_mngr_pswd;" \
  > /var/backups/klid/mngr_pswd-$(date +%Y%m%d-%H%M%S).bak

sudo -u postgres psql -d klid_system -c "DELETE FROM klid_at.ls_mngr_pswd;"
source /etc/klid/was.env 2>/dev/null || WAS_UNIT='<WAS 유닛명>'
sudo systemctl restart "$WAS_UNIT"     # backend 재기동 = WAS 재기동
# (베어메탈 토글일 때: sudo systemctl restart klid-backend)
```

> ⚠ 지운 뒤에는 설정 파일의 `ADMIN_CLAIM_PASSWORD_HASH` 가 다시 판정한다. **그 값이 비어 있으면
> 아무 패스워드도 통과하지 않으므로**(4-0-4 의 3번) 지우기 전에 그 값이 설정돼 있는지 확인한다.
> ⚠ 이 조치로 **발급된 관리자 유효창이 전부 무효**가 된다(→ 4-1).

**C. 관리자(ADMIN) 계정이 0명이라 관리 화면에 아무도 못 들어간다**
자격이 아니라 **역할**의 문제다. 최초 관리자 등록(정규 경로)과 저장소 직접 복구 SQL은
[04-configuration.md](04-configuration.md) 의 「G. 최초 관리자(ADMIN) 만들기」 절이 정본이다 — 여기에 옮겨 적지
않는다(두 번째 진실원을 만들지 않는다). 다만 성질 하나는 알아 둘 것: **관리자가 0명이면 자가부여
창구가 다시 열려** 정규 경로로 회복할 수 있고, 한 명이라도 생기면 그 창구는 닫힌다.

> ⚠ **DB 를 복원하면 패스워드도 그 시점으로 되돌아간다.** `ls_mngr_pswd` 는 `klid_at` 스키마 안에
> 있어 스키마 덤프에 함께 담긴다(→ [10-backup-dr.md](10-backup-dr.md)). 복원 뒤 관리자 패스워드가
> 예전 것으로 돌아가 있어도 결함이 아니다.

---

## 5. 정기 점검 체크리스트 (권장 주기)

| 주기 | 점검 항목 | 명령/방법 |
|---|---|---|
| 일 | 4개 구성요소 active·헬스 200/UP (backend 는 **WAS 유닛 + liveness** 두 축) | 0·1-1·1-2 |
| 일 | 배치 상태별 건수(PENDING/FAILED 적체) | 1-4 |
| 일 | 디스크 여유(저장소·로그) | 1-5 |
| 주 | 스케줄러 클러스터 노드 수·노드 간 시계 동기(NTP) | 1-4-1 |
| 주 | 비식별 미완료('F') 잔량·조치 | 1-4·2-4 |
| 주 | 외부 연동 헬스(DOWN 컴포넌트) | 1-2·2-3 |
| 주 | 오래된 로그 정리 | 2-6 |
| 월 | GPU 사용률 추이·추론 병목 | 1-5·2-2 |
| 월 | DB 백업·복구 리허설 | [10-backup-dr.md](10-backup-dr.md) §5 |

> **백업/복구·DR**: PostgreSQL(`klid_system`·`portal`)·설정(`/etc/klid`)·저장소(`/nas-storage`)의 백업 대상·주기·복원·전체 서버 DR 절차는 [10-backup-dr.md](10-backup-dr.md)를 따른다. RPO/RTO 목표값은 운영 정책으로 확정한다.
