# E-part3 — 데이터셋 Export · Export JSON 포맷 · 촬영환경/개인정보 메타 (E-6~E-9)

> 담당 범위: `docs/test-cases/E-augment-resolution-export-meta.md` **129~205행**
> 검증 일자: 2026-07-25 / 스택: klid-postgres · klid-backend(V130, :18081) · klid-ai-server · klid-mock-server(:9400) · klid-frontend
> DB 스키마: `public` / 참조 데이터: **rawSn=26**(프레임 16·라벨 131·APPROVED·export SUCCEEDED), 보조로 rawSn=13/17/19/4/5/6
> 로컬 설정: `authoring.control-notify.enabled=false`(ControlNotifyEventListener 빈 미생성), `VLM_CLIENT_ENABLED=false`

---

## E-6. 데이터셋 Export

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-EXPORT-001 | 검수승인 AFTER_COMMIT 강제 재생성 | PASS | [실동작] rawSn=26 재승인 → 로그 `[DatasetExportBridge] review approved rawSn=26 — triggering dataset export (force regenerate)` → `AsyncDatasetExportRunner ... forceRegenerate=true`. 정적 `DatasetExportBridge.java:35-41` 일치 | DatasetExportBridgeTest(2) | AFTER_COMMIT 후에만 발화 확인(승인 트랜잭션 커밋 로그 뒤에 브릿지 로그) |
| TC-EXPORT-002 | 승인 경로 멱등 skip 미적용 | PASS | [실동작] rawSn=26 **무수정 재승인** → `v2` 신규 채번(export_sn=12, SUCCEEDED, frame_cnt=32). rawSn=17도 v1→v2, rawSn=19는 v1/v2/v3 누적. 정적 `DatasetExportService.java:104-108` | DatasetExportServiceTest "무수정_재승인도_승인경로는_새버전_생성한다 (force=true, R6)" | 승인 1회 = 새 버전 1개 확정 |
| TC-EXPORT-003 | 재동결 경로 멱등 skip | PASS | [정적] `DatasetExportService.java:104-108` + `DatasetExportBridge.java:48-54`(force=false). 로컬에 `DatasetReExportEvent` 트리거 경로(EvntAnno 지연 승인) 미발생 → **실동작 미관측** | DatasetExportServiceTest "재동결_onReExport_경로는_동일해시면_멱등_skip_유지한다" | 실동작 미검증(정적+유닛만) |
| TC-EXPORT-004 | 정상 2벌 산출 SUCCEEDED | PASS | [실동작] rawSn=26 v2: `frames written kind=ORIGINAL written=16 skipped=0` / `kind=DEIDENTIFIED written=16 skipped=0` → `export succeeded version=2 written=32`. `ls_dataset_export.export_path_nm=/app/storage/labeling/26/v2`, `frame_cnt=32`. 폴더 실체 orgnl/deid 각 32파일 | DatasetExportServiceTest·DatasetExportE2EIT | |
| TC-EXPORT-005 | 일부 프레임 부재 PARTIAL | PASS | [실동작] rawSn=13 export_sn=3 `PARTIAL frame_cnt=11`. 실측 원인: `ls_data_src`(raw_sn=13) 11행 전부 `de_idntf_src_file_path_nm`=NULL → deid 폴더 실체 **비어 있음**(orgnl만 11쌍) | DatasetExportServiceTest "일부프레임_skip시_PARTIAL로_전이한다" | rawSn 4~13 구간은 deid 경로 NULL 잔존(기보고 회귀의 잔재) |
| TC-EXPORT-006 | 산출 0건 FAILED | PASS | [실동작] rawSn=4(v1,v2)·5·6 → export_sn 6/7/9/10 모두 `FAILED, frame_cnt=NULL`. 폴더는 생성되나 orgnl/deid 둘 다 빈 디렉터리. 정적 `DatasetExportService.java:126-131` | DatasetExportServiceTest "아무것도_산출못하면_FAILED로_전이한다" | 승인은 그대로 유지 = 기보고 `D-ISSUE-04` 실증(아래 E-ISSUE-43) |
| TC-EXPORT-007 | deid 프레임 export — 해상도 파생 정합 | PASS | [실동작] rawSn=19(=17의 RESL_480P 파생) `ls_data_src` 12/12 `de_idntf_src_file_path_nm` non-null → export v2/v3 **SUCCEEDED frame_cnt=24**(PARTIAL 회귀 없음). 정적 `ResolutionPersistService.java:243-247` 6-arg 아닌 **9-arg** `LsDataSrc.create(..., dst, dst, ...)` INSERT 시점 저장 | — | ★ 부수 결함 별건: orgnl/deid **경로가 동일**(E-ISSUE-41) |
| TC-EXPORT-008 | 버전 채번 UK 충돌 재시도 | PASS | [정적] `DatasetExportService.java:170-180` insertWithRetry(최대 3회) + `DatasetExportTxService` 각 시도 REQUIRES_NEW. 동시 승인 미재현 | DatasetExportServiceTest "동시_승인_UK위반시_재시도로_다음버전_채번된다" | 실동작 미검증 |
| TC-EXPORT-009 | 버전 채번 재시도 소진 | PASS | [정적] `DatasetExportService.java:112-117` null→VERSION_EXHAUSTED abort | DatasetExportServiceTest "UK위반이_재시도_상한_초과하면_산출을_중단한다" | |
| TC-EXPORT-010 | 파일쓰기 실패 시 승인 불변 | PASS | [실동작·간접] rawSn=4/5/6 export FAILED 상태에서 `ls_raw_data_status` 승인 상태 유지(롤백 없음). 정적 `DatasetExportService.java:146-153`(RuntimeException 삼킴 + markFailed) | DatasetExportServiceTest "파일산출_실패해도_승인은_롤백되지_않는다" | writer.write 예외 자체는 미재현(0건 산출 경로로 간접 확인) |
| TC-EXPORT-011 | 입력 부재 NO_INPUT | PASS | [정적] `DatasetExportService.java:95-99` + `DatasetExportTxService:98,105` (no frames / no active meta 로깅) | DatasetExportServiceTest "프레임_또는_활성메타_부재시_산출을_skip한다" | 메트릭 태그에 `no_input` 미출현(이번 세션 미발생) |
| TC-EXPORT-012 | 메트릭 outcome 정확히 1회 | PASS | [실동작] `/actuator/metrics/dataset.export.result` COUNT=**7.0**, tags outcome=[failed, completed]. `dataset.export.duration` COUNT=**7.0**. 세션 내 실제 export 건수(export_sn 6~12) = 7 로 **정확히 일치**(이중계상·누락 0) | DatasetExportServiceTest 메트릭 6건 | 메트릭명 실제는 `dataset.export.skipped_frames`(밑줄) |
| TC-EXPORT-013 | 브릿지 조건부 비활성화 | PASS | [정적] `DatasetExportBridge.java:28-29` `@ConditionalOnProperty(..., matchIfMissing=true)` | DatasetExportBridgeBeanConditionTest(3) | |
| TC-EXPORT-014 | 폴더 구조 계약 | PASS | [실동작] `/app/storage/labeling/26/v1`·`v2` → 각 `orgnl/`·`deid/`, 내부 `frame-{0..15}.jpg` + 동명 `.json`. 정적 `DatasetExportPathResolver.java:57-66`(labelingRoot 하위 startsWith 검증, 세그먼트 전부 타입안전 long/int/enum → CWE-22 표면 없음) | DatasetExportPathResolverTest(5) | 경로에 사용자 입력 미혼입 확인 |

