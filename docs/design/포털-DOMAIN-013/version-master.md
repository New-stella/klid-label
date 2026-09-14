# Version Master — DOMAIN-013

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-013 |
| Last sync | 2026-09-14T07:37:39.904Z |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 130 |
| 출력 루트 | docs/design/포털-DOMAIN-013 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-013 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 59건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 4건 / 스코프 밖 45건
  ℹ️  api_endpoint: 이번 키트 37건 / 스코프 밖 188건
  ℹ️  erd: 이번 키트 4건 / 스코프 밖 21건
  ℹ️  diagram_sequence: 이번 키트 3건 / 스코프 밖 33건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 5건 / 스코프 밖 33건
  ℹ️  use_case: 이번 키트 2건 / 스코프 밖 33건
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 7건 / 스코프 밖 87건 (그중 domain_id 없음 9건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 7건 / 스코프 밖 52건 (그중 domain_id 없음 11건)
  🚨 feature: 이번 키트 0건 / 프로젝트 전역 14건 (그중 domain_id 없음 13건) — 전량 누락
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
| [[AC-1068]] | acceptance | 13 | UNCHANGED |
| [[AC-1069]] | acceptance | 10 | UNCHANGED |
| [[AC-1070]] | acceptance | 19 | UNCHANGED |
| [[AC-1071]] | acceptance | 10 | UNCHANGED |
| [[AC-1102]] | acceptance | 3 | UNCHANGED |
| [[AC-1103]] | acceptance | 5 | UNCHANGED |
| [[AC-1106]] | acceptance | 9 | UNCHANGED |
| [[ADR-012]] | adr | 22 | UNCHANGED |
| [[ADR-013]] | adr | 23 | UNCHANGED |
| [[ADR-026]] | adr | 6 | UNCHANGED |
| [[ADR-055]] | adr | 6 | UNCHANGED |
| [[ADR-058]] | adr | 10 | UNCHANGED |
| [[ADR-059]] | adr | 1 | UNCHANGED |
| [[ADR-061]] | adr | 1 | UNCHANGED |
| [[API-024]] | api_endpoint | 6 | UNCHANGED |
| [[API-081]] | api_endpoint | 8 | UNCHANGED |
| [[API-082]] | api_endpoint | 10 | UNCHANGED |
| [[API-083]] | api_endpoint | 6 | UNCHANGED |
| [[API-110]] | api_endpoint | 4 | UNCHANGED |
| [[API-111]] | api_endpoint | 3 | UNCHANGED |
| [[API-115]] | api_endpoint | 4 | UNCHANGED |
| [[API-140]] | api_endpoint | 6 | UNCHANGED |
| [[API-142]] | api_endpoint | 8 | UNCHANGED |
| [[API-147]] | api_endpoint | 2 | UNCHANGED |
| [[API-149]] | api_endpoint | 3 | UNCHANGED |
| [[API-151]] | api_endpoint | 2 | UNCHANGED |
| [[API-154]] | api_endpoint | 4 | UNCHANGED |
| [[API-155]] | api_endpoint | 2 | UNCHANGED |
| [[API-157]] | api_endpoint | 4 | UNCHANGED |
| [[API-159]] | api_endpoint | 4 | UNCHANGED |
| [[API-161]] | api_endpoint | 3 | UNCHANGED |
| [[API-163]] | api_endpoint | 3 | UNCHANGED |
| [[API-166]] | api_endpoint | 3 | UNCHANGED |
| [[API-169]] | api_endpoint | 7 | UNCHANGED |
| [[API-171]] | api_endpoint | 4 | UNCHANGED |
| [[API-203]] | api_endpoint | 9 | UNCHANGED |
| [[API-225]] | api_endpoint | 9 | UNCHANGED |
| [[API-231]] | api_endpoint | 5 | UNCHANGED |
| [[API-232]] | api_endpoint | 4 | UNCHANGED |
| [[API-233]] | api_endpoint | 5 | UNCHANGED |
| [[API-234]] | api_endpoint | 5 | UNCHANGED |
| [[API-235]] | api_endpoint | 5 | UNCHANGED |
| [[API-236]] | api_endpoint | 3 | UNCHANGED |
| [[API-237]] | api_endpoint | 4 | UNCHANGED |
| [[API-238]] | api_endpoint | 1 | UNCHANGED |
| [[API-239]] | api_endpoint | 1 | UNCHANGED |
| [[API-240]] | api_endpoint | 4 | UNCHANGED |
| [[API-241]] | api_endpoint | 1 | UNCHANGED |
| [[API-242]] | api_endpoint | 3 | UNCHANGED |
| [[API-243]] | api_endpoint | 8 | UNCHANGED |
| [[API-244]] | api_endpoint | 7 | UNCHANGED |
| [[CDIAG-011]] | class_diagram | 20 | UNCHANGED |
| [[CMP-009]] | diagram_c4_component | 21 | UNCHANGED |
| [[DFEAT-043]] | domain_feature | 18 | UNCHANGED |
| [[DFEAT-044]] | domain_feature | 26 | UNCHANGED |
| [[DFEAT-053]] | domain_feature | 21 | UNCHANGED |
| [[DFEAT-055]] | domain_feature | 20 | UNCHANGED |
| [[DOMAIN-013]] | domain | 23 | UNCHANGED |
| [[ERD-018]] | erd | 16 | UNCHANGED |
| [[ERD-026]] | erd | 6 | UNCHANGED |
| [[ERD-028]] | erd | 7 | UNCHANGED |
| [[ERD-035]] | erd | 3 | UNCHANGED |
| [[EVT-012]] | domain_event | 5 | UNCHANGED |
| [[EXTSYS-006]] | external_system | 15 | UNCHANGED |
| [[INT-008]] | integration_point | 16 | UNCHANGED |
| [[INT-009]] | integration_point | 13 | UNCHANGED |
| [[INT-013]] | integration_point | 30 | UNCHANGED |
| [[INT-014]] | integration_point | 14 | UNCHANGED |
| [[NAV-002]] | navigation_tree | 17 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 6 | UNCHANGED |
| [[NFR-010]] | nfr | 4 | UNCHANGED |
| [[NFR-011]] | nfr | 7 | UNCHANGED |
| [[NFR-012]] | nfr | 6 | UNCHANGED |
| [[NFR-013]] | nfr | 14 | UNCHANGED |
| [[NFR-014]] | nfr | 5 | UNCHANGED |
| [[NFR-015]] | nfr | 6 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 10 | UNCHANGED |
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
| [[ROLE-001]] | permission_role | 14 | UNCHANGED |
| [[ROLE-002]] | permission_role | 10 | UNCHANGED |
| [[ROLE-003]] | permission_role | 15 | UNCHANGED |
| [[ROLE-004]] | permission_role | 5 | UNCHANGED |
| [[SCREEN-028]] | screen_spec | 41 | UNCHANGED |
| [[SCREEN-029]] | screen_spec | 54 | UNCHANGED |
| [[SCREEN-033]] | screen_spec | 43 | UNCHANGED |
| [[SCREEN-044]] | screen_spec | 14 | UNCHANGED |
| [[SCREEN-045]] | screen_spec | 6 | UNCHANGED |
| [[SD-024]] | screen_design | 12 | UNCHANGED |
| [[SD-025]] | screen_design | 6 | UNCHANGED |
| [[SD-026]] | screen_design | 11 | UNCHANGED |
| [[SD-027]] | screen_design | 11 | UNCHANGED |
| [[SEQ-016]] | diagram_sequence | 15 | UNCHANGED |
| [[SEQ-019]] | diagram_sequence | 14 | UNCHANGED |
| [[SEQ-034]] | diagram_sequence | 23 | UNCHANGED |
| [[SHELL-002]] | app_shell | 8 | UNCHANGED |
| [[TEST-005]] | test_scenario | 22 | UNCHANGED |
| [[UC-024]] | use_case | 34 | UNCHANGED |
| [[UC-027]] | use_case | 35 | UNCHANGED |
