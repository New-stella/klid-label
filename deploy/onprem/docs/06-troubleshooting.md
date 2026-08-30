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

## ffmpeg / ffprobe 없음 (RHEL 8.9: 시스템 RPM)

증상: backend 프레임 추출/duration 추출 실패(`ffmpeg`/`ffprobe` not found 또는 Permission denied).

> ⚠ 구 절 폐기(2026-08-28) — "Rocky 9 는 **정적 바이너리 번들**이 정석 / 번들은 **LGPL 빌드**(BtbN
> `linux64-lgpl`)다 — 지방정부 납품 GPL 회피용 / `/opt/klid/runtime/ffmpeg/bin/` 에 배치·`chmod +x`".
> 타깃이 RHEL 8.9(glibc 2.28)로 확정되어 그 정적 빌드(glibc 2.31 기반)는 `GLIBC_2.31 not found` 로
> 죽는다. 관제지원시스템과 같은 형상(RPM Fusion RPM)으로 전환했고 라이선스는 **GPLv3+** 다
> (별도 프로세스 호출이라 코드 전염 우려 낮음).

> ★★ **`install.sh` 는 ffmpeg 을 설치하지 않는다** (2026-08-30 사용자 확정, 구속).
> ffmpeg 은 **우리 반입물이 아니라 대상 장비(서버 A)의 전제조건**이며, 이 장비는
> 관제지원시스템과 **공동 배치**라 우리가 자동으로 깔면 **관제가 쓰던 설치본을 덮어써
> 관제 기능이 깨질 수 있다.** 그래서 설치는 <사람이 명시적으로 부를 때만> 일어난다.
> 설치 스크립트 중 **번호 접두가 없는 것**(`install-ffmpeg.sh`)이 그 표식이다.
>
> - 설치(있으면 아무것도 안 함): `sudo ./scripts/install/install-ffmpeg.sh`
> - 번들 판으로 교체: `sudo ./scripts/install/install-ffmpeg.sh --force`
> - 무엇을 할지만 확인: `sudo ./scripts/install/install-ffmpeg.sh --dry-run`
>
> 검증 단계 `19-verify-ffmpeg.sh` 는 **없으면 warn 이 아니라 die** 한다. ffmpeg 이 없어도
> backend 는 정상 기동하고 헬스체크도 통과하며 **배치를 실제로 돌려야 드러나기** 때문이다.
> 이 단계는 서버 A(`--role=app`) 전용이다 — ai 서버에서 돌리면 정상 설치를 실패시킨다
> (ai-server 소스에 ffmpeg/ffprobe 호출 0건).

해결(el8 base/AppStream 에 ffmpeg RPM 이 없어 EPEL + RPM Fusion + PowerTools 에서 번들한다):
- **먼저 위 `install-ffmpeg.sh` 를 쓴다.** 아래 수동 절차는 그 스크립트가 못 도는 상황의 폴백이다.
- 설치 확인: `rpm -q ffmpeg` → `ffmpeg-4.4.8-1.el8` 류. `command -v ffmpeg ffprobe` → `/usr/bin/…`.
- 없으면 번들 확인: `ls syspkgs/ffmpeg/*.rpm` 과 `ls syspkgs/ffmpeg/repodata/repomd.xml`.
  **`repodata/` 가 없으면 설치가 실패한다** — 빌드머신에서 `50-collect-syspkgs.sh` 를 재실행하라.
- 수동 설치(로컬 저장소 방식 — 폴백):
  ```bash
  sudo rpm --import syspkgs/gpg/RPM-GPG-KEY-*
  sudo tee /etc/yum.repos.d/klid-ffmpeg.repo >/dev/null <<'EOF'
  [klid-ffmpeg]
  name=KLID offline bundle (ffmpeg)
  baseurl=file:///<절대경로>/deploy/onprem/syspkgs/ffmpeg
  enabled=1
  gpgcheck=1
  EOF
  sudo dnf install -y --disablerepo='*' --enablerepo=klid-ffmpeg ffmpeg
  ```
  ⚠ `dnf install syspkgs/ffmpeg/*.rpm` 처럼 **파일을 직접 넘기지 말 것** — 번들에는 전이 의존성이
  전량(261개) 들어 있어 이미 설치된 glibc·coreutils 와 충돌하며 설치가 통째로 실패한다.
- 경로 확인: `backend.env` 의 `FFMPEG_BIN=/usr/bin/ffmpeg` 인지.
- 실동작 확인:
  `ffmpeg -f lavfi -i testsrc=duration=3:size=320x240:rate=10 -y /tmp/t.mp4 && ffprobe -v error -show_entries format=duration -of default=nw=1 /tmp/t.mp4`
  → `duration=3.000000`
