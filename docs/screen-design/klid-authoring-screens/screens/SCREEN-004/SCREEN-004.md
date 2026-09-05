---
logicraft_item: SCREEN-004
type: screen_spec
version: 17
last_updated_at: 2026-09-02T11:24:47.846Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T02:34:48.337Z
sync_session: 35
stale: true
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-004.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-153]]"]
---

# 개발용 로그인 화면

## route

/dev/login

## title

개발용 로그인 화면

## device

desktop

## status

draft

## purpose

개발 환경 전용 로그인 화면. 운영에서는 관제/포털 JWT 인계 흐름을 사용하며, 이 화면은 그 흐름을 재현하기 위한 개발용 토큰 발급 화면이다(비운영). 접근: 공개(개발 환경에서만 노출). 운영 미노출은 프론트엔드 빌드 설정·백엔드 런타임 설정·운영 계열 프로파일 기동 검증의 3중 조건으로 통제된다(아래 DEV 안내 헤더 절 참조).

## sections

### DEV 안내 헤더

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: Dev Login

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 노란색 경고 배지
- **type**: Custom
- **label**: DEV 빌드 전용

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Badge

#### [3]

- **type**: Custom
- **label**: 안내 문구: 로컬·개발 환경에서 관제서버 없이 토큰을 발급합니다. 운영 배포에는 포함되지 않습니다.

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Text

- **description**: 화면 상단 제목 'Dev Login' + 'DEV 빌드 전용' 배지 + 설명 문구. 로컬·개발 환경에서 관제서버 없이 토큰을 발급하며 운영 배포에는 포함되지 않음을 안내. 운영 미노출은 3중 구조로 보장된다: ① 프론트엔드 — 로컬 개발 빌드이거나, 운영 빌드라도 빌드타임 플래그(VITE_DEV_LOGIN_ENABLED=true)가 설정된 경우에만 이 화면과 토큰 발급 경로가 번들에 포함된다(폐쇄망 등 관제서버 미연결 환경 지원용, 기본 비활성). ② 백엔드 — 토큰 발급 API 는 런타임 설정(authoring.dev.login.enabled=true)이 켜져 있을 때만 등록되며 기본값은 비활성이다. 이 설정이 꺼져 있으면 ①에서 화면이 열려도 API 호출이 실패한다. ③ 운영 계열 배포 환경(스테이징·운영)에서는 ②의 설정이 켜진 채 기동을 시도하면 기동 자체를 실패시켜 배포 시점에 즉시 드러나게 한다 — 이 API 는 인증 없이 임의 권한의 토큰을 발급하므로, 운영 환경에서 활성화되면 인증 체계 전체가 우회되기 때문이다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 토큰 발급 폼

- **role**: main
- **layout**: form

**components**:

#### [1]

- **note**: 선택지는 빌드 채널에 따라 갈린다 — 아래 세 가지는 관제 채널 산출물의 것이고, 포털 채널 산출물은 'PORTAL_USER (3001, 홍길동) · PORTAL' 하나만 둔다. 각 산출물은 서로 다른 서버에 배포되고 자기 채널 사용자만 받으므로, 그 배포에서 쓸 수 없는 역할은 선택지에 두지 않는다.
- **type**: RadioGroup
- **label**: 역할 선택

**columns**:

_(empty)_

**options**:

- ADMIN (9001, 박관리) · INTERNAL
- REVIEWER (1001, 김검수) · INTERNAL
- WORKER (2001, 최라벨) · INTERNAL

- **binds_to**: role

#### [2]

- **type**: Input
- **label**: userNo (선택)

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: userNo
- **placeholder**: 역할별 기본값(예 1001)

#### [3]

- **note**: number 입력, min=1
- **type**: Input
- **label**: expSeconds (선택)

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: expSeconds
- **placeholder**: 3600

#### [4]

- **note**: errorMessage 존재 시에만 role=alert 로 노출
- **type**: Alert
- **label**: 토큰 발급 실패 / localStorage 저장 실패 등 에러 메시지
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [5]

- **note**: 제출 중 disabled + '발급 중…'. POST /api/v1/dev/tokens
- **type**: Button
- **label**: 토큰 발급 + 진입

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

**description**:

역할 라디오 선택 + userNo(선택, 비우면 BE 기본값) + expSeconds(선택, 기본 3600) 입력 후 제출. 제출 시 POST /api/v1/dev/tokens 호출 → 발급된 토큰을 채널에 맞는 자리에 보관 + claims 일부(사용자ID/명/권한) 스텁 저장 → /ingress 로 이동(운영 시나리오 1:1 재현). 실패 시 인라인 에러 알림 표시. 제출 중에는 버튼 disabled + '발급 중…'. 역할 선택에 따라 userNo placeholder와 기본값 안내(9001 박관리 / 1001 김검수 / 2001 최라벨 / 3001 홍길동), channel(INTERNAL/PORTAL)이 연동된다.

★ 역할 선택지는 빌드 채널에 따라 갈린다 — 관제 채널 산출물은 ADMIN·REVIEWER·WORKER(내부 채널 역할), 포털 채널 산출물은 PORTAL_USER. 두 채널 산출물은 서로 다른 서버에 배포되고 각 배포는 자기 채널 사용자만 받으므로, 그 배포에서 쓸 수 없는 역할을 선택지에 두면 고르는 순간 채널이 맞지 않아 진입이 막히는 선택지가 화면에 남는다. 포털 사용자 선택지는 없애는 것이 아니라 포털 채널 산출물로 옮기는 것이며, 그 산출물에서는 그것이 유일한 진입 수단이다. 기본 선택 역할도 각 채널에서 유효한 값이어야 한다.

★ 발급 토큰의 보관 자리도 채널에 따라 갈린다 — 관제 채널은 같은 출처 브라우저 저장소(klid-jwt-token)에 두고, 포털 채널은 본체가 브라우저 저장소를 쓰지 않으므로 Host 를 대신하는 임시 창구가 자기 몫으로 보관한다(INT-013). 그래서 이 화면이 발급한 토큰은 채널에 따라 다른 자리로 간다.

관리자를 선택지에 두는 이유는 이 화면이 인계 흐름을 재현하는 자리기 때문이다 — 재현할 수 없는 역할이 남으면 관리자 전용 화면을 사람이 눌러 확인할 수단이 없어진다.

**references_apis**:

- API-153

**references_features**:

_(empty)_

## brownfield

### status

new

### change_kind

- capability-add

### diff_summary

2차 개발 환경 전용 로그인 (운영 미사용)

## surface_kind

web

## consumes_apis

- API-153

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

- MOD-040

### records

- IMPREC-107
- IMPREC-187
- IMPREC-385

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-31T09:08:24.540Z

### module_paths

_(empty)_

## required_roles

_(empty)_

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-004/main.html
- **label**: 개발용 로그인 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **source_hash**: 2de41ef2968e53f0da322a8b4620e45500651fe6d2e4e7da900d9377cc87b700
- **generated_at**: 2026-09-02T11:24:47.845Z
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
