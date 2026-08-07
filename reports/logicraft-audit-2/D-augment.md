# 배치 D — 증강 축 (SCREEN-022/023)

## 요약
- 발견: ERR 6 / STALE 2 / GAP 7 / CONFLICT 3

---

## SCREEN-022 (증강 요청 화면, v9)

### [GAP-D01] `jobId`(요청 응답) 을 결과화면 이동에 쓰면 안 된다는 사실이 어디에도 없다
- **위치**: "고정 하단 액션 바" > components[2] "처리 요청" (`triggers_api: API-060`), description "성공 시 결과화면 이동"
- **정의서 서술**: "증강 종류(isAugmentKind)는 POST /v1/augments/request 위탁 요청 → 성공 시 결과화면 이동" — 어떤 값으로 이동하는지 명시 없음. API-060 응답 shape 도 정의서 어디에도 없음.
- **실제**: `POST /v1/augments/request` 응답의 `jobId` 는 **placeholder**(인스턴스 기동시각 기반 `AtomicLong`)로, 어떤 엔티티도 가리키지 않는다. 실제 결과화면 이동은 요청 바디의 `videoIds[0]`(원본 RAW_SN)을 써야 한다 — 코드 주석 자체가 "다시 되돌리지 말 것"이라고 경고할 만큼 과거 실제로 이 오류가 발생했던 지점이다.
- **근거**: `AugmentRequestResponse.java`(jobId 필드 javadoc "외부 시스템 jobId (placeholder — 외부 미연동 단계에서 임시 발급)") · `AugmentRequestPage.tsx`(useRequestAugment onSuccess — `navigate('/augment/result/${targetRawSn}')`, `variables.videoIds[0]` 사용, jobId 미사용) · `AugmentController.java`(`result()` Javadoc "jobId(=원본 RAW_SN)")
- **조치 제안**: SCREEN-022 "처리 요청" 컴포넌트 note 에 "성공 시 이동 대상은 응답 jobId 가 아니라 요청 본문의 videoIds[0](=RAW_SN)이다 — 응답 jobId 는 placeholder 이며 사용 금지" 를 명시. SCREEN-023 route `:jobId` 파라미터에도 "= 원본 RAW_SN, 별도 job 엔티티 식별자 아님" 명시 필요(SCREEN-023 GAP-D06 과 동일 뿌리).
- **확신도**: high

### [GAP-D02] 생성 조건(prompt) 5필드 검증 규칙의 구체적 수치·문구가 없다
- **위치**: "Step 1" > components[4] "생성 조건(prompt) 5필드"
- **정의서 서술**: "전부 필수·자유 문자열(공백만 입력 거부, 길이 상한, 보이지 않는 문자 제거)" — "길이 상한"이라고만 하고 숫자가 없음. 오류 문구도 없음.
- **실제**: 상한은 정확히 **50자**(원문 기준, BE `PromptFields.MAX_FIELD_LENGTH=50` / FE `AUGMENT_PROMPT_MAX_LENGTH=50`). 오류 문구도 고정: "필수 입력입니다. 공백·보이지 않는 문자만으로는 입력할 수 없습니다." / "50자 이내로 입력하세요." 이며, 필드마다 개별 오류가 뜬다(전체 요약 alert 는 별도로 "입력을 확인하세요: {필드명…} — 5개 항목을 모두 채워야 요청할 수 있습니다.").
- **근거**: `AugmentRequestRequest.java(PromptFields.MAX_FIELD_LENGTH)` · `features/augment/types.ts(AUGMENT_PROMPT_MAX_LENGTH)` · `features/augment/promptValidation.ts(validateAugmentPrompt)`
- **조치 제안**: "길이 상한" → "50자(원문 기준, DB 컬럼 아님 — VisibleTextNormalizer 정규화 후에도 재확인)"으로 구체화하고 표준 오류 문구를 옵션 필드로 등록.
- **확신도**: high

