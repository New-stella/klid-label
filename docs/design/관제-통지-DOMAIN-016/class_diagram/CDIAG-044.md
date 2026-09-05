---
logicraft_item: CDIAG-044
type: class_diagram
version: 1
domain: DOMAIN-016
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:16.049Z
status: NEW
prev_version: null
content_hash: 6c24a8b1efec8726cac5f059e9e25a8171e272c06fb41a66ec46b0b0572086df
stale: false
raw: ./_raw/CDIAG-044.json
links:
  belongs_to_domain: ["[[DOMAIN-016]]"]
---

# 관제 통지 구현 계층 — 서비스·정책·연동 오퍼레이션

## theme

neutral

## title

관제 통지 구현 계층 — 서비스·정책·연동 오퍼레이션

## classes

### ControlNotifyClient

- **kind**: service

**methods**:

#### sendTaskCompleted

**params**:

- 완료 통지 본문

- **is_static**: false
- **visibility**: public
- **description**: 검수 승인으로 작업이 끝났음을 관제에 알린다 — 라벨과 메타 본문은 싣지 않는다
- **is_abstract**: false
- **return_type**: 전송 결과

#### sendTaskModified

**params**:

- 수정 통지 본문

- **is_static**: false
- **visibility**: public
- **description**: 승인 뒤 내용이 바뀌었음을 관제에 알린다 — 바뀐 파일 이름만 싣고 경로와 본문은 싣지 않는다
- **is_abstract**: false
- **return_type**: 전송 결과

**attributes**:

_(empty)_

- **description**: 관제서버로 완료·수정 통지를 내보내는 창구

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

관제 통지 Service

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
