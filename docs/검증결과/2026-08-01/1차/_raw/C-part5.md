# C-part5 — C-3. TC-SAM2 (33건) + C-4. TC-KEYPOINT (13건) 전수 검증 결과

> 대상: `docs/test-cases/C-marking-labeling.md` `## C-3. TC-SAM2 — SAM2 분할 / 트랙 프록시`(TC-SAM2-01~33, 33건) +
> `## C-4. TC-KEYPOINT — 17-keypoint COCO SKELETON`(TC-KEYPOINT-01~13, 13건) = **총 46건**
> 검증일: 2026-08-01 · 회차: 1차 · 코드 기준: qa-0801 워크트리(56d30478) · 실동작 기준: 기동 중 스택(klid-backend, flyway V146)
> ⚠ **이슈 ID 주의** — 본 파트의 `C-ISSUE-81~86` 은 이 파트 전용 신규 번호다. `UNCERTAINTIES.md` 미해소 이월표의 구 `C-ISSUE-82`(동명이인 dead `common/util/TrackInterpolator`)와는 **다른 항목**이다.

## 0. 검증 환경 실측 — ★ 착수 전 전제 1건 반증됨

| 항목 | 실측값 |
|---|---|
| backend | `http://localhost:18081/api` (context-path `/api`), profile `local` |
| 토큰 | HS256 JWT 직접 조립 (`JWT_SECRET` = `docker exec klid-backend printenv`). `sub=1001`→REVIEWER, `sub=2001`/`2002`→WORKER (`GET /v1/me` 로 검증) |
| DB | `klid-postgres` / `klid_system` / 스키마 `public` |
| 검증 데이터 | raw 4(30프레임, raw+deid 양쪽) · raw 18(해상도/증강 파생, `SRC_FILE_PATH_NM`=NULL, deid 전용) · raw 27(**`DE_IDENT_YN='F'` 신고 구간**, 5프레임) · raw 35(라벨 0건, 미배정 — 키포인트 쓰기 테스트용) |
| 프레임 해상도 | 320×240 (BE 오류 메시지 `이미지=320x240` 로 실측) |

### ★★ 착수 전 가정 반증 — **ai-server SAM2 는 mock 이 아니라 실모델 로드 상태다**

지시문의 "ai-server는 YOLO/SAM2 가중치 파일이 없어 mock/폴백 모드 → 실제 분할 정확도 케이스는 BLOCKED" 는 **SAM2 에는 해당하지 않는다.**

- `docker exec klid-ai-server printenv` → `AI_MOCK_MODE=false`
- `docker logs klid-ai-server` → **YOLO 만** `[DETECT:yolox][MOCK] … reason=weights_missing`. SAM2 는 mock WARN 없이 `torchvision` 텐서 변환 로그 후 `POST /infer/sam2/segment 200`
- BE 응답도 `mock` 분기(빈 폴리곤)가 아니라 실 폴리곤 + `score=0.967` 반환

→ **C-3 의 SAM2 케이스는 BLOCKED 가 아니라 실동작 검증 가능**했고, 실제로 33건 중 26건을 실동작으로 판정했다.
(반대로 mock 분기(TC-SAM2-09)는 `_real_segment` 의 `empty_mask` 폴백을 유도해 **실동작으로 재현**했다 — 아래 참조.)

### 스택 버전 격차 영향

`stack-bringup.md` 가 기록한 구버전(V146) 격차는 **LS_DATA_INGEST 적재(B)** 와 **증강 폐기/복구(E)** 에 한정된다.
본 파트 담당 구간(`Sam2SegmentService`/`Sam2TrackService`/`FrameImageEncoder`/`LabelService` 키포인트/`KeypointSerializer`)은 커밋 범위 `b2b44f0e~56d30478` 에 **전부 미포함**(`git log --oneline` 대조) → **BLOCKED 사유 해당 없음**.

---

## 1. C-3. TC-SAM2 케이스별 판정 (33건)

