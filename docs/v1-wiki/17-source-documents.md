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
| [사용자 인터페이스 설계서](sources/KLID-AI-사용자인터페이스설계서_V1_1_분석.md) | D2 V1.1 | 2025.07.22 (v1.0은 2025.07.03) | **작성 강현우 / 승인 이주화**[^ui-fix] | KLID-AI-사용자 인터페이스 설계서 V1.1_250722.hwp (14MB) / 실제 PDF 12.08MB(12,666,314B) | [07](07-labeling-tools.md)·[12](12-generative-ai.md) |
| [화면정의서 (Storyboard)](sources/KLID_IM_UI-UX설계서_학습저작도구시스템_분석.md) | V1.0(0.7) | 2025.09.22 | 남궁은 | (화면정의서) | [04](04-menu-ia-screens.md)·[07](07-labeling-tools.md)·[09](09-review-workflow.md)·[10](10-dashboard-statistics.md) |
| [컴포넌트 설계서](sources/KLID-AI-컴포넌트설계서_V1_0_분석.md) | V1.0 | 2018.10.27 | - | (지능형 스마트 선별관제, 557KB) | [02](02-architecture.md) |
| [클래스 설계서](sources/KLID-AI-클래스설계서_V1_0_분석.md) | V1.0 | (2011.09.23 표기) | - | (2.85MB) | [02](02-architecture.md) |
| [운영자(사용자) 매뉴얼](sources/KLID_TE_운영자매뉴얼_분석.md) | V1.1 | 2025.12 | 강현우/이지연 | KLID_TE 매뉴얼(사용자) — ⚠ 이번 1차 PDF 3종에 미포함, 아래 정정 대상 아님(원문 미확인) | [07](07-labeling-tools.md)·[09](09-review-workflow.md)·[12](12-generative-ai.md) |
| [관리자 매뉴얼](sources/KLID_TE_관리자매뉴얼_분석.md) | **Rev. 1.0**(표지/파일명) | 2025.12(일자 미표기) | **내부 재개정이력**: v1.0 2025.10.30(작성 강현우/승인 이주화, "초안 작성") → v1.1 2025.11.12(작성 이지연/승인 이주화, "라벨링 화면 단축키 안내 추가, 증강기능 추가")[^admin-fix] | KLID_TE_학습 및 저작도구 시스템 매뉴얼 (관리자)  Rev. 1.0.pdf (29.34MB, 30,766,477B) | [05](05-project-management.md)·[06](06-video-frame-pipeline.md)·[11](11-augmentation-export.md) |

[^ui-fix]: ⚠ **정정(2026-08-19 원문 실측)** — 이전 판은 "작성자: 이주화/강현우"로 두 사람을 공동 작성자처럼 표기했으나, 원문 제개정 이력서(v1.0·v1.1 모두)는 **작성자=강현우, 승인자=이주화**로 역할이 분리돼 있다. 근거: `03-UI설계서V1.1.txt` 표지 「제개정 이력서」 "2025.07.03. 1.0 강현우 이주화 초안작성" / "2025.07.22. 1.1 강현우 이주화 프로젝트 검수 관리 기능 보완…".
[^admin-fix]: ⚠ **정정(2026-08-19 원문 실측)** — 이전 판은 "V1.1 / 2025.12 / 강현우·이지연"으로 단일 버전처럼 표기했으나, 실제로는 **표지·파일명(Rev. 1.0)과 문서 내부 재개정이력(v1.0→v1.1)이 서로 다른 값**이다. 게다가 **본문 각 페이지 하단 스탬프는 v1.1로 개정된 이후에도 계속 "Version 1.0"으로 남아 있다**(예: 게시판·연습장·업로드/생성 절이 있는 59~71p 전부 "Version 1.0" 스탬프, `02-관리자매뉴얼.txt` §9·§10·§11 다수 확인) — 원문 자체의 버전 표기 불일치이며 우리 쪽 오기가 아니다. 근거: `02-관리자매뉴얼.txt` 표지 "2025.12", 「재개정이력」 표, 다수 페이지 푸터

> 통합설계서 `.docx` 버전도 [`sources/KLID-AI-저작도구_통합설계서.md.docx`](sources/KLID-AI-저작도구_통합설계서.md.docx)에 존재.

