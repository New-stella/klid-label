# 01. 시스템 개요

> 출처: 통합설계서 §1, 아키텍처설계서 §1·요약, 코드규격서, 컴포넌트설계서
> 관련: [02 아키텍처](02-architecture.md) · [06 파이프라인](06-video-frame-pipeline.md)

## 1.1 목적

학습 저작도구(Authoring Tool)는 지자체 CCTV 영상에서 추출한 프레임 이미지를 기반으로 **AI 학습용 어노테이션 데이터를 제작·관리·검수**하는 핵심 서브시스템이다. 작업자가 이미지에 라벨을 부착하고 검수자가 품질을 검증하는 전 과정을 관리하며, 최종적으로 AI 모델 학습에 투입 가능한 정제 데이터셋(침수·화재·교통 탐지용)을 생성한다.

- **발주사**: KLID 한국지역정보개발원 / **수행사**: SweetK
- **대상 AI**: 침수 탐지(UperNet + ViT-Adapter) 등

## 1.2 전체 시스템 위상

```
지자체 통합관제센터 (서울 관악구 / 경기 안양시 / 강원 원주시 / 제주 등)
        ↓ 이벤트 + 클립영상
지자체 중계서버
        ↓
Ingest Server (수신 · 검증 · 초기 처리)
        ↓
control-hub 관제지원시스템
    ├─ Clip NAS (클립영상 저장)
    ├─ 메타데이터 DB (이벤트·인덱스)
    ├─ 비식별화 서버
    └─ ★ 학습 저작도구 ★   ← 본 위키의 대상
            ↓
        AI 학습 데이터셋 → 침수·화재·교통 탐지 AI 모델
```

## 1.3 저작도구 핵심 파이프라인

```
클립영상 수신
    ↓
프레임 자동 추출 (FFmpeg Batch, 1회/분)
    ↓
이미지 전처리 (리사이징, 밝기/대비 자동 보정)
    ↓
작업자 라벨링 (바운딩박스 / 폴리곤 / 스켈레톤 / AI Tool)
    ↓
1차 검수 → 승인 or 반려
    ↓
2차 검수 → 승인 or 반려
    ↓
데이터 증강 (선택: 밝기·반전 5종)
    ↓
데이터마트 등록 / AI 모델 학습 투입
```

단계별 상세: 프레임 추출·전처리 → [06](06-video-frame-pipeline.md), 라벨링 → [07](07-labeling-tools.md), 검수 → [09](09-review-workflow.md), 증강/내보내기 → [11](11-augmentation-export.md).

## 1.4 기술 스택 (v1)

| 분야 | 기술 |
|------|------|
| 백엔드 언어 | Python 3.9+ |
| API 프레임워크 | FastAPI |
| 프론트엔드 | React / Vue.js |
| 데이터베이스 | **MySQL 8.0** (InnoDB, `.ibd`) |
| 캐시 | Redis |
| 메시지 큐 | RabbitMQ / Kafka |
| 컨테이너 | Docker & Docker Compose |
| 영상 처리 | FFmpeg, OpenCV |
| ML/DL | TensorFlow, PyTorch |
| 데이터 처리 | Pandas, NumPy |
| 모니터링 | Prometheus + Grafana, ELK Stack, Sentry |
| 보안 스캔 | OWASP 기반, Snyk |

### AI 모델 구성

| AI 기능 | 모델 / 방식 |
|---------|-----------|
| 오토 라벨링 (객체 탐지) | YOLO |
| 클릭 세그멘테이션 | SAM (Segment Anything Model) |
| 침수 탐지 모델 | UperNet + ViT-Adapter (mmseg/mmcv) |
| 하이퍼파라미터 튜닝 | Random Search |
| 성능 평가 | Confusion Matrix, mAP, IoU |

> **v2 참고**: `klid-label`(v2)는 **Spring Boot(Java 17) + PostgreSQL** 메인 + **FastAPI ai-server(YOLO/SAM2/VLM 추론 전용)** 구조이며, MySQL/Redis/Kafka는 사용하지 않는다. 침수 모델 등 AI 모델 본체는 v2 범위 외(외부 시스템 책임).

## 1.5 설계 핵심 원칙 (v1)

1. **프로젝트 기반 분리** — 프로젝트별 독립 공간으로 다중 학습 프로젝트 병렬 운영
2. **자동화 파이프라인** — FFmpeg 추출 → 자동 분류 → AI 보조 라벨링
3. **다중 라벨링 지원** — 바운딩박스/폴리곤/키포인트(스켈레톤)/Track
4. **AI 보조** — YOLO 오토 라벨링 + SAM 클릭 세그멘테이션
5. **품질 관리 체계** — 작업자 → 검수자 → 승인/반려 + 이력
6. **생성형 AI 연동** — 실데이터 부족 시 데이터 증강
7. **보안 강화** — GPKI 인증, 데이터 암호화, XSS 방지

> **v2 참고**: v2는 **프로젝트 단위가 아닌 "영상 1건" 단위**로 작업을 식별한다(`LS_DATA_RAW.RAW_SN`). v1의 프로젝트 개념은 v2에 없다.
