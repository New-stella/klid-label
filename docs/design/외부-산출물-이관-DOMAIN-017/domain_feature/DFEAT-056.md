---
logicraft_item: DFEAT-056
type: domain_feature
version: 4
domain: DOMAIN-017
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-01T08:39:58.121Z
status: CHANGED
prev_version: 3
content_hash: 9f2a5413c13feaac85d66ae0cf49cc9a0c91c9feafe0ec79f314e09881caac35
stale: false
raw: ./_raw/DFEAT-056.json
links:
  based_on: ["[[ADR-048]]"]
  belongs_to_domain: ["[[DOMAIN-017]]"]
  implements: ["[[API-205]]"]
  specializes: ["[[FEAT-010]]"]
  verifies: ["[[AC-1079]]", "[[AC-1080]]", "[[AC-1081]]"]
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

돌려주는 내용은 산출물 문서가 선언한 영상 정보와 프레임·라벨의 규모, 그리고 알려야 할 사항이다. 알려야 할 사항은 한 목록에 모아 돌려주며, 적재를 막는 사유와 막지 않는 사유가 그 안에 함께 담긴다. 짝 문서가 없는 이미지가 있거나 문서가 선언한 건수와 실제 파일 수가 다른 것은 알리되 그대로 진행할 수 있다. 반면 아직 대응이 정해지지 않은 분류가 남아 있거나 이미 가져온 산출물이라는 사실은 진행을 막는다. 훑기 상한을 넘어선 것, 식별자를 만들 수 없는 것, 프레임이 한 건도 없는 것도 막는 쪽의 예이며 이것이 전수 목록은 아니다. 진행해도 되는지의 판정은 응답의 적재 가능 여부 한 자리가 한다. 자리를 나누면 판정 지점이 둘이 되어, 두 자리가 어긋났을 때 어느 쪽이 진실인지 알 수 없다.

폴더 경로는 허용된 저장소 범위 안인지 실제 경로로 확인한다. 상위 디렉터리로 거슬러 올라가는 입력은 받지 않는다. 폴더 안에 다른 자리를 가리키는 바로가기가 있으면 따라가지 않고 건너뛰며, 건너뛴 사실을 알린다. 산출물 문서는 외부에서 만들어진 것이라 믿을 수 없으므로, 폴더 경로를 한 번 검사하는 것만으로는 폴더 안의 항목이 범위 밖을 가리키는 것을 막지 못한다. 이 사실은 알리기만 하고 적재를 막지는 않는다.

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

- **then**: 영상 정보와 프레임·라벨의 규모가 돌아오고, 영상·프레임·라벨·이관 이력 어디에도 새로 저장된 것이 없다
- **when**: 검사를 실행한다
- **given**: 검수자가 산출물 폴더의 위치를 넣었다

### [2]

- **then**: 그 사실이 알림 목록에 담기되, 이 두 가지는 적재 가능 여부를 막지 않는다
- **when**: 검사를 실행한다
- **given**: 짝 문서가 없는 이미지가 있거나 문서가 선언한 건수와 실제 파일 수가 다르다

### [3]

- **then**: 그 사유가 같은 알림 목록에 담기고 적재 가능 여부가 막힘으로 표시된다
- **when**: 검사를 실행한다
- **given**: 대응이 정해지지 않은 분류가 남아 있거나 이미 가져온 산출물이다

### [4]

- **then**: 폴더를 읽지 않고 거부한다
- **when**: 검사를 실행한다
- **given**: 허용된 저장소 범위 밖을 가리키거나 상위 디렉터리로 거슬러 올라가는 경로가 들어왔다

### [5]

- **then**: 그 항목을 따라가지 않고 건너뛰며, 건너뛴 사실이 알림 목록에 담기되 적재 가능 여부는 막히지 않는다
- **when**: 검사를 실행한다
- **given**: 산출물 폴더 안에 다른 자리를 가리키는 바로가기가 들어 있다

## persists_in_tables

_(empty)_

## related_acceptances

- AC-1079
- AC-1080
- AC-1081

## specializes_feature

FEAT-010

## implemented_by_endpoints

- API-205

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
