| 항목 | 값 |
|---|---|
| CO 번호 | CO-013 |
| 제목 | 영상 처리 현황 목록이 WORKER 에게 본인 배정분만 보여준다 (목록↔단건 인가 비대칭 해소) |
| 대상 도메인 | DOMAIN-003(영상·프레임 수집) |
| 구현 상태 | ✅ 구현·QA 완료 (FULL 회귀 대기) |
| LogiCraft 설계반영 | 🎨 완료 (API-042 v8 · SCREEN-008 v44 · ROLE-002 v7) |
| 생성일 | 2026-08-25 |

> ★ 이 프로젝트는 **설계를 먼저 확정하고 코드가 뒤따른다**(`klid-dispatch` Phase 3.6). 따라서 정상 흐름에서는 §6 이 **구현 전에** 🎨 가 된다.

---

## §1 배경

**증상** — WORKER 계정으로 「영상 처리 현황」(`/video/status`) 을 열면 **자기에게 배정되지 않은 영상까지 목록에 그대로 보이고**, 그 행을 누르면 `403 본인에게 배정되지 않은 영상입니다.` 가 난다. 사용자 보고: *"워커한테 배정이 안되어있으면 안보이게 하는게 맞을것 같아. 지금은 누르면 에러가 나니깐 문제인거 같아."*

**근본 원인 (2026-08-25 cudo_246 dev 실측)** — `GET /v1/videos` 에 **사용자 축 인가가 아예 없다.**

