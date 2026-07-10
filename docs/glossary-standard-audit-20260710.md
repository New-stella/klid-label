# DB 표준용어 재감사 — 정합 리포트 (2026-07-10, 독립 재검증)

> **성격**: LogiCraft 수령 표준용어(`docs/표준용어/` 공공+산업)와 우리 물리 DB(`LS_*` 42테이블/403컬럼, Flyway V1~V86)의 **독립 재감사**. 읽기 전용 — DB/사전 변경 없음.
> **⚠️ 중복 고지**: 본 감사와 **동일 범위의 전수감사가 같은 날(2026-07-10) 이미 수행·커밋됨** (`reports/glossary-db-audit-2026-07-10.md`, `reports/glossary-db-audit-detail-2026-07-10.md`; 커밋 `7ba8f73`·`e54dec7`·`50b5a2a`). 본 문서는 그 결과를 **독립적으로 재확인**하고 **새로 발견된 소수 델타만** 남긴다.

---

## 0. 결론 — 이미 감사·정합 완료됨 (재확인)

- **정합도 96%+ 재확인**: 403컬럼 중 377개가 표준 등록 용어와 정확 일치. 선행 감사(378/392=96.4%)와 결과 일치.
- **선행 감사에서 이미 처리/결정된 항목**(재작업 불필요):

| 항목 | 선행 처리 | 상태 |
|------|----------|:---:|
| `REGISTERED_AT→REG_AT`, `REQ_KIND_CD→REQ_KND_CD`, `POLL_ATTEMPT_CNT→POLL_ATMPT_CNT`, `KPST_*→DE_IDNTF_*` | V83 rename | ✅ 커밋됨 |
| 폴링=`POLL` 단어/용어 등록 | LogiCraft Phase1(단어 id624 + 용어 3) | ✅ 등록됨 |
| 타입/길이 정합 (여부 CHAR1 13건, truncation 11건, FRM_NO→BIGINT) | V84·V85 | ✅ 커밋됨 |
| `LS_DATA_SET` drop | V86 | ✅ 커밋됨 |
| `DE_IDNTF_PJT_ID`·`DE_IDNTF_DATST_ID`·`ORGNL_RAW_SN`·`VDO_FRM_NO`·`VDO_LEN_MS` 등 | 합성 용어 등록/rename 반영 | ✅ 정합 |

> **내 재감사가 "수정/추가 후보"로 잡은 대부분은 위에서 이미 해소됨.** 특히 **CSV 스냅샷이 라이브 LogiCraft 사전보다 뒤처져** 있어(예: POLL 단어가 CSV엔 없지만 LogiCraft엔 등록됨), CSV만으로 판정하면 이미 처리된 건이 "미등록"으로 오탐된다.

---

## 1. 재감사가 재확인한 오탐 (조치 불필요 — 사용자 확정 정책)

내 token-level 재감사는 아래를 "약어 위반"으로 지목했으나, **선행 감사가 이미 오탐으로 확정**했다. 사용자 확정 정책 = **표준 용어사전에 합성 용어로 등록된 컬럼은 정합**(구성 약어의 word-purist 위반 여부와 무관).

| 컬럼 | 내 재감사 지목 | 확정 판정 |
|------|---------------|----------|
| `SAVE_REASON_CD` | REASON→RSN 위반 | **정합** — 저장사유코드로 등록됨(rename 불필요) |
| `DEAD_LETTER_AT` (×2) | DEAD/LETTER 비표준·_AT | **정합** — 영구실패시점으로 등록됨 |
| `MARKING_SN` | MARKING 비표준단어 | **정합** — 사업 도메인어로 흡수 |
| `EVNT_TYPE_CD`·`FRM_NO`·`LOCK_ID`·`OTSD_JOB_ID`·`DE_IDENT_YN`·`SCALE_X` 등 | (구버전 감사에서도 오탐) | **정합** — 전부 합성 용어 등록됨 |

> 교훈: 등록 term 자체를 word-purist로 다시 쪼개면 대량 오탐이 난다. 진실원은 **합성 용어사전 등록 여부**이며 이는 선행 감사가 이미 확정했다.

---

