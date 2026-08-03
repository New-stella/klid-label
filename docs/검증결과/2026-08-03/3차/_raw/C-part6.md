# C클러스터 part6 — C-4 TC-KEYPOINT · C-5 TC-TRACK · C-6 TC-PRESET (3차 회차)

담당 라인범위: `docs/test-cases/C-marking-labeling.md` 269~356행 (62건: KEYPOINT 13 + TRACK 31 + PRESET 18)
스택: 3차 §3-1(`stack-bringup.md`) 기준 5개 컨테이너 healthy, 외부연동 전부 mock-server 실배선 확인됨. 백엔드 `cleanTest test` 5198/5203 pass, 0 failure(`test-baseline.md`).
검증 방법: ①실동작(curl, WORKER=2001/REVIEWER=1001 dev 토큰, rawSn=101 seed 재사용) ②정적 대조(file:line) ③테스트 커버 대조(JUnit XML). 빌드/테스트 실행 없음(baseline 재사용), 파일 수정은 카탈로그 1개 파일만.

---

## 1. C-4 TC-KEYPOINT (13건) — 전건 PASS

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-KEYPOINT-01 | PASS | [정적] LabelService.java:792-825(validateSkeletonPoints), :834-839(serializePoints, 카탈로그 838 대비 종료`}` 1줄 차이·무의미) 정확 일치. [테스트] `LabelServiceKeypointTest` 10/10 pass(`SKELETON_17개_삼중값_저장후_조회시_동일값_반환`) |
| TC-KEYPOINT-02 | PASS | [정적] LabelService.java:793-796 정확 일치 |
| TC-KEYPOINT-03 | PASS | [정적] LabelService.java:797-801 정확 일치 |
| TC-KEYPOINT-04 | PASS | [정적] LabelService.java:805-811 정확 일치(NPE 이전 400 fail-secure) |
| TC-KEYPOINT-05 | PASS | [정적] LabelService.java:816-819 정확 일치 |
| TC-KEYPOINT-06 | PASS | [정적] LabelService.java:820-823 — v=0,x=0,y=0 은 음수 검사 통과(0<0 은 false) |
| TC-KEYPOINT-07 | PASS(동작 정상) | [정적] LabelService.java:820-823 동일 분기, x=-1 이면 400. **단 회귀 테스트 부재**(아래 C-ISSUE-101 carry) |
| TC-KEYPOINT-08 | PASS | [정적] KeypointSerializer.java:46 toJson 일치. [테스트] `KeypointSerializerTest#toJsonTripletFormat` pass |
| TC-KEYPOINT-09 | PASS(동작 정상) | [정적] KeypointSerializer.java:80-81 정확 일치. **테스트 부재**(C-ISSUE-101) |
| TC-KEYPOINT-10 | PASS | [정적] KeypointSerializer.java:85-86 정확 일치 |
| TC-KEYPOINT-11 | PASS(동작 정상) | [정적] KeypointSerializer.java:91-92 정확 일치. **테스트 부재**(C-ISSUE-101) |
| TC-KEYPOINT-12 | PASS | [정적] KeypointSerializer.java:69-81(빈/`"[]"` early return 70-72 포함) |
| TC-KEYPOINT-13 | PASS(동작 정상) | [정적] LabelService.java:568-585(normalizePoints, 2-튜플 파서 실패 시 raw 배열 폴백) 정확 일치. **`normalizePoints` 전용 테스트 0건**(`grep -rn "normalizePoints" backend/src/test` 무결과, C-ISSUE-101) |

근거 `file:line` 드리프트: **0건** (13건 전부 실제 코드와 정확 일치 — LabelService.java/KeypointSerializer.java 구간은 카탈로그 품질 양호).

---

