---
logicraft_item: SCREEN-010
type: screen_spec
version: 37
last_updated_at: 2026-08-16T07:22:17.530Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T06:23:42.108Z
sync_session: 28
stale: true
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-010.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-197]]", "[[API-182]]", "[[API-195]]", "[[API-034]]", "[[API-035]]", "[[API-036]]"]
  required_roles: ["[[ROLE-001]]", "[[ROLE-002]]"]
  realizes_use_cases: ["[[UC-008]]"]
---

# 로드 버전 선택

## route



## title

로드 버전 선택

## device

desktop

## status

draft

## purpose

라벨링 캔버스 화면(SCREEN-005)에 들어올 때 어느 버전에서 편집을 시작할지 고르는 모달의 상세 사양이다. 별도의 독립 페이지와 라우트를 두지 않는다. 검수 승인으로 만들어진 산출 버전이 둘 이상일 때만 나타나며, 하나도 없거나 하나뿐이면 고를 것이 없어 띄우지 않는다. 여기서 말하는 버전은 관제가 픽업하는 산출 폴더의 번호와 같은 것이며 영상 단위로 매겨진다. 구성은 산출 버전 목록과 고른 버전의 변경 내용 미리보기와 불러오기 확정 셋이다. 불러오기는 영상 전체를 화면에 올리기만 하고 서버에는 아무것도 쓰지 않으며 저장을 눌러야 확정된다. 저장 단위 변경 이력은 이 화면에서 다루지 않는다 — 되돌릴 수 있는 단위는 검수로 확정된 버전뿐이다. 모달은 진입 시점의 영상 식별자를 화면 간 전달로 직접 받으므로 URL 파라미터 검증이 필요하지 않다. 접근: REVIEWER/WORKER.

## sections

### 산출 버전 목록

- **role**: main
- **layout**: list

**components**:

#### [1]

- **note**: 각 행은 버전 번호와 승인 일시와 승인자를 한 줄로 보여준다. 한 번에 하나만 고른다.
- **type**: List
- **label**: 산출 버전 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-197

#### [2]

- **note**: 가장 마지막 버전에 단다.
- **type**: Badge
- **label**: 최신

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 목록을 불러오는 동안 자리를 지킨다.
- **type**: Custom
- **label**: 버전 목록 로딩

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: VersionListSkeleton

#### [4]

- **note**: 버전이 둘 미만이면 모달 자체가 뜨지 않으므로 이 안내는 조회에 실패했을 때만 나타난다.
- **type**: Alert
- **label**: 고를 버전이 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 영상 단위 산출 버전을 최신순으로 나열한다. 목록에 들어오면 가장 마지막 버전이 기본으로 골라져 있다 — 그대로 확정하면 이미 그 버전과 같은 작업본에는 아무 일도 일어나지 않아 안전한 기본값이다. 한 번에 하나만 고르며, 두 버전을 골라 서로 비교하는 방식은 두지 않는다. 여기서 고르는 것은 편집을 시작할 상태이지 비교 대상이 아니기 때문이다. 승인 버전은 검수 승인으로만 늘어난다 — 라벨을 저장하는 것만으로는 생기지 않으므로 이 목록에는 검수 승인 이력만 담긴다. 그 버전에서 내용이 변경되지 않은 프레임은 스냅샷이 새로 생기지 않으므로, 목록의 프레임 수는 그 버전에서 실제로 변경된 프레임만 센다.

**references_apis**:

- API-197

**references_features**:

_(empty)_

### 변경 내용 미리보기

- **role**: main
- **layout**: detail

**components**:

#### [1]

- **note**: 종류별로 색을 나눈다 — 추가와 수정과 삭제. 각 행은 종류와 프레임 식별자와 객체 식별자와 라벨 이름을 함께 보여준다.
- **type**: Custom
- **label**: 라벨 변경 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DiffViewer
- **triggers_api**: API-182

#### [2]

- **note**: 변경이 0건일 때 빈 목록 대신 노출한다.
- **type**: Alert
- **label**: 변경된 라벨이 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 고른 버전과 현재 작업본의 라벨 차이를 보여줘 무엇이 되돌아가는지 알고 고르게 한다. 비교 대상이 무엇인지 화면에 표시한다. 변경이 0건이면 빈 목록 대신 변경 없음을 알리고, 조회에 실패하면 실패로 표시해 변경 없음과 구분한다 — 렌더 순서를 미선택 다음 로딩 다음 오류 다음 결과로 두어 조회 실패가 변경 없음으로 잘못 보이지 않게 한다. 비교하는 축은 라벨 식별자와 도형 종류와 라벨 이름과 라벨 마스터 연결과 좌표와 추적 식별자다. 추적 식별자를 축에 넣는 이유는 트랙을 합치는 것만으로도 산출물이 다시 만들어지고 관제에 다시 알리는데 그것이 변경 없음으로 보이면 안 되기 때문이다. 자동 인식이 붙인 신뢰도 같은 값은 축에서 제외한다 — 사람이 고칠 수 있는 경로가 없고 소수점 흔들림이 잡음이 되기 때문이다.

**references_apis**:

- API-182

**references_features**:

_(empty)_

### 불러오기 확정

- **role**: main
- **layout**: detail

**components**:

#### [1]

- **note**: 고른 버전을 영상 전체 범위로 화면에 올린다. 서버에는 아무것도 쓰지 않는다.
- **type**: Button
- **label**: 이 버전으로 시작

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-195

#### [2]

- **note**: 어느 버전도 불러오지 않고 지금 작업본 그대로 편집을 시작한다.
- **type**: Button
- **label**: 현재 작업본으로 시작

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [3]

- **note**: 화면에 저장하지 않은 편집이 있을 때만 노출한다. 불러오면 그 편집이 사라진다는 것을 알리고 확인을 받는다.
- **type**: Dialog
- **label**: 저장하지 않은 편집이 사라집니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 고른 버전을 영상 전체 범위로 화면에 올린다. 서버에는 아무것도 쓰지 않는다 — 저장을 눌러야 확정되고 저장하지 않고 화면을 떠나면 작업본이 그대로 남는다. 불러오기 전에 화면에 저장하지 않은 편집이 있으면 그것이 사라진다는 것을 먼저 알리고 확인을 받는다. 불러온 뒤에는 저장이 프레임 하나가 아니라 영상 전체 단위다 — 일부 프레임만 저장하면 한 영상 안에 서로 다른 시점의 프레임이 섞인 채로 확정되어 그대로 외부로 나가기 때문이다. 어느 버전도 고르지 않고 지금 작업본 그대로 시작하는 길을 함께 둔다. 권한은 검수자 또는 본인에게 배정된 작업자다.

**references_apis**:

- API-195

**references_features**:

_(empty)_

## brownfield

### status

modified

### decided_by

ADR-009

### change_kind

- redesign

### diff_summary

1차 Gitea → 2차 DB 스냅샷 이력(FEAT-002)

### legacy_source

#### type

module

#### legacy_artifact_id

LEGACY-004

## surface_kind

web

## consumes_apis

- API-197
- API-182
- API-195
- API-034
- API-035
- API-036

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

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-010/main.html
- **label**: 로드 버전 선택 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **description**: 
- **source_hash**: 58b8156822868144999cd7fb34412f41d72270fc279d9dfd35ba55b0abd99219
- **generated_at**: 2026-08-13T00:54:55.513Z
- **generated_by**: sections-deterministic-generator

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-008

## covered_by_acceptances

_(empty)_
