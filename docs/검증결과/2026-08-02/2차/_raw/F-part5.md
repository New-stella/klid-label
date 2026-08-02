# F-part5 — F-8. 포털 영상 TUS 업로드 (세션 · 재개 · 완료검증 · 동시성) 32건

> 대상: `docs/test-cases/F-portal.md` § F-8 (TC-TUS-001~032)
> 일자: 2026-08-02 · 2차 · 코드 기준 `qa-0801`
> 검증 방식: **실동작 최우선** — 기동 중인 풀스택(backend :18081 `/api`, PostgreSQL, ai-server, mock-server :9400)에
> curl 로 TUS 프로토콜 시퀀스(OPTIONS→POST→HEAD→PATCH→DELETE)를 직접 왕복시키고,
> `ls_portal_tus_uld` / `ls_portal_uld` / `ls_portal_uld_frme` DB 상태와 컨테이너 파일시스템(`docker exec ls`),
> backend 애플리케이션 로그를 근거로 판정. 정적 대조(file:line Read)는 병행.
> **코드/설정/테스트 파일 수정 0건, 빌드/테스트 실행 0건.**

## 검증 환경

| 항목 | 값 |
|------|-----|
| backend | `http://localhost:18081/api` (context-path `/api`), 컨테이너 `klid-backend` |
| DB | `docker exec klid-postgres psql -U klid_user -d klid_system`, 스키마 `public` |
| 토큰 | 컨테이너 실효 `JWT_SECRET` 으로 HS256 자체 서명. `sub=3001`·`sub=3002` + `channel=PORTAL` → `GET /v1/me` 로 `role=PORTAL_USER` 확인. IDOR 은 3001 세션에 3002 토큰으로 접근 |
| 시료 | `ffmpeg -f lavfi -i testsrc` 로 생성한 유효 mp4(3,390B, 2s) / 오디오전용 mp4(18,802B) / ftyp 위조 파일(1,000B) / ftyp 헤더+쓰레기 본문(1,032B) / 16MB+1B 청크 |
| 실효 설정 | `portal.upload.max-file-size-bytes=5368709120`, `allowed-extensions=[mp4,mov,avi]`, `max-chunk-bytes=16777216`, `storage-path=/app/storage/raw/portal` (application.yml:464-483 실측) |
| DB 조작 | TC-013 검증용으로 세션 1건의 `expry_dt` 를 과거로 UPDATE(해당 세션은 검증 종료 후 CANCELLED 종결). 그 외 스키마·설정 변경 0 |
| 잔여 데이터 | 검증 중 생성한 IN_PROGRESS 세션은 전부 DELETE 로 회수 완료(잔여 IN_PROGRESS 0건). CANCELLED 20 / COMPLETED 8 행은 종결 상태로 잔존 |

## 판정 요약

| 판정 | 건수 |
|------|:---:|
| PASS | 29 |
| PARTIAL | 3 |
| FAIL | 0 |
| BLOCKED / N/A / 확인필요 | 0 |
| **합계** | **32** |

**근거 드리프트: 0건** — 인용된 32개 `file:line` 이 전부 실제 코드 위치와 정확히 일치(카탈로그의 `PortalVideoUploadTxService.java` -1 이동 반영이 이미 적용돼 있음).

---

## 케이스별 결과

