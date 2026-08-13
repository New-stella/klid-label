# UI 컴포넌트 카탈로그 (115건)

| ID | 이름 | category |
|---|---|---|
| UI-001 | action: Button | action |
| UI-002 | input: Input | input |
| UI-003 | input: Select | input |
| UI-004 | overlay: Modal | overlay |
| UI-005 | overlay: ConfirmDialog | overlay |
| UI-006 | overlay: Drawer | overlay |
| UI-007 | data: DataTable | data |
| UI-008 | navigation: Pagination | navigation |
| UI-009 | navigation: Tabs | navigation |
| UI-010 | display: KpiCard | display |
| UI-011 | layout: Card | layout |
| UI-012 | layout: PageHeader | layout |
| UI-013 | navigation: Breadcrumb | navigation |
| UI-014 | display: StatusBadge | display |
| UI-015 | [폐기] display: PrivacyBadge | display |
| UI-016 | display: EventTypeBadge | display |
| UI-017 | display: StageBadge | display |
| UI-018 | display: BatchStageIndicator | display |
| UI-019 | feedback: ProgressBar | feedback |
| UI-020 | feedback: EmptyState | feedback |
| UI-021 | feedback: ErrorState | feedback |
| UI-022 | feedback: LoadingOverlay | feedback |
| UI-023 | feedback: Toast | feedback |
| UI-024 | input: Checkbox | input |
| UI-025 | input: Radio | input |
| UI-026 | input: RadioGroup | input |
| UI-027 | input: Textarea | input |
| UI-028 | input: DatePicker | input |
| UI-029 | input: DateRangePicker | input |
| UI-030 | [폐기] input: FormField | input |
| UI-031 | overlay: Popover | overlay |
| UI-032 | feedback: Spinner | feedback |
| UI-033 | feedback: Skeleton | feedback |
| UI-034 | layout: AppLayout | layout |
| UI-035 | navigation: Gnb | navigation |
| UI-036 | navigation: Lnb | navigation |
| UI-037 | layout: PortalLayout | layout |
| UI-038 | layout: Footer | layout |
| UI-039 | data: SimplePieChart | data |
| UI-040 | data: SimpleBarChart | data |
| UI-041 | display: AuthImage | display |
| UI-042 | display: VideoPlayer | display |
| UI-043 | action: MarkingToolbar | action |
| UI-044 | display: MarkingTimeline | display |
| UI-045 | data: MarkingPanel | data |
| UI-046 | display: CanvasShell | display |
| UI-047 | action: ToolBar | action |
| UI-048 | overlay: LabelPickerModal | overlay |
| UI-049 | data: ObjectClassTree | data |
| UI-050 | input: ObjectAttributePanel | input |
| UI-051 | navigation: FrameFilmstrip | navigation |
| UI-052 | navigation: FrameNavigator | navigation |
| UI-053 | action: SaveCommitButton | action |
| UI-054 | action: UndoRedoToolbar | action |
| UI-055 | layout: LabelHeader | layout |
| UI-056 | display: TimeseriesSidePanel | display |
| UI-057 | action: DeidentReportButton | action |
| UI-058 | display: ReviewLabelCanvas | display |
| UI-059 | [폐기] action: ReviewActionBar | action |
| UI-060 | layout: ReviewHeader | layout |
| UI-061 | [폐기] data: IssueSidebar | data |
| UI-062 | overlay: RejectModal | overlay |
| UI-063 | navigation: ReviewFrameTimeline | navigation |
| UI-064 | [폐기] data: ObjectListPanel | data |
| UI-065 | input: ReviewMemoPanel | input |
| UI-066 | data: VersionList | data |
| UI-067 | display: DiffViewer | display |
| UI-068 | [폐기] input: VersionPicker | input |
| UI-069 | overlay: RollbackConfirmModal | overlay |
| UI-070 | layout: HistoryPanel | layout |
| UI-071 | input: ProcessKindCard | input |
| UI-072 | display: JobCard | display |
| UI-073 | action: DecisionCard | action |
| UI-074 | [폐기] input: TimeseriesSidePanel | input |
| UI-075 | [폐기] display: StateChangeTimeline | display |
| UI-076 | display: ConfidenceDistributionChart | display |
| UI-077 | data: MyTasksTable | data |
| UI-078 | display: EventDistributionGrid | display |
| UI-079 | display: NoticeCard | display |
| UI-080 | data: WorkerStatsTable | data |
| UI-081 | data: DailyCompletionChart | data |
| UI-082 | data: EventTypePieChart | data |
| UI-083 | overlay: AssignModal | overlay |
| UI-084 | overlay: HistoryDrawer | overlay |
| UI-085 | input: TaskFilters | input |
| UI-086 | input: YoloConfigCard | input |
| UI-087 | input: BatchConfigCard | input |
| UI-088 | input: PrecisionConfigCard | input |
| UI-089 | display: HealthStatusList | display |
| UI-090 | action: DangerActions | action |
| UI-091 | overlay: PresetEditModal | overlay |
| UI-092 | display: PresetCodeChip | display |
| UI-093 | [폐기] display: BatchStageSteps | display |
| UI-094 | action: VideoActions | action |
| UI-095 | input: VideoFilters | input |
| UI-096 | input: AugmentTypeCheckbox | input |
| UI-097 | data: IssueThreadPanel (이슈 스레드 패널) | data |
| UI-098 | input: FileInput | input |
| UI-099 | input: Field | input |
| UI-100 | input: DeidentConfigCard | input |
| UI-101 | display: RecheckBadge | display |
| UI-102 | display: ReadOnlyBadge | display |
| UI-103 | feedback: AlertBanner | feedback |
| UI-104 | display: CountChip | display |
| UI-105 | display: DerivativeBadge | display |
| UI-106 | data: KeyValueGrid | data |
| UI-107 | input: EventAnnotationPanel | input |
| UI-108 | input: PrivacyMetaPanel | input |
| UI-109 | display: Avatar | display |
| UI-110 | display: RoleBadge | display |
| UI-111 | display: Badge | display |
| UI-112 | display: AttachmentList | display |
| UI-113 | display: PresetLabelOverflowChip | display |
| UI-114 | input: PresetLabelPicker | input |
| UI-115 | display: FieldCounter | display |

---

<!-- UI-001 -->

# action: Button

## name

Button

## tags

- common
- primitive
- forwardRef

## category

action

## variants

### primary

- **description**: 기본 강조 버튼 (bg-primary-600)

### secondary

- **description**: 흰 배경 + 회색 보더

### outline

- **description**: primary 보더 아웃라인

### danger

- **description**: 파괴적 액션 (반려/삭제, bg-danger)

### ghost

- **description**: 배경 없는 텍스트 버튼

### success

- **description**: 성공/완료 강조 버튼(승인 등 긍정적 액션에 사용)

### link

- **description**: 배경 없는 밑줄 텍스트 링크 스타일 버튼

## description

공통 버튼 프리미티브. 7종 variant(primary/secondary/outline/danger/ghost/success/link) × 8종 size(xs/sm/md/lg/icon/icon-xs/icon-sm/icon-lg), loading 시 Spinner 표시 + aria-busy, fullWidth, leftIcon/rightIcon(lucide ComponentType) 슬롯, asChild 로 다른 요소에 스타일 위임 가능. forwardRef. 거의 모든 화면의 액션 트리거에 사용되는 기반 컴포넌트.

## code_snippet

<Button variant="primary" leftIcon={Plus} onClick={onSubmit}>저장</Button>

## props_schema

### variant

- **type**: 'primary'|'secondary'|'outline'|'danger'|'ghost'|'success'|'link'
- **default**: primary
- **required**: false

### size

- **type**: 'xs'|'sm'|'md'|'lg'|'icon'|'icon-xs'|'icon-sm'|'icon-lg'
- **default**: md
- **required**: false
- **description**: xs=콤팩트 텍스트(밀집 패널), sm/md/lg=표준 텍스트 크기. icon/icon-xs/icon-sm/icon-lg=텍스트 라벨 없는 정사각형 아이콘 전용 버튼(툴바·목록 행 액션) — 시각 라벨이 없으므로 aria-label 필수.

### loading

- **type**: boolean
- **default**: false
- **required**: false
- **description**: true 시 Spinner 표시 + 비활성

### fullWidth

- **type**: boolean
- **default**: false
- **required**: false

### type

- **type**: 'button'|'submit'|'reset'
- **default**: button
- **required**: false

### leftIcon

- **type**: ComponentType<{className?:string}>
- **required**: false

### rightIcon

- **type**: ComponentType<{className?:string}>
- **required**: false

### asChild

- **type**: boolean
- **default**: false
- **required**: false
- **description**: true 면 Button 자체를 렌더링하지 않고 자식 요소에 Button 스타일만 위임한다 — 다른 오버레이 프리미티브(확인 다이얼로그의 확인/취소 액션 등)에 버튼 스타일을 입힐 때 사용.

## design_system_id

DS-001

## accessibility_notes

focus-visible ring, disabled:cursor-not-allowed, loading 시 aria-busy 설정. 아이콘은 텍스트 라벨과 병행. icon 계열 size(icon/icon-xs/icon-sm/icon-lg)는 시각 텍스트가 없으므로 aria-label 을 반드시 별도 지정한다.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-006
- SCREEN-009
- SCREEN-011
- SCREEN-012
- SCREEN-018
- SCREEN-022
- SCREEN-025


---

<!-- UI-002 -->

# input: Input

## name

Input

## tags

- common
- primitive
- form

## category

input

## description

공통 텍스트 입력 프리미티브 — label/hint/error 를 자체적으로 두지 않는 순수 스타일 프리미티브다(네이티브 input 그대로, 커스텀 wrapper div 없음). type 을 그대로 통과시켜 type='file' 요청 시 파일 선택 버튼 스타일도 같은 컴포넌트가 처리한다. aria-invalid 를 직접 전달하면 그 값만으로 위험(destructive) 보더·링 스타일이 즉시 반영되지만 오류 메시지 자체는 렌더링하지 않는다 — 라벨·설명·오류 메시지 조립은 Field 계열(UI-099)이 전담한다. 우측 아이콘·액션이 필요하면 내장 슬롯 prop 대신 바깥에서 relative 컨테이너 + absolute 배치로 조합한다(전용 슬롯 prop 을 두지 않는다).

## props_schema

### type

- **type**: HTMLInputTypeAttribute
- **required**: false
- **description**: 네이티브 input type 을 그대로 전달. 'file' 지정 시 파일 선택 버튼 스타일도 동일 컴포넌트가 처리한다.

### aria-invalid

- **type**: boolean
- **required**: false
- **description**: true 전달 시 보더·포커스링이 destructive 색상으로 전환된다. 오류 문구는 렌더링하지 않음 — 문구 표시는 Field 계열의 FieldError(UI-099)가 담당.

### disabled

- **type**: boolean
- **required**: false
- **description**: 네이티브 disabled — 배경 흐림 + 포인터 이벤트 차단.

### className

- **type**: string
- **required**: false
- **description**: tailwind-merge 로 기본 스타일 위에 클래스를 덮어쓴다.

## usage_example

검색어·이름 등 한 줄 텍스트 입력 전반에 쓰는 기본 프리미티브. 라벨·설명·오류 문구가 필요하면 Field·FieldLabel·FieldDescription·FieldError(UI-099)로 감싸 조립한다 — 이 컴포넌트 자체는 label/hint/error prop 을 받지 않는다. 여러 줄 입력은 Textarea(UI-027), 날짜는 DatePicker(UI-028)를 쓰고 이 컴포넌트로 대체하지 않는다.

## design_system_id

DS-001

## accessibility_notes

라벨 연결·오류 안내·설명 문구 연결은 이 컴포넌트가 아니라 Field 계열 조립부(UI-099)가 담당한다 — 이 프리미티브 자신은 htmlFor 대상이 되는 id 만 받는다. aria-invalid 를 직접 전달하면 시각적 위험 스타일만 즉시 반영된다. 설명·오류 문구의 aria-describedby 연결은 Field 계열 조립부가 자동으로 맺는다 — 오류가 렌더되면 오류를, 오류가 없으면 설명을 가리키고 렌더되지 않은 문구는 참조하지 않는다. 호출부가 id·aria-describedby·aria-invalid 를 직접 지정하면 그 값이 우선한다. 포커스 시 2px 보더로 전환되는 진한 포커스 표시.

## referenced_by_screen_ids

- SCREEN-002
- SCREEN-004
- SCREEN-005
- SCREEN-012
- SCREEN-018
- SCREEN-024
- SCREEN-026
- SCREEN-030


---

<!-- UI-003 -->

# input: Select

## name

Select

## tags

- common
- primitive
- form
- compound

## category

input

## description

공통 셀렉트 프리미티브 — 조합형 컴포넌트로 Select(루트, 상태 보유)·SelectTrigger(트리거 버튼, size='default'|'sm')·SelectValue(선택값 또는 placeholder 텍스트 표시)·SelectContent(포털 드롭다운 패널, position='item-aligned'|'popper')·SelectItem(개별 옵션, 선택 시 체크 아이콘)·SelectGroup/SelectLabel/SelectSeparator(옵션 묶음·소제목·구분선)·SelectScrollUpButton/SelectScrollDownButton(패널 스크롤 시 자동 노출)으로 구성된다. 옵션 배열을 컴포넌트에 통째로 넘기는 단일 prop 은 두지 않고, 호출부가 옵션 데이터를 SelectItem 자식으로 직접 매핑해 그룹·구분선·비활성 옵션 등 임의 구성을 허용한다. label/hint/error 는 이 컴포넌트가 갖지 않고 조립 래퍼(UI-099)가 담당한다.

## props_schema

### Select.value

- **type**: string
- **required**: false
- **description**: 제어 모드 선택값.

### Select.onValueChange

- **type**: (value:string)=>void
- **required**: false
- **description**: 값 변경 콜백.

### Select.defaultValue

- **type**: string
- **required**: false
- **description**: 비제어 모드 초기값.

### Select.disabled

- **type**: boolean
- **required**: false
- **description**: 전체 비활성화.

### SelectTrigger.size

- **type**: 'default'|'sm'
- **default**: default
- **required**: false
- **description**: 트리거 버튼 높이. default 는 입력·버튼과 같은 44px 로 최소 터치 타깃 하한을 지키며, 그래야 한 폼 줄에서 다른 입력과 높이가 맞는다. sm 은 표 안 액션·툴바처럼 밀집 배치 전용 예외로 그보다 낮게 두되 단독 터치 타깃으로는 쓰지 않는다.

### SelectValue.placeholder

- **type**: ReactNode
- **required**: false
- **description**: 미선택 시 표시 문구. data-placeholder 속성으로 muted 색 처리.

### SelectContent.position

- **type**: 'item-aligned'|'popper'
- **default**: item-aligned
- **required**: false
- **description**: 드롭다운 패널 배치 방식.

### SelectItem.value

- **type**: string
- **required**: true
- **description**: 옵션 값.

### SelectItem.disabled

- **type**: boolean
- **required**: false
- **description**: 개별 옵션 비활성화.

## usage_example

옵션 목록에서 하나를 고르는 폼 필드(작업자 배정 선택, 라벨 속성 입력형식 선택 등)에 쓴다. 옵션 데이터는 배열 prop 이 아니라 호출부가 SelectItem 자식으로 매핑해 구성하며, 그룹 소제목이 필요하면 SelectGroup+SelectLabel 로 묶는다. 라벨·설명·오류 문구는 Field 계열(UI-099)로 감싸 조립한다. 옵션이 2~4개로 적고 항상 펼쳐 비교해야 하면 RadioGroup(UI-026)을 우선 검토한다.

## design_system_id

DS-001

## accessibility_notes

SelectValue 의 placeholder 는 data-placeholder 속성으로 muted 색 처리되어 미선택 상태를 시각적으로 구분한다. SelectItem 선택 시 체크 아이콘이 ItemIndicator 로 노출된다. aria-invalid 전달 시 트리거 보더가 destructive 로 전환되지만(오류 문구 자체는 렌더링하지 않음 — FieldError(UI-099)가 담당), 확인된 사용처 전체에서 aria-describedby 를 통한 오류 문구의 프로그램적 연결은 존재하지 않는다. 트리거 포커스 시 2px 포커스보더.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-007
- SCREEN-012
- SCREEN-018
- SCREEN-020
- SCREEN-024
- SCREEN-026
- SCREEN-027
- SCREEN-035


---

<!-- UI-004 -->

# overlay: Modal

## name

Modal

## tags

- common
- overlay
- portal
- a11y

## category

overlay

## description

공통 모달 오버레이. createPortal(body) 렌더, ESC 닫기 + 포커스 트랩 + 포커스 복귀, 백드롭 클릭 닫기(옵션), 4종 size(sm/md/lg/xl), title/description/footer 슬롯, 닫기(X) 버튼 표시 여부 제어(showCloseButton). role=dialog aria-modal. title 이 문자열이 아니면 ariaLabel 로 접근성 이름을 별도 지정한다.

## props_schema

### open

- **type**: boolean
- **required**: true

### onClose

- **type**: () => void
- **required**: true

### title

- **type**: ReactNode
- **required**: false

### description

- **type**: ReactNode
- **required**: false

### footer

- **type**: ReactNode
- **required**: false

### size

- **type**: 'sm'|'md'|'lg'|'xl'
- **default**: md
- **required**: false

### closeOnBackdrop

- **type**: boolean
- **default**: true
- **required**: false

### closeOnEsc

- **type**: boolean
- **default**: true
- **required**: false

### ariaLabel

- **type**: string
- **required**: false
- **description**: title 이 문자열이 아니거나(ReactNode) 미지정일 때 접근성 이름(aria-label)으로 사용된다.

### showCloseButton

- **type**: boolean
- **default**: true
- **required**: false
- **description**: 닫기(X) 버튼 표시 여부 — false 시 숨김(강제 확인이 필요한 흐름에서 사용).

## usage_example

영상 상세·사용자 관리·라벨 마스터 등록처럼 화면 전환 없이 폼·상세 정보를 오버레이로 띄울 때 사용. 단순 확인/취소 액션에는 Modal 을 직접 조립하지 않고 ConfirmDialog 를 사용한다.

## design_system_id

DS-001

## accessibility_notes

role=dialog, aria-modal=true, Tab 포커스 트랩, ESC 닫기, 닫을 시 직전 포커스 복귀. aria-label 은 title(문자열) 또는 ariaLabel. 닫기 버튼을 숨겨도(showCloseButton=false) ESC·포커스 트랩은 유지되어 키보드 접근성이 깨지지 않는다.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-009
- SCREEN-012
- SCREEN-024
- SCREEN-026
- SCREEN-030
- SCREEN-035
- SCREEN-036


---

<!-- UI-005 -->

# overlay: ConfirmDialog

## name

ConfirmDialog

## tags

- common
- overlay
- confirm
- composed-from:Modal

## category

overlay

## description

Modal(size=sm) 위에 구축한 확인/취소 다이얼로그. confirmLabel/cancelLabel, variant(primary|danger), loading 상태(확인 버튼 Spinner + 취소 비활성화), closeOnEsc/closeOnBackdrop 을 Modal 에 그대로 전달(처리 중 강제 닫힘 방지에 사용). 삭제/반려/롤백 등 되돌릴 수 없는 파괴적 액션 확인에 사용.

## props_schema

### open

- **type**: boolean
- **required**: true

### title

- **type**: ReactNode
- **required**: true

### description

- **type**: ReactNode
- **required**: false

### confirmLabel

- **type**: string
- **default**: 확인
- **required**: false

### cancelLabel

- **type**: string
- **default**: 취소
- **required**: false

### variant

- **type**: 'primary'|'danger'
- **default**: primary
- **required**: false

### loading

- **type**: boolean
- **required**: false

### onConfirm

- **type**: () => void
- **required**: true

### onCancel

- **type**: () => void
- **required**: true

### closeOnEsc

- **type**: boolean
- **required**: false
- **description**: ESC 키로 닫기 허용 여부(Modal 패스스루). 미지정 시 Modal 기본값(true). 처리 중(loading) 강제 닫힘을 막아야 하는 곳에서 false 지정.

### closeOnBackdrop

- **type**: boolean
- **required**: false
- **description**: 백드롭 클릭으로 닫기 허용 여부(Modal 패스스루). 미지정 시 Modal 기본값(true).

## usage_example

삭제·반려·롤백처럼 되돌릴 수 없는 액션을 확정하기 전 마지막 확인으로 쓴다. variant=danger 를 고를 때는 description 에 '복구할 수 없습니다' 와 같이 되돌릴 수 없음을 명시하는 문구를 반드시 포함한다 — 컴포넌트가 이 문구를 강제하지 않으므로 호출부 책임이다. 단순 정보 확인이나 되돌릴 수 있는 액션에는 variant=primary 를 쓴다. 저장 중(loading) 및 처리 중에는 closeOnEsc/closeOnBackdrop 을 false 로 둘러 사용자가 결과 확인 전에 다이얼로그를 닫지 못하게 한다.

## design_system_id

DS-001

## accessibility_notes

Modal(role=dialog, aria-modal=true, Tab 포커스 트랩, ESC 닫기, 닫을 시 직전 포커스 복귀)을 그대로 상속받는다. 확인/취소 버튼은 Tab 순서로 도달 가능. variant=danger 는 시각적 색상 만 바꿀 뿐 스크린리더에 위험도를 전달하지 않으므로, 위험성은 title/description 텍스트로 명시해야 한다.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-009
- SCREEN-010
- SCREEN-019
- SCREEN-024
- SCREEN-026
- SCREEN-031
- SCREEN-032


---

<!-- UI-006 -->

# overlay: Drawer

## name

Drawer

## tags

- common
- overlay
- portal
- a11y

## category

overlay

## description

공통 사이드 드로어 오버레이. createPortal, side(left|right), width 지정, ESC/백드롭 닫기(옵션) + 포커스 트랩 + 복귀, title/footer 슬롯, 닫기(X) 버튼 표시 여부 제어(showCloseButton). title 이 문자열이 아니면 ariaLabel 로 접근성 이름을 별도 지정한다. 배정 이력 등 보조 패널에 사용.

## props_schema

### open

- **type**: boolean
- **required**: true

### onClose

- **type**: () => void
- **required**: true

### side

- **type**: 'left'|'right'
- **default**: right
- **required**: false

### title

- **type**: ReactNode
- **required**: false

### footer

- **type**: ReactNode
- **required**: false

### width

- **type**: string
- **default**: 400px
- **required**: false

### ariaLabel

- **type**: string
- **required**: false
- **description**: title 이 문자열이 아니거나(ReactNode) 미지정일 때 접근성 이름(aria-label)으로 사용된다.

### closeOnBackdrop

- **type**: boolean
- **default**: true
- **required**: false
- **description**: 백드롭 클릭으로 닫기 허용 여부.

### closeOnEsc

- **type**: boolean
- **default**: true
- **required**: false
- **description**: ESC 키로 닫기 허용 여부.

### showCloseButton

- **type**: boolean
- **default**: true
- **required**: false
- **description**: 닫기(X) 버튼 표시 여부 — false 시 숨김.

## usage_example

배정 이력처럼 목록 화면을 유지한 채 보조 정보·이력을 옆에서 열람할 때 사용. 화면을 완전히 대체해야 하는 폼·상세 입력에는 Modal 을 사용한다.

## design_system_id

DS-001

## accessibility_notes

role=dialog aria-modal, Tab 트랩, ESC 닫기, 포커스 복귀. aria-label 은 title(문자열) 또는 ariaLabel. 닫기 버튼을 숨겨도(showCloseButton=false) ESC·포커스 트랩은 유지되어 키보드 접근성이 깨지지 않는다.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-010
- SCREEN-012
- SCREEN-035


---

<!-- UI-007 -->

# data: DataTable

## name

DataTable

## tags

- common
- data
- table
- generic

## category

data

## description

제네릭 데이터 테이블 — 컬럼 정의 위의 얇은 렌더 계층이다. columns 만 넘기면 헤더·본문을 렌더하며, 페이지네이션·로딩 표시·선택 체크박스 컬럼·서버 정렬 헤더는 이 컴포넌트가 내장하지 않고 호출부가 조합한다. 표 컨테이너는 최대 높이 60vh 로 스크롤되고 헤더 행은 상단에 고정(sticky)된다. 행이 0건이면 colSpan 전체 크기의 셀 하나에 빈 상태 표시(EmptyState)를 렌더하고 emptyMessage 가 그 문구가 된다 — 이는 '조회 성공 후 데이터 없음'에만 쓰는 표시이며, 조회 자체가 실패한 경우(에러)는 호출부가 이 컴포넌트를 아예 렌더하지 않고 별도 에러 표시로 대체해 빈 상태와 에러를 항상 구분한다. 로딩 중에도 이 컴포넌트는 렌더하지 않는다 — 호출부가 데이터 도착 전까지 스켈레톤 자리표시로 표 영역 전체를 대체하고, 데이터가 오면 이 컴포넌트로 교체한다. 페이지네이션은 이 컴포넌트 반환 영역 아래에 별도 컴포넌트로 이어붙이며, 총 개수·현재 페이지는 호출부가 서버 응답으로 소유한다. 정렬은 시간축 단일 기준으로 서버가 처리하는 것이 기본 정책이며, 그 화면에서는 정렬 헤더 UI와 정렬 상태를 컬럼 정의 쪽에서 직접 구성해 서버 정렬 콜백에 연결한다 — 이 컴포넌트가 제공하는 sortable(클라이언트 로컬 정렬, 기본 꺼짐)은 그 경우 켜지 않는다(두 축을 동시에 켜면 충돌한다). 선택(체크박스)도 전용 선택 컬럼을 columns 배열에 포함시켜 조합하고, 선택 상태 자체는 rowSelection/onRowSelectionChange/getRowId 로 제어형으로 관리한다. onRowClick 이 있으면 행 전체가 클릭 가능해지지만 체크박스·버튼·링크·입력 등 인터랙티브 요소 클릭은 행 이동으로 전파되지 않는다. 영상/작업/검수/사용자 목록 등 서버 페이징을 쓰는 모든 목록 화면의 렌더 기반.

## props_schema

### columns

- **type**: ColumnDef<T, unknown>[]
- **required**: true
- **description**: 컬럼 정의 배열. accessorKey/header/cell/size/enableSorting 등 표준 컬럼 정의 규약을 그대로 따르며 헤더·셀 렌더는 flexRender 로 위임한다. 선택 체크박스 컬럼·행 액션 버튼 컬럼도 전용 컬럼 정의를 이 배열에 포함시켜 조합한다 — 이 컴포넌트가 선택/액션 컬럼을 자동으로 추가하지 않는다.

### data

- **type**: T[]
- **required**: true
- **description**: 표시할 행 데이터 — 서버가 이미 페이징한 현재 페이지 분량.

### emptyMessage

- **type**: string
- **default**: 데이터가 없습니다.
- **required**: false
- **description**: 행이 0건일 때 표 본문에 colSpan 전체로 표시할 안내 문구. 조회 성공 후 결과 0건에만 쓰고 조회 실패(에러)에는 쓰지 않는다 — 실패 시 호출부가 이 컴포넌트를 아예 렌더하지 않고 별도 에러 표시로 대체한다.

### enableRowSelection

- **type**: boolean | ((row: Row<T>) => boolean)
- **required**: false
- **description**: 행 선택 가능 여부. 함수로 주면 행 단위 조건부 선택(예: 특정 상태 행만 선택 가능)이 가능하다. true 여도 선택 체크박스 컬럼 자체가 자동 추가되지는 않는다 — columns 에 선택 컬럼을 별도로 포함시켜야 한다.

### rowSelection

- **type**: Record<string, boolean>
- **required**: false
- **description**: 제어형 선택 상태 맵(행 id → 선택 여부). 값의 소유·영속은 호출부 책임.

### onRowSelectionChange

- **type**: (updaterOrValue) => void
- **required**: false
- **description**: 선택 상태 변경 콜백.

### getRowId

- **type**: (row: T, index: number) => string
- **required**: false
- **description**: React key 이자 선택 상태 키 산출 함수. 미지정 시 배열 인덱스로 대체된다.

### onRowClick

- **type**: (row: T) => void
- **required**: false
- **description**: 지정 시 행 전체가 클릭 가능해지고 hover 강조가 붙는다. 체크박스·버튼·링크·입력(label, role=checkbox 포함) 클릭은 행 이동에서 제외된다.

### minHeight

- **type**: number|string
- **required**: false
- **description**: 표 영역 최소 높이 — 로딩 자리표시에서 표로 전환될 때 레이아웃이 흔들리는 것을 막는다.

### sortable

- **type**: boolean
- **default**: false
- **required**: false
- **description**: 컬럼 헤더 클릭으로 켜는 클라이언트(로컬) 정렬. 기본 꺼짐 — 서버가 정렬을 처리하는 목록(시간축 단일 기준 정렬 정책을 쓰는 화면)에는 켜지 않는다. 그 경우 정렬 헤더 UI와 정렬 상태는 컬럼 정의 쪽에서 직접 구성해 서버 정렬 콜백과 연결하며, 이 로컬 정렬과 동시에 켜면 두 축이 충돌한다.

### initialSorting

- **type**: SortingState
- **required**: false
- **description**: sortable=true 일 때만 유효한 최초 정렬 상태.

### className

- **type**: string
- **required**: false
- **description**: 루트 컨테이너에 추가할 커스텀 클래스.

### columns[].meta.ariaSort

- **type**: 'ascending'|'descending'|'none'
- **required**: false
- **description**: 서버 정렬처럼 이 컴포넌트가 알지 못하는 정렬축의 정렬 방향을 컬럼 정의 쪽에서 헤더에 직접 실어 주는 확장점. 접근성 서술이 남겨 둔 '컬럼 정의 쪽에서 aria-sort 를 직접 부여할지'라는 열린 질문의 답이다 — 부여한다. 로컬 정렬(sortable=true)이 켜진 컬럼은 컴포넌트 자신의 정렬 상태가 우선하므로 이 값은 무시된다(두 축이 겹치지 않게 한다). 지정하지 않으면 정렬 방향을 알리는 속성이 붙지 않고 헤더 안의 시각 아이콘만 남는다.

## usage_example

서버 페이징 목록 화면(영상 목록/작업 목록/검수 목록/사용자 관리 등)에서 화면이 로딩·에러·목록 세 상태를 먼저 분기한다 — 로딩 중이면 표 영역 전체를 스켈레톤 자리표시로 대체하고, 에러면 이 컴포넌트를 아예 그리지 않고 에러 표시로 대체하며, 조회가 끝났을 때만 이 컴포넌트를 렌더한다. 페이지네이션은 이 컴포넌트 아래에 별도로 이어붙이고 총 페이지 수·현재 페이지·페이지 전환 콜백을 페이지네이션 훅과 연결한다. 일괄 작업(배정·삭제 등)이 있는 화면만 선택 컬럼을 columns 에 포함시키고 rowSelection 상태를 연결한다 — 포함하지 않으면 체크박스 컬럼 자체가 생기지 않는다. 서버 정렬이 필요한 컬럼은 헤더에 정렬 버튼을 직접 구성해 서버 정렬 파라미터와 연결하며, 이 경우 sortable prop 은 켜지 않는다. 로딩 자리표시는 화면마다 새로 만들지 않고 이 컴포넌트와 함께 제공되는 표 모양 스켈레톤(컬럼 수·행 수를 받아 표 영역을 채운다)을 쓴다 — 목록 화면마다 같은 자리표시를 복제하면 표와 자리표시의 열 수가 어긋나 전환 시 레이아웃이 흔들린다.

## design_system_id

DS-001

## accessibility_notes

네이티브 표 마크업(암묵적 table 역할)을 그대로 사용한다. 정렬 가능한 헤더의 aria-sort 는 이 컴포넌트 자신의 로컬 정렬 상태(sortable=true)만 반영해 'ascending'/'descending'/(정렬 가능하지만 미정렬인 경우 값 없음)로 나뉜다 — 서버 정렬을 컬럼 헤더에서 직접 구성하는 목록(sortable=false)은 이 컴포넌트가 그 정렬 상태를 모르므로 aria-sort 가 부여되지 않고, 정렬 방향은 헤더 안의 시각 아이콘으로만 전달된다(컬럼 정의 쪽에서 aria-sort 를 직접 부여할지는 별도 판단이 필요하다). 선택 컬럼을 조합하면 전체선택 체크박스는 aria-label='모두 선택', 행 체크박스는 aria-label='{행 id} 행 선택'이며 부분 선택 시 전체선택 체크박스가 indeterminate 로 표시된다.

## referenced_by_screen_ids

- SCREEN-018
- SCREEN-024


---

<!-- UI-008 -->

# navigation: Pagination

## name

Pagination

## tags

- common
- navigation
- pagination

## category

navigation

## description

페이지네이션 컨트롤. 이전 · 페이지 번호 목록 · 다음 순서로 배치한다.

페이지 번호는 **양끝(첫 페이지·마지막 페이지)과 현재 페이지 앞뒤 1칸**만 노출하고 그 사이가 떨어져 있으면 말줄임으로 접는다. 고정 개수의 번호 창을 옆으로 미끄러뜨리는 방식은 두지 않는다 — 번호가 많아질수록 끝 페이지로 가는 경로가 사라지기 때문이다.

그 대신 **처음·마지막 전용 버튼은 두지 않는다** — 양끝 번호가 항상 보이므로 그 자리가 겹친다. 첫 페이지에서는 이전을, 마지막 페이지에서는 다음을 비활성화한다(누르지 못하게 하되 자리는 유지해 버튼 위치가 흔들리지 않게 한다).

