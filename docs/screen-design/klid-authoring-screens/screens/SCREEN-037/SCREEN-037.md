---
logicraft_item: SCREEN-037
type: screen_spec
version: 8
last_updated_at: 2026-08-16T08:33:59.836Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-08T04:12:26.805Z
sync_session: 37
stale: true
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-037.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-096]]", "[[API-098]]", "[[API-106]]", "[[API-108]]"]
  required_roles: ["[[ROLE-001]]"]
---

# 공지 수정 화면

## route

/notice/:id/edit

## title

공지 수정 화면

## device

desktop

## status

draft

## purpose

기존 공지·가이드라인 게시글을 수정하는 화면(경로 변수 id=수정 대상 게시글 식별자). 공지 상세 화면의 '수정' 버튼으로 진입하며, REVIEWER 전용(역할 가드). 상세 조회 결과로 값이 채워진 제목·내용·상단 고정 폼(작성 화면과 동일 검증 규칙)과, 이미 id가 발급된 게시글이므로 가능한 첨부파일 관리(업로드·행별 삭제) 영역을 함께 제공한다. 저장·취소 모두 해당 게시글 상세 화면으로 복귀한다.

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
- **label**: 공지 수정

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 뒤로 가기 버튼(이전 화면으로 이동) + 페이지 제목 '공지 수정'.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 로딩·오류 상태

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: 상세 조회(API-096) 진행 중 폼 영역 전체를 대체.
- **type**: Skeleton
- **label**: 본문 로딩
- **state**: loading

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 조회 실패 또는 데이터 없음 시 폼 대신 노출. 재시도 버튼 포함.
- **type**: Alert
- **label**: 공지를 불러오지 못했습니다.
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

- **description**: 상세 조회(API-096) 결과로 폼 값을 채우기 전 로딩/오류 상태. 오류 시 아래 수정 폼·첨부 관리 섹션은 렌더되지 않는다.

**references_apis**:

- API-096

**references_features**:

_(empty)_

### 수정 폼

- **role**: main
- **layout**: form

**components**:

#### [1]

- **note**: 기존 값으로 초기화. 최대 200자.
- **type**: Input
- **label**: 제목 *

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **validation**: 필수 · 최대 200자 (zod noticeSchema, 앞뒤 공백 제거) — 작성 화면과 동일 규칙
- **placeholder**: 공지 제목을 입력하세요. (최대 200자)

#### [2]

- **note**: 기존 값으로 초기화.
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

- **note**: 기존 값으로 초기화.
- **type**: Checkbox
- **label**: 중요 공지

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

#### [4]

- **note**: 클릭 시 해당 게시글 상세 화면으로 이동. 제출 진행 중 비활성화.
- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [5]

- **note**: 검증 통과 시 제출. 성공 시 상세 화면으로 이동 + 성공 토스트('공지를 수정했습니다.'). 제출 중 로딩 스피너.
- **type**: Button
- **label**: 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-098

- **description**: 상세 조회 결과로 초기화된 제목/내용/중요 공지 폼(작성 화면과 동일 검증). 저장 시 API-098 PUT, 성공 시 상세 화면으로 이동.

**references_apis**:

- API-096
- API-098

**references_features**:

_(empty)_

### 첨부파일 관리

- **role**: main
- **layout**: list

**components**:

#### [1]

- **note**: Upload 아이콘. 숨김 file input — 선택 즉시 1개씩 업로드(멀티파트). 업로드 중 비활성화 + 스피너. 성공 토스트('첨부파일을 업로드했습니다.').
- **type**: Button
- **label**: 파일 추가

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline
- **triggers_api**: API-106

#### [2]

- **note**: 각 행: 원본 파일명 + 파일 크기 + 삭제 버튼. 삭제 성공 토스트('첨부파일을 삭제했습니다.'). 0건이면 '첨부된 파일이 없습니다.' 안내 텍스트.
- **type**: List
- **label**: 기존 첨부 목록

**columns**:

- 원본 파일명
- 파일 크기

**options**:

_(empty)_

- **triggers_api**: API-108

- **description**: 폼 하단 구분선 아래 별도 영역 — 파일 추가(API-106, 1개씩 업로드) + 기존 첨부 목록(행별 삭제, API-108). 작성 화면에는 이 섹션이 없다(게시글 id 발급 전이라 업로드 불가).

**references_apis**:

- API-106
- API-108

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

신규 추가 — 공지 수정 전용 화면(첨부파일 관리 포함)

## surface_kind

web

## consumes_apis

- API-096
- API-098
- API-106
- API-108

## implementation

### status

implemented

### modules

- MOD-037
- MOD-027
- MOD-029

### records

- IMPREC-007

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-11T23:08:20.662Z

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-037/main.html
- **label**: 공지 수정 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **source_hash**: 32e122ba57da7259e8c257988f6d87ad12e36996fe0426407ed247253c7f916e
- **generated_at**: 2026-08-13T01:02:45.294Z
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
