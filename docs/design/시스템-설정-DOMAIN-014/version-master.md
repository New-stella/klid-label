# Version Master — DOMAIN-014

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-014 |
| Last sync | 2026-09-15T15:36:12.679Z |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 137 |
| 출력 루트 | docs/design/시스템-설정-DOMAIN-014 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-014 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 24건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 1건 / 스코프 밖 48건
  ℹ️  api_endpoint: 이번 키트 20건 / 스코프 밖 217건
  ℹ️  erd: 이번 키트 2건 / 스코프 밖 23건
  ℹ️  diagram_sequence: 이번 키트 6건 / 스코프 밖 30건 (그중 domain_id 없음 12건)
  ℹ️  screen_spec: 이번 키트 10건 / 스코프 밖 29건
  ℹ️  use_case: 이번 키트 4건 / 스코프 밖 32건
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  ℹ️  acceptance: 이번 키트 13건 / 스코프 밖 102건 (그중 domain_id 없음 12건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 10건 / 스코프 밖 52건 (그중 domain_id 없음 11건)
  ℹ️  feature: 이번 키트 3건 / 스코프 밖 11건 (그중 domain_id 없음 10건)
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
| [[AC-1022]] | acceptance | 7 | UNCHANGED |
| [[AC-1023]] | acceptance | 6 | UNCHANGED |
| [[AC-1028]] | acceptance | 4 | UNCHANGED |
| [[AC-1029]] | acceptance | 5 | UNCHANGED |
| [[AC-1072]] | acceptance | 13 | UNCHANGED |
| [[AC-1073]] | acceptance | 5 | UNCHANGED |
| [[AC-1074]] | acceptance | 7 | UNCHANGED |
| [[AC-1087]] | acceptance | 2 | UNCHANGED |
| [[AC-1088]] | acceptance | 2 | UNCHANGED |
| [[AC-1089]] | acceptance | 2 | UNCHANGED |
| [[AC-1090]] | acceptance | 2 | UNCHANGED |
| [[AC-1091]] | acceptance | 2 | UNCHANGED |
| [[AC-1092]] | acceptance | 2 | UNCHANGED |
| [[ADR-006]] | adr | 6 | UNCHANGED |
| [[ADR-007]] | adr | 3 | UNCHANGED |
| [[ADR-013]] | adr | 24 | UNCHANGED |
| [[ADR-039]] | adr | 7 | UNCHANGED |
| [[ADR-046]] | adr | 18 | UNCHANGED |
| [[ADR-051]] | adr | 8 | UNCHANGED |
| [[ADR-053]] | adr | 3 | UNCHANGED |
| [[ADR-055]] | adr | 6 | UNCHANGED |
| [[ADR-057]] | adr | 22 | UNCHANGED |
| [[ADR-062]] | adr | 8 | UNCHANGED |
| [[API-004]] | api_endpoint | 10 | UNCHANGED |
| [[API-068]] | api_endpoint | 4 | UNCHANGED |
| [[API-069]] | api_endpoint | 13 | UNCHANGED |
| [[API-090]] | api_endpoint | 4 | UNCHANGED |
| [[API-118]] | api_endpoint | 2 | UNCHANGED |
| [[API-141]] | api_endpoint | 2 | UNCHANGED |
| [[API-152]] | api_endpoint | 11 | UNCHANGED |
| [[API-153]] | api_endpoint | 6 | UNCHANGED |
| [[API-158]] | api_endpoint | 8 | UNCHANGED |
| [[API-193]] | api_endpoint | 6 | UNCHANGED |
| [[API-194]] | api_endpoint | 12 | UNCHANGED |
| [[API-219]] | api_endpoint | 4 | UNCHANGED |
| [[API-220]] | api_endpoint | 4 | UNCHANGED |
| [[API-223]] | api_endpoint | 8 | UNCHANGED |
| [[API-226]] | api_endpoint | 4 | UNCHANGED |
| [[API-227]] | api_endpoint | 5 | UNCHANGED |
| [[API-228]] | api_endpoint | 4 | UNCHANGED |
| [[API-229]] | api_endpoint | 5 | UNCHANGED |
| [[API-230]] | api_endpoint | 4 | UNCHANGED |
| [[API-256]] | api_endpoint | 2 | UNCHANGED |
| [[CDIAG-012]] | class_diagram | 6 | UNCHANGED |
| [[CDIAG-025]] | class_diagram | 1 | UNCHANGED |
| [[CDIAG-033]] | class_diagram | 1 | UNCHANGED |
| [[CMP-011]] | diagram_c4_component | 10 | UNCHANGED |
| [[DFEAT-045]] | domain_feature | 28 | UNCHANGED |
| [[DOMAIN-014]] | domain | 9 | UNCHANGED |
| [[ERD-016]] | erd | 14 | UNCHANGED |
| [[ERD-033]] | erd | 4 | UNCHANGED |
| [[EXTSYS-002]] | external_system | 20 | UNCHANGED |
| [[FEAT-005]] | feature | 12 | UNCHANGED |
| [[FEAT-007]] | feature | 6 | UNCHANGED |
| [[FEAT-009]] | feature | 3 | UNCHANGED |
| [[INT-002]] | integration_point | 24 | UNCHANGED |
| [[INT-003]] | integration_point | 29 | UNCHANGED |
| [[INT-004]] | integration_point | 16 | UNCHANGED |
| [[INT-005]] | integration_point | 10 | UNCHANGED |
| [[INT-006]] | integration_point | 20 | UNCHANGED |
| [[INT-007]] | integration_point | 12 | UNCHANGED |
| [[INT-015]] | integration_point | 8 | UNCHANGED |
| [[INTSPEC-003]] | integration_spec | 21 | UNCHANGED |
| [[INTSPEC-006]] | integration_spec | 7 | UNCHANGED |
| [[NAV-001]] | navigation_tree | 26 | UNCHANGED |
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
| [[REQ-008]] | requirement | 7 | UNCHANGED |
| [[ROLE-001]] | permission_role | 16 | UNCHANGED |
| [[ROLE-002]] | permission_role | 10 | UNCHANGED |
| [[ROLE-003]] | permission_role | 17 | UNCHANGED |
| [[ROLE-004]] | permission_role | 6 | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 118 | UNCHANGED |
| [[SCREEN-008]] | screen_spec | 52 | UNCHANGED |
| [[SCREEN-024]] | screen_spec | 35 | UNCHANGED |
| [[SCREEN-025]] | screen_spec | 49 | UNCHANGED |
| [[SCREEN-027]] | screen_spec | 54 | UNCHANGED |
| [[SCREEN-038]] | screen_spec | 15 | UNCHANGED |
| [[SCREEN-040]] | screen_spec | 9 | UNCHANGED |
| [[SCREEN-041]] | screen_spec | 10 | UNCHANGED |
| [[SCREEN-042]] | screen_spec | 27 | UNCHANGED |
| [[SCREEN-043]] | screen_spec | 7 | UNCHANGED |
| [[SD-015]] | screen_design | 9 | UNCHANGED |
| [[SD-034]] | screen_design | 6 | UNCHANGED |
| [[SD-035]] | screen_design | 4 | UNCHANGED |
| [[SD-036]] | screen_design | 16 | UNCHANGED |
| [[SD-037]] | screen_design | 5 | UNCHANGED |
| [[SEQ-007]] | diagram_sequence | 8 | UNCHANGED |
| [[SEQ-013]] | diagram_sequence | 8 | UNCHANGED |
| [[SEQ-018]] | diagram_sequence | 9 | UNCHANGED |
| [[SEQ-024]] | diagram_sequence | 3 | UNCHANGED |
| [[SEQ-025]] | diagram_sequence | 10 | UNCHANGED |
| [[SEQ-035]] | diagram_sequence | 7 | UNCHANGED |
| [[SHELL-001]] | app_shell | 18 | UNCHANGED |
| [[UC-006]] | use_case | 12 | UNCHANGED |
| [[UC-013]] | use_case | 13 | UNCHANGED |
| [[UC-030]] | use_case | 17 | UNCHANGED |
| [[UC-031]] | use_case | 21 | UNCHANGED |
