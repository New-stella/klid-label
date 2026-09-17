---
logicraft_item: SCREEN-024
type: screen_spec
version: 39
domain: DOMAIN-001
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-17T01:12:22.961Z
status: CHANGED
prev_version: 35
content_hash: 4661625c88087438231fc1227bc19206b596a261131c82be2151e1d6bd4ef65d
stale: false
raw: ./_raw/SCREEN-024.json
links:
  based_on: ["[[ADR-046]]"]
  belongs_to_domain: ["[[DOMAIN-001]]"]
  consumes: ["[[API-001]]", "[[API-004]]", "[[API-194]]"]
  implements: ["[[IMPREC-002]]", "[[IMPREC-162]]", "[[IMPREC-176]]", "[[IMPREC-411]]"]
  migrated_from: ["[[LEGACY-125]]"]
  realizes: ["[[UC-030]]"]
  references: ["[[API-001]]", "[[API-004]]", "[[API-194]]"]
  requires: ["[[ROLE-004]]"]
  applies_to_backward: ["[[SHELL-001]]"]
  designs_backward: ["[[SD-009]]"]
  granted_on_backward: ["[[ROLE-004]]"]
  navigates_to_backward: ["[[NAV-001]]"]
  realizes_backward: ["[[MOD-001]]", "[[MOD-024]]", "[[MOD-025]]", "[[MOD-030]]"]
  references_backward: ["[[ADR-046]]", "[[UC-030]]"]
---

# 사용자 관리 화면

## route

/admin/users

## title

사용자 관리 화면

## device

desktop

## status

draft

## purpose

관리자가 사용자 목록을 조회·검색·필터링하고 표시 이름과 역할을 수정하는 관리 화면. 관리자 페이지에 속해 관리자 패스워드 확인을 거쳐야 도달한다. 이 화면은 관리자 역할을 요구한다 — 조회도 검수자 권한만으로는 되지 않는다. 이 화면의 저장에는 관리자 역할에 더해 유효한 관리자 단기 유효창이 함께 필요하다 — 역할을 바꾸는 저장도, 표시 이름만 고치는 저장도 같다. 유효창은 역할을 대체하지 않고 가산되며 역할을 승격시키지 않는다. 지정할 수 있는 역할은 관리자·검수자·작업자·포털이고, 마지막 관리자를 다른 역할로 내리는 것은 거부된다. 계정 활성 여부는 외부 시스템(관제서버) 소유값이라 읽기 전용 배지로만 표시한다. 역할이 아직 배정되지 않은 자동등록 사용자는 '미배정'으로 표시되며, 역할을 선택해야 역할이 저장된다. 접근: 관리자. 표시 이름은 수정 모달에서 고칠 수 있으며 보내지 않으면 바뀌지 않는다. 고친 이름은 저장된 이름을 읽는 자리 — 사용자 목록·작업 배정·검수 화면·이력의 작성자 표시 — 에 반영된다. 다만 본인 화면 상단에 보이는 이름은 인계 토큰의 이름이 앞서므로 바뀌지 않는다. 설계된 동작이다.

## sections

### 페이지 헤더

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 사용자 관리

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Text
- **label**: 시스템 사용자 계정을 관리합니다.

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 제목 '사용자 관리' + 고정 부제. 부제는 정적 텍스트이며 전체 사용자 수 등 동적 수치는 표시하지 않는다. 이 화면은 관리자 페이지에 속하며, 관리자 패스워드 확인을 거쳐야 도달한다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 검색·역할 필터

- **role**: filter
- **layout**: form

**components**:

#### [1]

- **type**: Input
- **label**: 검색

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 필수 아님. 길이 제한 없음. 이름 또는 로그인ID 부분일치로 서버에서 검색한다.
- **placeholder**: 이름 / 아이디를 입력하세요.
- **triggers_api**: API-001

#### [2]

- **note**: 검색 확정과 함께 서버 파라미터로 전달되는 서버 사이드 필터 — 클라이언트에서 현재 페이지만 거르지 않는다.
- **type**: Select
- **label**: 역할

**columns**:

_(empty)_

**options**:

- 전체 역할
- 관리자
- 검수자
- 작업자
- 포털

