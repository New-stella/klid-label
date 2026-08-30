# 02. [빌드머신] 패키지 수집

타깃은 **레드햇 엔터프라이즈 리눅스 8.9 (x86_64, glibc 2.28)** 다. 수집은 두 부류로 나뉜다:

- **OS 무관 산출물**(jar·FE dist·런타임 tarball·sam2·yolox): mac 등 어떤 빌드머신에서도 OK.
- **OS 종속 산출물**(pip wheel·RPM·**ffmpeg**): 반드시 **el8 컨테이너/머신 + 인터넷**에서 수집.

> ⚠ 구 서술 폐기(2026-08-28) — "타깃은 Rocky Linux 9 (glibc 2.34)" / "ffmpeg 정적은 OS 무관".
> ffmpeg 는 정적 tarball 번들을 폐기하고 **el8 RPM**으로 바뀌었으므로 더 이상 OS 무관이 아니다.

가장 간단한 길은 **전부 el8 컨테이너에서 한 번에 수집**하는 것이다(아래 "el8 컨테이너 수집").
RPM 수집(50·55)과 node_modules populate 는 빌드머신에 `dnf`/`npm` 이 없으면 **docker 로 el8
컨테이너를 자동 기동**하므로, mac 에서도 `./scripts/package.sh` 한 번으로 닫힌다.

## 실행 (el8 환경에서 전체 수집)

```bash
cd deploy/onprem
./scripts/package.sh
```

옵션:

```bash
# 시스템 의존성(RPM/ffmpeg) 수집 생략
SKIP_SYSPKGS=1 ./scripts/package.sh

# ffmpeg 예비물과 GPL 대응 소스만 빼고 나머지 시스템 RPM 은 수집(권장하지 않음 — 아래 ★)
SKIP_FFMPEG=1 ./scripts/package.sh

# 번들 PG16 RPM 수집 생략(타깃에 이미 PostgreSQL 이 있을 때)
SKIP_POSTGRES=1 ./scripts/package.sh

# HF 모델(SAM2)도 사전 다운로드(SAM2 사용 시 — 탐지 YOLOX 는 동봉 ONNX 라 불필요)
PREFETCH_HF=1 ./scripts/package.sh

# frontend 빌드 시점 변수 override (기본 /api/v1, localStorage)
# ★ VITE_TOKEN_INGRESS 는 localStorage 를 유지할 것 — 'url'/'both'/'all' 은 JWT 를 URL 쿼리로
#   받는 채널을 열어 접근 로그·리퍼러 헤더·브라우저 히스토리에 토큰이 잔존한다(CWE-598).
VITE_API_BASE_URL=/api/v1 VITE_TOKEN_INGRESS=localStorage ./scripts/package.sh

# ai-server wheel 을 받을 python 명시(대상과 동일 3.11)
PYTHON_BIN=python3.11 ./scripts/package.sh
```

## 단계별 수집물

| 단계 | 스크립트 | 수집 |
|------|----------|------|
| 1 | `package/10-build-backend.sh` | `./gradlew bootJar bootWar` → **`artifacts/backend/api.war`(반입 정본)** + `klid-backend.jar`(개발 전용). WAR 가 없으면 **실패**한다 |
| 2 | `package/20-build-frontend.sh` | `npm ci && npm run build` → `artifacts/frontend/dist` (VITE_* 빌드 주입) |
| 3 | `package/30-collect-ai-server.sh` | `app/` 소스 + pip wheel(torch CPU) + sam2 소스 + yolox 가중치 (+옵션 HF) |
| 4 | `package/40-collect-runtimes.sh` | CPython 3.11 standalone (tar.gz) — ai-server 용. **자바 런타임은 수집하지 않는다**(WAS 가 제공). 웹 서버는 `50-collect-syspkgs.sh` 가 httpd RPM 으로 수집 |
| 5 | `package/50-collect-syspkgs.sh` | **ffmpeg RPM**(`syspkgs/ffmpeg/` — **예비물**, 아래 ★) + **el8 RPM**(`mesa-libGL`/`libglvnd-glx`/`glib2`/`httpd`/`policycoreutils-python-utils` → `syspkgs/rpm/`) + **GPG 공개키**(`syspkgs/gpg/`) + **GPL 대응 소스 SRPM**(`syspkgs/ffmpeg-src/`). 각 디렉토리에 `repodata/`(로컬 yum 저장소) 생성 — 단 `ffmpeg-src/` 는 설치 대상이 아니라 색인하지 않는다 |
| 5.5 | `package/55-collect-postgresql.sh` | **(옵션·기본 ON)** PGDG **PostgreSQL 16 RPM**(postgresql16-server 등 +전이 의존 → `syspkgs/postgresql/`). `SKIP_POSTGRES=1` 로 생략. dnf 없으면 graceful SKIP |
| 6 | `package/60-collect-buildtools.sh` | **(옵션·기본 OFF)** 오프라인 빌드 키트: JDK17 full + Node20 + Gradle 8.8 + **populated gradle-home** + **frontend node_modules** + `src/` 소스 (소스 재빌드용). `WITH_BUILDTOOLS=1` 일 때만 실행 |

