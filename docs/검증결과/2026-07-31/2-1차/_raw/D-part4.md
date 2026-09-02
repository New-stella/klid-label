# D 클러스터 part4 — D-7 관제 조회 API(8) + D-8 데이터마트 View(23) = 31건

- 회차: 2026-07-31 / 2-1차
- 담당 범위: `docs/test-cases/D-review-version-notify.md` line 213(D-7) ~ 파일 끝(D-8)
- 이슈 ID 대역: **D-ISSUE-56~70**
- 검증 환경: backend `http://localhost:18081/api` · DB `docker compose exec klid-postgres psql -U klid_user -d klid_system`(스키마 `public`) · mock-server :9400
- 기준 데이터: `_raw/pipeline-drive.md` 의 rawSn=4(APPROVED + export SUCCEEDED). 사용자 `1001=REVIEWER`, `2001=WORKER(rawSn=4 LABELER 배정)`, `2002=WORKER(무배정)`, `3001=PORTAL_USER`

## ★ UNCERTAINTIES #4 반전 적용 결과 (IDOR 재검증)

1차(07-25)의 "관제 조회 API IDOR 케이스 제외" 지침을 **폐기하고 전 경로를 실동작으로 적극 반증**했다.
`summary`/`labels`/`meta` 세 경로 × (무배정 WORKER · 타 영상 WORKER · PORTAL · 무토큰 · 미존재 rawSn) 조합을 실제 HTTP 로 시도했고,
**모든 우회 시도가 차단**됐다(TC-NOTIFY-052 **PASS**). 상세는 아래 표·근거 참조.

## ⚠ 검증 중 환경 변동 고지 (판정 신뢰도 관련)

다른 병렬 에이전트가 같은 DB 를 동시 사용해 검증 도중 `rawSn=4` 의 `LS_RAW_DATA_STATUS.DATA_STTS_CD` 가
`APPROVED → IN_REVIEW` 로 바뀌었다(03:16). 이 때문에 **뷰 게이트 검증은 다음 두 방식으로 수행**했다.

1. **상태 변동 자체를 게이트 실증으로 사용** — APPROVED 시점에 `V_COMPLETED_VIDEO` 1행/`V_COMPLETED_FRAME` 30행,
   IN_REVIEW 로 바뀐 뒤 **양쪽 0행**. 게이트가 실제로 작동함을 반대 방향에서 확인(TC-MARTVIEW-001).
2. **`BEGIN … ROLLBACK` 트랜잭션 반례 주입** — 상태/경로/스냅샷/이력을 트랜잭션 안에서만 조작해 4뷰를 관측한 뒤
   전량 롤백. **영속 변경 0건**(코드·설정·데이터 미수정 규칙 준수), 다른 에이전트 데이터에도 영향 없음.

---

## D-7. 관제 조회 API (TC-NOTIFY, 조회) — 8건

