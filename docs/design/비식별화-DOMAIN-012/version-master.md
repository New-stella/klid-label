# Version Master — DOMAIN-012

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-012 |
| Last sync | 2026-08-16T14:48:55.876Z |
| Mode | INITIAL — NEW 72 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | docs/design/비식별화-DOMAIN-012 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-012 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 5건 / 스코프 밖 38건
  ℹ️  api_endpoint: 이번 키트 8건 / 스코프 밖 173건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 18건
  ℹ️  diagram_sequence: 이번 키트 5건 / 스코프 밖 20건 (그중 domain_id 없음 10건)
  ℹ️  screen_spec: 이번 키트 5건 / 스코프 밖 27건
  ℹ️  use_case: 이번 키트 3건 / 스코프 밖 22건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 2건 / 스코프 밖 10건
  ℹ️  acceptance: 이번 키트 5건 / 스코프 밖 20건 (그중 domain_id 없음 19건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 10건 / 스코프 밖 31건 (그중 domain_id 없음 4건)
  ℹ️  feature: 이번 키트 2건 / 스코프 밖 7건 (그중 domain_id 없음 7건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[AC-011]]
- NEW [[AC-013]]
- NEW [[AC-016]]
- NEW [[AC-019]]
- NEW [[AC-023]]
- NEW [[ADR-001]]
- NEW [[ADR-003]]
- NEW [[ADR-006]]
- NEW [[ADR-020]]
- NEW [[ADR-022]]
- NEW [[ADR-024]]
- NEW [[ADR-025]]
- NEW [[ADR-027]]
- NEW [[ADR-032]]
- NEW [[ADR-046]]
- NEW [[API-032]]
- NEW [[API-091]]
- NEW [[API-094]]
- NEW [[API-109]]
- NEW [[API-112]]
- NEW [[API-183]]
- NEW [[API-184]]
- NEW [[API-202]]
- NEW [[CDIAG-003]]
- NEW [[CMP-003]]
- NEW [[SEQ-001]]
- NEW [[SEQ-012]]
- NEW [[SEQ-013]]
- NEW [[SEQ-014]]
- NEW [[SEQ-025]]
- NEW [[DOMAIN-012]]
- NEW [[EVT-007]]
- NEW [[EVT-008]]
- NEW [[DFEAT-041]]
- NEW [[DFEAT-042]]
- NEW [[DFEAT-048]]
- NEW [[DFEAT-051]]
- NEW [[DFEAT-054]]
- NEW [[ERD-017]]
- NEW [[EXTSYS-003]]
- NEW [[FEAT-005]]
- NEW [[FEAT-006]]
- NEW [[INT-004]]
- NEW [[INT-005]]
- NEW [[INTSPEC-001]]
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
- NEW [[SD-021]]
- NEW [[SCREEN-005]]
- NEW [[SCREEN-008]]
- NEW [[SCREEN-009]]
- NEW [[SCREEN-025]]
- NEW [[SCREEN-032]]
- NEW [[TEST-001]]
- NEW [[UC-011]]
- NEW [[UC-013]]
- NEW [[UC-016]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-011]] | acceptance | 9 | NEW |
| [[AC-013]] | acceptance | 8 | NEW |
| [[AC-016]] | acceptance | 10 | NEW |
| [[AC-019]] | acceptance | 6 | NEW |
| [[AC-023]] | acceptance | 6 | NEW |
| [[ADR-001]] | adr | 2 | NEW |
| [[ADR-003]] | adr | 2 | NEW |
| [[ADR-006]] | adr | 4 | NEW |
| [[ADR-020]] | adr | 10 | NEW |
| [[ADR-022]] | adr | 5 | NEW |
| [[ADR-024]] | adr | 3 | NEW |
| [[ADR-025]] | adr | 3 | NEW |
| [[ADR-027]] | adr | 2 | NEW |
| [[ADR-032]] | adr | 7 | NEW |
| [[ADR-046]] | adr | 1 | NEW |
| [[API-032]] | api_endpoint | 8 | NEW |
| [[API-091]] | api_endpoint | 10 | NEW |
| [[API-094]] | api_endpoint | 9 | NEW |
| [[API-109]] | api_endpoint | 5 | NEW |
| [[API-112]] | api_endpoint | 4 | NEW |
| [[API-183]] | api_endpoint | 1 | NEW |
| [[API-184]] | api_endpoint | 2 | NEW |
| [[API-202]] | api_endpoint | 2 | NEW |
| [[CDIAG-003]] | class_diagram | 11 | NEW |
| [[CMP-003]] | diagram_c4_component | 10 | NEW |
| [[DFEAT-041]] | domain_feature | 12 | NEW |
| [[DFEAT-042]] | domain_feature | 11 | NEW |
| [[DFEAT-048]] | domain_feature | 16 | NEW |
| [[DFEAT-051]] | domain_feature | 4 | NEW |
| [[DFEAT-054]] | domain_feature | 2 | NEW |
| [[DOMAIN-012]] | domain | 8 | NEW |
| [[ERD-017]] | erd | 21 | NEW |
| [[EVT-007]] | domain_event | 3 | NEW |
| [[EVT-008]] | domain_event | 8 | NEW |
| [[EXTSYS-003]] | external_system | 8 | NEW |
| [[FEAT-005]] | feature | 9 | NEW |
| [[FEAT-006]] | feature | 11 | NEW |
| [[INT-004]] | integration_point | 11 | NEW |
| [[INT-005]] | integration_point | 7 | NEW |
| [[INTSPEC-001]] | integration_spec | 9 | NEW |
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
| [[SCREEN-005]] | screen_spec | 76 | NEW |
| [[SCREEN-008]] | screen_spec | 35 | NEW |
| [[SCREEN-009]] | screen_spec | 44 | NEW |
| [[SCREEN-025]] | screen_spec | 30 | NEW |
| [[SCREEN-032]] | screen_spec | 23 | NEW |
| [[SD-021]] | screen_design | 4 | NEW |
| [[SEQ-001]] | diagram_sequence | 14 | NEW |
| [[SEQ-012]] | diagram_sequence | 11 | NEW |
| [[SEQ-013]] | diagram_sequence | 6 | NEW |
| [[SEQ-014]] | diagram_sequence | 13 | NEW |
| [[SEQ-025]] | diagram_sequence | 1 | NEW |
| [[TEST-001]] | test_scenario | 10 | NEW |
| [[UC-011]] | use_case | 12 | NEW |
| [[UC-013]] | use_case | 8 | NEW |
| [[UC-016]] | use_case | 20 | NEW |
