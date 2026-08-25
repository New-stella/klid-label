---
logicraft_item: SCREEN-039
type: screen_spec
version: 17
domain: DOMAIN-017
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-25T10:52:16.669Z
status: CHANGED
prev_version: 17
content_hash: 5c10a8dc2b834e7bf49a3c676931a1fe1edd14b9f79ec356d999a91478c395c6
stale: true
raw: ./_raw/SCREEN-039.json
links:
  belongs_to_domain: ["[[DOMAIN-017]]"]
  consumes: ["[[API-205]]", "[[API-206]]", "[[API-207]]", "[[API-208]]", "[[API-209]]", "[[API-210]]", "[[API-211]]", "[[API-215]]"]
  covered_by: ["[[AC-041]]", "[[AC-042]]", "[[AC-043]]", "[[AC-044]]", "[[AC-045]]", "[[AC-046]]", "[[AC-047]]"]
  realizes: ["[[UC-035]]", "[[UC-036]]"]
  references: ["[[API-205]]", "[[API-206]]", "[[API-207]]", "[[API-208]]", "[[API-209]]", "[[API-210]]", "[[API-211]]", "[[API-215]]"]
  requires: ["[[ROLE-001]]"]
  applies_to_backward: ["[[SHELL-001]]"]
  navigates_to_backward: ["[[NAV-001]]"]
  references_backward: ["[[TEST-007]]", "[[TEST-008]]", "[[UC-035]]", "[[UC-036]]"]
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

검사를 요청하면 API-205 가 폴더를 훑어 영상 정보·프레임 수·라벨 수와 경고를 돌려주며 이 단계에서는 아무것도 저장되지 않는다. 경고는 한 목록으로 돌아오고 그 안에 적재를 막는 사유와 막지 않는 사유가 함께 담긴다. 막지 않는 예로는 짝이 없는 이미지, 선언 건수와 실제 파일 수의 차이, 폴더 안의 바로가기를 건너뛴 사실이 있고, 막는 예로는 훑기 상한을 넘은 경우, 식별자를 만들 수 없는 경우, 프레임이 한 건도 없는 경우가 있다. 이 열거는 예시이며 전수 목록이 아니다. 지금 상태로 적재할 수 있는지는 응답의 적재 가능 여부 값 하나가 정하고, 적재 버튼의 활성 여부도 그 값 하나가 정한다. 대응이 정해지지 않은 분류 목록과 이미 가져온 산출물 정보는 각각 따로 표시한다.

처음 보는 분류가 있으면 그 목록과 이름이 비슷한 후보가 표로 나오고, 검수자가 각각을 저작도구 라벨 또는 이벤트 유형에 연결해 확정한다(API-210). 이벤트 축은 한 행이 최말단 이름 하나이며 상위 계층 이름은 이 목록에 나오지 않는다. 한 번 확정하면 다음 산출물부터는 다시 묻지 않는다. 확정되지 않은 분류가 남아 있으면 적재 가능 여부가 서지 않아 적재 버튼이 비활성화된다.

적재(API-206)가 끝나면 영상은 곧바로 검수 대기가 되며 결과 요약과 함께 검수 목록으로 이동하는 길을 제공한다. 원본이라고 지정해 가져온 영상은 승인 보류가 서므로 적재 결과에 보류 여부와 그 보류를 푸는 길이 어느 갈래인지를 함께 알린다. 이 보류는 검수 승인만 막고 검수 착수·라벨 조회·프레임 이미지 열람은 막지 않는다. 하단에는 지금까지 가져온 내역이 최근순으로 나오며(API-207) 실패한 건은 사유를 펼쳐볼 수 있다(API-208).

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
- **label**: 적재를 막는 경고 — 이미 가져온 산출물, 대응이 정해지지 않은 분류, 훑기 상한 초과, 식별자 생성 불가, 프레임 0건 (예시이며 전수 목록이 아니다)
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

#### [6]

