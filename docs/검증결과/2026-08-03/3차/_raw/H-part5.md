# H 클러스터 part5 — H-11(포털 화면) + H-15(E2E 전체 사용자 시나리오) 검증 결과

- 회차: 2026-08-03 **3차** / 담당 범위: `docs/test-cases/H-frontend-e2e.md` **H-11**(347~373행) + **H-15**(427~437행)
- 대상 케이스: **28건** (H-11 22건 = TC-FE-165~177·271~275·TC-E2E-008~011 / H-15 6건 = TC-E2E-014~019). 폐기행 0건
- 이슈 ID: `H-ISSUE-81` ~
- 실행 금지 준수: 빌드·테스트(gradle/vitest/playwright) **미실행**. 실동작 확인은 HTTP 요청 + 브라우저(Playwright MCP) 조작 + DB 조회로만 수행

## ★ 최우선 결론 — 2차 HIGH 2건 해소 여부

| 2차 이슈 | 내용 | **3차 판정** |
|---|---|---|
| **H-ISSUE-103** (2차 HIGH #11) 포털 데이터마트 라벨링에 AI 분할·추적·스켈레톤 노출 | ADR-013 위반 | **✅ 해소 (FE 실동작 재확인)** |
| **H-ISSUE-143** (2차 HIGH #10) 전체 워크플로 E2E 픽스처(`WORKFLOW_VIDEO_ID=9035`) 부재 → 검수종결 E2E 커버리지 0 | 4건 연쇄 스킵 | **✅ 해소 (하드코딩 제거 + 실행시점 동적 해석, 전제 live 확인)** ⚠ 단 완주 실행은 이번 회차 규칙(실행 금지)상 미확인 |

### ① H-ISSUE-103 해소 근거 — FE단 실측 (F클러스터가 확인한 BE 완전제거와 동일축)

- **소스 단일 게이팅**: `frontend/src/features/label/types.ts:215-219`
  ```ts
  export const PORTAL_HIDDEN_TOOLS: readonly ToolType[] = [
    ToolType.SAM_SEGMENT, ToolType.TRACK, ToolType.KEYPOINT,
  ];
  ```
  `DarkToolbar.tsx:148-153` 이 이 단일 소스로 필터(`if (item.kind === 'tool') return !PORTAL_HIDDEN_TOOLS.includes(item.tool);`), 오토라벨(YOLO) 액션은 `portalHidden: true`(:135)로 별도 숨김.
- **실동작(브라우저, PORTAL_USER JWT `channel=PORTAL`, `role=PORTAL_USER`)** — 2회 독립 재현:
  - `/portal/label/468`(데이터마트 영상) `role=toolbar` 버튼 = **`선택, 바운딩 박스, 폴리곤, 삭제, 실행 취소, 화면 맞춤, 저장`**
    → `AI 분할`·`AI 추적`·`스켈레톤`·`AI 탐지` **0건**. 본문 텍스트에 `검수제출|시계열|VLM|버전` **0건**. 캔버스(`canvas-shell` 1개, `<canvas>` 3개) 정상 렌더 + 프레임 blob 이미지 10건 로드 성공.
  - `/portal/uploads/80/label`(본인 업로드 자산) 버튼 = **`내보내기(JSON), 원본 다운로드, 선택, 이동, 바운딩 박스, 폴리곤, 저장`** → AI 진입점 0건.
- **회귀 가드 존재**: `features/portal/__tests__/LabelingPagePortalRestrictions.test.tsx:110-123` 가 `queryByRole('button',{name:'AI 분할'|'AI 추적'|'스켈레톤'|'AI 탐지'}).toBeNull()` 로 **부재를 단언**(2차의 "노출을 기대값으로 고정"에서 반전 완료). 3차 baseline frontend 2,064건 전건 통과.
- ⇒ `UNCERTAINTIES.md` **#1(포털 SAM2 노출 = 결함 유지)** 은 FE 축에서도 **해소**. F-part1 의 원본 갱신 제안(“3차에서 ✅ 해소”)에 동의하며, 재도입 방지를 위해 **★확정 정책 절 승격**을 함께 권고한다(이 저장소의 ‘철회된 정책 재시도’ 차단 관례).

### ② H-ISSUE-143 해소 근거

- `frontend/e2e/fixtures/test-data.ts` 에서 구 상수 **삭제 확인**: `grep -n "WORKFLOW_VIDEO_ID\|WORKFLOW_SRC_SN"` → **0건**. 파일 상단 주석이 사고 경위(H-ISSUE-143)를 명시하고 하드코딩 금지를 선언.
- 대체 메커니즘: `resolveWorkflowFixture()`(`test-data.ts:116-128`)가 ①`GET /v1/assignments` 로 LABELER 배정 + `firstSrcSn` 보유 후보 수집 ②`GET /v1/reviews/{videoId}`·`GET /v1/frames/{srcSn}/labels` 로 신고게이트(412)·권한(403)·프레임부재(404) 후보 탈락 ③제출 가능 상태로 정규화(`cancel-submit`/`reject`) ④롤백용 커밋 2건 미달 시 승인 사이클로 적층 — 전부 **공개 API**, 테스트 백도어 없음. 스펙(`labeling-review-full-flow.spec.ts:27-36`)은 `beforeAll` 에서 이를 호출하고 180s 타임아웃을 설정.
- **전제 실동작 확인(현 스택)**:
  ```
  POST /api/v1/dev/tokens {role:WORKER,userNo:2001} → 200
  GET  /api/v1/assignments?page=0&size=5  (WORKER 2001) → 200, totalElements=49,
       content[0] = {id:76, videoId:115, taskTypeCd:"LABELER", firstSrcSn:508, ...}
  GET  /api/v1/frames/1/labels            (WORKER 2001) → 200
  ```
  ⇒ 후보 0건으로 인한 `pickCandidate` 실패는 현 시드에서 발생하지 않는다.
- 스펙 자체도 보강됨: 테스트 4건 → **9건**(픽스처 유효성 단언 `:53-83`, 최종 완주 단언 `:274-277` 신설). 단언이 전부 **무조건**(`toBeVisible`/`expect(status).toBe(200)`/정확한 토스트 문구)이라 2차의 "조건부 단언" 문제도 이 스펙에는 없다.
- ⚠ **남은 한계**: 이번 회차는 E2E 실행이 금지되어 **완주 자체는 미확인**이다. 또 현 로컬 스택 포트와 E2E 기본 대상이 어긋나 있어(→ H-ISSUE-84) 이 스펙은 **아직 한 번도 compose 스택 위에서 돌아본 적이 없다**. 다음 회차에서 `E2E_BE_URL`/`BASE_URL` 을 맞춰 1회 완주시켜야 “커버리지 0 → 실효 확보”가 최종 확정된다.

---

## 1. H-11. 포털 화면 (자산 업로드+수동 라벨링) — 22건

> 사용한 실데이터: 포털 업로드 자산 `uldSn=80`(`qa-portal.jpg`, READY)·`uldSn=81`(`qa <img src=x onerror=alert(1)>.jpg`, READY) / 데이터마트 영상 29건(대표 `rawSn=101`, `firstSrcSn=468`).
> ⚠ 검증 중 MCP 브라우저가 **다른 병렬 에이전트와 프로필을 공유**해 sessionStorage/localStorage 토큰이 도중에 REVIEWER·WORKER 토큰으로 교체되는 현상을 관측했다(→ `/portal/*` 이 `/forbidden` 으로 튐). **제품 결함이 아니라 검증 환경 아티팩트**임을 토큰 클레임 디코드로 확인했고, 스토리지 정리 후 재인계하여 모든 판정을 재현했다. 아래 판정은 전부 재현 후 값이다.

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-165 | PASS | [정적] `validation.ts:38-42` 확장자 allowlist(`IMAGE_EXTENSIONS=['jpg','jpeg','png']`) 위반 시 `errors.push(...IMAGE_POLICY_TEXT)` + `continue`(제외). [실동작] `/portal/uploads` 의 `input#portal-image-input accept="image/jpeg,image/png,.jpg,.jpeg,.png"`. 단위테스트 `validation.test.ts:21,28` |
| TC-FE-166 | PASS | [정적] `validation.ts:10` `MAX_IMAGE_BYTES=20*1024*1024`, `:43-46` 초과 시 "크기가 20MB를 초과했습니다" + 제외. 테스트 `validation.test.ts:36`. **근거 드리프트 1건 정정**(구 `:9`→`:10`) |
| TC-FE-167 | PASS | [정적] `validation.ts:12` `MAX_IMAGE_COUNT=50`, `:50-56` 초과 안내 + `valid: accepted.slice(0,50)`. 테스트 `validation.test.ts:42` |
| TC-FE-168 | PASS | [정적] `PortalUploadPage.tsx:77-87` 성공 후 `setSelected([])`·`setValidationErrors([])`·`imageInputRef.current.value=''` 3종 초기화. 테스트 `PortalUploadPage.test.tsx:72` |
| TC-FE-169 | PASS | [정적] `PortalUploadPage.tsx:90` `useTusUpload({ endpointBase: PORTAL_TUS_ENDPOINT })`, `:35` `PORTAL_TUS_ENDPOINT='/portal/uploads/tus'`, `:95-100` `tus.start(videoFile,...)`. [실동작] `input#portal-video-input accept="video/mp4,video/quicktime,video/x-msvideo,.mp4,.mov,.avi"`. **근거 드리프트 1건 정정**(`useTusUpload.ts:30`→`:36`) |
| TC-FE-170 | PASS | [정적] `PortalUploadPage.tsx:104` `window.confirm(...되돌릴 수 없습니다)`, `:310-311` `disabled={deleting \|\| isProcessing}` + `title="처리 중 자산은 삭제할 수 없습니다."`. 테스트 `PortalUploadPage.test.tsx:141,156`. (현 스택에 PROCESSING 자산이 없어 실동작 재현 불가 — 정적+단위테스트로 판정) |
| TC-FE-171 | PASS | [정적] `PortalUploadPage.tsx:48-54` `deleteErrorMessage` 가 409/CONFLICT 만 "처리 중 자산은 삭제할 수 없습니다.", 그 외는 일반 문구 — BE 내부 메시지 미전달(CWE-209 방어). 테스트 `PortalUploadPage.test.tsx:167` |
| TC-FE-172 | PASS | [실동작] `/portal/uploads` DOM 링크 = `["/portal","/portal/uploads/80/label","/portal/uploads/81/label"]` — READY 2건에만 라벨링 링크. [정적] `:296-306` `{isReady && <Link to={`/portal/uploads/${uldSn}/label`}>}` |
| TC-FE-173 | PASS | [정적] `PortalUploadPage.tsx:288-291` `{isFailed && ...(upload.failRsnCn ?? '처리에 실패했습니다...')}`. 테스트 `PortalUploadPage.test.tsx:178`. (FAILED 자산 부재로 실동작 재현 불가) |
| TC-FE-174 | PASS | [실동작] 업로드 자산명 `qa <img src=x onerror=alert(1)>.jpg` 가 **DOM 에 `&lt;img src=x` 로 escape 되어 존재**(`innerHTML.includes('&lt;img src=x') === true`), 스크립트 실행/이미지 태그 생성 0건. [정적] `:281` 텍스트 노드 렌더. 테스트 `PortalUploadPage.test.tsx:189` |
| TC-FE-175 | PASS | [실동작] `/portal/uploads/80/label` 도구 = `선택/이동/바운딩 박스/폴리곤` + `저장`, AI 진입점 0건. [정적] `PortalUploadLabelingPage.tsx:45-50` `UPLOAD_TOOLS` 4종 고정, `CanvasShell` 조립 |
| TC-FE-176 | PASS | [실동작] `/portal` 진입 → "AI 학습데이터 작성 포털 / 데이터마트 영상" 목록 렌더, 영상 29건(`GET /v1/portal/datamart/videos` totalElements=29 와 일치), 각 항목 선택 가능. 내비 링크 = `/portal`·`/portal/uploads` 2개뿐(내부 화면 링크 0건) |
| TC-FE-177 | PASS | [정적] `useSavePortalLabels.ts:1-45` — BE 계약 `POST /v1/portal/user-labels`(본인 작업분 `LS_PORTAL_USER_LABEL` 별도 적재, 원본 `LS_DATA_LBL` 미수정), 직렬화가 **BBOX/POLYGON 만** 허용하고 그 외 형태는 `null` 로 제외(BE allowlist 400 회피). 테스트 `features/portal/__tests__/useSavePortalLabels.test.tsx` |
| TC-FE-271 | PASS | [정적] `PortalUploadLabelingPage.tsx:371` `<BusyOverlay kind={busyKind} startedAt={busyStartedAt} onCancel={cancelBusy} />` 배선 + `:107` `cancelBusy` 를 store 에서 취득. 저장은 `useSaveUploadLabels` 가 `runExclusiveOrNotify('SAVE', ...)` 로 store busy 배타축에 올림(`:85`). 회귀 테스트 `PortalUploadLabelingBusy.test.tsx:146`("저장 중 진행 오버레이가 뜨고 취소할 수 있다") |
| TC-FE-272 | PASS | [정적] `useSaveUploadLabels.ts:94-98` `onSuccess: (result) => { if (result === null) return; options.onSuccess?.(); }` — 취소·폐기(null)면 `clearDirty` 등 성공 후처리 미실행. 캐시 무효화도 `isAlive()` 가드 안(`:88-90`). 테스트 `PortalUploadLabelingBusy.test.tsx:173` |
| TC-FE-273 | PASS | [정적] `busyPolicy.ts:69` `BUSY_OVERLAY_DELAY_MS = 300`, `BusyOverlay.tsx:48-66` 이 `startedAt` 기준 잔여시간만큼 `setTimeout` 후 `setVisible(true)` — 300ms 미만 저장은 오버레이 미표시. 테스트 `PortalUploadLabelingBusy.test.tsx:191` |
| TC-FE-274 | PASS | [실동작] `/portal/uploads/80/label` 전체 버튼 목록에 `AI 탐지/AI 분할/AI 추적/스켈레톤/오토라벨/SAM2/YOLO` **0건** → 관측 가능한 busy 는 SAVE 뿐. [정적] `UPLOAD_TOOLS`(:45-50)에 AI 도구 부재. 테스트 `PortalUploadLabelingBusy.test.tsx:197` |
| **TC-FE-275** | **PASS** | **[실동작]** 위 "★최우선 결론 ①" 참조 — 두 화면 모두 BBOX/POLYGON 만. ⚠ 2차 H-ISSUE-103 **해소 확정** |
| TC-E2E-008 | PASS | [실동작] PORTAL_USER JWT 로 `/ingress?token=` → `/portal` 자동 착지, 홈 렌더 확인. [정적] `portal-channel-guard.spec.ts:10` |
| TC-E2E-009 | PASS | [실동작] 포털 세션에서 `/dashboard` SPA 진입 → `location.pathname === '/forbidden'`. [정적] 스펙 `:15` + `router/guards.tsx:89` ChannelGuard |
| TC-E2E-010 | PASS | [실동작] `/manage/users` → `/forbidden`. 추가 반증으로 `/task`·`/label/468`(내부 라벨링) 도 `/forbidden` 확인 — 우회 경로 없음. [정적] 스펙 `:26` |
| **TC-E2E-011** | **FAIL** | [실동작] 스펙이 겨냥하는 `/portal`(홈)에 `input[type=file]` **0개**, `[data-testid=upload-dropzone]` **0개** → 첫 테스트의 `expect(visible).toBe(true)` 가 **실패**한다. 두 번째 테스트는 `if (fileInput.count()>0)` 안이라 통째로 미실행(공허 통과). 실제 업로드 UI 는 `/portal/uploads` 에만 있다(`#portal-image-input`/`#portal-video-input`). 2차 **H-ISSUE-101 미해소 이월** → **H-ISSUE-81** |

**H-11 집계: PASS 21 / FAIL 1 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0**

---

## 2. H-15. E2E 전체 사용자 시나리오 — 6건

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| **TC-E2E-014** | **FAIL** | [정적] `labeling-flow.spec.ts:30,35,39` 세 핵심 단계가 전부 `if ((await ...count()) > 0)` 조건부라 요소 미렌더 시 **아무 것도 검증하지 않고 통과**. [실동작] 더 근본적으로 대상 `srcSn=1` 은 이 스펙이 쓰는 `workerPage`(=`TEST_USERS.worker`, userNo **1003**)에게 **403**(`GET /api/v1/frames/1/labels` → `{"errorCode":"FORBIDDEN","message":"권한이 없습니다."}`)이라 캔버스 자체가 뜨지 않는다. 같은 프레임을 userNo **2001** 로 조회하면 200 — 즉 **픽스처 사용자와 대상 영상의 불일치**가 근본원인이며, 조건부 가드만 제거하면 이번엔 확정 실패한다. 2차 **H-ISSUE-141 미해소 이월 + 근본원인 신규 확인** → **H-ISSUE-82** |
| **TC-E2E-015** | **FAIL** | [정적] `worker-labeling.spec.ts:33-34` `const cnt = await labeling.bboxToolBtn.count(); expect(cnt).toBeGreaterThanOrEqual(0);` — **항상 참인 공허 단언**(count 는 음수가 될 수 없다). 기대결과 "바운딩박스 버튼 렌더"를 전혀 보장하지 않는다. TC-E2E-014 와 동일한 403 문제도 공유(`TEST_VIDEO_WITH_LABEL=1`, `workerPage`=1003). 2차 **H-ISSUE-142 미해소 이월** → **H-ISSUE-83** |
| TC-E2E-016 | PARTIAL | [정적+실동작] 차단 결함(H-ISSUE-143) 해소·전제 live 확인(위 ★결론 ②). 단 **완주 실행 미확인**(회차 규칙상 E2E 실행 금지) + 하네스 기본 포트 불일치(H-ISSUE-84)로 현 스택에서 즉시 실행 불가 → **H-ISSUE-86** |
| TC-E2E-017 | PARTIAL | [정적] `labeling-review-full-flow.spec.ts:186-216` — 이력 패널 열기 → 버전 탭 → 커밋 2건 이상 단언(`toBeGreaterThanOrEqual(2)`) → 롤백 트리거 → `POST .../versions/.../rollback` 200 단언. 단언은 전부 무조건. 픽스처가 커밋 2건을 사전 보장(`ensureRollbackableVersions`). **실행 미확인** → H-ISSUE-86 |
| TC-E2E-018 | PARTIAL | [정적] `:150-184` — 상세 진입 시 `POST /reviews/{id}/start` 응답 대기 → 반려 사유 입력 → `POST .../reject` 200 + 토스트 "반려 처리됨" 단언. **실행 미확인** → H-ISSUE-86 |
| TC-E2E-019 | PARTIAL | [정적] `:239-272` 승인(`POST .../approve` 200 + "승인 완료" 토스트) + `:274-277` 최종 `expect(await readStatus()).toBe('APPROVED')` — 카탈로그의 "APPROVED 전이" 정정 표현이 코드와 일치함을 재확인. **실행 미확인** → H-ISSUE-86 |

**H-15 집계: PASS 0 / FAIL 2 / PARTIAL 4 / BLOCKED 0 / N/A 0 / 확인필요 0**

---

## 3. 총 집계 (28건)

| 판정 | 건수 |
|---|---:|
| PASS | 21 |
| FAIL | 3 |
| PARTIAL | 4 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |

---

## 4. 이슈 대장

### [H-ISSUE-81] TC-E2E-011 — 포털 업로드 E2E 가 업로드 UI 가 없는 `/portal`(홈)을 겨냥해 첫 단언이 확정 실패한다 (2차 H-ISSUE-101 미해소 이월)
- **심각도**: HIGH
- **기대 동작(기대효과)**: 포털 자산 업로드(ADR-013 예외 경로)는 외부 채널이 파일을 반입하는 유일한 입구다. 업로드 화면의 dropzone/파일 입력 노출과 실제 업로드 시도가 E2E 로 보장되어야 회귀(라우트 변경·컴포넌트 이동)가 잡힌다.
- **현재 동작(이슈 내용)**: 스펙이 `PortalHomePage` POM 을 써서 `/portal` 로 이동한 뒤 업로드 요소를 찾는다.
  ```ts
  // e2e/specs/portal-upload.spec.ts:10-18
  const home = new PortalHomePage(portalPage);   // pages/PortalHomePage.ts:19 → pushState('/portal')
  await home.goto();
  const visible = (await home.dropzone.count()) > 0 || (await home.fileInput.count()) > 0;
  expect(visible).toBe(true);
  ```
  실측(PORTAL_USER 세션, 브라우저):
  ```
  /portal          → input[type=file] 0개, [data-testid=upload-dropzone] 0개   ← 단언 실패
  /portal/uploads  → input#portal-image-input(multiple, jpg/jpeg/png)
                     input#portal-video-input(mp4/mov/avi)
  ```
  두 번째 테스트(`:20-38`)는 `if ((await home.fileInput.count()) > 0)` 안에서만 동작하므로 **아무 것도 검증하지 않고 통과**한다. fixture `e2e/fixtures/sample.jpg.txt` 도 첫 줄이 `e2e mock placeholder file (not a real jpg)` 라 BE 매직바이트 검증을 통과할 수 없다.
- **재현/확인 경로**: PORTAL_USER JWT 로 `/ingress?token=...` → `/portal` 에서 `document.querySelectorAll('input[type=file]').length` → `0`. `/portal/uploads` 로 이동하면 `2`.
- **영향**: 기능 — 포털 업로드 화면의 E2E 보장이 0 이다(첫 테스트는 실패, 둘째는 공허). CI 에 E2E 를 붙이면 첫 테스트가 상시 red 라 스위트 자체가 무시되기 쉽다.
- **수정 방향(제안)**: ①`PortalUploadPage` POM 신설(또는 스펙이 직접 `/portal/uploads` 로 이동) ②`#portal-image-input`/`#portal-video-input` 을 명시 로케이터로 사용 ③`if (count>0)` 가드 제거하고 무조건 단언 ④fixture 를 실제 최소 JPEG(매직바이트 `FFD8FF`)로 교체. **본 검증에서는 수정하지 않음.**

### [H-ISSUE-82] TC-E2E-014 — 라벨링 플로우 E2E 의 핵심 단언이 전부 조건부이고, 대상 프레임이 픽스처 사용자에게 403 이라 실질 커버리지가 0 이다 (2차 H-ISSUE-141 미해소 + 근본원인 신규)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: WORKER 가 목록→캔버스→BBox 작성→저장→토스트까지 완주하는 것이 이 케이스의 보장 대상이다. 라벨링 캔버스는 이 제품의 핵심 화면이라 조건부가 아닌 확정 단언이 필요하다.
- **현재 동작(이슈 내용)**:
  ```ts
  // e2e/specs/labeling-flow.spec.ts:29-42
  if ((await labeling.bboxToolBtn.count()) > 0) { await labeling.bboxToolBtn.first().click(); }
  if ((await labeling.canvas.count()) > 0)      { await labeling.drawBoundingBox(...); }
  if ((await labeling.saveBtn.count()) > 0)     { await labeling.save(); await expect(...).toBeVisible(); }
  ```
  세 단계 모두 요소가 없으면 조용히 통과한다. 그리고 실제로 요소가 없다 — 이 스펙은 `workerPage`(`TEST_USERS.worker`, userNo **1003**)로 `srcSn=1` 에 진입하는데:
  ```
  POST /api/v1/dev/tokens {role:WORKER,userNo:1003}     → 200
  GET  /api/v1/frames/1/labels  (userNo 1003)           → 403 {"errorCode":"FORBIDDEN","message":"권한이 없습니다."}
  GET  /api/v1/frames/1/labels  (userNo 2001)           → 200
  ```
  즉 배정이 없는 사용자로 남의 프레임에 진입하므로 `LabelingPage.goto()` 의 `canvas-shell` 대기가 `.catch(() => undefined)` 로 삼켜지고(POM `:72-75`), 이후 3개 `if` 가 모두 false 가 된다.
- **재현/확인 경로**: 위 curl 3줄. 또는 `E2E_BE_URL`·`BASE_URL` 을 맞춘 뒤 `npx playwright test labeling-flow --reporter=list` 로 "통과하지만 아무 것도 안 한" 상태 확인.
- **영향**: 기능 — "E2E 스펙 11개 보유" 통계가 실제 보장과 어긋난다. 라벨링 저장 회귀가 이 스펙으로는 절대 잡히지 않는다.
- **수정 방향(제안)**: ①`labelerPage`(userNo 2001, 실제 LABELER 배정 보유)로 픽스처 사용자를 교체하거나, `resolveWorkflowFixture()` 와 같은 **동적 해석**으로 대상 프레임을 얻는다 ②`if (count>0)` 가드를 전부 제거하고 `toBeVisible()` + PUT 응답 200 + 정확한 토스트 문구('저장됨')로 단언한다(`labeling-review-full-flow.spec.ts:94-120` 이 이미 모범 사례다). **본 검증에서는 수정하지 않음.**

### [H-ISSUE-83] TC-E2E-015 — 바운딩박스 버튼 렌더 단언이 항상 참(`>= 0`)인 공허한 단언이다 (2차 H-ISSUE-142 미해소 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 라벨링 진입 시 도구바에 '바운딩 박스' 버튼이 실제로 렌더되어야 한다(포털 게이팅 회귀의 대조군이기도 하다 — 내부 채널에서는 도구가 살아 있어야 한다).
- **현재 동작(이슈 내용)**:
  ```ts
  // e2e/specs/worker-labeling.spec.ts:32-34
  // BBox 도구 버튼이 렌더 — 정확한 텍스트는 ... 변경 가능하므로 count 만 검증.
  const cnt = await labeling.bboxToolBtn.count();
  expect(cnt).toBeGreaterThanOrEqual(0);   // Locator.count() 는 음수가 될 수 없다 → 항상 참
  ```
  주석이 든 이유("정확한 텍스트가 변경 가능")도 현재는 성립하지 않는다 — POM `LabelingPage.ts:20` 이 `getByRole('button',{name:'바운딩 박스',exact:true})` 로 **접근성 이름을 이미 확정**해 쓰고 있고, `DarkToolbar` 의 표시명은 `TOOL_DISPLAY_NAME` 단일 출처에서 파생된다.
- **재현/확인 경로**: 해당 3줄 코드. 도구바를 통째로 제거해도 이 테스트는 통과한다.
- **영향**: 회귀 — 내부 라벨링 도구바가 사라져도 잡히지 않는다. 포털 게이팅(`PORTAL_HIDDEN_TOOLS`)을 확장하다가 내부 채널까지 숨기는 실수를 이 E2E 로는 못 잡는다(현재는 vitest `DarkToolbar.test.tsx` 만이 대조군 역할).
- **수정 방향(제안)**: `await expect(labeling.bboxToolBtn).toHaveCount(1)` 또는 `toBeVisible()` 로 교체. 함께 H-ISSUE-82 의 픽스처 사용자 문제(userNo 1003 → 403)도 해결해야 실효가 생긴다. **본 검증에서는 수정하지 않음.**

### [H-ISSUE-84] (H-15 전반) E2E 하네스의 기본 대상 주소가 로컬 compose 스택과 어긋나 있어 E2E 가 회귀 baseline 에 한 번도 포함된 적이 없다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 검수 종결 워크플로를 유일하게 종단 검증하는 자산(TC-E2E-016~019)이 로컬 표준 스택 위에서 그대로 실행 가능해야 한다. 실행되지 않는 E2E 는 커버리지가 아니라 장식이다.
- **현재 동작(이슈 내용)**:
  ```
  e2e/fixtures/be-client.ts:48   export const BE_BASE = process.env.E2E_BE_URL || 'http://127.0.0.1:8080';
  playwright.config.ts:20        baseURL: process.env.BASE_URL || 'http://127.0.0.1:5174'
  playwright.config.ts:26-31     webServer: { command: 'npm run dev', url: 'http://127.0.0.1:5174' }
  ```
  실제 검증 스택(docker compose, `_raw/stack-bringup.md`)은 **backend `18081:8080`**, **frontend `13000:5174`** 로 발행된다. 로컬에 8080 리스너 없음(`lsof -nP -iTCP:8080 -sTCP:LISTEN` → 0건). 따라서 환경변수 없이 `npm run e2e` 를 돌리면 `issueDevToken` 이 ECONNREFUSED 로 죽고 모든 스펙이 실패한다.
  또 3차 `_raw/test-baseline.md` 에는 backend(5,203)·frontend vitest(2,064)·ai-server(145)만 있고 **Playwright 실행 기록이 없다** — 1~3차 어느 회차에도 E2E baseline 이 없다.
- **재현/확인 경로**: `cd frontend && npx playwright test --list` 는 통과하지만, 실행하면 `be-client.ts` 의 토큰 발급에서 즉시 실패. `E2E_BE_URL=http://127.0.0.1:18081 BASE_URL=http://127.0.0.1:13000` 을 주면 대상이 맞는다(단, vite dev 프록시의 `BACKEND_ORIGIN` 도 함께 맞춰야 함).
- **영향**: 기능/프로세스 — H-ISSUE-143 을 고쳐 픽스처를 동적화했지만 **한 번도 실행으로 확인된 적이 없다**. 다음 회차에서도 같은 이유로 PARTIAL 이 반복될 위험.
- **수정 방향(제안)**: ①`.env.e2e`(또는 `package.json` 의 `e2e:local` 스크립트)에 compose 포트를 기본값으로 고정 ②`playwright.config.ts` 의 `webServer.command` 를 `reuseExistingServer` 와 함께 compose FE(13000) 를 쓰도록 선택 가능하게 ③검증 회차 §3-2 baseline 에 **Playwright 실행을 항목으로 추가**(현재 backend/frontend/ai-server 3종만). **본 검증에서는 수정하지 않음.**

### [H-ISSUE-85] (TC-E2E-014/015 인접) `TEST_VIDEO_WITH_LABEL = 1` 하드코딩이 남아 픽스처 파일의 자기 선언과 모순된다
- **심각도**: LOW
- **기대 동작(기대효과)**: `e2e/fixtures/test-data.ts:9-13` 이 스스로 *"⚠ 영상(rawSn)·프레임(srcSn) 은 하드코딩하지 않는다 — 시드가 재적재되면 PK 가 통째로 바뀌어 스펙 전체가 404 로 죽는다(H-ISSUE-143 실사고)"* 라고 선언했다. 같은 파일의 다른 상수도 그 규칙을 따라야 한다.
- **현재 동작(이슈 내용)**:
  ```ts
  // e2e/fixtures/test-data.ts:53-54
  /** 시드 데이터의 srcSn 1~5 에 라벨 존재 — 라벨링 진입 테스트는 1 사용. */
  export const TEST_VIDEO_WITH_LABEL = 1;
  ```
  `worker-labeling.spec.ts` 가 이 상수를 쓰는데, 현 스택에서 srcSn=1 은 **userNo 1003 에게 403**(H-ISSUE-82). H-ISSUE-143 과 동일한 실패 모드(하드코딩 PK + 시드 변화)가 축소된 형태로 남아 있다.
- **재현/확인 경로**: `GET /api/v1/frames/1/labels` 를 userNo 1003 / 2001 토큰으로 각각 호출 → 403 / 200.
- **영향**: 회귀 — 시드가 바뀌면 같은 방식으로 다시 깨진다. 현재는 조건부 단언(H-ISSUE-82/83) 때문에 실패조차 하지 않고 침묵한다.
- **수정 방향(제안)**: `resolveWorkflowFixture()` 와 같은 방식으로 "라벨을 보유하고 현재 사용자가 접근 가능한 프레임"을 API 로 해석하는 경량 헬퍼(`resolveLabelableFrame()`)를 만들어 상수를 대체한다. **본 검증에서는 수정하지 않음.**

### [H-ISSUE-86] TC-E2E-016/017/018/019 — 차단 결함은 해소됐으나 완주 실행이 이번 회차에서 확인되지 않았다 (PARTIAL 사유)
- **심각도**: LOW (검증 갭 — 제품 결함 아님)
- **기대 동작(기대효과)**: 라벨링→저장→제출→반려→롤백→재제출→승인 전 구간이 실제 스택에서 완주되어야 한다.
- **현재 동작(이슈 내용)**: 스펙·픽스처는 정상화됐고(H-ISSUE-143 해소) 전제도 실동작으로 확인됐다(WORKER 2001 배정 49건·`firstSrcSn` 보유·`/v1/frames/{srcSn}/labels` 200). 그러나 ①본 회차 규칙이 테스트 실행을 금지하고 ②하네스 기본 대상 포트가 스택과 어긋나(H-ISSUE-84) 즉시 실행이 불가해, **완주 여부는 미확인**이다.
- **재현/확인 경로**: `E2E_BE_URL=http://127.0.0.1:18081 BASE_URL=http://127.0.0.1:13000 npx playwright test labeling-review-full-flow --reporter=list` (다음 회차 또는 별도 실행 사이클에서 1회 완주 필요).
- **영향**: 검증 신뢰도 — "검수 종결 E2E 커버리지 확보"가 코드 근거로만 성립하고 실행 근거가 없다.
- **수정 방향(제안)**: H-ISSUE-84 를 먼저 처리한 뒤 이 4건을 실행해 PASS 로 승격. 실행 결과를 `_raw/test-baseline.md` 에 E2E 섹션으로 추가.

### [H-ISSUE-87] (카탈로그 정합성) H-11·H-15 근거 `file:line` 드리프트 5건 + TC-E2E-016 전제 stale — **이번 회차에서 직접 정정 완료**
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 존재 이유가 근거 정확도다(루트 `CLAUDE.md` 문서 동기화 규칙 — 2026-08-03 확정).
- **현재 동작(이슈 내용)**: 아래 6건이 실제 코드와 어긋나 있었다.

  | 케이스 | 구 근거/전제 | 실제 | 조치 |
  |---|---|---|---|
  | TC-FE-165 | `validation.ts:33-49` | `validateImageFiles` 본문 33-57, 확장자 분기 38-42 | `:33-48` 로 정정 + 범위 주석 |
  | TC-FE-166 | `validation.ts:9,44` | `MAX_IMAGE_BYTES` 는 `:10`(`:9` 는 JSDoc) | `:10,44` 로 정정 |
  | TC-FE-169 | `useTusUpload.ts:30` | `endpointBase` 옵션은 `:36`(`:30` 은 JSDoc) | `:36` 으로 정정 |
  | TC-FE-170 | `PortalUploadPage.tsx:103-105,309-311` | `window.confirm` 은 `:104`, `disabled`/`title` 은 `:310-311` | `:103-104,310-311` 로 정정 |
  | TC-FE-275 | `DarkToolbar.tsx:110-112,148-154` | 필터 블록은 `:148-153` | `:148-153` 으로 정정 |
  | TC-E2E-016 | 전제 "WORKER+REVIEWER / serial" (구 하드코딩 픽스처 전제) | 픽스처가 `resolveWorkflowFixture()` 동적 해석으로 교체, 테스트 4→9건 | 전제에 동적 픽스처·구 상수 삭제 명시 + 근거에 `test-data.ts:61-64,116-128` 추가 |
  | TC-E2E-019 | `spec.ts:239-277` | 승인 단계 `:239-272`, 최종 `APPROVED` 단언 `:274-277` | `:239-272,274-277` 로 분리 표기 |

- **영향**: 카탈로그 정합성. 특히 TC-E2E-016 전제는 **이미 폐기된 하드코딩 픽스처 전제**를 들고 있어, 다음 회차가 H-ISSUE-143 을 "미해소"로 오판할 소지가 있었다.
- **수정 방향(제안)**: 이번 회차에서 담당 라인범위(H-11·H-15) 안에서 **Edit 로 직접 정정 완료**. 프로덕션 코드는 수정하지 않았다. 변경 이력 표(파일 상단)에 5회차 행 추가는 병합 담당(PM)이 전 파트 정정 건수를 합산해 기재할 것을 권고.

---

## 5. 판정 시 적용한 확정 정책(★) / UNCERTAINTIES 확인

- **UNCERTAINTIES #1(포털 SAM2 노출)** — 본 파트 FE 축에서 **해소 확인**. 원본 갱신 제안: *"1차/2차 결함 → 3차(2026-08-03, `dcdbb827`)에서 BE 삭제 + FE `PORTAL_HIDDEN_TOOLS` 단일소스 게이팅으로 ✅ 해소. FE 실동작 재확인(3차 H-part5): `/portal/label/:id`·`/portal/uploads/:uldSn/label` 양쪽 도구바에 AI 분할·추적·스켈레톤·탐지 0건."* 아울러 **★확정 정책 절 승격**(포털 SAM2 재도입 금지)을 F-part1 과 함께 권고한다.
- **UNCERTAINTIES #12(포털 rate limit 부재)** — 본 파트 범위(FE) 밖. 판정 변경 없음.
- **★1~★5** — 본 범위(H-11·H-15)에는 해당 케이스가 없어 적용 대상 없음. 위 이슈 중 어느 것도 ★ 정책을 되돌리자는 제안이 아니다.

## 6. 검증 환경 비고 (판정 무효화 아님, 다음 회차 주의)

- **MCP 브라우저 프로필 공유로 인한 세션 오염**: 검증 도중 sessionStorage `klid_jwt` 가 다른 병렬 에이전트가 인계한 REVIEWER(`sub=1001`)/WORKER(`sub=2001, channel=INTERNAL`) 토큰으로 교체되어, 정상 동작하는 `/portal`·`/portal/uploads` 가 `/forbidden` 으로 튀는 **위양성 FAIL** 이 관측됐다. 토큰 클레임 디코드로 원인을 특정하고 스토리지 정리 후 재현하여 전건 정정했다. 앞으로 **FE 실동작 검증 파트를 병렬로 배정할 때는 브라우저 컨텍스트를 분리**하거나 직렬화할 것.
- 잔여 localStorage 키 `klid-jwt-token`/`klid-user-id`/`klid-authority`(관제 공유 스토리지 채널)가 sessionStorage 보다 우선 해석되는 정황도 함께 관측됐다 — 2차 H-ISSUE-01/04(`VITE_TOKEN_INGRESS=all` fail-open)의 실증 사례로, 해당 이슈 담당 파트에 참고 정보로 이월한다(본 파트 범위 밖이라 별도 이슈로 기록하지 않음).
