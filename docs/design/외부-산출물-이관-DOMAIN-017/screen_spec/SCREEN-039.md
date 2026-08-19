---
logicraft_item: SCREEN-039
type: screen_spec
version: 5
domain: DOMAIN-017
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-19T12:39:54.457Z
status: NEW
prev_version: null
content_hash: 5470e3f2df0de42d004e54ddae60ed2428113203e5bc4b775c2ea5ac91990118
stale: true
raw: ./_raw/SCREEN-039.json
links:
  belongs_to_domain: ["[[DOMAIN-017]]"]
  consumes: ["[[API-205]]", "[[API-206]]", "[[API-207]]", "[[API-208]]", "[[API-209]]", "[[API-210]]", "[[API-211]]"]
  covered_by: ["[[AC-041]]", "[[AC-042]]", "[[AC-043]]", "[[AC-044]]", "[[AC-045]]", "[[AC-046]]", "[[AC-047]]"]
  realizes: ["[[UC-035]]"]
  references: ["[[API-205]]", "[[API-206]]", "[[API-207]]", "[[API-208]]", "[[API-209]]", "[[API-210]]", "[[API-211]]"]
---

# 외부 산출물 이관

## route

/manage/imports

## title

외부 산출물 이관

## device

desktop

## status

draft

## purpose

검수자가 외부에서 받은 산출물 폴더를 저작도구로 가져오는 관리 화면이다.

경로 입력 → 미리보기 확인 → 적재의 3단 흐름을 한 화면에서 순서대로 밟는다. 상단 폼에 산출물 폴더 경로를 넣고, 원본 영상이 따로 있으면 그 경로도 함께 넣으며, 이 산출물이 비식별이 끝난 것인지 원본인지를 고른다. 기본값은 원본 쪽이고, 비식별이 끝난 것으로 고를 때 경고를 보여준다 — 잘못 고르면 비식별되지 않은 화면이 학습데이터로 나가고 승인 이후에는 되돌릴 수단이 사실상 없기 때문이다.

검사를 요청하면 API-205 가 폴더를 훑어 영상 정보·프레임 수·라벨 수와 경고를 돌려주며 이 단계에서는 아무것도 저장되지 않는다. 경고에는 적재를 막지 않는 사항만 담기며, 짝이 없는 이미지나 선언 건수와 실제 파일 수가 다른 것이 여기에 해당하고 알리기만 하고 진행할 수 있다. 적재를 막는 사유는 경고와 따로 보여준다 — 대응이 정해지지 않은 분류 목록과 이미 가져온 산출물 정보를 각각 표시하고, 지금 상태로 적재할 수 있는지를 함께 알린다.

처음 보는 분류가 있으면 그 목록과 이름이 비슷한 후보가 표로 나오고, 검수자가 각각을 저작도구 라벨 또는 이벤트 유형에 연결해 확정한다(API-210). 한 번 확정하면 다음 산출물부터는 다시 묻지 않는다. 확정되지 않은 분류가 남아 있으면 적재 버튼이 비활성화된다.

적재(API-206)가 끝나면 영상은 곧바로 검수 대기가 되며 결과 요약과 함께 검수 목록으로 이동하는 길을 제공한다. 하단에는 지금까지 가져온 내역이 최근순으로 나오며(API-207) 실패한 건은 사유를 펼쳐볼 수 있다(API-208).

검수자만 사용할 수 있다.

## sections

### 가져오기 입력

- **role**: main
- **layout**: form

**components**:

#### [1]

- **type**: Heading
- **label**: 외부 산출물 가져오기

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 허용된 저장소 범위 밖이면 거부된다
- **type**: Input
- **label**: 산출물 폴더 경로

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **placeholder**: 예: /nas-storage/handover/00000073

#### [3]

- **type**: Input
- **label**: 원본 영상 경로 (선택)

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **placeholder**: 비우면 프레임과 라벨만 가져온다

#### [4]

- **note**: 기본값은 원본. 비식별 완료를 고르면 주의 문구 표시
- **type**: RadioGroup
- **label**: 이 산출물은

**columns**:

_(empty)_

- **io_attr**: I

**options**:

- 원본이다
- 비식별이 끝난 것이다

#### [5]

- **note**: 비식별 완료 선택 시에만 노출
- **type**: Alert
- **label**: 비식별이 끝난 것으로 고르면 그대로 학습데이터로 나간다. 잘못 고르면 승인 뒤에 되돌릴 수단이 사실상 없다.
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **type**: Button
- **label**: 검사

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-205

- **description**: 산출물 폴더 경로와 원본 영상 경로를 받고, 이 산출물의 비식별 여부를 고르는 자리다. 영상 경로는 선택이며 비우면 프레임과 라벨만 가져온다. 비식별 여부는 기본이 원본이고, 비식별이 끝난 것으로 고르면 주의 문구가 나타난다.

**references_apis**:

- API-205

**references_features**:

