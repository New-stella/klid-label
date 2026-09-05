---
logicraft_item: SCREEN-041
type: screen_spec
version: 10
last_updated_at: 2026-08-28T22:11:36.148Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T02:34:48.377Z
sync_session: 35
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-041.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-223]]", "[[API-194]]"]
  required_roles: ["[[ROLE-004]]"]
---

# 관리자 패스워드 교체

## route

/admin/password

## title

관리자 패스워드 교체

## device

desktop

## status

draft

## purpose

관리자 공유 패스워드를 운영 중에 바꾸는 화면. 교체가 성립하려면 셋이 함께 갖춰져야 한다 — 관리자 역할, 유효한 관리자 유효창, 그리고 현재 패스워드 재확인이다. 유효창만으로 바꿀 수 있으면 잠깐 열린 창을 가로챈 사람이 자격 자체를 갈아 치워 정당한 운영자를 잠글 수 있으므로 현재 값을 한 번 더 받는다. 교체가 성공하면 그 전에 발급된 유효창이 모두 무효가 되며 방금 쓰던 자기 것도 함께 끊기므로, 이어서 관리 기능을 쓰려면 새 패스워드로 다시 진입해야 한다. 관리자 페이지에 속해 관리자 패스워드 확인을 거쳐야 도달한다. 접근: 관리자.

## sections

### 교체 전 안내

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 관리자 패스워드 교체

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 셋이 함께 성립해야 한다. 유효창은 인가를 대체하지 않고 더해지므로 관리자 역할이 그대로 필요하며, 유효창이 열려 있어도 현재 패스워드를 다시 받는다.
- **type**: List
- **label**: 교체 요건

**columns**:

_(empty)_

**options**:

- 관리자 역할
- 유효한 관리자 유효창
- 현재 패스워드 재확인

#### [3]

- **note**: 지금 이 화면을 열어 준 유효창도 함께 무효가 된다. 오류가 아니라 정상 동작이므로 누르기 전에 미리 알리며, 이어서 관리 기능을 쓰려면 새 패스워드로 다시 진입해야 한다.
- **type**: Alert
- **label**: 교체하면 열려 있는 유효창이 모두 끊긴다

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [4]

- **note**: 현재 패스워드도 새 패스워드도 화면에 다시 나타나지 않고 기록에도 남지 않는다. 실패 안내에도 값을 싣지 않는다.
- **type**: Text
- **label**: 입력한 값은 어디에도 남지 않는다

**columns**:

_(empty)_

**options**:

_(empty)_

**description**:

무엇이 갖춰져야 바꿀 수 있는지, 바꾸고 나면 무엇이 끊기는지를 폼보다 먼저 알리는 자리다.

요건을 셋으로 둔 이유를 함께 적는다. 유효창은 인가를 대체하지 않고 더해지므로 관리자 역할은 그대로 필요하고, 유효창이 열려 있더라도 현재 패스워드를 한 번 더 받는다. 그렇게 하지 않으면 잠깐 열린 창을 가로챈 사람이 자격 자체를 갈아 치워 정당한 운영자를 잠글 수 있다.

교체가 성공하면 그때까지 열려 있던 유효창이 모두 끊긴다는 사실을 누르기 전에 알린다. 지금 이 화면을 열어 준 유효창도 함께 끊기며, 이것은 정상 동작이므로 나중에 오류처럼 보이게 하지 않는다.

입력한 값이 어디에도 남지 않는다는 것도 여기서 밝힌다. 화면에 다시 나타나지 않고 기록에도 남지 않으며 실패 안내에도 실리지 않는다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 패스워드 교체 폼

- **role**: main
- **layout**: form

**components**:

#### [1]

- **note**: 유효창이 열려 있더라도 이 값을 다시 대조한다. 유효창 탈취가 곧 자격 완전 탈취가 되지 않게 하는 자리다.
- **type**: Input
- **label**: 현재 패스워드

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **validation**: 필수 · 4~100자 · 가림 입력
- **placeholder**: 현재 관리자 패스워드
- **type_length**: varchar(100)

#### [2]

- **note**: 현재 값과 같으면 거부한다. 바뀌지도 않았는데 열려 있던 유효창만 전부 끊기는 일을 막는다.
- **type**: Input
- **label**: 새 패스워드

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **validation**: 필수 · 8~100자 · 현재 값과 같으면 거부 · 가림 입력
- **placeholder**: 새 관리자 패스워드
- **type_length**: varchar(100)

#### [3]

- **note**: 두 칸이 어긋나면 제출되지 않는다. 잘못 친 값으로 바뀌면 아무도 다시 진입하지 못하므로 보내기 전에 화면에서 막는다.
- **type**: Input
- **label**: 새 패스워드 확인

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **validation**: 필수 · 새 패스워드와 일치
- **placeholder**: 새 관리자 패스워드 다시 입력
- **type_length**: varchar(100)

#### [4]

- **note**: 세 칸이 모두 채워지고 새 값 두 칸이 일치할 때만 눌린다. 누르기 전에 열려 있는 유효창이 모두 끊긴다는 것을 다시 확인받는다.
- **type**: Button
- **label**: 패스워드 교체
- **state**: disabled

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-223

#### [5]

- **note**: 입력한 값을 모두 비우고 되돌아간다. 비운 값은 화면에도 기록에도 남지 않는다.
- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [6]

