# 18. v1 ↔ v2 비교 (차이점 · v1 전용 · v2 전용 기능)

> 본 페이지는 **v1(SweetK 구축, 본 위키 01~17)**과 **v2(현재 `klid-label` 재구현)**를 비교한다.
>
> **v1 근거**: 1차 원본 PDF 3종 재추출본(2026-08-19 원문 재대조) — `01-UIUX설계서.txt`(화면정의서
> Storyboard v0.7, 화면 ID `SKKLID-UI-NN-NN-NN`) · `02-관리자매뉴얼.txt`(학습/저작도구 시스템 매뉴얼
> (관리자) Rev.1.0 — 표지·러닝헤더는 `1.0`이나 내부 제·개정이력에 `1.1(2025.11.12, 단축키 안내 추가)`
> 행이 병기됨) · `03-UI설계서V1.1.txt`(KLID-AI-사용자 인터페이스 설계서 D2 V1.1, 화면 ID
> `KLID-AI-SC-001~020`). 보조 참고는 `docs/v1-wiki/sources/` 기존 분석 11종(진실원 아님).
>
> **v2 근거**: 2026-08-19 실제 코드 실측 — `backend/src/main/java/kr/co/cudo/authoring/`(도메인 패키지
> 27개, 컨트롤러 57개, REST 엔드포인트 178건) · `backend/src/main/resources/db/migration/`
> (`V1__baseline.sql` + `V2`~`V13`) · `frontend/src/` · `ai-server/app/routers/` +
> `reports/wiki-align-20260819/facts/*.md`(F1~F5).
>
> 정본은 루트 [`CLAUDE.md`](../../CLAUDE.md). 본 페이지는 비교 요약이며, 세부 동작이 충돌하면 v2 코드가 우선.
>
> ⚠ **구 서술 폐기(2026-08-19 실측)** — *"v2 근거: 실제 코드 조사(... 22개 도메인, `db/migration/`
> V0~V55, ..., `docs/design/` R1~R3·D1~D9) 기준 — 2026-06 시점"* · *"정본은 루트 CLAUDE.md·`docs/design/`"*
> 는 낡았다. ① 도메인 패키지는 22개가 아니라 **27개**(`ls -d backend/src/main/java/kr/co/cudo/authoring/*/ | wc -l`).
> ② 마이그레이션은 `V0~V55`가 아니라 **2026-08-13 스쿼시** 이후 `V1__baseline.sql`+`V2`~`V13`(13개 파일)이다
> (구 180개 이력은 `backend/src/test/resources/db-archive/migration/`으로 이관, Flyway 실행 대상 아님).
> ③ **`docs/design/`은 2026-08-15에 동결**됐다 — CBD 납품 산출물(R1~R3·D1~D9, `KLID_AT_*.md` 11종)이
> `docs/archive/frozen-20260815/design/`로 이관됐고, 현재 `docs/design/`에는 **LogiCraft 구현 키트
> 14개 도메인 디렉터리만** 있다(`docs/design/*-DOMAIN-*/`). 근거: `docs/archive/frozen-20260815/README.md`,
> `find docs/design -maxdepth 1`. R1~R3·D1~D9 문서 자체는 동결본으로 여전히 존재하나 **정합 판정 근거로
> 재인용하지 말 것**(루트 CLAUDE.md 「문서 동기화 규칙」 구속).

---

## 18.1 한눈에 보는 관계

- **v1** = AI CCTV 관제 학습 저작도구 (**1차 PDF 3종에 Python/FastAPI·MySQL·GPKI 등 기술 스택 서술
  0건** — 이 절의 기술 스택 서술은 `docs/v1-wiki/sources/` 2차 분석본 기준이며 1차 원문으로 확인되지
  않았다. 확실히 원문으로 확인된 것은 **프로젝트 단위 관리** · CVAT류 자체 라벨링 캔버스다) · **프로젝트 단위**
- **v2** = 동일 목적의 **재구현** (Spring Boot Java 17 + ai-server FastAPI · PostgreSQL · **영상 1건 단위** · 관제/포털 JWT 인계 · CVAT 모듈 Java 포팅)

v2는 v1의 라벨링/검수 핵심은 계승하되, **작업 단위·아키텍처·범위**를 크게 재설계했다. 특히 v1이 내장하던 **생성형 AI·데이터마트·VLM/영상합성 모델 본체**는 v2에서 **외부 시스템 책임(범위 외)**으로 분리됐고, 대신 v2는 **마킹·외부 VLM 연동·비식별 누락 신고·버전 스냅샷·관제 통지** 등을 신설했다.

> ⚠ **구 서술 폐기(2026-08-19, ADR-020)** — *"v1이 내장하던 ... 데이터마트·Export·VLM/영상합성 모델
> 본체는 v2에서 외부 시스템 책임(범위 외)으로 분리"* 중 **Export(학습데이터셋 산출)는 사실과 다르다.**
> **export(NIA JSON) 산출은 저작도구(v2) 범위 안**이다 — 근거 ADR 이 `superseded`된 ADR-005 에서
> **ADR-020**(검수 승인 학습데이터 export 산출을 저작도구 범위로 포함)으로 뒤집혔다. 범위 외인 것은
> **데이터마트 구축·검색·다운로드**(외부 제공 시스템 책임)뿐이다. 상세는 §18.3·§18.5.

---

## 18.2 기술 · 구조 차이

