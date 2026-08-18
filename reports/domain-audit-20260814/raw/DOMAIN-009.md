# DOMAIN-009 게시판·공지 — 도메인 점검 원본

> 스킬 `mc-logi-domain-review` v1.5.0 (read-only) · 10차원 · 재개 세션(2026-08-14)
> 인벤토리: 활성 DFEAT 2(`DFEAT-037` 조회 · `DFEAT-038` 작성·발행) · API 10(`API-095`~`101`·`106`~`108`) ·
> 화면 4(`SCREEN-030` 목록 · `SCREEN-031` 상세 · `SCREEN-036`·`SCREEN-037` — 뒤 둘은 2026-08-06 신설) ·
> ERD 2(`ERD-006` v1 `LS_NTC_BBS`/`LS_ATCH_FILE` · `ERD-022` v2 `LS_NOTICE`/`LS_NOTICE_ATTACH`) · SD 4 · MOD 8
> **다이어그램 커버리지 0%** — missing=[DFEAT-037, DFEAT-038]. UC·AC·SEQ·TEST 귀속 **0건**.

---

## coverage (4건 — P1 2 / P2 2)

### ★★ 이 도메인의 가장 중요한 결과는 "재현되지 않음" 이다
**6개 도메인 연속이던 「DFEAT 링크 배열 전건 공란 → 도메인 API 전건 orphan」 패턴이 여기서는 없다.**
`DFEAT-037.implemented_by_endpoints=[095,096,107]` ∪ `DFEAT-038=[097,098,099,100,101,106,108]` = **활성 API 10건과 정확히 일치, 차집합 양방향 0**.
`persists_in_tables`(`LS_NOTICE`·`LS_NOTICE_ATTACH`)도 채워져 있다.
⇒ **그 패턴은 "프로젝트 전역 구조 결함"이 아니라 도메인별 편차다.** 최종 REPORT 의 클러스터 서술을 이에 맞춰 조정할 것.
(단 `invokes_apis`·`triggers`·`consumes`·`uses_constants`·`acceptance_rules`·`related_acceptances` 는 여전히 전건 공란)

### D009-COV-002 (P1) — DFEAT 2건 모두 UC backing 0 + FEAT 앵커 자체가 없음
`get_neighbors(DFEAT-037/038).backward` **양쪽 다 `[]`**. UC 전건 32(활성 25) 제목 열거 결과 게시판·공지 축 **0건**.
FEAT 9건(FEAT-001~009)에도 게시판 FEAT 가 없고, **두 DFEAT 에 `specializes_feature` 키 자체가 부재**해
COV-2 의 표준 검출 경로(`UC.realizes_features` ∋ DFEAT 의 `specializes_feature`)가 **구조적으로 성립할 수 없다**.
두 DFEAT 모두 사용자 상호작용 기능이며 백엔드 전용 명시가 없어 UC 불필요 예외에 해당하지 않는다.

### D009-COV-003 (P1) — 화면 4건이 DFEAT 를 전혀 인용하지 않음
`SCREEN-036` **전체 read** 결과 `references_features`·`references_dfeats` **키 자체가 없다**(섹션 단위 `references_features` 는 2개 섹션 모두 `[]`).
4건 전부 `realizes_use_cases=[]`·`covered_by_acceptances=[]` 이고 `consumes_apis` 만 채워져 있다.
`DFEAT-037` 은 화면을 **산문으로만** 지목(*"화면: 공지 목록(/notice, SCREEN-030)·상세(/notice/:id, SCREEN-031)"*),
`DFEAT-038` 은 **v2 화면 언급이 아예 없고 1차 baseline 만 인용**한다.
⇒ 추적이 `SCREEN→API→DFEAT` 우회 체인으로만 가능하고 DFEAT 축 직결 링크는 0.

### D009-COV-001 (P2) — 책임 인벤토리 기준선 부재 (게이트)
도메인 description 이 산문 4문장뿐이라 **번호 매긴 책임 목록도, "책임 제외" 섹션도 없다**(→ COV-8 의 negative 기준 부재).
`ubiquitous_language` 도 2항목("공지"·"게시글")뿐.
⇒ auditor 가 **파생 인벤토리 R1~R8**(작성/수정/발행·발행취소/삭제/첨부/목록/상세/권한분리)을 만들어 수행했고 **그 기준으로는 COV-7·8·9 전부 0건**이었다.
⚠ 이 인벤토리는 **감사자 해석이지 권위 기준이 아니다.**

### D009-COV-004 (P2) — 실현 흐름 도해 0
`list_diagram_coverage` 0% · SEQ 전건 14 중 게시판 흐름 **0건** · 도메인 UC 0건이라 COV-4(UC→SEQ) 검사 자체가 불성립.
**DRAFT↔PUBLISHED 상태 전이와 첨부 업로드/삭제 흐름이 어디에도 도해돼 있지 않다.**
severity 는 P0/P1 트리거가 문자 그대로 성립하지 않고 두 DFEAT priority 가 `could` 라 **P2**(UC 층이 서면 P1 승격 검토).

### ★ coverage 가 넘긴 강한 단서 (다른 차원 소관)
- **STL 후보(P0/P1급)**: `DFEAT-038.description` 의 *"발행일시(`PUB_DT`)는 **최초 발행 시점만 기록**"* 은 **폐기된 서술**이다.
  `API-100.change_summary`(v4)가 *"구 서술('최초 발행 시점에만 기록되고 발행 취소 후 재발행해도 불변')은 … 폐기한다"*,
  `API-101.change_summary`(v4)가 *"발행을 취소하면 이 필드는 비워진다"* 로 정정했는데 **DFEAT 본문만 구 모델**로 남았다.
  ⚠ **`stale` 플래그는 false 라 플래그로는 안 잡힌다.**
- **STL**: `DFEAT-038` 이 2026-08-06 신설 `SCREEN-036`·`SCREEN-037` 을 반영하지 않음.
- **ACC**: DFEAT 2건 `acceptance_rules`·`related_acceptances` 전건 `[]` + 화면 4건 `covered_by_acceptances` 전건 `[]` → **도메인 전체 수용기준 0건**.
- **LINK/IMPL 비대칭**: DFEAT 2건은 `implemented/100` 인데 `modules`·`records` 공란. 반면 **같은 도메인 `SCREEN-036` 은 `modules[MOD-038,036,027,029]`·`records[IMPREC-006]` 이 채워져** 있고 도메인 backward 에 MOD 8건이 `implements_in` 으로 붙어 있다 — **DFEAT 측만 비어 있다**.
- **LINK 확인 필요**: `SCREEN-030`(공지 **목록**, `/notice`)이 `consumes_apis` 에 `API-097`(POST /v1/notices, **생성**)을 포함 — 진입 버튼만인지 확인.
- **SCHEMA/STL**: `ERD-006`(v1)이 **여전히 `belongs_to_domain` 으로 활성 연결**돼 있는데 `brownfield.notes` 는 *"v1 원형 ERD 는 폐기 상태를 유지한다"* 고 한다 — 실제 status 확인 필요.
- **POL 확인 완료(오탐 아님)**: DOMAIN-009·DFEAT-037·DFEAT-038 의 `decided_by` 는 **`ADR-014`**(=011 의 후속, 정상)이며 notes 도 *"ADR-011, superseded"* 로 폐기 사실을 명시해 인용한다. **폐기 ADR 을 현행 근거로 인용한 사례 0건.**

