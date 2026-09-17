---
logicraft_item: SCREEN-026
type: screen_spec
version: 43
last_updated_at: 2026-09-16T05:27:56.112Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-17T01:18:31.309Z
sync_session: 43
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-026.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-037]]", "[[API-038]]", "[[API-039]]", "[[API-040]]", "[[API-185]]"]
  required_roles: ["[[ROLE-001]]"]
  realizes_use_cases: ["[[UC-032]]"]
  acceptance: ["[[AC-1128]]"]
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

- **note**: 담긴 라벨이 모두 AI 검출 클래스에 매핑되어 있지 않아 이 프리셋이 오토라벨링에 적용되지 않는 상태에만 카드 제목 옆에 노출된다. 배지가 없으면 적용되는 프리셋이다. 실효 여부는 서버가 프리셋마다 내려주는 값을 그대로 쓰고 화면에서 다시 판정하지 않는다. 배지에 붙는 보조 안내 문구는 「이 프리셋은 오토라벨링에 적용되지 않습니다」로 고정한다.
- **type**: Badge
- **label**: 오토라벨 미적용

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [4]

- **note**: REVIEWER 전용 → 편집 모달
- **type**: Button
- **label**: 수정

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [5]

- **note**: REVIEWER 전용 → 삭제 확인 다이얼로그
- **type**: Button
- **label**: 삭제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [6]

- **type**: Custom
- **label**: 로딩 스켈레톤 ×6
- **state**: loading

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Skeleton

#### [7]

- **type**: Custom
- **label**: 등록된 프리셋이 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: EmptyState

#### [8]

- **type**: Custom
- **label**: 프리셋 목록을 불러올 수 없습니다
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ErrorState

#### [9]

- **note**: REVIEWER 전용. 빈 상태 안내 영역에 노출되는 인라인 생성 버튼 — 헤더의 '프리셋 추가' 버튼과 동일하게 편집 모달을 신규 모드로 연다.
- **type**: Button
- **label**: 새 프리셋 만들기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [10]

- **note**: 라벨을 하나도 담지 않은 프리셋에만 카드 제목 옆에 노출된다. 판정은 목록 응답의 라벨 목록이 비어 있다는 사실 그대로이며 화면이 따로 해석하지 않는다. 배지에 붙는 보조 안내 문구는 「이 이벤트유형은 오토라벨링을 하지 않도록 지정되어 있습니다」로 고정한다. 「오토라벨 미적용」 배지와는 다른 상태다 — 그쪽은 라벨을 담았는데 적용되지 않는 프리셋의 것이고 이 배지는 라벨을 비워 대상에서 뺀 프리셋의 것이라, 두 배지가 한 카드에 함께 붙지 않는다.
- **type**: Badge
- **label**: 오토라벨 제외

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 프리셋 목록을 반응형 카드 그리드(좁은 화면 1열, 중간 2열, 넓은 화면 3열)로 표시한다(클라이언트 측 페이지네이션, 9건/페이지). 각 카드: 이벤트명과 유형코드를 함께 보인 제목 + 라벨 칩 목록(앞 6개 + 초과시 '+N') + 라벨 개수 + 수정일. 이벤트명은 서버가 응답에 실어 주는 표시명을 그대로 쓴다 — 화면이 이벤트 목록으로 역해석하면 그 목록에 없는 유형이 코드로만 노출된다. ★라벨 칩의 이름·색·형태는 라벨 마스터를 실시간 join 한 값이다. 담긴 라벨이 모두 AI 검출 클래스에 매핑되어 있지 않은 프리셋은 카드 제목 옆에 「오토라벨 미적용」 배지를 달아 구분한다 — 등록해 두고도 적용되지 않는 프리셋을 목록에서 바로 알아보게 하기 위해서다. 배지가 없으면 적용되는 프리셋이다. 실효 여부는 서버가 내려주는 값을 그대로 쓰고 화면에서 다시 판정하지 않는다. 라벨을 하나도 담지 않은 프리셋에는 카드 제목 옆에 「오토라벨 제외」 배지를 달아, 그 이벤트유형이 오토라벨링 대상에서 빠져 있음을 목록에서 바로 알아보게 한다 — 라벨을 담았는데 적용되지 않는 「오토라벨 미적용」과 다른 상태이고 두 배지가 함께 붙지 않는다.

