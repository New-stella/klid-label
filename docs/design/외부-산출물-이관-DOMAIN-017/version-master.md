# Version Master — DOMAIN-017

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-017 |
| Last sync | 2026-08-19T12:39:54.495Z |
| Mode | INITIAL — NEW 49 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | docs/design/외부-산출물-이관-DOMAIN-017 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-017 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 4건 / 스코프 밖 44건
  ℹ️  api_endpoint: 이번 키트 7건 / 스코프 밖 183건
  ℹ️  erd: 이번 키트 6건 / 스코프 밖 15건
  ℹ️  diagram_sequence: 이번 키트 1건 / 스코프 밖 25건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 1건 / 스코프 밖 32건
  ℹ️  use_case: 이번 키트 2건 / 스코프 밖 26건 (그중 domain_id 없음 1건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 7건 / 스코프 밖 37건 (그중 domain_id 없음 27건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 2건 / 스코프 밖 41건 (그중 domain_id 없음 6건)
  🚨 feature: 이번 키트 0건 / 프로젝트 전역 9건 (그중 domain_id 없음 9건) — 전량 누락
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[AC-041]]
- NEW [[AC-042]]
- NEW [[AC-043]]
- NEW [[AC-044]]
- NEW [[AC-045]]
- NEW [[AC-046]]
- NEW [[AC-047]]
- NEW [[ADR-042]]
- NEW [[ADR-048]]
- NEW [[API-205]]
- NEW [[API-206]]
- NEW [[API-207]]
- NEW [[API-208]]
- NEW [[API-209]]
- NEW [[API-210]]
- NEW [[API-211]]
- NEW [[SEQ-026]]
- NEW [[DOMAIN-017]]
- NEW [[EVT-005]]
- NEW [[DFEAT-056]]
- NEW [[DFEAT-057]]
- NEW [[DFEAT-058]]
- NEW [[DFEAT-059]]
- NEW [[ERD-010]]
- NEW [[ERD-012]]
- NEW [[ERD-017]]
- NEW [[ERD-019]]
- NEW [[ERD-025]]
- NEW [[ERD-031]]
- NEW [[NFR-008]]
- NEW [[NFR-009]]
- NEW [[NFR-010]]
- NEW [[NFR-011]]
- NEW [[NFR-012]]
- NEW [[NFR-013]]
- NEW [[NFR-014]]
- NEW [[NFR-015]]
- NEW [[NFR-016]]
- NEW [[NFR-017]]
- NEW [[NFR-018]]
- NEW [[NFR-019]]
- NEW [[NFR-020]]
- NEW [[NFR-021]]
- NEW [[ROLE-001]]
- NEW [[ROLE-002]]
- NEW [[ROLE-003]]
- NEW [[SCREEN-039]]
- NEW [[UC-018]]
- NEW [[UC-035]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-041]] | acceptance | 1 | NEW |
| [[AC-042]] | acceptance | 1 | NEW |
| [[AC-043]] | acceptance | 1 | NEW |
| [[AC-044]] | acceptance | 1 | NEW |
| [[AC-045]] | acceptance | 2 | NEW |
| [[AC-046]] | acceptance | 3 | NEW |
| [[AC-047]] | acceptance | 1 | NEW |
| [[ADR-042]] | adr | 6 | NEW |
| [[ADR-048]] | adr | 3 | NEW |
| [[API-205]] | api_endpoint | 3 | NEW |
| [[API-206]] | api_endpoint | 4 | NEW |
| [[API-207]] | api_endpoint | 3 | NEW |
| [[API-208]] | api_endpoint | 2 | NEW |
| [[API-209]] | api_endpoint | 3 | NEW |
| [[API-210]] | api_endpoint | 3 | NEW |
| [[API-211]] | api_endpoint | 4 | NEW |
| [[DFEAT-056]] | domain_feature | 1 | NEW |
| [[DFEAT-057]] | domain_feature | 2 | NEW |
| [[DFEAT-058]] | domain_feature | 1 | NEW |
| [[DFEAT-059]] | domain_feature | 1 | NEW |
| [[DOMAIN-017]] | domain | 1 | NEW |
| [[ERD-010]] | erd | 25 | NEW |
| [[ERD-012]] | erd | 32 | NEW |
| [[ERD-017]] | erd | 21 | NEW |
| [[ERD-019]] | erd | 21 | NEW |
| [[ERD-025]] | erd | 4 | NEW |
| [[ERD-031]] | erd | 5 | NEW |
| [[EVT-005]] | domain_event | 4 | NEW |
| [[NFR-008]] | nfr | 6 | NEW |
| [[NFR-009]] | nfr | 4 | NEW |
| [[NFR-010]] | nfr | 3 | NEW |
| [[NFR-011]] | nfr | 4 | NEW |
| [[NFR-012]] | nfr | 4 | NEW |
| [[NFR-013]] | nfr | 4 | NEW |
| [[NFR-014]] | nfr | 2 | NEW |
| [[NFR-015]] | nfr | 5 | NEW |
| [[NFR-016]] | nfr | 4 | NEW |
| [[NFR-017]] | nfr | 8 | NEW |
| [[NFR-018]] | nfr | 6 | NEW |
| [[NFR-019]] | nfr | 2 | NEW |
| [[NFR-020]] | nfr | 4 | NEW |
| [[NFR-021]] | nfr | 1 | NEW |
| [[ROLE-001]] | permission_role | 10 | NEW |
| [[ROLE-002]] | permission_role | 6 | NEW |
| [[ROLE-003]] | permission_role | 7 | NEW |
| [[SCREEN-039]] | screen_spec | 5 | NEW |
| [[SEQ-026]] | diagram_sequence | 5 | NEW |
| [[UC-018]] | use_case | 17 | NEW |
| [[UC-035]] | use_case | 5 | NEW |
