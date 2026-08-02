# 배치 파이프라인 정상 시나리오 실구동 — 2026-08-01 1차

## 환경 확인
- 스택: `klid-postgres`(healthy) / `klid-backend`(healthy, `local` 프로파일) / `klid-ai-server`(healthy) / `klid-mock-server:9400`(KPST/VLM/관제 통지 목업) / `klid-frontend`(healthy)
- `docker exec klid-backend env` 로 확인: `CONTROL_NOTIFY_ENABLED=true`, `VLM_CLIENT_ENABLED=true`, `CONTROL_NOTIFY_URL=http://klid-mock-server:9400` — 이번 드라이브는 두 외부연동이 모두 **활성** 상태였다(2026-07-25 1차 드라이브는 둘 다 false였음. 그때 관찰 못 한 VLM 콜백/관제 완료통지를 이번에 실측함).
- **중요한 환경 불일치 발견**: 워크스페이스 소스코드(git log b2b44f0e 등)는 "관제 인입 테이블 `LS_DATA_INGEST` 도입"(적재 소스를 `MNG_CLIP_MASTER` 스캔에서 `LS_DATA_INGEST` 직접 INSERT 픽업으로 교체) 기능이 이미 머지되어 있으나, **실행 중인 `klid-backend` 컨테이너의 `app.jar`는 이 변경 이전 빌드**다. 근거: DB(`flyway_schema_history`)가 `V146`까지만 적용되어 있고(`LS_DATA_INGEST`를 만드는 `V147`, `V148` 미적용), 실제로 `ls_data_ingest` 테이블이 DB에 존재하지 않으며 구 소스 테이블 `mng_clip_master`/`mng_clip_evnt_lst`가 여전히 존재한다. 즉 지금 컨테이너는 **구 경로(`MNG_CLIP_MASTER.JOB_DMND_YN='Y'` 스캔)** 로 동작한다. 이 사실을 먼저 확인한 뒤, 실행 중인 코드가 실제로 갖고 있는 진입점을 사용해 드라이브를 진행했다(코드/설정 수정 금지 원칙상 컨테이너 재빌드는 하지 않음).

## 적재 트리거 방법 조사
1. `ControlTrainingVideoScanJob`(Quartz)이 `TrainingVideoIngestService.scanAndIngest()`를 호출 — 현재 배포된 jar 기준으로는 `MNG_CLIP_MASTER.JOB_DMND_YN='Y'` 를 스캔.
2. dev 전용 REST 트리거 `POST /v1/dev/batch/scan` (`BatchDevTriggerController`, `@Profile("!prd")`)이 위 서비스를 동기 호출 — 확인해보니 `mng_clip_master`에는 이미 `job_dmnd_yn='Y'`인 시드 3건(`DEV-CLIP-9101/9102/9103`, 파일 `./storage/raw/seed/clip-910{1,2,3}.mp4`)이 있었지만, `ls_data_raw`에 이미 vmsClipId `CVER-91xx`로 15건(raw_sn 9101~9114, 25)이 과거 다른 세션에서 적재되어 있어 재스캔은 신규 유입을 만들지 않을 가능성이 높았다(멱등 UK).
3. 대신 **`POST /v1/dev/autolabel-test`**(`DevAutolabelTestController`, `authoring.dev.upload.enabled=true`가 `application-local.yml`에서 `local` 프로파일 시 항상 on) — 실제 mp4 파일 업로드 + 메타데이터로 신규 `rawSn`을 만들고 선두 비식별까지 즉시 트리거하는 dev 전용 경로. 이 경로로 신규 영상 1건을 실제로 흘려보냈다(2026-07-25 1차 드라이브와 동일 진입점, 재현 가능).
4. 업로드 원본 파일은 컨테이너 내 기존 시드 파일 `/app/storage/raw/seed/clip-9103.mp4`(20,590 bytes, ffprobe 확인 재생 길이 5.000s)를 로컬로 복사해 재사용.

## 사용한 진입점·명령 (재현 가능하게 원문)

