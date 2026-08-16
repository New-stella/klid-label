---
logicraft_item: FEAT-001
type: feature
version: 9
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:52.734Z
status: NEW
prev_version: null
content_hash: cb19357a54656469b9a33d3c40eecf85ad7696bb248590d66abc61fc44909ebd
stale: true
raw: ./_raw/FEAT-001.json
links:
  implements: ["[[REQ-006]]", "[[REQ-007]]", "[[REQ-019]]", "[[REQ-022]]", "[[REQ-023]]", "[[REQ-025]]", "[[REQ-026]]"]
  migrated_from: ["[[LEGACY-066]]"]
  deploys_to_backward: ["[[INFRA-001]]"]
  granted_on_backward: ["[[ROLE-001]]", "[[ROLE-002]]"]
  implements_backward: ["[[API-020]]", "[[API-093]]", "[[API-119]]", "[[API-120]]", "[[API-121]]", "[[API-123]]", "[[API-177]]"]
  realizes_backward: ["[[MOD-002]]", "[[MOD-006]]", "[[MOD-009]]", "[[MOD-020]]", "[[UC-004]]", "[[UC-005]]", "[[UC-006]]"]
  specializes_backward: ["[[DFEAT-018]]", "[[DFEAT-019]]", "[[DFEAT-020]]"]
---

# AI 보조 라벨링 (객체 추적·분할(VOS)·외곽 경계 밀착)

## priority

must

## main_flow

### [1]

- **step**: 1
- **actor**: 라벨링 작업자
- **action**: 라벨링할 객체와 보조 방식(추적/밀착)을 선택한다

### [2]

- **step**: 2
- **action**: 추적(08-01): 시작 프레임에서 객체를 지정하면 SAM2 비디오 추적·분할(VOS)로 후속 프레임의 위치·경계를 자동 갱신한다

### [3]

- **step**: 3
- **action**: 외곽 밀착(08-02): 클릭/박스 지정 시 SAM2가 정지 프레임 외곽 경계를 자동 산출한다

### [4]

- **step**: 4
- **action**: 트랙 보간으로 빈 프레임을 채우고 작업자가 결과를 확인·보정한다

### [5]

- **step**: 5
- **action**: 라벨을 확정해 저장한다

## brownfield

### status

modified

### change_kind

- capability-add

### diff_summary

1차 AI Tool(SAM)+Auto Labeling(YOLO) → 2차 객체 추적·외곽 경계 자동 밀착 고도화 (SFR-08-01/02)

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-02-02-05

#### legacy_artifact_id

LEGACY-066

## complexity

complex

## user_story

### goal

시작 프레임에서 객체를 지정하면 후속 프레임의 위치·경계가 자동 추적·갱신되고 클릭 한 번으로 외곽 경계가 밀착되기를 원한다

### actor

라벨링 작업자

### benefit

수작업 라벨링 시간을 줄이고 위치·경계 정확도를 높일 수 있다

## description

라벨링을 AI가 보조하는 기능 묶음. ① 객체 추적·분할(08-01): 시작 프레임에서 지정한 객체를 SAM2 비디오 추적·분할(VOS)로 후속 프레임의 위치·경계 자동 갱신. ② 외곽 경계 자동 밀착(08-02): 클릭/박스 한 번으로 SAM2가 정지 프레임 외곽 경계를 산출. 사람 개입 없이 배치로 수행하는 오토라벨링과 구분되는 작업 과정의 AI 보조다.

## business_rules

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

_(empty)_

### progress

100

### subtasks

_(empty)_

## implements_requirements

- REQ-006
- REQ-007
- REQ-019
- REQ-022
- REQ-023
- REQ-025
- REQ-026