---

## E-7. Export JSON 포맷

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-EXPORT-020 | event(구 event_annotation) pass-through | PASS | [실동작] rawSn=17 재승인 → v2 `orgnl/frame-0.json` 최상위 **`"event"`** 블록에 `ls_evnt_anno.anno_cn`(answer/caption.c1.cot/evidence.c1/question/event_class) **원문 그대로** 직렬화. 위치는 `video` 다음 | NiaJsonBuilderTest "event가_각_프레임_최상위에_c1cn_형태로_pass_through된다" / "최상위_VLM블록키는_event이고_event_annotation키는_부재_위치는_video다음" | **근거 드리프트**: TC 표 기대값은 `event_annotation` 이나 확정 결정(cudo local 이슈④)에 따라 `event` 로 rename 완료. TC 문서가 stale |
| TC-EXPORT-021 | event null 처리 | PASS | [실동작] rawSn=26 v1/v2 · rawSn=19 v3 → `"event" : null`(키 유지). VLM 비활성 상태에서 **값을 만들어내지 않음** = self-fill 없음 | NiaJsonBuilderTest "동결_event가_없으면_event키는_null이다" | `NiaAnnotationDoc` `@JsonInclude(ALWAYS)` |
| TC-EXPORT-022 | anonymity=ExportKind 파생(수동 override 금지) | PASS | [실동작] 프레임 446에 수동 `anonymity="Y"` 저장 후 재승인 → v2 `orgnl/frame-0.json` `image.anonymity="N"`, `deid/frame-0.json` `image.anonymity="Y"`. **수동값이 덮지 않음**(CWE-359 방어 유효). 정적 `NiaJsonBuilder.java:142-145` | NiaJsonBuilderTest 2건(원본 N 유지 / 비식별 Y 유지) | |
| TC-EXPORT-023 | pseudonymity/privacyIncluded 수동 우선 | PASS | [실동작] 프레임 446 수동 `pseudonymity=Y, privacyIncluded=Y` → v2 orgnl·deid 모두 `image.pseudonymity="Y"`, `privacy_included="Y"`. 미저장 프레임(frame-1)은 파생 `N/N` 폴백. 정적 `NiaJsonBuilder.java:148-151` | NiaJsonBuilderTest 2건 | |
| TC-EXPORT-024 | deid 산출 경로 fail-secure | PASS | [실동작·부분] rawSn=26 deid JSON `dataset.src_path`·`video.filename` = `/app/storage/deidentified/videos/26/..._mask.mp4`(원본 경로 미노출), orgnl 은 raw 경로. `deidVideoPath=null` 케이스는 실환경 부재 → 정적 `NiaJsonBuilder.java:131-136` / `VideoMetaMapper.java:56-59` | VideoMetaMapperTest "DEIDENTIFIED인데_deid경로_null이면_filename도_null_원본미노출" 외 4건 | |
| TC-EXPORT-025 | malformed 라벨 skip | PASS | [정적] `NiaJsonBuilder.java:170-193` try/catch → skip + `lblSn` 만 로깅(좌표·PII 미출력) | NiaJsonBuilderTest "malformed라벨은_문서조립시_skip되고_정상라벨만_남는다" | |
| TC-EXPORT-026 | 잉여키 제거/키 유지 정합 | PASS | [실동작] v2(신규 코드) JSON 에 `orign_file_name`/`orign_filename`/`cto`/`vqa` **전부 부재**. 미보유 필드(type/format/filesize/fps/frames/pixel 등)는 **키 유지 + 값 null**. 대조: rawSn=17 **v1**(구 코드, 07-23 산출)에는 `image.orign_file_name` 존재 → 제거가 실제로 반영됨을 2세대 비교로 확인 | NiaJsonBuilderTest·VideoMetaMapperTest 3건 | |
| TC-EXPORT-027 | vd_description 필드 존재 | PASS | [실동작] `video.vd_description : null` 키 존재. 정적 `NiaVideo.java:46` | NiaJsonBuilderTest | 데이터 출처 없음 → 항상 null(값 날조 없음 = 정상) |
| TC-EXPORT-028 | weather/time_of_day/season 수동값 우선 | PASS | [실동작] rawSn=26 수동 `weather=비, timeOfDay=DAY, season=SPRING` 저장 후 재승인 → v2 JSON `weather:"비"`, `time_of_day:"DAY"`, `season:"SPRING"`(파생값 NGT/SUMMER 를 **수동값이 이김**). 정적 `VideoMetaMapper.java:52-54`(raw→meta 역순 우선) | VideoMetaMapperTest 3건 | 검증 후 수동값 null 복원 완료 |
| TC-EXPORT-029 | 촬영환경 blank→null 정규화 | PASS | [정적] `VideoMetaMapper.java:139-147` firstNonBlank / `EnvironmentMetaService.java:160-163` blank→null. DB 직접 blank 주입 미재현 | VideoMetaMapperTest | |
| TC-EXPORT-030 | anonymity kind override(video) | PASS | [실동작] v2 orgnl `video.anonymity="N"` / deid `"Y"`, **촬영환경 3필드는 2벌 동일**(비/DAY/SPRING). 정적 `VideoMetaMapper.java:49-60` | VideoMetaMapperTest "ORIGINAL_DEIDENTIFIED_두_export의_촬영환경_동일" | |
| TC-EXPORT-031 | meta null 방어 | PASS | [정적] `NiaJsonBuilder.java:87-90` INVALID_INPUT("영상 메타가 null 입니다.") | NiaJsonBuilderTest "meta가_null이면_INVALID_INPUT" | |
| TC-EXPORT-032 | FORMAT_VERSION/info 계약 | PASS | [실동작] 모든 산출 JSON `info.version="1.3"`, `type="instances"`, `info.description="AI기반 CCTV 관제지원시스템 학습데이터"`, `licences[0]={id:1,name:"Private Use"}` | NiaJsonBuilderTest "최상위_8키_info부터_type까지_모두_존재한다" | 실제 최상위 키는 9개(info/dataset/licences/video/event/image/annotations/categories/type) |

