# 2차 파이프라인 정상 시나리오 구동 (2026-07-31)

- 구동 시각: 호스트 UTC `2026-07-30 17:48 ~ 18:04` (= KST `2026-07-31 02:48 ~ 03:04`). **DB·로그 타임스탬프는 KST**(`02:48`…), **mock-server 로그는 UTC**(`17:48`…) — 같은 사건이 9시간 차로 찍히니 대조 시 주의.
- backend 이미지: HEAD `ca3c712b` 재빌드본(`bdc64ea2ac26`, 02:42 KST). 소스/설정/테스트 **무수정**, 빌드·테스트 **미실행**, 컨테이너 **재시작·재빌드 안 함**.
- 스키마: **`public`** (`klid_at` 없음 — 1차와 동일).
- 인증: `POST /api/v1/dev/tokens` (REVIEWER=1001 / WORKER=2001). 토큰 TTL 1h.

---

## 0. 착수 시점 DB 상태

`stack-bringup.md` §5 기록(38건, rawSn 123~125 실데이터 존재)과 **다르다. 그 사이에 실데이터가 다시 소멸했다.**

| 항목 | bringup 기록(02:0x 이전) | 본 작업 착수 시점(02:46) |
|---|---:|---:|
| `ls_data_raw` | 38 | **35** (전부 `DEV-SEED-200xx` 합성 시드) |
| rawSn 123/124/125 | 존재 | **소멸** |
| `ls_data_src` | 40 | 35 (시드 1프레임씩) |
| `ls_data_lbl` | 64 | 64 — **전 행이 고아**(대응 `ls_data_src` 없음) |
| `ls_deident_proc_log` | 3 | **0** |
| `ls_marking` / `ls_batch_proc_log` / `ls_data_meta` / `ls_label_version` | - | **전부 0** |
| `ls_dataset_export` / `ls_data_aug` / 파생영상 | 0 | 0 |
| `ls_raw_data_status` | APPROVED 1 · REJECTED 1 · PENDING 1 · ASSIGNED 33 | APPROVED 1 · REJECTED 1 · PENDING 1 · ASSIGNED 32 |

원인은 규명했다 → **PIPE-ISSUE-01**(부팅 시 `dev-seed.sql` 이 관제 픽업분을 지운다). 즉 착수 시점 B~E 클러스터 실측 참조 데이터는 **0건**이었고, 아래 전 과정을 새로 만들었다.

---

## 1. 단계별 구동 기록

기준 영상 **rawSn=126** (관제 클립 `DEV-CLIP-9101` → 시드 실영상 `sample-cctv-1080p.mp4`, 1920×1080 h264 112.7s).

