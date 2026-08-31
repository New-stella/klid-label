# Version Master —  (-) — 화면 키트

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | -  |
| 다운로드 화면 | SCREEN-001, SCREEN-002, SCREEN-003, SCREEN-004, SCREEN-005, SCREEN-006, SCREEN-008, SCREEN-009, SCREEN-010, SCREEN-011, SCREEN-012, SCREEN-018, SCREEN-019, SCREEN-020, SCREEN-021, SCREEN-022, SCREEN-023, SCREEN-024, SCREEN-025, SCREEN-026, SCREEN-027, SCREEN-028, SCREEN-029, SCREEN-030, SCREEN-031, SCREEN-032, SCREEN-033, SCREEN-034, SCREEN-035, SCREEN-036, SCREEN-037, SCREEN-038, SCREEN-039, SCREEN-040, SCREEN-041, SCREEN-042, SCREEN-043 |
| Last sync | 2026-08-31T11:40:26.815Z (session 1) |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 461 |
| 출력 루트 | docs/screen-design/klid-authoring-screens |

## ITEM 버전 표

| ITEM ID | type | title | version | stale | status |
|---|---|---|---|---|---|
| [[API-001]] | api_endpoint | GET /v1/users | 6 | false | UNCHANGED |
| [[API-002]] | api_endpoint | GET /v1/users/workers | 2 | false | UNCHANGED |
| [[API-003]] | api_endpoint | GET /v1/users/{userNo} | 5 | false | UNCHANGED |
| [[API-004]] | api_endpoint | PATCH /v1/users/{userNo} | 8 | false | UNCHANGED |
| [[API-005]] | api_endpoint | GET /v1/users/me | 5 | false | UNCHANGED |
| [[API-006]] | api_endpoint | GET /v1/me | 10 | false | UNCHANGED |
| [[API-007]] | api_endpoint | POST /v1/auth/role-claim | 14 | true | UNCHANGED |
| [[API-008]] | api_endpoint | GET /v1/reviews | 12 | false | UNCHANGED |
| [[API-009]] | api_endpoint | GET /v1/reviews/{videoId} | 9 | false | UNCHANGED |
| [[API-010]] | api_endpoint | GET /v1/reviews/{videoId}/frames | 4 | false | UNCHANGED |
| [[API-011]] | api_endpoint | GET /v1/reviews/{videoId}/issues | 4 | false | UNCHANGED |
| [[API-012]] | api_endpoint | POST /v1/reviews/{videoId}/submit | 6 | false | UNCHANGED |
| [[API-013]] | api_endpoint | POST /v1/reviews/{videoId}/start | 6 | false | UNCHANGED |
| [[API-014]] | api_endpoint | POST /v1/reviews/{videoId}/approve | 12 | false | UNCHANGED |
| [[API-015]] | api_endpoint | POST /v1/reviews/{videoId}/reject | 8 | false | UNCHANGED |
| [[API-016]] | api_endpoint | POST /v1/meta/{metaReviewSn}/approve | 3 | false | UNCHANGED |
| [[API-017]] | api_endpoint | POST /v1/meta/{metaReviewSn}/reject | 5 | false | UNCHANGED |
| [[API-018]] | api_endpoint | GET /v1/frames/{srcSn}/labels | 5 | false | UNCHANGED |
| [[API-019]] | api_endpoint | PUT /v1/frames/{srcSn}/labels | 8 | false | UNCHANGED |
| [[API-020]] | api_endpoint | POST /v1/frames/{srcSn}/sam2-track | 14 | false | UNCHANGED |
| [[API-021]] | api_endpoint | GET /v1/frames/{srcSn}/image | 9 | false | UNCHANGED |
| [[API-022]] | api_endpoint | GET /v1/labels/{lblSn}/attrs | 4 | false | UNCHANGED |
| [[API-023]] | api_endpoint | PUT /v1/labels/{lblSn}/attrs | 4 | false | UNCHANGED |
| [[API-024]] | api_endpoint | GET /v1/manage/labels | 6 | false | UNCHANGED |
| [[API-025]] | api_endpoint | POST /v1/manage/labels | 8 | false | UNCHANGED |
| [[API-026]] | api_endpoint | PUT /v1/manage/labels/{id} | 5 | false | UNCHANGED |
| [[API-027]] | api_endpoint | DELETE /v1/manage/labels/{id} | 4 | false | UNCHANGED |
| [[API-028]] | api_endpoint | GET /v1/manage/labels/{labelId}/attrs | 3 | false | UNCHANGED |
| [[API-029]] | api_endpoint | POST /v1/manage/labels/{labelId}/attrs | 4 | false | UNCHANGED |
| [[API-030]] | api_endpoint | PUT /v1/manage/labels/{labelId}/attrs/{attrId} | 4 | false | UNCHANGED |
| [[API-031]] | api_endpoint | DELETE /v1/manage/labels/{labelId}/attrs/{attrId} | 3 | false | UNCHANGED |
| [[API-032]] | api_endpoint | POST /v1/labels/{srcSn}/deident-report | 8 | false | UNCHANGED |
| [[API-034]] | api_endpoint | GET /v1/frames/{srcSn}/versions | 9 | false | UNCHANGED |
| [[API-035]] | api_endpoint | GET /v1/versions/{version}/diff | 11 | false | UNCHANGED |
| [[API-036]] | api_endpoint | POST /v1/versions/{version}/rollback | 11 | false | UNCHANGED |
| [[API-037]] | api_endpoint | GET /v1/manage/presets | 12 | false | UNCHANGED |
| [[API-038]] | api_endpoint | POST /v1/manage/presets | 14 | false | UNCHANGED |
| [[API-039]] | api_endpoint | PUT /v1/manage/presets/{id} | 13 | false | UNCHANGED |
| [[API-040]] | api_endpoint | DELETE /v1/manage/presets/{id} | 4 | false | UNCHANGED |
| [[API-041]] | api_endpoint | [폐기] POST /v1/manage/presets/{id}/clone | 5 | false | UNCHANGED |
| [[API-042]] | api_endpoint | GET /v1/videos | 8 | false | UNCHANGED |
| [[API-043]] | api_endpoint | GET /v1/videos/{rawSn} | 25 | false | UNCHANGED |
| [[API-044]] | api_endpoint | GET /v1/videos/{rawSn}/labels/auto | 9 | false | UNCHANGED |
| [[API-045]] | api_endpoint | GET /v1/videos/{rawSn}/auto-summary | 2 | false | UNCHANGED |
| [[API-046]] | api_endpoint | GET /v1/videos/{rawSn}/frames/{frameNo}/image | 7 | false | UNCHANGED |
| [[API-047]] | api_endpoint | POST /v1/videos/{rawSn}/markings | 12 | false | UNCHANGED |
| [[API-055]] | api_endpoint | GET /v1/stats/summary | 6 | false | UNCHANGED |
| [[API-056]] | api_endpoint | GET /v1/stats/worker | 7 | false | UNCHANGED |
| [[API-057]] | api_endpoint | GET /v1/stats/overall | 6 | false | UNCHANGED |
| [[API-058]] | api_endpoint | GET /v1/stats/report | 4 | false | UNCHANGED |
| [[API-059]] | api_endpoint | GET /v1/augments | 6 | true | UNCHANGED |
| [[API-060]] | api_endpoint | POST /v1/augments/request | 16 | false | UNCHANGED |
| [[API-061]] | api_endpoint | GET /v1/augments/{jobId}/result | 10 | true | UNCHANGED |
| [[API-062]] | api_endpoint | POST /v1/augments/{id}/accept | 7 | true | UNCHANGED |
| [[API-063]] | api_endpoint | POST /v1/augments/{id}/reject | 9 | true | UNCHANGED |
| [[API-065]] | api_endpoint | POST /v1/vlm/callback | 21 | false | UNCHANGED |
| [[API-066]] | api_endpoint | GET /v1/frames/{srcSn}/meta | 7 | false | UNCHANGED |
| [[API-067]] | api_endpoint | PUT /v1/frames/{srcSn}/meta | 8 | false | UNCHANGED |
| [[API-068]] | api_endpoint | GET /v1/manage/configs | 4 | false | UNCHANGED |
| [[API-069]] | api_endpoint | PUT /v1/manage/configs/{key} | 9 | false | UNCHANGED |
| [[API-070]] | api_endpoint | POST /v1/assignments | 8 | false | UNCHANGED |
| [[API-071]] | api_endpoint | PATCH /v1/assignments/{assignmentId} | 7 | false | UNCHANGED |
| [[API-072]] | api_endpoint | GET /v1/assignments | 10 | false | UNCHANGED |
| [[API-073]] | api_endpoint | GET /v1/tasks/board | 9 | false | UNCHANGED |
| [[API-074]] | api_endpoint | GET /v1/tasks/{rawSn}/summary | 7 | false | UNCHANGED |
| [[API-075]] | api_endpoint | GET /v1/tasks/{rawSn}/labels | 8 | false | UNCHANGED |
| [[API-076]] | api_endpoint | GET /v1/tasks/{rawSn}/meta | 9 | false | UNCHANGED |
| [[API-081]] | api_endpoint | GET /v1/portal/datamart/labels | 7 | false | UNCHANGED |
| [[API-082]] | api_endpoint | POST /v1/portal/user-labels | 9 | false | UNCHANGED |
| [[API-083]] | api_endpoint | GET /v1/portal/user-labels | 6 | false | UNCHANGED |
| [[API-084]] | api_endpoint | GET /v1/videos/{rawSn}/stream | 8 | false | UNCHANGED |
| [[API-090]] | api_endpoint | GET /v1/manage/health | 4 | false | UNCHANGED |
| [[API-091]] | api_endpoint | POST /v1/videos/{rawSn}/deident-report | 12 | false | UNCHANGED |
| [[API-092]] | api_endpoint | POST /v1/videos/{rawSn}/resolution | 10 | true | UNCHANGED |
| [[API-093]] | api_endpoint | POST /v1/frames/{srcSn}/sam2-segment | 14 | false | UNCHANGED |
| [[API-094]] | api_endpoint | POST /v1/deident-reports/{rprtSn}/resolve | 9 | false | UNCHANGED |
| [[API-095]] | api_endpoint | GET /v1/notices | 6 | false | UNCHANGED |
| [[API-096]] | api_endpoint | GET /v1/notices/{id} | 7 | false | UNCHANGED |
| [[API-097]] | api_endpoint | POST /v1/notices | 7 | false | UNCHANGED |
| [[API-098]] | api_endpoint | PUT /v1/notices/{id} | 7 | false | UNCHANGED |
| [[API-099]] | api_endpoint | DELETE /v1/notices/{id} | 6 | false | UNCHANGED |
| [[API-100]] | api_endpoint | POST /v1/notices/{id}/publish | 8 | false | UNCHANGED |
| [[API-101]] | api_endpoint | POST /v1/notices/{id}/unpublish | 8 | false | UNCHANGED |
| [[API-102]] | api_endpoint | POST /v1/videos/{rawSn}/issues | 14 | false | UNCHANGED |
| [[API-103]] | api_endpoint | GET /v1/videos/{rawSn}/issues | 11 | false | UNCHANGED |
| [[API-104]] | api_endpoint | POST /v1/issues/{issueSn}/comments | 14 | false | UNCHANGED |
| [[API-105]] | api_endpoint | POST /v1/issues/{issueSn}/resolve | 7 | false | UNCHANGED |
| [[API-106]] | api_endpoint | POST /v1/notices/{id}/attachments | 9 | false | UNCHANGED |
| [[API-107]] | api_endpoint | GET /v1/notices/{id}/attachments/{attachId}/download | 7 | false | UNCHANGED |
| [[API-108]] | api_endpoint | DELETE /v1/notices/{id}/attachments/{attachId} | 6 | false | UNCHANGED |
| [[API-109]] | api_endpoint | GET /v1/deident-reports | 5 | false | UNCHANGED |
| [[API-110]] | api_endpoint | GET /v1/portal/frames/{srcSn}/labels | 4 | false | UNCHANGED |
| [[API-111]] | api_endpoint | GET /v1/portal/frames/{srcSn}/image | 3 | false | UNCHANGED |
| [[API-112]] | api_endpoint | POST /v1/videos/{rawSn}/redeident | 5 | false | UNCHANGED |
| [[API-113]] | api_endpoint | POST /infer/yolo/predict | 3 | false | UNCHANGED |
| [[API-114]] | api_endpoint | GET /v1/videos/{rawSn}/stream-url | 2 | false | UNCHANGED |
| [[API-115]] | api_endpoint | GET /v1/portal/datamart/videos | 3 | false | UNCHANGED |
| [[API-116]] | api_endpoint | GET /v1/assignments/{assignmentId}/history | 8 | false | UNCHANGED |
| [[API-117]] | api_endpoint | GET /v1/event-types/labels | 5 | false | UNCHANGED |
| [[API-118]] | api_endpoint | GET /v1/system/scheduler/health | 2 | false | UNCHANGED |
| [[API-119]] | api_endpoint | POST /infer/yolo/track | 3 | false | UNCHANGED |
| [[API-120]] | api_endpoint | POST /infer/sam2/segment | 3 | false | UNCHANGED |
| [[API-121]] | api_endpoint | POST /infer/sam2/track | 3 | true | UNCHANGED |
| [[API-122]] | api_endpoint | POST /infer/vlm/verify-objects | 2 | false | UNCHANGED |
| [[API-123]] | api_endpoint | POST /v1/frames/{srcSn}/yolo-track | 13 | false | UNCHANGED |
| [[API-124]] | api_endpoint | POST /v1/frames/{srcSn}/autolabel | 10 | false | UNCHANGED |
| [[API-125]] | api_endpoint | POST /v1/videos/{rawSn}/tracks/merge | 2 | false | UNCHANGED |
| [[API-126]] | api_endpoint | DELETE /v1/videos/{rawSn}/tracks/{trackId} | 2 | false | UNCHANGED |
| [[API-127]] | api_endpoint | POST /v1/videos/{rawSn}/tracks/{trackId}/split | 2 | false | UNCHANGED |
| [[API-128]] | api_endpoint | GET /v1/frames/{srcSn}/description | 3 | false | UNCHANGED |
| [[API-129]] | api_endpoint | PUT /v1/frames/{srcSn}/description | 5 | false | UNCHANGED |
| [[API-132]] | api_endpoint | GET /v1/videos/{rawSn}/event-annotation | 5 | false | UNCHANGED |
| [[API-133]] | api_endpoint | POST /v1/videos/{rawSn}/event-annotation/approve | 4 | false | UNCHANGED |
| [[API-134]] | api_endpoint | PUT /v1/videos/{rawSn}/event-annotation | 5 | false | UNCHANGED |
| [[API-135]] | api_endpoint | POST /v1/videos/{rawSn}/event-annotation/reject | 4 | false | UNCHANGED |
| [[API-136]] | api_endpoint | GET /v1/tasks/board/summary | 4 | false | UNCHANGED |
| [[API-137]] | api_endpoint | GET /v1/tasks/board/event-types | 6 | false | UNCHANGED |
| [[API-138]] | api_endpoint | GET /v1/reviews/summary | 4 | false | UNCHANGED |
| [[API-139]] | api_endpoint | POST /v1/portal/uploads/images | 3 | false | UNCHANGED |
| [[API-140]] | api_endpoint | GET /v1/portal/uploads/{uldSn} | 5 | false | UNCHANGED |
| [[API-141]] | api_endpoint | GET /health | 2 | false | UNCHANGED |
| [[API-142]] | api_endpoint | GET /v1/portal/uploads | 4 | false | UNCHANGED |
| [[API-143]] | api_endpoint | POST /v1/dev/batch/scan | 3 | false | UNCHANGED |
| [[API-144]] | api_endpoint | POST /v1/dev/batch/trigger | 3 | false | UNCHANGED |
| [[API-145]] | api_endpoint | POST /v1/dev/batch/trigger/next | 3 | false | UNCHANGED |
| [[API-146]] | api_endpoint | GET /v1/dev/batch/pending | 5 | false | UNCHANGED |
| [[API-147]] | api_endpoint | GET /v1/portal/uploads/{uldSn}/frames | 2 | false | UNCHANGED |
| [[API-148]] | api_endpoint | GET /v1/dev/dataset-video-meta/shooting-env-correction-targets | 3 | false | UNCHANGED |
| [[API-149]] | api_endpoint | GET /v1/portal/uploads/frames/{uldFrmeSn}/image | 3 | false | UNCHANGED |
| [[API-150]] | api_endpoint | POST /v1/dev/dataset-video-meta/shooting-env-corrections | 3 | false | UNCHANGED |
| [[API-151]] | api_endpoint | DELETE /v1/portal/uploads/{uldSn} | 2 | false | UNCHANGED |
| [[API-152]] | api_endpoint | POST /v1/dev/upload | 8 | false | UNCHANGED |
| [[API-153]] | api_endpoint | POST /v1/dev/tokens | 5 | false | UNCHANGED |
| [[API-154]] | api_endpoint | PUT /v1/portal/uploads/frames/{uldFrmeSn}/labels | 4 | false | UNCHANGED |
| [[API-155]] | api_endpoint | GET /v1/portal/uploads/frames/{uldFrmeSn}/labels | 2 | false | UNCHANGED |
| [[API-156]] | api_endpoint | OPTIONS /v1/uploads | 3 | false | UNCHANGED |
| [[API-157]] | api_endpoint | GET /v1/portal/uploads/{uldSn}/export | 3 | false | UNCHANGED |
| [[API-158]] | api_endpoint | POST /v1/uploads | 5 | true | UNCHANGED |
| [[API-159]] | api_endpoint | GET /v1/portal/uploads/{uldSn}/file | 4 | false | UNCHANGED |
| [[API-160]] | api_endpoint | HEAD /v1/uploads/{uploadId} | 4 | false | UNCHANGED |
| [[API-161]] | api_endpoint | OPTIONS /v1/portal/uploads/tus | 3 | false | UNCHANGED |
| [[API-162]] | api_endpoint | PATCH /v1/uploads/{uploadId} | 6 | false | UNCHANGED |
| [[API-163]] | api_endpoint | POST /v1/portal/uploads/tus | 3 | false | UNCHANGED |
| [[API-164]] | api_endpoint | DELETE /v1/uploads/{uploadId} | 3 | false | UNCHANGED |
| [[API-165]] | api_endpoint | POST /v1/genai/callback | 7 | false | UNCHANGED |
| [[API-166]] | api_endpoint | HEAD /v1/portal/uploads/tus/{uldId} | 3 | false | UNCHANGED |
| [[API-167]] | api_endpoint | POST /v1/videos/{rawSn}/batch/retry | 10 | false | UNCHANGED |
| [[API-168]] | api_endpoint | GET /v1/videos/{rawSn}/environment-meta | 2 | false | UNCHANGED |
| [[API-169]] | api_endpoint | PATCH /v1/portal/uploads/tus/{uldId} | 6 | false | UNCHANGED |
| [[API-170]] | api_endpoint | PUT /v1/videos/{rawSn}/environment-meta | 3 | false | UNCHANGED |
| [[API-171]] | api_endpoint | DELETE /v1/portal/uploads/tus/{uldId} | 3 | false | UNCHANGED |
| [[API-172]] | api_endpoint | GET /v1/frames/{srcSn}/privacy-meta | 4 | false | UNCHANGED |
| [[API-173]] | api_endpoint | PUT /v1/frames/{srcSn}/privacy-meta | 6 | false | UNCHANGED |
| [[API-174]] | api_endpoint | PUT /v1/frames/privacy-meta | 6 | false | UNCHANGED |
| [[API-175]] | api_endpoint | GET /v1/frames/{srcSn}/deid-image | 2 | false | UNCHANGED |
| [[API-176]] | api_endpoint | GET /v1/frames/{srcSn}/label-history | 3 | false | UNCHANGED |
| [[API-177]] | api_endpoint | GET /v1/manage/labels/detect-candidates | 4 | false | UNCHANGED |
| [[API-178]] | api_endpoint | POST /v1/reviews/{videoId}/cancel-submit | 8 | false | UNCHANGED |
| [[API-179]] | api_endpoint | GET /v1/videos/{rawSn}/resolution | 9 | false | UNCHANGED |
| [[API-181]] | api_endpoint | GET /v1/event-types | 7 | false | UNCHANGED |
| [[API-182]] | api_endpoint | GET /v1/versions/{version}/diff-with-working | 4 | false | UNCHANGED |
| [[API-183]] | api_endpoint | GET /v1/videos/{rawSn}/privacy-meta | 1 | false | UNCHANGED |
| [[API-184]] | api_endpoint | PUT /v1/videos/{rawSn}/privacy-meta | 2 | false | UNCHANGED |
| [[API-185]] | api_endpoint | GET /v1/manage/event-types | 8 | false | UNCHANGED |
| [[API-186]] | api_endpoint | PATCH /v1/manage/event-types/{evntTypeCd} | 6 | false | UNCHANGED |
| [[API-187]] | api_endpoint | GET /v1/assignments/event-types | 3 | false | UNCHANGED |
| [[API-188]] | api_endpoint | GET /v1/augments/{id}/progress | 2 | true | UNCHANGED |
| [[API-189]] | api_endpoint | POST /v1/augments/{id}/cancel | 2 | true | UNCHANGED |
| [[API-190]] | api_endpoint | POST /v1/augments/{id}/restore | 6 | true | UNCHANGED |
| [[API-191]] | api_endpoint | POST /v1/control-ingests/{rcptnSn}/requeue | 2 | false | UNCHANGED |
| [[API-192]] | api_endpoint | POST /v1/control-ingests/requeue | 4 | false | UNCHANGED |
| [[API-193]] | api_endpoint | GET /v1/ai-defaults | 5 | false | UNCHANGED |
| [[API-194]] | api_endpoint | POST /v1/manage/admin-session | 9 | true | UNCHANGED |
| [[API-195]] | api_endpoint | GET /v1/videos/{rawSn}/versions/{version}/labels | 7 | false | UNCHANGED |
| [[API-196]] | api_endpoint | PUT /v1/videos/{rawSn}/labels | 9 | false | UNCHANGED |
| [[API-197]] | api_endpoint | GET /v1/videos/{rawSn}/versions | 5 | false | UNCHANGED |
| [[API-198]] | api_endpoint | POST /v1/videos/{rawSn}/batch/stages/{stage}/skip | 7 | false | UNCHANGED |
| [[API-199]] | api_endpoint | POST /v1/videos/batch/retry | 2 | false | UNCHANGED |
| [[API-200]] | api_endpoint | DELETE /v1/videos/{rawSn}/batch/stages/{stage}/skip | 4 | false | UNCHANGED |
| [[API-201]] | api_endpoint | POST /v1/videos/{rawSn}/batch/stages/{stage}/rerun | 8 | true | UNCHANGED |
| [[API-202]] | api_endpoint | GET /v1/deident-reports/{rprtSn}/deident-candidates | 2 | false | UNCHANGED |
| [[API-203]] | api_endpoint | 포털 사용자 작업 데이터 ZIP 다운로드 | 6 | false | UNCHANGED |
| [[API-204]] | api_endpoint | POST /v1/ai-requests/{requestId}/cancel | 1 | false | UNCHANGED |
| [[API-205]] | api_endpoint | 외부 산출물 폴더 검사 | 8 | true | UNCHANGED |
| [[API-206]] | api_endpoint | 외부 산출물 적재 | 12 | true | UNCHANGED |
| [[API-207]] | api_endpoint | 이관 이력 목록 조회 | 7 | false | UNCHANGED |
| [[API-208]] | api_endpoint | 이관 이력 상세 조회 | 5 | false | UNCHANGED |
| [[API-209]] | api_endpoint | 분류 대응 목록 조회 | 6 | true | UNCHANGED |
| [[API-210]] | api_endpoint | 분류 대응 확정 | 9 | true | UNCHANGED |
| [[API-211]] | api_endpoint | 분류 대응 해제 | 8 | false | UNCHANGED |
| [[API-212]] | api_endpoint | POST /v1/videos/batch/stages/{stage}/skip | 5 | true | UNCHANGED |
| [[API-213]] | api_endpoint | DELETE /v1/videos/batch/stages/{stage}/skip | 6 | true | UNCHANGED |
| [[API-214]] | api_endpoint | POST /v1/videos/batch/stages/{stage}/rerun | 4 | true | UNCHANGED |
| [[API-215]] | api_endpoint | 비식별 완료 기록 | 4 | true | UNCHANGED |
| [[API-216]] | api_endpoint | 마킹 산출물 폴더 검사 | 2 | false | UNCHANGED |
| [[API-217]] | api_endpoint | 마킹 산출물 일괄 적재 | 2 | false | UNCHANGED |
| [[API-218]] | api_endpoint | 일괄 적재 진행 조회 | 2 | false | UNCHANGED |
| [[API-219]] | api_endpoint | GET /v1/manage/verification-event-types | 3 | false | UNCHANGED |
| [[API-220]] | api_endpoint | PUT /v1/manage/verification-event-types/{vrfcEvntTypeCd}/questions | 3 | false | UNCHANGED |
| [[API-221]] | api_endpoint | 이관 대상 폴더 탐색 | 15 | true | UNCHANGED |
| [[API-222]] | api_endpoint | 이관 대상 영상 파일 탐색 | 10 | true | UNCHANGED |
| [[API-223]] | api_endpoint | PUT /v1/manage/admin-password — 관리자 패스워드 교체 | 5 | true | UNCHANGED |
| [[CONST-001]] | constant | COCO-17 키포인트 스켈레톤 상수 | 4 | false | UNCHANGED |
| [[CONST-002]] | constant | CocoClasses — COCO-80 검출 클래스 allowlist | 3 | true | UNCHANGED |
| [[DS-001]] | design_system | KRDS Public | 9 | false | UNCHANGED |
| [[NAV-001]] | navigation_tree | 저작도구 내부 메뉴 (INTERNAL) | 26 | true | UNCHANGED |
| [[NAV-002]] | navigation_tree | 포털 메뉴 (PORTAL) | 8 | true | UNCHANGED |
| [[ROLE-001]] | permission_role | 검수자 (REVIEWER) | 13 | true | UNCHANGED |
| [[ROLE-002]] | permission_role | 라벨링 작업자 (WORKER) | 9 | true | UNCHANGED |
| [[ROLE-003]] | permission_role | 포털 회원 (PORTAL_USER) | 10 | false | UNCHANGED |
| [[ROLE-004]] | permission_role | 관리자 (ADMIN) | 4 | true | UNCHANGED |
| [[SCREEN-001]] | screen_spec | 세션 인계 진입 화면 | 16 | false | UNCHANGED |
| [[SCREEN-002]] | screen_spec | 관리자 등록 화면 | 26 | true | UNCHANGED |
| [[SCREEN-003]] | screen_spec | 접근 거부 화면 | 14 | false | UNCHANGED |
| [[SCREEN-004]] | screen_spec | 개발용 로그인 화면 | 16 | true | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 라벨링 캔버스 화면 | 102 | true | UNCHANGED |
| [[SCREEN-006]] | screen_spec | 마킹 화면 | 49 | true | UNCHANGED |
| [[SCREEN-008]] | screen_spec | 영상 처리 현황 화면 | 47 | true | UNCHANGED |
| [[SCREEN-009]] | screen_spec | 영상 상세 화면 | 75 | true | UNCHANGED |
| [[SCREEN-010]] | screen_spec | 로드 버전 선택 | 37 | true | UNCHANGED |
| [[SCREEN-011]] | screen_spec | 대시보드 화면 | 21 | true | UNCHANGED |
| [[SCREEN-012]] | screen_spec | 작업 목록 화면 | 47 | true | UNCHANGED |
| [[SCREEN-018]] | screen_spec | 검수 목록 화면 | 29 | true | UNCHANGED |
| [[SCREEN-019]] | screen_spec | 검수 상세 화면 | 43 | true | UNCHANGED |
| [[SCREEN-020]] | screen_spec | 작업자 통계 화면 | 32 | true | UNCHANGED |
| [[SCREEN-021]] | screen_spec | 전체 구축 현황 화면 | 29 | false | UNCHANGED |
| [[SCREEN-022]] | screen_spec | 증강 요청 화면 | 46 | true | UNCHANGED |
| [[SCREEN-023]] | screen_spec | 증강 결과 화면 | 41 | true | UNCHANGED |
| [[SCREEN-024]] | screen_spec | 사용자 관리 화면 | 32 | true | UNCHANGED |
| [[SCREEN-025]] | screen_spec | 시스템 설정 화면 | 46 | true | UNCHANGED |
| [[SCREEN-026]] | screen_spec | 프리셋 관리 화면 | 35 | true | UNCHANGED |
| [[SCREEN-027]] | screen_spec | 파일 업로드 | 42 | true | UNCHANGED |
| [[SCREEN-028]] | screen_spec | 포털 홈 화면 | 26 | true | UNCHANGED |
| [[SCREEN-029]] | screen_spec | 포털 라벨링 화면 | 41 | true | UNCHANGED |
| [[SCREEN-030]] | screen_spec | 공지 목록 화면 | 26 | true | UNCHANGED |
| [[SCREEN-031]] | screen_spec | 공지 상세 화면 | 33 | true | UNCHANGED |
| [[SCREEN-032]] | screen_spec | 비식별 신고 관리 화면 | 23 | true | UNCHANGED |
| [[SCREEN-033]] | screen_spec | 포털 업로드 화면 | 23 | true | UNCHANGED |
| [[SCREEN-034]] | screen_spec | 포털 업로드 라벨링 화면 | 26 | true | UNCHANGED |
| [[SCREEN-035]] | screen_spec | 라벨 관리 화면 | 19 | true | UNCHANGED |
| [[SCREEN-036]] | screen_spec | 공지 작성 화면 | 8 | true | UNCHANGED |
| [[SCREEN-037]] | screen_spec | 공지 수정 화면 | 8 | true | UNCHANGED |
| [[SCREEN-038]] | screen_spec | 이벤트유형 관리 화면 | 14 | true | UNCHANGED |
| [[SCREEN-039]] | screen_spec | 산출물 가져오기 | 33 | true | UNCHANGED |
| [[SCREEN-040]] | screen_spec | 관리자 페이지 진입 화면 | 9 | false | UNCHANGED |
| [[SCREEN-041]] | screen_spec | 관리자 패스워드 교체 | 10 | false | UNCHANGED |
| [[SCREEN-042]] | screen_spec | 연동 서버 주소 관리 화면 | 8 | false | UNCHANGED |
| [[SCREEN-043]] | screen_spec | 위험 작업 화면 | 7 | false | UNCHANGED |
| [[SD-001]] | screen_design | SCREEN-018 검수 목록 화면 | 5 | true | UNCHANGED |
| [[SD-002]] | screen_design | SCREEN-005 라벨링 캔버스 화면 | 17 | true | UNCHANGED |
| [[SD-003]] | screen_design | SCREEN-012 작업 목록 화면 | 8 | true | UNCHANGED |
| [[SD-004]] | screen_design | SCREEN-009 영상 상세 화면 | 19 | true | UNCHANGED |
| [[SD-005]] | screen_design | SCREEN-019 검수 상세 화면 | 7 | true | UNCHANGED |
| [[SD-006]] | screen_design | SCREEN-026 프리셋 관리 화면 | 5 | true | UNCHANGED |
| [[SD-007]] | screen_design | SCREEN-030 공지 목록 화면 | 6 | true | UNCHANGED |
| [[SD-008]] | screen_design | SCREEN-036 공지 작성 화면 | 4 | true | UNCHANGED |
| [[SD-009]] | screen_design | SCREEN-024 사용자 관리 화면 | 12 | true | UNCHANGED |
| [[SD-010]] | screen_design | SCREEN-031 공지 상세 화면 | 10 | true | UNCHANGED |
| [[SD-011]] | screen_design | SCREEN-037 공지 수정 화면 | 6 | false | UNCHANGED |
| [[SD-012]] | screen_design | SCREEN-006 마킹 화면 | 11 | true | UNCHANGED |
| [[SD-013]] | screen_design | SCREEN-008 영상 처리 현황 화면 | 7 | true | UNCHANGED |
| [[SD-014]] | screen_design | SCREEN-011 대시보드 화면 | 5 | false | UNCHANGED |
| [[SD-015]] | screen_design | SCREEN-025 시스템 설정 화면 | 9 | true | UNCHANGED |
| [[SD-016]] | screen_design | 공통 — 전역 레이아웃(헤더·좌측 주 메뉴) | 6 | false | UNCHANGED |
| [[SD-017]] | screen_design | SCREEN-001 세션 인계 진입 화면 | 3 | false | UNCHANGED |
| [[SD-018]] | screen_design | SCREEN-002 관리자 등록 화면 | 8 | false | UNCHANGED |
| [[SD-019]] | screen_design | SCREEN-003 접근 거부 화면 | 3 | true | UNCHANGED |
| [[SD-020]] | screen_design | SCREEN-004 개발용 로그인 화면 | 4 | true | UNCHANGED |
| [[SD-021]] | screen_design | SCREEN-032 비식별 신고 관리 화면 | 4 | true | UNCHANGED |
| [[SD-022]] | screen_design | SCREEN-035 라벨 관리 화면 | 4 | true | UNCHANGED |
| [[SD-023]] | screen_design | SCREEN-038 이벤트유형 관리 화면 | 5 | true | UNCHANGED |
| [[SD-024]] | screen_design | SCREEN-028 포털 홈 화면 | 6 | true | UNCHANGED |
| [[SD-025]] | screen_design | SCREEN-029 포털 라벨링 화면 | 4 | true | UNCHANGED |
| [[SD-026]] | screen_design | SCREEN-033 포털 업로드 화면 | 7 | true | UNCHANGED |
| [[SD-027]] | screen_design | SCREEN-034 포털 업로드 라벨링 화면 | 9 | true | UNCHANGED |
| [[SD-028]] | screen_design | SCREEN-022 증강 요청 화면 | 4 | true | UNCHANGED |
| [[SD-029]] | screen_design | SCREEN-023 증강 결과 화면 | 5 | true | UNCHANGED |
| [[SD-030]] | screen_design | SCREEN-020 작업자 통계 화면 | 5 | false | UNCHANGED |
| [[SD-031]] | screen_design | SCREEN-021 전체 구축 현황 화면 | 2 | true | UNCHANGED |
| [[SD-032]] | screen_design | SCREEN-010 로드 버전 선택 | 2 | true | UNCHANGED |
| [[SD-033]] | screen_design | SCREEN-027 영상 업로드 | 11 | true | UNCHANGED |
| [[SD-034]] | screen_design | SCREEN-040 관리자 페이지 진입 화면 | 6 | false | UNCHANGED |
| [[SD-035]] | screen_design | SCREEN-043 위험 작업 화면 | 4 | true | UNCHANGED |
| [[SD-036]] | screen_design | SCREEN-042 연동 서버 주소 관리 화면 | 4 | true | UNCHANGED |
| [[SD-037]] | screen_design | SCREEN-041 관리자 패스워드 교체 화면 | 5 | true | UNCHANGED |
| [[SHELL-001]] | app_shell | 저작도구 내부 채널 셸 | 11 | true | UNCHANGED |
| [[SHELL-002]] | app_shell | 포털 채널 셸 | 5 | true | UNCHANGED |
| [[UC-001]] | use_case | 증강 영상 생성 요청 | 14 | true | UNCHANGED |
| [[UC-002]] | use_case | 증강 결과 수신·등록 | 16 | true | UNCHANGED |
| [[UC-003]] | use_case | 해상도 변경 수행 | 15 | true | UNCHANGED |
| [[UC-004]] | use_case | 객체 자동 추적 | 16 | true | UNCHANGED |
| [[UC-005]] | use_case | 객체 외곽 경계 자동 밀착 | 11 | true | UNCHANGED |
| [[UC-006]] | use_case | 라벨링 정밀도 조절 | 10 | true | UNCHANGED |
| [[UC-007]] | use_case | 라벨 버전 저장·이력 추적 | 14 | true | UNCHANGED |
| [[UC-008]] | use_case | 버전 비교·복구 | 15 | true | UNCHANGED |
| [[UC-009]] | use_case | 검수 완료·수정 통지 | 21 | true | UNCHANGED |
| [[UC-010]] | use_case | 증강 영상 활용 여부 검수 | 14 | true | UNCHANGED |
| [[UC-011]] | use_case | 비식별 처리 요청 | 13 | true | UNCHANGED |
| [[UC-013]] | use_case | 비식별 옵션 설정 | 11 | true | UNCHANGED |
| [[UC-016]] | use_case | 비식별 처리 상태·이력 확인 | 25 | true | UNCHANGED |
| [[UC-018]] | use_case | 영상 적재 (관제 인입 테이블 직접 INSERT → 폴링 적재) | 19 | true | UNCHANGED |
| [[UC-019]] | use_case | 이벤트 마킹 (자동/수동) | 24 | true | UNCHANGED |
| [[UC-021]] | use_case | 라벨 편집·임시저장 | 21 | true | UNCHANGED |
| [[UC-022]] | use_case | VLM 시계열 메타 검토 | 23 | true | UNCHANGED |
| [[UC-023]] | use_case | 검수 승인·반려 | 27 | true | UNCHANGED |
| [[UC-024]] | use_case | 포털 라벨 작업 (조회·수정·다운로드) | 21 | false | UNCHANGED |
| [[UC-027]] | use_case | 포털 자산 업로드·수동 라벨링 | 17 | true | UNCHANGED |
| [[UC-028]] | use_case | 라벨 클래스·속성 정의 관리 | 7 | true | UNCHANGED |
| [[UC-029]] | use_case | 작업 목록 조회·필터링·배정 | 12 | true | UNCHANGED |
| [[UC-030]] | use_case | 사용자 계정·역할 관리 | 14 | true | UNCHANGED |
| [[UC-031]] | use_case | 시스템 운영 설정 관리 | 14 | true | UNCHANGED |
| [[UC-032]] | use_case | 라벨 프리셋 CRUD 관리 | 13 | true | UNCHANGED |
| [[UC-033]] | use_case | 전체 구축 현황 조회 — 검수완료 기준과 전체 기준 병기 | 4 | true | UNCHANGED |
| [[UC-034]] | use_case | 온디맨드 AI 자동 추적 | 6 | true | UNCHANGED |
| [[UC-035]] | use_case | 외부 산출물 가져오기 | 16 | true | UNCHANGED |
| [[UC-036]] | use_case | 이관 이력 조회 | 4 | true | UNCHANGED |
| [[UC-037]] | use_case | 마킹이 끝난 영상 일괄 올리기 | 4 | true | UNCHANGED |
| [[UI-001]] | ui_component | action: Button | 3 | false | UNCHANGED |
| [[UI-002]] | ui_component | input: Input | 6 | false | UNCHANGED |
| [[UI-003]] | ui_component | input: Select | 5 | false | UNCHANGED |
| [[UI-004]] | ui_component | overlay: Modal | 4 | false | UNCHANGED |
| [[UI-005]] | ui_component | overlay: ConfirmDialog | 4 | false | UNCHANGED |
| [[UI-006]] | ui_component | overlay: Drawer | 4 | false | UNCHANGED |
| [[UI-007]] | ui_component | data: DataTable | 7 | false | UNCHANGED |
| [[UI-008]] | ui_component | navigation: Pagination | 6 | false | UNCHANGED |
| [[UI-009]] | ui_component | navigation: Tabs | 3 | false | UNCHANGED |
| [[UI-010]] | ui_component | display: KpiCard | 4 | false | UNCHANGED |
| [[UI-011]] | ui_component | layout: Card | 4 | false | UNCHANGED |
| [[UI-012]] | ui_component | layout: PageHeader | 3 | false | UNCHANGED |
| [[UI-013]] | ui_component | navigation: Breadcrumb | 3 | false | UNCHANGED |
| [[UI-014]] | ui_component | display: StatusBadge | 5 | false | UNCHANGED |
| [[UI-015]] | ui_component | [폐기] display: PrivacyBadge | 4 | false | UNCHANGED |
| [[UI-016]] | ui_component | display: EventTypeBadge | 5 | false | UNCHANGED |
| [[UI-017]] | ui_component | display: StageBadge | 8 | false | UNCHANGED |
| [[UI-018]] | ui_component | display: BatchStageIndicator | 10 | false | UNCHANGED |
| [[UI-019]] | ui_component | feedback: ProgressBar | 4 | false | UNCHANGED |
| [[UI-020]] | ui_component | feedback: EmptyState | 5 | false | UNCHANGED |
| [[UI-021]] | ui_component | feedback: ErrorState | 4 | false | UNCHANGED |
| [[UI-022]] | ui_component | feedback: LoadingOverlay | 5 | false | UNCHANGED |
| [[UI-023]] | ui_component | feedback: Toast | 3 | false | UNCHANGED |
| [[UI-024]] | ui_component | input: Checkbox | 6 | false | UNCHANGED |
| [[UI-025]] | ui_component | input: Radio | 3 | false | UNCHANGED |
| [[UI-026]] | ui_component | input: RadioGroup | 6 | false | UNCHANGED |
| [[UI-027]] | ui_component | input: Textarea | 4 | false | UNCHANGED |
| [[UI-028]] | ui_component | input: DatePicker | 4 | false | UNCHANGED |
| [[UI-029]] | ui_component | input: DateRangePicker | 5 | false | UNCHANGED |
| [[UI-030]] | ui_component | [폐기] input: FormField | 6 | false | UNCHANGED |
| [[UI-031]] | ui_component | overlay: Popover | 3 | false | UNCHANGED |
| [[UI-032]] | ui_component | feedback: Spinner | 3 | false | UNCHANGED |
| [[UI-033]] | ui_component | feedback: Skeleton | 3 | false | UNCHANGED |
| [[UI-034]] | ui_component | layout: AppLayout | 4 | false | UNCHANGED |
| [[UI-035]] | ui_component | navigation: Gnb | 6 | false | UNCHANGED |
| [[UI-036]] | ui_component | navigation: Lnb | 3 | false | UNCHANGED |
| [[UI-037]] | ui_component | layout: PortalLayout | 4 | false | UNCHANGED |
| [[UI-038]] | ui_component | layout: Footer | 3 | false | UNCHANGED |
| [[UI-039]] | ui_component | data: SimplePieChart | 3 | false | UNCHANGED |
| [[UI-040]] | ui_component | data: SimpleBarChart | 6 | false | UNCHANGED |
| [[UI-041]] | ui_component | display: AuthImage | 3 | false | UNCHANGED |
| [[UI-042]] | ui_component | display: VideoPlayer | 4 | false | UNCHANGED |
| [[UI-043]] | ui_component | action: MarkingToolbar | 4 | false | UNCHANGED |
| [[UI-044]] | ui_component | display: MarkingTimeline | 4 | false | UNCHANGED |
| [[UI-045]] | ui_component | data: MarkingPanel | 5 | false | UNCHANGED |
| [[UI-046]] | ui_component | display: CanvasShell | 7 | false | UNCHANGED |
| [[UI-047]] | ui_component | action: ToolBar | 6 | false | UNCHANGED |
| [[UI-048]] | ui_component | overlay: LabelPickerModal | 7 | false | UNCHANGED |
| [[UI-049]] | ui_component | data: ObjectClassTree | 7 | false | UNCHANGED |
| [[UI-050]] | ui_component | input: ObjectAttributePanel | 6 | false | UNCHANGED |
| [[UI-051]] | ui_component | navigation: FrameFilmstrip | 4 | false | UNCHANGED |
| [[UI-052]] | ui_component | navigation: FrameNavigator | 7 | false | UNCHANGED |
| [[UI-053]] | ui_component | action: SaveCommitButton | 8 | false | UNCHANGED |
| [[UI-054]] | ui_component | action: UndoRedoToolbar | 5 | false | UNCHANGED |
| [[UI-055]] | ui_component | layout: LabelHeader | 11 | false | UNCHANGED |
| [[UI-056]] | ui_component | display: TimeseriesSidePanel | 6 | false | UNCHANGED |
| [[UI-057]] | ui_component | action: DeidentReportButton | 6 | false | UNCHANGED |
| [[UI-058]] | ui_component | display: ReviewLabelCanvas | 4 | false | UNCHANGED |
| [[UI-059]] | ui_component | [폐기] action: ReviewActionBar | 5 | false | UNCHANGED |
| [[UI-060]] | ui_component | layout: ReviewHeader | 5 | false | UNCHANGED |
| [[UI-061]] | ui_component | [폐기] data: IssueSidebar | 5 | false | UNCHANGED |
| [[UI-062]] | ui_component | overlay: RejectModal | 4 | false | UNCHANGED |
| [[UI-063]] | ui_component | navigation: ReviewFrameTimeline | 4 | false | UNCHANGED |
| [[UI-064]] | ui_component | [폐기] data: ObjectListPanel | 5 | false | UNCHANGED |
| [[UI-065]] | ui_component | input: ReviewMemoPanel | 4 | false | UNCHANGED |
| [[UI-066]] | ui_component | data: VersionList | 5 | false | UNCHANGED |
| [[UI-067]] | ui_component | display: DiffViewer | 4 | false | UNCHANGED |
| [[UI-068]] | ui_component | [폐기] input: VersionPicker | 6 | false | UNCHANGED |
| [[UI-069]] | ui_component | overlay: RollbackConfirmModal | 4 | false | UNCHANGED |
| [[UI-070]] | ui_component | layout: HistoryPanel | 4 | false | UNCHANGED |
| [[UI-071]] | ui_component | input: ProcessKindCard | 4 | false | UNCHANGED |
| [[UI-072]] | ui_component | display: JobCard | 4 | false | UNCHANGED |
| [[UI-073]] | ui_component | action: DecisionCard | 4 | false | UNCHANGED |
| [[UI-074]] | ui_component | [폐기] input: TimeseriesSidePanel | 5 | false | UNCHANGED |
| [[UI-075]] | ui_component | [폐기] display: StateChangeTimeline | 5 | false | UNCHANGED |
| [[UI-076]] | ui_component | display: ConfidenceDistributionChart | 4 | false | UNCHANGED |
| [[UI-077]] | ui_component | data: MyTasksTable | 4 | false | UNCHANGED |
| [[UI-078]] | ui_component | display: EventDistributionGrid | 4 | false | UNCHANGED |
| [[UI-079]] | ui_component | display: NoticeCard | 4 | false | UNCHANGED |
| [[UI-080]] | ui_component | data: WorkerStatsTable | 5 | false | UNCHANGED |
| [[UI-081]] | ui_component | data: DailyCompletionChart | 4 | false | UNCHANGED |
| [[UI-082]] | ui_component | data: EventTypePieChart | 4 | false | UNCHANGED |
| [[UI-083]] | ui_component | overlay: AssignModal | 5 | false | UNCHANGED |
| [[UI-084]] | ui_component | overlay: HistoryDrawer | 5 | false | UNCHANGED |
| [[UI-085]] | ui_component | input: TaskFilters | 4 | false | UNCHANGED |
| [[UI-086]] | ui_component | input: YoloConfigCard | 4 | false | UNCHANGED |
| [[UI-087]] | ui_component | input: BatchConfigCard | 5 | false | UNCHANGED |
| [[UI-088]] | ui_component | input: PrecisionConfigCard | 4 | false | UNCHANGED |
| [[UI-089]] | ui_component | display: HealthStatusList | 5 | false | UNCHANGED |
| [[UI-090]] | ui_component | action: DangerActions | 6 | false | UNCHANGED |
| [[UI-091]] | ui_component | overlay: PresetEditModal | 6 | false | UNCHANGED |
| [[UI-092]] | ui_component | display: PresetCodeChip | 4 | false | UNCHANGED |
| [[UI-093]] | ui_component | [폐기] display: BatchStageSteps | 6 | false | UNCHANGED |
| [[UI-094]] | ui_component | action: VideoActions | 5 | false | UNCHANGED |
| [[UI-095]] | ui_component | input: VideoFilters | 5 | false | UNCHANGED |
| [[UI-096]] | ui_component | input: AugmentTypeCheckbox | 5 | false | UNCHANGED |
| [[UI-097]] | ui_component | data: IssueThreadPanel (이슈 스레드 패널) | 5 | false | UNCHANGED |
| [[UI-098]] | ui_component | input: FileInput | 2 | false | UNCHANGED |
| [[UI-099]] | ui_component | input: Field | 2 | false | UNCHANGED |
| [[UI-100]] | ui_component | input: DeidentConfigCard | 2 | false | UNCHANGED |
| [[UI-101]] | ui_component | display: RecheckBadge | 1 | false | UNCHANGED |
| [[UI-102]] | ui_component | display: ReadOnlyBadge | 1 | false | UNCHANGED |
| [[UI-103]] | ui_component | feedback: AlertBanner | 1 | false | UNCHANGED |
| [[UI-104]] | ui_component | display: CountChip | 1 | false | UNCHANGED |
| [[UI-105]] | ui_component | display: DerivativeBadge | 1 | false | UNCHANGED |
| [[UI-106]] | ui_component | data: KeyValueGrid | 1 | false | UNCHANGED |
| [[UI-107]] | ui_component | input: EventAnnotationPanel | 1 | false | UNCHANGED |
| [[UI-108]] | ui_component | input: PrivacyMetaPanel | 1 | false | UNCHANGED |
| [[UI-109]] | ui_component | display: Avatar | 1 | false | UNCHANGED |
| [[UI-110]] | ui_component | display: RoleBadge | 2 | false | UNCHANGED |
| [[UI-111]] | ui_component | display: Badge | 3 | false | UNCHANGED |
| [[UI-112]] | ui_component | display: AttachmentList | 3 | false | UNCHANGED |
| [[UI-113]] | ui_component | display: PresetLabelOverflowChip | 1 | false | UNCHANGED |
| [[UI-114]] | ui_component | input: PresetLabelPicker | 1 | false | UNCHANGED |
| [[UI-115]] | ui_component | display: FieldCounter | 1 | false | UNCHANGED |
| [[UI-116]] | ui_component | display: LockIconBadge | 1 | false | UNCHANGED |
| [[UI-117]] | ui_component | feedback: DevOnlyNotice | 1 | false | UNCHANGED |
| [[UI-118]] | ui_component | display: ChannelChip | 1 | false | UNCHANGED |
| [[UI-119]] | ui_component | action: IconButton | 1 | false | UNCHANGED |
| [[UI-120]] | ui_component | display: Tooltip | 1 | false | UNCHANGED |
| [[UI-121]] | ui_component | display: DeidentStageBadge | 1 | false | UNCHANGED |
| [[UI-122]] | ui_component | data: DeidentArtifactCandidateList | 1 | false | UNCHANGED |
| [[UI-123]] | ui_component | input: ToggleSwitch | 1 | false | UNCHANGED |
| [[UI-124]] | ui_component | input: ColorSwatchField | 1 | false | UNCHANGED |
| [[UI-125]] | ui_component | input: DynamicList | 1 | false | UNCHANGED |
| [[UI-126]] | ui_component | display: DisplayNameSourceChip | 1 | false | UNCHANGED |
| [[UI-127]] | ui_component | display: DetectClassMapChip | 1 | false | UNCHANGED |
| [[UI-128]] | ui_component | data: DatamartVideoCard | 1 | false | UNCHANGED |
| [[UI-129]] | ui_component | layout: PortalHero | 1 | false | UNCHANGED |
| [[UI-130]] | ui_component | display: LabelOriginChip | 1 | false | UNCHANGED |
| [[UI-131]] | ui_component | input: UploadDropzone | 1 | false | UNCHANGED |
| [[UI-132]] | ui_component | display: AssetTypeChip | 1 | false | UNCHANGED |
| [[UI-133]] | ui_component | input: TargetResolutionSelect | 2 | false | UNCHANGED |
| [[UI-134]] | ui_component | display: SelectionSummary | 1 | false | UNCHANGED |
| [[UI-135]] | ui_component | layout: StickyActionBar | 1 | false | UNCHANGED |
| [[UI-136]] | ui_component | layout: StepSectionHeader | 1 | false | UNCHANGED |
| [[UI-137]] | ui_component | feedback: InlineResultSummary | 1 | false | UNCHANGED |
| [[UI-138]] | ui_component | data: FramePairGrid | 1 | false | UNCHANGED |
| [[UI-139]] | ui_component | overlay: SideBySideCompare | 1 | false | UNCHANGED |
| [[UI-140]] | ui_component | display: AugmentPromptSummary | 1 | false | UNCHANGED |
| [[UI-141]] | ui_component | feedback: AugmentProgressPanel | 1 | false | UNCHANGED |
| [[UI-142]] | ui_component | display: WorkerNameSub | 1 | false | UNCHANGED |
| [[UI-143]] | ui_component | display: RateGaugeCard | 1 | false | UNCHANGED |
| [[UI-144]] | ui_component | display: ProcessingStackBar | 1 | false | UNCHANGED |