**references_apis**:

- API-037

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

- **note**: 옵션은 등록된 전체 이벤트유형을 서버에서 조회해 채우고 이벤트명과 유형코드를 함께 보인다. ★필터 옵션 목록을 쓰지 않는다 — 그 목록은 제외 대분류를 감추므로 해당 유형의 프리셋을 만들거나 고칠 수 없게 된다. 같은 표시명을 가진 여러 유형코드가 한 그룹으로 접히는 것은 필터 축의 성질이며, 여기서는 유형을 개별로 고른다. 옵션 원천은 이벤트유형 관리 목록이다 — 표시명 맵을 주는 조회는 이름이 하나도 없는 유형을 응답에서 스스로 빼기 때문에 「등록된 전체」가 되지 못한다. 서버의 저장 검증이 등록 여부로 판정하므로 옵션도 같은 모집단이어야 한다. ★진입 모드에 따라 초기값이 다르다 — 신규 모드로 열면 아무것도 고르지 않은 상태이며 안내 문구 「이벤트유형을 선택하세요」로 미선택임을 드러낸다(문구는 이대로 고정한다). 수정 모드로 열면 그 프리셋에 저장돼 있는 매핑 이벤트유형이 선택된 상태로 열린다.
- **type**: Select
- **label**: 이벤트유형

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 필수. 20자 이하(코드값 표준도메인). 이벤트 1건에 프리셋 1건이라 이미 프리셋이 있는 이벤트는 거부된다.
- **placeholder**: 이벤트유형을 선택하세요

#### [2]

- **note**: 활성 라벨 마스터를 정렬순(sortNo, 동률은 labelId) 기준으로 나열해 보여준다. 제출은 labelId 배열만 전송한다. ★형태는 마스터가 소유하므로 읽기 전용 표시이며 사용자 토글 불가 ★라벨마다 AI 검출 클래스 매핑 여부를 함께 보여준다 — 매핑되지 않은 라벨에는 「AI 미매핑」 표시를 붙여 어느 라벨이 오토라벨 대상이 아닌지 짚어 준다. 매핑 여부는 서버가 라벨 항목마다 내려주는 값을 그대로 쓰고 화면에서 다시 판정하지 않는다. 매핑되지 않은 라벨도 고를 수 있다 — 선택을 막지 않는다. 수정 모드로 열면 그 프리셋에 저장돼 있는 라벨이 모두 선택된 상태로 열린다 — 검수자는 바꾸려는 라벨만 고치며 이미 담겨 있는 라벨을 다시 고르지 않는다. 상한을 넘겨서도 고를 수 있다 — 넘은 상태에서 무엇을 뺄지 고르려면 그 상태에 머무를 수 있어야 하므로 선택을 막지 않는다. 막는 것은 저장이다.
- **type**: Custom
- **label**: 라벨 마스터 체크박스 멀티셀렉트 (형태는 읽기 전용)

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 최대 20개 선택. 하나도 고르지 않은 채로도 저장할 수 있다 — 라벨을 비우면 저장 버튼이 곧바로 저장하지 않고 「라벨 없이 저장 확인」을 먼저 띄운다.
- **custom_name**: PresetCodeChip

#### [3]