페이지는 0부터 세고 화면에는 1부터의 번호로 표시한다. 이동 요청은 범위(첫 페이지~마지막 페이지)로 가두고, 현재 페이지와 같으면 콜백을 부르지 않는다 — 같은 페이지를 다시 조회하지 않게 하기 위해서다.

표에 내장되지 않는다. 표를 쓰는 화면은 표 아래에 이 컨트롤을 따로 이어붙이며, 현재 페이지와 전체 페이지 수는 호출부가 서버 응답으로 소유한다.

## props_schema

### page

- **type**: number
- **required**: true
- **description**: 현재 페이지(0부터). 화면 표시는 1부터의 번호로 한다.

### totalPages

- **type**: number
- **required**: true
- **description**: 전체 페이지 수. 서버 응답의 값을 그대로 받는다 — 총 건수와 페이지 크기로 되계산하지 않는다(페이지 크기 규칙을 이 컨트롤이 또 알 필요가 없도록).

### onChange

- **type**: (page: number) => void
- **required**: true
- **description**: 페이지 이동 요청(0부터). 범위 밖 요청은 가두고, 현재 페이지와 같으면 부르지 않는다.

### className

- **type**: string
- **required**: false
- **description**: 배치 조정용 추가 클래스.

## usage_example

목록/테이블 하단 페이지 전환에 사용. 표를 쓰는 화면은 표 아래에 이어붙이고, 표 없이 카드/리스트형 화면에서 독립적으로 직접 사용(게시판·프리셋 관리·사용자 관리·검수 목록·증강 결과). 제어형 컴포넌트 — page/totalPages 는 부모가 서버 응답으로 소유하고, 페이지 전환은 onChange 콜백으로만 통지한다.

## design_system_id

DS-001

## accessibility_notes

컨테이너는 nav 요소이며 aria-label='페이지네이션'. 현재 페이지 번호에는 aria-current='page' 를 붙인다. 이전·다음은 비활성 상태를 aria-disabled 로 알리고 포인터 동작을 막는다(자리는 유지). 말줄임 자리는 보조기술에 읽힐 필요가 없으므로 숫자 목록의 흐름을 깨지 않게 다룬다. 각 클릭 대상은 44px 이상의 터치 영역을 확보한다.

## referenced_by_screen_ids

- SCREEN-018
- SCREEN-023
- SCREEN-024
- SCREEN-026
- SCREEN-030
- SCREEN-008
- SCREEN-012
- SCREEN-022
- SCREEN-032


---

<!-- UI-009 -->

# navigation: Tabs

## name

Tabs

## tags

- common
- navigation
- tabs
- a11y

## category

navigation

## description

탭 네비게이션. items(value/label/disabled), 제어형 value/onChange, ArrowLeft/Right 키보드 이동(disabled 건너뜀), useId 기반 role=tablist/tab/tabpanel a11y. 선택 탭만 tabIndex=0(roving tabindex).

## props_schema

### items

- **type**: TabItem[]
- **required**: true

### value

- **type**: string
- **required**: true

### onChange

- **type**: (value:string)=>void
- **required**: true

### ariaLabel

- **type**: string
- **required**: false

### children

- **type**: ReactNode
- **required**: false
- **description**: tabpanel 내용

### className

- **type**: string
- **required**: false
- **description**: 탭 컨테이너에 추가할 CSS 클래스

## usage_example

한 화면 안에서 여러 뷰(예: 영상 상세의 정보/이력, 증강 결과의 영상별 구분)를 전환할 때 사용 — 라우팅 없이 같은 화면 내 콘텐츠만 교체한다. 탭이 1개 이하이거나 화면 자체를 이동해야 하는 경우에는 사용하지 않는다.

## design_system_id

DS-001

## accessibility_notes

role=tablist/tab/tabpanel, aria-selected, aria-controls, roving tabindex, 화살표 키 이동. 각 탭 min-h-11(44px) 터치 타깃.

## referenced_by_screen_ids

- SCREEN-009
- SCREEN-023


---

<!-- UI-010 -->

# display: KpiCard

## name

KpiCard

## tags

- common
- display
- kpi
- dashboard
- stat

## category

display

## description

KPI 지표 카드. label/value(number 는 ko-KR 천단위 콤마 포맷, string 은 그대로)/unit, trend(delta 부호+아이콘 TrendingUp/Down, 증가=success/감소=danger/0=회색 색상). 카드에 장식 아이콘을 두지 않는다 — 지표명과 값 텍스트가 카드의 정보를 모두 전달한다. onClick 지정 시 카드가 button 으로 렌더되어 클릭 가능해지고, selected 를 함께 주면 필터 토글형 카드로 동작해 aria-pressed 와 색상+테두리 두께로 선택 상태를 강조한다(selected 미지정 시 렌더·시맨틱은 클릭 전용 카드와 동일). 대시보드/통계/작업·검수 KPI 스트립에 여러 장을 나란히 배치해 사용.

## props_schema

### label

- **type**: ReactNode
- **required**: true
- **description**: 지표명 — 값 위에 작게 표시.

### value

- **type**: number|string
- **required**: true
- **description**: 지표값 — number 면 ko-KR 천단위 콤마 포맷 적용, string 은 그대로 표시.

### unit

- **type**: ReactNode
- **required**: false
- **description**: 값 옆에 붙는 단위(예: '건','장') — 라벨이 아니라 값의 일부로 취급해 값과 같은 줄에 표시.

### trend

- **type**: {delta:number,label?:string}
- **required**: false
- **description**: 증감 표시 — delta>0 은 TrendingUp 아이콘+success 색+'+' 부호, delta<0 은 TrendingDown 아이콘+danger 색, delta=0 은 회색(아이콘 없음).

### [폐기] icon

- **type**: ReactNode
- **required**: false
- **description**: 카드 우측 장식 아이콘을 두지 않는다. 지표명과 값 텍스트가 카드의 정보를 모두 전달하므로 아이콘은 시각적 잡음만 더한다.

### [폐기] iconBgClassName

- **type**: string
- **required**: false
- **description**: 장식 아이콘을 두지 않으므로 그 배경색 클래스도 두지 않는다.

### onClick

- **type**: () => void
- **required**: false
- **description**: 지정 시 카드를 <button> 으로 렌더해 클릭 가능하게 만든다.

### selected

- **type**: boolean
- **required**: false
- **description**: 필터 토글형 카드의 선택 상태. onClick 과 함께 쓸 때만 의미가 있으며 aria-pressed 와 강조 테두리(색상+두께)를 부여한다.

### data-testid

- **type**: string
- **required**: false
- **description**: 테스트/자동화 훅.

### className

- **type**: string
- **required**: false
- **description**: 루트 요소에 추가할 커스텀 클래스.

## usage_example

통계/대시보드/작업 목록/검수 목록 상단에 KPI 값을 요약해 여러 장 나란히 배치한다. 상태별 건수를 필터로도 쓰는 화면(예: 검수 목록의 '검수대기/검수중/승인/반려' 카드)은 onClick+selected 로 눌러서 필터를 토글하는 카드로 쓰고, 단순 요약만 필요한 화면은 onClick 을 생략해 일반 카드로 렌더한다.

## design_system_id

DS-001

## accessibility_notes

onClick 지정 시 실제 <button> 요소로 렌더돼 키보드 포커스·Enter/Space 활성화가 기본 제공된다. selected 를 함께 주면 aria-pressed 로 토글 버튼 시맨틱을 알리고, 색상뿐 아니라 테두리 두께로도 선택 상태를 구분해 색상 단독으로 정보를 전달하지 않는다.

## referenced_by_screen_ids

- SCREEN-018
- SCREEN-020


---

<!-- UI-011 -->

# layout: Card

## name

Card

## tags

- common
- layout
- container
- compound

## category

layout

## description

컴파운드 카드 컨테이너 — 루트(Card)는 셸만 제공하고 헤더·본문·푸터는 전용 하위 컴포넌트를 children으로 조합해 구성한다. Card 자체엔 title/description/actions/footer 같은 콘텐츠 prop이 없다. 구성 요소: CardHeader(헤더 영역 — CardAction이 있으면 자동으로 1fr/auto 2열 그리드로 전환), CardTitle(제목 — 일반 텍스트 요소이며 시맨틱 헤딩이 아니다), CardDescription(제목 아래 보조 설명, 톤 다운 텍스트), CardAction(헤더 우측 상단 액션 슬롯 — CardHeader의 2번째 grid column에 고정), CardContent(본문 영역), CardFooter(하단 구분선 + 옅은 배경의 캡션 영역 — 존재하면 Card 자체 하단 패딩이 0으로 줄어 Footer가 카드 바닥까지 맞닿는다). 각 하위 컴포넌트는 네이티브 컨테이너 props(className 포함)를 그대로 받으며 순서·존재 여부는 호출부가 결정한다 — 헤더 없이 CardContent만 두거나 Footer를 생략하는 등 자유 조합이 가능하다. size='sm'이면 카드 내부 여백 토큰이 한 단계 줄고 CardTitle 폰트도 한 단계 작아진다. 화면 내 그룹 박스에 광범위 사용.

## props_schema

### className

- **type**: string
- **required**: false
- **description**: 루트 컨테이너에 추가할 커스텀 클래스.

### size

- **type**: 'default'|'sm'
- **default**: default
- **required**: false
- **description**: 카드 내부 여백 프리셋. 'sm'이면 카드 내부 여백 토큰이 한 단계 줄고 CardTitle 폰트도 한 단계 작아진다.

## usage_example

화면 안의 한 섹션을 헤더/본문/푸터로 구성된 박스로 묶을 때 하위 컴포넌트를 조합해서 사용한다 — 예: CardHeader 안에 CardTitle과 CardDescription을 두고 우측에 CardAction으로 수정/삭제 버튼을 배치, CardContent에 본문 목록이나 칩을 나열, CardFooter에 개수·수정일 같은 캡션성 메타를 좌우로 배치한다(프리셋 카드가 이 조합의 전형). 헤더가 필요 없는 카드는 CardHeader 자체를 생략한다. 값 하나만 강조해 보여주는 KPI 스트립에는 이 컴포넌트 대신 KpiCard를 쓴다.

## design_system_id

DS-001

## accessibility_notes

CardTitle과 CardDescription 모두 일반 텍스트 요소로 렌더된다 — 시맨틱 헤딩(h1~h6)이 아니며 title과 컨테이너를 aria-labelledby로 연결하지도 않는다. 스크린리더의 헤딩·랜드마크 탐색으로 카드 제목이 자동 안내되지 않으므로, 그런 탐색이 필요한 화면은 호출부가 CardTitle 대신 직접 시맨틱 헤딩을 두는 등 별도 처리가 필요하다.

## referenced_by_screen_ids

- SCREEN-011
- SCREEN-019
- SCREEN-025
- SCREEN-026
- SCREEN-027


---

<!-- UI-012 -->

# layout: PageHeader

## name

PageHeader

## tags

- common
- layout
- header
- composed-from:Breadcrumb

## category

layout

## description

페이지 상단 헤더(<header>). breadcrumb 배열이 있으면 제목 위에 현재 위치 내비게이션(Breadcrumb)을 렌더한다 — 마지막 항목은 href 유무와 무관하게 링크 없이 aria-current='page' 로 표시된다. title(h1)과 옵션 description 문장, 우측 actions 버튼 영역으로 구성. 내부 화면 상단 공통 영역.

## props_schema

### title

- **type**: ReactNode
- **required**: true
- **description**: 페이지 제목(h1).

### description

- **type**: ReactNode
- **required**: false
- **description**: 제목 아래 보조 설명 문장.

### breadcrumb

- **type**: BreadcrumbItem[]
- **required**: false
- **description**: {label, href?}[] — 지정 시 제목 위에 현재 위치 내비게이션을 렌더한다. 마지막 항목은 href 유무와 무관하게 링크 없이 aria-current='page' 로 표시된다.

### actions

- **type**: ReactNode
- **required**: false
- **description**: 제목 우측 버튼 영역(새로고침·생성 등).

### className

- **type**: string
- **required**: false
- **description**: 루트 <header> 에 추가할 커스텀 클래스.

## usage_example

각 페이지 최상단에 1회만 배치한다. 목록/설정류 화면은 breadcrumb 로 상위 메뉴 경로를 보여주고, actions 에는 새로고침·생성 등 페이지 단위 버튼을 둔다.

## design_system_id

DS-001

## accessibility_notes

루트는 <header>, 제목은 <h1> 로 렌더돼 페이지당 랜드마크/제목 위계를 보장한다. breadcrumb 는 <nav aria-label='현재 위치'> 로 감싸이고 각 링크는 포커스 가능하며, 현재 페이지 항목만 aria-current='page' 로 표시되고 링크가 되지 않는다.

## referenced_by_screen_ids

- SCREEN-023
- SCREEN-024
- SCREEN-025
- SCREEN-027
- SCREEN-032


---

<!-- UI-013 -->

# navigation: Breadcrumb

## name

Breadcrumb

## tags

- common
- navigation
- breadcrumb

## category

navigation

## description

경로 브레드크럼. items(label/href), 마지막 항목 aria-current=page, 중간 항목 react-router Link, ChevronRight 구분자. nav aria-label=현재 위치.

## props_schema

### items

- **type**: BreadcrumbItem[]
- **required**: true
- **description**: {label, href?}[]

### className

- **type**: string
- **required**: false
- **description**: nav 요소에 추가할 CSS 클래스

## usage_example

PageHeader 의 선택적 breadcrumb prop 으로 전달해 헤더 상단에 노출한다. 마지막 항목은 href 를 지정해도 링크가 되지 않고 현재 위치(aria-current=page)로만 표시된다.

## design_system_id

DS-001

## accessibility_notes

nav aria-label=현재 위치, 마지막 aria-current=page, 구분자 aria-hidden. 목록은 <ol> 순서목록으로 구조화된다.

## referenced_by_screen_ids

- SCREEN-023


---

<!-- UI-014 -->

# display: StatusBadge

## name

StatusBadge

## tags

- common
- display
- badge
- status

## category

display

## description

작업/배치 상태 배지. 16종 상태(BATCH_PROCESSING/BATCH_COMPLETED/BATCH_FAILED/PENDING/MARKING_READY/IN_PROGRESS/PROCESSING/REVIEW_PENDING/REVIEWING/IN_REVIEW/COMPLETED/APPROVED/REJECTED/FAILED/DEIDENT_IN_PROGRESS/DEIDENT_FAILED)별 한글 라벨 + 톤(색) 매핑. 매핑에 없는 코드(BE alias 등)는 회색 톤으로 폴백하고 라벨은 status 원문을 그대로 보여준다. 배지에 아이콘을 두지 않는다 — 상태는 한글 라벨 텍스트가 전달하고 색은 보조 수단이다. data-status 속성으로 원본 상태값을 노출한다.

## props_schema

### status

- **type**: BadgeStatus|string
- **required**: true
- **description**: 표시할 상태 코드 — 매핑에 없는 값은 원문을 그대로 라벨로 쓰고 회색 폴백 톤이 적용된다.

### label

- **type**: string
- **required**: false
- **description**: 기본 매핑 라벨을 덮어쓸 텍스트.

### className

- **type**: string
- **required**: false
- **description**: 루트 <span> 에 추가할 커스텀 클래스.

## usage_example

작업 목록/영상 상태/배치 처리 현황 등 워크플로 상태를 보여주는 모든 목록·상세 화면에 쓴다. 배치 파이프라인의 개별 처리 단계(FRAME_EXTRACT/YOLO/SAM2 등)를 보여줄 때는 이 컴포넌트 대신 StageBadge 를 쓴다.

## design_system_id

DS-001

## accessibility_notes

상태를 색상과 텍스트 두 축으로 표현하되 정보 전달은 병기된 한글 라벨 텍스트가 담당해, 색상만으로 정보를 구분하지 못하는 사용자도 상태를 알 수 있다(KRDS 색상 단독 구분 금지).

## referenced_by_screen_ids

- SCREEN-007
- SCREEN-009
- SCREEN-011
- SCREEN-018
- SCREEN-019
- SCREEN-023
- SCREEN-024
- SCREEN-025
- SCREEN-032


---

<!-- UI-015 -->

# [폐기] display: PrivacyBadge

## name

PrivacyBadge

## tags

- common
- display
- badge
- privacy
- deprecated

## category

display

## description

[폐기] 영상 목록·상세에는 개인정보 처리 등급(PRVC/PSDO/ANONY)을 배지로 표시하지 않기로 한다. 이 판정값을 공급하는 외부 원천이 없어 화면에 표시할 근거가 없기 때문이다 — 개인정보 처리 등급 판정을 신뢰성 있게 공급하는 원천이 확보되기 전까지는 이 배지를 두지 않는다.

## props_schema

_(empty)_

## usage_example

개인정보 표시가 필요한 화면은 이 배지를 참조하지 않는다. 라벨링 화면의 개인정보 메타 입력(사람이 직접 입력하는 값)은 이 결정과 무관한 별개 축이며 계속 유지된다.

## design_system_id

DS-001

## accessibility_notes




---

<!-- UI-016 -->

# display: EventTypeBadge

## name

EventTypeBadge

## tags

- common
- display
- badge
- event

## category

display

## description

SFR 6종 이벤트(쓰러짐/폭력/교통사고/이상행동(유괴)/침수/산불) 및 그 외 세부 유형을 표시하는 색상 배지. EV-코드(상세 코드, 예 EV01000102) 또는 이미 해석된 한글 라벨을 받아 EV-코드→라벨 맵으로 표시 라벨을 결정한다 — categoryKey(그룹 조회용 키)는 이 배지에 직접 넘기지 않는다(맵에 없어 원문이 그대로 노출된다). 색상은 해석된 한글 라벨 기준 매핑(미매핑 라벨은 회색 폴백)이며 상태 구분이 아니라 보조 표시다. 배지에 아이콘을 두지 않는다 — 이벤트 유형은 라벨 텍스트가 전달한다. eventType 이 빈 문자열이면 빈 배지를 유지한다.

## props_schema

### eventType

- **type**: string|EventTypeCd
- **required**: true
- **description**: EV-코드(상세 코드) 또는 이미 해석된 한글 라벨. categoryKey 는 지원하지 않는다.

### size

- **type**: 'sm'|'md'
- **default**: sm
- **required**: false
- **description**: sm=14px, md=17px(ladder 상 큰 label 스텝이 없어 크기를 보존하는 body-md 사용).

### className

- **type**: string
- **required**: false
- **description**: 루트 <span> 에 추가할 커스텀 클래스.

## usage_example

영상 목록/작업 상세/검수 목록 등에서 이벤트 유형을 보여줄 때 사용한다. 화면에 이벤트 유형 필터가 있는 경우 필터 파라미터는 이 배지가 아니라 이벤트 코드 축으로 별도 관리한다(표시명이 같은 유형코드는 필터에서 하나로 묶이지만 배지는 실제 코드로 해석된 라벨을 그대로 보여준다).

## design_system_id

DS-001

## accessibility_notes

색상은 카테고리 구분의 보조 수단일 뿐이며 라벨 텍스트를 항상 함께 표시해 색상 단독으로 정보를 전달하지 않는다. 라벨은 텍스트 노드로만 렌더되어 XSS 를 방지한다.

## referenced_by_screen_ids

- SCREEN-007
- SCREEN-008
- SCREEN-009
- SCREEN-011
- SCREEN-018
- SCREEN-022


---

<!-- UI-017 -->

# display: StageBadge

## name

StageBadge

## tags

- common
- display
- badge
- batch
- stage

## category

display

## description

배치 파이프라인 단계 배지. stage(FRAME_EXTRACT/DEIDENTIFY/YOLO/SAM2/VLM_VERIFY또는 VLM/COMPLETED/FAILED) + status 조합으로 한글 라벨/톤을 결정한다 — 완료(COMPLETED|DONE)=초록, 실패(FAILED|FAIL)=빨강, VLM 단계=보라(특수 단계 강조용이며 상태 의미가 아님), 진행중(IN_PROGRESS|PROGRESS)=파랑, 그 외(대기 등)=노랑. 배지에 아이콘을 두지 않는다 — 단계와 상태는 한글 라벨 텍스트가 전달한다. STAGE_LABEL 매핑에 없는 stage 코드는 원문 코드를 그대로 라벨로 폴백한다. 기술 모델명 노출 금지 정책에 따라 YOLO→'AI 탐지', SAM2→'AI 분할', VLM·VLM_VERIFY→'시계열'로 표시한다(코드 자체는 유지).

## props_schema

### stage

- **type**: string
- **required**: true
- **description**: 처리 단계 코드. 매핑에 없으면 원문 코드를 그대로 라벨로 표시한다.

### status

- **type**: string
- **required**: false
- **description**: 단계 상태(COMPLETED|DONE / FAILED|FAIL / IN_PROGRESS|PROGRESS 등) — stage 와 함께 톤을 결정한다. 미지정 시 대기 톤(노랑)으로 폴백한다.

### size

- **type**: 'sm'|'md'
- **default**: sm
- **required**: false
- **description**: sm=14px, md=17px.

### className

- **type**: string
- **required**: false
- **description**: 루트 <span> 에 추가할 커스텀 클래스.

## usage_example

영상 목록·상세의 처리 단계 컬럼에서 사용한다. 실제 화면에서는 처리 단계가 완료(COMPLETED)·실패(FAILED)로 종료된 경우에만 이 배지를 쓰고, 진행 중인 세부 단계는 StatusBadge 로 표시하는 화면 구성이 일반적이다(비식별 진행 상태처럼 더 우선순위 높은 상태가 있으면 그 상태 배지가 처리 단계 배지보다 앞선다).

## design_system_id

DS-001

## accessibility_notes

톤(색)과 같은 의미를 담은 한글 라벨 텍스트를 항상 병기해 색상만으로 단계 상태를 구분하지 않는다.

## referenced_by_screen_ids

- SCREEN-007
- SCREEN-008


---

<!-- UI-018 -->

# display: BatchStageIndicator

## name

BatchStageIndicator

## tags

- common
- display
- batch
- stepper

## category

display

## description

배치 파이프라인 진행 스테퍼. stages(단계 코드+상태+진행률) 배열을 BE 가 내려주는 순서 그대로 렌더한다(FE 는 순서를 가정하지 않음) — DEIDENTIFY(비식별)→MARKING(마킹)→VLM(시계열)→FRAME_EXTRACT(프레임추출)→YOLO(AI 탐지)→SAM2(AI 분할)→INTERPOLATE(보간) 7단계가 실제 운영 순서다. 다만 스테퍼는 이 7단계를 그대로 7칸으로 그리지 않는다 — 오토라벨에 해당하는 마지막 세 단계(AI 탐지 · AI 분할 · 트랙 보간)를 오토라벨링 한 칸으로 접어 5칸으로 보여준다. 표시 단위를 조작 단위와 일치시키기 위함이다: 재수행·건너뛰기가 오토라벨 묶음 단위로만 동작하는데 세 칸으로 나뉘어 보이면 각 칸을 따로 조작할 수 있다고 읽힌다. 접는 것은 표시 층뿐이며 서버가 내려주는 단계 목록과 응답 계약은 그대로다. 접은 칸의 상태는 세 단계를 합쳐 판정한다 — 하나라도 실패면 실패, 실패가 없고 하나라도 진행 중이면 진행 중, 셋 다 끝났으면 완료다. 세부 단계는 그 칸에 보조 표기로 작게 병기해 진행 해상도를 잃지 않는다 — 진행 중이면 지금 어느 세부 단계인지, 실패면 어느 세부 단계에서 실패했는지를 적는다. 이 보조 표기는 글자이며 백분율 진행률 바가 아니다. 단계별 상태(DONE 초록/PROGRESS 파랑/FAIL 빨강/PENDING 회색)를 원형 점 + 연결선 색으로 순차로 보여준다. 스텝에 아이콘을 두지 않는다 — 대신 각 스텝 캡션에 단계명과 상태를 함께 적어(예: 비식별 완료 / 프레임추출 실패) 색상 단독으로 상태를 구분하지 않게 한다. 점은 상태와 무관하게 모양·크기가 같으므로 캡션이 색을 대신하는 유일한 구분 수단이다. 기술 모델명 노출 금지 정책에 따라 YOLO/SAM2/VLM 단계는 화면에 'AI 탐지'/'AI 분할'/'시계열'로만 표시된다. stages 가 비어 있으면(배치 로그 없는 기존 영상) 아무것도 렌더하지 않아 상위 화면이 다른 상태 표시로 폴백해야 한다. 영상 처리 현황 화면에 사용.

## props_schema

### stages

- **type**: BatchStageItem[]
- **required**: true
- **description**: {name:string, status:'DONE'|'PROGRESS'|'PENDING'|'FAIL', progress:number|null}[] — BE 가 canonical 순서/상태로 내려주는 단계 목록을 그대로 받는다 — 오토라벨 세 단계를 한 칸으로 접는 것은 이 배열을 받은 뒤의 표시 처리이고 배열 자체는 접지 않는다. progress 는 계약상 함께 내려오지만 백분율 진행률 바로 표시하지 않는다.

## usage_example

영상 처리 현황(영상 상세, 마킹/작업 목록 확장 행 등)에서 배치 파이프라인 진행 상황을 시각화할 때 사용한다. stages 가 없는 기존 영상에서는 아무것도 렌더되지 않으므로 상위 화면이 StatusBadge 등으로 폴백 표시를 준비해야 한다. 진행 중이거나 대기 중인 단계가 남아 있고 실패한 단계가 없으며 영상이 최종 완료 상태가 아니면 상위 화면이 주기적으로 상세를 다시 조회해 단계 표시를 갱신한다 — 실패로 멈췄거나 모든 단계가 끝났으면 다시 조회하지 않는다.

## design_system_id

DS-001

## accessibility_notes

각 스텝 캡션에 단계명과 상태를 함께 표시해 색상만으로 상태를 구분하지 않는다 — 점의 모양·크기가 상태별로 같아서, 색을 읽지 못하면 캡션이 유일한 구분 수단이 되기 때문이다(특히 완료 초록과 실패 빨강은 적록색약에서 구분되지 않는다). 진행 중인 단계를 스크린리더에도 안내하려면 화면에는 보이지 않는 aria-live="polite" 영역으로 현재 단계명 + 상태 문구(예: 비식별 진행 중)를 함께 알린다. 오토라벨링처럼 여러 세부 단계를 한 칸으로 접은 경우에도 캡션은 같은 규칙을 따라 묶음 이름과 상태를 함께 적고(예: 오토라벨링 실패), 어느 세부 단계인지 알리는 보조 표기도 색이 아니라 글자로 적는다.

## referenced_by_screen_ids

- SCREEN-006
- SCREEN-009


---

<!-- UI-019 -->

# feedback: ProgressBar

## name

ProgressBar

## tags

- common
- feedback
- progress

## category

feedback

## description

진행률 바. value(0-100 자동 클램핑, 숫자가 아니거나 무한대면 0으로 처리), tone(primary/success/warning/danger), size(sm/md), showLabel(% 표기). role=progressbar aria-valuenow. 진행률(%)이 명확한 결정론적 작업(라벨링/검수 진행률, 증강 재처리 진행률, 대시보드 KPI)에 사용한다 — 진행률을 알 수 없는 대기는 Spinner, 콘텐츠 도착 전 자리표시는 Skeleton을 사용한다.

## props_schema

### value

- **type**: number
- **required**: true
- **description**: 0-100

### tone

- **type**: 'primary'|'success'|'warning'|'danger'
- **default**: primary
- **required**: false

### size

- **type**: 'sm'|'md'
- **default**: md
- **required**: false

### showLabel

- **type**: boolean
- **required**: false

### className

- **type**: string
- **required**: false
- **description**: 외곽 컨테이너에 추가할 CSS 클래스

## usage_example

AugmentProgressPanel 은 재처리 실패 시 tone=danger, 그 외에는 tone=primary(기본값)로 상태에 따라 tone 을 바꿔 쓰는 식으로 사용한다. success/warning 은 타입에는 존재하나 현재 실사용이 확인되지 않는다.

## design_system_id

DS-001

## accessibility_notes

role=progressbar, aria-valuenow/min/max.

## referenced_by_screen_ids

- SCREEN-011
- SCREEN-023


---

<!-- UI-020 -->

# feedback: EmptyState

## name

EmptyState

## tags

- common
- feedback
- empty

## category

feedback

## description

빈 상태 플레이스홀더. title/message, 기본 Inbox 아이콘(교체 가능), 선택적 action 버튼(outline). role=status. 목록/테이블 조회 결과가 0건일 때 사용한다 — 조회 자체가 실패한 경우(에러)는 ErrorState 를 사용하고 EmptyState 로 대체하지 않는다.

## props_schema

### title

- **type**: ReactNode
- **required**: false

### message

- **type**: ReactNode
- **default**: 데이터가 없습니다
- **required**: false

### icon

- **type**: ReactNode
- **required**: false

### action

- **type**: {label:string,onClick:()=>void}
- **required**: false

### className

- **type**: string
- **required**: false
- **description**: 외곽 컨테이너에 추가할 CSS 클래스

## usage_example

DataTable 은 행 데이터가 0건이면 내부적으로 EmptyState 를 렌더한다(emptyMessage prop 으로 문구 교체 가능). 버전 diff 처럼 '변경 없음'을 알릴 때도 title/message 를 재정의해 재사용한다(예: title="변경 없음", message="이 버전 이후 변경된 라벨이 없습니다.").

## design_system_id

DS-001

## accessibility_notes

role=status, 아이콘 aria-hidden.

## referenced_by_screen_ids

- SCREEN-007
- SCREEN-010
- SCREEN-012
- SCREEN-022
- SCREEN-030
- SCREEN-032


---

<!-- UI-021 -->

# feedback: ErrorState

## name

ErrorState

## tags

- common
- feedback
- error

## category

feedback

## description

에러 상태 플레이스홀더. title(기본 "문제가 발생했습니다")/message(기본 "잠시 후 다시 시도해주세요"), AlertTriangle 아이콘, 선택적 onRetry 버튼(있을 때만 노출). role=alert. 조회 실패를 빈 결과(EmptyState)로 보여주지 않기 위한 전용 컴포넌트 — 데이터 0건과 조회 실패는 항상 구분해서 표시한다.

## props_schema

### title

- **type**: ReactNode
- **default**: 문제가 발생했습니다
- **required**: false

### message

- **type**: ReactNode
- **default**: 잠시 후 다시 시도해주세요
- **required**: false

### onRetry

- **type**: () => void
- **required**: false

### retryLabel

- **type**: string
- **default**: 다시 시도
- **required**: false

### className

- **type**: string
- **required**: false
- **description**: 외곽 컨테이너에 추가할 CSS 클래스

## usage_example

쿼리 에러를 화면 인라인 배너로 표시(목록 상단에 띄우고 테이블은 그대로 유지) 또는 잘못된 파라미터 등으로 화면 자체를 렌더할 수 없을 때 페이지 전체를 이 컴포넌트로 대체. 버전 비교 패널처럼 '미선택 → 로딩(Spinner) → 에러(ErrorState) → 결과' 순으로 분기하는 화면에서 에러 단계를 담당한다.

## design_system_id

DS-001

## accessibility_notes

role=alert, 아이콘 aria-hidden.

## referenced_by_screen_ids

- SCREEN-007
- SCREEN-009
- SCREEN-011
- SCREEN-012
- SCREEN-018
- SCREEN-019
- SCREEN-020
- SCREEN-022
- SCREEN-023
- SCREEN-024


---

<!-- UI-022 -->

# feedback: LoadingOverlay

## name

LoadingOverlay

## tags

- common
- feedback
- loading

## category

feedback

## description

로딩 오버레이. visible 시 반투명 배경(bg-white/70) + Spinner(size=lg) + message, fullscreen(fixed, 화면 전체 차단) 또는 컨테이너(absolute, 특정 영역만 차단) 모드. role=status aria-live=polite. visible=false 이면 렌더 자체를 생략한다.

## props_schema

### visible

- **type**: boolean
- **required**: true
- **description**: false면 렌더 자체를 생략한다

### message

- **type**: ReactNode
- **default**: 로딩 중
- **required**: false

### fullscreen

- **type**: boolean
- **required**: false
- **description**: true=fixed(화면 전체 차단), false(기본)=absolute(특정 영역만 차단)

### className

- **type**: string
- **required**: false
- **description**: 오버레이 요소에 추가할 CSS 클래스

## usage_example

특정 영역(폼 제출·업로드 처리 등) 위에 반투명 오버레이로 로딩을 표시하도록 설계된 공용 컴포넌트.

## design_system_id

DS-001

## accessibility_notes

role=status aria-live=polite.


---

<!-- UI-023 -->

# feedback: Toast

## name

Toast

## tags

- common
- feedback
- toast

## category

feedback

## variants

### success

- **description**: 성공(저장·승인·완료 등). sr-only 라벨 "성공: "

### error

- **description**: 오류(요청 실패 등). sr-only 라벨 "오류: "

### warning

- **description**: 경고(주의 필요한 상황). sr-only 라벨 "경고: "

### info

- **description**: 안내(단순 정보 전달). sr-only 라벨 "안내: "

## description

토스트 알림 단일 아이템. variant(success/error/warning/info)별 색 + sr-only 라벨, durationMs(기본 5000ms) 후 자동 dismiss — 수동으로 닫는 버튼은 없다. ToastProvider/useUiStore(pushToast/dismissToast)와 함께 사용. role=alert.

