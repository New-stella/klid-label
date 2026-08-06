# C 클러스터 part4 (C-3·C-4) 2차 검증 결과

> 대상: `docs/test-cases/C-marking-labeling.md` 의 **C-3. TC-SAM2 (33건)** + **C-4. TC-KEYPOINT (13건)** = **46건**
> 환경: backend `localhost:18081`(HEAD `ca3c712b` 재빌드본) · ai-server `:19300`(SAM2 **실가중치 실추론**, `mock=false/source=model`) · postgres `:5432` 스키마 `public`
> 검증 일시: 2026-07-31 03:4x~04:0x KST
> ⚠ `~~취소선~~` 폐기 행 **0건** — C-3·C-4 에는 폐기 케이스가 없어 33+13 전부 검증 대상.

## 사용/생성 데이터 (파괴적 변경 회피 기록)

| 용도 | 대상 | 비고 |
|---|---|---|
| SAM2 분할/추적 정상 경로 | **rawSn 132** (srcSn 77·79·81·83·85·87, 6프레임, `DE_IDENT_YN=Y`, WORKER 2001 배정) | segment/track 은 **미저장 프록시**라 DB 무변경. 검증 전후 `ls_data_lbl` **28행 동일** |
| 신고 구간 412 | **rawSn 133** (srcSn 78, `DE_IDENT_YN='F'`) | 읽기만 — **OPEN 상태 유지**(변경 없음) |
| 비식별 우선 폴백(파생) | **rawSn 130** (해상도 720P 파생, srcSn 74·75, `SRC_FILE_PATH_NM=null`) | 미저장. 라벨 21행 불변 |
| IDOR | WORKER **2002** 토큰(미배정) / PORTAL_USER 토큰 | |
| **키포인트 저장(쓰기 발생)** | **rawSn 143 / srcSn 111** (검증 착수 시 라벨 **0건**, `ASSIGNED`) | TC-KEYPOINT-06/13 로 SKELETON 라벨 **1건 신규 생성(lblSn 520)** + `ls_data_lbl_hstry` 1행. **원복하지 않음**(원복 저장도 이력을 남기므로 추가 오염). rawSn 143 은 B-part2 생성분 |
| **무변경 확인** | rawSn **126 · 129 · 133** | 라벨/상태/신고 전부 그대로. 126 프레임(65~67)에는 어떤 요청도 보내지 않음 |

## 집계

| 구분 | 검증 | PASS | PARTIAL | FAIL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| C-3 TC-SAM2 | 33 | 31 | 1 | 1 | 0 | 0 | 0 |
| C-4 TC-KEYPOINT | 13 | 13 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **46** | **44** | **1** | **1** | **0** | **0** | **0** |

- 실동작 근거 **35건** / 정적·테스트대조 근거 11건(실모델·실환경에서 물리적으로 강제 불가한 분기: ai 응답 오염, 20MB 초과 이미지, 파일 결손 프레임 등).
- 이슈 **6건** — HIGH 1 / MEDIUM 2 / LOW 3.
- 근거 드리프트 **6건**(전부 C-3. C-4 는 13건 `file:line` 전건 정확).

## 1차 이슈 해소 대조

1차(2026-07-25) C-3·C-4 는 **TC-SAM2-01~28 · TC-KEYPOINT-01~13 전건 PASS** 였고, C-3/C-4 귀속 이슈는 참고 1건뿐이었다. TC-SAM2-29~33 은 2026-07-30 카탈로그 최신화 신규분이라 1차 판정이 없다.

| 1차 항목 | 1차 판정 | 2차 실측 | 결론 |
|---|---|---|---|
| **C-ISSUE-61(1차, LOW·참고)** — SAM2 프롬프트 입력좌표 이미지 경계 사전검증 부재 | 참고 관찰(테이블 판정 무영향) | **미해소 재확인.** `points:[[99999,99999]]` → **200** + 981정점/score 0.1984, `points:[[-500,-500]]` → **200** + 정상 폴리곤. `Sam2SegmentRequest`·`Sam2TrackRequest` 어디에도 요청 좌표 상한 검증 없음(응답만 검증) | **이월** → 본 회차 `C-ISSUE-65` 로 재기록 |
| (1차) TC-SAM2-22 — `Sam2TrackService:93` 분기 미도달(LabelAccessGuard 선점) | 1차 비고로 기록 | **동일 재현.** `nextSrcSns=[999999]` → 404 이나 메시지가 `"프레임을 찾을 수 없습니다."`(가드 메시지)이지 `"후속 프레임을 찾을 수 없습니다: 999999"` 가 아님 | 기능 동일(404) — 드리프트로만 계상 |
| (1차) TC-SAM2-23 — 1차 PASS 판정 | PASS | **2차 FAIL 로 반전.** 1차는 *"공유 검증함수이므로 ai 응답 경로도 동일 방어 확인됨"* 으로 **요청 경로(prevPolygon) 실측 결과를 응답 경로 판정에 전용**했다. 실제 코드는 `INVALID_INPUT`(**400**)을 던져 카탈로그 기대값 **502 EXTERNAL_API_ERROR** 와 불일치하고, "정점부족"은 아예 검증하지 않는다 | **1차 확증편향 미검출** → `C-ISSUE-62` |
| (1차) TC-SAM2-26 — trackShape 회귀 재발 여부(과거 결함) | PASS | **재확인 PASS.** BE `shapeOrDefault():73` POLYGON 정규화 + FE `ObjectAttributePanel.tsx:320` `shapeToDetectType(target.shape) ?? track.shape`(선택 객체 형태 우선) 양쪽 유지. 잘못된 문자열 `"CIRCLE"` → 400 | 회귀 없음 |
| `nextSrcSns` 무제한 전송 → 400 "추적 실패"(과거 계약 버그) | — | **양쪽 다 정합.** BE `@Size(max=50)`(51→400 / 50→200 실측) + FE `sam2TrackAllChunks` 가 50개 청크로 분할(`sam2-track.test.tsx:96 상수는_BE_Size_상한과_정합한다`, `:179 정확히_50개_후속프레임은_단일_청크`) | 회귀 없음 |
| **G-ISSUE-22**(G-part2, HIGH) FE 파급 | BE DTO 유실까지만 확인 | **FE 종단까지 실증 완료** — 아래 절 | `C-ISSUE-61` 로 승계·확장 |

---

## ★ mock 플래그 전파 실측 (ai-server 응답 / BE DTO / FE 자동적용 차단 여부)

계약: *"SAM2 분할(클릭/박스→폴리곤+신뢰도) — **mock 응답은 FE 자동적용 차단**"* (CLAUDE.md, SFR-08-01 VOS).
**Segment 는 계약대로 차단되고, Track 은 전 계층에서 신호가 소멸한다.**

