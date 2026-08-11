# Version Master — 사용자·권한 (DOMAIN-001) — 화면 키트

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-001 사용자·권한 |
| 다운로드 화면 | SCREEN-001, SCREEN-002, SCREEN-003, SCREEN-004, SCREEN-024 |
| Last sync | 2026-08-11T07:42:26.579Z (session 2) |
| Mode | SYNC — NEW 7 / CHANGED 0 / UNCHANGED 126 |
| 출력 루트 | docs/screen-design/사용자권한-DOMAIN-001 |

## ITEM 버전 표

| ITEM ID | type | title | version | stale | status |
|---|---|---|---|---|---|
| API-001 | api_endpoint | GET /v1/users | 2 | false | UNCHANGED |
| API-002 | api_endpoint | GET /v1/users/workers | 2 | false | UNCHANGED |
| API-003 | api_endpoint | GET /v1/users/{userNo} | 3 | false | UNCHANGED |
| API-004 | api_endpoint | PATCH /v1/users/{userNo} | 3 | false | UNCHANGED |
| API-005 | api_endpoint | GET /v1/users/me | 4 | false | UNCHANGED |
| API-006 | api_endpoint | GET /v1/me | 5 | false | UNCHANGED |
| API-007 | api_endpoint | POST /v1/auth/role-claim | 4 | false | UNCHANGED |
| API-153 | api_endpoint | POST /v1/dev/tokens | 2 | false | UNCHANGED |
| DS-001 | design_system | KRDS Public | 8 | false | UNCHANGED |
| NAV-001 | navigation_tree | 저작도구 내부 메뉴 (INTERNAL) | 10 | true | UNCHANGED |
| ROLE-001 | permission_role | 검수자 (REVIEWER) | 7 | true | UNCHANGED |
| SCREEN-001 | screen_spec | 세션 인계 진입 화면 | 12 | false | UNCHANGED |
| SCREEN-002 | screen_spec | 역할 클레임 화면 | 11 | false | UNCHANGED |
| SCREEN-003 | screen_spec | 접근 거부 화면 | 9 | false | UNCHANGED |
| SCREEN-004 | screen_spec | 개발용 로그인 화면 | 7 | false | UNCHANGED |
| SCREEN-024 | screen_spec | 사용자 관리 화면 | 19 | false | UNCHANGED |
| SHELL-001 | app_shell | 저작도구 내부 채널 셸 | 6 | true | UNCHANGED |
| UC-030 | use_case | 사용자 계정·역할 관리 | 3 | true | UNCHANGED |
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
| UI-018 | ui_component | display: BatchStageIndicator | 6 | false | UNCHANGED |
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
| UI-109 | ui_component | display: Avatar | 1 | false | NEW |
| UI-110 | ui_component | display: RoleBadge | 1 | false | NEW |
| UI-111 | ui_component | display: Badge | 1 | false | NEW |
| UI-112 | ui_component | display: AttachmentList | 1 | false | NEW |
| UI-113 | ui_component | display: PresetLabelOverflowChip | 1 | false | NEW |
| UI-114 | ui_component | input: PresetLabelPicker | 1 | false | NEW |
| UI-115 | ui_component | display: FieldCounter | 1 | false | NEW |
