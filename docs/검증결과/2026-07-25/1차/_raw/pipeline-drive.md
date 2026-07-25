# 배치 파이프라인 정상 시나리오 실구동 — 2026-07-25 1차

## 환경
- 스택: `klid-postgres`(healthy) / `klid-backend`(healthy, `local` 프로파일) / `klid-ai-server`(healthy, CPU 실추론 YOLOX+SAM2) / `klid-mock-server:9400`(KPST 비식별 목업) / `klid-frontend`(healthy)
- 주의: `/api/actuator/health` 최상위는 `DOWN`이지만 `liveness`/`readiness` 그룹은 둘 다 `UP` — docker healthcheck 는 이 두 그룹 기준으로 `healthy` 판정. 최상위 DOWN 원인(커스텀 health indicator 추정)은 본 임무 범위 밖이라 근본원인 미조사, 사실만 기록.
- 이 docker 스택은 다른 에이전트와 **공유 환경**이다. `_raw/` 폴더에 이미 `A-part1~3.md`, `stack-bringup.md`, `vlm-wiring.md` 존재 확인 — 로그에 섞여 보이는 rawSn=15/16 마킹·배치 이벤트는 본 드라이브 이전/동시에 다른 세션이 남긴 흔적이며 본 보고서의 실측 대상이 아니다(구분해서 기록).
- `CONTROL_NOTIFY_ENABLED=false`, `VLM_CLIENT_ENABLED=false` — 두 외부연동은 설정으로 비활성. 파이프라인 관찰 결과 VLM 스텝은 정확히 no-op 처리되고(설계대로), TASK_COMPLETED 아웃바운드 통지도 로그에 나타나지 않음(설정 비활성으로 인한 예상 동작, self-fill 아님).

## 대상 영상 (rawSn, 파일, 출처)

두 갈래로 시도했다 (하나는 seed 데이터 결함으로 막혀 조기 종료, 다른 하나로 전체 파이프라인 완주):

| rawSn | 출처 | vmsClipId | 결과 |
|--:|------|-----------|------|
| 23 (24,25 동시적재) | `/v1/dev/batch/scan` → `MNG_CLIP_MASTER`(JOB_DMND_YN='Y') dev seed 3건 픽업 | DEV-CLIP-9101 | 비식별 성공 → 마킹 성공 → **FRAME_EXTRACT 에서 FAILED** (seed 원본 파일 실체 없음, 근본원인 하단) |
| **26** | `/v1/dev/autolabel-test` 실 mp4 업로드(`backend/storage/raw/autolabel-test/0ea2adab-...mp4`, 772,868 bytes, 로컬 이전 테스트 산출물을 fixture 로 재사용) | DRIVE-CLIP-0725A | **전체 파이프라인 COMPLETED → 배정 → 라벨링 제출 → 검수 승인 → export SUCCEEDED** |

rawSn=23 경로는 "적재→비식별까지는 실제 외부 연동으로 통과하지만 실존 영상 파일이 없어 프레임추출에서 막히는" 케이스를 실측하기 위해 그대로 두었고, rawSn=26 경로로 전체 시나리오를 끝까지 완주시켰다.

## 사용한 진입점·명령 (재현 가능하게 원문)

