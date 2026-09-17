# Version Master — DOMAIN-007

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-007 |
| Last sync | 2026-09-17T01:15:50.469Z |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 111 |
| 출력 루트 | docs/design/데이터-증강내보내기-DOMAIN-007 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-007 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 24건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 2건 / 스코프 밖 47건
  ℹ️  api_endpoint: 이번 키트 13건 / 스코프 밖 225건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 24건
  ℹ️  diagram_sequence: 이번 키트 4건 / 스코프 밖 32건 (그중 domain_id 없음 10건)
  ℹ️  screen_spec: 이번 키트 2건 / 스코프 밖 37건
  ℹ️  use_case: 이번 키트 4건 / 스코프 밖 33건
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 6건 / 스코프 밖 117건 (그중 domain_id 없음 18건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 17건 / 스코프 밖 48건 (그중 domain_id 없음 7건)
  ℹ️  feature: 이번 키트 2건 / 스코프 밖 12건 (그중 domain_id 없음 11건)
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
| [[AC-1044]] | acceptance | 6 | UNCHANGED |
| [[AC-1045]] | acceptance | 4 | UNCHANGED |
| [[AC-1046]] | acceptance | 8 | UNCHANGED |
| [[AC-1047]] | acceptance | 6 | UNCHANGED |
| [[AC-1048]] | acceptance | 6 | UNCHANGED |
| [[AC-1049]] | acceptance | 5 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-004]] | adr | 4 | UNCHANGED |
| [[ADR-018]] | adr | 5 | UNCHANGED |
| [[ADR-020]] | adr | 11 | UNCHANGED |
| [[ADR-022]] | adr | 6 | UNCHANGED |
| [[ADR-023]] | adr | 9 | UNCHANGED |
| [[ADR-029]] | adr | 2 | UNCHANGED |
| [[ADR-031]] | adr | 4 | UNCHANGED |
| [[ADR-044]] | adr | 3 | UNCHANGED |
| [[ADR-045]] | adr | 7 | UNCHANGED |
| [[ADR-048]] | adr | 6 | UNCHANGED |
| [[ADR-055]] | adr | 6 | UNCHANGED |
| [[ADR-058]] | adr | 10 | UNCHANGED |
| [[ADR-059]] | adr | 1 | UNCHANGED |
| [[ADR-061]] | adr | 1 | UNCHANGED |
| [[ADR-062]] | adr | 8 | UNCHANGED |
| [[ADR-066]] | adr | 3 | UNCHANGED |
| [[API-042]] | api_endpoint | 10 | UNCHANGED |
| [[API-059]] | api_endpoint | 9 | UNCHANGED |
| [[API-060]] | api_endpoint | 18 | UNCHANGED |
| [[API-061]] | api_endpoint | 11 | UNCHANGED |
| [[API-062]] | api_endpoint | 10 | UNCHANGED |
| [[API-063]] | api_endpoint | 10 | UNCHANGED |
| [[API-092]] | api_endpoint | 10 | UNCHANGED |
| [[API-165]] | api_endpoint | 8 | UNCHANGED |
| [[API-175]] | api_endpoint | 2 | UNCHANGED |
| [[API-179]] | api_endpoint | 9 | UNCHANGED |
| [[API-188]] | api_endpoint | 3 | UNCHANGED |
| [[API-189]] | api_endpoint | 3 | UNCHANGED |
| [[API-190]] | api_endpoint | 7 | UNCHANGED |
| [[CDIAG-010]] | class_diagram | 14 | UNCHANGED |
| [[CDIAG-021]] | class_diagram | 1 | UNCHANGED |
| [[CDIAG-031]] | class_diagram | 1 | UNCHANGED |
| [[CDIAG-039]] | class_diagram | 1 | UNCHANGED |
| [[CMP-007]] | diagram_c4_component | 8 | UNCHANGED |
| [[DFEAT-029]] | domain_feature | 18 | UNCHANGED |
| [[DFEAT-030]] | domain_feature | 3 | UNCHANGED |
| [[DOMAIN-007]] | domain | 18 | UNCHANGED |
| [[ERD-011]] | erd | 30 | UNCHANGED |
| [[EVT-011]] | domain_event | 5 | UNCHANGED |
| [[EXTSYS-004]] | external_system | 11 | UNCHANGED |
| [[FEAT-001]] | feature | 9 | UNCHANGED |
| [[FEAT-004]] | feature | 11 | UNCHANGED |
| [[INT-006]] | integration_point | 20 | UNCHANGED |
| [[INT-008]] | integration_point | 16 | UNCHANGED |
| [[INTSPEC-005]] | integration_spec | 3 | UNCHANGED |
| [[INTSPEC-006]] | integration_spec | 7 | UNCHANGED |
| [[NFR-008]] | nfr | 7 | UNCHANGED |
| [[NFR-009]] | nfr | 6 | UNCHANGED |
| [[NFR-010]] | nfr | 4 | UNCHANGED |
| [[NFR-011]] | nfr | 8 | UNCHANGED |
| [[NFR-012]] | nfr | 6 | UNCHANGED |
| [[NFR-013]] | nfr | 14 | UNCHANGED |
| [[NFR-014]] | nfr | 5 | UNCHANGED |
| [[NFR-015]] | nfr | 6 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 12 | UNCHANGED |
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
| [[NFR-038]] | nfr | 3 | UNCHANGED |
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
| [[ROLE-001]] | permission_role | 16 | UNCHANGED |
| [[ROLE-002]] | permission_role | 10 | UNCHANGED |
| [[ROLE-003]] | permission_role | 17 | UNCHANGED |
| [[ROLE-004]] | permission_role | 6 | UNCHANGED |
| [[SCREEN-022]] | screen_spec | 51 | UNCHANGED |
| [[SCREEN-023]] | screen_spec | 47 | UNCHANGED |
| [[SD-028]] | screen_design | 9 | UNCHANGED |
| [[SD-029]] | screen_design | 7 | UNCHANGED |
| [[SEQ-002]] | diagram_sequence | 13 | UNCHANGED |
| [[SEQ-003]] | diagram_sequence | 15 | UNCHANGED |
| [[SEQ-004]] | diagram_sequence | 11 | UNCHANGED |
| [[SEQ-011]] | diagram_sequence | 13 | UNCHANGED |
| [[TEST-003]] | test_scenario | 19 | UNCHANGED |
| [[UC-001]] | use_case | 19 | UNCHANGED |
| [[UC-002]] | use_case | 22 | UNCHANGED |
| [[UC-003]] | use_case | 17 | UNCHANGED |
| [[UC-010]] | use_case | 18 | UNCHANGED |
