# E 클러스터 part3 (E-4·E-5) 2차 검증 결과

> 대상: `docs/test-cases/E-augment-resolution-export-meta.md` §E-4(21건) · §E-5(42건) = **63건**
> 검증 시각: 2026-07-31 03:52~04:15 KST · backend `localhost:18081`(HEAD `ca3c712b` 재빌드본) · postgres `public` 스키마
> ⚠ `~~TC-RESL-047~~`(Phase C stale PII 게이트 — 재신고)은 **폐기(2026-07-30)** — 집계 제외. E-5 검증 대상은 **41건**.
> ⚠ `~~E-5B 백필~~`(TC-RESL-080~095, 16건)은 **기능 전체 제거**로 검증 대상 아님(본 문서 범위 밖).
> ⚠ 컨테이너 재기동·재빌드·빌드/테스트 실행 **0건**. 소스/설정 수정 0건(본 파일 1개만 신규 작성).
>
> **본 검증이 생성/변경한 데이터**(다른 에이전트 참고용):
> - 신규 해상도 파생 RAW **8건** — `160`·`161`(부모 146) / `164`(153) / `166`·`171`(152) / `174`(156) / `176`·`177`(136)
> - `LS_DATA_AUG` RESL_* 예약행 8건(전부 ACCEPTED 확정) + `LS_DATA_AUG_LBL_MAP` 라벨 매핑
> - `PUT /v1/frames/130/privacy-meta`(rawSn 152 프레임)로 Y/N/Y 설정 후 **원래 상태(전부 null)로 복원 완료** — TC-RESL-052 A/B 대조용
> - 🚨 **rawSn 126·129·130·131·133 은 일절 건드리지 않았다**(읽기만).

## 집계

| 섹션 | 총 | 검증 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 | 폐기 |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| E-4 해상도 파생 오케스트레이션 | 21 | 21 | 21 | 0 | 0 | 0 | 0 | 0 | 0 |
| E-5 예약/확정 | 42 | 41 | 39 | 0 | 2 | 0 | 0 | 0 | 1 |
| **합계** | **63** | **62** | **60** | **0** | **2** | **0** | **0** | **0** | **1** |

근거 구성: **실동작 판정 30건** · 정적+기존테스트 판정 32건.
신규 이슈 **6건**(PARTIAL 2 + 정보성 4) — CRITICAL 0 / HIGH 0 / MEDIUM 2 / LOW 4.
근거 드리프트 **3건**(전부 라인 표기, 동작 결함 아님).

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 판정 | 근거 |
|---|---|:--:|---|
| **E-ISSUE-22** (1차 최고위험) — `FrameSource:68-75` DEIDENTIFIED 에 rawBase 폴백 상시 허용 → 전 영상 PII 격리 fail-open | `candidateBases = (ORIGINAL) ? {rawBase} : {deidBase, rawBase}` | **해소** | [정적] `dataset/export/FrameSource.java:75` 가 **`Path base = (kind == ExportKind.ORIGINAL) ? rawBase : deidBase;`** 단일 base 로 되돌아왔다. `:69-74` 주석이 폐기 사유를 명시("파생 산출물을 deid base 로 이동한 뒤 폴백을 제거한다 — 비식별본이 없는 프레임은 원본으로 대체하지 않고 건너뛴다(fail-closed)"). `:90` 은 DEIDENTIFIED 에 대해 `StorageSubtreePolicy` 서브트리 강제까지 추가. **`backend/src/main/java` 전체 grep 결과 DEIDENTIFIED 경로의 rawBase 폴백 잔존 0건** |
| **E-ISSUE-41** — 파생 프레임의 `SRC`/`DE_IDNTF` 두 컬럼 동일값(`dst,dst`) → export 2벌 md5 동일 · `anonymity="N"` 오표기 | `LsDataSrc.create(..., dst, dst, ...)` | **해소** | **[실동작] 결정적 증거** — 파생 130/131/160/161/164/171/174/176/177 **전건** `SRC_FILE_PATH_NM = NULL`, `DE_IDNTF_SRC_FILE_PATH_NM = /app/storage/deidentified/frames/deid/{rawSn}/frame-N.jpg`. 두 컬럼 동일 행 **0건**. [정적] `ResolutionPersistService.java:284-288` `LsDataSrc.create(newRawSn, frameNo, videoFrameNo, **null**, dst, ...)` + `:267-273` 주석이 "정책 A — 파생영상은 원본 없음"을 명시. 파생 프레임 md5 도 부모와 상이(1920×1080 → 1280×720 실측) |
| **D-ISSUE-46** — `V_COMPLETED_FRAME` 이 원본을 비식별 경로로 노출(파생영상) | rawSn 19 가 `original_path = deidentified_path` 12/12 | **해소(이중)** | [실동작] `select raw_sn, count(*) filter (where original_path=deidentified_path) …` → **전 rawSn 0건**. ①원인 제거(위 E-41) ②뷰 자체에 fail-closed 가드 추가 — 뷰 정의 실측: `AND NOT (src_file_path_nm IS NOT NULL AND TRIM(…) <> '' AND de_idntf_src_file_path_nm IS NOT NULL AND TRIM(…) <> '' AND de_idntf_src_file_path_nm = src_file_path_nm)` (동일 경로 행을 뷰에서 제외). ⚠ 파생 RAW 가 APPROVED 되면 `ORIGINAL_PATH` 가 **NULL** 로 나가는데, 이는 `V_COMPLETED_VIDEO.ORIGINAL_VIDEO_PATH` 가 파생에서 NULL 인 것과 **동일한 확정 계약**(파생영상엔 원본이 없다)이라 결함이 아니다. 현재 APPROVED 파생이 0건이라 live 관측은 불가 |
| **B-ISSUE-61** — 해상도 파생 스트리밍 전면 403 | 파생 비식별본이 raw base 밑 | **해소(재확인)** | [실동작] 본 검증에서 **신규 생성한** 파생 `160`(720P)·`161`(480P) 도 `Range: bytes=0-99` → **206** + `Cache-Control: no-store`. `LS_DATA_RAW.RAW_FILE_PATH_NM` = `/app/storage/deidentified/videos/resolution/{parent}/{new}/{preset}.mp4` (deid base 하위). B-part4 가 확인한 130/131 과 동일 |
| **E-ISSUE-21** — 파생 산출물이 raw base 에 생성·기록 | 12종 증상의 뿌리 | **해소** | [실동작] `/app/storage/raw/resolution` **디렉터리 자체가 부재**. 신규 파생 8건 전부 `{deidBase}/videos/resolution/…` + `{deidBase}/frames/deid/{newRawSn}/`. procLog `DE_IDNTF_FILE_PATH_NM` 도 deid base |
| **E-ISSUE-23** — 확정 실패 파생 RAW 고아 무한 누적 | 부모 13 에 FAILED 파생 12건 | **해소** | [정적] `ResolutionPersistService.deleteFailedDerivativeRaw:237-259` + `VideoRepository.deleteFailedDerivative:445-453`(조건 동봉 DELETE) + `AsyncResolutionRunner:152-164`. [실동작] 동시 중복 5요청 중 4건 실패 후 **고아 RAW 0건**(예약 단계 실패라 RAW 미생성). 잔여 지적은 아래 **E-ISSUE-42**(SQL 조건 1개 누락) |
| **E-ISSUE-24** — 201 CREATED 가 예약 성공만 의미, 확정 실패 관측 불가 | 어느 화면에도 안 보임 | **부분 해소** | [실동작] 신규 `GET /v1/videos/{rawSn}/resolution` 200 — `{"derivatives":[{rawSn:160,…,status:"COMPLETED"},{rawSn:161,…,"COMPLETED"}]}`. IN_PROGRESS/COMPLETED 는 관측 가능. **그러나 확정 실패 파생은 E-ISSUE-23 해소 로직이 RAW 행을 지워버려 이 API 에서도 사라진다** → **E-ISSUE-43** |
| **E-ISSUE-25** — `_RESL_RESL_` 이중 접두 | `VMS_CLIP_ID` 드리프트 | **미해소(존치)** | [실동작] 신규 생성분도 `DEV-CLIP-9502_RESL_RESL_720P_1785437782919`. `LsDataRaw.java:226` `parent.getVmsClipId() + "_RESL_" + goalResCd`(goalResCd 자체가 `RESL_720P`). 다만 `AugTypeParser`(마커 위치 기반 정규화)가 흡수해 **기능 영향 0** → **E-ISSUE-44**(LOW) |
| **E-ISSUE-26** — 종횡비 미보존(강제 왜곡) | 축별 독립 배율 | **해소** | [정적] `LetterboxTransform`(`common/util`) 단일 계산기 도입 — `ResolutionSnapshotService.java:139-143`(라벨) 과 `Java2DImageResizer.java:43-54`(픽셀)이 **같은 인스턴스 계산식**을 쓴다. `scaleX==scaleY==box.scale()`. [실동작] 로그 `snapshot ready … scale=0.4444444444444444 offset=0,0`(480P), `scale=0.6666666666666666`(720P) — 축별 배율 아님 |
| **E-ISSUE-29** — `ResolutionFileMaterializerTest` 가 **raw base 를 기대값으로 고정** | 결함을 정상으로 박아둠 | **해소** | [정적] `ResolutionFileMaterializerTest.java:47-58` — `@TempDir` 한 디렉터리를 raw/deid **동일 base 로 주입**(운영 `/nas-storage` 재현)하고, `:102-128` 이 `frames/deid/{rawSn}` 삭제 · **`frames/raw/{rawSn}` 보존**을 단언한다. raw base 기대값 고정 잔존 0건. `ResolutionSnapshotServiceTest:140 해상도_파생_프레임_이미지가_deid_base_하위에_생성됨(raw_base와_분리된_환경)` · `:282 부모_비식별프레임이_frames_raw_하위면_파생소스로_거부된다` 도 신설 |
| **E-ISSUE-30** — 출력 경로 traversal 가드 도달 불가 | enum 상수명 입력 | **완화** | 여전히 enum 바인딩이라 traversal 문자 유입 경로는 없으나, `resolveSafeDir` 가 `startsWith` 만이 아니라 **`StorageSubtreePolicy.isDeidentifiedArtifact`(비식별 전용 서브트리)** 를 함께 강제하도록 강화됨(`ResolutionReservationPersister.java:157-163`) — 두 base 동일 설정에서 실효 가드가 됐다 |
| **E-ISSUE-31** — 실동작 미재현 4건 | 시드 부재 | **3/4 해소** | TC-RESL-005(전부 스킵 400)·TC-RESL-031(예약 게이트)·TC-RESL-012 인접(프레임 0건 400) 중 **005 는 실동작 재현 성공**(아래). 031(`'N'` 부모)·012(손상 이미지)·043(429 포화)은 이번에도 미재현 — 사유는 각 행 비고 |