- **note**: 선택한 라벨이 모두 AI 검출 클래스에 매핑되어 있지 않을 때만 노출된다. 문구는 「담긴 라벨이 모두 AI 검출 클래스에 매핑되어 있지 않아 이 프리셋은 오토라벨링에 적용되지 않습니다. 라벨 관리 화면에서 검출 클래스를 지정하면 그때부터 적용됩니다.」로 고정한다(라벨 관리 화면은 SCREEN-035 다). 저장을 막지 않는다 — 저장 버튼은 그대로 활성이고 저장은 성공하며, 나중에 라벨 마스터에 매핑을 지정하면 그때 적용되는 동선을 닫지 않기 위해서다. 같은 사실을 저장 성공 안내에도 함께 알린다.
- **type**: Alert
- **label**: 담긴 라벨이 모두 AI 미매핑일 때의 경고 배너

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [4]

- **note**: 고른 라벨이 상한을 넘는 동안에만 저장 수단 바로 곁에 노출된다. 지금 고른 개수와 상한을 넘었다는 사실을 함께 드러내 저장이 왜 막혔는지를 누르기 전에 알 수 있게 한다 — 먼 자리의 작은 안내로 대신하지 않는다. 상한 값은 라벨 선택에 이미 정해져 있는 단일 원천을 그대로 쓰고 이 자리에서 따로 정하지 않는다. 선택을 되돌려 상한 이하가 되면 이 안내는 사라지고 저장 수단이 다시 열린다.
- **type**: Alert
- **label**: 라벨 선택 개수 상한 초과 안내

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [5]

- **note**: 라벨을 하나 이상 고른 상태에서는 곧바로 저장한다. 라벨을 하나도 고르지 않은 상태에서 누르면 저장 전에 「라벨 없이 저장 확인」을 먼저 띄우고, 거기서 확인해야 저장 요청이 나간다. 신규 모드로 열면 이벤트유형이 미선택이라 이 버튼을 쓸 수 없고, 이벤트유형을 고르는 순간 곧바로 활성이 된다. 라벨을 하나도 고르지 않아도 이 버튼은 막히지 않는다 — 라벨을 비우는 것은 그 이벤트유형을 오토라벨링 대상에서 빼겠다는 정당한 선언이라, 실수와의 구분은 저장 전 확인이 맡는다. 수정 모드로 연 직후에는 이벤트유형이 이미 선택돼 있어 이 버튼이 곧바로 활성이며 필수 입력 오류 안내가 보이지 않는다. 단 고른 라벨이 상한을 넘는 동안에는 이 버튼을 쓸 수 없다 — 앞의 활성 서술은 개수가 상한 이하일 때의 이야기다. 막힌 사유와 지금 고른 개수는 이 버튼 곁의 안내가 드러낸다.
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

- **description**: 신규('새 프리셋 만들기')/수정('프리셋 편집') 공용 모달. 필드: 이벤트유형 select(필수), 라벨 선택. 프리셋은 이름과 설명을 갖지 않는다 — 식별 축은 이벤트유형 하나다. ★라벨은 라벨 마스터 단일 진실원에서 고른다 — 활성 마스터를 불러와 체크박스 멀티셀렉트하고 제출 시 labelId 배열만 전송한다. 프리셋은 라벨명·형태를 스냅샷 저장하지 않고 labelId FK 로 마스터를 실시간 join 한다. 담긴 라벨이 모두 AI 검출 클래스에 매핑되어 있지 않으면 그 프리셋은 오토라벨링에 적용되지 않으므로 경고 배너로 알린다 — 다만 저장을 막지 않는다. 라벨마다 매핑 여부를 표시해 어느 라벨이 그 원인인지 짚어 준다. ★라벨을 하나도 고르지 않고 저장할 수 있다 — 이벤트유형은 여전히 필수이고 라벨만 비울 수 있다. 라벨을 비운 프리셋은 그 이벤트유형을 오토라벨링 대상에서 빼겠다는 선언이므로, 실수로 못 고른 것과 구분되도록 저장 전에 「라벨 없이 저장 확인」을 띄운다. 이것은 위 매핑 경고와 다른 상태다 — 매핑 경고는 라벨을 하나 이상 고른 프리셋을 다루고, 라벨이 비면 그 경고 대신 확인이 뜬다. ★진입 모드마다 열릴 때의 초기 상태가 다르다. 신규 모드(헤더의 '프리셋 추가' 또는 빈 상태의 '새 프리셋 만들기')로 열면 이벤트유형과 라벨 두 축 모두 아무것도 선택되지 않은 빈 상태이며, 이벤트유형 자리는 안내 문구로 미선택임을 드러낸다. 수정 모드(카드의 '수정')로 열면 그 프리셋에 저장돼 있는 매핑 이벤트유형과 라벨이 두 축 모두 선택된 상태로 열린다 — 검수자는 바꾸려는 값만 고치며 이미 저장돼 있는 값을 다시 고르지 않는다. 그래서 수정 모드로 연 직후에는 필수 입력 오류 안내가 보이지 않고 저장 수단을 곧바로 쓸 수 있다. ★고른 라벨이 상한을 넘으면 저장할 수 없다 — 막힌 사유와 지금 고른 개수를 저장 수단 곁에서 알린다. 선택 자체는 막지 않는다.

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

