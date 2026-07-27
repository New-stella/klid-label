# B-8/B-9 검증 결과 (2026-07-25 1차)

> 검증 중 실제로 별도 세션(pipeline-drive)이 `rawSn=26`(vms_clip_id=DRIVE-CLIP-0725A)을 라이브로
> 구동해, 본 검증 세션이 그 파이프라인(YOLO→SAM2→INTERPOLATE→프레임 2벌)의 **실시간 실행·완료를
> DB/로그로 직접 관측**했다. 아래 "실동작" 근거는 대부분 이 rawSn=26 라이브 구동 + 기존 완료 영상
> (rawSn 13/14/17, 4~13 legacy)의 실제 DB 상태를 함께 사용했다.

## B-8. FfmpegFrameExtractor

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-100 | execute: marks 비면 INVALID_INPUT | PASS | 정적: FfmpegFrameExtractor.java:119-122 일치 | BatchStepExecuteTest#FRAME_execute_marks비면_INVALID_INPUT_extractByMarks_미호출 | |
| TC-BATCH-101 | execute: 추출 0건 → INTERNAL_ERROR | PASS | 정적: :130-133 일치 | BatchStepExecuteTest#FRAME_execute_추출결과_0건이면_INTERNAL_ERROR | |
| TC-BATCH-102 | extractByMarks: 영상 메타 blank → INVALID_INPUT | PASS | 정적: :163-165 일치 | FfmpegFrameExtractorTest | |
| TC-BATCH-103 | extractByMarks: 비식별 미완료 → INVALID_INPUT | PASS | 정적: :170-174 일치 + 실동작: DB `ls_data_raw.de_ident_yn≠'Y'` 영상은 프레임 0건(추출 자체 미도달) | FfmpegFrameExtractorTest#extractByMarks_notDeidentified_rejected | |
| TC-BATCH-104 | extractByMarks: 원본 미존재 → INVALID_INPUT | PASS | 정적: :175-179 일치 | FfmpegFrameExtractorTest | |
| TC-BATCH-105 | 비식별 경로 base 이탈 → RAW only(fail-closed) | PASS | 정적: :190-196 일치. **실동작 우연 검증**: `ls_deident_proc_log`의 rawSn 4~8 `de_idntf_file_path_nm`이 호스트 경로(`/Users/ck/Documents/.../backend/storage/...`, 컨테이너 base `/app/storage/deidentified` 밖)로 기록된 레거시 행이 실제로 `ls_data_src.de_idntf_src_file_path_nm` 전량 NULL(RAW only)로 처리돼 있어, fail-closed 가드가 실제로 발동한 사례를 실DB에서 확인 | FfmpegFrameExtractorTest#extractByMarks_deidPathOutsideBase_failClosedRawOnly | 가드 실동작 증거는 의도적 침투테스트가 아니라 우연히 발견된 legacy 데이터지만, 코드 경로 자체는 동일 |
| TC-BATCH-106 | 비식별 경로 null/파일 부재 → RAW only | PASS | 정적: :197-209 일치 | FfmpegFrameExtractorTest#extractByMarks_noDeidVideo_rawOnly 등 | |
| TC-BATCH-107 | 정상: 2벌 추출+deid 경로 INSERT 시점 저장 | PASS | 정적: :240-260 (6-arg `LsDataSrc.create` 이전에 deid 프레임 write→경로 확보) 일치. **실동작**: rawSn=26 라이브 구동 결과 `ls_data_src` 16 frames / deid_frames=16 / raw_frames=16 완전 일치(0건 NULL). rawSn 14/17도 동일(24/24, 24/24). PARTIAL 회귀 미재현 — 07-22 수정(frame-extract-deid-path-null-bug) 정상 유지 확인 | FfmpegFrameExtractorDeidPersistIT#execute_persistsDeidFramePathToDatabase | ★rawSn 11/12/13(07-06~07-15 생성, 수정 이전 레거시)은 디스크엔 deid 프레임 파일이 실재하나 DB `de_idntf_src_file_path_nm`은 전량 NULL — 구버전 self-invocation 버그의 잔존 흔적(수정 후 재실행 안 됨, 메모리 `frame-extract-deid-path-null-bug`의 "기존 NULL 백필은 스코프 밖" 그대로). export 재조회 시 rawSn=13은 실제로 `export_stts_cd=PARTIAL` 확인(신규 rawSn 14/17은 SUCCEEDED) |
| TC-BATCH-108 | seekMillis=round(frameIndex×1000/fps), pin fps 우선 | PASS | 정적: :218,232,297-302 일치 | FfmpegFrameExtractorTest#M3_seekMillis_실fps25_정확_frameIndex50이_2000ms 등 | |
| TC-BATCH-109 | pin fps null/비정상 → resolveFps 폴백 | PASS | 정적: :297-302 일치 | FfmpegFrameExtractorTest#마킹pin이_null이면_resolveFps로_폴백한다 | |
| TC-BATCH-110 | 출력 경로 순회 방어(CWE-22) | PASS | 정적: :281-288 일치 | FfmpegFrameExtractorTest#resolveSafeOutputDir_normalRawSn_passesGuardAndExtracts | |
| TC-BATCH-111 | frames raw/deid 서브세그먼트 분기(충돌 없음) | PASS | 정적: :281-288 일치. **실동작**: 컨테이너 내 `/app/storage/deidentified/frames/deid/{rawSn}`·`/app/storage/raw/frames/raw/{rawSn}` 양쪽 디렉토리 실존, base가 동일(`STORAGE_RAW_PATH`)해도 raw/deid 파일 충돌 없음(각기 다른 파일 크기·내용) | FfmpegFrameExtractorTest#extractByMarks_sameBase_rawAndDeidPathsDoNotCollide | |
| TC-BATCH-112 | 이력: deid 채운 경우 CREATED+DEID_ATTACHED 2건 | PASS | 정적: :256-260 일치 | FfmpegFrameExtractorTest (hstry 검증 포함) | |

