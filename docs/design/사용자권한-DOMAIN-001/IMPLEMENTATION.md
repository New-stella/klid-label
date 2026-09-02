# DOMAIN-001 사용자·권한 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(69건) · 버전은 `version-master.md`.
> ⚠ **아래 「빌드 순서」·「구현 현황」·「ITEM 인덱스」 세 표는 2026-08-18 스코프로 얼어 있다.** 키트 다운로더는 이 파일을 재생성하지 않아 SYNC 를 돌려도 따라오지 않는다 — 그 뒤 pin 에 편입된 ITEM(`ROLE-004`·`ADR-055` 등)이 표에 없고, 2026-08-31 재번호로 폐기된 3자리 AC 링크가 남아 있다. **있는 항목은 참이나 전수는 아니므로, 스코프 판정은 `.kit-scope.json` 으로 한다.** 본문 산문은 2026-09-01 에 폐기 결정 기준으로 정정했다.

## 도메인 (bounded context)

사용자 식별·역할 기반 접근제어(RBAC)·메뉴별 접근 권한을 담당하는 도메인. 영상/작업 단위 권한 배정(REVIEWER→WORKER 할당)의 실현체는 DOMAIN-015(작업 배정)에 있다 — 이 도메인은 그 배정에 쓰이는 역할·권한 축만 정의한다.

[인증 — 독립 로그인 UI 없음] 저작도구는 자체 로그인을 가지지 않고 관제서버(내부)·포털서버(외부)가 발급한 JWT 를 인계받아 검증한다(토큰 필터 → 클레임 추출 → 보안 컨텍스트 적재 순으로 처리). 토큰 인계 수단은 채널마다 갈린다(ADR-012 개정) — 관제 채널은 동일 도메인 운영이라 브라우저 스토리지 공유로 받고, 포털 채널은 Host 가 주입한 인계 창구로 받아 헤더에 싣는다(브라우저 저장소 미사용). 두 채널 모두 URL 쿼리파라미터 방식은 쓰지 않는다. 두 채널 모두 동일 발급 서버라 검증 로직은 단일이고, role + channel 클레임으로 권한을 분기한다. 세션 만료 시 각 상위 시스템 로그인 페이지로 리다이렉트한다.
(1차 SweetK 시스템의 GPKI 연동은 2차 구조가 아니다 — brownfield 맥락으로만 유효.)

[역할 4종 + 계층] ADMIN(관리자) · REVIEWER(검수자) · WORKER(라벨링 작업자) · PORTAL_USER(포털 회원)이며 계층은 ROLE_ADMIN > ROLE_REVIEWER 한 단계뿐이다(ADR-055). 관리자가 검수자 권한을 물려받으므로 겸직을 위해 계정을 둘 가질 필요가 없다. ⚠ 구 서술 폐기 — *별도의 시스템 관리자(ADMIN) 역할은 없으며 모든 관리 권한이 REVIEWER 에 통합돼 있다(ADR-003)* 는 더 이상 사실이 아니다. 그 결정은 ADR-055 가 supersede 했고 ADR-003·ADR-043 은 폐기돼 이 키트에 없다. 1차의 '업로더' 역할은 폐기됐다 — 영상 1차 적재는 사람이 올리는 것이 아니라 관제 인입이고(ADR-042), 생성 AI 업로더 검토는 외부화됐다(ADR-004).

[역할축의 단독 소유자] 역할은 저작도구 소유 테이블 LS_USER_ROLE(V75)가 단독으로 갖는다. 관제 공유 계정권한 테이블 2종은 런타임 참조 0 인 죽은 테이블이어서 V165 로 삭제됐다.

[★사용자 마스터 — 내부 채널 진입 시점 자동등록 (ADR-055)] 기존엔 관제 공유 테이블을 조인해 사용자명을 얻었으나, 그 테이블을 채우는 코드가 양쪽 어느 곳에도 없어 사실상 비어 있었다. 앞으로는 관제가 브라우저 localStorage 에 넣어주는 userId·userNm 을 받아 우리 사용자 테이블로 upsert 한다. ⚠ 등록 시점이 바뀌었다 — ADR-043 은 '역할 클레임 시점'으로 잡았으나 ADR-055 가 그것을 supersede 해 **역할이 없는 INTERNAL 진입자를 작업자로 자동 등록**한다(조건부·원자 upsert, 이미 부여된 역할은 덮어쓰지 않는다). 그 반전으로 '배정하려면 대상자가 먼저 자가부여를 해야 한다'는 제약이 풀린다. userNo 는 JWT sub 에서 오므로 위조 불가하고, userId/userNm 은 표시용이라 위조해도 자기 행 이름만 바뀐다.

