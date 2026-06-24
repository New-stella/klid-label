# 00. 개요 — 아키텍처 / 데이터 흐름 / 레이아웃

## 구성 요소

klid-label 은 모노레포의 3개 런타임으로 구성된다. 폐쇄망 단일 서버에 모두 베어메탈 설치한다.

```
            ┌──────────────────── 단일 리눅스 서버 (x86_64, CPU) ────────────────────┐
   사용자 ──┤  Caddy :80  ──/api/*──►  Spring Boot :8080  ──HTTP──►  FastAPI :9300    │
  (브라우저)│  (정적 dist)            (backend, Java17)            (ai-server, Py3.11)│
            │       │                       │                                        │
            │       └─ SPA(dist)            ├─► PostgreSQL :5432 (control + portal)   │
            │                               └─► FFmpeg / 저장소(/var/lib/klid)        │
            └────────────────────────────────────────────────────────────────────────┘
```

- **frontend (Caddy)**: React+Vite 정적 빌드(`dist`)를 80포트로 서빙하고, `/api/*` 를 backend 로 리버스프록시.
- **backend (Spring Boot)**: 인증/DB/오케스트레이션/라벨 CRUD/배치. context-path `/api`, 포트 8080.
  control DB(klid_system) + portal DB 2개 DataSource. Flyway 로 LS_* 테이블 자동 생성/검증.
- **ai-server (FastAPI)**: YOLOX(onnxruntime CPU)/SAM2/RT-DETR 추론만. 상태·인증·DB 없음. 포트 9300.

## 데이터 흐름(요약)

1. 관제 학습용 설정 영상을 주기 배치가 픽업 → `LS_DATA_RAW` 적재(PENDING).
2. 적재 직후 선두 **비식별** → 마킹 → VLM 시계열(콜백) → FFmpeg 프레임추출 → YOLO/SAM2 오토라벨링.
3. 라벨링/검수 → 검수 승인 시 라벨 스냅샷(`LS_LABEL_VERSION`) + (옵션)관제 outbound 통지.

> 외부 시스템(비식별 KPST / 관제통지 / VLM / 증강)은 토글로 on/off 한다. 온프렘에 외부가 없으면
> 04-configuration.md "외부 연동 경로 vs 자족 경로"를 따른다.

## 포트맵

| 서비스 | 포트 | 헬스 |
|--------|:----:|------|
| frontend(Caddy) | 80 | `GET /` |
| backend | 8080 | `GET /api/actuator/health/liveness` |
| ai-server | 9300 | `GET /health` |
| PostgreSQL | 5432 | — |

## 패키지 디렉토리 의미

| 디렉토리 | 채우는 주체 | 내용 |
|----------|-------------|------|
| `artifacts/backend` | package.sh | `klid-backend.jar` |
| `artifacts/frontend/dist` | package.sh | 정적 빌드 결과 |
| `artifacts/ai-server` | package.sh | `app/` 소스 + `requirements.txt` |
| `vendor/wheels` | package.sh | 모든 pip 의존성 wheel(torch CPU 포함) |
| `vendor/sam2/sam2-src` | package.sh | sam2 git 소스(VCS 의존성 오프라인화) |
| `runtimes/{jdk,python,caddy}` | package.sh | 대상 서버 런타임 바이너리(tar.gz) |
| `models/weights` | package.sh | `yolox_s.onnx` |
| `models/hf-cache` | package.sh(옵션) | HF 모델 캐시(rtdetr/sam2 사용 시) |
| `syspkgs/deb` | package.sh(데비안) | ffmpeg/libgl1/libglib2.0-0/curl `.deb` |
| `config/*` | (정적) | env 템플릿·systemd 유닛·프록시 설정 |
| `scripts/*` | (정적) | 수집/설치 스크립트 |

## 설치 후 레이아웃

README.md "설치 후 디렉토리 레이아웃" 참고. 핵심: 앱은 `/opt/klid`, 설정은 `/etc/klid`,
데이터는 `/var/lib/klid`, 로그는 `/var/log/klid`, 서비스 사용자는 `klid`.
