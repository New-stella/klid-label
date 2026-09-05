## C-4. TC-KEYPOINT — 17-keypoint COCO SKELETON

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고/이슈ID |
|----|---------|:--:|----------|-----------|------|
| TC-KEYPOINT-01 | SKELETON 정상 저장 | PASS | [실동작] `PUT /api/v1/frames/2/labels` (rawSn=4, WORKER 2001, 실 DB) 17개 [x,y,v] 저장 → 200, 응답 `points`가 삼중값 그대로 반환(`id:7` 신규 INSERT). [정적] LabelService.java:792-825(validateSkeletonPoints)/:834-838(serializePoints) 일치 | LabelServiceKeypointTest#skeletonRoundTrip | |
| TC-KEYPOINT-02 | 개수≠17 | PASS | [실동작] 16개 전송(`srcSn=3`) → 400 "SKELETON 키포인트는 정확히 17 개여야 합니다." [정적] :793-796 일치 | LabelServiceKeypointTest#skeleton16Rejected | |
| TC-KEYPOINT-03 | 삼중값 크기≠3 | PASS | [실동작] [x,y]만 17개 전송(`srcSn=4`) → 400 "키포인트는 [x, y, v] 형태여야 합니다." [정적] :797-801 일치 | LabelServiceKeypointTest#skeletonTwoTupleElementRejected | |
| TC-KEYPOINT-04 | v null 원소(NPE 방어) | PASS | [실동작] `[10,10,null]` 포함 전송(`srcSn=2`) → 400(500 아님) "키포인트 좌표에 null 원소가 있습니다." — 언박싱 전 fail-secure 확인. [정적] :802-811 일치(Double 박싱 상태에서 null 체크 후 언박싱) | LabelServiceKeypointTest#skeletonNullElementRejected(v null + y null 2케이스) | |
| TC-KEYPOINT-05 | v 범위 밖 | PASS | [실동작] v=3 전송(`srcSn=3`) → 400 "가시성 v 는 0/1/2 중 하나여야 합니다." [정적] :816-819 일치 | LabelServiceKeypointTest#skeletonVisibility3Rejected | |
| TC-KEYPOINT-06 | v=0 좌표 0 허용(경계) | PASS | [실동작] 17개 전부 `[0,0,0]` 전송(`srcSn=3`, REVIEWER) → 200, 저장·반환됨(id:9) | LabelServiceKeypointTest#skeletonV0ZeroCoordsAllowed | |
| TC-KEYPOINT-07 | 음수 좌표(v\>0) | PASS | [실동작] `x=-1,v=2` 전송(`srcSn=3`) → 400 "좌표는 0 이상이어야 합니다 (x=-1.0, y=10.0)". [정적] :820-823 일치 | 전용 케이스 없음(수동 검증) — LabelService 공통 음수 검사 회귀는 bboxRegression 등으로 간접 커버 | |
| TC-KEYPOINT-08 | KeypointSerializer toJson | PASS | [정적] KeypointSerializer.java:46 일치(nested List<Number> → objectMapper 직렬화) | KeypointSerializerTest#toJsonTripletFormat(수치검산: `[[1.5,2.5,2]]`) | |
| TC-KEYPOINT-09 | fromJson 배열 아님 | PASS | [정적] :80-81 일치(`!root.isArray()` → IAE) | **전용 단위테스트 없음** — 코드 리딩으로만 확인(간접: rejectsTwoTuple 은 배열이되 삼중값 크기 위반 케이스라 이 분기와 다름) | C-ISSUE-89 |
| TC-KEYPOINT-10 | fromJson 삼중값 크기위반 | PASS | [정적] :85-86 일치 | KeypointSerializerTest#rejectsTwoTuple(`[[1.0,2.0]]` → IAE) | |
| TC-KEYPOINT-11 | fromJson 숫자아님 | PASS | [정적] :91-92 일치(`!x.isNumber()` 등 → IAE) | **전용 단위테스트 없음** — 코드 리딩으로만 확인 | C-ISSUE-89 |
| TC-KEYPOINT-12 | fromJson 빈/[] | PASS | [정적] :69-81 일치(blank/"[]" 조기 반환 `List.of()`) | KeypointSerializerTest#emptyInputs | |
| TC-KEYPOINT-13 | SKELETON R7 비교 폴백 | PASS | [정적] LabelService.java:568-585(normalizePoints) 일치 — `LabelPointSerializer.fromJson`(2-튜플 전용)이 삼중값에서 예외 발생 시 catch 후 raw `List<List<Double>>` 파서로 폴백해 SKELETON 삼중값도 비교 가능 | **회귀 테스트 부재 — C-ISSUE-23 (이월, 미해소 재확인)** | C-ISSUE-23 |

