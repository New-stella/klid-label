---
logicraft_item: SCREEN-019
type: screen_spec
version: 29
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:41:14.398Z
status: NEW
prev_version: null
content_hash: a63d5a7b34b7441f374949d233bccbd0f716840d3c4692e275d1be08f38a65e3
stale: true
raw: ./_raw/SCREEN-019.json
links:
  belongs_to_domain: ["[[DOMAIN-005]]"]
  consumes: ["[[API-009]]", "[[API-010]]", "[[API-011]]", "[[API-013]]", "[[API-014]]", "[[API-015]]", "[[API-021]]", "[[API-066]]", "[[API-102]]", "[[API-103]]", "[[API-104]]", "[[API-105]]", "[[API-132]]"]
  realizes: ["[[UC-023]]"]
  references: ["[[API-009]]", "[[API-010]]", "[[API-011]]", "[[API-013]]", "[[API-014]]", "[[API-015]]", "[[API-021]]", "[[API-066]]", "[[API-102]]", "[[API-103]]", "[[API-104]]", "[[API-105]]", "[[API-132]]"]
  requires: ["[[ROLE-001]]"]
  applies_to_backward: ["[[SHELL-001]]"]
  designs_backward: ["[[SD-005]]"]
  granted_on_backward: ["[[ROLE-001]]"]
  navigates_to_backward: ["[[NAV-001]]"]
  realizes_backward: ["[[MOD-009]]"]
  references_backward: ["[[TEST-004]]", "[[UC-009]]", "[[UC-023]]"]
---

# 검수 상세 화면

## route

/review/:id

## title

검수 상세 화면

## device

desktop

## status

draft

## purpose

REVIEWER가 영상 검수 상세를 확인하고 승인 또는 반려를 결정하는 화면. 상단에 영상 메타 정보와 상태를 보여주는 헤더, 그 아래 프레임 이동 컨트롤이 있는 상단바, 캔버스 위의 프레임 썸네일 스트립, 중앙의 읽기 전용 라벨 캔버스, 우측에 객체/메타/이슈 3개 탭으로 구성된 패널이 배치된다. 승인·반려 액션은 검수 헤더가 단독으로 담당한다. 우측 패널의 '객체' 탭은 프레임 라벨 목록·선택 객체 속성·검수 메모를 다루고, '메타' 탭은 이벤트 어노테이션과 외부 시계열 메타를 검토하며, '이슈' 탭은 서버 연동 문의 스레드를 다룬다. 영상에 라벨이 하나도 없는 상태에서 승인을 시도하면 확인 절차를 한 번 더 거친다. 승인은 작업 완료를 의미한다. 접근: REVIEWER. 이미 완료(승인)된 영상 중 검수 승인 이후 라벨·메타가 수정되어 다시 승인이 필요한 영상은 재검토 필요로 표시되며, 이 상태에서는 완료 상태여도 승인 버튼이 다시 활성화되어 재승인할 수 있다.

## sections

### 검수 헤더 (영상 메타·상태)

- **role**: header
- **layout**: detail

**components**:

#### [1]

- **note**: 목록 화면으로 돌아간다.
- **type**: Button
- **label**: 검수 페이지 닫기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [2]

- **type**: Heading
- **label**: 영상명 + 영상 번호 / 작업자·제출일

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: review.cctvName

#### [3]

- **type**: Custom
- **label**: 검수 상태 배지

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: review.status
- **custom_name**: StatusBadge

#### [4]

- **note**: needsRecheck=true 인 영상에서 검수 상태 배지 옆에 표시한다. 색상만으로 구분하지 않고 아이콘과 '재검토 필요' 텍스트를 함께 사용한다.
- **type**: Badge
- **label**: 재검토 필요

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: review.needsRecheck

- **description**: 전체 화면 상단 헤더(밝은 배경). 닫기 버튼(목록으로 이동), 영상명+영상 번호, 작업자명·제출일시, 우측 상태 배지(검수대기/검수중/완료/반려). 진입 시 상태가 검수대기이면 자동으로 검수중으로 전환된다. 프레임 이동과 위치 표시는 헤더 바로 아래 별도 상단바가 담당한다.

**references_apis**:

- API-009
- API-013

**references_features**:

_(empty)_

### 상단 프레임 이동 바

- **role**: navigation
- **layout**: stack

**components**:

#### [1]

