---
logicraft_item: CDIAG-021
type: class_diagram
version: 1
domain: DOMAIN-007
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:13.075Z
status: NEW
prev_version: null
content_hash: 41d9719337a474ea31dfde5f6fa0174fc9857d23efe68fcea5b09bdec9ed80bc
stale: false
raw: ./_raw/CDIAG-021.json
links:
  belongs_to_domain: ["[[DOMAIN-007]]"]
---

# 데이터 증강 표현 계층 — 컨트롤러 오퍼레이션

## theme

neutral

## title

데이터 증강 표현 계층 — 컨트롤러 오퍼레이션

## classes

### AugmentController

- **kind**: service

**methods**:

#### request

**params**:

- 증강 요청 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 검수자가 외부 생성형 AI에 증강 영상 생성을 요청한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### result

**params**:

- 증강 작업 식별번호
- 쪽 번호
- 쪽 크기
- 항목 쪽 번호
- 항목 쪽 크기

- **is_static**: false
- **visibility**: public
- **description**: 증강 결과와 요청할 때 보낸 생성 조건을 쪽 단위로 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### progress

**params**:

- 증강 결과 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 증강 생성이 어디까지 진행됐는지 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### cancel

**params**:

- 증강 결과 식별번호
- 취소 요청 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 진행 중인 증강 생성을 취소한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### accept

**params**:

- 증강 결과 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 증강 결과를 쓰기로 승인해 작업 대상에 등재한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### reject

**params**:

- 증강 결과 식별번호
- 반려 사유
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 증강 결과를 폐기하기로 반려해 작업 대상에서 뺀다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### restore

**params**:

- 증강 결과 식별번호
- 복구 요청 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 반려한 증강 결과를 되살려 쓸지 버릴지 다시 정할 수 있게 한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

**attributes**:

_(empty)_

- **description**: 증강 요청·결과 검수 창구

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

데이터 증강 Controller

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
