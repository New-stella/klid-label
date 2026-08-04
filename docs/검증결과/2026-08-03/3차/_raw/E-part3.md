# E클러스터 part3 — E-4. 해상도 파생 오케스트레이션 (TC-RESL-001~021)

- 담당: `docs/test-cases/E-augment-resolution-export-meta.md` E-4절(21건, VideoResolutionService/VideoController)
- 스택: 재확인(`docker ps` 5개 healthy, 2026-08-03 재빌드 이미지) — `_raw/stack-bringup.md` 참조
- 데이터: `_raw/pipeline-drive.md` 의 rawSn=101(APPROVED, 비식별 완료 640x480) 재사용 + 기존 DB 잔존 영상(rawSn=8/9/15/115/145/900/901) 활용. 신규 파생 생성은 실제 API 호출(REVIEWER 토큰, userNo=1001)로 수행 — rawSn 154/155/156(101의 3종)·159/160(900의 1080p/480p)·161/162(115의 720p/480p) 생성됨(부작용, 원복 불필요 — 검증 목적 정상 데이터).
- 코드/설정/프로덕션 파일 수정 없음. 카탈로그(`E-augment-resolution-export-meta.md`) 는 담당 범위(E-4절) 안에서 TC-RESL-013 한 줄만 Edit로 보강(아래 "카탈로그 정정" 참조).
- DB 테스트용 임시 조작은 전부 시행 직후 원복함(rawSn=101 frm_no=0 경로 2회 임시변경 후 원복, rawSn=9 procLog 테스트행 삽입 후 삭제).

## 판정 요약

| 판정 | 건수 | ID |
|---|---|---|
| PASS | 19 | TC-RESL-001,002,003,004,005,006,007,008,009,010,011,014,015,016,017,018,019,020,021 |
| PARTIAL | 1 | TC-RESL-013 |
| FAIL | 1 | TC-RESL-012 |
| 합계 | 21 | |

## 결과 표

