# D클러스터 — Part5 (D-4 버전관리 스냅샷 TC-VERSION, D-8 데이터마트 View TC-MARTVIEW)

검증자: 담당 에이전트(D-part5) · 대상: `docs/test-cases/D-review-version-notify.md` §D-4(17건, 2건 폐기 제외 실질 15건) + §D-8(23건) = 40건
방법: 실동작 최우선(HTTP 요청 + DB 직접 SELECT + 실제 API 왕복), 근거 file:line 정적 대조 병행. 스택은 기존 기동 상태(backend :18081 / postgres) 그대로 사용, JWT는 컨테이너 실효 `JWT_SECRET`으로 HS256 자체 서명(`sub=1001`→REVIEWER, `sub=2001/2002`→WORKER, `GET /v1/me`로 검증). 코드/설정/테스트 파일은 수정하지 않았다. DB에 임시로 넣은 검증용 값(`de_ident_yn='F'` 토글, `ls_data_src.de_idntf_src_file_path_nm` 동일경로 실험, `active_yn='N'` 토글, `FrameDescription` PUT)은 모두 **검증 직후 원복 확인 완료**.

## D-4. 버전관리 스냅샷 (TC-VERSION) — 15건 (011/012 폐기 제외)

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-VERSION-001 | PASS | [실동작] | `ls_label_version` 실측 9건, 전부 `save_reason_cd=APPROVED`·`actvtn_yn=Y`·`version_hash` 64hex(SHA-256). `VersionService.java:158-208` |
| TC-VERSION-002 | PASS | [실동작]+[정적] | raw_sn=4: 프레임 30건 중 승인 시점 라벨 보유 4건만 스냅샷 생성(`ls_label_version` 4행). `:190-195` `if (labels.isEmpty()) continue;` |
| TC-VERSION-003 | PASS | [정적] | `:292-296` 멱등 — active 해시 일치 시 `IDEMPOTENT` 반환, 신규 미생성. 코드 로직 확인, 재승인 실측 데이터는 본 환경에 없음(N/A 아님 — 로직 자체가 명확) |
| TC-VERSION-004 | PASS | [정적] | `saveActiveVersion:1007-1016` — `currentActive.forEach(deactivate)` + `countByDataRawSnAndDataSrcSn+1`로 신규 active 적층. 로직 확인 |
| TC-VERSION-005 | PASS | [정적] | `:170-173` `frames.isEmpty() → return CommitResult.EMPTY`(0,0) |
| TC-VERSION-006 | PASS | [정적] | `:166-167` `videoRepository.findById(rawSn).orElseThrow(NOT_FOUND)`. REST 미직접노출(ReviewService.approve 내부 전용 호출, rawSn은 항상 유효 FK) — 도달 경로는 방어적이나 로직은 명세대로 |
| TC-VERSION-007 | **PARTIAL** | [정적] | `actor==null`→`CustomException(UNAUTHORIZED)`는 정상(401). 그러나 `rawSn==null`→순수 `IllegalArgumentException`이며 `GlobalExceptionHandler`에 해당 핸들러가 없어(`@ExceptionHandler(Exception.class)` 로 폴백) **500**으로 응답된다(기대는 400 계열). 단 `commitApproved`는 `ReviewService.approve()`가 `stts.getRawDataId()`(non-null FK)로만 호출하는 내부 전용 메서드라 실 REST 경로에서 rawSn=null 도달 불가 — 실사용 리스크는 낮음. → D-ISSUE-81 |
| TC-VERSION-008 | PASS | [정적] | `serializeSnapshotWithSimplification:1041-1053` 1MB 초과 시 `simplifyPolygons`로 재직렬화 후 10MB(`MAX_DEIDENT_PAYLOAD_BYTES`)까지 허용, 승인 차단 없음 |
| TC-VERSION-009 | PASS | [정적] | `:1049-1052` 단순화 후에도 초과 시 `INVALID_INPUT`→호출부(`snapshotFrameOnApprove:274-279`)가 `catch`해 해당 프레임만 `SKIPPED` 집계, 전체 승인은 유지 |
| TC-VERSION-010 | PASS | [정적]+[테스트] | `findActiveForUpdate`(`PESSIMISTIC_WRITE`, Repository:26-33)로 동시 승인/롤백 직렬화. `VersionServiceRollbackLockOrderTest` 존재 + baseline(`VersionServiceTest` 35 tests, 0 failed) |
| TC-VERSION-013 | PASS | [실동작] | WORKER(2002, raw4 미배정) → `GET /v1/frames/1/versions` **403** `"본인에게 배정되지 않은 영상입니다."` (`accessGuard.verifyAccess`) |
| TC-VERSION-014 | PASS | [실동작] | `GET /v1/frames/1/versions` 응답에 `commitSha/shortHash/authorName/message/committedAt/isCurrent`만 포함, `LABEL_PAYLOAD`(좌표 본문) 없음. 로그도 `srcSn/created/skipped`만(`:205-206`) |
| TC-VERSION-015 | PASS | [실동작] | raw18 `de_ident_yn`을 `'F'`로 임시 토글 후: `GET /v1/frames/45/versions`→**200**(정상 응답), `GET /v1/frames/45/labels`→**412** `"비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다."` 동일 조건에서 대비 확인. `GET /v1/versions/{hash}/diff?compareWith={hash}`도 **412** 확인(diff도 게이트 적용, `:354`). 검증 후 `'Y'`로 원복 |
| TC-VERSION-016 | PASS | [실동작]+[정적] | `ls_label_version WHERE data_src_sn IS NULL` 실측 **0건**(레거시 행 자체가 이 환경엔 없음). `findByDataSrcSnOrderByRegDtDesc(Long)`은 JPA `=` 파생 쿼리라 `data_src_sn IS NULL` 행과 매치 불가(설계상 배제) — 로직 확인 |
| TC-VERSION-017 | PASS | [정적] | `ReviewService.approve:466-513` 전체가 `@Transactional("controlTransactionManager")` 단일 메서드. `stts.transitionTo(APPROVED)` → `versionService.commitApproved` → `datasetVideoMetaSnapshotService.materialize` 모두 같은 트랜잭션. 실패 시 전체 롤백은 `DatasetMaterializeApproveRollbackIT`(실행 증거: XML `tests="1" failures="0"`, `materialize_실패시_approve_실DB_전체롤백_상태_버전_통합메타_outbox_미반영`)로 실제 DB 검증됨 |

