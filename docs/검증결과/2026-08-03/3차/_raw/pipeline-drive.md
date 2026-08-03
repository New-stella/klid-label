# 정상 시나리오 파이프라인 실구동 — 2026-08-03 (3차 §3-3)

> **공용 정상 데이터 (B~E 클러스터 참고용)**
> **rawSn = 101**, **srcSn(대표, 라벨 저장 프레임) = 468** (프레임 0), 영상 내 srcSn 468~477 (10프레임)
> vmsClipId = `QA3RD-PIPELINE-DRIVE-001`, 이벤트유형 `INTRUSION`, WORKER=2001, REVIEWER=1001(user_no)
> assignmentId = 74, 라벨(labelSn) = 729 (BBOX, labelId=1 person), 라벨버전(lblVersionSn) = 20 (ver_no=1, APPROVED)
> export: export_sn=56, export_ver_no=1, export_path_nm=`/app/storage/raw/seed/101`, export_stts_cd=SUCCEEDED, frame_cnt=20

전 구간을 **실제 API 호출 + mock-server(:9400) 실경유 + DB 상태 조회**로 구동했다. 코드/설정은 수정하지 않았다(DB 데이터 삽입·업데이트만 수행 — 아래 "막힌 지점"에 근거 명시).

---

## 0. 사용한 방법 요약

- **적재**: `MNG_CLIP_MASTER` 스캔 방식이 아니라 **최신 코드 기준 `LS_DATA_INGEST` 인입 테이블** 경로였다(`TrainingVideoIngestService` 주석: "관제 2차에서 적재 주체가 반전되어 읽는 대상만 인입 테이블로 교체"). `LS_DATA_INGEST`에 신규 행 1건을 직접 INSERT(관제가 하는 행위를 대신 수행)한 뒤, `POST /v1/dev/batch/scan`(`BatchDevTriggerController` — dev/stg/local 한정 REVIEWER 전용, prd 미노출)으로 픽업을 트리거했다.
- **파일 소스**: 기존 검증 라운드(2차)가 이미 만들어 둔 시드 영상 `/app/storage/raw/seed/clip-9101.mp4`(20,590 bytes, mp4)를 재사용했다 — 신규 파일을 넣지 않고 기존 고정 fixture를 가리키기만 함.
- **인증**: `POST /v1/dev/tokens`로 REVIEWER(user_no=1001, 기존 `LS_USER_ROLE` 시드 행 재사용)·WORKER(user_no=2001, 기존 시드 재사용) JWT 발급. (역할 인가가 JWT `role` 클레임이 아니라 `LS_USER_ROLE.user_no` 조회로 전환되어 있어, 임의 userNo로는 403이 남 — 기존 시드 계정을 그대로 사용해야 함.)
- **마킹**: `POST /v1/videos/101/markings` (AUTO 모드, intervalFrames=30) — REVIEWER 토큰.
- **배정**: `POST /v1/assignments` (workerId=2001, rawDataIds=[101]) — REVIEWER 토큰.
- **라벨링**: `PUT /v1/frames/468/labels` (BBOX, labelId=1 person, MANUAL) — WORKER 토큰.
- **검수**: `POST /v1/reviews/101/submit`(WORKER) → `POST /v1/reviews/101/start`(REVIEWER) → `POST /v1/reviews/101/approve`(REVIEWER).
- **VLM/비식별/관제통지**: 전부 백엔드가 자동/비동기로 mock-server(:9400)와 실제 HTTP 왕복 — 별도 트리거 불필요.

---

## 1. 단계별 상태 전이 실측

| 단계 | LS_DATA_RAW.DATA_STTS_CD | DE_IDENT_YN | LS_RAW_DATA_STATUS.DATA_STTS_CD | 근거 |
|---|---|---|---|---|
| 인입 직후(적재 트랜잭션 커밋) | PENDING (경유, 직접 관측 안 함 — 매우 빠르게 전이) | - | (행 없음 — 배정 전) | `LS_DATA_INGEST.raw_sn=101` 매핑 확인 |
| 선두 비식별 완료 | **MARKING_READY** | **Y** | (행 없음) | `SELECT ... FROM ls_data_raw WHERE raw_sn=101` → `MARKING_READY / Y` |
| 마킹 생성(AUTO) 직후 | (배치 트리거) | Y | (행 없음) | 마킹 응답 `"batchTriggered":true` |
| 잔여 배치(VLM→프레임추출→YOLO/SAM2→보간) 완료 | **COMPLETED** | Y | (행 없음 — 아직 배정 전) | ~15초 폴링 후 `data_stts_cd=COMPLETED` |
| 배정(assign) 직후 | COMPLETED | Y | **ASSIGNED** (ver=3) | `ls_raw_data_status` 신규 행 생성 확인 |
| 라벨 저장(작업본) | COMPLETED | Y | ASSIGNED | 변화 없음(라벨 임시저장은 워크플로 상태 미변경) |
| WORKER 제출 | COMPLETED | Y | **PENDING**(status=REVIEW_PENDING, ver=4) | `/v1/reviews/101/submit` 응답 |
| REVIEWER 검수 시작 | COMPLETED | Y | **IN_REVIEW**(status=REVIEWING, ver=5) | `/v1/reviews/101/start` 응답 |
| REVIEWER 승인 | COMPLETED | Y | **APPROVED**(status=COMPLETED, ver=6) | `/v1/reviews/101/approve` 응답 + DB 재조회 일치 |