| # | 단계 | 방법(요청) | 결과 | 상태전이 증거 | mock 경유 증거 | 소요 |
|---|---|---|---|---|---|---|
| 1 | 관제 학습용 적재 | `POST /v1/dev/batch/scan` (REVIEWER) | 200 `data=3` → rawSn **126/127/128** 신규 | `LS_DATA_RAW.DATA_STTS_CD=PENDING`, `DE_IDENT_YN=N` | - (공유 DB READ) | 즉시 |
| 2 | 선두 비식별(KPST) | 적재 이벤트 자동(`VideoIngestedEvent`) | **SUCCEEDED** | `126: PENDING→MARKING_READY`, `DE_IDENT_YN N→Y`, `LS_DEIDENT_PROC_LOG` REQUESTED→POLLING→SUCCEEDED | mock `POST /project`(prj_id=2, name=raw126) → `GET /retrieve_progress` ×3 → `[MOCK][KPST] watermark burned … sample-cctv-1080p-mask.mp4` → `production completed`. 호출자 IP `172.18.0.5`(=backend 컨테이너) | 약 40s |
| 3 | 작업 배정 | `POST /v1/assignments {"workerId":2001,"rawDataIds":[126]}` | 201 | `LS_RAW_DATA_STATUS(126)` 행 생성 = `ASSIGNED` | - | 즉시 |
| 4 | 마킹(AUTO) | `POST /v1/videos/126/markings {"mode":"AUTO","intervalFrames":300}` (WORKER) | 201, `batchTriggered=true`, marks 3건 | `LS_MARKING` 1행 | - | 즉시 |
| 5 | VLM 시계열 | 마킹 완료가 잔여 배치 트리거 | 시계열 메타 **15건** + 검수큐 15건 | `LS_DATA_META`(구간키 `0-8`…`112-113`) + `LS_DATA_META_REVIEW` 15×PENDING | mock `POST /v1/videovlm/describe` 200 → `[MOCK][VLM] media duration probed file=sample-cctv-1080p-mask.mp4`(**비식별본 전달 확인**) → 콜백 `POST http://klid-backend:8080/api/v1/vlm/callback` 200 | 약 2s |
| 6 | 프레임추출(2벌) | 동일 배치 | 3프레임 | `LS_DATA_SRC` 3행, `SRC_FILE_PATH_NM=/app/storage/raw/frames/raw/126/frame-N.jpg` · `DE_IDNTF_SRC_FILE_PATH_NM=/app/storage/deidentified/frames/deid/126/frame-N.jpg` (**두 경로 상이**) | - | 배치 내 |
| 7 | 오토라벨(YOLO/SAM2)+보간 | 동일 배치 | 라벨 19건(frame1=9, frame2=10, frame0=0) | `LS_DATA_LBL` (car/person/truck), `LS_DATA_SRC.LBL_VER=2` | ai-server(내부 사이드카) | 배치 내 |
| 8 | 배치 종료 | - | `LS_BATCH_PROC_LOG` `COMPLETED/COMPLETED` | `LS_DATA_RAW(126)=COMPLETED`, 작업상태 `ASSIGNED` 복귀 | - | 총 약 25s |
| 9 | 라벨링(수동 저장) | `PUT /v1/frames/65/labels` (BBOX person/car, `labelVersion:0`) | 200, `labelVersion 0→1` | `LS_DATA_LBL` +2(lblSn 134/135), `LS_DATA_LBL_HSTRY` 저장이벤트 1행(diff ADDED×2) | - | 즉시 |
| 10 | 검수 제출 | `POST /v1/reviews/126/submit` (WORKER) | 200 `REVIEW_PENDING` | 작업상태 `ASSIGNED→PENDING` | - | 즉시 |
| 11 | 검수 시작/승인 | `POST /v1/reviews/126/start` → `/approve` (REVIEWER) | 200 `COMPLETED` | 작업상태 `PENDING→IN_REVIEW→APPROVED`, `LS_DATA_META_REVIEW` 15건 **PENDING→APPROVED** | - | 즉시 |
| 12 | 버전 스냅샷 | 승인 부수효과 | **3행**(프레임별) | `LS_LABEL_VERSION` verNo=1, `SAVE_REASON_CD=APPROVED`, `ACTVTN_YN=Y`, 페이로드 631/10128/10482B | - | 즉시 |
| 13 | export v1 | 승인 부수효과(@Async) | **SUCCEEDED** | `LS_DATASET_EXPORT` sn=3, verNo=1, `EXPORT_PATH_NM=/app/storage/raw/seed/126`, `FRAME_CNT=6`(3프레임×2벌). 디스크 `126/v1/{orgnl,deid}/000{0,1,2}.{jpg,json}` 각 6파일 | - | 약 0.5s |
| 14 | 관제 완료통지 | export 성공 후 | **202** | `LS_CONTROL_NOTIFY_FALLBACK` sn=4 `TASK_COMPLETED/SUCCEEDED/SUCCESS` | mock `POST /api/data-set/v2/jobs/126/notify-completed` → **202 Accepted**, `image_count=3` | 즉시 |
| 15 | 증강 파생(WINTER) | `POST /v1/augments/request {"videoIds":[126],"types":["WINTER"]}` | 201 → 파생 **rawSn=129** | `LS_DATA_AUG` 1행 `WINTER/ACCEPTED`, `LS_DATA_AUG_JOB` SUCCEEDED(3파일), `LS_DATA_AUG_LBL_MAP` 21행, 파생 3프레임/21라벨 | mock `POST /api/genai/jobs` **202**(mode=I2I, inputs=3) → 콜백 `POST /api/v1/genai/callback` 200 ×4(진행 3 + 완료 1). 산출물 `/app/genai-out/genai/{jobId}/00N_frame-N_genai.jpg` 3개 실재, `LS_DATA_AUG_JOB_FILE.RSLT_FILE_PATH_NM` 가 그 경로를 가리킴, 로그 `[Augment][ExtractB] ingested rawSn=129 frames=3 (external outputs)` | 약 9s |
| 16 | 해상도 파생 | `POST /v1/videos/126/resolution {}` | 201, `derivatives=[130(720P),131(480P)]` (1080P 는 원본 동일로 스킵) | `LS_DATA_AUG` +2행 `RESL_720P/RESL_480P` = ACCEPTED, 파생 각 3프레임/21라벨, 파일 `/app/storage/deidentified/videos/resolution/126/13N/RESL_*.mp4` 실재, 프레임 파일 크기 축소 확인(126:15,487B → 130:16,150B → 131:7,773B) | - (내부 ffmpeg) | 약 10s |
| 17 | 승인 후 수정 → 재export/재통지 | `PUT /v1/frames/65/labels` (좌표 수정 2 + truck 신규 1) | 200 | **export v2 SUCCEEDED**(sn=4, verNo=2, 03:03:15) + 디스크 `126/v2/{orgnl,deid}/` 6+6파일. `LS_CONTROL_NOTIFY_FALLBACK` sn=5 `TASK_MODIFIED/SUCCEEDED` | 로그 `[ControlNotify] TASK_MODIFIED sent rawSn=126 frames=1 reExport=true` | **디바운스 약 60s 후** 발화 |
| 18 | 2·3번째 영상 완주 | 관제 클립 2건 추가(`DEV-CLIP-9104/9105`) → scan → 배정 → 마킹(interval 600) | rawSn **132/133** 각 6프레임/28라벨 COMPLETED | 비식별 SUCCEEDED ×2, 배치 COMPLETED ×2 | mock KPST·VLM 왕복 각 1회 | 약 3분 |
| 19 | 검수 대기건 확보 | `POST /v1/reviews/132/submit` | 200 `REVIEW_PENDING` | 132 작업상태 `PENDING`, `GET /v1/reviews` 에 노출 | - | 즉시 |
| 20 | 비식별 신고건 확보 | `POST /v1/labels/78/deident-report {"reason":...}` | 201 | `LS_DEIDENT_REPORT` sn=3 OPEN, `133 DE_IDENT_YN Y→F`. 게이트 실측: `GET /v1/frames/78/labels`→**412**, `GET /v1/videos/133/stream`→**404** | - | 즉시 |

