---
logicraft_item: SCREEN-001
type: screen_spec
version: 16
last_updated_at: 2026-08-27T08:14:10.280Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-27T20:53:19.454Z
sync_session: 31
stale: false
status: CHANGED
prev_version: 13
raw: ./_raw/SCREEN-001.json
wireframe: ./wireframe.html
---

> ⚠️ **버전 변경 감지 — logicraft v13 → v16**
> change_summary: 정적 HTML 와이어프레임 자동 생성 — 1440×auto (4.0KB)
> ↳ 요약/구현 노트 재검토 후 작성된 코드에 반영. 직전 요약은 git diff 확인.

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

- **description**: 진입 직후 기본 상태. 화면 중앙에 Spinner(size=lg, label='세션을 확인하는 중')만 표시. 진입 시 1회 토큰 인계→디코드→채널별 이동을 처리한다(중복 실행 방지). 관제 채널에서 토큰은 localStorage(klid-jwt-token) 또는 쿠키(klid_jwt)로만 인계받는다 — URL 쿼리 파라미터(?token=) 방식은 사용하지 않는다. 개발 환경에서는 위 두 경로에 토큰이 없을 때 빌드타임에 설정된 개발용 대체 토큰(VITE_DEV_TOKEN)을 사용하는 경로가 추가로 있다 — 관제서버가 기동되지 않은 환경에서도 이 화면 진입만으로 세션이 성립하게 하는 개발 전용 지원책이며 운영 빌드에는 포함되지 않는다. 수령한 토큰의 payload 를 클라이언트에서 해석해(서버 측 서명검증은 이 화면에서 하지 않음) 만료 여부를 확인한다. 성공 시 채널이 포털이면 포털 홈으로, 그 외에는 대시보드로 화면을 교체 이동(뒤로가기로 이 화면에 남지 않음). 이 채널 분기는 이 화면으로 직접 진입하는 경우에 적용되며, 포털 채널이 호스트 화면에 임베딩되어 진입하는 경우에는 호스트가 인증을 먼저 처리해 로그인된 사용자에게만 화면을 로드하므로 이 진입 화면을 거치지 않는다. API 호출 없음.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 인증 실패 안내

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: role=alert, aria-live=assertive. redirect 불가 시에만 표시
- **type**: Alert
- **label**: 로그인 서버 연결 실패 / 세션 만료 안내
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

- **description**: 토큰 없음 / claims decode 실패 / 세션 만료 시 fallback. 개발 환경(로컬 개발 빌드이거나, 운영 빌드에서도 빌드타임 플래그 VITE_DEV_LOGIN_ENABLED=true 로 개발 로그인 경로가 열려 있는 경우 — 관제서버 미기동 폐쇄망 구성 지원용, 기본 비활성)에서는 개발용 로그인 화면(SCREEN-004)으로 이동한다. 그 외(운영)에는 토큰의 channel 클레임에 따라 포털 또는 관제서버 각각의 로그인 페이지로 이동한다(진입 시도 경로를 ?next= 로 보존해 로그인 후 원래 위치로 복귀시키며, 허용된 목적지만 사용해 오픈 리다이렉트를 방지). 이동이 불가능한 경우(목적지 URL 미설정 등)에만 이 에러 배너를 노출한다. 메시지: 토큰없음/claims없음='로그인 서버에 연결할 수 없습니다. 관제서버 또는 포털에서 다시 접근해주세요.', 만료='세션이 만료되었습니다. 관제서버 또는 포털에서 다시 접근해주세요.'

**references_apis**:

_(empty)_

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

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-104

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-25T01:21:32.704Z

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

- **source_hash**: 705ba4165a065b5b490ef7f548c44ecc3ca7fdb290ae26ca9ddcf7cbb75f1bdd
- **generated_at**: 2026-08-27T08:14:10.280Z
- **generated_by**: sections-deterministic-generator

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
