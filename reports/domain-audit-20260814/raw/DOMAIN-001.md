# DOMAIN-001 사용자·권한 — 감사 결과

- 10차원 완주 (requirement 는 모드 A SKIP)
- 원시 갭 **58건** — P0 13 / P1 35 / P2 10
- 차원별: coverage 7 · links 10 · schema 5 · content 10 · diagram 2 · stale 9 · policy 8 · acceptance 4 · requirement 0 · test_scenario 3

## 중복 클러스터 (같은 결함이 여러 차원에서 검출됨 — 수정은 1회)

| 클러스터 | 검출 차원 | 실질 결함 |
|---|---|---|
| **C1** `CDIAG-008` 클래스 본문의 삭제 테이블 서술 | DIAG-001, LINK-010, POL-001, POL-002, STL-002 (5중) | `classes[User]`·`classes[UserManagementService]` 가 `MNG_ACCT_*` 마스터·`LS_AUTHRT_MPNG` 매핑을 **현재형**으로 서술. 같은 ITEM 의 top-level description 은 이미 "구 서술 폐기"라 명시 → **ITEM 내부 자기모순**. ADR-042 로 V165 에서 실제 삭제된 테이블 |
| **C2** `DFEAT-003.user_story.as = '관리자'` | CNT-002, POL-005, STL-007 (3중) | ADR-003 으로 폐기된 액터. 같은 ITEM description 은 "별도 ADMIN 역할 없음" 이고 UC-030.actor 는 이미 "검수자(REVIEWER)" 로 정합 |
| **C3** `ERD-001.brownfield.decided_by = ADR-017`(superseded) | LINK-009, POL-004, STL-004 (3중) | 현행은 ADR-021(supersede)·ADR-042. 본문도 "고도화에서는 `MNG_ACCT_*` 공유로 대체"라는 **철회된 방향**을 현재형 서술 (POL-003) |
| **C4** DFEAT↔API 배선 전무 | COV-005, LINK-001, LINK-002, LINK-003, SCH-002 (5중) | `DFEAT-001/002/003` 의 `implemented_by_endpoints`·`persists_in_tables`·`invokes_apis` 전부 `[]`, API 8건의 `implements_features` 전부 `[]` → **양방향 동시 단절** |
| **C5** `UC-030` 도메인·기능 축 고립 | COV-002, LINK-007, ACC-001 (3중) | `realizes_dfeats`·`realizes_features`·`covered_by_acceptances` 모두 `[]`, `domain_id` 없음. `SCREEN-024` 역참조로만 매달림 |
| **C6** `SCREEN-004` note "(API ITEM 미등록)" | CNT-004, LINK-004 (2중) | `API-153`(POST /v1/dev/tokens)이 실재하므로 **사실과 다른 서술** + `consumes_apis` 링크 누락 |
| **C7** `DFEAT-001` 1차 baseline 정지 | COV-007, POL-007, STL-008 (3중) | 형제 DFEAT-002/003 은 2026-08-04 REAL-DRIFT 갱신됐으나 이 건만 2026-05-30 정지. ADR-012·039·043 미반영, ADR 인용 0건 |

## P0 (13건)

