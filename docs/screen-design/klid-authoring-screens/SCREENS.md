# 저작도구 화면 화면 키트 — SCREENS.md

> 이 파일이 화면 구현의 진입점이다. mc-logi-screen-implement 는 이 파일부터 읽는다.
> 키트는 read-only 산출물 — **직접 수정 금지**. 갱신은 mc-logi-screen-kit 재실행.

## 키트 현황

| 항목 | 값 |
|---|---|
| Domain | DOMAIN-000 저작도구 화면 |
| last sync | 2026-08-25T09:51:55.013Z (session 23) |
| 화면 수 | 32개 |
| ui_component 카탈로그 | populated 144건 |
| 출력 루트 | /Users/ck/orca/workspaces/klid-label/screenshot/docs/screen-design/klid-authoring-screens |
| 생성 | download-kit.mjs + arrange-screen-kit.mjs (결정적, LLM 0) |

## 화면 목록

| SCREEN-ID | 화면명 | 상태 | 와이어프레임 | consumes_apis | required_roles |
|---|---|---|---|---|---|
| [[SCREEN-001]] | 세션 인계 진입 화면 | NEW | ✅ |  |  |
| [[SCREEN-002]] | 역할 클레임 화면 | NEW | ✅ | [[API-007]] |  |
| [[SCREEN-003]] | 접근 거부 화면 | NEW | ✅ |  |  |
| [[SCREEN-004]] | 개발용 로그인 화면 | NEW | ✅ | [[API-153]] |  |
| [[SCREEN-005]] | 라벨링 캔버스 화면 | NEW | ✅ | [[API-018]], [[API-019]], [[API-020]], [[API-021]], [[API-024]], [[API-032]], [[API-066]], [[API-067]], [[API-102]], [[API-103]], [[API-104]], [[API-105]], [[API-123]], [[API-124]], [[API-125]], [[API-126]], [[API-127]], [[API-128]], [[API-129]], [[API-132]], [[API-134]], [[API-133]], [[API-135]], [[API-093]], [[API-182]], [[API-012]], [[API-178]], [[API-022]], [[API-023]], [[API-168]], [[API-170]], [[API-172]], [[API-173]], [[API-183]], [[API-184]], [[API-177]], [[API-195]], [[API-196]], [[API-197]], [[API-034]], [[API-035]], [[API-036]], [[API-193]], [[API-204]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-006]] | 마킹 화면 | NEW | ✅ | [[API-047]], [[API-043]], [[API-091]], [[API-114]], [[API-084]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-008]] | 영상 처리 현황 화면 | NEW | ✅ | [[API-042]], [[API-047]], [[API-068]], [[API-070]], [[API-071]], [[API-181]], [[API-212]], [[API-214]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-009]] | 영상 상세 화면 | NEW | ✅ | [[API-021]], [[API-043]], [[API-044]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-010]] | 로드 버전 선택 | NEW | ✅ | [[API-197]], [[API-182]], [[API-195]], [[API-034]], [[API-035]], [[API-036]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-011]] | 대시보드 화면 | NEW | ✅ | [[API-042]], [[API-055]], [[API-072]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-012]] | 작업 목록 화면 | NEW | ✅ | [[API-001]], [[API-002]], [[API-070]], [[API-071]], [[API-072]], [[API-073]], [[API-136]], [[API-137]], [[API-116]], [[API-187]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-018]] | 검수 목록 화면 | NEW | ✅ | [[API-008]], [[API-138]] | [[ROLE-001]] |
| [[SCREEN-019]] | 검수 상세 화면 | NEW | ✅ | [[API-009]], [[API-010]], [[API-011]], [[API-013]], [[API-014]], [[API-015]], [[API-021]], [[API-132]], [[API-066]], [[API-102]], [[API-103]], [[API-104]], [[API-105]] | [[ROLE-001]] |
| [[SCREEN-020]] | 작업자 통계 화면 | NEW | ✅ | [[API-001]], [[API-056]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-021]] | 전체 구축 현황 화면 | NEW | ✅ | [[API-057]], [[API-058]] | [[ROLE-001]] |
| [[SCREEN-022]] | 증강 요청 화면 | NEW | ✅ | [[API-042]], [[API-059]], [[API-060]], [[API-092]], [[API-179]] | [[ROLE-001]] |
| [[SCREEN-023]] | 증강 결과 화면 | NEW | ✅ | [[API-061]], [[API-062]], [[API-063]], [[API-188]], [[API-189]], [[API-190]], [[API-175]] | [[ROLE-001]] |
| [[SCREEN-024]] | 사용자 관리 화면 | NEW | ✅ | [[API-001]], [[API-004]] | [[ROLE-001]] |
| [[SCREEN-025]] | 시스템 설정 화면 | NEW | ✅ | [[API-068]], [[API-069]], [[API-090]], [[API-194]] | [[ROLE-001]] |
| [[SCREEN-026]] | 프리셋 관리 화면 | NEW | ✅ | [[API-037]], [[API-038]], [[API-039]], [[API-040]], [[API-185]] | [[ROLE-001]] |
| [[SCREEN-027]] | 수동 업로드 | NEW | ✅ | [[API-043]], [[API-152]], [[API-156]], [[API-158]], [[API-160]], [[API-162]], [[API-164]], [[API-216]], [[API-217]], [[API-218]] | [[ROLE-001]] |
| [[SCREEN-028]] | 포털 홈 화면 | NEW | ✅ | [[API-115]], [[API-203]] | [[ROLE-003]] |
| [[SCREEN-029]] | 포털 라벨링 화면 | NEW | ✅ | [[API-024]], [[API-082]], [[API-110]], [[API-111]] | [[ROLE-003]] |
| [[SCREEN-030]] | 공지 목록 화면 | NEW | ✅ | [[API-095]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-031]] | 공지 상세 화면 | NEW | ✅ | [[API-096]], [[API-099]], [[API-100]], [[API-101]], [[API-107]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-032]] | 비식별 신고 관리 화면 | NEW | ✅ | [[API-094]], [[API-109]], [[API-202]] | [[ROLE-001]] |
| [[SCREEN-033]] | 포털 업로드 화면 | NEW | ✅ | [[API-139]], [[API-142]], [[API-151]], [[API-163]], [[API-166]], [[API-169]], [[API-171]], [[API-161]] | [[ROLE-003]] |
| [[SCREEN-034]] | 포털 업로드 라벨링 화면 | NEW | ✅ | [[API-140]], [[API-149]], [[API-154]], [[API-155]], [[API-157]], [[API-159]] | [[ROLE-003]] |
| [[SCREEN-035]] | 라벨 관리 화면 | NEW | ✅ | [[API-024]], [[API-025]], [[API-026]], [[API-027]], [[API-028]], [[API-029]], [[API-030]], [[API-031]] | [[ROLE-001]] |
| [[SCREEN-036]] | 공지 작성 화면 | NEW | ✅ | [[API-097]] | [[ROLE-001]] |
| [[SCREEN-037]] | 공지 수정 화면 | NEW | ✅ | [[API-096]], [[API-098]], [[API-106]], [[API-108]] | [[ROLE-001]] |
| [[SCREEN-038]] | 이벤트유형 관리 화면 | NEW | ✅ | [[API-185]], [[API-186]], [[API-219]], [[API-220]] | [[ROLE-001]] |

