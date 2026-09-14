# Version Master — DOMAIN-017

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-017 |
| Last sync | 2026-09-14T05:35:32.568Z |
| Mode | SYNC — NEW 1 / CHANGED 0 / UNCHANGED 106 |
| 출력 루트 | docs/design/외부-산출물-이관-DOMAIN-017 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-017 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 23건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 5건 / 스코프 밖 44건
  ℹ️  api_endpoint: 이번 키트 13건 / 스코프 밖 212건
  ℹ️  erd: 이번 키트 7건 / 스코프 밖 18건
  ℹ️  diagram_sequence: 이번 키트 3건 / 스코프 밖 33건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 2건 / 스코프 밖 36건
  ℹ️  use_case: 이번 키트 4건 / 스코프 밖 31건
  ℹ️  domain_event: 이번 키트 2건 / 스코프 밖 10건
  ℹ️  acceptance: 이번 키트 7건 / 스코프 밖 87건 (그중 domain_id 없음 9건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 9건 / 스코프 밖 50건 (그중 domain_id 없음 11건)
  ℹ️  feature: 이번 키트 1건 / 스코프 밖 13건 (그중 domain_id 없음 13건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[ADR-066]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-1032]] | acceptance | 9 | UNCHANGED |
| [[AC-1033]] | acceptance | 11 | UNCHANGED |
| [[AC-1079]] | acceptance | 9 | UNCHANGED |
| [[AC-1080]] | acceptance | 7 | UNCHANGED |
| [[AC-1081]] | acceptance | 7 | UNCHANGED |
| [[AC-1082]] | acceptance | 7 | UNCHANGED |
| [[AC-1083]] | acceptance | 5 | UNCHANGED |
| [[ADR-023]] | adr | 9 | UNCHANGED |
| [[ADR-042]] | adr | 7 | UNCHANGED |
| [[ADR-048]] | adr | 6 | UNCHANGED |
| [[ADR-052]] | adr | 3 | UNCHANGED |
| [[ADR-053]] | adr | 3 | UNCHANGED |
| [[ADR-055]] | adr | 6 | UNCHANGED |
| [[ADR-058]] | adr | 10 | UNCHANGED |
| [[ADR-065]] | adr | 1 | UNCHANGED |
| [[ADR-066]] | adr | 2 | NEW |
| [[API-205]] | api_endpoint | 10 | UNCHANGED |
| [[API-206]] | api_endpoint | 15 | UNCHANGED |
| [[API-207]] | api_endpoint | 7 | UNCHANGED |
| [[API-208]] | api_endpoint | 5 | UNCHANGED |
| [[API-209]] | api_endpoint | 7 | UNCHANGED |
| [[API-210]] | api_endpoint | 11 | UNCHANGED |
| [[API-211]] | api_endpoint | 8 | UNCHANGED |
| [[API-215]] | api_endpoint | 6 | UNCHANGED |
| [[API-216]] | api_endpoint | 7 | UNCHANGED |
| [[API-217]] | api_endpoint | 8 | UNCHANGED |
| [[API-218]] | api_endpoint | 6 | UNCHANGED |
| [[API-221]] | api_endpoint | 19 | UNCHANGED |
| [[API-222]] | api_endpoint | 13 | UNCHANGED |
| [[CDIAG-027]] | class_diagram | 1 | UNCHANGED |
| [[CDIAG-034]] | class_diagram | 1 | UNCHANGED |
| [[CDIAG-045]] | class_diagram | 1 | UNCHANGED |
| [[DFEAT-056]] | domain_feature | 5 | UNCHANGED |
| [[DFEAT-057]] | domain_feature | 14 | UNCHANGED |
| [[DFEAT-058]] | domain_feature | 5 | UNCHANGED |
| [[DFEAT-059]] | domain_feature | 6 | UNCHANGED |
| [[DFEAT-060]] | domain_feature | 4 | UNCHANGED |
| [[DOMAIN-017]] | domain | 1 | UNCHANGED |
| [[ERD-010]] | erd | 37 | UNCHANGED |
| [[ERD-012]] | erd | 52 | UNCHANGED |
| [[ERD-017]] | erd | 26 | UNCHANGED |
| [[ERD-019]] | erd | 24 | UNCHANGED |
| [[ERD-025]] | erd | 4 | UNCHANGED |
| [[ERD-031]] | erd | 16 | UNCHANGED |
| [[ERD-032]] | erd | 4 | UNCHANGED |
| [[EVT-001]] | domain_event | 5 | UNCHANGED |
| [[EVT-005]] | domain_event | 6 | UNCHANGED |
| [[FEAT-010]] | feature | 4 | UNCHANGED |
| [[INT-004]] | integration_point | 16 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 6 | UNCHANGED |
| [[NFR-010]] | nfr | 4 | UNCHANGED |
| [[NFR-011]] | nfr | 7 | UNCHANGED |
| [[NFR-012]] | nfr | 6 | UNCHANGED |
| [[NFR-013]] | nfr | 13 | UNCHANGED |
| [[NFR-014]] | nfr | 5 | UNCHANGED |
| [[NFR-015]] | nfr | 6 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 10 | UNCHANGED |
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
| [[ROLE-001]] | permission_role | 14 | UNCHANGED |
| [[ROLE-002]] | permission_role | 10 | UNCHANGED |
| [[ROLE-003]] | permission_role | 15 | UNCHANGED |
| [[ROLE-004]] | permission_role | 5 | UNCHANGED |
| [[SCREEN-032]] | screen_spec | 31 | UNCHANGED |
| [[SCREEN-039]] | screen_spec | 45 | UNCHANGED |
| [[SD-021]] | screen_design | 9 | UNCHANGED |
| [[SD-038]] | screen_design | 3 | UNCHANGED |
| [[SEQ-026]] | diagram_sequence | 17 | UNCHANGED |
| [[SEQ-029]] | diagram_sequence | 2 | UNCHANGED |
| [[SEQ-030]] | diagram_sequence | 10 | UNCHANGED |
| [[TEST-007]] | test_scenario | 5 | UNCHANGED |
| [[TEST-008]] | test_scenario | 4 | UNCHANGED |
| [[UC-018]] | use_case | 28 | UNCHANGED |
| [[UC-035]] | use_case | 20 | UNCHANGED |
| [[UC-036]] | use_case | 9 | UNCHANGED |
| [[UC-037]] | use_case | 16 | UNCHANGED |
