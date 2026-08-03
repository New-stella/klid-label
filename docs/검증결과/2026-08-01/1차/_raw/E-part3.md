# E-part3 — E-4. 해상도 파생 오케스트레이션 (VideoResolutionService / VideoController)

- 대상 섹션: `docs/test-cases/E-augment-resolution-export-meta.md` → `## E-4` (TC-RESL-001 ~ TC-RESL-021, **21건**)
- 검증 일자: 2026-08-02 (회차 폴더 2026-08-01/1차)
- 검증 커밋: `56d30478` / DB Flyway `V158` (스택 실행중 jar 와 스키마 정합 확인 — `ls_data_raw.aug_type_cd` 존재, `uk_ls_data_aug_resl` 부분유니크 존재)
- 스택: `klid-postgres`/`klid-backend`(:18081 `/api`)/`klid-ai-server`/`klid-mock-server`/`klid-frontend` 전부 healthy
- 검증 방식: **21건 전건 실동작(curl + DB + ffprobe)**. 정적 대조는 보조.

## 판정 요약

| 판정 | 건수 |
|------|---:|
| PASS | 19 |
| FAIL | 1 |
| PARTIAL | 1 |
| BLOCKED / N/A / 확인필요 | 0 |
| **합계** | **21** |

---

## 검증에 사용한 실데이터 (본 검증이 만든 것)

| rawSn | 용도 | 비고 |
|---|---|---|
| 26 | 기존 pipeline-drive 영상(APPROVED, 320×240) | 파생 76/77/78 생성됨 |
| 80 | 신규 업로드→비식별→마킹→배정→검수승인 완주 | TC-RESL-018/019/002 용. 파생 83/84/85 |
| 81 | 동상 | TC-RESL-003/004/005/007/012/016/017/021 용. 파생 86/87/95 |

- `de_ident_yn`·`de_idntf_src_file_path_nm`·`de_idntf_file_path_nm` 을 **본 검증이 만든 행에 한해** 일시 변경 후 **원복 완료**(최종 상태 표로 확인).
- TC-RESL-021 상태 매핑 검증용 합성 파생 3행(`QA0801-E4-SYN-*`)은 관측 후 **DELETE 로 원복 완료**.
- **기존 데이터(rawSn 4~71 및 그 파생 18~25)는 일절 변경하지 않았다.**

---

## 케이스별 결과

