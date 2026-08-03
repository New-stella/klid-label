# F 클러스터 — part4 검증 결과 (F-5 포털 이미지 업로드 · F-7 포털 업로드 라벨 CRUD)

- **대상**: `docs/test-cases/F-portal.md` §F-5 (TC-PORTALUP-001~018, 18건) + §F-7 (TC-PORTALUP-040~058, 19건) = **37건**
- **검증일**: 2026-08-02 / 2차
- **환경**: backend `http://localhost:18081/api` (docker `klid-backend`, profile=local) · DB `docker exec klid-postgres psql -U klid_user -d klid_system` (스키마 `public`)
- **인증**: `JWT_SECRET`(컨테이너 실효값) 으로 HS256 토큰 자체 발급 — `{sub, iss:klid-auth, channel:PORTAL, exp}` → `Role.PORTAL_USER` 고정 경로(`JwtAuthenticationFilter:94-99`). 테스트 사용자 `portal-qa-1` / `portal-qa-2` / `portal-other` / `portal-rl-1`
- **판정 요약**: PASS **32** · FAIL **1** · PARTIAL **4** · BLOCKED 0 · N/A 0 · 확인필요 0
- **신규 이슈**: F-ISSUE-61 ~ F-ISSUE-64 (4건)

> ⚠ 본 검증은 읽기 전용 원칙을 지켰다. 코드·설정·테스트 파일은 **일절 수정하지 않았다.** 실동작 확인을 위해 **DB 데이터 조작**(상태 플립·파일경로 조작·임시 CHECK 제약 추가 후 즉시 DROP)과 **HTTP 요청**만 수행했다.

---

## 1. F-5. 포털 이미지 업로드 (18건)

