---
logicraft_item: SCREEN-011
type: screen_spec
version: 21
last_updated_at: 2026-08-17T12:44:49.243Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-25T09:51:54.736Z
sync_session: 23
stale: true
status: NEW
prev_version: null
raw: ./_raw/SCREEN-011.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-042]]", "[[API-055]]", "[[API-072]]"]
  required_roles: ["[[ROLE-001]]", "[[ROLE-002]]"]
---

# 대시보드 화면

## route

/dashboard

## title

대시보드 화면

## device

desktop

## status

draft

## purpose

작업·검수 현황 요약을 보여주는 메인 대시보드. 접근: REVIEWER/WORKER.

## sections

### 헤더 (제목·새로고침)

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **note**: 부제 '시스템 요약 정보를 확인할 수 있습니다.' 가 함께 노출된다.
- **type**: Heading
- **label**: 대시보드

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 클릭 시 요약/영상/작업 조회를 재조회한다. 버튼 좌측에 마지막 새로고침 시각(시계 아이콘 + YYYY-MM-DD HH:mm:ss)을 정적 텍스트로 병기 — 자동 갱신 없이 버튼을 눌러야 갱신된다.
- **type**: Button
- **label**: 새로고침

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 좌측 '대시보드' 제목(부제 '시스템 요약 정보를 확인할 수 있습니다.'), 우측에 '새로고침' 버튼(마지막 새로고침 시각을 정적 텍스트로 병기, 클릭 시 요약·영상·작업 쿼리 재조회).

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### KPI 카드 그리드 (역할별)

- **role**: hero
- **layout**: grid

**components**:

#### [1]

- **note**: summary.pendingCount, 단위 건
- **type**: Stat
- **label**: 처리 대기

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-055

#### [2]

- **note**: summary.completedCount, 단위 건
- **type**: Stat
- **label**: 처리 완료

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-055

#### [3]

- **note**: summary.myTaskCount — WORKER 한정 카드
- **type**: Stat
- **label**: 내 작업

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-055

#### [4]

- **note**: summary.rejectedCount, 단위 건
- **type**: Stat
- **label**: 반려 건수

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-055

#### [5]

- **note**: isLoading 시 역할별 3|4개 Skeleton
- **type**: Skeleton
- **label**: 로딩 ×3|4

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 역할별 카드 수 분기 — WORKER 4카드(처리 대기/처리 완료/내 작업/반려 건수), REVIEWER 3카드(내 작업 제외). 로딩 시 Skeleton. 값은 /stats/summary 응답(pendingCount/completedCount/myTaskCount/rejectedCount).

**references_apis**:

- API-055

**references_features**:

_(empty)_

### 데이터 개수 카드 (이미지·영상, 이벤트 분포)

- **role**: main
- **layout**: grid

**components**:

#### [1]

- **note**: 주 수치=approvedImageCount(검수완료 기준, 장). 보조 텍스트 '검수완료 기준 · 전체 {cumulativeImageCount}장 (완료율 N%)'. 하단 이벤트 분포는 approvedImageDistribution(검수완료 기준). 주 수치와 프레임 단위 분포는 폐기되지 않은 프레임만 센다 — 보조의 전체 수치는 걸러내지 않는다.
- **type**: Card
- **label**: 이미지 데이터 개수

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-055

#### [2]

- **note**: 주 수치=approvedVideoCount(검수완료 기준, 건). 보조 텍스트 '검수완료 기준 · 전체 {cumulativeVideoCount}건 (완료율 N%)'. 하단 이벤트 분포는 approvedEventDistribution(검수완료 기준).
- **type**: Card
- **label**: 영상 데이터 개수

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-055

#### [3]