- **triggers_api**: API-001

#### [3]

- **note**: 검색어 또는 역할 필터 중 하나라도 활성일 때만 눌림 가능.
- **type**: Button
- **label**: 필터 초기화

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [4]

- **note**: 검색어 확정 트리거. 입력 즉시 조회하지 않고 Enter 또는 이 버튼으로 확정한다.
- **type**: Button
- **label**: 검색

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 검색 입력(이름/로그인ID 부분일치)과 역할 select(전체/관리자/검수자/작업자/포털) 모두 서버 사이드 필터다. 입력값은 Enter 또는 검색 실행으로 확정되어야 조회에 반영되며(셀렉트를 바꾸는 것만으로 즉시 재조회되지 않음), 확정 시 1페이지로 초기화된다. 목록 필터는 검색어·역할 두 축뿐이다.

**references_apis**:

- API-001

**references_features**:

_(empty)_

### 사용자 목록 테이블

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Table
- **label**: 사용자 목록

**columns**:

- 이름
- 로그인ID
- 역할
- 상태
- 등록일
- 최신 로그인
- 관리

**options**:

_(empty)_

- **triggers_api**: API-001

#### [2]

- **note**: 행별 수정 모달 오픈. 이 화면에 존재하는 유일한 행 조작이다.
- **type**: Button
- **label**: 수정

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [3]

- **type**: Custom
- **label**: 페이지네이션

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Pagination

#### [4]

- **type**: Alert
- **label**: 사용자 목록을 불러올 수 없습니다
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: DataTable. 컬럼: 이름(아바타 이니셜+이름)/로그인ID(계정의 로그인 식별자, 값이 없으면 '—' 표시)/역할(컬러 배지, 역할이 없으면 '미배정' 배지 — 관제 인계 키에 역할 클레임이 없는 자동등록 사용자)/상태(활성·비활성 배지, 읽기 전용)/등록일/최신 로그인/관리(수정 버튼). GET /v1/users 페이징 결과를 표시한다. 검색·역할 필터는 서버 사이드로 처리된다. 페이지 변경 시 URL page 갱신 → 재조회. 로드 실패 시 상단 ErrorState 배너. 빈 결과는 '조건에 맞는 사용자가 없습니다'. 이 화면은 관리자 역할을 요구한다. 목록·단건 조회 창구 자체는 검수자 권한으로도 응답하지만 — 작업 배정 흐름이 사용자 정보를 읽어야 하기 때문이다 — 이 화면에 들어오는 것은 관리자뿐이며, 조회에 관리자 단기 유효창까지는 요구하지 않는다.

**references_apis**:

- API-001

**references_features**:

_(empty)_

### 사용자 정보 수정 모달

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **type**: Dialog
- **label**: 사용자 정보 수정

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 화면이 입력 시점에 먼저 판정한다 — 빈 값·공백만·폭 초과를 저장을 누르기 전에 그 자리에서 알린다. 저장할 수 있는 폭은 저장된 이름이 정하므로 화면 사양에 숫자로 적지 않는다.
- **type**: Input
- **label**: 표시 이름

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 필수 아님 — 보내지 않으면 이름은 바뀌지 않는다. 앞뒤 공백과 보이지 않는 문자를 걷어낸 뒤 판정하며, 걷어내고 나면 빈 값이 되는 입력은 저장할 수 없다. 저장할 수 있는 폭을 넘으면 잘라서 저장하지 않고 거부한다 — 이름은 사람을 알아보는 값이라 조용히 잘리면 다른 사람으로 보인다.
- **placeholder**: 화면에 표시할 이름을 입력하세요.
- **triggers_api**: API-004

#### [3]

- **type**: Select
- **label**: 역할

**columns**:

_(empty)_

**options**:

- 관리자
- 검수자
- 작업자
- 포털

- **validation**: 저장하려면 반드시 선택해야 한다(초기값 없는 미배정 사용자는 선택 전까지 저장 불가). 허용값: 관리자(ADMIN)/검수자(REVIEWER)/작업자(WORKER)/포털(PORTAL_USER) — 서버가 화이트리스트로 재검증하며 그 외 값은 400으로 거부한다.