[★REVIEWER 자가부여 허용 — 인지·수용된 잔여 위험] 자가부여 가능 역할 화이트리스트에 REVIEWER 를 포함한다. 관리자 비밀번호를 아는 사람은 누구나 검수자가 될 수 있으며, 그 비밀번호의 관리 수준이 시스템 전체의 권한 경계다. 사용자가 트레이드오프를 명시적으로 제시받고 선택한 인지·수용된 잔여 위험이다. 이로써 온프렘 부트스트랩 문제도 해소된다(기존엔 dev 편의 경로를 운영 부트스트랩으로 안내하던 결함이 있었다).

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| 검수자(REVIEWER) | 작업 결과를 검토해 승인/반려하며 사용자 관리·시스템 설정·작업 배정 권한까지 통합 보유하는 역할. UI 호칭은 '검수자'로 통일하고 관리 화면 URL 은 /manage/* |
| 라벨링 작업자(WORKER) | 배정된 영상의 프레임에 라벨링을 수행하고 검수를 제출하는 역할 |
| 포털 회원(PORTAL_USER) | 데이터마트 영상 선택·기존 라벨 확인·본인 자산 업로드와 수동 라벨링을 수행. 오토라벨링·검수·버전관리는 없다 |
| 인계 토큰 | 관제·포털이 발급해 브라우저 스토리지로 넘겨주는 JWT. 저작도구는 검증만 하고 발급하지 않는다 |
| 역할 클레임 | 관리자 비밀번호 검증 후 자신에게 역할을 부여하는 행위. **관리자 부트스트랩 전용**이라 ADMIN 이 0명일 때만 열리고 부여 역할은 ADMIN 고정이며, 한 명이라도 생기면 닫힌다(ADR-055) |
| 작업 배정 | REVIEWER 가 WORKER 에게 영상 단위로 작업을 할당하는 행위(별도 배정 담당자 역할 없음) |

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-001]] · [[ADR-004]] · [[ADR-012]] · [[ADR-021]] · [[ADR-039]] · [[ADR-042]] · [[ADR-055]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | ERD 데이터 계층 | [[ERD-029]] |
| 4 | API 경계 계약 | [[API-001]] · [[API-002]] · [[API-003]] · [[API-004]] · [[API-005]] · [[API-006]] · [[API-007]] · [[API-153]] |
| 5 | DFEAT 비즈니스 로직 | [[DFEAT-001]] · [[DFEAT-002]] · [[DFEAT-003]] |
| 6 | SEQ 흐름 배선 | [[SEQ-018]] |
| 7 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 8 | SCREEN 화면 | [[SCREEN-001]] · [[SCREEN-002]] · [[SCREEN-003]] · [[SCREEN-004]] · [[SCREEN-012]] · [[SCREEN-020]] · [[SCREEN-024]] |
| 9 | UC 검증 | [[UC-030]] |
| 10 | CDIAG 클래스 구조 | [[CDIAG-008]] |
| 11 | SD 고충실 시안 | [[SD-009]] · [[SD-017]] · [[SD-018]] · [[SD-019]] · [[SD-020]] |

> ⚠ 이번 키트에 **0건**인 단계: CONST 상수값, EVT 이벤트 계약, AC 수용, TEST 통합시험, C4 컴포넌트, INT 외부 연동, FEAT 상위 기능 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| planned | 20 |
| implemented | 18 |
| (미기재) | 15 |

| ITEM | type | status | progress |
|---|---|---|---|
| [[API-001]] | api_endpoint | implemented | 100 |
| [[API-002]] | api_endpoint | implemented | 100 |
| [[API-003]] | api_endpoint | implemented | 100 |
| [[API-004]] | api_endpoint | implemented | 100 |
| [[API-005]] | api_endpoint | implemented | 100 |
| [[API-006]] | api_endpoint | implemented | 100 |
| [[API-007]] | api_endpoint | implemented | 100 |
| [[API-153]] | api_endpoint | implemented | 100 |
| [[DFEAT-001]] | domain_feature | implemented | 100 |
| [[DFEAT-002]] | domain_feature | implemented | 100 |
| [[DFEAT-003]] | domain_feature | implemented | 100 |
| [[SCREEN-001]] | screen_spec | implemented | 100 |
| [[SCREEN-002]] | screen_spec | implemented | 100 |
| [[SCREEN-003]] | screen_spec | implemented | 100 |
| [[SCREEN-004]] | screen_spec | implemented | 100 |
| [[SCREEN-012]] | screen_spec | implemented | 100 |
| [[SCREEN-020]] | screen_spec | implemented | 100 |
| [[SCREEN-024]] | screen_spec | implemented | 100 |
| [[ERD-029]] | erd | planned | 0 |
| [[NFR-008]] | nfr | planned | 0 |
| [[NFR-009]] | nfr | planned | 0 |
| [[NFR-010]] | nfr | planned | 0 |
| [[NFR-011]] | nfr | planned | 0 |
| [[NFR-012]] | nfr | planned | 0 |
| [[NFR-013]] | nfr | planned | 0 |
| [[NFR-014]] | nfr | planned | 0 |
| [[NFR-015]] | nfr | planned | 0 |
| [[NFR-016]] | nfr | planned | 0 |
| [[NFR-017]] | nfr | planned | 0 |
| [[NFR-018]] | nfr | planned | 0 |
| [[NFR-019]] | nfr | planned | 0 |
| [[NFR-020]] | nfr | planned | 0 |
| [[NFR-021]] | nfr | planned | 0 |
| [[ROLE-001]] | permission_role | planned | 0 |
| [[ROLE-002]] | permission_role | planned | 0 |
| [[ROLE-003]] | permission_role | planned | 0 |
| [[SEQ-018]] | diagram_sequence | planned | 0 |
| [[UC-030]] | use_case | planned | 0 |

> ⚠ 이 표는 **설계가 스스로 적은 주장**이다. 코드와 대조되지 않았다 — 그 대조가 `/mc-logi-implement-review` 의 몫이다.

## ITEM 인덱스

### adr (8)
- [[ADR-001]] — 작업 단위를 프로젝트에서 영상 1건(RAW_SN)으로 전환
- [[ADR-055]] — 역할 4종 + 계층(ROLE_ADMIN > ROLE_REVIEWER) — ADR-003·ADR-043 supersede
- [[ADR-004]] — 생성형 AI 본체 외부화 — 저작도구는 증강 결과 검수만
- [[ADR-012]] — 저작도구 채널별 별도 배포 — 동일 origin 스토리지 JWT 공유로 인증/진입
- [[ADR-021]] — 역할 판정 진실원을 관제 공유 MNG_ACCT_*에서 저작도구 자체 LS_USER_ROLE로 재전환 (ADR-017 supersede)
- [[ADR-039]] — dev 로그인/dev 업로드 — prd 빌드 env 토글 허용(기본 OFF, fail-closed)
- [[ADR-042]] — 관제 데이터 참조 전면 제거 — MNG_* 9종 삭제 + LS_DATA_INGEST 평면 수신

### nfr (14)
- [[NFR-008]] — 학습데이터 단계별 품질관리 기준 (수집·제작·검수)
- [[NFR-009]] — 학습데이터 종류·제작방법별 품질관리 기준
- [[NFR-010]] — 학습데이터 값 검증·정합성 (공공데이터 품질진단 기준)
- [[NFR-011]] — 화면 응답시간 기준
- [[NFR-012]] — 시스템 자원 효율
- [[NFR-013]] — 세션·계정 보안대책
- [[NFR-014]] — 시큐어코딩 (SW 개발보안)
- [[NFR-015]] — 웹표준·크로스브라우징
- [[NFR-016]] — 데이터 표준 준수
- [[NFR-017]] — API 호출 규약 (Base URL /api · Bearer JWT 인증 · 채널 격리)
- [[NFR-018]] — 기능 수행 지연 사전 안내
- [[NFR-019]] — 오류 응답 속도
- [[NFR-020]] — 역할별 접근제어
- [[NFR-021]] — 취약점 점검·모의해킹

### erd (1)
- [[ERD-029]] — 사용자·권한 ERD (고도화, PostgreSQL — LS_ACNT_USER, LS_USER_ROLE, LS_AUTHRT_GRANT_ATMPT)

### api_endpoint (8)
- [[API-001]] — GET /v1/users
- [[API-002]] — GET /v1/users/workers
- [[API-003]] — GET /v1/users/{userNo}
- [[API-004]] — PATCH /v1/users/{userNo}
- [[API-005]] — GET /v1/users/me
- [[API-006]] — GET /v1/me
- [[API-007]] — POST /v1/auth/role-claim
- [[API-153]] — POST /v1/dev/tokens

### domain_feature (3)
- [[DFEAT-001]] — 외부 JWT 인계 로그인 (독립 로그인 UI 없음)
- [[DFEAT-002]] — 역할별 메뉴·접근제어
- [[DFEAT-003]] — 사용자 관리

### diagram_sequence (1)
- [[SEQ-018]] — 사용자 계정·역할 관리 시퀀스

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (7)
- [[SCREEN-001]] — 세션 인계 진입 화면
- [[SCREEN-002]] — 역할 클레임 화면
- [[SCREEN-003]] — 접근 거부 화면
- [[SCREEN-004]] — 개발용 로그인 화면
- [[SCREEN-012]] — 작업 목록 화면
- [[SCREEN-020]] — 작업자 통계 화면
- [[SCREEN-024]] — 사용자 관리 화면

### use_case (1)
- [[UC-030]] — 사용자 계정·역할 관리

### class_diagram (1)
- [[CDIAG-008]] — 사용자·권한 도메인 모델 (개념 모델 — ERD 없음)

### screen_design (5)
- [[SD-009]] — SCREEN-024 사용자 관리 화면
- [[SD-017]] — SCREEN-001 세션 인계 진입 화면
- [[SD-018]] — SCREEN-002 역할 클레임 화면
- [[SD-019]] — SCREEN-003 접근 거부 화면
- [[SD-020]] — SCREEN-004 개발용 로그인 화면