| 계층 | SAM2 **Segment** | SAM2 **Track** |
|---|---|---|
| ai-server 응답 스키마 | `mock` / `source` / `mock_reason` 송신 | **동일하게 송신**(`schemas.py:180-188`, G-part2 §G-4 실측 `{"mock":false,"source":"model","mock_reason":null}`) |
| BE 클라이언트 DTO | `Sam2Response.java:26-32` — 3필드 **전부 선언** ✓ | `Sam2TrackResponse.java:14-18` — `trackId`/`polygon`/`score` 뿐, **3필드 전부 미선언** → Jackson 이 조용히 폐기 |
| BE 서비스 판정 | `Sam2SegmentService.java:124-127` `if (aiRes.mock()) return empty()` | `Sam2TrackService.java:105-142` — **mock 판정 자체가 없음** |
| BE→FE 응답 DTO | `Sam2SegmentResponse` polygon=`[]` + `ApiResponse.message="AI 모델 미로드 — 결과 신뢰 불가"`(`LabelController.java:174-178`) | `Sam2TrackResponseDto.TrackedItem` — mock 필드 없음, `message=null` |
| FE 처리 | `OverlayLayer.tsx:408-412` — `res.polygon.length===0` → 프리뷰 차단 + `onMockWarning` + **자동 적용 차단**. 추가로 `:413-417` score<0.3 저신뢰 차단 | `LabelingPage.tsx:634-667 handleTracked` — **무조건** `mergeAutoLabels`/`stashPendingTracks` 후 `"AI 추적 완료 (N프레임)"` **성공 토스트**. mock·score 분기 **0건** |
| **실동작 증거** | `box:[400,400,100,100]`(역전) → ai-server `WARNING [SAM2] segment real returned no mask — mock fallback` → BE **200** `{"polygon":[],"score":0.0,"empty":true}` + `message:"AI 모델 미로드 — 결과 신뢰 불가"` | `prevPolygon:[[9000,9000],[9000.5,9000],[9000.5,9000.4]]`… 및 `[[9000,9000],[9001,9000],[9001,9001]]` → ai-server `WARNING [SAM2] track real returned no mask — prev polygon fallback` → BE **200** `{"srcSn":79,"trackId":"mock-probe","points":[[9000.0,9000.0],[9001.0,9000.0],[9001.0,9001.0]],"score":0.5,"shapeType":"POLYGON"}`, **`message:null`** — 입력 폴리곤을 그대로 반사한 가짜 추적 결과가 실추론과 완전히 동일한 형태로 내려옴 |

→ **`C-ISSUE-61`(HIGH)**. 비대칭은 설계 실수가 아니라 *배선 누락*이다 — Segment 쪽 `Sam2SegmentResponse` javadoc 은 "mock 필드 없이 빈 폴리곤+메시지로 차단"을 **명시적 설계**로 기술하고 전용 테스트(`Sam2SegmentServiceTest:212 Sam2SegmentResponse에_mock필드가_없다`, `Sam2SegmentMockMessageWiringTest` 3건)까지 있으나, Track 에는 대응 설계·테스트가 **하나도 없다**.

---

