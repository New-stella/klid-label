---
logicraft_item: SCREEN-038
type: screen_spec
version: 7
last_updated_at: 2026-08-17T22:48:30.851Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-24T14:23:30.481Z
sync_session: 22
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-038.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-185]]", "[[API-186]]"]
  required_roles: ["[[ROLE-001]]"]
---

# 이벤트유형 관리 화면

## route

/manage/event-types

## title

이벤트유형 관리 화면

## device

desktop

## status

draft

## purpose

검수자 전용(/manage/event-types). 관제 인입으로 자동 등록된 이벤트유형의 표시명과 수집여부를 정정한다. ★생성·삭제 기능을 두지 않는다 — 등록의 유일한 출처가 관제 인입이며, 화면에 없는 유형은 관제가 보낸 적이 없는 유형이다. 지우면 그 유형의 영상이 라벨을 잃는다. ★표시명은 서버가 4단(운영자 지정명 → 관제 수신명 → 카테고리명 → 유형코드)으로 해석해 내려주며 화면은 그 값을 그대로 표시한다 — 화면에서 폴백을 다시 계산하면 판정이 갈라져 학습데이터 산출물의 이벤트명과 조용히 어긋난다. ★표시명 칸에는 그 값이 어느 단계에서 온 것인지를 함께 표기한다 — 같은 이름이 여러 줄에 보이는 까닭이 값만으로는 드러나지 않기 때문이며, 표기 값은 서버 응답에 실려 온 것을 그대로 쓴다. ★표시명 지정은 필터 그룹을 가르는 조작이다 — 목록 필터의 이벤트유형 옵션은 표시명이 같은 유형코드들을 한 건으로 접어 보여주므로, 여기서 이름을 지정하면 그동안 한 건으로 보이던 그룹이 자동으로 쪼개진다. 접기는 영구 병합이 아니라 표시명이 같은 동안만 유지되는 상태다. 표시명을 비워 저장하면 지정이 해제되어 관제 수신명으로 되돌아간다. 접근: 검수자 — 화면 진입 시점과 서버 요청 시점 양쪽에서 역할을 이중으로 검사한다.

## sections

### 페이지 헤더

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 이벤트유형 관리

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Text
- **label**: 관제에서 인입된 이벤트유형의 표시명과 수집여부를 관리합니다. 유형은 인입 시 자동 등록되므로 직접 추가·삭제할 수 없습니다.

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 제목과 안내 문구. 안내 문구는 이 화면에 추가·삭제 버튼이 없는 이유를 먼저 알려, 운영자가 없는 기능을 찾지 않게 한다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 이벤트유형 목록 테이블

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Table
- **label**: 이벤트유형 목록

**columns**:

- 유형코드
- 표시명
- 관제 원본
- 카테고리
- 수집
- 관리

**options**:

_(empty)_

#### [2]

- **note**: 표시명 칸에 그 값이 어느 단계에서 온 것인지 함께 표기한다. 표기 값은 운영자 지정명·관제 수신 유형명·카테고리명·유형코드 가운데 하나이며, 서버 응답에 실려 온 값을 그대로 쓴다 — 화면이 원본 이름 필드들을 보고 폴백을 다시 판정하지 않는다.
- **type**: Custom
- **label**: 표시명 출처 표기

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: eventType.dsplNmSource
- **custom_name**: DisplayNameSourceChip

#### [3]

- **note**: 행 전체가 아니라 표시명 칸만 입력으로 바뀐다. 다른 행을 편집하면 이전 편집은 저장되지 않고 닫힌다.
- **type**: Input
- **label**: 표시명 인라인 편집

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: eventType.optrIndctNm
- **validation**: 최대 200자. 빈 문자열은 지정 해제를 뜻하며 허용한다

#### [4]

- **note**: 누르면 즉시 반대값으로 저장한다. 확인 단계를 두지 않는 이유는 되돌리기가 같은 버튼 한 번이기 때문이다.
- **type**: Button
- **label**: 수집여부 토글 (노출/숨김)

**columns**:

_(empty)_

**options**:

- 노출
- 숨김

- **variant**: secondary
- **triggers_api**: API-186

#### [5]

- **type**: Button
- **label**: 표시명 수정

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [6]

- **note**: 표시명을 비운 채 저장하면 지정이 해제되어 관제 수신명으로 되돌아간다 — 되돌리기 경로다. 성공 응답에 갱신된 표시명과 그 출처가 함께 실려 오므로, 목록을 다시 불러오지 않고 해당 행의 표시명과 출처를 그 값으로 갱신하고 성공 알림을 띄운다.
- **type**: Button
- **label**: 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-186

#### [7]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [8]

- **type**: Skeleton
- **label**: 목록 로딩

**columns**:

_(empty)_

**options**:

_(empty)_

#### [9]

- **type**: Alert
- **label**: 목록을 불러오지 못했습니다
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

#### [10]

- **note**: 성공은 '이벤트유형을 저장했습니다', 실패는 서버가 준 메시지를 그대로 보여주고 없으면 '저장에 실패했습니다'.
- **type**: Toast
- **label**: 저장 결과 알림

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 등록된 이벤트유형 전체를 한 화면에 표로 보여준다. 비수집이거나 제외 대분류에 속한 유형까지 포함한다 — 필터 드롭다운용 조회와 목적이 다르며, 숨긴 유형을 다시 켜려면 목록에 보여야 하기 때문이다. 유형코드는 고정값이라 수정 대상이 아니다. 관제 원본과 카테고리는 읽기 전용이며 표시명이 어디서 왔는지 설명하는 근거로 함께 보여준다(값이 없으면 '-'). 표시명 칸은 해석된 최종 표시명을 보여주고, 수정을 누르면 그 칸만 입력으로 바뀐다. 저장 요청에는 표시명과 수집여부만 싣는다 — 유형코드·대분류코드·카테고리코드·등록일시는 보내지 않는다. 표시명을 지정·해제하면 필터 옵션의 접기 결과가 달라지므로 관련 목록 화면의 옵션이 갱신된다. 표시명 칸에는 해석된 표시명과 함께 그 값이 어느 단계에서 왔는지를 표기한다 — 표기 값은 서버 응답에 실려 온 것을 그대로 쓰고 화면이 폴백을 다시 판정하지 않는다. 표시명을 저장하면 그 응답에 갱신된 표시명과 출처가 함께 실려 오므로 목록을 다시 불러오지 않고 해당 행만 그 값으로 갱신하며, 운영자 지정명을 지우면 출처가 그 아래 단계로 내려간 결과가 응답에 그대로 드러난다.

**references_apis**:

- API-185
- API-186

**references_features**:

_(empty)_

## brownfield

### status

new

### diff_summary

이벤트유형 표시명·수집여부 관리 화면. 관제가 이벤트 코드만 보내고 유형명을 싣지 않아 표시명이 카테고리명까지 내려가 같은 이름이 여러 건 보이던 문제의 운영 측 해소 통로다. v2 신규.

## surface_kind

web

## consumes_apis

- API-185
- API-186

## implementation

### status

implemented

### modules

- MOD-042
- MOD-046

### records

- IMPREC-025

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-17T22:48:30.851Z

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-038/main.html
- **label**: 이벤트유형 관리 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **description**: 
- **source_hash**: d7bd540e6ec68548a7a50ed180bbbe44bd537ee23294f9e81dc3b6755c83de82
- **generated_at**: 2026-08-17T11:15:22.369Z
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
