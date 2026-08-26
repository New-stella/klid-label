---
logicraft_item: FEAT-002
type: feature
version: 9
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T01:11:06.882Z
status: CHANGED
prev_version: 8
content_hash: e7ac4f63074fb4f0f92d1dba1281418732c9f9bba86fb81b97e828a8a33e25af
stale: false
raw: ./_raw/FEAT-002.json
links:
  implements: ["[[REQ-009]]", "[[REQ-010]]"]
  granted_on_backward: ["[[ROLE-001]]", "[[ROLE-002]]"]
  implements_backward: ["[[API-034]]", "[[API-035]]", "[[API-036]]", "[[API-182]]", "[[API-195]]", "[[API-196]]", "[[API-197]]"]
  realizes_backward: ["[[MOD-007]]", "[[UC-007]]", "[[UC-008]]"]
  references_backward: ["[[CDIAG-015]]"]
---

# 라벨 버전관리·비교·복구

## priority

must

## main_flow

### [1]

- **step**: 1
- **action**: 라벨을 저장한다(작업본 저장이며 버전이 만들어지지 않는다)

### [2]

- **step**: 2
- **action**: 검수가 승인되면 그 시점의 라벨 전체가 한 버전으로 기록된다

### [3]

- **step**: 3
- **action**: 버전 목록과 버전 간 차이를 조회한다

### [4]

- **step**: 4
- **action**: 필요하면 이전 버전으로 복구한다

### [5]

- **step**: 5
- **action**: 과거 산출 회차를 불러와 확인한 뒤 저장을 눌러 확정한다

## brownfield

### status

new

### change_kind

- capability-add

### diff_summary

DB 스냅샷 기반 라벨 버전관리·diff·롤백 2차 신규 — 라벨 전체 JSON 스냅샷을 LS_LABEL_VERSION.LABEL_PAYLOAD 에 저장(versionHash=payload SHA-256), diff·rollback 은 스냅샷 비교로 앱에서 계산. 외부 VCS(Gitea) 미사용. 1차는 LS_DATA_LBL_HSTRY 이력만 보유 (SFR-08-04/05)

## complexity

moderate

## user_story

### goal

라벨 변경 이력을 남기고 이전 버전과 비교·복구하기를 원한다

### actor

작업자·검수자

### benefit

실수를 되돌리고 변경 내용을 추적할 수 있다

## description

검수가 승인된 시점의 라벨 전체를 한 버전으로 기록해 변경이력을 추적하고, 버전 간 차이를 비교하며, 필요 시 이전 버전으로 되돌리는 기능. 라벨 저장 자체는 작업본을 갱신할 뿐 버전을 만들지 않는다. 버전은 DB에 라벨 전체 스냅샷으로 보관하며(외부 VCS 미사용, versionHash=스냅샷 SHA-256), 같은 페이로드 재저장은 동일 버전으로 식별한다. diff·rollback 은 저장된 스냅샷 비교로 수행한다. 과거 산출 회차를 불러온 뒤 저장으로 확정하는 흐름은 버전 축을 바꾸지 않는다.

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
