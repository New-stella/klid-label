---
logicraft_item: DFEAT-021
type: domain_feature
version: 13
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-15T15:34:18.607Z
status: CHANGED
prev_version: 13
content_hash: b472b5cd399434160f9b2a5dfd326f9dd42af807703dd24a7d582a86b113dd38
stale: true
raw: ./_raw/DFEAT-021.json
links:
  based_on: ["[[ADR-002]]"]
  belongs_to_domain: ["[[DOMAIN-005]]"]
  implements: ["[[API-008]]", "[[API-009]]", "[[API-010]]", "[[API-012]]", "[[API-013]]", "[[API-138]]", "[[API-178]]", "[[API-250]]", "[[IMPREC-357]]"]
  migrated_from: ["[[LEGACY-077]]"]
  specializes: ["[[FEAT-008]]"]
  verifies: ["[[AC-1040]]", "[[AC-1041]]", "[[AC-1042]]", "[[AC-1109]]", "[[AC-1110]]", "[[AC-1111]]", "[[AC-1112]]", "[[AC-1113]]", "[[AC-1114]]", "[[AC-1115]]", "[[AC-1116]]"]
  depicts_backward: ["[[CDIAG-006]]", "[[CMP-005]]"]
  realizes_backward: ["[[UC-023]]"]
  references_backward: ["[[ADR-002]]"]
---

# 검수 (검수자 1인 승인까지 반복)

## title

검수 (검수자 1인 승인까지 반복)

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

_(empty)_

## brownfield

### status

modified

### decided_by

ADR-002

### change_kind

- merge
- redesign

### diff_summary

1차 1·2차 단계 검수 → 2차 단계 구분 폐기, 검수자 1인이 승인(OK)할 때까지 반복 검수 (DFEAT-021←021+022 통합)

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-02-02-16

#### legacy_artifact_id

LEGACY-077

## user_story

### as

검수자

### i_want

라벨링 결과를 프레임 단위로 검토하기를

### so_that

품질 기준을 충족하는지 확인한다

## description

검수자가 라벨링된 데이터를 프레임 단위로 검토한다. 검수자 1인이 승인할 때까지 반려와 재제출을 반복하는 단일 검수다.

검수는 배정 없이 전체 대기열에서 집어간다. 검수 목록은 검수 권한을 가진 누구에게나 검수 대기 전체를 보여주고, 검수 시작·승인·반려의 자격은 역할이 정한다. 검수자를 영상에 배정하는 절차를 두지 않으며 배정의 대상은 작업자뿐이다. 단일 검수라는 결론은 그대로다 — 검수 단계는 여전히 하나이고, 바뀐 것은 그 하나에 누가 들어가는지를 정하는 방식이다.

검수를 시작하면 그 사람이 그 영상을 점유한다. 점유는 작업 이력 원장에 「검수 시작」을 남기는 것으로 표현하며 전용 컬럼이나 표를 두지 않는다. 판정은 저장된 값이 아니라 조회 시점 파생이다 — 그 영상의 최신 검수 시작 기록이 있고, 그보다 뒤에 승인·반려 같은 종결 기록이 없으며, 발생시각에 유예를 더한 시각이 아직 지나지 않았으면 그 기록의 행위자가 점유 중이다. 유예는 배포 설정값이고 기본값은 30분이다. 점유를 푸는 별도 동작은 두지 않는다 — 승인·반려가 자기 기록을 남겨 저절로 풀린다.

★점유는 잠금이 아니다. 만료가 있어 영구 잠금이 되지 않고, 승인 시점의 동시성 보호가 실제 방어로 그대로 남는다. 두 축은 서로를 대체하지 않는다 — 점유는 헛수고를 줄이고 동시성 보호는 사고를 막는다. 점유가 생겼다는 이유로 승인 시점 보호를 없애지 않는다.

검수 시작에서 상태를 전이하는 갈래는 검수 대기에서 시작하는 하나뿐이다. 검수 대기 영상은 상태를 검수 진행으로 전이하며 점유하고, 그 밖의 갈래는 모두 상태 전이 없이 점유만 세운다 — 예컨대 같은 사람이 다시 들어오는 경우, 유예가 지나 풀린 검수 진행 영상을 이어받는 경우, 수정 뒤 재검토 필요 표시가 선 승인 영상이 그렇다. 재검수 건의 상태를 내리지 않는 이유는 승인 영상이 관제 조회용 데이터마트 뷰에 승인 상태를 조건으로 노출되기 때문이다 — 상태를 내리면 이미 완료로 통지한 영상의 행이 관제에서 예고 없이 사라진다. 같은 이유로 이 경우를 위한 상태값을 새로 만들지도 않는다. 두 부류는 동시 경합에서 결말이 갈린다. 상태를 전이하는 갈래는 상태 원장의 동시성 보호가 걸려 밀린 쪽이 충돌로 거절되고, 상태를 건드리지 않는 갈래는 그 보호가 걸릴 자리가 없어 나중에 누른 검수 시작이 점유를 가져간다 — 밀린 쪽은 성공하되 점유의 주인이 아니다. 상태를 건드리지 않는 것은 의도된 결정이며 그 대가로 그런 갈래에는 상태 기반 보호가 걸리지 않는다. 데이터 정합은 이 차이와 무관하게 지켜진다 — 승인 시점의 동시성 보호가 그대로 살아 있다.

검수 목록과 상세는 지금 누가 그 영상을 보고 있는지를 점유자의 표시 이름으로 보여주고, 가장 최근에 승인한 사람을 그 시점의 역할과 함께 보여준다. 역할은 행위 시점 값이라 관리자가 승인한 건은 관리자로 남으며, 역할 기록이 생기기 전에 쌓인 이력은 비어 있을 수 있어 그때는 비워 보인다.

여러 건을 한 번에 승인하는 창구(API-250)도 이 기능에 속한다. 자격은 유효 점유의 주인이 요청자 본인인 건으로 한정하고 부분 실패를 허용하며, 성공한 건의 결과는 단건 승인과 완전히 같다.

근거는 ADR-067.

## invokes_apis

_(empty)_

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-357
- IMPREC-459

### progress

100

### subtasks

_(empty)_

### last_updated

2026-09-15T13:26:26.529Z

### module_paths

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_RAW_DATA_STATUS

## related_acceptances

- AC-1040
- AC-1041
- AC-1042
- AC-1109
- AC-1110
- AC-1111
- AC-1112
- AC-1113
- AC-1114
- AC-1115
- AC-1116

## specializes_feature

FEAT-008

## implemented_by_endpoints

- API-008
- API-009
- API-010
- API-012
- API-013
- API-138
- API-178
- API-250

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
