---
logicraft_item: DFEAT-001
type: domain_feature
version: 8
domain: DOMAIN-001
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-27T20:54:27.189Z
status: CHANGED
prev_version: 4
content_hash: 5c6dd6b1382c2eff9d22a3e8fe5739c4e5d1e9925c5d7a77e4caab395a4d5a93
stale: true
raw: ./_raw/DFEAT-001.json
links:
  belongs_to_domain: ["[[DOMAIN-001]]"]
  implements: ["[[API-006]]", "[[API-007]]", "[[API-153]]"]
  migrated_from: ["[[LEGACY-001]]"]
  depicts_backward: ["[[CDIAG-008]]", "[[CMP-012]]"]
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

[채널별 배포 + 채널별 토큰 인계 수단 (ADR-012)] 저작도구를 관제·포털 각 채널에 별도 배포한다. 달라지는 것은 토큰을 인계받는 수단이며 채널마다 갈린다. 관제 채널은 호스트와 동일 origin 을 공유하므로 브라우저 스토리지(localStorage/sessionStorage)로 JWT 를 인계받는다. 포털 채널은 호스트가 주입한 인계 창구로 토큰을 얻고(획득·갱신·인증 실패 통지·활동 통지) API 호출 시 전용 요청 헤더로 실어 보내며, access token 은 호스트 메모리에만 두고 브라우저 저장소를 쓰지 않는다. 인계 계층은 저장소를 직접 읽는 대신 토큰을 얻는 창구 뒤로 추상화해 두 수단을 호출부 분기 없이 지원한다. 포털 채널 창구의 이름·마운트 경로·요청 헤더 이름 등 상세 규약은 INT-013 이 소유한다. 두 채널 모두 URL 쿼리 파라미터(?token=) 방식은 노출 위험으로 쓰지 않고, 저작도구가 토큰을 발급하지 않으며, 동일 JWT 발급 서버라 검증 로직은 단일이다(토큰 필터 → 클레임 추출 → 보안 컨텍스트). 세션 만료 시 각 상위 시스템 로그인 페이지로 리다이렉트한다.

[역할 클레임 화면으로 이어짐] 인증은 됐으나 role 클레임이 비어 있으면 역할 클레임 화면(SCREEN-002, API-007)으로 진입해 관리자 공유 패스워드로 역할을 자가부여받는다 — 이 시점에 사용자 마스터가 자동등록된다(ADR-043). role-claim 은 로그인 흐름의 연속이며 별도 회원가입이 없다.

[관리 기능 진입은 인계 위에 자체 확인이 가산된다] 토큰을 인계받아 진입한다는 위 성질은 그대로이고, 관리 기능에 들어갈 때만 그 위에 자체 확인이 한 겹 더 얹힌다. 관리자 공유 패스워드를 대조해 통과하면 그 사람에게 잠깐 열리는 유효창을 발급하고, 관리 기능의 수행은 그 유효창이 살아 있는 동안으로 한정한다. 유효기간은 기본 10분이며 늘려 잡더라도 30분을 넘기지 못한다. 발급된 유효창은 대조를 통과한 사용자 본인에게 묶여 다른 사용자가 가져다 쓸 수 없다. 저작도구가 스스로 발급하고 검증하고 만료시키는 세션은 이것뿐이다. 위 문단이 적은 대로 저작도구는 여전히 로그인 토큰을 발급하지 않는다 — 이 유효창은 로그인 수단이 아니라, 이미 인계받은 토큰으로 인증된 사용자에게 관리 기능만 잠깐 열어 주는 확인 절차이기 때문이다.

유효창은 인가를 대체하지 않고 가산한다 — 역할을 올려 주지 않으므로 인계 토큰이 부여한 역할 권한이 그대로 필요하고, 유효창만 들고서는 아무것도 열리지 않는다.

대조하는 자격 자체는 운영 중에 교체할 수 있다. 교체하면 그 전에 발급된 유효창은 일제히 무효가 되는데, 무효가 된 유효창을 따로 적어 두는 장부를 두지 않고 유효창의 서명이 현재 자격에 의존하도록 만들어 이룬다 — 자격이 바뀌면 옛 서명은 스스로 검증에 실패한다.

조회에는 유효창을 요구하지 않는다. 관리 화면의 목록·상세는 물론이고 다른 업무 화면이 사용자 정보를 읽는 경로도 인계 토큰의 역할 권한만으로 열린다.

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

### module_paths

_(empty)_

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
