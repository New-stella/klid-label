# F 클러스터 part3 — `F-4. 포털 SAM2` (TC-PORTAL-060~078, 19건)

- 검증일: 2026-08-02 (2차)
- 대상 파일: `docs/test-cases/F-portal.md` § F-4
- 코드 기준: 워크트리 `qa-0801`
- 실동작 환경: backend `localhost:18081/api` · ai-server `localhost:19300`(`AI_MOCK_MODE=false`, `source=model` 실측) · PostgreSQL `klid-postgres/klid_system/public`
- 토큰: `POST /api/v1/dev/tokens` — `{"role":"PORTAL_USER","channel":"PORTAL","sub":...}`

## 0. 사용한 실데이터 (DB 실측)

| rawSn | 작업상태 | `DE_IDENT_YN` | 파생 | 프레임 | 용도 |
|---|---|---|---|---|---|
| 4 | APPROVED | Y | - | srcSn 1~30 (raw+deid 양쪽 존재, md5 상이) | 정상 경로 |
| 18 | APPROVED | Y | `ORGNL_RAW_SN=4` | srcSn 45~74 (**`SRC_FILE_PATH_NM` NULL**, deid만) | 비식별본 전용 전송 실증 |
| 900 | APPROVED | **F**(신고 구간) | - | srcSn 429~433 | 신고 게이트 |
| 902 | APPROVED | Y | - | srcSn 445 (**raw 존재 / deid NULL**) | 원본 폴백 금지 실증 |
| 27 | ASSIGNED | Y | - | srcSn 301 | 비APPROVED 거부 |

> raw/deid 바이트 상이 확인: `raw/frames/raw/4/frame-0.jpg` 9,441B `be89bcc1…` vs `deidentified/frames/deid/4/frame-0.jpg` 13,164B `efadbd02…`

