# Version Master — DOMAIN-010

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-010 |
| Last sync | 2026-08-26T05:51:35.448Z |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 181 |
| 출력 루트 | docs/design/라벨링-DOMAIN-010 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-010 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 31건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 10건 / 스코프 밖 38건
  ℹ️  api_endpoint: 이번 키트 61건 / 스코프 밖 140건
  ℹ️  erd: 이번 키트 2건 / 스코프 밖 21건
  ℹ️  diagram_sequence: 이번 키트 8건 / 스코프 밖 18건 (그중 domain_id 없음 10건)
  ℹ️  screen_spec: 이번 키트 9건 / 스코프 밖 24건
  ℹ️  use_case: 이번 키트 11건 / 스코프 밖 19건 (그중 domain_id 없음 2건)
  ℹ️  domain_event: 이번 키트 1건 / 스코프 밖 11건
  ℹ️  acceptance: 이번 키트 26건 / 스코프 밖 91건 (그중 domain_id 없음 23건)
  ℹ️  adr: 이번 키트 18건 / 스코프 밖 31건 (그중 domain_id 없음 3건)
  ℹ️  feature: 이번 키트 4건 / 스코프 밖 6건 (그중 domain_id 없음 5건)
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
| [[AC-007]] | acceptance | 7 | UNCHANGED |
| [[AC-008]] | acceptance | 11 | UNCHANGED |
| [[AC-017]] | acceptance | 6 | UNCHANGED |
| [[AC-020]] | acceptance | 4 | UNCHANGED |
| [[AC-021]] | acceptance | 5 | UNCHANGED |
| [[AC-023]] | acceptance | 6 | UNCHANGED |
| [[AC-024]] | acceptance | 10 | UNCHANGED |
| [[AC-089]] | acceptance | 1 | UNCHANGED |
| [[AC-090]] | acceptance | 1 | UNCHANGED |
| [[AC-091]] | acceptance | 1 | UNCHANGED |
| [[AC-092]] | acceptance | 1 | UNCHANGED |
| [[AC-093]] | acceptance | 3 | UNCHANGED |
| [[AC-094]] | acceptance | 1 | UNCHANGED |
| [[AC-107]] | acceptance | 2 | UNCHANGED |
| [[AC-108]] | acceptance | 2 | UNCHANGED |
| [[AC-109]] | acceptance | 2 | UNCHANGED |
| [[AC-110]] | acceptance | 2 | UNCHANGED |
| [[AC-111]] | acceptance | 3 | UNCHANGED |
| [[AC-112]] | acceptance | 3 | UNCHANGED |
| [[AC-113]] | acceptance | 2 | UNCHANGED |
| [[AC-114]] | acceptance | 2 | UNCHANGED |
| [[AC-115]] | acceptance | 5 | UNCHANGED |
| [[AC-116]] | acceptance | 3 | UNCHANGED |
| [[AC-117]] | acceptance | 2 | UNCHANGED |
| [[AC-118]] | acceptance | 3 | UNCHANGED |
| [[AC-119]] | acceptance | 2 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-002]] | adr | 2 | UNCHANGED |
| [[ADR-003]] | adr | 2 | UNCHANGED |
| [[ADR-004]] | adr | 4 | UNCHANGED |
| [[ADR-006]] | adr | 4 | UNCHANGED |
| [[ADR-007]] | adr | 3 | UNCHANGED |
| [[ADR-009]] | adr | 5 | UNCHANGED |
| [[ADR-010]] | adr | 1 | UNCHANGED |
| [[ADR-019]] | adr | 8 | UNCHANGED |
| [[ADR-020]] | adr | 10 | UNCHANGED |
| [[ADR-022]] | adr | 5 | UNCHANGED |
| [[ADR-032]] | adr | 7 | UNCHANGED |
| [[ADR-033]] | adr | 3 | UNCHANGED |
| [[ADR-034]] | adr | 4 | UNCHANGED |
| [[ADR-036]] | adr | 6 | UNCHANGED |
| [[ADR-040]] | adr | 2 | UNCHANGED |
| [[ADR-051]] | adr | 5 | UNCHANGED |
| [[ADR-054]] | adr | 5 | UNCHANGED |
| [[API-012]] | api_endpoint | 6 | UNCHANGED |
| [[API-018]] | api_endpoint | 4 | UNCHANGED |
| [[API-019]] | api_endpoint | 8 | UNCHANGED |
| [[API-020]] | api_endpoint | 14 | UNCHANGED |
| [[API-021]] | api_endpoint | 8 | UNCHANGED |
| [[API-022]] | api_endpoint | 4 | UNCHANGED |
| [[API-023]] | api_endpoint | 4 | UNCHANGED |
| [[API-024]] | api_endpoint | 6 | UNCHANGED |
| [[API-025]] | api_endpoint | 8 | UNCHANGED |
| [[API-026]] | api_endpoint | 5 | UNCHANGED |
| [[API-027]] | api_endpoint | 4 | UNCHANGED |
| [[API-028]] | api_endpoint | 3 | UNCHANGED |
| [[API-029]] | api_endpoint | 4 | UNCHANGED |
| [[API-030]] | api_endpoint | 4 | UNCHANGED |
| [[API-031]] | api_endpoint | 3 | UNCHANGED |
| [[API-032]] | api_endpoint | 8 | UNCHANGED |
| [[API-034]] | api_endpoint | 9 | UNCHANGED |
| [[API-035]] | api_endpoint | 11 | UNCHANGED |
| [[API-036]] | api_endpoint | 11 | UNCHANGED |
| [[API-037]] | api_endpoint | 12 | UNCHANGED |
| [[API-038]] | api_endpoint | 14 | UNCHANGED |
| [[API-039]] | api_endpoint | 13 | UNCHANGED |
| [[API-040]] | api_endpoint | 4 | UNCHANGED |
| [[API-041]] | api_endpoint | 5 | UNCHANGED |
| [[API-066]] | api_endpoint | 6 | UNCHANGED |
| [[API-067]] | api_endpoint | 6 | UNCHANGED |
| [[API-093]] | api_endpoint | 14 | UNCHANGED |
| [[API-102]] | api_endpoint | 13 | UNCHANGED |
| [[API-103]] | api_endpoint | 10 | UNCHANGED |
| [[API-104]] | api_endpoint | 12 | UNCHANGED |
| [[API-105]] | api_endpoint | 7 | UNCHANGED |
| [[API-117]] | api_endpoint | 5 | UNCHANGED |
| [[API-123]] | api_endpoint | 13 | UNCHANGED |
| [[API-124]] | api_endpoint | 10 | UNCHANGED |
| [[API-125]] | api_endpoint | 2 | UNCHANGED |
| [[API-126]] | api_endpoint | 2 | UNCHANGED |
| [[API-127]] | api_endpoint | 2 | UNCHANGED |
| [[API-128]] | api_endpoint | 3 | UNCHANGED |
| [[API-129]] | api_endpoint | 5 | UNCHANGED |
| [[API-132]] | api_endpoint | 5 | UNCHANGED |
| [[API-133]] | api_endpoint | 4 | UNCHANGED |
| [[API-134]] | api_endpoint | 5 | UNCHANGED |
| [[API-135]] | api_endpoint | 4 | UNCHANGED |
| [[API-168]] | api_endpoint | 2 | UNCHANGED |
| [[API-170]] | api_endpoint | 3 | UNCHANGED |
| [[API-172]] | api_endpoint | 4 | UNCHANGED |
| [[API-173]] | api_endpoint | 6 | UNCHANGED |
| [[API-174]] | api_endpoint | 6 | UNCHANGED |
| [[API-175]] | api_endpoint | 2 | UNCHANGED |
| [[API-176]] | api_endpoint | 3 | UNCHANGED |
| [[API-177]] | api_endpoint | 4 | UNCHANGED |
| [[API-178]] | api_endpoint | 8 | UNCHANGED |
| [[API-182]] | api_endpoint | 4 | UNCHANGED |
| [[API-183]] | api_endpoint | 1 | UNCHANGED |
| [[API-184]] | api_endpoint | 2 | UNCHANGED |
| [[API-185]] | api_endpoint | 8 | UNCHANGED |
| [[API-193]] | api_endpoint | 5 | UNCHANGED |
| [[API-195]] | api_endpoint | 7 | UNCHANGED |
| [[API-196]] | api_endpoint | 9 | UNCHANGED |
| [[API-197]] | api_endpoint | 5 | UNCHANGED |
| [[API-204]] | api_endpoint | 1 | UNCHANGED |
| [[CDIAG-004]] | class_diagram | 9 | UNCHANGED |
| [[CDIAG-015]] | class_diagram | 7 | UNCHANGED |
| [[CMP-004]] | diagram_c4_component | 3 | UNCHANGED |
| [[CMP-006]] | diagram_c4_component | 5 | UNCHANGED |
| [[CONST-001]] | constant | 4 | UNCHANGED |
| [[CONST-002]] | constant | 3 | UNCHANGED |
| [[DFEAT-012]] | domain_feature | 3 | UNCHANGED |
| [[DFEAT-014]] | domain_feature | 2 | UNCHANGED |
| [[DFEAT-015]] | domain_feature | 3 | UNCHANGED |
| [[DFEAT-016]] | domain_feature | 2 | UNCHANGED |
| [[DFEAT-017]] | domain_feature | 5 | UNCHANGED |
| [[DFEAT-020]] | domain_feature | 6 | UNCHANGED |
| [[DFEAT-048]] | domain_feature | 16 | UNCHANGED |
| [[DFEAT-050]] | domain_feature | 6 | UNCHANGED |
| [[DFEAT-051]] | domain_feature | 5 | UNCHANGED |
| [[DFEAT-052]] | domain_feature | 3 | UNCHANGED |
| [[DOMAIN-010]] | domain | 10 | UNCHANGED |
| [[ERD-010]] | erd | 28 | UNCHANGED |
| [[ERD-019]] | erd | 23 | UNCHANGED |
| [[EVT-004]] | domain_event | 10 | UNCHANGED |
| [[FEAT-002]] | feature | 9 | UNCHANGED |
| [[FEAT-005]] | feature | 10 | UNCHANGED |
| [[FEAT-007]] | feature | 6 | UNCHANGED |
| [[FEAT-009]] | feature | 3 | UNCHANGED |
| [[NFR-008]] | nfr | 6 | UNCHANGED |
| [[NFR-009]] | nfr | 4 | UNCHANGED |
| [[NFR-010]] | nfr | 3 | UNCHANGED |
| [[NFR-011]] | nfr | 5 | UNCHANGED |
| [[NFR-012]] | nfr | 4 | UNCHANGED |
| [[NFR-013]] | nfr | 4 | UNCHANGED |
| [[NFR-014]] | nfr | 3 | UNCHANGED |
| [[NFR-015]] | nfr | 5 | UNCHANGED |
| [[NFR-016]] | nfr | 4 | UNCHANGED |
| [[NFR-017]] | nfr | 8 | UNCHANGED |
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 6 | UNCHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[ROLE-001]] | permission_role | 10 | UNCHANGED |
| [[ROLE-002]] | permission_role | 7 | UNCHANGED |
| [[ROLE-003]] | permission_role | 8 | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 100 | UNCHANGED |
| [[SCREEN-009]] | screen_spec | 58 | UNCHANGED |
| [[SCREEN-010]] | screen_spec | 37 | UNCHANGED |
| [[SCREEN-019]] | screen_spec | 39 | UNCHANGED |
| [[SCREEN-023]] | screen_spec | 41 | UNCHANGED |
| [[SCREEN-026]] | screen_spec | 34 | UNCHANGED |
| [[SCREEN-029]] | screen_spec | 41 | UNCHANGED |
| [[SCREEN-035]] | screen_spec | 19 | UNCHANGED |
| [[SCREEN-038]] | screen_spec | 14 | UNCHANGED |
| [[SD-002]] | screen_design | 16 | UNCHANGED |
| [[SD-006]] | screen_design | 4 | UNCHANGED |
| [[SD-022]] | screen_design | 4 | UNCHANGED |
| [[SD-032]] | screen_design | 2 | UNCHANGED |
| [[SEQ-008]] | diagram_sequence | 8 | UNCHANGED |
| [[SEQ-009]] | diagram_sequence | 10 | UNCHANGED |
| [[SEQ-010]] | diagram_sequence | 14 | UNCHANGED |
| [[SEQ-014]] | diagram_sequence | 13 | UNCHANGED |
| [[SEQ-020]] | diagram_sequence | 1 | UNCHANGED |
| [[SEQ-021]] | diagram_sequence | 1 | UNCHANGED |
| [[SEQ-022]] | diagram_sequence | 5 | UNCHANGED |
| [[SEQ-023]] | diagram_sequence | 7 | UNCHANGED |
| [[TEST-002]] | test_scenario | 15 | UNCHANGED |
| [[TEST-004]] | test_scenario | 19 | UNCHANGED |
| [[TEST-007]] | test_scenario | 1 | UNCHANGED |
| [[UC-004]] | use_case | 15 | UNCHANGED |
| [[UC-005]] | use_case | 10 | UNCHANGED |
| [[UC-006]] | use_case | 9 | UNCHANGED |
| [[UC-007]] | use_case | 13 | UNCHANGED |
| [[UC-008]] | use_case | 13 | UNCHANGED |
| [[UC-021]] | use_case | 20 | UNCHANGED |
| [[UC-022]] | use_case | 22 | UNCHANGED |
| [[UC-023]] | use_case | 25 | UNCHANGED |
| [[UC-028]] | use_case | 6 | UNCHANGED |
| [[UC-032]] | use_case | 11 | UNCHANGED |
| [[UC-034]] | use_case | 5 | UNCHANGED |
