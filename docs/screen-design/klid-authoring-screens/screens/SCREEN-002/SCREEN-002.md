---
logicraft_item: SCREEN-002
type: screen_spec
version: 17
last_updated_at: 2026-08-25T01:21:32.912Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-25T10:22:54.864Z
sync_session: 24
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-002.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-007]]"]
---

# 역할 클레임 화면

## route

/role-claim

## title

역할 클레임 화면

## device

desktop

## status

draft

## purpose

JWT role·channel 클레임으로 권한을 확정·전환하는 화면. 접근: 인증됨.

## sections

### 안내 헤더

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 권한 부여 필요

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Custom
- **label**: 검수자에게 받은 패스워드로 역할을 부여받으세요.

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: SubText

- **description**: 인증은 되었으나 role 클레임이 비어 있는 사용자에게 권한 부여가 필요함을 안내. 제목 '권한 부여 필요' + 보조 설명(검수자에게 받은 패스워드로 역할을 부여받으세요).

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 역할 선택

- **role**: main
- **layout**: form

**components**:

#### [1]

- **note**: 화면에 보이는 문구는 한글 호칭뿐이다. 라디오가 서버로 보내는 값은 각각 WORKER · REVIEWER 이며 그 값은 바뀌지 않는다.
- **type**: RadioGroup
- **label**: 역할 선택

**columns**:

_(empty)_

**options**:

- 작업자
- 검수자

- **binds_to**: role

- **description**: 부여받을 역할을 라디오로 선택. 작업자 / 검수자 2택 — 화면에는 한글 호칭만 보이고 전송값은 각각 WORKER · REVIEWER 다. role state 미선택 시 제출 버튼 비활성. mutation 진행 중에는 비활성.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 권한 부여 폼

- **role**: main
- **layout**: form

**components**:

#### [1]

- **note**: type=password, autoComplete=new-password
- **type**: Input
- **label**: 관리자 패스워드

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: adminPassword
- **placeholder**: 검수자에게 받은 패스워드를 입력하세요

#### [2]

- **note**: 실패 시에만 role=alert 로 노출
- **type**: Alert
- **label**: 에러 메시지
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [3]

- **type**: Button
- **label**: 권한 부여 확인

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-007

- **description**: 관리자 공유 패스워드 입력(type=password, autoComplete=new-password) + 제출. 역할 미선택 또는 패스워드 공백 또는 진행 중이면 제출 비활성. 제출 시 POST /v1/auth/role-claim 호출 — role·adminPassword 외에, 상위 시스템(관제서버)이 세션 인계 과정에서 함께 전달한 표시용 사용자 정보(사용자ID·사용자명, 있는 경우만)를 함께 전송한다 — 서버는 이 정보를 이용해 사용자 마스터를 자동등록한다. 성공 시 새 accessToken 으로 토큰 교체 후 /dashboard 로 이동. 실패 시 status 별 에러 메시지를 alert 로 표시: 401=패스워드 불일치, 403=해당 역할은 자가 부여할 수 없음(검수자에게 권한 부여를 요청하라는 안내 — 현재 화면은 WORKER/REVIEWER 2종만 노출해 정상 동선에서는 도달하지 않으나 서버 측 허용 역할 정책이 바뀌면 도달 가능), 409=이미 부여됨, 429=시도 초과, 그 외=일반 오류.

**references_apis**:

- API-007

**references_features**:

_(empty)_

## brownfield

### status

new

### decided_by

ADR-012

### change_kind

- redesign

### diff_summary

2차 role+channel 클레임 분기 화면 — 1차 GPKI 인증서 기반 로그인에는 없던 2차 전용 자가부여 온보딩 화면

## surface_kind

web

## consumes_apis

- API-007

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-105

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-25T01:21:32.912Z

## required_roles

_(empty)_

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-002/main.html
- **label**: 역할 클레임 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **source_hash**: d3a0988c15ad3a19bb1688a09442a1bde8a94f5f019beb5a51f3389e537a5992
- **generated_at**: 2026-08-16T12:43:57.924Z
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