| ID | 판정 | 근거 확인 | 실측 요약 |
|----|:--:|------|------|
| TC-PORTALUP-001 | PASS | [실동작] | `POST /v1/portal/uploads/images` (ok.jpg + ok.png) → **201**, 응답 `uldSttsCd=READY`·`frmeCnt=1`·`frmeSn` 각 1건. DB `ls_portal_uld` 2행 + `ls_portal_uld_frme` 2행, `file_path_nm=/app/storage/raw/portal/images/{UUID}.jpeg\|.png` |
| TC-PORTALUP-002 | PASS | [실동작] | 51장 → **400** `"요청당 최대 50 개까지 업로드할 수 있습니다."` (`PortalUploadService.java:90-93`) |
| TC-PORTALUP-003 | PASS | [실동작] | 정확히 50장 → **201**, `data` 배열 50건. DB 행수 4 → 54 (경계 통과) |
| TC-PORTALUP-004 | **PARTIAL** | [실동작] | 빈 **파일**(0바이트 파트) → 400 정상. 그러나 `files` **파트 자체 부재** → **500 INTERNAL_ERROR** (`MissingServletRequestPartException` 미매핑, 서버 로그 `unhandled exception` + 스택트레이스). → **F-ISSUE-61** |
| TC-PORTALUP-005 | PASS | [실동작] | 20,971,521B(=20MB+1) JPEG → **400** `"이미지 크기가 허용 한도를 초과했습니다."` (서비스 검증 `PortalUploadService.java:300-303`, `PortalUploadProperties.java:40` `maxImageSizeBytes=20971520`) |
| TC-PORTALUP-006 | PASS | [실동작] | 정확히 20,971,520B → **201** (`fileSz=20971520`). 경계 포함(`>` 비교) 확인 |
| TC-PORTALUP-007 | PASS | [실동작]+[정적] | local 프로파일에서 22MB → **400**(파싱 통과 후 서비스 20MB 검증) — 카탈로그의 프로파일 구분 서술과 일치. prd 21MB 상한은 `application-prd.yml:36-39` + 회귀가드 `ConfigProfileDriftGuardTest.java:42-70`(`prdMultipartLimitsStayTight`) 로 고정. `MaxUploadSizeExceededException → PAYLOAD_TOO_LARGE(413)` 매핑 존재(`GlobalExceptionHandler.java:234-238`, `ErrorCode.java:24`) |
| TC-PORTALUP-008 | PASS | [정적] | 공통 `application.yml:28-29` = 500MB/1200MB, prd override `application-prd.yml:38-39` = 21MB/1100MB. 동일 가드 테스트가 비운영 3종(local/dev/stg)이 공통 상속임도 단언 |
| TC-PORTALUP-009 | PASS | [실동작] | `.gif` → **400** `"허용되지 않는 확장자입니다. 허용: [jpg, jpeg, png]"` (`PortalUploadService.java:306-309`). 대문자 `.JPG` 는 소문자 정규화되어 201(정상) |
| TC-PORTALUP-010 | PASS | [실동작] | 텍스트 내용 + `.jpg` → **400** `"지원하지 않는 이미지 형식입니다(JPEG/PNG 만 허용)."` (`:310-315`). `GIF89a` 내용 + `.jpg` 도 동일 거부 |
| TC-PORTALUP-011 | PASS | [실동작] | JPEG 시그니처 + `.png` → **400** `"확장자와 실제 이미지 형식이 일치하지 않습니다."` (`:316-320`) |
| TC-PORTALUP-012 | PASS | [실동작] | EOI(FF D9) 없는 JPEG → **400** `"손상되었거나 완전하지 않은 이미지 파일입니다."` (`:321-327`). ⚠ 한계: `FF D8 FF D9` 4바이트 파일은 통과(201) — SOI+EOI 저비용 검사의 설계상 한계이며 전체 디코드를 하지 않는 정책의 귀결. 서빙이 `image/jpeg + nosniff` 라 스크립트 실행 벡터는 아님(FFD8FF…`<script>`…FFD9 폴리글롯 업로드 후 서빙 헤더 실측: `Content-Type: image/jpeg`·`X-Content-Type-Options: nosniff`·`Content-Disposition: inline`) |
| TC-PORTALUP-013 | PASS | [실동작] | 49장 정상 + 1장 위조 → **400**, `data[0]="[50] fake.jpg: 지원하지 않는 이미지 형식입니다"`. DB 행수 불변(4→4), 디스크 파일 증가 0. 검증(`:95-109`)이 write 루프(`:115-137`) **이전**에 전량 수행됨 |
| TC-PORTALUP-014 | **FAIL** | [실동작] | 프레임 INSERT 를 커밋 시점에 실패시키자(임시 `CHECK (frme_no <> 0) NOT VALID`) → **500 + DB 행 0건(정상 롤백)** 인데 **디스크 파일은 68→69 로 잔존**. `LsPortalUldFrme` 는 `GenerationType.SEQUENCE` 라 `save()` 가 INSERT 를 지연시켜 실패가 **커밋(flush) 시점** = `uploadImages` catch 블록 **바깥**에서 발생 → `rollbackFiles()` 미실행. → **F-ISSUE-62** |
| TC-PORTALUP-015 | **PARTIAL** | [실동작]+[정적] | 선행 파일 롤백은 성립(단위테스트 `다건_배치중_후행_write실패시_선행파일도_롤백`). 그러나 **실패한 그 파일의 부분 기록분**은 보상되지 않는다 — `writtenThisRequest.add(dst)` 가 `writeToDisk()` **뒤**(`:122-123`)라 copy 도중 IOException 시 dst 가 목록에 없다. JDK 실측(`Files.copy(InputStream,Path)` 중간 IOException → target 존재, size=100) 로 부분 파일 잔존 확인. → **F-ISSUE-63** |
| TC-PORTALUP-016 | PASS | [실동작] | 파일명 `../../../../etc/evil.jpg` 업로드 → 201, DB `orgnl_file_nm` 은 원문 보존(표시용), `file_path_nm=/app/storage/raw/portal/images/3d6e5df1-….jpeg`. 디스크 실측: `portal/images/` 하위에 UUID 파일만 존재, base 밖 생성 0 |
| TC-PORTALUP-017 | PASS | [실동작] | 304자 파일명 업로드 → 응답 `orgnlFileNm` 길이 **255**, DB `length(orgnl_file_nm)=255` (`:454-459`) |
| TC-PORTALUP-018 | PASS | [실동작] | 신규 사용자로 62회 연속 업로드 → 1~60회 400(검증 실패, permit 소비됨) · **61·62회 429**. `portalUpload` config `limit-for-period=60 / 1m / timeout=0`(`application.yml:692-695`), permit 획득이 서비스 진입 **전**(`PortalUploadController.java:83,188-196`). ⚠ 관찰: `RateLimiterRegistry` 에 per-user 리미터가 무기한 누적(TTL·eviction 없음) — 사용자 수 증가 시 메모리 누적 소지(이번 케이스 기대결과 밖이라 이슈 미발행) |