### 17.1.1 원본 PDF

> ⚠ **경로 정정(2026-08-19 확인)** — 이전 판은 이 PDF 3종이 **본 저장소 안의 `docs/1차/`**에 있다고 기술했으나, **`docs/1차/`는 이 git 저장소(`klid-label`)에 존재하지 않는다**(`ls docs/1차/` → No such file or directory, 2026-08-19 확인). 실제 PDF 3종의 원본 위치는 **`/Users/ck/Documents/workspace/klid/docs/1차/`**로, **이 저장소와 별개의 로컬 워크스페이스**다(다른 git 프로젝트 경로이며 본 리포에 커밋돼 있지 않다). 이번 정합 작업에서는 그 경로의 PDF를 텍스트 추출해 임시 스크래치패드(`.../scratchpad/1cha/{01-UIUX설계서,02-관리자매뉴얼,03-UI설계서V1.1}.txt`, 세션 종료 시 소멸)에서 대조했다. 아래 표의 "원본 PDF" 열은 파일 소재를 뜻하지 않고 **파일명·판정 근거로만** 읽을 것 — 이 위키를 보는 사람이 실제로 PDF를 열람하려면 관리자에게 `/Users/ck/Documents/workspace/klid/docs/1차/` 경로 접근을 요청해야 한다.
>
> 아래 3종은 위 HWP 원본의 **PDF 배포본**이다. HWP 대비 텍스트·표·화면 캡처 추출이 온전해, **화면별 입출력 param 스펙·조작 절차**를 [21 사용자 화면 가이드](21-user-screen-guide.md)로 종합했다.

| 원본 PDF (파일명, 소재는 위 경로 정정 참고) | 대응 분석본 | 반영 |
|------|------|------|
| KLID-AI-사용자 인터페이스 설계서 V1.1_250722.pdf (D2, 46p) | [사용자 인터페이스 설계서](sources/KLID-AI-사용자인터페이스설계서_V1_1_분석.md) | [21](21-user-screen-guide.md) (SC-001~020 + param 스펙)·[07](07-labeling-tools.md)·[12](12-generative-ai.md) |
| KLID_IM_UI, UX설계서_학습 저작도구 시스템.pdf (화면정의서, 83p) | [화면정의서](sources/KLID_IM_UI-UX설계서_학습저작도구시스템_분석.md) | [21](21-user-screen-guide.md) (레이아웃·단축키·프레임 색상)·[04](04-menu-ia-screens.md)·[07](07-labeling-tools.md)·[09](09-review-workflow.md)·[10](10-dashboard-statistics.md) |
| KLID_TE_학습 및 저작도구 시스템 매뉴얼 (관리자)  Rev. 1.0.pdf (71p, 파일명에 "(관리자)"와 "Rev."사이 공백 2칸 — 원본 파일명 그대로 표기) | [관리자 매뉴얼](sources/KLID_TE_관리자매뉴얼_분석.md) | [21](21-user-screen-guide.md) (조작 절차·확정 단축키·증강)·[05](05-project-management.md)·[06](06-video-frame-pipeline.md)·[11](11-augmentation-export.md) |

> **D2 §2.1 목록 vs §3 실제 화면 불일치**: 목록은 SC-001~018(18개, 원문 확인: `03-UI설계서V1.1.txt` §2.1)이나 §3에는 SC-019·SC-020(작업자 배정 목록/팝업)이 추가돼 실제 20개. V1.1 개정("작업자 할당·작업 분배 보완")의 흔적이며 목록 미반영. 상세는 [21 §21.1](21-user-screen-guide.md#211-세-문서의-화면-대조-교차-참조).

## 17.2 문서 신뢰도 / 주의

- **컴포넌트설계서·클래스설계서**: HWP 원본의 이진 포맷·다이어그램으로 인해 **텍스트 추출이 불완전**. 메서드 시그니처·시퀀스 다이어그램 세부는 원본 HWP 참조 필요. 작성일 표기(2018/2011)는 선행/템플릿 문서 흔적으로 보이며 본 프로젝트 시점과 불일치.
- **단축키**: 화면정의서와 운영자매뉴얼 간 표기 차이 존재 → [07 §7.3](07-labeling-tools.md#73-단축키)에 병기.
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