## props_schema

### id

- **type**: string
- **required**: true

### variant

- **type**: 'success'|'error'|'warning'|'info'
- **required**: true

### message

- **type**: string
- **required**: true

### durationMs

- **type**: number
- **default**: 5000
- **required**: false

### onDismiss

- **type**: (id:string)=>void
- **required**: true

## usage_example

ToastProvider 를 앱 최상위에 마운트하고, 화면 어디서든 useUiStore().pushToast({variant, message}) 로 추가한다. 여러 토스트는 화면 우상단에 스택으로 쌓인다. Toast 컴포넌트 자체를 화면에서 직접 렌더하지 않는다.

## design_system_id

DS-001

## accessibility_notes

role=alert aria-live=polite, variant 라벨 sr-only. 색상만으로 구분하지 않고 variant별 아이콘 + sr-only 라벨을 병기한다.


---

<!-- UI-024 -->

# input: Checkbox

## name

Checkbox

## tags

- common
- input
- form

## category

input

## description

공통 체크박스 프리미티브 — checked(boolean|'indeterminate')·onCheckedChange 로 제어하는 3상태 체크박스. label 을 자체적으로 갖지 않는 순수 컨트롤이며, 라벨 텍스트는 호출부가 옆에 별도 label 요소(또는 Field 계열 FieldLabel)로 배치한다. 선택 시 체크 아이콘 인디케이터를 표시한다.

## props_schema

### checked

- **type**: boolean | 'indeterminate'
- **required**: false
- **description**: 제어값 — 'indeterminate' 전달 시 부분선택 시각 상태(예: 표 전체선택 체크박스의 일부선택)를 표현한다.

### onCheckedChange

- **type**: (checked: boolean | 'indeterminate') => void
- **required**: false
- **description**: 값 변경 콜백.

### disabled

- **type**: boolean
- **required**: false

### aria-invalid

- **type**: boolean
- **required**: false
- **description**: 보더·링을 destructive 로 전환. 오류 문구는 렌더링하지 않음.

### id

- **type**: string
- **required**: false
- **description**: 외부 label 의 htmlFor 대상.

## usage_example

다중 선택 목록(객체 속성 CHECKBOX 타입 값 입력, 해상도 프리셋 다중 선택, 표 전체선택 등)에 쓴다. 라벨은 이 컴포넌트가 아니라 옆에 별도 label 요소 또는 Field 계열(UI-099)의 FieldLabel 로 붙인다. 전체선택 헤더 체크박스처럼 일부만 선택된 상태는 checked='indeterminate' 로 표현한다(별도 indeterminate prop 없음). 단일 선택은 RadioGroup(UI-026)을 쓴다.

## design_system_id

DS-001

## accessibility_notes

44px 터치 히트영역은 이 컴포넌트가 아니라 감싸는 외부 label 요소가 보장한다 — 시각 크기(정사각 5)와 히트영역 확보를 분리 책임진다. checked='indeterminate' 는 aria-checked='mixed' 로 매핑되어 스크린리더가 부분선택을 인지한다. aria-invalid 전달 시 보더·링 색만 즉시 전환되고, 오류 문구의 aria-describedby 연결은 Field 계열 조립부가 자동으로 맺는다(호출부가 직접 지정한 값이 있으면 그 값이 우선한다). 네이티브 button 기반이라 Space 키 토글은 브라우저 기본 동작.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-022
- SCREEN-030
- SCREEN-031


---

<!-- UI-025 -->

# input: Radio

## name

Radio

## tags

- common
- input
- form

## category

input

## description

공통 라디오 입력 프리미티브. RadioGroup 의 빌딩 블록. name/value/label/checked/disabled.

## props_schema

### name

- **type**: string
- **required**: false
- **description**: 네이티브 radio input 의 name. 같은 그룹으로 묶이려면 동일한 name 을 공유해야 함(RadioGroup 이 자동 주입).

### value

- **type**: string
- **required**: false
- **description**: 네이티브 radio input 의 value.

### label

- **type**: ReactNode
- **required**: false
- **description**: 우측 표시 라벨. 미지정 시 히트영역이 정사각(44px) 으로 가드된다.

### checked

- **type**: boolean
- **required**: false
- **description**: 제어 컴포넌트 사용 시 선택 여부.

### disabled

- **type**: boolean
- **required**: false
- **description**: 비활성화 여부.

## usage_example

RadioGroup 이 개별 옵션을 렌더할 때 쓰는 빌딩 블록. 단독 사용도 가능하나 그룹 접근성(role=radiogroup, 그룹 라벨)이 필요하면 RadioGroup 을 쓴다.

## design_system_id

DS-001

## accessibility_notes

라벨을 44px 히트영역으로 감싸 KRDS 터치 타겟 기준을 충족(라벨 없으면 정사각 44px 가드), 네이티브 radio 이라 같은 name 을 공유하면 브라우저가 그룹으로 인식해 화살표 키 이동을 기본 제공, 포커스링은 KRDS 공통 스타일.

## referenced_by_screen_ids

- SCREEN-002
- SCREEN-005
- SCREEN-027


---

<!-- UI-026 -->

# input: RadioGroup

## name

RadioGroup

## tags

- common
- input
- form
- composed-from:RadioGroupItem

## category

input

## description

라디오 그룹 프리미티브 — RadioGroup(루트)·RadioGroupItem(개별 옵션) 2개로 구성된다. value/onValueChange 로 제어하는 단일 선택, name 은 폼 제출용이다. 옵션 배열을 컴포넌트에 통째로 넘기는 단일 prop 은 두지 않고 호출부가 옵션마다 RadioGroupItem 을 직접 배치한다. 방향(가로/세로)은 전용 orientation prop 이 아니라 호출부가 className 으로 grid/flex 레이아웃을 지정한다(기본은 세로 grid). label/error 는 이 컴포넌트가 갖지 않는다. 라벨·설명·오류 문구는 이 프리미티브를 감싸는 표시 구조 래퍼(Field, UI-099)가 소유하며, 그 래퍼 안에 놓이면 그룹 이름과 오류 안내가 자동으로 연결된다. 래퍼 밖에서 쓰면 호출부가 aria-label 로 그룹 이름을 직접 지정한다.

## props_schema

### RadioGroup.value

- **type**: string
- **required**: false
- **description**: 제어값.

### RadioGroup.onValueChange

- **type**: (value:string)=>void
- **required**: false

### RadioGroup.defaultValue

- **type**: string
- **required**: false
- **description**: 비제어 모드 초기값.

### RadioGroup.name

- **type**: string
- **required**: false
- **description**: 폼 제출용 name.

### RadioGroup.disabled

- **type**: boolean
- **required**: false
- **description**: 그룹 전체 비활성화. 개별 옵션의 disabled 와 OR 조건으로 적용된다.

### RadioGroup.className

- **type**: string
- **required**: false
- **description**: 레이아웃 방향 지정 — 기본은 세로(grid gap-2), 가로로 배치하려면 호출부가 'flex gap-N' 등으로 덮어쓴다(전용 orientation prop 없음).

### RadioGroupItem.value

- **type**: string
- **required**: true
- **description**: 옵션 값.

### RadioGroupItem.disabled

- **type**: boolean
- **required**: false
- **description**: 개별 옵션 비활성화 — 그룹 disabled 와 OR.

## usage_example

증강 요청 화면의 처리 종류 단일 선택, AI 탐지 형태(바운딩박스/폴리곤) 단일 선택, 객체 속성(RADIO 타입) 단일 선택 등 소수 옵션을 한눈에 비교해야 하는 단일 선택 폼 필드에 쓴다. 옵션 라벨은 RadioGroupItem 옆에 호출부가 직접 배치한 label(htmlFor 연결) 또는 Field 계열(UI-099)로 붙인다. 그룹 제목은 네이티브 fieldset+legend 또는 aria-label 로 붙인다(전용 label prop 없음).

## design_system_id

DS-001

## accessibility_notes

role=radiogroup 은 컴포넌트가 자동 부여하지만, 그룹 제목은 호출부가 fieldset+legend 또는 aria-label 로 직접 연결해야 한다(컴포넌트 자체엔 label prop 없음). RadioGroupItem 은 각각 htmlFor 대상 id 를 받아 외부 label 과 연결. aria-invalid 전달 시 개별 항목 보더가 destructive 로 전환되며, 표시 구조 래퍼(Field, UI-099) 안에 놓이면 오류 문구가 aria-describedby 로 자동 연결되고 그 래퍼 밖에서 쓰면 호출부가 직접 연결한다. 포커스 시 2px 링 + 오프셋 2px.

## referenced_by_screen_ids

- SCREEN-022
- SCREEN-005


---

<!-- UI-027 -->

# input: Textarea

## name

Textarea

## tags

- common
- input
- form

## category

input

## description

공통 멀티라인 텍스트 입력 프리미티브 — label/hint/error 를 자체적으로 두지 않는 순수 네이티브 textarea 다. field-sizing:content 로 입력 내용에 따라 높이가 자동으로 늘어나며 최소 높이 이하로는 줄지 않는다. 고정 rows 기본값을 두지 않고(자동 크기조절이 기본 동작) 호출부가 필요 시 className 으로 최소 높이를 재지정한다.

## props_schema

### aria-invalid

- **type**: boolean
- **required**: false
- **description**: 보더·링을 destructive 로 전환. 오류 문구는 렌더링하지 않음.

### disabled

- **type**: boolean
- **required**: false

### className

- **type**: string
- **required**: false
- **description**: 기본 최소높이를 덮어쓸 때 사용(예: 공지 내용 입력의 더 큰 최소높이).

## usage_example

반려 사유, 검수 메모·이슈 스레드 댓글, 공지 내용, 프레임 설명·이벤트 주석·시계열 서술 등 여러 줄 텍스트 입력에 쓴다. 한 줄 입력은 Input(UI-002)을 쓴다(대체 금지). 라벨·설명·오류 문구는 Field 계열(UI-099)로 감싸 조립한다 — 이 컴포넌트 자체는 label/hint/error prop 을 받지 않는다.

## design_system_id

DS-001

## accessibility_notes

라벨 연결·오류 안내는 이 컴포넌트가 아니라 Field 계열 조립부(UI-099)가 담당한다. aria-invalid 전달 시 보더·링 색만 즉시 전환되고, 확인된 사용처 전체에서 aria-describedby 를 통한 오류 문구의 프로그램적 연결은 존재하지 않는다. 포커스 시 2px 보더. field-sizing:content 로 스크롤 없이 입력 내용 전체가 보이도록 자동 확장되어 화면 확대 사용 시 컨텍스트 손실을 줄인다.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-019
- SCREEN-023
- SCREEN-026
- SCREEN-027
- SCREEN-030
- SCREEN-031


---

<!-- UI-028 -->

# input: DatePicker

## name

DatePicker

## tags

- common
- input
- form
- date

## category

input

## description

날짜 선택 — 팝오버로 여는 달력(Calendar) + 하단 액션바(오늘/취소/확인) 조합. 트리거는 버튼(선택값 또는 placeholder 텍스트 + 달력 아이콘)이며, 팝오버를 열면 현재 확정값을 임시 선택값(초안)으로 복사해 시작한다 — 달력에서 날짜를 클릭해도 그 자리에서 즉시 반영되지 않고 초안만 바뀐다. '확인'을 눌러야 value/onChange 로 확정값이 커밋되고 팝오버가 닫히며, '취소'를 누르면 초안을 버리고 이전 확정값을 유지한 채 닫힌다. '오늘' 버튼은 초안을 오늘 날짜로 세팅할 뿐 커밋하지 않으며, min/max 범위 밖이면 비활성화된다. value/onChange 는 YYYY-MM-DD 문자열이다.

## props_schema

### value

- **type**: string
- **required**: false
- **description**: 확정된 선택값(ISO YYYY-MM-DD). 빈 문자열은 미선택.

### onChange

- **type**: (next:string)=>void
- **required**: false
- **description**: 확인 버튼 클릭 시에만 호출된다(초안 상태 변경으로는 호출되지 않음).

### placeholder

- **type**: string
- **default**: 날짜 선택
- **required**: false

### min

- **type**: string
- **required**: false
- **description**: 선택 가능 최소일(YYYY-MM-DD) — 이전 날짜는 달력에서 비활성.

### max

- **type**: string
- **required**: false
- **description**: 선택 가능 최대일(YYYY-MM-DD) — 이후 날짜는 달력에서 비활성.

### id

- **type**: string
- **required**: false
- **description**: 트리거 버튼 id.

## usage_example

단일 날짜 값(기간 필터의 시작일/종료일 등)을 입력받는 폼 필드에 쓴다. 시작일·종료일처럼 한 쌍을 받아야 하면 이 컴포넌트를 두 번 배치한다(전용 범위 선택 모드는 없음). 확인을 눌러야 값이 바뀌므로 실시간 미리보기가 필요한 곳에는 적합하지 않다.

## design_system_id

DS-001

## accessibility_notes

트리거는 버튼 요소라 Tab 으로 도달·Enter/Space 로 팝오버를 열 수 있다. 팝오버가 열리면 달력에 자동 포커스되고, 방향키로 날짜 이동은 달력 컴포넌트의 기본 키보드 내비게이션을 따른다. '오늘' 버튼은 min/max 범위 밖일 때 disabled 로 시각·스크린리더 모두에 비활성 상태를 전달한다. 확정 전 상태 변경(달력 클릭·오늘 클릭)이 값에 아무 영향을 주지 않아 실수로 인한 값 변경 위험이 낮다(확인을 눌러야 커밋).

## referenced_by_screen_ids

- SCREEN-008


---

<!-- UI-029 -->

# input: DateRangePicker

## name

DateRangePicker

## tags

- common
- input
- form
- date
- composed-from:DatePicker

## category

input

## description

기간(from~to) 선택. DatePicker 2개 조합, from/to 상호 min/max 제약. role=group. 통계/목록 기간 필터에 사용.

## props_schema

### value

- **type**: DateRange
- **required**: false
- **description**: {from?,to?}

### onChange

- **type**: (value:DateRange)=>void
- **required**: false

### label

- **type**: string
- **required**: false

### fromLabel

- **type**: string
- **default**: 시작일
- **required**: false

### toLabel

- **type**: string
- **default**: 종료일
- **required**: false

### error

- **type**: string
- **required**: false

### showLocalizedDisplay

- **type**: boolean
- **default**: true
- **required**: false
- **description**: 두 날짜 입력 아래에 붙는 한국어 날짜 병기 표시 여부. 기본은 표시한다. 한 줄 필터 바처럼 값이 들어올 때 병기 줄만큼 블록 높이가 자라 같은 줄의 다른 컨트롤과 밑선이 어긋나는 배치에서 끈다. 병기는 입력값을 다시 말해 주는 보조 표시라, 꺼도 값·라벨·상호 min/max 제약 계약은 그대로다.

## usage_example

통계·목록 화면의 기간 필터(조회 시작일~종료일) 등 두 날짜를 한 쌍으로 입력받는 폼 필드에 쓴다.

## design_system_id

DS-001

## accessibility_notes

role=group 으로 두 입력을 하나로 묶고(label 지정 시 aria-labelledby 로 그룹에 연결), 좌우 DatePicker 는 각각 fromLabel/toLabel 을 label 로 받아 개별 htmlFor 연결, 두 필드 사이 '~' 구분자는 aria-hidden 처리, error 시 role=alert 텍스트.


---

<!-- UI-030 -->

# [폐기] input: FormField

## name

FormField

## tags

- form
- react-hook-form
- wrapper
- deprecated

## category

input

## description

[폐기] 폼 값 바인딩만 담당하는 별도 래퍼는 두지 않는다. 폼 필드는 라벨·설명·오류 문구를 바깥에서 조립하는 표시 구조 래퍼(Field, UI-099)로 구성하고, 값 바인딩은 각 입력 프리미티브가 폼 라이브러리의 필드 등록 함수를 직접 받아 처리한다. 값 바인딩 전용 래퍼를 함께 두면 하나의 필드를 감싸는 래퍼가 둘이 되어 접근성 연결(htmlFor·aria-describedby·aria-invalid)을 어느 쪽이 책임지는지가 갈린다.

## props_schema

### name

- **type**: FieldPath<T>
- **required**: true
- **description**: react-hook-form 필드 경로(register 키).

### control

- **type**: Control<T>
- **required**: true
- **description**: useForm() 이 반환하는 control 객체.

### render

- **type**: (props)=>ReactElement
- **required**: false

### children

- **type**: ReactElement
- **required**: false

### defaultValue

- **type**: unknown
- **required**: false

## usage_example

[폐기] 이 래퍼는 쓰지 않는다. 시각적 라벨·설명·오류 배치와 그 접근성 연결은 Field(UI-099)가 담당하고, 값 바인딩은 입력 프리미티브가 폼 라이브러리의 필드 등록 함수를 직접 받아 처리한다. 값을 직접 주입받아야 하는 비-네이티브 위젯이라도 이 래퍼 없이 그 등록 함수를 받아 연결한다.

## design_system_id

DS-001

## accessibility_notes

자체 DOM 을 렌더하지 않고 Controller 결과를 그대로 위임하므로 접근성은 render/children 으로 전달되는 실제 입력 컴포넌트가 책임진다.


---

<!-- UI-031 -->

# overlay: Popover

## name

Popover

## tags

- common
- overlay
- popover

## category

overlay

## description

팝오버. trigger 콘텐츠를 내부 button 으로 감싸 aria-haspopup=dialog·aria-expanded 를 부여한다(trigger 로 이미 상호작용 가능한 요소를 넘기면 중첩 button 이 된다는 점에 유의). 절대배치 콘텐츠, placement(bottom/top × start/end), 외부 클릭/ESC 닫기. open/onOpenChange 를 지정하면 열림 상태를 외부에서 제어하는 controlled 모드로 전환된다(미지정 시 내부 상태로 자동 관리).

## props_schema

### trigger

- **type**: ReactNode
- **required**: true

### children

- **type**: ReactNode
- **required**: true

### placement

- **type**: 'bottom-start'|'bottom-end'|'top-start'|'top-end'
- **default**: bottom-start
- **required**: false

### open

- **type**: boolean
- **required**: false
- **description**: 지정 시 controlled 모드 — 외부에서 열림 상태를 직접 제어한다(미지정 시 내부 state로 자동 관리).

### onOpenChange

- **type**: (open: boolean) => void
- **required**: false
- **description**: controlled 모드에서 열림 상태가 바뀔 때 호출된다. open 과 함께 사용.

## usage_example

라벨링 툴바의 회전 각도 선택, 날짜 선택기 등 트리거 버튼 아래에 소규모 옵션·컨트롤을 띄울 때 사용. 값 선택 즉시 닫기처럼 액션 완료 후 자동으로 닫아야 하면 open/onOpenChange 로 controlled 모드를 사용한다.

## design_system_id

DS-001

## accessibility_notes

aria-haspopup=dialog, aria-expanded, ESC/외부클릭 닫기. trigger 콘텐츠는 내부 button 으로 감싸지므로 trigger 에 이미 상호작용 가능한 요소(버튼 등)를 전달하면 중첩 button 이 된다 — 아이콘·텍스트 등 비상호작용 콘텐츠를 넘기는 것을 권장. controlled 모드(open/onOpenChange)에서도 aria-expanded 는 실제 열림 상태를 그대로 반영한다.

## referenced_by_screen_ids

- SCREEN-005


---

<!-- UI-032 -->

# feedback: Spinner

## name

Spinner

## tags

- common
- feedback
- loading

## category

feedback

## description

로딩 스피너. size(sm/md/lg), aria-label + sr-only 텍스트. role=status. 진행률을 알 수 없는 짧은 대기(버튼 로딩, 페이지 전환 폴백, LoadingOverlay 내부)에 사용 — 진행률(%)을 아는 작업은 ProgressBar, 콘텐츠 구조를 아는 최초 로딩은 Skeleton을 사용한다.

## props_schema

### size

- **type**: 'sm'|'md'|'lg'
- **default**: md
- **required**: false

### label

- **type**: string
- **default**: 로딩 중
- **required**: false

### className

- **type**: string
- **required**: false
- **description**: 스피너 요소에 추가할 CSS 클래스

## design_system_id

DS-001

## accessibility_notes

role=status aria-live=polite, sr-only 라벨.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-006
- SCREEN-010
- SCREEN-019
- SCREEN-023
- SCREEN-025


---

<!-- UI-033 -->

# feedback: Skeleton

## name

Skeleton

## tags

- common
- feedback
- skeleton
- loading

## category

feedback

## description

스켈레톤 로딩 플레이스홀더. width/height/rounded, animate-pulse. role=presentation. 콘텐츠 구조(표 칸수·행 수 등)를 이미 알고 있는 최초 로딩에 사용 — DataTable/목록 화면의 로딩 행·카드가 대표 사례다.

## props_schema

### width

- **type**: string|number
- **required**: false

### height

- **type**: string|number
- **required**: false

### rounded

- **type**: boolean
- **default**: true
- **required**: false

### className

- **type**: string
- **required**: false
- **description**: 플레이스홀더 요소에 추가할 CSS 클래스

## design_system_id

DS-001

## accessibility_notes

role=presentation — 스크린리더에 노출하지 않는다.

## referenced_by_screen_ids

- SCREEN-007
- SCREEN-009
- SCREEN-011
- SCREEN-012
- SCREEN-020
- SCREEN-022
- SCREEN-023
- SCREEN-030


---

<!-- UI-034 -->

# layout: AppLayout

## name

AppLayout

## tags

- layout
- shell
- internal

## category

layout

## description

내부 채널(저작도구) 공통 레이아웃 쉘. fixed Gnb(상단, h-14) + fixed Lnb(좌측, w-60) + 본문 영역(pl-60 pt-14 오프셋, 내부 p-6 패딩, Outlet) + 하단 Footer(콘텐츠 뒤에 배치되어 짧은 콘텐츠에서도 뷰포트 하단에 붙는다). 라벨링 캔버스(/label/:id)와 포털 채널(/portal)은 이 쉘 밖에서 각각 별도 라우트/쉘로 렌더된다.

## usage_example

router 의 INTERNAL 채널 루트 element. 자식 라우트가 Outlet 으로 렌더됨.

## design_system_id

DS-001

## accessibility_notes

랜드마크 역할은 하위 컴포넌트가 각각 담당한다 — Gnb=header, Lnb=nav, Footer=footer. AppLayout 자체는 배경(bg-gray-50)과 오프셋만 제공하는 래퍼.

## referenced_by_screen_ids

- SCREEN-007
- SCREEN-011
- SCREEN-012
- SCREEN-018
- SCREEN-020
- SCREEN-022
- SCREEN-024
- SCREEN-030


---

<!-- UI-035 -->

# navigation: Gnb

## name

Gnb

## tags

- layout
- navigation
- gnb
- internal

## category

navigation

## description

전역 상단 네비게이션 바(h-14 fixed). 좌측 로고+제목(학습데이터 저작도구, /dashboard 링크), 우측 역할 배지(검수자/작업자/포털, 역할별 색상 구분) + 사용자 아바타(이름 첫 글자 원형 이니셜) + 이름. SSO 채널이라 역할 변경 불가 — read-only 표시. useAuthStore claims 구독.

## design_system_id

DS-001

## accessibility_notes

로고 아이콘은 aria-hidden, 로고 영역 전체가 텍스트를 포함한 링크라 스크린리더로도 이동 가능. 포커스 링(KRDS 포커스 링) 적용.

## referenced_by_screen_ids

- SCREEN-007
- SCREEN-011
- SCREEN-012
- SCREEN-018
- SCREEN-022
- SCREEN-024


---

<!-- UI-036 -->

# navigation: Lnb

## name

Lnb

## tags

- layout
- navigation
- lnb
- internal
- role-based

## category

navigation

## description

좌측 사이드 네비게이션(w-60 fixed, 상단 바 아래 전체 높이). 그룹 헤더 + 메뉴 링크로 구성되며 역할(allow: REVIEWER/WORKER) 기반으로 항목을 필터링해 보이지 않는 항목은 렌더하지 않는다. 활성 항목은 좌측 보더 + 배경 + 텍스트 색으로 강조. DEV 빌드에서만 최하단에 개발 전용 그룹이 추가된다. 메뉴 그룹·항목 구성은 내비게이션 정의(NAV)를 따른다.

## usage_example

메뉴 트리는 NAV(navigation_tree)와 정합. allow 배열로 역할별 노출 제어.

## design_system_id

DS-001

## accessibility_notes

nav aria-label=좌측 메뉴. NavLink 특성상 활성 항목에 aria-current=page 가 자동 부여된다.

## referenced_by_screen_ids

- SCREEN-007
- SCREEN-011
- SCREEN-012
- SCREEN-018
- SCREEN-022
- SCREEN-024


---

<!-- UI-037 -->

# layout: PortalLayout

## name

PortalLayout

## tags

- layout
- shell
- portal
- responsive

## category

layout

## description

외부 포털 채널 전용 레이아웃 쉘. 상단 sticky 헤더(제목 'AI 학습데이터 포털' + 사용자명, LNB 없음, fixed 가 아니라 sticky라 본문에 별도 오프셋 패딩이 필요 없다) + main(Outlet) + 하단 Footer(AppLayout 과 동일 콘텐츠). 모바일 친화(md:* 분기, WCAG 2.1 AA).

## usage_example

router 의 /portal 루트 element. PORTAL_USER 전용.

## design_system_id

DS-001

## accessibility_notes

사용자명은 역할 배지 없이 일반 텍스트로만 노출된다(Gnb 의 역할 배지는 내부 채널 전용).

## referenced_by_screen_ids

- SCREEN-029


---

<!-- UI-038 -->

# layout: Footer

## name

Footer

## tags

- layout
- footer

## category

layout

## description

하단 푸터. 공공 웹 표준(KRDS) 필수 정보인 근거법령·운영기관·문의처를 정의목록(dl/dt/dd)으로 표기하고, 그 아래에 버전 표기(AI 학습데이터 저작도구 v0.1.0) + 발주처 표기를 병기한다. AppLayout·PortalLayout 양쪽에서 동일 콘텐츠로 사용된다 — 포털 전용이 아니다. 근거법령/운영기관/문의처 항목은 실제 기관명·연락처가 확정되기 전까지 자리표시 텍스트(예: [운영기관명])를 표시한다.

## design_system_id

DS-001

## accessibility_notes

시맨틱 <footer> 요소. 근거법령·운영기관·문의처는 dl/dt/dd 정의목록 구조로 표기한다.

## referenced_by_screen_ids

- SCREEN-011
- SCREEN-029


---

<!-- UI-039 -->

# data: SimplePieChart

## name

SimplePieChart

## tags

- chart
- data
- recharts
- pie

## category

data

## description

recharts 기반 파이 차트. data(label/value/color) 배열로 조각을 그리고 중앙 파이 반경은 60px 고정, 캔버스 크기는 size(정사각형, px)로 조절한다. 각 조각에 hover 시 Tooltip 으로 값과 라벨을 함께 보여준다. showLegend=true 면 파이 아래에 색상 사각 마커+라벨 텍스트 목록을 세로로 나열한다(범례에 값 수치는 표시하지 않는다). 이벤트 분포 등 비율 시각화에 사용.

## props_schema

### data

- **type**: {label:string,value:number,color:string}[]
- **required**: true
- **description**: 조각별 라벨/값/색상(CSS color) 배열 — 항목 수만큼 파이 조각이 그려진다.

### size

- **type**: number
- **default**: 160
- **required**: false
- **description**: 파이차트 캔버스의 가로·세로 크기(px, 정사각형).

### showLegend

- **type**: boolean
- **required**: false
- **description**: true 면 파이 아래에 색상 마커+라벨 범례 목록을 렌더(값 수치는 범례에 표시하지 않음).

## usage_example

통계 화면의 이벤트 유형 분포 등 비율 시각화에 좌측 파이+범례, 우측 수치 리스트 조합으로 자주 쓴다. 항목이 많은 데이터는 범례가 길어지지 않게 별도 리스트 컴포넌트와 병행해 쓴다.

## design_system_id

DS-001

## referenced_by_screen_ids

- SCREEN-021


---

<!-- UI-040 -->

# data: SimpleBarChart

## name

SimpleBarChart

## tags

- chart
- data
- recharts
- bar

## category

data

## description

recharts 기반 막대 차트(ResponsiveContainer 로 폭 100%, 높이는 height prop 고정). CartesianGrid 점선 배경 + X축(label, 표시 간격 xAxisInterval) + Y축(정수만, 소수점 미허용) + Tooltip + 단일 계열 Bar. 일별 완료량 등 추이 시각화에 사용. 데이터가 0건이면 축도 막대도 그려지지 않아 빈 사각형만 남으므로, 차트 대신 빈 상태 안내를 height 만큼의 영역에 표시한다. 값이 전부 0 인 것은 데이터가 있는 경우이므로 그대로 차트로 그린다.

## props_schema

### data

- **type**: {label:string,value:number}[]
- **required**: true
- **description**: X축 라벨과 값 배열 — 항목 수만큼 막대가 그려진다.

### height

- **type**: number
- **default**: 200
- **required**: false
- **description**: 차트 영역 높이(px). 폭은 부모 컨테이너에 맞춰 100% 반응형.

### xAxisInterval

- **type**: number
- **default**: 0
- **required**: false
- **description**: X축 라벨 표시 간격 — 0 은 모든 라벨 표시, n 은 n개 건너뛰고 표시(라벨이 많은 30일 추이 등에 사용).

### color

- **type**: string
- **default**: 디자인시스템 주조색
- **required**: false
- **description**: 막대 채우기 색. 미지정이면 디자인시스템 주조색을 따른다 — 색값을 이 사양에 직접 박지 않는다. 팔레트를 바꾸면 별도 수정 없이 따라가야 하기 때문이다.

## usage_example

최근 N일 일별 작업량 등 시계열 막대 그래프에 사용한다. 라벨이 많아 겹치는 경우 xAxisInterval 로 표시 간격을 늘린다.

## design_system_id

DS-001

## referenced_by_screen_ids

- SCREEN-021


---

<!-- UI-041 -->

# display: AuthImage

## name

AuthImage

## tags

- common
- display
- image
- auth

## category

display

## description

JWT 인증이 필요한 프레임 이미지를 표시한다. srcSn(프레임 PK, 원본 프레임 /frames/{srcSn}/image) 또는 path(BE 가 내려준 이미지 API 경로 문자열, 화이트리스트 검증을 통과한 경우만 요청 — 예 /v1/frames/{srcSn}/deid-image) 중 하나로 소스를 지정한다(동시 지정 불가). apiClient(axios)로 blob 다운로드 → objectURL 로 변환해 <img> 로 렌더한다 — <img src> 를 직접 쓰면 Authorization 헤더가 빠져 401 이 나기 때문이다. 같은 경로를 보는 여러 소비자는 요청과 objectURL 을 공유해 중복 요청을 없애고, 마지막 소비자가 언마운트되거나 소스가 바뀌는 즉시 objectURL 을 revoke 한다(영속 캐시 없음 — 비식별 신고로 접근이 막혀도 캐시된 이미지가 계속 보이는 것을 방지). 로딩 중에는 펄스 애니메이션 placeholder, 요청 실패 또는 미허용 경로면 '이미지 없음' 문구로 대체한다 — 두 폴백 상태 모두 alt/data-*/aria-* 등 식별 속성은 유지한다. width/height/loading/decoding/srcSet/sizes/crossOrigin/referrerPolicy/useMap/fetchPriority 등 표준 img 속성은 그대로 전달되며, 폴백(div) 렌더 시에는 이 img 전용 속성을 제외한 나머지(className/id/data-*/aria-* 등)만 전달된다.

## props_schema

### srcSn

- **type**: number
- **required**: false
- **description**: 프레임 PK — /frames/{srcSn}/image 원본 프레임 이미지를 요청한다. path 와 동시 지정 불가. srcSn 과 path 중 하나는 반드시 지정해야 한다.

### path

- **type**: string
- **required**: false
- **description**: BE 응답의 이미지 API 경로 문자열(예 /v1/frames/{srcSn}/deid-image) — 화이트리스트 검증을 통과해야 요청되며, 통과하지 못하면 요청하지 않고 실패 폴백을 표시한다(fail-closed). srcSn 과 동시 지정 불가.

### alt

- **type**: string
- **required**: false
- **description**: 대체 텍스트 — 로딩/실패 폴백에서도 aria-label 로 유지된다.

### className

- **type**: string
- **required**: false
- **description**: 루트 img(또는 로딩/실패 시 폴백 div)에 적용할 커스텀 클래스.

### ...imgProps

- **type**: React.ImgHTMLAttributes<HTMLImageElement>
- **required**: false
- **description**: width/height/loading/decoding/srcSet/sizes/crossOrigin/referrerPolicy/useMap/fetchPriority 등 표준 img 속성은 그대로 전달된다. src 는 이 컴포넌트가 내부적으로 결정하므로 받지 않는다.

## usage_example

라벨링 캔버스 프레임 표시, 프레임 그리드/썸네일, 영상 상세의 미리보기 등 인증이 필요한 프레임 이미지를 그릴 때마다 <img> 대신 이 컴포넌트를 쓴다. 원본 프레임은 srcSn, 비식별 전용 이미지등 BE 가 내려준 특정 경로가 필요한 경우는 path 를 쓴다.

## design_system_id

DS-001

## accessibility_notes

