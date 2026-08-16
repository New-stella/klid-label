---
logicraft_item: SCREEN-026
type: screen_spec
version: 26
last_updated_at: 2026-08-16T12:44:01.133Z
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:18:44.987Z
sync_session: 14
stale: true
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-026.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-037]]", "[[API-038]]", "[[API-039]]", "[[API-040]]", "[[API-041]]", "[[API-117]]"]
  required_roles: ["[[ROLE-001]]"]
  realizes_use_cases: ["[[UC-032]]"]
---

# 프리셋 관리 화면

## route

/manage/presets

## title

프리셋 관리 화면

## device

desktop

## status

draft

## purpose

REVIEWER가 라벨링 프리셋을 생성·조회·수정·삭제·복제하는 관리 화면. 접근: REVIEWER 전용.

## sections

### 페이지 헤더 · 프리셋 추가

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 프리셋 관리

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 정적 문구 대신 전체 프리셋 개수를 포함한 동적 문구('라벨 코드 프리셋 관리 — 전체 N개')로 표시되는 변형도 있다.
- **type**: Text
- **label**: 프리셋을 관리합니다.

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 클릭 시 편집 모달을 신규 모드로 연다.
- **type**: Button
- **label**: 프리셋 추가

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: 제목 '프리셋 관리' + 부제. 부제는 정적 텍스트('프리셋을 관리합니다.')로 표시되는 변형과, 전체 프리셋 개수를 포함한 동적 문구('라벨 코드 프리셋 관리 — 전체 N개')로 표시되는 변형이 있다. 우측에 '프리셋 추가' 버튼이 항상 노출된다(화면 자체가 REVIEWER 전용 경로에만 존재).

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 프리셋 카드 그리드

- **role**: main
- **layout**: grid

**components**:

#### [1]

- **note**: 이름/이벤트 배지/설명/라벨 코드 칩/개수/수정일
- **type**: Card
- **label**: 프리셋 카드

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 카드에는 앞에서 6개까지만 칩으로 보이고 나머지는 '+N' 배지로 접힌다(편집 모달의 라벨 선택 개수 상한 20과는 별개의 표시용 축소다).
- **type**: List
- **label**: 라벨 코드 칩 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: preset.labelCodes

#### [3]

- **note**: REVIEWER 전용 → 편집 모달
- **type**: Button
- **label**: 수정

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [4]

- **note**: REVIEWER 전용 → 삭제 확인 다이얼로그
- **type**: Button
- **label**: 삭제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [5]

- **type**: Custom
- **label**: 로딩 스켈레톤 ×6
- **state**: loading

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Skeleton

#### [6]

- **type**: Custom
- **label**: 등록된 프리셋이 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: EmptyState

#### [7]

- **type**: Custom
- **label**: 프리셋 목록을 불러올 수 없습니다
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ErrorState

#### [8]

- **note**: REVIEWER 전용. 클릭 시 서버에 복제를 요청한다 — 이름 끝에 ' (복사본)' 접미사가 붙은 새 프리셋이 새 ID로 발급되며, 매핑 이벤트 타입은 상속되지 않고 미매핑으로 초기화된다.
- **type**: Button
- **label**: 복제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost
- **triggers_api**: API-041

#### [9]

- **note**: REVIEWER 전용. 빈 상태 안내 영역에 노출되는 인라인 생성 버튼 — 헤더의 '프리셋 추가' 버튼과 동일하게 편집 모달을 신규 모드로 연다.
- **type**: Button
- **label**: 새 프리셋 만들기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: 프리셋 목록을 반응형 카드 그리드(좁은 화면 1열, 중간 2열, 넓은 화면 3열)로 표시한다(클라이언트 측 페이지네이션, 9건/페이지). 각 카드: 프리셋명 + 매핑 이벤트 배지(없으면 '미매핑') + 설명(2줄까지, 없으면 '설명이 없습니다.') + 라벨 코드 칩 목록(앞 6개 + 초과시 '+N') + 라벨 개수 + 수정일. ★라벨 칩의 이름·색·형태는 프리셋에 저장된 값이 아니라 라벨 마스터를 조회 시점에 실시간 join 해 가져온다 — 마스터를 고치면 기존 프리셋 표시도 즉시 바뀌고, 마스터에 매칭되지 않는 레거시 코드는 오류 없이 '미연결'로 표시된다(칩에 '· 미연결' 접미사). REVIEWER일 때 카드 우상단에 수정·삭제·복제 버튼. 복제 버튼은 클릭 즉시 서버에 복제를 요청해 이름 끝에 ' (복사본)' 접미사가 붙은 새 프리셋을 생성한다 — 매핑 이벤트 타입은 상속되지 않고 미매핑으로 초기화된다. 로딩 시 Skeleton(6개 변형·4개 변형), 0건이면 빈 상태 안내(REVIEWER면 안내 영역에 '새 프리셋 만들기' 버튼도 함께 노출 — 헤더의 '프리셋 추가' 버튼과 동일하게 편집 모달을 신규 모드로 연다), 에러 시 ErrorState 배너.

**references_apis**:

- API-037
- API-041

**references_features**:

_(empty)_