## 공유 자산 인덱스

| type | 파일 | 건수 |
|---|---|---|
| design_system | _shared/design-system.md | 1 |
| ui_component | _shared/ui-catalog.md | 144 |
| app_shell + nav | _shared/shell-nav.md | 4 |
| api_endpoint | _shared/api/ | 189 |
| constant | _shared/constant/ | 2 |
| permission_role | _shared/role/ | 3 |
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
| 2 | [[SCREEN-002]] — 역할 클레임 화면 | screens/SCREEN-002/SCREEN-002.md | wireframe.html | uc/ | ac/ |
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
| 21 | [[SCREEN-027]] — 수동 업로드 | screens/SCREEN-027/SCREEN-027.md | wireframe.html | uc/ | ac/ |
| 22 | [[SCREEN-028]] — 포털 홈 화면 | screens/SCREEN-028/SCREEN-028.md | wireframe.html | uc/ | ac/ |
| 23 | [[SCREEN-029]] — 포털 라벨링 화면 | screens/SCREEN-029/SCREEN-029.md | wireframe.html | uc/ | ac/ |
| 24 | [[SCREEN-030]] — 공지 목록 화면 | screens/SCREEN-030/SCREEN-030.md | wireframe.html | uc/ | ac/ |
| 25 | [[SCREEN-031]] — 공지 상세 화면 | screens/SCREEN-031/SCREEN-031.md | wireframe.html | uc/ | ac/ |
| 26 | [[SCREEN-032]] — 비식별 신고 관리 화면 | screens/SCREEN-032/SCREEN-032.md | wireframe.html | uc/ | ac/ |
| 27 | [[SCREEN-033]] — 포털 업로드 화면 | screens/SCREEN-033/SCREEN-033.md | wireframe.html | uc/ | ac/ |
| 28 | [[SCREEN-034]] — 포털 업로드 라벨링 화면 | screens/SCREEN-034/SCREEN-034.md | wireframe.html | uc/ | ac/ |
| 29 | [[SCREEN-035]] — 라벨 관리 화면 | screens/SCREEN-035/SCREEN-035.md | wireframe.html | uc/ | ac/ |
| 30 | [[SCREEN-036]] — 공지 작성 화면 | screens/SCREEN-036/SCREEN-036.md | wireframe.html | uc/ | ac/ |
| 31 | [[SCREEN-037]] — 공지 수정 화면 | screens/SCREEN-037/SCREEN-037.md | wireframe.html | uc/ | ac/ |
| 32 | [[SCREEN-038]] — 이벤트유형 관리 화면 | screens/SCREEN-038/SCREEN-038.md | wireframe.html | uc/ | ac/ |