## C-3 결과표 (TC-SAM2, 33건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|---|---|---|
| TC-SAM2-01 | segment 정상 | PASS | [실동작] `POST /v1/frames/77/sam2-segment {"points":[[640,540]]}` → **200**, polygon 265정점 / `score 0.9855`(실모델). `box:[100,100,400,400]` 도 200(1379정점). 호출 전후 `ls_data_lbl(77,79,81,83,85,87)` **28행 동일 = 미저장** | 온라인 SAM2 자동저장 없음 재확인(오토라벨 2경로 정책 정합). 근거 `:84-149` 는 실제 메서드 `:84-153` |
| TC-SAM2-02 | segment path/body srcSn 불일치 | PASS | [실동작] path 77 / body 79 → **400** `INVALID_INPUT` "path 의 srcSn 과 body 의 srcSn 이 다릅니다." (`LabelController.java:167-172`) | CWE-345 |
| TC-SAM2-03 | points/box 배타 위반(둘 다) | PASS | [실동작] `points`+`box` 동시 → **400** "exactlyOnePrompt: points 또는 box 중 정확히 하나만 제공해야 합니다." | `@AssertTrue` `Sam2SegmentRequest.java:46` |
| TC-SAM2-04 | points/box 배타 위반(둘 다 빈) | PASS | [실동작] 둘 다 미지정 → **400** 동일 메시지 | |
| TC-SAM2-05 | segment IDOR | PASS | [실동작] WORKER **2002**(rawSn132 미배정) → **403** "본인에게 배정되지 않은 영상입니다." / **PORTAL_USER** → **403** "권한이 없습니다."(`@PreAuthorize` 단계) | CWE-639 양방향 확인 |
| TC-SAM2-06 | segment 경로순회 차단 | PASS | [정적] 실효 가드 = `FrameImageEncoder.resolveSafe:220-229`(`normalize()+startsWith`) + `StorageSubtreePolicy.verifyDeidentifiedFile:181-193`(**`toRealPath()` 실경로 서브트리 판정** — 심링크 우회 차단). 사용자 입력이 경로에 닿는 표면 없음(경로는 DB `LS_DATA_SRC` 컬럼) | ⚠ 카탈로그가 함께 인용한 `Sam2SegmentService.java:179 resolveSafe` 는 **호출부 0건 dead code** → `C-ISSUE-64` |
| TC-SAM2-07 | segment 이미지 미존재 | PASS | [실동작] `srcSn=999999` → **404** "프레임을 찾을 수 없습니다."(`:89` 계열). [정적] 파일 결손 분기는 `FrameImageEncoder.resolveFrameImageWithoutGate:111-113` → NOT_FOUND | 실환경에 DB 행은 있고 파일만 없는 프레임이 없어(컨테이너 실측 8개 영상 전수 존재) 파일 분기는 정적 확인 |
| TC-SAM2-08 | segment 이미지 크기 초과 | PASS | [정적+테스트] `Sam2SegmentService.java:99-102` → `PAYLOAD_TOO_LARGE`(**413**). 테스트 `Sam2SegmentServiceTest#imageTooLargeRejected`(상한을 1B 로 낮춰 `ErrorCode.PAYLOAD_TOO_LARGE` 단언 + `verify(aiServerClient, never())`) | 실환경 프레임 최대 226KB(상한 20MB, `application.yml:396`)라 실호출 재현 불가. ⚠ 해당 테스트 `@DisplayName` 이 "…초과시_**400**" 으로 오기(단언은 413) |
| TC-SAM2-09 | segment mock→빈 폴리곤+메시지 | PASS | [실동작] 역전 박스로 실모델 mask 실패 유도 → ai-server `[SAM2] segment real returned no mask — mock fallback` → BE **200** `{"polygon":[],"score":0.0,"empty":true}` + `message:"AI 모델 미로드 — 결과 신뢰 불가"`. FE `OverlayLayer.tsx:408-412` 가 빈 폴리곤에서 **자동 적용 차단 + onMockWarning** | 계약대로 동작. 같은 계약이 Track 에는 없음 → `C-ISSUE-61` |
| TC-SAM2-10 | segment 폴리곤 정점<3 | PASS | [정적+테스트] `validatePolygon:160-163` → `EXTERNAL_API_ERROR`(**502**). `Sam2SegmentServiceTest#3점_미만_폴리곤_응답시_오류` | 실모델이 <3정점을 반환하도록 강제 불가 |
| TC-SAM2-11 | segment 좌표 경계초과 | PASS | [정적+테스트] `:171-174` `x>imgWidth‖y>imgHeight‖음수‖null` → **502**. 이미지 실측 해상도는 `readImageSize:199-209` 로 확보(1920×1080) | 외부 응답 불신 원칙 정합 |
| TC-SAM2-12 | segment simplifyTolerance 범위 | PASS | [실동작] `60` → **400** "경계 세밀함은 50.0 이하여야 합니다.", `-1` → **400** "…0.0 이상…". 경계 `0`·`50` 은 200 | `@DecimalMin/@DecimalMax` `Sam2SegmentRequest.java:35-36` |
| TC-SAM2-13 | segment 단순화 3점 미만→원본유지 | PASS | [실동작] 동일 프롬프트에서 `simplifyTolerance:0` → 492정점 / `:50` → 5정점(Douglas-Peucker 실동작 확인, 3점 미만까지는 안 줄어듦). [정적] 폴백 분기 `Sam2SegmentService.java:144-147` + 테스트 `#AI분할_simplify_결과가_3점미만이면_원본폴리곤유지` | 근거 드리프트 — 실제 폴백은 `:144-147`(카탈로그 `:132-143` 은 tolerance 조회·단순화 블록) |
| TC-SAM2-14 | track 정상 POLYGON | PASS | [실동작] `77 → nextSrcSns:[79,81]` → **200**, 프레임별 70·121정점 / score `0.7871`·`0.1955`(실모델). `ls_data_lbl` 28행 불변 = 미저장 | 응답 필드 = `srcSn/trackId/label/points/score/shapeType` — **mock 지표 없음**(→ `C-ISSUE-61`) |
| TC-SAM2-15 | track path/body srcSn 불일치 | PASS | [실동작] path 77 / body 79 → **400** (`LabelController.java:138-143`) | |
| TC-SAM2-16 | track nextSrcSns 50 초과(경계) | PASS | [실동작] **51개 → 400** "nextSrcSns: size must be between 0 and 50" / **50개 → 200**(50프레임 순차 추론 완주) | 과거 "추적 실패" 계약 버그의 서버측 상한 유효. FE 도 50 청크 분할로 정합 |
| TC-SAM2-17 | track nextSrcSns 빈 | PASS | [실동작] `[]` → **400** "nextSrcSns: must not be empty" | |
| TC-SAM2-18 | track prevPolygon <3점 | PASS | [실동작] 2점 → **400** "prevPolygon: size must be between 3 and 1000" | |
| TC-SAM2-19 | track prevPolygon >1000점 | PASS | [실동작] 1001점 → **400** 동일 메시지 | CWE-770 |
| TC-SAM2-20 | track trackId 64자 초과 | PASS | [실동작] 65자 → **400** "trackId: size must be between 0 and 64" | `TRACK_ID VARCHAR(64)` 정합 |
| TC-SAM2-21 | track IDOR 시작+후속 각각 | PASS | [실동작] ①시작 프레임 미배정(W2002→srcSn77) → **403** ②**후속만 미배정**(W2002, 시작=114 본인 배정 / next=[77] 타인) → **403** + `docker logs klid-ai-server \| grep -c sam2/track` **5→5(AI 호출 0건)** | 후속 프레임 인가가 AI 호출 이전임을 로그 카운트로 실증 |
| TC-SAM2-22 | track 후속 프레임 미존재 | PASS | [실동작] `nextSrcSns:[999999]` → **404**. `[-1]`·`[0]` 도 404 | 근거 드리프트 — 실제 throw 는 `LabelAccessGuard`(선점)이고 `Sam2TrackService.java:93` 분기는 미도달(1차와 동일). 결과 404 동일 |
| TC-SAM2-23 | track ai 응답 폴리곤 검증 | FAIL | [정적+테스트] ①`Sam2TrackService.java:119` → `validatePolygon(:188-204)` 이 던지는 것은 `ErrorCode.INVALID_INPUT` = **400**(기대 502 EXTERNAL_API_ERROR 와 불일치). 테스트도 400 을 고정(`Sam2TrackServiceTest#sam2_track_ai응답폴리곤_검증실패시_400`, 음수 폴리곤 스텁 → `INVALID_INPUT` 단언) ②"**정점부족**"은 검증 자체가 없음 — track 의 `validatePolygon` 은 `null‖isEmpty` 만 거부하고 segment 의 `MIN_POLYGON_POINTS(3)` 대칭 검사가 부재 | → `C-ISSUE-62`. 1차 PASS 판정은 요청 경로 실측을 응답 경로에 전용한 오판 |
| TC-SAM2-24 | track BBOX 외접박스 산출 | PASS | [실동작] `shape:"BBOX"` → `points:[[96.0,133.0],[225.0,305.0]]`(정확히 2점 `[[minX,minY],[maxX,maxY]]`), `shapeType:"BBOX"` | `toCircumscribedBbox:171-185` |
| TC-SAM2-25 | track 퇴화 bbox 프레임 스킵 | PASS | [실동작] mock 폴백(입력 폴리곤 반사)을 이용해 폭 0.5px·높이 0.4px 폴리곤 강제 + `shape:"BBOX"`, `nextSrcSns:[79,81]` → **200** `{"tracked":[]}` — 두 프레임 모두 스킵되고 **전체 추적은 예외 없이 완주**. 대조군(100×100 폴리곤)은 정상 1건 반환 | 1차는 [정적]이었으나 2차는 실동작 재현 성공 |
| TC-SAM2-26 | track shape 기본 POLYGON | PASS | [실동작] `shape` 생략 / `shape:null` 둘 다 `shapeType:"POLYGON"`. 잘못된 문자열 `"CIRCLE"` → **400**(Jackson enum 거부). [정적] FE `ObjectAttributePanel.tsx:320` 이 선택 객체 형태 우선(`shapeToDetectType(target.shape) ?? track.shape`) | "AI 추적 출력 형태 = 선택 객체 형태 고정" 정책 양단 유지 — 과거 결함 재발 없음 |
| TC-SAM2-27 | track ai 호출 실패 502 | PARTIAL | [정적+테스트] track 경로 claim 자체는 **참** — `:106-114` catch → `EXTERNAL_API_ERROR`(502) + 클라이언트 메시지는 일반화("SAM2 track 호출에 실패했습니다.") + `LogSanitizer` 로그(`Sam2TrackServiceTest#sam2_track_ai호출실패시_502`). **단 형제 경로 segment 는 동일 실패에서 `e.getMessage()` 를 그대로 실어** `"SAM2 segment 호출 실패: 500 Internal Server Error from POST http://klid-ai-server:9300/infer/sam2/segment"` 로 **내부 호스트·포트·경로를 응답 본문에 노출**(실동작: `points:[[]]` → ai-server 500 유발) | 인접 발견 반영해 등급 하향(G-part2 TC-AICONTRACT-06 선례) → `C-ISSUE-63` |
| TC-SAM2-28 | track trackId 로그 sanitize | PASS | [실동작] `trackId:"evil\r\nFAKE-LOG-INJECTED admin=true"` → 200, 백엔드 로그는 **단일 라인** `[Sam2Track] propagated trackId=evilFAKE-LOG-INJECTED admin=true startSrc=77 …`(CR/LF 완전 제거). `grep -n` 결과 위조 라인 미생성 | CWE-117 방어 실증. 응답 본문 echo 는 JSON 이스케이프되어 무해 |
| TC-SAM2-29 | segment 신고 구간 412 (신규) | PASS | [실동작] srcSn **78**(rawSn 133, `DE_IDNTF_YN='F'`) → **412** `PRECONDITION_FAILED` "비식별 재처리 대기 중인 영상은 AI 추론을 실행할 수 없습니다." + ai-server 호출 0건 | `resolveFrameImageForInference:133-136` → `requireNotUnderDeidentReport:206-217` — **파일 읽기 이전** 차단(전송 후 폐기 아님) |
| TC-SAM2-30 | track 신고 구간 412 (신규) | PASS | [실동작] 동일 srcSn 78 시작 + `nextSrcSns:[80]` → **412**, ai-server 호출 0건 | 시작 프레임 인코딩(`:86`)이 `encodeFrame:178-180` 경유라 후속 루프 진입 전 차단 |
| TC-SAM2-31 | 게이트 없는 base64 오버로드 부재 (신규, 구조 단언) | PASS | [정적] `grep -rn "encodeToBase64" backend/src/main/java` → **메서드 정의·호출 0건**(주석/javadoc 4건만). `FrameImageEncoder` public 표면 = 생성자 + `resolveFrameImageForInference(LsDataSrc)` + `encodeDeidentifiedFrameForInference(LsDataSrc)` + `encodeFrame(LsDataSrc)` — **경로 문자열 진입점 없음**, 전부 `private encode(Path)` 로 수렴 | 포털 SAM2 원본 픽셀 유출 경로(3630558d) 차단 유지 |
| TC-SAM2-32 | 게이트 없는 해석기는 패키지 전용 (신규, 구조 단언) | PASS | [정적] `FrameImageEncoder.java:94` 선언에 접근제어자 없음(**package-private**). 전 코드베이스 호출부 = 내부 `:135` 1건 + 패키지 외부 **`FrameBoundsResolver.java:101` 단 1건**이며 그 소비는 `ImageIO.read → getWidth/getHeight` 치수 측정뿐(픽셀 미반출) | |
| TC-SAM2-33 | 비식별 우선 폴백 경로 해석 (신규) | PASS | [실동작] srcSn **74**(rawSn 130 = 720P 해상도 파생, `SRC_FILE_PATH_NM` **null** / `DE_IDNTF_SRC_FILE_PATH_NM` 존재) → segment **200** 412정점·score 0.9872, track `74→[75]` **200**. "이미지 경로가 비어있습니다"(400) 미발생 | `resolveFrameImageWithoutGate:94-115` 비식별 우선 폴백 실동작 확인 |

