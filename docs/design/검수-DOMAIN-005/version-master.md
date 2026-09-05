# Version Master — DOMAIN-005

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-005 |
| Last sync | 2026-09-05T00:45:17.828Z |
| Mode | SYNC — NEW 1 / CHANGED 0 / UNCHANGED 137 |
| 출력 루트 | docs/design/검수-DOMAIN-005 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-005 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 38건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 6건 / 스코프 밖 43건
  ℹ️  api_endpoint: 이번 키트 25건 / 스코프 밖 198건
  ℹ️  erd: 이번 키트 3건 / 스코프 밖 20건
  ℹ️  diagram_sequence: 이번 키트 5건 / 스코프 밖 31건 (그중 domain_id 없음 11건)
  ℹ️  screen_spec: 이번 키트 4건 / 스코프 밖 34건
  ℹ️  use_case: 이번 키트 5건 / 스코프 밖 30건
  ℹ️  domain_event: 이번 키트 5건 / 스코프 밖 7건
  ℹ️  acceptance: 이번 키트 9건 / 스코프 밖 73건 (그중 domain_id 없음 2건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 11건 / 스코프 밖 45건 (그중 domain_id 없음 7건)
  ℹ️  feature: 이번 키트 4건 / 스코프 밖 6건 (그중 domain_id 없음 5건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[CDIAG-019]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-1036]] | acceptance | 6 | UNCHANGED |
