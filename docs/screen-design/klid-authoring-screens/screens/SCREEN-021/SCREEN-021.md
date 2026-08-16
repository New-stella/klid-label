---
logicraft_item: SCREEN-021
type: screen_spec
version: 25
last_updated_at: 2026-08-16T12:44:00.316Z
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T12:52:08.634Z
sync_session: 13
stale: true
status: CHANGED
prev_version: 23
raw: ./_raw/SCREEN-021.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-057]]", "[[API-058]]"]
  required_roles: ["[[ROLE-001]]"]
---

> ⚠️ **버전 변경 감지 — logicraft v23 → v25**
> change_summary: 정적 HTML 와이어프레임 자동 생성 — 1440×auto (13.8KB)
> ↳ 요약/구현 노트 재검토 후 작성된 코드에 반영. 직전 요약은 git diff 확인.

# 전체 구축 현황 화면

## route

/stat/overall

## title

전체 구축 현황 화면

## device

desktop

## status

draft

## purpose

REVIEWER가 전체 집계·리포트 통계를 조회하는 화면. 접근: REVIEWER.

## sections

### 헤더 + 리포트 다운로드

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 전체 구축 현황

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 다운로드 중 로딩 상태 표시
- **type**: Button
- **label**: 리포트 다운로드

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-058

- **description**: '전체 구축 현황' 제목과 우측 '리포트 다운로드' 버튼. 버튼 클릭 시 period=MONTH 고정값으로 리포트를 요청해 CSV 파일로 저장한다(파일명은 고정 prefix+ISO 날짜, 사용자 입력 미반영). 다운로드 중 로딩 상태 표시, 완료/실패 시 토스트.

**references_apis**:

- API-058

**references_features**:

_(empty)_

### 누적 학습데이터 2카드

- **role**: hero
- **layout**: grid

**components**:

#### [1]

- **note**: 주 수치=approvedImageCount(검수완료 기준, 장). 보조 텍스트 '검수완료 기준 · 전체 {cumulativeImageCount}장(완료율 N%)'.
- **type**: Stat
- **label**: 누적 이미지

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: overall.approvedImageCount

#### [2]

- **note**: 주 수치=approvedVideoCount(검수완료 기준, 건). 보조 텍스트 '검수완료 기준 · 전체 {cumulativeVideoCount}건(완료율 N%)'.
- **type**: Stat
- **label**: 누적 영상

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: overall.approvedVideoCount

- **description**: 누적 이미지/누적 영상 2개 카드 — 주 수치는 검수완료(approvedImageCount/approvedVideoCount), 보조로 전체(cumulativeImageCount/cumulativeVideoCount)와 완료율 텍스트를 병기한다(예: '검수완료 기준 · 전체 1,000장 (완료율 42%)'). 시각적 진행바 요소(role=progressbar, <progress>)는 렌더하지 않는다 — 완료율은 텍스트로만 표기.

**references_apis**:

- API-057

**references_features**:

_(empty)_

### 처리 현황 카드

- **role**: main
- **layout**: grid

**components**:

#### [1]

- **note**: 카드가 아니라 섹션 헤더 우측 텍스트로 표시('전체 N건') — 완료+처리중+대기+실패 4구간 합계.
- **type**: Stat
- **label**: 전체

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 스택형 진행바 1구간(성공색) + 하단 범례 항목(건수+비율%). 값=processing.approved.
- **type**: Stat
- **label**: 완료

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 스택형 진행바 1구간(정보색) + 범례. 값=processing.inProgress+processing.reviewPending 합산.
- **type**: Stat
- **label**: 처리중

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 스택형 진행바 1구간(위험색) + 범례. 값=processing.rejected.
- **type**: Stat
- **label**: 실패

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 스택형 진행바 1구간(중립색) + 범례. 값=processing.pending.
- **type**: Stat
- **label**: 대기

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **note**: 가로 스택형 진행바 — 완료/처리중/대기/실패 4구간을 각 값/전체 비율(%)만큼 폭으로 표시. 4구간 합이 0이면 바를 렌더하지 않는다.
- **type**: Progress
- **label**: 처리현황 스택 바

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ProcessingStackBar