### coverage 미확인 층
1차 소스 grep OFF → **COV-6 검증 불가**(1차 화면 `SKKLID-UI-02-03-01~03`·`03-06-01~04` 중 무엇이 2차에서 누락됐는지 미대조. `migrated_from` 링크 `LEGACY-085`·`LEGACY-127` 만 확인) ·
UC 25건의 `realizes_*` **본문 전건 열람은 안 함**(근거는 ①DFEAT backward 0 ②UC 제목 전건 열거 ③FEAT 앵커 부재 3축) ·
정적 렌더 미러·로컬 키트·위키·test-cases·코드 층 미확인 · **COV-10 N/A**(SVC/IAPI/LIB 프로젝트 전역 0건).

---

## links (5건 — P1 1 / P2 4)

- **D009-LINK-001 (P1)** 화면 4건 전부 `realizes_use_cases=[]` + DFEAT 2건 backward 0 → **UC 축 추적이 시작점부터 단절**.
  UC 전건 32(활성 25 + retired 7) 열거 결과 게시판·공지 축 **0건**. (`required_roles` 는 반대로 4건 모두 채워져 있다)
- **D009-LINK-002 (P2)** `SCREEN-031.consumes_apis` 8건 중 **`API-098`·`API-106`·`API-108` 이 어느 섹션에서도 참조되지 않는다**.
  ★**그래프가 기계적으로 뒷받침** — 나머지 5건은 `consumes`+`references` 두 링크가 다 있는데 이 3건은 `consumes` 만 있다.
  실제 소비처는 `SCREEN-037`(`sections[2].references_apis=[096,098]`·`sections[3]=[106,108]`, consumes 4건과 정확히 일치). **수정·첨부가 전용 화면으로 분리된 뒤 남은 잔재** → auto_fixable
- **D009-LINK-003 (P2)** `SCREEN-030` 의 '새 게시글 작성' 버튼이 **API 를 호출하지 않고 `/notice/new` 로 이동**한다고 섹션이 서술하는데, `triggers_api`·`references_apis`·`consumes_apis` **세 곳에 `API-097` 링크가 남아** 같은 섹션 안에서 서술과 링크가 어긋난다. 실제 호출은 `SCREEN-036`
- **D009-LINK-004 (P2)** `API-108.description` 이 소비처를 *"**수정 모달(NoticeEditModal)** 에서 호출"* 로 적는데, `SCREEN-031` 이 *"전용 수정 화면으로 이동 — **모달을 열지 않는다**"* 로 명시적으로 부정한다. 실제는 `SCREEN-037` → auto_fixable
- **D009-LINK-005 (P2)** API 10건 전부 `implements_features=[]`. ⚠ **그러나 P1 이 아니다** — `DFEAT→API implements` 링크가 **양방향 materialize 돼 있어 역방향 추적이 정상 동작**하고, 이 도메인에 대응하는 **FEAT 자체가 없어**(FEAT 9건에 게시판 축 없음 + DFEAT 에 `specializes_feature` 키 부재) **지금 채울 대상이 없다**. `DFEAT.description` 의 *"v2 재구현 (R1 외 추가)"* 로 보아 FEAT 미생성이 의도로 읽힌다

### ★ links 가 확인한 "위반 없음" (근거 명시 — 재조사 불필요)
- **LINK-1 정합** — DFEAT 본문이 인용한 API(`API-107`·`API-108` cascade)가 각각 자기 `implemented_by_endpoints` 에 존재.
- **LINK-7 정합** — `persists_in_tables=[LS_NOTICE, LS_NOTICE_ATTACH]` ↔ `ERD-022.tables` 텍스트 일치, 미커버 0. `ERD-006` 의 v1 테이블은 어느 DFEAT 도 참조하지 않지만 **`ERD-006.status=deprecated` 이고** notes 가 의도를 명시해 갭 아님. (⚠ coverage 가 "활성 연결" 로 의심한 건 **여기서 해소** — status 는 deprecated 다)
- **LINK-9 정합** — `ERD-022` 가 `ERD-006` 을 인용하나 *"재사용하지 않고 v2 신규 테이블로 구성"* 이라는 **이력 명시**라 오탐 규칙 해당. superseded ADR 도 notes 의 *"v1 폐기(ADR-011, superseded) → ADR-014로 재도입"* 뿐이고 `decided_by` 는 활성 `ADR-014`.
- **LINK-3·LINK-4 는 "위반 없음"이 아니라 "대상 없음"** — 이 도메인에 SEQ·EVT·INT·TEST·NFR 귀속 ITEM 이 **0건**이다(backward 29건 전수 열거로 확인).
- **대리 측정 결과 선언 참조 ID 는 전부 materialize** — `implemented_by_endpoints` 10/10 · `consumes_apis` 15/15 · `required_roles` 6/6 · `legacy_artifact_id` 2/2 · `collaborators` 1/1. **예외는 `brownfield.decided_by`(ADR-014) 5건인데 이는 프로젝트 전역에서 decided_by 링크가 materialize 되지 않는 플랫폼 측 문제**로 이미 판정된 사안이라 갭 아님.

### ★★ links 가 넘긴 핵심 단서 — `PUB_DT` 3파전 + 물리명 불일치
| ITEM | 서술 | 판정 |
|---|---|---|
| `API-100`(v4)·`API-101`(v4) | **"발행 취소 시 비워진다"** | **최신 정정본** |
| `ERD-022.PBLCN_DT.description` | *"최초 발행 일시 — 발행 취소 후 재발행해도 불변"* | **stale** |
| `DFEAT-038.description` | *"발행일시(PUB_DT)는 최초 발행 시점만 기록"* | **stale** |

**물리 컬럼명도 갈린다** — `DFEAT-038` 은 `PUB_DT`, `API-097` 은 `PUB_STTS_CD` 라 쓰는데 **ERD-022 정의는 `PBLCN_DT`·`PBLCN_STTS_CD`** 다. → schema 소관.

### links 차원 단서
- **백엔드 code_module 미등록** — `MOD-034~038` 은 SCREEN 4건만 `realizes` 하고 **API·DFEAT 를 구현하는 모듈 링크 0건**(`get_neighbors(API-095).backward` 에 MOD 없음). DFEAT·API 전건이 `implemented/100` 인데 `modules`·`records` 공란.
- 화면 4건 모두 `sections[].references_features` 전건 `[]` + 최상위에 `references_features`·`references_dfeats` **키 자체 부재**(전체 read 확인) — links.md 에 룰 코드가 없어 links 에서 미보고(coverage 가 D009-COV-003 로 보고함).
- `DFEAT-037` 은 담당 화면을 명시하는데 `DFEAT-038` 에는 SCREEN-036/037 인용이 없어 **두 DFEAT 의 서술 대칭이 깨짐** → content 소관.

### links 미확인 층
`links.unresolved` **측정 불가**(쓰기 응답 전용 — 0건이 아니다) ·
정적 렌더 **메타만 확인**(`SCREEN-031` 의 main·delete-confirm 두 렌더는 `source_hash` 가 `7d4a6148…` 로 **동일**해 sections 기준 같은 판. **HTML 본문 내 API 인용 잔재는 미확인**) ·
`SD-007/008/010/011` 은 **링크 존재만 확인**(4/4 정상 매핑) 본문 미대조 · 로컬 키트·위키·test-cases·코드 층 미확인 · 1차 소스 grep OFF.

---

## schema (10건 — P0 1 / P1 6 / P2 3)

### D009-SCH-001 (P0) — 게시글 작성·수정 계약대로 보내면 **전건 400**
`API-097`·`API-098` 의 `request_body` 3필드가 **필드명·타입 모두** 구현과 다르다.

| ITEM 공표 | 실제 구현(`NoticeCreateRequest`·`NoticeUpdateRequest`) |
|---|---|
| `noticeTitle` (string, max200, required) | `title` `@NotBlank @Size(max=200)` |
| `noticeCn` (string, required) | `content` `@NotBlank` |
| `pinYn` (string enum `Y`/`N`, default `N`) | `pinned` **boolean** |