---

## E-8. 촬영환경 메타 (영상 단위)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-META-001 | 조회 — 수동값 우선 프리필 | PASS | [실동작] PUT(비/DAY/SPRING) 후 GET → `{"weather":"비","timeOfDay":"DAY","season":"SPRING","weatherSource":"MANUAL","timeOfDaySource":"MANUAL","seasonSource":"MANUAL"}` | EnvironmentMetaServiceTest(17) | |
| TC-META-002 | 조회 — 파생 프리필 | PASS | [실동작] 수동값 없는 rawSn=26 → `timeOfDay:"NGT"(DERIVED)`, `season:"SUMMER"(DERIVED)`, `weather:null, weatherSource:null`. `ls_data_raw` 3컬럼 전부 NULL 실측 | EnvironmentMetaServiceTest·TimeOfDaySeasonDeriverTest | ★ 파생 자체의 원천 문제는 E-ISSUE-42 |
| TC-META-003 | PUT 전체 교체 저장 | PASS | [실동작] 3필드 PUT → `ls_data_raw` 3컬럼만 갱신(다른 컬럼 불변). 정적 `EnvironmentMetaService.java:100-102` dirty checking | EnvironmentMetaServiceTest | |
| TC-META-004 | null 필드 = 수동값 삭제 | PASS | [실동작] `{"weather":null,"timeOfDay":null,"season":null}` PUT → DB 3컬럼 NULL 복귀, GET 이 다시 DERIVED 폴백. 정적 `EnvironmentMetaService.java:160-171` | EnvironmentMetaServiceTest | |
| TC-META-005 | 허용값 화이트리스트 weather | PASS | [실동작] `"폭우"` → **400** `촬영환경 weather 값이 허용 목록에 없습니다.` 정적 `ShootingEnvironmentVocabulary.java:23`(맑음/흐림/비/눈/안개) | EnvironmentMetaServiceTest | |
| TC-META-006 | 허용값 timeOfDay/season | PASS | [실동작] `"MORNING"`→400, `"MONSOON"`→400. 정적 `ShootingEnvironmentVocabulary.java:26-32` | EnvironmentMetaServiceTest | 파생 상수 재사용으로 코드집합 드리프트 차단 확인 |
| TC-META-007 | 길이 상한 20 | PASS | [실동작] 21자 weather → 400 `weather: size must be between 0 and 20`(@Size 선차단). 정적 `EnvironmentMetaUpdateRequest.java:28` | EnvironmentMetaControllerTest | |
| TC-META-008 | APPROVED 후 수정 재동결+통지 | PARTIAL | [실동작] 재동결 O — rawSn=26 PUT 후 `ls_dataset_video_meta` 신규 active 행(비/DAY/SPRING) 생성, 이전 행 `active_yn=N`, **`rvw_cmpl_dt`=10:45:47 원값 보존**(reg_dt만 11:39:32). 로그 `[Dataset] materialized ... inserted=true` + `[EnvironmentMeta] re-freeze triggered`. **통지 X — `authoring.control-notify.enabled=false` 로 `ControlNotifyEventListener` 빈 미생성(`ControlNotifyEventListener.java:21`) → TASK_MODIFIED 실발행 미관측** | EnvironmentMetaReFreezeIT(1) | 통지부는 정적/IT 만. 로컬 설정 제약 |
| TC-META-009 | 재동결 시 export 미재생성 | PASS | [실동작] PUT 직후 `/app/storage/labeling/26/` = `v1` 만(신규 폴더 없음), `ls_dataset_export` 신규행 없음. 정적 `EnvironmentMetaService.java:132-137` | — | 의도된 정책(디스크 증폭 방지) |
| TC-META-010 | 미검수 영상 수정 — 통지 없음 | PASS | [실동작] rawSn=11(미승인) PUT 2회 → 로그에 `[EnvironmentMeta] updated rawSn=11` 만, `materialized`/`re-freeze` 로그 **부재**. 정적 `EnvironmentMetaService.java:110,207-213` | EnvironmentMetaServiceTest | |
| TC-META-011 | 재동결 시 활성 스냅샷 부재 fail-safe | PASS | [정적] `EnvironmentMetaService.java:143-147` warn 후 return(예외 없음) | EnvironmentMetaServiceTest | 실환경 재현 불가(APPROVED+스냅샷 부재 조합 없음) |
| TC-META-012 | 동시성 — env저장 vs 승인 materialize | PASS | [정적] `EnvironmentMetaService.java:105-114` — `videoRepository.flush()`(raw 행락) → `videoMetaRepository.acquireRawLock(rawSn)`(advisory) → 상태 판정 순서. 잠금 순서 raw→advisory 단방향(교착 없음) | EnvironmentMetaConcurrencyIT(1) | 실동작 동시 재현 미수행 |
| TC-META-013 | WORKER 본인배정 아닌 영상 | PASS | [실동작] WORKER(2001) → rawSn=**10**(미배정) GET/PUT 모두 **403** `본인에게 배정되지 않은 영상입니다.` (rawSn=11 은 `ls_task_assignment` 상 2001 배정이라 200 — 정상) | EnvironmentMetaServiceTest | CWE-639 방어 유효 |
| TC-META-014 | 미인증/포털 채널 | PASS | [실동작] 토큰 없음 → **401** `인증이 필요합니다.` / PORTAL 채널 토큰 → **403** `권한이 없습니다.` | EnvironmentMetaControllerTest | |
| TC-META-015 | 영상 미존재 | PASS | [실동작] rawSn=9999 GET → **404** `영상을 찾을 수 없습니다.` | EnvironmentMetaServiceTest | |
| TC-META-016 | 로그 PII 미출력 | PASS | [실동작] 허용값 외 입력 3회 → 로그 `[EnvironmentMeta] rejected value field=weather/timeOfDay/season` — **입력 원문 미노출**. 성공 로그도 `updated rawSn=26` 만 | — | CWE-117/209 준수 |

