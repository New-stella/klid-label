---
logicraft_item: DFEAT-058
type: domain_feature
version: 1
domain: DOMAIN-017
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-19T12:39:54.447Z
status: NEW
prev_version: null
content_hash: 996afab6fa1d72227dfb853ebfb658ddc8f5336d8dffea31e213a14b515aad82
stale: true
raw: ./_raw/DFEAT-058.json
links:
  belongs_to_domain: ["[[DOMAIN-017]]"]
  implements: ["[[API-209]]", "[[API-210]]", "[[API-211]]"]
  verifies: ["[[AC-042]]", "[[AC-043]]"]
  realizes_backward: ["[[UC-035]]"]
---

# 외부 분류 대응 관리

## title

외부 분류 대응 관리

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

외부 산출물이 쓰는 분류 이름을 저작도구의 라벨과 이벤트 유형에 한 번 연결해 두고 싶다

### so_that

같은 분류가 다시 들어와도 다시 정하지 않고 적재할 수 있다

## description

외부 산출물이 쓰는 분류 이름을 저작도구의 라벨 체계와 이벤트 유형에 연결한다. 대응은 종류 단위이며 개별 항목마다 정하지 않는다. 같은 분류가 나올 때마다 다시 정해야 한다면 규모가 큰 산출물에서는 쓸 수 없는 기능이 된다.

처음 보는 분류가 나오면 이름이 비슷한 후보를 제시하고 사람이 확인해 확정한다. 자동으로 확정하지는 않는다. 이름만 보고 짐작해 연결하면 다른 분류로 저장되고, 저장된 뒤에는 어느 것이 짐작이었는지 구분할 수 없기 때문이다. 확정 이후 같은 분류는 자동으로 연결된다.

확정되지 않은 분류가 남아 있으면 적재하지 않고, 어느 분류가 남았는지 알려준다.

쓰지 않게 된 대응은 지우지 않고 쓰지 않음으로 표시한다. 그 대응으로 이미 적재된 라벨이 무엇을 근거로 그 분류에 놓였는지를 나중에도 되짚을 수 있어야 하기 때문이다.

## invokes_apis

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

## uses_constants

_(empty)_

## acceptance_rules

### [1]

- **then**: 그 대응이 종류 단위로 보관되고 이후 같은 분류에 자동으로 적용된다
- **when**: 검수자가 대응을 확정한다
- **given**: 저작도구의 라벨 또는 이벤트 유형에 연결할 외부 분류가 있다

### [2]

- **then**: 이름이 비슷한 후보가 함께 제시되되 사람의 확인 없이 확정되지는 않는다
- **when**: 대응을 정하려고 조회한다
- **given**: 검사 결과에 처음 보는 분류가 들어 있다

### [3]

- **then**: 어느 분류가 남았는지 알려주며 적재하지 않는다
- **when**: 적재를 시도한다
- **given**: 대응이 정해지지 않은 분류가 남아 있다

### [4]

- **then**: 대응 기록은 남고 사용 여부만 쓰지 않음으로 바뀐다
- **when**: 검수자가 그 대응을 해제한다
- **given**: 더는 쓰지 않을 대응이 있다

## persists_in_tables

- LS_OTSD_CTGRY_MPNG

## related_acceptances

- AC-042
- AC-043

## implemented_by_endpoints

- API-209
- API-210
- API-211

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