로딩·실패 폴백 모두 alt 를 aria-label 로 유지해 스크린리더가 이미지 설명을 계속 들을 수 있게 하고, 호출자가 지정한 data-testid 등 식별 속성도 폴백 상태에서 그대로 유지된다(로딩 중에도 테스트/자동화가 요소를 찾을 수 있게).

## referenced_by_screen_ids

- SCREEN-009


---

<!-- UI-042 -->

# display: VideoPlayer

## name

VideoPlayer

## tags

- feature:marking
- display
- video
- forwardRef

## category

display

## description

마킹 화면용 영상 플레이어(forwardRef). native <video> 엘리먼트를 감싸며 재생/일시정지, 배속 전환(0.25x/0.5x/1x/1.5x/2x/4x), 탐색(seek range), 버퍼링·탐색 중 스피너(waiting/seeking 시 노출, canplay/playing/seeked 시 해제)를 제공한다. useImperativeHandle(VideoPlayerHandle)로 getCurrentTime·getCurrentFrame·seekTo·getDuration을 상위에 노출하며, getCurrentFrame은 fps를 필수 인자로 받아 프레임 인덱스를 산출한다 — fps를 고정값(예: 30)으로 두면 실제 fps가 다른 영상에서 프레임 위치가 어긋난다. src는 HTTP Range를 지원하는 단기 서명 스트리밍 URL이며, 로드 실패(서명 만료 등) 시 onSrcError로 상위에 재발급을 요청한다.

## props_schema

### src

- **type**: string
- **required**: true
- **description**: 영상 스트리밍 URL(HTTP Range 지원, 단기 서명 URL)

### className

- **type**: string
- **required**: false
- **description**: 루트 컨테이너에 추가할 클래스

### onSrcError

- **type**: () => void
- **required**: false
- **description**: 영상 로드 실패(서명 URL 만료 등) 시 호출 — 상위가 스트림 URL을 재발급해 src를 교체한다

### onDurationChange

- **type**: (sec: number) => void
- **required**: false
- **description**: 메타데이터 로드로 실제 영상 길이(초)를 얻으면 호출 — 유효한 값(NaN/Infinity 아님, 0 초과)일 때만 통지한다

## design_system_id

DS-001

## accessibility_notes

버퍼링/탐색 중 오버레이는 role=status로 노출해 스크린리더에 상태를 알린다.

## referenced_by_screen_ids

- SCREEN-006


---

<!-- UI-043 -->

# action: MarkingToolbar

## name

MarkingToolbar

## tags

- feature:marking
- action
- toolbar

## category

action

## description

마킹 도구 모음. 자동/수동 모드 전환(탭 UI, 단축키 1=수동/2=자동 — 전역에서 항상 발화하며 입력 필드 포커스 시에는 제외), 자동 모드 전용 프레임 간격 입력, 초기화(로컬 마킹 전체 삭제), 마킹 완료 제출, 현재 마킹 건수 표시로 구성된다. 이벤트명은 이 도구에서 입력받지 않는다 — 관제 인입값(검증이벤트유형)에서 서버가 영상 단위로 자동 소싱해 VLM 위탁에 반영한다. 수동 모드에서는 단축키 안내(Space=마킹/Del·Backspace=삭제/Enter=완료)를 대신 노출한다.

## props_schema

### mode

- **type**: MarkingMode
- **required**: true
- **description**: 'AUTO'|'MANUAL' — 자동/수동 마킹 방식

### intervalFrames

- **type**: number
- **required**: true
- **description**: 자동 모드 프레임 간격. 하한 1 이상 정수만 검증하며 상한은 없다 — 입력 필드의 max 속성(3600)은 스핀 컨트롤 UI 힌트일 뿐 값 검증 규칙이 아니다

### onModeChange

- **type**: (mode: MarkingMode) => void
- **required**: true
- **description**: 모드 전환

### onIntervalFramesChange

- **type**: (frames: number) => void
- **required**: true
- **description**: 간격 값 변경

### onSubmit

- **type**: () => void
- **required**: true
- **description**: 마킹 완료 제출. 버튼은 항상 클릭 가능하다 — 수동 모드에서 마크 0건이면 토스트 경고로 제출만 막고, 자동 모드는 간격 값 검증만 통과하면 제출된다

### onClear

- **type**: () => void
- **required**: true
- **description**: 로컬 마킹 전체 초기화

### markCount

- **type**: number
- **required**: true
- **description**: 현재 로컬 마킹 건수 표시

### submitting

- **type**: boolean
- **required**: false
- **description**: 제출 진행 중 — 완료 버튼 비활성 + 진행 문구

### className

- **type**: string
- **required**: false
- **description**: 루트 컨테이너에 추가할 클래스

## design_system_id

DS-001

## accessibility_notes

자동/수동 모드 전환은 tablist/tab 시맨틱을 제공하는 탭 컴포넌트로 구현한다.

## referenced_by_screen_ids

- SCREEN-006


---

<!-- UI-044 -->

# display: MarkingTimeline

## name

MarkingTimeline

## tags

- feature:marking
- display
- timeline

## category

display

## description

마킹 타임라인 막대. 영상 길이(durationSec)와 실 프레임레이트(fps)로 산출한 총 프레임 수 대비 각 마크의 프레임 위치(frameIndex) 비율로 막대 위에 배치한다. 총 프레임 수가 0 이하(durationSec 미확보)이면 렌더하지 않는다. 막대 클릭 시 해당 마크를 선택(selectedIndex)하며 선택된 마크는 강조 색으로 표시한다.

## props_schema

### marks

- **type**: MarkItem[]
- **required**: true
- **description**: 로컬 마킹 배열

### durationSec

- **type**: number
- **required**: true
- **description**: 영상 길이(초)

### fps

- **type**: number
- **required**: true
- **description**: 영상 실 프레임레이트 — 마크의 frameIndex를 산출할 때 쓴 fps와 반드시 같아야 한다. 고정값(예: 30)을 쓰면 실제 fps가 다른 영상에서 마크 위치가 실제보다 앞쪽에 표시된다

### selectedIndex

- **type**: number|null
- **required**: true
- **description**: 현재 선택된 마크 인덱스

### onSelect

- **type**: (index: number) => void
- **required**: true
- **description**: 막대 클릭 시 해당 마크 선택

### className

- **type**: string
- **required**: false
- **description**: 루트 컨테이너에 추가할 클래스

## design_system_id

DS-001

## accessibility_notes

각 마크 버튼은 aria-label로 프레임 번호와 시각(F{frame}·mm:ss)을 노출한다 — 툴팁 텍스트(title)만으로는 스크린리더에 전달되지 않는다.

## referenced_by_screen_ids

- SCREEN-006


---

<!-- UI-045 -->

# data: MarkingPanel

## name

MarkingPanel

## tags

- feature:marking
- data
- list

## category

data

## description

제출 전 로컬 마킹 칩 목록(우측 패널). 각 마킹을 프레임 번호(F{frameIndex})와 타임스탬프(mm:ss) 칩으로 나열한다. 칩 클릭 시 해당 마킹을 선택(강조 표시)하고, 칩의 개별 삭제 버튼으로 그 마킹만 제거한다. 마킹 건수 표시와 전체 초기화 버튼은 이 패널에 두지 않는다 — 마킹 도구바가 단독으로 담당한다(같은 조작을 두 곳에 두면 표시 규칙이 갈린다). 마킹이 0건이면 빈 상태 안내 문구를 보여준다. 서버에 저장된 마킹이 아니라 제출 전 로컬 상태(localMarks)를 다루며, 마킹 완료 제출이 성공해야 서버에 적재된다.

## props_schema

### localMarks

- **type**: LocalMark[]
- **required**: true
- **description**: 제출 전 로컬 마킹 배열(프레임 인덱스+타임스탬프)

### color

- **type**: string
- **required**: true
- **description**: 마킹 표시 색상(이벤트 유형 색)

### selectedMarkId

- **type**: number|null
- **required**: true
- **description**: 현재 선택된 마킹 id

### onSelectMark

- **type**: (mark: LocalMark) => void
- **required**: true
- **description**: 칩 클릭 시 해당 마킹 선택

### onRemoveMark

- **type**: (id: number) => void
- **required**: true
- **description**: 칩의 개별 삭제 버튼 클릭 시 그 마킹만 제거

### [폐기] onClearMarks

- **type**: () => void
- **required**: false
- **description**: [폐기] 전체 마킹 초기화 조작을 이 패널에 두지 않는다 — 마킹 도구바가 단독으로 담당하며 그 계약에 초기화 콜백과 건수 값이 필수로 들어 있다.

## design_system_id

DS-001

## accessibility_notes

개별 삭제 버튼은 aria-label='마킹 삭제'로 노출한다.

## referenced_by_screen_ids

- SCREEN-006


---

<!-- UI-046 -->

# display: CanvasShell

## name

CanvasShell

## tags

- feature:label
- canvas
- konva
- display

## category

display

## description

react-konva Stage 컨테이너. Layer를 이미지/라벨/오버레이 3겹으로 분리해 이미지는 imageUrl 변경 시에만 재렌더되고 라벨 변경이 이미지 레이어를 다시 그리지 않는다. 도구 모드(선택/바운딩박스/폴리곤/AI 분할/키포인트)와 라벨 선택·갱신은 공유 라벨 스토어를 통해 이뤄지며, 이 컴포넌트는 프레임 데이터·컨테이너 크기·라벨 목록만 props로 받는다. 화면 표시용 회전(0/90/180/270°, 보기 전용 — 회전 중에는 편집 핸들을 붙이지 않는다), 드래그한 영역으로 확대하는 영역 확대(보기 조작 — 라벨을 만들지 않으며, 아주 작은 드래그는 무시하고, 배율은 기존 확대 한계를 넘지 않으며, 화면 맞춤으로 되돌린다), 격자 오버레이 토글을 지원한다. 밝기/대비 조절과 라벨/작업 레이어 투명도는 화면 표시 전용(저장 대상 아님)이며 공유 상태로 관리된다. 스페이스+드래그 또는 중클릭으로 팬, 휠로 커서 중심 줌을 지원한다. 편집 차단(장시간 작업 진행 중) 구간에는 라벨 레이어의 포인터 이벤트를 꺼 선택·이동·편집 진입 자체를 막는다. 내부 라벨링 캔버스 화면과 포털 업로드 라벨링 화면이 이 컴포넌트를 공유한다.

## props_schema

### frame

- **type**: FrameSummary
- **required**: true
- **description**: 현재 프레임 데이터(이미지 URL·프레임 식별자 등)

### width

- **type**: number
- **required**: true
- **description**: 캔버스 컨테이너(Stage) 폭 — 이미지 원본 해상도가 아니라 뷰포트 크기

### height

- **type**: number
- **required**: true
- **description**: 캔버스 컨테이너(Stage) 높이

### labels

- **type**: Label[]
- **required**: true
- **description**: 현재 프레임의 라벨(도형·키포인트) 목록

### onLabelAdd

- **type**: (label: Label) => void
- **required**: false
- **description**: 새 라벨(도형) 생성 시 호출

### readOnly

- **type**: boolean
- **required**: false
- **description**: 편집 잠금(작업락·포털 읽기 제약 등) — 라벨 이동·리사이즈를 비활성화한다

### onKeypointPlacingChange

- **type**: (placingIndex: number|null) => void
- **required**: false
- **description**: 키포인트 순차 배치 진행 인덱스(0~16) 변경 통지 — 진행 가이드는 별도 패널이 렌더한다

### onImageSize

- **type**: (width: number, height: number) => void
- **required**: false
- **description**: 로드된 프레임 이미지의 실측 네이티브 픽셀 크기 통지 — 좌표 편집 등 형제 컴포넌트가 동일 기준을 쓰도록 배선한다

### immediateSegment

- **type**: boolean
- **required**: false
- **description**: AI 분할 클릭마다 즉시 미리보기를 그리는 토글(기본 OFF)

### segmentSimplifyTolerance

- **type**: number
- **required**: false
- **description**: AI 분할 경계 세밀함 조절 값 — 지정 시 분할 요청에 실리고, 미지정이면 서버 기본값을 쓴다

### rotation

- **type**: number
- **required**: false
- **description**: 화면 표시용 회전각(0/90/180/270°, 시계방향) — 서버 저장 없이 보기 전용으로 이미지+라벨을 함께 회전한다. 회전 중에는 편집 핸들(선택·리사이즈)을 붙이지 않는다

### showGrid

- **type**: boolean
- **required**: false
- **description**: 격자 오버레이 표시 토글(보기 전용)

## design_system_id

DS-001

## accessibility_notes

편집 차단·읽기전용 상태는 루트 컨테이너의 aria-busy로 노출한다. 캔버스 자체는 시각적 드로잉 표면(konva Stage)이라 별도 대체 텍스트를 제공하지 않는다.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-029


---

<!-- UI-047 -->

# action: ToolBar

## name

ToolBar

## tags

- feature:label
- action
- toolbar
- canvas

## category

action

## description

라벨링 캔버스 좌측 도구바(role=toolbar). 그리기 도구는 선택/이동·바운딩박스(B)·폴리곤(P)·AI 분할(G)·AI 추적(Shift+T)·키포인트(K)이며, 바운딩박스·폴리곤·AI 분할은 클릭 시 먼저 라벨 선택 오버레이를 열고 라벨을 고른 뒤에야 해당 도구 모드로 진입한다(라벨 없이 그리기가 시작되지 않는다). 도구 아래에 AI 탐지(현재 프레임 전체 객체검출 대상 선택) 액션을 두고, 이어서 보기 조작(좌/우 90° 회전 — 회전 중에는 그리기 도구를 선택 모드로 잠근다, 화면 맞춤, 영역 확대)과 그리드 표시 토글을 두며, 맨 아래 고정 위치에 단축키 안내를 둔다. 화면 회전 중에는 선택 도구를 제외한 모든 도구·AI 탐지가 비활성화된다. 포털 채널에서는 AI 분할·AI 추적·키포인트 도구와 AI 탐지 액션을 숨긴다(포털은 바운딩박스/폴리곤 수동 라벨링만 제공한다). 저장·삭제·실행취소·다시실행은 이 도구바가 아니라 캔버스 상단 옵션바가 담당한다.

## props_schema

### mode

- **type**: DrawMode
- **required**: true
- **description**: 현재 활성 도구('select'|'draw_bbox'|'draw_polygon'|'sam_segment'|'draw_keypoint'|'zoom_region' 등)

### onModeChange

- **type**: (mode: DrawMode) => void
- **required**: true
- **description**: 도구 전환(select·키포인트는 즉시, bbox·polygon·AI분할은 라벨 선택 확정 후)

### labels

- **type**: Label[]
- **required**: true
- **description**: 라벨 선택 오버레이에 쓰이는 라벨 클래스 목록

### activeLabel

- **type**: Label
- **required**: true
- **description**: 현재 활성 라벨 — 오버레이에서 현재 선택을 강조

### onLabelChange

- **type**: (label: Label) => void
- **required**: true
- **description**: 오버레이에서 라벨을 고를 때 호출(활성 라벨 지정)

### onAiDetect

- **type**: () => void
- **required**: true
- **description**: AI 탐지 실행 요청(대상 선택 다이얼로그 열기)

### isAiDetecting

- **type**: boolean
- **required**: true
- **description**: AI 탐지 진행 중 — 버튼 스피너 + 재요청 차단

### segmentTolerance

- **type**: PrecisionControl
- **required**: true
- **description**: AI 분할 경계 세밀함(FEAT-007) 조절 값 — AI 분할 도구 오버레이 하단에 노출

### isPrecisionLoading

- **type**: boolean
- **required**: false
- **description**: 정밀도 기본값(시스템 설정) 로딩 중 — 슬라이더 비활성

### rotation

- **type**: number
- **required**: true
- **description**: 화면 회전각(0/90/180/270°). 0이 아니면 그리기·편집 도구를 select로 잠근다

### onRotateLeft

- **type**: () => void
- **required**: true
- **description**: 이미지를 반시계방향 90° 회전(보기 전용)

### onRotateRight

- **type**: () => void
- **required**: true
- **description**: 이미지를 시계방향 90° 회전(보기 전용)

### onFit

- **type**: () => void
- **required**: true
- **description**: 캔버스를 화면에 맞춤

### showGrid

- **type**: boolean
- **required**: true
- **description**: 캔버스 격자 표시 여부

### onToggleGrid

- **type**: () => void
- **required**: true
- **description**: 격자 표시 토글

### portalMode

- **type**: boolean
- **required**: false
- **description**: 포털 채널 여부 — true면 AI 분할·AI 추적·키포인트 도구와 AI 탐지 액션을 숨긴다(포털은 오토라벨링·SAM2 미제공)

## design_system_id

DS-001

## accessibility_notes

도구바 컨테이너는 role=toolbar를 갖는다. 각 도구·액션 버튼은 aria-label로 이름을 노출하고, 활성 도구에는 aria-pressed로 선택 상태를 알린다. 단축키는 버튼의 title/툴팁으로 함께 노출되며 aria-label 문자열 자체에는 포함되지 않는다.

## referenced_by_screen_ids

- SCREEN-005


---

<!-- UI-048 -->

# overlay: LabelPickerModal

## name

LabelPickerModal

## tags

- feature:label
- navigation
- sidebar
- canvas

## category

overlay

## description

도형 그리기 도구(바운딩박스/폴리곤/AI 분할)를 클릭하는 시점에 여는 라벨 선택 모달. 라벨 마스터 목록(활성만, 정렬순)을 검색창 + 목록으로 보여주며 색상칩과 라벨명을 함께 표시하고, 앞 9개 항목에는 숫자 단축키(1~9) 배지를 병기해 모달이 열려 있는 동안 숫자키로 즉시 선택할 수 있게 한다(검색창 포커스 중에는 숫자가 검색어로 들어간다). 라벨을 고르면 모달이 닫히고 해당 도구가 활성화되며, 바깥을 클릭하거나 취소하면 도구를 활성화하지 않고 이전 상태로 되돌아간다. 이 모달이 대체한 구 상시노출 사이드바 방식은 두지 않는다 — 라벨 선택은 도구 클릭 시점에만 이뤄진다.

## props_schema

### open

- **type**: boolean
- **required**: true
- **description**: 모달 표시 여부

### toolName

- **type**: string
- **required**: false
- **description**: 모달을 열어준 도구의 사람이 읽는 이름 — 안내 문구에만 쓰인다

### labels

- **type**: Label[]
- **required**: true
- **description**: 선택 가능한 라벨 마스터 목록(활성만, 정렬순)

### activeLabelId

- **type**: number|string|null
- **required**: true
- **description**: 현재 활성 라벨 — 목록에서 강조 표시

### onSelect

- **type**: (label) => void
- **required**: true
- **description**: 라벨 확정 — 모달을 닫고 해당 도구를 활성화한다

### onCancel

- **type**: () => void
- **required**: true
- **description**: 취소(ESC/바깥 클릭/닫기) — 도구를 이전 상태로 되돌린다

## design_system_id

DS-001

## accessibility_notes

각 라벨 항목은 시맨틱 버튼이며 aria-pressed로 현재 선택 상태를 노출한다. 색상칩은 라벨명 텍스트와 항상 병기되어 색상만으로 구별하지 않는다.

## referenced_by_screen_ids

- SCREEN-005


---

<!-- UI-049 -->

# data: ObjectClassTree

## name

ObjectClassTree

## tags

- feature:label
- data
- tree
- canvas

## category

data

## description

라벨링 객체 트리(우측 패널 '객체' 탭 상단). 현재 프레임 라벨(도형)을 라벨명 기준으로 그룹화해 그룹 헤더(색상 점+이름+개수)와 펼치기/접기를 제공하고, 각 행에 출처 아이콘(수동/자동/보간)·형태 배지(BBOX/POLYGON)·표시-숨김 토글·잠금 토글을 노출한다. 행을 펼치면 상세(속성) 패널을 인라인으로 붙일 수 있다. 케밥 메뉴(또는 인접 아이콘)로 복사·삭제를 제공하며, 서버 트랙을 가진 객체에는 트랙 편집(번호 변경/병합·분할·궤적 삭제) 항목을 추가한다 — 트랙 궤적 삭제는 되돌릴 수 없어 확인 다이얼로그를 거친다. 키포인트 인스턴스는 같은 목록 하단에 별도 '키포인트' 그룹으로 편입해 동일한 행 UI(선택·표시-숨김·잠금·편집·삭제)로 다루어진다. 라벨이 0건이면 빈 상태 안내를, 로딩 중이면 스켈레톤을 보여준다(로딩과 빈 상태를 구분). 그룹 대표색은 그룹 내 항목 순서와 무관하게 결정적으로 고른 대표 항목(라벨 마스터 연결 우선, 없으면 id순)의 색상을 쓴다 — 배열 순서가 바뀌어도 그룹 색이 흔들리지 않게 한다. 포털 채널에서는 트랙 번호 변경(연필)·분할 진입을 숨긴다(포털은 트랙 데이터모델이 없다).

검수 화면도 이 트리를 그대로 쓴다. 편집 핸들러(잠금·복사·삭제·상세 펼침·트랙 편집)를 넘기지 않으면 그 항목들이 행에서 사라져 읽기 전용 목록이 된다 — 검수용 별도 컴포넌트를 두지 않는 방식이며, 표시 규칙이 두 화면에서 갈리지 않게 한다. 검수에서는 목록 상단에 객체 수를 배지로 함께 보여준다. 선택과 hover 는 캔버스와 공유 상태로 양방향 동기화된다 — 캔버스에서 라벨을 고르면 목록의 해당 행이 강조되고 그 행이 속한 그룹이 자동으로 펼쳐지며, 목록에서 hover 하면 캔버스의 해당 도형이 강조된다(이미 선택된 항목에는 hover 표시를 겹치지 않는다). 형태 배지는 BBOX·POLYGON 외에 세그멘테이션·트랙도 표시한다.

## props_schema

### labels

- **type**: Label[]
- **required**: true
- **description**: 현재 프레임의 라벨(도형) 목록 — 라벨명으로 그룹화한다

### selectedId

- **type**: number|null
- **required**: false
- **description**: 현재 선택된 라벨 id — 외부에서 선택 상태를 제어하는 통합에서 전달(공유 스토어 기반 통합에서는 내부에서 직접 읽는다)

### onSelect

- **type**: (id: number|null) => void
- **required**: false
- **description**: 행 클릭 시 선택/선택 해제. 차단(편집 잠금) 중에는 선택 자체를 막는다

### onToggleVisible

- **type**: (id: number) => void
- **required**: true
- **description**: 표시/숨김 토글(eye 아이콘) — 화면 표시 전용, 저장 대상에는 영향 없음

### onToggleLocked

- **type**: (id: number) => void
- **required**: false
- **description**: 잠금 토글 — 지정하면 행에 자물쇠 버튼이 생기고, 잠긴 객체는 선택·삭제·트랙 편집 진입이 차단된다

### onCopy

- **type**: (annotation) => void
- **required**: false
- **description**: 케밥 '복사' — 미지정이면 항목이 표시만 되고 동작하지 않는다(읽기 전용 소비처)

### onRemove

- **type**: (id: number) => void
- **required**: false
- **description**: 케밥/아이콘 '삭제' — 미지정이면 항목이 표시만 되고 동작하지 않는다

### isLoading

- **type**: boolean
- **required**: false
- **description**: 프레임 라벨 로딩 중 — 목록이 비어도 빈 안내 대신 스켈레톤을 노출한다

### emptyMessage

- **type**: string
- **required**: false
- **description**: 빈 상태 안내 문구 커스터마이즈

### renderDetail

- **type**: (annotation) => ReactNode
- **required**: false
- **description**: 행을 펼쳤을 때 하단에 렌더할 상세(속성) 콘텐츠 — 지정 시 행에 펼치기 버튼이 생긴다

### keypoints

- **type**: KeypointsGroup
- **required**: false
- **description**: 키포인트 인스턴스 그룹(목록·선택·표시토글·잠금·삭제·편집 핸들러) — 지정 시 목록 하단에 별도 그룹으로 편입된다

### tracks

- **type**: TracksGroup
- **required**: false
- **description**: 트랙 단위 편집 핸들러(번호변경·분할·궤적 삭제) — 지정 시 서버 트랙을 가진 객체 행 케밥에 트랙 항목이 생긴다

### currentFrameNo

- **type**: number
- **required**: false
- **description**: 트랙 삭제/분할 기준 프레임 번호 — 미지정 시 트랙 삭제/분할 액션을 노출하지 않는다

### portalMode

- **type**: boolean
- **required**: false
- **description**: 포털 채널 여부 — true면 트랙 번호 변경(연필)·분할 진입을 숨긴다(포털은 트랙 데이터모델이 없어 rename·병합이 불가능하다)

## design_system_id

DS-001

## accessibility_notes

표시/숨김·잠금 토글 버튼은 각각 aria-label과 aria-pressed로 현재 상태를 노출한다. 행 선택 버튼은 '{라벨명} #{순번} 선택' 형태의 aria-label을 갖는다. 트랙 삭제는 파괴적 동작이라 즉시 실행하지 않고 확인 다이얼로그를 거친다. 그룹 헤더는 실제 버튼 요소이며 aria-expanded 로 펼침 상태를 알린다. 각 행은 role=button + aria-selected 를 가지며 Enter·Space 키로도 선택할 수 있다.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-019


---

<!-- UI-050 -->

# input: ObjectAttributePanel

## name

ObjectAttributePanel

## tags

- feature:label
- input
- canvas
- attributes

## category

input

## description

선택된 객체의 속성 편집 패널(객체 목록 행을 펼쳤을 때 인라인으로, 또는 우측 별도 패널로 노출). 라벨 변경 드롭다운(대상 라벨은 availableLabels로 화이트리스트 지정, 미지정 시 라벨 마스터 전체를 자동 조회), 생성 출처(수동/자동) 배지와 낮은 신뢰도 배지, 신뢰도 막대(오토라벨 객체만), 형태별 상세(BBOX는 X/Y/W/H 좌표를 이미지 경계로 clamp해 편집, POLYGON은 정점 수만 표시, KEYPOINT는 가시성 요약과 Alt+클릭 가시성 순환 안내)를 제공한다. 라벨 마스터에 정의된 커스텀 속성값 입력 영역을 라벨 id·객체 id 기준으로 함께 노출한다. AI 자동추적(SAM2 Track) 실행 버튼을 제공하며, 추적 가능한 후속 프레임이 없으면 비활성화하고 그 사실을 안내한다. AI 분할(SAM) 도구가 활성 상태면 선택 객체 유무와 무관하게 경계 세밀함 슬라이더와 '즉시 그리기'(클릭마다 미리보기 즉시 렌더) 토글을 노출한다. 선택된 객체가 없으면 안내 문구만 표시한다(단, AI 분할 정밀도 조절 영역은 선택 여부와 무관하게 노출된다).

## props_schema

### labels

- **type**: Label[]
- **required**: true
- **description**: 현재 프레임 라벨 목록 — 선택된 대상(annotation)을 찾는 데 쓰인다

### annotation

- **type**: Annotation
- **required**: false
- **description**: 선택된 객체 — 외부에서 대상을 지정하는 통합에서 전달(공유 스토어 기반 통합에서는 selectedId로 내부에서 찾는다)

### serverId

- **type**: number
- **required**: false
- **description**: 라벨 인스턴스 PK(lblSn) — 속성값 조회/저장 키. 저장 직후 신규 객체는 상위가 보강해 넘긴다

### availableLabels

- **type**: AvailableLabel[]
- **required**: false
- **description**: 라벨 드롭다운 옵션 — 미지정 시 라벨 마스터 전체(useYn='Y', sortNo asc)를 자동 조회해 채운다

### imageWidth

- **type**: number
- **required**: false
- **description**: 좌표 clamp 기준 — 이미지 실측 네이티브 픽셀 폭. 미확보 시 상한 clamp를 적용하지 않는다(하한 0만 유지)

### imageHeight

- **type**: number
- **required**: false
- **description**: 좌표 clamp 기준 — 이미지 실측 네이티브 픽셀 높이

### onChangeLabel

- **type**: (id: number, label: Label) => void
- **required**: false
- **description**: 라벨 드롭다운 변경 시 호출 — classId·labelId·className·color를 함께 갱신해야 마스터 조인이 끊기지 않는다

### onUpdatePoints

- **type**: (id: number, points: number[]) => void
- **required**: false
- **description**: BBOX 좌표 편집 시 호출(X/Y/W/H, 이미지 경계로 clamp)

### track

- **type**: {srcSn, nextSrcSns, onTracked?, shape?, label?}
- **required**: false
- **description**: AI 자동추적(SAM2 Track) 컨텍스트 — 추적에 필요한 값을 하나의 선택적 객체로 묶어 받는다. srcSn=추적을 시작할 프레임 식별자. nextSrcSns=전파 대상이 되는 후속 프레임 식별자 목록으로, 비어 있으면 추적 실행 버튼을 비활성화하고 그 사실을 안내한다. onTracked=전파가 성공했을 때 성공분과 부분 성공 여부를 상위로 넘기는 콜백이며, 작업본 병합과 사용자 안내는 상위가 맡는다(이 패널이 직접 저장하지 않는다). shape=추적 결과로 만들 형태(박스/폴리곤)이며 미지정이면 서버 기본값을 따른다. label=추적에 쓸 라벨명으로, 지정하면 선택 객체의 분류명보다 우선한다. 이 객체를 주지 않으면 추적 영역 자체를 노출하지 않는다.

### segment

- **type**: {defaultTolerance?, tolerance?, onToleranceChange?, immediateDraw?, onImmediateDrawChange?}
- **required**: false
- **description**: AI 분할(SAM_SEGMENT) 도구 활성 시 노출하는 경계 세밀함 슬라이더 + 즉시 그리기 토글 컨텍스트. 미지정 시 섹션을 노출하지 않는다

## design_system_id

DS-001

## accessibility_notes

신뢰도는 role=progressbar + aria-valuemin/max/now/label로 노출한다. 폴리곤 정점이 많아 꼭지점 편집이 비활성화되는 경우와 키포인트 가시성 순환 조작법은 텍스트로 안내한다.

## referenced_by_screen_ids

- SCREEN-005


---

<!-- UI-051 -->

# navigation: FrameFilmstrip

## name

FrameFilmstrip

## tags

- feature:label
- navigation
- filmstrip
- canvas

## category

navigation

## description

프레임 썸네일 가로 스크롤 스트립. 썸네일 클릭 시 그 프레임으로 이동한다. 현재 프레임(강조 테두리) · 미해결 문의가 걸린 프레임(경고색 테두리 + 깃발 아이콘) · 라벨이 저장된 프레임(성공색 테두리) · 그 외(기본색)를 테두리 색으로 구분해 상태를 한눈에 보여준다. 장시간 작업(AI 처리·저장 등) 진행 중에는 스트립 전체 선택을 비활성화한다. 포털 채널에서는 썸네일 이미지 조달 경로가 달라진다(내부 API 403 회피). thumbnailUrl 은 BE 신뢰 도메인만 사용한다.

## props_schema

### frames

- **type**: FrameSummary[]
- **required**: true
- **description**: 프레임 목록. 각 항목은 프레임 식별자(srcSn)·프레임 번호·라벨 저장 여부를 담는다.

### frameIndex

- **type**: number
- **required**: true
- **description**: 현재 선택된 프레임의 0-base 인덱스. 해당 썸네일을 강조하고 스트립 중앙으로 자동 스크롤한다.

### onSelectFrame

- **type**: (index:number)=>void
- **required**: true
- **description**: 썸네일 클릭 시 그 인덱스로 프레임 이동을 요청한다.

### inquirySrcSns

- **type**: Set<number>
- **required**: false
- **description**: 미해결 문의가 걸린 프레임 식별자 집합. 해당 썸네일을 경고색 테두리 + 깃발 아이콘으로 강조한다.

### portalMode

- **type**: boolean
- **required**: false
- **description**: 포털 채널 여부. 참이면 썸네일 이미지를 포털 전용 엔드포인트로 조달한다.

### disabled

- **type**: boolean
- **required**: false
- **description**: 장시간 작업 진행 중 스트립 선택을 비활성화한다.

## usage_example

라벨링 화면(SCREEN-005)과 포털 라벨링 화면(SCREEN-029) 하단 고정 영역에서 프레임 슬라이더와 나란히 쓴다. 문의 축이 없는 화면에는 inquirySrcSns 를 생략한다.

## design_system_id

DS-001

## accessibility_notes

컨테이너는 role=listbox, 개별 썸네일은 role=option + aria-selected 로 현재 프레임을 알린다. 각 썸네일에 aria-label 로 프레임 번호를 안내한다.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-029


---

<!-- UI-052 -->

# navigation: FrameNavigator

## name

FrameNavigator

## tags

- feature:label
- navigation
- canvas

## category

navigation

## description

프레임 이동 컨트롤. 라벨링 화면은 캔버스 상단 옵션바에, 검수 화면은 헤더 바로 아래 상단 바에 둔다 — 두 화면이 같은 컨트롤을 공유하며 따로 만들지 않는다.

구성은 세 부분이다. ① 처음/이전/다음/마지막 이동 버튼 — 첫 프레임에서는 처음·이전을, 마지막 프레임에서는 다음·마지막을 비활성화한다. ② 현재 프레임 번호(직접 입력 후 Enter 로 이동)와 전체 프레임 수 표시 — 범위(1~전체 프레임 수) 밖이거나 빈 값이면 현재 번호로 되돌린다. ③ 위치 슬라이더 — 썸을 끌어 연속 이동한다.

