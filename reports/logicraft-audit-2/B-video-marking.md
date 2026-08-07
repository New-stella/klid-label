# 배치 B — 마킹·영상 축 (SCREEN-006/007/009)

## 요약
- 발견: ERR 3 / STALE 0 / GAP 3 / CONFLICT 2

(STALE 로 분류할 만한 독립 항목은 없었음 — ERR-B01·ERR-B02 는 "폐기된 정책의 잔존"이라기보다 "현재 코드와 다른 서술"이라 ERR 로 분류. CONFLICT-B01 은 문서 간 상호모순이라 별도 분류.)

---

## SCREEN-006 (마킹 화면)

### [ERR-B01] VLM 검증이벤트유형 소싱처가 실제와 다르다 — MNG_CLIP_EVNT_LST 는 제거된 테이블
- **위치**: `purpose` 문단 + section 2 "③ 마킹 툴바" description
- **정의서 서술**: "이벤트명은 화면에서 입력받지 않고 관제 클립 메타에서 자동 소싱하여 VLM 에 영상 단위 전달"(purpose) / "**이벤트명은 화면에서 입력받지 않고 관제 클립 메타(MNG_CLIP_EVNT_LST.EVNT_TYPE_CD)에서 자동 소싱**되어 마킹 완료 시 영상 단위로 VLM에 전달된다"(section 2 description)
- **실제**: VLM `verify` 위탁의 `event_type` 조달처는 `LS_DATA_INGEST.VRFC_EVNT_TYPE_CD` 단일 진실원이며, `MNG_CLIP_EVNT_LST` 는 이미 제거된 관제 공유 마스터다. `IngestSourceLink` 클래스 javadoc 이 명시: "관제 공유 마스터(MNG_RESOURCE_CCTV·MNG_EX_LOCAL_GOV·MNG_CLIP_MASTER·MNG_CLIP_EVNT_LST) **제거 후**, CCTV 명·지자체명·파일형식·좌표는 전부 관제가 인입 행에 실어 보낸 평면값에서 온다." `VlmTimeseriesStep.resolveEventType()` 은 `IngestSourceRepository.findSourceMeta(rawSn)` 로 `LS_DATA_INGEST` 만 조회한다 — `MNG_CLIP_EVNT_LST` 를 참조하는 코드는 인입 배치 어댑터(적재 시점의 별도 매핑, `TrainingVideoIngestTx`)에만 있고 마킹 완료~VLM 위탁 경로에는 없다.
- **근거**: `IngestSourceLink.java(SQL_LATERAL_JOIN, class-level javadoc)` · `VlmTimeseriesStep.java(resolveEventType)` · `IngestSourceRepository.java`
- **조치 제안**: "관제 클립 메타(MNG_CLIP_EVNT_LST.EVNT_TYPE_CD)에서 자동 소싱" 문구를 "관제 인입값(`LS_DATA_INGEST.VRFC_EVNT_TYPE_CD`)에서 자동 소싱"으로 정정. CLAUDE.md 「★event_type 은 저작도구가 매핑표를 만들지 않고 관제 인입에서 수신한다」절과 정합시킬 것.
- **확신도**: high

### [ERR-B02] 영상 스트리밍이 "직접 src 바인딩"이 아니라 서명 URL 선행 조회 방식이다
- **위치**: section 0 "① 영상 플레이어" description
- **정의서 서술**: "스트림은 `/stream` 엔드포인트 직접 src 바인딩(**별도 fetch 아님**)."
- **실제**: `<video>` 엘리먼트는 `Authorization` 헤더를 붙일 수 없어 인증이 걸린 `/stream` 을 직접 재생할 수 없다. 실제 FE 는 먼저 `GET /v1/videos/{rawSn}/stream-url` 을 별도로 fetch 해 BE 가 만든 단기 서명 URL(`/api/v1/videos/{rawSn}/stream?exp=...&sig=...`)을 받고, 그 서명 URL 을 `<video src>` 에 바인딩한다. 서명 만료(401) 시 1회 재발급 로직도 있다.
- **근거**: `hooks/marking/useMarkingWorkspace.ts(streamQuery = useQuery(videoQueries.streamUrl))` · `api/videos/types.ts` (DTO 주석: "`<video>` 엘리먼트는 Authorization 헤더를 못 붙여 인증 스트림(/stream)을 직접 재생하지 못한다") · `api/videos/axios.ts(GET /videos/{rawSn}/stream-url)`
- **조치 제안**: description 을 "먼저 `GET /v1/videos/{rawSn}/stream-url` 로 단기 서명 URL 을 발급받아 `<video src>` 에 바인딩한다(서명 만료 시 1회 재발급). 인증 헤더를 붙일 수 없는 `<video>` 태그 특성상 `/stream` 직접 바인딩은 불가능하다"로 정정. `consumes_apis` 에도 stream-url 발급 API 를 추가 검토.
- **확신도**: high

