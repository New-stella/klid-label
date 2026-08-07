# 배치 A — 라벨링 축 (SCREEN-005, SCREEN-010)

## 요약
- 검토 섹션 18개(SCREEN-005 14개 + SCREEN-010 4개) / 컴포넌트 76개(65+11)
- 발견: ERR 7 / STALE 2(ERR과 중복 표기된 2건 포함, 아래 상세 참조) / GAP 6 / CONFLICT 2
- 최중대 3건:
  1. **[ERR-A03]** 좌측 "라벨 마스터 사이드바"(section[2], LabelSidebar)는 2026-08-03 폐지된 UI다. 현재는 도형 도구 클릭 시 뜨는 `LabelPickerModal`(도구→라벨선택→드로잉 순서)로 완전히 교체됐다. 제3자가 이 섹션대로 만들면 존재하지 않는 화면을 만들게 된다.
  2. **[GAP-A06/A07]** 우측 패널의 실제 구조는 "객체/메타/이슈" 3탭인데 정의서 `purpose`는 "Objects/Issues" 2탭만 언급하고, `sections`는 메타 탭 내부 6개 패널(시계열/프레임설명/이벤트어노테이션/개인정보×2/촬영환경)을 마치 항상 보이는 독립 side 섹션인 것처럼 각각 흩어 기술한다. 게다가 이슈 탭(IssueThreadPanel) 자체는 어느 섹션에도 없다 — API-102~105 가 `consumes_apis`에만 있고 참조하는 컴포넌트가 0건이다.
  3. **[GAP-A13]** SCREEN-010 "라벨 이력 화면"의 `sections`는 실제 `HistoryPanel`의 두 탭(변경 이력/버전) 중 "버전"(commit) 탭만 기술하고, 화면 제목이 그대로 가리키는 "변경 이력"(LS_DATA_LBL_HSTRY, 기본 탭, 되돌리기 기능 포함)은 전혀 언급하지 않는다.

---

## SCREEN-005

### [ERR-A01] 헤더 저장 버튼 — 2026-08-06 제거됨, 정의서엔 여전히 존재
- **위치**: section[0] "라벨링 헤더 바" 섹션 설명 + `components[9]` (`type=Button, label='저장', variant='primary', triggers_api=API-019`)
- **정의서 서술**: "우: N개 객체 + 프레임이미지타입 배지(DEID/RAW) + 비식별누락신고 버튼 + 히스토리 토글 + **저장 버튼** + 검수제출 버튼(WORKER만)."
- **실제**: 헤더의 저장 버튼은 2026-08-06 제거됐다(진입점 일원화). 저장 진입점은 좌측 도구바(`DarkToolbar`)의 저장 버튼 + `Ctrl+S` 뿐이며, 헤더는 "지금 저장돼 있나"라는 **상태**(`✓ 저장됨`/`● 편집 중`/`저장 중...`)만 표시한다.
- **근거**: `LabelHeader.tsx(LabelHeader)` — 컴포넌트 최상단 주석 "★ 2026-08-06 — 헤더 [저장] 버튼 제거(진입점 일원화)" + 렌더 트리 말미의 명시적 금지 주석 "⚠ 여기에 [저장] 버튼을 다시 넣지 말 것". `DarkToolbar.tsx` 상단 주석 "★ 2026-08-06 — 라벨링 화면의 유일한 저장 진입점이다(구 헤더 [저장] 버튼 제거)". section[1] `components[7]`(도구바 저장 버튼)은 이미 정의서에 존재하므로 헤더 쪽 컴포넌트[9]는 순수 중복/오류다.
- **조치 제안**: section[0] 설명 문장에서 "저장 버튼" 제거, `components[9]`를 삭제하거나 "저장 상태 표시(텍스트, 버튼 아님)"로 정정. section[1]의 도구바 저장 버튼이 유일한 저장 진입점임을 명시.
- **확신도**: high