---

## E-9. 프레임 개인정보 메타 (프레임 단위)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-META-030 | 조회 — 수동값 우선/파생 폴백 | PASS | [실동작] 미저장 프레임 446 GET → `{"anonymity":"Y","pseudonymity":"N","privacyIncluded":"N"}` (raw `prvc_type_cd=ANONY`→Y, PSDO 아님→N, `prvc_yn=N`). 수동 저장 후 GET → 저장값 반영. 정적 `FramePrivacyMetaService.java:154-177` | FramePrivacyMetaServiceTest(8) | |
| TC-META-031 | 단건 PUT 전체 교체 | PASS | [실동작] `{Y,Y,Y}` 저장 → DB 3컬럼 반영, 이후 `{null,null,null}` PUT → 3컬럼 NULL(파생 폴백 복귀) | FramePrivacyMetaServiceTest | |
| TC-META-032 | 값 화이트리스트 ^[YN]$ | PASS | [실동작] `"1"`→400 `anonymity 는 Y 또는 N 이어야 합니다.`, `"true"`→400. 정적 `FramePrivacyMetaUpdateRequest.java:30-32` | FramePrivacyMetaControllerTest | |
| TC-META-033 | path/body srcSn 불일치 | PASS | [실동작] path=446, body srcSn=447 → **400** `path 의 srcSn 과 body 의 srcSn 이 다릅니다.` 정적 `FramePrivacyMetaController.java:85-88` | FramePrivacyMetaControllerTest | CWE-345 |
| TC-META-034 | anonymity는 export 미덮음 | PASS | [실동작] 프레임 446 `anonymity="Y"` 저장 상태에서 재승인 → export orgnl `image.anonymity="N"`, deid `"Y"`. **API 조회는 Y(표시용)·export 는 kind 파생** 이중 값이 설계대로 분리됨 | NiaJsonBuilderTest 2건 | TC-EXPORT-022 와 동일 실측 |
| TC-META-035 | 벌크 저장 N+1 제거 | PASS | [실동작+정적] 2건 벌크 PUT 200, 로그 1행 `bulk-updated count=2 rawSns=1`. 정적 `FramePrivacyMetaService.java:100-136` — `findAllById` 1회 · rawSn distinct 인가 1회(`authorizedRawSns.add`) · `saveAll` 1회 | — | |
| TC-META-036 | 벌크 미존재 프레임 404 | PASS | [실동작] `[446, 999999]` 벌크 → **404** `프레임을 찾을 수 없습니다.` + 446 DB **미변경**(롤백) | FramePrivacyMetaServiceTest | |
| TC-META-037 | 벌크 타 영상 403 | PASS | [실동작] WORKER 로 `[446(본인), 384(타영상)]` 벌크 → **403** `본인에게 배정되지 않은 영상입니다.` + 두 행 모두 DB 미변경. 404先→403後 순서도 TC-META-036 과 대조해 보존 확인 | FramePrivacyMetaServiceTest | |
| TC-META-038 | 벌크 원자성 | PASS | [실동작] 위 404/403 두 케이스 모두 선행 정상 항목(446)이 **미반영** = `@Transactional` 전체 롤백. 정적 `FramePrivacyMetaService.java:99` | — | |
| TC-META-039 | APPROVED 후 수정 통지 디바운스 | PARTIAL | [정적] `FramePrivacyMetaService.java:126-129` 프레임별 `TaskModifiedEvent(META_UPDATED)` 발행 → 다운스트림 `ControlNotifyDebouncer` 코얼레스. **로컬은 `control-notify.enabled=false` 로 소비 리스너 빈 미생성 → 통지·디바운스 실동작 미관측** | — | 로컬 설정 제약 |
| TC-META-040 | 벌크 항목 수 초과/빈 목록 | PASS | [실동작] `items:[]` → **400** `items 는 1건 이상이어야 합니다.` 상한은 정적 `FramePrivacyBulkRequest.MAX_ITEMS=5000`(@Size) — 5000 초과 미재현 | FramePrivacyMetaControllerTest | 근거 드리프트: TC 표는 `FramePrivacyMetaController.java:97-99`(=@ApiResponses 주석 블록), 실 검증은 `FramePrivacyBulkRequest.java:20-24` |
| TC-META-041 | WORKER 본인배정/미인증 | PASS | [실동작] WORKER → 타 영상 프레임 384 GET/PUT **403**, 토큰 없음 **401**, PORTAL 채널 **403**, 미존재 srcSn **404** | FramePrivacyMetaServiceTest | |
| TC-META-042 | 로그 판단값 미출력 | PASS | [실동작] 로그 `[FramePrivacyMeta] updated srcSn=446 rawSn=26` / `bulk-updated count=2 rawSns=1` — **Y/N 판단값 미노출** | — | CWE-359 준수 |