```bash
# 1. REVIEWER/WORKER 토큰 발급 (userNo 기본값: REVIEWER=1001, WORKER=2001)
curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
  -H "Content-Type: application/json" \
  -d '{"role":"REVIEWER","channel":"INTERNAL"}'
curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
  -H "Content-Type: application/json" \
  -d '{"role":"WORKER","channel":"INTERNAL"}'

# 2. (경로 A — 실패 사례) 관제 학습용 적재 배치 1회 동기 실행 — MNG_CLIP_MASTER JOB_DMND_YN='Y' 픽업
curl -s -X POST http://localhost:18081/api/v1/dev/batch/scan -H "Authorization: Bearer $REV"
# → rawSn 23,24,25 PENDING 적재. VideoIngestedEvent → IngestDeidentifyBridge → AsyncDeidentifyRunner(@Async)
#   → KpstDeidentService.submit (mock-server POST /project) → KpstDeidentPollJob(30초 주기) → poll 완료 → MARKING_READY

# 3. (경로 A) AUTO 마킹 생성 (REVIEWER 는 배정 없이도 precheck 통과)
curl -s -X POST http://localhost:18081/api/v1/videos/23/markings \
  -H "Authorization: Bearer $REV" -H "Content-Type: application/json" \
  -d '{"mode":"AUTO","intervalFrames":30}'
# → MarkingCompletedEvent → MarkingBatchBridge(AFTER_COMMIT) → AsyncBatchRunner → BatchOrchestrator.process
# → MARKING(OK) → VLM(skip, disabled) → FRAME_EXTRACT: CustomException "원본 영상을 찾을 수 없습니다: rawSn=23" → FAILED (재시도 큐 enqueue, max 3회)

# 4. (경로 B — 성공 사례) 실 mp4 업로드 + 선두 비식별 트리거
curl -s -X POST http://localhost:18081/api/v1/dev/autolabel-test \
  -H "Authorization: Bearer $REV" \
  -F "file=@/Users/ck/Documents/workspace/klid/klid-label/backend/storage/raw/autolabel-test/0ea2adab-cf2e-4560-8446-7091c63fc78b.mp4;type=video/mp4" \
  -F 'meta={"vmsClipId":"DRIVE-CLIP-0725A","cctvId":"CCTV-001","eventTypeCd":"EV02000201","localGovCd":"1168000000","prvcTypeCd":"ANONY","capturedAt":"2026-07-25T09:00:00Z"};type=application/json'
# → rawSn=26, pipelineStatus=PROCESSING (DevPipelineRunner 가 즉시 선두 비식별 실행, MARKING_READY 에서 정지)

# 5. (경로 B) AUTO 마킹 생성
curl -s -X POST http://localhost:18081/api/v1/videos/26/markings \
  -H "Authorization: Bearer $REV" -H "Content-Type: application/json" \
  -d '{"mode":"AUTO","intervalFrames":60}'
# → MarkingCompletedEvent → post-marking 파이프라인 전체 실행(VLM skip→FRAME_EXTRACT→YOLO→SAM2→INTERPOLATE) → COMPLETED

# 6. 배정 → 검수 제출 → 검수 시작 → 승인
curl -s -X POST http://localhost:18081/api/v1/assignments -H "Authorization: Bearer $REV" \
  -H "Content-Type: application/json" -d '{"workerId":2001,"rawDataIds":[26],"reviewerId":1001}'
curl -s -X POST http://localhost:18081/api/v1/reviews/26/submit -H "Authorization: Bearer $WORKER"
curl -s -X POST http://localhost:18081/api/v1/reviews/26/start  -H "Authorization: Bearer $REV"
curl -s -X POST http://localhost:18081/api/v1/reviews/26/approve -H "Authorization: Bearer $REV"
```

## 단계별 실측