슬라이더는 두 가지를 함께 지킨다. 끄는 동안의 이동 요청을 일정 간격으로 솎아 보낸다 — 프레임마다 이미지를 불러오므로 끌 때마다 요청하면 과부하가 된다. 그리고 썸을 놓는 순간 대기 중이던 마지막 위치를 반드시 반영한다 — 솎아내기만 하고 마무리하지 않으면 마지막 프레임이 누락된다. 끄는 동안 썸은 로컬 표시값을 따라 부드럽게 움직이고 놓으면 실제 프레임 위치로 돌아온다.

세 부분 모두 실제 이동을 직접 수행하지 않고 미저장 변경 확인 절차를 거치는 단일 콜백에 위임한다 — 진입점마다 가드를 따로 붙이면 한 곳이 샌다. 미저장이 있는 상태에서 슬라이더를 주르륵 끌어도 확인은 한 번만 뜨고 목적지는 최종 위치로 갱신된다.

프레임 위치 표시·이동은 이 컨트롤이 단독으로 담당하며 라벨링 헤더·검수 헤더 어느 쪽에도 두지 않는다. 검수 화면의 썸네일 스트립(ReviewFrameTimeline)은 같은 현재 프레임 상태를 공유하는 별도 표면이다.

## props_schema

### frameIndex

- **type**: number
- **required**: true
- **description**: 현재 프레임 순번(0부터). 화면에는 1부터의 번호로 표시한다.

### frameCount

- **type**: number
- **required**: true
- **description**: 형제 프레임 총 개수. 이동 범위와 슬라이더 최대값을 정한다.

### onRequestGoTo

- **type**: (index: number) => void
- **required**: true
- **description**: 프레임 이동 요청(0부터). 버튼·번호 입력·슬라이더가 모두 이 하나를 부른다 — 미저장 변경 확인을 이 콜백 안에서 한 번만 처리하기 위해서다.

### showSlider

- **type**: boolean
- **required**: false
- **description**: 위치 슬라이더 노출 여부. 프레임이 적어 스크럽 이득이 없는 화면에서는 끄고 버튼·번호 입력만 둘 수 있다.

### disabled

- **type**: boolean
- **required**: false
- **description**: 장시간 작업(저장·AI 처리) 진행 중 전체 비활성. 이동이 작업과 교차하면 어느 프레임에 반영될지가 결정되지 않는다.

## usage_example

라벨링 화면(SCREEN-005)과 포털 라벨링 화면(SCREEN-029)의 캔버스 상단 옵션바 중앙에 저장·실행취소/다시실행 버튼과 함께 배치한다.

## design_system_id

DS-001

## accessibility_notes

컨테이너는 role=group aria-label='프레임 이동'. 처음/이전/다음/마지막 각 버튼에 개별 aria-label(처음 프레임/이전 프레임/다음 프레임/마지막 프레임)을 지정하고 툴팁으로 동일 문구를 보조 노출한다. 번호 입력 필드는 aria-label='프레임 번호'. 슬라이더는 방향키로 한 프레임씩 이동할 수 있어야 하며 현재 값·최솟값·최댓값을 보조기술에 알린다.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-029
- SCREEN-019


---

<!-- UI-053 -->

# action: SaveCommitButton

## name

SaveCommitButton

## tags

- feature:label
- action
- save
- canvas

## category

action

## description

라벨 저장 버튼(라벨링 화면의 유일한 저장 진입점, 캔버스 상단 옵션바에 위치 — Ctrl+S 단축키와 동일 동작). 헤더에도 좌측 도구바에도 저장 버튼을 두지 않는다. 클릭 시 작업본을 임시저장한다(버전 스냅샷은 만들지 않는다 — 버전은 검수 승인 시점에 생성). portalMode 가 참이면 포털 전용 저장 경로(데이터마트 원본 미수정, 본인 작업 데이터로 별도 적재)로, 거짓이면 내부 저장 경로로 라우팅한다. 저장 요청에는 조회 시점에 받아 둔 낙관적 동시성 토큰을 함께 실어 그사이 다른 사용자가 먼저 저장한 라벨을 조용히 덮어쓰지 않도록 한다 — 토큰이 낡았으면 저장 충돌 안내로 이어진다. 화면이 저장 절차를 소유하는 경우에는 저장 요청을 화면에 위임하고 이 버튼은 자체 저장을 수행하지 않는다 — 한 화면에 저장 경로가 둘 생기면 한쪽만 토큰을 실어 나중 저장이 앞 저장을 덮어쓴다. 위임 중에는 진행 상태를 받아 진행 표시를 띄우고 중복 클릭을 막으며, 오류·충돌 안내도 화면이 담당한다. 위임하지 않으면 이 버튼이 직접 저장한다. 자체 저장이 성공하면 라벨·영상·배정·검수 캐시를 일괄 무효화한다. 잠금(재비식별 대기) 상태이거나 다른 장시간 작업이 진행 중이면 비활성화된다. 미저장 변경 건수를 배지로 함께 표시한다.

## props_schema

### srcSn

- **type**: number|undefined
- **required**: true
- **description**: 저장 대상 프레임 식별자.

### labels

- **type**: Label[]
- **required**: true
- **description**: 저장할 라벨 전체(전량 교체 저장).

### portalMode

- **type**: boolean
- **required**: false
- **description**: 참이면 포털 전용 저장 경로로 라우팅한다(내부 저장 API 는 포털 채널에서 거부된다).

### locked

- **type**: boolean
- **required**: false
- **description**: 영상이 재비식별 대기 등으로 잠겨 있으면 저장을 비활성화한다. 편집 차단(장시간 작업 진행) 여부와는 별도 축이며 함께 판정한다.

### onSaved

- **type**: () => void
- **required**: false
- **description**: 저장 성공 후 콜백.

### onRequestSave

- **type**: () => void | Promise<void>
- **required**: false
- **description**: 화면이 저장 절차를 소유할 때 주입한다. 주입하면 이 버튼은 자체 저장을 하지 않고 저장 요청만 위임하며, 낙관적 동시성 토큰·저장 충돌 안내·포털/내부 경로 라우팅도 화면이 함께 처리한다. 저장 경로를 한 곳으로 모으기 위한 것이며, 미지정 시에는 이 버튼이 직접 저장한다.

### saving

- **type**: boolean
- **required**: false
- **description**: 위임한 저장이 진행 중인지 여부. 참이면 진행 표시를 띄우고 중복 클릭을 막는다. onRequestSave 를 주입한 경우에만 쓰인다.

## usage_example

라벨링 화면(SCREEN-005)과 포털 라벨링 화면(SCREEN-029)의 캔버스 상단 옵션바에 배치한다. 헤더와 좌측 도구바에는 두지 않는다. portalMode 는 저장 API 경로만 바꾸며 버튼 위치·문구는 동일하다.

## design_system_id

DS-001

## accessibility_notes

버튼에 aria-label='저장'을 지정한다. 저장 실패 시 오류 문구를 role=alert 로 노출한다.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-029


---

<!-- UI-054 -->

# action: UndoRedoToolbar

## name

UndoRedoToolbar

## tags

- feature:label
- action
- undo-redo
- canvas

## category

action

## description

실행취소/다시실행 시각 버튼. canUndo/canRedo 로 각각 비활성 여부를 판정하고, 클릭 시 onUndo/onRedo 콜백을 호출한다. 단축키(Ctrl+Z/Ctrl+Shift+Z)는 화면 레벨에서 전역으로 처리하며 이 버튼과 동일한 동작을 수행한다. role=toolbar.

## props_schema

### canUndo

- **type**: boolean
- **required**: true
- **description**: 실행취소 가능 여부. 거짓이면 버튼을 비활성화한다.

### canRedo

- **type**: boolean
- **required**: true
- **description**: 다시실행 가능 여부. 거짓이면 버튼을 비활성화한다.

### onUndo

- **type**: () => void
- **required**: true
- **description**: 실행취소를 수행한다.

### onRedo

- **type**: () => void
- **required**: true
- **description**: 다시실행을 수행한다.

## usage_example

라벨링 화면(SCREEN-005)과 포털 라벨링 화면(SCREEN-029)의 캔버스 상단 옵션바 또는 도구바에 저장 버튼과 함께 배치한다.

## design_system_id

DS-001

## accessibility_notes

컨테이너는 role=toolbar aria-label='실행 취소/다시 실행'. 각 버튼에 aria-label(실행 취소/다시 실행)과 단축키 안내를 title 로 병기한다.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-029


---

<!-- UI-055 -->

# layout: LabelHeader

## name

LabelHeader

## tags

- feature:label
- layout
- header
- canvas

## category

layout

## description

풀스크린 라벨링 화면 상단 라이트 헤더(높이 56px). 좌: 닫기 + CCTV명 + 프레임명 + 이벤트 배지. 중앙: 저장 상태(저장 중… / ● 편집 중 / ✓ 저장됨). 프레임 이미지 타입 배지(DEID/RAW)는 두지 않는다 — 캔버스가 항상 비식별 프레임을 서빙하므로 배지가 구분할 대상이 없다. 우: 비식별 누락 신고 + 단축키 도움말 + 제출 취소(작업자 본인·검수 시작 전에만) + 검수 제출(WORKER). 저장 버튼은 두지 않는다 — 저장 진입점은 캔버스 상단 옵션바의 저장 버튼 한 곳이며(Ctrl+S 단축키 동일 동작) 헤더는 저장 상태만 표시한다. 프레임 위치 표시·이동도 이 헤더에 두지 않는다(FrameNavigator 담당). 객체 수도 이 헤더에 두지 않는다 — 우측 패널 '객체' 탭 목록 상단에 둔다. 미저장 변경이 있는 상태로 검수 제출하면 확인 모달을 띄운다(저장 후 제출 / 무시하고 제출 / 취소). 영상 잠금(LOCKED_FOR_REDEIDENT) 상태에서는 저장·검수 제출·제출 취소가 모두 비활성이다.

## props_schema

### srcSn

- **type**: number
- **required**: true
- **description**: 프레임 식별자. 화면 상위가 이 값으로 현재 프레임을 특정한다.

### cctvName

- **type**: string
- **required**: true
- **description**: 좌측 식별 정보로 표시할 CCTV 명.

### frameName

- **type**: string
- **required**: false
- **description**: 프레임 파일·이름. CCTV명 우측에 보조 표기.

### events

- **type**: string[]
- **required**: false
- **description**: 이벤트 배지 라벨 목록. 영상의 EV-코드 또는 한글 이벤트명을 그대로 전달한다(카테고리 대표코드 금지).

### [폐기] imageType

- **type**: 'DEID' | 'RAW'
- **required**: false
- **description**: 프레임 이미지 타입 배지를 헤더에 두지 않으므로 이 prop 도 두지 않는다. 다만 프레임 이미지의 원천 값 자체는 화면 상위(라벨링 화면)에서 비식별 누락 신고 버튼의 활성 여부를 정하는 데 계속 쓰인다 — 원본을 보고 있을 때는 신고를 받지 않기 때문이다. 값을 함께 없애면 원본 열람 중에도 신고가 열린다.

### isDirty

- **type**: boolean
- **required**: true
- **description**: 미저장 변경 여부. 저장 상태 문구와 검수 제출 확인 모달을 좌우한다.

### isSaving

- **type**: boolean
- **required**: false
- **description**: 저장 요청 진행 중. 중앙 상태 문구를 '저장 중…' 으로 바꾼다 — 헤더에 저장 버튼이 없으므로 이것이 이 화면의 유일한 텍스트 진행 피드백이다.

### lockSttsCd

- **type**: 'NONE' | 'LOCKED_FOR_REDEIDENT'
- **required**: false
- **description**: 영상 잠금 상태. LOCKED_FOR_REDEIDENT 는 재비식별 대기를 뜻하며 저장·검수 제출·제출 취소를 모두 비활성화한다.

### [폐기] showHistory

- **type**: boolean
- **required**: false
- **description**: 히스토리 진입을 헤더에 두지 않으므로 이 prop 도 두지 않는다. 편집을 어느 버전에서 시작할지 고르는 일은 라벨링 진입 시 띄우는 모달이 맡는다.

### deidentReportUnsupportedReason

- **type**: string
- **required**: false
- **description**: 비식별 누락 신고 불가 사유. 지정하면 신고 버튼을 비활성화하고 사유를 툴팁으로 안내한다(파생영상 등).

### canSubmitReview

- **type**: boolean
- **required**: false
- **description**: 검수 제출 가능 여부. 거짓이면 제출 버튼 비활성.

### isSubmittingReview

- **type**: boolean
- **required**: false
- **description**: 검수 제출 요청 진행 중.

### canCancelSubmitReview

- **type**: boolean
- **required**: false
- **description**: 제출 취소 노출 여부. 작업자 본인이면서 검수 시작 전(REVIEW_PENDING)일 때만 참.

### isCancellingSubmit

- **type**: boolean
- **required**: false
- **description**: 제출 취소 요청 진행 중.

### onClose

- **type**: () => void
- **required**: true
- **description**: 닫기. 미저장 변경이 있으면 상위에서 확인 모달을 띄운다.

### [폐기] onHistoryClick

- **type**: () => void
- **required**: false
- **description**: 히스토리 패널을 여는 버튼을 두지 않으므로 이 prop 도 두지 않는다.

### [폐기] historyOpen

- **type**: boolean
- **required**: false
- **description**: 히스토리 패널이 없으므로 열림 상태를 보조기술에 알릴 대상이 없다.

### onHelpClick

- **type**: () => void
- **required**: false
- **description**: 단축키 도움말 열기. 지정 시 우측에 도움말 버튼을 노출한다.

### [폐기] onRolledBack

- **type**: (srcSn: number) => void
- **required**: false
- **description**: 헤더에서 되돌리기를 실행하지 않으므로 이 prop 도 두지 않는다. 버전을 불러온 결과는 라벨링 진입 시 띄우는 모달이 화면 상위에 알린다.

### onSubmitReview

- **type**: () => void
- **required**: false
- **description**: 검수 제출. 미저장 변경을 반영하지 않고 제출한다.

### onSaveAndSubmitReview

- **type**: () => void
- **required**: false
- **description**: 저장 후 검수 제출. 미저장 상태 확인 모달의 기본 동작.

### onCancelSubmitReview

- **type**: () => void
- **required**: false
- **description**: 제출 취소. 작업 상태로 되돌린다.

## usage_example

라벨링 화면(SCREEN-005) 풀스크린 최상단에 고정 배치한다. 검수 완료된 영상을 다시 제출하는 경우 검수 제출 버튼 문구가 '재검수 제출'로 바뀌어 완료본을 다시 건드린다는 것을 알린다. 포털 라벨링 화면(SCREEN-029)에서도 재사용하되 히스토리 진입은 노출하지 않는다.

## design_system_id

DS-001

## accessibility_notes

닫기 버튼은 aria-label='닫기'. 저장 상태 표시는 role=status aria-live=polite 로 감싸 저장 중/편집 중/저장됨 전환을 스크린리더에도 알린다. 히스토리 토글 버튼은 aria-label='히스토리 토글' + aria-expanded 로 패널 열림 상태를 알린다. 단축키 도움말 버튼은 aria-label='단축키 도움말'. 비식별 누락 신고 버튼은 사유 유무에 따라 aria-label 이 '비식별 누락 신고' 또는 '비식별 누락 신고 — {사유}'로 달라진다.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-029


---

<!-- UI-056 -->

# display: TimeseriesSidePanel

## name

TimeseriesSidePanel

## tags

- feature:label
- display
- timeseries
- vlm
- canvas

## category

display

## description

라벨링 우측 패널의 '메타' 탭에 포함되는 접이식 시계열(VLM) 메타 편집 패널. 편집 대상은 화이트리스트로 판정한 슬롯(위탁 서술 전문과 신규 등록용 수동 시계열 1건)만이며, 슬롯이 하나도 없으면 신규 등록 슬롯을 자동으로 하나 제공한다. 레거시 구간별 항목(시작~종료 구간 키)은 시간축 오름차순으로 나열하되 읽기 전용으로만 병기하고 편집 동선을 두지 않는다(과거 산출물 보존 — 삭제·숨김하지 않는다). 그 외 읽기 전용 메타(일치도 등)는 값만 표시하고 저장 요청에 싣지 않는다. 저장은 슬롯별로 원본과 달라진 항목만 전송하며(공백만 남긴 편집은 제외), 하나 이상 달라졌을 때만 저장 버튼이 활성화된다. 손대지 않은 슬롯을 보내지 않는 것은 그사이 다른 사람이 고친 값을 조용히 덮어쓰지 않기 위해서다. 저장 처리 중에는 입력을 비활성화해 같은 요청이 겹치지 않게 한다. 값은 텍스트로만 렌더링해 자동으로 이스케이프된다. 프레임 전환 시 조회 결과로 편집 값을 다시 동기화한다. 검토(승인/반려) 표면은 이 패널에 두지 않는다 — 검토 상태 확정은 영상 검수 승인 시 자동 처리된다.

## props_schema

### srcSn

- **type**: number|undefined
- **required**: true
- **description**: 메타 조회/수정 대상 프레임 식별자.

## usage_example

라벨링 화면(SCREEN-005) 우측 패널 '메타' 탭에서 촬영환경·개인정보·프레임 설명 패널과 세로로 나열한다. 포털 라벨링 화면(SCREEN-029)은 메타 탭 자체를 제공하지 않으므로 이 패널도 노출하지 않는다.

## design_system_id

DS-001

## accessibility_notes

각 편집 슬롯 textarea 에 라벨을 연결한 aria-label 을 지정한다(신규 등록 슬롯은 스크린리더 전용 라벨). 읽기 전용 참고 정보·레거시 구간 정보 묶음에는 각각 aria-label(시계열 참고 정보/이전 구간별 시계열 정보)을 지정해 편집 슬롯과 구분해 안내한다. 접이식 섹션 헤더는 시맨틱 button + aria-expanded 로 펼침 상태를 알린다. 각 편집 슬롯 하단에는 글자수 카운터를 병기한다.

## referenced_by_screen_ids

- SCREEN-005


---

<!-- UI-057 -->

# action: DeidentReportButton

## name

DeidentReportButton

## tags

- feature:label
- action
- deident-report
- canvas

## category

action

## description

비식별 누락 신고 버튼(라벨러·검수자 공용, 라벨링·마킹 화면 공용). 클릭 시 사유 textarea(zod 1~1000자) 입력 모달을 띄우고, 확인하면 신고 대상에 따라 라벨링은 프레임 식별자(srcSn) 기준, 마킹은 영상 식별자(rawSn) 기준으로 신고 API를 호출한다(둘 중 하나만 지정). 신고하면 영상이 재비식별 대기 상태로 잠긴다. 자동 재처리는 일어나지 않는다 — 외부 비식별 솔루션으로 다시 처리한 뒤 신고를 해소해야 대기가 풀린다. 대기 구간에는 그 영상의 라벨 조회·저장, 영상 재생, 프레임 이미지 조회가 모두 막힌다. 해소 후 재개 지점은 신고 단계에 따라 갈린다 — 라벨링에서 신고하면 프레임 이미지만 다시 만들고 기존 마킹·라벨을 유지한 채 이어서 작업하고, 마킹에서 신고하면 마킹부터 다시 시작한다. 이 안내는 제출 전에 모달 안에서 미리 보여준다. 파생영상 등 이 화면에서 신고 자체가 불가능한 경우 불가 사유를 지정하면 버튼을 비활성화하고 사유를 툴팁으로 안내한다(제출 후 거부당하는 것을 막기 위해 미리 차단). 서버 응답 409(이미 재처리 중)/403(본인 배정 아님)/404(영상 없음)/412(파생영상 또는 비식별 미수행)는 모달 안에 문구로 노출한다.

## props_schema

### target

- **type**: {scope:'frame', srcSn:number} | {scope:'video', rawSn:number}
- **required**: true
- **description**: 신고 대상. 라벨링은 프레임(srcSn), 마킹은 영상(rawSn) 중 하나만 지정한다. 서버가 신고 단계를 함께 기록해 재처리 완료 후 재개 지점을 가른다.

### unsupportedReason

- **type**: string
- **required**: false
- **description**: 이 영상에서는 신고 자체가 불가능할 때의 사유(예: 파생영상). 지정하면 버튼을 비활성화하고 사유를 툴팁으로 안내한다.

### disabled

- **type**: boolean
- **required**: false
- **description**: 잠금·원본 보기·포털 모드 등 그 외 사유로 버튼을 비활성화한다.

### onReported

- **type**: () => void
- **required**: false
- **description**: 신고 성공 후 콜백. 잠금 상태 반영 등 호출부 갱신에 사용한다.

## usage_example

라벨링 화면(SCREEN-005) 헤더와 마킹 화면(SCREEN-006) 헤더에 공용으로 배치한다. 화면별로 컴포넌트를 복제하지 않고 target 만 다르게 전달한다. 포털 라벨링 화면(SCREEN-029)에는 노출하지 않는다(내부 채널 전용).

## design_system_id

DS-001

## accessibility_notes

버튼 aria-label 은 기본 '비식별 누락 신고'이며, unsupportedReason 이 있으면 '비식별 누락 신고 — {사유}'로 사유를 함께 읽어준다. 신고 사유 입력 필드는 label 로 연결하고 글자 수 안내를 병기한다.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-006


---

<!-- UI-058 -->

# display: ReviewLabelCanvas

## name

ReviewLabelCanvas

## tags

- feature:review
- display
- canvas
- konva
- readonly

## category

display

## description

검수 화면 읽기 전용 라벨 캔버스(SCR-REVIEW-002). 현재 프레임 이미지를 인증된 요청으로 개별 발급받아 표시하고, 그 위에 BBOX/POLYGON 라벨을 카테고리별 색상으로 오버레이한다. 이미지 레이어와 라벨 레이어를 분리해 hover 재렌더가 이미지에 영향을 주지 않는다. hover 시 라벨명과 카테고리 내 순번을 칩으로 표시하고, 클릭으로 라벨을 선택한다 — 선택·hover 는 우측 객체 목록 패널과 양방향 동기화된다. 스켈레톤(키포인트) 라벨이 있으면 그리기·이동 없이 표시 전용으로 함께 오버레이한다. 휠 줌·배경 드래그 팬과 '화면 맞춤' 버튼으로 뷰포트를 조정할 수 있다. 이슈 추가 모드에서는 라벨 클릭이 선택 대신 그 라벨에 대한 이슈 카드 추가로 동작한다. 새 도형을 그리는 편집 기능은 두지 않는다(읽기 전용).

## props_schema

### frame

- **type**: FrameDetail
- **required**: true
- **description**: 현재 프레임 데이터. 이미지 발급 키와 라벨 배열을 포함한다.

### loading

- **type**: boolean
- **required**: false
- **description**: 프레임 목록 조회 진행 중 여부. true면 중앙 스피너를 표시하고 빈 상태 문구를 숨긴다.

### selectedLabelId

- **type**: number | null
- **required**: false
- **description**: 선택된 라벨 id. 우측 객체 목록 패널과 양방향으로 동기화된다.

### onSelect

- **type**: (id: number | null) => void
- **required**: false
- **description**: 라벨 선택이 바뀔 때 호출된다.

### keypoints

- **type**: KeypointInstance[]
- **required**: false
- **description**: 현재 프레임의 스켈레톤(키포인트) 인스턴스. 표시 전용으로 함께 오버레이한다.

### selectedKeypointId

- **type**: number | null
- **required**: false
- **description**: 선택된 키포인트 인스턴스 id.

### onSelectKeypoint

- **type**: (id: number) => void
- **required**: false
- **description**: 키포인트 인스턴스 선택 콜백.

## usage_example

검수 상세 화면(SCREEN-019) 중앙에서 현재 프레임과 그 라벨을 읽기 전용으로 확인할 때 사용한다. 라벨을 새로 그리거나 좌표를 수정하는 라벨링 작업 화면의 편집 캔버스와는 다른 컴포넌트다.

## design_system_id

DS-001

## accessibility_notes

'화면 맞춤' 버튼에 aria-label 이 지정되어 있다. 캔버스 자체는 그리기 요소(Konva Stage)로 렌더되어 시맨틱 마크업이나 키보드 포커스를 제공하지 않는다 — 라벨 목록 열람·선택은 키보드로 접근 가능한 우측 객체 목록 패널을 이용한다.

## referenced_by_screen_ids

- SCREEN-019


---

<!-- UI-059 -->

# [폐기] action: ReviewActionBar

## name

ReviewActionBar

## tags

- feature:review
- action
- approve-reject
- deprecated

## category

action

## description

[폐기] 검수 하단에 별도 액션 바는 두지 않는다. 승인·반려 액션은 검수 헤더(ReviewHeader)가 단독으로 담당한다 — 같은 액션을 두 곳에 두면 상태별 활성 조건과 진행 표시 판정이 갈리기 때문이다. 활성 조건(검수대기·검수중일 때만 활성, 완료·반려 시 비활성+안내, 두 액션 동시 진행 불가)은 헤더 쪽 사양을 따른다.

## props_schema

### status

- **type**: ReviewStatus
- **required**: true

### onApprove

- **type**: () => void
- **required**: true

### onReject

- **type**: () => void
- **required**: true

### isPending

- **type**: boolean
- **required**: false

## design_system_id

DS-001

## referenced_by_screen_ids

- SCREEN-019


---

<!-- UI-060 -->

# layout: ReviewHeader

## name

ReviewHeader

## tags

- feature:review
- layout
- header

## category

layout

## description

검수 화면 상단 라이트 헤더(높이 56px). 좌: 닫기 + 영상명(없으면 영상 번호) + 작업자·제출일시. 우: 상태 배지(StatusBadge) + 반려 + 승인. 승인·반려는 검수대기(REVIEW_PENDING)·검수중(REVIEWING) 일 때만 활성이고, 이미 승인·반려된 건은 재판정할 수 없다. 두 액션은 동시에 진행되지 않으며 한 쪽이 진행 중이면 다른 쪽도 비활성이다. 프레임 위치 표시·이동은 이 헤더에 두지 않는다 — 프레임 타임라인(ReviewFrameTimeline) 이 단독으로 담당해 이동 진입점이 둘로 갈리지 않게 한다. 화면 좌표를 직접 지정하는 마커는 두지 않는다.

## props_schema

### videoId

- **type**: number
- **required**: true
- **description**: 검수 대상 영상 식별자. 영상명이 없을 때 대체 표기에도 쓴다.

### cctvName

- **type**: string
- **required**: true
- **description**: 영상명(CCTV 명).

### workerName

- **type**: string
- **required**: true
- **description**: 제출한 라벨링 작업자.

### submittedAt

- **type**: string
- **required**: true
- **description**: 검수 제출 일시(ISO-8601). 화면에는 현지 시각으로 표시한다.

### status

- **type**: ReviewStatus
- **required**: true
- **description**: 검수 상태. 상태 배지 표시와 승인·반려 활성 판정의 단일 근거다.

### isApproving

- **type**: boolean
- **required**: false
- **description**: 승인 요청 진행 중. 진행 표시와 두 액션 비활성을 함께 유발한다.

### isRejecting

- **type**: boolean
- **required**: false
- **description**: 반려 요청 진행 중. 진행 표시와 두 액션 비활성을 함께 유발한다.

### onClose

- **type**: () => void
- **required**: true
- **description**: 검수 목록으로 닫기.

### onApprove

- **type**: () => void
- **required**: false
- **description**: 승인. 검수 완료로 작업을 종결시킨다.

### onReject

- **type**: () => void
- **required**: false
- **description**: 반려. 반려 사유 입력 모달(RejectModal)을 거친다.

## usage_example

검수 상세 화면(SCREEN-019) 최상단에 항상 고정 표시된다. 목록 화면에서 개별 검수 항목에 진입할 때만 쓰이며, 라벨링 작업 화면 상단바(별도 컴포넌트)와는 다른 헤더다.

## design_system_id

DS-001

## accessibility_notes

닫기 버튼은 아이콘만 표시되므로 aria-label(예: '검수 목록으로 닫기')이 지정되어 있다. 승인·반려 버튼은 아이콘과 함께 보이는 텍스트 레이블을 가져 별도 aria-label 없이도 스크린리더에 노출된다. 두 액션이 모두 비활성 처리될 때는 버튼의 disabled 속성만으로는 사유가 전달되지 않으므로, 비활성 사유(이미 처리된 검수)를 별도 텍스트 안내로 함께 제공해야 한다.

## referenced_by_screen_ids

- SCREEN-019


---

<!-- UI-061 -->

# [폐기] data: IssueSidebar

## name

IssueSidebar

## tags

- feature:review
- data
- issues
- sidebar
- deprecated

## category

data

## description

[폐기] 이 화면에는 별도의 이슈 사이드바를 두지 않는다. 프레임·객체별 이슈 카드를 누적해 보여주던 기능은 검수 메모 패널(ReviewMemoPanel)의 메모 목록으로 흡수되었고, 서버에 등록·댓글·해결까지 처리하는 문의 스레드는 '이슈' 탭이 별도로 담당한다.

## props_schema

### reviewId

- **type**: number
- **required**: true

### defaultFrameId

- **type**: number
- **required**: false

## design_system_id

DS-001

## referenced_by_screen_ids

- SCREEN-019


---

<!-- UI-062 -->

# overlay: RejectModal

## name

RejectModal

## tags

- feature:review
- overlay
- reject
- composed-from:Modal

## category

overlay

## description

검수 반려 모달. Modal 과 Textarea 로 반려 사유(1~1000자, 텍스트만)를 입력받는다 — 반려 사유 입력 외의 다른 입력 요소는 추가하지 않는다. 제출 시점까지 누적된 검수 메모·의견이 사용자가 입력한 사유에 합쳐져 최종 반려 사유로 전송된다. 사유가 비어 있거나 처리 중이면 제출할 수 없다. 반려 처리 후 작업은 재작업 상태로 작업자에게 돌아간다.

## props_schema

### reviewId

- **type**: number
- **required**: true
- **description**: 반려 대상 검수 식별자.

### open

- **type**: boolean
- **required**: true
- **description**: 모달 표시 여부.

### onClose

- **type**: () => void
- **required**: true
- **description**: 취소 또는 반려 완료 후 모달을 닫을 때 호출.

### onSuccess

- **type**: () => void
- **required**: false
- **description**: 반려 처리 성공 후 호출(예: 목록으로 이동).

### composeReason

- **type**: (userReason: string) => string
- **required**: false
- **description**: 제출 직전 사용자 입력 사유에 검수 메모·의견 등 추가 컨텍스트를 합성하는 콜백. 미지정 시 사용자 입력만 그대로 전송된다.

## usage_example

검수 액션에서 반려를 선택했을 때 여는 모달이다. 승인에는 쓰이지 않으며, 라벨링 작업 화면의 비식별 신고 모달과는 별개 기능이다.

## design_system_id

DS-001

## accessibility_notes

반려 사유 입력란은 label 이 연결되어 있고, 유효성 오류 시 aria-invalid 와 aria-describedby 로 오류 메시지(role=alert)를 연결한다. 제출 버튼은 사유가 비어 있거나 처리 중이면 비활성화된다.

## referenced_by_screen_ids

- SCREEN-019


---

<!-- UI-063 -->

# navigation: ReviewFrameTimeline

## name

ReviewFrameTimeline

## tags

- feature:review
- navigation
- timeline
- filmstrip

## category

navigation

## description

검수 화면에서 프레임 위치 표시와 이동을 전담하는 컴포넌트다(헤더에는 두지 않는다). 가로 스크롤 썸네일 스트립과 진행률 바, 'x/y' 위치 텍스트로 구성된다. 썸네일 클릭 또는 좌우 방향키로 프레임을 이동하면 현재 프레임 썸네일이 자동으로 스크롤되어 보이는 위치로 온다. 각 썸네일 테두리 색은 프레임 상태를 우선순위로 나타낸다: 현재 프레임(강조) > 미해소 문의가 있는 프레임(경고색) > 라벨이 저장된 프레임(완료색) > 기본. 프레임이 0건이면 안내 문구를 표시한다. 처음·이전·다음·마지막 프레임 이동 버튼과 프레임 번호 직접 입력, 위치 슬라이더는 헤더 바로 아래의 별도 상단 내비게이션 바가 제공하며, 이 컴포넌트와 같은 현재 프레임 상태를 공유한다.

## props_schema

### frames

- **type**: FrameStripItem[]
- **required**: true
- **description**: 표시할 프레임 목록. 각 항목은 프레임 식별자와 라벨 저장 여부를 담는다.

### frameIndex

- **type**: number
- **required**: true
- **description**: 현재 프레임 인덱스(0-based). 진행률 바·위치 텍스트·강조 테두리의 기준이 된다.

### onSelectFrame

- **type**: (index: number) => void
- **required**: true
- **description**: 썸네일 클릭 또는 방향키로 프레임을 이동할 때 호출된다.

### inquirySrcSns

- **type**: Set<number>
- **required**: false
- **description**: 미해소 문의가 걸린 프레임 식별자 집합. 경고색 테두리로 표시한다.

## usage_example

검수 상세 화면(SCREEN-019)에서 캔버스와 함께 항상 표시된다. 프레임이 1건 이하이면 표시하지 않거나 빈 상태 문구로 대체한다.

## design_system_id

DS-001

## accessibility_notes

