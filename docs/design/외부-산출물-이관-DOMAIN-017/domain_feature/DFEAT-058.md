---
logicraft_item: DFEAT-058
type: domain_feature
version: 5
domain: DOMAIN-017
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-01T08:39:58.123Z
status: CHANGED
prev_version: 4
content_hash: 894c1c88e841d536121ef5e0b716e9fb50980bc242ff2c62fbf0c812334dd103
stale: false
raw: ./_raw/DFEAT-058.json
links:
  based_on: ["[[ADR-048]]"]
  belongs_to_domain: ["[[DOMAIN-017]]"]
  implements: ["[[API-209]]", "[[API-210]]", "[[API-211]]"]
  specializes: ["[[FEAT-010]]"]
  verifies: ["[[AC-1079]]", "[[AC-1080]]", "[[AC-1081]]"]
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

관리자

### i_want

외부 산출물이 쓰는 분류 이름을 저작도구의 라벨과 이벤트 유형에 한 번 연결해 두고 싶다

### so_that

같은 분류가 다시 들어와도 다시 정하지 않고 적재할 수 있다

## description

외부 산출물이 쓰는 분류 이름을 저작도구의 라벨 체계와 이벤트 유형에 연결한다. 대응은 종류 단위이며 개별 항목마다 정하지 않는다. 같은 분류가 나올 때마다 다시 정해야 한다면 규모가 큰 산출물에서는 쓸 수 없는 기능이 된다.

처음 보는 분류가 나오면 이름이 비슷한 후보를 제시하고 사람이 확인해 확정한다. 자동으로 확정하지는 않는다. 이름만 보고 짐작해 연결하면 다른 분류로 저장되고, 저장된 뒤에는 어느 것이 짐작이었는지 구분할 수 없기 때문이다. 확정 이후 같은 분류는 자동으로 연결된다.

확정되지 않은 분류가 남아 있으면 적재하지 않고, 어느 분류가 남았는지 알려준다.

쓰지 않게 된 대응은 지우지 않고 쓰지 않음으로 표시한다. 그 대응으로 이미 적재된 라벨이 무엇을 근거로 그 분류에 놓였는지를 나중에도 되짚을 수 있어야 하기 때문이다.

대응의 열쇠는 축마다 다르다. 라벨 축은 산출물이 영문 코드와 표시 이름을 따로 주므로 그 코드가 그대로 대응 열쇠가 된다. 이벤트 축은 산출물이 코드를 주지 않고 이름만 주므로, 가장 구체적인 최말단 이름 하나가 대응 열쇠가 된다. 상위 계층 이름은 대응 목록에 나오지 않고 참고로만 보관한다. 상위 계층까지 대응 대상으로 열면 같은 항목이 여러 자리에 걸려 어느 대응이 실제로 적용됐는지 알 수 없다.

## invokes_apis

_(empty)_

## attached_files

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

## uses_constants

_(empty)_

## acceptance_rules

### [1]

- **then**: 그 대응이 종류 단위로 보관되고 이후 같은 분류에 자동으로 적용된다
- **when**: 관리자가 대응을 확정한다
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
- **when**: 관리자가 그 대응을 해제한다
- **given**: 더는 쓰지 않을 대응이 있다

### [5]

- **then**: 가장 구체적인 최말단 이름 하나만 대응 열쇠가 되고 상위 계층 이름은 대응 목록에 나오지 않는다
- **when**: 관리자가 그 분류의 대응을 확정한다
- **given**: 이벤트 축의 외부 분류가 상위 계층 이름과 최말단 이름을 함께 담고 있다

## persists_in_tables

- LS_OTSD_CTGRY_MPNG

## related_acceptances

- AC-1079
- AC-1080
- AC-1081

## specializes_feature

FEAT-010

## implemented_by_endpoints

- API-209
- API-210
- API-211

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
