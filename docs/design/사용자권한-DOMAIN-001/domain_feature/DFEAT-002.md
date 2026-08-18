---
logicraft_item: DFEAT-002
type: domain_feature
version: 6
domain: DOMAIN-001
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:50.341Z
status: NEW
prev_version: null
content_hash: cd9f6485b96861fee92c7e6627d054bcb1bc44c61442d796974b97ffa8142cda
stale: false
raw: ./_raw/DFEAT-002.json
links:
  belongs_to_domain: ["[[DOMAIN-001]]"]
  migrated_from: ["[[LEGACY-041]]"]
  depicts_backward: ["[[CDIAG-008]]"]
  references_backward: ["[[CDIAG-008]]"]
---

# 역할별 메뉴·접근제어

## title

역할별 메뉴·접근제어

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

ADR-003

### change_kind

- role-merge
- actor-change

### diff_summary

1차 6역할(작업자·검수자1/2차·업로더·슈퍼관리자·프로젝트담당자) → 2차 3역할(REVIEWER·WORKER·PORTAL_USER), ADMIN 권한 REVIEWER 흡수 (V1.3)

### legacy_source

#### type

table

#### identifier

LS_USER_MENU

#### legacy_artifact_id

LEGACY-041

## user_story

### as

시스템

### i_want

사용자 역할에 따라 접근 가능한 메뉴·기능을 제한하기를

### so_that

권한 없는 기능 접근을 차단한다

## description

사용자 역할에 따라 노출 메뉴와 기능 접근 권한을 분기하고 메뉴/역할 매핑을 관리한다.

[★2차 역할 3종] REVIEWER(검수자) · WORKER(라벨링 작업자) · PORTAL_USER(포털 회원). 1차의 6역할(작업자·검수자 1·2차·업로더·관리자·프로젝트담당자·슈퍼관리자)는 통합·폐기됐다 — 검수 1·2차 단계 통합(ADR-002), ADMIN 권한의 REVIEWER 흡수(ADR-003), 업로더·프로젝트 개념 폐기(ADR-001/004). UI 호칭은 '검수자'로 통일하고 관리 화면 URL 은 /manage/* 다.

[권한 판정 축] 역할은 저작도구 소유 테이블 LS_USER_ROLE(V75)이 단독으로 갖는다. 관제 공유 계정권한 테이블 2종은 런타임 참조 0 인 죽은 테이블이어서 V165 로 삭제됐다. 접근 제어는 인계 JWT 의 role + channel 클레임으로 분기하고 메서드 단위 권한 검증으로 강제한다(내부 채널 vs 포털 채널).

(1차 baseline 역할 목록은 brownfield 맥락으로만 유효하며 현행 권한 모델이 아니다.)

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

2026-05-30T02:33:03.328Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_USER_ROLE

## related_acceptances

_(empty)_

## implemented_by_endpoints

_(empty)_

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
