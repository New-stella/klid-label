# CVAT 전체 아키텍처 개요

## 개요
CVAT(Computer Vision Annotation Tool)는 Intel이 개발하고 CVAT.ai가 운영하는 오픈소스 어노테이션 플랫폼이다. Django 기반 백엔드와 React 프론트엔드로 구성된 모노레포 구조를 가지며, 이미지/비디오 데이터의 레이블링, 검수, 내보내기를 지원한다.

## 기술 스택

### 백엔드
- **프레임워크**: Django 4.x + Django REST Framework (DRF) + drf-spectacular (OpenAPI)
- **DB**: PostgreSQL 15 (Django ORM)
- **캐시**: Redis 7.2 (인메모리, django-redis)
- **온디스크 캐시**: Apache Kvrocks 2.12 (Redis 프로토콜 호환, 영구 저장)
- **비동기 작업**: django-rq (Redis Queue) — import/export/annotation/chunks/webhooks/quality_reports 큐 분리
- **이벤트 로깅**: ClickHouse + Vector + Grafana
- **권한 관리**: OPA (Open Policy Agent) + Rego 정책

### 프론트엔드
- **프레임워크**: React + TypeScript
- **상태 관리**: Redux (actions/reducers 패턴)
- **캔버스**: `cvat-canvas` 패키지 (SVG.js 기반 2D), `cvat-canvas3d` (Three.js 기반 3D)
- **핵심 로직**: `cvat-core` (비즈니스 로직, API 클라이언트)
- **데이터 처리**: `cvat-data` (미디어 청크 처리)

### AI 오토라벨링
- **런타임**: Nuclio (서버리스 함수 플랫폼)
- **모델 예시**: YOLOv7(ONNX), Faster R-CNN(TF), Mask R-CNN(OpenVINO), HRNet(PyTorch), SAM 등

### 인프라
- **프록시**: Traefik v3.6 (리버스 프록시, 라우팅)
- **분석**: Grafana + ClickHouse (어노테이션 이벤트 대시보드)

## 서비스 구성도 (docker-compose 기반)

```
[Traefik :8080]
  ├── /api/* → cvat_server (Django, :8080)
  └── / → cvat_ui (Nginx, :8000)

[cvat_server]  ←→  [cvat_db: PostgreSQL]
     ↓ RQ        ←→  [cvat_redis_inmem: Redis 7.2]
[Workers]        ←→  [cvat_redis_ondisk: Kvrocks]
  ├── cvat_worker_import     (큐: import)
  ├── cvat_worker_export     (큐: export)
  ├── cvat_worker_annotation (큐: annotation)
  ├── cvat_worker_chunks     (큐: chunks)
  ├── cvat_worker_webhooks   (큐: webhooks)
  ├── cvat_worker_quality_reports (큐: quality_reports)
  ├── cvat_worker_utils      (큐: notifications, cleaning)
  └── cvat_worker_consensus  (큐: consensus)

[cvat_opa]  ←  OPA 권한 정책 서버 (:8181)

[Nuclio] ←→ cvat_server  (AI 함수 게이트웨이)

[ClickHouse] ← [Vector(log shipper)] ← cvat_server
[Grafana] → ClickHouse (대시보드)
```

## 패키지 구조

### 백엔드 앱 (`cvat/apps/`)
| 앱 | 역할 |
|----|------|
| `engine` | 핵심 도메인 (Task, Job, Annotation, Label, User 등) |
| `dataset_manager` | 어노테이션 데이터 관리, Export/Import 포맷 변환 |
| `iam` | 인증/인가 (로그인, 회원가입, OPA 연동) |
| `organizations` | 조직/멤버십/초대 관리 |
| `lambda_manager` | Nuclio 서버리스 AI 함수 게이트웨이 |
| `quality_control` | 품질 리포트, 충돌 감지, Ground Truth |
| `webhooks` | 외부 웹훅 발송 |
| `events` | ClickHouse 기반 이벤트 로깅 |
| `redis_handler` | Redis 기반 요청 상태 관리 |
| `consensus` | 합의 어노테이션 (복수 작업자 결과 통합) |
| `access_tokens` | 개인 액세스 토큰 |

### 프론트엔드 패키지 (`cvat-*/`)
| 패키지 | 역할 |
|--------|------|
| `cvat-ui` | 메인 React 앱 |
| `cvat-canvas` | 2D 어노테이션 캔버스 (SVG.js) |
| `cvat-canvas3d` | 3D 포인트 클라우드 캔버스 |
| `cvat-core` | API 클라이언트 + 비즈니스 로직 |
| `cvat-data` | 미디어 청크 로딩/캐싱 |
| `cvat-sdk` | Python SDK |
| `cvat-cli` | CLI 도구 |

