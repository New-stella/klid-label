---
logicraft_item: SCREEN-003
type: screen_spec
version: 10
last_updated_at: 2026-08-25T01:21:33.118Z
domain: DOMAIN-001
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T05:53:16.494Z
sync_session: 13
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

접근 권한이 없는 사용자에게 표시되는 안내 화면. 접근: 공개.

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

- **description**: 잠금(Lock) 아이콘이 담긴 붉은 원형 배지 + 제목 '이 화면에 접근할 수 없습니다' + 설명 '현재 역할로는 이 페이지에 접근 권한이 없습니다.'. 역할 기반 접근 제어 또는 채널(관제서버/포털) 기반 접근 제어를 통과하지 못한 경우 이 화면으로 이동해 표시되는 공개 화면이다 — 포털 전용 라우트는 두 제어가 함께 적용된다. <main role="alert">. API 호출 없음.

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
- **label**: 현재 역할 배지 (검수자/작업자/포털)

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

- **description**: '현재 역할:' 라벨 + 역할 배지(REVIEWER=검수자/WORKER=작업자/PORTAL_USER=포털, 매핑 없으면 원본 role 코드). 역할 값은 클라이언트 상태 저장소의 claims.role 구독(기본값 WORKER), 서버 호출 없음. 하단 '대시보드로' primary 버튼 클릭 시 navigate('/').

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

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-106

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-25T01:21:33.118Z

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

- **source_hash**: e31fcda54f4e8081eacba81a8ad44084adece4f0a1dc3e6d4a836ebd3fcb52e0
- **generated_at**: 2026-08-13T01:02:39.049Z
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
