# B클러스터 part6 — B-8(FfmpegFrameExtractor) · B-9(YOLO/SAM2/Interpolate) 검증 결과

- 대상: `docs/test-cases/B-batch-deidentify.md` 196~254행, TC-BATCH-100~149 (총 49건, TC-BATCH-119는 결번)
- 방법: 정적 대조(코드 Read/Grep) + 실동작(3차 §3-3 pipeline-drive.md rawSn=101 실구동 결과 재사용 — DB 조회·`docker logs klid-backend` 재확인) + 테스트 커버 대조(`_raw/test-baseline.md`: backend 5203 총/5198 성공/0 실패/0 에러/5 스킵(무관 클래스), `cleanTest test` 강제 실행 확인됨)
- 코드/설정/프로덕션 파일 수정 없음. 카탈로그(`docs/test-cases/B-batch-deidentify.md`) 라인 드리프트 2건은 담당 라인범위(196~254) 내에서 Edit로 직접 정정함(§카탈로그 정정 참조).

## 실동작 근거 요약 (rawSn=101, srcSn 468~477)

```sql
-- 프레임 경로 분기 실측
SELECT src_sn, src_file_path_nm, de_idntf_src_file_path_nm FROM ls_data_src WHERE raw_sn=101;
-- src_file_path_nm=/app/storage/raw/frames/raw/101/frame-N.jpg
-- de_idntf_src_file_path_nm=/app/storage/deidentified/frames/deid/101/frame-N.jpg  (경로 분기 정상)

-- co-locate 비식별 영상 실경로
SELECT de_idntf_file_path_nm FROM ls_deident_proc_log WHERE data_raw_sn=101;
-- /app/storage/raw/seed/101/deid/clip-9101-mask.mp4   (dirname(원본)/{rawSn}/deid/ — co-locate 확인)

-- 라벨 결과 (오토라벨 0건 확인)
SELECT l.lbl_sn, l.src_sn, l.trck_id, a.auto_lbl_yn FROM ls_data_lbl l LEFT JOIN ls_data_lbl_ai_info a ON a.data_lbl_sn=l.lbl_sn WHERE l.src_sn BETWEEN 468 AND 477;
-- 729|468|(null)|(null)  ← WORKER 수동 BBOX 1건뿐, AI_INFO 없음(자동라벨 0건)
```

```
docker logs klid-backend (rawSn=101 관련):
[Batch][FrameExtract] mark-based extracted rawSn=101 frames=10                         ← WARN 없음(co-locate 채택 성공)
[Batch][YOLO] mock response detected ... rawSn=101 srcSn=468~477 source=mock mockReason=weights_missing  (×10)
[Batch][Yolo] saved labels rawSn=101 ... yoloCount=0 bboxSaved=0 hintsEmitted=0 droppedDegenerate=0 droppedMalformed=0
[Batch][Sam2] saved polygons rawSn=101 count=0
[Batch][Interpolation] no interpolation candidates rawSn=101
```
→ ai-server weights 미탑재(**기존 알려진 갭**, `stack-bringup.md`/`pipeline-drive.md` 4-3 기재)로 YOLO 빈 detections. mockReason=weights_missing 은 빈 detections 사유이며 self-fill 아님(CLAUDE.md/코드 주석: "weights_missing 등은 빈 detections" — env_mock 만 합성 박스를 만드는데 이 환경은 `AI_MOCK_MODE=false`라 도달 안 함). 로그 순서(FRAME_EXTRACT→YOLO→SAM2→INTERPOLATE)는 `BatchPipelineConfig` 선언 순서와 실측 일치.

## 판정 표

