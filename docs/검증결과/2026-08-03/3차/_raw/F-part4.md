# F클러스터 part4 — F-5(포털 이미지 업로드) · F-7(포털 업로드 라벨 CRUD) · F-9(프레임 추출) · F-10/F-11(상태전이·파이프라인 분리)

- 담당: F-portal.md 113~135행(F-5), 154~179행(F-7), 219~end행(F-9, F-10, F-11) — 총 64건
- 스택: `docs/검증결과/2026-08-03/3차/_raw/stack-bringup.md`(재빌드 완료, HEAD e065da42) 기준 5개 컨테이너 healthy 상태에서 실동작 검증 수행
- baseline: `_raw/test-baseline.md` — backend 5203/5198 pass(0 fail), portal 관련 테스트 클래스 전량 존재·통과 확인
- 이전 회차(2026-08-02/2차) 이슈 대조: `grep -n '^### \[F-ISSUE-' ISSUES.md` 기준 F-ISSUE-61/62/63(F-5 범위)·F-ISSUE-64(F-7 범위) 4건이 내 담당 라인범위에 해당 — 전건 code+live 재확인함(아래 §해소여부)

## 판정 요약

| 섹션 | 케이스 수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---|---|---|---|---|---|---|
| F-5 (TC-PORTALUP-001~018) | 18 | 15 | 3 | 0 | 0 | 0 | 0 |
| F-7 (TC-PORTALUP-040~058) | 19 | 19 | 0 | 0 | 0 | 0 | 0 |
| F-9 (TC-PORTALUP-060~073) | 14 | 14 | 0 | 0 | 0 | 0 | 0 |
| F-10/F-11 (TC-PORTALUP-080~087, TC-PORTAL-090~094) | 13 | 13 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **64** | **61** | **3** | 0 | 0 | 0 | 0 |

---

## F-5. 포털 이미지 업로드 (파일 검증 · all-or-nothing · 경계)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-PORTALUP-001 | PASS | [정적] `PortalUploadService.uploadImages` 82-152행 — 검증→저장→INSERT→READY 흐름 코드 일치. 컨트롤러 테스트 `PortalUploadControllerTest` 다수 존재·baseline 통과 |
| TC-PORTALUP-002 | PASS | [정적] `properties.maxImagesPerRequest()` 기본 50, `PortalUploadService.java:90-93` `> 50` 시 400. `PortalUploadProperties.java:36`(`@DefaultValue("50")`) |
| TC-PORTALUP-003 | PASS | [정적] 상한 조건이 `>` 이므로 정확히 50장은 통과(경계). `PortalUploadControllerTest`에 상응 케이스 존재 |
| TC-PORTALUP-004 | **FAIL** | [실동작] 재확인 — **여전히 미해소**. 아래 이슈 F-ISSUE-61 참조 |
| TC-PORTALUP-005 | PASS | [정적] `maxImageSizeBytes` 기본 20971520(20MB), `validate()` 300-303행 `> maxImageSizeBytes` 400. 프로파일 무관 상수(레코드 필드) 확인 |
| TC-PORTALUP-006 | PASS | [정적] 조건이 `>`(초과만 거부)라 정확히 20MB는 통과 |
| TC-PORTALUP-007 | PASS | [정적] `application.yml`(공통 27-28행 `max-file-size: 500MB`) vs `application-prd.yml`(37-38행 `21MB`) — 라인 번호가 카탈로그(prd:37-39, 공통:14-30)와 근사 일치(±1~2행, 실질 드리프트 아님). prd만 override 구조 실측 확인 |
| TC-PORTALUP-008 | PASS | [정적] 동일 파일, `max-request-size` 공통 1200MB / prd 1100MB. `ConfigProfileDriftGuardTest` 존재(baseline 통과) |
| TC-PORTALUP-009 | PASS | [정적] `allowedImageExtensions`=[jpg,jpeg,png], `validate()` 304-309행 |
| TC-PORTALUP-010 | PASS | [정적] `ImageMagicByteValidator.detect(head)` 매직바이트 미탐지 시 400(311-315행) |
| TC-PORTALUP-011 | PASS | [정적] `detected.get().matchesExtension(ext)` 불일치 400(316-320행) |
| TC-PORTALUP-012 | PASS | [정적] JPEG EOI(FF D9) 미확인 시 400(321-327행), `readTail` 구현 확인 |
| TC-PORTALUP-013 | PASS | **[실동작 재확인]** good.png(정상) + bad.txt→bad.png(위조 PNG) 2파일 업로드 → `HTTP 400 {"errorCode":"INVALID_INPUT","message":"업로드할 수 없는 파일이 포함되어 있습니다."}`. 디스크 파일 개수(`/app/storage/raw/portal/images`) 요청 전후 **69건으로 불변** — 사전검증(96-109행)이 디스크 쓰기(115행 이후) 전에 전량 실행되어 all-or-nothing 실증 |
| TC-PORTALUP-014 | **FAIL** | [정적] 재확인 — **여전히 미해소**. 아래 이슈 F-ISSUE-62 참조 |
| TC-PORTALUP-015 | **FAIL** | [정적] 재확인 — **여전히 미해소**. 아래 이슈 F-ISSUE-63 참조 |
| TC-PORTALUP-016 | PASS | [정적] `storedName = UUID.randomUUID() + "." + format`(118-119행), 원본명은 `ORGNL_FILE_NM`에만 저장 |
| TC-PORTALUP-017 | PASS | [정적] `truncate()` 454-459행 — 255자 초과 시 절단 |
| TC-PORTALUP-018 | PASS | [정적] `PortalUploadController.acquireUploadPermit` — `portalRateLimiterRegistry.rateLimiter("portalUpload-"+owner,...)`, 실패 시 429(TOO_MANY_REQUESTS). per-user 격리 확인 |

