---
logicraft_item: DFEAT-059
type: domain_feature
version: 4
domain: DOMAIN-017
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-20T12:49:26.881Z
status: CHANGED
prev_version: 2
content_hash: e8cc11c52ec5db71d93d8aa706de566b95af2cba6d1af860f1ae26ded62488ec
stale: false
raw: ./_raw/DFEAT-059.json
links:
  belongs_to_domain: ["[[DOMAIN-017]]"]
  implements: ["[[API-207]]", "[[API-208]]"]
  specializes: ["[[FEAT-010]]"]
  verifies: ["[[AC-044]]"]
  realizes_backward: ["[[UC-035]]"]
---

# 이관 이력 조회

## title

이관 이력 조회

## status

designed

## consumes

_(empty)_

## priority

should

## triggers

_(empty)_

## brownfield

### status

new

### decided_by

ADR-048

## user_story

### as

검수자

### i_want

언제 누가 어떤 폴더를 가져왔고 얼마나 들어왔는지를 보고 싶다

### so_that

이미 가져온 산출물인지 판단하고, 적재가 실패했을 때 그 사유를 되짚을 수 있다

## description

언제 누가 어떤 폴더를 가져왔고 얼마나 들어왔는지를 보여준다. 같은 산출물을 두 번 가져오려 할 때 거부의 근거가 되는 기록이며, 적재가 도중에 실패했을 때 무엇 때문이었는지를 되짚는 자리이기도 하다.

목록의 정렬은 시간순 하나로만 하며 상태를 우선순위로 섞지 않는다. 지금 처리할 것은 정렬이 아니라 검색 조건으로 좁힌다.

목록의 각 항목에는 그 이관으로 만들어진 영상에 검수 승인 보류가 서 있는지가 함께 실린다. 이 값은 이관 진행 상태와 다른 축이다. 이관이 성공한 영상 가운데 일부에만 보류가 서므로 이관 상태로 대신 판단할 수 없다. 보류가 선 영상만 비식별 완료 기록을 여는 대상이 되며, 보류가 서는 조건은 이 기능이 정하지 않는다. 영상이 만들어지지 않은 이력에는 이 값이 없다.

상세에서는 그 이관이 만든 영상과 들어온 프레임·라벨의 규모, 그리고 실패했다면 그 사유를 함께 본다.

## invokes_apis

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

#### [1]

- **done**: true
- **description**: 목록은 시간순으로만 정렬된다. 상태가 정렬 우선순위로 끼어들지 않는다.

#### [2]

- **done**: true
- **description**: 상세에서 가져온 폴더와 실행한 사람, 만들어진 영상, 프레임과 라벨의 규모를 본다.

#### [3]

- **done**: true
- **description**: 적재가 도중에 깨졌으면 실패 상태와 그 사유가 남아 경위를 되짚을 수 있다.

### last_updated

2026-08-20T12:24:09.296Z

## uses_constants

_(empty)_

## acceptance_rules

### [1]

- **then**: 시간순으로 정렬돼 돌아오고 상태가 정렬 우선순위로 끼어들지 않는다
- **when**: 이관 이력 목록을 조회한다
- **given**: 이관이 여러 차례 이뤄졌다

### [2]

- **then**: 가져온 폴더와 실행한 사람, 만들어진 영상, 프레임·라벨의 규모, 실패했다면 그 사유가 함께 돌아온다
- **when**: 상세를 조회한다
- **given**: 이관 이력에서 한 건을 골랐다

### [3]

- **then**: 실패 상태와 그 사유가 남아 있어 경위를 되짚을 수 있다
- **when**: 그 이관의 상세를 조회한다
- **given**: 적재가 도중에 실패했다

### [4]

- **then**: 각 항목에 그 이관으로 만들어진 영상의 검수 승인 보류 여부가 함께 돌아오고, 이 값은 이관 진행 상태와 다른 축이라 이관 상태로 대신 판단할 수 없으며, 영상이 만들어지지 않은 이력에는 이 값이 없다
- **when**: 이관 이력 목록을 조회한다
- **given**: 이관 이력에 성공한 건과 영상이 만들어지지 않은 실패 건이 섞여 있다

## persists_in_tables

- LS_OTSD_DATST_TRNSF_HSTRY

## related_acceptances

- AC-044

## specializes_feature

FEAT-010

## implemented_by_endpoints

- API-207
- API-208

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