### [ERR-A02] 헤더/도구바 "다크" 서술 — 2026-08-06 다크 테마 폐지됨
- **위치**: section[0] 설명 "풀스크린 상단 56px **다크** 바(LabelHeader)", section[1] 설명 "좌측 세로 아이콘 도구바(**DarkToolbar**, w-14)"
- **정의서 서술**: 헤더를 "다크 바"로 서술.
- **실제**: 2026-08-06 사용자 확정으로 라벨링·검수 화면의 다크 테마는 전면 폐지되고 라이트로 전환됐다. 헤더는 `bg-white`, 도구바도 `bg-white`, 하단 프레임 슬라이더도 `bg-white`다. 예외는 영상 프레임을 얹는 미디어 매트(`bg-gray-200`)와 그 위 오버레이(프레임번호 배지 `bg-black/50`)뿐이다. 컴포넌트 파일명(`DarkToolbar.tsx`, `DarkFrameStrip.tsx`, `DarkFrameSlider.tsx`)은 리네임되지 않아 `custom_name` 필드로 쓰는 것은 문제없으나, 사람이 읽는 설명 문장에서 "다크"를 시각적 사실처럼 쓰면 오도한다.
- **근거**: `LabelHeader.tsx` (`className="... bg-white border-b border-gray-200 ..."`) · `DarkToolbar.tsx` (`className="flex flex-col bg-white border-r border-gray-200 w-14 shrink-0"`) · `DarkFrameSlider.tsx`(`className="flex items-center gap-2 px-3 bg-white h-full ..."`).
- **조치 제안**: "다크 바"/"다크 도구바" 등 시각 서술을 "라이트 테마(공통 컴포넌트 기반)"로 정정. `custom_name`의 `Dark*` 접두어는 식별자로만 남기고 산문에서는 색상을 재서술하지 않는다.
- **확신도**: high

### [ERR-A03] 좌측 "라벨 마스터 사이드바" — 2026-08-03 폐지, `LabelPickerModal` 로 완전 교체
- **위치**: section[2] "라벨 마스터 사이드바" 전체(1 섹션, `components[0]`, `custom_name` 없음 but `type=List`)
- **정의서 서술**: "도구바 우측 라벨 마스터 목록(LabelSidebar, w-56). useLabelMasters로 GET /v1/manage/labels 조회 후 useYn='Y' 필터 + sortNo asc 정렬. 각 항목: 색상 박스 + 한글 라벨명 + (sortNo 1~9) 단축키 번호. 클릭 시 useLabelStore.activeLabelId 설정 → 신규 객체 그릴 때 기본 라벨로 사용."
- **실제**: 이 **항상 보이는 좌측 사이드바 자체가 존재하지 않는다.** `LabelSidebar.tsx` 파일이 코드베이스에 없다(검색 0건). 2026-08-03 사용자 확정으로 상호작용 순서가 "도구 클릭 → 라벨 선택 → 드로잉"으로 바뀌었고, 라벨 선택은 바운딩박스/폴리곤 등 도형 도구를 클릭/전환하는 **그 시점에 뜨는 모달**(`LabelPickerModal`)에서 이뤄진다. 라벨을 고르기 전에는 캔버스 드로잉이 시작되지 않는다. 목록 출처(GET /v1/manage/labels, useYn='Y' 필터, sortNo asc)와 1~9 단축키 슬롯은 그대로 이 모달로 이관됐다.
- **근거**: `LabelPickerModal.tsx` 파일 최상단 주석 "라벨 선택 모달 — 도형 도구를 클릭/전환하는 시점에 뜬다 (2026-08-03 사용자 확정)" + "배경: 좌측 상시 라벨 패널(구 LabelSidebar)을 폐지하고, '도구 클릭 → 라벨 선택 → 드로잉' 순으로 조작 흐름을 바꿨다." · `LabelingPage.tsx` 주석 "본문 — 좌측 도구바 + 캔버스 + 우측 패널 (좌측 상시 라벨 패널은 2026-08-03 폐지)".
- **조치 제안**: section[2] 전체를 "라벨 선택 모달(LabelPickerModal)"로 교체 서술 — 트리거 조건(도형 도구 클릭/전환 시), 목록 출처·정렬·활성 필터는 유지, 1~9 단축키는 모달 컨텍스트로 재서술, 검색창(신규 — 마스터 전체 노출이라 건수가 많을 수 있음) 추가, 취소 시 도구가 활성화되지 않고 이전 상태로 복귀함을 명시. section[1] 설명의 "도구 선택 자체는 클라이언트 캔버스 상태만 변경(API 호출 없음)"도 함께 정정 필요(도형 도구는 이제 라벨 모달을 거치므로 API 호출은 없어도 모달 오픈이라는 상태 변화가 있다).
- **확신도**: high

