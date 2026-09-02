# Version Master — DOMAIN-016

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-016 |
| Last sync | 2026-09-02T10:52:42.486Z |
| Mode | SYNC — NEW 0 / CHANGED 15 / UNCHANGED 50 |
| 출력 루트 | docs/design/관제-통지-DOMAIN-016 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-016 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 4건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 7건 / 스코프 밖 41건
  ℹ️  api_endpoint: 이번 키트 3건 / 스코프 밖 217건
  ℹ️  erd: 이번 키트 2건 / 스코프 밖 21건
  ℹ️  diagram_sequence: 이번 키트 5건 / 스코프 밖 21건 (그중 domain_id 없음 13건)
  ℹ️  screen_spec: 이번 키트 1건 / 스코프 밖 37건
  ℹ️  use_case: 이번 키트 1건 / 스코프 밖 34건 (그중 domain_id 없음 3건)
  ℹ️  domain_event: 이번 키트 5건 / 스코프 밖 7건
  ℹ️  acceptance: 이번 키트 2건 / 스코프 밖 78건 (그중 domain_id 없음 2건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 10건 / 스코프 밖 42건 (그중 domain_id 없음 7건)
  ℹ️  feature: 이번 키트 1건 / 스코프 밖 9건 (그중 domain_id 없음 8건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[ADR-002]] (prev v2)
- CHANGED [[ADR-013]] (prev v13)
- CHANGED [[ADR-020]] (prev v10)
- CHANGED [[ADR-046]] (prev v10)
- CHANGED [[ADR-055]] (prev v3)
- CHANGED [[CMP-009]] (prev v16)
- CHANGED [[DFEAT-006]] (prev v8)
- CHANGED [[DFEAT-044]] (prev v13)
- CHANGED [[DFEAT-053]] (prev v9)
- CHANGED [[NFR-009]] (prev v4)
- CHANGED [[NFR-017]] (prev v9)
- CHANGED [[NFR-020]] (prev v9)
- CHANGED [[ROLE-001]] (prev v13)
- CHANGED [[ROLE-003]] (prev v12)
- CHANGED [[ROLE-004]] (prev v4)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-1077]] | acceptance | 6 | UNCHANGED |
| [[AC-1078]] | acceptance | 8 | UNCHANGED |
| [[ADR-002]] | adr | 3 | CHANGED |
| [[ADR-007]] | adr | 3 | UNCHANGED |
| [[ADR-012]] | adr | 10 | UNCHANGED |
| [[ADR-013]] | adr | 20 | CHANGED |
| [[ADR-020]] | adr | 11 | CHANGED |
| [[ADR-029]] | adr | 2 | UNCHANGED |
| [[ADR-033]] | adr | 3 | UNCHANGED |
| [[ADR-037]] | adr | 3 | UNCHANGED |
| [[ADR-046]] | adr | 13 | CHANGED |
| [[ADR-055]] | adr | 5 | CHANGED |
| [[API-074]] | api_endpoint | 7 | UNCHANGED |
| [[API-075]] | api_endpoint | 8 | UNCHANGED |
| [[API-076]] | api_endpoint | 9 | UNCHANGED |
| [[CDIAG-013]] | class_diagram | 9 | UNCHANGED |
| [[CMP-009]] | diagram_c4_component | 19 | CHANGED |
| [[DFEAT-006]] | domain_feature | 9 | CHANGED |
| [[DFEAT-043]] | domain_feature | 16 | UNCHANGED |
| [[DFEAT-044]] | domain_feature | 16 | CHANGED |
| [[DFEAT-046]] | domain_feature | 11 | UNCHANGED |
| [[DFEAT-047]] | domain_feature | 7 | UNCHANGED |
| [[DFEAT-053]] | domain_feature | 19 | CHANGED |
| [[DFEAT-054]] | domain_feature | 3 | UNCHANGED |
| [[DOMAIN-016]] | domain | 12 | UNCHANGED |
| [[ERD-021]] | erd | 15 | UNCHANGED |
| [[ERD-027]] | erd | 6 | UNCHANGED |
| [[EVT-003]] | domain_event | 6 | UNCHANGED |
| [[EVT-004]] | domain_event | 10 | UNCHANGED |
| [[EVT-006]] | domain_event | 8 | UNCHANGED |
| [[EVT-009]] | domain_event | 4 | UNCHANGED |
| [[EVT-010]] | domain_event | 4 | UNCHANGED |
| [[EXTSYS-005]] | external_system | 15 | UNCHANGED |
| [[FEAT-003]] | feature | 11 | UNCHANGED |
| [[INT-007]] | integration_point | 11 | UNCHANGED |
| [[INT-010]] | integration_point | 12 | UNCHANGED |
| [[INT-011]] | integration_point | 2 | UNCHANGED |
| [[INTSPEC-004]] | integration_spec | 11 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 6 | CHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 6 | UNCHANGED |
| [[NFR-012]] | nfr | 5 | UNCHANGED |
| [[NFR-013]] | nfr | 10 | UNCHANGED |
| [[NFR-014]] | nfr | 4 | UNCHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 10 | CHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 10 | CHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[NFR-022]] | nfr | 2 | UNCHANGED |
| [[ROLE-001]] | permission_role | 14 | CHANGED |
| [[ROLE-002]] | permission_role | 9 | UNCHANGED |
| [[ROLE-003]] | permission_role | 14 | CHANGED |
| [[ROLE-004]] | permission_role | 5 | CHANGED |
| [[SCREEN-019]] | screen_spec | 43 | UNCHANGED |
| [[SEQ-010]] | diagram_sequence | 15 | UNCHANGED |
| [[SEQ-015]] | diagram_sequence | 4 | UNCHANGED |
| [[SEQ-020]] | diagram_sequence | 2 | UNCHANGED |
| [[SEQ-023]] | diagram_sequence | 9 | UNCHANGED |
| [[SEQ-025]] | diagram_sequence | 5 | UNCHANGED |
| [[TEST-004]] | test_scenario | 19 | UNCHANGED |
| [[UC-009]] | use_case | 22 | UNCHANGED |