### [ERR-B03] "마킹 완료" 버튼은 `state: disabled` 가 아니라 클릭 시 토스트 경고다
- **위치**: section 2 component[3] `{"label":"마킹 완료","state":"disabled", triggers_api:"API-047"}` + description "마킹 완료 버튼(마크 0건이면 disabled)"
- **정의서 서술**: 마크 0건이면 버튼이 `disabled` 상태.
- **실제**: 실제 "마킹 완료" 버튼(`MarkingHeader.tsx`, 스펙상 섹션 2 툴바가 아니라 상단 헤더에 위치)에는 `disabled` 속성이 전혀 없다 — 항상 클릭 가능하며, `handleComplete()` 내부에서 `markMode==="manual" && marks.length===0` 이면 `toast.warning("재생하며 마킹을 1건 이상 쌓아 주세요.")` 로 막고 제출을 취소한다. 이 차이는 사소하지 않다 — AUTO 모드는 애초에 `localMarks` 를 쓰지 않으므로(간격만 필요), 스펙이 말한 "마크 0건이면 disabled" 를 문자 그대로 구현하면 AUTO 모드 제출 버튼이 영구히 비활성화되는 오류가 된다.
- **근거**: `components/marking/MarkingHeader.tsx`(Button, disabled 미부여) · `hooks/marking/useMarkingWorkspace.ts(handleComplete)`
- **조치 제안**: "마크 0건이면 disabled" → "MANUAL 모드에서 마크 0건 상태로 클릭하면 토스트 경고를 띄우고 제출을 막는다(hard disable 아님, AUTO 모드는 이 검사 대상이 아님)"으로 정정.
- **확신도**: high

### [GAP-B01] 모드 전환 단축키(1=수동, 2=자동)가 spec 에 없다
- **위치**: purpose 문단 + section 2 description (단축키는 "Space 마킹/Del 삭제/Enter 완료"만 언급)
- **실제**: `useMarkingHotkeys` 는 전역 keydown 에서 `"1"` → 수동 모드, `"2"` → 자동 모드 전환을 처리하며, `MarkingToolbar.tsx` 의 탭 라벨에도 `<Kbd>1</Kbd>`/`<Kbd>2</Kbd>` 가 시각적으로 노출된다(사용자가 실제로 인지하는 기능). 또한 Space/Del/Enter 세 단축키는 코드상 **`mode==="manual"` 일 때만** 발화한다(`if (mode !== "manual") return;` 이후에 세 분기가 있음) — 이 스코프 제한 자체는 section 2 desc("수동 모드: 단축키 안내")와 일치하지만, 1/2 자체는 스코프 제한 없이 항상 발화하며 spec 어디에도 없다.
- **근거**: `hooks/marking/useMarkingHotkeys.ts` · `components/marking/MarkingToolbar.tsx`(Kbd 1/Kbd 2)
- **조치 제안**: purpose 의 "키보드 단축키(Space 마킹/Del 삭제/Enter 완료)"를 "키보드 단축키(1=수동/2=자동 모드 전환, Space=마킹/Del·Backspace=삭제/Enter=완료 — 후 3종은 수동 모드에서만 발화)"로 보강.
- **확신도**: high

