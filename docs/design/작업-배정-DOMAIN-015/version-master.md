# Version Master — DOMAIN-015

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-015 |
| Last sync | 2026-08-16T14:48:57.908Z |
| Mode | INITIAL — NEW 42 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | docs/design/작업-배정-DOMAIN-015 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-015 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 1건 / 스코프 밖 42건
  ℹ️  api_endpoint: 이번 키트 10건 / 스코프 밖 171건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 18건
  ℹ️  diagram_sequence: 이번 키트 1건 / 스코프 밖 24건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 3건 / 스코프 밖 29건
  ℹ️  use_case: 이번 키트 1건 / 스코프 밖 24건 (그중 domain_id 없음 2건)
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  🚨 acceptance: 이번 키트 0건 / 프로젝트 전역 25건 (그중 domain_id 없음 24건) — 전량 누락
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 3건 / 스코프 밖 38건 (그중 domain_id 없음 5건)
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
- NEW [[ADR-038]]
- NEW [[API-001]]
- NEW [[API-002]]
- NEW [[API-070]]
- NEW [[API-071]]
- NEW [[API-072]]
- NEW [[API-073]]
- NEW [[API-116]]
- NEW [[API-136]]
- NEW [[API-137]]
- NEW [[API-187]]
- NEW [[CDIAG-007]]
- NEW [[CMP-009]]
- NEW [[SEQ-017]]
- NEW [[STATE-001]]
- NEW [[DOMAIN-015]]
- NEW [[DFEAT-006]]
- NEW [[ERD-014]]
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
- NEW [[SD-003]]
- NEW [[SCREEN-008]]
- NEW [[SCREEN-011]]
- NEW [[SCREEN-012]]
- NEW [[UC-029]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[ADR-001]] | adr | 2 | NEW |
| [[ADR-003]] | adr | 2 | NEW |
| [[ADR-038]] | adr | 4 | NEW |
| [[API-001]] | api_endpoint | 3 | NEW |
| [[API-002]] | api_endpoint | 2 | NEW |
| [[API-070]] | api_endpoint | 8 | NEW |
| [[API-071]] | api_endpoint | 7 | NEW |
| [[API-072]] | api_endpoint | 10 | NEW |
| [[API-073]] | api_endpoint | 9 | NEW |
| [[API-116]] | api_endpoint | 8 | NEW |
| [[API-136]] | api_endpoint | 4 | NEW |
| [[API-137]] | api_endpoint | 6 | NEW |
| [[API-187]] | api_endpoint | 2 | NEW |
| [[CDIAG-007]] | class_diagram | 7 | NEW |
| [[CMP-009]] | diagram_c4_component | 11 | NEW |
| [[DFEAT-006]] | domain_feature | 8 | NEW |
| [[DOMAIN-015]] | domain | 6 | NEW |
| [[ERD-014]] | erd | 12 | NEW |
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
| [[SCREEN-008]] | screen_spec | 35 | NEW |
| [[SCREEN-011]] | screen_spec | 18 | NEW |
| [[SCREEN-012]] | screen_spec | 39 | NEW |
| [[SD-003]] | screen_design | 5 | NEW |
| [[SEQ-017]] | diagram_sequence | 1 | NEW |
| [[STATE-001]] | diagram_state | 6 | NEW |
| [[UC-029]] | use_case | 9 | NEW |
