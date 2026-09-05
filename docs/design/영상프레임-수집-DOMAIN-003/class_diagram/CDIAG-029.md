---
logicraft_item: CDIAG-029
type: class_diagram
version: 1
domain: DOMAIN-003
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:12.140Z
status: NEW
prev_version: null
content_hash: fc325e91c7e312ac99403bf905eaa51e9e93f38729e62698b43cc59ffc055a9b
stale: false
raw: ./_raw/CDIAG-029.json
links:
  belongs_to_domain: ["[[DOMAIN-003]]"]
---

# 영상·프레임 수집 구현 계층 — 배치·실행기·보안 오퍼레이션

## theme

neutral

## title

영상·프레임 수집 구현 계층 — 배치·실행기·보안 오퍼레이션

## classes

### BatchOrchestrator

- **kind**: service

**methods**:

#### process

**params**:

- 영상 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 그 영상의 배치 단계를 순서대로 실행한다
- **is_abstract**: false
- **return_type**: 배치 단계

#### process

**params**:

- 영상 식별번호
- 단계 토글

- **is_static**: false
- **visibility**: public
- **description**: 지정한 단계만 켠 채로 배치를 실행한다
- **is_abstract**: false
- **return_type**: 배치 단계

#### processWithHeldStageClaim

**params**:

- 영상 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 보류로 멈춰 있던 단계를 선점한 채로 이어서 실행한다
- **is_abstract**: false
- **return_type**: 배치 단계

#### processBundleRerun

**params**:

- 영상 식별번호
- 단계 토글
- 선점 출발 상태

- **is_static**: false
- **visibility**: public
- **description**: 묶음을 다시 실행한다 — 어느 상태에서 집어 왔는지를 함께 받아 되돌릴 자리를 안다
- **is_abstract**: false
- **return_type**: 배치 단계

#### processBundleRerun

**params**:

- 영상 식별번호
- 단계 토글
- 선점 출발 상태
- 검수 소유 상태 보존 여부

- **is_static**: false
- **visibility**: public
- **description**: 묶음을 다시 실행하되 검수가 소유한 상태는 건드리지 않는다
- **is_abstract**: false
- **return_type**: 배치 단계

**attributes**:

_(empty)_

- **description**: 배치 단계를 순서대로 돌리고 상태를 전이시키는 조율자

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### FfmpegFrameExtractor

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
- **description**: 마킹 위치에서 원본과 비식별본 두 벌의 프레임을 뽑고 목록 파일을 남긴다
- **is_abstract**: false
- **return_type**: 없음

#### extractByMarks

**params**:

- 영상
- 마킹 시점 목록

- **is_static**: false
- **visibility**: public
- **description**: 주어진 시점 목록대로 프레임을 뽑는다
- **is_abstract**: false
- **return_type**: 프레임 목록

#### extractByMarks

**params**:

- 영상
- 마킹 시점 목록
- 고정 초당 프레임 수

- **is_static**: false
- **visibility**: public
- **description**: 마킹이 만들어질 때 고정된 초당 프레임 수로 위치를 계산해 프레임을 뽑는다
- **is_abstract**: false
- **return_type**: 프레임 목록

**attributes**:

_(empty)_

- **description**: 마킹이 가리킨 위치에서 프레임을 뽑는 배치 단계

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### ControlTrainingVideoScanJob

- **kind**: service

**methods**:

#### execute

**params**:

- 잡 실행 문맥

- **is_static**: false
- **visibility**: public
- **description**: 관제가 인입 원장에 넣어 둔 미처리 행을 폴링해 영상으로 적재한다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 관제가 넣은 인입 대기 행을 주기적으로 집어 오는 스케줄 잡

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

인입 폴링과 배치 단계 실행, 마킹 위치 프레임 추출을 맡는 구현 계층이다.

컨트롤러가 아닌 구현 계층 클래스의 오퍼레이션을 적는다. 이름과 파라미터와 반환은 구현에서 실측했고 설명은 그 오퍼레이션이 무엇을 하는지 한 문장으로 적었다. 반환은 표를 읽기 쉽도록 추상 이름으로 적고 돌려줄 것이 없으면 없음이다.

주입 의존은 여기 적지 않는다 — 시퀀스에서 이미 드러난다. 이름이 같고 파라미터가 다른 오퍼레이션은 둘 다 적었다 — 받는 값이 다르면 하는 일도 다르기 때문이다. 이 다이어그램은 도메인 객체 모델이 아니라 구현 계층 목록이므로 같은 도메인의 도메인 모델 다이어그램과 축이 다르다.

## module_name

영상·프레임 수집 Implementation

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
