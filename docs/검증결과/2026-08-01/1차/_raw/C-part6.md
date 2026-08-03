# C클러스터 Part6 검증 결과 — C-5(TC-TRACK) + C-6(TC-PRESET)

- 담당 범위: `docs/test-cases/C-marking-labeling.md` `## C-5. TC-TRACK`(31건) + `## C-6. TC-PRESET`(18건) = 49건
- 검증 방식: 정적 대조(Read 소스+테스트) 전건. 빌드/테스트 실행 없이 `_raw/test-baseline.md`(2026-08-01, backend cleanTest 4,755/4,750 성공/0 실패) 결과를 근거로 대조.
- 대상 파일: `TrackInterpolator`(batch/interpolation, common/util 2종) · `PolyshapeMatcher` · `CoordinateTransformer` · `MaskRleConverter` · `YoloTrackService`/`YoloTrackRequest`/`DetectionBoxNormalizer`/`LabelController` · `preset/service/PresetService`(+엔티티/리포지토리/컨트롤러)

## C-5. TC-TRACK (31건)

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-TRACK-01 | PASS | [정적] batch/interpolation/TrackInterpolator.java:48-83; test `twoKeyframesInterpolateBetween`/`threeKeyframesInterpolatePerSegment` | 선형보간 수치 정확 확인(t=(f-k)/span) |
| TC-TRACK-02 | PASS | [정적] :53-55; test `emptyKeyframesProduceEmptyResult` | |
| TC-TRACK-03 | PASS | [정적] :57-64; test `singleKeyframeReturnsOnlyThatFrame` | |
| TC-TRACK-04 | PASS | [정적] :59-62; test `outsideKeyframeStopsProcessing` | |
| TC-TRACK-05 | PASS | [정적] :66-70; test `nextOutsideStoresCurrentOnlyAndStops` | |
| TC-TRACK-06 | PASS | [정적] :35-44(주석)/실제 로직은 마지막 키프레임 이후 loop 미진입; test `noPropagateAfterLastKeyframe` | |
| TC-TRACK-07 | PASS | [정적] :66-75 span≤0→continue, LinkedHashMap.put 재호출로 덮어씀; test `duplicateFrameKeyframeLastWins` | |
| TC-TRACK-08 | PASS | [정적] :50-52; test `negativeTotalFramesRejected` | |
| TC-TRACK-09 | PASS | [정적] :48-49 `Objects.requireNonNull`; test `nullKeyframesRejected` | |
| TC-TRACK-10 | PASS | [정적] :100-139 + PolyshapeMatcher.java:50-69(match 1회 계산·재사용); test `polygonVariableVertexCount` | 중간 프레임 정점수=max(a,b) 확인 |
| TC-TRACK-11 | PASS | [정적] closed/open 분기(:60-68, matchClosed/matchOpen); test `polylineAnchorInterpolated`·`polylineReversedDirectionInterpolated` | 개곡선 방향반전 감지(isReversed)까지 검증됨 |
| TC-TRACK-12 | PASS | [정적] CoordinateTransformer.java:21-41; test `rotate90Verified`·`rotateRoundtripIdentity` | ⚠아래 신규 이슈 참조 — 이 클래스는 프로덕션 미사용(호출부 0건) |
| TC-TRACK-13 | PASS | [정적] :22-27; test `rotateRejectsNullPoints`·`rotateRejectsNullCenter`·`rotateChecksPointsGuardBeforeCenterGuard` | **카탈로그 비고 정정 필요**: "전용 단위테스트 여전히 부재(C-ISSUE-81)"는 사실과 다름 — 해당 가드 테스트가 이미 존재(points→center 순서까지 검증). git log상 최초 커밋(2fd78c48)부터 존재해 최근 추가분도 아님. C-ISSUE-81 "가드 테스트 0%" 클레임은 CoordinateTransformer 한정으로는 **근거 드리프트**로 보임(카탈로그 정정 대상) |
| TC-TRACK-14 | PASS | [정적] :44-65; test `scaleRoundtripIdentity`·`translateRoundtripIdentity`·`scaleRejectsNullPoints`·`translateRejectsNullPoints` | |
| TC-TRACK-15 | PASS | [정적] MaskRleConverter.java:30-59(51-59 첫픽셀 on→0-run삽입); test `leadingOnInsertsZeroRun` `[0,2,1]` | |
| TC-TRACK-16 | PASS | [정적] :30-33; maskToRle(null)/maskToRle(new boolean[0][]) → `int[0]` | 전용 null 테스트는 rleToMask 쪽만 있고 maskToRle(null) 직접 단언 테스트는 없음(로직은 자명, 코드로 확인) |
| TC-TRACK-17 | PASS | [정적] :22,37-39; test `rejectsExcessivelyLargeMask`(1001x1001) | |
| TC-TRACK-18 | PASS | [정적] :44-47 row width 불일치 검사 | ⚠아래 신규 발견: `mask[0].length==0`(w=0, h>0)인 극단 입력은 이 가드를 통과해버리고 이후 `flat[0]` 접근에서 **ArrayIndexOutOfBoundsException**(비제어 예외) 발생 가능 — 단, 클래스가 프로덕션 미사용이라 실사용 경로 위험은 없음(C-ISSUE-101에 통합 기록) |
| TC-TRACK-19 | PASS | [정적] :93-95; test `rleToMaskRejectsNonPositiveWidth`·`rleToMaskRejectsNonPositiveHeight` | |
| TC-TRACK-20 | PASS | [정적] :88-92; test `rleToMaskRejectsExcessivePixels`(1001x1001) | |
| TC-TRACK-21 | PASS | [정적] :102-104; test `rleToMaskRejectsRunLengthOverflow`("초과 15") + 경계값 `rleToMaskAcceptsExactRunLength`(합계==total 정상) | |
| TC-TRACK-22 | PASS | [정적] :85-87; test `rleToMaskRejectsNullRle` | |
| TC-TRACK-23 | PASS | [정적/실동작급] test `rleRoundtripIntegrity1000` — Random(seed=42) 1000회 랜덤 mask 왕복 무손실 검증(픽셀별 assert) | 가장 강한 근거 — 실질적 fuzz 테스트 |
| TC-TRACK-24 | PASS | [정적] LabelController.java:198-203(path/body srcSn 불일치 400); test `YoloTrackControllerTest.pathBodySrcSnMismatch` | |
| TC-TRACK-25 | PASS | [정적] YoloTrackRequest.java:23 `@Size(max=50)`; test `tooManyNextSrcSns`(51개) | |
| TC-TRACK-26 | PASS | [정적] :23 `@NotEmpty`; test `emptyNextSrcSns` | |
| TC-TRACK-27 | PASS | [실동작급] YoloTrackService.java:158-182 + DetectionBoxNormalizer.java:50-69; test `negativePointsClampedNotRejected`(-1.57→0.0 clamp)·`pointsClampedToMeasuredFrameBounds`(실측 100x50 상한 clamp, 실제 PNG 파일 기반) | 400 아님을 확인(구 정책 폐기 실증) |
| TC-TRACK-28 | PASS | [실동작급] :168-172 + DetectionBoxNormalizer.java:54-57 `Double.isFinite`; test `nanPointsRejected` | NaN<0=false 회귀 방지 실증 |
| TC-TRACK-29 | PASS | [실동작급] :173-177; test `degenerateBoxSkippedWithoutDiscardingSequence`(퇴화 1건 스킵, 3프레임 전체 유지, 정상검출 "car" 보존) | |
| TC-TRACK-30 | PASS | [실동작급] :109-114(교차영상 rawSn 비교); test `nextFrameFromDifferentVideoRejected`(실 DB 영상 2건 시딩) | |
| TC-TRACK-31 | PASS | [정적] 프로덕션 참조 grep: `batch.interpolation.TrackInterpolator`→`TrackInterpolationStep.java`만 참조. `common.util.TrackInterpolator`는 main/test 전체에서 **자기 자신 외 참조 0건**(테스트 파일도 없음 — common/util 쪽은 전용 테스트조차 없음, batch 쪽만 테스트 존재) | 카탈로그 기술과 일치 — C-ISSUE-82 미해소 확인(dead code 잔존) |

