# Version Master — DOMAIN-010

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-010 |
| Last sync | 2026-08-16T14:48:54.523Z |
| Mode | INITIAL — NEW 155 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | docs/design/라벨링-DOMAIN-010 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-010 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 10건 / 스코프 밖 33건
  ℹ️  api_endpoint: 이번 키트 59건 / 스코프 밖 122건
  ℹ️  erd: 이번 키트 2건 / 스코프 밖 17건
  ℹ️  diagram_sequence: 이번 키트 8건 / 스코프 밖 17건 (그중 domain_id 없음 10건)
  ℹ️  screen_spec: 이번 키트 8건 / 스코프 밖 24건
  ℹ️  use_case: 이번 키트 10건 / 스코프 밖 15건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 7건 / 스코프 밖 18건 (그중 domain_id 없음 17건)
  ℹ️  adr: 이번 키트 16건 / 스코프 밖 25건 (그중 domain_id 없음 3건)
  ℹ️  feature: 이번 키트 4건 / 스코프 밖 5건 (그중 domain_id 없음 5건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[AC-007]]
- NEW [[AC-008]]
- NEW [[AC-017]]
- NEW [[AC-020]]
- NEW [[AC-021]]
- NEW [[AC-023]]
- NEW [[AC-024]]
- NEW [[ADR-001]]
- NEW [[ADR-002]]
- NEW [[ADR-003]]
- NEW [[ADR-004]]
- NEW [[ADR-006]]
- NEW [[ADR-007]]
- NEW [[ADR-009]]
- NEW [[ADR-010]]
- NEW [[ADR-019]]
- NEW [[ADR-020]]
- NEW [[ADR-022]]
- NEW [[ADR-032]]
- NEW [[ADR-033]]
- NEW [[ADR-034]]
- NEW [[ADR-036]]
- NEW [[ADR-040]]
- NEW [[API-012]]
- NEW [[API-018]]
- NEW [[API-019]]
- NEW [[API-020]]
- NEW [[API-021]]
- NEW [[API-022]]
- NEW [[API-023]]
- NEW [[API-024]]
- NEW [[API-025]]
- NEW [[API-026]]
- NEW [[API-027]]
- NEW [[API-028]]
- NEW [[API-029]]
- NEW [[API-030]]
- NEW [[API-031]]
- NEW [[API-032]]
- NEW [[API-034]]
- NEW [[API-035]]
- NEW [[API-036]]
- NEW [[API-037]]
- NEW [[API-038]]
- NEW [[API-039]]
- NEW [[API-040]]
- NEW [[API-041]]
- NEW [[API-066]]
- NEW [[API-067]]
- NEW [[API-093]]
- NEW [[API-102]]
- NEW [[API-103]]
- NEW [[API-104]]
- NEW [[API-105]]
- NEW [[API-117]]
- NEW [[API-123]]
- NEW [[API-124]]
- NEW [[API-125]]
- NEW [[API-126]]
- NEW [[API-127]]
- NEW [[API-128]]
- NEW [[API-129]]
- NEW [[API-132]]
- NEW [[API-133]]
- NEW [[API-134]]
- NEW [[API-135]]
- NEW [[API-168]]
- NEW [[API-170]]
- NEW [[API-172]]
- NEW [[API-173]]
- NEW [[API-174]]
- NEW [[API-175]]
- NEW [[API-176]]
- NEW [[API-177]]
- NEW [[API-178]]
- NEW [[API-182]]
- NEW [[API-183]]
- NEW [[API-184]]
- NEW [[API-193]]
- NEW [[API-195]]
- NEW [[API-196]]
- NEW [[API-197]]
- NEW [[CDIAG-004]]
- NEW [[CDIAG-015]]
- NEW [[CONST-001]]
- NEW [[CONST-002]]
- NEW [[CMP-004]]
- NEW [[CMP-006]]
- NEW [[SEQ-008]]
- NEW [[SEQ-009]]
- NEW [[SEQ-010]]
- NEW [[SEQ-014]]
- NEW [[SEQ-020]]
- NEW [[SEQ-021]]
- NEW [[SEQ-022]]
- NEW [[SEQ-023]]
- NEW [[DOMAIN-010]]
- NEW [[EVT-004]]
- NEW [[DFEAT-012]]
- NEW [[DFEAT-014]]
- NEW [[DFEAT-015]]
- NEW [[DFEAT-016]]
- NEW [[DFEAT-017]]
- NEW [[DFEAT-020]]
- NEW [[DFEAT-048]]
- NEW [[DFEAT-050]]
- NEW [[DFEAT-051]]
- NEW [[DFEAT-052]]
- NEW [[ERD-010]]
- NEW [[ERD-019]]
- NEW [[FEAT-002]]
- NEW [[FEAT-005]]
- NEW [[FEAT-007]]
- NEW [[FEAT-009]]
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
- NEW [[SD-002]]
- NEW [[SD-006]]
- NEW [[SD-022]]
- NEW [[SD-032]]
- NEW [[SCREEN-005]]
- NEW [[SCREEN-009]]
- NEW [[SCREEN-010]]
- NEW [[SCREEN-019]]
- NEW [[SCREEN-023]]
- NEW [[SCREEN-026]]
- NEW [[SCREEN-029]]
- NEW [[SCREEN-035]]
- NEW [[TEST-002]]
- NEW [[TEST-004]]
- NEW [[UC-004]]
- NEW [[UC-005]]
- NEW [[UC-006]]
- NEW [[UC-007]]
- NEW [[UC-008]]
- NEW [[UC-021]]
- NEW [[UC-022]]
- NEW [[UC-023]]
- NEW [[UC-028]]
- NEW [[UC-032]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-007]] | acceptance | 5 | NEW |
| [[AC-008]] | acceptance | 11 | NEW |
| [[AC-017]] | acceptance | 5 | NEW |
| [[AC-020]] | acceptance | 4 | NEW |
| [[AC-021]] | acceptance | 5 | NEW |
| [[AC-023]] | acceptance | 6 | NEW |
| [[AC-024]] | acceptance | 5 | NEW |
| [[ADR-001]] | adr | 2 | NEW |
| [[ADR-002]] | adr | 2 | NEW |
| [[ADR-003]] | adr | 2 | NEW |
| [[ADR-004]] | adr | 4 | NEW |
| [[ADR-006]] | adr | 4 | NEW |
| [[ADR-007]] | adr | 3 | NEW |
| [[ADR-009]] | adr | 5 | NEW |
| [[ADR-010]] | adr | 1 | NEW |
| [[ADR-019]] | adr | 7 | NEW |
| [[ADR-020]] | adr | 10 | NEW |
| [[ADR-022]] | adr | 5 | NEW |
| [[ADR-032]] | adr | 7 | NEW |
| [[ADR-033]] | adr | 3 | NEW |
| [[ADR-034]] | adr | 3 | NEW |
| [[ADR-036]] | adr | 4 | NEW |
| [[ADR-040]] | adr | 2 | NEW |
| [[API-012]] | api_endpoint | 6 | NEW |
| [[API-018]] | api_endpoint | 4 | NEW |
| [[API-019]] | api_endpoint | 8 | NEW |
| [[API-020]] | api_endpoint | 7 | NEW |
| [[API-021]] | api_endpoint | 7 | NEW |
| [[API-022]] | api_endpoint | 4 | NEW |
| [[API-023]] | api_endpoint | 4 | NEW |
| [[API-024]] | api_endpoint | 5 | NEW |
| [[API-025]] | api_endpoint | 8 | NEW |
| [[API-026]] | api_endpoint | 5 | NEW |
| [[API-027]] | api_endpoint | 4 | NEW |
| [[API-028]] | api_endpoint | 3 | NEW |
| [[API-029]] | api_endpoint | 4 | NEW |
| [[API-030]] | api_endpoint | 4 | NEW |
| [[API-031]] | api_endpoint | 3 | NEW |
| [[API-032]] | api_endpoint | 8 | NEW |
| [[API-034]] | api_endpoint | 8 | NEW |
| [[API-035]] | api_endpoint | 10 | NEW |
| [[API-036]] | api_endpoint | 10 | NEW |
| [[API-037]] | api_endpoint | 5 | NEW |
| [[API-038]] | api_endpoint | 7 | NEW |
| [[API-039]] | api_endpoint | 6 | NEW |
| [[API-040]] | api_endpoint | 3 | NEW |
| [[API-041]] | api_endpoint | 4 | NEW |
| [[API-066]] | api_endpoint | 4 | NEW |
| [[API-067]] | api_endpoint | 5 | NEW |
| [[API-093]] | api_endpoint | 9 | NEW |
| [[API-102]] | api_endpoint | 13 | NEW |
| [[API-103]] | api_endpoint | 9 | NEW |
| [[API-104]] | api_endpoint | 12 | NEW |
| [[API-105]] | api_endpoint | 7 | NEW |
| [[API-117]] | api_endpoint | 5 | NEW |
| [[API-123]] | api_endpoint | 6 | NEW |
| [[API-124]] | api_endpoint | 6 | NEW |
| [[API-125]] | api_endpoint | 2 | NEW |
| [[API-126]] | api_endpoint | 2 | NEW |
| [[API-127]] | api_endpoint | 2 | NEW |
| [[API-128]] | api_endpoint | 3 | NEW |
| [[API-129]] | api_endpoint | 5 | NEW |
| [[API-132]] | api_endpoint | 2 | NEW |
| [[API-133]] | api_endpoint | 3 | NEW |
| [[API-134]] | api_endpoint | 4 | NEW |
| [[API-135]] | api_endpoint | 3 | NEW |
| [[API-168]] | api_endpoint | 2 | NEW |
| [[API-170]] | api_endpoint | 3 | NEW |
| [[API-172]] | api_endpoint | 3 | NEW |
| [[API-173]] | api_endpoint | 5 | NEW |
| [[API-174]] | api_endpoint | 5 | NEW |
| [[API-175]] | api_endpoint | 2 | NEW |
| [[API-176]] | api_endpoint | 3 | NEW |
| [[API-177]] | api_endpoint | 4 | NEW |
| [[API-178]] | api_endpoint | 8 | NEW |
| [[API-182]] | api_endpoint | 3 | NEW |
| [[API-183]] | api_endpoint | 1 | NEW |
| [[API-184]] | api_endpoint | 2 | NEW |
| [[API-193]] | api_endpoint | 3 | NEW |
| [[API-195]] | api_endpoint | 6 | NEW |
| [[API-196]] | api_endpoint | 8 | NEW |
| [[API-197]] | api_endpoint | 4 | NEW |
| [[CDIAG-004]] | class_diagram | 9 | NEW |
| [[CDIAG-015]] | class_diagram | 7 | NEW |
| [[CMP-004]] | diagram_c4_component | 3 | NEW |
| [[CMP-006]] | diagram_c4_component | 5 | NEW |
| [[CONST-001]] | constant | 4 | NEW |
| [[CONST-002]] | constant | 3 | NEW |
| [[DFEAT-012]] | domain_feature | 3 | NEW |
| [[DFEAT-014]] | domain_feature | 2 | NEW |
| [[DFEAT-015]] | domain_feature | 3 | NEW |
| [[DFEAT-016]] | domain_feature | 2 | NEW |
| [[DFEAT-017]] | domain_feature | 5 | NEW |
| [[DFEAT-020]] | domain_feature | 6 | NEW |
| [[DFEAT-048]] | domain_feature | 16 | NEW |
| [[DFEAT-050]] | domain_feature | 4 | NEW |
| [[DFEAT-051]] | domain_feature | 4 | NEW |
| [[DFEAT-052]] | domain_feature | 3 | NEW |
| [[DOMAIN-010]] | domain | 10 | NEW |
| [[ERD-010]] | erd | 25 | NEW |
| [[ERD-019]] | erd | 21 | NEW |
| [[EVT-004]] | domain_event | 10 | NEW |
| [[FEAT-002]] | feature | 8 | NEW |
| [[FEAT-005]] | feature | 9 | NEW |
| [[FEAT-007]] | feature | 6 | NEW |
| [[FEAT-009]] | feature | 3 | NEW |
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
| [[SCREEN-009]] | screen_spec | 44 | NEW |
| [[SCREEN-010]] | screen_spec | 37 | NEW |
| [[SCREEN-019]] | screen_spec | 29 | NEW |
| [[SCREEN-023]] | screen_spec | 39 | NEW |
| [[SCREEN-026]] | screen_spec | 26 | NEW |
| [[SCREEN-029]] | screen_spec | 34 | NEW |
| [[SCREEN-035]] | screen_spec | 16 | NEW |
| [[SD-002]] | screen_design | 11 | NEW |
| [[SD-006]] | screen_design | 4 | NEW |
| [[SD-022]] | screen_design | 3 | NEW |
| [[SD-032]] | screen_design | 2 | NEW |
| [[SEQ-008]] | diagram_sequence | 5 | NEW |
| [[SEQ-009]] | diagram_sequence | 10 | NEW |
| [[SEQ-010]] | diagram_sequence | 13 | NEW |
| [[SEQ-014]] | diagram_sequence | 13 | NEW |
| [[SEQ-020]] | diagram_sequence | 1 | NEW |
| [[SEQ-021]] | diagram_sequence | 1 | NEW |
| [[SEQ-022]] | diagram_sequence | 1 | NEW |
| [[SEQ-023]] | diagram_sequence | 2 | NEW |
| [[TEST-002]] | test_scenario | 11 | NEW |
| [[TEST-004]] | test_scenario | 17 | NEW |
| [[UC-004]] | use_case | 13 | NEW |
| [[UC-005]] | use_case | 10 | NEW |
| [[UC-006]] | use_case | 9 | NEW |
| [[UC-007]] | use_case | 12 | NEW |
| [[UC-008]] | use_case | 13 | NEW |
| [[UC-021]] | use_case | 20 | NEW |
| [[UC-022]] | use_case | 17 | NEW |
| [[UC-023]] | use_case | 22 | NEW |
| [[UC-028]] | use_case | 5 | NEW |
| [[UC-032]] | use_case | 6 | NEW |
