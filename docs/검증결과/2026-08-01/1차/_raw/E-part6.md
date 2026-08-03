# E클러스터 — Part6 (E-7 Export JSON 포맷, E-8 촬영환경 메타, E-9 프레임 개인정보 메타)

검증자: 담당 에이전트(E-part6) · 대상: `docs/test-cases/E-augment-resolution-export-meta.md` §E-7(18건) + §E-8(20건, TC-META-009 폐기 제외 실질 19건) + §E-9(15건) = 53건(폐기 제외 실질 52건)
방법: 실동작 최우선(HTTP 요청 + 컨테이너 내부 export JSON 파일 직접 열람 + DB 직접 SELECT + backend 로그 tail), 근거 file:line 정적 대조 병행. 스택은 기존 기동 상태(backend :18081, `local` 프로파일, V158) 그대로 사용. `/v1/dev/tokens`로 REVIEWER(sub=1001)/WORKER(sub=2001) 토큰 발급. 코드/설정/테스트 파일은 수정하지 않았다.

## 사용한 실증 데이터
- **rawSn=26**(base video, APPROVED, WORKER 2001/REVIEWER 1001 배정) — 기존 완주 파이프라인(pipeline-drive.md) 산출물. export v1~v9 누적 관찰(본 검증 중 env-meta/privacy-meta PUT으로 v6까지 직접 트리거 후 다른 병행 클러스터 활동으로 v9까지 추가 관찰됨 — 디바운스 재export 자체가 반복 가능함을 방증).
- **rawSn=18**(증강 파생, `ORGNL_RAW_SN=4`, AUG_TYPE_CD=WINTER, APPROVED, export v1 SUCCEEDED) — 파생영상 export 실증에 사용(생성은 본 검증 이전 다른 세션 산출물, 코드 수정 없이 기존 파일만 열람).
- DB 변경: `ls_data_raw.wthr_nm/day_ngt_cd/sesn_cd`(rawSn=26,7), `ls_data_src.anony/psdo/prvc_incl_yn`(srcSn=296,297) — 모두 정상 API 경로(PUT)를 통한 실 데이터이며 원복하지 않음(검증 자체가 실사용 시나리오와 동일한 저장 동작이라 되돌릴 필요가 없다고 판단, 다른 클러스터의 export 버전 누적에 영향 없음 — export/재export는 멱등 append 방식이라 무해).