```bash
# 1. REVIEWER/WORKER 토큰 발급
curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
  -H "Content-Type: application/json" -d '{"role":"REVIEWER","channel":"INTERNAL"}'
curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
  -H "Content-Type: application/json" -d '{"role":"WORKER","channel":"INTERNAL"}'

# 2. 영상 업로드 + 선두 비식별 트리거 (신규 rawSn 발급)
curl -s -X POST http://localhost:18081/api/v1/dev/autolabel-test \
  -H "Authorization: Bearer $REV" \
  -F "file=@qa0801-clip.mp4;type=video/mp4" \
  -F 'meta={"vmsClipId":"QA0801-DRIVE-01","cctvId":"CCTV-001","eventTypeCd":"EV02000201","localGovCd":"1168000000","prvcTypeCd":"ANONY","capturedAt":"2026-08-01T08:59:39Z"};type=application/json'
# → {"rawSn":26,"savedFilePath":"autolabel-test/95b05485-....mp4","pipelineStatus":"PROCESSING"}

# 3. AUTO 마킹 생성 (간격 30프레임)
curl -s -X POST http://localhost:18081/api/v1/videos/26/markings \
  -H "Authorization: Bearer $REV" -H "Content-Type: application/json" \
  -d '{"mode":"AUTO","intervalFrames":30}'
# → markingSn=23, marks 5건(0/1/2/3/4초), batchTriggered=true

# 4. 배정 → 검수 제출 → 검수 시작
curl -s -X POST http://localhost:18081/api/v1/assignments -H "Authorization: Bearer $REV" \
  -H "Content-Type: application/json" -d '{"workerId":2001,"rawDataIds":[26],"reviewerId":1001}'
curl -s -X POST http://localhost:18081/api/v1/reviews/26/submit -H "Authorization: Bearer $WORKER"
curl -s -X POST http://localhost:18081/api/v1/reviews/26/start  -H "Authorization: Bearer $REV"

# 5. 검수 승인 — 1차 시도는 라벨 0건이라 409(REVIEW_NO_LABEL)로 거부됨(정상 방어 로직).
#    negative sample 명시 확인 플래그로 재시도.
curl -s -X POST http://localhost:18081/api/v1/reviews/26/approve -H "Authorization: Bearer $REV"
# → 409 REVIEW_NO_LABEL "라벨이 없는 영상입니다. 객체가 없는 영상이 맞다면 '라벨 없음' 확인 후 승인하세요."
curl -s -X POST http://localhost:18081/api/v1/reviews/26/approve -H "Authorization: Bearer $REV" \
  -H "Content-Type: application/json" -d '{"noLabelConfirmed":true}'
# → 200 status=COMPLETED, dataSttsCd=APPROVED
```

## 단계별 실측 (rawSn=26, vmsClipId=QA0801-DRIVE-01)