타임라인 영역은 aria-label 이 지정된 region 이며 포커스 가능해 좌우 방향키 이동을 받는다. 진행률 바는 role=progressbar 로 현재/전체 위치를 aria-value* 로 노출한다. 각 썸네일은 실제 버튼 요소이며 alt/aria-label 로 '프레임 N'을 표시하고, 현재 프레임은 aria-current 로 표시된다.

## referenced_by_screen_ids

- SCREEN-019


---

<!-- UI-064 -->

# [폐기] data: ObjectListPanel

## name

ObjectListPanel

## tags

- feature:review
- data
- tree
- objects
- deprecated

## category

data

## description

[폐기] 검수 화면에 전용 객체 목록 컴포넌트를 따로 두지 않는다. 검수도 라벨링과 같은 객체 트리(ObjectClassTree)를 쓰며, 편집 핸들러를 넘기지 않는 것으로 읽기 전용 목록이 된다. 검수 전용으로 여기 적혀 있던 사양 — 목록 상단 객체 수 배지, 캔버스와의 선택·hover 양방향 동기화, 외부 선택 시 그룹 자동 펼침, 세그멘테이션·트랙 형태 배지, 행의 키보드 선택 — 은 모두 그 항목으로 옮겨졌다. 같은 화면 요소를 두 항목으로 나눠 적으면 한쪽만 갱신돼 서로 다른 표시 규칙을 말하게 된다.

## props_schema

_(empty)_

## usage_example

새로 쓰지 않는다. 검수 화면의 객체 목록은 라벨링 객체 트리(ObjectClassTree) 항목을 보고 구현한다.

## design_system_id

DS-001

## accessibility_notes



## referenced_by_screen_ids

- SCREEN-019


---

<!-- UI-065 -->

# input: ReviewMemoPanel

## name

ReviewMemoPanel

## tags

- feature:review
- input
- memo

## category

input

## description

검수 메모 패널(우측 하단, '객체' 탭). 프레임·객체별 메모 목록 — 메모를 추가하면 그 시점의 현재 프레임 번호와 선택 객체가 자동으로 태깅되며, 별도의 추가 모드 전환은 두지 않는다. 각 메모는 최대 1000자이며 인라인으로 내용을 수정하거나 삭제할 수 있다. 서버에 저장되지 않는 이 화면 전용 메모이며, 반려 시 반려 사유에 합쳐진다. 단일 검수 의견 입력란과 첨부파일 기능은 두지 않는다 — 의견은 프레임·객체별로 여러 건 작성 가능한 메모 목록으로 대체된다. 이 화면 전용 메모와 별개로, 검수 요청 이전에 이미 서버에 등록되어 있던 이슈가 있으면 같은 목록에 참고용(읽기 전용)으로 함께 표시된다.

## props_schema

### memos

- **type**: ReviewMemo[]
- **required**: true
- **description**: 프레임·객체별로 누적된 이 화면 전용 메모 목록.

### contextFrameNo

- **type**: number
- **required**: true
- **description**: 다음 메모가 태깅될 현재 프레임 번호.

### contextObjectTag

- **type**: string | null
- **required**: false
- **description**: 다음 메모가 태깅될 선택 객체 표기(예: '사람 #2'). 객체 미선택이면 null.

### onAdd

- **type**: (text: string) => void
- **required**: true
- **description**: 메모 추가.

### onUpdate

- **type**: (id: number, text: string) => void
- **required**: true
- **description**: 메모 내용 수정.

### onRemove

- **type**: (id: number) => void
- **required**: true
- **description**: 메모 삭제.

### issues

- **type**: ReviewIssue[]
- **required**: false
- **description**: 검수 요청 이전에 서버에 이미 등록되어 있던 이슈. 같은 목록에 참고용(읽기 전용)으로 표시된다.

## usage_example

검수 상세 화면(SCREEN-019) 우측 '객체' 탭 하단, 객체 속성 패널 아래에 표시된다. 라벨 자체를 수정하는 기능은 없으며 검수 의견을 남기는 용도로만 쓰인다.

## design_system_id

DS-001

## accessibility_notes

메모 추가 입력은 Enter 키로 제출되며 한글 입력기 조합 중 Enter 는 무시한다. 추가·삭제 버튼에는 aria-label 이 지정되어 있고, 메모별 textarea 는 aria-label '메모 내용'으로 스크린리더에 노출된다.

## referenced_by_screen_ids

- SCREEN-019


---

<!-- UI-066 -->

# data: VersionList

## name

VersionList

## tags

- feature:version
- data
- list

## category

data

## description

검수완료 버전(스냅샷) 커밋 목록. 최신순으로 나열하며 각 행에 shortHash(mono)·저장 사유·작성자·커밋 일시를 표시한다. '최신'(시간순으로 가장 앞, idx 0)과 '현재'(isCurrent, 지금 활성인 스냅샷) 배지는 서로 독립적으로 붙는다 — 롤백 이후에는 활성본이 시간상 최신이 아닌 과거 커밋으로 이동해 두 배지가 서로 다른 행에 붙을 수 있다. 선택은 두 축이 상호 배타적이다: 행 본문 클릭(단일 선택)은 그 버전과 현재 작업본을 비교하는 선택이고, 체크박스(최대 2건)는 버전 간 비교 선택이다. 체크박스가 하나라도 켜지면 행 클릭 선택은 비활성화된다. 체크 2건 상태에서는 각 행에 기준(from, 더 오래된 쪽)/비교(to, 더 최신 쪽) 역할 배지를 추가로 표시한다. 롤백 트리거는 이 목록에 두지 않는다 — 단일 선택한 버전이 최신이 아닐 때만 노출되는 별도 롤백 버튼(패널 단위)으로 일원화한다. 목록이 비어 있으면 EmptyState('버전 이력이 없습니다')를 보여준다.

## props_schema

### versions

- **type**: Version[]
- **required**: true
- **description**: 표시할 버전(커밋) 목록. BE 가 최신순으로 응답한 순서를 그대로 사용한다(idx 0 = 최신).

### selectedHash

- **type**: string | null
- **required**: true
- **description**: 단일 선택(현재 작업본과 비교) 대상 commitSha. checkedHashes 가 1건 이상이면 무시된다.

### checkedHashes

- **type**: string[]
- **required**: true
- **description**: 버전 간 비교용 체크 목록(최대 2). 2건이 채워지면 더 최신 쪽이 비교(to), 더 오래된 쪽이 기준(from)이 된다.

### onSelect

- **type**: (commitSha: string) => void
- **required**: true
- **description**: 행 본문 클릭 처리. checkedHashes 가 비어 있을 때만 유효하며, 그 외에는 클릭 대상이 비활성화된다.

### onCheck

- **type**: (commitSha: string, checked: boolean) => void
- **required**: true
- **description**: 체크박스 토글 처리. 체크를 켜면 단일 선택(selectedHash)은 해제된다.

## usage_example

SCREEN-005(라벨링 캔버스) 우측 히스토리 패널의 '버전' 탭 안, 커밋 목록 표시에 사용한다. 목록이 비어 있는 상태(EmptyState)는 이 컴포넌트가 자체적으로 처리하므로, 상위 패널은 로딩/에러 상태만 별도로 분기하면 된다.

## design_system_id

DS-001

## accessibility_notes

체크박스에는 '커밋 {shortHash} 선택' 형태의 aria-label 을 부여해 스크린리더가 각 행을 구분한다. 행 선택 버튼은 짧은 해시·메시지·작성자 텍스트를 그대로 노출해 별도 라벨 없이도 내용이 read out 된다. 체크박스가 하나 이상 켜지면 행 선택 버튼에 disabled 를 걸어 두 선택 모드가 동시에 활성화되지 않게 한다(비활성 상태에서는 포커스도 받지 않는다).

## referenced_by_screen_ids

- SCREEN-010
- SCREEN-005


---

<!-- UI-067 -->

# display: DiffViewer

## name

DiffViewer

## tags

- feature:version
- display
- diff

## category

display

## description

두 버전(또는 한 버전과 현재 작업본) 간 라벨 변경 표시. diff 항목을 ADDED(추가/초록)·MODIFIED(수정/노랑)·REMOVED(삭제/빨강)로 색상 분리하고, 색상만으로 종류를 구분하지 않도록 [추가]/[수정]/[삭제] 텍스트 라벨을 아이콘·색상과 함께 병기한다. 각 행은 [종류] + 프레임ID + objectId(mono) + 이전/이후 shape 요약을 보여준다. shape 요약은 형태별로 다르다 — BBOX 는 좌표 4개, POLYGON 은 점 개수, KEYPOINT 는 관절 개수, MASK 는 가로x세로 크기. MODIFIED 판정 자체는 라벨 식별자·라벨 형태·라벨명·라벨 마스터 식별자(labelId)·좌표·트랙 식별자 6개 축을 비교해 내려지지만, 그 축 값(라벨명·labelId·trackId) 자체는 화면에 렌더하지 않는다 — AI 보조 메타·표시용 라벨명/색상은 의도적으로 제외한다. 트랙 병합처럼 시스템이 자체적으로 라벨을 재작성한 변경도 이 축에 포함되어 MODIFIED 로 잡힌다. 변경 0건은 빈 목록이 아니라 '변경 없음' 안내로 구분해서 보여준다. 로딩·에러 상태와 비교 대상 텍스트(예: 'a1b2c3d → 현재 작업본')는 이 컴포넌트가 아니라 상위 패널이 소유·표시한다 — 이 컴포넌트는 결과 렌더(빈 상태 포함)만 담당한다.

## props_schema

### diffs

- **type**: LabelDiff[]
- **required**: true
- **description**: diff 항목 배열. type 은 ADDED|MODIFIED|REMOVED, before/after 는 해당 없는 쪽이 null(ADDED=before null, REMOVED=after null).

### emptyTitle

- **type**: string
- **required**: false
- **description**: diffs 가 빈 배열일 때 보여줄 제목. 비교 축(작업본 비교/버전간 비교)마다 문구가 달라야 하므로 기본값 대신 호출부가 지정한다.

### emptyMessage

- **type**: string
- **required**: false
- **description**: diffs 가 빈 배열일 때 보여줄 설명 문구. 미지정 시 두 버전 비교 기준의 기본 문구를 쓴다.

## usage_example

히스토리 패널의 '버전' 탭에서 커밋 선택(단일=작업본 비교, 2건 체크=버전간 비교) 결과를 표시할 때 사용한다. 선택이 아예 없는 상태는 이 컴포넌트를 렌더하지 않고 상위 패널이 별도 안내 문구로 대체한다 — 빈 diffs 배열(변경 없음)과 미선택 상태를 같은 화면으로 섞지 않는다.

## design_system_id

DS-001

## accessibility_notes

변경 종류를 색상에만 의존해 전달하지 않도록 [추가]/[수정]/[삭제] 텍스트 라벨을 아이콘·색상과 함께 항상 병기한다(색각 이상 대응).

## referenced_by_screen_ids

- SCREEN-010
- SCREEN-005


---

<!-- UI-068 -->

# [폐기] input: VersionPicker

## name

VersionPicker

## tags

- feature:version
- input
- composed-from:Select

## category

input

## description

[폐기] 이 화면은 드롭다운형 버전 선택 컴포넌트를 두지 않는다. 버전 비교 대상 선택은 커밋 목록(UI-066)에서 이루어진다 — 행 본문 클릭(단일)은 현재 작업본과의 비교, 체크박스(최대 2건)는 두 버전 간 비교이며 두 방식은 상호 배타적이다(체크박스가 하나라도 켜지면 클릭 선택은 비활성화된다). Select 드롭다운으로 비교 버전을 고르는 방식은 채택하지 않는다.

## props_schema

### label

- **type**: string
- **required**: true

### value

- **type**: string
- **required**: true

### onChange

- **type**: (commitSha:string)=>void
- **required**: true

### versions

- **type**: Version[]
- **required**: true

## design_system_id

DS-001

## referenced_by_screen_ids

_(empty)_


---

<!-- UI-069 -->

# overlay: RollbackConfirmModal

## name

RollbackConfirmModal

## tags

- feature:version
- overlay
- rollback
- composed-from:ConfirmDialog

## category

overlay

## description

롤백 확인 모달(SCR-HIST-002). danger 변형 ConfirmDialog 를 재사용하며, 확인 클릭 시 롤백 mutation 을 실행한다. 성공 시 라벨 히스토리(현재 프레임과 형제 프레임 포함)와 버전 목록을 모두 무효화해 라벨링 캔버스와 히스토리 패널을 함께 갱신한다. 롤백은 대상 스냅샷 행을 재활성하는 것이며 새 버전 행을 적층하지 않는다 — 현재 활성 스냅샷이 이미 그 대상이면(예: 롤백 이후 같은 대상을 다시 선택) 서버가 아무 것도 바꾸지 않는 무해한 no-op 으로 처리하고, 화면은 이를 일반 성공과 동일하게 처리한다(모달 닫힘 + 선택 해제). 다른 저장·AI 작업이 진행 중이면(편집 잠금) 확인 클릭을 막고 안내를 띄운 뒤 모달을 닫는다 — 롤백은 서버측 라벨 재작성이라 저장 진행 중과 교차 실행되면 최종본이 결정되지 않기 때문이며, 이는 트리거 버튼 자체를 비활성화하는 것과는 별개의 이중 방어다(모달이 열린 뒤 장시간 작업이 새로 시작될 수 있다). 권한(REVIEWER 또는 본인 배정 WORKER)·commit SHA 형식 검증은 BE 가 수행하고, 트리거 노출 여부(최신이 아닌 버전을 선택했을 때만)는 상위 패널 책임이다.

## props_schema

### open

- **type**: boolean
- **required**: true
- **description**: 모달 표시 여부.

### commitSha

- **type**: string
- **required**: true
- **description**: 롤백 대상 버전 식별자(라벨 스냅샷 SHA-256 hex). 형식 검증은 BE 가 수행한다.

### shortHash

- **type**: string
- **required**: true
- **description**: 확인 문구에 보여줄 표시용 짧은 해시.

### srcSn

- **type**: number
- **required**: true
- **description**: 롤백 대상 프레임(LS_DATA_SRC.SRC_SN).

### onClose

- **type**: () => void
- **required**: true
- **description**: 취소 또는 편집 잠금으로 인한 강제 닫힘 처리.

### onSuccess

- **type**: () => void
- **required**: true
- **description**: 롤백 성공(no-op 포함) 시 호출 — 호출부가 선택 상태를 초기화한다.

## usage_example

히스토리 패널의 '버전' 탭에서 최신이 아닌 커밋을 선택했을 때만 노출되는 '롤백' 버튼의 확인 모달로 사용한다. 트리거 버튼은 다른 저장·AI 작업이 진행 중이면 이미 비활성화되므로 이 모달이 열리는 시점에는 원칙적으로 충돌이 없지만, 모달이 열린 뒤 새 작업이 시작되는 경합에 대비해 확인 클릭 시점에도 다시 검사한다.

## design_system_id

DS-001

## accessibility_notes

공용 확인 모달을 사용해 role=dialog·aria-modal=true, 포커스 트랩(Tab 순환), ESC 닫기, 닫힘 시 트리거 요소로 포커스 복귀를 제공한다. 처리 중(loading)에는 확인·취소 버튼에 loading/disabled 상태를 반영해 중복 제출을 막는다.

## referenced_by_screen_ids

- SCREEN-010
- SCREEN-005


---

<!-- UI-070 -->

# layout: HistoryPanel

## name

HistoryPanel

## tags

- feature:version
- layout
- composite
- history

## category

layout

## description

라벨링 캔버스(SCREEN-005) 우측에 임베드하는 히스토리 패널. 열림/닫힘 상태와 진입 트리거는 호출부가 소유하며, 닫기 콜백이 전달됐을 때만 패널 내부에 닫기 버튼을 노출한다(ESC 키로도 닫힌다). 상단은 '저장 이력'(기본 탭)과 '버전' 2개 탭으로 구성한다. 이 화면은 독립 페이지(URL)를 두지 않는다 — 과거에 있었던 별도 라우트는 폐기했다.

저장 이력 탭은 저장(PUT) 1회 단위 변경을 최신순으로 보여주는 조회 축이다 — 각 항목은 추가/수정/삭제 건수 요약과 작성자·시각을 보여주고, 펼치면 라벨 단위 변경 상세를 노출한다. 되돌리기 콜백이 전달된 경우에만 각 항목에 '되돌리기' 버튼을 노출한다 — 이 되돌리기는 그 저장 이벤트의 변경분을 현재 작업본(다음 저장 전까지의 미저장 상태)에 역적용하는 것이며, 서버 롤백과 달리 즉시 반영되거나 새 버전을 만들지 않는다. 다른 저장·AI 작업이 진행 중이면 되돌리기 버튼도 비활성화된다. 작업본 컨텍스트가 없는 진입(콜백 미전달)에서는 되돌리기 버튼을 아예 렌더하지 않는다.

버전 탭은 검수 승인 시점 스냅샷 비교와 롤백을 제공한다(커밋 목록 + diff 표시 + 롤백 확인 조합). 커밋 1건 클릭 = 현재 작업본과 비교, 체크박스 2건 = 두 버전 간 비교이며 비교 대상 텍스트(예: 'a1b2c3d → 현재 작업본')를 diff 위에 표시한다. 단일 선택한 버전이 최신(시간순 idx 0)이 아닐 때만 '롤백' 버튼을 노출한다 — 이 조건은 '현재 활성 스냅샷이 아닐 때'가 아니므로, 롤백 이후 활성본이 과거 커밋으로 이동한 상태에서 그 커밋을 다시 선택해도 버튼이 뜨고 클릭하면 무해한 no-op 이 된다. 다른 저장·AI 작업이 진행 중이면 롤백 버튼이 비활성화되고 툴팁으로 안내한다.

조회는 WORKER+REVIEWER, 롤백은 REVIEWER 또는 본인 배정 WORKER이며 본인 배정 여부는 BE 가 검증한다.

## props_schema

### srcSn

- **type**: number
- **required**: true
- **description**: 히스토리 조회·롤백·되돌리기 전부의 기준 프레임(LS_DATA_SRC.SRC_SN).

### onClose

- **type**: () => void
- **required**: false
- **description**: 지정 시 패널 내부에 닫기 버튼을 노출하고 ESC 키로도 닫는다. 미지정 시 닫기 버튼을 렌더하지 않는다(호출부가 별도 UI로 닫힘을 제어).

### defaultTab

- **type**: 'changes' | 'versions'
- **required**: false
- **description**: 초기 활성 탭. 기본은 '저장 이력'(changes) — 저장 직후 진입 시 기대 화면을 바로 보여준다. 버전 브라우징이 목적인 진입에서는 'versions' 를 전달한다.

### onRevert

- **type**: (item: LabelHistoryItem) => void
- **required**: false
- **description**: 저장 이력 항목의 '되돌리기' 요청 콜백. 작업본 컨텍스트가 있는 진입에서만 전달한다 — 미전달 시 되돌리기 버튼을 렌더하지 않는다.

## usage_example

SCREEN-005(라벨링 캔버스) 우측 슬라이드 영역에 삽입해 쓰는 임베드형 패널이다 — 열림/닫힘 상태와 트리거 버튼은 호출부가 소유하고, onClose 를 전달한 경우에만 패널 내부에 닫기 버튼이 노출된다. 라벨 저장 직후 진입할 때는 기본 탭을 '저장 이력'으로 열어 방금 저장한 변경을 바로 보여주고, 버전 브라우징이 목적일 때는 기본 탭을 '버전'으로 지정한다.

## design_system_id

DS-001

## accessibility_notes

탭 전환은 role=tablist/tab, aria-selected, aria-controls 로 연결되며 각 탭 패널은 role=tabpanel + aria-labelledby 로 탭과 연결된다. 다만 화살표 키 좌우 이동(roving tabindex)은 구현하지 않아 각 탭 버튼은 Tab 키로 개별 포커스한다. 닫기 버튼에는 '히스토리 닫기' aria-label 을 부여한다.

## referenced_by_screen_ids

- SCREEN-010
- SCREEN-005


---

<!-- UI-071 -->

# input: ProcessKindCard

## name

ProcessKindCard

## tags

- feature:augment
- input
- card
- radio

## category

input

## description

증강 요청 화면에서 처리 종류 하나를 고르는 라디오 카드. WINTER/NIGHT/RAIN(외부 위탁 증강) 및 RESOLUTION(해상도 변경, 저작도구 내부 수행) 4종 중 하나를 선택하면 아이콘·제목·설명과 선택 표시(체크)가 뜬다. 부모가 role=radiogroup으로 감싸고 카드 각각은 role=radio + aria-checked로 단일 선택 상태를 표현하며, 로빙 tabIndex(선택된 카드만 0)와 화살표 키 이동(onKeyDown)으로 키보드만으로 탐색·선택할 수 있다. disabled는 제출 처리 중일 때뿐 아니라, 대상 영상이 이미 파생영상(원본이 아닌 증강/해상도 결과물)이라 증강 요청 자체가 영구히 허용되지 않는 경우에도 쓰인다 — 파생 깊이를 1로 고정하는 정책상 재시도 여지가 없는 조건이며, 이때는 요청 진입 자체가 막히고 사유가 툴팁으로 안내된다.

## props_schema

### kind

- **type**: ProcessKind
- **required**: true
- **description**: 카드가 나타내는 처리 종류 — WINTER/NIGHT/RAIN(증강) 또는 RESOLUTION(해상도 변경).

### selected

- **type**: boolean
- **required**: true
- **description**: 현재 라디오그룹에서 선택된 카드인지. true면 강조 스타일 + 체크 아이콘 + aria-checked=true.

### onSelect

- **type**: () => void
- **required**: true
- **description**: 카드 클릭 또는 키보드 선택 시 호출.

### disabled

- **type**: boolean
- **required**: false
- **description**: 비활성화 — 제출 처리 중이거나, 대상 영상이 파생영상이라 요청이 영구히 불가한 경우.

### tabIndex

- **type**: number
- **required**: false
- **description**: 로빙 tabIndex — 부모 radiogroup 안에서 선택(또는 진입 대표) 카드만 0, 나머지는 -1.

### onKeyDown

- **type**: (e: React.KeyboardEvent<HTMLButtonElement>) => void
- **required**: false
- **description**: 부모 radiogroup의 화살표 키 탐색 핸들러.

## usage_example

증강 요청 화면에서 처리 종류 4개 카드를 role=radiogroup 컨테이너 안에 그리드로 배치해 단일 선택을 받는다. 선택 후 유형에 따라 증강 생성 조건 입력 폼 또는 해상도 선택 폼이 이어서 나타난다.

## design_system_id

DS-001

## accessibility_notes

role=radio + aria-checked, 부모 role=radiogroup(WCAG 4.1.2). 로빙 tabIndex로 그룹 내 Tab 이동은 1스톱, 화살표 키로 카드 간 이동. 포커스 링 적용. 선택 상태는 체크 아이콘 + 배경색 변화로 함께 표시(색상 단독 아님).

## referenced_by_screen_ids

- SCREEN-022


---

<!-- UI-072 -->

# display: JobCard

## name

JobCard

## tags

- feature:augment
- display
- card
- job

## category

display

## description

증강 잡 이력 카드. CCTV명 + 상태 배지 + 처리 종류 배지 목록 + 영상 건수 + 요청 일시를 보여주며 카드 전체가 버튼으로 클릭 시 해당 잡의 결과 화면으로 이동한다. 상태 배지는 잡 집계 상태(REQUESTED/IN_PROGRESS/COMPLETED/FAILED)를 표시하는 축으로, 결과 항목별 채택/거부 같은 사람의 활용 결정 축과는 별개다 — 집계 내 모든 항목이 종료 상태(채택/반려/취소)에 도달하면 COMPLETED로 내려오므로 취소로 끝난 잡도 COMPLETED가 되며, 성공을 단정하지 않는 중립 문구('처리 종료')로 보정해 표시한다. 처리 종류 배지는 외부 위탁 증강 3종(겨울/야간/비)과 저작도구 내부 수행인 해상도 파생을 서로 다른 톤으로 구분해 병기하며, 증강 없이 해상도 파생만 있는 잡에는 '파생' 배지를 추가로 붙여 증강 검수 완료로 오인하지 않게 한다. 동일 (영상×처리종류) 재요청이 허용되므로 같은 영상에 대해 여러 잡 카드가 동시에 존재할 수 있다.

## props_schema

### job

- **type**: AugmentJob
- **required**: true
- **description**: 카드 1건이 표시할 증강 잡 데이터 — CCTV명·잡 상태·요청 처리종류 목록·해상도 파생 목록·영상 건수·요청 일시·잡 식별자를 담는다.

## usage_example

증강 요청/결과 화면의 최근 잡 이력 그리드에서 반복 렌더. 클릭 시 그 잡의 결과 화면으로 이동한다.

## design_system_id

DS-001

## accessibility_notes

카드 전체가 네이티브 button 요소(키보드 포커스 + Enter/Space 활성화 가능), 포커스 링 적용. 상태·처리종류는 배지의 색상과 텍스트를 함께 표시해 색상 단독으로 정보를 전달하지 않는다.

## referenced_by_screen_ids

- SCREEN-022
- SCREEN-023


---

<!-- UI-073 -->

# action: DecisionCard

## name

DecisionCard

## tags

- feature:augment
- action
- decision
- card

## category

action

## description

외부 증강/해상도 결과물 1건에 대한 사람의 활용 결정(채택/거부) 카드. 생성 자체의 성공/실패(잡 상태) 축과는 별개이며, 생성이 끝난 항목에 대해서만 결정 대기 상태가 된다. 상태별 표시 — PENDING: 채택/거부 버튼(거부는 사유 입력 모달에서 검증 후 확정) · ACCEPTED: 결정 일시만 표시(변경 불가) · REJECTED: 결정 일시 + 거부 사유를 표시하고 변경 불가 · CANCELED(사용자 취소로 종결): 활용되지 않는다는 안내만 표시. 반려된 항목은 유예 기간이 지나면 소프트 삭제된 결과물이 실삭제되는 폐기 생명주기를 별도로 가진다 — 아직 지워지지 않았으면 삭제 예정 일시를, 이미 지워졌으면 복구 불가 안내를, 유예 스윕이 꺼져 있으면 일시를 지어내지 않고 삭제 예정이 정해져 있지 않다고만 안내한다. 복구 버튼은 서버가 내려준 복구 가능 여부 값을 그대로 따르며 조건을 재유도하지 않는다(값이 없으면 버튼을 그리지 않는다) — 복구 사유는 별도 확인 모달에서 검증 후 전달한다.

## props_schema

### status

- **type**: AugmentDecision
- **required**: true
- **description**: 활용 결정 상태 — PENDING/ACCEPTED/REJECTED/CANCELED.

### decidedAt

- **type**: string
- **required**: false
- **description**: 결정(채택/거부) 일시. ACCEPTED·REJECTED일 때만 존재.

### rejectReason

- **type**: string
- **required**: false
- **description**: 거부 사유. REJECTED일 때만 존재, 변경 불가로 표시.

### discard

- **type**: AugmentDiscardState | null
- **required**: false
- **description**: 반려 이후 폐기(소프트 삭제) 생명주기 — 삭제 여부와 삭제 예정 일시를 담는다. 결정 축(status)과 별개이며 폐기 표식이 없으면 null/undefined로 와서 안내를 그리지 않는다.

### restoreEligible

- **type**: boolean
- **required**: false
- **description**: 복구 버튼 노출 여부 — 서버가 계산한 복구 사전조건을 그대로 쓴다. 미지정(구버전 응답)이면 버튼을 그리지 않는다.

### onAccept

- **type**: () => void
- **required**: true
- **description**: 채택 버튼 클릭 시 호출.

### onReject

- **type**: (reason: string) => void
- **required**: true
- **description**: 거부 확정(사유 입력 후) 시 호출.

### onRestore

- **type**: (reason: string) => void
- **required**: true
- **description**: 반려 복구 확정(사유 입력 후) 시 호출.

### loading

- **type**: boolean
- **required**: false
- **description**: 결정/복구 요청 처리 중 — 버튼 비활성화.

## usage_example

증강 결과 화면에서 결과 항목 1건마다 렌더. 반려 후 유예 기간 내라면 같은 카드에서 복구까지 이어진다.

## design_system_id

DS-001

## accessibility_notes

결정 상태는 텍스트 라벨(채택됨/거부됨/취소됨)과 테두리·배경 색을 함께 사용해 색상 단독으로 전달하지 않는다. 거부 사유·폐기 안내는 서버 응답값을 텍스트로만 렌더해 자동 이스케이프된다.

## referenced_by_screen_ids

- SCREEN-023


---

<!-- UI-074 -->

# [폐기] input: TimeseriesSidePanel

## name

TimeseriesSidePanel

## tags

- feature:label
- input
- vlm
- timeseries
- deprecated

## category

input

## description

[폐기] 시계열 메타 편집 패널을 이 항목으로 따로 두지 않는다. 같은 패널은 TimeseriesSidePanel(UI-056) 하나로 규정하며, 이 항목에만 있던 규정(손대지 않은 슬롯을 보내지 않는 이유 · 저장 처리 중 입력 비활성화 · 값의 자동 이스케이프 · 접이식 헤더의 펼침 상태 안내 · 글자수 카운터)은 그 항목 본문으로 옮겨 두었다. 시계열 메타 편집이 필요한 화면은 UI-056 을 참조한다.

## props_schema

### srcSn

- **type**: number | undefined
- **required**: true
- **description**: 메타를 조회·수정할 프레임 PK. 프레임 전환 시 부모가 리마운트해 편집 중이던 값을 초기화한다.

## usage_example

라벨링 캔버스 우측 패널의 메타 탭 안에서 프레임 설명 패널과 같은 접이식 헤더 스타일을 공유해 렌더된다.

## design_system_id

DS-001

## accessibility_notes

각 입력 textarea는 항목명과 연결된 aria-label을 가지며 하단에 글자수 카운터를 병기한다. 접이식 섹션 헤더는 시맨틱 button + aria-expanded로 펼침 상태를 알린다.

## referenced_by_screen_ids

- SCREEN-005


---

<!-- UI-075 -->

# [폐기] display: StateChangeTimeline

## name

StateChangeTimeline

## tags

- feature:auto
- display
- timeline

## category

display

## description

[폐기] 이 컴포넌트는 두지 않는다 — 외부 자동 감지 결과의 프레임 간 상태 전이(frame x에서 y로)를 순차 타임라인으로 보여주는 화면 요소는 설계에 포함하지 않는다. 시계열 메타 검토·수정은 자유 서술형 텍스트를 직접 고치는 것만으로 충족하며, 프레임 단위 상태 전이를 별도로 감지·집계해 타임라인으로 보여주는 기능은 두지 않는다.

## props_schema

### changes

- **type**: StateChange[]
- **required**: true

## design_system_id

DS-001

## referenced_by_screen_ids

_(empty)_


---

<!-- UI-076 -->

# display: ConfidenceDistributionChart

## name

ConfidenceDistributionChart

## tags

- feature:video
- display
- chart
- confidence

## category

display

## description

오토라벨 신뢰도 분포 막대 차트. 신뢰도 구간 3단계(90% 이상/70~90%/70% 미만)별 라벨 건수를 막대로 보여주며 각 구간은 서로 다른 의미색(고=success/중=warning/저=danger 계열)으로 구분한다. 영상 상세 화면의 오토라벨 결과 탭에서 '신뢰도 분포' 제목 카드 안에 배치되어 처리 정보 요약·라벨별 분포와 함께 노출된다.

## props_schema

### buckets

- **type**: ConfidenceBucket[]
- **required**: true
- **description**: 구간별(high/mid/low) 건수·비율 목록.

## design_system_id

DS-001

## accessibility_notes

막대마다 X축 텍스트 라벨(구간명)과 수치를 함께 표시해 색상 단독으로 구간을 구분하지 않는다.

## referenced_by_screen_ids

- SCREEN-009


---

<!-- UI-077 -->

# data: MyTasksTable

## name

MyTasksTable

## tags

- feature:dashboard
- data
- table

## category

data

## description

WORKER 전용 — 로그인한 작업자 본인의 최근 작업 5건을 표로 보여준다(영상명/상태/진행률 3컬럼). 진행률은 응답 필드가 아니라 작업 상태에서 파생한다(완료=100%, 진행중=50%, 그 외=0%). REVIEWER 화면에서는 노출하지 않는다. 대시보드의 '최근 완료 영상' 표와 나란히 배치된다.

## props_schema

### workerId

- **type**: number
- **required**: true
- **description**: 본인 작업 목록을 조회할 작업자 식별자(로그인 사용자 sub).

## design_system_id

DS-001

## accessibility_notes

상태는 배지의 색상과 텍스트 라벨을 함께 표시. 진행률은 막대와 수치(%)를 함께 표기한다.

## referenced_by_screen_ids

- SCREEN-011


---

<!-- UI-078 -->

# display: EventDistributionGrid

## name

EventDistributionGrid

## tags

- feature:dashboard
- feature:stat
- display
- grid
- event

## category

display

## variants

### compact

- **description**: 대시보드 — 파이차트 없이 라벨+건수 2열 그리드만 표시.

### withPie

- **description**: 전체 통계 화면 — 파이차트와 나란히 배치, 항목별 비율 막대 병기.

## description