`@JsonIgnoreProperties(ignoreUnknown=true)` 라 `noticeTitle`/`noticeCn` 은 **조용히 버려지고** `title`/`content` 가 null → `@NotBlank` 위반 → **항상 400 INVALID_INPUT**. FE(`types.ts`)·`SCREEN-036`(Checkbox "중요 공지")도 boolean 축이다.

### P1 6건

| ID | 요지 |
|---|---|
| **D009-SCH-002** | **실재하지 않는 물리 컬럼명 4종을 9개 ITEM 이 공표** — `PIN_YN`→**`UPEND_FIX_YN`** / `ATTACH_SN`→**`ATCH_FILE_SN`** / `PUB_STTS_CD`→**`PBLCN_STTS_CD`** / `PUB_DT`→**`PBLCN_DT`**. `grep -rin "pin_yn"` 마이그레이션·schema.sql **0건**. ⚠ 리포지토리 javadoc 도 `PIN_YN` 을 써서 **같은 뿌리** → auto_fixable |
| **D009-SCH-003** | **역방향** — 구현 `NoticeResponse.writerName` 이 **API 응답 스키마 5건 어디에도 없다**. 구현 javadoc 이 *"화면은 writerName 을 표시하고 없을 때만 폴백"* 이라 하고 `SCREEN-031` 도 *"작성자(writerName, 없으면 regId 폴백)"* 로 그리는데 **API-096 이 제공하지 않는 필드를 화면 정의서가 그리고 있다** |
| **D009-SCH-004** | `API-095.parameters` 가 `page`·`size` **2건뿐**인데 **같은 ITEM 의 400 응답이** *"검색 필드(field) 화이트리스트 위반"* 을 말하고 구현은 `field`·`keyword` 를 받으며 `SCREEN-030` 에 검색 UI 가 있다 |
| **D009-SCH-005** | `mdfcnDt` 를 **nullable(미수정 시 null)** 로 공표하나 컬럼은 **NOT NULL + DEFAULT CURRENT_TIMESTAMP** 이고 `@PrePersist` 가 **생성 시점에 채운다** → null 이 될 수 없다(응답 5건 + example 전부) |
| **D009-SCH-006** | 코드값 컬럼 `PBLCN_STTS_CD` 가 **`varchar(16)`** — 등록 코드 도메인은 **코드V10·코드V20·코드C1~C12 뿐**. 실 enum 은 `DRAFT`(5)/`PUBLISHED`(9)라 **코드V10 으로 수용 가능** |
| **D009-SCH-009** | `ERD-022.PBLCN_DT`·`DFEAT-038` 이 *"최초 발행 일시 — 발행 취소 후 재발행해도 불변"* 인데 구현 `unpublish()` 가 **`pubDt = null`**. `API-101`(v4)이 *"구 서술('PUB_DT 는 유지(최초 발행 이력 보존)')은 **폐기한다**"* 로 정정했는데 **ERD-022 는 그 뒤인 2026-08-09 에 갱신되고도 구 서술 유지** → auto_fixable |

### P2 3건
- **D009-SCH-007** `NOTICE_CN` 이 ERD·DDL·D9 설계서 3중으로 `text` 인데 **이 ERD 가 스스로 등록한 표준용어는 `V(4000)`** 이고 양쪽 다 도메인 미연결(등록 가능한 `내용V4000` 이 실재)
- **D009-SCH-008** 논리명 드리프트(`발행상태` vs D9 설계서 `발행상태코드` — 물리명이 `_CD` 라 후자가 표준 조합) + **DDL 의 `DEFAULT CURRENT_TIMESTAMP` 3건이 ERD 에 미기재** → auto_fixable
- **D009-SCH-010** `ERD-022` 가 **스쿼시로 사라진 `V56__create_ls_notice.sql`** 을 근거로 제시(활성은 V1~V6, 그 파일은 테스트 아카이브에만 존재) → auto_fixable

### ★ schema 3중 대조 결과 — **ERD-022 자체는 매우 정확하다**
ERD-022 의 컬럼 17개가 `V1__baseline.sql`·`deploy/onprem/db/schema.sql`·D9 설계서와 **물리명·타입·길이·nullable·default 완전 일치**.
FK(`fk_lnta_notice ON DELETE CASCADE`)·인덱스 2종도 일치. **가공의 테이블·누락 테이블·잘못된 참조 대상 0건** — D007 과 정반대다.
⇒ **이 도메인의 결함은 ERD 가 아니라 API 계약 쪽에 몰려 있다.**

### ★ CSV 정본 grep 실적 (MCP 검색 미사용)
정본 6종 로드(공통 3,284/123/13,176 · 사업 471/45/1,373). **컬럼 17개** → 단어 축 23약어 + 용어 축 15물리명 **2단 확인**.
**물리명 자체는 17개 전부 등록**(미등록 0). 남은 이슈는 **도메인(타입·길이) 축 2건**뿐.
- **단어 축 0건 오판 회피**: `NOTICE`·`TITLE` 이 단어 축엔 없지만 **용어 축에 복합용어로 등록**돼 있음을 확인.
- **우선순위 실측**: `UPEND_FIX_YN` 은 행안부 `여부C1`(C,1) / 사업 `여부V1` 로 갈려 **행안부 우선 → char(1) 이 맞다**(ERD 일치). `ORGNL_FILE_NM` 은 행안부 명V300 / 사업 명V200 → **300 이 맞다**(ERD 일치).
- ⚠⚠ **`NOTICE_*`·`PBLCN_STTS_CD`·`STRG_FILE_NM`·`ATCH_FILE_SN` 의 사업표준용어 출처가 `KLID-저작도구 ERD-022`** 다 — **이 ERD 가 스스로 등록한 항목이라 ERD 검증의 독립 근거가 되지 못한다.** `FILE_SZ` 만 KLID-BM 배포분.

### schema 차원 단서
- **content 축**: `ERD-022` 의 `Flyway V56__…` + **`API-095`~`108` 의 `brownfield.notes` 9건 전부 `커밋 9ca1d33`** 를 달고 있다 → 본문 오염.
- **`ATCH_FILE_SN`·`FILE_SZ` 도메인 판정 보류** — 행안부는 `일련번호N10`·`수N14` 인데 실물은 `bigint`. **프로젝트 전역이 PK·용량에 bigint 를 쓰므로 이 도메인만 위반으로 보고하면 억지 판정**이라 제외(전역 정책 확인 필요).
- `SCREEN-030` 페이지네이션이 `type='Custom'` + `custom_name='Pagination'` 인데 **enum 에 표준 `Pagination` 이 실재** — enum 위반은 아니라 미보고, 카탈로그 일관성 축 대상.
- `API-106.responses` 의 `[폐기]` 표기 보존은 **이 저장소 관례에 부합** — 재보고 금지.

### schema 미확인 층
1차 소스 grep OFF · **SCH-2 미적용**(활성 ERD 17건 전부 physical 이라 페어 관례 없음. 논리 모델은 `KLID_AT_엔티티관계모형설계서.md` 로 관리되나 본문 미열람) ·
SEQ 본문 미열람(제목 축 0건) · SCH-10 N/A · `NoticeService`·`NoticeAttachService` 본문 미열람(컨트롤러·DTO·엔티티로 계약 확정 판단).

---

## stale (13건 — P0 2 / P1 6 / P2 5)

> ★ **STL-1(`stale=true`) 0건** — 활성 32건 전부 `stale=false`·`stale_reason=null`.
> **그런데 P0 2건이 나왔다. 이 도메인에서 플래그는 검출력이 0 이다.**

### P0 2건 — 발행일시 시맨틱이 API 정정을 따라오지 않음