## 1. 판정 결과

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-PORTAL-060 | FAIL | [실동작] | **정책 위반 재확인 — 엔드포인트 여전히 살아있고 실동작한다.** `POST /api/v1/portal/frames/1/sam2-segment` (PORTAL 토큰) → **HTTP 200**, `polygon` 176점 실좌표 반환. ai-server 실추론(`source=model`) 경유. → **F-ISSUE-41** |
| TC-PORTAL-061 | FAIL | [실동작] | 동상. `POST /v1/portal/frames/1/sam2-track` (next=[2,3]) → **200**, `tracked` 2건 실좌표. `ls_data_lbl` 에 `trck_id like 'qa-track%'` 0건 = persist 없음(설계대로)이나 **기능 노출 자체가 정책 위반**. → **F-ISSUE-41** |
| TC-PORTAL-062 | PASS | [실동작] | srcSn=301(raw27 ASSIGNED) segment/track 모두 **403** `"데이터마트에 노출되지 않은 영상입니다."` — `requireExposedFrame` → `isDatamartExposed`(행 부재/타상태 = fail-closed) `PortalSam2Service.java:236-251` |
| TC-PORTAL-063 | PASS | [실동작] | srcSn=99999999 → **404** `"프레임을 찾을 수 없습니다."` `PortalSam2Service.java:237-238` |
| TC-PORTAL-064 | PASS | [실동작] | path=1 / body srcSn=2 → segment·track **양쪽 400** `"path 의 srcSn 과 body 의 srcSn 이 다릅니다."` (CWE-345) `PortalSam2Controller.java:86-90` |
| TC-PORTAL-065 | PASS | [정적] | `aiRes.untrusted()`(`AiMockMeta` 긍정증명) → `Sam2SegmentResponse.empty()` + 컨트롤러가 `MOCK_UNAVAILABLE_MESSAGE` 세팅 `PortalSam2Service.java:127-131` / `PortalSam2Controller.java:80-82`. track 은 해당 프레임 제외 + `anyMock` 안내 `:187-195`. **live 미재현 사유**: ai-server 실효 `AI_MOCK_MODE=false` + 가중치 로드 상태(직접 호출 실측 `{"mock":false,"source":"model"}`) — mock 유도는 설정 변경이 필요해 §10 절대규칙상 금지. 단위테스트 4건 커버(`PortalSam2ServiceTest#segment_mock_returnsEmptyPolygon`, `#segment_omittedMockMeta_returnsEmptyPolygon`, `#track_mockResponse_excluded`, `#track_omittedMockMeta_excluded`) |
| TC-PORTAL-066 | PASS | [실동작] | 동시 10요청(단일 포털 사용자 9002, srcSn=1) → **정확히 4건 200 / 6건 429** `"SAM2 동시 요청이 많습니다…"`. `application.yml:675-677` `portalSam2.max-concurrent-calls=4, max-wait-duration=0` 과 정확히 일치 |
| TC-PORTAL-067 | PASS | [실동작] | 신규 사용자(sub=9001) 35연속 요청 → 1~26 정상(404), **27번째부터 429** `"SAM2 요청이 너무 많습니다…"`. permit 획득이 프레임 조회보다 앞(`:111`)이라 존재하지 않는 프레임으로도 소진됨 = 설계대로. config `limit-for-period:30 / 1m / timeout 0`(`application.yml:684-687`) |
| TC-PORTAL-068 | PASS | [실동작] | nextSrcSns 50건(2~30 + 45~65) track → **HTTP 429, 소요 60.87s**, 본문 `"SAM2 추적 처리 시간이 초과되었습니다…"`. backend 로그 `[Portal][Sam2Track] wall-clock budget exceeded — abort processed=42/50`. `TRACK_WALL_CLOCK_BUDGET=60s` (`:87`, 체크 `:163-168`) |
| TC-PORTAL-069 | PASS | [실동작]+[정적] | 동일 검증기 `validatePolygon`(`:253-272`) 실동작 확인 — 음수 좌표 `[[-5,10],…]` → **400** `"폴리곤 좌표는 0 이상이어야 합니다."`, `NaN` 리터럴은 Jackson 파싱 단계 **400**. AI 응답에도 같은 검증기 적용됨(segment `:132`, track `:182`) — `!Double.isFinite` + `<0` 차단 |
| TC-PORTAL-070 | PARTIAL | [정적] | `aiRes == null \|\| aiRes.polygon() == null` → `EXTERNAL_API_ERROR`(=502) `:121-123`, `:179-181`; `catch RuntimeException` → 502 `:225-229`. **다만 `polygon: []`(빈 리스트, non-null)** 은 이 분기를 통과해 `validatePolygon` 에서 **400 INVALID_INPUT** 이 되어 기대결과(`EXTERNAL_API_ERROR`)와 다르다. 포털 SAM2 경로에 대한 이 축의 자동테스트도 0건. → **F-ISSUE-43**(LOW) |
| TC-PORTAL-071 | PASS | [실동작] | `trackId="qa\r\nINJECTED_FAKE_LOG_LINE"` 로 track 호출 → backend 로그 한 줄로 `trackId=qaINJECTED_FAKE_LOG_LINE` (CR/LF 제거). `LogSanitizer.sanitize` `:205-206` (CWE-117) |
| TC-PORTAL-072 | FAIL | [정적] | **정책 위반 재확인.** `LabelingPagePortalRestrictions.test.tsx:108-124` 가 포털 모드에서 `AI 분할`·`AI 추적`·`스켈레톤` 버튼 **존재를 단언**한다(=노출이 기대값으로 고정됨). FE→BE 배선도 실재: `features/label/api.ts:604-610,812-823` 가 `portalMode` 면 `/portal/frames/{id}/sam2-*` 로 분기. `AI 탐지`(YOLO)만 숨김. → **F-ISSUE-42** |
| TC-PORTAL-073 | PASS | [정적] | `LabelingPage.tsx:1200` 제출 영역이 `isWorker && data` 조건 — PORTAL 토큰은 role=PORTAL_USER 라 `submit-review-button` 미렌더. 테스트 `LabelingPagePortalRestrictions.test.tsx:86-91` 가 `queryByTestId('submit-review-button')` null 단언 |
| TC-PORTAL-074 | PASS | [정적] | `LabelingPage.tsx:474-475` `showMeta = !portalMode`, `:1472` VLM/시계열 메타 블록은 내부 채널만 렌더. 테스트 `:93-105` 가 `시계열 메타`·`VLM` 텍스트/탭 부재 단언(300ms 대기 후 재확인) |
| TC-PORTAL-075 | PASS | [실동작] | **비식별본 전용 + 원본 폴백 금지 양방향 실증.** ①srcSn=45(raw18, `SRC_FILE_PATH_NM` **NULL**, deid 존재) → **200** 실좌표 = 원본 경로 없이도 동작 = 비식별 경로를 씀. ②srcSn=445(raw902, **raw 파일 9,037B 실재 / deid NULL**) → **404** `"비식별 프레임이 존재하지 않습니다."` 이며 **ai-server 호출 0건**(로그 라인 델타로 확인) = 원본으로 폴백하지 않음. 코드 `PortalSam2Service.java:116` → `FrameImageEncoder.encodeDeidentifiedFrameForInference:148-152` → `resolveDeidentifiedFrame:155-170`(폴백 없음). 단위테스트 `AiInferenceDeidentReportGateTest.java:416-433` 이 전송 base64 를 캡처해 `deid 바이트와 동일 && 원본 바이트와 상이` 를 단언 |
| TC-PORTAL-076 | PASS | [실동작] | srcSn=429(raw900 `DE_IDENT_YN='F'`) segment → **412 PRECONDITION_FAILED** `"비식별 재처리 대기 중인 영상은 AI 추론을 실행할 수 없습니다."`, **ai-server 로그 라인 델타 0** = 파일 읽기·전송 전 차단. `FrameImageEncoder:149,206-217` → `DeidentReportGate.isUnderDeidentReport`(자기 rawSn 단일 컬럼, 캐시 없음) |
| TC-PORTAL-077 | PASS | [실동작] | ①srcSn=429 track → **412 + ai 호출 0건**. ②**프레임별 재판정 적대검증**: start=1(정상) / next=[2, 429(신고), 3] → 프레임2 처리 후 **412 로 중단**, ai-server 호출 델타 **정확히 1** = 신고 프레임 및 그 이후(3)는 전송되지 않음. 코드 `PortalSam2Service.java:170-172`(루프 내 `encodeDeidentifiedFrameForInference` 재호출) |
| TC-PORTAL-078 | PASS | [정적] | `grep -rn "encodeToBase64" backend/src` → **주석·javadoc 5건뿐, 메서드 정의·호출 0건**. `FrameImageEncoder` public 표면 = `resolveFrameImageForInference(:133)` / `encodeDeidentifiedFrameForInference(:148)` / `encodeFrame(:178)` 3종이며 모두 `LsDataSrc` 를 받아 게이트를 통과한 뒤 private `encode(Path)(:190)` 로 수렴. 게이트 없는 `resolveFrameImageWithoutGate(:94)` 는 **package-private** 이고 패키지 외부 소비자 0건(유일 호출부 `FrameBoundsResolver.java:101` 은 동일 패키지·치수 판독 전용) |