#### [4]

- **note**: 편집 불가. '계정 활성 여부는 이 화면에서 변경하지 않습니다' 안내 문구와 함께 표시된다.
- **type**: Custom
- **label**: 상태 (읽기 전용 배지)

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: user.active
- **custom_name**: StatusBadge

#### [5]

- **note**: 이 화면의 저장에 필요한 관리자 단기 유효창의 남은 시간. 유효창이 열려 있지 않으면 확인이 필요함을 표시한다.
- **type**: Custom
- **label**: 남은 유효 시간

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: AdminSessionCountdown

#### [6]

- **note**: 바꾼 값이 하나도 없으면 비활성화된다. 역할을 바꾸려면 값을 골라야 하고, 이름을 바꾸려면 걷어낸 뒤에도 값이 남아 있고 저장할 수 있는 폭 안이어야 한다. 유효한 관리자 단기 유효창이 없으면 저장이 거부되며, 화면은 관리자 패스워드 재확인을 요구한다.
- **type**: Button
- **label**: 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-004

#### [7]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [8]

- **note**: 저장이 유효창 만료로 거부됐을 때 표시한다. 거부를 조용히 삼키지 않고 만료 사실을 알린다.
- **type**: Alert
- **label**: 관리자 확인 시간이 만료되었습니다. 다시 확인해 주세요
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

#### [9]

- **note**: 유효창이 없거나 만료된 상태에서 저장을 시도하면 열린다. 입력값은 화면에 다시 표시하지 않으며, 확인에 성공하면 저장을 이어서 시도한다.
- **type**: Dialog
- **label**: 관리자 패스워드 재확인

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-194

- **description**: Modal. 행의 '수정' 클릭 시 오픈(테이블 행 데이터 기반, 별도 단건 조회 없음). 표시 이름과 역할을 수정할 수 있다. 역할을 저장하려면 반드시 값을 선택해야 하며, 대상 사용자가 '미배정'(역할 없음)이면 역할을 고르기 전까지 역할이 저장되지 않고 '아직 역할이 배정되지 않은 사용자입니다' 안내가 표시된다. 상태(활성/비활성)는 읽기 전용 배지로만 표시되며 이 화면에서 변경하지 않는다(계정 활성 여부는 외부 시스템 소유). 저장 시 바꾼 값만 PATCH /v1/users/{userNo} 로 전송된다 — 이름을 건드리지 않았으면 이름을, 역할을 그대로 두었으면 역할을 보내지 않는다. 같은 값을 다시 저장해도 아무것도 바뀌지 않는다. 이름만 바꾼 저장은 역할 축 판정에 걸리지 않는다. 둘 다 바꾸지 않았으면 모달만 닫는다. 성공 시 토스트 + 목록 캐시 무효화. 이 화면의 저장은 운영·관리 성격의 쓰기라 유효한 관리자 단기 유효창을 함께 요구한다 — 역할을 바꾸는 저장도, 표시 이름만 고치는 저장도 같다. 관리자 역할을 대체하지 않고 그 위에 가산된다. 모달에는 남은 유효 시간을 표시한다. 유효창이 없거나 유효기간이 끝난 뒤 저장을 시도하면 서버가 거부하며, 화면은 만료 사실을 안내하고 관리자 패스워드 재확인을 요구한다 — 재확인에 성공하면 저장을 이어서 시도한다. 재확인 입력값은 화면에 다시 표시하지 않는다. 유효 여부 판정은 서버가 소유하며 화면이 스스로 아직 유효하다고 정하지 않는다. 이름이 거부되면 화면이 자기 문구로 안내하며, 서버가 준 문구를 그대로 내보이거나 필드 경로·항목 순번 표기를 노출하지 않는다.

**references_apis**:

- API-004
- API-194

**references_features**:

_(empty)_

## brownfield

### notes

