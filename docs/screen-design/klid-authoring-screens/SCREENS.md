# KLID 저작도구 전체 화면 화면 키트 — SCREENS.md

> 이 파일이 화면 구현의 진입점이다. mc-logi-screen-implement 는 이 파일부터 읽는다.
> 키트는 read-only 산출물 — **직접 수정 금지**. 갱신은 mc-logi-screen-kit 재실행.

## 키트 현황

| 항목 | 값 |
|---|---|
| Domain | DOMAIN-000 KLID 저작도구 전체 화면 |
| last sync | 2026-09-05T01:33:13.609Z (session 34) |
| 화면 수 | 36개 |
| ui_component 카탈로그 | populated 145건 |
| 출력 루트 | docs/screen-design/klid-authoring-screens |
| 생성 | download-kit.mjs + arrange-screen-kit.mjs (결정적, LLM 0) |

## 화면 목록

| SCREEN-ID | 화면명 | 상태 | 와이어프레임 | consumes_apis | required_roles |
|---|---|---|---|---|---|
| [[SCREEN-001]] | 세션 인계 진입 화면 | UNCHANGED | ✅ |  |  |
| [[SCREEN-002]] | 관리자 등록 화면 | CHANGED | ✅ | [[API-007]] |  |
| [[SCREEN-003]] | 접근 거부 화면 | UNCHANGED | ✅ |  |  |
| [[SCREEN-004]] | 개발용 로그인 화면 | CHANGED | ✅ | [[API-153]] |  |
| [[SCREEN-005]] | 라벨링 캔버스 화면 | UNCHANGED | ✅ | [[API-018]], [[API-019]], [[API-020]], [[API-021]], [[API-024]], [[API-032]], [[API-066]], [[API-067]], [[API-102]], [[API-103]], [[API-104]], [[API-105]], [[API-123]], [[API-124]], [[API-125]], [[API-126]], [[API-127]], [[API-128]], [[API-129]], [[API-132]], [[API-134]], [[API-133]], [[API-135]], [[API-093]], [[API-182]], [[API-012]], [[API-178]], [[API-022]], [[API-023]], [[API-168]], [[API-170]], [[API-172]], [[API-173]], [[API-183]], [[API-184]], [[API-177]], [[API-195]], [[API-196]], [[API-197]], [[API-034]], [[API-035]], [[API-036]], [[API-193]], [[API-204]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-006]] | 마킹 화면 | UNCHANGED | ✅ | [[API-047]], [[API-043]], [[API-091]], [[API-114]], [[API-084]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-008]] | 영상 처리 현황 화면 | CHANGED | ✅ | [[API-042]], [[API-047]], [[API-068]], [[API-070]], [[API-071]], [[API-181]], [[API-212]], [[API-214]] | [[ROLE-001]] |
| [[SCREEN-009]] | 영상 상세 화면 | CHANGED | ✅ | [[API-021]], [[API-043]], [[API-044]], [[API-167]], [[API-198]], [[API-201]] | [[ROLE-001]] |
| [[SCREEN-010]] | 로드 버전 선택 | UNCHANGED | ✅ | [[API-197]], [[API-182]], [[API-195]], [[API-034]], [[API-035]], [[API-036]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-011]] | 대시보드 화면 | UNCHANGED | ✅ | [[API-042]], [[API-055]], [[API-072]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-012]] | 작업 목록 화면 | CHANGED | ✅ | [[API-001]], [[API-002]], [[API-070]], [[API-071]], [[API-072]], [[API-073]], [[API-136]], [[API-137]], [[API-116]], [[API-187]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-018]] | 검수 목록 화면 | UNCHANGED | ✅ | [[API-008]], [[API-138]] | [[ROLE-001]] |
| [[SCREEN-019]] | 검수 상세 화면 | UNCHANGED | ✅ | [[API-009]], [[API-010]], [[API-011]], [[API-013]], [[API-014]], [[API-015]], [[API-021]], [[API-132]], [[API-066]], [[API-102]], [[API-103]], [[API-104]], [[API-105]], [[API-168]], [[API-183]], [[API-128]], [[API-172]] | [[ROLE-001]] |
| [[SCREEN-020]] | 작업자 통계 화면 | UNCHANGED | ✅ | [[API-001]], [[API-056]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-021]] | 전체 구축 현황 화면 | CHANGED | ✅ | [[API-057]], [[API-058]] | [[ROLE-001]] |
| [[SCREEN-022]] | 증강 요청 화면 | CHANGED | ✅ | [[API-042]], [[API-059]], [[API-060]], [[API-092]], [[API-179]] | [[ROLE-001]] |
| [[SCREEN-023]] | 증강 결과 화면 | CHANGED | ✅ | [[API-061]], [[API-062]], [[API-063]], [[API-188]], [[API-189]], [[API-190]], [[API-175]] | [[ROLE-001]] |
| [[SCREEN-024]] | 사용자 관리 화면 | CHANGED | ✅ | [[API-001]], [[API-004]], [[API-194]] | [[ROLE-004]] |
| [[SCREEN-025]] | 시스템 설정 화면 | CHANGED | ✅ | [[API-068]], [[API-069]], [[API-090]] | [[ROLE-001]] |
| [[SCREEN-026]] | 프리셋 관리 화면 | UNCHANGED | ✅ | [[API-037]], [[API-038]], [[API-039]], [[API-040]], [[API-185]] | [[ROLE-001]] |
| [[SCREEN-027]] | 파일 업로드 | CHANGED | ✅ | [[API-043]], [[API-152]], [[API-156]], [[API-158]], [[API-160]], [[API-162]], [[API-164]], [[API-194]] | [[ROLE-004]] |
| [[SCREEN-028]] | 포털 내 작업 화면 | CHANGED | ✅ | [[API-225]], [[API-203]] | [[ROLE-003]] |
| [[SCREEN-029]] | 포털 라벨링 화면 | CHANGED | ✅ | [[API-024]], [[API-082]], [[API-110]], [[API-111]], [[API-140]], [[API-149]], [[API-154]], [[API-155]], [[API-234]], [[API-235]], [[API-236]], [[API-237]] | [[ROLE-003]] |
| [[SCREEN-030]] | 공지 목록 화면 | UNCHANGED | ✅ | [[API-095]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-031]] | 공지 상세 화면 | UNCHANGED | ✅ | [[API-096]], [[API-099]], [[API-100]], [[API-101]], [[API-107]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-032]] | 비식별 신고 관리 화면 | CHANGED | ✅ | [[API-094]], [[API-109]], [[API-202]], [[API-207]], [[API-215]] | [[ROLE-001]] |
| [[SCREEN-033]] | 포털 업로드 화면 | CHANGED | ✅ | [[API-140]], [[API-142]], [[API-151]], [[API-157]], [[API-159]], [[API-161]], [[API-163]], [[API-166]], [[API-169]], [[API-171]], [[API-231]] | [[ROLE-003]] |
| [[SCREEN-035]] | 라벨 관리 화면 | UNCHANGED | ✅ | [[API-024]], [[API-025]], [[API-026]], [[API-027]], [[API-028]], [[API-029]], [[API-030]], [[API-031]] | [[ROLE-001]] |
| [[SCREEN-036]] | 공지 작성 화면 | UNCHANGED | ✅ | [[API-097]] | [[ROLE-001]] |
| [[SCREEN-037]] | 공지 수정 화면 | UNCHANGED | ✅ | [[API-096]], [[API-098]], [[API-106]], [[API-108]] | [[ROLE-001]] |
| [[SCREEN-038]] | 이벤트유형 관리 화면 | UNCHANGED | ✅ | [[API-185]], [[API-186]], [[API-219]], [[API-220]] | [[ROLE-001]] |
| [[SCREEN-039]] | 산출물 가져오기 | CHANGED | ✅ | [[API-205]], [[API-206]], [[API-207]], [[API-208]], [[API-209]], [[API-210]], [[API-211]], [[API-215]], [[API-221]], [[API-222]], [[API-216]], [[API-217]], [[API-218]] | [[ROLE-004]] |
| [[SCREEN-040]] | 관리자 페이지 진입 화면 | UNCHANGED | ✅ | [[API-194]] | [[ROLE-004]] |
| [[SCREEN-041]] | 관리자 패스워드 교체 | UNCHANGED | ✅ | [[API-223]], [[API-194]] | [[ROLE-004]] |
| [[SCREEN-042]] | 연동 서버 주소 관리 화면 | CHANGED | ✅ | [[API-068]], [[API-069]], [[API-194]], [[API-226]], [[API-227]], [[API-228]], [[API-229]], [[API-230]] | [[ROLE-004]] |
| [[SCREEN-043]] | 위험 작업 화면 | UNCHANGED | ✅ |  | [[ROLE-004]] |