| ID | 판정 | 근거 확인 | 비고 |
|---|---|---|---|
| TC-RESL-001 | PASS | [실동작] `POST /v1/videos/101/resolution` body `{}` → 201, `derivatives` 3건 CREATED(rawSn 154/155/156, 1080p/720p/480p). 근거 `VideoResolutionService.java:92-141` 라인 정확 | |
| TC-RESL-002 | PASS | [실동작] rawSn=115 `{"presets":["RESL_720P","RESL_480P"]}` → 201 2건만(rawSn 161/162). `ResolutionChangeRequest.java:32-37` 정확 | |
| TC-RESL-003 | PASS | [실동작] 같은 요청에 `"RESL_720P"` 중복 포함 `{"RESL_720P","RESL_720P","RESL_480P"}` → distinct 2건만 생성(중복 무시). `resolvePresets` 의 `.distinct()` 확인. 근거 `:33-37` 정확 | |
| TC-RESL-004 | PASS | [정적+부분실동작] `VideoResolutionService.java:116-123` 루프의 `preset.width()==srcW && preset.height()==srcH` 스킵 로직 확인. rawSn=101(640x480)은 3종 어느 것과도 안 맞아 스킵 없이 3건 전부 생성됨을 실동작으로 확인(대칭 검증) — 정확히 일치하는 실 프레임(1920x1080/1280x720/854x480) 픽셀 fixture 는 구성 비용상 미실측, 코드 대조로 보완 | 라인 드리프트 없음 |
| TC-RESL-005 | PASS | [정적+부분실동작] `:126-129` `results.isEmpty()` → 400 확인. 3종 모두 생성된 rawSn=101 재요청(`{}`)은 사문화되지 않고 전건 시도 후 3건 FAILED(중복)로 `results` 는 비지 않아 이 케이스가 아니라 TC-RESL-006 경로로 빠짐(정상 — "전부 스킵"과 "전부 실패"는 다른 분기이며 카탈로그도 별도 케이스로 분리) | |
| TC-RESL-006 | PASS | [실동작] rawSn=101 재요청 `{"presets":[0]}`(=1080p, 이미 존재) → `HTTP 500 {"errorCode":"INTERNAL_ERROR","message":"요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."}`. `:133-139` 정확 | |
| TC-RESL-007 | PASS | [실동작] rawSn=900(`DE_IDNTF_YN='F'`, 산출물 실재) `{}` 요청 → 201, `[{rawSn:159,RESL_1080P,CREATED},{rawSn:null,RESL_720P,FAILED},{rawSn:160,RESL_480P,CREATED}]` — CREATED/FAILED 혼재 201 확인(720p 는 기존 파생 중복으로 실패). `:205-215` 정확 | |
| TC-RESL-008 | PASS | [실동작] rawSn=101 원본 640x480 → 1080p(1920x1080)·720p(1280x720)·480p(854x480) 전부 원본보다 큼(업스케일)에도 거부 없이 3건 생성됨(201). 구 `targetH>=srcH` 가드 실제로 없음 확인. `:106-123` 정확 | |
| TC-RESL-009 | PASS | [실동작] 생성된 파생 rawSn=154(`ORGNL_RAW_SN=101`)에 재요청 → `HTTP 400 {"errorCode":"INVALID_INPUT","message":"원본 영상에만 해상도 변경 가능"}`. `:230-232` 정확 | |
| TC-RESL-010 | PASS | [실동작] rawSn=145(검수 FAILED) → `HTTP 409 {"errorCode":"CONFLICT","message":"검수 완료(APPROVED)된 영상만 해상도 변경할 수 있습니다."}`. `:234-240` 정확 | |
| TC-RESL-011 | PASS | [실동작] rawSn=999999 → `HTTP 404`. `:225-227` 정확 | |
| TC-RESL-012 | **FAIL** | [실동작] rawSn=101 frm_no=0 의 `DE_IDNTF_SRC_FILE_PATH_NM` 을 임시로 mp4 경로(비이미지)로 변경 후 요청 → `HTTP 500 {"errorCode":"INTERNAL_ERROR","message":"프레임 이미지를 읽을 수 없습니다."}` (기대: 400 `"원본 프레임 해상도를 확인할 수 없습니다."`). 테스트 후 원본 경로로 즉시 원복. `Java2DImageResizer.readImage`(80-98행 부근) 가 `dim<=0` 반환 경로 자체가 없어 `VideoResolutionService.java:110-112` 의 400 분기가 여전히 사문화 코드임을 재확인 | **1차(2026-08-01) E-ISSUE-41 미해소 — 아래 이슈 참조** |
| TC-RESL-013 | PARTIAL | [실동작] 문자열 축(`["RESL_240P"]`,`["resl_720p"]`,`[""]`,`[true]`,`[{}]`) → 전부 `HTTP 400`(기대 일치). 그러나 **숫자 ordinal 축**(`[0]`,`[1]`) → Jackson 이 `RESL_1080P`/`RESL_720P` 로 정상 바인딩해 서비스 로직까지 진입(rawSn=101 재요청 시 중복이라 500으로 관측되지만 이는 "도달했다"는 증거). `[99]`(범위초과)만 400 | **1차 E-ISSUE-42 미해소 — 카탈로그 TC-RESL-013 행에 각주 보강(Edit 완료), 아래 이슈 참조** |
| TC-RESL-014 | PASS | [실동작] WORKER 토큰 → `HTTP 403 FORBIDDEN`("권한이 없습니다."), 토큰 없음 → `HTTP 401 UNAUTHORIZED`("인증이 필요합니다."). `VideoController.java:365-367` 정확 | |
| TC-RESL-015 | PASS | [실동작] 전 응답에서 `{derivatives:[{rawSn,goalResCd,targetW,targetH,status}]}` 형태만 확인, 내부 파일경로·EXPORT_SN 등 미노출 | |
| TC-RESL-016 | PASS | [실동작] rawSn=101 frm_no=0 경로를 `/etc/passwd` 로 임시 변경 후 요청 → `HTTP 400 {"errorCode":"INVALID_INPUT","message":"원본 프레임 경로가 허용된 저장 경로를 벗어납니다."}`(경로 원문 미노출). 즉시 원복. `:262-274` 정확 | |
| TC-RESL-017 | PASS | [실동작] 위 TC-RESL-001/007 등 전 요청이 `de_idntf_src_file_path_nm`(비식별, `/app/storage/deidentified/frames/deid/...` 절대경로)을 그대로 사용해 통과함 — deid base 허용 확인. `:266-270` 정확 | |
| TC-RESL-018 | PASS | [실동작] rawSn=8/9/15(`DE_IDNTF_YN='F'`, `ls_deident_proc_log` 에 SUCCESS 행 없음 — 비식별 API 실패형) 3건 모두 `HTTP 409 {"errorCode":"CONFLICT","message":"원본의 비식별 산출물을 찾을 수 없어 해상도 파생을 만들 수 없습니다."}`. `ParentDeidArtifactGuard.java:65-94`, 호출부 `VideoResolutionService.java:100` 정확 | |
| TC-RESL-019 | PASS | [실동작] rawSn=900(`DE_IDNTF_YN='F'`, procLog SUCCESS 행 존재 + 실제 파일 존재) → 201 정상 생성(TC-RESL-007 항목과 동일 응답). `'F'` 만으로 차단되지 않고 산출물 실재 여부로만 판정됨을 확인. `ParentDeidArtifactGuard.java:70-93` 정확 | |
| TC-RESL-020 | PASS | [실동작] rawSn=9 에 `ls_deident_proc_log` SUCCESS 행을 임시 삽입(`de_idntf_file_path_nm='/etc/passwd'`) 후 요청 → `HTTP 400 {"errorCode":"INVALID_INPUT","message":"경로가 허용된 비식별 저장 경로를 벗어납니다."}`(경로 원문 미노출). 테스트 후 삽입 행 즉시 삭제. `ParentDeidArtifactGuard.java:100-124` 정확 | |
| TC-RESL-021 | PASS | [실동작] 15초 대기 후 `GET /v1/videos/101/resolution` → `HTTP 200 {"derivatives":[{rawSn:154,...,COMPLETED},{rawSn:155,...,COMPLETED},{rawSn:156,...,COMPLETED}]}` — 비동기 확정 완료 반영 확인. `:153-200`, `VideoController.java:392-397` 정확 | |