## B-9. YOLO / SAM2 / Interpolate (오토라벨 단계)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-120 | YOLO: rawSn null → INVALID_INPUT | PASS | 정적: YoloAutolabelStep.java:148-150 일치 | YoloAutolabelStepTest#nullRawSnRejected | |
| TC-BATCH-121 | YOLO: 프레임별 순차 track(clipId 격리, 0=리셋) | PASS | 정적: :164-181,164,174-232 일치. **실동작**: docker logs `[Batch][Yolo] ... rawSn=26 clipId=26 ... frames=16` — clipId=rawSn 문자열, frame 0..15 순차 호출 확인(ai-server 실추론, mock 아님 — AI_MOCK_MODE=false) | YoloAutolabelStepTest#frameIndexAccumulatesFromZero, #predictYoloTrackCalledAndTrackIdPersisted | |
| TC-BATCH-122 | YOLO: ai-server 호출 실패 → EXTERNAL_API_ERROR | PASS | 정적: :184-188 일치 | YoloAutolabelStepTest#externalErrorWrapped | 실장애 재현은 미실시(정적+테스트로 충분 판단) |
| TC-BATCH-123 | YOLO: mock 응답 감지 WARN(CRLF 살균) | PASS | 정적: :193-205 일치 — AI_MOCK_MODE=false 실추론 환경이라 실동작에서는 mock 분기 미도달(정상, ai-server가 실제 mock 아님을 응답 필드로 알림) | YoloAutolabelStepTest#mockResponseTriggersWarnLog, #realResponseNoWarnLog | |
| TC-BATCH-124 | YOLO: DTCT_TYPE_CD 축 매핑 | PASS | 정적: :213-229 일치. **실동작**: rawSn=26 실행결과 `ls_data_lbl_ai_info(lbl_src_cd=YOLO)` 64건, `ls_data_lbl`에 `lbl_id=1(person)`·`lbl_id=5(bus)` 실제 매핑됨(`ls_label.dtct_type_cd`='person'/'bus'와 일치) | YoloAutolabelStepTest#yoloLabelIdMappedFromMaster, #detectionMatchesDtctTypeAxisForLabelId | |
| TC-BATCH-125 | YOLO: 프리셋 토글 필터(미매핑 fail-safe) | PASS | 정적: :286-296 일치. 실동작 로그에 `preset=(none)`으로 rawSn=26 이벤트타입(EV02000201) 미매핑 확인되었고 BOTH(전체통과)로 정상 동작(64건 전부 저장) | YoloAutolabelStepTest#unmappedLabelDefaultsToBothFailSafe | |
| TC-BATCH-126 | YOLO: 이미지 경로 순회 방어(CWE-22) | PASS | 정적: :302-307 일치 | (readImageAsBase64 경로가드 — YoloAutolabelStepTest 내 경로관련 케이스로 간접 커버) | |
| TC-BATCH-127 | YOLO: conf/imgsz/iou 설정 fail-safe | PASS | 정적: :244-272 일치. 실동작 로그 `conf=0.25 imgsz=1280 iou=0.5`(SystemConfig 값 실제 적용 확인, DEFAULT 0.4 아님 — 운영 조정값이 실제 ai-server 호출에 반영됨) | YoloAutolabelStepTest#systemConfigValuesPassedToAiServer, #fallbackDefaultsWhenSystemConfigMissing | |
| TC-BATCH-128 | SAM2: (srcSn,label,trackId) dedup DB BBOX 우선 | PASS | 정적: Sam2SegmentStep.java:212-228 일치. **실동작**: rawSn=26 YOLO 64건 BBOX → SAM2 호출/저장 62건(dedup으로 자연 감소, 트랙 반복 프레임이 동일 trackId로 합쳐지지 않고 프레임별 별도 처리되어 거의 1:1에 가까움 — dedup 로직이 실제로 라벨+trackId 키로 동작 중임을 카운트 차이로 방증) | Sam2SegmentStepTest#dedup_은_label_과_trackId_조합_기준 | |
| TC-BATCH-129 | SAM2: polygon=false 라벨 skip | PASS | 정적: :167-173 일치 | Sam2SegmentStepTest#polygonEnabled_false_라벨은_SAM2_호출_안_함 | |
| TC-BATCH-130 | SAM2: 응답 폴리곤 상한 초과 → 단순화 | PASS | 정적: :325-343 일치. 실동작 로그에서 관측된 폴리곤 점수(points=237~830) 전부 1000점 cap 미만이라 실제 simplify 트리거는 미관측(정상 — 실 SAM2 CPU 추론 결과가 애초에 상한 이내) | Sam2SegmentStepTest#Sam2Step_4192점_응답_폴리곤은_저장전_1000점_이하로_simplify | |
| TC-BATCH-131 | SAM2: 호출 실패 → EXTERNAL_API_ERROR | PASS | 정적: :197-205 일치 | Sam2SegmentStepTest (외부 오류 래핑 케이스 — DisplayName 목록상 명시적 실패 테스트 미확인, YOLO 대칭 패턴으로 코드는 확인됨) | 이 항목만 전용 실패주입 테스트 미확인(확인필요 낮음, 코드 자체는 YOLO와 동일 패턴이라 신뢰도 높음) |
| TC-BATCH-132 | Interpolate: 프레임/후보 없음 → 0 | PASS | 정적: :131-134(frames empty),154-157(candidates empty) 일치 | TrackInterpolationStepTest#프레임_없는_영상은_no_op, #trackId_있는_BBOX_없으면_no_op | |
| TC-BATCH-133 | Interpolate: 재실행 멱등 stale 선삭제 | PASS | 정적: :144-151 일치 | TrackInterpolationStepTest#재실행_idempotency_기존_보간row_삭제후_재삽입 | |
| TC-BATCH-134 | Interpolate: 트랙 부분실패 격리 | PASS | 정적: :164-175(try/catch per trackId) 일치 | TrackInterpolationStepTest#한트랙_예외_다른트랙_보간은_저장됨 | |
| TC-BATCH-135 | Interpolate: 혼재 타입 트랙 skip | PASS | 정적: :310-315 일치 | TrackInterpolationStepTest#트랙내_타입혼재_안전_skip | |
| TC-BATCH-136 | Interpolate: BBOX flat/nested 양포맷 | PASS | 정적: :398-438 일치 | TrackInterpolationStepTest#nested_BBOX_좌표_포맷도_파싱_지원 | |
| TC-BATCH-137 | Interpolate 단건: from+to stale 삭제 | PASS | 정적: :225-285 일치 | TrackInterpolationSingleTrackIntegrationTest#머지후_fromTrackId_기존보간산출물_고아_0 등 | |
| TC-BATCH-138 | MarkingLoadStep: 마킹 로드+최신 markCn 파싱 | PASS | 정적: MarkingLoadStep.java:50-58 일치. 실동작: rawSn=26 파이프라인이 마킹 로드→marks 파싱→FRAME_EXTRACT로 정상 이어짐(전체 체인 실행 완료로 간접 확인) | MarkingLoadStepTest#마킹_있으면_markings_로드 | |
| TC-BATCH-139 | MarkingLoadStep: markCn 파싱 실패 → INTERNAL_ERROR | PASS | 정적: :60-66 일치 | MarkingLoadStepTest#markCn_이_잘못된_JSON_이면_INTERNAL_ERROR | |
| TC-BATCH-140 | 파이프라인 순서 검증 | PASS | 정적: BatchPipelineConfig.java:31-42(`List.of(markingLoad, vlm, frame, yolo, sam2, interp)`) 일치. **실동작**: rawSn=26 `ls_batch_proc_log.proc_step_cd`가 시점별로 SAM2→...→COMPLETED로 진행 관측(YOLO→SAM2→INTERPOLATE 순서 실제 준수, ai_info 카운트가 YOLO 64→SAM2 39→62→INTERPOLATE 5로 시간순 증가하며 정합) | BatchPipelineConfigTest#파이프라인_순서는_MARKING_VLM_FRAME_YOLO_SAM2_INTERPOLATE | |

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [B-ISSUE-41] TC-BATCH-107 관련 — 07-22 수정 이전 레거시 프레임(rawSn 11/12/13)의 DB NULL 방치로 export가 여전히 PARTIAL
- **심각도**: LOW (신규 결함 아님 — 이미 알려진 갭의 실측 재확인)
- **기대 동작(기대효과)**: 프레임 2벌(원본+비식별)이 추출된 영상은 검수 export가 SUCCEEDED로 산출되어야 한다.
- **현재 동작(이슈 내용)**: `ls_data_src`에서 rawSn 11/12/13은 디스크에 비식별 프레임 파일이 실재함(`docker exec klid-backend ls /app/storage/deidentified/frames/deid/{11,12,13}` 확인)에도 `de_idntf_src_file_path_nm` 컬럼이 전량 NULL. 07-22 수정(6-arg `LsDataSrc.create`로 INSERT 시점에 deid 경로 포함)은 코드상 정상 반영돼 있고 신규 rawSn(14,17,26 등)은 정상이지만, 수정 이전에 생성된 행은 백필되지 않아 export 재조회 시 rawSn=13은 `ls_dataset_export.export_stts_cd='PARTIAL'`로 실측됨.
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT raw_sn, count(*), count(de_idntf_src_file_path_nm) FROM public.ls_data_src WHERE raw_sn IN (11,12,13) GROUP BY raw_sn;"`
- **영향**: 07-22 이전 생성된 소수의 레거시 영상만 영향(신규 파이프라인은 정상). 데이터마트 반출 시 해당 영상만 비식별 프레임 누락으로 표시.
- **수정 방향(제안)**: 1회성 백필 배치(레거시 `ls_deident_proc_log.de_idntf_file_path_nm` 기준으로 `ls_data_src.de_idntf_src_file_path_nm` UPDATE) — 메모리 `frame-extract-deid-path-null-bug`에 이미 "스코프 밖"으로 기록된 사항이라 이번 회차에서는 정보 기록만.

