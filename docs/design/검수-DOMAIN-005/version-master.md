# Version Master — DOMAIN-005

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-005 |
| Last sync | 2026-09-02T10:52:16.868Z |
| Mode | SYNC — NEW 0 / CHANGED 23 / UNCHANGED 85 |
| 출력 루트 | docs/design/검수-DOMAIN-005 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-005 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 20건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 6건 / 스코프 밖 42건
  ℹ️  api_endpoint: 이번 키트 25건 / 스코프 밖 195건
  ℹ️  erd: 이번 키트 3건 / 스코프 밖 20건
  ℹ️  diagram_sequence: 이번 키트 5건 / 스코프 밖 21건 (그중 domain_id 없음 11건)
  ℹ️  screen_spec: 이번 키트 4건 / 스코프 밖 34건
  ℹ️  use_case: 이번 키트 5건 / 스코프 밖 30건 (그중 domain_id 없음 3건)
  ℹ️  domain_event: 이번 키트 5건 / 스코프 밖 7건
  ℹ️  acceptance: 이번 키트 7건 / 스코프 밖 73건 (그중 domain_id 없음 2건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 10건 / 스코프 밖 42건 (그중 domain_id 없음 6건)
  ℹ️  feature: 이번 키트 4건 / 스코프 밖 6건 (그중 domain_id 없음 5건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[AC-1038]] (prev v7)
- CHANGED [[AC-1039]] (prev v5)
- CHANGED [[ADR-002]] (prev v2)
- CHANGED [[ADR-020]] (prev v10)
- CHANGED [[ADR-055]] (prev v3)
- CHANGED [[CDIAG-006]] (prev v12)
- CHANGED [[CMP-005]] (prev v6)
- CHANGED [[SEQ-011]] (prev v9)
- CHANGED [[DOMAIN-005]] (prev v14)
- CHANGED [[DFEAT-024]] (prev v11)
- CHANGED [[ERD-023]] (prev v10)
- CHANGED [[ERD-030]] (prev v1)
- CHANGED [[FEAT-004]] (prev v10)
- CHANGED [[NFR-009]] (prev v4)
- CHANGED [[NFR-017]] (prev v9)
- CHANGED [[NFR-020]] (prev v9)
- CHANGED [[ROLE-001]] (prev v13)
- CHANGED [[ROLE-003]] (prev v12)
- CHANGED [[ROLE-004]] (prev v4)
- CHANGED [[SCREEN-023]] (prev v41)
- CHANGED [[TEST-003]] (prev v15)
- CHANGED [[UC-010]] (prev v15)
- CHANGED [[UC-023]] (prev v28)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-1036]] | acceptance | 6 | UNCHANGED |
| [[AC-1037]] | acceptance | 5 | UNCHANGED |
| [[AC-1038]] | acceptance | 8 | CHANGED |
| [[AC-1039]] | acceptance | 6 | CHANGED |
| [[AC-1040]] | acceptance | 6 | UNCHANGED |
| [[AC-1041]] | acceptance | 6 | UNCHANGED |
| [[AC-1042]] | acceptance | 6 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-002]] | adr | 3 | CHANGED |
| [[ADR-004]] | adr | 4 | UNCHANGED |
| [[ADR-007]] | adr | 3 | UNCHANGED |
| [[ADR-009]] | adr | 5 | UNCHANGED |
| [[ADR-015]] | adr | 4 | UNCHANGED |
| [[ADR-019]] | adr | 8 | UNCHANGED |
| [[ADR-020]] | adr | 11 | CHANGED |
| [[ADR-031]] | adr | 2 | UNCHANGED |
| [[ADR-055]] | adr | 5 | CHANGED |
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
| [[CDIAG-006]] | class_diagram | 13 | CHANGED |
| [[CDIAG-014]] | class_diagram | 13 | UNCHANGED |
| [[CMP-005]] | diagram_c4_component | 7 | CHANGED |
| [[DFEAT-021]] | domain_feature | 9 | UNCHANGED |
| [[DFEAT-023]] | domain_feature | 2 | UNCHANGED |
| [[DFEAT-024]] | domain_feature | 12 | CHANGED |
| [[DFEAT-025]] | domain_feature | 6 | UNCHANGED |
| [[DFEAT-049]] | domain_feature | 8 | UNCHANGED |
| [[DFEAT-054]] | domain_feature | 3 | UNCHANGED |
| [[DOMAIN-005]] | domain | 15 | CHANGED |
| [[ERD-015]] | erd | 16 | UNCHANGED |
| [[ERD-023]] | erd | 12 | CHANGED |
| [[ERD-030]] | erd | 2 | CHANGED |
| [[EVT-003]] | domain_event | 6 | UNCHANGED |
| [[EVT-004]] | domain_event | 10 | UNCHANGED |
| [[EVT-006]] | domain_event | 8 | UNCHANGED |
| [[EVT-008]] | domain_event | 8 | UNCHANGED |
| [[EVT-009]] | domain_event | 4 | UNCHANGED |
| [[FEAT-003]] | feature | 11 | UNCHANGED |
| [[FEAT-004]] | feature | 11 | CHANGED |
| [[FEAT-008]] | feature | 4 | UNCHANGED |
| [[FEAT-009]] | feature | 3 | UNCHANGED |
| [[INT-003]] | integration_point | 18 | UNCHANGED |
| [[INTSPEC-002]] | integration_spec | 12 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 6 | CHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 6 | UNCHANGED |
| [[NFR-012]] | nfr | 5 | UNCHANGED |
| [[NFR-013]] | nfr | 10 | UNCHANGED |
| [[NFR-014]] | nfr | 4 | UNCHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 10 | CHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 10 | CHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[NFR-022]] | nfr | 2 | UNCHANGED |
| [[ROLE-001]] | permission_role | 14 | CHANGED |
| [[ROLE-002]] | permission_role | 9 | UNCHANGED |
| [[ROLE-003]] | permission_role | 14 | CHANGED |
| [[ROLE-004]] | permission_role | 5 | CHANGED |
| [[SCREEN-005]] | screen_spec | 102 | UNCHANGED |
| [[SCREEN-018]] | screen_spec | 29 | UNCHANGED |
| [[SCREEN-019]] | screen_spec | 43 | UNCHANGED |
| [[SCREEN-023]] | screen_spec | 47 | CHANGED |
| [[SD-001]] | screen_design | 5 | UNCHANGED |
| [[SD-005]] | screen_design | 7 | UNCHANGED |
| [[SEQ-008]] | diagram_sequence | 9 | UNCHANGED |
| [[SEQ-010]] | diagram_sequence | 15 | UNCHANGED |
| [[SEQ-011]] | diagram_sequence | 11 | CHANGED |
| [[SEQ-015]] | diagram_sequence | 4 | UNCHANGED |
| [[SEQ-023]] | diagram_sequence | 9 | UNCHANGED |
| [[TEST-002]] | test_scenario | 15 | UNCHANGED |
| [[TEST-003]] | test_scenario | 19 | CHANGED |
| [[TEST-004]] | test_scenario | 19 | UNCHANGED |
| [[TEST-006]] | test_scenario | 2 | UNCHANGED |
| [[TEST-007]] | test_scenario | 3 | UNCHANGED |
| [[TEST-008]] | test_scenario | 3 | UNCHANGED |
| [[UC-007]] | use_case | 15 | UNCHANGED |
| [[UC-009]] | use_case | 22 | UNCHANGED |
| [[UC-010]] | use_case | 16 | CHANGED |
| [[UC-022]] | use_case | 24 | UNCHANGED |
| [[UC-023]] | use_case | 29 | CHANGED |
| [[UI-097]] | ui_component | 5 | UNCHANGED |
