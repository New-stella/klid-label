---
logicraft_item: DFEAT-011
type: domain_feature
version: 3
domain: DOMAIN-003
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:51.139Z
status: NEW
prev_version: null
content_hash: 5216501644db3160cb17124b04e08e308e728a639a16ade32cf6928700df3384
stale: false
raw: ./_raw/DFEAT-011.json
links:
  belongs_to_domain: ["[[DOMAIN-003]]"]
  migrated_from: ["[[LEGACY-021]]"]
  depicts_backward: ["[[CDIAG-001]]"]
---

# 메타데이터 기반 자동 분류

## title

메타데이터 기반 자동 분류

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

preserved

### legacy_source

#### type

table

#### identifier

LS_DATA_RAW

#### legacy_artifact_id

LEGACY-021

## user_story

### as

시스템

### i_want

메타데이터로 데이터를 자동 분류하기를

### so_that

검색·배정·관리 효율을 높인다

## description

영상/이미지의 메타데이터(이벤트 유형·시간대·개인정보 포함 여부 등) 기준 규칙기반 자동 분류(self-fill)를 1차 baseline 에서 시도했으나, 촬영시각 규칙 기반 자동 파생이 오분류(예: 여름 18시 촬영분을 야간으로 오분류)를 실증해 제거됐다(ADR-032). 현재 촬영환경(날씨·시간대·계절)·개인정보(익명/가명/PII 포함 여부) 메타는 라벨링 화면에서 WORKER/REVIEWER가 수동으로 입력·저장한다(DFEAT-051). 이 기능은 자동 분류가 아니라 수동 입력으로 대체됐다.

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

### last_updated

2026-05-30T02:34:27.041Z

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