- **type**: Alert
- **label**: 알리기만 하는 경고 — 짝이 없는 이미지, 선언과 실제 건수 불일치, 폴더 안의 바로가기를 건너뜀 (예시이며 전수 목록이 아니다)
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

- **description**: 검사 결과를 보여준다. 영상 정보와 프레임·라벨 건수, 그리고 경고가 나온다. 경고는 한 목록으로 돌아오며 그 안에서 적재를 막는 것과 알리기만 하는 것이 구분되어 표시된다. 막는 예로는 훑기 상한을 넘은 경우, 식별자를 만들 수 없는 경우, 프레임이 한 건도 없는 경우가 있고, 막지 않는 예로는 짝이 없는 이미지, 선언 건수와 실제 파일 수의 차이, 폴더 안의 바로가기를 건너뛴 사실이 있다. 이 열거는 예시이며 전수 목록이 아니다. 폴더 안에 다른 자리를 가리키는 바로가기가 있으면 따라가지 않고 건너뛰며 그 사실을 이 자리에 드러낸다. 이 알림은 적재를 막지 않는다. 지금 상태로 적재할 수 있는지는 응답의 적재 가능 여부 값 하나가 정한다. 선언된 프레임 수와 실제 파일 수가 다르면 둘 다 보여주고 실제 파일을 기준으로 적재한다는 것을 명시한다.

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

- **description**: 처음 보는 분류만 모아 보여주고 저작도구 라벨·이벤트 유형에 연결하게 한다. 이름이 비슷한 후보를 미리 골라 놓지만 사람이 확인해야 확정된다. 한 번 확정한 대응은 다음 산출물부터 자동으로 적용되므로 이 표에 다시 나오지 않는다. 두 축은 한 행이 가리키는 것이 다르다. 라벨 축은 산출물이 영문 코드와 표시 이름을 따로 주므로 두 값을 각각 보여주고 코드를 열쇠로 삼는다. 이벤트 축은 코드가 없어 이름이 곧 코드 자리에 들어가며, 한 행은 가장 구체적인 최말단 이름 하나다. 산출물이 상위 계층 이름을 함께 주더라도 그것은 참고 정보라 이 목록에 나오지 않는다.

**references_apis**:

- API-209
- API-210
- API-211

**references_features**:

_(empty)_

### 확정된 대응

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Heading
- **label**: 확정된 분류 대응

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Select
- **label**: 대응 종류

**columns**:

_(empty)_

- **io_attr**: I

**options**:

- 전체
- 라벨
- 이벤트 유형

#### [3]

- **note**: 기본은 쓰는 대응만 보여주고, 켜면 쓰지 않게 표시한 것까지 함께 나온다
- **type**: Checkbox
- **label**: 쓰지 않게 표시한 대응까지 보기

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

#### [4]

- **note**: 외부 분류 코드와 외부 표시 이름은 두 열로 유지한다. 이벤트 축은 코드가 없어 두 값이 같게 보이지만 라벨 축은 영문 코드와 표시 이름이 서로 다르다
- **type**: Table
- **label**: 분류 대응 목록

**columns**:

- 종류
- 외부 분류 코드
- 외부 표시 이름
- 연결된 대상
- 사용 여부

- **io_attr**: O

**options**:

_(empty)_

#### [5]

- **note**: 쪽 단위로 돌려받는다. 가져온 내역과 같은 형식을 쓴다
- **type**: Pagination
- **label**: 페이지

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **note**: 누르면 곧바로 해제되지 않고 확인 단계가 뜬다
- **type**: Button
- **label**: 해제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [7]

- **note**: 해제 버튼을 누른 행에 대해서만 뜬다
- **type**: Dialog
- **label**: 이 대응을 해제하면 그 분류는 다시 처음 보는 분류가 된다. 다음 산출물을 가져올 때 사람이 다시 확정하기 전까지 적재가 막힌다. 해제하겠는가?
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-211