## 공유 자산 인덱스

| type | 파일 | 건수 |
|---|---|---|
| design_system | _shared/design-system.md | 1 |
| ui_component | _shared/ui-catalog.md | 145 |
| app_shell + nav | _shared/shell-nav.md | 4 |
| api_endpoint | _shared/api/ | 202 |
| constant | _shared/constant/ | 2 |
| permission_role | _shared/role/ | 4 |
| implementation_guideline | _shared/guideline/ | 0 |

## 빌드 순서 (mc-logi-screen-implement 참조)

> "공유 자산 먼저, 화면 단위 점진" 원칙.

### Phase 1 — 공유 자산
1. guideline/ → constant/ → design-system.md
2. ui-catalog.md (0건이면 Phase 0.5 선행)
3. shell-nav.md
4. api/ + role/

### Phase 4 — 화면별 점진

| 순서 | 화면 | screen_spec | 와이어프레임 | UC | AC |
|---|---|---|---|---|---|
| 1 | [[SCREEN-001]] — 세션 인계 진입 화면 | screens/SCREEN-001/SCREEN-001.md | wireframe.html | uc/ | ac/ |
| 2 | [[SCREEN-002]] — 관리자 등록 화면 | screens/SCREEN-002/SCREEN-002.md | wireframe.html | uc/ | ac/ |
| 3 | [[SCREEN-003]] — 접근 거부 화면 | screens/SCREEN-003/SCREEN-003.md | wireframe.html | uc/ | ac/ |
| 4 | [[SCREEN-004]] — 개발용 로그인 화면 | screens/SCREEN-004/SCREEN-004.md | wireframe.html | uc/ | ac/ |
| 5 | [[SCREEN-005]] — 라벨링 캔버스 화면 | screens/SCREEN-005/SCREEN-005.md | wireframe.html | uc/ | ac/ |
| 6 | [[SCREEN-006]] — 마킹 화면 | screens/SCREEN-006/SCREEN-006.md | wireframe.html | uc/ | ac/ |
| 7 | [[SCREEN-008]] — 영상 처리 현황 화면 | screens/SCREEN-008/SCREEN-008.md | wireframe.html | uc/ | ac/ |
| 8 | [[SCREEN-009]] — 영상 상세 화면 | screens/SCREEN-009/SCREEN-009.md | wireframe.html | uc/ | ac/ |
| 9 | [[SCREEN-010]] — 로드 버전 선택 | screens/SCREEN-010/SCREEN-010.md | wireframe.html | uc/ | ac/ |
| 10 | [[SCREEN-011]] — 대시보드 화면 | screens/SCREEN-011/SCREEN-011.md | wireframe.html | uc/ | ac/ |
| 11 | [[SCREEN-012]] — 작업 목록 화면 | screens/SCREEN-012/SCREEN-012.md | wireframe.html | uc/ | ac/ |
| 12 | [[SCREEN-018]] — 검수 목록 화면 | screens/SCREEN-018/SCREEN-018.md | wireframe.html | uc/ | ac/ |
| 13 | [[SCREEN-019]] — 검수 상세 화면 | screens/SCREEN-019/SCREEN-019.md | wireframe.html | uc/ | ac/ |
| 14 | [[SCREEN-020]] — 작업자 통계 화면 | screens/SCREEN-020/SCREEN-020.md | wireframe.html | uc/ | ac/ |
| 15 | [[SCREEN-021]] — 전체 구축 현황 화면 | screens/SCREEN-021/SCREEN-021.md | wireframe.html | uc/ | ac/ |
| 16 | [[SCREEN-022]] — 증강 요청 화면 | screens/SCREEN-022/SCREEN-022.md | wireframe.html | uc/ | ac/ |
| 17 | [[SCREEN-023]] — 증강 결과 화면 | screens/SCREEN-023/SCREEN-023.md | wireframe.html | uc/ | ac/ |
| 18 | [[SCREEN-024]] — 사용자 관리 화면 | screens/SCREEN-024/SCREEN-024.md | wireframe.html | uc/ | ac/ |
| 19 | [[SCREEN-025]] — 시스템 설정 화면 | screens/SCREEN-025/SCREEN-025.md | wireframe.html | uc/ | ac/ |
| 20 | [[SCREEN-026]] — 프리셋 관리 화면 | screens/SCREEN-026/SCREEN-026.md | wireframe.html | uc/ | ac/ |
| 21 | [[SCREEN-027]] — 파일 업로드 | screens/SCREEN-027/SCREEN-027.md | wireframe.html | uc/ | ac/ |
| 22 | [[SCREEN-028]] — 포털 내 작업 화면 | screens/SCREEN-028/SCREEN-028.md | wireframe.html | uc/ | ac/ |
| 23 | [[SCREEN-029]] — 포털 라벨링 화면 | screens/SCREEN-029/SCREEN-029.md | wireframe.html | uc/ | ac/ |
| 24 | [[SCREEN-030]] — 공지 목록 화면 | screens/SCREEN-030/SCREEN-030.md | wireframe.html | uc/ | ac/ |
| 25 | [[SCREEN-031]] — 공지 상세 화면 | screens/SCREEN-031/SCREEN-031.md | wireframe.html | uc/ | ac/ |
| 26 | [[SCREEN-032]] — 비식별 신고 관리 화면 | screens/SCREEN-032/SCREEN-032.md | wireframe.html | uc/ | ac/ |
| 27 | [[SCREEN-033]] — 포털 업로드 화면 | screens/SCREEN-033/SCREEN-033.md | wireframe.html | uc/ | ac/ |
| 28 | [[SCREEN-035]] — 라벨 관리 화면 | screens/SCREEN-035/SCREEN-035.md | wireframe.html | uc/ | ac/ |
| 29 | [[SCREEN-036]] — 공지 작성 화면 | screens/SCREEN-036/SCREEN-036.md | wireframe.html | uc/ | ac/ |
| 30 | [[SCREEN-037]] — 공지 수정 화면 | screens/SCREEN-037/SCREEN-037.md | wireframe.html | uc/ | ac/ |
| 31 | [[SCREEN-038]] — 이벤트유형 관리 화면 | screens/SCREEN-038/SCREEN-038.md | wireframe.html | uc/ | ac/ |
| 32 | [[SCREEN-039]] — 산출물 가져오기 | screens/SCREEN-039/SCREEN-039.md | wireframe.html | uc/ | ac/ |
| 33 | [[SCREEN-040]] — 관리자 페이지 진입 화면 | screens/SCREEN-040/SCREEN-040.md | wireframe.html | uc/ | ac/ |
| 34 | [[SCREEN-041]] — 관리자 패스워드 교체 | screens/SCREEN-041/SCREEN-041.md | wireframe.html | uc/ | ac/ |
| 35 | [[SCREEN-042]] — 연동 서버 주소 관리 화면 | screens/SCREEN-042/SCREEN-042.md | wireframe.html | uc/ | ac/ |
| 36 | [[SCREEN-043]] — 위험 작업 화면 | screens/SCREEN-043/SCREEN-043.md | wireframe.html | uc/ | ac/ |

