# B-batch-deidentify 검증 (Part 5) — B-8 / B-9

- 대상: `docs/test-cases/B-batch-deidentify.md` 191~249행 (TC-BATCH-100~118, TC-BATCH-120~149) — 49건
- 검증일: 2026-07-31 / 2-1차
- 스택: klid-backend(:18081) · klid-postgres(`klid_system`/`public`) · klid-ai-server(:19300, `AI_MOCK_MODE=false`) · klid-mock-server(:9400)
- 선행 실동작 근거: `_raw/pipeline-drive.md` (rawSn=4, 적재→비식별→마킹(AUTO 30건)→VLM→FFmpeg(원본+비식별 2벌)→YOLO/SAM2(mock 폴백)→배정→라벨링→검수승인)
- 본 파트는 코드 정독(19개 대상 파일 전문) + DB 실측(`ls_data_src`, `ls_data_lbl`, `ls_data_lbl_ai_info`) + ai-server 컨테이너 내부 실측(`/app/weights` 디렉터리) + 기존 테스트 자산 대조(`FfmpegFrameExtractorTest` 24케이스 · `FfmpegFrameExtractorDeidPersistIT` 2케이스 · `VideoArtifactRootResolverTest` 14케이스 · `YoloAutolabelStepTest` 40+케이스 · `Sam2SegmentStepTest` 20케이스 · `TrackInterpolationStepTest` 20케이스 · `TrackInterpolationSingleTrackIntegrationTest` 10케이스 · `MarkingLoadStepTest` 4케이스 · `BatchPipelineConfigTest` 2케이스 · `DetectionBoxNormalizerTest` 12케이스)로 수행.

## ★★ YOLO mock 폴백 성격 확정 — 배포 설정 누락(코드 결함 아님)

- **실측**: `docker exec klid-ai-server env | grep MOCK` → `AI_MOCK_MODE=false`(실추론 의도 확정). `docker exec klid-ai-server ls -la /app/weights` → **디렉터리는 존재하나 파일 0개**(빈 디렉터리, `total 8`/엔트리 `.`·`..`뿐).
- `ai-server/app/models/yolox_loader.py:_resolve_yolox_weights()` → `settings.yolox_weights_path`(`./weights/yolox_s.onnx`, `.env.example:19` 문서화)가 파일로 존재하지 않으면 `"weights_missing"` 사유로 **명시적 mock 폴백**(크래시 없음, `source=mock mockReason=weights_missing` 응답 필드로 노출). `ai-server/Dockerfile`을 확인한 결과 `COPY app ./app`만 있고 **가중치 파일을 이미지에 COPY하거나 빌드 시 다운로드하는 단계가 없다** — 즉 가중치 바이너리 자체가 이미지/볼륨 어디에도 반입되지 않았다.
- **결론: 설정/배포 누락**이지 코드 결함이 아니다. 코드는 가중치 부재를 정확히 감지해 (a) 크래시하지 않고 (b) 응답에 `mock`/`mockReason`을 명시하며 (c) backend가 이를 LogSanitizer로 살균해 WARN 로깅하는, 설계된 fail-safe 경로를 그대로 탄다. 은폐형 self-fill이 아니다(pipeline-drive.md 기존 판단과 일치).
- **DB 실측**(rawSn=4,5,6): `ls_data_lbl_ai_info`에 YOLO/SAM2/INTERPOLATE 출처 행 **0건** — `ls_data_lbl`도 WORKER 수동 저장 BBOX 1건만 존재(`reg_user_no=2001`). 즉 오토라벨 산출물은 이번 구동에서 전혀 생성되지 않았다.
- **B-9 케이스 전수에 대한 영향 판단**: TC-BATCH-120~149 49건 중 "실제 YOLO 검출 정확도"(모델이 사람/차량을 올바르게 찾는지)를 요구하는 케이스는 **0건**이다 — 전부 API 계약/에러 매핑/좌표 정규화(clamp)/dedup/직렬화/파이프라인 순서/트랜잭션 경계를 다루며, `resp.detections()`가 빈 리스트여도(mock) 또는 임의 좌표를 담고 있어도(단위테스트 mock) 동일하게 검증 가능한 로직이다. 따라서 **BLOCKED 처리 대상 케이스는 없다** — 전건 코드 정독 + 기존 단위/통합 테스트로 PASS 판정 가능. 다만 "실제 YOLOX 추론이 실물 이미지에서 정상 동작하는지" 자체는 이번 회차에서도 여전히 미검증이며, 이는 UNCERTAINTIES.md #14/#15(실모델 게이팅)와 동일 이슈로 별도 등록한다(B-ISSUE-86, 아래).

