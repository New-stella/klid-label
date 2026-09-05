---
logicraft_item: CDIAG-025
type: class_diagram
version: 1
domain: DOMAIN-014
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:13.925Z
status: NEW
prev_version: null
content_hash: 75b7f3094968b94d42d33d5d5826ec3ae2f01192ce05bd393209344229cd2f87
stale: false
raw: ./_raw/CDIAG-025.json
links:
  belongs_to_domain: ["[[DOMAIN-014]]"]
---

# 시스템 설정 표현 계층 — 컨트롤러 오퍼레이션

## theme

neutral

## title

시스템 설정 표현 계층 — 컨트롤러 오퍼레이션

## classes

### SystemConfigController

- **kind**: service

**methods**:

#### list

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 운영 설정 키와 값 전량을 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### update

**params**:

- 설정 키
- 설정 값
- 관리자 유효창 토큰
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 설정 값 하나를 고치고 설정 캐시를 비운다
- **is_abstract**: false
- **return_type**: 응답 래퍼

**attributes**:

_(empty)_

- **description**: 운영 설정 조회·수정 창구

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### ManageHealthController

- **kind**: service

**methods**:

#### health

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 비식별 서버·AI 추론 서버·데이터베이스의 연결 상태를 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

**attributes**:

_(empty)_

- **description**: 외부 연동 상태 조회 창구

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### EventTypeController

- **kind**: service

**methods**:

#### filterOptions

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 걸러내기에 쓸 이벤트 유형을 표시명이 같은 것끼리 접어 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### labels

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 이벤트 유형 코드와 표시명의 짝을 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

**attributes**:

_(empty)_

- **description**: 이벤트 유형 조회 창구

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

시스템 설정 Controller

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
