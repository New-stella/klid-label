---
logicraft_item: CDIAG-037
type: class_diagram
version: 1
domain: DOMAIN-004
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:24.146Z
status: NEW
prev_version: null
content_hash: 9fbcd1aa6d73a513d6f64ae10dca4ee8e78ed937f622b0f2affd988c9ae96c50
stale: false
raw: ./_raw/CDIAG-037.json
links:
  belongs_to_domain: ["[[DOMAIN-004]]"]
---

# AI 보조 라벨링 구현 계층 — 서비스·정책·연동 오퍼레이션

## theme

neutral

## title

AI 보조 라벨링 구현 계층 — 서비스·정책·연동 오퍼레이션

## classes

### AiServerClient

- **kind**: service

**methods**:

#### predictYoloTrack

**params**:

- 자동 추적 요청

- **is_static**: false
- **visibility**: public
- **description**: 시작 객체 없이 구간에서 여러 객체를 찾아 각각의 경로를 받아 온다
- **is_abstract**: false
- **return_type**: 추론 응답

#### segment

**params**:

- 분할 요청

- **is_static**: false
- **visibility**: public
- **description**: 클릭이나 상자를 받아 객체 외곽 폴리곤을 받아 온다
- **is_abstract**: false
- **return_type**: 추론 응답

#### track

**params**:

- 추적 요청

- **is_static**: false
- **visibility**: public
- **description**: 고른 객체 하나를 뒤따르는 프레임으로 따라가게 한다
- **is_abstract**: false
- **return_type**: 추론 응답

**attributes**:

_(empty)_

- **description**: AI 추론 서버를 부르는 창구

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### YoloAutolabelStep

- **kind**: service

**methods**:

#### stage

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 이 단계가 배치의 어느 자리인지 알려준다
- **is_abstract**: false
- **return_type**: 배치 단계

#### execute

**params**:

- 배치 문맥

- **is_static**: false
- **visibility**: public
- **description**: 실효 프리셋이 정한 대상만 원본 프레임에서 탐지해 라벨로 저장한다 — 프리셋이 없으면 묶음째 보류한다
- **is_abstract**: false
- **return_type**: 없음

#### run

**params**:

- 영상 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 탐지를 돌려 상자 힌트를 돌려준다 — 뒤따르는 분할 단계가 이 힌트를 쓴다
- **is_abstract**: false
- **return_type**: 상자 힌트 목록

**attributes**:

_(empty)_

- **description**: 원본 프레임에 탐지를 돌려 라벨을 만드는 배치 단계

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### PresetLabelLookupService

- **kind**: service

**methods**:

#### resolve

**params**:

- 이벤트 유형 코드

- **is_static**: false
- **visibility**: public
- **description**: 그 유형에 실효한 프리셋이 있는지 판정한다 — 없음·비어 있음·실효를 갈라 돌려주어 보류와 제외가 구분된다
- **is_abstract**: false
- **return_type**: 프리셋 판정

#### normalizeLabelKey

**params**:

- 라벨 문자열

- **is_static**: false
- **visibility**: public
- **description**: 라벨 문자열을 대조에 쓸 형태로 다듬는다
- **is_abstract**: false
- **return_type**: 정규화된 키

**attributes**:

_(empty)_

- **description**: 이벤트 유형에 실효한 오토라벨 프리셋을 찾는 판정기

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

AI 보조 라벨링 Service

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
