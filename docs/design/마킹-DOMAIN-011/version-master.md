# Version Master — DOMAIN-011

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-011 |
| Last sync | 2026-08-26T01:11:08.464Z |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 51 |
| 출력 루트 | docs/design/마킹-DOMAIN-011 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-011 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 4건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 2건 / 스코프 밖 46건
  ℹ️  api_endpoint: 이번 키트 5건 / 스코프 밖 194건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 22건
  ℹ️  diagram_sequence: 이번 키트 2건 / 스코프 밖 24건 (그중 domain_id 없음 12건)
  ℹ️  screen_spec: 이번 키트 2건 / 스코프 밖 31건
  ℹ️  use_case: 이번 키트 1건 / 스코프 밖 29건 (그중 domain_id 없음 1건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 2건 / 스코프 밖 114건 (그중 domain_id 없음 35건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 9건 / 스코프 밖 40건 (그중 domain_id 없음 5건)
  ℹ️  feature: 이번 키트 1건 / 스코프 밖 9건 (그중 domain_id 없음 8건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- (변경 없음)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-027]] | acceptance | 6 | UNCHANGED |
| [[AC-028]] | acceptance | 8 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-003]] | adr | 2 | UNCHANGED |
| [[ADR-006]] | adr | 4 | UNCHANGED |
| [[ADR-008]] | adr | 3 | UNCHANGED |
| [[ADR-022]] | adr | 5 | UNCHANGED |
| [[ADR-029]] | adr | 2 | UNCHANGED |
| [[ADR-030]] | adr | 4 | UNCHANGED |
| [[ADR-046]] | adr | 1 | UNCHANGED |
| [[ADR-052]] | adr | 2 | UNCHANGED |
| [[API-043]] | api_endpoint | 17 | UNCHANGED |
| [[API-047]] | api_endpoint | 12 | UNCHANGED |
| [[API-084]] | api_endpoint | 8 | UNCHANGED |
| [[API-091]] | api_endpoint | 12 | UNCHANGED |
| [[API-114]] | api_endpoint | 2 | UNCHANGED |
| [[CDIAG-002]] | class_diagram | 6 | UNCHANGED |
| [[CMP-002]] | diagram_c4_component | 3 | UNCHANGED |
| [[DFEAT-039]] | domain_feature | 11 | UNCHANGED |
| [[DFEAT-048]] | domain_feature | 16 | UNCHANGED |
| [[DOMAIN-011]] | domain | 8 | UNCHANGED |
| [[ERD-013]] | erd | 12 | UNCHANGED |
| [[EVT-001]] | domain_event | 5 | UNCHANGED |
| [[FEAT-005]] | feature | 10 | UNCHANGED |
| [[INT-002]] | integration_point | 17 | UNCHANGED |
| [[INTSPEC-003]] | integration_spec | 12 | UNCHANGED |
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
| [[ROLE-003]] | permission_role | 7 | UNCHANGED |
| [[SCREEN-006]] | screen_spec | 49 | UNCHANGED |
| [[SCREEN-008]] | screen_spec | 45 | UNCHANGED |
| [[SD-012]] | screen_design | 10 | UNCHANGED |
| [[SEQ-001]] | diagram_sequence | 20 | UNCHANGED |
| [[SEQ-014]] | diagram_sequence | 13 | UNCHANGED |
| [[TEST-001]] | test_scenario | 10 | UNCHANGED |
| [[TEST-002]] | test_scenario | 15 | UNCHANGED |
| [[UC-019]] | use_case | 21 | UNCHANGED |