## 2. F-7. 포털 업로드 라벨 CRUD (19건)

| ID | 판정 | 근거 확인 | 실측 요약 |
|----|:--:|------|------|
| TC-PORTALUP-040 | PASS | [실동작] | `PUT /frames/1/labels` [BBOX,POLYGON] → 200 2건 저장 → `[]` PUT → 200 `data=[]` → `GET` 0건. 동일 본문 재PUT 멱등(건수 동일, PK만 재발급). 본문 미첨부(Content-Length:0)도 전체 삭제로 동작(`@RequestBody(required=false)` → `List.of()`) |
| TC-PORTALUP-041 | PASS | [실동작] | 위조 1건 포함 PUT → 400 후 `GET labels` 가 **기존 2건 그대로** 반환. 검증(`:99-107`)이 `deleteAllBy…`(`:122`) 이전에 완료 |
| TC-PORTALUP-042 | PASS | [실동작] | `lblTypeCd=SEGMENT` → **400** `"허용되지 않는 lblTypeCd 입니다. 허용: BBOX, POLYGON"` (`:265-270`, fail-closed) |
| TC-PORTALUP-043 | PASS | [실동작] | 501건 → **400**(`replaceLabels.labels:` = 컨트롤러 `@Size` 1차, `PortalUploadLabelController.java:63`) / 500건 → **200**(경계 통과). 서비스 이중 강제(`PortalUploadLabelService.java:100-103`) 존재 |
| TC-PORTALUP-044 | PASS | [실동작] | BBOX 3점 → **400** `"BBOX 는 정확히 2 점이어야 합니다."` (`:281-284`) |
| TC-PORTALUP-045 | PASS | [실동작] | POLYGON 2점 → 400 / 201점 → 400 / **3점·200점 → 200**(양 경계 포함, `:285-290`) |
| TC-PORTALUP-046 | PASS | [실동작] | `"Infinity"` 문자열 좌표 → **400** `"좌표는 유한한 숫자여야 합니다."`(`:300-303`), 비표준 JSON 리터럴 `NaN` → **400**(Jackson 파싱 거부) |
| TC-PORTALUP-047 | PASS | [실동작] | label 81자 → **400** (`@Size(80)` 컨트롤러 1차 + 서비스 `:271-275` 이중) |
| TC-PORTALUP-048 | PASS | [실동작] | 자산 상태 `FAILED` → **409** `"라벨링 가능한(READY) 자산이 아닙니다. 현재 상태: FAILED"`, `PROCESSING` → **409** (`:116-119`). 프레임 락 획득 **후** 판정이라 상태 경합 창 없음 |
| TC-PORTALUP-049 | PASS | [실동작] | 동일 프레임에 3종 페이로드(3·5·7건)를 **18병렬** PUT → 전건 200, 최종 DB 상태 `k7 × 7행` **단일 요청 집합**(혼합·중복·유실 0). `@Lock(PESSIMISTIC_WRITE)` `findByUldFrmeSnAndOwnerForUpdate`(`LsPortalUldFrmeRepository.java:42-47`) 로 직렬화 |
| TC-PORTALUP-050 | PASS | [실동작] | 타 사용자 토큰으로 `PUT /frames/1/labels` → **403** `"본인 자산이 아니거나 존재하지 않습니다."`(부재와 동일 메시지 — 자원 열거 차단) |
| TC-PORTALUP-051 | **PARTIAL** | [실동작] | 정규 경로 2.8MB/12MB 본문 → **413** 즉시(0.0017s, 파싱 전). 그러나 경로를 퍼센트 인코딩(`/frames/1/%6Cabels`)하면 필터가 스킵되고 **핸들러에 도달**(200) → 12MB 본문이 전량 파싱된 뒤 400(0.21s). pre-parse 상한 **우회 가능**. → **F-ISSUE-64** |
| TC-PORTALUP-052 | **PARTIAL** | [실동작] | 정규 경로 chunked → **411** 정상. 인코딩 경로(`%6Cabels`) + chunked → **200**(411 미적용). 동일 근본원인. → **F-ISSUE-64** |
| TC-PORTALUP-053 | PASS | [실동작] | 다른 PUT 경로(`PUT /v1/videos/1/environment-meta`) 에 chunked·2.8MB 를 보내도 411/413 이 아니라 **403**(필터 스킵) · 같은 경로에 **POST** → 405(메서드 필터) · `/labels/x` 추가 세그먼트 → 404(정규식 미매치). 경로·메서드 스코핑은 설계대로 동작 |
| TC-PORTALUP-054 | PASS | [실동작] | 타 사용자 프레임 `GET labels` → **403** / 부재 프레임(999999) → **403**(부재·타인 응답 동일) |
| TC-PORTALUP-055 | PASS | [실동작]+[정적] | `GET /1/export` → 200, `Content-Disposition: attachment; filename="portal-upload-1-labels.json"`(서버 고정명) + `nosniff` + `application/json`. 본문 키 `uldSn·uldTypeCd·orgnlFileNm·fileSz·mimeTypeNm·uldSttsCd·frmeCnt·regDt·frames`, 프레임별 라벨 중첩. N+1 회피 = 업로드 단위 일괄 조회 후 그룹핑(`:163-169`) |
| TC-PORTALUP-056 | PASS | [실동작] | `orgnl_file_nm` 에 CR/LF·`"`·`\`·한글을 주입한 뒤 `GET /1/file` → 응답 헤더 `Content-Disposition: attachment; filename="download.jpg"; filename*=UTF-8''evil%20Set-Cookie%3A%20x%3D1%3Bquote%EA%B0%80.jpg`. **개행 제거 → 헤더 분할 없음**, 주입 시도한 `Set-Cookie` 헤더 미생성(CWE-113 차단) |
| TC-PORTALUP-057 | PASS | [실동작] | `file_path_nm=null` → **404** / base 내 부재 파일 → **404** / `/etc/passwd`(base 밖) → **403** `"허용되지 않은 경로입니다."` (`:217-228`, `resolveSafe` `:398-407`) |
| TC-PORTALUP-058 | PASS | [실동작] | 타 사용자 토큰 `GET /1/export` → **403**, `GET /1/file` → **403** |

---

## 3. 근거 드리프트 (카탈로그 file:line ↔ 실제)

> F-7 헤더의 *"PortalUploadLabelService.java·PortalUploadLabelController.java 모두 2026-07-25 이후 무변경 — 라인 재확인만"* 주석과 달리 **서비스 라인이 전반적으로 +1 ~ +15 밀려 있다.** 컨트롤러(`:63`)와 Properties(`:40`)만 정확.

| TC | 카탈로그 | 실제 |
|----|------|------|
| 001 | PortalUploadService.java:82-151 | 83-152 |
| 002·003 | :89-92 | 90-93 |
| 004 | :86-88 | 87-89 |
| 005·006 | :274-277 / :274 | 300-303 / 300 |
| 009 | :278-283 | 306-309 |
| 010 | :284-289 | 310-315 |
| 011 | :290-294 | 316-320 |
| 012 | :295-301 | 321-327 |
| 013 | :94-108 | 95-109 |
| 014 | :137-146 | 133-134(frme save) · 138-147(catch) |
| 015 | :339-359 | 365-372(writeToDisk) · 374-385(rollbackFiles) |
| 017 | :428-433 | 454-459 |
| 018 | PortalUploadController.java:157-165 | 188-196 |
| 040 | PortalUploadLabelService.java:92-132 | 93-133 |
| 041 | :98-106 | 99-107 |
| 042 | :250-255 | 265-270 |
| 044 | :266-269 | 281-284 |
| 045 | :270-275 | 285-290 |
| 046 | :285-288 | 300-303 |
| 047 | :257-260 | 271-275 |
| 048 | :115-118 | 116-119 |
| 049·050 | :108-109 / :109-110 | 110-111 |
| 051 | PortalLabelBodySizeFilter.java:73-78 | 70-75 |
| 052 | :66-72 | 62-69 |
| 053 | :54-60 | 52-57 |
| 054 | :137-143 | 138-144 |
| 055 | :152-201 | 153-202 |
| 056 | :321-345 | 336-344(attachmentDisposition) · 347-360(sanitizeFileName) |
| 057 | :216-227 | 217-228 |
| 058 | :154-156 | 155-157 |

## 4. 기존 자동테스트 커버 대조 (`_raw/test-baseline.md` 2026-08-01 = BE 4,755 tests / 실패 0)

| 영역 | 파일 | 커버 |
|------|------|------|
| F-5 | `PortalUploadServiceTest`(23) · `PortalUploadControllerTest`(21) · `PortalUploadControllerRateLimitTest`(1) | 002·004(서비스 레벨)·005·009~013·014(모킹)·015(부분)·016·018 커버. **003(정확히 50장)·006(정확히 20MB) 경계 테스트 없음**, **004 의 HTTP 파트 부재 경로 테스트 없음**(F-ISSUE-61 이 그래서 자동테스트에 안 잡혔다) |
| F-7 | `PortalUploadLabelServiceTest`(32) · `PortalUploadLabelControllerTest`(9) · `PortalUploadLabelConcurrencyIT`(1) · `PortalLabelBodySizeFilterTest`(6) | 040~050·054~058 폭넓게 커버. **051/052 는 정규 경로만 테스트** — 퍼센트 인코딩 우회 케이스 부재(F-ISSUE-64 미검출 원인) |

---

## 5. 이슈

### [F-ISSUE-61] TC-PORTALUP-004 — `files` 파트 부재 업로드가 400 이 아니라 500(스택트레이스 로깅)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 업로드할 이미지가 없는 요청은 **클라이언트 입력 오류(400 INVALID_INPUT)** 로 거부돼야 한다. 5xx 로 나가면 ①클라이언트가 재시도 가능한 서버 장애로 오인하고 ②`GlobalExceptionHandler` 최종 `Exception` 핸들러가 **ERROR + 스택트레이스**를 남겨 내부 프레임워크/필터 체인이 로그에 노출된다(CWE-209). 모니터링상 5xx 알람도 오염된다.
- **현재 동작(이슈 내용)**: `MissingServletRequestPartException` 이 `GlobalExceptionHandler` 에 매핑돼 있지 않다. 형제 클래스인 `MissingServletRequestParameterException` 만 400 으로 매핑돼 있어 누락이 눈에 띄지 않는다.
  - `backend/src/main/java/kr/co/cudo/authoring/common/exception/GlobalExceptionHandler.java:76-77`
    ```java
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
    ```
  - 실측 서버 로그: `ERROR ... GlobalExceptionHandler - [Exception] unhandled exception` + `org.springframework.web.multipart.support.MissingServletRequestPartException: Required part 'files' is not present.`
  - 서비스의 빈 목록 가드(`PortalUploadService.java:87-89`)는 **도달조차 하지 않는다**(컨트롤러 바인딩 단계에서 예외).
- **재현/확인 경로**:
  ```bash
  curl -s -w "\n%{http_code}\n" -H "Authorization: Bearer $PORTAL_JWT" \
       -X POST http://localhost:18081/api/v1/portal/uploads/images
  # → {"success":false,...,"errorCode":"INTERNAL_ERROR"} / 500
  # 참고: -F "files=" (빈 값) 도 동일하게 500
  ```
- **영향**: CWE-209(내부 정보 노출 — 스택트레이스 ERROR 로깅) + 오류 분류 오염(4xx→5xx). 인증 필요 경로라 외부 무인증 공격면은 아니나, PORTAL_USER 누구나 5xx 알람을 유발할 수 있다.
- **수정 방향(제안)**: `GlobalExceptionHandler` 에 `@ExceptionHandler(MissingServletRequestPartException.class)`(또는 상위 `ServletRequestBindingException`) 를 추가해 `ErrorCode.INVALID_INPUT`(400) 으로 매핑. 컨트롤러 테스트에 "files 파트 없는 업로드 → 400" 케이스 추가.

### [F-ISSUE-62] TC-PORTALUP-014 — 프레임 INSERT 가 커밋 시점에 실패하면 업로드 파일이 고아로 영구 잔존
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `uploadImages` 는 all-or-nothing 이다(클래스 Javadoc `#4 고아 파일 방지: write 후 DB INSERT 실패 시 이번 요청에 기록한 파일을 즉시 보상 삭제`). DB 가 롤백되면 디스크에도 아무것도 남지 않아야 한다. 남으면 ①소유 레코드 없는 **사용자 업로드 원본**이 스토리지에 무기한 잔존해 파기 요구(개인정보 보존기간)를 만족할 수 없고 ②어떤 스윕도 회수하지 않아 누적된다.
- **현재 동작(이슈 내용)**: `LsPortalUldFrme` 의 PK 전략이 `SEQUENCE` 라 `frmeRepository.save()` 는 INSERT 를 **큐에만 넣고** 실제 실행은 트랜잭션 커밋 시 flush 에서 일어난다. 그 실패는 `uploadImages` 의 try/catch **밖**이라 `rollbackFiles(writtenThisRequest)` 가 실행되지 않는다.
  - `PortalUploadService.java:133-147`
    ```java
    LsPortalUldFrme frme = frmeRepository.save(              // ← SEQUENCE: 실제 INSERT 는 커밋 시 flush
            LsPortalUldFrme.create(savedUld.getUldSn(), IMAGE_FRAME_NO, dst.toString()));
    ...
    } catch (Exception e) {
        rollbackFiles(writtenThisRequest);                    // ← 커밋 단계 실패에는 도달하지 않음
    ```
  - `LsPortalUldFrme.java:36-37` → `@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "lsPortalUldFrmeSeq")` (반면 `LsPortalUld.java:44-45` 는 IDENTITY 라 즉시 INSERT)
  - 단위테스트 `PortalUploadServiceTest#orphanFileRemovedOnDbFailure` 는 `frmeRepository.save()` 를 **모킹해 즉시 throw** 시키므로 이 지연 특성을 재현하지 못한다.
  - 회수 수단 부재: `PortalUploadSweepJob` 은 만료 TUS 임시파일과 `frames/{uldSn}/` 디렉터리만 정리하며 `portal/images/` 고아는 대상이 아니다(`PortalUploadSweepJob.java:67-113`).