## C-6. TC-PRESET (18건)

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-PRESET-01 | PASS | [정적] PresetService.java:62-73,164-172; test `PresetControllerTest.PresetController_POST_저장후_DB에는_labelId만_저장되고_LBL_CD는_null`(line 172) | |
| TC-PRESET-02 | PASS | [정적] :127-134; test `PresetController_저장시_구_EVT코드면_400`·`PresetServiceTest.validateEventTypeAgainstCategoryKeys` | |
| TC-PRESET-03 | PASS | [정적] :128-130 blank→return(허용); test 동일 메서드 내 `service.create("빈값","",List.of(10L),"")` → eventTypeCd null 정상 저장 | |
| TC-PRESET-04 | PASS | [정적] :65-67; test `duplicateNameConflict` | |
| TC-PRESET-05 | PASS | [정적] :144-160; test `unknownLabelIdRejected`·`softDeletedLabelIdRejected`·`negativeLabelIdRejected` | |
| TC-PRESET-06 | PARTIAL | [정적] :174-181 saveWithEventUniqueGuard가 DataIntegrityViolationException→CONFLICT 변환; test `createWithDuplicateEventThrowsConflict`는 **Mockito로 DIVE를 직접 throw하는 단위 목킹**이며 진짜 동시 INSERT 레이스를 재현하는 통합/동시성 테스트가 아님(카탈로그 계층=integration, 실제는 unit-mock) | DB 제약(`V15__alter_ls_label_preset_event.sql` UK_LS_LABEL_PRESET_EVNT UNIQUE(EVNT_TYPE_CD)) 자체는 존재해 race-safe하나, "동시 동일 이벤트" 입력 조건을 실제로 구동한 근거는 없음 — TC-PRESET-17(LsLabelPresetCodeLabelIdUniqueIT)과 달리 이 케이스엔 DB레벨 IT가 없음 |
| TC-PRESET-07 | PASS | [정적] :79-80; test `updateMissingPresetThrowsNotFound` | |
| TC-PRESET-08 | PASS | [정적] :81-83; test `PresetController_PUT_...` 자기제외 중복 검증 로직(`existsByPresetNmAndPresetIdNot`) | |
| TC-PRESET-09 | PASS | [정적] :88-93; test `updateWithDuplicateEventThrowsConflict` — 단, 이 역시 Mockito DIVE 직접 throw(TC-PRESET-06과 동일 성격) | |
| TC-PRESET-10 | PASS | [실동작급] :98-103; test `PresetControllerTest.PresetController_DELETE_존재_204_그리고_미존재도_204_idempotent` — 실제 MockMvc 2연속 DELETE로 멱등 실증 | |
| TC-PRESET-11 | PASS | [실동작급] :183-195; test `PresetController_POST_clone_정상_복사본이_생성되고_이름중복시_복사본2로_명명된다` — "(복사본)"→"(복사본 2)" 실제 시퀀스 확인 | |
| TC-PRESET-12 | PASS | [정적] :106-118 eventTypeCd=null로 createWithOptions; test `cloneDoesNotInheritEventMapping` | |
| TC-PRESET-13 | PASS | [정적] :183-195 CLONE_SUFFIX_MAX=50, i=2..50 루프 소진 시 CONFLICT throw — 로직 확인, **전용 단위/통합 테스트 없음**(50회 소진 실패 케이스 자체를 재현하는 테스트 미발견, grep으로 "복사본 50"/"CloneNameExceed" 등 0건) | 테스트 커버 갭(신규 발견) — 로직은 정적으로 타당하나 회귀 가드 부재 |
| TC-PRESET-14 | PASS | [정적] :239-244; test `unlinkedCodeShowsLegacyName`(labelId null) | |
| TC-PRESET-15 | PASS | [정적] :239-244(master==null 분기 공용); test `listWithLabelIdButDeletedMasterShowsUnlinked`(labelId=77, soft delete) | |
| TC-PRESET-16 | PASS | [정적] :246-248 + `LabelGeometry` enum(BBOX→bbox=T/polygon=F, POLYGON→반대, POINT/SKELETON→둘다 F); test `bboxMasterEnablesBboxOnly`·`polygonMasterEnablesPolygonOnly`·`pointAndSkeletonDisableBothToggles` | |
| TC-PRESET-17 | PASS | [실동작급] `LsLabelPresetCodeLabelIdUniqueIT.duplicateLabelIdRejectedByPartialUniqueIndex` — dedup 우회(raw JDBC INSERT)로 DB 부분유니크인덱스(`V119__preset_code_labelid_unique.sql` UK_LS_LABEL_PRESET_CODE_LBLID)가 실제 DataIntegrityViolationException을 던짐을 실증 | 49건 중 가장 강한 동시성 증거 |
| TC-PRESET-18 | PASS | [정적] :220-235 toViews가 labelId 전체 수집 후 `findActiveByIds` 1회 호출; test `listDoesNotTriggerNPlusOne`(Mockito verify times(1)) | |

