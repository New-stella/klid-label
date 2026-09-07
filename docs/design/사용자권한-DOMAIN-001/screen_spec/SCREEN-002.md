---
logicraft_item: SCREEN-002
type: screen_spec
version: 32
domain: DOMAIN-001
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-07T15:15:46.260Z
status: CHANGED
prev_version: 28
content_hash: 51af221dadb7a9be15d994aad722df5853af8b0129ee4c5849d3a2b091888e1d
stale: true
raw: ./_raw/SCREEN-002.json
links:
  based_on: ["[[ADR-012]]"]
  belongs_to_domain: ["[[DOMAIN-001]]"]
  consumes: ["[[API-007]]", "[[API-245]]"]
  implements: ["[[IMPREC-105]]"]
  references: ["[[API-007]]", "[[API-245]]"]
  designs_backward: ["[[SD-018]]"]
  realizes_backward: ["[[MOD-002]]", "[[MOD-054]]"]
  references_backward: ["[[API-245]]", "[[UC-041]]"]
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

역할이 아직 비어 있는 채로 진입한 내부 채널 사용자를 받아, 관리자 등록 창구가 열려 있는지에 따라 두 갈래로 안내하는 화면. 진입한 사용자는 아직 역할이 부여되지 않아 일반 기능에 접근할 수 없다. 화면은 진입 시점에 창구 개폐를 조회해(API-245) 열림·닫힘 두 모습 중 하나를 처음부터 보인다 — 아직 관리자가 한 명도 없으면 창구가 열려 있고, 이미 관리자가 있으면 닫혀 있다. 이 화면으로 보내는 판정 축은 「역할 없음」 하나가 아니라 「역할 없음」과 「창구 개폐」 둘이며, 창이 닫혀 있으면 관리자 등록 모습이 아니라 권한 요청 안내 모습을 본다. 창구가 열린 상태: 안내 헤더와 부여 권한 안내, 그리고 배포 시 설정된 공유 패스워드로 본인을 최초 관리자로 등록하는 부트스트랩 폼을 보인다. '이 시스템에는 아직 관리자가 없습니다'라는 안내는 이 상태에서만 나타난다. 부여 역할은 관리자 고정이며 화면에 역할 선택지를 두지 않는다 — 서버가 요청에 실린 역할 값을 읽기는 하지만 그 값이 부여 결과를 바꾸지 못하므로, 선택지를 두면 사용자가 고른 값과 실제 부여 역할이 갈려 화면이 거짓을 말한다. 등록에 성공하면 이 사용자가 최초 관리자가 되고 창구는 닫힌다. 창구가 닫힌 상태: 이미 관리자가 있어 최초 관리자 등록은 성립하지 않는다. 권한 요청 안내만 보이고 패스워드 입력칸과 등록 버튼을 두지 않으며, 아직 권한이 없는 계정에게 관리자에게 권한을 요청하라고 안내한다. 관리자가 한 명이라도 생기면 이 창구는 닫히고, 이후 역할 부여는 관리자가 사용자 관리 화면에서 한다. 개폐 조회는 순간의 상태를 알려 줄 뿐이다. 조회와 제출 사이에 다른 사람이 최초 관리자가 되면 창은 그 사이에 닫히고 제출은 창 닫힘으로 거절된다 — 그래서 열림으로 그린 뒤에도 제출이 창 닫힘으로 거절되는 갈래를 그대로 유지하며, 그때 화면은 권한 요청 안내로 전환한다. 사전 조회는 그 갈래를 없애는 수단이 아니라 첫 화면이 사실과 다른 안내를 하지 않게 하는 수단이다. 진입 시 개폐 조회가 실패하면 이 화면으로 떨어지지 않는다 — 인증이 유효하지 않으면(토큰 없음·만료·검증 실패) 상위 시스템 로그인으로 되돌아가고, 그 밖의 조회 장애는 오류 상태로 알린다. 어느 쪽도 관리자가 없다는 안내로 바꾸지 않는다. 즉 이 화면은 인증이 유효하고 역할이 아직 없는 사용자에게만 나타난다. 접근: 인증됨.

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

- **description**: 창구가 열린 상태에서만 보이는 머리말 — 진입 시점에 조회한 창구 개폐(API-245)가 열림일 때만 렌더하고, 닫힘이면 이 섹션 대신 권한 요청 안내를 보인다. 관리자가 한 명도 없는 동안에만 열리는 창구임을 알리고, 진입한 사용자에게 최초 관리자 등록이 필요함을 안내. 제목 '관리자 등록' + 보조 설명(이 시스템에는 아직 관리자가 없습니다. 배포 시 설정한 공유 패스워드로 본인을 최초 관리자로 등록하세요.) 이 보조 설명은 열림 상태에서만 나타나므로 관리자가 이미 있는 시스템에서는 표시되지 않는다.

**references_apis**:

- API-245

**references_features**:

_(empty)_

### 부여 권한 안내

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

- **description**: 창구가 열린 상태에서만 보이는, 부여될 권한을 알리는 안내 배너. 역할을 고르는 자리를 두지 않는다 — 부여 역할이 관리자 고정이라 서버가 요청에 실린 역할 값을 읽더라도 그 값이 부여 결과를 바꾸지 못하므로, 선택지를 두면 사용자가 고른 값과 실제 부여 역할이 갈려 화면이 거짓을 말한다. 대신 무슨 일이 일어나는지를 명시한다.

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

