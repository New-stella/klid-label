# Version Master — DOMAIN-014

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-014 |
| Last sync | 2026-08-26T05:51:27.521Z |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 72 |
| 출력 루트 | docs/design/시스템-설정-DOMAIN-014 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-014 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 12건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 1건 / 스코프 밖 47건
  ℹ️  api_endpoint: 이번 키트 9건 / 스코프 밖 192건
  ℹ️  erd: 이번 키트 2건 / 스코프 밖 21건
  ℹ️  diagram_sequence: 이번 키트 4건 / 스코프 밖 22건 (그중 domain_id 없음 12건)
  ℹ️  screen_spec: 이번 키트 4건 / 스코프 밖 29건
  ℹ️  use_case: 이번 키트 3건 / 스코프 밖 27건 (그중 domain_id 없음 2건)
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  ℹ️  acceptance: 이번 키트 10건 / 스코프 밖 107건 (그중 domain_id 없음 36건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 7건 / 스코프 밖 42건 (그중 domain_id 없음 7건)
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
| [[AC-006]] | acceptance | 9 | UNCHANGED |
| [[AC-055]] | acceptance | 3 | UNCHANGED |
| [[AC-071]] | acceptance | 3 | UNCHANGED |
| [[AC-072]] | acceptance | 3 | UNCHANGED |
| [[AC-073]] | acceptance | 3 | UNCHANGED |
| [[AC-074]] | acceptance | 1 | UNCHANGED |
| [[AC-075]] | acceptance | 3 | UNCHANGED |
| [[AC-076]] | acceptance | 1 | UNCHANGED |
| [[AC-077]] | acceptance | 1 | UNCHANGED |
| [[AC-078]] | acceptance | 1 | UNCHANGED |
| [[ADR-003]] | adr | 2 | UNCHANGED |
| [[ADR-006]] | adr | 4 | UNCHANGED |
| [[ADR-007]] | adr | 3 | UNCHANGED |
| [[ADR-039]] | adr | 6 | UNCHANGED |
| [[ADR-046]] | adr | 1 | UNCHANGED |
| [[ADR-051]] | adr | 5 | UNCHANGED |
| [[ADR-053]] | adr | 1 | UNCHANGED |
| [[API-068]] | api_endpoint | 4 | UNCHANGED |
| [[API-069]] | api_endpoint | 8 | UNCHANGED |
| [[API-090]] | api_endpoint | 4 | UNCHANGED |
| [[API-118]] | api_endpoint | 2 | UNCHANGED |
| [[API-141]] | api_endpoint | 2 | UNCHANGED |
| [[API-193]] | api_endpoint | 5 | UNCHANGED |
| [[API-194]] | api_endpoint | 4 | UNCHANGED |
| [[API-219]] | api_endpoint | 3 | UNCHANGED |
| [[API-220]] | api_endpoint | 3 | UNCHANGED |
| [[CDIAG-012]] | class_diagram | 4 | UNCHANGED |
| [[CMP-011]] | diagram_c4_component | 7 | UNCHANGED |
| [[DFEAT-045]] | domain_feature | 15 | UNCHANGED |
| [[DOMAIN-014]] | domain | 5 | UNCHANGED |
| [[ERD-016]] | erd | 13 | UNCHANGED |
| [[ERD-033]] | erd | 2 | UNCHANGED |
| [[EXTSYS-002]] | external_system | 11 | UNCHANGED |
| [[FEAT-007]] | feature | 6 | UNCHANGED |
| [[FEAT-009]] | feature | 3 | UNCHANGED |
| [[INT-002]] | integration_point | 17 | UNCHANGED |
| [[INT-004]] | integration_point | 11 | UNCHANGED |
| [[INT-005]] | integration_point | 7 | UNCHANGED |
| [[INT-007]] | integration_point | 11 | UNCHANGED |
| [[INTSPEC-003]] | integration_spec | 12 | UNCHANGED |
| [[NAV-001]] | navigation_tree | 18 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 4 | UNCHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 5 | UNCHANGED |
| [[NFR-012]] | nfr | 4 | UNCHANGED |
| [[NFR-013]] | nfr | 4 | UNCHANGED |
| [[NFR-014]] | nfr | 3 | UNCHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 8 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 6 | UNCHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[REQ-008]] | requirement | 7 | UNCHANGED |
| [[ROLE-001]] | permission_role | 10 | UNCHANGED |
| [[ROLE-002]] | permission_role | 7 | UNCHANGED |
| [[ROLE-003]] | permission_role | 8 | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 100 | UNCHANGED |
| [[SCREEN-008]] | screen_spec | 45 | UNCHANGED |
| [[SCREEN-025]] | screen_spec | 39 | UNCHANGED |
| [[SCREEN-038]] | screen_spec | 14 | UNCHANGED |
| [[SD-015]] | screen_design | 4 | UNCHANGED |
| [[SEQ-007]] | diagram_sequence | 6 | UNCHANGED |
| [[SEQ-013]] | diagram_sequence | 6 | UNCHANGED |
| [[SEQ-024]] | diagram_sequence | 1 | UNCHANGED |
| [[SEQ-025]] | diagram_sequence | 1 | UNCHANGED |
| [[SHELL-001]] | app_shell | 10 | UNCHANGED |
| [[UC-006]] | use_case | 9 | UNCHANGED |
| [[UC-013]] | use_case | 9 | UNCHANGED |
| [[UC-031]] | use_case | 9 | UNCHANGED |