## 근거 드리프트

**없음** — E-4절 21건 전부 `file:line` 을 현재 소스와 대조한 결과 정확히 일치했다(2026-08-03 카탈로그 갱신 changelog 3회차의 "E-4/E-5 는 리팩터 후에도 근거가 정확했다" 서술과 부합).

## 카탈로그 정정 (Edit 1건, 담당 범위 내)

- `TC-RESL-013` 행에 각주 보강: 문자열 축은 400 정상이나 숫자 ordinal(`[0]`/`[1]`/`[2]`) 은 Jackson 기본 동작으로 통과해 서비스 로직까지 진입하는 미해소 갭(1차 E-ISSUE-42)을 명시. 라인 드리프트는 없었고(기존 근거 `ResolutionPreset.java` 그대로 정확), 내용 보강만 수행.

## 이전 회차(2026-08-01 1차) 이슈 대조

| 1차 이슈 | 케이스 | 상태 |
|---|---|---|
| E-ISSUE-41 | TC-RESL-012 | **미해소 — 이월** (재현 동일, 코드 변경 없음 확인) |
| E-ISSUE-42 | TC-RESL-013 | **미해소 — 이월** (재현 동일, 코드 변경 없음 확인) |

E-4절 범위(TC-RESL-001~021) 내 1차 이슈는 이 2건뿐이며 둘 다 재현됨. (TC-RESL-030 이후 E-5절의 E-ISSUE-61~65 는 본 담당 범위 밖)

---

### [E-ISSUE-41] TC-RESL-012 — 프레임 해상도 실측 실패가 400 이 아니라 500 으로 나가고, 400 가드는 도달 불가 사문화 (1차 E-ISSUE-41 이월, 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 원본 프레임 이미지가 손상/미지원 포맷/부재라 해상도를 확인할 수 없으면 **400 INVALID_INPUT `"원본 프레임 해상도를 확인할 수 없습니다."`** 로 응답해야 한다. 서버 장애가 아니라 요청 대상 데이터 상태의 전제 불충족이므로 4xx 여야 FE 가 "이 영상은 프레임이 깨져 해상도 변경 불가"로 안내할 수 있고 5xx 알람(운영 오탐)을 만들지 않는다.
- **현재 동작(이슈 내용)**: `VideoResolutionService.java:110-112`
  ```java
  int[] dim = measureFirstFrame(rawSn);
  int srcW = dim[0]; int srcH = dim[1];
  if (srcW <= 0 || srcH <= 0) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "원본 프레임 해상도를 확인할 수 없습니다.");
  }
  ```
  실측기 `Java2DImageResizer.readImage`(80-98행 부근)는 `ImageIO.read` 가 IOException 이거나 null 이면 **그 자리에서** `INTERNAL_ERROR("프레임 이미지를 읽을 수 없습니다.")` 를 던진다 — `dim<=0` 을 반환하는 경로가 없다. 따라서 위 400 분기는 어떤 입력으로도 도달 불가능한 사문화 코드다.
  2026-08-03 3차 재실증(rawSn=101, frm_no=0 의 `de_idntf_src_file_path_nm` 을 mp4 경로로 임시 변경):
  ```
  HTTP/1.1 500
  {"success":false,"data":null,"message":"프레임 이미지를 읽을 수 없습니다.","errorCode":"INTERNAL_ERROR"}
  ```
  테스트 직후 원본 경로(`/app/storage/deidentified/frames/deid/101/frame-0.jpg`)로 원복함.
