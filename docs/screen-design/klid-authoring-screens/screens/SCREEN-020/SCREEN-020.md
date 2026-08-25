---
logicraft_item: SCREEN-020
type: screen_spec
version: 32
last_updated_at: 2026-08-18T03:34:50.526Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-24T14:23:30.459Z
sync_session: 22
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-020.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-001]]", "[[API-056]]"]
  required_roles: ["[[ROLE-001]]", "[[ROLE-002]]"]
---

# 작업자 통계 화면

## route

/stat/worker

## title

작업자 통계 화면

## device

desktop

## status

draft

## purpose

요약·작업자별 통계를 조회하는 화면. 접근: REVIEWER/WORKER.

## sections

### 헤더·작업자 선택

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **note**: REVIEWER 는 제목 '작업자 통계'(부제 '작업자별 통계를 확인합니다.'), WORKER 는 제목 '나의 통계'(부제 '나의 작업 통계를 확인합니다.') — 역할별로 제목·부제가 분기된다.
- **type**: Heading
- **label**: 작업자 통계

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 구현에 따라 렌더 여부가 다르다 — 작업자 select 자체가 선택된 이름을 보여주므로 이 서브타이틀을 생략하는 구현도 있다. 필드(workerStat.workerName)는 항상 응답에 존재한다.
- **type**: Custom
- **label**: 조회 대상 작업자명

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: workerStat.workerName
- **custom_name**: WorkerNameSub

#### [3]

- **note**: REVIEWER 전용(대상: WORKER 역할 사용자 목록, /users, size=100). WORKER 본인은 claims.sub 로 고정되어 select 자체가 렌더되지 않는다. 초기값 없음(자동 폴백 없음) — REVIEWER 가 아무것도 선택하지 않으면 대상 workerId 가 비어 있어 아래 '미선택 안내' 섹션이 KPI/차트/표 대신 렌더된다.
- **type**: Select
- **label**: 작업자 선택
- **state**: default

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 역할별 헤더 — REVIEWER: 제목 '작업자 통계' + 우측 작업자 선택 select(대상: WORKER 역할 사용자 목록, /users, size=100). WORKER: 제목 '나의 통계', select 미노출(claims.sub 고정). 대상 workerId 결정: REVIEWER=선택값(초기값 없음, 자동 폴백 없음), WORKER=claims.sub.

**references_apis**:

- API-056
- API-001

**references_features**:

_(empty)_

### 미선택 안내 (EmptyState)

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: REVIEWER 가 작업자를 선택하지 않았을 때만 렌더 — 아래 KPI/보조지표/차트/표 섹션 전체를 대체한다. 아이콘 + 안내문구 '상단에서 작업자를 선택하면 해당 작업자의 통계가 표시됩니다.' WORKER 는 대상이 항상 고정돼 있어 이 상태에 도달하지 않는다.
- **type**: Custom
- **label**: 작업자를 선택하세요

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: EmptyState

- **description**: REVIEWER 가 작업자를 고르기 전 상태 — 아래 KPI/보조지표/일별 차트/월별 표 섹션 전체 대신 이 안내만 렌더된다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### KPI 4종

- **role**: hero
- **layout**: grid

**components**:

#### [1]

- **note**: 주 수치 workerStat.completed(검수완료). 보조로 workerStat.assignedTotal 과 workerStat.completionRate 를 한 줄로 병기한다.
- **type**: Stat
- **label**: 완료

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: workerStat.completed

#### [2]

- **type**: Stat
- **label**: 작업중

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: workerStat.inProgress

#### [3]

- **type**: Stat
- **label**: 반려

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: workerStat.rejected

#### [4]

- **note**: 주 수치 workerStat.approvedLabelCount(검수완료 영상의 라벨 중 폐기되지 않은 프레임의 라벨). 보조로 전체 workerStat.labelCount 만 병기하고 비율은 붙이지 않는다 — 그 값은 폐기 여부로 걸러내지 않는다.
- **type**: Stat
- **label**: 총 라벨 수

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: workerStat.approvedLabelCount

**description**:

완료/진행중/반려/총 라벨 수 4개 KpiCard. 로딩 중 Skeleton ×4. data null 시 0 fallback. 데이터 출처는 /stats/worker 응답.