### 라벨 없이 저장 확인

- **role**: modal
- **layout**: detail

**components**:

#### [1]

- **note**: 제목 문구는 「라벨을 하나도 고르지 않았습니다」로 고정한다.
- **type**: Dialog
- **label**: 라벨 없이 저장 확인

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 본문 문구는 이대로 고정한다. 보류되지 않는다는 사실을 반드시 함께 알린다 — 라벨을 담았는데 적용되지 않는 상태와 달리 이쪽은 배치가 멈추지 않는다.
- **type**: Text
- **label**: 이대로 저장하면 이 이벤트유형은 오토라벨링 대상에서 빠집니다. 해당 유형의 영상은 보류되지 않고 배치가 그대로 완료되며 오토라벨링만 건너뜁니다. 의도한 선택이 맞습니까?

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 편집 모달이 준비한 저장 요청을 그대로 이어 보낸다 — 신규는 API-038, 수정은 API-039.
- **type**: Button
- **label**: 라벨 없이 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [4]

- **note**: 편집 모달로 되돌아간다. 고른 이벤트유형과 선택 상태는 유지한다.
- **type**: Button
- **label**: 돌아가서 라벨 고르기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [5]

- **note**: 문구는 「프리셋을 저장했습니다. 이 이벤트유형은 오토라벨링 대상에서 빠집니다.」로 고정한다.
- **type**: Toast
- **label**: 라벨 없이 저장 완료 안내

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 편집 모달에서 라벨을 하나도 고르지 않은 채 저장을 누르면 저장 전에 뜬다. 라벨을 비우는 것은 그 이벤트유형을 오토라벨링 대상에서 빼겠다는 선언이라, 실수로 못 고른 것과 일부러 비운 것을 사람이 여기서 구분한다. 막지 않는다 — 정당한 선택이므로 확인만 받고 그대로 저장한다. 라벨을 하나 이상 고른 상태에서는 뜨지 않으며, 그 상태를 다루는 매핑 경고 배너와는 서로 다른 상태를 맡는다. 확인 후 저장이 성공하면 성공 안내에도 이 이벤트유형이 오토라벨링 대상에서 빠졌음을 함께 알린다.

**references_apis**:

- API-038
- API-039

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

## attached_files

_(empty)_

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
- IMPREC-127

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-25T12:54:05.844Z

### module_paths

_(empty)_

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-026/main.html
- **label**: 메인 페이지
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **description**: 
- **source_hash**: 09dc54bf7ebbe365ca642f087eea3333c46a7b3226502a82452e3e37144da33d
- **generated_at**: 2026-09-08T04:05:06.430Z
- **generated_by**: logi-update-specialist (SCREEN-026 v40 sections 기준 재생성)

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## uses_components

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-032

## covered_by_acceptances

- AC-1128
