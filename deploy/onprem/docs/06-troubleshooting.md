# 06. 트러블슈팅 (오프라인 설치)

## 누락 wheel — `pip ... No matching distribution`

증상: `13-install-ai-server.sh` 에서 `--no-index` 설치가 특정 패키지를 못 찾음.

원인/해결:
- 빌드머신 python 과 대상 python 의 **마이너/ABI 불일치**(cp310 vs cp311). 빌드머신을 `python3.11` 로
  맞춰 `PYTHON_BIN=python3.11 ./scripts/package.sh` 재수집.
- 빌드머신 **OS/glibc 불일치**(manylinux 태그). 대상과 동일 Linux x86_64 빌드머신에서 재수집.
- 일부 패키지의 sdist 만 받힌 경우 → 대상에 빌드도구가 없어 실패. 빌드머신에서 동일 환경으로 wheel 을
  강제 수집하거나 누락분만 `pip download --only-binary=:all:` 로 보강.

## torch CPU — 버전/인덱스 문제

> **현재 상태(2026-06-24 확인)**: lock 의 `torch==2.12.0` / `torchvision==0.27.0` 의 cp311 x86_64
> CPU wheel 이 PyTorch CPU 인덱스에 **존재**한다
> (`torch-2.12.0+cpu-cp311-cp311-manylinux_2_28_x86_64.whl`,
> `torchvision-0.27.0+cpu-cp311-cp311-manylinux_2_28_x86_64.whl`).
> 따라서 기본 수집 경로로 그대로 받힌다. 아래는 **lock 버전이 향후 CPU 인덱스에서 사라졌을 때**의 절차다.

증상: `30-collect-ai-server.sh` 의 torch CPU 다운로드 실패(예: `torch==X.Y.Z` 가 CPU 인덱스에 없음).

원인: requirements lock 의 torch/torchvision 버전이 PyTorch CPU 인덱스(`download.pytorch.org/whl/cpu`)에
존재하지 않을 수 있다(lock 은 CUDA/기본 인덱스 기준 핀일 수 있음).

해결(앱 requirements.txt 는 건드리지 않고 수집/문서 레벨로 해결):

1. CPU 인덱스에서 가용한 인접 버전 확인:
   ```bash
   # 인덱스 HTML 에서 cp311 x86_64 CPU wheel 목록 확인
   curl -fsSL https://download.pytorch.org/whl/cpu/torch/ \
     | grep -oiE 'torch-2\.[0-9]+\.[0-9]+%2Bcpu-cp311-cp311-manylinux[^"]*x86_64\.whl' | sort -uV
   curl -fsSL https://download.pytorch.org/whl/cpu/torchvision/ \
     | grep -oiE 'torchvision-0\.[0-9]+\.[0-9]+%2Bcpu-cp311-cp311-manylinux[^"]*x86_64\.whl' | sort -uV
   ```
   (sam2 가 요구하는 torch 하한 `torch>=2.5.1` 을 충족하는 버전을 고른다.)

2. `scripts/lib/versions.sh` 의 권장 핀을 가용 버전으로 갱신:
   ```bash
   # 예: 인접 버전이 2.11.0 / 0.26.0 이라면
   TORCH_CPU_PIN="torch==2.11.0"
   TORCHVISION_CPU_PIN="torchvision==0.26.0"
   ```

3. **torch/torchvision 만** 그 핀으로 받고 **나머지 lock 은 유지**한다. 일반 wheel 은 이미 받힌 상태에서
   torch 계열만 별도로 보강(requirements.txt 미변경):
   ```bash
   cd deploy/onprem
   # versions.sh 의 핀을 사용해 torch 계열만 CPU 인덱스에서 추가 수집
   . scripts/lib/versions.sh
   python3.11 -m pip download --dest vendor/wheels \
     --index-url https://download.pytorch.org/whl/cpu \
     "${TORCH_CPU_PIN}" "${TORCHVISION_CPU_PIN}"
   # 체크섬 재생성(전송 무결성)
   ( cd vendor/wheels && find . -type f ! -name SHA256SUMS -print0 \
       | sort -z | xargs -0 sha256sum > SHA256SUMS )
   ```
   대상 설치(`13-install-ai-server.sh`)는 `--no-index --find-links vendor/wheels` 로 설치하므로
   requirements 의 핀과 다른 torch 버전이 wheels 에 있으면 의존성 충돌이 날 수 있다. 그때는
   대상에서 `pip install --no-index --find-links vendor/wheels` 시
   `torch torchvision` 만 명시 핀으로 먼저 깔거나, 빌드머신에서 requirements 핀과 동일 버전을
   확보하는 것이 안전하다(앱 lock 변경은 별도 협의 대상).