- ⚠ **라이선스**: 번들 ffmpeg 은 RPM Fusion 의 **GPLv3+** 빌드다. 매체에 담아 반입한 시점에
  이미 재배포이므로 대응 소스(`syspkgs/ffmpeg-src/*.src.rpm`)가 함께 반입되어 있어야 한다.
  ⚠ 이것은 **ai-server 의 opencv wheel 안에 번들된 LGPL FFmpeg 과 다른 물건**이다
  (그쪽 대응 소스는 `licenses/copyleft-sources/`). 어느 한쪽을 중복으로 보고 지우지 말 것 —
  판도 라이선스도 다르다. 상세는 `licenses/manual/LGPL-SOURCE-OFFER.md`.

## libGL / opencv (`ImportError: libGL.so.1`)

증상: ai-server 기동 시 opencv import 실패(`libGL.so.1: cannot open shared object file`).

해결(el8 AppStream RPM):
- 번들 RPM 설치 확인: `rpm -q mesa-libGL libglvnd-glx glib2`.
- 누락 시 오프라인 설치(로컬 저장소 방식 — 위 ffmpeg 절의 `.repo` 예시에서 `baseurl` 만
  `syspkgs/rpm` 으로 바꿔 `dnf install -y --disablerepo='*' --enablerepo=klid-… mesa-libGL libglvnd-glx glib2`).
  ⚠ 구 명령 폐기(2026-08-30): `dnf install -y --disablerepo='*' --setopt=gpgcheck=0 syspkgs/rpm/*.rpm`
  (파일 직접 설치). 조달이 `--alldeps` 로 바뀌어 기반 패키지가 섞이면서 그 방식은 충돌로 실패한다.
- **`GPG check FAILED` / `public key is not installed` 로 dnf install 실패 시**: 폐쇄망 타깃에 EPEL/
  RPM Fusion 공개키가 없을 때 발생한다. 정석은 **번들 키를 등록**하는 것이다:
  `sudo rpm --import syspkgs/gpg/RPM-GPG-KEY-*` (`11-install-runtimes.sh` 가 이미 수행).
  키 자체가 반입되지 않았다면 `KLID_RPM_GPGCHECK=0` 으로 검증을 낮춰 설치할 수 있다(그 경우
  무결성은 번들 `SHA256SUMS` 에만 의존한다).
- **`No available modular metadata for modular package 'httpd-…module+el8…'`**: el8 의 httpd 는
  모듈러 패키지인데 번들 로컬 저장소에는 모듈러 메타데이터가 없어서 나는 오류다. 정석은 저장소
  정의에 `module_hotfixes=1` 을 넣는 것이다(`common.sh` 의 `klid_dnf_install_from_bundle` 가 이미
  포함). 수동으로 `.repo` 를 만들 때 이 줄을 빠뜨리면 **httpd·semanage 설치가 실패한다**.
- **`rpm -Uvh` 폴백은 의존성 자동해소를 못 한다** — 정상 경로는 dnf(로컬 의존 해소)다. 폴백이
  `Failed dependencies` 로 실패하면 번들 RPM 세트가 불완전한 것이니, 빌드머신에서
  `50-collect-syspkgs.sh` 로 전이 의존성까지 포함해 재수집한 RPM 세트를 점검하라.
- 사내 미러가 있으면: `sudo dnf install -y mesa-libGL libglvnd-glx glib2`.
- 번들이 비었으면 el8 컨테이너에서 `dnf download --resolve --alldeps --archlist=x86_64,noarch mesa-libGL libglvnd-glx glib2`
  로 받고 `createrepo_c` 로 `repodata/` 까지 만들어 `syspkgs/rpm/` 에 채워 재설치(02-build-package.md 참고).

## glibc 불일치 (`version 'GLIBC_2.xx' not found`)

원인: 빌드머신 glibc > 대상 서버 glibc. **대상은 RHEL 8.9 = glibc 2.28** 이다(RHEL 8 은 8.0~8.10 전
버전이 2.28 고정). 즉 빌드 glibc 는 반드시 **2.28 이하**여야 한다.

해결: **el8 빌드머신/컨테이너**(`rockylinux/rockylinux:8`, glibc 2.28)에서 재수집. 런타임 바이너리·wheel 모두 영향.

