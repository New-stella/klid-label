---
screen: SCREEN-005
generated_at: 2026-08-11T00:00:00Z
screen_design_item: SD-002
design_render_urls: [/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-002/main.html]
surfaces: [main]
ds: DS-001 (KRDS Public)
source_wireframes: [wireframe.html]
generated_by: mc-logi-screen-design
---

# SCREEN-005 디자인 노트 — 라벨링 캔버스 화면

> Phase 1~4(입력 합성 → 로컬 디자인 작성 → 검증 → 키트 반영)만 수행했다. Phase 5(screen_design 역등록)·
> Phase 6(ui_component 보강)은 이번 배치에서 수행하지 않았다 — 사용자 확인 후 별도 진행.

## § 디자인 결정 (와이어프레임 대비 추가분)

- **2026-08-11 (버그 수정)**: 사용자 신고 — "모달에 폰트 등이 잘못된 것 같다". 원인: `.lbl-overlay-showcase`
  (모달·다이얼로그 정적 미리보기 7종을 모은 섹션)가 `.lbl-root`의 형제(바깥)에 배치돼 있어 §2 RESET/BASE의
  `font-family: var(--font-body)`(+ 기본 색상·크기) 상속을 받지 못하고 브라우저 기본 폰트로 렌더링되고
  있었다. `.lbl-overlay-showcase`에 동일 베이스 선언(font-family/color/font-size/line-height)과
  `button/select/input/textarea { font-family: inherit; }`를 추가해 실제 화면과 동일한 타이포그래피로
  통일했다. HTML 변경 없음 — CSS만 수정.

- **2026-08-11**: SCREEN-005 screen_spec v61 반영 — 시계열 메타를 검수 상세(SCREEN-019)와 동일한
  3분할 구조(서술 전문/일치도/기술정보)로 교정. 구 "Textarea 1개 + 메타 저장 버튼 1개" 단일 편집
  UI를 ①서술 전문(vlm.description) Textarea(편집 가능) ②일치도(vlm.accuracy) 읽기전용 표시 ③시계열
  메타 저장 버튼 ④영상 기술 정보(technicalMeta) 읽기전용 2열 key-value 4개 컴포넌트로 교체했다.
  읽기전용 2개 항목은 `disabled`/입력 요소를 두지 않는 방식 + muted 배경·톤다운 텍스트(`lbl-field--readonly`)로
  구분하고 label 문구에도 "읽기전용"을 항상 병기했다(색만으로 구분하지 않음).

- **상태별**: 캔버스 위에 3종 상태 배너를 별도 예시로 병기했다 — 진행중(파랑, 스피너+텍스트) / 이미지 로드
  실패(에러 톤, 아이콘+텍스트) / 폐기 프레임(경고 톤, 아이콘+텍스트). 색만으로 구분하지 않고 아이콘+문구를
  항상 동반한다(DS dont_rules). 저장 상태(헤더)는 편집 중(주황 점+텍스트)/저장됨(초록 점+텍스트) 2종으로
  분리했다.
- **데이터 밀도**: 객체 목록은 그룹(사람/차량/키포인트) + 그룹당 실제 항목(수동 BBOX, AI 탐지 0.91 신뢰도,
  폴리곤)으로 채웠고, 이벤트 어노테이션 캡션·근거 후보에는 실제 문장(사고과정 3단계 포함)을 넣어 placeholder
  가 아닌 그럴듯한 데이터로 표현했다. 프레임 설명·시계열 메타도 실제 문장 예시로 채움.
- **시각 위계**: 좌측 레일(그리기 도구 → 보기 조작 → 단축키 안내)과 캔버스 상단 옵션바(프레임 이동 → 저장/
  실행취소 → 화면배율 → 표시/폐기)를 그룹 구분선(divider)·수직 스택으로 나누고, 우측 패널은 탭(객체/메타/
  이슈)으로 절차를 나눴다. 1차 액션(저장·검수제출·탐지실행)은 primary, 보조 액션은 outline/ghost/secondary로
  구분.
