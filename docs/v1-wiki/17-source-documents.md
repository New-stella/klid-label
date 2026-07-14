# 17. 원본 문서 카탈로그

> 본 위키의 원천. 모든 원본은 [`sources/`](sources/) (= `docs/v1-wiki/sources/`)에 있으며, 각 문서는 SweetK/KLID의 v1 시스템 산출물을 분석·정리한 것이다.

## 17.1 원본 문서 목록

| 문서 | 버전 | 작성일 | 작성자 | 원본 파일 | 위키 반영 |
|------|------|--------|--------|----------|----------|
| [통합설계서](sources/KLID-AI-저작도구_통합설계서.md) | - | 2026.04.14 | (분석 취합) | (취합본) | 전 페이지 |
| [아키텍처 설계서](sources/KLID-DE-아키텍처설계서_저작도구구조분석.md) | V1.5 | 2025.12.05 | 이장우/노명철 | KLID-DE-아키텍처 설계서 V1.5_20251205.hwp | [02](02-architecture.md)·[06](06-video-frame-pipeline.md)·[16](16-security.md) |
| [데이터베이스 설계서](sources/KLID-AI-데이터베이스설계서_V1_4_분석.md) | V1.4 | 2025.12.08 | 이주화/강현우 | KLID-AI-데이터베이스 설계서 V1.4_20251208.hwp | [15](15-database.md) |
| [인터페이스 규격서 (D4)](sources/KLID-AI-인터페이스규격서_분석.md) | V1.0 | 2025.11 | - | KLID-AI-인터페이스 설계서(D4) | [14](14-interfaces-api.md)·[06](06-video-frame-pipeline.md)·[08](08-ai-assisted-labeling.md) |
| [프로그램 코드 규격서](sources/KLID-AI-프로그램코드규격서_분석.md) | V1.1 | 2025.11.13 | 이주화/강현우 | (프로그램 코드) | [02](02-architecture.md) |
| [사용자 인터페이스 설계서](sources/KLID-AI-사용자인터페이스설계서_V1_1_분석.md) | V1.1 | 2025.07.22 | 이주화/강현우 | KLID-AI-사용자 인터페이스 설계서 V1.1_250722.hwp (14MB) | [07](07-labeling-tools.md)·[12](12-generative-ai.md) |
| [화면정의서 (Storyboard)](sources/KLID_IM_UI-UX설계서_학습저작도구시스템_분석.md) | V1.0(0.7) | 2025.09.22 | 남궁은 | (화면정의서) | [04](04-menu-ia-screens.md)·[07](07-labeling-tools.md)·[09](09-review-workflow.md)·[10](10-dashboard-statistics.md) |
| [컴포넌트 설계서](sources/KLID-AI-컴포넌트설계서_V1_0_분석.md) | V1.0 | 2018.10.27 | - | (지능형 스마트 선별관제, 557KB) | [02](02-architecture.md) |
| [클래스 설계서](sources/KLID-AI-클래스설계서_V1_0_분석.md) | V1.0 | (2011.09.23 표기) | - | (2.85MB) | [02](02-architecture.md) |
| [운영자(사용자) 매뉴얼](sources/KLID_TE_운영자매뉴얼_분석.md) | V1.1 | 2025.12 | 강현우/이지연 | KLID_TE 매뉴얼(사용자) | [07](07-labeling-tools.md)·[09](09-review-workflow.md)·[12](12-generative-ai.md) |
| [관리자 매뉴얼](sources/KLID_TE_관리자매뉴얼_분석.md) | V1.1 | 2025.12 | 강현우/이지연 | KLID_TE 매뉴얼(관리자) | [05](05-project-management.md)·[06](06-video-frame-pipeline.md)·[11](11-augmentation-export.md) |

> 통합설계서 `.docx` 버전도 [`sources/KLID-AI-저작도구_통합설계서.md.docx`](sources/KLID-AI-저작도구_통합설계서.md.docx)에 존재.

### 17.1.1 원본 PDF (docs/1차)

아래 3종은 위 HWP 원본의 **PDF 배포본**으로 [`docs/1차/`](../1차/)에 보관된다. HWP 대비 텍스트·표·화면 캡처 추출이 온전해, **화면별 입출력 param 스펙·조작 절차**를 [21 사용자 화면 가이드](21-user-screen-guide.md)로 종합했다.

