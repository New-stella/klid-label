---
logicraft_item: DFEAT-030
type: domain_feature
version: 3
domain: DOMAIN-007
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:52.733Z
status: NEW
prev_version: null
content_hash: dd1abd7457c859a0382c98af09973362af71c8cce47af2606ebb69ae6c4a0a99
stale: false
raw: ./_raw/DFEAT-030.json
links:
  belongs_to_domain: ["[[DOMAIN-007]]"]
  migrated_from: ["[[LEGACY-015]]"]
  depicts_backward: ["[[CDIAG-010]]"]
  references_backward: ["[[CDIAG-010]]"]
---

# 증강 상태 흐름 — 생성 결과와 검수자 활용 결정

## title

증강 상태 흐름 — 생성 결과와 검수자 활용 결정

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

modified

### diff_summary

1차 «가능→대기→진행→완료/부분성공/실패» 단일 상태축 + 실패 자동 재처리 → 2차 «생성 결과 축(콜백 소유)»과 «활용 결정 축(검수자 채택·반려)» 분리, 자동 재처리 폐기(재요청이 정상 동선), 채택분만 등재·반려분은 유예 후 삭제

### legacy_source

#### type

table

#### identifier

LS_DATA_AUG

#### legacy_artifact_id

LEGACY-015

## user_story

### as

검수자(REVIEWER)

### i_want

생성된 증강 결과를 확인해 활용할지 폐기할지 고르기를

### so_that

학습데이터로 쓸 파생만 작업 대상으로 등재된다

## description

증강 상태를 **두 축으로 나눠** 관리한다. 한 컬럼에 합치지 않는다 — 생성이 성공했다는 것과 사람이 그것을 쓰기로 했다는 것은 다른 사실이며, 합치면 활용 결정 자체가 성립하지 않는다.

**[생성 결과 축]** 외부 증강 시스템의 처리 결과만 담는다 — 대기 → 생성 성공 / 생성 실패 / 취소. 이 축은 결과 콜백이 소유하며 사람이 쓰지 않는다.

**[활용 결정 축]** 생성된 파생을 학습데이터로 쓸지는 **검수자(REVIEWER)가 고른다.** 결과를 확인해 채택하거나 반려하며 결정자·결정 일시·반려 사유를 함께 남긴다. **채택된 파생만 작업 목록·배정에 등재**된다. 반려된 파생은 작업 대상에서 빠지고 유예 기간이 지나면 파생 영상과 그 산출물이 삭제된다. 반려는 되돌릴 수 있고, 되돌리면 채택·반려를 다시 고를 수 있다.

해상도 파생은 외부 위탁이 없는 내부 결정론적 리스케일이라 검수 대상이 아니다 — 활용 결정 축을 거치지 않고 등재된다.

[폐기] 실패·부분성공을 시스템이 자동으로 재처리하던 1차 동작은 **두지 않는다.** 같은 (영상 × 증강 종류) 재요청을 허용하므로, 결과가 원하는 것이 아니면 다시 요청하는 것이 정상 동선이다.

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

_(empty)_

### last_updated

2026-05-30T02:38:26.006Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

_(empty)_

## related_acceptances

_(empty)_

## implemented_by_endpoints

_(empty)_

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
