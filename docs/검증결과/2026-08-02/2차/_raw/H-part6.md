# H 클러스터 검증 결과 — part6 (H-10 증강/해상도 파생 화면 14건 + H-11 포털 화면 22건 = 36건)

- **대상**: `docs/test-cases/H-frontend-e2e.md` `## H-10. 증강/해상도 파생 화면`(TC-FE-157~164, 209~213, TC-E2E-007) + `## H-11. 포털 화면`(TC-FE-165~177, 271~275, TC-E2E-008~011)
- **검증일**: 2026-08-02 (2차)
- **환경**: frontend `localhost:13000` · backend `localhost:18081/api` (실기동, `/actuator/health` UP). 코드 기준 `qa-0801`
- **인증**: BE `POST /v1/dev/tokens` 실서명 JWT → `/ingress?token=` (REVIEWER userNo=1001 / PORTAL_USER userNo=2001)
- **검증 방식**: Playwright MCP 실브라우저 조작(실동작) 우선 + `file:line` 정적 대조 + FE 단위테스트 인벤토리 대조
- **판정 집계**: 총 36건 — **PASS 32 · PARTIAL 3 · FAIL 1** (FAIL/PARTIAL 4건 = 이슈 H-ISSUE-101~105)

## ⚠ 검증 환경 특기사항 (판정 신뢰도에 영향)