---

## C-5. TC-TRACK — 트랙 보간 / 좌표변환 / MASK↔RLE / YOLO 트랙 프록시

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고/이슈ID |
|----|---------|:--:|----------|-----------|------|
| TC-TRACK-01 | BBOX 보간 정상 | PASS | [정적] batch/interpolation/TrackInterpolator.java:48-83 일치(file:line 정확) | TrackInterpolatorTest#twoKeyframesInterpolateBetween(수치검산 t=0.4→(40,40,140,140) 등) | |
| TC-TRACK-02 | 빈 키프레임 | PASS | [정적] :53-55 일치 | TrackInterpolatorTest#emptyKeyframesProduceEmptyResult | |
| TC-TRACK-03 | 단일 키프레임 | PASS | [정적] :57-64 일치 | TrackInterpolatorTest#singleKeyframeReturnsOnlyThatFrame | |
| TC-TRACK-04 | outside 마커 종료 | PASS | [정적] :59-62 일치 | TrackInterpolatorTest#outsideKeyframeStopsProcessing | |
| TC-TRACK-05 | 다음이 outside | PASS | [정적] :66-70 일치 | TrackInterpolatorTest#nextOutsideStoresCurrentOnlyAndStops | |
| TC-TRACK-06 | 마지막 이후 propagate 없음 | PASS | [정적] :35-44 일치 | TrackInterpolatorTest#noPropagateAfterLastKeyframe | |
| TC-TRACK-07 | 동일 frame 중복 뒤값 우선 | PASS | [정적] :66-75 일치 | TrackInterpolatorTest#duplicateFrameKeyframeLastWins | |
| TC-TRACK-08 | totalFrames 음수 | PASS | [정적] :50-52 일치 | TrackInterpolatorTest#negativeTotalFramesRejected | |
| TC-TRACK-09 | keyframes null | PASS | [정적] :48-49 일치 | TrackInterpolatorTest#nullKeyframesRejected | |
| TC-TRACK-10 | 폴리쉐이프 보간 정점수 다름 | PASS | [정적] :100-139 일치, PolyshapeMatcher.match() 대응쌍이 키프레임 쌍당 1회만 계산되어 사이 프레임에 재사용됨(:126-127) 확인 | TrackInterpolatorPolyshapeTest#polygonVariableVertexCount(3점↔4점→중간 4점, 좌표 유한) | |
| TC-TRACK-11 | 폴리쉐이프 closed/open | PASS | [정적] :100-127 일치 | TrackInterpolatorPolyshapeTest#polygonSameVertexCountInterpolated(closed)+#polylineAnchorInterpolated·#polylineReversedDirectionInterpolated(open, 방향반전 포함) | |
| TC-TRACK-12 | rotate 중심 기준 | PASS | [정적] CoordinateTransformer.java:21-41 일치(표준 2D 회전행렬) | CoordinateTransformerTest#rotate90Verified(수치검산 (10,0)+90도→(0,10))+#rotateRoundtripIdentity(45도 왕복) | |
| TC-TRACK-13 | rotate points/center null | PASS | [정적] :22-27 일치(points null→IAE, center null→IAE) | **해소됨(1차 대비 개선)** — CoordinateTransformerTest#rotateRejectsNullPoints·#rotateRejectsNullCenter·#rotateChecksPointsGuardBeforeCenterGuard(가드 순서까지 고정) 신규 추가 확인. 1차 C-ISSUE-81 지적사항 중 이 서브케이스는 해소 | |
| TC-TRACK-14 | scale/translate | PASS | [정적] :44-65 일치 | CoordinateTransformerTest#scaleRoundtripIdentity·#translateRoundtripIdentity(+각각 null 가드 테스트) | |
| TC-TRACK-15 | maskToRle 첫픽셀 on | PASS | [정적] MaskRleConverter.java:30-59 일치(51-59 구간이 첫픽셀 on 시 0-run 삽입) | MaskRleConverterTest#leadingOnInsertsZeroRun(수치검산 [1,1,0]→[0,2,1]) | |
| TC-TRACK-16 | maskToRle 빈/null | PASS | [정적] :30-33 일치 | **해소됨(1차 대비 개선)** — MaskRleConverterTest#emptyMaskHandled(5x5 all-false)·#rleRoundtripIntegrity1000 이 h∈[1,20] 랜덤이라 사실상 null/빈 배열 자체(길이0)에 대한 전용 assertion은 없음 — `mask==null → new int[0]` 분기는 여전히 코드 리딩으로만 확인 | C-ISSUE-90 |
| TC-TRACK-17 | maskToRle \>1M 픽셀(DoS) | PASS | [정적] :22, :37-39 일치 | MaskRleConverterTest#rejectsExcessivelyLargeMask(1001x1001) | |
| TC-TRACK-18 | maskToRle 비직사각형 | PASS | [정적] :44-47 일치(row.length!=w 시 IAE) | **여전히 미커버** — MaskRleConverterTest 전수 grep 결과 "비직사각형"/"row.*width" 대상 테스트 0건(1차 C-ISSUE-81 지적사항 중 유일하게 미해소) | C-ISSUE-90 |
| TC-TRACK-19 | rleToMask width/height≤0 | PASS | [정적] :93-95 일치(수치검산: width=0→즉시 IAE) | **해소됨** — MaskRleConverterTest#rleToMaskRejectsNonPositiveWidth·#rleToMaskRejectsNonPositiveHeight(0/음수 각각) | |
| TC-TRACK-20 | rleToMask \>1M | PASS | [정적] :88-92 일치 | **해소됨** — MaskRleConverterTest#rleToMaskRejectsExcessivePixels(1001x1001) + #rleToMaskChecksPixelLimitBeforePositiveDimensions(가드 순서 고정) | |
| TC-TRACK-21 | rleToMask 길이합 초과 | PASS | [정적] :102-104 일치(수치검산 rle=[10,10],w=5,h=3,total=15→idx=15 도달 시 IAE) | **해소됨** — MaskRleConverterTest#rleToMaskRejectsRunLengthOverflow + #rleToMaskAcceptsExactRunLength(경계값 == total 은 정상) | |
| TC-TRACK-22 | rleToMask null | PASS | [정적] :85-87 일치 | **해소됨** — MaskRleConverterTest#rleToMaskRejectsNullRle | |
| TC-TRACK-23 | round-trip mask→rle→mask | PASS | [정적] :30-113 알고리즘 확인 | MaskRleConverterTest#rleRoundtripIntegrity1000(랜덤 1000회 완전 일치, h/w 1~20) | |
| TC-TRACK-24 | yolo-track path/body 불일치 | PASS | [실동작] `POST /api/v1/frames/1/yolo-track` body `srcSn=2` (REVIEWER) → **400** "path 의 srcSn 과 body 의 srcSn 이 다릅니다." [정적] LabelController.java:198-203 일치 | | |
| TC-TRACK-25 | yolo-track nextSrcSns 50 초과 | PASS | [실동작] `nextSrcSns` 51개 전송 → **400** "nextSrcSns: size must be between 0 and 50" [정적] YoloTrackRequest.java:23 일치 | | |
| TC-TRACK-26 | yolo-track nextSrcSns 빈 | PASS | [실동작] `nextSrcSns:[]` 전송 → **400** "nextSrcSns: must not be empty" | | |
| TC-TRACK-27 | yolo-track 좌표 clamp (신규 · C-ISSUE-41) | PASS | [정적] YoloTrackService.java:163-180(bounds 조회 후 normalizeBbox 호출)·DetectionBoxNormalizer.java:59-68(clamp 로직, 0≤x≤maxX/maxY) 일치 — [실동작] 경로는 ai-server가 로컬 YOLO 가중치 부재로 mock 폴백 중이라(`pipeline-drive.md` 참조) 실제 경계초과 좌표를 임의 주입해 왕복 검증은 불가, 대신 아래 mocked AiServerClient 단위테스트로 동일 코드경로 확정 | YoloTrackServiceTest#negativePointsClampedNotRejected(-1.57…→0.0 clamp)+#pointsClampedToMeasuredFrameBounds(1000,900→실측 100,50 clamp) | |
| TC-TRACK-28 | yolo-track NaN/Infinity 400 (신규) | PASS | [정적] YoloTrackService.java:168-172(catch IAE→400)·DetectionBoxNormalizer.java:54-58(`!Double.isFinite` 가드) 일치 | YoloTrackServiceTest#nanPointsRejected(NaN 좌표 → INVALID_INPUT, 구 `NaN<0`=false 버그 회귀 방지) | |
| TC-TRACK-29 | yolo-track 퇴화 박스 검출 단위 스킵 (신규) | PASS | [정적] YoloTrackService.java:173-177(`points.isEmpty()` → WARN+continue, 예외 아님) 일치 | YoloTrackServiceTest#degenerateBoxSkippedWithoutDiscardingSequence(3프레임 중 1건 퇴화 검출만 스킵, person/car 2건 검출 중 person만 빠지고 car 유지 + 시퀀스 3프레임 모두 생존) | |
| TC-TRACK-30 | yolo-track 교차 영상 혼입 차단 (신규) | PASS | [실동작] `POST /api/v1/frames/1/yolo-track` `{"srcSn":1,"nextSrcSns":[31]}` (srcSn=1→rawSn=4, srcSn=31→rawSn=5, 실 DB 서로 다른 영상) → **400** "시퀀스 프레임이 시작 프레임과 다른 영상에 속합니다: srcSn=31" [정적] YoloTrackService.java:111-114 일치 | YoloTrackServiceTest#nextFrameFromDifferentVideoRejected | |
| TC-TRACK-31 | 보간 진실원 단일 확인 (신규, 구조 단언) | PASS | [정적] `grep -rn "import kr.co.cudo.authoring.common.util.TrackInterpolator" backend/src/main/java src/test/java` → **0건**(프로덕션·테스트 어디서도 import 없음). `grep -rln "batch.interpolation.TrackInterpolator" src/main/java` → `TrackInterpolationStep.java` 1곳만 사용. `common/util/TrackInterpolator.java` 클래스 자체는 여전히 파일로 존재(회전보간 포함) | 동명이인 파일에 자체 단위테스트(`common/util/TrackInterpolatorTest.java`)도 별도 존재하나 프로덕션 미사용 dead code 라는 판정에는 영향 없음 | **C-ISSUE-82 (이월, 미해소 재확인)** |