이벤트 유형별 분포 위젯. 관제가 내려주는 카테고리(categoryKey/한글 라벨)와 건수를 그대로 순회 렌더하며 6종 고정 목록으로 하드코딩하지 않는다(관제 마스터 구성에 따라 항목 수가 달라질 수 있다). 대시보드에서는 파이차트 없이 라벨+건수만 2열 그리드로 간결하게 표시하고, 전체 통계 화면에서는 파이차트와 나란히 배치해 항목별 건수와 비율 막대를 함께 보여준다. 두 화면 모두 표시 기준은 검수완료(APPROVED) 데이터다.

## props_schema

### data

- **type**: EventDistribution[]
- **required**: true
- **description**: 카테고리 키·한글 라벨·건수 목록. 순서는 응답 순서를 그대로 따른다.

### isPending

- **type**: boolean
- **required**: false
- **description**: 집계 도착 전 — true면 빈 배열을 '분포 없음'으로 오인하지 않도록 자리표시 스켈레톤을 그린다.

## design_system_id

DS-001

## accessibility_notes

각 행은 라벨 텍스트 + 건수(및 비율 막대) 조합으로 표시해 색상만으로 구간을 구분하지 않는다.

## referenced_by_screen_ids

- SCREEN-011
- SCREEN-021


---

<!-- UI-079 -->

# display: NoticeCard

## name

NoticeCard

## tags

- feature:dashboard
- display
- card
- notice

## category

display

## description

공지사항 카드. 고정(pinned) 공지 우선 정렬 + 최신순, 빈 상태 EmptyState. 대시보드.

## props_schema

### notices

- **type**: Notice[]
- **required**: true
- **description**: 공지 목록 — 고정 여부(pinned)·제목·등록일을 포함. 고정 공지 우선, 이후 최신순으로 정렬해 최대 5건 표시.

## usage_example

대시보드에서 고정 공지를 우선 노출하고 이후 최신순으로 정렬해 최대 5건까지 보여준다.

## design_system_id

DS-001

## accessibility_notes

섹션 전체에 aria-label='공지사항'. 고정 공지 아이콘은 aria-label='고정 공지'로 의미를 알리고, 등록일은 time 요소(dateTime 속성)로 마크업된다. 빈 목록은 EmptyState로 안내한다.

## referenced_by_screen_ids

- SCREEN-011


---

<!-- UI-080 -->

# data: WorkerStatsTable

## name

WorkerStatsTable

## tags

- feature:stat
- data
- table

## category

data

## description

작업자별 현황 표. 작업자·라벨·진행·검수·오토라벨·반려율 6컬럼으로 구성된다. 각 컬럼이 표시하는 값은 작업자=작업자 이름, 라벨=라벨 건수, 진행=진행 건수, 검수=검수 건수, 오토라벨=오토라벨 비율(백분율), 반려율=100에서 승인율을 뺀 값이다. 라벨·검수·오토라벨 헤더는 클릭으로 오름/내림 정렬을 토글하며, 각 헤더는 자기 컬럼이 표시하는 값과 같은 축으로 정렬한다(기본 정렬은 라벨 내림차순). 오토라벨 비율과 반려율은 값이 없으면 자리표시 기호를 표시하고, 반려율이 10을 초과하면 강조색으로 표시한다. 전체 통계 화면 하단에 배치되어 작업자별 작업량과 품질 지표를 함께 보여준다.

## props_schema

### rows

- **type**: WorkerRow[]
- **required**: true
- **description**: 작업자별 통계 행 — 작업자 식별자·이름·라벨 건수·진행 건수·검수 건수·오토라벨 비율·승인율.

### loading

- **type**: boolean
- **required**: false
- **description**: true면 표 대신 로딩 문구를 표시한다.

## design_system_id

DS-001

## accessibility_notes

정렬 가능한 헤더는 네이티브 button 요소이며 현재 정렬 방향을 화살표 텍스트(↓/↑)로 병기한다(색상 단독 아님).

## referenced_by_screen_ids

- SCREEN-021


---

<!-- UI-081 -->

# data: DailyCompletionChart

## name

DailyCompletionChart

## tags

- feature:stat
- data
- chart
- recharts

## category

data

## description

일별 완료 건수 막대 차트. 날짜별 완료 건수 하나의 계열만 그리며, 작업자 통계 화면에서 최근 30일 추이를 보여주는 데 쓰인다. 데이터가 비어 있으면 빈 차트를 그린다.

## props_schema

### data

- **type**: DailyCompletionPoint[]
- **required**: true
- **description**: 날짜(date)·완료 건수(count) 목록.

### height

- **type**: number
- **default**: 240
- **required**: false
- **description**: 차트 높이(px).

## design_system_id

DS-001

## accessibility_notes

recharts accessibilityLayer 옵션을 켜면 막대에 키보드 포커스 이동 및 값 안내가 가능하다(구현에 따라 옵션 사용 여부가 다르다).

## referenced_by_screen_ids

- SCREEN-020


---

<!-- UI-082 -->

# data: EventTypePieChart

## name

EventTypePieChart

## tags

- feature:stat
- data
- chart
- pie

## category

data

## description

이벤트 유형 비율 파이 차트. 카테고리별 건수를 비율로 시각화하며 색상은 카테고리 코드에 고정 매핑하지 않고 응답 순서(인덱스) 기준 팔레트를 순환 배정한다 — 관제가 카테고리 구성의 단일 진실원이므로 화면은 색만 부여한다. 조각에 마우스를 올리면 툴팁으로 값을, 옆에는 범례로 카테고리명을 보여준다.

## props_schema

### data

- **type**: EventTypePoint[]
- **required**: true
- **description**: 카테고리 키·한글 라벨·건수 목록.

### height

- **type**: number
- **default**: 240
- **required**: false
- **description**: 차트 높이(px).

## design_system_id

DS-001

## referenced_by_screen_ids

- SCREEN-021


---

<!-- UI-083 -->

# overlay: AssignModal

## name

AssignModal

## tags

- feature:task
- overlay
- assign
- composed-from:Modal

## category

overlay

## description

작업 배정 모달. assign(단건 신규)/reassign(기존 배정 재배정)/bulk(선택 다건 일괄) 3모드 공용. 상단에 영상 정보 박스(단건은 영상명, bulk는 대상 영상 중 최대 3건을 칩으로 미리 보여주고 나머지는 '외 N건'으로 요약 + 모든 선택 영상에 동일 작업자가 배정됨을 안내) + 작업자 select(필수) + 검수자 select(REVIEWER 역할이면 기본값=로그인 사용자로 변경 가능, WORKER 역할이면 읽기 전용 표시) 로 구성된다. 작업자·검수자 후보 목록은 REVIEWER 역할이면서 모달이 열려 있을 때만 조회한다(WORKER 화면에 마운트돼 있어도 무조건 호출되면 403이 나기 때문). reassign 모드에서 현재 배정된 작업자와 동일한 작업자를 다시 고르면 저장 버튼이 비활성화되고 경고 문구가 노출된다. 저장 시 assign/bulk는 생성 요청, reassign은 수정 요청을 호출하며 성공 시 토스트 안내 후 모달을 닫는다. 작업 배정/목록 화면, 영상 목록/처리 현황 화면(마킹 진입 팝업에서 '수동' 선택 시 단건 신규 배정 모드로도 진입).

## props_schema

### open

- **type**: boolean
- **required**: true
- **description**: 모달 표시 여부.

### onClose

- **type**: () => void
- **required**: true
- **description**: 취소·배경클릭·저장 성공 시 호출.

### task

- **type**: Task|null
- **required**: true
- **description**: assign/reassign 모드의 대상. 미배정 영상의 단건 신규 배정이거나 bulk 모드일 때는 null.

### mode

- **type**: 'assign'|'reassign'|'bulk'
- **required**: true
- **description**: assign=단건 신규 배정, reassign=기존 배정 변경, bulk=선택 다건 일괄 배정.

### videoId

- **type**: number
- **required**: false
- **description**: 단건 신규 배정(assign) 모드에서 task 가 없을 때 쓰는 대상 영상 식별자 — 마킹 진입 팝업에서 '수동'을 고른 경우.

### videoName

- **type**: string
- **required**: false
- **description**: 단건 신규 배정 영상의 표시명(영상 정보 박스에 노출).

### videoIds

- **type**: number[]
- **required**: false
- **description**: bulk 모드 대상 영상 식별자 목록.

### videoNameById

- **type**: Record<number,string>
- **required**: false
- **description**: bulk 미리보기 칩에 쓰는 영상명 맵.

### onSuccess

- **type**: () => void
- **required**: false
- **description**: assign/reassign 단건 성공 콜백.

### onBulkSuccess

- **type**: (videoIds: number[]) => void
- **required**: false
- **description**: bulk 성공 콜백 — 선택 해제 등에 사용.

## usage_example

작업 목록 화면에서 미배정 행의 '배정'·기존 배정 행의 '재배정'·선택 다건의 '일괄 배정' 클릭 시 연다. 영상 목록/처리 현황 화면에서는 '재배정'·'일괄 배정' 버튼 및 마킹 진입 팝업의 '수동' 선택 시(단건 신규 배정)에도 재사용한다.

## design_system_id

DS-001

## accessibility_notes

작업자 select 는 label 로 연결되고 필수 표시(*)를 텍스트로 병기한다. 오류 문구는 role=alert 로 노출. 저장 버튼은 작업자 미선택이거나 처리 중이거나(reassign 한정) 동일 작업자 재선택 시 비활성화되며 그 사유가 텍스트 경고로 함께 표시된다(색상 단독 아님).

## referenced_by_screen_ids

- SCREEN-012
- SCREEN-007
- SCREEN-008


---

<!-- UI-084 -->

# overlay: HistoryDrawer

## name

HistoryDrawer

## tags

- feature:task
- overlay
- history
- drawer

## category

overlay

## description

배정 이력 드로어. 우측 슬라이드 패널(createPortal, 포커스 트랩 + 복귀)로 표시되며 ESC·배경클릭·X 버튼으로 닫힌다. 헤더(시계 아이콘 + '배정 이력' 제목 + 닫기) + 대상 작업(영상명) 박스 + 타임라인으로 구성된다. 타임라인은 배정/검수 워크플로 이벤트 6종(ASSIGN/REASSIGN/SUBMIT/CANCEL_SUBMIT/APPROVE/REJECT)과 개인정보 선언 변경·초기화 감사 이벤트 2종(PRIVACY_META_UPDATE/PRIVACY_META_RESET), 총 8종을 조회한다. 각 항목은 이벤트 종류별 좌측 점 색상(의미 상태색 토큰 — APPROVE=성공, REJECT=실패, REASSIGN/CANCEL_SUBMIT=주의, ASSIGN/SUBMIT=정보, 개인정보 감사 2종은 중립 톤) + 일시 + '{행위자} — {행위 설명}' 패턴 문구(예: '{A} — {B} 작업자에게 배정', '{A} — 개인정보 선언 저장') + 반려 사유(있을 때만)를 보여준다. 개인정보 감사 이벤트 2종은 배정·검수 진행 자체가 아니라 개인정보 선언값이 언제 바뀌었는지를 기록하는 용도이며, 판단값(Y/N) 자체는 노출하지 않고 고정 사유 문구만 보여준다. BE 응답은 합성 ASSIGN 이벤트를 항상 첫 행으로 내려 정상 응답은 최소 1건이므로 빈 상태는 비정상/방어 케이스에서만 노출된다.

## props_schema

### open

- **type**: boolean
- **required**: true
- **description**: 드로어 표시 여부.

### onClose

- **type**: () => void
- **required**: true
- **description**: ESC·배경클릭·X 버튼 공통 닫기 콜백.

### assignmentId

- **type**: number|null
- **required**: true
- **description**: 조회 대상 배정 PK. null/undefined 면 조회 자체가 비활성화된다.

### videoName

- **type**: string
- **required**: false
- **description**: 대상 작업의 영상명 — 헤더 '대상 작업' 박스에 노출. 미지정 시 '영상 정보 없음'.

## usage_example

작업 목록/영상 목록 화면의 행별 '이력' 버튼 클릭 시 assignmentId 를 전달해 연다. REVIEWER 및 본인 작업을 보는 WORKER 양쪽이 쓴다.

## design_system_id

DS-001

## accessibility_notes

role=dialog + aria-modal=true + aria-label='배정 이력'. 열릴 때 드로어 내 첫 포커스 가능 요소로 포커스를 이동하고(없으면 루트 컨테이너), 닫히면 열기 직전 포커스로 복귀한다. Tab 순환은 드로어 내로 트랩(Tab/Shift+Tab 양쪽 순환 처리). ESC 키는 문서 레벨에서 가로채 이벤트 전파를 막고 닫기만 수행한다.

## referenced_by_screen_ids

- SCREEN-012


---

<!-- UI-085 -->

# input: TaskFilters

## name

TaskFilters

## tags

- feature:task
- input
- filters

## category

input

## description

작업 목록 필터 바. 영상명/작업자명 검색어(REVIEWER) 또는 영상명 검색어(WORKER) + 이벤트 유형 select + 상태 select + 작업자 select(REVIEWER 전용) 로 구성된다. 네 입력 모두 대기(draft) 상태를 거치며 '조회' 버튼(또는 Enter 제출)을 눌러야 한꺼번에 적용된다 — 입력마다 즉시 재조회되지 않는다. 상태 옵션 집합은 역할별로 다르다: REVIEWER는 전체/미배정/배정 완료(작업중)/검수요청/완료/반려(IN_PROGRESS 없음 — 서버가 그 값을 절대 반환하지 않는 축), WORKER는 전체/배정 완료/작업중(IN_PROGRESS)/검수요청/완료/반려(미배정 옵션 없음 — 본인 배정 행만 다룸). 이벤트 유형 옵션은 서버 조회 코드 목록(역할별 별도 API, 전체 기준)이며 표시는 한글 카테고리명, 미등록 코드는 원문 폴백. 옵션이 서버 상한으로 잘렸으면 안내 문구가 노출된다. '초기화'는 기본 필터값으로 되돌리고 즉시 재조회를 트리거한다. KPI 카드 클릭으로 바뀌는 축은 상태(workStatus) 하나뿐이라 그 값만 외부에서 동기화하고, 아직 제출하지 않은 검색어·이벤트 선택은 유지된다.

## props_schema

### values

- **type**: TaskFilterValues
- **required**: true
- **description**: 적용된 필터값(URL 파생) — 대기값의 초기값이자 KPI 카드 클릭 시 workStatus 동기화 기준.

### onApply

- **type**: (next: TaskFilterValues) => void
- **required**: true
- **description**: '조회' 제출 — 네 입력을 한꺼번에 적용.

### onReset

- **type**: () => void
- **required**: true
- **description**: '초기화' 클릭 — 기본 필터값으로 즉시 재조회.

### isResetEnabled

- **type**: boolean
- **required**: true
- **description**: 필터가 기본값과 다를 때만 초기화 버튼을 활성화.

### eventTypes

- **type**: string[]
- **required**: true
- **description**: 이벤트 유형 코드 목록 — 역할별 서버 옵션 API 결과(전체 기준, 현재 페이지 아님).

### isEventTypesTruncated

- **type**: boolean
- **required**: true
- **description**: 서버 옵션이 상한 초과로 잘렸는지 — true면 일부만 표시됨을 안내.

### isReviewer

- **type**: boolean
- **required**: true
- **description**: REVIEWER 역할이면 작업자 select 노출 + 상태 옵션이 워크플로(board) 축을 쓴다.

### workers

- **type**: WorkerOption[]
- **required**: true
- **description**: 작업자 select 옵션(REVIEWER 전용 노출).

## usage_example

작업 목록 화면 상단 필터 바. REVIEWER 조회는 서버에 위임(검색어·이벤트·작업자), WORKER 조회는 본인 배정 목록 안에서 클라이언트 필터로 동작한다.

## design_system_id

DS-001

## accessibility_notes

각 입력은 label과 htmlFor로 연결된다. 이벤트유형 절단 안내는 role=status로 노출되어 스크린리더가 옵션이 잘렸음을 알 수 있다.

## referenced_by_screen_ids

- SCREEN-012


---

<!-- UI-086 -->

# input: YoloConfigCard

## name

YoloConfigCard

## tags

- feature:sysconfig
- input
- config
- form

## category

input

## description

AI 탐지 추론 파라미터 설정 카드. 헤더(제목 + 저장 버튼) + 슬라이더 2종 + 숫자 입력 1종으로 구성된다. ①Confidence Threshold — 서버·화면 모두 정수 25~80(표시는 100으로 나눠 0.25~0.80), 높을수록 확신도 높은 객체만 인식(오탐 감소·미탐 증가) ②이미지 크기(imgsz) — 서버는 정수 320~1920만 검증(32의 배수 강제 없음), 화면은 32 스텝 입력을 유도하고 포커스 이탈 시 가까운 32의 배수로 조용히 보정한다(거부 아님) ③IoU 임계값 — 서버는 정수 30~80이지만 화면 슬라이더는 25~80으로 노출되어 25~29 구간을 입력할 수 있는데 그 구간은 서버가 거부한다(화면 표시 범위가 서버 허용 범위보다 넓은 의도된 불일치). 필드 값이 서버 조회값과 하나라도 달라지면(isDirty) 저장 버튼이 활성화되고, 저장은 카드 안에서 실제로 바뀐 키에 한해 키별로 개별 PUT 요청한다(일괄 저장 API 없음). 각 필드 아래 값의 의미를 설명하는 보조 문구가 있다. 시스템 설정 화면.

## props_schema

### configs

- **type**: ConfigMap
- **required**: true

## usage_example

시스템 설정 화면의 3개 설정 카드 중 하나로 2열 그리드에 배치된다. 화면에 노출되는 문구는 기술 모델명을 쓰지 않고 'AI 탐지 추론 파라미터'로 표기한다.

## design_system_id

DS-001

## referenced_by_screen_ids

- SCREEN-025


---

<!-- UI-087 -->

# input: BatchConfigCard

## name

BatchConfigCard

## tags

- feature:sysconfig
- input
- config
- form

## category

input

## description

배치 파이프라인 설정 카드. 헤더(제목 + 저장 버튼) + 슬라이더 2종으로 구성된다. ①처리 주기(초) — 서버는 정수 10~3600초를 검증하지만 화면 슬라이더는 10~300초 범위만 노출해 그 이상은 애초에 입력할 수 없다(서버·화면 범위 불일치가 화면에서는 드러나지 않는다), 짧을수록 신규 영상이 빨리 처리되나 서버·GPU 부하 증가 ②동시 처리 수 — 서버는 정수 1~10을 검증하지만 화면 슬라이더는 1~8 범위만 노출(위와 동일한 이유로 불일치가 드러나지 않는다), 높일수록 처리량 증가하나 GPU 메모리·자원 경합 증가. 필드 값이 서버 조회값과 하나라도 달라지면(isDirty) 저장 버튼이 활성화되고, 저장은 카드 안에서 실제로 바뀐 키에 한해 키별로 개별 PUT 요청한다(일괄 저장 API 없음). 각 필드 아래 값의 의미를 설명하는 보조 문구가 있다. 시스템 설정 화면.

## props_schema

### configs

- **type**: ConfigMap
- **required**: true

## usage_example

시스템 설정 화면의 3개 설정 카드 중 하나로 2열 그리드에 배치된다.

## design_system_id

DS-001

## referenced_by_screen_ids

- SCREEN-025


---

<!-- UI-088 -->

# input: PrecisionConfigCard

## name

PrecisionConfigCard

## tags

- feature:sysconfig
- input
- config
- form

## category

input

## description

라벨링 정밀도 설정 카드. 헤더(제목 + 저장 버튼) + 슬라이더 2종으로 구성된다. ①인식 민감도 — 서버·화면 모두 정수 25~80(표시는 100으로 나눠 0.25~0.80), AI 탐지 추론 파라미터 카드의 Confidence Threshold와 같은 설정값을 공유하므로 한쪽에서 바꾸면 다른 카드에도 반영된다. 값이 높을수록 확신도 높은 객체만 인식 ②경계 세밀함 — 서버는 소수 0.0~50.0(0.5 단위 입력, Douglas-Peucker epsilon px), 값이 작을수록 폴리곤 경계가 원본에 가깝게 세밀해져 점 수가 늘고, 클수록 경계가 단순해져 점 수가 줄어든다. 필드 값이 서버 조회값과 하나라도 달라지면(isDirty) 저장 버튼이 활성화되고, 저장은 카드 안에서 실제로 바뀐 키에 한해 키별로 개별 PUT 요청한다(일괄 저장 API 없음). 시스템 설정 화면.

## props_schema

### configs

- **type**: ConfigMap
- **required**: true

## usage_example

시스템 설정 화면의 3개 설정 카드 중 하나로 2열 그리드에 배치된다.

## design_system_id

DS-001

## referenced_by_screen_ids

- SCREEN-025


---

<!-- UI-089 -->

# display: HealthStatusList

## name

HealthStatusList

## tags

- feature:sysconfig
- display
- health

## category

display

## description

의존 시스템 헬스 상태 목록. 5초 간격 폴링으로 조회하며 읽기 전용이다(편집 불가). 컴포넌트별(DB/디스크/관제서버/포털서버/AI서버/비식별서버)로 UP(정상)/DOWN(연결 끊김)/OUT_OF_SERVICE(서비스 중단) 3상태를 Wifi/WifiOff 아이콘 + 색상 배지로 표시하고, 응답 상세에 latencyMs 가 있으면 함께 보여준다. 컴포넌트 상세에 url 이 있으면 보조 텍스트로 함께 노출하고 없으면 표시하지 않는다. 컴포넌트별 상태 목록이 비어 있으면 개별 항목 대신 전체 상태 1행으로 대체 표시한다. 로딩 스켈레톤, 조회 실패 시 에러 상태. actuator/health 에서 실시간 조회되며 편집할 수 없다는 안내 문구를 하단에 보여준다. 시스템 설정 화면.

## design_system_id

DS-001

## accessibility_notes

상태는 아이콘(Wifi/WifiOff) + 색상 배지 텍스트(정상/연결 끊김/서비스 중단)를 함께 써서 색상 단독으로 정보를 전달하지 않는다.

## referenced_by_screen_ids

- SCREEN-025


---

<!-- UI-090 -->

# action: DangerActions

## name

DangerActions

## tags

- feature:sysconfig
- action
- danger
- composed-from:ConfirmDialog

## category

action

## description

위험 작업 영역. 이관 예정 안내 배너 + 경고 문구('아래 작업은 되돌릴 수 없습니다') + 액션 버튼 3종(시스템 초기화(개발 전용)/배치 큐 초기화/캐시 삭제)으로 구성된다. 세 액션 모두 되돌릴 수 없는 파괴적 작업이므로 버튼 클릭 시 공통 확인 다이얼로그(ConfirmDialog)를 거친다 — 다이얼로그는 수행할 작업명 + 개별 설명(예: '대기 중인 배치 작업이 모두 제거됩니다. 진행 중인 작업은 영향받지 않습니다') + 되돌릴 수 없다는 경고를 함께 보여주고, 확인을 누른 뒤에만 실행된다. 취소하면 아무 일도 일어나지 않는다. 시스템 설정 화면은 검수자만 접근한다.

## usage_example

시스템 설정 화면 하단 '위험 구역'. 운영 도구 이관 전까지 데모 동작으로 제공된다.

## design_system_id

DS-001

## referenced_by_screen_ids

- SCREEN-025


---

<!-- UI-091 -->

# overlay: PresetEditModal

## name

PresetEditModal

## tags

- feature:preset
- overlay
- form
- composed-from:Modal

## category

overlay

## description

프리셋 생성/편집 모달. 신규('새 프리셋 만들기')/수정('프리셋 편집') 공용이며 프리셋 이름(필수, 1~64자) + 설명(선택, 0~500자, textarea) + 매핑 이벤트 타입 select(서버 동적 조회, 값=이벤트유형코드의 그룹 대표코드, 빈값=미매핑, 동일 이벤트는 1개 프리셋에만 매핑 가능해 중복 시 거부) + 라벨 항목 선택(필수, 1~20개)으로 구성된다. 라벨은 라벨 마스터 단일 진실원에서 고른다 — 활성 마스터를 체크박스 다중선택 목록으로 보여주고 제출 시 labelId 배열만 전송한다(라벨명·형태 스냅샷 저장 없음). 형태(bbox/폴리곤 등)는 라벨 마스터가 소유하므로 목록에 읽기 전용으로만 함께 표시되며 프리셋에서 개별 토글할 수 없다(코드칩별 BBOX/POLYGON 체크박스 토글은 폐기됐다). 편집 대상에 라벨 마스터와 더 이상 매칭되지 않는 레거시 코드가 있으면 경고 배너로 알리고(오류로 올리지 않고 '미연결'로 표시하며 자동 생성·삭제는 하지 않는다), 저장 시 이 항목은 자동 제외된다. 라벨 0개 선택 시 저장 버튼이 비활성화된다. 저장 요청 자체는 이 모달이 수행하지 않는다 — 입력 수집과 검증까지만 맡고 제출 시 폼 값을 호출 화면에 넘긴다. 생성/수정 요청, 중복(이벤트 중복/이름 중복) 응답의 안내 문구 노출, 성공 후 닫기는 호출 화면이 처리하며 처리 중 여부도 호출 화면이 내려준다. 프리셋 관리 화면.

## props_schema

### open

- **type**: boolean
- **required**: true
- **description**: 모달 표시 여부.

### onClose

- **type**: () => void
- **required**: true
- **description**: 취소/성공 시 호출.

### initial

- **type**: Preset|undefined
- **required**: false
- **description**: 편집 대상 프리셋. 미지정이면 신규 생성 모드이며 입력값은 빈 폼으로 시작한다.

### onSubmit

- **type**: (form: PresetForm) => void
- **required**: true
- **description**: 제출 콜백 — 검증을 통과한 폼 값을 호출 화면에 넘긴다. 생성/수정 요청과 중복 응답 안내는 호출 화면이 수행한다.

### submitting

- **type**: boolean
- **required**: false
- **description**: 호출 화면이 저장 요청을 처리하는 중임을 알린다. 참이면 저장 버튼이 진행 상태로 바뀌고 저장·취소 버튼이 비활성화되어 중복 제출을 막는다.

## design_system_id

DS-001

## referenced_by_screen_ids

- SCREEN-026


---

<!-- UI-092 -->

# display: PresetCodeChip

## name

PresetCodeChip

## tags

- feature:preset
- display
- chip

## category

display

## description

프리셋 라벨 코드 표시 칩(읽기 전용). 라벨명 + 형태 배지(연결된 코드일 때만)를 보여주며, 라벨 마스터에 연결(linked)된 코드는 그 마스터의 현재 라벨명·형태를 실시간 조회해 표시하고, 마스터에 더 이상 없는 미연결(linked=false) 코드는 레거시 라벨명 + '미연결' 배지로 구분 표시된다(색상도 연결/미연결으로 다르다). 토글·삭제 상호작용은 제공하지 않는 순수 표시 컴포넌트다(구 bbox/polygon 토글 체크박스 + 삭제 버튼은 폐기됨 — 프리셋 편집은 라벨 마스터 체크박스 다중선택으로 바뀌어 이 칩은 편집 모달 내부가 아니라 프리셋 카드 그리드에서만 쓰인다). 라벨명은 텍스트 노드로만 렌더한다(dangerouslySetInnerHTML 미사용 — XSS 방어). 프리셋 관리 화면 카드 그리드.

## props_schema

### code

- **type**: LabelCodeOption
- **required**: true
- **description**: 표시할 라벨 코드 — labelId/labelName/labelType/linked/code(레거시 표시용) 필드 포함.

## design_system_id

DS-001

## referenced_by_screen_ids

- SCREEN-026


---

<!-- UI-093 -->

# [폐기] display: BatchStageSteps

## name

BatchStageSteps

## tags

- feature:video
- display
- stepper
- batch
- deprecated

## category

display

## description

[폐기] 배치 단계 표시를 이 항목으로 따로 두지 않는다. 같은 화면의 같은 단계 표시는 BatchStageIndicator(UI-018) 하나로 규정하며, 이 항목에만 있던 두 가지 규정(진행률 값을 백분율 바로 표시하지 않는다 · 진행 중인 단계가 남아 있을 때 상위 화면이 주기적으로 다시 조회한다)은 그 항목 본문으로 옮겨 두었다. 배치 단계 표시가 필요한 화면은 UI-018 을 참조한다.

## props_schema

### stages

- **type**: BatchStageItem[]
- **required**: true
- **description**: {name, status, progress}[] — progress 필드는 타입에 존재하나 현재 렌더에서는 쓰이지 않는다.

## design_system_id

DS-001

## referenced_by_screen_ids

- SCREEN-009


---

<!-- UI-094 -->

# action: VideoActions

## name

VideoActions

## tags

- feature:video
- action
- row-actions

## category

action

## description

영상 행 액션 버튼. 삼갈래 분기된다 — ①기존 배정(workerId 존재) && 검수 승인 완료가 아닌 행: '재배정' 버튼(REVIEWER 전용, 수정 요청 호출) ②미배정(workerId 없음) && 마킹 진입 가능(배치 단계가 마킹 대기이며 비식별이 확정적으로 미완료가 아닌): '마킹 설정' 버튼(REVIEWER 전용, 마킹 진입 팝업 오픈) ③그 외(역할 무관): '상세' 버튼만 항상 노출된다. 조건을 충족하지 않는 행은 재배정/마킹 버튼 없이 상세 버튼만 남는다. 단순 배정('배정') 액션은 더 이상 노출하지 않는다 — 미배정 영상의 신규 배정은 마킹 진입 팝업에서 '수동'을 고를 때만 이루어진다. 영상 목록/처리 현황 화면 테이블.

## props_schema

### video

- **type**: Video
- **required**: true
- **description**: 대상 영상 행 데이터 — workerId, canMark, assignStatus 등 액션 분기 필드 포함.

### isReviewer

- **type**: boolean
- **required**: true
- **description**: REVIEWER 역할이어야 '재배정'·'마킹 설정'이 노출된다. WORKER 는 '상세'만 보인다.

### onDetail

- **type**: (video) => void
- **required**: false
- **description**: '상세' 클릭 — 영상 상세 화면으로 이동.

### onReassign

- **type**: (video) => void
- **required**: false
- **description**: '재배정' 클릭 — 작업 배정 모달을 reassign 모드로 연다.

### onMark

- **type**: (video) => void
- **required**: false
- **description**: '마킹 설정' 클릭 — 마킹 진입 팝업(자동/수동 선택)을 연다.

## design_system_id

DS-001

## referenced_by_screen_ids

- SCREEN-007
- SCREEN-008


---

<!-- UI-095 -->

# input: VideoFilters

## name

VideoFilters

## tags

- feature:video
- input
- filters

## category

input

## description

영상 목록 필터. CCTV명/영상ID 검색어 + 상태 select + 이벤트 유형 select + 시작일/종료일 날짜 입력 + 조회/초기화 버튼으로 구성된 한 줄 그리드 폼이다. 상태 옵션은 BE 데이터 상태코드 5종(완료/처리중/마킹 대기/대기/실패) + 전체와 1:1이며 '마킹 대기(MARKING_READY)'가 빠지면 적재~마킹 구간 영상을 상태로 좁힐 수 없다. 이벤트 유형 옵션은 서버 동적 조회(관제 이벤트 마스터 기반 카테고리 옵션)이며 하드코딩된 고정 유형 목록을 쓰지 않는다. 로딩 중에는 선택 불가 안내 옵션이 disabled 상태로 삽입된다. 네 입력 모두 로컬 state 에 담긴 다음 '조회' 제출(또는 Enter) 시 하나의 요청으로 합쳐져 적용된다 — 검색어는 trim 후 공백만 남으면 필터 미적용. '초기화'는 로컬 state 전체를 비우고 즉시 재조회를 트리거한다. 영상 목록/처리 현황 화면.

## props_schema

### initial

- **type**: VideoListParams
- **required**: true

### onApply

- **type**: (next:VideoListParams)=>void
- **required**: true

## design_system_id

DS-001

## accessibility_notes

각 입력은 label 과 htmlFor 로 연결된다. 이벤트 유형 select 는 로딩 중 disabled + aria-busy 로 표시되어 스크린리더가 옵션이 아직 준비되지 않았음을 알 수 있다.

## referenced_by_screen_ids

- SCREEN-007
- SCREEN-008


---

<!-- UI-096 -->

# input: AugmentTypeCheckbox

## name

AugmentTypeCheckbox

## tags

- feature:augment
- input
- composed-from:Checkbox

## category

input

## description

[폐기] 증강 유형 4종(겨울/야간/비/해상도)을 다중 선택하는 체크박스는 두지 않는다. 증강 요청 화면의 처리 종류 선택은 카드 4개를 role=radiogroup 으로 묶은 단일 선택(라디오)이며 한 번에 하나의 종류만 고를 수 있다. 해상도 변경을 고른 뒤에만 별도로 노출되는 타겟 해상도 선택(1080P/720P/480P)은 다중 선택 체크박스이지만, 이것은 '처리 종류' 자체가 아니라 해상도 프리셋 집합을 고르는 별개 축이다. 증강 유형별 체크박스 설계는 실제 코드에 만들어진 적이 없다. 증강 요청 화면.

## props_schema

### type

- **type**: AugmentType
- **required**: true

### checked

- **type**: boolean
- **required**: true

### onChange

- **type**: (next:boolean)=>void
- **required**: true

### disabled

- **type**: boolean
- **required**: false

## design_system_id

DS-001

## referenced_by_screen_ids

_(empty)_


---

<!-- UI-097 -->

# data: IssueThreadPanel (이슈 스레드 패널)

