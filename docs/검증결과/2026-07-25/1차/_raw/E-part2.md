# E-part2 — 해상도 파생 오케스트레이션 / 예약·확정 (E-4 · E-5)

> 대상: `docs/test-cases/E-augment-resolution-export-meta.md` 66~128행 (TC-RESL-001~017, TC-RESL-030~065, 53건)
> 환경: 로컬 풀스택(klid-backend:18081 `/api`, klid-postgres, klid-ai-server, klid-mock-server, klid-frontend), DB 스키마 `public`
> 방식: 실제 API 호출(REVIEWER dev 토큰) + DB/파일시스템 실측 + 정적 대조 + 테스트 커버 확인
> 실동작 대상: 신규 파생 생성 12건(rawSn 30,32~39 / 부모 13), 기존 파생 실측(15,16,18,19 / 부모 14,17). **rawSn=26 은 미사용**(E-6 참조 데이터 보호)

## E-4. 해상도 파생 오케스트레이션 (VideoResolutionService / VideoController)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-RESL-001 | 프리셋 미지정 3종 전체 생성 | PASS | [실동작] `POST /v1/videos/13/resolution` 바디 생략 → 201, derivatives 3건 CREATED(rawSn 33/34/35), DB `ls_data_raw` 3행 `orgnl_raw_sn=13`·`PENDING`·`de_ident_yn='N'` INSERT 확인 | `VideoResolutionServiceTest`·`VideoResolutionControllerTest` "presets_미지정_바디시_기본_3종생성_201" | 응답 CREATED = **예약 성공**이며 비동기 확정 결과가 아님(E-ISSUE-24) |
| TC-RESL-002 | 프리셋 부분 지정 | PASS | [실동작] `{"presets":["RESL_480P"]}` → 1건(rawSn 32) / `["RESL_1080P","RESL_720P"]` → 2건(36/37) | "특정_프리셋_목록_지정시_그_목록만_생성된다" | — |
| TC-RESL-003 | presets 중복 제거 | PASS | [실동작] `["RESL_1080P","RESL_1080P"]` → derivatives 1건(rawSn 30). `ResolutionChangeRequest.java:32-37` distinct | 간접(서비스 테스트) | — |
| TC-RESL-004 | 원본 동일 해상도 프리셋 스킵 | PASS | [실동작] rawSn=17(실측 1920×1080) 3종 요청 → 로그 `preset skipped (same resolution) rawSn=17 preset=RESL_1080P 1920x1080`, 결과 목록에서 제외 | "원본과_동일_해상도_프리셋은_스킵된다" | 원본 해상도는 JPEG SOF0 파싱으로 실측 대조 |
| TC-RESL-005 | 전부 스킵 시 400 | PARTIAL | [정적] `VideoResolutionService.java:113-117` 확인. **실동작 미재현** — 3 프리셋과 모두 동일 해상도인 영상이 환경에 부재 | "모든_대상_프리셋이_원본과_동일해상도면_400_INVALID_INPUT" | 프리셋이 3종뿐이라 논리적으로 재현 불가에 가까움 |
| TC-RESL-006 | 전부 실패 시 500 | PASS | [실동작] rawSn=17 3종 → 500 `{"errorCode":"INTERNAL_ERROR","message":"요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."}`, 내부 사유 미노출. 로그 `all presets failed rawSn=17 attempted=2` | "모든_프리셋_생성이_실패하면_500이다" | — |
| TC-RESL-007 | 부분 실패 격리 201 | PASS | [실동작] 동시 2요청(A=[480P], B=[480P,1080P]) → B 응답 201 + `[{rawSn:null,goalResCd:"RESL_480P",status:"FAILED"},{rawSn:39,...,status:"CREATED"}]` 혼재 확인 | "한_프리셋_생성실패가_다른_프리셋_생성을_막지않는다" | 실패 항목 rawSn=null 계약 준수 |
| TC-RESL-008 | 업스케일 허용 | PASS | [실동작] rawSn=13(실측 1080×1920 세로) → RESL_1080P/720P 모두 거부 없이 예약. 로그 `src=1080x1920 target=1920x1080`(scaleX=1.78 업스케일) | "업스케일_프리셋도_400없이_정상_생성된다" | 종횡비 미보존 → E-ISSUE-26 |
| TC-RESL-009 | 증강본/파생본 거부 | PASS | [실동작] rawSn=19(파생, orgnlRawSn=17) → 400 "원본 영상에만 해상도 변경 가능" | "증강본_orgnlRawSn_null아님_원본은_거부된다" | — |
| TC-RESL-010 | 미검수 영상 거부 | PASS | [실동작] rawSn=27(ASSIGNED) → 409 "검수 완료(APPROVED)된 영상만 해상도 변경할 수 있습니다." | "미검수_영상_요청시_409" | — |
| TC-RESL-011 | 영상 미존재 | PASS | [실동작] rawSn=999999 → 404 | "영상_미존재_시_404" | — |
| TC-RESL-012 | 프레임 해상도 확인 불가 | PARTIAL | [정적] `VideoResolutionService.java:97-100`. **실동작 미재현** — `readDimensions` 가 0/음수를 반환하는 손상 이미지 주입 불가(파일 수정 금지) | "srcW_srcH가_0이면_파생생성이_거부된다"(mock 기반) | mock 반환값 테스트라 실제 이미지 디코더 경로는 미검증 |
| TC-RESL-013 | 프리셋 enum 화이트리스트 | PASS | [실동작] `{"presets":["RESL_240P"]}` → 400 "요청 본문이 올바르지 않습니다."(Jackson 역직렬화 거부) | "화이트리스트_외_preset_값_요청_400" | 근거 라인 드리프트 → E-ISSUE-28 |
| TC-RESL-014 | WORKER/미인증 차단 | PASS | [실동작] 토큰 없음 → 401 / WORKER 토큰 → 403 `FORBIDDEN`. `VideoController.java:286 @PreAuthorize("hasRole('REVIEWER')")` | "미인증_401","REVIEWER가_아니면_403이다" | — |
| TC-RESL-015 | 응답 형태 계약 | PASS | [실동작] `{"derivatives":[{"rawSn":32,"goalResCd":"RESL_480P","targetW":854,"targetH":480,"status":"CREATED"}]}` — 내부 파일경로·dataAugSn·EXPORT_SN 미노출 | "응답에_생성된_rawSn목록과_상태가_포함된다" | — |
| TC-RESL-016 | measureFirstFrame 경로 CWE-22 | PASS | [실동작] rawSn=5/6(프레임 경로가 `/Users/ck/...` = 컨테이너 base 밖) → 400 "원본 프레임 경로가 허용된 저장 경로를 벗어납니다." | "상위경로_traversal(..)_은_여전히_INVALID_INPUT으로_차단된다","raw도_deid도_아닌_경로는_INVALID_INPUT" | — |
| TC-RESL-017 | deid 프레임 경로 base 허용 | PASS | [실동작] rawSn=17 프레임 `de_idntf_src_file_path_nm=/app/storage/deidentified/frames/deid/17/frame-0.jpg`(deid base 절대경로)로 실측 통과 → 파생 생성 진행. `VideoResolutionService.java:195-201` 두 base 허용 | "비식별_프레임_경로는_deid_base로_통과하여_해상도변경이_성공한다" | — |

