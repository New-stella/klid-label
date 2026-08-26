# Version Master — 사용자·권한 (DOMAIN-001) — 화면 키트

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-001 사용자·권한 |
| 다운로드 화면 | SCREEN-001, SCREEN-002, SCREEN-003, SCREEN-004, SCREEN-024 |
| Last sync | 2026-08-26T05:08:26.420Z (session 13) |
| Mode | INITIAL — NEW 167 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | /Users/ck/orca/workspaces/klid-label/portal/docs/screen-design/사용자권한-DOMAIN-001 |

## ITEM 버전 표

| ITEM ID | type | title | version | stale | status |
|---|---|---|---|---|---|
| [[API-001]] | api_endpoint | GET /v1/users | 5 | false | NEW |
| [[API-002]] | api_endpoint | GET /v1/users/workers | 2 | false | NEW |
| [[API-003]] | api_endpoint | GET /v1/users/{userNo} | 4 | false | NEW |
| [[API-004]] | api_endpoint | PATCH /v1/users/{userNo} | 5 | false | NEW |
| [[API-005]] | api_endpoint | GET /v1/users/me | 5 | false | NEW |
| [[API-006]] | api_endpoint | GET /v1/me | 9 | false | NEW |
| [[API-007]] | api_endpoint | POST /v1/auth/role-claim | 7 | false | NEW |
| [[API-153]] | api_endpoint | POST /v1/dev/tokens | 4 | false | NEW |
| [[DS-001]] | design_system | KRDS Public | 8 | false | NEW |
| [[NAV-001]] | navigation_tree | 저작도구 내부 메뉴 (INTERNAL) | 18 | true | NEW |
| [[ROLE-001]] | permission_role | 검수자 (REVIEWER) | 10 | true | NEW |
| [[SCREEN-001]] | screen_spec | 세션 인계 진입 화면 | 13 | false | NEW |
| [[SCREEN-002]] | screen_spec | 역할 클레임 화면 | 17 | false | NEW |
| [[SCREEN-003]] | screen_spec | 접근 거부 화면 | 10 | false | NEW |
| [[SCREEN-004]] | screen_spec | 개발용 로그인 화면 | 11 | false | NEW |
| [[SCREEN-024]] | screen_spec | 사용자 관리 화면 | 24 | true | NEW |
| [[SD-009]] | screen_design | SCREEN-024 사용자 관리 화면 | 7 | true | NEW |
| [[SD-017]] | screen_design | SCREEN-001 세션 인계 진입 화면 | 2 | false | NEW |
| [[SD-018]] | screen_design | SCREEN-002 역할 클레임 화면 | 5 | false | NEW |
| [[SD-019]] | screen_design | SCREEN-003 접근 거부 화면 | 2 | false | NEW |
| [[SD-020]] | screen_design | SCREEN-004 개발용 로그인 화면 | 2 | true | NEW |
| [[SHELL-001]] | app_shell | 저작도구 내부 채널 셸 | 10 | true | NEW |
| [[UC-030]] | use_case | 사용자 계정·역할 관리 | 8 | false | NEW |
| [[UI-001]] | ui_component | action: Button | 3 | false | NEW |
| [[UI-002]] | ui_component | input: Input | 6 | false | NEW |
| [[UI-003]] | ui_component | input: Select | 5 | false | NEW |
| [[UI-004]] | ui_component | overlay: Modal | 4 | false | NEW |
| [[UI-005]] | ui_component | overlay: ConfirmDialog | 4 | false | NEW |
| [[UI-006]] | ui_component | overlay: Drawer | 4 | false | NEW |
| [[UI-007]] | ui_component | data: DataTable | 7 | false | NEW |
| [[UI-008]] | ui_component | navigation: Pagination | 6 | false | NEW |
| [[UI-009]] | ui_component | navigation: Tabs | 3 | false | NEW |
| [[UI-010]] | ui_component | display: KpiCard | 4 | false | NEW |
| [[UI-011]] | ui_component | layout: Card | 4 | false | NEW |
| [[UI-012]] | ui_component | layout: PageHeader | 3 | false | NEW |
| [[UI-013]] | ui_component | navigation: Breadcrumb | 3 | false | NEW |
| [[UI-014]] | ui_component | display: StatusBadge | 5 | false | NEW |
| [[UI-015]] | ui_component | [폐기] display: PrivacyBadge | 4 | false | NEW |
| [[UI-016]] | ui_component | display: EventTypeBadge | 5 | false | NEW |
| [[UI-017]] | ui_component | display: StageBadge | 8 | false | NEW |
| [[UI-018]] | ui_component | display: BatchStageIndicator | 9 | false | NEW |
| [[UI-019]] | ui_component | feedback: ProgressBar | 4 | false | NEW |
| [[UI-020]] | ui_component | feedback: EmptyState | 5 | false | NEW |
| [[UI-021]] | ui_component | feedback: ErrorState | 4 | false | NEW |
| [[UI-022]] | ui_component | feedback: LoadingOverlay | 5 | false | NEW |
| [[UI-023]] | ui_component | feedback: Toast | 3 | false | NEW |
| [[UI-024]] | ui_component | input: Checkbox | 6 | false | NEW |
| [[UI-025]] | ui_component | input: Radio | 3 | false | NEW |
| [[UI-026]] | ui_component | input: RadioGroup | 6 | false | NEW |
| [[UI-027]] | ui_component | input: Textarea | 4 | false | NEW |
| [[UI-028]] | ui_component | input: DatePicker | 4 | false | NEW |
| [[UI-029]] | ui_component | input: DateRangePicker | 5 | false | NEW |
| [[UI-030]] | ui_component | [폐기] input: FormField | 6 | false | NEW |
| [[UI-031]] | ui_component | overlay: Popover | 3 | false | NEW |
| [[UI-032]] | ui_component | feedback: Spinner | 3 | false | NEW |
| [[UI-033]] | ui_component | feedback: Skeleton | 3 | false | NEW |
| [[UI-034]] | ui_component | layout: AppLayout | 4 | false | NEW |
| [[UI-035]] | ui_component | navigation: Gnb | 6 | false | NEW |
| [[UI-036]] | ui_component | navigation: Lnb | 3 | false | NEW |
| [[UI-037]] | ui_component | layout: PortalLayout | 4 | false | NEW |
| [[UI-038]] | ui_component | layout: Footer | 3 | false | NEW |
| [[UI-039]] | ui_component | data: SimplePieChart | 3 | false | NEW |
| [[UI-040]] | ui_component | data: SimpleBarChart | 6 | false | NEW |
| [[UI-041]] | ui_component | display: AuthImage | 3 | false | NEW |
| [[UI-042]] | ui_component | display: VideoPlayer | 4 | false | NEW |
| [[UI-043]] | ui_component | action: MarkingToolbar | 4 | false | NEW |
| [[UI-044]] | ui_component | display: MarkingTimeline | 4 | false | NEW |
| [[UI-045]] | ui_component | data: MarkingPanel | 5 | false | NEW |
| [[UI-046]] | ui_component | display: CanvasShell | 7 | false | NEW |
| [[UI-047]] | ui_component | action: ToolBar | 6 | false | NEW |
| [[UI-048]] | ui_component | overlay: LabelPickerModal | 7 | false | NEW |
| [[UI-049]] | ui_component | data: ObjectClassTree | 7 | false | NEW |
| [[UI-050]] | ui_component | input: ObjectAttributePanel | 6 | false | NEW |
| [[UI-051]] | ui_component | navigation: FrameFilmstrip | 4 | false | NEW |
| [[UI-052]] | ui_component | navigation: FrameNavigator | 7 | false | NEW |
| [[UI-053]] | ui_component | action: SaveCommitButton | 8 | false | NEW |
| [[UI-054]] | ui_component | action: UndoRedoToolbar | 5 | false | NEW |
| [[UI-055]] | ui_component | layout: LabelHeader | 11 | false | NEW |
| [[UI-056]] | ui_component | display: TimeseriesSidePanel | 6 | false | NEW |
| [[UI-057]] | ui_component | action: DeidentReportButton | 6 | false | NEW |
| [[UI-058]] | ui_component | display: ReviewLabelCanvas | 4 | false | NEW |
| [[UI-059]] | ui_component | [폐기] action: ReviewActionBar | 5 | false | NEW |
| [[UI-060]] | ui_component | layout: ReviewHeader | 5 | false | NEW |
| [[UI-061]] | ui_component | [폐기] data: IssueSidebar | 5 | false | NEW |
| [[UI-062]] | ui_component | overlay: RejectModal | 4 | false | NEW |
| [[UI-063]] | ui_component | navigation: ReviewFrameTimeline | 4 | false | NEW |
| [[UI-064]] | ui_component | [폐기] data: ObjectListPanel | 5 | false | NEW |
| [[UI-065]] | ui_component | input: ReviewMemoPanel | 4 | false | NEW |
| [[UI-066]] | ui_component | data: VersionList | 5 | false | NEW |
| [[UI-067]] | ui_component | display: DiffViewer | 4 | false | NEW |
| [[UI-068]] | ui_component | [폐기] input: VersionPicker | 6 | false | NEW |
| [[UI-069]] | ui_component | overlay: RollbackConfirmModal | 4 | false | NEW |
| [[UI-070]] | ui_component | layout: HistoryPanel | 4 | false | NEW |
| [[UI-071]] | ui_component | input: ProcessKindCard | 4 | false | NEW |
| [[UI-072]] | ui_component | display: JobCard | 4 | false | NEW |
| [[UI-073]] | ui_component | action: DecisionCard | 4 | false | NEW |
| [[UI-074]] | ui_component | [폐기] input: TimeseriesSidePanel | 5 | false | NEW |
| [[UI-075]] | ui_component | [폐기] display: StateChangeTimeline | 5 | false | NEW |
| [[UI-076]] | ui_component | display: ConfidenceDistributionChart | 4 | false | NEW |
| [[UI-077]] | ui_component | data: MyTasksTable | 4 | false | NEW |
| [[UI-078]] | ui_component | display: EventDistributionGrid | 4 | false | NEW |
| [[UI-079]] | ui_component | display: NoticeCard | 4 | false | NEW |
| [[UI-080]] | ui_component | data: WorkerStatsTable | 5 | false | NEW |
| [[UI-081]] | ui_component | data: DailyCompletionChart | 4 | false | NEW |
| [[UI-082]] | ui_component | data: EventTypePieChart | 4 | false | NEW |
| [[UI-083]] | ui_component | overlay: AssignModal | 5 | false | NEW |
| [[UI-084]] | ui_component | overlay: HistoryDrawer | 5 | false | NEW |
| [[UI-085]] | ui_component | input: TaskFilters | 4 | false | NEW |
| [[UI-086]] | ui_component | input: YoloConfigCard | 4 | false | NEW |
| [[UI-087]] | ui_component | input: BatchConfigCard | 5 | false | NEW |
| [[UI-088]] | ui_component | input: PrecisionConfigCard | 4 | false | NEW |
| [[UI-089]] | ui_component | display: HealthStatusList | 5 | false | NEW |
| [[UI-090]] | ui_component | action: DangerActions | 4 | false | NEW |
| [[UI-091]] | ui_component | overlay: PresetEditModal | 6 | false | NEW |
| [[UI-092]] | ui_component | display: PresetCodeChip | 4 | false | NEW |
| [[UI-093]] | ui_component | [폐기] display: BatchStageSteps | 6 | false | NEW |
| [[UI-094]] | ui_component | action: VideoActions | 4 | false | NEW |
| [[UI-095]] | ui_component | input: VideoFilters | 5 | false | NEW |
| [[UI-096]] | ui_component | input: AugmentTypeCheckbox | 5 | false | NEW |
| [[UI-097]] | ui_component | data: IssueThreadPanel (이슈 스레드 패널) | 5 | false | NEW |
| [[UI-098]] | ui_component | input: FileInput | 2 | false | NEW |
| [[UI-099]] | ui_component | input: Field | 2 | false | NEW |
| [[UI-100]] | ui_component | input: DeidentConfigCard | 2 | false | NEW |
| [[UI-101]] | ui_component | display: RecheckBadge | 1 | false | NEW |
| [[UI-102]] | ui_component | display: ReadOnlyBadge | 1 | false | NEW |
| [[UI-103]] | ui_component | feedback: AlertBanner | 1 | false | NEW |
| [[UI-104]] | ui_component | display: CountChip | 1 | false | NEW |
| [[UI-105]] | ui_component | display: DerivativeBadge | 1 | false | NEW |
| [[UI-106]] | ui_component | data: KeyValueGrid | 1 | false | NEW |
| [[UI-107]] | ui_component | input: EventAnnotationPanel | 1 | false | NEW |
| [[UI-108]] | ui_component | input: PrivacyMetaPanel | 1 | false | NEW |
| [[UI-109]] | ui_component | display: Avatar | 1 | false | NEW |
| [[UI-110]] | ui_component | display: RoleBadge | 1 | false | NEW |
| [[UI-111]] | ui_component | display: Badge | 3 | false | NEW |
| [[UI-112]] | ui_component | display: AttachmentList | 3 | false | NEW |
| [[UI-113]] | ui_component | display: PresetLabelOverflowChip | 1 | false | NEW |
| [[UI-114]] | ui_component | input: PresetLabelPicker | 1 | false | NEW |
| [[UI-115]] | ui_component | display: FieldCounter | 1 | false | NEW |
| [[UI-116]] | ui_component | display: LockIconBadge | 1 | false | NEW |
| [[UI-117]] | ui_component | feedback: DevOnlyNotice | 1 | false | NEW |
| [[UI-118]] | ui_component | display: ChannelChip | 1 | false | NEW |
| [[UI-119]] | ui_component | action: IconButton | 1 | false | NEW |
| [[UI-120]] | ui_component | display: Tooltip | 1 | false | NEW |
| [[UI-121]] | ui_component | display: DeidentStageBadge | 1 | false | NEW |
| [[UI-122]] | ui_component | data: DeidentArtifactCandidateList | 1 | false | NEW |
| [[UI-123]] | ui_component | input: ToggleSwitch | 1 | false | NEW |
| [[UI-124]] | ui_component | input: ColorSwatchField | 1 | false | NEW |
| [[UI-125]] | ui_component | input: DynamicList | 1 | false | NEW |
| [[UI-126]] | ui_component | display: DisplayNameSourceChip | 1 | false | NEW |
| [[UI-127]] | ui_component | display: DetectClassMapChip | 1 | false | NEW |
| [[UI-128]] | ui_component | data: DatamartVideoCard | 1 | false | NEW |
| [[UI-129]] | ui_component | layout: PortalHero | 1 | false | NEW |
| [[UI-130]] | ui_component | display: LabelOriginChip | 1 | false | NEW |
| [[UI-131]] | ui_component | input: UploadDropzone | 1 | false | NEW |
| [[UI-132]] | ui_component | display: AssetTypeChip | 1 | false | NEW |
| [[UI-133]] | ui_component | input: TargetResolutionSelect | 2 | false | NEW |
| [[UI-134]] | ui_component | display: SelectionSummary | 1 | false | NEW |
| [[UI-135]] | ui_component | layout: StickyActionBar | 1 | false | NEW |
| [[UI-136]] | ui_component | layout: StepSectionHeader | 1 | false | NEW |
| [[UI-137]] | ui_component | feedback: InlineResultSummary | 1 | false | NEW |
| [[UI-138]] | ui_component | data: FramePairGrid | 1 | false | NEW |
| [[UI-139]] | ui_component | overlay: SideBySideCompare | 1 | false | NEW |
| [[UI-140]] | ui_component | display: AugmentPromptSummary | 1 | false | NEW |
| [[UI-141]] | ui_component | feedback: AugmentProgressPanel | 1 | false | NEW |
| [[UI-142]] | ui_component | display: WorkerNameSub | 1 | false | NEW |
| [[UI-143]] | ui_component | display: RateGaugeCard | 1 | false | NEW |
| [[UI-144]] | ui_component | display: ProcessingStackBar | 1 | false | NEW |
