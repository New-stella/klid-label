---
logicraft_item: DFEAT-003
type: domain_feature
version: 9
domain: DOMAIN-001
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-29T01:27:28.494Z
status: CHANGED
prev_version: 9
content_hash: 5769bb0afb373735784de9c42e8da99dbf9d4a1b6ad40f6e939d4674cf84b356
stale: true
raw: ./_raw/DFEAT-003.json
links:
  belongs_to_domain: ["[[DOMAIN-001]]"]
  implements: ["[[API-001]]", "[[API-002]]", "[[API-003]]", "[[API-004]]", "[[API-005]]"]
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

검수자(REVIEWER)

### i_want

사용자 계정과 역할을 관리하기를

### so_that

조직 구성에 맞게 권한을 운영한다

## description

REVIEWER 가 사용자 목록을 조회하고 역할을 부여·변경한다(별도 ADMIN 역할 없음, ADR-003).

[★사용자는 어디서 오는가 (ADR-043)] 저작도구에는 회원가입이 없고 JWT 클레임에도 이름이 없다(sub/role/channel/exp 뿐). 기존엔 관제 공유 계정 테이블을 조인해 이름을 얻었으나 그 테이블을 채우는 코드가 양쪽 어느 곳에도 없어 사실상 비어 있었다(실측). 앞으로는 관제가 브라우저 localStorage 에 넣어주는 userId·userNm 을 받아 역할 클레임 시점에 우리 사용자 테이블로 upsert 한다 — 사용자를 만드는 주체가 관제 DB 가 아니라 이 클레임 흐름이 된다.

[등록 시점 = 역할 클레임 때만] 모든 접속 시가 아니다. 인증된 경로로만 사용자가 생기고 역할 없는 허수 행이 안 쌓인다. userNo 는 JWT sub 에서 오므로 위조 불가하고, userId/userNm 은 표시용이라 위조해도 자기 행 이름만 바뀐다.

[영향 API] GET /v1/users(목록) · /v1/users/workers(배정 대상) · /v1/users/me · /v1/users/{userNo} + 타인 이름 표시(배정·검수·통계·이슈스레드). LS_TASK_ALTMNT 은 userNo 만 저장하므로 이름 표시에 사용자 테이블이 필요하다.

[자가부여 범위] 자가부여 화이트리스트에 WORKER 와 REVIEWER 가 들어간다. 관리자 비밀번호(BCrypt)를 아는 사람은 누구나 검수자가 될 수 있으며, 그 비밀번호의 관리 수준이 시스템 전체 권한 경계다 — 사용자가 트레이드오프를 명시적으로 수용한 결정이다.

[역할 변경의 요건] 역할을 부여·변경하는 것은 운영·관리 성격의 쓰기라 검수자 권한에 관리자 단기 유효창이 가산된다. 유효창은 역할을 올리지 않으므로 검수자 권한은 그대로 필요하다. 조회는 그 대상이 아니다 — 목록·단건 조회와 배정 대상 조회는 검수자 권한만으로 되며, 조회까지 막으면 작업 배정 흐름이 끊긴다.

(1차 baseline: 관리자가 사용자 목록 조회·역할/권한 부여, 화면 SKKLID-UI-03-05-01)

## invokes_apis

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
