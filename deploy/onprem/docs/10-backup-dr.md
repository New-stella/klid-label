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
| **T1 (저작도구 소관·필수)** | 환경설정·시크릿 | `/etc/klid/*.env`(+ `kpst-ca.crt` 등) | **저작도구 운영자** | 파일 복사(권한 640 유지) | 변경 시 + 주 1회 |
| **T2 (스토리지 운영 정책)** | 저장소: 비식별 영상·프레임 | `STORAGE_RAW_PATH`·`STORAGE_DEIDENTIFIED_PATH`(기본 `/nas-storage`) | 스토리지(NAS) 운영 주체 | `rsync` 증분 또는 스토리지 스냅샷 | **일 1회(증분)** |
| **외부 책임** | DB: `klid_system`(관제/저작도구)·`portal` | 외부 DB 인프라 | **DB 운영(인프라) 주체** | DB 운영 주체 정책(참고 §1) | DB 운영 주체 정책 |
| **재설치 가능** | 앱·런타임 | `/opt/klid`, `/var/lib/klid` | 설치 패키지로 재설치 | 백업 불필요(패키지 보관으로 갈음) | — |

> **DB 스키마는 덤프에 포함**된다 — `pg_dump` 는 `flyway_schema_history` 를 함께 담으므로 복원 후 Flyway 가
> 재마이그레이션하지 않는다(마이그레이션 이력 그대로 복원). 온프렘 번들 단독 구성에서는 `klid_system` 에
> **LS_*·MNG_*·QRTZ_*** 가 모두 부트스트랩되어 있어 `klid_system` 덤프 하나로 저작도구 전체 스키마가 보존된다.
> (외부 DB 사용 시 스키마·데이터 백업은 DB 운영 주체 소관.)
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
- 경로는 `backend.env` 의 `STORAGE_RAW_PATH`·`STORAGE_DEIDENTIFIED_PATH` 실제 설정값을 사용한다.

## 3. 복원(Restore)

### 3-1. DB 복원(부분 — 특정 DB만)

```bash
# 서비스 정지(쓰기 차단) 후 복원 권장
sudo systemctl stop klid-backend

# 대상 DB 재생성(기존 손상분 폐기 시) — 주의: 데이터 삭제
sudo -u postgres dropdb --if-exists klid_system
sudo -u postgres createdb -O klid_user -E UTF8 klid_system
sudo -u postgres pg_restore -d klid_system --no-owner /backup/klid/<날짜>/klid_system.dump

# portal 동일
sudo -u postgres dropdb --if-exists portal
sudo -u postgres createdb -O klid_user -E UTF8 portal
sudo -u postgres pg_restore -d portal --no-owner /backup/klid/<날짜>/portal.dump

sudo systemctl start klid-backend
# 검증: Flyway 이력·핵심 테이블 확인
sudo -u postgres psql -d klid_system \
  -c "select count(*) from flyway_schema_history; select count(*) from ls_data_raw;"
```

> 롤이 없는 새 인스턴스라면 DB 복원 전 `sudo -u postgres psql -f /backup/klid/<날짜>/globals.sql` 로
> 롤/권한을 먼저 복원한다.

### 3-2. 설정·시크릿 복원

```bash
sudo tar -C /etc -xzpf /backup/klid/<날짜>/etc-klid.tgz   # /etc/klid/*.env 복원(권한 640 유지)
```

## 4. 재해복구(DR) — 전체 서버 손실

전체 서버가 소실된 경우 아래 순서로 복원한다.

1. **OS 준비**: Rocky Linux 9 재설치(설치 요구사항 → [01-prerequisites.md](01-prerequisites.md)).
2. **패키지 설치**: 보관 중인 온프렘 설치 패키지로 런타임·앱 설치(→ [03-install.md](03-install.md)). 외부 DB 사용 시 DB 는 인프라 주체가 제공·복구하며, 번들 PG 단독 구성 시에만 PG16 을 함께 설치한다.
3. **설정 복원**: 3-2 로 `/etc/klid/*.env` 복원(백업이 없으면 [04-configuration.md](04-configuration.md) 로 재작성).
4. **DB 복원**: **외부 DB 는 DB 운영 주체가 복구**하며 저작도구는 접속 정보만 설정한다. (번들 PG 단독 구성이면 3-1 로 `klid_system`·`portal` 복원, 필요 시 `globals.sql` 선복원.)
5. **저장소 복원**: `/nas-storage` 를 백업/스냅샷에서 복원(스토리지 운영 주체 정책, 마운트가 살아 있으면 재마운트만).
6. **기동·검증**: 의존 순서(PostgreSQL→ai-server→backend→frontend)로 기동 후 스모크(→ [05-run-verify.md](05-run-verify.md)).

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
sudo -u postgres psql -d klid_system_verify -c "select count(*) from ls_data_raw;"
sudo -u postgres dropdb klid_system_verify
```

| 주기 | 검증 항목 |
|---|---|
| 주 | 최신 DB 덤프 `pg_restore -l` 목록 확인(손상 여부) |
| 월 | 스크래치 DB 복원 리허설 + 핵심 테이블 건수 확인 |
| 분기 | DR 전체 흐름(§4) 리허설(별도 서버·VM) |
