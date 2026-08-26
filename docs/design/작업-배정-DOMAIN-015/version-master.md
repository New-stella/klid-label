# Version Master — DOMAIN-015

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-015 |
| Last sync | 2026-08-26T04:28:03.369Z |
| Mode | SYNC — NEW 0 / CHANGED 1 / UNCHANGED 50 |
| 출력 루트 | docs/design/작업-배정-DOMAIN-015 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-015 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 4건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  ℹ️  domain_feature: 이번 키트 1건 / 스코프 밖 47건
  ℹ️  api_endpoint: 이번 키트 10건 / 스코프 밖 191건
  ℹ️  erd: 이번 키트 1건 / 스코프 밖 22건
  ℹ️  diagram_sequence: 이번 키트 1건 / 스코프 밖 25건 (그중 domain_id 없음 14건)
  ℹ️  screen_spec: 이번 키트 3건 / 스코프 밖 30건
  ℹ️  use_case: 이번 키트 1건 / 스코프 밖 29건 (그중 domain_id 없음 2건)
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  ℹ️  acceptance: 이번 키트 9건 / 스코프 밖 108건 (그중 domain_id 없음 37건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  adr: 이번 키트 3건 / 스코프 밖 46건 (그중 domain_id 없음 7건)
  🚨 feature: 이번 키트 0건 / 프로젝트 전역 10건 (그중 domain_id 없음 9건) — 전량 누락
```

해소: logicraft 에서 해당 ITEM 의 `domain_id` 를 채우거나, 다운로드 시
`--cross-domain-types` 에 그 타입을 추가한다.

> 💡 이 키트 루트를 Obsidian 볼트로 열면 ITEM 관계가 그래프로 보인다.
> 그래프뷰 → 필터 → *Existing files only* 를 켜면 키트 밖 ITEM(MOD·LEGACY 등)의
> 유령 노드가 사라진다.

## Changelog (this run)

- CHANGED [[ROLE-003]] (prev v7)

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-062]] | acceptance | 1 | UNCHANGED |
| [[AC-063]] | acceptance | 1 | UNCHANGED |
| [[AC-064]] | acceptance | 1 | UNCHANGED |
| [[AC-065]] | acceptance | 1 | UNCHANGED |
| [[AC-066]] | acceptance | 1 | UNCHANGED |
| [[AC-067]] | acceptance | 1 | UNCHANGED |
| [[AC-068]] | acceptance | 1 | UNCHANGED |
| [[AC-069]] | acceptance | 1 | UNCHANGED |
| [[AC-070]] | acceptance | 1 | UNCHANGED |
| [[ADR-001]] | adr | 2 | UNCHANGED |
| [[ADR-003]] | adr | 2 | UNCHANGED |
| [[ADR-038]] | adr | 4 | UNCHANGED |
| [[API-001]] | api_endpoint | 5 | UNCHANGED |
| [[API-002]] | api_endpoint | 2 | UNCHANGED |
| [[API-070]] | api_endpoint | 8 | UNCHANGED |
| [[API-071]] | api_endpoint | 7 | UNCHANGED |
| [[API-072]] | api_endpoint | 10 | UNCHANGED |
| [[API-073]] | api_endpoint | 9 | UNCHANGED |
| [[API-116]] | api_endpoint | 8 | UNCHANGED |
| [[API-136]] | api_endpoint | 4 | UNCHANGED |
| [[API-137]] | api_endpoint | 6 | UNCHANGED |
| [[API-187]] | api_endpoint | 3 | UNCHANGED |
| [[CDIAG-007]] | class_diagram | 7 | UNCHANGED |
| [[CMP-009]] | diagram_c4_component | 13 | UNCHANGED |
| [[DFEAT-006]] | domain_feature | 8 | UNCHANGED |
| [[DOMAIN-015]] | domain | 6 | UNCHANGED |
| [[ERD-014]] | erd | 12 | UNCHANGED |
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
| [[ROLE-003]] | permission_role | 8 | CHANGED |
| [[SCREEN-008]] | screen_spec | 45 | UNCHANGED |
| [[SCREEN-011]] | screen_spec | 21 | UNCHANGED |
| [[SCREEN-012]] | screen_spec | 47 | UNCHANGED |
| [[SD-003]] | screen_design | 8 | UNCHANGED |
| [[SEQ-017]] | diagram_sequence | 1 | UNCHANGED |
| [[STATE-001]] | diagram_state | 6 | UNCHANGED |
| [[UC-029]] | use_case | 11 | UNCHANGED |