- **컴포넌트 디테일**: 버튼 hover(배경 톤 변화)·focus-visible(3px outline+2px offset)·disabled(opacity 0.5)를
  전 버튼 클래스에 반영. 객체 트리 행 hover는 DS 문서가 명시한 `#FFFBEB`(do_rules 예외값, `--row-hover-bg`로
  :root 선언). 슬라이더·스위치·라디오칩 등 인터랙션 상태를 시각적으로 구현.
- **접근성/공공 제약**: 본문 15~19px(design-prompt 지시대로 body-sm 이상), 헤더 CCTV명 17px, 모든 클릭 가능
  요소를 `button`/`label`로 구현(`div onclick` 없음), 인터랙티브 요소 최소 44×44px 히트영역(도구 버튼·
  아이콘 버튼·필드 44px 등), focus-visible 3px 링. 장식적 그라데이션·neumorphism·auto-play 없음(캔버스
  미디어 매트의 짙은 그라데이션은 DS가 명시한 유일 예외 — "영상 프레임을 얹는 매트").
- **AI slop 회피**: 토큰을 단순 적용하는 데 그치지 않고 좌측 레일 폭(208px)·우측 패널 폭(380px)을 이 화면
  전용으로 조정하고(밀집 작업 패널 규칙 — 8~16px 간격 허용하되 44px 히트영역 유지), 캔버스 상단 옵션바를
  와이어프레임의 좌측 열 배치에서 "캔버스 상단" 실제 의도대로 캔버스 바로 위 가로 바로 재배치했다(정보
  순서·섹션 구성은 보존, 배치만 명칭이 가리키는 실제 위치로 정교화 — 이것이 "와이어프레임에 없는 디자인
  결정"에 해당).
- **⚠️ 배치 조정 고지**: 캔버스 상단 옵션바(⑰)를 와이어프레임의 좌측 레일 하단 카드 대신 캔버스 위 가로
  바로 옮겼다. 섹션 구성(components·역할)과 정보 순서는 100% 보존했고 컴포넌트 배치 좌표만 컴포넌트명이
  가리키는 실제 의도(canvas-top)에 맞춰 조정한 것이다. implement 단계에서 이 배치를 따를지, 와이어프레임
  그대로 좌측 레일에 둘지는 재확인 권장.

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 헤더 바 | UI-055 LabelHeader | title-sm 17/600, spacing sm/md, --surface | 닫기=UI-001 Button ghost |
| 좌측 도구바 | UI-047 ToolBar | body-sm 15/400, spacing xs/sm, radius-md | 아이콘 버튼 44px 히트영역 |
| 라벨 선택 오버레이 | UI-048 LabelPickerModal | shadow-lg, radius-lg, primary-0/6(숫자 배지) | 오버레이 쇼케이스 ③ |
| 캔버스 컨테이너 | UI-046 CanvasShell | neutral-9/10(미디어 매트, DS 유일 다크 예외) | Konva Stage 자리 |
| AI 추적 액션 | Custom(AiTrackAction) → UI-001 Button primary | primary-5 | 캔버스 오버레이 우하단 |
| 캔버스 상단 옵션바 | UI-052 FrameNavigator + UI-053 SaveCommitButton + UI-054 UndoRedoToolbar | body-sm, spacing xs, --border | 저장 버튼에 미저장 건수 배지 |
| 객체 목록 | UI-049 ObjectClassTree | success/info/secondary-5(출처 점), row-hover-bg | 그룹 헤더+행, 트랙 메뉴는 오버레이 ⑪ |
| 객체 속성 패널 | UI-050 ObjectAttributePanel | label 14/600, info-5(신뢰도 바) | 인라인 펼침 |
| 이미지·라벨 표시 조절 | Custom(DisplayAdjustPanel) → 커스텀 슬라이더 | primary-5(슬라이더 thumb) | 저장 대상 아님, 보기 전용 |
| 시계열 메타 — 서술 전문 | UI-056 TimeseriesSidePanel → UI-027 Textarea | body-sm, textarea, caption(자수 카운트) | API-066 items(vlm.description) 편집·API-067 저장 대상 |
| 시계열 메타 — 일치도(읽기전용) | UI-056 TimeseriesSidePanel → Custom(AccuracyMeter) | info-5(진행바 채움), mono 14(수치) | API-066 readOnlyMeta(vlm.accuracy), 검수큐 미노출·수정 불가 |
| 시계열 메타 저장 버튼 | UI-001 Button primary | button 17/500 | API-067 PUT — 서술 전문(items)만 전송, accuracy/technicalMeta 포함 시 400 |
| 영상 기술 정보(읽기전용) | Custom(TechnicalMetaKeyValue) | caption(key), mono 14(value) | API-066 technicalMeta(video.* ffprobe) 2열 key-value, 편집 불가 |
| 프레임 설명 | ⚠️ 미정(전용 UI-NNN 없음, ui-catalog UI-002 Input/UI-027 Textarea 조합으로 매핑) | body-sm, caption(자수 카운트) | NIA image.description |
| 개인정보 메타(영상축/프레임축) | ⚠️ 미정(전용 UI-NNN 없음 — Custom FrameMetaPanel/VideoPrivacyPanel/VideoMetaPanel 은 카탈로그에 등록 안 됨) | label 14/600, chip radio(primary-0/6) | *Source(MANUAL/DERIVED) 태그 |
| 이벤트 어노테이션 | ⚠️ 미정(전용 UI-NNN 없음) | label/caption/body-sm, warn-0(검토대기 배지) | 캡션·근거 후보 반복 행 |
| 이슈 스레드 | UI-097 IssueThreadPanel | error-0/6(반려), warn-0/6(문의), n-0(댓글) | 미해결 건수 배지 |
| 로드 버전 선택 모달 | UI-066 VersionList + UI-067 DiffViewer | mono 14(shortHash), success/warn/error(diff 태그) | 오버레이 쇼케이스 ⑥ |
| 저장 확인/신고 모달 | UI-004 Modal / UI-005 ConfirmDialog + UI-057 DeidentReportButton | shadow-lg, error-5(신고 버튼) | 오버레이 쇼케이스 ⑧ |
| 키포인트 인체 가이드 | Custom(KeypointGuide/KeypointProgressGuide) | success-4(완료 관절), primary-5(현재 관절) | SVG 프리뷰 |
| AI 탐지 다이얼로그 | Custom(AiDetectTargetPicker) + Custom(PrecisionControl) | primary-0/6(선택 칩), n-2(미매핑 칩 비활성) | 미매핑 라벨은 opacity 0.45 |
| 단축키 안내 | Custom(ShortcutHelpTrigger/ShortcutHelpTable) | mono 14(kbd), caption | 좌측 레일 하단 hover 팝오버 |
| 프레임 타임라인 | UI-051 FrameFilmstrip | success-4(저장됨 테두리), error-4(이슈 테두리), primary-5(현재) | 폐기 프레임 opacity 0.45 |
| 배지(이벤트유형/상태) | UI-016 EventTypeBadge / UI-014 StatusBadge | warn/info/success/error 0·6 조합 | 색+텍스트 동시 표기 |
| 버튼 전종 | UI-001 Button | button 17/500, radius-md, shadow-sm(hover 없음) | variant: primary/secondary/outline/destructive/ghost |
| 입력/선택/텍스트영역 | UI-002 Input / UI-003 Select / UI-027 Textarea | body-sm 15/400, radius-md, --border | 44px 미만 필드는 --sm 변형(36px, 밀집 패널 허용치) |

> ⚠️ 표에 `⚠️ 미정`으로 표기한 3개 영역(프레임 설명·개인정보 메타·이벤트 어노테이션)은 `_shared/ui-catalog.md`
> 100건 중 전용 컴포넌트가 없어 범용 Input/Textarea/Select 조합 + 이 디자인이 새로 정의한 클래스
> (`lbl-radio-tri`·`lbl-candidate-row` 등)로 표현했다. **Phase 6(ui_component 보강)은 이번 배치에서
> 수행하지 않았으므로 등록 여부는 사용자 확인 후 별도 진행.**

## § 접근성 검증 (WCAG 대비 — Phase 3 D5)

동봉 `scripts/contrast_checker.py`로 실제 사용한 텍스트/배경 조합을 전수 검사했다(DS 토큰의 *기존* hex 값만
사용, 새 색 추가 없음).

| 조합 (텍스트 × 배경) | 토큰명 | 대비 | 판정 | 조치 |
|---|---|---|---|---|
| 본문 × 헤더/카드 배경 | neutral-9(#1e2124) × white | 16.18:1 | AAA | OK |
| 본문 × 페이지 배경 | neutral-9 × neutral-0(#f4f5f6) | 14.82:1 | AAA | OK |
| 보조텍스트 × white | neutral-6(#58616a) × white | 6.3:1 | AA | OK |
| 보조텍스트 × 회색 표면 | neutral-6 × neutral-0 | 5.77:1 | AA | OK (DS do_rules: 60단 이상 사용 지침 준수) |
| 보조텍스트 × 행 hover | neutral-6 × #FFFBEB(DS 지정값) | 6.08:1 | AA | OK |
| 버튼 텍스트 × primary 배경 | white × primary-5(#256ef4) | 4.55:1 | AA | OK (DS known_gaps 기재: 여유 적음 — 텍스트를 primary 표면에 추가로 얹지 않음) |
| 버튼 텍스트 × destructive 배경 | white × error-5(#de3412) | 4.56:1 | AA | OK |
| 배지 텍스트 × primary 배지배경 | primary-6(#0b50d0) × primary-0 | 6.09:1 | AA | OK |
| 배지 텍스트 × warn 배지배경 | warn-6(#8a5c00) × warn-0 | 5.29:1 | AA | OK |
| 배지 텍스트 × error 배지배경 | error-6(#bd2c0f) × error-0 | 5.31:1 | AA | OK |
| 배지 텍스트 × success 배지배경 | success-6(#267337) × success-0 | 5.26:1 | AA | OK |
| 배지 텍스트 × info 배지배경 | info-6(#096ab3) × info-0 | 5.04:1 | AA | OK |
| 저장됨 상태텍스트 × white | success-6 × white | 5.85:1 | AA | OK |
| 편집중 상태텍스트 × white | warn-5(#9e6a00) × white | 4.65:1 | AA | OK |
| focus ring(비텍스트) × white | primary-5 × white | 4.55:1 | PASS(3:1 기준) | OK |

- **전 조합 AA 이상 통과 — 재작성 필요 없음.**
- ⚠️ 참고(범위 밖 관찰, 실패 아님): 카드·입력 테두리에 쓴 `--border`(neutral-2, #cdd1d5)는 white 배경 대비
  1.54:1로 WCAG 1.4.11 비텍스트 3:1 기준에는 못 미친다. 이 스킬의 D5 게이트는 텍스트/배경 조합만 검사
  대상으로 하며(notes-template.md 명시), 테두리는 그 범위 밖이다. neutral-2는 DS 팔레트 값 그대로이고 이
  프로젝트 전 화면이 공유하는 기본 테두리 톤이라 이 화면 단독으로 진하게 바꾸지 않았다 — 필요하면 DS
  차원의 보강(팔레트 조정)이 선행돼야 한다는 점만 기록한다.

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | design-main.html | 전체 페이지(page) — 헤더·좌측도구바·캔버스·우측패널(탭 3종)·하단 타임라인 + 오버레이 쇼케이스 |

- 공유 스타일: design-main.css — 게시본(logicraft 렌더)과 바이트 동일한 로컬 미러이며 design-main.html 이 참조하는 정본이다. 구 작성본 design.css 는 이후 수정이 반영되지 않아 게시본과 어긋난 채 남아 있었으므로 삭제했다(2026-08-18).
- 스크린샷: (없음 — 브라우저 프리뷰로 확인, 게이트1 참조)

## § 역등록 기록 (Phase 5 — 완료)

- `screen_design` ITEM 신규 생성: **SD-002** (`designs_screen: SCREEN-005`, domain: DOMAIN-010, status: draft)
- `upload_design_render` 호출: render_id=`main`, surface=`page` (SCREEN-005의 `static_renders`(wireframe.html)와
  같은 surface·render_id로 맞춰 비교 뷰가 형성되도록 함)
  - html url: `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-002/main.html`
  - css url: `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-002/main.css`
  - action: `add` (신규), 결과 `current_version: 2`
- Phase 6(ui_component 보강)은 이번 배치에서 여전히 미수행 — § 컴포넌트 · 토큰 매핑의 `⚠️ 미정` 3건
  (프레임 설명·개인정보 메타·이벤트 어노테이션)은 별도 처리 대상으로 남는다.