| ID | 판정 | 근거 확인 | 실측 내용 |
|----|:--:|------|------|
| TC-RESL-001 | PASS | [실동작] | `POST /v1/videos/26/resolution` 바디 생략 → **201**, `derivatives` 3건 전부 `CREATED` + 새 RAW_SN 76/77/78. DB: `ls_data_raw` 3행(`orgnl_raw_sn=26`, `aug_type_cd=RESL_1080P/720P/480P`), `ls_data_aug` 3행(`RESL_*`, ACCEPTED, `new_raw_sn=76/77/78`) |
| TC-RESL-002 | PASS | [실동작] | `{"presets":["RESL_1080P","RESL_480P"]}` (rawSn=80) → 201, **2건만** 생성(84/85). 720P 미생성 |
| TC-RESL-003 | PASS | [실동작] | `{"presets":["RESL_480P","RESL_480P"]}` (rawSn=81) → 201, 응답 **1건**(86). 중복 미제거였다면 2번째가 `UK_LS_DATA_AUG_RESL` 로 409→`FAILED` 항목이 추가로 보였을 것 — 나오지 않음 = distinct 동작 확인. 정적: `ResolutionChangeRequest.java:36` `stream().distinct()` |
| TC-RESL-004 | PASS | [실동작] | rawSn=81 첫 프레임을 1920×1080 이미지로 지정 후 바디 생략 → 응답에 **RESL_1080P 항목 자체가 없음**(스킵), 720P `CREATED`. 백엔드 로그 `preset skipped (same resolution) rawSn=81 preset=RESL_1080P 1920x1080` |
| TC-RESL-005 | PASS | [실동작] | 같은 조건 + `{"presets":["RESL_1080P"]}` → **400 INVALID_INPUT** `"원본과 동일하지 않은 적용 가능한 해상도 프리셋이 없습니다."` |
| TC-RESL-006 | PASS | [실동작] | rawSn=26 동일 요청 재전송(3종 전부 부분유니크 중복) → **500 INTERNAL_ERROR** `"요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."` — 내부 사유(제약명·스택) 미노출 확인. `{"presets":[]}` 로도 동일(빈 배열→기본 3종) |
| TC-RESL-007 | PASS | [실동작] | rawSn=81(1080P 스킵, 480P 이미 존재) 바디 생략 → **201** + `[{rawSn:87, RESL_720P, CREATED}, {rawSn:null, RESL_480P, FAILED}]` — 실패 프리셋 `rawSn:null` + CREATED 혼재, 성공분은 정상 확정 |
| TC-RESL-008 | PASS | [실동작] | 원본 320×240 → 1920×1080/1280×720/854×480 **전부 업스케일인데 거부 없이 201**. 산출 프레임 ffprobe 실측 `deid/76/frame-0.jpg=1920,1080` · `77=1280,720` · `78=854,480`. 구 `targetH>=srcH` 가드 부재 확인 |
| TC-RESL-009 | PASS | [실동작] | rawSn=18(`orgnl_raw_sn=4`, WINTER 파생) → **400** `"원본 영상에만 해상도 변경 가능"` |
| TC-RESL-010 | PASS | [실동작] | rawSn=5(비APPROVED) → **409 CONFLICT** `"검수 완료(APPROVED)된 영상만 해상도 변경할 수 있습니다."` |
| TC-RESL-011 | PASS | [실동작] | rawSn=999999 → **404 NOT_FOUND**. GET 경로도 동일 404 |
| **TC-RESL-012** | **FAIL** | [실동작] | 기대 `400 "원본 프레임 해상도를 확인할 수 없습니다."` — **실제는 500 INTERNAL_ERROR `"프레임 이미지를 읽을 수 없습니다."`**. 손상/비이미지 파일·파일 부재 두 케이스 모두 500. `Java2DImageResizer.readDimensions` 가 dim≤0 을 **반환하지 않고 예외를 던지므로** `VideoResolutionService.java:110-112` 의 400 분기는 도달 불가 사문화 → **E-ISSUE-41** |
| **TC-RESL-013** | **PARTIAL** | [실동작] | 문자열 축은 화이트리스트 강제 확인 — `"RESL_240P"`/`"resl_720p"`/`"RESL_720P "`/`""`/`true`/`{}`/비배열 전부 **400**. 그러나 **숫자(enum ordinal)는 통과** — `[0]`/`[1]` 은 역직렬화를 통과해 실제 프리셋으로 매핑되고(중복이라 500 all-failed 까지 진행), `[99]` 만 범위초과로 400. 계약(OpenAPI `allowableValues` 3종 문자열) 밖의 표현이 열려 있음 → **E-ISSUE-42** |
| TC-RESL-014 | PASS | [실동작] | POST: WORKER **403**(`FORBIDDEN`) / 토큰없음 **401**(`UNAUTHORIZED`). GET 도 동일(403/401). `VideoController` `@PreAuthorize("hasRole('REVIEWER')")` 양 엔드포인트 부착 |
| TC-RESL-015 | PASS | [실동작] | 201 본문 = `{"derivatives":[{"rawSn","goalResCd","targetW","targetH","status"}]}` 정확히 5필드. 파일경로·EXPORT_SN·예약 aug PK 등 내부 식별자 **미포함** |
| TC-RESL-016 | PASS | [실동작] | 프레임 경로를 `/etc/passwd` 및 `../../../../etc/passwd` 로 각각 지정 → 둘 다 **400** `"원본 프레임 경로가 허용된 저장 경로를 벗어납니다."`, 경로 원문 미반향 |
| TC-RESL-017 | PASS | [실동작] | ①deid 절대경로(`/app/storage/deidentified/frames/deid/{rawSn}/…`)로 정상 통과(001·002·007 전 케이스가 이 축) ②deid 경로를 null 로 비워 raw base 축(`/app/storage/raw/frames/raw/81/…`)으로 폴백시켜도 경로검증 통과(측정 이후 단계까지 진행). 두 base 합집합 허용 확인 |
| TC-RESL-018 | PASS | [실동작] | rawSn=8(`de_ident_yn='F'`, `ls_deident_proc_log` 는 FAILED 만 = 산출물 부재형) → **409 CONFLICT** `"원본의 비식별 산출물을 찾을 수 없어 해상도 파생을 만들 수 없습니다."` — 예약 이전 동기 거부, `ls_data_aug`·파생 RAW 미생성 확인 |
| TC-RESL-019 | PASS | [실동작] | rawSn=80 을 `de_ident_yn='F'`(신고형, SUCCESS procLog + 실파일 존재)로 두고 요청 → **201 CREATED**(rawSn=83), 이후 `COMPLETED` 확정까지 완주. ★2026-07-29 확정정책("신고는 파생 생성을 막지 않는다") 실동작 일치 — **결함 아님** |
| TC-RESL-020 | PASS | [실동작] | `ls_deident_proc_log.de_idntf_file_path_nm` 을 `/etc/passwd` 및 `../../../etc/passwd` 로 변조 → 둘 다 **400 INVALID_INPUT** `"경로가 허용된 비식별 저장 경로를 벗어납니다."`, 경로 원문 미노출 |
| TC-RESL-021 | PASS | [실동작] | `GET /v1/videos/81/resolution` → 200. 매핑 실측: `'Y'`+COMPLETED→**COMPLETED**(86/87) · `DATA_STTS_CD=FAILED`→**FAILED** · PENDING→**IN_PROGRESS**(합성행으로 확인). 비-해상도 파생(WINTER 합성행, 그리고 rawSn=4 의 실제 WINTER/NIGHT/RAIN 18·19·23)은 **전부 제외**(rawSn=4 GET 응답에 20/21/22 세 건만 노출) |

