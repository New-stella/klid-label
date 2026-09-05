---
logicraft_item: CDIAG-022
type: class_diagram
version: 1
domain: DOMAIN-009
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:19.604Z
status: NEW
prev_version: null
content_hash: 8e7ba1366d44499ee7d332143ba479ba71893df222702f01702a63a279d6756d
stale: false
raw: ./_raw/CDIAG-022.json
links:
  belongs_to_domain: ["[[DOMAIN-009]]"]
---

# 게시판·공지 표현 계층 — 컨트롤러 오퍼레이션

## theme

neutral

## title

게시판·공지 표현 계층 — 컨트롤러 오퍼레이션

## classes

### NoticeController

- **kind**: service

**methods**:

#### list

**params**:

- 쪽 번호
- 쪽 크기
- 검색 대상
- 검색어
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 공지 목록을 조회한다 — 작업자에게는 발행된 글만 담는다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### get

**params**:

- 공지 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 공지 한 건의 상세를 조회한다 — 발행되지 않은 글은 작업자에게 존재가 드러나지 않는다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### create

**params**:

- 공지 등록 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 공지를 초안 상태로 등록한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### update

**params**:

- 공지 식별번호
- 공지 수정 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 공지의 제목과 내용을 고친다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### delete

**params**:

- 공지 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 공지를 지우고 첨부 파일도 함께 정리한다
- **is_abstract**: false
- **return_type**: 없음

#### publish

**params**:

- 공지 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 공지를 발행해 작업자가 열람할 수 있게 한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### unpublish

**params**:

- 공지 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 공지의 발행을 취소하고 발행 일시를 비운다
- **is_abstract**: false
- **return_type**: 응답 래퍼

**attributes**:

_(empty)_

- **description**: 공지 등록·발행 창구

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### NoticeAttachController

- **kind**: service

**methods**:

#### upload

**params**:

- 공지 식별번호
- 첨부 파일
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 공지에 첨부를 올린다 — 새 이름을 만들어 정해진 폴더 안에만 저장한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### download

**params**:

- 공지 식별번호
- 첨부 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 공지 첨부 파일을 내려보낸다
- **is_abstract**: false
- **return_type**: 파일 응답

#### delete

**params**:

- 공지 식별번호
- 첨부 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 공지 첨부 파일을 지운다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 공지 첨부 창구

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

게시판·공지 Controller

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
