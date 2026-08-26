# Version Master — DOMAIN-017

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-017 |
| Last sync | 2026-08-26T23:11:40.826Z |
| Mode | SYNC — NEW 0 / CHANGED 2 / UNCHANGED 64 |
| 출력 루트 | docs/design/외부-산출물-이관-DOMAIN-017 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-017 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 25건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 4건 / 스코프 밖 44건
  ℹ️  api_endpoint: 이번 키트 10건 / 스코프 밖 191건
  ℹ️  erd: 이번 키트 6건 / 스코프 밖 17건
  ℹ️  diagram_sequence: 이번 키트 1건 / 스코프 밖 25건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 1건 / 스코프 밖 32건
  ℹ️  use_case: 이번 키트 3건 / 스코프 밖 27건 (그중 domain_id 없음 1건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 12건 / 스코프 밖 105건 (그중 domain_id 없음 37건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 5건 / 스코프 밖 44건 (그중 domain_id 없음 8건)
  ℹ️  feature: 이번 키트 1건 / 스코프 밖 9건 (그중 domain_id 없음 9건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[API-221]] (prev v14)
- CHANGED [[API-222]] (prev v9)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-041]] | acceptance | 5 | UNCHANGED |
| [[AC-042]] | acceptance | 4 | UNCHANGED |
| [[AC-043]] | acceptance | 5 | UNCHANGED |
| [[AC-044]] | acceptance | 6 | UNCHANGED |
| [[AC-045]] | acceptance | 7 | UNCHANGED |
| [[AC-046]] | acceptance | 11 | UNCHANGED |
| [[AC-047]] | acceptance | 7 | UNCHANGED |
| [[AC-048]] | acceptance | 8 | UNCHANGED |
| [[AC-052]] | acceptance | 3 | UNCHANGED |
| [[AC-053]] | acceptance | 3 | UNCHANGED |
| [[AC-054]] | acceptance | 3 | UNCHANGED |
| [[AC-120]] | acceptance | 13 | UNCHANGED |
| [[ADR-023]] | adr | 7 | UNCHANGED |
| [[ADR-042]] | adr | 6 | UNCHANGED |
| [[ADR-048]] | adr | 5 | UNCHANGED |
| [[ADR-052]] | adr | 2 | UNCHANGED |
| [[ADR-053]] | adr | 1 | UNCHANGED |
| [[API-205]] | api_endpoint | 8 | UNCHANGED |
| [[API-206]] | api_endpoint | 10 | UNCHANGED |
| [[API-207]] | api_endpoint | 7 | UNCHANGED |
| [[API-208]] | api_endpoint | 5 | UNCHANGED |
| [[API-209]] | api_endpoint | 6 | UNCHANGED |
| [[API-210]] | api_endpoint | 7 | UNCHANGED |
| [[API-211]] | api_endpoint | 6 | UNCHANGED |
| [[API-215]] | api_endpoint | 4 | UNCHANGED |
| [[API-221]] | api_endpoint | 15 | CHANGED |
| [[API-222]] | api_endpoint | 10 | CHANGED |
| [[DFEAT-056]] | domain_feature | 3 | UNCHANGED |
| [[DFEAT-057]] | domain_feature | 11 | UNCHANGED |
| [[DFEAT-058]] | domain_feature | 3 | UNCHANGED |
| [[DFEAT-059]] | domain_feature | 5 | UNCHANGED |
| [[DOMAIN-017]] | domain | 1 | UNCHANGED |
| [[ERD-010]] | erd | 28 | UNCHANGED |
| [[ERD-012]] | erd | 45 | UNCHANGED |
| [[ERD-017]] | erd | 22 | UNCHANGED |
| [[ERD-019]] | erd | 23 | UNCHANGED |
| [[ERD-025]] | erd | 4 | UNCHANGED |
| [[ERD-031]] | erd | 15 | UNCHANGED |
| [[EVT-005]] | domain_event | 5 | UNCHANGED |
| [[EXTSYS-007]] | external_system | 1 | UNCHANGED |
| [[FEAT-010]] | feature | 4 | UNCHANGED |
| [[INT-012]] | integration_point | 2 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 4 | UNCHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 5 | UNCHANGED |
| [[NFR-012]] | nfr | 4 | UNCHANGED |
| [[NFR-013]] | nfr | 4 | UNCHANGED |
| [[NFR-014]] | nfr | 3 | UNCHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 8 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 6 | UNCHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[ROLE-001]] | permission_role | 10 | UNCHANGED |
| [[ROLE-002]] | permission_role | 7 | UNCHANGED |
| [[ROLE-003]] | permission_role | 8 | UNCHANGED |
| [[SCREEN-039]] | screen_spec | 32 | UNCHANGED |
| [[SEQ-026]] | diagram_sequence | 13 | UNCHANGED |
| [[TEST-007]] | test_scenario | 1 | UNCHANGED |
| [[TEST-008]] | test_scenario | 1 | UNCHANGED |
| [[UC-018]] | use_case | 17 | UNCHANGED |
| [[UC-035]] | use_case | 15 | UNCHANGED |
| [[UC-036]] | use_case | 3 | UNCHANGED |