### 집계
| 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| 19 | 15 | 3 | 1 | 0 | 0 | 0 |

## 2. 확정 정책 재확인 결과 (지시 3축)

| 축 | 결과 |
|---|---|
| ① 포털에서 SAM2 API 가 **아직도 호출 가능한가** | **그렇다.** PORTAL 채널 토큰으로 segment·track 모두 HTTP 200 + 실좌표. 기능 토글·프로파일 가드 없음(`application.yml` 에 `portalSam2` 는 bulkhead/ratelimiter config 뿐). FE 도구바·API 클라이언트까지 종단 배선. → **UNCERTAINTIES #1 의 "정책 위반 = FAIL 유지" 판정 그대로 유지** |
| ② 전송 픽셀이 **원본이 아니라 비식별본**인가 | **그렇다(심각도 완화 근거 실증).** 원본 경로가 NULL 인 파생 프레임에서 동작하고, 원본이 실재하고 비식별본만 없는 프레임에서는 404 로 끊긴다(폴백 없음). 게이트 없는 `encodeToBase64(String)` 유출 경로는 코드에서 소멸 |
| ③ 신고 게이트가 **포털 SAM2 경로에도** 걸리는가 | **그렇다.** segment·track 모두 412 + ai-server 호출 0건, track 은 후속 프레임마다 재판정. 게이트는 `DeidentReportGate` 단일 원천이며 **자기 rawSn 행 하나만** 본다(★1 확정 정책과 정합 — 파생 경유 열람은 결함으로 재보고하지 않음) |

## 3. 근거 드리프트 (카탈로그 정합성 — 판정과 별개)

`PortalSam2Service.java` 인용이 전반적으로 **약 2~18줄 앞당겨져** 있다(파일 상단 javadoc/필드 증가분 미반영).

