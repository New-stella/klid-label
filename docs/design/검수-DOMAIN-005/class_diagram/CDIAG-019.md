---
logicraft_item: CDIAG-019
type: class_diagram
version: 4
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-15T13:21:55.591Z
status: CHANGED
prev_version: 1
content_hash: 16ed10f71671584178ecf4eb0775fa143ef8a89160f6a0c169e1231a9c919fd5
stale: false
raw: ./_raw/CDIAG-019.json
links:
  belongs_to_domain: ["[[DOMAIN-005]]"]
---

# 검수 표현 계층 — 컨트롤러 오퍼레이션

## theme

neutral

## title

검수 표현 계층 — 컨트롤러 오퍼레이션

## classes

### ReviewController

- **kind**: service

**methods**:

#### list

**params**:

- 검수 상태
- 검색어
- 쪽 번호
- 쪽 크기
- 정렬 기준
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 검수 대상 목록을 쪽 단위로 조회한다. 각 항목에 지금 그 영상을 점유한 사람과 마지막으로 승인한 사람·그 행위 시점의 역할, 그리고 요청자가 그 건을 일괄 승인에 담을 수 있는지를 함께 싣는다. 세 축 모두 표시 전용이며 걸러내기·정렬 항목으로 올리지 않는다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### summary

**params**:

- 검수 상태
- 검색어
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 걸러낸 조건 전체를 기준으로 검수 상태별 건수를 집계한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### detail

**params**:

- 영상 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 검수 대상 한 건의 상세를 조회한다. 지금 그 영상을 점유한 사람과 마지막으로 승인한 사람·그 행위 시점의 역할을 함께 싣는다. 점유는 표시이지 조회 자격이 아니라, 남이 점유 중이어도 상세는 열린다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### frames

**params**:

- 영상 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 그 영상의 프레임 목록을 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### issues

**params**:

- 영상 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 그 영상에 등록된 검수 지적 목록을 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### submit

**params**:

- 영상 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 작업자가 라벨링을 마치고 검수를 요청한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### cancelSubmit

**params**:

- 영상 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 작업자가 검수 요청을 거둬들인다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### start

**params**:

- 영상 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 검수자가 그 영상의 검수를 시작한다. 시작하면 그 사람이 그 영상을 점유하며, 점유는 작업 이력 원장에 「검수 시작」을 남기는 것으로 표현하고 전용 컬럼이나 표를 두지 않는다. 상태를 전이하는 갈래는 검수 대기에서 집어가는 하나뿐이고, 그 밖의 갈래는 모두 상태 전이 없이 점유만 세운다 — 예컨대 같은 사람의 재진입, 유예가 지나 풀린 검수 진행 영상을 이어받는 경우, 재검토 필요 표시가 선 승인 영상이 그렇다. 검수 시작 이력에는 행위자와 함께 그 행위를 한 시점의 역할이 남는다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### approve

**params**:

- 영상 식별번호
- 승인 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 검수를 승인해 학습데이터로 확정하고 버전 스냅샷·산출물 재생성·관제 통지를 잇는다. 승인 이력에는 행위자와 함께 그 행위를 한 시점의 역할이 남는다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### batchApprove

**params**:

- 일괄 승인 대상 목록
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 검수 목록에서 고른 여러 영상을 한 번에 승인한다. 승인만 두고 반려는 두지 않는다 — 반려는 건마다 사유가 달라 묶을 수 없다. 자격은 유효 점유의 주인이 요청자 본인이고 단건 승인이 허용하는 상태인 건이며, 부분 실패를 허용해 건별 결과를 돌려준다. 성공한 건의 결과는 단건 승인과 완전히 같다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### reject

**params**:

- 영상 식별번호
- 반려 사유
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 검수를 반려해 작업자에게 되돌린다. 반려 이력에는 행위자와 함께 그 행위를 한 시점의 역할이 남는다
- **is_abstract**: false
- **return_type**: 응답 래퍼

**attributes**:

_(empty)_

- **description**: 검수 목록·상세·판정 창구

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

검수 Controller

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
