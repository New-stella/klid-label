# Version Master — 작업 배정 (DOMAIN-015) — 화면 키트

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-015 작업 배정 |
| 다운로드 화면 | SCREEN-012 |
| Last sync | 2026-09-15T15:41:37.909Z (session 20) |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 180 |
| 출력 루트 | docs/screen-design/작업-배정-DOMAIN-015 |

## ITEM 버전 표

| ITEM ID | type | title | version | stale | status |
|---|---|---|---|---|---|
| [[AC-1075]] | acceptance | UC-029 작업 목록 조회·필터링·배정 — 역할별 범위·일괄 확정 필터·시간축 정렬·KPI 토글·배정/일괄배정 (happy) | 6 | true | UNCHANGED |
| [[AC-1076]] | acceptance | UC-029 작업 목록 분기 — 미등록 정렬 키 strict 400·옵션 절단 안내·배정 이력 Drawer (negative) | 6 | true | UNCHANGED |
| [[AC-1108]] | acceptance | 포털 라벨링 AI 보조 — AI 탐지·AI 분할·AI 자동 추적 노출·작업 대상 인가·요청량 한도·비식별 판정 미적용·채널 격리·관제향 화면 무변경 (happy·negative) | 3 | false | UNCHANGED |
| [[AC-1122]] | acceptance | ADR-069 배정 해제 — 배정만 풀리고 라벨·이력은 남으며 해제 대상자가 이력에 실린다 (happy) | 1 | true | UNCHANGED |
| [[AC-1123]] | acceptance | ADR-069 배정 해제 거부 — 검수 단계 배정은 풀리지 않고 반려는 풀린다·작업자는 할 수 없다 (negative) | 2 | true | UNCHANGED |
| [[AC-1124]] | acceptance | 영상 제외·복원 — 화면 목록 세 곳에서 빠지고 건수·집계가 함께 줄며 되돌리면 다시 보인다 (happy) | 3 | true | UNCHANGED |
| [[AC-1125]] | acceptance | 영상 제외·복원 멱등 — 같은 값을 다시 보내면 아무것도 바뀌지 않고 이력도 남지 않으며 오류가 아니다 (edge) | 2 | true | UNCHANGED |
| [[AC-1126]] | acceptance | 영상 제외·복원 인가 — 작업자는 수행할 수 없고 검수자 이상만 수행하며 작업자 화면에는 그 동작이 없다 (negative) | 2 | true | UNCHANGED |
| [[AC-1127]] | acceptance | ADR-069 배정과 제외의 충돌 — 배정된 영상은 제외 거부·안내가 해제 창구로 이어진다·제외분에는 배정이 없다 (negative) | 4 | false | UNCHANGED |
| [[API-001]] | api_endpoint | GET /v1/users | 10 | false | UNCHANGED |
| [[API-002]] | api_endpoint | GET /v1/users/workers | 2 | false | UNCHANGED |
| [[API-070]] | api_endpoint | POST /v1/assignments | 12 | false | UNCHANGED |
| [[API-071]] | api_endpoint | PATCH /v1/assignments/{assignmentId} | 10 | false | UNCHANGED |
| [[API-072]] | api_endpoint | GET /v1/assignments | 12 | false | UNCHANGED |
| [[API-073]] | api_endpoint | GET /v1/tasks/board | 12 | false | UNCHANGED |
| [[API-116]] | api_endpoint | GET /v1/assignments/{assignmentId}/history | 10 | false | UNCHANGED |
| [[API-136]] | api_endpoint | GET /v1/tasks/board/summary | 5 | false | UNCHANGED |
| [[API-137]] | api_endpoint | GET /v1/tasks/board/event-types | 7 | false | UNCHANGED |
| [[API-187]] | api_endpoint | GET /v1/assignments/event-types | 4 | false | UNCHANGED |
| [[API-254]] | api_endpoint | POST /v1/portal/frames/{srcSn}/yolo-track | 5 | false | UNCHANGED |
| [[API-255]] | api_endpoint | POST /v1/portal/frames/{srcSn}/autolabel | 4 | false | UNCHANGED |
| [[API-256]] | api_endpoint | GET /v1/portal/ai-defaults | 2 | true | UNCHANGED |
| [[API-257]] | api_endpoint | POST /v1/portal/frames/{srcSn}/sam2-segment | 4 | false | UNCHANGED |
| [[API-258]] | api_endpoint | POST /v1/portal/ai-requests/{requestId}/cancel | 3 | true | UNCHANGED |
| [[API-259]] | api_endpoint | DELETE /v1/assignments/{assignmentId} | 2 | true | UNCHANGED |
| [[DS-001]] | design_system | KRDS Public | 9 | false | UNCHANGED |
| [[NAV-001]] | navigation_tree | 저작도구 내부 메뉴 (INTERNAL) | 26 | true | UNCHANGED |
| [[ROLE-001]] | permission_role | 검수자 (REVIEWER) | 16 | true | UNCHANGED |
| [[ROLE-002]] | permission_role | 라벨링 작업자 (WORKER) | 10 | true | UNCHANGED |
| [[ROLE-003]] | permission_role | 포털 회원 (PORTAL_USER) | 17 | false | UNCHANGED |
| [[ROLE-004]] | permission_role | 관리자 (ADMIN) | 6 | false | UNCHANGED |
| [[SCREEN-012]] | screen_spec | 작업 목록 화면 | 53 | false | UNCHANGED |
| [[SD-003]] | screen_design | SCREEN-012 작업 목록 화면 | 8 | true | UNCHANGED |
| [[SHELL-001]] | app_shell | 저작도구 내부 채널 셸 | 18 | false | UNCHANGED |
| [[UC-029]] | use_case | 작업 목록 조회·필터링·배정 | 17 | true | UNCHANGED |
| [[UC-043]] | use_case | 잘못 들어온 영상을 화면 목록에서 제외하고 되돌리기 | 2 | true | UNCHANGED |
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
| [[UI-035]] | ui_component | navigation: Gnb | 8 | false | UNCHANGED |
| [[UI-036]] | ui_component | navigation: Lnb | 3 | false | UNCHANGED |
| [[UI-037]] | ui_component | layout: PortalLayout | 6 | false | UNCHANGED |
| [[UI-038]] | ui_component | layout: Footer | 3 | false | UNCHANGED |
| [[UI-039]] | ui_component | data: SimplePieChart | 3 | false | UNCHANGED |
| [[UI-040]] | ui_component | data: SimpleBarChart | 6 | false | UNCHANGED |
| [[UI-041]] | ui_component | display: AuthImage | 3 | false | UNCHANGED |
| [[UI-042]] | ui_component | display: VideoPlayer | 4 | false | UNCHANGED |
| [[UI-043]] | ui_component | action: MarkingToolbar | 5 | false | UNCHANGED |
| [[UI-044]] | ui_component | display: MarkingTimeline | 4 | false | UNCHANGED |
| [[UI-045]] | ui_component | data: MarkingPanel | 5 | false | UNCHANGED |
| [[UI-046]] | ui_component | display: CanvasShell | 8 | false | UNCHANGED |
| [[UI-047]] | ui_component | action: ToolBar | 6 | false | UNCHANGED |
| [[UI-048]] | ui_component | overlay: LabelPickerModal | 7 | false | UNCHANGED |
| [[UI-049]] | ui_component | data: ObjectClassTree | 7 | false | UNCHANGED |
| [[UI-050]] | ui_component | input: ObjectAttributePanel | 6 | false | UNCHANGED |
| [[UI-051]] | ui_component | navigation: FrameFilmstrip | 4 | false | UNCHANGED |
| [[UI-052]] | ui_component | navigation: FrameNavigator | 7 | false | UNCHANGED |
| [[UI-053]] | ui_component | action: SaveCommitButton | 8 | false | UNCHANGED |
| [[UI-054]] | ui_component | action: UndoRedoToolbar | 5 | false | UNCHANGED |
| [[UI-055]] | ui_component | layout: LabelHeader | 11 | false | UNCHANGED |
| [[UI-056]] | ui_component | display: TimeseriesSidePanel | 8 | false | UNCHANGED |
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
| [[UI-071]] | ui_component | input: ProcessKindCard | 5 | false | UNCHANGED |
| [[UI-072]] | ui_component | display: JobCard | 5 | false | UNCHANGED |
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
| [[UI-083]] | ui_component | overlay: AssignModal | 6 | false | UNCHANGED |
| [[UI-084]] | ui_component | overlay: HistoryDrawer | 6 | false | UNCHANGED |
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
| [[UI-096]] | ui_component | input: AugmentTypeCheckbox | 6 | false | UNCHANGED |
| [[UI-097]] | ui_component | data: IssueThreadPanel (이슈 스레드 패널) | 6 | false | UNCHANGED |
| [[UI-098]] | ui_component | input: FileInput | 2 | false | UNCHANGED |
| [[UI-099]] | ui_component | input: Field | 2 | false | UNCHANGED |
| [[UI-100]] | ui_component | input: DeidentConfigCard | 2 | false | UNCHANGED |
| [[UI-101]] | ui_component | display: RecheckBadge | 1 | false | UNCHANGED |
| [[UI-102]] | ui_component | display: ReadOnlyBadge | 1 | false | UNCHANGED |
| [[UI-103]] | ui_component | feedback: AlertBanner | 1 | false | UNCHANGED |
| [[UI-104]] | ui_component | display: CountChip | 1 | false | UNCHANGED |
| [[UI-105]] | ui_component | display: DerivativeBadge | 3 | false | UNCHANGED |
| [[UI-106]] | ui_component | data: KeyValueGrid | 1 | false | UNCHANGED |
| [[UI-107]] | ui_component | input: EventAnnotationPanel | 7 | false | UNCHANGED |
| [[UI-108]] | ui_component | input: PrivacyMetaPanel | 3 | false | UNCHANGED |
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
| [[UI-129]] | ui_component | layout: PortalHero | 4 | false | UNCHANGED |
| [[UI-130]] | ui_component | display: LabelOriginChip | 1 | false | UNCHANGED |
| [[UI-131]] | ui_component | input: UploadDropzone | 2 | false | UNCHANGED |
| [[UI-132]] | ui_component | display: AssetTypeChip | 2 | false | UNCHANGED |
| [[UI-133]] | ui_component | input: TargetResolutionSelect | 2 | false | UNCHANGED |
| [[UI-134]] | ui_component | display: SelectionSummary | 2 | false | UNCHANGED |
| [[UI-135]] | ui_component | layout: StickyActionBar | 1 | false | UNCHANGED |
| [[UI-136]] | ui_component | layout: StepSectionHeader | 1 | false | UNCHANGED |
| [[UI-137]] | ui_component | feedback: InlineResultSummary | 1 | false | UNCHANGED |
| [[UI-138]] | ui_component | data: FramePairGrid | 2 | false | UNCHANGED |
| [[UI-139]] | ui_component | overlay: SideBySideCompare | 2 | false | UNCHANGED |
| [[UI-140]] | ui_component | display: AugmentPromptSummary | 2 | false | UNCHANGED |
| [[UI-141]] | ui_component | feedback: AugmentProgressPanel | 1 | false | UNCHANGED |
| [[UI-142]] | ui_component | display: WorkerNameSub | 1 | false | UNCHANGED |
| [[UI-143]] | ui_component | display: RateGaugeCard | 1 | false | UNCHANGED |
| [[UI-144]] | ui_component | display: ProcessingStackBar | 1 | false | UNCHANGED |
