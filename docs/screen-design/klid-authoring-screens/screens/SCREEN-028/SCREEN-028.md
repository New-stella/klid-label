---
logicraft_item: SCREEN-028
type: screen_spec
version: 26
last_updated_at: 2026-08-27T01:03:34.722Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-01T08:09:59.466Z
sync_session: 36
stale: true
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-028.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-115]]", "[[API-203]]"]
  required_roles: ["[[ROLE-003]]"]
  realizes_use_cases: ["[[UC-024]]"]
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

PORTAL_USER가 진입하는 포털 메인 화면. 데이터마트에서 검수 완료(APPROVED)되고 프레임 1건 이상 보유한 영상만 노출하며, 영상 카드 또는 '시작하기' 버튼 클릭 시 해당 영상 첫 프레임(firstSrcSn)의 라벨링 화면(/portal/label/{srcSn})으로 진입한다. 헤더에 본인 자산 업로드 관리 화면(/portal/uploads) 진입 링크('내 업로드')를 제공한다. KPI 2종(영상 수/라벨링 완료 — 라벨링 완료는 현재 0 고정 표시)을 노출한다. 오토라벨링·SAM2·VLM·검수·버전관리는 포털 전 구간에서 미제공. 반응형(WCAG 2.1 AA). 접근: PORTAL_USER.

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

- **note**: 현재 0 고정
- **type**: Stat
- **label**: 라벨링 완료

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: KPI 2개 그리드. '영상 수'는 데이터마트 목록(API-115) totalElements, '라벨링 완료'는 현재 0으로 고정 표시(집계 미연동).

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

- **note**: onPageChange → URL page 갱신. 전체가 한 페이지면 렌더하지 않는다.
- **type**: Pagination
- **label**: 페이지네이션

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **type**: Text
- **label**: 선택 가능한 영상이 없습니다.

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 카드 클릭 영역과 분리된 버튼. 활성 여부는 이 영상을 내려받는 중인지와 다른 영상을 받는 중이라 잠겼는지로만 정한다. myLabelExpiresAt 은 값이 있을 때만 '만료: YYYY-MM-DD' 텍스트로 병기하고, 없으면 그 자리를 비워 카드 정렬을 유지한다. 클릭 시 blob 응답을 받아 브라우저 다운로드 트리거(JWT 인증 하 직링크 불가).
- **type**: Custom
- **label**: 영상 카드 다운로드 버튼(만료일 표시 포함)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DatamartVideoDownloadAction
- **triggers_api**: API-203

#### [6]

- **note**: 내려받는 중일 때만 다운로드 버튼 자리에 나타난다. 누르면 전송을 멈추고 다시 받을 수 있는 상태로 되돌린다. 사용자가 스스로 멈춘 것이므로 실패 안내를 띄우지 않는다.
- **type**: Button
- **label**: 다운로드 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

**description**:

GET /v1/portal/datamart/videos(API-115, PORTAL_USER 전용·검수완료 APPROVED만·프레임 0건 제외·페이징) 목록을 2열 카드 그리드로 표시. 각 카드는 제목 + 이벤트명(없으면 '-') + 프레임 건수 + (본인 저장 라벨이 있는 경우) 만료 예정일 + 다운로드 버튼, 카드 본체 클릭 시 해당 영상 firstSrcSn 으로 라벨링 화면 이동. 로딩 중 안내 텍스트, 빈 목록 시 '선택 가능한 영상이 없습니다.'

목록 아래에 페이지네이션을 둔다. 서버가 페이징으로 내려주는데 화면에 페이지를 옮길 수단이 없으면 첫 페이지 영상만 도달할 수 있고 나머지는 존재해도 고를 수 없다. 페이지를 옮기면 주소의 page 값을 갱신해 뒤로가기와 북마크가 동작하게 하며, 이는 내부 목록 화면이 쓰는 방식과 같다. 전체가 한 페이지에 들어오면 페이저를 그리지 않는다.

내려받는 중에는 같은 자리에서 전송을 멈출 수 있게 한다. 산출물에 영상이 포함되면 크기가 커서 한 번 시작하면 끝날 때까지 기다리는 수밖에 없고, 받는 동안에는 다른 영상의 다운로드도 함께 잠기므로 잘못 눌렀거나 지금 받을 상황이 아닐 때 빠져나올 길이 필요하다. 한 번에 하나만 받는다. 취소하면 전송을 중단하고 다시 받을 수 있는 상태로 되돌아가며 받다 만 파일은 남기지 않는다. 사용자가 누른 취소는 오류가 아니라 정상 종료다 — 실패 안내를 띄우지 않으며, 알린다면 취소되었다는 사실만 중립적으로 알린다. 전송이 끊겨 실패한 경우와 한 갈래로 묶지 않는다. 묶으면 스스로 멈춘 사용자에게 연결을 확인하고 다시 시도하라고 권하게 된다.

**references_apis**:

- API-115
- API-203

**references_features**:

_(empty)_

## brownfield

### status

new

### change_kind

- capability-add

### diff_summary

2차 포털 메인(홈) 화면 — 데이터마트 검수 완료(APPROVED) 영상 목록 진입점

## surface_kind

web

## consumes_apis

- API-115
- API-203

## implementation

### status

implemented

### modules

- MOD-017
- MOD-021

### records

- IMPREC-023

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-17T22:48:30.526Z

### module_paths

_(empty)_

## required_roles

- ROLE-003

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-028/main.html
- **label**: 포털 홈 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **source_hash**: bc47c9c91ef98b3dd6461c7c1201d764e9d8f7053d16167657e4ca06ec68bf19
- **generated_at**: 2026-08-27T01:03:34.721Z
- **generated_by**: generate-wireframes.py

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-024

## covered_by_acceptances

_(empty)_