- **description**: 확정된 분류 대응을 조회하고 잘못 걸린 것을 해제하는 자리다. 바로 위에서 처음 보는 분류를 확정하고 여기서 기존 대응을 확인·수정한다. 대응 종류와 쓰지 않게 표시한 대응의 포함 여부로 걸러내며 목록은 쪽 단위로 돌려받는다. 표는 외부 분류 코드와 외부 표시 이름을 각각 두 열로 유지한다. 이벤트 축은 코드가 없어 두 열이 같은 값을 보이지만 그것은 그 축의 사실이고, 라벨 축은 영문 코드와 표시 이름이 실제로 달라 두 열이 서로 다른 값을 담는다. 열을 하나로 합치면 라벨 축이 손해를 본다. 해제는 확인 단계를 거쳐야 성립한다. 되돌리면 그 분류가 다시 처음 보는 분류가 되어 다음 산출물을 가져올 때 사람이 다시 확정하기 전까지 적재가 막히기 때문이다. 확인 문구는 무엇이 되돌려지는지와 그 결과를 함께 말한다. 해제해도 행을 지우지 않고 쓰지 않음으로 표시하며, 이미 그 대응으로 적재된 라벨은 그대로 둔다.

**references_apis**:

- API-209
- API-211

**references_features**:

_(empty)_

### 적재 실행

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **type**: Checkbox
- **label**: 검사에서 돌아온 경고를 확인했다

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

#### [2]

- **note**: 활성 여부는 검사 응답의 적재 가능 여부 값 하나가 정한다
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

- **note**: 원본이라고 지정해 가져온 경우에만 노출
- **type**: Alert
- **label**: 승인 보류 — 비식별이 끝나야 검수 승인이 가능하다. 검수 착수와 라벨 조회, 프레임 이미지 열람은 그대로 열려 있다.
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

#### [5]

- **note**: 보류가 섰을 때만 노출
- **type**: Text
- **label**: 보류를 푸는 길 — 원본 영상을 함께 가져왔으면 저작도구가 비식별 단계를 태우고 그 비식별이 성공으로 기록되면 풀린다. 프레임만 가져왔으면 외부에서 비식별한 산출물을 받아 기록하는 별도 행위가 푼다.
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

#### [6]

- **type**: Link
- **label**: 검수 목록으로 이동
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

**description**:

미리보기와 대응을 확인한 뒤 실제로 가져오는 자리다. 버튼의 활성 여부는 검사 응답의 적재 가능 여부 값 하나가 정한다. 막는 경고가 남아 있거나 확정되지 않은 분류가 있으면 그 값이 서지 않아 버튼이 비활성화된다. 끝나면 결과 요약과 함께 검수 목록으로 가는 길을 제공한다.

적재 결과에는 이 영상에 승인 보류가 섰는지와 그 보류를 푸는 길이 어느 갈래인지가 함께 나온다. 비식별이 끝난 것으로 지정해 가져왔으면 보류가 서지 않아 곧바로 검수하고 승인할 수 있다. 원본이라고 지정해 가져왔고 원본 영상 파일을 함께 준 경우에는 보류가 서고, 저작도구가 비식별 단계를 태워 그 비식별이 성공으로 기록되면 보류가 풀린다. 원본이라고 지정했으나 영상 파일 없이 프레임만 가져온 경우에도 보류가 서며, 이때는 비식별할 대상 영상이 없어 외부에서 비식별한 산출물을 받아 기록하는 별도 행위가 그 보류를 푼다.

이 보류는 검수 승인 하나만 막는다. 검수 착수와 라벨 조회, 프레임 이미지 열람은 막지 않는다. 검수자가 화면 전체가 잠긴 것으로 오해하지 않도록 막히는 범위를 함께 알린다.

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
- 승인 보류
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

#### [5]

- **note**: 승인 보류가 선 행에서만 열린다
- **type**: Button
- **label**: 비식별 완료 기록

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [6]