| # | 단계 | 결과 | 상태 전이(before→after) | 외부연동 경유 | 근거(로그/SELECT) |
|--:|------|:----:|----------------------|:---:|------------------|
| 1 | 적재 (관제 학습용 스캔) | 성공(23/24/25), 성공(26, 업로드 경로) | 없음 → `PENDING` | X (DB 픽업/파일 저장만) | `ls_data_raw` INSERT 확인 (raw_sn 23~26), 로그 `[TrainingIngest] ingested clipId=... rawSn=23` |
| 2 | 선두 비식별 (KPST mock) | 성공(23/24/25/26 전부) | `PENDING` → `MARKING_READY`, `de_ident_yn` N→Y | **O** — mock-server `POST /project` 3회 + `GET /retrieve_progress` 3회(23-25), 26도 동일 패턴 반복 | mock-server access log `"POST /project HTTP/1.1" 200`, `"GET /retrieve_progress HTTP/1.1" 200`; `ls_deident_proc_log`(rawSn=26) `proc_stts_cd=SUCCEEDED, poll_stts_cd=DOWNLOADED, de_idntf_pjt_id=6`(예시), `de_idntf_file_path_nm=/app/storage/deidentified/videos/26/..._mask.mp4` |
| 3 | 마킹 (AUTO) | 성공(23, 26) | `LS_MARKING` INSERT (`stts_cd=PENDING`, `mark_mode_cd=AUTO`) | X | rawSn=26: `ls_marking` marking_sn=29, frme_intv_nocs=60, fps=30 pin. API 응답 marks 16건(30fps·32초·간격60프레임) |
| 4 | VLM | **정상 no-op** (설계대로) | 상태 변화 없음 | X (설정으로 비활성) | 로그 `[Batch][VlmTimeseries] skipped (disabled) rawSn=26`; `ls_data_meta` rawSn=26 0건 |
| 5 | 프레임추출 (원본+비식별 2벌) | rawSn=23: **실패**(원본 파일 실체 없음) / rawSn=26: **성공** | rawSn=26: 없음 → `LS_DATA_SRC` 16건(frm_no 0~15) INSERT | X (내부 FFmpeg) | rawSn=26: `src_file_path_nm=/app/storage/raw/frames/raw/26/frame-N.jpg` vs `de_idntf_src_file_path_nm=/app/storage/deidentified/frames/deid/26/frame-N.jpg` — 서로 다른 경로 확인(설계 요구사항 충족). rawSn=23: `ls_batch_proc_log.err_msg_cn`="원본 영상을 찾을 수 없습니다: rawSn=23" |
| 6 | 오토라벨 YOLO | 성공(26) | `LS_DATA_LBL` INSERT 84건(YOLO 1차) | **O** — ai-server 실추론(YOLOX, CPU) | `Batch][Sam2] mapped label name=...` 로그 이전 단계, 최종 라벨 131건 중 다수가 YOLO 기원 |
| 7 | 오토라벨 SAM2 | 성공(26) | 라벨 폴리곤 갱신/추가, 최종 131건(person/car/bus 3종) | **O** — ai-server `POST /infer/sam2/segment` 실호출 다수(로그에서 확인) | ai-server access log `"POST /infer/sam2/segment HTTP/1.1" 200` 수십 회, 백엔드 로그 `[Batch][Sam2] mapped label name=car labelId=2 points=937` 등 |
| 8 | 트랙 보간 + COMPLETED | 성공(26) | `PROCESSING` → `COMPLETED` | X | `ls_batch_proc_log`(batch_proc_log_sn=14) `proc_step_cd=COMPLETED, proc_stts_cd=COMPLETED, bgng_dt=10:43:14, end_dt=10:45:20`(약 2분 소요, SAM2 CPU 추론이 대부분) |
| 9 | 배정→검수제출→검수시작→승인 | 전부 성공 | `LS_RAW_DATA_STATUS`: (배치완료 후)`ASSIGNED`→(제출)`PENDING`→(시작)`IN_REVIEW`→(승인)`APPROVED` | X | `ls_task_event_log` 3행(ASSIGN actor=1001, SUBMIT actor=2001, APPROVE actor=1001), API 응답 `dataSttsCd` 전이 그대로 관찰 |
| 10 | 라벨 버전 스냅샷 | 성공 | `LS_LABEL_VERSION` INSERT 16건(프레임별, `save_reason_cd=APPROVED`) | X | `ls_label_version` WHERE data_raw_sn=26 → 16 rows, 각기 다른 `version_hash` |
| 11 | export | **SUCCEEDED** | `LS_DATASET_EXPORT` INSERT(export_ver_no=1) | X | `ls_dataset_export` export_sn=5, `export_stts_cd=SUCCEEDED`, `export_path_nm=/app/storage/labeling/26/v1`, `frame_cnt=32`(=16프레임×2파일(jpg+json)); 폴더 실체 `orgnl/`·`deid/` 각각 frame-0~15 .jpg+.json 확인 |

## 최종 도달 지점

- **rawSn=26 경로: 전 구간(적재→비식별→마킹→VLM(no-op)→프레임추출→YOLO→SAM2→보간→COMPLETED→배정→라벨링제출→검수시작→검수승인→라벨버전스냅샷→export SUCCEEDED)을 실제 외부연동(mock-server KPST, ai-server YOLOX/SAM2) 경유로 완주.**
- rawSn=23 경로: 적재→비식별(성공, mock-server 실경유)→마킹(성공)까지는 정상, **FRAME_EXTRACT 단계에서 멈춤** — dev seed 데이터(`MNG_CLIP_MASTER.file_path='./storage/raw/seed/clip-9101.mp4'`)가 가리키는 원본 영상 파일이 이 로컬 docker 스토리지 볼륨에 실제로 존재하지 않아(비식별 mock 조차 원본을 못 찾아 18바이트짜리 `MOCK_DEIDENTIFIED` 플레이스홀더 텍스트를 대신 써넣음) 이후 FfmpegFrameExtractor 가 "원본 영상을 찾을 수 없습니다"로 실패. 재시도 큐(max 3회)에 등록된 채로 남음. **코드 수정 금지 원칙에 따라 seed 데이터를 임의로 채워 넣지 않고, 대신 실제 mp4 업로드 경로(경로 B)로 전체 시나리오를 완주시켜 근거를 확보**했다.