## 2. C-5 TC-TRACK (31건)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-TRACK-01~11 | PASS(11건) | [정적] `batch/interpolation/TrackInterpolator.java` 전 라인 정확 일치(48-83 interpolate, 100-139 interpolatePolyshape 등). [테스트] `batch.interpolation.TrackInterpolatorTest` 10/10 pass |
| TC-TRACK-12 | PASS | [정적] CoordinateTransformer.java:21-41 rotate 정확 일치 |
| TC-TRACK-13 | PASS(카탈로그 비고 정정) | [정적] :22-27 정확 일치. **비고 오류 수정**: "전용 단위테스트 여전히 부재(C-ISSUE-81)"는 사실이 아님 — `CoordinateTransformerTest`에 `rotateRejectsNullPoints`/`rotateRejectsNullCenter`/`rotateChecksPointsGuardBeforeCenterGuard` 3건 존재·pass(1차 회차 ISSUES.md에 이미 지적됐으나 카탈로그 본문 미반영 — 이번에 직접 정정, 아래 §4 참조) |
| TC-TRACK-14 | PASS | [정적] :44-65 scale/translate 정확 일치 |
| TC-TRACK-15~23 | PASS(9건) | [정적] MaskRleConverter.java 전 라인 정확 일치(:22,:30-33,:37-39,:44-47,:85-95,:102-104). [테스트] `MaskRleConverterTest` 12/12 pass |
| TC-TRACK-24 | **PASS[실동작]** | `POST /v1/frames/468/yolo-track` body srcSn=469(path=468) → `{"errorCode":"INVALID_INPUT","message":"path 의 srcSn 과 body 의 srcSn 이 다릅니다."}`. LabelController.java:198-208 정확 일치 |
| TC-TRACK-25 | **PASS[실동작]** | nextSrcSns 51개 → `"nextSrcSns: size must be between 0 and 50"`. YoloTrackRequest.java:23 정확 일치 |
| TC-TRACK-26 | **PASS[실동작]** | nextSrcSns=[] → `"nextSrcSns: must not be empty"`. 동일 라인 |
| TC-TRACK-27 | PASS | [정적] YoloTrackService.java:163-180 · DetectionBoxNormalizer.java:59-68 정확 일치. [테스트] `YoloTrackServiceTest#aiserver_응답_음수좌표는_거부되지_않고_0으로_clamp되어_반환된다` 외 2건 pass |
| TC-TRACK-28 | PASS | [정적] YoloTrackService.java:168-172 · DetectionBoxNormalizer.java:54-58 정확 일치. [테스트] `#aiserver_응답_좌표가_NaN이면_INVALID_INPUT` pass |
| TC-TRACK-29 | PASS | [정적] YoloTrackService.java:173-177 정확 일치. [테스트] `#이미지_전체밖_퇴화박스는_해당_검출만_스킵되고_시퀀스_전체는_유지된다` pass |
| TC-TRACK-30 | PASS | [정적] YoloTrackService.java:110-114(카탈로그 110-115, 실제 if~} 는 111-114·직전 주석 109-110 — 1줄 이내 오차, 무의미). [테스트] `#후속프레임이_다른_영상이면_INVALID_INPUT` pass. (실동작 재현은 accessGuard 가 먼저 403 을 낼 수 있어 시도하지 않음 — 단위테스트가 서비스 로직을 직접 검증하므로 충분) |
| TC-TRACK-31 | PASS(구조 단언 확인됨) | [정적] `grep -rn "common.util.TrackInterpolator\|import kr.co.cudo.authoring.common.util.TrackInterpolator" backend/src/main` → **0건**. `batch/interpolation/TrackInterpolator.java:23` 클래스 선언 확인. 카탈로그가 단언한 "여전히 잔존 + 미해소 dead code" 그대로 재확인됨(신규 결함 아님, 기존 UNCERTAINTIES 이월 항목) |

근거 `file:line` 드리프트: **TC-TRACK-13 비고 1건 정정**(위 §4). 그 외 30건은 카탈로그와 정확 일치.

---

