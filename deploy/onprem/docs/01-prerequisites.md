# 01. 사전 요구사항

## ★ 타깃 OS — Rocky Linux 9 (RHEL 9 계열, x86_64, glibc 2.34)

이 패키지는 **Rocky Linux 9**(RHEL 9 계열, x86_64, glibc 2.34, dnf/rpm)를 대상으로 한다.
시스템 의존성은 **RPM 기반**으로 수집·설치한다.

- 런타임(JRE17/Python standalone/Caddy)·pip wheel(torch manylinux_2_28)은 glibc 2.34 호환이라 그대로 쓴다.
- **ffmpeg 는 정적 바이너리**로 번들한다 — Rocky 9 base/AppStream 에 ffmpeg 가 없고
  RPM Fusion/EPEL 미러가 필요해 폐쇄망에서 의존성 지옥에 빠지기 때문(02·03 문서 참고).
- **libGL(opencv)·glib2** 는 Rocky 9 AppStream 의 `mesa-libGL`/`libglvnd-glx`/`glib2` RPM 으로 제공된다.

## ★ 빌드머신 제약 — wheel/RPM 은 대상과 동일 OS/아키텍처에서 수집

폐쇄망 설치의 모든 의존성은 **빌드머신에서 수집**된다. 다음 산출물은 OS/아키텍처/glibc 에 강하게 묶인다.

- **pip wheel**: manylinux(glibc 버전 태그) + CPython ABI(cp311) 정합
- **시스템 RPM**(mesa-libGL 등): Rocky 9(RHEL 9) 정합 → **rockylinux:9 컨테이너/머신에서 수집**
- **런타임 바이너리**(JRE/Python standalone/Caddy): linux x86_64 빌드(OS 무관 tarball)
- **ffmpeg 정적 바이너리**: OS 무관(정적 링크) — curl 만 있으면 mac 에서도 수집 가능

따라서 **wheel·RPM 수집 단계는 Rocky Linux 9 (x86_64, glibc 2.34) 환경 + 인터넷**에서 수행해야 한다.
권장: `rockylinux:9` 컨테이너(또는 Rocky 9 머신)를 인터넷 가능 영역에 띄워 빌드머신으로 사용.
(jar/FE dist/런타임/ffmpeg 등 OS 무관 산출물은 mac 등 다른 빌드머신에서 만들어도 된다 — 02 문서 구분표 참고.)

## 빌드머신 요구사항 (인터넷 필요)

| 항목 | 요구 | 확인 |
|------|------|------|
| OS/arch | wheel·RPM 수집은 **Rocky Linux 9 x86_64** | `uname -srm`, `cat /etc/os-release` |
| JDK | **17** (backend bootJar) | `java -version` |
| Node.js | **20** + npm (frontend build) | `node -v` |
| Python | **3.11** (pip download — cp311 wheel) | `python3.11 --version` |
| git | sam2 git 소스 clone | `git --version` |
| 기본 도구 | bash, curl, tar, xz | — |
| (Rocky 9) | dnf (+ `dnf-plugins-core` 의 `dnf download`) | RPM 수집용 |
| 디스크 | 약 10~15GB 여유 | torch CPU wheel·런타임이 큼 |

> JDK17/Node20/Python3.11 자체는 빌드머신에 미리 설치되어 있어야 한다(번들 대상 아님).
> 대상 서버 런타임(JRE/Python/Caddy/ffmpeg)은 package.sh 가 별도로 받아 번들한다.
> RPM 수집(`mesa-libGL` 등)은 **rockylinux:9 컨테이너/머신**에서 해야 한다(`dnf download`).
> ffmpeg 정적 바이너리·jar·FE dist·런타임 tarball 은 OS 무관이라 다른 빌드머신에서 만들어도 된다.

## 대상 서버 요구사항 (폐쇄망, 인터넷 불필요)

| 항목 | 요구 |
|------|------|
| OS/arch | **Rocky Linux 9** (RHEL 9 계열, x86_64, glibc 2.34) |
| init | **systemd** |
| 권한 | 설치는 **root/sudo** |
| CPU/GPU | **CPU only** (GPU/CUDA 불필요 — torch/onnxruntime CPU) |
| 메모리 | backend(JVM, MaxRAMPercentage 75%) + ai-server(torch CPU) 고려해 충분히(권장 ≥ 8GB) |
| 디스크 | 앱·런타임·모델 + 영상 저장소. 영상 규모에 비례(저장소 별도 산정) |
| PostgreSQL | **16** 접속 정보(host/port/db/user/pw). control(klid_system) + portal 2개 DB |
| **KPST 비식별 서버** | **폐쇄망에 별도 설치·접근 가능**해야 함(본 패키지 비포함, 외부 시스템). backend 가 폴링 연동(http 또는 https+사설CA). 비식별은 파이프라인 선두 필수 단계라 prd 에서 끄거나 mock 우회 불가 — 04-configuration.md B 절 참고 |
| 네트워크 | 같은 호스트 내 80/8080/9300/5432 + KPST 비식별 포트(예 9201) 도달. 외부 인바운드는 80만 노출 권장 |

### 런타임 시스템 의존성(대상 서버 = Rocky 9)

ai-server/backend 가 런타임에 의존하는 것 — 모두 패키지에 번들되어 install.sh 가 오프라인 설치한다:

- backend: **ffmpeg/ffprobe** → 정적 바이너리(`syspkgs/ffmpeg/`)를 `/opt/klid/runtime/ffmpeg/bin/` 로 배치.
- ai-server: **libGL.so.1**(opencv import) + glib2 → Rocky 9 RPM(`mesa-libGL`/`libglvnd-glx`/`glib2`,
  `syspkgs/rpm/`)을 `dnf install --disablerepo='*'`(폴백 `rpm -Uvh`)로 오프라인 설치.

> Rocky 9 base/AppStream 에 ffmpeg 가 없으므로(RPM Fusion/EPEL 미러 필요) ffmpeg 만 정적 바이너리로
> 처리하고, libGL/glib2 는 AppStream RPM 으로 처리한다. 번들이 비어 있으면 install.sh 가 경고를 낸다
> (그 경우 대상에 사전 설치되어 있어야 함) — 06-troubleshooting 참고.

### PostgreSQL 준비

- control DB(`klid_system`)와 portal DB(`portal`), 앱 유저(`klid_user` 등)와 비밀번호.
- 스키마/LS_* 테이블은 backend 가 기동 시 **Flyway 로 자동 생성**(추가 작업 불필요).
- 단, 관제 공유 테이블(`MNG_*`/`QRTZ_*`)은 `ddl-auto=validate` 로 **검증만** 하므로, 온프렘이
  자체 DB 를 운영하면 해당 공유 스키마도 사전 준비되어 있어야 한다(없으면 기동 실패).
  → 03-install.md / 04-configuration.md DB 절 참고.
