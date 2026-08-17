# Version Master — DOMAIN-013

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-013 |
| Last sync | 2026-08-17T01:37:01.954Z |
| Mode | SYNC — NEW 2 / CHANGED 0 / UNCHANGED 74 |
| 출력 루트 | docs/design/포털-DOMAIN-013 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-013 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 4건 / 스코프 밖 40건
  ℹ️  api_endpoint: 이번 키트 23건 / 스코프 밖 159건
  ℹ️  erd: 이번 키트 3건 / 스코프 밖 17건
  ℹ️  diagram_sequence: 이번 키트 2건 / 스코프 밖 23건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 4건 / 스코프 밖 28건
  ℹ️  use_case: 이번 키트 2건 / 스코프 밖 24건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 6건 / 스코프 밖 28건 (그중 domain_id 없음 24건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 2건 / 스코프 밖 39건 (그중 domain_id 없음 6건)
  🚨 feature: 이번 키트 0건 / 프로젝트 전역 9건 (그중 domain_id 없음 9건) — 전량 누락
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[SHELL-002]]
- NEW [[NAV-002]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-032]] | acceptance | 1 | UNCHANGED |
| [[AC-033]] | acceptance | 3 | UNCHANGED |
| [[AC-034]] | acceptance | 1 | UNCHANGED |
| [[AC-035]] | acceptance | 1 | UNCHANGED |
| [[AC-036]] | acceptance | 1 | UNCHANGED |
| [[AC-037]] | acceptance | 1 | UNCHANGED |
| [[ADR-003]] | adr | 2 | UNCHANGED |
| [[ADR-013]] | adr | 8 | UNCHANGED |
| [[API-024]] | api_endpoint | 6 | UNCHANGED |
| [[API-081]] | api_endpoint | 6 | UNCHANGED |
| [[API-082]] | api_endpoint | 7 | UNCHANGED |
| [[API-083]] | api_endpoint | 5 | UNCHANGED |
| [[API-110]] | api_endpoint | 3 | UNCHANGED |
| [[API-111]] | api_endpoint | 3 | UNCHANGED |
| [[API-115]] | api_endpoint | 3 | UNCHANGED |
| [[API-139]] | api_endpoint | 3 | UNCHANGED |
| [[API-140]] | api_endpoint | 4 | UNCHANGED |
| [[API-142]] | api_endpoint | 3 | UNCHANGED |
| [[API-147]] | api_endpoint | 2 | UNCHANGED |
| [[API-149]] | api_endpoint | 3 | UNCHANGED |
| [[API-151]] | api_endpoint | 2 | UNCHANGED |
| [[API-154]] | api_endpoint | 4 | UNCHANGED |
| [[API-155]] | api_endpoint | 2 | UNCHANGED |
| [[API-157]] | api_endpoint | 3 | UNCHANGED |
| [[API-159]] | api_endpoint | 3 | UNCHANGED |
| [[API-161]] | api_endpoint | 3 | UNCHANGED |
| [[API-163]] | api_endpoint | 3 | UNCHANGED |
| [[API-166]] | api_endpoint | 3 | UNCHANGED |
| [[API-169]] | api_endpoint | 6 | UNCHANGED |
| [[API-171]] | api_endpoint | 3 | UNCHANGED |
| [[API-203]] | api_endpoint | 2 | UNCHANGED |
| [[CDIAG-011]] | class_diagram | 7 | UNCHANGED |
| [[CMP-009]] | diagram_c4_component | 11 | UNCHANGED |
| [[DFEAT-043]] | domain_feature | 10 | UNCHANGED |
| [[DFEAT-044]] | domain_feature | 9 | UNCHANGED |
| [[DFEAT-053]] | domain_feature | 5 | UNCHANGED |
| [[DFEAT-055]] | domain_feature | 2 | UNCHANGED |
| [[DOMAIN-013]] | domain | 7 | UNCHANGED |
| [[ERD-018]] | erd | 10 | UNCHANGED |
| [[ERD-026]] | erd | 4 | UNCHANGED |
| [[ERD-028]] | erd | 1 | UNCHANGED |
| [[EVT-012]] | domain_event | 3 | UNCHANGED |
| [[EXTSYS-006]] | external_system | 4 | UNCHANGED |
| [[INT-009]] | integration_point | 4 | UNCHANGED |
| [[NAV-002]] | navigation_tree | 5 | NEW |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 4 | UNCHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 4 | UNCHANGED |
| [[NFR-012]] | nfr | 4 | UNCHANGED |
| [[NFR-013]] | nfr | 4 | UNCHANGED |
| [[NFR-014]] | nfr | 2 | UNCHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 8 | UNCHANGED |
| [[NFR-018]] | nfr | 3 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 4 | UNCHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[ROLE-001]] | permission_role | 10 | UNCHANGED |
| [[ROLE-002]] | permission_role | 6 | UNCHANGED |
| [[ROLE-003]] | permission_role | 7 | UNCHANGED |
| [[SCREEN-028]] | screen_spec | 17 | UNCHANGED |
| [[SCREEN-029]] | screen_spec | 34 | UNCHANGED |
| [[SCREEN-033]] | screen_spec | 13 | UNCHANGED |
| [[SCREEN-034]] | screen_spec | 15 | UNCHANGED |
| [[SD-024]] | screen_design | 4 | UNCHANGED |
| [[SD-025]] | screen_design | 3 | UNCHANGED |
| [[SD-026]] | screen_design | 2 | UNCHANGED |
| [[SD-027]] | screen_design | 2 | UNCHANGED |
| [[SEQ-016]] | diagram_sequence | 1 | UNCHANGED |
| [[SEQ-019]] | diagram_sequence | 1 | UNCHANGED |
| [[SHELL-002]] | app_shell | 4 | NEW |
| [[TEST-005]] | test_scenario | 11 | UNCHANGED |
| [[UC-024]] | use_case | 14 | UNCHANGED |
| [[UC-027]] | use_case | 10 | UNCHANGED |