- **note**: 허용된 저장소 범위 밖이거나 그 자리에 산출물이 실재하지 않으면 거부된다
- **type**: Input
- **label**: 비식별 산출물 폴더 경로
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **placeholder**: 예: /nas-storage/handover/00000073-deid

#### [7]

- **note**: 이름이 맞는 파일이 한 건도 없으면 아무것도 기록하지 않고 거부하며 승인 보류는 그대로 남는다
- **type**: Button
- **label**: 기록
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-215

#### [8]

- **type**: Alert
- **label**: 기록 결과 — 승인 보류 해제 여부, 비식별 이미지를 채운 프레임 수, 이름이 맞는 파일이 없어 비워 둔 프레임 수
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

#### [9]

- **note**: 비워 둔 프레임이 하나라도 있을 때만 노출
- **type**: Alert
- **label**: 비워 둔 프레임이 남았다 — 그만큼의 프레임이 비식별 이미지 없이 남아 이 영상의 학습데이터 산출물에 빠진 채로 나간다.
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

**description**:

지금까지 가져온 내역을 최근순으로 보여준다. 실패한 건은 사유를 펼쳐볼 수 있다. 정렬은 시간순 하나로만 하며 상태를 우선순위로 섞지 않는다.

승인 보류가 선 영상은 이 목록에서 드러나며, 그 행에서 비식별 완료를 기록하는 자리를 연다. 검수 화면이 아니라 이 자리에 두는 까닭은 이 목록이 이미 영상별 상태를 보여주고 있고 검수 화면은 다른 갈래의 소관이라 경계를 넘기 때문이다. 기록은 외부에서 비식별한 산출물이 놓인 폴더 위치를 사람이 입력해 이뤄진다. 결과에는 비식별 이미지를 채운 프레임 수와 함께 이름이 맞는 파일이 없어 비워 둔 프레임 수를 보여준다. 비워 둔 프레임이 하나라도 있으면 그 영상의 학습데이터 산출물에 비식별 이미지가 빠진 채로 나가므로 사람이 그 사실을 알아야 한다. 이름이 맞는 파일이 한 건도 없으면 아무것도 기록하지 않고 거부하며 승인 보류는 그대로 남는다.

조회가 실패했을 때와 조회는 됐으나 이력이 한 건도 없을 때를 다른 자리로 가른다. 앞은 이관 이력을 불러올 수 없다는 안내이고 뒤는 가져온 내역이 없다는 빈 상태 안내다. 한 문구로 묶으면 그런 이력이 없다는 뜻과 잘못 물었다는 뜻이 구분되지 않기 때문이다. 다만 계약을 벗어난 요청이라 거부된 것과 그 밖의 조회 실패는 같은 안내로 묶는다. 상태를 고르는 자리는 정해진 선택지 안에서만 고르므로 화면 조작만으로는 계약을 벗어난 값이 나가지 않으며, 그런 값은 주소를 직접 고쳐야 닿는다. 실패 사유를 펼치는 자리도 같은 갈래를 지켜, 사유를 불러오지 못한 것과 기록된 사유가 없는 것을 다른 문구로 알린다.

승인 보류는 이 목록에만 있고 이관 상세에는 없다. 상세는 이관 그 자체를, 보류는 그 이관으로 만들어진 영상의 검수 상태를 말해 축이 다르기 때문이다. 진행 상태로 대신 판단하지도 않는다. 값은 보류·없음·미상 세 갈래이며, 비어 있는 것을 보류 아님으로 단정하지 않고 기록하는 자리를 연다. 단정하면 그 자리가 감춰져 그 영상은 승인될 길을 잃는다.

**references_apis**:

- API-207
- API-208
- API-215

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
- API-215

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-111

### progress

100

### subtasks

#### [1]

- **done**: true
- **description**: 가져오기 입력 — 폴더 경로·영상 경로·비식별 여부(기본 원본) + 비식별 완료 선택 시 주의 문구

#### [2]

- **done**: true
- **description**: 미리보기 — 알림을 막는 것과 알리기만 하는 것으로 나눠 표시. 적재 가능 여부는 응답 값 하나로만 판정

