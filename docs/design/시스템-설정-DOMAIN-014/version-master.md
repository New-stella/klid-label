# Version Master — DOMAIN-014

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-014 |
| Last sync | 2026-08-29T00:36:36.733Z |
| Mode | SYNC — NEW 0 / CHANGED 6 / UNCHANGED 85 |
| 출력 루트 | docs/design/시스템-설정-DOMAIN-014 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-014 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 15건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 1건 / 스코프 밖 47건
  ℹ️  api_endpoint: 이번 키트 14건 / 스코프 밖 188건
  ℹ️  erd: 이번 키트 2건 / 스코프 밖 21건
  ℹ️  diagram_sequence: 이번 키트 5건 / 스코프 밖 21건 (그중 domain_id 없음 12건)
  ℹ️  screen_spec: 이번 키트 10건 / 스코프 밖 27건
  ℹ️  use_case: 이번 키트 4건 / 스코프 밖 26건 (그중 domain_id 없음 2건)
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  ℹ️  acceptance: 이번 키트 11건 / 스코프 밖 113건 (그중 domain_id 없음 40건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 7건 / 스코프 밖 41건 (그중 domain_id 없음 7건)
  ℹ️  nfr: 이번 키트 14건 / 스코프 밖 1건 (그중 domain_id 없음 1건)
  ℹ️  feature: 이번 키트 2건 / 스코프 밖 8건 (그중 domain_id 없음 7건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[API-194]] (prev v8)
- CHANGED [[API-223]] (prev v4)
- CHANGED [[SEQ-018]] (prev v4)
- CHANGED [[DFEAT-045]] (prev v18)
- CHANGED [[SCREEN-025]] (prev v44)
- CHANGED [[UC-030]] (prev v12)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-006]] | acceptance | 9 | UNCHANGED |
| [[AC-055]] | acceptance | 3 | UNCHANGED |
| [[AC-071]] | acceptance | 5 | UNCHANGED |
| [[AC-072]] | acceptance | 9 | UNCHANGED |
| [[AC-073]] | acceptance | 5 | UNCHANGED |
| [[AC-074]] | acceptance | 2 | UNCHANGED |
| [[AC-075]] | acceptance | 4 | UNCHANGED |
| [[AC-076]] | acceptance | 2 | UNCHANGED |
| [[AC-077]] | acceptance | 3 | UNCHANGED |
| [[AC-078]] | acceptance | 1 | UNCHANGED |
| [[AC-122]] | acceptance | 1 | UNCHANGED |
| [[ADR-006]] | adr | 4 | UNCHANGED |
| [[ADR-007]] | adr | 3 | UNCHANGED |
| [[ADR-039]] | adr | 6 | UNCHANGED |
| [[ADR-046]] | adr | 8 | UNCHANGED |
| [[ADR-051]] | adr | 5 | UNCHANGED |
| [[ADR-053]] | adr | 1 | UNCHANGED |
| [[ADR-055]] | adr | 3 | UNCHANGED |
| [[API-004]] | api_endpoint | 8 | UNCHANGED |
| [[API-068]] | api_endpoint | 4 | UNCHANGED |
| [[API-069]] | api_endpoint | 9 | UNCHANGED |
| [[API-090]] | api_endpoint | 4 | UNCHANGED |
| [[API-118]] | api_endpoint | 2 | UNCHANGED |
| [[API-141]] | api_endpoint | 2 | UNCHANGED |
| [[API-152]] | api_endpoint | 8 | UNCHANGED |
| [[API-153]] | api_endpoint | 4 | UNCHANGED |
| [[API-158]] | api_endpoint | 5 | UNCHANGED |
| [[API-193]] | api_endpoint | 5 | UNCHANGED |
| [[API-194]] | api_endpoint | 9 | CHANGED |
| [[API-219]] | api_endpoint | 3 | UNCHANGED |
| [[API-220]] | api_endpoint | 3 | UNCHANGED |
| [[API-223]] | api_endpoint | 5 | CHANGED |
| [[CDIAG-012]] | class_diagram | 5 | UNCHANGED |
| [[CMP-011]] | diagram_c4_component | 9 | UNCHANGED |
| [[DFEAT-045]] | domain_feature | 19 | CHANGED |
| [[DOMAIN-014]] | domain | 6 | UNCHANGED |
| [[ERD-016]] | erd | 14 | UNCHANGED |
| [[ERD-033]] | erd | 2 | UNCHANGED |
| [[EXTSYS-002]] | external_system | 12 | UNCHANGED |
| [[FEAT-007]] | feature | 6 | UNCHANGED |
| [[FEAT-009]] | feature | 3 | UNCHANGED |
| [[INT-002]] | integration_point | 17 | UNCHANGED |
| [[INT-004]] | integration_point | 11 | UNCHANGED |
| [[INT-005]] | integration_point | 7 | UNCHANGED |
| [[INT-007]] | integration_point | 11 | UNCHANGED |
| [[INTSPEC-003]] | integration_spec | 12 | UNCHANGED |
| [[NAV-001]] | navigation_tree | 26 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 4 | UNCHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 5 | UNCHANGED |
| [[NFR-012]] | nfr | 4 | UNCHANGED |
| [[NFR-013]] | nfr | 9 | UNCHANGED |
| [[NFR-014]] | nfr | 3 | UNCHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 9 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 8 | UNCHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[REQ-008]] | requirement | 7 | UNCHANGED |
| [[ROLE-001]] | permission_role | 13 | UNCHANGED |
| [[ROLE-002]] | permission_role | 9 | UNCHANGED |
| [[ROLE-003]] | permission_role | 10 | UNCHANGED |
| [[ROLE-004]] | permission_role | 4 | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 102 | UNCHANGED |
| [[SCREEN-008]] | screen_spec | 47 | UNCHANGED |
| [[SCREEN-024]] | screen_spec | 32 | UNCHANGED |
| [[SCREEN-025]] | screen_spec | 46 | CHANGED |
| [[SCREEN-027]] | screen_spec | 42 | UNCHANGED |
| [[SCREEN-038]] | screen_spec | 14 | UNCHANGED |
| [[SCREEN-040]] | screen_spec | 9 | UNCHANGED |
| [[SCREEN-041]] | screen_spec | 10 | UNCHANGED |
| [[SCREEN-042]] | screen_spec | 8 | UNCHANGED |
| [[SCREEN-043]] | screen_spec | 7 | UNCHANGED |
| [[SD-015]] | screen_design | 9 | UNCHANGED |
| [[SD-034]] | screen_design | 6 | UNCHANGED |
| [[SD-035]] | screen_design | 4 | UNCHANGED |
| [[SD-036]] | screen_design | 4 | UNCHANGED |
| [[SD-037]] | screen_design | 5 | UNCHANGED |
| [[SEQ-007]] | diagram_sequence | 6 | UNCHANGED |
| [[SEQ-013]] | diagram_sequence | 6 | UNCHANGED |
| [[SEQ-018]] | diagram_sequence | 5 | CHANGED |
| [[SEQ-024]] | diagram_sequence | 3 | UNCHANGED |
| [[SEQ-025]] | diagram_sequence | 5 | UNCHANGED |
| [[SHELL-001]] | app_shell | 11 | UNCHANGED |
| [[UC-006]] | use_case | 9 | UNCHANGED |
| [[UC-013]] | use_case | 9 | UNCHANGED |
| [[UC-030]] | use_case | 13 | CHANGED |
| [[UC-031]] | use_case | 12 | UNCHANGED |
