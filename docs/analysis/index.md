# CVAT 분석 문서 목차

> 마지막 업데이트: 2026-04-17
> 분석 대상 경로: `/Users/ck/Documents/workspace/klid-ai-label/cvat/`

## 빠른 룩업 테이블

| 필요한 것 | 참조 문서 | 핵심 섹션 |
|-----------|-----------|-----------|
| 어노테이션 DB 스키마 | [annotation-model.md](annotation-model.md) | #db-schema |
| BBOX/POLYGON 좌표 저장 방식 | [annotation-model.md](annotation-model.md) | #coordinate-format |
| 라벨 타입(RECTANGLE/POLYGON/MASK…) | [label-system.md](label-system.md) | #label-types |
| 속성(attribute) 타입 | [label-system.md](label-system.md) | #attribute-types |
| 영상 업로드 → 프레임 추출 흐름 | [video-processing.md](video-processing.md) | #pipeline |
| FFmpeg/PyAV 사용 방법 | [video-processing.md](video-processing.md) | #ffmpeg-usage |
| Task→Job 상태 전이 | [workflow.md](workflow.md) | #state-transition |
| 라벨러/검수자 구조 | [workflow.md](workflow.md) | #role-structure |
| 캔버스 드로잉 도구 구현 | [canvas-drawing.md](canvas-drawing.md) | #drawing-handlers |
| MASK 브러시 도구 | [canvas-drawing.md](canvas-drawing.md) | #mask-drawing |
| YOLO 오토라벨링 연동 | [auto-labeling.md](auto-labeling.md) | #yolo-integration |
| SAM/Segment Anything 연동 | [auto-labeling.md](auto-labeling.md) | #sam-integration |
| Nuclio 서버리스 함수 등록 | [auto-labeling.md](auto-labeling.md) | #nuclio-architecture |
| 비디오 객체 추적(Track) | [tracking-interpolation.md](tracking-interpolation.md) | #track-model |
| 키프레임 보간 알고리즘 | [tracking-interpolation.md](tracking-interpolation.md) | #interpolation-algorithm |
| YOLO 포맷 내보내기 | [export-import.md](export-import.md) | #yolo-format |
| COCO JSON 포맷 | [export-import.md](export-import.md) | #coco-format |
| REST API 엔드포인트 목록 | [api-reference.md](api-reference.md) | #endpoints |
| 공통 응답 형식 | [api-reference.md](api-reference.md) | #response-format |
| TUS 대용량 업로드 (재개 가능) | [video-processing.md](video-processing.md) | #tus |
| 비동기 작업 상태 조회 | [async-jobs.md](async-jobs.md) | (전체) |
| 웹훅/이벤트 시스템 | [webhooks-events.md](webhooks-events.md) | (전체) |
| Canvas 외부 API | [canvas-drawing.md](canvas-drawing.md) | #canvas-외부-인터페이스-api |
| 어노테이션 품질 점수 계산 | [quality-review.md](quality-review.md) | #quality-score |
| Ground Truth Job | [quality-review.md](quality-review.md) | #gt-job |
| 역할(Role) 목록 및 권한 | [auth-rbac.md](auth-rbac.md) | #roles |
| OPA(Open Policy Agent) 권한 검사 | [auth-rbac.md](auth-rbac.md) | #opa |
| 전체 아키텍처 개요 | [overview.md](overview.md) | #architecture |
| Docker 서비스 구성 | [overview.md](overview.md) | #services |
| Docker 없이 배포 (통합 가이드) | [deployment-without-docker.md](deployment-without-docker.md) | (전체) |

## 문서 목록

| 문서 | 설명 |
|------|------|
| [overview.md](overview.md) | 전체 아키텍처, 기술 스택, 서비스 구성 |
| [annotation-model.md](annotation-model.md) | DB 스키마, 좌표 형식, API 형식 |
| [label-system.md](label-system.md) | 라벨 타입, 속성 구조, 프리셋 |
| [video-processing.md](video-processing.md) | 프레임 추출, 청크 생성, PyAV |
| [workflow.md](workflow.md) | Task→Job 상태, 라벨러 할당, 검수 |
| [canvas-drawing.md](canvas-drawing.md) | 캔버스 드로잉 구현 (TypeScript) |
| [auto-labeling.md](auto-labeling.md) | Nuclio/Lambda 기반 AI 모델 통합 |
| [tracking-interpolation.md](tracking-interpolation.md) | 트랙 구조, 키프레임, 보간 알고리즘 |
| [export-import.md](export-import.md) | YOLO, COCO, CVAT XML 포맷 변환 |
| [api-reference.md](api-reference.md) | REST API 엔드포인트, 응답 패턴 |
| [async-jobs.md](async-jobs.md) | 비동기 작업 상태 추적, RQ Job 패턴 |
| [webhooks-events.md](webhooks-events.md) | 웹훅 모델, 이벤트 타입, ClickHouse 로깅 |
| [quality-review.md](quality-review.md) | GT Job, 품질 점수, 충돌 유형 |
| [auth-rbac.md](auth-rbac.md) | 역할 목록, OPA 권한 검사 |
| [deployment-without-docker.md](deployment-without-docker.md) | Docker 없이 CVAT 전체 배포 통합 가이드 |

