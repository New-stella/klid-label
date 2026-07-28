## C-5. TC-TRACK — 트랙 보간 / 좌표변환 / MASK↔RLE
| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-TRACK-01 | BBOX 보간 정상 | PASS | [정적] batch/interpolation/TrackInterpolator.java:48-83 일치(file:line 정확). [실동작] rawSn=26 실파이프라인이 트랙 보간 5건 실행·저장 완료(B-part3.md TC-BATCH-140 참조) | TrackInterpolatorTest#두_키프레임_5_10_사이_선형_보간(수치검산: t=0.4→(40,40,140,140) 일치) | 근거 file:line 은 `batch/interpolation/TrackInterpolator.java` 를 가리킴(정확). 동명 `common/util/TrackInterpolator.java` 는 미사용 dead code — C-ISSUE-82 |
| TC-TRACK-02 | 빈 키프레임 | PASS | [정적] :53-55 일치 | TrackInterpolatorTest#emptyKeyframesProduceEmptyResult | |
| TC-TRACK-03 | 단일 키프레임 | PASS | [정적] :57-64 일치 | TrackInterpolatorTest#singleKeyframeReturnsOnlyThatFrame | |
| TC-TRACK-04 | outside 마커 종료 | PASS | [정적] :59-62 일치 | TrackInterpolatorTest#outsideKeyframeStopsProcessing | |
| TC-TRACK-05 | 다음이 outside | PASS | [정적] :66-70 일치 | TrackInterpolatorTest#nextOutsideStoresCurrentOnlyAndStops | |
| TC-TRACK-06 | 마지막 이후 propagate 없음 | PASS | [정적] :36-44 일치 | TrackInterpolatorTest#noPropagateAfterLastKeyframe | |
| TC-TRACK-07 | 동일 frame 중복 뒤값 우선 | PASS | [정적] :71-75 일치 | TrackInterpolatorTest#duplicateFrameKeyframeLastWins | |
| TC-TRACK-08 | totalFrames 음수 | PASS | [정적] :50-52 일치 | TrackInterpolatorTest#negativeTotalFramesRejected | |
| TC-TRACK-09 | keyframes null | PASS | [정적] :49 일치 | TrackInterpolatorTest#nullKeyframesRejected | |
| TC-TRACK-10 | 폴리쉐이프 보간 정점수 다름 | PASS | [정적] :100-139 일치, PolyshapeMatcher.match() 대응쌍 1회 계산 후 재사용 확인 | TrackInterpolatorPolyshapeTest#polygonVariableVertexCount(3점↔4점→중간 4점, 좌표 유한) | |
| TC-TRACK-11 | 폴리쉐이프 closed/open | PASS | [정적] :100-127 일치 | TrackInterpolatorPolyshapeTest#polygonSameVertexCountInterpolated(closed) + #polylineAnchorInterpolated·#polylineReversedDirectionInterpolated(open, 방향반전 포함) | |
| TC-TRACK-12 | rotate 중심 기준 | PASS | [정적] CoordinateTransformer.java:21-41 일치 | CoordinateTransformerTest#rotate90Verified(수치검산:(10,0)+90도→(0,10) 일치)+#rotateRoundtripIdentity | |
| TC-TRACK-13 | rotate points/center null | PASS | [정적] :22-27 IAE 가드 확인 | 단위테스트 미커버(null 케이스) — 코드 리딩으로만 확인 | C-ISSUE-81 |
| TC-TRACK-14 | scale/translate | PASS | [정적] :44-65 일치 | CoordinateTransformerTest#scaleRoundtripIdentity·#translateRoundtripIdentity | |
| TC-TRACK-15 | maskToRle 첫픽셀 on | PASS | [정적] MaskRleConverter.java:51-59 일치 | MaskRleConverterTest#leadingOnInsertsZeroRun(수치검산:[1,1,0]→[0,2,1] 일치) | |
| TC-TRACK-16 | maskToRle 빈/null | PASS | [정적] :30-32 일치 | emptyMaskHandled 은 all-false 5x5(내용빈)만 커버, **null/길이0 배열 자체는 미테스트** | C-ISSUE-81 |
| TC-TRACK-17 | maskToRle >1M 픽셀(DoS) | PASS | [정적] :36-40 일치 | MaskRleConverterTest#rejectsExcessivelyLargeMask(1001x1001) | |
| TC-TRACK-18 | maskToRle 비직사각형 | PASS | [정적] :44-47 일치(row.length!=w 시 IAE) | 단위테스트 미커버 | C-ISSUE-81 |
| TC-TRACK-19 | rleToMask width/height≤0 | PASS | [정적] :93-95 일치(수치검산: width=0→즉시 IAE) | 단위테스트 미커버 | C-ISSUE-81 |
| TC-TRACK-20 | rleToMask >1M | PASS | [정적] :88-92 일치 | **단위테스트 미커버 — P1 보안(CWE-770)인데 회귀방지 테스트 없음** | C-ISSUE-81 |
| TC-TRACK-21 | rleToMask 길이합 초과 | PASS | [정적] :102-104 일치(수치검산: rle=[10,10],w=5,h=3,total=15→16번째 픽셀 idx=15에서 IAE) | 단위테스트 미커버 | C-ISSUE-81 |
| TC-TRACK-22 | rleToMask null | PASS | [정적] :85-87 일치 | 단위테스트 미커버 | C-ISSUE-81 |
| TC-TRACK-23 | round-trip mask→rle→mask | PASS | [정적] :30-113 알고리즘 확인 | MaskRleConverterTest#rleRoundtripIntegrity1000(랜덤 1000회 완전일치) | |
| TC-TRACK-24 | yolo-track path/body 불일치 | PASS | [실동작] `POST /api/v1/frames/446/yolo-track` body srcSn=447 → **400 INVALID_INPUT** "path 의 srcSn 과 body 의 srcSn 이 다릅니다." (LabelController.java:198-203 일치) | | |
| TC-TRACK-25 | yolo-track nextSrcSns 50 초과 | PASS | [실동작] nextSrcSns 51개 전송 → **400** "nextSrcSns: size must be between 0 and 50"(YoloTrackRequest.java:23 일치) | | |
| TC-TRACK-26 | yolo-track nextSrcSns 빈 | PASS | [실동작] nextSrcSns:[] 전송 → **400** "nextSrcSns: must not be empty" | | |

