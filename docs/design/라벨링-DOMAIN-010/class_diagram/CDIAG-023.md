---
logicraft_item: CDIAG-023
type: class_diagram
version: 1
domain: DOMAIN-010
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:20.476Z
status: NEW
prev_version: null
content_hash: 78c0217e7eed13bac9283bdc6f0bb3ca093991190ec9ece77cfa0cdc80aec922
stale: false
raw: ./_raw/CDIAG-023.json
links:
  belongs_to_domain: ["[[DOMAIN-010]]"]
---

# 라벨링 표현 계층 — 컨트롤러 오퍼레이션

## theme

neutral

## title

라벨링 표현 계층 — 컨트롤러 오퍼레이션

## classes

### LabelController

- **kind**: service

**methods**:

#### getLabels

**params**:

- 프레임 식별번호
- 원본 요청 여부
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 그 프레임의 작업 라벨 목록을 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### getLabelHistory

**params**:

- 프레임 식별번호
- 페이지 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 그 프레임의 라벨 변경 이력을 쪽 단위로 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### bulkUpsert

**params**:

- 프레임 식별번호
- 라벨 일괄 저장 요청
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 그 프레임의 라벨을 통째로 저장한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### sam2Track

**params**:

- 프레임 식별번호
- 추적 요청 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 고른 객체 하나를 뒤따르는 프레임 구간으로 따라가게 한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### sam2Segment

**params**:

- 프레임 식별번호
- 분할 요청 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 클릭이나 상자로 객체 외곽을 따 폴리곤을 만든다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### yoloTrack

**params**:

- 프레임 식별번호
- 자동 추적 요청 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 시작 객체 없이 구간에서 여러 객체를 찾아 각각의 이어진 경로를 돌려준다
- **is_abstract**: false
- **return_type**: 응답 래퍼

**attributes**:

_(empty)_

- **description**: 프레임 라벨 조회·저장·AI 보조 창구

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### LabelAttrController

- **kind**: service

**methods**:

#### listAttrs

**params**:

- 라벨 마스터 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 그 라벨의 속성 정의 목록을 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### createAttr

**params**:

- 라벨 마스터 식별번호
- 속성 정의 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 라벨에 속성 정의를 더한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### updateAttr

**params**:

- 라벨 마스터 식별번호
- 속성 식별번호
- 속성 정의 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 라벨 속성 정의를 고친다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### deleteAttr

**params**:

- 라벨 마스터 식별번호
- 속성 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 라벨 속성 정의를 지운다
- **is_abstract**: false
- **return_type**: 없음

#### listAttrValues

**params**:

- 라벨 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 그 라벨에 입력된 속성 값을 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### upsertAttrValues

**params**:

- 라벨 식별번호
- 속성 값 저장 요청
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 라벨의 속성 값을 저장한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

**attributes**:

_(empty)_

- **description**: 라벨 속성 정의·값 창구

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### LabelMasterController

- **kind**: service

**methods**:

#### list

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 등록된 라벨 마스터 목록을 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### detectCandidates

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: AI 검출 클래스가 매핑된 라벨 후보를 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### create

**params**:

- 라벨 마스터 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 라벨 마스터를 등록한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### update

**params**:

- 라벨 마스터 식별번호
- 라벨 마스터 정보
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 라벨 마스터의 이름·형태·색상과 AI 검출 클래스 매핑을 고친다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### delete

**params**:

- 라벨 마스터 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 라벨 마스터를 지운다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 라벨 마스터 관리 창구

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### PresetController

- **kind**: service

**methods**:

#### list

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 오토라벨 프리셋 목록을 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### create

**params**:

- 프리셋 정보

- **is_static**: false
- **visibility**: public
- **description**: 이벤트 유형에 적용할 오토라벨 프리셋을 등록한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### update

**params**:

- 프리셋 식별번호
- 프리셋 정보

- **is_static**: false
- **visibility**: public
- **description**: 프리셋에 담긴 라벨 구성을 고친다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### delete

**params**:

- 프리셋 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 프리셋을 지운다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 오토라벨 프리셋 관리 창구

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

라벨링 Controller

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