---

## ★ export JSON 실물 대조 (rawSn=26)

### 확인한 파일 경로 (컨테이너 `klid-backend` 내부)
- `/app/storage/labeling/26/v1/orgnl/frame-0.json` · `/app/storage/labeling/26/v1/deid/frame-0.json` (승인 시 최초 산출, 각 16쌍 = 32파일)
- `/app/storage/labeling/26/v2/orgnl/frame-0.json` · `/app/storage/labeling/26/v2/deid/frame-0.json` (**수동 메타 입력 후 재승인** 산출)
- 대조군: `/app/storage/labeling/17/v1/orgnl/frame-0.json`(구 코드 07-23) vs `/app/storage/labeling/17/v2/orgnl/frame-0.json`(현 코드) · `/app/storage/labeling/13/v1/deid/`(빈 폴더, PARTIAL) · `/app/storage/labeling/4/v1/`(양쪽 빈 폴더, FAILED)

### 필드별 3자 대조 (DB ↔ JSON ↔ 기대스펙)

| JSON 경로 | DB 원천 (실측값) | JSON 값 (v1 / v2) | 기대 스펙 | 판정 |
|-----------|-----------------|-------------------|-----------|:--:|
| `info.version` | (상수) | `1.3` | xlsx v1.3 | 일치 |
| `dataset.src_path`(orgnl) | `ls_dataset_video_meta.raw_file_path_nm` | `/app/storage/raw/autolabel-test/a59263dc….mp4` | 원본 raw 경로 | 일치 |
| `dataset.src_path`(deid) | `ls_deident_proc_log.de_idntf_file_path_nm` = `/app/storage/deidentified/videos/26/a59263dc…_202607250142_mask.mp4` | 동일 문자열 | 비식별 경로(원본 미노출) | 일치 |
| `video.id` / `image.video_id` | `raw_sn=26` | `"26"` | rawSn | 일치 |
| `video.length` | `vdo_len_sec=32` | `"32"` | 초 문자열 | 일치 |
| `video.weather` | `ls_data_raw.wthr_nm` = NULL / (v2 시점) `"비"` | `null` / **`"비"`** | 수동값, 없으면 null | 일치(수동 우선 확인) |
| `video.time_of_day` | raw=NULL, `ls_dataset_video_meta.day_ngt_cd`=`NGT` / (v2) raw=`DAY` | `"NGT"` / **`"DAY"`** | 수동 우선, 없으면 파생 | 일치 |
| `video.season` | raw=NULL, meta=`SUMMER` / (v2) raw=`SPRING` | `"SUMMER"` / **`"SPRING"`** | 동상 | 일치 |
| `video.anonymity` | (DB 원천 없음 — ExportKind 파생) | orgnl `"N"` / deid `"Y"` | kind 파생, 수동 미덮음 | 일치 |
| `video.pseudonymity` | `prvc_type_cd=ANONY`(≠PSDO) | `"N"` | 파생 | 일치 |
| `video.privacy_included` | `prvc_yn='N'` | `"N"` | 그대로 | 일치 |
| `video.event_id` / `event_name` | `evnt_type_cd=EV02000201` / `evnt_nm=쓰러짐` | 동일 | 그대로 | 일치 |
| `video.cctv_name` | `cctv_nm=CCTV-강남구-001` | 동일 | 그대로 | 일치 |
| `video.vd_description` | (원천 없음) | `null` | 키 유지·값 null | 일치 |
| `event` | `ls_evnt_anno`(rawSn=26 부재) | `null` | 없으면 null | 일치 (**self-fill 없음**) |
| `image.id` / `frame_num` | `ls_data_src.src_sn=446` / `frm_no=0` | `446` / `0` | 그대로 | 일치 |
| `image.date_captured` | `ls_data_src.sht_dt=2026-07-25T18:00` | `"2026-07-25T18:00"` | 그대로 | 일치 |
| `image.anonymity` | `anony_incl_yn` NULL /(v2) `Y` | orgnl `"N"` / deid `"Y"` — **v2 도 동일** | kind 파생 고정 | 일치 |
| `image.pseudonymity` / `privacy_included` | `psdo_incl_yn`/`prvc_incl_yn` NULL /(v2) `Y`/`Y` | `"N"/"N"` / **`"Y"/"Y"`** | 수동 우선 | 일치 |
| `image.width/height` | `ls_dataset_video_meta.vdo_wdth/vdo_hgt` = NULL | `null` | 미보유 시 null | 일치(원천 없음 → 날조 안 함) |
| `annotations[]` | `ls_data_lbl` (rawSn=26 총 131건) | frame-0 에 bbox 4 + polygon 1 | 라벨 매핑 | 일치 |
| `categories[]` | `ls_label` 사용분 | person(1)/car(2)/bus(5), `type:"bbox"` | 사용 라벨만 | 일치 |

### 원천 없이 채워진 필드(self-fill 의심) 목록

| 필드 | 실측 값 | 원천 | 판정 |
|------|--------|------|:--:|
| `video.time_of_day` | `NGT` | **없음** — `ls_data_raw.day_ngt_cd` NULL, 관제 `MNG_CLIP_EVNT_LST.HR_TYPE_CD` 미독. `SHT_DT.hour>=18 → NGT` 규칙으로 코드가 생성 | **self-fill (E-ISSUE-42)** |
| `video.season` | `SUMMER` | **없음** — `sesn_cd` NULL, 관제 `SESN_CD` 미독. `SHT_DT.month` 규칙으로 생성 | **self-fill (E-ISSUE-42)** |
| `video.weather` | `null` | 없음 → **null 유지** | 정상 (날조 안 함) |
| `video/image.anonymity` | `N`/`Y` | ExportKind(산출 종류) — 실제 산출물 성격과 1:1 대응 | 정상 (의도된 파생, DB 근거 있음) |
| `video.pseudonymity`, `image.pseudonymity`/`privacy_included` | `N` | `ls_data_raw.prvc_type_cd`/`prvc_yn` 실값 | 정상 |
| `event`, `vd_description`, `width/height/fps/frames/pixel` 등 | `null` | 없음 → null | 정상 |

