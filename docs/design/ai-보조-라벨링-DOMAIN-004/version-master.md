# Version Master — DOMAIN-004

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-004 |
| Last sync | 2026-08-16T14:48:52.171Z |
| Mode | INITIAL — NEW 73 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | docs/design/ai-보조-라벨링-DOMAIN-004 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-004 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 3건 / 스코프 밖 40건
  ℹ️  api_endpoint: 이번 키트 21건 / 스코프 밖 160건
  🚨 erd: 이번 키트 0건 / 프로젝트 전역 19건 — 전량 누락
  ℹ️  diagram_sequence: 이번 키트 4건 / 스코프 밖 21건 (그중 domain_id 없음 11건)
  ℹ️  screen_spec: 이번 키트 3건 / 스코프 밖 29건
  ℹ️  use_case: 이번 키트 3건 / 스코프 밖 22건 (그중 domain_id 없음 2건)
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  ℹ️  acceptance: 이번 키트 3건 / 스코프 밖 22건 (그중 domain_id 없음 21건)
  ℹ️  constant: 이번 키트 1건 / 스코프 밖 1건 (그중 domain_id 없음 1건)
  ℹ️  adr: 이번 키트 10건 / 스코프 밖 31건 (그중 domain_id 없음 4건)
  ℹ️  feature: 이번 키트 3건 / 스코프 밖 6건 (그중 domain_id 없음 6건)
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- NEW [[AC-004]]
- NEW [[AC-005]]
- NEW [[AC-006]]
- NEW [[ADR-001]]
- NEW [[ADR-003]]
- NEW [[ADR-019]]
- NEW [[ADR-026]]
- NEW [[ADR-031]]
- NEW [[ADR-035]]
- NEW [[ADR-039]]
- NEW [[ADR-040]]
- NEW [[ADR-041]]
- NEW [[ADR-046]]
- NEW [[API-020]]
- NEW [[API-043]]
- NEW [[API-065]]
- NEW [[API-093]]
- NEW [[API-113]]
- NEW [[API-119]]
- NEW [[API-120]]
- NEW [[API-121]]
- NEW [[API-122]]
- NEW [[API-123]]
- NEW [[API-124]]
- NEW [[API-125]]
- NEW [[API-126]]
- NEW [[API-127]]
- NEW [[API-152]]
- NEW [[API-156]]
- NEW [[API-158]]
- NEW [[API-160]]
- NEW [[API-162]]
- NEW [[API-164]]
- NEW [[API-177]]
- NEW [[CDIAG-005]]
- NEW [[CONST-002]]
- NEW [[CMP-008]]
- NEW [[SEQ-005]]
- NEW [[SEQ-006]]
- NEW [[SEQ-007]]
- NEW [[SEQ-023]]
- NEW [[DOMAIN-004]]
- NEW [[DFEAT-018]]
- NEW [[DFEAT-019]]
- NEW [[DFEAT-020]]
- NEW [[FEAT-001]]
- NEW [[FEAT-007]]
- NEW [[FEAT-009]]
- NEW [[INFRA-001]]
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
- NEW [[SD-033]]
- NEW [[SCREEN-005]]
- NEW [[SCREEN-025]]
- NEW [[SCREEN-027]]
- NEW [[UC-004]]
- NEW [[UC-005]]
- NEW [[UC-006]]

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-004]] | acceptance | 7 | NEW |
| [[AC-005]] | acceptance | 7 | NEW |
| [[AC-006]] | acceptance | 9 | NEW |
| [[ADR-001]] | adr | 2 | NEW |
| [[ADR-003]] | adr | 2 | NEW |
| [[ADR-019]] | adr | 7 | NEW |
| [[ADR-026]] | adr | 4 | NEW |
| [[ADR-031]] | adr | 2 | NEW |
| [[ADR-035]] | adr | 3 | NEW |
| [[ADR-039]] | adr | 2 | NEW |
| [[ADR-040]] | adr | 2 | NEW |
| [[ADR-041]] | adr | 4 | NEW |
| [[ADR-046]] | adr | 1 | NEW |
| [[API-020]] | api_endpoint | 7 | NEW |
| [[API-043]] | api_endpoint | 12 | NEW |
| [[API-065]] | api_endpoint | 15 | NEW |
| [[API-093]] | api_endpoint | 9 | NEW |
| [[API-113]] | api_endpoint | 3 | NEW |
| [[API-119]] | api_endpoint | 3 | NEW |
| [[API-120]] | api_endpoint | 3 | NEW |
| [[API-121]] | api_endpoint | 3 | NEW |
| [[API-122]] | api_endpoint | 2 | NEW |
| [[API-123]] | api_endpoint | 6 | NEW |
| [[API-124]] | api_endpoint | 6 | NEW |
| [[API-125]] | api_endpoint | 2 | NEW |
| [[API-126]] | api_endpoint | 2 | NEW |
| [[API-127]] | api_endpoint | 2 | NEW |
| [[API-152]] | api_endpoint | 4 | NEW |
| [[API-156]] | api_endpoint | 2 | NEW |
| [[API-158]] | api_endpoint | 3 | NEW |
| [[API-160]] | api_endpoint | 3 | NEW |
| [[API-162]] | api_endpoint | 5 | NEW |
| [[API-164]] | api_endpoint | 2 | NEW |
| [[API-177]] | api_endpoint | 4 | NEW |
| [[CDIAG-005]] | class_diagram | 4 | NEW |
| [[CMP-008]] | diagram_c4_component | 3 | NEW |
| [[CONST-002]] | constant | 3 | NEW |
| [[DFEAT-018]] | domain_feature | 8 | NEW |
| [[DFEAT-019]] | domain_feature | 8 | NEW |
| [[DFEAT-020]] | domain_feature | 6 | NEW |
| [[DOMAIN-004]] | domain | 11 | NEW |
| [[FEAT-001]] | feature | 9 | NEW |
| [[FEAT-007]] | feature | 6 | NEW |
| [[FEAT-009]] | feature | 3 | NEW |
| [[INFRA-001]] | infra_component | 4 | NEW |
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
| [[SCREEN-025]] | screen_spec | 30 | NEW |
| [[SCREEN-027]] | screen_spec | 26 | NEW |
| [[SD-033]] | screen_design | 9 | NEW |
| [[SEQ-005]] | diagram_sequence | 8 | NEW |
| [[SEQ-006]] | diagram_sequence | 6 | NEW |
| [[SEQ-007]] | diagram_sequence | 6 | NEW |
| [[SEQ-023]] | diagram_sequence | 2 | NEW |
| [[UC-004]] | use_case | 13 | NEW |
| [[UC-005]] | use_case | 10 | NEW |
| [[UC-006]] | use_case | 9 | NEW |
