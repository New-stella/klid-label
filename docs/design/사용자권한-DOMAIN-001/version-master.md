# Version Master — DOMAIN-001

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-001 |
| Last sync | 2026-08-21T00:02:38.475Z |
| Mode | SYNC — NEW 0 / CHANGED 12 / UNCHANGED 41 |
| 출력 루트 | docs/design/사용자권한-DOMAIN-001 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-001 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 13건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 3건 / 스코프 밖 45건
  ℹ️  api_endpoint: 이번 키트 8건 / 스코프 밖 186건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 20건
  ℹ️  diagram_sequence: 이번 키트 1건 / 스코프 밖 25건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 7건 / 스코프 밖 26건
  ℹ️  use_case: 이번 키트 1건 / 스코프 밖 28건 (그중 domain_id 없음 2건)
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  🚨 acceptance: 이번 키트 0건 / 프로젝트 전역 51건 (그중 domain_id 없음 30건) — 전량 누락
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 8건 / 스코프 밖 37건 (그중 domain_id 없음 5건)
  🚨 feature: 이번 키트 0건 / 프로젝트 전역 10건 (그중 domain_id 없음 9건) — 전량 누락
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[ADR-039]] (prev v2)
- CHANGED [[ADR-042]] (prev v5)
- CHANGED [[API-001]] (prev v3)
- CHANGED [[API-006]] (prev v8)
- CHANGED [[API-007]] (prev v6)
- CHANGED [[ERD-029]] (prev v1)
- CHANGED [[NFR-018]] (prev v3)
- CHANGED [[ROLE-003]] (prev v6)
- CHANGED [[SD-018]] (prev v4)
- CHANGED [[SCREEN-012]] (prev v39)
- CHANGED [[SCREEN-020]] (prev v28)
- CHANGED [[SCREEN-024]] (prev v22)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-003]] | adr | 2 | UNCHANGED |
| [[ADR-004]] | adr | 4 | UNCHANGED |
| [[ADR-012]] | adr | 5 | UNCHANGED |
| [[ADR-021]] | adr | 4 | UNCHANGED |
| [[ADR-039]] | adr | 3 | CHANGED |
| [[ADR-042]] | adr | 6 | CHANGED |
| [[ADR-043]] | adr | 6 | UNCHANGED |
| [[API-001]] | api_endpoint | 5 | CHANGED |
| [[API-002]] | api_endpoint | 2 | UNCHANGED |
| [[API-003]] | api_endpoint | 4 | UNCHANGED |
| [[API-004]] | api_endpoint | 5 | UNCHANGED |
| [[API-005]] | api_endpoint | 5 | UNCHANGED |
| [[API-006]] | api_endpoint | 9 | CHANGED |
| [[API-007]] | api_endpoint | 7 | CHANGED |
| [[API-153]] | api_endpoint | 4 | UNCHANGED |
| [[CDIAG-008]] | class_diagram | 4 | UNCHANGED |
| [[DFEAT-001]] | domain_feature | 3 | UNCHANGED |
| [[DFEAT-002]] | domain_feature | 6 | UNCHANGED |
| [[DFEAT-003]] | domain_feature | 7 | UNCHANGED |
| [[DOMAIN-001]] | domain | 9 | UNCHANGED |
| [[ERD-029]] | erd | 2 | CHANGED |
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
| [[ROLE-003]] | permission_role | 7 | CHANGED |
| [[SCREEN-001]] | screen_spec | 13 | UNCHANGED |
| [[SCREEN-002]] | screen_spec | 17 | UNCHANGED |
| [[SCREEN-003]] | screen_spec | 10 | UNCHANGED |
| [[SCREEN-004]] | screen_spec | 11 | UNCHANGED |
| [[SCREEN-012]] | screen_spec | 46 | CHANGED |
| [[SCREEN-020]] | screen_spec | 32 | CHANGED |
| [[SCREEN-024]] | screen_spec | 23 | CHANGED |
| [[SD-009]] | screen_design | 7 | UNCHANGED |
| [[SD-017]] | screen_design | 2 | UNCHANGED |
| [[SD-018]] | screen_design | 5 | CHANGED |
| [[SD-019]] | screen_design | 2 | UNCHANGED |
| [[SD-020]] | screen_design | 2 | UNCHANGED |
| [[SEQ-018]] | diagram_sequence | 1 | UNCHANGED |
| [[UC-030]] | use_case | 7 | UNCHANGED |