## 2. 🆕 재감사 신규 델타 — 선행 감사 미포착 (실제 조치 후보)

선행 감사의 truncation(§4-B)·타입(§4) 목록에 **없던** 내부 길이 불일치 2건을 새로 포착. 둘 다 "같은 표준 용어인데 테이블별 길이가 다른" 내부 드리프트.

| # | 컬럼 | 현재 | 형제/표준 | 판정 | 제안 |
|---|------|------|----------|------|------|
| N1 | `LS_DATA_AUG.REJECT_RSN` | `VARCHAR(500)` | 형제 `LS_DATA_AUG_RVW`·`LS_DATA_META_REVIEW.REJECT_RSN`=**1000**, 표준 반려사유=**V1000** | **✅ 수정 완료** | **V87** 500→1000 (build GREEN·ddl-validate PASS) |
| N2 | `LS_DEIDENT_REPORT.RSN` | `VARCHAR(1000)` | 형제 `LS_TASK_EVENT_LOG.RSN`=**500**, 표준 사유=**V500** | **검토(정책)** | 사유(500) vs 반려사유(1000) 구분 정리 후 통일 |

> N1 반영 시 확인된 사실: `LsDataAug.rejectRsn` 은 **`@Transient`(미매핑)** 이고 실제 반려사유 영속은 `LsDataAugRvw.REJECT_RSN`(이미 1000)이 담당 → `LS_DATA_AUG.REJECT_RSN` 은 audit 이관 후 **미사용 레거시 물리 컬럼**. V87 은 표준 정합 위생 조치이며, 엔티티 변경은 불필요(영속 동작 변경·설계 역행 회피). 이 컬럼 자체는 장기적으로 **DROP 검토 후보**. N2는 "사유(500)"/"반려사유(1000)" 도메인 구분 정책 결정 후 통일.

**참고(확인됨)**: `LsDataAug.REG_USER_NO` 는 엔티티 `@Column(length=50)`(VARCHAR50)로 실재 — 파서 아티팩트 아님. 타 테이블 `REG_USER_NO`=BIGINT 와 타입 상이(내부 불일치) → 별도 검토 후보(이번 범위 외).

---

## 3. 미결 후속 (선행 감사 baseline에서 이월 — 본 재감사와 동일)

`reports/glossary-db-audit-detail-2026-07-10.md` §5 및 진행 메모 기준 잔여:
- **`LS_TUS_UPLOAD` 통삭제**(BE `upload/` 패키지+API+테이블 + FE `features/upload/`·DevAutolabelTestPage) — 폐지예정 테이블. 비표준 약어 9건이 여기 집중 → **표준화가 아니라 제거가 정답**. dev 오토라벨 테스트 의존 있어 고영향·별도 승인.
- **D8/D9 폐쇄망 문서(Word/HWP) 수동 반영** (가이드 `reports/D8D9-표준정합-수정가이드-2026-07-10.md`).
- **`docs/v2-wiki/18-database.md`** 에 `LS_DATA_SET` 제거 반영.
- LOW: `LsDataSrcRepository.findByRawSnAndFrameNo` Integer→Long.

---

## 4. 종합 판단

**우리 DB 표준용어 정합은 오늘(2026-07-10) 이미 전수 감사·정합 완료 상태다.** 본 독립 재감사의 순기여는:
1. 선행 결과(96%+ 정합, 오탐 debunk, V83~V86 반영)를 **독립적으로 재확인**.
2. 선행 감사가 놓친 **내부 길이 불일치 2건(N1·N2)** 신규 포착.

→ **실질 신규 작업 = N1(`LS_DATA_AUG.REJECT_RSN` 500→1000) 뿐** (N2는 정책 결정, 나머지는 이월 후속). 대규모 rename/추가는 필요 없음.

---

### 감사 재현 자료 (scratchpad)
`schema_final.tsv`(403컬럼) · `audit_token_issues.tsv` · `audit_clean_word.tsv` · `audit_type_mismatch.tsv` · `audit_len_mismatch.tsv`. 표준 진실원 = `docs/표준용어/` CSV(**단, 라이브 LogiCraft 사전보다 스냅샷이 뒤처질 수 있음** — POLL 사례).