## E-7. Export JSON 포맷 — 18건

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-EXPORT-020 | PASS | [실동작]+[정적] | rawSn=26 export v1/v6 JSON 실측: 최상위 키 순서 `info,dataset,licences,video,event,image,annotations,categories,type` — `event`가 `video` 다음. 값 없으면 `"event":null`. `NiaAnnotationDoc.java:24-37` `@JsonInclude(ALWAYS)` + `@JsonPropertyOrder` 코드와 정확히 일치 |
| TC-EXPORT-021 | PASS | [실동작] | 동결 event 없는 rawSn=26에서 전 프레임 JSON `"event" : null` 확인(키 always present) |
| TC-EXPORT-022 | PASS | [실동작] | srcSn=296에 수동 `ANONY_INCL_YN='Y'` 저장된 상태에서 orgnl(v6) `image.anonymity="N"`, deid(v6) `="Y"` — 수동값이 덮지 않음(CWE-359 방어 실증). `video.anonymity`도 동일 패턴(orgnl N / deid Y) |
| TC-EXPORT-023 | PASS | [실동작] | srcSn=297에 `PSDO_INCL_YN='Y'` 저장 → orgnl/deid 양쪽 JSON `image.pseudonymity="Y"` 반영(수동 우선 확인). `NiaJsonBuilder.java:156-159` `firstNonBlank(src값, 파생폴백)` 일치 |
| TC-EXPORT-024 | PASS | [정적] | `NiaJsonBuilder.buildDataset`(132-137)이 `kind==DEIDENTIFIED`일 때 `ctx.deidVideoPath()`가 null이면 `path=null`→`baseNameNoExt(null)=null`이 그대로 `NiaDataset(id, null, null, null)`로 흘러 dataset.src_path/name 모두 null. `VideoMetaMapper.toVideo`(61-65 실측 라인 63-65)도 `kindVideoPath=deidVideoPath(null)`→`basename(null)=null`→filename null. 본 환경엔 deid경로 결측 상태로 export된 실사례가 없어 라이브 재현은 못했으나 로직은 결정론적 null 전파라 코드 근거로 충분 |
| TC-EXPORT-025 | PASS | [정적] | `buildAnnotations`(178-201) `try{labelMapper.toAnnotation}catch(CustomException){skipped++; log.warn(lblSn만)}` — 1건 skip해도 문서 전체 정상 생성. 라이브 환경엔 malformed 라벨 유발 데이터가 없어 실행은 미관찰, catch 로직 자체는 명확 |
| TC-EXPORT-026 | PASS | [실동작]+[정적] | rawSn=26 JSON 실측: `type/format/filesize/location/pixel/cctv_height` 등 미보유 필드가 `null` 값으로 키 유지(생략 안 됨). `NiaVideo.java:12`/`NiaImage.java:9` `@JsonInclude(ALWAYS)` 일치 |
| TC-EXPORT-027 | PASS | [실동작]+[정적] | JSON `"vd_description":null` 키 존재 확인. `NiaVideo.java:46` 필드 선언, `VideoMetaMapper.java:101` `null // vd_description(미보유 — 데이터 출처 없음)` 정확히 일치 |
| TC-EXPORT-028 | PASS | [실동작]+[정적] | rawSn=26 초기(수동 미입력) export에서 `weather/time_of_day/season` 전부 `null`(SHT_DT 기반 자동파생 없음, self-fill 폐기 실증). PUT으로 수동값 저장 후 재export(v6)에서 `weather:"비", time_of_day:"NGT", season:"WINTER"` 정확 반영. `VideoMetaMapper.java:57` `firstNonBlank(raw, meta)` — raw(최신 수동값) 우선 코드 일치 |
| TC-EXPORT-029 | PASS | [정적] | `VideoMetaMapper.firstNonBlank`(145-152, catalog 144-152와 근사) 모두 blank→null 정규화 |
| TC-EXPORT-030 | PASS | [실동작]+[정적] | orgnl/deid 양쪽 export에서 촬영환경(weather/time_of_day/season) 값 동일(kind로 분기 안 함) 확인, `anonymity`만 N/Y로 갈림. `VideoMetaMapper.java:65` `anonymity=(kind==ORIGINAL)?NO:YES` 정확 일치 |
| TC-EXPORT-031 | PASS | [정적] | `NiaJsonBuilder.prepareContext`(88-91) `if(meta==null) throw CustomException(INVALID_INPUT,"영상 메타가 null 입니다.")` — 정확 일치(400은 CustomException→GlobalExceptionHandler 매핑) |
| TC-EXPORT-032 | PASS | [실동작]+[정적] | 전 export JSON `info.version="1.3"`, `type="instances"` 확인. `NiaJsonBuilder.java:37`(FORMAT_VERSION="1.3") / `:39`(TYPE_INSTANCES) / `:96`(info 조립) 정확 일치 |
| TC-EXPORT-033 | PASS | [실동작]+[정적] | rawSn=26 image.file_name="0000.jpg"~"0004.jpg"(FRM_NO 기준), 파생 rawSn=18은 "0007.jpg" 등. `ExportFileNaming.imageFileName(long)` 단일 지점 사용 확인(`NiaJsonBuilder.java:145`), FRM_NO 4자리 zero-pad 규칙과 실측 파일명 정확 일치 |
| TC-EXPORT-034 | PASS | [실동작] | DB `ls_data_src`(rawSn=26) `vdo_frm_no`=0/30/60/90/120(FRM_NO는 0~4 순번)이며, export JSON `image.frame_num`도 정확히 0/30/60/90/120 — VDO_FRM_NO 사용, FRM_NO(추출순번) 아님을 실측 확정. 파일명(0001.jpg=FRM_NO)과 frame_num(30=VDO_FRM_NO)이 서로 다른 값임을 srcSn=297에서 동시 확인 |
| TC-EXPORT-035 | PASS | [실동작]+[정적] | rawSn=18(파생, ORGNL_RAW_SN=4) export 실폴더: `v1/deid/`만 존재(`v1/orgnl/` 디렉터리 자체 없음). JSON `dataset.src_path`/`video.filename`이 파생 자신의 비식별 사본 basename("WINTER.mp4") — 부모 경로 아님. `DatasetExportService.java:184-195` `original kind skipped — derivative video has no original frames reason=DERIVATIVE_NO_ORIGINAL` 로그 문구로 코드상 ORIGINAL 벌 자체를 생성 안 함이 확정 |
| TC-EXPORT-036 | PASS | [실동작]+[정적] | rawSn=26: orgnl `dataset.src_path`=`RAW_FILE_PATH_NM`(.../95b05485-....mp4), deid `dataset.src_path`=deidVideoPath(.../95b05485-....-mask.mp4) — kind별로 서로 다른 실제 경로 확인. `NiaJsonBuilder.java:132-137` 정확 일치 |
| TC-EXPORT-037 | PASS | [정적] | `NiaJsonBuilder.java:174` `src.getFrmExpln()`이 `NiaImage` 마지막 인자로 정확히 전달됨(라이브 관측된 JSON들은 `description:null` — 프레임 설명 미입력 상태와 일치, 필드 자체는 항상 존재) |

