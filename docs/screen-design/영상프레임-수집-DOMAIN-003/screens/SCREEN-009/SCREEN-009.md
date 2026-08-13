---
logicraft_item: SCREEN-009
type: screen_spec
version: 28
last_updated_at: 2026-08-07T23:33:05.407Z
domain: DOMAIN-003
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-11T06:30:02.876Z
sync_session: 2
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-009.json
wireframe: ./wireframe.html
links:
  consumes_apis: [API-021, API-043, API-044, API-112]
  required_roles: [ROLE-001, ROLE-002]
---

# 영상 상세 화면

## route

/video/:id

## title

영상 상세 화면

## device

desktop

## status

draft

## purpose

영상 메타와 오토라벨 결과를 조회하는 상세 화면. 기본 정보 탭에서 배치 파이프라인 처리 단계(BatchStageIndicator, video.stages — GET /v1/videos/{id}=API-043 응답)를 표시한다. 헤더 카드 우측에는 '재비식별 요청' 버튼(POST /v1/videos/{rawSn}/redeident)이 REVIEWER + reviewSttsCd='APPROVED' + deIdntfYn≠'Y' 일 때만 노출된다. ※ 이것은 '비식별 누락 신고'와 다른 축이다 — 파생영상 여부에 따른 신고 버튼 비활성화+툴팁은 라벨링 화면(SCREEN-005)과 마킹 화면(SCREEN-006)이 담당하며 이 화면은 신고 진입점을 갖지 않는다. ★개인정보 유무(분류)는 표시하지 않는다(관제가 그 값을 실제로 보내지 않는다 — 응답 필드 privacyTypeCd 자체는 하위호환으로 존치). 접근: REVIEWER/WORKER.

## sections

### 영상 헤더 카드

- **role**: hero
- **layout**: detail

**components**:

#### [1]

- **note**: navigate(-1)
- **type**: Button
- **label**: 뒤로가기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [2]

- **type**: Custom
- **label**: 썸네일

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: framePreviews[0].srcSn
- **custom_name**: AuthImage
- **triggers_api**: API-021

#### [3]

- **type**: Heading
- **label**: CCTV명

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: video.cctvName

#### [4]

- **type**: Badge
- **label**: 이벤트 유형 배지(EventTypeBadge)

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: video.eventTypeCd

#### [5]

- **type**: Badge
- **label**: 상태 배지(StatusBadge)

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: video.status

#### [6]

- **note**: RedeidentButton — 노출 가드: REVIEWER + reviewSttsCd='APPROVED' + deIdntfYn≠'Y'. 클릭·확인 시 POST /v1/videos/{rawSn}/redeident (202). 409=이미 처리 중이거나 비식별된 영상, 403=권한 없음, 404=대상 영상을 찾을 수 없음 → 각각 에러 토스트. 권한 가드는 UX 편의이고 실제 강제는 BE. 이 기능은 환경 설정에 따라 조건부로 제공되는 설계로, 비활성 환경에서는 이 요청 자체가 존재하지 않아 같은 404 가 응답된다(대상 영상 부재와 동일한 응답). 확인 다이얼로그는 요청 처리 중 ESC·백드롭 닫기를 막아 중복 요청을 방지한다.
- **type**: Button
- **label**: 재비식별 요청

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary
- **triggers_api**: API-112

- **description**: 뒤로가기 버튼 + 헤더 카드. 썸네일(첫 프레임 이미지, 없으면 '미리보기 없음' 자리표시), CCTV명, 이벤트 유형 배지와 상태 배지, 길이·녹화일 메타로 구성한다. 썸네일은 인증 헤더를 실어 받아 표시한다. 우측 상단에는 '재비식별 요청' 버튼이 조건부로 노출된다 — 검수자이면서 검수 상태가 승인이고 비식별이 완료되지 않은 경우다. ★검수 완료 판정은 배치 단계(LS_DATA_RAW.DATA_STTS_CD)가 아니라 검수 상태(LS_RAW_DATA_STATUS.DATA_STTS_CD)로 한다 — 배치 단계는 종착이 완료라 검수 승인 여부를 구분하지 못한다.

**references_apis**:

- API-043
- API-021
- API-112

**references_features**:

_(empty)_

### 탭 네비게이션

- **role**: navigation
- **layout**: tabs

**components**:

#### [1]

- **type**: Tabs
- **label**: 상세 탭

**columns**:

_(empty)_

**options**:

- 기본 정보
- 프레임 미리보기
- 오토라벨 결과

- **description**: 3개 탭 전환 — 기본 정보 / 프레임 미리보기 / 오토라벨 결과. 로컬 state(activeTab) 기반 클라이언트 전환.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 기본 정보 탭

- **role**: main
- **layout**: detail

**components**:

#### [1]

- **note**: CCTV ID/해상도/길이/녹화시각/처리단계/생성일/수정일 — '개인정보 분류' 항목은 제외한다
- **type**: KeyValue
- **label**: 메타 정보 그리드

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: video

