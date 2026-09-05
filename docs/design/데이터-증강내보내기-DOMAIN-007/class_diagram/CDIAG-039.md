---
logicraft_item: CDIAG-039
type: class_diagram
version: 1
domain: DOMAIN-007
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:13.080Z
status: NEW
prev_version: null
content_hash: 5eed3cf953fe960d29a4aec45a90088c833479d72cd86f6adc1f1da86c5379e4
stale: false
raw: ./_raw/CDIAG-039.json
links:
  belongs_to_domain: ["[[DOMAIN-007]]"]
---

# 데이터 증강·내보내기 구현 계층 — 서비스·정책·연동 오퍼레이션

## theme

neutral

## title

데이터 증강·내보내기 구현 계층 — 서비스·정책·연동 오퍼레이션

## classes

### AugmentDiscardPurgeSweeper

- **kind**: service

**methods**:

#### run

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 반려되고 유예가 지난 파생만 골라 지운다 — 파생이면서 반려됐고 유예가 지난 셋을 모두 만족할 때만이다
- **is_abstract**: false
- **return_type**: 삭제 건수

#### isScheduled

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 이 청소기가 지금 돌도록 걸려 있는지 알려준다
- **is_abstract**: false
- **return_type**: 참·거짓

**attributes**:

_(empty)_

- **description**: 폐기된 증강 파생을 유예 뒤 실제로 지우는 청소기

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

2차 신규 — 구현 계층 서비스의 오퍼레이션을 설계 산출물에 적는다.

## description

컨트롤러가 아닌 구현 계층 클래스의 오퍼레이션을 적는다. 이 클래스들은 시퀀스가 받는 메시지에 시그니처가 드러나지 않아 설계 클래스 정의의 칸이 비어 있던 것들이다.

코드에 있는 공개 메서드를 다 싣지 않았다 — 다른 클래스가 실제로 부르는 창구와 프레임워크가 부르는 진입점(사건 수신·주기 실행·구성 생성)만 남기고 내부 보조와 저장소 조회는 뺐다. 그 기준으로 145개에서 63개로 좁혔다.

이름과 파라미터와 반환은 구현에서 실측했고 설명은 그 오퍼레이션이 무엇을 하는지 한 문장으로 적었다. 반환은 표를 읽기 쉽도록 추상 이름으로 적고 돌려줄 것이 없으면 없음이다. 주입 의존은 여기 적지 않는다 — 시퀀스에서 이미 드러난다.

## module_name

데이터 증강·내보내기 Service

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