### 페이지네이션

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **type**: Custom
- **label**: 페이지네이션

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: safePage
- **custom_name**: Pagination

- **description**: 프리셋이 1페이지를 초과할 때만 표시. 클라이언트 측 페이지네이션(9건/페이지) — 서버 페이징이 아니라 전체 프리셋 목록을 받은 뒤 화면에서 잘라 표시한다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 프리셋 편집 모달

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **note**: 필수
- **type**: Input
- **label**: 프리셋 이름

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 필수. 1~64자.
- **placeholder**: 예: 교통사고 표준 프리셋

#### [2]

- **type**: Textarea
- **label**: 설명

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 선택. 0~500자.
- **placeholder**: 프리셋에 대한 설명을 입력하세요.

#### [3]

- **note**: 나머지 옵션은 이벤트유형 마스터를 서버에서 동적 조회해 채운다(표시명 기준, 개수는 고정 6종이 아니다). 같은 표시명을 가진 여러 유형코드는 옵션 1건으로 접힌다.
- **type**: Select
- **label**: 매핑 이벤트 타입

**columns**:

_(empty)_

**options**:

- 선택 안 함 (미매핑)

- **validation**: 선택. 문자 형식 제약 없이 최대 32자(초과 시 거부). 동일 이벤트는 1개 프리셋에만 매핑된다(중복 시 거부).

#### [4]

- **note**: 활성 라벨 마스터를 정렬순(sortNo, 동률은 labelId) 기준으로 나열해 보여준다. 제출은 labelId 배열만 전송한다. ★형태는 마스터가 소유하므로 읽기 전용 표시이며 사용자 토글 불가
- **type**: Custom
- **label**: 라벨 마스터 체크박스 멀티셀렉트 (형태는 읽기 전용)

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 최소 1개~최대 20개 선택. 0개이면 저장 버튼이 비활성화된다.
- **custom_name**: PresetCodeChip

#### [5]

- **type**: Button
- **label**: 만들기/저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-038

#### [6]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [7]

- **note**: 편집 대상에 마스터와 매칭되지 않는 레거시 코드(linked=false)가 있으면 노출. 오류로 상승시키지 않고 '미연결'로 표시하며 자동 생성·삭제하지 않는다. 저장 시 이 항목은 자동 제외된다
- **type**: Alert
- **label**: 더 이상 라벨 마스터에 없는 항목 경고 배너

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

- **description**: 신규('새 프리셋 만들기')/수정('프리셋 편집') 공용 모달. 필드: 프리셋 이름(필수), 설명(textarea), 매핑 이벤트 타입 select, 라벨 선택. ★라벨은 라벨 마스터 단일 진실원에서 고른다 — 활성 마스터를 불러와 체크박스 멀티셀렉트하고 제출 시 labelId 배열만 전송한다. 프리셋은 라벨명·형태를 스냅샷 저장하지 않고 labelId FK 로 마스터를 실시간 join 하므로 마스터 변경이 신규·기존 프리셋 모두에 즉시 반영된다. 형태는 마스터가 소유해 읽기 전용으로만 표시되며 프리셋별 토글이 불가능하다. 매핑 이벤트 옵션은 서버 조회로 value=이벤트유형코드(그룹 대표코드)·표시=이벤트명이며 빈 값=미매핑(1:1 매핑). 편집 대상에 미연결(linked=false) 코드가 있으면 경고 배너로 재선택을 유도한다. 저장 시 create/update, 409 CONFLICT 면 서버 메시지(이벤트 중복/이름 중복) 토스트.

**references_apis**:

- API-038
- API-039

**references_features**:

_(empty)_

### 프리셋 삭제 확인

- **role**: modal
- **layout**: detail

**components**:

#### [1]

- **type**: Dialog
- **label**: 프리셋 삭제

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Button
- **label**: 삭제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive
- **triggers_api**: API-040

#### [3]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 카드 삭제 버튼 클릭 시 오픈. '{이름} 프리셋을 삭제합니다. 이 작업은 되돌릴 수 없습니다.' 안내 후 삭제. 성공/실패 토스트.

**references_apis**:

- API-040

**references_features**:

_(empty)_

## brownfield

### status

modified

### decided_by

ADR-034

### diff_summary

1차 라벨링 프리셋 관리 — ADR-034(프리셋-마스터 단일화)로 저장모델·요청 계약이 반전(코드 문자열 스냅샷 → 마스터 PK(labelId) 실시간 join)돼 preserved 로 남을 수 없다.

### legacy_source

#### type

module

#### legacy_artifact_id

LEGACY-004

## surface_kind

web

## consumes_apis

- API-037
- API-038
- API-039
- API-040
- API-041
- API-117

## implementation

### status

implemented

### modules

- MOD-031
- MOD-032
- MOD-033
- MOD-028

### records

- IMPREC-003

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-11T23:08:20.446Z

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-026/main.html
- **label**: 프리셋 관리 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **description**: 
- **source_hash**: fe4846825d313dde4dbaef0c5437a1d7c70590dfb5eac8adaff6694919232dc8
- **generated_at**: 2026-08-16T12:44:01.132Z
- **generated_by**: generate-wireframes.py

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-032

## covered_by_acceptances

_(empty)_
