# Version Master — DOMAIN-005

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-005 |
| Last sync | 2026-09-15T15:36:16.708Z |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 159 |
| 출력 루트 | docs/design/검수-DOMAIN-005 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-005 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 40건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 6건 / 스코프 밖 43건
  ℹ️  api_endpoint: 이번 키트 27건 / 스코프 밖 210건
  ℹ️  erd: 이번 키트 3건 / 스코프 밖 22건
  ℹ️  diagram_sequence: 이번 키트 5건 / 스코프 밖 31건 (그중 domain_id 없음 11건)
  ℹ️  screen_spec: 이번 키트 4건 / 스코프 밖 35건
  ℹ️  use_case: 이번 키트 6건 / 스코프 밖 30건
  ℹ️  domain_event: 이번 키트 5건 / 스코프 밖 7건
  ℹ️  acceptance: 이번 키트 20건 / 스코프 밖 95건 (그중 domain_id 없음 13건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 13건 / 스코프 밖 49건 (그중 domain_id 없음 8건)
  ℹ️  feature: 이번 키트 4건 / 스코프 밖 10건 (그중 domain_id 없음 9건)
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
| [[AC-1036]] | acceptance | 9 | UNCHANGED |
| [[AC-1037]] | acceptance | 7 | UNCHANGED |
| [[AC-1038]] | acceptance | 8 | UNCHANGED |
| [[AC-1039]] | acceptance | 6 | UNCHANGED |
| [[AC-1040]] | acceptance | 7 | UNCHANGED |
| [[AC-1041]] | acceptance | 6 | UNCHANGED |
| [[AC-1042]] | acceptance | 7 | UNCHANGED |
| [[AC-1057]] | acceptance | 6 | UNCHANGED |
| [[AC-1058]] | acceptance | 10 | UNCHANGED |
| [[AC-1109]] | acceptance | 4 | UNCHANGED |
| [[AC-1110]] | acceptance | 1 | UNCHANGED |
| [[AC-1111]] | acceptance | 1 | UNCHANGED |
| [[AC-1112]] | acceptance | 3 | UNCHANGED |
| [[AC-1113]] | acceptance | 1 | UNCHANGED |
| [[AC-1114]] | acceptance | 1 | UNCHANGED |
| [[AC-1115]] | acceptance | 1 | UNCHANGED |
| [[AC-1116]] | acceptance | 1 | UNCHANGED |
| [[AC-1117]] | acceptance | 1 | UNCHANGED |
| [[AC-1124]] | acceptance | 3 | UNCHANGED |
| [[AC-1126]] | acceptance | 2 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-002]] | adr | 3 | UNCHANGED |
| [[ADR-004]] | adr | 4 | UNCHANGED |
| [[ADR-007]] | adr | 3 | UNCHANGED |
| [[ADR-009]] | adr | 5 | UNCHANGED |
| [[ADR-015]] | adr | 4 | UNCHANGED |
| [[ADR-019]] | adr | 8 | UNCHANGED |
| [[ADR-020]] | adr | 11 | UNCHANGED |
| [[ADR-031]] | adr | 4 | UNCHANGED |
| [[ADR-055]] | adr | 6 | UNCHANGED |
| [[ADR-060]] | adr | 1 | UNCHANGED |
| [[ADR-067]] | adr | 6 | UNCHANGED |
| [[ADR-069]] | adr | 4 | UNCHANGED |
| [[API-008]] | api_endpoint | 20 | UNCHANGED |
| [[API-009]] | api_endpoint | 10 | UNCHANGED |
| [[API-010]] | api_endpoint | 4 | UNCHANGED |
| [[API-011]] | api_endpoint | 4 | UNCHANGED |
| [[API-012]] | api_endpoint | 6 | UNCHANGED |
| [[API-013]] | api_endpoint | 11 | UNCHANGED |
| [[API-014]] | api_endpoint | 13 | UNCHANGED |
| [[API-015]] | api_endpoint | 9 | UNCHANGED |
| [[API-016]] | api_endpoint | 3 | UNCHANGED |
| [[API-017]] | api_endpoint | 5 | UNCHANGED |
| [[API-021]] | api_endpoint | 9 | UNCHANGED |
| [[API-043]] | api_endpoint | 28 | UNCHANGED |
| [[API-065]] | api_endpoint | 25 | UNCHANGED |
| [[API-066]] | api_endpoint | 7 | UNCHANGED |
| [[API-067]] | api_endpoint | 8 | UNCHANGED |
| [[API-102]] | api_endpoint | 15 | UNCHANGED |
| [[API-103]] | api_endpoint | 11 | UNCHANGED |
| [[API-104]] | api_endpoint | 16 | UNCHANGED |
| [[API-105]] | api_endpoint | 8 | UNCHANGED |
| [[API-128]] | api_endpoint | 3 | UNCHANGED |
| [[API-132]] | api_endpoint | 7 | UNCHANGED |
| [[API-138]] | api_endpoint | 5 | UNCHANGED |
| [[API-168]] | api_endpoint | 2 | UNCHANGED |
| [[API-172]] | api_endpoint | 4 | UNCHANGED |
| [[API-178]] | api_endpoint | 8 | UNCHANGED |
| [[API-183]] | api_endpoint | 1 | UNCHANGED |
| [[API-250]] | api_endpoint | 4 | UNCHANGED |
| [[CDIAG-006]] | class_diagram | 19 | UNCHANGED |
| [[CDIAG-014]] | class_diagram | 18 | UNCHANGED |
| [[CDIAG-019]] | class_diagram | 4 | UNCHANGED |
| [[CMP-005]] | diagram_c4_component | 11 | UNCHANGED |
| [[DFEAT-021]] | domain_feature | 13 | UNCHANGED |
| [[DFEAT-023]] | domain_feature | 2 | UNCHANGED |
| [[DFEAT-024]] | domain_feature | 17 | UNCHANGED |
| [[DFEAT-025]] | domain_feature | 8 | UNCHANGED |
| [[DFEAT-049]] | domain_feature | 8 | UNCHANGED |
| [[DFEAT-054]] | domain_feature | 3 | UNCHANGED |
| [[DOMAIN-005]] | domain | 19 | UNCHANGED |
| [[ERD-015]] | erd | 19 | UNCHANGED |
| [[ERD-023]] | erd | 12 | UNCHANGED |
| [[ERD-030]] | erd | 2 | UNCHANGED |
| [[EVT-003]] | domain_event | 6 | UNCHANGED |
| [[EVT-004]] | domain_event | 10 | UNCHANGED |
| [[EVT-006]] | domain_event | 8 | UNCHANGED |
| [[EVT-008]] | domain_event | 9 | UNCHANGED |
| [[EVT-009]] | domain_event | 4 | UNCHANGED |
| [[FEAT-003]] | feature | 11 | UNCHANGED |
| [[FEAT-004]] | feature | 11 | UNCHANGED |
| [[FEAT-008]] | feature | 4 | UNCHANGED |
| [[FEAT-009]] | feature | 3 | UNCHANGED |
| [[INT-003]] | integration_point | 29 | UNCHANGED |
| [[INTSPEC-002]] | integration_spec | 17 | UNCHANGED |
| [[NFR-008]] | nfr | 7 | UNCHANGED |
| [[NFR-009]] | nfr | 6 | UNCHANGED |
| [[NFR-010]] | nfr | 4 | UNCHANGED |
| [[NFR-011]] | nfr | 8 | UNCHANGED |
| [[NFR-012]] | nfr | 6 | UNCHANGED |
| [[NFR-013]] | nfr | 14 | UNCHANGED |
| [[NFR-014]] | nfr | 5 | UNCHANGED |
| [[NFR-015]] | nfr | 6 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 11 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 3 | UNCHANGED |
| [[NFR-020]] | nfr | 11 | UNCHANGED |
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
| [[ROLE-001]] | permission_role | 16 | UNCHANGED |
| [[ROLE-002]] | permission_role | 10 | UNCHANGED |
| [[ROLE-003]] | permission_role | 17 | UNCHANGED |
| [[ROLE-004]] | permission_role | 6 | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 118 | UNCHANGED |
| [[SCREEN-018]] | screen_spec | 37 | UNCHANGED |
| [[SCREEN-019]] | screen_spec | 56 | UNCHANGED |
| [[SCREEN-023]] | screen_spec | 47 | UNCHANGED |
| [[SD-001]] | screen_design | 5 | UNCHANGED |
| [[SD-005]] | screen_design | 11 | UNCHANGED |
| [[SEQ-008]] | diagram_sequence | 11 | UNCHANGED |
| [[SEQ-010]] | diagram_sequence | 19 | UNCHANGED |
| [[SEQ-011]] | diagram_sequence | 13 | UNCHANGED |
| [[SEQ-015]] | diagram_sequence | 8 | UNCHANGED |
| [[SEQ-023]] | diagram_sequence | 11 | UNCHANGED |
| [[TEST-002]] | test_scenario | 19 | UNCHANGED |
| [[TEST-003]] | test_scenario | 19 | UNCHANGED |
| [[TEST-004]] | test_scenario | 20 | UNCHANGED |
| [[TEST-006]] | test_scenario | 3 | UNCHANGED |
| [[TEST-007]] | test_scenario | 7 | UNCHANGED |
| [[TEST-008]] | test_scenario | 4 | UNCHANGED |
| [[UC-007]] | use_case | 16 | UNCHANGED |
| [[UC-009]] | use_case | 26 | UNCHANGED |
| [[UC-010]] | use_case | 18 | UNCHANGED |
| [[UC-022]] | use_case | 31 | UNCHANGED |
| [[UC-023]] | use_case | 37 | UNCHANGED |
| [[UC-043]] | use_case | 2 | UNCHANGED |
| [[UI-056]] | ui_component | 8 | UNCHANGED |
| [[UI-097]] | ui_component | 6 | UNCHANGED |
| [[UI-107]] | ui_component | 7 | UNCHANGED |
| [[UI-156]] | ui_component | 2 | UNCHANGED |
| [[UI-157]] | ui_component | 3 | UNCHANGED |
| [[UI-158]] | ui_component | 3 | UNCHANGED |
