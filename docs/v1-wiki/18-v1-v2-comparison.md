# 18. v1 ↔ v2 비교 (차이점 · v1 전용 · v2 전용 기능)

> 본 페이지는 **v1(SweetK 구축, 본 위키 01~17)**과 **v2(현재 `klid-label` 재구현)**를 비교한다.
> v2 근거: 실제 코드 조사(`backend/src/main/java/kr/co/cudo/authoring/` 22개 도메인, `db/migration/` V0~V55, `frontend/src/`, `ai-server/app/routers/`, `docs/design/` R1~R3·D1~D9) 기준 — 2026-06 시점.
> 정본은 루트 [`CLAUDE.md`](../../CLAUDE.md)·[`docs/design/`](../design/). 본 페이지는 비교 요약이며, 세부 동작이 충돌하면 v2 코드/설계가 우선.

---

## 18.1 한눈에 보는 관계

- **v1** = AI CCTV 관제 학습 저작도구 (Python/FastAPI · MySQL · **프로젝트 단위** · GPKI · CVAT 미사용 자체구현)
- **v2** = 동일 목적의 **재구현** (Spring Boot Java 17 + ai-server FastAPI · PostgreSQL · **영상 1건 단위** · 관제/포털 JWT 인계 · CVAT 모듈 Java 포팅)

v2는 v1의 라벨링/검수 핵심은 계승하되, **작업 단위·아키텍처·범위**를 크게 재설계했다. 특히 v1이 내장하던 **생성형 AI·데이터마트·Export·VLM/영상합성 모델 본체**는 v2에서 **외부 시스템 책임(범위 외)**으로 분리됐고, 대신 v2는 **마킹·외부 VLM 연동·비식별 누락 신고·버전 스냅샷·관제 통지** 등을 신설했다.

---

## 18.2 기술 · 구조 차이

| 구분 | v1 | v2 |
|------|----|----|
| 백엔드 | Python 3.9 / FastAPI | **Spring Boot 3.3 (Java 17)** + ai-server(FastAPI, 추론 전용) |
| DB | MySQL 8.0 / InnoDB | **PostgreSQL** / `klid_at` 스키마 |
| 스키마 소유 | 단일 시스템 소유 | 저작도구 **LS_*** 자체 소유 + 관제 **MNG_*** 9종 `validate` 참조 |
| 마이그레이션 | - | **Flyway** (V0~V55, 70+ 테이블/뷰) |
| 캐시/큐 | Redis / RabbitMQ·Kafka | **없음** (Caffeine 로컬 캐시 60s, Quartz JobStore) |
| 스케줄러 | 배치 프로그램 1회/분 | **Spring Boot Quartz**(PostgreSQL JobStore, 단일 인스턴스) |
| 인증 | **GPKI 로그인** (자체 로그인 UI) | **관제/포털 JWT 인계**(독립 로그인 UI 없음, `JwtAuthenticationFilter`) |
| 작업 단위 | **프로젝트** (`PJT_SN`) | **영상 1건** (`LS_DATA_RAW.RAW_SN`) |
| 라벨 캔버스 | 자체 구현 | **konva.js** (CVAT canvas-drawing 포팅) |
| AI 추론 위치 | PF-005/006 내장 모듈 | **ai-server** stateless 분리(`AiServerClient`, Resilience4j) |
| 외부 연동 회복성 | - | **Resilience4j**(타임아웃/재시도/서킷) + Fallback 큐 + Idempotency |
| 화면 ID 체계 | `SKKLID-UI-NN-NN-NN` (66개) | `KLID-AT-SC-NNN` (23개, deprecated 3 제외) |
| 요구사항 체계 | SFR-06/07, RQ-SFR-10-* | **RQ-SFR-06~09 + NFR**, R1~R3 추적표, D1~D9 설계서 |

---

## 18.3 v1에만 있는 기능 (v2 미보유 / 범위 외)

> 이 중 **무엇을 v2에 추가할지** 결정용 체크리스트는 [19 v2 갭/마이그레이션 체크리스트](19-v2-gap-checklist.md) 참고.