## E-5. 해상도 파생 예약/확정 (Reservation·Snapshot·Materialize·Persist·Runner)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-RESL-030 | 예약행 PENDING 커밋+새 RAW | PASS | [실동작] 로그 `derivative reserved parentRawSn=13 newRawSn=30 dataAugSn=13 preset=RESL_1080P` → 직후 `[AsyncResolutionRunner] starting ... rawSn=30`(AFTER_COMMIT). DB `ls_data_aug` PENDING 행 + `ls_data_raw` 신규행 확인 | `ResolutionReservationPersisterTest`, `ResolutionDerivativeFlowIntegrationTest` | — |
| TC-RESL-031 | 부모 잠금하 PII 게이트 | PARTIAL | [정적] `ResolutionReservationPersister.java:69-74`. **실동작 미재현** — APPROVED + `de_ident_yn≠'Y'` + base 내 프레임을 동시에 만족하는 영상 부재(5/6은 경로 가드가 선행 차단) | "부모가_비식별신고로_F전이되면_동기게이트에서_파생생성이_거부된다","비식별미완료_deIdntfYn_아님_부모면_예약단계에서_거부된다" | 단위/IT 커버 있음 |
| TC-RESL-032 | 대표프레임 SRC_SN null fail-fast | PASS | [정적] `:78-80` INSERT 이전 fail-fast | "srcSn_null이면_LS_DATA_AUG_INSERT전에_INVALID_INPUT을_던진다" | 상위 `firstFrame()` 이 항상 non-null 반환이라 방어적 |
| TC-RESL-033 | 부분유니크 중복 예약 차단 | PASS | [실동작] ①rawSn=17 720/480 재요청 → `DataIntegrityViolation`→CONFLICT(스택 `ResolutionReservationPersister.java:95`) ②동시 2요청 중 1건만 성공(A=500, B=201). DB 인덱스 실측 `uk_ls_data_aug_resl UNIQUE btree (src_sn, aug_type_cd) WHERE aug_type_cd ~~ 'RESL\_%'` (V125) | "동일_parent_preset_예약이_UK위반이면_CONFLICT로_거부된다","같은영상_같은프리셋_동시요청시_1건성공_1건CONFLICT" | — |
| TC-RESL-034 | 출력 경로 CWE-22 | PASS | [정적] `:122-128` 가드 존재. 단 `preset.name()` 은 enum 바인딩이라 traversal 문자 유입 **경로가 도달 불가**(방어적 잔존) | 전용 테스트 없음 | E-ISSUE-30 |
| TC-RESL-035 | Phase A 멱등 skip | PASS | [정적] `ResolutionSnapshotService.java:88-92` | IT "확정된_파생을_PhaseA가_멱등skip하고_프레임이_중복생성되지_않는다" | — |
| TC-RESL-036 | Phase A 부모 PII 재검증 | PASS | [정적] `:94-106` 부모 `findByRawSnForUpdate` + `'Y'` 재검증 → CONFLICT | IT "예약후_async확정전에_부모가_비식별신고로_F전이되면 ... 파생이_FAILED된다" | — |
| TC-RESL-037 | Phase A 비식별 비디오 경로 부재 | PARTIAL | [정적] `:126-130` NOT_FOUND. 실동작 미재현(대상 부모 전부 SUCCESS procLog 보유) | **전용 테스트 미발견** | E-ISSUE-29 |
| TC-RESL-038 | Phase A 프레임 0건 fail-fast | PASS | [정적] `:137-140` | "부모_프레임이_0건이면_fail_fast로_거부한다(#9)" | — |
| TC-RESL-039 | Phase A 중복 videoFrameNo fail-fast | PARTIAL | [정적] `:166-171` `seenFrameKeys` 중복 감지 | **전용 테스트 미발견** | E-ISSUE-29 |
| TC-RESL-040 | Phase A deid 프레임 경로 strict | PASS | [실동작] rawSn=13(프레임 11건 전부 `de_idntf_src_file_path_nm` NULL) 파생 요청 → Phase A `deidFrameSourceStrict` CONFLICT → Phase B 미실행(파일 0건 생성), 파생 RAW `de_ident_yn='N'`·`FAILED` 유지. **원본 픽셀 복제 + 'Y' 위장 차단 실증** | 전용 DisplayName 미발견(실동작으로 대체 검증) | 강한 실증 |
| TC-RESL-041 | Phase B 비디오 복사+프레임 리스케일 | PARTIAL | [실동작] rawSn=18/19(부모 17) — 비디오 1개 + 프레임 12개 산출, JPEG SOF0 파싱 실측 `18=1280×720`, `19=854×480`(목표 정확 일치). **단 산출물이 deid base 가 아닌 raw base(`/app/storage/raw/resolution/...`) 아래 생성** → E-ISSUE-21 | `ResolutionFileMaterializerTest` | 기능 정상 / 저장 위치 결함 |
| TC-RESL-042 | Phase B 원본 비식별 파일 부재 | PASS | [정적] `ResolutionFileMaterializer.java:49-51` NOT_FOUND + `finally` 게이트 반환 | "비식별_비디오원본이_없으면_NOT_FOUND로_실패하고_게이트를_반환한다" | — |
| TC-RESL-043 | Phase B 리사이즈 게이트(DoS) | PARTIAL | [정적] `ResizeConcurrencyGate` — `Semaphore(fair)`, `resize-max-concurrent:2`, `tryAcquire(5s)` 초과 시 429 TOO_MANY_REQUESTS. **실동작 동시부하 미재현** | acquire/release 호출 검증만(슬롯 포화·429 경로 미검증) | 초과 시 "대기 후 거부" 동작 자체는 미실증 |
| TC-RESL-044 | Phase C 확정 영속 | PARTIAL | [실동작] rawSn=18/19 — `ls_data_src` 12행 INSERT, `ls_data_aug` ACCEPTED 전이, `ls_data_raw.de_ident_yn='Y'`·`data_stts_cd='COMPLETED'`, `ls_deident_proc_log` SUCCEEDED 생성 전부 확인. **단 procLog `DE_IDNTF_FILE_PATH_NM`·`LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM` 에 raw base 경로가 기록** → E-ISSUE-21 | IT "해상도파생_finalize성공시_파생RAW_배치상태_COMPLETED_이고 ..." | 메타복사는 18/19 생성 시점 이후 커밋(27b6bb0d)이라 미실증 |
| TC-RESL-045 | 좌표 배율 재계산 라벨 복사 | PASS | [실동작] 부모17 라벨 13건 → 18/19 각 13건 복사. 실측 대조: `619.6822→413.1215`(×0.66667=1280/1920), `507.7863→338.5242`(×0.66667), 480P `→275.6295`(×0.444792=854/1920)·`→225.6828`(×0.44444). `ls_data_aug_lbl_map` `coord_recalc_yn='Y'`, `scale_x/scale_y=0.666667` 적재 확인 | `LabelCoordinateScalerTest` 21케이스(BBOX/POLYGON/SEGMENTATION/SKELETON 삼중값·업스케일·기형 JSON 거부) | 라벨 유형별은 단위테스트 커버, 실동작은 BBOX만 |
| TC-RESL-046 | 부모 라벨 0건 | PASS | [정적] `ResolutionPersistService.java:350-353` early return 0 | 전용 테스트 미발견(로직 자명) | — |
| TC-RESL-047 | Phase C stale PII 게이트 — 재신고 | PASS | [정적] `:286-294` capturedAt 이후 신고 존재 시 CONFLICT | IT "진짜_A~C창_PhaseB가_실제파일산출_후_스냅샷이후_부모_재비식별신고시_PhaseC가_CONFLICT하고_cleanup이_실제파일을_삭제하며 ..." | 실파일 산출까지 검증하는 강한 IT |
| TC-RESL-048 | Phase C stale — 비식별 경로 변경 | PASS | [정적] `:296-309` | "stale창_최신비식별procLog경로가_스냅샷과_다르면_CONFLICT로_abort한다(H-1_①경로게이트)" | — |
| TC-RESL-049 | Phase C stale — 파일 mtime 교체 | PARTIAL | [정적] `:311-321`. `IOException` 시 **보수적 통과(페일오픈)** — 로그만 남기고 진행 | **mtime 경로 전용 테스트 미발견** | E-ISSUE-29 |
| TC-RESL-050 | Phase C 부모 재잠금 PII 최종게이트 | PASS | [정적] `:96-105` parent→newRaw 잠금 순서 고정 | "부모가_비식별미완료(F)면_PII최종게이트에서_CONFLICT로_abort한다" | — |
| TC-RESL-051 | 중복 finalize CAS skip | PASS | [정적] `:116-122` newRaw 재잠금 + `'Y'` CAS → SKIPPED | IT "같은_파생RAW를_2스레드가_동시_finalize해도_프레임은_1회만_삽입된다(#5_승자보호)" | — |
| TC-RESL-052 | 프레임 개인정보 3필드 복사 | PASS | [실동작] 부모17 12프레임 `anony/psdo/prvc_incl_yn` 전부 NULL → 파생 18/19 12프레임 전부 NULL(부모 null→파생 null 계약 준수) | "부모프레임_개인정보3필드가_해상도파생_프레임에_복사되어_INSERT된다" | 값이 채워진 부모 실동작은 미재현 |
| TC-RESL-053 | 예약 aug 슬롯 해제 | PASS | [실동작] 로그 `reserved aug slot released dataAugSn=13 augTypeCd=RESL_1080P`(및 17). DB `ls_data_aug` 에서 RESL 예약행 삭제 확인 + **동일 프리셋 재요청이 다시 201 성공**(락아웃 해소 실증) | "finalize_transient실패시_예약aug행이_삭제되고_새RAW는_FAILED이며_동일프리셋_재시도가_성공한다" | — |
| TC-RESL-054 | 슬롯 해제 방어 — 라벨맵 참조 시 미삭제 | PASS | [정적] `:208-213` | "releaseReservedAug_라벨맵이_참조중이면_삭제하지않는다(승자참조_보호)" | — |
| TC-RESL-055 | 슬롯 해제 방어 — 비-RESL 미삭제 | PASS | [정적] `:214-224` `RESL_` 접두 확인 | "releaseReservedAug_RESL_접두가_아니면_삭제하지않는다(오배송_방어)" | — |
| TC-RESL-056 | isAlreadyFinalized FOR UPDATE 판정 | PASS | [정적] `:181-190` `findByRawSnForUpdate` + `'Y' \|\| COMPLETED` | "isAlreadyFinalized_deIdntfYn_Y면_true_COMPLETED면_true_그외_false다" | 코드 주석이 잔여 경합(승자-뒤짐·락 타임아웃 페일오픈)을 정직하게 명시 |
| TC-RESL-057 | 러너 A→B→C 정상 완주 | PASS | [실동작] rawSn=18/19 완주 결과(프레임·라벨·aug ACCEPTED·procLog·COMPLETED) 실측 | "정상확정시_A_B_C를_순차호출하고_FAILED전이나_정리를_하지않는다" | 현 세션에서의 신규 완주는 조건 부재로 미재현 |
| TC-RESL-058 | 러너 snapshot empty skip | PASS | [정적] `AsyncResolutionRunner.java:62-66` | "PhaseA가_멱등skip이면_B_C_정리_전이_모두_수행하지않는다" | — |
| TC-RESL-059 | 러너 persist SKIPPED — 파일 미정리 | PASS | [정적] `:73-79` | "PhaseC가_SKIPPED_중복finalize패자면_정리도_FAILED전이도_하지않는다" | — |
| TC-RESL-060 | 러너 실패 정리 — 승자 보호 선점검 | PASS | [정적] `:98-113` cleanup 이전에 `isAlreadyFinalized` 선점검 | "실패했지만_이미_승자가_확정(Y)했으면_cleanup도_FAILED전이도_aug해제도_스킵한다(M-1_승자산출물보호)" | — |
| TC-RESL-061 | 러너 실패 정리 — cleanup+슬롯해제+FAILED | PASS | [실동작] 파생 30/32/33~39 전건: `releaseReservedAug` 로그 + `ls_data_raw.data_stts_cd='FAILED'`·`de_ident_yn='N'` 전이 확인. Phase A 실패라 cleanup 은 스냅샷 부재로 정상 생략(파일 0건, `resolution/13` 디렉토리 미생성 확인) | "PhaseA_예외시_스냅샷이없어_cleanup은_생략하고_예약aug해제_및_FAILED전이한다","PhaseB_실패시_아티팩트정리후 ..." | — |
| TC-RESL-062 | cleanup 원본 미삭제 보장 | PASS | [정적] `ResolutionFileMaterializer.java:76-110` 삭제 대상은 `resolution/{newRawSn}/` + `videoDst` 만. 파생 id 와 부모 id 는 상호배타(파생은 부모가 될 수 없음)라 네임스페이스 충돌 발생 불가 | "cleanup은_파생_비디오와_프레임디렉토리를_삭제하고_무관파일은_보존하며_true를_반환한다" | 다만 비디오/프레임 디렉토리 스코프 비대칭 → E-ISSUE-27 |
| TC-RESL-063 | @Async 예외 삼킴 | PASS | [실동작] 파생 30/32~39 비동기 실패가 API 응답(201)·서버 기동에 전파되지 않고 WARN 로그로만 종결 | "예약aug_해제가_예외를_던져도_FAILED전이는_수행된다" | — |
| TC-RESL-064 | createResolutionPending RESL_ 접두 강제 | PASS | [정적] `LsDataAug.buildResolution` `RESL_PREFIX` 미충족 시 INVALID_INPUT | 간접 | — |
| TC-RESL-065 | markResolutionGenerated 이중전이 차단 | PASS | [정적] `LsDataAug.markResolutionGenerated` `RESL_` + `PENDING` 가드, 비-PENDING 시 CONFLICT | 간접(IT 상태전이) | — |

