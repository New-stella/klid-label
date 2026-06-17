# 01. 시스템 개요

> 출처: CLAUDE.md, R1 사용자요구사항정의서(v1.17), R2 §1·4
> 관련: [02 아키텍처](02-architecture.md) · [07 배치 파이프라인](07-batch-pipeline.md) · [19 외부 시스템](19-external-security-cvat.md)

## 1.1 목적

AI 기반 지방정부 CCTV 관제지원시스템(2차)의 **학습데이터 저작도구(AT)**. 영상/이미지 라벨링, 검수 워크플로우, 비식별화, 외부 생성 메타데이터 검토를 담당한다.

- **시스템명**: AI 기반 지방정부 CCTV 관제지원시스템 구축(2차) / 서브시스템: 학습데이터 저작도구(AT)
- **작업 단위 = 영상 1건** (`LS_DATA_RAW.RAW_SN`) — 프로젝트 단위 개념 없음 (v1과의 핵심 차이)

## 1.2 책임 범위

### 담당 (저작도구 책임)
사용자/권한 · 마킹(자동/수동) · 배치 파이프라인 · 라벨링 · 검수(REVIEWER 배정) · 비식별화 연동 · 버전관리(DB 스냅샷) · 데이터 증강 연동/검수 · 포털(데이터마트 Load).

### 범위 외 (외부 시스템 책임)
| 범위 외 | 저작도구가 하는 것 |
|---------|-------------------|
| **데이터마트** | 라벨링·검수·버전관리까지만. 마트 구축·검색·다운로드 미담당 (View 노출까지) |
| **생성형 AI 본체** | 외부 증강 결과 검수(SCR-AUG-002)만 |
| **VLM 모델 본체** | 외부 VLM 호출 연동 + 결과 검토만 |
| **영상 합성 모델 본체** | 합성/증강 영상 수신·라벨링·검수만 |
| **학습데이터셋 Export** | 라벨링·검수·버전관리까지만 |

> 외부가 본체인 시계열 메타 모델·생성형 AI·영상 합성 모델은 요구사항에서 제외. 저작도구 잔존 책임(외부 VLM 호출·메타 검토 UI·증강 연동·생성 영상 라벨링)은 SFR-08에 흡수. 상세 → [19](19-external-security-cvat.md).

## 1.3 역할

| 역할 | 코드 | 주요 권한 |
|------|------|-----------|
| 검수자 | `REVIEWER` | **사용자 관리·시스템 설정**, 작업자 배정·재배정·이력, 검수 승인/반려, 증강 요청·검수, 비식별 요청·옵션, 버전 비교·복구 |
| 라벨링 작업자 | `WORKER` | 본인 배정 영상 라벨 수정·검수 제출, AI 보조 라벨링, 비식별 누락 신고 |
| 포털 회원 | `PORTAL_USER` | 데이터마트 영상 선택, 기존 라벨 확인·수정·저장, 본인 데이터 다운로드 (업로드·오토라벨링 없음 — ADR-013) |

> ADMIN 역할 없음 — 모든 관리 권한은 REVIEWER에 통합. UI 호칭 '검수자', 관리 화면 URL `/manage/*`. 상세 → [03](03-auth-roles.md).

## 1.4 기술 스택

### 백엔드 (Spring Boot)
Java 17 · Spring Boot 3.3 · Gradle 8 · Spring Data JPA(Hibernate 6) + **QueryDSL 5.1** · Spring Security + **JJWT 0.12** · **Flyway 10**(PostgreSQL, `klid_at` 스키마) · **Spring Boot Quartz**(PostgreSQL JobStore) · **Resilience4j** · WebFlux WebClient · **net.bramp.ffmpeg** · Caffeine · MapStruct/Lombok · **Micrometer + Prometheus** · Springdoc OpenAPI · JUnit5 + Testcontainers.

### AI 추론 서버 (ai-server)
Python 3.11 + FastAPI · **YOLOX(ONNX Runtime, Apache-2.0)** 탐지 · **Meta SAM2(Apache-2.0)** 분할 · RT-DETR(transformers) · onnxruntime · torch · opencv. **경량 추론 전용, stateless, 인증/DB 없음** — Spring Boot가 오케스트레이션. (외부가 아니라 저작도구 내부 구성요소) · 라이선스: AGPL(ultralytics) 미사용 — 전부 permissive(MIT/Apache-2.0)로 구성

### 프론트엔드
React 18 + TypeScript 5 + Vite 5 · TanStack Query v5 · Zustand · React Router v6 · axios · Tailwind · **konva.js**(CVAT canvas-drawing 포팅).

### DB
**PostgreSQL** · `klid_at` 스키마 — 저작도구 전용 **LS_*** 자체 소유, 관제서버 **MNG_*** 9개 `validate` 참조, Quartz `QRTZ_*`. 상세 → [18](18-database.md).

## 1.5 핵심 파이프라인

```
영상 적재 → 비식별화(전체 영상 무조건, 적재 직후 선두 자동) → 마킹(비식별 영상)
  → 외부 VLM 시계열(콜백) → FFmpeg(마킹 위치 기반 원본+비식별 2벌)
  → YOLO(원본만) → SAM2 → 트랙 보간
  → 라벨링 → 검수(승인=완료) → 버전 스냅샷 + 관제 통지 → (선택) 증강
```

> 비식별 선두 재배치(R1 NFR-001 v1.5) **구현 완료**. 단계 순서는 `BatchPipelineConfig` 에서 선언적으로 관리. → [07](07-batch-pipeline.md).

## 1.6 핵심 산출물 목표 (NFR)

| NFR | 목표 |
|-----|------|
| NFR-002 | 이미지 학습데이터 **10만장**(`LS_DATA_SRC`) |
| NFR-003 | 영상 학습데이터 **5,000건**(30초 이상/건, `LS_DATA_RAW`) |
| 증강 4종 | WINTER / NIGHT / RAIN / RESOLUTION |

전체 NFR-001~007 → [19](19-external-security-cvat.md#nfr).