## name

IssueThreadPanel

## tags

- issue
- review
- thread
- R1-외-추가

## category

data

## description

검수자↔작업자 이슈 소통 채널 패널. 반려(REJECTION) 이력과 문의(INQUIRY)를 하나의 스레드 목록으로 통합 표시하며, 헤더에 미해결 문의 건수 배지를 함께 보여준다. mode='worker'(라벨링 화면)일 때만 헤더의 '문의' 토글로 새 문의 작성 폼을 열고 닫을 수 있다(본문 1~1000자, 등록 성공 시 폼이 자동 닫힌다). 각 스레드 카드는 유형·상태 배지(반려/문의, 대기/답변완료/해소) + 본문 + 작성자 표시 + 댓글 목록을 보여주며, mode='reviewer'일 때만 미해소 문의 스레드에 '해소' 버튼이 노출된다. 둘 다 댓글 작성은 가능하다(본문 1~1000자). 미해결 문의가 해소되면 그 스레드의 댓글 입력이 잠기며(반려 이력은 해소된 뒤에도 댓글을 계속 남길 수 있어 이 잠금 규칙이 적용되지 않는다). 다른 사용자가 같은 문의를 먼저 처리한 경우(409 충돌) 스레드 카드에 동시 처리 안내를 인라인으로 보여주고 최신 상태를 다시 조회한다. 대상 영상 식별자(videoId)가 없으면 탭 자체는 계속 노출하되 패널 자리에 '영상 정보가 없어 이슈 스레드를 사용할 수 없습니다'라는 안내만 보여주고 패널은 렌더하지 않는다. 목록 조회, 문의 등록, 댓글 추가, 해소 처리 4개 동작을 각각 별도 API 로 수행한다. 본문은 텍스트 노드로만 렌더되고(dangerouslySetInnerHTML 미사용 — XSS 방어) 개행은 보존된다.

## props_schema

### videoId

- **type**: number
- **required**: true
- **description**: 대상 영상 식별자 — 이슈 스레드 조회/등록 경로 키.

### mode

- **type**: 'worker'|'reviewer'
- **required**: true
- **description**: worker=라벨링 화면(문의 등록+댓글), reviewer=검수 화면(댓글+해소).

### currentSrcSn

- **type**: number
- **required**: false
- **description**: 작업자 문의 등록 시 태깅할 현재 프레임 식별자(선택).

## usage_example

라벨링 캔버스 화면(SCREEN-005) 우측 RightPanel의 Objects/Issues 탭 분기 중 Issues 탭, 검수 상세 화면(SCREEN-019) 이슈 영역. API-102~105 소비.

## design_system_id

DS-001

## accessibility_notes

패널 전체가 aria-label='이슈 스레드'로 노출된다. 문의/댓글 입력란은 sr-only label 로 연결된다. 입력 검증 오류는 role=alert 로, 동시처리 충돌 안내는 role=status 로 노출된다.

## referenced_by_screen_ids

- SCREEN-005
- SCREEN-019


---

<!-- UI-098 -->

# input: FileInput

## name

FileInput

## tags

- input
- file
- form
- common

## category

input

## description

파일 선택 입력. 텍스트 입력·선택 입력과 같은 골격(라벨 + 입력 + 오류/힌트 + 보조 표시)을 따르는 공통 입력 컴포넌트다. 파일 선택 버튼의 표기는 이 컴포넌트가 단독으로 정한다 — 업로드 화면마다 같은 스타일 문자열을 복제하면 한쪽만 바뀌어 조용히 갈라진다. 선택한 파일의 요약(파일명과 MB 단위 크기)도 호출처마다 같은 계산을 반복하지 않도록 여기서 함께 표시한다. 오류가 있으면 오류 문구를, 없고 힌트가 있으면 힌트를 입력 아래에 보여준다(둘을 동시에 보여주지 않는다 — 오류가 우선). 보안: 허용 확장자 지정은 화면 편의를 위한 보조 가드일 뿐 신뢰 경계가 아니다. 확장자와 실제 형식 검증은 서버가 수행하며, 화면 지정만 믿고 서버 검증을 생략하지 않는다.

## props_schema

### label

- **type**: string
- **required**: false
- **description**: 입력 라벨. 지정하면 입력과 연결된 라벨 요소를 렌더한다.

### hideLabel

- **type**: boolean
- **required**: false
- **description**: 라벨을 화면에서만 숨긴다. 보조기술에는 그대로 읽히므로 라벨을 아예 비우는 것과 다르다.

### error

- **type**: string
- **required**: false
- **description**: 오류 문구. 지정하면 입력이 오류 상태로 표시되고 문구가 즉시 안내된다. 힌트보다 우선한다.

### hint

- **type**: string
- **required**: false
- **description**: 허용 확장자·용량 등 보조 안내. 오류가 없을 때만 표시되며 입력의 설명으로 연결된다.

### selectedFile

- **type**: File | null
- **required**: false
- **description**: 선택된 파일. 지정하면 입력 아래에 파일명과 MB 단위 크기 요약을 표시한다. null 이면 표시하지 않는다.

### selectedFileTestId

- **type**: string
- **required**: false
- **description**: 선택 파일 요약 요소의 테스트 식별자.

### accept

- **type**: string
- **required**: false
- **description**: 허용 확장자·형식 힌트. 화면 편의용 보조 가드이며 신뢰 경계가 아니다.

### multiple

- **type**: boolean
- **required**: false
- **description**: 복수 선택 허용 여부.

### disabled

- **type**: boolean
- **required**: false
- **description**: 비활성 여부. 업로드 진행 중 등에 쓴다.

### onChange

- **type**: (e: ChangeEvent<HTMLInputElement>) => void
- **required**: false
- **description**: 파일 선택 변경 콜백.

## usage_example

파일을 골라 서버로 올리는 폼에서 쓴다. 허용 확장자·최대 용량 안내는 `hint` 에 적어 사용자가 고르기 전에 알게 한다.

**쓰지 않는 경우**: 버튼을 눌러 숨은 입력을 여는 방식(예: 첨부파일 목록에 파일을 하나씩 추가하는 관리 영역)은 이 컴포넌트가 아니라 버튼 + 숨김 입력 조합으로 만든다 — 이 컴포넌트는 라벨과 입력이 화면에 함께 보이는 폼 필드용이다.

**주의**: `accept` 만으로 형식을 제한했다고 보지 않는다. 서버가 확장자·실제 형식·크기를 다시 검증한다.

## design_system_id

DS-001

## accessibility_notes

라벨은 입력과 명시적으로 연결한다. 라벨 숨김 옵션은 시각적으로만 감추고 보조기술에는 남긴다. 오류 상태에서는 입력에 오류 표식을 붙이고 오류 문구를 즉시 안내 영역으로 노출해 스크린리더가 바로 읽게 한다. 오류가 없고 힌트가 있으면 힌트를 입력의 설명으로 연결한다(오류와 힌트를 동시에 연결하지 않는다 — 오류가 우선). 포커스 표시는 디자인시스템의 공통 포커스 링을 따른다.

## referenced_by_screen_ids

- SCREEN-027


---

<!-- UI-099 -->

# input: Field

## name

Field

## tags

- common
- layout
- form
- compound

## category

input

## description

입력 프리미티브(Input/Select/Checkbox/RadioGroup/Textarea 등)와 라벨·설명·오류 문구를 바깥에서 조립하는 표시 구조 래퍼. Field(그룹 컨테이너, orientation='vertical'|'horizontal'|'responsive')·FieldLabel(입력과 htmlFor 로 연결되는 라벨)·FieldDescription(보조 설명문)·FieldError(오류 문구, errors 배열 또는 children)·FieldContent(가로 배치에서 라벨+설명을 세로로 묶어 컨트롤 옆에 두는 하위 컨테이너)·FieldGroup(여러 Field 를 세로로 묶는 상위 컨테이너)·FieldSet+FieldLegend(네이티브 fieldset+legend 로 여러 Field 를 그룹핑)·FieldTitle(특정 입력과 연결되지 않는 정적 섹션 라벨)·FieldSeparator(구분선, children 지정 시 중앙에 텍스트 병기)로 구성된다. 입력 프리미티브 자신은 label/hint/error prop 을 갖지 않으며 이 조립부가 라벨·설명·오류의 위치와 표시를 전담한다. FormField(UI-030, react-hook-form Controller 값 바인딩 래퍼)와는 관심사가 다르다 — FormField 는 '값을 어떻게 연결하는가'만 다루고 시각적 라벨·설명·오류 배치는 이 Field 가 담당하므로 두 래퍼는 함께 쓰일 수 있다(FormField 의 render 결과 안쪽에 Field 조립을 두는 식). UI-030 의 usage_example 에도 이 경계를 병기한다.

## props_schema

### Field.orientation

- **type**: 'vertical'|'horizontal'|'responsive'
- **default**: vertical
- **required**: false
- **description**: vertical=라벨 위·컨트롤 아래로 세로 배치(기본). horizontal=라벨과 컨트롤을 한 줄에 나란히(체크박스·라디오류에 사용). responsive=좁은 화면은 세로, 중간폭 이상에서 가로로 전환.

### Field.data-invalid

- **type**: boolean
- **required**: false
- **description**: 지정 시 그룹 전체 텍스트가 destructive 색으로 전환된다. 실제 aria-invalid 는 이 값과 별개로 각 입력 프리미티브에 호출부가 직접 건다(자동 연동 아님).

### FieldLabel

- **type**: component
- **required**: false
- **description**: htmlFor 로 대상 입력과 연결되는 라벨. Radix Label 위에 얇게 얹힌 래퍼.

### FieldDescription

- **type**: component
- **required**: false
- **description**: 라벨 아래·오류 위에 오는 보조 설명 문단. 링크 포함 시 밑줄 스타일이 자동 적용된다.

### FieldError

- **type**: component
- **required**: false
- **description**: errors(메시지 객체 배열) 또는 children 으로 오류 문구를 받는다. role=alert. 서로 다른 메시지가 2개 이상이면 불릿 목록으로 자동 렌더, 값이 없으면 아무것도 렌더링하지 않는다(빈 alert 로 DOM 에 남지 않음).

### FieldContent

- **type**: component
- **required**: false
- **description**: orientation='horizontal' 조합(체크박스·라디오를 라벨 앞에 두는 배치)에서 라벨+설명을 세로로 묶어 컨트롤 옆에 두는 하위 컨테이너.

### FieldGroup

- **type**: component
- **required**: false
- **description**: 여러 Field 를 세로로 묶는 상위 컨테이너. 폼 전체를 감싸는 최상위 요소로 쓴다.

### FieldSet

- **type**: component
- **required**: false
- **description**: 네이티브 fieldset 래퍼 — 여러 Field 를 의미상 한 묶음으로 나타낼 때 FieldLegend 와 함께 쓴다.

### FieldLegend

- **type**: component
- **required**: false
- **description**: FieldSet 의 제목. variant='legend'(기본, 큰 글자) 또는 'label'(작은 글자).

### FieldTitle

- **type**: component
- **required**: false
- **description**: 특정 입력 하나와 연결되지 않는 정적 섹션 라벨(예: 상태 값을 나열하는 섹션의 제목). FieldLabel 과 달리 htmlFor 대상이 없다.

### FieldSeparator

- **type**: component
- **required**: false
- **description**: Field 사이 구분선. children 을 지정하면 구분선 중앙에 텍스트를 병기한다(예: '또는').

## usage_example

Input/Select/Checkbox/RadioGroup/Textarea/DatePicker 를 라벨·설명·오류 문구와 함께 배치할 때 이 조립부로 감싼다 — 예: Field 안에 FieldLabel + Input + FieldError 를 순서대로 배치. 체크박스처럼 컨트롤이 라벨 앞에 오는 가로 배치는 Field(orientation='horizontal') 안에 Checkbox 를 두고, 그 옆에 FieldContent 로 FieldLabel+FieldDescription 을 묶는다. 여러 Field 를 한 화면에 나열할 때는 FieldGroup 으로 감싸 세로 간격을 통일한다. react-hook-form 의 비-네이티브 위젯 바인딩이 필요하면 FormField(UI-030)와 함께 쓴다(값 연결은 FormField, 시각 배치는 이 Field).

## design_system_id

DS-001

## accessibility_notes

FieldLabel 은 htmlFor 로 대상 컨트롤과 연결되어 라벨 클릭 시 포커스가 이동한다. 이 연결은 조립부가 자동으로 맺는다 — 조립부가 컨트롤 식별자를 만들어 FieldLabel 과 입력 프리미티브가 같은 값을 쓰므로 호출부가 식별자를 맞출 필요가 없다. 호출부가 입력에 식별자를 직접 지정한 경우에는 그 실제 값이 조립부에 전달되어 라벨 연결이 끊기지 않는다. 설명·오류 문구도 조립부가 aria-describedby 로 자동 연결하며, 오류가 렌더되면 오류를 참조하고 없으면 설명을 참조한다. 렌더되지 않은 문구는 참조하지 않는다(존재하지 않는 식별자를 가리키지 않기 위해서다). 오류가 렌더되면 aria-invalid 도 자동으로 참이 되며, 호출부가 명시한 값이 있으면 그 값이 우선한다. FieldError 는 role=alert 로 스크린리더에 즉시 안내되며 내용이 없으면 DOM 에 렌더되지 않아 빈 alert 로 남지 않는다. 여러 컨트롤을 한 묶음으로 다루는 그룹 입력은 aria-labelledby 로 FieldLabel 을 가리켜 연결한다. 필수 여부는 시각 기호만으로 전달하지 않고 보조 기술이 읽을 수 있는 텍스트를 함께 제공한다. 연결을 자동으로 두는 이유는 호출부가 매번 식별자를 손으로 맞추는 방식이 한 곳만 빠뜨려도 조용히 끊기고, 이 조립부를 쓰는 자리가 많을수록 그 위험이 커지기 때문이다.


---

<!-- UI-100 -->

# input: DeidentConfigCard

## name

DeidentConfigCard

## tags

- feature:sysconfig
- input
- config
- form
- deident

## category

input

## description

비식별 위탁 옵션 설정 카드. 헤더(제목 + 저장 버튼) + 입력 3종으로 구성된다. ①마스킹 방식 — 색상/모자이크/블러 중 하나를 고르는 드롭다운이며, 벤더가 정의한 세 가지 밖의 값은 고를 수 없다 ②마스킹 범위 — 실수 0.5~2.0 이며 감지된 영역을 얼마나 넓게 덮을지를 정한다(작으면 사람이 보이고 크면 주변까지 가린다) ③프레임 저장 여부 — 비식별 서버가 처리 프레임을 자기 DB 에 남길지를 정하는 토글이다. 설정은 전역 1벌이라 영상별로 다르게 줄 수 없고, 저장한 값은 그 다음부터 새로 위탁하는 영상에 적용된다(이미 위탁한 건은 바뀌지 않는다). 필드 값이 서버 조회값과 하나라도 달라지면(isDirty) 저장 버튼이 활성화되고, 저장은 카드 안에서 실제로 바뀐 키에 한해 키별로 개별 요청한다(일괄 저장 API 없음). 각 필드 아래 값의 의미를 설명하는 보조 문구가 있다. ⚠ 출력 화질·출력 포맷은 이 카드에 두지 않는다 — 비식별 제공자가 미지원이라고 밝혔고(값을 보내도 원본 그대로 저장된다), 반영되지 않는 값을 조절할 수 있게 두면 운영자가 바꿔 놓고 아무 일도 일어나지 않는 상태가 된다. 그 둘은 규격상 기본값으로 계속 전송된다. 시스템 설정 화면.

## props_schema

### configs

- **type**: ConfigMap
- **required**: true

## usage_example

시스템 설정 화면의 설정 카드 중 하나로 2열 그리드에 배치된다.

## design_system_id

DS-001

## referenced_by_screen_ids

- SCREEN-025


---

<!-- UI-101 -->

# display: RecheckBadge

## name

RecheckBadge

## tags

- review
- recheck
- badge

## category

display

## variants

### default

- **description**: warn 톤 아웃라인 칩 + 경고 아이콘 + '재검토 필요' 텍스트

## description

검수 완료 후 수정으로 재검토 대상이 된 건을 표시하는 배지 — StatusBadge와 시각적으로 구분되는 warn 톤 아웃라인 칩(경고 아이콘+텍스트 병기, 색상 단독 구분 금지).

## props_schema

### label

- **type**: string
- **default**: 재검토 필요
- **required**: false
- **description**: 표시 텍스트

## usage_example

SCREEN-018 검수 목록 화면(상태 배지 옆 병기) · SCREEN-019 검수 상세 화면(헤더 상태 배지 옆). 두 화면에서 각각 RecheckFlag/RecheckBadge로 독립 제안됐던 것을 통합한 컴포넌트.

## design_system_id

DS-001

## accessibility_notes

색상만으로 구분하지 않고 경고 아이콘 + 한글 텍스트를 항상 동반한다.


---

<!-- UI-102 -->

# display: ReadOnlyBadge

## name

ReadOnlyBadge

## tags

- readonly
- badge

## category

display

## variants

### overlay

- **description**: 반투명 다크 배경 + 흰 텍스트, 미디어 매트 위 오버레이용

## description

읽기 전용 영역임을 알리는 범용 배지 — 캔버스 좌상단 등 고정 위치에 표시.

## props_schema

### label

- **type**: string
- **default**: 읽기 전용
- **required**: false

## usage_example

SCREEN-019 검수 상세 화면(라벨 캔버스 좌상단 고정 배지)

## design_system_id

DS-001


---

<!-- UI-103 -->

# feedback: AlertBanner

## name

AlertBanner

## tags

- banner
- alert
- feedback

## category

feedback

## variants

### info

- **description**: 정보 안내 톤 — SCREEN-018 RefreshingNotice(갱신 중 안내)의 기반으로도 사용 가능

### warn

- **description**: 주의 필요 톤

### error

- **description**: 오류 톤 — 아이콘+제목+본문+재시도 액션

## description

화면 상단에 인라인으로 표시하는 안내/경고/오류 배너 — 아이콘+제목+본문+선택적 액션. 화면 전체를 대체하는 ErrorState/EmptyState와 달리 목록·폼은 유지한 채 상단에 병기한다.

## props_schema

### variant

- **type**: 'info'|'warn'|'error'
- **required**: true

### title

- **type**: string
- **required**: true

### description

- **type**: string
- **required**: false

### actionLabel

- **type**: string
- **required**: false

### onAction

- **type**: () => void
- **required**: false

## usage_example

SCREEN-012 작업 목록 화면(오류 안내 배너)

## design_system_id

DS-001


---

<!-- UI-104 -->

# display: CountChip

## name

CountChip

## tags

- chip
- count

## category

display

## variants

### default

- **description**: primary tint 배경

## description

임의 텍스트를 담는 범용 pill 배지 — 'N개 선택됨' 같은 상태 요약 표시. 도메인 특정 값을 표시하는 StatusBadge/EventTypeBadge와 달리 자유 텍스트용 프리미티브.

## props_schema

### label

- **type**: string
- **required**: true

## usage_example

SCREEN-012 작업 목록 화면(일괄 배정 액션바 'N개 선택됨' 칩)

## design_system_id

DS-001


---

<!-- UI-105 -->

# display: DerivativeBadge

## name

DerivativeBadge

## tags

- badge
- derivative
- augment

## category

display

## variants

### default

- **description**: neutral 톤 + dashed border

## description

파생 영상(증강 WINTER/NIGHT/RAIN, 해상도 프리셋 등)임을 표시하는 배지 — 점선 보더로 EventTypeBadge와 시각적으로 구분해 '원본 아님'을 알린다.

## props_schema

### label

- **type**: string
- **required**: true
- **description**: 파생 유형 표시 텍스트(예: WINTER, 480p)

## usage_example

SCREEN-012 작업 목록 화면(작업 목록 테이블 영상명 옆)

## design_system_id

DS-001


---

<!-- UI-106 -->

# data: KeyValueGrid

## name

KeyValueGrid

## tags

- data
- meta
- key-value

## category

data

## variants

### default

## description

라벨(dt)+값(dd) 쌍을 2열 그리드로 나열하는 범용 메타 정보 표시 컴포넌트 — 영상/작업 상세류 화면에서 반복되는 읽기전용 키-값 목록에 재사용.

## props_schema

### items

- **type**: Array<{label:string, value:string}>
- **required**: true

## usage_example

SCREEN-009 영상 상세 화면(기본 정보 탭 메타 그리드). SCREEN-019 검수 상세 화면의 '영상 기술 정보' 패널도 별도 컴포넌트 신설 대신 이 컴포넌트 재사용을 권장.

## design_system_id

DS-001


---

<!-- UI-107 -->

# input: EventAnnotationPanel

## name

EventAnnotationPanel

## tags

- annotation
- vqa
- cot
- review

## category

input

## variants

### editable

- **description**: 캡션·근거 후보 텍스트 입력 반복 행 (SCREEN-005 라벨링)

### readOnly

- **description**: Select+시간 입력 2종으로 정오 판정하는 검토 모드 (SCREEN-019 검수, 구 제안명 EventAnnotationReviewPanel과 동일 개념 통합)

## description

이벤트 어노테이션(VQA/CoT — 캡션·근거 후보)을 편집 또는 검토하는 패널. 라벨링 화면에서는 캡션·근거 후보 텍스트 입력 반복 행으로 편집 가능하고, 검수 화면에서는 Select+시간 입력 필드로 정오를 판정하는 검토 모드로 동작한다.

## props_schema

### mode

- **type**: 'editable'|'readOnly'
- **required**: true

### items

- **type**: Array<{caption:string, evidence?:string}>
- **required**: false
- **description**: 편집 모드의 캡션/근거 후보 목록

## usage_example

SCREEN-005 라벨링 캔버스 화면(우측 패널, editable) · SCREEN-019 검수 상세 화면(우측 메타 탭, readOnly)

## design_system_id

DS-001


---

<!-- UI-108 -->

# input: PrivacyMetaPanel

## name

PrivacyMetaPanel

## tags

- privacy
- meta
- input

## category

input

## variants

### video-axis

- **description**: 영상 단위 개인정보 메타 편집

### frame-axis

- **description**: 프레임 단위 개인정보 메타 편집

## description

영상 축(video)/프레임 축(frame) 개인정보 3필드(익명·가명·PII 포함여부)를 편집하는 패널 — 각 필드는 라디오 칩 3지(Y/N/미입력)로 표현하고, 사용자가 직접 고르지 않은 값은 *Source(MANUAL/DERIVED) 태그로 병기한다.

## props_schema

### axis

- **type**: 'video'|'frame'
- **required**: true

### anonymity

- **type**: 'Y'|'N'|null
- **required**: false

### pseudonymity

- **type**: 'Y'|'N'|null
- **required**: false

### privacyIncluded

- **type**: 'Y'|'N'|null
- **required**: false

### source

- **type**: 'MANUAL'|'DERIVED'
- **required**: false
- **description**: 각 필드값의 출처 태그(*Source)

## usage_example

SCREEN-005 라벨링 캔버스 화면(우측 메타 탭, 영상축/프레임축 두 인스턴스)

## design_system_id

DS-001


---

<!-- UI-109 -->

# display: Avatar

## name

Avatar

## tags

- display
- avatar
- user

## category

display

## variants

### initial

- **description**: 이니셜 1자, 원형(size=md 기준 36px), primary-05 배경 + primary-60 텍스트(대비 6.09:1, AA)

## description

사용자 이름 옆에 붙는 이니셜 원형 아바타. 이니셜 1자를 원형 배경 안에 표시해 사용자를 시각적으로 식별한다.

## props_schema

### initial

- **type**: string
- **required**: true
- **description**: 표시할 이니셜 1자

### size

- **type**: 'sm'|'md'
- **default**: md
- **required**: false
- **description**: size=md 기본 36px

## usage_example

SCREEN-024(사용자 관리) 사용자 목록 표 이름 컬럼 — 아바타 + 이름 텍스트를 병기해 표시한다.

## design_system_id

DS-001

## accessibility_notes

이니셜만으로 식별 정보를 전달하지 않도록 이름 텍스트를 항상 함께 표시한다(SCREEN-024 디자인 결정).

## implements_in_module_ids

- MOD-024


---

<!-- UI-110 -->

# display: RoleBadge

## name

RoleBadge

## tags

- display
- badge
- role
- user

## category

display

## variants

### reviewer

- **description**: 검수자 — primary-05 배경 + primary-60 텍스트(6.09:1, AA)

### worker

- **description**: 작업자 — secondary-05 배경 + secondary-70 텍스트(10.01:1, AAA)

### portal

- **description**: 포털 사용자 — neutral-05 배경 + border + neutral-80 텍스트

### unassigned

- **description**: 미배정 — warn-05 배경 + warn-70 텍스트(8.43:1, AAA) + warning 아이콘(삼각형 경고) 병기, 색상 단독으로 "주의 필요"를 전달하지 않기 위함

## description

사용자의 역할(검수자/작업자/포털 사용자/미배정)을 표시하는 배지. 작업/배치 워크플로 상태를 표시하는 StatusBadge(UI-014, 16종 매핑)와는 의미 축이 달라(역할 vs 상태) 별도로 정의한다.

## props_schema

### role

- **type**: 'REVIEWER'|'WORKER'|'PORTAL_USER'|null
- **required**: true
- **description**: 역할 코드. null은 미배정을 의미

### label

- **type**: string
- **required**: false
- **description**: 기본 매핑 라벨을 덮어쓸 때 사용

## usage_example

SCREEN-024(사용자 관리) 사용자 목록 표 역할 컬럼, 사용자 정보 수정 모달의 참고 서브카드(미배정 사용자를 열었을 때 대체 상태).

## design_system_id

DS-001

## accessibility_notes

unassigned variant만 warning 아이콘 + 한글 라벨을 병기해 색상만으로 의미를 전달하지 않는다. 그 외 variant도 항상 역할명 텍스트를 함께 표시한다.

## implements_in_module_ids

- MOD-025


---

<!-- UI-111 -->

# display: Badge

## name

Badge

## tags

- display
- badge
- notice

## category

display

## variants

### pinned

- **description**: "중요" 표시 — warn 톤(warn-0 배경 + warn-70 텍스트, 8.43:1 AAA) + Pin 아이콘 + "중요" 텍스트 병기

### success

- **description**: "발행" 상태 표시 — success 톤(success-0 배경 + success-70 텍스트, 6.99:1 AA)

### neutral

- **description**: "작성중"(DRAFT) 상태 표시 — neutral 톤(neutral-10/100 배경 + neutral-70 텍스트, 7.07:1 AAA)

## description

워크플로 코드 축(StatusBadge UI-014, 16종 매핑)이 아닌 자유 의미의 소형 pill 배지. 공지 목록/상세의 "중요(고정)" 표시와 "발행/작성중" 상태 표시처럼 도메인 자체 의미를 갖는 배지에 사용한다.

## props_schema

### variant

- **type**: 'pinned'|'success'|'neutral'
- **required**: true
- **description**: pinned=중요(고정), success=발행, neutral=작성중(DRAFT)

### label

- **type**: string
- **required**: true
- **description**: 배지에 표시할 텍스트(예: 중요/발행/작성중) — 색상 단독으로 의미를 전달하지 않기 위해 항상 텍스트를 병기한다

### icon

- **type**: string
- **required**: false
- **description**: pinned variant에서 Pin 아이콘 등을 병기할 때 사용

## usage_example

SCREEN-030(공지 목록) 표 제목 셀의 "중요"(고정) 배지 및 상태 컬럼(발행/작성중). SCREEN-031(공지 상세) 상단의 "중요"/"발행" 배지(작성중/DRAFT은 이 데모 데이터에는 없어 CSS로만 정의됨).

## design_system_id

DS-001

## accessibility_notes

모든 variant는 텍스트 라벨을 항상 함께 표시해 색상 단독 구분을 피한다(pinned는 Pin 아이콘도 추가 병기).

## implements_in_module_ids

- MOD-026


---

<!-- UI-112 -->

# display: AttachmentList

## name

AttachmentList

## tags

- display
- attachment
- file-list

## category

display

## variants

### default

- **description**: 파일 아이콘(Paperclip) + 파일명(ellipsis, title 속성으로 전체 파일명 접근성 보완) + 크기 + 액션 버튼(UI-001 Button variant=ghost size=icon-sm)

### downloading

- **description**: 파일명/크기 텍스트를 옅게 표시하고 액션 버튼을 aria-busy + disabled + Spinner 아이콘으로 전환(재클릭 방지)

### empty

- **description**: 첨부파일 0건 안내 카드 — 목록과 배타적으로 노출되는 실제 분기

## description

첨부파일을 파일 아이콘 + 파일명(ellipsis) + 파일 크기 + 액션(다운로드 또는 삭제) 아이콘 버튼 한 행으로 나열하는 목록 컴포넌트. 항목별 진행 상태(예: 다운로드 중)를 표시할 수 있다.

## props_schema

### items

- **type**: Array<{ name: string; size: string; status?: 'idle' | 'downloading' }>
- **required**: true
- **description**: 첨부파일 목록

### action

- **type**: 'download'|'delete'
- **required**: true
- **description**: 행마다 노출할 액션 종류(SCREEN-031=download, SCREEN-037=delete)

## usage_example

SCREEN-031(공지 상세) 첨부 다운로드 목록(action=download, 다운로드 중 상태 포함). SCREEN-037(공지 수정) 기존 첨부파일 관리 목록(action=delete, 0건 참고 카드 포함).

## design_system_id

DS-001

## accessibility_notes

시각 라벨이 없는 아이콘 전용 액션 버튼에는 파일명을 포함한 aria-label을 명시한다.

## implements_in_module_ids

- MOD-027


---

<!-- UI-113 -->

# display: PresetLabelOverflowChip

## name

PresetLabelOverflowChip

## tags

- display
- chip
- preset

## category

display

## variants

### default

- **description**: neutral 톤(neutral-1 배경 + neutral-6 텍스트, 5.13:1 AA), 숫자만 표시

## description

프리셋 카드의 라벨 칩이 6개를 초과할 때 나머지 개수만 "+N"으로 축약해 표시하는 칩. PresetCodeChip(UI-092)과 유사하나 라벨 정보 없이 숫자만 표시하는 축약 전용 칩이다.

## props_schema

### count

- **type**: number
- **required**: true
- **description**: 6개를 초과한 나머지 라벨 개수(N)

## usage_example

SCREEN-026(프리셋 관리) 카드 그리드에서 라벨이 6개를 초과할 때(예: 8개 중 6개 표시+2, 12개 중 6개 표시+6).

## design_system_id

DS-001

## accessibility_notes

디자인 기준 초안 — 구현 시 확정. "+N" 텍스트 자체가 정보를 전달하므로 별도 아이콘은 사용하지 않는다.

## implements_in_module_ids

- MOD-028


---

<!-- UI-114 -->

# input: PresetLabelPicker

## name

PresetLabelPicker

## tags

- input
- checkbox-list
- preset

## category

input

## variants

### default

- **description**: 스크롤 가능 목록(최대 높이 260px), 행당 Checkbox(UI-024) + 라벨명 + 형태 읽기전용 배지, 선택 개수 카운터를 굵게 강조해 병기

## description

프리셋 편집 모달(UI-091 PresetEditModal)에서 라벨 마스터를 다중 선택하는 체크박스 목록 컨테이너. 행마다 체크박스 + 라벨명 + 형태(BBOX/POLYGON) 읽기전용 배지로 구성되고 스크롤 가능한 높이 제한(260px)을 둔다.

## props_schema

### items

- **type**: Array<{ labelId: string; labelName: string; shapeType?: 'BBOX' | 'POLYGON'; checked: boolean }>
- **required**: true
- **description**: 라벨 마스터 활성 목록 + 선택 상태

### onChange

- **type**: (labelId: string, checked: boolean) => void
- **required**: false

## usage_example

SCREEN-026(프리셋 관리) 편집 모달의 "라벨 마스터 체크박스 멀티셀렉트" 슬롯(예: 라벨 마스터 20개 중 8개 선택 상태로 시연).

## design_system_id

DS-001

## accessibility_notes

Checkbox(UI-024)를 조합한 목록이며, 형태 배지는 읽기 전용으로 라벨명과 함께 노출되어 시각적 구분을 돕는다.


---

<!-- UI-115 -->

# display: FieldCounter

## name

FieldCounter

## tags

- display
- input
- counter
- field

## category

display

## variants

### default

- **description**: neutral-50 텍스트(4.51:1, AA) × white 배경, caption 스타일. SCREEN-036 디자인에서는 D2Coding mono 폰트로 표시

## description

텍스트 입력 필드의 현재 글자 수 / 최대 글자 수를 "18/200"처럼 표시하는 카운터. Field(UI-099) 라벨 옆에 캡션 스타일로 병기해 글자 수 제한을 시각적으로 체감시킨다.

## props_schema

### current

- **type**: number
- **required**: true
- **description**: 현재 입력된 글자 수

### max

- **type**: number
- **required**: true
- **description**: 허용 최대 글자 수

## usage_example

SCREEN-036(공지 작성) 제목 Input 라벨 옆 "18/200" 문자 수 카운터 — 최대 200자 제한 검증 규칙을 시각화.

## design_system_id

DS-001

## accessibility_notes

디자인 기준 초안 — 구현 시 확정. 이번 디자인에서는 한도 근접/초과 시의 색상 전환(warn)이 실제로 정의되지 않았다(추정하지 않음).

## implements_in_module_ids

- MOD-029

