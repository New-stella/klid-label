# 구현 정합 점검 — 파일럿 라운드 (DOMAIN-005 검수 · DOMAIN-006 통계·대시보드)

> 2026-08-16 · 브랜치 `domain-check` · read-only 검출 (코드·설계 무수정)
> 선행: `HANDOFF.md` §3 · 키트는 이 라운드에서 INITIAL 생성 (활성 14 도메인 전량)

---

## 1. 무엇을 했나

| | |
|---|---|
| 키트 | 활성 **14 도메인 전량** 생성 (`docs/design/{slug}-{DOMAIN-ID}/`) · 2,149 파일 · 21MB · 전건 무열화 검증 통과 |
| 점검 범위 | 파일럿 2 도메인 — D005(92 ITEM) · D006(39 ITEM) |
| 차원 | 6종 (api · schema · policy · coverage · acceptance · role) 병렬 |
| 판정 | 3방향(키트·코드·IMPREC) — 단 IMPREC 은 아래 §4-③ 사유로 사실상 1축 결손 |

### 스코프 판정 — 서버 `--domain` 필터를 쓰지 않았다

프로젝트 980 ITEM 중 **517건이 `domain_id` 미설정**이다. 서버 필터는 그 컬럼 일치만 보므로
그대로 쓰면 설계의 절반이 조용히 빠진다(스킬이 경고한 48% 유실 사고와 같은 형태).
`kit-export` 전수를 받아 **그래프(1-hop 도메인 확장)로 판정**하고 `.kit-scope.json` pin 으로 굳혔다.
pin 은 키트와 함께 커밋해야 다른 PC 가 같은 키트를 얻는다.

---

## 2. 결과 요약

**45건** (P0 1 · P1 25 · P2 19). 그중 **38건은 auditor**, **7건은 PM 이 감도 갭을 메우며 추가**했다.

| 차원 | 점검 | P0 | P1 | P2 | 성격 |
|---|---:|---:|---:|---:|---|
| api | 28 | 0 | 8 | 2 | 경로·메서드는 28/28 정합. 표류는 전부 계약 기술(파라미터·바디·응답 필드) |
| coverage | 131 | 0 | 8 | 4 | 진짜 미구현 1건뿐. 나머지는 IMPREC 추적이 코드를 못 따라감 |
| schema | 4 | **1** | 2 | 3 | 컬럼·타입 축은 완전 정합. 결함은 제약·인덱스 축에 몰림 |
| acceptance | 11 | 0 | 2 | 4 | D005 검증 밀도 높음. D006 은 인수기준 계층 자체가 없음 |
| policy | 51 | 0 | 1 | 1 | **코드가 ADR 을 위반한 사례 0건.** 둘 다 ADR 본문이 낡은 것 |
| role | 38 | 0 | 0 | 2 | 28 API·7 SCREEN 인가 정합 |
| **PM 추가** | — | 0 | 4 | 3 | §3 참조 |

### 방향이 압도적으로 한쪽이다

**코드가 설계를 위반한 것(`code_drift`)은 손에 꼽고, 대부분은 설계가 코드를 못 따라간 것이다.**
특히 policy 차원에서 51개 정책 항목을 대조해 **위반 0건**이 나온 것이 그 방증이다 —
이 저장소가 반복 결함으로 지목해 온 축 혼동(`COMPLETED` 3축)·판정 복제·폐기 결정 잔재가
모두 방어돼 있었다.

⇒ **후속 작업의 무게중심은 코드 수정이 아니라 설계 갱신이다.**

---

## 3. P0 — 즉시 조치 (1건)

### `SCH-CONF-001` · `LS_ISSUE_COMMENT` 의 FK 부재 → 댓글 고아

PM 이 4단계로 독립 검증했다.

| 확인 | 결과 |
|---|---|
| `ls_issue_comment → ls_data_issue` FK | **0건** (`V1__baseline.sql`·온프렘 정본 양쪽) |
| "FK 안 다는 관례" 변호 | **불성립** — V1 에 FK **46개** 선언 |
| 설계 요구 | `ERD-023` 이 `on_delete: restrict` 명시 |
| 고아 경로 실재 | `fk_ls_data_issue_raw ... ON DELETE CASCADE` 확인 + 폐기 스윕 삭제 목록 8테이블에 `LS_ISSUE_COMMENT` **없음** |