**정리 — 목표 완주 여부**: 적재→비식별→마킹→VLM→프레임추출→오토라벨→보간→배정→라벨링→검수제출→승인→버전스냅샷→export(SUCCEEDED)→관제통지(202) **전 구간 실동작 완주**. 증강 파생 1건·해상도 파생 2건·승인후 수정→v2 재export→TASK_MODIFIED 까지 추가 확보.

---

## 2. 막힌 단계와 원인

파이프라인 자체가 막힌 구간은 **없다**(중단 없이 완주). 아래는 구동 중 실측된 결함 후보다.

### [PIPE-ISSUE-01] 부팅 시드 — `dev-seed.sql` 이 부팅마다 관제 픽업 실데이터를 전삭제하고, 자식 라벨은 고아로 남긴다
- **심각도**: HIGH (검증 토대 소실 + 데이터 정합)
- **기대 동작**: 개발 시드는 마스터 데이터만 멱등 재적재하고, 실제 파이프라인 산출물은 보존한다.
- **현재 동작**: `backend/src/main/resources/db/seed/dev-seed.sql:52` 의
  `DELETE FROM LS_DATA_RAW WHERE VMS_CLIP_ID LIKE 'DEV-CLIP-%';` 가 **부팅마다** 관제 학습용 픽업으로 적재된 영상을 전삭제한다. 나머지 정리 구문은 `RAW_SN BETWEEN 9001 AND 9999` 로 스코프돼 있는데, 실제 픽업 영상은 시퀀스 채번이라 그 범위 밖(123·126…)이다. 결과:
  1. `LS_DATA_RAW` 삭제 시 `LS_DATA_SRC` 는 FK 로 함께 사라지지만 **`LS_DATA_LBL` 은 FK 가 없어 남는다** → 착수 시점 `ls_data_lbl` **64행 전부가 고아**(`select count(*) from ls_data_lbl l where not exists(select 1 from ls_data_src s where s.src_sn=l.src_sn)` = **64**).
  2. 2차 착수 실측(bringup §5)의 rawSn=123 참조가 backend 재빌드 재기동 한 번으로 무효화됐다.
- **재현 경로**:
  ```sql
  select count(*) from ls_data_lbl l where not exists (select 1 from ls_data_src s where s.src_sn = l.src_sn);
  -- 부팅 전후 비교: select raw_sn from ls_data_raw where vms_clip_id like 'DEV-CLIP-%';
  ```