| ID | 판정 | 근거 확인 | 상세 |
|----|------|-----------|------|
| TC-TUS-001 | PASS | [실동작] | `OPTIONS /v1/portal/uploads/tus` → **204** + `Tus-Resumable: 1.0.0` · `Tus-Version: 1.0.0` · `Tus-Extension: creation,termination` · **`Tus-Max-Size: 5368709120`**. `PortalTusUploadController.java:67-76` 라인 일치 |
| TC-TUS-002 | PASS | [실동작] | `POST` (Upload-Length: 1024, Upload-Metadata: `filename c2FtcGxlLm1wNA==`) → **201** + `Location: /v1/portal/uploads/tus/7cc90fe6-67e7-40bb-ae57-930fb3096a07`. DB `ls_portal_tus_uld` 1행 IN_PROGRESS/offset=0 생성 확인. `PortalVideoUploadService.java:80-122` 일치. ⚠ Location 이 context-path `/api` 를 누락 → **F-ISSUE-82**(케이스 기대값 자체는 충족하므로 PASS) |
| TC-TUS-003 | PASS | [실동작] | Upload-Length 헤더 생략 → **400** `{"errorCode":"INVALID_INPUT","message":"Upload-Length 헤더가 필요합니다."}`. `Controller:88-90` 일치. 추가 반증: `Upload-Length: 0` → 400, `-1` → 400, `abc` → 400(`파라미터 형식이 올바르지 않습니다: Upload-Length`) — 전부 방어됨 |
| TC-TUS-004 | PASS | [실동작] | `Upload-Length: 5368709121`(=상한+1) → **413** `PAYLOAD_TOO_LARGE` "허용 한도(5368709120 bytes) 를 초과". `Service:87-90` 일치. 커버 테스트 `PortalVideoUploadServiceTest#uploadLengthTooLarge`(baseline 통과) |
| TC-TUS-005 | PASS | [실동작] | `evil.exe` → **400** "허용되지 않는 확장자입니다. 허용: [mp4, mov, avi]". 반증 추가: 확장자 없음(메타 미전송) → 400, `a.webm` → **400**(코드의 `EXT_TO_MIME` 에 webm/mkv 가 있지만 allowlist 가 최종 게이트 — 우회 불가), `A.MP4` 대문자 → **201**(`toLowerCase` 정규화 정상). `Service:91-95` 일치. 테스트 `#invalidExtensionRejected` |
| TC-TUS-006 | PASS | [실동작] | IN_PROGRESS 3건 보유 상태에서 4번째 POST → **429** `TOO_MANY_REQUESTS` "동시 진행 가능한 업로드 세션 수(3) 를 초과". `Service:96-102` 일치 |
| TC-TUS-007 | PASS | [실동작] | `Upload-Metadata: filename <b64('../../../../etc/passwd.mp4')>` → 201. DB `file_path_nm=/app/storage/raw/portal/tus-video/b3e4c9f4-….mp4`, `orgnl_file_nm='../../../../etc/passwd.mp4'`(표시용만). 컨테이너 `ls` 로 `tus-video/` 하위 UUID 파일 실재 확인, 상위 경로 파일 생성 0. `Service:104-107` 일치. 테스트 `#pathTraversalForcedUuid` |
| TC-TUS-008 | PASS | [실동작] | `Upload-Metadata` 1,109자 → **413** "Upload-Metadata 가 1KB 를 초과했습니다.". `Controller:182-184` 일치 |
| TC-TUS-009 | PASS | [실동작] | `Upload-Metadata: filename !!!not-b64!!!` → **400** "Upload-Metadata 디코딩에 실패했습니다.". `Controller:194-200` 일치 |
| TC-TUS-010 | PARTIAL | [실동작] | `Tus-Resumable: 0.2.2` → **412** `PRECONDITION_FAILED`(POST·HEAD 양쪽 확인, `Controller:163-168` 일치). **그러나 헤더를 아예 생략하면 검사 없이 201/204 로 통과**한다 — `requireTusVersion` 이 `tusResumable != null` 가드라 null 을 허용. TUS 1.0 은 OPTIONS 외 전 요청에 Tus-Resumable 을 MUST 로 요구하고 부재 시 412 를 규정 → **F-ISSUE-83** |
| TC-TUS-011 | PASS | [실동작] | 소유 세션 HEAD → **204** + `Upload-Offset: 0` · `Upload-Length: 1024` · **`Cache-Control: no-store`**. 청크1(2,000B) 전송 후 재HEAD → `Upload-Offset: 2000`(재개 좌표 정확). `Controller:102-116` 일치 |
| TC-TUS-012 | PASS | [실동작] | 3001 소유 세션에 3002 토큰으로 **HEAD 403 / PATCH 403 / DELETE 403** (`{"errorCode":"FORBIDDEN","message":"본인의 업로드 세션이 아닙니다."}`). 세션 하이재킹(타 사용자 세션 재개) 차단 확인. 존재하지 않는 uldId → 404(존재 여부 오라클 없음). `Service:130-132` + `TxService:74-76` 일치. 테스트 `#nonOwnerPatchForbidden`·`#nonOwnerHeadDeleteForbidden` |
| TC-TUS-013 | PASS | [실동작] | `expry_dt='2020-01-01'` 로 조작한 IN_PROGRESS 세션 → **HEAD 410 GONE** / **PATCH 410** "업로드 세션이 만료되었습니다.". `Service:133-135`·`TxService:77-79` 일치. (DELETE 는 만료 검사 없이 204 로 정리 — 정리 동선상 정상) |
| TC-TUS-014 | PASS | [실동작] | 3,390B 세션에 청크1(0~1,999) PATCH → **204** + `Upload-Offset: 2000`, 청크2(2,000~3,389) → **204** + `Upload-Offset: 3390`. DB `uld_offset=3390`, `stts_cd=COMPLETED`, `uld_sn=57`. `ls_portal_uld` 57 = VIDEO/tiny.mp4/3390B/READY/frme_cnt=1. `TxService:68-124` 일치 |
| TC-TUS-015 | PASS | [실동작] | Upload-Offset 생략 PATCH → **400** "Upload-Offset 헤더가 필요합니다.". `Controller:130-132` 일치 |
| TC-TUS-016 | PASS | [실동작] | 서버 offset=0 인 세션에 `Upload-Offset: 100` → **409** `CONFLICT` "Upload-Offset 이 서버 상태와 일치하지 않습니다."(재개 무결성). `TxService:98-100` 일치 |
| TC-TUS-017 | PASS | [실동작] | `Upload-Offset: -5` → **400**, `Upload-Offset: 99999`(>length) → **400** "Upload-Offset 값이 범위를 벗어났습니다.". `TxService:95-97` 일치 |
| TC-TUS-018 | PASS | [실동작] | Upload-Length 20,000,000 세션에 16,777,217B(=16MB+1) 청크 → **413** "청크 크기가 허용 한도(16777216 bytes) 를 초과했습니다.". 사후 DB `uld_offset=0` 유지 + 컨테이너 파일 **0바이트 유지**(부분 쓰기 0). `TxService:91-94` 일치 |
| TC-TUS-019 | PASS | [실동작] | offset=2000, 잔여 1,390B 세션에 2,000B 청크 → **400** "청크 길이가 잔여 용량을 초과합니다.". `TxService:101-103` 일치 |
| TC-TUS-020 | PARTIAL | [실동작] | 동일 세션에 offset=0 PATCH 3건 **동시** 발사 → **204 정확히 1건 + 409 2건**, DB `uld_offset=1048576`·`ver=1`, 파일 크기 정확히 1,048,576B(오염 0). **결과는 기대대로**지만 **메커니즘이 케이스 전제와 다르다** — 직렬화는 `findByUldIdForUpdate`(PESSIMISTIC_WRITE)가 담당하고 후행 요청은 `TxService:98-100` 의 **offset 불일치 409** 로 떨어진다. 케이스가 인용한 `TxService:109-115`(낙관적 락 catch + `truncateTo` 복원)는 로그(`concurrent PATCH conflict`) 0건으로 **미발화** → **F-ISSUE-87** |
| TC-TUS-021 | PASS | [실동작] | CANCELLED 세션에 PATCH → **409** "취소된 업로드 세션입니다."(완료 검사보다 먼저 평가되는 순서도 `TxService:81-87` 로 확인). 테스트 `#cancelledSessionPatchRejected` |
| TC-TUS-022 | PASS | [실동작] | COMPLETED 세션(uldSn=57)에 마지막 청크 재전송(offset=2000, 1,390B) → **204 + `Upload-Offset: 3390`**, 서비스는 `PortalTusPatchResult(3390, true, 57)` 로 기존 uldSn 재반환(`TxService:84-87` → `Service:151-157`). **`ls_portal_uld` 중복 행 0건**(재전송 전후 count 불변). 단 HTTP 응답 본문/헤더에 uldSn 이 노출되지 않는다 → **F-ISSUE-88**(unit 계층 기대값은 서비스 반환값이라 PASS 유지). 테스트 `#duplicateCompletionPublishesEventOnce` |
| TC-TUS-023 | PASS | [실동작] | `MZ\x90\x00`+패딩(1,000B, 확장자만 .mp4) 완료 청크 → **400** "업로드된 파일이 유효한 영상 컨테이너가 아닙니다." + DB `stts_cd=CANCELLED` + **컨테이너 임시파일 삭제 확인**(`ls: No such file or directory`). `Service:161-165` + `TxService:164-178`(REQUIRES_NEW 로 400 던져도 취소가 롤백 안 됨) 일치. 테스트 `#magicByteMismatchRejectsAndDeletes` |
| TC-TUS-024 | PASS | [실동작] | 유효 `ftyp isom` 헤더 + 쓰레기 본문(1,032B) → 매직바이트는 통과하고 **ffprobe 단계에서 400** "영상을 확인할 수 없습니다. 손상되었거나 지원되지 않는 형식일 수 있습니다." + CANCELLED + 파일 삭제. `Service:167-175` 일치. 타임아웃 가드(`PortalVideoProbeFfprobe` Future.get 30s)도 정적 확인 |
| TC-TUS-025 | PASS | [실동작] | 오디오 전용 mp4(aac, 18,802B) → **400** "비디오 스트림이 없는 파일입니다(오디오 전용 등)." + CANCELLED + 파일 삭제. `Service:176-179` 일치. 테스트 `#audioOnlyRejected` |
| TC-TUS-026 | PASS | [실동작] | **락/트랜잭션 밖 검증을 레이스로 실증**했다 — 최종 청크 PATCH 발사 후 20ms·40ms 뒤 DELETE 를 보내면 DELETE 가 **즉시 행 락을 획득해 204 로 임시파일을 삭제**하고, 그 뒤 진행되던 ffprobe 가 파일 부재로 실패해 400 이 났다. 검증 구간에 행 락이 걸려 있었다면 DELETE 는 대기했어야 한다 ⇒ 검증이 tx/락 밖에서 도는 것이 확인됨. 정적으로도 `Service.appendChunk`(:146-186)에 `@Transactional` 부재 + `PortalVideoUploadTxService` 별 빈 분리(self-invocation 회피) 확인 |
| TC-TUS-027 | PASS | [실동작] | 완료 7회(uldSn 57·67~72) 각각에 대해 `ls_portal_uld` 1행 / `ls_portal_uld_frme` 1행 — **1:1, 중복 0**. 조건부 UPDATE `markCompletedIfInProgress`(`LsPortalTusUploadRepository`) + `affectedRows==1` 게이트 후 `publishEvent` 순서 `TxService:132-157` 일치. 프레임 추출이 세션당 정확히 1회 실행된 것이 이벤트 단발 발행의 실측 증거. 테스트 `#duplicateCompletionPublishesEventOnce` |
| TC-TUS-028 | PARTIAL | [정적] | 정확한 창(**검증 성공 후 `finalizeCompleted` 진입 직전**에 cancel 착지)은 폭이 밀리초급이라 8회 동시 레이스 + 8단계 지연 스윕(20ms~300ms, 총 16시행)으로도 재현하지 못했다(관측된 것은 ①cancel 선행 → 409 "취소된 업로드 세션입니다" ②검증 중 cancel → 400 ffprobe 실패 ③cancel 후행 → 정상 완료 3종). **다만 인접 레이스 16시행 전부에서 고아 `ls_portal_uld` 행이 0건**이었고, 보상 삭제 경로(`TxService:141-152`: `uldRepository.delete(saved)` → 재조회 후 이미 완료면 기존 uldSn 멱등 반환, 아니면 409)를 코드로 확인. 실동작 미재현 구간이 남아 PARTIAL |
| TC-TUS-029 | PASS | [실동작] | IN_PROGRESS 세션 3건 DELETE → **204**, 각각 컨테이너 임시파일이 삭제 전 존재 → 삭제 후 부재로 확인, DB `stts_cd=CANCELLED`. `Service:190-206` 일치 |
| TC-TUS-030 | PASS | [실동작] | COMPLETED 세션(uldSn=57) DELETE → **204**, 파일 `d325816e-….mp4` **3,390B 그대로 보존**, DB 상태 `COMPLETED/uld_sn=57` 불변(영구 영상 삭제 금지 no-op). `Service:198-201` 일치 |
| TC-TUS-031 | PASS | [실동작] | 600KB 청크를 `--limit-rate 100K` 로 전송(총 6.02s)하는 도중 t≈1.5s 에 DELETE 발사 → **DELETE 가 4.26s 소요**(≈PATCH tx 커밋까지 대기)하고 그 뒤 성공. cancel 이 PATCH 와 같은 `findByUldIdForUpdate` 락으로 **직렬화**됨을 시간축으로 실증. 최종 상태 CANCELLED, 파일 삭제. `Service:192-194` 일치 |
| TC-TUS-032 | PASS | [실동작] | 저장 경로는 항상 `storageRoot/tus-video/{UUID}.{ext}` 로 조립되고 `ext` 는 `^[a-z0-9]{1,8}$` 정규화 + allowlist 통과분만 사용되므로 사용자 입력이 경로 세그먼트에 도달하지 않는다. 경로형 filename 실투입에서도 storage 밖 파일 생성 0(TC-007 참조). `resolveSafe`(`Service:210-218`)는 **도달 불가한 심층방어**이지만 존재·정확성 확인. 삭제 경로도 `TusChunkStore.deleteQuietly(path, storageRoot)` 로 root 하위 재검증 |