## sam2 설치 실패

증상: `pip install vendor/sam2/sam2-src` 실패.

해결:
- SAM2 분할/Track 을 쓰지 않으면 무시 가능(yolox 탐지/오토라벨링은 동작).
- 쓰려면: sam2 빌드 의존(torch 등)이 wheels 에 있어야 한다. torch CPU 수집이 선행됐는지 확인.
- 재현성: `versions.sh` 의 `SAM2_GIT_REF` 가 main HEAD 커밋(`2b90b9f5...`, 2024-12-16)으로 고정돼 있다.
  `30-collect-ai-server.sh` 는 이 SHA 로 clone 후 `git checkout` 한다(브랜치/태그가 아니므로 `--branch` 불가).
  다른 커밋으로 바꾸려면 이 값만 교체 후 재수집.

## ffmpeg / ffprobe 없음 (Rocky 9: 정적 바이너리)

증상: backend 프레임 추출/duration 추출 실패(`ffmpeg`/`ffprobe` not found 또는 Permission denied).

해결(Rocky 9 는 정적 바이너리 번들이 정석 — base/AppStream 에 ffmpeg RPM 이 없음):
- 번들은 **LGPL 빌드**(BtbN `linux64-lgpl`)다 — 지방정부 납품 GPL 회피용. native H.264/HEVC 디코더를
  포함하므로 프레임 추출(디코드)·duration 추출에 충분하다(인코딩 미사용 → GPL 코덱 불필요).
- 설치 확인: `ls -l /opt/klid/runtime/ffmpeg/bin/` → `ffmpeg`/`ffprobe` 가 있고 `chmod +x`(0755) 인지.
  없으면 `syspkgs/ffmpeg/*.tar.xz` 가 번들됐는지 확인 후 `11-install-runtimes.sh` 재실행.
- 권한 오류면: `sudo chmod +x /opt/klid/runtime/ffmpeg/bin/ffmpeg /opt/klid/runtime/ffmpeg/bin/ffprobe`.
- 경로 확인: `backend.env` 의 `FFMPEG_BIN=/opt/klid/runtime/ffmpeg/bin/ffmpeg`(절대경로) 인지.
  systemd 유닛 PATH 에도 `…/ffmpeg/bin` 이 추가돼 있어 bare 이름으로도 잡힌다.
- 직접 보강: 정적 바이너리를 `/opt/klid/runtime/ffmpeg/bin/` 에 두고 `chmod +x` 후 재기동.

## libGL / opencv (`ImportError: libGL.so.1`)

증상: ai-server 기동 시 opencv import 실패(`libGL.so.1: cannot open shared object file`).

해결(Rocky 9 AppStream RPM):
- 번들 RPM 설치 확인: `rpm -q mesa-libGL libglvnd-glx glib2`.
- 누락 시 오프라인 설치: `sudo dnf install -y --disablerepo='*' --setopt=gpgcheck=0 syspkgs/rpm/*.rpm`
  (폴백 `sudo rpm -Uvh --replacepkgs syspkgs/rpm/*.rpm`).
- **`GPG check FAILED` / `public key is not installed` 로 dnf install 실패 시**: 최소 Rocky 9 이미지에
  GPG 키가 없을 때 발생한다. 무결성은 번들 `SHA256SUMS` 로 이미 검증되므로
  `--setopt=gpgcheck=0` 을 붙여 설치한다(`11-install-runtimes.sh` 가 이미 이 옵션으로 설치).
- **`rpm -Uvh` 폴백은 의존성 자동해소를 못 한다** — 정상 경로는 dnf(로컬 의존 해소)다. 폴백이
  `Failed dependencies` 로 실패하면 번들 RPM 세트가 불완전한 것이니, 빌드머신에서
  `50-collect-syspkgs.sh` 로 전이 의존성까지 포함해 재수집한 RPM 세트를 점검하라.
