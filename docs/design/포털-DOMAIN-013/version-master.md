# Version Master — DOMAIN-013

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-013 |
| Last sync | 2026-08-26T04:28:12.076Z |
| Mode | SYNC — NEW 0 / CHANGED 11 / UNCHANGED 69 |
| 출력 루트 | docs/design/포털-DOMAIN-013 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-013 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 18건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 4건 / 스코프 밖 44건
  ℹ️  api_endpoint: 이번 키트 23건 / 스코프 밖 178건
  ℹ️  erd: 이번 키트 3건 / 스코프 밖 20건
  ℹ️  diagram_sequence: 이번 키트 2건 / 스코프 밖 24건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 4건 / 스코프 밖 29건
  ℹ️  use_case: 이번 키트 2건 / 스코프 밖 28건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 10건 / 스코프 밖 107건 (그중 domain_id 없음 37건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 2건 / 스코프 밖 47건 (그중 domain_id 없음 8건)
  🚨 feature: 이번 키트 0건 / 프로젝트 전역 10건 (그중 domain_id 없음 9건) — 전량 누락
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[ADR-013]] (prev v8)
- CHANGED [[API-203]] (prev v3)
- CHANGED [[DOMAIN-013]] (prev v7)
- CHANGED [[DFEAT-043]] (prev v11)
- CHANGED [[DFEAT-044]] (prev v10)
- CHANGED [[DFEAT-053]] (prev v7)
- CHANGED [[ERD-018]] (prev v10)
- CHANGED [[ROLE-003]] (prev v7)
- CHANGED [[SCREEN-029]] (prev v39)
- CHANGED [[UC-024]] (prev v16)
- CHANGED [[UC-027]] (prev v12)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-032]] | acceptance | 3 | UNCHANGED |
| [[AC-033]] | acceptance | 6 | UNCHANGED |
| [[AC-034]] | acceptance | 4 | UNCHANGED |
| [[AC-035]] | acceptance | 4 | UNCHANGED |
| [[AC-036]] | acceptance | 6 | UNCHANGED |
| [[AC-037]] | acceptance | 4 | UNCHANGED |
| [[AC-095]] | acceptance | 2 | UNCHANGED |
| [[AC-096]] | acceptance | 2 | UNCHANGED |
| [[AC-097]] | acceptance | 2 | UNCHANGED |
| [[AC-098]] | acceptance | 2 | UNCHANGED |
| [[ADR-003]] | adr | 2 | UNCHANGED |
| [[ADR-013]] | adr | 10 | CHANGED |
| [[API-024]] | api_endpoint | 6 | UNCHANGED |
| [[API-081]] | api_endpoint | 6 | UNCHANGED |
| [[API-082]] | api_endpoint | 7 | UNCHANGED |
| [[API-083]] | api_endpoint | 5 | UNCHANGED |
| [[API-110]] | api_endpoint | 3 | UNCHANGED |
| [[API-111]] | api_endpoint | 3 | UNCHANGED |
| [[API-115]] | api_endpoint | 3 | UNCHANGED |
| [[API-139]] | api_endpoint | 3 | UNCHANGED |
| [[API-140]] | api_endpoint | 5 | UNCHANGED |
| [[API-142]] | api_endpoint | 4 | UNCHANGED |
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
| [[API-203]] | api_endpoint | 4 | CHANGED |
| [[CDIAG-011]] | class_diagram | 9 | UNCHANGED |
| [[CMP-009]] | diagram_c4_component | 13 | UNCHANGED |
| [[DFEAT-043]] | domain_feature | 12 | CHANGED |
| [[DFEAT-044]] | domain_feature | 11 | CHANGED |
| [[DFEAT-053]] | domain_feature | 8 | CHANGED |
| [[DFEAT-055]] | domain_feature | 6 | UNCHANGED |
| [[DOMAIN-013]] | domain | 8 | CHANGED |
| [[ERD-018]] | erd | 11 | CHANGED |
| [[ERD-026]] | erd | 4 | UNCHANGED |
| [[ERD-028]] | erd | 2 | UNCHANGED |
| [[EVT-012]] | domain_event | 3 | UNCHANGED |
| [[EXTSYS-006]] | external_system | 4 | UNCHANGED |
| [[INT-009]] | integration_point | 4 | UNCHANGED |
| [[NAV-002]] | navigation_tree | 5 | UNCHANGED |
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
| [[ROLE-001]] | permission_role | 10 | UNCHANGED |
| [[ROLE-002]] | permission_role | 7 | UNCHANGED |
| [[ROLE-003]] | permission_role | 8 | CHANGED |
| [[SCREEN-028]] | screen_spec | 22 | UNCHANGED |
| [[SCREEN-029]] | screen_spec | 39 | CHANGED |
| [[SCREEN-033]] | screen_spec | 18 | UNCHANGED |
| [[SCREEN-034]] | screen_spec | 22 | UNCHANGED |
| [[SD-024]] | screen_design | 5 | UNCHANGED |
| [[SD-025]] | screen_design | 4 | UNCHANGED |
| [[SD-026]] | screen_design | 2 | UNCHANGED |
| [[SD-027]] | screen_design | 3 | UNCHANGED |
| [[SEQ-016]] | diagram_sequence | 3 | UNCHANGED |
| [[SEQ-019]] | diagram_sequence | 2 | UNCHANGED |
| [[SHELL-002]] | app_shell | 5 | UNCHANGED |
| [[TEST-005]] | test_scenario | 11 | UNCHANGED |
| [[UC-024]] | use_case | 17 | CHANGED |
| [[UC-027]] | use_case | 13 | CHANGED |