## C-6. TC-PRESET — 라벨 프리셋
| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-PRESET-01 | 생성 정상(labelId 기반) | PASS | [실동작] `POST /api/v1/manage/presets` labelIds:[1,2] → 201, 응답 `code:null`(문자열 미저장), labelName/labelType 마스터 join 파생 확인 | PresetServiceTest#create_정상_케이스는_eventTypeCd가_전달되어_저장된다 | 검증후 삭제로 정리 완료 |
| TC-PRESET-02 | 생성 이벤트타입 무효 | PASS | [실동작] eventTypeCd:"NOT_A_REAL_EVENT" → **400** "지원하지 않는 이벤트 타입입니다" | PresetServiceTest#프리셋_저장시_유효_categoryKey면_통과_미유효면_400 | |
| TC-PRESET-03 | 생성 이벤트타입 빈값 허용 | PASS | [실동작] eventTypeCd:"" → 201, 응답 eventTypeCd:null(정규화) | PresetServiceTest#eventTypeCd_빈_문자열은_null_로_정규화되어_저장된다 | |
| TC-PRESET-04 | 생성 이름 중복 | PASS | [실동작] 기존 이름 재사용 → **409** "이미 사용 중인 프리셋 이름입니다." | PresetServiceTest#동일_이름_프리셋은_CONFLICT_변경_없음 | |
| TC-PRESET-05 | 생성 labelId 미존재/비활성 | PASS | [실동작] labelId=999999 → **400** "존재하지 않거나 비활성 라벨입니다: labelId=999999" | PresetServiceTest#마스터에_없는_labelId로_코드추가시_400 | |
| TC-PRESET-06 | 생성 이벤트 유니크 경합 | PASS | [정적+실동작] DB `uk_ls_label_preset_evnt`(evnt_type_cd) 실제 존재 확인(psql) + saveAndFlush→DataIntegrityViolationException→CONFLICT 변환(PresetService.java:174-181). 실제 동시 요청 레이스는 재현 안 함(단일 세션 순차 검증) | PresetServiceTest#동일_이벤트가_다른_프리셋에_이미_매핑되어_있으면_CONFLICT | 진짜 동시성 경합은 미재현(순차 요청으로 동일 제약 위반만 확인) |
| TC-PRESET-07 | 수정 미존재 | PASS | [실동작] `PUT /manage/presets/999999` → **404** "프리셋을 찾을 수 없습니다." | PresetServiceTest#update_존재하지않는_프리셋이면_NOT_FOUND | |
| TC-PRESET-08 | 수정 이름 중복(자기제외) | PASS | [실동작] 타 프리셋명으로 수정 시도 → **409** | PresetServiceTest 유사 케이스 | |
| TC-PRESET-09 | 수정 이벤트 유니크 위반 | PASS | [실동작] 이미 매핑된 eventTypeCd 로 수정 → **409** "이미 다른 프리셋에 매핑된 이벤트입니다" | PresetServiceTest#update_시_이벤트_UNIQUE_위반은_CONFLICT_로_변환 | |
| TC-PRESET-10 | 삭제 멱등 | PASS | [실동작] `DELETE /manage/presets/999999` → **204**(no-op) | | |
| TC-PRESET-11 | 복제 이름 시퀀스 | PASS | [실동작] "교통사고 표준 프리셋" 복제 → "교통사고 표준 프리셋 (복사본)" | | |
| TC-PRESET-12 | 복제 이벤트 미상속 | PASS | [실동작] 원본 eventTypeCd="030001" 인 프리셋 복제 → 복제본 eventTypeCd:null | PresetServiceTest#clone_은_eventTypeCd를_상속하지_않고_코드를_복사한다 | |
| TC-PRESET-13 | 복제 이름 50회 초과 실패 | PASS | [실동작] 동일 프리셋 51회 연속 clone 실행 → 1~50회 200, **51번째 409** "복제 이름 생성에 실패했습니다." (PresetService.java:184-196 CLONE_SUFFIX_MAX=50 정합) | 단위테스트 미커버(50회 루프 없음) — 본 회차에 실동작으로 직접 확증 | |
| TC-PRESET-14 | 코드뷰 미연결(labelId null) | PASS | [정적] :239-245 일치. 시드 DB엔 labelId=null 행 없어 실동작 미재현 | PresetServiceTest#labelId가_null인_미연결코드는_linked_false와_legacy명으로_노출된다 | |
| TC-PRESET-15 | 코드뷰 마스터 미존재/soft delete | PASS | [실동작] LS_LABEL.lbl_id=1 을 USE_YN='N' 으로 임시 변경 → 프리셋 응답 linked:false, labelType:null, labelName="PERSON"(legacy 코드 폴백), 오류 없음. **검증 후 USE_YN='Y' 로 원복 완료** | PresetServiceTest#list_labelId있지만_soft_delete된_마스터는_linked_false로_노출된다 | 원복 확인: `select use_yn from ls_label where lbl_id=1` → Y |
| TC-PRESET-16 | 코드뷰 형태 마스터 파생 | PASS | [실동작] LS_LABEL.lbl_id=2(car) 를 `lbl_nm='car_VERIFYTEST', lbl_type_cd='POLYGON'` 로 임시 변경 → 프리셋 응답이 즉시 labelName="car_VERIFYTEST", labelType="POLYGON", bboxEnabled:false→polygonEnabled:true 로 반영(스냅샷 아님, 실시간 join 확증). **검증 후 `lbl_nm='car', lbl_type_cd='BBOX'` 로 원복 완료** | PresetServiceTest#마스터_라벨명_변경후_프리셋조회시_변경된_라벨명이_반영된다 | 원복 확인: `select lbl_nm,lbl_type_cd from ls_label where lbl_id=2` → car/BBOX |
| TC-PRESET-17 | 부분유니크 labelId 중복 방지 | PASS | [실동작] 라이브 DB에서 `INSERT INTO ls_label_preset_code(preset_id,lbl_id,lbl_cd) VALUES(1,2,NULL)`(이미 존재) 직접 실행(트랜잭션 ROLLBACK) → **`duplicate key value violates unique constraint "uk_ls_label_preset_code_lblid"`** 실제 발생 확인 | LsLabelPresetCodeLabelIdUniqueIT#동일_프리셋에_같은_labelId_코드_중복INSERT는_부분유니크인덱스로_거부된다 | DB만 조회/트랜잭션 롤백, 실데이터 변경 없음 |
| TC-PRESET-18 | N+1 회피 배치 조회 | PASS | [정적] PresetService.java:220-236 `toViews()` 가 전체 preset 의 labelId 를 LinkedHashSet 으로 모아 `labelMasterService.findActiveByIds()` 1회 배치 조회 확인 | PresetServiceTest#프리셋_목록_조회시_코드_라벨_join이_N플러스1을_유발하지_않는다 | |