### [F-ISSUE-61] TC-PORTALUP-004 — `files` 파트 부재 업로드가 400이 아니라 500(스택트레이스 로깅) — **2차 F-ISSUE-61 이월, 미해소**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 업로드할 이미지가 없는 요청은 클라이언트 입력 오류(400 INVALID_INPUT)로 거부되어야 한다. 5xx로 나가면 클라이언트가 서버 장애로 오인하고, `GlobalExceptionHandler`의 최종 `Exception` 핸들러가 ERROR 레벨 스택트레이스를 남겨 내부 필터 체인이 로그에 노출된다(CWE-209). 모니터링 5xx 알람도 오염된다.
- **현재 동작(이슈 내용)**: `MissingServletRequestPartException`이 `GlobalExceptionHandler`(`backend/src/main/java/kr/co/cudo/authoring/common/exception/GlobalExceptionHandler.java`)에 여전히 매핑돼 있지 않다(76-93행에 `MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException` 핸들러만 존재, `MissingServletRequestPartException` 없음. 241행 이후 `Exception.class` catch-all(273-278행)로 낙하).
  - 실측(2026-08-04, 3차 재검증):
    ```
    curl -H "Authorization: Bearer $PORTAL_JWT" -F "dummy=x" http://localhost:18081/api/v1/portal/uploads/images
    → HTTP 500 {"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}
    backend log: ERROR ... GlobalExceptionHandler - [Exception] unhandled exception
      org.springframework.web.multipart.support.MissingServletRequestPartException: Required part 'files' is not present.
    ```
  - `PortalUploadService.uploadImages`의 빈 목록 가드(`files == null || files.isEmpty()` → 400, 87-89행)는 컨트롤러 바인딩 단계에서 예외가 던져져 도달조차 하지 않는다.
- **재현/확인 경로**:
  ```bash
  curl -s -w "\nHTTP:%{http_code}\n" -H "Authorization: Bearer $PORTAL_JWT" \
       -F "dummy=x" http://localhost:18081/api/v1/portal/uploads/images
  # → 500 INTERNAL_ERROR (기대: 400 INVALID_INPUT)
  ```
- **영향**: CWE-209(스택트레이스 ERROR 로깅) + 오류 분류 오염(4xx→5xx). 인증 필요 경로라 외부 무인증 공격면은 아니나 PORTAL_USER 누구나 5xx 알람을 유발 가능.
- **수정 방향(제안)**: `GlobalExceptionHandler`에 `@ExceptionHandler(MissingServletRequestPartException.class)`(또는 상위 `ServletRequestBindingException`)를 추가해 `ErrorCode.INVALID_INPUT`(400)으로 매핑. 컨트롤러 테스트에 "files 파트 없는 업로드 → 400" 케이스 추가(현재 `PortalUploadControllerTest`에 이 케이스 없음, 재확인함).