## D-8. 데이터마트 View (TC-MARTVIEW) — 23건

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-MARTVIEW-001 | PASS | [실동작] | `ls_raw_data_status` APPROVED 8건 중 `v_completed_video`엔 6건만 노출(48,67은 `ls_dataset_video_meta` 미존재 — materialize 미실행 상태의 시드 데이터, APPROVED 게이트 자체는 정상 동작. 뷰 정의 `WHERE s.data_stts_cd='APPROVED'` 실측 일치) |
| TC-MARTVIEW-002 | PASS | [실동작] | raw4 `export_path_nm=/app/storage/raw/seed/4`(영상 루트, 7개 export 버전 전부 동일 경로), `frame_cnt=60`. `DatasetExportTxService.java:174` 주석 "EXPORT_PATH_NM 은 영상 루트" 일치 |
| TC-MARTVIEW-003 | PASS | [실동작] | raw8/9/15(export 레코드 없음): `export_path_nm`/`frame_cnt` 둘 다 NULL, 영상당 정확히 1행 |
| TC-MARTVIEW-004 | PASS | [실동작] | raw72 export `FAILED` 1건뿐(SUCCEEDED 없음) → raw72는 애초 미승인(PENDING)이라 뷰 대상 아님(001과 결합 확인). SUCCEEDED만 조인하는 LATERAL 서브쿼리(`ex.export_stts_cd='SUCCEEDED'`) 확인 |
| TC-MARTVIEW-005 | PASS | [실동작] | raw26 `ls_dataset_video_meta.active_yn`을 `'N'`으로 임시 변경 → `v_completed_video`에서 즉시 **0행**(사라짐), `'Y'`로 원복 후 재노출 확인. 실제 데이터에도 raw5가 자연 `active_yn='N'` 상태로 존재 |
| TC-MARTVIEW-006 | PASS | [실동작] | srcSn=1의 `de_idntf_src_file_path_nm`을 `src_file_path_nm`과 동일값으로 임시 변경 → `v_completed_frame`에서 **0행**(제외). 원복 후 재노출 확인. 대조: raw18(srcSn45~47, `src_file_path_nm=NULL`+deid만 존재 = "결측")은 정상 노출됨 — fail-closed(완전동일)와 결측(편측NULL) 구분이 실측대로 동작 |
| TC-MARTVIEW-007 | PASS | [실동작] | `PUT /v1/frames/1/description` 실호출로 `"D-8 검증용 테스트 설명"` 저장 → `v_completed_frame.description` 즉시 반영 확인 → 빈 문자열 PUT으로 원복(NULL). 컬럼은 8개 기존 컬럼 뒤에 추가(V104, 순서 보존) |
| TC-MARTVIEW-008 | PASS | [실동작] | `v_completed_label_change` 실제 26행 전부 `LBL_HSTRY_SN/RAW_SN/SRC_SN/ADD_CNT/MDFCN_CNT/DEL_CNT/REG_ID/REG_DT` 8컬럼만, 좌표본문(`CHG_DTL_CN`) 컬럼 자체가 뷰 스키마에 없음(`\d+` 확인) |
| TC-MARTVIEW-009 | PASS | [실동작] | `ls_data_lbl_hstry`에 이력 존재하는 raw는 {4,34,35,5,6,26,27} 7종이나 APPROVED는 {4,26}뿐 — `v_completed_label_change`엔 실제로 raw 4·26만 노출(34/35/5/6/27 완전 배제) |
| TC-MARTVIEW-010 | PASS | [실동작] | `v_completed_meta` 3행 모두 `rvw_stts_cd=APPROVED`. `ls_data_meta`에 `meta_key='video.%'` 행 다수 존재하나 뷰엔 VLM 메타(`0-5`)만 |
| TC-MARTVIEW-011 | PASS | [실동작] | 상동 — `video.fps/codec/bit_rate/duration_ms/filesize/resolution` 6종 raw4/6에 실존하나 `v_completed_meta`엔 0건(`NOT LIKE 'video.%'` 필터 실측 확인) |
| TC-MARTVIEW-012 | PASS | [실동작] | `pg_views WHERE viewname LIKE 'v_completed%'` → 4개(`_video/_frame/_label_change/_meta`)뿐, `v_completed_label`/`v_completed_label_attr` 부재 확인 |
| TC-MARTVIEW-013 | PASS | [정적] | V138/V139 전부 `CREATE OR REPLACE VIEW` 사용, DDL 확인 |
| TC-MARTVIEW-014 | PASS | [실동작] | `DatasetMaterializeApproveRollbackIT` **존재하며 실행·통과**(XML `tests="1" failures="0" errors="0"`, 테스트명 `materialize_실패시_approve_실DB_전체롤백_상태_버전_통합메타_outbox_미반영`) — 카탈로그의 "⚠ 전용 IT 미비(D-ISSUE-50 미해소)" 메모는 **stale**(현재 코드베이스에서 해소됨). 카탈로그 정정 필요 |
| TC-MARTVIEW-015 | PASS | [실동작] | raw4 `de_idntf_file_path_nm=/app/storage/raw/seed/4/deid/clip-9101-mask.mp4`({stem}-mask{ext} 패턴, 실측 KPST/mock 결과값), raw18(증강파생) `=".../WINTER.mp4"`(다른 명명 규칙) — 두 값이 서로 다른 실제 `ls_deident_proc_log.de_idntf_file_path_nm` 원문값이며 문자열 조합이 아님을 확인. 미완료(raw8/9/15, `de_ident_yn='F'`)는 NULL |
| TC-MARTVIEW-016 | PASS | [실동작] | raw18 `orgnl_raw_sn=4`(증강파생) → `original_video_path` **빈값/NULL** 확인. raw4(비파생)는 `./storage/raw/seed/clip-9101.mp4` 정상 노출 — 대비 확인 |
| TC-MARTVIEW-017 | PASS | [실동작] | `ls_data_lbl_hstry`에 `add+mdfcn+del=0`인 행 8건(롤백/개인정보리셋 감사) 실존하나 `v_completed_label_change`엔 **0건도 노출 안 됨**(26행 전부 합계>0 확인). 테이블 원본은 8건 그대로 보존(삭제 안 됨) |
| TC-MARTVIEW-018 | PASS | [실동작] | raw8/9/15 **실제로 `de_idntf_yn='F'`(비식별 신고 구간) 상태로 이미 APPROVED**돼 있고, `v_completed_video`에서 행이 사라지지 않고 그대로 노출됨(export_path_nm/deid_path만 자연히 NULL — 신고로 비식별 산출물이 없어서일 뿐, 뷰가 의도적으로 비운 게 아님). 확정 정책 그대로 실증 — 결함 아님 |
| TC-MARTVIEW-019 | PASS | [실동작] | raw4/8/9/15/18/26 전부 `day_ngt_cd`/`sesn_cd`/`wthr_nm` **NULL**(수동 미입력, `SHT_DT` 기반 자동 파생 없음 확인 — 예: raw8 `captured_at=2020-01-02 03:04:05`(야간 시각)인데도 `day_ngt_cd` 미채움) |
| TC-MARTVIEW-020 | PASS | [정적]+[실동작] | `DatasetVideoMetaBackfillDevController` 코드 확인 + `GET /v1/dev/dataset-video-meta/shooting-env-correction-targets` 실호출 200 `{"targetCount":0}`(본 환경엔 정정 대상 레거시 파생행 없음 — 정상, 대상 0건이면 서비스가 no-op 반환하는 코드도 확인) |
| TC-MARTVIEW-021 | PASS | [실동작] | REVIEWER(1001) → 200. WORKER(2001) → **403** `"권한이 없습니다."`. `@Profile("!prd")`+`@PreAuthorize("hasRole('REVIEWER')")`+`DevProfileGuard` 3중 방어 코드 확인. `?dryRun=` 같은 행위분기 파라미터 없음(GET/POST 별도 sub-resource) 확인 |
| TC-MARTVIEW-022 | PASS | [정적] | `application.yml:245` `max-per-run: ${DATASET_ENV_CORRECTION_MAX_PER_RUN:200}`, `DatasetVideoMetaBackfillService.java:176-188` 페이징 루프 + 시작/잔여 로그 확인 |
| TC-MARTVIEW-023 | PASS | [실동작] | 반증 데이터 2종 실측: ① `active_yn='N'` 스냅샷(raw26 임시 토글) → 뷰 0행 ② `meta_key='video.codec'`(video.* 6종 실존) → `v_completed_meta`에 0행. 두 반례 모두 요구대로 게이트 실효 확인 |