---

## 테스트 커버리지 대조

| 항목 | 내용 |
|------|------|
| 자동 테스트 자산 | `backend/src/test/java/kr/co/cudo/authoring/portal/PortalVideoUploadServiceTest.java`(372L, 11 테스트) 단 1개. 그 외 `PortalUploadSweepIT`·`PortalUploadSweepTxServiceTest`·`PortalUploadPropertiesBindingTest`·`V111PortalTusUploadMigrationTest` 는 F-8 직접 대상 아님 |
| baseline | `docs/검증결과/2026-08-01/1차/_raw/test-baseline.md` — backend tests=4,755 / failures 0 / errors 0. 아래 커버 테스트는 전부 통과 상태 |
| 커버되는 TC | TC-004(`#uploadLengthTooLarge`) · 005(`#invalidExtensionRejected`) · 007·032(`#pathTraversalForcedUuid`) · 012(`#nonOwnerPatchForbidden`,`#nonOwnerHeadDeleteForbidden`) · 014·027(`#completesToUploadedAndPublishesEvent`) · 021(`#cancelledSessionPatchRejected`) · 022·027(`#duplicateCompletionPublishesEventOnce`) · 023(`#magicByteMismatchRejectsAndDeletes`,`#rejectionPersistsCancelledAndDeletesFile`) · 025(`#audioOnlyRejected`) — **11 TC** |
| **미커버 TC** | TC-001·002·003·006·008·009·010·011·013·015·016·017·018·019·020·024·026·028·029·030·031 — **21 TC**. 특히 **컨트롤러(MockMvc) 테스트가 0건**이라 헤더 프로토콜(OPTIONS 광고·Tus-Resumable 412·Location·metadata 1KB/base64·no-store) 전체가 자동 회귀 보호를 못 받는다 → **F-ISSUE-89** |

