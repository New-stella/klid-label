---
logicraft_item: SCREEN-012
type: screen_spec
version: 39
last_updated_at: 2026-08-16T09:56:01.951Z
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:27:41.228Z
sync_session: 15
stale: true
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-012.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-001]]", "[[API-002]]", "[[API-070]]", "[[API-071]]", "[[API-072]]", "[[API-073]]", "[[API-136]]", "[[API-137]]", "[[API-116]]", "[[API-187]]"]
  required_roles: ["[[ROLE-001]]", "[[ROLE-002]]"]
  realizes_use_cases: ["[[UC-029]]"]
---

# 작업 목록 화면

## route

/task

## title

작업 목록 화면

## device

desktop

## status

draft

## purpose

본인에게 할당된(WORKER) 또는 처리 완료된 전체(REVIEWER) 영상 작업 목록을 조회하고 라벨링·마킹으로 진입하는 화면. 정렬은 시간축 단일 기준(컬럼 헤더 정렬만)이며, '지금 처리할 것'은 KPI 카드(REVIEWER 5장: 전체/미배정/작업중/검수요청/반려)와 이벤트유형 필터로 표현한다. 필터 축은 두 개로 분리 — 배치상태(status, 이 화면은 COMPLETED 고정)와 워크플로상태(workStatus, KPI 카드·select 공유). 미등록 정렬 키는 이 화면(/v1/tasks/board*)에서 strict 400(검수목록의 lenient 200 폴백과 의도적으로 다르며 통일하지 않는다). 필터·집계는 BE 가 전체 데이터 기준으로 계산하며 현재 페이지 20건 안에서 재필터링하지 않는다 — 필터 축(검색어·상태·이벤트유형)은 REVIEWER·WORKER 공통으로 서버가 전체 데이터 기준 처리한다. KPI 집계만 역할별로 갈린다: REVIEWER 는 별도 요약 API로 서버 집계하고, WORKER 는 그런 API가 없어 현재 로드된 페이지 데이터로 클라이언트 집계한다. 접근: REVIEWER/WORKER.

## sections

### 헤더 (제목·새로고침)

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 작업 목록

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 화면 상단 제목 아래에 역할에 따라 다른 안내 문구가 표시된다.
- **type**: Text
- **label**: 역할별 부제 (검수자: 처리 완료된 영상만 표시 / 작업자: 본인 배정 작업 안내)

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

- **description**: 좌측 '작업 목록' 제목 + 역할별 부제 문구, 우측 새로고침 버튼. 새로고침은 목록을 다시 조회한다. 역할 구분은 부제 문구로 표현한다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 검색·필터 폼 (TaskFilters)

- **role**: filter
- **layout**: form

**components**:

#### [1]

- **note**: 검색어 입력란. 입력만으로는 조회되지 않고 '조회' 버튼을 눌러야 반영된다.
- **type**: Input
- **label**: 영상명/작업자명

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 역할별 분기 — 검수자는 서버 조회(API-137, 배치완료 전체 기준) 결과를 옵션으로 쓰고 서버 필터(eventTypeCd)로 위임한다. 작업자도 별도 서버 조회 결과를 옵션으로 쓰고 서버 필터(eventTypeCd)로 위임한다 — 본인 배정 전체 데이터 기준이며 현재 페이지 안에서 다시 거르지 않는다. ★표시명이 같은 유형코드들은 옵션 1건으로 접히며 값은 그룹 대표코드(그룹 내 최소 유형코드)다. 입력만으로는 조회되지 않고 '조회' 버튼을 눌러야 반영된다.
- **type**: Select
- **label**: 이벤트: 전체/동적 이벤트 유형

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 검수자에게는 미배정 옵션이 추가된다. 상태 필터도 이벤트유형과 마찬가지로 역할 무관 서버 필터로 위임되며 현재 페이지 안에서 다시 거르지 않는다. 입력만으로는 조회되지 않고 '조회' 버튼을 눌러야 반영된다.
- **type**: Select
- **label**: 상태: 전체/미배정(REVIEWER)/대기/진행중/검수대기/완료/반려