---

## B-8. FfmpegFrameExtractor (원본+비식별 2벌, co-locate, 경계)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-100 | execute: marks 비면 INVALID_INPUT | PASS | 정적 `FfmpegFrameExtractor.java:133-136` ✓ 코드 일치 | `FfmpegFrameExtractorTest:463-464` V2_마킹_빈_배열_시_INVALID_INPUT | |
| TC-BATCH-101 | execute: 추출 0건 → INTERNAL_ERROR | PASS | 정적 `:144-147` ✓ | (marks 비어있지 않으면 항상 ≥1건 추출되므로 정상 경로에서 0건 도달 불가 — 방어 코드로서 타당) | |
| TC-BATCH-102 | extractByMarks: 영상 메타 blank → INVALID_INPUT | PASS | 정적 `:177-179` ✓ | 코드 레벨 가드, null/blank 케이스는 다른 테스트의 전제조건에서 반증적으로 확인 | |
| TC-BATCH-103 | extractByMarks: 비식별 미완료 → INVALID_INPUT(비식별 선행) | PASS | 정적 `:185-188` ✓ | `FfmpegFrameExtractorTest:431-432` 비식별_미완료_영상은_프레임추출에서_차단된다 | |
| TC-BATCH-104 | extractByMarks: 원본 미존재 → INVALID_INPUT | PASS | 정적 `:190-193` ✓ | (frameWriter.sourceExists 가드, mock FrameWriter로 반증 가능 — 코드 확인) | |
| TC-BATCH-105 | ★비식별 경로가 허용 base 전부의 밖 → RAW only(fail-closed) | PASS | 정적 `:206-213,316-333` ✓ — `readableDeidVideoBases` 판정으로 위임, 거부 시 원본 폴백 없음·경로 원문 미노출 확인 | `FfmpegFrameExtractorTest:207-208` MEDsec_비식별경로가_base밖이면_fail_closed로_RAW만추출_비식별경로_미저장 | |
| TC-BATCH-106 | extractByMarks: 비식별 경로 null/파일 부재 → RAW only | PASS | 정적 `:216-225` ✓ | `FfmpegFrameExtractorTest:418-419` V2_마킹_비식별_영상_없으면_RAW_만_graceful | |
| TC-BATCH-107 | 정상: 2벌 추출+deid 경로 INSERT 시점 저장 | **PASS (실동작)** | 정적 `:256-277` ✓ 6-arg `LsDataSrc.create`에 deidPath 포함(create 이전에 write) / **실동작**: `ls_data_src` DB 조회 — rawSn=4, 총 30행, `de_idntf_src_file_path_nm` **NOT NULL 30/30** (deid_count=30) | `FfmpegFrameExtractorTest:181-182` V2_마킹_기반_추출_비식별_영상_포함_2벌 / `FfmpegFrameExtractorDeidPersistIT:186-187` execute_프로덕션경로_비식별프레임경로가_DB에_실제_영속된다 | PARTIAL 회귀(구 결함) 재발 없음 확인 |
| TC-BATCH-108 | seekMillis=round(frameIndex×1000/fps), pin fps 우선 | PASS | 정적 `:234,266,393-398` ✓ | `FfmpegFrameExtractorTest:159-160` V2_마킹_위치_기반_프레임_추출_정확한_frameIndex | |
| TC-BATCH-109 | pin fps null/비정상 → resolveFps 폴백 | PASS | 정적 `effectiveFps():393-398` ✓ — null/NaN/Infinity/≤0 모두 폴백 분기 확인 | 전용 테스트명 미확인(코드 로직 단순·명확) | |
| TC-BATCH-110 | 출력 경로 순회 방어(CWE-22) | PASS | 정적 `resolveSafeOutputDir():377-384` ✓ `!resolved.startsWith(base)` 가드 | `FfmpegFrameExtractorTest:592-593` 경로순회_가드_raw와_deid_세그먼트_모두_base_하위로_정규화되어_통과한다(정상계) + 코드상 거부 분기 확인 | |
| TC-BATCH-111 | frames raw/deid 서브세그먼트 분기(충돌 없음) | PASS | 정적 `:377-384` ✓ `{base}/frames/{raw|deid}/{rawSn}` | `FfmpegFrameExtractorTest:508-509` 동일_base_주입돼도_원본과_비식별_프레임_경로가_달라_디스크_덮어쓰기_없음 / `:563-564` 동일_base_라도_원본_프레임은_frames_raw_하위에_생성된다 | |
| TC-BATCH-112 | 이력: deid 채운 경우 CREATED+DEID_ATTACHED 2건 | PASS | 정적 `:272-276` ✓ `recordCreated`+`recordDeidAttached` | (FfmpegFrameExtractorDeidPersistIT 계열에서 간접 확인) | |
| TC-BATCH-113 | ★co-locate 산출 비식별 영상 채택 | **PASS (실동작)** | 정적 `:316-333` + `VideoArtifactRootResolver.java:337-348 readableDeidVideoBases` ✓ / **실동작**: rawSn=4 co-locate 산출 비식별본이 실제 채택되어 `de_idntf_src_file_path_nm` 전 프레임 NOT NULL(위 TC-107 DB 실측과 동일 증거) | `FfmpegFrameExtractorTest:234-235` 결함2_원본옆_co_locate_비식별영상도_채택되어_비식별프레임경로가_저장된다 | 구 결함(자기 산출물 항상 RAW only) 회귀 없음 확인 |
| TC-BATCH-114 | 구 위치도 계속 허용 — 2-way allowlist | PASS | 정적 `VideoArtifactRootResolver.java:308-327,337-348` ✓ `readableDeidVideoDirs`가 구 위치+co-locate 위치 둘 다 후보로 반환 | `VideoArtifactRootResolverTest` allowlist 계열 다수 + `FfmpegFrameExtractorTest:443-444` 프레임추출이_저장된_비식별경로를_읽어_2벌_추출한다 | |
| TC-BATCH-115 | 심링크 방어: 대상 파일이 원본을 가리키면 거부 | PASS | 정적 `:356-368` + `VideoArtifactRootResolver.verifyRealPathUnder:458-467` ✓ — 예외가 아니라 false 반환하여 RAW only로 흡수 확인 | `FfmpegFrameExtractorTest:338-339` CWE59_co_locate_허용경로의_심링크가_원본영상을_가리키면_거부되고_RAW만_추출된다 | |
| TC-BATCH-116 | 심링크 방어: 중간 세그먼트 심링크도 거부 | PASS | 정적 `VideoArtifactRootResolver.realOrNearest:474-497` ✓ | `FfmpegFrameExtractorTest:363-364` CWE59_경로중간_세그먼트가_심링크로_base밖을_가리키면_거부되고_RAW만_추출된다 | |
| TC-BATCH-117 | 리졸버 미주입(단위 수동 생성) → 구 동작 | PASS | 정적 `:316-320` ✓ `artifactRootResolver==null` 분기 — `deidentified-path` 단독 판정 폴백 | 코드 확인(FfmpegFrameExtractorTest 단위 테스트들이 실제로 이 경로로 리졸버 없이 생성하는 케이스로 간접 검증) | |
| TC-BATCH-118 | 후보 도출 예외 시 구 동작 폴백 | PASS | 정적 `:321-326` + `VideoArtifactRootResolver:341-346` ✓ try/catch RuntimeException → 구 동작 폴백 | 코드 확인(fail-secure 원칙과 일관) | |