- **type**: Custom
- **label**: 처음/이전/다음/마지막 프레임 이동

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: FrameNavControls

#### [2]

- **type**: Input
- **label**: 프레임 번호 직접 입력

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 현재 프레임 위치(Frame N/total) 표시를 포함한다.
- **type**: Custom
- **label**: 프레임 위치 슬라이더

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: FrameSlider

- **description**: 헤더 바로 아래에 위치하는 별도 상단바. 처음/이전/프레임 번호 입력/다음/마지막 프레임 이동 컨트롤과 슬라이더로 구성되며, 현재 프레임 위치(Frame N/total)를 이 영역에서 표시한다.

**references_apis**:

- API-010

**references_features**:

_(empty)_

### 프레임 썸네일 스트립

- **role**: navigation
- **layout**: list

**components**:

#### [1]

- **type**: Custom
- **label**: 썸네일 스트립 접기/펼치기

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: FrameStripToggle

#### [2]

- **type**: List
- **label**: 프레임 썸네일 스트립

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: frameList.frames

- **description**: 캔버스 바로 위에 위치하며 접고 펼칠 수 있는 가로 스크롤 썸네일 스트립. 썸네일 클릭 시 현재 프레임이 바뀌고, 현재 프레임 위치로 자동 스크롤한다. 좌우 방향키로도 프레임을 이동할 수 있다. 프레임 이미지는 인증된 요청으로 개별 발급받는다. 프레임이 0건이면 안내 문구를 표시한다. 각 썸네일 테두리 색은 프레임 상태를 우선순위로 나타낸다: 현재 프레임(강조) > 미해소 문의가 있는 프레임(경고색) > 라벨이 저장된 프레임(완료색) > 기본.

**references_apis**:

- API-010
- API-021
- API-103

**references_features**:

_(empty)_

### 라벨 캔버스 (읽기 전용)

- **role**: main
- **layout**: detail

**components**:

#### [1]

- **type**: Custom
- **label**: 읽기 전용 캔버스

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: frameList.frames[currentFrameIdx]
- **custom_name**: LabelCanvas

#### [2]

- **type**: Custom
- **label**: 읽기 전용

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ReadOnlyBadge

- **description**: 캔버스 기반 읽기 전용 렌더러. 현재 프레임 이미지 위에 BBOX/POLYGON 라벨을 카테고리별 색상으로 오버레이하고, hover 시 라벨명 칩을 표시한다. 좌상단에 '읽기 전용' 배지를 고정 표시한다. 프레임 이미지는 인증된 요청(GET /v1/frames/{srcSn}/image)으로 개별 발급받는다. 라벨 데이터는 검수 프레임 목록(API-010) 응답의 라벨 배열을 사용한다.

**references_apis**:

- API-010
- API-021

**references_features**:

_(empty)_

### 검수 우측 패널 — 객체 탭: 카테고리 트리

- **role**: side
- **layout**: tabs

**components**:

#### [1]

- **note**: 우측 패널은 이 3개 탭으로 전환된다. 이 섹션부터 이어지는 3개 섹션(카테고리 트리·선택 객체 속성·검수 메모)은 모두 '객체' 탭 안에 함께 표시된다.
- **type**: Tabs
- **label**: 객체 / 메타 / 이슈

**columns**:

_(empty)_

**options**:

- 객체
- 메타
- 이슈

#### [2]

- **type**: List
- **label**: 카테고리 그룹 트리

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: frameList.frames[currentFrameIdx].labels

#### [3]

- **type**: Custom
- **label**: 라벨 타입 배지

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: TypeBadge

- **description**: 우측 패널 '객체' 탭 상단. 현재 프레임 라벨을 카테고리별로 그룹화한 트리. 그룹은 접고 펼칠 수 있으며, 각 행은 색상점+'{라벨명} #순번'+타입 배지(bbox/polygon/segment/track)로 구성된다. 캔버스에서의 라벨 선택·hover 와 양방향으로 동기화된다. 라벨이 0건이면 안내 문구를 표시한다.

**references_apis**:

- API-010

**references_features**:

_(empty)_

### 검수 우측 패널 — 객체 탭: 선택 객체 속성

- **role**: side
- **layout**: detail

**components**:

#### [1]

- **type**: Card
- **label**: 선택 객체 속성

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: selectedLabel

#### [2]

- **type**: Custom
- **label**: 객체를 선택하세요

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: EmptyState

