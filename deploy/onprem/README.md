# klid-label 온프렘(폐쇄망) 설치 패키지

학습데이터 저작도구(backend + ai-server + frontend)를 **인터넷이 없는 폐쇄망(에어갭) Rocky Linux 9 서버**에
**Docker 없이 베어메탈 + systemd**로 설치하기 위한 자족 패키지다.

> 타깃 OS: **Rocky Linux 9** (RHEL 9 계열, x86_64, glibc 2.34, dnf/rpm). 시스템 의존성은 RPM + 정적 ffmpeg.

> 이 폴더(`deploy/onprem/`)를 **빌드머신에서 한 번 채운 뒤** 폐쇄망 서버로 통째로 가져가면 설치된다.

---

## 5단계 요약

```
[빌드머신 — 인터넷 O. wheel·RPM 은 rockylinux:9 환경에서 수집]
  1) ./scripts/package.sh
       └ backend jar · frontend dist · pip wheel(torch CPU) · 런타임 · ffmpeg 정적 · RPM · 모델 수집

  2) deploy/onprem/ 폴더 전체를 USB/전송 매체로 복사

[대상 서버 — 폐쇄망, Rocky Linux 9 x86_64 CPU]
  3) sudo ./scripts/install.sh
       └ 런타임 설치 → backend → ai-server → frontend → (옵션)DB 초기화

  4) sudo $EDITOR /etc/klid/backend.env      # DB 비밀번호·JWT 시크릿 등 필수 입력
     sudo $EDITOR /etc/klid/ai-server.env

  5) sudo systemctl enable --now klid-ai-server klid-backend klid-frontend
```

설치 검증:
```bash
curl -fsS http://127.0.0.1:8080/api/actuator/health/liveness   # backend
curl -fsS http://127.0.0.1:9300/health                          # ai-server
curl -fsS http://127.0.0.1/                                      # frontend(Caddy)
```

---

## 두 가지 역할(머신)

| 머신 | 인터넷 | 역할 | 핵심 명령 |
|------|:------:|------|-----------|
| **빌드머신** | 필요 | 모든 의존성 수집·빌드 | `./scripts/package.sh` |
| **대상 서버** | 불필요 | 오프라인 설치·구동 | `sudo ./scripts/install.sh` |

> ★ **pip wheel·RPM 수집은 Rocky Linux 9 (x86_64, glibc 2.34) 환경**에서 해야 한다.
> manylinux wheel·RPM·glibc 정합 때문이며, macOS/Windows/ARM 에서 받은 wheel/RPM 은 동작하지 않는다.
> (jar/FE dist/런타임 tarball/ffmpeg 정적은 OS 무관 — 02-build-package.md 구분표 참고)

---

## 포트맵

| 서비스 | 포트 | 경로/헬스 | 비고 |
|--------|:----:|-----------|------|
| frontend (Caddy) | 80 | `/` (SPA), `/api/*` → backend | 정적 dist 서빙 + 리버스프록시 |
| backend (Spring) | 8080 | `/api`, liveness `/api/actuator/health/liveness` | context-path `/api` |
| ai-server (FastAPI) | 9300 | `/health` | CPU 추론(onnxruntime/torch CPU) |
| PostgreSQL | 5432 | control(klid_system) + portal | 외부/별도 준비 |

---

## 설치 후 디렉토리 레이아웃

```
/opt/klid/runtime/{jre,python,caddy,ffmpeg}   번들 런타임(ffmpeg 정적 포함)
/opt/klid/app/klid-backend.jar         backend 실행 jar
/opt/klid/ai/{venv,app,weights,.hf-cache}  ai-server
/opt/klid/web/{dist,Caddyfile}         frontend
/etc/klid/{backend.env,ai-server.env}  환경설정 (chmod 640, root:klid)
/var/lib/klid/storage/{raw,deidentified}  영상 저장
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
| [docs/08-build-from-source.md](docs/08-build-from-source.md) | [대상 서버] 인터넷 없이 **소스에서 재빌드**(빌드 키트) + 사전빌드 vs 소스빌드 선택 |

---

## 디렉토리 구조

```
deploy/onprem/
├── README.md  VERSION  .gitignore
├── docs/        00~07 단계별 가이드
├── scripts/
│   ├── lib/{common.sh, versions.sh}
│   ├── package.sh + package/{10..60}     # [빌드머신] 수집(60=오프라인 빌드 키트)
│   ├── install.sh + install/{11..15}     # [대상 서버] 설치
│   ├── install/build-from-source.sh      # [대상 서버] 소스 오프라인 재빌드
│   └── uninstall.sh
├── config/
│   ├── backend/env.template
│   ├── ai-server/env.template
│   ├── frontend/{Caddyfile.template, nginx.conf.template}
│   └── systemd/{klid-backend,klid-ai-server,klid-frontend}.service
├── artifacts/{backend,frontend,ai-server}/   # package.sh 가 채움(사전 빌드)
├── runtimes/{jdk,python,caddy}/
├── buildtools/{jdk,node,gradle,gradle-home}/ # 오프라인 빌드 키트(소스 재빌드용)
├── src/{backend,frontend,ai-server}/         # 빌드용 소스(60단계가 채움)
├── vendor/{wheels,sam2}/
├── models/{weights,hf-cache}/
└── syspkgs/{rpm,ffmpeg}/                 # Rocky 9 RPM + ffmpeg 정적 tarball
```