### [ERR-A04] `AutolabelClassModal` 커스텀명 오류 — 실제 컴포넌트는 `AiToolModal`
- **위치**: `purpose` 필드("YOLO 오토라벨은 AutolabelClassModal(클래스 선택)→POST autolabel"), section[1] `components[9]` note, section[12] 섹션 이름·`components[1]`(`custom_name='AutolabelClassModal'`)
- **정의서 서술**: 오토라벨 클래스 선택 모달의 컴포넌트명을 `AutolabelClassModal`로 일관되게 지칭.
- **실제**: 현재 코드에 `AutolabelClassModal`이라는 컴포넌트/파일은 존재하지 않는다(검색 0건). 실제로 좌측 도구바 "AI 탐지" 버튼이 여는 모달은 `AiToolModal`이며, 이는 오토라벨(클래스/도형/정밀도) 뿐 아니라 `canTrack` prop 을 통해 **추적 모드**까지 함께 다루는 통합 모달이다(`onConfirm={runAiTool}`).
- **근거**: `LabelingPage.tsx` — `<AiToolModal open={autolabelModalOpen} ... canTrack={nextSrcSns.length > 0} candidates={detectCandidates} ... onConfirm={runAiTool} />` 및 인접 주석 "Phase 4 — AI Tool 팝업(형태 + 라벨 + 일반/트랙). 확정 시 shape/classIds/mode 로 실행."
- **조치 제안**: `purpose`·section[1]·section[12]의 `custom_name`을 `AiToolModal`로 정정하고, "일반 오토라벨 모드"와 "추적(track) 모드"를 함께 처리하는 통합 모달임을 명시(`canTrack` 조건부 노출).
- **확신도**: high

### [ERR-A05] UI 문구가 기술 모델명(YOLO/SAM)을 노출 — 확정 정책 위반 + 실제 표기와 불일치
- **위치**: section[1] `components[3]` `label='SAM 분할 (G)'`, `components[4]` `label='SAM 추적 (T)'`, `components[9]` `label='오토라벨 (YOLO)'`; section[0] 설명 텍스트 "SAM2 자동추적"; purpose "YOLO 오토라벨"
- **정의서 서술**: 버튼 라벨에 "SAM"·"YOLO"를 그대로 노출.
- **실제**: 2026-07-17 사용자 확정 정책 — 프론트엔드 **사용자 노출 문구**(버튼·툴팁·토스트 등)에 YOLO/SAM2 등 기술 모델명을 쓰지 않는다("AI 접두어 통일": YOLO 탐지→AI 탐지, SAM2 분할→AI 분할, SAM2 Track→AI 추적). 실제 버튼 텍스트도 이 정책대로다.
- **근거**: `features/label/types.ts(TOOL_DISPLAY_NAME)` — `[ToolType.TRACK]: 'AI 추적'`, `[ToolType.SAM_SEGMENT]: 'AI 분할'`. `DarkToolbar.tsx` 오토라벨 액션 항목 `label: 'AI 탐지'`.
- **조치 제안**: `label` 필드를 실제 표기(`AI 분할 (G)`/`AI 추적 (T)`/`AI 탐지`)로 정정. 내부 식별자·API 경로(`/sam2-segment`, `/sam2-track`, `autolabel`)·`custom_name`은 변경 대상 아님 — 산문·label 필드만 정정.
- **확신도**: high