| ID | 판정 | 근거 확인 |
|----|:----:|------|
| TC-NOTIFY-026 | PASS | [실동작] `GET /v1/tasks/4/summary` (REVIEWER) → 200 `{"rawSn":4,"status":"COMPLETED","totalFrames":30,"labeledFrames":2,"totalLabels":2,"totalMeta":7,"reviewerName":"김검수","lastModifiedAt":"2026-07-30T18:02:55.267961Z"}`. 프레임/라벨/메타 카운트 + 상태 + 최종수정일 전부 존재. `reviewerName` 은 상수가 아니라 `LS_TASK_EVENT_LOG(APPROVE)→MNG_ACCT_USER.USER_NM` 실측(`TaskQueryService.java:215-223`) — self-fill 아님. WORKER(2001, 배정자) 로도 200 |
| TC-NOTIFY-027 | PASS | [실동작] `GET /v1/tasks/4/labels` 응답 아이템 = `{srcSn, frameNo, labels:[{lblSn,lblTypeCd,label,points}]}` 뿐. `filePath`/`deIdntfSrcFilePath`/경로 문자열 **0건**. [정적] `TaskQueryService.toLabelsResponse` (`:234-245`) 가 record 4필드로만 매핑해 구조적으로 경로 유출 불가(CWE-359) |
| TC-NOTIFY-028 | PASS | [실동작] 기본 `size=20`(`?page=0` → `"size":20`) · `?size=500` → **`"size":100` 클램프** · `?frameIds=1..100` → 200 · `?frameIds=1..101` → **400** `{"errorCode":"INVALID_INPUT","message":"getLabels.frameIds: frameIds 는 최대 100개까지 지정할 수 있습니다."}`. 타 영상 프레임 `frameIds=34`(rawSn=6 소속) 지정 시 `totalElements:0` — rawSn 스코프 밖 프레임은 필터로도 못 끌어옴. [정적] `TaskQueryController.java:91-94` `@Size(max=100)` + `TaskQueryService.capped()` `:201-209` + 서비스 2중 방어 `normalizeFrameIds` `:178-189` |
| TC-NOTIFY-029 | PASS | [실동작] `GET /v1/tasks/4/meta` → `{"rawSn":4,"items":[…],"page":0,"size":20,"totalElements":7,"totalPages":1}`, `?size=500` → `"size":100`. `metaSn/metaKey/metaVl` 3필드. [정적] `cappedMeta()` `:192-199`(metaSn ASC 고정 정렬) |
| TC-NOTIFY-030 | PASS | [실동작] REVIEWER 로 `rawSn=99999` 3경로 전부 **404** `{"errorCode":"NOT_FOUND","message":"영상을 찾을 수 없습니다."}`. `rawSn=-1`·`0` 도 404. `rawSn=abc`·`9223372036854775808`(long overflow) 는 400 `파라미터 형식이 올바르지 않습니다: rawSn`(500 아님) |
| TC-NOTIFY-031 | PASS | [실동작] 무토큰 → **401** `UNAUTHORIZED` / PORTAL 토큰(3001) → **403** `권한이 없습니다.` (3경로 모두). [정적] 토글 off 시 컨트롤러 미등록은 `@ConditionalOnProperty(name="authoring.control-notify.enabled", havingValue="true")` (`TaskQueryController.java:46`) — 현 스택은 `CONTROL_NOTIFY_ENABLED=true` 라 off 분기는 정적 확인. 비고: 컨테이너 단계에서 거부되는 경로(`/v1/tasks/4%2F../4/summary`)는 Tomcat 기본 HTML 400 이 나가 `ApiResponse` 래퍼가 아님(D-ISSUE-58, LOW) |
| **TC-NOTIFY-052** | PASS | [실동작] **IDOR 순회 전면 차단 확인.** ① 무배정 WORKER(2002) → rawSn=4 의 summary/labels/meta **전부 403** `본인에게 배정되지 않은 영상입니다.` ② 배정 WORKER(2001) 가 **타 영상 rawSn=5** 접근 → 3경로 **403** ③ 배정 WORKER(2001) → 본인 rawSn=4 는 **200**(정상 통과 확인) ④ REVIEWER(1001) 는 3경로 전부 200 ⑤ 존재 오라클 누수 없음 — 무배정 WORKER 는 **없는 rawSn(99999)에도 403**(404 아님)이라 rawSn 존재 여부를 역추론할 수 없다. [정적] `TaskQueryController.java:71,96,121` → `LabelAccessGuard.verifyRawAccess` (`:83-100`, REVIEWER 얼리리턴 / WORKER `existsByUserNoAndTaskTypeCdAndRawDataId(selfNo, TASK_LABELER, rawSn)` / 그 외 FORBIDDEN). 테스트커버: `TaskQueryControllerTest#배정되지_않은_WORKER_는_rawSn_순회로_라벨을_읽을_수_없다_403`(:212) |
| **TC-NOTIFY-053** | PASS | [실동작] `DE_IDENT_YN='F'` 인 rawSn=8·9 로 검증. REVIEWER 로 `labels` → **412** `{"errorCode":"PRECONDITION_FAILED","message":"비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다."}` — **역할 무관 차단** 확인. 같은 영상의 `summary`·`meta` 는 **200**(좌표 미포함이라 대상 아님) 확인. **인가 이후 평가** 확인 — 무배정 WORKER(2002) 가 rawSn=8 `labels` 호출 시 412 가 아니라 **403** 이 먼저 나온다(게이트가 인가를 대체·우회하지 않음). [정적] `TaskQueryController.java:96→101` 순서 + `LabelAccessGuard.requireNotUnderDeidentReport` `:135-141` → `DeidentReportGate` 단일 원천 |

**D-7 집계**: PASS 8 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / 확인필요 0

---

## D-8. 데이터마트 View (TC-MARTVIEW) — 23건

> 확정 정책(CLAUDE.md): **검수 완료·통지 건의 관제 접근은 어떤 사유로도 차단하지 않는다.** 신고 구간(`DE_IDNTF_YN='F'`)에도
> 뷰에서 행을 감추거나 경로를 NULL 로 비우지 않는다 → TC-MARTVIEW-018 은 이 정책대로 동작함을 확인(결함 재분류 안 함).