| 원본 PDF (docs/1차) | 대응 분석본 | 반영 |
|------|------|------|
| KLID-AI-사용자 인터페이스 설계서 V1.1_250722.pdf (D2, 46p) | [사용자 인터페이스 설계서](sources/KLID-AI-사용자인터페이스설계서_V1_1_분석.md) | [21](21-user-screen-guide.md) (SC-001~020 + param 스펙)·[07](07-labeling-tools.md)·[12](12-generative-ai.md) |
| KLID_IM_UI, UX설계서_학습 저작도구 시스템.pdf (화면정의서, 83p) | [화면정의서](sources/KLID_IM_UI-UX설계서_학습저작도구시스템_분석.md) | [21](21-user-screen-guide.md) (레이아웃·단축키·프레임 색상)·[04](04-menu-ia-screens.md)·[07](07-labeling-tools.md)·[09](09-review-workflow.md)·[10](10-dashboard-statistics.md) |
| KLID_TE_학습 및 저작도구 시스템 매뉴얼 (관리자) Rev. 1.0.pdf (71p) | [관리자 매뉴얼](sources/KLID_TE_관리자매뉴얼_분석.md) | [21](21-user-screen-guide.md) (조작 절차·확정 단축키·증강)·[05](05-project-management.md)·[06](06-video-frame-pipeline.md)·[11](11-augmentation-export.md) |

> **D2 §2.1 목록 vs §3 실제 화면 불일치**: 목록은 SC-001~018(18개)이나 §3에는 SC-019·SC-020(작업자 배정 목록/팝업)이 추가돼 실제 20개. V1.1 개정("작업자 할당·작업 분배 보완")의 흔적이며 목록 미반영. 상세는 [21 §21.1](21-user-screen-guide.md#211-세-문서의-화면-대조-교차-참조).

## 17.2 문서 신뢰도 / 주의

- **컴포넌트설계서·클래스설계서**: HWP 원본의 이진 포맷·다이어그램으로 인해 **텍스트 추출이 불완전**. 메서드 시그니처·시퀀스 다이어그램 세부는 원본 HWP 참조 필요. 작성일 표기(2018/2011)는 선행/템플릿 문서 흔적으로 보이며 본 프로젝트 시점과 불일치.
- **단축키**: 화면정의서와 운영자매뉴얼 간 표기 차이 존재 → [07 §7.3](07-labeling-tools.md#단축키)에 병기.
- **테이블 수**: DB설계서 본문 표는 24행, 집계는 "29개" — 이력/매핑 테이블 포함 차이([15](15-database.md)).

## 17.3 SFR 요구사항 매핑 (참고)

| SFR | 영역 | 관련 페이지 |
|-----|------|------------|
| SFR-06 | 비디오 프레임 추출 (II-001/002) | [06](06-video-frame-pipeline.md)·[14](14-interfaces-api.md) |
| SFR-07 | 오토라벨링/트래킹 (II-003~007) | [08](08-ai-assisted-labeling.md)·[14](14-interfaces-api.md) |
| RQ-SFR-10-01~04 | 이벤트 수신·클립 저장·검색·권한 관리 | [02](02-architecture.md)·[06](06-video-frame-pipeline.md) |

## 17.4 v1 → v2 핵심 차이 요약

> 전체 비교(차이점·v1 전용·v2 전용 기능)는 [18 v1 ↔ v2 비교](18-v1-v2-comparison.md) 참고. 아래는 요약.

| 구분 | v1 (본 위키) | v2 (`klid-label`) |
|------|-------------|-------------------|
| 작업 단위 | 프로젝트 | 영상 1건(`RAW_SN`) |
| 백엔드 | Python/FastAPI | Spring Boot(Java 17) + ai-server(FastAPI) |
| DB | MySQL 8.0/InnoDB | PostgreSQL/`klid_at` |
| 인증 | GPKI 로그인 | 관제/포털 JWT 인계(독립 로그인 없음) |
| 역할 | 작업자/1·2차 검수자/업로더/담당자/슈퍼관리자 | REVIEWER/WORKER/PORTAL_USER |
| 검수 | 1차→2차 다단계 | REVIEWER 단일 승인 |
| 버전관리 | 라벨 이력 누적 | 라벨 스냅샷 JSON + SHA-256(`LS_LABEL_VERSION`) |
| 생성형 AI/데이터마트 | 저작도구 내 기능 | 외부 시스템 책임(범위 외) |
| 외부 통지 | 송수신 양방향 II | 단방향 outbound(`TASK_COMPLETED`/`TASK_MODIFIED`) |

> v2 정본은 루트 [`CLAUDE.md`](../../CLAUDE.md) 및 [`docs/`](../) 하위 설계 문서를 따른다.