### [GAP-B02] 비식별 신고 버튼의 클라이언트 사전 비활성화(UnsupportedReasonTooltip)가 마킹 화면에는 미구현
- **위치**: section 2 components[4]/[5] — "비식별 누락 신고" 버튼 note: "버튼은 videoDetail 기준으로 사전 비활성화 — ①파생영상 ②배치 단계≠MARKING_READY" + 전용 컴포넌트 `UnsupportedReasonTooltip`
- **실제**: 마킹 화면(`MarkingHeader.tsx`)의 `DeidentReportButton` 호출부는 `target={{ scope: "video", rawSn }}` 만 넘기고 `unsupportedReason` prop 을 전혀 전달하지 않는다. `unsupportedReason` prop 사용처는 저장소 전체에서 라벨링 화면(`components/labeling/header/LabelHeader.tsx`, SCREEN-005) 한 곳뿐이다. 즉 마킹 화면에서는 파생영상이든 배치 단계가 무엇이든 버튼이 항상 활성 상태로 노출되고, 사전 차단 없이 클릭 → 제출 → BE 의 412 응답에만 의존한다(사유를 다 적고 제출한 뒤에야 거부되는 동선 — CLAUDE.md 가 명시적으로 피하려던 UX).
- **근거**: `components/marking/MarkingHeader.tsx`(DeidentReportButton 호출, unsupportedReason 미전달) · `components/deident/DeidentReportButton.tsx`(prop 정의) · `components/labeling/header/LabelHeader.tsx`(유일한 unsupportedReason 사용처)
- **조치 제안**: 코드 갭이므로 정의서 자체를 고칠 필요는 없을 수 있으나(정의서가 옳고 코드가 미달), 감사 목적상 실제 구현 갭으로 보고. 정의서를 "제3자가 구현 가능한 사양"으로 유지하려면 이 사전 비활성화 로직이 실제로 존재하지 않는다는 점을 알고, 재구현 시 videoDetail(`API-043`) 응답의 `derivative`/`status` 필드를 이용해 버튼을 비활성화하고 툴팁 문구(정의서에 이미 정확히 명시됨)를 노출하도록 별도 구현 과제로 추적할 것.
- **확신도**: high

### [GAP-B03] `POST /v1/videos/{rawSn}/redeident`(SCREEN-009 관련이지만 참고) 류 조건부 토글 패턴과 별개로, 마킹 화면 자체엔 role 차이 서술 없음(문제 없음, 확인용)
- 스킵 — SCREEN-009 항목으로 이관(아래 GAP-B05).

---

## SCREEN-007 (영상 목록 화면)

### [CONFLICT-B01] `/video/completed`(VideoListPage) 는 납품 FE 에 존재하지 않는다 — 실제 라이브 구현은 LogiCraft 가 "삭제됐다"고 기록한 SCREEN-008(`/video/status`, VideoStatusPage)이다
- **위치**: `route: /video/completed`, `title: 영상 목록 화면`, purpose 전체
- **정의서 서술**: 이 화면(SCREEN-007)이 route `/video/completed`, 컴포넌트 `VideoListPage`로 현재 유효하며, REVIEWER 의 마킹 진입/재배정/일괄 배정 기능을 담당한다.
- **실제**:
  1. **테스트베드**(`upload-ui/frontend`)에는 정확히 이 사양대로 `/video/completed → VideoListPage`, `VideoFilters`, `AssignModal`(assign/reassign/bulk), `MarkingModal` 이 존재하고 필터·상태 옵션(6종)·이벤트유형 그룹핑·일괄배정 흐름까지 SCREEN-007 서술과 정확히 일치한다.
  2. **납품 FE**(`klid-label-frontend`, 외부팀 기준 실제 구현)에는 `/video/completed` 라우트 자체가 없다. 대신 `/video/status → VideoStatusPage`(`VideoStatusFilters` + `VideoStatusTable`)가 사실상 동일한 기능(제목 "영상 처리 현황", 새로고침, 필터, BulkActionBar 일괄배정, TaskAssignDialog, MarkingEntryDialog 자동/수동)을 제공한다 — 이름만 다를 뿐 SCREEN-007 의 section 0~5 서술과 기능적으로 대응된다.
  3. 그런데 LogiCraft 자체 ITEM `SCREEN-008`(제목 "영상 처리 현황 화면", route `/video/status`)은 **`status: deprecated`** 이고 `change_summary`가 "코드 제거에 따른 deprecated 처리 — `/video/status` 라우트+`VideoStatusPage`+전용 훅(`useBatchStatus`)+`VideoStatusStepper`+관련 타입/API/쿼리키 삭제(2026-06-17). LNB 진입점이 없던 orphan 화면이었고, '영상 처리 현황' 메뉴는 SCREEN-007(VideoListPage, `/video/completed`)에 연결됨."이라고 명시한다.
  4. 즉 SCREEN-008 의 "코드 삭제됨" 주장은 **납품 FE 기준으로는 사실이 아니다** — `VideoStatusPage`/`/video/status` 는 납품 FE 의 살아있는 유일한 목록 화면이다. 이 deprecation 판단은 테스트베드만 보고 내려진 것으로 보이며, 두 FE 가 갈라진 뒤(메모리: "우리 FE 는 테스트베드, 실제 FE 는 외부팀 기준 구현") 재확인되지 않았다.