---

## 근거 드리프트 (카탈로그 file:line 정합)

| TC | 카탈로그 표기 | 실제 위치 | 편차 |
|----|------|------|:--:|
| TC-RESL-001 | VideoResolutionService.java:93-142 | 92-141 | -1 |
| TC-RESL-004 | :117-124 | 116-123 | -1 |
| TC-RESL-005 | :127-130 | 126-129 | -1 |
| TC-RESL-006 | :134-140 | 133-139 | -1 |
| TC-RESL-007 | :199-209 | `createOne` 205-215 | +6 |
| TC-RESL-008 | :107-124 | 106-123 | -1 |
| TC-RESL-009 | :224-226 | 230-232 | +6 |
| TC-RESL-010 | :229-234 | 235-240 | +6 |
| TC-RESL-011 | :219-221 | 225-227 | +6 |
| TC-RESL-012 | :111-113 | 110-112 | -1 |
| TC-RESL-016 | :256-268 | `resolveSafeSource` 262-274 | +6 |
| TC-RESL-017 | :260-265 | 266-272 | +6 |
| TC-RESL-018 | VideoResolutionService.java:101 | 100 | -1 |
| TC-RESL-021 | :154-194 / VideoController.java:349-353 | `listDerivatives` 153-168 · `presetOf` 178-189 · `statusOf` 191-200 / 357-361 | +8 |
| TC-RESL-014 | VideoController.java:322-325 | 해당 구간은 `@ApiResponses` 어노테이션. 실제 인가 지점은 332(`@PreAuthorize`) | 오지시 |

정합(드리프트 없음): TC-RESL-002/003(`ResolutionChangeRequest.java:32-37`) · TC-RESL-013(`ResolutionPreset.java:26-28`) · TC-RESL-015 · TC-RESL-018/019/020(`ParentDeidArtifactGuard.java` 65-94 / 70-93 / 100-124).

---

## 테스트 커버 대조 (`_raw/test-baseline.md` = backend 4,755 tests / 실패 0)

