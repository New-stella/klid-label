---
logicraft_item: CDIAG-026
type: class_diagram
version: 1
domain: DOMAIN-015
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:15.128Z
status: NEW
prev_version: null
content_hash: 94c0c6a97c30e58565edbb84610839d5a05de32814f7f3a443d9bf0e95256386
stale: false
raw: ./_raw/CDIAG-026.json
links:
  belongs_to_domain: ["[[DOMAIN-015]]"]
---

# 작업 배정 표현 계층 — 컨트롤러 오퍼레이션

## theme

neutral

## title

작업 배정 표현 계층 — 컨트롤러 오퍼레이션

## classes

### AssignmentController

- **kind**: service

**methods**:

#### create

**params**:

- 배정 요청 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 검수자가 영상을 작업자에게 배정한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### reassign

**params**:

- 배정 식별번호
- 재배정 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 배정을 다른 작업자로 옮기고 사유를 이력에 남긴다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### eventTypes

**params**:

- 작업자 식별번호
- 검색어
- 작업 상태
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 배정 목록 걸러내기에 쓸 이벤트 유형 목록을 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### history

**params**:

- 배정 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 그 배정의 변경 이력을 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

**attributes**:

_(empty)_

- **description**: 작업 배정·재배정 창구

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### TaskBoardController

- **kind**: service

**methods**:

#### board

**params**:

- 배치 상태
- 작업 상태
- 검색어
- 이벤트 유형
- 작업자 식별번호
- 페이지 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 작업 목록을 쪽 단위로 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### boardSummary

**params**:

- 배치 상태
- 작업 상태
- 검색어
- 이벤트 유형
- 작업자 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 걸러낸 조건 전체를 기준으로 작업 상태별 건수를 집계한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### boardEventTypes

**params**:

- 배치 상태
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 작업 목록 걸러내기에 쓸 이벤트 유형 목록을 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

**attributes**:

_(empty)_

- **description**: 작업 목록 조회 창구

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

## brownfield

### status

new

### change_kind

- component-add

### diff_summary

2차 신규 — 컨트롤러 오퍼레이션을 설계 산출물에 처음으로 적는다.

## description

이 도메인의 컨트롤러가 밖으로 여는 오퍼레이션을 적는다. 시퀀스에서 컨트롤러가 받는 메시지는 주소 경로라 메서드 이름과 파라미터가 드러나지 않으므로, 그 자리를 채우기 위해 구현 층에서 옮겨 적은 것이다.

이름과 파라미터와 반환은 구현에서 실측했고 설명은 그 오퍼레이션이 무엇을 하는지 한 문장으로 적었다. 반환은 표를 읽기 쉽도록 추상 이름으로 적는다 — 성공 응답을 감싸는 공통 껍데기는 응답 래퍼, 파일·영상 전송은 각각 파일 응답·스트리밍 응답, 돌려줄 것이 없으면 없음이다.

주입 의존은 여기 적지 않는다 — 시퀀스에서 이미 드러난다. 이 다이어그램은 도메인 객체 모델이 아니라 표현 계층 목록이므로 같은 도메인의 도메인 모델 다이어그램과 축이 다르다.

## module_name

작업 배정 Controller

## relationships

_(empty)_

## attached_files

_(empty)_

## depicts_dfeats

_(empty)_

## implementation

### status

planned

### modules

_(empty)_

### records

_(empty)_

### progress

0

### subtasks

_(empty)_

### module_paths

_(empty)_

## referenced_items

_(empty)_

## realizes_features

_(empty)_
