# Version Master — DOMAIN-014

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-014 |
| Last sync | 2026-08-16T14:48:57.240Z |
| Mode | INITIAL — NEW 50 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | docs/design/시스템-설정-DOMAIN-014 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-014 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 1건 / 스코프 밖 42건
  ℹ️  api_endpoint: 이번 키트 7건 / 스코프 밖 174건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 18건
  ℹ️  diagram_sequence: 이번 키트 4건 / 스코프 밖 21건 (그중 domain_id 없음 12건)
  ℹ️  screen_spec: 이번 키트 2건 / 스코프 밖 30건
  ℹ️  use_case: 이번 키트 3건 / 스코프 밖 22건 (그중 domain_id 없음 2건)
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  ℹ️  acceptance: 이번 키트 1건 / 스코프 밖 24건 (그중 domain_id 없음 23건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 5건 / 스코프 밖 36건 (그중 domain_id 없음 6건)
  ℹ️  feature: 이번 키트 1건 / 스코프 밖 8건 (그중 domain_id 없음 8건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[AC-006]]
- NEW [[ADR-003]]
- NEW [[ADR-006]]
- NEW [[ADR-007]]
- NEW [[ADR-039]]
- NEW [[ADR-046]]
- NEW [[API-068]]
- NEW [[API-069]]
- NEW [[API-090]]
- NEW [[API-118]]
- NEW [[API-141]]
- NEW [[API-193]]
- NEW [[API-194]]
- NEW [[CDIAG-012]]
- NEW [[CMP-011]]
- NEW [[SEQ-007]]
- NEW [[SEQ-013]]
- NEW [[SEQ-024]]
- NEW [[SEQ-025]]
- NEW [[DOMAIN-014]]
- NEW [[DFEAT-045]]
- NEW [[ERD-016]]
- NEW [[FEAT-007]]
- NEW [[INT-002]]
- NEW [[INT-004]]
- NEW [[INT-005]]
- NEW [[INT-007]]
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
- NEW [[SD-015]]
- NEW [[SCREEN-005]]
- NEW [[SCREEN-025]]
- NEW [[UC-006]]
- NEW [[UC-013]]
- NEW [[UC-031]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-006]] | acceptance | 9 | NEW |
| [[ADR-003]] | adr | 2 | NEW |
| [[ADR-006]] | adr | 4 | NEW |
| [[ADR-007]] | adr | 3 | NEW |
| [[ADR-039]] | adr | 2 | NEW |
| [[ADR-046]] | adr | 1 | NEW |
| [[API-068]] | api_endpoint | 4 | NEW |
| [[API-069]] | api_endpoint | 8 | NEW |
| [[API-090]] | api_endpoint | 4 | NEW |
| [[API-118]] | api_endpoint | 2 | NEW |
| [[API-141]] | api_endpoint | 2 | NEW |
| [[API-193]] | api_endpoint | 3 | NEW |
| [[API-194]] | api_endpoint | 4 | NEW |
| [[CDIAG-012]] | class_diagram | 4 | NEW |
| [[CMP-011]] | diagram_c4_component | 7 | NEW |
| [[DFEAT-045]] | domain_feature | 9 | NEW |
| [[DOMAIN-014]] | domain | 5 | NEW |
| [[ERD-016]] | erd | 13 | NEW |
| [[FEAT-007]] | feature | 6 | NEW |
| [[INT-002]] | integration_point | 14 | NEW |
| [[INT-004]] | integration_point | 11 | NEW |
| [[INT-005]] | integration_point | 7 | NEW |
| [[INT-007]] | integration_point | 10 | NEW |
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
| [[SCREEN-025]] | screen_spec | 30 | NEW |
| [[SD-015]] | screen_design | 3 | NEW |
| [[SEQ-007]] | diagram_sequence | 6 | NEW |
| [[SEQ-013]] | diagram_sequence | 6 | NEW |
| [[SEQ-024]] | diagram_sequence | 1 | NEW |
| [[SEQ-025]] | diagram_sequence | 1 | NEW |
| [[UC-006]] | use_case | 9 | NEW |
| [[UC-013]] | use_case | 8 | NEW |
| [[UC-031]] | use_case | 7 | NEW |