---

## 이슈 상세 (신규 · 이번 회차)

### [C-ISSUE-89] TC-KEYPOINT-09/11 — KeypointSerializer.fromJson 형식위반 2분기(비배열·비숫자) 전용 단위테스트 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: `fromJson`이 배열이 아닌 JSON("{}" 등) 또는 원소가 숫자가 아닌 JSON(`[["a","b","c"]]`)을 받으면 `IllegalArgumentException`으로 fail-secure 거부해야 하고, 그 동작이 회귀 테스트로 고정되어야 한다.
- **현재 동작(이슈 내용)**: 구현(`KeypointSerializer.java:80-81` root.isArray() 체크, `:91-92` x/y/v.isNumber() 체크)은 정상이다. 그러나 `KeypointSerializerTest`는 `rejectsTwoTuple`(배열이지만 삼중값 크기=2)만 커버하고, "루트가 배열이 아님"·"원소가 숫자가 아님" 두 분기를 직접 겨냥한 테스트가 없다(`backend/src/test/java` 전수 grep 결과 0건).
- **재현/확인 경로**: `KeypointSerializer.fromJson("{\"x\":1}", om)` → IAE 예상(비배열) / `KeypointSerializer.fromJson("[[\"a\",\"b\",\"c\"]]", om)` → IAE 예상(비숫자). 둘 다 테스트 파일에 assertion 없음.
- **영향**: 회귀 위험만(현재 동작 결함 아님). 리팩터링 시 이 두 가드가 조용히 깨질 수 있음.
- **수정 방향(제안)**: `KeypointSerializerTest`에 `fromJson_루트가_배열이_아니면_예외`, `fromJson_원소가_숫자아니면_예외` 케이스 2건 추가.

