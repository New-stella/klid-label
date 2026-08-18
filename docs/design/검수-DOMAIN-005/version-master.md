# Version Master — DOMAIN-005

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-005 |
| Last sync | 2026-08-16T14:41:14.438Z |
| Mode | INITIAL — NEW 94 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | docs/design/검수-DOMAIN-005 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-005 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 6건 / 스코프 밖 37건
  ℹ️  api_endpoint: 이번 키트 21건 / 스코프 밖 160건
  ℹ️  erd: 이번 키트 2건 / 스코프 밖 17건
  ℹ️  diagram_sequence: 이번 키트 5건 / 스코프 밖 20건 (그중 domain_id 없음 11건)
  ℹ️  screen_spec: 이번 키트 4건 / 스코프 밖 28건
  ℹ️  use_case: 이번 키트 5건 / 스코프 밖 20건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 5건 / 스코프 밖 7건
  ℹ️  acceptance: 이번 키트 3건 / 스코프 밖 22건 (그중 domain_id 없음 22건)
  ℹ️  adr: 이번 키트 10건 / 스코프 밖 31건 (그중 domain_id 없음 3건)
  ℹ️  feature: 이번 키트 4건 / 스코프 밖 5건 (그중 domain_id 없음 5건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[AC-010]]
- NEW [[AC-022]]
- NEW [[AC-024]]
- NEW [[ADR-001]]
- NEW [[ADR-002]]
- NEW [[ADR-003]]
- NEW [[ADR-004]]
- NEW [[ADR-007]]
- NEW [[ADR-009]]
- NEW [[ADR-015]]
- NEW [[ADR-019]]
- NEW [[ADR-020]]
- NEW [[ADR-031]]
- NEW [[API-008]]
- NEW [[API-009]]
- NEW [[API-010]]
- NEW [[API-011]]
- NEW [[API-012]]
- NEW [[API-013]]
- NEW [[API-014]]
- NEW [[API-015]]
- NEW [[API-016]]
- NEW [[API-017]]
- NEW [[API-021]]
- NEW [[API-065]]
- NEW [[API-066]]
- NEW [[API-067]]
- NEW [[API-102]]
- NEW [[API-103]]
- NEW [[API-104]]
- NEW [[API-105]]
- NEW [[API-132]]
- NEW [[API-138]]
- NEW [[API-178]]
- NEW [[CDIAG-006]]
- NEW [[CDIAG-014]]
- NEW [[CONST-001]]
- NEW [[CONST-002]]
- NEW [[CMP-005]]
- NEW [[SEQ-008]]
- NEW [[SEQ-010]]
- NEW [[SEQ-011]]
- NEW [[SEQ-015]]
- NEW [[SEQ-023]]
- NEW [[DOMAIN-005]]
- NEW [[EVT-003]]
- NEW [[EVT-004]]
- NEW [[EVT-006]]
- NEW [[EVT-008]]
- NEW [[EVT-009]]
- NEW [[DFEAT-021]]
- NEW [[DFEAT-023]]
- NEW [[DFEAT-024]]
- NEW [[DFEAT-025]]
- NEW [[DFEAT-049]]
- NEW [[DFEAT-054]]
- NEW [[ERD-015]]
- NEW [[ERD-023]]
- NEW [[FEAT-003]]
- NEW [[FEAT-004]]
- NEW [[FEAT-008]]
- NEW [[FEAT-009]]
- NEW [[INT-003]]
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
- NEW [[SD-001]]
- NEW [[SD-005]]
- NEW [[SCREEN-005]]
- NEW [[SCREEN-018]]
- NEW [[SCREEN-019]]
- NEW [[SCREEN-023]]
- NEW [[TEST-002]]
- NEW [[TEST-003]]
- NEW [[TEST-004]]
- NEW [[UC-007]]
- NEW [[UC-009]]
- NEW [[UC-010]]
- NEW [[UC-022]]
- NEW [[UC-023]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-010]] | acceptance | 7 | NEW |
| [[AC-022]] | acceptance | 7 | NEW |
| [[AC-024]] | acceptance | 5 | NEW |
| [[ADR-001]] | adr | 2 | NEW |
| [[ADR-002]] | adr | 2 | NEW |
| [[ADR-003]] | adr | 2 | NEW |
| [[ADR-004]] | adr | 4 | NEW |
| [[ADR-007]] | adr | 3 | NEW |
| [[ADR-009]] | adr | 5 | NEW |
| [[ADR-015]] | adr | 4 | NEW |
| [[ADR-019]] | adr | 7 | NEW |
| [[ADR-020]] | adr | 10 | NEW |
| [[ADR-031]] | adr | 2 | NEW |
| [[API-008]] | api_endpoint | 11 | NEW |
| [[API-009]] | api_endpoint | 8 | NEW |
| [[API-010]] | api_endpoint | 4 | NEW |
| [[API-011]] | api_endpoint | 4 | NEW |
| [[API-012]] | api_endpoint | 6 | NEW |
| [[API-013]] | api_endpoint | 6 | NEW |
| [[API-014]] | api_endpoint | 9 | NEW |
| [[API-015]] | api_endpoint | 7 | NEW |
| [[API-016]] | api_endpoint | 3 | NEW |
| [[API-017]] | api_endpoint | 4 | NEW |
| [[API-021]] | api_endpoint | 7 | NEW |
| [[API-065]] | api_endpoint | 15 | NEW |
| [[API-066]] | api_endpoint | 4 | NEW |
| [[API-067]] | api_endpoint | 5 | NEW |
| [[API-102]] | api_endpoint | 13 | NEW |
| [[API-103]] | api_endpoint | 9 | NEW |
| [[API-104]] | api_endpoint | 12 | NEW |
| [[API-105]] | api_endpoint | 7 | NEW |
| [[API-132]] | api_endpoint | 2 | NEW |
| [[API-138]] | api_endpoint | 4 | NEW |
| [[API-178]] | api_endpoint | 8 | NEW |
| [[CDIAG-006]] | class_diagram | 9 | NEW |
| [[CDIAG-014]] | class_diagram | 7 | NEW |
| [[CMP-005]] | diagram_c4_component | 4 | NEW |
| [[CONST-001]] | constant | 4 | NEW |
| [[CONST-002]] | constant | 3 | NEW |
| [[DFEAT-021]] | domain_feature | 8 | NEW |
| [[DFEAT-023]] | domain_feature | 2 | NEW |
| [[DFEAT-024]] | domain_feature | 9 | NEW |
| [[DFEAT-025]] | domain_feature | 6 | NEW |
| [[DFEAT-049]] | domain_feature | 7 | NEW |
| [[DFEAT-054]] | domain_feature | 2 | NEW |
| [[DOMAIN-005]] | domain | 14 | NEW |
| [[ERD-015]] | erd | 14 | NEW |
| [[ERD-023]] | erd | 10 | NEW |
| [[EVT-003]] | domain_event | 5 | NEW |
| [[EVT-004]] | domain_event | 10 | NEW |
| [[EVT-006]] | domain_event | 8 | NEW |
| [[EVT-008]] | domain_event | 8 | NEW |
| [[EVT-009]] | domain_event | 4 | NEW |
| [[FEAT-003]] | feature | 10 | NEW |
| [[FEAT-004]] | feature | 9 | NEW |
| [[FEAT-008]] | feature | 3 | NEW |
| [[FEAT-009]] | feature | 3 | NEW |
| [[INT-003]] | integration_point | 13 | NEW |
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
| [[SCREEN-018]] | screen_spec | 24 | NEW |
| [[SCREEN-019]] | screen_spec | 29 | NEW |
| [[SCREEN-023]] | screen_spec | 39 | NEW |
| [[SD-001]] | screen_design | 5 | NEW |
| [[SD-005]] | screen_design | 5 | NEW |
| [[SEQ-008]] | diagram_sequence | 5 | NEW |
| [[SEQ-010]] | diagram_sequence | 13 | NEW |
| [[SEQ-011]] | diagram_sequence | 7 | NEW |
| [[SEQ-015]] | diagram_sequence | 1 | NEW |
| [[SEQ-023]] | diagram_sequence | 2 | NEW |
| [[TEST-002]] | test_scenario | 11 | NEW |
| [[TEST-003]] | test_scenario | 14 | NEW |
| [[TEST-004]] | test_scenario | 17 | NEW |
| [[UC-007]] | use_case | 12 | NEW |
| [[UC-009]] | use_case | 19 | NEW |
| [[UC-010]] | use_case | 13 | NEW |
| [[UC-022]] | use_case | 17 | NEW |
| [[UC-023]] | use_case | 22 | NEW |