- **description**: 창구가 열린 상태에서만 보이는 폼. 관리자 공유 패스워드 입력(type=password, autoComplete=new-password) + 제출. 패스워드가 비어 있거나 진행 중이면 제출 비활성. 제출 시 POST /v1/auth/role-claim 호출 — role·adminPassword 외에, 상위 시스템(관제서버)이 세션 인계 과정에서 함께 전달한 표시용 사용자 정보(사용자ID·사용자명, 있는 경우만)를 함께 전송한다 — 서버는 이 정보를 이용해 사용자 마스터를 자동등록한다. 성공 시 새 accessToken 으로 토큰 교체 후 /dashboard 로 이동. 실패 시 status 별 에러 메시지를 alert 로 표시: 401=패스워드 불일치, 403=자가 부여할 수 없는 역할(이 창구가 싣는 값은 관리자 고정이라 정상 동선에서는 도달하지 않는다), 409=이미 관리자가 있어 이 창구가 닫힘(진입 시 조회가 열림이었더라도 조회와 제출 사이에 다른 사람이 최초 관리자가 되면 이 갈래로 온다 — 사전 조회가 이 갈래를 없애지 못하므로 그대로 유지하며, 이때 화면은 권한 요청 안내로 전환한다), 429=시도 초과. 400 은 전용 문구를 두지 않고 서버가 내려준 메시지를 그대로 싣는 갈래에 속한다 — 자가 부여 대상이 아닌 역할이나 허용 범위를 벗어난 패스워드 길이처럼 화면이 제출 전에 걸러내는 값이라 정상 동선에서는 도달하지 않고, 상태코드만으로는 사유를 가를 수 없어 고정 문구로 덮으면 실제 원인이 가려진다. 그 밖의 실패도 같은 갈래이며 서버 메시지가 없을 때만 일반 오류 문구를 쓴다. 이 화면이 요구하는 패스워드는 관리 기능 진입에 쓰이는 것과 같은 관리자 패스워드다 — 그 값이 운영 중에 교체되면 이 화면에서도 교체된 값을 입력해야 한다.

**references_apis**:

- API-007

**references_features**:

_(empty)_

### 권한 요청 안내

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 권한 요청 안내

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 아직 권한이 없는 계정입니다. 관리자에게 권한을 요청하세요. 권한이 부여되면 다시 진입할 수 있습니다. 강조 수준은 경고가 아니라 정보다 — 막는 것이 아니라 알리는 자리다.
- **type**: Alert
- **label**: 이미 관리자가 있어 최초 관리자 등록 창구가 닫혀 있습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 관리자 등록 창구가 닫힌 상태(이미 관리자가 있음)에서, 역할이 아직 비어 있는 사용자에게 보이는 안내. 진입 시점에 조회한 창구 개폐(API-245)가 닫힘이면 제출을 거치지 않고 처음부터 이 안내를 보인다. 패스워드 입력칸과 등록 버튼을 두지 않는다 — 최초 관리자 등록은 이미 성립하지 않아 제출해도 거절만 돌아오기 때문이다. 아직 권한이 없는 계정에게 관리자에게 권한을 요청하라고 알리고, 이후 역할 부여는 관리자가 사용자 관리 화면에서 한다는 점을 안내한다. 창구가 열린 상태에서 제출이 409(이미 관리자가 있음)로 돌아오면 화면은 이 안내로 전환한다.

**references_apis**:

- API-245

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
- API-245

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

- MOD-002
- MOD-054

### records

- IMPREC-105
- IMPREC-410

### progress

100

### subtasks

#### [1]

- **done**: true
- **description**: SessionIngressPage: 관제(INTERNAL) 진입 시 GET /v1/me 로 서버 role 확인 → role 있으면 /dashboard, role=null 이면 /role-claim (claims.role 토큰 대신 /me 신뢰, 실패 시 토큰 role 폴백)

#### [2]

- **done**: true
- **description**: useAuthStore.setServerRole 신설: /me 서버 role 을 claims.role 에 주입(토큰 원본·채널·exp 유지). 관제 토큰에 role 클레임이 없어 RoleGuard(claims.role 읽음)가 role 보유자를 /role-claim 으로 튀기던 결함 해소(인가 진실원=서버 LS_USER_ROLE, ADR-021)

#### [3]

- **done**: true
- **description**: RoleClaimPage(/role-claim): 창 열림 관리자 비밀번호 폼 → POST /v1/auth/role-claim {role:ADMIN,adminPassword}, 성공 시 새 토큰 교체 후 /dashboard

#### [4]

- **done**: true
- **description**: 창 닫힘(409) → 서버 사유 그대로 노출하는 안내 전환 (401/429/400 별 분기 포함)

#### [5]

- **done**: true
- **description**: 보안·a11y: 비밀번호 평문 미저장·미로그, dangerouslySetInnerHTML 미사용, label 연결·role=alert

#### [6]

- **done**: true
- **description**: vitest/RTL: role=null 라우팅·서버 role 주입·토큰 클레임 없어도 /me role 로 가드 통과·/me 실패 폴백 (SessionIngress 4 + RoleGuard 2 + store 3) + 폼 성공·409 전환 (RoleClaim 기존). auth+router+stores 271 green, tsc·lint 0, build ✓

### last_updated

2026-09-07T03:54:56.401Z

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

- **source_hash**: 1f0779d5c549fb033432986b900cc9951726d45a2990638259d47b200f513396
- **generated_at**: 2026-09-02T11:24:47.730Z
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

_(empty)_

## covered_by_acceptances

_(empty)_
