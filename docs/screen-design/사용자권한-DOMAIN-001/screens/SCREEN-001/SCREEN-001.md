---
logicraft_item: SCREEN-001
type: screen_spec
version: 23
last_updated_at: 2026-09-10T06:57:50.433Z
domain: DOMAIN-001
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-17T01:18:28.148Z
sync_session: 22
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-001.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-006]]"]
---

# 세션 인계 진입 화면

## route

/ingress

## title

세션 인계 진입 화면

## device

desktop

## status

draft

## purpose

저작도구는 독립 로그인 UI 없이 관제서버(내부)가 발급한 JWT를 인계받아 진입하는 관제 채널 진입 화면. 접근: 공개.

## sections

### 세션 확인 (로딩)

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: role=status, aria-live=polite. size=lg
- **type**: Custom
- **label**: 세션을 확인하는 중
- **state**: loading

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Spinner

- **description**: 진입 직후 기본 상태. 화면 중앙에 Spinner(size=lg, label='세션을 확인하는 중')만 표시. 진입 시 토큰 인계→디코드→인가 역할 확보→도착지 이동을 처리한다(진입 1회당 중복 실행 방지). 이 처리는 1회 해석에 그치지 않는다 — 화면을 다시 불러올 때마다 인가 역할을 저작도구에 다시 물어 확보한다(서버가 알려 준 역할은 화면 수명 동안만 유효하다). 확보는 채널을 가리지 않으며, 알아낸 역할은 인계 토큰 두는 자리에 함께 보관하지 않는다. 관제 채널에서 토큰은 localStorage(klid-jwt-token) 또는 쿠키(klid_jwt)로만 인계받는다 — URL 쿼리 파라미터(?token=) 방식은 사용하지 않는다. 개발 환경에서는 위 두 경로에 토큰이 없을 때 빌드타임에 설정된 개발용 대체 토큰(VITE_DEV_TOKEN)을 사용하는 경로가 추가로 있다 — 관제서버가 기동되지 않은 환경에서도 이 화면 진입만으로 세션이 성립하게 하는 개발 전용 지원책이며 운영 빌드에는 포함되지 않는다. 수령한 토큰의 payload 를 클라이언트에서 해석해(서버 측 서명검증은 이 화면에서 하지 않음) 만료 여부를 확인한다. 확보가 끝나기 전에는 도착지를 판정하지 않고 위 Spinner 상태를 유지한다 — 확인하지 못한 상태를 역할 없음으로 읽으면 잘못된 안내로 보내진다. 확보가 끝난 뒤 성공 시 채널이 포털이면 포털 홈으로, 그 외에는 대시보드로 화면을 교체 이동(뒤로가기로 이 화면에 남지 않음). 이 채널 분기는 이 화면으로 직접 진입하는 경우에 적용되며, 포털 채널이 호스트 화면에 임베딩되어 진입하는 경우에는 호스트가 인증을 먼저 처리해 로그인된 사용자에게만 화면을 로드하므로 이 진입 화면을 거치지 않는다. 다만 그 경로에서도 역할 확보는 빠지지 않는다 — 호스트 인증 뒤 저작도구 화면이 실행되는 시점에 같은 확보를 수행하고, 끝난 뒤에 표시 범위를 판정한다. 이 화면이 서버에 묻는 것은 역할 확보 조회 하나이며, 토큰 해석에는 서버 호출이 없다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 인증 실패 안내

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: role=alert, aria-live=assertive. redirect 불가 시 또는 역할 확보 조회 장애 시 표시
- **type**: Alert
- **label**: 로그인 서버 연결 실패 / 세션 만료 안내
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [2]

- **note**: 역할 확보 실패 안내에서만 노출 — 누르면 역할 확보를 다시 수행한다.
- **type**: Button
- **label**: 다시 시도

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary
- **triggers_api**: API-006