1. **Playwright MCP 브라우저가 다른 병렬 에이전트와 공유**되어 `sessionStorage['klid_jwt']`·현재 탭이 수시로 덮어써졌다. `navigate → tabs.select(내 탭) → evaluate(SPA pushState + DOM 수집)` 순서로 재시도해 **모든 실동작 판정은 URL·역할(`role` 클레임)을 응답에 함께 실어 검증**했다. 아래 `[실동작]` 표기 건은 전부 대상 URL 이 응답에 확인된 회차의 결과다.
2. **검증 중 수행한 데이터 변경**(실동작 확인 목적, 전부 QA 데이터):
   - 포털 이미지 2건 업로드(`uldSn=80`, `81` — 81은 XSS 페이로드 파일명)
   - 포털 user-label 1건 저장(`userLblSn=20`, rawSn=94/srcSn=448)
   - 증강 `dataAugSn=8`(job 18 NIGHT) **PENDING→ACCEPTED** (되돌릴 수 없음 — `applyReviewStatus` 는 PENDING 에서만 전이)
   - 증강 `dataAugSn=4`(job 4 RAIN #2) PENDING→REJECTED→**복구(PENDING)** — 원상복구 완료
   - 해상도 파생 1건 생성(원본 rawSn=94 → 파생 **rawSn=100**, 480P, 검수 대기)

---

## 1. H-10. 증강/해상도 파생 화면 (14건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|------|------|
| TC-FE-157 | PASS | [실동작] | `/augment` 진입 시 `radiogroup "처리 종류"` 안에 **ProcessKindCard 4종**(겨울·야간·우천·해상도 변경)이 `role=radio` 로 렌더 → 단일 선택. 해상도 변경 선택 시에만 "생성할 해상도(파생영상)" 체크박스 3종(1080P/720P/480P, 기본 전체 체크) 노출. 근거 `features/augment/types.ts:24-46` · `pages/AugmentRequestPage.tsx:63-69,428` |
| TC-FE-158 | PASS | [실동작] | 종류=해상도 변경, 프리셋 480P 만 체크, 영상 `#94` 선택 → [처리 요청] → 화면에 **"파생영상 1건 생성됨 — 검수 대기 / 480P (854×480) / 검수 대기 (영상 #100)"**. `useResolutionDerivative.ts:17-27` 이 성공 시 `queryClient.invalidateQueries({queryKey: VIDEO_KEYS.all})` 수행(근거 라인 정확) |
| TC-FE-159 | PASS | [실동작] | `/augment` 최근 요청 이력에서 jobId 81 카드가 `해상도 1080p`·`해상도 720p`·`해상도 480p` 3배지 렌더. 배지 클래스 `border-info/30 bg-info/10 text-info`(info 톤), 문구는 `resolutionDerivativeLabel(code)` 한글 라벨. ⚠ **근거 드리프트**: 카탈로그 `JobCard.tsx:64-72` → 실제 `JobCard.tsx:81-89` |
| TC-FE-160 | PASS | [실동작] | jobId 81(RESL 3항목, `reviewable=false`) 결과 화면 → 3탭 전부에서 `[data-testid=decision-card]` **미렌더**(`decisionCard:false`). jobId 4 의 `해상도 480p` 탭에서도 동일. 판정 로직은 `AugmentResultPanel.tsx:123-128`(`isResolution` + `reviewable`) → `:237-252` 조건부 렌더. JobCard 에는 원래 결정 액션 없음(`JobCard.tsx` 전체에 accept/reject 부재) 확인. ⚠ **근거 드리프트**: 카탈로그 `AugmentResultPage.tsx:419-421,452-461`(해당 파일은 422줄이고 DecisionCard 배선이 없음) → 실제 `features/augment/components/AugmentResultPanel.tsx:123-128,237-252` |
| TC-FE-161 | PASS | [실동작] | jobId 906·900(`types=[]`, `resolutionTypes=["RESL_480P"]`/`["RESL_720P"]`) 카드에 해상도 배지 + **"파생"** 구분자 동시 렌더. ⚠ **근거 드리프트**: `JobCard.tsx:75-83` → 실제 `:91-98` |
| TC-FE-162 | PASS | [실동작] | jobId 18 `야간` 탭 = `data-decision="PENDING"` + [채택]/[거부] 버튼 → [채택] 클릭 → **`data-decision="ACCEPTED"` + "채택됨 / 결정 일시: 2026. 8. 2. 오후 6:42:53"**, 토스트 "채택 처리됨". 훅 `useAugmentDecision.ts:57-67`(성공 시 `AUGMENT_KEYS.all` 무효화) |
| TC-FE-163 | PASS | [실동작] | jobId 4 `비 #2`(PENDING) → [거부] → `role=dialog` 에 "거부 사유 입력 / 이 증강 결과를 거부하는 사유를 입력하세요. / 거부 사유 / 취소 / 거부 확정". 사유 입력 후 [거부 확정] → `data-decision="REJECTED"` 전이 확인 |
| TC-FE-164 | PASS | [실동작] | 거부 사유에 `<img src=x onerror="window.__xss=1">QA검증` 입력·저장 → `[data-testid=decision-reject-reason]` 의 `innerHTML` = `거부 사유: &lt;img src=x onerror="window.__xss=1"&gt;QA검증`(escape), 주입된 `img` 0개, `window.__xss` 미정의 → **스크립트 미실행**. 코드에도 `dangerouslySetInnerHTML` 실사용 0건(주석뿐) |
| TC-FE-209 | PASS | [실동작] | jobId 81(RESL_*) 결과 화면의 비교 이미지가 전부 `src="blob:http://localhost:13000/…"` (raw `<img src="/v1/frames/…">` 아님), `alt`="원본 프레임 0" / "해상도 1080p 프레임 0". `FrameGrid12`·`SideBySideCompare` 에 `authImages` prop 전달(`AugmentResultPanel.tsx:175,194`) → `AuthImage`(Bearer blob). ⚠ **근거 드리프트**: `AugmentResultPage.tsx:425-450` → 실제 `AugmentResultPanel.tsx:170-196`. `FrameGrid12.tsx:156-167` → 실제 `:158`(근사), `SideBySideCompare.tsx:88-98` → `:90`(정확) |
| TC-FE-210 | PASS | [실동작] | jobId 4 `겨울 #1`(totalFramePairs=30) → `[data-testid=augment-frame-pager]` 노출, 버튼 `1/2/3`. 2페이지 클릭 시 그리드가 `frame-pair-351`~`362`(다음 12쌍)로 교체 → **접근 불가 프레임 0**. `page`/`size` 는 `getAugmentResult` 쿼리 파라미터(기본 `FRAME_PAGE_SIZE=12`). ⚠ **근거 드리프트**: `AugmentResultPage.tsx:33,414-441` → 실제 `AugmentResultPanel.tsx:30(FRAME_PAGE_SIZE),113,177-186` |
| TC-FE-211 | PASS | [실동작] | jobId 4 에서 `겨울 #1` 프레임 2페이지로 이동한 뒤 `해상도 480p` 탭 클릭 → framePage 리셋되어 `frame-pair-145`~`156`(그 항목의 1페이지) 표시, 빈 그리드 없음. 마운트 시점 리셋 안 함(`prevIdRef` 비교) + 안전망 `framePage>0` 이면 페이저 유지(`AugmentResultPanel.tsx:113`) 확인. ⚠ **근거 드리프트**: `AugmentResultPage.tsx:344-349,418` → 실제 `AugmentVideoSection.tsx:59-64` + `AugmentResultPage.tsx:120-124`(항목 페이저 전환 시 리셋) |
| TC-FE-212 | PASS | [실동작] | 탭 라벨·슬롯 라벨·요약이 모두 "해상도 1080p / 해상도 720p / 해상도 480p"(기술코드 `RESL_*` 노출 0). `augTypeLabel.ts:30-36` 근거 라인 **정확**. 미지 코드→`'증강'`, `RESL_` 접두→`resolutionDerivativeLabel` 폴백 구현 확인 |
| **TC-FE-213** | **PARTIAL** | [실동작] | ①`augment-result-completed-empty` 미노출 = **충족**(jobId 18·81 모두 COMPLETED + results 존재 시 미렌더, `AugmentResultPage.tsx:263-274`) ②"총 처리 이미지 = 페이징 전 전체 쌍 수" = **충족**(라벨명만 "비교 프레임 쌍"으로 바뀜. jobId 4 = 120쌍 = 현재 항목 페이지 항목들의 `totalFramePairs` 합, jobId 81 = 15쌍 = 5+5+5. `totalPairsOf()` 가 `totalFramePairs` 사용 — `resultView.ts:44-47`) ③**"라벨 무결성 카드 표시" = 불충족** — 그 카드는 더 이상 없다. `augment-result-integrity` 는 **"증강 이미지 생성률"** 로 이름·의미가 바뀌었고(분모 = 현재 프레임 페이지에 로드된 쌍), **`status==='COMPLETED' && hasFramePairs` 일 때만** 렌더된다. 프레임 쌍 0인 COMPLETED 잡(jobId 18)에서는 카드 자체가 없다 → 카탈로그 기대값이 구현에 뒤처짐(H-ISSUE-104). ⚠ 근거 드리프트: `:36-38,89-99,242-254` → 실제 `:263-274`(empty), `:282-296`(생성률), `:186-196`(쌍 수) |
| **TC-E2E-007** | **PARTIAL** | [실동작]+[정적] | 수동 실동작으로는 PASS(TC-FE-162 참조). 그러나 **자동 스펙이 사실상 아무것도 단언하지 않는다** — `e2e/specs/augment-decision.spec.ts:6` 이 `/augment/result/3001` 로 이동하는데 실 DB 에 jobId 3001 이 없어 BE 가 `{status:"PROCESSING", results:[]}` 를 주고, 스펙은 `if ((await adoptBtn.count()) > 0)` 가드 안에서만 단언하므로 **무단언 통과**(H-ISSUE-102) |

---

## 2. H-11. 포털 화면 (자산 업로드 + 수동 라벨링) (22건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|------|------|
| TC-FE-165 | PASS | [정적]+[테스트] | `features/portal/uploads/validation.ts:33-48` — 확장자 allowlist(`IMAGE_EXTENSIONS=['jpg','jpeg','png']`) 위반 파일 제외 + `IMAGE_POLICY_TEXT` 정책 안내 동봉. 화면 실측: `/portal/uploads` 안내문 "허용: jpg/jpeg/png · 개당 최대 20MB · 요청당 최대 50장 (최종 검증은 서버가 수행합니다)". 단위테스트 `validation.test.ts:21,28` |
| TC-FE-166 | PASS | [정적]+[테스트] | `validation.ts:10(MAX_IMAGE_BYTES=20*1024*1024),43-45` → "크기가 20MB를 초과했습니다." + 정책문. 테스트 `validation.test.ts:36` |
| TC-FE-167 | PASS | [정적]+[테스트] | `validation.ts:12(MAX_IMAGE_COUNT=50),50-56` → 초과 안내 후 `accepted.slice(0,50)`. 테스트 `validation.test.ts:42` |
| TC-FE-168 | PASS | [정적]+[테스트] | `pages/portal/PortalUploadPage.tsx:77-87`(근거 라인 **정확**) — `uploadAsync` 성공 후 `setSelected([])`·`setValidationErrors([])`·`imageInputRef.current.value=''`. 테스트 `PortalUploadPage.test.tsx:72`. (실브라우저 파일 선택은 공유 브라우저 경합으로 미수행) |
| TC-FE-169 | PASS | [실동작]+[정적] | 화면 실측: `#portal-video-input` `accept="video/mp4,video/quicktime,video/x-msvideo,.mp4,.mov,.avi"`, 안내 "mp4/mov/avi · 최대 5GB (재개 가능 업로드)". 배선 `PortalUploadPage.tsx:35(PORTAL_TUS_ENDPOINT='/portal/uploads/tus'),89-100` → `useTusUpload({endpointBase})`(`features/upload/hooks/useTusUpload.ts:29-31,34-35`) |
| TC-FE-170 | PASS | [정적]+[테스트] | `PortalUploadPage.tsx:270-271`(`isProcessing` 판정, 근거 정확) → `:310 disabled={deleting||isProcessing}` + `:311 title="처리 중 자산은 삭제할 수 없습니다."`. 테스트 `PortalUploadPage.test.tsx:156`. ⚠ 실동작 미확인 — 이미지 업로드가 즉시 READY 로 끝나 PROCESSING 상태 자산을 만들 수 없었다 |
| TC-FE-171 | PASS | [정적]+[테스트] | `PortalUploadPage.tsx:48-53`(근거 정확) — 409/`ErrorCode.CONFLICT` 만 "처리 중 자산은 삭제할 수 없습니다.", 그 외 일반 문구. **BE 원문 메시지·스택 미노출**(CWE-209 방어). 테스트 `PortalUploadPage.test.tsx:167` |
| TC-FE-172 | PASS | [실동작] | `/portal/uploads` 에 READY 자산 2건 → 각 항목에 `<a href="/portal/uploads/80/label">라벨링</a>`, `/portal/uploads/81/label`. 근거 `:296-306` 정확 |
| TC-FE-173 | PASS | [정적]+[테스트] | `PortalUploadPage.tsx:288-292`(정확) — `isFailed` 시 `failRsnCn ?? 기본 문구`. 테스트 `PortalUploadPage.test.tsx:178`. (FAILED 자산 미보유로 실동작 미확인) |
| TC-FE-174 | PASS | [실동작] | 파일명 `qa <img src=x onerror=alert(1)>.jpg` 로 업로드 → 목록 `innerHTML` = `qa &lt;img src=x onerror=alert(1)&gt;.jpg`(escape), `li img` 0개, alert 미발화. `aria-label` 도 텍스트 속성으로만 사용. 근거 `:280-281` 정확 |
| TC-FE-175 | PASS | [실동작] | `/portal/uploads/80/label` 진입 → `role=toolbar` 버튼 = **선택 / 이동 / 바운딩 박스 / 폴리곤 4종뿐**, `<canvas>` 렌더(CanvasShell). 본문에 "AI 탐지/AI 분할/AI 추적/스켈레톤/오토라벨" 문구 0건, "검수/버전/승인/반려" 0건. 근거 `PortalUploadLabelingPage.tsx:43-49(UPLOAD_TOOLS)` |
| TC-FE-176 | PASS | [실동작] | `/portal` 진입(PORTAL_USER) → "AI 학습데이터 작성 포털" + "데이터마트 영상" 목록 12건 노출(BE `GET /v1/portal/datamart/videos` totalElements=20), 각 영상 선택 버튼 + "라벨링 가능 20건 / 시작하기" 카드. 상단 내비는 `/portal`·`/portal/uploads` **2개뿐**(내부 화면 진입점 0) |
| TC-FE-177 | PASS | [실동작] | 포털 저장 경로 실왕복: `POST /v1/portal/user-labels {sourceRawSn:94, sourceSrcSn:448, BBOX}` → `userLblSn=20` 적재, `GET /v1/portal/user-labels?rawSn=94` 에 1건. **동일 시점 내부 라벨 `GET /v1/frames/448/labels` = items 0건 · labelVersion 0 (변화 없음)** → 원본(LS_DATA_LBL) 미수정 확인. 훅 `useSavePortalLabels.ts:78-112`(busy 'SAVE' 배타 + 폐기 시 성공 후처리 skip) |
| TC-FE-271 | PASS | [정적]+[테스트] | `PortalUploadLabelingPage.tsx:370` `<BusyOverlay kind={busyKind} startedAt={busyStartedAt} onCancel={cancelBusy} />`, busy 는 프레임 스코프(`isEditBlockedState(s, uldFrmeSn)`, `:100-106`)로 필터. 취소 시 dirty 유지(성공 후처리 미실행 — 아래 272). 테스트 `PortalUploadLabelingBusy.test.tsx:146` |
| TC-FE-272 | PASS | [정적]+[테스트] | `features/portal/uploads/hooks/useSaveUploadLabels.ts:85-98`(근거 **정확**) — `runExclusiveOrNotify` 의 `isAlive()` 가드로 캐시 무효화 차단, `onSuccess(result)` 가 `result===null` 이면 즉시 return → `clearDirty()` 미호출. 테스트 `PortalUploadLabelingBusy.test.tsx:173` |
| TC-FE-273 | PASS | [정적]+[테스트] | `features/label/busyPolicy.ts:69 BUSY_OVERLAY_DELAY_MS = 300`(근거 **정확**) + `isBusyOverlayVisible` 파생. 테스트 `PortalUploadLabelingBusy.test.tsx:191` |
| TC-FE-274 | PASS | [실동작] | `/portal/uploads/80/label` 전체 버튼 = 내보내기(JSON)·원본 다운로드·선택·이동·바운딩 박스·폴리곤·저장. **AI 진입점 0** → 관측 가능한 busy 는 SAVE 뿐. 테스트 `PortalUploadLabelingBusy.test.tsx:197`. ⚠ 근거 드리프트: 카탈로그 `:42-48` → 실제 `:43-49` |
| **TC-FE-275** | **PARTIAL** | [실동작] | 경계 구분 자체는 **충족** — `/portal/label/448`(데이터마트 영상)은 도구 = 선택·바운딩 박스·폴리곤·**AI 분할·AI 추적·스켈레톤**, **AI 탐지 없음**(`DarkToolbar.tsx:118-132 portalHidden:true`, 필터 `:141-147`). `/portal/uploads/:uldSn/label`(본인 업로드)은 AI 0. 양쪽 모두 저장 배타·오버레이·취소를 store busy 단일 축으로 공유. **그러나 `PORTAL_HIDDEN_TOOLS` 가 빈 배열이라 AI 분할·추적·스켈레톤이 포털에 노출되는 것은 `CLAUDE.md`/ADR-013 "포털 오토라벨링(YOLO/SAM2)·VLM 미제공" 위반**이며 `UNCERTAINTIES.md` #1 이 **결함(FAIL) 유지**로 확정한 항목 → 이월(H-ISSUE-103) |
| TC-E2E-008 | PASS | [실동작] | PORTAL_USER 토큰으로 `/portal` 진입 → `location.pathname='/portal'`, h1 "AI 학습데이터 작성 포털" |
| TC-E2E-009 | PASS | [실동작] | PORTAL_USER 가 `/dashboard` 진입 시도 → `/forbidden` ("이 화면에 접근할 수 없습니다 / 현재 역할: 포털") |
| TC-E2E-010 | PASS | [실동작] | `/manage/users` → `/forbidden`. 추가 반증: `/review/pending`·`/augment` 도 `/forbidden`, `/portal/uploads` 는 정상 진입 → 가드가 경로 축으로 정확히 동작 |
| **TC-E2E-011** | **FAIL** | [실동작]+[정적] | 화면 자체는 정상(`/portal/uploads` 에 이미지·영상 파일 입력 + 정책 안내가 실제로 있음 — TC-FE-165/169 참조). 그러나 **E2E 스펙이 업로드 화면을 겨냥하지 않는다** — `e2e/pages/PortalHomePage.ts:19-25 goto()` 가 `/portal` 로만 이동하는데 `PortalHomePage.tsx`(134줄)에는 `input[type=file]` 도 `data-testid="upload-dropzone"` 도 **없다**(실브라우저 실측 및 grep 확인. `upload-dropzone` 문자열은 `PortalHomePage.test.tsx:51` 의 "없어야 한다" 단언에만 존재). 따라서 `portal-upload.spec.ts:10-18` 의 `expect(visible).toBe(true)` 는 **거짓**이 되어야 하고, `:20-38` 의 업로드 시도는 `if (count>0)` 가드로 무단언 통과한다(H-ISSUE-101) |

---

## 3. 이슈 기록

### [H-ISSUE-101] TC-E2E-011 — 포털 업로드 E2E 스펙이 업로드 화면이 아닌 포털 홈을 겨냥한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `/portal/uploads` 의 파일 선택 영역 노출과 실제 업로드 흐름이 E2E 로 회귀 보호돼야 한다. ADR-013 예외로 신설된 포털 자산 업로드는 내부 파이프라인과 분리된 별도 경로라 단위테스트만으로는 라우팅·가드·화면 조립이 보장되지 않는다.
- **현재 동작(이슈 내용)**:
  - `frontend/e2e/pages/PortalHomePage.ts:19-25`
    ```ts
    async goto() {
      await this.page.evaluate(() => {
        if (window.location.pathname !== '/portal') {
          window.history.pushState({}, '', '/portal');   // ← 업로드 화면이 아니라 포털 홈
    ```
  - `frontend/e2e/specs/portal-upload.spec.ts:10-18` 이 그 POM 으로 이동한 뒤 `expect((await home.dropzone.count()) > 0 || (await home.fileInput.count()) > 0).toBe(true)` 를 단언한다.
  - 실측(Playwright, PORTAL_USER): `/portal` 본문에 `input[type=file]` 0개(버튼만: "시작하기 ▶", 영상 선택 카드 12개). `grep -rn "upload-dropzone" frontend/src` 결과는 `pages/portal/__tests__/PortalHomePage.test.tsx:51`(= `toBeNull()` 단언) 1건뿐.
  - `frontend/src/pages/portal/PortalHomePage.tsx`(134줄)에 `input` 문자열 0건.
  - 두 번째 테스트(`:20-38`)는 `if ((await home.fileInput.count()) > 0)` 안에서만 단언하므로 파일 입력이 없으면 **아무 것도 검증하지 않고 통과**한다. fixture `e2e/fixtures/sample.jpg.txt` 도 첫 줄이 `e2e mock placeholder file (not a real jpg)` 라 실제 이미지가 아니다(BE 매직바이트 검증 통과 불가).
- **재현/확인 경로**: `cd frontend && npx playwright test e2e/specs/portal-upload.spec.ts` → 첫 테스트 실패 예상. 또는 브라우저로 `/portal` 진입 후 `document.querySelectorAll('input[type=file]').length` → `0`.
- **영향**: 포털 업로드 화면의 E2E 회귀 보호가 **0**이다. 라우팅·`ChannelGuard`·업로드 UI 조립이 깨져도 CI 가 잡지 못한다(기능 결함은 아니며 테스트 자산 결함).
- **수정 방향(제안)**: ① `PortalHomePage` POM 을 그대로 두고 `PortalUploadPage` POM 을 신설해 `/portal/uploads` 로 이동시키거나, `portal-upload.spec.ts` 가 직접 `/portal/uploads` 로 이동하도록 변경 ② `input#portal-image-input` / `input#portal-video-input` 을 명시 로케이터로 사용 ③ `if (count>0)` 가드를 제거하고 무조건 단언 ④ fixture 를 실제 최소 JPEG(매직바이트 `FFD8FF`) 로 교체.

### [H-ISSUE-102] TC-E2E-007 — 증강 채택 E2E 가 존재하지 않는 jobId 를 대상으로 해 무단언 통과한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: PENDING 증강 결과를 채택하면 ACCEPTED 로 전이되는 흐름이 E2E 로 보호돼야 한다(REVIEWER 검수 결정 경로).
- **현재 동작(이슈 내용)**:
  - `frontend/e2e/specs/augment-decision.spec.ts:6-18`
    ```ts
    await reviewerPage.goto('/augment/result/3001');
    const adoptBtn = reviewerPage.getByRole('button', { name: /채택/ });
    if ((await adoptBtn.count()) > 0) {   // ← 0 이면 통째로 스킵
      await adoptBtn.first().click();
    ```
  - 실측: `GET /api/v1/augments/3001/result` → `{"jobId":3001,"status":"PROCESSING","results":[],...}`. 결과 항목이 0건이라 DecisionCard 자체가 렌더되지 않고 `adoptBtn.count()===0` → 단언 미실행.
  - 실 DB 의 잡 ID 는 `4·18·26·50·80·81·900·906·77777777` 이며 3001 은 없다(`GET /v1/augments` 실측 totalElements=9).
- **재현/확인 경로**: `curl -s localhost:18081/api/v1/augments/3001/result -H "Authorization: Bearer <REVIEWER JWT>"`
- **영향**: 채택 상태전이 회귀가 CI 에서 잡히지 않는다(수동 실동작 검증에서는 정상 동작 확인됨 — TC-FE-162).
- **수정 방향(제안)**: 스펙 안에서 `POST /v1/augments/request` 로 PENDING 항목을 만들거나 시드 고정 잡을 쓰고, `if (count>0)` 가드를 제거해 `expect(adoptBtn).toBeVisible()` → 클릭 → `expect(getByText('채택됨')).toBeVisible()` 로 무조건 단언.

### [H-ISSUE-103] TC-FE-275 — 포털 데이터마트 라벨링에 AI 분할·추적·스켈레톤 도구가 노출된다 (ADR-013 위반, 이월)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `CLAUDE.md` "포털 — 오토라벨링(YOLO/SAM2)·VLM·버전관리·검수 미제공" + ADR-013 에 따라 포털 채널에서는 AI 보조 도구가 제공되지 않아야 한다. `UNCERTAINTIES.md` #1 이 "문서가 정본, 노출은 정책 위반 → 결함 플래그 유지"로 확정.
- **현재 동작(이슈 내용)**: `frontend/src/features/label/components/DarkToolbar.tsx:103-105,141-147`
  ```ts
  // Phase 9 — 포털에 SAM 분할/추적·키포인트 도구 제공(PORTAL_HIDDEN_TOOLS 현재 비어있음).
  ...
  if (item.kind === 'tool') return !PORTAL_HIDDEN_TOOLS.includes(item.tool);
  ```
  실측(PORTAL_USER, `/portal/label/448`): `role=toolbar` 버튼 = `선택, 바운딩 박스, 폴리곤, AI 분할, AI 추적, 스켈레톤, 삭제, 실행 취소, 화면 맞춤, 저장`. 단축키 안내도 노출(`AI 분할 G`, `AI 추적 Shift+T`, `스켈레톤 K`). AI 탐지(YOLO)만 `portalHidden:true` 로 숨겨짐.
- **재현/확인 경로**: PORTAL_USER JWT 로 `/portal/label/{srcSn}` 진입 → 좌측 도구바에 "AI 분할"·"AI 추적"·"스켈레톤" 버튼 확인.
- **영향**: 범위 정책 위반(요구사항 불일치). 포털 사용자가 ai-server 추론 자원을 소비할 수 있다(외부 채널 자원 소모 + `UNCERTAINTIES` #12 에 따라 해당 경로에 rate limit 부재). 전송 픽셀은 비식별본(`encodeDeidentifiedFrameForInference`)이고 신고 게이트도 적용되므로 PII 노출 위험은 완화된 상태.
- **수정 방향(제안)**: `PORTAL_HIDDEN_TOOLS` 에 `SAM_SEGMENT`·`TRACK`·`KEYPOINT` 를 추가하고 `useLabelingShortcuts` 의 포털 게이팅 정책 소스와 동기화. **또는** 정책을 바꿀 거라면 `CLAUDE.md`·ADR-013·`UNCERTAINTIES.md` #1 을 먼저 갱신(문서가 정본이므로 코드 단독 선행 금지).

### [H-ISSUE-104] TC-FE-213 — 카탈로그 기대값 "라벨 무결성 카드"가 구현에 존재하지 않는다 (카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스의 기대결과가 현재 구현을 정확히 서술해야 다음 회차 재검증이 성립한다.
- **현재 동작(이슈 내용)**: `frontend/src/pages/AugmentResultPage.tsx:276-296`
  ```tsx
  {summary.status === 'COMPLETED' && summary.hasFramePairs && (
    <div ... data-testid="augment-result-integrity">
      <p ...>증강 이미지 생성률</p>
      ...현재 화면에 표시된 프레임 {summary.loadedPairs}쌍 기준입니다.
  ```
  "라벨 무결성"이라는 이름·개념은 코드에서 제거됐고(주석에 "라벨을 검사한 값이 아니어서 구 이름은 사실과 달랐다"고 명시), 카드는 **프레임 쌍이 있는 COMPLETED 잡에서만** 렌더된다. 실측: jobId 81 → "증강 이미지 생성률 100% / 현재 화면에 표시된 프레임 15쌍 기준입니다.", jobId 18(쌍 0건) → 카드 없음. 또 "총 처리 이미지"는 "비교 프레임 쌍"으로 개명됐다(값 자체는 기대대로 페이징 전 전체 쌍 수).
- **재현/확인 경로**: `/augment/result/18` 진입 → `document.querySelector('[data-testid=augment-result-integrity]')` → `null`.
- **영향**: 케이스를 문자 그대로 판정하면 오검(FAIL)이 난다. 기능 결함 아님.
- **수정 방향(제안)**: `H-frontend-e2e.md` TC-FE-213 기대결과를 "`augment-result-completed-empty` 미노출 + (프레임 쌍이 있을 때만) `augment-result-integrity`= '증강 이미지 생성률' 카드 표시 + '비교 프레임 쌍' = 페이징 전 전체 쌍 수"로 갱신.

### [H-ISSUE-105] H-10/H-11 근거 `file:line` 드리프트 8건 (카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 링크가 실제 구현 위치를 가리켜야 재검증 비용이 들지 않는다.
- **현재 동작(이슈 내용)**: 아래 8건이 어긋난다. 특히 TC-FE-160/209/210/211 은 **파일 자체가 바뀌었다**(증강 결과 화면이 `AugmentResultPage` → `AugmentVideoSection` → `AugmentResultPanel` 로 분해되면서 프레임 페이징·DecisionCard·authImages 배선이 전부 패널로 이동. `AugmentResultPage.tsx` 는 422줄이라 `452-461` 은 존재하지 않는 라인이다).

  | TC | 카탈로그 근거 | 실제 위치 |
  |----|------|------|
  | TC-FE-159 | `JobCard.tsx:64-72` | `JobCard.tsx:81-89` |
  | TC-FE-160 | `AugmentResultPage.tsx:419-421,452-461` | `features/augment/components/AugmentResultPanel.tsx:123-128,237-252` |
  | TC-FE-161 | `JobCard.tsx:75-83` | `JobCard.tsx:91-98` |
  | TC-FE-209 | `AugmentResultPage.tsx:425-450` · `FrameGrid12.tsx:156-167` | `AugmentResultPanel.tsx:170-196` · `FrameGrid12.tsx:158`(`SideBySideCompare.tsx:90` 은 정확) |
  | TC-FE-210 | `AugmentResultPage.tsx:33,414-441` | `AugmentResultPanel.tsx:30,113,177-186` |
  | TC-FE-211 | `AugmentResultPage.tsx:344-349,418` | `AugmentVideoSection.tsx:59-64` · `AugmentResultPage.tsx:120-124` |
  | TC-FE-213 | `AugmentResultPage.tsx:36-38,89-99,242-254` | `AugmentResultPage.tsx:186-196,263-274,282-296` |
  | TC-FE-274 | `PortalUploadLabelingPage.tsx:42-48` | `PortalUploadLabelingPage.tsx:43-49` |

  정확했던 근거(참고): `augTypeLabel.ts:30-36` · `useResolutionDerivative.ts:17-27` · `validation.ts:33-49/9,44/12,50-56` · `PortalUploadPage.tsx:48-53,77-87,270-271,288-292,296-306,280-281` · `useSaveUploadLabels.ts:85-98` · `busyPolicy.ts:69` · `SideBySideCompare.tsx:88-98`.
- **재현/확인 경로**: 각 파일을 Read 해 라인 대조.
- **영향**: 검증자가 매번 Grep 으로 재탐색해야 한다. 기능 결함 아님.
- **수정 방향(제안)**: `H-frontend-e2e.md` H-10/H-11 근거 컬럼을 위 표대로 갱신.

---

## 4. 확증편향 반증 시도 기록 (요구 지시 대응)

| 반증 대상 | 시도 | 결과 |
|------|------|------|
| **증강 폐기/복구가 실제로 도는가** | jobId 4 `비 #2` 를 UI 로 거부(REJECTED) → `[data-testid=decision-restore]` 클릭 → 복구 사유 모달("결과물을 다시 활용 결정 대기로 되돌립니다…") 입력 → [복구 확정] | **정상 동작** — `data-decision` REJECTED→**PENDING**, 탭 라벨도 "비 #2 · 거부됨"→"비 #2 · 활용 결정 대기", 토스트 "복구 처리됨". `restoreEligible=false` 인 항목(id 3 = GENERATION_FAILED, id 14 = ACCEPTED)에는 복구 버튼이 아예 안 뜬다(fail-closed 확인) |
| **진행률이 자체 생성(self-fill)인가** | jobId 4 항목 14 의 `augment-progress-*` 실측 | **BE 실값** — "진행 상태 완료 / 진행률 100% / 처리 완료 1 / 1건". `AugmentResultPage.tsx:200-206` 주석대로 잡 단위 가짜 진행률(0/50/100)을 그리지 않고 항목별 `GET /v1/augments/{id}/progress` 를 쓴다. 해상도 파생 항목은 `enabled={!isResolution}` 으로 폴링 자체를 안 함 |
| **생성 조건(prompt)도 self-fill 인가** | 같은 항목 `augment-prompt-14` | **BE 값 그대로** — `{"time":"NIGHT","season":"WINTER","weather":"RAIN","terrain":"ROAD","severity":"HIGH"}` 를 "시간대 NIGHT / 계절 WINTER / …" 로 표시. 값 없는 항목(`prompt:null`)은 카드를 그리지 않음 |
| **포털에 오토라벨/검수/버전관리 버튼이 정말 없는가** | `/portal`·`/portal/uploads`·`/portal/uploads/80/label` 3화면 본문 정규식 검사 | 업로드 라벨링: `AI 탐지\|AI 분할\|AI 추적\|스켈레톤\|오토라벨` **0건**, `검수\|버전\|승인\|반려` **0건**. 포털 홈 내비 링크 = `/portal`·`/portal/uploads` 2개뿐. ⚠ **단 `/portal/label/:srcSn`(데이터마트 영상)에는 AI 분할·추적·스켈레톤이 노출** → H-ISSUE-103 |
| **해상도 파생에 채택/거부가 새는 경로가 있는가** | jobId 81 3탭 + jobId 4 의 RESL 3탭 전수 + JobCard 전체 코드 | `decision-card` 0건. BE 도 `reviewable=false` 로 내려줌. 다만 `GET /v1/augments/4/result` 실측상 RESL 항목의 `decision` 은 `ACCEPTED` 로 채워져 내려오는데(내부 생성물 라이프사이클), 화면은 `isResolution` 우선 판정이라 표시되지 않는다 — 의도된 동작 |
| **프레임 페이저로 도달 불가한 프레임이 있는가** | 30쌍 항목에서 페이지 1→2 이동, 12쌍 슬라이스 경계 확인 | 1페이지 `frame-pair-339~350`, 2페이지 `frame-pair-351~362`, 페이저 버튼 `1/2/3` → 30쌍 전부 도달 가능 |
| **탭 전환 시 빈 그리드에 갇히는가** | 30쌍 항목 2페이지 → 5쌍짜리 480P 탭 전환 | framePage 리셋되어 정상 렌더(갇힘 없음). 안전망 `emptyFramePage` + "첫 페이지로" 버튼도 코드상 존재(`AugmentResultPanel.tsx:198-228`) |
| **XSS 가 실제로 실행되는가** | ①증강 거부 사유 `<img src=x onerror="window.__xss=1">` ②포털 업로드 파일명 `qa <img src=x onerror=alert(1)>.jpg` | 둘 다 **escape 되어 텍스트로 렌더**, DOM 에 주입 `img` 0개, 스크립트 미실행 |
| **포털 저장이 원본을 건드리는가** | 포털 user-label 저장 전/후 내부 `GET /v1/frames/448/labels` 대조 | items 0 → 0, labelVersion 0 → 0 (**불변**). 포털 데이터는 `LS_PORTAL_USER_LABEL` 에만 적재 |
| **포털 채널 가드가 경로별로 다 도는가** | `/dashboard`·`/manage/users`·`/review/pending`·`/augment` 4경로 시도 | 전부 `/forbidden`. 포털 전용 `/portal`·`/portal/uploads` 만 통과 |
