---
logicraft_item: DFEAT-056
type: domain_feature
version: 1
domain: DOMAIN-017
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-19T12:39:54.447Z
status: NEW
prev_version: null
content_hash: 65ce16edda021704319a2a3a42cdd70377e5ae819d1f568824752326bdeab463
stale: true
raw: ./_raw/DFEAT-056.json
links:
  belongs_to_domain: ["[[DOMAIN-017]]"]
  implements: ["[[API-205]]"]
  verifies: ["[[AC-041]]", "[[AC-047]]"]
  realizes_backward: ["[[UC-035]]"]
---

# 산출물 폴더 검사·미리보기

## title

산출물 폴더 검사·미리보기

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

산출물 폴더의 위치를 넣어 무엇이 얼마나 들어오는지와 처리할 수 없는 항목을 미리 확인하고 싶다

### so_that

쓸 수 없는 산출물을 저장하기 전에 걸러내고, 진행해도 되는지를 스스로 판단할 수 있다

## description

검수자가 넣은 폴더 경로를 서버가 훑어 무엇이 얼마나 들어오는지와 처리할 수 없는 항목을 돌려준다. 이 단계는 아무것도 저장하지 않는다. 사람이 내용을 확인한 뒤에 적재를 실행하는 동선의 전제이므로, 이 단계에서 무엇이든 저장되면 확인의 의미가 없어진다.

돌려주는 내용은 산출물 문서가 선언한 영상 정보와 프레임·라벨의 규모, 그리고 알려야 할 사항이다. 알려야 할 사항은 성격에 따라 자리를 나눈다. 짝 문서가 없는 이미지가 있거나 문서가 선언한 건수와 실제 파일 수가 다른 것은 경고로 알리되 그대로 진행할 수 있다. 반면 적재를 막는 사유는 경고와 따로 돌려준다. 아직 대응이 정해지지 않은 분류와 이미 가져온 산출물이라는 사실, 그리고 적재 가능 여부가 여기에 해당한다. 두 가지를 한 자리에 섞으면 부르는 쪽이 진행해도 되는지를 판단할 수 없다.

폴더 경로는 허용된 저장소 범위 안인지 실제 경로로 확인한다. 상위 디렉터리로 거슬러 올라가는 입력은 받지 않는다.

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

- **then**: 영상 정보와 프레임·라벨의 규모가 돌아오고, 영상·프레임·라벨·이관 이력 어디에도 새로 저장된 것이 없다
- **when**: 검사를 실행한다
- **given**: 검수자가 산출물 폴더의 위치를 넣었다

### [2]

- **then**: 그 사실이 경고로 돌아오되 적재 가능 여부는 막히지 않는다
- **when**: 검사를 실행한다
- **given**: 짝 문서가 없는 이미지가 있거나 문서가 선언한 건수와 실제 파일 수가 다르다

### [3]

- **then**: 그 사유가 경고와 다른 자리로 돌아오고 적재 가능 여부가 막힘으로 표시된다
- **when**: 검사를 실행한다
- **given**: 대응이 정해지지 않은 분류가 남아 있거나 이미 가져온 산출물이다

### [4]

- **then**: 폴더를 읽지 않고 거부한다
- **when**: 검사를 실행한다
- **given**: 허용된 저장소 범위 밖을 가리키거나 상위 디렉터리로 거슬러 올라가는 경로가 들어왔다

## persists_in_tables

_(empty)_

## related_acceptances

- AC-041
- AC-047

## implemented_by_endpoints

- API-205

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
