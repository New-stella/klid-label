# Version Master — DOMAIN-005

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-005 |
| Last sync | 2026-08-21T04:17:32.532Z |
| Mode | SYNC — NEW 0 / CHANGED 4 / UNCHANGED 90 |
| 출력 루트 | docs/design/검수-DOMAIN-005 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-005 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 12건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 6건 / 스코프 밖 42건
  ℹ️  api_endpoint: 이번 키트 21건 / 스코프 밖 173건
  ℹ️  erd: 이번 키트 3건 / 스코프 밖 18건
  ℹ️  diagram_sequence: 이번 키트 5건 / 스코프 밖 21건 (그중 domain_id 없음 11건)
  ℹ️  screen_spec: 이번 키트 4건 / 스코프 밖 29건
  ℹ️  use_case: 이번 키트 5건 / 스코프 밖 24건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 5건 / 스코프 밖 7건
  ℹ️  acceptance: 이번 키트 3건 / 스코프 밖 49건 (그중 domain_id 없음 28건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 10건 / 스코프 밖 35건 (그중 domain_id 없음 3건)
  ℹ️  feature: 이번 키트 4건 / 스코프 밖 6건 (그중 domain_id 없음 5건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[API-014]] (prev v11)
- CHANGED [[CDIAG-006]] (prev v10)
- CHANGED [[SEQ-008]] (prev v7)
- CHANGED [[SEQ-015]] (prev v3)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-010]] | acceptance | 7 | UNCHANGED |
| [[AC-022]] | acceptance | 10 | UNCHANGED |
| [[AC-024]] | acceptance | 6 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-002]] | adr | 2 | UNCHANGED |
| [[ADR-003]] | adr | 2 | UNCHANGED |
| [[ADR-004]] | adr | 4 | UNCHANGED |
| [[ADR-007]] | adr | 3 | UNCHANGED |
| [[ADR-009]] | adr | 5 | UNCHANGED |
| [[ADR-015]] | adr | 4 | UNCHANGED |
| [[ADR-019]] | adr | 7 | UNCHANGED |
| [[ADR-020]] | adr | 10 | UNCHANGED |
| [[ADR-031]] | adr | 2 | UNCHANGED |
| [[API-008]] | api_endpoint | 12 | UNCHANGED |
| [[API-009]] | api_endpoint | 9 | UNCHANGED |
| [[API-010]] | api_endpoint | 4 | UNCHANGED |
| [[API-011]] | api_endpoint | 4 | UNCHANGED |
| [[API-012]] | api_endpoint | 6 | UNCHANGED |
| [[API-013]] | api_endpoint | 6 | UNCHANGED |
| [[API-014]] | api_endpoint | 12 | CHANGED |
| [[API-015]] | api_endpoint | 8 | UNCHANGED |
| [[API-016]] | api_endpoint | 3 | UNCHANGED |
| [[API-017]] | api_endpoint | 5 | UNCHANGED |
| [[API-021]] | api_endpoint | 8 | UNCHANGED |
| [[API-065]] | api_endpoint | 15 | UNCHANGED |
| [[API-066]] | api_endpoint | 4 | UNCHANGED |
| [[API-067]] | api_endpoint | 5 | UNCHANGED |
| [[API-102]] | api_endpoint | 13 | UNCHANGED |
| [[API-103]] | api_endpoint | 10 | UNCHANGED |
| [[API-104]] | api_endpoint | 12 | UNCHANGED |
| [[API-105]] | api_endpoint | 7 | UNCHANGED |
| [[API-132]] | api_endpoint | 4 | UNCHANGED |
| [[API-138]] | api_endpoint | 4 | UNCHANGED |
| [[API-178]] | api_endpoint | 8 | UNCHANGED |
| [[CDIAG-006]] | class_diagram | 11 | CHANGED |
| [[CDIAG-014]] | class_diagram | 8 | UNCHANGED |
| [[CMP-005]] | diagram_c4_component | 5 | UNCHANGED |
| [[DFEAT-021]] | domain_feature | 8 | UNCHANGED |
| [[DFEAT-023]] | domain_feature | 2 | UNCHANGED |
| [[DFEAT-024]] | domain_feature | 10 | UNCHANGED |
| [[DFEAT-025]] | domain_feature | 6 | UNCHANGED |
| [[DFEAT-049]] | domain_feature | 7 | UNCHANGED |
| [[DFEAT-054]] | domain_feature | 3 | UNCHANGED |
| [[DOMAIN-005]] | domain | 14 | UNCHANGED |
| [[ERD-015]] | erd | 16 | UNCHANGED |
| [[ERD-023]] | erd | 10 | UNCHANGED |
| [[ERD-030]] | erd | 1 | UNCHANGED |
| [[EVT-003]] | domain_event | 6 | UNCHANGED |
| [[EVT-004]] | domain_event | 10 | UNCHANGED |
| [[EVT-006]] | domain_event | 8 | UNCHANGED |
| [[EVT-008]] | domain_event | 8 | UNCHANGED |
| [[EVT-009]] | domain_event | 4 | UNCHANGED |
| [[FEAT-003]] | feature | 10 | UNCHANGED |
| [[FEAT-004]] | feature | 9 | UNCHANGED |
| [[FEAT-008]] | feature | 4 | UNCHANGED |
| [[FEAT-009]] | feature | 3 | UNCHANGED |
| [[INT-003]] | integration_point | 13 | UNCHANGED |
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
| [[SCREEN-005]] | screen_spec | 96 | UNCHANGED |
| [[SCREEN-018]] | screen_spec | 29 | UNCHANGED |
| [[SCREEN-019]] | screen_spec | 37 | UNCHANGED |
| [[SCREEN-023]] | screen_spec | 41 | UNCHANGED |
| [[SD-001]] | screen_design | 5 | UNCHANGED |
| [[SD-005]] | screen_design | 5 | UNCHANGED |
| [[SEQ-008]] | diagram_sequence | 8 | CHANGED |
| [[SEQ-010]] | diagram_sequence | 14 | UNCHANGED |
| [[SEQ-011]] | diagram_sequence | 7 | UNCHANGED |
| [[SEQ-015]] | diagram_sequence | 4 | CHANGED |
| [[SEQ-023]] | diagram_sequence | 2 | UNCHANGED |
| [[TEST-002]] | test_scenario | 12 | UNCHANGED |
| [[TEST-003]] | test_scenario | 15 | UNCHANGED |
| [[TEST-004]] | test_scenario | 19 | UNCHANGED |
| [[TEST-006]] | test_scenario | 2 | UNCHANGED |
| [[UC-007]] | use_case | 13 | UNCHANGED |
| [[UC-009]] | use_case | 20 | UNCHANGED |
| [[UC-010]] | use_case | 13 | UNCHANGED |
| [[UC-022]] | use_case | 17 | UNCHANGED |
| [[UC-023]] | use_case | 25 | UNCHANGED |
