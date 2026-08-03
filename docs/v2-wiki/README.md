# v2 학습데이터 저작도구 기능 위키

> **무엇인가**: 현재 프로젝트 **`klid-label`(v2) — "AI 기반 지방정부 CCTV 관제지원시스템(2차) 학습데이터 저작도구(AT)"** 의 기능을 기능 단위로 정리한 위키입니다.
> **정본 출처**: 루트 [`CLAUDE.md`](../../CLAUDE.md) + [`docs/design/`](../design/) (R1~R3, D1~D9) + 실제 코드(`backend/`, `frontend/`, `ai-server/`). 본 위키는 이를 기능별로 재구성한 빠른 참조용이며, 충돌 시 정본이 우선합니다.
> **이전 시스템(v1)** 비교는 [`docs/v1-wiki/`](../v1-wiki/) (특히 [18 v1↔v2 비교](../v1-wiki/18-v1-v2-comparison.md)) 참고.

## 핵심 정체성 (한 줄 요약)

영상 **1건 단위**로 마킹 → 비식별 → 오토라벨링(YOLO/SAM2) → 라벨링 → 검수 → 버전관리까지 담당하는 저작도구. **생성형 AI·데이터마트·VLM 모델 본체·Export는 외부 시스템 책임**이고 저작도구는 연동(호출/통지/검수)만 한다.

---

## 📑 페이지 목차

| # | 페이지 | 내용 |
|---|--------|------|
| 00 | [로컬 자족 실행 (외부 0개 테스트)](00-local-setup.md) | docker/native 기동, dev 토큰 인증, 시나리오별 검증 경로, 외부0개 토글 (ai-server CPU 실추론·RT-DETR) |
| 01 | [시스템 개요](01-system-overview.md) | 목적·범위·범위 외·역할·기술스택·핵심 파이프라인 |
| 02 | [아키텍처](02-architecture.md) | 모노레포·레이어·공통 인프라·ai-server 분리·듀얼 데이터소스 |
| 03 | [인증 · 권한 · 역할](03-auth-roles.md) | JWT 인계, REVIEWER/WORKER/PORTAL_USER, 진입 |
| 04 | [화면 · IA](04-screens-ia.md) | KLID-AT-SC 화면, 내부/포털 채널, 라우트 |
| 05 | [영상 관리 · 업로드](05-video-management.md) | LS_DATA_RAW, TUS 업로드, 스트리밍, 등록 |
| 06 | [마킹](06-marking.md) | 자동/수동 마킹, 스트리밍, 배치 트리거 |
| 07 | [배치 파이프라인](07-batch-pipeline.md) | 오케스트레이터 단계, Quartz, 재시도 |
| 08 | [비식별화](08-deidentification.md) | 외부 연동, 누락 신고, 처리 이력 |
| 09 | [VLM 시계열 메타](09-vlm-timeseries.md) | 외부 VLM 호출·콜백, 메타 검수, event_annotation(VQA/CoT) 수동입력·검수 |
| 10 | [라벨링](10-labeling.md) | konva 캔버스, 도구, 라벨 속성, 프리셋 |
| 11 | [AI 보조 · 오토라벨링](11-ai-assisted.md) | YOLO/SAM2(VOS·분할), 트랙 보간, ai-server |
| 12 | [검수 · 작업 배정](12-review-assignment.md) | REVIEWER 승인/반려, 배정·재배정 이력 |
| 13 | [버전관리](13-version-control.md) | 라벨 스냅샷, diff, rollback |
| 14 | [데이터 증강 · 해상도 변경](14-augmentation.md) | 외부 증강 위탁·검수, 무결성, 해상도 변경 파생영상(업스케일 허용·좌표 배율 재계산) |
| 15 | [관제서버 통지](15-control-notify.md) | TASK_COMPLETED/MODIFIED, fallback, 조회 API |
| 16 | [포털](16-portal.md) | 데이터마트 영상 선택, 사용자 라벨 별도 적재, SAM2 인터랙티브·자동추적·키포인트(Phase 9, 좌표만·LS_PORTAL_USER_LABEL 단방향) |
| 17 | [통계 · 대시보드](17-statistics.md) | 작업자/전체 통계, CSV 리포트 |
| 18 | [데이터베이스](18-database.md) | LS_* 테이블, V_COMPLETED_* View, 마이그레이션 |
| 19 | [외부 시스템 · 보안 · CVAT 포팅 · 설계문서](19-external-security-cvat.md) | 연동 클라이언트, 보안, CVAT 모듈, R/D 카탈로그 |
| 20 | [게시판 (공지·가이드라인)](20-notice-board.md) | 공지 CRUD, DRAFT/발행, 고정, 첨부, 권한 — R1 외 추가 |
| 21 | [이슈 소통 채널](21-issue-channel.md) | 검수자↔작업자 문의 스레드, 상태 머신, 반려 통합 — R1 외 추가 |
| 22 | [비식별화 솔루션 API 연동 명세](22-deid-solution-api.md) | KPST 외부 API 13종 명세 전사(v1.0), 폴링 모델, 코드·상태 정의 |
| 23 | [v1→v2 DB 이관 분석 (영상·라벨)](23-v1-v2-db-migration.md) | MariaDB(klid)→PostgreSQL(klid_system_246) **영상 목록+라벨 결과** 실DB 검증 매핑, POINT 좌표 포맷 변환, ID 재연결, 결정 필요 GAP 3종 |
| 24 | [검수 승인 시 학습데이터 파일 산출](24-dataset-export.md) | 승인 AFTER_COMMIT 트리거(API 없음), `{RAW_SN}/v{n}/orgnl\|deid/` 폴더, NIA COCO 확장 JSON(8키·video 매핑·미보유 null·SKELETON 1-indexed·event_annotation c1..cn), 콘텐츠 해시 멱등·버전 누적, LS_DATASET_EXPORT |