| ID | 판정 | 근거 확인 | 비고 |
|----|------|-----------|------|
| TC-SAM2-01 | PASS | [실동작] `POST /v1/frames/1/sam2-segment {"srcSn":1,"points":[[100,100]]}` (WORKER 2001) → **200** `{"polygon":[[90,0],[80,8],[81,178],[119,178],[119,0]],"score":0.967,"empty":false}`. **미저장 실증** — 호출 전후 `raw_sn=4` 라벨 수 10건 불변(`select count(*) … join ls_data_src`) | `Sam2SegmentService.java:84-153`(카탈로그 84-149, 경미 드리프트). 테스트 `Sam2SegmentServiceTest#segmentWithPointReturnsPolygonAndScore`(baseline 통과) |
| TC-SAM2-02 | PASS | [실동작] path `/frames/1/…` + body `srcSn=2` → **400** `{"message":"path 의 srcSn 과 body 의 srcSn 이 다릅니다.","errorCode":"INVALID_INPUT"}` | `LabelController.java:167-171`(카탈로그 161-172 범위 포함) |
| TC-SAM2-03 | PASS | [실동작] `points`+`box` 동시 → **400** `"exactlyOnePrompt: points 또는 box 중 정확히 하나만 제공해야 합니다."` | `Sam2SegmentRequest.java:46` `@AssertTrue isExactlyOnePrompt` (XOR) — 라인 정확 |
| TC-SAM2-04 | PASS | [실동작] `{"srcSn":1}` (둘 다 null) → **400** 동일 메시지. XOR 이라 둘 다 빈 경우도 false | `Sam2SegmentRequest.java:46` |
| TC-SAM2-05 | PASS | [실동작] WORKER `sub=2001` → `srcSn=31`(raw 5, LABELER 배정자는 2002) → **403** `"본인에게 배정되지 않은 영상입니다."` | `Sam2SegmentService.java:86` → `LabelAccessGuard.verifyAccess:39-72`. AI 호출 이전 차단(ai-server 로그에 해당 요청 없음) |
| TC-SAM2-06 | PASS | [정적] 경로순회 차단은 **`FrameImageEncoder`** 가 담당 — 비식별 분기 `StorageSubtreePolicy.verifyDeidentifiedFile`(`:181-216`, **realpath 기준 서브트리 판정 + 실경로 반환**), 원본 분기 `resolveSafe`(`FrameImageEncoder.java:220-229`, `resolve().normalize()+startsWith` → `..` 차단 400). 단위테스트 `FrameImageEncoderTest:64` `encodeFrame(frame("../../etc/passwd"))` 거부 | ⚠ **근거 드리프트**: 카탈로그의 `Sam2SegmentService.java:179` 는 **dead code** — 그 `resolveSafe(Path,String)` 는 `segment()` 에서 호출되지 않는다(→ **C-ISSUE-84**). ⚠ 원본 분기는 realpath/NOFOLLOW 미적용(비식별 분기와 비대칭) — 경로값이 DB 소유라 LOW |
| TC-SAM2-07 | PASS | [실동작] `srcSn=999999` → **404** `"프레임을 찾을 수 없습니다."` (인가 가드 단계). [정적] 파일 부재 분기는 `FrameImageEncoder.java:111-113` `Files.exists` false → NOT_FOUND, deid 분기는 `Verdict.MISSING`→원본 폴백→`:108` NOT_FOUND. 단위테스트 `FrameImageEncoderTest:72` | 카탈로그 근거 `:89, :96` 정확 |
| TC-SAM2-08 | PASS | [정적] `Sam2SegmentService.java:98-102` — `fileSize()` 로 **b64 인코딩·ImageIO 이전**에 `maxImageBytes`(기본 20MB) 초과 시 `PAYLOAD_TOO_LARGE`. `ErrorCode:24` = `HttpStatus.PAYLOAD_TOO_LARGE`(413). 단위테스트 `Sam2SegmentServiceTest#imageTooLargeRejected` | 로컬 프레임이 13KB 급이라 실동작 유도 불가(설정 변경 금지) |
| TC-SAM2-09 | PASS | [실동작] **mock 분기를 실제로 유도** — `{"srcSn":1,"box":[10000,10000,10001,10001]}` → ai-server `_real_segment` 가 마스크 미생성 → `mock=true, mock_reason=empty_mask` 폴백 → BE **200** `{"polygon":[],"score":0.0,"empty":true}` + `"message":"AI 모델 미로드 — 결과 신뢰 불가"`. 역박스(`[100,100,10,10]`)도 동일. **FE 차단 배선 확인** — `OverlayLayer.tsx:578-583` `if (res.polygon.length === 0) { setSegPreview(null); onMockWarning?.(res); return; }` (자동적용 이전, 저신뢰 분기보다 **선행**) | `Sam2SegmentService.java:124-127` + `LabelController.java:173-177`(카탈로그 174-178, off-by-1). ⚠ 메시지가 `empty_mask`(모델 로드됨) 경우에도 "모델 미로드"로 나감 → **C-ISSUE-86**(LOW) |
| TC-SAM2-10 | PASS | [정적] `Sam2SegmentService.java:159-163` `polygon.size() < MIN_POLYGON_POINTS(3)` → `EXTERNAL_API_ERROR`(`ErrorCode:28` = `BAD_GATEWAY` **502**). 단위테스트 `Sam2SegmentServiceTest#lessThanThreePointsRejected` | 실모델이 <3점을 반환하면 ai-server 가 mock 폴백하므로 실동작 유도 불가 |
| TC-SAM2-11 | PASS | [정적] `:164-175` — 원소 크기≠2 / null / 음수 / `x>imgWidth` / `y>imgHeight` 전부 502. 상한은 `readImageSize`(`:199-209`)로 **파일 실측**한 값 사용(요청값 아님). 단위테스트 `#polygonOutOfImageBoundsRejected` | 경계 정책: `x == width` 허용(`LabelService.validateWithinBounds` javadoc `:744-745` 와 동일 기준 — ★3 정합) |
| TC-SAM2-12 | PASS | [실동작] `simplifyTolerance:60` → **400** `"simplifyTolerance: 경계 세밀함은 50.0 이하여야 합니다."` / `-1` → **400** `"…0.0 이상이어야 합니다."` (하한도 함께 확인) | `Sam2SegmentRequest.java:35-36` 라인 정확 |
| TC-SAM2-13 | PASS | [정적] `Sam2SegmentService.java:144-147` — `outPolygon.size() < 3` 이면 `aiRes.polygon()` 원본으로 되돌림. 단위테스트 `#simplifyBelowMinKeepsOriginalPolygon` + `#simplifySystemConfigFailureFallsBack`(설정 조회 실패 시 상수 1.0 폴백) | ⚠ 근거 드리프트: 카탈로그 `:132-143` → 실제 단순화 블록 `131-147`, 3점미만 폴백은 `144-147` |
| TC-SAM2-14 | PASS | [실동작] `POST /frames/1/sam2-track` `nextSrcSns:[2,3]` → **200**, 프레임별 폴리곤 2건(`srcSn:2`, `srcSn:3`) + `shapeType:"POLYGON"`. **미저장 실증** — raw 4 라벨 수 10건 불변 | `Sam2TrackService.java:67-148`. ⚠ 이 경로에는 **mock 게이트가 없다** → **C-ISSUE-81**(케이스 자체의 단언은 충족하나 카탈로그 미커버 결함) |
| TC-SAM2-15 | PASS | [실동작] path 1 + body `srcSn=2` → **400** 동일 메시지 | `LabelController.java:138-142`(카탈로그 132-143 범위 포함) |
| TC-SAM2-16 | PASS | [실동작] `nextSrcSns` 51개 → **400** `"nextSrcSns: size must be between 0 and 50"`. **경계 실측** — 정확히 50개는 **200**(50프레임 전파 완료, 로그 `count=50`) | `Sam2TrackRequest.java:26` `@NotEmpty @Size(max=50)` |
| TC-SAM2-17 | PASS | [실동작] `nextSrcSns:[]` → **400** `"must not be empty"` | `Sam2TrackRequest.java:26` |
| TC-SAM2-18 | PASS | [실동작] `prevPolygon` 2점 → **400** `"prevPolygon: size must be between 3 and 1000"` | `Sam2TrackRequest.java:24` |
| TC-SAM2-19 | PASS | [실동작] 1001점 → **400**. **경계 실측** — 정확히 1000점은 **200** | `Sam2TrackRequest.java:24` |
| TC-SAM2-20 | PASS | [실동작] `trackId` 65자 → **400** `"trackId: size must be between 0 and 64"`. **경계 실측** — 64자는 200(응답 `trackId` 64자 그대로 에코) | `Sam2TrackRequest.java:23`. `TRCK_ID VARCHAR(30)` 컬럼보다 크지만 track 은 미저장 경로라 정합 문제 없음 |
| TC-SAM2-21 | PASS | [실동작] 시작 `srcSn=1`(배정됨) + `nextSrcSns=[31]`(raw 5, 미배정) → **403** `"본인에게 배정되지 않은 영상입니다."`. **AI 호출 이전 실증** — ai-server 로그에 해당 track 요청 0건(루프 진입 즉시 `verifyAccess(nextSrcSn)` 에서 종료) | `Sam2TrackService.java:69`(시작), `:90`(후속) — 라인 정확 |
| TC-SAM2-22 | PASS | [실동작] `nextSrcSns=[999999]` → **404** `"프레임을 찾을 수 없습니다."` | `Sam2TrackService.java:92-93`. ⚠ 메시지는 실제로 `"후속 프레임을 찾을 수 없습니다: 999999"` 가 아닌 가드 단계(`:90` verifyAccess) 의 문구 — 후속 프레임은 인가 검사가 먼저라 `:93` 이 도달 불가(무해, 코드 도달성 노트) |
| **TC-SAM2-23** | **FAIL** | [정적+실동작 반증] 기대 **502 EXTERNAL_API_ERROR** ↔ 실제 **400 INVALID_INPUT**. `Sam2TrackService.java:119` 가 요청 검증과 **동일한** `validatePolygon(…)`(`:188-204`)을 재사용하는데 이 메서드는 `ErrorCode.INVALID_INPUT`(=400) 을 던진다. 게다가 ①**정점 수 하한 미검증**(비어있지 않기만 하면 통과 — segment 의 `MIN_POLYGON_POINTS=3` 상당 검사 없음) ②**이미지 경계 상한 미검증**(segment 의 `x>imgWidth` 상당 없음) ③`aiRes.score()` **미클램프**(segment 는 `clampScore` 로 [0,1]+4자리 반올림). 실동작 근거: `POST /frames/1/sam2-track` 응답 `score=4.187454578641336E-6` 원본 그대로 노출. 프로젝트 테스트 자체가 현행을 고정 — `Sam2TrackServiceTest#aiPolygonInvalid400` | → **C-ISSUE-82** |
| TC-SAM2-24 | PASS | [실동작] `shape:"BBOX"` → `{"points":[[22.0,7.0],[79.0,107.0]],"shapeType":"BBOX"}` = `[[minX,minY],[maxX,maxY]]` | `Sam2TrackService.java:171-185` 라인 정확 |
| TC-SAM2-25 | PASS | [실동작] `shape:"BBOX"` + `prevPolygon:[[5,5],[5.2,5],[5.2,5.2]]`(퇴화 시드) + `nextSrcSns:[2,3]` → **200** `{"tracked":[]}` — 두 프레임 모두 스킵되고 **예외 없이 정상 종료**(전체 추적 미중단). 서버 로그 `[Sam2Track] degenerate bbox skipped nextSrcSn=…` | `Sam2TrackService.java:128-137`(스킵) + `:181-183`(`MIN_BBOX_EXTENT=1.0`) 라인 정확 |
| TC-SAM2-26 | PASS | [실동작] `"shape":null` 명시 → 200 `shapeType:"POLYGON"` / `shape` 키 생략 → 동일. 잘못된 문자열(`"CIRCLE"`)은 Jackson 이 **400** `"요청 본문이 올바르지 않습니다."` 로 차단 | `Sam2TrackService.java:73` `req.shapeOrDefault()` + `Sam2TrackRequest.java:39-41` |
| TC-SAM2-27 | PASS | [정적] `Sam2TrackService.java:108-114` — `catch (Exception e)` → 로그는 `LogSanitizer.sanitize(e.getMessage())` 로만 남기고 클라이언트에는 일반화 메시지 `"SAM2 track 호출에 실패했습니다."` + `EXTERNAL_API_ERROR`(502). CWE-209 정합. 단위테스트 `Sam2TrackServiceTest#aiCallFailure502` + `#aiPolygonNull502` | ai-server 를 죽이는 것은 병렬 에이전트 방해라 미수행 |
| TC-SAM2-28 | PASS | [실동작] `trackId:"tid\r\nINJECTED-LOG-LINE"` 전송 → 서버 로그 **한 줄**로 `trackId=tidINJECTED-LOG-LINE`(CRLF 제거, 로그 라인 위조 실패). `docker logs klid-backend` 원문 확인 | `Sam2TrackService.java:144-146` `LogSanitizer.sanitize` — 라인 정확 (CWE-117) |
| TC-SAM2-29 | PASS | [실동작] **신고 구간 실데이터**(raw 27, `DE_IDENT_YN='F'`, `LS_DEIDENT_REPORT` OPEN) `srcSn=301` → **412** `{"message":"비식별 재처리 대기 중인 영상은 AI 추론을 실행할 수 없습니다.","errorCode":"PRECONDITION_FAILED"}`. **REVIEWER 도 동일 412**(역할 무관 프리컨디션 실증). **ai-server 호출 0건**(로그 무발생) = 전송 후 폐기가 아니라 **읽기 전 차단** | `Sam2SegmentService.java:96` → `FrameImageEncoder.java:133-136`(게이트 먼저) → `:206-217` — 라인 정확 |
| TC-SAM2-30 | PASS | [실동작] ①시작 프레임이 신고 구간(`srcSn=301`) → **412** ②**시작은 정상(raw 4 `srcSn=1`) + 후속만 신고 구간(`srcSn=302`) → 412** = 프레임마다 게이트 평가 실증 ③**인가 우선 순서 실증** — 미배정 WORKER(2002)가 같은 `srcSn=301` 호출 시 412 가 아니라 **403** → 412 가 미인가자에게 영상 상태를 알려주는 오라클이 되지 않음(CWE-209) | `Sam2TrackService.java:86`(시작), `:95`(후속) → `FrameImageEncoder.java:178-180` — 라인 정확 |
| TC-SAM2-31 | PASS | [정적] 소스 전수 스캔 — `grep -rn "encodeToBase64" backend/src` 결과 **javadoc 주석 5건뿐, 메서드 정의·호출 0건**. `FrameImageEncoder.encode(Path)` 는 `private`(`:190`). 외부 진입점은 `LsDataSrc` 를 받는 3개(`resolveFrameImageForInference` / `encodeFrame` / `encodeDeidentifiedFrameForInference`)뿐이며 **전부 게이트 통과** | `FrameImageEncoder.java:182-197` 라인 정확. 실소비자 실측: `Sam2SegmentService:96`·`Sam2TrackService:86,95`·`YoloTrackService:116`·`AutolabelOnlineService:222`·`PortalSam2Service:115,152,167` — 게이트 없는 경로 0건 |
| TC-SAM2-32 | PASS | [정적] `FrameImageEncoder.java:94` `Path resolveFrameImageWithoutGate(LsDataSrc)` — **접근제어자 없음 = package-private** 확인. 소비자 전수 `grep` 결과 `FrameBoundsResolver.java:101` **1건뿐**이고, 그 사용처(`measure()`, `:100-120`)는 `ImageIO.read` 후 `img.getWidth()/getHeight()` **치수만** 반환(픽셀·경로 미유출) | ⚠ **카탈로그 서술 오류**: "유일한 **패키지 외** 소비자"라고 적혀 있으나 `FrameBoundsResolver` 는 **동일 패키지** `kr.co.cudo.authoring.label.service`(파일 1행 확인). 단언의 실질(package-private + 치수만 소비)은 성립 |
| TC-SAM2-33 | PASS | [실동작] 해상도/증강 파생 프레임 `srcSn=45`(raw 18, `SRC_FILE_PATH_NM` **NULL**, deid 경로만 존재) → **200** `{"polygon":[[76,9],…,[78,9]],"score":0.9652}`. 400 `"이미지 경로가 비어있습니다"` **미발생** | `FrameImageEncoder.java:94-115` — deid 우선 → `verifyDeidentifiedFile` ok → 반환. 라인 정확 |

