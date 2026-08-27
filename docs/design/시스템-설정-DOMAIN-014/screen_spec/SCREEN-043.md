---
logicraft_item: SCREEN-043
type: screen_spec
version: 3
domain: DOMAIN-014
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-27T20:54:27.748Z
status: NEW
prev_version: null
content_hash: 86215cab04db3e88115c81e21b302f2de75baf63d66c6bf0b1a15b497f9f90e4
stale: true
raw: ./_raw/SCREEN-043.json
links:
  based_on: ["[[ADR-046]]"]
  belongs_to_domain: ["[[DOMAIN-014]]"]
  requires: ["[[ROLE-001]]"]
  designs_backward: ["[[SD-035]]"]
  navigates_to_backward: ["[[NAV-001]]"]
  references_backward: ["[[ADR-046]]"]
---

# 위험 작업 화면

## route

/admin/maintenance

## title

위험 작업 화면

## device

desktop

## status

draft

## purpose

검수자가 되돌릴 수 없는 파괴적 운영 작업을 실행하는 화면. 시스템 초기화·배치 큐 초기화·캐시 삭제를 다룬다. 관리자 페이지에 속해 관리자 패스워드 확인을 거쳐야 도달한다. 전용 화면으로 분리한 것은 다른 일을 하다가 실수로 누르는 동선을 없애기 위해서다. 버튼을 누르는 것만으로는 실행되지 않는다 — 수행할 작업명과 그 작업이 무엇을 지우는지에 대한 설명, 되돌릴 수 없다는 경고를 함께 보여주는 확인 절차를 먼저 거치며, 확인하지 않고 벗어나면 아무것도 실행되지 않는다. 실행에는 검수자 권한에 더해 유효한 관리자 단기 유효창이 가산되며, 유효 기간이 끝나면 관리자 패스워드 재확인을 요구한다. 접근: REVIEWER.

## sections

### 위험 구역 안내

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 위험 작업

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Text
- **label**: 되돌릴 수 없는 운영 작업만 모아 둔 화면입니다.

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 화면에 들어선 순간 보이는 자리에 둔다. 개별 작업의 확인 절차를 대신하지 않으며 그 절차는 그대로 남는다.
- **type**: Alert
- **label**: 아래 작업은 되돌릴 수 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

- **description**: 제목과 고정 부제, 그리고 되돌릴 수 없다는 경고를 화면 첫 자리에 둔다. 이 화면은 관리자 페이지에 속하며 관리자 패스워드 확인을 거쳐야 도달한다. 여기 모인 작업을 다른 설정 항목과 같은 화면에 두지 않는 이유는, 되돌릴 수 없는 작업이라 다른 일을 하다가 실수로 누르는 동선을 없애기 위해서다. 이 안내는 화면 전체에 대한 것이며, 작업마다 요구되는 확인 절차를 대신하지 않는다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 위험 작업

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: 이 영역의 구성과 동작은 컴포넌트 카탈로그가 소유한다. 화면은 그 자리와 진입 조건만 정한다.
- **type**: Custom
- **label**: 위험 작업 영역

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DangerActions

#### [2]

- **note**: 확인 절차를 거친 뒤에만 실행된다.
- **type**: Button
- **label**: 시스템 초기화

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [3]

- **note**: 확인 절차를 거친 뒤에만 실행된다.
- **type**: Button
- **label**: 배치 큐 초기화

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [4]

- **note**: 확인 절차를 거친 뒤에만 실행된다.
- **type**: Button
- **label**: 캐시 삭제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [5]

- **note**: 관리자 단기 유효창의 남은 시간. 유효창이 열려 있지 않으면 확인이 필요함을 표시한다.
- **type**: Custom
- **label**: 남은 유효 시간

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: AdminSessionCountdown

#### [6]

