# Version Master — 라벨링 (DOMAIN-010) — 화면 키트

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-010 라벨링 |
| 다운로드 화면 | SCREEN-005, SCREEN-026 |
| Last sync | 2026-08-21T00:40:33.335Z (session 15) |
| Mode | SYNC — NEW 0 / CHANGED 2 / UNCHANGED 222 |
| 출력 루트 | docs/screen-design/라벨링-DOMAIN-010 |

## ITEM 버전 표

| ITEM ID | type | title | version | stale | status |
|---|---|---|---|---|---|
| [[AC-004]] | acceptance | 객체 자동 추적(SAM2) 수행 | 7 | true | UNCHANGED |
| [[AC-005]] | acceptance | 객체 외곽 경계 자동 밀착 | 7 | true | UNCHANGED |
| [[AC-006]] | acceptance | 라벨링 정밀도(폴리곤 단순화) 조절 | 9 | false | UNCHANGED |
| [[AC-007]] | acceptance | 라벨 버전 스냅샷 저장·해시 식별 | 7 | false | CHANGED |
| [[AC-008]] | acceptance | 버전 diff 비교·롤백 복구 | 11 | false | UNCHANGED |
| [[AC-017]] | acceptance | 실영상 라벨링·메타 가공 | 6 | false | UNCHANGED |
| [[AC-020]] | acceptance | 다양한 환경·산불 유형 학습데이터 제작 | 4 | true | UNCHANGED |
| [[AC-021]] | acceptance | 생성된 영상 라벨링으로 학습데이터셋 편입 | 5 | true | UNCHANGED |
| [[AC-023]] | acceptance | 이미지 학습데이터 가공(추출·라벨링·가명·검수) | 6 | true | UNCHANGED |
| [[AC-024]] | acceptance | 영상 학습데이터 가공(라벨링·메타·VLM 시계열 메타 검수) | 6 | false | UNCHANGED |
| [[AC-038]] | acceptance | 온디맨드 AI 자동 추적 — 진입점 구분과 시작 객체 없는 실행 | 3 | false | UNCHANGED |
| [[AC-039]] | acceptance | 온디맨드 AI 자동 추적 — 결과 적용 방식과 수락 입도, 확정 시점 | 3 | false | UNCHANGED |
| [[AC-040]] | acceptance | 온디맨드 AI 자동 추적 — 라벨 마스터 식별자 전달 | 3 | false | UNCHANGED |
| [[API-012]] | api_endpoint | POST /v1/reviews/{videoId}/submit | 6 | false | UNCHANGED |
| [[API-018]] | api_endpoint | GET /v1/frames/{srcSn}/labels | 4 | false | UNCHANGED |
| [[API-019]] | api_endpoint | PUT /v1/frames/{srcSn}/labels | 8 | false | UNCHANGED |
| [[API-020]] | api_endpoint | POST /v1/frames/{srcSn}/sam2-track | 14 | false | UNCHANGED |
| [[API-021]] | api_endpoint | GET /v1/frames/{srcSn}/image | 8 | false | UNCHANGED |
| [[API-022]] | api_endpoint | GET /v1/labels/{lblSn}/attrs | 4 | false | UNCHANGED |
| [[API-023]] | api_endpoint | PUT /v1/labels/{lblSn}/attrs | 4 | false | UNCHANGED |
| [[API-024]] | api_endpoint | GET /v1/manage/labels | 6 | false | UNCHANGED |
| [[API-032]] | api_endpoint | POST /v1/labels/{srcSn}/deident-report | 8 | false | UNCHANGED |
| [[API-034]] | api_endpoint | GET /v1/frames/{srcSn}/versions | 8 | true | UNCHANGED |
| [[API-035]] | api_endpoint | GET /v1/versions/{version}/diff | 10 | true | UNCHANGED |
| [[API-036]] | api_endpoint | POST /v1/versions/{version}/rollback | 10 | true | UNCHANGED |
| [[API-037]] | api_endpoint | GET /v1/manage/presets | 5 | false | UNCHANGED |
| [[API-038]] | api_endpoint | POST /v1/manage/presets | 7 | false | UNCHANGED |
| [[API-039]] | api_endpoint | PUT /v1/manage/presets/{id} | 6 | false | UNCHANGED |
| [[API-040]] | api_endpoint | DELETE /v1/manage/presets/{id} | 3 | false | UNCHANGED |
| [[API-041]] | api_endpoint | POST /v1/manage/presets/{id}/clone | 4 | false | UNCHANGED |
| [[API-066]] | api_endpoint | GET /v1/frames/{srcSn}/meta | 4 | false | UNCHANGED |
| [[API-067]] | api_endpoint | PUT /v1/frames/{srcSn}/meta | 5 | false | UNCHANGED |
| [[API-093]] | api_endpoint | POST /v1/frames/{srcSn}/sam2-segment | 14 | false | UNCHANGED |
| [[API-102]] | api_endpoint | POST /v1/videos/{rawSn}/issues | 13 | false | UNCHANGED |
| [[API-103]] | api_endpoint | GET /v1/videos/{rawSn}/issues | 10 | false | UNCHANGED |
| [[API-104]] | api_endpoint | POST /v1/issues/{issueSn}/comments | 12 | false | UNCHANGED |
| [[API-105]] | api_endpoint | POST /v1/issues/{issueSn}/resolve | 7 | false | UNCHANGED |
| [[API-117]] | api_endpoint | GET /v1/event-types/labels | 5 | false | UNCHANGED |
| [[API-123]] | api_endpoint | POST /v1/frames/{srcSn}/yolo-track | 13 | false | UNCHANGED |
| [[API-124]] | api_endpoint | POST /v1/frames/{srcSn}/autolabel | 10 | false | UNCHANGED |
| [[API-125]] | api_endpoint | POST /v1/videos/{rawSn}/tracks/merge | 2 | false | UNCHANGED |
| [[API-126]] | api_endpoint | DELETE /v1/videos/{rawSn}/tracks/{trackId} | 2 | false | UNCHANGED |
| [[API-127]] | api_endpoint | POST /v1/videos/{rawSn}/tracks/{trackId}/split | 2 | false | UNCHANGED |
| [[API-128]] | api_endpoint | GET /v1/frames/{srcSn}/description | 3 | false | UNCHANGED |
| [[API-129]] | api_endpoint | PUT /v1/frames/{srcSn}/description | 5 | false | UNCHANGED |
| [[API-132]] | api_endpoint | GET /v1/videos/{rawSn}/event-annotation | 4 | false | UNCHANGED |
| [[API-133]] | api_endpoint | POST /v1/videos/{rawSn}/event-annotation/approve | 4 | false | UNCHANGED |
| [[API-134]] | api_endpoint | PUT /v1/videos/{rawSn}/event-annotation | 4 | false | UNCHANGED |
| [[API-135]] | api_endpoint | POST /v1/videos/{rawSn}/event-annotation/reject | 4 | false | UNCHANGED |
| [[API-168]] | api_endpoint | GET /v1/videos/{rawSn}/environment-meta | 2 | false | UNCHANGED |
| [[API-170]] | api_endpoint | PUT /v1/videos/{rawSn}/environment-meta | 3 | false | UNCHANGED |
| [[API-172]] | api_endpoint | GET /v1/frames/{srcSn}/privacy-meta | 4 | false | UNCHANGED |
| [[API-173]] | api_endpoint | PUT /v1/frames/{srcSn}/privacy-meta | 6 | false | UNCHANGED |
| [[API-177]] | api_endpoint | GET /v1/manage/labels/detect-candidates | 4 | false | UNCHANGED |
| [[API-178]] | api_endpoint | POST /v1/reviews/{videoId}/cancel-submit | 8 | false | UNCHANGED |
| [[API-182]] | api_endpoint | GET /v1/versions/{version}/diff-with-working | 3 | true | UNCHANGED |
| [[API-183]] | api_endpoint | GET /v1/videos/{rawSn}/privacy-meta | 1 | false | UNCHANGED |
| [[API-184]] | api_endpoint | PUT /v1/videos/{rawSn}/privacy-meta | 2 | false | UNCHANGED |
| [[API-193]] | api_endpoint | GET /v1/ai-defaults | 5 | false | UNCHANGED |
| [[API-195]] | api_endpoint | GET /v1/videos/{rawSn}/versions/{version}/labels | 6 | true | UNCHANGED |
| [[API-196]] | api_endpoint | PUT /v1/videos/{rawSn}/labels | 9 | false | UNCHANGED |
| [[API-197]] | api_endpoint | GET /v1/videos/{rawSn}/versions | 4 | true | UNCHANGED |
| [[DS-001]] | design_system | KRDS Public | 8 | false | UNCHANGED |
| [[NAV-001]] | navigation_tree | 저작도구 내부 메뉴 (INTERNAL) | 15 | true | UNCHANGED |
| [[ROLE-001]] | permission_role | 검수자 (REVIEWER) | 10 | true | UNCHANGED |
| [[ROLE-002]] | permission_role | 라벨링 작업자 (WORKER) | 6 | true | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 라벨링 캔버스 화면 | 96 | true | UNCHANGED |
| [[SCREEN-026]] | screen_spec | 프리셋 관리 화면 | 26 | true | UNCHANGED |
| [[SD-002]] | screen_design | SCREEN-005 라벨링 캔버스 화면 | 14 | true | UNCHANGED |
| [[SD-006]] | screen_design | SCREEN-026 프리셋 관리 화면 | 4 | true | UNCHANGED |
| [[SHELL-001]] | app_shell | 저작도구 내부 채널 셸 | 10 | true | UNCHANGED |
| [[UC-004]] | use_case | 객체 자동 추적 | 15 | true | UNCHANGED |
| [[UC-005]] | use_case | 객체 외곽 경계 자동 밀착 | 10 | true | UNCHANGED |
| [[UC-006]] | use_case | 라벨링 정밀도 조절 | 9 | true | UNCHANGED |
| [[UC-007]] | use_case | 라벨 버전 저장·이력 추적 | 13 | false | UNCHANGED |
| [[UC-008]] | use_case | 버전 비교·복구 | 13 | true | UNCHANGED |
| [[UC-021]] | use_case | 라벨 편집·임시저장 | 20 | true | UNCHANGED |
| [[UC-022]] | use_case | VLM 시계열 메타 검토 | 17 | true | UNCHANGED |
| [[UC-032]] | use_case | 라벨 프리셋 CRUD 관리 | 6 | true | UNCHANGED |
| [[UC-034]] | use_case | 온디맨드 AI 자동 추적 | 5 | true | UNCHANGED |
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
| [[UI-018]] | ui_component | display: BatchStageIndicator | 8 | false | UNCHANGED |
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
| [[UI-056]] | ui_component | display: TimeseriesSidePanel | 5 | false | UNCHANGED |
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
| [[UI-087]] | ui_component | input: BatchConfigCard | 5 | false | CHANGED |
| [[UI-088]] | ui_component | input: PrecisionConfigCard | 4 | false | UNCHANGED |
| [[UI-089]] | ui_component | display: HealthStatusList | 5 | false | UNCHANGED |
| [[UI-090]] | ui_component | action: DangerActions | 4 | false | UNCHANGED |
| [[UI-091]] | ui_component | overlay: PresetEditModal | 6 | false | UNCHANGED |
| [[UI-092]] | ui_component | display: PresetCodeChip | 4 | false | UNCHANGED |
| [[UI-093]] | ui_component | [폐기] display: BatchStageSteps | 6 | false | UNCHANGED |
| [[UI-094]] | ui_component | action: VideoActions | 4 | false | UNCHANGED |
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
| [[UI-110]] | ui_component | display: RoleBadge | 1 | false | UNCHANGED |
| [[UI-111]] | ui_component | display: Badge | 1 | false | UNCHANGED |
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
| [[UI-133]] | ui_component | input: TargetResolutionSelect | 1 | false | UNCHANGED |
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