---

## ★PII 격리 실측

`SRC_FILE_PATH_NM`(원본) vs `DE_IDNTF_SRC_FILE_PATH_NM`(비식별) 을 SQL 로 직접 대조했다.

| 파생 rawSn | 부모 | SRC 경로 | DE_IDNTF 경로 | 두 값 동일? | 산출물 md5 |
|---:|---:|---|---|:--:|---|
| 130 (720P) | 126 | **NULL** | `/app/storage/deidentified/frames/deid/130/frame-N.jpg` | **아니오** | 부모 `064376b2…` ≠ 130 `05fd23ea…` |
| 131 (480P) | 126 | **NULL** | `…/frames/deid/131/frame-N.jpg` | **아니오** | 131 `c683fd5d…`(3자 전부 상이) |
| **160** (720P, 본 검증) | 146 | **NULL** | `…/frames/deid/160/frame-N.jpg` | **아니오** | 부모 `b090be7e…` ≠ 160 `33222b20…` |
| **161** (480P, 본 검증) | 146 | **NULL** | `…/frames/deid/161/frame-N.jpg` | **아니오** | — |
| **164 / 166 / 171 / 174 / 176 / 177** | 153/152/152/156/136/136 | **NULL** 전건 | `…/frames/deid/{rawSn}/…` 전건 | **아니오** | — |

- **DB 전수 대조**: `V_COMPLETED_FRAME` 에서 `original_path = deidentified_path` 인 행 **0건**(전 rawSn).
- **파생 비디오**: `md5(부모 비식별본) == md5(파생 비디오)` — `1c2d2b2c39a5bae1d71ace03d4a83c69` 로 720P·480P·WINTER 전부 동일. ⚠ **이것이 정상**이다(재인코딩 없이 비식별본을 복사하는 확정 정책). 파생 프레임 이미지만 리스케일된다(치수 실측: 부모 1920×1080 → 720P 1280×720 → 480P 854×480).
- **부모 원본 경로 폴백 잔존 확인** — 3축 모두 부재:
  1. `FrameSource.java:75` DEIDENTIFIED = `deidBase` 단일(위 표).
  2. `ResolutionSnapshotService.deidFrameSourceStrict:240-247` — `deidFilePath` blank/null 이면 **CONFLICT 로 실패**하고 `srcFilePathNm` 으로 폴백하지 않는다.
  3. `ResolutionPersistService.insertFrames:284-288` — `SRC_FILE_PATH_NM` 자리에 리터럴 `null`.
  - 유일하게 원본 프레임을 읽는 곳은 **치수 측정 전용** `ResolutionDerivativeService.frameSourcePath:139-145` / `resolveSafeMeasureSource:322-334` 인데, 픽셀을 산출물로 내보내지 않고 `readDimensions` 만 호출한다(주석 `:319-321` 이 명시).
- **파생 프레임 서빙** [실동작]: `GET /v1/frames/156/deid-image` → **200 / 1280×720 / `Cache-Control: no-store`**. `GET /v1/frames/156/image?raw=true`(REVIEWER) → **404**(원본 폴백 없음). `GET /v1/frames/156/image` → 200(비식별본 기본 서빙 정책대로).
- **파생 상세 응답**(`GET /v1/videos/160`) `filePath` = `/app/storage/deidentified/videos/resolution/146/160/RESL_720P.mp4` — **부모 원본 NAS 경로가 새지 않는다**.
- **부모 무결성**: 파생 8건을 만든 뒤에도 부모 146 의 `frames/deid` 3장 · `frames/raw` 4장 그대로. cleanup 이 원본을 건드린 흔적 0.

---

## ★좌표 배율 실측

부모 전건 1920×1080 → 720P `scale = min(1280/1920, 720/1080) = 0.666667`, `offset=(0,0)` / 480P `scale = min(854/1920, 480/1080) = 0.444444`, `drawW=853` → `offsetX=(854-853)/2=0`.

| 라벨 타입 | 원본 좌표 | scaleX·Y (DB 적재값) | 기대 | 실적재 |
|---|---|---|---|---|
| BBOX (lbl 115, 126→131) | `x1=619.6822412826367` | 0.444444 / 0.444444 | 275.41432945 | **275.4143294589496** ✓ |
| BBOX (lbl 115, 126→131) | `y2=684.0405208327282` | 〃 | 304.01800926 | **304.01800925899033** ✓ |
| BBOX 경계 (lbl 123, x1=**0.0**) | `0.0` | 〃 | 0.0 | **0.0** ✓ (음수 미발생) |
| BBOX 경계 (lbl 349, 146→160) | `x2=1919.6597039276853` | 0.666667 / 0.666667 | 1279.77313595 | **1279.7731359517902** ✓ (목표폭 1280 미만 — 이미지 밖 이탈 없음) |
| POLYGON (lbl 359, 146→160) | `[[1892.0,528.0], …]` | 0.666667 | `[1261.3333…, 352.0]` | **`[[1261.3333333333333,352.0], …]`** ✓ |
| **SKELETON**(17-keypoint, lbl 421, 156→174) | `[[100.0,200.0,**0**],[110.0,205.0,**1**],[120.0,210.0,**2**], …]` | 0.666667 | x·y만 스케일, 가시성 v 불변 | **`[[66.66666666666666,133.33333333333331,0],[73.33333333333333,136.66666666666666,1],[80.0,140.0,2], …]`** ✓ |

- `LS_DATA_AUG_LBL_MAP` 실측: `COORD_RECALC_YN='Y'`, `SCALE_X=SCALE_Y`(720P `0.666667` / 480P `0.444444`) — **축별 독립 배율 아님**(E-ISSUE-26 해소).
- **반올림 누적 오차 없음** — 좌표는 `double` 원본에 배율을 1회 곱해 저장(누적 아님). `LetterboxTransform` 의 정수 반올림은 `drawW/drawH/offset` 에만 적용되고 픽셀·라벨이 **같은 값**을 공유한다.
- **오프셋≠0 케이스는 live 미재현** — 이 환경의 모든 원본이 16:9(1920×1080)라 3 프리셋 전부 `offset=(0,0)` 이다. 비-16:9 레터박스는 `ResolutionLetterboxTest`(4건: 종횡비 보존 / 오프셋이 라벨에 반영 / 정확맞춤 / 음수 오프셋 거부)가 커버한다.
- **누락 라벨 1건은 결함 아님** — 126 의 라벨은 22건인데 파생 130/131 은 21건이다. 미매핑 `lbl_sn=255` 의 `reg_dt=03:02:06` 이 파생 생성 시각(02:55)보다 **뒤**다(스냅샷 시맨틱). 나머지 21건은 전부 `mapped=3`(WINTER+720P+480P).

---