| TC | 카탈로그 표기 | 실제 위치 |
|---|---|---|
| 060 | `PortalSam2Service.java:108-134` | `109-137` (`segment()`) |
| 061 | `:142-190` | `145-208` (`track()`) |
| 062 | `:218-226` | `236-251` (`requireExposedFrame`+`isDatamartExposed`) |
| 063 | `:219-220` | `237-238` |
| 064 | `PortalSam2Controller.java:81-84` | `86-90` |
| 065 | `:125-128` | `127-131` |
| 066 | `:196-204` | `214-230` (bulkhead catch `219-222`) |
| 067 | `:267-275` | `285-293` |
| 068 | `:154-163` | `159-168` |
| 069 | `:236-254` | `253-272` |
| 070 | `:120-122,174-176` | `121-123, 179-181` |
| 071 | `:186-188` | `204-207` |
| 075 | `:113-116` | `114-117` (1줄) — 테스트 인용 `AiInferenceDeidentReportGateTest.java:416-434` 는 정확(실 `416-433`) |
| 076 | `:115` / `FrameImageEncoder.java:148-152` / 테스트 `387-400` | `116` / **정확** / `386-399` |
| 077 | `:150-167` / 테스트 `402-414` | `157`·`159-168`·`170-172` / `401-413` |
| 078 | `FrameImageEncoder.java:172-197`(주석 `185-189`) | `182-197`(주석 **`185-189` 정확**) |

## 4. 관찰 사항 (이슈로 승격하지 않음)

- **존재 오라클**: 비존재 프레임 404 / 존재하나 비APPROVED 403 으로 갈려 포털 사용자가 `srcSn` 열거로 프레임 존재 여부를 식별할 수 있다(CWE-209 경미). 다만 카탈로그 TC-062/063 이 이 응답을 **기대값으로 명시**하고 있어 설계 의도로 판단, 결함 처리하지 않음.
- **상단 경계 좌표 미검증**: `box:[10,10,99999,99999]` 요청이 200 으로 통과한다(ai-server 가 내부 clamp). 저장 좌표가 아니라 프롬프트이고 ★3 확정 정책(AI 축 = clamp)과 정합하므로 결함 아님.
- **rate limit 소진 지점이 인가·조회보다 앞**(`:111` → `:112`): 존재하지 않는 srcSn 폭주로도 본인 permit 이 소진된다. per-user 격리라 타 사용자 영향은 없어 설계대로로 판단.

---

# 이슈 대장

### [F-ISSUE-41] TC-PORTAL-060, TC-PORTAL-061 — 포털 SAM2 분할/추적 엔드포인트가 여전히 살아 있음(정책상 미제공 기능)
- **심각도**: HIGH *(1차 대비 완화 — 전송 픽셀이 비식별본으로 교체되고 신고 게이트가 걸려 CRITICAL 아님)*
- **기대 동작(기대효과)**: 루트 `CLAUDE.md` 포털 섹션 + ADR-013 은 포털 채널에 **"오토라벨링·SAM2·VLM·버전관리·검수 미제공"** 을 명시한다. 포털은 데이터마트 영상 열람 + 수동 라벨링(BBOX/POLYGON) + 본인 자산 업로드만 담당해야 하며, GPU 추론 자원을 외부 채널에 개방하면 ①내부 파이프라인 자원 경합 ②승인 완료(데이터마트 노출) 프레임 픽셀의 외부 프로세스 반복 전송 ③요구사항 범위 확대(감리 지적)로 이어진다.
- **현재 동작(이슈 내용)**: 두 엔드포인트가 무조건 노출된다. 기능 토글·프로파일 가드가 없다.
  - `backend/.../portal/controller/PortalSam2Controller.java:44,54-55,71-72`
    ```java
    @RequestMapping("/v1/portal/frames")
    @PostMapping("/{srcSn}/sam2-track")   @PreAuthorize("hasRole('PORTAL_USER')")
    @PostMapping("/{srcSn}/sam2-segment") @PreAuthorize("hasRole('PORTAL_USER')")
    ```
  - 실동작(2026-08-02, 포털 토큰 `channel=PORTAL, role=PORTAL_USER, sub=3001`):
    ```
    POST /api/v1/portal/frames/1/sam2-segment  {"srcSn":1,"box":[10,10,100,100]}
      → 200 {"success":true,"data":{"polygon":[[16.0,10.0], … 176점 …],"score":…}}
    POST /api/v1/portal/frames/1/sam2-track    {"srcSn":1,"trackId":"qa-track-1",…,"nextSrcSns":[2,3]}
      → 200 {"data":{"tracked":[{"srcSn":2,…},{"srcSn":3,…}]}}
    ```
    ai-server 직접 조회로 실추론 확인: `{"mock":false,"source":"model"}`.
  - `application.yml` 에는 `resilience4j.bulkhead.configs.portalSam2`(:675) · `ratelimiter.configs.portalSam2`(:684) 만 있고 **기능 on/off 키가 없다** → 운영에서 끌 수단이 없음.