_(empty)_

### 미리보기

- **role**: main
- **layout**: detail

**components**:

#### [1]

- **type**: Heading
- **label**: 가져올 내용

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: KeyValue
- **label**: 영상 파일명·해상도·길이·이벤트 유형·촬영환경

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

#### [3]

- **type**: Stat
- **label**: 프레임 수 (실제 / 선언)

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

#### [4]

- **type**: Stat
- **label**: 라벨 수

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

#### [5]

- **type**: Alert
- **label**: 적재를 막는 문제 — 이미 가져온 산출물이거나 대응이 정해지지 않은 분류가 남음
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

#### [6]

- **type**: Alert
- **label**: 알리기만 하는 문제 — 짝이 없는 이미지, 선언과 실제 건수 불일치
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

- **description**: 검사 결과를 보여준다. 영상 정보와 프레임·라벨 건수, 그리고 경고가 나온다. 경고는 적재를 막는 것과 알리기만 하는 것이 구분된다. 선언된 프레임 수와 실제 파일 수가 다르면 둘 다 보여주고 실제 파일을 기준으로 적재한다는 것을 명시한다.

**references_apis**:

- API-205

**references_features**:

_(empty)_

### 분류 대응 확정

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Heading
- **label**: 처음 보는 분류

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Text
- **label**: 연결하지 않은 분류가 남아 있으면 적재할 수 없다. 이름만 보고 자동으로 정하지 않는 것은, 짐작으로 연결하면 다른 분류로 저장되고 나중에 구분할 수 없기 때문이다.

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 연결할 대상은 선택 컨트롤이며 추천 후보가 미리 골라져 있다
- **type**: Table
- **label**: 미등록 분류 목록

**columns**:

- 종류
- 외부 분류 코드
- 외부 표시 이름
- 연결할 대상
- 확인

- **io_attr**: I

**options**:

_(empty)_

#### [4]

- **type**: Button
- **label**: 대응 확정

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary
- **triggers_api**: API-210

- **description**: 처음 보는 분류만 모아 보여주고 저작도구 라벨·이벤트 유형에 연결하게 한다. 이름이 비슷한 후보를 미리 골라 놓지만 사람이 확인해야 확정된다. 한 번 확정한 대응은 다음 산출물부터 자동으로 적용되므로 이 표에 다시 나오지 않는다.

**references_apis**:

- API-209
- API-210
- API-211

**references_features**:

_(empty)_

### 적재 실행

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **type**: Checkbox
- **label**: 알리기만 하는 경고를 확인했다

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

#### [2]

- **note**: 대응이 남았거나 중복이면 비활성
- **type**: Button
- **label**: 가져오기
- **state**: disabled

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-206

#### [3]

- **type**: Alert
- **label**: 적재 결과 — 영상 식별번호·프레임 수·라벨 수
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

#### [4]

- **type**: Link
- **label**: 검수 목록으로 이동
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 미리보기와 대응을 확인한 뒤 실제로 가져오는 자리다. 적재를 막는 문제가 남아 있으면 버튼이 비활성화된다. 끝나면 결과 요약과 함께 검수 목록으로 가는 길을 제공한다.

**references_apis**:

- API-206

**references_features**:

_(empty)_

### 이관 이력

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Heading
- **label**: 가져온 내역

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Select
- **label**: 상태

**columns**:

_(empty)_

- **io_attr**: I

**options**:

- 전체
- 진행중
- 성공
- 실패

#### [3]

- **type**: Table
- **label**: 이관 이력

**columns**:

- 가져온 시각
- 폴더명
- 영상 번호
- 상태
- 프레임 수
- 라벨 수
- 실행자

- **io_attr**: O

**options**:

_(empty)_

#### [4]

- **type**: Pagination
- **label**: 페이지

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 지금까지 가져온 내역을 최근순으로 보여준다. 실패한 건은 사유를 펼쳐볼 수 있다. 정렬은 시간순 하나로만 하며 상태를 우선순위로 섞지 않는다.

**references_apis**:

- API-207
- API-208

**references_features**:

_(empty)_

## framework

react

## brownfield

### status

new

## surface_kind

web

## consumes_apis

- API-205
- API-206
- API-207
- API-208
- API-209
- API-210
- API-211

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

## required_roles

_(empty)_

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-039/main.html
- **label**: 외부 산출물 이관 (본문)
- **surface**: page

**sections**:

_(empty)_

- **description**: 경로 입력 → 미리보기 → 분류 대응 확정 → 적재 실행 → 이관 이력의 5단 본문 와이어프레임
- **source_hash**: 69be2bcfc6a9ba5c39d5f9372e10bfa9220245a8ac51408dbfd70a8fed0da517
- **generated_at**: 2026-08-19T09:34:34.233Z

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-035

## covered_by_acceptances

- AC-041
- AC-042
- AC-043
- AC-044
- AC-045
- AC-046
- AC-047
