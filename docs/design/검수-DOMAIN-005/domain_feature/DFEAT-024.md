---
logicraft_item: DFEAT-024
type: domain_feature
version: 9
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:41:14.285Z
status: NEW
prev_version: null
content_hash: beb5933d839e7ac24878ab206a74920911dbbb563a8ddd4ec1423c33ee69fb09
stale: false
raw: ./_raw/DFEAT-024.json
links:
  belongs_to_domain: ["[[DOMAIN-005]]"]
  implements: ["[[API-014]]", "[[API-015]]"]
  migrated_from: ["[[LEGACY-078]]"]
  specializes: ["[[FEAT-008]]"]
  triggers: ["[[EVT-006]]"]
  verifies: ["[[AC-022]]"]
  depicts_backward: ["[[CDIAG-006]]"]
  realizes_backward: ["[[UC-023]]"]
---

# 승인·반려

## title

승인·반려

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

- EVT-006

## brownfield

### status

modified

### decided_by

ADR-003

### change_kind

- actor-change

### diff_summary

1차 승인/반려/관리자 확인 요청 → 2차 관리자 역할 폐기(REVIEWER 흡수)로 검수자 확인으로 변경

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-02-02-17

#### legacy_artifact_id

LEGACY-078

## user_story

### as

검수자

### i_want

제출 데이터를 승인 또는 반려하기를

### so_that

검수 결과를 확정하고 재작업을 지시한다

## description

검수자가 제출된 데이터를 승인 또는 반려한다. 관리자 확인 요청은 이 기능에 두지 않는다 — 관리자 역할이 검수자로 통합되어 확인을 요청할 상대가 없고, 검수자와 작업자 사이의 확인·문의 소통 축은 이슈 스레드(DFEAT-049)가 담당한다. (1차 baseline, 화면 SKKLID-UI-02-02-17)

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

2026-05-30T02:36:23.314Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_RAW_DATA_STATUS
- LS_DATA_ISSUE

## related_acceptances

- AC-022

## specializes_feature

FEAT-008

## implemented_by_endpoints

- API-014
- API-015

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