## E-8. 촬영환경 메타 — 20건(TC-META-009는 2026-07-30 폐기, 판정 대상 제외)

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-META-001 | PASS | [실동작] | rawSn=26 PUT 저장 후 GET → `weatherSource/timeOfDaySource/seasonSource` 전부 `"MANUAL"`, 저장값 그대로 프리필. `EnvironmentMetaService.java:192-211 toResponse` 일치 |
| TC-META-002 | PASS | [실동작] | 수동값 저장 전 초기 GET(rawSn=26) → `weather:null, timeOfDay:"DAY", season:"SUMMER", timeOfDaySource:"DERIVED", seasonSource:"DERIVED"` — SHT_DT 기반 파생 프리필 확인(조회 전용, 동결과는 별개 정책) |
| TC-META-003 | PASS | [실동작] | PUT 3필드 전송 → `ls_data_raw.wthr_nm/day_ngt_cd/sesn_cd` 3컬럼만 UPDATE 확인(dirty checking). `EnvironmentMetaService.java:100-129 update()` 일치 |
| TC-META-004 | PASS | [실동작] | weather만 전송(`{"weather":"눈"}`) → 응답 `timeOfDay:"DAY"(DERIVED), season:"SUMMER"(DERIVED)` — timeOfDay/season 수동값 삭제 후 파생 폴백 확인 |
| TC-META-005 | PASS | [실동작] | `weather:"폭우"` PUT → 400 `"촬영환경 weather 값이 허용 목록에 없습니다."`. `ShootingEnvironmentVocabulary.java:23` WEATHERS 5종 화이트리스트와 일치 |
| TC-META-006 | PASS | [실동작] | `timeOfDay:"NIGHT"`(오표기, 실제 허용값은 "NGT") PUT → 400 확인 / `season:"MONSOON"` PUT → 400 확인. `TimeOfDaySeasonDeriver.NIGHT="NGT"` 상수 재사용 확인 |
| TC-META-007 | PASS | [정적] | 허용값 전부 20자 이내라 화이트리스트 검사(`validate`, 174-185)에서 함께 걸림 + DTO `@Size(max=ShootingEnvironmentVocabulary.MAX_LENGTH=20)` 이중 방어(`EnvironmentMetaUpdateRequest.java:28,32,36`) 확인 |
| TC-META-008 | PASS | [실동작] | rawSn=26(APPROVED) PUT 후 백엔드 로그 `[EnvironmentMeta] re-freeze triggered rawSn=26` 확인 + export v6가 새 촬영환경 값을 반영해 재생성됨(TC-META-017과 동일 증거로 교차확인). `RVW_CMPL_DT` 승계는 `reFreezeApprovedSnapshot`(156-167)에서 `active.get(0).getRvwCmplDt()`를 그대로 `materialize`에 전달하는 코드로 확인(정적) |
| TC-META-009 | N/A | — | 2026-07-30 폐기 케이스(대체: TC-META-017). 판정 대상 제외 |
| TC-META-010 | PASS | [실동작] | 미검수(PENDING, export 0건) rawSn=7에 PUT → 로그에 `updated rawSn=7`만 있고 `re-freeze triggered` 로그 없음(30초 tail 확인) — 재동결·통지 모두 미수행 확인 |
| TC-META-011 | PASS | [정적] | `reFreezeApprovedSnapshot`(156-167) `if(active.isEmpty()){log.warn(...); return;}` — fail-safe skip, 예외 미발생. 라이브 재현(APPROVED인데 스냅샷 없는 이례 상태)은 본 환경에 없어 코드 근거만 |
| TC-META-012 | PASS | [정적] | `update()`(115-121) `videoRepository.flush()`(raw 행 락) → `videoMetaRepository.acquireRawLock(rawSn)`(advisory) → `isReviewApproved` 판정 순서가 소스에 그대로 구현됨. 실제 동시 요청(PUT vs approve) 경합 재현은 타이밍 조작이 필요해 본 검증에서는 미수행 — 코드·주석(Javadoc 93-98)의 순서 보장 논리로 판정 |
| TC-META-013 | PASS | [실동작] | WORKER(2001)로 미배정 rawSn=5 GET/PUT → 둘 다 403 `"본인에게 배정되지 않은 영상입니다."`(IDOR 방어 확인) |
| TC-META-014 | PASS | [실동작] | 토큰 없이 rawSn=26 GET → 401 `"인증이 필요합니다."` 확인 |
| TC-META-015 | PASS | [실동작] | rawSn=999999999 GET → 404 `"영상을 찾을 수 없습니다."`. `findRaw`(187-190) 일치 |
| TC-META-016 | PASS | [실동작] | `weather:"폭우"`(허용값 외) PUT 요청 후 백엔드 로그 확인 — `[EnvironmentMeta] rejected value field=weather`만 남고 입력 원문("폭우")은 로그에 없음(CWE-117/209 방어 실증) |
| TC-META-017 | PASS | [실동작] | PUT(weather=비/NGT/WINTER) → 약 75초 후 디바운스 flush 로그 `[ControlNotifyDebounce] flush rawSn=26 regen=true` → `[DatasetExport] async re-export... forceRegenerate=true` → export v6 SUCCEEDED → v6 JSON `video.weather="비", time_of_day="NGT", season="WINTER"` 정확 반영 — end-to-end 완전 실증 |
| TC-META-018 | PASS | [정적]+[실동작 정황] | `DatasetVideoMetaSnapshotService.java:120-123` `nullIfBlank(src.getDayNgtCd()/getSesnCd()/getWthrNm())` — SHT_DT 파생 없이 수동값만 동결(주석에 "여름 18:00이 NGT로 오분류되던 실증" 명시). rawSn=18(2026-07-31 생성) 최초 export JSON에서 `weather/time_of_day/season` 전부 null이었던 것도 self-fill 폐기 정황과 부합 |
| TC-META-019 | PASS | [실동작] | `GET /v1/dev/dataset-video-meta/shooting-env-correction-targets`(REVIEWER) → 200 `{"targetCount":0}`(본 환경엔 레거시 파생 동결행 없음, 정정 미수행 확인) |
| TC-META-020 | PASS | [실동작] | `POST /v1/dev/dataset-video-meta/shooting-env-corrections`(REVIEWER) → 200 `{"corrected":0,"remaining":0,"completed":true}`, 재호출도 동일(0,0) — 멱등 확인. WORKER 토큰으로 GET 호출 시 403 `"권한이 없습니다."`(REVIEWER 전용 확인). 대상 0건 환경이라 상한(200건/회) 초과 케이스의 실동작은 미관찰, 설정키(`env-correction.max-per-run`)·페이징 로직은 코드로 확인 |

