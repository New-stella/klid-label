# 파이프라인 실구동 결과 (2-1차)

- 사용 rawSn: **4** (vms_clip_id=DEV-CLIP-9101, 시드 mng_clip_master 3건 중 1건 채택; rawSn 5·6도 동일 절차로 MARKING_READY까지 병행 확인)
- 사전 조치: 시드 `MNG_CLIP_MASTER.FILE_PATH`(`./storage/raw/seed/clip-910N.mp4`)가 가리키는 실파일이 스토리지에 존재하지 않아(최초 스캔 시 `KPST_SOURCE_MISSING`으로 3건 모두 실패), backend 컨테이너 내 `ffmpeg -f lavfi testsrc`로 5초짜리 실 mp4 3개를 해당 경로에 생성(테스트 데이터 준비, 코드/설정 미수정) 후 `ls_data_raw` 기존 실패 행 삭제→재스캔.

## 단계별 결과

| 단계 | 수행 방법 | 결과 상태값 | 근거 | O/X |
|---|---|---|---|:--:|
| 1. 적재 | `POST /v1/dev/batch/scan` (REVIEWER 토큰, `BatchDevTriggerController` — `TrainingVideoIngestService.scanAndIngest()` 실경로) | `ls_data_raw` 3건 INSERT(rawSn=4,5,6) `PENDING` | API 응답 `{"data":3}` + DB 조회 | O |
| 2. 비식별(선두, event-driven) | 적재 tx AFTER_COMMIT → `IngestDeidentifyBridge`→`AsyncDeidentifyRunner`→`KpstDeidentTxService`(mock-server KPST 실경유) | `de_ident_yn='Y'`, `data_stts_cd=MARKING_READY` (3건 모두) | backend 로그 `[KpstDeid] submitted/completed rawSn=4/5/6` + mock-server 로그 `[MOCK][KPST] project created/production completed prj_id=1..3` + DB 조회 | O |
| 3. 마킹(AUTO) | `POST /v1/videos/4/markings` `{"mode":"AUTO","intervalFrames":10}` (WORKER 사전 배정 없이도 REVIEWER 토큰 허용) | 마킹 30건 생성(`marks[]`), `batchTriggered:true` → 응답 즉시 `data_stts_cd=PROCESSING` | API 응답 marks 30건 + DB 조회 | O |
| 4. VLM 콜백 | `BatchOrchestrator` 동기 호출 → `VlmTimeseriesStep`→`VlmClient`(mock-server 실경유) submit → mock 비동기 콜백 `POST /api/v1/vlm/callback` | `LS_DATA_META` 7건 적재, `markingsTransitioned=1` | backend 로그 `[Batch][VlmTimeseries] describe submit` + mock-server 로그 `[MOCK][VLM] describe accepted`+`callback sent status=200` + backend 로그 `[Webhook][Vlm] result applied new=1` + DB count(7) | O |
| 5. 프레임추출(FFmpeg, 원본+비식별 2벌) | `FfmpegFrameExtractor`/`BrampFfmpegFrameWriter` (실제 ffmpeg 바이너리, mock 아님) | `LS_DATA_SRC` 30건(rawSn=4) | backend 로그 `extracted frame ... path=frame-NN.jpg` 다수 + DB count(30) | O |
| 6. 오토라벨링(YOLO/SAM2) | `YoloAutolabelStep`→ai-server 호출 | **ai-server가 mock 응답으로 폴백**(`mockReason=weights_missing`) — 저장된 bbox 0건 | backend 로그 `[Batch][YOLO] mock response detected ... source=mock mockReason=weights_missing` (rawSn=4, srcSn 1~30 전부) + `yoloCount=0 bboxSaved=0` + `[Batch][Sam2] saved polygons rawSn=4 count=0` | **X(부분)** — self-fill 아님(ai-server 자체가 명시적으로 mock 표시 후 응답, 은폐 없음)이나 stack-bringup에서 확인한 `AI_MOCK_MODE=false`(실추론 의도)와 달리 **YOLO 가중치 파일 부재로 실제 추론이 수행되지 못함** |
| 7. 배정 | `POST /v1/assignments` `{"workerId":2001,"rawDataIds":[4],"reviewerId":1001}` | 배정 성공, `ls_raw_data_status.data_stts_cd=ASSIGNED` | API 응답 + DB 조회 | O |
| 8. 라벨링(임시저장) | `PUT /v1/frames/1/labels` (WORKER 토큰) BBOX 1건 수동 저장 | `labelVersion=1` 저장 확인 | API 응답 `items[0]` 반영 | O |
| 9. 검수 제출→승인 | `POST /v1/reviews/4/submit`(WORKER)→`/start`(REVIEWER)→`/approve`(REVIEWER) | `PENDING→REVIEW_PENDING→REVIEWING→COMPLETED`, `ls_raw_data_status.data_stts_cd=APPROVED` | 3개 API 응답 각각 확인 + DB 조회 | O |
| 10. 버전 스냅샷 | 검수 승인 시점 `LS_LABEL_VERSION` 자동 스냅샷 | 1행 생성 확인: `lbl_version_sn=1, data_raw_sn=4, data_src_sn=1, ver_no=1, save_reason_cd=APPROVED, actvtn_yn=Y, version_hash=6c53c75f...` | DB 조회(재확인 완료) — 컬럼명은 `lbl_version_sn`/`save_reason_cd`(최초 추정과 상이) | O |
| 11. 관제 통지(TASK_COMPLETED) | `ControlNotifyClient` → mock-server | `202 Accepted`, `job_id=4 image_count=30` | mock-server 로그 `[MOCK] notify-completed accepted job_id=4 image_count=30` + `POST /api/data-set/v2/jobs/4/notify-completed` | O |
| 12. 데이터마트 View | `V_COMPLETED_VIDEO` 1행 조회 | `review_stts_cd=APPROVED`, `batch_stts_cd=COMPLETED`, `de_idntf_yn=Y`, `de_idntf_file_path_nm=.../deid/clip-9101-mask.mp4`, `frame_cnt=60`(원본+비식별 2벌 합산 추정) | DB 조회(뷰) 1행 실측 | O |