### [C-ISSUE-90] TC-TRACK-16/18 — MaskRleConverter 잔여 커버리지 갭 (1차 C-ISSUE-81 부분 해소 후 재평가)
- **심각도**: LOW (1차 MEDIUM 대비 하향 — 대부분 서브케이스 해소됨)
- **기대 동작(기대효과)**: `maskToRle(null)`/`maskToRle(new boolean[0][])` → `int[0]` 반환, `maskToRle`에 행 길이가 다른(비직사각형) mask 입력 시 `IllegalArgumentException`. 두 동작 모두 회귀 테스트로 고정되어야 한다.
- **현재 동작(이슈 내용)**: 1차 검증(C-ISSUE-81, 2026-07-25)에서 지적된 7개 서브케이스(TC-TRACK-13/16/18/19/20/21/22) 중 **13/19/20/21/22 는 이번 회차에 신규 테스트로 해소 확인**(`CoordinateTransformerTest`·`MaskRleConverterTest`에 다수 추가). 다만 **16(빈/null)과 18(비직사각형)은 여전히 전용 테스트가 없다**. `MaskRleConverterTest#emptyMaskHandled`는 "5x5 all-false"(내용은 비어있지만 배열 자체는 5x5로 존재)만 검증해 `mask==null`/`mask.length==0` 조기 반환(:30-33) 분기를 타지 않고, 비직사각형(행마다 열 개수 다름, :44-47) 분기를 겨냥한 테스트도 0건이다.
- **재현/확인 경로**: `grep -n "직사각형\|row.*width\|mask\[y\]\.length" backend/src/test/java/kr/co/cudo/authoring/common/util/MaskRleConverterTest.java` → 0건. `MaskRleConverter.maskToRle(null)` 호출 시 `int[0]` 반환 자체는 코드상 명확하나 회귀 가드 없음.
- **영향**: 회귀 위험만(현재 동작 결함 아님). CWE-770 관련 상한(TC-TRACK-17/20)은 이미 잘 커버되어 있어 보안 임계 리스크는 낮음.
- **수정 방향(제안)**: `MaskRleConverterTest`에 `maskToRle_null_또는_빈배열은_int0`, `maskToRle_행_길이_불일치시_예외` 2건 추가. 1차 C-ISSUE-81은 이 잔여 2건으로 축소 후 종결 처리 검토.