## E-4 결과표 (해상도 파생 오케스트레이션)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-RESL-001 | 프리셋 미지정 3종 전체 생성 | PASS | [실동작] 바디 생략 `POST /v1/videos/146/resolution` → 표준 3종 전부 대상. 로그 `preset skipped (same resolution) rawSn=146 preset=RESL_1080P 1920x1080` + 720P·480P 시도. [정적] `VideoResolutionService.java:57-58,104-105,117-124` + `VideoResolutionControllerTest:90 presets_미지정_바디시_기본_3종생성_201` | ⚠ "3건 CREATED" 자체는 재현 불가 — 이 환경 원본이 전부 1920×1080 이라 RESL_1080P 는 항상 스킵된다(TC-RESL-004 와 상호배타). 3종 동시 CREATED 는 `ResolutionDerivativeFlowIntegrationTest:208` 가 커버 |
| TC-RESL-002 | 프리셋 부분 지정 | PASS | [실동작] `{"presets":["RESL_720P","RESL_480P"]}`(rawSn 136) → 201, `derivatives` **2건만**(1080P 미포함). [정적] `ResolutionChangeRequest.java:32-37` | |
| TC-RESL-003 | presets 중복 제거 | PASS | [실동작] `{"presets":["RESL_720P","RESL_720P"]}`(136) → 201, `derivatives` **1건**(rawSn 176). [정적] `ResolutionChangeRequest.java:36` `presets.stream().distinct().toList()` | |
| TC-RESL-004 | 원본 동일 해상도 프리셋 스킵 | PASS | [실동작] 146(1920×1080) 바디 생략 → 응답 `derivatives` 에 **RESL_1080P 없음**(720P·480P만). 로그 `preset skipped (same resolution)`. [정적] `:117-124` | |
| TC-RESL-005 | 전부 스킵 시 400 | PASS | [실동작] `{"presets":["RESL_1080P"]}`(146) → **400** `"원본과 동일하지 않은 적용 가능한 해상도 프리셋이 없습니다."` `errorCode=INVALID_INPUT`. [정적] `:127-130` | 1차 E-ISSUE-31 ①(시드 부재로 미재현) **해소** |
| TC-RESL-006 | 전부 실패 시 500 | PASS | [실동작] 같은 프리셋 재요청(146 `[RESL_720P]`) → **500** `"요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."`. 응답에 내부 사유·경로 미노출(로그에만 `reason=CustomException`). [정적] `:134-140` + `VideoResolutionControllerTest:147` | |
| TC-RESL-007 | 부분 실패 격리 201 | PASS | [실동작] 146 바디 생략 → **201** + `[{rawSn:null,goalResCd:"RESL_720P",status:"FAILED"},{rawSn:161,goalResCd:"RESL_480P",status:"CREATED"}]` 혼재. 136 `[720P,480P]` 도 동일 패턴(FAILED+CREATED). [정적] `:199-209` | |
| TC-RESL-008 | 업스케일 허용 | PASS | [정적] `:107-124` 에 `targetH>=srcH` 거부 가드 부재(주석 `:39` "업스케일 허용 … 구 업스케일 가드 제거"). [테스트] `VideoResolutionServiceTest:159 업스케일_프리셋도_400없이_정상_생성된다` · `ResolutionDerivativeServiceTest:122` · IT `:392 업스케일_프리셋에서_프레임이_확대되어_저장된다` | 실동작 미재현 — 최대 프리셋이 1080P 인데 원본이 이미 1920×1080 이라 업스케일 대상 프리셋이 존재하지 않는다 |
| TC-RESL-009 | 증강본/파생본 거부 | PASS | [실동작] `POST /v1/videos/160/resolution`(파생) → **400** `"원본 영상에만 해상도 변경 가능"`. [정적] `:224-226` | |
| TC-RESL-010 | 미검수 영상 거부 | PASS | [실동작] rawSn 135(ASSIGNED) → **409** `"검수 완료(APPROVED)된 영상만 해상도 변경할 수 있습니다."`. [정적] `:229-234` | |
| TC-RESL-011 | 영상 미존재 | PASS | [실동작] rawSn 999999 → **404**. [정적] `:219-221` | |
| TC-RESL-012 | 프레임 해상도 확인 불가 | PASS | [정적] `:111-113` + `ResolutionDerivativeServiceTest:140 srcW_srcH가_0이면_파생생성이_거부된다`. [실동작 인접] 프레임 0건 영상(151·155, APPROVED) → **400** `"실측할 프레임이 없습니다."`(`:243-244`) | `dim≤0` 분기 자체는 손상 이미지 주입이 필요해 미재현(파일 수정 금지) |
| TC-RESL-013 | 프리셋 enum 화이트리스트 | PASS | [실동작] `{"presets":["RESL_240P"]}` → **400** `"요청 본문이 올바르지 않습니다."`(Jackson 역직렬화 거부, 내부 파서 정보 미노출). [정적] `ResolutionPreset.java:26-28`(상수 3종) + `VideoResolutionControllerTest:78` | |
| TC-RESL-014 | WORKER/미인증 차단 | PASS | [실동작] WORKER → **403** `FORBIDDEN` / 무토큰 → **401** `UNAUTHORIZED`. [정적] `VideoController.java:332 @PreAuthorize("hasRole('REVIEWER')")` + 테스트 `:59/:68` | 근거 드리프트(아래) |
| TC-RESL-015 | 응답 형태 계약 | PASS | [실동작] 201 본문 `{"derivatives":[{"rawSn":160,"goalResCd":"RESL_720P","targetW":1280,"targetH":720,"status":"CREATED"}]}` — 5필드 정확 일치, **파일 경로·dataAugSn 등 내부 식별자 0건**(CWE-209). [정적] `ResolutionChangeResponse.java` | |
| TC-RESL-016 | measureFirstFrame 경로 CWE-22 | PASS | [정적] `VideoResolutionService.java:256-268` — `normalize()` 후 `!startsWith(rawBase) && !startsWith(deidBase)` → `INVALID_INPUT`("원본 프레임 경로가 허용된 저장 경로를 벗어납니다.", 경로 원문 미노출). [테스트] `VideoResolutionServiceTest:400 상위경로_traversal(..)_은_여전히_INVALID_INPUT으로_차단된다` · `:416 raw도_deid도_아닌_경로는_INVALID_INPUT으로_차단된다` | DB 경로 오염 주입 불가(파괴적)라 실동작 미재현 |
| TC-RESL-017 | deid 프레임 경로 base 허용 | PASS | **[실동작]** 검증한 부모 전건(126/136/146/152/153/156)의 `DE_IDNTF_SRC_FILE_PATH_NM` 이 `/app/storage/deidentified/frames/deid/…` **절대경로**인데 해상도 변경이 정상 통과했다(2-way base 허용 실증). [정적] `:260-265` | |
| TC-RESL-018 | ★부모 비식별 산출물 실재 동기 확인 | PASS | **[실동작]** rawSn 20012(APPROVED, `DE_IDENT_YN='Y'` 이나 SUCCESS procLog **0건**) → **409 CONFLICT** `"원본의 비식별 산출물을 찾을 수 없어 해상도 파생을 만들 수 없습니다."` — **예약 이전 동기 거부**(파생 RAW·aug 예약행 생성 0건 DB 확인). [정적] `ParentDeidArtifactGuard.java:65-94` + `VideoResolutionService.java:101` + `ParentDeidArtifactGuardTest:100/:113/:124` | "201 후 조용한 소멸" 회귀 차단 확인 |
| TC-RESL-019 | ★신고('F') + 산출물 실재 = 생성 허용 | PASS | [정적] 판정 단일 원천 `LsDataRaw.hasDeidentArtifact():340-342` = `"Y".equals ‖ "F".equals` — **3 게이트 전부 이 헬퍼를 호출**한다(`ResolutionReservationPersister:83` · `ResolutionSnapshotService:116` · `ResolutionPersistService:97`) + `ParentDeidArtifactGuard:70`. 어느 곳에도 `'F'` 전용 차단 분기 없음. [테스트] 6건 — `VideoResolutionServiceTest:274` · `ParentDeidArtifactGuardTest:86` · `ResolutionReservationPersisterTest:149` · `ResolutionSnapshotServiceTest:216` · `ResolutionPersistServiceTest:127` · IT `:662 예약후_async확정전에_부모가_비식별신고로_F전이돼도_파생이_정상_확정된다` | **실동작 미재현** — `APPROVED` + `DE_IDENT_YN='F'` + 산출물 실재를 동시 만족하는 영상이 없다(133 은 `'F'`+산출물 실재이나 워크플로 `ASSIGNED`). 133 에 검수 승인을 걸거나 APPROVED 영상에 신고를 거는 것은 **참조 데이터 훼손**이라 의도적으로 미수행 |
| TC-RESL-020 | 부모 procLog 경로가 허용 저장경로 밖 | PASS | [정적] `ParentDeidArtifactGuard.java:100-124 resolveSafeDeidVideo` — 2-way(co-locate 디렉터리 ∪ `StorageSubtreePolicy.isDeidentifiedArtifact`) 둘 다 실패 시 `INVALID_INPUT`("경로가 허용된 비식별 저장 경로를 벗어납니다.", 원문 미노출). [테스트] `ParentDeidArtifactGuardTest:133 적재된_경로가_허용_비식별_저장경로_밖이면_거부된다_CWE22` | |
| TC-RESL-021 | ★파생 확정 상태 조회 API | PASS | **[실동작]** `GET /v1/videos/146/resolution` → 200 `[{rawSn:160,RESL_720P,COMPLETED},{rawSn:161,RESL_480P,COMPLETED}]`. `GET /v1/videos/152/resolution` → **해상도 파생 2건만**(같은 부모의 증강 파생 165 WINTER·167 NIGHT 는 제외 ✓). WORKER 403 / 무토큰 401 / 미존재 404. [정적] `VideoResolutionService.java:154-194` | `FAILED` 매핑은 실동작 미재현 — 확정 실패 파생 RAW 는 `deleteFailedDerivativeRaw` 가 지워 이 API 에서도 사라진다 → **E-ISSUE-43**(관측성) |