### [GAP-D03] PII 경고 배너가 정의서에 없다
- **위치**: "Step 1" > components[4] "생성 조건(prompt) 5필드"
- **실제**: 프롬프트 입력 블록 상단에 상시 노출되는 경고 배너가 있다: "개인식별정보(이름·차량번호·연락처 등)를 입력하지 마세요 — 입력한 내용은 외부 생성형 AI 서비스로 그대로 전송됩니다." 이 값이 외부 벤더로 그대로 나간다는 점을 감안하면 화면 요소로서 누락 시 재현 시 개인정보 노출 경고 자체가 빠질 수 있다.
- **근거**: `features/augment/components/AugmentPromptFieldset.tsx`(ShieldAlert 경고 문단)
- **조치 제안**: components 목록에 Alert(PII 경고, variant=warning) 컴포넌트 추가.
- **확신도**: high

### [CONFLICT-D04] "가공 없이 외부로 전송" vs 같은 섹션의 "보이지 않는 문자 제거"
- **위치**: purpose 상단 "[증강 3종 — 생성 조건(prompt) 필수]" 문단 vs "Step 1" components[4] note
- **정의서 서술**: purpose: "값은 가공 없이 외부로 전송되고 LS_DATA_AUG.PROMPT_CN 에 원문이 보관된다." / 같은 화면 컴포넌트 note: "공백만 입력 거부, 길이 상한, 보이지 않는 문자 제거."
- **실제**: BE 는 `VisibleTextNormalizer` 로 제어문자·NBSP·ZWSP·BOM·RLO·U+2028/2029 등을 제거하고 trim 한 **정규화값**을 저장·전송한다 — "원문 그대로"가 아니다.
- **근거**: `AugmentRequestRequest.java(buildPrompt, requirePromptField)` — "정규화는 VisibleTextNormalizer 단일 원천을 쓴다" 절
- **조치 제안**: purpose 문구를 "값은 형식 정규화(제어문자·보이지 않는 문자 제거, trim)만 거쳐 그 외 가공(요약·재구성) 없이 외부로 전송되고…"로 정정. 사업적 의미("사용자가 입력한 조건을 재해석하지 않는다")는 유지하되 문자 그대로의 "가공 없이"는 오도.
- **확신도**: medium

### [ERR-D05] "최근 요청 이력" 이 선언한 API-059 응답 shape 이 실제보다 좁다
- **위치**: "최근 요청 이력" section description
- **정의서 서술**: "응답은 영상 단위 그룹핑 {jobId,videoId,types[],status}로 제공"
- **실제**: 실제 응답(`AugmentJobResponse`)은 `jobId, videoId, cctvName, types[], resolutionTypes[], status, requestedAt, completedAt, videoCount` 9필드다. 같은 섹션의 컴포넌트 라벨 자체가 "증강 잡 카드 ×6 (유형/상태/**요청일시**)"라고 카드에 요청일시를 표시한다고 해놓고, description 이 선언한 응답 필드 목록에는 `requestedAt` 이 아예 없다 — 카드가 표시해야 할 값의 출처가 정의서 안에서 끊긴다.
- **근거**: `AugmentJobResponse.java`(9필드) · SCREEN-022 raw JSON "최근 요청 이력" 섹션(label "유형/상태/요청일시" vs description 필드 목록)
- **조치 제안**: description 의 필드 목록에 `requestedAt`(카드 표시용) 최소 추가, 가능하면 9필드 전체 반영.
- **확신도**: high

---

## SCREEN-023 (증강 결과 화면, v5)