#### [3]

- **done**: true
- **description**: 분류 대응 확정 — 추천 후보 미리 선택 + 사람 확인 필수, 후보가 비어도 오류로 그리지 않음

#### [4]

- **done**: true
- **description**: 확정된 대응 — 종류·미사용 포함 걸러내기, 쪽 이동, 해제에 확인 단계

#### [5]

- **done**: true
- **description**: 적재 실행 — 결과 요약·승인 보류 안내·보류를 푸는 길·검수 목록 이동

#### [6]

- **done**: true
- **description**: 이관 이력 — 상태 걸러내기·쪽 이동·실패 사유 펼침 + 비식별 완료 기록 진입점

#### [7]

- **done**: true
- **description**: 비식별 완료 기록 — 폴더 경로 입력, 결과에 비워 둔 프레임 수 경고

#### [8]

- **done**: true
- **description**: 라우팅 및 관리 메뉴 등재(검수자 전용)

### last_updated

2026-08-25T01:21:34.287Z

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-039/main.html
- **label**: 메인 페이지
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

#### 가져오기 입력

- **role**: main
- **layout**: form

**components**:

##### [1]

- **type**: Heading
- **label**: 외부 산출물 가져오기

**columns**:

_(empty)_

**options**:

_(empty)_

##### [2]

- **note**: 허용된 저장소 범위 밖이면 거부된다
- **type**: Input
- **label**: 산출물 폴더 경로

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **placeholder**: 예: /nas-storage/handover/00000073

##### [3]

- **type**: Input
- **label**: 원본 영상 경로 (선택)

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **placeholder**: 비우면 프레임과 라벨만 가져온다

##### [4]

- **note**: 기본값은 원본. 비식별 완료를 고르면 주의 문구 표시
- **type**: RadioGroup
- **label**: 이 산출물은

**columns**:

_(empty)_

- **io_attr**: I

**options**:

- 원본이다
- 비식별이 끝난 것이다

##### [5]

- **note**: 비식별 완료 선택 시에만 노출
- **type**: Alert
- **label**: 비식별이 끝난 것으로 고르면 그대로 학습데이터로 나간다. 잘못 고르면 승인 뒤에 되돌릴 수단이 사실상 없다.
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

##### [6]

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

#### 미리보기

- **role**: main
- **layout**: detail

**components**:

##### [1]

- **type**: Heading
- **label**: 가져올 내용

**columns**:

_(empty)_

**options**:

_(empty)_

##### [2]

- **type**: KeyValue
- **label**: 영상 파일명·해상도·길이·이벤트 유형·촬영환경

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

##### [3]

- **type**: Stat
- **label**: 프레임 수 (실제 / 선언)

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

##### [4]

- **type**: Stat
- **label**: 라벨 수

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

##### [5]

- **type**: Alert
- **label**: 적재를 막는 경고 — 이미 가져온 산출물, 대응이 정해지지 않은 분류, 훑기 상한 초과, 식별자 생성 불가, 프레임 0건 (예시이며 전수 목록이 아니다)
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

##### [6]

- **type**: Alert
- **label**: 알리기만 하는 경고 — 짝이 없는 이미지, 선언과 실제 건수 불일치, 폴더 안의 바로가기를 건너뜀 (예시이며 전수 목록이 아니다)
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

- **description**: 검사 결과를 보여준다. 영상 정보와 프레임·라벨 건수, 그리고 경고가 나온다. 경고는 한 목록으로 돌아오며 그 안에서 적재를 막는 것과 알리기만 하는 것이 구분되어 표시된다. 막는 예로는 훑기 상한을 넘은 경우, 식별자를 만들 수 없는 경우, 프레임이 한 건도 없는 경우가 있고, 막지 않는 예로는 짝이 없는 이미지, 선언 건수와 실제 파일 수의 차이, 폴더 안의 바로가기를 건너뛴 사실이 있다. 이 열거는 예시이며 전수 목록이 아니다. 폴더 안에 다른 자리를 가리키는 바로가기가 있으면 따라가지 않고 건너뛰며 그 사실을 이 자리에 드러낸다. 이 알림은 적재를 막지 않는다. 지금 상태로 적재할 수 있는지는 응답의 적재 가능 여부 값 하나가 정한다. 선언된 프레임 수와 실제 파일 수가 다르면 둘 다 보여주고 실제 파일을 기준으로 적재한다는 것을 명시한다.