## C-4 결과표 (TC-KEYPOINT, 13건)

> 저장 실동작은 `PUT /v1/frames/111/labels`(rawSn 143, WORKER 2001) 로 수행. `lblTypeCd` 는 요청 DTO 값(`LabelItemDto` `@Pattern(...|SKELETON)`)이라 SKELETON 라벨 마스터 없이도 경로 진입 가능.

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|---|---|---|
| TC-KEYPOINT-01 | SKELETON 정상 저장 | PASS | [실동작] 17개 삼중값 저장 → **200**, `labelVersion 0→1`. DB `ls_data_lbl.point_cn` = `[[0.0,0.0,0],[10.0,20.0,2],…]` **정확히 17개 삼중값, v 정수 보존**. 재조회(`GET /v1/frames/111/labels`)도 삼중값 그대로 반환(`LabelResponse.java:110 KeypointSerializer.fromJson` 라운드트립 무손실) | 근거 `:792-825`·`:834-838` **정확 일치** |
| TC-KEYPOINT-02 | 개수≠17 | PASS | [실동작] 16개 → **400** "SKELETON 키포인트는 정확히 17 개여야 합니다." / **18개도 400**(상·하한 양쪽) | `:793-796` 정확 |
| TC-KEYPOINT-03 | 삼중값 크기≠3 | PASS | [실동작] 전 원소 `[x,y]` → **400** "키포인트는 [x, y, v] 형태여야 합니다." / 원소 4개 `[10,20,2,5]` → **400** 동일(초과도 거부) | `:797-801` 정확 |
| TC-KEYPOINT-04 | v null 원소(NPE 방어) | PASS | [실동작] `[10,20,null]` 포함 → **400** "키포인트 좌표에 null 원소가 있습니다." — **500 NPE 아님**(언박싱 이전 fail-secure) | `:805-811` 정확 |
| TC-KEYPOINT-05 | v 범위 밖 | PASS | [실동작] `v=3` → **400** "가시성 v 는 0/1/2 중 하나여야 합니다." / **`v=-1` 400** / **`v=1.5`(비정수) 400**(`v != vRaw` 정수성 검사 실동작) | `:816-819` 정확 |
| TC-KEYPOINT-06 | v=0 좌표 0 허용(경계) | PASS | [실동작] `[0,0,0]` + 나머지 16개 → **200** 저장 성공, DB 에 `[0.0,0.0,0]` 보존 | `:820-823` 정확 |
| TC-KEYPOINT-07 | 음수 좌표(v>0) | PASS | [실동작] `x=-1,v=2` → **400** "좌표는 0 이상이어야 합니다 (x=-1.0, y=20.0)". **`v=0` 이어도 음수는 400**(v 무관 — 0 허용은 "0 이상"이지 음수 허용이 아님) | `:820-823` 정확. 반증 추가: `x=99999,v=2` → **400** "좌표가 이미지 경계를 벗어났습니다 (…이미지=1920x1080)" — ★3 사용자 저장 경로 = 경계 초과 400 거부(clamp 아님) 정합 |
| TC-KEYPOINT-08 | KeypointSerializer toJson | PASS | [실동작+정적] 저장 결과 JSON 이 `[[x,y,v],…]` 결정적 순서로 직렬화됨(위 DB 실측). `KeypointSerializer.java:46-60` `List.of(x,y,v)` 중첩 후 `writeValueAsString`. 테스트 `KeypointSerializerTest#roundTrip`·`#toJsonTripletFormat` | `:46` 정확 |
| TC-KEYPOINT-09 | fromJson 배열 아님 | PASS | [정적] `:80-81` `if (!root.isArray()) throw new IllegalArgumentException("키포인트는 배열이어야 합니다")` | 전용 단위테스트 **없음**(회귀 공백) — `KeypointSerializerTest` 4건에 미포함 |
| TC-KEYPOINT-10 | fromJson 삼중값 크기위반 | PASS | [정적+테스트] `:85-86` `!triplet.isArray() ‖ size!=3` → IAE. `KeypointSerializerTest#rejectsTwoTuple` 커버 | `:85-86` 정확 |
| TC-KEYPOINT-11 | fromJson 숫자아님 | PASS | [정적] `:91-92` `!x.isNumber()‖!y.isNumber()‖!v.isNumber()` → IAE | 전용 단위테스트 **없음**(회귀 공백) |
| TC-KEYPOINT-12 | fromJson 빈/[] | PASS | [정적+테스트] `:70-72` `null‖isBlank‖"[]"` → `List.of()`. `KeypointSerializerTest#emptyInputs` 커버 | `:69-81` 범위 내 |
| TC-KEYPOINT-13 | SKELETON R7 비교 폴백 | PASS | [실동작] 동일 삼중값 페이로드 재저장(`id:520` 지정) → **200**, `labelVersion` **1 유지(미증가)** + `ls_data_lbl_hstry` **1행 유지**(최초 ADDED 만) — 2-튜플 파서가 삼중값을 거부한 뒤 raw 숫자배열 폴백 비교로 "무변경" 정확 판정됨 | `LabelService.java:568-585 normalizePoints` **정확 일치**. 폴백이 없으면 매 저장이 UPDATED 로 오분류돼 버전·이력이 부풀었을 것 |

