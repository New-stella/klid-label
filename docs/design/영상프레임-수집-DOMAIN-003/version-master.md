# Version Master — DOMAIN-003

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-003 |
| Last sync | 2026-08-29T01:27:24.612Z |
| Mode | SYNC — NEW 0 / CHANGED 27 / UNCHANGED 106 |
| 출력 루트 | docs/design/영상프레임-수집-DOMAIN-003/ |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-003 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 53건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 8건 / 스코프 밖 40건
  ℹ️  api_endpoint: 이번 키트 44건 / 스코프 밖 158건
  ℹ️  erd: 이번 키트 3건 / 스코프 밖 20건
  ℹ️  diagram_sequence: 이번 키트 2건 / 스코프 밖 24건 (그중 domain_id 없음 12건)
  ℹ️  screen_spec: 이번 키트 11건 / 스코프 밖 26건
  ℹ️  use_case: 이번 키트 4건 / 스코프 밖 26건 (그중 domain_id 없음 1건)
  ℹ️  domain_event: 이번 키트 3건 / 스코프 밖 9건
  ℹ️  acceptance: 이번 키트 6건 / 스코프 밖 118건 (그중 domain_id 없음 36건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 16건 / 스코프 밖 32건 (그중 domain_id 없음 3건)
  ℹ️  nfr: 이번 키트 14건 / 스코프 밖 1건 (그중 domain_id 없음 1건)
  ℹ️  feature: 이번 키트 1건 / 스코프 밖 9건 (그중 domain_id 없음 8건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[API-045]] (prev v2)
- CHANGED [[API-070]] (prev v8)
- CHANGED [[API-071]] (prev v7)
- CHANGED [[API-143]] (prev v3)
- CHANGED [[API-144]] (prev v3)
- CHANGED [[API-145]] (prev v3)
- CHANGED [[API-146]] (prev v5)
- CHANGED [[API-148]] (prev v3)
- CHANGED [[API-150]] (prev v3)
- CHANGED [[API-156]] (prev v3)
- CHANGED [[API-160]] (prev v4)
- CHANGED [[API-162]] (prev v6)
- CHANGED [[API-164]] (prev v3)
- CHANGED [[API-168]] (prev v2)
- CHANGED [[API-170]] (prev v3)
- CHANGED [[API-172]] (prev v4)
- CHANGED [[API-173]] (prev v6)
- CHANGED [[API-174]] (prev v6)
- CHANGED [[API-181]] (prev v7)
- CHANGED [[SEQ-004]] (prev v9)
- CHANGED [[EVT-001]] (prev v5)
- CHANGED [[EVT-005]] (prev v5)
- CHANGED [[DFEAT-007]] (prev v10)
- CHANGED [[DFEAT-008]] (prev v6)
- CHANGED [[DFEAT-029]] (prev v16)
- CHANGED [[DFEAT-051]] (prev v5)
- CHANGED [[SCREEN-011]] (prev v21)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-025]] | acceptance | 6 | UNCHANGED |
| [[AC-026]] | acceptance | 5 | UNCHANGED |
| [[AC-049]] | acceptance | 5 | UNCHANGED |
| [[AC-050]] | acceptance | 5 | UNCHANGED |
| [[AC-051]] | acceptance | 11 | UNCHANGED |
| [[AC-055]] | acceptance | 3 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-004]] | adr | 4 | UNCHANGED |
| [[ADR-006]] | adr | 4 | UNCHANGED |
| [[ADR-010]] | adr | 1 | UNCHANGED |
| [[ADR-018]] | adr | 5 | UNCHANGED |
| [[ADR-024]] | adr | 3 | UNCHANGED |
| [[ADR-030]] | adr | 4 | UNCHANGED |
| [[ADR-032]] | adr | 7 | UNCHANGED |
| [[ADR-042]] | adr | 6 | UNCHANGED |
| [[ADR-046]] | adr | 8 | UNCHANGED |
| [[ADR-048]] | adr | 5 | UNCHANGED |
| [[ADR-049]] | adr | 4 | UNCHANGED |
| [[ADR-050]] | adr | 5 | UNCHANGED |
| [[ADR-051]] | adr | 5 | UNCHANGED |
| [[ADR-054]] | adr | 5 | UNCHANGED |
| [[ADR-055]] | adr | 3 | UNCHANGED |
| [[API-021]] | api_endpoint | 9 | UNCHANGED |
| [[API-042]] | api_endpoint | 8 | UNCHANGED |
| [[API-043]] | api_endpoint | 25 | UNCHANGED |
| [[API-044]] | api_endpoint | 9 | UNCHANGED |
| [[API-045]] | api_endpoint | 2 | CHANGED |
| [[API-046]] | api_endpoint | 7 | UNCHANGED |
| [[API-047]] | api_endpoint | 12 | UNCHANGED |
| [[API-068]] | api_endpoint | 4 | UNCHANGED |
| [[API-070]] | api_endpoint | 8 | CHANGED |
| [[API-071]] | api_endpoint | 7 | CHANGED |
| [[API-084]] | api_endpoint | 8 | UNCHANGED |
| [[API-092]] | api_endpoint | 10 | UNCHANGED |
| [[API-114]] | api_endpoint | 2 | UNCHANGED |
| [[API-143]] | api_endpoint | 3 | CHANGED |
| [[API-144]] | api_endpoint | 3 | CHANGED |
| [[API-145]] | api_endpoint | 3 | CHANGED |
| [[API-146]] | api_endpoint | 5 | CHANGED |
| [[API-148]] | api_endpoint | 3 | CHANGED |
| [[API-150]] | api_endpoint | 3 | CHANGED |
| [[API-156]] | api_endpoint | 3 | CHANGED |
| [[API-158]] | api_endpoint | 5 | UNCHANGED |
| [[API-160]] | api_endpoint | 4 | CHANGED |
| [[API-162]] | api_endpoint | 6 | CHANGED |
| [[API-164]] | api_endpoint | 3 | CHANGED |
| [[API-167]] | api_endpoint | 10 | UNCHANGED |
| [[API-168]] | api_endpoint | 2 | CHANGED |
| [[API-170]] | api_endpoint | 3 | CHANGED |
| [[API-172]] | api_endpoint | 4 | CHANGED |
| [[API-173]] | api_endpoint | 6 | CHANGED |
| [[API-174]] | api_endpoint | 6 | CHANGED |
| [[API-181]] | api_endpoint | 7 | CHANGED |
| [[API-185]] | api_endpoint | 8 | UNCHANGED |
| [[API-186]] | api_endpoint | 6 | UNCHANGED |
| [[API-191]] | api_endpoint | 2 | UNCHANGED |
| [[API-192]] | api_endpoint | 4 | UNCHANGED |
| [[API-198]] | api_endpoint | 7 | UNCHANGED |
| [[API-199]] | api_endpoint | 2 | UNCHANGED |
| [[API-200]] | api_endpoint | 4 | UNCHANGED |
| [[API-201]] | api_endpoint | 8 | UNCHANGED |
| [[API-212]] | api_endpoint | 5 | UNCHANGED |
| [[API-213]] | api_endpoint | 6 | UNCHANGED |
| [[API-214]] | api_endpoint | 4 | UNCHANGED |
| [[API-219]] | api_endpoint | 3 | UNCHANGED |
| [[API-220]] | api_endpoint | 3 | UNCHANGED |
| [[CDIAG-001]] | class_diagram | 6 | UNCHANGED |
| [[CMP-001]] | diagram_c4_component | 6 | UNCHANGED |
| [[CMP-010]] | diagram_c4_component | 5 | UNCHANGED |
| [[DFEAT-007]] | domain_feature | 10 | CHANGED |
| [[DFEAT-008]] | domain_feature | 6 | CHANGED |
| [[DFEAT-009]] | domain_feature | 6 | UNCHANGED |
| [[DFEAT-010]] | domain_feature | 2 | UNCHANGED |
| [[DFEAT-011]] | domain_feature | 3 | UNCHANGED |
| [[DFEAT-029]] | domain_feature | 16 | CHANGED |
| [[DFEAT-045]] | domain_feature | 19 | UNCHANGED |
| [[DFEAT-051]] | domain_feature | 5 | CHANGED |
| [[DOMAIN-003]] | domain | 12 | UNCHANGED |
| [[ERD-012]] | erd | 45 | UNCHANGED |
| [[ERD-020]] | erd | 22 | UNCHANGED |
| [[ERD-025]] | erd | 4 | UNCHANGED |
| [[EVT-001]] | domain_event | 5 | CHANGED |
| [[EVT-002]] | domain_event | 6 | UNCHANGED |
| [[EVT-005]] | domain_event | 5 | CHANGED |
| [[FEAT-004]] | feature | 10 | UNCHANGED |
| [[INT-011]] | integration_point | 2 | UNCHANGED |
| [[LEGACY-003]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-005]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-021]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-043]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-044]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-046]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-119]] | legacy_artifact | 1 | UNCHANGED |
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
| [[NFR-020]] | nfr | 8 | UNCHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[ROLE-001]] | permission_role | 13 | UNCHANGED |
| [[ROLE-002]] | permission_role | 9 | UNCHANGED |
| [[ROLE-003]] | permission_role | 10 | UNCHANGED |
| [[ROLE-004]] | permission_role | 4 | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 102 | UNCHANGED |
| [[SCREEN-006]] | screen_spec | 49 | UNCHANGED |
| [[SCREEN-008]] | screen_spec | 47 | UNCHANGED |
| [[SCREEN-009]] | screen_spec | 75 | UNCHANGED |
| [[SCREEN-011]] | screen_spec | 21 | CHANGED |
| [[SCREEN-019]] | screen_spec | 43 | UNCHANGED |
| [[SCREEN-022]] | screen_spec | 46 | UNCHANGED |
| [[SCREEN-025]] | screen_spec | 46 | UNCHANGED |
| [[SCREEN-026]] | screen_spec | 35 | UNCHANGED |
| [[SCREEN-027]] | screen_spec | 42 | UNCHANGED |
| [[SCREEN-038]] | screen_spec | 14 | UNCHANGED |
| [[SD-004]] | screen_design | 19 | UNCHANGED |
| [[SD-013]] | screen_design | 7 | UNCHANGED |
| [[SD-023]] | screen_design | 5 | UNCHANGED |
| [[SEQ-001]] | diagram_sequence | 20 | UNCHANGED |
| [[SEQ-004]] | diagram_sequence | 9 | CHANGED |
| [[STATE-002]] | diagram_state | 2 | UNCHANGED |
| [[TEST-001]] | test_scenario | 10 | UNCHANGED |
| [[UC-011]] | use_case | 12 | UNCHANGED |
| [[UC-016]] | use_case | 24 | UNCHANGED |
| [[UC-018]] | use_case | 17 | UNCHANGED |
| [[UC-032]] | use_case | 11 | UNCHANGED |