| TC | 커버 테스트 | 상태 |
|----|------|:--:|
| 001 | `VideoResolutionServiceTest#createsDerivativePerPreset` | 통과 |
| 002 | `VideoResolutionServiceTest#specifiedPresetsOnly` | 통과 |
| **003** | **없음** (`resolvePresets` distinct 전용 테스트 부재) | **커버 갭** |
| 004 | `#sameResolutionSkipped` | 통과 |
| 005 | `#allTargetPresetsSameResolutionRejected400` | 통과 |
| 006 | `#allFailedReturnsError`, `VideoResolutionControllerTest#모든_프리셋_생성이_실패하면_500이다` | 통과 |
| 007 | `#partialFailureIsolated` | 통과 |
| 008 | `#upscaleAllowedNo400` | 통과 |
| 009 | `#derivedParentRejected` | 통과 |
| 010 | `#notApprovedRejected`, `VideoResolutionControllerTest#미검수_영상_요청시_409` | 통과 |
| 011 | `#notFound404` | 통과 |
| **012** | **없음** (해상도 실측 실패 경로 미커버 — 그래서 500 회귀가 드러나지 않았다) | **커버 갭** |
| 013 | `VideoResolutionControllerTest#화이트리스트_외_preset_값_요청_400` (문자열 축만) | 통과(숫자 축 미커버) |
| 014 | `VideoResolutionControllerTest#미인증_401`, `#REVIEWER가_아니면_403이다` | 통과 |
| 015 | `#responseContainsRawSnsAndStatus`(서비스·컨트롤러 양쪽) | 통과 |
| 016 | `#traversalStillRejected`, `#outsideBothBasesRejected` | 통과 |
| 017 | `#deidFramePathPassesViaDeidBase`, `#rawFramePathPassesViaRawBase` | 통과 |
| 018 | `ParentDeidArtifactGuardTest#F가_비식별API실패인_경우…CONFLICT`, `#SUCCESS_비식별_이력이_없으면…`, `VideoResolutionServiceTest#missingParentDeidArtifactRejectedBeforeReservation` | 통과 |
| 019 | `ParentDeidArtifactGuardTest#신고F여도_비식별_산출물_파일이_실재하면_통과한다`, `VideoResolutionServiceTest#reportedParentWithArtifactStillCreatesDerivatives` | 통과 |
| 020 | `ParentDeidArtifactGuardTest#적재된_경로가_허용_비식별_저장경로_밖이면_거부된다_CWE22` | 통과 |
| **021** | `#listDerivativesResolvesPresetFromColumn`(COMPLETED 만), `#listDerivativesExcludesNonResolutionAndUnknown` | **부분** — `FAILED`/`IN_PROGRESS` 매핑 단언 부재 |

---

## 관찰 사항 (결함 아님 — 확정정책/설계 의도)

1. **★부모 게이트 `'N'`만 차단 / `'F'` 통과** — TC-RESL-018/019 실동작으로 재확인. `ParentDeidArtifactGuard:70`(`hasDeidentArtifact()`)이 `'N'`·null 만 막고, `'F'` 는 **산출물 실재 검증(fail-closed)** 으로 뒤를 받친다. 2026-07-29 구속 정책과 일치하며 **결함으로 재보고하지 않는다.**
2. **중복 재요청이 500 으로 보인다** — 전 프리셋이 `UK_LS_DATA_AUG_RESL` 중복(409)으로 실패해도 `createOne` 이 흡수 후 `all failed → 500` 으로 마감된다. `CLAUDE.md`·카탈로그 모두 "전부 실패=500 / 사유 미노출(CWE-209)"을 확정정책으로 못박고 있어 **정책 준수**로 판정했다. 다만 운영 관측상 "클라이언트 중복 요청"과 "진짜 내부 실패"가 구분되지 않는다는 점만 기록해 둔다(수정 제안 아님).
3. **좌표 배율 실측(E-5 TC-RESL-045 보강 근거)** — 320×240 원본 기준 `ls_data_aug_lbl_map` 실적재값: `RESL_1080P → scale_x=scale_y=4.500000`(=min(1920/320, 1080/240)) · `RESL_720P → 3.000000`(=min(4,3)) · `RESL_480P → 2.000000`(=min(2.669,2)), `coord_recalc_yn='Y'`. **축별 독립 배율이 아니라 균일 배율(레터박스)** 이 실제로 적용됨을 확인. 외부 증강(WINTER/NIGHT/RAIN)은 `coord_recalc_yn='N'` + scale null 로 정상 분리.
4. **파생 비디오는 부모 비식별본 복사 경로에 실재** — `/app/storage/deidentified/videos/resolution/{parent}/{newRawSn}/{PRESET}.mp4` (예: `.../26/76/RESL_1080P.mp4` 50,854 bytes). 원본 폴백 경로 미사용 확인.

