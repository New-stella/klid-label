---
logicraft_item: CDIAG-035
type: class_diagram
version: 1
domain: DOMAIN-001
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:16.934Z
status: NEW
prev_version: null
content_hash: cd170afd88dc5a2e6d7ed5e5aa6099bd59beb32f2bd1b2f87edaefa4ac7808e5
stale: false
raw: ./_raw/CDIAG-035.json
links:
  belongs_to_domain: ["[[DOMAIN-001]]"]
---

# 사용자·권한 구현 계층 — 서비스·정책·연동 오퍼레이션

## theme

neutral

## title

사용자·권한 구현 계층 — 서비스·정책·연동 오퍼레이션

## classes

### AutoWorkerRegisterTxService

- **kind**: service

**methods**:

#### registerAsWorker

**params**:

- 사용자 번호
- 사용자 이름

- **is_static**: false
- **visibility**: public
- **description**: 역할이 없는 내부 진입자를 작업자로 등록한다 — 이미 부여된 역할은 덮지 않으며 조건부 갱신이라 사용자당 한 번만 일어난다
- **is_abstract**: false
- **return_type**: 참·거짓

**attributes**:

_(empty)_

- **description**: 역할 없는 내부 진입자를 작업자로 자동 등록하는 서비스

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### RoleHierarchy

- **kind**: class

**methods**:

#### authoringRoleHierarchy

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 관리자가 검수자 자리를 물려받는 한 단계만 세운다 — 작업자나 포털 회원을 넣으면 전용으로 열린 자리가 열린다
- **is_abstract**: false
- **return_type**: 역할 계층

**attributes**:

_(empty)_

- **description**: 역할 사이의 상하 관계를 정하는 구성

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

사용자·권한 Service

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
