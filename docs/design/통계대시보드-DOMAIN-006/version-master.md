# Version Master — DOMAIN-006

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-006 |
| Last sync | 2026-08-28T11:36:59.095Z |
| Mode | SYNC — NEW 2 / CHANGED 7 / UNCHANGED 36 / RETIRED 1 |
| 출력 루트 | docs/design/통계대시보드-DOMAIN-006/ |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-006 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 9건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 3건 / 스코프 밖 45건
  ℹ️  api_endpoint: 이번 키트 7건 / 스코프 밖 195건
  🚨 erd: 이번 키트 0건 / 프로젝트 전역 23건 — 전량 누락
  🚨 diagram_sequence: 이번 키트 0건 / 프로젝트 전역 26건 (그중 domain_id 없음 14건) — 전량 누락
  ℹ️  screen_spec: 이번 키트 3건 / 스코프 밖 34건
  ℹ️  use_case: 이번 키트 1건 / 스코프 밖 29건 (그중 domain_id 없음 2건)
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  ℹ️  acceptance: 이번 키트 3건 / 스코프 밖 121건 (그중 domain_id 없음 41건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 4건 / 스코프 밖 44건 (그중 domain_id 없음 7건)
  🚨 feature: 이번 키트 0건 / 프로젝트 전역 10건 (그중 domain_id 없음 9건) — 전량 누락
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[ADR-055]]
- NEW [[ROLE-004]]
- CHANGED [[API-001]] (prev v5)
- CHANGED [[NFR-013]] (prev v4)
- CHANGED [[NFR-017]] (prev v8)
- CHANGED [[NFR-020]] (prev v6)
- CHANGED [[ROLE-001]] (prev v10)
- CHANGED [[ROLE-002]] (prev v7)
- CHANGED [[ROLE-003]] (prev v8)
- RETIRED [[ADR-003]] → _retired/

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-029]] | acceptance | 3 | UNCHANGED |
| [[AC-030]] | acceptance | 2 | UNCHANGED |
| [[AC-031]] | acceptance | 2 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-019]] | adr | 8 | UNCHANGED |
| [[ADR-038]] | adr | 4 | UNCHANGED |
| [[ADR-055]] | adr | 3 | NEW |
| [[API-001]] | api_endpoint | 6 | CHANGED |
| [[API-042]] | api_endpoint | 8 | UNCHANGED |
| [[API-055]] | api_endpoint | 6 | UNCHANGED |
| [[API-056]] | api_endpoint | 7 | UNCHANGED |
| [[API-057]] | api_endpoint | 6 | UNCHANGED |
| [[API-058]] | api_endpoint | 3 | UNCHANGED |
| [[API-072]] | api_endpoint | 10 | UNCHANGED |
| [[CDIAG-009]] | class_diagram | 4 | UNCHANGED |
| [[DFEAT-026]] | domain_feature | 3 | UNCHANGED |
| [[DFEAT-027]] | domain_feature | 4 | UNCHANGED |
| [[DFEAT-028]] | domain_feature | 4 | UNCHANGED |
| [[DOMAIN-006]] | domain | 8 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 4 | UNCHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 5 | UNCHANGED |
| [[NFR-012]] | nfr | 4 | UNCHANGED |
| [[NFR-013]] | nfr | 9 | CHANGED |
| [[NFR-014]] | nfr | 3 | UNCHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 9 | CHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 7 | CHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[ROLE-001]] | permission_role | 13 | CHANGED |
| [[ROLE-002]] | permission_role | 9 | CHANGED |
| [[ROLE-003]] | permission_role | 10 | CHANGED |
| [[ROLE-004]] | permission_role | 4 | NEW |
| [[SCREEN-011]] | screen_spec | 21 | UNCHANGED |
| [[SCREEN-020]] | screen_spec | 32 | UNCHANGED |
| [[SCREEN-021]] | screen_spec | 28 | UNCHANGED |
| [[SD-014]] | screen_design | 5 | UNCHANGED |
| [[SD-030]] | screen_design | 5 | UNCHANGED |
| [[SD-031]] | screen_design | 2 | UNCHANGED |
| [[TEST-006]] | test_scenario | 2 | UNCHANGED |
| [[UC-033]] | use_case | 3 | UNCHANGED |
