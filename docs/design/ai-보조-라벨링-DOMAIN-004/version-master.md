# Version Master — DOMAIN-004

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-004 |
| Last sync | 2026-09-08T04:12:25.371Z |
| Mode | SYNC — NEW 2 / CHANGED 0 / UNCHANGED 178 |
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
  ℹ️  api_endpoint: 이번 키트 33건 / 스코프 밖 190건
  ℹ️  erd: 이번 키트 3건 / 스코프 밖 21건
  ℹ️  diagram_sequence: 이번 키트 10건 / 스코프 밖 26건 (그중 domain_id 없음 11건)
  ℹ️  screen_spec: 이번 키트 5건 / 스코프 밖 33건
  ℹ️  use_case: 이번 키트 8건 / 스코프 밖 27건
  ℹ️  domain_event: 이번 키트 2건 / 스코프 밖 10건
  ℹ️  acceptance: 이번 키트 26건 / 스코프 밖 63건 (그중 domain_id 없음 4건)
  ℹ️  constant: 이번 키트 1건 / 스코프 밖 1건 (그중 domain_id 없음 1건)
  ℹ️  adr: 이번 키트 22건 / 스코프 밖 36건 (그중 domain_id 없음 6건)
  ℹ️  feature: 이번 키트 3건 / 스코프 밖 7건 (그중 domain_id 없음 6건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[AC-1100]]