---

## E-5 결과표 (예약·스냅샷·산출·확정·러너)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-RESL-030 | 예약행 PENDING 커밋 + 새 RAW | PASS | **[실동작]** 로그 순서 `[ResolutionDerivative] derivative reserved parentRawSn=146 newRawSn=161 dataAugSn=34` → (커밋) → `[AsyncResolutionRunner] starting … rawSn=161`. DB: `LS_DATA_AUG(src_sn=114, RESL_480P)` + `LS_DATA_RAW(161, orgnl_raw_sn=146)`. [정적] `ResolutionReservationPersister.java:68-123`, AFTER_COMMIT 등록 `:137-149` | |
| TC-RESL-031 | 부모 잠금하 산출물 게이트 — 'N'만 차단 | PASS | [정적] `:76-88` — 주석이 `'F'` 통과 정책을 명시하고 조건은 `!parent.hasDeidentArtifact()` 하나. [테스트] `ResolutionReservationPersisterTest:134 부모_비식별산출물이_없으면(N)_예약게이트에서_파생생성이_거부된다` · `:149 부모가_비식별신고구간(F)이어도_해상도_파생영상이_정상_예약생성된다` · IT `:635` | 실동작 미재현 — `APPROVED` + `DE_IDENT_YN='N'` 영상 부재(있어도 `ParentDeidArtifactGuard` 가 요청 시점에 먼저 409). 1차 E-ISSUE-31 ③과 동일 제약 |
| TC-RESL-032 | 대표프레임 SRC_SN null fail-fast | PASS | [정적] `:92-94` INSERT **이전** 400. [테스트] `ResolutionReservationPersisterTest:119` | 실경로에서는 `ResolutionDerivativeService.firstFrame:124-130` 이 먼저 400 을 던져 도달하지 않는 심층방어(`LS_DATA_AUG.SRC_SN` NOT NULL 대비) |
| TC-RESL-033 | 부분유니크 중복 예약 차단 | **PARTIAL** | **[실동작] 결정적 증거** — 같은 (SRC_SN, RESL_720P) **동시 5요청** → **정확히 1건만 201**(rawSn 164), 4건 실패. DB: `LS_DATA_AUG` RESL_720P **1행**, 파생 RAW **1건**, 고아 0. 인덱스 실측 `uk_ls_data_aug_resl … (src_sn, aug_type_cd) WHERE aug_type_cd LIKE 'RESL\_%'` = **상태 무관** ✓. [정적] `:101-109` `DataIntegrityViolationException` → `CONFLICT("동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.")` | ★**409 가 클라이언트에 도달하지 않는다** — 단일 프리셋 요청이면 `changeResolution:134-140` 의 "전부 실패=500" 규칙이 CONFLICT 를 삼켜 **500 INTERNAL_ERROR** 로 나간다(실측). → **E-ISSUE-41** |
| TC-RESL-034 | 출력 경로 CWE-22 + 비식별 서브트리 강제 | PASS | [정적] `:157-163 resolveSafeDir` — `base.resolve(rel).normalize()` 후 **`StorageSubtreePolicy.isDeidentifiedArtifact`** 까지 이중 강제(두 base 동일 설정에서도 `frames/raw/**` 유출 차단). [테스트] IT `:532 경로에_상위탈출_시도시_거부되고_파생_확정_실패시_LS_DATA_RAW_고아행이_남지_않음` · `ResolutionReservationPathIT:77` | 입력이 enum 상수명이라 traversal 유입 경로는 여전히 없음(심층방어) |
| TC-RESL-035 | Phase A 멱등 skip | PASS | [정적] `ResolutionSnapshotService.java:102-106` `"Y".equals(newRaw.getDeIdntfYn())` → `Optional.empty()`(부모 재잠금 이전). [테스트] `ResolutionSnapshotServiceTest:190 이미확정된_파생RAW면_멱등skip하고_부모재잠금을_하지않는다` · `AsyncResolutionRunnerTest:83` · IT `:408 확정된_파생을_PhaseA가_멱등skip하고_프레임이_중복생성되지_않는다` | |
| TC-RESL-036 | Phase A 부모 산출물 재검증 — 'N'만 abort | PASS | [정적] `:108-122` — `!parent.hasDeidentArtifact()` 단일 조건, 주석이 `'F'` 통과를 명시. [테스트] `ResolutionSnapshotServiceTest:201`(N→CONFLICT) · `:216`(F→통과) | |
| TC-RESL-037 | Phase A 비식별 비디오 경로 부재 | PASS | [정적] `:149-153` `orElseThrow(NOT_FOUND, "원본 비식별 영상 경로를 찾을 수 없습니다")`. [테스트] `ResolutionSnapshotServiceTest:238 확정게이트1_…procLog가_없으면_NOT_FOUND로_거부한다(E-29)` | 1차 E-ISSUE-29 지적(테스트 부재) **해소** |
| TC-RESL-038 | Phase A 프레임 0건 fail-fast | PASS | [정적] `:161-164` `INTERNAL_ERROR("파생할 프레임이 없습니다")`. [테스트] `ResolutionSnapshotServiceTest:306` | |
| TC-RESL-039 | Phase A 중복 videoFrameNo fail-fast | PASS | [정적] `:194-199` `seenFrameKeys.add` 실패 → `INTERNAL_ERROR`(라벨 이중매핑 차단). [테스트] `ResolutionSnapshotServiceTest:256 확정게이트2_…(E-29)` | 1차 E-ISSUE-29 지적 **해소** |
| TC-RESL-040 | ★Phase A deid 프레임 경로 strict | PASS | [정적] `:240-247 deidFrameSourceStrict` — blank/null 이면 `CONFLICT("비식별 프레임 경로가 없어 파생영상을 생성할 수 없습니다")`, **원본 폴백 분기 자체가 없다**. [테스트] `ResolutionSnapshotServiceTest:282 부모_비식별프레임이_frames_raw_하위면_파생소스로_거부된다(동일_base_격리)` | |
| TC-RESL-041 | Phase B 비디오 복사 + 프레임 리스케일 | PASS | **[실동작]** 로그 `[B] materialized rawSn=161 frames=3 target=854x480`. 파일 실측 — 비디오 34,654,319B(부모와 md5 동일=복사), 프레임 3장 치수 854×480. [정적] `ResolutionFileMaterializer.java:62-80` — `@Transactional` 미부착·리포지토리 주입 0 확인 | |
| TC-RESL-042 | Phase B 원본 비식별 파일 부재 | PASS | [정적] `:66-68` `videoFileCopier.exists` false → `NOT_FOUND("원본 비식별 영상 파일을 찾을 수 없습니다.")`, 폴백 없음. [테스트] `ResolutionFileMaterializerTest:85` | |
| TC-RESL-043 | Phase B 리사이즈 게이트(DoS) | PASS | [정적] `:63 resizeGate.acquire()` 가 **비디오 복사(`:69`) 이전**, `:77-79 finally release` — 복사+리사이즈 전체가 한 슬롯. `ResizeConcurrencyGate` = fair Semaphore(기본 2) + 5s 타임아웃 → 429 `TOO_MANY_REQUESTS`. 설정 실측 `application.yml:391-392` `RESOLUTION_RESIZE_MAX_CONCURRENT:2` / `..._TIMEOUT_SEC:5`. [테스트] `ResolutionFileMaterializerTest:66/:85`(성공·실패 모두 release 검증) | 429 포화는 미재현 — Phase B 1건이 ~200ms(3~12프레임)이고 `batchAsyncExecutor` 가 core2/max4 라 5초 대기 상한을 넘길 부하를 만들 수 없다(1차 E-ISSUE-31 ④와 동일) |
| TC-RESL-044 | Phase C 확정 영속 | PASS | **[실동작]** 로그 `[C] persisted rawSn=161 orgnlRawSn=146 dataAugSn=34 frames=3 labels=13 metas=21 metaReviews=15`. DB 전건 확인 — 프레임 3행 INSERT · `LS_DATA_AUG_LBL_MAP` 13행 · aug `PENDING→ACCEPTED` · `DE_IDENT_YN='Y'` · `DATA_STTS_CD='COMPLETED'` · `LS_DEIDENT_PROC_LOG(161, SUCCEEDED, 파생 비디오 경로)`. [정적] `ResolutionPersistService.java:85-154`(순서: 확정 블록 `:134-143` → `copyMetaAndReviews` `:148`) | `LS_RAW_DATA_STATUS` 는 생성되지 않음(워크플로 상태 미개입) — 정책대로 |
| TC-RESL-045 | ★레터박스 종횡비 보존 좌표 변환 | PASS | **[실동작]** 위 "좌표 배율 실측" 표 — BBOX·POLYGON·**SKELETON(가시성 v 불변)** 3종 전부 균일 배율 검산 일치. `LS_DATA_AUG_LBL_MAP.SCALE_X == SCALE_Y`. [정적] `ResolutionSnapshotService.java:136-143`(`LetterboxTransform.of` → `scaleX=scaleY=box.scale()`) + `ResolutionPersistService.java:384-409` + `Java2DImageResizer.java:43-54`(**같은 계산기**를 픽셀에도 적용). [테스트] `ResolutionLetterboxTest` 4건 | offsetX/Y≠0 은 live 미재현(전 원본 16:9). `:377-382` 이 "매핑 행만으로 오프셋 역산 불가"라는 추적성 한계를 스스로 명시 — 케이스 기대와 일치 |
| TC-RESL-046 | 부모 라벨 0건 | PASS | **[실동작]** 라벨 0건 영상 152 → 파생 166 생성 성공(`COMPLETED`), 파생 라벨 0건·매핑 0건, 예외 없음. [정적] `:386-389` | |
| ~~TC-RESL-047~~ | ~~Phase C stale PII 게이트 — 재신고~~ | — | **[폐기 2026-07-30]** 집계 제외. 판정축이 신고→복사 원자성으로 교체됨. [정적 확인] `ResolutionPersistService.java:312-314` 주석이 "구 조건 '`capturedAt` 이후 신고 이력 존재'는 순수 신고 결합이라 **제거**됐다"를 명시하고, `assertDeidentNotReplacedSince:322-360` 에 신고 이력 조회가 **실제로 없다**(남은 조건은 ①procLog 경로 ②mtime 둘뿐) → 대체 케이스 048/049 로 이관 | |
| TC-RESL-048 | Phase C stale — 비식별 경로 변경 abort | PASS | [정적] `:328-342` 최신 SUCCESS procLog 경로 ≠ 스냅샷 경로 → `CONFLICT("스냅샷 이후 원본 비식별본이 변경되어…")`. 경로 해석 실패는 `resolveQuietly:362-369` 가 `null` 반환 → `currentDeid == null` 로 **불일치 취급(abort)** ✓. [테스트] `ResolutionPersistServiceTest:148` · IT `:748 진짜_A~C창_PhaseB중_부모비식별본이_신규경로로_교체되면_PhaseC가_CONFLICT하고_cleanup이_실제파일을_삭제한다` | |
| TC-RESL-049 | Phase C stale — 파일 mtime 교체 abort | PASS | [정적] `:344-359` — `if (Files.exists(snapshotDeid))` **파일 존재 시에만** 판정, `IOException` 은 로그만 남기고 보수적 통과. [테스트] `ResolutionPersistServiceTest:252 확정게이트3_…mtime으로_교체됐으면_CONFLICT로_abort한다(E-29)` | 1차 E-ISSUE-29 지적(테스트 부재·페일오픈 미확정) **해소**(테스트 신설 + 주석이 페일오픈을 의도로 명시) |
| TC-RESL-050 | Phase C 부모 재잠금 최종 게이트 — 'N'만 abort | PASS | [정적] `:90-103` — 잠금 순서 `parent(:94) → newRaw(:113)` 고정(주석 `:91` 명시), `!hasDeidentArtifact()` 단일 조건. [테스트] `ResolutionPersistServiceTest:115`(N) · `:127`(F 통과) · IT `:698` | |
| TC-RESL-051 | 중복 finalize CAS skip | PASS | [정적] `:111-119` — `findByRawSnForUpdate(newRawSn)` 재잠금 후 `"Y".equals` → `Result.SKIPPED`(프레임 재삽입 없음). [테스트] `ResolutionPersistServiceTest:165` · IT `:485 같은_파생RAW를_2스레드가_동시_finalize해도_프레임은_1회만_삽입된다(#5_승자보호)` | |
| TC-RESL-052 | 프레임 개인정보 3필드 복사 + SRC null | PASS | **[실동작] A/B 대조** — 부모 152 프레임(src 130) 3필드가 **null** 이던 시점 생성분 파생 166 = `null,null,null`; `PUT /v1/frames/130/privacy-meta` 로 `Y/N/Y` 설정 후 생성분 파생 171 = **`Y,N,Y`**. 두 파생 모두 `SRC_FILE_PATH_NM=NULL`. [정적] `:275-292` | 검증 후 src 130 을 원래대로(전부 null) 복원 완료 |
| TC-RESL-053 | 예약 aug 슬롯 해제 | PASS | [정적] `:200-222` — `RESL_` 접두 + 라벨맵 미참조 조건에서만 `delete`. 인덱스 실측이 **상태 무관**(`WHERE aug_type_cd LIKE 'RESL\_%'`)임을 확인했으므로 예약행 잔존 = 영구 락아웃이 맞다. [테스트] `ResolutionPersistServiceTest:363/:370/:382/:395` · IT `:591 finalize_transient실패시_예약aug행이_삭제되고_새RAW는_FAILED이며_동일프리셋_재시도가_성공한다` | 실동작 미재현 — Phase A~C 실패를 파일/DB 훼손 없이 유발할 수단이 없다 |
| TC-RESL-054 | 슬롯 해제 방어 — 라벨맵 참조 시 미삭제 | PASS | [정적] `:205-210` `findAllByDataAugSn` 비어있지 않으면 WARN + return. [테스트] `ResolutionPersistServiceTest:370 …승자참조_보호` | |
| TC-RESL-055 | 슬롯 해제 방어 — 비-RESL 미삭제 | PASS | [정적] `:211-221` `augTypeCd.startsWith(RESL_PREFIX)` 아니면 WARN skip. [테스트] `ResolutionPersistServiceTest:395 …오배송_방어` | |
| TC-RESL-056 | isAlreadyFinalized FOR UPDATE 판정 | PASS | [정적] `:178-187` `findByRawSnForUpdate` + `'Y' ‖ COMPLETED`. `:168-176` javadoc 이 잔여 창 2종(승자-뒤짐·락 타임아웃)을 **정직한 한계로 명시** — 케이스 기대와 일치. [테스트] `ResolutionPersistServiceTest:326/:332` | |
| TC-RESL-057 | 러너 A→B→C 정상 완주 | PASS | **[실동작]** 로그 4행 시퀀스 실측 — `starting … rawSn=161` → `[A] snapshot ready … frames=3 scale=0.444…` → `[B] materialized …` → `[C] persisted …` → `finalize completed rawSn=161`. 예외 0. [정적] `AsyncResolutionRunner.java:56-86` | 파생 8건 전부 동일 시퀀스 |
| TC-RESL-058 | 러너 snapshot empty skip | PASS | [정적] `:62-66` `opt.isEmpty()` → 로그 후 return(B·C 미실행). [테스트] `AsyncResolutionRunnerTest:83` | |
| TC-RESL-059 | 러너 persist SKIPPED — 파일 미정리 | PASS | [정적] `:73-79` — SKIPPED 면 `handleFailure` 미호출(cleanup 없음). 주석 `:75-76` 이 "승자와 동일 경로라 cleanup 하면 승자 산출물을 지운다"를 명시. [테스트] `AsyncResolutionRunnerTest:96` | |
| TC-RESL-060 | ★러너 실패 정리 — 승자 보호 선점검 | PASS | [정적] `:98-113` — `isAlreadyFinalized` 가 cleanup(`:127`)·`markRawDataFailed`(`:150`) **양쪽보다 먼저**. 재조회 예외는 보수적 false. [테스트] `AsyncResolutionRunnerTest:145 실패했지만_이미_승자가_확정(Y)했으면_cleanup도_FAILED전이도_aug해제도_스킵한다(M-1_승자산출물보호)` · `:234` | |
| TC-RESL-061 | 러너 실패 정리 — cleanup+슬롯해제+FAILED | PASS | [정적] `:115-150` 순서 — WARN(`:119`) → `resolutionMetrics.finalizeFailed()`(`:121`) → cleanup(`:129`) → `releaseReservedAug`(`:145`) → `markRawDataFailed`(`:150`). 예약 해제 예외도 FAILED 전이를 막지 않음(`:146-149`). [테스트] `AsyncResolutionRunnerTest:111/:128/:164/:182/:199/:218` | |
| TC-RESL-062 | cleanup 원본 미삭제 보장 | PASS | [정적] `ResolutionFileMaterializer.java:98-154` — 삭제 대상은 `frames/deid/{newRawSn}`(`:108`)·레거시 `resolution/{newRawSn}`(`:127`)·`videoDst` 뿐. `resolveSafeDir` 가 `isDeidentifiedArtifact` 를 강제해 `frames/raw/**` 는 애초에 해석되지 않는다. [테스트] `ResolutionFileMaterializerTest:102`(같은 base 에 둔 `frames/raw/{rawSn}` 보존 단언). **[실동작]** 파생 8건 생성 후 부모 146 의 `frames/deid` 3장·`frames/raw` 4장 무결 | |
| TC-RESL-063 | @Async 예외 삼킴 | PASS | [정적] `:47-50 runAsync` → `finalizeDerivative` 가 `:81 catch (RuntimeException)` 로 전건 흡수, 재throw 없음 | |
| TC-RESL-064 | createResolutionPending RESL_ 접두 강제 | PASS | [정적] `LsDataAug.java:214-215`(팩토리) → `:227-231 buildResolution` 가 `!augResTypeCd.startsWith(RESL_PREFIX)` 시 `INVALID_INPUT("해상도 파생 코드는 'RESL_' 접두여야 합니다.")` | 전용 단위 테스트 **부재**(호출 픽스처로만 간접 사용) → E-ISSUE-45 |
| TC-RESL-065 | markResolutionGenerated 이중전이 차단 | PASS | [정적] `LsDataAug.java:248-256` — `RESL_` 접두 가드(`:249-252`) + `!STTS_PENDING.equals` 시 `CONFLICT("이미 처리된 해상도 파생 행입니다.")`(`:253-256`). **[실동작 인접]** 확정된 RESL 행에 `POST /v1/augments/{id}/accept`·`/reject` → **400** `"해상도 파생 결과는 검수 대상이 아닙니다."`(aug 2·3·31·34 전건, 상태 불변 확인) | 전용 단위 테스트 부재 → E-ISSUE-45. 근거 드리프트(아래) |
| TC-RESL-066 | ★확정 실패 파생 RAW 고아 정리 | **PARTIAL** | [정적] `ResolutionPersistService.java:237-259` — 잠금 후 Java 4조건 재확인(파생임/`'Y'` 아님/FAILED/프레임 0건) ✓. 그러나 **DELETE 문(`VideoRepository.deleteFailedDerivative:445-453`)에 동봉된 조건은 3개**(`ORGNL_RAW_SN IS NOT NULL`·`DATA_STTS_CD='FAILED'`·`NOT EXISTS 프레임`)로 **`DE_IDENT_YN <> 'Y'` 가 빠져 있다**. javadoc `:232-233` 은 "최종 DELETE 문에도 동일 조건을 SQL 조건으로 함께 걸어"라고 서술. [테스트] `ResolutionPersistServiceTest:274/:289/:303` | 실효 위험은 낮다(같은 REQUIRES_NEW 트랜잭션의 `FOR UPDATE` 잠금 하에서 Java 검사→DELETE 가 이어지고, `'Y'` 확정은 항상 `COMPLETED` 를 동반해 `DATA_STTS_CD='FAILED'` 조건에서 이미 걸러진다). **문서·구현 불일치** → **E-ISSUE-42** |
| TC-RESL-067 | 파일 잔존 시 RAW 행 보존 | PASS | [정적] `AsyncResolutionRunner.java:126-141`(`artifactsClean` 산출·예외 시 false) + `:155-158` `if (!artifactsClean) { WARN; return; }` — `deleteFailedDerivativeRaw` 미호출. 주석 `:125,154` 가 "유일한 DB 포인터 소실 방지"를 명시 | `AsyncResolutionRunnerTest` 에 `deleteFailedDerivativeRaw` 호출/미호출을 단언하는 테스트가 **1건도 없다**(`:128` 은 메트릭만 검증) → E-ISSUE-45 |
| TC-RESL-068 | 레거시 raw base 파생 디렉터리 정리 | PASS | [정적] `ResolutionFileMaterializer.java:119-138` — `legacyDir.startsWith(legacyRoot) && !legacyDir.equals(legacyRoot)` 두 조건 통과 시에만 재귀 삭제, raw base 미설정은 `IllegalStateException` 으로 잡아 **잔존 판정에 미반영**(`:134-137` 주석). **[실동작]** `/app/storage/raw/resolution` 디렉터리 부재(정리 대상 소진) | `ResolutionFileMaterializerTest` 가 `storageRawPath` 를 주입하지 않아 이 분기가 **테스트에서 항상 skip 된다** → E-ISSUE-45 |
| TC-RESL-069 | ★파생 비디오 경로 키에 파생 RAW_SN 포함 | PASS | **[실동작]** DB `RAW_FILE_PATH_NM` 실측 — `/app/storage/deidentified/videos/resolution/**146**/**160**/RESL_720P.mp4`, `…/146/161/RESL_480P.mp4`(부모·파생 두 축 모두 경로에 포함). 같은 (부모,프리셋) 재시도로 만들어진 152 의 480P·720P 도 각각 `152/166/`·`152/171/` 로 분리. 파일시스템에 `.pending` 잔존 **0건**(`find` 실측) → 잠정 경로가 커밋되지 않음 ✓. [정적] `ResolutionReservationPersister.java:111-119,126-134` + `StorageSubtreePolicy.resolutionVideoFile`. [테스트] `ResolutionReservationPathIT:77` | |
| TC-RESL-070 | ★파생 메타 복사 + 미검수 검수행 | PASS | **[실동작]** 부모 146 메타 21건 → 파생 160·161 각 **21건**(전체 복사, `video.*` 포함). 검수행: 부모 146 = `APPROVED` 15건 → 파생 = **`PENDING` 15건**(APPROVED 미승계 ✓). 로그 `metas=21 metaReviews=15`. [정적] `DerivedMetaCopier.java:72-158`(배치 upsert 1회 `:97`, `(metaSn, metaTypeCd)` 선재 skip `:114-117,138-140`). [테스트] `DerivedMetaCopierTest` 6건 + `DerivedMetaCopierIT` 3건 | `video.*` 가 부모와 동일한 것이 정상(비디오 재인코딩 없음) — 확정 정책대로 결함 아님 |
| TC-RESL-071 | ★메타 복사 호출 순서 계약 | PASS | [정적] `ResolutionPersistService.java:134-148` — `markResolutionGenerated`(134-136) → `markDeidentified('Y')`(138) → `markCompleted`(139) → procLog save(143) → **`copyMetaAndReviews`(148)**. `DerivedMetaCopier.java:43-49` 가 역전 시 결과(dirty 유실→영구 미확정)를 명시. **[실동작 간접]** 파생 8건 전부 `DE_IDENT_YN='Y'`+`COMPLETED`+aug ACCEPTED 와 메타 21건이 **동시 성립** = 순서 계약이 실제로 지켜졌다는 증거. [테스트] IT `:350 …(순서계약_HIGH4)` | |

