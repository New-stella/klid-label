# Version Master — DOMAIN-004

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-004 |
| Last sync | 2026-08-21T04:17:30.979Z |
| Mode | SYNC — NEW 0 / CHANGED 1 / UNCHANGED 83 |
| 출력 루트 | docs/design/ai-보조-라벨링-DOMAIN-004 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-004 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 6건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 3건 / 스코프 밖 45건
  ℹ️  api_endpoint: 이번 키트 22건 / 스코프 밖 172건
  🚨 erd: 이번 키트 0건 / 프로젝트 전역 21건 — 전량 누락
  ℹ️  diagram_sequence: 이번 키트 4건 / 스코프 밖 22건 (그중 domain_id 없음 11건)
  ℹ️  screen_spec: 이번 키트 3건 / 스코프 밖 30건
  ℹ️  use_case: 이번 키트 4건 / 스코프 밖 25건 (그중 domain_id 없음 2건)
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  ℹ️  acceptance: 이번 키트 6건 / 스코프 밖 46건 (그중 domain_id 없음 24건)
  ℹ️  constant: 이번 키트 1건 / 스코프 밖 1건 (그중 domain_id 없음 1건)
  ℹ️  adr: 이번 키트 11건 / 스코프 밖 34건 (그중 domain_id 없음 4건)
  ℹ️  feature: 이번 키트 3건 / 스코프 밖 7건 (그중 domain_id 없음 6건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[API-043]] (prev v15)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-004]] | acceptance | 7 | UNCHANGED |
| [[AC-005]] | acceptance | 7 | UNCHANGED |
| [[AC-006]] | acceptance | 9 | UNCHANGED |
| [[AC-038]] | acceptance | 3 | UNCHANGED |
| [[AC-039]] | acceptance | 3 | UNCHANGED |
| [[AC-040]] | acceptance | 3 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-003]] | adr | 2 | UNCHANGED |
| [[ADR-019]] | adr | 7 | UNCHANGED |
| [[ADR-026]] | adr | 4 | UNCHANGED |
| [[ADR-031]] | adr | 2 | UNCHANGED |
| [[ADR-035]] | adr | 3 | UNCHANGED |
| [[ADR-039]] | adr | 3 | UNCHANGED |
| [[ADR-040]] | adr | 2 | UNCHANGED |
| [[ADR-041]] | adr | 4 | UNCHANGED |
| [[ADR-046]] | adr | 1 | UNCHANGED |
| [[ADR-047]] | adr | 1 | UNCHANGED |
| [[API-020]] | api_endpoint | 14 | UNCHANGED |
| [[API-043]] | api_endpoint | 16 | CHANGED |
| [[API-065]] | api_endpoint | 15 | UNCHANGED |
| [[API-093]] | api_endpoint | 14 | UNCHANGED |
| [[API-113]] | api_endpoint | 3 | UNCHANGED |
| [[API-119]] | api_endpoint | 3 | UNCHANGED |
| [[API-120]] | api_endpoint | 3 | UNCHANGED |
| [[API-121]] | api_endpoint | 3 | UNCHANGED |
| [[API-122]] | api_endpoint | 2 | UNCHANGED |
| [[API-123]] | api_endpoint | 13 | UNCHANGED |
| [[API-124]] | api_endpoint | 10 | UNCHANGED |
| [[API-125]] | api_endpoint | 2 | UNCHANGED |
| [[API-126]] | api_endpoint | 2 | UNCHANGED |
| [[API-127]] | api_endpoint | 2 | UNCHANGED |
| [[API-152]] | api_endpoint | 6 | UNCHANGED |
| [[API-156]] | api_endpoint | 2 | UNCHANGED |
| [[API-158]] | api_endpoint | 3 | UNCHANGED |
| [[API-160]] | api_endpoint | 3 | UNCHANGED |
| [[API-162]] | api_endpoint | 5 | UNCHANGED |
| [[API-164]] | api_endpoint | 2 | UNCHANGED |
| [[API-177]] | api_endpoint | 4 | UNCHANGED |
| [[API-204]] | api_endpoint | 1 | UNCHANGED |
| [[CDIAG-005]] | class_diagram | 5 | UNCHANGED |
| [[CMP-008]] | diagram_c4_component | 3 | UNCHANGED |
| [[CONST-002]] | constant | 3 | UNCHANGED |
| [[DFEAT-018]] | domain_feature | 8 | UNCHANGED |
| [[DFEAT-019]] | domain_feature | 9 | UNCHANGED |
| [[DFEAT-020]] | domain_feature | 6 | UNCHANGED |
| [[DOMAIN-004]] | domain | 11 | UNCHANGED |
| [[FEAT-001]] | feature | 9 | UNCHANGED |
| [[FEAT-007]] | feature | 6 | UNCHANGED |
| [[FEAT-009]] | feature | 3 | UNCHANGED |
| [[INFRA-001]] | infra_component | 4 | UNCHANGED |
| [[LEGACY-005]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-066]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-067]] | legacy_artifact | 1 | UNCHANGED |
| [[LEGACY-096]] | legacy_artifact | 1 | UNCHANGED |
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
| [[NFR-018]] | nfr | 6 | UNCHANGED |
| [[NFR-019]] | nfr | 2 | UNCHANGED |
| [[NFR-020]] | nfr | 4 | UNCHANGED |
| [[NFR-021]] | nfr | 1 | UNCHANGED |
| [[ROLE-001]] | permission_role | 10 | UNCHANGED |
| [[ROLE-002]] | permission_role | 6 | UNCHANGED |
| [[ROLE-003]] | permission_role | 7 | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 96 | UNCHANGED |
| [[SCREEN-025]] | screen_spec | 38 | UNCHANGED |
| [[SCREEN-027]] | screen_spec | 28 | UNCHANGED |
| [[SD-033]] | screen_design | 9 | UNCHANGED |
| [[SEQ-005]] | diagram_sequence | 8 | UNCHANGED |
| [[SEQ-006]] | diagram_sequence | 6 | UNCHANGED |
| [[SEQ-007]] | diagram_sequence | 6 | UNCHANGED |
| [[SEQ-023]] | diagram_sequence | 2 | UNCHANGED |
| [[SHELL-001]] | app_shell | 10 | UNCHANGED |
| [[UC-004]] | use_case | 15 | UNCHANGED |
| [[UC-005]] | use_case | 10 | UNCHANGED |
| [[UC-006]] | use_case | 9 | UNCHANGED |
| [[UC-034]] | use_case | 5 | UNCHANGED |
