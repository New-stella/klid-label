# Version Master — DOMAIN-000

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-000 |
| Last sync | 2026-09-02T10:53:44.755Z |
| Mode | SYNC — NEW 3 / CHANGED 110 / UNCHANGED 354 / RETIRED 1 |
| 출력 루트 | docs/screen-design/klid-authoring-screens |
| 생성 | download-kit.mjs (결정적 다운로드, LLM 0) |
| 링크 포맷 | wikilink-v1 — frontmatter `links:` 가 `[[ID]]` wikilink |
| 스코프 | DOMAIN-000 — .kit-scope.json (스킬 LLM 판정) |
| 전역 수집 | nfr, implementation_guideline, permission_role |
| ⚠️ 미판정 | 15건 — 스킬 Phase 2 판정 필요(이번 키트 미포함) |

## ⚠️ 스코프 밖 ITEM (유실 점검)

도메인 필터는 `domain_id` 컬럼 일치만 본다. 아래는 이번 스코프에 들어오지 않은 핵심 타입이다.
**🚨 = 프로젝트엔 있는데 이번 키트엔 0건** — 구현이 그 설계를 못 본다.

```
  🚨 domain_feature: 이번 키트 0건 / 프로젝트 전역 48건 — 전량 누락
  ℹ️  api_endpoint: 이번 키트 202건 / 스코프 밖 18건
  🚨 erd: 이번 키트 0건 / 프로젝트 전역 23건 — 전량 누락
  🚨 diagram_sequence: 이번 키트 0건 / 프로젝트 전역 26건 (그중 domain_id 없음 14건) — 전량 누락
  ℹ️  screen_spec: 이번 키트 36건 / 스코프 밖 2건
  ℹ️  use_case: 이번 키트 31건 / 스코프 밖 4건
  🚨 domain_event: 이번 키트 0건 / 프로젝트 전역 12건 — 전량 누락
  ℹ️  acceptance: 이번 키트 4건 / 스코프 밖 76건
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

- NEW [[SD-038]]
- NEW [[UI-145]]
- NEW [[UC-042]]
- CHANGED [[AC-1086]] (prev v1)
- CHANGED [[AC-1087]] (prev v1)
- CHANGED [[API-001]] (prev v6)
- CHANGED [[API-004]] (prev v8)
- CHANGED [[API-007]] (prev v14)
- CHANGED [[API-059]] (prev v6)
- CHANGED [[API-060]] (prev v16)
- CHANGED [[API-061]] (prev v10)
- CHANGED [[API-062]] (prev v7)
- CHANGED [[API-063]] (prev v9)
- CHANGED [[API-070]] (prev v8)
- CHANGED [[API-115]] (prev v3)
- CHANGED [[API-139]] (prev v3)
- CHANGED [[API-140]] (prev v5)
- CHANGED [[API-142]] (prev v4)
- CHANGED [[API-152]] (prev v8)
- CHANGED [[API-153]] (prev v5)
- CHANGED [[API-157]] (prev v3)
- CHANGED [[API-158]] (prev v5)
- CHANGED [[API-169]] (prev v6)
- CHANGED [[API-171]] (prev v3)
- CHANGED [[API-188]] (prev v2)
- CHANGED [[API-189]] (prev v2)
- CHANGED [[API-190]] (prev v6)
- CHANGED [[API-194]] (prev v10)
- CHANGED [[API-201]] (prev v8)
- CHANGED [[API-205]] (prev v8)
- CHANGED [[API-206]] (prev v13)
- CHANGED [[API-209]] (prev v6)
- CHANGED [[API-210]] (prev v10)
- CHANGED [[API-212]] (prev v5)
- CHANGED [[API-213]] (prev v6)
- CHANGED [[API-214]] (prev v4)
- CHANGED [[API-215]] (prev v4)
- CHANGED [[API-221]] (prev v16)
- CHANGED [[API-222]] (prev v11)
- CHANGED [[API-223]] (prev v5)
- CHANGED [[SHELL-002]] (prev v5)
- CHANGED [[NAV-002]] (prev v8)
- CHANGED [[ROLE-001]] (prev v13)
- CHANGED [[ROLE-003]] (prev v10)
- CHANGED [[ROLE-004]] (prev v4)
- CHANGED [[SD-009]] (prev v12)
- CHANGED [[SD-021]] (prev v4)
- CHANGED [[SD-024]] (prev v6)
- CHANGED [[SD-025]] (prev v4)
- CHANGED [[SD-026]] (prev v7)
- CHANGED [[SD-027]] (prev v9)
- CHANGED [[SD-028]] (prev v4)
- CHANGED [[SD-029]] (prev v5)
- CHANGED [[SD-033]] (prev v11)
- CHANGED [[SD-036]] (prev v4)
- CHANGED [[SCREEN-002]] (prev v26)
- CHANGED [[SCREEN-008]] (prev v47)
- CHANGED [[SCREEN-009]] (prev v75)
- CHANGED [[SCREEN-012]] (prev v47)
- CHANGED [[SCREEN-022]] (prev v46)
- CHANGED [[SCREEN-023]] (prev v41)
- CHANGED [[SCREEN-024]] (prev v32)
- CHANGED [[SCREEN-025]] (prev v46)
- CHANGED [[SCREEN-027]] (prev v48)
- CHANGED [[SCREEN-028]] (prev v26)
- CHANGED [[SCREEN-029]] (prev v41)
- CHANGED [[SCREEN-033]] (prev v23)
- CHANGED [[SCREEN-039]] (prev v41)
- CHANGED [[SCREEN-042]] (prev v8)
- CHANGED [[UI-035]] (prev v6)
- CHANGED [[UI-037]] (prev v4)
- CHANGED [[UI-046]] (prev v7)
- CHANGED [[UI-071]] (prev v4)
- CHANGED [[UI-072]] (prev v4)
- CHANGED [[UI-096]] (prev v5)
- CHANGED [[UI-105]] (prev v1)
- CHANGED [[UI-129]] (prev v1)
- CHANGED [[UI-131]] (prev v1)
- CHANGED [[UI-132]] (prev v1)
- CHANGED [[UI-134]] (prev v1)
- CHANGED [[UI-138]] (prev v1)
- CHANGED [[UI-139]] (prev v1)
- CHANGED [[UI-140]] (prev v1)
- CHANGED [[UC-001]] (prev v14)
- CHANGED [[UC-002]] (prev v16)
- CHANGED [[UC-003]] (prev v15)
- CHANGED [[UC-004]] (prev v16)
- CHANGED [[UC-005]] (prev v11)
- CHANGED [[UC-006]] (prev v10)
- CHANGED [[UC-007]] (prev v14)
- CHANGED [[UC-008]] (prev v15)
- CHANGED [[UC-009]] (prev v21)
- CHANGED [[UC-010]] (prev v14)
- CHANGED [[UC-011]] (prev v13)
- CHANGED [[UC-013]] (prev v11)
- CHANGED [[UC-016]] (prev v25)
- CHANGED [[UC-018]] (prev v19)
- CHANGED [[UC-019]] (prev v24)
- CHANGED [[UC-021]] (prev v21)
- CHANGED [[UC-022]] (prev v23)
- CHANGED [[UC-023]] (prev v27)
- CHANGED [[UC-024]] (prev v21)
- CHANGED [[UC-027]] (prev v17)
- CHANGED [[UC-028]] (prev v7)
- CHANGED [[UC-029]] (prev v12)
- CHANGED [[UC-030]] (prev v14)
- CHANGED [[UC-031]] (prev v14)
- CHANGED [[UC-032]] (prev v13)
- CHANGED [[UC-033]] (prev v4)
- CHANGED [[UC-034]] (prev v6)
- CHANGED [[UC-035]] (prev v17)
- CHANGED [[UC-036]] (prev v6)
- CHANGED [[UC-037]] (prev v9)
- RETIRED [[SCREEN-034]] → _retired/

## ITEM 표

| ID | type | version | status |
|---|---|---|---|
| [[AC-1032]] | acceptance | 4 | UNCHANGED |
| [[AC-1033]] | acceptance | 5 | UNCHANGED |
| [[AC-1086]] | acceptance | 2 | CHANGED |
| [[AC-1087]] | acceptance | 2 | CHANGED |
| [[API-001]] | api_endpoint | 8 | CHANGED |
| [[API-002]] | api_endpoint | 2 | UNCHANGED |
| [[API-003]] | api_endpoint | 5 | UNCHANGED |
| [[API-004]] | api_endpoint | 10 | CHANGED |
| [[API-005]] | api_endpoint | 5 | UNCHANGED |
| [[API-006]] | api_endpoint | 10 | UNCHANGED |
| [[API-007]] | api_endpoint | 16 | CHANGED |
| [[API-008]] | api_endpoint | 12 | UNCHANGED |
| [[API-009]] | api_endpoint | 9 | UNCHANGED |
| [[API-010]] | api_endpoint | 4 | UNCHANGED |
| [[API-011]] | api_endpoint | 4 | UNCHANGED |
| [[API-012]] | api_endpoint | 6 | UNCHANGED |
| [[API-013]] | api_endpoint | 6 | UNCHANGED |
| [[API-014]] | api_endpoint | 12 | UNCHANGED |
| [[API-015]] | api_endpoint | 8 | UNCHANGED |
| [[API-016]] | api_endpoint | 3 | UNCHANGED |
| [[API-017]] | api_endpoint | 5 | UNCHANGED |
| [[API-018]] | api_endpoint | 5 | UNCHANGED |
| [[API-019]] | api_endpoint | 8 | UNCHANGED |
| [[API-020]] | api_endpoint | 14 | UNCHANGED |
| [[API-021]] | api_endpoint | 9 | UNCHANGED |
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
| [[API-042]] | api_endpoint | 8 | UNCHANGED |
| [[API-043]] | api_endpoint | 25 | UNCHANGED |
| [[API-044]] | api_endpoint | 9 | UNCHANGED |
| [[API-045]] | api_endpoint | 2 | UNCHANGED |
| [[API-046]] | api_endpoint | 7 | UNCHANGED |
| [[API-047]] | api_endpoint | 12 | UNCHANGED |
| [[API-055]] | api_endpoint | 6 | UNCHANGED |
| [[API-056]] | api_endpoint | 7 | UNCHANGED |
| [[API-057]] | api_endpoint | 6 | UNCHANGED |
| [[API-058]] | api_endpoint | 4 | UNCHANGED |
| [[API-059]] | api_endpoint | 9 | CHANGED |
| [[API-060]] | api_endpoint | 18 | CHANGED |
| [[API-061]] | api_endpoint | 11 | CHANGED |
| [[API-062]] | api_endpoint | 10 | CHANGED |
| [[API-063]] | api_endpoint | 10 | CHANGED |
| [[API-065]] | api_endpoint | 21 | UNCHANGED |
| [[API-066]] | api_endpoint | 7 | UNCHANGED |
| [[API-067]] | api_endpoint | 8 | UNCHANGED |
| [[API-068]] | api_endpoint | 4 | UNCHANGED |
| [[API-069]] | api_endpoint | 9 | UNCHANGED |
| [[API-070]] | api_endpoint | 9 | CHANGED |
| [[API-071]] | api_endpoint | 7 | UNCHANGED |
| [[API-072]] | api_endpoint | 10 | UNCHANGED |
| [[API-073]] | api_endpoint | 9 | UNCHANGED |
| [[API-074]] | api_endpoint | 7 | UNCHANGED |
| [[API-075]] | api_endpoint | 8 | UNCHANGED |
| [[API-076]] | api_endpoint | 9 | UNCHANGED |
| [[API-081]] | api_endpoint | 7 | UNCHANGED |
| [[API-082]] | api_endpoint | 9 | UNCHANGED |
| [[API-083]] | api_endpoint | 6 | UNCHANGED |
| [[API-084]] | api_endpoint | 8 | UNCHANGED |
| [[API-090]] | api_endpoint | 4 | UNCHANGED |
| [[API-091]] | api_endpoint | 12 | UNCHANGED |
| [[API-092]] | api_endpoint | 10 | UNCHANGED |
| [[API-093]] | api_endpoint | 14 | UNCHANGED |
| [[API-094]] | api_endpoint | 9 | UNCHANGED |
| [[API-095]] | api_endpoint | 6 | UNCHANGED |
| [[API-096]] | api_endpoint | 7 | UNCHANGED |
| [[API-097]] | api_endpoint | 7 | UNCHANGED |
| [[API-098]] | api_endpoint | 7 | UNCHANGED |
| [[API-099]] | api_endpoint | 6 | UNCHANGED |
| [[API-100]] | api_endpoint | 8 | UNCHANGED |
| [[API-101]] | api_endpoint | 8 | UNCHANGED |
| [[API-102]] | api_endpoint | 14 | UNCHANGED |
| [[API-103]] | api_endpoint | 11 | UNCHANGED |
| [[API-104]] | api_endpoint | 14 | UNCHANGED |
| [[API-105]] | api_endpoint | 7 | UNCHANGED |
| [[API-106]] | api_endpoint | 9 | UNCHANGED |
| [[API-107]] | api_endpoint | 7 | UNCHANGED |
| [[API-108]] | api_endpoint | 6 | UNCHANGED |
| [[API-109]] | api_endpoint | 5 | UNCHANGED |
| [[API-110]] | api_endpoint | 4 | UNCHANGED |
| [[API-111]] | api_endpoint | 3 | UNCHANGED |
| [[API-112]] | api_endpoint | 5 | UNCHANGED |
| [[API-113]] | api_endpoint | 4 | UNCHANGED |
| [[API-114]] | api_endpoint | 2 | UNCHANGED |
| [[API-115]] | api_endpoint | 4 | CHANGED |
| [[API-116]] | api_endpoint | 8 | UNCHANGED |
| [[API-117]] | api_endpoint | 5 | UNCHANGED |
| [[API-118]] | api_endpoint | 2 | UNCHANGED |
| [[API-119]] | api_endpoint | 4 | UNCHANGED |
| [[API-120]] | api_endpoint | 4 | UNCHANGED |
| [[API-121]] | api_endpoint | 4 | UNCHANGED |
| [[API-122]] | api_endpoint | 2 | UNCHANGED |
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
| [[API-136]] | api_endpoint | 4 | UNCHANGED |
| [[API-137]] | api_endpoint | 6 | UNCHANGED |
| [[API-138]] | api_endpoint | 4 | UNCHANGED |
| [[API-139]] | api_endpoint | 4 | CHANGED |
| [[API-140]] | api_endpoint | 6 | CHANGED |
| [[API-141]] | api_endpoint | 2 | UNCHANGED |
| [[API-142]] | api_endpoint | 8 | CHANGED |
| [[API-143]] | api_endpoint | 3 | UNCHANGED |
| [[API-144]] | api_endpoint | 3 | UNCHANGED |
| [[API-145]] | api_endpoint | 3 | UNCHANGED |
| [[API-146]] | api_endpoint | 5 | UNCHANGED |
| [[API-147]] | api_endpoint | 2 | UNCHANGED |
| [[API-148]] | api_endpoint | 3 | UNCHANGED |
| [[API-149]] | api_endpoint | 3 | UNCHANGED |
| [[API-150]] | api_endpoint | 3 | UNCHANGED |
| [[API-151]] | api_endpoint | 2 | UNCHANGED |
| [[API-152]] | api_endpoint | 11 | CHANGED |
| [[API-153]] | api_endpoint | 6 | CHANGED |
| [[API-154]] | api_endpoint | 4 | UNCHANGED |
| [[API-155]] | api_endpoint | 2 | UNCHANGED |
| [[API-156]] | api_endpoint | 3 | UNCHANGED |
| [[API-157]] | api_endpoint | 4 | CHANGED |
| [[API-158]] | api_endpoint | 8 | CHANGED |
| [[API-159]] | api_endpoint | 4 | UNCHANGED |
| [[API-160]] | api_endpoint | 4 | UNCHANGED |
| [[API-161]] | api_endpoint | 3 | UNCHANGED |
| [[API-162]] | api_endpoint | 6 | UNCHANGED |
| [[API-163]] | api_endpoint | 3 | UNCHANGED |
| [[API-164]] | api_endpoint | 3 | UNCHANGED |
| [[API-165]] | api_endpoint | 7 | UNCHANGED |
| [[API-166]] | api_endpoint | 3 | UNCHANGED |
| [[API-167]] | api_endpoint | 10 | UNCHANGED |
| [[API-168]] | api_endpoint | 2 | UNCHANGED |
| [[API-169]] | api_endpoint | 7 | CHANGED |
| [[API-170]] | api_endpoint | 3 | UNCHANGED |
| [[API-171]] | api_endpoint | 4 | CHANGED |
| [[API-172]] | api_endpoint | 4 | UNCHANGED |
| [[API-173]] | api_endpoint | 6 | UNCHANGED |
| [[API-174]] | api_endpoint | 6 | UNCHANGED |
| [[API-175]] | api_endpoint | 2 | UNCHANGED |
| [[API-176]] | api_endpoint | 3 | UNCHANGED |
| [[API-177]] | api_endpoint | 4 | UNCHANGED |
| [[API-178]] | api_endpoint | 8 | UNCHANGED |
| [[API-179]] | api_endpoint | 9 | UNCHANGED |
| [[API-181]] | api_endpoint | 7 | UNCHANGED |
| [[API-182]] | api_endpoint | 4 | UNCHANGED |
| [[API-183]] | api_endpoint | 1 | UNCHANGED |
| [[API-184]] | api_endpoint | 2 | UNCHANGED |
| [[API-185]] | api_endpoint | 8 | UNCHANGED |
| [[API-186]] | api_endpoint | 6 | UNCHANGED |
| [[API-187]] | api_endpoint | 3 | UNCHANGED |
| [[API-188]] | api_endpoint | 3 | CHANGED |
| [[API-189]] | api_endpoint | 3 | CHANGED |
| [[API-190]] | api_endpoint | 7 | CHANGED |
| [[API-191]] | api_endpoint | 2 | UNCHANGED |
| [[API-192]] | api_endpoint | 4 | UNCHANGED |
| [[API-193]] | api_endpoint | 5 | UNCHANGED |
| [[API-194]] | api_endpoint | 12 | CHANGED |
| [[API-195]] | api_endpoint | 7 | UNCHANGED |
| [[API-196]] | api_endpoint | 9 | UNCHANGED |
| [[API-197]] | api_endpoint | 5 | UNCHANGED |
| [[API-198]] | api_endpoint | 7 | UNCHANGED |
| [[API-199]] | api_endpoint | 2 | UNCHANGED |
| [[API-200]] | api_endpoint | 4 | UNCHANGED |
| [[API-201]] | api_endpoint | 9 | CHANGED |
| [[API-202]] | api_endpoint | 2 | UNCHANGED |
| [[API-203]] | api_endpoint | 6 | UNCHANGED |
| [[API-204]] | api_endpoint | 1 | UNCHANGED |
| [[API-205]] | api_endpoint | 9 | CHANGED |
| [[API-206]] | api_endpoint | 14 | CHANGED |
| [[API-207]] | api_endpoint | 7 | UNCHANGED |
| [[API-208]] | api_endpoint | 5 | UNCHANGED |
| [[API-209]] | api_endpoint | 7 | CHANGED |
| [[API-210]] | api_endpoint | 11 | CHANGED |
| [[API-211]] | api_endpoint | 8 | UNCHANGED |
| [[API-212]] | api_endpoint | 6 | CHANGED |
| [[API-213]] | api_endpoint | 7 | CHANGED |
| [[API-214]] | api_endpoint | 5 | CHANGED |
| [[API-215]] | api_endpoint | 5 | CHANGED |
| [[API-216]] | api_endpoint | 4 | UNCHANGED |
| [[API-217]] | api_endpoint | 5 | UNCHANGED |
| [[API-218]] | api_endpoint | 4 | UNCHANGED |
| [[API-219]] | api_endpoint | 3 | UNCHANGED |
| [[API-220]] | api_endpoint | 3 | UNCHANGED |
| [[API-221]] | api_endpoint | 17 | CHANGED |
| [[API-222]] | api_endpoint | 12 | CHANGED |
| [[API-223]] | api_endpoint | 7 | CHANGED |
| [[CONST-001]] | constant | 4 | UNCHANGED |
| [[CONST-002]] | constant | 3 | UNCHANGED |
| [[DS-001]] | design_system | 9 | UNCHANGED |
| [[NAV-001]] | navigation_tree | 26 | UNCHANGED |
| [[NAV-002]] | navigation_tree | 17 | CHANGED |
| [[ROLE-001]] | permission_role | 14 | CHANGED |
| [[ROLE-002]] | permission_role | 9 | UNCHANGED |
| [[ROLE-003]] | permission_role | 14 | CHANGED |
| [[ROLE-004]] | permission_role | 5 | CHANGED |
| [[SCREEN-001]] | screen_spec | 16 | UNCHANGED |
| [[SCREEN-002]] | screen_spec | 27 | CHANGED |
| [[SCREEN-003]] | screen_spec | 14 | UNCHANGED |
| [[SCREEN-004]] | screen_spec | 16 | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 102 | UNCHANGED |
| [[SCREEN-006]] | screen_spec | 49 | UNCHANGED |
| [[SCREEN-008]] | screen_spec | 48 | CHANGED |
| [[SCREEN-009]] | screen_spec | 76 | CHANGED |
| [[SCREEN-010]] | screen_spec | 37 | UNCHANGED |
| [[SCREEN-011]] | screen_spec | 21 | UNCHANGED |
| [[SCREEN-012]] | screen_spec | 49 | CHANGED |
| [[SCREEN-018]] | screen_spec | 29 | UNCHANGED |
| [[SCREEN-019]] | screen_spec | 43 | UNCHANGED |
| [[SCREEN-020]] | screen_spec | 32 | UNCHANGED |
| [[SCREEN-021]] | screen_spec | 29 | UNCHANGED |
| [[SCREEN-022]] | screen_spec | 51 | CHANGED |
| [[SCREEN-023]] | screen_spec | 47 | CHANGED |
| [[SCREEN-024]] | screen_spec | 33 | CHANGED |
| [[SCREEN-025]] | screen_spec | 49 | CHANGED |
| [[SCREEN-026]] | screen_spec | 35 | UNCHANGED |
| [[SCREEN-027]] | screen_spec | 51 | CHANGED |
| [[SCREEN-028]] | screen_spec | 34 | CHANGED |
| [[SCREEN-029]] | screen_spec | 48 | CHANGED |
| [[SCREEN-030]] | screen_spec | 26 | UNCHANGED |
| [[SCREEN-031]] | screen_spec | 33 | UNCHANGED |
| [[SCREEN-032]] | screen_spec | 26 | UNCHANGED |
| [[SCREEN-033]] | screen_spec | 34 | CHANGED |
| [[SCREEN-035]] | screen_spec | 19 | UNCHANGED |
| [[SCREEN-036]] | screen_spec | 8 | UNCHANGED |
| [[SCREEN-037]] | screen_spec | 8 | UNCHANGED |
| [[SCREEN-038]] | screen_spec | 14 | UNCHANGED |
| [[SCREEN-039]] | screen_spec | 42 | CHANGED |
| [[SCREEN-040]] | screen_spec | 9 | UNCHANGED |
| [[SCREEN-041]] | screen_spec | 10 | UNCHANGED |
| [[SCREEN-042]] | screen_spec | 10 | CHANGED |
| [[SCREEN-043]] | screen_spec | 7 | UNCHANGED |
| [[SD-001]] | screen_design | 5 | UNCHANGED |
| [[SD-002]] | screen_design | 17 | UNCHANGED |
| [[SD-003]] | screen_design | 8 | UNCHANGED |
| [[SD-004]] | screen_design | 19 | UNCHANGED |
| [[SD-005]] | screen_design | 7 | UNCHANGED |
| [[SD-006]] | screen_design | 5 | UNCHANGED |
| [[SD-007]] | screen_design | 6 | UNCHANGED |
| [[SD-008]] | screen_design | 4 | UNCHANGED |
| [[SD-009]] | screen_design | 13 | CHANGED |
| [[SD-010]] | screen_design | 10 | UNCHANGED |
| [[SD-011]] | screen_design | 6 | UNCHANGED |
| [[SD-012]] | screen_design | 11 | UNCHANGED |
| [[SD-013]] | screen_design | 7 | UNCHANGED |
| [[SD-014]] | screen_design | 5 | UNCHANGED |
| [[SD-015]] | screen_design | 9 | UNCHANGED |
| [[SD-016]] | screen_design | 6 | UNCHANGED |
| [[SD-017]] | screen_design | 3 | UNCHANGED |
| [[SD-018]] | screen_design | 8 | UNCHANGED |
| [[SD-019]] | screen_design | 3 | UNCHANGED |
| [[SD-020]] | screen_design | 5 | UNCHANGED |
| [[SD-021]] | screen_design | 6 | CHANGED |
| [[SD-022]] | screen_design | 4 | UNCHANGED |
| [[SD-023]] | screen_design | 5 | UNCHANGED |
| [[SD-024]] | screen_design | 12 | CHANGED |
| [[SD-025]] | screen_design | 6 | CHANGED |
| [[SD-026]] | screen_design | 11 | CHANGED |
| [[SD-027]] | screen_design | 11 | CHANGED |
| [[SD-028]] | screen_design | 9 | CHANGED |
| [[SD-029]] | screen_design | 7 | CHANGED |
| [[SD-030]] | screen_design | 5 | UNCHANGED |
| [[SD-031]] | screen_design | 3 | UNCHANGED |
| [[SD-032]] | screen_design | 2 | UNCHANGED |
| [[SD-033]] | screen_design | 13 | CHANGED |
| [[SD-034]] | screen_design | 6 | UNCHANGED |
| [[SD-035]] | screen_design | 4 | UNCHANGED |
| [[SD-036]] | screen_design | 5 | CHANGED |
| [[SD-037]] | screen_design | 5 | UNCHANGED |
| [[SD-038]] | screen_design | 2 | NEW |
| [[SHELL-001]] | app_shell | 11 | UNCHANGED |
| [[SHELL-002]] | app_shell | 8 | CHANGED |
| [[UC-001]] | use_case | 16 | CHANGED |
| [[UC-002]] | use_case | 18 | CHANGED |
| [[UC-003]] | use_case | 16 | CHANGED |
| [[UC-004]] | use_case | 17 | CHANGED |
| [[UC-005]] | use_case | 12 | CHANGED |
| [[UC-006]] | use_case | 11 | CHANGED |
| [[UC-007]] | use_case | 15 | CHANGED |
| [[UC-008]] | use_case | 16 | CHANGED |
| [[UC-009]] | use_case | 22 | CHANGED |
| [[UC-010]] | use_case | 16 | CHANGED |
| [[UC-011]] | use_case | 14 | CHANGED |
| [[UC-013]] | use_case | 12 | CHANGED |
| [[UC-016]] | use_case | 27 | CHANGED |
| [[UC-018]] | use_case | 21 | CHANGED |
| [[UC-019]] | use_case | 25 | CHANGED |
| [[UC-021]] | use_case | 22 | CHANGED |
| [[UC-022]] | use_case | 24 | CHANGED |
| [[UC-023]] | use_case | 29 | CHANGED |
| [[UC-024]] | use_case | 23 | CHANGED |
| [[UC-027]] | use_case | 31 | CHANGED |
| [[UC-028]] | use_case | 8 | CHANGED |
| [[UC-029]] | use_case | 13 | CHANGED |
| [[UC-030]] | use_case | 16 | CHANGED |
| [[UC-031]] | use_case | 15 | CHANGED |
| [[UC-032]] | use_case | 14 | CHANGED |
| [[UC-033]] | use_case | 6 | CHANGED |
| [[UC-034]] | use_case | 7 | CHANGED |
| [[UC-035]] | use_case | 18 | CHANGED |
| [[UC-036]] | use_case | 7 | CHANGED |
| [[UC-037]] | use_case | 11 | CHANGED |
| [[UC-042]] | use_case | 2 | NEW |
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
| [[UI-035]] | ui_component | 7 | CHANGED |
| [[UI-036]] | ui_component | 3 | UNCHANGED |
| [[UI-037]] | ui_component | 6 | CHANGED |
| [[UI-038]] | ui_component | 3 | UNCHANGED |
| [[UI-039]] | ui_component | 3 | UNCHANGED |
| [[UI-040]] | ui_component | 6 | UNCHANGED |
| [[UI-041]] | ui_component | 3 | UNCHANGED |
| [[UI-042]] | ui_component | 4 | UNCHANGED |
| [[UI-043]] | ui_component | 4 | UNCHANGED |
| [[UI-044]] | ui_component | 4 | UNCHANGED |
| [[UI-045]] | ui_component | 5 | UNCHANGED |
| [[UI-046]] | ui_component | 8 | CHANGED |
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
| [[UI-071]] | ui_component | 5 | CHANGED |
| [[UI-072]] | ui_component | 5 | CHANGED |
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
| [[UI-096]] | ui_component | 6 | CHANGED |
| [[UI-097]] | ui_component | 5 | UNCHANGED |
| [[UI-098]] | ui_component | 2 | UNCHANGED |
| [[UI-099]] | ui_component | 2 | UNCHANGED |
| [[UI-100]] | ui_component | 2 | UNCHANGED |
| [[UI-101]] | ui_component | 1 | UNCHANGED |
| [[UI-102]] | ui_component | 1 | UNCHANGED |
| [[UI-103]] | ui_component | 1 | UNCHANGED |
| [[UI-104]] | ui_component | 1 | UNCHANGED |
| [[UI-105]] | ui_component | 3 | CHANGED |
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
| [[UI-129]] | ui_component | 4 | CHANGED |
| [[UI-130]] | ui_component | 1 | UNCHANGED |
| [[UI-131]] | ui_component | 2 | CHANGED |
| [[UI-132]] | ui_component | 2 | CHANGED |
| [[UI-133]] | ui_component | 2 | UNCHANGED |
| [[UI-134]] | ui_component | 2 | CHANGED |
| [[UI-135]] | ui_component | 1 | UNCHANGED |
| [[UI-136]] | ui_component | 1 | UNCHANGED |
| [[UI-137]] | ui_component | 1 | UNCHANGED |
| [[UI-138]] | ui_component | 2 | CHANGED |
| [[UI-139]] | ui_component | 2 | CHANGED |
| [[UI-140]] | ui_component | 2 | CHANGED |
| [[UI-141]] | ui_component | 1 | UNCHANGED |
| [[UI-142]] | ui_component | 1 | UNCHANGED |
| [[UI-143]] | ui_component | 1 | UNCHANGED |
| [[UI-144]] | ui_component | 1 | UNCHANGED |
| [[UI-145]] | ui_component | 1 | NEW |