| gap | 차원 | 대상 | 내용 | auto |
|---|---|---|---|:---:|
| D001-SCH-003 | schema | API-003/004/005/006 | **실제 계약 버그** — 응답 `channel` enum 을 `["CONTROL","PORTAL"]` 로 공표하나 구현 enum 은 `INTERNAL/PORTAL`. 코드 전체에 `"CONTROL"` 0건. 같은 도메인 `API-153` 은 이미 `["INTERNAL","PORTAL"]` → 도메인 내 자기모순 | ✅ |
| D001-SCH-001 | schema | DOMAIN-001, ERD-001 | **활성 사용자·권한 ERD 0건.** 실 운영 테이블 `LS_ACNT_USER`·`LS_USER_ROLE`·`LS_AUTHRT_GRANT_ATMPT` 3종이 어떤 활성 ERD 에도 없음. 유일 연결 `ERD-001` 은 deprecated + 1차 MariaDB 형상(PK 형상도 다름) | ❌ |
| D001-DIAG-001 | diagram | CDIAG-008 | C1 — 클래스 본문 삭제 테이블 서술 | ✅ |
| D001-POL-001 | policy | CDIAG-008 | C1 — `classes[User]` 축 | ✅ |
| D001-POL-002 | policy | CDIAG-008 | C1 — `classes[UserManagementService]` 축 | ✅ |
| D001-STL-002 | stale | CDIAG-008 | C1 — `stale=false` 인데 본문 표류 (**플래그가 표류를 못 잡는 반례**) | ✅ |
| D001-LINK-009 | links | ERD-001, ADR-017 | C3 — superseded ADR 활성 참조 | ❌ |
| D001-CNT-001 | content | API-007 | description 이 "JWT 클레임으로 권한 확정·전환 / 포털 채널 분기"라 적었으나 실제 계약은 관리자 패스워드 검증 → 역할 자가부여 → 새 토큰 발급. **"포털 채널 분기"는 계약이 PORTAL_USER 를 400 거부하는 것과 정면 모순** | ✅ |
| D001-CNT-002 | content | DFEAT-003 | C2 | ✅ |
| D001-POL-005 | policy | DFEAT-003 | C2 | ✅ |
| D001-STL-007 | stale | DFEAT-003 | C2 | ✅ |
| D001-COV-004 | coverage | UC-030 | 도메인 유일 활성 UC 에 happy path·error path SEQ **0건**. 프로젝트 전역 SEQ 14건에 인증·역할 축 시퀀스 자체가 없음 | ❌ |
| D001-COV-005 | coverage | API-001~007, API-153 | C4 — API 8건 전부 orphan | ❌ |
| D001-STL-003 | stale | API-001 | `brownfield.diff_summary` 가 deprecated 인 `ERD-001` 을 **"preserved"** 로 인용 (사실과 반대) | ✅ |

## P1 (35건)