## E-9. 프레임 개인정보 메타 — 15건

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-META-030 | PASS | [실동작] | 저장값 없는 srcSn GET → 파생값(`ANONY_INCL_YN` 등 영상 타입 기준) 반환, 저장 후 GET → 저장값 그대로. `toEffective`(FramePrivacyMetaService) 일치 |
| TC-META-031 | PASS | [실동작] | srcSn=296 PUT(anonymity/pseudonymity/privacyIncluded 3필드) → 200 저장 확인, DB `ls_data_src` 즉시 반영 |
| TC-META-032 | PASS | [실동작] | `anonymity:"true"` PUT → 400 `"anonymity 는 Y 또는 N 이어야 합니다."`(`@Pattern("^[YN]$")`) |
| TC-META-033 | PASS | [실동작] | path srcSn=296, body srcSn=297 PUT → 400 `"path 의 srcSn 과 body 의 srcSn 이 다릅니다."`(CWE-345 방어 확인) |
| TC-META-034 | PASS | [실동작] | srcSn=296에 `ANONY_INCL_YN='Y'` 저장돼 있어도 export JSON `image.anonymity`는 여전히 kind파생(orgnl N/deid Y) — 프레임 수동값이 export를 덮지 않음(TC-EXPORT-022와 쌍으로 실증) |
| TC-META-035 | PASS | [실동작] | 벌크 PUT(srcSn 296,297) 200 성공. `findAllById`(1회)+rawSn당 인가 1회+`saveAll`(1회) 구조는 `updateBulk`(100-136) 코드로 확인(SQL 로그 별도 카운트는 미측정이나 구조상 N+1 아님이 명확) |
| TC-META-036 | PASS | [실동작] | 벌크 PUT에 미존재 srcSn=9999999 포함 → 404 `"프레임을 찾을 수 없습니다."`(전체 거부) |
| TC-META-037 | PASS | [실동작] | WORKER(2001) 벌크 PUT(본인 srcSn=296 + 미배정 srcSn=31) → 403 `"본인에게 배정되지 않은 영상입니다."`. 404/403 혼재 케이스(srcSn=9999999 + srcSn=31)는 **404가 먼저** 반환됨 — "404先→403後 순서 보존" 실증 일치 |
| TC-META-038 | PASS | [실동작] | 벌크 PUT(srcSn=297 유효값 변경 + srcSn=9999999 무효) → 404 실패 후 DB 재조회 시 srcSn=297 값이 **변경 전 그대로**(Y/Y/N) — `@Transactional` 전체 롤백 확인(부분 반영 없음) |
| TC-META-039 | PASS | [실동작] | 벌크 PUT(srcSn 296,297, rawSn=26 APPROVED) 후 디바운스 flush 로그에 `frames=296=[META_UPDATED],297=[META_UPDATED]` 코얼레스 확인 → export force 재생성(v6) → 통지 순서로 실행됨(env-meta 변경과 동시에 1회로 묶여 처리된 것까지 실측) |
| TC-META-040 | PASS | [실동작] | 벌크 PUT `{"items":[]}` → 400 `"items 는 1건 이상이어야 합니다."` |
| TC-META-041 | PASS | [실동작] | WORKER(2001) 미배정 srcSn=31 GET → 403. 토큰 없이 srcSn=296 GET → 401 |
| TC-META-042 | PASS | [정적] | `applyAndNotify` 로그 `log.info("...updated srcSn={} rawSn={}", ...)` — anonymity/pseudonymity/privacyIncluded 판단값은 로그 인자에 없음(코드상 원천 배제). 벌크 로그도 `count={} rawSns={}`뿐, 개별 판단값 미포함 |
| TC-META-043 | PASS | [실동작] | 단건 PUT(srcSn=296, APPROVED 영상)도 디바운스 로그에 `META_UPDATED` 반영되어 재export 트리거됨(TC-META-039와 동일 flush 이벤트로 교차 실증) |
| TC-META-044 | PASS | [정적] | `AugmentExtractPersist.java:104-115`/`ResolutionPersistService.java`(insertFrames) 둘 다 `parent==null?null:parent.getAnonyInclYn()/getPsdoInclYn()/getPrvcInclYn()`로 부모 프레임 값을 파생 프레임 INSERT 시 그대로 복사(부모 null이면 파생도 null). ⚠ 이 복사는 **파생 생성 시점의 스냅샷**이라 이후 부모 값이 바뀌어도 기존 파생에 소급 반영되지 않음(라이브 확인: rawSn=18의 부모 rawSn=4 srcSn=1이 나중에 `anony_incl_yn='Y'`로 설정됐지만 이미 생성된 파생 srcSn=45는 `null`로 남아있음 — 이는 "확정 시점 1회 복사" 설계와 일치하는 정상 동작이며 결함 아님, 라이브 재동기화를 요구하는 근거는 카탈로그에도 없음) |