- **재현/확인 경로**:
  ```bash
  TOK=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"PORTAL_USER","channel":"PORTAL","sub":"3001"}' | jq -r .data.token)
  curl -s -w '\n%{http_code}\n' -X POST http://localhost:18081/api/v1/portal/frames/1/sam2-segment \
    -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"srcSn":1,"box":[10,10,100,100]}'
  # → 200 + 실좌표
  ```
  (srcSn=1 은 rawSn=4, `LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED'`)
- **영향**: 요구사항·ADR 위반(범위 이탈). 보안 측면은 **완화됨** — 전송 픽셀이 비식별본이고(TC-075) 신고 구간은 412 로 차단(TC-076/077)되므로 CWE-359 잔여 위험은 "승인된 비식별 프레임이 외부 채널 트리거로 ai-server 로 반복 전송됨" 수준. 자원 측면은 포털 전용 bulkhead(4)·per-user rate limit(30/min)로 내부 `aiOnline` 경로와 격리돼 있어 잠식 위험도 제한적. 남는 실질 리스크는 **정책·감리 정합성**과 **끌 수 없는 노출 표면**.
- **수정 방향(제안)**: ⚠ 구현하지 않음.
  1. (정석) `PortalSam2Controller` + `PortalSam2Service` + FE 분기(`api.ts:604-610,812-823`, `useSam2Segment`/`useSam2Track`/`CanvasShell`/`Sam2TrackTool`/`ObjectAttributePanel`) 제거, 포털 도구바에서 `AI 분할`·`AI 추적`·`스켈레톤` 비노출(F-ISSUE-42 와 동시 처리), `LabelingPagePortalRestrictions.test.tsx:108-124` 를 **부재 단언**으로 반전.
  2. (대안) 정책을 "포털 SAM2 제공"으로 **상향 확정**한다면 `CLAUDE.md` 포털 섹션·ADR-013 을 개정하고 UNCERTAINTIES #1 을 종결 — 단 그 경우에도 운영 kill-switch(`authoring.portal.sam2.enabled` 등 `@ConditionalOnProperty`)를 함께 두어야 한다.
  - 어느 쪽이든 **정책 확정이 선행**돼야 하며, 현재 상태(문서=미제공 / 코드=제공)의 방치가 가장 나쁘다.

### [F-ISSUE-42] TC-PORTAL-072 — FE 포털 라벨링 도구바가 SAM2(AI 분할/추적)·스켈레톤을 노출하고, 테스트가 그 노출을 기대값으로 고정
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 포털 모드 라벨링 화면은 수동 도형(BBOX/POLYGON)만 제공해야 한다(ADR-013). BE 를 닫아도 FE 가 버튼을 노출하면 사용자에게 미제공 기능을 광고하게 되고, 반대로 FE 만 닫으면 API 는 열린 채로 남는다.
- **현재 동작(이슈 내용)**: 포털 모드에서 SAM2 버튼이 노출되며, **회귀 테스트가 이 노출을 단언**한다.
  - `frontend/src/features/portal/__tests__/LabelingPagePortalRestrictions.test.tsx:108-124`
    ```tsx
    // Phase 9 (ADR-013 override) — 포털 캔버스 좌측 도구바에 SAM2 분할/추적·키포인트 노출.
    it('포털_라벨링_도구바_SAM2_분할_추적_키포인트_노출_Phase9', async () => {
      expect(screen.getByRole('button', { name: 'AI 분할' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'AI 추적' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '스켈레톤' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'AI 탐지' })).toBeNull();  // YOLO만 숨김
    });
    ```
  - API 배선: `frontend/src/features/label/api.ts:604-610`(track), `:812-823`(segment) 가 `portalMode` 시 `/portal/frames/{id}/sam2-*` 로 분기. 소비처 `canvas/CanvasShell.tsx:40-41`, `canvas/tools/Sam2TrackTool.tsx:43-44`, `hooks/useSam2Segment.ts:40`, `hooks/useSam2Track.ts:34`, `components/ObjectAttributePanel.tsx:89`, `pages/label/LabelingPage.tsx:1511-1516`.
  - 대조군(정상): 같은 파일 `:86-105` 는 검수제출 버튼·VLM/시계열 메타 **부재**를 단언한다(TC-073/074 PASS) — 즉 다른 미제공 기능은 제대로 닫혀 있고 SAM2 만 예외.
