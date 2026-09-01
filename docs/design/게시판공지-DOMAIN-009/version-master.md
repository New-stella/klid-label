# Version Master — DOMAIN-009

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-009 |
| Last sync | 2026-09-01T12:37:40.807Z |
| Mode | SYNC — NEW 0 / CHANGED 1 / UNCHANGED 45 |
| 출력 루트 | 게시판공지-DOMAIN-009 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-009 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 18건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 2건 / 스코프 밖 46건
  ℹ️  api_endpoint: 이번 키트 10건 / 스코프 밖 194건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 22건
  🚨 diagram_sequence: 이번 키트 0건 / 프로젝트 전역 26건 (그중 domain_id 없음 14건) — 전량 누락
  ℹ️  screen_spec: 이번 키트 4건 / 스코프 밖 33건
  ℹ️  use_case: 이번 키트 1건 / 스코프 밖 34건 (그중 domain_id 없음 3건)
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  ℹ️  acceptance: 이번 키트 2건 / 스코프 밖 73건 (그중 domain_id 없음 2건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 2건 / 스코프 밖 48건 (그중 domain_id 없음 8건)
  🚨 feature: 이번 키트 0건 / 프로젝트 전역 10건 (그중 domain_id 없음 9건) — 전량 누락
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[ROLE-003]] (prev v10)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-1050]] | acceptance | 7 | UNCHANGED |
| [[AC-1051]] | acceptance | 5 | UNCHANGED |
| [[ADR-014]] | adr | 5 | UNCHANGED |
| [[ADR-055]] | adr | 3 | UNCHANGED |
| [[API-095]] | api_endpoint | 6 | UNCHANGED |
| [[API-096]] | api_endpoint | 7 | UNCHANGED |
| [[API-097]] | api_endpoint | 7 | UNCHANGED |
| [[API-098]] | api_endpoint | 7 | UNCHANGED |
| [[API-099]] | api_endpoint | 6 | UNCHANGED |
| [[API-100]] | api_endpoint | 8 | UNCHANGED |
| [[API-101]] | api_endpoint | 8 | UNCHANGED |
| [[API-106]] | api_endpoint | 9 | UNCHANGED |
| [[API-107]] | api_endpoint | 7 | UNCHANGED |
| [[API-108]] | api_endpoint | 6 | UNCHANGED |
| [[DFEAT-037]] | domain_feature | 11 | UNCHANGED |
| [[DFEAT-038]] | domain_feature | 15 | UNCHANGED |
| [[DOMAIN-009]] | domain | 13 | UNCHANGED |
| [[ERD-022]] | erd | 17 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 4 | UNCHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 6 | UNCHANGED |
| [[NFR-012]] | nfr | 5 | UNCHANGED |
| [[NFR-013]] | nfr | 10 | UNCHANGED |
| [[NFR-014]] | nfr | 4 | UNCHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 9 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 9 | UNCHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[NFR-022]] | nfr | 2 | UNCHANGED |
| [[ROLE-001]] | permission_role | 13 | UNCHANGED |
| [[ROLE-002]] | permission_role | 9 | UNCHANGED |
| [[ROLE-003]] | permission_role | 12 | CHANGED |
| [[ROLE-004]] | permission_role | 4 | UNCHANGED |
| [[SCREEN-030]] | screen_spec | 26 | UNCHANGED |
| [[SCREEN-031]] | screen_spec | 33 | UNCHANGED |
| [[SCREEN-036]] | screen_spec | 8 | UNCHANGED |
| [[SCREEN-037]] | screen_spec | 8 | UNCHANGED |
| [[SD-007]] | screen_design | 6 | UNCHANGED |
| [[SD-008]] | screen_design | 4 | UNCHANGED |
| [[SD-010]] | screen_design | 10 | UNCHANGED |
| [[SD-011]] | screen_design | 6 | UNCHANGED |
| [[UC-040]] | use_case | 4 | UNCHANGED |