영상 삭제 → 이슈 CASCADE 소멸 → **댓글만 남는다.** 설계가 `restrict` 로 막으려던 상황이다.
⚠ FK 를 `restrict` 로 걸면 폐기 스윕이 `LS_ISSUE_COMMENT` 선삭제를 해야 하므로 **그 경로를 함께 보정**해야 한다.
⚠ `V1__baseline.sql` 은 수정 금지(체크섬) — 신규 `V` 파일로 추가한다.

---

## 4. 감도 측정 — 이 라운드의 가장 중요한 결과

인계문 §3-1 의 기지 결함 표본을 **의도적으로 프롬프트에서 빼고** 돌렸다. 재발견 여부가 곧 감도다.

| 표본 | 스코프 | auditor |
|---|---|---|
| `API-014` request_body 미선언 | D005 | ✅ 재발견 |
| `API-015` request_body 미선언 | D005 | ❌ **누락** |
| `StatsService.getDashboardSummary` `notices` 하드코딩 | D006 | ❌ **누락** |
| javadoc 개수 표기 갈림(9종 ↔ 14종) | D006 | ❌ **누락** |
| `INT-002` · `API-061` · `API-190` · `RoleClaimPage` | 타 도메인 | 해당 없음 |

**스코프 안 4건 중 1건만 잡았다.** 원인은 부주의가 아니라 **검사 방식이 구조적으로 좁은 것**이며 두 형태다.

### ① 역추적이 「단서 기반」이라 축을 전수로 훑지 않는다
`API-014` 는 **응답코드 불일치(400 없음)** 라는 단서에 걸려 잡혔다. `API-015` 는 400 이 이미
선언돼 있어 그 단서에 안 걸렸다. PM 이 `request_body` 선언 유무를 **28건 전수**로 훑자
누락이 1건이 아니라 **5건**이었다:

| ITEM | 코드 | 키트 |
|---|---|---|
| `API-014` POST approve | 선택 바디 `ApproveRequest` | 미선언 |
| `API-015` POST reject | **필수** 바디 `RejectRequest(@NotBlank reason)` | 미선언 |
| `API-016` POST meta approve | 선택 바디 | 미선언 |
| `API-017` POST meta reject | **필수** 바디 | 미선언 |
| `API-105` POST issue resolve | **필수** 바디 | 미선언 |

### ② 필드명 대조는 「선언은 있는데 항상 비어 있음」을 못 본다
api·coverage 두 차원 모두 `API-055` 를 **"계약 14필드 ↔ DTO 14필드 완전 일치 ✅"** 로 통과시켰다.
그런데 그중 `notices` 는 `StatsService.java:114` 에서 `List.of()` **하드코딩**이고
백엔드 전역에 채우는 지점이 **0건**이다(같은 저장소에 `NoticeService` 가 실재하는데도).
FE `NoticeCard` 는 항상 "공지사항이 없습니다"를 그린다.
**필드가 있느냐가 아니라 값이 오느냐를 봐야 잡힌다.**

### ③ IMPREC 축이 사실상 비어 있다
두 키트의 implementation 레코드가 **전건 `records: []`·`modules: []`**(예외 `DFEAT-054→MOD-043` 1건).
3방향 대조의 ②축(커밋·심볼)이 구조적으로 부재해, 이번 판정은 ①의존맵 + ③계약 grep **2축**으로만 이뤄졌다.

### 반대로 — 오탐은 6차원 전부에서 0건이었다

반증이 잘 작동했다. 기각된 후보들:
- **role**: `GET /v1/assignments` 가 `@PreAuthorize("isAuthenticated()")` 단독이라 겉보기엔 느슨하지만,
  1차 원천인 `SecurityConfig` 순서 있는 매처까지 내려가 유효 역할 집합이 키트와 같음을 확인하고 기각.
  **과거 이 프로젝트에서 인가 불일치 101건을 오탐하게 만든 바로 그 함정이다.**