| ID | 판정 | 근거 확인 |
|----|:----:|------|
| TC-MARTVIEW-001 | PASS | [실동작] 게이트 양방향 실증. ①rawSn=4 가 `APPROVED` 이던 03:0x 시점 → `V_COMPLETED_VIDEO` **1행** ②다른 에이전트가 `IN_REVIEW` 로 되돌린 뒤 → **0행**(`ls_dataset_video_meta` 행은 그대로 존재) ③rollback tx 로 다시 `APPROVED` 로 만들면 → **1행** 복귀. `INNER JOIN LS_RAW_DATA_STATUS s` + `s.DATA_STTS_CD='APPROVED'` 실효. [정적] 라이브 `pg_get_viewdef` 가 V138 정의와 1:1 일치. 테스트커버: `DatamartViewRebuildIT#재검수중_PENDING이면_V_COMPLETED_VIDEO에_미노출`(:188)·`#재승인시_다시_노출`(:207) |
| TC-MARTVIEW-002 | PARTIAL | [실동작] **선택 메커니즘은 정상** — `EXPORT_PATH_NM=/app/storage/raw/seed/4`(= `{dirname(원본 ./storage/raw/seed/clip-9101.mp4)}/{rawSn}` 절대경로 해석), rollback tx 로 `EXPORT_VER_NO=999` SUCCEEDED 를 추가하니 즉시 `/app/storage/raw/seed/4-V999`·`frame_cnt=99` 로 바뀌고 **행은 1행 유지**(LATERAL LIMIT 1, VER DESC 실효). **그러나 `FRAME_CNT` 값 의미가 프레임 수가 아니다** — 라이브 `FRAME_CNT=60` 인데 같은 rawSn 의 `V_COMPLETED_FRAME` 은 **30행**, 관제 통지 `image_count` 도 **30**. → **D-ISSUE-56** |
| TC-MARTVIEW-003 | PASS | [실동작] rollback tx 에서 rawSn=4 의 export 행 전량 DELETE → `EXPORT_PATH_NM`·`FRAME_CNT` 둘 다 NULL 이고 **영상 행은 1행 보존**. 테스트커버: `DatamartViewSlimIT#미export_영상은_EXPORT_PATH_NM이_null이어도_VIDEO에_노출된다`(:179) |
| TC-MARTVIEW-004 | PASS | [실동작] rollback tx 에서 `EXPORT_STTS_CD='SUCCEEDED'→'PARTIAL'` 로 바꾸자 `EXPORT_PATH_NM`·`FRAME_CNT` 가 **즉시 NULL**(행은 1행 보존) → SUCCEEDED 만 조인됨을 반증 방식으로 확인. [정적] V138:87 `ex.EXPORT_STTS_CD='SUCCEEDED'` |
| TC-MARTVIEW-005 | PASS | [실동작] rollback tx 에서 `LS_DATASET_VIDEO_META.ACTIVE_YN='N'` → `V_COMPLETED_VIDEO` **0행**, `'Y'` 복구 시 1행. [정적] V138:100. 테스트커버: `DatamartViewRebuildIT#V_COMPLETED_VIDEO_ACTIVE_Y만_노출`(:173) |
| TC-MARTVIEW-006 | PASS | [실동작] 4가지 조합 전수 확인(rollback tx, src_sn=1 대상). ①원본==비식별 동일경로로 세팅 → **해당 행만 제외**(30→ 대상행 미노출) ②비식별 `NULL` → **통과(노출 유지)** ③비식별 `'   '`(공백) → **통과** ④원본·비식별 둘 다 NULL → **통과**. 즉 동일경로만 fail-closed 이고 결측은 보존(증강 파생·RAW only 추출 소실 방지). 라이브 정상 데이터에서 `original_path` 30건 전부 `/frames/raw/4/`, `deidentified_path` 30건 전부 `/frames/deid/4/` 로 **동일경로 0건**(원본 덮어쓰기 0 확인). [정적] 라이브 viewdef 의 `NOT (src IS NOT NULL AND TRIM(src)<>'' AND deid IS NOT NULL AND TRIM(deid)<>'' AND deid=src)` |
| TC-MARTVIEW-007 | PASS | [실동작] rollback tx 에서 `FRM_EXPLN='설명테스트'` 세팅 → `DESCRIPTION` 컬럼에 그대로 노출. 컬럼 순서 `src_sn,raw_sn,frame_no,original_path,deidentified_path,captured_at,reg_dt,upd_dt,description`(끝 추가). 테스트커버: `CompletedFrameDescriptionIT`(:90,:114,:129,:144) |
| TC-MARTVIEW-008 | PASS | [실동작] `information_schema.columns` 실측 — `v_completed_label_change` 컬럼 = `lbl_hstry_sn,raw_sn,src_sn,add_cnt,mdfcn_cnt,del_cnt,reg_id,reg_dt` **8개뿐**. rollback tx 로 `CHG_DTL_CN='[{"secret":"coords"}]'` 를 심어도 뷰 어느 컬럼에도 나타나지 않음(좌표 본문 비노출, CWE-359). 테스트커버: `DatamartViewSlimIT#V_COMPLETED_LABEL_CHANGE에_라벨_좌표_본문이_노출되지_않는다`(:307) |
| TC-MARTVIEW-009 | PASS | [실동작] 라이브 `ls_data_lbl_hstry` 9행 중 `lbl_hstry_sn=3`(src_sn=34 → rawSn=6, 상태 `FAILED`)만 뷰에서 **제외**, rawSn=4(APPROVED) 이력 9건만 노출. [정적] `EXISTS(… s.DATA_STTS_CD='APPROVED')`. 테스트커버: `DatamartViewSlimIT#비APPROVED영상의_저장이벤트는_LABEL_CHANGE에_안나온다`(:358) |
| TC-MARTVIEW-010 | PASS | [실동작] `V_COMPLETED_META` 1행(meta_sn=19, `RVW_STTS_CD='APPROVED'`)만 노출. 같은 테이블의 `PENDING` 검수행 3건(meta_sn 20·27·28 / rawSn 5·6·7)은 전부 제외. [정적] 라이브 viewdef `mrev.rvw_stts_cd='APPROVED'` + `EXISTS(s.DATA_STTS_CD='APPROVED')` |
| TC-MARTVIEW-011 | PASS | [실동작] rawSn=4 는 `video.fps/codec/bit_rate/duration_ms/filesize/resolution` 6건 + VLM 시계열 1건(`meta_key='0-5'`)을 보유하는데 뷰에는 **VLM 1건만** 노출 — `video.*` 6건 전량 제외. [정적] `meta.meta_key !~~ 'video.%'`. 테스트커버: `DatamartViewRebuildIT#V_COMPLETED_META_video기술메타_제외_VLM시계열만`(:226) |
| TC-MARTVIEW-012 | PASS | [실동작] `SELECT 1 FROM v_completed_label` → `ERROR: relation "v_completed_label" does not exist`, `v_completed_label_attr` 동일. `pg_views` 실측 결과 뷰는 `v_completed_video / _frame / _meta / _label_change` **4종뿐**. 테스트커버: `DatamartViewSlimIT#V_COMPLETED_LABEL과_LABEL_ATTR_뷰가_제거되어_존재하지_않는다`(:147) |
| TC-MARTVIEW-013 | PASS | [실동작] rollback tx 안에서 `V138`·`V133`·`V139` SQL 원문을 **그대로 재실행** → 전부 `CREATE VIEW` 성공(에러 없음), 재실행 전후 컬럼 시그니처 md5 **완전 동일**(video `8b856512…`, frame `675c5692…`, label_change `1726195c…`). 컬럼 이름·순서·타입 보존 확인. 부팅 로그상 Flyway `Successfully applied 144 migrations … v146` |
| TC-MARTVIEW-014 | PARTIAL | [실동작] 구조적 불변식만 확인 — `uk_ls_dataset_video_meta_raw_active UNIQUE btree(raw_sn) WHERE active_yn='Y'` 부분 유니크 인덱스가 **활성 스냅샷 1건**을 DB 레벨에서 강제. 승인↔미승인 전이 시 뷰 노출/미노출 왕복도 확인(TC-MARTVIEW-001). [정적] 그러나 근거로 지정된 `DatasetMaterializeApproveRollbackIT` 는 실제로는 **"materialize 실패 시 approve 트랜잭션 롤백"** 시나리오(`materializeFailure_rollsBackApproveAcrossAllTables`, 파일 내 테스트 1개)이고 **"승인→materialize→(버전)rollback 후 뷰·스냅샷 정합"** 전용 IT 는 여전히 없다 → **D-ISSUE-57**(D-ISSUE-50 이월) |
| TC-MARTVIEW-015 | PASS | [실동작] 4각도 검증(rollback tx). ①라이브 `V_COMPLETED_VIDEO.DE_IDNTF_FILE_PATH_NM = /app/storage/raw/seed/4/deid/clip-9101-mask.mp4` 이고 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 적재값과 **바이트 단위 동일**(치환·조합 흔적 0 — 원본 파일명 `clip-9101.mp4` 에서 `-mask` 는 KPST 가 만든 값) ②더 최신 SUCCEEDED procLog 주입 → 즉시 그 값 채택(REQ_DT DESC) ③그 최신건을 `FAILED` 로 바꾸면 → 이전 SUCCEEDED 로 폴백 ④REQ_DT 동률로 맞추면 → `PROC_LOG_SN DESC` tiebreak 로 최신 SN 채택 ⑤procLog 전량 삭제 → 값 NULL 이지만 **영상 행 보존**. 항상 1행. 테스트커버: `DatamartViewSlimIT`(:195,:212,:235,:256) |
| TC-MARTVIEW-016 | PASS | [실동작] rollback tx 에서 `ORGNL_RAW_SN=99` + `RAW_FILE_PATH_NM=NULL` 로 파생 형상을 만들자 → `ORIGINAL_VIDEO_PATH` **NULL**, `ORGNL_RAW_SN=99` 노출, `DE_IDNTF_FILE_PATH_NM` 은 정상 값 유지, **행은 보존**(관제는 비식별 경로로 픽업 가능). [정적] `DatasetVideoMetaSnapshotService` `frozenRawFilePathNm = derivative ? null : src.getRawFilePathNm()` 및 그 위 주석(파생엔 원본 부재) — 동결 시점에 null 로 고정되므로 뷰가 자동 정합. ⚠ 관제 협의 대상 BREAKING 이라는 표기는 그대로 유효(계약 변경 사실 자체는 결함 아님) |
| TC-MARTVIEW-017 | PASS | [실동작] rollback tx 에서 `add/mdfcn/del = 0,0,0` 이력 1건 INSERT → 뷰에 **미노출**(0건), 같은 행의 `add_cnt` 를 1로 올리자 **즉시 노출**. 테이블 행은 그대로 존재(감사 근거 보존). [정적] `(COALESCE(add)+COALESCE(mdfcn)+COALESCE(del)) > 0`. 테스트커버: `DatamartViewSlimIT#변경_0건_이력은_…_노출되지_않는다`(:332) |
| TC-MARTVIEW-018 | PASS | [실동작] rollback tx 에서 APPROVED 영상의 `DE_IDENT_YN='F'`(신고 구간) 로 세팅 → `V_COMPLETED_VIDEO` 행 **유지**, `DE_IDNTF_YN='F'` 로 노출되고 `EXPORT_PATH_NM=/app/storage/raw/seed/4`·`DE_IDNTF_FILE_PATH_NM` 모두 **NULL 로 비워지지 않음**. `V_COMPLETED_FRAME` 도 행수 불변. 확정 정책대로 동작 — **결함 아님** |
| TC-MARTVIEW-019 | PASS | [실동작] rawSn=4 의 `LS_DATA_RAW.SHT_DT = 2026-07-31 02:56`(새벽·7월) 이라 구 파생 규칙이면 `NGT`/`SUMMER` 가 나와야 하는데, `LS_DATASET_VIDEO_META` 및 뷰의 `DAY_NGT_CD`/`SESN_CD`/`WTHR_NM` 이 **전부 NULL** — SHT_DT 기반 self-fill 없음이 실데이터로 확인됨. [정적] `DatasetVideoMetaSnapshotService` 의 `nullIfBlank(src.getDayNgtCd()/getSesnCd()/getWthrNm())` (공백→null 정규화) + 주석의 폐지 근거 |
| TC-MARTVIEW-020 | PASS | [실동작] `ENV_CORRECTION_PREDICATE` 를 read-only 로 실행해 4가지 반례를 전수 확인(rollback tx). ①스냅샷 `DAY_NGT_CD='NGT'` + 라이브 raw NULL → **대상 1건** ②날씨(`WTHR_NM`)만 값이 있는 경우 → **대상 0건**(날씨 제외 확인) ③역방향(raw non-null, 스냅샷 null) → **대상 0건** ④스냅샷 공백(`'  '`) → **대상 0건**(TRIM 정규화). [정적] 정정 1건 = `DatasetVideoMetaEnvCorrectionTx.correct()` 가 advisory lock → `materialize` 재동결 → `publishEvent(new TaskModifiedEvent(rawSn, null, META_UPDATED, null, **true**))`(regen=true) 를 한 트랜잭션에서 수행. 재동결 후 스냅샷값이 null 이 되어 판별식에서 빠지므로 재실행 no-op. 테스트커버: `DatasetVideoMetaEnvCorrectionIT`, `DatasetVideoMetaBackfillServiceIT` |
| TC-MARTVIEW-021 | PASS | [실동작] `GET /v1/dev/dataset-video-meta/shooting-env-correction-targets` — REVIEWER **200** `{"targetCount":0}` / WORKER **403** / PORTAL **403** / 무토큰 **401**. `POST …/shooting-env-corrections` — WORKER **403** / REVIEWER **200** `{"corrected":0,"remaining":0,"completed":true}`. `?dryRun=true` 를 붙여도 동작이 변하지 않음(쿼리파라미터 행위분기 미사용 — 조회/실행이 별도 sub-resource). [정적] `@Profile("!prd")` 로 prd 빈 미등록(404) — 로컬은 prd 로 못 띄우므로 이 절반은 정적 확인 |
| TC-MARTVIEW-022 | PASS | [정적] `DatasetVideoMetaBackfillService`: `while (processed < envCorrectionMaxPerRun)` 상한 루프 + `findShootingEnvCorrectionTargets(ENV_CORRECTION_BATCH_SIZE=100)` 페이징 + `attempted` Set 로 실패건 재시도 금지(무한루프 방지) + 시작 로그 `shooting-env correction started targets={} maxPerRun={}` / 잔여는 API 응답 `remaining` 및 로그로 노출. 상한 설정키 `authoring.dataset-video-meta.env-correction.max-per-run:200`(`application.yml:244`). [실동작] 현 환경 대상 0건이라 상한 절단 자체는 미발화(응답 `corrected=0 remaining=0 completed=true` 로 no-target 분기 확인) |
| TC-MARTVIEW-023 | PASS | [실동작] 두 반례 모두 0행 확인. ①`ACTIVE_YN='N'` 스냅샷(rollback tx) → `V_COMPLETED_VIDEO` **0행** ②`META_KEY='video.codec'` 행은 **라이브에 실제 존재**(meta_sn=3, rawSn=4)하는데 `V_COMPLETED_META` 에 **미노출**. 즉 D-ISSUE-49 가 지적한 "게이트가 데이터 부재로 검증 안 됨" 상태가 이번 회차에는 해소(반례 데이터 실재 + 0행 확인) |

