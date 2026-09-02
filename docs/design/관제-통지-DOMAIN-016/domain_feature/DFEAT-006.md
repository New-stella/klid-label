---
logicraft_item: DFEAT-006
type: domain_feature
version: 9
domain: DOMAIN-015
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-02T10:52:42.473Z
status: CHANGED
prev_version: 8
content_hash: ef882a9936bf86329a73455043618017489eeab4e7f3e7162bf8b4b64c9e1ed4
stale: true
raw: ./_raw/DFEAT-006.json
links:
  based_on: ["[[ADR-055]]"]
  belongs_to_domain: ["[[DOMAIN-015]]"]
  implements: ["[[API-070]]", "[[API-071]]", "[[API-072]]", "[[API-116]]", "[[IMPREC-350]]"]
  migrated_from: ["[[LEGACY-112]]"]
  depicts_backward: ["[[CDIAG-007]]", "[[CMP-009]]"]
  realizes_backward: ["[[MOD-014]]", "[[UC-029]]"]
  references_backward: ["[[CDIAG-007]]"]
---

# 작업 배정·재배정·확인

## title

작업 배정·재배정·확인

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

_(empty)_

## brownfield

### status

modified

### decided_by

ADR-055

### change_kind

- actor-change

### diff_summary

1차 관리자/담당자 배정 → 2차 검수자(REVIEWER)가 작업자(WORKER)에게 배정, 영상 단위 배정 (V1.3/V1.8). 관리자(ADMIN)는 역할 계층(ROLE_ADMIN > ROLE_REVIEWER)으로 검수자 권한을 물려받아 배정할 수 있다.

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-03-02-14

#### legacy_artifact_id

LEGACY-112

## user_story

### as

검수자

### i_want

작업자에게 데이터를 배정하고 재배정·확인하기를

### so_that

작업 부하를 분배하고 진행을 통제한다

## description

검수자가 영상 1건 단위로 작업자에게 작업을 배정한다. (RAW_DATA_ID, USER_NO, TASK_TYPE_CD) 유니크로 중복 배정을 막고, 재배정 시 기존 배정 행을 갱신하고 이벤트 로그(LS_TASK_EVNT_LOG)에 이전·신규 담당자를 기록 — 재배정 전용 이력 테이블은 두지 않는다. 작업 상태는 배정 시점에 생성되고 배치 완료 시 ASSIGNED 로 복귀하며, 검수 승인 시 APPROVED 로 종결한다. 증강 파생은 검수자가 사용하기로 결정한 것만 작업목록·배정에 등재된다. (1차 baseline, 화면 SKKLID-UI-03-02-14~15, 02-02-03)

## invokes_apis

_(empty)_

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-350

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:26:19.660Z

### module_paths

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_TASK_ALTMNT
- LS_TASK_EVNT_LOG

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-070
- API-071
- API-072
- API-116

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