**D009-STL-001 (P0) — `ERD-022.tables[0].columns[5]`(`PBLCN_DT`)** = *"최초 발행 일시 — 발행 취소 후 재발행해도 불변"*
`API-100.change_summary`(v4, 08-07): *"구 서술('최초 발행 시점에만 기록되고 발행 취소 후 재발행해도 불변')은 이 필드가 **최초 발행 이력을 보존한다는 뜻이라 폐기한다**"*
`API-101`(v4) 현행: *"발행 일시는 비워진다 — 이 필드는 최초 발행 이력을 보존하지 않고 **현재 발행 상태의 전이 시점만** 가리킨다"*
⇒ **ERD-022 는 그 뒤인 08-09(v13)에 갱신되고도 최상위 description 만 손대고 `columns[]` 부속 배열은 정합되지 않았다.** → auto_fixable

**D009-STL-002 (P0) — `DFEAT-038.description`** = *"발행일시(PUB_DT)는 최초 발행 시점만 기록"* (v11, **08-09** — API 정정 이후 갱신인데도 잔존) → auto_fixable

### P1 6건

| ID | 요지 |
|---|---|
| D009-STL-003 | `API-108` 이 폐기된 **"수정 모달(NoticeEditModal)"** 을 호출 주체로 명시 — `SCREEN-031` 이 *"모달을 열지 않는다"* 로, `SCREEN-037`(08-06 신설)이 실제 소관임을 명시 → auto_fixable |
| D009-STL-004 | `DFEAT-038` 이 **08-06 신설 `SCREEN-036`·`SCREEN-037` 미반영** — 1차 baseline 화면코드만 인용. **형제 `DFEAT-037` 은 v2 화면을 명시**해 서술 층위가 비대칭 → auto_fixable |
| **D009-STL-005** | 구 물리 컬럼명 4종이 **9개 ITEM 에 잔존**(`PIN_YN`·`PUB_STTS_CD`·`PUB_DT`·`ATTACH_SN`). ★**방향이 명확하다** — ERD-022 는 v13(08-09) 최신이고 **API 군은 v3(06-15) 라 API 쪽이 뒤처졌다** → auto_fixable (schema D009-SCH-002 와 **동일 사안, dedupe**) |
| **D009-STL-006** | ★**`code_module` 4건이 잘못된 `screen_design` ID 를 인용** — `MOD-034`(SCREEN-030)→SD-008(실제 SD-007) · `MOD-035`(SCREEN-031)→**`SD-009`**(실제 SD-010, ⚠**SD-009 는 SCREEN-024 사용자 관리로 타 도메인**) · `MOD-036`·`MOD-038`(SCREEN-036)→SD-010(실제 SD-008). **`MOD-037` 만 정확**. `ai_session_log[0].summary` 에도 같은 오ID 복제 → auto_fixable |
| **D009-STL-007** | ★**확정 화면 사양과 모듈 서술이 정반대** — `SCREEN-036` 이 **두 번** *"첨부파일 관리는 이 화면에 없다 — **게시글 id가 발급되기 전이라** 업로드 대상이 없다"* 고 명시하는데, `MOD-036` 은 *"**AttachmentList**·FieldCounter 신설 공용 컴포넌트 적용"* 이라 하고 `SCREEN-036.implementation.modules` 에도 **`MOD-027`(AttachmentList)이 등재**돼 있다. ⚠ 코드 실물 확인 필요(auto_fixable 아님) |
| D009-STL-008 | `API-095.brownfield.notes` 가 *"**ADR 미작성으로** decided_by 비움"* 이라 적었으나 **`ADR-014` 는 실재**하고 DOMAIN-009·DFEAT-037/038·ERD-022·ERD-006 이 **모두 인용**한다. 나머지 API 9건은 `decided_by` **키 자체가 없다** |

### P2 5건
- **D009-STL-009** ★**정리 결정이 cascade 되지 않았다** — `ERD-022.change_summary`(08-09)가 *"비고에 들어 있던 **저장소 리비전 식별자를 걷어낸다**. 설계 산출물 본문은 이 저장소를 모르는 사람도 그대로 읽을 수 있어야 하는데…"* 라 선언하고 DFEAT 2건도 같이 정리했는데, **API 10건의 `brownfield.notes` 에는 `커밋 9ca1d33` 이 10/10 그대로**(마지막 갱신이 정리 결정 이전) → auto_fixable
- **D009-STL-010** `DFEAT-037/038.brownfield.notes` 에 **해소된 stale 재확인 작업 로그**(*"2026-07-30 stale 재확인 — … CASCADE-ONLY"*)가 상주. 같은 ITEM 이 08-09 에 본문 정리를 했는데 이 부분만 남음 → auto_fixable
- **D009-STL-011** **13건이 `implemented/100` 인데 `modules`·`records` 전부 공란**(DFEAT 2 · ERD-022 · API 10). 같은 도메인 **SCREEN 4건은 채워져 있다**(`IMPREC-004`~`007`). ★**이 도메인엔 BE code_module 이 하나도 없다** — 8건 전부 FE component/page
- **D009-STL-012** `DOMAIN-009.brownfield.status=modified` 인데 **`legacy_source` 키 부재**(하위 ITEM 은 모두 보유 — `ERD-006`=module/`KLID-AI-PF-001`, DFEAT 2건=screen/`SKKLID-UI-*`+`LEGACY-085`·`127`)
- **D009-STL-013** ADMIN→REVIEWER 정정이 title·description·user_story 엔 반영됐는데 **`slug` 만 `게시글-작성수정-관리자`** 로 남음(도메인 자신은 `change_summary` v11 로 같은 정정을 완료)

### ★ stale 이 확인한 "위반 없음" (근거 명시 — 재조사 금지)
- **입력 단서 #3 반증 완료** — `ERD-006.status` 는 실제로 **`deprecated`** 이고 `list_items(include_retired=true)` 가 `retired_items` 로 분리한다. `belongs_to_domain` 링크가 남는 건 **`items.domain_id` 의 그래프 표현이라 정상**. (coverage 의 의심 해소)
- `ERD-022` 가 `ERD-006` 을 인용한 것은 **provenance** — `change_summary` 가 *"남길 사실(v1 원형이 어느 ERD 인지)은 그대로 두고"* 라 명시.
- **STL-5 0건**(`new` 인 ITEM 15건 모두 `legacy_source` 미보유가 정상) · **STL-6 0건**(*"Claude가 생성"* 문구 32건 전수 확인).
- ★**`API-095`·`API-096` 의 `pubDt`(*"DRAFT 면 null"*)는 현행 시맨틱과 정합** — 구 서술은 **`ERD-022` 컬럼과 `DFEAT-038` 두 곳에만** 남았다.
- `SCREEN-031` 도 정합 — *"발행일시(pubDt)는 API 응답에는 있으나 **화면에 렌더되지 않는다**"*.

### stale 미확인 층
1차 소스 grep OFF · **코드 실물 미확인**(STL-007 판정 불가) ·
**정적 렌더 미러 미확인** — `source_hash` **메타만** 봤고 로컬 재계산 대조 안 함. ⚠ `SCREEN-031` 의 두 렌더가 **동일 해시인데 `generated_at` 이 08-13 / 08-08 로 갈리는 점**도 미검증 ·
`SD-007/008/010/011` 본문 미열람(디자인 본문의 `PUB_DT`·수정 모달 잔재 미확인) ·
UC·AC·REQ·TEST·SEQ 귀속분 미확인(**0건으로 단정하지 않음**) · ADR 본문 미열람 · `ui_component` 카탈로그 층 미검사.

---

## policy (8건 — P0 1 / P1 5 / P2 2)