각 디렉토리에 `SHA256SUMS` 가 생성되어 전송 무결성을 검증한다.

## ★ ffmpeg 는 담되 자동 설치되지 않는다 (2026-08-30 사용자 확정, 구속)

수집(이 문서)과 설치(03 문서)의 지위가 **다르다**. 혼동하면 "왜 담았는데 안 깔리나"가 된다.

| | 수집(빌드머신) | 설치(대상 서버) |
|---|---|---|
| ffmpeg | **기본 포함**. `syspkgs/ffmpeg/` + `syspkgs/ffmpeg-src/` | **자동 설치 안 함.** 관제지원시스템 팀이 설치하고 우리는 검증만 한다. 필요하면 `install/install-ffmpeg.sh` 를 **사람이** 실행 |

- **왜 담나**: 관제가 설치하지 않은 것으로 밝혀졌을 때 폐쇄망에서 조달할 방법이 없다. 예비물이다.
- **왜 자동으로 안 까나**: 대상 장비는 관제지원시스템과 **공동 배치**다. 관제가 이미 깔아 둔
  ffmpeg 를 덮어쓰면 **관제 기능이 깨진다.**
- `SKIP_FFMPEG=1` 로 빼면 매체에 예비물이 없어져 **`install-ffmpeg.sh` 가 설치할 것을 못 찾는다.**
  그 매체는 "대상 장비에 ffmpeg 가 반드시 이미 있다"를 전제로만 쓸 수 있다.
- SRPM(`syspkgs/ffmpeg-src/`)이 0건이면 **수집 실패로 처리한다** — 매체를 만든 뒤에는 되돌릴 수 없는
  누락이기 때문이다(아래 「왜 소스(SRPM)를 넣나」).

## 서버 2대 — 매체는 하나, 역할은 설치 시 선택

패키지를 물리적으로 쪼개지 않는다. **같은 매체를 두 장비에 올리고 `--role` 로 고른다.**

| 반입물 | 서버 A(`app`) | 서버 B(`ai`) |
|---|:---:|:---:|
| `artifacts/backend`(api.war) | ○ | |
| `artifacts/frontend/dist` | ○ | |
| `syspkgs/rpm`(httpd·semanage / mesa-libGL·glib2) | ○ | ○ |
| `syspkgs/gpg` | ○ | ○ |
| `syspkgs/postgresql`(옵션) | ○ | |
| `syspkgs/ffmpeg`·`syspkgs/ffmpeg-src`(예비물) | ○ | |
| `vendor/wheels` · `models/weights` · `runtimes/python` | | ○ |

`install.sh` 의 무결성 검증(`SHA256SUMS`) 목록도 **역할을 따른다** — 서버 B 에서
`artifacts/frontend/dist` 를 검증하려 들지 않는다.

## 오프라인 빌드 키트(60단계) — 타깃에서 "소스 재빌드"

기존 1~5단계는 **사전 빌드 아티팩트(jar/dist)** 만 번들한다. 폐쇄망 타깃에서 인터넷 없이
**소스에서 재빌드**까지 가능하게 하려면 6단계(`60-collect-buildtools.sh`)가 추가로 다음을 채운다.
빌드 도구 바이너리만으로는 부족하고 **의존성 캐시까지** 번들해야 오프라인 빌드가 닫힌다.

| 산출물 | 번들 위치 | 비고 |
|--------|-----------|------|
| Temurin JDK17 full(javac 포함) | `buildtools/jdk/*.tar.gz` | 설치 런타임 JRE 와 별개(빌드용). `versions.sh` 의 `JDK17_FULL_*` |
| Node 20 | `buildtools/node/*.tar.xz` | `versions.sh` 의 `NODE20_*` (BUILD_NODE_MAJOR=20 정합) |
| Gradle 8.8 dist | `buildtools/gradle/*.zip` | backend 가 gradle 8.8 사용(wrapper/Dockerfile/lockfile 확인). `GRADLE_DIST_*` |
| **populated gradle-home** | `buildtools/gradle-home/` | `GRADLE_USER_HOME` 에 전 의존 jar 캐시(플랫폼 무관). `--offline` 빌드 전제 |
| **frontend node_modules** | `buildtools/frontend-node_modules.tar.gz` | **⚠ Linux x64 전용**(esbuild 등 plat 바이너리). mac 산출물 금지 |
| 빌드용 소스 | `src/{backend,frontend,ai-server}` | `.git`/`node_modules`/`build`/`dist` 제외, 플랫폼 무관 |

> **node_modules 는 반드시 Linux x64 에서 수집**한다(esbuild·rollup 등 네이티브 바이너리 포함).
> `60-collect-buildtools.sh` 는 `uname -s` 가 `Darwin`(mac)이면 node_modules populate 단계만
> **경고 후 SKIP** 하고 나머지(JDK/Node/Gradle/gradle-home/src)는 진행한다. mac 에서 키트를 만들었다면
> **node_modules 는 el8 컨테이너에서 채워야** 한다 — 60 단계가 docker 로 자동 처리하며, docker 가 없을 때만 SKIP 된다(아래 예).

