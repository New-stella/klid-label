# klid-label 온프렘(폐쇄망) 설치 패키지

학습데이터 저작도구(backend + ai-server + frontend)를 **인터넷이 없는 폐쇄망(에어갭) 레드햇 엔터프라이즈 리눅스 8.9 서버 2대**에
**Docker 없이** 설치하기 위한 자족 패키지다.

> **서버 구성 (2026-08-30 확정)** — **매체는 하나이고 설치할 때 역할만 고른다**(`--role=app|ai`).
> 역할을 주지 않으면 종전대로 한 대에 전부 설치된다(하위호환).
>
> | | **서버 A `--role=app`** | **서버 B `--role=ai`** |
> |---|---|---|
> | 올라가는 것 | httpd(정적 dist + `/api` 프록시) · 외부 WAS 에 `api.war` · (옵션)PostgreSQL | ai-server(파이썬 3.11 + 오프라인 휠 + YOLOX/SAM2 모델) |
> | systemd 유닛 | `httpd` (+`postgresql-16`) — **백엔드는 유닛이 아니다** | `klid-ai-server` |
> | httpd | 필요 | **불필요** |
> | ffmpeg | **전제조건 — 관제지원시스템 팀이 설치.** 우리는 검증만 한다 | 쓰지 않는다 |
> | GPU | 해당 없음 | **장비에 GPU 가 있으나 이번 반입은 CPU 전용**(torch CPU 휠·onnxruntime CPU) |
>
> ⚠ 서버 B 에는 **파이썬조차 없다** — 런타임을 전부 반입한다.
> ⚠ 서버 A 의 `AI_SERVER_URL` 기본값 `http://127.0.0.1:9300` 은 단일 서버 기준이다. 2대로 나누면
>   **반드시 서버 B 주소로 바꿔야 한다.**

> **배포 형상 (2026-08-30 사용자 확정, 구속 · @design DEPLOY-001 · RUNBOOK-001)**
> - **backend = 외부 WAS 에 `api.war` 반입.** 대상 장비에는 관제지원시스템 WAS 와 동일 사양인
>   **Tomcat 10.1.x + Java 17** 이 이미 돌고 있다(실측 17.0.19). 그래서 **자바 런타임을 반입하지 않는다.**
>   백엔드 프로세스의 수명주기는 WAS 가 소유한다 — systemd 가 `java -jar` 로 띄우지 않는다.
> - **frontend = Apache httpd** 가 정적 dist 서빙 + `/api` 리버스프록시(배포판 RPM).
> - **ai-server = WAR 와 무관한 별도 파이썬 프로세스**(systemd). 파이썬 런타임·오프라인 휠·모델은
>   **그대로 반입한다.** 자바를 뺐다고 함께 빠지지 않는다.
> - **오프라인 빌드 키트(buildtools/·src/)는 반입하지 않는다** — 현장 재빌드 요구가 없음을 확인했다.
>   필요하면 `WITH_BUILDTOOLS=1 ./scripts/package.sh` 로 켠다.
> - **실행 가능 jar(`klid-backend.jar`)도 반입하지 않는다**(2026-08-30) — 개발 환경 전용이라
>   설치가 배치하지 않는데 수집만 되고 있었다(83.6 MiB). 베어메탈 형상으로 갈 때만
>   `WITH_BACKEND_JAR=1 ./scripts/package.sh` 로 켠다.
>
> ⚠ 구 서술 폐기(2026-08-30) — "**Docker 없이 베어메탈 + systemd**로 설치". backend 를 systemd 유닛으로
>   기동하는 베어메탈 형상은 **정본이 아니다**(토글로만 남는다 — `INSTALL_BACKEND_SYSTEMD_UNIT=1`).

> 타깃 OS: **레드햇 엔터프라이즈 리눅스 8.9** (RHEL 8 계열, x86_64, glibc 2.28, dnf/rpm) — **2대 모두**.
> 시스템 의존성은 전부 RPM(ffmpeg 포함). 단 **ffmpeg 는 매체에 담기만 하고 자동 설치하지 않는다**
> (관제지원시스템과 공동 배치라 관제 설치본을 덮어쓰면 안 된다 — [docs/03-install.md](docs/03-install.md) 「ffmpeg — 세 경로」).
>
> ⚠ 구 서술 폐기(2026-08-28) — "Rocky Linux 9 (RHEL 9 계열, glibc 2.34) … 시스템 의존성은 RPM + 정적 ffmpeg".
>   el9 RPM 은 RHEL 8 에 설치되지 않고, 정적 ffmpeg(glibc 2.31 기반)는 glibc 2.28 에서 실행되지 않는다.