#### [2]

- **type**: Custom
- **label**: 처리 단계 표시

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: video.stages
- **custom_name**: BatchStageIndicator

- **description**: 영상 메타 2열 그리드 — CCTV ID, 해상도, 길이(formatDuration), 녹화 시각, 처리 단계(BatchStageIndicator 또는 StatusBadge), 생성일/수정일. 모든 값은 video 상세 응답(API-043)에서 파생. ★'개인정보 분류' 항목은 표시하지 않는다 — 관제서버가 개인정보 유무를 실제로 보내지 않고(인입 원장 LS_DATA_INGEST 의 ANONY_INCL_YN/PSDO_INCL_YN/PRVC_INCL_YN 이 dev 실측 40행 전부 NULL), 화면이 보던 값은 관제값이 아니라 적재 시 고정되는 레거시 컬럼 LS_DATA_RAW.PRVC_TYPE_CD 였다. ⚠ 바뀐 것은 화면 노출뿐이며 PRVC_TYPE_CD 컬럼과 응답 필드 privacyTypeCd 는 하위호환으로 존치되고 비식별 대상 판정(needsDeidentify)에 계속 쓰인다. 라벨링 화면(SCREEN-005)의 개인정보 메타 패널은 사람이 직접 입력하는 별개 축이다.

**references_apis**:

- API-043

**references_features**:

_(empty)_

### 프레임 미리보기 탭

- **role**: main
- **layout**: grid

**components**:

#### [1]

- **type**: Custom
- **label**: 프레임 썸네일

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: framePreviews[].srcSn
- **custom_name**: AuthImage
- **triggers_api**: API-021

#### [2]

- **note**: 라이트박스 오픈
- **type**: Button
- **label**: 프레임 상세 보기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

- **description**: framePreviews 썸네일 그리드(3~6열). 각 프레임은 AuthImage(srcSn 기반 blob 이미지), 이슈 있으면 빨간 점 표시, 하단 #frameNo 라벨. 클릭 시 라이트박스 Modal 오픈 — 확대 이미지 + 프레임 번호/타임스탬프/이슈 표시. 프레임 없으면 빈 상태 메시지.

**references_apis**:

- API-021

**references_features**:

_(empty)_

### 프레임 라이트박스 모달

- **role**: modal
- **layout**: detail

**components**:

#### [1]

- **note**: Modal size=xl
- **type**: Dialog
- **label**: 프레임 확대 모달

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Custom
- **label**: 확대 프레임 이미지

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: lightboxFrame.srcSn
- **custom_name**: AuthImage
- **triggers_api**: API-021

#### [3]

- **type**: Button
- **label**: 닫기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 프레임 썸네일 클릭 시 열리는 확대 보기 Modal(size xl). 확대 이미지(AuthImage), 프레임 #/타임스탬프(ms)/이슈 여부를 표시하고, 푸터는 '닫기' 단일 버튼이다.

**references_apis**:

- API-021

**references_features**:

_(empty)_

### 오토라벨 결과 탭

- **role**: main
- **layout**: dashboard

**components**:

#### [1]

- **type**: Stat
- **label**: 처리 정보(총 라벨/오토라벨/비율/상태 4종)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Chart
- **label**: 신뢰도 분포 막대

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: labels.confidence

#### [3]

- **type**: Chart
- **label**: 라벨별 분포 바

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: labels.labelCode

#### [4]

- **note**: 오토라벨 없음 / 불러오기 실패 메시지
- **type**: Alert
- **label**: 빈/에러 상태

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: useVideoLabels(API-044)로 오토라벨 결과 조회. 처리 정보 카드(총 라벨 수/오토라벨 수/오토라벨 비율/처리 상태), 신뢰도 분포 막대(0.9+ / 0.7~0.9 / <0.7, 오토라벨 기준 집계), 라벨별 분포 바(상위 10종, count 내림차순). 로딩 스켈레톤·에러·빈 상태 분기.

**references_apis**:

- API-044

**references_features**:

_(empty)_

## brownfield

### status

modified

### decided_by

ADR-001

### change_kind

- scope-shrink

### diff_summary

영상 단위 상세

### legacy_source

#### type

module

#### legacy_artifact_id

LEGACY-003

## surface_kind

web

## consumes_apis

- API-021
- API-043
- API-044
- API-112

## implementation

### status

implemented

### modules

_(empty)_

### records

_(empty)_

### progress

100

### subtasks

_(empty)_

## required_roles

- ROLE-001
- ROLE-002

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-009/main.html
- **label**: 영상 상세 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **description**: 
- **source_hash**: e03c1196c3c3595046ac860c27b9c746fd82a25a99e1381b8ed684800d35e198
- **generated_at**: 2026-08-07T23:33:05.407Z
- **generated_by**: sections-deterministic-generator

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

_(empty)_

## covered_by_acceptances

_(empty)_
