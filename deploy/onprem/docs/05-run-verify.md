# 05. 기동 및 검증

## 기동 순서

의존 순서대로 **PostgreSQL → ai-server → backend → frontend** 로 켠다(유닛에도 의존성이 박혀 있다).
번들 PG 를 설치했다면 `10-install-postgresql.sh` 가 이미 `postgresql-16` 을 `enable --now` 해 둔다.

```bash
sudo systemctl daemon-reload

# (번들 PG 사용 시) DB 가 떠 있는지 먼저 확인 — backend 전에 기동되어야 한다.
systemctl is-active postgresql-16    # active 가 아니면: sudo systemctl enable --now postgresql-16

# enable + 즉시 시작 (한 번에)
sudo systemctl enable --now klid-ai-server
sudo systemctl enable --now klid-backend
sudo systemctl enable --now klid-frontend
```

> backend 유닛은 `Requires=klid-ai-server` + `After=postgresql.service klid-ai-server.service` 라
> ai-server 준비 후 기동된다. backend 첫 기동 시 Flyway 가 LS_*·MNG_*·QRTZ_* 스키마를 자동
> 부트스트랩(`CREATE TABLE IF NOT EXISTS`)하므로 시작이 다소 길 수 있다(TimeoutStartSec=180).
>
> ℹ **번들 PG 의 유닛명은 `postgresql-16.service`** 라 backend 유닛의 `After=postgresql.service`
> (이름 불일치)만으로는 부팅 순서 보장이 안 된다. 이를 위해 **번들 PG 사용 시
> `10-install-postgresql.sh` 가 설치 말미에 drop-in 을 자동 생성**해 실제 유닛명으로 순서를 묶는다
> (외부 PG 사용 시엔 생성하지 않는다):
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
systemctl status klid-ai-server klid-backend klid-frontend --no-pager
```

## PostgreSQL 스모크 (번들 PG)

```bash
# 서비스 active 확인
systemctl is-active postgresql-16

# 접속 확인(앱 유저로 control/portal DB 접속) — 비밀번호는 backend.env 와 동일
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system -c '\conninfo'
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d portal      -c '\conninfo'

# (backend 기동 후) Flyway 가 스키마를 자동 생성했는지 — LS_*/MNG_*/QRTZ_* 테이블 확인
#   ★ 저작도구 객체는 klid_at 스키마에 있다. psql 기본 search_path("$user", public)로는 보이지 않으므로
#     스키마를 명시한다(아래 모든 조회 동일).
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "\dt klid_at.*" | grep -iE 'ls_data_raw|ls_marking|qrtz_'
# flyway_schema_history 도 생성된다(마이그레이션 이력)
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "select version, description, success from klid_at.flyway_schema_history order by installed_rank;"
```

> **스키마 = `klid_at`** (`DB_SCHEMA` 로 변경 가능). 앱은 커넥션 `currentSchema` / Flyway `schemas` /
> Quartz `tablePrefix` / JPA `default_schema` 네 지점이 모두 이 값 하나를 읽는다.
> 온프렘(`SPRING_FLYWAY_ENABLED=false`)은 설치 시 `db/schema.sql` 로드로 이 스키마가 만들어지며,
> 그 경우 `flyway_schema_history` 는 생성되지 않는다(덤프에서 의도적으로 제외 — Flyway 미사용).

> backend 로그(`journalctl -u klid-backend`)에 Flyway `Migrating schema ... to version 2`,
> `Successfully applied N migration(s)` 가 보이면 스키마 자동 부트스트랩이 성공한 것이다.

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

## 로그 위치

```bash
# systemd journald (권장)
journalctl -u klid-backend   -f
journalctl -u klid-ai-server -f
journalctl -u klid-frontend  -f

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
sudo systemctl restart klid-backend
sudo systemctl stop klid-frontend klid-backend klid-ai-server
```

## 환경설정 변경 반영

`/etc/klid/*.env` 수정 후:

```bash
sudo systemctl restart klid-backend     # 또는 klid-ai-server
```