**references_apis**:

- API-205

**references_features**:

_(empty)_

#### 분류 대응 확정

- **role**: main
- **layout**: list

**components**:

##### [1]

- **type**: Heading
- **label**: 처음 보는 분류

**columns**:

_(empty)_

**options**:

_(empty)_

##### [2]

- **type**: Text
- **label**: 연결하지 않은 분류가 남아 있으면 적재할 수 없다. 이름만 보고 자동으로 정하지 않는 것은, 짐작으로 연결하면 다른 분류로 저장되고 나중에 구분할 수 없기 때문이다.

**columns**:

_(empty)_

**options**:

_(empty)_

##### [3]

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

##### [4]

- **type**: Button
- **label**: 대응 확정

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary
- **triggers_api**: API-210

- **description**: 처음 보는 분류만 모아 보여주고 저작도구 라벨·이벤트 유형에 연결하게 한다. 이름이 비슷한 후보를 미리 골라 놓지만 사람이 확인해야 확정된다. 한 번 확정한 대응은 다음 산출물부터 자동으로 적용되므로 이 표에 다시 나오지 않는다. 두 축은 한 행이 가리키는 것이 다르다. 라벨 축은 산출물이 영문 코드와 표시 이름을 따로 주므로 두 값을 각각 보여주고 코드를 열쇠로 삼는다. 이벤트 축은 코드가 없어 이름이 곧 코드 자리에 들어가며, 한 행은 가장 구체적인 최말단 이름 하나다. 산출물이 상위 계층 이름을 함께 주더라도 그것은 참고 정보라 이 목록에 나오지 않는다.

**references_apis**:

- API-209
- API-210
- API-211

**references_features**:

_(empty)_

#### 확정된 대응

- **role**: main
- **layout**: list

**components**:

##### [1]

- **type**: Heading
- **label**: 확정된 분류 대응

**columns**:

_(empty)_

**options**:

_(empty)_

##### [2]

- **type**: Select
- **label**: 대응 종류

**columns**:

_(empty)_

- **io_attr**: I

**options**:

- 전체
- 라벨
- 이벤트 유형

##### [3]

- **note**: 기본은 쓰는 대응만 보여주고, 켜면 쓰지 않게 표시한 것까지 함께 나온다
- **type**: Checkbox
- **label**: 쓰지 않게 표시한 대응까지 보기

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

##### [4]

- **note**: 외부 분류 코드와 외부 표시 이름은 두 열로 유지한다. 이벤트 축은 코드가 없어 두 값이 같게 보이지만 라벨 축은 영문 코드와 표시 이름이 서로 다르다
- **type**: Table
- **label**: 분류 대응 목록

**columns**:

- 종류
- 외부 분류 코드
- 외부 표시 이름
- 연결된 대상
- 사용 여부

- **io_attr**: O

**options**:

_(empty)_

##### [5]

- **note**: 쪽 단위로 돌려받는다. 가져온 내역과 같은 형식을 쓴다
- **type**: Pagination
- **label**: 페이지

**columns**:

_(empty)_

**options**:

_(empty)_

##### [6]

- **note**: 누르면 곧바로 해제되지 않고 확인 단계가 뜬다
- **type**: Button
- **label**: 해제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

##### [7]

- **note**: 해제 버튼을 누른 행에 대해서만 뜬다
- **type**: Dialog
- **label**: 이 대응을 해제하면 그 분류는 다시 처음 보는 분류가 된다. 다음 산출물을 가져올 때 사람이 다시 확정하기 전까지 적재가 막힌다. 해제하겠는가?
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-211