- **재현/확인 경로** (실측 완료 — 임시 제약은 즉시 DROP 했음):
  ```sql
  ALTER TABLE ls_portal_uld_frme ADD CONSTRAINT qa_tmp_chk CHECK (frme_no <> 0) NOT VALID;
  ```
  ```bash
  docker exec klid-backend sh -c "ls /app/storage/raw/portal/images | wc -l"   # 68
  curl -s -w "\n%{http_code}\n" -H "Authorization: Bearer $PORTAL_JWT" \
       -F "files=@ok.jpg;filename=orphanprobe2.jpg" \
       http://localhost:18081/api/v1/portal/uploads/images                      # 500 INTERNAL_ERROR
  docker exec klid-backend sh -c "ls /app/storage/raw/portal/images | wc -l"   # 69  ← 고아 1건
  ```
  ```sql
  ALTER TABLE ls_portal_uld_frme DROP CONSTRAINT qa_tmp_chk;
  SELECT count(*) FROM ls_portal_uld WHERE orgnl_file_nm='orphanprobe2.jpg';   -- 0 (DB 는 정상 롤백)
  ```
  잔존 파일: `/app/storage/raw/portal/images/87b5ba03-6f4e-4640-af90-afa73e8ab352.jpeg`
- **영향**: CWE-459(Incomplete Cleanup) + 저장소 고갈(OWASP API4). 고아 파일은 **사용자 업로드 원본 이미지**이므로 소유·삭제 경로가 사라진 개인 자산이 남는다(CWE-359 인접).
- **수정 방향(제안)**: ①업로드 트랜잭션에 `TransactionSynchronization`(`afterCompletion(STATUS_ROLLED_BACK)`) 을 등록해 커밋 실패까지 포함한 보상 삭제를 걸거나 ②프레임 저장 직후 `saveAndFlush`(또는 `entityManager.flush()`)로 INSERT 실패를 메서드 내부로 끌어오거나 ③주기 스윕에 `portal/images/` ↔ `LS_PORTAL_ULD.FILE_PATH_NM` 대조 고아 회수를 추가. 아울러 단위테스트를 모킹 대신 실 DB(Testcontainers) 제약 위반으로 바꿔 지연 flush 를 재현할 것.

