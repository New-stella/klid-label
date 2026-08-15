---
logicraft_item: SCREEN-024
type: screen_spec
version: 20
last_updated_at: 2026-08-13T01:02:41.725Z
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-15T14:28:13.681Z
sync_session: 12
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-024.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-001]]", "[[API-004]]", "[[API-003]]"]
  required_roles: ["[[ROLE-001]]"]
---

# 사용자 관리 화면

## route

/manage/users

## title

사용자 관리 화면

## device

desktop

## status

draft

## purpose

REVIEWER가 사용자 목록을 조회·검색·필터링하고 역할을 수정하는 관리 화면. 계정 활성 여부는 외부 시스템(관제서버) 소유값이라 읽기 전용 배지로만 표시한다. 역할이 아직 배정되지 않은 자동등록 사용자는 '미배정'으로 표시되며, 역할을 선택해야 저장할 수 있다. 접근: REVIEWER.

## sections

### 페이지 헤더

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 사용자 관리

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Text
- **label**: 시스템 사용자 계정을 관리합니다.

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 제목 '사용자 관리' + 고정 부제. 부제는 정적 텍스트이며 전체 사용자 수 등 동적 수치는 표시하지 않는다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 검색·역할 필터

- **role**: filter
- **layout**: form

**components**:

#### [1]

- **type**: Input
- **label**: 검색

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 필수 아님. 길이 제한 없음. 이름 또는 이메일 부분일치로 서버에서 검색한다.
- **placeholder**: 이름 / 이메일을 입력하세요.
- **triggers_api**: API-001

#### [2]

- **note**: 검색 확정과 함께 서버 파라미터로 전달되는 서버 사이드 필터 — 클라이언트에서 현재 페이지만 거르지 않는다.
- **type**: Select
- **label**: 역할

**columns**:

_(empty)_

**options**:

- 전체 역할
- 검수자
- 작업자
- 포털

- **triggers_api**: API-001

#### [3]

- **note**: 검색어 또는 역할 필터 중 하나라도 활성일 때만 눌림 가능.
- **type**: Button
- **label**: 필터 초기화

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [4]

- **note**: 검색어 확정 트리거. 입력 즉시 조회하지 않고 Enter 또는 이 버튼으로 확정한다.
- **type**: Button
- **label**: 검색

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 검색 입력(이름/이메일 부분일치)과 역할 select(전체/검수자/작업자/포털) 모두 서버 사이드 필터다. 입력값은 Enter 또는 검색 실행으로 확정되어야 조회에 반영되며(셀렉트를 바꾸는 것만으로 즉시 재조회되지 않음), 확정 시 1페이지로 초기화된다. 목록 필터는 검색어·역할 두 축뿐이다.

**references_apis**:

- API-001

**references_features**:

_(empty)_

### 사용자 목록 테이블

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Table
- **label**: 사용자 목록

**columns**:

- 이름
- 이메일
- 역할
- 상태
- 등록일
- 관리

**options**:

_(empty)_

- **triggers_api**: API-001

#### [2]

- **note**: 행별 수정 모달 오픈. 이 화면에 존재하는 유일한 행 조작이다.
- **type**: Button
- **label**: 수정

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [3]

- **type**: Custom
- **label**: 페이지네이션

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Pagination

#### [4]

- **type**: Alert
- **label**: 사용자 목록을 불러올 수 없습니다
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: DataTable. 컬럼: 이름(아바타 이니셜+이름)/이메일(없으면 loginId)/역할(컬러 배지, 역할이 없으면 '미배정' 배지 — 관제 인계 키에 역할 클레임이 없는 자동등록 사용자)/상태(활성·비활성 배지, 읽기 전용)/등록일/관리(수정 버튼). GET /v1/users 페이징 결과를 표시한다. 검색·역할 필터는 서버 사이드로 처리된다. 페이지 변경 시 URL page 갱신 → 재조회. 로드 실패 시 상단 ErrorState 배너. 빈 결과는 '조건에 맞는 사용자가 없습니다'.

**references_apis**:

- API-001

**references_features**:

_(empty)_

### 사용자 정보 수정 모달

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **type**: Dialog
- **label**: 사용자 정보 수정

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Select
- **label**: 역할

**columns**:

_(empty)_

**options**:

- 검수자
- 작업자
- 포털

- **validation**: 저장하려면 반드시 선택해야 한다(초기값 없는 미배정 사용자는 선택 전까지 저장 불가). 허용값: 검수자(REVIEWER)/작업자(WORKER)/포털(PORTAL_USER) — 서버가 화이트리스트로 재검증하며 그 외 값은 400으로 거부한다.

#### [3]

- **note**: 편집 불가. '계정 활성 여부는 이 화면에서 변경하지 않습니다' 안내 문구와 함께 표시된다.
- **type**: Custom
- **label**: 상태 (읽기 전용 배지)

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: user.active
- **custom_name**: StatusBadge

#### [4]

- **note**: 역할을 선택하지 않았거나 원래 값과 같으면 비활성화된다.
- **type**: Button
- **label**: 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-004

#### [5]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: Modal. 행의 '수정' 클릭 시 오픈(테이블 행 데이터 기반, 별도 단건 조회 없음). 역할 select(검수자/작업자/포털)만 수정 가능 — 저장하려면 반드시 값을 선택해야 하며, 대상 사용자가 '미배정'(역할 없음)이면 선택 전까지 저장 버튼이 비활성화되고 '아직 역할이 배정되지 않은 사용자입니다' 안내가 표시된다. 상태(활성/비활성)는 읽기 전용 배지로만 표시되며 이 화면에서 변경하지 않는다(계정 활성 여부는 외부 시스템 소유). 저장 시 역할만 PATCH /v1/users/{userNo} 로 전송된다. 역할을 바꾸지 않았으면 모달만 닫음. 성공 시 토스트 + 목록 캐시 무효화.

**references_apis**:

- API-004

**references_features**:

_(empty)_

## brownfield

### status

preserved

### diff_summary

1차 사용자·권한 관리

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-03-05-01

#### legacy_artifact_id

LEGACY-125

## surface_kind

web

## consumes_apis

- API-001
- API-004
- API-003

## implementation

### status

implemented

### modules

- MOD-030
- MOD-024
- MOD-025

### records

- IMPREC-002

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-11T23:08:20.363Z

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-024/main.html
- **label**: 사용자 관리 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **description**: 
- **source_hash**: c09b570380e6f338637a55257de71b4ce089d22f774ace46406a68eb1243a096
- **generated_at**: 2026-08-13T01:02:41.724Z
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
