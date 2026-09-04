# 10. 백업 및 재해복구(DR)

> 운영 단계의 **백업 대상·주기·절차**와 **재해복구(전체 서버 손실 시 복원)** 를 다룬다.
> 상태 확인·장애 조치는 [09-operations-runbook.md](09-operations-runbook.md), 설치는 [03-install.md](03-install.md)를 참조한다.

> **⚠ 책임 경계 — 데이터베이스는 외부 인프라 제공(저작도구 미운영)**
> 관계형 데이터베이스는 외부 인프라로 제공되며 **DB 서버의 백업·복구·가용성은 DB 운영(인프라) 주체 책임**이다.
> 저작도구가 **직접 관리·백업하는 대상**은 **환경설정/시크릿(`/etc/klid`)** 이며, 저장소(`/nas-storage`)는 스토리지(NAS) 운영 정책을 따른다.
> 아래 §1 DB 백업/복구·§4 DR 의 DB 절차는 **온프레미스 번들 PostgreSQL 로 단독(폐쇄망 자족) 구성한 경우에 한한 참고**이며,
> 운영 환경에서 외부 DB 를 사용할 때는 **DB 운영 주체의 백업/복구 절차를 따른다**(본 문서 범위 밖).

## 백업 대상과 책임 주체

| 등급 | 대상 | 경로 | 책임 주체 | 백업 방식 | 권장 주기 |
|---|---|---|---|---|---|
| **T1 (저작도구 소관·필수)** | 환경설정·시크릿 | `/etc/klid/` 전체 — **`application.properties`(WAR 형상 정본)** · `*.env` · `was.env` · `kpst-ca.crt` 등 | **저작도구 운영자** | 파일 복사(권한 640 유지) | 변경 시 + 주 1회 |
| **T2 (스토리지 운영 정책)** | 저장소: 비식별 영상·프레임 | `STORAGE_RAW_PATH`·`STORAGE_DEIDENTIFIED_PATH`(기본 `/nas-storage`) | 스토리지(NAS) 운영 주체 | `rsync` 증분 또는 스토리지 스냅샷 | **일 1회(증분)** |
| **외부 책임** | DB: `klid_system`(관제/저작도구)·`portal` | 외부 DB 인프라 | **DB 운영(인프라) 주체** | DB 운영 주체 정책(참고 §1) | DB 운영 주체 정책 |
| **재설치 가능** | 앱·런타임 | `/opt/klid`, `/var/lib/klid` | 설치 패키지로 재설치 | 백업 불필요(패키지 보관으로 갈음) | — |

> **DB 스키마는 덤프에 포함**된다 — 온프렘은 **Flyway 를 쓰지 않으므로**(`flyway_schema_history` 가 아예
> 없다) 복원본이 곧 최종 스키마이고, 복원 후 재마이그레이션 같은 후속 동작도 없다. `klid_system` 에
> **`LS_*`·`QRTZ_*` 와 뷰**가 모두 들어 있어 그 DB 덤프 하나로 저작도구 전체 스키마가 보존된다.
> (외부 DB 사용 시 스키마·데이터 백업은 DB 운영 주체 소관.)
> ⚠ **빈 DB 로 복구할 때는 `db/schema.sql` 을 먼저 로드해야 한다** — 앱이 만들어 주지 않는다.
>
> **★ 저작도구 객체는 `klid_at` 스키마에 있다** — DB 전체 덤프(`pg_dump -d klid_system`)는 스키마 단위가
> 아니라 DB 단위라 그대로 포함된다. 다만 **스키마를 한정한 덤프**(`-n public`)를 쓰면 저작도구 데이터가
> 통째로 빠지므로 쓰지 않는다. 복원 후 검증 조회도 `klid_at.` 로 한정해야 한다(§3-1).
>
> **저장소 원본 주의**: 원본(비-비식별) 영상은 관제 NAS 절대경로에 있고 저작도구는 경로만 기록한다.
> 저작도구 저장소(`/nas-storage`)에는 **비식별 영상·프레임(작업 산출물)** 이 위치하므로 T2 백업 대상은 이 산출물이다.
> KPST 공유 export 경로(`KPST_DEID_EXPORT_PATH_BASE`, 기본 `/share/...`)는 KPST 소유 마운트로 백업 책임 밖이다.