> 이 폴더(`deploy/onprem/`)를 **빌드머신에서 한 번 채운 뒤** 폐쇄망 서버로 통째로 가져가면 설치된다.

---

## 설치 요약 (서버 A 7단계 + 서버 B 3단계)

> ⚠ 구 제목 폐기(2026-08-30) — "7단계 요약"/"5단계 요약". 서버가 2대로 나뉘었다.
> WAR 반입 형상에서 **사람이 해야 하는 단계**(WAS 설정 이관 · WAR 배포 · `was.env` 기록)는
> 스크립트가 대신할 수 없다.

```
[빌드머신 — 인터넷 O. wheel·RPM 은 el8 환경에서 수집(dnf 없으면 docker 로 자동)]
  1) ./scripts/package.sh
       └ backend api.war · frontend dist · pip wheel(torch CPU) · python 런타임 · ffmpeg RPM(예비물)
         · 시스템 RPM · GPG 키 · GPL 대응 소스(SRPM) · 모델 수집
         (자바 런타임·빌드 키트는 반입하지 않는다 — 위 「배포 형상」 참고)

  2) deploy/onprem/ 폴더 전체를 USB/전송 매체로 복사 → 서버 A·B 양쪽에 같은 매체를 올린다

[서버 A — 폐쇄망, RHEL 8.9 x86_64. 프론트 + 백엔드]
  3) sudo ./scripts/install.sh --role=app
       └ (옵션)PG → api.war 배치 → frontend/httpd → DB 초기화·스키마 로드
         → ffmpeg 전제조건 검증 → 프론트 런타임 설정 게이트 → AI 서버 주소 확인
          ★ ffmpeg 가 없으면 여기서 멈춘다(관제팀 설치 요청 또는 install/install-ffmpeg.sh)
          ★ 상위 로그인 주소가 비어 있어도 멈춘다(프론트 설정 게이트 — docs/04-configuration.md)
          ★ DB 스키마 로드는 <옵트인>이다(SCHEMA_LOAD_RUN=1). 넣지 않아도 설치·기동은 성공하고
            화면·배치가 DB 를 처음 쓸 때 깨진다 — docs/03-install.md 「테이블을 만드는 주체」

  4) sudo $EDITOR /etc/klid/application.properties   # DB 비밀번호·JWT 시크릿 등 필수 입력
                                                     # (backend.env 는 베어메탈 형상용)
     sudo $EDITOR /etc/klid/was.env                  # ★ WAS 현장값(유닛명·WAS_HOME·로그 경로)
                                                     #   운영 런북의 명령이 이 값을 읽는다

  5) ★ WAS 설정 이관 — docs/10-was-settings.md 체크리스트를 끝까지 수행
       └ 예시 파일: config/was/  (README.md · setenv.sh.example · context-api.xml.example
                                   · server-connector.xml.example)
       └ 건너뛰면 기동·일반 요청은 정상이고 <대용량 업로드만 조용히 깨진다>

  6) ★ WAR 배포 — /opt/klid/app/api.war 를 WAS 배포 디렉터리로 <사람이> 복사(파일명 변경 금지)

  7) sudo systemctl enable --now httpd

[서버 B — 폐쇄망, RHEL 8.9 x86_64. ai-server]
  8) sudo ./scripts/install.sh --role=ai
  9) sudo $EDITOR /etc/klid/ai-server.env
 10) sudo systemctl enable --now klid-ai-server
     ★ 서버 A 의 AI_SERVER_URL 이 이 장비를 가리키게 할 것(기본값은 127.0.0.1 이다)
```

설치 검증:
```bash
curl -fsS http://127.0.0.1:8080/api/actuator/health/liveness   # backend(WAS 기동 후)
curl -fsS http://127.0.0.1:9300/health                          # ai-server
curl -fsS http://127.0.0.1/                                      # frontend(httpd)
```

---

## 두 가지 역할(머신)

| 머신 | 인터넷 | 역할 | 핵심 명령 |
|------|:------:|------|-----------|
| **빌드머신** | 필요 | 모든 의존성 수집·빌드 | `./scripts/package.sh` |
| **대상 서버 A** | 불필요 | 프론트·백엔드 오프라인 설치·구동 | `sudo ./scripts/install.sh --role=app` |
| **대상 서버 B** | 불필요 | ai-server 오프라인 설치·구동 | `sudo ./scripts/install.sh --role=ai` |

> 역할을 주지 않으면 `--role=all` 로 동작해 **한 대에 전부** 설치된다(기존 절차 그대로).