## 3. C-6 TC-PRESET (18건) — 전건 PASS, 단 근거 라인 드리프트 다수(카탈로그 직접 정정)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-PRESET-01 | **PASS[실동작]** | `POST /v1/manage/presets` labelIds=[1](person,BBOX) → `"labelCodeOptions":[{"labelId":1,"code":null,...}]` — **`code:null` 확인, LBL_CD 미저장** 실증. create() :62-73 정확. 코드문자열 미저장 근거는 :185-194(toSpecs) — **카탈로그 정정**(구 163-172, 드리프트 22줄) |
| TC-PRESET-02 | **PASS[실동작]** | eventTypeCd="NOT_A_REAL_CATEGORY" → HTTP 400. 근거 :137-144(validateEventType) — **카탈로그 정정**(구 126-134) |
| TC-PRESET-03 | PASS | eventTypeCd="" 는 위 :01 라이브 호출에서 통과 확인(빈 문자열 허용). 근거 :137-144 — **카탈로그 정정**(구 126-134, TC-PRESET-02 와 동일 오류) |
| TC-PRESET-04 | **PASS[실동작]** | 동일 name 재생성 → HTTP 409. 근거 :65-67 정확(정정 불필요) |
| TC-PRESET-05 | **PASS[실동작]** | labelIds=[999999] → `"존재하지 않거나 비활성 라벨입니다: labelId=999999"` 메시지 완전 일치. 근거 :166-183(resolveLabels) — **카탈로그 정정**(구 144-160, 드리프트 22줄) |
| TC-PRESET-06 | PASS | [정적] :197-203(saveWithEventUniqueGuard) — **카탈로그 정정**(구 174-181) |
| TC-PRESET-07 | PASS | [정적] update() 존재 검증 :86-87 — **카탈로그 정정**(구 79-80) |
| TC-PRESET-08 | PASS | [정적] :91-93 — **카탈로그 정정**(구 81-83) |
| TC-PRESET-09 | PASS | [정적] :98-103(saveAndFlush try/catch) — **카탈로그 정정**(구 88-93) |
| TC-PRESET-10 | PASS | [정적] delete() :108-113 — **카탈로그 정정**(구 98-103, 그 구간은 실제로 TC-09 의 saveAndFlush 였음) |
| TC-PRESET-11 | PASS | [정적] resolveCloneName :205-218 — **카탈로그 정정**(구 183-195) |
| TC-PRESET-12 | PASS | [정적] clone() :115-128 — **카탈로그 정정**(구 106-118) |
| TC-PRESET-13 | PASS | [정적] resolveCloneName 루프+throw :211-217 — **카탈로그 정정**(구 185-195, TC-11 과 동일 잘못된 구간이 재사용돼 있었음) |
| TC-PRESET-14 | PASS | [정적] toCodeView !linked 분기 :261-267 — **카탈로그 정정**(구 237-244) |
| TC-PRESET-15 | PASS | [정적] :262-267 — **카탈로그 정정**(구 238-244) |
| TC-PRESET-16 | **PASS[실동작]** | 위 TC-01 응답에서 `"labelType":"BBOX","bboxEnabled":true,"polygonEnabled":false"` — 마스터(person=BBOX)에서 파생 확인, 프리셋 자체 토글 필드 없음. 근거 :268-273 — **카탈로그 정정**(구 245-251) |
| TC-PRESET-17 | PASS | [테스트] `LsLabelPresetCodeLabelIdUniqueIT` 1/1 pass(`동일_프리셋에_같은_labelId_코드_중복INSERT는_부분유니크인덱스로_거부된다`), 마이그레이션 `V119__preset_code_labelid_unique.sql:24` `UK_LS_LABEL_PRESET_CODE_LBLID` 확인 |
| TC-PRESET-18 | PASS | [정적] toViews() 배치 조회 :242-258(`findActiveByIds(labelIds)` 1회 호출 후 순회) — **카탈로그 정정**(구 220-235, 그 구간은 실제로 단건 toView 오버로드였음) |

**PresetServiceTest 25/25 pass** (test-results XML, 0 failures) — create/update/delete/clone/코드뷰 전 케이스 자동 테스트로도 뒷받침.

---

## 4. 카탈로그 정정 내역 (총 17건, 본 세션에서 Edit 직접 반영 — `docs/test-cases/C-marking-labeling.md` 269~356행 범위 내)

전부 `file:line` 근거 드리프트(카탈로그 자체 정합성 결함) — **동작 결함 아님**. `PresetService.java` 에 이력 중 `resolveLabels`(22줄)·`toSpecs`(위치 이동)·update() 확장 javadoc(약 7~10줄) 등이 순차 삽입되며 카탈로그가 갱신 없이 방치된 것으로 추정.