```bash
# (docker 도 없어 node_modules 가 SKIP 된 경우) el8 컨테이너에서 node_modules 만 채우기
cd deploy/onprem
docker run --rm --platform linux/amd64 -v "$PWD/../..:/work" -w /work/deploy/onprem rockylinux/rockylinux:8 bash -lc '
  dnf -y install nodejs git tar gzip && \
  SKIP_GRADLE_HOME=1 SKIP_SRC=1 ./scripts/package/60-collect-buildtools.sh
'
```

> **gradle-home populate 도 인터넷 필요**: `60` 단계는 `backend` 에서 `GRADLE_USER_HOME` 를
> `buildtools/gradle-home` 으로 지정해 `bootJar -x test` 를 한 번 실행하며 전 의존성을 내려받아
> 캐시를 채운다. 따라서 이 단계는 빌드머신(인터넷 O)에서만 동작한다. 그 후 락/임시 파일은 정리된다.

### 빌드 키트는 기본 제외 — 켜려면 명시한다 (2026-08-30 사용자 확정, 구속)

**현장 재빌드 요구가 없음을 확인했으므로 빌드 키트를 반입하지 않는다.** 필요할 때만 켠다.

```bash
WITH_BUILDTOOLS=1 ./scripts/package.sh    # 빌드 키트도 함께 수집
```

빼면 함께 줄어드는 것:

| 축 | 무엇이 줄어드나 |
|---|---|
| 라이선스 표면 | JDK full 이 한 벌 더 실리지 않고, `node_modules`(645 패키지, **MPL-2.0 3건** 포함)가 반입물에서 빠져 그 목록의 고지 의무를 지지 않는다 |
| 매체 용량 | JDK full + `gradle-home` 캐시 + `node_modules` 는 GB 급이다 |
| 보안 표면 | 타깃에 컴파일러·빌드 도구 체인을 남기지 않는다 |

> ⚠ 구 기본값 폐기(2026-08-30) — "빌드 키트는 **기본 포함**이고 `SKIP_BUILDTOOLS=1` 로 끈다".
> `SKIP_BUILDTOOLS` 토글 자체는 **계속 존중한다**(그 값으로 돌리던 기존 호출이 깨지지 않게).
> `WITH_BUILDTOOLS=1` 과 `SKIP_BUILDTOOLS=1` 이 겹치면 **제외가 이긴다.**
>
> ⚠ 이 제외는 「자바 런타임 미반입」과 **다른 이유다.** 여기 JDK17 full 은 *소스 빌드용*이고
> 그쪽 JRE 는 *실행용*이다 — `versions.sh` 도 두 핀을 별개로 둔다. 둘 다 빠지지만 근거가 다르므로
> 한쪽을 되살릴 때 다른 쪽까지 함께 되살리지 말 것.

`60-collect-buildtools.sh` 자체에도 세부 토글이 있다(`SKIP_NODE_MODULES=1`, `SKIP_GRADLE_HOME=1`, `SKIP_SRC=1`).

> 빌드 키트가 없는 패키지에서 `install/build-from-source.sh` 를 실행하면 **손상이 아니라 형상**임을
> 안내하고 종료한다(사전 빌드 아티팩트로 설치하거나 `WITH_BUILDTOOLS=1` 로 재수집하라는 안내).

> 타깃에서의 소스 재빌드 절차는 **[docs/08-build-from-source.md](08-build-from-source.md)** 참고.

> **50 단계의 자동 분기**: `dnf` 가 있으면 네이티브로, 없으면 docker 로 el8 컨테이너를 띄워 수집한다.
> RPM 은 `dnf`/`yum` 이 있을 때만 `dnf download --resolve` 로 받고, 없으면(mac 등) **graceful SKIP** +
> 둘 다 없으면 `syspkgs/rpm/README-collect-on-el8.txt` 안내를 남긴다(타깃은 el8/RPM 단일 경로).

## el8 컨테이너 수집 (권장 — wheel·RPM 정합)

mac/다른 OS 빌드머신에서 jar/FE dist/런타임까지 만든 뒤, **wheel·RPM·ffmpeg 만** el8 컨테이너에서
채우거나, 처음부터 전부 컨테이너에서 수집한다. 예(전체 수집):

```bash
cd deploy/onprem
docker run --rm --platform linux/amd64 -v "$PWD/../..:/work" -w /work/deploy/onprem rockylinux/rockylinux:8 bash -lc '
  dnf -y install dnf-plugins-core java-17-openjdk-devel nodejs python3.11 git curl tar xz findutils && \
  ./scripts/package.sh
'
```

RPM(ffmpeg 포함)만 별도로 채우려면(50 단계가 docker 없이 SKIP 된 경우):