---

## ★ 이월 이슈 재확인 결과 (담당 지정 2건)

### [C-ISSUE-23] TC-KEYPOINT-13 — R7 손상 좌표 JSON fail-safe 회귀 테스트 부재 — **미해소 (재확인)**
- **심각도**: LOW
- **기대 동작(기대효과)**: `LabelService.normalizePoints`(:568-585)가 SKELETON 삼중값처럼 `LabelPointSerializer.fromJson`(2-튜플 전용)으로 못 읽는 포맷을 만나면 raw `List<List<Double>>` 파서로 폴백하고, 그마저 실패(진짜 손상 JSON)하면 `null`을 반환해 `pointsEqual`이 "변경됨"으로 fail-safe 처리해야 한다. 이 동작이 회귀 테스트로 고정되어야 한다.
- **현재 동작(이슈 내용)**: 코드는 여전히 정상 구현되어 있다(:568-585, 2026-07-25 확인 시점과 라인 동일 — 근거 드리프트 없음). 그러나 `grep -rn "pointsEqual\|normalizePoints" backend/src/test/java` 결과가 이번 회차에도 **0건**이다. `LabelServiceFullReplaceIntegrationTest#corruptDiffJsonGracefulFallback`(line 277-293)은 여전히 존재하나, 이는 `LsDataLblHstry.CHG_DTL_CN`(이력 diff JSON) 손상 케이스를 검증하는 것이지 `normalizePoints`/`pointCn` 손상(저장 시점 변경 여부 판정) 케이스가 아니다 — 1차 지적과 동일하게 대상이 다르다.
- **재현/확인 경로**: `grep -rn "pointsEqual\|normalizePoints" backend/src/test/java` → 0건 (2026-07-31 재확인).
- **영향**: 회귀 위험만. 현재 동작 자체는 결함 아님(fail-safe 정상).
- **수정 방향(제안)**: 1차와 동일 — `pointCn`을 손상값(`"{{{"`)으로 덮어쓴 뒤 SKELETON 라벨을 무변경 재저장해 UPDATED 이력이 남는지(=false 반환·변경됨 처리) 확인하는 케이스 1건 추가.

