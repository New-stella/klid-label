# DOMAIN-009 게시판·공지 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(46건) · 버전은 `version-master.md`.
> ⚠ **아래 「빌드 순서」·「구현 현황」·「ITEM 인덱스」 세 표는 2026-08-18 스코프로 얼어 있다.** 키트 다운로더는 이 파일을 재생성하지 않아 SYNC 를 돌려도 따라오지 않는다 — 그 뒤 pin 에 편입된 ITEM(`ROLE-004`·`ADR-055` 등)이 표에 없고, 2026-08-31 재번호로 폐기된 3자리 AC 링크가 남아 있다. **있는 항목은 참이나 전수는 아니므로, 스코프 판정은 `.kit-scope.json` 으로 한다.** 본문 산문은 2026-09-01 에 폐기 결정 기준으로 정정했다.

## 도메인 (bounded context)

공지사항 작성·관리, 게시글 목록·상세 조회. 작성·수정·발행·삭제와 첨부파일 관리는 검수자 전용이고, 작업자는 발행된 게시글만 볼 수 있다. 게시글은 작성 시 임시저장(DRAFT) 상태로 만들어지며 발행(PUBLISHED)해야 작업자에게 노출되고, 발행을 취소하면 다시 임시저장 상태로 돌아간다. 목록은 상단 고정 게시글을 먼저 보여준 뒤 등록일 내림차순으로 정렬한다. 이 도메인이 지는 책임은 게시글 작성·수정·삭제, 발행과 발행취소, 첨부파일 업로드·다운로드·삭제, 검색 대상(제목·본문·전체)과 검색어를 받는 목록 조회, 첨부 목록을 포함한 상세 조회다. 첨부파일은 게시글이 저장되어 식별자가 발급된 뒤에만 다룰 수 있어 작성 화면에는 첨부 영역이 없고 수정 화면에서만 관리한다. 반대로 이 도메인이 지지 않는 책임은 분류·댓글·조회수·구독 알림이며, 게시글은 제목·본문·상단 고정 여부·발행 상태·첨부파일만 가진다. 게시판·공지는 발주처 요구사항 baseline(R1) 외 추가로 재도입한 기능이라 대응하는 상위 요구사항이 없다.

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| 공지 | 검수자가 작성하는 공지사항 |
| 게시글 | 제목·본문·상단 고정 여부·발행 상태·첨부파일로 이루어진 게시판 한 건 |
| 발행 상태 | 게시글이 임시저장(DRAFT)인지 발행(PUBLISHED)인지를 나타내는 값. 발행해야 작업자에게 노출된다 |
| 발행 일시 | 현재 발행 상태로 전이한 시점. 발행을 취소하면 비워지고 다시 발행하면 그 시점으로 새로 기록되므로 최초 발행 이력은 남지 않는다 |
| 상단 고정 | 목록에서 다른 게시글보다 먼저 보이도록 지정하는 게시 속성. 정렬은 고정 게시글을 앞세운 뒤 등록일 내림차순으로 이어진다 |
| 첨부파일 | 게시글에 딸린 파일. 서버에 저장할 파일명은 충돌을 피하도록 새로 만들고 업로드 원본 파일명은 따로 보관해 내려받을 때 복원한다 |

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-014]] · [[ADR-055]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | ERD 데이터 계층 | [[ERD-022]] |
| 4 | API 경계 계약 | [[API-095]] · [[API-096]] · [[API-097]] · [[API-098]] · [[API-099]] · [[API-100]] · [[API-101]] · [[API-106]] · [[API-107]] · [[API-108]] |
| 5 | DFEAT 비즈니스 로직 | [[DFEAT-037]] · [[DFEAT-038]] |
| 6 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 7 | SCREEN 화면 | [[SCREEN-030]] · [[SCREEN-031]] · [[SCREEN-036]] · [[SCREEN-037]] |
| 8 | SD 고충실 시안 | [[SD-007]] · [[SD-008]] · [[SD-010]] · [[SD-011]] |

> ⚠ 이번 키트에 **0건**인 단계: CONST 상수값, EVT 이벤트 계약, SEQ 흐름 배선, UC 검증, AC 수용, TEST 통합시험, CDIAG 클래스 구조, C4 컴포넌트, INT 외부 연동, FEAT 상위 기능 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| implemented | 17 |
| planned | 17 |
| (미기재) | 7 |

| ITEM | type | status | progress |
|---|---|---|---|
| [[API-095]] | api_endpoint | implemented | 100 |
| [[API-096]] | api_endpoint | implemented | 100 |
| [[API-097]] | api_endpoint | implemented | 100 |
| [[API-098]] | api_endpoint | implemented | 100 |
| [[API-099]] | api_endpoint | implemented | 100 |
| [[API-100]] | api_endpoint | implemented | 100 |
| [[API-101]] | api_endpoint | implemented | 100 |
| [[API-106]] | api_endpoint | implemented | 100 |
| [[API-107]] | api_endpoint | implemented | 100 |
| [[API-108]] | api_endpoint | implemented | 100 |
| [[DFEAT-037]] | domain_feature | implemented | 100 |
| [[DFEAT-038]] | domain_feature | implemented | 100 |
| [[ERD-022]] | erd | implemented | 100 |
| [[SCREEN-030]] | screen_spec | implemented | 100 |
| [[SCREEN-031]] | screen_spec | implemented | 100 |
| [[SCREEN-036]] | screen_spec | implemented | 100 |
| [[SCREEN-037]] | screen_spec | implemented | 100 |
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

> ⚠ 이 표는 **설계가 스스로 적은 주장**이다. 코드와 대조되지 않았다 — 그 대조가 `/mc-logi-implement-review` 의 몫이다.

## ITEM 인덱스

### adr (2)
- [[ADR-055]] — 역할 4종 + 계층(ROLE_ADMIN > ROLE_REVIEWER) — ADR-003·ADR-043 supersede
- [[ADR-014]] — 게시판(공지·가이드라인) 재도입 — ADR-011 번복, v2 신규 재구현

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
- [[ERD-022]] — 게시판 ERD (고도화, PostgreSQL — LS_NOTICE, LS_NOTICE_ATTACH)

### api_endpoint (10)
- [[API-095]] — GET /v1/notices
- [[API-096]] — GET /v1/notices/{id}
- [[API-097]] — POST /v1/notices
- [[API-098]] — PUT /v1/notices/{id}
- [[API-099]] — DELETE /v1/notices/{id}
- [[API-100]] — POST /v1/notices/{id}/publish
- [[API-101]] — POST /v1/notices/{id}/unpublish
- [[API-106]] — POST /v1/notices/{id}/attachments
- [[API-107]] — GET /v1/notices/{id}/attachments/{attachId}/download
- [[API-108]] — DELETE /v1/notices/{id}/attachments/{attachId}

### domain_feature (2)
- [[DFEAT-037]] — 게시글 목록·상세 조회
- [[DFEAT-038]] — 게시글 작성·수정·발행 (REVIEWER)

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (4)
- [[SCREEN-030]] — 공지 목록 화면
- [[SCREEN-031]] — 공지 상세 화면
- [[SCREEN-036]] — 공지 작성 화면
- [[SCREEN-037]] — 공지 수정 화면

### screen_design (4)
- [[SD-007]] — SCREEN-030 공지 목록 화면
- [[SD-008]] — SCREEN-036 공지 작성 화면
- [[SD-010]] — SCREEN-031 공지 상세 화면
- [[SD-011]] — SCREEN-037 공지 수정 화면