## 1. 데이터베이스 백업 — *번들 PG 단독 구성 한정(참고)*

> **외부 DB 를 사용하는 운영 환경에서는 이 절이 적용되지 않는다** — DB 백업/복구는 DB 운영(인프라) 주체 책임이다.
> 아래는 온프레미스 번들 PostgreSQL 16(로컬 `127.0.0.1:5432`, OS 유저 `postgres` peer 인증)으로 DB 까지 자족 운영하는 경우의 참고 절차다.

### 1-1. 수동 백업(즉시)

```bash
BK=/backup/klid/$(date +%F)        # 백업 보관 위치(별도 디스크·NAS 권장)
sudo mkdir -p "$BK" && sudo chown postgres:postgres "$BK"

# 롤/권한(글로벌) — 계정·비밀번호 메타
sudo -u postgres pg_dumpall --globals-only > "$BK/globals.sql"

# DB별 덤프(custom 포맷 -Fc: 압축·선택복원 가능)
sudo -u postgres pg_dump -Fc -d klid_system > "$BK/klid_system.dump"
sudo -u postgres pg_dump -Fc -d portal      > "$BK/portal.dump"

# 무결성 간이 확인(항목 나열이 되면 정상)
sudo -u postgres pg_restore -l "$BK/klid_system.dump" | head
```

### 1-2. 자동 백업(cron)

`/opt/klid/bin/klid-db-backup.sh` 로 저장 후 실행 권한 부여, cron 등록:

```bash
#!/usr/bin/env bash
# /opt/klid/bin/klid-db-backup.sh — 번들 PG16 일일 백업 + 보관주기 정리
set -euo pipefail
ROOT=/backup/klid
KEEP_DAYS=14
DEST="$ROOT/$(date +%F)"
mkdir -p "$DEST"
sudo -u postgres pg_dumpall --globals-only            > "$DEST/globals.sql"
sudo -u postgres pg_dump -Fc -d klid_system           > "$DEST/klid_system.dump"
sudo -u postgres pg_dump -Fc -d portal                > "$DEST/portal.dump"
# 설정·시크릿 동반 백업(권한 유지)
tar -C /etc -czpf "$DEST/etc-klid.tgz" klid
# 보관주기 초과분 삭제
find "$ROOT" -maxdepth 1 -type d -mtime +"$KEEP_DAYS" -exec rm -rf {} \;
echo "[backup] done: $DEST"
```

```bash
sudo install -m 750 -o root -g klid /dev/stdin /opt/klid/bin/klid-db-backup.sh < klid-db-backup.sh
# 매일 02:30 실행(crontab -e, root)
30 2 * * * /opt/klid/bin/klid-db-backup.sh >> /var/log/klid/backup.log 2>&1
```

> 백업본은 **원본 서버와 다른 디스크/NAS** 에 보관한다(동일 디스크 백업은 디스크 장애 시 무의미).
> 시크릿을 담은 `etc-klid.tgz`·`globals.sql` 은 접근 권한을 제한하고 안전하게 보관한다.

## 2. 저장소(영상·프레임) 백업

```bash
# 증분 백업(rsync) — 삭제 반영 없이 누적 보존하려면 --delete 생략
sudo rsync -a --info=progress2 /nas-storage/ /backup/klid-storage/
# 스토리지가 스냅샷 지원 NAS면 스냅샷 정책을 우선 사용(대용량에 유리)
```

- 저장소는 대용량이므로 전량 재백업보다 **증분(rsync)·스냅샷** 을 권장한다.
- 경로는 설정 파일(WAR 형상 `application.properties` · 베어메탈 토글 `backend.env`)의
  `STORAGE_RAW_PATH`·`STORAGE_DEIDENTIFIED_PATH` 실제 설정값을 사용한다.

## 3. 복원(Restore)

### 3-1. DB 복원(부분 — 특정 DB만)

