---
logicraft_item: CDIAG-042
type: class_diagram
version: 2
domain: DOMAIN-011
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:21.387Z
status: NEW
prev_version: null
content_hash: 078a6ba6caf8d73907b36bcda6e1c1dda97afeae08b748cd51acc6cbcc1a8b53
stale: false
raw: ./_raw/CDIAG-042.json
links:
  belongs_to_domain: ["[[DOMAIN-011]]"]
---

# 마킹 구현 계층 — 서비스·정책·연동 오퍼레이션

## theme

neutral

## title

마킹 구현 계층 — 서비스·정책·연동 오퍼레이션

## classes

### MarkingGuards

- **kind**: service

**methods**:

#### requireAssignedOrReviewer

**params**:

- 영상 식별번호
- 사용자 정보
- 배정 저장소

- **is_static**: false
- **visibility**: public
- **description**: 본인에게 배정된 영상이거나 검수자일 때만 통과시킨다
- **is_abstract**: false
- **return_type**: 없음

#### requirePreconditions

**params**:

- 영상

- **is_static**: false
- **visibility**: public
- **description**: 비식별이 끝나 마킹 대기에 이른 영상만 통과시킨다
- **is_abstract**: false
- **return_type**: 없음

#### requireNoActiveMarking

**params**:

- 영상 식별번호
- 마킹 저장소

- **is_static**: false
- **visibility**: public
- **description**: 이미 활성인 마킹이 있으면 막는다 — 같은 영상에 두 마킹이 겹치지 않게 한다
- **is_abstract**: false
- **return_type**: 없음

#### creatorId

**params**:

- 주체 식별자

- **is_static**: false
- **visibility**: public
- **description**: 마킹을 만든 사람으로 기록할 식별자를 정한다
- **is_abstract**: false
- **return_type**: 식별자 문자열

**attributes**:

_(empty)_

- **description**: 마킹 저장 전에 통과해야 하는 조건들을 모아 둔 판정기

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### MarkingBatchBridge

- **kind**: service

**methods**:

#### onMarkingCompleted

**params**:

- 마킹 완료 사건

- **is_static**: false
- **visibility**: public
- **description**: 마킹 트랜잭션이 커밋된 뒤에 잔여 배치를 비동기로 시작한다 — 부모 영상의 비식별이 유효할 때만이다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 마킹 완료를 받아 잔여 배치를 깨우는 연결자

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### MarkingActivationTxService

- **kind**: service

**methods**:

#### activateReserved

**params**:

- 영상 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 예약 마킹을 조건부 갱신으로 원자적으로 하나만 깨워 활성으로 올리고, 집은 쪽에서만 마킹 완료를 알려 잔여 배치가 두 번 기동하지 않게 한다
- **is_abstract**: false
- **return_type**: 활성화된 마킹 식별번호

#### closeReservations

**params**:

- 영상 식별번호
- 마감 사유

- **is_static**: false
- **visibility**: public
- **description**: 쓸 수 없게 된 예약 마킹을 마감한다 — 적재 자체는 되돌리지 않고 마킹만 닫아 사람이 그 영상을 다시 마킹할 수 있게 한다
- **is_abstract**: false
- **return_type**: 마감 건수

**attributes**:

_(empty)_

- **description**: 비식별이 끝난 영상의 예약 마킹을 깨우거나 마감하는 전용 트랜잭션 서비스

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

마킹 Service

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
