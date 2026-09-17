# Version Master — DOMAIN-004

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-004 |
| Last sync | 2026-09-17T01:15:46.421Z |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 186 |
| 출력 루트 | docs/design/ai-보조-라벨링-DOMAIN-004 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-004 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 26건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 5건 / 스코프 밖 44건
  ℹ️  api_endpoint: 이번 키트 37건 / 스코프 밖 201건
  ℹ️  erd: 이번 키트 3건 / 스코프 밖 22건
  ℹ️  diagram_sequence: 이번 키트 10건 / 스코프 밖 26건 (그중 domain_id 없음 11건)
  ℹ️  screen_spec: 이번 키트 6건 / 스코프 밖 33건
  ℹ️  use_case: 이번 키트 8건 / 스코프 밖 29건
  ℹ️  domain_event: 이번 키트 2건 / 스코프 밖 10건
  ℹ️  acceptance: 이번 키트 27건 / 스코프 밖 96건 (그중 domain_id 없음 13건)
  ℹ️  constant: 이번 키트 1건 / 스코프 밖 1건 (그중 domain_id 없음 1건)
  ℹ️  adr: 이번 키트 22건 / 스코프 밖 43건 (그중 domain_id 없음 7건)
  ℹ️  feature: 이번 키트 3건 / 스코프 밖 11건 (그중 domain_id 없음 10건)
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
| [[AC-1022]] | acceptance | 7 | UNCHANGED |
| [[AC-1023]] | acceptance | 6 | UNCHANGED |
| [[AC-1024]] | acceptance | 7 | UNCHANGED |
| [[AC-1025]] | acceptance | 5 | UNCHANGED |
| [[AC-1026]] | acceptance | 5 | UNCHANGED |
| [[AC-1027]] | acceptance | 5 | UNCHANGED |
| [[AC-1028]] | acceptance | 4 | UNCHANGED |
| [[AC-1029]] | acceptance | 5 | UNCHANGED |
| [[AC-1030]] | acceptance | 4 | UNCHANGED |
| [[AC-1031]] | acceptance | 5 | UNCHANGED |
| [[AC-1032]] | acceptance | 9 | UNCHANGED |
| [[AC-1033]] | acceptance | 11 | UNCHANGED |
| [[AC-1034]] | acceptance | 6 | UNCHANGED |
| [[AC-1035]] | acceptance | 5 | UNCHANGED |
| [[AC-1086]] | acceptance | 3 | UNCHANGED |
| [[AC-1087]] | acceptance | 2 | UNCHANGED |
| [[AC-1088]] | acceptance | 2 | UNCHANGED |
| [[AC-1089]] | acceptance | 2 | UNCHANGED |
| [[AC-1090]] | acceptance | 2 | UNCHANGED |
| [[AC-1091]] | acceptance | 2 | UNCHANGED |
| [[AC-1092]] | acceptance | 2 | UNCHANGED |
| [[AC-1093]] | acceptance | 3 | UNCHANGED |
| [[AC-1094]] | acceptance | 3 | UNCHANGED |
| [[AC-1099]] | acceptance | 9 | UNCHANGED |
| [[AC-1100]] | acceptance | 3 | UNCHANGED |
| [[AC-1101]] | acceptance | 5 | UNCHANGED |
| [[AC-1108]] | acceptance | 3 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-008]] | adr | 4 | UNCHANGED |
| [[ADR-013]] | adr | 25 | UNCHANGED |
| [[ADR-019]] | adr | 8 | UNCHANGED |
| [[ADR-026]] | adr | 7 | UNCHANGED |
| [[ADR-029]] | adr | 2 | UNCHANGED |
| [[ADR-030]] | adr | 4 | UNCHANGED |
| [[ADR-031]] | adr | 4 | UNCHANGED |
| [[ADR-035]] | adr | 3 | UNCHANGED |
| [[ADR-039]] | adr | 7 | UNCHANGED |
| [[ADR-040]] | adr | 2 | UNCHANGED |
| [[ADR-041]] | adr | 4 | UNCHANGED |
| [[ADR-046]] | adr | 18 | UNCHANGED |
| [[ADR-047]] | adr | 1 | UNCHANGED |
| [[ADR-048]] | adr | 6 | UNCHANGED |
| [[ADR-052]] | adr | 3 | UNCHANGED |
| [[ADR-053]] | adr | 3 | UNCHANGED |
| [[ADR-054]] | adr | 5 | UNCHANGED |
| [[ADR-055]] | adr | 6 | UNCHANGED |
| [[ADR-056]] | adr | 4 | UNCHANGED |
| [[ADR-057]] | adr | 22 | UNCHANGED |
| [[ADR-062]] | adr | 8 | UNCHANGED |
| [[API-019]] | api_endpoint | 8 | UNCHANGED |
| [[API-020]] | api_endpoint | 15 | UNCHANGED |
| [[API-043]] | api_endpoint | 28 | UNCHANGED |
| [[API-065]] | api_endpoint | 25 | UNCHANGED |
| [[API-093]] | api_endpoint | 15 | UNCHANGED |
| [[API-113]] | api_endpoint | 4 | UNCHANGED |
| [[API-119]] | api_endpoint | 4 | UNCHANGED |
| [[API-120]] | api_endpoint | 4 | UNCHANGED |
| [[API-121]] | api_endpoint | 4 | UNCHANGED |
| [[API-122]] | api_endpoint | 2 | UNCHANGED |
| [[API-123]] | api_endpoint | 14 | UNCHANGED |
| [[API-124]] | api_endpoint | 11 | UNCHANGED |
| [[API-125]] | api_endpoint | 2 | UNCHANGED |
| [[API-126]] | api_endpoint | 2 | UNCHANGED |
| [[API-127]] | api_endpoint | 2 | UNCHANGED |
| [[API-152]] | api_endpoint | 11 | UNCHANGED |
| [[API-156]] | api_endpoint | 3 | UNCHANGED |
| [[API-158]] | api_endpoint | 8 | UNCHANGED |
| [[API-160]] | api_endpoint | 4 | UNCHANGED |
| [[API-162]] | api_endpoint | 6 | UNCHANGED |
| [[API-164]] | api_endpoint | 3 | UNCHANGED |
| [[API-177]] | api_endpoint | 5 | UNCHANGED |
| [[API-194]] | api_endpoint | 12 | UNCHANGED |
| [[API-204]] | api_endpoint | 2 | UNCHANGED |
| [[API-216]] | api_endpoint | 7 | UNCHANGED |
| [[API-217]] | api_endpoint | 8 | UNCHANGED |
| [[API-218]] | api_endpoint | 6 | UNCHANGED |
| [[API-224]] | api_endpoint | 2 | UNCHANGED |
| [[API-226]] | api_endpoint | 4 | UNCHANGED |
| [[API-227]] | api_endpoint | 5 | UNCHANGED |
| [[API-228]] | api_endpoint | 4 | UNCHANGED |
| [[API-229]] | api_endpoint | 5 | UNCHANGED |
| [[API-230]] | api_endpoint | 4 | UNCHANGED |
| [[API-254]] | api_endpoint | 5 | UNCHANGED |
| [[API-255]] | api_endpoint | 4 | UNCHANGED |
| [[API-257]] | api_endpoint | 4 | UNCHANGED |
| [[API-258]] | api_endpoint | 3 | UNCHANGED |
| [[CDIAG-005]] | class_diagram | 6 | UNCHANGED |
| [[CDIAG-018]] | class_diagram | 1 | UNCHANGED |
| [[CDIAG-030]] | class_diagram | 1 | UNCHANGED |
| [[CDIAG-037]] | class_diagram | 1 | UNCHANGED |
| [[CMP-008]] | diagram_c4_component | 9 | UNCHANGED |
| [[CONST-002]] | constant | 3 | UNCHANGED |
| [[DFEAT-018]] | domain_feature | 10 | UNCHANGED |
| [[DFEAT-019]] | domain_feature | 9 | UNCHANGED |
| [[DFEAT-020]] | domain_feature | 6 | UNCHANGED |
| [[DFEAT-045]] | domain_feature | 28 | UNCHANGED |
| [[DFEAT-060]] | domain_feature | 4 | UNCHANGED |
| [[DOMAIN-004]] | domain | 11 | UNCHANGED |
| [[ERD-019]] | erd | 24 | UNCHANGED |
| [[ERD-032]] | erd | 4 | UNCHANGED |
| [[ERD-034]] | erd | 4 | UNCHANGED |
| [[EVT-001]] | domain_event | 5 | UNCHANGED |
| [[EVT-005]] | domain_event | 6 | UNCHANGED |
| [[FEAT-001]] | feature | 9 | UNCHANGED |
| [[FEAT-007]] | feature | 6 | UNCHANGED |
| [[FEAT-009]] | feature | 3 | UNCHANGED |
| [[INFRA-001]] | infra_component | 8 | UNCHANGED |
| [[INFRA-003]] | infra_component | 8 | UNCHANGED |
| [[INT-004]] | integration_point | 18 | UNCHANGED |
| [[LEGACY-005]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-066]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-067]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-096]] | legacy_artifact | 1 | UNCHANGED |
| [[MODEL-001]] | model_usage | 1 | UNCHANGED |
| [[MODEL-002]] | model_usage | 1 | UNCHANGED |
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
| [[SCREEN-005]] | screen_spec | 118 | UNCHANGED |
| [[SCREEN-025]] | screen_spec | 49 | UNCHANGED |
| [[SCREEN-027]] | screen_spec | 54 | UNCHANGED |
| [[SCREEN-029]] | screen_spec | 58 | UNCHANGED |
| [[SCREEN-039]] | screen_spec | 45 | UNCHANGED |
| [[SCREEN-042]] | screen_spec | 27 | UNCHANGED |
| [[SD-033]] | screen_design | 14 | UNCHANGED |
| [[SEQ-005]] | diagram_sequence | 12 | UNCHANGED |
| [[SEQ-006]] | diagram_sequence | 10 | UNCHANGED |
| [[SEQ-007]] | diagram_sequence | 8 | UNCHANGED |
| [[SEQ-023]] | diagram_sequence | 11 | UNCHANGED |
| [[SEQ-025]] | diagram_sequence | 10 | UNCHANGED |
| [[SEQ-028]] | diagram_sequence | 2 | UNCHANGED |
| [[SEQ-030]] | diagram_sequence | 10 | UNCHANGED |
| [[SEQ-031]] | diagram_sequence | 9 | UNCHANGED |
| [[SEQ-032]] | diagram_sequence | 4 | UNCHANGED |
| [[SEQ-035]] | diagram_sequence | 7 | UNCHANGED |
| [[SHELL-001]] | app_shell | 19 | UNCHANGED |
| [[UC-004]] | use_case | 19 | UNCHANGED |
| [[UC-005]] | use_case | 14 | UNCHANGED |
| [[UC-006]] | use_case | 12 | UNCHANGED |
| [[UC-034]] | use_case | 9 | UNCHANGED |
| [[UC-037]] | use_case | 16 | UNCHANGED |
| [[UC-038]] | use_case | 9 | UNCHANGED |
| [[UC-039]] | use_case | 5 | UNCHANGED |
| [[UC-042]] | use_case | 6 | UNCHANGED |
