# 05. 기동 및 검증

## 기동 순서

의존 순서대로 **PostgreSQL → ai-server → backend(WAS) → frontend(httpd)** 로 켠다.
⚠ **PostgreSQL 은 현장이 운영한다** — 우리 설치는 켜지도 끄지도 않는다(2026-09-05).

> ★ **서버가 2대면 이 절의 명령이 장비마다 갈린다** (`--role=app` / `--role=ai`).
> `klid-ai-server` 는 **서버 B(ai)** 에만, `httpd` 와 WAS 는 **서버 A(app)** 에만 있다.
> 한 장비에서 전부 돌리려다 없는 유닛에 `systemctl` 을 걸면 실패한다.
> 단일 서버 설치(`--role` 미지정 = `all`)면 종전대로 한 장비에서 전부 확인한다.

> ★ **ffmpeg 은 우리가 설치하지 않는다 — 서버 A 의 전제조건이다.**
> 없으면 backend 는 **정상 기동하고 헬스체크도 통과하지만** 프레임 추출·영상 메타 조회가
> 배치 실행 시점에 죽는다. 그래서 설치 마지막 단계 `19-verify-ffmpeg.sh` 가 die 한다.
> 기동 전에 `command -v ffmpeg ffprobe` 로 확인하고, 없으면
> `sudo ./scripts/install/install-ffmpeg.sh` 로 번들 판을 설치한다
> (관제지원시스템과 공동 배치라 자동 설치하지 않는다 — [06-troubleshooting.md](06-troubleshooting.md)).

> ★ **backend 는 systemd 유닛이 아니다** (배포 형상 = 외부 WAS 에 WAR 반입, @design DEPLOY-001).
> `api.war` 를 WAS 배포 디렉터리에 올리고 **WAS 를 기동**한다. 그 전에 **WAS 설정 이관**
> ([10-was-settings.md](10-was-settings.md))이 끝나 있어야 한다 — 안 끝나 있어도 기동은 성공하고
> 대용량 업로드에서만 실패하므로, 기동 성공을 그 단계의 완료 근거로 쓰지 말 것.
>
> ⚠ 구 서술 폐기(2026-08-30) — `sudo systemctl enable --now klid-backend` / `klid-frontend`.
> 전자는 베어메탈 형상 전용 유닛이라 기본 설치되지 않고, 후자는 **애초에 존재한 적 없는 유닛명**이다
> (웹 서버는 배포판 `httpd.service` — 2026-08-19 Caddy 폐기 이후).

```bash
sudo systemctl daemon-reload

# (번들 PG 사용 시) DB 가 떠 있는지 먼저 확인 — backend 전에 기동되어야 한다.
systemctl is-active postgresql-16    # active 가 아니면: sudo systemctl enable --now postgresql-16

# enable + 즉시 시작 (한 번에)
sudo systemctl enable --now klid-ai-server
sudo systemctl enable --now httpd

# backend — WAS 배포 후 WAS 를 기동한다(유닛명은 WAS 설치 형상에 따름).
#   예) sudo cp /opt/klid/app/api.war <WAS_HOME>/webapps/api.war   # 파일명 변경 금지
#       sudo systemctl restart <WAS 유닛명>
```

> ⚠ 아래 「번들 PG 순서 drop-in」 설명은 **베어메탈 형상(systemd 유닛으로 backend 를 띄우는 경우)**
> 에만 해당한다. WAR 형상에서는 기동 순서를 WAS 가 갖고 있으므로, **WAS 유닛에 PG·ai-server 기동
> 순서를 거는 것은 WAS 설정 소관**이다(우리 drop-in 은 만들어지지 않는다 — 유닛 자체가 없다).
>
> backend 유닛은 `Requires=klid-ai-server` + `After=postgresql.service klid-ai-server.service` 라
> ai-server 준비 후 기동된다. ⚠ **기동 전에 스키마가 이미 로드돼 있어야 한다** — 온프렘은 Flyway 를
> 쓰지 않으므로 backend 가 테이블을 만들지 않는다. ⚠⚠ 비어 있어도 **기동은 성공한다**
> (`ddl-auto=validate` 가 실동작하지 않는다 — 04-configuration.md D 절). 화면·배치가 DB 를 처음
> 건드릴 때 깨지므로, 로드 여부는 아래 「스키마 확인」의 카운트 쿼리로 판정한다.
>
> ⚠ **구 안내 폐기(2026-09-05)** — *"번들 PG 의 유닛명은 `postgresql-16.service` 라 …
> `10-install-postgresql.sh` 가 설치 말미에 drop-in 을 자동 생성해 순서를 묶는다"*.
> 데이터베이스가 **다른 장비**에 있으므로 같은 장비의 부팅 순서로 묶을 대상이 아니다.
> 참고로 그 drop-in 은 이런 모양이었다:
>
> ```ini
> # /etc/systemd/system/klid-backend.service.d/10-pg16-after.conf  (자동 생성)
> [Unit]
> After=postgresql-16.service
> Wants=postgresql-16.service
> ```
>
> 따라서 별도 수동 조치는 불필요하다. 생성 여부는 아래로 확인한다:
>
> ```bash
> cat /etc/systemd/system/klid-backend.service.d/10-pg16-after.conf
> systemctl show klid-backend -p After | tr ' ' '\n' | grep postgresql-16
> ```

