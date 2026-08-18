---
logicraft_item: FEAT-004
type: feature
version: 9
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:51.144Z
status: NEW
prev_version: null
content_hash: 0a62c4bd37a5c183b0eb41edb3e3be70b00bb6a21c141393dececfee6ef1860d
stale: true
raw: ./_raw/FEAT-004.json
links:
  based_on: ["[[ADR-004]]"]
  implements: ["[[REQ-001]]", "[[REQ-003]]", "[[REQ-004]]", "[[REQ-005]]", "[[REQ-020]]", "[[REQ-023]]"]
  migrated_from: ["[[LEGACY-015]]"]
  granted_on_backward: ["[[ROLE-001]]"]
  implements_backward: ["[[API-059]]", "[[API-060]]", "[[API-061]]", "[[API-062]]", "[[API-063]]", "[[API-092]]", "[[API-165]]", "[[API-179]]", "[[API-188]]", "[[API-189]]", "[[API-190]]"]
  realizes_backward: ["[[MOD-013]]", "[[MOD-018]]", "[[UC-001]]", "[[UC-002]]", "[[UC-003]]", "[[UC-010]]"]
  references_backward: ["[[SCREEN-022]]", "[[SCREEN-023]]"]
  specializes_backward: ["[[DFEAT-029]]"]
---

# 영상 증강 연동·검수 + 해상도 변경

## priority

should

## main_flow

### [1]

- **step**: 1
- **action**: 외부 증강 영상(WINTER/NIGHT/RAIN)을 원본과 연결된 새 영상으로 수신·등록한다

### [2]

- **step**: 2
- **action**: 원본의 라벨 정보를 새 영상에 복사·매핑하고 좌표를 보존한다(해상도 동일)

### [3]

- **step**: 3
- **action**: 해상도 변경은 원본보다 낮은 해상도(예: 720p)로 다운스케일하여 이미지셋으로 제공한다(업스케일 불가·라벨 좌표 미제공)

### [4]

- **step**: 4
- **action**: 증강 새 영상은 미검수 상태로 들어온다

### [5]

- **step**: 5
- **actor**: 검수자
- **action**: 검수자가 내용을 확인해 학습데이터 활용을 승인/반려한다

## brownfield

### status

modified

### decided_by

ADR-004

### change_kind

- capability-add
- component-replace

### diff_summary

1차 증강 5종(밝기/어둡기/반전) → 2차 외부 증강 연동·결과 검수 + 해상도 변경 (V1.5/V2.0, SFR-07)

### legacy_source

#### type

table

#### identifier

LS_DATA_AUG

#### legacy_artifact_id

LEGACY-015

## complexity

complex

## user_story

### goal

외부에서 증강된 영상을 받아 라벨이 잘 보존됐는지 확인하고 학습데이터로 쓸지 결정하기를 원한다

### actor

검수자

### benefit

증강 데이터를 안전하게 학습데이터로 편입할 수 있다

## description

외부 증강 시스템이 만든 새 영상을 원본과 연결해 수신하고, 원본 라벨을 복사·매핑하여 무결성을 보존하며, 미검수 상태로 시작해 검수 승인/반려를 거친다. 이미지 해상도 변경은 저작도구가 직접 수행하며, 원본보다 낮은 하위 해상도(예: 720p)로 다운스케일한 이미지셋을 제공한다(업스케일 불가·라벨 좌표 미제공).

## business_rules

- 증강 결과는 원본과 다른 새 영상으로 등록하며 원본을 참조한다
- 프롬프트·생성 본체는 외부 시스템 책임으로 범위 외

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

- REQ-001
- REQ-003
- REQ-004
- REQ-005
- REQ-020
- REQ-023