**D-8 집계**: PASS 21 / FAIL 0 / PARTIAL 2 / BLOCKED 0 / 확인필요 0

---

## 총 집계 (31건)

| 판정 | 건수 |
|------|--:|
| PASS | **29** |
| FAIL | 0 |
| PARTIAL | **2** |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |

---

## 이슈

### [D-ISSUE-56] TC-MARTVIEW-002 — `V_COMPLETED_VIDEO.FRAME_CNT` 가 프레임 수가 아니라 "산출 파일 수(원본+비식별 합산)"라 관제 3개 계약값이 서로 어긋난다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 관제/데이터마트가 `V_COMPLETED_VIDEO` 1행을 UPSERT 할 때 `FRAME_CNT` 는 그 영상의 **프레임(이미지) 개수**를 뜻해야 한다. 같은 영상에 대해 ①`V_COMPLETED_FRAME` 행 수 ②관제 통지 `image_count` ③`FRAME_CNT` 세 값이 동일 단위여야 관제가 수신 건수 검증·누락 감지를 할 수 있다.
- **현재 동작(이슈 내용)**: `FRAME_CNT` 에 적재되는 값은 export writer 가 **원본 벌 + 비식별 벌 두 번 써서 합산한 파일 수**다.
  ```java
  // DatasetExportService.java:194-204
  ExportResult original = writer.write(rawSn, …, ExportKind.ORIGINAL, …);
  totalWritten += original.writtenCnt();
  ExportResult deidentified = writer.write(rawSn, …, ExportKind.DEIDENTIFIED, …);
  totalWritten += deidentified.writtenCnt();
  …
  txService.finalizeUnlessUnderDeidentReport(rawSn, inserted.exportSn(), totalWritten, false); // → markSucceeded(frameCnt=totalWritten)
  ```
  실측(rawSn=4, 프레임 30개):
  - `SELECT frame_cnt FROM v_completed_video WHERE raw_sn=4` → **60**
  - `SELECT count(*) FROM v_completed_frame WHERE raw_sn=4` → **30**
  - mock-server 수신 통지 로그 → `notify-completed accepted job_id=4 **image_count=30**` (`ControlNotifyPayloadFactory:83` = `srcRepository.countByRawSn`)

  추가로 **영상 종류에 따라 배수가 달라진다** — 파생영상(`originalAbsent`)은 ORIGINAL 벌을 쓰지 않으므로 `FRAME_CNT = N`(1배), 원본영상은 `2N`. 즉 같은 컬럼이 행마다 다른 단위를 갖는다. V138 마이그레이션 헤더에도 `FRAME_CNT` 의미에 대한 설명이 없다.