| # | 케이스 | 정정 |
|---|---|---|
| 1 | TC-PRESET-01 | 보조근거 `:163-172` → `:185-194` |
| 2 | TC-PRESET-02 | `126-134` → `137-144` |
| 3 | TC-PRESET-03 | `126-134` → `137-144` |
| 4 | TC-PRESET-05 | `144-160` → `166-183` |
| 5 | TC-PRESET-06 | `174-181` → `197-203` |
| 6 | TC-PRESET-07 | `79-80` → `86-87` |
| 7 | TC-PRESET-08 | `81-83` → `91-93` |
| 8 | TC-PRESET-09 | `88-93` → `98-103` |
| 9 | TC-PRESET-10 | `98-103` → `108-113` |
| 10 | TC-PRESET-11 | `183-195` → `205-218` |
| 11 | TC-PRESET-12 | `106-118` → `115-128` |
| 12 | TC-PRESET-13 | `185-195` → `211-217` |
| 13 | TC-PRESET-14 | `237-244` → `261-267` |
| 14 | TC-PRESET-15 | `238-244` → `262-267` |
| 15 | TC-PRESET-16 | `245-251` → `268-273` |
| 16 | TC-PRESET-18 | `220-235` → `242-258` |
| 17 | TC-TRACK-13 | 비고 "전용 단위테스트 여전히 부재(C-ISSUE-81)" 오류 문구 → 테스트 3건 존재·pass 로 정정 |

(TC-PRESET-04, 17 은 기존 근거가 정확해 정정 없음.)

---

## 5. 이슈 기록 (C-ISSUE-101부터, 담당 파트 신규 번호 부여분)

### [C-ISSUE-101] TC-KEYPOINT-07/09/11/13 — 자동테스트 공백 (1차 C-ISSUE-85 미해소 이월)
- **심각도**: LOW
- **기대 동작(기대효과)**: SKELETON 은 `LBL_TYPE_CD` 기반 type-route 로 격리된 분기라(`KeypointSerializer` javadoc:14-16) 회귀 테스트로 방어가 고정돼야 한다.
- **현재 동작(이슈 내용)**: 3차 실측 재확인 — 1차(2026-08-01) 지적 이후 코드/테스트 변경 없음.
  - `KeypointSerializerTest` 여전히 4건(`roundTrip`/`toJsonTripletFormat`/`emptyInputs`/`rejectsTwoTuple`) — 비배열(TC-09)·숫자아님(TC-11) 케이스 미추가.
  - `LabelServiceKeypointTest` 10건 중 음수 좌표(TC-07) 케이스 없음.
  - `grep -rn "normalizePoints" backend/src/test` → 0건(TC-13, R7 폴백 회귀 가드 없음).
- **재현/확인 경로**: `grep -n DisplayName backend/src/test/java/kr/co/cudo/authoring/common/util/KeypointSerializerTest.java`(4건) · `.../label/LabelServiceKeypointTest.java`(10건, 음수 케이스 0) · `grep -rn "normalizePoints" backend/src/test`(0건).
- **영향**: 회귀 감지력 저하. 특히 TC-13 무커버 상태에서 `normalizePoints` 폴백이 사라지면 SKELETON 라벨이 매 저장마다 "변경됨"으로 오판정되어 `LS_DATA_LBL_HSTRY`/`TASK_MODIFIED` 통지가 무한 증식할 수 있다(1차 분석과 동일, 운영 영향 조용하고 큼).
- **수정 방향(제안)**: ⚠ 구현하지 않음. `KeypointSerializerTest`에 비배열(`"{}"`)·비숫자(`"[[\"a\",2,1]]"`) 2건, `LabelServiceKeypointTest`에 음수좌표 400 1건, R7 무변경 폴백은 "SKELETON 동일값 재저장 시 이력 0건" 통합테스트 1건 추가.

### [C-ISSUE-102] TC-TRACK-12~23 — CoordinateTransformer·MaskRleConverter 프로덕션 미사용 (1차 C-ISSUE-101 미해소 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md CVAT 포팅 전략에 명시된 "MASK↔RLE↔Polygon 변환"·"좌표 변환/회전 유틸"은 실제 라벨링 파이프라인에서 소비돼야 포팅 목적이 달성된다.
- **현재 동작(이슈 내용)**: 3차 재확인 — `grep -rn "MaskRleConverter" backend/src/main` / `grep -rn "CoordinateTransformer" backend/src/main` 모두 **자기 자신 파일 1건뿐**(호출부 0건). 두 클래스 모두 대응 Test 클래스에서만 참조(`MaskRleConverterTest` 12건, `CoordinateTransformerTest` 10건 — 클래스 자체 품질은 양호하나 소비처가 없음). SAM2 분할 결과는 여전히 `Sam2SegmentService`가 polygon 을 직접 응답하고 RLE 경로를 타지 않으며, 라벨 엔티티에 회전각 필드가 없어 서버측 회전 재계산 지점도 없다.
  - 부수 결함(미해소): `MaskRleConverter.maskToRle`에 `height>0,width==0`(예: `new boolean[3][0]`) 입력 시 `mask.length==0` 가드를 통과한 뒤 `flat=new boolean[0]`에서 `flat[0]` 접근으로 **미제어 ArrayIndexOutOfBoundsException**(다른 가드는 전부 IAE로 통일된 것과 대비). 호출부가 없어 악용 경로는 없음(dead code 한정 결함).