> **인가(부가 확인)**: WORKER 토큰으로 `GET /v1/manage/presets` 호출 → [실동작] **403 FORBIDDEN** "권한이 없습니다." 확인(REVIEWER 전용 정합, `/v1/manage/**` + `@PreAuthorize`). 별도 TC ID 없어 표에는 미포함.

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [C-ISSUE-81] TC-TRACK-13/16/18/19/20/21/22 — MaskRleConverter·CoordinateTransformer 경계/보안 가드 단위테스트 커버리지 0%
- **심각도**: MEDIUM (TC-TRACK-20은 CWE-770 DoS 방어 P1 케이스라 회귀방지 공백은 실질적 리스크)
- **기대 동작(기대효과)**: 순수 알고리즘 클래스의 모든 가드절(특히 P1/security 우선순위 케이스)은 단위테스트로 회귀 방지되어야 한다.
- **현재 동작(이슈 내용)**: 코드 자체는 전부 정확함(수치 검산으로 확인 완료 — 예: TC-TRACK-21 `rleToMask([10,10], w=5,h=3)`→total=15, 20>15이므로 idx=15 도달 시점에 IAE 발생함을 직접 계산해 확인). 그러나 `MaskRleConverterTest.java`에는 `rleToMask` 계열 예외 가드(width/height≤0, null, 길이합 초과, DoS 상한)에 대한 테스트가 **하나도 없음**(maskToRle 쪽 DoS만 테스트됨 — 비대칭). `CoordinateTransformerTest.java`도 `rotate(null, ...)`/`rotate(points, angle, null)` null 가드 테스트가 없음.
- **재현/확인 경로**: `grep -c "rleToMask" backend/src/test/java/kr/co/cudo/authoring/common/util/MaskRleConverterTest.java` (roundtrip 호출 외 예외 케이스 assert 없음 확인 가능)
- **영향**: 향후 리팩터링 시 이 가드들이 조용히 깨져도(예: 상한 체크 순서 변경, null 체크 누락) CI가 잡아내지 못함. 기능 결함은 아님(현재는 정상).
- **수정 방향(제안)**: `rleToMask` 4종 예외 케이스(null/width≤0/height≤0/DoS상한/길이합초과) + `rotate` null 가드 2종에 대한 단위테스트 추가.