| # | 단계 | 결과 | 상태 전이 | 소요시간 | 외부연동 경유 | 근거 |
|--:|------|:----:|----------|:---:|:---:|------|
| 1 | 적재 (`/v1/dev/autolabel-test` 업로드) | 성공 | 없음→`PENDING` | 즉시 | X (파일 저장 + DB INSERT) | 응답 `{"rawSn":26,"pipelineStatus":"PROCESSING"}`; `ls_data_raw` raw_sn=26 확인, `raw_file_path_nm=/app/storage/raw/autolabel-test/95b05485-....mp4` |
| 2 | 선두 비식별 (KPST mock) | 성공 | `PENDING`→`MARKING_READY`, `de_ident_yn` N→Y | 약 12.5초(08:59:39.337 요청 → 08:59:51.852 응답) | **O** — mock-server `POST /project` 200, `GET /retrieve_progress` 200 | mock-server 로그 `[MOCK][KPST] project created prj_id=10`, `[MOCK][KPST] watermark burned ... production completed prj_id=10 files=1`; `ls_deident_proc_log`(proc_log_sn=25) `proc_stts_cd=SUCCEEDED, poll_stts_cd=DOWNLOADED, de_idntf_pjt_id=10, de_idntf_datst_id=10, de_idntf_file_path_nm=/app/storage/raw/autolabel-test/26/deid/95b05485-....-mask.mp4` |
| 3 | 마킹 (AUTO) | 성공 | `LS_MARKING` INSERT(marking_sn=23, stts_cd=PENDING→처리후 완료) | 즉시 | X | API 응답 marks 5건(intervalFrames=30, 5초 영상 기준 0~4초), `batchTriggered=true` |
| 4 | VLM 시계열 | **성공(실 콜백 왕복 확인)** | `ls_data_meta` 1건 INSERT | 제출 18:00:10.130 → 콜백 적용 18:00:12.209 (약 2.1초) | **O** — mock-server `POST /v1/videovlm/describe` 200 accepted → 비동기 콜백 `POST http://klid-backend:8080/api/v1/vlm/callback` 200 | 백엔드 로그 `[Batch][VlmTimeseries] describe submit ... request_id=49be7da3-...`, `[Webhook][Vlm] result applied request_id=49be7da3-... new=1`; `ls_data_meta`(meta_sn=123, meta_key='0-5') 값 확인: "도심 이면도로/맑음/보행자 여러 명 이동 정황/주간 순광/심각성 3점" 등 VLM 산출 텍스트 — mock-server 자체 생성값이지만 실제 HTTP 왕복을 거쳐 저장된 값(self-fill 아님) |
| 5 | 프레임추출 (원본+비식별 2벌) | 성공 | `ls_data_src` 5건(frm_no 0~4) INSERT | 즉시(마킹 기반, mark-based extracted frames=5) | X (내부 FFmpeg) | `src_file_path_nm=/app/storage/raw/frames/raw/26/frame-N.jpg` vs `de_idntf_src_file_path_nm=/app/storage/deidentified/frames/deid/26/frame-N.jpg` — 경로 상이 확인(설계 요구 충족) |
| 6 | 오토라벨 YOLO | **호출은 성공, 검출 0건** | 라벨 미생성 | 즉시 | **O** — ai-server 실제 HTTP 호출 `POST /infer/yolo/track` 200 ×5(프레임당 1회) | 백엔드 로그 `[Batch][YOLO] mock response detected — ai-server is in mock mode. rawSn=26 srcSn=296~300 source=mock mockReason=weights_missing`; 최종 `yoloCount=0 bboxSaved=0`. **원인: ai-server 컨테이너에 YOLO 모델 가중치가 없어(weights_missing) mock 응답으로 폴백** — 코드 결함이 아니라 이 로컬 docker 환경의 모델 파일 미탑재 이슈 |
| 7 | 오토라벨 SAM2 | 호출 스킵(입력 0건이라 자연 스킵) | 폴리곤 미생성 | 즉시 | 해당 없음 (YOLO 박스가 0건이라 SAM2에 넘길 시드 없음) | `[Batch][Sam2] saved polygons rawSn=26 count=0` |
| 8 | 트랙 보간 | no-op(정상) | 변화 없음 | 즉시 | X | `[Batch][Interpolation] no interpolation candidates rawSn=26` |
| 9 | 배치 완료 | 성공 | `PROCESSING`→`COMPLETED`(`ls_data_raw.data_stts_cd`), `ls_raw_data_status.data_stts_cd`→`ASSIGNED` 복귀 | 배치 전체 약 0.38초(18:00:10.121→18:00:10.503, YOLO/SAM2가 mock이라 실추론 없이 즉시 종료) | X | `ls_batch_proc_log`(batch_proc_log_sn=20) `proc_step_cd=COMPLETED, proc_stts_cd=COMPLETED`; `[BatchOrchestrator] completed rawSn=26` |
| 10 | 배정→검수제출→검수시작 | 전부 성공 | `ASSIGNED`→(제출)`PENDING`(REVIEW_PENDING)→(시작)`IN_REVIEW`(REVIEWING) | 즉시(각 API) | X | `ls_task_assignment` id=29(workerId=2001, reviewerId=1001); 응답 `dataSttsCd` 전이 그대로 관찰 |
| 11 | 검수 승인 — 1차(라벨 0건, 미확인) | **정상 거부(409)** | 없음 | 즉시 | X | 응답 `errorCode=REVIEW_NO_LABEL`, 메시지 "라벨이 없는 영상입니다. 객체가 없는 영상이 맞다면 '라벨 없음' 확인 후 승인하세요." — negative sample 방어 로직 정상 동작 |
| 12 | 검수 승인 — 2차(`noLabelConfirmed:true`) | 성공 | `IN_REVIEW`→`APPROVED`(`status=COMPLETED`) | 즉시 | X | 응답 `status=COMPLETED, dataSttsCd=APPROVED` |
| 13 | 라벨 버전 스냅샷 | **의도된 0건**(결함 아님) | 없음 | - | X | `ls_label_version` WHERE data_raw_sn=26 → 0 rows. `VersionService.commitApproved` 소스 확인: "라벨이 하나도 없는 프레임은 스냅샷을 생성하지 않는다(스킵) — 빈 버전 적재 방지"가 코드 주석/동작으로 명문화되어 있어 negative sample 승인 시 버전 미생성은 설계대로임 |
| 14 | 관제 완료통지 (TASK_COMPLETED) | 성공 | - | 즉시(승인 응답 직후) | **O** — mock-server `POST /api/data-set/v2/jobs/26/notify-completed` 202 Accepted | 백엔드 로그 `[ControlNotify] TASK_COMPLETED sent rawSn=26 actual=TASK_COMPLETED`, `[ControlNotifyFallback] send success recorded`; mock-server 로그 `[MOCK] notify-completed accepted job_id=26 image_count=5`. **경고 관찰**: 같은 시점 `[ControlNotify] local gov not found lclgvCd=1168000000` WARN — 업로드 요청에 넣은 `localGovCd=1168000000`이 로컬 지자체 코드 마스터에 미등록이라 통지 페이로드의 지자체명 매핑이 비었을 가능성(통지 자체는 202로 성공, 필드 매핑만 결손 — 근본 확인은 이번 임무 범위 밖으로 사실만 기록) |
| 15 | export | **SUCCEEDED** | `LS_DATASET_EXPORT` INSERT(export_ver_no=1) | 즉시(승인과 같은 트랜잭션 흐름 내) | X | `ls_dataset_export` export_sn=13, `export_stts_cd=SUCCEEDED`, `export_path_nm=/app/storage/raw/autolabel-test/26`, `frame_cnt=10`(=5프레임×2파일); 로그 `[DatasetExport] frames written rawSn=26 kind=ORIGINAL version=1 written=5`, `kind=DEIDENTIFIED version=1 written=5`, `export succeeded rawSn=26 version=1 written=10`. 컨테이너 내 실체 확인: `/app/storage/raw/autolabel-test/26/v1/{orgnl,deid}/000{0..4}.{jpg,json}` 10+10=20개 파일 존재 |

