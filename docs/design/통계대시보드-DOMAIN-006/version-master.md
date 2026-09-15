# Version Master — DOMAIN-006

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-006 |
| Last sync | 2026-09-15T13:24:11.147Z |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 76 |
| 출력 루트 | docs/design/통계대시보드-DOMAIN-006 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-006 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 19건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 3건 / 스코프 밖 46건
  ℹ️  api_endpoint: 이번 키트 7건 / 스코프 밖 227건
  🚨 erd: 이번 키트 0건 / 프로젝트 전역 25건 — 전량 누락
  ℹ️  diagram_sequence: 이번 키트 1건 / 스코프 밖 35건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 3건 / 스코프 밖 36건
  ℹ️  use_case: 이번 키트 1건 / 스코프 밖 34건
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  ℹ️  acceptance: 이번 키트 3건 / 스코프 밖 105건 (그중 domain_id 없음 10건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 4건 / 스코프 밖 57건 (그중 domain_id 없음 12건)
  ℹ️  feature: 이번 키트 1건 / 스코프 밖 13건 (그중 domain_id 없음 12건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- (변경 없음)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-1043]] | acceptance | 4 | UNCHANGED |
| [[AC-1084]] | acceptance | 3 | UNCHANGED |
| [[AC-1085]] | acceptance | 4 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-019]] | adr | 8 | UNCHANGED |
| [[ADR-038]] | adr | 4 | UNCHANGED |
| [[ADR-055]] | adr | 6 | UNCHANGED |
| [[API-001]] | api_endpoint | 10 | UNCHANGED |
| [[API-042]] | api_endpoint | 8 | UNCHANGED |
| [[API-055]] | api_endpoint | 6 | UNCHANGED |
| [[API-056]] | api_endpoint | 7 | UNCHANGED |
| [[API-057]] | api_endpoint | 6 | UNCHANGED |
| [[API-058]] | api_endpoint | 4 | UNCHANGED |
| [[API-072]] | api_endpoint | 11 | UNCHANGED |
| [[CDIAG-009]] | class_diagram | 6 | UNCHANGED |
| [[CDIAG-020]] | class_diagram | 1 | UNCHANGED |
| [[CDIAG-038]] | class_diagram | 1 | UNCHANGED |
| [[DFEAT-026]] | domain_feature | 3 | UNCHANGED |
| [[DFEAT-027]] | domain_feature | 4 | UNCHANGED |
| [[DFEAT-028]] | domain_feature | 4 | UNCHANGED |
| [[DOMAIN-006]] | domain | 10 | UNCHANGED |
| [[FEAT-014]] | feature | 1 | UNCHANGED |
| [[NFR-008]] | nfr | 7 | UNCHANGED |
| [[NFR-009]] | nfr | 6 | UNCHANGED |
| [[NFR-010]] | nfr | 4 | UNCHANGED |
| [[NFR-011]] | nfr | 8 | UNCHANGED |
| [[NFR-012]] | nfr | 6 | UNCHANGED |
| [[NFR-013]] | nfr | 14 | UNCHANGED |
| [[NFR-014]] | nfr | 5 | UNCHANGED |
| [[NFR-015]] | nfr | 6 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 11 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 3 | UNCHANGED |
| [[NFR-020]] | nfr | 11 | UNCHANGED |
| [[NFR-021]] | nfr | 2 | UNCHANGED |
| [[NFR-022]] | nfr | 3 | UNCHANGED |
| [[NFR-023]] | nfr | 1 | UNCHANGED |
| [[NFR-024]] | nfr | 1 | UNCHANGED |
| [[NFR-025]] | nfr | 1 | UNCHANGED |
| [[NFR-026]] | nfr | 1 | UNCHANGED |
| [[NFR-027]] | nfr | 1 | UNCHANGED |
| [[NFR-028]] | nfr | 1 | UNCHANGED |
| [[NFR-029]] | nfr | 1 | UNCHANGED |
| [[NFR-030]] | nfr | 1 | UNCHANGED |
| [[NFR-031]] | nfr | 1 | UNCHANGED |
| [[NFR-032]] | nfr | 1 | UNCHANGED |
| [[NFR-033]] | nfr | 1 | UNCHANGED |
| [[NFR-034]] | nfr | 1 | UNCHANGED |
| [[NFR-035]] | nfr | 1 | UNCHANGED |
| [[NFR-036]] | nfr | 1 | UNCHANGED |
| [[NFR-037]] | nfr | 1 | UNCHANGED |
| [[NFR-038]] | nfr | 1 | UNCHANGED |
| [[NFR-039]] | nfr | 1 | UNCHANGED |
| [[NFR-040]] | nfr | 1 | UNCHANGED |
| [[NFR-041]] | nfr | 1 | UNCHANGED |
| [[NFR-042]] | nfr | 1 | UNCHANGED |
| [[NFR-043]] | nfr | 1 | UNCHANGED |
| [[NFR-044]] | nfr | 1 | UNCHANGED |
| [[NFR-045]] | nfr | 1 | UNCHANGED |
| [[NFR-046]] | nfr | 1 | UNCHANGED |
| [[NFR-047]] | nfr | 1 | UNCHANGED |
| [[NFR-048]] | nfr | 1 | UNCHANGED |
| [[ROLE-001]] | permission_role | 15 | UNCHANGED |
| [[ROLE-002]] | permission_role | 10 | UNCHANGED |
| [[ROLE-003]] | permission_role | 17 | UNCHANGED |
| [[ROLE-004]] | permission_role | 6 | UNCHANGED |
| [[SCREEN-011]] | screen_spec | 21 | UNCHANGED |
| [[SCREEN-020]] | screen_spec | 32 | UNCHANGED |
| [[SCREEN-021]] | screen_spec | 29 | UNCHANGED |
| [[SD-014]] | screen_design | 5 | UNCHANGED |
| [[SD-030]] | screen_design | 5 | UNCHANGED |
| [[SD-031]] | screen_design | 3 | UNCHANGED |
| [[SEQ-027]] | diagram_sequence | 2 | UNCHANGED |
| [[TEST-006]] | test_scenario | 3 | UNCHANGED |
| [[UC-033]] | use_case | 8 | UNCHANGED |
