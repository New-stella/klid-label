---
logicraft_item: SCREEN-003
type: screen_spec
version: 16
last_updated_at: 2026-09-08T12:03:42.287Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-17T02:12:07.289Z
sync_session: 44
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-003.json
wireframe: ./wireframe.html
---

# 접근 거부 화면

## route

/forbidden

## title

접근 거부 화면

## device

desktop

## status

draft

## purpose

접근 권한이 없는 사용자에게 표시되는 안내 화면. 이 화면은 역할이 확인됐고 그 역할로는 접근할 수 없는 경우의 도착지다. 역할이 아직 확인되지 않았거나 역할 확인 조회가 실패한 상태에서는 이 화면으로 보내지 않는다 — 서버가 알려 준 역할은 화면 수명 동안만 유효해 화면을 다시 불러올 때마다 다시 확보하며, 그 확보가 끝난 뒤에만 도착지를 판정한다. 확인하지 못한 상태를 여기로 보내면 사용자는 자기에게 권한이 없다고 잘못 안내받는다. 세션이 끊긴 뒤 같은 인계 토큰으로 다시 서면 역할이 아직 확보되지 않은 상태가 되는데, 그 미확보 상태도 같은 이유로 이 화면으로 보내지 않는다. 미확보·확보 중·확보 실패 어느 것도 도착 사유가 아니다. 접근: 공개.

## sections

### 접근 거부 안내 hero

- **role**: hero
- **layout**: stack

**components**:

#### [1]

- **type**: Custom
- **label**: 잠금 아이콘 (lucide Lock, 붉은 원형 배지)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: LockIconBadge

#### [2]

- **type**: Heading
- **label**: 이 화면에 접근할 수 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **type**: Custom
- **label**: 현재 역할로는 이 페이지에 접근 권한이 없습니다.

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Description

- **description**: 잠금(Lock) 아이콘이 담긴 붉은 원형 배지 + 제목 '이 화면에 접근할 수 없습니다' + 설명 '현재 역할로는 이 페이지에 접근 권한이 없습니다.'. 역할 기반 접근 제어 또는 채널(관제서버/포털) 기반 접근 제어를 통과하지 못한 경우 이 화면으로 이동해 표시되는 공개 화면이다 — 포털 전용 라우트는 두 제어가 함께 적용된다. 두 제어 모두 역할이 확인된 뒤에 판정하며, 역할이 아직 확인되지 않았거나 확인 조회가 실패한 상태는 이 화면의 도착 사유가 아니다 — 확인하지 못한 것과 확인했는데 권한이 모자란 것은 다른 상태다. 세션이 끊긴 뒤 같은 인계 토큰으로 다시 서면 역할이 아직 확보되지 않은 상태가 되는데, 그 미확보 상태도 같은 이유로 도착 사유가 아니다 — 원인만 다를 뿐 확인하지 못한 것을 확인한 것처럼 다루는 같은 오류이고, 그대로 판정하면 사용자는 자기에게 권한이 없다고 잘못 안내받는다. 이 화면에 오려면 확보가 끝났고 역할이 확인됐으며 그 역할로는 접근할 수 없어야 한다 — 미확보·확보 중·확보 실패 어느 것도 도착 사유가 아니다. <main role="alert">. API 호출 없음.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 현재 역할 표시 + 대시보드 이동

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **type**: Badge
- **label**: 현재 역할 배지 (관리자/검수자/작업자/포털, 역할이 없으면 미배정)

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: useAuthStore.claims.role

#### [2]

- **note**: navigate('/')
- **type**: Button
- **label**: 대시보드로

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: '현재 역할:' 라벨 + 역할 배지(ADMIN=관리자/REVIEWER=검수자/WORKER=작업자/PORTAL_USER=포털, 매핑 없으면 원본 role 코드). 역할 값은 클라이언트 상태 저장소의 claims.role 구독, 서버 호출 없음. 역할이 없으면 미배정으로 표시하며 특정 역할로 채우지 않는다 — 접근이 거부된 자리에서 없는 역할을 있는 것처럼 보이면 왜 막혔는지를 오히려 흐린다. 하단 '대시보드로' primary 버튼 클릭 시 navigate('/').

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

## brownfield

### status

new

### change_kind

- capability-add

### diff_summary

2차 권한 없음 안내 화면

## surface_kind

web

## consumes_apis

_(empty)_

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

- MOD-039

### records

- IMPREC-106
- IMPREC-184
- IMPREC-442

### progress

100

### subtasks

_(empty)_

### last_updated

2026-09-08T12:03:42.287Z

### module_paths

_(empty)_

## required_roles

_(empty)_

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-003/main.html
- **label**: 접근 거부 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **source_hash**: 604871f4435dd6ebfebd43805e6fdaab695fd3dd76ead82782b9f33922c224a9
- **generated_at**: 2026-08-28T21:56:59.553Z
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
