# Version Master — DOMAIN-003

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-003 |
| Last sync | 2026-08-16T14:48:51.454Z |
| Mode | INITIAL — NEW 99 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | docs/design/영상프레임-수집-DOMAIN-003 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-003 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 7건 / 스코프 밖 36건
  ℹ️  api_endpoint: 이번 키트 38건 / 스코프 밖 143건
  ℹ️  erd: 이번 키트 3건 / 스코프 밖 16건
  ℹ️  diagram_sequence: 이번 키트 1건 / 스코프 밖 24건 (그중 domain_id 없음 13건)
  ℹ️  screen_spec: 이번 키트 8건 / 스코프 밖 24건
  ℹ️  use_case: 이번 키트 3건 / 스코프 밖 22건 (그중 domain_id 없음 1건)
  ℹ️  domain_event: 이번 키트 2건 / 스코프 밖 10건
  ℹ️  acceptance: 이번 키트 2건 / 스코프 밖 23건 (그중 domain_id 없음 22건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 8건 / 스코프 밖 33건 (그중 domain_id 없음 4건)
  ℹ️  feature: 이번 키트 1건 / 스코프 밖 8건 (그중 domain_id 없음 8건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[AC-025]]
- NEW [[AC-026]]
- NEW [[ADR-001]]
- NEW [[ADR-003]]
- NEW [[ADR-004]]
- NEW [[ADR-006]]
- NEW [[ADR-010]]
- NEW [[ADR-018]]
- NEW [[ADR-032]]
- NEW [[ADR-042]]
- NEW [[API-021]]
- NEW [[API-042]]
- NEW [[API-043]]
- NEW [[API-044]]
- NEW [[API-045]]
- NEW [[API-046]]
- NEW [[API-047]]
- NEW [[API-070]]
- NEW [[API-071]]
- NEW [[API-084]]
- NEW [[API-092]]
- NEW [[API-114]]
- NEW [[API-143]]
- NEW [[API-144]]
- NEW [[API-145]]
- NEW [[API-146]]
- NEW [[API-148]]
- NEW [[API-150]]
- NEW [[API-156]]
- NEW [[API-158]]
- NEW [[API-160]]
- NEW [[API-162]]
- NEW [[API-164]]
- NEW [[API-167]]
- NEW [[API-168]]
- NEW [[API-170]]
- NEW [[API-172]]
- NEW [[API-173]]
- NEW [[API-174]]
- NEW [[API-181]]
- NEW [[API-185]]
- NEW [[API-186]]
- NEW [[API-191]]
- NEW [[API-192]]
- NEW [[API-198]]
- NEW [[API-199]]
- NEW [[API-200]]
- NEW [[API-201]]
- NEW [[CDIAG-001]]
- NEW [[CMP-001]]
- NEW [[CMP-010]]
- NEW [[SEQ-004]]
- NEW [[STATE-002]]
- NEW [[DOMAIN-003]]
- NEW [[EVT-002]]
- NEW [[EVT-005]]
- NEW [[DFEAT-007]]
- NEW [[DFEAT-008]]
- NEW [[DFEAT-009]]
- NEW [[DFEAT-010]]
- NEW [[DFEAT-011]]
- NEW [[DFEAT-029]]
- NEW [[DFEAT-051]]
- NEW [[ERD-012]]
- NEW [[ERD-020]]
- NEW [[ERD-025]]
- NEW [[FEAT-004]]
- NEW [[NFR-008]]
- NEW [[NFR-009]]
- NEW [[NFR-010]]
- NEW [[NFR-011]]
- NEW [[NFR-012]]
- NEW [[NFR-013]]
- NEW [[NFR-014]]
- NEW [[NFR-015]]
- NEW [[NFR-016]]
- NEW [[NFR-017]]
- NEW [[NFR-018]]
- NEW [[NFR-019]]
- NEW [[NFR-020]]
- NEW [[NFR-021]]
- NEW [[ROLE-001]]
- NEW [[ROLE-002]]
- NEW [[ROLE-003]]
- NEW [[SD-004]]
- NEW [[SD-013]]
- NEW [[SD-023]]
- NEW [[SCREEN-005]]
- NEW [[SCREEN-006]]
- NEW [[SCREEN-008]]
- NEW [[SCREEN-009]]
- NEW [[SCREEN-011]]
- NEW [[SCREEN-022]]
- NEW [[SCREEN-027]]
- NEW [[SCREEN-038]]
- NEW [[TEST-001]]
- NEW [[UC-011]]
- NEW [[UC-016]]
- NEW [[UC-018]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-025]] | acceptance | 6 | NEW |
| [[AC-026]] | acceptance | 5 | NEW |
| [[ADR-001]] | adr | 2 | NEW |
| [[ADR-003]] | adr | 2 | NEW |
| [[ADR-004]] | adr | 4 | NEW |
| [[ADR-006]] | adr | 4 | NEW |
| [[ADR-010]] | adr | 1 | NEW |
| [[ADR-018]] | adr | 3 | NEW |
| [[ADR-032]] | adr | 7 | NEW |
| [[ADR-042]] | adr | 5 | NEW |
| [[API-021]] | api_endpoint | 7 | NEW |
| [[API-042]] | api_endpoint | 5 | NEW |
| [[API-043]] | api_endpoint | 12 | NEW |
| [[API-044]] | api_endpoint | 5 | NEW |
| [[API-045]] | api_endpoint | 2 | NEW |
| [[API-046]] | api_endpoint | 5 | NEW |
| [[API-047]] | api_endpoint | 10 | NEW |
| [[API-070]] | api_endpoint | 8 | NEW |
| [[API-071]] | api_endpoint | 7 | NEW |
| [[API-084]] | api_endpoint | 6 | NEW |
| [[API-092]] | api_endpoint | 7 | NEW |
| [[API-114]] | api_endpoint | 1 | NEW |
| [[API-143]] | api_endpoint | 3 | NEW |
| [[API-144]] | api_endpoint | 3 | NEW |
| [[API-145]] | api_endpoint | 3 | NEW |
| [[API-146]] | api_endpoint | 5 | NEW |
| [[API-148]] | api_endpoint | 3 | NEW |
| [[API-150]] | api_endpoint | 3 | NEW |
| [[API-156]] | api_endpoint | 2 | NEW |
| [[API-158]] | api_endpoint | 3 | NEW |
| [[API-160]] | api_endpoint | 3 | NEW |
| [[API-162]] | api_endpoint | 5 | NEW |
| [[API-164]] | api_endpoint | 2 | NEW |
| [[API-167]] | api_endpoint | 10 | NEW |
| [[API-168]] | api_endpoint | 2 | NEW |
| [[API-170]] | api_endpoint | 3 | NEW |
| [[API-172]] | api_endpoint | 3 | NEW |
| [[API-173]] | api_endpoint | 5 | NEW |
| [[API-174]] | api_endpoint | 5 | NEW |
| [[API-181]] | api_endpoint | 7 | NEW |
| [[API-185]] | api_endpoint | 1 | NEW |
| [[API-186]] | api_endpoint | 2 | NEW |
| [[API-191]] | api_endpoint | 2 | NEW |
| [[API-192]] | api_endpoint | 4 | NEW |
| [[API-198]] | api_endpoint | 4 | NEW |
| [[API-199]] | api_endpoint | 2 | NEW |
| [[API-200]] | api_endpoint | 2 | NEW |
| [[API-201]] | api_endpoint | 3 | NEW |
| [[CDIAG-001]] | class_diagram | 6 | NEW |
| [[CMP-001]] | diagram_c4_component | 6 | NEW |
| [[CMP-010]] | diagram_c4_component | 5 | NEW |
| [[DFEAT-007]] | domain_feature | 10 | NEW |
| [[DFEAT-008]] | domain_feature | 6 | NEW |
| [[DFEAT-009]] | domain_feature | 6 | NEW |
| [[DFEAT-010]] | domain_feature | 2 | NEW |
| [[DFEAT-011]] | domain_feature | 3 | NEW |
| [[DFEAT-029]] | domain_feature | 11 | NEW |
| [[DFEAT-051]] | domain_feature | 4 | NEW |
| [[DOMAIN-003]] | domain | 12 | NEW |
| [[ERD-012]] | erd | 28 | NEW |
| [[ERD-020]] | erd | 22 | NEW |
| [[ERD-025]] | erd | 4 | NEW |
| [[EVT-002]] | domain_event | 6 | NEW |
| [[EVT-005]] | domain_event | 3 | NEW |
| [[FEAT-004]] | feature | 9 | NEW |
| [[NFR-008]] | nfr | 6 | NEW |
| [[NFR-009]] | nfr | 4 | NEW |
| [[NFR-010]] | nfr | 3 | NEW |
| [[NFR-011]] | nfr | 4 | NEW |
| [[NFR-012]] | nfr | 4 | NEW |
| [[NFR-013]] | nfr | 4 | NEW |
| [[NFR-014]] | nfr | 2 | NEW |
| [[NFR-015]] | nfr | 5 | NEW |
| [[NFR-016]] | nfr | 4 | NEW |
| [[NFR-017]] | nfr | 8 | NEW |
| [[NFR-018]] | nfr | 3 | NEW |
| [[NFR-019]] | nfr | 2 | NEW |
| [[NFR-020]] | nfr | 4 | NEW |
| [[NFR-021]] | nfr | 1 | NEW |
| [[ROLE-001]] | permission_role | 10 | NEW |
| [[ROLE-002]] | permission_role | 6 | NEW |
| [[ROLE-003]] | permission_role | 6 | NEW |
| [[SCREEN-005]] | screen_spec | 76 | NEW |
| [[SCREEN-006]] | screen_spec | 41 | NEW |
| [[SCREEN-008]] | screen_spec | 35 | NEW |
| [[SCREEN-009]] | screen_spec | 44 | NEW |
| [[SCREEN-011]] | screen_spec | 18 | NEW |
| [[SCREEN-022]] | screen_spec | 36 | NEW |
| [[SCREEN-027]] | screen_spec | 26 | NEW |
| [[SCREEN-038]] | screen_spec | 5 | NEW |
| [[SD-004]] | screen_design | 13 | NEW |
| [[SD-013]] | screen_design | 3 | NEW |
| [[SD-023]] | screen_design | 3 | NEW |
| [[SEQ-004]] | diagram_sequence | 7 | NEW |
| [[STATE-002]] | diagram_state | 2 | NEW |
| [[TEST-001]] | test_scenario | 10 | NEW |
| [[UC-011]] | use_case | 12 | NEW |
| [[UC-016]] | use_case | 20 | NEW |
| [[UC-018]] | use_case | 16 | NEW |
