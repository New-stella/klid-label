---
logicraft_item: DFEAT-001
type: domain_feature
version: 4
domain: DOMAIN-001
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T01:11:02.328Z
status: CHANGED
prev_version: 3
content_hash: 5a514898f89e4197bd856cc9be52ca7c15a229d65318b7523abe00621fde24b1
stale: false
raw: ./_raw/DFEAT-001.json
links:
  belongs_to_domain: ["[[DOMAIN-001]]"]
  implements: ["[[API-006]]", "[[API-007]]", "[[API-153]]"]
  migrated_from: ["[[LEGACY-001]]"]
  depicts_backward: ["[[CDIAG-008]]"]
  references_backward: ["[[CDIAG-008]]"]
---

# 외부 JWT 인계 로그인 (독립 로그인 UI 없음)

## title

외부 JWT 인계 로그인 (독립 로그인 UI 없음)

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

preserved

### legacy_source

#### repo

KLID-AI-PF-001

#### type

module

#### identifier

KLID-AI-PF-001

#### legacy_artifact_id

LEGACY-001

## user_story

### as

저작도구 사용자

### i_want

상위 시스템(관제/포털)에서 로그인한 세션으로 별도 재로그인 없이 진입하기를

### so_that

단일 인증으로 도구를 사용할 수 있다

## description

저작도구는 자체 로그인 화면이 없으며, 관제서버(내부)·포털(외부)이 발급한 JWT 토큰을 인계받아 진입한다. 토큰의 role·channel 클레임으로 사용자/권한을 식별한다.

[채널별 배포 + 동일 origin 스토리지 공유 (ADR-012)] 저작도구를 관제·포털 각 채널에 별도 배포하고 각 인스턴스가 호스트와 동일 origin 을 공유해 브라우저 스토리지(localStorage/sessionStorage)로 JWT 를 인계받는다. URL 쿼리 파라미터(?token=) 방식은 노출 위험으로 쓰지 않는다. 두 채널 모두 동일 JWT 발급 서버라 검증 로직은 단일이다(토큰 필터 → 클레임 추출 → 보안 컨텍스트). 세션 만료 시 각 상위 시스템 로그인 페이지로 리다이렉트한다.

[역할 클레임 화면으로 이어짐] 인증은 됐으나 role 클레임이 비어 있으면 역할 클레임 화면(SCREEN-002, API-007)으로 진입해 관리자 공유 패스워드로 역할을 자가부여받는다 — 이 시점에 사용자 마스터가 자동등록된다(ADR-043). role-claim 은 로그인 흐름의 연속이며 별도 회원가입이 없다.

[dev 로그인 예외 (ADR-039)] 관제 미기동 폐쇄망 브링업 시나리오에서는 dev 로그인(API-153, POST /v1/dev/tokens)으로 같은 흐름을 재현한다. env 토글로 제어되며 기본 OFF, local/dev 프로파일만 기본 ON — stg/prd 는 토글이 켜진 채 기동을 시도하면 부팅 자체를 거부한다(fail-closed).

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

2026-05-30T02:33:01.456Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_ACNT_USER
- LS_AUTHRT_GRANT_ATMPT

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-006
- API-007
- API-153

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