- **note**: 상태코드와 오류코드로는 권한이 없는 것과 유효창이 끝난 것이 갈리지 않는다. 다만 문구는 다르다 — 유효창이 없거나 끝난 경우에는 다시 열 자리를 안내한다. 유효창 거부의 문구는 사유가 무엇이든 하나로 같다. 현재 패스워드가 맞지 않는 경우, 새 값이 규칙에 맞지 않거나 현재 값과 같은 경우, 시도가 너무 잦은 경우를 안내하되 어느 경우에도 입력한 값을 싣지 않는다.
- **type**: Alert
- **label**: 교체 실패 안내
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [7]

- **note**: 유효창이 없거나 이미 끝난 상태에서 제출하면 관리자 패스워드를 다시 확인해 유효창을 연다. 이 확인이 여는 것은 역할 승격이 아니라 그 사람에게 잠깐 열리는 창이며, 관리자 역할은 그대로 필요하다.
- **type**: Dialog
- **label**: 관리자 확인
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-194

**description**:

현재 패스워드와 새 패스워드를 받아 교체를 제출하는 자리다.

세 칸 모두 가림 입력이며 입력값이 화면에 드러나지 않는다. 새 값은 두 번 받아 서로 어긋나면 제출되지 않게 한다 — 잘못 친 값으로 바뀌면 아무도 다시 진입하지 못한다.

새 값이 현재 값과 같으면 거부한다. 바뀌지도 않았는데 열려 있던 유효창만 전부 끊기는 일을 막기 위해서다.

실패는 상태코드와 오류코드로는 두 사유를 가르지 않는다 — 권한이 없는 것과 유효창이 끝난 것이 같은 코드로 돌아온다. 다만 안내 문구는 다르다. 유효창이 없거나 끝난 경우에는 다시 유효창을 열 자리를 안내한다 — 그러지 않으면 막힌 사람이 스스로 돌아올 길이 없다. 유효창 거부의 문구는 실패 사유가 무엇이든 하나로 같다. 없음·끝남·위조·남의 것을 문구로 갈라 놓으면 그 응답이 유효창 상태를 알려주는 창이 된다. 두 요청을 견주면 자기 역할 상태를 짐작할 수 있다는 것은 인지하고 받아들인 대가다 — 짐작되는 것은 자기 자신의 역할이고, 그 대가로 얻는 것은 막힌 사람이 스스로 회복할 길이다. 어떤 실패 안내에도 입력한 값을 싣지 않는다.

유효창이 없거나 이미 끝난 상태에서 제출하면 관리자 확인을 다시 받아 유효창을 연 뒤 이어서 진행한다. 그 확인이 여는 것은 역할 승격이 아니라 그 사람에게 잠깐 열리는 창이다.

**references_apis**:

- API-223
- API-194

**references_features**:

_(empty)_

### 교체 후 안내

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: 성공을 알리는 자리다. 열려 있던 유효창이 모두 끊긴 것은 이 교체가 의도한 결과이므로 오류처럼 보이게 하지 않는다. 바뀐 값을 다시 보여 주지 않는다.
- **type**: Alert
- **label**: 패스워드를 교체했다
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [2]

- **note**: 관리 기능의 쓰기는 새 패스워드로 관리자 확인을 다시 거쳐야 열린다. 조회는 관리자 역할만으로 계속 되므로 화면이 통째로 잠긴 것처럼 안내하지 않는다.
- **type**: Text
- **label**: 이어서 쓰려면 새 패스워드로 다시 진입해야 한다

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 새 패스워드로 유효창을 다시 연다. 여기서 실패하면 새 값이 잘못 저장된 것이 아니라 입력이 틀린 것이므로 같은 자리에서 다시 시도한다.
- **type**: Button
- **label**: 관리자 확인 다시 하기
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-194

**description**:

교체가 성공한 뒤에만 보이는 자리다.

성공을 알리면서 열려 있던 유효창이 모두 끊긴 것을 함께 알린다. 그것은 이 교체가 의도한 결과이므로 실패나 세션 오류처럼 보이게 하지 않는다 — 오류로 보이면 사람이 방금 바꾼 값을 의심해 되돌리려 든다.

다음에 할 일을 분명히 한다. 관리 기능의 쓰기를 이어서 하려면 새 패스워드로 관리자 확인을 다시 거쳐야 한다. 조회는 관리자 역할만으로 계속 되므로 화면이 통째로 잠긴 것처럼 안내하지 않는다.

여기에도 바뀐 값을 다시 보여 주지 않는다.

**references_apis**:

- API-194

**references_features**:

_(empty)_

## brownfield

### status

new

### decided_by

ADR-046

### change_kind

- capability-add

### diff_summary

관리자 패스워드가 배포 설정에 고정돼 운영 중 교체가 불가능하던 것을 화면에서 바꿀 수 있게 한다.

## surface_kind

web

## consumes_apis

- API-223
- API-194

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-155

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-27T20:57:00.605Z

### module_paths

_(empty)_

## required_roles

- ROLE-004

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-041/main.html
- **label**: 관리자 패스워드 교체 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **source_hash**: eadea89e835e3ef316e2da2f43464e6e6978468b3b393931cf01cac4a146a3b4
- **generated_at**: 2026-08-28T22:11:36.148Z
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
