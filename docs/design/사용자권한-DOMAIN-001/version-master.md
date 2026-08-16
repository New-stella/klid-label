# Version Master — DOMAIN-001

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-001 |
| Last sync | 2026-08-16T14:48:50.563Z |
| Mode | INITIAL — NEW 53 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | docs/design/사용자권한-DOMAIN-001 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-001 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 3건 / 스코프 밖 40건
  ℹ️  api_endpoint: 이번 키트 8건 / 스코프 밖 173건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 18건
  ℹ️  diagram_sequence: 이번 키트 1건 / 스코프 밖 24건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 7건 / 스코프 밖 25건
  ℹ️  use_case: 이번 키트 1건 / 스코프 밖 24건 (그중 domain_id 없음 2건)
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  🚨 acceptance: 이번 키트 0건 / 프로젝트 전역 25건 (그중 domain_id 없음 24건) — 전량 누락
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 8건 / 스코프 밖 33건 (그중 domain_id 없음 5건)
  🚨 feature: 이번 키트 0건 / 프로젝트 전역 9건 (그중 domain_id 없음 9건) — 전량 누락
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[ADR-001]]
- NEW [[ADR-003]]
- NEW [[ADR-004]]
- NEW [[ADR-012]]
- NEW [[ADR-021]]
- NEW [[ADR-039]]
- NEW [[ADR-042]]
- NEW [[ADR-043]]
- NEW [[API-001]]
- NEW [[API-002]]
- NEW [[API-003]]
- NEW [[API-004]]
- NEW [[API-005]]
- NEW [[API-006]]
- NEW [[API-007]]
- NEW [[API-153]]
- NEW [[CDIAG-008]]
- NEW [[SEQ-018]]
- NEW [[DOMAIN-001]]
- NEW [[DFEAT-001]]
- NEW [[DFEAT-002]]
- NEW [[DFEAT-003]]
- NEW [[ERD-029]]
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
- NEW [[SD-009]]
- NEW [[SD-017]]
- NEW [[SD-018]]
- NEW [[SD-019]]
- NEW [[SD-020]]
- NEW [[SCREEN-001]]
- NEW [[SCREEN-002]]
- NEW [[SCREEN-003]]
- NEW [[SCREEN-004]]
- NEW [[SCREEN-012]]
- NEW [[SCREEN-020]]
- NEW [[SCREEN-024]]
- NEW [[UC-030]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[ADR-001]] | adr | 2 | NEW |
| [[ADR-003]] | adr | 2 | NEW |
| [[ADR-004]] | adr | 4 | NEW |
| [[ADR-012]] | adr | 5 | NEW |
| [[ADR-021]] | adr | 4 | NEW |
| [[ADR-039]] | adr | 2 | NEW |
| [[ADR-042]] | adr | 5 | NEW |
| [[ADR-043]] | adr | 6 | NEW |
| [[API-001]] | api_endpoint | 3 | NEW |
| [[API-002]] | api_endpoint | 2 | NEW |
| [[API-003]] | api_endpoint | 4 | NEW |
| [[API-004]] | api_endpoint | 5 | NEW |
| [[API-005]] | api_endpoint | 5 | NEW |
| [[API-006]] | api_endpoint | 8 | NEW |
| [[API-007]] | api_endpoint | 6 | NEW |
| [[API-153]] | api_endpoint | 4 | NEW |
| [[CDIAG-008]] | class_diagram | 4 | NEW |
| [[DFEAT-001]] | domain_feature | 3 | NEW |
| [[DFEAT-002]] | domain_feature | 6 | NEW |
| [[DFEAT-003]] | domain_feature | 7 | NEW |
| [[DOMAIN-001]] | domain | 9 | NEW |
| [[ERD-029]] | erd | 1 | NEW |
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
| [[SCREEN-001]] | screen_spec | 13 | NEW |
| [[SCREEN-002]] | screen_spec | 17 | NEW |
| [[SCREEN-003]] | screen_spec | 10 | NEW |
| [[SCREEN-004]] | screen_spec | 11 | NEW |
| [[SCREEN-012]] | screen_spec | 39 | NEW |
| [[SCREEN-020]] | screen_spec | 28 | NEW |
| [[SCREEN-024]] | screen_spec | 22 | NEW |
| [[SD-009]] | screen_design | 7 | NEW |
| [[SD-017]] | screen_design | 2 | NEW |
| [[SD-018]] | screen_design | 4 | NEW |
| [[SD-019]] | screen_design | 2 | NEW |
| [[SD-020]] | screen_design | 2 | NEW |
| [[SEQ-018]] | diagram_sequence | 1 | NEW |
| [[UC-030]] | use_case | 7 | NEW |
