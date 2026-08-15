# 인계 — LogiCraft 감사 결함 수정 (2026-08-15 세션 종료 시점)

> **먼저 읽을 것**: 이 문서 → `TASK-db-standard-terms.md` **§2-0**(다음 작업의 입력) → 필요하면 `FIX-ROUND-1.md`(무엇을 왜 고쳤나).
> `REPORT.md`·`raw/DOMAIN-*.md` 는 감사 원본이라 **다음 라운드**에 쓴다.

✅ **이 문서들은 저장소에 커밋됐다** (2026-08-15, `git add -f`). `reports/` 는 여전히 `.gitignore` 대상이지만 이 디렉터리만 강제로 담았으므로 **워크트리를 지워도 사라지지 않는다.** 무시 규칙 자체는 그대로라 다른 임시 산출물은 계속 무시된다.

> ⚠ 다만 **하네스 작업파일**(`.cc-plan.md`·`.cc-progress.md`·`.cc-review-result`)은 **여전히 워크트리 전용**이다 — 의도된 격리이며 커밋 대상이 아니다. 세션을 넘겨 지켜야 할 사실은 이 문서와 auto-memory 에 있다.

---

## 1. 지금 상태

브랜치 `domain-check` · 작업 트리 **clean** · `origin/main` 머지 완료(**충돌 0건**)

```
81fae124  chore(screen-kit): 서버 편집을 반영해 키트 7개를 다시 맞춘다
1c613143  docs(rules): 정합 라운드에서 새로 뚫린 사각 셋을 규칙에 넣는다
ee2d625b  Merge origin/main into domain-check
a052b802  docs: 감사에서 드러난 지침 드리프트를 코드 실측으로 바로잡는다
64ebdaa3  docs: 낡은 납품 설계 산출물을 동결해 자동참조 경로에서 뺀다
```

**LogiCraft 서버 편집은 커밋 대상이 아니다**(서버에 있다). 키트 SYNC 로 로컬 사본이 최신이며, 메인 키트 실제 변경 28건이 우리가 고친 ITEM 과 일치하는 것으로 반영을 교차 확인했다.

---

## 2. 끝난 것

**교차 클러스터 C1~C9 전부** + 사용자 판정 9건 반영. ITEM 약 **60건** 수정, 전 건 **서버 재조회 바이트 대조 통과 · 배열 원소 감소 0 · `status` 불변 · 한글 손상 0**.

두 축은 **전수(951)로 0건 확정**: 검수 종결 상태값 · 재생성/통지 트리거.
미러 층: **9화면 게시**, 38화면 `sections`·`purpose`·`title`·`route` 4축 **드리프트 0**.

상세는 `FIX-ROUND-1.md`.

---

## 3. 다음 작업 — DB 표준용어 마이그레이션

**입력**: `TASK-db-standard-terms.md`(**§2-0 을 반드시 먼저**). 착수 전 실측이 끝나 있어 **탐색 없이 바로 설계에 들어갈 수 있다.**

### ✅ 착수 전 사용자 판정 4건 — **전부 회신됨 (2026-08-15)**

| # | 판정 | 결론 |
|---|---|---|
| 1 | 표준 해석 단위 | **V5 선례 유지** — 등록된 *용어*가 있으면 정합. ⇒ `ASSIGNMENT_ID`·`ACTOR/SUBJECT/PREV_USER_NO`·`QUEUE_SN`·`DLQ_DT` **6종은 개명 대상 아님**. 진짜 위반은 **3건**(`APLY_DT` + 테이블 2종) |
| 2 | `APLY_DT` override | ✅ **뒤집었다 → `APLCN_DT`**(`V8`). 사업사전 override 행은 **개명이 아니라 삭제**(행안부에 이미 있어 중복). ⚠ 구 근거 `DE_IDNTF_APLCN_DT` 는 **물리 컬럼으로는 없고 용어 사전에는 있다**(KLID-BM 배포분) — 축을 나눠 읽을 것 |
| 3 | 착수 순서 | **`STTS_CD` 단독 먼저** — `APLY_DT` 개명·테이블 2종 개명은 **다음 라운드** |
| 4 | `ADR-020.decided_by` | **미변경 유지** — 계보 포인터로 읽으면 참이고 `supersedes[0]` 가 이미 관계를 표현한다 |

### 라운드 2 진행 상태 — `STTS_CD` 단독

