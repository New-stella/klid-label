---
logicraft_item: DFEAT-052
type: domain_feature
version: 3
domain: DOMAIN-010
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-29T01:27:31.637Z
status: CHANGED
prev_version: 3
content_hash: e581bbaed00b6e9d8ede12474fb8acc7484facfcea52102009d2b83cf7796b80
stale: true
raw: ./_raw/DFEAT-052.json
links:
  based_on: ["[[ADR-034]]"]
  belongs_to_domain: ["[[DOMAIN-010]]"]
  implements: ["[[API-024]]", "[[API-025]]", "[[API-026]]", "[[API-027]]", "[[API-028]]", "[[API-029]]", "[[API-030]]", "[[API-031]]"]
  depicts_backward: ["[[CDIAG-004]]"]
  realizes_backward: ["[[UC-028]]"]
---

# 라벨 클래스·속성 정의 관리

## title

라벨 클래스·속성 정의 관리

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

ADR-034

### change_kind

- capability-add

### diff_summary

라벨 마스터·속성 정의 관리 DFEAT 신규 등록(그래프 연결 누락 보완) — 관리 화면(SCREEN-035)·ADR-034(프리셋-마스터 단일화) 정합

## user_story

### as

검수자(REVIEWER)

### i_want

라벨 클래스와 속성 정의를 직접 관리하기를

### so_that

프리셋·오토라벨·라벨링 화면 전체가 참조하는 단일 진실원을 유지보수할 수 있다

## description

REVIEWER가 라벨 마스터(LS_LABEL: 라벨명·형태 LBL_TYPE_CD·COCO 검출클래스 매핑 DTCT_TYPE_CD)와 속성 정의(LS_LABEL_ATTR)를 CRUD한다(GET/POST/PUT/DELETE /v1/manage/labels = API-024~027, GET/POST/PUT/DELETE /v1/manage/labels/{labelId}/attrs = API-028~031). 라벨 프리셋(LS_LABEL_PRESET_CODE)은 라벨명·형태를 스냅샷 저장하지 않고 LBL_ID FK로 마스터를 실시간 join하므로(ADR-034) 여기서 라벨명·형태를 바꾸면 신규·기존 프리셋에 즉시 반영된다. 형태는 마스터 LBL_TYPE_CD가 소유하며 프리셋에서 개별 토글할 수 없다. AI 탐지 후보는 이 마스터 목록 기준으로 노출하되 DTCT_TYPE_CD가 매핑된 라벨만 실제 검출 가능(미매핑은 표시만, 선택 불가). 1차 관리자매뉴얼 §4.1.3/§4.1.4 갭을 해소하는 관리 화면(SCREEN-035, /manage/labels)의 백엔드 기능이다.

## invokes_apis

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-371

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:26:22.483Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_LABEL
- LS_LABEL_ATTR

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-024
- API-025
- API-026
- API-027
- API-028
- API-029
- API-030
- API-031

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