- **영향**: (a) 검증/시연 데이터가 재기동마다 소실 — 본 문서의 rawSn 126~133 도 **backend 재기동 시 전부 사라진다**(§3 경고). (b) 고아 라벨이 누적되어 라벨 통계·마이그레이션·FK 추가 작업의 지뢰가 된다.
- **수정 방향(제안)**: 삭제를 `LS_DATA_LBL`(자식)부터 동일 스코프로 확장하거나, 픽업 영상 삭제 자체를 시드에서 제거(멱등키 `VMS_CLIP_ID` UK 로 이미 중복 적재가 차단됨). ⚠ **구현하지 않는다.**

### [PIPE-ISSUE-02] 마킹 — 마킹 레코드·응답이 **원본(비식별 전) 영상 경로**를 담는다
- **심각도**: MEDIUM (정책 드리프트 + 경로 노출)
- **기대 동작**: 마킹은 비식별 영상 대상이므로(CLAUDE.md "마킹 화면 = 비식별 영상 스트리밍") 저장·노출 경로도 비식별본이어야 한다.
- **현재 동작**: `MarkingService:217-218` 이 `raw.getRawFilePathNm()`(원본 NAS 경로)을 그대로 `LS_MARKING.VIDEO_FILE_PATH_NM` 에 넣고, 201 응답 `videoPath` 로 **WORKER 에게 반환**한다.
  ```
  ls_marking: 4|126|VLM_COMPLETED|/app/storage/raw/seed/sample-cctv-1080p.mp4   ← 원본
  ls_deident_proc_log(126).DE_IDNTF_FILE_PATH_NM = /app/storage/raw/seed/126/deid/sample-cctv-1080p-mask.mp4
  ```
