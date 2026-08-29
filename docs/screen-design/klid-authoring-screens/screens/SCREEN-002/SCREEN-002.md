---
logicraft_item: SCREEN-002
type: screen_spec
version: 26
last_updated_at: 2026-08-28T22:18:45.961Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-29T01:19:52.330Z
sync_session: 36
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-002.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-007]]"]
---

# 관리자 등록 화면

## route

/role-claim

## title

관리자 등록 화면

## device

desktop

## status

draft

## purpose

관리자가 한 명도 없는 시스템에서, 인증은 되었으나 역할이 비어 있는 사용자가 배포 시 설정된 공유 패스워드로 본인을 최초 관리자로 등록하는 부트스트랩 화면. 부여 역할은 관리자 고정이며 화면에 역할 선택지를 두지 않는다 — 서버가 요청의 역할 값을 읽지 않으므로 선택지를 두면 고른 값과 실제 부여 역할이 갈린다. 관리자가 한 명이라도 생기면 이 창구는 닫히고, 이후 역할 부여는 관리자가 사용자 관리 화면에서 한다. 접근: 인증됨.

## sections

### 안내 헤더

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 관리자 등록

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Custom
- **label**: 이 시스템에는 아직 관리자가 없습니다. 배포 시 설정한 공유 패스워드로 본인을 최초 관리자로 등록하세요.

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: SubText

- **description**: 인증은 되었으나 역할이 비어 있는 사용자에게 최초 관리자 등록이 필요함을 안내. 제목 '관리자 등록' + 보조 설명(이 시스템에는 아직 관리자가 없습니다. 배포 시 설정한 공유 패스워드로 본인을 최초 관리자로 등록하세요.)

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 역할 선택

- **role**: main
- **layout**: form

**components**:

#### [1]

- **note**: 등록을 마치면 이 창구는 닫힙니다. 이후 다른 사용자의 역할은 관리자가 사용자 관리 화면에서 지정합니다. 강조 수준은 경고가 아니라 정보다 — 막는 것이 아니라 알리는 자리다.
- **type**: Alert
- **label**: 관리자 권한이 부여됩니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 부여될 권한을 알리는 안내 배너. 역할을 고르는 자리를 두지 않는다 — 부여 역할이 관리자 고정이고 서버가 요청의 역할 값을 읽지 않으므로, 선택지를 두면 사용자가 고른 값과 실제 부여 역할이 갈려 화면이 거짓을 말한다. 대신 무슨 일이 일어나는지를 명시한다.

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
- **placeholder**: 배포 시 설정한 공유 패스워드를 입력하세요

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
- **label**: 관리자로 등록

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-007

- **description**: 관리자 공유 패스워드 입력(type=password, autoComplete=new-password) + 제출. 패스워드가 비어 있거나 진행 중이면 제출 비활성. 제출 시 POST /v1/auth/role-claim 호출 — role·adminPassword 외에, 상위 시스템(관제서버)이 세션 인계 과정에서 함께 전달한 표시용 사용자 정보(사용자ID·사용자명, 있는 경우만)를 함께 전송한다 — 서버는 이 정보를 이용해 사용자 마스터를 자동등록한다. 성공 시 새 accessToken 으로 토큰 교체 후 /dashboard 로 이동. 실패 시 status 별 에러 메시지를 alert 로 표시: 401=패스워드 불일치, 403=자가 부여할 수 없는 역할(이 창구가 싣는 값은 관리자 고정이라 정상 동선에서는 도달하지 않는다), 409=이미 관리자가 있어 이 창구가 닫힘, 429=시도 초과. 400 은 전용 문구를 두지 않고 서버가 내려준 메시지를 그대로 싣는 갈래에 속한다 — 자가 부여 대상이 아닌 역할이나 허용 범위를 벗어난 패스워드 길이처럼 화면이 제출 전에 걸러내는 값이라 정상 동선에서는 도달하지 않고, 상태코드만으로는 사유를 가를 수 없어 고정 문구로 덮으면 실제 원인이 가려진다. 그 밖의 실패도 같은 갈래이며 서버 메시지가 없을 때만 일반 오류 문구를 쓴다. 이 화면이 요구하는 패스워드는 관리 기능 진입에 쓰이는 것과 같은 관리자 패스워드다 — 그 값이 운영 중에 교체되면 이 화면에서도 교체된 값을 입력해야 한다.

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

### module_paths

_(empty)_

## required_roles

_(empty)_

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-002/main.html
- **label**: 관리자 등록 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **source_hash**: f4200e88f9ea5c5c0e3e94d7d3d88bcff08a21244898915141fa0f04afe16128
- **generated_at**: 2026-08-28T22:18:45.960Z
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