---

## 이슈

### [F-ISSUE-81] TC-TUS-014 — PATCH 의 Content-Type 불일치/부재가 415 가 아니라 500 + 스택트레이스 ERROR 로 처리됨
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `@PatchMapping(consumes = "application/offset+octet-stream")` 과 다른 Content-Type 으로 온 PATCH 는 **415 Unsupported Media Type**(TUS 1.0 도 415 를 규정)으로 거부돼야 한다. 클라이언트가 "내 요청이 잘못됐다"를 알 수 있어야 하고, 정상적인 클라이언트 오류가 서버 장애로 집계·알림되면 안 된다.
- **현재 동작(이슈 내용)**: `HttpMediaTypeNotSupportedException` 을 처리하는 핸들러가 `GlobalExceptionHandler` 에 없어 **generic 핸들러로 낙하 → 500 + `INTERNAL_ERROR`**, 동시에 `[Exception] unhandled exception` 으로 **ERROR 레벨 전체 스택트레이스**가 로그에 남는다.
  ```
  2026-08-02 18:02:37.110 ERROR k.c.c.a.c.e.GlobalExceptionHandler - [Exception] unhandled exception
  org.springframework.web.HttpMediaTypeNotSupportedException: Content-Type 'application/octet-stream' is not supported
      at org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping.handleNoMatch(...)
  ```
  응답: `{"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}` (500)
  **포털 TUS 전용이 아니라 관제 내부 TUS(`PATCH /v1/uploads/{id}`)도 동일하게 500** 이다(양쪽 실측).
- **재현/확인 경로**:
  ```bash
  curl -s -w " <%{http_code}>" -X PATCH -H "Authorization: Bearer $PORTAL_TOKEN" \
    -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 0" \
    -H "Content-Type: application/octet-stream" --data-binary @chunk.bin \
    http://localhost:18081/api/v1/portal/uploads/tus/00000000-0000-0000-0000-000000000000
  # → 500 INTERNAL_ERROR (기대: 415)
  # Content-Type 자체를 생략해도 동일하게 500
  ```
- **영향**: ①프로토콜 오분류 — 표준 TUS 클라이언트가 415(요청 수정)와 500(서버 장애, 재시도 대상)을 구분 못 해 무의미한 재시도를 반복한다. ②운영 노이즈/오탐 — 클라이언트 실수가 ERROR 스택트레이스로 남아 장애 알림·SLO 를 오염시킨다. ③CWE-209 인접 — 내부 프레임워크 예외 타입·스택이 로그에 그대로 적재된다(응답 본문 유출은 없음).
- **수정 방향(제안)**: `common/exception/GlobalExceptionHandler` 에 `@ExceptionHandler(HttpMediaTypeNotSupportedException.class)` 를 추가해 **415 + 도메인 errorCode**(예: `UNSUPPORTED_MEDIA_TYPE`)로 매핑하고 WARN 레벨로 낮춘다. 함께 `HttpRequestMethodNotSupportedException`(405)·`HttpMediaTypeNotAcceptableException`(406)도 같은 계층에서 처리 여부를 점검할 것. ⚠ 구현은 하지 않음.

### [F-ISSUE-82] TC-TUS-002 — 세션 생성 `Location` 헤더가 context-path `/api` 를 누락해 표준 TUS 클라이언트가 따라갈 수 없음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: TUS 1.0 은 POST 응답의 `Location` 을 **이후 HEAD/PATCH/DELETE 에 그대로 사용하는 URL** 로 규정한다. 컨트롤러 javadoc 이 명시적으로 *"응답: TUS 1.0 헤더 기반(ApiResponse 미사용 — **표준 tus-js-client 호환**)"*(`PortalTusUploadController.java:38-40`)을 표방하므로, Location 은 실제로 도달 가능한 경로여야 한다.
- **현재 동작(이슈 내용)**: 애플리케이션 context-path 가 `/api`(`application.yml:126-127`)인데 Location 은 `/api` 없이 조립된다.
  ```java
  // PortalTusUploadController.java:94-97
  return ResponseEntity.status(HttpStatus.CREATED)
          .header(H_RESUMABLE, TUS_VERSION)
          .header(HttpHeaders.LOCATION, "/v1/portal/uploads/tus/" + uldId)   // ← "/api" 누락
          .build();
  ```
  실측: `Location: /v1/portal/uploads/tus/7cc90fe6-67e7-40bb-ae57-930fb3096a07`. 이 절대경로를 origin 기준으로 해석하면 `http://host/v1/portal/uploads/tus/{id}` 로 **404**. 자체 FE(`frontend/src/features/upload/api/tusClient.ts:99` `location.split('/').pop()`)는 **마지막 세그먼트만 잘라 endpointBase 로 재조립**하기 때문에 우연히 동작한다. 관제 내부 `TusUploadController.java:96` 도 동일 패턴.