- **재현 경로**: `curl -X POST $BASE/v1/videos/126/markings -d '{"mode":"AUTO","intervalFrames":300}'` → 응답 `data.videoPath` 확인.
- **영향**: 실제 스트리밍은 `/v1/videos/{rawSn}/stream` 가 비식별본만 서빙하므로 **화면상 원본 노출은 없다**. 다만 (a) 원본 NAS 절대경로가 WORKER 응답에 실린다(CWE-209 계열 경로 노출), (b) 이 컬럼을 소비하는 후속 로직이 생기면 원본을 가리키게 된다. VLM 위탁 경로는 이 값을 쓰지 않고 별도로 비식별본을 해석한다(mock 로그가 `-mask.mp4` 를 프로브한 것으로 확인).
- **수정 방향(제안)**: 비식별 산출 경로(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`)를 저장·응답하거나, 응답에서 경로 필드를 제거하고 스트리밍 URL 만 내린다. ⚠ **구현하지 않는다.**

### [PIPE-ISSUE-03] export JSON — `video.length` 만 관제 신고값, 나머지는 ffprobe 실측 (한 레코드 안에서 출처 혼재)
- **심각도**: MEDIUM (데이터 품질)
- **기대 동작**: 한 산출물 안의 영상 기술메타는 동일 출처거나, 최소한 서로 모순되지 않아야 한다.
- **현재 동작**: `126/v2/orgnl/0000.json` 에 `"length":"30"`(= `LS_DATA_RAW.VDO_LEN_SEC`, 관제 `MNG_CLIP_MASTER.VDO_LEN_SEC` 30000ms 유래)인데, 같은 JSON 의 `fps=29.97 / width=1920 / height=1080 / filesize=28220079` 는 ffprobe 실측이고 `LS_DATA_META.video.duration_ms` 에는 **112679**(=112.7초)가 적재돼 있다. 즉 **같은 영상의 길이가 30초와 112.7초로 동시에 존재**하며 export 는 관제 신고값을 택한다.
- **재현 경로**:
  ```bash
  docker exec klid-backend head -c 900 /app/storage/raw/seed/126/v2/orgnl/0000.json
  ```
  ```sql
  select meta_key, meta_vl from ls_data_meta where raw_sn=126 and meta_key='video.duration_ms';
  select vdo_len_sec from ls_data_raw where raw_sn=126;
  ```
- **영향**: 데이터마트로 나가는 학습데이터셋의 영상 길이가 실제와 다를 수 있다(관제 신고값이 부정확한 경우 그대로 전파). 마킹 자동 간격 계산도 이 값을 쓰는 것으로 보인다 — 112초 영상에 `intervalFrames=300` 을 줬는데 마크가 **3개**(0/300/600)만 생성됐다.
- ⚠ 이 케이스의 30초는 dev 시드가 넣은 값이라 **시드 아티팩트일 가능성**이 있다. 다만 "ffprobe back-fill 이 있는데도 export 는 신고값을 쓴다"는 구조 자체는 실측이다(`TrainingVideoIngestTx` 주석은 1초 미만일 때만 ffprobe 백필에 위임한다고 명시).
- **수정 방향(제안)**: export 의 `video.length` 를 `video.duration_ms` 실측 우선으로 파생하거나, 두 값을 함께 내보내고 출처를 명시. ⚠ **구현하지 않는다.**

### [PIPE-ISSUE-04] 파생영상에 `LS_RAW_DATA_STATUS` 행이 생성되지 않는다 (작업 플로우 진입 불가 상태)
- **심각도**: MEDIUM
- **기대 동작**: CLAUDE.md — "파생영상은 **미검수(PENDING) 상태**로 시작 → 작업자 배정 → 수정 → 검수".
- **현재 동작**: 파생 129/130/131 은 `LS_DATA_RAW.DATA_STTS_CD=COMPLETED` 인데 **`LS_RAW_DATA_STATUS` 행 자체가 없다**(`(none)`). `GET /v1/tasks/board` 는 이들을 `status:"UNASSIGNED"` 로 표시한다(배정 부재에서 파생된 표시값).
- **재현 경로**:
  ```sql
  select r.raw_sn, coalesce(s.data_stts_cd,'(none)') from ls_data_raw r
    left join ls_raw_data_status s on s.raw_data_id=r.raw_sn where r.orgnl_raw_sn is not null;
  ```
- **영향**: 워크플로 행은 배정 시점에 lazy 생성되는 설계로 보이므로(126 도 배정 때 생성됐다) **기능적으로 막히지는 않으나**, "PENDING 으로 시작"이라는 문서 서술과 실제 표시(UNASSIGNED)가 다르다. bringup §7-6 이 "원인 미조사"로 남긴 rawSn=123 status 불일치도 **같은 원인**이다(DB=ASSIGNED 인데 board=UNASSIGNED 인 케이스는 배정행 유무 판정축이 다른 것).
- **수정 방향(제안)**: 문서를 실제 동작(배정 시 생성)에 맞추거나, 파생 확정 시 `PENDING` 행을 선생성. ⚠ **구현하지 않는다.**

### [PIPE-ISSUE-05] 비식별 실패(`DE_IDENT_YN='F'`) 영상에 **재비식별 트리거가 없다**
- **심각도**: LOW (기지 정책이나, 검증 환경에서 실질 차단)
- **현재 동작**: rawSn 127/128(파일 부재로 `KPST_SOURCE_MISSING` 실패)의 `LS_DATA_RAW.RAW_FILE_PATH_NM` 을 실파일로 고쳐도 재시도할 진입점이 없다. `POST /v1/dev/batch/trigger` 는 마킹 이후 파이프라인만 돌리고, 비식별은 적재 이벤트에서만 발화한다. `GET /v1/dev/batch/pending` 은 여전히 `[127,128]` 을 반환하지만 실행 수단이 없다.
- **영향**: 실패 영상은 새 클립(새 `CLIP_ID`)으로 다시 적재하는 것 외에 복구 경로가 없다 — 본 작업도 그렇게 우회했다(`DEV-CLIP-9104/9105` 신규 시드).
- **참고**: 정책상 "외부 솔루션 수동 재비식별"이 정본이므로 **설계된 부재**일 수 있다. 다만 적재 자체가 실패한 케이스(신고 아님)까지 같은 취급이라 운영상 고아 행이 남는다.
- **수정 방향(제안)**: 실패건 재비식별 dev/운영 트리거 또는 재적재 정리 배치. ⚠ **구현하지 않는다.**

---

## 3. ★검증용 참조 데이터 카탈로그

> ⚠ **수명 경고**: 아래 rawSn 126~133 은 `VMS_CLIP_ID LIKE 'DEV-CLIP-%'` 라 **backend 컨테이너를 재기동하면 `dev-seed.sql` 이 전부 삭제한다**(PIPE-ISSUE-01). 이 데이터를 쓰는 검증은 **backend 재기동 전에** 끝내야 한다. 재생성이 필요하면 §5 절차를 그대로 반복하면 된다(약 6분).

### 3-1. 영상 카탈로그

| 용도 | rawSn | 배치상태 | 작업/검수상태 | 비식별 | 프레임(srcSn) | 라벨 | 비고 |
|---|---:|---|---|:--:|---|---:|---|
| **완주 기준 영상 / APPROVED / export SUCCEEDED** | **126** | COMPLETED | **APPROVED** | Y | 3 (65, 66, 67) | 22 | 실라벨 보유. 버전스냅샷 3행, export v1+v2, TASK_COMPLETED+TASK_MODIFIED 발송 완료 |
| 비식별 실패(적재 직후 정지) | 127 | PENDING | (없음) | **F** | 0 | 0 | `KPST_SOURCE_MISSING`. 재비식별 트리거 없음(PIPE-ISSUE-05) |
| 비식별 실패(동일) | 128 | PENDING | (없음) | **F** | 0 | 0 | 동상 |
| **증강 파생(WINTER)** | **129** | COMPLETED | (행 없음) | Y | 3 (68, 69, 70) | 21 | `ORGNL_RAW_SN=126`. 비디오 `/app/storage/deidentified/videos/augment/126/129/WINTER.mp4` |
| **해상도 파생 720P** | **130** | COMPLETED | (행 없음) | Y | 3 (74, 75, 76) | 21 | `ORGNL_RAW_SN=126`, `AUG_TYPE_CD=RESL_720P`(1280×720) |
| **해상도 파생 480P** | **131** | COMPLETED | (행 없음) | Y | 3 (71, 72, 73) | 21 | `ORGNL_RAW_SN=126`, `AUG_TYPE_CD=RESL_480P`(854×480) |
| **검수 대기(실데이터)** | **132** | COMPLETED | **PENDING**(REVIEW_PENDING) | Y | 6 (77, 79, 81, 83, 85, 87) | 28 | `GET /v1/reviews` 목록 1행. 승인/반려 실동작 검증용 |
| **비식별 신고 OPEN(실데이터)** | **133** | COMPLETED | ASSIGNED | **F** | 6 (78, 80, 82, 84, 86, 88) | 28 | `LS_DEIDENT_REPORT` sn=3 OPEN. 게이트 실측: labels **412** / stream **404** |
| seed 합성 APPROVED | 20012 | COMPLETED | APPROVED | Y | 1 | 0 | 실라벨 없음 — export 없음 |
| seed 합성 REJECTED | 20013 | COMPLETED | REJECTED | Y | 1 | 0 | |
| seed 합성 PENDING | 20011 | COMPLETED | PENDING | Y | 1 | 0 | |
| seed 합성 ASSIGNED ×32 | 20001~20010, 20014~20035 | COMPLETED | ASSIGNED | Y | 1 | 0 | 목록/페이징용 |

### 3-2. 용도별 바로 쓰는 ID

| 필요한 것 | 값 |
|---|---|
| 라벨 보유 프레임(수동+AI 혼재) | `srcSn=65`(수동 3: lblSn 134·135·신규 truck) · `66`(AI 9) · `67`(AI 10) |
| 라벨 0건 프레임 | 없음(126 은 전부 보유). 파생 프레임 68~76 은 부모 복사본 |
| APPROVED + export SUCCEEDED | rawSn **126**, `EXPORT_PATH_NM=/app/storage/raw/seed/126`, 버전 **v1·v2** 둘 다 실재(`{v1,v2}/{orgnl,deid}/000N.{jpg,json}`), `FRAME_CNT=6` |
| 버전 diff/롤백 대상 | `LS_LABEL_VERSION` sn 12(src 65)·13(src 66)·14(src 67), verNo=1, `SAVE_REASON_CD=APPROVED` |
| 라벨 변경이력(저장이벤트 diff) | `LS_DATA_LBL_HSTRY` sn=11(src 65, ADDED×2) + 수정 이벤트 1행. `V_COMPLETED_LABEL_CHANGE` 조회 가능 |
| VLM 시계열 메타 | rawSn 126 · 15건(구간키 `0-8`…`112-113`), `LS_DATA_META_REVIEW` 15건 **APPROVED**(승인 시 일괄 전이) |
| 데이터마트 뷰 | `V_COMPLETED_VIDEO` **1행(126)** · `V_COMPLETED_FRAME` 3행(원본≠비식별 경로) · `V_COMPLETED_META` 15행 · `V_COMPLETED_LABEL_CHANGE` 1행 |
| 관제 통지 이력 | `LS_CONTROL_NOTIFY_FALLBACK` sn=4 `TASK_COMPLETED/SUCCEEDED` · sn=5 `TASK_MODIFIED/SUCCEEDED` (둘 다 rawSn=126) |
| 증강 잡 | `LS_DATA_AUG` sn=1(WINTER/ACCEPTED)·2(RESL_720P)·3(RESL_480P), 전부 `SRC_SN=65` 기준. `LS_DATA_AUG_JOB` sn=1 SUCCEEDED, `otsdJobId=635750cf0a52426f84a7b68abcfdc067` |
| 증강 이력 API | `GET /v1/augments` → 1행, `types=["WINTER"]`, `resolutionTypes=["RESL_720P","RESL_480P"]` |
| 비식별 신고 | `LS_DEIDENT_REPORT` sn=3, rawSn=133, srcSn=78, `OPEN`, 신고자 2001 |
| 관제 학습용 클립 시드 | `MNG_CLIP_MASTER` 5건(`DEV-CLIP-9101`~`9105`) — 9101/9104/9105 는 실파일 경로로 교체됨(§5), 9102/9103 은 원래 더미 경로 |
| 라벨 마스터 | `LS_LABEL` 9건 — 1 person / 2 car / 3 bicycle / 4 motorbike / 5 bus / 6 truck (BBOX, COCO 매핑 有) · 7 fire / 8 smoke / 9 water (POLYGON, `DTCT_TYPE_CD` 없음 → AI 탐지 불가) |

### 3-3. 재사용 스니펫

```bash
BASE=http://localhost:18081/api
RT=$(curl -s -X POST $BASE/v1/dev/tokens -H 'Content-Type: application/json' \
      -d '{"role":"REVIEWER","channel":"INTERNAL"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
WT=$(curl -s -X POST $BASE/v1/dev/tokens -H 'Content-Type: application/json' \
      -d '{"role":"WORKER","channel":"INTERNAL"}'   | sed 's/.*"token":"\([^"]*\)".*/\1/')
P() { docker exec klid-postgres psql -U klid_user -d klid_system -At -F'|' -c "$1"; }
```
- DB 접속: `docker exec klid-postgres psql -U klid_user -d klid_system` (스키마 `public`)
- 컬럼명 함정: `ls_data_raw.de_ident_yn`(`de_idntf_yn` 아님) · `ls_raw_data_status.raw_data_id`(`raw_sn` 아님) · `ls_batch_proc_log.proc_step_cd/proc_stts_cd/err_msg_cn` · `ls_data_meta.meta_key/meta_vl` · `ls_label_version.save_reason_cd/actvtn_yn`

---

## 4. self-fill 의심 지점

**본 구동에서 "외부 응답 없이 본 프로그램이 값을 자체 생성"한 확증 사례는 없다.** 외부 3연동 모두 mock-server 실왕복이 backend 컨테이너 IP(`172.18.0.5`)에서 관측됐다.

| 연동 | 판정 | 근거 |
|---|:--:|---|
| KPST 비식별 | **실경유** | `POST /project`→`GET /retrieve_progress`(폴링) 왕복, mock 이 실제 ffmpeg 로 워터마크 합성한 `-mask.mp4` 산출, 그 경로가 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 에 기록됨. 파일명이 `deidentified.mp4` 가 아니라 `{stem}-mask.mp4` 인 것이 "조합하지 않고 응답값을 읽었다"는 증거 |
| VLM 시계열 | **실경유** | `POST /v1/videovlm/describe` 200 → mock→backend 콜백 200. 메타 15건의 문안이 mock 시뮬레이터 문구(장소/날씨/상황 3줄 템플릿). 전달 미디어가 **비식별본**(`-mask.mp4`)임이 mock 프로브 로그로 확인 |
| 증강(genai) | **실경유** | `POST /api/genai/jobs` 202 → 콜백 4회 200. `LS_DATA_AUG_JOB_FILE.RSLT_FILE_PATH_NM` 이 mock 산출 경로(`/app/genai-out/genai/{jobId}/00N_frame-N_genai.jpg`)를 가리키고 그 파일이 실재. 로그 `ingested rawSn=129 frames=3 (external outputs)` |

**단, 증강만은 "실경유"를 md5 로 재확인할 수 없다 — 검증 가능성 자체의 결함(정보 제공)**

- 파생 129 의 프레임 md5 == mock genai 산출물 md5 == **부모 126 의 비식별 프레임 md5**(`064376b24753aaf61f950a89f66b0e96`, 3자 동일).
- 원인은 mock 쪽이다 — `mock-server/app/services/genai_sim.py` 가 입력 파일을 **바이트 그대로 복사**해 `_genai` 접미사만 붙인다(`_copy_limited`). 시각적 변환이 전혀 없다.
- 결과: **"backend 가 벤더 산출물을 가져왔다"와 "backend 가 부모 프레임을 자체 복사했다"를 산출물만으로는 구별할 수 없다.** 이번엔 DB(`RSLT_FILE_PATH_NM`)와 로그(`external outputs`)로 전자임을 확인했으나, 픽셀 수준 증거는 없다.
- 제안(⚠ 구현하지 않음): KPST 비식별 목업이 워터마크를 굽는 것처럼 **genai 목업도 유형별 가시 마커(WINTER/NIGHT/RAIN)를 합성**하면, 이후 모든 증강 검증이 md5/육안으로 출처를 판별할 수 있다.

기타 확인:
- `AI_MOCK_MODE=true` 가 backend env 에 있으나 backend 코드가 참조하지 않는 dead env(bringup §3 기록과 동일). ai-server 자체는 `AI_MOCK_MODE=false` 로 실추론 — YOLO 가 프레임 0 에서 0건, 프레임 1/2 에서 9·10건을 낸 **불균등 결과** 자체가 자체생성이 아닌 실추론의 방증.
- `CONTROL_NOTIFY_ENABLED=true`(런타임 실효값 확인) — 통지가 실제로 mock 에 **202** 로 도달했고 `LS_CONTROL_NOTIFY_FALLBACK` 에 `SUCCESS` 로 기록됨. 1차의 `false` 상태는 해소돼 있어 별도 조치 불필요.

---

## 5. 수동 개입 구간

배치·상태 전이는 **전부 API 로 실구동**했다. `UPDATE` 로 상태를 위조한 구간은 없다. 개입은 아래 3건뿐이며 모두 **관제 소유(MNG_*) 입력 픽스처**를 만든 것이다 — 저작도구가 READ 하는 "관제가 학습용으로 설정한 영상"을 실제로 존재하게 만든 것.

| # | 무엇을 | 왜 |
|---|---|---|
| 1 | `UPDATE mng_clip_master SET file_path='/app/storage/raw/seed/sample-cctv-1080p.mp4' WHERE clip_id='DEV-CLIP-9101'` | `dev-seed.sql` 이 넣는 기본 경로 `./storage/raw/seed/clip-9101.mp4` 는 **실파일이 없다**(시드 주석도 "실파일 부재 허용 — 픽업·적재·이벤트 발행 검증 목적"이라 명시). 실파일 없이는 비식별 단계에서 `KPST_SOURCE_MISSING` 으로 즉시 실패해 파이프라인 완주가 원천 불가. 컨테이너에 실재하는 유일한 CCTV 영상으로 교체 |
| 2 | 위와 동일 UPDATE 를 `DEV-CLIP-9102/9103` 에 적용 + `ls_data_raw(127,128).raw_file_path_nm` 갱신 | 2번째 영상 확보 시도. **결과적으로 무효**했다 — 재비식별 트리거가 없어(PIPE-ISSUE-05) 이미 `F` 인 127/128 은 되살아나지 않았다. 이 두 행은 "비식별 실패 상태" 참조로만 남긴다 |
| 3 | `INSERT INTO mng_clip_master/mng_clip_evnt_lst` 로 `DEV-CLIP-9104`/`9105` 2건 신규 시드(실파일 경로, `JOB_DMND_YN='Y'`) | 2·3번째 완주 영상(132/133) 확보. 관제가 영상 2건을 추가로 학습용 설정한 상황을 그대로 재현한 것이며, 적재 이후는 전부 정규 API 경로(`/v1/dev/batch/scan` → 자동 비식별 → 마킹 → 배치) |

**개입하지 않은 것(명시)**: `LS_DATA_RAW.DATA_STTS_CD`, `LS_RAW_DATA_STATUS.DATA_STTS_CD`, `LS_DEIDENT_PROC_LOG`, `LS_DATASET_EXPORT`, `LS_LABEL_VERSION`, `LS_CONTROL_NOTIFY_FALLBACK`, `LS_DATA_AUG*` — 모두 애플리케이션이 스스로 쓴 값이다.

### 환경 관련 부기 (다른 에이전트 주의)

- 구동 중 mock-server 에 **호스트 IP(`172.18.0.1`)발 KPST 호출**(`GET /retrieve_progress`, 18:02 UTC)이 관측됐다. backend 컨테이너발(`172.18.0.5`)이 아니므로 **다른 세션/에이전트가 같은 스택을 동시에 만지고 있을 가능성**이 있다. mock 로그로 증거를 인용할 때 호출자 IP 를 반드시 확인할 것.
- backend 로그에 `Invalid character found in the request target [/api/v1\genai/callback ]` 가 1건 있으나 본 구동과 무관한 외부 요청이다.
- 컨테이너 재시작·재빌드·빌드/테스트 실행은 하지 않았다. 소스/설정/테스트 파일 수정 0건(본 문서 1개만 신규 작성).