---

## 근거 드리프트

동작 결함이 아니라 **케이스표의 근거 표기가 현행 코드와 어긋난** 건이다.

| ID | 표기된 근거 | 실제 | 성격 |
|---|---|---|---|
| TC-RESL-014 | `VideoController.java:322-325` | `:330 @PostMapping("/{rawSn}/resolution")` · `:332 @PreAuthorize("hasRole('REVIEWER')")` (322-325 는 Swagger `@ApiResponses`) | 라인 |
| TC-RESL-021 | `VideoController.java:349-353` | `:357 @GetMapping("/{rawSn}/resolution")` · `:358 @PreAuthorize` · `:359-362` 핸들러 | 라인 |
| TC-RESL-065 | `LsDataAug.java:248-249` | `:248` 메서드 시그니처 · `:249-252` 는 **RESL_ 접두 가드**. 이중전이 409 는 **`:253-256`** | 라인(가리키는 가드가 다름) |

> 참고: 1차 **E-ISSUE-28**(TC-RESL-013 근거가 클래스 Javadoc)은 케이스표가 라인 없는 `ResolutionPreset.java (enum 상수 3종)` 로 바뀌어 **해소**됐다(실제 상수는 `:26-28`). 그 밖의 E-4/E-5 근거 **59건은 전부 정합**이다.