## "Docker 없이 운영" 빠른 참조

| 모듈 | Docker 의존도 | 네이티브 대응 문서 |
|------|:------------:|------------------|
| PostgreSQL (메인 DB) | 낮음 | [overview.md#docker-미사용-대응](overview.md) |
| Redis (RQ 큐) | 낮음 | [async-jobs.md#docker-미사용-대응](async-jobs.md) |
| Kvrocks (청크 캐시) | 낮음 | [async-jobs.md#docker-미사용-대응](async-jobs.md) |
| Django backend | 낮음 | [overview.md#docker-미사용-대응](overview.md) |
| RQ Worker (8종) | 낮음 | [workflow.md#docker-미사용-대응](workflow.md), [async-jobs.md#docker-미사용-대응](async-jobs.md) |
| cvat-ui (정적 SPA) | 낮음 | [api-reference.md#docker-미사용-대응](api-reference.md) |
| OPA (권한 엔진) | 낮음 | [auth-rbac.md#docker-미사용-대응](auth-rbac.md) |
| ClickHouse / Vector / Grafana | 중간 | [webhooks-events.md#docker-미사용-대응](webhooks-events.md) |
| PyAV / FFmpeg | 낮음 (시스템 패키지 필요) | [video-processing.md#docker-미사용-대응](video-processing.md) |
| **Traefik** | **높음 (대체 필수)** | [api-reference.md#docker-미사용-대응](api-reference.md) — Nginx/Caddy 대체 |
| **Nuclio** | **높음 (대체 필수)** | [auto-labeling.md#docker-미사용-대응](auto-labeling.md) — 모델 직접 서빙 |

통합 배포 가이드는 [deployment-without-docker.md](deployment-without-docker.md) 참조.

## 독립 포팅 카탈로그

각 모듈을 단독으로 떼어 다른 프로젝트로 가져가는 가이드입니다.
난이도/의존성/추출 가치 기준으로 정리했습니다.

| # | 모듈 | 가치 | 난이도 | 단독 가능 | 가이드 |
|---|------|:---:|:------:|:--------:|------|
| 1 | 트랙 보간 알고리즘 | 매우 높음 | 하 | ✅ | [01-track-interpolation.md](portable-modules/01-track-interpolation.md) |
| 2 | MASK ↔ RLE ↔ Polygon | 매우 높음 | 하 | ✅ | [02-mask-rle-conversion.md](portable-modules/02-mask-rle-conversion.md) |
| 3 | TUS 재개 가능 업로드 | 높음 | 하 | ✅ | [03-tus-upload.md](portable-modules/03-tus-upload.md) |
| 4 | manifest.jsonl 포맷 | 높음 | 하 | ✅ | [04-manifest-jsonl.md](portable-modules/04-manifest-jsonl.md) |
| 5 | AI 함수 핸들러 템플릿 | 매우 높음 | 중 | ✅ | [05-nuclio-function-template.md](portable-modules/05-nuclio-function-template.md) |
| 6 | 좌표 변환/회전 유틸 | 중간 | 하 | ✅ | [06-coordinate-conversion.md](portable-modules/06-coordinate-conversion.md) |
| 7 | YOLO/COCO 변환 | 높음 | 중 | ✅ | [07-export-format-yolo-coco.md](portable-modules/07-export-format-yolo-coco.md) |
| 8 | RQ Worker 분리 운영 | 중간 | 하 | ✅ | [08-rq-worker-pattern.md](portable-modules/08-rq-worker-pattern.md) |
| 9 | 품질 충돌 감지 | 높음 | 중 | ⚠️ (GT Job 의존) | [09-quality-conflict-detection.md](portable-modules/09-quality-conflict-detection.md) |

### 권장 추출 순서

1. **단독 알고리즘부터** (의존성 없음) — 트랙 보간, MASK 변환, 좌표 변환
2. **인프라 패턴** — TUS 업로드, RQ 워커
3. **데이터 포맷** — manifest, YOLO/COCO 변환
4. **AI 통합** — Nuclio 핸들러 템플릿
5. **고도화 기능** — 품질 충돌 감지 (GT Job 인프라 먼저 필요)

### 관련 분석 문서

각 portable-module은 단독 가이드지만, 더 깊은 도메인 컨텍스트가 필요하면 다음 분석 문서를 참고하세요:

| Portable Module | 관련 분석 문서 |
|----------------|--------------|
| 01 트랙 보간 | [tracking-interpolation.md](tracking-interpolation.md) |
| 02 MASK RLE | [annotation-model.md](annotation-model.md), [canvas-drawing.md](canvas-drawing.md) |
| 03 TUS 업로드 | [video-processing.md](video-processing.md) |
| 04 manifest.jsonl | [video-processing.md](video-processing.md) |
| 05 AI 함수 핸들러 | [auto-labeling.md](auto-labeling.md) |
| 06 좌표 변환 | [canvas-drawing.md](canvas-drawing.md) |
| 07 YOLO/COCO | [export-import.md](export-import.md) |
| 08 RQ Worker | [async-jobs.md](async-jobs.md), [workflow.md](workflow.md) |
| 09 품질 충돌 감지 | [quality-review.md](quality-review.md) |