## 변경 알림 (코드 재반영 필요)

| ITEM | type | 상태 |
|---|---|---|
| [[AC-001]] | acceptance | NEW |
| [[AC-002]] | acceptance | NEW |
| [[AC-003]] | acceptance | NEW |
| [[AC-004]] | acceptance | NEW |
| [[AC-005]] | acceptance | NEW |
| [[AC-006]] | acceptance | NEW |
| [[AC-007]] | acceptance | NEW |
| [[AC-008]] | acceptance | NEW |
| [[AC-009]] | acceptance | NEW |
| [[AC-010]] | acceptance | NEW |
| [[AC-011]] | acceptance | NEW |
| [[AC-013]] | acceptance | NEW |
| [[AC-016]] | acceptance | NEW |
| [[AC-017]] | acceptance | NEW |
| [[AC-018]] | acceptance | NEW |
| [[AC-019]] | acceptance | NEW |
| [[AC-020]] | acceptance | NEW |
| [[AC-021]] | acceptance | NEW |
| [[AC-022]] | acceptance | NEW |
| [[AC-023]] | acceptance | NEW |
| [[AC-024]] | acceptance | NEW |
| [[AC-025]] | acceptance | NEW |
| [[AC-026]] | acceptance | NEW |
| [[AC-027]] | acceptance | NEW |
| [[AC-028]] | acceptance | NEW |
| [[AC-029]] | acceptance | NEW |
| [[AC-030]] | acceptance | NEW |
| [[AC-031]] | acceptance | NEW |
| [[AC-032]] | acceptance | NEW |
| [[AC-033]] | acceptance | NEW |
| [[AC-034]] | acceptance | NEW |
| [[AC-035]] | acceptance | NEW |
| [[AC-036]] | acceptance | NEW |
| [[AC-037]] | acceptance | NEW |
| [[AC-038]] | acceptance | NEW |
| [[AC-039]] | acceptance | NEW |
| [[AC-040]] | acceptance | NEW |
| [[AC-099]] | acceptance | NEW |
| [[AC-100]] | acceptance | NEW |
| [[AC-101]] | acceptance | NEW |
| [[AC-102]] | acceptance | NEW |
| [[AC-103]] | acceptance | NEW |
| [[AC-104]] | acceptance | NEW |
| [[AC-105]] | acceptance | NEW |
| [[AC-106]] | acceptance | NEW |
| [[API-001]] | api_endpoint | NEW |
| [[API-002]] | api_endpoint | NEW |
| [[API-003]] | api_endpoint | NEW |
| [[API-004]] | api_endpoint | NEW |
| [[API-005]] | api_endpoint | NEW |
| [[API-006]] | api_endpoint | NEW |
| [[API-007]] | api_endpoint | NEW |
| [[API-008]] | api_endpoint | NEW |
| [[API-009]] | api_endpoint | NEW |
| [[API-010]] | api_endpoint | NEW |
| [[API-011]] | api_endpoint | NEW |
| [[API-012]] | api_endpoint | NEW |
| [[API-013]] | api_endpoint | NEW |
| [[API-014]] | api_endpoint | NEW |
| [[API-015]] | api_endpoint | NEW |
| [[API-016]] | api_endpoint | NEW |
| [[API-017]] | api_endpoint | NEW |
| [[API-018]] | api_endpoint | NEW |
| [[API-019]] | api_endpoint | NEW |
| [[API-020]] | api_endpoint | NEW |
| [[API-021]] | api_endpoint | NEW |
| [[API-022]] | api_endpoint | NEW |
| [[API-023]] | api_endpoint | NEW |
| [[API-024]] | api_endpoint | NEW |
| [[API-025]] | api_endpoint | NEW |
| [[API-026]] | api_endpoint | NEW |
| [[API-027]] | api_endpoint | NEW |
| [[API-028]] | api_endpoint | NEW |
| [[API-029]] | api_endpoint | NEW |
| [[API-030]] | api_endpoint | NEW |
| [[API-031]] | api_endpoint | NEW |
| [[API-032]] | api_endpoint | NEW |
| [[API-034]] | api_endpoint | NEW |
| [[API-035]] | api_endpoint | NEW |
| [[API-036]] | api_endpoint | NEW |
| [[API-037]] | api_endpoint | NEW |
| [[API-038]] | api_endpoint | NEW |
| [[API-039]] | api_endpoint | NEW |
| [[API-040]] | api_endpoint | NEW |
| [[API-041]] | api_endpoint | NEW |
| [[API-042]] | api_endpoint | NEW |
| [[API-043]] | api_endpoint | NEW |
| [[API-044]] | api_endpoint | NEW |
| [[API-045]] | api_endpoint | NEW |
| [[API-046]] | api_endpoint | NEW |
| [[API-047]] | api_endpoint | NEW |
| [[API-055]] | api_endpoint | NEW |
| [[API-056]] | api_endpoint | NEW |
| [[API-057]] | api_endpoint | NEW |
| [[API-058]] | api_endpoint | NEW |
| [[API-059]] | api_endpoint | NEW |
| [[API-060]] | api_endpoint | NEW |
| [[API-061]] | api_endpoint | NEW |
| [[API-062]] | api_endpoint | NEW |
| [[API-063]] | api_endpoint | NEW |
| [[API-065]] | api_endpoint | NEW |
| [[API-066]] | api_endpoint | NEW |
| [[API-067]] | api_endpoint | NEW |
| [[API-068]] | api_endpoint | NEW |
| [[API-069]] | api_endpoint | NEW |
| [[API-070]] | api_endpoint | NEW |
| [[API-071]] | api_endpoint | NEW |
| [[API-072]] | api_endpoint | NEW |
| [[API-073]] | api_endpoint | NEW |
| [[API-074]] | api_endpoint | NEW |
| [[API-075]] | api_endpoint | NEW |
| [[API-076]] | api_endpoint | NEW |
| [[API-081]] | api_endpoint | NEW |
| [[API-082]] | api_endpoint | NEW |
| [[API-083]] | api_endpoint | NEW |
| [[API-084]] | api_endpoint | NEW |
| [[API-090]] | api_endpoint | NEW |
| [[API-091]] | api_endpoint | NEW |
| [[API-092]] | api_endpoint | NEW |
| [[API-093]] | api_endpoint | NEW |
| [[API-094]] | api_endpoint | NEW |
| [[API-095]] | api_endpoint | NEW |
| [[API-096]] | api_endpoint | NEW |
| [[API-097]] | api_endpoint | NEW |
| [[API-098]] | api_endpoint | NEW |
| [[API-099]] | api_endpoint | NEW |
| [[API-100]] | api_endpoint | NEW |
| [[API-101]] | api_endpoint | NEW |
| [[API-102]] | api_endpoint | NEW |
| [[API-103]] | api_endpoint | NEW |
| [[API-104]] | api_endpoint | NEW |
| [[API-105]] | api_endpoint | NEW |
| [[API-106]] | api_endpoint | NEW |
| [[API-107]] | api_endpoint | NEW |
| [[API-108]] | api_endpoint | NEW |
| [[API-109]] | api_endpoint | NEW |
| [[API-110]] | api_endpoint | NEW |
| [[API-111]] | api_endpoint | NEW |
| [[API-112]] | api_endpoint | NEW |
| [[API-113]] | api_endpoint | NEW |
| [[API-114]] | api_endpoint | NEW |
| [[API-115]] | api_endpoint | NEW |
| [[API-116]] | api_endpoint | NEW |
| [[API-117]] | api_endpoint | NEW |
| [[API-118]] | api_endpoint | NEW |
| [[API-119]] | api_endpoint | NEW |
| [[API-120]] | api_endpoint | NEW |
| [[API-121]] | api_endpoint | NEW |
| [[API-122]] | api_endpoint | NEW |
| [[API-123]] | api_endpoint | NEW |
| [[API-124]] | api_endpoint | NEW |
| [[API-125]] | api_endpoint | NEW |
| [[API-126]] | api_endpoint | NEW |
| [[API-127]] | api_endpoint | NEW |
| [[API-128]] | api_endpoint | NEW |
| [[API-129]] | api_endpoint | NEW |
| [[API-132]] | api_endpoint | NEW |
| [[API-133]] | api_endpoint | NEW |
| [[API-134]] | api_endpoint | NEW |
| [[API-135]] | api_endpoint | NEW |
| [[API-136]] | api_endpoint | NEW |
| [[API-137]] | api_endpoint | NEW |
| [[API-138]] | api_endpoint | NEW |
| [[API-139]] | api_endpoint | NEW |
| [[API-140]] | api_endpoint | NEW |
| [[API-141]] | api_endpoint | NEW |
| [[API-142]] | api_endpoint | NEW |
| [[API-143]] | api_endpoint | NEW |
| [[API-144]] | api_endpoint | NEW |
| [[API-145]] | api_endpoint | NEW |
| [[API-146]] | api_endpoint | NEW |
| [[API-147]] | api_endpoint | NEW |
| [[API-148]] | api_endpoint | NEW |
| [[API-149]] | api_endpoint | NEW |
| [[API-150]] | api_endpoint | NEW |
| [[API-151]] | api_endpoint | NEW |
| [[API-152]] | api_endpoint | NEW |
| [[API-153]] | api_endpoint | NEW |
| [[API-154]] | api_endpoint | NEW |
| [[API-155]] | api_endpoint | NEW |
| [[API-156]] | api_endpoint | NEW |
| [[API-157]] | api_endpoint | NEW |
| [[API-158]] | api_endpoint | NEW |
| [[API-159]] | api_endpoint | NEW |
| [[API-160]] | api_endpoint | NEW |
| [[API-161]] | api_endpoint | NEW |
| [[API-162]] | api_endpoint | NEW |
| [[API-163]] | api_endpoint | NEW |
| [[API-164]] | api_endpoint | NEW |
| [[API-165]] | api_endpoint | NEW |
| [[API-166]] | api_endpoint | NEW |
| [[API-167]] | api_endpoint | NEW |
| [[API-168]] | api_endpoint | NEW |
| [[API-169]] | api_endpoint | NEW |
| [[API-170]] | api_endpoint | NEW |
| [[API-171]] | api_endpoint | NEW |
| [[API-172]] | api_endpoint | NEW |
| [[API-173]] | api_endpoint | NEW |
| [[API-174]] | api_endpoint | NEW |
| [[API-175]] | api_endpoint | NEW |
| [[API-176]] | api_endpoint | NEW |
| [[API-177]] | api_endpoint | NEW |
| [[API-178]] | api_endpoint | NEW |
| [[API-179]] | api_endpoint | NEW |
| [[API-181]] | api_endpoint | NEW |
| [[API-182]] | api_endpoint | NEW |
| [[API-183]] | api_endpoint | NEW |
| [[API-184]] | api_endpoint | NEW |
| [[API-185]] | api_endpoint | NEW |
| [[API-186]] | api_endpoint | NEW |
| [[API-187]] | api_endpoint | NEW |
| [[API-188]] | api_endpoint | NEW |
| [[API-189]] | api_endpoint | NEW |
| [[API-190]] | api_endpoint | NEW |
| [[API-191]] | api_endpoint | NEW |
| [[API-192]] | api_endpoint | NEW |
| [[API-193]] | api_endpoint | NEW |
| [[API-194]] | api_endpoint | NEW |
| [[API-195]] | api_endpoint | NEW |
| [[API-196]] | api_endpoint | NEW |
| [[API-197]] | api_endpoint | NEW |
| [[API-198]] | api_endpoint | NEW |
| [[API-199]] | api_endpoint | NEW |
| [[API-200]] | api_endpoint | NEW |
| [[API-201]] | api_endpoint | NEW |
| [[API-202]] | api_endpoint | NEW |
| [[API-203]] | api_endpoint | NEW |
| [[API-204]] | api_endpoint | NEW |
| [[API-212]] | api_endpoint | NEW |
| [[API-213]] | api_endpoint | NEW |
| [[API-214]] | api_endpoint | NEW |
| [[API-216]] | api_endpoint | NEW |
| [[API-217]] | api_endpoint | NEW |
| [[API-218]] | api_endpoint | NEW |
| [[CONST-001]] | constant | NEW |
| [[CONST-002]] | constant | NEW |
| [[DS-001]] | design_system | NEW |
| [[NAV-001]] | navigation_tree | NEW |
| [[NAV-002]] | navigation_tree | NEW |
| [[ROLE-001]] | permission_role | NEW |
| [[ROLE-002]] | permission_role | NEW |
| [[ROLE-003]] | permission_role | NEW |
| [[SD-001]] | screen_design | NEW |
| [[SD-002]] | screen_design | NEW |
| [[SD-003]] | screen_design | NEW |
| [[SD-004]] | screen_design | NEW |
| [[SD-005]] | screen_design | NEW |
| [[SD-006]] | screen_design | NEW |
| [[SCREEN-001]] | screen_spec | NEW |
| [[SCREEN-002]] | screen_spec | NEW |
| [[SCREEN-003]] | screen_spec | NEW |
| [[SCREEN-004]] | screen_spec | NEW |
| [[SCREEN-005]] | screen_spec | NEW |
| [[SCREEN-006]] | screen_spec | NEW |
| [[SCREEN-008]] | screen_spec | NEW |
| [[SCREEN-009]] | screen_spec | NEW |
| [[SCREEN-010]] | screen_spec | NEW |
| [[SCREEN-011]] | screen_spec | NEW |
| [[SCREEN-012]] | screen_spec | NEW |
| [[SCREEN-018]] | screen_spec | NEW |
| [[SCREEN-019]] | screen_spec | NEW |
| [[SCREEN-020]] | screen_spec | NEW |
| [[SCREEN-021]] | screen_spec | NEW |
| [[SCREEN-022]] | screen_spec | NEW |
| [[SCREEN-023]] | screen_spec | NEW |
| [[SCREEN-024]] | screen_spec | NEW |
| [[SCREEN-025]] | screen_spec | NEW |
| [[SCREEN-026]] | screen_spec | NEW |
| [[SCREEN-027]] | screen_spec | NEW |
| [[SCREEN-028]] | screen_spec | NEW |
| [[SCREEN-029]] | screen_spec | NEW |
| [[SCREEN-030]] | screen_spec | NEW |
| [[SCREEN-031]] | screen_spec | NEW |
| [[SCREEN-032]] | screen_spec | NEW |
| [[SCREEN-033]] | screen_spec | NEW |
| [[SCREEN-034]] | screen_spec | NEW |
| [[SCREEN-035]] | screen_spec | NEW |
| [[SCREEN-036]] | screen_spec | NEW |
| [[SCREEN-037]] | screen_spec | NEW |
| [[SCREEN-038]] | screen_spec | NEW |
| [[SHELL-001]] | app_shell | NEW |
| [[SHELL-002]] | app_shell | NEW |
| [[SD-007]] | screen_design | NEW |
| [[SD-008]] | screen_design | NEW |
| [[SD-009]] | screen_design | NEW |
| [[SD-010]] | screen_design | NEW |
| [[SD-011]] | screen_design | NEW |
| [[SD-012]] | screen_design | NEW |
| [[SD-013]] | screen_design | NEW |
| [[SD-014]] | screen_design | NEW |
| [[SD-015]] | screen_design | NEW |
| [[SD-016]] | screen_design | NEW |
| [[SD-017]] | screen_design | NEW |
| [[SD-018]] | screen_design | NEW |
| [[SD-019]] | screen_design | NEW |
| [[SD-020]] | screen_design | NEW |
| [[SD-021]] | screen_design | NEW |
| [[SD-022]] | screen_design | NEW |
| [[SD-023]] | screen_design | NEW |
| [[SD-024]] | screen_design | NEW |
| [[SD-025]] | screen_design | NEW |
| [[SD-026]] | screen_design | NEW |
| [[SD-027]] | screen_design | NEW |
| [[SD-028]] | screen_design | NEW |
| [[SD-029]] | screen_design | NEW |
| [[SD-030]] | screen_design | NEW |
| [[SD-031]] | screen_design | NEW |
| [[SD-032]] | screen_design | NEW |
| [[SD-033]] | screen_design | NEW |
| [[UC-001]] | use_case | NEW |
| [[UC-002]] | use_case | NEW |
| [[UC-003]] | use_case | NEW |
| [[UC-004]] | use_case | NEW |
| [[UC-005]] | use_case | NEW |
| [[UC-006]] | use_case | NEW |
| [[UC-007]] | use_case | NEW |
| [[UC-008]] | use_case | NEW |
| [[UC-009]] | use_case | NEW |
| [[UC-010]] | use_case | NEW |
| [[UC-011]] | use_case | NEW |
| [[UI-001]] | ui_component | NEW |
| [[UI-002]] | ui_component | NEW |
| [[UI-003]] | ui_component | NEW |
| [[UI-004]] | ui_component | NEW |
| [[UI-005]] | ui_component | NEW |
| [[UI-006]] | ui_component | NEW |
| [[UI-007]] | ui_component | NEW |
| [[UI-008]] | ui_component | NEW |
| [[UI-009]] | ui_component | NEW |
| [[UI-010]] | ui_component | NEW |
| [[UI-011]] | ui_component | NEW |
| [[UI-012]] | ui_component | NEW |
| [[UI-013]] | ui_component | NEW |
| [[UI-014]] | ui_component | NEW |
| [[UI-015]] | ui_component | NEW |
| [[UI-016]] | ui_component | NEW |
| [[UI-017]] | ui_component | NEW |
| [[UI-018]] | ui_component | NEW |
| [[UI-019]] | ui_component | NEW |
| [[UI-020]] | ui_component | NEW |
| [[UI-021]] | ui_component | NEW |
| [[UI-022]] | ui_component | NEW |
| [[UI-023]] | ui_component | NEW |
| [[UC-013]] | use_case | NEW |
| [[UC-016]] | use_case | NEW |
| [[UC-018]] | use_case | NEW |
| [[UC-019]] | use_case | NEW |
| [[UC-021]] | use_case | NEW |
| [[UC-022]] | use_case | NEW |
| [[UC-023]] | use_case | NEW |
| [[UC-024]] | use_case | NEW |
| [[UC-027]] | use_case | NEW |
| [[UC-028]] | use_case | NEW |
| [[UC-029]] | use_case | NEW |
| [[UC-030]] | use_case | NEW |
| [[UC-031]] | use_case | NEW |
| [[UC-032]] | use_case | NEW |
| [[UC-033]] | use_case | NEW |
| [[UC-034]] | use_case | NEW |
| [[UC-037]] | use_case | NEW |
| [[UI-024]] | ui_component | NEW |
| [[UI-025]] | ui_component | NEW |
| [[UI-026]] | ui_component | NEW |
| [[UI-027]] | ui_component | NEW |
| [[UI-028]] | ui_component | NEW |
| [[UI-029]] | ui_component | NEW |
| [[UI-030]] | ui_component | NEW |
| [[UI-031]] | ui_component | NEW |
| [[UI-032]] | ui_component | NEW |
| [[UI-033]] | ui_component | NEW |
| [[UI-034]] | ui_component | NEW |
| [[UI-035]] | ui_component | NEW |
| [[UI-036]] | ui_component | NEW |
| [[UI-037]] | ui_component | NEW |
| [[UI-038]] | ui_component | NEW |
| [[UI-039]] | ui_component | NEW |
| [[UI-040]] | ui_component | NEW |
| [[UI-041]] | ui_component | NEW |
| [[UI-042]] | ui_component | NEW |
| [[UI-043]] | ui_component | NEW |
| [[UI-044]] | ui_component | NEW |
| [[UI-045]] | ui_component | NEW |
| [[UI-046]] | ui_component | NEW |
| [[UI-047]] | ui_component | NEW |
| [[UI-048]] | ui_component | NEW |
| [[UI-049]] | ui_component | NEW |
| [[UI-050]] | ui_component | NEW |
| [[UI-051]] | ui_component | NEW |
| [[UI-052]] | ui_component | NEW |
| [[UI-053]] | ui_component | NEW |
| [[UI-054]] | ui_component | NEW |
| [[UI-055]] | ui_component | NEW |
| [[UI-056]] | ui_component | NEW |
| [[UI-057]] | ui_component | NEW |
| [[UI-058]] | ui_component | NEW |
| [[UI-059]] | ui_component | NEW |
| [[UI-060]] | ui_component | NEW |
| [[UI-061]] | ui_component | NEW |
| [[UI-062]] | ui_component | NEW |
| [[UI-063]] | ui_component | NEW |
| [[UI-064]] | ui_component | NEW |
| [[UI-065]] | ui_component | NEW |
| [[UI-066]] | ui_component | NEW |
| [[UI-067]] | ui_component | NEW |
| [[UI-068]] | ui_component | NEW |
| [[UI-069]] | ui_component | NEW |
| [[UI-070]] | ui_component | NEW |
| [[UI-071]] | ui_component | NEW |
| [[UI-072]] | ui_component | NEW |
| [[UI-073]] | ui_component | NEW |
| [[UI-074]] | ui_component | NEW |
| [[UI-075]] | ui_component | NEW |
| [[UI-076]] | ui_component | NEW |
| [[UI-077]] | ui_component | NEW |
| [[UI-078]] | ui_component | NEW |
| [[UI-079]] | ui_component | NEW |
| [[UI-080]] | ui_component | NEW |
| [[UI-081]] | ui_component | NEW |
| [[UI-082]] | ui_component | NEW |
| [[UI-083]] | ui_component | NEW |
| [[UI-084]] | ui_component | NEW |
| [[UI-085]] | ui_component | NEW |
| [[UI-086]] | ui_component | NEW |
| [[UI-087]] | ui_component | NEW |
| [[UI-088]] | ui_component | NEW |
| [[UI-089]] | ui_component | NEW |
| [[UI-090]] | ui_component | NEW |
| [[UI-091]] | ui_component | NEW |
| [[UI-092]] | ui_component | NEW |
| [[UI-093]] | ui_component | NEW |
| [[UI-094]] | ui_component | NEW |
| [[UI-095]] | ui_component | NEW |
| [[UI-096]] | ui_component | NEW |
| [[UI-097]] | ui_component | NEW |
| [[UI-098]] | ui_component | NEW |
| [[UI-099]] | ui_component | NEW |
| [[UI-100]] | ui_component | NEW |
| [[UI-101]] | ui_component | NEW |
| [[UI-102]] | ui_component | NEW |
| [[UI-103]] | ui_component | NEW |
| [[UI-104]] | ui_component | NEW |
| [[UI-105]] | ui_component | NEW |
| [[UI-106]] | ui_component | NEW |
| [[UI-107]] | ui_component | NEW |
| [[UI-108]] | ui_component | NEW |
| [[UI-109]] | ui_component | NEW |
| [[UI-110]] | ui_component | NEW |
| [[UI-111]] | ui_component | NEW |
| [[UI-112]] | ui_component | NEW |
| [[UI-113]] | ui_component | NEW |
| [[UI-114]] | ui_component | NEW |
| [[UI-115]] | ui_component | NEW |
| [[UI-116]] | ui_component | NEW |
| [[UI-117]] | ui_component | NEW |
| [[UI-118]] | ui_component | NEW |
| [[UI-119]] | ui_component | NEW |
| [[UI-120]] | ui_component | NEW |
| [[UI-121]] | ui_component | NEW |
| [[UI-122]] | ui_component | NEW |
| [[UI-123]] | ui_component | NEW |
| [[UI-124]] | ui_component | NEW |
| [[UI-125]] | ui_component | NEW |
| [[UI-126]] | ui_component | NEW |
| [[UI-127]] | ui_component | NEW |
| [[UI-128]] | ui_component | NEW |
| [[UI-129]] | ui_component | NEW |
| [[UI-130]] | ui_component | NEW |
| [[UI-131]] | ui_component | NEW |
| [[UI-132]] | ui_component | NEW |
| [[UI-133]] | ui_component | NEW |
| [[UI-134]] | ui_component | NEW |
| [[UI-135]] | ui_component | NEW |
| [[UI-136]] | ui_component | NEW |
| [[UI-137]] | ui_component | NEW |
| [[UI-138]] | ui_component | NEW |
| [[UI-139]] | ui_component | NEW |
| [[UI-140]] | ui_component | NEW |
| [[UI-141]] | ui_component | NEW |
| [[UI-142]] | ui_component | NEW |
| [[UI-143]] | ui_component | NEW |
| [[UI-144]] | ui_component | NEW |