- **재현/확인 경로**:
  ```sql
  SELECT (SELECT frame_cnt FROM v_completed_video WHERE raw_sn=4)            AS view_frame_cnt,   -- 60
         (SELECT count(*)  FROM v_completed_frame WHERE raw_sn=4)            AS frame_view_rows,  -- 30
         (SELECT count(*)  FROM ls_data_src       WHERE raw_sn=4)            AS src_rows;         -- 30
  ```
  (통지값은 mock-server 로그 `[MOCK] notify-completed accepted job_id=4 image_count=30`)
- **영향**: 데이터정합. 관제가 `FRAME_CNT` 를 기대 프레임 수로 소비하면 원본영상은 항상 2배로 과대 계상되고, 통지 `image_count`(30)와 뷰 `FRAME_CNT`(60)가 어긋나 **누락/중복 감지 로직이 오작동**한다. 파생·원본 간 단위가 달라 관제가 배수 보정을 하드코딩할 수도 없다.
- **수정 방향(제안)**: 셋 중 하나 — ①`markSucceeded` 에 넘기는 값을 `prep.frames().size()`(프레임 수) 로 바꾸고 파일 수는 별도 컬럼/로그로 분리 ②컬럼을 `EXPORT_FILE_CNT` 로 개명(뷰는 `CREATE OR REPLACE` 로 끝 추가만 가능하므로 신규 컬럼 추가 + 기존 컬럼 유지) ③최소 조치로 V138 헤더·관제 연동규격서에 "FRAME_CNT = 산출 파일 수(원본벌+비식별벌), 파생영상은 1벌" 을 명문화하고 관제팀과 합의. **어느 쪽이든 관제팀 협의 필요**(현행 계약 변경).