2026-08-27 — 관리 기능을 별도 진입 경로로 분리했다. 새 역할을 만들지 않고, 관리자 패스워드 확인으로 열리는 단기 유효창을 역할 변경에 가산한다. ⚠ 「새 역할을 만들지 않고」는 뒤집혔다 — ADR-055 가 관리자 역할(ADMIN)을 신설했다. 그 시점의 판단으로 남겨 두되 현재 사양이 아니다. 관리 기능을 별도 진입 경로로 빼고 그 진입에 관리자 패스워드를 요구하는 것은 이번 변경 대상이 아니며 그대로다.
- 라우트를 관리자 페이지로 옮겼다. 구 경로는 이 화면의 주소가 아니다.
- 화면과 창구를 갈랐다. 화면 진입은 관리자 역할을 요구하고, 목록·단건 조회 창구 자체는 검수자 권한으로도 응답한다 — 그 창구까지 막으면 작업 배정 흐름이 끊긴다.
- 유효창은 인가를 대체하지 않고 가산된다. 관리자 역할은 그대로 필요하며 유효창이 역할을 승격시키지 않는다.
- 유효창 만료 뒤 저장을 시도하면 만료 사실을 안내하고 재확인을 요구한다 — 거부만 하고 끝내지 않는다.
- 역할만 쓰기 가능하고 계정 활성 여부는 외부 시스템 소유값이라 읽기 전용이라는 기존 계약은 그대로다.

### status

modified

### decided_by

ADR-046

### change_kind

- auth-upgrade
- route-change

### diff_summary

1차 사용자·권한 관리. 2차에서 관리자 페이지로 옮기고 역할 변경에 관리자 단기 유효창을 가산 요건으로 더했다.

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-03-05-01

#### legacy_artifact_id

LEGACY-125

## surface_kind

web

## consumes_apis

- API-001
- API-004
- API-194

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

- MOD-030
- MOD-024
- MOD-025
- MOD-001

### records

- IMPREC-002
- IMPREC-162
- IMPREC-176
- IMPREC-411

### progress

100

### subtasks

#### [1]

- **done**: true
- **description**: 수정 모달에 표시 이름 입력칸을 역할 선택 위에 둔다

#### [2]

- **done**: true
- **description**: 바꾼 축만 전송 — 이름만/역할과 함께/같은 값이면 미전송(정규화 비교)

#### [3]

- **done**: true
- **description**: 빈 값·공백만·저장 폭 초과를 입력 시점에 화면이 먼저 막는다(maxLength 미사용 — 조용한 절단 방지)

#### [4]

- **done**: true
- **description**: 서버 400 문구·필드 경로를 노출하지 않고 화면 자기 문구로 안내한다(409 서버 문장 노출은 유지)

#### [5]

- **done**: true
- **description**: 미배정 사용자도 이름만 고쳐 저장 가능 — saveBlockedReason 단일 지점을 두 축으로 넓힘

#### [6]

- **done**: true
- **description**: 유효창은 창구 전체에 걸린다 — 이름만 고치는 저장에도 헤더가 실리고 안내 문구를 저장 축으로 넓힘

#### [7]

- **done**: true
- **description**: 상한 값은 displayNameRules 한 곳만 정의하고 화면·문구·시험이 참조한다

#### [8]

- **done**: true
- **description**: 「변경 없음」 안내가 역할·표시 이름 두 축을 모두 말한다 — 구 문구는 역할만 말해, 이름을 고치려는 사용자가 「다른 역할을 선택하라」는 엉뚱한 지시를 받았다(같은 모달 미배정 안내와 축이 어긋나 있었다)

#### [9]

- **done**: true
- **description**: 서버 400 거부 안내를 이름 입력 onChange 에서 해제한다 — 해제 지점이 저장·모달 개폐뿐이라 이미 고친 값 위에 거부 안내가 남아 있었다. 문구도 특정 칸을 지목하지 않는 축 중립으로 바꿨다(칸이 늘면 엉뚱한 칸을 가리키게 되므로)

### last_updated

2026-09-16T10:41:25.112Z

### module_paths

_(empty)_

## required_roles

- ROLE-004

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-024/main.html
- **label**: 사용자 관리 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **description**: 
- **source_hash**: 79868125477b3ebdb075a1d544f269a4e85b22318553d2c9dac5080d3b0b322b
- **generated_at**: 2026-08-28T22:11:33.786Z
- **generated_by**: generate-wireframes.py

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## uses_components

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-030

## covered_by_acceptances

_(empty)_