> ★ **pip wheel·RPM 수집은 el8 (x86_64, glibc 2.28) 환경**에서 해야 한다.
>   RPM 수집(50·55)과 node_modules populate 는 dnf/npm 이 없으면 `docker run --platform linux/amd64`
>   로 el8 컨테이너를 자동 기동한다. ⚠ `--platform` 을 빠뜨리면 Apple Silicon 에서 aarch64 RPM 을
>   받아 놓고 "성공"으로 끝나는 조용한 실패가 된다.
> manylinux wheel·RPM·glibc 정합 때문이며, macOS/Windows/ARM 에서 받은 wheel/RPM 은 동작하지 않는다.
> (jar/FE dist/런타임 tarball 은 OS 무관 — 02-build-package.md 구분표 참고.
>  ⚠ **ffmpeg 는 2026-08-28 부터 RPM 이라 OS 무관이 아니다** — 구 서술 "ffmpeg 정적은 OS 무관" 폐기)

---

## 포트맵

| 서비스 | 포트 | 경로/헬스 | 비고 |
|--------|:----:|-----------|------|
| frontend (httpd) | 80 | `/` (SPA), `/api/*` → backend | 정적 dist 서빙 + 리버스프록시 |
| backend (Spring) | 8080 | `/api`, liveness `/api/actuator/health/liveness` | 외부 WAS 커넥터. 컨텍스트 `/api` 는 **WAR 파일명**이 정한다 |
| ai-server (FastAPI) | 9300 | `/health` | CPU 추론(onnxruntime/torch CPU) |
| PostgreSQL | 5432 | control(klid_system) + portal | 외부/별도 준비 |

---

## 설치 후 디렉토리 레이아웃

```
/opt/klid/runtime/python               ai-server 런타임(웹 서버는 배포판 httpd, ffmpeg 는 /usr/bin,
                                       자바는 외부 WAS 제공 — 셋 다 /opt/klid/runtime 아래에 없다)
/opt/klid/app/api.war                  backend 반입 정본(여기서 WAS 배포 디렉터리로 복사)
/opt/klid/ai/{venv,app,weights,.hf-cache}  ai-server
/opt/klid/web/dist                     frontend 정적 자산(httpd 문서 루트)
/etc/klid/application.properties       WAR 반입 형상에서 WAS 가 읽는 앱 설정 (chmod 640, root:klid)
/etc/klid/{backend.env,ai-server.env}  환경설정 (chmod 640, root:klid)
                                       backend.env 는 베어메탈 형상용 — WAS 는 읽지 않는다
/etc/klid/was.env                      WAS 현장값(유닛명·WAS_HOME·로그 경로·실행 계정) — chmod 644.
                                       ★ 앱 설정이 아니다. 운영 런북의 셸 명령이 읽는 파일이며
                                         비밀값을 넣지 않는다(운영자가 읽고 고쳐야 한다)
/nas-storage/...                       영상·프레임 저장 (NAS 마운트, STORAGE_RAW_PATH)
/var/lib/klid                          런타임 데이터(저장소 외)
/var/log/klid                          로그
서비스 사용자: klid (system, nologin)
```

---

## 문서 목차

| 문서 | 내용 |
|------|------|
| [docs/00-overview.md](docs/00-overview.md) | 아키텍처·데이터 흐름·포트맵·디렉토리 의미 |
| [docs/01-prerequisites.md](docs/01-prerequisites.md) | 빌드머신/대상 서버 요구사항 |
| [docs/02-build-package.md](docs/02-build-package.md) | [빌드머신] 수집 절차 + **복사 대상 명세표** |
| [docs/03-install.md](docs/03-install.md) | [대상 서버] 오프라인 설치 절차 |
| [docs/04-configuration.md](docs/04-configuration.md) | `.env` 전체표 · DB 준비 · 외부 연동 토글 |
| [docs/05-run-verify.md](docs/05-run-verify.md) | 서비스 기동 순서 · 헬스체크 · 스모크 |
| [docs/06-troubleshooting.md](docs/06-troubleshooting.md) | 오프라인 설치 흔한 실패와 해결 |
| [docs/07-uninstall-rollback.md](docs/07-uninstall-rollback.md) | 제거/롤백 |
| [docs/08-build-from-source.md](docs/08-build-from-source.md) | [대상 서버] 인터넷 없이 **소스에서 재빌드**(빌드 키트 — **기본 미반입**) + 사전빌드 vs 소스빌드 선택 |
| [docs/10-was-settings.md](docs/10-was-settings.md) | ★ **WAS 설정 이관** — WAR 반입 형상에서 반드시 옮겨야 하는 것(건너뛰면 대용량 업로드만 조용히 깨진다) |
| [docs/09-operations-runbook.md](docs/09-operations-runbook.md) | **운영 런북(관리자 매뉴얼)** — 프로세스 상태 확인·장애 유형별 조치·로그·정기 점검 |
| [docs/11-gpu-migration.md](docs/11-gpu-migration.md) | **GPU 전환 가이드** — 이번 반입은 CPU 전용. GPU 로 옮길 때 바꿔야 할 조달·설정(코드 변경 아님) |
| [docs/10-backup-dr.md](docs/10-backup-dr.md) | **백업 및 재해복구(DR)** — DB/설정/저장소 백업 대상·주기·복원·전체 서버 DR·RPO/RTO |