| ID | 판정 | 근거 확인 | 비고 |
|---|:--:|---|---|
| TC-BATCH-100 | PASS | [정적] FfmpegFrameExtractor.java:133-136 marks empty→INVALID_INPUT, 근거 라인 일치 | |
| TC-BATCH-101 | PASS | [정적] :144-147 frames empty→INTERNAL_ERROR, 일치 | |
| TC-BATCH-102 | PASS | [정적] :177-179 rawFilePathNm blank→INVALID_INPUT, 일치 | |
| TC-BATCH-103 | PASS | [정적][실동작] :185-188 deIdntfYn≠Y→INVALID_INPUT. 실측(pipeline-drive.md)도 비식별 완료 후에만 FRAME_EXTRACT 진행 확인 | |
| TC-BATCH-104 | PASS | [정적] :190-193 원본 부재→INVALID_INPUT, 일치 | |
| TC-BATCH-105 | PASS | [정적] :206-213(WARN, rawSn만) + :316-333(isUnderAllowedDeidBase), 경로 원문 미노출 확인. 테스트 `FfmpegFrameExtractorTest#MEDsec_비식별경로가_base밖이면...` 존재, baseline PASS | |
| TC-BATCH-106 | PASS | [정적] :216-225 RAW only WARN, 일치 | |
| TC-BATCH-107 | PASS | [실동작] rawSn=101 DB `de_idntf_src_file_path_nm` 전 10행 non-null 확인 — INSERT 시점(6-arg `LsDataSrc.create`, :148-149 시그니처 확인) 포함 실증. 근거 :262-277 일치 | |
| TC-BATCH-108 | PASS | [정적] :234,248,393-398 seekMillis=round(frameIndex*1000/fps), pin 우선 — 라인 일치(1차 회차 지적된 :266 드리프트는 이미 :248 로 정정되어 있음, 재확인) | |
| TC-BATCH-109 | PASS | [정적] :393-398 effectiveFps 폴백, 일치 | |
| TC-BATCH-110 | PASS | [정적] :377-384 resolveSafeOutputDir, base 이탈 시 INVALID_INPUT, 일치 | |
| TC-BATCH-111 | PASS | [실동작] rawSn=101 `frames/raw/101/*`·`frames/deid/101/*` 분기 확인(위 SQL). 근거 :377-384 일치 | |
| TC-BATCH-112 | PASS | [정적] :272-276 CREATED+DEID_ATTACHED 2건, 일치 | |
| TC-BATCH-113 | PASS | [실동작] rawSn=101 비식별 영상이 실제로 `dirname(원본)/{rawSn}/deid/`(co-locate)에서 채택됨(WARN 없음, DE_IDNTF_SRC_FILE_PATH_NM 채워짐). 근거 FfmpegFrameExtractor.java:316-333 일치, VideoArtifactRootResolver.java 참조는 정정(아래 카탈로그 정정 참조) | |
| TC-BATCH-114 | PASS | [정적] 구 위치(`{deid_base}/videos/{rawSn}/`)도 `readableDeidVideoDirs`(:308-320)가 계속 후보에 포함. **근거 드리프트 발견 및 정정**(아래) | 카탈로그 정정 |
| TC-BATCH-115 | PASS | [정적] `underBaseWithRealPath`(FfmpegFrameExtractor.java:356-367)가 `verifyRealPathUnder` 실패를 RuntimeException catch→false 로 흡수 확인. 테스트 `FfmpegFrameExtractorTest#CWE59_co_locate_허용경로의_심링크가_원본영상을_가리키면_거부되고_RAW만_추출된다` 존재, baseline PASS. **근거 드리프트 발견 및 정정**(아래 — 구 인용 458-467 은 javadoc 뿐, 실제 메서드는 469-471/487-497) | 카탈로그 정정 |
| TC-BATCH-116 | PASS | [정적] VideoArtifactRootResolver.java:474-497 `resolveRealPathUnder`(487-497)가 `realOrNearest` 로 중간 세그먼트 심링크까지 접어 재검증. 테스트 `CWE59_경로중간_세그먼트가_심링크로_base밖을_가리키면...` 존재 | |
| TC-BATCH-117 | PASS | [정적] :316-320 리졸버 null→구 동작(`underBaseWithRealPath(deidPath, baseDeidPath)`) 폴백, 일치 | |
| TC-BATCH-118 | PASS | [정적] :321-326 후보 도출 RuntimeException→구 동작 폴백, VideoArtifactRootResolver.java:341-346 대응 확인, 일치 | |
| TC-BATCH-120 | PASS | [정적] YoloAutolabelStep.java:180-183 rawSn null→INVALID_INPUT, 일치 | |
| TC-BATCH-121 | PASS | [실동작] rawSn=101 로그 clipId=101(=String.valueOf(rawSn)), 10프레임 순차 호출 확인(YOLO WARN 로그 10줄, srcSn 468→477 순서). 근거 :195-197,209-212 일치 | |
| TC-BATCH-122 | PASS | [정적] :219-227 RuntimeException→EXTERNAL_API_ERROR, 일치 (본 구동에서는 호출 자체는 200 OK라 실패 경로 미발동 — 정적 대조로 보완) | |
| TC-BATCH-123 | PASS | [실동작] rawSn=101 실로그 "mock response detected ... mockReason=weights_missing" 10건 — LogSanitizer 정제 문자열로 정상 출력(CRLF/제어문자 없는 클린 값). 근거 :244-248,270-275 일치 | |
| TC-BATCH-124 | PASS | [정적] :318-321 `findLabelIdByDtctType` 매핑, 일치(본 구동은 검출 0건이라 실제 매핑 호출은 없었음 — 정적 대조) | |
| TC-BATCH-125 | PASS | [정적] :467-477 resolveToggle, togglesOpt empty→BOTH, 일치 | |
| TC-BATCH-126 | PASS | [정적] :494-499 readImageAsBase64 경로 이탈→INVALID_INPUT, 일치 | |
| TC-BATCH-127 | PASS | [실동작] rawSn=101 로그 "conf=0.25 imgsz=1280 iou=0.5"(YOLO saved labels 라인) — SystemConfig 기본값이 실제 ai-server 요청 파라미터로 반영됨 확인(로그에 노출). 근거 :191-193,425-453 일치 | |
| TC-BATCH-128 | PASS | [정적] Sam2SegmentStep.java:304-320 buildJobs dedup, DB BBOX putIfAbsent 우선, 라인 정확 일치 | |
| TC-BATCH-129 | PASS | [정적] :195-201 polygon=false skip WARN, 일치 | |
| TC-BATCH-130 | PASS | [정적] :234,428-446 capPolygon, 상한 초과 시 simplifyToMax, 라인 정확 일치. 테스트 `Sam2Step_4192점_응답_폴리곤은_저장전_1000점_이하로_simplify되어_저장된다` 존재 | |
| TC-BATCH-131 | PASS | [정적] :289-297 callSam2 실패→EXTERNAL_API_ERROR, 라인 정확 일치 | |
| TC-BATCH-132 | PASS | [실동작] rawSn=101 실로그 "no interpolation candidates rawSn=101" — 후보 0건(YOLO 검출 0건→trackId 있는 BBOX 없음)에서 정상 0 반환 확인. 근거 :131-134,144-147,174-178 일치 | |
| TC-BATCH-133 | PASS | [정적] :158-172 stale 재실행 시 AI_INFO→LBL 순 삭제 후 재생성, 라인 정확 일치 | |
| TC-BATCH-134 | PASS | [정적] :190 catch(Exception ex) 트랙 단위 격리, 일치 | |
| TC-BATCH-135 | PASS | [정적] 혼재 타입 skip 실제 판정문은 :354-358(`if(types.size()>1)` WARN+return). **근거 드리프트 발견 및 정정**(카탈로그가 353 한 줄만 지목 — 그 줄은 types 계산일 뿐 분기문이 아님) | 카탈로그 정정 |
| TC-BATCH-136 | PASS | [정적] :441 parseBbox 메서드 시작 — flat/nested 양포맷 파싱(441-481) 확인, 일치 | |
| TC-BATCH-137 | PASS | [정적] :247-330 interpolateSingleTrack/Touched — stale는 from+to 양쪽(:274-275 `List.of(fromTrackId, toTrackId)`), 재보간 호출부(:313)에 try/catch 없어 예외가 caller(TrackMergeService)로 전파됨을 확인(원자성). 일치 | |
| TC-BATCH-138 | PASS | [정적] MarkingLoadStep.java:50-58 execute — setMarkings/setMarks, 일치 | |
| TC-BATCH-139 | PASS | [정적] :60-65 parseMarks 실패→INTERNAL_ERROR, 일치 | |
| TC-BATCH-140 | PASS | [실동작] rawSn=101 로그 타임스탬프 순서(FRAME_EXTRACT→YOLO→SAM2→INTERPOLATE, VLM 은 앞서 별도 제출)가 BatchPipelineConfig.java:33-41 `List.of(markingLoad, vlm, frame, yolo, sam2, interp)` 선언과 일치 | |
| TC-BATCH-141 | PASS | [정적] DetectionBoxNormalizer.java:41-70(실제 clamp 로직 50-69), 0≤x≤W/0≤y≤H clamp 확인. 테스트 `이미지_상한_초과좌표는_이미지_경계로_clamp된다` 등 baseline PASS | |
| TC-BATCH-142 | PASS | [정적] :54-58 유한성 가드가 clamp(:59-64) 이전에 위치, NaN<0=false 함정 회피 확인. 테스트 `NaN_Infinity_좌표는_거부한다_역직렬화_500_회귀방지` 존재 | |
| TC-BATCH-143 | PASS | [정적] :60-70(정확 65-67 퇴화 판정) + YoloAutolabelStep.java:305-311,342-347 두 지점 모두 droppedDegenerate 로 스킵 확인. ★2축 정책(AI 검출=clamp+퇴화스킵) 정합 — 사용자 저장 400 거부와 혼동 없음 | |
| TC-BATCH-144 | PASS | [실동작+정적] YoloAutolabelStep.java:301-317 loop 내 catch(IllegalArgumentException)→droppedMalformed++·continue 확인(본 구동은 형식위반 0건이라 정적 대조 위주). 온라인 경로(`AutolabelOnlineService.normalizeDetections:562-575`) 는 동일 형식위반에 `throw new CustomException(INVALID_INPUT)`로 all-or-nothing 유지 확인 — ★3 좌표검증 2축 정책과 일치, "비일관"이 아님 | |
| TC-BATCH-145 | PASS | [정적] :358-366 BbHint 가 clamp 된 points 를 공유, 일치 | |
| TC-BATCH-146 | PASS | [정적] :301-317 정규화 실패/퇴화 시 continue 로 bbox 저장·hint 발행 둘 다 스킵, 일치(144 와 동일 라인 — 같은 분기가 두 효과를 겸함, 카탈로그상 정상) | |
| TC-BATCH-147 | PASS | [정적] DetectionBoxNormalizer.java:41-49,72-78 bounds null/비정상→상한 생략(Double.MAX_VALUE), YoloAutolabelStep.java:278-279 확인 | |
| TC-BATCH-148 | PASS | [실동작] rawSn=101: `resp.detections().isEmpty()` 분기로 인해(본 구동은 weights_missing→빈 detections) frameBoundsResolver 미호출 경로 실제로 탐. 근거 :278-279 일치 | |
| TC-BATCH-149 | PASS | [실동작] rawSn=101: 검출 0건이라 `labeledFrames` 비어 `bumpLabelVersionIn` 미호출(로그에 해당 UPDATE 실행 흔적 없음, saved labels 라인만 존재) — "라벨 생성된 프레임만 bump" 의 대우(생성 0건→bump 0회) 실증. 근거 :202,341,385-386 · LsDataSrcRepository.java:292-295(grep 재확인, 완전 일치) | |