## ★ self-fill 의심 (외부 응답 없이 값이 채워진 지점)

| 값 | 위치 | 판정 |
|----|------|------|
| `video.weather` = `null` | export JSON | self-fill 아님 — **미채움**(관제 클립 이벤트의 날씨 코드 매핑 미완료로 알려진 기존 갭, memory `control-clip-meta-source-of-truth` 문서화됨). 신규 발견 아님 |
| `video.time_of_day` = `"NGT"`, `video.season` = `"SUMMER"` | export JSON | **저작도구 자체 파생값** — `capturedAt`(2026-07-25T09:00:00Z=KST 18:00)에서 도구가 직접 계산한 값이지 외부(관제) 응답이 아님. 기존 설계로 알려진 파생 로직(memory `env-privacy-meta-manual-input`)과 일치 — 신규 결함 아니고 기존에 문서화된 self-fill 지점의 재확인 |
| `de_idntf_pjt_id`/`de_idntf_datst_id`(프로젝트/데이터셋 ID) | `ls_deident_proc_log` | self-fill 아님 — mock-server `POST /project` 응답 바디에서 그대로 수신(실제 외부 응답값). 정상 |
| YOLO/SAM2 라벨 좌표·폴리곤 | `ls_data_lbl` | self-fill 아님 — ai-server 실제 추론 응답(HTTP 200, 실좌표) 그대로 저장. 정상 |
| `label_path`=`null`(export JSON `dataset.label_path`) | export JSON | 미채움 필드 — 코드상 의도적 null 로 보이나 근본 확인은 범위 밖(별도 확인 필요 항목으로만 기록) |

**결론: 이번 드라이브에서 새로 발견한 self-fill(외부 응답 없이 도구가 자체 생성해 마치 실측값처럼 보이는) 결함은 0건.** 관찰된 null/파생값은 모두 기존에 memory 로 문서화된 알려진 갭이거나 설계상 의도된 동작이다.

## 발견된 결함

### 1. (환경/시드 데이터 갭 — 코드 결함 아님) dev seed 클립 원본 파일 부재로 FRAME_EXTRACT 실패
- **심각도**: LOW (테스트 환경 fixture 문제, 프로덕션 코드 경로 결함 아님)
- **기대 동작**: `POST /v1/dev/batch/scan` 으로 픽업되는 `MNG_CLIP_MASTER`(JOB_DMND_YN='Y') seed 3건(DEV-CLIP-9101/9102/9103)이 실제 재생 가능한 영상 파일을 가리켜 전체 파이프라인을 시연 가능해야 함
- **현재 동작**: `file_path`(`./storage/raw/seed/clip-9101.mp4` 등)가 가리키는 파일이 이 로컬 docker 스토리지 볼륨(named volume `klid-storage`) 어디에도 존재하지 않음. 비식별 mock 서버조차 원본을 찾지 못해 18바이트 플레이스홀더(`MOCK_DEIDENTIFIED`)를 출력하고 `SUCCEEDED`로 잘못 보고 → 이후 FRAME_EXTRACT 가 "원본 영상을 찾을 수 없습니다"로 CustomException 발생 → FAILED, 재시도 큐(최대 3회) 등록
- **재현 경로**: `POST /v1/dev/batch/scan` → rawSn 확보 → `POST /v1/videos/{rawSn}/markings {"mode":"AUTO","intervalFrames":30}` → 약 수초 후 `data_stts_cd=FAILED`, `ls_batch_proc_log.err_msg_cn="원본 영상을 찾을 수 없습니다: rawSn=..."`
- **영향**: dev 편의 기능(`/v1/dev/batch/scan`)으로 "빠르게 전체 파이프라인 시연"을 시도하는 사람이 매번 이 실패를 마주침. 실제 운영 경로(관제 학습용 실제 클립)에는 영향 없음 — seed 데이터 fixture 문제로 한정
- **수정 방향(제안, 미실행)**: `docker/postgres-init` 시드 스크립트가 참조하는 실제 mp4 파일을 `klid-storage` 볼륨(`/app/storage/raw/seed/`)에 함께 준비하거나, seed 데이터 자체를 제거하고 안내 문서에 "전체 파이프라인 시연은 `/v1/dev/autolabel-test` 업로드 경로를 사용" 이라고 명시하는 편이 간단함. 코드 로직 자체는 수정 불필요(원본 부재 시 FRAME_EXTRACT 가 명확한 에러로 실패하는 것은 올바른 방어적 동작).