> 결론: **날씨·event·미보유 필드는 self-fill 하지 않는다**(정상). 단 **시간대·계절 2필드만 원천 없이 코드 규칙으로 생성**되며, 이 값이 export JSON·데이터마트 뷰에 `MANUAL/DERIVED` 구분자 없이 실린다.

---

## 이슈 상세

### [E-ISSUE-41] TC-EXPORT-007 — 해상도 파생 영상의 orgnl/deid 프레임 경로가 **동일**해 2벌 산출이 바이트 동일하고 anonymity 가 오표기됨
- **심각도**: HIGH (개인정보 메타 오표기 · 데이터마트 계약 위반 · 저장소 2배 낭비)
- **기대 동작(기대효과)**: `V_COMPLETED_FRAME` 계약대로 `ORIGINAL_PATH`(원본)와 `DEIDENTIFIED_PATH`(비식별)가 **항상 상이**하고, `orgnl/` 산출물(anonymity="N")은 비식별 처리되지 않은 원본 픽셀, `deid/` 산출물(anonymity="Y")은 비식별 픽셀이어야 한다.
- **현재 동작(이슈 내용)** [실동작]:
  - `ls_data_src`(raw_sn=18,19 — 해상도 파생) 실측: `src_file_path_nm` == `de_idntf_src_file_path_nm` = `/app/storage/raw/resolution/19/frames/frame-N.jpg` (완전 동일 문자열, 12/12행).
  - 산출물 md5 동일: `/app/storage/labeling/19/v3/orgnl/frame-0.jpg` = `/app/storage/labeling/19/v3/deid/frame-0.jpg` = `ae0d1773889308f3435a5ecb122f5523`.
  - 코드 근거 — `ResolutionPersistService.java:243-247` `LsDataSrc.create(newRawSn, frameNo, videoFrameNo, dst, dst, ...)` (주석 "파생본은 비식별 산출 → src=deid 경로 동일" 로 **의도적**).
  - 결과: `orgnl/frame-0.json` 이 `video.anonymity="N"`, `image.anonymity="N"` 으로 나가지만 픽셀 실체는 비식별본이다(`NiaJsonBuilder.java:145` 가 kind 로만 결정). 역으로 `deid/` 는 정상.
  - 또한 `FrameSource.java:71-78` 이 DEIDENTIFIED 에 대해 `deidBase` 실패 시 `rawBase` fallback 을 허용해 이 경로가 통과한다(설계상 해상도 파생 수용 목적).
- **재현/확인 경로**:
  1) `SELECT src_file_path_nm, de_idntf_src_file_path_nm FROM ls_data_src WHERE raw_sn=19;` → 두 컬럼 동일
  2) `docker exec klid-backend md5sum /app/storage/labeling/19/v3/{orgnl,deid}/frame-0.jpg` → 동일 해시
  3) `grep '"anonymity"' /app/storage/labeling/19/v3/orgnl/frame-0.json` → `"N"`
- **영향**: 학습데이터 라벨 메타 오표기(비식별본을 "익명화 안 됨"으로 배포). 데이터마트 `V_COMPLETED_FRAME` 의 "두 경로 항상 상이" 불변식 파손. 해상도 파생 1건마다 동일 바이트를 버전당 2벌 복사(디스크 2배). 보안 CWE-1188(부정확한 보안 속성 초기화) 성격 — PII 유출 방향은 아니나 **역방향 오표기**.
- **수정 방향(제안, 구현 금지)**: ①해상도 파생 프레임의 `SRC_FILE_PATH_NM` 을 부모 **원본** 프레임을 리스케일한 별도 산출로 두고 deid 경로를 분리하거나, ②파생 영상은 원천이 비식별본임을 `LsDataRaw`(예: `orgnl_raw_sn` + `de_ident_yn`) 로 판정해 **ORIGINAL 산출을 스킵하거나 `anonymity="Y"` 로 표기**하도록 `NiaJsonBuilder`/`VideoMetaMapper` 의 kind 파생을 보정. ③최소 조치로 export 시 두 경로 동일이면 `deid` 1벌만 산출.

### [E-ISSUE-42] TC-META-002 / TC-EXPORT-028 — `time_of_day`·`season` 이 원천 없이 촬영일시 규칙으로 생성되어 export·데이터마트에 **파생 표시 없이** 실림 (self-fill)
- **심각도**: MEDIUM (데이터 정확성 · 관제 원천 미활용)
- **기대 동작(기대효과)**: 촬영환경 3필드의 원천은 ①작업자 수동입력 또는 ②관제 공유 `MNG_CLIP_EVNT_LST.WTHR_CD/SESN_CD/HR_TYPE_CD` 실값이어야 하고, 어느 쪽도 없으면 `null`(미상) 이거나 최소한 파생임이 소비자에게 식별 가능해야 한다.
- **현재 동작(이슈 내용)** [실동작]:
  - rawSn=26: `ls_data_raw.day_ngt_cd/sesn_cd` 모두 NULL 인데 `ls_dataset_video_meta` 에 `NGT`/`SUMMER` 가 동결되고 export JSON `video.time_of_day="NGT"`, `season="SUMMER"` 로 출력.
  - 생성 규칙 — `TimeOfDaySeasonDeriver.java:49-55`(hour ∈ [6,18) → DAY, else NGT), `:63-74`(월 3-3-3-3). 호출 지점 `DatasetVideoMetaSnapshotService.java:109-110`.
  - **정확성 결함 실증**: rawSn=26 의 `sht_dt = 2026-07-25 18:00`(한국 7월 일몰 ≈ 19:50) → 실제로는 주간이나 규칙상 `NGT`. 경계 18:00 고정이 계절과 무관해 여름 저녁은 항상 야간으로 오분류된다.
  - **원천 미활용 실증**: 저작도구 엔티티 `MngClipEvntLst.java:20` 주석이 `SESN_CD/WTHR_CD/HR_TYPE_CD/PRVC_TYPE_CD` 를 **명시적으로 매핑 생략**. 코드 전체에 `WTHR_CD`/`HR_TYPE_CD` 참조 0건(grep).
  - **소비자 구분 불가**: 조회 API 는 `timeOfDaySource:"DERIVED"` 를 주지만(투명), **export JSON·`LS_DATASET_VIDEO_META`·데이터마트 뷰에는 MANUAL/DERIVED 구분자가 없다** → 관제/데이터마트는 파생 추정값을 관측값과 동일하게 소비한다.