### C-3 집계

| 판정 | 건수 |
|---|---:|
| PASS | 32 |
| FAIL | 1 (TC-SAM2-23) |
| PARTIAL / BLOCKED / N/A / 확인필요 | 0 |
| **합계** | **33** |

근거 확인 내역: **[실동작] 24건 · [정적] 9건**(08·10·11·13·27·31·32 + 06·07 은 실동작+정적 혼합).

---

## 2. C-4. TC-KEYPOINT 케이스별 판정 (13건)

검증 경로: `PUT /api/v1/frames/318/labels`(raw 35, 라벨 0건·미배정 → REVIEWER 토큰). **검증 후 `{"items":[]}` 로 원복**하여 `ls_data_lbl` 0건 상태 복구 확인.

| ID | 판정 | 근거 확인 | 비고 |
|----|------|-----------|------|
| TC-KEYPOINT-01 | PASS | [실동작] `lblTypeCd=SKELETON` + `[[10,20,2]]×17` → **200**. DB 실측 `select point_cn from ls_data_lbl where src_sn=318` → `[[10.0,20.0,2],[10.0,20.0,2],…]` **17개 삼중값**(x·y=double, v=int). 응답 `labelVersion` 1→2 증가 | `LabelService.java:792-825`(검증) + `:834-838`(`serializePoints` SKELETON 분기 → `KeypointSerializer.toJson`). 라인 정확. 테스트 `LabelServiceKeypointTest#SKELETON_17개_삼중값_저장후_조회시_동일값_반환` |
| TC-KEYPOINT-02 | PASS | [실동작] 16개 → **400** `"SKELETON 키포인트는 정확히 17 개여야 합니다."`. **상한도 반증** — 18개도 동일 400, 3개도 동일 400 (`!=` 비교라 양방향 차단) | `LabelService.java:793-796` 라인 정확 |
| TC-KEYPOINT-03 | PASS | [실동작] `[[10,20]]×17`(2-튜플) → **400** `"키포인트는 [x, y, v] 형태여야 합니다."`. **초과도 반증** — `[10,20,2,9]`(4원소) 동일 400. `null` 원소(triplet 자체가 null) 도 동일 400 | `LabelService.java:797-801` 라인 정확 |
| TC-KEYPOINT-04 | PASS | [실동작] `[10,20,null]` → **400** `"키포인트 좌표에 null 원소가 있습니다."` — **500 아님**(언박싱 NPE 방어 성립). 역방향 반증: x·y 위치 null 도 같은 분기 | `LabelService.java:806-811` — `Double xBox/yBox/vBox` 를 언박싱 **이전**에 null 체크. 카탈로그 `:805-811` 정확 |
| TC-KEYPOINT-05 | PASS | [실동작] `v=3` → **400** `"가시성 v 는 0/1/2 중 하나여야 합니다."`. **추가 반증** — `v=1.5`(소수) 도 동일 400 (`int v=(int)vRaw; if (v != vRaw …)` 로 비정수 차단), `v=-1` 도 400 | `LabelService.java:815-819`(카탈로그 816-819) |
| TC-KEYPOINT-06 | PASS | [실동작] `[[0,0,0]]` + 나머지 16개 → **200** 저장 성공. DB `point_cn` 선두가 `[0.0,0.0,0]` 로 보존 | `LabelService.java:820-823` — `x<0\|\|y<0` 만 차단하므로 0 은 통과. 라인 정확 |
| TC-KEYPOINT-07 | PASS | [실동작] `[-1,20,2]` → **400** `"좌표는 0 이상이어야 합니다 (x=-1.0, y=20.0)"` | `LabelService.java:820-823`. ⚠ 실제 구현은 **v 값과 무관하게** 음수를 차단(카탈로그의 "v>0" 한정보다 엄격 — 안전 방향). ⚠ **테스트 공백** → C-ISSUE-85 |
| TC-KEYPOINT-08 | PASS | [실동작] DB 직렬화 결과가 `[[10.0,20.0,2],…]` 로 **결정적**(x·y 는 `double`, v 는 `int` — 타입 혼합이 아니라 `KeypointPoint` 레코드 타입 그대로). 단위테스트 `KeypointSerializerTest#toJsonTripletFormat` 이 `"[[1.5,2.5,2]]"` 정확 일치 단언 | `KeypointSerializer.java:46-60` — `List.of(kp.x(), kp.y(), kp.v())` 순서 고정. 라인 정확 |
| TC-KEYPOINT-09 | PASS | [정적] `KeypointSerializer.java:80-82` — `if (!root.isArray()) throw new IllegalArgumentException("키포인트는 배열이어야 합니다")`. 예외 메시지에 원본 JSON 미포함(CWE-117/209) | ⚠ **테스트 공백** — `KeypointSerializerTest` 4건 중 비배열 케이스 없음 → C-ISSUE-85 |
| TC-KEYPOINT-10 | PASS | [실동작+정적] `:85-87` `!triplet.isArray() \|\| triplet.size() != TRIPLET_SIZE` → IAE. 단위테스트 `#rejectsTwoTuple`(`"[[1.0,2.0]]"`). 서비스 경유 실동작으로도 2원소/4원소 모두 400 확인(TC-KEYPOINT-03) | 라인 정확 |
| TC-KEYPOINT-11 | PASS | [정적] `:88-93` — `x/y/v` 각각 `isNumber()` 검사. 문자열 원소·JSON `null` 원소(`NullNode.isNumber()==false`) 모두 IAE | ⚠ **테스트 공백** → C-ISSUE-85. 서비스 요청 표면은 `List<List<Double>>` 역직렬화라 문자열이 400 으로 먼저 차단됨(이 분기는 DB 적재값 재파싱 경로 방어) |
| TC-KEYPOINT-12 | PASS | [정적] `:70-72` — `json == null \|\| isBlank() \|\| "[]".equals(json.trim())` → `List.of()`. `"[ ]"` 같은 변형도 `readTree` → 빈 배열 → 루프 미실행 → 빈 리스트. 단위테스트 `#emptyInputs`(null·`"[]"` 양쪽) | 카탈로그 `:69-81` 범위 포함 |
| TC-KEYPOINT-13 | PASS | [실동작] 삼중값 저장(`lbl_sn=600`) 후 **동일 값 재저장** → 200 이지만 `labelVersion` **2 그대로**(미증가) 이고 `LS_DATA_LBL_HSTRY` 행 **추가 0건**(59·60 이후 신규 없음) = 무변경 판정 성립. 이어서 첫 점만 `[11,20,2]` 로 변경 → hstry `61` `kind:"UPDATED"` 생성 + before/after 삼중값 보존 | `LabelService.java:568-585` — `LabelPointSerializer.fromJson`(2-튜플 파서)이 삼중값을 거부 → `catch` → raw `List<List<Double>>` 폴백 비교. 라인 정확. ⚠ **테스트 공백** → C-ISSUE-85 |