### [F-ISSUE-63] TC-PORTALUP-015 — write 도중 IOException 시 **실패한 그 파일의 부분 기록분**이 보상되지 않음
- **심각도**: LOW
- **기대 동작(기대효과)**: 디스크 고갈 등으로 저장이 실패하면 이번 요청이 만든 파일은 **하나도 남지 않아야** 한다(클래스 Javadoc `#5 디스크 고갈: 저장 도중 IOException 시 이번 요청 파일 전부 롤백 삭제`).
- **현재 동작(이슈 내용)**: 롤백 목록 등록이 write **성공 이후**라, copy 중간에 끊긴 파일은 목록에 없어 삭제되지 않는다.
  - `PortalUploadService.java:120-123`
    ```java
    Path dst = resolveSafe(baseDir, imagesDir.resolve(storedName));
    writeToDisk(v.file(), dst);          // ← 여기서 IOException 이면 dst 는 부분 기록된 채
    writtenThisRequest.add(dst);         // ← 이 줄에 도달하지 못해 롤백 목록에서 누락
    ```
  - JDK 동작 실측(`Files.copy(InputStream, Path)` 는 중간 실패 시 target 을 지우지 않음):
    ```
    copy failed: disk full
    target exists=true size=100
    ```
  - 기존 단위테스트 `laterWriteFailureRollsBackEarlierFiles` 의 테스트 더블은 `getInputStream()` 에서 던지므로 **target 파일이 아예 생성되지 않아** 이 경로를 검증하지 못한다.
  - F-ISSUE-62 와 마찬가지로 `portal/images/` 고아를 회수하는 스윕이 없다.