### D009-POL-001 (P0) — `ERD-022.PBLCN_DT` 가 **명시적으로 폐기 선언된** 서술을 유지
`API-100.change_summary`(v4)가 구 서술을 **문자 그대로 인용해 "폐기한다"** 고 선언한 능동적 supersede 다(수동적 불일치가 아니다).
★**제3 ITEM 독립 교차검증**: `API-095`(v3, 06-15) 목록 응답이 *"발행 일시 (**DRAFT 면 null**)"* — 불변 시맨틱이면 발행→취소된 글이 `DRAFT + non-null` 이 되어 **성립 불가**. 즉 **정정 이전부터 계약면은 clear-on-unpublish 만 만족**했다.
★**타임스탬프 반론 기각**: ERD-022(v13)·DFEAT-038(v11)이 08-09 로 API v4(08-07)보다 늦지만 **두 v 의 change_summary 는 *"저장소 리비전 식별자를 걷어낸다"* 뿐**이라 PUB_DT 를 재확인한 편집이 아니다.

### P1 5건 / P2 2건

| ID | sev | 요지 |
|---|:--:|---|
| D009-POL-002 | P1 | `DFEAT-038` 의 같은 구 서술 (STL-002 와 dedupe) |
| **D009-POL-003** | **P1** | `SCREEN-030` '새 게시글 작성' 버튼 — **정본은 섹션 서술(전용 화면 이동)이고 `triggers_api=API-097` 이 폐기 잔재**. ⚠ **links 가 P2 로 본 것을 P1 로 승격** |
| D009-POL-004 | P1 | `API-108` 의 폐기된 "수정 모달" 호출처 (STL-003·LINK-004 와 dedupe) |
| **D009-POL-005** | **P1** | ★**권한 과소 선언** — `SCREEN-031` 이 발행·발행취소·삭제를 담는데 `ROLE-001` 의 그 화면 permission 은 **`["view"]` 뿐**. 대조군은 동사를 부여한다(`SCREEN-019`=view/update/approve · `SCREEN-037`=view/update/delete). **SCREEN-031 만 이탈** |
| **D009-POL-006** | **P1** | ★**ADR-014 가 `references` 로 이 API 군을 붙잡고 있는데 역방향이 전부 비었다** — API 10건·SCREEN 4건 **`decided_by` 키 자체 부재**. 같은 도메인 DOMAIN/DFEAT/ERD 는 모두 인용해 **이탈이 이 14건에 한정**. ADR-014 스스로 *"추적성 감사 지적 가능"* 을 위험으로 기록 |
| D009-POL-007 | P2 | `ADR-014.references` 가 화면을 **개별 열거**하는데 이후 신설된 `SCREEN-036`·`037` **미등재**(ADR 최종 갱신 08-06T01:08 < 화면 생성 08-06T15:59) |
| D009-POL-008 | P2 | API 10건 `brownfield.notes` 의 **커밋 해시 잔존**(STL-009 와 dedupe). ⚠ `ADR-014.context` 에도 있으나 **결정 이력 서술이라 판단이 갈려 제외**(사용자 확인 필요) |

### ★★ 이관 사안 3건 판정 (policy 결론)

**① `SCREEN-030` 버튼 정본 = 섹션 동선 서술**(전용 화면 이동). `triggers_api=API-097` 이 제거 대상.
근거 5중: ①섹션이 *"모달이 아니라"* 로 **구 동선을 능동 부정** ②`SCREEN-036` 이 `/notice/new` 로 실재하며 *"목록 화면의 '새 게시글 작성' 버튼으로 진입"* 자기 선언 ③그 화면의 '작성' 버튼이 `triggers_api=API-097` 보유 ④**대칭 사례** `SCREEN-031` '수정' 버튼은 같은 전용화면 이동인데 **`triggers_api` 를 갖지 않는다** ⑤`ROLE-001` 이 **create 권한을 SCREEN-036 에** 부여.

**② 권한 정책 대조 — 역할 배정 자체는 위반 0건.** `ROLE-001`=REVIEWER / `ROLE-002`=WORKER 확인.
API security 10/10 일치(쓰기·관리 7건 REVIEWER 단독 / 열람 3건 양쪽 + *"WORKER 는 PUBLISHED 만"* 명시).
★**`API-107`(첨부 다운로드)이 WORKER 에게 열린 것은 위반이 아니다** — *"첨부파일 관리는 검수자 전용"* 의 '관리'는 업로드·삭제이고, 다운로드는 *"작업자는 발행된 게시글만 볼 수 있다"* 의 **열람 축**이다.
**ADMIN 잔재 0건.** 유일 이탈이 위 POL-005.

**③ `PUB_DT` 확정 정책 = `API-100`/`API-101`**(취소 시 비움·최초 이력 미보존·재발행 시 재기록). ERD-022·DFEAT-038 이 폐기 서술 보유.
⚠ **파생 확인 필요(policy 범위 밖)**: 확정 시맨틱하에서는 **"최초 발행 이력"을 보존하는 수단이 설계 어디에도 없다.** `API-100.change_summary` 도 *"필요하면 이 필드가 아닌 별도 수단이 필요하다"* 고 남겼다 — **운영상 필요 여부는 사용자 결정 사항.**

### ★ policy 가 검토 후 기각한 것 (재보고 금지)
- **URL 동사 금지 위반 아님** — `/publish`·`/unpublish`·`/download` 는 **상태전이 sub-resource** 로 이 프로젝트의 확립된 관례다(선례 `POST /v1/reviews/{videoId}/approve`·`/deident-reports/{rprtSn}/resolve`·`/frames/{srcSn}/sam2-track`). 금지 대상인 **`?action=publish` 쿼리 분기를 오히려 회피한 형태**.
- **목록 정렬 위반 아님** — *"상단 고정 우선 + 등록일 내림차순"* 은 도메인 확정 사양이고 **고정은 상태가 아니라 게시 속성**이라 `ORDER BY CASE` 금지 정책에 저촉되지 않는다.
- **페이징·래퍼·에러 노출 전부 충족** — page/size optional + max 100 + `ApiResponse<Page<T>>` + 400 에 화이트리스트 위반 명시, 에러 4종 모두 스택트레이스·내부 경로 없음.
- **POL-1(1차 보존) 위반 아님** — v2 신규 테이블 사용은 **정책 위반이 아니라 ADR-014 가 정형화한 정책 그 자체**.
- **POL-2~POL-7 미적용** — 타 프로젝트(관제지원) ADR 체계 기반. 잔재 스캔은 했다: **bff 태그 0건 · `EV99999999` 0건 · 인증 필요 API 10/10 `security[jwt]` 명시**.

### policy 미확인 층
1차 소스 grep OFF · 코드 구현체 미대조(권한 판정은 **API security·ROLE permissions·SCREEN required_roles 3축 상호 대조**로만 수행) ·
`ROLE-001/002` 의 `stale=true` 는 **타 화면(SCREEN-020) cascade 라 이 도메인과 무관** — POL-005 는 플래그가 아니라 permissions 본문 실측으로 판정.

---

## acceptance (3건 — P2 3)

> ★ severity 근거: DFEAT 2건이 공히 **`priority=could`** 라 ACC-002 기본 P1 을 **한 단계 낮춰 P2**.
> 단 둘 다 `implemented`/100 이고 **역할 기반 접근 경계를 실제로 보유**하므로, 도메인 priority 를 재평가해 상향하면 **D009-ACC-002 는 P1 로 올리는 것이 타당**하다(auditor 권고).

- **D009-ACC-001 (P2)** `DFEAT-037` 검증 경로 전무 — `acceptance_rules`·`related_acceptances` **명시적 빈 배열**. 활성 AC 25 + retired 3 전건 열거 결과 게시판 축 **0건**.
  ⇒ description 이 규정한 **정렬 2축 · 역할별 가시성 분기 · 검색 field 화이트리스트 400 · size 상한 100** 어느 것도 검증되지 않는다.