### [D-ISSUE-57] TC-MARTVIEW-014 — "승인→materialize→rollback 후 뷰·스냅샷 정합" 전용 IT 부재 (D-ISSUE-50 이월·미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 라벨 버전 롤백(대상 스냅샷을 새 active 로 복원)이 일어난 뒤에도 `V_COMPLETED_VIDEO` 가 영상당 1행이고 활성 스냅샷이 정확히 1건임을, 회귀 테스트로 고정해야 한다. 롤백은 승인 이후에 실행되는 경로라 뷰 노출과 스냅샷 활성 플래그가 동시에 어긋날 여지가 있다.
- **현재 동작(이슈 내용)**: 케이스 근거로 지정된 `backend/src/test/java/kr/co/cudo/authoring/dataset/service/DatasetMaterializeApproveRollbackIT.java`(159줄)는 테스트 메서드가 1개이며 시나리오가 다르다.
  ```java
  @DisplayName("materialize_실패시_approve_실DB_전체롤백_상태_버전_통합메타_outbox_미반영")
  void materializeFailure_rollsBackApproveAcrossAllTables() { … }
  ```
  즉 "materialize 예외 → approve 트랜잭션 전체 롤백"을 검증할 뿐, **버전 롤백 후 뷰 정합**은 어느 테스트에서도 검증되지 않는다(`grep -rln "v_completed" backend/src/test` 결과 6개 파일 중 롤백 시나리오 없음).