> CLAUDE.md 서술대로 **두 테이블 책임 분리**가 실측으로 확인됨: `LS_DATA_RAW.DATA_STTS_CD`는 배치 완료 시점(`COMPLETED`)에서 멈추고 이후 검수 사이클과 무관하게 유지되며(재조회 시에도 COMPLETED 그대로), 작업/검수 워크플로는 전적으로 `LS_RAW_DATA_STATUS.DATA_STTS_CD`(PENDING→ASSIGNED→PENDING(제출)→IN_REVIEW→APPROVED)가 담당했다.

---

## 2. mock-server 인바운드 로그 — 실제 왕복 확인 (rawSn=101 관련분만 발췌)

```
2026-08-03 15:04:50,161 [INFO] app.routers.deid - [MOCK][KPST] project created prj_id=1 name=raw101 creator=authoring
INFO:     172.20.0.5:40572 - "POST /project HTTP/1.1" 200 OK
2026-08-03 15:04:50,752 [INFO] app.services.deid_sim - [MOCK][KPST] 워터마크 인코더 = libx264(CPU)
2026-08-03 15:04:50,859 [INFO] app.services.media_probe - [MOCK][DEID] media duration probed file=clip-9101.mp4 duration=5.000s
2026-08-03 15:04:50,898 [INFO] app.services.deid_sim - [MOCK][KPST] watermark burned — 'MOCK 비식별 완료' 우측하단, 길이·무결성 검증 통과 file=clip-9101-mask.mp4
2026-08-03 15:04:50,898 [INFO] app.services.deid_sim - [MOCK][KPST] production completed prj_id=1 files=1
INFO:     172.20.0.5:33450 - "GET /retrieve_progress HTTP/1.1" 200 OK
...
2026-08-03 15:07:16,670 [INFO] app.routers.vlm - [MOCK][VLM] describe accepted request_id=0e29705f-339c-4799-9ed8-2053c65d5fdd callback_url=http://klid-backend:8080/api/v1/vlm/callback
INFO:     172.20.0.5:59230 - "POST /v1/videovlm/describe HTTP/1.1" 200 OK
2026-08-03 15:07:18,737 [INFO] app.services.media_probe - [MOCK][VLM] media duration probed file=clip-9101-mask.mp4 duration=5.000s
2026-08-03 15:07:18,763 [INFO] httpx - HTTP Request: POST http://klid-backend:8080/api/v1/vlm/callback "HTTP/1.1 200 "
2026-08-03 15:07:18,763 [INFO] app.services.vlm_sim - [MOCK][VLM] callback sent url=http://klid-backend:8080/api/v1/vlm/callback status=200 request_id=0e29705f-339c-4799-9ed8-2053c65d5fdd
...
2026-08-03 15:09:47,926 [INFO] app.routers.control - [MOCK] notify-completed accepted job_id=101 image_count=10
INFO:     172.20.0.5:48274 - "POST /api/data-set/v2/jobs/101/notify-completed HTTP/1.1" 202 Accepted
```

- **KPST 비식별**: `POST /project`(프로젝트 생성) → `GET /retrieve_progress`(폴링) 실왕복. 결과물 파일명은 CLAUDE.md 서술대로 `{stem}-mask{ext}`(= `clip-9101-mask.mp4`, `fileName`이 아니라 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 값을 그대로 사용) — 혼동 함정 회피 확인.
- **VLM**: `POST /v1/videovlm/describe`(요청 수락, request_id 발급) → 약 2초 뒤 mock-server가 **자발적으로 콜백**(`POST /api/v1/vlm/callback`, 200) — 논블로킹 제출(§C-1 서술)과 비동기 콜백 수신이 실측으로 일치.
- **관제 통지(TASK_COMPLETED)**: 검수 승인 직후 `POST /api/data-set/v2/jobs/101/notify-completed` 202 Accepted, `job_id=101`(=rawSn), `image_count=10`(=프레임 수) — self-fill 아님, 실제 콜아웃 확인.
- **ai-server(YOLO)**: `POST /infer/yolo/track` 10회 호출(프레임 수와 일치) 200 OK, 단 `ai-server/weights`가 빈 디렉터리라 `[MOCK] returning mock track (model not loaded or AI_MOCK_MODE=true) reason=weights_missing` 경고와 함께 **빈 detections**를 반환함(ai-server 소스 주석: "weights_missing 등은 운영 데이터 오염 방지 위해 빈 detections"). 이는 self-fill이 아니라 **결측 시 fail-closed(빈 결과) 설계** — stack-bringup.md가 이미 기록한 기존 갭(가중치 미탑재)의 재확인이며 신규 결함 아님.