- ✅ **`ERD-027` v4 → v5** — `LS_MON_NOTI_ACML.STTS_CD` `varchar(20)` → `varchar(16)`. 바이트 대조 통과 · `change_summary` 완전 일치 · 배열 원소 감소 0 · `stale` 원래 false(잃은 추적 신호 없음)
- ✅ **ITEM 층 축 종결** — kit-export `include_retired=true`(모집단 **952**) 전수 스캔에서 **이름이 정확히 `STTS_CD` 인 컬럼은 전 ERD 6건 모두 `varchar(16)`**. `ERD-027` 이 마지막 1건이었다
- ✅ `CDIAG-013` 은 이 테이블을 언급하나 물리 길이 미보유 — **정상**(Java 속성 축) · `analyze_impact` 의존자 0건
- ⏳ 코드 층(`V7` 마이그레이션 + 엔티티 + `schema.sql` + 문서) 진행 중

### ⚠ 새로 측정된 사실 — 접두형 `*_STTS_CD` 는 손대지 마라

전수 스캔 결과 접두형은 **`varchar(20)` 26건 vs `varchar(16)` 2건**으로 갈려 있다. 그런데
`v_completed_video.output_stts_cd`·`v_completed_meta.rvw_stts_cd` 는 **관제가 SELECT 하는 뷰 출력 컬럼**이라
넓히는 순간 외부 계약면이 된다. **"일관성"을 이유로 통일하지 말 것** — 별도 협의 사안이다.

---

## 4. ★되돌리면 안 되는 확정 사실 (전부 코드 실측)

| 사실 | 되돌리면 |
|---|---|
| **`COMPLETED` 는 다섯 축**이고 오류는 `LsRawDataStatus.STTS_COMPLETED` **하나뿐** | 배정 목록 표시축(`AssignmentWorkStatus`)을 고치면 **대시보드 진행률이 0%** 로 떨어진다 |
| **`DE_IDENT_YN`(테이블) ≠ `DE_IDNTF_YN`(뷰 출력 별칭)** — 둘 다 맞다 | 통일하면 엔티티 매핑이 깨지거나 **관제 계약면**이 바뀐다 |
| **export 산출은 저작도구 범위 안** (`ADR-005` superseded → `ADR-020`) | 범위 밖인 것은 **데이터마트 구축·검색·다운로드**뿐이다 |
| **자동 재비식별 큐는 없다** | `batch/retry/BatchRetryQueue` 는 **배치 일반 재시도**다. 클래스 실재를 근거로 되돌리지 마라 |
| **재검수 대상은 개수가 아니라 성질** | `N종` 표기를 되살리면 층마다 또 갈린다(7·8·9로 갈려 있었다) |
| **`NEXT_RTRY_DT` 동명이표** · **`EVNT_ID` 는 KLID-BM 배포분** | 전역 치환·우리 소유 아닌 것 수정 금지 |

### 감사가 틀렸던 것 (재보고 금지)
- **C8 진단 반대** — 병목은 `belongs_to_domain` 이 아니라 `realizes_dfeats`
- **C2 처방 불가능** — ADR 의 `references` 는 그래프 엣지를 만들지 않는다(`domain_id` 는 만든다 — 37건 확인)
- **D006 「지배적 결함 4」 오탐** — 고쳤으면 대시보드가 망가졌다
- **`CDIAG` 물리명 과잉 호출 5건** — 클래스 다이어그램은 **Java 속성 축**이다

---

## 5. 반복 확인된 함정 (프롬프트에 넣을 것)

규칙 파일 `.claude/rules/logicraft-integration.md` 에 §2-F·§2-G·§5-G·§7-7~10 으로 반영해 뒀다. 요지:

1. **대상 목록을 그대로 믿지 말고 전수로 다시 찾아라** — 이번 세션에 **일곱 번 연속 부족**했다. 원인은 부주의가 아니라 **감사는 도메인별로 적는데 하나의 결정은 도메인을 가로질러 복제**돼 있다는 구조다. **"결정 = 축"으로 잡고 전수로 닫아라.**
2. **`kit-export?include_retired=true`** — 기본 호출은 활성만(873 vs 951).
3. **쓰기 전 `stale`·`stale_reason` 기록** — `update_item` 이 자동 해제해 **추적 신호만 사라진다**(이번에 6건).
4. **바이트 대조가 유일한 방어선** — 서버 덤프를 기준선으로, `old` 는 **서버 문자열에서 앵커로 잘라내고**(전사 금지), 각 치환 `count==1` assert, 쓰기 후 재조회 `data` 전체 대조.
5. **낱말이 아니라 축으로 판정** — `COMPLETED`·`만료`·`디바운스`·`8경로` 전부 정상 문맥이 있다.

