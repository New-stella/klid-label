---
logicraft_item: FEAT-003
type: feature
version: 11
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T01:11:03.599Z
status: CHANGED
prev_version: 10
content_hash: 855e443f06b120c13171568fb86af4f5196e492fed07eb6df45b2c6e25626794
stale: false
raw: ./_raw/FEAT-003.json
links:
  implements: ["[[REQ-009]]", "[[REQ-010]]"]
  granted_on_backward: ["[[ROLE-001]]"]
  implements_backward: ["[[API-074]]", "[[API-075]]", "[[API-076]]"]
  realizes_backward: ["[[MOD-015]]", "[[UC-009]]"]
  specializes_backward: ["[[DFEAT-046]]", "[[DFEAT-047]]", "[[DFEAT-054]]"]
---

# 데이터마트 라벨 동기화 통지

## priority

should

## main_flow

### [1]

- **step**: 1
- **action**: 검수 완료된 영상의 라벨 수정이 발생한다

### [2]

- **step**: 2
- **action**: 수정 내용을 영상 단위로 축적하고 그 영상을 재검수 대상으로 표시한다

### [3]

- **step**: 3
- **action**: 검수자가 그 수정을 다시 승인하면 축적분을 요약해 외부(관제)에 수정 통지를 발송한다

### [4]

- **step**: 4
- **action**: 저작도구가 제공하는 조회 경로로 외부가 최신 내용을 다시 가져가 반영한다

## brownfield

### status

new

### change_kind

- capability-add

### diff_summary

검수 완료/재승인 시 관제서버 outbound TASK_COMPLETED/TASK_MODIFIED 통지 2차 신규 (V1.8, SFR-08-06)

## complexity

moderate

## user_story

### goal

검수 완료 후 라벨이 수정되면 외부에 알리기를 원한다

### actor

시스템(관제 연동)

### benefit

데이터마트가 최신 라벨로 유지된다

## description

데이터마트 자체는 외부에서 관리한다. 검수가 끝난 영상의 라벨을 수정하면 그 영상은 재검수 대상이 되고, 검수자가 그 수정을 다시 승인한 시점에 '수정되었음'을 외부(관제)에 통지해 외부가 최신 내용으로 다시 맞출 수 있게 하는 기능. 참고: RQ-SFR-08-06은 요구사항 범위 외(deprecated)로 처리됐으나, 수정분의 TASK_MODIFIED 통지 기능 자체는 버전관리(RQ-SFR-08-04/05) 흐름과 연계되어 운영 유지된다.

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

- REQ-009
- REQ-010
