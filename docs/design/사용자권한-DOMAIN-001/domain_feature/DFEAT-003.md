---
logicraft_item: DFEAT-003
type: domain_feature
version: 11
domain: DOMAIN-001
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-07T15:15:46.259Z
status: CHANGED
prev_version: 10
content_hash: 4dfebe96f52c2b917ec7c38cd3494b0cba4462984eb4acc8d81c2fb9120c161c
stale: true
raw: ./_raw/DFEAT-003.json
links:
  based_on: ["[[ADR-055]]"]
  belongs_to_domain: ["[[DOMAIN-001]]"]
  implements: ["[[API-001]]", "[[API-002]]", "[[API-003]]", "[[API-004]]", "[[API-005]]", "[[IMPREC-349]]"]
  migrated_from: ["[[LEGACY-125]]"]
  depicts_backward: ["[[CDIAG-008]]", "[[CMP-012]]"]
  realizes_backward: ["[[UC-030]]"]
  references_backward: ["[[CDIAG-008]]"]
---

# 사용자 관리

## title

사용자 관리

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

### decided_by

ADR-055

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-03-05-01

#### legacy_artifact_id

LEGACY-125

## user_story

### as

관리자(ADMIN)

### i_want

사용자 계정과 역할을 관리하기를

### so_that

조직 구성에 맞게 권한을 운영한다

## description

관리자가 사용자 목록을 조회하고 역할을 부여·변경한다. 역할은 ADMIN·REVIEWER·WORKER·PORTAL_USER 4종이고 계층은 관리자 > 검수자 한 단계뿐이다(ADR-055).

[★사용자는 어디서 오는가] 저작도구에는 회원가입이 없다. 사용자 행을 만드는 주체는 관제 공유 계정 테이블이 아니라 이 진입 흐름이다 — 기존엔 그 공유 테이블을 조인해 이름을 얻었으나 그것을 채우는 코드가 양쪽 어느 곳에도 없어 사실상 비어 있었다. 인계 토큰은 주체 식별자·발급처·역할·채널·이름·만료를 싣고 오며 이름 클레임도 함께 온다(ADR-063). 다만 표시 이름의 진실원은 저작도구가 보관한 값이고 진입 시 조회하는 「내 정보」 응답이 그것을 내준다 — 인계 토큰의 이름 클레임은 보조 조달원이라, 토큰에 이름이 실려 오지 않아도 서버가 아는 이름이 표시된다(SHELL-001).

[등록 시점 = 내부 채널 진입 시점] 인증을 통과한 내부 채널 진입자에게 역할이 없으면 그 시점에 사용자 행과 작업자 역할을 만든다(조건부·원자 등록, 사용자당 사실상 1회). 이미 부여된 역할은 덮어쓰지 않는다 — 관리자·검수자가 다시 들어와도 작업자로 내려가지 않는다. userNo 는 인계 토큰의 주체 식별자에서 오고, 그 토큰은 서명 검증과 만료 검사를 통과한 것이라 위조할 수 없다. 이름도 같은 토큰이 나르지만 표시용이라 인가에 쓰지 않으므로, 값이 어긋나도 영향은 자기 행의 표시명 하나뿐이다. 주체 식별자가 사용자 번호 형태가 아닌 경우를 위해 별도 식별자 클레임으로 마스터를 찾고 그래도 찾지 못하면 로컬 식별 레코드를 원자적으로 발급하는 갈래가 규격에 있으며, 그 발급 자체는 역할을 부여하지 않는다(ADR-063). 부수 이득으로 배정하려면 대상자가 먼저 역할을 스스로 받아야 한다는 제약이 사라진다.

[영향 API] GET /v1/users(목록) · /v1/users/workers(배정 대상) · /v1/users/me · /v1/users/{userNo} + 타인 이름 표시(배정·검수·통계·이슈스레드). LS_TASK_ALTMNT 은 userNo 만 저장하므로 이름 표시에 사용자 테이블이 필요하다.

[자가부여 = 관리자 부트스트랩 전용] 자가부여는 관리자가 0명일 때만 열리고 부여 역할은 ADMIN 고정이다. 관리자가 한 명이라도 생기면 창구가 닫히며 그 뒤의 역할 부여는 관리자가 사용자 관리 화면에서 한다. 검수자나 작업자를 자가부여로 얻는 경로는 없다.

[역할 변경의 요건] 역할을 부여·변경하는 것은 관리자 전용 쓰기라 관리자 권한에 관리자 단기 유효창이 가산된다. 유효창은 역할을 올리지 않으므로 관리자 권한은 그대로 필요하다. 조회는 그 대상이 아니다 — 목록·단건 조회와 배정 대상 조회는 검수자 권한만으로 되며(관리자는 계층으로 그 권한을 물려받는다), 조회까지 막으면 작업 배정 흐름이 끊긴다.

[마지막 관리자 보호] 마지막 관리자를 강등하거나 비활성화하는 요청은 거부한다. 관리자가 0명이 되면 부트스트랩 창구가 다시 열려 아무나 관리자가 될 수 있기 때문이다.

(1차 baseline: 관리자가 사용자 목록 조회·역할/권한 부여, 화면 SKKLID-UI-03-05-01)

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

- IMPREC-349

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:26:19.558Z

### module_paths

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_ACNT_USER
- LS_USER_ROLE

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-001
- API-002
- API-003
- API-004
- API-005

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
