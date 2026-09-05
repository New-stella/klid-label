---
logicraft_item: CDIAG-040
type: class_diagram
version: 1
domain: DOMAIN-009
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:19.605Z
status: NEW
prev_version: null
content_hash: 8916ba165d05fa0501352ba6146f3cdde88ebb8a25756d8b68d61e70ad7d5f15
stale: false
raw: ./_raw/CDIAG-040.json
links:
  belongs_to_domain: ["[[DOMAIN-009]]"]
---

# 게시판·공지 구현 계층 — 서비스·정책·연동 오퍼레이션

## theme

neutral

## title

게시판·공지 구현 계층 — 서비스·정책·연동 오퍼레이션

## classes

### NoticeAttachService

- **kind**: service

**methods**:

#### upload

**params**:

- 공지 식별번호
- 첨부 파일
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 허용 확장자와 크기를 확인한 뒤 새 이름을 만들어 정해진 폴더 안에만 저장한다
- **is_abstract**: false
- **return_type**: 첨부 기록

#### download

**params**:

- 공지 식별번호
- 첨부 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 저장된 첨부를 읽어 내보낸다 — 큰 파일을 위해 전용 제한 시간을 쓴다
- **is_abstract**: false
- **return_type**: 내려받기 자료

#### delete

**params**:

- 공지 식별번호
- 첨부 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 첨부 기록과 저장된 파일을 함께 지운다
- **is_abstract**: false
- **return_type**: 없음

#### listByNotice

**params**:

- 공지 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 그 공지에 딸린 첨부 목록을 돌려준다
- **is_abstract**: false
- **return_type**: 첨부 목록

#### cleanupPhysicalFiles

**params**:

- 공지 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 공지를 지울 때 남은 첨부 파일을 함께 정리한다 — 기록만 지우면 파일이 고아로 남는다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 공지 첨부를 새 이름으로 보관하고 내보내는 서비스

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

게시판·공지 Service

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
