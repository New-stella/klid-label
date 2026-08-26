# Version Master — DOMAIN-007

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-007 |
| Last sync | 2026-08-26T04:11:45.502Z |
| Mode | SYNC — NEW 0 / CHANGED 18 / UNCHANGED 54 |
| 출력 루트 | docs/design/데이터-증강내보내기-DOMAIN-007 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-007 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 7건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 2건 / 스코프 밖 46건
  ℹ️  api_endpoint: 이번 키트 13건 / 스코프 밖 188건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 22건
  ℹ️  diagram_sequence: 이번 키트 4건 / 스코프 밖 22건 (그중 domain_id 없음 10건)
  ℹ️  screen_spec: 이번 키트 2건 / 스코프 밖 31건
  ℹ️  use_case: 이번 키트 4건 / 스코프 밖 26건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 5건 / 스코프 밖 112건 (그중 domain_id 없음 32건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 10건 / 스코프 밖 39건 (그중 domain_id 없음 5건)
  ℹ️  feature: 이번 키트 2건 / 스코프 밖 8건 (그중 domain_id 없음 7건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[AC-003]] (prev v7)
- CHANGED [[ADR-018]] (prev v3)
- CHANGED [[API-092]] (prev v7)
- CHANGED [[API-179]] (prev v5)
- CHANGED [[CDIAG-010]] (prev v9)
- CHANGED [[SEQ-003]] (prev v9)
- CHANGED [[SEQ-004]] (prev v7)
- CHANGED [[SEQ-011]] (prev v7)
- CHANGED [[DOMAIN-007]] (prev v15)
- CHANGED [[DFEAT-029]] (prev v12)
- CHANGED [[ERD-011]] (prev v22)
- CHANGED [[FEAT-004]] (prev v9)
- CHANGED [[INTSPEC-006]] (prev v1)
- CHANGED [[ROLE-003]] (prev v7)
- CHANGED [[SD-029]] (prev v4)
- CHANGED [[SCREEN-022]] (prev v40)
- CHANGED [[UC-002]] (prev v13)
- CHANGED [[UC-003]] (prev v11)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-001]] | acceptance | 11 | UNCHANGED |
| [[AC-002]] | acceptance | 8 | UNCHANGED |
| [[AC-003]] | acceptance | 12 | CHANGED |
| [[AC-018]] | acceptance | 6 | UNCHANGED |
| [[AC-021]] | acceptance | 5 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-003]] | adr | 2 | UNCHANGED |
| [[ADR-004]] | adr | 4 | UNCHANGED |
| [[ADR-018]] | adr | 5 | CHANGED |
| [[ADR-020]] | adr | 10 | UNCHANGED |
| [[ADR-022]] | adr | 5 | UNCHANGED |
| [[ADR-023]] | adr | 7 | UNCHANGED |
| [[ADR-031]] | adr | 2 | UNCHANGED |
| [[ADR-044]] | adr | 3 | UNCHANGED |
| [[ADR-045]] | adr | 5 | UNCHANGED |
| [[API-042]] | api_endpoint | 8 | UNCHANGED |
| [[API-059]] | api_endpoint | 6 | UNCHANGED |
| [[API-060]] | api_endpoint | 14 | UNCHANGED |
| [[API-061]] | api_endpoint | 10 | UNCHANGED |
| [[API-062]] | api_endpoint | 7 | UNCHANGED |
| [[API-063]] | api_endpoint | 9 | UNCHANGED |
| [[API-092]] | api_endpoint | 10 | CHANGED |
| [[API-165]] | api_endpoint | 6 | UNCHANGED |
| [[API-175]] | api_endpoint | 2 | UNCHANGED |
| [[API-179]] | api_endpoint | 9 | CHANGED |
| [[API-188]] | api_endpoint | 2 | UNCHANGED |
| [[API-189]] | api_endpoint | 2 | UNCHANGED |
| [[API-190]] | api_endpoint | 6 | UNCHANGED |
| [[CDIAG-010]] | class_diagram | 11 | CHANGED |
| [[CMP-007]] | diagram_c4_component | 6 | UNCHANGED |
| [[DFEAT-029]] | domain_feature | 14 | CHANGED |
| [[DFEAT-030]] | domain_feature | 3 | UNCHANGED |
| [[DOMAIN-007]] | domain | 16 | CHANGED |
| [[ERD-011]] | erd | 24 | CHANGED |
| [[EVT-011]] | domain_event | 4 | UNCHANGED |
| [[EXTSYS-004]] | external_system | 8 | UNCHANGED |
| [[FEAT-001]] | feature | 9 | UNCHANGED |
| [[FEAT-004]] | feature | 10 | CHANGED |
| [[INT-006]] | integration_point | 11 | UNCHANGED |
| [[INT-008]] | integration_point | 13 | UNCHANGED |
| [[INTSPEC-005]] | integration_spec | 1 | UNCHANGED |
| [[INTSPEC-006]] | integration_spec | 2 | CHANGED |
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
| [[ROLE-003]] | permission_role | 8 | CHANGED |
| [[SCREEN-022]] | screen_spec | 43 | CHANGED |
| [[SCREEN-023]] | screen_spec | 41 | UNCHANGED |
| [[SD-028]] | screen_design | 3 | UNCHANGED |
| [[SD-029]] | screen_design | 5 | CHANGED |
| [[SEQ-002]] | diagram_sequence | 7 | UNCHANGED |
| [[SEQ-003]] | diagram_sequence | 10 | CHANGED |
| [[SEQ-004]] | diagram_sequence | 9 | CHANGED |
| [[SEQ-011]] | diagram_sequence | 8 | CHANGED |
| [[TEST-003]] | test_scenario | 15 | UNCHANGED |
| [[UC-001]] | use_case | 12 | UNCHANGED |
| [[UC-002]] | use_case | 14 | CHANGED |
| [[UC-003]] | use_case | 14 | CHANGED |
| [[UC-010]] | use_case | 13 | UNCHANGED |
