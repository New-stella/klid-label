# Version Master — DOMAIN-012

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-012 |
| Last sync | 2026-09-01T08:40:14.728Z |
| Mode | SYNC — NEW 0 / CHANGED 15 / UNCHANGED 68 |
| 출력 루트 | 비식별화-DOMAIN-012 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-012 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 10건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 5건 / 스코프 밖 43건
  ℹ️  api_endpoint: 이번 키트 9건 / 스코프 밖 194건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 22건
  ℹ️  diagram_sequence: 이번 키트 5건 / 스코프 밖 21건 (그중 domain_id 없음 10건)
  ℹ️  screen_spec: 이번 키트 6건 / 스코프 밖 31건
  ℹ️  use_case: 이번 키트 4건 / 스코프 밖 31건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 2건 / 스코프 밖 10건
  ℹ️  acceptance: 이번 키트 5건 / 스코프 밖 70건 (그중 domain_id 없음 2건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 15건 / 스코프 밖 35건 (그중 domain_id 없음 2건)
  ℹ️  feature: 이번 키트 2건 / 스코프 밖 8건 (그중 domain_id 없음 7건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[DFEAT-041]] (prev v12)
- CHANGED [[DFEAT-042]] (prev v11)
- CHANGED [[DFEAT-048]] (prev v16)
- CHANGED [[NFR-011]] (prev v5)
- CHANGED [[NFR-013]] (prev v9)
- CHANGED [[NFR-014]] (prev v3)
- CHANGED [[NFR-020]] (prev v8)
- CHANGED [[SD-021]] (prev v4)
- CHANGED [[SCREEN-008]] (prev v47)
- CHANGED [[SCREEN-009]] (prev v75)
- CHANGED [[SCREEN-025]] (prev v46)
- CHANGED [[UC-011]] (prev v13)
- CHANGED [[UC-013]] (prev v11)
- CHANGED [[UC-016]] (prev v25)
- CHANGED [[UC-019]] (prev v24)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-1063]] | acceptance | 6 | UNCHANGED |
| [[AC-1064]] | acceptance | 6 | UNCHANGED |
| [[AC-1065]] | acceptance | 4 | UNCHANGED |
| [[AC-1066]] | acceptance | 7 | UNCHANGED |
| [[AC-1067]] | acceptance | 5 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-006]] | adr | 4 | UNCHANGED |
| [[ADR-020]] | adr | 10 | UNCHANGED |
| [[ADR-022]] | adr | 5 | UNCHANGED |
| [[ADR-024]] | adr | 3 | UNCHANGED |
| [[ADR-025]] | adr | 3 | UNCHANGED |
| [[ADR-027]] | adr | 2 | UNCHANGED |
| [[ADR-029]] | adr | 2 | UNCHANGED |
| [[ADR-030]] | adr | 4 | UNCHANGED |
| [[ADR-032]] | adr | 7 | UNCHANGED |
| [[ADR-046]] | adr | 9 | UNCHANGED |
| [[ADR-049]] | adr | 4 | UNCHANGED |
| [[ADR-051]] | adr | 5 | UNCHANGED |
| [[ADR-054]] | adr | 5 | UNCHANGED |
| [[ADR-055]] | adr | 3 | UNCHANGED |
| [[API-032]] | api_endpoint | 8 | UNCHANGED |
| [[API-091]] | api_endpoint | 12 | UNCHANGED |
| [[API-094]] | api_endpoint | 9 | UNCHANGED |
| [[API-109]] | api_endpoint | 5 | UNCHANGED |
| [[API-112]] | api_endpoint | 5 | UNCHANGED |
| [[API-175]] | api_endpoint | 2 | UNCHANGED |
| [[API-183]] | api_endpoint | 1 | UNCHANGED |
| [[API-184]] | api_endpoint | 2 | UNCHANGED |
| [[API-202]] | api_endpoint | 2 | UNCHANGED |
| [[CDIAG-003]] | class_diagram | 11 | UNCHANGED |
| [[CMP-003]] | diagram_c4_component | 11 | UNCHANGED |
| [[DFEAT-041]] | domain_feature | 13 | CHANGED |
| [[DFEAT-042]] | domain_feature | 12 | CHANGED |
| [[DFEAT-048]] | domain_feature | 17 | CHANGED |
| [[DFEAT-051]] | domain_feature | 5 | UNCHANGED |
| [[DFEAT-054]] | domain_feature | 3 | UNCHANGED |
| [[DOMAIN-012]] | domain | 8 | UNCHANGED |
| [[ERD-017]] | erd | 22 | UNCHANGED |
| [[EVT-007]] | domain_event | 3 | UNCHANGED |
| [[EVT-008]] | domain_event | 8 | UNCHANGED |
| [[EXTSYS-003]] | external_system | 8 | UNCHANGED |
| [[FEAT-005]] | feature | 10 | UNCHANGED |
| [[FEAT-006]] | feature | 11 | UNCHANGED |
| [[INT-004]] | integration_point | 11 | UNCHANGED |
| [[INT-005]] | integration_point | 7 | UNCHANGED |
| [[INTSPEC-001]] | integration_spec | 9 | UNCHANGED |
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
| [[SCREEN-005]] | screen_spec | 102 | UNCHANGED |
| [[SCREEN-008]] | screen_spec | 48 | CHANGED |
| [[SCREEN-009]] | screen_spec | 76 | CHANGED |
| [[SCREEN-019]] | screen_spec | 43 | UNCHANGED |
| [[SCREEN-025]] | screen_spec | 47 | CHANGED |
| [[SCREEN-032]] | screen_spec | 26 | UNCHANGED |
| [[SD-021]] | screen_design | 6 | CHANGED |
| [[SEQ-001]] | diagram_sequence | 20 | UNCHANGED |
| [[SEQ-012]] | diagram_sequence | 13 | UNCHANGED |
| [[SEQ-013]] | diagram_sequence | 7 | UNCHANGED |
| [[SEQ-014]] | diagram_sequence | 13 | UNCHANGED |
| [[SEQ-025]] | diagram_sequence | 5 | UNCHANGED |
| [[TEST-001]] | test_scenario | 10 | UNCHANGED |
| [[TEST-008]] | test_scenario | 3 | UNCHANGED |
| [[UC-011]] | use_case | 14 | CHANGED |
| [[UC-013]] | use_case | 12 | CHANGED |
| [[UC-016]] | use_case | 26 | CHANGED |
| [[UC-019]] | use_case | 25 | CHANGED |
