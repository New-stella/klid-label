---
logicraft_item: SCREEN-028
type: screen_spec
version: 10
last_updated_at: 2026-08-13T01:02:42.534Z
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-13T13:55:05.809Z
sync_session: 7
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-028.json
wireframe: ./wireframe.html
links:
  consumes_apis: [API-115]
  required_roles: [ROLE-003]
---

# 포털 홈 화면

## route

/portal

## title

포털 홈 화면

## device

responsive

## status

draft

## purpose

PORTAL_USER가 진입하는 포털 메인 화면. 데이터마트에서 검수 완료(APPROVED)되고 프레임 1건 이상 보유한 영상만 노출하며, 영상 카드 또는 '시작하기' 버튼 클릭 시 해당 영상 첫 프레임(firstSrcSn)의 라벨링 화면(/portal/label/{srcSn})으로 진입한다. 헤더에 본인 자산 업로드 관리 화면(/portal/uploads) 진입 링크('내 업로드')를 제공한다. KPI 2종(영상 수/라벨링 완료 — 라벨링 완료는 현재 0 고정 표시, 집계 연동은 후속)을 노출한다. 오토라벨링·SAM2·VLM·검수·버전관리는 포털 전 구간에서 미제공. 반응형(WCAG 2.1 AA). 접근: PORTAL_USER.

## sections

### Hero 배너 + 내 업로드 링크

- **role**: hero
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: AI 학습데이터 작성 포털

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Text
- **label**: 데이터마트 영상 선택, 간편 라벨링

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: → /portal/uploads
- **type**: Link
- **label**: 내 업로드

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 단색 neutral 배경 hero 섹션(KRDS Don't 준수 — 그라데이션·brand 대면적 금지). 제목 + 부제 + '내 업로드' 링크(/portal/uploads, 포털 업로드 화면 진입).

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 요약 KPI

- **role**: main
- **layout**: grid

**components**:

#### [1]

- **type**: Stat
- **label**: 영상 수

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: totalVideos
- **triggers_api**: API-115

#### [2]

- **note**: 현재 0 고정 — 라벨링 완료 집계 연동은 후속
- **type**: Stat
- **label**: 라벨링 완료

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: KPI 2개 그리드. '영상 수'는 데이터마트 목록(API-115) totalElements, '라벨링 완료'는 현재 0으로 고정 표시(집계 미연동, 후속 과제).

**references_apis**:

- API-115

**references_features**:

_(empty)_

### 이용 방법 카드 (라벨링 시작하기)

- **role**: main
- **layout**: detail

**components**:

#### [1]

- **type**: Heading
- **label**: 라벨링

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Text
- **label**: 선택한 영상에 라벨을 추가하세요

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **type**: Text
- **label**: 라벨링 가능 N건

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: totalVideos

#### [4]

- **note**: 영상 0건이면 aria-disabled(포커스는 유지, WCAG 2.1.1). 첫 영상(firstSrcSn)으로 진입
- **type**: Button
- **label**: 시작하기 ▶

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: 이용 방법 카드. '시작하기' 클릭 시 데이터마트 목록 첫 영상의 firstSrcSn 으로 /portal/label/{srcSn} 이동. 영상이 없으면 버튼이 aria-disabled(네이티브 disabled 는 Tab 순서에서 빠져 접근성상 aria-disabled 채택).

**references_apis**:

- API-115

**references_features**:

_(empty)_

### 데이터마트 영상 목록

- **role**: main
- **layout**: grid

**components**:

#### [1]

- **type**: List
- **label**: 데이터마트 영상 카드 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: videos
- **triggers_api**: API-115

#### [2]

- **note**: 카드 클릭 시 goToLabel(v.firstSrcSn) → /portal/label/{firstSrcSn}
- **type**: Custom
- **label**: 영상 카드 (제목 + 이벤트명 + 프레임 N건)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DatamartVideoCard

#### [3]

- **type**: Text
- **label**: 선택 가능한 영상이 없습니다.

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **type**: Text
- **label**: ※ 선택한 영상은 본인만 조회/라벨링할 수 있으며, 결과 파일 제공은 포털 시스템에서 별도로 안내됩니다.

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: GET /v1/portal/datamart/videos(API-115, PORTAL_USER 전용·검수완료 APPROVED만·프레임 0건 제외·페이징) 목록을 2열 카드 그리드로 표시. 각 카드는 제목 + 이벤트명(없으면 '-') + 프레임 건수, 클릭 시 해당 영상 firstSrcSn 으로 라벨링 화면 이동. 로딩 중 안내 텍스트, 빈 목록 시 '선택 가능한 영상이 없습니다.' 하단에 본인 데이터 전용·다운로드 별도 안내 고정 문구.

**references_apis**:

- API-115

**references_features**:

_(empty)_

## surface_kind

web

## consumes_apis

- API-115

## implementation

### status

planned

### modules

_(empty)_

### records

_(empty)_

### progress

0

### subtasks

_(empty)_

## required_roles

- ROLE-003

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-028/main.html
- **label**: 포털 홈 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **source_hash**: b00d159a0c57e9e9e5062408f096fc68920fc7df2523da2cba2d722f9303311a
- **generated_at**: 2026-08-13T01:02:42.533Z
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