- `video/controller/VideoController(list)` 는 `@AuthenticationPrincipal TokenClaims actor` 를 **파라미터로 받지도 않는다.** 역할 게이트 `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 만 있고, 그것은 *역할 축*이지 *사용자 축*이 아니다.
- `video/service/VideoQueryService(list)` 시그니처도 `(Pageable, VideoListFilter)` 뿐이고 actor·userNo 를 **한 번도 참조하지 않는다.**
- 반면 형제 단건 경로 `VideoController(getOne)` 은 `label/service/LabelAccessGuard(verifyRawAccess)` 로 배정을 강제한다.

⇒ **목록은 열려 있고 클릭은 막혀 있는 비대칭**이 증상의 전부다.

**실측 (WORKER userNo=2001, 미배정 rawSn 4·5 기준)**

| 엔드포인트 | 응답 | 미배정 포함 |
|---|---|:---:|
| `GET /v1/videos` | 200, total 6 | **예 ← 결함** |
| `GET /v1/videos/5` | **403** 본인에게 배정되지 않은 영상입니다 | — |
| `GET /v1/assignments` | 200, total 4 `[7,6,2,1]` | 아니오 (정상) |
| `GET /v1/tasks/board` | 403 (REVIEWER 전용) | 아니오 |

**정보 노출도 함께 일어난다** — 응답 `VideoSummaryResponse` 가 미배정 영상의 `cctvName`·`localGov`·`eventTypeCd`·`capturedAt`·`durationSec`·`prvcTypeCd`·`deIdntfYn` 를 그대로 준다(CWE-639 / CWE-200).

**왜 여기만 남았나** — 커밋 `dd288bed`(B-63, 2026-08-26 아님 2026-07-26)이 미배정 WORKER 의 임의 영상 재생 IDOR 를 닫을 때, 감사 축이 *"같은 자산을 노출하는 **rawSn 단위** 형제 경로"* 였다(`videos/{rawSn}` · `labels/auto` · `frames/{n}/image` · `stream` · `stream-url`). **컬렉션 엔드포인트는 그 축에 들어가지 않았다.**

## §2 변경 요지

`GET /v1/videos` 가 호출자를 보고 **WORKER 면 본인에게 `LABELER` 로 배정된 영상만** 돌려준다. REVIEWER 는 종전대로 전체를 본다.

- **403 이 아니라 결과 축소**다. 목록에서 남의 영상을 빼는 것이지 요청을 거부하는 게 아니다(`AssignmentSearchCondition(scopedToSelf)` 가 이미 확정한 관례 — *"403 아님"*).
- 요청이 보낸 **어떤 파라미터도 이 축에 읽지 않는다.** 값을 참조하는 순간 그 분기가 IDOR 의 입구가 된다.
- 판정은 **한 곳에만** 둔다(복제 금지). 정본 패턴은 `assignment/service/AssignmentService(scopeForActor)`.
- FE 는 **무변경으로 정상화**된다 — `VideoListPage`/`useVideos` 는 응답을 그대로 렌더하므로 BE 가 좁히면 화면도 좁아진다.

## §3 도메인별 변경 상세

### DOMAIN-003 (영상·프레임 수집) — `klid-d003-implementer`

- **대상 파일·심볼**
  - `video/controller/VideoController.java(list)`
  - `video/service/VideoQueryService.java(list)` — 2개 오버로드 중 `(Pageable, VideoListFilter)` 가 본체
  - `video/repository/VideoRepository.java(searchOriginals · searchOriginalsInternal)`
  - (참고 정본) `assignment/service/AssignmentService.java(scopeForActor)` · `assignment/dto/AssignmentSearchCondition.java(scopedToSelf)`
  - (참고) `assignment/domain/LsTaskAssignment` — 배정 엔티티. 배정 판정은 `TASK_LABELER` 축이다(`LabelAccessGuard(verifyRawAccess)` 가 쓰는 것과 같은 축: `existsByUserNoAndTaskTypeCdAndRawDataId(selfNo, TASK_LABELER, rawSn)`).

- **변경**
  1. `VideoController(list)` 에 `@AuthenticationPrincipal TokenClaims actor` 파라미터 추가 → `videoQueryService.list(pageable, filter, actor)` 로 전달.
  2. `VideoQueryService(list)` 가 actor 를 받아 **스코핑 대상 userNo** 를 도출한다.
     - `actor.role() == Role.WORKER` → `parseUserNo(actor.sub())` (본인)
     - `actor.role() == Role.REVIEWER` → 스코핑 미적용
     - `actor == null` → `UNAUTHORIZED`, 그 외 역할 → `FORBIDDEN`
     - ★ 이 판정을 `VideoQueryService` 안에 **새로 쓰지 말고**, `AssignmentService(scopeForActor)` 와 **같은 규칙**임을 코드로 드러내라. 공용 헬퍼로 뽑을 수 있으면 뽑고(예: `common/security` 또는 `assignment` 쪽 공개 헬퍼), 순환 의존 등으로 불가하면 그 사유를 javadoc 에 남기고 최소 복제하되 **원본을 인용**할 것.
  3. `VideoRepository(searchOriginalsInternal)` 에 배정 술어를 추가한다. 기존 on/off 플래그 관례를 그대로 따른다:
     ```
     AND (:assignedOnlyOn = 0
          OR EXISTS (SELECT 1 FROM LsTaskAssignment a
                      WHERE a.rawDataId = v.rawSn
                        AND a.userNo = :actorUserNo
                        AND a.taskTypeCd = 'LABELER'))
     ```
     - ★★ **본 쿼리와 `countQuery` 가 같은 술어 문자열을 공유해야 한다.** 그 파일은 이미 `SKIPPED_BUNDLE_PREDICATE`·`FAILED_BUNDLE_PREDICATE` 를 상수로 뽑아 양쪽에 붙이는 방식을 쓴다(`searchOriginalsInternal` 의 `@Query` + `countQuery`). **같은 방식으로 상수를 신설**하고 양쪽에 붙여라. 한쪽만 붙이면 `totalElements` 가 필터 적용 전 값으로 남아 페이지 수가 틀어진다.
     - ★ **`EXISTS` 를 쓸 것.** 조인으로 하면 한 영상에 배정 행이 여러 개일 때(예 LABELER+REVIEWER 두 행) 행이 증식해 `totalElements` 가 부풀고 중복 행이 나온다. 실제로 rawSn 1·7 은 LABELER·REVIEWER 두 행을 갖는다.
     - ★ 미적용 시 바인딩 값은 그 파일의 기존 관례를 따를 것(`NO_BUNDLE_MATCH` 처럼 더미 상수 또는 플래그 0). **`null` 을 넣으면 타입 추론이 안 잡힌다**(그 파일이 `NO_BUNDLE_MATCH` 를 두는 이유가 그것).
  4. `TASK_LABELER` 리터럴을 새로 쓰지 말고 `LsTaskAssignment` 의 기존 상수를 참조할 것.

- **불변(건드리면 안 되는 것)**
  - **REVIEWER 결과는 조금도 달라지면 안 된다.** 기존 필터 8종(`dataSttsCd`·`reviewStatusCd`·`cctvNameKeyword`·`eventTypeCd`·`from`·`to`·`skippedStage`·`failedStage`)·정렬 allowlist·`safeSort`·기본값·응답 스키마 전부 무변경.
  - `VideoListFilter` 레코드에 **사용자 축 필드를 추가하지 말 것** — 요청이 채울 수 있는 자리에 두면 그 자체가 IDOR 입구다. 스코핑 값은 **actor 에서만** 나온다.
  - `list(Pageable, String, String)` 레거시 오버로드의 기존 호출자를 깨지 말 것(호출처를 먼저 grep 해 확인).
  - R1(원본 RAW 만 노출 — 파생 `ORGNL_RAW_SN NOT NULL` 제외) 규칙 유지.
  - 단건 경로(`getOne` 등)의 403 은 **그대로 둔다.** 목록을 좁히는 것과 별개의 방어선이며, 직접 URL 입력·외부 클라이언트를 막는다.

- **주의**
  - **403 으로 만들지 말 것.** WORKER 가 목록을 부르는 것 자체는 정상이다. 배정 0건이면 **빈 페이지(200)** 다.
  - `parseUserNo(actor.sub())` 가 실패하는 토큰(비숫자 sub)이 있을 수 있다 — 기존 `AssignmentService` 가 그 경우를 어떻게 다루는지 확인해 **같게** 처리하라(임의로 다르게 만들지 말 것).
  - PORTAL 채널·`PORTAL_USER` 는 이 엔드포인트에 도달하지 않는다(`hasAnyRole('REVIEWER','WORKER')`). 새 분기를 만들지 말 것.
  - 이 변경으로 **대시보드 「최근 완료 영상」**(같은 API 를 `reviewStatusCd=APPROVED&size=5` 로 호출)도 WORKER 에게는 본인 배정분만 보인다. **이는 사용자가 확정한 의도된 방향이다**(2026-08-25) — 예외를 만들지 말 것.

- **수용기준**
  1. WORKER 토큰으로 `GET /v1/videos` → 응답 `content` 에 **본인에게 `LABELER` 로 배정된 rawSn 만** 포함된다.
  2. 같은 요청의 `totalElements` 가 그 배정 건수와 일치한다(count 쿼리 술어 누락 검출).
  3. REVIEWER 토큰의 응답은 변경 전과 **동일**하다(전체 노출).
  4. 배정 0건인 WORKER 는 **403 이 아니라 200 + 빈 페이지**를 받는다.
  5. 한 영상에 LABELER·REVIEWER 배정이 함께 있어도 **중복 행이 나오지 않는다**.
  6. 기존 필터·정렬·페이징 동작이 REVIEWER 기준으로 무변경(`ListApiBackwardCompatibilityIT` 「5. 영상 처리 현황」 통과).

### 프론트 — **이번 CO 에서는 변경 없음**

BE 가 좁히면 `pages/VideoListPage.tsx` · `features/video/hooks/useVideos.ts` 는 무변경으로 정상화된다(응답을 그대로 렌더하므로). 클라이언트 필터를 **추가하지 말 것** — BE 가 정본이고, FE 필터는 다른 클라이언트(외부 FE 팀)를 보호하지 못한다.

> 별건 권고 2건은 §4 에 기록만 하고 이번 범위에서 제외한다(게이트에서 사용자 확정).

### 공유기반 선처리 — **해당 없음**

`common/`·`batch/`·`db/migration/`·앱 진입점 무관. **DB 마이그레이션 없음**(기존 `LS_TASK_ALTMNT` 를 읽기만 한다).

## §4 영향·리스크

- **하위호환**
  - REVIEWER 전 경로 무영향.
  - **WORKER 의 `totalElements` 가 줄어든다.** 파라미터 계약(신규 optional·기본값 불변)과는 다른 **인가 축**의 변경이므로 그 계약과 직접 충돌하지는 않으나, **외부 FE 팀에 고지가 필요**하다.
  - 대시보드 「최근 완료 영상」이 WORKER 시각에서 좁아진다(의도 — 사용자 확정).
  - 마킹 흐름 무영향 — `MarkingController(create)` 는 `MarkingGuards.requireAssignedOrReviewer` 로 이미 배정을 강제하고, WORKER 는 `TaskListPage` → `/v1/assignments` 경유로 진입한다.
  - `AugmentRequestPage` 는 REVIEWER 전용 라우트라 무영향.

- **되돌리기**: 술어를 미적용 상태로 바인딩(플래그 0)하면 즉시 종전 동작. 스키마 변경이 없어 롤백에 데이터 위험이 없다.

- **리스크**
  1. **count 쿼리에 술어를 빠뜨리면** 목록은 좁아지는데 페이지 수·`totalElements` 가 전체 기준으로 남는다(빈 페이지가 딸려 나온다). → 수용기준 2 로 고정.
  2. **조인으로 구현하면** 배정 행 수만큼 중복·부풀림. → 수용기준 5 로 고정.
  3. `VideoListFilter` 에 사용자 축을 넣으면 **요청이 그 값을 채울 수 있어** IDOR 이 다시 열린다. → 불변 항목으로 명시.

## §5 검증

- **신설 회귀 가드 (BE)** — 기존 3겹이 이 축을 전부 놓쳤으므로 반드시 신설한다.
  - `video/VideoContentRoleGateTest(workerOkOnVideoList)` 는 **상태코드만 단언**하고 응답 본문을 보지 않아 **결함 동작을 "정상"으로 고정 중**이다(주석: *"빈 페이지여도 정상"*). **본문 단언을 추가**해 이 축을 고정할 것.
  - 신설 3케이스: ①WORKER 응답 본문에 미배정 rawSn 이 없음 ②`totalElements` = 배정 건수 ③REVIEWER 는 전체를 본다(대조군).
  - 중복 방지 케이스: 한 영상에 LABELER+REVIEWER 배정이 있어도 1행.
- **기존 테스트 영향**
  - `ListApiBackwardCompatibilityIT` 「5. 영상 처리 현황」(`videosWithoutParamsUnchanged` 등)은 `requestAs(reviewerToken, …)` 즉 **REVIEWER 토큰** → **무영향**(2026-08-25 확인).
  - `VideoListSearchFilterIT` 는 REVIEWER 토큰 단독 → 무영향.
- **카탈로그** — `docs/test-cases/B-batch-deidentify.md` B-18(TC-VIDEO-001~021)에 **인가 축 케이스가 0건**이므로 신설 케이스를 등재한다(`CLAUDE.md` 「문서 동기화 규칙」 — 같은 커밋에서).
- **수동 확인(선택)** — cudo_246 에서 WORKER dev 토큰으로 `GET /v1/videos` 호출 시 미배정 rawSn 4·5 가 사라지는지.

## §6 관련 설계 ITEM  ★구현 전에 먼저 확정한다

> ⚠ **현재 설계 자체가 모순이다** — `SCREEN-008` 이 WORKER 에게 전체 목록 + *"항상 노출"* 되는 상세 버튼을 규정하는데, `API-043` 은 그 버튼이 WORKER 에게 403 이라고 규정한다. 즉 **설계가 막다른 버튼을 명시**하고 있다. 코드만 고치면 다음 감사가 "설계 위반"으로 되잡으므로 ITEM 을 먼저 고친다.

| ITEM | 타입 | 무엇을 어떻게 | 근거 |
|---|---|---|---|
| API-042 | api_endpoint | `description` 에 **조회 범위 규칙**을 추가 — *"REVIEWER 는 전체 영상, WORKER 는 본인에게 LABELER 로 배정된 영상만 조회된다. 범위 제한은 403 이 아니라 결과 축소이며, 요청 파라미터로 이 범위를 넓히거나 지정할 수 없다."* ★**403 응답 정의는 두지 않는다** | 실측상 이 엔드포인트에 사용자 축 서술이 0건. 좁히기는 거부가 아니라 축소여야 한다(`scopedToSelf` 확정 관례) |
| SCREEN-008 | screen_spec | `purpose` 의 *"조회는 REVIEWER/WORKER 공통"* → *"REVIEWER 는 전체 영상을, WORKER 는 본인에게 배정된 영상만 조회한다"* 로 정정. 「영상 목록 테이블」 섹션의 *"영상 상세 버튼(항상 노출)"* 근거도 함께 정정 | 목록이 좁혀지므로 "항상 노출"이 더 이상 막다른 버튼이 아니게 된다 — 그 근거 변화를 `change_summary` 에 남긴다 |
| SCREEN-011 | screen_spec | 대시보드가 API-042 를 소비하므로 **WORKER 시각에서 「최근 완료 영상」이 본인 배정분으로 좁아진다**는 점이 본문과 어긋나지 않는지 확인하고, 어긋나면 정정 | `consumes` 에 API-042 포함. 사용자 확정으로 예외를 두지 않음 |

⚠ **`screen_spec` 쓰기 규율** — 이 타입은 본문에 `[폐기]` 표기를 두지 않는다(발주처 산출물). **원소째 정정**하고 옛 서술은 `change_summary` 로 보존한다(`.claude/rules/logicraft-integration.md` §2-A 예외).

**cascade 예상 하위**: `AC-049` · `AC-050`(SCREEN-008 `covered_by`) · `UC-018`(SCREEN-008 `realizes`) · `DFEAT-007`(API-042 참조) · `TEST-001`(SCREEN-008 `references_backward`) · `MOD-003`(`realizes_backward`) · SCREEN-008 정적 렌더 미러(`source_hash` 대조 필요)

**확정 (Phase 3.6 — 2026-08-25)**

| ITEM | 버전 | 반영 내용 |
|---|---|---|
| API-042 | v7 → **v8** | description 에 조회 범위 규칙 추가. 403 응답 정의는 두지 않음(결과 축소). 파라미터·응답 스키마 무변경 |
| SCREEN-008 | v42 → **v44** | purpose 정정. ⚠ v43 에서 「컬럼」이 「컴럼」으로 손상돼 v44 로 즉시 복구(기대본 대조로 검출) |
| **ROLE-002** | v6 → **v7** | ★cascade 중 발견한 진짜 갭 — SCREEN-008 항목만 범위 서술이 빠져 있었다. 이 문서는 본문과 다른 화면 항목(대시보드·작업 목록·영상 상세)에서 이미 「본인 배정분」을 규정하고 있었다 |

**대조 후 무변경 판정** — SCREEN-011(대시보드: API 조합만 서술, 「전체」 전제 없음 · 범위는 ROLE-002 가 이미 규정) · SCREEN-022(접근: REVIEWER) · DFEAT-007(범위 서술 없음) · TEST-001(배치 흐름, 축 다름) · UC-018(적재 흐름, 조회 범위 0건)

**열지 않은 층 (0건이 아니라 미확인)** — MOD-003 · SD-013 · AC-049 · AC-050 · NAV-001 · SHELL-001 · CMP-010 · CDIAG-001 · UC-011. 타입·제목상 조회 범위 축과 다르다고 판단해 열지 않았다.

⚠ **정적 렌더 미러 stale — 후속 필요**: SCREEN-008 의 `main` 렌더가 구 문구(「조회는 REVIEWER/WORKER 공통」)를 담고 있다(HTTP 200 으로 받아 실측: 구 문구 1건 / 신 문구 0건). `source_hash` 는 `sections` 의 해시라 일치하지만 **purpose 축은 그 오라클이 덮지 않는다.** 렌더 재게시가 필요하다 — 로컬에서 미러를 직접 고치면 다음 SYNC 가 서버 판으로 되돌리므로, 결정적 생성기로 재생성해 게시해야 한다.

★ **교차 검증** — ROLE-002 가 이미 「대시보드 — 본인 배정분 기준」이라고 규정하고 있어, 사용자가 확정한 대시보드 축소가 기존 설계와 정합함이 확인됐다. 코드가 그 규정을 따르지 않고 있었던 것이다.

⚠ **덮인 stale 표식 (쓰기가 자동 해제하므로 기록)** — API-042 `false` · SCREEN-008 *"API-042의 data.implementation 변경 — consumes"* · ROLE-002 *"SCREEN-008의 data.implementation 변경 — granted_on"* · SCREEN-011 *"API-042의 data.parameters 변경 — consumes"*(무변경이라 보존됨) · DFEAT-007 · SCREEN-022 · TEST-001 · UC-018(전부 이번 축과 무관한 기존 표식). ROLE-002 의 직전 리비전은 *"본문은 바꾸지 않는다"* 는 stale-ack 도장이었고, **그 도장이 이번 갭을 덮고 있었다.**

## §7 구현 로그

| 일시 | 도메인 | 에이전트 | 결과 | QA | 커밋 |
|---|---|---|---|---|---|
| 2026-08-25 | DOMAIN-003 | klid-d003-implementer | 구현 완료 (빌드·테스트는 타 세션 FULL 충돌로 미실행 → QA 가 실측) | pass_with_notes | (대기) |
| 2026-08-25 | DOMAIN-003 | klid-qa-verifier | **62 tests / 0 failures / 0 skipped** (6 클래스, `cleanTest` 포함, XML mtime 으로 실행 증거 확인) | — | — |
| 2026-08-25 | DOMAIN-003 | klid-d003-implementer | QA 지적 1건(javadoc 과잉 주장) 정정 · `compileJava` executed 통과 · **테스트 재실행은 타 세션 FULL 진행 중이라 미실행** | — | (대기) |

**★mutation 2종으로 가드 실효성 실증 (QA 수행)**

| mutation | 결과 |
|---|---|
| `countQuery` 술어만 무력화 | `size=2` 케이스 **단독 RED**(`totalElements` 3→16), 나머지 6건 통과 — **함정 재현** |
| 스코핑 전면 무력화 | scope IT 7/7 RED + 역할게이트 1건 RED. **REVIEWER 대조군·`role=null` 403 은 green 유지**(회귀 없음) |

원복: `shasum -c` 2파일 OK · `MUTANT` 잔재 0 · 재실행 동일 62 tests green.

**수용기준 6/6 충족.** 「레거시 오버로드에 main 호출자 0건」 주장도 QA 가 grep 으로 독립 검증
(`videoQueryService.list(` main 0건 / 테스트 12건, `VideoQueryService` 주입 main 클래스 = `VideoController` 하나).

## 검증 로그 (2026-08-25 저녁)

| 검증 | 결과 |
|---|---|
| 독립 QA — 변경 범위 6클래스 | ✅ **62 tests / 0 failures**, mutation 2종 실증 |
| javadoc 정정분 재확인 (2클래스) | ✅ **15 tests / 0 failures** (XML 2개 · mtime 17:57:27 · `cleanTest` 후 신규 생성). 「기계로 고정」 주장 잔존 0건 |
| **전체 회귀(FULL)** | ❌ **미완주 — 판정 불가**(red 아님, 결과 XML 0개) |

**FULL 미완주 사유 (확정)** — 코디네이터가 **GC 데스 스파이럴로 판단해 중단**시켰다(exit 144 = `kill`).
관측: 32분 경과 시점 G1 힙 `total 2,097,152K / used 2,093,387K`(99.8%) · young 1 region(1MB) · survivors 0 ·
CPU 753% 인데 **결과 XML 0개 · 빌드 산출물 3분간 변경 0건 · Testcontainers PG CPU 0.52%**(DB 무활동).
머신 전체도 swap 7,168M 중 6,154M 사용. QA 도 *"종료 직전 8분간 output.bin 증가가 7KB 로 사실상 멎었다"* 로 같은 정체를 관측했다.
⇒ 테스트 실패가 아니라 **진행 불가 상태를 끊은 것**이며, 이번 변경과의 인과는 보이지 않는다(실패 테스트 0건 기록).
단 이는 **부재의 증거이지 무결의 증거가 아니다.**

⚠ **알려진 오염 가능성** — 우리 종료(18:32:5x)와 `preset` 워크트리 기동(18:32:54)이 **초 단위로 겹친다.**
두 워크트리는 Testcontainers DB 를 공유하므로 그 중첩 구간에 상호 오염이 있었을 수 있다 — **`preset` 세션 결과도
이 구간을 의심할 여지가 있다.**

## ⚠ 커밋 전 남은 것

1. **전체 회귀(FULL) 미완주** — 시도했으나 GC 스래싱으로 중단(위 검증 로그). 검증은 변경 범위 6클래스 한정이다. 이 워크스페이스는 워크트리 8개가
   **Testcontainers 컨테이너·DB 를 공유**하므로(build 디렉터리만 분리) 타 세션 FULL 과 동시 실행하면 양쪽이 오염된다.
   실제로 `vlm-parsing` 워크트리 FULL 이 계속 돌고 있어 두 차례 보류했다. **그 회귀가 끝난 뒤 FULL 1회가 필요하다.**
   ⚠ 「지금 gradle 이 비어 있다」는 판정은 **확인과 실행 사이에 새 런이 시작될 수 있어** 매번 재확인해야 한다
   (실측으로 그 일이 일어났다). 판정은 `pgrep -f GradleWorkerMain` 의 **tmpdir 경로**로 워크트리를 식별해서 한다.
2. **javadoc 정정분은 컴파일만 확인**됐다(`compileJava` executed, BUILD SUCCESSFUL). 주석 전용이라 동작이 달라질 수
   없다는 것은 **추론이지 실행 증거가 아니다.**
3. **SCREEN-008 정적 렌더 미러 재게시** — §6 에 기록한 후속.

## QA 가 남긴 low 이슈 (미조치 · 의도)

- `VideoContentRoleGateTest(reviewerSeesAllOnVideoList)` 가 기대값을 「전체 비파생 영상 수」로 잡아 공유 컨테이너의
  전역 상태에 의존한다. **관측된 실패는 없고** QA 도 「현행 유지 수용 가능」으로 판정해 손대지 않았다 — 지금 고치면
  검증된 트리가 다시 미검증이 된다. 고친다면 `cctvNameKeyword` 로 자기가 심은 영상만 좁혀 대조하는 방향.

## 후속 (CO 범위 밖)

- **역할 스코프 판정 공용 헬퍼 추출** — `AssignmentService.scopeForActor` 가 private 이고 반환형이 그 도메인 전용
  조건 객체라 경계를 넘는다. 현재는 규칙을 최소 복제했고 **동치를 검증하는 가드가 없다**(javadoc 에 그 사실을 명시).
  공용화하려면 `assignment/` 또는 `common/security/` 를 함께 고쳐야 한다.
- **기존 술어 3개(KEYWORD/SKIPPED/FAILED)의 countQuery 축 점검** — 회귀 가드가 전부 기본 size 라 같은 이유로
  실질 미검증일 수 있다(이번에 발견한 함정의 파급).

**미반영·보류 항목**

- **별건 권고 ①** `frontend/src/lib/queryClient.ts(queryClient)` — `retry: 1` + TanStack 기본 `retryDelay`(첫 지연 1000ms) 때문에 **403 하나당 요청이 2회** 나간다. 로그에서 "1초 간격 반복"으로 보이던 것의 정체이며 **폴링이 아니다**(FE 전 `refetchInterval` 은 2000/3000/5000ms). 4xx 재시도 제외 검토 대상.
- **별건 권고 ②** `frontend/src/pages/VideoDetailPage.tsx(VideoDetailPage)` — `ErrorState` 가 BE 메시지를 고정 문구(*"영상 정보를 불러올 수 없습니다"*)로 덮어 사용자가 403 사유를 볼 수 없다.
- **별건 결함 후보** `frontend/src/components/layout/Lnb.tsx` — `const visible = g.items.filter((i) => !role || i.allow.includes(role))` 이라 `role` 이 `undefined` 면 **모든 메뉴가 노출**된다(fail-open). 실제 도달 가능성 미검증.
- **403 WARN 의 바깥 반복 주기(5~17초 불규칙)** 출처 미확인 — FE 에 자동 소스가 없어 사용자 재클릭·재마운트로 추정되나 단정하지 않는다.