---

## 이슈

### [E-ISSUE-41] TC-RESL-012 — 프레임 해상도 실측 실패가 400 이 아니라 500 으로 나가고, 400 가드는 도달 불가 사문화
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 원본 프레임 이미지가 손상/미지원 포맷/부재라 해상도를 확인할 수 없으면 **400 INVALID_INPUT `"원본 프레임 해상도를 확인할 수 없습니다."`** 로 응답해야 한다. 이는 서버 장애가 아니라 **요청 대상 데이터 상태의 전제 불충족**이므로 4xx 여야 FE 가 사용자에게 "이 영상은 프레임이 깨져 해상도 변경이 불가"라고 안내할 수 있고, 5xx 알람(운영 오탐)을 만들지 않는다.
- **현재 동작(이슈 내용)**: `VideoResolutionService.java:107-112`
  ```java
  int[] dim = measureFirstFrame(rawSn);
  int srcW = dim[0]; int srcH = dim[1];
  if (srcW <= 0 || srcH <= 0) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "원본 프레임 해상도를 확인할 수 없습니다.");
  }
  ```
  그런데 실측기 `Java2DImageResizer.java:81-98` 은 **dim≤0 을 반환하는 경로가 없다** — `ImageIO.read` 가 IOException 이거나 null 이면 그 자리에서 `INTERNAL_ERROR("프레임 이미지를 읽을 수 없습니다.")` 를 던진다. 따라서 위 400 분기는 **어떤 입력으로도 도달할 수 없는 사문화 코드**이고, 실제 사용자는 항상 500 을 받는다.
  실측(rawSn=81, 첫 프레임 경로만 조작):
  - 비이미지 파일(mp4) → `HTTP 500 {"errorCode":"INTERNAL_ERROR","message":"프레임 이미지를 읽을 수 없습니다."}`
  - 파일 자체 부재 → 동일하게 `HTTP 500`
- **재현/확인 경로**
  ```sql
  -- 첫 프레임 소스를 읽을 수 없는 파일로 지정(허용 base 안이어야 경로가드를 통과)
  UPDATE ls_data_src SET de_idntf_src_file_path_nm='/app/storage/deidentified/frames/deid/81/NOPE.jpg'
   WHERE raw_sn=81 AND frm_no=0;
  ```
  ```bash
  curl -i -X POST http://localhost:18081/api/v1/videos/81/resolution \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' \
    -d '{"presets":["RESL_1080P"]}'
  # → HTTP/1.1 500 ... "프레임 이미지를 읽을 수 없습니다."
  ```
- **영향**: 기능/운영. ①데이터 상태 문제가 서버 오류로 분류되어 5xx 알람·SLO 오염 ②FE 가 4xx/5xx 로 분기하는 표준 처리에서 "재시도하면 될 일시 장애"로 오안내 ③해당 400 분기가 테스트로도 커버되지 않아(커버 갭) 회귀 감지 불가. 보안 등급은 아님(메시지에 경로·스택 미노출은 유지됨).
- **수정 방향(제안)**: `Java2DImageResizer.readImage` 의 실패를 호출부에서 구분 가능하게 만든다 — 예) 포트 계약을 `Optional<int[]> readDimensionsQuietly(Path)` 로 넓히거나, 실측 실패를 `ErrorCode.INVALID_INPUT` 계열 전용 예외로 승격하고 `VideoResolutionService.measureFirstFrame` 에서 400 메시지로 재던진다. 어느 쪽이든 `srcW<=0` 사문화 분기는 제거하거나 실제 도달 가능하게 배선해야 하며, "손상 프레임 → 400" 테스트를 추가한다. (⚠ 구현은 하지 않는다)