```bash
# 서비스 정지(쓰기 차단) 후 복원 권장
#   ★ backend 정지 = WAS 정지다 — 백엔드는 systemd 유닛이 아니라 외부 WAS 가 api.war 를 기동한다
#     (배포 형상 @design DEPLOY-001 · 09-operations-runbook.md §0).
#     <WAS 유닛명> 등 현장값은 설치 시 /etc/klid/was.env 에 적어 둔다(같은 §0-1).
source /etc/klid/was.env 2>/dev/null || WAS_UNIT='<WAS 유닛명>'
sudo systemctl stop "$WAS_UNIT"
#   (베어메탈 토글 형상이면: sudo systemctl stop klid-backend)
#   ⚠ WAS 를 통째로 내릴 수 없으면 api.war 컨텍스트만 정지시킨다 — 필요한 것은 "앱이 DB 에 붙어
#     있지 않다" 이지 "WAS 프로세스가 없다" 가 아니다.

# 대상 DB 재생성(기존 손상분 폐기 시) — 주의: 데이터 삭제
sudo -u postgres dropdb --if-exists klid_system
sudo -u postgres createdb -O klid_user -E UTF8 klid_system
sudo -u postgres pg_restore -d klid_system --no-owner /backup/klid/<날짜>/klid_system.dump

# portal 동일
sudo -u postgres dropdb --if-exists portal
sudo -u postgres createdb -O klid_user -E UTF8 portal
sudo -u postgres pg_restore -d portal --no-owner /backup/klid/<날짜>/portal.dump

sudo systemctl start "$WAS_UNIT"
#   (베어메탈 토글 형상이면: sudo systemctl start klid-backend)
# 검증: 핵심 테이블 확인 (저작도구 객체는 klid_at 스키마 — 스키마 한정 필수)
sudo -u postgres psql -d klid_system \
  -c "select count(*) from klid_at.ls_data_raw;"
# Flyway 부트스트랩 구성이면 이력도 확인(온프렘 기본 구성에는 이 테이블이 없다)
sudo -u postgres psql -d klid_system \
  -c "select count(*) from klid_at.flyway_schema_history;"
```

> 롤이 없는 새 인스턴스라면 DB 복원 전 `sudo -u postgres psql -f /backup/klid/<날짜>/globals.sql` 로
> 롤/권한을 먼저 복원한다.

> ⚠ **DB 를 복원하면 관리자 공유 패스워드도 그 시점으로 되돌아간다.** 운영 중 화면에서 바꾼
> 패스워드는 `klid_at.ls_mngr_pswd`(한 행)에 저장되므로 스키마 덤프에 함께 담긴다. 복원 뒤 관리자
> 패스워드가 예전 것으로 보이는 것은 결함이 아니다. 그 값을 모르면 저장소 행을 비워 배포 설정값
> (`ADMIN_CLAIM_PASSWORD_HASH`)으로 되돌린다 — 절차는
> [09-operations-runbook.md](09-operations-runbook.md) §4-2.
>
> ⚠ **역할도 함께 되돌아간다.** 복원 시점 이후에 부여·변경한 역할은 사라지므로, 복원 직후
> `klid_at.ls_user_role` 의 관리자 수를 확인한다. 0 이면 자가부여 창구가 다시 열리므로
> [04-configuration.md](04-configuration.md) 의 「G. 최초 관리자(ADMIN) 만들기」로 회복한다.

### 3-2. 설정·시크릿 복원

```bash
sudo tar -C /etc -xzpf /backup/klid/<날짜>/etc-klid.tgz   # /etc/klid/ 복원(권한 640 유지)
```

> ⚠ **WAR 형상에서 백엔드가 실제로 읽는 것은 `/etc/klid/application.properties` 다.** 이 파일이
> 복원되지 않으면 WAS 는 뜨는데 애플리케이션이 DB 접속·시크릿 없이 기동에 실패한다.
> `backend.env` 는 베어메탈 토글 형상용이라 WAS 가 읽지 않는다(→ [04-configuration.md](04-configuration.md)).
>
> ⚠ **`/etc/klid/was.env` 는 백업 대상이되 복원 대상은 아닐 수 있다** — WAS 유닛명·경로는 **새 서버의
> 실제 값**이라야 한다. 복원 후 새 장비의 값으로 다시 확인해 고친다.

