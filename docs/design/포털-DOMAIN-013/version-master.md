# Version Master — DOMAIN-013

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-013 |
| Last sync | 2026-09-01T12:37:59.719Z |
| Mode | SYNC — NEW 0 / CHANGED 24 / UNCHANGED 55 |
| 출력 루트 | 포털-DOMAIN-013 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-013 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 22건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 4건 / 스코프 밖 44건
  ℹ️  api_endpoint: 이번 키트 23건 / 스코프 밖 181건
  ℹ️  erd: 이번 키트 3건 / 스코프 밖 20건
  ℹ️  diagram_sequence: 이번 키트 2건 / 스코프 밖 24건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 4건 / 스코프 밖 33건
  ℹ️  use_case: 이번 키트 2건 / 스코프 밖 33건 (그중 domain_id 없음 3건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 4건 / 스코프 밖 71건 (그중 domain_id 없음 2건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 4건 / 스코프 밖 46건 (그중 domain_id 없음 8건)
  🚨 feature: 이번 키트 0건 / 프로젝트 전역 10건 (그중 domain_id 없음 9건) — 전량 누락
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[AC-1068]] (prev v6)
- CHANGED [[AC-1070]] (prev v6)
- CHANGED [[AC-1071]] (prev v5)
- CHANGED [[ADR-013]] (prev v11)
- CHANGED [[API-115]] (prev v3)
- CHANGED [[API-139]] (prev v3)
- CHANGED [[API-142]] (prev v4)
- CHANGED [[CDIAG-011]] (prev v12)
- CHANGED [[CMP-009]] (prev v15)
- CHANGED [[SEQ-016]] (prev v5)
- CHANGED [[SEQ-019]] (prev v5)
- CHANGED [[DOMAIN-013]] (prev v11)
- CHANGED [[DFEAT-043]] (prev v15)
- CHANGED [[DFEAT-044]] (prev v12)
- CHANGED [[DFEAT-053]] (prev v8)
- CHANGED [[DFEAT-055]] (prev v6)
- CHANGED [[INT-013]] (prev v13)
- CHANGED [[NAV-002]] (prev v8)
- CHANGED [[ROLE-003]] (prev v10)
- CHANGED [[SCREEN-028]] (prev v26)
- CHANGED [[SCREEN-029]] (prev v41)
- CHANGED [[SCREEN-033]] (prev v23)
- CHANGED [[UC-024]] (prev v22)
- CHANGED [[UC-027]] (prev v18)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-1068]] | acceptance | 7 | CHANGED |
| [[AC-1069]] | acceptance | 8 | UNCHANGED |
| [[AC-1070]] | acceptance | 7 | CHANGED |
| [[AC-1071]] | acceptance | 6 | CHANGED |
| [[ADR-012]] | adr | 10 | UNCHANGED |
| [[ADR-013]] | adr | 13 | CHANGED |
| [[ADR-026]] | adr | 5 | UNCHANGED |
| [[ADR-055]] | adr | 3 | UNCHANGED |
| [[API-024]] | api_endpoint | 6 | UNCHANGED |
| [[API-081]] | api_endpoint | 7 | UNCHANGED |
| [[API-082]] | api_endpoint | 9 | UNCHANGED |
| [[API-083]] | api_endpoint | 6 | UNCHANGED |
| [[API-110]] | api_endpoint | 4 | UNCHANGED |
| [[API-111]] | api_endpoint | 3 | UNCHANGED |
| [[API-115]] | api_endpoint | 4 | CHANGED |
| [[API-139]] | api_endpoint | 4 | CHANGED |
| [[API-140]] | api_endpoint | 5 | UNCHANGED |
| [[API-142]] | api_endpoint | 5 | CHANGED |
| [[API-147]] | api_endpoint | 2 | UNCHANGED |
| [[API-149]] | api_endpoint | 3 | UNCHANGED |
| [[API-151]] | api_endpoint | 2 | UNCHANGED |
| [[API-154]] | api_endpoint | 4 | UNCHANGED |
| [[API-155]] | api_endpoint | 2 | UNCHANGED |
| [[API-157]] | api_endpoint | 3 | UNCHANGED |
| [[API-159]] | api_endpoint | 4 | UNCHANGED |
| [[API-161]] | api_endpoint | 3 | UNCHANGED |
| [[API-163]] | api_endpoint | 3 | UNCHANGED |
| [[API-166]] | api_endpoint | 3 | UNCHANGED |
| [[API-169]] | api_endpoint | 6 | UNCHANGED |
| [[API-171]] | api_endpoint | 3 | UNCHANGED |
| [[API-203]] | api_endpoint | 6 | UNCHANGED |
| [[CDIAG-011]] | class_diagram | 13 | CHANGED |
| [[CMP-009]] | diagram_c4_component | 16 | CHANGED |
| [[DFEAT-043]] | domain_feature | 16 | CHANGED |
| [[DFEAT-044]] | domain_feature | 13 | CHANGED |
| [[DFEAT-053]] | domain_feature | 9 | CHANGED |
| [[DFEAT-055]] | domain_feature | 7 | CHANGED |
| [[DOMAIN-013]] | domain | 14 | CHANGED |
| [[ERD-018]] | erd | 11 | UNCHANGED |
| [[ERD-026]] | erd | 6 | UNCHANGED |
| [[ERD-028]] | erd | 2 | UNCHANGED |
| [[EVT-012]] | domain_event | 3 | UNCHANGED |
| [[EXTSYS-006]] | external_system | 11 | UNCHANGED |
| [[INT-009]] | integration_point | 12 | UNCHANGED |
| [[INT-013]] | integration_point | 16 | CHANGED |
| [[NAV-002]] | navigation_tree | 9 | CHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 4 | UNCHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 6 | UNCHANGED |
| [[NFR-012]] | nfr | 5 | UNCHANGED |
| [[NFR-013]] | nfr | 10 | UNCHANGED |
| [[NFR-014]] | nfr | 4 | UNCHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 9 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 9 | UNCHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[NFR-022]] | nfr | 2 | UNCHANGED |
| [[ROLE-001]] | permission_role | 13 | UNCHANGED |
| [[ROLE-002]] | permission_role | 9 | UNCHANGED |
| [[ROLE-003]] | permission_role | 12 | CHANGED |
| [[ROLE-004]] | permission_role | 4 | UNCHANGED |
| [[SCREEN-028]] | screen_spec | 29 | CHANGED |
| [[SCREEN-029]] | screen_spec | 42 | CHANGED |
| [[SCREEN-033]] | screen_spec | 24 | CHANGED |
| [[SCREEN-034]] | screen_spec | 26 | UNCHANGED |
| [[SD-024]] | screen_design | 6 | UNCHANGED |
| [[SD-025]] | screen_design | 4 | UNCHANGED |
| [[SD-026]] | screen_design | 7 | UNCHANGED |
| [[SD-027]] | screen_design | 9 | UNCHANGED |
| [[SEQ-016]] | diagram_sequence | 6 | CHANGED |
| [[SEQ-019]] | diagram_sequence | 6 | CHANGED |
| [[SHELL-002]] | app_shell | 5 | UNCHANGED |
| [[TEST-005]] | test_scenario | 12 | UNCHANGED |
| [[UC-024]] | use_case | 23 | CHANGED |
| [[UC-027]] | use_case | 19 | CHANGED |