### [GAP-A06] 우측 패널의 실제 구조(객체/메타/이슈 3탭)가 정의서에 없음
- **위치**: `purpose`("우측 패널은 Objects/Issues 탭 분기"), section[4]/[9]/[11]/[13] 전체(각각 독립 `role=side` 섹션으로 병렬 나열)
- **정의서 서술**: purpose는 우측 패널을 "Objects/Issues 탭 분기"(2탭)로만 설명하고, section[4](객체·속성·시계열메타), section[9](프레임 설명), section[11](이벤트 어노테이션), section[13](개인정보·촬영환경)을 서로 독립적인, 병렬적으로 항상 존재하는 side 섹션처럼 나열한다.
- **실제**: 우측 패널은 실제로 **"객체"/"메타"/"이슈" 3개 탭**(`role="tablist"`, `rightTab: 'objects' | 'meta' | 'issues'`)으로 구성된다. "객체" 탭에는 `ObjectClassTree` + `ObjectAttributePanel`만 있다. section[9]("프레임 설명")·section[11]("이벤트 어노테이션")·section[13]("개인정보·촬영환경")과 section[4]의 "시계열 메타" 부분은 전부 **하나의 "메타" 탭 안에 세로로 쌓여 스크롤되는 접이식 패널들**이다(`EnvironmentMetaPanel` → `VideoPrivacyMetaPanel` → `FramePrivacyMetaPanel` → `FrameDescriptionPanel` → `TimeseriesSidePanel` → `EventAnnotationPanel` 순서로 한 컨테이너 안에 렌더). "이슈" 탭은 별도로 존재하며 section 자체가 없다(아래 GAP-A07).
- **근거**: `LabelingPage.tsx` — `const [rightTab, setRightTab] = useState<'objects' | 'meta' | 'issues'>('objects')` 및 탭바 렌더 블록(`role="tablist"`, "객체"/"메타"/"이슈" 버튼 3개), `showMeta && rightTab === 'meta'` 분기 안에 `EnvironmentMetaPanel`·`VideoPrivacyMetaPanel`·`FramePrivacyMetaPanel`·`FrameDescriptionPanel`·`TimeseriesSidePanel`·`EventAnnotationPanel` 6개가 순서대로 나열.
- **조치 제안**: purpose를 "객체/메타/이슈 3탭"으로 정정. section[4]를 "객체" 탭(ObjectClassTree+ObjectAttributePanel)과 "메타" 탭 공통 컨테이너 두 갈래로 재구성하거나, 최소한 각 섹션 설명에 "이 패널은 우측 패널의 '메타' 탭 안에서 다른 5개 패널과 함께 세로로 쌓여 렌더되며, '메타' 탭으로 전환해야 보인다"는 문장을 추가. 탭 노출 조건(`showMeta = !portalMode`, `showIssues = !portalMode && issueRawSn !== undefined`)도 명시.
- **확신도**: high

### [GAP-A07] "이슈" 탭(IssueThreadPanel) 자체가 어느 섹션에도 없음
- **위치**: `purpose`에서만 언급("Issues 탭은 검수자↔작업자 이슈 소통 채널(IssueThreadPanel, UI-097/DFEAT-049, API-102~105, 미해소 문의 카운트 배지, videoId 부재 시 탭 비노출)"). `consumes_apis`에 API-102~105 포함.
- **정의서 서술**: purpose 한 문장 요약만 있고, 14개 `sections` 어디에도 이슈 탭의 컴포넌트·상태·동작을 다루는 섹션이 없다.
- **실제**: 실제 구현은 상당히 크다 — `IssueThreadPanel`(mode='worker'), 미해소 문의(inquiry) 생성 폼, 댓글 작성, RESOLVED 시 댓글 입력 잠금(단 REJECTION 타입은 RESOLVED 여도 입력 가능), 이슈 타입/상태 배지, 탭에 미해소 건수 배지(`issue-tab-badge`) 등. API-102~105는 `IssueController`의 4개 엔드포인트(POST /videos/{rawSn}/issues, GET /videos/{rawSn}/issues, POST /issues/{issueSn}/comments, POST /issues/{issueSn}/resolve)와 정확히 대응하지만 어떤 섹션의 `triggers_api`로도 참조되지 않는다(기계적으로 확인: `consumes_apis - {section 참조 API}` = `{API-033, API-102, API-103, API-104, API-105}`).
- **근거**: `features/review/components/IssueThreadPanel.tsx`(`IssueThreadPanel`, `mode: 'worker' | 'reviewer'`) · `backend/.../review/controller/IssueController.java`(4개 매핑) · `LabelingPage.tsx`(`showIssues && rightTab === 'issues' && issueRawSn !== undefined ? <IssueThreadPanel rawSn={issueRawSn} mode="worker" /> : ...`, `unresolvedInquiries` 배지).
- **조치 제안**: 신규 섹션("이슈 소통 패널")을 추가해 최소한 다음을 명시: 노출 조건(내부 채널 + videoId 존재), 문의 생성 폼, 댓글 작성, RESOLVED 시 댓글 잠금 예외(REJECTION), 상태/유형 배지, 미해소 카운트 배지 노출 위치(탭 라벨 옆). API-102~105 각각을 `triggers_api`로 연결.
- **확신도**: high