### C-4 집계

| 판정 | 건수 |
|---|---:|
| PASS | 13 |
| FAIL / PARTIAL / BLOCKED / N/A / 확인필요 | 0 |
| **합계** | **13** |

근거 확인 내역: **[실동작] 9건 · [정적] 4건**(09·11·12 + 10 은 혼합).

### C-4 추가 반증(카탈로그 외 경계값) — 전부 fail-secure 확인

| 반증 입력 | 결과 |
|---|---|
| `SKELETON` + `x=99999` (이미지 320×240) | **400** `"좌표가 이미지 경계를 벗어났습니다 (x=99999.0, y=20.0, 이미지=320x240)"` — 상한 검증이 삼중값 경로에도 적용됨(`LabelService.validateWithinBounds:738-742` 문서화된 SKELETON 분기 실증, ★3 정합) |
| `SKELETON` + `x=1e308` | **400** 동일(오버플로 우회 없음) |
| `SKELETON` + `x=NaN` | **400** `"요청 본문이 올바르지 않습니다."` — Jackson 이 `ALLOW_NON_NUMERIC_NUMBERS` 미활성이라 역직렬화 단계에서 차단. **NaN 이 `x<0`·`x>width` 를 둘 다 우회해 저장되는 시나리오는 성립하지 않음** |
| `BBOX` + 삼중값 좌표 | **400** `"좌표는 [x, y] 형태여야 합니다."` — 타입 라우팅 역방향 차단 |
| SAM2 track `prevPolygon` + `NaN` / segment `points` + `NaN` | 둘 다 **400**(Jackson 단계) |

---

## 3. 총계 (46건)

| 클러스터 구간 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| C-3 TC-SAM2 | 33 | 32 | 1 | 0 | 0 | 0 | 0 |
| C-4 TC-KEYPOINT | 13 | 13 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **46** | **45** | **1** | **0** | **0** | **0** | **0** |