- **description**: 우측 패널 '객체' 탭 중단. 선택된 라벨의 상세 속성을 표시한다: 카테고리 색상점+'#순번', 타입, BBOX 라벨은 좌표(x/y/w/h), 그 외 타입은 점 개수, 신뢰도(퍼센트), 라벨 유형(자동/수동), 카테고리. 선택된 객체가 없으면 안내 문구를 표시한다.

**references_apis**:

- API-010

**references_features**:

_(empty)_

### 검수 우측 패널 — 객체 탭: 검수 메모

- **role**: side
- **layout**: stack

**components**:

#### [1]

- **note**: 메모를 추가하면 그 시점의 현재 프레임 번호와 선택 객체가 자동으로 태깅된다. 각 메모는 최대 1000자이며 수정·삭제할 수 있다. 서버에 저장되지 않는 이 화면 전용 메모이며, 반려 시 반려 사유에 합쳐진다.
- **type**: List
- **label**: 프레임/객체별 검수 메모 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: reviewMemos.items

- **description**: 우측 패널 '객체' 탭 하단. 프레임/객체별 검수 메모 목록(서버 미연동, 이 화면 전용) — 메모 추가 시점의 프레임과 선택 객체가 자동 태깅된다. 메모는 반려 시 반려 사유에 합쳐진다. 이 화면 전용 메모와 별개로, 검수 요청 이전에 이미 서버에 등록되어 있던 이슈가 있으면 같은 목록에 참고용으로 함께 표시된다.

**references_apis**:

- API-011

**references_features**:

_(empty)_

### 검수 우측 패널 — 메타 탭

- **role**: side
- **layout**: stack

**components**:

#### [1]

- **note**: 이벤트 유형·시작/종료 시각 등 이벤트 어노테이션 내용을 검토한다.
- **type**: Custom
- **label**: 이벤트 어노테이션 검토

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: EventAnnotationReviewPanel

#### [2]

- **note**: 외부 시계열 분석 결과(서술 전문과 일치도)를 프레임 단위로 검토한다. 서술 전문은 검수자가 확인·수정할 수 있고, 일치도는 참고용으로 읽기 전용 표시된다.
- **type**: Custom
- **label**: 시계열 메타 검토

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: TimeseriesMetaReviewPanel

#### [3]

- **type**: Custom
- **label**: 영상 기술 정보(읽기 전용)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: VideoTechnicalMetaPanel

#### [4]

- **type**: Custom
- **label**: 표시할 메타 정보가 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: EmptyState

- **description**: 우측 패널 '메타' 탭. 이벤트 어노테이션 검토와 외부 시계열 메타 검토(서술 전문 확인·수정 가능, 일치도는 읽기 전용) 두 영역으로 구성된다. 검토 대상에 포함되지 않는 영상 기술 정보(해상도·코덱 등)도 참고용으로 함께 표시되며, 표시할 정보가 전혀 없으면 빈 상태 안내를 노출한다.

**references_apis**:

- API-132
- API-066

**references_features**:

_(empty)_

### 검수 우측 패널 — 이슈 탭

- **role**: side
- **layout**: list

**components**:

#### [1]

- **note**: '객체' 탭 안의 메모(이 화면 전용)와는 서로 다른 별개 기능이다.
- **type**: List
- **label**: 문의 스레드

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: issueThread.items

#### [2]

- **type**: Button
- **label**: 문의 등록

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **type**: Button
- **label**: 댓글 추가

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **type**: Button
- **label**: 해결 처리

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **type**: Badge
- **label**: 미해결 문의 건수

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **type**: Badge
- **label**: 이슈 유형·진행 상태 배지

**columns**:

_(empty)_

**options**:

_(empty)_

#### [7]

- **type**: Custom
- **label**: 동시 처리 충돌 안내

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ConflictNotice

