---
logicraft_item: SCREEN-036
type: screen_spec
version: 8
last_updated_at: 2026-08-16T08:33:58.661Z
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:27:41.264Z
sync_session: 15
stale: true
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-036.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-097]]"]
  required_roles: ["[[ROLE-001]]"]
---

# 공지 작성 화면

## route

/notice/new

## title

공지 작성 화면

## device

desktop

## status

draft

## purpose

공지·가이드라인 게시글을 새로 작성하는 화면. 공지 목록 화면의 '새 게시글 작성' 버튼으로 진입하며, REVIEWER 전용(역할 가드 — WORKER 는 라우트 접근 자체가 차단된다). 제목·내용·상단 고정 여부를 입력하는 단일 폼(react-hook-form + zod 검증)이며, 저장 성공 시 새로 생성된 게시글의 상세 화면으로 이동한다. 첨부파일 관리는 이 화면에 없다 — 게시글 id가 발급되기 전이라 업로드 대상이 없기 때문에, 첨부는 저장 후 수정 화면에서 추가한다.

## sections

### 페이지 헤더

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **note**: ArrowLeft 아이콘. 특정 경로 고정이 아니라 브라우저 이전 화면으로 이동.
- **type**: Button
- **label**: 뒤로 가기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [2]

- **type**: Heading
- **label**: 새 공지 작성

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 뒤로 가기 버튼(이전 화면으로 이동) + 페이지 제목 '새 공지 작성'.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 작성 폼

- **role**: main
- **layout**: form

**components**:

#### [1]

- **note**: 최대 200자.
- **type**: Input
- **label**: 제목 *

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **validation**: 필수 · 최대 200자 (zod noticeSchema, 앞뒤 공백 제거)
- **placeholder**: 공지 제목을 입력하세요. (최대 200자)

#### [2]

- **type**: Textarea
- **label**: 내용 *

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **validation**: 필수 (zod, 앞뒤 공백 제거)
- **placeholder**: 공지 내용을 입력하세요.

#### [3]

- **note**: 체크 시 게시판 목록 최상단에 고정 표시된다.
- **type**: Checkbox
- **label**: 중요 공지

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

#### [4]

- **note**: 클릭 시 목록 화면으로 이동. 제출 진행 중 비활성화.
- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [5]

- **note**: 검증 통과 시 제출. 성공 시 생성된 게시글의 상세 화면(id 발급됨)으로 이동. 제출 중 로딩 스피너로 전환.
- **type**: Button
- **label**: 작성

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-097

- **description**: 제목(필수·최대 200자)·내용(필수)·중요 공지 체크박스로 구성된 단일 폼. 하단 취소/작성 버튼. 첨부파일 관리 영역은 없음 — 게시글 id 발급 전이라 업로드가 불가하며, 저장 후 수정 화면에서 첨부를 추가한다.

**references_apis**:

- API-097

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

신규 추가 — 공지 작성 전용 화면

## surface_kind

web

## consumes_apis

- API-097

## implementation

### status

implemented

### modules

- MOD-038
- MOD-036
- MOD-027
- MOD-029

### records

- IMPREC-006

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-11T23:08:20.613Z

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-036/main.html
- **label**: 공지 작성 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **source_hash**: 1eddcb3fe7495732d284d78a57b4a16f7949eadaf4ffb7fe3d1581f241f2e851
- **generated_at**: 2026-08-13T01:02:44.905Z
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
