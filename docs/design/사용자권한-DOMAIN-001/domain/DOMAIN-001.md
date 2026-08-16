---
logicraft_item: DOMAIN-001
type: domain
version: 9
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:50.327Z
status: NEW
prev_version: null
content_hash: 2f56659313ce9650da9bb9c25f2b591c94ab0bfaedca703168697e7ade1235cd
stale: true
raw: ./_raw/DOMAIN-001.json
links:
  collaborates_with: ["[[DOMAIN-005]]"]
  applies_to_backward: ["[[NFR-013]]", "[[NFR-020]]"]
  belongs_to_domain_backward: ["[[ADR-003]]", "[[ADR-012]]", "[[ADR-021]]", "[[ADR-043]]", "[[API-001]]", "[[API-002]]", "[[API-003]]", "[[API-004]]", "[[API-005]]", "[[API-006]]", "[[API-007]]", "[[API-153]]", "[[CDIAG-008]]", "[[DFEAT-001]]", "[[DFEAT-002]]", "[[DFEAT-003]]", "[[ERD-029]]", "[[SCREEN-001]]", "[[SCREEN-002]]", "[[SCREEN-003]]", "[[SCREEN-004]]", "[[SCREEN-024]]", "[[SD-009]]", "[[SEQ-018]]", "[[UC-030]]"]
  collaborates_with_backward: ["[[DOMAIN-005]]", "[[DOMAIN-009]]"]
  implements_in_backward: ["[[MOD-001]]", "[[MOD-002]]", "[[MOD-024]]", "[[MOD-025]]", "[[MOD-030]]", "[[MOD-039]]", "[[MOD-040]]", "[[MOD-048]]"]
---

# 사용자·권한

## name

사용자·권한

## brownfield

### notes

1차(SweetK) 시스템 도메인. Pass 2 역할 6→3 통합은 DFEAT-002·역할 카탈로그(LEGACY-007~012)에 반영됨

### status

preserved

## description

사용자 식별·역할 기반 접근제어(RBAC)·메뉴별 접근 권한을 담당하는 도메인. 영상/작업 단위 권한 배정(REVIEWER→WORKER 할당)의 실현체는 DOMAIN-015(작업 배정)에 있다 — 이 도메인은 그 배정에 쓰이는 역할·권한 축만 정의한다.

[인증 — 독립 로그인 UI 없음] 저작도구는 자체 로그인을 가지지 않고 관제서버(내부)·포털서버(외부)가 발급한 JWT 를 인계받아 검증한다(토큰 필터 → 클레임 추출 → 보안 컨텍스트 적재 순으로 처리). 관제와 동일 도메인 운영이라 브라우저 스토리지 공유로 토큰을 받으며 URL 쿼리파라미터 방식은 쓰지 않는다. 두 채널 모두 동일 발급 서버라 검증 로직은 단일이고, role + channel 클레임으로 권한을 분기한다. 세션 만료 시 각 상위 시스템 로그인 페이지로 리다이렉트한다.
(1차 SweetK 시스템의 GPKI 연동은 2차 구조가 아니다 — brownfield 맥락으로만 유효.)

[역할 3종] REVIEWER(검수자) · WORKER(라벨링 작업자) · PORTAL_USER(포털 회원). 별도의 시스템 관리자(ADMIN) 역할은 없으며 모든 관리 권한이 REVIEWER 에 통합돼 있다(ADR-003). 1차의 '업로더' 역할은 폐기됐다 — 영상 1차 적재는 사람이 올리는 것이 아니라 관제 인입이고(ADR-042), 생성 AI 업로더 검토는 외부화됐다(ADR-004).

[역할축의 단독 소유자] 역할은 저작도구 소유 테이블 LS_USER_ROLE(V75)가 단독으로 갖는다. 관제 공유 계정권한 테이블 2종은 런타임 참조 0 인 죽은 테이블이어서 V165 로 삭제됐다.

[★사용자 마스터 — 역할 클레임 시점 자동등록 (ADR-043)] 기존엔 관제 공유 테이블을 조인해 사용자명을 얻었으나, 그 테이블을 채우는 코드가 양쪽 어느 곳에도 없어 사실상 비어 있었다. 앞으로는 관제가 브라우저 localStorage 에 넣어주는 userId·userNm 을 받아 역할 클레임 시점에 우리 사용자 테이블로 upsert 한다. 등록 시점을 '모든 접속'이 아닌 '역할 클레임'으로 잡는 이유는 인증된 경로로만 사용자가 생기고 역할 없는 허수 행이 안 쌓이게 하기 위해서다. userNo 는 JWT sub 에서 오므로 위조 불가하고, userId/userNm 은 표시용이라 위조해도 자기 행 이름만 바뀐다.

[★REVIEWER 자가부여 허용 — 인지·수용된 잔여 위험] 자가부여 가능 역할 화이트리스트에 REVIEWER 를 포함한다. 관리자 비밀번호를 아는 사람은 누구나 검수자가 될 수 있으며, 그 비밀번호의 관리 수준이 시스템 전체의 권한 경계다. 사용자가 트레이드오프를 명시적으로 제시받고 선택한 인지·수용된 잔여 위험이다. 이로써 온프렘 부트스트랩 문제도 해소된다(기존엔 dev 편의 경로를 운영 부트스트랩으로 안내하던 결함이 있었다).

## upstream_of

_(empty)_

## context_kind

supporting

## collaborators

- DOMAIN-005

## integrates_with

_(empty)_

## uses_integrations

_(empty)_

## ubiquitous_language

### [1]

- **term**: 검수자(REVIEWER)
- **meaning**: 작업 결과를 검토해 승인/반려하며 사용자 관리·시스템 설정·작업 배정 권한까지 통합 보유하는 역할. UI 호칭은 '검수자'로 통일하고 관리 화면 URL 은 /manage/*

### [2]

- **term**: 라벨링 작업자(WORKER)
- **meaning**: 배정된 영상의 프레임에 라벨링을 수행하고 검수를 제출하는 역할

### [3]

- **term**: 포털 회원(PORTAL_USER)
- **meaning**: 데이터마트 영상 선택·기존 라벨 확인·본인 자산 업로드와 수동 라벨링을 수행. 오토라벨링·검수·버전관리는 없다

### [4]

- **term**: 인계 토큰
- **meaning**: 관제·포털이 발급해 브라우저 스토리지로 넘겨주는 JWT. 저작도구는 검증만 하고 발급하지 않는다

### [5]

- **term**: 역할 클레임
- **meaning**: 관리자 비밀번호 검증 후 자신에게 역할을 부여하는 행위. 이 시점에 사용자 행이 자동 등록된다(ADR-043)

### [6]

- **term**: 작업 배정
- **meaning**: REVIEWER 가 WORKER 에게 영상 단위로 작업을 할당하는 행위(별도 배정 담당자 역할 없음)
