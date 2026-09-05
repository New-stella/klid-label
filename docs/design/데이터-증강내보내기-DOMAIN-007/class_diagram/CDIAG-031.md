---
logicraft_item: CDIAG-031
type: class_diagram
version: 1
domain: DOMAIN-007
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:13.079Z
status: NEW
prev_version: null
content_hash: 56e5f807b32ac46032315bd5eb7a843ba53d594fa67b7693b985b082f65e056e
stale: false
raw: ./_raw/CDIAG-031.json
links:
  belongs_to_domain: ["[[DOMAIN-007]]"]
---

# 데이터 증강·내보내기 구현 계층 — 배치·실행기·보안 오퍼레이션

## theme

neutral

## title

데이터 증강·내보내기 구현 계층 — 배치·실행기·보안 오퍼레이션

## classes

### AsyncDatasetExportRunner

- **kind**: service

**methods**:

#### runAsync

**params**:

- 영상 식별번호
- 강제 재생성 여부

- **is_static**: false
- **visibility**: public
- **description**: 그 영상의 학습데이터 파일을 산출한다
- **is_abstract**: false
- **return_type**: 없음

#### runApprovalAsync

**params**:

- 영상 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 검수 승인 시점의 산출을 돌리고 끝나면 완료 통지로 잇는다
- **is_abstract**: false
- **return_type**: 없음

#### runReExportThenNotify

**params**:

- 영상 식별번호
- 강제 재생성 여부
- 산출 뒤 이어서 할 일

- **is_static**: false
- **visibility**: public
- **description**: 산출물을 다시 만든 뒤에 통지를 보낸다 — 통지가 앞서면 관제가 옛 폴더를 집는다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 검수 승인 뒤 학습데이터 파일을 만드는 비동기 실행기

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### AsyncAugmentFrameRunner

- **kind**: service

**methods**:

#### runAsync

**params**:

- 새 영상 식별번호
- 증강 결과 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 증강으로 만들어진 새 영상의 프레임을 적재한다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 증강 결과로 파생영상 프레임을 만드는 비동기 실행기

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### AsyncResolutionRunner

- **kind**: service

**methods**:

#### runAsync

**params**:

- 새 영상 식별번호
- 부모 영상 식별번호
- 증강 결과 식별번호
- 해상도 프리셋

- **is_static**: false
- **visibility**: public
- **description**: 부모의 비식별 영상을 복사하고 프레임만 목표 해상도로 다시 만들며 라벨 좌표를 배율로 옮긴다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 해상도 파생영상을 만드는 비동기 실행기

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

2차 신규 — 구현 계층 오퍼레이션을 설계 산출물에 처음으로 적는다.

## description

승인 뒤 산출물을 만들고 파생영상 프레임을 만드는 비동기 실행기들이다.

컨트롤러가 아닌 구현 계층 클래스의 오퍼레이션을 적는다. 이름과 파라미터와 반환은 구현에서 실측했고 설명은 그 오퍼레이션이 무엇을 하는지 한 문장으로 적었다. 반환은 표를 읽기 쉽도록 추상 이름으로 적고 돌려줄 것이 없으면 없음이다.

주입 의존은 여기 적지 않는다 — 시퀀스에서 이미 드러난다. 이름이 같고 파라미터가 다른 오퍼레이션은 둘 다 적었다 — 받는 값이 다르면 하는 일도 다르기 때문이다. 이 다이어그램은 도메인 객체 모델이 아니라 구현 계층 목록이므로 같은 도메인의 도메인 모델 다이어그램과 축이 다르다.

## module_name

데이터 증강·내보내기 Implementation

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