**columns**:

_(empty)_

**options**:

- 전체
- 미배정(REVIEWER)
- 대기
- 진행중
- 검수대기
- 완료
- 반려

#### [4]

- **note**: 검수자 한정 작업자 드롭다운. 선택값만 서버 필터(작업자 ID)로 위임된다. 입력만으로는 조회되지 않고 '조회' 버튼을 눌러야 반영된다.
- **type**: Select
- **label**: 작업자

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-001

#### [5]

- **note**: 위 4개 입력의 대기 중인 값을 실제 조회 조건으로 일괄 적용해 서버에서 다시 조회한다. 입력을 바꾸는 즉시 조회되지 않고, 이 버튼(또는 Enter 제출)을 눌러야 반영된다.
- **type**: Button
- **label**: 조회

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [6]

- **note**: 기본 필터값으로 되돌린다. 정렬도 진입 시 기본값(등록일 최신순)으로 함께 되돌리는데, 등록일 정렬은 컬럼 헤더가 없어 이 버튼이 유일한 복구 경로다.
- **type**: Button
- **label**: 초기화

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [7]

- **note**: 이벤트유형 옵션이 서버 상한으로 잘렸을 때만 표시된다.
- **type**: Text
- **label**: 이벤트유형이 많아 일부만 표시됩니다.

**columns**:

_(empty)_

**options**:

_(empty)_

**description**:

검수자는 검색어·이벤트유형·작업자를 서버(작업 목록 조회 API)로 위임한다 — 현재 페이지 결과를 다시 거르지 않는다. ★4개 입력 모두 대기 상태를 거치며, '조회' 버튼 클릭(또는 Enter 제출) 시에만 실제 조회 조건으로 일괄 적용되어 서버 재조회가 일어난다 — 입력마다 즉시 재조회되지 않는다. KPI 카드 클릭만 이 대기 상태를 건너뛰고 즉시 적용된다. 배치 상태 축은 화면에 필터 UI 가 없고 완료 상태로 고정되어 있다(부제 문구와 한 몸). 워크플로 상태는 목록 select 로도 선택 가능하나 KPI 카드가 같은 축을 대표한다. 작업자도 검색어·상태·이벤트유형 필터를 서버(작업 배정 목록 조회 API)로 위임한다 — 전체 데이터 기준이며 현재 페이지 안에서 다시 거르지 않는다. 이벤트유형 옵션 소스는 역할마다 별도 서버 엔드포인트를 쓴다 — 검수자·작업자 모두 서버 조회 결과를 옵션으로 받으며 현재 페이지에서 수집하지 않는다.

★이벤트유형은 '표시명 그룹' 축이다 — 같은 표시명의 유형코드들을 옵션 1건으로 접는다(대표코드=그룹 내 최소 유형코드). 필터 파라미터는 표시명이 아니라 이벤트 코드이며, 대표/비대표 어느 코드든 그룹 전체로 확장해 매칭한다(북마크 하위호환). 절단(옵션이 상한을 넘어 일부만 노출)은 접은 뒤 판정하며, 절단 시 필터 하단에 안내 문구가 노출된다. 미등록 코드도 자기 그룹으로 남긴다.

**references_apis**:

- API-001
- API-137

**references_features**:

_(empty)_

### KPI 카드 (REVIEWER 5종 서버집계 / WORKER 4종 클라이언트집계)

- **role**: hero
- **layout**: grid

**components**:

#### [1]

- **note**: 서버 집계(GET /v1/tasks/board/summary). workStatus 매핑: 전체=필터없음, 미배정=UNASSIGNED, 작업중=PENDING, 검수요청=REVIEW_PENDING, 반려=REJECTED. 카드 클릭=workStatus 필터 토글
- **type**: Stat
- **label**: REVIEWER 5카드 — 전체/미배정/작업중/검수요청/반려

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-136