**집계**: PASS 49 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0 (총 49건, TC-BATCH-119 결번은 분모 제외)

## 카탈로그 정정 (담당 라인범위 196~254행 내 Edit 완료, 3건)

1. **TC-BATCH-114** (214행) — `VideoArtifactRootResolver.java:308-327,337-348` → **`308-320,337-346`** 정정.
   - 실제 `readableDeidVideoDirs` 메서드는 308~320행(다음 메서드 `readableDeidVideoBases` 의 javadoc 이 322~336행에 별도 존재 — 구 범위가 이를 침범).
   - `readableDeidVideoBases` 메서드는 337~346행(구 범위 337-348 은 다음 메서드 javadoc 2줄까지 침범).
   - 이 드리프트는 2026-08-01 1차 회차에서 이미 한 번 지적됐으나(`docs/검증결과/2026-08-01/1차/ISSUES.md` 근거 드리프트 섹션) 카탈로그에 반영되지 않고 남아 있었다 — 이번에 정정 완료.
2. **TC-BATCH-115** (215행) — `VideoArtifactRootResolver.java:458-467` → **`469-471,487-497`** 정정.
   - 구 범위(458-467)는 `verifyRealPathUnder` 메서드의 **javadoc 전체**일 뿐 실제 코드가 아니다(468행 `*/` 로 javadoc 종료, 469행부터 실제 메서드 3줄).
   - 실질 판정 로직(`realOrNearest` 비교, FORBIDDEN 예외)은 `resolveRealPathUnder`(487-497)에 있어 이를 함께 인용하도록 정정.
   - FfmpegFrameExtractor.java 쪽 참조도 `356-368`→`356-367`로 미세 정정(367행이 `underBaseWithRealPath` 메서드의 실제 닫는 괄호, 368행은 공백줄).