---

## 근거 드리프트

C-3 6건. **C-4 는 13건 전건 정확**(`LabelService.java:792-825/793-796/797-801/805-811/816-819/820-823/834-838/568-585`, `KeypointSerializer.java:46/80-81/85-86/91-92/69-81` 모두 실측 라인과 일치).

| # | TC | 카탈로그 근거 | 실측 | 성격 |
|---|---|---|---|---|
| 1 | TC-SAM2-06 | `Sam2SegmentService.java:179` (resolveSafe) | 선언은 `:179` 에 실재하나 **호출부 0건 — dead code**. 실효 가드는 함께 인용된 `FrameImageEncoder.java:220-229` + (미인용) `StorageSubtreePolicy.verifyDeidentifiedFile:181-193` | **실질**(무효 근거 → `C-ISSUE-64`) |
| 2 | TC-SAM2-13 | `Sam2SegmentService.java:132-143` | "3점 미만→원본유지" 분기는 실제 `:144-147`. `:132-143` 은 tolerance 결정 + Douglas-Peucker 적용 구간 | **실질**(기대결과가 가리키는 코드가 범위 밖) |
| 3 | TC-SAM2-22 | `Sam2TrackService.java:93` | 해당 `orElseThrow("후속 프레임을 찾을 수 없습니다: …")` 는 **도달 불가** — `accessGuard.verifyAccess(:90)` 가 먼저 404 를 던짐(실동작 메시지로 확인). 1차와 동일 관측 | **실질**(도달 불가 근거) |
| 4 | TC-SAM2-01 | `Sam2SegmentService.java:84-149` | `segment` 메서드 실제 범위 `:84-153` | 경미 |
| 5 | TC-SAM2-23 | `Sam2TrackService.java:188-206` | `validatePolygon` 실제 범위 `:188-204`(`:205-206` 은 다음 javadoc) | 경미 |
| 6 | TC-SAM2-27 | `Sam2TrackService.java:111-116` | catch 블록 `:108-114`(throw `:112-113`), `:115-116` 은 별개의 null 응답 가드 | 경미 |

> 근거는 아니지만 함께 기록: `Sam2SegmentServiceTest#imageTooLargeRejected` 의 `@DisplayName` 이 "이미지_크기_상한_초과시_**400**" 으로 오기(실 단언은 `PAYLOAD_TOO_LARGE`=413). 테스트 명칭만의 문제로 동작은 정상.

---

## 이슈 상세

### [C-ISSUE-61] TC-SAM2-14 / TC-SAM2-09 대비 — SAM2 Track 의 mock 폴백이 실추론과 구분 불가하고 FE 가 무조건 자동 병합한다 (G-ISSUE-22 의 FE 종단 파급)

- **심각도**: **HIGH**
- **기대 동작(기대효과)**: CLAUDE.md SFR-08-01 은 *"SAM2 분할(클릭/박스→폴리곤+신뢰도) … **mock 응답은 FE 자동적용 차단**"* 을 계약으로 못박고 있다. SAM2 Track 은 같은 VOS(추적+분할) 축의 형제 기능이고, ai-server 는 Track 응답에도 `mock`/`source`/`mock_reason` 을 **실제로 실어 보낸다**(`ai-server/app/schemas.py:180-188`). 따라서 Track 의 mock 폴백 결과도 사용자 작업본에 자동 병합되어서는 안 되며, 최소한 "AI 모델 미로드 — 결과 신뢰 불가" 급의 경고가 표시돼야 한다. Track 의 mock 폴백은 *"이전 프레임 폴리곤을 그대로 반사하고 score 를 0.5 로 고정"* 하는 **추적이 전혀 일어나지 않은 가짜 결과**라 오히려 Segment 보다 위험하다(Segment 는 빈 폴리곤이라 시각적으로 티가 나지만 Track 은 정상 결과와 형태가 동일하다).
- **현재 동작(이슈 내용)**: mock 신호가 **BE 클라이언트 DTO → BE 서비스 → BE 응답 DTO → FE** 4계층에서 연속으로 소멸한다.
  1. `backend/src/main/java/kr/co/cudo/authoring/common/client/dto/Sam2TrackResponse.java:14-18`
     ```java
     public record Sam2TrackResponse(
             @JsonProperty("track_id") String trackId,
             List<List<Double>> polygon,
             double score
     ) {}
     ```
     `mock`/`source`/`mock_reason` **미선언** → Jackson 이 조용히 폐기(컴파일·런타임 에러 없음). 대조군 `Sam2Response.java:26-32` 는 3필드를 모두 선언하고 javadoc 에 *"BE 는 이를 그대로 클라이언트 응답으로 전파해 FE 가 … 자동 적용 차단을 수행하도록 한다"* 고 명시돼 있다.
  2. `label/service/Sam2TrackService.java:105-142` — `aiRes.trackId()`/`polygon()`/`score()` 만 읽고 mock 판정 없음. 대조군 `Sam2SegmentService.java:124-127` 는 `if (aiRes.mock()) return Sam2SegmentResponse.empty();`.
  3. `label/dto/Sam2TrackResponseDto.java:21-28 TrackedItem` — mock 필드 없음. 컨트롤러(`LabelController.java:132-145`)도 Segment 와 달리 안내 message 를 세팅하지 않는다.
  4. `frontend/src/pages/label/LabelingPage.tsx:634-667 handleTracked` — **조건 없이** `mergeAutoLabels(...)` / `stashPendingTracks(...)` 후 `pushToast({variant:'success', message:'AI 추적 완료 (N프레임)'})`. mock 분기도 score 임계 분기도 없다. 대조군 `frontend/src/features/label/canvas/layers/OverlayLayer.tsx:408-417` 는 `res.polygon.length===0`(mock 신호) 와 `res.score < 0.3`(저신뢰) 두 단계로 자동 적용을 차단한다.
