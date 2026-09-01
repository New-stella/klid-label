---
logicraft_item: SCREEN-040
type: screen_spec
version: 9
last_updated_at: 2026-08-28T22:11:35.831Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-01T08:09:59.489Z
sync_session: 36
stale: true
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-040.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-194]]"]
  required_roles: ["[[ROLE-004]]"]
---

# 관리자 페이지 진입 화면

## route

/admin

## title

관리자 페이지 진입 화면

## device

desktop

## status

draft

## purpose

관리 기능에 들어가기 전에 관리자 패스워드를 확인하는 게이트 화면. 이 화면은 확인만 맡고 관리 기능 자체를 담지 않는다. 유효창이 없거나 끝난 상태로 관리 화면을 열려고 하면 그 화면 대신 이 화면이 뜨고, 확인을 통과하면 원래 가려던 화면으로 되돌려 보낸다. 패스워드가 여는 것은 확인한 사람에게 잠깐 열리는 유효창이며 역할을 승격시키지 않는다 — 관리자 역할은 그대로 필요하고 유효창은 인가를 대체하지 않고 가산된다. 접근: 관리자.

## sections

### 진입 안내

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 관리자 페이지 진입

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 확인으로 열리는 것은 잠깐 열리는 유효창이며 역할이 바뀌는 것이 아니다. 관리자 역할은 그대로 필요하다.
- **type**: Text
- **label**: 관리 기능을 이용하려면 관리자 패스워드 확인이 필요합니다.

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 돌아갈 화면은 이 화면으로 오기 직전에 열려던 관리 화면이다. 그것을 알 수 없을 때만 관리자 페이지의 기본 화면으로 보낸다.
- **type**: Text
- **label**: 확인이 끝나면 원래 이용하려던 화면으로 돌아갑니다.

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 관리자 패스워드를 확인해 관리 기능 진입 유효창을 여는 자리임을 안내한다. 이 화면은 확인만 맡고 관리 기능을 담지 않는다 — 사용자 관리·연동 서버 주소 설정·영상 업로드·관리자 패스워드 교체·위험 액션 같은 화면이 그 기능을 담으며, 이 열거는 예시이지 전수 목록이 아니다. 유효창이 아직 없거나 끝난 상태로 그런 화면을 열려고 하면 그 화면 대신 이 화면이 뜨고, 확인을 통과하면 원래 가려던 화면으로 되돌려 보낸다. 되돌아갈 화면을 알 수 없을 때만 관리자 페이지의 기본 화면으로 보낸다. 유효창은 확인한 사람에게 묶이며 역할을 승격시키지 않는다 — 관리자 역할은 그대로 필요하고 유효창은 인가를 대체하지 않고 가산된다. 조회만 하는 경로는 이 확인을 요구하지 않는다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 관리자 패스워드 확인

- **role**: main
- **layout**: form

**components**:

#### [1]

- **note**: 가려진 입력으로 받고 자동완성 저장을 쓰지 않는다. 입력한 값은 화면에 다시 나타나지 않고 요청 후 지우며 어떤 기록에도 남기지 않는다.
- **type**: Input
- **label**: 관리자 패스워드

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **binds_to**: adminPassword
- **validation**: 필수 · 4~100자
- **placeholder**: 관리자 패스워드를 입력하세요
- **type_length**: varchar(100)

#### [2]

- **note**: 패스워드가 비어 있거나 허용 길이를 벗어나면 비활성. 요청이 진행되는 동안에도 비활성으로 두어 중복 제출을 막는다.
- **type**: Button
- **label**: 확인

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-194

#### [3]

- **note**: 진입을 그만두고 직전 업무 화면으로 돌아간다. 유효창은 열리지 않는다.
- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 관리자 패스워드를 입력받아 유효창 발급을 요청한다. 입력이 비어 있거나 허용 길이를 벗어나면 확인 버튼을 비활성으로 두고, 요청이 진행되는 동안에도 비활성으로 두어 같은 요청이 겹치지 않게 한다. 입력한 패스워드는 화면에도 기록에도 남지 않는다 — 가려진 입력으로 받고, 다시 표시하지 않으며, 요청을 보낸 뒤 화면에서 지운다. 확인에 성공하면 서버가 유효창을 열고 만료 시각을 함께 알려준다. 유효 여부 판정은 서버가 소유하므로 화면이 스스로 아직 유효하다고 정하지 않는다. 취소하면 진입을 그만두고 직전 업무 화면으로 돌아간다.