---

## 3. DB 산출물 실측

```sql
-- 프레임 추출 (원본/비식별 경로 분기 확인)
SELECT src_sn, raw_sn, frm_no, src_file_path_nm, de_idntf_src_file_path_nm
FROM ls_data_src WHERE raw_sn=101 ORDER BY src_sn;
-- 468~477 (frm_no 0~9), src_file_path_nm=/app/storage/raw/frames/raw/101/frame-N.jpg,
-- de_idntf_src_file_path_nm=/app/storage/deidentified/frames/deid/101/frame-N.jpg (경로 분기 정상, 원본 덮어쓰기 없음)

-- VLM 시계열 메타
SELECT meta_key, left(meta_vl,80) FROM ls_data_meta WHERE raw_sn=101;
-- video.fps=30, video.codec=H264, video.duration_ms=10000, video.filesize=20590,
-- video.resolution=640x480, video.bit_rate=30624, "0-5"=(장소/날씨/상황/환경 텍스트, VLM 응답 그대로)

-- 라벨 버전 스냅샷 (승인 시점 생성)
SELECT lbl_version_sn, data_raw_sn, data_src_sn, ver_no, save_reason_cd, actvtn_yn, version_hash
FROM ls_label_version WHERE data_raw_sn=101;
-- 20 | 101 | 468 | 1 | APPROVED | Y | 6ac66291f4f9768122848e169ee2b57766aa7f8a02fef41b3e2a54afbb8b026e

-- export
SELECT export_sn, data_raw_sn, export_ver_no, export_path_nm, export_stts_cd, frame_cnt
FROM ls_dataset_export WHERE data_raw_sn=101;
-- 56 | 101 | 1 | /app/storage/raw/seed/101 | SUCCEEDED | 20

-- 데이터마트 View 반영 확인
SELECT raw_sn, review_completed_at, export_path_nm, frame_cnt FROM v_completed_video WHERE raw_sn=101;
-- 101 | 2026-08-04 00:09:47.86866 | /app/storage/raw/seed/101 | 20
```

파일시스템 실측(export 산출물, `docker exec klid-backend find /app/storage/raw/seed/101`):
```
101/deid/clip-9101-mask.mp4                 (비식별 영상 사본)
101/v1/orgnl/0000.jpg ~ 0009.jpg (+ .json)  (원본 프레임 10장 + 메타 JSON 10개)
101/v1/deid/0000.jpg ~ 0009.jpg (+ .json)   (비식별 프레임 10장 + 메타 JSON 10개)
```
→ export 버전 루트(`v1`) + 원본/비식별 2벌 이미지셋(합 20 frame_cnt) — CLAUDE.md export 정책과 일치.

---

## 4. 막힌 지점 (있었지만 코드/설정 변경 없이 DB 데이터로만 우회)

### 4-1. 마킹 생성이 "이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다"(INVALID_INPUT)로 거부됨
- **원인**: `LS_DATA_INGEST`(V147, 관제 인입 신규 테이블)에는 `EVNT_TYPE_CD` 컬럼 자체가 없다. `TrainingVideoIngestTx.persistRaw`가 `EVNT_TYPE_CD_UNAVAILABLE`(=null)로 고정 적재하는 것을 소스에서 확인함(`warnControlContractGaps` 메서드 주석: "EVNT_TYPE_CD — 인입에 유형코드 컬럼이 없어 신규 적재분은 전량 null이다"). 이는 **이미 알려진 기존 갭**(project memory `evnt-type-cd-null-gap.md` 기재 내용과 일치, 신규 발견 아님)이며, 관제 인입 컬럼 추가가 선행돼야 코드 수정 없이 해소된다.
- **우회 방법(코드/설정 미변경)**: `UPDATE ls_data_raw SET evnt_type_cd='INTRUSION' WHERE raw_sn=101` 로 DB 값만 직접 세팅 — 이는 검증 목적의 데이터 준비 행위이며 프로덕션 코드·설정·마이그레이션 파일은 손대지 않았다. 이후 마킹 생성이 정상 통과함(이벤트명 `INTRUSION`이 마킹 응답 `eventName`에 서버 소싱값으로 그대로 반영됨).
- **B~E 클러스터 유의사항**: 신규로 영상을 적재해 파이프라인을 태우려는 다른 에이전트도 동일하게 마킹 단계에서 막힐 것이다 — `LS_DATA_RAW.evnt_type_cd`를 사전에 채워야 마킹이 통과한다(코드 결함이 아니라 관제 계약 갭이므로 결함으로 보고하지 말 것 — UNCERTAINTIES 대상 아니고 이미 memory에 기록된 기존 갭).