- **description**: 우측 패널 '이슈' 탭. 검수 중 발견한 문제를 등록·댓글·해결 처리하는 서버 연동 문의 스레드. '객체' 탭 안의 메모(이 화면 전용)와는 서로 다른 별개 기능이다. 상단에 아직 해결되지 않은 문의 건수를 표시하고, 각 스레드는 유형(반려/문의)과 진행 상태(미해결/답변완료/해결)를 배지로 구분해 보여준다. 반려 이력은 해결 처리 이후에도 댓글을 남길 수 있지만, 해결된 문의는 댓글 입력이 잠긴다. 다른 사용자가 같은 문의를 먼저 처리한 경우 동시 처리 충돌 안내를 표시한다. 각 스레드와 각 댓글에는 작성자를 '이름 (역할)' 형태로 표시한다 — 역할은 코드값이 아니라 한글 호칭(작업자·검수자)으로 바꿔 보여주고, 목록에 없는 값은 받은 값을 그대로 쓴다. 이름을 해석하지 못하면 사번으로 대신하고, 역할을 해석하지 못하면 빈 괄호를 남기지 않고 이름만 표시한다. 문의는 작업자와 검수자가 모두 등록할 수 있어 이 표기가 누가 낸 문의인지 가르는 축이 된다. ★스레드 작성자의 역할과 댓글 작성자의 역할은 기준 시점이 다르다 — 댓글은 작성 당시의 역할을 그대로 보존해 보여주고, 스레드는 조회하는 시점의 현재 역할을 보여준다. 따라서 문의를 낸 뒤 역할이 바뀐 사용자는 스레드에서는 바뀐 역할로, 그 사람이 그때 남긴 댓글에서는 당시 역할로 보인다.

**references_apis**:

- API-102
- API-103
- API-104
- API-105

**references_features**:

_(empty)_

### 검수 액션 (승인·반려 — 검수 헤더)

- **role**: footer
- **layout**: stack

**components**:

#### [1]

- **type**: Button
- **label**: 반려

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive
- **triggers_api**: API-015

#### [2]

- **type**: Button
- **label**: 승인

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-014

#### [3]

- **type**: Dialog
- **label**: 승인 확정 확인창

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-014

#### [4]

- **type**: Dialog
- **label**: 반려 처리 창

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-015

#### [5]

- **note**: 라벨이 없는 영상을 승인하려 할 때 이 확인창이 먼저 뜬다. 객체가 실제로 없는 정상 영상이면 그대로 승인할 수 있음을 안내하고, 검수자가 명시적으로 확인하면 '라벨 없음 확인' 사실이 작업 이력에 기록되며 승인이 처리된다.
- **type**: Dialog
- **label**: 라벨 없음 확인창

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-014

- **description**: 승인·반려는 검수 헤더가 단독으로 담당한다. 반려/승인 버튼은 검수대기·검수중 상태에서만 활성화되고, 완료·반려 상태에서는 모두 비활성화되며 이미 처리된 검수임을 안내한다. 두 액션은 동시에 진행되지 않으며 한쪽이 진행 중이면 다른 쪽도 비활성이다. 승인 클릭 시 승인 확정 확인창을 거쳐 승인 처리하고 목록으로 돌아간다. ★해당 영상에 라벨이 하나도 없으면 승인이 곧바로 처리되지 않고 '라벨 없음 확인' 창이 먼저 뜬다 — 객체가 실제로 없는 정상 영상이면 검수자가 명시적으로 확인해야 승인이 완료되며, 그 확인 사실은 작업 이력에 남는다. 반려 클릭 시 반려 사유(1~1000자) 입력창을 거쳐 반려 처리하고 목록으로 돌아간다. 반려 사유에는 검수 의견과 메모 목록 내용이 함께 담긴다. ★재검토 필요(needsRecheck=true) 영상은 예외다 — 상태가 완료이어도 승인 버튼이 활성화되어 다시 승인할 수 있다(재승인). 재승인도 승인 확정 확인창을 거치며, 승인 상태 자체는 이미 완료이므로 전이 없이 재검토 필요 표시만 해제된다. 반려 버튼의 활성화 여부는 이 예외와 무관하게 기존 규칙(완료 상태 비활성)을 따른다.

**references_apis**:

- API-014
- API-015

**references_features**:

_(empty)_

## brownfield

### status

modified

### decided_by

ADR-002

### change_kind

- merge

### diff_summary

단일 검수 상세(영상 단위)

## surface_kind

web

## consumes_apis

- API-009
- API-010
- API-011
- API-013
- API-014
- API-015
- API-021
- API-132
- API-066
- API-102
- API-103
- API-104
- API-105

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

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-019/main.html
- **label**: 검수 상세 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **source_hash**: cd33b768074c7f455b05686e7c75296c212cc9d361aebd13eb8feb58ed7fc3e6
- **generated_at**: 2026-08-14T23:44:28.831Z
- **generated_by**: generate-wireframes.py

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-023

## covered_by_acceptances

_(empty)_