- 사내 미러가 있으면: `sudo dnf install -y mesa-libGL libglvnd-glx glib2`.
- 번들이 비었으면 rockylinux:9 컨테이너에서 `dnf download --resolve mesa-libGL libglvnd-glx glib2` 로
  받아 `syspkgs/rpm/` 에 채워 재설치(02-build-package.md 참고).

## glibc 불일치 (`version 'GLIBC_2.xx' not found`)

원인: 빌드머신 glibc > 대상 서버 glibc.
해결: **대상 서버와 동일(또는 더 낮은) glibc 빌드머신**에서 재수집. 런타임 바이너리/whl 모두 영향.

## HF 모델 못 찾음 (SAM2, 오프라인)

증상: `OSError: ... not found ... offline mode`.
해결:
- 탐지(YOLOX)는 동봉 ONNX 라 HF 불필요 — SAM2 만 HF 캐시가 필요하다.
- SAM2 필요 시 빌드머신에서 `PREFETCH_HF=1 ./scripts/package.sh` 로 `models/hf-cache` 채워 재설치,
  ai-server.env `HF_HOME` 가 캐시 경로를 가리키는지 확인.

## 포트 충돌

증상: `Address already in use` (80/8080/9300).
해결: `sudo ss -ltnp | grep -E ':(80|8080|9300)'` 로 점유 프로세스 확인 후 정리, 또는 유닛/설정에서 포트 변경.
80 은 Caddy(비루트)라 `setcap`/`AmbientCapabilities` 가 필요 — 14 스크립트가 처리하나 실패 시 경고 참고.

## 권한 오류

증상: 저장소/캐시 쓰기 권한 거부.
해결: 로컬 데이터·캐시(`/var/lib/klid`, `/opt/klid/ai/.hf-cache`)는 `klid:klid` 소유인지 확인
(`sudo chown -R klid:klid /var/lib/klid /opt/klid`).
영상 저장소는 **NAS 마운트**(`STORAGE_RAW_PATH`, 기본 `/nas-storage`)다 — ① 마운트 확인
(`mountpoint /nas-storage`) ② `klid` 쓰기 가능 확인(`sudo runuser -u klid -- test -w /nas-storage`).
**NAS 전체에 `chown -R` 금지**(기존 v1 대용량 파일 소유권 훼손) — v2 가 쓰는 하위 디렉터리만 권한 부여.

## backend 부팅 실패 — webhook HMAC

증상: prd 부팅 시 `webhook.hmac.secret.augment 이(가) 설정되지 않았습니다`.
해결: `backend.env` 의 `WEBHOOK_HMAC_SECRET_AUGMENT` 를 강한 값으로 채움(`openssl rand -hex 32`).
(VLM 콜백은 벤더 무서명 규격이라 `WEBHOOK_HMAC_SECRET_VLM` 은 소비처가 없다 — 채워도 무시된다.)

증상: prd 부팅 시 `webhook.trusted-proxy-cidrs ... 에 잘못된 값이 있습니다`.
해결: CIDR 오타(`203.0.113.0/33`)·구분자 오타(`;`)·호스트명은 부팅 차단된다(DEV_FIX N-4).
IP/CIDR 리터럴만 쉼표로 나열하고, 적용하지 않겠다면 `none` 을 명시.

## PostgreSQL 번들 설치 (오프라인)

번들 PG16(`USE_BUNDLED_POSTGRES=1`, 기본)을 `10-install-postgresql.sh` 가 설치한다. 흔한 실패:

- **RPM 누락 / 설치 스킵**: `syspkgs/postgresql/*.rpm` 이 비어 있으면 설치를 건너뛴다(안내 출력).
  타깃에 이미 PG 가 있으면 `sudo USE_BUNDLED_POSTGRES=0 ./scripts/install.sh`. 번들이 필요하면
  rockylinux:9 컨테이너에서 PG16 RPM 을 받아 `syspkgs/postgresql/` 에 채운다(55-collect-postgresql.sh).
- **`GPG check FAILED` / `public key is not installed`**: 폐쇄망 Rocky 9 에 PGDG GPG 키가 없을 때.
  무결성은 번들 `SHA256SUMS` 로 이미 검증되므로 `--setopt=gpgcheck=0` 으로 설치한다
  (`10-install-postgresql.sh` 가 이미 이 옵션 사용). 수동 폴백: `sudo rpm -Uvh --replacepkgs syspkgs/postgresql/*.rpm`.
