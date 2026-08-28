# Version Master — DOMAIN-007

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-007 |
| Last sync | 2026-08-28T11:38:08.747Z |
| Mode | SYNC — NEW 1 / CHANGED 0 / UNCHANGED 74 |
| 출력 루트 | docs/design/데이터-증강내보내기-DOMAIN-007/ |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-007 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 9건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 2건 / 스코프 밖 46건
  ℹ️  api_endpoint: 이번 키트 13건 / 스코프 밖 189건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 22건
  ℹ️  diagram_sequence: 이번 키트 4건 / 스코프 밖 22건 (그중 domain_id 없음 10건)
  ℹ️  screen_spec: 이번 키트 2건 / 스코프 밖 35건
  ℹ️  use_case: 이번 키트 4건 / 스코프 밖 26건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 5건 / 스코프 밖 119건 (그중 domain_id 없음 36건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 12건 / 스코프 밖 36건 (그중 domain_id 없음 4건)
  ℹ️  feature: 이번 키트 2건 / 스코프 밖 8건 (그중 domain_id 없음 7건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[ADR-048]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-001]] | acceptance | 11 | UNCHANGED |
| [[AC-002]] | acceptance | 8 | UNCHANGED |
| [[AC-003]] | acceptance | 12 | UNCHANGED |
| [[AC-018]] | acceptance | 6 | UNCHANGED |
| [[AC-021]] | acceptance | 5 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-004]] | adr | 4 | UNCHANGED |
| [[ADR-018]] | adr | 5 | UNCHANGED |
| [[ADR-020]] | adr | 10 | UNCHANGED |
| [[ADR-022]] | adr | 5 | UNCHANGED |
| [[ADR-023]] | adr | 7 | UNCHANGED |
| [[ADR-029]] | adr | 2 | UNCHANGED |
| [[ADR-031]] | adr | 2 | UNCHANGED |
| [[ADR-044]] | adr | 3 | UNCHANGED |
| [[ADR-045]] | adr | 5 | UNCHANGED |
| [[ADR-048]] | adr | 5 | NEW |
| [[ADR-055]] | adr | 3 | UNCHANGED |
| [[API-042]] | api_endpoint | 8 | UNCHANGED |
| [[API-059]] | api_endpoint | 6 | UNCHANGED |
| [[API-060]] | api_endpoint | 16 | UNCHANGED |
| [[API-061]] | api_endpoint | 10 | UNCHANGED |
| [[API-062]] | api_endpoint | 7 | UNCHANGED |
| [[API-063]] | api_endpoint | 9 | UNCHANGED |
| [[API-092]] | api_endpoint | 10 | UNCHANGED |
| [[API-165]] | api_endpoint | 7 | UNCHANGED |
| [[API-175]] | api_endpoint | 2 | UNCHANGED |
| [[API-179]] | api_endpoint | 9 | UNCHANGED |
| [[API-188]] | api_endpoint | 2 | UNCHANGED |
| [[API-189]] | api_endpoint | 2 | UNCHANGED |
| [[API-190]] | api_endpoint | 6 | UNCHANGED |
| [[CDIAG-010]] | class_diagram | 11 | UNCHANGED |
| [[CMP-007]] | diagram_c4_component | 6 | UNCHANGED |
| [[DFEAT-029]] | domain_feature | 16 | UNCHANGED |
| [[DFEAT-030]] | domain_feature | 3 | UNCHANGED |
| [[DOMAIN-007]] | domain | 16 | UNCHANGED |
| [[ERD-011]] | erd | 25 | UNCHANGED |
| [[EVT-011]] | domain_event | 4 | UNCHANGED |
| [[EXTSYS-004]] | external_system | 9 | UNCHANGED |
| [[FEAT-001]] | feature | 9 | UNCHANGED |
| [[FEAT-004]] | feature | 10 | UNCHANGED |
| [[INT-006]] | integration_point | 12 | UNCHANGED |
| [[INT-008]] | integration_point | 13 | UNCHANGED |
| [[INTSPEC-005]] | integration_spec | 1 | UNCHANGED |
| [[INTSPEC-006]] | integration_spec | 2 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 4 | UNCHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 5 | UNCHANGED |
| [[NFR-012]] | nfr | 4 | UNCHANGED |
| [[NFR-013]] | nfr | 9 | UNCHANGED |
| [[NFR-014]] | nfr | 3 | UNCHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 9 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 7 | UNCHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[ROLE-001]] | permission_role | 13 | UNCHANGED |
| [[ROLE-002]] | permission_role | 9 | UNCHANGED |
| [[ROLE-003]] | permission_role | 10 | UNCHANGED |
| [[ROLE-004]] | permission_role | 4 | UNCHANGED |
| [[SCREEN-022]] | screen_spec | 45 | UNCHANGED |
| [[SCREEN-023]] | screen_spec | 41 | UNCHANGED |
| [[SD-028]] | screen_design | 4 | UNCHANGED |
| [[SD-029]] | screen_design | 5 | UNCHANGED |
| [[SEQ-002]] | diagram_sequence | 8 | UNCHANGED |
| [[SEQ-003]] | diagram_sequence | 11 | UNCHANGED |
| [[SEQ-004]] | diagram_sequence | 9 | UNCHANGED |
| [[SEQ-011]] | diagram_sequence | 8 | UNCHANGED |
| [[TEST-003]] | test_scenario | 15 | UNCHANGED |
| [[UC-001]] | use_case | 13 | UNCHANGED |
| [[UC-002]] | use_case | 15 | UNCHANGED |
| [[UC-003]] | use_case | 14 | UNCHANGED |
| [[UC-010]] | use_case | 13 | UNCHANGED |