- **근거**: `klid-label-frontend/src/routes/index.tsx`(`{ path: "/video/status", element: <VideoStatusPage /> }`, `/video/completed` 없음) · `klid-label-frontend/src/pages/VideoStatusPage.tsx` · `klid-label-frontend/src/components/video-status/VideoStatusTable.tsx`(BulkActionBar·TaskAssignDialog·MarkingEntryDialog) · `upload-ui/frontend/src/router/index.tsx`(`/video/completed` → `VideoListPage`) · LogiCraft `SCREEN-008`(status=deprecated, change_summary, MCP `get_item` 조회 결과)
- **조치 제안**: (a) SCREEN-007 의 route 를 납품 FE 기준(`/video/status`)으로 정정하거나, (b) 테스트베드/납품 FE 중 어느 쪽이 사양의 진실원인지 먼저 확정 — 만약 "납품 FE 가 최종 산출물"이 맞다면 SCREEN-007 자체가 존재하지 않는 라우트를 규정하고 있는 것이고 SCREEN-008 의 deprecated 판정도 되돌려야 한다. LogiCraft ITEM 은 이번 감사에서 직접 수정하지 않으므로, 사용자 확인 후 SCREEN-007/SCREEN-008 두 ITEM 을 함께 재조정할 것을 권고.
- **확신도**: high (라우터 파일 직접 대조로 확인. 단 "어느 FE 가 사양의 진실원인가"라는 판단 자체는 정책 결정 사안이라 그 부분만 조치 제안에 명시)

### [CONFLICT-B02] `intervalFrames` 상한(3600)이 SCREEN-006 과 SCREEN-007 에서 다르게 서술된다
- **위치**: SCREEN-007 section 5 "⑤ 마킹 진입 팝업" description — "자동은 프레임 간격(**1 이상 정수, 상한 미검증·BE 백스톱**) 입력" vs SCREEN-006 section 2 — "간격(프레임) number 입력(**1~3600**, 기본값 300)"
- **실제**: BE `MarkingRequest` DTO 에는 `intervalFrames` 에 `@Min`/`@Max` 어노테이션이 전혀 없고, `MarkingService.create()` 는 `intervalFrames == null || intervalFrames <= 0` 만 거부한다(`ErrorCode.INVALID_INPUT`) — **상한 검증이 코드 어디에도 없다**. FE 의 `3600`(`MAX_INTERVAL_FRAMES`)은 `<input type="number" max={3600}>` HTML 속성일 뿐이며, `onChange` 핸들러가 `Number(e.target.value)` 를 그대로 세팅해 브라우저가 값 자체를 막지 않는다(스핀 버튼 클릭만 제한, 직접 타이핑은 통과) — 즉 SCREEN-007 의 "상한 미검증" 서술이 실제와 일치하고, SCREEN-006 의 "1~3600" 은 마치 하드 상한이 있는 것처럼 오도한다.
- **근거**: `backend/.../marking/dto/MarkingRequest.java`(intervalFrames 필드에 검증 어노테이션 없음) · `backend/.../marking/service/MarkingService.java`(`create` 메서드 — `intervalFrames <= 0` 만 검사) · `klid-label-frontend/src/components/marking/markingModel.ts`(`MAX_INTERVAL_FRAMES = 3600`, UI 힌트일 뿐) · `klid-label-frontend/src/components/marking/MarkingToolbar.tsx`(`<Input type="number" max={MAX_INTERVAL_FRAMES}>`)
- **조치 제안**: SCREEN-006 section 2 note 를 "1 이상 정수(FE UI 힌트는 최대 3600 이나 BE 는 하한만 검증하며 상한은 미검증 — SCREEN-007 서술과 통일)"로 정정.
- **확신도**: medium-high (BE 미검증은 코드로 확정. FE `<input max>` 의 브라우저별 clamp 거동 미세 차이는 확인하지 않았으나 `onChange` 자체가 값을 그대로 받아 상한을 신뢰할 수 없다는 결론엔 영향 없음)

### 확인 결과 — 이상 없음 (정확한 항목, 기록만)
- 상태 필터 6종(`전체/완료/처리중/마킹 대기/대기/실패`)은 `LsDataRaw.DATA_STTS_*`(PENDING/MARKING_READY/PROCESSING/COMPLETED/FAILED) 5종 + 전체와 정확히 1:1 (테스트베드 `VideoFilters.tsx` 확인).
- 이벤트유형 그룹 축(표시명 동일 유형코드 1옵션 접기, 파라미터는 코드 유지) 서술은 `useEventTypes` 훅·`EventTypeFilterSupport` 계열 정책과 정합.
- 일괄 배정이 전용 벌크 API 없이 `videoIds` 개별 `POST /v1/assignments` 반복 호출이라는 서술은 `AssignModal.tsx`(bulk 모드) 와 일치.
- 재배정이 `PATCH /v1/assignments/{assignmentId}` 이고 reviewer 변경 미지원(workerId 만 변경)이라는 서술은 코드 주석과 일치.

