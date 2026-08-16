# Version Master — DOMAIN-013

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-013 |
| Last sync | 2026-08-16T14:48:56.610Z |
| Mode | INITIAL — NEW 66 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | docs/design/포털-DOMAIN-013 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-013 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 3건 / 스코프 밖 40건
  ℹ️  api_endpoint: 이번 키트 22건 / 스코프 밖 159건
  ℹ️  erd: 이번 키트 3건 / 스코프 밖 16건
  ℹ️  diagram_sequence: 이번 키트 2건 / 스코프 밖 23건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 4건 / 스코프 밖 28건
  ℹ️  use_case: 이번 키트 2건 / 스코프 밖 23건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  🚨 acceptance: 이번 키트 0건 / 프로젝트 전역 25건 (그중 domain_id 없음 24건) — 전량 누락
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

- NEW [[ADR-003]]
- NEW [[ADR-013]]
- NEW [[API-024]]
- NEW [[API-081]]
- NEW [[API-082]]
- NEW [[API-083]]
- NEW [[API-110]]
- NEW [[API-111]]
- NEW [[API-115]]
- NEW [[API-139]]
- NEW [[API-140]]
- NEW [[API-142]]
- NEW [[API-147]]
- NEW [[API-149]]
- NEW [[API-151]]
- NEW [[API-154]]
- NEW [[API-155]]
- NEW [[API-157]]
- NEW [[API-159]]
- NEW [[API-161]]
- NEW [[API-163]]
- NEW [[API-166]]
- NEW [[API-169]]
- NEW [[API-171]]
- NEW [[CDIAG-011]]
- NEW [[CMP-009]]
- NEW [[SEQ-016]]
- NEW [[SEQ-019]]
- NEW [[DOMAIN-013]]
- NEW [[EVT-012]]
- NEW [[DFEAT-043]]
- NEW [[DFEAT-044]]
- NEW [[DFEAT-053]]
- NEW [[ERD-018]]
- NEW [[ERD-026]]
- NEW [[ERD-028]]
- NEW [[EXTSYS-006]]
- NEW [[INT-009]]
- NEW [[NFR-008]]
- NEW [[NFR-009]]
- NEW [[NFR-010]]
- NEW [[NFR-011]]
- NEW [[NFR-012]]
- NEW [[NFR-013]]
- NEW [[NFR-014]]
- NEW [[NFR-015]]
- NEW [[NFR-016]]
- NEW [[NFR-017]]
- NEW [[NFR-018]]
- NEW [[NFR-019]]
- NEW [[NFR-020]]
- NEW [[NFR-021]]
- NEW [[ROLE-001]]
- NEW [[ROLE-002]]
- NEW [[ROLE-003]]
- NEW [[SD-024]]
- NEW [[SD-025]]
- NEW [[SD-026]]
- NEW [[SD-027]]
- NEW [[SCREEN-028]]
- NEW [[SCREEN-029]]
- NEW [[SCREEN-033]]
- NEW [[SCREEN-034]]
- NEW [[TEST-005]]
- NEW [[UC-024]]
- NEW [[UC-027]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[ADR-003]] | adr | 2 | NEW |
| [[ADR-013]] | adr | 8 | NEW |
| [[API-024]] | api_endpoint | 5 | NEW |
| [[API-081]] | api_endpoint | 5 | NEW |
| [[API-082]] | api_endpoint | 7 | NEW |
| [[API-083]] | api_endpoint | 4 | NEW |
| [[API-110]] | api_endpoint | 3 | NEW |
| [[API-111]] | api_endpoint | 3 | NEW |
| [[API-115]] | api_endpoint | 2 | NEW |
| [[API-139]] | api_endpoint | 3 | NEW |
| [[API-140]] | api_endpoint | 3 | NEW |
| [[API-142]] | api_endpoint | 2 | NEW |
| [[API-147]] | api_endpoint | 2 | NEW |
| [[API-149]] | api_endpoint | 3 | NEW |
| [[API-151]] | api_endpoint | 2 | NEW |
| [[API-154]] | api_endpoint | 4 | NEW |
| [[API-155]] | api_endpoint | 2 | NEW |
| [[API-157]] | api_endpoint | 3 | NEW |
| [[API-159]] | api_endpoint | 3 | NEW |
| [[API-161]] | api_endpoint | 3 | NEW |
| [[API-163]] | api_endpoint | 3 | NEW |
| [[API-166]] | api_endpoint | 3 | NEW |
| [[API-169]] | api_endpoint | 6 | NEW |
| [[API-171]] | api_endpoint | 3 | NEW |
| [[CDIAG-011]] | class_diagram | 7 | NEW |
| [[CMP-009]] | diagram_c4_component | 11 | NEW |
| [[DFEAT-043]] | domain_feature | 10 | NEW |
| [[DFEAT-044]] | domain_feature | 8 | NEW |
| [[DFEAT-053]] | domain_feature | 5 | NEW |
| [[DOMAIN-013]] | domain | 7 | NEW |
| [[ERD-018]] | erd | 9 | NEW |
| [[ERD-026]] | erd | 4 | NEW |
| [[ERD-028]] | erd | 1 | NEW |
| [[EVT-012]] | domain_event | 3 | NEW |
| [[EXTSYS-006]] | external_system | 4 | NEW |
| [[INT-009]] | integration_point | 4 | NEW |
| [[NFR-008]] | nfr | 6 | NEW |
| [[NFR-009]] | nfr | 4 | NEW |
| [[NFR-010]] | nfr | 3 | NEW |
| [[NFR-011]] | nfr | 4 | NEW |
| [[NFR-012]] | nfr | 4 | NEW |
| [[NFR-013]] | nfr | 4 | NEW |
| [[NFR-014]] | nfr | 2 | NEW |
| [[NFR-015]] | nfr | 5 | NEW |
| [[NFR-016]] | nfr | 4 | NEW |
| [[NFR-017]] | nfr | 8 | NEW |
| [[NFR-018]] | nfr | 3 | NEW |
| [[NFR-019]] | nfr | 2 | NEW |
| [[NFR-020]] | nfr | 4 | NEW |
| [[NFR-021]] | nfr | 1 | NEW |
| [[ROLE-001]] | permission_role | 10 | NEW |
| [[ROLE-002]] | permission_role | 6 | NEW |
| [[ROLE-003]] | permission_role | 6 | NEW |
| [[SCREEN-028]] | screen_spec | 15 | NEW |
| [[SCREEN-029]] | screen_spec | 34 | NEW |
| [[SCREEN-033]] | screen_spec | 13 | NEW |
| [[SCREEN-034]] | screen_spec | 15 | NEW |
| [[SD-024]] | screen_design | 4 | NEW |
| [[SD-025]] | screen_design | 3 | NEW |
| [[SD-026]] | screen_design | 2 | NEW |
| [[SD-027]] | screen_design | 2 | NEW |
| [[SEQ-016]] | diagram_sequence | 1 | NEW |
| [[SEQ-019]] | diagram_sequence | 1 | NEW |
| [[TEST-005]] | test_scenario | 11 | NEW |
| [[UC-024]] | use_case | 13 | NEW |
| [[UC-027]] | use_case | 10 | NEW |
