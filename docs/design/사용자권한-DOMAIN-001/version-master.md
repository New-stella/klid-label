# Version Master — DOMAIN-001

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-001 |
| Last sync | 2026-09-15T15:36:15.731Z |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 137 |
| 출력 루트 | docs/design/사용자권한-DOMAIN-001 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-001 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 54건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 3건 / 스코프 밖 46건
  ℹ️  api_endpoint: 이번 키트 23건 / 스코프 밖 214건
  ℹ️  erd: 이번 키트 2건 / 스코프 밖 23건
  ℹ️  diagram_sequence: 이번 키트 3건 / 스코프 밖 33건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 10건 / 스코프 밖 29건
  ℹ️  use_case: 이번 키트 2건 / 스코프 밖 34건
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  ℹ️  acceptance: 이번 키트 18건 / 스코프 밖 97건 (그중 domain_id 없음 8건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 13건 / 스코프 밖 49건 (그중 domain_id 없음 11건)
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
| [[AC-1016]] | acceptance | 17 | UNCHANGED |
| [[AC-1017]] | acceptance | 12 | UNCHANGED |
| [[AC-1018]] | acceptance | 7 | UNCHANGED |
| [[AC-1019]] | acceptance | 6 | UNCHANGED |
| [[AC-1072]] | acceptance | 13 | UNCHANGED |
| [[AC-1073]] | acceptance | 5 | UNCHANGED |
| [[AC-1074]] | acceptance | 7 | UNCHANGED |
| [[AC-1095]] | acceptance | 1 | UNCHANGED |
| [[AC-1096]] | acceptance | 1 | UNCHANGED |
| [[AC-1097]] | acceptance | 1 | UNCHANGED |
| [[AC-1098]] | acceptance | 5 | UNCHANGED |
| [[AC-1102]] | acceptance | 3 | UNCHANGED |
| [[AC-1103]] | acceptance | 5 | UNCHANGED |
| [[AC-1104]] | acceptance | 3 | UNCHANGED |
| [[AC-1105]] | acceptance | 8 | UNCHANGED |
| [[AC-1106]] | acceptance | 9 | UNCHANGED |
| [[AC-1107]] | acceptance | 1 | UNCHANGED |
| [[AC-1108]] | acceptance | 3 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-004]] | adr | 4 | UNCHANGED |
| [[ADR-007]] | adr | 3 | UNCHANGED |
| [[ADR-012]] | adr | 23 | UNCHANGED |
| [[ADR-013]] | adr | 24 | UNCHANGED |
| [[ADR-021]] | adr | 5 | UNCHANGED |
| [[ADR-039]] | adr | 7 | UNCHANGED |
| [[ADR-042]] | adr | 7 | UNCHANGED |
| [[ADR-046]] | adr | 18 | UNCHANGED |
| [[ADR-055]] | adr | 6 | UNCHANGED |
| [[ADR-058]] | adr | 10 | UNCHANGED |
| [[ADR-063]] | adr | 9 | UNCHANGED |
| [[ADR-068]] | adr | 3 | UNCHANGED |
| [[API-001]] | api_endpoint | 10 | UNCHANGED |
| [[API-002]] | api_endpoint | 2 | UNCHANGED |
| [[API-003]] | api_endpoint | 6 | UNCHANGED |
| [[API-004]] | api_endpoint | 10 | UNCHANGED |
| [[API-005]] | api_endpoint | 6 | UNCHANGED |
| [[API-006]] | api_endpoint | 13 | UNCHANGED |
| [[API-007]] | api_endpoint | 17 | UNCHANGED |
| [[API-153]] | api_endpoint | 6 | UNCHANGED |
| [[API-194]] | api_endpoint | 12 | UNCHANGED |
| [[API-223]] | api_endpoint | 8 | UNCHANGED |
| [[API-242]] | api_endpoint | 3 | UNCHANGED |
| [[API-243]] | api_endpoint | 8 | UNCHANGED |
| [[API-244]] | api_endpoint | 7 | UNCHANGED |
| [[API-245]] | api_endpoint | 2 | UNCHANGED |
| [[API-246]] | api_endpoint | 4 | UNCHANGED |
| [[API-247]] | api_endpoint | 5 | UNCHANGED |
| [[API-248]] | api_endpoint | 3 | UNCHANGED |
| [[API-249]] | api_endpoint | 3 | UNCHANGED |
| [[API-254]] | api_endpoint | 5 | UNCHANGED |
| [[API-255]] | api_endpoint | 4 | UNCHANGED |
| [[API-256]] | api_endpoint | 2 | UNCHANGED |
| [[API-257]] | api_endpoint | 4 | UNCHANGED |
| [[API-258]] | api_endpoint | 3 | UNCHANGED |
| [[CDIAG-008]] | class_diagram | 10 | UNCHANGED |
| [[CDIAG-016]] | class_diagram | 1 | UNCHANGED |
| [[CDIAG-028]] | class_diagram | 1 | UNCHANGED |
| [[CDIAG-035]] | class_diagram | 1 | UNCHANGED |
| [[CMP-012]] | diagram_c4_component | 6 | UNCHANGED |
| [[DFEAT-001]] | domain_feature | 10 | UNCHANGED |
| [[DFEAT-002]] | domain_feature | 8 | UNCHANGED |
| [[DFEAT-003]] | domain_feature | 12 | UNCHANGED |
| [[DOMAIN-001]] | domain | 15 | UNCHANGED |
| [[ERD-029]] | erd | 5 | UNCHANGED |
| [[ERD-035]] | erd | 3 | UNCHANGED |
| [[EXTSYS-005]] | external_system | 18 | UNCHANGED |
| [[INT-007]] | integration_point | 12 | UNCHANGED |
| [[INT-013]] | integration_point | 31 | UNCHANGED |
| [[INT-014]] | integration_point | 16 | UNCHANGED |
| [[INT-015]] | integration_point | 8 | UNCHANGED |
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
| [[SCREEN-001]] | screen_spec | 23 | UNCHANGED |
| [[SCREEN-002]] | screen_spec | 34 | UNCHANGED |
| [[SCREEN-003]] | screen_spec | 16 | UNCHANGED |
| [[SCREEN-004]] | screen_spec | 18 | UNCHANGED |
| [[SCREEN-012]] | screen_spec | 53 | UNCHANGED |
| [[SCREEN-020]] | screen_spec | 32 | UNCHANGED |
| [[SCREEN-024]] | screen_spec | 35 | UNCHANGED |
| [[SCREEN-040]] | screen_spec | 9 | UNCHANGED |
| [[SCREEN-041]] | screen_spec | 10 | UNCHANGED |
| [[SCREEN-046]] | screen_spec | 3 | UNCHANGED |
| [[SD-009]] | screen_design | 13 | UNCHANGED |
| [[SD-017]] | screen_design | 6 | UNCHANGED |
| [[SD-018]] | screen_design | 8 | UNCHANGED |
| [[SD-019]] | screen_design | 3 | UNCHANGED |
| [[SD-020]] | screen_design | 5 | UNCHANGED |
| [[SD-034]] | screen_design | 6 | UNCHANGED |
| [[SD-037]] | screen_design | 5 | UNCHANGED |
| [[SEQ-018]] | diagram_sequence | 9 | UNCHANGED |
| [[SEQ-025]] | diagram_sequence | 10 | UNCHANGED |
| [[SEQ-034]] | diagram_sequence | 23 | UNCHANGED |
| [[UC-030]] | use_case | 17 | UNCHANGED |
| [[UC-041]] | use_case | 29 | UNCHANGED |