| gap | 차원 | 대상 | 내용 | auto |
|---|---|---|---|:---:|
| D001-COV-001 | coverage | DFEAT-001, DFEAT-002 | 두 DFEAT 를 실현하는 UC 가 **프로젝트 전역 0건** (활성 25 + retired 7 전수 확인) | ❌ |
| D001-COV-002 | coverage | DFEAT-003, UC-030 | C5 | ❌ |
| D001-COV-003 | coverage | DFEAT-001/002/003 | 화면 5종 보유인데 DFEAT 3건 전부 어떤 SCREEN 에서도 미인용. SCREEN 에 `references_features`/`references_dfeats` 키 자체가 부재 | ❌ |
| D001-COV-006 | coverage | DOMAIN-001 | **도메인 경계 드리프트** — description 이 선언한 책임 "영상/작업 단위 권한 배정"의 실현체가 `DOMAIN-015`(DFEAT-006)에 있음 | ❌ |
| D001-COV-007 | coverage | DFEAT-001, SCREEN-004, API-153 | C7 | ❌ |
| D001-LINK-001 | links | DFEAT-003 + API-001~005 | C4 — description 이 API 4종을 글로 열거하는데 링크는 `[]` | ✅ |
| D001-LINK-002 | links | DFEAT-001/002 + API-006/007/153 | C4 — 인증·역할 축 API 3종 귀속 미상 | ❌ |
| D001-LINK-003 | links | API 8건 | C4 — `implements_features` 전건 `[]` | ❌ |
| D001-LINK-004 | links | SCREEN-004, API-153 | C6 | ✅ |
| D001-LINK-006 | links | SCREEN-024, UC-030 | `UC-030.related_screens=[SCREEN-024]` 인데 `SCREEN-024.realizes_use_cases=[]` → 역방향 비대칭 | ✅ |
| D001-LINK-007 | links | UC-030, DFEAT-003 | C5 | ✅ |
| D001-LINK-008 | links | DFEAT 3건, ERD-001 | `persists_in_tables` 전건 `[]`, 유일 ERD 는 deprecated 1차 형상 | ❌ |
| D001-LINK-010 | links | CDIAG-008, ERD-001 | C1 | ❌ |
| D001-SCH-002 | schema | DFEAT 3건 | C4 — 영속 선언 부재로 SCH-1 대조 자체가 불가 | ❌ |
| D001-SCH-004 | schema | API-007, SCREEN-002 | **POST 인데 `request_body` 계약이 통째로 없음.** 응답 400/401/429 는 요청 필드를 전제로 서술하고, SCREEN-002 는 `role`·`adminPassword`+표시용 `userId`·`userNm` 전송을 명시 | ❌ |
| D001-SCH-005 | schema | API-001~005 | 응답 필드 ↔ 물리 컬럼 대조 기준 부재. `userEmail ↔ USER_EML_ADDR` 처럼 단순 camelCase 변환이 아닌 매핑이 어디에도 기록 없음 | ❌ |
| D001-CNT-003 | content | API-004 | 중첩 `responses[].description` 이 폐기된 쓰기 필드 `useYn` 을 수정 대상으로 서술. UC-030·SCREEN-024 는 "계정 활성 여부는 외부 시스템 소유값·읽기 전용"으로 확정. 200 응답 스키마에도 `useYn` 필드 없음 | ❌ |
| D001-CNT-004 | content | SCREEN-004 | C6 | ✅ |
| D001-CNT-005 | content | API-153, SCREEN-004 | **두 활성 ITEM 이 같은 dev 토글을 배타적으로 서술** — API-153 "검증(stg) 프로파일 기본 true" vs SCREEN-004 "스테이징에서 켜진 채 기동하면 기동 실패". 함께 적용하면 stg 는 기본값만으로 항상 기동 실패. ADR-039("기본 OFF, fail-closed")는 SCREEN-004 쪽과 정합 | ❌ |
| D001-CNT-006 | content | UC-030 | 본문이 "SCREEN-024 화면 사양의 [폐기] 표기 참조"라 지시하나 SCREEN-024(v20) 전수에 `[폐기]` 문자열 0건 → 끊긴 안내 | ✅ |
| D001-CNT-007 | content | DOMAIN-001, DFEAT-002, NFR-020, API-006, CDIAG-008 | 본문에 내부 구현 어휘 잔존 — 클래스명·프레임워크 어노테이션·마이그레이션 버전(`V75`/`V165`). API-006 은 v5 change_summary 가 "클래스명을 걷어냈다"고 적었으나 **중첩 필드는 누락** | ✅ |
| D001-CNT-008 | content | DOMAIN-001, DFEAT-003, CDIAG-008 | 구현 상태 서술("설계 확정·구현 예정")·유지보수자 지시문("되돌리지 말 것")·자기 리비전 서술이 본문에 혼입 | ❌ |
| D001-POL-003 | policy | ERD-001 | C3 — 폐기 사유가 철회된 방향(`MNG_ACCT_*` 대체) | ✅ |
| D001-POL-004 | policy | ERD-001 | C3 | ✅ |
| D001-POL-006 | policy | SCREEN-002 | 화면 문구 "관리자에게 받은 패스워드로…" — ADR-003 상 존재하지 않는 행위자. 같은 화면의 403 안내는 이미 "검수자에게 요청"으로 정합 (자산명 '관리자 패스워드'는 확정 용어라 대상 아님) | ❌ |
| D001-STL-004 | stale | ERD-001 | C3 | ❌ |
| D001-STL-005 | stale | API-007, SCREEN-002 | `brownfield.status=modified` 인데 `legacy_source` 키 자체가 부재. API-007 은 `diff_summary` 에 미검증 표식 "[추정 — 1차 인증 대비 변경]" 잔존 | ❌ |
| D001-STL-008 | stale | DFEAT-001 | C7 | ❌ |
| D001-ACC-001 | acceptance | UC-030 | C5 — 도메인 유일 활성 UC 에 검증 AC 0건. `alternate_flows`(화이트리스트 밖 400 / 저장 비활성)·`postconditions`(계정 활성여부 변경 불가) 전부 미검증 | ❌ |
| D001-ACC-002 | acceptance | DFEAT-001 | priority=must 인데 UC 경유·직접 어느 경로로도 AC 0건 | ❌ |
| D001-ACC-003 | acceptance | DFEAT-002 | priority=must 인데 AC 0건. 역할 3종 분기·타 역할 403 미검증 | ❌ |
| D001-ACC-004 | acceptance | DFEAT-003, UC-030 | AC 0건. 역할 클레임 4경계(401/429/409/400)·사용자 자동등록 미검증 | ❌ |
| D001-TST-001 | test_scenario | 도메인 전반 | 프로젝트 통합시험 5건(TEST-001~005) 중 **DOMAIN-001 귀속 0건**. 귀속 4축(`covers_use_cases`·`exercises_screens`·`verifies_*`·`related_domains`) 전부 미교차 | ❌ |
| D001-TST-002 | test_scenario | NFR-013, NFR-020 | 도메인에 `applies_to` 로 직결된 NFR 2건 미검증. **프로젝트 전체에 `kind=system` 시험이 0건** | ❌ |
| D001-STL-001(일부) | stale | — | ↓ P2 참조 | — |