## 최종 도달 지점

**전 구간을 실제 외부연동(mock-server KPST/VLM/관제통지, ai-server HTTP 왕복) 경유로 완주했다**: 적재 → 비식별(mock KPST 실왕복) → 마킹 → VLM 시계열(mock 실왕복, 비동기 콜백까지 확인) → 프레임추출(원본+비식별 2벌 분리 확인) → YOLO(ai-server 실HTTP 호출이나 검출 0건 — 모델 가중치 미탑재) → SAM2(자연 스킵) → 트랙보간(no-op) → COMPLETED → 배정 → 라벨링 제출 → 검수 시작 → 검수 승인(negative sample 확인 경로) → export SUCCEEDED → 관제 완료통지(mock 실수신) 까지 막힘 없이 도달.

이번 드라이브는 2026-07-25 1차와 달리 `CONTROL_NOTIFY_ENABLED=true`/`VLM_CLIENT_ENABLED=true` 환경이어서, 그때 관찰하지 못했던 **VLM 콜백 왕복**과 **TASK_COMPLETED 관제 통지 실전송**을 실측으로 추가 확보했다.

## 발견된 사항 (결함 여부 판정 포함)

### 1. (환경 갭 — 코드 결함 아님) 실행 중인 backend 컨테이너가 최신 소스(`LS_DATA_INGEST` 도입분)보다 낡음
- **심각도**: 정보성(환경 관리 이슈, 애플리케이션 코드 결함 아님)
- **근거**: 워크스페이스 git 최신 커밋(`b2b44f0e` 등)은 적재 소스를 `MNG_CLIP_MASTER` 스캔에서 `LS_DATA_INGEST` 직접 INSERT 픽업으로 교체했지만, 실행 중인 컨테이너는 `V146`(구 스키마) 기준으로 기동돼 있어 `ls_data_ingest` 테이블 자체가 없다.
- **영향**: 이번 드라이브는 구 경로(`MNG_CLIP_MASTER`/`/v1/dev/autolabel-test`)로 우회해 전체 파이프라인을 검증했으나, `LS_DATA_INGEST` 기반 신규 적재 경로 자체는 이번 드라이브에서 실측하지 못했다. 그 경로를 실측하려면 컨테이너 재빌드(최신 이미지 배포)가 선행되어야 한다(코드/설정 수정 금지 원칙상 본 임무에서는 수행하지 않음).

