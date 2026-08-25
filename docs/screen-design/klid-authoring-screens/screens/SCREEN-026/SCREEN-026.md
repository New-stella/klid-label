---
logicraft_item: SCREEN-026
type: screen_spec
version: 29
last_updated_at: 2026-08-25T08:14:45.281Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-25T09:51:54.750Z
sync_session: 23
stale: false
status: NEW
prev_version: null
raw: ./_raw/SCREEN-026.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-037]]", "[[API-038]]", "[[API-039]]", "[[API-040]]", "[[API-185]]"]
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

REVIEWER가 이벤트유형별 라벨 프리셋을 생성·조회·수정·삭제하는 관리 화면. 접근: REVIEWER 전용.

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

- **note**: 이벤트명 (유형코드) 제목 / 라벨 칩 / 라벨 개수 / 수정일
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

- **note**: REVIEWER 전용. 빈 상태 안내 영역에 노출되는 인라인 생성 버튼 — 헤더의 '프리셋 추가' 버튼과 동일하게 편집 모달을 신규 모드로 연다.
- **type**: Button
- **label**: 새 프리셋 만들기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: 프리셋 목록을 반응형 카드 그리드(좁은 화면 1열, 중간 2열, 넓은 화면 3열)로 표시한다(클라이언트 측 페이지네이션, 9건/페이지). 각 카드: 이벤트명과 유형코드를 함께 보인 제목 + 라벨 칩 목록(앞 6개 + 초과시 '+N') + 라벨 개수 + 수정일. 이벤트명은 서버가 응답에 실어 주는 표시명을 그대로 쓴다 — 화면이 이벤트 목록으로 역해석하면 그 목록에 없는 유형이 코드로만 노출된다. ★라벨 칩의 이름·색·형태는 라벨 마스터를 실시간 join 한 값이다.

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

- **note**: 옵션은 등록된 전체 이벤트유형을 서버에서 조회해 채우고 이벤트명과 유형코드를 함께 보인다. ★필터 옵션 목록을 쓰지 않는다 — 그 목록은 제외 대분류를 감추므로 해당 유형의 프리셋을 만들거나 고칠 수 없게 된다. 같은 표시명을 가진 여러 유형코드가 한 그룹으로 접히는 것은 필터 축의 성질이며, 여기서는 유형을 개별로 고른다. 옵션 원천은 이벤트유형 관리 목록이다 — 표시명 맵을 주는 조회는 이름이 하나도 없는 유형을 응답에서 스스로 빼기 때문에 「등록된 전체」가 되지 못한다. 서버의 저장 검증이 등록 여부로 판정하므로 옵션도 같은 모집단이어야 한다.
- **type**: Select
- **label**: 이벤트유형

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 필수. 20자 이하(코드값 표준도메인). 이벤트 1건에 프리셋 1건이라 이미 프리셋이 있는 이벤트는 거부된다.

#### [2]

- **note**: 활성 라벨 마스터를 정렬순(sortNo, 동률은 labelId) 기준으로 나열해 보여준다. 제출은 labelId 배열만 전송한다. ★형태는 마스터가 소유하므로 읽기 전용 표시이며 사용자 토글 불가
- **type**: Custom
- **label**: 라벨 마스터 체크박스 멀티셀렉트 (형태는 읽기 전용)

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 최소 1개~최대 20개 선택. 0개이면 저장 버튼이 비활성화된다.
- **custom_name**: PresetCodeChip

#### [3]

- **type**: Button
- **label**: 만들기/저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-038

#### [4]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [5]

- **note**: 편집 대상에 마스터와 매칭되지 않는 레거시 코드(linked=false)가 있으면 노출. 오류로 상승시키지 않고 '미연결'로 표시하며 자동 생성·삭제하지 않는다. 저장 시 이 항목은 자동 제외된다
- **type**: Alert
- **label**: 더 이상 라벨 마스터에 없는 항목 경고 배너

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

- **description**: 신규('새 프리셋 만들기')/수정('프리셋 편집') 공용 모달. 필드: 이벤트유형 select(필수), 라벨 선택. 프리셋은 이름과 설명을 갖지 않는다 — 식별 축은 이벤트유형 하나다. ★라벨은 라벨 마스터 단일 진실원에서 고른다 — 활성 마스터를 불러와 체크박스 멀티셀렉트하고 제출 시 labelId 배열만 전송한다. 프리셋은 라벨명·형태를 스냅샷 저장하지 않고 labelId FK 로 마스터를 실시간 join 한다.

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

- **description**: 카드 삭제 버튼 클릭 시 오픈. 어느 이벤트의 프리셋인지 밝히고 되돌릴 수 없음을 안내한 뒤 삭제한다. 성공/실패 토스트.

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
- API-185

## implementation

### status

implemented

### modules

- MOD-031
- MOD-032
- MOD-033
- MOD-028
- MOD-008

### records

- IMPREC-003

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-25T08:11:33.257Z

### module_paths

_(empty)_

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