- **재현/확인 경로** (실측 완료):
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
    -H 'Content-Type: application/json' -d '{"role":"WORKER","channel":"INTERNAL"}' | jq -r .data.token)
  # 이미지 밖 폴리곤으로 실모델 mask 실패 → ai-server 가 mock 폴백
  curl -s -X POST http://localhost:18081/api/v1/frames/77/sam2-track \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"srcSn":77,"trackId":"mock-probe","prevPolygon":[[9000,9000],[9001,9000],[9001,9001]],"label":"car","nextSrcSns":[79]}'
  # → 200 {"success":true,"data":{"tracked":[{"srcSn":79,"trackId":"mock-probe","label":"car",
  #        "points":[[9000.0,9000.0],[9001.0,9000.0],[9001.0,9001.0]],"score":0.5,"shapeType":"POLYGON"}]},
  #        "message":null,"errorCode":null}
  docker logs --tail 20 klid-ai-server | grep "mock fallback"
  # → WARNING:app.routers.sam2:[SAM2] track real returned no mask — prev polygon fallback
  ```
  대조군(Segment 는 정상 차단):
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/frames/77/sam2-segment \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"srcSn":77,"box":[400,400,100,100]}'
  # → 200 {"data":{"polygon":[],"score":0.0,"empty":true},"message":"AI 모델 미로드 — 결과 신뢰 불가"}
  ```
- **영향**: **데이터 무결성 / 학습데이터 품질**(CWE-393 Return of Wrong Status Code 계열, 신뢰경계 미표시). ①ai-server 모델 미로드(`AI_MOCK_MODE=true`·가중치 부재·로드 실패)나 실모델의 mask 실패 시, 작업자는 **"AI 추적 완료"** 성공 토스트를 보고 가짜 좌표를 작업본에 받아들인다. ②그 좌표는 `PUT /v1/frames/{srcSn}/labels` 로 확정되면 실제 학습데이터가 되고, `AUTO_LBL_YN='Y'`+`AUTO_SAM2` provenance 까지 붙어 **"AI 가 추적한 라벨"로 데이터마트에 나간다**. ③mock 폴백은 이전 폴리곤을 그대로 반사하므로 N프레임 전부 **동일 좌표**가 되어 트랙 보간·품질검사에서도 "정지한 객체"로 보일 뿐 오류로 식별되지 않는다. ④부수적으로, 반사된 좌표가 이미지 경계를 벗어나 있으면(track 의 `validatePolygon` 은 상한 검사가 없다 — `C-ISSUE-62` 참조) 저장 단계(`LabelService.validateWithinBounds`)에서야 400 이 나 작업자는 원인 불명의 저장 실패를 겪는다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`Sam2TrackResponse` 에 `mock`/`source`/`mock_reason` 3필드 추가(하위호환 생성자 병행) → ②`Sam2TrackService` 에서 `aiRes.mock()` 이면 해당 프레임을 결과에서 제외하거나(Segment 의 `empty()` 대칭) 응답에 표식 전파 → ③`Sam2TrackResponseDto.TrackedItem` 또는 `Sam2TrackResponseDto` 상위에 표식 노출 + 컨트롤러가 `Sam2SegmentResponse.MOCK_UNAVAILABLE_MESSAGE` 를 재사용 → ④FE `handleTracked` 에 mock·저신뢰 차단 분기 추가(Segment 의 `OverlayLayer.tsx:408-417` 과 동일 규약). 회귀 가드로 `Sam2TrackServiceTest` 에 mock 응답 케이스, FE `sam2-track.test.tsx` 에 자동병합 차단 케이스 추가.

### [C-ISSUE-62] TC-SAM2-23 — SAM2 Track 이 ai-server 응답 오류를 400(클라이언트 귀책)으로 반환하고, 응답 폴리곤 최소 정점 수를 검증하지 않는다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그 기대값은 **502 `EXTERNAL_API_ERROR`** 다. 외부 시스템(ai-server)이 규약 위반 응답을 보낸 것은 **클라이언트 귀책이 아니므로** 400 이 아니라 502 여야 한다(형제 경로 `Sam2SegmentService.validatePolygon:159-175` 은 정확히 `EXTERNAL_API_ERROR` 를 던진다). 또한 폐곡선 폴리곤의 최소 정점 수(3) 검사도 segment 와 동일하게 있어야 한다.
- **현재 동작(이슈 내용)**:
  - `backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2TrackService.java:119`
    ```java
    // 외부 시스템 응답도 신뢰하지 않음 — 동일 좌표 검증.
    validatePolygon(aiRes.polygon(), "ai-server polygon");
    ```
    이 `validatePolygon(:188-204)` 은 **요청 `prevPolygon` 과 공용**이며 전부 `ErrorCode.INVALID_INPUT`(=`HttpStatus.BAD_REQUEST`, `ErrorCode.java:6`)을 던진다 → 외부 응답 오염이 **400** 으로 나간다.
  - 같은 메서드에 **최소 정점 수 검사가 없다**:
    ```java
    private void validatePolygon(List<List<Double>> polygon, String fieldName) {
        if (polygon == null || polygon.isEmpty()) { ... }   // ← null/빈 만 거부
        for (List<Double> pair : polygon) { /* size!=2, null, 비유한, 음수 */ }
    }
    ```
    segment 의 `MIN_POLYGON_POINTS(=3)` 대칭 검사가 부재하므로 ai-server 가 **1~2정점 폴리곤**을 반환해도 그대로 통과해 FE 작업본에 병합된다(요청 `prevPolygon` 은 DTO `@Size(min=3)` 로 막히지만 **응답 경로는 무방비**).
  - 상한(이미지 경계) 검사도 없다 — segment 는 `x > imgWidth` 를 502 로 막지만 track 은 `x < 0` 만 본다. `C-ISSUE-61` 실측에서 `[[9000,9000],…]` 이 그대로 200 으로 나온 것이 그 증거다.
  - 회귀 테스트가 현재 동작을 **기대값으로 고정**하고 있다: `backend/src/test/java/kr/co/cudo/authoring/label/Sam2TrackServiceTest.java:272-285 sam2_track_ai응답폴리곤_검증실패시_400` — 음수 좌표 응답 스텁에 대해 `ErrorCode.INVALID_INPUT` 을 단언. 즉 코드를 502 로 고치면 이 테스트가 깨진다(정책 확정 필요).
- **재현/확인 경로**: 실모델이 오염 폴리곤을 반환하도록 강제할 수 없어 정적·테스트 대조로 확인.
  ```bash
  sed -n '115,120p;188,204p' backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2TrackService.java
  sed -n '1,10p' backend/src/main/java/kr/co/cudo/authoring/common/exception/ErrorCode.java   # INVALID_INPUT = BAD_REQUEST
  sed -n '270,285p' backend/src/test/java/kr/co/cudo/authoring/label/Sam2TrackServiceTest.java
  ```
