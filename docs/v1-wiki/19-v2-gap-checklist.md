# 19. v2 갭 / 마이그레이션 체크리스트

> **목적**: v1에는 있고 v2에는 없는 기능을 점검하여, **무엇을 v2에 추가할지 / 추가하지 않을지**를 의사결정하기 위한 체크리스트.
> **전제**: v1 전용 기능을 전부 추가해야 하는 것은 **아니다**. 상당수는 v2에서 의도적으로 범위 외이거나 다른 방식으로 이미 대체됐다. 아래 분류로 구분한다.
> 근거: [18 v1↔v2 비교](18-v1-v2-comparison.md) · **2026-08-19 코드 실측 재판정**(전 후보 재확인, 이하 각 항목 참조) · 루트 [`CLAUDE.md`](../../CLAUDE.md).
>
> ⚠ **구 근거 표기 폐기(2026-08-19)** — *"v2 코드 조사(2026-06)"* 는 2개월 이상 낡아 후보 다수가 그 사이 실제로 구현됐다. 이번 라운드에서 §19.1 전 후보를 코드로 재확인해 완료/갭/대체 3분류로 재정리했다(아래 각 항목의 근거 참조). **`docs/design/` 인용은 2026-08-15 동결로 정본이 아니다** — 이 문서가 옛 버전에서 인용하던 `docs/design/D2-*` 등의 경로는 근거로 재사용하지 않는다(범위 밖 — 담당 밖 문서이므로 이 라운드에서 그 인용 자체를 지우지는 않되, 새로 쓰는 근거는 전부 코드 심볼로만 단다).

## 분류 범례

| 표시 | 의미 | 조치 |
|:---:|------|------|
| 🟢 **대체됨** | v2가 다른 방식으로 이미 같은 목적 달성 | 추가 불필요 (확인만) |
| ⛔ **범위 외** | v2 설계상 외부 시스템 책임 | 추가 안 함 (의사결정 완료) |
| 🟡 **검토 후보** | v2에 없는 실제 갭. 도메인 요구에 따라 추가 검토 | **아래 체크리스트 대상** |

---

## 19.1 🟡 검토/추가 후보 (실제 갭 — 의사결정 필요)

> 추가 여부는 **요구사항(R1, `docs/design/`) 확정에 따름**. 아래는 "v1엔 있었으나 v2에 없음 → 필요하면 추가" 후보다. 각 항목 추가 전 R1 요구사항 매핑부터 확인할 것.
> **갱신(2026-07-14)**: [21 사용자 화면 가이드](21-user-screen-guide.md)의 화면 단위 세부(SC-003~018) + v2 BE/FE 코드 재조사로 신규 갭 다수 확인. 아래를 **P1~P3 우선순위 + BE/FE 실구현 상태**로 재정리한다. **제외 조건**(사용자 결정 2026-07-14): ⛔ 영상/이미지 업로드·AI 생성(관제/포털 담당) · ⛔ 프로젝트 관리(→ 영상관리로 대체) — 갭 대상 아님(§19.3).

### 🔴 P1 — 라벨링 생산성·정확도 직접 영향

> ⚠ **2026-08-19 재판정 — 옛 P1 후보 5건 중 3건이 완전 구현, 1건이 부분 구현으로 확인됐다.** 각 항목의 이동 사유·근거는 아래 참조. `docs/v1-wiki/19` 담당 재확인이며 코드로 직접 열어 확인했다(추측 아님).

