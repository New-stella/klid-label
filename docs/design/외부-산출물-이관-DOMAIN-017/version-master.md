# Version Master — DOMAIN-017

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-017 |
| Last sync | 2026-08-20T11:03:25.766Z |
| Mode | SYNC — NEW 0 / CHANGED 13 / UNCHANGED 39 |
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
  ℹ️  api_endpoint: 이번 키트 8건 / 스코프 밖 186건
  ℹ️  erd: 이번 키트 6건 / 스코프 밖 15건
  ℹ️  diagram_sequence: 이번 키트 1건 / 스코프 밖 25건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 1건 / 스코프 밖 32건
  ℹ️  use_case: 이번 키트 2건 / 스코프 밖 26건 (그중 domain_id 없음 1건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 8건 / 스코프 밖 40건 (그중 domain_id 없음 30건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 2건 / 스코프 밖 42건 (그중 domain_id 없음 6건)
  ℹ️  feature: 이번 키트 1건 / 스코프 밖 9건 (그중 domain_id 없음 9건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[AC-044]] (prev v2)
- CHANGED [[AC-045]] (prev v3)
- CHANGED [[AC-046]] (prev v4)
- CHANGED [[AC-047]] (prev v4)
- CHANGED [[AC-048]] (prev v4)
- CHANGED [[ADR-048]] (prev v4)
- CHANGED [[API-206]] (prev v8)
- CHANGED [[API-215]] (prev v1)
- CHANGED [[DFEAT-057]] (prev v6)
- CHANGED [[ERD-012]] (prev v33)
- CHANGED [[ERD-031]] (prev v9)
- CHANGED [[FEAT-010]] (prev v3)
- CHANGED [[UC-035]] (prev v11)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-041]] | acceptance | 3 | UNCHANGED |
| [[AC-042]] | acceptance | 2 | UNCHANGED |
| [[AC-043]] | acceptance | 3 | UNCHANGED |
| [[AC-044]] | acceptance | 4 | CHANGED |
| [[AC-045]] | acceptance | 4 | CHANGED |
| [[AC-046]] | acceptance | 8 | CHANGED |
| [[AC-047]] | acceptance | 5 | CHANGED |
| [[AC-048]] | acceptance | 5 | CHANGED |
| [[ADR-042]] | adr | 6 | UNCHANGED |
| [[ADR-048]] | adr | 5 | CHANGED |
| [[API-205]] | api_endpoint | 8 | UNCHANGED |
| [[API-206]] | api_endpoint | 9 | CHANGED |
| [[API-207]] | api_endpoint | 4 | UNCHANGED |
| [[API-208]] | api_endpoint | 3 | UNCHANGED |
| [[API-209]] | api_endpoint | 6 | UNCHANGED |
| [[API-210]] | api_endpoint | 7 | UNCHANGED |
| [[API-211]] | api_endpoint | 6 | UNCHANGED |
| [[API-215]] | api_endpoint | 4 | CHANGED |
| [[DFEAT-056]] | domain_feature | 3 | UNCHANGED |
| [[DFEAT-057]] | domain_feature | 10 | CHANGED |
| [[DFEAT-058]] | domain_feature | 3 | UNCHANGED |
| [[DFEAT-059]] | domain_feature | 2 | UNCHANGED |
| [[DOMAIN-017]] | domain | 1 | UNCHANGED |
| [[ERD-010]] | erd | 26 | UNCHANGED |
| [[ERD-012]] | erd | 34 | CHANGED |
| [[ERD-017]] | erd | 22 | UNCHANGED |
| [[ERD-019]] | erd | 21 | UNCHANGED |
| [[ERD-025]] | erd | 4 | UNCHANGED |
| [[ERD-031]] | erd | 15 | CHANGED |
| [[EVT-005]] | domain_event | 5 | UNCHANGED |
| [[FEAT-010]] | feature | 4 | CHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 4 | UNCHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 4 | UNCHANGED |
| [[NFR-012]] | nfr | 4 | UNCHANGED |
| [[NFR-013]] | nfr | 4 | UNCHANGED |
| [[NFR-014]] | nfr | 2 | UNCHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 8 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 4 | UNCHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[ROLE-001]] | permission_role | 10 | UNCHANGED |
| [[ROLE-002]] | permission_role | 6 | UNCHANGED |
| [[ROLE-003]] | permission_role | 7 | UNCHANGED |
| [[SCREEN-039]] | screen_spec | 10 | UNCHANGED |
| [[SEQ-026]] | diagram_sequence | 9 | UNCHANGED |
| [[UC-018]] | use_case | 17 | UNCHANGED |
| [[UC-035]] | use_case | 12 | CHANGED |