- **재현/확인 경로**:
  ```bash
  L=$(curl -s -D - -o /dev/null -X POST -H "Authorization: Bearer $PORTAL_TOKEN" \
      -H "Tus-Resumable: 1.0.0" -H "Upload-Length: 1024" \
      -H "Upload-Metadata: filename $(printf a.mp4|base64)" \
      http://localhost:18081/api/v1/portal/uploads/tus | grep -i '^location')
  echo "$L"                       # Location: /v1/portal/uploads/tus/{uuid}
  curl -s -o /dev/null -w "%{http_code}\n" -I "http://localhost:18081/v1/portal/uploads/tus/{uuid}"  # 404
  ```
- **영향**: 기능(외부 연동) — 문서가 표방한 tus-js-client 등 표준 클라이언트 연동이 즉시 깨진다. 현재는 자체 FE 의 "마지막 세그먼트만 사용" 우회에 의존하고 있어, FE 가 표준 라이브러리로 교체되거나 외부 파트너가 붙는 순간 드러난다.
- **수정 방향(제안)**: `ServletUriComponentsBuilder.fromCurrentRequest()` 또는 `request.getContextPath()` 를 이용해 context-path 포함 URL 을 조립(포털·관제 두 컨트롤러 동시). 리버스 프록시 경로 재작성 가능성을 고려해 `server.forward-headers-strategy` 와 함께 검토. ⚠ 구현은 하지 않음.