---

## ★ 해상도 파생 raw base 뿌리 — 증상 전수 조사 결과

**뿌리**: 파생 산출물의 **출력 base 를 raw base 로만 강제**하는 설계.
`ResolutionReservationPersister.java:82-84`(비디오 목적지) · `ResolutionSnapshotService.java:177-178`(프레임 목적지) · 동 `resolveSafeFile/resolveSafeDir`(출력은 raw base 만 허용, 주석 227행 "파생 산출물은 raw base 하위에만 쓴다") · `ResolutionFileMaterializer.java:80`(cleanup base).
그 결과 **비식별 산출물이 비식별 저장소 밖(raw 저장소)에 존재**하며, 이를 참조하는 하위 시스템마다 fail-closed/fail-open 이 갈린다.

| # | 증상 | 실측 근거 | 심각도 | 관련 기확정 이슈 |
|--:|------|----------|:--:|------|
| 1 | **파생 비디오 파일이 raw base 아래 생성** | `docker exec ls -R /app/storage/raw/resolution` → `/app/storage/raw/resolution/14\|17/{RESL_720P,RESL_480P}/video/*.mp4`. deid base(`/app/storage/deidentified`)에는 `frames`·`videos` 만 있고 resolution 없음 | HIGH | B-ISSUE-61 |
| 2 | **파생 프레임 이미지셋이 raw base 아래 생성** | `/app/storage/raw/resolution/{15,16,18,19}/frames/frame-*.jpg` 각 12장 | HIGH | D-ISSUE-46 |
| 3 | `LS_DATA_RAW.RAW_FILE_PATH_NM` = raw base 파생 비디오 경로 | `SELECT raw_file_path_nm ... raw_sn IN (15,16,18,19)` → `/app/storage/raw/resolution/{14,17}/{preset}/video/{preset}.mp4` | HIGH | B-61 |
| 4 | `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`(파생) = raw base 경로 | `SELECT data_raw_sn, de_idntf_file_path_nm FROM ls_deident_proc_log` → 15/16/18/19 전부 `/app/storage/raw/resolution/...`. 일반 영상(7,11~14,17,23~28)은 `/app/storage/deidentified/videos/...` | HIGH | B-61 |
| 5 | **파생 영상 스트리밍 전면 403** | [실동작] `GET /v1/videos/18\|19/stream` → **403** `{"message":"허용되지 않은 영상 경로입니다.","errorCode":"FORBIDDEN"}` / 부모 17 → 206. 원인: `VideoStreamService.resolveStreamMeta` 가 `baseDir=deidentifiedPath` 로만 `resolveSafe` → FORBIDDEN | HIGH | B-ISSUE-61 |
| 6 | `LS_DATA_SRC.SRC_FILE_PATH_NM` == `DE_IDNTF_SRC_FILE_PATH_NM` (동일 raw base 경로) | `ResolutionPersistService.java:243-247` `LsDataSrc.create(..., dst, dst, ...)` — 같은 값을 원본·비식별 두 컬럼에 저장. DB 실측: raw 15/16/18/19 각 12행 두 컬럼 동일 | HIGH | D-ISSUE-46 |
| 7 | **데이터마트 뷰 `V_COMPLETED_FRAME` 오노출** | [실동작] `SELECT raw_sn, count(*), sum(original_path=deidentified_path)` → **rawSn=19: 12행 중 12행이 ORIGINAL==DEIDENTIFIED**. 다른 영상(14,17,26)은 0. 관제는 "원본 경로"로 비식별 산출물을, "비식별 경로"로 raw base 파일을 받는다 | HIGH | D-ISSUE-46 |
| 8 | **`FrameSource` 가 DEIDENTIFIED 에 rawBase 폴백을 상시 허용** | `dataset/export/FrameSource.java:68-75` — `candidateBases = kind==ORIGINAL ? {rawBase} : {deidBase, rawBase}`, 주석에 "해상도 파생 = ResolutionPersistService 가 파생 비식별을 raw base 에 기록" 명시. **해상도 파생 우회용 예외가 전 영상 export 에 적용**되어 PII 격리가 fail-open | HIGH | (신규, D-46 확장) |
| 9 | 파생 export 2벌이 동일 소스의 중복 | [실동작] `/app/storage/labeling/19/v2\|v3` 각 `orgnl=24, deid=24` 파일. 파생은 원본(비-비식별) 프레임이 애초에 존재하지 않아 ORIGINAL 벌이 사실상 비식별본 사본 | MED | D-46 |
| 10 | CLAUDE.md 데이터마트 계약 위반 | 문서: "신규 추출은 `{base}/frames/raw\|deid/{rawSn}` 로 분기 저장돼 **두 경로가 항상 상이**(원본 덮어쓰기 0)" — 파생 경로가 이 불변식을 깬다 | MED | D-46 |
| 11 | 비디오/프레임 디렉토리 스코프 비대칭 | 비디오=`resolution/{parentRawSn}/{preset}/video/`, 프레임=`resolution/{newRawSn}/frames/`. cleanup 은 `resolution/{newRawSn}` + `videoDst` 만 삭제 → 실패 후 빈 부모 디렉토리 잔존(`resolution/14`,`17` 실측) | LOW | (신규) |
| 12 | 프레임 이미지 서빙은 **통과** (비대칭) | [실동작] `GET /v1/frames/420/image`(파생 19 프레임) → **200 image/jpeg 7107B**. `FrameImageController` 는 raw base 로만 검증하므로 파생이 우연히 통과. 스트리밍(403)과 서빙(200)이 엇갈림 | MED | B-61 |

