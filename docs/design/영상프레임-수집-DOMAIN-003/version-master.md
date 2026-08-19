# Version Master — DOMAIN-003

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-003 |
| Last sync | 2026-08-19T13:52:30.244Z |
| Mode | SYNC — NEW 11 / CHANGED 11 / UNCHANGED 88 |
| 출력 루트 | docs/design/영상프레임-수집-DOMAIN-003 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-003 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 7건 / 스코프 밖 41건
  ℹ️  api_endpoint: 이번 키트 41건 / 스코프 밖 152건
  ℹ️  erd: 이번 키트 3건 / 스코프 밖 18건
  ℹ️  diagram_sequence: 이번 키트 1건 / 스코프 밖 25건 (그중 domain_id 없음 13건)
  ℹ️  screen_spec: 이번 키트 8건 / 스코프 밖 25건
  ℹ️  use_case: 이번 키트 3건 / 스코프 밖 25건 (그중 domain_id 없음 1건)
  ℹ️  domain_event: 이번 키트 2건 / 스코프 밖 10건
  ℹ️  acceptance: 이번 키트 2건 / 스코프 밖 42건 (그중 domain_id 없음 25건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 9건 / 스코프 밖 35건 (그중 domain_id 없음 4건)
  ℹ️  feature: 이번 키트 1건 / 스코프 밖 8건 (그중 domain_id 없음 8건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[ADR-049]]
- NEW [[API-212]]
- NEW [[API-213]]
- NEW [[API-214]]
- NEW [[LEGACY-003]]
- NEW [[LEGACY-005]]
- NEW [[LEGACY-021]]
- NEW [[LEGACY-043]]
- NEW [[LEGACY-044]]
- NEW [[LEGACY-046]]
- NEW [[LEGACY-119]]
- CHANGED [[ADR-042]] (prev v5)
- CHANGED [[API-201]] (prev v3)
- CHANGED [[EVT-005]] (prev v3)
- CHANGED [[ERD-012]] (prev v30)
- CHANGED [[NFR-018]] (prev v3)
- CHANGED [[SCREEN-005]] (prev v84)
- CHANGED [[SCREEN-006]] (prev v41)
- CHANGED [[SCREEN-009]] (prev v44)
- CHANGED [[SCREEN-022]] (prev v36)
- CHANGED [[SCREEN-038]] (prev v7)
- CHANGED [[UC-018]] (prev v16)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-025]] | acceptance | 6 | UNCHANGED |
| [[AC-026]] | acceptance | 5 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-003]] | adr | 2 | UNCHANGED |
| [[ADR-004]] | adr | 4 | UNCHANGED |
| [[ADR-006]] | adr | 4 | UNCHANGED |
| [[ADR-010]] | adr | 1 | UNCHANGED |
| [[ADR-018]] | adr | 3 | UNCHANGED |
| [[ADR-032]] | adr | 7 | UNCHANGED |
| [[ADR-042]] | adr | 6 | CHANGED |
| [[ADR-049]] | adr | 2 | NEW |
| [[API-021]] | api_endpoint | 8 | UNCHANGED |
| [[API-042]] | api_endpoint | 5 | UNCHANGED |
| [[API-043]] | api_endpoint | 14 | UNCHANGED |
| [[API-044]] | api_endpoint | 5 | UNCHANGED |
| [[API-045]] | api_endpoint | 2 | UNCHANGED |
| [[API-046]] | api_endpoint | 6 | UNCHANGED |
| [[API-047]] | api_endpoint | 10 | UNCHANGED |
| [[API-070]] | api_endpoint | 8 | UNCHANGED |
| [[API-071]] | api_endpoint | 7 | UNCHANGED |
| [[API-084]] | api_endpoint | 7 | UNCHANGED |
| [[API-092]] | api_endpoint | 7 | UNCHANGED |
| [[API-114]] | api_endpoint | 1 | UNCHANGED |
| [[API-143]] | api_endpoint | 3 | UNCHANGED |
| [[API-144]] | api_endpoint | 3 | UNCHANGED |
| [[API-145]] | api_endpoint | 3 | UNCHANGED |
| [[API-146]] | api_endpoint | 5 | UNCHANGED |
| [[API-148]] | api_endpoint | 3 | UNCHANGED |
| [[API-150]] | api_endpoint | 3 | UNCHANGED |
| [[API-156]] | api_endpoint | 2 | UNCHANGED |
| [[API-158]] | api_endpoint | 3 | UNCHANGED |
| [[API-160]] | api_endpoint | 3 | UNCHANGED |
| [[API-162]] | api_endpoint | 5 | UNCHANGED |
| [[API-164]] | api_endpoint | 2 | UNCHANGED |
| [[API-167]] | api_endpoint | 10 | UNCHANGED |
| [[API-168]] | api_endpoint | 2 | UNCHANGED |
| [[API-170]] | api_endpoint | 3 | UNCHANGED |
| [[API-172]] | api_endpoint | 4 | UNCHANGED |
| [[API-173]] | api_endpoint | 6 | UNCHANGED |
| [[API-174]] | api_endpoint | 6 | UNCHANGED |
| [[API-181]] | api_endpoint | 7 | UNCHANGED |
| [[API-185]] | api_endpoint | 3 | UNCHANGED |
| [[API-186]] | api_endpoint | 5 | UNCHANGED |
| [[API-191]] | api_endpoint | 2 | UNCHANGED |
| [[API-192]] | api_endpoint | 4 | UNCHANGED |
| [[API-198]] | api_endpoint | 4 | UNCHANGED |
| [[API-199]] | api_endpoint | 2 | UNCHANGED |
| [[API-200]] | api_endpoint | 2 | UNCHANGED |
| [[API-201]] | api_endpoint | 4 | CHANGED |
| [[API-212]] | api_endpoint | 1 | NEW |
| [[API-213]] | api_endpoint | 1 | NEW |
| [[API-214]] | api_endpoint | 1 | NEW |
| [[CDIAG-001]] | class_diagram | 6 | UNCHANGED |
| [[CMP-001]] | diagram_c4_component | 6 | UNCHANGED |
| [[CMP-010]] | diagram_c4_component | 5 | UNCHANGED |
| [[DFEAT-007]] | domain_feature | 10 | UNCHANGED |
| [[DFEAT-008]] | domain_feature | 6 | UNCHANGED |
| [[DFEAT-009]] | domain_feature | 6 | UNCHANGED |
| [[DFEAT-010]] | domain_feature | 2 | UNCHANGED |
| [[DFEAT-011]] | domain_feature | 3 | UNCHANGED |
| [[DFEAT-029]] | domain_feature | 11 | UNCHANGED |
| [[DFEAT-051]] | domain_feature | 4 | UNCHANGED |
| [[DOMAIN-003]] | domain | 12 | UNCHANGED |
| [[ERD-012]] | erd | 32 | CHANGED |
| [[ERD-020]] | erd | 22 | UNCHANGED |
| [[ERD-025]] | erd | 4 | UNCHANGED |
| [[EVT-002]] | domain_event | 6 | UNCHANGED |
| [[EVT-005]] | domain_event | 4 | CHANGED |
| [[FEAT-004]] | feature | 9 | UNCHANGED |
| [[LEGACY-003]] | legacy_artifact | 1 | NEW |
| [[LEGACY-005]] | legacy_artifact | 1 | NEW |
| [[LEGACY-021]] | legacy_artifact | 1 | NEW |
| [[LEGACY-043]] | legacy_artifact | 1 | NEW |
| [[LEGACY-044]] | legacy_artifact | 1 | NEW |
| [[LEGACY-046]] | legacy_artifact | 1 | NEW |
| [[LEGACY-119]] | legacy_artifact | 1 | NEW |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 4 | UNCHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 4 | UNCHANGED |
| [[NFR-012]] | nfr | 4 | UNCHANGED |
| [[NFR-013]] | nfr | 4 | UNCHANGED |
| [[NFR-014]] | nfr | 2 | UNCHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 8 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | CHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 4 | UNCHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[ROLE-001]] | permission_role | 10 | UNCHANGED |
| [[ROLE-002]] | permission_role | 6 | UNCHANGED |
| [[ROLE-003]] | permission_role | 7 | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 96 | CHANGED |
| [[SCREEN-006]] | screen_spec | 43 | CHANGED |
| [[SCREEN-008]] | screen_spec | 35 | UNCHANGED |
| [[SCREEN-009]] | screen_spec | 46 | CHANGED |
| [[SCREEN-011]] | screen_spec | 21 | UNCHANGED |
| [[SCREEN-022]] | screen_spec | 39 | CHANGED |
| [[SCREEN-027]] | screen_spec | 28 | UNCHANGED |
| [[SCREEN-038]] | screen_spec | 7 | CHANGED |
| [[SD-004]] | screen_design | 13 | UNCHANGED |
| [[SD-013]] | screen_design | 3 | UNCHANGED |
| [[SD-023]] | screen_design | 3 | UNCHANGED |
| [[SEQ-004]] | diagram_sequence | 7 | UNCHANGED |
| [[STATE-002]] | diagram_state | 2 | UNCHANGED |
| [[TEST-001]] | test_scenario | 10 | UNCHANGED |
| [[UC-011]] | use_case | 12 | UNCHANGED |
| [[UC-016]] | use_case | 20 | UNCHANGED |
| [[UC-018]] | use_case | 17 | CHANGED |