```bash
docker run --rm --platform linux/amd64 -v "$PWD:/work" -w /work rockylinux/rockylinux:8 bash -lc '
  dnf -y install dnf-plugins-core createrepo_c && \
  dnf install -y https://dl.fedoraproject.org/pub/epel/epel-release-latest-8.noarch.rpm && \
  dnf install -y https://download1.rpmfusion.org/free/el/rpmfusion-free-release-8.noarch.rpm && \
  dnf config-manager --set-enabled powertools && \
  dnf download --resolve --alldeps --archlist=x86_64,noarch --downloaddir syspkgs/ffmpeg ffmpeg && \
  dnf download --resolve --alldeps --archlist=x86_64,noarch --downloaddir syspkgs/rpm \
    mesa-libGL libglvnd-glx glib2 httpd policycoreutils-python-utils && \
  createrepo_c syspkgs/ffmpeg && createrepo_c syspkgs/rpm && \
  mkdir -p syspkgs/gpg && cp /etc/pki/rpm-gpg/*GPG-KEY* syspkgs/gpg/ && \
  mkdir -p syspkgs/ffmpeg-src && \
  dnf download --source --downloaddir syspkgs/ffmpeg-src ffmpeg x264-libs x265-libs && \
  for d in ffmpeg rpm gpg ffmpeg-src; do ( cd syspkgs/$d && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS ); done
'
```

> `createrepo_c` 로 만든 `repodata/` 와 `syspkgs/gpg/` 는 **빠뜨리면 타깃에서 설치가 실패한다**
> (전자는 의존성 해소 불가, 후자는 서명 검증 거부). 위 명령은 둘 다 포함한다.
> ⚠ PowerTools(CRB) 활성화도 필수다 — 없으면 `nothing provides libSDL2-2.0.so.0()(64bit) needed by
> ffmpeg` 로 의존 해소가 실패한다. RHEL 8 에서는 `codeready-builder-for-rhel-8-x86_64-rpms` 다.

PostgreSQL 16 RPM 만 별도로 채우려면(55 단계가 mac 에서 SKIP 한 경우 — 번들 PG 사용 시):

```bash
docker run --rm --platform linux/amd64 -v "$PWD/../..:/work" -w /work/deploy/onprem rockylinux/rockylinux:8 bash -lc '
  dnf install -y createrepo_c https://download.postgresql.org/pub/repos/yum/reporpms/EL-8-x86_64/pgdg-redhat-repo-latest.noarch.rpm && \
  dnf -qy module disable postgresql && \
  dnf download --resolve --alldeps --archlist=x86_64,noarch --downloaddir syspkgs/postgresql \
    postgresql16-server postgresql16 postgresql16-libs postgresql16-contrib && \
  createrepo_c syspkgs/postgresql && \
  mkdir -p syspkgs/gpg && cp /etc/pki/rpm-gpg/*GPG-KEY* syspkgs/gpg/ && \
  ( cd syspkgs/postgresql && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )
'
```

> **PGDG repo 핀 한계**: PGDG repo RPM 은 `...repo-latest.noarch.rpm`(가변 latest)이라 버전 고정
> 체크섬 핀이 불가하다. `55-collect-postgresql.sh` 는 repo RPM 으로 PGDG repo 메타만 추가하고
> 받은 PG16 *.rpm 의 전송 무결성은 `syspkgs/postgresql/SHA256SUMS` 로 검증한다.
> 타깃에 이미 PG 가 있으면 `SKIP_POSTGRES=1`(수집)·`USE_BUNDLED_POSTGRES=0`(설치)로 번들 PG 를 끈다.

### 어디서 무엇을 채우나 (산출물별 빌드머신)

| 산출물 | 위치 | OS 종속? | 어디서 수집 |
|--------|------|:--------:|-------------|
| backend jar | `artifacts/backend/` | 무관 | mac/Linux 어디서나(JDK17) |
| frontend dist | `artifacts/frontend/dist/` | 무관 | mac/Linux 어디서나(Node20) |
| 런타임 Python | `runtimes/python/` | 무관(linux tarball) | mac/Linux 어디서나(curl) |
| ~~런타임 JRE~~ | — | — | **수집하지 않는다**(2026-08-30 — 대상 WAS 가 Java 17 제공) |
| **ffmpeg RPM**(예비물) | `syspkgs/ffmpeg/` | **el8 정합** | **el8 컨테이너/머신**(`dnf download`). ⚠ 구: 정적 tarball·OS 무관. ⚠ **설치는 수동**(`install/install-ffmpeg.sh`) |
| sam2 소스 / yolox 가중치 | `vendor/sam2/`, `models/weights/` | 무관 | mac/Linux 어디서나(git/curl) |
| **pip wheel(torch CPU 등)** | `vendor/wheels/` | **el8 정합(glibc 2.28)** | **el8 컨테이너/머신** |
| **시스템 RPM(mesa-libGL 등)** | `syspkgs/rpm/` | **el8 정합** | **el8 컨테이너/머신**(`dnf download`) |
| **GPL 대응 소스(SRPM)** | `syspkgs/ffmpeg-src/` | **el8 정합** | **el8 컨테이너/머신**(`dnf download --source`). **설치 대상 아님** — 아래 「왜 소스를 넣나」 참조 |
| **PostgreSQL 16 RPM(옵션)** | `syspkgs/postgresql/` | **el8 정합** | **el8 컨테이너/머신**(PGDG **EL-8** repo + `dnf download`). 타깃에 PG 있으면 `SKIP_POSTGRES=1` |