---

## 디렉토리 구조

```
deploy/onprem/
├── README.md  VERSION  .gitignore
├── docs/        00~11 단계별 가이드 13편 (10 이 둘 — was-settings · backup-dr, 11=GPU 전환)
├── db/schema.sql                         # ★ 전체 스키마 SQL(온프렘은 Flyway 미사용 — 1회 로드)
├── scripts/
│   ├── lib/{common.sh, versions.sh}
│   ├── lib/{licenses.sh, backend_licenses.py}   # 라이선스 고지 수집 공용
│   ├── package.sh + package/{10..70}     # [빌드머신] 수집(55=PG16, 60=오프라인 빌드 키트 — 기본 제외,
│   │                                     #  65=(L)GPL 대응 소스, 70=고지 집합 생성)
│   │                                     #  목록·순서의 단일 진실원은 package/steps.sh
│   ├── package-step.sh                   # [빌드머신] 단계별 수집(list / <번호> / from <번호>)
│   ├── install.sh + install/{10..21}     # [대상 서버] 설치(10=번들 PG16 옵션, 16·17=스키마 로드,
│   │                                     #  19=ffmpeg 전제조건 검증, 20=프론트 설정 게이트,
│   │                                     #  21=AI 서버 주소 확인)
│   ├── install-step.sh                   # [대상 서버] 단계별 설치(list / <번호>)
│   ├── install/install-ffmpeg.sh         # [대상 서버 A] ★ 수동 전용 — install.sh 가 호출하지 않는다
│   ├── install/render-frontend-config.sh # [대상 서버 A] 프론트 런타임 설정(klid-config.js) 생성
│   ├── install/build-from-source.sh      # [대상 서버] 소스 오프라인 재빌드
│   ├── gen-schema-sql.sh                 # [빌드머신] db/schema.sql 재생성
│   └── uninstall.sh
├── config/
│   ├── backend/{application.properties.template, env.template, was.env.template}
│   ├── ai-server/env.template
│   ├── frontend/{frontend.env.template, httpd-klid.conf.template, nginx.conf.template}
│   ├── was/                              # ★ WAS 설정 예시(setenv.sh · context.xml · server.xml 커넥터)
│   └── systemd/{klid-backend,klid-ai-server}.service   # 웹서버는 배포판 httpd.service
├── artifacts/{backend,frontend,ai-server}/   # package.sh 가 채움(backend=api.war 반입 정본.
│                                             #  klid-backend.jar 는 개발 전용이라 반입 대상이
│                                             #  아니고 기본 미수집 — WITH_BACKEND_JAR=1 로만)
├── licenses/                                 # ★ 제3자 라이선스 고지 — 대부분 자동 생성.
│   ├── README.md  NOTICE.header.txt          #   사람이 쓰는 것(git 추적)
│   ├── manual/{OVERRIDES.tsv, LGPL-SOURCES.tsv, LGPL-SOURCE-OFFER.md, fonts/, texts/}
│   └── (backend|frontend|python-*|copyleft-sources)/  # 수집 시 채워짐(git 제외)
├── runtimes/python/                          # 자바 런타임은 반입하지 않는다(WAS 가 제공 —
│                                             #  runtimes/jdk/ 는 빈 골격만 남아 있다.
│                                             #  runtimes/caddy/ 는 2026-08-30 삭제 — httpd 전환으로 폐기)
├── buildtools/{jdk,node,gradle,gradle-home}/ # 오프라인 빌드 키트 — **기본 미반입**(WITH_BUILDTOOLS=1)
├── src/{backend,frontend,ai-server}/         # 빌드용 소스(60단계가 채움 — 기본 미반입)
├── vendor/{wheels,sam2}/
├── models/{weights,hf-cache}/
└── syspkgs/{rpm,gpg,ffmpeg,ffmpeg-src,postgresql}/
                                              # rpm=el8 시스템 RPM(서버 A·B 공통) · gpg=반입 GPG 공개키
                                              # ffmpeg=예비물(자동 설치 안 함) · ffmpeg-src=GPL 대응 소스(SRPM)
                                              # postgresql=(옵션)PG16 RPM
```