### [E-ISSUE-42] TC-RESL-013 — `presets` enum 이 숫자(ordinal)로도 바인딩돼 문자열 화이트리스트 계약을 우회한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `presets` 원소는 **`RESL_1080P`/`RESL_720P`/`RESL_480P` 세 문자열만** 허용되고 그 외 값·형식은 Jackson 역직렬화 단계에서 400 으로 거부돼야 한다(`ResolutionChangeRequest.java:16-17` 의 명시 계약, OpenAPI `allowableValues` 3종, "자유 입력 해상도 차단 CWE-20").
- **현재 동작(이슈 내용)**: Jackson 기본 동작상 **JSON 정수는 enum ordinal 로 해석**된다. 프로젝트에 `FAIL_ON_NUMBERS_FOR_ENUMS` 류 하드닝 설정이 없어(`application*.yml` 에 `spring.jackson` 블록 자체가 없음) 다음이 성립한다:
  - `{"presets":[0]}` → `RESL_1080P`, `{"presets":[1]}` → `RESL_720P`, `{"presets":[2]}` → `RESL_480P` 로 **정상 바인딩되어 서비스 로직까지 진입**
  - `{"presets":[99]}` → 400 (ordinal 범위 초과일 때만 거부)
  실측(rawSn=81, 해당 프리셋들이 이미 존재해 중복 실패로 마감되는 상태):
  ```
  {"presets":[0]}   -> HTTP 500 "요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."   ← 역직렬화 통과 + 실측 통과 + createOne 시도까지 진행
  {"presets":[1]}   -> HTTP 500 (동상)
  {"presets":[99]}  -> HTTP 400 "요청 본문이 올바르지 않습니다."
  {"presets":["RESL_240P"|"resl_720p"|"RESL_720P "|""|true|{}] } -> 전부 HTTP 400
  ```
  즉 **문자열 축만 화이트리스트가 서고, 숫자 축은 열려 있다.**
- **재현/확인 경로**
  ```bash
  # 파생이 아직 없는 APPROVED 원본에 대해 실행하면 ordinal 이 실제 프리셋으로 생성되는 것까지 관측된다
  curl -i -X POST http://localhost:18081/api/v1/videos/{approvedRawSn}/resolution \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' -d '{"presets":[2]}'
  # → RESL_480P 파생이 생성됨 (문서화되지 않은 표현)
  ```
- **영향**: 입력검증/계약(CWE-20). **값 공간이 넓어지지는 않으므로**(0~2 는 동일한 3개 프리셋) 권한상승·자유해상도 주입은 아니다. 실질 위험은 ①**계약 밖 표현이 허용돼 OpenAPI·연동규격과 실제 수용 입력이 어긋남** ②**순서 의존 취약** — 향후 `ResolutionPreset` 에 상수를 앞/중간에 추가하면 기존 숫자 페이로드가 **조용히 다른 프리셋으로 매핑**되어 잘못된 해상도 파생이 생성된다(무증상 데이터 오류) ③보안 스캐너/감리 관점의 "enum 화이트리스트 강제" 주장이 성립하지 않음.
- **수정 방향(제안)**: 전역 Jackson 설정에 `spring.jackson.deserialization.fail-on-numbers-for-enums: true` 를 켜거나(영향 범위가 넓으므로 전 DTO 회귀 확인 필요), 국소적으로 `ResolutionPreset` 에 `@JsonCreator` 문자열 전용 팩토리(미지값 → `IllegalArgumentException`)를 두어 숫자 입력을 400 으로 떨어뜨린다. 아울러 `VideoResolutionControllerTest` 에 `{"presets":[0]}` → 400 케이스를 추가한다. (⚠ 구현은 하지 않는다)

---

## 남은 커버 갭 (이슈 아님 — 테스트 자산 보강 제안)

- TC-RESL-003: `ResolutionChangeRequest.resolvePresets` 중복 제거 단위 테스트 부재
- TC-RESL-012: 프레임 실측 실패 경로 테스트 부재 (E-ISSUE-41 이 드러나지 않은 직접 원인)
- TC-RESL-021: `statusOf` 의 `FAILED`/`IN_PROGRESS` 매핑 단언 부재 (COMPLETED 만 커버)