- **재현/확인 경로**: 포털 토큰(`channel=PORTAL`)으로 `/label/{srcSn}` 진입 → 좌측 도구바에 `AI 분할`·`AI 추적`·`스켈레톤` 버튼 표시. 정적으로는 위 테스트 파일·`api.ts` 분기 확인.
- **영향**: 기능 범위(ADR-013) 위반의 사용자 접점. 테스트가 위반 상태를 고정하고 있어, F-ISSUE-41 을 BE 에서 닫으면 **이 FE 테스트가 RED 로 뒤집히는** 구조적 결합이 있다(수정 시 함께 반전 필요).
- **수정 방향(제안)**: ⚠ 구현하지 않음. F-ISSUE-41 의 정책 확정 결과에 종속. "미제공"으로 확정되면 도구바 렌더 조건에 `!portalMode` 를 추가(현재 `showMeta = !portalMode` 와 동일 패턴, `LabelingPage.tsx:474-475`)하고 위 테스트를 `queryByRole(...)` **null 단언**으로 반전한다. `api.ts` 의 포털 분기와 `PortalSam2*` 도 함께 제거.

### [F-ISSUE-43] TC-PORTAL-070 — ai-server 가 `polygon: []`(빈 배열)을 주면 502 가 아니라 400 이 나가고, 이 축의 테스트가 0건
- **심각도**: LOW
- **기대 동작(기대효과)**: 외부 추론 응답이 **비었거나 실패**하면 "외부 API 오류"(`EXTERNAL_API_ERROR` = 502)로 종결해야 한다. 502 는 "외부 의존 실패, 재시도 가능"을 뜻하고 400 은 "클라이언트 입력이 잘못됨"을 뜻하므로, 외부 원인을 400 으로 내보내면 FE·운영이 원인을 사용자 입력으로 오귀속하고 모니터링(외부 API 실패율)에도 잡히지 않는다.
- **현재 동작(이슈 내용)**: null 만 502 로 분기하고 **빈 배열은 좌표 검증으로 흘러 400** 이 된다.
  - `backend/.../portal/service/PortalSam2Service.java:121-123`
    ```java
    if (aiRes == null || aiRes.polygon() == null) {
        throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "SAM2 segment 응답이 비어있습니다.");
    }
    ```
  - 이어서 `:132` `validatePolygon(aiRes.polygon())` → `:255-257`
    ```java
    if (polygon == null || polygon.isEmpty()) {
        throw new CustomException(ErrorCode.INVALID_INPUT, "폴리곤 좌표가 비어있습니다.");  // 400
    }
    ```
  - track 도 동일 구조(`:179-181` null 체크 → `:182` 검증).
  - 테스트: `PortalSam2ServiceTest` 에 ai 실패/빈응답·좌표 이상치 케이스 **0건**(`grep "EXTERNAL_API_ERROR\|isFinite\|음수" backend/src/test/.../portal/` 무결과).
- **재현/확인 경로**: 현 환경에서는 **실동작 재현 불가** — ai-server 는 contour < 3점이거나 변환 실패 시 빈 배열이 아니라 **mock 폴백**(`mock=true, source="mock"`)을 반환하므로(`ai-server/app/routers/sam2.py:157-162, 184-201, 305-313`) `polygon: []` + `source: "model"` 조합이 실제로 나오지 않는다. 재현하려면 스텁으로 `new Sam2Response(List.of(), 0.9)` 를 주입해야 한다(단위테스트 영역).
- **영향**: 기능·보안 영향 없음(양쪽 다 fail-closed 거부이고, 좌표가 사용자에게 적용되지 않는다). **오분류·관측성** 문제이며 계약 방어의 완결성 결함. 향후 ai-server 가 빈 배열을 정상 응답으로 내보내도록 바뀌면(계약 드리프트) 그 시점에 드러난다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. `:121-123`·`:179-181` 의 조건을 `polygon() == null || polygon().isEmpty()` 로 확장해 빈 응답을 502 로 일원화하고(내부 경로 `Sam2SegmentService`·`Sam2TrackService` 와 조건을 맞출 것), `PortalSam2ServiceTest` 에 ①ai null/빈 배열 → 502 ②ai 음수·NaN 좌표 → 400 두 케이스를 추가한다.