## 4. 재해복구(DR) — 전체 서버 손실

전체 서버가 소실된 경우 아래 순서로 복원한다.

1. **OS 준비**: 레드햇 엔터프라이즈 리눅스 8.9 재설치(설치 요구사항 → [01-prerequisites.md](01-prerequisites.md)).
2. **패키지 설치**: 보관 중인 온프렘 설치 패키지로 런타임·앱 설치(→ [03-install.md](03-install.md)). 외부 DB 사용 시 DB 는 인프라 주체가 제공·복구하며, 번들 PG 단독 구성 시에만 PG16 을 함께 설치한다.
3. **설정 복원**: 3-2 로 `/etc/klid/` 복원(백업이 없으면 [04-configuration.md](04-configuration.md) 로 재작성).
   **WAR 형상은 `application.properties` 가 정본**이다.
4. **DB 복원**: **외부 DB 는 DB 운영 주체가 복구**하며 저작도구는 접속 정보만 설정한다. (번들 PG 단독 구성이면 3-1 로 `klid_system`·`portal` 복원, 필요 시 `globals.sql` 선복원.)
5. **저장소 복원**: `/nas-storage` 를 백업/스냅샷에서 복원(스토리지 운영 주체 정책, 마운트가 살아 있으면 재마운트만).
6. **★ WAS 재구성**: 새 장비의 WAS 에 `api.war` 를 배포하고 기동 옵션
   (`-Dspring.config.additional-location=file:/etc/klid/` · `-Dspring.profiles.active=prd` · **`JAVA_OPTS`**)과
   **WAS 설정 이관**([10-was-settings.md](10-was-settings.md))을 다시 수행한다. 예시 파일은
   `config/was/` 에 있다. **이 단계는 설치 스크립트가 대신하지 못한다** — 빠뜨리면 기동은 되는데
   대용량 업로드만 조용히 깨진다. 확정한 현장값은 `/etc/klid/was.env` 에 다시 적는다.
7. **기동·검증**: 의존 순서(PostgreSQL→ai-server→backend(WAS)→httpd)로 기동 후 스모크(→ [05-run-verify.md](05-run-verify.md)).

### 목표 지표(RPO/RTO — 운영 정책으로 확정)

| 지표 | 의미 | 결정 기준 |
|---|---|---|
| **RPO**(복구 시점 목표) | 최대 허용 데이터 손실 | 백업 주기에 종속 — 일 1회 백업 시 최대 24h. 더 짧게 필요하면 DB WAL 아카이빙(PITR) 도입 검토 |
| **RTO**(복구 시간 목표) | 최대 허용 복구 소요 | 서버 재설치+DB 복원+저장소 복원 시간의 합. 저장소 대용량이 지배적 → 스냅샷/증분으로 단축 |

> 더 낮은 RPO 가 필요하면 `postgresql.conf` 의 WAL 아카이빙 + `pg_basebackup` 기반 **PITR(Point-In-Time Recovery)**
> 를 별도 도입한다(현행 패키지는 논리 덤프 기준). 도입 시 이 문서에 절차를 추가한다.

## 5. 백업 검증(리허설)

백업은 **복원해봐야 유효하다.** 주기적으로 스크래치 환경에 복원 리허설을 수행한다.

```bash
# 스크래치 DB 로 복원 확인(운영 DB 건드리지 않음)
sudo -u postgres createdb -O klid_user -E UTF8 klid_system_verify
sudo -u postgres pg_restore -d klid_system_verify --no-owner /backup/klid/<날짜>/klid_system.dump
sudo -u postgres psql -d klid_system_verify -c "select count(*) from klid_at.ls_data_raw;"
sudo -u postgres dropdb klid_system_verify
```

| 주기 | 검증 항목 |
|---|---|
| 주 | 최신 DB 덤프 `pg_restore -l` 목록 확인(손상 여부) |
| 월 | 스크래치 DB 복원 리허설 + 핵심 테이블 건수 확인 |
| 분기 | DR 전체 흐름(§4) 리허설(별도 서버·VM) |