### [GAP-D06] 진행률·취소(cancel) 기능 전체가 화면 정의에 없음 (가장 중대)
- **위치**: 화면 전체 — `consumes_apis: [API-061, API-062, API-063]` 만 선언, sections 어디에도 진행률·취소 컴포넌트 없음
- **정의서 서술**: purpose: "진행률은 잡 단위 가짜값이 아니라 항목별 서버 실값이다." — 이 문장이 유일한 단서이나, 그 실값을 어떤 API 로 어떻게 가져오는지, 화면 어디에 표시하는지 sections 에 전혀 없음. ② 작업 요약 카드의 Progress 컴포넌트는 "COMPLETED 아닐 때만 진행률 + ProgressBar 노출"이라고만 하고 `binds_to` 도 없어 API-061 파생값처럼 읽힘.
- **실제**: 결과 항목(DecisionCard) 마다 **별도 컴포넌트** `AugmentProgressPanel` 이 있고, 이것이 `GET /v1/augments/{id}/progress`(코드상 존재하나 SCREEN-023 에 API 아이디 자체가 없음 — API-092 처럼 등록되어 있지 않음)를 폴링해 항목별 실제 진행률을 그린다. 진행 상태 5종(RECEIVED/RUNNING/SUCCEEDED/FAILED/CANCELED) + 진행률 산출 불가 사유 4종(NOOP/TRANSIENT_ERROR/AWAITING_ACK/QUERY_LIMIT_EXCEEDED)을 사용자 언어로 구분 표시한다. 같은 패널 안에 **취소 버튼 + AugmentCancelModal**(REVIEWER 전용, `cancelable=true` 일 때만 노출, 부분 취소 결과 안내)까지 있다 — `POST /v1/augments/{id}/cancel` 은 SCREEN-023 어디에도 언급 없음. 두 프론트엔드(납품 FE `AugmentCancelDialog`, 테스트베드 FE `AugmentCancelModal`) 모두에 실장돼 있다.
- **근거**: `AugmentController.java`(`progress()`, `cancel()` 엔드포인트 전문 + Javadoc) · `features/augment/components/AugmentProgressPanel.tsx` · `features/augment/components/AugmentCancelModal.tsx` · 납품 FE `components/augment/AugmentCancelDialog.tsx`
- **조치 제안**: SCREEN-023 에 API-092 유사한 신규 API 아이디 2개(progress/cancel) 등록 후 `consumes_apis` 에 추가, "⑤ 활용 결정 카드" 옆(또는 신규 섹션 "⑤-1 진행상태/취소")에 AugmentProgressPanel·취소 다이얼로그를 컴포넌트로 명시. 5개 진행상태 라벨·4개 unavailableReason 안내문·부분취소 톤 분기까지 옵션으로 등록.
- **확신도**: high

### [GAP-D07] 복구(restore) 버튼에 `triggers_api` 없음 — API 카탈로그 자체에 restore 엔드포인트 미등록
- **위치**: "⑤ 활용 결정 카드" > components[5] "복구" 버튼
- **정의서 서술**: note 에 "POST /v1/augments/{id}/restore" 라고 프리텍스트로만 적혀 있고, 정작 컴포넌트 JSON 필드 `triggers_api` 는 비어 있다. `consumes_apis`(API-061/062/063)에도 restore 대응 API 아이디가 없다.
- **실제**: `POST /v1/augments/{id}/restore` 는 실재하는 엔드포인트이고(`AugmentController.restore`), 두 FE 모두 실제로 호출한다(`RestoreReasonModal`/납품 FE `AugmentDecisionCard onRequestRestore`).
- **근거**: `AugmentController.java`(`restore()`) · `features/augment/components/RestoreReasonModal.tsx` · 납품 FE `hooks/augment/useAugmentDecision.ts`
- **조치 제안**: restore 용 API 아이디를 신규 등록해 `triggers_api`/`consumes_apis` 에 연결. 사유 최대 길이(500자, RejectRequest 와 동일 상한)도 note 에 명시(현재 "사유 입력"이라고만 있고 상한 없음).
- **확신도**: high

### [ERR-D08] 잡 상태(status) "파생" 서술이 실제 구현과 다르다
- **위치**: "① 페이지 헤더" > components[3] "잡 상태" description, "② 작업 요약 카드" description
- **정의서 서술**: "상태는 API-061 응답 results로부터 파생(results 비면 PROCESSING, augmentedPairs 0이면 FAILED)."
- **실제**: `AugmentResultResponse` 에는 **BE 가 직접 집계해 내려주는 `status` 필드**(`COMPLETED|FAILED|PROCESSING`, `AugmentJobStatus` enum 값)가 있고, FE 는 이를 그대로 쓴다(`data.status ?? 'PROCESSING'`). "results 비면 PROCESSING, augmentedPairs 0이면 FAILED" 같은 클라이언트측 파생 로직은 현재 코드에 없다 — 과거 방식이 남아있는 서술로 보인다. 추가로 FE 는 취소 종결을 BE 가 COMPLETED 로 내려주는 것을 화면에서 CANCELED 로 보정 표시하는 별도 로직(`displayStatus`)까지 갖고 있는데 이 보정도 정의서에 없다.
- **근거**: `AugmentResultResponse.java`(status 필드, javadoc "집계 상태") · `pages/AugmentResultPage.tsx`(testbed, `summarize()` 함수 — "상태는 BE 집계값을 그대로 쓴다(results.length 로 파생하지 않는다)")
- **조치 제안**: "상태는 API-061 응답의 `status` 필드(BE 집계값)를 그대로 쓴다. 단, 사용자 취소로 종결된 경우 BE 집계는 COMPLETED 로 내려오므로 화면이 CANCELED 로 별도 보정 표시한다" 로 정정.
- **확신도**: high

