# 19. v2 갭 / 마이그레이션 체크리스트

> **목적**: v1에는 있고 v2에는 없는 기능을 점검하여, **무엇을 v2에 추가할지 / 추가하지 않을지**를 의사결정하기 위한 체크리스트.
> **전제**: v1 전용 기능을 전부 추가해야 하는 것은 **아니다**. 상당수는 v2에서 의도적으로 범위 외이거나 다른 방식으로 이미 대체됐다. 아래 분류로 구분한다.
> 근거: [18 v1↔v2 비교](18-v1-v2-comparison.md) · v2 코드 조사(2026-06) · 루트 [`CLAUDE.md`](../../CLAUDE.md).

## 분류 범례

| 표시 | 의미 | 조치 |
|:---:|------|------|
| 🟢 **대체됨** | v2가 다른 방식으로 이미 같은 목적 달성 | 추가 불필요 (확인만) |
| ⛔ **범위 외** | v2 설계상 외부 시스템 책임 | 추가 안 함 (의사결정 완료) |
| 🟡 **검토 후보** | v2에 없는 실제 갭. 도메인 요구에 따라 추가 검토 | **아래 체크리스트 대상** |

---

## 19.1 🟡 검토/추가 후보 (실제 갭 — 의사결정 필요)

> 추가 여부는 **요구사항(R1, `docs/design/`) 확정에 따름**. 아래는 "v1엔 있었으나 v2에 없음 → 필요하면 추가" 후보다. 각 항목 추가 전 R1 요구사항 매핑부터 확인할 것.
> **갱신(2026-07-14)**: [21 사용자 화면 가이드](21-user-screen-guide.md)의 화면 단위 세부(SC-003~018) + v2 BE/FE 코드 재조사로 신규 갭 다수 확인. 아래를 **P1~P3 우선순위 + BE/FE 실구현 상태**로 재정리한다. **제외 조건**(사용자 결정 2026-07-14): ⛔ 영상/이미지 업로드·AI 생성(관제/포털 담당) · ⛔ 프로젝트 관리(→ 영상관리로 대체) — 갭 대상 아님(§19.3).

### 🔴 P1 — 라벨링 생산성·정확도 직접 영향