- **재현/확인 경로**: `cd backend/src/main && grep -rn "MaskRleConverter\|CoordinateTransformer" .` → 자기 파일만.
- **영향**: 기능 결함은 아님(호출부 없어 런타임 영향 0). 문서-코드 드리프트(유지보수 혼동) + 향후 실연동 시 width==0 극단값 결함이 활성화될 잠재 리스크.
- **수정 방향(제안)**: ⚠ 구현하지 않음. ① RLE 저장·회전이 실제로 불필요하면 두 클래스+테스트를 정리하거나 CLAUDE.md 포팅 전략 표에 "구현만 되고 미연동" 명시. ② 연동 계획이 있다면 `maskToRle`의 `width==0` 가드를 다른 가드와 동일하게 `IllegalArgumentException`으로 통일.

### (참고, 신규 번호 미부여) TC-TRACK-31 — common/util/TrackInterpolator dead code
- 카탈로그 자체가 이미 "C-ISSUE-82 미해소 — dead code"로 정확히 서술 중이며 UNCERTAINTIES.md 이월표 항목과 일치. 3차 재확인 결과 그대로(`grep` 0건 재확인). 별도 신규 이슈 번호 없이 상태만 재확인.

---

## 6. 이전 회차(1차, 2026-08-01) 이슈 대조

| 이전 이슈 | 본 파트 관련 여부 | 3차 상태 |
|---|---|---|
| C-ISSUE-85(TC-KEYPOINT-07/09/11/13 테스트 공백) | 직접 관련 | **미해소** — C-ISSUE-101 로 이월 재기록(코드/테스트 변경 없음 재확인) |
| C-ISSUE-101(TC-TRACK-12~23 dead code) | 직접 관련 | **미해소** — C-ISSUE-102 로 이월 재기록(변경 없음 재확인, width==0 결함도 동일) |
| TC-TRACK-13 비고 드리프트(1차 "카탈로그 근거 드리프트" 섹션에 지적, `C-ISSUE-81` 클레임 재검증 권고) | 직접 관련 | **1차에서 지적만 되고 카탈로그 본문 미반영 상태였음 — 3차에서 직접 정정 완료**(§4) |
| C-ISSUE-82(1차, SAM2 track 외부응답 검증) · C-ISSUE-83(Sam2SegmentService @Transactional) · C-ISSUE-84~86 | C-3(SAM2) 소관, 본 파트(C-4~6) 범위 밖 | 대조 안 함 |

2차(2026-08-02) 회차는 F/G/H 클러스터만 검증해 C 클러스터 비교 대상 없음.

---

## 7. self-fill / 확정정책 대조

- **self-fill**: 본 구간 0건. TC-TRACK-24~30 실동작 응답은 전부 요청 검증(Bean Validation)·서비스 로직 결과이며 하드코딩 값 없음. TC-PRESET 라이브 응답(`code:null`, `bboxEnabled` 등)도 DB/마스터 조회 결과 그대로.
- **★4(라벨명=마스터 등록명)**: TC-PRESET-16 라이브 확인에서 `labelName`이 마스터 그대로("person") — 별도 사전 치환 없음, 확정 정책과 일치.
- **★3(좌표 검증 2축)**: TC-TRACK-27/28 은 AI 검출 응답 축(clamp+퇴역 스킵)이라 확정 정책과 일치, 재보고 안 함.
- 그 외 확정 정책(★1/★2/★5) 해당 케이스 없음.

---

## 8. 남긴 테스트 데이터 (다음 회차 참고)

- `LS_LABEL_PRESET.PRESET_ID=57`("QA3RD-PRESET-TEST", labelId=1 person, eventTypeCd=null) — 실동작 검증용 신규 생성. 삭제하지 않음(다른 검증에 영향 없는 독립 데이터).
