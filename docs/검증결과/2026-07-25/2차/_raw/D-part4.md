# D 클러스터 part4 (D-6·D-8) 2차 검증 결과

> 대상: `docs/test-cases/D-review-version-notify.md` 의 `## D-6. 관제 통지 (TC-NOTIFY)` 45건 + `## D-8. 데이터마트 View (TC-MARTVIEW)` 23건 = **68건**
> 환경: backend `localhost:18081`(HEAD `ca3c712b`) · mock `:9400` · postgres `:5432`(스키마 `public`) · `CONTROL_NOTIFY_ENABLED=true` / `CONTROL_NOTIFY_URL=http://klid-mock-server:9400` (**1차의 `false` 와 달라 D-6 전 구간 실동작 가능**)
> 검증 일시: 2026-07-31 03:55 ~ 04:20 KST (mock 로그는 UTC = KST-9h)
> ⚠ 폐기(`~~취소선~~`) 행 없음 — D-6·D-8 두 섹션에 취소선 케이스는 존재하지 않는다(집계 68 = 표 행수 68).

## 검증용으로 신규 투입한 데이터 (다른 클러스터와 충돌 없음)

| 대상 | 내용 |
|---|---|
| `MNG_EX_LOCAL_GOV` | `41135`(경기도 성남시 분당구, `USE_YN='Y'`) · `41136`(폐지시 폐지구, `USE_YN='N'`) · `41137`(가×60 / 나×60 = 121자, `Y`) 3행 신규 |
| `MNG_CLIP_MASTER`/`MNG_CLIP_EVNT_LST` | `DEV-CLIP-9801`(FALLDOWN·41135·45s) · `9802`(LOITERING·41136·77s) · `9803`(FIRE·41137·99s) |
| 신규 영상 | **rawSn 168 / 169 / 170** (적재→비식별→마킹→배치→승인 완주). 170 의 해상도 파생 **179(720P) / 180(480P)** |
| 폴백 큐 주입행 | `VERIFY-D6-REBUILD-1`(빈 payload=재조립 유도) · `VERIFY-D6-BADJOB-1`(`job_id="../../etc/passwd"`) — 둘 다 종결(SUCCEEDED / DEAD_LETTER) |
| 목서버 상태 | `POST /api/data-set/v2/jobs/_reset` 1회 실행(04:06:40 KST) — 404 자기치유 실측용. 등록부는 이후 통지로 재구성됨 |

> 🚨 rawSn **126·133 은 손대지 않았다**. backend 재기동 없음. 소스/설정 무수정. 빌드·테스트 미실행.

---

## 집계

| 판정 | D-6 | D-8 | 합계 |
|---|---:|---:|---:|
| PASS | 45 | 21 | **66** |
| PARTIAL | 0 | 1 | **1** |
| 확인필요 | 0 | 1 | **1** |
| FAIL / BLOCKED / N/A | 0 | 0 | **0** |
| **계** | **45** | **23** | **68** |