- **D009-ACC-002 (P2)** `DFEAT-038` — 확정 계약 **6종 전부 무검증**: ①검수자 전용 경계 ②DRAFT↔PUBLISHED 왕복 ③발행일시 비보존 ④발행 멱등 no-op 200 ⑤첨부는 저장 이후 수정 화면에서만(작성 화면 `consumes_apis=[API-097]` 단독) ⑥**`API-097`·`API-098` 요청 본문 계약 불일치(schema P0)**.
  ★**⑥은 "AC 가 하나라도 있었으면 계약 왕복에서 걸렸을 결함"이며, 검증 게이트 부재가 실제 P0 을 통과시킨 직접 증거다.**
- **D009-ACC-003 (P2)** 화면 4건 전부 `covered_by_acceptances` **명시적 `[]`**(projection 미지원으로 인한 빈 결과가 아님을 `data_projected_to` 로 확인).
  `required_roles` 가 **목록·상세=ROLE-001+002 / 작성·수정=ROLE-001 단독**으로 갈려 **경계가 화면 축에 이미 선언돼 있는데 그것을 검증하는 AC 가 0건**이다.

### ★ acceptance 가 SKIP 한 것과 그 근거
- **ACC-003(도메인 REQ 검증 AC) SKIP** — DFEAT 2건이 *"v2 재구현 (**R1 외 추가**)"* 라 **발주처 baseline 에 대응 REQ 가 없다**. 룰대로 억지 갭을 만들지 않음.
- **ACC-001(UC 검증 AC) 대상 없음** — 도메인 UC 0건이라 검사할 UC 자체가 없다(coverage 기보고).
- **ACC-004~008 전부 대상 0건** — ⚠ *"'부재'와 'stale' 은 다른 축이므로 stale 계열을 0건으로 보고하는 것이지 **검사 통과가 아니다**"*(auditor 명시).

### acceptance 미확인 층
AC 25건 **본문 전수 텍스트 인용 검사 미수행** — 귀속 판정은 ①제목 전건 열거(28/28) ②링크 축 2축으로 확정. **링크 없이 `notes` 본문에만 '공지/게시판'을 언급한 AC 가 남아 있을 가능성은 배제 못 함**(서버측 전문 검색 수단 없음) ·
1차 소스 grep OFF · 정적 렌더 미러·로컬 키트·위키·test-cases·코드 층 미확인(**ITEM 본문 + 링크 2개 층만**).

---

## requirement (1건 — P2) · 검증 모드 **A**(REQ 귀속 0건)

> **RQ-001~006 전부 SKIP.** 억지 갭을 만들지 않았고, 그 근거를 수치로 남겼다.
> 모드 A 확정 근거: REQ 전건 26(활성 22 + deprecated 4) **전부 title 접두가 `RQ-SFR-06/07/08/09/11/16/17`** 이며 게시판·공지 관련 **0건**. `get_neighbors(DOMAIN-009).backward` 29건에도 requirement **0건**.
> **RQ-004 SKIP 근거가 특히 중요** — RFP 7건 전수 확인 결과 **게시판·공지·가이드라인 전달 채널을 요구하는 항목이 0건**이다. 가장 근접한 `RFP-003`(SFR-08)조차 heading 3개가 라벨링 정확도·버전 관리·데이터마트 연계뿐이고 `related_requirements=[REQ-006~011]` 로 전부 라벨링/버전/동기화 축이다. **하향할 상위 요구가 없다.**

### D009-RQ-001 (P2) — "R1 외 추가" 사실이 **도메인 노드 본문에만 없다**
같은 사실이 **9곳에 중복 기재**돼 있다 — `ADR-014` 4곳(context·justification·negative·risks) + `DFEAT-037/038` 각 2곳 + `API-095` 1곳.
그런데 `DOMAIN-009` 는 `description`·`brownfield.notes` 어디에도 **`R1` 문자열이 0회**다(notes 는 폐기·재도입 계보만 서술).
★`ADR-014.risks` 가 **스스로** *"제출 문서에서 'R1 외 추가' 명시 누락 시 **추적성 감사 지적 가능**"* 이라 적어 둔 **바로 그 리스크가 도메인 축에서만 미완화**다.
★**구조적 대안 없음 확인** — `get_item_schema(domain).allowed_fields` 에 `derived_from_rfp` 류 RFP 링크 필드가 **아예 없다**. 적을 자리는 자유 텍스트뿐. → auto_fixable

### ★★ "R1 외 추가" 추적성 판정 (4축)
| 축 | 판정 | 근거 |
|---|---|---|
| **의도성** | **성립** | RFP 7건 전수 대응 0건 + `ADR-014`(approved, supersedes ADR-011)에 명문화. **폐기→재도입 번복 경위·기각안 2개·채택 근거까지 갖춘 정형 결정**이라 REQ 없음은 누락이 아니라 설계된 상태 |
| **기록 밀도** | **충분** | 9곳 중복 기재 — 단일 지점 소실에 강함 |
| **체인 도달성** | **성립** | `DOMAIN-009.brownfield.decided_by="ADR-014"` 로 **1홉 도달**. 링크 단절 없음 |
| **유일한 구멍** | 도메인 노드 본문 | → D009-RQ-001 |

⚠ **`SCREEN-030/031/036/037` 에 R1 표기가 없는 것은 갭이 아니다** — 화면정의서는 발주처 제출 산출물이라 **RFP 코드(`SFR-*`·`R1 v*`)를 본문에 두지 않는 것이 확정 규칙**이다. 이 4건을 "R1 미표기"로 보고하지 않았다.

### requirement 차원 단서
- **RFP→REQ 상위 커버리지 축이 구조적으로 부재**하므로 **coverage 는 RFP 기준이 아니라 도메인 description 책임 목록 기준으로만 판정해야 한다**(RFP 미커버를 COV 갭으로 키우지 말 것) — 실제로 coverage 는 그렇게 했다.
- **수정 시 `brownfield` 블록 편집이 D009-POL-006·007 과 겹친다** → `mc-logi-update` 에서 **한 번에 묶어 처리**(중복 리비전 방지).
- ⚠ `ADR-014.justification` 이 약속한 *"R1 외 추가 임을 산출물(**위키 v1-gap 체크리스트**)에 명시"* 의 **이행 여부는 미검증** — 대상이 LogiCraft ITEM 이 아닌 외부 `.md` 라 이 차원 범위 밖(문서 동기화 축에서 확인 필요).

---

## diagram (2건 — P2 2)

> ★ **전 다이어그램 타입 6종 44건 전수 스캔**(SEQ 14 · CDIAG 15 · CMP 11 · CTX 1 · CNT 1 · STATE 1 · DEP 1) — 게시판 귀속 **0건** 독립 재확인.
> ★ **SEQ 14건은 제목뿐 아니라 `description` 본문까지 조회**해 확인했다(다른 차원은 제목 축만 봤다). 14건 전부 영상·라벨·비식별·증강·버전·통지 축이며 **게시글/공지/첨부 서술이 본문에 단 한 건도 없다**.

- **D009-DIAG-001 (P2)** DFEAT 2건이 **구조 축(클래스·컴포넌트) 어디에도 없다**.
  ★**구조적 이탈 근거**: `CDIAG` 는 **도메인 1:1 대응이 확립된 패턴**이고 `CDIAG-008`·`CDIAG-009` 는 *"개념 모델 — ERD 없음"* 인데도 작성돼 있다. **DOMAIN-009 는 ERD 2건을 실제 보유하는데도 CDIAG 가 없어 ERD 보유 도메인 중 유일한 이탈**이다.
  `CMP-009`(*"소규모 서브시스템 3종 그룹"*)가 편입될 자연스러운 자리인데 components 13개·relationships 17건에 **게시판 0건** — **depicts 만 빈 게 아니라 본문 자체가 부재**다.
  severity 하향 근거: `priority=could` + *"1차 도메인(단순 CRUD)"* + R1 외 추가 재도입.