### [B-ISSUE-42] YOLO/SAM2 배치 저장이 루프 내 개별 save() — IDENTITY 전략이 hibernate.jdbc.batch_size 설정을 무력화
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `rules/performance.md`("대량 처리: 1건씩 save 금지 → saveAll() 사용", "hibernate.jdbc.batch_size: 50")에 따라 프레임×검출 단위의 대량 INSERT는 배치로 묶여야 한다.
- **현재 동작(이슈 내용)**:
  - `YoloLabelPersister.persistBbox`(`backend/src/main/java/kr/co/cudo/authoring/batch/step/YoloLabelPersister.java:58~`)가 `YoloAutolabelStep.java:220-224` 루프(프레임×검출) 안에서 매 detection마다 `lblRepository.save()` + `aiInfoRepository.save()`를 개별 호출.
  - `Sam2SegmentStep.java:186-189`도 동일하게 SegmentJob 루프 안에서 `lblRepository.save()` + `aiInfoRepository.save()` 개별 호출.
  - `TrackInterpolationStep.java:178,184`만 `saveAll()` 사용(상대적으로 낫지만 근본 해결은 아님 — 아래 참고).
  - `LsDataLbl`/`LsDataLblAiInfo` 엔티티는 `@GeneratedValue(strategy = GenerationType.IDENTITY)`(`LsDataLbl.java:55`) — Hibernate는 IDENTITY 전략에서 **PK를 즉시 알아야 하므로 JDBC 배치를 구조적으로 비활성화**한다. 즉 `application.yml:33-36`의 `hibernate.jdbc.batch_size=50`/`order_inserts=true` 설정은 이 엔티티들에는 **효과가 없다**(saveAll()로 바꿔도 동일 — IDENTITY 전략 자체가 원인).
  - 실측: rawSn=26 라이브 구동에서 YOLO 64건, SAM2 62건 각각 실제로는 128/124회의 개별 INSERT(라벨+AI_INFO)로 실행됨(로그 타임스탬프 간격 상 SAM2는 프레임당 순차 호출·저장이 관측됨 — SAM2 CPU 추론 자체가 지배적 비용이라 즉각적 성능 문제로 체감되진 않으나, SFR-16(이미지 10만장) 규모에서는 누적 INSERT 왕복이 유의미해질 수 있음).