---

## B-9. YOLO / SAM2 / Interpolate (오토라벨 단계 · 좌표 정규화)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-120 | YOLO: rawSn null → INVALID_INPUT | PASS | 정적 `YoloAutolabelStep.java:169-171` ✓ | `YoloAutolabelStepTest:414-415` rawSn_null이면_INVALID_INPUT | |
| TC-BATCH-121 | YOLO: 프레임별 순차 track(clipId 격리, 0=리셋) | **PASS (실동작)** | 정적 `:185,201-211` ✓ `clipId=String.valueOf(rawSn)`, frameIndex 0..N 순차 루프 / **실동작**: pipeline-drive.md — rawSn=4, srcSn 1~30 전체 프레임에 대해 순차 호출(로그 `mockReason=weights_missing` 전 30건) 확인. 단 응답이 mock이라 clipId 트래커 상태 격리 자체의 실효과(track_id 부여)는 미관측 | `YoloAutolabelStepTest` 다수(Phase 4 관련) | |
| TC-BATCH-122 | YOLO: ai-server 호출 실패 → EXTERNAL_API_ERROR | PASS | 정적 `:212-216` ✓ | `YoloAutolabelStepTest:423-424` ai_server_예외시_EXTERNAL_API_ERROR | |
| TC-BATCH-123 | YOLO: mock 응답 감지 WARN(CRLF 살균) | **PASS (실동작)** | 정적 `:221-233` ✓ `LogSanitizer.sanitize(resp.source())`, `LogSanitizer.sanitize(resp.mockReason())` / **실동작**: pipeline-drive.md 로그 `[Batch][YOLO] mock response detected ... source=mock mockReason=weights_missing`(rawSn=4, srcSn 1~30 전부) — 코드와 실측 로그 문구 정확히 일치 | `YoloAutolabelStepTest:492-493` ai_server_mock_응답_감지시_WARN_로그_출력_및_파이프라인_계속_진행 | |
| TC-BATCH-124 | YOLO: DTCT_TYPE_CD 축 매핑 | PASS | 정적 `:277-279` `findLabelIdByDtctType` ✓ | `YoloAutolabelStepTest:1004-1005` 검출라벨이_COCO_검출축에서_토글매칭되어_labelId가_부여된다 | 실동작 미검증(검출 0건) — 정적+테스트로 충분 |
| TC-BATCH-125 | YOLO: 프리셋 토글 필터(미매핑 fail-safe) | PASS | 정적 `resolveToggle():400-410` ✓ togglesOpt empty → BOTH | `YoloAutolabelStepTest:544-545` eventTypeCd_null이면_fail_safe로_전체_통과 | |
| TC-BATCH-126 | YOLO: 이미지 경로 순회 방어(CWE-22) | PASS | 정적 `readImageAsBase64():427-432` ✓ `!imagePath.startsWith(baseRawPath)` 가드, 메시지에 경로 미노출 | 전용 단위테스트 미확인(Sam2SegmentStep의 동일 패턴은 안전하게 재사용된 코드) | 테스트 커버리지 갭 — 기능 결함 아님 |
| TC-BATCH-127 | YOLO: conf/imgsz/iou 설정 fail-safe | PASS | 정적 `readDoublePercent/readInt:358-386` ✓ 예외 시 기본값 폴백 | `YoloAutolabelStepTest:657-658,681-682,702-703` (SystemConfig 반영/미설정 기본값/조회실패 기본값 3종) | |
| TC-BATCH-128 | SAM2: (srcSn,label,trackId) dedup DB BBOX 우선 | PASS | 정적 `Sam2SegmentStep.buildJobs():241-257` ✓ `putIfAbsent` — DB BBOX 선등록 후 hint는 무시 | `Sam2SegmentStepTest:329-330,408-409,435-436` (DB+hint 동시존재/trackId dedup/trackId null fallback 3종) | |
| TC-BATCH-129 | SAM2: polygon=false 라벨 skip | PASS | 정적 `:182-188` ✓ WARN + continue | `Sam2SegmentStepTest:272-273` Sam2Step_polygonEnabled_false_라벨은_SAM2_호출_안_함_그리고_경고_로그 | |
| TC-BATCH-130 | SAM2: 응답 폴리곤 상한 초과 → 단순화 | PASS | 정적 `capPolygon():365-383` ✓ `PolygonSimplifier.simplifyToMax` | `Sam2SegmentStepTest:242-243` 4192점_응답_폴리곤은_저장전_1000점_이하로_simplify되어_저장된다 | |
| TC-BATCH-131 | SAM2: 호출 실패 → EXTERNAL_API_ERROR | PASS | 정적 `callSam2():226-234` ✓ | 전용 단위테스트 미확인 | 테스트 커버리지 갭 — 코드는 YOLO(TC-122)와 동일 패턴이라 기능 결함 아님 |
| TC-BATCH-132 | Interpolate: 프레임/후보 없음 → 0 | **PASS (실동작)** | 정적 `TrackInterpolationStep.interpolate():143-147,174-178` ✓ / **실동작**: rawSn=4 — YOLO 검출 0건 → `findAutoBboxWithTrackId` 후보 0건 → 보간 0건(DB `ls_data_lbl_ai_info` INTERPOLATE 출처 0건과 정합) | `TrackInterpolationStepTest:112-113,124-125` (프레임없음/trackId있는 BBOX없음 no-op 2종) | |
| TC-BATCH-133 | Interpolate: 재실행 멱등 stale 선삭제 | PASS | 정적 `:156-172` ✓ 자식(AI_INFO)→부모(LS_DATA_LBL) 순 삭제 + bump 선행 | `TrackInterpolationStepTest:475-476` 재실행_idempotency_기존_보간row_삭제후_재삽입 | |
| TC-BATCH-134 | Interpolate: 트랙 부분실패 격리 | PASS | 정적 `:185-196` ✓ try/catch per trackId, WARN skip | `TrackInterpolationStepTest:449-450` 한트랙_예외_다른트랙_보간은_저장됨_(부분실패_격리) | |
| TC-BATCH-135 | Interpolate: 혼재 타입 트랙 skip | PASS | 정적 `interpolateTrack():353-358` ✓ `types.size()>1` → WARN + `List.of()` | `TrackInterpolationStepTest:432-433` 트랙내_타입혼재_(BBOX+POLYGON)_안전_skip | |
| TC-BATCH-136 | Interpolate: BBOX flat/nested 양포맷 | PASS | 정적 `parseBbox():441-481` ✓ flat/[[x,y],[x,y]] 양쪽 파싱 | `TrackInterpolationStepTest:493-494` nested_BBOX_좌표_포맷도_파싱_지원_(수동_라벨_호환) | |
| TC-BATCH-137 | Interpolate 단건: from+to stale 삭제 | PASS | 정적 `interpolateSingleTrackTouched():259-328` ✓ from+to 양쪽 stale 삭제, 예외 전파(캐치 없음 — 머지 롤백 유도) 확인 | `TrackInterpolationSingleTrackIntegrationTest:228-229` 보간산출물_stale_삭제가_from_to만_적용_타트랙_불변 | |
| TC-BATCH-138 | MarkingLoadStep: 마킹 로드+최신 markCn 파싱 | **PASS (실동작)** | 정적 `MarkingLoadStep.java:50-58` ✓ / **실동작**: pipeline-drive.md — rawSn=4 `POST /v1/videos/4/markings` AUTO → marks 30건 생성·응답 반영 확인 | `MarkingLoadStepTest:47-48` 마킹_있으면_markings_로드_+_최신_마킹의_markCn_을_MarkItem_으로_파싱 | |
| TC-BATCH-139 | MarkingLoadStep: markCn 파싱 실패 → INTERNAL_ERROR | PASS | 정적 `:60-65` ✓ | `MarkingLoadStepTest:77-78` markCn_이_잘못된_JSON_이면_INTERNAL_ERROR | |
| TC-BATCH-140 | 파이프라인 순서 검증 | **PASS (실동작)** | 정적 `BatchPipelineConfig.java:33-41` ✓ `List.of(markingLoad, vlm, frame, yolo, sam2, interp)` / **실동작**: pipeline-drive.md 단계별 결과표 순서(마킹→VLM→FFmpeg→YOLO/SAM2)가 코드 순서와 일치 | `BatchPipelineConfigTest:28-29` 파이프라인_순서는_MARKING_VLM_FRAME_YOLO_SAM2_INTERPOLATE | |
| TC-BATCH-141 | ★좌표 정규화 단일 규칙 — clamp | PASS | 정적 `DetectionBoxNormalizer.java:41-70` ✓ 0≤x≤imgWidth, 0≤y≤imgHeight | `DetectionBoxNormalizerTest:27-28,41-42,53-54,65-66` (음수/y음수/상한초과/정상 4종) | |
| TC-BATCH-142 | 유한성 가드가 clamp 이전 | PASS | 정적 `:54-58` ✓ `Double.isFinite` 가드가 clamp보다 먼저 실행 | `DetectionBoxNormalizerTest:114-115` NaN_Infinity_좌표는_거부한다_역직렬화_500_회귀방지 | |
| TC-BATCH-143 | 퇴화 박스는 예외가 아니라 스킵 | PASS | 정적 `:60-70` + `YoloAutolabelStep.java:263-268` ✓ `Optional.empty()` → `droppedDegenerate` | `DetectionBoxNormalizerTest:88-89` clamp후_퇴화한_박스는_예외가_아니라_스킵신호를_반환한다 / `YoloAutolabelStepTest:258-259` 배치에서_이미지_전체밖_퇴화박스는_저장을_건너뛰고_나머지는_저장된다 | |
| TC-BATCH-144 | 형식 위반도 검출 단위 드롭 | PASS | 정적 `YoloAutolabelStep.java:252-275` ✓ catch(IllegalArgumentException) 후 continue, 영상 전체 실패 없음 | `YoloAutolabelStepTest:280-281,308-309` (형식위반/NaN 각각 검출단위 드롭 확인) | |
| TC-BATCH-145 | SAM box 프롬프트가 clamp 좌표를 공유 | PASS | 정적 `:316-325` ✓ `BbHint`에 정규화된 points 전달 | `YoloAutolabelStepTest:355-356` 폴리곤전용_프리셋의_hint좌표는_이미지_경계로_clamp된_값이다 | |
| TC-BATCH-146 | 퇴화 시 bbox·polygon 동시 스킵 | PASS | 정적 `:259-275` ✓ `continue`로 둘 다 스킵 | `YoloAutolabelStepTest:331-332,378-379` (폴리곤전용/BOTH 프리셋 각각 확인) | |
| TC-BATCH-147 | 해상도 측정 불가 시 상한 생략(fail-open) | PASS | 정적 `DetectionBoxNormalizer.java:41-49,72-78` + `YoloAutolabelStep.java:236-237` ✓ | `DetectionBoxNormalizerTest:76-77` 이미지_실측_해상도를_모르면_상한없이_음수만_0으로_clamp된다 | |
| TC-BATCH-148 | 검출 0건 프레임은 해상도 해석 자체를 안 함 | PASS | 정적 `YoloAutolabelStep.java:236-237` ✓ `resp.detections().isEmpty() ? null : frameBoundsResolver.resolve(...)` | 전용 단위테스트 미확인(코드 3항 연산자 직관적) | |
| TC-BATCH-149 | 라벨셋 버전 bump 범위 = 라벨이 실제 생성된 프레임만 | **PASS (실동작)** | 정적 `:189-190,332-345` + `LsDataSrcRepository.java:292-295 bumpLabelVersionIn` ✓ `labeledFrames`(실제 저장된 프레임만) 집합으로 bump / **실동작**: rawSn=4 — YOLO 검출 0건 → `labeledFrames` 공집합 → `if(!labeledFrames.isEmpty())` 가드로 bump 미호출(불필요한 프레임 락/버전 증가 없음, 코드 분기와 정합) | (SAM2/Interpolate 동일 패턴 각각 테스트 보유) | |

