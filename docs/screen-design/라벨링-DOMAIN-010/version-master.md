# Version Master — 라벨링 (DOMAIN-010) — 화면 키트

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-010 라벨링 |
| 다운로드 화면 | SCREEN-005, SCREEN-026 |
| Last sync | 2026-08-26T05:08:42.931Z (session 13) |
| Mode | INITIAL — NEW 224 / CHANGED 0 / UNCHANGED 0 |
| 출력 루트 | /Users/ck/orca/workspaces/klid-label/portal/docs/screen-design/라벨링-DOMAIN-010 |

## ITEM 버전 표

| ITEM ID | type | title | version | stale | status |
|---|---|---|---|---|---|
| [[AC-004]] | acceptance | 객체 자동 추적(SAM2) 수행 | 7 | true | NEW |
| [[AC-005]] | acceptance | 객체 외곽 경계 자동 밀착 | 7 | true | NEW |
| [[AC-006]] | acceptance | 라벨링 정밀도(폴리곤 단순화) 조절 | 9 | false | NEW |
| [[AC-007]] | acceptance | 라벨 버전 스냅샷 저장·해시 식별 | 7 | false | NEW |
| [[AC-008]] | acceptance | 버전 diff 비교·롤백 복구 | 11 | false | NEW |
| [[AC-017]] | acceptance | 실영상 라벨링·메타 가공 | 6 | false | NEW |
| [[AC-020]] | acceptance | 다양한 환경·산불 유형 학습데이터 제작 | 4 | true | NEW |
| [[AC-021]] | acceptance | 생성된 영상 라벨링으로 학습데이터셋 편입 | 5 | true | NEW |
| [[AC-023]] | acceptance | 이미지 학습데이터 가공(추출·라벨링·가명·검수) | 6 | true | NEW |
| [[AC-024]] | acceptance | 영상 학습데이터 가공(라벨링·메타·VLM 시계열 메타 검수) | 10 | false | NEW |
| [[AC-038]] | acceptance | 온디맨드 AI 자동 추적 — 진입점 구분과 시작 객체 없는 실행 | 5 | false | NEW |
| [[AC-039]] | acceptance | 온디맨드 AI 자동 추적 — 결과 적용 방식과 수락 입도, 확정 시점 | 5 | false | NEW |
| [[AC-040]] | acceptance | 온디맨드 AI 자동 추적 — 라벨 마스터 식별자 전달 | 5 | false | NEW |
| [[API-012]] | api_endpoint | POST /v1/reviews/{videoId}/submit | 6 | false | NEW |
| [[API-018]] | api_endpoint | GET /v1/frames/{srcSn}/labels | 4 | false | NEW |
| [[API-019]] | api_endpoint | PUT /v1/frames/{srcSn}/labels | 8 | false | NEW |
| [[API-020]] | api_endpoint | POST /v1/frames/{srcSn}/sam2-track | 14 | false | NEW |
| [[API-021]] | api_endpoint | GET /v1/frames/{srcSn}/image | 8 | false | NEW |
| [[API-022]] | api_endpoint | GET /v1/labels/{lblSn}/attrs | 4 | false | NEW |
| [[API-023]] | api_endpoint | PUT /v1/labels/{lblSn}/attrs | 4 | false | NEW |
| [[API-024]] | api_endpoint | GET /v1/manage/labels | 6 | false | NEW |
| [[API-032]] | api_endpoint | POST /v1/labels/{srcSn}/deident-report | 8 | false | NEW |
| [[API-034]] | api_endpoint | GET /v1/frames/{srcSn}/versions | 9 | false | NEW |
| [[API-035]] | api_endpoint | GET /v1/versions/{version}/diff | 11 | false | NEW |
| [[API-036]] | api_endpoint | POST /v1/versions/{version}/rollback | 11 | false | NEW |
| [[API-037]] | api_endpoint | GET /v1/manage/presets | 12 | false | NEW |
| [[API-038]] | api_endpoint | POST /v1/manage/presets | 14 | false | NEW |
| [[API-039]] | api_endpoint | PUT /v1/manage/presets/{id} | 13 | false | NEW |
| [[API-040]] | api_endpoint | DELETE /v1/manage/presets/{id} | 4 | false | NEW |
| [[API-041]] | api_endpoint | [폐기] POST /v1/manage/presets/{id}/clone | 5 | false | NEW |
| [[API-066]] | api_endpoint | GET /v1/frames/{srcSn}/meta | 6 | false | NEW |
| [[API-067]] | api_endpoint | PUT /v1/frames/{srcSn}/meta | 6 | false | NEW |
| [[API-093]] | api_endpoint | POST /v1/frames/{srcSn}/sam2-segment | 14 | false | NEW |
| [[API-102]] | api_endpoint | POST /v1/videos/{rawSn}/issues | 13 | false | NEW |
| [[API-103]] | api_endpoint | GET /v1/videos/{rawSn}/issues | 10 | false | NEW |
| [[API-104]] | api_endpoint | POST /v1/issues/{issueSn}/comments | 12 | false | NEW |
| [[API-105]] | api_endpoint | POST /v1/issues/{issueSn}/resolve | 7 | false | NEW |
| [[API-117]] | api_endpoint | GET /v1/event-types/labels | 5 | false | NEW |
| [[API-123]] | api_endpoint | POST /v1/frames/{srcSn}/yolo-track | 13 | false | NEW |
| [[API-124]] | api_endpoint | POST /v1/frames/{srcSn}/autolabel | 10 | false | NEW |
| [[API-125]] | api_endpoint | POST /v1/videos/{rawSn}/tracks/merge | 2 | false | NEW |
| [[API-126]] | api_endpoint | DELETE /v1/videos/{rawSn}/tracks/{trackId} | 2 | false | NEW |
| [[API-127]] | api_endpoint | POST /v1/videos/{rawSn}/tracks/{trackId}/split | 2 | false | NEW |
| [[API-128]] | api_endpoint | GET /v1/frames/{srcSn}/description | 3 | false | NEW |
| [[API-129]] | api_endpoint | PUT /v1/frames/{srcSn}/description | 5 | false | NEW |
| [[API-132]] | api_endpoint | GET /v1/videos/{rawSn}/event-annotation | 5 | false | NEW |
| [[API-133]] | api_endpoint | POST /v1/videos/{rawSn}/event-annotation/approve | 4 | false | NEW |
| [[API-134]] | api_endpoint | PUT /v1/videos/{rawSn}/event-annotation | 5 | false | NEW |
| [[API-135]] | api_endpoint | POST /v1/videos/{rawSn}/event-annotation/reject | 4 | false | NEW |
| [[API-168]] | api_endpoint | GET /v1/videos/{rawSn}/environment-meta | 2 | false | NEW |
| [[API-170]] | api_endpoint | PUT /v1/videos/{rawSn}/environment-meta | 3 | false | NEW |
| [[API-172]] | api_endpoint | GET /v1/frames/{srcSn}/privacy-meta | 4 | false | NEW |
| [[API-173]] | api_endpoint | PUT /v1/frames/{srcSn}/privacy-meta | 6 | false | NEW |
| [[API-177]] | api_endpoint | GET /v1/manage/labels/detect-candidates | 4 | false | NEW |
| [[API-178]] | api_endpoint | POST /v1/reviews/{videoId}/cancel-submit | 8 | false | NEW |
| [[API-182]] | api_endpoint | GET /v1/versions/{version}/diff-with-working | 4 | false | NEW |
| [[API-183]] | api_endpoint | GET /v1/videos/{rawSn}/privacy-meta | 1 | false | NEW |
| [[API-184]] | api_endpoint | PUT /v1/videos/{rawSn}/privacy-meta | 2 | false | NEW |
| [[API-193]] | api_endpoint | GET /v1/ai-defaults | 5 | false | NEW |
| [[API-195]] | api_endpoint | GET /v1/videos/{rawSn}/versions/{version}/labels | 7 | false | NEW |
| [[API-196]] | api_endpoint | PUT /v1/videos/{rawSn}/labels | 9 | false | NEW |
| [[API-197]] | api_endpoint | GET /v1/videos/{rawSn}/versions | 5 | false | NEW |
| [[DS-001]] | design_system | KRDS Public | 8 | false | NEW |
| [[NAV-001]] | navigation_tree | 저작도구 내부 메뉴 (INTERNAL) | 18 | true | NEW |
| [[ROLE-001]] | permission_role | 검수자 (REVIEWER) | 10 | true | NEW |
| [[ROLE-002]] | permission_role | 라벨링 작업자 (WORKER) | 7 | true | NEW |
| [[SCREEN-005]] | screen_spec | 라벨링 캔버스 화면 | 100 | true | NEW |
| [[SCREEN-026]] | screen_spec | 프리셋 관리 화면 | 34 | true | NEW |
| [[SD-002]] | screen_design | SCREEN-005 라벨링 캔버스 화면 | 16 | true | NEW |
| [[SD-006]] | screen_design | SCREEN-026 프리셋 관리 화면 | 4 | true | NEW |
| [[SHELL-001]] | app_shell | 저작도구 내부 채널 셸 | 10 | true | NEW |
| [[UC-004]] | use_case | 객체 자동 추적 | 15 | true | NEW |
| [[UC-005]] | use_case | 객체 외곽 경계 자동 밀착 | 10 | true | NEW |
| [[UC-006]] | use_case | 라벨링 정밀도 조절 | 9 | true | NEW |
| [[UC-007]] | use_case | 라벨 버전 저장·이력 추적 | 13 | true | NEW |
| [[UC-008]] | use_case | 버전 비교·복구 | 13 | true | NEW |
| [[UC-021]] | use_case | 라벨 편집·임시저장 | 20 | true | NEW |
| [[UC-022]] | use_case | VLM 시계열 메타 검토 | 22 | true | NEW |
| [[UC-032]] | use_case | 라벨 프리셋 CRUD 관리 | 11 | false | NEW |
| [[UC-034]] | use_case | 온디맨드 AI 자동 추적 | 5 | true | NEW |
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
