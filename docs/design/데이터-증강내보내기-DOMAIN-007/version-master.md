# Version Master — DOMAIN-007

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-007 |
| Last sync | 2026-09-02T10:51:57.278Z |
| Mode | SYNC — NEW 1 / CHANGED 41 / UNCHANGED 36 |
| 출력 루트 | docs/design/데이터-증강내보내기-DOMAIN-007 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-007 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 21건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 2건 / 스코프 밖 46건
  ℹ️  api_endpoint: 이번 키트 13건 / 스코프 밖 207건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 22건
  ℹ️  diagram_sequence: 이번 키트 4건 / 스코프 밖 22건 (그중 domain_id 없음 10건)
  ℹ️  screen_spec: 이번 키트 2건 / 스코프 밖 36건
  ℹ️  use_case: 이번 키트 4건 / 스코프 밖 31건 (그중 domain_id 없음 3건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 6건 / 스코프 밖 74건 (그중 domain_id 없음 2건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 13건 / 스코프 밖 39건 (그중 domain_id 없음 5건)
  ℹ️  feature: 이번 키트 2건 / 스코프 밖 8건 (그중 domain_id 없음 7건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[ADR-059]]
- CHANGED [[AC-1044]] (prev v4)
- CHANGED [[AC-1046]] (prev v6)
- CHANGED [[ADR-020]] (prev v10)
- CHANGED [[ADR-023]] (prev v7)
- CHANGED [[ADR-045]] (prev v5)
- CHANGED [[ADR-055]] (prev v3)
- CHANGED [[API-059]] (prev v6)
- CHANGED [[API-060]] (prev v16)
- CHANGED [[API-061]] (prev v10)
- CHANGED [[API-062]] (prev v7)
- CHANGED [[API-063]] (prev v9)
- CHANGED [[API-188]] (prev v2)
- CHANGED [[API-189]] (prev v2)
- CHANGED [[API-190]] (prev v6)
- CHANGED [[CDIAG-010]] (prev v12)
- CHANGED [[CMP-007]] (prev v7)
- CHANGED [[SEQ-002]] (prev v10)
- CHANGED [[SEQ-011]] (prev v9)
- CHANGED [[DOMAIN-007]] (prev v16)
- CHANGED [[EVT-011]] (prev v4)
- CHANGED [[DFEAT-029]] (prev v17)
- CHANGED [[ERD-011]] (prev v25)
- CHANGED [[EXTSYS-004]] (prev v9)
- CHANGED [[FEAT-004]] (prev v10)
- CHANGED [[INT-006]] (prev v12)
- CHANGED [[INT-008]] (prev v13)
- CHANGED [[INTSPEC-005]] (prev v1)
- CHANGED [[NFR-009]] (prev v4)
- CHANGED [[NFR-017]] (prev v9)
- CHANGED [[NFR-020]] (prev v9)
- CHANGED [[ROLE-001]] (prev v13)
- CHANGED [[ROLE-003]] (prev v12)
- CHANGED [[ROLE-004]] (prev v4)
- CHANGED [[SD-028]] (prev v4)
- CHANGED [[SD-029]] (prev v5)
- CHANGED [[SCREEN-022]] (prev v46)
- CHANGED [[SCREEN-023]] (prev v41)
- CHANGED [[TEST-003]] (prev v15)
- CHANGED [[UC-001]] (prev v15)
- CHANGED [[UC-002]] (prev v17)
- CHANGED [[UC-010]] (prev v15)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-1044]] | acceptance | 6 | CHANGED |
| [[AC-1045]] | acceptance | 4 | UNCHANGED |
| [[AC-1046]] | acceptance | 8 | CHANGED |
| [[AC-1047]] | acceptance | 6 | UNCHANGED |
| [[AC-1048]] | acceptance | 6 | UNCHANGED |
| [[AC-1049]] | acceptance | 5 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-004]] | adr | 4 | UNCHANGED |
| [[ADR-018]] | adr | 5 | UNCHANGED |
| [[ADR-020]] | adr | 11 | CHANGED |
| [[ADR-022]] | adr | 5 | UNCHANGED |
| [[ADR-023]] | adr | 8 | CHANGED |
| [[ADR-029]] | adr | 2 | UNCHANGED |
| [[ADR-031]] | adr | 2 | UNCHANGED |
| [[ADR-044]] | adr | 3 | UNCHANGED |
| [[ADR-045]] | adr | 6 | CHANGED |
| [[ADR-048]] | adr | 5 | UNCHANGED |
| [[ADR-055]] | adr | 5 | CHANGED |
| [[ADR-059]] | adr | 1 | NEW |
| [[API-042]] | api_endpoint | 8 | UNCHANGED |
| [[API-059]] | api_endpoint | 9 | CHANGED |
| [[API-060]] | api_endpoint | 18 | CHANGED |
| [[API-061]] | api_endpoint | 11 | CHANGED |
| [[API-062]] | api_endpoint | 10 | CHANGED |
| [[API-063]] | api_endpoint | 10 | CHANGED |
| [[API-092]] | api_endpoint | 10 | UNCHANGED |
| [[API-165]] | api_endpoint | 7 | UNCHANGED |
| [[API-175]] | api_endpoint | 2 | UNCHANGED |
| [[API-179]] | api_endpoint | 9 | UNCHANGED |
| [[API-188]] | api_endpoint | 3 | CHANGED |
| [[API-189]] | api_endpoint | 3 | CHANGED |
| [[API-190]] | api_endpoint | 7 | CHANGED |
| [[CDIAG-010]] | class_diagram | 14 | CHANGED |
| [[CMP-007]] | diagram_c4_component | 8 | CHANGED |
| [[DFEAT-029]] | domain_feature | 18 | CHANGED |
| [[DFEAT-030]] | domain_feature | 3 | UNCHANGED |
| [[DOMAIN-007]] | domain | 18 | CHANGED |
| [[ERD-011]] | erd | 29 | CHANGED |
| [[EVT-011]] | domain_event | 5 | CHANGED |
| [[EXTSYS-004]] | external_system | 10 | CHANGED |
| [[FEAT-001]] | feature | 9 | UNCHANGED |
| [[FEAT-004]] | feature | 11 | CHANGED |
| [[INT-006]] | integration_point | 13 | CHANGED |
| [[INT-008]] | integration_point | 15 | CHANGED |
| [[INTSPEC-005]] | integration_spec | 2 | CHANGED |
| [[INTSPEC-006]] | integration_spec | 2 | UNCHANGED |
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
| [[SCREEN-022]] | screen_spec | 51 | CHANGED |
| [[SCREEN-023]] | screen_spec | 47 | CHANGED |
| [[SD-028]] | screen_design | 9 | CHANGED |
| [[SD-029]] | screen_design | 7 | CHANGED |
| [[SEQ-002]] | diagram_sequence | 11 | CHANGED |
| [[SEQ-003]] | diagram_sequence | 12 | UNCHANGED |
| [[SEQ-004]] | diagram_sequence | 9 | UNCHANGED |
| [[SEQ-011]] | diagram_sequence | 11 | CHANGED |
| [[TEST-003]] | test_scenario | 19 | CHANGED |
| [[UC-001]] | use_case | 16 | CHANGED |
| [[UC-002]] | use_case | 18 | CHANGED |
| [[UC-003]] | use_case | 16 | UNCHANGED |
| [[UC-010]] | use_case | 16 | CHANGED |