## Obsidian 볼트로 보기

이 키트 루트를 볼트로 열면 화면↔API↔ROLE↔UC↔AC 관계가 그래프로 보인다
(frontmatter `links:` 가 `[[ID]]` wikilink). 그래프뷰 → 필터 → *Existing files only* 를
켜면 키트 밖 ITEM 의 유령 노드가 사라진다.

> ⚠️ 여러 화면이 공유하는 UC/AC 31건은 화면 폴더마다 같은 파일명으로
> 복제돼 있어 wikilink 가 어느 사본을 가리킬지 모호하다(구현엔 영향 없음):
> - UC-006 — SCREEN-005, SCREEN-025
> - UC-008 — SCREEN-005, SCREEN-010
> - UC-011 — SCREEN-008, SCREEN-009
> - UC-016 — SCREEN-009, SCREEN-032
> - UC-023 — SCREEN-018, SCREEN-019
> - UC-024 — SCREEN-028, SCREEN-029
> - UC-027 — SCREEN-033, SCREEN-034
> - AC-006 — SCREEN-005, SCREEN-025
> - AC-008 — SCREEN-005, SCREEN-010
> - AC-011 — SCREEN-008, SCREEN-009
> - AC-016 — SCREEN-009, SCREEN-032
> - AC-018 — SCREEN-022, SCREEN-023
> - AC-019 — SCREEN-008, SCREEN-009, SCREEN-009, SCREEN-032
> - AC-021 — SCREEN-005, SCREEN-023
> - AC-022 — SCREEN-018, SCREEN-019
> - AC-023 — SCREEN-005, SCREEN-008, SCREEN-009
> - AC-024 — SCREEN-005, SCREEN-005
> - AC-032 — SCREEN-028, SCREEN-029
> - AC-033 — SCREEN-028, SCREEN-029
> - AC-034 — SCREEN-028, SCREEN-029
> - AC-035 — SCREEN-028, SCREEN-029
> - AC-036 — SCREEN-028, SCREEN-029
> - AC-037 — SCREEN-028, SCREEN-029
> - AC-099 — SCREEN-027, SCREEN-027
> - AC-100 — SCREEN-027, SCREEN-027
> - AC-101 — SCREEN-027, SCREEN-027
> - AC-102 — SCREEN-027, SCREEN-027
> - AC-103 — SCREEN-027, SCREEN-027
> - AC-104 — SCREEN-027, SCREEN-027
> - AC-105 — SCREEN-027, SCREEN-027
> - AC-106 — SCREEN-027, SCREEN-027

## git 권장

`docs/screen-design/` 를 git 으로 함께 버전관리 권장.