---

## SCREEN-009 (영상 상세 화면)

### 확인 결과 — 이상 없음 (정확한 항목, 기록만)
- `RedeidentButton` 노출 가드가 `status`(배치단계, 종착 COMPLETED)가 아니라 `reviewSttsCd`(검수 진실원) 기준이라는 서술은 `VideoDetailHeader.tsx` 주석·코드와 정확히 일치("검수완료 판정은 reviewSttsCd(진실원)로 한다 — status 는 종착이 COMPLETED 라 절대 APPROVED 가 되지 않으므로...").
- "개인정보 분류" 항목 미표시(2026-08-05 정책) 서술은 `VideoDetailPage`/관련 컴포넌트 전체에 `privacyTypeCd`/`PrivacyBadge`/"개인정보" 참조가 0건인 것으로 확인.
- "버전관리로 이동" 버튼 제거 + 히스토리가 라벨링 캔버스(SCREEN-005)로 통합됐다는 서술은 `/history/{id}` 라우트 부재 + `HistorySheet` 가 `LabelHeader.tsx`(라벨링 화면)에만 존재하는 것으로 확인.

### [GAP-B04] 재비식별 API(API-112)가 `kpst.deid.enabled` 토글에 조건부로만 등록된다는 사실이 spec 에 없다
- **위치**: section 0 component[5] "재비식별 요청" 버튼 note
- **정의서 서술**: 노출 가드(REVIEWER+APPROVED+deIdntfYn≠Y)와 응답 코드(202/409/403)만 서술.
- **실제**: `ApprovedRedeidentController` 전체가 `@ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")` 로 등록되어, 이 설정이 꺼진 환경(KPST 미연동/토글 off)에서는 `POST /v1/videos/{rawSn}/redeident` 라우트 자체가 존재하지 않아 404 가 난다. `application.yml` 기본값은 `KPST_DEID_ENABLED:true` 라 평상시엔 노출되지만, 이 조건이 spec 에 전혀 언급되지 않아 "가드 조건을 충족했는데 왜 404 가 나는가"를 제3자가 재현·디버깅할 근거가 없다. FE `RedeidentButton.tsx` 의 에러 매핑도 404 를 "대상 영상을 찾을 수 없습니다"로만 표시해, 실제로는 "기능 자체가 이 환경에서 비활성"인 상황을 영상이 없다는 것처럼 오인시킬 소지가 있다.
- **근거**: `ApprovedRedeidentController.java`(클래스 레벨 `@ConditionalOnProperty`) · `application.yml`(`kpst.deid.enabled: ${KPST_DEID_ENABLED:true}`) · `RedeidentButton.tsx`(`resolveRedeidentError`, 404 분기)
- **조치 제안**: component note 에 "이 엔드포인트는 `kpst.deid.enabled` 설정(기본 true)이 꺼진 환경에서는 라우트 자체가 없어 404 가 난다"를 부기.
- **확신도**: medium (기본값이 true 라 실제 영향 환경은 제한적이나, spec 완결성 기준으로는 누락)

### [GAP-B05] SCREEN-006/009 공통 — `required_roles: []` 이지만 WORKER/REVIEWER 별 화면 차이가 서술에만 있고 컴포넌트 단위 role 분기가 명시되지 않음
- **위치**: SCREEN-009 전체 (required_roles: [])
- **실제**: 재비식별 버튼은 REVIEWER 전용(`isReviewer` 가드)이지만 이 필드 자체는 컴포넌트 note 안에 산문으로만 적혀 있고, 스키마 레벨 role 태깅(`required_roles`)에는 반영되지 않는다. 기능상 치명적 갭은 아니나(note 에 정확히 적혀 있어 제3자가 놓치기 어려움), 스키마 일관성 관점의 경미한 지적.
- **근거**: SCREEN-009.json (`required_roles: []`, RedeidentButton note 에만 REVIEWER 조건 기술)
- **조치 제안**: 낮은 우선순위 — 스키마 표준화 논의 시 참고.
- **확신도**: low
