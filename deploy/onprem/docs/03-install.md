# 03. [대상 서버] 오프라인 설치

폐쇄망 **Rocky Linux 9** 대상 서버에서 root 로 실행한다. **외부 네트워크 호출이 전혀 없다**(모든 의존성은 번들).

## 실행

```bash
cd deploy/onprem
sudo ./scripts/install.sh
```

옵션:

```bash
# 외부(기존) PostgreSQL 사용 — 번들 PG16 설치 생략
sudo USE_BUNDLED_POSTGRES=0 ./scripts/install.sh

# DB 자동 생성 단계 생략(DBA 가 이미 준비한 경우)
sudo SKIP_DB_INIT=1 ./scripts/install.sh

# 기존 nginx 사용(Caddy 대신 nginx.conf 템플릿만 배치)
sudo USE_NGINX=1 ./scripts/install.sh

# 설치 경로/사용자 변경(기본 /opt/klid, klid)
sudo KLID_PREFIX=/opt/klid KLID_USER=klid ./scripts/install.sh
```

## 단계별 동작

| 단계 | 스크립트 | 동작 |
|------|----------|------|
| 0 | `install.sh` | `klid` 사용자/그룹 + 디렉토리 생성, SHA256 무결성 검증 |
| 1 | `install/10-install-postgresql.sh` | **(옵션·기본 ON)** 번들 PG16 RPM 오프라인 설치 + initdb + `postgresql.conf`/`pg_hba.conf` + `postgresql-16` 기동. `USE_BUNDLED_POSTGRES=0` 이면 전체 스킵(외부 PG) |
| 2 | `install/11-install-runtimes.sh` | JRE/Python/Caddy + **ffmpeg 정적** 설치 + **RPM**(mesa-libGL/glib2) 오프라인 설치 |
| 3 | `install/12-install-backend.sh` | jar 배치 + `backend.env` + systemd 유닛 |
| 4 | `install/13-install-ai-server.sh` | venv + `pip --no-index` 설치 + 모델 배치 + 유닛 |
| 5 | `install/14-install-frontend.sh` | dist 배치 + Caddyfile + 유닛 (+ 80포트 setcap) |
| 6 | `install/15-init-db.sh` | (옵션) control/portal **DB·유저** 생성 안내 또는 수행 (테이블 생성 아님) |

> **PG vs DB/유저 vs 테이블 — 역할 분담**: 단계 1(`10`)은 **PG 엔진 설치+기동**, 단계 6(`15`)은
> **control/portal DB·앱 유저 생성**, **테이블/스키마는 backend 가 기동 시 Flyway 로 자동 생성**한다
> (LS_*·MNG_*·QRTZ_* `CREATE TABLE IF NOT EXISTS`). 셋은 중복 없이 연계된다.
>
> **Flyway 를 끄는 구성(`SPRING_FLYWAY_ENABLED=false`, 2노드 이중화 권장)** 이면 테이블을 앱이 만들지
> 않으므로 `16-load-schema.sh`(control) · `17-load-portal-schema.sh`(portal)가 사전 로드를 담당한다.

> **★ 스키마**: 저작도구 객체는 **`klid_at`**(`DB_SCHEMA`, 기본값)에 만들어진다. portal DB 는 대상이
> 아니며 `public` 을 그대로 쓴다(별개 물리 DB) — **두 DB 가 다른 것이 정상**이다.
> **저작도구 객체가 `public` 에 있는 기존 DB 는 설치 전에 이관**해야 한다
> (`09-operations-runbook.md` §2-5-1). 이관 없이 로드하면 빈 `klid_at` 이 생기고 데이터는 `public` 에
> 남아 앱이 조용히 빈 스키마를 보므로, `16-load-schema.sh` 는 그 상태를 감지하면 **로드를 거부**한다.

설치는 **멱등**하다(재실행 안전). 이미 존재하는 `*.env` 는 덮어쓰지 않아 사용자 편집을 보존한다.
번들 PG 단계도 멱등하다(이미 설치/initdb 된 경우 해당 작업을 건너뛴다).

## 오프라인 설치 보장

- ai-server: `pip install --no-index --find-links vendor/wheels` + sam2 로컬 소스. PyPI/인터넷 접근 0.
- 런타임: 번들 tar.gz 압축 해제만.
- ffmpeg: 번들 정적 tarball 을 `/opt/klid/runtime/ffmpeg/bin/` 로 풀어 배치(인터넷 미접근).
- RPM: `dnf install -y --disablerepo='*' syspkgs/rpm/*.rpm`(폴백 `rpm -Uvh --replacepkgs`)로 로컬 설치.
- **PostgreSQL 16**: `dnf install -y --disablerepo='*' --setopt=gpgcheck=0 syspkgs/postgresql/*.rpm`
  (폴백 `rpm -Uvh`)로 로컬 설치. PGDG 미러/인터넷 미접근. 전이 의존성은 번들 RPM 으로 해소.

## 설치 후 즉시 할 일

1. `/etc/klid/backend.env` 편집 — DB 비밀번호, JWT_SECRET, STREAM_SIGN_SECRET, webhook HMAC 등 (04 참고).
2. `/etc/klid/ai-server.env` 편집 — 보통 기본값으로 충분(yolox CPU).
3. DB 준비 확인 — `15-init-db.sh` 출력의 DDL 또는 DBA 준비 결과.
4. 05-run-verify.md 로 기동·검증.

## 시스템 의존성이 번들에 없을 때 (수동 보강)

정상 패키지라면 ffmpeg(정적)·RPM 이 번들되어 있어 자동 설치된다. 번들이 비어 install 이 경고만 내면:

- **ffmpeg 누락**: 빌드머신에서 `50-collect-syspkgs.sh` 를 다시 실행해 `syspkgs/ffmpeg/*.tar.xz` 를 채운다.
  급하면 대상에 정적 바이너리를 `/opt/klid/runtime/ffmpeg/bin/{ffmpeg,ffprobe}`(chmod +x)로 직접 배치하거나,
  `backend.env` 의 `FFMPEG_BIN`/`FFPROBE_BIN` 에 절대경로를 지정한다.
- **RPM(libGL/glib2) 누락**: 사내 미러가 있으면 `sudo dnf install -y mesa-libGL libglvnd-glx glib2`.
  폐쇄망이면 rockylinux:9 컨테이너에서 `dnf download --resolve` 로 받아 `syspkgs/rpm/` 에 채워 재설치.
- **PG16 RPM 누락(`syspkgs/postgresql/` 비어 있음)**: 타깃에 이미 PG 가 있으면
  `sudo USE_BUNDLED_POSTGRES=0 ./scripts/install.sh` 로 외부 PG 를 쓴다. 번들이 필요하면
  rockylinux:9 컨테이너에서 PGDG repo 추가 후 `dnf download --resolve --alldeps` 로 받아
  `syspkgs/postgresql/` 에 채워 재실행한다(02-build-package.md / 55-collect-postgresql.sh 참고).

ffmpeg/ffprobe 가 없으면 backend FFmpegStep(프레임추출·duration)이, libGL.so.1 이 없으면 ai-server opencv 가 실패한다.