- **note**: 고정 6개 슬롯이 아니라 서버가 내려주는 카테고리를 그대로 순회 렌더(개수 무관). 그룹핑은 이벤트유형 표시명 그룹 정책을 따른다. 관측 예시 팔레트(최대 10항목): 침수(범람)·산사태·화재·쓰러짐·파손·교통사고·싸움·흉기소지·납치(유괴)·그 외.
- **type**: List
- **label**: 이벤트 유형 분포 (개수 가변)

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 2열 카드. '이미지 데이터 개수'(주 수치 approvedImageCount 장, 보조 '검수완료 기준 · 전체 N장(완료율 X%)') + '영상 데이터 개수'(주 수치 approvedVideoCount 건, 동일 보조 패턴). 각 카드 하단에 검수완료 기준 이벤트 분포(approvedImageDistribution/approvedEventDistribution)를 개수 무관하게 그대로 순회 렌더. 모두 /stats/summary 응답. 이미지 카드의 주 수치와 그 하단 프레임 단위 분포는 검수완료 영상의 프레임 중 폐기되지 않은 것만 센 값이다 — 학습데이터 산출물과 데이터마트 노출이 폐기된 프레임을 구조적으로 제외하므로 확정 분량을 뜻하는 이 수치도 같은 집합이어야 한다. 보조로 병기하는 전체 기준 수치는 수집한 전체 분량이라는 다른 축이라 폐기 여부로 걸러내지 않는다. 영상 카드와 영상 단위 분포는 폐기와 무관하다.

**references_apis**:

- API-055

**references_features**:

_(empty)_

### 최근 완료 영상 테이블

- **role**: main
- **layout**: list

**components**:

#### [1]

- **note**: size=5, 정렬=reviewCompletedAt desc + 필수 필터 reviewStatusCd=APPROVED(둘은 짝으로만 전송 — 필터 없이 정렬만 보내면 BE가 조인 전용 정렬키를 무시해 미검수 영상까지 섞인다).
- **type**: Table
- **label**: CCTV명/이벤트/길이/완료일

**columns**:

- CCTV명
- 이벤트
- 길이
- 완료일

**options**:

_(empty)_

- **triggers_api**: API-042

#### [2]

- **note**: eventTypeCd 가 있으면 그 값을, 없으면 eventName 을 표시한다.
- **type**: Badge
- **label**: EventTypeBadge 이벤트 배지

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 목록 조회 중 표시
- **type**: Skeleton
- **label**: 로딩

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 정렬=reviewCompletedAt desc + 필수 필터 reviewStatusCd=APPROVED 조합으로 상위 5건 영상 목록(size=5, /videos). 컬럼: CCTV명/이벤트(EventTypeBadge)/길이(formatDuration)/완료일(MM-DD HH:mm). 빈 상태 '영상이 없습니다.', 로딩 Skeleton.

**references_apis**:

- API-042

**references_features**:

_(empty)_

### 내 작업 현황 테이블 (WORKER 한정)

- **role**: main
- **layout**: list

**components**:

#### [1]

- **note**: workerId=본인 size=5 로 조회, WORKER 역할에서만 렌더
- **type**: Table
- **label**: 영상/상태/진행률

**columns**:

- 영상
- 상태
- 진행률

**options**:

_(empty)_

- **triggers_api**: API-072

#### [2]

- **type**: Badge
- **label**: StatusBadge 작업 상태

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: COMPLETED 100·IN_PROGRESS 50·그 외 0
- **type**: Progress
- **label**: ProgressBar 진행률

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 목록 조회 중 표시
- **type**: Skeleton
- **label**: 로딩

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: WORKER 역할에서만 렌더. 본인 배정 작업 상위 5건(size=5, /assignments). 컬럼: 영상/상태(StatusBadge)/진행률(ProgressBar — COMPLETED 100·IN_PROGRESS 50·그 외 0). 빈 상태 '작업이 없습니다.', 로딩 Skeleton.

**references_apis**:

- API-072

**references_features**:

_(empty)_

### 에러 상태 배너

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: /stats/summary 조회 실패 시 ErrorState
- **type**: Alert
- **label**: 대시보드 정보를 불러올 수 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

- **description**: /stats/summary 조회 실패 시 상단에 ErrorState 노출('대시보드 정보를 불러올 수 없습니다').

**references_apis**:

- API-055

**references_features**:

_(empty)_

## brownfield

### status

preserved

### diff_summary

1차 대시보드 기반

## surface_kind

web

## consumes_apis

- API-042
- API-055
- API-072

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

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-011/main.html
- **label**: 대시보드 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **description**: 
- **source_hash**: ca9da9dfc3f5d7f2655838d4bcb045cb998af598ccc60e115f40a834131a83a0
- **generated_at**: 2026-08-17T12:44:49.242Z
- **generated_by**: generate-wireframes.py

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