- **description**: 확정된 분류 대응을 조회하고 잘못 걸린 것을 해제하는 자리다. 바로 위에서 처음 보는 분류를 확정하고 여기서 기존 대응을 확인·수정한다. 대응 종류와 쓰지 않게 표시한 대응의 포함 여부로 걸러내며 목록은 쪽 단위로 돌려받는다. 표는 외부 분류 코드와 외부 표시 이름을 각각 두 열로 유지한다. 이벤트 축은 코드가 없어 두 열이 같은 값을 보이지만 그것은 그 축의 사실이고, 라벨 축은 영문 코드와 표시 이름이 실제로 달라 두 열이 서로 다른 값을 담는다. 열을 하나로 합치면 라벨 축이 손해를 본다. 해제는 확인 단계를 거쳐야 성립한다. 되돌리면 그 분류가 다시 처음 보는 분류가 되어 다음 산출물을 가져올 때 사람이 다시 확정하기 전까지 적재가 막히기 때문이다. 확인 문구는 무엇이 되돌려지는지와 그 결과를 함께 말한다. 해제해도 행을 지우지 않고 쓰지 않음으로 표시하며, 이미 그 대응으로 적재된 라벨은 그대로 둔다.

**references_apis**:

- API-209
- API-211

**references_features**:

_(empty)_

#### 적재 실행

- **role**: main
- **layout**: stack

**components**:

##### [1]

- **type**: Checkbox
- **label**: 검사에서 돌아온 경고를 확인했다

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

##### [2]

- **note**: 활성 여부는 검사 응답의 적재 가능 여부 값 하나가 정한다
- **type**: Button
- **label**: 가져오기
- **state**: disabled

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-206

##### [3]

- **type**: Alert
- **label**: 적재 결과 — 영상 식별번호·프레임 수·라벨 수
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

##### [4]

- **note**: 원본이라고 지정해 가져온 경우에만 노출
- **type**: Alert
- **label**: 승인 보류 — 비식별이 끝나야 검수 승인이 가능하다. 검수 착수와 라벨 조회, 프레임 이미지 열람은 그대로 열려 있다.
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

##### [5]

- **note**: 보류가 섰을 때만 노출
- **type**: Text
- **label**: 보류를 푸는 길 — 원본 영상을 함께 가져왔으면 저작도구가 비식별 단계를 태우고 그 비식별이 성공으로 기록되면 풀린다. 프레임만 가져왔으면 외부에서 비식별한 산출물을 받아 기록하는 별도 행위가 푼다.
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

##### [6]

- **type**: Link
- **label**: 검수 목록으로 이동
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

**description**:

미리보기와 대응을 확인한 뒤 실제로 가져오는 자리다. 버튼의 활성 여부는 검사 응답의 적재 가능 여부 값 하나가 정한다. 막는 경고가 남아 있거나 확정되지 않은 분류가 있으면 그 값이 서지 않아 버튼이 비활성화된다. 끝나면 결과 요약과 함께 검수 목록으로 가는 길을 제공한다.

적재 결과에는 이 영상에 승인 보류가 섰는지와 그 보류를 푸는 길이 어느 갈래인지가 함께 나온다. 비식별이 끝난 것으로 지정해 가져왔으면 보류가 서지 않아 곧바로 검수하고 승인할 수 있다. 원본이라고 지정해 가져왔고 원본 영상 파일을 함께 준 경우에는 보류가 서고, 저작도구가 비식별 단계를 태워 그 비식별이 성공으로 기록되면 보류가 풀린다. 원본이라고 지정했으나 영상 파일 없이 프레임만 가져온 경우에도 보류가 서며, 이때는 비식별할 대상 영상이 없어 외부에서 비식별한 산출물을 받아 기록하는 별도 행위가 그 보류를 푼다.

