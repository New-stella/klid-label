# 04. 화면 · IA

> 출처: D2 사용자인터페이스설계서(KLID-AT-SC 체계), 코드(`frontend/src/` 라우트)
> 관련: 각 기능 페이지

## 4.1 채널 구조

- **내부 채널 (INTERNAL)**: `/` 하위 + AppLayout(LNB+GNB). 관제서버 JWT.
- **포털 채널 (PORTAL)**: `/portal` 하위 + PortalLayout(모바일 친화, LNB 없음). 포털 JWT. → [16](16-portal.md)

화면 ID 체계: **`KLID-AT-SC-NNN`** (SC=Screen). 활성 25개(deprecated 7 제외).

## 4.2 내부 채널 화면

| 화면 ID | 화면명 | 라우트 | 권한 | 위키 |
|---------|-------|--------|------|------|
| SC-001 | 세션 인계 진입 | `/ingress` | 전체 | [03](03-auth-roles.md) |
| SC-002 | 역할 클레임 | `/role-claim` | 전체 | [03](03-auth-roles.md) |
| SC-005 | 라벨링 캔버스 | `/label/:id` | WORKER/REVIEWER | [10](10-labeling.md) |
| SC-006 | 마킹 | `/marking/:rawSn` | WORKER/REVIEWER | [06](06-marking.md) |
| SC-007 | 영상 목록 | `/video/completed` | - | [05](05-video-management.md) |
| SC-009 | 영상 상세 | `/video/:id` | - | [05](05-video-management.md) |
| SC-010 | 라벨 이력(버전) | `/history/:videoId` | - | [13](13-version-control.md) |
| SC-011 | 대시보드 | `/dashboard` | - | [17](17-statistics.md) |
| SC-012 | 작업 목록 | `/task` | - | [12](12-review-assignment.md) |
| SC-018 | 검수 목록 | `/review` | REVIEWER | [12](12-review-assignment.md) |
| SC-019 | 검수 상세 | `/review/:id` | REVIEWER | [12](12-review-assignment.md) |
| SC-020 | 작업자 통계 | `/stat` | - | [17](17-statistics.md) |
| SC-021 | 전체 통계 | `/stat/overall` | REVIEWER | [17](17-statistics.md) |
| SC-022 | 증강 요청 | `/augment` | REVIEWER | [14](14-augmentation.md) |
| SC-023 | 증강 결과 | `/augment/result/:jobId` | REVIEWER | [14](14-augmentation.md) |
| SC-024 | 사용자 관리 | `/manage/users` | REVIEWER | [03](03-auth-roles.md) |
| SC-025 | 시스템 설정 | `/manage/settings` | REVIEWER | [10](10-labeling.md#정밀도-설정) |
| SC-026 | 프리셋 관리 | `/manage/presets` | REVIEWER | [10](10-labeling.md#라벨-프리셋) |
| SC-033 | 비식별 신고 관리 | `/manage/deident-reports` | REVIEWER | [10](10-labeling.md) |
| SC-036 | 라벨 관리 | `/manage/labels` | REVIEWER | [10](10-labeling.md) |
| SC-030 | 게시판 목록 | `/notice` | WORKER/REVIEWER | [20](20-notice-board.md) |
| SC-031 | 게시판 상세 | `/notice/:id` | WORKER/REVIEWER | [20](20-notice-board.md) |
| SC-032 | 게시판 작성/수정 (모달) | (SC-030/031 내) | REVIEWER | [20](20-notice-board.md) |

개발 전용: `/dev/login`(SC-004), `/dev/autolabel-test`(SC-027) — DEV 빌드만.

> **SC-036 라벨 관리**(`/manage/labels`, 2026-07-20, REVIEWER 전용) — 1차 관리자매뉴얼 §4.1.3(라벨 생성)·§4.1.4(속성 설정)가 요구하는 **라벨 클래스(마스터)·속성 정의 관리 화면**. 그간 BE(`LabelMasterController`·`LabelAttrController`, `/v1/manage/labels/**`)만 완비되고 FE 화면이 없어 DB 직접 조작으로만 가능하던 갭을 해소. ①**라벨 마스터 목록/CRUD**: 라벨명·형태(BBOX/POLYGON/POINT/SKELETON)·색상(#RRGGBB)·정렬순 생성/수정/삭제(삭제는 확인 모달). ②**라벨별 속성 정의**: 행 선택 시 속성 정의 패널 노출 — 속성명·입력형식(SELECT/CHECKBOX/RADIO/NUMBER/TEXT)·선택항목(valuesJson, 선택형에서만 노출)·기본값·수정가능·정렬순 CRUD. 마스터 변경은 `LABEL_MASTER_KEYS` 무효화로 라벨링 캔버스(SC-005) 드롭다운·색상에 반영. 스켈레톤 포인트 정의는 COCO-17 고정상수라 미제공(화면 스코프 외). 기존 `/manage/*` 관리 화면(SC-024~026) 패턴·게이트 재사용.

> **SC-026 프리셋 관리 = 라벨 마스터 단일 진실원 연동**(`/manage/presets`, REVIEWER, 2026-07-21, V117~V119) — 프리셋의 라벨 코드가 라벨 관리 마스터(`LS_LABEL`, SC-036)를 **단일 진실원으로 실시간 참조**하도록 전환됐다. 프리셋 편집 시 라벨을 **라벨 마스터 목록에서 선택**(기존 하드코딩 라벨 제거)하고, 저장 요청은 `LBL_ID`(labelId) 기반이다. **라벨명·형태는 마스터에서 표시**되며 프리셋 화면에서는 **형태(BBOX/POLYGON 도형 허용)가 마스터 `LBL_TYPE_CD` 기준 읽기전용**으로 노출된다(프리셋 개별 토글 불가 — 구 bbox/폴리곤 활성 토글 폐지). 마스터에서 라벨명/형태를 바꾸면 신규·기존 프리셋에 즉시 반영되고, 마스터에 매칭 안 되는 기존 코드는 **'미연결' 배지**로 표시된다(오류·자동 생성/삭제 없음). 스키마·오토라벨 정합 상세 → [10 §10.4](10-labeling.md#104-라벨-프리셋) · [18](18-database.md).
>
> **SC-005 라벨링 캔버스 도구**(2026-07-14) — BBOX·폴리곤·마스크·SAM2 분할/추적에 더해 **키포인트(COCO-17 휴먼 포즈, 단축키 K)** 도구 추가. 17관절 순차 배치 + 관절별 드래그 + 스켈레톤 렌더, 관절 Alt+클릭으로 가시성(가시/비가시/미표기) 순환. `SKELETON` 타입 삼중값 `[x,y,v]`로 저장. 포털 모드는 미노출(ADR-013 — 오토라벨/키포인트 미제공). 상세 → [10 라벨링](10-labeling.md#102-도구).
>
> **SC-005 AI Tool 팝업 + 오토라벨 draft 저장 + 라벨 변경 이력**(2026-07-20, R1~R12) — 캔버스 AI 탐지·AI 추적 진입점이 단일 `AiToolModal`(AI Tool 팝업)로 통합됐다. 팝업 구성: **형태**(바운딩박스/폴리곤 라디오, 기본 박스) + **라벨**(클래스 다중선택, 미선택=전체) + **일반/트랙** 실행 버튼(모델명 YOLO/SAM 미노출). 일반=단일 프레임 AI 탐지(박스) 또는 AI 탐지→박스별 AI 분할(폴리곤 선택 시, 박스 개수 상한은 시스템 설정), 트랙=AI 추적(시작 프레임 박스를 후속 프레임으로 전파). **오토라벨 draft 저장 정상화**: 온라인(캔버스) AI 탐지·AI 추적 결과는 더 이상 DB에 즉시 저장되지 않고 좌표만 반환되며, FE가 작업본(draft)에 병합(IoU 중복 방지·기존 라벨 보존)한 뒤 사용자가 저장 버튼(`PUT /v1/frames/{srcSn}/labels`)을 눌러야 확정 저장된다(배치 파이프라인 오토라벨은 저장 유지, 무변경). **라벨 변경 이력 패널** 신설(`LabelHistoryPanel`) — 프레임 단위 라벨 변경 이력(추가/수정/삭제 + 작업자 + 시각)을 `GET /v1/frames/{srcSn}/label-history`로 페이징 조회. 라벨 버전(SC-010, `LS_LABEL_VERSION`) 이력과는 별개 개념.
>
> **SC-005 AI 정밀도 per-실행 조절**(2026-07-22) — AI Tool 팝업(AI 탐지)에 **인식 민감도**(0.25~0.80) + **경계 세밀함**(0~50px, **폴리곤 형태일 때만**) 슬라이더, **AI 분할** 도구에 **경계 세밀함**만 추가됐다(SAM2는 신뢰도 임계값 미수용 → 인식 민감도 미노출). 시스템 설정 "라벨링 정밀도" 카드(SC-025) 값으로 프리필되고, 조절하지 않으면 시스템 설정 기본값으로 동작(세션 한정 override, DB 미저장). 문구는 모델명(YOLO/SAM) 비노출. 상세 → [10 §10.5](10-labeling.md#정밀도-설정-rq-sfr-08-03) · [11 §11.5](11-ai-assisted.md).
> **SC-036 라벨↔AI 검출 클래스(COCO) 매핑 + SC-005 AI 탐지 팝업 후보 연동**(2026-07-23, V129 `DTCT_TYPE_CD VARCHAR(20)`) — 라벨 관리(SC-036) 생성/수정 폼에 **"AI 탐지 클래스 (선택)" select**가 추가됐다. 라벨 마스터(`LS_LABEL`)를 COCO 80종 중 한 클래스에 매핑(`dtctTypeCd`)하거나 **미지정(미매핑)** 으로 둘 수 있다(기본 미지정 → null). **한 COCO 클래스는 하나의 활성 라벨에만 매핑** — 이미 다른 활성 라벨에 매핑된 클래스를 지정하면 BE가 **409**로 거부하고 폼에 서버 안내 메시지를 노출한다(값 검증·중복 판정은 BE allowlist가 최종 신뢰 경계). **SC-005 AI Tool 팝업**의 라벨 후보는 하드코딩 6종을 폐기하고 **BE 후보 조회**(`GET /v1/manage/labels/detect-candidates` = 활성 라벨 마스터 + 매핑 여부)로 대체됐다 — **매핑된 라벨만 선택 가능**(체크박스 활성), 미매핑 라벨은 표시하되 선택 불가(`미매핑` 배지). 매핑 라벨이 하나도 없으면 [일반]/[트랙] 실행이 비활성 + 안내. 후보 조회 실패 시 팝업에 **에러 안내 + "다시 시도"(refetch)** 노출(무한로딩/빈화면 없음). 실제 검출 대상은 BE가 '매핑 라벨→COCO'로 재구성·재검증하며, 화면 문구에 모델명(YOLO/SAM2) 미노출(COCO 클래스명은 라벨 관리 매핑 select 맥락에서만 표기). 상세 → [10 라벨링](10-labeling.md) · [11 §11.5](11-ai-assisted.md) · [18](18-database.md).
> **SC-005 히스토리 패널 탭 통합**(2026-07-21) — 우측 상단 **"히스토리" 버튼**이 여는 인라인 패널을 탭 구조로 통합했다. **① 변경 이력(저장, `LS_DATA_LBL_HSTRY`)** 탭과 **② 버전(커밋, `LS_LABEL_VERSION`)** 탭을 한 곳에서 전환하며, **기본 활성 탭은 "변경 이력"**이다 — 라벨 저장 직후 프레임별 추가/수정/삭제 이력이 바로 보인다(버전 스냅샷은 종전대로 검수 승인 시점에만 생성되므로 승인 전 버전 탭은 비어 있는 게 정상). 종전 "메타" 탭 하단에 있던 중복 `LabelHistoryPanel`은 제거해 변경 이력 노출 지점을 히스토리 버튼으로 단일화했다(메타 탭은 프레임 설명 + 시계열 메타만 유지). 버전 전용 라우트(`/history/:videoId`, `HistoryPage`)는 기본 탭을 "버전"으로 유지. 포털/미로그인 가드 불변(INTERNAL 전용).
>
> **SC-005 "변경 이력" = 저장 이벤트 단위 + 펼침 diff**(2026-07-21, V114) — 히스토리 패널 "변경 이력" 탭이 이제 **라벨 1건=1행이 아니라 저장 행위(저장 클릭 1회)=이벤트 카드 1건**으로 표시된다. 각 카드는 **추가 +N / 수정 ~N / 삭제 -N 요약**(저장시각·작업자)을 보여주고, **펼치면 항목별 이전값→새값 diff**(각 항목 `{lblSn, changeKind, labelName, before, after}`)를 나열한다. 이력은 **직전 저장 대비 diff**로 계산되며, 첫 저장은 전부 추가(ADDED)로 기록된다. **저장은 프레임 전체 교체** — `PUT /v1/frames/{srcSn}/labels` 요청에서 빠진 라벨은 실제로 삭제되고 그 삭제도 이력에 DELETED로 남는다(무변경 저장은 이력을 만들지 않음). 트랙 삭제·비식별 신고 삭제도 같은 저장 이벤트 모델로 프레임당 1건 기록된다. 백엔드 스키마 재구조화(`LS_DATA_LBL_HSTRY`: 구 `LBL_SN`·`CHG_KIND_CD` 제거 → `ADD_CNT`/`MDFCN_CNT`/`DEL_CNT` + `CHG_DTL_CN`(diff JSON), V114)와 정합. 버전 탭(`LS_LABEL_VERSION`)은 별개 개념으로 불변.
>
> **SC-005 "변경 이력" 되돌리기(복원)**(2026-07-21) — "변경 이력" 탭의 각 저장 이벤트 카드에 **되돌리기 버튼**을 제공한다. 클릭 후 확인 모달을 승인하면 그 저장 이벤트의 변경을 **현재 캔버스 작업본(draft)에 역적용**한다 — 수정(UPDATED)→이전값 복원, 추가(ADDED)→작업본에서 제거, 삭제(DELETED)→이전 라벨을 신규로 재추가. 역적용은 **즉시 DB 저장이 아니라** 작업본만 변경(단일 undo로 취소 가능·dirty 표시)하며, 사용자가 기존 **저장 버튼**을 눌러야 확정된다(승인 전에도 동작, `LS_LABEL_VERSION` 롤백과 별개). before 스냅샷은 이력의 `CHG_DTL_CN`에 저장돼 있어 별도 조회 없이 복원한다. 현재 작업본에 없는 대상(저장 후 다른 편집으로 사라짐)·좌표 복원 불가(SEGMENT 마스크) 항목은 안전 스킵 + 안내 토스트. 비식별 재처리 잠금(`LOCKED_FOR_REDEIDENT`) 영상은 되돌리기 차단. FE 전용(되돌리기용 BE API 없음 — 저장은 기존 `PUT /v1/frames/{srcSn}/labels`).

> **SC-005 메타 탭 event_annotation 수동입력 패널**(2026-07-22) — 라벨링 캔버스 우측 **'메타' 탭**(INTERNAL 채널만, 포털 미노출)에 `TimeseriesSidePanel` 아래로 **이벤트 어노테이션 패널**(`EventAnnotationPanel`)이 추가됐다. 영상(RAW_SN) 단위 event_annotation(VQA/CoT)을 입력·검수한다: `event_class`(필수)·`question`·`answer` + **caption 후보 c1..cn**(caption_text + CoT 1·2·3단계) + **evidence 후보 c1..cn**(evidence_text + frame_id/obj_id/obj_bbox/obj_label). 외부 자동 생성값을 폼에 프리필하고 WORKER/REVIEWER 가 수동 덮어쓰기, REVIEWER 가 승인/반려한다. 조회/저장 `GET`/`PUT /v1/videos/{rawSn}/event-annotation`, 검수 `POST .../approve|reject`. 문구는 모델명(YOLO/SAM/VLM) 비노출. 상세 → [09 §9.3-1](09-vlm-timeseries.md) · [24 §24.3.1](24-dataset-export.md).

> **deprecated 화면 정리**(2026-06-17) — 진입점 없는 orphan/중복 화면을 코드·라우트와 함께 제거:
> - **SC-008 영상 처리 현황**(`/video/status`) — LNB·링크 진입점 없는 orphan. '영상 처리 현황' LNB 메뉴는 SC-007(영상 목록)에 연결돼 영향 없음.
> - **SC-014 오토라벨 요약**(`/auto/:videoId`) — 영상 상세(SC-009)의 인라인 `AutoLabelTab`으로 대체.
> - **SC-015 VLM 메타 검토**(`/auto/:videoId/meta`) — 라벨링 캔버스(SC-005)의 시계열 메타 패널(`TimeseriesSidePanel`)로 대체.
> - **SC-013 작업 배정**(`/task/assign`) — 작업 목록(SC-012, `UNASSIGNED` 필터 + `AssignModal`)·영상 목록(SC-007 인라인 배정)으로 대체.

> SC-030~032(게시판)는 **R1 요구사항 외 추가 결정**(2026-06-05) — [20 게시판](20-notice-board.md) 참고.
>
> **해상도 변경**(RQ-SFR-06-03, 2026-07-21부터 증강형 파생영상 — 표준 3종 프리셋마다 새 RAW_SN 생성, 업스케일 허용, 좌표 배율 재계산 제공) UI는 **SC-022 증강 요청** 화면의 통합 단일 선택 UI에 '해상도 변경' 카드로 포함된다 — 영상 상세(SC-009)에서 이관·통합(2026-06-16). 처리 종류 카드 4개(겨울/야간/우천/해상도 변경) 중 하나 + 검수완료 영상 1건을 고른 뒤, 실행 시 증강 3종은 잡 요청(`/augments/request`)·해상도는 직접 수행(`/videos/{rawSn}/resolution`)으로 분기된다(둘 다 결과는 새 파생영상 생성 → PENDING → 배정·검수) → [14 증강](14-augmentation.md) · [05 영상](05-video-management.md).
>
> **SC-027 영상 업로드**(개발 전용)는 운영 시나리오 1:1 고정 플로우다(2026-06-16) — 단계 선택·마킹 수동 체크박스 없이, 업로드 → 비식별(무조건) → **MARKING_READY 정지** 후 검수자가 마킹 화면(SC-006)에서 마킹(자동/수동)→완료해야 잔여 배치가 진행된다 → [06 마킹](06-marking.md) · [07 배치](07-batch-pipeline.md).

> **검수완료 영상 재비식별**(REVIEWER 액션): 검수완료(APPROVED)됐으나 비식별 미완인 영상(주로 v1→v2 이관분)을 재비식별하는 `POST /v1/videos/{rawSn}/redeident` API가 제공된다(BE 완료). 라벨·검수상태 보존. 화면 진입점은 **SC-009 영상 상세** 또는 **SC-019 검수 상세**의 REVIEWER 액션 버튼으로 연계 예정(**FE 후속**) → [08 비식별화 §8.6](08-deidentification.md).

> **배치 단계 진행 표시**(SC-009 영상 상세 '처리 단계' + SC-006 마킹 화면 헤더): `GET /videos/{id}` 응답의 `stages` 배열(BE `StageStatusDto` — canonical 순서 `DEIDENTIFY→MARKING→VLM→FRAME_EXTRACT→YOLO→SAM2→INTERPOLATE`, 각 단계 status=`DONE/PROGRESS/PENDING/FAIL`)을 `BatchStageIndicator` 로 렌더해 어느 단계(비식별/마킹/VLM/프레임추출/AI 탐지/AI 분할/보간)가 진행 중인지 표시한다. 최신 `LS_BATCH_PROC_LOG` 1행 기준. **문구 규칙**: 기술 모델명(YOLO/SAM2) 화면 노출 금지 → **AI 탐지/AI 분할**로 표기(코드/name 은 유지). **하위호환**: 배치 로그 없는 기존 영상은 `stages` 빈 배열 → 인디케이터 미표시 + 기존 `StatusBadge('처리중')` 폴백(예외·빈화면 없음). stages 는 영상 상세 인가 경로를 그대로 타므로 타인 영상 단계 노출 없음(IDOR 방지), 단계 정보에 PII·경로·스택 미포함 → [05 영상](05-video-management.md) · [06 마킹](06-marking.md) · [07 배치](07-batch-pipeline.md).

## 4.3 포털 채널 화면

| 화면 ID | 화면명 | 라우트 |
|---------|-------|--------|
| (홈) | 데이터마트 영상 선택 | `/portal` |
| SC-029 | 포털 라벨링 | `/portal/label/:id` |
| SC-034 | 포털 업로드 | `/portal/uploads` |
| SC-035 | 포털 업로드 라벨링 | `/portal/uploads/:uldSn/label` |

→ [16 포털](16-portal.md)

> **SC-034/035 포털 자산 업로드**(2026-07-17) — ADR-013 예외로 신설. 포털 사용자(PORTAL_USER)가 **본인 이미지(jpg/jpeg/png, 20MB/장·50장/요청)·영상(mp4/mov/avi, 5GB, TUS 재개 업로드)을 직접 업로드**해 수동 라벨링(BBOX/POLYGON만) 후 본인 데이터(JSON export/원본)를 다운로드한다. 업로드 자산은 신규 `LS_PORTAL_*` 테이블로 내부 파이프라인(비식별→마킹→배치→검수)·데이터마트 View와 **완전 분리** — 오토라벨링·SAM2·VLM·검수·버전관리 미제공. 영상은 비식별 미적용(본인 데이터), 고정 간격 프레임 추출(`LS_SYSTEM_CONFIG` `portal.upload.frame-interval-sec` 기본 5초). 상태: `UPLOADED`→`PROCESSING`→`READY`\|`FAILED`. API `/v1/portal/uploads/**` → [16 포털](16-portal.md) · [18 DB](18-database.md).

## 4.4 deprecated 화면

| 화면 ID | 사유 |
|---------|------|
| SC-016 / SC-017 | 비식별 목록/상세 → 외부 비식별 솔루션 검토화면으로 이관 |
| SC-028 | 포털 홈 → ADR-013(데이터마트 영상 선택 전용으로 재정의) |

## 4.5 화면 ID 외 식별 체계

| 체계 | 의미 | 문서 |
|------|------|------|
| `KLID-AT-SC-*` | 화면(Screen) | D2 |
| `KLID-AT-UC-*` | 유스케이스 | R2 |
| `KLID-AT-CO-*` | 컴포넌트 | D3 |
| `KLID-AT-CL-*` | 클래스 | D1 |
| `KLID-AT-SS-*` | 서브시스템(10개) | R2 |
| `RQ-SFR-NN-NN` | 요구사항 | R1 |

→ [19 설계 문서 카탈로그](19-external-security-cvat.md#설계-문서-카탈로그)