## 상태 확인

```bash
# 서버 A(app): httpd + WAS  /  서버 B(ai): klid-ai-server
systemctl status klid-ai-server httpd --no-pager
# backend 는 WAS 프로세스 안에 있다 — WAS 유닛 상태와 아래 liveness 로 확인한다.
```

## PostgreSQL 스모크 (번들 PG)

```bash
# 서비스 active 확인
systemctl is-active postgresql-16

# 접속 확인(앱 유저로 control/portal DB 접속) — 비밀번호는 backend.env 와 동일
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system -c '\conninfo'
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d portal      -c '\conninfo'

# ★ WAR 를 올리기 <전에> 스키마가 로드됐는지 확인한다 — 비어 있으면 backend 가 기동에 실패한다.
#   ★ 저작도구 객체는 klid_at 스키마에 있다. psql 기본 search_path("$user", public)로는 보이지 않으므로
#     스키마를 명시한다(아래 모든 조회 동일).
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "select count(*) from information_schema.tables where table_schema='klid_at';"
#   → 0 이면 db/schema.sql 이 로드되지 않은 것이다(16-load-schema.sh 또는 DBA 수동 로드).
#     정상이면 82 — information_schema.tables 는 뷰를 포함한다(테이블 78 = LS_* 67 + QRTZ_* 11, 뷰 4).
#     ⚠ 이 숫자는 마이그레이션이 늘면 바뀐다. 외우지 말고 매체에서 다시 세라:
#        grep -c '^CREATE TABLE klid_at\.' db/schema.sql   +   grep -c '^CREATE VIEW klid_at\.' db/schema.sql
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "\dt klid_at.*" | grep -iE 'ls_data_raw|ls_marking|qrtz_'
```

> **스키마 = `klid_at`** (`DB_SCHEMA` 로 변경 가능). 앱은 커넥션 `currentSchema` /
> Quartz `tablePrefix` / JPA `default_schema` 가 모두 이 값 하나를 읽는다.
> 온프렘은 **Flyway 를 쓰지 않으므로**(`SPRING_FLYWAY_ENABLED=false`) 이 스키마는 설치 시
> `db/schema.sql` 로드로 만들어지고, **`flyway_schema_history` 는 생성되지 않는다**
> (덤프에서 의도적으로 제외). 그 테이블이 없는 것은 정상이며 결함이 아니다.

> ⚠ 따라서 backend 로그에서 **Flyway 마이그레이션 로그를 찾지 말 것** — 나오지 않는 것이 정상이다.
> 스키마가 준비됐다는 증거는 위 `information_schema.tables` 카운트다.

## 헬스체크 (스모크)

```bash
# ai-server
curl -fsS http://127.0.0.1:9300/health

# backend liveness
curl -fsS http://127.0.0.1:8080/api/actuator/health/liveness

# frontend (httpd SPA)
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1/

# 프록시 경로(프론트 → backend) 점검
curl -fsS http://127.0.0.1/api/actuator/health/liveness
```

기대: ai-server `{"status":"ok"}` 류, backend liveness `{"status":"UP"}`, frontend `200`.

## 프론트엔드 런타임 설정 확인

화면이 뜨는 것과 **상위 로그인 주소가 맞는 것**은 다른 문제다. 주소가 비거나 틀리면 세션 만료
전까지는 아무 증상이 없다가, 만료되는 순간 사용자가 갈 곳을 잃는다. 설치 직후 한 번 본다.

```bash
# 1) 생성물이 실제로 서빙되는가 + 어떤 값이 실렸는가
curl -fsS http://127.0.0.1/klid-config.js

# 2) 캐시되지 않는가 (no-store 가 없으면 값을 고쳐도 브라우저가 옛 값을 계속 쓴다)
curl -sS -o /dev/null -D - http://127.0.0.1/klid-config.js | grep -i cache-control
```

기대: 1)에 `VITE_CONTROL_LOGIN_URL`/`VITE_PORTAL_LOGIN_URL` 이 **현장 주소**로 보이고,
2)에 `Cache-Control: no-store...` 가 보인다.

- 값이 예시 주소(`*.example.local`)로 보이면 정본을 안 고친 것이다.
- 값을 바꾸는 절차는 **재빌드·재설치가 아니라 한 줄**이다:
  ```bash
  sudo vi /etc/klid/frontend.env
  sudo /opt/klid/bin/klid-frontend-config
  ```
  (웹 서버 재기동도 필요 없다. 브라우저 새로고침이면 반영된다 — `04-configuration.md` C-1 절)
- ★ **이 파일은 브라우저로 그대로 내려간다.** 출력에 비밀값이 보이면 정본에 잘못 적은 것이다 —
  생성기가 allowlist 로 거르지만, 그 목록에 키를 추가하는 순간 공개된다.

## 로그 위치

