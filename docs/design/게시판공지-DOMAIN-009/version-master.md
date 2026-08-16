# Version Master — DOMAIN-009

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-009 |
| Last sync | 2026-08-16T14:48:53.629Z |
| Mode | INITIAL — NEW 41 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | docs/design/게시판공지-DOMAIN-009 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-009 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 2건 / 스코프 밖 41건
  ℹ️  api_endpoint: 이번 키트 10건 / 스코프 밖 171건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 18건
  🚨 diagram_sequence: 이번 키트 0건 / 프로젝트 전역 25건 (그중 domain_id 없음 14건) — 전량 누락
  ℹ️  screen_spec: 이번 키트 4건 / 스코프 밖 28건
  🚨 use_case: 이번 키트 0건 / 프로젝트 전역 25건 (그중 domain_id 없음 2건) — 전량 누락
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
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
- NEW [[ADR-014]]
- NEW [[API-095]]
- NEW [[API-096]]
- NEW [[API-097]]
- NEW [[API-098]]
- NEW [[API-099]]
- NEW [[API-100]]
- NEW [[API-101]]
- NEW [[API-106]]
- NEW [[API-107]]
- NEW [[API-108]]
- NEW [[DOMAIN-009]]
- NEW [[DFEAT-037]]
- NEW [[DFEAT-038]]
- NEW [[ERD-022]]
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
- NEW [[SD-007]]
- NEW [[SD-008]]
- NEW [[SD-010]]
- NEW [[SD-011]]
- NEW [[SCREEN-030]]
- NEW [[SCREEN-031]]
- NEW [[SCREEN-036]]
- NEW [[SCREEN-037]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[ADR-003]] | adr | 2 | NEW |
| [[ADR-014]] | adr | 5 | NEW |
| [[API-095]] | api_endpoint | 6 | NEW |
| [[API-096]] | api_endpoint | 7 | NEW |
| [[API-097]] | api_endpoint | 7 | NEW |
| [[API-098]] | api_endpoint | 7 | NEW |
| [[API-099]] | api_endpoint | 6 | NEW |
| [[API-100]] | api_endpoint | 8 | NEW |
| [[API-101]] | api_endpoint | 8 | NEW |
| [[API-106]] | api_endpoint | 9 | NEW |
| [[API-107]] | api_endpoint | 7 | NEW |
| [[API-108]] | api_endpoint | 6 | NEW |
| [[DFEAT-037]] | domain_feature | 11 | NEW |
| [[DFEAT-038]] | domain_feature | 15 | NEW |
| [[DOMAIN-009]] | domain | 13 | NEW |
| [[ERD-022]] | erd | 17 | NEW |
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
| [[SCREEN-030]] | screen_spec | 23 | NEW |
| [[SCREEN-031]] | screen_spec | 29 | NEW |
| [[SCREEN-036]] | screen_spec | 8 | NEW |
| [[SCREEN-037]] | screen_spec | 8 | NEW |
| [[SD-007]] | screen_design | 5 | NEW |
| [[SD-008]] | screen_design | 4 | NEW |
| [[SD-010]] | screen_design | 7 | NEW |
| [[SD-011]] | screen_design | 6 | NEW |