> ⚠ **PASS 율이 높다는 사실 자체가 검증 품질의 근거가 아니다.** 카탈로그가 단언한 항목은 대부분 성립했으나, **카탈로그가 묻지 않은 축**에서 HIGH 1건(C-ISSUE-81) · MEDIUM 2건(C-ISSUE-82/83)이 나왔다. 아래 이슈 대장이 본 파트의 실질 산출물이다.

---

## 4. 이슈

### [C-ISSUE-81] TC-SAM2-14/24/26 (카탈로그 미커버 축) — SAM2 **Track** 경로에 mock 게이트가 없어 mock 좌표가 정상 결과로 FE 에 자동 적용된다

- **심각도**: **HIGH**
- **기대 동작(기대효과)**: ai-server 가 mock 응답(`mock=true`, 사유 `env_mock`/`weights_missing`/`load_failed`/`empty_mask`)을 반환하면 BE 가 이를 감지해 **좌표를 그대로 내려보내지 않아야** 한다. segment 경로가 이미 그렇게 한다(`Sam2SegmentService.java:124-127` → 빈 폴리곤 + `MOCK_UNAVAILABLE_MESSAGE` → `OverlayLayer.tsx:578-583` 자동적용 차단). 이 보호가 없으면 **모델이 없는 상태에서 만들어진 가짜 좌표가 학습데이터로 확정**된다(SFR-08 라벨링 정확도 근간 훼손).
- **현재 동작(이슈 내용)**: **BE 의 클라이언트 DTO 가 mock 신호를 아예 파싱하지 않는다.**
  ```java
  // backend/.../common/client/dto/Sam2TrackResponse.java:14-18  — mock/source/mock_reason 없음
  public record Sam2TrackResponse(
          @JsonProperty("track_id") String trackId,
          List<List<Double>> polygon,
          double score) {}
  ```
  ai-server 는 실제로 그 필드를 내보낸다(실측):
  ```
  GET http://localhost:19300/openapi.json
  → Sam2TrackResponse  ['track_id','polygon','score','mock','source','mock_reason']
    Sam2SegmentResponse ['polygon','score','mock','source','mock_reason','success','message','error_code']
  ```
  따라서 `Sam2TrackService.track()`(`:105-142`)·`PortalSam2Service.track()`(`:173-181`) 어디에도 `aiRes.mock()` 검사가 없다(`grep -rn "\.mock()" backend/src/main` → segment 2곳·online autolabel 2곳·YOLO 배치 1곳만 히트, **track 0곳**).
  ai-server 의 mock track 은 특히 위험하다 — `ai-server/app/routers/sam2.py:317-326`:
  ```python
  def _mock_track(req, reason="env_mock"):
      return Sam2TrackResponse(track_id=req.track_id,
          polygon=[list(p) for p in req.prev_polygon],   # 시드 폴리곤 그대로 복사
          score=0.9, mock=True, source="mock", mock_reason=reason)
  ```
  **score 0.9** 라 FE 의 저신뢰 분기(`SAM_LOW_CONFIDENCE_THRESHOLD`)에도 걸리지 않는다. 즉 SAM2 가중치가 없으면 "N 프레임 추적"이 **시드 폴리곤 N개 복제**로 조용히 둔갑한다.
- **재현/확인 경로**:
  1. 현 스택은 SAM2 가중치가 **있어서** 실모델로 동작한다. 그러나 **동일 컨테이너의 YOLO 는 이미 `weights_missing`** 이다(`docker logs klid-ai-server` → `[DETECT:yolox][MOCK] … reason=weights_missing`) — SAM2 가중치 누락도 동일하게 발생 가능한 배포 상태다.
  2. `ai-server` 를 `AI_MOCK_MODE=true` 로 기동하거나 SAM2 가중치를 제거한 뒤:
     `curl -X POST -H "Authorization: Bearer <WORKER>" -d '{"srcSn":1,"trackId":"t","prevPolygon":[[10,10],[100,10],[100,100]],"label":"person","nextSrcSns":[2,3]}' localhost:18081/api/v1/frames/1/sam2-track`
     → 기대: 빈 결과 + 경고 메시지 / 실제(예상): 200 + 시드 폴리곤 복제 2건 + `score 0.9`, 경고 없음.
  3. 대조군(현행 정상 동작): 같은 스택에서 segment 는 mock 유도 시 차단됨 — `-d '{"srcSn":1,"box":[10000,10000,10001,10001]}'` → `{"polygon":[],"empty":true}` + `"message":"AI 모델 미로드 — 결과 신뢰 불가"`.
- **영향**: 데이터 정합/학습데이터 오염(CWE-345 불충분한 데이터 진정성 검증). 라벨은 검수 승인 시 `LS_LABEL_VERSION` 스냅샷 + export + 관제 `TASK_COMPLETED` 로 흘러가므로 오염이 데이터마트까지 전파된다. 포털 SAM2 track(`PortalSam2Service.track`)도 동일 결함이며, 포털은 오토라벨 미제공 채널이라 사용자가 결과를 신뢰할 근거가 더 약하다.
- **수정 방향(제안)**: ⚠ 구현하지 않음.
  1. `common/client/dto/Sam2TrackResponse` 에 `mock` / `source` / `@JsonProperty("mock_reason") mockReason` 3필드 추가(`Sam2Response` 와 동일 형태 + 기존 3-arg 호환 생성자 유지).
  2. `Sam2TrackService.track()` 의 응답 검증 지점(`:115-119`)에 `if (aiRes.mock()) { … }` 을 넣어 **해당 프레임을 결과에서 제외**하고, 루프 종료 후 mock 이 1건이라도 있었으면 컨트롤러가 `Sam2SegmentResponse.MOCK_UNAVAILABLE_MESSAGE` 와 동등한 안내를 `ApiResponse.message` 에 세팅(= segment 규약과 동형).
  3. `PortalSam2Service.track()` 에도 같은 배선(현재 segment 만 `:125` 에 있음).
  4. 회귀 가드: "ai-server 가 내보내는 mock 메타를 BE DTO 가 전부 보유한다"는 계약 드리프트 테스트(기존 `CocoClassesDriftTest` 패턴 재사용).
  5. 카탈로그에도 대응 케이스 신설 권장 — `TC-SAM2-3x: track mock→결과 제외+안내 메시지`(현재 C-3 에 track mock 케이스가 **없다**).

---

### [C-ISSUE-82] TC-SAM2-23 — SAM2 track 의 **외부 응답** 검증이 요청 검증과 동일 코드라 400 으로 나가고, 정점 수·이미지 경계·score 를 검증하지 않는다