---

## 6. 남은 후속 (판정 불필요, 실행만)

| # | 내용 |
|---|---|
| 1 | **코드 층 대조** — 이번 라운드에서 가장 덜 열린 층. 정정한 ITEM 사양을 코드가 실제로 따르는지 |
| 2 | **감사 개별 P1/P2 약 1,000건** — 교차 클러스터 밖. 도메인별 라운드 |
| 3 | `ADR-020.references` 의 `example.invalid` 커밋 URL 2건 + `brownfield.notes` PR·해시 5건 (본문 오염) |
| 4 | `UC-024`·`UC-031`·`SEQ-014` 의 **소실된 `stale` 사유 재확인** — 쓰기 부수효과로 지워졌고 원인은 미확인 |
| 5 | ✅ ~~`LS_TASK_ASSIGN_HSTRY` — dev DB 에 실재하고 FK 까지 걸려 있다(0행). 정체 판정 필요~~ → **해소(2026-08-15 저장소 실측)** |
| 6 | ✅ ~~`LS_TASK_ASSIGNMENT.TASK_TYPE_CD` — 코드가 읽는 컬럼이 스키마에 없다~~ → **오보. 컬럼은 실재한다** |

### ★P3 선행 판정 결과 (2026-08-15) — 둘 다 차단 사유가 아니다

**5 — `LS_TASK_ASSIGN_HISTORY` 는 정상적으로 제거됐다.** ⚠ 이름 철자부터 틀렸다 — 정본은 `HSTRY` 가 아니라
**`LS_TASK_ASSIGN_HISTORY`** 다(그래서 `HSTRY` 로 grep 하면 0건이 나와 "저장소에 없다"로 오판하게 된다).
- 이력: V1 베이스라인에 정의가 있었고 **`V4__drop_unused_tables_round2.sql` 이 `DROP TABLE ls_task_assign_history` 로 제거**했다(사용처 0 · 프로덕션 read 경로 0, 전수 확인 근거가 V4 헤더에 있다).
- 현재 형상: `deploy/onprem/db/schema.sql` **0건** · 코드 참조 **0건**(살아 있는 참조 4곳은 전부 *제거를 설명하는 주석*이다 — `test-data.sql` · `test-data-stats-clean.sql` · `AssignmentReassignConcurrencyIT` · `AssignmentServiceTest`).
- V4 헤더가 **`DROP TABLE` 이 딸린 PK·FK·인덱스·IDENTITY 시퀀스를 함께 정리한다**고 명시 — 고아 FK 가 남지 않는다.
- ⚠ **dev DB 는 확인 못 했다**(`klid_system_246` **ETIMEDOUT** — 망 접근 불가). 인계 시점 관측이 맞다면 **dev 가 V4 를 적용하기 전 상태**였을 가능성이 가장 크다. dev 접근이 되면 `information_schema.tables` 로 1회 확인할 것.
- ★**설령 dev 에 잔재가 있어도 P3 를 막지 않는다** — PostgreSQL 의 `ALTER TABLE … RENAME` 은 의존 FK 를 **OID 로 참조**하므로 무효화하지 않는다(제약 *이름*만 낡게 남는다). 개명이 아니라 `DROP` 을 할 때만 문제가 된다.

**6 — 오보다.** `schema.sql` 의 `CREATE TABLE klid_at.ls_task_assignment` 에 **`task_type_cd character varying(20) NOT NULL` 이 실재**한다. `ddl-auto=validate` 가 무력하다는 사실은 별개로 참이나(이번 라운드에 `JpaBuilderConfig` 로 재확인), 이 컬럼은 그 사각의 사례가 아니다.

⇒ **P3(테이블 2종 개명)의 선행 차단 조건은 없다.** 남은 난점은 DB 오브젝트 13개 동반과 `MngAcctUserTableRemovalTest` 의 하드코딩 목록(개명 후 **실패하지 않고 조용히 대상을 놓친다**)이다.