#### [2]

- **note**: 본인 배정 목록에서 상태별 건수를 클라이언트에서 집계한다. 별도 API 없음
- **type**: Stat
- **label**: WORKER 4카드 — 전체/진행중/검수대기/반려

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: KPI 카드는 역할별로 분리된다 — REVIEWER 는 5장(전체/미배정/작업중/검수요청/반려, GET /v1/tasks/board/summary=API-136, 필터 결과 전체 기준 서버 집계). WORKER 는 4장(전체/진행중/검수대기/반려, 본인 배정 목록에서 클라이언트 집계, 별도 API 없음). ★REVIEWER '작업중' 카드는 workStatus=PENDING 을 집계하며 IN_PROGRESS 값은 BE 에 존재하지 않는다. ★'미배정' 카드는 workStatus=UNASSIGNED 로 전송된다(status=UNASSIGNED 가 아님). 카드 클릭은 workStatus 필터를 토글(같은 카드 재클릭 시 해제=전체)한다. 로딩/정상/실패 3상태 구분 렌더.

**references_apis**:

- API-136

**references_features**:

_(empty)_

### 일괄 배정 액션바 (REVIEWER, 1건+ 선택 시)

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: selectedVideoIds.size
- **type**: Badge
- **label**: N개 선택됨

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 선택을 모두 해제한다.
- **type**: Button
- **label**: 선택 해제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [3]

- **note**: 배정 모달을 일괄 배정 모드로 연다.
- **type**: Button
- **label**: N개 일괄 배정

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: REVIEWER 가 테이블에서 영상 1건 이상 선택하면 노출되는 액션바. 선택 개수 칩 + 선택 해제 + 'N개 일괄 배정' 버튼. 클릭 시 배정 모달이 일괄 배정 모드로 열린다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 작업 목록 테이블 + 페이지네이션

- **role**: main
- **layout**: list

**components**:

#### [1]

- **note**: 검수자 한정, 일부 선택 상태(indeterminate) 지원. ★이미 작업자가 배정된 행은 선택 체크박스가 비활성화된다 — 일괄 배정은 미배정 행에만 적용된다.
- **type**: Checkbox
- **label**: 현재 페이지 전체 선택

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 작업자 시각은 본인 배정 작업 목록을, 검수자 시각은 처리 완료 영상 + 배정 정보를 조회한다(API-072/API-073). ★검수자 시각에는 촬영일시 컬럼이 추가되며 헤더 클릭으로 서버 정렬 토글이 가능하다(작업자 배정 목록에는 값이 없어 컬럼 자체가 노출되지 않는다). 이벤트 컬럼 아래, 증강·해상도 변경으로 파생된 영상에 한해 파생 유형 배지(WINTER/NIGHT/RAIN 또는 해상도 프리셋)가 추가로 표시된다(원본 영상에는 표시되지 않는다).
- **type**: Table
- **label**: 선택/영상명/이벤트/촬영일시/상태/작업자/검수자/액션

**columns**:

- 선택
- 영상명
- 이벤트
- 촬영일시
- 상태
- 작업자
- 검수자
- 액션

**options**:

_(empty)_

- **triggers_api**: API-073

#### [3]

- **note**: 검수자, 미배정 행 — 배정 모달을 신규 배정 모드로 연다.
- **type**: Button
- **label**: 배정

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [4]

- **note**: 검수자, 기존 배정 행(완료 행 제외) — 배정 모달을 재배정 모드로 연다.
- **type**: Button
- **label**: 재배정

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [5]

- **note**: 작업자, 라벨링 대상 프레임이 있는 경우 — 라벨링 화면(/labeling/{srcSn})으로 이동한다.
- **type**: Button
- **label**: 작업

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [6]