## 최종 도달 단계

**Export 단계(데이터마트 View, `V_COMPLETED_VIDEO`)까지 전 구간 도달.** rawSn=4는 적재→비식별→마킹→VLM→프레임추출→(제한적)오토라벨링→배정→라벨링→검수승인→관제통지→View노출까지 실제 API/로그/DB 근거로 확인됨.

## 막힌 지점 / 특이사항

1. **시드 데이터 자체 결함(테스트 데이터 이슈, 코드 결함 아님)**: `MNG_CLIP_MASTER` 시드 3건의 `FILE_PATH`가 실제로 존재하지 않는 파일을 가리켜 최초 스캔 시 3건 모두 `KPST_SOURCE_MISSING`으로 실패(`data_stts_cd=FAILED`, `de_ident_yn=F`). 검증 목적상 파일을 직접 생성해 재현했으나, **원래 시드로는 정상 파이프라인 재현이 불가능**했던 점은 이슈로 별도 보고 필요.
2. **오토라벨링 미실동작(YOLO 가중치 부재)**: stack-bringup에서 `ai-server`가 `AI_MOCK_MODE=false`(CPU 실추론 모드)로 확인됐으나, 실제 배치 실행 시 `YoloAutolabelStep`이 매 프레임(30/30) `mockReason=weights_missing`으로 **mock 응답 폴백**을 명시적으로 로그에 남기며 처리함. ai-server 자체가 응답에 `source=mock`을 명시하므로 backend 쪽 self-fill(은폐)은 아니지만, **실제 YOLO 추론은 이번 구동에서 검증되지 못함**(모델 가중치 파일 미탑재가 원인으로 추정 — `ai-server/weights` 마운트 확인 필요). B/C 클러스터 오토라벨링 관련 케이스 판정 시 "실추론 결과"가 아닌 "mock 폴백 결과"임을 감안할 것.
3. **버전 스냅샷 재확인 완료**: 컬럼명이 최초 추정(`version_sn`)과 달라(`lbl_version_sn`/`save_reason_cd`) 1차 조회는 실패했으나 재조회로 `SAVE_REASON_CD=APPROVED` 스냅샷 1행(`lbl_version_sn=1`, `version_hash=6c53c75f...`) 생성을 확인함.
4. **self-fill 의심 지점 없음** — 비식별(KPST)·VLM·관제통지 3개 외부연동 전부 mock-server 실경유 로그(요청/응답 페어) 확보. 유일한 "실제 미동작"은 오토라벨링이며 이는 mock 폴백임이 로그에 명시적으로 드러나 은폐형 self-fill과는 다름.
