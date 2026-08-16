# Version Master — DOMAIN-011

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-011 |
| Last sync | 2026-08-16T14:48:55.204Z |
| Mode | INITIAL — NEW 47 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | docs/design/마킹-DOMAIN-011 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-011 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 2건 / 스코프 밖 41건
  ℹ️  api_endpoint: 이번 키트 5건 / 스코프 밖 176건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 18건
  ℹ️  diagram_sequence: 이번 키트 2건 / 스코프 밖 23건 (그중 domain_id 없음 12건)
  ℹ️  screen_spec: 이번 키트 2건 / 스코프 밖 30건
  ℹ️  use_case: 이번 키트 1건 / 스코프 밖 24건 (그중 domain_id 없음 1건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 2건 / 스코프 밖 23건 (그중 domain_id 없음 22건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 6건 / 스코프 밖 35건 (그중 domain_id 없음 5건)
  ℹ️  feature: 이번 키트 1건 / 스코프 밖 8건 (그중 domain_id 없음 8건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[AC-027]]
- NEW [[AC-028]]
- NEW [[ADR-001]]
- NEW [[ADR-003]]
- NEW [[ADR-006]]
- NEW [[ADR-008]]
- NEW [[ADR-022]]
- NEW [[ADR-046]]
- NEW [[API-043]]
- NEW [[API-047]]
- NEW [[API-084]]
- NEW [[API-091]]
- NEW [[API-114]]
- NEW [[CDIAG-002]]
- NEW [[CMP-002]]
- NEW [[SEQ-001]]
- NEW [[SEQ-014]]
- NEW [[DOMAIN-011]]
- NEW [[EVT-001]]
- NEW [[DFEAT-039]]
- NEW [[DFEAT-048]]
- NEW [[ERD-013]]
- NEW [[FEAT-005]]
- NEW [[INT-002]]
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
- NEW [[SD-012]]
- NEW [[SCREEN-006]]
- NEW [[SCREEN-008]]
- NEW [[TEST-001]]
- NEW [[TEST-002]]
- NEW [[UC-019]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-027]] | acceptance | 6 | NEW |
| [[AC-028]] | acceptance | 5 | NEW |
| [[ADR-001]] | adr | 2 | NEW |
| [[ADR-003]] | adr | 2 | NEW |
| [[ADR-006]] | adr | 4 | NEW |
| [[ADR-008]] | adr | 3 | NEW |
| [[ADR-022]] | adr | 5 | NEW |
| [[ADR-046]] | adr | 1 | NEW |
| [[API-043]] | api_endpoint | 12 | NEW |
| [[API-047]] | api_endpoint | 10 | NEW |
| [[API-084]] | api_endpoint | 6 | NEW |
| [[API-091]] | api_endpoint | 10 | NEW |
| [[API-114]] | api_endpoint | 1 | NEW |
| [[CDIAG-002]] | class_diagram | 4 | NEW |
| [[CMP-002]] | diagram_c4_component | 3 | NEW |
| [[DFEAT-039]] | domain_feature | 8 | NEW |
| [[DFEAT-048]] | domain_feature | 16 | NEW |
| [[DOMAIN-011]] | domain | 6 | NEW |
| [[ERD-013]] | erd | 10 | NEW |
| [[EVT-001]] | domain_event | 5 | NEW |
| [[FEAT-005]] | feature | 9 | NEW |
| [[INT-002]] | integration_point | 14 | NEW |
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
| [[SCREEN-006]] | screen_spec | 41 | NEW |
| [[SCREEN-008]] | screen_spec | 35 | NEW |
| [[SD-012]] | screen_design | 8 | NEW |
| [[SEQ-001]] | diagram_sequence | 14 | NEW |
| [[SEQ-014]] | diagram_sequence | 13 | NEW |
| [[TEST-001]] | test_scenario | 10 | NEW |
| [[TEST-002]] | test_scenario | 11 | NEW |
| [[UC-019]] | use_case | 17 | NEW |
