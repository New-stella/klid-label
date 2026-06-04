# v1 학습 저작도구 기능 위키

> **무엇인가**: SweetK가 KLID(한국지역정보개발원) 의뢰로 구축한 **이전(v1) "AI 기반 지자체 CCTV 관제지원시스템 — 학습 저작도구"**의 설계서·매뉴얼을 기능 단위로 재정리한 위키입니다.
> **왜 있는가**: 현재 `klid-label`(v2) 재구현 시 이전 시스템의 기능·데이터·인터페이스를 빠르게 찾아보기 위한 레퍼런스입니다.
> **원본 위치**: [`sources/`](sources/) (= `docs/v1-wiki/sources/`, 11개 분석 문서 + 통합설계서 docx). 본 위키는 원본을 **기능별로 재배치**한 것이며 원본은 그대로 보존됩니다.

⚠️ **주의**: 본 위키는 **v1 시스템**을 설명합니다. v2(`klid-label`)의 범위·아키텍처는 루트 [`CLAUDE.md`](../../CLAUDE.md)를 따르며 v1과 다릅니다(예: v1은 MySQL·프로젝트 단위·GPKI, v2는 PostgreSQL·영상 단위·관제/포털 JWT). v1↔v2 차이는 각 페이지의 *"v2 참고"* 메모를 확인하세요.

---

## 📑 페이지 목차

| # | 페이지 | 내용 |
|---|--------|------|
| 01 | [시스템 개요](01-system-overview.md) | 목적·전체 위상·핵심 파이프라인·기술 스택 |
| 02 | [아키텍처 · 컴포넌트 · 클래스](02-architecture.md) | control-hub 도메인, 서브시스템(PF), 패키지/클래스 구조 |
| 03 | [역할 · 권한 · 메뉴 접근](03-roles-permissions.md) | 작업자/검수자/업로더/담당자/슈퍼관리자, 권한 매트릭스 |
| 04 | [메뉴 IA · 화면 목록](04-menu-ia-screens.md) | 메뉴 트리, 전체 화면 66개, 화면 ID 규칙 |
| 05 | [프로젝트 관리](05-project-management.md) | 생성 5단계, 완료/완료취소, 데이터 배정 |
| 06 | [영상 수집 · 프레임 추출 · 전처리](06-video-frame-pipeline.md) | 이벤트 수신, FFmpeg 배치, 이미지 분류, 프레임 분할, 전처리 |
| 07 | [라벨링 도구](07-labeling-tools.md) | 바운딩박스/폴리곤/스켈레톤/블러/그리드, 객체·메타·이슈 탭, 단축키 |
| 08 | [AI 보조 라벨링](08-ai-assisted-labeling.md) | AI Tool(SAM), Auto Labeling(YOLO), Track 모드(선형보간) |
| 09 | [검수 워크플로우](09-review-workflow.md) | 1·2차 검수, 프레임 색상, 관리자 확인요청, 이력 |
| 10 | [대시보드 · 통계](10-dashboard-statistics.md) | 작업자/관리자 대시보드, 통계 화면 |
| 11 | [데이터 증강 · 내보내기](11-augmentation-export.md) | 증강 5종, 상태 흐름, 데이터마트 등록 |
| 12 | [생성형 AI 연동](12-generative-ai.md) | Text/Image→Image/Video, 메타 기반 생성, 업로드/생성 데이터 관리 |
| 13 | [게시판 · 연습장](13-board-practice.md) | 공지/가이드라인, 라벨링 연습 환경 |
| 14 | [인터페이스 · REST API](14-interfaces-api.md) | II-001~007, API 엔드포인트, 데이터 모델 |
| 15 | [데이터베이스](15-database.md) | 테이블 29개, ERD, 핵심 컬럼, 인덱스, 용량 |
| 16 | [보안 정책](16-security.md) | GPKI, 입력/네트워크/데이터 보안, 개인정보 보호 |
| 17 | [원본 문서 카탈로그](17-source-documents.md) | 11개 원본 문서 메타·버전·출처 매핑 |
| 18 | [v1 ↔ v2 비교](18-v1-v2-comparison.md) | **현재 프로젝트와의 차이 · v1 전용 · v2 전용 기능** |
| 19 | [v2 갭/마이그레이션 체크리스트](19-v2-gap-checklist.md) | **v1→v2 추가 후보 체크리스트** (대체됨/범위외/검토후보 분류) |
| 20 | [외부 시스템](20-external-systems.md) | **v2 연동 외부 시스템 + 범위 외 시스템** (관제·포털·비식별·VLM·증강) |