- **재현/확인 경로**: `grep -n "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/dataset/service/DatasetMaterializeApproveRollbackIT.java` → 메서드 1건. `rg "rollback" backend/src/test/java/kr/co/cudo/authoring/dataset/view/` → 0건.
- **영향**: 기능/데이터정합 회귀 가드 부재. 현재 동작 자체는 DB 부분 유니크 인덱스(`uk_ls_dataset_video_meta_raw_active UNIQUE btree(raw_sn) WHERE active_yn='Y'`)로 "활성 1건" 이 강제되고 있어 즉시 결함은 아니지만, 롤백 경로가 바뀌면 조용히 깨진다.
- **수정 방향(제안)**: `dataset/view/` 에 IT 1건 추가 — 승인→스냅샷 활성화→라벨 버전 롤백→(a) `V_COMPLETED_VIDEO` 1행 유지 (b) `LS_DATASET_VIDEO_META` 활성 1건 (c) `V_COMPLETED_LABEL_CHANGE` 델타 0 행 미노출 을 단언. ⚠ 구현은 하지 않음.

### [D-ISSUE-58] TC-NOTIFY-031 — 컨테이너 단계 400 응답이 `ApiResponse` 래퍼가 아닌 Tomcat 기본 HTML
- **심각도**: LOW
- **기대 동작(기대효과)**: `rules/api-design.md` — 모든 API 응답은 `ApiResponse<T>`(`success/data/message/errorCode`) 래퍼. FE 가 `errorCode` 로 분기하므로 4xx 도 동일 포맷이어야 한다.
- **현재 동작(이슈 내용)**: 경로에 인코딩된 구분자가 들어간 요청은 Spring 진입 전 Tomcat 이 거부한다.
  ```
  $ curl -s -D- http://localhost:18081/api/v1/tasks/4%2F../4/summary
  HTTP/1.1 400
  Content-Type: text/html;charset=utf-8
  <!doctype html><html lang="en"><head><title>HTTP Status 400 – Bad Request</title>…
  ```
  (동일 상황에서 애플리케이션이 처리하는 400 은 정상적으로 `{"success":false,…,"errorCode":"INVALID_INPUT"}` 을 반환한다 — 예: `/v1/tasks/abc/summary`.)