---

## 🔍 빠른 찾기

### 기능 → 페이지

| 찾는 것 | 페이지 |
|--------|--------|
| 작업 단위(영상 1건, RAW_SN) | [01](01-system-overview.md) |
| 마킹 (자동=프레임간격 / 수동=Space·Del·Enter) | [06](06-marking.md) |
| 배치 파이프라인 순서 | [07](07-batch-pipeline.md) |
| 비식별화 / 누락 신고 (DE_IDENT_YN='F') | [08](08-deidentification.md) |
| 비식별 솔루션(KPST) API 명세·폴링 흐름 | [22](22-deid-solution-api.md) |
| VLM 시계열 메타 검수 | [09](09-vlm-timeseries.md) |
| 라벨링 도구 (bbox/polygon/mask) | [10](10-labeling.md) |
| SAM2 추적(VOS) / 외곽 밀착 / YOLO | [11](11-ai-assisted.md) |
| 라벨링 정밀도 조절 (YOLO_CONF_THRESHOLD 등) | [11](11-ai-assisted.md#정밀도-조절) |
| 검수 승인/반려, 작업 배정/재배정 | [12](12-review-assignment.md) |
| 버전 스냅샷 / diff / rollback | [13](13-version-control.md) |
| 데이터 증강(WINTER/NIGHT/RAIN) / 해상도 변경 | [14](14-augmentation.md) |
| 관제 통지 TASK_COMPLETED/MODIFIED | [15](15-control-notify.md) |
| 포털 (데이터마트 영상 선택) | [16](16-portal.md) |
| 외부 시스템 연동 / 보안 / CVAT 포팅 | [19](19-external-security-cvat.md) |
| 게시판 (공지/가이드라인, 첨부) | [20](20-notice-board.md) |
| 이슈 소통 (문의/답변/해소, 반려 스레드) | [21](21-issue-channel.md) |
| v1(MariaDB)→v2(PostgreSQL) DB 이관 분석 | [23](23-v1-v2-db-migration.md) |
| 검수 승인 시 학습데이터 파일 산출(orgnl/deid, NIA COCO JSON) | [24](24-dataset-export.md) |

### 화면 ID(`KLID-AT-SC-*`) → 페이지

| 화면 ID | 화면 | 페이지 |
|---------|------|--------|
| SC-001/002 | 세션 인계 / 역할 클레임 | [03](03-auth-roles.md) |
| SC-005 | 라벨링 캔버스 | [10](10-labeling.md) |
| SC-006 | 마킹 | [06](06-marking.md) |
| SC-007/009 | 영상 목록/상세 | [05](05-video-management.md) |
| SC-011 | 대시보드 | [17](17-statistics.md) |
| SC-012 | 작업 목록 (배정 동선 포함) | [12](12-review-assignment.md) |
| SC-018/019 | 검수 목록/상세 | [12](12-review-assignment.md) |
| SC-020/021 | 작업자/전체 통계 | [17](17-statistics.md) |
| SC-022/023 | 증강 요청/결과 | [14](14-augmentation.md) |
| SC-024/025/026 | 사용자/시스템설정/프리셋 관리 | [03](03-auth-roles.md)·[10](10-labeling.md) |
| SC-029 | 포털 라벨링 | [16](16-portal.md) |
| SC-030/031/032 | 게시판 목록/상세/작성 모달 | [20](20-notice-board.md) |

> deprecated: SC-016/017(비식별 목록/상세 → 외부 솔루션), SC-028(포털 홈 → ADR-013).

### 서브시스템 / 요구사항

10개 서브시스템(KLID-AT-SS-001~010) · 13개 유스케이스(KLID-AT-UC-*) · SFR(06-03·07·08·09)·NFR-001~007 → [19](19-external-security-cvat.md#설계-문서-카탈로그).

---

## 한눈에 보는 핵심 파이프라인 (v2)

```
영상 적재(업로드/TUS)
  → ⭐비식별화(전체 영상 무조건, 적재 직후 선두 자동 → MARKING_READY)
  → 마킹(자동=프레임간격 / 수동=단축키, 비식별 영상 대상)
  → 외부 VLM 시계열(콜백 비동기)
  → FFmpeg(마킹 위치 기반 원본+비식별 2벌 추출)
  → YOLO(원본만) → SAM2 → 트랙 보간
  → 라벨링(WORKER) → 검수(REVIEWER 승인=완료)
  → 버전 스냅샷 + 관제 TASK_COMPLETED 통지
  → (선택) 외부 증강 → 새 영상 검수
```

> 비식별 선두 재배치(R1 NFR-001 v1.5) **구현 완료** — 단계 순서는 `BatchPipelineConfig` 에서 선언적으로 관리. 자세한 단계는 [07 배치 파이프라인](07-batch-pipeline.md) 참고.