### [F-ISSUE-62] TC-PORTALUP-014 — 프레임 INSERT가 커밋 시점에 실패하면 업로드 파일이 고아로 영구 잔존 — **2차 F-ISSUE-62 이월, 미해소**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `uploadImages`는 all-or-nothing이다(클래스 Javadoc `#4`). DB가 롤백되면 디스크에도 아무것도 남지 않아야 한다.
- **현재 동작(이슈 내용)**: `LsPortalUldFrme`(`backend/src/main/java/kr/co/cudo/authoring/portal/entity/LsPortalUldFrme.java:36-39`) PK 전략이 여전히 `GenerationType.SEQUENCE`(`allocationSize=50`)라 `frmeRepository.save()`(`PortalUploadService.java:133-134`)는 INSERT를 큐에만 넣고 실제 실행은 트랜잭션 커밋 flush 시점에 일어난다. 그 실패는 `uploadImages`의 `try/catch`(138-147행) **밖**이라 `rollbackFiles(writtenThisRequest)`가 실행되지 않는다. 코드 변경 없음(2차 실측 재확인, 라인도 동일).
- **재현/확인 경로**: 2차와 동일(임시 CHECK 제약으로 커밋 시점 INSERT 실패 유발 → 파일 잔존, DB는 정상 롤백). 회수 스윕(`PortalUploadSweepJob`)도 `frames/{uldSn}/`(영상 프레임)만 정리하고 `portal/images/`(이미지 업로드 원본)는 대상이 아님(`PortalUploadSweepJob.java:91-114`, 여전히 미대상).
- **영향**: CWE-459(Incomplete Cleanup) + 저장소 고갈(OWASP API4). 소유 레코드 없는 사용자 업로드 원본 이미지가 무기한 잔존.
- **수정 방향(제안)**: 2차와 동일 — ①`TransactionSynchronization.afterCompletion(STATUS_ROLLED_BACK)` 등록 ②`saveAndFlush`로 INSERT를 메서드 내부로 끌어옴 ③스윕에 `portal/images/` ↔ `LS_PORTAL_ULD.FILE_PATH_NM` 고아 회수 추가.

### [F-ISSUE-63] TC-PORTALUP-015 — write 도중 IOException 시 부분 기록분이 보상되지 않음 — **2차 F-ISSUE-63 이월, 미해소**
- **심각도**: LOW
- **기대 동작(기대효과)**: 디스크 고갈 등으로 저장이 실패하면 이번 요청이 만든 파일은 하나도 남지 않아야 한다(클래스 Javadoc `#5`).
- **현재 동작(이슈 내용)**: `PortalUploadService.java:120-123` — `writeToDisk(v.file(), dst)`(122행) 실행 후에야 `writtenThisRequest.add(dst)`(123행)가 실행되는 순서가 그대로다. `writeToDisk`(365-372행) 내부 `Files.copy(in, dst)`가 중간에 IOException을 던지면 `dst`는 부분 기록된 채 남고 `writtenThisRequest`에는 추가되지 않아 `rollbackFiles`(139/143행)가 그 파일을 못 지운다. 코드 변경 없음(라인 동일).
- **재현/확인 경로**: 2차와 동일 — 디스크 고갈 시뮬레이션 또는 코드 순서만으로도 결정적 성립.
- **영향**: CWE-459. 보안 영향은 낮음(디스크 이미 고갈된 상황 잔여물).
- **수정 방향(제안)**: `writtenThisRequest.add(dst)`를 `writeToDisk` 호출 **전**으로 이동(rollback이 `deleteIfExists`라 미생성 파일에도 안전), 또는 `writeToDisk` catch에서 `Files.deleteIfExists(dst)` 수행 후 예외 재던짐.

---