> ⚠ 이 번호는 본 파트 신규 번호다. `UNCERTAINTIES.md` 이월표의 구 `C-ISSUE-82`(dead `common/util/TrackInterpolator`)와 무관.

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그 기대결과는 **502 EXTERNAL_API_ERROR**. 외부 시스템(ai-server)의 잘못된 응답을 **클라이언트 입력 오류(400)로 오귀속하면 안 된다** — FE·운영자가 "내가 잘못 보냈다"로 오진단해 실제 장애(모델 이상·계약 드리프트)를 놓친다. segment 경로는 이미 502 로 처리한다(`Sam2SegmentService.java:159-175`). 또한 "외부 응답 불신" 원칙상 **정점 수 하한·이미지 경계 상한·score 범위**를 segment 와 동등하게 검사해야 한다.
- **현재 동작(이슈 내용)**: 요청 검증과 응답 검증이 **같은 메서드**를 공유한다.
  ```java
  // Sam2TrackService.java:71   (요청)
  validatePolygon(req.prevPolygon(), "prevPolygon");
  // Sam2TrackService.java:119  (외부 응답) — 같은 메서드
  validatePolygon(aiRes.polygon(), "ai-server polygon");

  // Sam2TrackService.java:188-204
  private void validatePolygon(List<List<Double>> polygon, String fieldName) {
      if (polygon == null || polygon.isEmpty())            // ← 정점 수 하한(3) 검사 없음
          throw new CustomException(ErrorCode.INVALID_INPUT, …);   // ← 400
      … if (… x < 0 || y < 0) throw new CustomException(ErrorCode.INVALID_INPUT, …);  // ← 상한 검사 없음
  }
  ```
  결과 3가지 갭:
  ① **응답코드 불일치** — `ErrorCode.INVALID_INPUT` = `HttpStatus.BAD_REQUEST`(`ErrorCode.java:6`), 기대는 `EXTERNAL_API_ERROR` = `BAD_GATEWAY`(`ErrorCode.java:28`). 프로젝트 테스트가 현행을 고정 중 — `Sam2TrackServiceTest#aiPolygonInvalid400`.
  ② **정점 수 미검증** — ai-server 가 1~2점 폴리곤을 돌려줘도 통과해 FE 로 나간다(segment 는 `MIN_POLYGON_POINTS=3` 으로 502).
  ③ **경계 상한·score 미검증** — segment 는 `x>imgWidth` 502 + `clampScore` [0,1] 4자리 반올림. track 은 둘 다 없어 실측 응답이 `"score":4.187454578641336E-6` 로 원본 노출:
  ```
  POST /api/v1/frames/1/sam2-track → 200
  {"tracked":[{"srcSn":2,…,"score":4.187454578641336E-6,"shapeType":"POLYGON"}]}
  ```
- **재현/확인 경로**:
  - 코드 대조: `Sam2TrackService.java:119,188-204` vs `Sam2SegmentService.java:129,159-175`.
  - score 미클램프 실동작: `curl -X POST -H "Authorization: Bearer <WORKER>" -H 'Content-Type: application/json' -d '{"srcSn":1,"trackId":"t","prevPolygon":[[10,10],[100,10],[100,100]],"label":"p","nextSrcSns":[2]}' localhost:18081/api/v1/frames/1/sam2-track` → 응답 `score` 필드 확인.
  - ①②는 ai-server 응답 조작이 필요(스텁) — 단위테스트 레벨 재현.
- **영향**: 기능/운영(오진단 유도) + 데이터 정합(퇴화 폴리곤·경계 밖 좌표가 FE 작업본에 유입 → 이후 `PUT /labels` 저장 시 `validateWithinBounds` 가 400 을 내 사용자에게 "저장 실패"로 나타남 — 원인이 상류에 있는데 하류에서 터진다). 보안 관점은 낮음(좌표 자체는 인가된 프레임 것).
- **수정 방향(제안)**: ⚠ 구현하지 않음. `validatePolygon(polygon, fieldName)` 을 **요청용/응답용 2개로 분리** — 응답용은 `ErrorCode.EXTERNAL_API_ERROR` + `MIN_POLYGON_POINTS(3)` + 이미지 실측 경계 상한(track 도 `FrameImageEncoder`/`FrameBoundsResolver` 로 치수 확보 가능) + `clampScore` 를 segment 와 동일 적용. 기존 `#aiPolygonInvalid400` 테스트는 기대값을 502 로 갱신. 판정 로직을 두 서비스가 공유하도록 공용 검증기로 추출하는 편이 재발 방지에 낫다.

---

### [C-ISSUE-83] TC-SAM2-01 (부수) — `Sam2SegmentService` 가 `@Transactional` 을 유지해 AI 블로킹 호출 내내 DB 커넥션을 점유한다 (track 은 이미 제거됨)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: SAM2 분할은 **DB write 가 없는 stateless 프록시**다(클래스 javadoc `:40` "BE 는 DB 저장하지 않는 stateless 프록시"). 파일 I/O + base64 + **최대 60s 블로킹 AI 호출** 구간에서 control HikariCP 커넥션을 쥐고 있으면 안 된다. `Sam2TrackService` 는 이미 그렇게 고쳤고 그 이유를 코드에 남겼다 — `Sam2TrackService.java:34-36`: *"비트랜잭셔널(F-1 커넥션풀 고갈 방지): DB write 가 없으므로 @Transactional 을 제거했다. AI 블로킹 호출 구간이 control HikariCP 커넥션을 점유하지 않는다."* 프로젝트 규약도 같다(`CLAUDE.md` — "프레임 이미지 서빙은 트랜잭션 밖에서 파일 I/O 를 한다", "커넥션을 쥔 채 NAS I/O 를 하면 커넥션 기아로 간다 — 전례 있음").
- **현재 동작(이슈 내용)**: segment 만 트랜잭션이 남아 있다.
  ```java
  // Sam2SegmentService.java:58-62
  @Service
  @RequiredArgsConstructor
  @Transactional(value = "controlTransactionManager", readOnly = true)   // ← 남아 있음
  public class Sam2SegmentService {
      public Sam2SegmentResponse segment(...) {   // :84  — 프록시 경유 public
          accessGuard.verifyAccess(...);          // :86  DB 접근 → 커넥션 획득
          …
          aiRes = aiServerClient.segment(aiReq).block();   // :114  최대 60s 블로킹 (AiServerClient.java:73)
  ```
  **실측(반증 시도로 확인)** — segment 6건 동시 호출 중 `pg_stat_activity` 샘플링:
  ```
  ### segment ×6 동시
   state               | count
   active              |     1
   idle                |     4
   idle in transaction |     6     ← 6건 전부 AI 호출 동안 커넥션 점유
  ### track ×6 동시 (대조군)
   state  | count
   active |     1
   idle   |    10                  ← idle in transaction 0건
  ```
  운영 풀은 `application-prd.yml:20,27` `maximum-pool-size: 20`(control/portal 각각). 라벨링 작업자 20명이 동시에 클릭 분할을 하면 control 풀이 소진되며, ai-server 가 느려질수록(60s 타임아웃) 점유 시간이 그대로 늘어난다.
- **재현/확인 경로**:
  ```bash
  for i in 1 2 3 4 5 6; do curl -s -o /dev/null -X POST -H "Authorization: Bearer <WORKER>" \
    -H 'Content-Type: application/json' -d "{\"srcSn\":$i,\"points\":[[50,50]]}" \
    localhost:18081/api/v1/frames/$i/sam2-segment & done
  sleep 1.2
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select state, count(*) from pg_stat_activity where datname='klid_system' group by 1;"
  ```
- **영향**: 가용성/성능(CWE-400 Uncontrolled Resource Consumption · OWASP API4:2023). 커넥션 기아 시 SAM2 와 무관한 API(라벨 저장·검수·배치)까지 동반 지연/실패한다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. `Sam2SegmentService` 의 클래스 `@Transactional` 을 제거하고, DB 조회(`accessGuard.verifyAccess` + `srcRepository.findById` + `resolveFrameImageForInference` 의 게이트 조회)만 별도 `@Transactional(readOnly)` 조회 빈으로 분리한 뒤 **파일 I/O·base64·AI 호출은 트랜잭션 밖**에서 수행한다 — `FrameImageLookupService` 가 이미 같은 목적으로 존재하므로 그 패턴을 따르면 된다. ⚠ 자기호출(self-invocation)로 프록시를 우회하면 효과가 없다.

