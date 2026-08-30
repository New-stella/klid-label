# 00. 개요 — 아키텍처 / 데이터 흐름 / 레이아웃

## 구성 요소

klid-label 은 모노레포의 3개 런타임으로 구성된다. 폐쇄망 **서버 2대**에 나눠 설치하되 **backend 만
외부 WAS 에 WAR 로 반입**하고 나머지는 systemd/RPM 으로 설치한다.

> **서버 구성 (2026-08-30 확정)** — 매체는 하나이고 **설치할 때 역할만 고른다**(`--role=app|ai`).
> 역할을 주지 않으면 종전대로 전체가 한 대에 설치된다(하위호환).
>
> | | **서버 A (`app`)** | **서버 B (`ai`)** |
> |---|---|---|
> | 올라가는 것 | httpd(정적 dist + `/api` 프록시) · 외부 WAS 에 `api.war` · (옵션)PostgreSQL | ai-server(파이썬 3.11 + 오프라인 휠 + YOLOX/SAM2 모델) |
> | systemd 유닛 | `httpd` — **백엔드는 유닛이 아니다**(WAS 가 기동 주체) | `klid-ai-server` |
> | httpd | 필요 | **불필요** |
> | 파이썬 | 불필요 | **전부 반입**(장비에 파이썬이 없다) |
> | ffmpeg | **전제조건** — 관제지원시스템 팀이 설치. 우리는 검증만 한다 | 쓰지 않는다 |
> | GPU | 해당 없음 | **장비에 GPU 가 있으나 이번 반입은 CPU 전용**이다(torch CPU 휠·onnxruntime CPU) |
>
> ⚠ **"GPU 가 없다"가 아니라 "이번 반입이 GPU 를 쓰지 않는다"** 이다. 이걸 적어 두지 않으면
> 나중에 "GPU 서버인데 왜 느린가"라는 형태로만 드러난다. GPU 를 쓰려면 CUDA 휠·드라이버·
> onnxruntime-gpu 로 재수집해야 하며 이번 범위가 아니다.

> **배포 형상 (2026-08-30 사용자 확정, 구속 · @design DEPLOY-001 · RUNBOOK-001)**
> backend 는 `api.war` 로 **대상 장비에 이미 돌고 있는 WAS(Tomcat 10.1.x + Java 17)** 에 올린다.
> 그래서 이 패키지는 **자바 런타임을 반입하지 않는다.**
> ⚠ 구 서술 폐기(2026-08-30) — "3개 런타임을 폐쇄망 단일 서버에 **모두 베어메탈 설치**한다"
>   (backend 를 systemd 가 `java -jar` 로 띄우던 형상).
> ⚠ ai-server 는 이 변경과 무관한 **별도 파이썬 프로세스**라 런타임·휠·모델을 그대로 반입한다.

```
            ┌────────── 서버 A (app · x86_64, RHEL 8.9) ──────────┐   ┌─── 서버 B (ai) ───┐
   사용자 ──┤  httpd :80  ──/api/*──►  Spring Boot :8080          │──►│  FastAPI :9300    │
  (브라우저)│  (정적 dist)            (backend, 외부 WAS 의 api.war)│   │ (ai-server Py3.11)│
            │       │                       │                     │   │  YOLOX / SAM2     │
            │       └─ SPA(dist)            ├─► PostgreSQL :5432   │   │  ※ CPU 전용       │
            │                               ├─► ffmpeg(전제조건)   │   │  ※ httpd 없음     │
            │                               ├─► 저장소(NAS /nas-storage)  └───────────────┘
            │                               └─► KPST 비식별 서버 (폴링, 외부 동거)         │
            └───────────────────────────────────────│─────────────────────────────────────┘
                                                     ▼
                              ┌─ KPST 비식별 서버 (폐쇄망 별도 설치, 본 패키지 비포함) ─┐
                              │   backend 가 폴링으로 위탁/결과 회수 (http 또는 https+CA) │
                              └──────────────────────────────────────────────────────────┘
```

> 서버 A 의 `AI_SERVER_URL` 이 **서버 B** 를 가리켜야 한다. 기본값 `http://127.0.0.1:9300` 은
> 단일 서버 구성 기준이라, 2대로 나누면 **반드시 바꿔야 한다**(04-configuration.md).