- **D009-DIAG-002 (P2)** **DRAFT↔PUBLISHED 상태 머신이 어느 상태 다이어그램에도 없다**.
  `diagram_state` 는 **전역 1건뿐**이고 그것은 `STATE-001`(영상 작업 상태 `LS_RAW_DATA_STATUS`)로 축이 완전히 다르다.
  ★**이 축은 클래스·컴포넌트가 대신할 수 없어 DIAG-001 과 별건**이며, **coverage 가 볼 수 없는 영역**이다.

### ★★ 미도해 흐름 9종 (이 감사의 실질 산출물)
UC·AC·SEQ·TEST·다이어그램이 **전부 0건**이라 아래가 **ITEM 어디에도 표현되지 않는다**.

| # | 흐름 | 수용 축 |
|---|---|---|
| 1 | DRAFT ↔ PUBLISHED 전이(취소 시 DRAFT 복귀) | `diagram_state` |
| 2 | **발행 멱등 — 이미 PUBLISHED 면 no-op 200**(자기 전이) | `diagram_state` |
| 3 | 발행일시 시맨틱(취소 시 비움·재발행 시 재기록·최초 이력 미보존) | `diagram_state` |
| 4 | 역할별 가시성 분기(WORKER=PUBLISHED만 / REVIEWER=DRAFT 포함) | `diagram_state` |
| 5 | **첨부 라이프사이클** — 저장 **이후** 수정 화면에서 1개씩. **저장 전후로 가능한 조작이 갈리는 순서 의존 흐름인데 시퀀스가 없다** | `diagram_sequence` |
| 6 | 화면 동선 — 목록→`/notice/new`, 상세→`/notice/:id/edit`. ★**모달이 아닌 전용 라우트라는 점이 정형에 없어 후행 구현이 모달로 오해할 여지** | `diagram_sequence` |
| 7 | 삭제 확인 모달 — `SCREEN-031` 에 실재하는 **파괴적 액션 확인 단계**인데 흐름 축에 없음 | `diagram_sequence` |
| 8 | 목록 정렬(고정 우선 + 등록일 desc) | API 축 — 도해 대상 여부는 사용자 판단 |
| 9 | 검색 계약(`field` 화이트리스트 400 · `size` 100) | API 축 — 동일 |

### ★ diagram 이 "대상 없음"으로 명확히 구분한 것
- **DIAG-002(폐기 참조)·DIAG-003(dangling)·DIAG-005(본문 stale) 전부 "검사 통과가 아니라 검사 대상 부재"** — 귀속 다이어그램 0건이라 정독할 본문이 없다. **CDIAG/CMP/STATE 가 신설되면 그때 최초로 검사 대상이 생긴다.**
- `depicts` 공란 13건은 **전역 DIAG-004 사안이라 이 도메인 소관 아님**. ⚠ 단 `CMP-009.depicts_dfeats=[]` 는 DIAG-001 수정 시 함께 해소되는 접점이라 **전역 수정과 순서 충돌 조율 필요**.
- **`CNT-001` 에 게시판 컨테이너가 없는 것은 갭이 아니다** — 컨테이너 축은 frontend/backend/ai-server/DB/스토리지 **5종 배포 단위**이지 도메인 단위가 아니며, 게시판은 backend 내부 컴포넌트로 표현되는 것이 정상.

### diagram 차원 단서
- ★`DFEAT-038` 발행일시 서술이 확정 계약과 **정면 충돌** — diagram 이 독립 재확인(STL-002·POL-002·SCH-009 와 동일 사안, dedupe).
- `DFEAT-038` 에 **첨부 라이프사이클 시점 서술 부재** — 저장 방식(*"UUID 안전 파일명"*)만 적고 **'저장 이후 수정 화면에서만'이라는 시점·동선 계약이 없다** → content 소관.

### diagram 미확인 층
1차 소스 grep OFF · 정적 렌더 미러 미확인(**다이어그램 0건이라 미러 대상 자체가 없을 것으로 보이나 확인된 사실은 아니다**) ·
`SD-007/008/010/011` 시각 자산 내부의 상태 전이·동선 도해 미확인(단 `screen_design` 은 `diagram_*` 타입이 아니라 **커버리지 대상이 아니므로 판정에 영향 없음**).

---

## content (14건 — P0 1 / P1 6 / P2 7)

> 방법: `get_item` 30건 전수 정독 + **REST `kit-export` 서버 원문 덤프 기계 대조**. 문자열 **1,952건**(한글 630 / 고유 404).
> 검사 필드 **재귀 전수**(최상위만 훑지 않음) — `responses[].schema.properties[*]` 중첩 전개 · `sections[].components[].{note,label,validation,placeholder}` · `tables[].columns[].code_values` · `related_classes[].note` · `implements_domains[].responsibility` 등.
> 오염 정규식 **12축**.

### D009-CNT-001 (P0) — `code_module` 4건이 **틀린 `screen_design` ID 를 인용**(SD 번호가 한 칸씩 밀림)
구조 필드(진실원): SD-007→SCREEN-030 / SD-008→SCREEN-036 / SD-010→SCREEN-031 / SD-011→SCREEN-037 / **SD-009→SCREEN-024(타 도메인)**

| MOD | realizes_screens | 본문 인용 | 정답 |
|---|---|---|---|
| MOD-034 | SCREEN-030 | SD-008 | **SD-007** |
| MOD-035 | SCREEN-031 | **SD-009**(타 도메인!) | **SD-010** |
| MOD-036 | SCREEN-036 | SD-010 | **SD-008** |
| MOD-038 | SCREEN-036 | SD-010 | **SD-008** |
| MOD-037 | SCREEN-037 | SD-011 | ✅ 유일 정답 |

★**SD↔MOD 간 forward 링크가 없어 본문 인용이 유일한 추적 수단인데, 그 유일한 수단이 틀렸다.**(stale D009-STL-006 과 동일 사안 — **두 차원이 독립 검출**)

### P1 6건
`D009-CNT-002` `API-108` 의 *"수정 모달(NoticeEditModal)"* — **폐기 UI 개념 + 실재하지 않는 FE 컴포넌트명** ·
`D009-CNT-003` **커밋 해시가 9건이 아니라 10건**(전수 스캔으로 정정). ★**9건은 `notes` 값이 `"커밋 9ca1d33"` 단독 — 커밋 해시를 빼면 남는 설계 정보가 0** ·
`D009-CNT-004` `ERD-022` 의 마이그레이션 파일명·버전 ·
`D009-CNT-005` **`responses[].description` 에 어노테이션 13곳**(`@PreAuthorize` 9 · `@Valid` 2 · *"핸들러가 void 반환 + @ResponseStatus(NO_CONTENT)"* 2) + `ResponseEntity<Resource>`. ★**`security[].jwt` 구조 필드가 이미 역할을 선언하므로 정보 추가도 아니다** ·
`D009-CNT-006` `SCREEN-031.note` 에 **FE 훅·상태변수·CSS 유틸**(`publish.mutate(id)`·`loading=remove.isPending`·`whitespace-pre-wrap break-words`·`navigate('/notice')`). ★**형제 SCREEN-036/037 의 note 는 구현 어휘 없이 쓰여 있어 SCREEN-031 만 서술 층이 다르다** ·
`D009-CNT-007` `SD-011.description` 에 **접근성 검증 회차와 조치 이력**(*"Phase 3 WCAG 2.1 검증에서 발견된 2건을 … 교체해 … 통과시켰다"*) — 692자 단일 문단 작업 보고체