- **initdb 위치/실패**: PGDG PG16 의 데이터 디렉토리는 `/var/lib/pgsql/16/data`,
  초기화는 `/usr/pgsql-16/bin/postgresql-16-setup initdb` 다(base RHEL `postgresql-setup` 과 경로가 다름).
  이미 초기화돼 있으면(`/var/lib/pgsql/16/data/PG_VERSION` 존재) 스크립트가 건너뛴다. 실패 시 데이터
  디렉토리 권한(`postgres:postgres`)·디스크 공간을 확인한다.
- **접속 거부(`no pg_hba.conf entry` / `password authentication failed`)**: `10` 스크립트는
  `pg_hba.conf` 에 `127.0.0.1/32`·`::1/128` 을 `scram-sha-256` 으로 허용한다. backend 가 다른 대역에서
  접속하면 `PG_HBA_EXTRA_CIDR=<대역>`·`PG_LISTEN_ADDRESSES='*'` 로 재설치하거나 두 conf 를 직접 수정 후
  `sudo systemctl reload postgresql-16`. 비밀번호 오류면 `15-init-db.sh` 로 만든 앱 유저 비밀번호와
  `backend.env` 의 `*_DB_PASSWORD` 일치를 확인한다.
- **서비스 미기동**: `systemctl status postgresql-16` / `journalctl -u postgresql-16`. 기동 후
  `sudo systemctl enable --now postgresql-16`.

## backend 부팅 실패 — DB validate

증상: Hibernate `ddl-auto=validate` 가 `MNG_*`/`QRTZ_*`(또는 LS_*) 테이블/컬럼 부재로 실패.

원인/해결:
- **정상 흐름에선 거의 발생하지 않는다.** backend 는 기동 시 Flyway(`spring.flyway.enabled=true`,
  prd 포함)로 V2 마이그레이션을 먼저 적용해 LS_*·MNG_*·QRTZ_* 를 `CREATE TABLE IF NOT EXISTS` 로 만든 뒤
  validate 한다. 즉 **빈 DB 면 저작도구가 전 스키마를 자동 부트스트랩**하므로 관제 스키마를 사전
  적재할 필요가 없다(04-configuration.md D 절).
- 그래도 validate 가 실패하면 Flyway 가 **꺼졌거나 마이그레이션이 적용되지 않은** 경우다:
  - `journalctl -u klid-backend` 에서 Flyway 로그(`Migrating schema ... to version 2`)가 보이는지 확인.
  - 안 보이면 앱 유저에게 **DDL 권한**(해당 DB OWNER)이 있는지 확인 —
    `15-init-db.sh` 는 `CREATE DATABASE ... OWNER <앱유저>` 로 만들어 OWNER 권한을 준다.
  - 관제가 이미 채운 공유 테이블과 **컬럼 스키마가 다르면** validate 가 불일치로 실패할 수 있다.
    이 경우 관제 인프라/DBA 와 스키마 정합을 협의한다(이는 "사전 적재 필요"가 아니라 "정합 충돌").

## 비식별 설정오류 / KPST 연동

비식별 정책은 **KPST 동거 연동으로 확정**(04-configuration.md B 절). 끄거나 mock 우회는 prd 미지원.

- **부팅 실패 — 비식별 설정오류**: `DeidentifyStep` 설정오류 거부(`KPST_DEID_ENABLED=false` + mock 아님).
  → `KPST_DEID_ENABLED=true` 로 두고 동거 KPST 주소/CA 를 설정한다.
- **부팅 실패 — KPST SSL(fail-closed)**: `KPST_DEID_BASE_URL` 이 `https://` 인데
  `KPST_DEID_CA_CERT_PATH` 미설정·읽기실패(CWE-295). → 사설 CA(ca.crt) 경로를 채우거나, 격리망이면
  `http://IP:PORT` 평문 + CA 비움.
- **영상이 비식별 'F' 로 남음 / 마킹·프레임추출 차단**: KPST 도달 불가 또는 주소/포트 오설정.
  → 05-run-verify.md "비식별(KPST) 연동 스모크"로 KPST 도달을 확인하고 `KPST_DEID_BASE_URL` 점검.