---

## 🔍 빠른 찾기

### 기능 → 페이지

| 찾는 것 | 페이지 |
|--------|--------|
| 프로젝트 생성 5단계 (기본정보·메타·라벨·단계·권한) | [05](05-project-management.md) |
| 라벨 형태 (바운딩박스/폴리곤/스켈레톤/키포인트) | [07](07-labeling-tools.md) |
| 단축키 (W/A/S/D, Ctrl+S, 폴리곤 F/Q 등) | [07](07-labeling-tools.md#단축키) |
| 트랙 모드 / 선형보간 / 트래킹 버튼 | [08](08-ai-assisted-labeling.md#track-모드-선형보간) |
| 오토 라벨링(YOLO) / AI Tool(SAM) | [08](08-ai-assisted-labeling.md) |
| 비식별화(블러) 작업 | [07](07-labeling-tools.md#블러--비식별화) |
| 검수 반려/승인, 프레임 색상(연두/주황/빨강) | [09](09-review-workflow.md) |
| 관리자 확인 요청 / 폐기 | [09](09-review-workflow.md#관리자-확인-요청) |
| 데이터 증강 5종 (밝게/어둡게/좌우반전) | [11](11-augmentation-export.md) |
| 생성형 AI (Text/Image to Image/Video) | [12](12-generative-ai.md) |
| 프레임 분할 (초당/분당/시간당 FPS) | [06](06-video-frame-pipeline.md#프레임-분할-배정) |
| 대시보드 통계 / 엑셀 다운로드 | [10](10-dashboard-statistics.md) |
| **v2와의 차이 / v1·v2 전용 기능** | [18](18-v1-v2-comparison.md) |
| **v1→v2 추가 후보 체크리스트** | [19](19-v2-gap-checklist.md) |
| **외부 시스템 (연동/범위 외)** | [20](20-external-systems.md) |

### 화면 ID(`SKKLID-UI-*`) → 페이지

| 화면 ID 그룹 | 영역 | 페이지 |
|-------------|------|--------|
| `UI-02-01`, `UI-03-01` | 대시보드 | [10](10-dashboard-statistics.md) |
| `UI-02-02-04~15` | 데이터 작업(라벨링) | [07](07-labeling-tools.md) |
| `UI-02-02-16~19` | 데이터 검수 | [09](09-review-workflow.md) |
| `UI-02-03` | 게시판 | [13](13-board-practice.md) |
| `UI-02-04` | 연습장 | [13](13-board-practice.md) |
| `UI-02-05` | 업로드/생성 | [12](12-generative-ai.md) |
| `UI-03-02` | 프로젝트 관리 | [05](05-project-management.md) |
| `UI-03-03` | 영상/이미지 관리 | [06](06-video-frame-pipeline.md) |
| `UI-03-04` | 업로드/생성 데이터 관리 | [12](12-generative-ai.md) |

> 전체 66개 화면 ID 목록은 [04 메뉴 IA · 화면 목록](04-menu-ia-screens.md#전체-화면-목록-66개)에 있습니다.

### DB 테이블 → 페이지

핵심: `LS_DATA_SRC`(원천), `LS_PJT`(프로젝트), `LS_DATA_LBL_HSTRY`(라벨이력·최대용량), `LS_DATA_AUG`(증강) — 전체 29개 정의는 [15 데이터베이스](15-database.md).

### 인터페이스 ID → 페이지

`KLID-AI-II-001/002`(프레임 추출 Batch), `II-003~006`(오토라벨링 Online), `II-007`(트래킹) — 상세는 [14 인터페이스 · REST API](14-interfaces-api.md).

---

## 한눈에 보는 핵심 파이프라인 (v1)

```
클립영상 수신 → 프레임 자동 추출(FFmpeg Batch) → 이미지 전처리(리사이징·밝기/대비)
   → 작업자 라벨링(바운딩박스/폴리곤/스켈레톤 + AI Tool/Auto Labeling)
   → 1차 검수 → 2차 검수 (승인/반려)
   → 데이터 증강(선택, 5종) → 데이터마트 등록 / AI 모델 학습 투입
```

*출처: v1 통합설계서 §1.3, 아키텍처설계서 §3. 자세한 단계는 [01](01-system-overview.md)·[06](06-video-frame-pipeline.md) 참고.*
