---
logicraft_item: SCREEN-030
type: screen_spec
version: 26
last_updated_at: 2026-08-26T01:08:06.920Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-17T01:18:31.314Z
sync_session: 43
stale: true
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-030.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-095]]"]
  required_roles: ["[[ROLE-001]]", "[[ROLE-002]]"]
---

# 공지 목록 화면

## route

/notice

## title

공지 목록 화면

## device

desktop

## status

draft

## purpose

공지·가이드라인 게시글 목록. 상단 고정(PIN) 우선 + 등록일 내림차순, 페이징. REVIEWER 는 DRAFT 포함 전체 + 작성/수정/발행 관리 버튼 노출, WORKER 는 PUBLISHED 만 열람.

## sections

### 페이지 헤더

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **note**: 부제 '공지사항을 확인합니다.' 와 함께 제목+부제 2단 구성 — 단일 문자열 '게시판 / 공지사항' 이 아니다.
- **type**: Heading
- **label**: 게시판

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: REVIEWER만 노출. Plus 아이콘.
- **type**: Button
- **label**: 새 공지 작성

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: 제목 '게시판' + 부제 '공지사항을 확인합니다.' + REVIEWER 전용 '새 공지 작성' 버튼. 클릭 시 모달이 아니라 전용 작성 화면(공지 작성 화면, route /notice/new)으로 이동한다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 검색 필터

- **role**: filter
- **layout**: stack

**components**:

#### [1]

- **type**: Select
- **label**: 검색 필드

**columns**:

_(empty)_

**options**:

- 제목+내용
- 제목
- 내용

#### [2]

- **type**: Input
- **label**: 검색어

**columns**:

_(empty)_

**options**:

_(empty)_

- **placeholder**: 검색어를 입력하세요.

#### [3]

- **type**: Button
- **label**: 검색

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary
- **triggers_api**: API-095

#### [4]

- **note**: 검색 버튼과 짝을 이루는 공용 필터바 컨벤션 — 필터 값이 하나라도 활성 상태일 때만 노출.
- **type**: Button
- **label**: 초기화

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

- **description**: 검색 필드 select(제목+내용/제목/내용) + 검색어 input(max 100자) + 검색/초기화 버튼. 조건·페이지는 URL 쿼리 반영(뒤로가기 유지).

**references_apis**:

- API-095

**references_features**:

_(empty)_

### 게시글 테이블

- **role**: main
- **layout**: list

**components**:

#### [1]

- **note**: 번호(순번) 컬럼은 없다 — 3열 구성.
- **type**: Table
- **label**: 제목/상태/등록일

**columns**:

- 제목
- 상태
- 등록일

**options**:

_(empty)_

- **triggers_api**: API-095

#### [2]

- **type**: Custom
- **label**: 페이지네이션

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Pagination

- **description**: 제목(고정 배지 — Pin 아이콘, amber, 텍스트 '고정' — pinned 일 때만)/상태(REVIEWER만 — 발행/작성중)/등록일 3컬럼. 고정 글 상단 + 등록일 내림차순. 행 클릭 시 상세(/notice/:id) 이동. 하단 페이지네이션(20건).

**references_apis**:

- API-095

**references_features**:

_(empty)_

## brownfield

### status

new

### decided_by

ADR-014

### change_kind

- screen-add

### diff_summary

신규 추가 — 게시판 목록 화면

## surface_kind

web

## consumes_apis

- API-095

## implementation

### status

implemented

### modules

- MOD-034
- MOD-026

### records

- IMPREC-004

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-11T23:08:20.493Z

## required_roles

- ROLE-001
- ROLE-002

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-030/main.html
- **label**: 공지 목록 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **description**: 
- **source_hash**: 6a58fc676e2eed9ffab46c656829422d1a560281a454654a1e200ba6eb14561c
- **generated_at**: 2026-08-26T01:08:06.920Z
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