| 구분 | v1 | v2 |
|------|----|----|
| 백엔드 | Python 3.9 / FastAPI — ⚠ **1차 PDF 3종 전수 grep 0건, 원문 미확인**(`sources/` 분석본 기준) | **Spring Boot 3.3 (Java 17)** + ai-server(FastAPI, 추론 전용). 도메인 패키지 **27개**·컨트롤러 **57개**·REST 엔드포인트 **178건**(2026-08-19 실측) |
| DB | MySQL 8.0 / InnoDB — ⚠ **1차 PDF 원문 미확인** | **PostgreSQL** / `klid_at` 스키마 |
| 스키마 소유 | 단일 시스템 소유 — ⚠ **1차 PDF 원문 미확인** | 저작도구 **`ls_*`** 테이블 57개 자체 소유 + Quartz **`qrtz_*`** 11개(총 **68개** 테이블, 2026-08-19 실측). ⚠ 구 "관제 `MNG_*` 9종 `validate` 참조"는 **폐기** — 관제 공유 테이블 `MNG_*`는 **DROP**됐다(스쿼시 이전 이력의 `V167`, 회귀 가드 `MngControlMasterTableRemovalTest`가 JPA 매핑·SQL 참조 0건을 고정). 관제는 이제 저작도구 소유 `LS_DATA_INGEST`에 **직접 INSERT**하고 저작도구 주기 배치가 폴링한다(ADR-042) |
| 마이그레이션 | - | **Flyway** — `V1__baseline.sql`(구 V0~V185 180개 이력을 2026-08-13 스쿼시) + `V2`~`V13` = 실행 대상 **13개 파일**. ⚠ 구 "V0~V55, 70+ 테이블/뷰"는 **폐기**(스쿼시 이전 세션 시점 수치). 구 180개 이력은 `backend/src/test/resources/db-archive/migration/`에 원문 보존되나 Flyway 실행 대상이 아니다 |
| 캐시/큐 | Redis / RabbitMQ·Kafka — ⚠ **1차 PDF 원문 미확인** | **없음** (Caffeine 로컬 캐시 60s, Quartz JobStore) |
| 스케줄러 | 배치 프로그램 1회/분 — ⚠ **1차 PDF 원문 미확인** | **Spring Boot Quartz**(PostgreSQL JobStore). ⚠ 구 "단일 인스턴스"는 **폐기** — **2노드 Active-Active** 배포 + Quartz **클러스터링** 적용. stg/prd는 클러스터링이 꺼져 있으면 `QuartzClusteringGuard`가 `@PostConstruct`에서 기동을 거부하는 **fail-closed** |
| 인증 | GPKI 로그인(자체 로그인 UI) — ⚠ **"GPKI" 문자열은 1차 PDF 3종 전수 grep 0건**(`sources/` 분석본에만 근거). 1차 원문에서 확인되는 것은 "로그인 화면"의 존재뿐, 인증 방식 상세는 미확인 | **관제/포털 JWT 인계**(독립 로그인 UI 없음, `JwtAuthenticationFilter`) |
| 작업 단위 | **프로젝트** (`PJT_SN`) | **영상 1건** (`LS_DATA_RAW.RAW_SN`) |
| 라벨 캔버스 | 자체 구현(1차 원문 화면정의서 `SKKLID-UI-02-02-*` 계열로 확인) | **konva.js** (CVAT canvas-drawing 포팅) |
| AI 추론 위치 | PF-005/006 내장 모듈 — ⚠ **1차 PDF 원문 미확인**(PF-00N 서브시스템 표기 자체가 `sources/` 분석본에만 등장) | **ai-server** stateless 분리(`AiServerClient`, Resilience4j) |
| 탐지 백엔드 | ⚠ **1차 PDF 3종 전수 grep 결과 YOLO/SAM 등 AI 모델명 0건** — `sources/` 분석본 기준, 원문 미확인 | **YOLOX(onnxruntime) 단독** + SAM2(분할) + ByteTrack(추적). ⚠ 과거 v2 자체 검토안 "RT-DETRv2(transformers) 선택 가능"은 **제거**됐다 — `transformers`·`ultralytics` 미사용, `detector_backend` 설정 분기 없음(`ai-server/tests/test_yolo_dispatch.py`가 회귀 가드) |
| 외부 연동 회복성 | - | **Resilience4j**(타임아웃/재시도/서킷) + Fallback 큐 + Idempotency |
| 화면 ID 체계 | `SKKLID-UI-NN-NN-NN` — 1차 원문 전수 실측(2026-08-19, 독립 2회 일치) **고유 70개**(구 "66개"는 폐기). 첫 XX=권한구분값(공통/라벨러/관리자), 둘째=메뉴구분, 셋째=하위 페이지. 별도로 `03-UI설계서V1.1.txt`의 `KLID-AI-SC-001~020`(20개) 체계가 **공존**(스토리보드와 별개 산출물, 혼동 금지) | 코드의 1차 식별자는 **`SCREEN-NNN`**(코드 참조 고유값 **32개**, 결번 007·013~017 — 2026-08-19 실측: `grep -rhoa "SCREEN-[0-9]{3}" frontend/src \| sort -u \| wc -l`). ⚠ 구 "`KLID-AT-SC-NNN`(23개, deprecated 3 제외)"는 **폐기** — 그 체계는 페이지 파일 29개 중 `NoticeListPage.tsx`·`NoticeDetailPage.tsx` **2개에만** 잔존, deprecated 분류 자체도 부정확(§18.6 참조) |
| 요구사항 체계 | SFR-06/07, RQ-SFR-10-* — ⚠ **1차 PDF 원문 미확인** | **RQ-SFR-06~09 + NFR**, R1~R3 추적표, D1~D9 설계서 — ⚠ 이 CBD 납품물은 2026-08-15 `docs/archive/frozen-20260815/design/KLID_AT_*.md`로 **동결 이관**됐다(판정 근거로 재인용 금지, 위 헤더 참조) |

---

## 18.3 v1에만 있는 기능 (v2 미보유 / 범위 외)

> 이 중 **무엇을 v2에 추가할지** 결정용 체크리스트는 [19 v2 갭/마이그레이션 체크리스트](19-v2-gap-checklist.md) 참고.