- **재현/확인 경로**: 위 curl. 응답 헤더에 서버/프레임워크 **버전 정보는 없음**(`Server` 헤더 부재 — CWE-209 관점 유출 없음).
- **영향**: 기능(경미) — FE 가 `errorCode` 파싱에 실패해 일반 오류로 폴백. 보안 영향은 확인되지 않음(버전·경로·스택 미노출).
- **수정 방향(제안)**: `ErrorPageRegistrar` 또는 edge(nginx) 에서 4xx 기본 오류 페이지를 JSON 으로 통일. 전 엔드포인트 공통 이슈이므로 A(공통 인프라) 클러스터에서 일괄 처리 권장. ⚠ 구현은 하지 않음.

---

## 근거 드리프트 / 카탈로그 정합 메모 (결함 아님, 카탈로그 정정 후보)

| 케이스 | 카탈로그 근거 표기 | 실제 |
|---|---|---|
| TC-MARTVIEW-002 | `DatasetExportTxService.java:174-180` | 파일 실경로는 `dataset/export/DatasetExportTxService.java` 이고 `markSucceeded` 는 **:187-188**. `frameCnt` 산출부는 `DatasetExportService.java:194-232` |
| TC-MARTVIEW-014 | `DatasetMaterializeApproveRollbackIT` | 파일은 존재하나 시나리오 불일치(D-ISSUE-57 참조) |
| TC-MARTVIEW-006 | `V133:41-70` | V133 는 `ALTER TABLE` + 2개 뷰 재정의를 포함(라인 범위는 대략 일치, 판정 영향 없음) |

## 참고 관측 (이슈 아님)

- DB `now()` 는 UTC(`TimeZone=Etc/UTC`)인데 애플리케이션이 쓰는 `LocalDateTime.now()` 는 컨테이너 TZ(KST)라 저장 타임스탬프가 약 9시간 앞선다. `LS_DEIDENT_PROC_LOG.REQ_DT` 는 **엔티티에서 항상 명시 세팅**(`LsDeidentProcLog.java:127`)되므로 `REQ_DT DESC` 정렬은 앱 경로 내에서 일관적이며, 이번 검증에서 정합 문제는 관측되지 않았다. (DB DEFAULT `CURRENT_TIMESTAMP` 로 들어오는 경로가 새로 생기면 정렬이 뒤집힐 수 있으니 참고만.)
- 검증 중 다른 병렬 에이전트가 rawSn=4 를 재검수 흐름에 다시 태워 `APPROVED→IN_REVIEW`, export 버전 1→4 누적 등 상태가 계속 변했다. 본 파일의 수치는 관측 시점 기준이며, 게이트 판정은 전부 rollback 트랜잭션 반례로 재확인했다.