- [ ] **이미지 조절 패널** — SC-005. 밝기/대비/투명도/작업(라벨) 투명도 슬라이더 전무(FE). 클라이언트 렌더링만이라 FE 단독 구현 가능.
- [ ] **라벨 복사/붙여넣기 (Ctrl+C/V·전체복사·좌표 +10 offset)** — SC-004. BE·FE 모두 없음(증강용 `LsDataLbl.copy`만 존재). 프레임 간 반복작업 핵심 편의.
- [ ] **트랙 번호 변경/머지 (merge/split)** — SC-004. `trackId` 저장·SAM2 Track 전파는 있으나 **병합/번호변경 로직·엔드포인트·UI 없음**. 가려졌다 재등장한 객체 연결 불가.
- [ ] **프레임 테두리 4색 체계** — 전 화면 공통(저장=연두/반려=주황/확인요청=빨강/현재=강조). FE는 현재+이슈 플래그 2색만(`DarkFrameStrip.tsx`). 작업 진행 가시성 핵심.
- [ ] **라벨링 단축키 정합** — 매뉴얼 Rev.1.1 확정셋(W/A/S/D·폴리곤 F/Q·R 삭제·T 표시숨김)과 v2 실장(화살표/Del/Ctrl+S)이 불일치. **정합 결정 필요**(차이표: [21 §21.9](21-user-screen-guide.md#219-크로스컷-규칙-전-화면-공통)).

### 🟡 P2 — 보조/편의

- [ ] **회전 / Fit(초기화) / 영역 확대 도구** — 서포트 도구. FE 줌만, 회전 하드코딩 미노출·Fit/드래그 확대 없음.
- [ ] **캔버스 그리드 오버레이** — 라벨링 도구. FE 없음(작은 편의).
- [ ] **객체 잠금/숨김 아이콘** — 객체/라벨 탭. FE 목록만, 잠금·숨김 토글 없음.
- [ ] **YOLO 오토라벨 수동 트리거 버튼** — SC-004. BE 배치는 구현, 라벨링 화면 툴바엔 SAM만 노출(YOLO 수동 버튼은 dev 페이지만).
- [ ] **커스텀 메타 라디오형 입력** — SC-006/011. 이미지 description·라벨 속성(SELECT/RADIO)은 구현, 프레임 메타의 사용자정의 라디오형 없음.
- [ ] **트랙 보간 POLYGON/POLYLINE** — BE BBOX simple 보간만. `TrackInterpolator.java:38` "후속 Phase" 미구현.
- [ ] **통계 CSV 리포트 실데이터** — `/v1/stats/report`가 헤더만 반환하는 placeholder(`StatsController.java:110`). 집계 로직 미구현.

### 🟢 P3 — 낮은 우선순위

- [ ] **이미지 자동 분류** — 메타(이벤트/시간/날씨/계절) 기반 프레임 자동 카테고리화. v2는 `TimeOfDaySeasonDeriver`(주야간/계절 파생)만, 메타 필터로 부분 대체 가능.
- [ ] **연습장 (라벨링 연습 환경)** — SC-016. BE·FE 전무. 신규 작업자 온보딩용([13](13-board-practice.md)).
- [ ] **프레임 폐기(discard) 워크플로** — 관리자 확인요청 처리의 "폐기". v2는 검수 반려로 수렴(부분 대체) — 프레임 단위 폐기 별도 미구현.

### ✅ 완료 (이전 후보 중 구현됨)

- [x] **라벨 클래스(마스터)·속성 정의 관리 화면** — ✅ **구현 완료 (2026-07-20)** — 1차 관리자매뉴얼 §4.1.3(라벨 생성)·§4.1.4(속성 설정) 요구. 그간 BE(`LabelMasterController`·`LabelAttrController`, `/v1/manage/labels/**`, REVIEWER)만 완비되고 FE 화면이 없어 DB 직접 조작으로만 가능하던 갭. 신규 화면 **SC-036 `/manage/labels`**(REVIEWER) — 라벨 마스터 CRUD(name·형태 BBOX/POLYGON/POINT/SKELETON·색상·정렬순) + 라벨별 속성 정의 CRUD(name·inputType SELECT/CHECKBOX/RADIO/NUMBER/TEXT·valuesJson·기본값·수정가능·정렬순). FE: `pages/manage/LabelMasterManagePage.tsx`·`components/LabelMasterFormModal.tsx`·`features/label/components/{LabelAttrDefPanel,LabelAttrFormModal}.tsx`·`api/{labelMaster,labelAttr}.ts`·`hooks/{useLabelMasterMutations,useLabelAttrs}.ts`, 라우트·LNB. 마스터 변경은 `LABEL_MASTER_KEYS` 무효화로 라벨링 캔버스 반영. **범위 제외**: 스켈레톤 포인트 정의 편집기(COCO-17 고정상수). → [v2-wiki 04](../v2-wiki/04-screens-ia.md) SC-036 · [D2](../design/D2-사용자인터페이스설계서.md) v1.4.
- [x] **키포인트/스켈레톤 라벨링 (17-keypoint COCO 포즈)** — ✅ **구현 완료 (2026-07-14, 4-Phase)** — BE: `common/util/{KeypointSerializer,KeypointPoint,KeypointSkeleton}.java`(삼중값 17×[x,y,v]·COCO-17 상수 17이름·19엣지), `LsDataLbl` SKELETON 타입, `LabelService` validate/serialize, `VersionService` 스냅샷/롤백/diff, `LabelMaster` SKELETON regex, `V_COMPLETED_LABEL` pass-through(IT). FE: konva 17점 배치·개별 드래그·스켈레톤 렌더·가시성(`canvas/layers/{LabelsLayer,OverlayLayer}.tsx`·`utils/keypointHelpers.ts`), 툴바 버튼·단축키(K)·serialize/undo 딥클론(`useLabelStore.ts`), `ObjectAttributePanel`. 커밋 `dc31e82`→`23a8cae`→`8b10cd0`→`4fa9e03`, 전 Phase QA GREEN. **범위 제외(후속)**: 프레임 간 보간/추적(Phase 6)·COCO-pose JSON 파일 조립(외부)·YOLO-pose 자동추정·포털(ADR-013). 설계: auto-memory `keypoint-labeling-design`.
- [x] **검수자 ↔ 작업자 이슈 소통 채널** — ✅ **구현 완료 (2026-06-05)** — `LS_DATA_ISSUE` 확장(INQUIRY·OPEN/ANSWERED/RESOLVED)+`LS_ISSUE_COMMENT`(V57), `/v1/videos/{rawSn}/issues`·`/v1/issues/{issueSn}/*` API, 이슈 스레드 탭(`IssueThreadPanel.tsx`). 상세: [v2-wiki 21](../v2-wiki/21-issue-channel.md). ※ 관리자 확인요청은 INQUIRY 타입으로 커버.
- [x] **게시판 (공지/가이드라인 배포)** — ✅ **구현 완료 (2026-06-05)** — `LS_NOTICE`/`LS_NOTICE_ATTACH`(V56), `/v1/notices*` API, 목록/상세/편집 화면. 상세: [v2-wiki 20](../v2-wiki/20-notice-board.md).

> **요약**: 잔여 후보를 P1 5종 + P2 7종 + P3 3종으로 재정리. **키포인트 라벨링은 2026-07-14 4-Phase 구현 완료**(BE+FE, 프레임 간 보간만 후속). 복사/붙여넣기·트랙 머지는 BE·FE 모두 신규 필요. 그 외 v1 기능은 §19.2·§19.3처럼 **이미 대체됐거나 범위 외**라 추가 대상이 아니다.

---

## 19.2 🟢 이미 대체됨 (추가 불필요)

| v1 기능 | v2 대체 방식 | 확인 |
|---------|-------------|:---:|
| 프로젝트 단위 관리 (생성 5단계·완료/취소·통계) | **영상 1건 단위**(`RAW_SN`) + 작업 배정 + 통계 화면(SC-020/021) | ✅ |
| GPKI 로그인 | 관제/포털 **JWT 인계**(`JwtAuthenticationFilter`) | ✅ |
| 영상/이미지 관리 + 사전 배정 | 영상 목록/상세(SC-007/009) + REVIEWER→WORKER 작업 배정(`LS_TASK_ASSIGNMENT`) | ✅ |
| 프로젝트 배정 시 프레임 분할(초당/분당/시간당) | **마킹 위치 기반 추출** + FFmpeg(원본+비식별 2벌) | ✅ |
| 증강 5종(밝게/어둡게/좌우반전) 내장 | **외부 증강 3종**(WINTER/NIGHT/RAIN, 이미지-to-이미지·비디오 원본 복사) + **저작도구 내부 해상도 변경 파생**(RESOLUTION, SFR-06-03 — 2026-07-21부터 증강과 동일하게 파생영상 생성·업스케일 허용·좌표 배율 재계산) + 검수(`LS_DATA_AUG_RVW`, 해상도 변경 파생영상은 일반 검수 파이프라인) | ✅ |
| 다단계 검수 (1차 → 2차) | **REVIEWER 단일 승인**으로 의도적 변경 (승인=작업 완료→관제 통지) | ✅ |
| 양방향 송수신 인터페이스(II-001~007) | **단방향 outbound 통지**(`TASK_COMPLETED/MODIFIED`) + inbound 조회 API | ✅ |
| 이력 누적(`_HSTRY`) 버전관리 | `LS_LABEL_VERSION` 스냅샷 + `LS_DATA_LBL_HSTRY` (diff/rollback) | ✅ |
| 통계 엑셀 다운로드 | CSV 리포트(`KLID-AT-SC-021`) | ✅ |

> 이 항목들은 "없어진" 게 아니라 **v2 방식으로 바뀐 것**이다. 추가하지 말 것.

---

## 19.3 ⛔ 범위 외 (추가 안 함 — 외부 시스템 책임)

> 외부 시스템 연동·범위 상세는 [20 외부 시스템](20-external-systems.md) 참고.

| v1 기능 | v2 결정 근거 |
|---------|-------------|
| 생성형 AI 화면 (Text/Image→Image/Video 내장) | 생성형 AI 본체는 외부 시스템 책임. v2는 **외부 증강 결과 검수**(SCR-AUG-002)만 ([CLAUDE.md](../../CLAUDE.md)) |
| 데이터마트 등록 / 검색 / 다운로드 | 데이터마트는 외부 제공 시스템. v2는 `V_COMPLETED_*` View 노출까지만 |
| 학습데이터셋 Export | 외부 시스템 책임 |
| VLM 모델 본체 (학습·프롬프트) | 외부 VLM 서비스. v2는 **호출 연동 + 결과 검토**(SC-015)만 |
| 영상 합성 모델 본체 | 외부 시스템. v2는 합성 영상 수신·라벨링·검수만 |
| 업로더 역할(내부) | 내부 1차 적재는 관제 학습용 설정 기반 — 사용자 업로드 역할 없음 |

> 이 항목들은 **의사결정이 끝난 범위 제외**다. v1에 있다고 v2에 추가하면 설계 위반이 된다.

> **요구사항 외 추가 결정(2026-07-17) — 포털 사용자 업로드 (ADR-013 예외)**: 포털 사용자(PORTAL_USER)가 **본인 이미지(20MB/장·50장)·영상(5GB, TUS)을 직접 업로드**해 수동 라벨링(BBOX/POLYGON) 후 본인 데이터(JSON export/원본)를 다운로드하는 기능을 신설한다(R1 미기재, 사용자 확정). 신규 `LS_PORTAL_*` 테이블(V107/V108)로 내부 파이프라인·데이터마트와 **완전 분리**되며 오토라벨링·SAM2·VLM·검수·버전관리는 여전히 미제공 — ADR-013의 "데이터마트 영상 선택 전용" 원칙은 내부 파이프라인 반영 대상에 한정된다. 신규 화면 SC-034(`/portal/uploads`)·SC-035(`/portal/uploads/:uldSn/label`) → [v2-wiki 04](../v2-wiki/04-screens-ia.md)·[18](../v2-wiki/18-database.md).

> **요구사항 외 추가 결정(2026-07-20) — AI Tool 팝업·오토라벨 draft 저장·라벨 변경 히스토리**: R1에는 "AI 탐지 결과의 폴리곤 출력 형태 선택"·"AI Tool 팝업(형태/라벨/일반·트랙) UI"·"온라인 오토라벨의 임시(draft) 저장 방식"·"라벨 변경 이력(추가/수정/삭제) 조회" 문구가 없다(SFR-08-01은 VOS 자체만 명시). 아래 4건은 SFR-08 계열을 실현하는 과정에서 필요해진 **UX/구현 세부 결정**으로 R1 근거 없이 추가됐다(사용자 확정, R1 미기재):
> - **AI 탐지 폴리곤 출력** — AI 탐지에서 폴리곤 형태를 선택하면 탐지(박스)→박스별 AI 분할(SAM)로 폴리곤을 산출(박스 개수 상한 `autolabel.polygon.max-boxes` 기본 20, 부분 실패 시 성공분만 반환).
> - **AI Tool 팝업** — 캔버스 AI 탐지·AI 추적 진입점을 형태(박스/폴리곤)·라벨·일반/트랙 버튼으로 통합한 단일 팝업(`AiToolModal`).
> - **오토라벨 draft 저장 정상화** — 온라인(캔버스) AI 탐지·AI 추적은 DB 즉시 저장을 폐지하고 좌표만 반환 → FE 작업본 병합(IoU 중복 방지) → 사용자 명시 저장(`PUT /v1/frames/{srcSn}/labels`)으로 전환. 배치 파이프라인 오토라벨은 저장 유지(무변경).
> - **라벨 변경 히스토리** — `LS_DATA_LBL_HSTRY`를 '삭제 전용 감사'에서 '변경 이력(ADDED/UPDATED/DELETED)'로 확장, `GET /v1/frames/{srcSn}/label-history` + 라벨링 화면 이력 패널 신설.
>
> → [v2-wiki 04](../v2-wiki/04-screens-ia.md)·[18](../v2-wiki/18-database.md).

---

## 19.4 사용 방법

1. **추가 검토는 §19.1 체크리스트만** 본다. (🟢·⛔는 추가 대상 아님)
2. 각 후보는 추가 전 **R1 요구사항정의서(`docs/design/R1-*.md`)에 해당 SFR이 있는지** 먼저 확인.
3. 요구사항에 있고 미구현이면 → `/cc-plan`으로 설계 → `/cc` 개발 파이프라인.
4. 요구사항에 없으면 → "의도적 미포함"으로 본 페이지에 사유를 기록(🟢/⛔로 이동).

> 이 체크리스트는 **v1 위키 내부 참고용**이다. 실제 개발 백로그는 `docs/design/`·이슈 트래커에서 관리할 것.

---

## 19.5 R1 요구사항 매핑 결과 (2026-07-14)

> §19.1 후보를 **R1 사용자요구사항정의서**(`docs/design/R1-사용자요구사항정의서-제출본.md`) + R3 추적표에 대조한 결과. **원칙: R1 근거 없으면 넣지 않는다**(§19.4). R1 제출본은 발주처 톤의 업무 수준 서술이라 캔버스 UI 세부 조작(슬라이더·버튼·단축키)은 열거하지 않음 — 따라서 다수 후보가 "간접" 또는 "범위 외"로 판정됨.

### ✅ 개발 대상 (R1 명시 근거)

| 항목 | R1 근거 | 상태 |
|---|---|---|
| **폴리곤/폴리라인 트랙 보간** | **SFR-08-01** "경계(폴리곤)를 자동 갱신…빈 프레임은 트랙 보간으로 채운다" | ⚠ **BBOX only**(`TrackInterpolator.java:38` 후속) — 요구 대비 미달. **유일한 명확 개발 갭** |

### ✅ R1 근거 있으나 이미 충족/해결 (추가 개발 불요)

- **VLM 벤더 계약 정합** (SFR-17 + 범위 정합 ③) — ✅ **코드 정합 구현 완료 (2026-07-07)**. callbackUrl 실주입·비동기 accepted 계약·HMAC 제거(벤더 무서명, 발급게이트 대체) 전부 CLOSED. 잔여=실운영 전 IP allowlist 재검토(prd)·`VLM_CLIENT_ENABLED=false` 실연동 별도. 메모리 `vlm-vendor-contract-intellivix`.
- **작업 중 비식별 신고** (SFR-09-03) — ✅ 라벨링단계(srcSn) 구현 완료(`DeidentReportController`). R1 원문은 '라벨 작업 중'만 명시 → **사실상 충족**. 마킹단계(rawSn) 확장은 R1 원문 밖(planned, 선택).

### 🔶 간접 (R1 미명시 — 상위 SFR 포괄 여지, 사업/UX 판단)

이미지 조절 패널 · 라벨 복사/붙여넣기 · 트랙 번호 변경/머지 · 프레임 4색 체계 · YOLO 수동 트리거 버튼 · 커스텀 라디오 메타 · 증강 적재 선두 비식별.
> SFR-08/11/16/17에 포괄될 수는 있으나 직접 인용할 문구 없음. **넣으려면 별도 UX 결정 필요**(R1 자동 근거 아님).

### ⛔ 범위 외 (R1 미기재 — 넣지 말 것)

라벨링 단축키 정합 · 회전/Fit/영역확대 도구 · 캔버스 그리드 · 객체 잠금/숨김 · 통계 CSV 리포트 · 이미지 자동 분류 · 연습장 · 프레임 폐기 워크플로.
> 주의: 회전/확대를 RQ-SFR-06-03("이미지 확대·축소")로 근거 삼으면 **오독** — 해당 SFR은 생성형 변형(외부 책임)을 가리킴. 통계 CSV는 통계 SFR 자체가 R1에 없음.

> **결론**: R1 근거로 실제 남은 개발 대상은 **폴리곤/폴리라인 트랙 보간 1건**(SFR-08-01). 나머지 P1~P3 후보는 R1 미명시라 사업/UX 결정 없이는 착수 대상이 아니다.

---

### 관련 페이지
- 전체 비교: [18 v1 ↔ v2 비교](18-v1-v2-comparison.md)
- 화면 단위 상세(SC-ID·입출력 param·단축키·프레임 색상): [21 사용자 화면 가이드](21-user-screen-guide.md)
- v1 기능 상세: [05 프로젝트](05-project-management.md) · [07 라벨링](07-labeling-tools.md) · [09 검수](09-review-workflow.md) · [13 게시판·연습장](13-board-practice.md)
- v2 정본: 루트 [`CLAUDE.md`](../../CLAUDE.md) · [`docs/design/`](../design/)