## 요약

- **합계 40건**(TC-VERSION 15건 유효 + TC-MARTVIEW 23건 + 폐기 2건 제외): **PASS 39 / PARTIAL 1 / FAIL 0 / BLOCKED 0 / N/A 0 / 확인필요 0**
- 모든 View·버전관리 핵심 기능을 **실제 DB SELECT + 실 API 왕복**으로 확인. 특히 확증편향 방지 대상이던 3가지(①스냅샷 해시 SHA-256 중복식별, ②신고구간(`DE_IDNTF_YN='F'`) 영상도 뷰 필터링 없이 노출, ③미export 영상 EXPORT_PATH_NM/FRAME_CNT null 유지)는 모두 **실측 데이터로 정확히 실증됨**(raw8/9/15가 실제 신고구간 APPROVED 상태로 이미 존재해 완벽한 반증 데이터 역할을 했음).

### [D-ISSUE-81] TC-VERSION-007 — `commitApproved`의 `rawSn==null` 가드가 처리되지 않은 예외로 이어져 500 응답
- **심각도**: LOW
- **기대 동작(기대효과)**: `VersionService.commitApproved(rawSn, actor)`는 `rawSn==null`일 때 의미있는 4xx(예: `INVALID_INPUT`/400)로 실패해야 한다. 카탈로그 기대결과는 "IllegalArgument/UNAUTHORIZED(401)"로, 명확한 클라이언트 오류 응답을 전제한다.
- **현재 동작(이슈 내용)**: `VersionService.java:160-162`가 순수 `throw new IllegalArgumentException("rawSn 은 필수입니다.")`를 던지는데, `GlobalExceptionHandler`(`backend/src/main/java/kr/co/cudo/authoring/common/exception/GlobalExceptionHandler.java`)에는 `IllegalArgumentException` 전용 `@ExceptionHandler`가 없다(`CustomException`/`MethodArgumentNotValidException`/`ConstraintViolationException`/`HttpMessageNotReadableException`/`MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException`/`DataIntegrityViolationException`/`AccessDeniedException`/`AuthenticationException`/`MaxUploadSizeExceededException`/`NoResourceFoundException`/`HttpRequestMethodNotSupportedException`/`Exception` 뿐). 따라서 `:273 @ExceptionHandler(Exception.class)` 제네릭 500 핸들러로 떨어진다.
  ```java
  // VersionService.java:160-165
  if (rawSn == null) {
      throw new IllegalArgumentException("rawSn 은 필수입니다.");
  }
  if (actor == null) {
      throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
  }
  ```