## 변경 알림 (코드 재반영 필요)

| ITEM | type | 상태 |
|---|---|---|
| [[AC-1032]] | acceptance | NEW |
| [[AC-1033]] | acceptance | NEW |
| [[AC-1086]] | acceptance | NEW |
| [[AC-1087]] | acceptance | NEW |
| [[API-001]] | api_endpoint | CHANGED (v6→v8) |
| [[API-004]] | api_endpoint | CHANGED (v8→v10) |
| [[API-007]] | api_endpoint | CHANGED (v14→v16) |
| [[API-058]] | api_endpoint | CHANGED (v4→v4) |
| [[API-059]] | api_endpoint | CHANGED (v6→v9) |
| [[API-060]] | api_endpoint | CHANGED (v16→v18) |
| [[API-061]] | api_endpoint | CHANGED (v10→v11) |
| [[API-062]] | api_endpoint | CHANGED (v7→v10) |
| [[API-063]] | api_endpoint | CHANGED (v9→v10) |
| [[API-070]] | api_endpoint | CHANGED (v8→v9) |
| [[API-081]] | api_endpoint | CHANGED (v7→v8) |
| [[API-112]] | api_endpoint | CHANGED (v5→v6) |
| [[API-113]] | api_endpoint | CHANGED (v3→v4) |
| [[API-115]] | api_endpoint | CHANGED (v3→v4) |
| [[API-119]] | api_endpoint | CHANGED (v3→v4) |
| [[API-120]] | api_endpoint | CHANGED (v3→v4) |
| [[API-121]] | api_endpoint | CHANGED (v3→v4) |
| [[API-139]] | api_endpoint | CHANGED (v3→v4) |
| [[API-140]] | api_endpoint | CHANGED (v5→v6) |
| [[API-142]] | api_endpoint | CHANGED (v4→v8) |
| [[API-152]] | api_endpoint | CHANGED (v8→v11) |
| [[API-153]] | api_endpoint | CHANGED (v5→v6) |
| [[API-157]] | api_endpoint | CHANGED (v3→v4) |
| [[API-158]] | api_endpoint | CHANGED (v5→v8) |
| [[API-169]] | api_endpoint | CHANGED (v6→v7) |
| [[API-171]] | api_endpoint | CHANGED (v3→v4) |
| [[API-188]] | api_endpoint | CHANGED (v2→v3) |
| [[API-189]] | api_endpoint | CHANGED (v2→v3) |
| [[API-190]] | api_endpoint | CHANGED (v6→v7) |
| [[API-194]] | api_endpoint | CHANGED (v9→v12) |
| [[API-201]] | api_endpoint | CHANGED (v8→v9) |
| [[API-205]] | api_endpoint | CHANGED (v8→v9) |
| [[API-206]] | api_endpoint | CHANGED (v12→v14) |
| [[API-209]] | api_endpoint | CHANGED (v6→v7) |
| [[API-210]] | api_endpoint | CHANGED (v9→v11) |
| [[API-212]] | api_endpoint | CHANGED (v5→v6) |
| [[API-213]] | api_endpoint | CHANGED (v6→v7) |
| [[API-214]] | api_endpoint | CHANGED (v4→v5) |
| [[API-215]] | api_endpoint | CHANGED (v4→v5) |
| [[API-216]] | api_endpoint | CHANGED (v2→v6) |
| [[API-217]] | api_endpoint | CHANGED (v2→v7) |
| [[API-218]] | api_endpoint | CHANGED (v2→v6) |
| [[API-221]] | api_endpoint | CHANGED (v15→v17) |
| [[API-222]] | api_endpoint | CHANGED (v10→v12) |
| [[API-223]] | api_endpoint | CHANGED (v5→v7) |
| [[SHELL-002]] | app_shell | CHANGED (v5→v8) |
| [[NAV-002]] | navigation_tree | CHANGED (v8→v17) |
| [[ROLE-001]] | permission_role | CHANGED (v13→v14) |
| [[ROLE-003]] | permission_role | CHANGED (v10→v14) |
| [[ROLE-004]] | permission_role | CHANGED (v4→v5) |
| [[SD-009]] | screen_design | CHANGED (v12→v13) |
| [[SD-020]] | screen_design | CHANGED (v4→v5) |
| [[SD-021]] | screen_design | CHANGED (v4→v6) |
| [[SD-024]] | screen_design | CHANGED (v6→v12) |
| [[SD-025]] | screen_design | CHANGED (v4→v6) |
| [[SD-026]] | screen_design | CHANGED (v7→v11) |
| [[SD-027]] | screen_design | CHANGED (v9→v11) |
| [[SD-028]] | screen_design | CHANGED (v4→v9) |
| [[SD-029]] | screen_design | CHANGED (v5→v7) |
| [[SD-031]] | screen_design | CHANGED (v2→v3) |
| [[SD-033]] | screen_design | CHANGED (v11→v13) |
| [[SD-036]] | screen_design | CHANGED (v4→v5) |
| [[SD-038]] | screen_design | NEW |
| [[SCREEN-002]] | screen_spec | CHANGED (v26→v28) |
| [[SCREEN-004]] | screen_spec | CHANGED (v16→v17) |
| [[SCREEN-008]] | screen_spec | CHANGED (v47→v48) |
| [[SCREEN-009]] | screen_spec | CHANGED (v75→v76) |
| [[SCREEN-012]] | screen_spec | CHANGED (v47→v49) |
| [[SCREEN-021]] | screen_spec | CHANGED (v29→v29) |
| [[SCREEN-022]] | screen_spec | CHANGED (v46→v51) |
| [[SCREEN-023]] | screen_spec | CHANGED (v41→v47) |
| [[SCREEN-024]] | screen_spec | CHANGED (v32→v33) |
| [[SCREEN-025]] | screen_spec | CHANGED (v46→v49) |
| [[SCREEN-027]] | screen_spec | CHANGED (v42→v52) |
| [[SCREEN-028]] | screen_spec | CHANGED (v26→v34) |
| [[SCREEN-029]] | screen_spec | CHANGED (v41→v48) |
| [[SCREEN-032]] | screen_spec | CHANGED (v23→v26) |
| [[SCREEN-033]] | screen_spec | CHANGED (v23→v37) |
| [[SCREEN-039]] | screen_spec | CHANGED (v33→v43) |
| [[SCREEN-042]] | screen_spec | CHANGED (v8→v11) |
| [[UI-035]] | ui_component | CHANGED (v6→v7) |
| [[UI-037]] | ui_component | CHANGED (v4→v6) |
| [[UI-046]] | ui_component | CHANGED (v7→v8) |
| [[UI-071]] | ui_component | CHANGED (v4→v5) |
| [[UI-072]] | ui_component | CHANGED (v4→v5) |
| [[UI-096]] | ui_component | CHANGED (v5→v6) |
| [[UI-105]] | ui_component | CHANGED (v1→v3) |
| [[UI-129]] | ui_component | CHANGED (v1→v4) |
| [[UI-131]] | ui_component | CHANGED (v1→v2) |
| [[UI-132]] | ui_component | CHANGED (v1→v2) |
| [[UI-134]] | ui_component | CHANGED (v1→v2) |
| [[UI-138]] | ui_component | CHANGED (v1→v2) |
| [[UI-139]] | ui_component | CHANGED (v1→v2) |
| [[UI-140]] | ui_component | CHANGED (v1→v2) |
| [[UI-145]] | ui_component | NEW |
| [[UC-001]] | use_case | CHANGED (v14→v18) |
| [[UC-002]] | use_case | CHANGED (v16→v21) |
| [[UC-003]] | use_case | CHANGED (v15→v16) |
| [[UC-004]] | use_case | CHANGED (v16→v18) |
| [[UC-005]] | use_case | CHANGED (v11→v13) |
| [[UC-006]] | use_case | CHANGED (v10→v11) |
| [[UC-007]] | use_case | CHANGED (v14→v15) |
| [[UC-008]] | use_case | CHANGED (v15→v17) |
| [[UC-009]] | use_case | CHANGED (v21→v23) |
| [[UC-010]] | use_case | CHANGED (v14→v16) |
| [[UC-011]] | use_case | CHANGED (v13→v19) |
| [[UC-013]] | use_case | CHANGED (v11→v12) |
| [[UC-016]] | use_case | CHANGED (v25→v30) |
| [[UC-018]] | use_case | CHANGED (v19→v24) |
| [[UC-019]] | use_case | CHANGED (v24→v27) |
| [[UC-021]] | use_case | CHANGED (v21→v22) |
| [[UC-022]] | use_case | CHANGED (v23→v24) |
| [[UC-023]] | use_case | CHANGED (v27→v30) |
| [[UC-024]] | use_case | CHANGED (v21→v25) |
| [[UC-027]] | use_case | CHANGED (v17→v32) |
| [[UC-028]] | use_case | CHANGED (v7→v8) |
| [[UC-029]] | use_case | CHANGED (v12→v13) |
| [[UC-030]] | use_case | CHANGED (v14→v16) |
| [[UC-031]] | use_case | CHANGED (v14→v16) |
| [[UC-032]] | use_case | CHANGED (v13→v14) |
| [[UC-033]] | use_case | CHANGED (v4→v7) |
| [[UC-034]] | use_case | CHANGED (v6→v7) |
| [[UC-035]] | use_case | CHANGED (v16→v19) |
| [[UC-036]] | use_case | CHANGED (v4→v8) |
| [[UC-037]] | use_case | CHANGED (v4→v15) |
| [[UC-042]] | use_case | NEW |