- **schema**: `REVLT_YN` CHECK 부재 → `char(1)` 33컬럼 중 YN CHECK 0건 = 전역 관례라 기각 /
  `LS_TASK_ALTMNT` 개명 미반영 의혹 → V9 개명 방향이 `assignment→altmnt` 라 엔티티가 옳음으로 기각.
- **policy**: `ORDER BY CASE` 매치가 **폐기를 적은 부정문**임을 확인하고 기각(과거 정상 8건 오탐 지점).
- **coverage**: `EvntAnnoController` 형제 메서드·`/deid-image` 를 `extra_code` 로 올리려다
  logicraft 에 `API-133/134/135`·`API-175` 로 실재함을 확인하고 전건 기각.

---

## 5. 버킷

### A. 코드 수정 (`/cc` 개발 파이프라인)

| id | 대상 | 요지 |
|---|---|---|
| `SCH-CONF-001` **P0** | `V1__baseline.sql` 후속 V 파일 | `LS_ISSUE_COMMENT` FK 추가 + 폐기 스윕 선삭제 보정 |
| `COV-001` P1 | `WorkerStatSummaryResponse` | 계약 3필드(`assignedTotal`·`completionRate`·`approvedLabelCount`) 미구현. IMPREC 이 `in_progress 77%` + 정확히 그 3건을 미완 subtask 로 들고 있어 **추적은 정직하다** |
| `PM-001` P1 | `StatsService.java:114` | `notices` 하드코딩 `List.of()` — `NoticeService` 연결 또는 계약에서 제거 |
| `ACC-CONF-002` P2 | `DatamartViewRebuildIT` | `V_COMPLETED_META` 의 PENDING/REJECTED 배제 단언 0건 (seed 가 전건 APPROVED) |
| `ACC-CONF-003` P2 | `AugmentDiscardPurgeIT:771` | 상태코드 미단언이 409(AC) ↔ 404(코드) 불일치를 가리고 있음 |

### B. 설계 갱신 (`mc-logi-update`)

**소유 도메인이 이번 스코프 밖인 건이 많다** — 교차 소비로 키트에 들어온 ITEM 이라 라우팅 주의.

| id | ITEM | 소유 | 요지 |
|---|---|---|---|
| `PM-002` P1 | `API-014`·`015`·`016`·`017`·`105` | D005 | `request_body` 미선언 5건 (필수 바디 3 · 선택 2) |
| `API-CONF-005/006` P1 | `API-042` | D003 | 쿼리 파라미터 7종·400 응답 누락 / 응답 7필드 누락 |
| `API-CONF-007` P1 | `API-001` | D001 | 파라미터 미선언인데 400 서술은 `size` 한도를 전제 — 자기모순 |
| `API-CONF-008` P1 | `API-072` | D015 | 403 서술이 코드와 **정면 반대**(코드는 403 아니라 무시) |
| `API-CONF-004` P1 | `API-132` | D010 | 응답 필드가 키트·코드로 갈림 (`annotation`↔`payload` 등) |
| `SCH-CONF-002` P1 | `ERD-015` | D005 | 본문이 "FK 미선언" 이라 적는데 실제 DDL 은 CASCADE FK 보유 — **P0 를 낳은 서술일 가능성** |
| `SCH-CONF-003` P1 | `ERD-023` | D005 | 영상 삭제가 반려 이력을 함께 지운다는 사실이 설계에 없음 |
| `POL-CONF-001` P1 | `ADR-009` | D005 | context 가 "라벨 저장 시 스냅샷" — 코드·같은 키트 IMPLEMENTATION.md 와 정반대 |
| `ACC-CONF-006` P1 | D006 DFEAT-026~028 | D006 | 인수기준 계층 전무한데 코드는 도메인 정책을 이미 단언 중 |
| `ACC-CONF-004` P2 | `TEST-002` | D005 | 폐기된 `results` 배열·자유 metaKey 규격 잔존 (같은 ITEM s1 은 갱신됨 = 부분 갱신) |
| `PM-003` P2 | — | — | `StatsController` javadoc 「9종」 ↔ `StatsService` javadoc 「14종」 · `TaskSummaryResponse` javadoc 「관제 계약 6필드」(실제 10) — **개수 표기 금지 규칙 위반** |
| 그 외 | `API-CONF-009/010`·`SCH-CONF-004/005/006`·`ACC-CONF-005`·`POL-CONF-002`·`ROLE-CONF-001` | | 응답 필드·인덱스·기본값·`verification_method`·통지 타이밍 서술 |