### [F-ISSUE-83] TC-TUS-010 — `Tus-Resumable` 헤더가 **부재**하면 버전 검사를 통째로 건너뛴다(412 미발생)
- **심각도**: LOW
- **기대 동작(기대효과)**: TUS 1.0 core 규약상 OPTIONS 를 제외한 모든 요청은 `Tus-Resumable` 을 포함해야 하며, 부재 또는 미지원 버전이면 서버는 **412 Precondition Failed** 로 거부해야 한다. 버전 협상을 강제해야 클라이언트/서버 프로토콜 드리프트가 조용히 통과하지 않는다.
- **현재 동작(이슈 내용)**: null 을 예외 처리해 검사를 우회한다.
  ```java
  // PortalTusUploadController.java:163-168
  private void requireTusVersion(String tusResumable) {
      if (tusResumable != null && !TUS_VERSION.equals(tusResumable)) {   // ← null 이면 통과
          throw new CustomException(ErrorCode.PRECONDITION_FAILED, ...);
      }
  }
  ```
  실측: 헤더 생략 POST → **201**(세션 생성됨), 헤더 생략 HEAD/PATCH/DELETE 도 정상 처리. `0.2.2` 같은 잘못된 값만 412.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Authorization: Bearer $PORTAL_TOKEN" \
    -H "Upload-Length: 1024" -H "Upload-Metadata: filename $(printf a.mp4|base64)" \
    http://localhost:18081/api/v1/portal/uploads/tus     # → 201 (기대: 412)
  ```
- **영향**: 프로토콜 conformance. 버전 미표기 클라이언트가 조용히 성공하므로, 향후 TUS 2.x 도입이나 헤더 규약 변경 시 호환성 문제를 사전에 못 잡는다. 보안 영향은 없다(인증·소유자 검증은 별도로 동작).
- **수정 방향(제안)**: `requireTusVersion` 을 `if (!TUS_VERSION.equals(tusResumable))` 로 바꿔 null 도 412 로 거부. 단 **기존 자체 FE 클라이언트가 항상 헤더를 보내는지 먼저 확인**(`tusClient.ts` 는 POST/HEAD/PATCH 모두 전송 확인, DELETE 경로만 재확인 필요)한 뒤 적용. 관제 내부 컨트롤러도 동일 검토. ⚠ 구현은 하지 않음.

### [F-ISSUE-84] TC-TUS-004 — 오류 응답에 `Tus-Resumable` 헤더가 포함되지 않음
- **심각도**: LOW
- **기대 동작(기대효과)**: TUS 1.0 은 서버가 **모든 응답**(오류 포함)에 `Tus-Resumable` 을 실어 보내도록 규정한다. 클라이언트가 오류 응답만 보고도 상대가 TUS 서버인지·어떤 버전인지 판별할 수 있어야 한다.
- **현재 동작(이슈 내용)**: 정상 응답(201/204)에는 컨트롤러가 직접 `H_RESUMABLE` 을 붙이지만, 예외 경로는 `GlobalExceptionHandler` 가 `ApiResponse` JSON 을 만들어 반환하므로 TUS 헤더가 전부 사라진다. 실측:
  ```
  $ curl -D - -X POST ... -H "Upload-Length: 99999999999" ...
  HTTP/1.1 413
  (Tus-Resumable 헤더 없음)
  ```
- **재현/확인 경로**: 위 curl 의 `-D -` 출력에서 `Tus-Resumable` grep → 0건. 413/412/409/400 전 오류 코드 동일.
- **영향**: 프로토콜 conformance만. 기능·보안 영향 없음.
- **수정 방향(제안)**: TUS 경로 전용 `HandlerInterceptor` 또는 `ResponseBodyAdvice` 로 `/v1/portal/uploads/tus/**`·`/v1/uploads/**` 응답에 `Tus-Resumable` 을 일괄 부착. F-ISSUE-81 의 예외 매핑 작업과 함께 처리하면 경제적. ⚠ 구현은 하지 않음.

### [F-ISSUE-85] TC-TUS-011/029 — CANCELLED 세션에 HEAD 하면 204 + 실제로는 존재하지 않는 offset 을 돌려준다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 재개(resume)의 진입점은 HEAD 다. 종료된(취소된) 업로드에 대해 서버는 **더 이상 재개 대상이 아님**을 HEAD 단계에서 알려야(TUS 1.0: 404) 클라이언트가 새 세션을 만드는 정상 동선으로 갈 수 있다.
- **현재 동작(이슈 내용)**: `getForOwner` 는 소유자·만료만 검사하고 **취소 여부를 보지 않는다**.
  ```java
  // PortalVideoUploadService.java:126-137
  LsPortalTusUpload session = tusRepository.findById(uldId).orElseThrow(... NOT_FOUND ...);
  if (!session.isOwnedBy(portalUserNo)) { throw ... FORBIDDEN ...; }
  if (session.isExpired(java.time.LocalDateTime.now())) { throw ... GONE ...; }   // isExpired 는 IN_PROGRESS 만 판정
  return session;                                                                  // CANCELLED 여도 그대로 반환
  ```
  실측 시나리오(TC-031 레이스 산출물): 느린 PATCH 로 614,400B 를 커밋한 직후 DELETE 가 착지 → DB `stts_cd=CANCELLED, uld_offset=614400`, **임시파일은 삭제됨**. 이 세션에 HEAD → **204 `Upload-Offset: 614400`**. 클라이언트는 "614,400B 부터 이어서 보내면 된다"고 판단하지만 이어지는 PATCH 는 409 `취소된 업로드 세션입니다` 로 거부된다(파일도 이미 없다).
- **재현/확인 경로**:
  ```bash
  # 세션 생성 → 청크 일부 PATCH → DELETE → HEAD
  curl -s -o /dev/null -w "%{http_code}\n" -X DELETE -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" $B/v1/portal/uploads/tus/$S
  curl -s -I -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" $B/v1/portal/uploads/tus/$S | grep -iE "HTTP|Upload-Offset"
  # → HTTP/1.1 204 / Upload-Offset: 614400   (기대: 404 또는 410)
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select stts_cd,uld_offset from ls_portal_tus_uld where uld_id='$S';"
  # → CANCELLED | 614400  (파일은 삭제 상태)
  ```
- **영향**: 기능/데이터정합 — 재개 클라이언트가 무효 세션을 유효한 것으로 오인해 불필요한 재시도 루프에 빠지고, 사용자에게는 "이어받기 가능"으로 표시된 뒤 실패한다. DB offset 과 실제 파일(부재)의 불일치가 그대로 노출된다. 보안 영향은 없다(소유자 검증은 선행되고 파일도 이미 삭제).
- **수정 방향(제안)**: `getForOwner` 에 `if (session.isCancelled()) throw new CustomException(ErrorCode.NOT_FOUND, ...)` 를 추가(TUS 1.0 의 terminated upload → 404 규약). 취소 후 오래 지난 세션의 존재 여부 오라클을 만들지 않도록 **404 로 통일**하는 편이 410 보다 안전하다. 관제 내부 TUS 도 동일 패턴인지 확인. ⚠ 구현은 하지 않음.

### [F-ISSUE-86] TC-TUS-006 — TUS 세션 생성에 rate limit 이 없고 종결 세션 행이 영구 잔존해 create/cancel 루프로 무제한 증식 가능
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 포털은 외부 채널이므로 인증된 사용자라도 자원 소비를 제한해야 한다(OWASP API4:2023 Unrestricted Resource Consumption). 같은 포털 도메인의 **이미지 업로드(`PortalUploadController:189-192`, config `portalUpload`)와 SAM2(`PortalSam2Service:286-289`, config `portalSam2`)에는 per-user RateLimiter 가 이미 적용**돼 있어, TUS 만 빠진 것은 방어 일관성의 구멍이다.
- **현재 동작(이슈 내용)**: `PortalTusUploadController`·`PortalVideoUploadService` 어디에도 RateLimiter 배선이 없다(`grep -rn "RateLimit" portal/` → `PortalUploadController`·`PortalSam2Service` 2곳만 매치). 유일한 억제 수단은 `MAX_CONCURRENT_IN_PROGRESS = 3`(`Service:52,96-102`)인데, 이는 **IN_PROGRESS 만 세므로** create→cancel 을 반복하면 무한히 우회된다. 게다가 정리 스윕은 **IN_PROGRESS 만 삭제**한다:
  ```java
  // LsPortalTusUploadRepository
  @Query("SELECT u FROM LsPortalTusUpload u WHERE u.sttsCd = 'IN_PROGRESS' AND u.expiresAt < :now")
  List<LsPortalTusUpload> findExpired(...);
  @Query("DELETE FROM LsPortalTusUpload u WHERE u.uldId = :uldId AND u.sttsCd = 'IN_PROGRESS'")
  int deleteExpiredInProgress(...);
  ```
  → **CANCELLED/COMPLETED 행은 어떤 경로로도 삭제되지 않는다**(`PortalUploadSweepTxService.claimExpiredSessions` 실측). 세션 생성 시마다 `TusChunkStore.createEmptyFile` 로 0바이트 파일도 만들어진다(cancel 시 삭제되긴 함).
- **재현/확인 경로**:
  ```bash
  # POST → DELETE 를 N회 반복해도 429 없이 계속 성공하고, 행은 계속 쌓인다
  for i in $(seq 1 100); do
    L=$(curl -s -D - -o /dev/null -X POST -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" \
        -H "Upload-Length: 1024" -H "Upload-Metadata: filename $(printf a.mp4|base64)" \
        $B/v1/portal/uploads/tus | grep -i '^location' | tr -d '\r' | awk '{print $2}')
    curl -s -o /dev/null -X DELETE -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" $B$L
  done
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select stts_cd,count(*) from ls_portal_tus_uld group by stts_cd;"
  # 본 검증 세션만으로도 CANCELLED 20건이 영구 잔존 상태로 남았다
  ```
- **영향**: 가용성/저장소(CWE-770). 인증된 포털 사용자 1인이 `LS_PORTAL_TUS_ULD` 를 무제한 증식시킬 수 있고, 종결 행이 정리되지 않아 시간이 지날수록 테이블이 단조 증가한다. 부분 인덱스(`idx_lptu_user_stts`, `idx_lptu_expry`)가 IN_PROGRESS 조건부라 조회 성능 영향은 제한적이지만 스토리지·백업 비용은 누적된다.
- **수정 방향(제안)**: ①`PortalTusUploadController.create` 에 `PortalUploadController` 와 동일한 per-user RateLimiter(`portalTusCreate` config 신설 또는 `portalUpload` 재사용) 적용. ②스윕(`PortalUploadSweepTxService`)에 **종결 세션 보존기간(retention) 정리**를 추가 — `CANCELLED` 이고 `mdfcn_dt < now - N일` 인 행 조건부 벌크 삭제. `COMPLETED` 는 `uld_sn` 추적 가치가 있으니 보존기간을 따로 잡을 것. ⚠ 구현은 하지 않음.

### [F-ISSUE-87] TC-TUS-020 — 낙관적 락 충돌 복원 분기(`truncateTo`)가 비관적 락에 가려 실행되지 않는 사실상 dead branch
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스는 동시 PATCH 시 `OptimisticLockingFailureException` → 409 + **기록 시작 오프셋으로 truncate 복원**을 기대한다. 방어 코드가 실제로 도는지, 아니면 다른 계층이 먼저 막는지가 명확해야 유지보수 시 잘못된 안전감을 갖지 않는다.
- **현재 동작(이슈 내용)**: `appendChunkTx` 진입 시 이미 `findByUldIdForUpdate`(PESSIMISTIC_WRITE)로 행을 잠그므로(`TxService:71-73`) 동일 세션의 PATCH 는 **완전 직렬화**된다. 후행 요청은 락 획득 후 `expectedOffset != session.getOffsetBytes()` 에 걸려 `TxService:98-100` 에서 409 로 끝나고, `@Version` 충돌은 발생할 수 없다.
  ```java
  // TxService:109-115 — 실동작에서 미발화
  } catch (OptimisticLockingFailureException e) {
      TusChunkStore.truncateTo(session.getFilePathNm(), expectedOffset);
      log.warn("[PortalTus] concurrent PATCH conflict uldId={}", uldId);
      throw new CustomException(ErrorCode.CONFLICT, "동시 업로드 요청이 충돌했습니다. 재시도하세요.");
  }
  ```
  실측: offset=0 동시 PATCH 3건 → 204×1 + 409×2, 두 409 의 메시지는 모두 `Upload-Offset 이 서버 상태와 일치하지 않습니다`(=offset 불일치 경로). backend 로그에 `concurrent PATCH conflict` **0건**.
- **재현/확인 경로**:
  ```bash
  for i in 1 2 3; do ( curl -s -w "req$i:<%{http_code}> " -X PATCH -H "Authorization: Bearer $T" \
      -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 0" -H "Content-Type: application/offset+octet-stream" \
      --data-binary @1mb.bin $B/v1/portal/uploads/tus/$S ) & done; wait
  docker logs klid-backend 2>&1 | grep -c "concurrent PATCH conflict"   # → 0
  ```
- **영향**: 기능·보안 영향 없음(결과는 정확히 1건만 성공, 파일 무결). 다만 ①테스트 카탈로그의 전제(TC-TUS-020 이 인용한 `TxService:109-115`)와 실제 방어 계층이 어긋나고 ②커버리지가 영원히 도달하지 못하는 분기가 남아, 향후 누군가 비관적 락을 제거하면 "낙관적 락이 받쳐준다"는 잘못된 전제로 회귀를 만들 수 있다.
- **수정 방향(제안)**: 둘 중 택일 — ⓐ현 구조 유지 + 해당 catch 블록에 "PESSIMISTIC_WRITE 로 도달 불가한 최후 방어" 주석 명시 + 카탈로그 TC-TUS-020 의 근거를 `TxService:98-100`(offset 불일치 409)로 정정, ⓑ 비관적 락을 유지하는 한 낙관적 락 catch 를 제거. **ⓐ 권장**(다중 노드/락 타임아웃 상황의 심층방어 가치는 남는다). ⚠ 구현은 하지 않음.

### [F-ISSUE-88] TC-TUS-022/027 — 완료 결과(uldSn·completed)가 HTTP 응답에 노출되지 않고, FE 도 완료 시 목록을 무효화하지 않아 업로드한 영상이 화면에 안 보인다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 서비스가 완료 시 `uldSn` 을 산출하므로(`PortalTusPatchResult(newOffset, completed, uldSn)`) 클라이언트는 어떤 자산이 만들어졌는지 알 수 있어야 하고, 최소한 사용자가 방금 올린 영상이 "내 업로드" 목록에 즉시 나타나야 한다.
- **현재 동작(이슈 내용)**: 컨트롤러가 `result.completed()`·`result.uldSn()` 을 **버리고** offset 만 반환한다.
  ```java
  // PortalTusUploadController.java:136-142
  PortalVideoUploadService.PortalTusPatchResult result =
          uploadService.appendChunk(uldId, requireUser(actor), uploadOffset, in, contentLength);
  return ResponseEntity.noContent()
          .header(H_RESUMABLE, TUS_VERSION)
          .header(H_UPLOAD_OFFSET, String.valueOf(result.newOffset()))   // completed/uldSn 미노출
          .build();
  ```
  실측: 완료 청크 PATCH → `HTTP/1.1 204` + `Upload-Offset: 3390` 뿐(본문 없음, 커스텀 헤더 없음). DB 에는 `uld_sn=57` 이 정상 기록됨.
  FE 쪽도 보완이 없다 — `PortalUploadPage.tsx:95-99` 의 `onVideoStart` 는 `tus.start(...).catch(() => undefined)` 만 하고 **`queryClient.invalidateQueries` 를 호출하지 않으며**, 목록 훅 `usePortalUploads`(`hooks/usePortalUploads.ts:17-29`)의 폴링은 **이미 캐시에 있는 목록에 진행중 자산이 있을 때만** 동작한다(`pollIntervalFor`: 전부 종결이면 `false`). 즉 목록이 전부 READY/FAILED 인 상태에서 영상을 새로 올리면 **폴링도 무효화도 없어 새 자산이 화면에 나타나지 않는다**(이미지 업로드는 `useUploadImages.ts:31` 에서 `invalidateQueries` 를 한다 — 경로별 비대칭).
- **재현/확인 경로**:
  ```bash
  # BE: 완료 응답에 uldSn 이 없다
  curl -s -D - -o /dev/null -X PATCH -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" \
    -H "Upload-Offset: 2000" -H "Content-Type: application/offset+octet-stream" \
    --data-binary @chunk2.bin $B/v1/portal/uploads/tus/$S
  # → HTTP/1.1 204 / Upload-Offset: 3390  (uldSn 없음)
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select uld_id,stts_cd,uld_sn from ls_portal_tus_uld where uld_id='$S';"
  # → COMPLETED | 57
  ```
  FE: 목록의 모든 자산이 READY 인 상태(`/portal/uploads`)에서 영상을 업로드 → 진행률 100% 도달 후에도 목록에 새 행이 추가되지 않음(수동 새로고침 필요).
- **영향**: 기능/UX. 업로드가 성공했는데 화면상 실패처럼 보인다. TUS 204 규약을 지키려는 의도는 타당하나, 그 경우 **클라이언트 측 후속 동선(목록 무효화)** 이 반드시 있어야 하는데 양쪽 다 빠져 있다.
- **수정 방향(제안)**: ⓐ최소안 — FE `PortalUploadPage.onVideoStart` 의 성공 콜백에서 `qc.invalidateQueries({ queryKey: PORTAL_KEYS.all })`(이미지 경로와 동일 패턴) 호출. ⓑ보강안 — BE 가 완료 시 커스텀 헤더(예: `X-Portal-Uld-Sn`)를 함께 내려 클라이언트가 생성된 자산을 직접 지목할 수 있게 한다(204 본문 없는 TUS 규약과 양립). **ⓐ 는 필수, ⓑ 는 선택**. ⚠ 구현은 하지 않음.

### [F-ISSUE-89] F-8 전반 — TUS 컨트롤러 계층 자동 테스트가 0건이라 헤더 프로토콜·동시성·만료가 회귀 무방비
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: TUS 는 **전적으로 HTTP 헤더로 동작하는 프로토콜**이라 서비스 단위 테스트만으로는 계약을 보호할 수 없다. 상태코드·헤더 조합(201+Location, 204+Upload-Offset, 412, 413, 410, 409, no-store)이 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: F-8 관련 자동 테스트는 `PortalVideoUploadServiceTest.java`(11 테스트, 서비스 계층 + fake repository) **단 1 파일**이다. `grep -rln "PortalTus|PortalVideoUpload|portal/uploads/tus" backend/src/test` 결과에 **MockMvc/`@WebMvcTest`/`@SpringBootTest` 기반 컨트롤러·IT 테스트가 없다**. 결과적으로 F-8 32건 중 **21건(TC-001·002·003·006·008·009·010·011·013·015·016·017·018·019·020·024·026·028·029·030·031)이 미커버**다. 미커버 항목에는 이번 검증에서 실제로 결함이 나온 지점(Tus-Resumable 부재 통과=F-ISSUE-83, Location 조립=F-ISSUE-82, CANCELLED HEAD=F-ISSUE-85)이 전부 포함된다.
- **재현/확인 경로**:
  ```bash
  grep -rln "PortalTus\|PortalVideoUpload\|portal/uploads/tus" backend/src/test
  # → PortalUploadPropertiesBindingTest / PortalUploadSweepIT / PortalUploadSweepTxServiceTest
  #    PortalVideoUploadServiceTest / V111PortalTusUploadMigrationTest   (컨트롤러 테스트 없음)
  grep -c "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/portal/PortalVideoUploadServiceTest.java   # → 11
  ```
- **영향**: 품질/회귀. 헤더 계약이 코드 리팩터로 조용히 깨져도 baseline(4,755 tests) 이 전부 통과한다. 동시성(TC-020/027/028/031)·만료(TC-013)는 Testcontainers 없이는 검증 자체가 불가능한데 IT 가 없다.
- **수정 방향(제안)**: ①`PortalTusUploadControllerTest`(`@WebMvcTest` + `@WithMockUser` 또는 TokenClaims 주입)로 헤더 프로토콜 12건(TC-001·002·003·008·009·010·011·015) 고정. ②`PortalTusUploadIT`(Testcontainers PostgreSQL)로 재개 시퀀스·offset 불일치 409·만료 410·동시 PATCH 직렬화·완료 멱등(TC-013·016·018·019·020·022·027·029·030·031) 커버. ③F-ISSUE-83/85 수정 시 회귀 가드를 함께 추가. ⚠ 구현은 하지 않음.

---

## 부기 — 확증편향 반증에서 "문제 없음"으로 확인된 항목

| 반증 시도 | 결과 |
|-----------|------|
| 타 사용자 세션 하이재킹(HEAD/PATCH/DELETE) | 3경로 전부 403. `isOwnedBy` 가 HEAD 는 `getForOwner`, PATCH 는 `appendChunkTx`, DELETE 는 `cancel` 에서 **각각 독립 검사**(한 곳에 몰지 않음) |
| 세션 ID 추측 | `UUID.randomUUID()`(v4, SecureRandom). 미존재 uldId → 404, 잘못된 UUID 문자열 → 400 (존재 여부 오라클 없음) |
| 역할/채널 우회 | WORKER(2001)·REVIEWER(1001) INTERNAL 토큰 → POST/OPTIONS 모두 **403**, 무토큰 → **401**. `@PreAuthorize("hasRole('PORTAL_USER')")` + SecurityConfig 채널 격리 이중 방어 실동작 확인 |
| 확장자 우회 | `A.MP4`(대문자) 201 정상 / `a.webm`·`a.mkv`(`EXT_TO_MIME` 상수엔 존재) → **400**. allowlist(`properties.allowedExtensions()`)가 최종 게이트라 상수 잔재로는 우회 불가 |
| 매직바이트 polyglot | `ftyp` 만 위장한 파일(`corrupt.mp4`)은 매직바이트를 통과하지만 **ffprobe 2차 게이트에서 400**. 브랜드 allowlist(`VideoMagicByteValidator.ALLOWED_BRANDS`) + box size 상한(1MB)도 정적 확인 |
| 부분 쓰기 오염 | 413(청크 상한)·400(잔여 초과) 거부 후 파일 크기·DB offset 모두 불변. `TusChunkStore.writeChunkAtomically` 의 `truncateTo` 롤백 + `advanceOffset` 미호출 경로 확인 |
| 검증 실패 시 세션 고착 | `finalizeRejected` 가 `REQUIRES_NEW` 라 400 예외로 롤백되지 않음 — 실측 3케이스 모두 DB 가 CANCELLED 로 **커밋 확정**됨(IN_PROGRESS 고착 0) |
| 임의 파일 삭제(CWE-22) | `TusChunkStore.deleteQuietly(path, storageRoot)` 가 `startsWith(storageRoot)` 재검증. 저장 경로 자체가 사용자 입력 미도달이라 이중 방어 |
| 완료 이벤트 중복 | 완료 7세션 ↔ ULD 7행 ↔ 프레임 7행 (1:1:1). 조건부 UPDATE 게이트 유효 |