**references_apis**:

- API-194

**references_features**:

_(empty)_

### 확인 결과 안내

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: 실패는 사유를 구분해 알리지 않는다. 패스워드 불일치·권한 없음·시도 초과를 하나의 문구로 덮어, 응답이 상태를 알려주는 신호가 되지 않게 한다.
- **type**: Alert
- **label**: 확인하지 못했습니다. 입력한 내용을 다시 확인해 주세요.
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

- **variant**: destructive

#### [2]

- **note**: 시도가 제한된다는 사실만 알리고 남은 횟수·제한 기준·해제 시각은 알리지 않는다.
- **type**: Alert
- **label**: 시도가 잦습니다. 잠시 후 다시 시도해 주세요.
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

- **variant**: default

#### [3]

- **note**: 확인을 통과한 뒤 유효창이 언제까지 열려 있는지 남은 시간으로 보여준다. 서버가 알려준 만료 시각을 기준으로 계산하며 화면이 유효 여부를 스스로 정하지 않는다.
- **type**: Text
- **label**: 남은 유효 시간

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

- **binds_to**: expiresAt

#### [4]

- **note**: 유효창이 끝난 뒤 관리 기능을 다시 쓰려 할 때 뜬다. 만료를 거부 코드로만 알리지 않고 재확인 통로를 같은 화면에서 연다.
- **type**: Alert
- **label**: 유효 시간이 끝났습니다. 계속하려면 관리자 패스워드를 다시 확인해 주세요.
- **state**: hidden

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

- **variant**: default

- **description**: 확인 결과와 유효창 상태를 사람이 읽을 수 있게 알린다. 실패는 사유를 구분해 알리지 않는다 — 패스워드 불일치·권한 없음·시도 초과를 가려 주면 그 응답이 공격자에게 상태를 알려주는 신호가 되기 때문이다. 따라서 실패 문구는 하나로 두고, 시도가 잦을 때만 제한된다는 사실을 덧붙여 알린다. 남은 시도 횟수나 제한 기준은 알리지 않는다. 확인에 성공하면 유효창이 언제까지 열려 있는지 남은 시간으로 보여준다 — 만료를 거부 응답으로만 알리고 끝내지 않는다. 유효창이 끝난 뒤 관리 기능을 다시 쓰려 하면 이 화면이 그 자리를 맡아 재확인을 받는다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

## brownfield

### notes

2026-08-27 — 관리 기능을 별도 관리자 주소로 분리하고 진입에 관리자 패스워드 확인을 두기로 한 결정에 따라 신설한다.
- 이 화면은 게이트 전용이다. 확인만 맡고 관리 기능을 담지 않는다.
- 유효창이 없거나 끝난 상태로 관리 화면에 들어가려 하면 이 화면이 그 자리를 맡고, 통과하면 원래 가려던 화면으로 되돌려 보낸다.
- 새 역할을 만들지 않는다. 유효창은 인가를 대체하지 않고 가산되며 검수자 권한은 그대로 필요하다.
- 실패는 사유를 구분해 알리지 않는다. 시도가 잦으면 제한된다는 사실만 덧붙인다.
- 남은 유효 시간을 사람이 읽을 수 있게 보여준다. 만료를 거부 응답으로만 알리고 끝내지 않는다.

### status

new

### decided_by

ADR-046

### change_kind

- capability-add

### diff_summary

관리 기능을 별도 관리자 주소로 분리하면서 그 진입을 지키는 게이트 화면을 신설했다. 1차에는 대응 화면이 없다.

## surface_kind

web

## consumes_apis

- API-194

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-154

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-27T20:56:57.088Z

### module_paths

_(empty)_

## required_roles

- ROLE-004

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-040/main.html
- **label**: 관리자 페이지 진입 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **description**: 
- **source_hash**: 2eb4c06dccc590353d1c62836fffc86288be9b797135c5fb4de06e3cb0759c04
- **generated_at**: 2026-08-28T22:11:35.830Z
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