> **런타임 공식 체크섬 검증(fail-closed)**: `40-collect-runtimes.sh`(Python)는 tarball 을 받은
> 직후 `scripts/lib/versions.sh` 의 공식 체크섬과 대조한다(불일치 시 즉시 중단). 설치 단계
> `11-install-runtimes.sh` 도 압축 해제 직전 동일 검증을 한 번 더 수행한다. 이는 디렉토리 단위
> `SHA256SUMS`(전송 무결성)와 별개의 **출처(공급망) 무결성** 검증이다.
>
> **RPM(ffmpeg·시스템·PG)은 검증 축이 다르다** — 버전 핀·체크섬 핀을 두지 않고 **RPM 서명**으로
> 검증한다. 조달 시 `syspkgs/gpg/` 의 공개키를 함께 반입하고(PGDG 는 `PGDG-RPM-GPG-KEY-RHEL` 이라 `RPM-GPG-KEY-*` 글롭에 걸리지 않는다), 설치 시 `rpm --import` 후
> `gpgcheck=1` 로 확인한다(기본값). 전송 무결성은 각 디렉토리 `SHA256SUMS` 가 담당한다.
> ⚠ 구 서술 폐기(2026-08-28): "ffmpeg 정적은 BtbN 의 dated autobuild 태그 … `checksums.sha256`
> 값으로 검증한다" — 정적 tarball 조달 자체가 폐기됐다.
> 웹 서버(httpd)도 배포판 RPM 이라 같은 축이다(2026-08-19 Caddy 폐기).

### 왜 소스(SRPM)를 넣나 — GPLv3+ 재배포 의무 (2026-08-30 사용자 확정, 구속)

**el8 RPM Fusion 의 `ffmpeg` 는 `GPLv3+` 다**(실측 `ffmpeg-4.4.8-1.el8` / License `GPLv3+` /
Vendor `RPM Fusion`). 관제지원시스템 dev 서버의 el7 판(`3.4.13` / `GPLv2+`)과 **다르다**.
그 바이너리를 매체에 담아 고객에게 넘기는 것은 **재배포**이고, 폐쇄망이라 "고객이 인터넷에서
받으면 된다"가 성립하지 않는다. 그래서 **대응 소스(SRPM)를 반입물에 함께 동봉**한다.

| 항목 | 규칙 |
|------|------|
| 대상 | **RPM Fusion 유래** ∩ **(L)GPL 라이선스** 패키지 |
| 비대상 | 배포판 기본 리포(BaseOS/AppStream/PowerTools) — 고객이 이미 그 배포판 사용권을 갖고 있고 우리가 재배포 주체가 아니다 |
| 중복 제거 | `sourcerpm` 기준. `ffmpeg`·`ffmpeg-libs`·`libavdevice` 는 **같은 `ffmpeg-*.src.rpm`** 이라 한 번만 받는다 |
| 설치 | **하지 않는다.** `syspkgs/ffmpeg-src/` 는 설치 스크립트가 건드리지 않고 `install.sh` 의 `SHA256SUMS` 검증만 받는다 |

실측(2026-08-30, `rockylinux/rockylinux:8`) — ffmpeg 의존 폐포 261개 중 **RPM Fusion 유래는 7개**:

| 패키지 | 라이선스 | SRPM | 동봉 |
|--------|----------|------|:----:|
| `ffmpeg` · `ffmpeg-libs` · `libavdevice` | GPLv3+ | `ffmpeg-4.4.8-1.el8.src.rpm` | ○ (1건으로 합침) |
| `x264-libs` | GPLv2+ | `x264-0.157-…el8.src.rpm` | ○ |
| `x265-libs` | GPLv2+ and BSD | `x265-3.1.2-1.el8.src.rpm` | ○ |
| `opencore-amr` · `vo-amrwbenc` | ASL 2.0 | — | ✕ (퍼미시브 — 소스 제공 의무 없음) |

⚠ 나머지 20개는 EPEL(Vendor `Fedora Project`) 유래이고 그중 GPL 계열이 5개
(`jack-audio-connection-kit`·`libffado`·`vid.stab`·`xvidcore`·`zvbi`) 있다.
**현재 규칙은 EPEL 을 대상에서 제외한다** — 위 「비대상」 판정을 EPEL 에도 적용한 것이며,
EPEL 은 배포판 기본 리포가 아니므로 **법무 확인이 필요한 잔여 항목**이다.

## ★ 복사 대상 파일/라이브러리 명세표

### backend

| 무엇 | 어디서 수집 | 번들 위치 | 대략 용량 |
|------|-------------|-----------|:---------:|
| **WAR(반입 정본)** | `backend/build/libs/api.war` (bootWar) | `artifacts/backend/api.war` | ~60–90MB |
| 실행 jar(개발 전용) | `backend/build/libs/*.jar` (bootJar) | `artifacts/backend/klid-backend.jar` | ~60–90MB |

