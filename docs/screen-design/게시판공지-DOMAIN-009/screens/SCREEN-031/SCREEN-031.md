---
logicraft_item: SCREEN-031
type: screen_spec
version: 33
last_updated_at: 2026-08-26T01:08:36.362Z
domain: DOMAIN-009
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T01:33:13.109Z
sync_session: 16
stale: true
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-031.json
wireframe: ./wireframe-main.html
links:
  consumes_apis: ["[[API-096]]", "[[API-099]]", "[[API-100]]", "[[API-101]]", "[[API-107]]"]
  required_roles: ["[[ROLE-001]]", "[[ROLE-002]]"]
---

# 공지 상세 화면

## route

/notice/:id

## title

공지 상세 화면

## device

desktop

## status

draft

## purpose

공지·가이드라인 게시글 상세. 본문 + 첨부파일 목록(원본 파일명 표시, 다운로드). REVIEWER 는 수정/삭제/발행/발행취소 버튼 노출, WORKER 는 PUBLISHED 게시글만 접근 가능.

## sections

### 상단 액션 바 (목록으로·발행 제어)

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **note**: ArrowLeft 아이콘. 클릭 시 목록 화면으로 이동
- **type**: Button
- **label**: 목록으로

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [2]

- **note**: REVIEWER 전용. DRAFT 일 때만 노출. Eye 아이콘. 클릭 시 발행 API 호출
- **type**: Button
- **label**: 발행

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-100

#### [3]

- **note**: REVIEWER 전용. PUBLISHED 일 때만 노출. EyeOff 아이콘. 클릭 시 발행취소 API 호출
- **type**: Button
- **label**: 발행취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline
- **triggers_api**: API-101

- **description**: 좌측 '목록으로' 뒤로가기 버튼(/notice 이동). 우측은 REVIEWER 한정 발행 상태 제어 — 현재 pubStatus 기준으로 발행/발행취소 중 하나만 노출(동시 노출 안 함), 진행 중 loading. WORKER 에게는 발행 제어 버튼이 미노출.

**references_apis**:

- API-100
- API-101

**references_features**:

_(empty)_

### 하단 관리 액션 (수정·삭제)

- **role**: footer
- **layout**: stack

**components**:

#### [1]

- **note**: REVIEWER 전용. Pencil 아이콘. 전용 수정 화면(공지 수정 화면, route /notice/:id/edit)으로 이동 — 모달을 열지 않는다.
- **type**: Button
- **label**: 수정

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [2]

- **note**: REVIEWER 전용. Trash2 아이콘. 클릭 시 확인 다이얼로그 트리거(AlertDialog) — 확인은 아래 '삭제 확인 모달' 섹션 참조
- **type**: Button
- **label**: 삭제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

- **description**: 본문(article) 하단, 구분선 아래 별도 영역 — REVIEWER 한정 수정(전용 화면 이동)·삭제(확인 다이얼로그) 버튼. 발행 제어(상단)와 레이아웃이 분리되어 있다 — 하나의 액션 바가 아니다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 게시글 본문

- **role**: main
- **layout**: detail

**components**:

#### [1]

- **note**: notice.pinned 일 때만 노출. Pin 아이콘. amber. 텍스트 '고정'.
- **type**: Badge
- **label**: 고정

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: REVIEWER 한정. pubStatus PUBLISHED→'발행'(green), DRAFT→'작성중'(gray)
- **type**: Badge
- **label**: 발행 / 작성중

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: h1
- **type**: Heading
- **label**: 공지 제목

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: notice.title

#### [4]

- **note**: 조건부 표시: 작성자(writerName, 없으면 regId 폴백)·등록=regDt·수정=mdfcnDt(있을 때). 날짜·시각 형식으로 표시. — 발행일시(pubDt)는 API 응답에는 있으나 화면에 렌더되지 않는다.
- **type**: KeyValue
- **label**: 게시 메타

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 줄바꿈과 긴 단어를 그대로 보존해 표시
- **type**: Text
- **label**: 본문

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: notice.content

#### [6]

- **note**: isLoading 시 제목+본문 스켈레톤
- **type**: Skeleton
- **label**: 로딩 placeholder
- **state**: loading

**columns**:

_(empty)_

**options**:

_(empty)_

#### [7]