### [ERR-A08] 이벤트 어노테이션 패널 — "vqa/cot/vd_description은 VLM 생성(표시)" 서술이 실제와 반대
- **위치**: section[11] 섹션 설명 "caption만 VLM+수동입력이고 vqa/cot/vd_description은 VLM 생성(표시)."
- **정의서 서술**: 질의응답(VQA)·CoT·`vd_description`은 읽기 전용(VLM이 생성한 값을 표시만) 필드이고, caption만 사람이 편집 가능하다고 서술.
- **실제**: `question`(질의)·`answer`(답변) 필드는 완전히 편집 가능한 `Textarea`다(`onChange` 바인딩, readOnly/disabled 없음). 컴포넌트 최상단 주석도 "작업자/검수자가 전 필드 수동 덮어쓰기 후 저장"이라고 명시한다. 또한 이 패널 안에는 `vd_description`이라는 필드 자체가 없다(코드 전체 검색 0건) — `vd_description`은 CLAUDE.md의 별개 개념(export JSON의 `video.vd_description`, VLM 시계열 서술 조달값)과 혼동된 것으로 보인다. `cot`(CoT 3단계)도 caption 후보 행마다 사용자가 입력하는 배열 필드다(`row.cot.map(...)`로 편집).
- **근거**: `EventAnnotationPanel.tsx` — 파일 상단 주석 "작업자/검수자가 전 필드 수동 덮어쓰기 후 저장 → useUpdateEventAnnotation(rawSn)" + `<Textarea id="ea-question" value={question} onChange={(e) => setQuestion(e.target.value)} .../>` + `<Textarea id="ea-answer" value={answer} onChange={(e) => setAnswer(e.target.value)} .../>`. 델리버리 FE도 동일: `klid-label-frontend/src/components/labeling/panel/event-annotation/EventAnnotationPanel.tsx`(`onChange={(e) => form.setQuestion(e.target.value)}` 등).
- **조치 제안**: "caption만 편집, vqa/cot/vd_description은 읽기 전용" 서술을 삭제하고 "event_class(필수)·question(질의)·answer(답변)·caption 후보(c1..cn, 각 caption + CoT 3단계)·evidence 후보를 작업자/검수자가 모두 직접 입력/수정하며, 최초 로드 시 외부 자동 생성값으로 1회 프리필된다"로 정정. `vd_description`은 이 화면의 개념이 아니므로 제거(export 정책 문서에서만 다룰 개념).
- **확신도**: high

### [ERR-A09] `EvidenceLinker`/"MAX_ID 힌트" — 실제 컴포넌트명·메커니즘과 불일치
- **위치**: section[11] `components[4]` (`type=Custom, label='evidence(근거 객체)', custom_name='EvidenceLinker'`, note="캔버스 선택객체 자동연결 + MAX_ID 힌트를 제공")
- **정의서 서술**: 커스텀 컴포넌트명 `EvidenceLinker`, "MAX_ID 힌트"라는 보조 기능 서술.
- **실제**: `EvidenceLinker`라는 컴포넌트는 존재하지 않는다. 실제 evidence 행 컴포넌트는 `EvidenceCandidateRow`이고, 행 키는 `nextKey(prev)`로 파생되는 `c1, c2 ...` 형태의 순번(caption 후보와 동일한 키 체계)이며 "MAX_ID"라는 명칭의 힌트는 코드 전체에서 발견되지 않는다.
- **근거**: `features/label/components/EvidenceCandidateRow.tsx`(파일 존재) · `EventAnnotationPanel.tsx`의 `nextKey(prev)` 기반 키 생성 로직. "EvidenceLinker"·"MAX_ID" 문자열은 프로젝트 전체(frontend/backend) 검색 0건.
- **조치 제안**: `custom_name`을 `EvidenceCandidateRow`로 정정, "MAX_ID 힌트" 서술을 "행 키는 c1..cn 순번으로 자동 부여되며, 캔버스에서 선택한 라벨 객체 1건의 좌표/클래스를 evidence 행의 obj_* 필드에 자동 채워 넣는 버튼을 제공"으로 정정.
- **확신도**: medium (컴포넌트명·"MAX_ID" 부재는 high, 정확한 대체 문구는 코드 동작 재확인 권장)

