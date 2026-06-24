# 01. 사전 요구사항

## ★ 가장 중요한 제약 — 빌드머신 = 대상 서버와 동일 OS/아키텍처

폐쇄망 설치의 모든 의존성은 **빌드머신에서 수집**된다. 다음 산출물은 OS/아키텍처/glibc 에 강하게 묶인다.

- **pip wheel**: manylinux(glibc 버전 태그) + CPython ABI(cp311) 정합
- **시스템 패키지(.deb)**: 배포판/버전 정합
- **런타임 바이너리**(JRE/Python standalone/Caddy): linux x86_64 빌드

따라서 빌드머신은 **대상 서버와 동일한 Linux x86_64(glibc 계열, 가능하면 동일 배포판/버전)** 여야 한다.
macOS/Windows/ARM 빌드머신에서 수집하면 폐쇄망에서 동작하지 않는다.

> 권장: 대상 서버와 같은 이미지의 임시 VM/컨테이너를 인터넷 가능 영역에 띄워 빌드머신으로 사용.

## 빌드머신 요구사항 (인터넷 필요)

| 항목 | 요구 | 확인 |
|------|------|------|
| OS/arch | 대상과 동일 Linux x86_64 (glibc) | `uname -srm` |
| JDK | **17** (backend bootJar) | `java -version` |
| Node.js | **20** + npm (frontend build) | `node -v` |
| Python | **3.11** (pip download — cp311 wheel) | `python3.11 --version` |
| git | sam2 git 소스 clone | `git --version` |
| 기본 도구 | bash, curl, tar | — |
| (데비안) | apt-get, dpkg (+ 선택 apt-rdepends) | `.deb` 수집용 |
| 디스크 | 약 10~15GB 여유 | torch CPU wheel·런타임이 큼 |

> JDK17/Node20/Python3.11 자체는 빌드머신에 미리 설치되어 있어야 한다(번들 대상 아님).
> 대상 서버 런타임(JRE/Python/Caddy)은 package.sh 가 별도로 받아 번들한다.

## 대상 서버 요구사항 (폐쇄망, 인터넷 불필요)

| 항목 | 요구 |
|------|------|
| OS/arch | Linux x86_64, glibc (빌드머신과 동일 권장) |
| init | **systemd** |
| 권한 | 설치는 **root/sudo** |
| CPU/GPU | **CPU only** (GPU/CUDA 불필요 — torch/onnxruntime CPU) |
| 메모리 | backend(JVM, MaxRAMPercentage 75%) + ai-server(torch CPU) 고려해 충분히(권장 ≥ 8GB) |
| 디스크 | 앱·런타임·모델 + 영상 저장소. 영상 규모에 비례(저장소 별도 산정) |
| PostgreSQL | **16** 접속 정보(host/port/db/user/pw). control(klid_system) + portal 2개 DB |
| **KPST 비식별 서버** | **폐쇄망에 별도 설치·접근 가능**해야 함(본 패키지 비포함, 외부 시스템). backend 가 폴링 연동(http 또는 https+사설CA). 비식별은 파이프라인 선두 필수 단계라 prd 에서 끄거나 mock 우회 불가 — 04-configuration.md B 절 참고 |
| 네트워크 | 같은 호스트 내 80/8080/9300/5432 + KPST 비식별 포트(예 9201) 도달. 외부 인바운드는 80만 노출 권장 |

### 런타임 시스템 패키지(대상 서버)

ai-server/backend 가 런타임에 의존하는 시스템 라이브러리:

- backend: `ffmpeg`(ffprobe 포함), `curl`
- ai-server: `libgl1`, `libglib2.0-0` (opencv 런타임)

데비안/우분투 대상이면 package.sh 가 `.deb` 를 수집해 install.sh 가 오프라인 설치한다.
비데비안(RHEL/Rocky 등)이면 대상 OS 패키지 매니저로 별도 준비해야 한다(06-troubleshooting 참고).

### PostgreSQL 준비

- control DB(`klid_system`)와 portal DB(`portal`), 앱 유저(`klid_user` 등)와 비밀번호.
- 스키마/LS_* 테이블은 backend 가 기동 시 **Flyway 로 자동 생성**(추가 작업 불필요).
- 단, 관제 공유 테이블(`MNG_*`/`QRTZ_*`)은 `ddl-auto=validate` 로 **검증만** 하므로, 온프렘이
  자체 DB 를 운영하면 해당 공유 스키마도 사전 준비되어 있어야 한다(없으면 기동 실패).
  → 03-install.md / 04-configuration.md DB 절 참고.