- **재현/확인 경로**:
  ```sql
  UPDATE ls_data_src SET de_idntf_src_file_path_nm='/app/storage/deidentified/videos/101/clip-9101-mask.mp4'
   WHERE src_sn=468; -- rawSn=101 frm_no=0 (허용 base 안, 확장자만 비이미지)
  ```
  ```bash
  curl -i -X POST http://localhost:18081/api/v1/videos/101/resolution \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' -d '{}'
  # → HTTP 500 "프레임 이미지를 읽을 수 없습니다."
  ```
- **영향**: 기능/운영. 데이터 상태 문제가 서버 오류로 분류되어 5xx 알람·SLO 오염, FE 표준 4xx/5xx 분기에서 "일시 장애"로 오안내, 회귀 테스트 커버 갭(400 분기가 원천적으로 테스트 불가). 보안 등급 아님(경로·스택 미노출 유지 확인됨).
- **수정 방향(제안)**: `Java2DImageResizer.readImage` 의 실패를 호출부에서 구분 가능하게 한다 — 포트 계약을 `Optional<int[]>` 로 넓히거나 실측 실패를 `INVALID_INPUT` 전용 예외로 승격해 `measureFirstFrame` 에서 400 메시지로 재던진다. `srcW<=0` 사문화 분기는 제거하거나 실제 도달 가능하게 배선하고 "손상 프레임 → 400" 테스트를 추가한다. (⚠ 구현은 하지 않는다)

---

### [E-ISSUE-42] TC-RESL-013 — `presets` enum 이 숫자(ordinal)로도 바인딩돼 문자열 화이트리스트 계약을 우회한다 (1차 E-ISSUE-42 이월, 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `presets` 원소는 `RESL_1080P`/`RESL_720P`/`RESL_480P` 세 문자열만 허용되고 그 외 값·형식은 Jackson 역직렬화 단계에서 400 으로 거부돼야 한다(`ResolutionChangeRequest.java` 의 명시 계약, "자유 입력 해상도 차단 CWE-20").
- **현재 동작(이슈 내용)**: Jackson 기본 동작상 JSON 정수는 enum ordinal 로 해석된다. 프로젝트에 `fail-on-numbers-for-enums` 류 하드닝 설정이 없고(`application*.yml` 에 `spring.jackson` 블록 없음) `ResolutionPreset` 에 `@JsonCreator` 문자열 전용 팩토리도 없다.
  2026-08-03 3차 재실증(rawSn=101, 이미 3종 파생 존재하는 상태에서):
  ```
  {"presets":[0]}   -> HTTP 500 "요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."  ← 역직렬화 통과 + createOne 시도까지 도달(중복이라 실패로 관측되나 "도달했다"는 것 자체가 증거)
  {"presets":[99]}  -> HTTP 400 "요청 본문이 올바르지 않습니다." (ordinal 범위초과만 거부)
  {"presets":["RESL_240P"]} -> HTTP 400 (문자열 축은 정상)
  ```
- **재현/확인 경로**:
  ```bash
  curl -i -X POST http://localhost:18081/api/v1/videos/{approvedRawSn}/resolution \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' -d '{"presets":[2]}'
  # → 파생이 없는 원본이면 RESL_480P 파생이 실제로 생성됨(문서화되지 않은 표현)
  ```
- **영향**: 입력검증/계약(CWE-20). 값 공간이 넓어지지는 않으므로(0~2=동일 3개 프리셋) 권한상승·자유해상도 주입은 아니다. 실질 위험: ①계약 밖 표현이 허용돼 OpenAPI·연동규격과 실제 수용 입력이 어긋남 ②순서 의존 취약 — 향후 `ResolutionPreset` 에 상수를 앞/중간에 추가하면 기존 숫자 페이로드가 조용히 다른 프리셋으로 재매핑(무증상 데이터 오류) ③"enum 화이트리스트 강제" 보안 주장이 부분적으로만 성립.
- **수정 방향(제안)**: 전역 `spring.jackson.deserialization.fail-on-numbers-for-enums: true`(영향범위 넓어 전 DTO 회귀 확인 필요) 또는 국소적으로 `ResolutionPreset` 에 `@JsonCreator` 문자열 전용 팩토리(미지값 → `IllegalArgumentException`→400)를 둔다. `{"presets":[0]}` → 400 케이스를 테스트에 추가한다. (⚠ 구현은 하지 않는다)