- **재현/확인 경로**: `SELECT sht_dt, day_ngt_cd, sesn_cd FROM ls_data_raw WHERE raw_sn=26;`(전부 NULL) ↔ `SELECT day_ngt_cd, sesn_cd FROM ls_dataset_video_meta WHERE raw_sn=26 AND active_yn='Y';`(NGT/SUMMER) ↔ `grep time_of_day /app/storage/labeling/26/v1/orgnl/frame-0.json`
- **영향**: 학습데이터 속성(주야간·계절)이 사실과 다를 수 있고, 이 속성으로 필터링/증강 유형 매칭을 하면 오염이 전파된다. 관제에 이미 존재하는 정답 값을 두고 추정값을 배포한다(메모리 `control-clip-meta-source-of-truth` 의 확정 방향과 배치).
- **수정 방향(제안, 구현 금지)**: ①`MngClipEvntLst` 에 `WTHR_CD/SESN_CD/HR_TYPE_CD/PRVC_TYPE_CD` 매핑 추가 → 적재/동결 시 **관제 실값 우선**(우선순위: 수동 > 관제 > 파생 > null). ②관제 코드도메인↔`WTHR_NM` 매핑표 확보(ERD-024). ③파생만 남는 경우 `LS_DATASET_VIDEO_META` 에 출처 컬럼(예: `ENV_SRC_CD` MANUAL/CTRL/DERIVED)을 추가해 export·뷰로 전파하거나, ④파생을 **중단하고 null** 로 두어 self-fill 을 제거.

### [E-ISSUE-43] TC-EXPORT-006 — 라벨/프레임 산출 불가 영상이 승인은 되고 export 만 FAILED 로 남아 학습데이터 0건인 채 "검수 완료"로 노출됨
- **심각도**: MEDIUM (기보고 `D-ISSUE-04` 의 E 구간 실증 — 중복 아님, 산출물 관점 보강)
- **기대 동작(기대효과)**: 검수 승인 = 학습데이터 확정이므로, 산출 가능한 프레임이 0건이면 승인 자체가 차단되거나 최소한 승인 후 재시도/알림 경로가 있어야 한다.
- **현재 동작(이슈 내용)** [실동작]: rawSn=4(v1,v2)·5·6 → `ls_dataset_export` 4행 `FAILED, frame_cnt=NULL`. 대응 폴더 `/app/storage/labeling/4/v1/{orgnl,deid}` 는 **생성되었으나 비어 있음**. `ls_raw_data_status` 는 `APPROVED` 유지. 코드 근거 `DatasetExportService.java:126-131`(`markFailed` 후 예외 미전파 — 승인 불변은 의도된 계약).
  - 부수: 실패해도 빈 디렉터리가 남고 정리(cleanup)되지 않는다(`DatasetExportWriter.java:80-87` 에서 선생성).
  - 부수: `retention` 미구현이 코드 TODO 로 명시(`DatasetExportService.java:110-111`) — 승인 반복마다 v1..vN 이 무한 누적(rawSn=19 는 이미 v3).
- **재현/확인 경로**: `SELECT * FROM ls_dataset_export WHERE export_stts_cd='FAILED';` → `docker exec klid-backend ls -R /app/storage/labeling/4`
- **영향**: 데이터마트가 `V_COMPLETED_VIDEO.EXPORT_PATH_NM` 로 픽업하면 빈 폴더/NULL 을 얻는다. 운영 알림 없이 무산출 승인이 축적.
- **수정 방향(제안, 구현 금지)**: 승인 전 `프레임 수>0 && 산출 가능 이미지>0` 선검증(reject 대신 경고+차단), export FAILED 시 재시도 잡/운영 알림 연결, 빈 산출 디렉터리 cleanup, retention 잡 도입.

### [E-ISSUE-44] TC-EXPORT-020 — export 최상위 VLM 키가 `event` 로 rename 되었으나 테스트케이스 문서·기존 산출물(v1)은 `event_annotation` — 계약 이원화 + `cot` 배열/객체 혼재
- **심각도**: LOW~MEDIUM (다운스트림 파서 이원화)
- **기대 동작(기대효과)**: 산출 JSON 의 VLM 블록 키와 내부 구조가 단일 계약이어야 한다.
- **현재 동작(이슈 내용)** [실동작]:
  - 현 코드: `NiaAnnotationDoc.java:33` `@JsonProperty("event")` → rawSn=17 **v2**(오늘 산출) = `"event"`, rawSn=19 v3 = `"event": null`.
  - 과거 산출물: rawSn=17 **v1**(07-23) = `"event_annotation"` + `image.orign_file_name` 잉여키 존재. **두 포맷이 같은 스토리지에 공존**(v1/v2 폴더).
  - `cot`: 정본은 객체(`{"1단계":..}`) 이나(`EventAnnotationPayload.java:88-96`) 동결 소스는 `ls_evnt_anno.anno_cn` **원문 JsonNode pass-through** 이므로 기존 저장분(`evnt_anno_sn=1,2`)의 **배열** 형태가 그대로 export 된다 — 실측 v2 JSON `"cot" : [ "111", "222", "3333" ]`. 코드 주석이 "배열 동결본 백필은 out of scope" 로 명시(의도된 잔존).
  - 테스트케이스 문서(TC-EXPORT-020/021)는 여전히 `event_annotation` 표기 → **근거 드리프트**.