> 위 그림의 `Spring Boot :8080` 은 **외부 WAS(Tomcat 10.1.x + Java 17)에 올린 `api.war`** 다.
> 별도 프로세스로 띄우는 것이 아니라 WAS 가 기동한다(2026-08-30 형상 확정).

- **frontend (httpd)**: React+Vite 정적 빌드(`dist`)를 80포트로 서빙하고, `/api/*` 를 backend 로 리버스프록시. 관제지원시스템 웹 서버와 동일 사양(Apache httpd)이다.
- **backend (Spring Boot, 외부 WAS 에 `api.war`)**: 인증/DB/오케스트레이션/라벨 CRUD/배치. 포트 8080.
  컨텍스트 `/api` 는 **WAR 파일명이 정한다** — WAR 배포에서는 `server.servlet.context-path` 가
  적용되지 않으므로 **`api.war` 라는 이름을 바꾸지 않는다**. 같은 이유로 `server.tomcat.*`(업로드
  본문 한도·스레드 예산·비동기 타임아웃)도 적용되지 않아 **WAS 설정으로 옮겨야 한다**
  ([10-was-settings.md](10-was-settings.md) — 빠뜨리면 대용량 업로드만 조용히 깨진다).
  control DB(klid_system) + portal DB 2개 DataSource. **스키마는 `db/schema.sql` 1회 로드로 준비**하고
  **온프렘은 Flyway 를 쓰지 않는다**(사전요건은 빈 DB 2개). ⚠ `ddl-auto=validate` 가 선언돼 있으나
  **실동작하지 않아** 스키마가 비어도 기동은 막히지 않는다 — 01-prerequisites.md 「스키마/테이블」.
- **ai-server (FastAPI)**: YOLOX(onnxruntime CPU, 탐지 단일 백엔드)/SAM2 추론만. 상태·인증·DB 없음. 포트 9300.
  **서버 B 에 단독 설치**하며 httpd 는 필요 없다. 장비에 파이썬이 없어 런타임까지 전부 반입한다.
  **장비에 GPU 가 있으나 이번 반입은 CPU 전용**이다(torch CPU 휠 + onnxruntime CPU).
- **ffmpeg / ffprobe (서버 A 전제조건)**: 우리 반입물이 아니다. **관제지원시스템 팀이 대상 장비에
  설치**하며, 설치 스크립트는 **자동 설치하지 않고 검증만** 한다(공동 배치 서버라 관제 설치본을
  덮어쓰면 관제가 깨진다). 예비물은 매체에 동봉되며 `install/install-ffmpeg.sh` 로 **사람이**
  설치한다. 상세는 [03-install.md](03-install.md) 「ffmpeg — 세 경로」.
- **KPST 비식별 서버 (외부 동거)**: 폐쇄망에 **별도 설치**(본 패키지 비포함). backend 가 폴링으로
  비식별을 위탁하고 결과를 회수한다. 비식별은 파이프라인 선두 필수 단계라 prd 에서 끄거나 mock 우회 불가.

## 데이터 흐름(요약)

1. 관제 학습용 설정 영상을 주기 배치가 픽업 → `LS_DATA_RAW` 적재(PENDING).
2. 적재 직후 선두 **비식별** → 마킹 → VLM 시계열(콜백) → FFmpeg 프레임추출 → YOLO/SAM2 오토라벨링.
3. 라벨링/검수 → 검수 승인 시 라벨 스냅샷(`LS_LABEL_VERSION`) + (옵션)관제 outbound 통지.

> **비식별(KPST)은 폐쇄망 동거 연동이 확정 정책**(끄거나 mock 우회 불가). 나머지 외부(관제통지 / VLM /
> 증강)는 토글로 on/off 한다. 상세는 04-configuration.md B 절 "외부 연동 경로 / 비식별(KPST) 동거 연동" 참고.

## 포트맵

| 서비스 | 포트 | 헬스 |
|--------|:----:|------|
| frontend(httpd) | 80 | `GET /` |
| backend | 8080 | `GET /api/actuator/health/liveness` |
| ai-server | 9300 | `GET /health` |
| PostgreSQL | 5432 | — |
| KPST 비식별(외부 동거) | 설치값(예 9201) | KPST 제공(http 또는 https+사설CA) |

