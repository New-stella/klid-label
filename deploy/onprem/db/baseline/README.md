# 상용 온프렘 스키마 동결본

## 현재 기준선

| 항목 | 값 |
|---|---|
| 파일 | `schema-baseline-20260904-V31.sql` |
| 동결일 | 2026-09-04 |
| 마이그레이션 경계 | **V1 ~ V31 적용 결과** (다음 증분은 `V32` 부터) |
| 출처 커밋 | `6aaf0ede` 의 `deploy/onprem/db/schema.sql` — **바이트 단위 동일** |
| sha256 | `511d2118297dfa12b278f74fc06d489d675a923fbaee9d3364925538402f471f` (`SHA256SUMS`) |
| 대상 스키마 | `klid_at` (파일이 `CREATE SCHEMA` 를 포함하고 전 객체가 스키마 한정이라 로더의 `search_path` 와 무관) |

## 구성 (동결본이라 이 수치는 낡지 않는다)

- 테이블 **78** = 저작도구 `ls_*` **67** + Quartz `qrtz_*` **11**
- 뷰 **4** (데이터마트 `v_completed_*`)
- 시퀀스 2 · 시드 **66행** (`ls_evnt_ctgry` 11 · `ls_evnt_type` 16 · `ls_label` 9 · `ls_system_config` 14 · `ls_vrfc_evnt_qstn` 7 · `ls_vrfc_evnt_type` 7 · `qrtz_locks` 2)
- `flyway_schema_history` **없음** (온프렘은 Flyway 미사용)
- **컬럼 주석(`COMMENT ON`) 없음** — 생성기가 `pg_dump --no-comments` 로 뽑는다. 의도된 것이며
  결함이 아니다. 다만 마이그레이션 원본에는 주석이 있으므로 **로컬/dev DB 에는 주석이 있고
  온프렘 DB 에는 없다.** 컬럼 주석을 근거로 삼는 점검을 온프렘 DB 에서 돌리면 전건 0 이 나온다.

## 이 파일을 다시 만들지 말 것

`gen-schema-sql.sh` 는 `../schema.sql` 만 갱신한다. 이 동결본은 **그 스크립트의 산출 대상이 아니며**,
다시 생성하면 "현장이 설치한 것"이라는 성질 자체가 사라진다. 마이그레이션이 늘면 여기가 아니라
`../incremental/` 에 쌓는다.

## 새 기준선을 잡을 때

다음 상용 반입에서 스키마를 통째로 새로 설치하는 경우에만, 그 시점 `schema.sql` 을
`schema-baseline-YYYYMMDD-V{n}.sql` 로 **추가**하고 `SHA256SUMS` 를 갱신한다.
**기존 파일은 지우지 않는다** — 현장마다 기준선이 다를 수 있고, 그때 어느 증분을 줘야 하는지는
그 현장의 기준선이 정한다.

## 검증

```bash
shasum -a 256 -c SHA256SUMS
```