> **두 산출물의 지위가 다르다** (@design DEPLOY-001 의 `build_artifacts`):
> - `api.war` = **반입 정본**. 대상 장비의 외부 WAS(Tomcat 10.1.x + Java 17)에 올린다.
>   **파일 이름이 곧 웹 컨텍스트(`/api`)** 이므로 rename 금지.
>   WAR 에는 톰캣이 들어가지 않는다(`providedRuntime` → `WEB-INF/lib-provided`).
> - `klid-backend.jar` = **개발 환경 전용이라 반입 대상이 아니다.** 설치 스크립트는 WAR 형상에서
>   이 jar 를 배치하지 않는다(베어메탈 토글 `INSTALL_BACKEND_SYSTEMD_UNIT=1` 일 때만 쓴다).
>   빌드머신이 계속 만들어 두는 이유는 개발 형상과 베어메탈 복귀 경로가 그 산출물을 쓰기 때문이고,
>   둘은 같은 소스에서 나오므로 내용이 갈릴 일이 없다.
>
> 런타임 의존(ffmpeg·curl)은 syspkgs/ 에서 별도 수집. **자바는 수집하지 않는다** — WAS 가 제공한다.

### frontend

| 무엇 | 어디서 수집 | 번들 위치 | 대략 용량 |
|------|-------------|-----------|:---------:|
| 정적 dist | `frontend/dist` (vite build) | `artifacts/frontend/dist` | ~수 MB |

> VITE_API_BASE_URL=/api/v1, VITE_TOKEN_INGRESS=localStorage 가 **빌드 시점에 정적 치환**됨.
> (토큰 인계 기본값이 localStorage 인 이유는 위 override 예시 주석 참고 — URL 쿼리 JWT 잔존 방지)

### ai-server

| 무엇 | 어디서 수집 | 번들 위치 | 대략 용량 |
|------|-------------|-----------|:---------:|
| 앱 소스 | `ai-server/app` | `artifacts/ai-server/app` | < 1MB |
| requirements | `ai-server/requirements.txt` | `artifacts/ai-server/requirements.txt` | 작음 |
| pip wheel(일반) | PyPI (`pip download`) | `vendor/wheels/*.whl` | 수백 MB |
| **torch/torchvision (CPU)** | **PyTorch CPU 인덱스** | `vendor/wheels/` | **~200MB+** |
| sam2 소스 | `git clone facebookresearch/sam2` | `vendor/sam2/sam2-src` | ~수십 MB |
| YOLOX 가중치 | `ai-server/weights/yolox_s.onnx` | `models/weights/yolox_s.onnx` | ~35MB |
| (옵션) HF 모델 | HuggingFace Hub | `models/hf-cache/` | 모델별 수백 MB |

### 런타임 / 시스템 의존성

| 무엇 | 어디서 수집 | 번들 위치 | 대략 용량 |
|------|-------------|-----------|:---------:|
| CPython 3.11 standalone (3.11.15) | python-build-standalone (태그 20260623) | `runtimes/python/*.tar.gz` | ~30MB |

> ⚠ 구 행 폐기(2026-08-30) — `Temurin JRE 17 (17.0.19+10)` → `runtimes/jdk/*.tar.gz` (~45MB).
> 자바 런타임은 반입 대상이 아니다(대상 장비 WAS 가 Tomcat 10.1.x + Java 17 로 이미 돌고 있다).
> `versions.sh` 의 `TEMURIN_JRE_*` 핀은 베어메탈 복귀용으로 **남겨 두되 아무도 읽지 않는다**.

| httpd | 배포판 저장소(RPM) | `syspkgs/rpm/httpd*.rpm` | ~2MB(+의존성) |
| **ffmpeg RPM (4.4.8-1.el8)** — **예비물, 자동 설치 안 함** | RPM Fusion free el8 (+EPEL, PowerTools) | `syspkgs/ffmpeg/*.rpm` + `repodata/` | ~144MB(261개, 전이 의존 전량) |
| **GPL 대응 소스(SRPM) 3건** | RPM Fusion free el8 (`dnf download --source`) | `syspkgs/ffmpeg-src/*.src.rpm` | **~11.4MB**(ffmpeg 9.2MB + x265 1.4MB + x264 0.77MB) |
| **RPM: mesa-libGL/libglvnd-glx/glib2/httpd** | `dnf download`(el8) | `syspkgs/rpm/*.rpm` + `repodata/` | 수~수십 MB |
| **(옵션) PostgreSQL 16 RPM** | PGDG **EL-8** repo + `dnf download`(el8) | `syspkgs/postgresql/*.rpm` + `repodata/` | 수십 MB |

## 함정 요약(반드시 인지)

- **torch CPU**: 기본 PyPI 는 CUDA wheel(거대)을 준다. 스크립트는 PyTorch CPU 인덱스에서 받는다.
  현재 lock 버전 `torch==2.12.0` / `torchvision==0.27.0` 의 **cp311 x86_64 CPU wheel 이 CPU 인덱스에
  존재함을 확인**(2026-06-24)했으므로 lock 그대로 받힌다. 향후 lock 의 torch 버전이 CPU 인덱스에
  없을 때만 실패하며 그 경우 → 06-troubleshooting "torch CPU" 절.
- **sam-2**: requirements 의 `git+https://.../sam2.git` 는 폐쇄망에서 설치 불가 → git 소스를 vendor 해
  설치 시 로컬 경로로 처리한다. Dockerfile 은 torch 를 strip 했지만 **베어메탈은 torch 도 설치**한다.