### [STALE-D09] "라벨 무결성 %" 라벨·바인딩이 코드에서 명시적으로 폐기된 이름이다
- **위치**: "③ 잡 상태 배너 / 라벨 무결성" > components[4] "라벨 무결성 %"(`binds_to: summary.labelIntegrity`)
- **정의서 서술**: "COMPLETED — augmentedPairs/totalPairs" 비율로 "라벨 무결성 카드", 95%↑ 우수·85%↑ 양호 톤 분기.
- **실제**: 테스트베드 FE 코드 주석이 명시적으로 이 이름을 폐기 사유와 함께 기록하고 있다 — "이 값은 지금 화면에 로드된 프레임 쌍(현재 프레임 페이지) 중 증강 이미지가 있는 비율이다. 라벨을 검사한 값이 아니어서 구 이름('라벨 무결성')은 사실과 달랐고, 프레임 페이지를 넘길 때마다 값이 바뀌는데 잡 전체 지표처럼 보였다." 현재 표시명은 "증강 이미지 생성률"이며, 95/85 임계값(색상 톤)은 유지되지만 분모가 "잡 전체 augmentedPairs/totalPairs"가 아니라 "현재 로드된 프레임 페이지 내 쌍"이다.
- **근거**: `pages/AugmentResultPage.tsx`(testbed, `rateToneClass`, "증강 이미지 생성률" 렌더 + 위 주석)
- **조치 제안**: 라벨을 "증강 이미지 생성률"로, `binds_to` 를 "현재 프레임 페이지 로드분 기준 계산값(잡 전체 값 아님)"으로 정정. "라벨 무결성"이라는 이름 자체가 이 값의 실제 산출 근거(라벨 검사 아님)와 무관하므로 재사용 금지.
- **확신도**: high

### [ERR-D10] "작업 요약 카드" KeyValue 5칸 구성이 실제 두 구현 어느 쪽과도 다르다
- **위치**: "② 작업 요약 카드" > components[1] "작업 ID/증강 유형/대상 영상/총 처리 이미지/생성일"
- **정의서 서술**: 5칸 = 작업 ID · 증강 유형 · 대상 영상 · 총 처리 이미지 · 생성일.
- **실제**:
  - 테스트베드 FE 5칸: 작업 ID · 증강 유형 · 대상 영상 · **결과 항목**(건수) · **비교 프레임 쌍**(총 처리 이미지 아님, "생성일" 칸 자체가 없음). 코드 주석: "'총 처리 이미지'라는 잡 전체 이름으로 렌더해… 실제로 처리를 마쳐도 항상 '0장'이었다"며 명칭·집계축을 의도적으로 바꿈.
  - 납품 FE 5칸: 작업 ID · **결과 항목** · 대상 영상 · **비교 이미지** · 생성일("−" 고정, `meta.createdAt` 미공급).
  둘 다 "증강 유형"과 "총 처리 이미지"를 동시에 갖고 있지 않다.
- **근거**: `pages/AugmentResultPage.tsx`(testbed, `dl` grid-cols-5) · `components/augment/AugmentResultHeadline.tsx`(납품 FE, `SummaryItem` 5개)
- **조치 제안**: 두 구현 중 정본을 확정(테스트베드가 "잡 전체 vs 페이지 부분합" 문제를 더 명시적으로 다룸)한 뒤 5칸 필드명·의미·범위 표기("(이 페이지)" 등)를 정의서에 정확히 반영. "총 처리 이미지"라는 표현은 두 구현 모두에서 폐기됐으므로 정의서에서도 제거.
- **확신도**: high