### 4-2. 인증 토큰 발급 시 임의 userNo 사용 시 403
- **원인**: `JwtAuthenticationFilter`가 JWT `role` 클레임이 아니라 `LS_USER_ROLE.user_no` 테이블 조회로 역할을 재해석하도록 전환되어 있음(주석: "역할 분리 Phase 3 — 인가 역할 출처를 JWT role 클레임 → 저작도구 소유 LS_USER_ROLE 로 전환"). 임의 userNo(`QA3RD-REVIEWER` 등 비수치/미등록)로 발급한 토큰은 role 해석이 안 돼 인가 authority가 비고, 이후 REVIEWER 전용 API가 403(FORBIDDEN)을 반환했다.
- **우회 방법**: 기존 `LS_USER_ROLE` 시드에 있는 수치 userNo(1001=REVIEWER, 2001=WORKER)로 토큰을 재발급 — 코드/설정 변경 없음, 검증 절차상 정상 사용법(사용자 신규 등록이 필요 없는 기존 계정 재사용).

### 4-3. (결함 아님, 관측 기록) 오토라벨 결과 0건
- ai-server `weights` 디렉터리가 비어 있어 YOLO 추론은 실제로 호출됐으나(`POST /infer/yolo/track` × 10, 200 OK) 빈 detections를 반환 → `LS_DATA_LBL`에 자동 라벨 0건. §3-1(stack-bringup.md)이 이미 "가중치 미탑재로 오토라벨 정확도 케이스는 BLOCKED"라고 기록한 기존 갭의 재확인이며, self-fill 위반은 아니다(빈 응답 = fail-closed, 가짜 박스를 채우지 않음).

---

## 5. export 산출 결과

**성공**. `export_stts_cd=SUCCEEDED`, `frame_cnt=20`(원본10+비식별10), 파일시스템에 `v1/orgnl`·`v1/deid` 양쪽 실체 파일 확인, `V_COMPLETED_VIDEO` 뷰에도 반영됨. 관제 통지(`TASK_COMPLETED`)도 mock-server에 실제 도달(202 Accepted)해 export 성공 후에만 통지가 나간다는 정책과 순서가 일치했다(승인 00:09:47.839 → export 00:09:47.905 → notify-completed 00:09:47.926, 전부 같은 초 이내 순차 완료 — 동기 경로로 보임).

---

## 6. 다른 검증 에이전트를 위한 참고

- 이 rawSn=101 영상은 **이미 검수 승인 완료(APPROVED) + export SUCCEEDED** 상태다. 비식별 신고(`deident-report`)·재검토·재라벨링 등 "아직 진행 중" 시나리오가 필요한 케이스는 **이 데이터를 그대로 쓰지 말고 별도 rawSn을 새로 인입**할 것(이 문서의 §0 방법을 그대로 재사용 가능 — `LS_DATA_INGEST` INSERT + `/v1/dev/batch/scan` 또는 60초 주기 `ControlTrainingVideoScanJob`이 자동으로 픽업).
- `POST /v1/dev/batch/scan` 호출 시 응답 `data`(ingested 건수)가 0으로 나와도 실패가 아닐 수 있다 — 본 구동에서 실제로는 **60초 주기 `ControlTrainingVideoScanJob`이 수동 트리거보다 먼저 인입 행을 픽업**해 이미 `DONE` 처리한 뒤였다(수동 트리거 시점엔 PENDING 후보가 이미 0건). 즉 정상 동작이며 dev 트리거는 그저 "이미 처리됐다"를 관측한 것. 인입 성공 여부는 응답값이 아니라 `LS_DATA_INGEST.proc_stts_cd`/`LS_DATA_RAW` 존재로 확인할 것.
- 마킹 생성 전 `LS_DATA_RAW.evnt_type_cd`가 비어 있으면 반드시 채워야 한다(§4-1).
- 토큰 발급은 기존 시드 계정(REVIEWER=1001, WORKER=2001, 그 외 2002/3001 등)의 **수치 userNo**를 사용할 것.
