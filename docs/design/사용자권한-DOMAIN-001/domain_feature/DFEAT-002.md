---
logicraft_item: DFEAT-002
type: domain_feature
version: 7
domain: DOMAIN-001
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-27T20:54:27.190Z
status: CHANGED
prev_version: 6
content_hash: 1f5389c9d688855a48ff0a5188e75ed9d9143fdc276b4b716fb0cbe1eadbd155
stale: false
raw: ./_raw/DFEAT-002.json
links:
  based_on: ["[[ADR-003]]"]
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

[★2차 역할 3종] REVIEWER(검수자) · WORKER(라벨링 작업자) · PORTAL_USER(포털 회원). 1차의 6역할(작업자·검수자 1·2차·업로더·관리자·프로젝트담당자·슈퍼관리자)는 통합·폐기됐다 — 검수 1·2차 단계 통합(ADR-002), ADMIN 권한의 REVIEWER 흡수(ADR-003), 업로더·프로젝트 개념 폐기(ADR-001/004). UI 호칭은 '검수자'로 통일하고 관리 화면은 두 경로로 갈린다 — 라벨 마스터·프리셋·이벤트유형·비식별 신고 관리와 배치·추론·정밀도·비식별 설정은 /manage/* 에, 사용자 관리·연동 서버 주소·업로드·위험 액션·관리자 패스워드 교체는 /admin/* 에 있다.

[권한 판정 축] 역할은 저작도구 소유 테이블 LS_USER_ROLE(V75)이 단독으로 갖는다. 관제 공유 계정권한 테이블 2종은 런타임 참조 0 인 죽은 테이블이어서 V165 로 삭제됐다. 접근 제어는 인계 JWT 의 role + channel 클레임으로 분기하고 메서드 단위 권한 검증으로 강제한다(내부 채널 vs 포털 채널). 관리 성격의 쓰기에는 그 위에 관리자 단기 유효창이 가산된다 — 인가는 대체되지 않고 더해지므로 유효창이 열려 있어도 원래 필요한 역할은 그대로 요구된다.

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

### module_paths

_(empty)_

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