## F-7. 포털 업로드 라벨 CRUD (전체교체 · 상한 · READY 가드 · 다운로드)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-PORTALUP-040 | PASS | [정적] `replaceLabels` 99-139행 — 빈 배열 시 `deleteAllByUldFrmeSnAndPortalUserNo`만 실행, saveAll 대상 0건(멱등). |
| TC-PORTALUP-041 | PASS | [정적] 검증(`validateAndPrepare`, 110-113행)이 락 획득(116행)·DELETE(128행)보다 **먼저** 실행 — 검증 실패 시 DELETE 도달 자체가 불가(400, 기존 라벨 보존 보장) |
| TC-PORTALUP-042 | PASS | [정적] `validateAndPrepare` 271-276행 — `TYPE_BBOX`/`TYPE_POLYGON` 외 400(fail-closed) |
| TC-PORTALUP-043 | PASS | [정적] `MAX_LABELS_PER_FRAME=500`(68행), 106-109행 `input.size() > 500` 400. 컨트롤러 `@Size` 이중 방어(`PortalUploadLabelController.java:63`) |
| TC-PORTALUP-044 | PASS | [정적] `BBOX_POINT_COUNT=2`(76행), 287-290행 정확히 2점 아니면 400 |
| TC-PORTALUP-045 | PASS | [정적] `POLYGON_MIN_POINTS=3`/`POLYGON_MAX_POINTS=200`(78-79행), 291-296행 범위 밖 400 |
| TC-PORTALUP-046 | PASS | [정적] 306-309행 `!Double.isFinite(x)\|\|!Double.isFinite(y)` → 400 |
| TC-PORTALUP-047 | PASS | [정적] `MAX_LABEL_LENGTH=80`(83행), 278-281행 초과 시 400 |
| TC-PORTALUP-048 | PASS | [정적] 120-125행 — `uld.getUldSttsCd()` READY 아니면 409(CONFLICT) |
| TC-PORTALUP-049 | PASS | [정적] `frmeRepository.findByUldFrmeSnAndOwnerForUpdate`(116행) 비관적 락으로 동일 프레임 병렬 PUT 직렬화 |
| TC-PORTALUP-050 | PASS | [정적] 락 조회 자체가 소유자 스코프(`AndOwnerForUpdate`) — 타인 uldFrmeSn은 `Optional.empty()`→403 |
| TC-PORTALUP-051 | PASS | **[실동작 근거 재확인]** — `PortalLabelBodySizeFilter`가 2차 지적(F-ISSUE-64) 이후 **PathPattern 기반 판정으로 교체 완료**(`PortalLabelBodySizeFilter.java:77-92, 181-200`). `maxLabelBodyBytes` 기본 2097152(2MB, `PortalUploadProperties.java:38`), 146-150행 초과 시 413 |
| TC-PORTALUP-052 | PASS | [정적] 140-145행 `contentLength < 0`(chunked/Content-Length 부재) → 411 |
| TC-PORTALUP-053 | PASS | [정적] `shouldNotFilter` 102-124행 — 라벨 PUT/user-labels POST 경로 외에는 필터 스킵 |
| TC-PORTALUP-054 | PASS | [정적] `listLabels` 145-150행 — `findByUldFrmeSnAndOwner` 소유자 스코프, 타인/부재 403 |
| TC-PORTALUP-055 | PASS | [정적] `exportLabels` 168-175행 — `lblRepository.findAllByUldSnAndPortalUserNo`(uldSn 단위 1회 조회) 후 `labelsByFrame` 맵으로 그룹핑, 프레임별 재조회 없음(N+1 회피) |
| TC-PORTALUP-056 | PASS | [정적] `attachmentDisposition`/`sanitizeFileName`(337-366행) — CR/LF/제어문자/따옴표/역슬래시/슬래시 제거 + RFC5987 `filename*=UTF-8''` 인코딩 |
| TC-PORTALUP-057 | PASS | [정적] `downloadFile` 224-228행 — `filePathNm == null \|\| isBlank()` → 404 |
| TC-PORTALUP-058 | PASS | [정적] `exportLabels`/`downloadFile` 모두 `uldRepository.findByUldSnAndPortalUserNo(...).orElseThrow(this::forbidden)` — 타인 uldSn 403 |

> **F-ISSUE-64(2차) 해소 확인**: `PortalLabelBodySizeFilter.java`가 `request.getRequestURI()`(디코딩 전 원문) 정규식 매칭에서 `ServletRequestPathUtils.parseAndCache`+`PathPattern`(MVC와 동일 판정) 기반으로 전면 교체됐다(77-92행 패턴 선언, 181-200행 `pathWithinApp`). trailing slash 변형 패턴도 함께 등록(78-79, 87-88행). 클래스 Javadoc(48-67행)에 2차 QA가 지적한 퍼센트 인코딩 우회(`%6Cabels`)·chunked 우회 사례가 회귀 배경으로 명시돼 있고, `servlet.path` prefix 정합까지 반영(172-177행 주석 + TC-PORTALUP-057 카탈로그 각주). **실제 코드 수정으로 해소된 것으로 판단**(회귀 테스트는 `PortalLabelBodySizeFilterTest`에 위임, baseline에서 이 클래스 통과 확인).

---

