# Version Master — 저작도구 화면 (-) — 화면 키트

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | - 저작도구 화면 |
| 다운로드 화면 | SCREEN-005, SCREEN-006, SCREEN-008, SCREEN-009, SCREEN-010, SCREEN-011, SCREEN-012, SCREEN-018, SCREEN-019, SCREEN-024, SCREEN-025, SCREEN-026, SCREEN-030, SCREEN-031, SCREEN-036, SCREEN-037 |
| Last sync | 2026-08-13T05:07:48.568Z (session 7) |
| Mode | SYNC — NEW 58 / CHANGED 4 / UNCHANGED 220 |
| 출력 루트 | /Users/ck/orca/workspaces/klid-label/r12-screen-design/docs/screen-design/klid-authoring-screens |

## ITEM 버전 표

| ITEM ID | type | title | version | stale | status |
|---|---|---|---|---|---|
| AC-004 | acceptance | 객체 자동 추적(SAM2) 수행 | 5 | false | UNCHANGED |
| AC-005 | acceptance | 객체 외곽 경계 자동 밀착 | 5 | false | UNCHANGED |
| AC-006 | acceptance | 라벨링 정밀도(폴리곤 단순화) 조절 | 5 | false | UNCHANGED |
| AC-007 | acceptance | 라벨 버전 스냅샷 저장·해시 식별 | 5 | false | UNCHANGED |
| AC-008 | acceptance | 버전 diff 비교·롤백 복구 | 8 | false | UNCHANGED |
| AC-009 | acceptance | 검수 완료 후 수정 통지(TASK_MODIFIED) | 6 | false | NEW |
| AC-011 | acceptance | 비식별 처리 요청·결과 저장 | 6 | false | UNCHANGED |
| AC-013 | acceptance | 비식별 옵션 설정 | 4 | false | UNCHANGED |
| AC-016 | acceptance | 비식별 처리 상태·이력 화면 확인 | 5 | false | UNCHANGED |
| AC-017 | acceptance | 실영상 라벨링·메타 가공 | 3 | false | UNCHANGED |
| AC-019 | acceptance | 개인정보 비식별화 처리·검수·누락 신고 | 4 | false | UNCHANGED |
| AC-020 | acceptance | 다양한 환경·산불 유형 학습데이터 제작 | 3 | false | UNCHANGED |
| AC-021 | acceptance | 생성된 영상 라벨링으로 학습데이터셋 편입 | 4 | false | UNCHANGED |
| AC-022 | acceptance | 학습데이터셋 자동·수동 검수 | 4 | false | NEW |
| AC-023 | acceptance | 이미지 학습데이터 가공(추출·라벨링·가명·검수) | 4 | false | UNCHANGED |
| AC-024 | acceptance | 영상 학습데이터 가공(라벨링·메타·VLM 시계열 메타 검수) | 4 | false | UNCHANGED |
| AC-027 | acceptance | 자동/수동 마킹 완료·잔여 배치 트리거 | 3 | false | UNCHANGED |
| AC-028 | acceptance | 마킹에서 도출된 VLM 위탁 입력(frame_policy·event_type) | 3 | false | UNCHANGED |
| API-001 | api_endpoint | GET /v1/users | 2 | false | NEW |
| API-002 | api_endpoint | GET /v1/users/workers | 2 | false | NEW |
| API-003 | api_endpoint | GET /v1/users/{userNo} | 3 | false | NEW |
| API-004 | api_endpoint | PATCH /v1/users/{userNo} | 3 | false | NEW |
| API-008 | api_endpoint | GET /v1/reviews | 6 | false | NEW |
| API-009 | api_endpoint | GET /v1/reviews/{videoId} | 5 | false | NEW |
| API-010 | api_endpoint | GET /v1/reviews/{videoId}/frames | 3 | false | NEW |
| API-011 | api_endpoint | GET /v1/reviews/{videoId}/issues | 3 | false | NEW |
| API-012 | api_endpoint | POST /v1/reviews/{videoId}/submit | 4 | false | UNCHANGED |
| API-013 | api_endpoint | POST /v1/reviews/{videoId}/start | 4 | false | NEW |
| API-014 | api_endpoint | POST /v1/reviews/{videoId}/approve | 8 | false | NEW |
| API-015 | api_endpoint | POST /v1/reviews/{videoId}/reject | 4 | false | NEW |
| API-018 | api_endpoint | GET /v1/frames/{srcSn}/labels | 4 | false | UNCHANGED |
| API-019 | api_endpoint | PUT /v1/frames/{srcSn}/labels | 7 | false | UNCHANGED |
| API-020 | api_endpoint | POST /v1/frames/{srcSn}/sam2-track | 6 | false | UNCHANGED |
| API-021 | api_endpoint | GET /v1/frames/{srcSn}/image | 4 | false | UNCHANGED |
| API-022 | api_endpoint | GET /v1/labels/{lblSn}/attrs | 4 | false | UNCHANGED |
| API-023 | api_endpoint | PUT /v1/labels/{lblSn}/attrs | 3 | false | UNCHANGED |
| API-024 | api_endpoint | GET /v1/manage/labels | 4 | false | UNCHANGED |
| API-032 | api_endpoint | POST /v1/labels/{srcSn}/deident-report | 6 | false | UNCHANGED |
| API-034 | api_endpoint | GET /v1/frames/{srcSn}/versions | 7 | false | UNCHANGED |
| API-035 | api_endpoint | GET /v1/versions/{version}/diff | 8 | false | UNCHANGED |
| API-036 | api_endpoint | POST /v1/versions/{version}/rollback | 8 | false | UNCHANGED |
| API-037 | api_endpoint | GET /v1/manage/presets | 3 | false | NEW |
| API-038 | api_endpoint | POST /v1/manage/presets | 5 | false | NEW |
| API-039 | api_endpoint | PUT /v1/manage/presets/{id} | 4 | false | NEW |
| API-040 | api_endpoint | DELETE /v1/manage/presets/{id} | 2 | false | NEW |
| API-041 | api_endpoint | POST /v1/manage/presets/{id}/clone | 3 | false | NEW |
| API-042 | api_endpoint | GET /v1/videos | 4 | false | UNCHANGED |
| API-043 | api_endpoint | GET /v1/videos/{rawSn} | 10 | false | UNCHANGED |
| API-044 | api_endpoint | GET /v1/videos/{rawSn}/labels/auto | 5 | false | UNCHANGED |
| API-047 | api_endpoint | POST /v1/videos/{rawSn}/markings | 4 | false | UNCHANGED |
| API-055 | api_endpoint | GET /v1/stats/summary | 2 | false | UNCHANGED |
| API-066 | api_endpoint | GET /v1/frames/{srcSn}/meta | 3 | false | UNCHANGED |
| API-067 | api_endpoint | PUT /v1/frames/{srcSn}/meta | 3 | false | UNCHANGED |
| API-068 | api_endpoint | GET /v1/manage/configs | 3 | false | UNCHANGED |
| API-069 | api_endpoint | PUT /v1/manage/configs/{key} | 5 | false | UNCHANGED |
| API-070 | api_endpoint | POST /v1/assignments | 5 | false | UNCHANGED |
| API-071 | api_endpoint | PATCH /v1/assignments/{assignmentId} | 3 | false | UNCHANGED |
| API-072 | api_endpoint | GET /v1/assignments | 4 | false | UNCHANGED |
| API-073 | api_endpoint | GET /v1/tasks/board | 4 | false | NEW |
| API-084 | api_endpoint | GET /v1/videos/{rawSn}/stream | 3 | false | UNCHANGED |
| API-090 | api_endpoint | GET /v1/manage/health | 3 | false | UNCHANGED |
| API-091 | api_endpoint | POST /v1/videos/{rawSn}/deident-report | 5 | false | UNCHANGED |
| API-093 | api_endpoint | POST /v1/frames/{srcSn}/sam2-segment | 7 | false | UNCHANGED |
| API-095 | api_endpoint | GET /v1/notices | 3 | false | NEW |
| API-096 | api_endpoint | GET /v1/notices/{id} | 3 | false | NEW |
| API-097 | api_endpoint | POST /v1/notices | 3 | false | NEW |
| API-098 | api_endpoint | PUT /v1/notices/{id} | 3 | false | NEW |
| API-099 | api_endpoint | DELETE /v1/notices/{id} | 3 | false | NEW |
| API-100 | api_endpoint | POST /v1/notices/{id}/publish | 4 | false | NEW |
| API-101 | api_endpoint | POST /v1/notices/{id}/unpublish | 4 | false | NEW |
| API-102 | api_endpoint | POST /v1/videos/{rawSn}/issues | 7 | false | UNCHANGED |
| API-103 | api_endpoint | GET /v1/videos/{rawSn}/issues | 5 | false | UNCHANGED |
| API-104 | api_endpoint | POST /v1/issues/{issueSn}/comments | 5 | false | UNCHANGED |
| API-105 | api_endpoint | POST /v1/issues/{issueSn}/resolve | 3 | false | UNCHANGED |
| API-106 | api_endpoint | POST /v1/notices/{id}/attachments | 6 | false | NEW |
| API-107 | api_endpoint | GET /v1/notices/{id}/attachments/{attachId}/download | 3 | false | NEW |
| API-108 | api_endpoint | DELETE /v1/notices/{id}/attachments/{attachId} | 3 | false | NEW |
| API-114 | api_endpoint | GET /v1/videos/{rawSn}/stream-url | 1 | false | UNCHANGED |
| API-116 | api_endpoint | GET /v1/assignments/{assignmentId}/history | 2 | false | NEW |
| API-117 | api_endpoint | GET /v1/event-types/labels | 1 | false | NEW |
| API-123 | api_endpoint | POST /v1/frames/{srcSn}/yolo-track | 3 | false | UNCHANGED |
| API-124 | api_endpoint | POST /v1/frames/{srcSn}/autolabel | 5 | false | UNCHANGED |
| API-125 | api_endpoint | POST /v1/videos/{rawSn}/tracks/merge | 2 | false | UNCHANGED |
| API-126 | api_endpoint | DELETE /v1/videos/{rawSn}/tracks/{trackId} | 2 | false | UNCHANGED |
| API-127 | api_endpoint | POST /v1/videos/{rawSn}/tracks/{trackId}/split | 2 | false | UNCHANGED |
| API-128 | api_endpoint | GET /v1/frames/{srcSn}/description | 3 | false | UNCHANGED |
| API-129 | api_endpoint | PUT /v1/frames/{srcSn}/description | 5 | false | UNCHANGED |
| API-132 | api_endpoint | GET /v1/videos/{rawSn}/event-annotation | 1 | false | UNCHANGED |
| API-133 | api_endpoint | POST /v1/videos/{rawSn}/event-annotation/approve | 2 | false | UNCHANGED |
| API-134 | api_endpoint | PUT /v1/videos/{rawSn}/event-annotation | 3 | false | UNCHANGED |
| API-135 | api_endpoint | POST /v1/videos/{rawSn}/event-annotation/reject | 2 | false | UNCHANGED |
| API-136 | api_endpoint | GET /v1/tasks/board/summary | 1 | false | NEW |
| API-137 | api_endpoint | GET /v1/tasks/board/event-types | 3 | false | NEW |
| API-138 | api_endpoint | GET /v1/reviews/summary | 2 | false | NEW |
| API-167 | api_endpoint | POST /v1/videos/{rawSn}/batch/retry | 9 | false | UNCHANGED |
| API-168 | api_endpoint | GET /v1/videos/{rawSn}/environment-meta | 2 | false | UNCHANGED |
| API-170 | api_endpoint | PUT /v1/videos/{rawSn}/environment-meta | 3 | false | UNCHANGED |
| API-172 | api_endpoint | GET /v1/frames/{srcSn}/privacy-meta | 3 | false | UNCHANGED |
| API-173 | api_endpoint | PUT /v1/frames/{srcSn}/privacy-meta | 5 | false | UNCHANGED |
| API-177 | api_endpoint | GET /v1/manage/labels/detect-candidates | 3 | false | UNCHANGED |
| API-178 | api_endpoint | POST /v1/reviews/{videoId}/cancel-submit | 4 | false | UNCHANGED |
| API-181 | api_endpoint | GET /v1/event-types | 3 | false | UNCHANGED |
| API-182 | api_endpoint | GET /v1/versions/{version}/diff-with-working | 2 | true | UNCHANGED |
| API-183 | api_endpoint | GET /v1/videos/{rawSn}/privacy-meta | 1 | false | UNCHANGED |
| API-184 | api_endpoint | PUT /v1/videos/{rawSn}/privacy-meta | 2 | false | UNCHANGED |
| API-187 | api_endpoint | GET /v1/assignments/event-types | 1 | false | NEW |
| API-194 | api_endpoint | POST /v1/manage/admin-session | 2 | false | UNCHANGED |
| API-195 | api_endpoint | GET /v1/videos/{rawSn}/versions/{version}/labels | 6 | false | UNCHANGED |
| API-196 | api_endpoint | PUT /v1/videos/{rawSn}/labels | 5 | false | UNCHANGED |
| API-197 | api_endpoint | GET /v1/videos/{rawSn}/versions | 3 | true | UNCHANGED |
| API-198 | api_endpoint | POST /v1/videos/{rawSn}/batch/stages/{stage}/skip | 4 | false | UNCHANGED |
| API-199 | api_endpoint | POST /v1/videos/batch/retry | 2 | false | UNCHANGED |
| API-200 | api_endpoint | DELETE /v1/videos/{rawSn}/batch/stages/{stage}/skip | 2 | false | UNCHANGED |
| API-201 | api_endpoint | POST /v1/videos/{rawSn}/batch/stages/{stage}/rerun | 3 | false | UNCHANGED |
| DS-001 | design_system | KRDS Public | 8 | false | UNCHANGED |
| NAV-001 | navigation_tree | 저작도구 내부 메뉴 (INTERNAL) | 10 | true | UNCHANGED |
| ROLE-001 | permission_role | 검수자 (REVIEWER) | 7 | true | UNCHANGED |
| ROLE-002 | permission_role | 라벨링 작업자 (WORKER) | 5 | true | UNCHANGED |
| SCREEN-005 | screen_spec | 라벨링 캔버스 화면 | 63 | true | UNCHANGED |
| SCREEN-006 | screen_spec | 마킹 화면 | 35 | false | CHANGED |
| SCREEN-008 | screen_spec | 영상 처리 현황 화면 | 32 | false | UNCHANGED |
| SCREEN-009 | screen_spec | 영상 상세 화면 | 44 | false | CHANGED |
| SCREEN-010 | screen_spec | 로드 버전 선택 | 33 | true | UNCHANGED |
| SCREEN-011 | screen_spec | 대시보드 화면 | 16 | false | UNCHANGED |
| SCREEN-012 | screen_spec | 작업 목록 화면 | 32 | false | NEW |
| SCREEN-018 | screen_spec | 검수 목록 화면 | 23 | false | NEW |
| SCREEN-019 | screen_spec | 검수 상세 화면 | 26 | true | NEW |
| SCREEN-024 | screen_spec | 사용자 관리 화면 | 20 | false | NEW |
| SCREEN-025 | screen_spec | 시스템 설정 화면 | 24 | false | UNCHANGED |
| SCREEN-026 | screen_spec | 프리셋 관리 화면 | 20 | true | NEW |
| SCREEN-030 | screen_spec | 공지 목록 화면 | 20 | false | NEW |
| SCREEN-031 | screen_spec | 공지 상세 화면 | 22 | false | NEW |
| SCREEN-036 | screen_spec | 공지 작성 화면 | 7 | false | NEW |
| SCREEN-037 | screen_spec | 공지 수정 화면 | 7 | false | NEW |
| SD-001 | screen_design | SCREEN-018 검수 목록 화면 | 3 | false | NEW |
| SD-002 | screen_design | SCREEN-005 라벨링 캔버스 화면 | 7 | true | UNCHANGED |
| SD-003 | screen_design | SCREEN-012 작업 목록 화면 | 3 | false | NEW |
| SD-004 | screen_design | SCREEN-009 영상 상세 화면 | 10 | true | CHANGED |
| SD-005 | screen_design | SCREEN-019 검수 상세 화면 | 3 | false | NEW |
| SD-006 | screen_design | SCREEN-026 프리셋 관리 화면 | 2 | false | NEW |
| SD-007 | screen_design | SCREEN-030 공지 목록 화면 | 2 | false | NEW |
| SD-008 | screen_design | SCREEN-036 공지 작성 화면 | 2 | false | NEW |
| SD-009 | screen_design | SCREEN-024 사용자 관리 화면 | 2 | false | NEW |
| SD-010 | screen_design | SCREEN-031 공지 상세 화면 | 3 | false | NEW |
| SD-011 | screen_design | SCREEN-037 공지 수정 화면 | 2 | false | NEW |
| SD-012 | screen_design | SCREEN-006 마킹 화면 | 5 | false | CHANGED |
| SD-013 | screen_design | SCREEN-008 영상 처리 현황 화면 | 3 | false | UNCHANGED |
| SD-014 | screen_design | SCREEN-011 대시보드 화면 | 3 | false | UNCHANGED |
| SD-015 | screen_design | SCREEN-025 시스템 설정 화면 | 3 | false | UNCHANGED |
| SHELL-001 | app_shell | 저작도구 내부 채널 셸 | 6 | true | UNCHANGED |
| UC-004 | use_case | 객체 자동 추적 | 10 | true | UNCHANGED |
| UC-005 | use_case | 객체 외곽 경계 자동 밀착 | 7 | true | UNCHANGED |
| UC-006 | use_case | 라벨링 정밀도 조절 | 6 | true | UNCHANGED |
| UC-007 | use_case | 라벨 버전 저장·이력 추적 | 8 | true | UNCHANGED |
| UC-008 | use_case | 버전 비교·복구 | 8 | true | UNCHANGED |
| UC-009 | use_case | 검수 완료·수정 통지 | 13 | true | NEW |
| UC-011 | use_case | 비식별 처리 요청 | 8 | true | UNCHANGED |
| UC-013 | use_case | 비식별 옵션 설정 | 6 | true | UNCHANGED |
| UC-016 | use_case | 비식별 처리 상태·이력 확인 | 14 | true | UNCHANGED |
| UC-019 | use_case | 이벤트 마킹 (자동/수동) | 12 | true | UNCHANGED |
| UC-021 | use_case | 라벨 편집·임시저장 | 11 | true | UNCHANGED |
| UC-022 | use_case | VLM 시계열 메타 검토 | 13 | true | UNCHANGED |
| UC-023 | use_case | 검수 승인·반려 | 14 | true | NEW |
| UC-029 | use_case | 작업 목록 조회·필터링·배정 | 4 | true | NEW |
| UC-030 | use_case | 사용자 계정·역할 관리 | 3 | true | NEW |
| UC-031 | use_case | 시스템 운영 설정 관리 | 3 | true | UNCHANGED |
| UC-032 | use_case | 라벨 프리셋 CRUD 관리 | 3 | true | NEW |
| UI-001 | ui_component | action: Button | 3 | false | UNCHANGED |
| UI-002 | ui_component | input: Input | 6 | false | UNCHANGED |
| UI-003 | ui_component | input: Select | 5 | false | UNCHANGED |
| UI-004 | ui_component | overlay: Modal | 4 | false | UNCHANGED |
| UI-005 | ui_component | overlay: ConfirmDialog | 4 | false | UNCHANGED |
| UI-006 | ui_component | overlay: Drawer | 4 | false | UNCHANGED |
| UI-007 | ui_component | data: DataTable | 7 | false | UNCHANGED |
| UI-008 | ui_component | navigation: Pagination | 6 | false | UNCHANGED |
| UI-009 | ui_component | navigation: Tabs | 3 | false | UNCHANGED |
| UI-010 | ui_component | display: KpiCard | 4 | false | UNCHANGED |
| UI-011 | ui_component | layout: Card | 4 | false | UNCHANGED |
| UI-012 | ui_component | layout: PageHeader | 3 | false | UNCHANGED |
| UI-013 | ui_component | navigation: Breadcrumb | 3 | false | UNCHANGED |
| UI-014 | ui_component | display: StatusBadge | 5 | false | UNCHANGED |
| UI-015 | ui_component | [폐기] display: PrivacyBadge | 4 | false | UNCHANGED |
| UI-016 | ui_component | display: EventTypeBadge | 5 | false | UNCHANGED |
| UI-017 | ui_component | display: StageBadge | 7 | false | UNCHANGED |
| UI-018 | ui_component | display: BatchStageIndicator | 7 | false | UNCHANGED |
| UI-019 | ui_component | feedback: ProgressBar | 4 | false | UNCHANGED |
| UI-020 | ui_component | feedback: EmptyState | 5 | false | UNCHANGED |
| UI-021 | ui_component | feedback: ErrorState | 4 | false | UNCHANGED |
| UI-022 | ui_component | feedback: LoadingOverlay | 5 | false | UNCHANGED |
| UI-023 | ui_component | feedback: Toast | 3 | false | UNCHANGED |
| UI-024 | ui_component | input: Checkbox | 6 | false | UNCHANGED |
| UI-025 | ui_component | input: Radio | 3 | false | UNCHANGED |
| UI-026 | ui_component | input: RadioGroup | 5 | false | UNCHANGED |
| UI-027 | ui_component | input: Textarea | 4 | false | UNCHANGED |
| UI-028 | ui_component | input: DatePicker | 4 | false | UNCHANGED |
| UI-029 | ui_component | input: DateRangePicker | 5 | false | UNCHANGED |
| UI-030 | ui_component | [폐기] input: FormField | 6 | false | UNCHANGED |
| UI-031 | ui_component | overlay: Popover | 3 | false | UNCHANGED |
| UI-032 | ui_component | feedback: Spinner | 3 | false | UNCHANGED |
| UI-033 | ui_component | feedback: Skeleton | 3 | false | UNCHANGED |
| UI-034 | ui_component | layout: AppLayout | 4 | false | UNCHANGED |
| UI-035 | ui_component | navigation: Gnb | 6 | false | UNCHANGED |
| UI-036 | ui_component | navigation: Lnb | 3 | false | UNCHANGED |
| UI-037 | ui_component | layout: PortalLayout | 4 | false | UNCHANGED |
| UI-038 | ui_component | layout: Footer | 3 | false | UNCHANGED |
| UI-039 | ui_component | data: SimplePieChart | 3 | false | UNCHANGED |
| UI-040 | ui_component | data: SimpleBarChart | 6 | false | UNCHANGED |
| UI-041 | ui_component | display: AuthImage | 3 | false | UNCHANGED |
| UI-042 | ui_component | display: VideoPlayer | 4 | false | UNCHANGED |
| UI-043 | ui_component | action: MarkingToolbar | 4 | false | UNCHANGED |
| UI-044 | ui_component | display: MarkingTimeline | 4 | false | UNCHANGED |
| UI-045 | ui_component | data: MarkingPanel | 5 | false | UNCHANGED |
| UI-046 | ui_component | display: CanvasShell | 6 | false | UNCHANGED |
| UI-047 | ui_component | action: ToolBar | 5 | false | UNCHANGED |
| UI-048 | ui_component | overlay: LabelPickerModal | 6 | false | UNCHANGED |
| UI-049 | ui_component | data: ObjectClassTree | 7 | false | UNCHANGED |
| UI-050 | ui_component | input: ObjectAttributePanel | 6 | false | UNCHANGED |
| UI-051 | ui_component | navigation: FrameFilmstrip | 4 | false | UNCHANGED |
| UI-052 | ui_component | navigation: FrameNavigator | 6 | false | UNCHANGED |
| UI-053 | ui_component | action: SaveCommitButton | 8 | false | UNCHANGED |
| UI-054 | ui_component | action: UndoRedoToolbar | 4 | false | UNCHANGED |
| UI-055 | ui_component | layout: LabelHeader | 11 | false | UNCHANGED |
| UI-056 | ui_component | display: TimeseriesSidePanel | 5 | false | UNCHANGED |
| UI-057 | ui_component | action: DeidentReportButton | 6 | false | UNCHANGED |
| UI-058 | ui_component | display: ReviewLabelCanvas | 4 | false | UNCHANGED |
| UI-059 | ui_component | [폐기] action: ReviewActionBar | 5 | false | UNCHANGED |
| UI-060 | ui_component | layout: ReviewHeader | 5 | false | UNCHANGED |
| UI-061 | ui_component | [폐기] data: IssueSidebar | 5 | false | UNCHANGED |
| UI-062 | ui_component | overlay: RejectModal | 4 | false | UNCHANGED |
| UI-063 | ui_component | navigation: ReviewFrameTimeline | 4 | false | UNCHANGED |
| UI-064 | ui_component | [폐기] data: ObjectListPanel | 5 | false | UNCHANGED |
| UI-065 | ui_component | input: ReviewMemoPanel | 4 | false | UNCHANGED |
| UI-066 | ui_component | data: VersionList | 5 | false | UNCHANGED |
| UI-067 | ui_component | display: DiffViewer | 4 | false | UNCHANGED |
| UI-068 | ui_component | [폐기] input: VersionPicker | 6 | false | UNCHANGED |
| UI-069 | ui_component | overlay: RollbackConfirmModal | 4 | false | UNCHANGED |
| UI-070 | ui_component | layout: HistoryPanel | 4 | false | UNCHANGED |
| UI-071 | ui_component | input: ProcessKindCard | 4 | false | UNCHANGED |
| UI-072 | ui_component | display: JobCard | 4 | false | UNCHANGED |
| UI-073 | ui_component | action: DecisionCard | 4 | false | UNCHANGED |
| UI-074 | ui_component | [폐기] input: TimeseriesSidePanel | 5 | false | UNCHANGED |
| UI-075 | ui_component | [폐기] display: StateChangeTimeline | 5 | false | UNCHANGED |
| UI-076 | ui_component | display: ConfidenceDistributionChart | 4 | false | UNCHANGED |
| UI-077 | ui_component | data: MyTasksTable | 4 | false | UNCHANGED |
| UI-078 | ui_component | display: EventDistributionGrid | 4 | false | UNCHANGED |
| UI-079 | ui_component | display: NoticeCard | 4 | false | UNCHANGED |
| UI-080 | ui_component | data: WorkerStatsTable | 5 | false | UNCHANGED |
| UI-081 | ui_component | data: DailyCompletionChart | 4 | false | UNCHANGED |
| UI-082 | ui_component | data: EventTypePieChart | 4 | false | UNCHANGED |
| UI-083 | ui_component | overlay: AssignModal | 5 | false | UNCHANGED |
| UI-084 | ui_component | overlay: HistoryDrawer | 5 | false | UNCHANGED |
| UI-085 | ui_component | input: TaskFilters | 4 | false | UNCHANGED |
| UI-086 | ui_component | input: YoloConfigCard | 4 | false | UNCHANGED |
| UI-087 | ui_component | input: BatchConfigCard | 4 | false | UNCHANGED |
| UI-088 | ui_component | input: PrecisionConfigCard | 4 | false | UNCHANGED |
| UI-089 | ui_component | display: HealthStatusList | 5 | false | UNCHANGED |
| UI-090 | ui_component | action: DangerActions | 4 | false | UNCHANGED |
| UI-091 | ui_component | overlay: PresetEditModal | 6 | false | UNCHANGED |
| UI-092 | ui_component | display: PresetCodeChip | 4 | false | UNCHANGED |
| UI-093 | ui_component | [폐기] display: BatchStageSteps | 6 | false | UNCHANGED |
| UI-094 | ui_component | action: VideoActions | 4 | false | UNCHANGED |
| UI-095 | ui_component | input: VideoFilters | 5 | false | UNCHANGED |
| UI-096 | ui_component | input: AugmentTypeCheckbox | 5 | false | UNCHANGED |
| UI-097 | ui_component | data: IssueThreadPanel (이슈 스레드 패널) | 5 | false | UNCHANGED |
| UI-098 | ui_component | input: FileInput | 2 | false | UNCHANGED |
| UI-099 | ui_component | input: Field | 2 | false | UNCHANGED |
| UI-100 | ui_component | input: DeidentConfigCard | 2 | false | UNCHANGED |
| UI-101 | ui_component | display: RecheckBadge | 1 | false | UNCHANGED |
| UI-102 | ui_component | display: ReadOnlyBadge | 1 | false | UNCHANGED |
| UI-103 | ui_component | feedback: AlertBanner | 1 | false | UNCHANGED |
| UI-104 | ui_component | display: CountChip | 1 | false | UNCHANGED |
| UI-105 | ui_component | display: DerivativeBadge | 1 | false | UNCHANGED |
| UI-106 | ui_component | data: KeyValueGrid | 1 | false | UNCHANGED |
| UI-107 | ui_component | input: EventAnnotationPanel | 1 | false | UNCHANGED |
| UI-108 | ui_component | input: PrivacyMetaPanel | 1 | false | UNCHANGED |
| UI-109 | ui_component | display: Avatar | 1 | false | UNCHANGED |
| UI-110 | ui_component | display: RoleBadge | 1 | false | UNCHANGED |
| UI-111 | ui_component | display: Badge | 1 | false | UNCHANGED |
| UI-112 | ui_component | display: AttachmentList | 1 | false | UNCHANGED |
| UI-113 | ui_component | display: PresetLabelOverflowChip | 1 | false | UNCHANGED |
| UI-114 | ui_component | input: PresetLabelPicker | 1 | false | UNCHANGED |
| UI-115 | ui_component | display: FieldCounter | 1 | false | UNCHANGED |