### [C-ISSUE-82] batch/interpolation/TrackInterpolator.java 와 동명이인 common/util/TrackInterpolator.java — 미사용 dead code 잔존
- **심각도**: LOW (기능 영향 없음 — 순수 housekeeping)
- **기대 동작(기대효과)**: 동일 클래스명이 프로덕션 경로 밖에 중복 존재하면 향후 유지보수자가 잘못된 파일을 참조/수정할 위험이 있어, 사용하지 않는 초안은 정리되어야 한다.
- **현재 동작(이슈 내용)**: `kr.co.cudo.authoring.common.util.TrackInterpolator`(POLYGON은 `UnsupportedOperationException`, 회전보간 `interpolateRotation` 포함)는 어떤 프로덕션 코드에서도 import 되지 않음(`grep -rn "common.util.TrackInterpolator"` 결과 0건). 실제 사용처는 `TrackInterpolationStep`이 참조하는 `kr.co.cudo.authoring.batch.interpolation.TrackInterpolator`(POLYGON 완전 지원)뿐. 본 문서(C-5)의 file:line 근거는 후자와 정확히 일치하므로 검증 자체엔 영향 없음.
- **재현/확인 경로**: `grep -rn "import kr.co.cudo.authoring.common.util.TrackInterpolator" backend/src/main/java` (결과 없음)
- **영향**: 없음(dead code). 라벨 도메인에 회전(rotation) 속성이 아예 존재하지 않아(`grep -rln rotation kr/co/cudo/authoring/label` 0건), dead 클래스의 회전보간 기능이 숨은 요구사항 갭도 아님.
- **수정 방향(제안)**: `common/util/TrackInterpolator.java` + 대응 테스트 삭제, 또는 명확히 "미사용 초안" 주석 강화.

## 요약
- 총 44건 / PASS 44 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0
- 근거 라인 드리프트: 0건 (전건 file:line 정확 일치 — 단, TrackInterpolator 동명 파일 2개 중 실제 사용 파일과의 일치를 확인함, C-ISSUE-82 참조)
- self-fill 결함: 0건
- 신규 이슈 2건(C-ISSUE-81 MEDIUM/테스트커버리지 갭, C-ISSUE-82 LOW/dead code)
- 실동작 검증에 사용한 마스터 데이터 임시변경(LS_LABEL lbl_id=1 USE_YN, lbl_id=2 lbl_nm/lbl_type_cd)은 전부 원상복구 확인 완료. 검증용으로 생성한 프리셋(id 2,3,4 및 clone 51건)은 전부 DELETE로 정리, 최종 DB는 preset_id=1(원본)만 잔존.
