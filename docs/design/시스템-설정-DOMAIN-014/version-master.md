# Version Master — DOMAIN-014

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-014 |
| Last sync | 2026-09-02T10:52:40.466Z |
| Mode | SYNC — NEW 0 / CHANGED 23 / UNCHANGED 62 |
| 출력 루트 | docs/design/시스템-설정-DOMAIN-014 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-014 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 25건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 1건 / 스코프 밖 47건
  ℹ️  api_endpoint: 이번 키트 14건 / 스코프 밖 206건
  ℹ️  erd: 이번 키트 2건 / 스코프 밖 21건
  ℹ️  diagram_sequence: 이번 키트 5건 / 스코프 밖 21건 (그중 domain_id 없음 12건)
  ℹ️  screen_spec: 이번 키트 10건 / 스코프 밖 28건
  ℹ️  use_case: 이번 키트 4건 / 스코프 밖 31건 (그중 domain_id 없음 3건)
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  ℹ️  acceptance: 이번 키트 3건 / 스코프 밖 77건 (그중 domain_id 없음 2건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 8건 / 스코프 밖 44건 (그중 domain_id 없음 8건)
  ℹ️  feature: 이번 키트 2건 / 스코프 밖 8건 (그중 domain_id 없음 7건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[ADR-046]] (prev v10)
- CHANGED [[ADR-055]] (prev v3)
- CHANGED [[ADR-057]] (prev v5)
- CHANGED [[API-004]] (prev v8)
- CHANGED [[API-152]] (prev v10)
- CHANGED [[API-153]] (prev v5)
- CHANGED [[API-158]] (prev v7)
- CHANGED [[API-194]] (prev v11)
- CHANGED [[API-223]] (prev v6)
- CHANGED [[CDIAG-012]] (prev v5)
- CHANGED [[SEQ-018]] (prev v5)
- CHANGED [[DFEAT-045]] (prev v20)
- CHANGED [[NFR-009]] (prev v4)
- CHANGED [[NFR-017]] (prev v9)
- CHANGED [[NFR-020]] (prev v9)
- CHANGED [[ROLE-001]] (prev v13)
- CHANGED [[ROLE-003]] (prev v12)
- CHANGED [[ROLE-004]] (prev v4)
- CHANGED [[SD-036]] (prev v4)
- CHANGED [[SCREEN-024]] (prev v32)
- CHANGED [[SCREEN-025]] (prev v47)
- CHANGED [[SCREEN-042]] (prev v8)
- CHANGED [[UC-030]] (prev v15)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-1072]] | acceptance | 9 | UNCHANGED |
| [[AC-1073]] | acceptance | 5 | UNCHANGED |
| [[AC-1074]] | acceptance | 5 | UNCHANGED |
| [[ADR-006]] | adr | 4 | UNCHANGED |
| [[ADR-007]] | adr | 3 | UNCHANGED |
| [[ADR-039]] | adr | 6 | UNCHANGED |
| [[ADR-046]] | adr | 13 | CHANGED |
| [[ADR-051]] | adr | 5 | UNCHANGED |
| [[ADR-053]] | adr | 3 | UNCHANGED |
| [[ADR-055]] | adr | 5 | CHANGED |
| [[ADR-057]] | adr | 7 | CHANGED |
| [[API-004]] | api_endpoint | 10 | CHANGED |
| [[API-068]] | api_endpoint | 4 | UNCHANGED |
| [[API-069]] | api_endpoint | 9 | UNCHANGED |
| [[API-090]] | api_endpoint | 4 | UNCHANGED |
| [[API-118]] | api_endpoint | 2 | UNCHANGED |
| [[API-141]] | api_endpoint | 2 | UNCHANGED |
| [[API-152]] | api_endpoint | 11 | CHANGED |
| [[API-153]] | api_endpoint | 6 | CHANGED |
| [[API-158]] | api_endpoint | 8 | CHANGED |
| [[API-193]] | api_endpoint | 5 | UNCHANGED |
| [[API-194]] | api_endpoint | 12 | CHANGED |
| [[API-219]] | api_endpoint | 3 | UNCHANGED |
| [[API-220]] | api_endpoint | 3 | UNCHANGED |
| [[API-223]] | api_endpoint | 7 | CHANGED |
| [[CDIAG-012]] | class_diagram | 6 | CHANGED |
| [[CMP-011]] | diagram_c4_component | 10 | UNCHANGED |
| [[DFEAT-045]] | domain_feature | 21 | CHANGED |
| [[DOMAIN-014]] | domain | 6 | UNCHANGED |
| [[ERD-016]] | erd | 14 | UNCHANGED |
| [[ERD-033]] | erd | 2 | UNCHANGED |
| [[EXTSYS-002]] | external_system | 12 | UNCHANGED |
| [[FEAT-007]] | feature | 6 | UNCHANGED |
| [[FEAT-009]] | feature | 3 | UNCHANGED |
| [[INT-002]] | integration_point | 18 | UNCHANGED |
| [[INT-004]] | integration_point | 11 | UNCHANGED |
| [[INT-005]] | integration_point | 7 | UNCHANGED |
| [[INT-007]] | integration_point | 11 | UNCHANGED |
| [[INTSPEC-003]] | integration_spec | 12 | UNCHANGED |
| [[NAV-001]] | navigation_tree | 26 | UNCHANGED |
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
| [[REQ-008]] | requirement | 7 | UNCHANGED |
| [[ROLE-001]] | permission_role | 14 | CHANGED |
| [[ROLE-002]] | permission_role | 9 | UNCHANGED |
| [[ROLE-003]] | permission_role | 14 | CHANGED |
| [[ROLE-004]] | permission_role | 5 | CHANGED |
| [[SCREEN-005]] | screen_spec | 102 | UNCHANGED |
| [[SCREEN-008]] | screen_spec | 48 | UNCHANGED |
| [[SCREEN-024]] | screen_spec | 33 | CHANGED |
| [[SCREEN-025]] | screen_spec | 49 | CHANGED |
| [[SCREEN-027]] | screen_spec | 51 | UNCHANGED |
| [[SCREEN-038]] | screen_spec | 14 | UNCHANGED |
| [[SCREEN-040]] | screen_spec | 9 | UNCHANGED |
| [[SCREEN-041]] | screen_spec | 10 | UNCHANGED |
| [[SCREEN-042]] | screen_spec | 10 | CHANGED |
| [[SCREEN-043]] | screen_spec | 7 | UNCHANGED |
| [[SD-015]] | screen_design | 9 | UNCHANGED |
| [[SD-034]] | screen_design | 6 | UNCHANGED |
| [[SD-035]] | screen_design | 4 | UNCHANGED |
| [[SD-036]] | screen_design | 5 | CHANGED |
| [[SD-037]] | screen_design | 5 | UNCHANGED |
| [[SEQ-007]] | diagram_sequence | 7 | UNCHANGED |
| [[SEQ-013]] | diagram_sequence | 7 | UNCHANGED |
| [[SEQ-018]] | diagram_sequence | 8 | CHANGED |
| [[SEQ-024]] | diagram_sequence | 3 | UNCHANGED |
| [[SEQ-025]] | diagram_sequence | 5 | UNCHANGED |
| [[SHELL-001]] | app_shell | 11 | UNCHANGED |
| [[UC-006]] | use_case | 11 | UNCHANGED |
| [[UC-013]] | use_case | 12 | UNCHANGED |
| [[UC-030]] | use_case | 16 | CHANGED |
| [[UC-031]] | use_case | 15 | UNCHANGED |