### [GAP-A10] 단축키 치트시트 모달(ShortcutCheatSheet) 전체 누락
- **위치**: 없음 — 14개 섹션 어디에도 "치트시트"/"단축키 도움말"/"CheatSheet" 관련 서술이 없다(전문 검색 0건). 다만 section[0] `LabelHeader`에는 이를 여는 `?` 버튼이 실제로 존재한다(`onHelpClick`, `data-testid="shortcut-help-button"`).
- **정의서 서술**: (없음)
- **실제**: 헤더 우측 도움말(`?`) 버튼을 클릭하면 `ShortcutCheatSheet` 모달이 열리고, `SHORTCUT_KEYMAP`(단축키 단일 출처)을 도구/프레임이동/액션 3개 그룹으로 묶어 표로 보여준다. 포털 모드에서는 SAM2 분할/추적·키포인트처럼 포털에 없는 도구의 단축키 행을 목록에서 제외한다(`PORTAL_HIDDEN_TOOLS` 단일 소스 공유).
- **근거**: `features/label/components/ShortcutCheatSheet.tsx`(`ShortcutCheatSheetProps`, `portalMode` prop, `GROUPS`) · `LabelHeader.tsx`(`onHelpClick`/`shortcut-help-button`) · `LabelingPage.tsx`(`<ShortcutCheatSheet open={cheatSheetOpen} onClose={...} portalMode={portalMode} />`).
- **조치 제안**: section[0]에 도움말(`?`) 버튼 컴포넌트를 추가하고, 신규 섹션(또는 section[7] 모달 섹션에 추가)으로 "단축키 치트시트 모달"을 서술 — 그룹 3종(도구/프레임 이동/액션), 포털 모드 시 미제공 도구 단축키 제외 규칙 포함.
- **확신도**: high

### [GAP-A11] 라벨 색상 판정 순서·`labelId` 필수 요건이 문서화돼 있지 않음
- **위치**: section[4] `components[0]`("객체 목록", ObjectClassTree) 설명 — "트랙색상 바"만 언급, 판정 로직 없음
- **정의서 서술**: 색상에 대한 서술이 "트랙색상 바"라는 시각 요소 존재만 언급하고 판정 규칙이 없다.
- **실제**: CLAUDE.md에 "★라벨 표시 색상의 단일 진실원" 항목으로 구속 정책이 있을 만큼 실제로 사고가 난 영역이다 — 색상 판정 순서는 `label.color`(BE enrichment) → `labelMasters[labelId||classId].color` → `trackId` 해시 → source fallback이며, 라벨 생성 시 `labelId`가 채워지지 않으면(예: `classId`만 채운 채 저장) 저장 **후 재조회 시에만** 색상이 3순위(트랙 해시색)로 조용히 낙하한다(저장 전에는 `classId` 폴백으로 정상처럼 보임). 우측 객체 패널의 그룹 헤더(분류축, 마스터 색상)와 개별 항목 막대(트랙 시각화축, 해시색)는 의도적으로 서로 다른 축을 보여준다.
- **근거**: `frontend/src/features/label/utils/labelColor.ts(getLabelDisplayColor)` · `frontend/src/features/label/components/OverlayLayer.tsx(newLabelFrom)` · `CLAUDE.md("★라벨 표시 색상의 단일 진실원")`.
- **조치 제안**: section[4] 설명에 색상 판정 순서(4단계)와 "그룹 헤더=분류축(마스터 색), 항목 막대=트랙 시각화축(해시색)"을 명시. 이 화면을 처음 구현하는 사람이 재현 가능하도록 하는 것이 목적이므로 최소한 판정 순서만이라도 남기는 것을 권장.
- **확신도**: medium (판정 순서 자체는 CLAUDE.md 근거로 high, section 문구로의 구체적 반영 방식은 제안 수준)

### [note] `API-033`이 `consumes_apis`에 있으나 어떤 섹션에서도 참조되지 않음
- **위치**: `consumes_apis` 목록
- **정의서 서술**: (참조 없음)
- **실제**: `API-032`(비식별 누락 신고)와 인접한 번호대라 관련 엔드포인트로 추정되나, 로컬 스테이징 킷 안에 `API-033.json`이 없어 정확한 경로를 확인하지 못했다. 사용처가 어느 섹션에도 없는 것은 사실이나(기계적 diff로 확인), 이 API 자체가 무엇인지는 미확인.
- **근거**: 기계적 diff(`consumes_apis - 섹션참조합집합 = {API-033, API-102~105}`), API-033 상세는 미확인.
- **조치 제안**: API-033의 실체를 확인해 관련 섹션에 `triggers_api`로 연결하거나, 화면에서 실제로 쓰지 않는다면 `consumes_apis`에서 제거.
- **확신도**: low (사용처 없음은 확인됐으나 API-033 정체는 미확인)

---

## SCREEN-010