| v1 기능 | v1 위치 | v2에서의 처리 |
|---------|---------|--------------|
| **프로젝트 단위 관리** (생성 5단계·완료/완료취소·프로젝트 통계) | [05](05-project-management.md) | 폐기 — v2는 영상 1건 단위. 프로젝트/메타·라벨·단계·권한 설정 개념 없음 |
| **1차 / 2차 다단계 검수** | [09](09-review-workflow.md) | 단일 REVIEWER 승인으로 단순화 |
| **관리자 확인 요청 / 폐기** 흐름 | [09](09-review-workflow.md#관리자-확인-요청) | 없음 (REVIEWER가 반려로 처리) |
| **업로더 역할 + 생성형 AI 화면**(Text/Image→Image/Video 내장) | [12](12-generative-ai.md) | **범위 외** — 생성형 AI 본체는 외부 시스템. v2는 외부 증강 결과 검수만 |
| **데이터마트 등록 / 내보내기**(저작도구 내) | [11](11-augmentation-export.md) | **범위 외** — 마트 구축·검색·다운로드는 외부. v2는 `V_COMPLETED_*` View 노출까지만 |
| **학습데이터셋 Export** | [11](11-augmentation-export.md) | **범위 외** |
| **게시판 / 연습장** | [13](13-board-practice.md) | v2 범위에 없음 |
| **GPKI 로그인** | [16](16-security.md) | JWT 인계로 대체 |
| **스켈레톤 / 키포인트 라벨**(포인트 정의 도구) | [07](07-labeling-tools.md#스켈레톤-skeleton) | v2 라벨 타입은 BBOX/POLYGON/POINT 중심. 스켈레톤 골격 정의 도구 없음 |
| **증강 5종**(밝게/어둡게/좌우반전 ±2) 내장 처리 | [11](11-augmentation-export.md) | 외부 증강 3종(WINTER/NIGHT/RAIN, 외부 위탁)+저작도구 내부 해상도 변경 파생(RESOLUTION, SFR-06-03)으로 대체 |
| **프로젝트 배정 시 프레임 분할**(초당/분당/시간당) | [06](06-video-frame-pipeline.md#프레임-분할-배정) | 마킹 위치 기반 추출로 대체 |
| **이미지 자동 분류**(메타 기반 카테고리화) | [06](06-video-frame-pipeline.md#이미지-자동-분류) | 명시 기능 없음 |
| **영상/이미지 관리 + 사전 배정** UI | [06](06-video-frame-pipeline.md#영상이미지-프로젝트-배정) | 영상 목록/상세 + 작업 배정(REVIEWER→WORKER)로 재구성 |

> v1의 "양방향 송수신 인터페이스(II-001~007 데이터송신/수신 시스템)"도 v2에서는 **단방향 outbound 통지 + inbound 조회 API**로 바뀌었다(M2M 양방향 deprecated).

---

## 18.4 v2에만 있는 기능 (v1 미보유 — 신설)

> 모두 v2 코드/마이그레이션으로 실재 확인됨.

| v2 기능 | 근거 (코드/테이블) | 설명 |
|---------|-------------------|------|
| **마킹(Marking) — 자동/수동** | `marking/` 도메인, `LS_MARKING`(V45), `MarkingCompletedEvent`→`MarkingBatchBridge` | 비식별 영상에서 이벤트 시점 마킹(자동=프레임 간격, 수동=Space/Del/Enter). 완료 시 배치 자동 트리거 |
| **영상 스트리밍 마킹 화면** | `MarkingPage.tsx`, `GET /v1/videos/{rawSn}/stream`(HTTP Range) | 배속 0.25~4x 재생 + 마킹 |
| **외부 VLM 시계열 메타 연동** | `VlmClient`, `batch/step/VlmTimeseriesStep`, `webhook/VlmResultController`, `LS_DATA_META`/`LS_DATA_META_REVIEW` | 외부 VLM 호출(콜백) → 메타 적재 → REVIEWER 검토(`MetaReviewPage`) |
| **비식별 누락 신고** | `deident/`, `LS_DEIDENT_REPORT`(V21)·`LS_DEIDENT_PROC_LOG`(V29), `DE_IDNTF_YN='F'` | 작업 중 PII 노출 발견 시 신고 → 라벨 삭제 + 외부 재비식별 |
| **라벨 버전 스냅샷 + diff/rollback** | `version/`, `LS_LABEL_VERSION`(V24, `GITEA_CMT_HASH`/`ACTIVE_YN`) | 버전 비교·롤백. (코드: Gitea 커밋 해시 식별 / 설계: 페이로드 스냅샷) |
| **증강 검수 + 라벨 무결성 계산** | `augment/`, `LS_DATA_AUG_RVW`(V25)·`LS_DATA_AUG_LBL_MAP`(V26), `LabelIntegrityCalculator` | 외부 증강 결과를 검수(ACCEPTED/REJECTED), 원본 대비 라벨 무결성 검증 |
| **관제서버 단방향 통지** | `controlnotify/`, `LS_CONTROL_NOTIFY_FALLBACK`(V44), `ControlNotifyDebouncer` | 검수 완료 `TASK_COMPLETED` / 수정 `TASK_MODIFIED` push (디바운스 + Fallback 재시도) |
| **데이터마트 적재용 View** | `V_COMPLETED_VIDEO/FRAME/LABEL/LABEL_ATTR/META`(V52) | 검수 완료(APPROVED) 영상만 노출 → 관제서버가 SELECT |
| **웹훅 멱등성** | `webhook/`, `LS_WEBHOOK_IDEMPOTENCY`(V39), In-Memory/Persistent Ledger | 비식별/증강/VLM 결과 수신 중복 방지 |
| **CVAT 모듈 Java 포팅** | `batch/interpolation/TrackInterpolator`, `common/util/MaskRleConverter`·`PolygonSimplifier`·`YoloCocoConverter` | 트랙 보간(선형)·MASK↔RLE·Polygon 단순화(RDP)·YOLO/COCO 변환 |
| **SAM2 VOS + 마스크 도구** | `label/canvas/` `Sam2TrackTool`/`MaskBrushTool`/`MaskEraserTool` | SAM2 비디오 객체 추적·분할, 마스크 브러시/지우개 |
| **라벨 속성(다형 입력)** | `LS_LABEL_ATTR`(V33)·`LS_DATA_LBL_ATTR_VAL` | SELECT/CHECKBOX/RADIO/NUMBER/TEXT 속성 |
| **라벨 프리셋 + 이벤트 필터** | `preset/`, `LS_LABEL_PRESET`(V13), `PresetLabelLookupService` | 이벤트 유형별 라벨 자동 필터(BBOX/POLYGON 토글) |
| **시스템 설정(화이트리스트)** | `sysconfig/`, `LS_SYSTEM_CONFIG`(V11), Caffeine 60s | `YOLO_CONF_THRESHOLD`/`YOLO_IOU`/`YOLO_IMGSZ`/`POLYGON_SIMPLIFY_TOLERANCE` 등 정밀도 조절 |
| **해상도 변경 파생영상** | `LS_RESOLUTION_EXPORT`(V55)+`NEW_RAW_SN`(V122), `LS_RESOLUTION_LBL_MAP`(V122), RQ-SFR-06-03 | 2026-07-21부터 증강형 파생영상 전환 — 표준 3종(1080p/720p/480p) 새 RAW_SN 생성, 비디오 원본 복사+프레임 리스케일(업스케일 허용), 라벨 좌표 배율 재계산 제공(구 '다운스케일만 허용' 폐기) |
| **배치 재시도 큐** | `BatchRetryQueue`, `BatchRetryQuartzJob` | 실패 영상 재처리 |
| **AI 라벨 출처/신뢰도 추적** | `LS_DATA_LBL_AI_INFO`(V23, `CONF_SCORE`) | YOLO/SAM2/VLM 출처·신뢰도 기록 |
| **포털(데이터마트 영상 선택)** | `portal/`, `LS_PORTAL_USER_LABEL`(V47), `PortalLabelingPage` | 포털 사용자 라벨 별도 적재(원본·마트 미수정). 업로드/오토라벨/검수 없음(ADR-013) |
| **관제 통지 조회 API** | `controlnotify/TaskQueryController` | 관제서버가 통지 수신 후 상세 조회 |

---

## 18.5 공통 기능 — 방식 차이

| 기능 | v1 방식 | v2 방식 |
|------|---------|---------|
| **오토 라벨링** | YOLO 내장(PF-006), 일반/트랙 버튼 | YOLO를 ai-server에서 **원본만** 실행, 결과 비식별본과 공유. 프리셋 필터 |
| **클릭 세그멘테이션** | SAM(AI Tool) 클릭 | **SAM2** segment/track, 마스크+폴리곤 |
| **트랙 보간** | 선형보간 + '트래킹' 버튼(클라이언트) | **CVAT 알고리즘 Java 포팅**(`TrackInterpolator`), 배치 단계 |
| **비식별화** | 작업자가 캔버스에서 블러(다른 라벨 전 우선) | **외부 비식별 서버 연동**(`DeidentifyClient`), 배치 파이프라인 + 누락 신고 |
| **검수** | 작업자→1차→2차→관리자 + 프레임 색상 | REVIEWER 단일 승인, 승인=완료→관제 통지 |
| **배정** | 관리자/담당자가 프로젝트 데이터 배정(작업자~검수자 한번에) | REVIEWER→WORKER 영상 단위 배정(`LS_TASK_ALTMNT`), 재배정 이력 |
| **배치 파이프라인** | FFmpeg 추출→전처리→라벨링 | 마킹→VLM→비식별→FFmpeg(2벌)→YOLO→SAM2→트랙 보간 (Quartz 1건/분) |
| **데이터 증강** | 밝기/반전 5종 내장 | 외부 증강 3종(WINTER/NIGHT/RAIN)+내부 해상도 변경 파생(RESOLUTION), **새 영상(`ORGNL_RAW_SN`)** 생성→검수 |
| **이력/버전** | `_HSTRY` 테이블 누적 | `LS_LABEL_VERSION` 스냅샷 + `LS_DATA_LBL_HSTRY` |
| **통계** | 프로젝트/권한별, 엑셀 다운로드 | 작업자/전체 통계(`stat/`), CSV 리포트(`KLID-AT-SC-021`) |
| **관리 화면 URL** | (역할별 메뉴) | `/manage/*` (사용자·시스템 설정·프리셋), REVIEWER 전용 |

---

## 18.6 화면 ID 매핑 (참고)

| v1 (`SKKLID-UI-*`) | v2 (`KLID-AT-SC-*`) | 비고 |
|--------------------|---------------------|------|
| 02-02-04~15 데이터 작업 | SC-005 라벨링 캔버스 | konva 재구현 |
| (신규) | SC-006 마킹 | v2 신설 |
| 02-02-16~19 검수 | SC-018/019 검수 목록/상세 | 단일 검수로 |
| 02-01-01 / 03-01-01 대시보드 | SC-011 대시보드 / SC-020·021 통계 | |
| 03-02-* 프로젝트 관리 | (없음) | 프로젝트 개념 폐기 → SC-012/013 작업 목록/배정 |
| 03-03-* 영상/이미지 관리 | SC-007/009 영상 목록/상세 | |
| 02-05-* 업로드/생성 | SC-022/023 증강 요청/결과 | 생성형 AI 내장 → 외부 증강 검수 |
| 03-04-* 데이터 관리 | (없음) | 범위 외 |
| 02-03 게시판 / 02-04 연습장 | (없음) | v2 범위 밖 |
| (신규) | SC-014/015 오토라벨 요약 / VLM 메타 검토 | v2 신설 |
| (신규) | SC-024/025/026 사용자·시스템·프리셋 관리 | v2 신설 |
| (신규) | SC-029 포털 라벨링 | v2 신설 |

> v2 deprecated: SC-016/017(비식별 목록/상세 → 외부 솔루션), SC-028(포털 홈 → ADR-013).

---

## 18.7 범위 변화 요약 (v1 내장 → v2 외부)

```
v1: [저작도구] 라벨링 + 검수 + 생성형AI + 증강 + 데이터마트 + Export + (침수모델 학습)
                                  │
v2: [저작도구] 마킹 + 라벨링 + 검수 + 버전관리 + 외부증강 검수 + 외부VLM 검토 + 포털 라벨
    [외부 시스템] ← 생성형AI 본체 · VLM 모델 본체 · 영상합성 모델 · 데이터마트 · Export
```

v2 저작도구는 **"라벨링·검수·버전관리까지"**로 책임 범위를 좁히고, 그 너머(생성·마트·Export·모델 학습)는 외부 시스템과의 **연동(호출/통지/검수)**으로 처리한다.

> v1↔v2 핵심 차이 요약표는 [17 §17.4](17-source-documents.md#174-v1--v2-핵심-차이-요약)에도 있음.