## 패키지 디렉토리 의미

| 디렉토리 | 채우는 주체 | 내용 |
|----------|-------------|------|
| `artifacts/backend` | package.sh | **`api.war`**(반입 정본 — WAS 에 올린다). `klid-backend.jar` 는 **개발 환경 전용이라 담지 않는다** — 수집이 기본으로 만들지도 복사하지도 않고(`WITH_BACKEND_JAR=1` 로만 켠다), 설치도 배치하지 않는다 |
| `artifacts/frontend/dist` | package.sh | 정적 빌드 결과 |
| `artifacts/ai-server` | package.sh | `app/` 소스 + `requirements.txt` |
| `vendor/wheels` | package.sh | 모든 pip 의존성 wheel(torch CPU 포함) |
| `vendor/sam2/sam2-src` | package.sh | sam2 git 소스(VCS 의존성 오프라인화) |
| `runtimes/python` | package.sh | ai-server 런타임 바이너리(tar.gz). 웹 서버는 `syspkgs/rpm` 의 httpd. **자바 런타임(`runtimes/jdk`)은 반입하지 않는다** — WAS 가 제공 |
| `buildtools/`·`src/` | package.sh(옵션) | 오프라인 빌드 키트 — **기본 미반입**(`WITH_BUILDTOOLS=1` 로만 채움). 현장 재빌드 요구 없음 |
| `models/weights` | package.sh | `yolox_s.onnx` |
| `models/hf-cache` | package.sh(옵션) | HF 모델 캐시(SAM2 사용 시) |
| `syspkgs/ffmpeg` | package.sh(el8 `dnf download`) | `ffmpeg`/`ffprobe` RPM + 전이 의존 (RPM Fusion, GPLv3+). **예비물** — 설치가 자동으로 쓰지 않는다(`install/install-ffmpeg.sh` 전용, 서버 A) |
| `syspkgs/ffmpeg-src` | package.sh(el8 `dnf download --source`) | **GPL 대응 소스(SRPM)** — 설치 대상 아님. GPLv3+ ffmpeg 바이너리를 매체로 반입(=재배포)하는 데 따른 소스 제공 의무 충족물(상세: `02-build-package.md` 「왜 소스(SRPM)를 넣나」) |
| `syspkgs/gpg` | package.sh(el8) | 반입 RPM GPG 공개키 — 타깃에서 `rpm --import` 후 `gpgcheck=1` 검증에 쓴다 |
| `syspkgs/rpm` | package.sh(el8 `dnf download`) | `mesa-libGL`/`libglvnd-glx`/`glib2`(서버 B, opencv 런타임 의존) + `httpd`/`policycoreutils-python-utils`(서버 A). **양쪽 서버 모두에 필요하다** |
| `syspkgs/postgresql` | package.sh(옵션, PGDG `dnf download`) | PostgreSQL 16 RPM (번들 PG, `USE_BUNDLED_POSTGRES=1` 시 설치) |
| `config/*` | (정적) | env 템플릿(`config/backend/` — `application.properties`·`backend.env`·**`was.env`**)·systemd 유닛·프록시 설정·**WAS 설정 예시(`config/was/`)** |
| `scripts/*` | (정적) | 수집/설치 스크립트 |

## 설치 후 레이아웃

README.md "설치 후 디렉토리 레이아웃" 참고. `api.war` 는 `/opt/klid/app` 에 놓이고 **거기서
WAS 배포 디렉터리로 사람이 복사**한다(설치 스크립트는 WAS 를 건드리지 않는다).
WAS 현장값(유닛명·`WAS_HOME`·로그 경로·실행 계정)은 **`/etc/klid/was.env`** 에 적는다 —
앱 설정이 아니라 **운영 런북의 셸 명령이 읽는 파일**이며, 비워 두면 런북 명령이 자리표시자로 남는다.
그 외 핵심: 앱은 `/opt/klid`, 설정은 `/etc/klid`,
영상·프레임 저장은 NAS 마운트(`STORAGE_RAW_PATH`, 기본 `/nas-storage`), 그 외 런타임 데이터는 `/var/lib/klid`, 로그는 `/var/log/klid`, 서비스 사용자는 `klid`.
