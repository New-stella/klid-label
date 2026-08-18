---
logicraft_item: SCREEN-018
type: screen_spec
version: 26
last_updated_at: 2026-08-18T03:34:50.014Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-18T07:16:50.244Z
sync_session: 18
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-018.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-008]]", "[[API-138]]"]
  required_roles: ["[[ROLE-001]]"]
  realizes_use_cases: ["[[UC-023]]"]
---

# 검수 목록 화면

## route

/review

## title

검수 목록 화면

## device

desktop

## status

draft

## purpose

REVIEWER가 검수 대상 영상 목록을 조회하는 화면(/review/pending alias). 진입 기본값은 검수요청(REVIEW_PENDING) + 제출일 오래된순(FIFO)이며 URL 에 기록되어 새로고침·북마크·뒤로가기에서도 유지된다(필터의 단일 진실원=URL). 화면에서 행을 다시 거르지 않는다 — 서버가 이미 거른 결과를 그대로 그린다. 미등록 정렬 키는 이 화면(/v1/reviews*)에서 lenient 200 + 기본 정렬 폴백 + WARN(작업목록의 strict 400과 의도적으로 다름 — '변경 전 그 엔드포인트가 200이었는가' 기준이며 통일하지 않는다). KPI 4장(검수요청/검수중/승인/반려)는 GET /v1/reviews/summary(API-138) 서버 집계 전체 기준이며 카드 클릭으로 상태 필터 토글(재클릭 해제). 접근: REVIEWER.

## sections

### 헤더 (제목·새로고침)

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 검수 목록

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 화면 상단 제목 아래의 부제 문구.
- **type**: Text
- **label**: 작업자가 제출한 라벨링 결과를 검수합니다.

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 목록을 다시 조회한다.
- **type**: Button
- **label**: 새로고침

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 좌측 '검수 목록' 제목 + 부제 문구, 우측 새로고침 버튼.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 검수 현황 KPI

- **role**: hero
- **layout**: grid

**components**:

#### [1]

- **note**: status=REVIEW_PENDING 건수
- **type**: Card
- **label**: 검수요청

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: status=REVIEWING 건수
- **type**: Card
- **label**: 검수중

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: status=COMPLETED 건수
- **type**: Card
- **label**: 승인

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: status=REJECTED 건수
- **type**: Card
- **label**: 반려

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: KPI 4카드(검수 대기/검수중/승인/반려)는 GET /v1/reviews/summary(API-138) 서버 집계로 필터 결과 전체 기준이며 현재 페이지 20건 안에서 세지 않는다. 카드에 장식 아이콘을 두지 않는다 — 지표명과 값 텍스트가 카드의 정보를 모두 전달한다. 로딩/에러/정상 3상태 분기, 집계 실패해도 목록 표시는 막지 않는다. 카드 클릭은 status 필터를 토글(같은 카드 재클릭 시 해제=전체).

**references_apis**:

- API-138

**references_features**:

_(empty)_

### 검색·상태 필터

- **role**: filter
- **layout**: stack

**components**:

#### [1]

- **type**: Input
- **label**: 영상명 / 작업자명

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: keyword
- **placeholder**: 검색어를 입력하세요

#### [2]

- **type**: Select
- **label**: 상태

**columns**:

_(empty)_

**options**:

- 전체
- 검수요청
- 검수중
- 승인
- 반려

- **binds_to**: statusFilter

#### [3]

- **type**: Button
- **label**: 조회

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [4]

- **note**: 필터 활성 시에만 enabled
- **type**: Button
- **label**: 초기화

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

- **description**: 영상명/작업자명 키워드(q)와 상태(status) select 는 URL 이 단일 진실원이며 GET /v1/reviews(API-008)로 서버 위임된다 — 화면에서 현재 페이지 행을 다시 거르지 않는다. 초기화 버튼은 진입 기본값(검수요청·오래된순)으로 되돌리며, 이미 기본값이면(정렬 포함) 비활성화된다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 검수 목록 테이블

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Table
- **label**: 검수 목록

**columns**:

- 영상명
- 이벤트
- 작업자
- 제출일
- 라벨 수
- 상태
- 액션

**options**:

_(empty)_

#### [2]

- **note**: EventTypeBadge — eventName 없으면 -
- **type**: Badge
- **label**: 이벤트 유형

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: StatusBadge — REVIEW_PENDING/REVIEWING/COMPLETED/REJECTED
- **type**: Badge
- **label**: 상태

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 상태별 라벨·variant 분기, /review/{id} 이동
- **type**: Button
- **label**: 검수 시작 / 이어서 검수 / 결과 보기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [5]

- **note**: onPageChange → URL page 갱신
- **type**: Pagination
- **label**: 페이지네이션

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **type**: Custom
- **label**: 갱신 중 안내(이전 결과 표시)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: RefreshingNotice

#### [7]

- **note**: needsRecheck=true 인 영상의 상태 셀에 StatusBadge와 나란히 표시한다. 색상만으로 구분하지 않고 '재검토 필요' 텍스트를 함께 표시한다. 필터·정렬 축은 추가하지 않는다(표시 전용).
- **type**: Badge
- **label**: 재검토 필요

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: DataTable로 검수 대상 영상을 페이징 표시. 컬럼: 영상명(+video-NNNN id)/이벤트(EventTypeBadge)/작업자/제출일(정렬 가능, BE allowlist 유일 sortable 컬럼)/라벨 수(좌정렬)/상태(StatusBadge, sortable 미노출 — 진입 기본화면은 상태가 한 종류로 수렴해 1차 정렬이 무효화되고 FIFO 의미가 깨질 수 있어 의도적으로 제외)/액션. 행 액션 버튼은 상태별로 라벨·스타일 분기(REVIEW_PENDING=검수 시작 primary / REVIEWING=이어서 검수 / 그 외=결과 보기), 클릭 시 /review/{id} 이동. 서버 페이징(page/size/sort은 URL searchParams)+정렬 변경 시 page=0 리셋. 에러 시 ErrorState, 빈 목록 시 안내. 필터·정렬·페이지 전환 중에는 새 결과가 도착하기 전까지 이전 조건의 행을 그대로 유지하며 '갱신 중' 안내를 함께 표시해 과도기 상태임을 알려준다. 총 페이지 수가 줄어 현재 페이지가 범위를 벗어나면 자동으로 마지막 페이지로 되돌린다. 상태 셀에는 재검토 필요(needsRecheck=true) 영상을 별도로 식별할 수 있는 배지를 StatusBadge와 나란히 표시한다 — 색상만으로 구분하지 않고 '재검토 필요' 텍스트를 함께 표시한다. 이 배지는 표시 전용이며 별도의 필터·정렬 축을 추가하지 않는다.

**references_apis**:

- API-008

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

1차 1·2차 검수 → 2차 단일 검수 목록

## surface_kind

web

## consumes_apis

- API-008
- API-138

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

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-018/main.html
- **label**: 검수 목록 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **source_hash**: 5a5574582cf3216d08f705b17b6a64ec5374f7554e1be7890304f730d3f6671f
- **generated_at**: 2026-08-18T03:34:50.014Z
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
