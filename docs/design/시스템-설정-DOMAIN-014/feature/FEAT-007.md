---
logicraft_item: FEAT-007
type: feature
version: 6
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:57.151Z
status: NEW
prev_version: null
content_hash: a0926b731dd615cdef07a266d4749f68b0364dc5ea3c216fee21981c70a19f54
stale: false
raw: ./_raw/FEAT-007.json
links:
  implements: ["[[REQ-008]]"]
  implements_backward: ["[[API-068]]", "[[API-069]]", "[[API-124]]", "[[API-194]]"]
  realizes_backward: ["[[MOD-006]]", "[[MOD-016]]", "[[UC-031]]"]
  references_backward: ["[[SCREEN-005]]", "[[SCREEN-025]]"]
---

# 라벨링 정밀도 조절

## priority

must

## main_flow

### [1]

- **step**: 1
- **actor**: 라벨링 작업자
- **action**: 작업자가 정밀도 옵션을 연다

### [2]

- **step**: 2
- **action**: 인식 민감도·경계의 세밀함을 조절한다

### [3]

- **step**: 3
- **action**: 조절한 값이 라벨에 반영된다

## brownfield

### status

new

### change_kind

- capability-add

### diff_summary

라벨링 정밀도 조절 2차 신규 (SFR-08-03, REQ-008 분리)

## complexity

moderate

## user_story

### goal

라벨의 정밀도(인식 민감도·경계의 세밀함)를 직접 조절하기를 원한다

### actor

라벨링 작업자

### benefit

상황에 맞게 라벨 정밀도를 맞춰 품질을 확보할 수 있다

## description

라벨링 시 인식 민감도와 경계의 세밀함 등 정밀도를 사용자가 직접 조절할 수 있는 기능.

## business_rules

- 정밀도 옵션은 시스템 설정으로 관리한다

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

#### [1]

- **done**: true
- **description**: PolygonSimplifier(Douglas-Peucker) + sysconfig DECIMAL 키 POLYGON_SIMPLIFY_TOLERANCE

#### [2]

- **done**: true
- **description**: SystemConfigService.getDouble + DECIMAL 범위 검증

#### [3]

- **done**: true
- **description**: Sam2TrackService 폴리곤 단순화 적용 + V54 시드

#### [4]

- **done**: true
- **description**: FE PrecisionConfigCard(인식 민감도 YOLO_CONF_THRESHOLD + 경계 세밀함) UI

### last_updated

2026-05-30T02:32:59.749Z

## implements_requirements

- REQ-008