완료 카드와 총 라벨 수 카드는 검수완료를 주 수치로 두고 전체를 함께 보여준다 — 학습데이터로 확정된 것은 검수를 통과한 분량뿐이기 때문이다. 완료 카드는 completed 를 크게 두고 그 아래 '검수완료 기준 · 전체 N건 (완료율 M%)' 을 덧붙이고(assignedTotal·completionRate), 총 라벨 수 카드는 approvedLabelCount 를 주 수치로 두고 그 아래 '검수완료 기준 · 전체 N개' 를 덧붙인다 — 완료율은 붙이지 않는다. 총 라벨 수 카드의 주 수치는 검수완료 영상의 라벨 중 폐기되지 않은 프레임의 라벨만 센 값이다 — 학습데이터 산출물과 데이터마트 노출이 폐기된 프레임을 구조적으로 제외하므로 확정 분량을 뜻하는 이 수치도 같은 집합이어야 한다. 보조로 병기하는 전체 라벨 수는 배정된 전체 분량이라는 다른 축이라 폐기 여부로 걸러내지 않는다. completionRate 는 영상 건수의 비율이라 라벨 분량에 그대로 쓸 수 없고 라벨 단위 비율은 서버가 내려주지 않으므로, 화면에서 지어내지 않는다. 진행중·반려 카드는 그 자체가 미완료 상태를 세는 값이라 병기 대상이 아니다.

완료율은 0~1 비율로 오므로 표시할 때만 백분율로 바꾼다. 전체가 0이면 0%로 표시하며, 비율을 화면에서 다시 나눠 구하지 않는다 — 서버가 내려준 값을 그대로 쓴다.

**references_apis**:

- API-056

**references_features**:

_(empty)_

### 보조 지표 (오토라벨 비율·반려율)

- **role**: main
- **layout**: grid

**components**:

#### [1]

- **type**: Stat
- **label**: 오토라벨 비율

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: workerStat.autoLabelRate

#### [2]

- **type**: Stat
- **label**: 반려율

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: workerStat.rejectRate

- **description**: data 존재 시에만 렌더. 오토라벨 비율(autoLabelRate*100, 1자리)·반려율(rejectRate*100, 1자리). 값이 유한하지 않으면 '—' 표시. 반려율 10% 초과 시 빨간색 강조.

**references_apis**:

- API-056

**references_features**:

_(empty)_

### 일별 작업량 차트 (최근 30일)

- **role**: main
- **layout**: dashboard

**components**:

#### [1]

- **type**: Chart
- **label**: 일별 완료 바차트

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: workerStat.dailyCompletion

- **description**: 최근 30일 일별 작업량 막대 차트. 가로축은 날짜, 세로축은 그날 완료 건수다. 작업자 통계 응답의 일별 완료 목록을 그대로 그리며, 목록이 비어 있으면 빈 차트를 표시한다.

**references_apis**:

- API-056

**references_features**:

_(empty)_

### 월별 통계 표 (최근 12개월)

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Table
- **label**: 월별 통계

**columns**:

- 월
- 완료
- 반려
- 라벨 수

**options**:

_(empty)_

- **binds_to**: workerStat.monthly

- **description**: 월/완료/반려/라벨 수 4열 테이블. data.monthly(최근 12개월) 매핑. 비어있으면 '월별 데이터가 없습니다' empty row. 반려 컬럼은 빨간색, 숫자는 ko-KR locale 포맷.

**references_apis**:

- API-056

**references_features**:

_(empty)_

## brownfield

### status

preserved

### diff_summary

1차 작업 통계 기반

## surface_kind

web

## consumes_apis

- API-001
- API-056

## implementation

### status

implemented

### modules

- MOD-021
- MOD-012

### records

- IMPREC-022

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-17T22:48:30.382Z

## required_roles

- ROLE-001
- ROLE-002

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-020/main.html
- **label**: 작업자 통계 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **description**: 
- **source_hash**: 27c3182c5f61af56a6b0870bc4100d68102b7b9de8ae39991333207f649242aa
- **generated_at**: 2026-08-18T03:34:50.525Z
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
