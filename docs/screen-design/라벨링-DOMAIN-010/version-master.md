# Version Master — DOMAIN-010

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-010 |
| Last sync | 2026-09-02T10:54:13.705Z |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 212 |
| 출력 루트 | docs/screen-design/라벨링-DOMAIN-010 |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-010 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 126건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  🚨 domain_feature: 이번 키트 0건 / 프로젝트 전역 48건 — 전량 누락
  ℹ️  api_endpoint: 이번 키트 49건 / 스코프 밖 171건
  🚨 erd: 이번 키트 0건 / 프로젝트 전역 23건 — 전량 누락
  🚨 diagram_sequence: 이번 키트 0건 / 프로젝트 전역 26건 (그중 domain_id 없음 14건) — 전량 누락
  ℹ️  screen_spec: 이번 키트 2건 / 스코프 밖 36건
  ℹ️  use_case: 이번 키트 9건 / 스코프 밖 26건 (그중 domain_id 없음 3건)
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  🚨 acceptance: 이번 키트 0건 / 프로젝트 전역 80건 (그중 domain_id 없음 2건) — 전량 누락
  ℹ️  permission_role: 이번 키트 3건 / 스코프 밖 1건 (그중 domain_id 없음 1건)
  🚨 constant: 이번 키트 0건 / 프로젝트 전역 2건 (그중 domain_id 없음 2건) — 전량 누락
  🚨 adr: 이번 키트 0건 / 프로젝트 전역 52건 (그중 domain_id 없음 10건) — 전량 누락
  🚨 nfr: 이번 키트 0건 / 프로젝트 전역 15건 (그중 domain_id 없음 15건) — 전량 누락
  🚨 feature: 이번 키트 0건 / 프로젝트 전역 10건 (그중 domain_id 없음 9건) — 전량 누락
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
| [[API-012]] | api_endpoint | 6 | UNCHANGED |
| [[API-018]] | api_endpoint | 5 | UNCHANGED |
| [[API-019]] | api_endpoint | 8 | UNCHANGED |
| [[API-020]] | api_endpoint | 14 | UNCHANGED |
| [[API-021]] | api_endpoint | 9 | UNCHANGED |
| [[API-022]] | api_endpoint | 4 | UNCHANGED |
| [[API-023]] | api_endpoint | 4 | UNCHANGED |
| [[API-024]] | api_endpoint | 6 | UNCHANGED |
| [[API-032]] | api_endpoint | 8 | UNCHANGED |
| [[API-034]] | api_endpoint | 9 | UNCHANGED |
| [[API-035]] | api_endpoint | 11 | UNCHANGED |
| [[API-036]] | api_endpoint | 11 | UNCHANGED |
| [[API-037]] | api_endpoint | 12 | UNCHANGED |
| [[API-038]] | api_endpoint | 14 | UNCHANGED |
| [[API-039]] | api_endpoint | 13 | UNCHANGED |
| [[API-040]] | api_endpoint | 4 | UNCHANGED |
| [[API-041]] | api_endpoint | 5 | UNCHANGED |
| [[API-066]] | api_endpoint | 7 | UNCHANGED |
| [[API-067]] | api_endpoint | 8 | UNCHANGED |
| [[API-093]] | api_endpoint | 14 | UNCHANGED |
| [[API-102]] | api_endpoint | 14 | UNCHANGED |
| [[API-103]] | api_endpoint | 11 | UNCHANGED |
| [[API-104]] | api_endpoint | 14 | UNCHANGED |
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
| [[API-177]] | api_endpoint | 4 | UNCHANGED |
| [[API-178]] | api_endpoint | 8 | UNCHANGED |
| [[API-182]] | api_endpoint | 4 | UNCHANGED |
| [[API-183]] | api_endpoint | 1 | UNCHANGED |
| [[API-184]] | api_endpoint | 2 | UNCHANGED |
| [[API-193]] | api_endpoint | 5 | UNCHANGED |
| [[API-195]] | api_endpoint | 7 | UNCHANGED |
| [[API-196]] | api_endpoint | 9 | UNCHANGED |
| [[API-197]] | api_endpoint | 5 | UNCHANGED |
| [[DS-001]] | design_system | 9 | UNCHANGED |
| [[NAV-001]] | navigation_tree | 26 | UNCHANGED |
| [[ROLE-001]] | permission_role | 14 | UNCHANGED |
| [[ROLE-002]] | permission_role | 9 | UNCHANGED |
| [[ROLE-004]] | permission_role | 5 | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 102 | UNCHANGED |
| [[SCREEN-026]] | screen_spec | 35 | UNCHANGED |
| [[SD-002]] | screen_design | 17 | UNCHANGED |
| [[SD-006]] | screen_design | 5 | UNCHANGED |
| [[SHELL-001]] | app_shell | 11 | UNCHANGED |
| [[UC-004]] | use_case | 17 | UNCHANGED |
| [[UC-005]] | use_case | 12 | UNCHANGED |
| [[UC-006]] | use_case | 11 | UNCHANGED |
| [[UC-007]] | use_case | 15 | UNCHANGED |
| [[UC-008]] | use_case | 16 | UNCHANGED |
| [[UC-021]] | use_case | 22 | UNCHANGED |
| [[UC-022]] | use_case | 24 | UNCHANGED |
| [[UC-032]] | use_case | 14 | UNCHANGED |
| [[UC-034]] | use_case | 7 | UNCHANGED |
| [[UI-001]] | ui_component | 3 | UNCHANGED |
| [[UI-002]] | ui_component | 6 | UNCHANGED |
| [[UI-003]] | ui_component | 5 | UNCHANGED |
| [[UI-004]] | ui_component | 4 | UNCHANGED |
| [[UI-005]] | ui_component | 4 | UNCHANGED |
| [[UI-006]] | ui_component | 4 | UNCHANGED |
| [[UI-007]] | ui_component | 7 | UNCHANGED |
| [[UI-008]] | ui_component | 6 | UNCHANGED |
| [[UI-009]] | ui_component | 3 | UNCHANGED |
| [[UI-010]] | ui_component | 4 | UNCHANGED |
| [[UI-011]] | ui_component | 4 | UNCHANGED |
| [[UI-012]] | ui_component | 3 | UNCHANGED |
| [[UI-013]] | ui_component | 3 | UNCHANGED |
| [[UI-014]] | ui_component | 5 | UNCHANGED |
| [[UI-015]] | ui_component | 4 | UNCHANGED |
| [[UI-016]] | ui_component | 5 | UNCHANGED |
| [[UI-017]] | ui_component | 8 | UNCHANGED |
| [[UI-018]] | ui_component | 10 | UNCHANGED |
| [[UI-019]] | ui_component | 4 | UNCHANGED |
| [[UI-020]] | ui_component | 5 | UNCHANGED |
| [[UI-021]] | ui_component | 4 | UNCHANGED |
| [[UI-022]] | ui_component | 5 | UNCHANGED |
| [[UI-023]] | ui_component | 3 | UNCHANGED |
| [[UI-024]] | ui_component | 6 | UNCHANGED |
| [[UI-025]] | ui_component | 3 | UNCHANGED |
| [[UI-026]] | ui_component | 6 | UNCHANGED |
| [[UI-027]] | ui_component | 4 | UNCHANGED |
| [[UI-028]] | ui_component | 4 | UNCHANGED |
| [[UI-029]] | ui_component | 5 | UNCHANGED |
| [[UI-030]] | ui_component | 6 | UNCHANGED |
| [[UI-031]] | ui_component | 3 | UNCHANGED |
| [[UI-032]] | ui_component | 3 | UNCHANGED |
| [[UI-033]] | ui_component | 3 | UNCHANGED |
| [[UI-034]] | ui_component | 4 | UNCHANGED |
| [[UI-035]] | ui_component | 7 | UNCHANGED |
| [[UI-036]] | ui_component | 3 | UNCHANGED |
| [[UI-037]] | ui_component | 6 | UNCHANGED |
| [[UI-038]] | ui_component | 3 | UNCHANGED |
| [[UI-039]] | ui_component | 3 | UNCHANGED |
| [[UI-040]] | ui_component | 6 | UNCHANGED |
| [[UI-041]] | ui_component | 3 | UNCHANGED |
| [[UI-042]] | ui_component | 4 | UNCHANGED |
| [[UI-043]] | ui_component | 4 | UNCHANGED |
| [[UI-044]] | ui_component | 4 | UNCHANGED |
| [[UI-045]] | ui_component | 5 | UNCHANGED |
| [[UI-046]] | ui_component | 8 | UNCHANGED |
| [[UI-047]] | ui_component | 6 | UNCHANGED |
| [[UI-048]] | ui_component | 7 | UNCHANGED |
| [[UI-049]] | ui_component | 7 | UNCHANGED |
| [[UI-050]] | ui_component | 6 | UNCHANGED |
| [[UI-051]] | ui_component | 4 | UNCHANGED |
| [[UI-052]] | ui_component | 7 | UNCHANGED |
| [[UI-053]] | ui_component | 8 | UNCHANGED |
| [[UI-054]] | ui_component | 5 | UNCHANGED |
| [[UI-055]] | ui_component | 11 | UNCHANGED |
| [[UI-056]] | ui_component | 6 | UNCHANGED |
| [[UI-057]] | ui_component | 6 | UNCHANGED |
| [[UI-058]] | ui_component | 4 | UNCHANGED |
| [[UI-059]] | ui_component | 5 | UNCHANGED |
| [[UI-060]] | ui_component | 5 | UNCHANGED |
| [[UI-061]] | ui_component | 5 | UNCHANGED |
| [[UI-062]] | ui_component | 4 | UNCHANGED |
| [[UI-063]] | ui_component | 4 | UNCHANGED |
| [[UI-064]] | ui_component | 5 | UNCHANGED |
| [[UI-065]] | ui_component | 4 | UNCHANGED |
| [[UI-066]] | ui_component | 5 | UNCHANGED |
| [[UI-067]] | ui_component | 4 | UNCHANGED |
| [[UI-068]] | ui_component | 6 | UNCHANGED |
| [[UI-069]] | ui_component | 4 | UNCHANGED |
| [[UI-070]] | ui_component | 4 | UNCHANGED |
| [[UI-071]] | ui_component | 5 | UNCHANGED |
| [[UI-072]] | ui_component | 5 | UNCHANGED |
| [[UI-073]] | ui_component | 4 | UNCHANGED |
| [[UI-074]] | ui_component | 5 | UNCHANGED |
| [[UI-075]] | ui_component | 5 | UNCHANGED |
| [[UI-076]] | ui_component | 4 | UNCHANGED |
| [[UI-077]] | ui_component | 4 | UNCHANGED |
| [[UI-078]] | ui_component | 4 | UNCHANGED |
| [[UI-079]] | ui_component | 4 | UNCHANGED |
| [[UI-080]] | ui_component | 5 | UNCHANGED |
| [[UI-081]] | ui_component | 4 | UNCHANGED |
| [[UI-082]] | ui_component | 4 | UNCHANGED |
| [[UI-083]] | ui_component | 5 | UNCHANGED |
| [[UI-084]] | ui_component | 5 | UNCHANGED |
| [[UI-085]] | ui_component | 4 | UNCHANGED |
| [[UI-086]] | ui_component | 4 | UNCHANGED |
| [[UI-087]] | ui_component | 5 | UNCHANGED |
| [[UI-088]] | ui_component | 4 | UNCHANGED |
| [[UI-089]] | ui_component | 5 | UNCHANGED |
| [[UI-090]] | ui_component | 6 | UNCHANGED |
| [[UI-091]] | ui_component | 6 | UNCHANGED |
| [[UI-092]] | ui_component | 4 | UNCHANGED |
| [[UI-093]] | ui_component | 6 | UNCHANGED |
| [[UI-094]] | ui_component | 5 | UNCHANGED |
| [[UI-095]] | ui_component | 5 | UNCHANGED |
| [[UI-096]] | ui_component | 6 | UNCHANGED |
| [[UI-097]] | ui_component | 5 | UNCHANGED |
| [[UI-098]] | ui_component | 2 | UNCHANGED |
| [[UI-099]] | ui_component | 2 | UNCHANGED |
| [[UI-100]] | ui_component | 2 | UNCHANGED |
| [[UI-101]] | ui_component | 1 | UNCHANGED |
| [[UI-102]] | ui_component | 1 | UNCHANGED |
| [[UI-103]] | ui_component | 1 | UNCHANGED |
| [[UI-104]] | ui_component | 1 | UNCHANGED |
| [[UI-105]] | ui_component | 3 | UNCHANGED |
| [[UI-106]] | ui_component | 1 | UNCHANGED |
| [[UI-107]] | ui_component | 1 | UNCHANGED |
| [[UI-108]] | ui_component | 1 | UNCHANGED |
| [[UI-109]] | ui_component | 1 | UNCHANGED |
| [[UI-110]] | ui_component | 2 | UNCHANGED |
| [[UI-111]] | ui_component | 3 | UNCHANGED |
| [[UI-112]] | ui_component | 3 | UNCHANGED |
| [[UI-113]] | ui_component | 1 | UNCHANGED |
| [[UI-114]] | ui_component | 1 | UNCHANGED |
| [[UI-115]] | ui_component | 1 | UNCHANGED |
| [[UI-116]] | ui_component | 1 | UNCHANGED |
| [[UI-117]] | ui_component | 1 | UNCHANGED |
| [[UI-118]] | ui_component | 1 | UNCHANGED |
| [[UI-119]] | ui_component | 1 | UNCHANGED |
| [[UI-120]] | ui_component | 1 | UNCHANGED |
| [[UI-121]] | ui_component | 1 | UNCHANGED |
| [[UI-122]] | ui_component | 1 | UNCHANGED |
| [[UI-123]] | ui_component | 1 | UNCHANGED |
| [[UI-124]] | ui_component | 1 | UNCHANGED |
| [[UI-125]] | ui_component | 1 | UNCHANGED |
| [[UI-126]] | ui_component | 1 | UNCHANGED |
| [[UI-127]] | ui_component | 1 | UNCHANGED |
| [[UI-128]] | ui_component | 1 | UNCHANGED |
| [[UI-129]] | ui_component | 4 | UNCHANGED |
| [[UI-130]] | ui_component | 1 | UNCHANGED |
| [[UI-131]] | ui_component | 2 | UNCHANGED |
| [[UI-132]] | ui_component | 2 | UNCHANGED |
| [[UI-133]] | ui_component | 2 | UNCHANGED |
| [[UI-134]] | ui_component | 2 | UNCHANGED |
| [[UI-135]] | ui_component | 1 | UNCHANGED |
| [[UI-136]] | ui_component | 1 | UNCHANGED |
| [[UI-137]] | ui_component | 1 | UNCHANGED |
| [[UI-138]] | ui_component | 2 | UNCHANGED |
| [[UI-139]] | ui_component | 2 | UNCHANGED |
| [[UI-140]] | ui_component | 2 | UNCHANGED |
| [[UI-141]] | ui_component | 1 | UNCHANGED |
| [[UI-142]] | ui_component | 1 | UNCHANGED |
| [[UI-143]] | ui_component | 1 | UNCHANGED |
| [[UI-144]] | ui_component | 1 | UNCHANGED |