### P2 7건
`D009-CNT-008` DFEAT 2건 notes 의 감사 회차 메모 · `D009-CNT-009` `related_classes[].note` 의 **자바 패키지 경로**(`name` 은 사양 어휘라 오염 아님) ·
`D009-CNT-010` `API-095` 400 서술이 **선언되지 않은 `field` 파라미터를 전제**(schema SCH-004 와 동일) ·
`D009-CNT-011` `ubiquitous_language` 의 *"게시글 = 게시판 항목"* **동어반복** — 정작 핵심 용어(발행상태·상단고정·첨부)가 용어집에 없다 ·
`D009-CNT-012` `DFEAT-038` 담당 화면 미인용(형제 비대칭) · `D009-CNT-013` 발행일시 시맨틱 **한 ITEM 안 3중 기술** ·
`D009-CNT-014` **SD 4건 서술 층위가 148자 구조 나열 ~ 692자 작업 보고체로 갈림**

### ★★ 한글 표기 손상 — **①②③④ 전부 0건** (D009 는 미검출 도메인)
① 알려진 오타 15종 → 0 · ② 희귀 음절(고유 340종 중 2회 이하 **58종 전수 문맥 확인**) → 0 ·
③ **1음절+목적격 패턴** 5종 검출됐으나 전부 정상(⚠ `수을` 은 *"최대 글자 수 / 최대 글자 수를"* 을 **공백 넘어 매칭한 정규식 오탐**) ·
④ **낱말 고립도** — 고유 낱말 699종 중 단발 351종, **완전 고립 142종 전수 문맥 확인** → 0.
⚠ 범위 한정: **렌더 미러 HTML 본문과 `ERD-006`(deprecated) 는 대상이 아니었다.**

### ★ content 가 오탐으로 기각한 것
`project_id` UUID 조각 · `code_module.file_path`(그 타입의 지정 구조 필드) · SD/SCREEN 렌더 URL · MOD 제목의 컴포넌트명 · `SCREEN-031` UI 라벨 *"로딩 placeholder"* · `API-106` 의 `[폐기]` 표기.
⚠ **`ai_session_log[].summary` 8건은 `change_summary` 와 같은 취급으로 제외** — 다만 그 안에 **PR 번호 8건 + CNT-001 과 동일한 SD 오인용이 MOD-027·029·034·035·036·038 에 그대로** 있다. **이 필드를 오염 대상으로 볼지는 판단 필요.**

---

## test_scenario (1건 — P1 1)

### D009-TST-001 (P1) — 도메인 귀속 TEST **0건**
귀속 4축 **전부 공집합**으로 입증: ①`related_domains` 5건 전건 공란 ②`exercises_screens` 에 이 도메인 화면 **0** ③도메인 UC 자체가 0 ④`nfr` **전역 조회 결과 이 도메인 0건**.
★**`steps[]` 본문 전수(총 24 step) 확인** — 공지·게시글·첨부·발행 인용 **0회**. 다루는 테이블은 `LS_DATA_RAW`·`LS_DEIDENT_PROC_LOG`·`LS_DATA_META`·`LS_DATA_AUG`·`LS_RAW_DATA_STATUS`·`LS_PORTAL_USER_LABEL` 뿐이고 **`LS_NOTICE`·`LS_NOTICE_ATTACH` 는 0회**.

**severity 근거를 양방향으로 제시**:
- **P0 으로 안 올린 이유** — `priority=could` + *"1차 도메인(단순 CRUD)"* + 도메인 forward 링크가 `DOMAIN-001 collaborates_with(weak)` 1건뿐이라 **cross-domain 통합 경계가 없다**.
- **P2 로 안 내린 이유** — **`API-097`/`API-098` P0 계약 불일치가 실증 사례로 존재**하고(계약 왕복 TEST 1건만 있었어도 검출), 발행 상태전이·멱등·첨부 라이프사이클은 **단순 조회 CRUD 가 아니라 상태 전이 동반 흐름**이다.

⚠ **미확정 사항**: TEST 5건의 `notes` 가 모두 *"대외 연동 경계: KLID-AT-II-00N"* 으로 시작해 **외부 연동 흐름만 담는 것이 관례로 보인다**(내부 CRUD 전용인 이 도메인 제외가 의도일 가능성). **명시 선언이 없어 "의도된 제외"인지 "누락"인지 확정 불가 — 사용자 확인 대상.**
⚠ `docs/test-cases` 에 게시판 TC 가 이미 있는지 확인하지 않았으므로 **LogiCraft TEST 0건이 곧 "검증 자산 전무"를 뜻하지 않는다.**

---

# ✅ DOMAIN-009 완료 — 10/10 차원 · **61건 (P0 5 / P1 27 / P2 29)**

| 차원 | 건수 | P0 | P1 | P2 |
|---|---:|---:|---:|---:|
| coverage | 4 | 0 | 2 | 2 |
| links | 5 | 0 | 1 | 4 |
| schema | 10 | 1 | 6 | 3 |
| stale | 13 | 2 | 6 | 5 |
| policy | 8 | 1 | 5 | 2 |
| acceptance | 3 | 0 | 0 | 3 |
| requirement | 1 | 0 | 0 | 1 |
| diagram | 2 | 0 | 0 | 2 |
| content | 14 | 1 | 6 | 7 |
| test_scenario | 1 | 0 | 1 | 0 |
| **계** | **61** | **5** | **27** | **29** |

## D009 최종 요약 — **뿌리는 하나: 정정·정리 라운드가 API 축을 지나가지 않았다**

| 결정 | 시점 | 반영된 곳 | **안 된 곳** |
|---|---|---|---|
| 발행일시 시맨틱 정정 | 08-07 (API-100·101 v4) | — | **ERD-022·DFEAT-038**(08-09 에 편집되고도 미추종) |
| 저장소 리비전 식별자 정리 | 08-09 (DFEAT 2·ERD-022) | DFEAT·ERD | **API 10건 전부**(마지막 갱신이 그 이전) |
| 모달 → 전용 화면 전환 | 08-06 (SCREEN-036·037 신설) | SCREEN-031·037 | **API-108·SCREEN-030 버튼·DFEAT-038** |

⇒ **cascade 가 "나중에 갱신된 ITEM"에만 닿고 "오래 안 건드린 ITEM"은 건너뛴다.**

### D009 의 특이점 (다른 도메인과 다른 것)
1. **`ERD-022` 자체는 3중 대조 완전 일치** — 컬럼 17개 물리명·타입·길이·nullable·default 가 `V1__baseline.sql`·`schema.sql`·D9 설계서와 일치, FK·인덱스도 일치, **가공 테이블·누락 테이블·잘못된 참조 0건**(D007 과 정반대). **결함이 API 계약 쪽에 몰려 있다.**
2. **"API 전건 orphan" 패턴 미재현** — DFEAT 2건이 `implemented_by_endpoints`·`persists_in_tables` 를 정확히 채워 차집합 0.
3. **한글 손상 0건**(4가지 방법 전수).
4. **검증 게이트 0** — UC·AC·SEQ·TEST·다이어그램이 **전부 0건**. acceptance·test_scenario 두 차원이 독립적으로 *"AC/TEST 가 하나라도 있었으면 그 P0 계약 불일치가 걸렸다"* 고 판정.

### D009 소급/확인 항목
- **`ai_session_log[].summary` 를 오염 검사 대상으로 볼지** — 현재 제외 중이나 PR 번호 8건 + SD 오인용이 그대로 있다.
- **통합시험 suite 스코프 정책 미선언** — 외부 연동 흐름만 담는 관례로 보이나 명시가 없다.
- `ADR-014.context` 의 커밋 해시 — **결정 이력 서술이라 판단이 갈려 보류**.