- **영향**: ①**오류 귀속 왜곡** — FE·운영자가 400 을 보고 "내 요청이 잘못됐다"고 판단해 ai-server 장애를 놓친다(관측성 저하). 동일 실패에서 segment 는 502, track 은 400 으로 갈려 계약이 비일관하다. ②**미검증 정점부족·경계초과 좌표 유입** — 1~2정점 폴리곤은 폐곡선이 아니라 캔버스 렌더·MASK/RLE 변환·트랙 보간에서 하류 오류를 유발하고, 경계 초과 좌표는 저장 단계(`validateWithinBounds`)에서야 400 이 나 작업 손실로 이어진다. ③보안 등급은 낮음 — 응답 메시지 `"ai-server polygon 좌표는 …"` 이 내부 컴포넌트명을 노출하나 호스트·경로는 없다(CWE-209 경미).
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `validatePolygon` 을 **요청용/응답용 2개로 분리**해 ①요청 경로는 현행 `INVALID_INPUT`(400) 유지 ②응답 경로는 `EXTERNAL_API_ERROR`(502) + `MIN_POLYGON_POINTS(3)` + 이미지 실측 상한(segment 처럼 `readImageSize` 로 확보) 검사 추가. `Sam2TrackServiceTest:272` 는 502 기대로 갱신. ⚠ 카탈로그 기대값을 코드에 맞춰 400 으로 내리는 선택도 가능하나, 그 경우에도 **정점부족·상한 미검증**은 별개로 남으므로 최소한 그 두 가드는 추가해야 한다.

### [C-ISSUE-63] TC-SAM2-27 인접 — `Sam2SegmentService` 가 예외 원문을 응답 본문에 그대로 실어 내부 ai-server URL·파일 경로를 노출한다 (CWE-209)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `rules/security.md` "System Information Leak (CWE-209)" · `api-design.md` *"스택트레이스, 내부 경로 등 기술 정보 절대 포함 금지"*. 형제 경로 `Sam2TrackService.java:108-114` 는 정확히 이 규약을 지킨다 — 예외 원문은 `LogSanitizer.sanitize` 를 거쳐 **서버 로그로만** 가고 클라이언트에는 `"SAM2 track 호출에 실패했습니다."` 라는 일반화 메시지만 나간다.
- **현재 동작(이슈 내용)**: `backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java` 4곳이 `e.getMessage()` 를 클라이언트 메시지에 직접 연결한다.
  ```java
  :115-118  } catch (Exception e) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "SAM2 segment 호출 실패: " + e.getMessage());   // ← 내부 URL 노출
            }
  :194      throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 크기 확인 실패: " + e.getMessage());
  :207      throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 읽기 실패: " + e.getMessage());
  :217      throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 읽기 실패: " + e.getMessage());
  ```
  실동작 확인(ai-server 500 유발):
  ```
  HTTP=502
  {"success":false,"data":null,
   "message":"SAM2 segment 호출 실패: 500 Internal Server Error from POST http://klid-ai-server:9300/infer/sam2/segment",
   "errorCode":"EXTERNAL_API_ERROR"}
  ```
  → **내부 서비스 호스트명·포트·엔드포인트 경로**가 인증된 WORKER 응답으로 그대로 나간다. `:194/:207/:217` 의 `IOException.getMessage()` 는 통상 **파일 절대경로**(`/app/storage/deidentified/frames/deid/…`)를 포함하므로 스토리지 레이아웃까지 노출된다(CWE-209 + 경로 정보 노출).
  참고: `FrameImageEncoder.encode:190-197` 은 같은 상황에서 `"이미지 읽기에 실패했습니다."` 로 원문을 삼키도록 이미 고쳐져 있다 — `Sam2SegmentService` 만 구 패턴이 남았다.
- **재현/확인 경로**:
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
    -H 'Content-Type: application/json' -d '{"role":"WORKER","channel":"INTERNAL"}' | jq -r .data.token)
  # points 내부 좌표쌍 누락 → ai-server 500 (G-ISSUE-21) → BE 502 + URL 노출
  curl -s -X POST http://localhost:18081/api/v1/frames/77/sam2-segment \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"srcSn":77,"points":[[]]}'
  ```
- **영향**: **정보 노출(CWE-209)**. 내부 네트워크 토폴로지(서비스 DNS 명 `klid-ai-server`, 포트 9300, 추론 엔드포인트 경로)와 스토리지 절대경로가 애플리케이션 응답으로 유출된다. 저작도구는 관제/포털에서 토큰을 인계받는 다중 채널 앱이라 내부 주소 노출은 후속 SSRF·측면 이동의 정찰 정보가 된다. 즉시 착취 가능한 취약점은 아니나 Fortify/CodeQL 의 *System Information Leak* 룰에 직접 걸리는 패턴이다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** 4곳 모두 `Sam2TrackService.java:108-114` 패턴으로 통일 — `log.error(..., LogSanitizer.sanitize(e.getMessage()))` 로 서버 로그에만 남기고, `CustomException` 메시지는 `"SAM2 분할 호출에 실패했습니다."` / `"이미지를 읽을 수 없습니다."` 같은 일반화 문구로 교체(원인 예외는 `CustomException(code, msg, e)` 3-arg 로 체인). 회귀 가드로 "응답 message 에 `http://`·`/app/` 문자열이 없다"는 단언을 `Sam2SegmentServiceTest#aiServerTimeoutMapped` 에 추가.

### [C-ISSUE-64] TC-SAM2-06 — `Sam2SegmentService.resolveSafe` 는 호출부 0건의 dead code이며 카탈로그가 이를 경로순회 방어 근거로 인용하고 있다

- **심각도**: LOW
- **기대 동작(기대효과)**: 보안 가드로 문서화·인용되는 코드는 실제 실행 경로에 있어야 한다. 그렇지 않으면 다음 검증자·유지보수자가 "가드가 있다"고 믿고 실제 방어선을 점검하지 않는다(1차·2차 모두 이 라인을 근거로 PASS 판정했다).
- **현재 동작(이슈 내용)**: `Sam2SegmentService.java:179-188` 의 `private Path resolveSafe(Path baseDir, String relativePath)` 는 **어디서도 호출되지 않는다**. 클래스 javadoc `:52` 도 여전히 *"경로 순회(CWE-22): `{@link #resolveSafe}` 로 기준 디렉토리 외부 접근 차단"* 이라고 기술한다. 실제 경로 해석은 `:96` 에서 `frameImageEncoder.resolveFrameImageForInference(src)` 로 위임되며, 진짜 가드는 `FrameImageEncoder.resolveSafe:220-229`(lexical) 와 `StorageSubtreePolicy.verifyDeidentifiedFile:181-193`(**`toRealPath()` 실경로 서브트리 판정 — 심링크 우회 차단**)이다.
  ```bash
  grep -n "resolveSafe" backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java
  # 52: *   <li>경로 순회(CWE-22): {@link #resolveSafe} 로 ...   ← javadoc
  # 179:    private Path resolveSafe(Path baseDir, String relativePath) {   ← 선언만
  # (호출 0건)
  ```