- **note**: 작업자, 라벨링 대상 프레임이 아직 없는 경우 — 마킹 화면(/marking/{videoId})으로 이동한다. 다만 해당 영상의 비식별 처리가 아직 완료되지 않았거나 실패한 경우에는 버튼이 비활성화되고 '비식별 완료 후 마킹 가능'이라는 안내가 노출되어 마킹 진입 자체를 막는다.
- **type**: Button
- **label**: 마킹

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [7]

- **note**: 배정 이력 패널을 연다.
- **type**: Button
- **label**: 이력

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [8]

- **note**: 서버 페이징.
- **type**: Pagination
- **label**: 페이지네이션

**columns**:

_(empty)_

**options**:

_(empty)_

#### [9]

- **note**: 로딩 중 5행 스켈레톤 표시.
- **type**: Skeleton
- **label**: 로딩

**columns**:

_(empty)_

**options**:

_(empty)_

#### [10]

- **type**: Custom
- **label**: 배정된 작업이 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: EmptyState

- **description**: 작업자는 본인 배정 작업을, 검수자는 처리 완료 영상과 배정 정보를 함께 조회한다. 컬럼: (검수자)선택 체크박스, 영상명+영상 ID, 이벤트(파생 영상은 파생 유형 배지 추가 표시), (검수자)촬영일시(서버 정렬 가능), 상태, 작업자, 검수자, 액션. ★일괄 선택 체크박스는 이미 배정된 행에서는 비활성화된다 — 일괄 배정은 미배정 행 전용이다. 액션 분기 — 검수자: 배정/재배정(완료 행 제외)+이력, 작업자: 라벨링 대상 프레임 유무에 따라 작업(라벨링 화면 이동) 또는 마킹(마킹 화면 이동) + 이력. 서버 페이징.

**references_apis**:

- API-072
- API-073

**references_features**:

_(empty)_

### 작업 배정 모달 (AssignModal)

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **note**: 단건/일괄 미리보기. 일괄 모드는 대상 영상 중 최대 3건만 칩으로 미리 보여주고 나머지는 '외 N건'으로 요약하며, 선택된 모든 영상에 동일한 작업자가 배정됨을 안내 문구로 알린다.
- **type**: Custom
- **label**: 영상 정보 / 대상 영상 일괄

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: VideoInfoBox

#### [2]

- **note**: 필수. 작업자 후보 목록은 검수자 역할이면서 모달이 열려 있을 때만 조회된다.
- **type**: Select
- **label**: 작업자 *

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-002

#### [3]

- **note**: 검수자 역할은 기본값=로그인 사용자, 작업자 역할은 읽기 전용. 검수자 후보 목록은 검수자 역할이면서 모달이 열려 있을 때만 조회된다.
- **type**: Select
- **label**: 검수자

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-001

#### [4]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [5]

- **note**: 신규 배정과 재배정은 서로 다른 API 를 호출한다 — 신규 배정은 생성 요청(API-070), 재배정은 수정 요청(API-071). 이 컴포넌트의 triggers_api 는 대표값(API-070)만 표기하며, 실제 호출 API 는 모드에 따라 다르다.
- **type**: Button
- **label**: 저장 / N건 일괄 배정

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-070

#### [6]

- **note**: 재배정 모드에서 현재 배정된 작업자와 동일한 작업자를 다시 선택하면 저장 버튼이 비활성화되고 이 경고 문구가 노출된다.
- **type**: Text
- **label**: 현재 배정된 작업자와 동일합니다. 다른 작업자를 선택해주세요.

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: assign/reassign/bulk 3모드. 영상 정보(단건/일괄 미리보기) + 작업자 select(필수) + 검수자 select(검수자 역할, 기본값=로그인 사용자) 또는 읽기 전용(작업자 역할). 작업자·검수자 후보 목록은 검수자 역할이면서 모달이 열려 있을 때만 조회된다. 저장 시 신규 배정은 생성 요청(API-070), 재배정은 수정 요청(API-071)을 호출한다. 성공/실패 결과를 토스트로 안내한다.