- **재현/확인 경로**: 저장 볼륨을 소진시키거나(예: 작은 tmpfs 를 `/app/storage/raw/portal/images` 에 마운트) `Files.copy` 중간 IOException 을 유발한 뒤 `ls /app/storage/raw/portal/images` 에 부분 파일 잔존을 확인. 코드 순서만으로도 결정적으로 성립한다.
- **영향**: CWE-459. 디스크가 이미 고갈된 상황에서 잔여물이 남아 회복을 더 어렵게 만든다(보안 영향은 낮음).
- **수정 방향(제안)**: `writtenThisRequest.add(dst)` 를 `writeToDisk` **호출 전**으로 옮기거나(rollback 이 `deleteIfExists` 라 미생성 파일에도 안전), `writeToDisk` 내부 catch 에서 `Files.deleteIfExists(dst)` 를 수행한 뒤 예외를 던진다.

### [F-ISSUE-64] TC-PORTALUP-051 / TC-PORTALUP-052 — 라벨 PUT 본문 상한 필터가 **URL 퍼센트 인코딩 경로로 우회**됨 (pre-parse DoS 재개통)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `PortalLabelBodySizeFilter` 는 이름 그대로 **Jackson 역직렬화 이전**에 본문 크기를 차단하기 위해 존재한다(클래스 Javadoc: *"대용량 페이로드로 파서 메모리를 소진시키는 pre-parse DoS(CWE-770 / OWASP API4)를 막지 못한다. 본 필터가 파싱 전에 상한을 적용한다"*). 라벨 PUT 핸들러에 **도달하는 모든 요청**이 이 상한을 통과해야 의미가 있다.
- **현재 동작(이슈 내용)**: 필터는 **디코딩되지 않은 원시 URI**(`getRequestURI()`)에 정규식을 적용하는데, Spring MVC 는 **디코딩된 경로**로 핸들러를 매핑한다. 두 해석이 어긋나 `labels` 의 한 글자만 퍼센트 인코딩해도 필터는 스킵되고 핸들러는 정상 실행된다(CWE-436 Interpretation Conflict).
  - `backend/src/main/java/kr/co/cudo/authoring/portal/config/PortalLabelBodySizeFilter.java:40-41, 56, 91-101`
    ```java
    private static final Pattern LABEL_PUT_PATH =
            Pattern.compile("^/v1/portal/uploads/frames/[^/]+/labels$");
    ...
    return !LABEL_PUT_PATH.matcher(pathWithinApp(request)).matches();
    ...
    private static String pathWithinApp(HttpServletRequest request) {
        String uri = request.getRequestURI();      // ← 퍼센트 인코딩이 살아있는 원시 URI
    ```
