---
logicraft_item: CDIAG-043
type: class_diagram
version: 1
domain: DOMAIN-012
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:22.424Z
status: NEW
prev_version: null
content_hash: 5dba427cb00270d78f947f9df0ee3d05e71725488ee66f6de617b9d64bbb2f23
stale: false
raw: ./_raw/CDIAG-043.json
links:
  belongs_to_domain: ["[[DOMAIN-012]]"]
---

# 비식별화 구현 계층 — 서비스·정책·연동 오퍼레이션

## theme

neutral

## title

비식별화 구현 계층 — 서비스·정책·연동 오퍼레이션

## classes

### IngestDeidentifyBridge

- **kind**: service

**methods**:

#### onVideoIngested

**params**:

- 영상 적재 사건

- **is_static**: false
- **visibility**: public
- **description**: 적재 트랜잭션이 커밋된 뒤에 파이프라인 선두의 비식별을 비동기로 시작한다 — 커밋 전에 시작하면 대상 행을 읽지 못한다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 적재를 받아 선두 비식별을 띄우는 연결자

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### StorageSubtreePolicy

- **kind**: service

**methods**:

#### ok

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 이 정책이 쓸 수 있게 준비됐는지 알려준다
- **is_abstract**: false
- **return_type**: 참·거짓

#### verifyDeidentifiedFile

**params**:

- 허용 기준 경로
- 대상 경로

- **is_static**: false
- **visibility**: public
- **description**: 대상이 비식별 산출물 자리인지 실제 경로로 확인한다 — 확인한 그 경로로 열어야 심링크가 끼어들지 못한다
- **is_abstract**: false
- **return_type**: 검증 결과

#### isDeidentifiedArtifact

**params**:

- 허용 기준 경로
- 확인된 경로

- **is_static**: false
- **visibility**: public
- **description**: 그 경로가 비식별 산출물 하위에 있는지 판정한다
- **is_abstract**: false
- **return_type**: 참·거짓

#### isExactSegmentPath

**params**:

- 허용 기준 실경로
- 대상 실경로
- 기대하는 자리 이름

- **is_static**: false
- **visibility**: public
- **description**: 경로가 기대한 자리와 정확히 겹치는지 본다 — 이름이 비슷한 이웃 폴더를 같은 자리로 보지 않는다
- **is_abstract**: false
- **return_type**: 참·거짓

#### deidFramesDir

**params**:

- 영상 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 그 영상의 비식별 프레임이 놓이는 자리를 돌려준다
- **is_abstract**: false
- **return_type**: 경로 문자열

#### augmentVideoFile

**params**:

- 부모 영상 식별번호
- 파생 영상 식별번호
- 증강 종류

- **is_static**: false
- **visibility**: public
- **description**: 증강 파생 영상의 비식별 사본이 놓일 자리를 돌려준다
- **is_abstract**: false
- **return_type**: 경로 문자열

#### resolutionVideoFile

**params**:

- 부모 영상 식별번호
- 파생 영상 식별번호
- 해상도 프리셋

- **is_static**: false
- **visibility**: public
- **description**: 해상도 파생 영상의 비식별 사본이 놓일 자리를 돌려준다
- **is_abstract**: false
- **return_type**: 경로 문자열

**attributes**:

_(empty)_

- **description**: 비식별 산출물 경로가 허용 범위 안인지 판정하는 정책

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

비식별화 Service

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