- NEW [[AC-1101]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-1022]] | acceptance | 6 | UNCHANGED |
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
| [[AC-1086]] | acceptance | 2 | UNCHANGED |
| [[AC-1087]] | acceptance | 2 | UNCHANGED |
| [[AC-1088]] | acceptance | 2 | UNCHANGED |
| [[AC-1089]] | acceptance | 2 | UNCHANGED |
| [[AC-1090]] | acceptance | 2 | UNCHANGED |
| [[AC-1091]] | acceptance | 2 | UNCHANGED |
| [[AC-1092]] | acceptance | 2 | UNCHANGED |
| [[AC-1093]] | acceptance | 3 | UNCHANGED |
| [[AC-1094]] | acceptance | 3 | UNCHANGED |
| [[AC-1099]] | acceptance | 7 | UNCHANGED |
| [[AC-1100]] | acceptance | 2 | NEW |
| [[AC-1101]] | acceptance | 3 | NEW |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-008]] | adr | 4 | UNCHANGED |
| [[ADR-013]] | adr | 23 | UNCHANGED |
| [[ADR-019]] | adr | 8 | UNCHANGED |
| [[ADR-026]] | adr | 6 | UNCHANGED |
| [[ADR-029]] | adr | 2 | UNCHANGED |
| [[ADR-030]] | adr | 4 | UNCHANGED |
| [[ADR-031]] | adr | 4 | UNCHANGED |
| [[ADR-035]] | adr | 3 | UNCHANGED |
| [[ADR-039]] | adr | 7 | UNCHANGED |
| [[ADR-040]] | adr | 2 | UNCHANGED |
| [[ADR-041]] | adr | 4 | UNCHANGED |
| [[ADR-046]] | adr | 16 | UNCHANGED |
| [[ADR-047]] | adr | 1 | UNCHANGED |
| [[ADR-048]] | adr | 5 | UNCHANGED |
| [[ADR-052]] | adr | 3 | UNCHANGED |
| [[ADR-053]] | adr | 3 | UNCHANGED |
| [[ADR-054]] | adr | 5 | UNCHANGED |
| [[ADR-055]] | adr | 6 | UNCHANGED |
| [[ADR-056]] | adr | 4 | UNCHANGED |
| [[ADR-057]] | adr | 18 | UNCHANGED |
| [[ADR-062]] | adr | 6 | UNCHANGED |
| [[API-019]] | api_endpoint | 8 | UNCHANGED |
| [[API-020]] | api_endpoint | 14 | UNCHANGED |
| [[API-043]] | api_endpoint | 27 | UNCHANGED |
| [[API-065]] | api_endpoint | 25 | UNCHANGED |
| [[API-093]] | api_endpoint | 14 | UNCHANGED |
| [[API-113]] | api_endpoint | 4 | UNCHANGED |
| [[API-119]] | api_endpoint | 4 | UNCHANGED |
| [[API-120]] | api_endpoint | 4 | UNCHANGED |
| [[API-121]] | api_endpoint | 4 | UNCHANGED |
| [[API-122]] | api_endpoint | 2 | UNCHANGED |
| [[API-123]] | api_endpoint | 13 | UNCHANGED |
| [[API-124]] | api_endpoint | 10 | UNCHANGED |
| [[API-125]] | api_endpoint | 2 | UNCHANGED |
| [[API-126]] | api_endpoint | 2 | UNCHANGED |
| [[API-127]] | api_endpoint | 2 | UNCHANGED |
| [[API-152]] | api_endpoint | 11 | UNCHANGED |
| [[API-156]] | api_endpoint | 3 | UNCHANGED |
| [[API-158]] | api_endpoint | 8 | UNCHANGED |
| [[API-160]] | api_endpoint | 4 | UNCHANGED |
| [[API-162]] | api_endpoint | 6 | UNCHANGED |
| [[API-164]] | api_endpoint | 3 | UNCHANGED |
| [[API-177]] | api_endpoint | 4 | UNCHANGED |
| [[API-194]] | api_endpoint | 12 | UNCHANGED |
| [[API-204]] | api_endpoint | 1 | UNCHANGED |
| [[API-216]] | api_endpoint | 7 | UNCHANGED |
| [[API-217]] | api_endpoint | 8 | UNCHANGED |
| [[API-218]] | api_endpoint | 6 | UNCHANGED |
| [[API-224]] | api_endpoint | 2 | UNCHANGED |
| [[API-226]] | api_endpoint | 3 | UNCHANGED |
| [[API-227]] | api_endpoint | 4 | UNCHANGED |
| [[API-228]] | api_endpoint | 3 | UNCHANGED |
| [[API-229]] | api_endpoint | 3 | UNCHANGED |
| [[API-230]] | api_endpoint | 3 | UNCHANGED |
| [[CDIAG-005]] | class_diagram | 6 | UNCHANGED |
| [[CDIAG-018]] | class_diagram | 1 | UNCHANGED |
| [[CDIAG-030]] | class_diagram | 1 | UNCHANGED |
| [[CDIAG-037]] | class_diagram | 1 | UNCHANGED |
| [[CMP-008]] | diagram_c4_component | 9 | UNCHANGED |
| [[CONST-002]] | constant | 3 | UNCHANGED |
| [[DFEAT-018]] | domain_feature | 10 | UNCHANGED |
| [[DFEAT-019]] | domain_feature | 9 | UNCHANGED |
| [[DFEAT-020]] | domain_feature | 6 | UNCHANGED |
| [[DFEAT-045]] | domain_feature | 25 | UNCHANGED |
| [[DFEAT-060]] | domain_feature | 4 | UNCHANGED |
| [[DOMAIN-004]] | domain | 11 | UNCHANGED |
| [[ERD-019]] | erd | 23 | UNCHANGED |
| [[ERD-032]] | erd | 4 | UNCHANGED |
| [[ERD-034]] | erd | 4 | UNCHANGED |
| [[EVT-001]] | domain_event | 5 | UNCHANGED |
| [[EVT-005]] | domain_event | 5 | UNCHANGED |
| [[FEAT-001]] | feature | 9 | UNCHANGED |
| [[FEAT-007]] | feature | 6 | UNCHANGED |
| [[FEAT-009]] | feature | 3 | UNCHANGED |
| [[INFRA-001]] | infra_component | 8 | UNCHANGED |
| [[INFRA-003]] | infra_component | 8 | UNCHANGED |
| [[INT-004]] | integration_point | 15 | UNCHANGED |
| [[LEGACY-005]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-066]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-067]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-096]] | legacy_artifact | 1 | UNCHANGED |
| [[MODEL-001]] | model_usage | 1 | UNCHANGED |
| [[MODEL-002]] | model_usage | 1 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 6 | UNCHANGED |
| [[NFR-010]] | nfr | 4 | UNCHANGED |
| [[NFR-011]] | nfr | 7 | UNCHANGED |
| [[NFR-012]] | nfr | 6 | UNCHANGED |
| [[NFR-013]] | nfr | 10 | UNCHANGED |
| [[NFR-014]] | nfr | 5 | UNCHANGED |
| [[NFR-015]] | nfr | 6 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 10 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 3 | UNCHANGED |
| [[NFR-020]] | nfr | 10 | UNCHANGED |
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
| [[NFR-038]] | nfr | 1 | UNCHANGED |
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
| [[ROLE-001]] | permission_role | 14 | UNCHANGED |
| [[ROLE-002]] | permission_role | 9 | UNCHANGED |
| [[ROLE-003]] | permission_role | 14 | UNCHANGED |
| [[ROLE-004]] | permission_role | 5 | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 106 | UNCHANGED |
| [[SCREEN-025]] | screen_spec | 49 | UNCHANGED |
| [[SCREEN-027]] | screen_spec | 52 | UNCHANGED |
| [[SCREEN-039]] | screen_spec | 45 | UNCHANGED |
| [[SCREEN-042]] | screen_spec | 17 | UNCHANGED |
| [[SD-033]] | screen_design | 13 | UNCHANGED |
| [[SEQ-005]] | diagram_sequence | 12 | UNCHANGED |
| [[SEQ-006]] | diagram_sequence | 10 | UNCHANGED |
| [[SEQ-007]] | diagram_sequence | 8 | UNCHANGED |
| [[SEQ-023]] | diagram_sequence | 10 | UNCHANGED |
| [[SEQ-025]] | diagram_sequence | 7 | UNCHANGED |
| [[SEQ-028]] | diagram_sequence | 2 | UNCHANGED |
| [[SEQ-030]] | diagram_sequence | 10 | UNCHANGED |
| [[SEQ-031]] | diagram_sequence | 6 | UNCHANGED |
| [[SEQ-032]] | diagram_sequence | 4 | UNCHANGED |
| [[SEQ-035]] | diagram_sequence | 6 | UNCHANGED |
| [[SHELL-001]] | app_shell | 12 | UNCHANGED |
| [[UC-004]] | use_case | 18 | UNCHANGED |
| [[UC-005]] | use_case | 13 | UNCHANGED |
| [[UC-006]] | use_case | 11 | UNCHANGED |
| [[UC-034]] | use_case | 7 | UNCHANGED |
| [[UC-037]] | use_case | 16 | UNCHANGED |
| [[UC-038]] | use_case | 7 | UNCHANGED |
| [[UC-039]] | use_case | 5 | UNCHANGED |
| [[UC-042]] | use_case | 4 | UNCHANGED |