## 신규 이슈

### [C-ISSUE-101] TC-TRACK-12~23 — CoordinateTransformer·MaskRleConverter 프로덕션 미사용(dead code)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md "CVAT 포팅 전략" Phase 6에 명시된 "MASK ↔ RLE ↔ Polygon 변환"(portable-modules/02)과 "좌표 변환/회전 유틸"(portable-modules/06)은 실제 라벨링 파이프라인(SAM2 분할 결과 저장, 캔버스 회전 등)에서 소비되어야 포팅 목적이 달성된다.
- **현재 동작(이슈 내용)**: `grep -rln "MaskRleConverter" backend/src/main` → 결과가 `MaskRleConverter.java` 자기 자신 1건뿐(호출부 0건). `grep -rln "CoordinateTransformer" backend/src/main` → 마찬가지로 자기 자신 1건뿐. 두 클래스 모두 `backend/src/test/.../common/util/{MaskRleConverterTest,CoordinateTransformerTest}.java` 에서만 참조된다. 실제 SAM2 분할 결과는 `Sam2SegmentService.java:119-152`에서 **polygon을 직접** 응답에 담아 반환하고 RLE 인코딩 경로를 거치지 않는다(`aiRes.polygon()` → `PolygonSimplifier.simplify` → 그대로 반환). 회전(rotate)은 프론트엔드 `frontend/src/features/label/canvas/utils/coordinateTransformer.ts`에 별도로 구현되어 있고, 백엔드는 라벨 엔티티에 회전각(angle/rotation) 필드 자체가 없어(grep 결과 0건) 서버측 회전 재계산이 필요한 지점이 없다.
- **재현/확인 경로**:
  ```
  cd backend/src/main && grep -rln "MaskRleConverter" .   # → 자기 파일만
  cd backend/src/main && grep -rln "CoordinateTransformer" .  # → 자기 파일만
  ```