### 참고 관측 (판정에 영향 없음)

- **파생영상의 `AUG_TYPE_CD` 판별을 조회 API 가 문자열 파싱으로 한다** — `VideoResolutionService.presetOf:172-183` 이 `AugTypeParser.parse(vmsClipId)` 를 쓴다(`LS_DATA_AUG` 조인이 아님). 확정 실패로 예약행이 삭제된 파생도 목록에 남길 수 있는 이점이 있는 반면, CLAUDE.md 가 권한 "문자열 파싱 폐지" 방향과는 반대다. 파서가 이중/구형 접두를 모두 흡수하도록 강화(`AugTypeParser` 마커 위치 기반)돼 현재 오작동은 없다.
- **`AsyncResolutionRunner` 가 `batchAsyncExecutor`(core2/max4/queue50)를 배치 파이프라인과 공유**한다. 해상도 파생을 대량 요청하면 배치 스텝이 큐에서 대기한다. 본 검증(동시 5요청)에서는 지연만 관측되고 실패는 없었다.
- **`GET /v1/videos` 목록에서 파생 RAW 는 정상 제외**된다(실측: 160/161/164/166/171/174/176/177 미노출, 같은 시각 생성된 원본 168/169 는 노출).

---

## 이슈 상세

### [E-ISSUE-41] TC-RESL-033 — 중복 해상도 파생 요청이 409 CONFLICT 가 아니라 **500 INTERNAL_ERROR** 로 나간다