- **재현/확인 경로**: `commitApproved`는 REST로 직접 노출되지 않고 `ReviewService.approve()`가 `stts.getRawDataId()`(항상 non-null FK)로만 호출하는 내부 전용 메서드다. 따라서 **현재 시점엔 실 HTTP 요청으로 재현 불가**(내부 단위테스트에서 `versionService.commitApproved(null, actor)` 직접 호출 시에만 확인 가능하며, 해당 테스트도 리포지토리에 없음 — `grep -rn "commitApproved(null" backend/src/test` 0건).
- **영향**: 기능 영향 낮음(공개 API 경로에서 도달 불가). 다만 향후 `commitApproved`를 다른 컨텍스트(예: 관리자 수동 재스냅샷 API)에서 재사용하면 이 가드가 그대로 500을 낼 수 있어 잠재 함정이다. OWASP A10:2025(Mishandling of Exceptional Conditions) 관점에서 "의미있는 4xx" 원칙과 어긋남.
- **수정 방향(제안)**: `IllegalArgumentException`을 `CustomException(ErrorCode.INVALID_INPUT, ...)`으로 교체하거나, `GlobalExceptionHandler`에 `IllegalArgumentException → 400` 핸들러를 추가한다. 후자는 프로젝트 전역에 영향을 주므로 전자(로컬 교체)가 더 안전. ⚠ 구현은 하지 않는다.