**통합 수정 방향(제안 — 구현 금지)**

- B-61(파생 스트리밍 403, fail-closed)과 D-46(마트 비식별 경로 오노출, fail-open)은 **같은 뿌리의 앞뒤 면**이다. B-61 을 "`VideoStreamService` 가 raw base 도 허용"으로 고치면 파생·비파생 구분 없이 raw base 비디오가 비식별 스트림으로 서빙돼 D-46 이 확대된다. 반대로 D-46 만 "뷰에서 파생 제외"로 막으면 파생이 마트에서 사라져 요구(파생도 마트 대상)를 어긴다.
- **정공법: 파생 산출물의 출력 base 를 deid base 하위로 이동**한다.
  1. `ResolutionReservationPersister.java:82-84` 의 `base` 를 `storageDeidentifiedPath` 로, 경로를 `deid base + videos/{newRawSn}/...`(일반 영상 규약과 동일)로 변경.
  2. `ResolutionSnapshotService.buildFrameSpecs` 의 `fdst` 를 `deid base + frames/deid/{newRawSn}/`(일반 영상 규약과 동일)로 변경하고 `resolveSafeFile/resolveSafeDir` 의 출력 base 를 deid base 로 교체.
  3. `ResolutionPersistService.insertFrames` 의 `LsDataSrc.create(..., dst, dst, ...)` 를 분리 — `SRC_FILE_PATH_NM` 은 (파생에 원본이 없으므로) **NULL 또는 명시적 파생 원본 정책**을 정하고, `DE_IDNTF_SRC_FILE_PATH_NM` 만 deid 경로로 채운다. 두 컬럼 동일 저장은 금지.
  4. `ResolutionFileMaterializer.cleanup` 의 base 도 동반 이동(현 raw base 삭제 로직이 새 경로를 못 지움 → 고아 파일 회귀 위험).
  5. `FrameSource.java:73-75` 의 **DEIDENTIFIED rawBase 폴백을 제거**(1~3 이 선행돼야 안전). 제거하지 않으면 PII 격리가 계속 fail-open.
  6. `VideoStreamService` 는 **손대지 않는다**(deid base 강제 유지가 정답). 1~4 완료 시 파생 스트리밍 403 이 자동 해소된다.
  7. 기존 파생(15,16,18,19)은 파일 이동 + `LS_DATA_RAW.RAW_FILE_PATH_NM`·`LS_DATA_SRC` 2컬럼·`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 백필 마이그레이션 필요.
  8. 회귀 테스트 추가: **확정 후(deIdntfYn='Y') 파생 스트리밍 200** + **`V_COMPLETED_FRAME` 에서 ORIGINAL≠DEIDENTIFIED** 2건. 현 테스트는 "확정 **전** N 상태 스트리밍 NOT_FOUND"만 검증해 이 결함을 통과시킨다.

---

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [E-ISSUE-21] TC-RESL-041 / TC-RESL-044 — 해상도 파생 산출물이 비식별 저장소(deid base) 밖 raw base 에 생성·기록됨
- **심각도**: HIGH (보안 CWE-359 정보 격리 / 데이터 무결성)
- **기대 동작**: 파생 비디오·프레임은 비식별 산출물이므로 `STORAGE_DEIDENTIFIED_PATH` 하위(`videos/{rawSn}/`, `frames/deid/{rawSn}/`)에 생성되고, `DE_IDNTF_*` 컬럼에는 deid base 경로가, `SRC_FILE_PATH_NM` 에는 원본 경로가(파생은 원본 부재 → 별도 정책) 기록되어 마트·스트리밍·export 가 일관되게 동작해야 한다.
- **현재 동작**: `ResolutionReservationPersister.java:82-84`(비디오), `ResolutionSnapshotService.java:177-178` + `:244-254 resolveSafeFile`(프레임)이 출력 base 를 raw base 로만 강제. `ResolutionPersistService.java:243-247` 이 같은 경로를 `SRC_FILE_PATH_NM`·`DE_IDNTF_SRC_FILE_PATH_NM` 두 컬럼에 동일 저장. `:143-146` 이 procLog `DE_IDNTF_FILE_PATH_NM` 에도 raw base 경로 기록.
  실측: `/app/storage/raw/resolution/{15,16,18,19}/frames/*.jpg`(각 12장), `/app/storage/raw/resolution/{14,17}/{preset}/video/*.mp4`, `ls_deident_proc_log` 15/16/18/19 전부 raw base.
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT raw_sn, count(*), sum(CASE WHEN original_path=deidentified_path THEN 1 ELSE 0 END) FROM v_completed_frame GROUP BY raw_sn"` → rawSn=19 가 12/12. / `curl -H 'Authorization: Bearer <REVIEWER>' localhost:18081/api/v1/videos/19/stream` → 403.
- **영향**: ①파생 영상 재생 전면 불가(B-61) ②관제 데이터마트가 raw 저장소 경로를 "비식별 경로"로 수신(D-46) ③디렉토리 단위 접근제어·보존·백업 정책이 파생 비식별본을 raw 로 취급 ④`FrameSource` 우회 폴백을 강제해 전 영상 PII 격리 약화(E-ISSUE-22). CWE-359(Privacy Violation) / CWE-668(Exposure of Resource to Wrong Sphere).
- **수정 방향(제안)**: 위 "통합 수정 방향" 1~8. **B-61 을 raw base 허용으로 고치면 안 됨**(D-46 확대).

### [E-ISSUE-22] TC-RESL-041 파생 — `FrameSource` 의 DEIDENTIFIED rawBase 폴백이 전 영상 PII 격리를 fail-open 으로 만듦
- **심각도**: HIGH (보안 CWE-359)
- **기대 동작**: export 의 DEIDENTIFIED 벌은 deid base 하위 파일만 허용해야 한다(ORIGINAL 이 rawBase 단일 강제인 것과 대칭).
- **현재 동작**: `backend/src/main/java/kr/co/cudo/authoring/dataset/export/FrameSource.java:68-75`
  `Path[] candidateBases = (kind == ExportKind.ORIGINAL) ? new Path[]{rawBase} : new Path[]{deidBase, rawBase};`
  주석(69-71행)에 "해상도 파생 = ResolutionPersistService 가 파생 비식별을 raw base 에 기록"이라고 **우회 목적이 명시**되어 있다. 즉 해상도 파생 하나를 살리려고 **모든 영상**의 DEIDENTIFIED export 가 raw base 파일을 수용하게 됐다.
- **재현/확인 경로**: 임의 영상의 `LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM` 이 raw base 를 가리키도록 오염돼도 export 가 이를 "비식별본"으로 기록한다(정적).
- **영향**: 비식별 미적용 원본 프레임이 DEIDENTIFIED export 벌에 섞여 관제/데이터마트로 유출될 수 있는 경로가 상시 열림.
- **수정 방향(제안)**: E-ISSUE-21 의 1~4 선행 후 `candidateBases` 를 `{deidBase}` 단일로 되돌린다. 단독 제거 시 기존 파생 export 가 즉시 PARTIAL 로 회귀하므로 **반드시 경로 이동·백필과 동일 릴리스**여야 한다.

### [E-ISSUE-23] TC-RESL-061 — 파생 확정 실패 시 `LS_DATA_RAW` 고아 행이 영구 잔존·무한 누적
- **심각도**: MEDIUM (데이터 위생)
- **기대 동작**: 확정 실패한 파생은 예약 aug 슬롯 해제와 함께 사용자/운영자가 상태를 인지하거나 정리할 수 있어야 한다.
- **현재 동작**: `AsyncResolutionRunner.handleFailure` 는 파일 cleanup + aug 삭제 + `markRawDataFailed` 만 수행하고 `LS_DATA_RAW` 행은 남긴다(`ResolutionPersistService`/`AsyncResolutionRunner` 전체에 파생 RAW 삭제 경로 없음). 실측: 부모 13 에 대한 FAILED 파생 RAW 가 **12건**(20,21,22,30,32~39) 누적. `GET /v1/videos` 는 파생을 제외하므로 화면·증강 이력 어디에도 노출되지 않는 **침묵 쓰레기**.
- **재현/확인 경로**: `POST /v1/videos/13/resolution` 반복 → `SELECT orgnl_raw_sn, data_stts_cd, count(*) FROM ls_data_raw WHERE orgnl_raw_sn IS NOT NULL AND raw_file_path_nm LIKE '%resolution%' GROUP BY 1,2`
- **영향**: 재시도마다 RAW 행 증가(파일은 미생성), 통계·마이그레이션·감사 시 노이즈. RAW_SN 시퀀스 소모.
- **수정 방향(제안)**: ①실패 파생 RAW 를 soft-delete/삭제하거나 ②FAILED 파생을 조회 가능한 운영 화면·API 에 노출하고 수동 정리 제공. ③최소한 동일 (부모,프리셋) 재요청 시 기존 FAILED 파생 RAW 를 재사용하도록 변경.

### [E-ISSUE-24] TC-RESL-001/007 — 201 `CREATED` 가 "예약 성공"만 의미하며 비동기 확정 실패를 알 방법이 없음
- **심각도**: MEDIUM (기능/UX)
- **기대 동작**: 사용자가 파생 생성 성공/실패를 확인할 수 있어야 한다.
- **현재 동작**: [실동작] `POST /v1/videos/13/resolution` → 201 `derivatives:[{rawSn:33,status:"CREATED"},...]` 반환. 그러나 ~20ms 뒤 3건 전부 Phase A CONFLICT 로 FAILED 전이. 응답은 CREATED 그대로이고, 파생 RAW 는 `GET /v1/videos` 에서 제외, 예약 aug 는 삭제되어 `GET /v1/augments` 의 `resolutionTypes` 에도 미노출 → **어느 화면에서도 실패를 볼 수 없다**.
- **재현/확인 경로**: 위 요청 후 `SELECT raw_sn,data_stts_cd FROM ls_data_raw WHERE raw_sn IN (33,34,35)` → 전부 FAILED.
- **영향**: REVIEWER 가 파생이 생성됐다고 오인. 부모 13 처럼 비식별 프레임이 없는 영상은 매번 조용히 실패.
- **수정 방향(제안)**: ①응답 상태값을 `RESERVED`/`ACCEPTED` 로 명확화 ②파생 상태 조회 API 또는 증강 이력에 FAILED 파생 노출 ③가능하면 동기 단계에서 "부모 비식별 프레임 보유" 선검증을 추가해 예약 전에 400 으로 거부.

### [E-ISSUE-25] TC-RESL-001 — 파생 `VMS_CLIP_ID` 의 `RESL_RESL_` 이중 접두 드리프트
- **심각도**: LOW (데이터 품질)
- **기대 동작**: 파생 식별자에 프리셋 코드가 1회만 들어간다.
- **현재 동작**: `LsDataRaw.createFromResolution` (`LsDataRaw.java:226`) `vmsClipId = parent + "_RESL_" + goalResCd + "_" + millis` 이고 `goalResCd` 자체가 `RESL_720P` → 실측 `test-1784791814270_RESL_RESL_720P_1784792022000`.
- **재현/확인 경로**: `SELECT vms_clip_id FROM ls_data_raw WHERE orgnl_raw_sn IS NOT NULL`
- **영향**: 파생 종류를 `VMS_CLIP_ID` 마커 파싱으로 식별하는 코드/화면의 드리프트(기존 메모 `reviewpage-augmented-list-facts` 의 `RESL_RESL`/`RES_RES` 이슈와 동일 뿌리).
- **수정 방향(제안)**: 접두를 `"_"` 로 바꾸거나 파생 종류를 `LS_DATA_AUG.AUG_TYPE_CD` 조인으로만 판별(문자열 파싱 폐지).

### [E-ISSUE-26] TC-RESL-008 — 종횡비 보존 미구현(Javadoc 과 실제 불일치), 비-16:9 원본이 강제 왜곡됨
- **심각도**: MEDIUM (기능)
- **기대 동작**: `ResolutionPreset` Javadoc — "실제 다운스케일은 원본 종횡비를 보존하므로 목표 세로(height)를 기준으로 비율을 산정하고 가로는 종횡비에 맞춰 계산한다."
- **현재 동작**: `ResolutionSnapshotService.java:111-121` 이 `targetW=preset.width()`, `targetH=preset.height()` 를 그대로 쓰고 `ResolutionFileMaterializer.java:56` 이 `imageResizer.resize(src, dst, targetW, targetH)` 로 **고정 W×H 강제 스케일**. [실동작] 부모 13(1080×1920 세로) → RESL_1080P 요청 시 로그 `src=1080x1920 target=1920x1080` (scaleX=1.778, scaleY=0.5625) — 세로 영상이 가로로 눌린다. 라벨 좌표도 동일 배율로 왜곡 복사.
- **재현/확인 경로**: `POST /v1/videos/13/resolution` 로그 확인.
- **영향**: 세로/비표준 종횡비 원본의 파생 영상·라벨이 왜곡된 학습데이터로 산출. 문서·주석과 구현 불일치(감리 지적 소지).
- **수정 방향(제안)**: 정책 확정 필요 — ①Javadoc 대로 종횡비 보존(목표 높이 기준, 가로는 계산)으로 구현 정정하거나 ②정책이 고정 W×H 라면 Javadoc·설계서를 실제에 맞게 고치고 비-16:9 원본 처리(레터박스/거부) 규칙을 명시.

### [E-ISSUE-27] TC-RESL-062 — 파생 비디오/프레임 저장 스코프 비대칭 + 빈 부모 디렉토리 잔존
- **심각도**: LOW (설계 견고성)
- **기대 동작**: 한 파생의 모든 산출물이 파생 스코프 한 디렉토리에 모여 cleanup 1회로 완전 정리된다.
- **현재 동작**: 비디오=`resolution/{parentRawSn}/{preset}/video/{preset}.mp4`(`ResolutionReservationPersister.java:83-84`), 프레임=`resolution/{newRawSn}/frames/`(`ResolutionSnapshotService.java:177-178`). `cleanup(newRawSn, videoDst)` 은 프레임 디렉토리와 비디오 파일만 지우고 `resolution/{parentRawSn}/{preset}/video/` 빈 디렉토리는 남는다(실측 `resolution/14`, `resolution/17`).
- **재현/확인 경로**: `docker exec klid-backend ls -R /app/storage/raw/resolution`
- **영향**: 현재 id 상호배타성(파생은 부모가 될 수 없음) 덕분에 삭제 충돌은 없으나, 규약 변경 시 교차 삭제 위험. 빈 디렉토리 누적.
- **수정 방향(제안)**: E-ISSUE-21 경로 이동 시 비디오·프레임 모두 `{deid base}/resolution/{newRawSn}/` 단일 스코프로 통일.

### [E-ISSUE-28] TC-RESL-013 — 근거 라인 드리프트 (`ResolutionPreset.java:7-8`)
- **심각도**: LOW (문서)
- **기대 동작**: 케이스 근거가 실제 enum 정의 위치를 가리킨다.
- **현재 동작**: 테스트케이스 근거 `ResolutionPreset.java:7-8` 은 클래스 Javadoc. 실제 화이트리스트 enum 상수는 **14~16행**(`RESL_1080P(1920,1080)` 등).
- **영향**: 근거 추적 오류. (E-4/E-5 범위 다른 44개 근거는 전부 정합 — 드리프트 1건)
- **수정 방향(제안)**: 근거를 `ResolutionPreset.java:14-16` 으로 정정.

### [E-ISSUE-29] TC-RESL-037/039/049 — 확정 게이트 3종 전용 테스트 부재 + "테스트 GREEN = 안전" 착각 지점
- **심각도**: MEDIUM (검증 신뢰도)
- **기대 동작**: 보안·정합 게이트마다 실행 경로를 검증하는 테스트가 있어야 한다.
- **현재 동작**: backend 3013 테스트 전건 GREEN(`_raw/test-baseline.md`)임에도 —
  ①`ResolutionSnapshotService.java:126-130`(비식별 비디오 procLog 부재 404) ②동 `:166-171`(중복 videoFrameNo fail-fast) ③`ResolutionPersistService.java:311-321`(mtime 교체 게이트, `IOException` 시 **페일오픈 통과**) 3건에 대응하는 `@DisplayName` 이 `Resolution*Test`/`*IT` 전체에 없다.
  더 중요한 것은 **확정 후 파생 스트리밍**을 검증하는 테스트가 없다는 점이다 — 존재하는 테스트는 "확정**전**_파생RAW는_deIdntfYn_N이라_스트리밍이_NOT_FOUND로_거부된다(#2_PII_TOCTOU)" 뿐이라, 확정 후 403(E-ISSUE-21 증상5)이 전 테스트를 통과한다. 마찬가지로 어떤 테스트도 산출물 base 가 deid base 인지 단언하지 않고 raw base 를 기대값으로 고정하고 있다(`ResolutionFileMaterializerTest`).
- **재현/확인 경로**: `grep -h "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/video/Resolution*.java`
- **영향**: 결함이 CI 를 통과. 회귀 감지 불가.
- **수정 방향(제안)**: ①위 3게이트 단위 테스트 추가 ②`deIdntfYn='Y'` 확정 후 파생 스트리밍 200 IT 추가 ③`V_COMPLETED_FRAME` 에서 파생의 ORIGINAL≠DEIDENTIFIED 를 단언하는 IT 추가 ④mtime 게이트의 `IOException` 페일오픈이 의도된 정책인지 확정.

### [E-ISSUE-30] TC-RESL-034 — 출력 경로 traversal 가드가 도달 불가 코드
- **심각도**: LOW (정보)
- **기대 동작**: 케이스는 "preset명 traversal → 400" 을 기대.
- **현재 동작**: `ResolutionReservationPersister.java:122-128` `resolveSafeDir` 는 존재하나 입력이 `preset.name()`(enum 상수명)이라 traversal 문자가 유입될 경로가 없다. 즉 **가드는 실행되지만 위반 분기는 도달 불가**.
- **영향**: 없음(방어적 코드). 다만 케이스가 실제 위협을 검증하지 않는다는 착각을 준다.
- **수정 방향(제안)**: 케이스 기대값을 "enum 바인딩으로 traversal 원천 차단(가드는 심층방어)"으로 재기술. 코드 변경 불필요.

### [E-ISSUE-31] TC-RESL-005/012/031/043 — 실동작 미재현 4건(환경 제약)
- **심각도**: LOW (검증 커버리지)
- **내용**: ①TC-RESL-005(전 프리셋 동일 해상도) — 3 프리셋과 모두 일치하는 영상 부재 ②TC-RESL-012(dim≤0) — 손상 이미지 주입 불가(파일 수정 금지) ③TC-RESL-031(예약 단계 PII 게이트) — `APPROVED` + `de_ident_yn≠'Y'` + base 내 프레임을 동시 만족하는 영상 부재(rawSn 5/6 은 경로 가드가 선행 400) ④TC-RESL-043(리사이즈 게이트 포화 429) — 동시 부하 미생성.
- **수정 방향(제안)**: 검증용 시드(비식별 미완 APPROVED 영상, 프리셋 동일 해상도 영상)를 dev 시드에 추가하면 이후 회차에서 실동작 재현 가능.

---

## 요약

- **총 53건 / PASS 44 / FAIL 0 / PARTIAL 9 / BLOCKED 0 / N/A 0 / 확인필요 0**
  - E-4(17건): PASS 15 / PARTIAL 2 (TC-RESL-005, 012)
  - E-5(36건): PASS 29 / PARTIAL 7 (TC-RESL-031, 037, 039, 041, 043, 044, 049)
- **근거 라인 드리프트: 1건** (TC-RESL-013 `ResolutionPreset.java:7-8` → 실제 14-16). 나머지 44개 근거는 전부 정합.
- **self-fill 결함: 0건** — 외부 응답 없이 값을 자체 생성하는 경로 없음. 파생 소스는 `deidFrameSourceStrict`(`ResolutionSnapshotService.java:212-219`)로 **비식별 프레임만** 허용하며, 부재 시 원본 폴백 없이 CONFLICT 로 실패함을 rawSn=13 실동작으로 실증(파생 RAW `de_ident_yn='N'` 유지, 파일 0건).
- **★ 최대 결함**: 해상도 파생 산출물 raw base 생성(E-ISSUE-21) — 증상 12종 전수 식별. B-ISSUE-61(fail-closed 403)·D-ISSUE-46(fail-open 마트 오노출)·신규 E-ISSUE-22(FrameSource 폴백)를 **한 릴리스에서 함께** 고쳐야 하며, 정공법은 "파생 산출물을 deid base 하위로 이동 + `SRC`/`DE_IDNTF` 컬럼 분리 + FrameSource 폴백 제거 + 기존 4건 백필"이다. `VideoStreamService` 의 deid base 강제는 유지해야 한다.
- 신규 이슈 11건: E-ISSUE-21 ~ E-ISSUE-31 (HIGH 2 / MEDIUM 5 / LOW 4)