- [x] ✅ **이미지 조절 패널** — **구현 완료(재확인 2026-08-19)** — SC-005. 밝기/대비/라벨 투명도/작업 투명도 슬라이더 + 초기화 버튼 구현. konva 이미지 필터(밝기/대비) + 레이어 opacity(투명도), 세션 전용(영속 안 함). FE: `frontend/src/features/label/components/ImageAdjustPanel.tsx`(컴포넌트 본체, "SCR-LABEL-001 이미지 조절 패널" 헤더 주석) · `frontend/src/features/label/canvas/CanvasShell.tsx`(imageAdjust.labelOpacity/activeOpacity 적용) · `frontend/src/pages/label/LabelingPage.tsx`(`<ImageAdjustPanel />` 마운트) · store `useLabelStore(imageAdjust/setImageAdjust/resetImageAdjust)`. → [완료 항목](#-완료-이전-후보-중-구현됨)으로 이동.
- [x] ✅ **라벨 복사/붙여넣기 (Ctrl+C/V·전체복사·좌표 +10 offset)** — **구현 완료(재확인 2026-08-19)** — SC-004. Phase 4 로 BE 저장 없이 **클라이언트 클립보드**로 구현됨. 좌표 +10 offset 도 확인됨 — 붙여넣을 위치에 **완전동일 좌표의 라벨이 이미 있으면**(같은 프레임에 다시 붙여넣기 등) `PASTE_OFFSET=10`px 만큼 이동 후 이미지 경계로 clamp(무조건 +10 이 아니라 중복 좌표일 때만 오프셋 — v1 원문에 조건 명시는 없어 이 조건부 방식이 v1 그대로인지는 별도 확인 필요). 단축키: `Ctrl+C`(선택 복사)·`Ctrl+Shift+C`(프레임 전체 복사)·`Ctrl+V`/`Ctrl+Shift+V`(붙여넣기) — 1차 원문 §4.3.1 표와 **키 조합까지 정확히 일치**. FE: `frontend/src/features/label/hooks/labelingKeymap.ts`(`SHORTCUT_KEYMAP` 의 `clipboard.copy`/`clipboard.copyAll`/`clipboard.paste`) · `frontend/src/stores/useLabelStore.ts`(`PASTE_OFFSET` 상수 · `copyLabels`/`pasteLabels` 액션 · `clipboard` 필드) · 테스트 `useLabelStore.clipboard.test.ts`·`labelingKeymap.clipboard.test.ts`. → [완료 항목](#-완료-이전-후보-중-구현됨)으로 이동.
- [x] ✅ **트랙 번호 변경/머지 (merge/split)** — **구현 완료(재확인 2026-08-19)** — SC-004. BE: `POST /v1/videos/{rawSn}/tracks/merge`(`TrackMergeController.mergeTracks` → `TrackMergeService`, "Phase 4 — 트랙 병합 API. 두 트랙(fromTrackId→toTrackId)을 하나로 합치고 재보간") · `POST /v1/videos/{rawSn}/tracks/{trackId}/split`(`TrackEditController.splitTrack` → `TrackEditService`, "Phase 3(트랙 관리 확장)") · `DELETE /v1/videos/{rawSn}/tracks/{trackId}`(`TrackEditController.deleteTrack`, R4). FE: `frontend/src/features/label/components/ObjectClassTree.tsx`(연필 아이콘 "트랙 ID 변경"=rename(=미사용 번호로 병합), 삭제·분할 버튼 — `onRenameTrack`/`onDeleteTrack`/`onSplitTrack` prop). 포털 채널은 트랙 데이터모델 부재로 미제공(의도, ADR-013). → [완료 항목](#-완료-이전-후보-중-구현됨)으로 이동.
- [ ] 🔶 **프레임 테두리 4색 체계** — **부분 구현(재확인 2026-08-19)** — 전 화면 공통(저장=연두/반려=주황/확인요청=빨강/현재=강조). ⚠ **구 서술 폐기** — *"FE는 현재+이슈 플래그 2색만(`DarkFrameStrip.tsx`)"* 은 낡았다. `DarkFrameStrip.tsx` 파일 자체가 더는 존재하지 않고(→ `FrameFilmstrip.tsx` 로 대체) 4색 판정 엔진 자체는 구현돼 있다 — `frontend/src/features/label/components/frameStatus.ts`(`FRAME_STATUS`={CURRENT,INQUIRY,REJECTION,SAVED,NONE} + `FRAME_STATUS_BORDER`={주황=REJECTION,빨강=INQUIRY,연두=SAVED,강조=CURRENT} + `resolveFrameStatus` 우선순위 함수, 헤더 주석 "프레임 테두리 4색 상태 — 관리자 매뉴얼 Rev.1.1"). 소비처 2곳(`FrameFilmstrip.tsx`·검수 화면 `frontend/src/features/review/components/FrameTimeline.tsx`) 모두 **실제로는 3색만 켜진다** — `hasRejection` 이 항상 `false` 로 고정 전달된다(`FrameTimeline.tsx` 주석: "v2 반려는 영상 단위라 프레임 매핑 불가 → 주황 미대상"). 이유는 구조적: v2 검수 반려(`REJECTION`)가 **영상 단위**(`srcSn=null`)라 개별 프레임에 매핑할 데이터가 없다 — UI 버그가 아니라 v2 데이터모델(다단계 검수 폐지, REVIEWER 단일 승인/반려)의 결과다. **잔여 갭**: 반려색(주황)이 실제 라벨링·검수 화면에서 도달 불가 — 프레임 단위 반려 데이터 자체가 v2 에 없으므로 되살리려면 검수 반려를 프레임 단위로 확장하는 별도 설계가 선행돼야 한다.
- [ ] 🔶 **라벨링 단축키 정합** — **대부분 정합 완료(재확인 2026-08-19)**, 잔여 2행 갭. 1차 원문 §4.3.1(`02-관리자매뉴얼.txt`, 관리자매뉴얼 Rev.1.0 표지 + 내부 개정이력 "1.1 2025.11.12 단축키 안내 추가")의 확정 단축키표를 v2 키맵과 대조:
  - ✅ **일치**: `W/A/S/D`(첫/이전/끝/다음 프레임) · `Ctrl+S`(저장) · `Esc`(도구 닫기/생성취소) · `Ctrl+Z`(실행취소) · `Delete`/`R`(객체 삭제) · `Ctrl+C`/`Ctrl+V`/`Ctrl+Shift+C`/`Ctrl+Shift+V`(복사/붙여넣기, 위 항목과 동일 근거) · `T`(라벨 표시/숨김) · `F`(폴리곤 점 추가) · `Q`(폴리곤 자동완료). 근거: `frontend/src/features/label/hooks/labelingKeymap.ts`(`SHORTCUT_KEYMAP`, 헤더 주석 "관리자 매뉴얼 Rev.1.1 확정셋 기준"). 도구 키 충돌은 v2 가 **의도적으로 재배치**했다 — 도구 SELECT 는 WASD 와 겹쳐 `S`→`Esc` 로, SAM 추적(TRACK)은 평문 `T`(라벨 표시/숨김)와 겹쳐 `T`→`Shift+T` 로 이동(같은 파일 주석).
  - 🔶 **미확인/갭**: 1차 §4.3.1 의 `Shift+클릭`("(폴리곤) 점 수정" — 폴리곤 선택 후 Shift 유지하며 시작점-추가할 점들-끝점 순서로 클릭해 형태 재정의)·`Ctrl+클릭`("(폴리곤) 점 삭제" — 삭제할 점을 Ctrl+클릭) 2건은 v2 캔버스에서 **확인되지 않았다**. v2 는 정점 **드래그 이동**만 구현돼 있다(`frontend/src/features/label/canvas/layers/LabelsLayer.tsx`(`commitVertex`) · `frontend/src/features/label/canvas/utils/polygonEdit.ts`(`moveVertexToCanvas`)) — 기존 폴리곤에 점을 끼워넣거나(insert) 특정 점만 삭제(vertex-level delete)하는 조작은 캔버스 코드에서 `shiftKey`/`ctrlKey` 참조가 0건이라 미구현으로 판단(확인 명령: `grep -rn "shiftKey\|ctrlKey" frontend/src/features/label/canvas` → 매치 없음). **정합 결정 필요**(차이표: [21 §21.9](21-user-screen-guide.md#219-크로스컷-규칙-전-화면-공통)).

### 🟡 P2 — 보조/편의

> ⚠ **2026-08-19 재판정 — 옛 P2 후보 7건 중 4건이 완전 구현, 1건이 부분 구현(POLYGON만)으로 확인됐다.** 통계 CSV·커스텀 메타 라디오는 재확인 결과에서도 여전히 갭이다.

- [x] ✅ **회전 / Fit(초기화) / 영역 확대 도구** — **구현 완료(재확인 2026-08-19)** — 서포트 도구. 좌·우 90° 회전 + 화면 맞춤(Fit) + 드래그 영역 확대 전부 구현. FE: `frontend/src/features/label/canvas/CanvasShell.tsx`(`rotateVectorClockwise` 회전 벡터 함수 · `zoomToArea` 드래그 영역 확대 · `zoomToPoint` 휠 줌) · `frontend/src/stores/useLabelStore.ts`(`resetView` — "R3 수동 Fit: zoom=1·pan=0 으로 화면 맞춤 복귀") · `frontend/src/features/label/components/ToolBar.tsx`(툴바 순서 주석 "좌·우 90° 회전 / 화면 맞춤 / 영역 확대 / 그리드 표시", 버튼 라벨 "화면 맞춤"). 테스트: `CanvasShellRotationGrid.test.tsx`·`CanvasShellZoomArea.test.tsx`·`CanvasShellWheelZoom.test.tsx`. → [완료 항목](#-완료-이전-후보-중-구현됨)으로 이동.
- [x] ✅ **캔버스 그리드 오버레이** — **구현 완료(재확인 2026-08-19)** — 라벨링 도구. 툴바 토글 버튼 + 캔버스 격자 렌더링. FE: `frontend/src/features/label/components/ToolBar.tsx`(`showGrid`/`onToggleGrid` prop, `Grid3x3` 아이콘, 버튼 라벨 "그리드 표시") · `frontend/src/features/label/canvas/CanvasShell.tsx`(`gridLines` useMemo + `Layer name="grid-layer"` 렌더). → [완료 항목](#-완료-이전-후보-중-구현됨)으로 이동.
- [x] ✅ **객체 잠금/숨김 아이콘** — **구현 완료(재확인 2026-08-19)** — 객체/라벨 탭. 표시/숨김(Eye/EyeOff)·잠금(Lock/Unlock) 토글 아이콘이 항목마다 노출. FE: `frontend/src/features/label/components/ObjectClassTree.tsx`("Phase 3 R6 — 객체 개별 표시/숨김 + 잠금", `hiddenLabelIds`/`lockedLabelIds`/`toggleLabelLock`, `aria-label`="{name} #{n} 표시/숨김"·"잠금/잠금 해제"). 잠긴 객체는 트랙 ID 변경 진입도 함께 차단. → [완료 항목](#-완료-이전-후보-중-구현됨)으로 이동.
- [x] ✅ **YOLO 오토라벨 수동 트리거 버튼** — **구현 완료(재확인 2026-08-19)** — SC-004. ⚠ **구 서술 폐기** — *"라벨링 화면 툴바엔 SAM만 노출(YOLO 수동 버튼은 dev 페이지만)"* 은 낡았다. 프로덕션 라벨링 화면 툴바에 "YOLO 오토라벨 수동 트리거"가 실재한다(사용자 노출 문구는 모델명 대신 "박스/폴리곤" 등으로 순화— MED #13 용어 정책). FE: `frontend/src/features/label/components/ToolBar.tsx`("Phase 3 — YOLO 오토라벨 수동 트리거 핸들러. 미지정 시 버튼 미노출" · 로딩/중복클릭 방지 상태 포함, 포털은 ADR-013 으로 숨김) · `frontend/src/features/label/components/AiToolModal.tsx`(형태·라벨·일반/트랙 통합 팝업, §19.3 "요구사항 외 추가 결정(2026-07-20)" 참조). → [완료 항목](#-완료-이전-후보-중-구현됨)으로 이동.
- [ ] **커스텀 메타 라디오형 입력** — **여전히 갭(재확인 2026-08-19)**. SC-006/011. 라벨 **속성**(per-object attribute) 축은 SELECT/CHECKBOX/RADIO/NUMBER/TEXT 5종 입력타입이 이미 구현돼 있다(`backend/src/main/java/kr/co/cudo/authoring/label/entity/LsLabelAttr.java`, `INPUT_RADIO` 상수 — 아래 "완료" 절 "라벨 클래스(마스터)·속성 정의 관리 화면" 항목과 동일 기능). 그러나 이 항목이 가리키는 **프레임 메타(촬영환경/개인정보) 축**은 여전히 **관리자가 정의하는 사용자정의 라디오형이 없고, 고정 enum(날씨/시간대/계절) 라디오만** 존재한다 — 확인: `frontend/src/features/label/components/EnvironmentMetaPanel.tsx`(`role="radio"`/`role="radiogroup"` 이지만 값 집합이 `WEATHER`/`SEASON` 등 코드로 고정, 헤더 주석 "자유입력은 select/토글로 차단") · `frontend/src/features/label/components/FrameDescriptionPanel.tsx`(자유서술 textarea 1개뿐, 라디오 없음) · BE `MetaController`(외부 VLM 시계열 메타 검토·수정 전용, 사용자정의 필드 스키마 없음). **두 축(라벨 속성 vs 프레임 메타)을 혼동하지 말 것** — 전자는 완료, 후자만 갭.
- [ ] 🔶 **트랙 보간 POLYGON/POLYLINE** — **부분 구현(재확인 2026-08-19) — POLYGON 완료, POLYLINE 여전히 갭.** ⚠ **구 서술 폐기** — *"BE BBOX simple 보간만. `TrackInterpolator.java:38` '후속 Phase' 미구현"* 은 낡았다(그 문구는 **더는 파이프라인에서 쓰이지 않는 구 클래스** `backend/src/main/java/kr/co/cudo/authoring/common/util/TrackInterpolator.java` 에 남아 있으나 main 코드에서 이 클래스를 호출하는 곳이 0건 — 죽은 코드). **실제 파이프라인이 쓰는 클래스는 별개**: `backend/src/main/java/kr/co/cudo/authoring/batch/interpolation/TrackInterpolator.java`(`interpolatePolyshape` 메서드 — 폴리곤/폴리라인 정점 대응은 `PolyshapeMatcher.match` 로 계산) + `backend/src/main/java/kr/co/cudo/authoring/batch/step/TrackInterpolationStep.java`(`LBL_TYPE_CD` 로 BBOX/POLYGON 라우팅, 주석 "BBOX 는 선형 보간, POLYGON 은 polyshape 보간. **(POLYLINE 은 현재 DB 코드값 미도입.)**"). 즉 **POLYGON 트랙 보간은 실제 배치 파이프라인에서 동작**하고(SFR-08-01 요구 충족), **POLYLINE 만 여전히 갭**이다 — 그 사유는 코드 미구현이 아니라 **POLYLINE 이라는 라벨 형태 자체가 DB 코드값(`LBL_TYPE_CD`)에 아직 없어서**(도형 종류 자체가 도입 전이므로 그 형태의 보간도 대상이 없음).
- [x] **통계 CSV 리포트 실데이터** — **해소(2026-08-31)**. `GET /v1/stats/report` 가 `period`(WEEK/MONTH/QUARTER/YEAR)에 따라 실집계를 담은 CSV 를 반환한다 — 섹션 블록 5개(누적 학습데이터·처리현황·일별 작업량·이벤트 유형 분포·작업자별 현황). 수치는 전체 구축 현황과 같은 집계 하나(`StatsService.getOverallSummary`)에서 조달하고 `period` 는 일별 작업량 블록의 창(7/30/90/365일)만 정한다. 응답 계약(CSV·BOM·파일명)은 무변경. 근거: `StatsController(report)` · `OverallStatReportCsvWriter` · `API-058`(v4). ⚠ 구 서술 *"헤더 한 줄(`month,labeled,reviewed,approvalRate`)만 반환하는 placeholder — 집계 로직 미구현"* 은 폐기.

### 🟢 P3 — 낮은 우선순위

> ⚠ **2026-08-19 재판정 — 옛 P3 후보 3건 중 1건(프레임 폐기)이 완전 구현으로 확인됐다.**

- [ ] **이미지 자동 분류** — **여전히 갭(재확인 2026-08-19)**. 메타(이벤트/시간/날씨/계절) 기반 프레임 자동 카테고리화. v2는 여전히 `backend/src/main/java/kr/co/cudo/authoring/dataset/util/TimeOfDaySeasonDeriver.java`(주야간/계절 파생)만, 메타 필터로 부분 대체 가능. "자동 분류" 명칭의 신규 기능은 확인되지 않음(확인 명령: `grep -arli "자동.*분류\|카테고리화" backend/src/main/java frontend/src` → `TimeOfDaySeasonDeriver` 관련 3건뿐, 신규 없음).
- [ ] **연습장 (라벨링 연습 환경)** — **여전히 갭(재확인 2026-08-19)**. SC-016. BE·FE 전무 그대로(확인 명령: `grep -arli "연습장\|practice" backend/src/main/java frontend/src` → 0건). 신규 작업자 온보딩용([13](13-board-practice.md)).
- [x] ✅ **프레임 폐기(discard) 워크플로** — **구현 완료(재확인 2026-08-19)**. ⚠ **구 서술 폐기** — *"v2는 검수 반려로 수렴(부분 대체) — 프레임 단위 폐기 별도 미구현"* 은 낡았다. 프레임 단위 폐기(`DSCD_YN`)가 별도 구현돼 있고, 검수 반려와는 **다른 축**으로 명확히 분리돼 있다(CLAUDE.md "폐기는 라벨·이미지를 그대로 두는 논리 폐기"). 근거: `backend/src/main/java/kr/co/cudo/authoring/label/service/LabelService.java`(`requireDiscardAllowed` — 승인 이력 있는 영상은 400 차단) → `backend/src/main/java/kr/co/cudo/authoring/label/service/FrameDiscardApplier.java`(`apply` — 단일 적용 지점) · `PUT /v1/videos/{rawSn}/labels`(`VideoLabelController` — "영상 전체 라벨 + 프레임 폐기 상태를 한 트랜잭션으로 확정"). FE: `frontend/src/features/label/components/FrameFilmstrip.tsx`(`discardedSrcSns` prop, 폐기 프레임은 흐림+회색조+"폐기" 표식, `aria-label`="프레임 {n} (폐기)"). → [완료 항목](#-완료-이전-후보-중-구현됨)으로 이동.

### ✅ 완료 (이전 후보 중 구현됨)

- [x] **라벨 클래스(마스터)·속성 정의 관리 화면** — ✅ **구현 완료 (2026-07-20)** — 1차 관리자매뉴얼 §4.1.3(라벨 생성)·§4.1.4(속성 설정) 요구. 그간 BE(`LabelMasterController`·`LabelAttrController`, `/v1/manage/labels/**`, REVIEWER)만 완비되고 FE 화면이 없어 DB 직접 조작으로만 가능하던 갭. 신규 화면 **SC-035 `/manage/labels`**(REVIEWER) — 라벨 마스터 CRUD(name·형태 BBOX/POLYGON/POINT/SKELETON·색상·정렬순) + 라벨별 속성 정의 CRUD(name·inputType SELECT/CHECKBOX/RADIO/NUMBER/TEXT·valuesJson·기본값·수정가능·정렬순). FE: `pages/manage/LabelMasterManagePage.tsx`·`components/LabelMasterFormModal.tsx`·`features/label/components/{LabelAttrDefPanel,LabelAttrFormModal}.tsx`·`api/{labelMaster,labelAttr}.ts`·`hooks/{useLabelMasterMutations,useLabelAttrs}.ts`, 라우트·LNB. 마스터 변경은 `LABEL_MASTER_KEYS` 무효화로 라벨링 캔버스 반영. **범위 제외**: 스켈레톤 포인트 정의 편집기(COCO-17 고정상수). → [v2-wiki 04](../v2-wiki/04-screens-ia.md) SC-035.
  > ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"신규 화면 SC-036 `/manage/labels`"* 는 화면 ID 가 틀렸다. `frontend/src/pages/manage/LabelMasterManagePage.tsx` 는 `SCREEN-035` 를 참조하고(`grep -rhoa "SCREEN-[0-9]\{3\}" frontend/src/pages/manage/LabelMasterManagePage.tsx` → `SCREEN-035`), `/manage/labels` 라우트도 `router/index.tsx` 에서 그 컴포넌트로 배선돼 있다. `SC-036` 은 실제로는 공지 작성(`/notice/new`) 화면이다(2026-08-19 전수 화면ID 정합 결과, [18](18-v1-v2-comparison.md) 참조). 또한 인용하던 `docs/design/D2-사용자인터페이스설계서.md` 링크는 2026-08-15 동결로 정본이 아니므로 제거한다.
- [x] **키포인트/스켈레톤 라벨링 (17-keypoint COCO 포즈)** — ✅ **구현 완료 (2026-07-14, 4-Phase)** — BE: `common/util/{KeypointSerializer,KeypointPoint,KeypointSkeleton}.java`(삼중값 17×[x,y,v]·COCO-17 상수 17이름·19엣지), `LsDataLbl` SKELETON 타입, `LabelService` validate/serialize, `VersionService` 스냅샷/롤백/diff, `LabelMaster` SKELETON regex, `V_COMPLETED_LABEL` pass-through(IT). FE: konva 17점 배치·개별 드래그·스켈레톤 렌더·가시성(`canvas/layers/{LabelsLayer,OverlayLayer}.tsx`·`utils/keypointHelpers.ts`), 툴바 버튼·단축키(K)·serialize/undo 딥클론(`useLabelStore.ts`), `ObjectAttributePanel`. 커밋 `dc31e82`→`23a8cae`→`8b10cd0`→`4fa9e03`, 전 Phase QA GREEN. **범위 제외(후속)**: 프레임 간 보간/추적(Phase 6)·COCO-pose JSON 파일 조립(외부)·YOLO-pose 자동추정·포털(ADR-013). 설계: auto-memory `keypoint-labeling-design`.
- [x] **검수자 ↔ 작업자 이슈 소통 채널** — ✅ **구현 완료 (2026-06-05)** — `LS_DATA_ISSUE` 확장(INQUIRY·OPEN/ANSWERED/RESOLVED)+`LS_ISSUE_COMMENT`(V57), `/v1/videos/{rawSn}/issues`·`/v1/issues/{issueSn}/*` API, 이슈 스레드 탭(`IssueThreadPanel.tsx`). 상세: [v2-wiki 21](../v2-wiki/21-issue-channel.md). ※ 관리자 확인요청은 INQUIRY 타입으로 커버.
- [x] **게시판 (공지/가이드라인 배포)** — ✅ **구현 완료 (2026-06-05)** — `LS_NOTICE`/`LS_NOTICE_ATTACH`(V56), `/v1/notices*` API, 목록/상세/편집 화면. 상세: [v2-wiki 20](../v2-wiki/20-notice-board.md).
- [x] **이미지 조절 패널** — ✅ **구현 완료(재확인 2026-08-19)** — 밝기/대비/라벨 투명도/작업 투명도 슬라이더 + 초기화. 근거는 위 P1 재판정 항목 참조. FE: `ImageAdjustPanel.tsx`·`CanvasShell.tsx`·`LabelingPage.tsx`·`useLabelStore.ts(imageAdjust)`.
- [x] **라벨 복사/붙여넣기 (Ctrl+C/V·전체복사)** — ✅ **구현 완료(재확인 2026-08-19)** — 클라이언트 클립보드 방식(BE 저장 없음). 근거는 위 P1 재판정 항목 참조. FE: `labelingKeymap.ts(SHORTCUT_KEYMAP)`·`useLabelStore.ts(clipboard)`.
- [x] **트랙 번호 변경/머지 (merge/split)** — ✅ **구현 완료(재확인 2026-08-19)** — 근거는 위 P1 재판정 항목 참조. BE: `TrackMergeController`·`TrackEditController`·`TrackMergeService`·`TrackEditService`. FE: `ObjectClassTree.tsx`.
- [x] **회전 / Fit(초기화) / 영역 확대 도구** — ✅ **구현 완료(재확인 2026-08-19)** — 근거는 위 P2 재판정 항목 참조. FE: `CanvasShell.tsx(rotateVectorClockwise/zoomToArea/zoomToPoint)`·`useLabelStore.ts(resetView)`·`ToolBar.tsx`.
- [x] **캔버스 그리드 오버레이** — ✅ **구현 완료(재확인 2026-08-19)** — 근거는 위 P2 재판정 항목 참조. FE: `ToolBar.tsx(showGrid/onToggleGrid)`·`CanvasShell.tsx(gridLines)`.
- [x] **객체 잠금/숨김 아이콘** — ✅ **구현 완료(재확인 2026-08-19)** — 근거는 위 P2 재판정 항목 참조. FE: `ObjectClassTree.tsx(hiddenLabelIds/lockedLabelIds/toggleLabelLock)`.
- [x] **YOLO 오토라벨 수동 트리거 버튼** — ✅ **구현 완료(재확인 2026-08-19)** — 근거는 위 P2 재판정 항목 참조. FE: `ToolBar.tsx`·`AiToolModal.tsx`(프로덕션 라벨링 화면 툴바, dev 전용 아님).
- [x] **프레임 폐기(discard) 워크플로** — ✅ **구현 완료(재확인 2026-08-19)** — 근거는 위 P3 재판정 항목 참조. BE: `LabelService.requireDiscardAllowed`→`FrameDiscardApplier.apply`(DSCD_YN, 승인 이력 영상은 400 차단). FE: `FrameFilmstrip.tsx(discardedSrcSns)`.
- [x] **트랙 보간 POLYGON**(BBOX 외) — ✅ **구현 완료(재확인 2026-08-19), POLYLINE 은 여전히 갭** — 위 P2 재판정 항목 참조. BE: `batch/interpolation/TrackInterpolator.interpolatePolyshape` + `PolyshapeMatcher.match` + `TrackInterpolationStep`(LBL_TYPE_CD 라우팅).

> **요약(2026-08-19 갱신)**: §19.1 옛 후보 15건 중 **10건이 구현 완료**(이미지 조절 패널·복사붙여넣기·트랙 머지/분할·회전·Fit·영역확대·그리드·잠금숨김·YOLO 수동트리거·프레임 폐기), **2건이 부분 구현**(프레임 4색 체계 — 3/4 색만 실제 도달, 반려색은 v2 반려가 영상단위라 구조적으로 불가 / 트랙 보간 — POLYGON 완료·POLYLINE 은 DB 코드값 자체 미도입), **1건이 정합 대부분 완료·잔여 2행만 갭**(라벨링 단축키 — 폴리곤 점 단위 Shift/Ctrl+클릭 편집만 미확인), **잔여 순수 갭은 4건**(커스텀 메타 라디오형[프레임 축]·통계 CSV 리포트·이미지 자동 분류·연습장). 이전 라운드(2026-06/2026-07-14)에 완료 처리된 항목(라벨 마스터·키포인트·이슈채널·게시판)은 그대로 유지. 그 외 v1 기능은 §19.2·§19.3처럼 **이미 대체됐거나 범위 외**라 추가 대상이 아니다.

---

## 19.2 🟢 이미 대체됨 (추가 불필요)

| v1 기능 | v2 대체 방식 | 확인 |
|---------|-------------|:---:|
| 프로젝트 단위 관리 (생성 5단계·완료/취소·통계) | **영상 1건 단위**(`RAW_SN`) + 작업 배정 + 통계 화면(SC-020/021) | ✅ |
| GPKI 로그인 | 관제/포털 **JWT 인계**(`JwtAuthenticationFilter`) | ✅ |
| 영상/이미지 관리 + 사전 배정 | 영상 목록/상세(SC-008/009) + REVIEWER→WORKER 작업 배정(`LS_TASK_ALTMNT`) | ✅ |
| 프로젝트 배정 시 프레임 분할(초당/분당/시간당) | **마킹 위치 기반 추출** + FFmpeg(원본+비식별 2벌) | ✅ |
| 증강 5종(밝게/어둡게/좌우반전) 내장 | **외부 증강 3종**(WINTER/NIGHT/RAIN, 이미지-to-이미지·비디오 원본 복사) + **저작도구 내부 해상도 변경 파생**(RESOLUTION, SFR-06-03 — 2026-07-21부터 증강과 동일하게 파생영상 생성·업스케일 허용·좌표 배율 재계산) + 검수(`LS_DATA_AUG_RVW`, 해상도 변경 파생영상은 일반 검수 파이프라인) | ✅ |
| 다단계 검수 (1차 → 2차) | **REVIEWER 단일 승인**으로 의도적 변경 (승인=작업 완료→관제 통지) | ✅[폐기 전제·정정 예정] |
| 양방향 송수신 인터페이스(II-001~007) | **단방향 outbound 통지**(`TASK_COMPLETED/MODIFIED`) + inbound 조회 API | ✅ |
| 이력 누적(`_HSTRY`) 버전관리 | `LS_LABEL_VERSION` 스냅샷 + `LS_DATA_LBL_HSTRY` (diff/rollback) | ✅ |
| 통계 엑셀 다운로드 | CSV 리포트(`SC-021`) | ✅[CSV 실데이터는 P2 갭 — 위 §19.1 참조] |

> ⚠ **구 서술 폐기(2026-08-19 원문 재실측)** — *"다단계 검수 (1차 → 2차)"* 행의 v1 전제 자체가 틀렸다. `02-관리자매뉴얼.txt` 원문 재확인 결과 1차의 검수 단계는 **고정 "1차→2차"가 아니라 프로젝트별 설정값**이다 — 최소 `가공 1단계 → 1차검수`, 최대 `가공 2단계 × 각 검수`(즉 검수 단계 수 자체가 프로젝트마다 다르게 설정 가능)이며, 그와 별개로 **관리자에게 최종 단계 승인/반려 게이트**가 존재한다(1차 원문 근거: CROSSAXIS 실측, 원문 §4~5 프로젝트관리·검수 절). v2 의 "REVIEWER 단일 승인" 은 이 **가변 다단계 설정을 없앤 단순화**이지, "1차→2차라는 고정 2단계를 단일 승인으로 바꾼 것"이 아니다 — 대체 방향 자체는 맞지만(둘 다 "여러 단계 검수 → 단일 REVIEWER 승인"으로 단순화됐다는 결론은 유지) v1 쪽 서술을 이 문서 그대로 재인용하지 말 것. 정정 담당은 [18 v1↔v2 비교](18-v1-v2-comparison.md)(다른 담당 편집 중이라 이 라운드에서는 본 문서에만 각주로 정정을 남긴다).
>
> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 위 표의 *"`KLID-AT-SC-021`"*은 낡은 식별자다. 코드의 1차 식별자는 `SCREEN-021`(축약 `SC-021`) — 근거는 `04-screens-ia.md`(§4.1) · `reports/wiki-align-20260819/facts/F3-frontend-screens.md`.
>
> ⚠ **CSV 리포트 각주(2026-08-19)** — "통계 엑셀 다운로드 → CSV 리포트" 행은 **엔드포인트 존재**(`GET /v1/stats/report`)만 확인된 것이고 **실데이터 여부는 별개 축**이다. 그 엔드포인트는 위 §19.1 P2 재판정에서 재확인한 대로 **여전히 placeholder**(period 무관 헤더 한 줄)다 — "대체됨(✅)"은 "포맷이 CSV로 바뀌었다"는 사실만 가리키고 "데이터가 채워진다"를 보장하지 않는다.
>
> 이 항목들은 "없어진" 게 아니라 **v2 방식으로 바뀐 것**이다. 추가하지 말 것.

---

## 19.3 ⛔ 범위 외 (추가 안 함 — 외부 시스템 책임)

> 외부 시스템 연동·범위 상세는 [20 외부 시스템](20-external-systems.md) 참고.

| v1 기능 | v2 결정 근거 |
|---------|-------------|
| 생성형 AI 화면 (Text/Image→Image/Video 내장) | 생성형 AI 본체는 외부 시스템 책임. v2는 **외부 증강 결과 검수**(SCR-AUG-002)만 ([CLAUDE.md](../../CLAUDE.md)) |
| 데이터마트 **등록 / 검색 / 다운로드**(마트 구축·마트 UI) | 데이터마트 구축·검색·다운로드는 외부 제공 시스템 책임. v2는 `V_COMPLETED_*` View 노출까지만 |
| ~~학습데이터셋 Export~~ [폐기] | ⚠ 아래 각주 참조 — **이 행 자체가 오류였다. 범위 외가 아니라 저작도구 범위 안이다.** |
| VLM 모델 본체 (학습·프롬프트) | 외부 VLM 서비스. v2는 **호출 연동 + 결과 검토**(SC-015)만 |
| 영상 합성 모델 본체 | 외부 시스템. v2는 합성 영상 수신·라벨링·검수만 |
| 업로더 역할(내부) | 내부 1차 적재는 관제 학습용 설정 기반 — 사용자 업로드 역할 없음 |

> ⚠ **구 서술 폐기(2026-08-19, 루트 CLAUDE.md 구속 정정 반영)** — *"학습데이터셋 Export | 외부 시스템 책임"* 행은 **사실과 다르다.** export(NIA JSON) 산출은 **저작도구 범위 안**이며, 검수 승인 시 `v{n+1}` 로 전량 재생성 → 관제 통지(`TASK_COMPLETED`/`TASK_MODIFIED`)까지가 저작도구 책임이다. 근거 결정이 뒤집혔다 — 구 서술의 근거였던 `ADR-005` 는 `superseded` 이고 **`ADR-020`**(검수 승인 학습데이터 export 산출을 저작도구 범위로 포함)이 대체했다. 담당 축은 **검수 승인 경로**(`dataset/export/*`, `LS_DATASET_EXPORT`)이며 데이터 증강 도메인과는 무관하다. **범위 밖인 것은 export 산출물이 이후 적재되는 "데이터마트 구축·검색·다운로드"뿐**이며(위 행), export JSON 산출 자체를 범위 외로 다시 적지 말 것. 구현 근거: `backend/src/main/java/kr/co/cudo/authoring/dataset/export/*`(36파일, `DatasetExportService`·`DatasetExportTxService` 등), `LS_DATASET_EXPORT` 테이블, `V_COMPLETED_VIDEO.OUTPUT_PATH_NM`(산출 폴더 경로 컬럼).
>
> 이 항목들은 **의사결정이 끝난 범위 제외**다. v1에 있다고 v2에 추가하면 설계 위반이 된다.

> **요구사항 외 추가 결정(2026-07-17) — 포털 사용자 업로드 (ADR-013 예외)**: 포털 사용자(PORTAL_USER)가 **본인 이미지(20MB/장·50장)·영상(5GB, TUS)을 직접 업로드**해 수동 라벨링(BBOX/POLYGON) 후 본인 데이터(JSON export/원본)를 다운로드하는 기능을 신설한다(R1 미기재, 사용자 확정). 신규 `LS_PORTAL_*` 테이블(V107/V108)로 내부 파이프라인·데이터마트와 **완전 분리**되며 오토라벨링·SAM2·VLM·검수·버전관리는 여전히 미제공 — ADR-013의 "데이터마트 영상 선택 전용" 원칙은 내부 파이프라인 반영 대상에 한정된다. 신규 화면 SC-034(`/portal/uploads`)·SC-035(`/portal/uploads/:uldSn/label`) → [v2-wiki 04](../v2-wiki/04-screens-ia.md)·[18](../v2-wiki/18-database.md).

> **요구사항 외 추가 결정(2026-07-20) — AI Tool 팝업·오토라벨 draft 저장·라벨 변경 히스토리**: R1에는 "AI 탐지 결과의 폴리곤 출력 형태 선택"·"AI Tool 팝업(형태/라벨/일반·트랙) UI"·"온라인 오토라벨의 임시(draft) 저장 방식"·"라벨 변경 이력(추가/수정/삭제) 조회" 문구가 없다(SFR-08-01은 VOS 자체만 명시). 아래 4건은 SFR-08 계열을 실현하는 과정에서 필요해진 **UX/구현 세부 결정**으로 R1 근거 없이 추가됐다(사용자 확정, R1 미기재):
> - **AI 탐지 폴리곤 출력** — AI 탐지에서 폴리곤 형태를 선택하면 탐지(박스)→박스별 AI 분할(SAM)로 폴리곤을 산출(박스 개수 상한 `autolabel.polygon.max-boxes` 기본 20, 부분 실패 시 성공분만 반환).
> - **AI Tool 팝업** — 캔버스 AI 탐지·AI 추적 진입점을 형태(박스/폴리곤)·라벨·일반/트랙 버튼으로 통합한 단일 팝업(`AiToolModal`).
> - **오토라벨 draft 저장 정상화** — 온라인(캔버스) AI 탐지·AI 추적은 DB 즉시 저장을 폐지하고 좌표만 반환 → FE 작업본 병합(IoU 중복 방지) → 사용자 명시 저장(`PUT /v1/frames/{srcSn}/labels`)으로 전환. 배치 파이프라인 오토라벨은 저장 유지(무변경).
> - **라벨 변경 히스토리** — `LS_DATA_LBL_HSTRY`를 '삭제 전용 감사'에서 '변경 이력(ADDED/UPDATED/DELETED)'로 확장, `GET /v1/frames/{srcSn}/label-history` + 라벨링 화면 이력 패널 신설.
>
> → [v2-wiki 04](../v2-wiki/04-screens-ia.md)·[18](../v2-wiki/18-database.md).

> **요구사항 외 추가 결정(2026-07-31) — 증강 진행 취소**: R1은 증강(생성형 AI 위탁) 요청·검수(SFR-07/RQ-SFR-07-03)만 명시하고 **진행 중 위탁을 취소하는 기능은 기재하지 않는다**. v2는 「생성형 AI API 연동명세서 v1.1」§4.6(INT-031, 취소 API)이 이미 존재하는 데 착안해 `POST /v1/augments/{id}/cancel`(REVIEWER 전용)을 신설하고 `LS_DATA_AUG.AUG_PROC_STTS_CD`에 `CANCELED`(V148)를 추가했다(R1 미기재, 사용자 확정). 계약상 취소는 웹훅을 발사하지 않으므로 동기 응답 시점에 상태를 확정하지 않으면 영구 `PENDING`으로 남는다는 운영상 필요에서 비롯됐다. → [v2-wiki 14 §14.4.1](../v2-wiki/14-augmentation.md)·[18](../v2-wiki/18-database.md).

> **요구사항 외 추가 결정(2026-07-24) — 촬영환경·개인정보 메타 수동입력**: 구 v1 시스템의 "촬영환경 메타(날씨/시간대/계절)·개인정보 메타(익명여부/가명여부/개인정보 포함여부)" 화면을 v2 라벨링 메타탭으로 되살린다. **R1에는 없다** — v2에서 "날씨/계절/시간"은 증강 위탁 유형(SFR-07, WINTER/NIGHT/RAIN)으로만, 개인정보는 비식별 파이프라인·신고로만 다뤄왔다(사용자 확정, R1 미기재). 신규 컬럼 `LS_DATA_RAW.WTHR_NM/DAY_NGT_CD/SESN_CD`(영상단위)·`LS_DATA_SRC.ANONY_INCL_YN/PSDO_INCL_YN/PRVC_INCL_YN`(프레임단위, V130). export NIA JSON 필드(NiaVideo/NiaImage)는 이미 존재(자동파생)해 **수동값 우선 반영** 경로만 신설했다.
> - **익명여부만 특수**: 화면 표시·저장(작업자 판단 기록)만 하고 export의 anonymity는 시스템이 원본/비식별로 자동 결정(수동 override 금지 — 원본 '익명화됨' 오표기·개인정보 노출 위험 차단). 가명여부·개인정보 포함여부만 export 수동 반영.
> - **후속 백로그(BLOCKED)**: 이 메타 원천은 실은 관제 공유 `MNG_CLIP_EVNT_LST`(WTHR_CD/SESN_CD/HR_TYPE_CD/PRVC_TYPE_CD)에 존재하나 저작도구 미매핑. 관제 코드도메인·DB 접근 확보 시 관제 실제값 원천화로 전환(추정 매핑 금지).
> - 신규 화면요소 SC-005 메타탭 두 패널 → [v2-wiki 04](../v2-wiki/04-screens-ia.md)·[18](../v2-wiki/18-database.md)·[24 §24.4.1](../v2-wiki/24-dataset-export.md).

> **요구사항 외 추가 결정(2026-08-18) — 온디맨드 AI 자동 추적(시작 객체 미지정)**: R1 SFR-08-01은 "**사용자가 지정한 객체**를 추적"으로 기재돼 있고 그 해석은 ADR-035(VOS = 추적+분할)로 확정됐다 — 시작 프레임의 객체 지정(클릭·박스·마스크)이 선행되는 흐름이다(RQ-SFR-08-01 제약). v2가 라벨링 화면에 배선한 「AI 자동 추적」은 **시작 객체를 지정하지 않고** 현재 프레임과 뒤따르는 구간에서 여러 객체를 한 번에 검출·추적하므로 그 요구의 범위 밖이다(R1 미기재, 사용자 확정). 근거는 **1차 계승**이다 — 1차 오토 라벨링 화면(SKKLID-UI-02-02-06)이 시작 객체를 지정하지 않고 자동 검출하는 성질을 이미 갖고 있었다. 다만 계승한 것은 **그 성질까지**이고 검출·추적 메커니즘은 2차 신규다(1차는 첫 프레임 검출 후 선형보간 전파, 2차는 프레임마다 재검출 + 추적 식별자 연결). 선택 객체 추적(SFR-08-01 본류)은 그대로 유지되고 진입점이 화면에서 구분된다. 설계 ITEM은 UC-034 + AC-038~AC-040이며, **UC-034는 상위 기능(FEAT-001)을 직접 실현한다고 선언하지 않는다** — 그 선언이 남으면 시작 객체 지정을 전제하는 RQ-SFR-08-01을 이 흐름이 검증한다고 주장하게 되기 때문이다(상위 연결은 DFEAT-019 경유로 유지). → [v2-wiki 11](../v2-wiki/11-ai-assisted.md)·[04](../v2-wiki/04-screens-ia.md).

---

## 19.4 사용 방법

1. **추가 검토는 §19.1 체크리스트만** 본다. (🟢·⛔는 추가 대상 아님)
2. 각 후보는 추가 전 **R1 요구사항정의서**에 해당 SFR이 있는지 먼저 확인.

> ⚠ **구 서술 폐기(2026-08-19 재확인)** — *"`docs/design/R1-*.md`"* 경로는 더 이상 유효하지 않다. `docs/design/`은 2026-08-15 동결됐고 R1 요구사항정의서는 `docs/archive/frozen-20260815/design/KLID_AT_사용자요구사항정의서.md`로 이관됐다(판정 근거로 쓰지 않는 것이 원칙 — 위 §19.5 각주 참조). 현재 `docs/design/`에는 LogiCraft 구현 키트 디렉터리 14개만 있다. 근거: `docs/archive/frozen-20260815/README.md`.
3. 요구사항에 있고 미구현이면 → `/cc-plan`으로 설계 → `/cc` 개발 파이프라인.
4. 요구사항에 없으면 → "의도적 미포함"으로 본 페이지에 사유를 기록(🟢/⛔로 이동).

> 이 체크리스트는 **v1 위키 내부 참고용**이다. 실제 개발 백로그는 `docs/design/`·이슈 트래커에서 관리할 것.

---

## 19.5 R1 요구사항 매핑 결과 (2026-07-14, ★2026-08-19 구현 현황 갱신)

> §19.1 후보를 **R1 사용자요구사항정의서** + R3 추적표에 대조한 결과(2026-07-14 시점). **원칙: R1 근거 없으면 넣지 않는다**(§19.4). R1 제출본은 발주처 톤의 업무 수준 서술이라 캔버스 UI 세부 조작(슬라이더·버튼·단축키)은 열거하지 않음 — 따라서 다수 후보가 "간접" 또는 "범위 외"로 판정됨.
>
> ⚠ **`docs/design/R1-*.md` 인용은 2026-08-15 동결로 더는 조회 가능한 정본 경로가 아니다.** 아래 SFR 인용문 자체는 이번 라운드에서 재확인하지 않았다(범위 밖 — R1 재조회는 이 라운드의 담당이 아니다). 이 절의 **가치는 "R1 근거 유무 판정"이 아니라 "이미 구현됐는가"로 옮겨갔다** — §19.1 재판정(2026-08-19)에서 아래 "간접"·"범위 외" 분류 항목 다수가 **R1 근거 유무와 무관하게 이미 구현 완료**로 확인됐기 때문이다(R1 이 없어도 사업/UX 판단으로 만들어진 것 — §19.3 의 "요구사항 외 추가 결정" 관례와 같은 패턴). 아래 하위 절 표기는 **원문 그대로 보존**하고 구현 현황만 병기한다(내용을 지우지 않는다 — 문서 동기화 규칙).

### ✅ 개발 대상 (R1 명시 근거)

| 항목 | R1 근거 | 상태(2026-07-14) | 상태(2026-08-19 재확인) |
|---|---|---|---|
| **폴리곤/폴리라인 트랙 보간** | **SFR-08-01** "경계(폴리곤)를 자동 갱신…빈 프레임은 트랙 보간으로 채운다" | ⚠ BBOX only — 요구 대비 미달. **유일한 명확 개발 갭** | 🔶 **POLYGON 은 구현 완료**(`batch/interpolation/TrackInterpolator.interpolatePolyshape`), **POLYLINE 만 잔여 갭**(DB 코드값 자체 미도입). 위 §19.1 P2 참조. |

### ✅ R1 근거 있으나 이미 충족/해결 (추가 개발 불요)

- **VLM 벤더 계약 정합** (SFR-17 + 범위 정합 ③) — ✅ **코드 정합 구현 완료 (2026-07-07)**. callbackUrl 실주입·비동기 accepted 계약·HMAC 제거(벤더 무서명, 발급게이트 대체) 전부 CLOSED. 잔여=실운영 전 IP allowlist 재검토(prd)·`VLM_CLIENT_ENABLED=false` 실연동 별도. 메모리 `vlm-vendor-contract-intellivix`.
- **작업 중 비식별 신고** (SFR-09-03) — ✅ 라벨링단계(srcSn) 구현 완료(`DeidentReportController`). R1 원문은 '라벨 작업 중'만 명시 → **사실상 충족**. 마킹단계(rawSn) 확장은 R1 원문 밖(planned, 선택).

### 🔶 간접 (R1 미명시 — 상위 SFR 포괄 여지, 사업/UX 판단) — **2026-08-19: 대부분 이미 구현됨**

이미지 조절 패널 · 라벨 복사/붙여넣기 · 트랙 번호 변경/머지 · 프레임 4색 체계 · YOLO 수동 트리거 버튼 · 커스텀 라디오 메타 · 증강 적재 선두 비식별.
> SFR-08/11/16/17에 포괄될 수는 있으나 직접 인용할 문구 없음. **넣으려면 별도 UX 결정 필요**(R1 자동 근거 아님) — 였으나, **"넣을지 말지"의 UX 결정은 이미 내려져 구현됐다.** 위 §19.1 재판정 결과: **이미지 조절 패널·복사/붙여넣기·트랙 번호 변경/머지·YOLO 수동 트리거 버튼**은 완료, **프레임 4색 체계**는 3/4 부분 구현, **커스텀 라디오 메타**(프레임 축)만 여전히 갭. "증강 적재 선두 비식별"은 이 §19.5 절 밖(배치 파이프라인 축)이라 이번 라운드에서 재확인하지 않음.

### ⛔ 범위 외 (R1 미기재 — 넣지 말 것) — **2026-08-19: "범위 외"가 아니라 상당수 이미 구현됨, 표기 유의**

라벨링 단축키 정합 · 회전/Fit/영역확대 도구 · 캔버스 그리드 · 객체 잠금/숨김 · 통계 CSV 리포트 · 이미지 자동 분류 · 연습장 · 프레임 폐기 워크플로.
> 주의: 회전/확대를 RQ-SFR-06-03("이미지 확대·축소")로 근거 삼으면 **오독** — 해당 SFR은 생성형 변형(외부 책임)을 가리킴. 통계 CSV는 통계 SFR 자체가 R1에 없음.
>
> ⚠ **이 분류명("범위 외")은 R1 인용 근거가 없다는 뜻이었을 뿐, "만들지 않는다"는 확정 결정이 아니었다** — 실제로는 R1 근거 없이도 사업/UX 판단으로 다수가 만들어졌다. 위 §19.1 재판정(2026-08-19) 결과: **라벨링 단축키 정합**(대부분 정합, 폴리곤 점 편집만 잔여)·**회전/Fit/영역확대 도구**·**캔버스 그리드**·**객체 잠금/숨김**·**프레임 폐기 워크플로**는 완료. **통계 CSV 리포트**(실데이터)·**이미지 자동 분류**·**연습장**만 R1 미기재 그대로 미구현으로 남아 있다.

> **결론(2026-08-19 갱신)**: R1 명시 근거만으로 좁혀 보던 2026-07-14 시점 결론(*"실제 남은 개발 대상은 폴리곤/폴리라인 트랙 보간 1건"*)은 **더 이상 "무엇이 구현됐는가"를 반영하지 못한다** — R1 미기재 후보 다수가 R1 과 무관하게 사업/UX 판단으로 이미 만들어졌기 때문이다. 진짜 잔여 갭은 위 §19.1 재판정 기준 **4건**(커스텀 메타 라디오형[프레임 축]·통계 CSV 리포트·이미지 자동 분류·연습장) + **부분 갭 2건**(프레임 4색 체계의 반려색·트랙 보간의 POLYLINE) + **부분 정합 1건**(라벨링 단축키의 폴리곤 점 단위 편집)이다.

---

### 관련 페이지
- 전체 비교: [18 v1 ↔ v2 비교](18-v1-v2-comparison.md)
- 화면 단위 상세(SC-ID·입출력 param·단축키·프레임 색상): [21 사용자 화면 가이드](21-user-screen-guide.md)
- v1 기능 상세: [05 프로젝트](05-project-management.md) · [07 라벨링](07-labeling-tools.md) · [09 검수](09-review-workflow.md) · [13 게시판·연습장](13-board-practice.md)
- v2 정본: 루트 [`CLAUDE.md`](../../CLAUDE.md)

> ⚠ **구 서술 폐기(2026-08-19 재확인)** — *"v2 정본: 루트 `CLAUDE.md` · `docs/design/`"* 에서 `docs/design/` 부분은 낡았다. 2026-08-15 동결로 그 경로는 더 이상 CBD 산출물(R1~R3·D1~D9)의 정본이 아니며(→ `docs/archive/frozen-20260815/design/`로 이관, 판정 근거로 쓰지 않음), 지금 `docs/design/`에는 **LogiCraft 구현 키트 디렉터리 14개**만 있다. v2 정본은 루트 `CLAUDE.md`뿐이다.