- **note**: ErrorState — error || !notice 시 표시 + 목록으로 버튼
- **type**: Alert
- **label**: 공지를 불러올 수 없습니다
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 상세 조회(API-096) 결과를 카드로 렌더. 헤더: 고정 배지(pinned), 발행상태 배지(REVIEWER 한정 — PUBLISHED→'발행', DRAFT→'작성중'), 제목 heading, 메타(작성자 writerName→regId 폴백/등록 regDt/수정 mdfcnDt — 조건부). 본문은 줄바꿈·긴 단어를 보존해 표시. 로딩 시 Skeleton, 오류/미존재 시 ErrorState.

**references_apis**:

- API-096

**references_features**:

_(empty)_

### 첨부파일 다운로드

- **role**: footer
- **layout**: list

**components**:

#### [1]

- **note**: Paperclip 아이콘 + 첨부 개수
- **type**: Heading
- **label**: 첨부파일 (N)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 각 항목: Download 아이콘 + 원본 파일명 + (파일크기). 클릭 시 다운로드 API 호출. 다운로드 중에는 비활성화
- **type**: List
- **label**: 첨부 다운로드 목록

**columns**:

- 원본 파일명
- 파일 크기

**options**:

_(empty)_

- **triggers_api**: API-107

- **description**: 첨부가 1건 이상일 때만 노출되는 footer. 원본 파일명 + 파일크기 목록을 버튼 리스트로 렌더. 클릭 시 다운로드(API-107) — 원본 파일명 우선, 실패 시 대체 이름 사용. 다운로드 중 해당 항목 비활성화, 실패 시 에러 토스트.

**references_apis**:

- API-107

**references_features**:

_(empty)_

### 삭제 확인 모달 (REVIEWER)

- **role**: modal
- **layout**: stack

**components**:

#### [1]

- **note**: AlertDialog 트리거='삭제' 버튼. 제목 '공지 삭제', 본문 '{공지제목} 공지를 삭제합니다. 이 작업은 되돌릴 수 없습니다.'
- **type**: Dialog
- **label**: 공지 삭제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [2]

- **note**: 삭제 API 호출 → 성공 시 목록 화면으로 이동. 처리 중에는 버튼 비활성화
- **type**: Button
- **label**: 삭제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive
- **triggers_api**: API-099

#### [3]

- **note**: 다이얼로그 닫기
- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 삭제 확인 다이얼로그 — 제목은 '공지 삭제', 본문은 '{제목} 공지를 삭제합니다. 이 작업은 되돌릴 수 없습니다.'. 확인하면 삭제(API-099)를 호출하고 그 공지의 첨부도 함께 정리되며, 완료 후 공지 목록으로 이동한다. 삭제가 진행되는 동안에는 진행 중 상태를 표시해 재지시를 막는다.

**references_apis**:

- API-099

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

신규 추가 — 게시판 상세 화면

## surface_kind

web

## consumes_apis

- API-096
- API-099
- API-100
- API-101
- API-107

## implementation

### status

implemented

### modules

- MOD-035
- MOD-026
- MOD-027

### records

- IMPREC-005

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-11T23:08:20.565Z

## required_roles

- ROLE-001
- ROLE-002

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-031/main.html
- **label**: 공지 상세 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **description**: 
- **source_hash**: 46dc9b43233491011e09ebe9f1c2d5246021a001bbecd71c0b52ddccbce62184
- **generated_at**: 2026-08-26T01:08:07.209Z
- **generated_by**: generate-wireframes.py

**triggered_by**:

_(empty)_

### delete-confirm

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-031/delete-confirm.html
- **label**: 삭제 확인 다이얼로그
- **width**: 480
- **surface**: modal
- **overlays**: main

**sections**:

_(empty)_

- **description**: 삭제 확인 다이얼로그 — 확인하면 삭제(API-099)를 호출하고 그 공지의 첨부도 함께 정리된 뒤 공지 목록으로 이동한다.
- **source_hash**: 46dc9b43233491011e09ebe9f1c2d5246021a001bbecd71c0b52ddccbce62184
- **generated_at**: 2026-08-26T01:08:36.362Z
- **generated_by**: sections-deterministic-generator

**triggered_by**:

- 상세 화면 '삭제' 버튼 (REVIEWER)

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

_(empty)_

## covered_by_acceptances

_(empty)_