실제 사례(2026-08-28 확정): ffmpeg 정적 바이너리(BtbN `linux64-lgpl`)가 glibc **2.31** 기반이라
타깃에서 `GLIBC_2.31 not found` 로 죽었다 — 이 때문에 ffmpeg 조달을 **정적 번들 → el8 RPM** 으로
전환했다. 반면 torch wheel 은 `manylinux_2_28` 태그라 **정확히 2.28 기준선**이어서 영향이 없다.

## HF 모델 못 찾음 (SAM2, 오프라인)

증상: `OSError: ... not found ... offline mode`.
해결:
- 탐지(YOLOX)는 동봉 ONNX 라 HF 불필요 — SAM2 만 HF 캐시가 필요하다.
- SAM2 필요 시 빌드머신에서 `PREFETCH_HF=1 ./scripts/package.sh` 로 `models/hf-cache` 채워 재설치,
  ai-server.env `HF_HOME` 가 캐시 경로를 가리키는지 확인.

## 포트 충돌

증상: `Address already in use` (80/8080/9300).
해결: `sudo ss -ltnp | grep -E ':(80|8080|9300)'` 로 점유 프로세스 확인 후 정리, 또는 유닛/설정에서 포트 변경.
80 은 배포판 httpd 가 직접 연다(root 로 바인딩 후 권한 강등) — 별도 `setcap` 이 필요 없다. 대신 **SELinux** 를 본다: 문서 루트 문맥(`httpd_sys_content_t`)이 없으면 403, `httpd_can_network_connect` 가 꺼져 있으면 `/api` 프록시가 503 이다. 14 스크립트가 둘 다 처리하나 실패 시 경고를 참고한다.

## 권한 오류

증상: 저장소/캐시 쓰기 권한 거부.
해결: 로컬 데이터·캐시(`/var/lib/klid`, `/opt/klid/ai/.hf-cache`)는 `klid:klid` 소유인지 확인
(`sudo chown -R klid:klid /var/lib/klid /opt/klid`).
영상 저장소는 **NAS 마운트**(`STORAGE_RAW_PATH`, 기본 `/nas-storage`)다 — ① 마운트 확인
(`mountpoint /nas-storage`) ② `klid` 쓰기 가능 확인(`sudo runuser -u klid -- test -w /nas-storage`).
**NAS 전체에 `chown -R` 금지**(기존 v1 대용량 파일 소유권 훼손) — v2 가 쓰는 하위 디렉터리만 권한 부여.

## backend 가 안 보인다 — WAR 반입 형상 (2026-08-30 형상 확정)

배포 형상이 **외부 WAS 에 `api.war` 반입**이라(@design DEPLOY-001 · RUNBOOK-001), 베어메탈 시절의
`systemctl status klid-backend` 는 **유닛이 없어서** 실패한다. 그건 장애가 아니라 형상이다.

| 증상 | 원인 | 조치 |
|------|------|------|
| `Unit klid-backend.service could not be found` | WAR 형상에는 그 유닛이 없다(기동 주체는 WAS) | WAS 유닛 상태와 `curl /api/actuator/health/liveness` 로 확인 |
| 모든 요청이 **404**(WAR 는 배포됐는데 로그도 없음) | 컨텍스트 경로 불일치 — WAR 배포에서 `server.servlet.context-path` 는 **적용되지 않고** 컨텍스트는 **파일명**이 정한다 | 배포 파일명이 `api.war` 인지 확인(rename 금지). 이미 `webapps/xxx/` 로 풀린 이전 배포가 남았으면 함께 정리 |
| 기동 실패 — DB 접속/시크릿 없음 | WAS 가 설정 파일을 못 읽었다. `/etc/klid/backend.env` 는 **베어메탈 유닛의 `EnvironmentFile`** 이라 WAS 에 자동 전달되지 않는다 | WAS 기동 옵션에 `-Dspring.config.additional-location=file:/etc/klid/` (읽히는 파일은 `/etc/klid/application.properties`) + `-Dspring.profiles.active=prd` 를 넣는다. 환경변수로 주고 싶으면 WAS 유닛의 `EnvironmentFile=`/`setenv.sh` 로 배선 |
| `SPRING_*` 설정이 무시됨(예 Flyway 가 그대로 돌음) | 그 파일은 **환경변수가 아니라 스프링 설정 파일**이라 `SPRING_FLYWAY_ENABLED` 같은 이름 변환이 일어나지 않는다 | **점 표기**로 적는다 — `spring.flyway.enabled=false`. 실측으로 확인된 함정이다(DBA 선적용 스키마 위에서 마이그레이션이 또 돈다) |
| **대용량 업로드만** 실패(그 외 전부 정상) | `server.tomcat.*`(본문 한도·스레드·비동기 타임아웃)는 내장 서버 전용이라 WAR 배포에서 무시된다 | [10-was-settings.md](10-was-settings.md) 의 WAS 설정 이관을 수행. **기동 성공은 이 단계의 완료 근거가 아니다** |
| 앞단은 통과했는데 본문이 잘림 | 앞단 httpd 본문 한도와 WAS 커넥터 한도 중 **작은 쪽**이 실제 상한 | 두 값을 함께 본다(`httpd-klid.conf.template` + WAS 커넥터) |
| `java` 를 못 찾음(베어메탈로 되돌린 경우) | 패키지가 **JRE 를 반입하지 않는다**(2026-08-30) — `klid-backend.service` 의 `/opt/klid/runtime/jre` 는 없는 경로다 | 그 형상이 필요하면 `40-collect-runtimes.sh`/`11-install-runtimes.sh` 의 JRE 배선을 되살리거나 `ExecStart` 를 장비의 자바로 바꾼다 |

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
  el8 컨테이너에서 PG16 RPM(+`repodata/`)을 받아 `syspkgs/postgresql/` 에 채운다(55-collect-postgresql.sh).
