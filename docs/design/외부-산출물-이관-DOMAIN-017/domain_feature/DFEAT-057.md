---
logicraft_item: DFEAT-057
type: domain_feature
version: 2
domain: DOMAIN-017
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-19T12:39:54.447Z
status: NEW
prev_version: null
content_hash: e317b2fa0a9870a6feadf45468ac67de23b1d198e2fac204badd9c03556544a3
stale: true
raw: ./_raw/DFEAT-057.json
links:
  belongs_to_domain: ["[[DOMAIN-017]]"]
  implements: ["[[API-206]]"]
  verifies: ["[[AC-044]]", "[[AC-045]]", "[[AC-046]]", "[[AC-047]]"]
  realizes_backward: ["[[UC-035]]"]
---

# 외부 산출물 적재

## title

외부 산출물 적재

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

확인을 마친 산출물 폴더에서 영상과 그에 딸린 프레임·라벨을 만들고 싶다

### so_that

외부에서 라벨링이 끝난 데이터를 검수할 수 있는 상태로 올릴 수 있다

## description

확인을 마친 폴더에서 영상 한 건과 그에 딸린 프레임·라벨을 만들고 이관 이력을 남긴다. 원본 영상 경로는 선택 입력이며, 넣지 않으면 프레임과 라벨만 적재한다.

가져올 때 이 산출물이 비식별이 끝난 것인지 원본인지를 사람이 지정한다. 원본으로 지정한 영상은 비식별이 끝나기 전까지 검수 승인이 막힌다. 비식별되지 않은 화면이 학습데이터로 나가는 것을 막기 위해서다. 적재는 비식별 처리를 불러오지 않는다. 그 처리는 영상이 새로 적재될 때 자동으로 시작되는 것이 원칙이나, 외부 산출물은 이미 끝난 상태로 오거나 외부에서 다시 처리해 가져오는 것이 정상 동선이기 때문이다.

승인을 막을지 풀지는 그 영상의 비식별 상태 하나로 판정한다. 원본으로 가져온 영상은 비식별이 필요하다는 표시로 적재되어 그동안 승인이 막히고, 외부에서 비식별을 처리해 그 결과가 비식별 성공으로 기록된 뒤에는 같은 영상의 승인이 정상으로 진행되어 학습데이터 산출물이 만들어지고 관제로 통지가 나간다. 비식별이 끝난 것으로 지정해 가져온 영상은 처음부터 이 제한을 받지 않는다. 저작도구가 스스로 비식별을 돌려 이 표시를 푸는 경로는 두지 않으므로, 표시를 푸는 길은 외부에서 처리해 그 결과가 기록되는 것뿐이다.

적재된 영상은 작업자 배정과 검수 제출 단계를 거치지 않고 곧바로 검수 대기가 된다. 이 경로에는 작업자가 라벨링을 마치고 제출하는 단계 자체가 없다. 검수자는 내용을 그대로 승인하거나, 고칠 것이 있으면 그때 작업자에게 배정한다.

산출물의 촬영환경 표기가 저작도구와 다를 수 있어 받을 때 저작도구 값으로 바꿔 저장한다. 저장되는 값이 하나로 유지돼야 화면과 산출물이 어긋나지 않는다.

같은 산출물을 다시 가져오려 하면 앞서 만들어진 영상을 알려주며 거부한다. 검수 중이거나 이미 승인된 내용이 조용히 덮여 쓰이는 것을 막기 위해서다.

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

- **then**: 영상 한 건과 그에 딸린 프레임·라벨이 만들어지고 이관 이력이 남는다
- **when**: 적재를 실행한다
- **given**: 검사를 통과한 산출물 폴더가 있다

### [2]

- **then**: 영상 파일 없이 프레임과 라벨만 적재된다
- **when**: 적재를 실행한다
- **given**: 원본 영상 경로를 넣지 않았다

### [3]

- **then**: 비식별이 끝나기 전까지 승인이 막힌다
- **when**: 검수 승인을 시도한다
- **given**: 이 산출물을 원본이라고 지정해 적재했다

### [4]

- **then**: 그 영상이 작업자 배정과 검수 제출 없이 검수할 수 있는 상태로 보인다
- **when**: 검수자가 검수 대상을 조회한다
- **given**: 산출물이 적재됐다

### [5]

- **then**: 앞서 만들어진 영상을 알려주며 거부하고 기존 내용을 덮어쓰지 않는다
- **when**: 같은 산출물의 적재를 다시 시도한다
- **given**: 이미 가져온 산출물이다

### [6]

- **then**: 저작도구 값으로 바꿔 저장한다
- **when**: 적재를 실행한다
- **given**: 산출물의 촬영환경 표기가 저작도구와 다르다

### [7]

- **then**: 승인이 정상으로 진행되어 학습데이터 산출물이 만들어지고 관제로 통지가 나간다
- **when**: 검수 승인을 시도한다
- **given**: 이 산출물을 원본이라고 지정해 적재한 뒤, 외부에서 비식별을 처리해 그 결과가 비식별 성공으로 기록됐다

## persists_in_tables

- LS_DATA_RAW
- LS_DATA_SRC
- LS_DATA_LBL
- LS_DATA_META
- LS_DEIDENT_PROC_LOG

## related_acceptances

- AC-044
- AC-045
- AC-046
- AC-047

## implemented_by_endpoints

- API-206

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