- **HF 모델**: 탐지(YOLOX)는 동봉 ONNX 라 불필요. SAM2 사용 시에만 `PREFETCH_HF=1` 로 미리 받아야 폐쇄망에서 동작.
- **PEP 517 빌드 백엔드(setuptools·wheel)**: `requirements.txt` 는 pip-compile 산출물이라 끝에
  `# setuptools` 주석만 남기고 **락에서 제외**한다. 그런데 sam2 는 로컬 소스 설치이고 sdist 로만
  받히는 패키지도 있어 둘 다 PEP 517 빌드를 거치는데, pip 는 **빌드 격리 환경에서 setuptools 를
  다시 찾는다**(venv 에 이미 있어도 쓰지 않는다). 그래서 30 단계가 `setuptools`·`wheel` wheel 을
  명시적으로 반입한다. 빠지면 폐쇄망에서 `Could not find a version that satisfies the requirement
  setuptools>=61 (from versions: none)` 로 **설치가 멈춘다**(2026-08-30 실측).
- **sdist 혼입**: `pip download` 는 wheel 이 없는 패키지를 `.tar.gz`(sdist)로 받는다. 2026-08-30 기준
  이 lock 에는 `antlr4-python3-runtime`·`iopath` 2건이 해당한다(둘 다 순수 파이썬이라 위 빌드
  백엔드만 있으면 설치된다). **`--only-binary=:all:` 을 전체에 강제하지 않는 이유가 이것**이다 —
  강제하면 이 2건 때문에 수집 자체가 실패한다. 대신 30 단계가 sdist 목록을 경고로 출력하므로,
  **C 확장 sdist 가 새로 끼어들면 아래 무네트워크 리허설에서 잡아야 한다**.
- **RPM `--alldeps` 와 `repodata` 는 한 세트**: `--alldeps` 없이 받으면 "빌드 컨테이너에 이미 깔린"
  패키지가 빠져 타깃에서 의존성이 부족해진다. 반대로 그렇게 받은 세트를 `dnf install <파일들>` 로
  넘기면 기반 패키지(glibc 등) 충돌로 **설치가 통째로 실패**한다. 그래서 `createrepo_c` 로 로컬
  저장소를 만들어 반입하고, 설치는 `dnf install <패키지명>` 으로 한다(dnf 가 필요한 것만 고른다).
  실측: 261개 반입 → 105개 설치, glibc 무변경.
- **el8 모듈러 패키지(`module_hotfixes=1`)**: RHEL 8 의 `httpd` 는 **모듈러 패키지**
  (`httpd-2.4.37-…module+el8…`)인데 `createrepo_c` 는 모듈러 메타데이터(`modules.yaml`)를 만들지
  않는다. 그대로 두면 dnf 가 `No available modular metadata for modular package 'httpd-…',
  it cannot be installed` 로 **거부한다**(2026-08-30 리허설에서 실제로 잡힌 실패). 설치 헬퍼가
  로컬 저장소 정의에 `module_hotfixes=1` 을 넣어 해소한다. ⚠ el9 에는 모듈러가 거의 없어 이
  함정이 없다 — **el8 로 내려오면서 새로 생긴 것**이라 구 문서에 대응 서술이 없다.

## ★ 반출 전 필수 — 무네트워크 설치 리허설

폐쇄망은 **설치 시점에 네트워크가 0** 이다. 조달 누락은 빌드머신에서는 절대 드러나지 않고
**현장에서 처음 드러난다**. 그래서 반출 전에 `--network none` 컨테이너로 리허설한다.

> 이 리허설이 잡아내는 것: 반입 안 된 RPM 의존성 · 누락된 pip wheel · GPG 키 부재로 인한 설치
> 거부 · sdist 의 빌드 의존성 부재 · repodata 누락. 전부 **"조용히 빠지는"** 부류라 눈으로는 못 찾는다.

```bash
# deploy/onprem 에서 실행. --network none 이 핵심(인터넷 접근을 물리적으로 차단).
docker run --rm --network none --platform linux/amd64 \
  -v "$PWD:/onprem" -w /onprem \
  rockylinux/rockylinux:8 bash -lc '
    # 0) 네트워크가 정말 끊겼는지 먼저 확인 — 살아 있으면 리허설이 무의미하다.
    timeout 5 curl -sI https://pypi.org >/dev/null 2>&1 && { echo "!! 네트워크가 열려 있음 — 리허설 무효"; exit 1; }
    echo "네트워크 차단 확인 OK"

    # 1) 설치 스크립트 전체 실행(비대화)
    ASSUME_YES=1 ./scripts/install.sh
  '
```

확인 항목(하나라도 실패하면 반출 금지):