### [CONFLICT-D11] 버튼 라벨 "거부" vs purpose 전반의 "반려" 용어 불일치
- **위치**: "⑤ 활용 결정 카드" components[2] 라벨 "거부"(및 description 전반 "거부"/"거부됨") vs purpose 최상단 문단들("반려하면", "반려 후 폐기·복구", "반려 자체를 되돌려")
- **정의서 서술**: 같은 화면 안에서 액션 명칭이 "거부"(버튼 라벨·컴포넌트 description)와 "반려"(purpose 서술, CLAUDE.md 전반 용어와 일치)로 혼재.
- **실제**: 두 프론트엔드가 이 혼선을 각기 다르게 반영했다 — 테스트베드 FE 버튼/문구는 "거부"("거부됨", "거부 사유")로 정의서와 같은 쪽을 따르고, 납품 FE 는 "반려"("반려", CLAUDE.md 의 검수 도메인 용어와 일치하는 쪽)로 구현했다. 같은 기능을 두 구현이 다른 한글 용어로 노출하는 근본 원인이 정의서 자체의 용어 불일치로 보인다.
- **근거**: `features/augment/components/DecisionCard.tsx`(testbed, "거부"/"거부됨"/"거부 사유") · `components/augment/AugmentDecisionCard.tsx`(납품, "반려") · SCREEN-023 raw JSON purpose 문단(반려 x7) vs section⑤ 컴포넌트/description(거부 x6)
- **조치 제안**: CLAUDE.md 도메인 용어("검수 반려")와 정합되는 "반려"로 화면 표기를 통일하고, purpose·컴포넌트 라벨·description 전체에서 "거부" 표현을 제거. 두 FE 구현 중 하나(납품 FE)로 표기 통일 필요.
- **확신도**: medium

### [GAP-D12] "생성된 파생 영상 보기" 링크(라벨링/검수 이동)가 정의서에 없음
- **위치**: "⑤ 활용 결정 카드(DecisionCard)"
- **실제**: 납품 FE `AugmentDecisionCard` 는 `derivativeRawSn` 이 있고 실삭제(discard.purged)되지 않았으면 `/video/{derivativeRawSn}` 로 이동하는 "생성된 파생 영상 보기" 링크를 그린다. `AugmentResultItemResponse.derivativeRawSn` 필드 자체가 "FE 가 파생본 라벨링/검수로 이동할 때 사용"이라고 명시돼 있어 의도된 기능이다.
- **근거**: `components/augment/AugmentDecisionCard.tsx`(derivativeLink 렌더) · `AugmentResultItemResponse.java`(derivativeRawSn javadoc)
- **조치 제안**: DecisionCard 컴포넌트 목록에 "파생 영상 보기 링크"(조건: derivativeRawSn != null && !discard.purged) 추가.
- **확신도**: medium (납품 FE 1곳에서만 확인 — 테스트베드 FE 에는 동일 링크 미확인, 화면 정본 판단 필요)

---

## 부록 — 확인했으나 정의서와 부합해 이견 없는 항목 (참고용)
- 파생영상 요청 거부 4조건과 그 판정 순서(파생 400 → 미검수 400 → 신고 412 → 프레임 미추출 412)는 `AugmentRequestService.request()` 실제 코드 순서와 정확히 일치.
- 중복 (영상×종류) 요청 무제한 허용, 409/유니크 인덱스 완전 제거는 코드로 확인됨(`createOneAugmentRequest` 주석에 "이 분기는 <중복 증강 차단이 아니다>" 명시).
- 처리종류 4카드 라벨(겨울/야간/우천/해상도 변경), radiogroup 로빙 tabindex, prompt 5필드 키(time/season/weather/terrain/severity), 해상도 3종 다중선택 기본 전체선택, 반려 사유 max 500자, 프레임 페이지 크기 12, 페이징 2축(page/size vs itemPage/itemSize) 설계는 모두 코드와 일치.
- 해상도 파생(RESL_*) 결정 카드 미노출 + BE 400 차단, resultState 8종 enum 값은 정의서·코드 완전 일치.