### [C-ISSUE-82] batch/interpolation/TrackInterpolator.java 와 동명이인 common/util/TrackInterpolator.java 잔존 — **미해소 (재확인)**
- **심각도**: LOW (기능 영향 없음 — 순수 housekeeping)
- **기대 동작(기대효과)**: 동일 클래스명이 프로덕션 경로 밖에 중복 존재하면 유지보수자가 잘못된 파일을 참조/수정할 위험이 있어 정리되어야 한다.
- **현재 동작(이슈 내용)**: 이번 회차 재확인 결과도 동일 — `kr.co.cudo.authoring.common.util.TrackInterpolator`는 `grep -rn "import kr.co.cudo.authoring.common.util.TrackInterpolator" src/main/java src/test/java` 결과 **0건**(프로덕션·테스트 모두 미참조). 실사용처는 `TrackInterpolationStep`이 참조하는 `kr.co.cudo.authoring.batch.interpolation.TrackInterpolator` 뿐. 오히려 동명이인 dead 클래스 전용 테스트(`src/test/java/kr/co/cudo/authoring/common/util/TrackInterpolatorTest.java`)까지 별도로 존재해 유지보수 혼동 표면이 1차보다 줄지 않았다.
- **재현/확인 경로**: `grep -rn "class TrackInterpolator" backend/src/main/java` → 2건(동명이인 확인). `grep -rn "import kr.co.cudo.authoring.common.util.TrackInterpolator" backend/src/main/java backend/src/test/java` → 0건.
- **영향**: 없음(dead code). 본 문서 C-5 근거 file:line은 전부 `batch/interpolation/TrackInterpolator`를 정확히 가리키므로 검증 정합성에는 영향 없음.
- **수정 방향(제안)**: 1차와 동일 — `common/util/TrackInterpolator.java` + 대응 테스트 삭제, 또는 미사용 초안임을 명확히 하는 주석 강화.

---

## 집계

- 총 44건 (TC-KEYPOINT 13 + TC-TRACK 31) — 폐기 케이스 없음
- **PASS 44 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0**
- 신규 결함(LOW) 2건: C-ISSUE-89(KeypointSerializer 형식위반 2분기 테스트 갭), C-ISSUE-90(MaskRleConverter 빈/비직사각형 테스트 갭 — 1차 C-ISSUE-81 부분 해소 후 잔여분)
- 이월 이슈 재확인: **C-ISSUE-23 미해소**(정적 재확인, 라인 드리프트 없음) / **C-ISSUE-82 미해소**(정적 재확인, import 0건 재검증)
- 1차 대비 개선 확인: C-ISSUE-81 중 TC-TRACK-13/19/20/21/22 서브케이스는 신규 테스트 추가로 해소, TC-TRACK-16/18만 잔존(→ C-ISSUE-90으로 축소 재기록)
- self-fill 의심 없음 — 전 케이스가 순수 도메인 로직(보간/좌표변환/RLE) 또는 IDOR·형식검증이며 외부 연동 없음