| [[AC-1037]] | acceptance | 5 | UNCHANGED |
| [[AC-1038]] | acceptance | 8 | UNCHANGED |
| [[AC-1039]] | acceptance | 6 | UNCHANGED |
| [[AC-1040]] | acceptance | 6 | UNCHANGED |
| [[AC-1041]] | acceptance | 6 | UNCHANGED |
| [[AC-1042]] | acceptance | 6 | UNCHANGED |
| [[AC-1057]] | acceptance | 6 | UNCHANGED |
| [[AC-1058]] | acceptance | 10 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-002]] | adr | 3 | UNCHANGED |
| [[ADR-004]] | adr | 4 | UNCHANGED |
| [[ADR-007]] | adr | 3 | UNCHANGED |
| [[ADR-009]] | adr | 5 | UNCHANGED |
| [[ADR-015]] | adr | 4 | UNCHANGED |
| [[ADR-019]] | adr | 8 | UNCHANGED |
| [[ADR-020]] | adr | 11 | UNCHANGED |
| [[ADR-031]] | adr | 2 | UNCHANGED |
| [[ADR-055]] | adr | 5 | UNCHANGED |
| [[ADR-060]] | adr | 1 | UNCHANGED |
| [[API-008]] | api_endpoint | 12 | UNCHANGED |
| [[API-009]] | api_endpoint | 9 | UNCHANGED |
| [[API-010]] | api_endpoint | 4 | UNCHANGED |
| [[API-011]] | api_endpoint | 4 | UNCHANGED |
| [[API-012]] | api_endpoint | 6 | UNCHANGED |
| [[API-013]] | api_endpoint | 6 | UNCHANGED |
| [[API-014]] | api_endpoint | 12 | UNCHANGED |
| [[API-015]] | api_endpoint | 8 | UNCHANGED |
| [[API-016]] | api_endpoint | 3 | UNCHANGED |
| [[API-017]] | api_endpoint | 5 | UNCHANGED |
| [[API-021]] | api_endpoint | 9 | UNCHANGED |
| [[API-065]] | api_endpoint | 21 | UNCHANGED |
| [[API-066]] | api_endpoint | 7 | UNCHANGED |
| [[API-067]] | api_endpoint | 8 | UNCHANGED |
| [[API-102]] | api_endpoint | 14 | UNCHANGED |
| [[API-103]] | api_endpoint | 11 | UNCHANGED |
| [[API-104]] | api_endpoint | 14 | UNCHANGED |
| [[API-105]] | api_endpoint | 7 | UNCHANGED |
| [[API-128]] | api_endpoint | 3 | UNCHANGED |
| [[API-132]] | api_endpoint | 5 | UNCHANGED |
| [[API-138]] | api_endpoint | 4 | UNCHANGED |
| [[API-168]] | api_endpoint | 2 | UNCHANGED |
| [[API-172]] | api_endpoint | 4 | UNCHANGED |
| [[API-178]] | api_endpoint | 8 | UNCHANGED |
| [[API-183]] | api_endpoint | 1 | UNCHANGED |
| [[CDIAG-006]] | class_diagram | 15 | UNCHANGED |
| [[CDIAG-014]] | class_diagram | 13 | UNCHANGED |
| [[CDIAG-019]] | class_diagram | 1 | NEW |
| [[CMP-005]] | diagram_c4_component | 9 | UNCHANGED |
| [[DFEAT-021]] | domain_feature | 9 | UNCHANGED |
| [[DFEAT-023]] | domain_feature | 2 | UNCHANGED |
| [[DFEAT-024]] | domain_feature | 14 | UNCHANGED |
| [[DFEAT-025]] | domain_feature | 6 | UNCHANGED |
| [[DFEAT-049]] | domain_feature | 8 | UNCHANGED |
| [[DFEAT-054]] | domain_feature | 3 | UNCHANGED |
| [[DOMAIN-005]] | domain | 16 | UNCHANGED |
| [[ERD-015]] | erd | 16 | UNCHANGED |
| [[ERD-023]] | erd | 12 | UNCHANGED |
| [[ERD-030]] | erd | 2 | UNCHANGED |
| [[EVT-003]] | domain_event | 6 | UNCHANGED |
| [[EVT-004]] | domain_event | 10 | UNCHANGED |
| [[EVT-006]] | domain_event | 8 | UNCHANGED |
| [[EVT-008]] | domain_event | 8 | UNCHANGED |
| [[EVT-009]] | domain_event | 4 | UNCHANGED |
| [[FEAT-003]] | feature | 11 | UNCHANGED |
| [[FEAT-004]] | feature | 11 | UNCHANGED |
| [[FEAT-008]] | feature | 4 | UNCHANGED |
| [[FEAT-009]] | feature | 3 | UNCHANGED |
| [[INT-003]] | integration_point | 18 | UNCHANGED |
| [[INTSPEC-002]] | integration_spec | 12 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 6 | UNCHANGED |
| [[NFR-010]] | nfr | 4 | UNCHANGED |
| [[NFR-011]] | nfr | 7 | UNCHANGED |
| [[NFR-012]] | nfr | 6 | UNCHANGED |
| [[NFR-013]] | nfr | 10 | UNCHANGED |
| [[NFR-014]] | nfr | 5 | UNCHANGED |
| [[NFR-015]] | nfr | 6 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 10 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 3 | UNCHANGED |
| [[NFR-020]] | nfr | 10 | UNCHANGED |
| [[NFR-021]] | nfr | 2 | UNCHANGED |
| [[NFR-022]] | nfr | 3 | UNCHANGED |
| [[NFR-023]] | nfr | 1 | UNCHANGED |
| [[NFR-024]] | nfr | 1 | UNCHANGED |
| [[NFR-025]] | nfr | 1 | UNCHANGED |
| [[NFR-026]] | nfr | 1 | UNCHANGED |
| [[NFR-027]] | nfr | 1 | UNCHANGED |
| [[NFR-028]] | nfr | 1 | UNCHANGED |
| [[NFR-029]] | nfr | 1 | UNCHANGED |
| [[NFR-030]] | nfr | 1 | UNCHANGED |
| [[NFR-031]] | nfr | 1 | UNCHANGED |
| [[NFR-032]] | nfr | 1 | UNCHANGED |
| [[NFR-033]] | nfr | 1 | UNCHANGED |
| [[NFR-034]] | nfr | 1 | UNCHANGED |
| [[NFR-035]] | nfr | 1 | UNCHANGED |
| [[NFR-036]] | nfr | 1 | UNCHANGED |
| [[NFR-037]] | nfr | 1 | UNCHANGED |
| [[NFR-038]] | nfr | 1 | UNCHANGED |
| [[NFR-039]] | nfr | 1 | UNCHANGED |
| [[NFR-040]] | nfr | 1 | UNCHANGED |
| [[NFR-041]] | nfr | 1 | UNCHANGED |
| [[NFR-042]] | nfr | 1 | UNCHANGED |
| [[NFR-043]] | nfr | 1 | UNCHANGED |
| [[NFR-044]] | nfr | 1 | UNCHANGED |
| [[NFR-045]] | nfr | 1 | UNCHANGED |
| [[NFR-046]] | nfr | 1 | UNCHANGED |
| [[NFR-047]] | nfr | 1 | UNCHANGED |
| [[NFR-048]] | nfr | 1 | UNCHANGED |
| [[ROLE-001]] | permission_role | 14 | UNCHANGED |
| [[ROLE-002]] | permission_role | 9 | UNCHANGED |
| [[ROLE-003]] | permission_role | 14 | UNCHANGED |
| [[ROLE-004]] | permission_role | 5 | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 102 | UNCHANGED |
| [[SCREEN-018]] | screen_spec | 29 | UNCHANGED |
| [[SCREEN-019]] | screen_spec | 43 | UNCHANGED |
| [[SCREEN-023]] | screen_spec | 47 | UNCHANGED |
| [[SD-001]] | screen_design | 5 | UNCHANGED |
| [[SD-005]] | screen_design | 7 | UNCHANGED |
| [[SEQ-008]] | diagram_sequence | 10 | UNCHANGED |
| [[SEQ-010]] | diagram_sequence | 18 | UNCHANGED |
| [[SEQ-011]] | diagram_sequence | 13 | UNCHANGED |
| [[SEQ-015]] | diagram_sequence | 5 | UNCHANGED |
| [[SEQ-023]] | diagram_sequence | 9 | UNCHANGED |
| [[TEST-002]] | test_scenario | 15 | UNCHANGED |
| [[TEST-003]] | test_scenario | 19 | UNCHANGED |
| [[TEST-004]] | test_scenario | 19 | UNCHANGED |
| [[TEST-006]] | test_scenario | 2 | UNCHANGED |
| [[TEST-007]] | test_scenario | 3 | UNCHANGED |
| [[TEST-008]] | test_scenario | 3 | UNCHANGED |
| [[UC-007]] | use_case | 15 | UNCHANGED |
| [[UC-009]] | use_case | 23 | UNCHANGED |
| [[UC-010]] | use_case | 16 | UNCHANGED |
| [[UC-022]] | use_case | 24 | UNCHANGED |
| [[UC-023]] | use_case | 30 | UNCHANGED |
| [[UI-097]] | ui_component | 5 | UNCHANGED |