### 2. (관찰만, 결함 아닐 가능성 높음) mock-server 가 원본 부재 시에도 비식별 `SUCCEEDED` 로 보고
- **심각도**: 정보성 (mock-server 자체는 프로덕션 코드가 아니므로 결함 분류 보류)
- **관찰**: rawSn=23 케이스에서 원본 파일이 없었음에도 mock-server 는 `POST /project`→`GET /retrieve_progress`에 정상 `SUCCEEDED` 를 반환했고, 저작도구는 이를 그대로 신뢰해 `MARKING_READY` 로 전이시켰다(비식별 완료로 오인)
- **참고**: 실제 KPST 벤더 API 라면 원본 부재 시 에러를 반환할 가능성이 높으므로, 이는 mock-server 의 단순화된 구현 한계로 추정. 저작도구 코드 결함으로 단정하지 않고 "실 벤더 연동 시 재확인 필요" 항목으로만 기록.

## B 클러스터 검증용 참조 데이터

- **성공 완주 영상**: `rawSn=26` (vmsClipId=`DRIVE-CLIP-0725A`, cctvId=`CCTV-001`, eventTypeCd=`EV02000201`)
  - `ls_data_raw.raw_sn=26` (`data_stts_cd=COMPLETED`)
  - `ls_data_src.src_sn=446~461` (frm_no 0~15, raw/deid 경로 분리 확인됨)
  - `ls_data_lbl` — src_sn 446~461 조인, 총 131건 (person=labelId 1, car=labelId 2, bus=labelId 5)
  - `ls_marking.marking_sn=29`
  - `ls_deident_proc_log` data_raw_sn=26 (proc_stts_cd=SUCCEEDED, poll_stts_cd=DOWNLOADED)
  - `ls_batch_proc_log.batch_proc_log_sn=14` (proc_step_cd=COMPLETED)
  - `ls_task_assignment` — id=21 (workerId=2001, reviewerId=1001)
  - `ls_raw_data_status.raw_data_id=26` (최종 `data_stts_cd=APPROVED`)
  - `ls_task_event_log` evnt_id=26(ASSIGN)/27(SUBMIT)/28(APPROVE)
  - `ls_label_version` — data_raw_sn=26, lbl_version_sn 21~36 (16건, `save_reason_cd=APPROVED`)
  - `ls_dataset_export.export_sn=5` (`export_stts_cd=SUCCEEDED`, `export_path_nm=/app/storage/labeling/26/v1`, `frame_cnt=32`)
  - 실제 export 폴더: `/app/storage/labeling/26/v1/{orgnl,deid}/frame-{0..15}.{jpg,json}` (컨테이너 내부 경로, klid-backend 컨테이너에서 접근 가능)

- **부분 실패(FRAME_EXTRACT 블로커) 참조**: `rawSn=23,24,25` (vmsClipId DEV-CLIP-9101/9102/9103)
  - `ls_data_raw.data_stts_cd=FAILED`(23,24), 25는 마킹 미생성이라 `MARKING_READY` 대기 상태로 남음(다른 세션이 24를 건드렸을 가능성 있어 24 상태는 참고만)
  - `ls_batch_proc_log.batch_proc_log_sn=13` (data_raw_sn=23, proc_step_cd=FRAME_EXTRACT, err_msg_cn="원본 영상을 찾을 수 없습니다: rawSn=23")
  - `ls_bat_rty_wtng` raw_sn=23,24 (rty_nmtm=1, max_rty_nmtm=3, stts_cd=PENDING) — 재시도 대기 중

- **사용 토큰**: REVIEWER sub=1001(김검수), WORKER sub=2001(최라벨) — `/v1/dev/tokens` 로 즉시 재발급 가능(local 프로파일, 만료 1시간)
