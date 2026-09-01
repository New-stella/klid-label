# Version Master — DOMAIN-011

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-011 |
| Last sync | 2026-09-01T08:40:13.196Z |
| Mode | SYNC — NEW 0 / CHANGED 8 / UNCHANGED 48 |
| 출력 루트 | 마킹-DOMAIN-011 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-011 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 9건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 2건 / 스코프 밖 46건
  ℹ️  api_endpoint: 이번 키트 5건 / 스코프 밖 198건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 22건
  ℹ️  diagram_sequence: 이번 키트 2건 / 스코프 밖 24건 (그중 domain_id 없음 12건)
  ℹ️  screen_spec: 이번 키트 2건 / 스코프 밖 35건
  ℹ️  use_case: 이번 키트 1건 / 스코프 밖 34건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 3건 / 스코프 밖 9건
  ℹ️  acceptance: 이번 키트 3건 / 스코프 밖 72건 (그중 domain_id 없음 2건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 9건 / 스코프 밖 41건 (그중 domain_id 없음 5건)
  ℹ️  feature: 이번 키트 1건 / 스코프 밖 9건 (그중 domain_id 없음 8건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[DFEAT-039]] (prev v11)
- CHANGED [[DFEAT-048]] (prev v16)
- CHANGED [[NFR-011]] (prev v5)
- CHANGED [[NFR-013]] (prev v9)
- CHANGED [[NFR-014]] (prev v3)
- CHANGED [[NFR-020]] (prev v8)
- CHANGED [[SCREEN-008]] (prev v47)
- CHANGED [[UC-019]] (prev v24)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-1013]] | acceptance | 6 | UNCHANGED |
| [[AC-1014]] | acceptance | 4 | UNCHANGED |
| [[AC-1015]] | acceptance | 6 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-006]] | adr | 4 | UNCHANGED |
| [[ADR-008]] | adr | 3 | UNCHANGED |
| [[ADR-022]] | adr | 5 | UNCHANGED |
| [[ADR-029]] | adr | 2 | UNCHANGED |
| [[ADR-030]] | adr | 4 | UNCHANGED |
| [[ADR-046]] | adr | 9 | UNCHANGED |
| [[ADR-052]] | adr | 3 | UNCHANGED |
| [[ADR-055]] | adr | 3 | UNCHANGED |
| [[API-043]] | api_endpoint | 25 | UNCHANGED |
| [[API-047]] | api_endpoint | 12 | UNCHANGED |
| [[API-084]] | api_endpoint | 8 | UNCHANGED |
| [[API-091]] | api_endpoint | 12 | UNCHANGED |
| [[API-114]] | api_endpoint | 2 | UNCHANGED |
| [[CDIAG-002]] | class_diagram | 7 | UNCHANGED |
| [[CMP-002]] | diagram_c4_component | 3 | UNCHANGED |
| [[DFEAT-039]] | domain_feature | 12 | CHANGED |
| [[DFEAT-048]] | domain_feature | 17 | CHANGED |
| [[DOMAIN-011]] | domain | 8 | UNCHANGED |
| [[ERD-013]] | erd | 12 | UNCHANGED |
| [[EVT-001]] | domain_event | 5 | UNCHANGED |
| [[EVT-002]] | domain_event | 6 | UNCHANGED |
| [[EVT-005]] | domain_event | 5 | UNCHANGED |
| [[FEAT-005]] | feature | 10 | UNCHANGED |
| [[INT-002]] | integration_point | 18 | UNCHANGED |
| [[INTSPEC-003]] | integration_spec | 12 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 4 | UNCHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 6 | CHANGED |
| [[NFR-012]] | nfr | 5 | UNCHANGED |
| [[NFR-013]] | nfr | 10 | CHANGED |
| [[NFR-014]] | nfr | 4 | CHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 9 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 9 | CHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[NFR-022]] | nfr | 2 | UNCHANGED |
| [[ROLE-001]] | permission_role | 13 | UNCHANGED |
| [[ROLE-002]] | permission_role | 9 | UNCHANGED |
| [[ROLE-003]] | permission_role | 10 | UNCHANGED |
| [[ROLE-004]] | permission_role | 4 | UNCHANGED |
| [[SCREEN-006]] | screen_spec | 49 | UNCHANGED |
| [[SCREEN-008]] | screen_spec | 48 | CHANGED |
| [[SD-012]] | screen_design | 11 | UNCHANGED |
| [[SEQ-001]] | diagram_sequence | 20 | UNCHANGED |
| [[SEQ-014]] | diagram_sequence | 13 | UNCHANGED |
| [[TEST-001]] | test_scenario | 10 | UNCHANGED |
| [[TEST-002]] | test_scenario | 15 | UNCHANGED |
| [[UC-019]] | use_case | 25 | CHANGED |