- **`GPG check FAILED` / `public key is not installed`**: 폐쇄망 타깃에 PGDG GPG 키가 없을 때.
  정석은 번들 키 등록이다: `sudo rpm --import syspkgs/gpg/RPM-GPG-KEY-*`
  (`10-install-postgresql.sh` 가 이미 수행). 키가 반입되지 않았다면 `KLID_RPM_GPGCHECK=0` 으로 낮춘다.
  수동 폴백: `sudo rpm -Uvh --replacepkgs syspkgs/postgresql/*.rpm`.
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
  - **WAS 로그**(WAR 형상) 또는 `journalctl -u klid-backend`(베어메탈 형상)에서 Flyway 로그
    (`Migrating schema ... to version 2`)가 보이는지 확인.
  - 안 보이면 앱 유저에게 **DDL 권한**(해당 DB OWNER)이 있는지 확인 —
    `15-init-db.sh` 는 `CREATE DATABASE ... OWNER <앱유저>` 로 만들어 OWNER 권한을 준다.
  - 관제가 이미 채운 공유 테이블과 **컬럼 스키마가 다르면** validate 가 불일치로 실패할 수 있다.
    이 경우 관제 인프라/DBA 와 스키마 정합을 협의한다(이는 "사전 적재 필요"가 아니라 "정합 충돌").
- **★ 테이블이 분명히 있는데 validate 가 "없다"고 하면 스키마를 확인한다.** 저작도구는
  `klid_at`(`DB_SCHEMA`)만 본다. `public` 에 테이블이 있고 `klid_at` 이 비어 있으면 **구 형상 DB**다 —
  `09-operations-runbook.md` §2-5-1 로 이관한다(복사가 아니라 `ALTER ... SET SCHEMA` 로 **이동**).
  ```bash
  psql ... -c "select table_schema, count(*) from information_schema.tables
               where table_schema in ('klid_at','public') group by 1;"
  ```
- **기동 거부 — Flyway 체크섬 불일치**(`Migration checksum mismatch for migration version 62/63/71`):
  스키마 중립화로 세 파일이 바뀌었다. 이관 절차 ③(체크섬 재정렬) 또는 `flyway repair` 를 1회 수행한다.
  → `09-operations-runbook.md` §2-5-1 ③.

## 비식별 설정오류 / KPST 연동

비식별 정책은 **KPST 동거 연동으로 확정**(04-configuration.md B 절). 끄거나 mock 우회는 prd 미지원.

- **부팅 실패 — 비식별 설정오류**: `DeidentifyStep` 설정오류 거부(`KPST_DEID_ENABLED=false` + mock 아님).
  → `KPST_DEID_ENABLED=true` 로 두고 동거 KPST 주소/CA 를 설정한다.
- **부팅 실패 — KPST SSL(fail-closed)**: `KPST_DEID_BASE_URL` 이 `https://` 인데
  `KPST_DEID_CA_CERT_PATH` 미설정·읽기실패(CWE-295). → 사설 CA(ca.crt) 경로를 채우거나, 격리망이면
  `http://IP:PORT` 평문 + CA 비움.
- **영상이 비식별 'F' 로 남음 / 마킹·프레임추출 차단**: KPST 도달 불가 또는 주소/포트 오설정.
  → 05-run-verify.md "비식별(KPST) 연동 스모크"로 KPST 도달을 확인하고 `KPST_DEID_BASE_URL` 점검.