| v1 기능 | v1 위치 | v2에서의 처리 |
|---------|---------|--------------|
| **프로젝트 단위 관리** (생성 5단계·완료/완료취소·프로젝트 통계) | [05](05-project-management.md) | 폐기 — v2는 영상 1건 단위. 프로젝트/메타·라벨·단계·권한 설정 개념 없음 |
| ~~**1차 / 2차 다단계 검수**~~ | [09](09-review-workflow.md) | [폐기 표기 — 서술 정정] 아래 참조 |
| **관리자 확인 요청 / 폐기** 흐름 | [09](09-review-workflow.md#94-관리자-확인-요청) | 없음 (REVIEWER가 반려로 처리) |
| **업로더 역할 + 생성형 AI 화면**(Text/Image→Image/Video 내장) | [12](12-generative-ai.md) | **범위 외** — 생성형 AI 본체는 외부 시스템. v2는 외부 증강 결과 검수만 |
| **데이터마트 등록 / 내보내기**(저작도구 내) | [11](11-augmentation-export.md) | [폐기 표기 — 서술 정정] 아래 참조 |
| ~~**학습데이터셋 Export**~~ | [11](11-augmentation-export.md) | [폐기 표기 — 서술 정정] 아래 참조 |
| **게시판 / 연습장** | [13](13-board-practice.md) | [폐기 표기 — 서술 정정] 아래 참조 |
| **GPKI 로그인** | [16](16-security.md) | JWT 인계로 대체. ⚠ "GPKI"는 1차 PDF 3종 원문 미확인(위 18.2 참조) |
| ~~**스켈레톤 / 키포인트 라벨**(포인트 정의 도구)~~ | [07](07-labeling-tools.md#스켈레톤-skeleton) | [폐기 표기 — 서술 정정] 아래 참조 |
| **증강 5종**(밝게/어둡게/좌우반전 ±2) 내장 처리 | [11](11-augmentation-export.md) | 외부 증강 3종(WINTER/NIGHT/RAIN, 외부 위탁)+저작도구 내부 해상도 변경 파생(RESOLUTION, SFR-06-03)으로 대체. §18.5 "데이터 증강" 참조 |
| ~~**프로젝트 배정 시 프레임 분할**(초당/분당/시간당)~~ | [06](06-video-frame-pipeline.md#66-프레임-분할-배정) | [폐기 표기 — 서술 정정] 아래 참조 |
| **이미지 자동 분류**(메타 기반 카테고리화) | [06](06-video-frame-pipeline.md#63-이미지-자동-분류) | 명시 기능 없음(v2 코드에 대응 기능 없음, 2026-08-19 재확인) |
| **영상/이미지 관리 + 사전 배정** UI | [06](06-video-frame-pipeline.md#65-영상이미지-프로젝트-배정) | 영상 목록/상세 + 작업 배정(REVIEWER→WORKER)로 재구성 |

> v1의 "양방향 송수신 인터페이스(II-001~007 데이터송신/수신 시스템)"도 v2에서는 **단방향 outbound 통지 + inbound 조회 API**로 바뀌었다(M2M 양방향 deprecated). ⚠ 이 인터페이스 규격서 자체는 **1차 PDF 3종 세트 밖**(`sources/` 분석본에만 근거) — 원문 미확인.

### ★ 정정 1 — 검수 단계는 "1차/2차 다단계"가 아니라 **프로젝트별 설정값**이다

> ⚠ **구 서술 폐기(2026-08-19 원문 재실측)** — *"1차 / 2차 다단계 검수 → 단일 REVIEWER 승인으로
> 단순화"*는 v1 실제와 다르다.

`02-관리자매뉴얼.txt`(프로젝트 생성 — 단계 설정, 11p) 원문:

> "데이터 작업은 '1단계의 가공 → 1차 검수 → 2차 검수 → 2단계의 가공 → 1차 검수 → 2차 검수'로
> 진행됩니다. 기본 데이터 작업 프로세스는 '1단계의 가공 → 1차 검수'이며, 더 짧게 줄이는 것은
> 불가능합니다. '검수 추가' 버튼을 클릭하면 검수 과정을 추가할 수 있으며, 검수는 최대 2단계까지
> 설정 가능합니다. '단계 추가' 버튼을 클릭하면 가공 단계를 추가할 수 있으며, 단계 또한 최대
> 2단계까지 구성할 수 있습니다."

즉 v1의 검수 체계는 **고정 "1차→2차→관리자"가 아니라 프로젝트 생성 시 관리자가 정하는 설정값**이다
— **최소 = 가공 1단계 → 1차검수**, **최대 = 가공 2단계 × 각 단계 1차·2차검수**(조합에 따라 최대 6단계
흐름). 또한 §4.3.12(라벨링 작업 완료 후 제출)에 검수자의 **프레임 단위 승인/반려** 게이트가 있고,
§3.2.1(프로젝트 완료 처리)에 관리자가 프로젝트 전체를 잠그는 **완료/완료취소** 게이트가 별도로 있다
(사용자 확인 필요 시 재작업 요청). 근거: `02-관리자매뉴얼.txt`(프로젝트 생성 단계설정, §3.2.1, §4.3.12).

**v2에서의 처리**: 이 다단계 설정값 자체는 폐기 — v2는 **단일 REVIEWER 승인**으로 단순화한다(구
서술의 결론은 유지). 데이터 상태값도 v1엔 `변환대기 → 변환중 → 변환오류/배정대기`(원문: "데이터
상태는 '변환 대기', '변환중', '변환오류', '배정대기' 중 하나의 상태로 확인가능", `02-관리자매뉴얼.txt`
§5.2)가 있었으나 v2는 `PENDING → MARKING_READY → COMPLETED`(배치 축) / `PENDING → ASSIGNED → ...`
(검수 워크플로 축, 루트 CLAUDE.md 「배치 상태 전이」 참조)로 재설계됐다.

### ★ 정정 2 — Export(학습데이터셋 산출)는 v1 전용이 아니라 v2도 자체 구현한다

> ⚠ **구 서술 폐기(2026-08-19, ADR-020)** — *"학습데이터셋 Export | [11] | 범위 외"*는 **사실과 다르다.**

**export(NIA JSON) 산출은 v2 저작도구 범위 안**이다. 근거 결정이 뒤집혔다 — 구 근거였던 ADR-005는
`superseded`이고 **ADR-020**(검수 승인 학습데이터 export 산출을 저작도구 범위로 포함)이 대체했다.
v2는 검수 승인(`APPROVED`) 시점에 영상 단위로 `v{n+1}` 전량(이미지 2벌+JSON) 재생성한다(루트
CLAUDE.md 「★ export 재생성·동기화 정책」). 클래스: `backend/src/main/java/kr/co/cudo/authoring/dataset/export/`
(36개 파일). v1의 방식(§11)과는 트리거·저장 모델이 다르지만 **"v2에는 없다"는 결론 자체가 틀렸다.**

**"데이터마트 등록 / 내보내기"** 행도 두 개념이 섞여 있었다 — **데이터마트 구축·검색·다운로드는
여전히 외부 제공 시스템 책임**(v2 범위 외, 근거 유지)이지만, **그 마트가 픽업할 export 산출물을
만드는 것은 v2 몫**이다(위 단락). v2는 `V_COMPLETED_*` View 4종(§18.4) 노출 + export 산출 폴더 경로를
관제에 통지하는 것까지 담당한다.

### ★ 정정 3 — 게시판은 v1 전용이 아니다, v2에 이미 있다 (연습장만 v1 전용)

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"게시판 / 연습장 | [13] | v2 범위에 없음"*은 **게시판
> 절반이 틀렸다.**

**게시판(공지)은 v2에 실재한다** — `notice` 도메인(14개 .java 파일, `NoticeController`), 화면
`SCREEN-030`(공지 목록)·`SCREEN-031`(공지 상세)·`SCREEN-036`(공지 작성)·`SCREEN-037`(공지 수정),
라우트 `/notice`·`/notice/new`·`/notice/:id/edit`. REVIEWER가 작성·수정하고 전 역할이 열람한다.
**연습장(practice pad)만 v2 코드에 대응 기능 0건**(2026-08-19 확인: `연습장`/`practice` 문자열 검색
결과 backend/frontend 전체 0건) — 이 부분만 "v2에 없음"이 유효하다.

v1(§13)의 게시판은 "관리자가 올린 공지/가이드라인 **확인 채널**"(열람 중심)이었던 반면, v2의 공지는
REVIEWER 작성·수정 권한을 갖춘 CRUD형 공지 게시판이다 — 방식 차이는 §18.5에 별도 행으로 정리.

### ★ 정정 4 — 스켈레톤/키포인트: v1은 "정의 도구", v2는 "고정 COCO-17 프리셋"

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"스켈레톤 / 키포인트 라벨(포인트 정의 도구) → v2 라벨
> 타입은 BBOX/POLYGON/POINT 중심. 스켈레톤 골격 정의 도구 없음"*은 **더 이상 사실이 아니다.**

v2는 라벨 마스터 형태 코드에 **`POINT`·`SKELETON`이 실재**한다(`frontend/src/features/label/constants/labelTypes.ts`
`TYPE_LABEL`). 라벨링 캔버스에 **17점 COCO 키포인트 순차 배치 도구**(`KeypointGuide.tsx`, 단축키
`K`)가 구현돼 있고, `KEYPOINT_NAMES`·`COCO_SKELETON`(`features/label/types.ts`)이 관절 이름·연결선을
정의한다. 다만 **v1의 "포인트 정의 도구"(임의 개수·배치의 스켈레톤 템플릿을 프로젝트마다 새로
정의하는 도구, `02-관리자매뉴얼.txt` 라벨 생성 정보 — "라벨 형태(바운딩박스/폴리곤/스켈레톤)"
선택 시 "위치 이동"·"선 그리기"·"삭제"로 스켈레톤을 그리는 관리자 도구)와 v2의 방식은 다르다** —
v2는 **고정 COCO-17 프리셋 하나**만 제공하고(임의 관절 수·배치를 정의하는 UI 없음), 라벨 마스터가
`SKELETON` 형태로 등록되면 이 고정 프리셋이 적용된다. 즉 "스켈레톤 라벨을 붙일 수 있는가"는 v2도
**가능**하지만, "스켈레톤 템플릿 자체를 자유롭게 정의하는가"는 v1만 가능하다 — 이 나뉜 사실을
"v2에 스켈레톤이 없다"로 뭉뚱그리지 말 것.

### ★ 정정 5 — 프로젝트 배정 시 프레임 분할: "자동 배치"가 아니라 "관리자 FPS 수동 설정"

> ⚠ **구 서술 폐기(2026-08-19 원문 재실측)** — 행 제목 "프로젝트 배정 시 프레임 분할(초당/분당/
> 시간당)" 자체는 원문과 부합하나, 이 프로젝트의 다른 문서(§06 등)가 이를 **FFmpeg 자동 배치**처럼
> 서술한 지점이 있어 함께 정정한다.

`02-관리자매뉴얼.txt` §5.2(영상/이미지 프로젝트 배정) 원문:

> "특정 영상/이미지의 '체크박스' 선택(중복 선택 가능) → '프로젝트 배정' 클릭 시 ... '프레임 분할
> 기준' 팝업이 노출됩니다. ... 분할 기준 설정 — 분할 단위: 프레임 분할할 단위 선택(초당, 분당,
> 시간당, **전체** 중 선택) / 프레임 수: 프레임 수 작성(숫자만 입력 가능). ... '업로드' 버튼 클릭
> 시 영상은 프레임 분할 과정을 거쳐 프로젝트에 배정됩니다."

즉 v1의 프레임 분할은 **FFmpeg가 정해진 규칙으로 자동 실행하는 배치가 아니라, 관리자가 영상을
프로젝트에 배정하는 시점에 분할 단위(초당/분당/시간당/전체)와 프레임 수를 직접 입력하는 수동
설정**이다. **v2에서의 처리**: v2는 이 수동 분할 자체를 폐기하고 **마킹 위치 기반 추출**(작업자가
마킹한 이벤트 시점 주변만 원본+비식별 2벌 추출)로 대체한다 — 이 결론은 유지된다.

---

## 18.4 v2에만 있는 기능 (v1 미보유 — 신설)

> 모두 v2 코드/마이그레이션으로 실재 확인됨(2026-08-19 재확인).
> ⚠ 아래 표의 **버전 번호(V##)는 2026-08-13 스쿼시 이전 세션 시점 번호이며 현재 마이그레이션
> 목록(`V1__baseline.sql`+`V2`~`V13`)과 무관하다** — 해당 스키마 변경은 전부 `V1__baseline.sql`
> 안에 흡수돼 있다. 표·클래스명은 유효하므로 남기되, 버전 번호로 현재 파일을 찾으려 하지 말 것.

| v2 기능 | 근거 (코드/테이블) | 설명 |
|---------|-------------------|------|
| **마킹(Marking) — 자동/수동** | `marking/` 도메인, `LS_MARKING`, `MarkingCompletedEvent`→`MarkingBatchBridge` | 비식별 영상에서 이벤트 시점 마킹(자동=프레임 간격, 수동=Space/Del/Enter). 완료 시 배치 자동 트리거. 화면 `SCREEN-006` |
| **영상 스트리밍 마킹 화면** | `MarkingPage.tsx`(`@design SCREEN-006`), `GET /v1/videos/{rawSn}/stream`(HTTP Range) | 배속 0.25~4x 재생 + 마킹 |
| **외부 VLM 시계열 메타 연동** | `VlmClient`, `batch/step/VlmTimeseriesStep`, `webhook/VlmResultController`, `LS_DATA_META`/`LS_DATA_META_REVIEW` | 외부 VLM 호출(`POST /v1/videovlm/verify`, 논블로킹 제출) → 콜백 결과(`{accuracy, description}`) 적재 → REVIEWER 검토 |
| **비식별 누락 신고** | `deident/`, `LS_DEIDENT_REPORT`·`LS_DEIDENT_PROC_LOG`, `DE_IDENT_YN='F'` | 작업 중 PII 노출 발견 시 신고(마킹/라벨링 2단계) → 작업락 + 라벨은 **보존**(구 "라벨 삭제" 정책은 2026-07-27 폐기) + 외부 수동 재비식별 후 resolve |
| **라벨 버전 스냅샷 + diff/rollback** | `version/`, `LS_LABEL_VERSION`(`VERSION_HASH` SHA-256, `ACTVTN_YN`) | 검수 승인(`APPROVED`) 시점 라벨 전체 스냅샷. ⚠ 구 "`GITEA_CMT_HASH`(코드: Gitea 커밋 해시 식별)"는 **폐기** — 현재 컬럼은 `version_hash`(페이로드 SHA-256)뿐, Gitea 흔적은 코드·스키마 전체 **0건**(2026-08-19 grep 재확인). 롤백도 구 "새 active 버전 적층"이 아니라 **대상 스냅샷 행 재활성**(멱등 롤백은 no-op, 적층 아님) |
| **증강 검수 + 라벨 무결성 계산** | `augment/`, `LS_DATA_AUG_RVW`·`LS_DATA_AUG_LBL_MAP`, `LabelIntegrityCalculator` | 외부 증강 결과를 검수(ACCEPTED/REJECTED), 원본 대비 라벨 무결성 검증. ⚠ 구 "요청 1회=파생영상 1건(409 차단)" 정책은 **폐기** — 동일 (영상×증강종류) 재요청을 차단하지 않는다(2026-07-31 확정, 생성 결과가 매번 달라 재요청이 정당한 동선이라는 사용자 판단) |
| **파생영상 폐기 스윕**(신규 보강) | `augment/service/AugmentDiscardPurgeSweeper` | REVIEWER가 증강 결과를 반려하면 유예기간(기본 7일) 경과 후 DB 행+생성 파일을 **실삭제**. 조건부 UPDATE 원자 클레임(2노드 Active-Active 레이스 방지), `NEW_RAW_SN IS NOT NULL`인 신규 파생만 대상(그랜드퍼더링 파생은 제외) |
| **트랙 편집/병합**(신규 보강) | `label/service/TrackEditService`·`TrackMergeService`, `TrackEditController`·`TrackMergeController` | 라벨링 화면에서 트랙 번호 재부여·트랙 병합. 「검수 완료 후 콘텐츠를 고치면 무조건 재검수」 정책의 대상 경로 중 하나 |
| **재검토 표식(REVLT_YN)**(신규 보강) | `LS_RAW_DATA_STATUS.REVLT_YN`, `assignment/service/ReviewApprovalGate`, `label/event/DeidentStageResumeEvent` | 승인 후 콘텐츠 수정 시 상태를 되돌리지 않고 "재검토 필요" 표식만 세운다. 재승인까지 관제 통지(`TASK_MODIFIED`) flush를 보류하고, 재승인 시점에 표식 해제 + 축적분 일괄 통지 |
| **메타 복제 아웃박스(포털향)**(신규 보강) | `LS_META_REPL_OUTBOX`, `dataset/config/MetaReplicationQuartzJob`, `dataset/worker/MetaReplicationWorker`·`PortalMetaReplicaWriter` | 저작도구(control DB) → 포털 DB 단방향 메타 복제(at-least-once, Quartz 잡). 포털은 이 복제를 읽는 쪽이며, 포털 DB를 저작도구가 직접 읽지 않는다(§18.5 "포털" 참조) |
| **관제서버 단방향 통지** | `controlnotify/`, `LS_CONTROL_NOTIFY_FALLBACK`, `ControlNotifyDebouncer` | 검수 완료 `TASK_COMPLETED`(필수 9필드+선택 1필드) / 수정 `TASK_MODIFIED`(변경 파일명 목록만) push (디바운스 + Fallback 재시도) |
| **데이터마트 적재용 View** | `V_COMPLETED_VIDEO`(30컬럼)·`V_COMPLETED_FRAME`(9컬럼)·`V_COMPLETED_LABEL_CHANGE`(8컬럼)·`V_COMPLETED_META`(11컬럼) — **4종** | 검수 완료(`APPROVED`) 영상만 노출 → 관제서버가 SELECT. ⚠ 구 "`V_COMPLETED_VIDEO/FRAME/LABEL/LABEL_ATTR/META`"는 **폐기** — 라벨 본문 뷰(`V_COMPLETED_LABEL`·`V_COMPLETED_LABEL_ATTR`)는 제거되고 변경점만 보여주는 `V_COMPLETED_LABEL_CHANGE`로 대체됐다(라벨 본문은 검수 승인 export 폴더 JSON에 있으므로 뷰로 중복 노출하지 않음) |
| **웹훅 멱등성** | `webhook/`, `LS_WEBHOOK_IDEMPOTENCY`, In-Memory/Persistent Ledger | 비식별/증강/VLM 결과 수신 중복 방지 |
| **CVAT 모듈 Java 포팅** | `batch/interpolation/TrackInterpolator`, `common/util/MaskRleConverter`·`PolygonSimplifier`·`YoloCocoConverter` | 트랙 보간(BBOX 선형+POLYGON polyshape, §18.5 참조)·MASK↔RLE·Polygon 단순화(RDP)·YOLO/COCO 변환. ⚠ **`MaskRleConverter`·`YoloCocoConverter`는 프로덕션 호출처 0건**(2026-08-19 `grep -rln` 재확인 — 자기 파일 정의 외 main 소스에서 참조 없음, 단위테스트만 존재). `PolygonSimplifier`는 `Sam2TrackService`·`AutolabelOnlineService`·`Sam2SegmentService`·`LabelService`·`VersionService`·`Sam2SegmentStep` 등에서 **실사용 중** |
| **SAM2 VOS + 마스크 도구** | `label/canvas/tools/Sam2TrackTool`·`MaskBrushTool`·`MaskEraserTool` | SAM2 비디오 객체 추적·분할, 마스크 브러시/지우개 |
| **라벨 속성(다형 입력)** | `LS_LABEL_ATTR`·`LS_DATA_LBL_ATTR_VAL` | SELECT/CHECKBOX/RADIO/NUMBER/TEXT 속성 |
| **라벨 프리셋 + 이벤트 필터** | `preset/`, `LS_LABEL_PRESET`·`LS_LABEL_PRESET_CODE`, `PresetLabelLookupService` | 이벤트 유형별 라벨 자동 필터. ⚠ 구 "형태(BBOX/POLYGON) 토글" 서술은 **폐기** — `ls_label_preset_code`에 `BBOX_ENABLED`/`POLYGON_ENABLED` 컬럼은 **더 이상 없다**(2026-08-19 실측, 스키마엔 `lbl_id` FK만). 형태는 이제 **라벨 마스터(`LS_LABEL.LBL_TYPE_CD`)에서 파생**하고 프리셋은 `labelId`만 참조한다(2026-07-23 이후 supersede) |
| **시스템 설정(화이트리스트)** | `sysconfig/`, `LS_SYSTEM_CONFIG`, Caffeine 60s | `YOLO_CONF_THRESHOLD`/`YOLO_IOU`/`POLYGON_SIMPLIFY_TOLERANCE` 등 정밀도 조절. ⚠ 구 "`YOLO_IMGSZ`" 키는 **폐기·삭제**됐다(`V13__drop_yolo_imgsz_config.sql` — 조정해도 ai-server YOLOX 로더가 입력 크기를 640×640으로 고정 사용해 실제로 반영되지 않던 죽은 설정면이었음, `ConfigKeys.ALLOWED`에서도 제외) |
| **해상도 변경 파생영상** | `LS_DATA_AUG`(`AUG_TYPE_CD='RESL_1080P'/'RESL_720P'/'RESL_480P'`)+`LS_DATA_AUG_LBL_MAP`, RQ-SFR-06-03 | 표준 3종(1080p/720p/480p) 새 RAW_SN 생성, 비디오 원본 복사+프레임 리스케일(업스케일 허용), 라벨 좌표 배율 재계산. ⚠ 구 "전용 테이블 `LS_RESOLUTION_EXPORT`(V55)+`LS_RESOLUTION_LBL_MAP`(V122)"는 **폐기** — 2026-07-22 저장모델이 증강과 완전 통합돼 전용 테이블은 백필 후 DROP됐다(판별자는 `AUG_TYPE_CD` 값, 신규 컬럼 없음) |
| **배치 재시도 큐** | `BatchRetryQueue`, `BatchRetryQuartzJob` | 실패 영상 재처리 |
| **AI 라벨 출처/신뢰도 추적** | `LS_DATA_LBL`(`AUTO_LBL_YN`/`CONF_SCORE`/`LBL_SRC_CD`) | YOLO/SAM2/VLM 출처·신뢰도 기록. ⚠ 구 "별도 테이블 `LS_DATA_LBL_AI_INFO`"는 **폐기** — 스쿼시 이전 세션에서 `LS_DATA_LBL`로 흡수·컬럼 통합됐다(분리 테이블 소멸, 2026-08-19 스키마 재확인) |
| **프레임 폐기(discard) 워크플로**(신규 보강) | `LS_DATA_SRC.DSCD_YN`, `label/service/LabelService(requireDiscardAllowed)`→`FrameDiscardApplier(apply)` | 라벨링 화면에서 개별 프레임을 학습데이터 산출 대상에서 제외(폐기)/복원. 단일 적용 지점(정적 가드로 고정). 검수 승인 이력이 한 번이라도 있는 영상은 신규 폐기·복원이 **400**으로 차단(데이터마트 롤백 정합성 근거) |
| **포털(데이터마트 영상 선택 + 본인 자산 업로드)** | `portal/`, `LS_PORTAL_USER_LABEL`·`LS_PORTAL_ULD` 등, `PortalLabelingPage`·`PortalUploadPage`(`SCREEN-028`·`SCREEN-033`·`SCREEN-034`) | 포털 사용자 라벨 별도 적재(원본·마트 미수정) + 본인 이미지/영상 업로드 후 수동 라벨링(ADR-013 예외). 오토라벨/검수/버전관리 없음 |
| **관제 통지 조회 API** | `controlnotify/TaskQueryController` | 관제서버가 통지 수신 후 상세 조회 |

---

## 18.5 공통 기능 — 방식 차이

| 기능 | v1 방식 | v2 방식 |
|------|---------|---------|
| **오토 라벨링** | YOLO 내장(PF-005/006, 1차 PDF 원문 미확인), 일반/트랙 버튼 | **YOLOX(onnxruntime) 단독**을 ai-server에서 **원본만** 실행, 결과 비식별본과 공유. 프리셋(라벨 마스터 `DTCT_TYPE_CD` COCO 매핑) 필터 |
| **클릭 세그멘테이션** | SAM(AI Tool) 클릭 — 1차 PDF 원문 미확인(모델명 grep 0건) | **SAM2** segment/track, 마스크+폴리곤 |
| **트랙 보간** | 선형보간 + '트래킹' 버튼(클라이언트) | **CVAT 알고리즘 Java 포팅**(`batch/interpolation/TrackInterpolator`), 배치 단계(`TrackInterpolationStep`). **BBOX 선형보간 + POLYGON polyshape 보간** 둘 다 배치 단계에 실배선(2026-08-19 코드 확인: `interpolate()`/`interpolatePolyshape(closed=true)` 양쪽 호출). **POLYLINE**(개곡선)은 알고리즘상 지원되나 **DB 코드값 자체가 아직 미도입**이라 실질적으로 쓰이지 않는다(주석: "POLYLINE 은 현재 DB 코드값 미도입") |
| **비식별화** | 작업자가 캔버스에서 블러(다른 라벨 전 우선) | **외부 비식별 서버 연동**(`DeidentifyClient`), 배치 파이프라인 선두 자동 실행 + 누락 신고(마킹/라벨링 2단계) |
| **검수** | 프로젝트별 설정에 따라 작업자→(1차)→(2차)→관리자 완료 게이트, 최소 1단계·최대 2단계×각 검수(§18.3 정정 1 참조) + 프레임 색상 | REVIEWER 단일 승인, 승인=완료→관제 통지 |
| **배정** | 관리자/담당자가 프로젝트 데이터를 프레임 분할 설정과 함께 배정(작업자~검수자 한번에, §18.3 정정 5 참조) | REVIEWER→WORKER 영상 단위 배정(`LS_TASK_ALTMNT`), 재배정 이력(`LS_TASK_EVNT_LOG`) |
| **배치 파이프라인** | 영상 등록 → **배정 시 관리자가 FPS 수동 설정해 분할**(초당/분당/시간당/전체) → 라벨링(**밝기/대비는 라벨러가 쓰는 수동 "이미지 조절 패널"**, `02-관리자매뉴얼.txt` §4.3.2 — 구 "이미지 자동 전처리(리사이징·밝기/대비 자동보정)" 서술은 이 절에서 폐기) → 검수(기본 1차, 프로젝트 설정에 따라 2차/2단계 추가) → 증강 → 프로젝트 완료 처리 | 비식별(선두 자동) → 마킹(자동/수동) → VLM 시계열(비동기 제출) → FFmpeg 프레임 추출(원본+비식별 2벌) → YOLO(원본만) → SAM2 → 트랙 보간 (Quartz, 2노드 Active-Active 클러스터링) |
| **데이터 증강** | 밝기/반전 5종 내장, 프로젝트 완료 라벨링 데이터 대상 일괄 처리(`02-관리자매뉴얼.txt` §3.2.2) | 외부 증강 3종(WINTER/NIGHT/RAIN)+내부 해상도 변경 파생(RESOLUTION), **새 영상(`ORGNL_RAW_SN`)** 생성→검수. 동일 (영상×종류) **재요청을 차단하지 않는다**(2026-07-31 확정 — 생성 결과가 매번 달라 재요청이 정당한 동선). 파생 깊이는 1로 고정(파생의 파생 생성 불가, 400) |
| **이력/버전** | `_HSTRY` 테이블 누적 | `LS_LABEL_VERSION` 스냅샷(`VERSION_HASH` SHA-256) + `LS_DATA_LBL_HSTRY`. 승인 시점에만 스냅샷 생성(라벨 저장마다 아님) |
| **통계** | 프로젝트/권한별, 엑셀 다운로드 | 작업자/전체 통계(`stats/`, `SCREEN-020`/`SCREEN-021`). ⚠ **CSV 리포트(`GET /v1/stats/report`)는 현재 placeholder** — `StatsController(report)`가 기간(period)과 무관하게 헤더 행 한 줄만 반환한다(2026-08-19 코드 확인, `docs/v1-wiki/19-v2-gap-checklist.md`에서 미구현으로 추적) |
| **관리 화면 URL** | (역할별 메뉴) | `/manage/*` (사용자·시스템 설정·프리셋·라벨 관리·이벤트유형 관리·비식별 신고 관리), REVIEWER 전용 |
| **라벨링 단축키**(신규 확인) | `02-관리자매뉴얼.txt` §4.3.1(31p)에 확정 단축키표 실재: `W/A/S/D`(프레임 첫/이전/끝/다음) · `Ctrl+S`(저장) · `Esc`(도구 닫기) · `Ctrl+Z`(실행취소, 비식별화 제외) · `Delete`/`R`(객체 삭제) · `Ctrl+C`/`V`(복사/붙여넣기) · `Ctrl+Shift+C`/`V`(전체 복사/붙여넣기) · `T`(라벨 표시/숨김) · `F`(폴리곤 점 추가) · `Q`(폴리곤 자동완료) · `Shift+클릭`(폴리곤 점 수정) · `Ctrl+클릭`(폴리곤 점 삭제) | `frontend/src/features/label/hooks/labelingKeymap.ts` 실측: **동일 키가 대부분 일치**한다 — `W/A/S/D` 프레임 이동, `Ctrl+S` 저장, `Escape` 도구 닫기(선택 도구로), `Ctrl+Z`/`Ctrl+Shift+Z`(실행취소/재실행), `Delete`·`R` 객체 삭제, `Ctrl+C`/`Ctrl+Shift+C` 복사(선택/전체), `Ctrl+V`/`Ctrl+Shift+V` 붙여넣기, `T` 라벨 표시/숨김, `F` 폴리곤 점 추가, `Q` 폴리곤 자동완료가 **그대로 이어졌다**. 이 정합은 이번 라운드 실측으로 확정됐다(⚠ 구 "1차 원문 미확인이라 미정합 상태"라는 판단은 폐기 — §4.3.1 원문이 실재하고 v2 구현과 대조 가능했다). ⚠ **미대조**: `Shift+클릭`/`Ctrl+클릭`(폴리곤 점 수정/삭제)은 마우스 인터랙션이라 이 키맵 파일에 없어 이번 라운드에서 캔버스 레이어 코드까지는 확인하지 못했다(확인 불가로 남김) |
| **게시판**(신규 확인) | "관리자가 올린 공지/가이드라인 **확인 채널**"(열람 중심, `02-관리자매뉴얼.txt` §9) | `notice` 도메인 — REVIEWER가 작성·수정 가능한 CRUD형 공지 게시판(`SCREEN-030`/`031`/`036`/`037`). §18.3 정정 3 참조 |

---

## 18.6 화면 ID 매핑 (참고)

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 아래 매핑은 v2 화면 ID 체계를 `KLID-AT-SC-NNN`으로
> 전제하고 있었으나, §18.2에서 정정했듯 코드의 1차 식별자는 **`SCREEN-NNN`**이다. 매핑값 자체도
> 세 군데가 어긋나 있었다(`SC-007`→`SC-008`, `SC-014/015` 결번, "`SC-028`(포털 홈)=deprecated" 오류).
> 아래 표는 `SCREEN-NNN`으로 갱신했다.

| v1 (`SKKLID-UI-*`) | v2 (`SCREEN-NNN`) | 비고 |
|--------------------|---------------------|------|
| 02-02-04~15 데이터 작업 | SCREEN-005 라벨링 캔버스 | konva 재구현. `LabelingPage.tsx` |
| (신규) | SCREEN-006 마킹 | v2 신설. `MarkingPage.tsx` |
| 02-02-16~19 검수 | SCREEN-018/019 검수 목록/상세 | 단일 검수로. `ReviewListPage.tsx`/`ReviewPage.tsx` |
| 02-01-01 / 03-01-01 대시보드 | SCREEN-011 대시보드 / SCREEN-020·021 통계 | `DashboardPage.tsx`/`WorkerStatPage.tsx`/`OverallStatPage.tsx` |
| 03-02-* 프로젝트 관리 | (없음) | 프로젝트 개념 폐기 → SCREEN-012 작업 목록(`TaskListPage.tsx`) |
| 03-03-* 영상/이미지 관리 | SCREEN-008/009 영상 목록/상세 | ⚠ 구 "SC-007 `/video/completed`"는 **폐기** — 그런 라우트는 존재하지 않는다. 실제는 **`SCREEN-008`**(`/video/status`, `VideoListPage.tsx`)/`SCREEN-009`(영상 상세, `VideoDetailPage.tsx`) |
| 02-05-* 업로드/생성 | SCREEN-022/023 증강 요청/결과 | 생성형 AI 내장 → 외부 증강 검수. `AugmentRequestPage.tsx`/`AugmentResultPage.tsx` |
| 03-04-* 데이터 관리 | (없음) | 데이터마트 구축·검색·다운로드는 여전히 범위 외(§18.3 정정 2 참조 — Export 산출 자체는 범위 안) |
| 02-03 게시판 / 02-04 연습장 | SCREEN-030/031/036/037 공지 목록/상세/작성/수정 | ⚠ 게시판은 v2에 실재(§18.3 정정 3). 연습장만 대응 없음 |
| (신규) | SCREEN-024/025/026 사용자·시스템·프리셋 관리 | `UserManagePage.tsx`/`SystemSettingsPage.tsx`/`PresetListPage.tsx` |
| (신규) | SCREEN-035 라벨 관리 / SCREEN-032 비식별 신고 관리 / SCREEN-038 이벤트유형 관리 | v2 신설. `LabelMasterManagePage.tsx`/`DeidentReportListPage.tsx`/`EventTypeManagePage.tsx` — 구 매핑표에 아예 없던 화면들 |
| (신규) | SCREEN-029 포털 라벨링 | v2 신설. ⚠ 코드에 직접 `@design SCREEN-029` 태그는 없고, 공유 컴포넌트(`FrameNavigator.tsx`/`CanvasOptionBar.tsx`) 주석의 교차참조("SCREEN-005 §캔버스 상단 옵션바 / SCREEN-029 동일 배치")로만 추정 가능(확인 불가 — 단정 금지) |
| (신규) | SCREEN-010 버전 로드 선택(모달) / SCREEN-027 (dev) 영상 업로드 | v2 신설. `StartVersionModal.tsx`/`DevAutolabelTestPage.tsx` |

> ⚠ **v2 deprecated 재정정**: 구 "SC-016/017(비식별 목록/상세 → 외부 솔루션), **SC-028(포털 홈 →
> ADR-013)**"에서 **SC-028은 틀렸다**. `PortalHomePage.tsx`가 `@design SCREEN-028`을 직접 달고
> `/portal` 라이브 라우트로 서빙 중이며 `features/portal/*`이 그 ID 아래 구현되고 있다 — **활성
> 화면이다.** ADR-013은 포털 업로드를 **추가**한 결정이지 포털 홈을 제거한 결정이 아니다.
> `SCREEN-016`/`017`(비식별 목록/상세 → 외부 솔루션 이관)은 코드 결번으로 재확인되어 deprecated
> 분류가 유지된다. **`SCREEN-013`/`014`/`015`도 코드 참조 0건**(오토라벨 요약/VLM 메타 검토로
> 추정되는 화면 — 현재 라벨링 캔버스의 "메타 탭"으로 흡수된 것으로 보이나, **LogiCraft ITEM 상의
> deprecated 여부는 이번 라운드에서 재확인하지 못했다**(확인 불가로 남김, 다른 담당·후속 라운드 대상).

---

## 18.7 범위 변화 요약 (v1 내장 → v2 외부)

```
v1: [저작도구] 라벨링 + 검수 + 생성형AI + 증강 + 데이터마트 + Export + (침수모델 학습)
                                  │
v2: [저작도구] 마킹 + 라벨링 + 검수 + 버전관리 + Export(NIA JSON 산출) + 외부증강 검수 + 외부VLM 검토 + 포털 라벨
    [외부 시스템] ← 생성형AI 본체 · VLM 모델 본체 · 영상합성 모델 · 데이터마트(구축/검색/다운로드)
```

> ⚠ **구 서술 폐기(2026-08-19, ADR-020)** — 위 다이어그램의 구판은 `v2: ... + 외부증강 검수 + ...`
> 줄에 **Export가 없고**, 아래 `[외부 시스템]` 줄에 **"데이터마트"만 있던 자리에 "· Export ·"가
> 함께 있었다.** §18.3 정정 2에서 설명했듯 **Export 산출은 v2 저작도구 몫**이라 위처럼 정정했다.

v2 저작도구는 **"라벨링·검수·버전관리·Export 산출까지"**로 책임 범위를 갖고, 그 너머(생성·데이터마트
구축/검색/다운로드·모델 학습)는 외부 시스템과의 **연동(호출/통지/검수)**으로 처리한다.
⚠ 구 "라벨링·검수·버전관리까지"(Export 제외) 서술은 위 이유로 폐기.

> v1↔v2 핵심 차이 요약표는 [17 §17.4](17-source-documents.md#174-v1--v2-핵심-차이-요약)에도 있음
> (⚠ 이 페이지 정정과의 정합 여부는 이번 라운드에서 확인하지 못함 — 17장은 다른 담당 범위).