- **description**: 카드 1개로 구성 — 헤더에 '처리현황' 제목과 우측 '전체 N건' 텍스트, 본문에 가로 스택형 진행바(완료/처리중/대기/실패 4구간, 폭=비율) + 하단 4항목 범례 리스트(각 항목 색상점+라벨+건수+비율%). 개별 카드 5개로 나눠 표시하지 않는다.

**references_apis**:

- API-057

**references_features**:

_(empty)_

### 일별 전체 작업량 막대차트

- **role**: main
- **layout**: dashboard

**components**:

#### [1]

- **note**: 막대 차트
- **type**: Chart
- **label**: 일별 전체 작업량 (최근 30일)

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: overall.dailyCounts

- **description**: 최근 30일 일별 작업량 막대차트. dailyCounts[].date/count 매핑. X축 눈금은 5일 간격으로 표시한다.

**references_apis**:

- API-057

**references_features**:

_(empty)_

### 이벤트 유형 분포

- **role**: main
- **layout**: dashboard

**components**:

#### [1]

- **note**: 파이 차트 + 범례. 검수완료 기준(approvedEventDistribution).
- **type**: Chart
- **label**: 이벤트 분포 파이차트

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 고정 6종이 아니라 서버가 내려주는 카테고리 전체(count 0 포함)를 categoryKey 오름차순으로 순회 렌더 — 개수 가변. 그룹핑은 이벤트유형 표시명 그룹 정책을 따른다.
- **type**: List
- **label**: 이벤트 분포 가로 리스트 (개수 가변)

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: overall.eventDistribution

- **description**: 검수완료 기준(approvedEventDistribution) 이벤트 유형 분포. 좌측 파이 차트 + 범례, 우측 라벨+건수 가로 리스트(개수 가변 — 서버가 수집 대상 카테고리를 count 0 포함해 전부 반환하므로 그대로 렌더). eventDistribution 을 code별 count 로 매핑.

**references_apis**:

- API-057

**references_features**:

_(empty)_

### 작업자별 현황 테이블

- **role**: main
- **layout**: list

**components**:

#### [1]

- **note**: 헤더 클릭으로 정렬
- **type**: Table
- **label**: 작업자별 현황

**columns**:

- 작업자
- 라벨
- 진행
- 검수
- 오토라벨
- 반려율

**options**:

_(empty)_

- **binds_to**: overall.workers

- **description**: 작업자별 현황 표 — 작업자·라벨·진행·검수·오토라벨·반려율 컬럼. 작업자는 작업자 이름, 라벨·진행·검수는 각 건수, 오토라벨은 오토라벨 비율(백분율), 반려율은 100에서 승인율을 뺀 값을 표시한다. 라벨·검수·오토라벨 헤더는 클릭으로 정렬하며 정렬 축은 그 컬럼이 표시하는 값과 같다(기본 정렬은 라벨 내림차순). workers[] 매핑. 로딩 시 '불러오는 중…', 빈 데이터 시 '작업자 통계가 없습니다'.

**references_apis**:

- API-057

**references_features**:

_(empty)_

### 에러 상태

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **type**: Alert
- **label**: 전체 통계를 불러올 수 없습니다
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

- **description**: overall 조회 실패 시 상단에 ErrorState 배너 노출('전체 통계를 불러올 수 없습니다').

**references_apis**:

- API-057

**references_features**:

_(empty)_

## brownfield

### status

preserved

### diff_summary

1차 전체 집계 통계

## surface_kind

web

## consumes_apis

- API-057
- API-058

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

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-021/main.html
- **label**: 전체 구축 현황 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **description**: 
- **source_hash**: 6cb8412e19d1ecb58d60d05f11aacd0ce638cbdf9ea19a4795e3930a3a38e555
- **generated_at**: 2026-08-16T12:44:00.316Z
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
