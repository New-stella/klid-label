---
logicraft_item: DFEAT-047
type: domain_feature
version: 7
domain: DOMAIN-016
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-29T01:27:27.703Z
status: CHANGED
prev_version: 7
content_hash: 86ed39f90d493d67756b52d1fc1ace176e6700faa4ab89a6bcbd71cc89dfc0fc
stale: false
raw: ./_raw/DFEAT-047.json
links:
  based_on: ["[[ADR-007]]"]
  belongs_to_domain: ["[[DOMAIN-016]]"]
  implements: ["[[API-074]]", "[[API-075]]", "[[API-076]]"]
  specializes: ["[[FEAT-003]]"]
  depicts_backward: ["[[CDIAG-013]]", "[[CMP-009]]"]
  realizes_backward: ["[[MOD-015]]"]
  references_backward: ["[[CDIAG-013]]"]
---

# 관제 inbound 상세 조회 API 제공

## title

관제 inbound 상세 조회 API 제공

## status

designed

## consumes

_(empty)_

## priority

should

## triggers

_(empty)_

## brownfield

### notes

2026-07-30: 관제 정본(EXTSYS-006/API-251/285)과 재검증 필요로 등록했으나 그 뒤 해소됐다 — 경로·페이로드 불일치는 계약 전면 교체로 정합됐고, 함께 지적됐던 x-access-token 부재는 아웃바운드 통지 축의 사안이라 이 인바운드 조회에는 해당하지 않는다. 이 조회 API 는 저작도구가 소유·제공하며 bearer JWT + 역할 기반 메서드 인가 + 접근 권한 검증(IDOR 방지)으로 보호된다. 남은 미확정은 관제가 이 경로를 실제로 호출하기로 합의했는지 여부 하나다.

### status

new

### decided_by

ADR-007

### change_kind

- capability-add

### diff_summary

2차 신규 — 통지를 받은 관제가 상세를 가져가는 조회 경로를 저작도구가 소유·제공한다. 1차 양방향 연동은 쓰지 않는다.

## description

관제서버가 통지 수신 후 상세 데이터를 직접 조회할 수 있도록 영상 단위 요약·라벨·메타 조회 API(GET /v1/tasks/{rawSn}/summary|labels|meta = API-074/075/076)를 제공한다(관제가 RAW_SN으로 4 View 단순 SELECT 후 UPSERT). 경로·필드명은 관제 정본을 따른다. 이 조회 API 는 저작도구가 소유·제공하는 인바운드 경로이며 bearer JWT + 역할 기반 메서드 인가 + 영상 접근 권한 검증(IDOR 방지)으로 보호된다 — 함께 지적됐던 x-access-token 부재는 관제 정본이 반대 방향인 아웃바운드 통지에 요구하는 헤더라 이 축에는 해당하지 않으며, 따라서 이 API 의 인증은 미확정이 아니다. ⚠ 다만 관제가 이 경로를 실제로 호출하기로 합의했는지는 확인된 바가 없어 그 한 가지는 미확정으로 남는다.

## invokes_apis

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-367

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:26:21.924Z

### module_paths

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

_(empty)_

## related_acceptances

_(empty)_

## specializes_feature

FEAT-003

## implemented_by_endpoints

- API-074
- API-075
- API-076

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