이 보류는 검수 승인 하나만 막는다. 검수 착수와 라벨 조회, 프레임 이미지 열람은 막지 않는다. 검수자가 화면 전체가 잠긴 것으로 오해하지 않도록 막히는 범위를 함께 알린다.

**references_apis**:

- API-206

**references_features**:

_(empty)_

#### 이관 이력

- **role**: main
- **layout**: list

**components**:

##### [1]

- **type**: Heading
- **label**: 가져온 내역

**columns**:

_(empty)_

**options**:

_(empty)_

##### [2]

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

##### [3]

- **type**: Table
- **label**: 이관 이력

**columns**:

- 가져온 시각
- 폴더명
- 영상 번호
- 상태
- 승인 보류
- 프레임 수
- 라벨 수
- 실행자

- **io_attr**: O

**options**:

_(empty)_

##### [4]

- **type**: Pagination
- **label**: 페이지

**columns**:

_(empty)_

**options**:

_(empty)_

##### [5]

- **note**: 승인 보류가 선 행에서만 열린다
- **type**: Button
- **label**: 비식별 완료 기록

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

##### [6]

- **note**: 허용된 저장소 범위 밖이거나 그 자리에 산출물이 실재하지 않으면 거부된다
- **type**: Input
- **label**: 비식별 산출물 폴더 경로
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **placeholder**: 예: /nas-storage/handover/00000073-deid

##### [7]

- **note**: 이름이 맞는 파일이 한 건도 없으면 아무것도 기록하지 않고 거부하며 승인 보류는 그대로 남는다
- **type**: Button
- **label**: 기록
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-215

##### [8]

- **type**: Alert
- **label**: 기록 결과 — 승인 보류 해제 여부, 비식별 이미지를 채운 프레임 수, 이름이 맞는 파일이 없어 비워 둔 프레임 수
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

##### [9]

- **note**: 비워 둔 프레임이 하나라도 있을 때만 노출
- **type**: Alert
- **label**: 비워 둔 프레임이 남았다 — 그만큼의 프레임이 비식별 이미지 없이 남아 이 영상의 학습데이터 산출물에 빠진 채로 나간다.
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

**description**:

지금까지 가져온 내역을 최근순으로 보여준다. 실패한 건은 사유를 펼쳐볼 수 있다. 정렬은 시간순 하나로만 하며 상태를 우선순위로 섞지 않는다.

승인 보류가 선 영상은 이 목록에서 드러나며, 그 행에서 비식별 완료를 기록하는 자리를 연다. 검수 화면이 아니라 이 자리에 두는 까닭은 이 목록이 이미 영상별 상태를 보여주고 있고 검수 화면은 다른 갈래의 소관이라 경계를 넘기 때문이다. 기록은 외부에서 비식별한 산출물이 놓인 폴더 위치를 사람이 입력해 이뤄진다. 결과에는 비식별 이미지를 채운 프레임 수와 함께 이름이 맞는 파일이 없어 비워 둔 프레임 수를 보여준다. 비워 둔 프레임이 하나라도 있으면 그 영상의 학습데이터 산출물에 비식별 이미지가 빠진 채로 나가므로 사람이 그 사실을 알아야 한다. 이름이 맞는 파일이 한 건도 없으면 아무것도 기록하지 않고 거부하며 승인 보류는 그대로 남는다.

**references_apis**:

- API-207
- API-208
- API-215

**references_features**:

_(empty)_

- **description**: 경로 입력 → 미리보기 → 분류 대응 확정 → 확정된 대응 → 적재 실행 → 이관 이력의 6단 본문 와이어프레임
- **source_hash**: 04a7ebd00041c9d9f51ea193f08736c87e4678f16678b0dd52de2b389db74b9e
- **generated_at**: 2026-08-20T16:33:40.316Z
- **generated_by**: generate-wireframes.py

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-035
- UC-036

## covered_by_acceptances

- AC-041
- AC-042
- AC-043
- AC-044
- AC-045
- AC-046
- AC-047