## F-9. 포털 영상 프레임 추출 (비동기 · 간격 · 상한 · 실패)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-PORTALUP-060 | PASS | [정적] `PortalFrameExtractBridge.onPortalVideoUploaded`(`@TransactionalEventListener(phase=AFTER_COMMIT)`) → `runner.runAsync(uldSn)` — 커밋 후에만 트리거 확인(`PortalFrameExtractBridge.java:29-34`) |
| TC-PORTALUP-061 | PASS | [정적] `extract()` 86-91행 — `txService.beginProcessing(uldSn)` `Optional.isEmpty()` 시 즉시 return(skip) |
| TC-PORTALUP-062 | PASS | [정적] `ConfigKeys.java:44`(주석 "NUMBER 정수 1~600. 기본 5"), `DEFAULT_INTERVAL_SEC=5`(`PortalFrameExtractRunner.java:47`) — DB 시드=코드 폴백=5 일치(UNCERTAINTIES #5 해소 확정과 정합) |
| TC-PORTALUP-063 | PASS | [정적] `snapshotIntervalSec()` 185-193행 — `RuntimeException` catch 시 `DEFAULT_INTERVAL_SEC` 폴백 |
| TC-PORTALUP-064 | PASS | [정적] `ConfigKeys.java:85`(`new int[]{1, 600}`) 범위 밖 값은 `SystemConfigService.update` 단계에서 거부(경계 [1,600] 실측 확인) |
| TC-PORTALUP-065 | PASS | [정적] `computeFrameNumbers` 147-183행 — `candidates.size() > cap`이면 균등 재샘플링(`stride = totalFrames/cap`) |
| TC-PORTALUP-066 | PASS | [정적] `candidates.size() <= cap`이면 그대로 반환(161-163행) — 정확히 2000이면 전량 반환(≤2000 충족) |
| TC-PORTALUP-067 | PASS | [정적] `candidates.isEmpty()` 시 `candidates.add(0)`(158-160행) — 영상 길이<간격이어도 최소 1프레임(0번) 보장 |
| TC-PORTALUP-068 | PASS | [정적] `DEFAULT_FPS=30.0`(49행), `probe.fps() > 0 ? probe.fps() : DEFAULT_FPS`(102행) |
| TC-PORTALUP-069 | PASS | [정적] `txService.completeReady(uldSn, frames, duration, fps)`(127행) — 전체 추출 성공 후 원자 커밋 |
| TC-PORTALUP-070 | PASS | [정적] `catch (Exception e)`(133-138행) — `cleanup(written, outputDir)` + `txService.markFailed(...)` |
| TC-PORTALUP-071 | PASS | [정적] `i % PROGRESS_CHECK_EVERY == 0 && !txService.touchProcessing(uldSn)`(114-119행, `PROGRESS_CHECK_EVERY=50`) — 진행 중 자산 삭제/전이 감지 시 abort+cleanup |
| TC-PORTALUP-072 | PASS | [정적] `resolveSafeFramesDir` 195-201행 — `!resolved.startsWith(storageRoot)` → `IllegalStateException` |
| TC-PORTALUP-073 | PASS | [정적] `runAsync` 71-82행 — `catch (Exception e)` 후 `log.warn`만, 예외 재던짐 없음(`@Async` 예외 흡수) |

> `PortalFrameExtractRunner.java` 라인 전부 카탈로그와 일치(변경 없음 — 3차 재확인).

---

## F-10. 상태 전이 · 정리 스윕

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-PORTALUP-080 | PASS | [정적] `LsPortalUld.java` — `@Setter` 없음, `markProcessing()`(114-118행)/`markReady()`(120-131행)/`markFailed()`(133-138행) 비즈니스 메서드만으로 전이 |
| TC-PORTALUP-081 | PASS | [정적] `PortalUploadService.java:129` — `uld.markReady(null, null, 1)` 이미지 생성 직후 즉시 호출(READY 즉시 확정) |
| TC-PORTALUP-082 | PASS | [정적] `PortalUploadSweepJob.cleanupExpiredSessions()`(71-77행) → `txService.claimExpiredSessions()` + `TusChunkStore.deleteQuietly` |
| TC-PORTALUP-083 | PASS | [정적] `failStuckUploads()`(83-89행) — `stuckTimeoutMinutes`(기본 30, `PortalUploadProperties.java:39` `@DefaultValue("30")`) 경과 시 FAILED 전이 + `cleanupFrameDir`(원본 미삭제, 프레임만 정리) |
| TC-PORTALUP-084 | PASS | [정적] `cleanupFrameDir` 92-97행 — `!framesDir.startsWith(storageRoot)` → WARN + skip(root 밖 삭제 차단) |
| TC-PORTALUP-085 | PASS | [정적] 클래스 Javadoc(25-28행) — `@Scheduled` self-invocation 프록시 우회 방지 목적 명시, `run()`이 `txService.claimExpiredSessions()`/`failStuckUploads()` 위임 확인 |
| TC-PORTALUP-086 | PASS | [정적] `PortalUploadSweepTxService.claimExpiredSessions()` 46-56행 — `tusRepository.deleteExpiredInProgress(uldId)` 조건부 벌크 삭제, `removed==1`인 노드만 `claimedFilePaths`에 추가(다른 노드는 0행, 예외 없음) |
| TC-PORTALUP-087 | PASS | [정적] `failStuckUploads` 67-84행 — `uldRepository.failIfInStatus(...)` 조건부 UPDATE(WHERE 상태 재확인), `n==1`인 노드만 성공 처리 |

## F-11. 내부 파이프라인 분리

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-PORTAL-090 | PASS | [정적] `PortalUploadLabelService` 생성자 의존성 — `LsPortalUldRepository`/`LsPortalUldFrmeRepository`/`LsPortalUldLblRepository`/`PortalUploadProperties`/`ObjectMapper`만(내부 batch/video/label 도메인 미참조, 85-89행) |
| TC-PORTAL-091 | PASS | [정적] `PortalVideoUploadService.java` 클래스 Javadoc(24-31행) — "완료 합류처(LS_PORTAL_ULD)·완료 이벤트(PortalVideoUploadedEvent)는 포털 전용으로 완전 분리(관제 비식별 파이프라인 미연결)" 명시. `PortalFrameExtractBridge`도 `PortalVideoUploadedEvent`만 소비, 관제 `VideoIngestedEvent`와 무관(19-20행 주석) |
| TC-PORTAL-092 | PASS | [실동작 근거] `PortalLabelService`(saveUserLabel) — `userLabelRepository.save(LsPortalUserLabel.create(...))`만 실행, `LS_DATA_LBL` 미접촉 확인(코드 read: `PortalLabelService.java` saveUserLabel 본문) |
| TC-PORTAL-093 | PASS | [정적] `PortalFrameExtractRunner.runAsync`(71행) — `@Async("portalExtractExecutor")` 전용 풀 명시(관제 배치 풀과 격리, 클래스 Javadoc 26-27행) |
| TC-PORTAL-094 | PASS | [정적] `PortalUploadLabelService` 리포지토리 의존성에 데이터마트 View/내부 도메인 조회 경로 없음(44-46행 상응 확인) — 포털 자산은 `V_COMPLETED_*` 뷰 소스 테이블(`LS_DATA_RAW` 등)과 무관한 별도 `LS_PORTAL_*` 테이블 |

---

## 이전 회차(2026-08-02/2차) 이슈 해소 여부 총괄 (내 담당 범위 4건)

| 이슈 | 상태 | 비고 |
|---|---|---|
| F-ISSUE-61 (TC-PORTALUP-004, files 파트 부재→500) | **미해소** | 코드·실동작 모두 재확인, 변경 없음. 이번 회차 F-ISSUE-61로 이월 |
| F-ISSUE-62 (TC-PORTALUP-014, 프레임 INSERT 커밋 실패 고아) | **미해소** | 코드 재확인(라인 동일), 변경 없음. 이번 회차 F-ISSUE-62로 이월 |
| F-ISSUE-63 (TC-PORTALUP-015, write 중 IOException 부분기록 미보상) | **미해소** | 코드 재확인(라인 동일), 변경 없음. 이번 회차 F-ISSUE-63으로 이월 |
| F-ISSUE-64 (TC-PORTALUP-051/052, body size 필터 URL 인코딩 우회) | **✅ 해소** | `PortalLabelBodySizeFilter`가 PathPattern 기반 디코딩 경로 판정으로 전면 교체됨(코드 실증) |

## 카탈로그 정정

이번 회차 담당 라인범위(113~135, 154~179, 219~end) 내에서 근거 `file:line`·기대결과 문구 전수 재확인 결과 **정정 0건**(카탈로그가 2026-08-03 회차에 이미 근거 라인 드리프트를 전수 재확인해 반영해 둔 상태였음 — 이번 검증에서 발견된 라인 오차는 application.yml/application-prd.yml의 ±1~2행 근사 오차뿐이며 실질 드리프트로 판단하지 않아 미수정).