## 코드베이스 규모
| 파일 | 라인 수 |
|------|---------|
| `cvat/apps/engine/models.py` | 1,581 |
| `cvat/apps/engine/views.py` | ~2,900+ |
| `cvat/apps/engine/serializers.py` | ~4,000+ |
| `cvat/apps/dataset_manager/annotation.py` | ~1,000+ |
| `cvat/apps/quality_control/quality_reports.py` | ~1,500+ |
| `cvat-canvas/src/typescript/drawHandler.ts` | ~1,200+ |
| `cvat-canvas/src/typescript/canvasModel.ts` | ~400+ |
| Export 포맷 수 (`dataset_manager/formats/`) | 24종 |
| Serverless AI 모델 예시 | pytorch/onnx/openvino/tensorflow 4개 런타임 |

## 핵심 의사결정

- **모노레포 + Docker Compose 기본**: 모든 컴포넌트를 docker-compose.yml로 일괄 배포. Helm Chart도 별도 제공.
- **Django + DRF**: REST API는 DRF로 일관, OpenAPI는 drf-spectacular로 자동 생성.
- **PostgreSQL 전용**: Django의 PostgreSQL 전용 기능(JSONField, ArrayField 일부 등)을 사용하므로 다른 DB로의 이식은 비자명.
- **워커 큐 8개 분리**: import/export/chunks/annotation/webhooks/quality_reports/consensus/utils로 큐를 분리하여 작업 격리.
- **이중 Redis**: 인메모리 Redis(RQ 큐) + 디스크 영구 저장 Kvrocks(청크 캐시) 두 인스턴스 사용.
- **OPA 분리**: 권한 정책은 Rego DSL로 분리 운영. CVAT 코드와 정책 변경 주기 분리 가능.
- **Nuclio 서버리스**: AI 모델은 Docker 컨테이너로 격리 → CVAT 서버는 HTTP 게이트웨이만 담당.
- **ClickHouse + Vector + Grafana**: 분석/모니터링 스택 별도 운영. `CVAT_ANALYTICS=0`으로 비활성화 가능.

## Docker 미사용 대응

### 12개 서비스 네이티브 설치 가능 여부

| 서비스 | 네이티브 가능 | 비고 |
|--------|:------------:|------|
| cvat_db (PostgreSQL 15) | O | apt/dnf/brew |
| cvat_redis_inmem (Redis 7.2) | O | apt/dnf/brew |
| cvat_redis_ondisk (Kvrocks 2.12) | O | 공식 바이너리 또는 빌드 |
| cvat_server (Django + uWSGI) | O | Python venv + uvicorn/gunicorn |
| cvat_worker_* (8종) | O | systemd unit 또는 supervisord |
| cvat_ui (React + nginx) | O | npm build → nginx 정적 호스팅 |
| **traefik** | **X** | Docker socket 의존 → Nginx/Caddy 대체 필수 |
| cvat_opa (OPA 1.12) | O | Go 단일 바이너리 |
| cvat_clickhouse | O | apt/dnf 공식 저장소 |
| cvat_vector | O | curl 설치 스크립트 또는 apt |
| cvat_grafana | O | apt/dnf 공식 저장소 |
| **nuclio** (옵션) | **X** | Docker socket 의존 → 모델 직접 서빙 대체 |

### 의존성 트리

```
cvat_server
  ├── cvat_db (PostgreSQL)
  ├── cvat_redis_inmem (RQ 큐)
  ├── cvat_redis_ondisk (청크 캐시)
  ├── cvat_clickhouse (CVAT_ANALYTICS=1 시)
  └── cvat_opa (권한 검사)

cvat_worker_* (8개)
  ├── cvat_redis_inmem
  ├── cvat_redis_ondisk
  ├── cvat_db
  └── cvat_clickhouse (export 워커만)

cvat_ui
  └── cvat_server (API 호출)

traefik (필수 X 시 nginx/caddy)
  ├── cvat_server (라우팅)
  ├── cvat_ui (라우팅)
  └── cvat_grafana (라우팅, 분석 활성화 시)

cvat_vector
  └── cvat_clickhouse

cvat_grafana
  └── cvat_clickhouse

nuclio (옵션, 오토라벨링 사용 시)
  └── Docker 데몬 (함수 컨테이너 관리)
```

### 결론: Docker 없이 운영 가능한가?

**가능하다.** 단 다음 두 가지는 대체 구성이 필요하다:

1. **Traefik → Nginx/Caddy**: 정적 라우팅 설정으로 대체. Docker 라벨 자동 검색 기능을 잃지만, 단일 호스트에서는 정적 설정으로 충분.
2. **Nuclio Dashboard → 모델 직접 서빙**: function.yaml의 핸들러 코드를 Flask/FastAPI 라우터로 변환하거나 backend 프로세스 내 임베딩.

다른 모든 컴포넌트는 OS 패키지 매니저 또는 공식 바이너리로 네이티브 설치 가능하며, systemd unit으로 관리할 수 있다.

자세한 통합 배포는 [deployment-without-docker.md](deployment-without-docker.md) 참고.
