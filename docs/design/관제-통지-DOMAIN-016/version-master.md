# Version Master — DOMAIN-016

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-016 |
| Last sync | 2026-08-16T14:49:12.452Z |
| Mode | INITIAL — NEW 58 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | docs/design/관제-통지-DOMAIN-016 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-016 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 7건 / 스코프 밖 36건
  ℹ️  api_endpoint: 이번 키트 3건 / 스코프 밖 178건
  ℹ️  erd: 이번 키트 2건 / 스코프 밖 17건
  ℹ️  diagram_sequence: 이번 키트 5건 / 스코프 밖 20건 (그중 domain_id 없음 13건)
  ℹ️  screen_spec: 이번 키트 1건 / 스코프 밖 31건
  ℹ️  use_case: 이번 키트 1건 / 스코프 밖 24건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 4건 / 스코프 밖 8건
  ℹ️  acceptance: 이번 키트 1건 / 스코프 밖 24건 (그중 domain_id 없음 23건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 8건 / 스코프 밖 33건 (그중 domain_id 없음 5건)
  ℹ️  feature: 이번 키트 1건 / 스코프 밖 8건 (그중 domain_id 없음 8건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[AC-009]]
- NEW [[ADR-002]]
- NEW [[ADR-003]]
- NEW [[ADR-007]]
- NEW [[ADR-013]]
- NEW [[ADR-020]]
- NEW [[ADR-033]]
- NEW [[ADR-037]]
- NEW [[ADR-046]]
- NEW [[API-074]]
- NEW [[API-075]]
- NEW [[API-076]]
- NEW [[CDIAG-013]]
- NEW [[CMP-009]]
- NEW [[SEQ-010]]
- NEW [[SEQ-015]]
- NEW [[SEQ-020]]
- NEW [[SEQ-023]]
- NEW [[SEQ-025]]
- NEW [[DOMAIN-016]]
- NEW [[EVT-003]]
- NEW [[EVT-004]]
- NEW [[EVT-009]]
- NEW [[EVT-010]]
- NEW [[DFEAT-006]]
- NEW [[DFEAT-043]]
- NEW [[DFEAT-044]]
- NEW [[DFEAT-046]]
- NEW [[DFEAT-047]]
- NEW [[DFEAT-053]]
- NEW [[DFEAT-054]]
- NEW [[ERD-021]]
- NEW [[ERD-027]]
- NEW [[EXTSYS-005]]
- NEW [[FEAT-003]]
- NEW [[INT-007]]
- NEW [[INT-010]]
- NEW [[INTSPEC-004]]
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
- NEW [[SCREEN-019]]
- NEW [[TEST-004]]
- NEW [[UC-009]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-009]] | acceptance | 15 | NEW |
| [[ADR-002]] | adr | 2 | NEW |
| [[ADR-003]] | adr | 2 | NEW |
| [[ADR-007]] | adr | 3 | NEW |
| [[ADR-013]] | adr | 8 | NEW |
| [[ADR-020]] | adr | 10 | NEW |
| [[ADR-033]] | adr | 3 | NEW |
| [[ADR-037]] | adr | 3 | NEW |
| [[ADR-046]] | adr | 1 | NEW |
| [[API-074]] | api_endpoint | 7 | NEW |
| [[API-075]] | api_endpoint | 8 | NEW |
| [[API-076]] | api_endpoint | 8 | NEW |
| [[CDIAG-013]] | class_diagram | 7 | NEW |
| [[CMP-009]] | diagram_c4_component | 11 | NEW |
| [[DFEAT-006]] | domain_feature | 8 | NEW |
| [[DFEAT-043]] | domain_feature | 10 | NEW |
| [[DFEAT-044]] | domain_feature | 8 | NEW |
| [[DFEAT-046]] | domain_feature | 9 | NEW |
| [[DFEAT-047]] | domain_feature | 6 | NEW |
| [[DFEAT-053]] | domain_feature | 5 | NEW |
| [[DFEAT-054]] | domain_feature | 2 | NEW |
| [[DOMAIN-016]] | domain | 10 | NEW |
| [[ERD-021]] | erd | 14 | NEW |
| [[ERD-027]] | erd | 6 | NEW |
| [[EVT-003]] | domain_event | 5 | NEW |
| [[EVT-004]] | domain_event | 10 | NEW |
| [[EVT-009]] | domain_event | 4 | NEW |
| [[EVT-010]] | domain_event | 4 | NEW |
| [[EXTSYS-005]] | external_system | 10 | NEW |
| [[FEAT-003]] | feature | 10 | NEW |
| [[INT-007]] | integration_point | 10 | NEW |
| [[INT-010]] | integration_point | 6 | NEW |
| [[INTSPEC-004]] | integration_spec | 5 | NEW |
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
| [[SCREEN-019]] | screen_spec | 29 | NEW |
| [[SEQ-010]] | diagram_sequence | 13 | NEW |
| [[SEQ-015]] | diagram_sequence | 1 | NEW |
| [[SEQ-020]] | diagram_sequence | 1 | NEW |
| [[SEQ-023]] | diagram_sequence | 2 | NEW |
| [[SEQ-025]] | diagram_sequence | 1 | NEW |
| [[TEST-004]] | test_scenario | 17 | NEW |
| [[UC-009]] | use_case | 19 | NEW |