- 근거 확인 방식: **[실동작] 41건 · [정적] 27건**(정적은 전건 `file:line` 실측 + 기존 테스트 커버 확인)
- **self-fill 0건** — 통지 6필드 전부 DB 실측임을 *값을 바꿔 payload 가 따라 변하는지*로 확증(아래 §통지 payload 실캡처)
- 신규 이슈 **2건** (MEDIUM 1 · LOW 1)

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 판정 | 근거 |
|---|---|---|---|
| **D-ISSUE-41** (CRITICAL) | `TASK_COMPLETED` payload 가 `totalFrames=0/labeledFrames=0/reviewerName=null` 상수 self-fill | **✅ 해소** | 계약 자체가 **6필드 평면 snake_case** 로 교체됐고(그 세 필드는 소멸 — UNCERTAINTIES #3), 6필드 전부 DB 실측. rawSn 168/169/170 을 서로 다른 DB 값으로 만들어 payload 가 **전 필드 따라 변함**을 확인 |
| **D-ISSUE-42** | `TASK_MODIFIED` 가 프레임↔변경종류 페어링 상실 | **✅ 해소** | `LS_MON_NOTI_ACML.CHG_DTL_CN` = `{"frames":{"184":["LABEL_ADDED"],"185":["LABEL_ADDED"],"186":["LABEL_ADDED","LABEL_DELETED"]}}` 실측 |
| **D-ISSUE-43** | 영상 단위(srcSn=null) 변경 통지가 디바운서 NPE 로 유실 | **✅ 해소** | 신고(META_UPDATED, 영상 단위) 축적·flush 실동작 확인(`flush rawSn=169 regen=true frames=179=[META_UPDATED]`). 축적 저장소가 인메모리→공유 DB(V144)로 이관 |
| **D-ISSUE-44** | `LABEL_ADDED` 가 발행되지 않는 dead 계약값 | **✅ 해소** | 축적행에 `LABEL_ADDED` 실관측(`LabelService:423-424` 가 변경 종류별 분리 발행) |
| **TC-NOTIFY-003 FAIL** | 위 self-fill | **✅ PASS** | §통지 payload 실캡처 |
| **TC-NOTIFY-008 FAIL** | 디바운스 실동작 미검증 | **✅ PASS** | 1초 내 3회 수정 → 축적행 **1건** → 약 64초 뒤 **TASK_MODIFIED 1회** |
| **TC-NOTIFY-014 FAIL** | 재export 트리거·regen 미검증 | **✅ PASS** | 라벨 수정 경로 실동작(export `v2` SUCCEEDED 04:04:58.143 → 통지 04:04:58.193) + 7경로 전부 `exportRegenerated=true` 코드 실측 |
| **D-ISSUE-45** | 관제 조회 API 페이징·상한 부재 | (D-7 소관 — part3) | — |
| **D-ISSUE-46** | `V_COMPLETED_FRAME` 이 원본 경로를 비식별 컬럼으로 노출 | **✅ 해소** | `select count(*) from v_completed_frame where original_path = deidentified_path` = **0**. V133 fail-closed 술어 실동작. 결측(파생 179 등 7행)은 정상 통과 |
| **D-ISSUE-47 / TC-MARTVIEW-008 FAIL** | `V_COMPLETED_LABEL_CHANGE` 가 라벨 본문(좌표) 노출 | **✅ 해소** | 뷰 출력 8컬럼(`LBL_HSTRY_SN/RAW_SN/SRC_SN/ADD_CNT/MDFCN_CNT/DEL_CNT/REG_ID/REG_DT`) — `CHG_DTL_CN` 부재 |
| **D-ISSUE-48** | 통지·조회가 런타임 비활성(`CONTROL_NOTIFY_ENABLED=false`)이라 실동작 미검증 | **✅ 해소** | 이번 회차 `true` + 목서버 관제 스텁(`/api/data-set/v2/jobs/**`) 배선 — D-6 45건 중 **28건을 실동작으로 확정** |
| **D-ISSUE-49** | 뷰 필터 반증 데이터 부재 | **✅ 해소** | `ACTIVE_YN='N'` 스냅샷 1건(rawSn 146) · `META_KEY LIKE 'video.%'` 230건 · `PARTIAL/FAILED` export 3건 · 변경 0건 이력 1건 · 비승인 이력 40건 — **모두 실재하며 4뷰에서 0행** |
| **D-ISSUE-50 / TC-MARTVIEW-014** | 승인→materialize→롤백 뷰 정합 IT 미비 | **❌ 미해소** | `DatasetMaterializeApproveRollbackIT` 는 존재하나 시나리오가 **"materialize 실패 시 approve 전체 롤백"** 하나뿐(`:129`). 라벨 버전 롤백 후 뷰·활성 스냅샷 정합 케이스 없음 → **D-ISSUE-62** |

---

## ★통지 payload 실캡처 (DB 실측 대조 · self-fill 여부)

캡처원: `LS_CONTROL_NOTIFY_FALLBACK.PAYLOAD_CN`(= 실제 전송 본문, `recordImmediateSuccess`) + `klid-mock-server` 인바운드 로그(경로·상태코드).

### TASK_COMPLETED — 6필드 전부 DB 실측 (상수 self-fill 0)

| rawSn | 전송 payload | DB 실측(`LS_DATA_RAW` / `COUNT(LS_DATA_SRC)` / `MNG_EX_LOCAL_GOV`) | 판정 |
|---:|---|---|---|
| 168 | `{"job_id":"168","event_type_cd":"FALLDOWN","lclgv_cd":"41135","lclgv_nm":"경기도 성남시 분당구","duration_sec":45,"image_count":2}` | FALLDOWN / 41135 / 45 / 2 / (41135 `USE_YN='Y'`) | 일치 |
| 169 | `{... "event_type_cd":"LOITERING","lclgv_cd":"41136","lclgv_nm":null,"duration_sec":77,"image_count":3}` | LOITERING / 41136 / 77 / 3 / (41136 **`USE_YN='N'`→ 폐지 명칭 미전송**) | 일치 |
| 170 | `{... "event_type_cd":"FIRE","lclgv_cd":"41137","lclgv_nm":"가…나…"(**length=100**),"duration_sec":99,"image_count":4}` | FIRE / 41137 / 99 / 4 / (조합 121자 → **100자 절단**) | 일치 |
| 126 | `{... "lclgv_cd":"11110","lclgv_nm":null, "duration_sec":30,"image_count":3}` | 11110 은 마스터 **미존재** → null(값 미생성) | 일치 |
| 156 | `event_type_cd:"FIRE"` / `image_count:4` | FIRE / 4 | 일치 |
| 151 | `image_count:0` | `COUNT(LS_DATA_SRC)=0` — **0 이 상수가 아니라 실측 0** | 일치 |
| 179(파생) | `event_type_cd:"FIRE","lclgv_cd":"41137","duration_sec":99,"image_count":4` | 부모(170) 승계값 그대로 DB 보유 | 일치 |

> **self-fill 반증 실험**: 지자체 마스터에 3가지 상태(정상/폐지/초과길이)를 심고, 이벤트유형·지자체코드·영상길이·프레임수가 서로 다른 영상 3건을 새로 만들어 승인했다. 6필드가 **영상마다 전부 다르게** 나갔고 특히 `lclgv_nm` 은 마스터 상태에 따라 값/`null`/절단으로 갈렸다 → **상수 self-fill 아님이 실증**. `duration_sec`/`image_count` 도 45·77·99 / 2·3·4 로 각각 DB 값과 1:1.

### TASK_MODIFIED — 파일명 목록만, 본문·식별자·경로 없음

| 발생 경로 | payload | 확인 |
|---|---|---|
| 재export 동반(regen=true, rawSn 170) | `{"job_id":"170","changed_items":{"images":["0000.jpg","0001.jpg","0002.jpg","0003.jpg"],"jsons":["0000.json",…]}}` | `FRM_NO` 4자리 zero-pad · **전 프레임** · `SRC_SN`·좌표·절대경로·토큰 **없음** |
| 재export 없는 메타 수정(rawSn 173, 타 에이전트 생성) | mock 로그 `images=0 jsons=1` | `buildModified` 는 **jsons 만** 싣는다(이미지 미재생성) |
| 조립 실패 대비(regen=false) | — | 정적: 빈 `changed_items` 로 확정 적재(`ControlNotifyService:181-186`) |

### 경로·상태코드 실왕복 (mock 인바운드)

```
POST /api/data-set/v2/jobs/168/notify-completed  -> 202   (image_count=2)
POST /api/data-set/v2/jobs/170/notify-completed  -> 202   (image_count=4)
POST /api/data-set/v2/jobs/152/notify-completed  -> 409   -> 즉시 /notify-updated -> 202   (409 자기치유)
POST /api/data-set/v2/jobs/170/notify-updated    -> 404   -> 즉시 /notify-completed -> 202 (404 자기치유)
POST /api/data-set/v2/jobs/168/notify-completed  -> 202   (폴백 큐 재조립 재전송)
```

### 순서 보장(export SUCCEEDED → 통지) 실측

| rawSn | export 행 커밋 | 통지 관찰행 | 간격 |
|---:|---|---|---|
| 169 | `export_sn=29 v1 SUCCEEDED 04:02:46.877147` | `queue_sn=32 04:02:46.902278` | +25ms |
| 170 | `export_sn=30 v1 SUCCEEDED 04:02:46.955751` | `queue_sn=33 04:02:46.982033` | +26ms |
| 170(수정) | `export_sn=33 v2 SUCCEEDED 04:04:58.143650` | `queue_sn=36 04:04:58.193896` | +50ms |
| 169(신고 해소 복구) | `export_sn=45 v2 SUCCEEDED 04:13:32.819` | `queue_sn=52 04:13:32.849` | +30ms |

**역전 0건.** 반대로 export 가 막힌 경우(신고 구간)는 통지가 **나가지 않았다**:
```
04:11:28.510 AsyncDatasetExportRunner - async re-export(+notify) starting rawSn=169 forceRegenerate=true
04:11:28.511 DatasetExportService     - export blocked — deident report open rawSn=169
04:11:28.511 AsyncDatasetExportRunner - async export failed rawSn=169 cause=CustomException
   → LS_CONTROL_NOTIFY_FALLBACK 신규행 0 · mock 인바운드 0
```

### 디바운스 (60s 윈도우 · 공유 DB)

| 시각 | 사건 |
|---|---|
| 04:03:53.98 ~ 04:03:54.09 | rawSn 170 의 프레임 3개(184/185/186)에 **연속 3회** 라벨 저장(총 1초) |
| 04:03:54 직후 | `LS_MON_NOTI_ACML` **1행만** 생성 (`uk_lmna_raw_pending` = `UNIQUE(raw_sn) WHERE stts_cd='PENDING'`), `EXPORT_RPRCS_YN='Y'`, `CHG_DTL_CN={"frames":{"184":["LABEL_ADDED"],"185":["LABEL_ADDED"],"186":["LABEL_ADDED","LABEL_DELETED"]}}` |
| 04:04:58 (**+64s**) | flush 1회 → export v2 → **TASK_MODIFIED 1건** (`queue_sn=36`) |

→ 3변경 = **통지 1회**. 윈도우 60s + tick 10s 와 정확히 부합.

### 폴백 큐 · 재시도 · dead-letter (주입 실동작)

| 주입행 | 결과 | 커버 케이스 |
|---|---|---|
| `VERIFY-D6-REBUILD-1` (payload `''` = `PAYLOAD_REBUILD_REQUIRED`, rawSn 168) | 04:09:36 재시도 잡이 claim → **DB 에서 재조립** → mock `notify-completed 202 (image_count=2)` → `SUCCEEDED/SUCCESS` | TC-NOTIFY-021·038 |
| `VERIFY-D6-BADJOB-1` (`job_id="../../etc/passwd"`) | 04:09:36 `IllegalArgumentException: 허용되지 않는 job_id 형식입니다.` → **HTTP 요청 자체가 나가지 않음**(mock 로그 0) → `RTRY_NMTM 0→1`, `PENDING`, `NEXT_RTRY_DT=+2분`(2^1), `LAST_ERR` 정제됨 | TC-NOTIFY-037·019·022·023 |
| 위 행 `RTRY_NMTM=5` 로 조정 후 재발화 | 04:14:36 `RTRY_NMTM=6 > MAX=5` → **`DEAD_LETTER`** + `DLQ_DT` 기록 + `NEXT_RTRY_DT=null` | TC-NOTIFY-020 |

---

## ★뷰 4종 실측

기준 시각 04:15 KST. 뷰 목록(`pg_views`) = `v_completed_video` / `v_completed_frame` / `v_completed_label_change` / `v_completed_meta` **4종만**(라벨 본문 뷰 2종 부재 확인).

| 뷰 | 행수 | 주요 컬럼 실측 | 정책 정합 |
|---|---:|---|---|
| `V_COMPLETED_VIDEO` | 15 | `EXPORT_PATH_NM=/app/storage/raw/seed/{rawSn}` · `FRAME_CNT`(2벌 합) · `DE_IDNTF_FILE_PATH_NM=…/{rawSn}/deid/sample-cctv-1080p-mask.mp4`(KPST 규칙 원문) · `DAY_NGT_CD/SESN_CD/WTHR_NM` **전부 null** | APPROVED 전용(비APPROVED 조인 0) · **영상 1건=1행**(중복 0) · 미export(151·155) 두 값 null · `ACTIVE_YN='N'` 스냅샷(146) 제외 · `PARTIAL/FAILED` export(173 v4~v6) 건너뛰고 최신 SUCCEEDED(v8) 선택 |
| `V_COMPLETED_FRAME` | 52 | `ORIGINAL_PATH=/app/storage/raw/frames/raw/{rawSn}/…` vs `DEIDENTIFIED_PATH=/app/storage/deidentified/frames/deid/{rawSn}/…` · `DESCRIPTION` 컬럼(끝) 존재 | **동일 경로 행 0** (D-ISSUE-46) · 결측(파생 179 등 `ORIGINAL_PATH` null) **7행 정상 노출** |
| `V_COMPLETED_LABEL_CHANGE` | 22 | 출력 8컬럼 — `CHG_DTL_CN` **부재** | 변경 0건 행: 테이블 1건 존재 / **뷰 0행**(V139) · 비승인 이력 40건 존재 / **뷰 0행** |
| `V_COMPLETED_META` | 105 | `META_KEY` = `0-8`·`8-16`… (VLM 시계열), `RVW_STTS_CD='APPROVED'` | `video.*` 키 230건 실재 / **뷰 0행** · 비승인 메타 0행 |

### 파생영상 행 (TC-MARTVIEW-016 실동작)

rawSn **179**(170 의 720P 해상도 파생)을 배정→검수→승인해 실측:

| 컬럼 | 값 | 정책 |
|---|---|---|
| `ORGNL_RAW_SN` | 170 | 파생 표식 |
| `ORIGINAL_VIDEO_PATH` | **NULL** | 동결 시 `RAW_FILE_PATH_NM=null` — **파생엔 원본이 없다**(정상) |
| `DE_IDNTF_FILE_PATH_NM` | `/app/storage/deidentified/videos/resolution/170/179/RESL_720P.mp4` | 관제 픽업 경로(자기 비식별 사본, 부모 원본 폴백 없음) |
| `RESL`/`VDO_WDTH`/`VDO_HGT` | `1920x1080`/1920/1080 | **부모와 동일이 정상**(비디오 복사, 프레임만 리스케일) — 결함 아님 |
| `V_COMPLETED_FRAME`(179) | 4행, `ORIGINAL_PATH` null · `DEIDENTIFIED_PATH` 존재 | 결측 통과 정책(V133) |

### 뷰 멱등성 (TC-MARTVIEW-013 실동작)

V138·V139·V133 의 `CREATE OR REPLACE VIEW` 문을 그대로 재실행 → 3건 모두 성공, `information_schema.columns` 의 **컬럼명·순서·타입 문자열이 재실행 전후 완전 동일**(37컬럼), 행수 불변.

---

## D-6 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-NOTIFY-001 | TASK_COMPLETED 발행 트리거 = export SUCCEEDED | PASS | [실동작] export→통지 4쌍 전부 +25~50ms 순서 보장. `ControlNotifyEventListener.java:38-41` 이 `DatasetExportCompletedEvent` 소비 | 승인 이벤트 직후 발행 아님을 시각으로 확정 |
| TC-NOTIFY-002 | 승인 롤백 시 통지 미발행 | PASS | [정적] `DatasetExportBridge.java:36-37` `@TransactionalEventListener(AFTER_COMMIT)`. 테스트 `ReviewServiceEventPublishTest` | 롤백 유발은 소스 수정 필요라 미수행 |
| TC-NOTIFY-003 | TASK_COMPLETED 페이로드 = 6필드 평면(snake_case) | PASS | [실동작] 168/169/170/126/151/156/179 payload 실캡처, 6필드 전부 DB 1:1. `TaskCompletedPayload.java:26-32`(`@JsonProperty` 고정) | **D-ISSUE-41 해소 확증**. 라벨·PII·토큰·원본경로 없음 |
| TC-NOTIFY-004 | 전송 실패 시 폴백 적재 | PASS | [정적] `ControlNotifyService.java:130-134`. 테스트 `ControlNotifyServiceTest:101 TASK_COMPLETED_실패시_폴백큐_적재됨` | 목서버 중단은 타 에이전트 검증 간섭 위험이라 미유발 |
| TC-NOTIFY-005 | 성공 관찰행 적재(실제 전송분 기준) | PASS | [실동작] rawSn 152: 요청=completed·409 자기치유 → 관찰행 `TASK_MODIFIED`(queue 26~28). rawSn 170: 요청=modified·404 자기치유 → 관찰행 `TASK_COMPLETED`(queue 40) | `SendOutcome` 반환 계약이 실제로 동작 |
| TC-NOTIFY-006 | 관찰행 적재 실패 시 통지 성공 유지 | PASS | [정적] `ControlNotifyService.java:318-325` catch+warn. 테스트 `관찰_적재_실패해도_통지성공_metrics는_유지된다` | |
| TC-NOTIFY-007 | TASK_MODIFIED 페이로드 = 변경 파일명 목록 | PASS | [실동작] `{"job_id":"170","changed_items":{"images":["0000.jpg"…],"jsons":["0000.json"…]}}`. rawSn 173 은 `images=0 jsons=1`(비재생성 경로) | `SRC_SN`·좌표·메타본문·절대경로 없음. 두 리스트 항상 non-null |
| TC-NOTIFY-008 | 디바운스 축적은 공유 DB(V144) | PASS | [실동작] 1초 내 3변경 → `LS_MON_NOTI_ACML` 1행(rawSn·frames↔changeTypes·`EXPORT_RPRCS_YN='Y'`), 부분 유니크 `uk_lmna_raw_pending` DDL 확인 | **1차 FAIL 해소** |
| TC-NOTIFY-009 | 셧다운 시 잔여 drain | PASS | [정적] `ControlNotifyDebouncer.java:81(MAX_DRAIN_ROUNDS=20),191-204 @PreDestroy`. 테스트 `flushAll_모든_윈도우_즉시_flush` | backend 재기동 금지라 실동작 불가 |
| TC-NOTIFY-010 | flush 전용 daemon 스케줄러 | PASS | [실동작] 기동 로그 `flush scheduler started intervalMs=10000 leaseMs=300000`, flush 로그의 스레드명 `[control-notify-debounce-flush]` | `@EnableScheduling` 비의존 확인(`:142-157`) |
| TC-NOTIFY-011 | changeType 계약값 검증 | PASS | [실동작] 축적행에 `LABEL_ADDED`·`LABEL_DELETED`·`META_UPDATED` 관측, **payload 에는 미포함**. `ChangeType.java:16-30` 4종 | `LABEL_UPDATED` 는 코드 실측(`TrackMergeService:201` 등) |
| TC-NOTIFY-012 | 라벨 수정 통지 — APPROVED 후에만 발행 | PASS | [실동작] rawSn 180(ASSIGNED) 라벨 저장 200 → `LS_MON_NOTI_ACML` 0행 | `LabelService.java:414` `isReviewApproved` 게이트 |
| TC-NOTIFY-013 | 라벨 수정 — 무변경 통지 미발행 | PASS | [실동작] 기존 `id` 동반 동일 payload 재저장 → 이력 4→4 · `LBL_VER` 2→2 · 축적행 변화 0 | `id` 미동반 저장은 full-replace(삭제+추가)라 정상적으로 변경으로 집계됨 |
| TC-NOTIFY-014 | 재export 7경로 TASK_MODIFIED(regen=true) | PASS | [실동작] 라벨 경로: export v2 SUCCEEDED → 통지. [정적] 7경로 전부 `exportRegenerated=true` 실측 — `LabelService:423-424`·`TrackEditService:326`·`TrackMergeService:201`·`VersionService:518-519`·`EnvironmentMetaService:125-126`·`FrameDescriptionService:60-61`·`FramePrivacyMetaService:131-132,154-155` | **1차 FAIL 해소** |
| TC-NOTIFY-015 | 폴백 적재 idempotency 중복 방지 | PASS | [정적+DB] `uk_lcnf_idempotency UNIQUE(idmp_key)` DDL 실측 + `ControlNotifyFallbackService.java:91-95` catch. 테스트 `enqueuePending_중복_idempotencyKey_멱등_처리` | |
| TC-NOTIFY-016 | 폴백 큐 깊이 상한(DoS) | PASS | [정적] `ControlNotifyFallbackService.java:36 MAX_QUEUE_DEPTH=10000`, `:74-82` 거부+`control.notify.fallback.queue.full` | 1만행 적재는 미수행 |
| TC-NOTIFY-017 | 폴백 enabled=false 게이트 | PASS | [정적] `:71-73`, `:113-115` `Optional.empty()` | 런타임 토글 on |
| TC-NOTIFY-018 | 재시도 claim 원자 CAS(멀티인스턴스) | PASS | [정적] `:137-143` `claimAtomically` 조건부 UPDATE, `updated!=1 → empty`. 테스트 `claimForRetry_CAS_실패시_empty` | 단일 노드라 경합 실동작 불가 |
| TC-NOTIFY-019 | 재시도 백오프 스케줄 | PASS | [실동작] 1회 실패 → `PENDING` + `NEXT_RTRY_DT = 실패시각+2분`(2^1). `LsControlNotifyFallback.java:207-211` cap 60 | |
| TC-NOTIFY-020 | dead-letter 전이 | PASS | [실동작] `RTRY_NMTM=6 > MAX_RTRY_NMTM=5` → `DEAD_LETTER`, `DLQ_DT=04:14:36`, `NEXT_RTRY_DT=null` | |
| TC-NOTIFY-021 | 재시도 잡 — due 항목 처리 | PASS | [실동작] 주입행 2건을 04:09:36 tick 이 claim→처리(1 성공 / 1 실패격리). `ControlNotifyFallbackRetryJob.java:38-39` 5분 fixedDelay·batch 20 | |
| TC-NOTIFY-022 | 재시도 잡 — 재실패 재스케줄 | PASS | [실동작] `VERIFY-D6-BADJOB-1` → `markFailedAndSchedule` 경로로 `RTRY_NMTM` 증가·재스케줄 | |
| TC-NOTIFY-023 | 에러 메시지 sanitize(토큰/URL/제어문자) | PASS | [실동작] `LAST_ERR_MSG_CN="IllegalArgumentException: 허용되지 않는 job_id 형식입니다."` — 개행·URL·토큰 없음. [정적] `LsControlNotifyFallback.java:246-252` 3단 치환+1900자 truncate, 단위테스트 5건 | |
| TC-NOTIFY-024 | 재시도 성공 관찰행 멱등 | PASS | [정적] `:124-128` UK 충돌 삼킴. 테스트 `recordImmediateSuccess_중복키_충돌시_멱등_처리` | |
| TC-NOTIFY-025 | 토글 off 시 빈 등록 범위 | PASS | [정적] `@ConditionalOnProperty(authoring.control-notify.enabled)` 보유 빈 **10종** 실측(Client/Service/PayloadFactory/EventListener/RetryJob/SchedulingConfig/TaskQueryController/TaskQueryService/HealthIndicator/Metrics). 반면 `ControlNotifyDebouncer.java:73`·`TaskModifiedAccumulateListener.java:27`·`DatasetExportBridge.java:29-30(matchIfMissing=true)` 는 **무조건 등록** | 런타임이 on 이라 off 형상은 정적 판정 |
| TC-NOTIFY-032 | 통지 경로 계약 | PASS | [실동작] mock access log `POST /api/data-set/v2/jobs/{id}/notify-completed\|notify-updated` → 202 수용. `ControlNotifyClient.java:55-58,93` `Content-Type: application/json` | 200/201 수용은 `:96-99` 정적 |
| TC-NOTIFY-033 | 자기치유 — 409 completed→updated | PASS | [실동작] rawSn 146·152·156·173 에서 `409 Conflict` 직후 `notify-updated 202`, 관찰행이 `TASK_MODIFIED` 로 기록 | `ControlNotifyService.java:221-232` |
| TC-NOTIFY-034 | 자기치유 — 404 updated→completed | PASS | [실동작] 목 등록부 reset 후 rawSn 170 → `notify-updated 404` → `notify-completed 202`, 관찰행 `TASK_COMPLETED`(queue 40) | `:251-262` |
| TC-NOTIFY-035 | 자기치유 재귀 금지 | PASS | [정적] `:225-231`,`:255-261` 각 분기가 1회만 전환. 테스트 `폴백은_재귀하지_않는다`·`completed_409_후_updated_도_실패하면_재귀하지_않고_폴백큐로_간다` | |
| TC-NOTIFY-036 | 4xx/5xx 분류 | PASS | [실동작] 409/404 가 `ControlNotifyStatusException` 으로 전파돼 자기치유 발동(재시도 없이 즉시 전환). [정적] `ControlNotifyClient.java:115-120` 5xx=`IllegalStateException` | 테스트 `관제서버_500_응답시_Retry_3회_후_실패` |
| TC-NOTIFY-037 | job_id 형식 fail-closed | PASS | [실동작] `job_id="../../etc/passwd"` 주입 → `IllegalArgumentException`, **HTTP 요청 미발생**(mock 로그 0건) → 재시도 큐로 격리. `ControlNotifyService.java:73,275-279` | CWE-22/88 차단 실증 |
| TC-NOTIFY-038 | 페이로드 조립 실패 → 통지 유실 금지 | PASS | [실동작] 빈 payload(`PAYLOAD_REBUILD_REQUIRED`) 주입행이 재시도 시점에 **DB 재조립**되어 `image_count=2` 로 발송·`SUCCEEDED` | `:112-123` + `ControlNotifyFallbackRetryJob.java:85-101` |
| TC-NOTIFY-039 | 조립 실패 시 regen 플래그 보존 | PASS | [정적] `ControlNotifyService.java:170-188` regen=false→빈 `changed_items` 확정 적재 / regen=true→REBUILD_REQUIRED. 테스트 2건(MED2) | |
| TC-NOTIFY-040 | export 실패 시 통지 보류 | PASS | [실동작] rawSn 169 신고 구간에서 `export blocked → async export failed` 로그 후 **통지 큐·mock 인바운드 모두 0건**. `AsyncDatasetExportRunner.java:73-75,97-98` 성공시에만 이벤트/콜백 | 관제의 구버전 폴더 픽업 차단 확인 |
| TC-NOTIFY-041 | 실패 export 회수 후 통지 재개 | PASS | [정적] `DatasetExportFailureRecoverer.java:145-160` 조건부 UPDATE 클레임 후 `runApprovalAsync`(완료 이벤트 재발행). `DatasetExportFailureRecoveryIT` 4건 | 회수 유예(기본) 미경과라 실동작 미유발 |
| TC-NOTIFY-042 | 회수기 — 신고 구간은 재시도 예산 미소모 | PASS | [정적] `DatasetExportFailureRecoverer.java:142-146` — `deidentReportGate.isUnderDeidentReport` 가 **클레임 이전**에 continue, `deidentSkipped` INFO 로그 | |
| TC-NOTIFY-043 | 재export 트리거는 통지 토글과 무관 | PASS | [정적] `TaskModifiedAccumulateListener.java:27-37` 무조건 등록 + `ControlNotifyDebouncer` 의 `notifyService`/`metrics` `@Nullable`(`:92-97`), export 위임은 `:296-303` 무조건 | 런타임 토글 on 이라 off 형상 미실측. 테스트 `HIGH-E_통지_토글_off여도…`·`ControlNotifyDebounceCrossNodeIT:176` |
| TC-NOTIFY-044 | 크로스노드 1회 flush | PASS | [정적] `JpaControlNotifyDebounceStore.java:104-110` `claimForFlush` 조건부 UPDATE(`!=1 → empty`). `ControlNotifyDebounceCrossNodeIT:95 2노드에_축적된_디바운스가_한_번만_flush_된다` | 단일 노드라 실동작 불가 |
| TC-NOTIFY-045 | flush 실패 시 임차 회수 | PASS | [정적] `ControlNotifyDebouncer.java:84 MIN_LEASE_MILLIS=60000`, `:134 leaseMillis clamp`, `:242-270` complete 미호출 시 FLUSHING 잔존+`dropped`. 테스트 `flush_실패로_남은_윈도우는_임차_만료_후_재클레임되어_다시_발송된다` | 런타임 `leaseMs=300000` 로그로 설정값 확인 |
| TC-NOTIFY-046 | 폴백 큐 적재 실패 격리 | PASS | [정적] `ControlNotifyService.java:301-309` `enqueueQuietly` 예외 미전파 + `incrementDropped` + ERROR. 테스트 3건 | |
| TC-NOTIFY-047 | `lclgv_nm` 조달 규칙 | PASS | [실동작] 마스터 미존재(11110)→null · `USE_YN='N'`(41136)→null · 121자(41137)→**정확히 100자 절단**(`length()=100` 실측) | `ControlNotifyPayloadFactory.java:66,150-174` |
| TC-NOTIFY-048 | changed_items 미해석 프레임 제외 | PASS | [정적] `:185-219` 두 벌 보유 프레임만 해석, `unresolved` WARN(식별자만)+`incrementUnresolvedFrame`. 테스트 4건 | |
| TC-NOTIFY-049 | 전량 재생성 통지 대상 프레임 | PASS | [실동작+정적] rawSn 170 의 4프레임 전부 `changed_items` 에 실림(=exportable 4건과 일치). `:120-126` `findExportableFrameNosByRawSn` | |
| TC-NOTIFY-050 | 신고 해소 시 보류분 복구 | PASS | [실동작] rawSn 169 resolve → `DatasetExportBridge deident report resolved … re-triggering withheld export/notify` → export v2 SUCCEEDED → `TASK_COMPLETED sent rawSn=169` (queue 52) | `DatasetExportBridge.java:81-88`. 신고 구간 보류분의 **유일 복구 경로** 실증 |
| TC-NOTIFY-051 | 통지 요청 인증 헤더 | PASS | [정적] `WebClientConfig.java:78-81` `controlNotifyWebClient` 는 `baseUrl` 만 설정 — `defaultHeader` 없음. `ControlNotifyClient.java:90-94` 는 `Content-Type` 만 부여 | 기대결과(=미부착 연동 갭)와 일치. **관제 계약 `x-access-token` 미배선** — UNCERTAINTIES #26, 관제팀 협의 대상. 목서버가 헤더를 기록하지 않아 실왕복 확증은 불가 |

---

## D-8 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-MARTVIEW-001 | V_COMPLETED_VIDEO — APPROVED 게이트 | PASS | [실동작] 뷰 15행 전부 `REVIEW_STTS_CD='APPROVED'`. 비APPROVED 조인 결과 **0행**. 미승인 영상(20011 PENDING·20013 REJECTED·135/137/138 ASSIGNED 등) 미노출 | `V138:82,101` |
| TC-MARTVIEW-002 | V_COMPLETED_VIDEO — export 경로/프레임수 | PASS | [실동작] `EXPORT_PATH_NM=/app/storage/raw/seed/{rawSn}` = `dirname(원본)/{rawSn}` · `FRAME_CNT` 존재. `V138:83-90` LATERAL LIMIT 1 / `EXPORT_VER_NO DESC` | 173 은 v8(최신 SUCCEEDED) 선택 |
| TC-MARTVIEW-003 | V_COMPLETED_VIDEO — 미export null·1row | PASS | [실동작] rawSn 151·155 는 두 값 null 이면서 행 보존. `group by raw_sn having count(*)>1` = **0** | |
| TC-MARTVIEW-004 | V_COMPLETED_VIDEO — PARTIAL/PENDING export 제외 | PASS | [실동작] rawSn 173 은 `v4=PARTIAL·v5/v6=FAILED·v7/v8=SUCCEEDED` 보유 → 뷰는 `FRAME_CNT=6`(v8) 노출, PARTIAL 의 5 가 아님 | 반증 데이터 실재 |
| TC-MARTVIEW-005 | V_COMPLETED_VIDEO — ACTIVE_YN 스냅샷 게이트 | PASS | [실동작] rawSn 146 은 `LS_DATASET_VIDEO_META` 에 `ACTIVE_YN='N'`+`'Y'` 2행 보유 → 뷰에는 **1행만** | `V138:100` |
| TC-MARTVIEW-006 | V_COMPLETED_FRAME — 동일경로 행 fail-closed 제외 | PASS | [실동작] `original_path = deidentified_path` **0행**. 결측 행(파생 179 등 `ORIGINAL_PATH` null) 7행은 정상 통과·노출 | `V133:50-70`. **D-ISSUE-46 해소** |
| TC-MARTVIEW-007 | V_COMPLETED_FRAME — DESCRIPTION 노출 | PASS | [실동작] 출력 9컬럼 `SRC_SN/RAW_SN/FRAME_NO/ORIGINAL_PATH/DEIDENTIFIED_PATH/CAPTURED_AT/REG_DT/UPD_DT/DESCRIPTION`(끝) | `V133:41-49` |
| TC-MARTVIEW-008 | V_COMPLETED_LABEL_CHANGE — 건수만 노출 | PASS | [실동작] 출력 8컬럼, `CHG_DTL_CN` **부재**. `V139:25-37` | **1차 FAIL·D-ISSUE-47 해소** |
| TC-MARTVIEW-009 | V_COMPLETED_LABEL_CHANGE — APPROVED 게이트 | PASS | [실동작] 비승인 영상 소속 이력 **40행 실재** / 뷰 0행. 뷰 22행 전부 승인영상 | `V139:38-43` |
| TC-MARTVIEW-010 | V_COMPLETED_META — RVW_STTS_CD APPROVED만 | PASS | [실동작] 105행 전부 `RVW_STTS_CD='APPROVED'`, 비승인 0행 | `V107:124-146` |
| TC-MARTVIEW-011 | V_COMPLETED_META — video.* 기술메타 제외 | PASS | [실동작] `LS_DATA_META` 에 `video.%` **230행 실재**(승인영상 9건 × 5~6키) / 뷰 **0행** | `V107:140` |
| TC-MARTVIEW-012 | 라벨 내용 뷰 제거 확인 | PASS | [실동작] `pg_views` 에 `v_completed_video/_frame/_label_change/_meta` **4종만** — `V_COMPLETED_LABEL(_ATTR)` 부재 | `V114:30-31` |
| TC-MARTVIEW-013 | 뷰 멱등성(CREATE OR REPLACE) | PASS | [실동작] V138·V139·V133 의 `CREATE OR REPLACE VIEW` 재실행 3건 성공, 컬럼명·순서·타입 문자열 재실행 전후 **완전 동일**, 행수 불변 | |
| TC-MARTVIEW-014 | 승인→materialize→rollback 정합 | 확인필요 | [정적] `DatasetMaterializeApproveRollbackIT:129` 는 **"materialize 실패 시 approve 전체 롤백"** 시나리오뿐. 라벨 버전 롤백 후 뷰·활성 스냅샷 정합 케이스 없음. [실동작] rawSn 170 프레임의 활성 스냅샷이 1건뿐(승인 1회)이라 롤백 대상 다버전 미확보 | **D-ISSUE-50 미해소 확인 → D-ISSUE-62** |
| TC-MARTVIEW-015 | 비식별 영상 경로 노출(V138) | PASS | [실동작] `DE_IDNTF_FILE_PATH_NM=/app/storage/raw/seed/{rawSn}/deid/sample-cctv-1080p-mask.mp4` — KPST 규칙 `{stem}-mask{ext}` **적재값 원문**(조합·치환 흔적 없음). 미완료 영상은 null·행 보존(`V138:91-99` LATERAL) | 파생 179 는 자기 사본 경로 |
| TC-MARTVIEW-016 | 파생영상 ORIGINAL_VIDEO_PATH = NULL | PASS | [실동작] rawSn 179(`ORGNL_RAW_SN=170`) 승인 후 `ORIGINAL_VIDEO_PATH` **NULL**, `DE_IDNTF_FILE_PATH_NM` 로 픽업 가능 | 관제 협의 대상 BREAKING 확인 |
| TC-MARTVIEW-017 | 변경 0건 행 제외(V139) | PASS | [실동작] `LS_DATA_LBL_HSTRY` 에 델타 0 행 **1건 실재**(개인정보 리셋 감사) / 뷰 **0행** | `V139:37` |
| TC-MARTVIEW-018 | 신고 구간에도 뷰 노출 유지(확정 정책) | PARTIAL | [실동작] rawSn 169 를 `DE_IDNTF_YN='F'` 로 만든 뒤: 행 **유지** · `EXPORT_PATH_NM` **유지** · `DE_IDNTF_FILE_PATH_NM` **유지** · `V_COMPLETED_FRAME` 3행 유지 → **핵심 정책 충족**. 단 뷰의 `DE_IDNTF_YN` 이 **동결 스냅샷값 `'Y'`** 로 남아 신고를 반영하지 않음(라이브 `LS_DATA_RAW`='F') | 기대결과 후단 "`DE_IDNTF_YN` 컬럼으로 관제가 자체 판단 가능" 미성립 → **D-ISSUE-61**. 정책(행·경로 유지)은 결함으로 재분류하지 않음 |
| TC-MARTVIEW-019 | 촬영환경 self-fill 제거 — 미입력은 NULL | PASS | [실동작] 승인영상 9건 전부 `DAY_NGT_CD/SESN_CD/WTHR_NM` **null**. `SHT_DT` 기반 NGT/SUMMER 파생 흔적 0 | `DatasetVideoMetaSnapshotService.java:106-122` |
| TC-MARTVIEW-020 | 레거시 파생 동결값 정정 백필 | PASS | [실동작] `GET .../shooting-env-correction-targets` 200, 대상 **0건**(레거시 파생 동결 스냅샷 부재 = 정정 완료 상태). [정적] `DatasetVideoMetaBackfillService.java:182-200` 판별식 단일화·`DatasetVideoMetaEnvCorrectionTx.java:68-83` 건당 `TaskModifiedEvent(regen=true)`. IT 2종 | 대상 0건이라 재동결 본체는 정적 판정 |
| TC-MARTVIEW-021 | 백필 dev API 노출 통제 | PASS | [실동작] REVIEWER 200 / WORKER **403**. [정적] `@Profile("!prd")` + `@PreAuthorize("hasRole('REVIEWER')")` 이중, sub-resource 2개(`?dryRun=` 분기 없음) | prd 미등록은 프로파일 특성상 정적 |
| TC-MARTVIEW-022 | 백필 폭주 방지 | PASS | [정적] `DatasetVideoMetaBackfillService.java:184-200` `envCorrectionMaxPerRun` 상한 + `ENV_CORRECTION_BATCH_SIZE` 페이징 + 시작/잔여 건수 로그, 재실행 자연 no-op | |
| TC-MARTVIEW-023 | 뷰 필터 반증 데이터 | PASS | [실동작] `ACTIVE_YN='N'` 스냅샷 1건 · `META_KEY='video.*'` 230건 **모두 실재하며 4뷰 SELECT 결과 0행**. 추가 반례(PARTIAL/FAILED export 3건·비승인 이력 40건·델타0 이력 1건)도 전부 0행 | **D-ISSUE-49 커버리지 갭 해소** |

---

## 근거 드리프트

카탈로그 `근거` 의 `file:line` 이 **여전히 올바른 멤버를 가리키되 1~10행 어긋난** 건. 판정에는 영향 없으나 다음 최신화 때 정정 권장.

| ID | 카탈로그 근거 | 실측 위치 | 어긋남 |
|---|---|---|---|
| TC-NOTIFY-007 | `TaskModifiedPayload.java:20-54` | record 선언 `21-24`, `ChangedItems` `44-58` | 범위가 실제 두 블록과 반쯤 어긋남 |
| TC-NOTIFY-008 | `ControlNotifyDebouncer.java:174-176` | `accumulate` `175-177` | +1 |
| TC-NOTIFY-009 | `ControlNotifyDebouncer.java:80,190-203` | `MAX_DRAIN_ROUNDS` `81`, `flushAll` `191-204` | +1 |
| TC-NOTIFY-010 | `ControlNotifyDebouncer.java:100-103,141-166` | 필드 `101-104`, `startFlushScheduler` `142-157`, `flushExpiredWindowsSafely` `160-167` | +1 |
| TC-NOTIFY-011 | `ControlNotifyDebouncer.java:289-320` | flush 요약 로그 `292-295`, `summarizeChangeTypes` `312-321` | +1~3 |
| TC-NOTIFY-025 | `ControlNotifyDebouncer.java:72-74` | `@Component/@Slf4j/class` `73-75` | +1 |
| TC-NOTIFY-043 | `ControlNotifyDebouncer.java:285-308` | `send()` `286-309` | +1 |
| TC-NOTIFY-044/045 | `ControlNotifyDebouncer.java:241-269` / `:82-83` | `claimAndSendIsolated` `242-270` / `MIN_LEASE_MILLIS` `84`·`leaseMillis` `110` | +1, 필드 위치 상이 |
| TC-NOTIFY-013 | `LabelService.java:402-412` | 무변경 판정(`changes.isEmpty()`) `398-407` | -4 |
| TC-NOTIFY-012 | `LabelService.java:412-426` | 통지 게이트 `413-426` | +1 |
| TC-NOTIFY-041 | `DatasetExportFailureRecoverer.java:119-170` | `recover()` `119-169`, 클레임 `145-152` | 근사 일치 |
| TC-NOTIFY-042 | `DatasetExportFailureRecoverer.java:141-144` | 신고 스킵 `142-146` | +1 |
| TC-NOTIFY-051 | `WebClientConfig.java:76-80` | `controlNotifyWebClient` `77-82` | +1 |
| TC-NOTIFY-003 | `ControlNotifyPayloadFactory.java:78-92` · `TaskCompletedPayload.java:24-32` | `buildCompleted` `78-92` 일치 / record `26-32` | 후자 -2 |
| TC-MARTVIEW-008 | `V137:22-` | 실질 정의는 **V139** 가 최종(V137 은 중간 개정) | 최신 정의 파일 표기 권장 |

> D-6·D-8 범위에서 **기대결과 자체가 무효화된 근거 드리프트는 없다**(B/G 클러스터와 달리 계약 교체가 이미 카탈로그에 반영돼 있다).

---

## 이슈 상세

### [D-ISSUE-61] TC-MARTVIEW-018 — `V_COMPLETED_VIDEO.DE_IDNTF_YN` 이 동결 스냅샷값이라 비식별 신고 상태를 반영하지 못한다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 확정 정책상 신고 구간에도 행·경로를 유지하되, **관제가 자체 판단할 수 있도록 `DE_IDNTF_YN` 컬럼을 내보낸다**(카탈로그 TC-MARTVIEW-018 기대결과 후단 · `CLAUDE.md` "관제가 원하면 자체 판단도 가능하다"). 즉 이 컬럼은 관제가 "지금 이 영상이 신고 구간인가"를 판별하는 유일한 단서다.
- **현재 동작(이슈 내용)**: 뷰가 이 컬럼을 **라이브 `LS_DATA_RAW` 가 아니라 동결 스냅샷 `LS_DATASET_VIDEO_META` 에서 읽는다.**

  `backend/src/main/resources/db/migration/V138__expose_deident_video_path_in_completed_video_view.sql:49,80`
  ```sql
  m.DE_IDENT_YN      AS DE_IDNTF_YN,
  ...
  FROM LS_DATASET_VIDEO_META m
  INNER JOIN LS_DATA_RAW        r ON r.RAW_SN      = m.RAW_SN   -- r 은 상태 컬럼에 쓰이지 않는다
  ```
  스냅샷은 **검수 승인 시점(materialize)** 에 동결되므로, 승인 이후 접수된 신고는 재동결이 일어나기 전까지 반영되지 않는다.

  실측(rawSn 169):
  ```
  04:11:27  POST /api/v1/labels/179/deident-report  -> 201 (rprtSn=12)
  live :  select de_ident_yn from ls_data_raw where raw_sn=169;              -> F
  frozen: select active_yn, de_ident_yn from ls_dataset_video_meta ...=169;  -> (N,'Y') (Y,'Y')
  view  : select de_idntf_yn from v_completed_video where raw_sn=169;        -> Y     ← 신고 미반영
  ```
  값이 **비결정적**이라는 점이 특히 문제다 — 같은 시점의 rawSn 173 은 신고 이후 재동결이 한 번 더 일어나 뷰가 `F` 를 보여준다. 즉 관제 입장에서 같은 컬럼이 어떤 영상에선 최신, 어떤 영상에선 승인 시점 값이다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST localhost:18081/api/v1/labels/{srcSn}/deident-report \
       -H "Authorization: Bearer $REVIEWER_OR_ASSIGNED_WORKER" \
       -H 'Content-Type: application/json' -d '{"reason":"..."}'
  ```
  ```sql
  SELECT r.de_ident_yn AS live, v.de_idntf_yn AS view_value
    FROM ls_data_raw r JOIN v_completed_video v ON v.raw_sn = r.raw_sn
   WHERE r.raw_sn = {rawSn};   -- live='F' 인데 view_value='Y'
  ```
- **영향**: 관제가 이 컬럼으로 "마스킹 실패 가능성 있는 산출물"을 스스로 걸러내려 하면 **걸러지지 않는다**(CWE-359 정보 노출 판단 근거 오류). 확정 정책상 저작도구가 뷰에서 차단하지 않는 대신 관제에게 판단 재료를 준다는 전제가 성립하지 않으므로, "잔여 누수"가 아니라 **계약 상 약속한 신호의 부재**다. 파일 접근 자체는 정책대로 허용이 정상이며 그 부분은 결함이 아니다.
- **수정 방향(제안)**: 셋 중 택1로 관제팀과 확정. ①`V138` 의 해당 컬럼을 `r.DE_IDENT_YN`(라이브 `LS_DATA_RAW`, 이미 INNER JOIN 되어 있어 추가 조인 불요)으로 전환 — 다만 `CREATE OR REPLACE` 는 기존 컬럼 타입 변경을 허용하므로 같은 타입이면 안전. ②컬럼 의미를 "승인 시점 값"으로 문서 고정하고 **라이브 신고 상태용 컬럼을 맨 끝에 신규 추가**(하위호환). ③이 컬럼으로 자체 판단하지 않기로 관제와 합의하고 카탈로그·`CLAUDE.md` 문구에서 "자체 판단 가능" 서술 삭제. ⚠ **구현하지 않는다.**

### [D-ISSUE-62] TC-MARTVIEW-014 — 승인→materialize→라벨 버전 롤백 후 뷰·활성 스냅샷 정합 시나리오가 여전히 미커버 (D-ISSUE-50 미해소)

- **심각도**: LOW (커버리지 갭 — 현재까지 관측된 오동작 없음)
- **기대 동작(기대효과)**: 검수 승인으로 동결된 스냅샷이 있는 상태에서 라벨 버전을 롤백(대상 스냅샷 재활성)했을 때, `V_COMPLETED_VIDEO`/`V_COMPLETED_LABEL_CHANGE` 가 정합을 유지하고 **활성 스냅샷이 1건으로 유지**되는지 전용 통합 테스트가 고정해야 한다.
- **현재 동작(이슈 내용)**: 카탈로그가 근거로 지목한 `backend/src/test/java/kr/co/cudo/authoring/dataset/service/DatasetMaterializeApproveRollbackIT.java` 의 유일한 시나리오는
  ```java
  :129  @DisplayName("materialize_실패시_approve_실DB_전체롤백_상태_버전_통합메타_outbox_미반영")
  ```
  로, **"materialize 가 실패하면 승인 트랜잭션이 통째로 롤백된다"**를 검증한다. 케이스가 요구하는 **"승인 성공 → 이후 라벨 버전 롤백 → 뷰 정합"** 과 다른 시나리오다. `VersionService` 롤백 경로를 뷰와 함께 검증하는 IT 는 검색되지 않는다(`DatamartViewSlimIT`·`DatamartViewRebuildIT` 모두 롤백 미포함).
- **재현/확인 경로**:
  ```bash
  grep -rn "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/dataset/service/DatasetMaterializeApproveRollbackIT.java
  grep -rln "VersionService\|rollback" backend/src/test/java/kr/co/cudo/authoring/dataset/view/
  ```
  실동작 측면에서도 이번 회차에서는 확인 불가였다 — 신규 승인 영상(170)의 프레임별 활성 스냅샷이 `APPROVED` 1건뿐이라(`GET /v1/frames/186/versions` → 1건) 롤백 대상 다버전이 존재하지 않고, 다버전 확보에는 반려→재작업→재승인 사이클이 필요해 타 클러스터 참조 데이터를 건드리게 된다.
- **영향**: 롤백이 `LS_LABEL_VERSION` 재활성 방식으로 바뀐 뒤(`SAVE_REASON='ROLLBACK'` 적층 폐기) 뷰/동결 스냅샷과의 상호작용이 회귀 가드 없이 남아 있다. 롤백 시 활성 스냅샷이 2건이 되거나 `V_COMPLETED_LABEL_CHANGE` 에 팬텀 행이 생기는 회귀가 조용히 통과할 수 있다.
- **수정 방향(제안)**: `DatamartViewSlimIT` 에 시나리오 1건 추가 — ①승인(v1 스냅샷) ②라벨 수정 후 재검수·재승인(v2) ③`POST /v1/versions/{v1hash}/rollback` ④단언: `LS_LABEL_VERSION` 활성 1건 = v1 · `V_COMPLETED_VIDEO` 1행 유지 · `V_COMPLETED_LABEL_CHANGE` 에 델타 0 롤백 감사행 미노출. ⚠ **구현하지 않는다.**