- **description**: 토큰 없음 / claims decode 실패 / 세션 만료 시 fallback. 개발 환경(로컬 개발 빌드이거나, 운영 빌드에서도 빌드타임 플래그 VITE_DEV_LOGIN_ENABLED=true 로 개발 로그인 경로가 열려 있는 경우 — 관제서버 미기동 폐쇄망 구성 지원용, 기본 비활성)에서는 개발용 로그인 화면(SCREEN-004)으로 이동한다. 그 외(운영)에는 토큰의 channel 클레임에 따라 포털 또는 관제서버 각각의 로그인 페이지로 이동한다(진입 시도 경로를 ?next= 로 보존해 로그인 후 원래 위치로 복귀시키며, 허용된 목적지만 사용해 오픈 리다이렉트를 방지). 이동이 불가능한 경우(목적지 URL 미설정 등)에만 이 에러 배너를 노출한다. 메시지: 토큰없음/claims없음='로그인 서버에 연결할 수 없습니다. 관제서버 또는 포털에서 다시 접근해주세요.', 만료='세션이 만료되었습니다. 관제서버 또는 포털에서 다시 접근해주세요.' 화면을 다시 불러오며 역할을 다시 확보하는 조회가 실패한 경우도 이 자리에서 알린다 — 인증이 유효하지 않으면(토큰 없음·만료·검증 실패) 위와 같이 로그인 페이지로 이동하고, 그 밖의 조회 장애는 이 에러 배너로 노출한다. 메시지: 역할확보실패='권한 정보를 확인하지 못했습니다. 잠시 후 다시 시도해주세요.' 이 실패를 역할 없음으로 다루지 않는다 — 최초 관리자 등록 안내로도, 역할 부여를 요청하라는 안내로도 바꾸어 표시하지 않는다. 확보되지 않은 동안에는 도착지 판정을 내리지 않은 채로 둔다. 확보 조회의 실패 응답도 그것을 요청한 세션에만 반영한다 — 응답이 도착했을 때 그 조회를 시작한 세션이 이미 갈렸으면 이 안내를 띄우지 않는다. 다시 시도는 사람이 하며 자동으로 반복하지 않는다. 역할 확보 실패 안내에는 다시 시도 조작을 함께 둔다 — 누르면 역할 확보 조회를 한 번 더 보낸다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 역할 확보 상태

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **type**: KeyValue
- **label**: 세션 없음 — 물을 대상이 없어 도착지 판정을 통과시킨다

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: KeyValue
- **label**: 미확보 — 역할 판정에 도달하지 않고 로딩 상태를 유지한다

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **type**: KeyValue
- **label**: 확보 중 — 판정을 보류한다

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **type**: KeyValue
- **label**: 확보 실패 — 확인하지 못함이며 역할 없음이 아니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 이 화면을 지나 들어올 때마다 인가 역할을 확보한다. 세션이 끊겼다 같은 인계 토큰으로 다시 서는 경우에도 다시 확보한다 — 앞서 확보한 결과를 그대로 재사용하지 않으며, 재사용하더라도 그 결과가 지금 서 있는 세션의 상태에 실제로 반영돼 있어야 한다. 확보 상태는 네 가지를 가른다. 세션 없음은 물을 대상이 없으므로 도착지 판정을 통과시킨다. 미확보는 이와 다르다 — 역할에 따른 도착지 판정에 도달하지 않고 세션 확인(로딩) 상태를 유지한다. 두 상태를 하나로 다루면 아직 묻지 않은 사용자를 역할이 없는 사용자로 보내게 된다. 확보 중에는 판정을 보류한다. 확보 실패는 확인하지 못했다는 뜻이지 역할이 없다는 뜻이 아니며 인증 실패 안내에서 알린다. 확보 상태의 초기값을 확보 중으로 두지 않는다. 확보 응답은 그것을 요청한 세션에만 반영한다 — 응답이 도착했을 때 그 조회를 시작한 세션이 이미 갈렸으면 성공과 실패 어느 쪽도 반영하지 않는다.

**references_apis**:

- API-006

**references_features**:

_(empty)_

## brownfield

### status

preserved

### diff_summary

외부 JWT 인계 로그인(DFEAT-001 preserved) 진입

## surface_kind

web

## consumes_apis

- API-006

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-104
- IMPREC-440

### progress

100

### subtasks

_(empty)_

### last_updated

2026-09-08T12:03:42.097Z

### module_paths

_(empty)_

## required_roles

_(empty)_

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-001/main.html
- **label**: 메인 페이지
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **description**: v18 사양 기준 재생성 — 역할 확보 완료 전 로딩 유지, 재확보 실패 표시를 인증 실패와 구분
- **source_hash**: 40e1a6fd3d22b4c5515335301cb2d70efaa307e971457630d6124c852fdcb29b
- **generated_at**: 2026-09-10T06:57:50.433Z
- **generated_by**: session_01EL1k4Kbj4LcTHm5RJKpGmW

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
