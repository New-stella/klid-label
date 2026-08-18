# Version Master — DOMAIN-007

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-007 |
| Last sync | 2026-08-16T14:48:52.847Z |
| Mode | INITIAL — NEW 70 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | docs/design/데이터-증강내보내기-DOMAIN-007 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-007 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 2건 / 스코프 밖 41건
  ℹ️  api_endpoint: 이번 키트 13건 / 스코프 밖 168건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 18건
  ℹ️  diagram_sequence: 이번 키트 4건 / 스코프 밖 21건 (그중 domain_id 없음 10건)
  ℹ️  screen_spec: 이번 키트 2건 / 스코프 밖 30건
  ℹ️  use_case: 이번 키트 4건 / 스코프 밖 21건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 5건 / 스코프 밖 20건 (그중 domain_id 없음 19건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 10건 / 스코프 밖 31건 (그중 domain_id 없음 3건)
  ℹ️  feature: 이번 키트 2건 / 스코프 밖 7건 (그중 domain_id 없음 7건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[AC-001]]
- NEW [[AC-002]]
- NEW [[AC-003]]
- NEW [[AC-018]]
- NEW [[AC-021]]
- NEW [[ADR-001]]
- NEW [[ADR-003]]
- NEW [[ADR-004]]
- NEW [[ADR-018]]
- NEW [[ADR-020]]
- NEW [[ADR-022]]
- NEW [[ADR-023]]
- NEW [[ADR-031]]
- NEW [[ADR-044]]
- NEW [[ADR-045]]
- NEW [[API-042]]
- NEW [[API-059]]
- NEW [[API-060]]
- NEW [[API-061]]
- NEW [[API-062]]
- NEW [[API-063]]
- NEW [[API-092]]
- NEW [[API-165]]
- NEW [[API-175]]
- NEW [[API-179]]
- NEW [[API-188]]
- NEW [[API-189]]
- NEW [[API-190]]
- NEW [[CDIAG-010]]
- NEW [[CMP-007]]
- NEW [[SEQ-002]]
- NEW [[SEQ-003]]
- NEW [[SEQ-004]]
- NEW [[SEQ-011]]
- NEW [[DOMAIN-007]]
- NEW [[EVT-011]]
- NEW [[DFEAT-029]]
- NEW [[DFEAT-030]]
- NEW [[ERD-011]]
- NEW [[EXTSYS-004]]
- NEW [[FEAT-001]]
- NEW [[FEAT-004]]
- NEW [[INT-006]]
- NEW [[INT-008]]
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
- NEW [[SD-028]]
- NEW [[SD-029]]
- NEW [[SCREEN-022]]
- NEW [[SCREEN-023]]
- NEW [[TEST-003]]
- NEW [[UC-001]]
- NEW [[UC-002]]
- NEW [[UC-003]]
- NEW [[UC-010]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-001]] | acceptance | 9 | NEW |
| [[AC-002]] | acceptance | 8 | NEW |
| [[AC-003]] | acceptance | 7 | NEW |
| [[AC-018]] | acceptance | 5 | NEW |
| [[AC-021]] | acceptance | 5 | NEW |
| [[ADR-001]] | adr | 2 | NEW |
| [[ADR-003]] | adr | 2 | NEW |
| [[ADR-004]] | adr | 4 | NEW |
| [[ADR-018]] | adr | 3 | NEW |
| [[ADR-020]] | adr | 10 | NEW |
| [[ADR-022]] | adr | 5 | NEW |
| [[ADR-023]] | adr | 6 | NEW |
| [[ADR-031]] | adr | 2 | NEW |
| [[ADR-044]] | adr | 3 | NEW |
| [[ADR-045]] | adr | 4 | NEW |
| [[API-042]] | api_endpoint | 5 | NEW |
| [[API-059]] | api_endpoint | 6 | NEW |
| [[API-060]] | api_endpoint | 10 | NEW |
| [[API-061]] | api_endpoint | 10 | NEW |
| [[API-062]] | api_endpoint | 6 | NEW |
| [[API-063]] | api_endpoint | 8 | NEW |
| [[API-092]] | api_endpoint | 7 | NEW |
| [[API-165]] | api_endpoint | 6 | NEW |
| [[API-175]] | api_endpoint | 2 | NEW |
| [[API-179]] | api_endpoint | 5 | NEW |
| [[API-188]] | api_endpoint | 2 | NEW |
| [[API-189]] | api_endpoint | 2 | NEW |
| [[API-190]] | api_endpoint | 4 | NEW |
| [[CDIAG-010]] | class_diagram | 9 | NEW |
| [[CMP-007]] | diagram_c4_component | 6 | NEW |
| [[DFEAT-029]] | domain_feature | 11 | NEW |
| [[DFEAT-030]] | domain_feature | 3 | NEW |
| [[DOMAIN-007]] | domain | 15 | NEW |
| [[ERD-011]] | erd | 22 | NEW |
| [[EVT-011]] | domain_event | 4 | NEW |
| [[EXTSYS-004]] | external_system | 7 | NEW |
| [[FEAT-001]] | feature | 9 | NEW |
| [[FEAT-004]] | feature | 9 | NEW |
| [[INT-006]] | integration_point | 9 | NEW |
| [[INT-008]] | integration_point | 10 | NEW |
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
| [[SCREEN-022]] | screen_spec | 36 | NEW |
| [[SCREEN-023]] | screen_spec | 39 | NEW |
| [[SD-028]] | screen_design | 3 | NEW |
| [[SD-029]] | screen_design | 4 | NEW |
| [[SEQ-002]] | diagram_sequence | 6 | NEW |
| [[SEQ-003]] | diagram_sequence | 9 | NEW |
| [[SEQ-004]] | diagram_sequence | 7 | NEW |
| [[SEQ-011]] | diagram_sequence | 7 | NEW |
| [[TEST-003]] | test_scenario | 14 | NEW |
| [[UC-001]] | use_case | 12 | NEW |
| [[UC-002]] | use_case | 13 | NEW |
| [[UC-003]] | use_case | 11 | NEW |
| [[UC-010]] | use_case | 13 | NEW |