### [CONFLICT-A12] 화면 자체 서술이 "라우트는 죽었다"와 "라우트 진입점이다"로 모순
- **위치**: `purpose` 필드 vs section[0] "페이지 헤더" 섹션 설명
- **정의서 서술**: `purpose` 필드는 "⚠ 이 화면은 2026-08-03 코드에서 제거됐다 — 라우트 /history/:videoId 와 HistoryPage 가 더 이상 존재하지 않으며 ... 진입 링크를 따라가면 404 다"라고 명시한다. 그런데 바로 아래 `sections[0]`("페이지 헤더")은 "뒤로가기 버튼 + 제목 '버전 관리' + videoId(=프레임 SRC_SN) 표기. **/history/:videoId 라우트 진입점(HistoryPage)**. videoId 가 숫자가 아니면 '잘못된 영상 ID' ErrorState 표시."라고, 마치 지금도 라우트로 진입 가능한 독립 페이지인 것처럼 서술한다.
- **실제**: 실제 통합 위치는 라벨링 캔버스(SCREEN-005) 우측 "히스토리" 토글로 여는 **인라인 슬라이드 패널**(`HistoryPanel`)이며, 이 패널에는 "뒤로가기" 버튼이나 "videoId 숫자 검증 ErrorState"가 없다(대신 `onClose` prop으로 X 닫기, `srcSn`을 프레임 컨텍스트에서 항상 정수로 전달받음 — URL 파싱·NaN 케이스 자체가 없음).
- **근거**: `HistoryPanel.tsx` 파일 상단 주석 "라벨링 화면(SC-005) 우측 슬라이드 패널의 본문 UI. 유일한 사용처이며 ... (구 별도 페이지 /history/:videoId 는 2026-08-03 제거.)" — `srcSn: number`(props, URL 파싱 없음), `onClose?: () => void`(X 닫기, 뒤로가기 아님).
- **조치 제안**: section[0]을 통째로 삭제하거나(더 이상 해당하는 UI가 없으므로), 남기려면 "이 섹션은 폐지된 구 페이지의 헤더였다 — 현재는 section 없음, HistoryPanel은 onClose(X버튼)만 가짐"으로 명시적으로 재작성. 이 화면 ITEM 자체를 유지할지(purpose가 이미 "별도 결정 사항"이라 언급)와 무관하게, 남기는 섹션 서술은 최소한 purpose와 모순되지 않아야 한다.
- **확신도**: high

### [GAP-A13] "라벨 이력" 화면인데 "변경 이력"(LS_DATA_LBL_HSTRY) 탭이 통째로 빠짐
- **위치**: `sections[1]` "커밋 목록" 전체 — 실제 `HistoryPanel`의 두 탭 중 "버전"(versions) 탭만 서술
- **정의서 서술**: `sections`는 "프레임(srcSn)의 라벨 버전 스냅샷 목록(최신순)"만 다루며, 비교축(R7)·롤백 시맨틱 등 버전(commit) 탭의 최신 정책은 정확히 반영돼 있다. 그러나 화면 자체는 "라벨 이력 화면"(SCREEN-010 title)이라는 이름을 갖는다.
- **실제**: 실제 `HistoryPanel`은 **두 개의 탭**을 가진다 — ① "변경 이력"(changes, `LabelHistoryPanel` 컴포넌트, `LS_DATA_LBL_HSTRY` 저장 이벤트 단위, **기본 활성 탭**) ② "버전"(versions, 커밋/스냅샷, `LS_LABEL_VERSION`). "변경 이력" 탭은 저장 시점마다 카드로 나열되고(추가/수정/삭제 건수 배지), 카드를 펼치면 라벨별 diff 상세가 보이며, 각 카드에 "되돌리기"(`onRevert`, 그 저장 이벤트를 현재 작업본에 역적용) 버튼이 있다. 이 탭은 `GET /v1/frames/{srcSn}/label-history` API를 쓰는데, 이 API는 SCREEN-010의 `consumes_apis`(`API-034`, `API-035`, `API-036`만 나열)에도 없다. "라벨 이력"이라는 화면 제목은 오히려 이 "변경 이력" 탭(정확히 `LS_DATA_LBL_HSTRY` = 라벨 변경 이력)에 더 부합하는데, 정작 정의서는 이 탭을 완전히 빠뜨리고 "버전" 탭만 "라벨 이력 화면"으로 서술한 형태가 됐다.
- **근거**: `HistoryPanel.tsx` — `type HistoryTab = 'changes' | 'versions'`, `defaultTab = 'changes'`(주석: "기본 활성 탭 = 변경 이력(저장). 저장 직후 사용자가 기대하는 화면을 바로 노출한다"), `activeTab === 'changes' ? <LabelHistoryPanel srcSn={srcSn} onRevert={onRevert} /> : ...`. `LabelHistoryPanel.tsx` — 파일 상단 주석 "버전(LS_LABEL_VERSION) 이력 패널(features/version/HistoryPanel)과 별개다. 이 패널은 프레임(srcSn) 단위 라벨 변경 이력(LS_DATA_LBL_HSTRY)을 '저장 이벤트' 단위로 최신순 카드로 보여주고 ...", 소비 계약 "GET /v1/frames/{srcSn}/label-history". `backend/.../label/controller/LabelController.java` — `@GetMapping("/{srcSn}/label-history")`. 관련 UC: `UC-021`(라벨 편집·임시저장) 설명에 "되돌리기는 저장 이벤트 단위 diff 이력(GET /v1/frames/{srcSn}/label-history)을 통해 이전 저장본으로 복원할 수 있으며, FE undo/redo(세션 내)와는 별개 개념이다"라고 이미 문서화돼 있다 — 즉 다른 ITEM(UC-021)에는 이 기능이 알려져 있는데 SCREEN-010에는 반영되지 않았다.
- **조치 제안**: SCREEN-010(또는 SCREEN-005 section[5])에 "변경 이력" 탭 섹션을 신설 — 탭 전환 UI(변경 이력/버전, 기본값=변경 이력), 저장 이벤트 카드(추가+N/수정~N/삭제-N 배지), 펼침 diff, "되돌리기" 버튼과 그 의미(현재 작업본에 역적용, 버전 스냅샷과 무관), `GET /v1/frames/{srcSn}/label-history` API를 `consumes_apis`와 `triggers_api`에 추가.
- **확신도**: high

