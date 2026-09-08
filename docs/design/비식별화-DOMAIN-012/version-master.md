# Version Master — DOMAIN-012

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-012 |
| Last sync | 2026-09-08T04:11:53.371Z |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 119 |
| 출력 루트 | docs/design/비식별화-DOMAIN-012 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-012 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 14건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 5건 / 스코프 밖 44건
  ℹ️  api_endpoint: 이번 키트 11건 / 스코프 밖 212건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 23건
  ℹ️  diagram_sequence: 이번 키트 8건 / 스코프 밖 28건 (그중 domain_id 없음 10건)
  ℹ️  screen_spec: 이번 키트 6건 / 스코프 밖 32건
  ℹ️  use_case: 이번 키트 5건 / 스코프 밖 30건
  ℹ️  domain_event: 이번 키트 2건 / 스코프 밖 10건
  ℹ️  acceptance: 이번 키트 5건 / 스코프 밖 84건 (그중 domain_id 없음 9건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 17건 / 스코프 밖 41건 (그중 domain_id 없음 5건)
  ℹ️  feature: 이번 키트 2건 / 스코프 밖 8건 (그중 domain_id 없음 7건)
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
| [[AC-1063]] | acceptance | 7 | UNCHANGED |
| [[AC-1064]] | acceptance | 9 | UNCHANGED |
| [[AC-1065]] | acceptance | 4 | UNCHANGED |
| [[AC-1066]] | acceptance | 9 | UNCHANGED |
| [[AC-1067]] | acceptance | 5 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-006]] | adr | 4 | UNCHANGED |
| [[ADR-020]] | adr | 11 | UNCHANGED |
| [[ADR-022]] | adr | 5 | UNCHANGED |
| [[ADR-024]] | adr | 3 | UNCHANGED |
| [[ADR-025]] | adr | 3 | UNCHANGED |
| [[ADR-027]] | adr | 2 | UNCHANGED |
| [[ADR-029]] | adr | 2 | UNCHANGED |
| [[ADR-030]] | adr | 4 | UNCHANGED |
| [[ADR-032]] | adr | 7 | UNCHANGED |
| [[ADR-046]] | adr | 16 | UNCHANGED |
| [[ADR-049]] | adr | 4 | UNCHANGED |
| [[ADR-051]] | adr | 8 | UNCHANGED |
| [[ADR-054]] | adr | 5 | UNCHANGED |
| [[ADR-055]] | adr | 6 | UNCHANGED |
| [[ADR-062]] | adr | 6 | UNCHANGED |
| [[ADR-065]] | adr | 1 | UNCHANGED |
| [[API-032]] | api_endpoint | 9 | UNCHANGED |
| [[API-091]] | api_endpoint | 12 | UNCHANGED |
| [[API-094]] | api_endpoint | 9 | UNCHANGED |
| [[API-109]] | api_endpoint | 5 | UNCHANGED |
| [[API-112]] | api_endpoint | 6 | UNCHANGED |
| [[API-175]] | api_endpoint | 2 | UNCHANGED |
| [[API-183]] | api_endpoint | 1 | UNCHANGED |
| [[API-184]] | api_endpoint | 2 | UNCHANGED |
| [[API-202]] | api_endpoint | 2 | UNCHANGED |
| [[API-207]] | api_endpoint | 7 | UNCHANGED |
| [[API-215]] | api_endpoint | 6 | UNCHANGED |
| [[CDIAG-003]] | class_diagram | 13 | UNCHANGED |
| [[CDIAG-032]] | class_diagram | 1 | UNCHANGED |
| [[CDIAG-043]] | class_diagram | 1 | UNCHANGED |
| [[CMP-003]] | diagram_c4_component | 13 | UNCHANGED |
| [[DFEAT-041]] | domain_feature | 15 | UNCHANGED |
| [[DFEAT-042]] | domain_feature | 12 | UNCHANGED |
| [[DFEAT-048]] | domain_feature | 19 | UNCHANGED |
| [[DFEAT-051]] | domain_feature | 5 | UNCHANGED |
| [[DFEAT-054]] | domain_feature | 3 | UNCHANGED |
| [[DOMAIN-012]] | domain | 11 | UNCHANGED |
| [[ERD-017]] | erd | 25 | UNCHANGED |
| [[EVT-007]] | domain_event | 3 | UNCHANGED |
| [[EVT-008]] | domain_event | 8 | UNCHANGED |
| [[EXTSYS-003]] | external_system | 13 | UNCHANGED |
| [[FEAT-005]] | feature | 10 | UNCHANGED |
| [[FEAT-006]] | feature | 11 | UNCHANGED |
| [[INT-004]] | integration_point | 15 | UNCHANGED |
| [[INT-005]] | integration_point | 10 | UNCHANGED |
| [[INTSPEC-001]] | integration_spec | 11 | UNCHANGED |
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
| [[SCREEN-005]] | screen_spec | 106 | UNCHANGED |
| [[SCREEN-008]] | screen_spec | 48 | UNCHANGED |
| [[SCREEN-009]] | screen_spec | 76 | UNCHANGED |
| [[SCREEN-019]] | screen_spec | 43 | UNCHANGED |
| [[SCREEN-025]] | screen_spec | 49 | UNCHANGED |
| [[SCREEN-032]] | screen_spec | 28 | UNCHANGED |
| [[SD-021]] | screen_design | 8 | UNCHANGED |
| [[SEQ-001]] | diagram_sequence | 25 | UNCHANGED |
| [[SEQ-012]] | diagram_sequence | 17 | UNCHANGED |
| [[SEQ-013]] | diagram_sequence | 8 | UNCHANGED |
| [[SEQ-014]] | diagram_sequence | 15 | UNCHANGED |
| [[SEQ-025]] | diagram_sequence | 7 | UNCHANGED |
| [[SEQ-030]] | diagram_sequence | 10 | UNCHANGED |
| [[SEQ-031]] | diagram_sequence | 6 | UNCHANGED |
| [[SEQ-035]] | diagram_sequence | 6 | UNCHANGED |
| [[TEST-001]] | test_scenario | 12 | UNCHANGED |
| [[TEST-008]] | test_scenario | 4 | UNCHANGED |
| [[UC-011]] | use_case | 21 | UNCHANGED |
| [[UC-013]] | use_case | 12 | UNCHANGED |
| [[UC-016]] | use_case | 31 | UNCHANGED |
| [[UC-019]] | use_case | 30 | UNCHANGED |
| [[UC-036]] | use_case | 8 | UNCHANGED |