3. **TC-BATCH-135** (239행) — `TrackInterpolationStep.java:353` → **`354-358`** 정정.
   - 353행은 `types` 리스트를 계산하는 문장일 뿐이고, "혼재 타입 skip" 을 실제로 판정·실행하는 코드(`if (types.size() > 1)` + WARN 로그 + `return List.of()`)는 354~358행이다.

## 확증편향 반증 메모

- **좌표 검증 2축 혼동 여부(지시 사항 반증 포인트)**: TC-BATCH-141~148(배치, clamp+퇴화스킵)과 TC-BATCH-144 온라인 경로(all-or-nothing 400)를 코드 레벨에서 직접 대조 — 배치는 `continue`(검출 단위 스킵), 온라인은 `throw CustomException(INVALID_INPUT)`(전체 400)로 실제로 분기가 다름을 확인. 두 경로 모두 같은 `DetectionBoxNormalizer.normalizeBbox` 를 호출하지만 **호출부가 결과를 다르게 소비**하는 것이 설계 의도(UNCERTAINTIES ★3)이며, "비일관"으로 잘못 보고할 소지를 코드로 직접 반증했다.
- **self-fill 여부**: YOLO mockReason=weights_missing 은 빈 detections 를 반환하며(ai-server 소스 근거는 이전 회차 확인, 본 회차는 backend 로그 `bboxSaved=0 hintsEmitted=0`로 재확인), `env_mock` 사유(합성 person 박스)와 달리 이 환경(`AI_MOCK_MODE=false`)에서는 도달하지 않는 경로임을 실측으로 재확인했다 — self-fill 아님.
- **기존 알려진 갭 재확인**: ai-server weights 미탑재(오토라벨 검출 결과 자체는 BLOCKED 대상)는 `stack-bringup.md`·`pipeline-drive.md` 에 이미 기록된 회차 공통 갭이며, 본 part6 에서는 신규 결함으로 집계하지 않았다(경로/계약/방어 로직 검증에는 영향 없음).

## 이전 회차(1차, 2026-08-01) 이슈 대조

- 본 담당 범위(B-8/B-9) 관련 1차 이슈 중 직접 대응되는 것은 **근거 드리프트 지적 1건**(TC-BATCH-114 관련, "TC-BATCH-108 :266→:248" 및 "TC-BATCH-114 :308-327→:308-320" 를 함께 언급한 메모)이며, TC-BATCH-108 쪽은 이미 정정되어 있었으나 **TC-BATCH-114 는 미정정 상태로 남아 있어 이번에 정정**했다(위 카탈로그 정정 1번).
- B-ISSUE-22/23/24/42/61/65/82/84/85 등 1차 주요 이슈는 B-1~B-7(배치 오케스트레이터·VLM·스트리밍) 및 B-11 이후 영역 소관이라 본 part6(B-8/B-9) 재검증 대상 밖이다 — 별도 이월 처리 불필요.