### [STALE-A14] `static_renders[0].description` 임베디드 서술이 이미 폐기된 구 정책을 담고 있음
- **위치**: `static_renders[0].description` 필드(화면 렌더 미리보기 메타데이터, `sections`와 별개 필드)
- **정의서 서술**: "HistoryPage/HistoryPanel 기반 — 커밋 목록·diff·롤백. FEAT-002 DB 스냅샷 버전관리." 및 내부 `sections[1]`(커밋 목록) 설명에 "단일선택=직전버전 diff, 체크2개=두버전 diff."라고 명시.
- **실제**: 위 SCREEN-010 본문 `sections`(및 SCREEN-005 section[5])가 이미 정확히 기록하고 있듯, "단일선택=직전버전 diff"는 2026-08-05 폐기된 구 정책이다. 현재는 "단일 선택=현재 작업본과 비교"(`diff-with-working`, API-182)다. 같은 ITEM 안에서 `sections` 필드는 신정책을, `static_renders[0].description` 임베디드 필드는 구정책을 담고 있어 내부적으로 모순된다.
- **근거**: `static_renders[0]` 내 `sections[1].description`("단일선택=직전버전 diff") vs 본문 `sections[1].description`("커밋 1건 클릭 = <현재 작업본과 비교>(GET /v1/versions/{version}/diff-with-working) ... 구 동작 '단건 선택은 직전 버전과 비교'는 폐기했다").
- **조치 제안**: `static_renders` 임베디드 설명도 본문 `sections`와 같은 시점으로 갱신하거나, 렌더 미리보기가 신뢰할 수 있는 산출물이 아니라면 감사 범위·소유자에게 별도 보고(이 필드가 실제로 "화면정의서 본문"으로 취급되는지는 LogiCraft 스키마 정책에 달려 있어 낮은 확신도로 표기).
- **확신도**: medium (내용 불일치 자체는 high, 이 필드가 "화면정의서"로서 감사 대상인지는 확인 필요)

---

## 부록 — 기계적 점검 결과
- SCREEN-005 `consumes_apis`(29개) 중 어떤 섹션에서도 참조되지 않는 것: `API-033`, `API-102`, `API-103`, `API-104`, `API-105` (5건, GAP-A07·note 참조)
- SCREEN-005 컴포넌트 합계 65건 = section별 [11,10,1,2,7,3,2,5,1,2,4,8,6,3] 합과 일치(구조적 정합, 이상 없음)
- SCREEN-010 `sections` 필드는 4섹션/11컴포넌트로 `static_renders[0]`의 임베디드 sections(동일 4섹션 구조)와 구조는 일치하나 본문 일부 표현이 다름(STALE-A14)