```bash
# systemd journald (권장)
journalctl -u klid-ai-server -f
journalctl -u httpd          -f
# backend 로그는 WAS 의 로그다 — WAS 유닛(journalctl -u <WAS 유닛명>) 또는 WAS 로그 디렉터리를 본다.
# (베어메탈 형상에서만 journalctl -u klid-backend 가 존재한다)

# 앱 로그 디렉토리(앱이 파일 로깅 시)
ls -al /var/log/klid
```

## 설치 직후 — 관리자 등록이 남아 있다

헬스가 모두 UP 이어도 **관리자(ADMIN)는 아직 0명**이라 관리 영역(`/admin/*` — 사용자 관리 · 연동
서버 주소 · 파일 업로드 · 산출물 가져오기 · 패스워드 교체 · 위험 작업)에는 아무도 들어가지 못한다.
검수자·작업자 기능은 그대로 쓸 수 있으므로 **장애가 아니라 남은 설치 절차**다.

```bash
# 확인 — 0 이면 아직 등록 전이다
sudo -u postgres psql -d klid_system -tAc \
  "SELECT count(*) FROM klid_at.ls_user_role WHERE role_cd = 'ADMIN';"
```

절차는 [04-configuration.md](04-configuration.md) 의 「G. 최초 관리자(ADMIN) 만들기」 절이 정본이다. 자가부여 창구는
**관리자가 0명일 때만** 열리고 한 명이라도 생기면 닫히므로, 최초 1회는 그 창이 열려 있는 동안 끝낸다.

> ★ **기본값이 있어 비워 두어도 성립한다(2026-09-01) — 평문 `admin`.** 종전에는 비어 있으면
> 창구가 **항상 401** 이라 창이 열려 있어도 아무도 통과하지 못했는데, 여기까지의 헬스·스모크는
> 전부 정상이라 화면에는 "패스워드가 틀렸다"로만 보였다. 그 무신호 상태를 없앤 것이다.
>
> ⚠⚠ **널리 알려진 값이므로 최초 관리자를 만든 직후 화면에서 반드시 교체한다.**
> 다른 값을 설정 파일에 넣었다면 아래로 확인한다(넣지 않았다면 0 이 나오는 것이 정상이다).
>
> ```bash
> sudo grep -c '^ADMIN_CLAIM_PASSWORD_HASH=\$2[aby]\$' /etc/klid/application.properties
> # 1 이면 설정됨. 0 이면 미설정이거나 평문(평문이면 애초에 기동이 실패한다)
> ```

## 추가 스모크(선택)

```bash
# ai-server 추론 헬스(백엔드별 mock/실모델 동작 확인은 운영 시나리오에 맞춰 별도 수행)
# backend actuator(노출 토글에 따라 readiness/info 등)
curl -fsS http://127.0.0.1:8080/api/actuator/health
```

## 비식별(KPST) 연동 스모크

비식별은 파이프라인 선두 필수 단계라 동거 KPST 연동을 확인한다(04-configuration.md B 절 확정 정책).

- **부팅 통과 자체가 1차 확인**: https base-url + CA 미설정이면 fail-closed 로 기동 실패한다.
  backend 가 정상 기동했다면 KPST SSL 컨텍스트 구성은 통과한 것이다.
- **KPST 도달 확인**(설치값 IP/PORT 로 치환):

  ```bash
  # http 격리망: 단순 연결/헬스(엔드포인트는 KPST 제공값)
  curl -fsS http://<KPST_IP>:<PORT>/   # 또는 KPST 헬스 경로

  # https + 사설 CA: CA 로 검증 연결
  curl -fsS --cacert /etc/klid/kpst-ca.crt https://<KPST_IP>:<PORT>/
  ```

- **end-to-end 전이 확인**(운영 시나리오): 관제 학습용 설정 영상 1건이 적재(`LS_DATA_RAW` PENDING)된 뒤
  선두 비식별이 돌면 해당 영상의 `DE_IDENT_YN='Y'`(비식별 완료) + `DATA_STTS_CD=MARKING_READY` 로 전이된다.
  계속 `DE_IDENT_YN='F'` 면 KPST 연동/주소/CA 를 점검한다 → [06-troubleshooting.md](06-troubleshooting.md).

## 재시작 / 정지

```bash
sudo systemctl restart <WAS 유닛명>              # backend 재기동 = WAS 재기동
sudo systemctl stop httpd klid-ai-server
sudo systemctl stop <WAS 유닛명>
```

## 환경설정 변경 반영

`/etc/klid/*.env` 수정 후:

```bash
sudo systemctl restart <WAS 유닛명>     # backend(.env 는 WAS 가 읽는다 — 아래 주의)
sudo systemctl restart klid-ai-server
```

> ⚠ `/etc/klid/backend.env` 는 **베어메탈 유닛의 `EnvironmentFile`** 로 주입되던 파일이다.
> WAR 형상에서는 그 유닛이 없으므로 **WAS 프로세스에 같은 환경변수가 전달되도록 WAS 쪽에서
> 배선**해야 한다(EAP: `bin/standalone.conf` 의 `JAVA_OPTS`, 또는 WAS 유닛의 `EnvironmentFile`). 배선하지 않으면
> DB 접속·시크릿이 비어 기동이 실패한다.