## RETIRED (_retired/ 이동)

- [[SCREEN-034]] — 코드 제거 검토

## Obsidian 볼트로 보기

이 키트 루트를 볼트로 열면 화면↔API↔ROLE↔UC↔AC 관계가 그래프로 보인다
(frontmatter `links:` 가 `[[ID]]` wikilink). 그래프뷰 → 필터 → *Existing files only* 를
켜면 키트 밖 ITEM 의 유령 노드가 사라진다.

> ⚠️ 여러 화면이 공유하는 UC/AC 13건은 화면 폴더마다 같은 파일명으로
> 복제돼 있어 wikilink 가 어느 사본을 가리킬지 모호하다(구현엔 영향 없음):
> - UC-006 — SCREEN-005, SCREEN-025
> - UC-008 — SCREEN-005, SCREEN-010
> - UC-011 — SCREEN-008, SCREEN-009
> - UC-016 — SCREEN-009, SCREEN-032
> - UC-023 — SCREEN-018, SCREEN-019
> - UC-024 — SCREEN-028, SCREEN-029
> - UC-027 — SCREEN-029, SCREEN-033
> - UC-032 — SCREEN-026, SCREEN-038
> - UC-036 — SCREEN-032, SCREEN-039
> - AC-1032 — SCREEN-039, SCREEN-039
> - AC-1033 — SCREEN-039, SCREEN-039
> - AC-1086 — SCREEN-027, SCREEN-027
> - AC-1087 — SCREEN-027, SCREEN-027

## git 권장

`docs/screen-design/` 를 git 으로 함께 버전관리 권장.