---

## 이슈 상세

### [B-ISSUE-86] TC-BATCH-121~149 전반(참고) — YOLOX ONNX 가중치 파일이 배포 산출물(이미지/볼륨)에 반입되지 않아, `AI_MOCK_MODE=false`(실추론 의도)에도 불구하고 모든 검출이 mock 폴백으로 처리됨
- **심각도**: MEDIUM (검증 커버리지 갭 · 배포 설정 누락 — **프로덕션 코드 결함 아님**)
- **기대 동작(기대효과)**: `AI_MOCK_MODE=false`인 CPU 실추론 모드에서는 `yolox_weights_path`(기본 `./weights/yolox_s.onnx`)에 실제 ONNX 가중치가 존재해 `get_yolox_model()`이 실 세션을 로드하고, YOLO/SAM2/Interpolate 배치 단계가 실제 검출 결과로 라벨을 생성해야 한다.
- **현재 동작(이슈 내용)**:
  - `docker exec klid-ai-server ls -la /app/weights` → 디렉터리만 존재, 가중치 파일 0개.
  - `ai-server/Dockerfile`은 `COPY app ./app`만 수행 — 가중치 바이너리를 이미지에 COPY하거나 빌드/기동 시 다운로드하는 단계가 없음(grep 결과 COPY/download 관련 라인 전무).
  - `ai-server/app/models/yolox_loader.py:_resolve_yolox_weights()`가 파일 부재를 감지해 `mock_reason="weights_missing"`으로 **의도된 fail-safe** 동작(크래시 없음, 응답에 `mock=true`/`mockReason` 명시) — **코드는 정상**.
  - 실측(pipeline-drive.md, rawSn=4): 30/30 프레임 전부 `mockReason=weights_missing`, `yoloCount=0 bboxSaved=0`, SAM2/Interpolate도 후속 0건. DB `ls_data_lbl_ai_info`에 YOLO/SAM2/INTERPOLATE 출처 행 0건(재확인).
  - 결과적으로 이번 회차뿐 아니라 이 스택이 재기동되는 한 **실제 YOLOX 추론 정확도는 어떤 시나리오로도 검증되지 못한다**(UNCERTAINTIES.md #14 imgsz 무효 실효검증·#15 실모델 테스트 게이팅과 동일 갭의 연장선).
- **재현/확인 경로**:
  ```bash
  docker exec klid-ai-server env | grep AI_MOCK_MODE          # false
  docker exec klid-ai-server ls -la /app/weights               # 비어있음
  grep -n "COPY\|weight\|onnx\|download" ai-server/Dockerfile   # 가중치 반입 단계 없음
  cat ai-server/.env.example | grep YOLOX_WEIGHTS_PATH         # ./weights/yolox_s.onnx (문서상 기대 경로)
  ```
- **영향**: 기능 결함 아님(설계된 fail-safe 정상 동작, self-fill 없음). 다만 B-9 케이스가 검증하는 "API 계약/좌표 정규화/dedup/파이프라인 순서" 로직은 mock 응답 + 기존 단위테스트로 충분히 커버되나, "YOLOX가 실물 CCTV 프레임에서 사람/차량을 정확히 검출하는지" 자체는 이 배포 형상으로는 영구히 미검증 상태로 남는다. SFR-16(이미지 10만장) 목표 진행 시 실가중치 없이는 오토라벨 산출물이 전혀 생성되지 않아 라벨링 작업자 부담이 그대로 수작업으로 전가된다.
- **수정 방향(제안)**: ① `ai-server/Dockerfile`에 가중치 다운로드/COPY 단계 추가(라이선스 허용 시 빌드 시점 fetch, 또는 배포 산출물에 가중치 포함 volume mount 문서화) ② 온프렘 설치 패키지(`deploy/onprem`) 체크리스트에 "YOLOX ONNX 가중치 배치 필수" 항목 명시(`env.template`류 문서에 `YOLOX_WEIGHTS_PATH` 설명은 있으나 실물 파일 확보 절차가 없음) ③ `/actuator/health` 또는 별도 헬스 인디케이터에 "YOLOX 모델 로드 상태(mock/실모델)"를 노출해 운영자가 mock 폴백 상태를 조용히 지나치지 않게 함(현재는 응답 필드/로그로만 확인 가능).

---

## 요약

- 총 **49건** / PASS **49** / FAIL **0** / PARTIAL **0** / BLOCKED **0** / N/A **0** / 확인필요 **0**
- **YOLO weights_missing 이슈 성격**: **배포 설정 누락**(ai-server 이미지/볼륨에 YOLOX ONNX 가중치 파일이 반입되지 않음) — **코드 결함 아님**. `yolox_loader.py`는 가중치 부재를 정확히 감지해 크래시 없이 mock 응답(`mock=true`, `mockReason=weights_missing`)으로 폴백하는 설계된 fail-safe 경로를 그대로 수행했으며, backend는 이를 은폐 없이 WARN 로그로 노출했다(self-fill 아님). B-ISSUE-86으로 등록.
- **B-9 케이스 판정에 미친 영향**: 49건 전부가 "API 계약/에러 매핑/좌표 정규화(clamp)/dedup/직렬화/파이프라인 순서" 등 **로직 검증**이며 "실제 검출 정확도"를 요구하는 케이스가 없어 **BLOCKED 대상 0건**. mock 응답(빈 검출 목록) + 기존 단위·통합 테스트(총 12개 대상 파일, 130+ 케이스)로 전건 PASS 근거 확보. 단, TC-BATCH-121/123/132/138/140/149는 실제 rawSn=4 파이프라인 구동 로그·DB로 **[실동작]** 교차 확인.
- **B-8 판정**: 19건 전부 PASS. 특히 TC-BATCH-107/113(co-locate 비식별 프레임 채택)은 DB 실측(`ls_data_src` rawSn=4, `de_idntf_src_file_path_nm` 30/30 NOT NULL)으로 구 결함(PARTIAL export 회귀) 재발 없음을 직접 확인.
- **테스트 커버리지 갭(기능 결함 아님, 참고용)**: TC-BATCH-109(pin fps 폴백)·TC-BATCH-126(YOLO 이미지경로 CWE-22)·TC-BATCH-131(SAM2 EXTERNAL_API_ERROR)·TC-BATCH-148(검출 0건 시 해상도 미해석) — 전용 단위테스트명이 확인되지 않았으나 코드가 명확하고 동일 패턴이 인접 코드(FfmpegFrameExtractor/Sam2SegmentStep/YoloAutolabelStep 자신)에서 이미 테스트된 방어 로직을 재사용하므로 PASS 유지.
- **근거 라인 드리프트**: 0건 — 문서 근거(file:line) 전부 실제 코드와 일치 확인.
- **self-fill 의심 지점**: 0건 — mock 응답도 `source=mock`/`mockReason` 명시적 노출, backend가 값을 자체 채우지 않음.