---

### [C-ISSUE-84] TC-SAM2-06 — `Sam2SegmentService` 의 경로가드가 dead code 이고 클래스 javadoc 이 실제 동작(비식별 프레임 사용)과 반대로 적혀 있다 (근거 드리프트 포함)

- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그·주석이 가리키는 방어 지점이 **실제로 실행되는 코드**여야 한다. 그렇지 않으면 다음 검증자·수정자가 "여기서 막고 있다"고 믿고 실제 방어선(`FrameImageEncoder`)의 회귀를 놓친다(프로젝트가 이미 겪은 패턴 — `CLAUDE.md`: *"컨트롤러가 정책 판정을 복제 보유하면 정책 갱신 때 조용히 뒤처진다"*).
- **현재 동작(이슈 내용)**: 3건.
  1. **dead code** — `Sam2SegmentService.java:179-188` `resolveSafe(Path baseDir, String relativePath)` 는 `segment()` 에서 호출되지 않는다(`grep -n "resolveSafe" Sam2SegmentService.java` → `:52`(javadoc) · `:179`(정의) 뿐). 함께 `@Value` 필드 `storageRawPath`(`:77-78`)도 미사용. 실제 경로가드는 `FrameImageEncoder`(비식별=`StorageSubtreePolicy.verifyDeidentifiedFile` realpath 판정 / 원본=`FrameImageEncoder.resolveSafe:220-229`).
  2. **javadoc 이 동작과 반대** — `:42-44`: *"SAM2 는 **원본 이미지에만 실행**한다 … `srcFilePathNm` = 원본 프레임 경로 사용"*. 실제 `:96` 은 `resolveFrameImageForInference` → `FrameImageEncoder:98-105` **비식별 경로 우선**이다.
     **실동작 반증(결정적)**: `raw_sn=4 frame-0` 은 원본/비식별 파일의 md5 가 다르다(`be89bcc1…` vs `efadbd02…`). ai-server 에 두 파일을 직접 넣어 비교하면
     - 원본 → `…[80.0,176.0],[81.0,177.0],[81.0,178.0],[95.0,178.0],[96.0,179.0],[97.0,178.0],[105…`
     - 비식별 → `…[80.0,176.0],[81.0,177.0],[81.0,178.0],[119.0,178.0],[119.0,6.0],[120.0,5.0],[120.0…`

       BE 응답은 `[[90,0],[80,8],[81,178],[119,178],[119,0]]` → **비식별본 계열과 일치**. 즉 SAM2 분할은 비식별 프레임으로 실행된다.
     (동작 자체는 2026-07-30 확정 정책 "라벨링 캔버스는 비식별 프레임을 서빙한다" 와 정합하며 **결함이 아니다** — 캔버스가 보여주는 픽셀과 분할 대상이 같아야 좌표가 맞는다. 문제는 **주석·`CLAUDE.md` "오토라벨링" 절이 갱신되지 않은 것**.)
  3. **근거 드리프트(카탈로그 정합성)** — TC-SAM2-06 의 `Sam2SegmentService.java:179` 는 dead code 지시. 부수적으로 TC-SAM2-32 서술 "유일한 **패키지 외** 소비자 `FrameBoundsResolver`" 는 사실과 다르다(`FrameBoundsResolver` 는 동일 패키지 `kr.co.cudo.authoring.label.service`).
  - 부가 관찰(별도 이슈 아님, LOW): `FrameImageEncoder` 의 **비식별** 분기는 realpath 기반(TOCTOU/심링크 방어, `StorageSubtreePolicy:201-215`)인데 **원본** 분기(`:220-229`)는 lexical 검증만 하고 `NOFOLLOW` 없이 연다. 경로값이 배치 소유(DB)라 현재 사용자 도달 경로는 없으나, `CLAUDE.md` 가 서빙 4경로에 요구하는 규약과 비대칭이다.
- **재현/확인 경로**: `grep -n "resolveSafe\|storageRawPath" backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java` → 호출부 0건 확인. 비식별 사용 실증은 위 md5 + ai-server 직접 호출 대조.
- **영향**: 유지보수/검증 신뢰도(감사 지적 가능 — 주석이 보안 통제를 잘못 서술). 런타임 보안 영향 없음.
- **수정 방향(제안)**: ⚠ 구현하지 않음. ①`Sam2SegmentService` 의 미사용 `resolveSafe`·`storageRawPath` 제거 ②클래스 javadoc `:42-44`·`:52` 를 "비식별 우선 해석 + 경로가드·신고게이트는 `FrameImageEncoder` 단일 위임"으로 정정 ③`CLAUDE.md` "오토라벨링" 절에 **온라인(사용자 트리거) SAM2 = 비식별본 / 배치 YOLO·SAM2 = 원본** 구분 명시 ④카탈로그 TC-SAM2-06 근거를 `FrameImageEncoder.java:220-229`(+`StorageSubtreePolicy.java:181-216`)로, TC-SAM2-32 서술을 "동일 패키지 유일 소비자"로 정정.

---

### [C-ISSUE-85] TC-KEYPOINT-07/09/11/13 — 자동테스트 공백 (동작은 정상, 회귀 가드 부재)

- **심각도**: LOW
- **기대 동작(기대효과)**: 키포인트는 `LBL_TYPE_CD` 기반 type-route 로 기존 2-튜플 경로와 격리된 분기라(`KeypointSerializer` javadoc `:14-16`), 분기 삭제·머지 시 조용히 깨지기 쉽다. 검증한 방어는 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: 실측 커버리지 대조 결과 4건 무커버.

  | 케이스 | 방어 위치 | 테스트 |
  |---|---|---|
  | TC-KEYPOINT-07 음수 좌표(SKELETON) | `LabelService.java:820-823` | **없음** (`LabelServiceKeypointTest` 11건 중 음수 케이스 0건) |
  | TC-KEYPOINT-09 `fromJson` 비배열 | `KeypointSerializer.java:80-82` | **없음** (`KeypointSerializerTest` 4건: roundTrip / toJsonTripletFormat / emptyInputs / rejectsTwoTuple) |
  | TC-KEYPOINT-11 `fromJson` 숫자 아님 | `KeypointSerializer.java:91-93` | **없음** |
  | TC-KEYPOINT-13 SKELETON R7 무변경 폴백 | `LabelService.java:568-585` | **없음** (`grep -rn "normalizePoints" backend/src/test` 무결과) |

  ※ 4건 모두 본 검증에서 실동작/정적으로 **정상 확인**됨 — 결함은 "동작"이 아니라 "가드 부재"다. `_raw/test-baseline.md` 기준 backend 실패 0건이라 이 공백은 baseline 으로 드러나지 않는다.
