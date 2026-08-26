# Version Master — DOMAIN-016

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-016 |
| Last sync | 2026-08-26T05:51:29.621Z |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 60 |
| 출력 루트 | docs/design/관제-통지-DOMAIN-016 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-016 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 6건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 7건 / 스코프 밖 41건
  ℹ️  api_endpoint: 이번 키트 3건 / 스코프 밖 198건
  ℹ️  erd: 이번 키트 2건 / 스코프 밖 21건
  ℹ️  diagram_sequence: 이번 키트 5건 / 스코프 밖 21건 (그중 domain_id 없음 13건)
  ℹ️  screen_spec: 이번 키트 1건 / 스코프 밖 32건
  ℹ️  use_case: 이번 키트 1건 / 스코프 밖 29건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 4건 / 스코프 밖 8건
  ℹ️  acceptance: 이번 키트 1건 / 스코프 밖 116건 (그중 domain_id 없음 36건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 9건 / 스코프 밖 40건 (그중 domain_id 없음 6건)
  ℹ️  feature: 이번 키트 1건 / 스코프 밖 9건 (그중 domain_id 없음 8건)
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
| [[AC-009]] | acceptance | 16 | UNCHANGED |
| [[ADR-002]] | adr | 2 | UNCHANGED |
| [[ADR-003]] | adr | 2 | UNCHANGED |
| [[ADR-007]] | adr | 3 | UNCHANGED |
| [[ADR-013]] | adr | 10 | UNCHANGED |
| [[ADR-020]] | adr | 10 | UNCHANGED |
| [[ADR-029]] | adr | 2 | UNCHANGED |
| [[ADR-033]] | adr | 3 | UNCHANGED |
| [[ADR-037]] | adr | 3 | UNCHANGED |
| [[ADR-046]] | adr | 1 | UNCHANGED |
| [[API-074]] | api_endpoint | 7 | UNCHANGED |
| [[API-075]] | api_endpoint | 8 | UNCHANGED |
| [[API-076]] | api_endpoint | 9 | UNCHANGED |
| [[CDIAG-013]] | class_diagram | 7 | UNCHANGED |
| [[CMP-009]] | diagram_c4_component | 14 | UNCHANGED |
| [[DFEAT-006]] | domain_feature | 8 | UNCHANGED |
| [[DFEAT-043]] | domain_feature | 12 | UNCHANGED |
| [[DFEAT-044]] | domain_feature | 12 | UNCHANGED |
| [[DFEAT-046]] | domain_feature | 9 | UNCHANGED |
| [[DFEAT-047]] | domain_feature | 6 | UNCHANGED |
| [[DFEAT-053]] | domain_feature | 8 | UNCHANGED |
| [[DFEAT-054]] | domain_feature | 3 | UNCHANGED |
| [[DOMAIN-016]] | domain | 12 | UNCHANGED |
| [[ERD-021]] | erd | 14 | UNCHANGED |
| [[ERD-027]] | erd | 6 | UNCHANGED |
| [[EVT-003]] | domain_event | 6 | UNCHANGED |
| [[EVT-004]] | domain_event | 10 | UNCHANGED |
| [[EVT-009]] | domain_event | 4 | UNCHANGED |
| [[EVT-010]] | domain_event | 4 | UNCHANGED |
| [[EXTSYS-005]] | external_system | 14 | UNCHANGED |
| [[FEAT-003]] | feature | 11 | UNCHANGED |
| [[INT-007]] | integration_point | 11 | UNCHANGED |
| [[INT-010]] | integration_point | 12 | UNCHANGED |
| [[INT-011]] | integration_point | 2 | UNCHANGED |
| [[INTSPEC-004]] | integration_spec | 11 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 4 | UNCHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 5 | UNCHANGED |
| [[NFR-012]] | nfr | 4 | UNCHANGED |
| [[NFR-013]] | nfr | 4 | UNCHANGED |
| [[NFR-014]] | nfr | 3 | UNCHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 8 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 6 | UNCHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[ROLE-001]] | permission_role | 10 | UNCHANGED |
| [[ROLE-002]] | permission_role | 7 | UNCHANGED |
| [[ROLE-003]] | permission_role | 8 | UNCHANGED |
| [[SCREEN-019]] | screen_spec | 39 | UNCHANGED |
| [[SEQ-010]] | diagram_sequence | 14 | UNCHANGED |
| [[SEQ-015]] | diagram_sequence | 4 | UNCHANGED |
| [[SEQ-020]] | diagram_sequence | 1 | UNCHANGED |
| [[SEQ-023]] | diagram_sequence | 7 | UNCHANGED |
| [[SEQ-025]] | diagram_sequence | 1 | UNCHANGED |
| [[TEST-004]] | test_scenario | 19 | UNCHANGED |
| [[UC-009]] | use_case | 20 | UNCHANGED |