- **note**: 실행이 유효창 만료로 거부됐을 때 표시한다. 거부를 조용히 삼키지 않고 만료 사실을 알린다.
- **type**: Alert
- **label**: 관리자 확인 시간이 만료되었습니다. 다시 확인해 주세요
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 시스템 초기화·배치 큐 초기화·캐시 삭제를 각각 실행 버튼으로 둔다. 어느 버튼도 누르는 것만으로 실행되지 않으며 반드시 확인 절차를 거친다. 실행에는 검수자 권한에 더해 유효한 관리자 단기 유효창이 필요하다 — 유효창은 검수자 권한을 대체하지 않고 그 위에 가산되며 역할을 승격시키지 않는다. 유효 여부 판정은 서버가 소유하며 화면이 스스로 아직 유효하다고 정하지 않는다. 화면에는 남은 유효 시간을 표시하고, 유효창이 없거나 기간이 끝난 뒤 실행을 시도하면 만료 사실을 안내한 뒤 관리자 패스워드 재확인을 요구한다. 이 영역의 구성과 동작은 컴포넌트 카탈로그의 DangerActions 가 소유하므로 여기에 다시 정의하지 않는다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 실행 확인

- **role**: modal
- **layout**: stack

**components**:

#### [1]

- **type**: Dialog
- **label**: 작업 실행 확인

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 어느 작업을 실행하려는지 그대로 보여준다.
- **type**: Text
- **label**: 수행할 작업명

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 작업마다 다른 문구를 쓴다. 지워지는 대상과 영향받지 않는 대상을 함께 적는다.
- **type**: Text
- **label**: 이 작업이 무엇을 지우는지에 대한 설명

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **type**: Alert
- **label**: 이 작업은 되돌릴 수 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [5]

- **note**: 이 버튼을 누른 뒤에만 실행된다.
- **type**: Button
- **label**: 실행

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [6]

- **note**: 누르면 아무 일도 일어나지 않는다. 확인하지 않고 닫는 것도 같다.
- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [7]

- **note**: 유효창이 없거나 기간이 끝난 상태에서 실행을 시도하면 열린다. 입력값은 화면에 다시 표시하지 않으며 확인에 성공하면 실행을 이어서 시도한다.
- **type**: Dialog
- **label**: 관리자 패스워드 재확인

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 되돌릴 수 없는 작업은 이 절차를 거쳐야 실행된다. 확인 창은 수행할 작업명, 그 작업이 무엇을 지우는지에 대한 개별 설명, 되돌릴 수 없다는 경고를 함께 보여준다. 설명은 작업마다 다른 문구이며 지워지는 대상과 영향받지 않는 대상을 함께 적는다. 확인을 누른 뒤에만 실행되고, 취소하거나 확인하지 않고 닫으면 아무 일도 일어나지 않는다. 실행 시점에 유효한 관리자 단기 유효창이 없으면 서버가 거부하며, 화면은 만료 사실을 안내하고 관리자 패스워드 재확인을 요구한다 — 재확인에 성공하면 실행을 이어서 시도한다. 재확인 입력값은 화면에 다시 표시하지 않는다. 이 절차는 화면 구성에서 뺄 수 없다 — 이 단계가 없으면 되돌릴 수 없는 작업이 한 번의 클릭으로 실행되는 사양이 된다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

## brownfield

### status

new

### decided_by

ADR-046

### diff_summary

2차 신규 — 되돌릴 수 없는 운영 작업을 관리자 페이지의 전용 화면으로 분리한다.

## surface_kind

web

## consumes_apis

_(empty)_

## implementation

### status

planned

### modules

_(empty)_

### records

_(empty)_

### progress

0

### subtasks

_(empty)_

### module_paths

_(empty)_

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-043/main.html
- **label**: 위험 작업 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **source_hash**: a51f3dd14a69d234842edaaba92891c502733c69edda5a48c28e21db567324ca
- **generated_at**: 2026-08-27T09:39:42.279Z
- **generated_by**: generate-wireframes.py

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

_(empty)_

## covered_by_acceptances

_(empty)_