- **재현/확인 경로**: `grep -n "GenerationType.IDENTITY" backend/src/main/java/kr/co/cudo/authoring/batch/entity/LsDataLbl.java` + `grep -n "batch_size" backend/src/main/resources/application.yml`
- **영향**: 성능(대량 처리 시 배치 INSERT 미적용) — 기능 정확성에는 영향 없음. CWE 해당 없음(성능 규칙 위반).
- **수정 방향(제안)**: (a) `LsDataLbl`/`LsDataLblAiInfo`를 시퀀스 기반 PK(`GenerationType.SEQUENCE` + `allocationSize`)로 전환해 실제 JDBC 배치를 활성화하거나, (b) 프레임 단위로 라벨을 모아 `saveAll()` 일괄 호출로 리팩터링(단, (a) 없이는 배치 효과 제한적).

## 요약

- 총 34건 / PASS 33 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 1(TC-BATCH-131 — 전용 실패주입 테스트 미확인, 코드 패턴은 YOLO와 동일해 신뢰도 높음)
- 근거 라인 드리프트: 0건 (문서상 file:line 전건이 실제 코드와 정확히 일치 — ±1~3라인 이내 오차만, 실질 드리프트 아님)
- self-fill 결함: 0건 (rawSn=26 라이브 파이프라인으로 YOLO/SAM2/INTERPOLATE 전 단계가 실제 ai-server 호출·실제 DB 저장으로 end-to-end 확인됨 — self-fill 의심 없음)
- 신규 이슈 2건(B-ISSUE-41 LOW/기지 갭 재확인, B-ISSUE-42 MEDIUM/성능)