**references_apis**:

- API-070
- API-071
- API-001
- API-002

**references_features**:

_(empty)_

### 배정 이력 Drawer (HistoryDrawer)

- **role**: modal
- **layout**: list

**components**:

#### [1]

- **type**: Custom
- **label**: 대상 작업 영상명

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: TargetTaskBox

#### [2]

- **note**: 배정/검수 워크플로 이벤트(ASSIGN/REASSIGN/SUBMIT/CANCEL_SUBMIT/APPROVE/REJECT) 6종과 개인정보 선언 변경·초기화 감사 이벤트(PRIVACY_META_UPDATE/PRIVACY_META_RESET) 2종, 총 8종을 조회한다(API-116). 이벤트별로 좌측 점 색상이 다르게 표시된다.
- **type**: Timeline
- **label**: 배정 이력

**columns**:

_(empty)_

**options**:

- ASSIGN
- REASSIGN
- SUBMIT
- CANCEL_SUBMIT
- APPROVE
- REJECT
- PRIVACY_META_UPDATE
- PRIVACY_META_RESET

- **triggers_api**: API-116

#### [3]

- **type**: Button
- **label**: 닫기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [4]

- **type**: Skeleton
- **label**: 로딩

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **type**: Custom
- **label**: 이력이 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: EmptyState

- **description**: 우측 슬라이드 Drawer(REVIEWER/WORKER 본인). 헤더(시계 아이콘+제목+닫기) + 대상 작업(영상명) + 타임라인(ASSIGN/REASSIGN/SUBMIT/CANCEL_SUBMIT/APPROVE/REJECT 워크플로 이벤트 6종 + PRIVACY_META_UPDATE/PRIVACY_META_RESET 개인정보 선언 변경·초기화 감사 이벤트 2종, 총 8종, 이벤트별 dot 색상 + 일시 + 설명 + 반려 사유). 감사 이벤트 2종은 배정·검수 진행 자체가 아니라 개인정보 선언값이 언제 바뀌었는지 기록하는 용도이며, 실제 판정값(Y/N)은 표시하지 않고 고정된 사유 문구만 보여준다. ESC/배경클릭/X 닫기, 포커스 트랩.

**references_apis**:

- API-116

**references_features**:

_(empty)_

### 오류 안내 배너 (목록 조회 실패 시)

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **note**: 검수자에게는 배정 기능이 새로고침 전까지 제한된다는 안내가 추가되고, 작업자에게는 일반 재조회 안내만 노출된다. 재시도 버튼은 새로고침과 동일한 동작을 수행한다.
- **type**: Alert
- **label**: 작업 목록을 불러올 수 없습니다 (재시도)

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 작업 목록 조회가 실패하면 화면 상단에 오류 안내 배너가 나타난다. 화면에 남아있는 목록은 최신 정보가 아님을 알리고, 검수자 시각에서는 배정 관련 액션이 최신 정보가 아니므로 새로고침 후 사용할 수 있다는 안내를 추가로 보여준다. 배너의 재시도 동작은 헤더의 새로고침과 같다.

**references_apis**:

_(empty)_

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

1차 프로젝트 단위 → 2차 영상 단위 작업 목록

### legacy_source

#### type

screen

#### identifier

SKKLID-UI-03-02-14

#### legacy_artifact_id

LEGACY-112

## surface_kind

web

## consumes_apis

- API-001
- API-002
- API-070
- API-071
- API-072
- API-073
- API-136
- API-137
- API-116
- API-187

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

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-012/main.html
- **label**: 작업 목록 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **description**: 
- **source_hash**: bfc81b8d69603af0dc03456ad6c296a3ba7953d96686033d761e04458ce5d0b4
- **generated_at**: 2026-08-16T09:56:01.950Z
- **generated_by**: sections-deterministic-generator

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-029

## covered_by_acceptances

_(empty)_