## P2 (10건)

| gap | 차원 | 대상 | 내용 | auto |
|---|---|---|---|:---:|
| D001-STL-001 | stale | ROLE-001/002/003, UC-030, MOD-001/002 | `stale=true` 6건 **전수 본문 통독 결과 구 모델 서술 0건** — cascade 플래그만 잔존. 원인 ITEM 갱신이 1일 이내라 30일 P0 승격 조건도 미충족 → **정규화 규칙대로 P2** | ✅ |
| D001-STL-006 | stale | API-001~006, SCREEN-001, MOD-001/002 | `brownfield.status=preserved`(1차 보존 주장) 9건에 `legacy_source` 부재. 형제 `DFEAT-001`·`UC-030`·`SCREEN-024` 는 채워져 있어 불일치 | ❌ |
| D001-STL-009 | stale | ROLE 3건, UC-030, NFR-013/020, CDIAG-008 | approved 인데 `implementation.status=planned`/progress 0 방치. 같은 도메인 DFEAT 는 implemented/100 이고 MOD-001/002 실재 | ❌ |
| D001-POL-007 | policy | DFEAT-001 | C7 — ADR 인용 0건 (형제는 ADR-012/039 보유) | ✅ |
| D001-POL-008 | policy | ROLE-001 | `typical_actors` 에 "운영 관리자(겸임)" 잔존. '(겸임)' 표기라 의도 가능성 있어 advisory | ❌ |
| D001-CNT-009 | content | SCREEN-002 | 화면 문구 행위자 '관리자' (POL-006 과 동일 축) | ❌ |
| D001-CNT-010 | content | DFEAT-001 | C7 — 본문이 1차 baseline 2문장에 정지 | ✅ |
| D001-LINK-005 | links | SCREEN-024, API-003 | `consumes_apis` 에 `API-003` 이 있으나 어떤 섹션도 미사용. 수정 모달은 "별도 단건 조회 없음" 명시 → 잔재 | ✅ |
| D001-TST-003 | test_scenario | TEST-001~005 | **전건 `related_domains` 공란** → ⚠ **프로젝트 레벨 이슈, 14개 도메인에서 중복 검출 예상 (최종 dedupe 대상)** | ❌ |
| — | — | — | — | — |

## requirement 차원 — 모드 A SKIP (갭 0건)

도메인 귀속 REQ 0건. 근거 4축: ①도메인 backward 31건에 `requirement` 0 ②DFEAT 3건 backward 에 REQ 0 ③활성 REQ 22건 전량이 SFR-06~17 데이터 파이프라인 축 ④인접했던 `REQ-017`(개인정보 보호대책)은 deprecated.

> **관찰(사용자 판단 필요)**: 이 도메인은 `context_kind=supporting` 으로 **RFP→REQ 하향이 아니라 도메인→DFEAT 직결** 설계이며, 요구 축을 `NFR-013`·`NFR-020` 이 대신 지고 있다. 의도인지 결함인지 확정 필요.

## 각 auditor 가 명시한 미확인 층 (보고 정직성 기록)

- **정적 렌더 미러** (`SCREEN-001~004`·`024` 의 main.html, `source_hash` 대조) — 전 차원 미확인. 특히 SCREEN-002 의 '관리자' 문구가 미러에도 복제됐을 개연성 높음
- **로컬 키트 스냅샷** `docs/screen-design/사용자권한-DOMAIN-001/` — 미확인
- **위키** `docs/v2-wiki` · **테스트케이스** `docs/test-cases` · **구현 코드** — 미확인 (단 schema·diagram 차원은 `deploy/onprem/db/schema.sql` 을 실측 grep 함)
- 1차 소스 grep — `legacy_grep_enabled=false` 로 전 차원 미수행
- `list_diagram_coverage` MCP 도구가 **auditor 도구 목록에 없어** diagram 차원이 수동 fallback 사용 → 메인에서 별도 호출해 **커버리지 100%(3/3) 일치 확인 완료**