- **재현/확인 경로**: `grep -n '"event' /app/storage/labeling/17/v1/orgnl/frame-0.json` vs `.../17/v2/orgnl/frame-0.json`
- **영향**: 관제/데이터마트 파서가 두 키와 두 `cot` 형태를 모두 다뤄야 한다.
- **수정 방향(제안, 구현 금지)**: ①구 버전 산출물 재생성 또는 폐기 정책 명시, ②`cot` 배열 동결본 백필(또는 export 시 배열→`n단계` 객체 정규화 — 이미 `CotDeserializer` 로직 재사용 가능), ③테스트케이스 문서 `event_annotation`→`event` 정정.

### [E-ISSUE-45] TC-META-008 / TC-META-039 — 로컬 설정으로 TASK_MODIFIED 통지 경로 실동작 미검증 (검증 한계)
- **심각도**: INFO (제품 결함 아님 — 검증 커버리지 공백)
- **기대 동작**: 촬영환경/프레임 개인정보 메타를 APPROVED 후 수정하면 관제로 `TASK_MODIFIED(META_UPDATED)` 가 rawSn 단위 1회 코얼레스되어 발행.
- **현재 상태**: `ControlNotifyEventListener.java:21` `@ConditionalOnProperty(name="authoring.control-notify.enabled", havingValue="true")` + 로컬 `false` → **소비 리스너 빈 자체가 없다.** 이벤트 발행부(`EnvironmentMetaService.java:112-114`, `FramePrivacyMetaService.java:126-129,147-150`)는 정적 확인만 완료. 로그에 `TaskModified`/`Debounce` 출력 0건.
- **영향**: TC-META-008 의 통지 절반, TC-META-039 전체가 실동작 미검증 → 두 케이스 PARTIAL.
- **수정 방향(제안)**: 목업 관제 inbound 엔드포인트(mock-server)로 `CONTROL_NOTIFY_ENABLED=true` 를 켠 별도 회차에서 재검증.

### [E-ISSUE-46] 검증 한계 — 목업 비식별이 원본을 복사하므로 orgnl/deid 산출물의 **픽셀 차이**를 실증할 수 없음
- **심각도**: INFO (환경 제약)
- **현재 상태**: rawSn=26 의 `orgnl/frame-0.jpg` 와 `deid/frame-0.jpg` md5 동일(`f7d5e59c…`). 단 **경로는 상이**(`/app/storage/raw/frames/raw/26/…` vs `/app/storage/deidentified/frames/deid/26/…`) 이고, mock-server 비식별이 원본 파일을 그대로 복사하는 구현이므로 **코드 결함이 아니다**(E-ISSUE-41 의 rawSn=19 와는 다름 — 그쪽은 경로 자체가 동일).
- **영향**: "비식별 픽셀이 실제로 마스킹되었는가"는 이번 회차에서 판정 불가.
- **수정 방향(제안)**: 실 KPST 연동 환경에서 재확인.

---

## 요약

- 총 **56건** / PASS **53** / FAIL **0** / PARTIAL **2**(TC-META-008, TC-META-039) / BLOCKED 0 / N/A 0 / 확인필요 0
- 실동작 검증: 44건 / 정적·테스트만: 12건 (TC-EXPORT-003·008·009·011·013·024(부분)·025·029·031, TC-META-011·012, TC-META-039)
- **근거 라인 드리프트: 4건**
  1. TC-EXPORT-020/021 근거 `NiaJsonBuilder.java:66-100` / `:59-62` — 실제 pass-through 는 `:121`(`ctx.eventAnnotation()`), null 키 유지는 `NiaAnnotationDoc.java:26`(`@JsonInclude ALWAYS`)
  2. TC-EXPORT-020/021 **기대 키명** `event_annotation` → 실제 `event`(문서 stale, E-ISSUE-44)
  3. TC-EXPORT-026 근거 `NiaVideo.java:12` → 실제 `@JsonInclude` 는 `:13`
  4. TC-META-040 근거 `FramePrivacyMetaController.java:97-99`(=@ApiResponses 주석) → 실 검증 지점 `FramePrivacyBulkRequest.java:20-24`
  - (부수) TC-EXPORT-007 근거 `ResolutionPersistService.java:234-251` 은 **9-arg** `LsDataSrc.create` 이며 파일 경로는 `video/service/`(TC 표는 경로 미기재)
- **self-fill 결함: 1건** — `video.time_of_day` / `video.season` (E-ISSUE-42). 반대로 `weather`·`event`·`vd_description`·미보유 필드는 **원천 없을 때 null 유지**로 정상.
- 신규 이슈 6건: E-ISSUE-41(HIGH) · 42(MED) · 43(MED) · 44(LOW~MED) · 45(INFO) · 46(INFO)

### 검증 중 수행한 상태 변경 및 복원
| 대상 | 변경 | 복원 |
|------|------|:--:|
| rawSn=26 촬영환경 3필드 | 비/DAY/SPRING 저장 → 재승인(v2 산출) | **null 복원 완료** (`ls_data_raw` 3컬럼 NULL) |
| 프레임 446/447/448 개인정보 3필드 | Y/Y/Y 등 저장 | **NULL 복원 완료** |
| rawSn=11 weather | 맑음 저장(IDOR 오판 테스트) | **null 복원 완료** |
| rawSn=26 / rawSn=17 검수 상태 | submit→start→approve 1사이클 | **APPROVED 복귀 확인**(라벨 131건 불변) |
| 잔존 부산물 | `labeling/26/v2`, `labeling/17/v2` export 폴더 신규 생성(삭제 안 함) | 승인 경로 R6 계약상 정상 누적 |