### C. 추적 정정 (IMPREC)

`imprec_mismatch` **11건** — 방향이 전부 하나다: **`planned` 인데 이미 구현됨.**
반대 방향(구현됐다 주장인데 코드 없음, P0급)은 **0건**이다.

대표: `ERD-015`·`API-132`·`FEAT-008`·`FEAT-009`·`UC-022`·`UC-023`·`SEQ-015`·`SEQ-023`·`ROLE-001~003`·`NFR` 14건.
`INT-003`·`SEQ-008`·`SEQ-011` 은 `status=implemented` 인데 `progress=0` 인 자기모순.

⚠ `EVT-008` 은 성질이 다르다 — `implemented/100` 인데 `src/main` 에 발행처가 **0건**인 휴면 확장점이다.
이는 프로젝트 확정 정책(통지 트리거를 재승인 시점으로 이관)의 결과이므로 **코드가 옳고 ITEM 본문·IMPREC 이 낡았다.**
**코드 결함으로 되돌리지 말 것.**

---

## 6. 다음 라운드에 반드시 반영할 것 (프롬프트 보강)

파일럿의 목적이 이것이었다. 나머지 12 도메인으로 확대할 때 auditor 프롬프트에 아래를 **명시**한다.

1. **축을 전수로 훑어라 — 단서 역추적으로 대체하지 마라.**
   `request_body`·`parameters`·`responses` 선언 유무는 **대상 전건에 대해 표로 만들고** 대조한다.
   "무언가 이상해 보이는 것"부터 파고들면 정상처럼 보이는 누락을 통째로 놓친다.
2. **필드가 있는지가 아니라 값이 오는지 보라.**
   응답 DTO 필드가 계약과 이름·개수가 같아도, 그 필드를 **채우는 코드가 있는지** 확인한다.
   하드코딩 상수·빈 컬렉션·항상 `null` 은 "구현됨"이 아니다.
3. **`records: []` 면 IMPREC 축이 없는 것이다.** 그 사실을 confidence 산정 근거에 명시하고,
   2축만으로 판정했음을 보고에 적는다.
4. **개수 표기(`N종`·`N필드`)를 만나면 그 자리에서 실제 개수를 세라.** 이 저장소에서 반복 확인된 드리프트 형태다.

---

## 7. 이 라운드가 보지 않은 것 (정직한 한계)

- **12 도메인 미점검** — 키트는 준비됐으나 auditor 를 돌리지 않았다.
- **중첩 DTO 내부 필드** — 응답 대조는 최상위 record 컴포넌트까지다.
  `MyTaskBreakdown`·`WorkerRow`·`EventAnnotationPayload`·`frames[]`·`comments[]` 내부는 미대조.
- **런타임 실호출 0** — 상태코드는 `ErrorCode`·`throw` 지점의 정적 추적이다. 테스트도 실행하지 않았다.
- **`@JsonProperty` 직렬화명 재정의** — `EventAnnotationInfo` 만 확인, 나머지 DTO 미확인.
- **FE 화면 내부의 액션 단위 역할 분기** — 라우트 가드까지만 봤다.
- **측정형 NFR**(3초 예산 등) — 정적 판정 불가. 반대로 구현된 흔적은 없었다.
- **D006 의 `schema`·`acceptance` 축** — 활성 ERD·AC·UC·TEST 가 0건이라 대조 baseline 자체가 없다.
  코드로 확인한 결과 stats 패키지는 엔티티 0·소유 테이블 0·쓰기 0 이라 **ERD 0건은 정상**이지만,
  인수기준 부재는 정상이 아니다(→ `ACC-CONF-006`).