- **재현/확인 경로**: `grep -n "DisplayName" backend/src/test/java/kr/co/cudo/authoring/common/util/KeypointSerializerTest.java` (4건) · `.../label/LabelServiceKeypointTest.java` (11건) · `grep -rn "normalizePoints" backend/src/test` (0건).
- **영향**: 회귀 감지력. 특히 TC-KEYPOINT-13 은 무커버 상태에서 `normalizePoints` 폴백이 사라지면 **SKELETON 라벨이 매 저장마다 "변경됨"으로 오판정**되어 `LS_DATA_LBL_HSTRY` 와 `TASK_MODIFIED` 관제 통지가 무한 증식한다(운영 영향이 조용하고 크다).
- **수정 방향(제안)**: ⚠ 구현하지 않음. `KeypointSerializerTest` 에 비배열(`"{}"`)·비숫자(`"[[\"a\",2,1]]"`)·null 원소(`"[[1,2,null]]"`) 3건 추가, `LabelServiceKeypointTest` 에 음수 좌표 400 1건 추가, R7 무변경 폴백은 "SKELETON 동일값 재저장 시 이력 0건·버전 미증가" 통합테스트로 추가(본 검증의 실동작 시나리오를 그대로 코드화하면 된다).

---

### [C-ISSUE-86] TC-SAM2-09 (부수) — mock 안내 메시지가 사유와 무관하게 "AI 모델 미로드"로 고정돼 오진단을 유도한다

- **심각도**: LOW
- **기대 동작(기대효과)**: 사용자·운영자에게 나가는 안내는 실제 사유와 일치해야 한다. ai-server 는 사유를 4종으로 구분해 내려준다(`mock_reason` ∈ `env_mock` | `weights_missing` | `load_failed` | `empty_mask`).
- **현재 동작(이슈 내용)**: BE 는 사유를 무시하고 단일 문자열을 세팅한다.
  ```java
  // Sam2SegmentResponse.java:23
  public static final String MOCK_UNAVAILABLE_MESSAGE = "AI 모델 미로드 — 결과 신뢰 불가";
  // LabelController.java:173-177 — res.isEmpty() 이면 무조건 위 문자열
  ```
  **실측**: 현 스택은 SAM2 **모델이 정상 로드**된 상태인데, 마스크가 안 잡히는 프롬프트(`box:[10000,10000,10001,10001]` 또는 역박스 `[100,100,10,10]`)를 주면 ai-server 가 `mock_reason=empty_mask` 로 폴백하고 FE 에는 **"AI 모델 미로드"** 가 표시된다. 실제로는 "프롬프트 위치에 객체가 없음"이다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST -H "Authorization: Bearer <WORKER>" -H 'Content-Type: application/json' \
    -d '{"srcSn":1,"box":[100,100,10,10]}' localhost:18081/api/v1/frames/1/sam2-segment
  # → {"data":{"polygon":[],"score":0.0,"empty":true},"message":"AI 모델 미로드 — 결과 신뢰 불가"}
  # 동시에 ai-server 는 모델 로드 상태(실추론 로그 존재, AI_MOCK_MODE=false)
  ```
- **영향**: 운영/UX. 작업자가 "서버 장애"로 오인해 불필요한 에스컬레이션을 하거나, 반대로 진짜 `weights_missing` 상황이 "늘 뜨는 메시지"로 묻힌다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. `Sam2Response.mockReason()` 을 BE 가 이미 파싱하고 있으므로(`common/client/dto/Sam2Response.java:24`) 이를 사유별 메시지로 매핑한다 — `empty_mask` → "선택 지점에서 객체를 찾지 못했습니다 — 다른 위치를 클릭해 주세요", `weights_missing`/`load_failed`/`env_mock` → 현행 "AI 모델 미로드 — 결과 신뢰 불가". **자동적용 차단(빈 폴리곤) 동작은 두 경우 모두 그대로 유지**한다. FE `OverlayLayer.onMockWarning` 은 `res.message` 를 그대로 표시하므로 BE 만 고치면 된다.

---

## 5. 근거 드리프트 목록 (카탈로그 자체 정합성)

| 케이스 | 카탈로그 근거/서술 | 실제 |
|---|---|---|
| TC-SAM2-06 | `Sam2SegmentService.java:179` (`resolveSafe`) | **dead code** — 호출부 0건. 실제 가드는 `FrameImageEncoder.java:220-229` + `StorageSubtreePolicy.java:181-216` |
| TC-SAM2-23 | 기대결과 `502 EXTERNAL_API_ERROR` | 실제 **400 INVALID_INPUT** (→ C-ISSUE-82. 카탈로그 기대값이 옳고 구현이 어긋난 케이스) |
| TC-SAM2-32 | "유일한 **패키지 외** 소비자 `FrameBoundsResolver`" | `FrameBoundsResolver` 는 **동일 패키지**(`label.service`) |
| TC-SAM2-01 | `Sam2SegmentService.java:84-149` | `segment()` 는 `84-153` |
| TC-SAM2-09 | `LabelController.java:174-178` | `173-177` (off-by-1) |
| TC-SAM2-13 | `Sam2SegmentService.java:132-143` | 단순화 블록 `131-147`, 3점미만 폴백 `144-147` |
| TC-KEYPOINT-05 | `LabelService.java:816-819` | `815-819` |
| — (미커버) | C-3 에 **track mock 게이트 케이스가 없다** | segment 만 TC-SAM2-09 로 커버 → C-ISSUE-81 대응 케이스 신설 필요 |

> 그 외 TC-SAM2-02~05/07/08/10/11/12/14~22/24~31/33 및 TC-KEYPOINT-01~04/06~13 의 `file:line` 은 실측과 일치(드리프트 없음).

## 6. self-fill 관점 점검

- SAM2 분할/추적은 **좌표를 자체 생성하지 않는다** — 전량 ai-server 응답에서 온다(`Sam2SegmentService:114` / `Sam2TrackService:107`). 응답이 null/폴리곤 null 이면 502 로 마감(`:119-121` / `:115-117`), 하드코딩 폴백 좌표 없음. `score` 도 외부값 기반(segment 는 clamp, track 은 원본 — C-ISSUE-82).
- 단 **ai-server 내부의 mock 폴백**은 self-fill 성격을 갖는다(`_mock_segment`/`_mock_track` 이 좌표를 합성). segment 는 BE 가 이를 감지해 차단하므로 방어되고, **track 은 감지하지 못해 self-fill 결과가 그대로 통과한다 → C-ISSUE-81 이 본 파트의 self-fill 항목**이다.
- 키포인트는 전량 사용자 입력이며 서버 생성값 없음. 미지정 필드를 임의값으로 메우는 경로 없음(개수 17 강제).

## 7. 검증 중 발생시킨 상태 변경 (원복 완료)

| 대상 | 변경 | 원복 |
|---|---|---|
| `ls_data_lbl` (raw 35 / `src_sn=318`) | 키포인트 라벨 생성·수정(TC-KEYPOINT-01/06/13) | `PUT /v1/frames/318/labels {"items":[]}` → `select count(*) … where src_sn=318` = **0** (검증 전과 동일) |
| `ls_data_lbl_hstry` (`src_sn=318`) | 이력 3행(59/60/61) 적재 | append-only 감사 테이블이라 미삭제 — 원복 대상 아님(정상 동작의 산물) |
| raw 4 / raw 18 / raw 27 | SAM2 segment·track 호출만 (전부 미저장 경로) | 라벨 수 불변 확인(raw 4 = 10건 유지). 변경 없음 |
| `ls_deident_report` | **본 파트는 신고를 생성하지 않음** — 병렬 C-part4 에이전트가 만든 raw 27 의 OPEN 신고를 **읽기만** 하여 TC-SAM2-29/30 을 실동작 검증 | 해당 없음 |