- **재현/확인 경로** (실측):
  ```bash
  # 12,960,000 바이트 JSON 준비
  # (1) 정규 경로 — 파싱 전 차단
  curl -s -o /dev/null -w "code=%{http_code} time=%{time_total}\n" -H "Authorization: Bearer $PORTAL_JWT" \
    -H "Content-Type: application/json" -X PUT --data-binary @huge.json \
    http://localhost:18081/api/v1/portal/uploads/frames/1/labels
  # → code=413 time=0.0017     (본문을 읽지도 않음)

  # (2) 'l' 만 %6C 로 인코딩 — 필터 스킵, 12MB 전량 파싱 후 거부
  curl -s -o /dev/null -w "code=%{http_code} time=%{time_total}\n" -H "Authorization: Bearer $PORTAL_JWT" \
    -H "Content-Type: application/json" -X PUT --data-binary @huge.json \
    http://localhost:18081/api/v1/portal/uploads/frames/1/%6Cabels
  # → code=400 time=0.21       (413 이 아님 = 상한 미적용)

  # (3) chunked 가드(411)도 동일하게 우회 — 인코딩 경로 + Transfer-Encoding: chunked → 200 (라벨이 실제로 교체됨)
  curl -s -w " HTTP:%{http_code}\n" -H "Authorization: Bearer $PORTAL_JWT" -H "Content-Type: application/json" \
    -H "Transfer-Encoding: chunked" -X PUT --data '[]' \
    http://localhost:18081/api/v1/portal/uploads/frames/1/%6Cabels
  # → {"success":true,"data":[]} HTTP:200
  ```
  인증/인가는 우회되지 않는다(무토큰 401 · 타 사용자 403 확인). 우회되는 것은 **본문 크기·chunked 가드뿐**이다.