- **재현/확인 경로**: 위 grep. 방어 자체는 유효함을 별도 확인 — 프레임 경로는 DB 컬럼 유래라 사용자 입력이 닿는 표면이 없고, `FrameImageEncoder`/`StorageSubtreePolicy` 이중 가드가 실행 경로에 있다.
- **영향**: **기능·보안 영향 없음**(경로순회는 실제로 차단됨). 순수 유지보수/추적성 문제 — 검증 문서의 `file:line` 근거가 무효 코드를 가리켜 **감사 추적이 허위 안전감을 준다**. `C-ISSUE-82`(동명이인 dead `TrackInterpolator`)와 같은 계열.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** dead `resolveSafe` 제거 + 클래스 javadoc `:52` 를 실제 가드(`FrameImageEncoder`/`StorageSubtreePolicy`) 위임으로 갱신. 카탈로그 TC-SAM2-06 근거도 `FrameImageEncoder.java:220-229 · StorageSubtreePolicy.java:181-193` 으로 정정.

### [C-ISSUE-65] TC-SAM2-01/14 — SAM2 프롬프트 입력좌표의 이미지 경계 사전검증 부재 (1차 C-ISSUE-61 이월·미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: 응답 폴리곤은 이미지 실측 상한까지 검증하면서(`Sam2SegmentService.validatePolygon:171`) 요청 프롬프트는 무검증인 것은 비대칭이다. 최소한 명백히 무의미한 좌표(이미지 폭·높이의 수십 배)는 외부 ai-server 로 보내기 전에 400 으로 끊는 것이 자원·계약상 합리적이다. 서비스는 이미 `:104-106` 에서 실측 `imgWidth/imgHeight` 를 확보하고 있어 추가 I/O 없이 검사 가능하다.
- **현재 동작(이슈 내용)**: `Sam2SegmentRequest.points/box`(`:33-34` — 개수 상한만) 과 `Sam2TrackRequest.prevPolygon`(`:24` — 개수 상한만) 어디에도 좌표 상한 검증이 없다. 실측:
  - `{"srcSn":77,"points":[[99999,99999]]}` → **200**, polygon 981정점 / score 0.1984 (프레임 실측 1920×1080)
  - `{"srcSn":77,"points":[[-500,-500]]}` → **200**, 정상 형태 폴리곤
  - track `prevPolygon:[[9000,9000],[9001,9000],[9001,9001]]` → **200**(음수만 `Sam2TrackService.validatePolygon:199` 로 차단, 상한은 무검증)
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/frames/77/sam2-segment \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"srcSn":77,"points":[[99999,99999]]}'   # → 200
  ```
- **영향**: 보안 취약점 아님(응답은 여전히 사후 검증되고 경로순회·주입 표면 없음). ①낭비성 외부 추론 호출(실모델 CPU 추론 1회 ≈ 수백 ms) ②`C-ISSUE-61` 과 결합 시 악화 — 이미지 밖 프롬프트는 mask 실패 → mock 폴백 확률을 높이고, track 은 그 결과를 무표식으로 자동 병합한다. 1차 대비 **상태 변화 없음**.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `Sam2SegmentService` 는 `:104-106` 직후에, `Sam2TrackService` 는 시작 프레임 이미지 치수를 확보한 뒤 `0 ≤ x ≤ imgWidth`, `0 ≤ y ≤ imgHeight` 사전 400 검증 추가 검토. ⚠ 좌표 2축 정책(★3)상 이는 **사용자 입력 경로**이므로 clamp 가 아니라 **400 거부**가 맞다.

### [C-ISSUE-66] TC-SAM2-08 인접 — 이미지 상한이 압축 바이트만 검사하고 디코딩 픽셀 수 상한이 없으며, 내부 채널 SAM2 엔드포인트에는 rate limit 이 없다

- **심각도**: LOW
- **기대 동작(기대효과)**: `rules/security.md` "Unrestricted Resource Consumption(OWASP API4:2023)" — 요청 크기 제한만으로는 부족하고 **디코딩 후 자원 사용량**까지 통제돼야 한다.
- **현재 동작(이슈 내용)**:
  1. **압축 크기만 검사** — `Sam2SegmentService.java:98-102` 는 `Files.size()`(압축 바이트)를 `maxImageBytes`(기본 20MB)와 비교한 뒤, `:199-209 readImageSize` 에서 `ImageIO.read(imagePath.toFile())` 로 **이미지를 전량 디코딩**한다. JPEG/PNG 압축비를 감안하면 20MB 파일이 수십억 픽셀로 전개될 수 있고(디컴프레션 밤, CWE-409), `BufferedImage` 는 픽셀당 3~4바이트를 힙에 잡는다. 픽셀 치수·총 픽셀 수 상한이 어디에도 없다. (`ImageIO.setUseCache`/`ImageReader` 헤더-only 치수 조회 미사용 — `ImageIO.read` 는 전체 래스터를 읽는다.)
  2. **내부 채널 rate limit 부재** — `POST /v1/frames/{srcSn}/sam2-segment` · `sam2-track` 에는 RateLimiter 배선이 없다(`grep -rn "RateLimit" label/controller/LabelController.java` → 0건). 포털 경로는 `PortalSam2Service`·`PortalUploadController` 가 per-user Resilience4j RateLimiter 를 보유해 대칭이 깨져 있다. track 은 1요청당 최대 **50회 순차 실모델 추론**을 유발하므로(TC-SAM2-16 실측 50개 완주) 인증된 계정 하나로 ai-server CPU 를 장시간 점유시킬 수 있다.
- **재현/확인 경로**:
  ```bash
  grep -n "maxImageBytes\|ImageIO.read" backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java
  grep -rn "RateLimit" backend/src/main/java/kr/co/cudo/authoring/label/controller/LabelController.java   # 0건
  grep -rn "RateLimit" backend/src/main/java/kr/co/cudo/authoring/portal/service/PortalSam2Service.java   # 존재
  ```
- **영향**: **가용성(DoS)**, 착취 난도 높음. ①디컴프레션 밤은 프레임 이미지가 **내부 ffmpeg 추출물**(경로도 DB 유래)이라 공격자가 임의 파일을 심을 표면이 사실상 없어 현실 위험은 낮다 — 다만 외부 비식별 솔루션(KPST)이 산출한 비식별 프레임도 같은 경로를 타므로 "외부 산출물을 무조건 디코딩한다"는 신뢰 가정이 남는다. ②rate limit 부재는 내부 인증 사용자(WORKER/REVIEWER) 한정이라 위협 모델상 낮으나, ai-server 는 배치 오토라벨과 GPU/CPU 를 공유하므로 배치 지연으로 번질 수 있다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`ImageIO.getImageReaders` 로 **헤더만 읽어 치수를 얻고**(전체 디코딩 없이) `width*height` 상한(예: 8K = 33M px)을 먼저 검사한 뒤 필요할 때만 디코딩 ②내부 SAM2 엔드포인트에도 `PortalSam2Service` 와 동일한 per-user Resilience4j RateLimiter 적용 검토(임계값은 운영 판단). ⚠ 실제 도입 전 "내부 작업자 편의 저하 vs 자원 보호" 트레이드오프를 사용자와 확정할 것.
