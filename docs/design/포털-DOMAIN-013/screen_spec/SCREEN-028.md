---
logicraft_item: SCREEN-028
type: screen_spec
version: 29
domain: DOMAIN-013
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-01T12:37:59.566Z
status: CHANGED
prev_version: 26
content_hash: 3e9011cb4a67f2dce7b7de6337879df04512ba9d077447f09d4b1b16668e8a51
stale: true
raw: ./_raw/SCREEN-028.json
links:
  belongs_to_domain: ["[[DOMAIN-013]]"]
  consumes: ["[[API-203]]", "[[API-225]]"]
  implements: ["[[IMPREC-023]]"]
  realizes: ["[[UC-024]]"]
  references: ["[[API-203]]"]
  requires: ["[[ROLE-003]]"]
  designs_backward: ["[[SD-024]]"]
  granted_on_backward: ["[[ROLE-003]]"]
  navigates_to_backward: ["[[NAV-002]]"]
  realizes_backward: ["[[MOD-017]]", "[[MOD-021]]"]
  references_backward: ["[[SEQ-016]]", "[[UC-024]]"]
---

# 포털 내 작업 화면

## route

/portal

## title

포털 내 작업 화면

## device

responsive

## status

draft

## purpose

PORTAL_USER 가 본인이 저장한 작업을 관리하는 화면. 저장한 작업을 목록으로 보여 주고, 각 행에서 대상 영상과 저장 시각·만료 예정일을 확인하고 라벨링 화면으로 이어서 작업하거나 본인 작업 데이터를 내려받는다. 데이터마트에서 영상을 고르는 목록은 포털이 자기 화면에서 제공하며, 거기서 고른 영상은 라벨링 화면으로 바로 진입한다. 본인 업로드 자산 관리 화면(/portal/uploads) 진입 링크를 함께 제공한다. 오토라벨링·SAM2·검수·버전관리는 포털 전 구간에서 미제공이며, 시계열 축의 미제공은 외부 시계열 분석 서버로 나가는 위탁 연동(호출·콜백)을 뜻한다. 반응형(WCAG 2.1 AA). 접근: PORTAL_USER.

## sections

### 본인 업로드 자산 진입

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: → /portal/uploads
- **type**: Link
- **label**: 내 업로드

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 본인 업로드 자산 관리 화면(/portal/uploads)으로 가는 진입 링크. 이 화면이 데이터마트에서 받은 영상의 작업을 다루는 것과 달리, 그 화면은 사용자가 직접 올린 자산을 다룬다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 내 저장 작업 목록

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Table
- **label**: 내 저장 작업 목록

**columns**:

- 대상 영상
- 저장 시각
- 만료 예정일
- 작업

**options**:

_(empty)_

#### [2]

- **note**: → /portal/label/{srcSn}
- **type**: Button
- **label**: 이어서 작업

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [3]

- **note**: 누르면 그 작업의 데이터 묶음을 내려받는다. 한 번에 하나만 받으며 받는 동안 다른 행의 내려받기는 잠긴다. 클릭 시 blob 응답을 받아 브라우저 다운로드를 트리거한다(인증이 필요해 직링크를 쓸 수 없다).
- **type**: Button
- **label**: 내려받기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline
- **triggers_api**: API-203

#### [4]

- **note**: 내려받는 중일 때만 내려받기 버튼 자리에 나타난다. 누르면 전송을 멈추고 다시 받을 수 있는 상태로 되돌린다. 사용자가 스스로 멈춘 것이므로 실패 안내를 띄우지 않는다.
- **type**: Button
- **label**: 다운로드 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [5]

- **type**: Text
- **label**: 저장한 작업이 없습니다.

**columns**:

_(empty)_

**options**:

_(empty)_

**description**:

본인이 저장한 작업을 행 단위로 보여 준다. 각 행은 대상 영상 식별자와 이름, 저장 시각, 만료 예정일, 그리고 이어서 작업·내려받기 두 액션으로 이루어진다. '이어서 작업'은 그 작업의 대상 영상 라벨링 화면(/portal/label/{srcSn})으로 이동하며 같은 배포본 안에서의 이동이다.

만료 예정일은 '만료: YYYY-MM-DD' 형식으로 날짜까지만 표기한다(시각은 표기하지 않는다). 저장한 작업 데이터는 보존기간이 지나면 저장 행과 파일이 함께 삭제되므로, 사용자가 내려받을 시점을 놓치지 않도록 목록에서 바로 보이게 둔다. 조회 시점 설정으로 계산되어 응답에 실려 오므로 화면이 따로 보관하지 않고 받은 값을 그대로 표시하며, 값이 없는 행은 그 자리를 비워 정렬을 유지한다. 만료가 가까운 작업을 색·아이콘으로 강조하는 표기는 두지 않는다. 본인 업로드 자산 목록(SCREEN-033)과 같은 규칙이다.

내려받기는 프레임 이미지와 라벨 문서, 비식별 영상을 한 묶음으로 받는다. 한 번에 하나만 받으며 받는 동안 다른 행의 내려받기는 잠기므로, 같은 자리에서 전송을 멈출 수 있게 한다. 취소하면 전송을 중단하고 다시 받을 수 있는 상태로 되돌아가며 받다 만 파일은 남기지 않는다. 사용자가 누른 취소는 오류가 아니라 정상 종료다 — 실패 안내를 띄우지 않으며, 전송이 끊겨 실패한 경우와 한 갈래로 묶지 않는다. 저장한 작업이 하나도 없으면 '저장한 작업이 없습니다.'를 표시한다.

**references_apis**:

- API-203

**references_features**:

_(empty)_

## brownfield

### status

new

### change_kind

- capability-add

### diff_summary

2차 포털 본인 저장 작업 관리 화면 — 이어서 작업·내려받기·만료 예정일 확인

## surface_kind

web

## consumes_apis

- API-225
- API-203

## attached_files

_(empty)_

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

## uses_components

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-024

## covered_by_acceptances

_(empty)_