| # | 확인 | 판정 방법 |
|---|------|----------|
| 0 | `api.war` 반입·배치 | `artifacts/backend/api.war` 존재 + `SHA256SUMS` 에 그 파일이 **목록돼 있음**(반입 정본이 검증 없이 통과하지 않도록) |
| 1 | ffmpeg/ffprobe 설치·실행 | `ffmpeg -version`, `ffprobe -version` 이 `/usr/bin` 에서 동작 |
| 2 | ffprobe 실동작 | `ffmpeg -f lavfi -i testsrc=duration=3:size=320x240:rate=10 -y /tmp/t.mp4 && ffprobe -v error -show_entries format=duration -of default=nw=1 /tmp/t.mp4` → `duration=3.000000` |
| 3 | opencv 런타임 의존 | `python -c 'import cv2'` (libGL.so.1 해소) |
| 4 | ai-server wheel 설치 | `pip install --no-index` 단계가 **네트워크 조회 없이** 완주 |
| 5 | sam2 소스 설치 | PEP 517 빌드가 `setuptools>=61` 을 **번들 wheels 에서** 찾아 성공 |
| 6 | GPG 서명 검증 | `gpgcheck=1` 로 RPM 설치 성공(실패 시 `syspkgs/gpg/` 반입 여부 확인) |
| 7 | httpd | `httpd -v` 동작 |
| 8 | 자바 미반입 확인 | `runtimes/jdk` 가 비어 있어도 설치가 완주한다(자바는 WAS 소유) |

> ⚠ **이 리허설은 WAS 를 재현하지 않는다.** 컨테이너에 Tomcat 이 없으므로 `api.war` 실제 기동과
> WAS 설정 이관(docs/10-was-settings.md) 효과는 **여기서 검증되지 않는다.**
> 그 두 가지는 대상 장비의 WAS 에서만 확인할 수 있고, 특히 **대용량 업로드 설정은 사람이 1회 수행**해야 한다.
> ⚠ 그 1회를 **빠뜨려도 설치는 성공하고 화면도 뜬다** — 아무 신호가 없다가 큰 파일 업로드에서만 실패한다.

> ⚠ 리허설 컨테이너는 최소 이미지라 **실제 RHEL 8.9 서버보다 설치된 패키지가 적다**. 즉 이 리허설을
> 통과하면 실서버에서는 더 안전한 쪽이다(반대는 성립하지 않는다). 다만 서버에만 있는 SELinux
> Enforcing·방화벽·기존 PG 같은 조건은 여기서 재현되지 않으므로 05-run-verify.md 를 함께 본다.
>
### 리허설이 실제로 검증한 범위 (2026-08-30 실측)

아래는 `--network none`(DNS 해석 실패로 차단을 먼저 확인) 상태에서 **실행해 통과한 것**이다.
구 기재 *"절차만 기술돼 있고 ffmpeg/시스템 RPM 구간(1·2·6·7)만 검증"* 은 **폐기** — 그 뒤
리허설이 더 넓게 돌았다.

| 구간 | 검증 내용 | 표 항목 |
|---|---|---|
| GPG·저장소 | GPG 키 등록 → 로컬 저장소에서 RPM 설치 성공. **glibc 무변경** 확인 | 6 |
| httpd | 모듈러 스트림으로 설치 완주 | 7 |
| SELinux 도구 | 관련 도구 패키지 설치 완주 | — |
| ffmpeg | 3단 전부 — 부재 시 `die` → 번들 RPM 설치 → **실동작 검증 통과** | 1 · 2 |
| 파이썬 오프라인 설치 | 번들 파이썬 venv 에 `--no-index` 로 설치 후 **import 성공**: `cv2` 4.13.0 · `torch` 2.12.0+cpu · `onnxruntime` 1.27.0 · `sam2` · `fastapi` | 3 · 4 · 5 |
| 설치 스크립트 | `install.sh --role=ai` 완주 · `install.sh --role=app` 완주(설정값을 채운 뒤) | — |

### 리허설이 확인하지 <못한> 것

통과했다고 해서 아래가 검증된 것은 **아니다**. 이 경계가 이 절의 값어치다.

- **WAS 기동·`api.war` 실행** — 컨테이너에 Tomcat 이 없다. 설치 스크립트가 WAR 를 배치하는
  데까지만 확인됐고 그것이 뜨는지는 보지 않았다.
- **WAS 설정 이관 효과**(docs/10-was-settings.md) — 대상 장비의 WAS 에서만 확인된다.
  ★ **대용량 업로드 설정은 대상 장비에서 사람이 1회 수행해야 하고, 빠뜨려도 아무 신호가 없다.**
  설치는 성공하고 화면도 뜨는데 큰 파일을 올릴 때에야 실패한다.
- **PostgreSQL 실기동** — 리허설의 `systemctl` 은 스텁이다. 기동·스키마 적용은 확인되지 않았다.
- **SELinux Enforcing 문맥** — 도구 설치만 했을 뿐 Enforcing 상태에서의 파일 문맥·포트 정책은
  재현하지 않았다.
- **ai-server 실기동·추론** — import 까지만 확인했다. 서비스 기동과 실제 추론은 보지 않았다.
- **실제 RHEL 8.9** — 리허설 베이스는 **Rocky 8.10** 이다. glibc 계열은 같지만 동일 OS 가 아니다.

## 전송

수집이 끝나고 **위 리허설을 통과하면** `deploy/onprem/` 폴더 **전체**를 USB/전송 매체로 복사해
대상 서버로 옮긴다. (`VERSION.built` 에 수집 시점/커밋이 기록된다.)