- **영향**: CWE-436 / CWE-770 (OWASP API4:2023 Unrestricted Resource Consumption). 인증된 PORTAL_USER 가 임의 크기 JSON 을 Jackson 에 밀어 넣어 힙·CPU 를 소모시킬 수 있다(멀티 커넥션 병행 시 증폭). chunked 차단 우회는 Content-Length 조차 선언하지 않은 무제한 스트림을 허용한다. A 클러스터에서 확인된 "HMAC 필터 URL 인코딩 우회(CWE-436)" 와 **동일 계열의 반복 결함**이다.
- **수정 방향(제안)**: 필터의 경로 판정을 원시 URI 정규식에서 **정규화·디코딩된 경로 매칭**으로 교체한다 — Spring 이 이미 사용하는 `ServletRequestPathUtils.parseAndCache(request)` + `PathPattern`(또는 최소한 `UriUtils.decode(uri, UTF_8)` 후 매칭 + 중복 슬래시 정규화). 더 견고한 대안은 경로 판정을 없애고 **모든 요청에 대해** `HandlerInterceptor`(핸들러 확정 후) 또는 컨트롤러 진입 시점의 `ContentCachingRequestWrapper` 상한을 쓰는 것이다. 회귀 가드로 `PortalLabelBodySizeFilterTest` 에 퍼센트 인코딩 경로(`%6Cabels`, `%2Fabels` 등) 케이스를 추가할 것.

---

## 6. 검증 중 생성/변경한 테스트 데이터 (참고)

- `ls_portal_uld` 신규 행: `portal-qa-1`(uld_sn 1~4·5~54·76), `portal-qa-2`(74·75·78 등) — 이미지 자산
- 데이터 조작(원복하지 않은 항목): `uld_sn=1` 의 `orgnl_file_nm`(CRLF 주입 문자열, TC-056 근거) · `uld_sn=3` 의 `file_path_nm='/etc/passwd'`(TC-057 근거)
- 임시 DB 제약 `qa_tmp_chk` 는 **추가 직후 DROP 완료**(현재 미존재)
- 고아 파일 1건 존치(F-ISSUE-62 물증): `/app/storage/raw/portal/images/87b5ba03-6f4e-4640-af90-afa73e8ab352.jpeg`