- **심각도**: MEDIUM (API 계약/운영 — 클라이언트가 "중복"과 "서버 장애"를 구분할 수 없고 5xx 알람이 오발화)
- **기대 동작(기대효과)**: 케이스 기대는 `409("동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.")` 이며, 부분 유니크 인덱스가 막은 "이미 존재" 는 클라이언트 정정 가능한 조건이므로 4xx 여야 한다(`api-design.md`: 409 = 충돌/중복).
- **현재 동작(이슈 내용)**:
  - `ResolutionReservationPersister.java:106-109` 는 정확히 409 를 던진다.
    ```java
    } catch (DataIntegrityViolationException e) {
        throw new CustomException(ErrorCode.CONFLICT, "동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.");
    }
    ```
  - 그런데 `VideoResolutionService.createOne:203-208` 이 **모든 `RuntimeException` 을 삼켜** `FAILED` 결과로 바꾸고, `changeResolution:134-140` 이 "CREATED 가 하나도 없으면" `INTERNAL_ERROR`(500)로 승격한다. 요청 프리셋이 1개면 중복 = 전부 실패 = **500**.
  - [실동작] `POST /v1/videos/146/resolution {"presets":["RESL_720P"]}` 재요청 → `500 {"errorCode":"INTERNAL_ERROR","message":"요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."}`. 동시 5요청 시에도 승자 1건 201 / **패자 4건 전부 500**.
  - 로그에는 원인이 남는다(`[Video][Resolution] derivative creation failed … reason=CustomException` + `all presets failed`), 즉 서버는 중복임을 알면서 5xx 로 응답한다.
  - 다중 프리셋 요청에서는 201 + 항목별 `FAILED` 로 나가므로(실측) 여전히 사유를 알 수 없다 — 응답에 사유 코드가 없다(CWE-209 회피 목적의 의도적 설계).
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/v1/videos/146/resolution \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' -d '{"presets":["RESL_720P"]}'   # 1회차 201
  # 동일 명령 재실행 → 500
  ```
- **영향**: FE 가 "이미 만들어져 있음"을 안내하지 못하고 일반 오류로 처리한다. 운영 모니터링에서 정상 중복 클릭이 5xx 로 집계돼 알람 노이즈·SLO 오염. 보안 영향 없음.
- **수정 방향(제안)**: `createOne` 이 `CustomException` 의 `ErrorCode` 를 보존해(예: `CreatedDerivative` 에 추상 사유코드 필드 추가) `changeResolution` 이 "전부 실패이고 전부 CONFLICT" 면 409 로, 그 외 전부 실패면 500 으로 갈라 응답한다. 응답에는 경로·스택이 아닌 **사유 코드만** 싣는다. ⚠ **구현하지 않는다.**

### [E-ISSUE-42] TC-RESL-066 — `deleteFailedDerivative` DELETE 문에 `DE_IDENT_YN <> 'Y'` 조건이 빠져 javadoc 의 "동일 조건 동봉" 서술과 불일치

- **심각도**: LOW (문서·구현 불일치 / 심층방어 결손 — 현재 실피해 경로는 확인되지 않음)
- **기대 동작(기대효과)**: `ResolutionPersistService.java:230-233` javadoc — "다음 중 하나라도 어긋나면 삭제하지 않는다 — ①파생이 아님 ②이미 확정(deIdntfYn='Y') ③상태가 FAILED 가 아님 ④프레임이 이미 적재됨. **최종 DELETE 문에도 동일 조건을 SQL 조건으로 함께 걸어**, 검사~삭제 사이에 상태가 바뀐 행은 0건 삭제된다."
- **현재 동작(이슈 내용)**: `video/repository/VideoRepository.java:445-453`
  ```sql
  DELETE FROM LS_DATA_RAW
   WHERE RAW_SN = :rawSn
     AND ORGNL_RAW_SN IS NOT NULL
     AND DATA_STTS_CD = 'FAILED'
     AND NOT EXISTS (SELECT 1 FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn)
  ```
  4조건 중 **②`DE_IDENT_YN <> 'Y'` 가 없다.** 그 조건은 Java 선검사(`ResolutionPersistService.java:246-249`)에만 존재한다.
- **재현/확인 경로**: `grep -n "deleteFailedDerivative" -A10 backend/src/main/java/kr/co/cudo/authoring/video/repository/VideoRepository.java`
- **영향**: 현실적 위험은 낮다 — ①선검사와 DELETE 가 같은 `REQUIRES_NEW` 트랜잭션 안에서 `findByRawSnForUpdate` 잠금을 쥔 채 연속 실행되고 ②확정 경로(`persist:138-139`)가 `markDeidentified('Y')` 와 `markCompleted()` 를 항상 함께 수행하므로 `'Y'` 인 행은 `DATA_STTS_CD='FAILED'` 조건에서 이미 걸러진다. 다만 향후 상태 전이가 분리되면 조용히 깨지는 형태의 결손이고, **javadoc 이 사실과 다르다**는 점이 남는다.
- **수정 방향(제안)**: DELETE 문에 `AND (DE_IDENT_YN IS NULL OR DE_IDENT_YN <> 'Y')` 를 추가하거나, javadoc 을 "SQL 은 3조건, `'Y'` 는 FAILED 조건이 함의" 로 정정한다. ⚠ **구현하지 않는다.**

### [E-ISSUE-43] TC-RESL-021 — 확정 실패한 파생은 **상태 조회 API 에서도 사라진다**(E-ISSUE-24 잔여)

- **심각도**: MEDIUM (관측성 — 1차 지적의 핵심 시나리오가 여전히 남음)
- **기대 동작(기대효과)**: 1차 E-ISSUE-24 의 요지는 "REVIEWER 가 파생 생성 성공/실패를 확인할 수 있어야 한다" 이고, TC-RESL-021 은 `DATA_STTS_CD=FAILED` → 조회 응답 `FAILED` 매핑을 기대한다.
- **현재 동작(이슈 내용)**:
  - 조회 API 는 신설됐고 `IN_PROGRESS`/`COMPLETED` 는 실동작으로 확인된다(`VideoResolutionService.statusOf:185-194`).
  - 그러나 **정상적인 실패 경로에서는 그 행이 남지 않는다.** `AsyncResolutionRunner.handleFailure:150-164` 가 `markRawDataFailed` 직후 `deleteFailedDerivativeRaw` 를 호출하고, 그 조건(파생·FAILED·프레임 0건)은 Phase A/B 실패의 **전형적 상태와 정확히 일치**한다(Phase C 는 같은 트랜잭션이라 프레임이 롤백된다). 결과적으로 `FAILED` 는 **cleanup 이 실패해 파일이 잔존한 경우에만** 조회된다(`:155-158`).
  - 코드가 이 트레이드오프를 스스로 인정한다 — `:115-118` "여기서부터는 파생 RAW·예약행이 정리되어 **실패 흔적이 DB 에 남지 않는다** … WARN 로그 + 메트릭이 유일한 관측 수단이다".
  - 즉 E-ISSUE-23(고아 누적 제거)과 E-ISSUE-24(실패 가시화)가 **서로를 상쇄**한다. 예약 단계 실패(중복 등)는 요청 응답에 `FAILED` 로 즉시 보이지만(실측), 그 응답을 놓치면 사후 조회 수단이 없다.
- **재현/확인 경로**: `POST /v1/videos/{approvedRawSn}/resolution` 로 확정 실패를 유발한 뒤 `GET /v1/videos/{rawSn}/resolution` → 해당 프리셋이 목록에 없음. 로그만 `[AsyncResolutionRunner] derivative discarded — finalize failed, reservation released rawSn=… dataAugSn=…` + 메트릭 `resolution.finalize.failed`.
- **영향**: REVIEWER 가 "3종 요청했는데 2종만 있다"를 화면에서 인지할 수 없다. 운영자는 로그/메트릭 접근이 필요하다. 보안 영향 없음.
- **수정 방향(제안)**: ①고아 RAW 를 삭제하는 대신 **경량 실패 이력 테이블**(또는 `LS_BATCH_PROC_LOG` 재사용)에 `(parentRawSn, preset, 사유코드, 시각)` 을 남기고 조회 API 가 그 이력을 합쳐 응답하거나, ②`GET /v1/videos/{rawSn}/resolution` 응답에 "요청됐으나 산출물 없음" 을 표현할 수 있도록 프리셋 3종을 항상 나열하고 미존재를 `NOT_CREATED` 로 표기한다. ⚠ **구현하지 않는다.**

### [E-ISSUE-44] TC-RESL-001 — `VMS_CLIP_ID` 의 `_RESL_RESL_` 이중 접두 드리프트가 신규 생성분에도 그대로 남아 있다 (1차 E-ISSUE-25 미해소)

- **심각도**: LOW (데이터 품질 — 기능 영향 없음)
- **기대 동작(기대효과)**: 파생 식별자에 프리셋 코드가 1회만 들어간다.
- **현재 동작(이슈 내용)**: `video/entity/LsDataRaw.java:226`
  ```java
  raw.vmsClipId = parent.getVmsClipId() + "_RESL_" + goalResCd + "_" + System.currentTimeMillis();
  ```
  `goalResCd` 가 이미 `RESL_720P` 이므로 결과가 `…_RESL_RESL_720P_…` 가 된다. [실동작] 본 검증 신규 생성분 전건 — `DEV-CLIP-9502_RESL_RESL_720P_1785437782919`, `DEV-CLIP-9509_RESL_RESL_480P_…`.
- **재현/확인 경로**: `select vms_clip_id from ls_data_raw where orgnl_raw_sn is not null and vms_clip_id like '%RESL%';`
- **영향**: 기능 영향은 **없다** — `AugTypeParser`(`video/util/AugTypeParser.java:64-80`)가 "가장 오른쪽 마커 뒤 토큰" 규칙으로 이중/구형(`_RES_RES_`) 접두를 모두 정규화하고, 회귀 테스트(`AugmentResolutionResultTest:217/:244`)도 있다. 남는 것은 ①식별자 가독성 ②`VMS_CLIP_ID` 를 사람이 읽는 감사·운영 문서에서의 혼란 ③파서를 계속 유지해야 하는 부채다.
- **수정 방향(제안)**: 접두를 `"_"` 로 바꾸거나(신규 생성분만 정상화, 파서는 하위호환용으로 존치), 파생 종류 판별을 `LS_DATA_AUG.AUG_TYPE_CD` 조인으로 일원화해 문자열 파싱을 폐지한다. ⚠ **구현하지 않는다.**

### [E-ISSUE-45] TC-RESL-064/065/067/068 — 확정·정리 가드 4종에 전용 테스트가 없다 (1차 E-ISSUE-29 의 잔여 축)

- **심각도**: LOW (검증 신뢰도)
- **기대 동작(기대효과)**: 상태 전이·정리 가드마다 실행 경로를 고정하는 테스트가 있어야 회귀가 감지된다. 1차 E-ISSUE-29 가 지목한 3건(procLog 부재 404 / 중복 videoFrameNo / mtime 교체)은 이번 회차에 **전부 신설·해소**됐다(`ResolutionSnapshotServiceTest:238,:256` · `ResolutionPersistServiceTest:252`).
- **현재 동작(이슈 내용)**: 아래 4건은 여전히 단언하는 테스트가 없다.
  1. **TC-RESL-064** `LsDataAug.buildResolution:227-231` 의 `RESL_` 접두 400 — 테스트에서 팩토리는 정상값으로만 호출된다(`grep createResolutionPending` 결과 12곳 전부 `AUG_RESL_720P`).
  2. **TC-RESL-065** `LsDataAug.markResolutionGenerated:253-256` 의 이중 전이 409 — `@DisplayName` 전수 스캔 결과 대응 테스트 0건.
  3. **TC-RESL-067** `AsyncResolutionRunner:155-158` "파일 잔존 시 RAW 보존" — `AsyncResolutionRunnerTest` 에 `deleteFailedDerivativeRaw` 를 `verify`/`never()` 로 단언하는 케이스가 **0건**(`:128` 은 `cleanupFailed` 메트릭만 본다).
  4. **TC-RESL-068** `ResolutionFileMaterializer:119-138` 레거시 raw base 정리 — `ResolutionFileMaterializerTest` 가 `storageRawPath` 를 주입하지 않아 이 블록이 **항상 `IllegalStateException` 으로 skip** 된다(테스트에서 한 번도 실행되지 않는다).
- **재현/확인 경로**:
  ```bash
  grep -rn "deleteFailedDerivativeRaw" backend/src/test/java/kr/co/cudo/authoring/video/AsyncResolutionRunnerTest.java   # 0건
  grep -rn "storageRawPath" backend/src/test/java/kr/co/cudo/authoring/video/ResolutionFileMaterializerTest.java          # 0건
  grep -rn "markResolutionGenerated" backend/src/test/java/kr/co/cudo/authoring                                           # 단언 0건
  ```
- **영향**: 결함이 CI 를 통과한다. 특히 ③은 "고아 파일 방지"라는 안전 불변식이고 ④는 원본 저장소에 PII 사본이 잔존하는 것을 막는 정리 로직이라, 회귀 시 조용히 무력화된다.
- **수정 방향(제안)**: 각각 ①비-RESL 코드 400 ②ACCEPTED 재전이 409 ③`cleanup=false` 일 때 `verify(persistService, never()).deleteFailedDerivativeRaw(any())` ④`storageRawPath` 주입 후 `{rawBase}/resolution/{newRawSn}` 삭제·`{rawBase}/resolution` 루트 보존 단언을 추가한다. ⚠ **구현하지 않는다.**

### [E-ISSUE-46] TC-RESL-044/045 — 파생 라벨이 AI 메타(`LS_DATA_LBL_AI_INFO`)·등록자를 승계하지 않아 오토라벨 산출물이 수동 라벨처럼 보인다

- **심각도**: LOW (데이터 계보 — 현재 export/뷰 소비 경로에는 영향 없음)
- **기대 동작(기대효과)**: 파생영상은 부모 라벨의 **복사본**이므로, 그 라벨이 AI 가 만든 것인지(자동여부·신뢰도·출처)가 함께 따라가야 계보가 유지된다. 같은 리포의 버전 롤백 정책은 이 점을 명시적으로 요구한다(CLAUDE.md — "`LBL_SN`·AI 메타(`AUTO_LBL_YN`/신뢰도/출처)·`TRCK_ID` 까지 보존 복원").
- **현재 동작(이슈 내용)**: `batch/entity/LsDataLbl.java:333-348 copyForNewSrcScaled` 가 복사하는 필드는 `srcSn / lblTypeCd / labelId / labelNm / pointCn / trackId` 6개뿐이다.
  - `LS_DATA_LBL_AI_INFO`(별도 테이블)는 어느 파생 경로에서도 복사되지 않는다. [실동작] 부모 156 의 `ls_data_lbl_ai_info` **7행** ↔ 파생 174 **0행**.
  - `REG_USER_NO` 도 빌더에 없어 파생 라벨은 전부 `null`(실측).
  - 증강 경로(`webhook/service/AugmentExtractPersist.java:125` → `LsDataLbl.copyForNewSrc:300-307`)도 **동일하게 미복사**라 두 파생 경로 사이의 드리프트는 아니다. `:297-298` 주석은 "Transient 필드(autoLblYn, confScore, lblSrcCd)는 DB 미저장이므로 복사 대상 아님" 이라고만 서술하고, 별도 테이블인 `LS_DATA_LBL_AI_INFO` 는 언급하지 않는다.
- **재현/확인 경로**:
  ```sql
  select 'parent' , count(*) from ls_data_lbl_ai_info i join ls_data_lbl l on l.lbl_sn=i.data_lbl_sn
    join ls_data_src d on d.src_sn=l.src_sn where d.raw_sn=156
  union all select 'deriv', count(*) from ls_data_lbl_ai_info i join ls_data_lbl l on l.lbl_sn=i.data_lbl_sn
    join ls_data_src d on d.src_sn=l.src_sn where d.raw_sn=174;    -- 7 vs 0
  ```
- **영향**: 현재 export JSON 빌더는 자동여부·신뢰도를 내보내지 않으므로(`grep autoLblYn|confScore backend/.../dataset/export/` 0건) **학습데이터 산출물에는 영향이 없다.** 남는 영향은 ①파생영상 라벨링 화면에서 AI 라벨/수동 라벨 구분 불가 ②품질 통계(오토라벨 비율)에서 파생분이 전량 수동으로 집계 ③향후 export 계약에 자동여부가 추가되면 조용히 오표기된다.
- **수정 방향(제안)**: 파생 라벨 복사 시 `LS_DATA_LBL_AI_INFO` 를 함께 복제(신뢰도·출처 유지, `LBL_SRC_CD` 는 그대로 두거나 `DERIVED` 파생값 추가)하고 `REG_USER_NO` 를 승계한다. 증강·해상도 두 경로가 같은 헬퍼를 쓰므로 한 곳만 고치면 된다. **또는** "파생 라벨은 계보를 승계하지 않는다"를 명시적 계약으로 문서화한다. ⚠ **구현하지 않는다.**