### 2. (환경 갭 — 코드 결함 아님) ai-server YOLO 모델 가중치 미탑재로 실추론 대신 mock 폴백
- **심각도**: 정보성(로컬 docker 환경 fixture/모델 배포 이슈, 애플리케이션 로직 결함 아님)
- **근거**: 백엔드 로그 `mock response detected — ai-server is in mock mode ... mockReason=weights_missing` — ai-server가 실제 요청을 받아 200을 응답하긴 했으나(HTTP 왕복 자체는 정상) 모델 가중치가 없어 빈 검출 결과를 반환.
- **영향**: YOLO/SAM2 실제 검출 좌표(person/car 등 바운딩박스·폴리곤)를 이번 드라이브에서는 실측하지 못했다(2026-07-25 1차 드라이브는 이 부분이 실추론으로 동작해 131건 라벨을 생성했었다 — 이번 컨테이너 형상은 그때와 다름). 실동작 확인이 필요하면 ai-server 컨테이너에 YOLOX/SAM2 가중치 파일 배치가 선행되어야 한다.

### 3. (관찰만, 근본원인 미조사) 관제 완료통지 페이로드에서 `localGovCd` 매핑 실패 WARN
- **심각도**: LOW~정보성
- **근거**: `[ControlNotify] local gov not found lclgvCd=1168000000` — 업로드 시 넣은 지자체 코드가 로컬 DB의 지자체 코드 마스터에 없어 통지 페이로드의 지자체명 필드가 비어 나갔을 가능성.
- **영향**: 통지 자체는 202로 성공했으므로 통지 전송 실패는 아니다. 지자체명 필드 결손이 실제 결함인지, 아니면 이 로컬 환경에 지자체 마스터 시드가 없어서인지는 이번 임무 범위 밖이라 근본원인은 미조사(사실만 기록).

## self-fill 의심 점검

| 값 | 위치 | 판정 |
|----|------|------|
| VLM 시계열 텍스트("도심 이면도로/맑음/보행자 여러 명 이동…") | `ls_data_meta.meta_vl` | self-fill 아님 — mock-server가 생성한 값이지만 `POST /v1/videovlm/describe` → 비동기 `POST /v1/vlm/callback` 실제 HTTP 왕복을 거쳐 저장됨(호출 없이 도구가 자체 생성한 값이 아님) |
| YOLO 검출 0건 | `ls_data_lbl` | self-fill 아님 — ai-server가 실제로 HTTP 요청을 받아 "가중치 없음"을 이유로 빈 결과를 응답한 것이며, 백엔드가 임의로 채운 값이 아님(발견 2 참조) |
| `de_idntf_pjt_id`/`de_idntf_datst_id`(10/10) | `ls_deident_proc_log` | self-fill 아님 — mock-server `POST /project` 응답 바디 값 그대로 수신 |
| `ls_label_version` 0건 | - | self-fill 아님(누락도 아님) — 코드 설계상 라벨 0건 프레임은 스냅샷 생성을 의도적으로 스킵 |

**결론: 이번 드라이브에서 새로 발견한 self-fill 결함은 0건.**

## B 클러스터 검증용 참조 데이터

- **완주 영상**: `rawSn=26` (vmsClipId=`QA0801-DRIVE-01`, cctvId=`CCTV-001`, eventTypeCd=`EV02000201`)
  - `ls_data_raw.raw_sn=26` — 최종 `data_stts_cd=COMPLETED`
  - `ls_raw_data_status.raw_data_id=26` — 최종 `data_stts_cd=APPROVED`(ver=6)
  - `ls_data_src.src_sn=296~300`(frm_no 0~4, raw/deid 경로 분리 확인)
  - `ls_marking.marking_sn=23`
  - `ls_deident_proc_log.proc_log_sn=25`(data_raw_sn=26, `proc_stts_cd=SUCCEEDED`)
  - `ls_data_meta.meta_sn=123`(meta_key='0-5', VLM 콜백 산출)
  - `ls_batch_proc_log.batch_proc_log_sn=20`(`proc_step_cd=COMPLETED`)
  - `ls_task_assignment.id=29`(workerId=2001, reviewerId=1001)
  - `ls_dataset_export.export_sn=13`(`export_stts_cd=SUCCEEDED`, `export_path_nm=/app/storage/raw/autolabel-test/26`, `frame_cnt=10`)
  - 실제 export 폴더(컨테이너 내부): `/app/storage/raw/autolabel-test/26/v1/{orgnl,deid}/000{0..4}.{jpg,json}`
- **negative sample(라벨 0건) 승인 케이스 참조**: `ls_label_version` WHERE data_raw_sn=26 → 0 rows(의도된 스킵, 결함 아님) — negative sample 승인 회귀 테스트 시 이 rawSn을 참조 가능
- **사용 토큰**: REVIEWER sub=1001(김검수), WORKER sub=2001(최라벨) — `/v1/dev/tokens` 로 즉시 재발급 가능(local 프로파일)