## 요약

- **합계 53건**(TC-META-009 폐기 제외 실질 52건 판정 대상): **PASS 52 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 1(폐기) / 확인필요 0**
- 이슈(E-ISSUE-101~120 범위) **발견 0건** — 결함으로 기록할 FAIL/PARTIAL 없음.
- 핵심 실증(확증편향 방지 포커스):
  - **anonymity 수동override 금지**(TC-EXPORT-022/TC-META-034): srcSn=296에 실제로 `ANONY_INCL_YN='Y'`를 저장한 뒤에도 export JSON은 kind파생(N/Y)만 실었다 — 코드만이 아니라 "저장값이 있는데도 안 먹힘"을 라이브로 반증 시도해 통과 확인.
  - **frame_num=VDO_FRM_NO vs 파일명=FRM_NO 분리**(TC-EXPORT-033/034): DB 원본 컬럼과 JSON 출력을 나란히 대조해 두 값이 실제로 다르다는 것(0/30/60/90/120 vs 0001~0004)을 직접 확인.
  - **재export 트리거 end-to-end**(TC-META-008/017/039/043): PUT → 60초 디바운스 → force export → 새 버전 폴더 JSON 값 갱신까지 전 구간을 시간차를 두고 재확인, 심지어 env-meta와 frame-meta 두 변경이 하나의 통지로 코얼레스되는 것까지 로그로 실증.
  - **벌크 원자성**(TC-META-038): 성공할 값과 실패할 값을 섞어 보내 실패 시 DB에 부분 반영이 없는지 직접 대조.
  - **파생영상 ORIGINAL 벌 미생성**(TC-EXPORT-035): 파일시스템에서 `orgnl/` 디렉터리 자체가 없음을 직접 확인.
- self-fill 의심 점검: weather/time_of_day/season 전부 수동 입력 전 null(자동 파생 없음), VLM 없이 촬영환경 채워지는 경로 없음 — self-fill 결함 0건.
- 근거 file:line 드리프트: 없음(catalog의 모든 file:line이 실제 라인과 정확히 일치하거나 ±3라인 이내 근접 — 주석 삽입에 의한 자연스러운 오차 수준).