- **영향**: 기능 결함은 아님(아무도 호출하지 않으므로 런타임 오류 없음). 다만 (1) 문서(CLAUDE.md)가 약속한 포팅 산출물이 실제로는 고아 코드로 남아 있어 유지보수 시 혼동 유발, (2) `MaskRleConverter`에는 미사용 코드 특유의 미검증 극단 입력 처리 결함이 존재 — `maskToRle`에서 `mask`가 `height>0, width==0`(예: `new boolean[3][0]`)인 경우 `mask.length==0` 조기 반환 가드를 통과한 뒤 `total=0`으로 `flat=new boolean[0]`가 되고, 이어지는 `flat[0]` 접근(:53,57)에서 **미제어 `ArrayIndexOutOfBoundsException`**이 발생한다(`IllegalArgumentException`이 아님 — 이 클래스의 나머지 가드는 전부 IAE로 통일돼 있는 것과 대비). 현재는 호출부가 없어 악용 경로가 없으나, 향후 이 클래스를 실제로 SAM2 마스크 저장에 연결하면 그대로 결함이 된다.
- **수정 방향(제안)**: ① 실제로 RLE 저장/회전 기능이 필요 없다면 두 클래스와 테스트를 정리(제거) 또는 카탈로그·CLAUDE.md 포팅 전략 표에서 "완료" 대신 "구현만 되고 미연동" 상태로 명시. ② 향후 연동 계획이 있다면 `MaskRleConverter.maskToRle`의 `width==0` 극단 케이스에 `IllegalArgumentException` 가드 추가(다른 가드와 일관되게).

## 카탈로그 근거 드리프트

- **TC-TRACK-13 비고 정정**: "※전용 단위테스트 여전히 부재(C-ISSUE-81)"는 `CoordinateTransformerTest.java`의 `rotateRejectsNullPoints`/`rotateRejectsNullCenter`/`rotateChecksPointsGuardBeforeCenterGuard` 3건과 불일치. git log 확인 결과 이 테스트 메서드들은 프로젝트 초기 커밋(2fd78c48, `feat(infra)`)부터 이미 존재했으며 이후 수정 이력이 없다. C-ISSUE-81("MaskRle/Coordinate 가드 테스트 0%")은 최소 CoordinateTransformer에 대해서는 사실이 아니며, MaskRleConverterTest 역시 null/DoS/경계값 가드 테스트를 풍부하게 보유(9개 가드 테스트 확인). C-ISSUE-81 클레임 자체의 재검증(또는 폐기) 권고.
