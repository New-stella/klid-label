# C클러스터 — 3차(2026-08-03) 검증 결과 병합

> `_raw/C-part1.md` ~ `_raw/C-part6.md` 6개 파일을 순서대로 병합. 원본은 `_raw/`에 보존.

---

# C클러스터 part1 — C-1. TC-MARK (마킹 자동/수동) 3차 검증 결과

- 대상: `docs/test-cases/C-marking-labeling.md` 20~74행, TC-MARK-01~46 (46건, 폐기 0건 — `grep -cE '^\| *~*TC-MARK-'` = 46 실측 일치)
- 방법: 실동작 최우선 — 로컬 스택(backend:18081, mock-server:9400, postgres)에 신규 rawSn 30여건을 직접 INSERT(비식별화 등 파이프라인은 우회하고 `de_ident_yn`/`data_stts_cd`를 목표 전제로 직접 세팅 — 검증 목적의 데이터 준비이며 프로덕션 코드/설정은 무수정)해 `POST /v1/videos/{rawSn}/markings` 를 REVIEWER(1001)/WORKER(2001)/WORKER2(미배정, 2002)/PORTAL_USER(3001) 토큰으로 직접 호출. 사용한 raw_sn: 116~147(C3-* vms_clip_id 접두).
- 근거 `file:line` 43건 전량 재대조(코드 재열람) — 드리프트 0건(1차 C-ISSUE-04가 이미 2026-08-03 라운드에서 자체 정정돼 있었음, 아래 참조).

## 판정 집계

| 판정 | 건수 | 비고 |
|---|--:|---|
| PASS | 44 | 실동작 확인 33건 + 코드/유닛테스트 확인 11건(TC-MARK-13/21~28/33/34/38/39 등 저위험 구조 확인) |
| PARTIAL | 2 | TC-MARK-11(29.97 회귀테스트 부재, 실동작 자체는 정확), TC-MARK-36(카탈로그 "순차 409" 문구가 실배선 타이밍과 어긋남 — 아래 참조) |
| FAIL | 0 | |
| BLOCKED | 0 | |
| N/A | 0 | |

## 케이스별 결과 요약

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-MARK-01 | PASS | [실동작] raw116 AUTO intervalFrames=30 → 201, marks 5건(0,30,60,90,120), STTS=PENDING, batchTriggered=true |
| TC-MARK-02 | PASS | [실동작] raw117 MANUAL marks=[{0,"00:00"}] → 201 |
| TC-MARK-03 | PASS | [실동작] raw126 mode="" → 400 "mode: mode 는 필수입니다." |
| TC-MARK-04 | PASS | [실동작] raw127 mode="XYZ" → 400 "mode 는 AUTO 또는 MANUAL 이어야 합니다." |
| TC-MARK-05 | PASS | [실동작] raw128 AUTO intervalFrames 누락 → 400 "1 이상" |
| TC-MARK-06 | PASS | [실동작] raw129(0)/raw130(-1) → 둘 다 400 |
| TC-MARK-07 | PASS | [실동작] raw131 MANUAL marks=[] → 400 "marks 는 필수입니다" |
| TC-MARK-08 | PASS | [실동작] raw121(vdo_len_sec NULL, 부재 파일) AUTO → 400 "영상 길이를 확인할 수 없어" |
| TC-MARK-09 | PASS | [실동작] raw132(vdo_len_sec=0) AUTO → 400 동일 메시지(퇴화 방지) |
| TC-MARK-10 | PASS | [실동작] raw133(dur=10,fps미상→30폴백) intervalFrames=30 → marks 0..270(10건), 300 미포함(off-by-one 없음) |
| TC-MARK-11 | **PARTIAL** | [실동작] raw145(video.fps=29.97 메타, dur=10) intervalFrames=30 → totalFrames=round(299.7)=300, marks 0..270(10건), fps DB pin=29.97, timestamp 정확(예 270/29.97=9.009s→"00:09") — 실동작 자체는 정확. 단 `grep -rn "29.97" backend/src/test`에 `MarkingServiceTest`(marks 산출 경로) 커버는 여전히 0건(fps=25/30/60만 존재) — 카탈로그가 이미 "1차 PARTIAL(B-ISSUE-27)"로 자기 기재해 둔 상태 그대로 미해소 확인 |
| TC-MARK-12 | PASS | [실동작] raw133: `ls_data_meta`에 video.fps 행 0건 확인 후 `ls_marking.fps=30` 저장 확인(폴백) |
| TC-MARK-13 | PASS | [실동작] raw116/raw145 DB `ls_marking.fps` 컬럼에 각각 30(폴백)·29.97(실값) pin 확인. [정적] LsMarking.java:117,:177,:228 일치 |
| TC-MARK-14 | PASS | [실동작] Authorization 헤더 없이 POST → 401 |
| TC-MARK-15 | PASS | [실동작] raw123(미배정) WORKER2(2002) 토큰 → 403 "본인에게 배정된 영상의 마킹만 접근할 수 있습니다" |
| TC-MARK-16 | PASS | [실동작] raw123(WORKER2 미배정) REVIEWER 토큰 → 201(전체 허용 확인) |
| TC-MARK-17 | PASS | [실동작] raw=999999 → 404 "영상을 찾을 수 없습니다" |
| TC-MARK-18 | PASS | [실동작] raw119(de_ident_yn='N') → 412 "비식별이 완료된 영상에서만 마킹할 수 있습니다" |
| TC-MARK-19 | PASS | [실동작] raw120(data_stts_cd='PENDING', MARKING_READY 아님) → 412 "이미 처리된 영상은 재마킹할 수 없습니다" |
| TC-MARK-20 | PASS | [실동작] raw118(evnt_type_cd=NULL) → 400 "이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다" |
| TC-MARK-21 | PASS | [실동작+정적] raw123 403 응답이 즉시(ffprobe 로그 없이) 반환됨(TC-MARK-15와 동일 요청). [정적] MarkingService.java:110-126 순서(precheck→duration resolve) 확인 |
| TC-MARK-22 | PASS | [정적] MarkingPrecheckReader.java:44 `@Transactional(readOnly=true, propagation=REQUIRES_NEW)` 확인 |
| TC-MARK-23 | PASS | [정적+실동작] MarkingService.java:169-178 persist 재검증 확인. 실동작상 모든 4xx가 precheck·persist 어느 경로든 동일 메시지로 일관 반환됨을 확인(가드 단일화 `MarkingGuards` 공용) |
| TC-MARK-24 | PASS | [실동작] 전 케이스 응답 `eventName`이 raw.evnt_type_cd("INTRUSION")를 그대로 반영 |
| TC-MARK-25 | PASS | [정적] `LsMarkingEntityTest`에 `createAuto_intervalFrames_0이하_예외`/`createAuto_rawSn_null_예외` 등 커버 확인(라인 157-168 일치) |
| TC-MARK-26 | PASS | [정적] `LsMarkingEntityTest`에 `createManual_eventName_null_예외`/`createManual_videoPath_blank_예외` 커버 확인(라인 211-219 일치) |
| TC-MARK-27 | PASS | [정적] LsMarking.java:246-253 코드 일치. `LsMarkingEntityTest#markVlmRequested_상태전이` 존재 |
| TC-MARK-28 | PASS | [정적] LsMarking.java:246-249 no-op 분기 확인. `#PENDING에서만_VLM_REQUESTED로_전이_그외_no_op` 존재 |
| TC-MARK-29 | PASS | [정적] MarkingBatchBridge.java:91-92,:125-130 일치(SKIP_BATCH_STAGES). [실동작] raw134 마킹 직후 `data_stts_cd`가 빠르게 MARKING_READY를 벗어남을 확인(간접 증거, 아래 TC-MARK-36 참조) |
| TC-MARK-30 | PASS | [정적] MarkingBatchBridge.java:134-139 일치 |
| TC-MARK-31 | PASS | [실동작] raw123: REVIEWER가 미배정 영상에 직접 마킹 → batchTriggered=true(작업상태 row 부재 → tryCreateBatchQueuedRow 경로) |
| TC-MARK-32 | PASS | [정적] MarkingBatchBridge.java:152-161 DataIntegrityViolationException catch 확인. [실동작] raw142 동시요청에서 대칭 메커니즘(V142 unique) 실증(TC-MARK-37 참조) |
| TC-MARK-33 | PASS | [정적] MarkingBatchBridge.java:114-119 일치 |
| TC-MARK-34 | PASS | [정적] MarkingBatchBridge.java:127,:136(sanitize 호출),:196-198(sanitize 정의) 일치 |
| TC-MARK-35 | PASS | [실동작] raw141 PORTAL_USER 토큰(channel=PORTAL) → 403 "권한이 없습니다" |
| TC-MARK-36 | **PARTIAL** | [실동작] ①raw134: AUTO 마킹 성공(201) 직후 곧바로 재요청 → **412**(`data_stts_cd`가 이미 FAILED로 전이된 상태 확인, 마킹 자체는 STTS=PENDING로 남음) — 카탈로그 기재값 409가 아님. ②raw143: `time_total` 계측 결과 1차 201(10.6ms)→2차 412(3.2ms), DB 재조회 시 raw143.data_stts_cd=FAILED. ③raw142: 진짜 동시(백그라운드 `&`) 2요청 → 1×201 + 1×409 확인(카탈로그 기대값 재현 성공). **결론**: `MarkingGuards`의 평가 순서(④MARKING_READY 선행 → ⑥활성마킹중복)상, 이 409는 재요청 시점에 raw가 여전히 MARKING_READY일 때만 도달하는데, mock-server 기반 실배선에서는 AFTER_COMMIT 배치가 수 ms 내로 raw를 다음 단계로 밀어내 **"순차"(사람이 응답을 보고 재시도) 시나리오는 실질적으로 항상 412로 귀결**한다. 409는 진짜 레이스(동시 요청)에서만 안정적으로 재현됨 — TC-MARK-37과 사실상 동일 메커니즘. 카탈로그 20~74행 범위 내에서 이 행에 실측 각주를 직접 추가(정정 완료, 아래 "카탈로그 정정" 참조) |
| TC-MARK-37 | PASS | [실동작] raw142 동시 2요청(`curl ... & curl ... & wait`) → 1×201("markingSn":88) + 1×409 CONFLICT("이미 진행 중인 마킹이 있습니다") |
| TC-MARK-38 | PASS | [실동작] `docker exec klid-postgres psql ... \d ls_marking` → `uk_ls_marking_raw_actvtn UNIQUE (raw_sn) WHERE stts_cd = ANY(['PENDING','VLM_REQUESTED'])` — `LsMarking.ACTIVE_STATUSES` 리터럴과 문자 일치 확인 |
| TC-MARK-39 | PASS | [정적] MarkingPrecheckReader.precheck()(라인 45-50)가 인가→프리컨디션→활성중복 전부를 **하나의 메서드 안에서** 완료한 뒤에야 `MarkingService.create()`가 `durationResolver.resolveDurationSec()`(ffprobe 트리거점)를 호출(라인 110-126) — 구조상 활성중복 판정이 ffprobe보다 항상 선행됨이 코드로 보장됨. 실동작으로는 raw147(REVIEW_OWNED 스킵 후 SKIPPED 종결)이라 활성 상태 유지 재현에는 실패했으나(스킵 시 즉시 종결되어 활성 아님), 코드 구조상 결론은 확정적 |
| TC-MARK-40 | PASS | [실동작] raw135 frameIndex=-5 → 400 "marks[0].frameIndex: frameIndex 는 0 이상이어야 합니다" |
| TC-MARK-41 | PASS | [실동작] raw136 [{0},{0}] → 400 "중복된 마킹 시점입니다: frameIndex=0" |
| TC-MARK-42 | PASS | [실동작] raw137(dur=5,fps폴백30→limit=round(150)+30=180) frameIndex=999999999 → 400 "허용 상한 180 프레임 미만" |
| TC-MARK-43 | PASS | [실동작] raw138(dur/메타 모두 부재) frameIndex=999999 → 201 통과 + 백엔드 로그에 WARN "[Marking] duration unknown — manual mark upper-bound check skipped rawSn=138 marks=1" 확인 |
| TC-MARK-44 | PASS | [실동작] raw139 timestamp="99:99"/"−1:00" → 둘 다 400 "timestamp 는 mm:ss 또는 mm:ss:ff 형식이어야 합니다" |
| TC-MARK-45 | PASS | [실동작] raw140 marks 20001건 → 400 "marks: 한 번에 처리 가능한 마킹 수 초과 (최대 20000)" |
| TC-MARK-46 | PASS | [실동작] raw146(사전에 `ls_raw_data_status.data_stts_cd='APPROVED'` 세팅) AUTO 마킹 → 201 + `batchTriggered:false, batchSkipReason:"검수 진행/완료(또는 반려) 상태이거나 이미 배치가 큐잉되어 배치를 시작하지 않았습니다."`. 동시에 DB 확인 결과 해당 마킹은 즉시 `STTS_CD='SKIPPED'`로 종결되어(B-ISSUE-41 수정 반영) 영구 고아·재마킹 409 고착이 발생하지 않음을 확인 |

## 카탈로그 정정 (내 담당 라인범위 20~74행 내, Edit로 직접 수행) — 1건

- **TC-MARK-36 행에 실측 각주 추가**: 기존 기대결과 "409 CONFLICT (순차)"는 유지하되, 실배선 스택에서 이 409가 재현되는 조건(진짜 동시 요청에서만 안정적, "순차"는 사실상 412로 귀결)을 실측 근거(raw134/raw142/raw143)와 함께 각주로 덧붙였다. 판정 자체(가드 존재 여부)는 바꾸지 않았고 — 즉 코드 결함이 아니라 카탈로그 문구의 재현조건 설명 보강이다. (파일: `docs/test-cases/C-marking-labeling.md:59`)

## 이전 회차(1차 2026-08-01) C-1 이슈 해소 여부 대조

| 이슈 | 내용 | 3차 상태 |
|---|---|---|
| C-ISSUE-01(1차) | AUTO 마킹 marks 개수 무상한(MANUAL 20000 상한을 AUTO가 우회) | **미해소 — 재현 확인**. raw124(dur=3600, intervalFrames=1) → 201, marks 108,000건, `length(mark_cn)`=4,424,891 bytes(응답 size_download=4,425,205). `MarkingRequest.marks`의 `@Size(20000)`은 MANUAL 배열에만 적용되고 `generateAutoMarks`(MarkingService.java:268-283)에는 여전히 상한이 없음 |
| C-ISSUE-02(1차) | intervalFrames 검증이 ffprobe 실행 이후 수행됨(무효 입력에도 프로브 서브프로세스 실행) | **미해소 — 재현 확인**. raw144(vdo_len_sec NULL, 부재 파일) `{"mode":"AUTO","intervalFrames":0}` → 400 반환되었으나 백엔드 로그에 `ERROR ... BrampVideoProbe - [Video][Probe] ffprobe empty output path=80685da1`가 400 이전에 기록됨(ffprobe가 무효 입력 검증보다 먼저 실행) |
| C-ISSUE-03(1차) | `LS_MARKING.VIDEO_FILE_PATH_NM`이 비식별 전 원본 절대경로를 저장·응답 노출 | **미해소 — 코드 확인**. `MarkingService.java:217-218`가 여전히 `raw.getRawFilePathNm()`(원본 컬럼)을 `createAuto/createManual`에 전달. 실동작 응답 `videoPath`도 raw_file_path_nm 그대로 노출(예: raw116 응답 `"videoPath":"/app/storage/raw/seed/clip-9101.mp4"`) |
| C-ISSUE-04(1차) | 근거 file:line 드리프트 3건(MarkItem/MarkingRequest) | **해소 확인** — 별도 수정 커밋 없이, 2026-08-03 "라인 드리프트 57건 정정" 라운드(카탈로그 변경이력 3행)에서 이미 자체 정정되어 있었음. 현재 `MarkItem.java:23-25`(TC-MARK-40)·`:27-29`(TC-MARK-44), `MarkingRequest.java:31`(TC-MARK-45) 전부 실제 코드와 정확히 일치함을 재확인(43건 전수 재대조 결과 드리프트 0건) |
| C-ISSUE-05(1차) | TC-MARK-11 29.97fps marks 산출 회귀 테스트 부재 | **미해소 유지**. `MarkingServiceTest`에 fps=25/30(폴백)/60 케이스만 존재, 29.97 케이스 여전히 0건(실동작 자체는 정확함을 재확인) |

## 참고 — 검증 중 생성한 데이터(원복 안 함, 판정 근거 보존 목적)

- `ls_data_raw` raw_sn 116~147(`vms_clip_id` 접두 `C3-*`), `ls_task_assignment`(user_no=2001, task_type_cd='LABELER') 다수, `ls_raw_data_status`(raw_sn 146/147, APPROVED), `ls_data_meta`(raw_sn 145, video.fps=29.97) — 전부 검증 목적 신규 삽입이며 기존 데이터(rawSn≤115) 수정 없음. 코드/설정 파일은 `docs/test-cases/C-marking-labeling.md:59` 1행 외 변경 없음.

---

# C 클러스터 part2 — C-2. TC-LABEL 앞 1/3 (파일 75~123행, TC-LABEL-01~37 · 40~47 = 45건)

> 검증일 2026-08-03(KST 2026-08-04 00:50~01:15) · 회차 3차 · 담당 범위: `docs/test-cases/C-marking-labeling.md` 75~123행
> 방법: **풀스택 실동작 우선** — backend `localhost:18081/api` 실호출 + PostgreSQL DB 실측 + mock-server(:9400) 인바운드 로그 + backend 로그.
> 환경 전제: `_raw/stack-bringup.md`(5컨테이너 healthy, Flyway v163, 외부연동 4종 mock-server 실배선) · `_raw/pipeline-drive.md`(rawSn=101 APPROVED/export SUCCEEDED — 조회·APPROVED 축에만 사용).

## 사용한 실데이터 (신규 생성분)

| 용도 | 값 |
|---|---|
| 미검수(ASSIGNED) 라벨 CRUD | rawSn=115 · srcSn=508/509/510 (+신규 511·512·513, frm_no 90~92) · WORKER=2001 배정 |
| 동시성/IDOR/타프레임 | rawSn=110 · srcSn=514(frm_no 95)·515(frm_no 96) — REVIEWER 토큰 |
| 검수완료(APPROVED) 통지 축 | rawSn=101 · srcSn=468 (기존 파이프라인 산출) |
| 토큰 | `POST /v1/dev/tokens` — ⚠ 요청 필드는 `sub` 이 아니라 **`userNo`** (`DevTokenRequest`). `sub` 로 보내면 조용히 역할 기본값(REVIEWER=1001/WORKER=2001/PORTAL=3001)으로 발급돼 IDOR 케이스가 **거짓 PASS** 난다 |
| 이미지 경계 | raw115/raw110 프레임 = **320x240** (`FrameBoundsResolver` 실측값) |

> ⚠ 검증 중 다른 병렬 에이전트가 rawSn=115 에 비식별 신고(`DE_IDNTF_YN='F'` + 작업락)를 걸어 중반부터 115 저장이 409 가 됐다. 이후 케이스는 rawSn=110 전용 프레임으로 이관해 수행했다(판정 영향 없음).

---

## 판정표

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-LABEL-01 | PASS | [실동작] `GET /v1/frames/468/labels` (WORKER 2001) → 200. `labelVersion:1`, `siblings` 10건(`hasLabel` 포함), items[0]에 마스터 enrich(`labelName:"person"`,`color:"#E74C3C"`) + AI_INFO enrich(`autoLblYn:"N"`,`confScore:null`,`lblSrcCd:null`). 근거 `LabelService.java:187-213` 정확 |
| TC-LABEL-02 | PASS | [실동작] WORKER **2002**(raw101 미배정) `GET`·`PUT` 모두 **403** `{"errorCode":"FORBIDDEN","message":"본인에게 배정되지 않은 영상입니다."}`. PORTAL_USER GET 도 403. 근거 `LabelAccessGuard.java:62-69` 정확 |
| TC-LABEL-03 | PASS | [실동작] Authorization 헤더 없이 GET → **401** `UNAUTHORIZED`. `LabelAccessGuard.java:48-50` 정확 |
| TC-LABEL-04 | PASS | [실동작] srcSn=99999999 → **404**, srcSn=0 → **404** (`"프레임을 찾을 수 없습니다."`). `LabelAccessGuard.java:54-58` 정확 |
| TC-LABEL-05 | PASS | [실동작] REVIEWER `?raw=true` → `"frameImageType":"RAW"`. `LabelService.java:229-234` 정확 |
| TC-LABEL-06 | PASS | [실동작] WORKER `?raw=true` → `"frameImageType":"DEID"`(무시 강제). 동일 근거 |
| TC-LABEL-07 | PASS | [실동작] `PUT /v1/frames/508/labels` id=null·source=MANUAL → lblSn=730 INSERT, `ls_data_lbl_ai_info` 행 **미생성** → 응답 `autoLblYn:"N"`, 이력 `kind:"ADDED"`(before=null), `lbl_ver` 0→1. `:359-365` 정확 |
| TC-LABEL-08 | PASS | [실동작] id=730 좌표 변경 → UPDATE + 이력 `kind:"UPDATED"` before `[[10,10],[100,100]]` / after `[[20,20],[120,120]]` 양쪽 스냅샷 기록. `:323-348` 정확 |
| TC-LABEL-09 | PASS | [실동작] srcSn=509 에 3건(731/732/733) 생성 후 731만 전송 → 732·733 **실삭제**, 이력에 DELETED 2건 + before 스냅샷(after=null). `:375-393` 정확 |
| TC-LABEL-10 | PASS | [실동작] `items:[]` → 프레임 라벨 0건, 이력 DELETED 기록, `lbl_ver` +1. 동일 근거 |
| TC-LABEL-11 | PASS | [실동작] 삭제 대상 라벨(731)에 `ls_data_lbl_attr_val`(실 FK) 1건 + 다른 라벨(732)에 `ls_data_lbl_ai_info` 1건을 심은 뒤 삭제 → **FK 위반 500 없이 200**, 자식 행 전부 동반 삭제(고아 0). `:390-392` 정확 |
| TC-LABEL-12 | PASS | [실동작] `ls_auth_work_lock` 에 `TARGET_RAW/115/LOCKED` INSERT → `PUT` **409** `"비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다."`, 조회는 200 + `lockSttsCd:"LOCKED"`. 락 해제 후 저장 200. `:255-258` 정확. ⚠ **방어 심층 공백 발견 → C-ISSUE-22**(락은 6h 만료·sweep 회수, `DE_IDNTF_YN='F'` 는 남음 → 저장 재개방) |
| TC-LABEL-13 | PASS | [실동작] 같은 id=730 을 2개(label "first"/"last") 전송 → 최종 저장값 `last` + `[[7,7],[70,70]]`, 라벨 1건 유지. `:501-519` 정확 |
| TC-LABEL-14 | PASS | [실동작] `points:[]` → 400 `"items[0].points: must not be empty"`(DTO `@NotEmpty`), `points:null` 도 동일 메시지 → 카탈로그의 "서비스 메시지 도달 불가" 서술 실증. `LabelItemDto.java:42` 정확 |
| **TC-LABEL-15** | **PARTIAL** | [실동작] `pair size=3` → 400 / `pair=null` → 400 (기대 충족). **그러나 원소 null `[[null,5],[10,10]]` 은 여전히 500 `INTERNAL_ERROR`** — 1차 **C-ISSUE-21 미해소 이월**. 대조군 SKELETON(`:805-811`)은 400. → **C-ISSUE-21** |
| TC-LABEL-16 | PASS | [실동작] BBOX `x=-1` → 400 `"좌표는 0 이상이어야 합니다 (x=-1.0, y=5.0)"`. **SEGMENT·TRACK 타입도 동일 400** 확인(형태 전 분기 커버). `:725-728` 정확. ★3(사용자 저장=거부) 정합 |
| TC-LABEL-17 | PASS | [실동작] `[[0,0],[10,10]]` → 200 저장 성공(0 은 음수 아님) |
| TC-LABEL-18 | PASS | [실동작] 신규 POLYGON **1001점 → 400** `"라벨당 좌표 개수 초과 (최대 1000 점)"`, **1000점(경계) → 200** 저장 후 `json_array_length=1000` 보존. `:715-718` 정확 |
| TC-LABEL-19 | PASS | [실동작] 기존 라벨(id 지정) 1001점 → **400 아님, 200** + 저장값 `json_array_length=34`(Douglas-Peucker simplify). `:838, :907-912` 정확 |
| TC-LABEL-20 | PASS | [실동작] labelId=999999 → **404** `"라벨 마스터를 찾을 수 없습니다: labelId=999999"`. `:670-673` 정확 |
| TC-LABEL-21 | PASS | [실동작] ①신규(id=null)에 비활성 labelId=10 → **409** `"사용 중지된 라벨입니다"` ②DB로 심은 기존 라벨(lblSn=736, lblId=10)이 **같은 labelId 유지** 저장 → **200 통과**(C-ISSUE-25 수정 실증) ③기존 라벨의 labelId 를 다른 비활성(11)로 **변경** → 409. `:650-679, :685-695` 정확 |
| TC-LABEL-22 | PASS | [실동작] ①`ls_data_lbl` 에 `auto_lbl_yn` **컬럼 자체 없음**(DDL 실측: `lbl_sn/src_sn/lbl_type_cd/lbl_nm/point_cn/trck_id/reg_user_no/reg_dt/mdfcn_dt/lbl_id`) ②수동 신규에 `autoLblYn:"Y"` 전송 → 응답 `"N"` ③기존 AUTO 라벨을 `autoLblYn:"N"`+`source:"MANUAL"`+`confScore:0.01` 로 UPDATE → 응답 **`"Y"`/0.5/YOLO 유지**. 추가 반증: `trckId`,`lblSn`,`regUserNo`,`isAdmin` 미지 필드 주입 → 전부 무시(`trck_id=null`, `reg_user_no`=actor). `:133-143, :436` 정확 |
| TC-LABEL-23 | PASS | [실동작] `source:"AUTO_YOLO", confScore:0.87, algorithm:"YOLO"` → 응답 `autoLblYn:"Y"`, `ls_data_lbl_ai_info` 행 생성(`lbl_src_cd=YOLO, conf_score=0.87000, auto_lbl_yn=Y, data_raw_sn=115, data_src_sn=510, reg_id=2001`). `:351-358, :853-869` 정확 |
| TC-LABEL-24 | PASS | [실동작] `source:"HACK"` → 400 `@Pattern`. `algorithm:"EVIL"` → 400, `lblTypeCd:"FOO"` → 400. `LabelItemDto.java:44-45` 정확 |
| TC-LABEL-25 | PASS | [실동작] `confScore:1.5` → 400 `@DecimalMax`, `-0.1` → 400 `@DecimalMin`. `:46-47` 정확 |
| TC-LABEL-26 | PASS | [실동작] items **501건 → 400** `"한 번에 처리 가능한 라벨 수 초과 (최대 500)"`, **500건(경계) → 200**(DB 500행 확인), `items:null` → 400 `@NotNull`. `LabelBulkUpsertRequest.java:24` 정확 |
| TC-LABEL-27 | PASS | [실동작] 완전 동일 payload 재저장 → 이력 신규 0건 + `lbl_ver` 2 유지 + 통지 미발행. 반증: **좌표는 같고 라벨명만 변경** → UPDATED 발행 / **labelId 만 변경** → UPDATED 발행(무변경 판정이 과잉 흡수하지 않음). `:342-348, :397-399` 정확 |
| TC-LABEL-28 | PASS | [실동작] 저장값 `[[20.0,20.0],[120.0,120.0]]` 에 대해 `[[20,20],[120.000,120.0]]` 재전송 → 이력 미발행·버전 미증가. `:547-561` 정확 |
| TC-LABEL-29 | PASS | [실동작] DB에 레거시 2포맷 직접 심고(`[{"x":5,"y":6},{"x":50,"y":60}]` / `[7,8,70,80]`) 동일 좌표를 정규 포맷으로 재전송 → **UPDATED 이력 0건**(두 포맷 모두 흡수). `:568-585` 정확 |
| TC-LABEL-30 | PASS | [실동작] `point_cn='{not-json'` 손상값 라벨을 재저장 → **UPDATED 이력 기록**(before `"{not-json"` 보존) = '변경됨' fail-safe. `:556-559, :582-584` 정확. ※카탈로그 주석대로 **전용 회귀 테스트는 여전히 부재**(3차 재확인) → **C-ISSUE-23**(LOW, 이월) |
| TC-LABEL-31 | PASS | [실동작] APPROVED rawSn=101/srcSn=468 좌표 수정 → `ls_mon_noti_acml` 행 생성(`raw_sn=101, stts_cd=PENDING, export_rprcs_yn='Y', chg_dtl_cn={"frames":{"468":["LABEL_UPDATED"]}}`) → 60s 디바운스 flush 후 backend 로그 순서 실측: `flush rawSn=101 regen=true` → `export succeeded rawSn=101 **version=2** written=20` → `TASK_MODIFIED sent rawSn=101 reExport=true`. mock-server 인바운드 `POST /api/data-set/v2/jobs/101/notify-updated 202 Accepted`, `ls_dataset_export` 에 `export_ver_no=2` SUCCEEDED 추가. **export 성공 후 통지** 순서까지 정합. `:413-426` 정확 |
| TC-LABEL-32 | PASS | [실동작] ASSIGNED rawSn=115 저장 → `ls_mon_noti_acml` 행 **미생성**, mock-server `notify-updated` 카운트 불변(8→8). `:413` 정확 |
| TC-LABEL-33 | PASS | [실동작] WORKER 2002 `GET /v1/frames/468/label-history` → **403**. `:477` 정확 (엔드포인트는 `/label-history` — 카탈로그 표기는 근거 파일이라 영향 없음) |
| TC-LABEL-34 | PASS | [실동작] `size=500` → 응답 `size:100` 클램프. 반증: `size=0`·`size=-1` → **500 아님**, 기본 20 폴백. `page=999999` → 200 빈 content. `:593-594` 정확 |
| TC-LABEL-35 | PASS | [실동작] `?sort=badfield,desc` → **200**(500 아님), `?sort=regDt;DROP TABLE,desc` → 200. 응답 순서 `lblHstrySn` 내림차순(REG_DT DESC + tiebreaker). `:588-600` 정확 |
| TC-LABEL-36 | PASS | [실동작·동시성] ①stale `labelVersion:0` 첨부 → **409** `"다른 사용자가 먼저 저장했습니다…"` ②정확한 버전 첨부 → 200(+1) ③미첨부 → 200(하위호환 skip) ④음수 → 400 `@PositiveOrZero` ⑤**동일 baseVersion 으로 10병렬 PUT → 정확히 1건 200 / 9건 409**, 최종 `lbl_ver=2`·생존 라벨 1건. 락 획득 시점 CAS(스칼라 프로젝션)가 실경합에서 동작함을 실증. `:272-274, :457-465` 정확 |
| TC-LABEL-37 | PASS | [실동작] backend 로그 전량 확인 — `[Label] bulkUpsert srcSn=511 actor=2001 existing=1 saved=0 deleted=1 labelVersion=3->4` 형태로 **카운트+버전만**, 좌표·라벨명·PII 없음. 409 경로도 `stale label version rejected srcSn/requested/current` 만. `:408-409` 정확 |
| TC-LABEL-40 | PASS | [실동작] `POST /v1/manage/labels` REVIEWER → **201** `{labelId:30,...,useYn:"Y"}`. `LabelMasterService.java:74-95` 정확 |
| TC-LABEL-41 | PASS | [실동작] 활성 `"qa-c2p2-alpha"` 존재 시 `"  QA-C2P2-Alpha  "` → **409**. 반증: **soft-delete 된 동일 이름 재사용은 201 허용**(부분 유니크 `WHERE use_yn='Y'` 시맨틱 정합). `:76-78` 정확 |
| TC-LABEL-42 | PASS | [실동작·동시성] 동일 이름 8병렬 POST → **1건 201 / 7건 409**, DB 1행. 인덱스 실측 `uk_ls_label_nm_ci ON (lower(btrim(lbl_nm))) WHERE use_yn='Y'` 존재. `:74-78 · LsLabelRepository.java:37` 정확 |
| TC-LABEL-43 | PASS | [실동작] `dtctTypeCd:"human"` → 400 `"지원하지 않는 검출 클래스입니다."`. 반증: `"Person"`(대문자) → **400**(대소문자 구분 allowlist), `"  car  "` → trim 후 유효 판정되어 409(중복 매핑), 21자 → 400 `@Size(max=20)`. `:122-134` 정확 |
| TC-LABEL-44 | PASS | [실동작] 활성 `person` 매핑 존재 시 재매핑 → **409** `"이미 사용 중인 검출 클래스 매핑입니다."`. 인덱스 `uk_ls_label_dtct_type ... WHERE use_yn='Y' AND dtct_type_cd IS NOT NULL` 실측. `:81-82, :109-111` 정확 |
| TC-LABEL-45 | PASS | [실동작] `dtctTypeCd:"   "`(blank) → 201 + `dtctTypeCd:null` 저장. `:122-134` 정확 |
| TC-LABEL-46 | PASS | [실동작] `"#ffffff"` → 400 `"color 는 대문자 hex (#RRGGBB) 형식이어야 합니다."`. `LabelMasterRequest.java:33` 정확 |
| TC-LABEL-47 | PASS | [실동작] `type:"FOO"` → 400 `@Pattern(BBOX/POLYGON/POINT/SKELETON)`. `:37` 정확 |

### 집계 (45건)

| PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|:--:|:--:|:--:|:--:|:--:|:--:|
| **44** | 0 | **1** | 0 | 0 | 0 |

- 실동작 검증 45/45 (정적 전용 0건).
- 근거 `file:line` **드리프트 0건** — 45행 전량 실파일 대조 결과 3차 재확인분과 일치.

---

## 추가 반증 결과 (케이스 외 — 결함 아님으로 확인된 것)

| 반증 시도 | 결과 |
|---|---|
| full-replace 에 **타 프레임 라벨 id** 주입(IDOR/데이터 파괴) | 타 프레임 라벨(lblSn=1252/srcSn=515) **무손상 생존**, 현재 프레임 신규 ADDED 로 안전 처리 — 문서화된 설계와 일치 |
| Mass Assignment(`trckId`/`lblSn`/`regUserNo`/`isAdmin`) | 전부 무시 (`trck_id=null`, `reg_user_no`=토큰 actor) |
| 이력 API `size=0/-1`, `page=999999`, sort 인젝션 | 전부 200, 500·오라클 없음 |
| 무변경 판정의 과잉 흡수 (라벨명·labelId 만 변경) | 정상적으로 UPDATED 발행 — `snapshotsEqual` 이 좌표만 보지 않음 |
| APPROVED 통지가 export 보다 앞서는가 | export `version=2` 성공(01:03:23.739) → 통지(01:03:23.742) 순서 실측 정합 |

---

## 이전 회차 이슈 대조

| 이전 이슈 | 상태 | 근거 |
|---|:--:|---|
| **1차 C-ISSUE-21** (TC-LABEL-15, 좌표 원소 null → NPE 500) | **미해소 (이월)** | 3차 실동작 재현 — 동일 500 `INTERNAL_ERROR`. 자동테스트 baseline(backend 5,203건 전건 통과)에도 이 경로 회귀 테스트 없음 |
| 카탈로그 주석 C-ISSUE-23 (TC-LABEL-30 회귀 테스트 부재) | **미해소 (이월)** | `LabelServiceFullReplaceIntegrationTest` 의 "손상된_diff_JSON…" 은 **이력 조회 폴백** 테스트이고, `pointsEqual`/`normalizePoints` 손상값 fail-safe 전용 테스트는 여전히 없음 |
| 1차 C-ISSUE-25 (비활성 마스터가 프레임 저장 영구 차단) | **해소 확인** | TC-LABEL-21 ② 실동작 200 |
| UNCERTAINTIES #9 (동시 저장 Race) | **확정 재실증** | 10병렬 CAS 1/9 결과 |
| UNCERTAINTIES #10 / ★3 (좌표 경계) | **확정 재실증** | 사용자 저장 경로는 경계 초과 400 거부(클램프 아님) |

---

## 카탈로그 정정 (담당 라인범위 75~123행 내에서만 수행)

| 행 | 정정 내용 |
|---|---|
| TC-LABEL-15 (93행) | 입력/조건에 `pair=null` 추가 + 기대결과에 **"원소 null 은 여전히 500 — 1차 C-ISSUE-21 미해소 이월"** 명시(대조군 SKELETON `:805-811` 병기). 근거 라인 `719-722` → **`719-728`**(음수 검사까지 포함한 2-튜플 루프 전체가 판정 지점) |
| TC-LABEL-12 (90행) | 기대결과에 **"저장 경로에는 신고 게이트가 없고 작업락이 유일 방어 — 락 6h 만료 후 조회 412 ↔ 저장 200 비대칭(C-ISSUE-22)"** 경고 추가 |

**정정 2건.** 프로덕션 코드·타 라인범위 무수정.

---

# 이슈 대장

### [C-ISSUE-21] TC-LABEL-15 — 좌표 배열 **원소**가 null 이면 2-튜플 경로에서 언박싱 NPE → 500 (1차 미해소 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 좌표 **형식 위반은 전부 400(INVALID_INPUT)** 이어야 한다. SKELETON 경로는 같은 상황을 명시적으로 400 으로 막고 있고 그 주석이 계약을 선언한다(*"언박싱 전에 400 으로 fail-secure 거부하여 계약(형식 위반=400)과 일치시킨다"*). CWE-20 입력 방어선 + OWASP A10:2025(fail-secure) 요구.
- **현재 동작(이슈 내용)**: `pair.size() != 2` 만 검사한 뒤 곧바로 `Double → double` 언박싱한다. `pair.get(0)` 이 null 이면 NPE → `GlobalExceptionHandler` catch-all → **500**.

  `backend/src/main/java/kr/co/cudo/authoring/label/service/LabelService.java:719-728`
  ```java
  for (List<Double> pair : points) {
      if (pair == null || pair.size() != 2) {
          throw new CustomException(ErrorCode.INVALID_INPUT, "좌표는 [x, y] 형태여야 합니다.");
      }
      double x = pair.get(0);   // ← pair.get(0) == null 이면 언박싱 NPE
      double y = pair.get(1);
  ```
  대조군 `LabelService.java:805-811`(SKELETON)은 동일 상황을 400 으로 거부한다.

  **3차 실동작 재현 (2026-08-04 01:00 KST)**
  ```
  PUT /api/v1/frames/510/labels  (WORKER 2001, 배정 프레임)
  {"items":[{"lblTypeCd":"BBOX","labelId":1,"label":"x","points":[[null,5],[10,10]]}]}
  → HTTP 500 {"success":false,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}

  (대조) points:[null,[10,10]]          → 400 INVALID_INPUT "좌표는 [x, y] 형태여야 합니다."
  (대조) points:[[1,2,3],[4,5,6]]       → 400 INVALID_INPUT (동일 메시지)
  ```
- **재현/확인 경로**
  ```bash
  B=http://localhost:18081/api
  curl -s -X POST $B/v1/dev/tokens -H 'Content-Type: application/json' \
    -d '{"userNo":"2001","role":"WORKER","channel":"INTERNAL"}' -o /tmp/t.json
  T=$(python3 -c 'import json;print(json.load(open("/tmp/t.json"))["data"]["token"])')
  curl -i -X PUT $B/v1/frames/510/labels -H "Authorization: Bearer $T" \
    -H 'Content-Type: application/json' \
    -d '{"items":[{"lblTypeCd":"BBOX","labelId":1,"label":"x","points":[[null,5],[10,10]]}]}'
  # → HTTP/1.1 500, errorCode=INTERNAL_ERROR  (기대: 400 INVALID_INPUT)
  ```
- **영향**
  - **계약 위반(CWE-20 / CWE-248)**: 입력 형식 오류가 500 으로 나가 FE 가 "서버 장애"로 오분류 → 무의미한 재시도 유발.
  - **운영 알람 노이즈**: 매 요청마다 ERROR 레벨 스택트레이스가 로그에 남아 알림 대상이 된다.
  - 응답 본문에 스택트레이스는 없어 CWE-209 정보노출은 없음.
  - 유입 경로: FE 캔버스 부분 직렬화 버그, 외부 도구로 만든 라벨 임포트, SAM2/YOLO 결과 후처리 누락 등.
  - **자동테스트 공백**: backend 5,203건 전건 통과인데도 이 경로가 잡히지 않는다(회귀 가드 부재).
- **수정 방향(제안)**: `LabelService.validatePoints` 의 2-튜플 루프에서 `pair.get(0)`/`pair.get(1)` 을 `Double` 로 먼저 받아 null 검사 후 언박싱한다(SKELETON `validateSkeletonPoints:805-811` 과 동일 패턴). 메시지는 SKELETON 과 대칭으로 `"좌표에 null 원소가 있습니다."` 권장. 회귀 테스트는 `LabelControllerTest` 또는 `LabelSaveGuardsIT` 에 BBOX/POLYGON/SEGMENT/TRACK 4형태 × 신규/기존 2경로로 추가. ⚠ **구현은 하지 않음.**

---

### [C-ISSUE-22] TC-LABEL-12 (인접) — 신고 구간 라벨 **저장** 방어가 작업락 하나뿐이라, 락 6h 만료 후 "조회 412 ↔ 저장 200" 비대칭이 열린다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 비식별 누락 신고(`DE_IDNTF_YN='F'`) 구간에는 그 영상의 라벨을 **읽지도 쓰지도 못해야** 한다. `CLAUDE.md` 는 *"신고 구간 동안 라벨 조회를 차단한다(412) … 저장·수정은 기존 작업락으로 409 차단"* 이라고 두 축을 나눠 선언하는데, 이 분업이 성립하려면 **작업락의 수명이 신고 구간과 같아야** 한다.
- **현재 동작(이슈 내용)**: 두 수명이 다르다.
  - 신고 락은 **6시간 만료**로 생성된다 — `backend/src/main/java/kr/co/cudo/authoring/auth/entity/LsAuthWorkLock.java:84`
    ```java
    public static LsAuthWorkLock lockRawForRedeident(Long rawSn, String ownerId) {
        ...
        lock.expireDt = lock.lockDt.plusHours(6);
    ```
  - `WorkLockSweepJob` → `WorkLockService.sweepExpiredLocks()` 가 만료 락을 **자동 회수(RELEASED)** 한다.
  - 그러나 `DE_IDNTF_YN='F'` 는 **외부 솔루션 수동 재비식별 후 `resolve` 호출까지** 남는다(정책상 자동 재비식별 큐 없음 → 6시간 초과가 정상 동선).
  - `LabelService.bulkUpsert` 는 `accessGuard.requireNotUnderDeidentReport(...)` 를 **호출하지 않는다**(`LabelService.java:250-258` — 락 검사만). 반면 `getByFrame:193` / `getHistory:482` 는 호출한다.

  **3차 실동작 재현 (rawSn=110 에 `DE_IDNTF_YN='F'` + 작업락 0건 상태를 만들어 확인 → 즉시 원복)**
  ```
  DB: ls_data_raw.de_ident_yn='F' (raw_sn=110),  ls_auth_work_lock LOCKED 0건

  GET  /api/v1/frames/515/labels        → 412 PRECONDITION_FAILED
       {"message":"비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요."}
  PUT  /api/v1/frames/515/labels  {"items":[]}
                                        → 200 OK, labelVersion 5→6
  DB:  select count(*) from ls_data_lbl where src_sn=515;  → 0   (기존 라벨 전량 삭제됨)
  ```
- **재현/확인 경로**
  ```sql
  -- 운영 재현 조건: 신고 접수 후 6시간이 지나 sweep 이 락을 회수했고 아직 resolve 안 된 영상
  SELECT r.raw_sn, r.de_ident_yn,
         (SELECT count(*) FROM ls_auth_work_lock l
           WHERE l.data_raw_sn = r.raw_sn AND l.lck_stts_cd='LOCKED') AS locked
    FROM ls_data_raw r
   WHERE r.de_ident_yn = 'F';
  -- locked = 0 인 행이 이 창에 들어간 영상
  ```
  ```bash
  # 해당 rawSn 의 프레임에 대해
  curl -i -X GET $B/v1/frames/{srcSn}/labels -H "Authorization: Bearer $T"   # → 412
  curl -i -X PUT $B/v1/frames/{srcSn}/labels -H "Authorization: Bearer $T" \
       -H 'Content-Type: application/json' -d '{"items":[]}'                # → 200 (전량 삭제)
  ```
- **영향**
  - **데이터 유실(HIGH)**: 저장 계약이 full-replace 인데 **조회는 412 로 막혀 있어** 작업자/FE 가 현재 라벨 세트를 읽을 수 없다. 그 상태에서 저장이 통과하므로 **불완전한(또는 빈) 세트가 전량 교체로 커밋**되어 기존 라벨이 조용히 사라진다. 실동작에서 `items:[]` 로 전량 삭제가 실제로 성공했다.
  - **정책 우회(CWE-863 — Incorrect Authorization / OWASP A10:2025 fail-open)**: 신고 게이트의 목적(재비식별 완료 전 라벨 축 접근 차단)이 시간 경과만으로 반쯤 무력화된다. 게이트가 "조회만 막고 쓰기는 연다"는 상태는 어느 정책 문서에도 없다.
  - **PII 간접 노출**: 저장 응답(`LabelResponse`)에는 저장 결과 items 의 **좌표가 그대로 실린다**. 즉 412 로 막힌 좌표를 **PUT 으로 우회 열람**할 수 있다(무변경 재전송이면 부작용 없이 조회 효과만 얻음). 라벨 좌표는 CLAUDE.md 가 "PII 위치 특정 정보"로 규정한 대상이다(CWE-359).
  - **역할 무관**: WORKER(본인 배정)·REVIEWER 모두 해당.
- **수정 방향(제안)**: 아래 중 하나. 조합이 가장 안전하다.
  1. **(권장) `LabelService.bulkUpsert` 에도 게이트를 건다** — `accessGuard.verifyAndGet` 직후, 락 검사 앞뒤 어디든 `accessGuard.requireNotUnderDeidentReport(current.getRawSn())` 추가. 게이트는 이미 인가 이후 평가되는 프리컨디션이고 `DeidentReportGate` 단일 원천이라 판정 중복이 생기지 않는다. 단 **응답 코드 정합** 검토 필요 — 저장 경로의 기존 신고 차단은 409(작업락)였으므로, 412 로 바꾸면 FE 분기가 늘어난다. 락 있으면 409·없으면 412 로 갈리는 것도 상태 오라클이 되므로 **저장 경로는 412 로 통일**을 권장.
  2. 신고 락에 **만료를 두지 않는다**(`expireDt = null`) — 신고 락만 sweep 대상에서 제외. 다만 락 누수 시 영구 차단이 되므로 resolve 경로가 유일 해제라는 점을 확인해야 한다.
  3. `sweepExpiredLocks` 회수 시 `DE_IDNTF_YN='F'` 인 RAW 의 `REASON=REDEIDENT` 락은 **만료 연장**(재획득)한다.

  ⚠ 어느 안이든 **★1 확정 정책(게이트 판정 범위 = 자기 rawSn 행 하나, 조상/자손 전파 금지)** 을 건드리지 않는다 — 본 이슈는 전파 범위가 아니라 **동일 rawSn 내 읽기/쓰기 축 비대칭**이다. ⚠ **구현은 하지 않음.**

---

### [C-ISSUE-23] TC-LABEL-30 — `pointsEqual` 손상 JSON fail-safe 전용 회귀 테스트 부재 (이월, 동작은 정상)
- **심각도**: LOW
- **기대 동작(기대효과)**: `point_cn` 이 파싱 불가한 손상값일 때 `pointsEqual` 이 **'변경됨'(false)** 으로 처리해 이력을 남겨야 한다(이력 유실 방지 fail-safe). 이 동작이 반대로 회귀하면 손상 라벨의 수정 이력·`TASK_MODIFIED` 통지가 조용히 사라진다 — 관제 동기화 요구에 직결된다.
- **현재 동작(이슈 내용)**: 동작 자체는 정상이다. 3차 실동작에서 `point_cn='{not-json'` 라벨을 재저장하니 UPDATED 이력이 before `"{not-json"` 과 함께 기록됐다(`LabelService.java:556-559, :582-584`). 그러나 이 경로를 고정하는 **전용 테스트가 없다**.
  - `LabelServiceFullReplaceIntegrationTest:277` `"손상된_diff_JSON이_있어도_조회는_200과_빈changes를_반환한다"` 는 **`LS_DATA_LBL_HSTRY.CHG_DTL_CN` 조회 폴백** 테스트로, 대상 필드도 코드 경로도 다르다.
  - `LabelServiceFullReplaceIntegrationTest` 의 레거시 포맷 테스트 2건(평탄/객체배열)은 **정상 파싱 성공** 경로만 덮는다.
  - backend 자동테스트 5,203건 전건 통과 상태에서도 이 분기는 미커버.
- **재현/확인 경로**
  ```sql
  UPDATE ls_data_lbl SET point_cn = '{not-json' WHERE lbl_sn = {lblSn};
  ```
  ```bash
  curl -X PUT $B/v1/frames/{srcSn}/labels -H "Authorization: Bearer $T" \
    -H 'Content-Type: application/json' \
    -d '{"items":[{"id":{lblSn},"lblTypeCd":"BBOX","labelId":1,"label":"broken","points":[[1,1],[10,10]]}]}'
  # 기대: LS_DATA_LBL_HSTRY 에 UPDATED 1건 (현재 정상 동작)
  ```
- **영향**: 기능 영향 없음(현재 정상). 회귀 가드 부재로 향후 `normalizePoints` 리팩터링 시 fail-safe 가 조용히 반전될 수 있고, 그 결과는 **이력·관제 통지 유실**이라 사후 발견이 어렵다.
- **수정 방향(제안)**: `LabelServiceFullReplaceIntegrationTest` 에 `"손상된_point_cn_라벨_재저장시_변경됨으로_판정되어_UPDATED_이력이_남는다"` 추가. 대조군으로 `"레거시_평탄포맷은_무변경으로_판정된다"`(이미 존재)를 같은 클래스에 두어 fail-safe 와 흡수 로직의 경계를 고정한다. ⚠ **구현은 하지 않음.**

---

# C 클러스터 part3 — TC-LABEL 중간부(라벨마스터 / 온라인 오토라벨 / 비식별 신고)

- **대상 파일**: `docs/test-cases/C-marking-labeling.md`
- **담당 라인 범위**: 124~168행 (TC-LABEL-48 ~ TC-LABEL-105), **총 45건** — 폐기 2건(TC-LABEL-70·99) 제외 **검증 대상 43건**
- **회차**: 2026-08-03 3차 / 실행일 2026-08-04 KST
- **환경**: `_raw/stack-bringup.md`(재빌드 후 5컨테이너 healthy, 외부 4연동 mock-server 실배선) + `_raw/pipeline-drive.md`(rawSn=101)
- **실동작 근거 확보 방식**: backend `localhost:18081/api` 실 HTTP 호출 + `klid-postgres` 직접 조회 + `docker logs` (backend/ai-server/mock-server). 빌드·테스트 미실행, 프로덕션 코드 미수정.
- **검증용 데이터**: 신고/해소 시나리오는 **rawSn=905·906**(APPROVED·비파생·비식별 SUCCEEDED procLog 보유)에서 수행했다. 공용 fixture rawSn=101 은 다른 파트가 신고 게이트 케이스(TC-LABEL-121~126 등)에 쓸 수 있어 **잠금·`'F'` 전이를 유발하지 않도록 의도적으로 회피**했다. 검증 종료 시 905·906 은 RESOLVED + `'Y'` + 락 해제로 원복했고, 생성한 라벨 마스터(28·29·42)·프리셋(58)은 전부 정리했다.

---

## 1. 판정 집계

| 판정 | 건수 |
|---|---|
| PASS | 42 |
| FAIL | 0 |
| PARTIAL | 1 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **검증 대상 소계** | **43** |
| 폐기(분모 제외) | 2 (TC-LABEL-70, TC-LABEL-99) |

---

## 2. 케이스별 판정

### C-2-a. 라벨 마스터 (TC-LABEL-48 ~ 55) — 8건

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-LABEL-48 | PASS | [실동작] `POST /v1/manage/labels {"sortNo":-1}` → **400** `sortNo: sortNo 는 0 이상이어야 합니다.` (`LabelMasterRequest.java:40`) |
| TC-LABEL-49 | PASS | [실동작] `PUT /v1/manage/labels/9999999` → **404** `라벨을 찾을 수 없습니다.` (`LabelMasterService.java:100-101`) |
| TC-LABEL-50 | PASS | [실동작] labelId=29 를 타 라벨명 `"QA-C3-A "` 로 수정 → **409** `이미 사용 중인 라벨 이름입니다.` / 자기 자신 동일명 재저장은 **200**(자기제외 정상, `:104-105`) |
| TC-LABEL-51 | PASS | [실동작] `DELETE /v1/manage/labels/28` → **204**. DB 재조회 시 행이 **남아 있고** `use_yn='N'`(hard delete 없음). 목록 API 에서는 미노출 |
| TC-LABEL-52 | PASS | [실동작] WORKER 토큰 `POST`/`PUT`/`DELETE` 전부 **403** (`LabelMasterController.java:82-83`, `:101`, `:117`) |
| TC-LABEL-53 | PASS | [실동작] `GET /v1/manage/labels` — WORKER **200** / PORTAL_USER **200** (`SecurityConfig.java:126` `.authenticated()`) |
| TC-LABEL-54 | PASS | [정적] `findLabelIdByDtctType` 이 null/blank 를 **repository 호출 전에** `Optional.empty()` 로 조기반환 (`LabelMasterService.java:152-154`) |
| TC-LABEL-55 | PASS | [실동작] `pg_indexes` 실조회 — `uk_ls_label_dtct_type UNIQUE (dtct_type_cd) WHERE use_yn='Y' AND dtct_type_cd IS NOT NULL` **실존**. 파생 쿼리 `Optional<LsLabel> findByDtctTypeCdAndUseYn`(`LsLabelRepository.java:84`) 가 NonUniqueResult 를 만들 수 없음 |

**★ 반증 — 라벨 마스터 ↔ 프리셋 실시간 join (지시 항목)**: 실동작으로 **성립 확인**.
```
POST /v1/manage/presets {"name":"QA-C3-JOIN","labelIds":[29]}
  → labelCodeOptions=[{labelId:29, code:null, labelName:"qa-c3-b",       labelType:"POLYGON", linked:true, polygonEnabled:true}]
PUT  /v1/manage/labels/29 {"name":"qa-c3-RENAMED","type":"BBOX", ...}   → 200
GET  /v1/manage/presets  (재조회, 프리셋 수정 없음)
  → labelCodes=["qa-c3-RENAMED"]
    labelCodeOptions=[{labelId:29, code:null, labelName:"qa-c3-RENAMED", labelType:"BBOX",    linked:true, bboxEnabled:true}]
```
프리셋에 **스냅샷이 남지 않고** 라벨명·형태(`LBL_TYPE_CD`)가 마스터에서 매 조회 시 파생됨을 확인했다(CLAUDE.md "라벨 프리셋 = 라벨 마스터 단일 진실원(V117~V119)" 준수). 비활성(soft delete) 마스터를 프리셋에 **새로 부여**하려 하면 400(`존재하지 않거나 비활성 라벨입니다: labelId=28`)으로 차단된다.

**추가 반증(우회 시도) — 모두 방어됨**
- `color`/`type` 정규식이 `^...$` 앵커라 개행 주입(`"#AABBCC\n"`, `"BBOX\n"`)으로 우회 가능한지 시험 → **400**. Bean Validation `@Pattern` 은 full-match 시맨틱이라 CWE-625(부적절한 정규식 앵커) 성립 안 함.
- `sortNo` 미지정(null) → 201 + `sortNo=0` 으로 저장. `@Min` 이 null 허용이며 엔티티 기본값 0. 정렬 표시값이라 무해 — 결함으로 집계하지 않음.

---

### C-2-b. 온라인 오토라벨 (TC-LABEL-60 ~ 80) — 21건 중 폐기 1건(TC-70) 제외 20건

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-LABEL-60 | **PARTIAL** | [실동작] `POST /v1/frames/468/autolabel`(배정 WORKER) → **200** `{"srcSn":468,"detectedCount":0,"savedCount":0,"labels":[]}`. **미저장 정책은 실증됨**(40+회 호출 후에도 `ls_data_lbl` 신규 행 0). 그러나 ai-server 가중치 미탑재(`reason=weights_missing`)로 **좌표를 실제로 반환하는 정상 경로는 미검증** → C-ISSUE-41(1차 C-ISSUE-44 이월) |
| TC-LABEL-61 | PASS | [실동작] WORKER 2001 이 미배정 프레임(srcSn=507, raw 112) 호출 → **403** `본인에게 배정되지 않은 영상입니다.` (`AutolabelOnlineService.java:210`) |
| TC-LABEL-62 | PASS | [실동작] 신고로 잠긴 rawSn=905 의 srcSn=459 호출 → **409** `작업이 잠긴 영상입니다.` (`:413-416`, 작업락 판정이 신고게이트보다 먼저) |
| TC-LABEL-63 | PASS | [실동작] 동일 srcSn=468 **12병렬** → 1×200 / **11×409** `이미 오토라벨링이 진행 중인 프레임입니다.` 직후 단건 재요청 **200**(finally 락해제 확인, `:217-219`·`:278-280`) |
| TC-LABEL-64 | PASS | [실동작] `{"classes":["dog","cat"]}`(전부 미매핑) → 200 + `detectedCount:0` + `검출할 수 있는 라벨이 없습니다…` 메시지. backend 로그 `[Autolabel] no mapped detect classes … requested=2` 이후 **ai-server 인바운드 없음**(`/infer/yolo/track` 로그 부재) |
| TC-LABEL-65 | PASS | [실동작] `{"classes":["person","hack","dog"]}` → 로그 `drop unmapped detect class=hack` / `=dog` 2줄, person 만 ai 전달 |
| TC-LABEL-66 | PASS | [실동작] **강한 반증 성립** — `dog` 는 COCO 80 allowlist 에 있는 **유효 클래스**인데도 마스터 `DTCT_TYPE_CD` 미매핑이라 drop 됐다. 즉 화이트리스트 축이 COCO 목록이 아니라 **마스터 매핑**이며 FE 요청을 신뢰하지 않음(`:459-478`) |
| TC-LABEL-67 | PASS | [실동작] mock 응답 시 **좌표 미반환** + `message="AI 모델 미로드 — 결과 신뢰 불가"`. **우회 경로 없음**을 별도 확인: 판정이 `AiMockMeta.untrusted(mock, source)` 의 **긍정 증명**(`source=="model"` 명시 필요)이라 mock 메타 **필드 생략** 응답도 fail-closed. 역직렬화는 canonical 생성자를 타므로 `source=null` → untrusted. `shape=POLYGON` 도 mock 분기(`:247-252`)가 형태 분기(`:268`)보다 앞이라 우회 불가 |
| TC-LABEL-68 | PASS | [정적+테스트] `DetectionBoxNormalizer.java:51-53` 개수≠4 → IAE → `AutolabelOnlineService.java:569-572` 에서 all-or-nothing 400. 회귀 테스트 `DetectionBoxNormalizerTest.좌표개수가_4개가_아니면_거부한다` · `AutolabelOnlineServiceTest.형식위반은_여전히_all_or_nothing_하나라도_비정상이면_전부_미반환` (baseline 전건 통과) |
| TC-LABEL-69 | PASS | [정적+테스트] `:54-58` `!Double.isFinite(v)` 가드가 clamp **이전**에 위치. 테스트 `NaN_Infinity_좌표는_거부한다_역직렬화_500_회귀방지`, `ai_응답_좌표_NaN이면_INVALID_INPUT`, `…Infinity면_INVALID_INPUT` |
| ~~TC-LABEL-70~~ | 폐기 | 분모 제외 (`validateBbox` 부재 재확인 — `grep validateBbox` 결과 0건) |
| TC-LABEL-71 | PASS | [정적+테스트] `:259-265` 빈 검출 시 `reCheckLock` 후 즉시 반환. 테스트 `YOLO_박스0개면_SAM호출없이_빈결과_반환한다` |
| TC-LABEL-72 | PASS | [정적+테스트] `:297-302` `limit=min(detected,maxBoxes)`·`truncated`. 테스트 `박스_개수가_상한을_초과하면_상한까지만_처리하고_message로_고지한다` |
| TC-LABEL-73 | PASS | [정적+테스트] `:304`·`:314-320` wall-clock 예산. 테스트 `폴리곤_예산소진시_잔여박스_잘라_message_고지`(테스트용 `polygonTotalBudget` 필드로 분기 발화) |
| TC-LABEL-74 | PASS | [정적+테스트] `:321-350` 박스별 try/catch 스킵 + `:372-389` 안내 조립(비-mock 실패도 `skipped>0` 으로 고지). 테스트 `일부_박스_SAM실패시…`, `일부_박스_mock이면…`, `전량_mock이면_빈결과와_신뢰불가_안내` |
| TC-LABEL-75 | PASS | [정적+테스트] `:340-345` `TOO_MANY_REQUESTS` 만 재던짐(스킵 흡수 안 함) + `:531-535`. 테스트 `폴리곤_배치중_bulkhead429는_즉시_전파되고_삼켜지지_않는다` |
| TC-LABEL-76 | PASS | [정적+테스트] 루프 진입마다 `requireNotBlocked`(`:313`) + 응답 조립 직전 `reCheckLock`(`:354`). 테스트 `폴리곤_배치중_작업락걸리면_409로_차단한다`, `AI호출_완료_후_작업락이_걸리면_409로_차단하고_좌표를_반환하지_않는다` |
| TC-LABEL-77 | PASS | [실동작] 서로 다른 40 프레임 동시 호출(REVIEWER) → **16×429** `TOO_MANY_REQUESTS`, backend 로그 `[Autolabel] bulkhead full — reject srcSn=…`. `resilience4j.bulkhead.instances.aiOnline.max-concurrent-calls=4`, `max-wait-duration=0`(fail-fast) 실효 확인 |
| TC-LABEL-78 | PASS | [정적+테스트] `:506-511` `RuntimeException → EXTERNAL_API_ERROR` + `LogSanitizer` 로 메시지 정제(스택·경로 미노출). 테스트 `AI_실패시_502이며_inflight_락이_해제된다` |
| TC-LABEL-79 | PASS | [실동작] `confThreshold=0.9` → **400** `인식 민감도는 0.80 이하여야 합니다.` / `0.20` 도 400(하한 0.25) |
| TC-LABEL-80 | PASS | [실동작] `classes` 101개 → **400** `클래스는 최대 100개까지 지정할 수 있습니다.` |

---

### C-2-c. 비식별 누락 신고 / 해소 (TC-LABEL-90 ~ 105) — 16건 중 폐기 1건(TC-99) 제외 15건

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-LABEL-90 | PASS | [실동작] rawSn=905(APPROVED·비파생·비식별 `'Y'`)에 `POST /v1/labels/459/deident-report` → **201**(rprtSn=31). 부수효과 전건 DB 실측 — 아래 표 참조 |
| TC-LABEL-91 | PASS | [실동작] `reason:""` → 400 / `reason:"   "` → 400 (`@NotBlank` + 서비스 백스톱 `:161-166`) |
| TC-LABEL-92 | PASS | [실동작] 1001자 → **400** `신고 사유는 1000자 이하여야 합니다.` / **1000자 경계 → 201**(`DeidentReportRequest.java:19`) |
| TC-LABEL-93 | PASS | [실동작] WORKER 2001 이 미배정 srcSn=459 신고 → **403**. 영상이 이미 잠긴 상태였는데도 **409 가 아니라 403** 이 먼저 나옴 = 인가가 프리컨디션보다 앞 |
| TC-LABEL-94 | PASS | [실동작] 잠긴 rawSn=905 에 재신고(srcSn=460) → **409** `이미 비식별 재처리 중인 영상입니다.` |
| TC-LABEL-95 | PASS | [정적+실동작] `DeidentReportService.java:182-183` 이 `VideoRepository.findByRawSnForUpdate`(`@Lock(PESSIMISTIC_WRITE)`, `VideoRepository.java:39-41`) 호출. 동시 신고 6건이 **직렬화**되어 1건만 통과(아래 TC-96)한 것이 잠금 실효의 실측 증거 |
| TC-LABEL-96 | PASS | [실동작] rawSn=906 에 6병렬 신고 → **1×201 / 5×409**. DB: `ls_deident_report` **1행**, `ls_auth_work_lock(LOCKED)` **1행**, `de_ident_yn='F'`. 최후 방어인 부분 유니크 인덱스 `ux_ls_auth_work_lock_raw_active (data_raw_sn) WHERE lck_target_cd='RAW' AND lck_stts_cd='LOCKED'` 실존 확인 |
| TC-LABEL-97 | PASS | [실동작] 신고 직전 3프레임(459·460·461)에 3필드 `Y/N/N` 세팅 + 영상 축도 세팅 → 신고 후 **프레임 5건 전부 NULL**, **영상 축도 NULL**. 로그 `privacyReset=5 privacyResetAudited=3 videoPrivacyReset=true`(감사 대상은 실제 값이 있던 3건만) |
| TC-LABEL-98 | PASS | [실동작] APPROVED 영상이라 `TaskModifiedEvent(META_UPDATED)` 발행 확인 — `[ControlNotifyDebounce] flush rawSn=905 regen=true frames=459=[META_UPDATED, LABEL_ADDED],460=[META_UPDATED, LABEL_ADDED],461=[META_UPDATED]`. ⚠ 이어지는 재export 는 `[DatasetExport] export blocked — deident report open rawSn=905` 로 보류되어 **outbound HTTP 통지는 resolve 이후로 지연**된다 — 확정 정책("통지는 export 성공 후 발송")대로이며 결함 아님. 카탈로그에 이 사실을 명시 보강했다 |
| ~~TC-LABEL-99~~ | 폐기 | 분모 제외 (신고 경로에 라벨 스냅샷/삭제 분기 부재 재확인 — `report`/`doReport` 본문에 삭제 코드 0건) |
| TC-LABEL-100 | PASS | [실동작] Authorization 헤더 없음 → **401**, 잘못된 토큰 → **401** |
| TC-LABEL-101 | PASS | [실동작] `POST /v1/deident-reports/99999999/resolve` → **404** `신고를 찾을 수 없습니다.` |
| TC-LABEL-102 | PASS | [실동작] WORKER 2001 이 rawSn=905 신고(rprtSn=31) resolve → **403** |
| TC-LABEL-103 | PASS | [실동작] rprtSn=31 을 성공 resolve 한 뒤 재호출 → **409** `이미 처리된 신고입니다.` |
| TC-LABEL-104 | PASS | [실동작] **3변종 전부 409 + fail-closed** — ①procLog 경로 blank ②18바이트 스텁(`MOCK_DEIDENTIFIED\n`) ③SUCCEEDED procLog 자체 부재(FAILED 로 변경). 세 경우 모두 `report_stts_cd=OPEN`·`lck_stts_cd=LOCKED`·`de_ident_yn='F'` 유지. 판정은 `DeidentArtifactIntegrity.isValidVideoArtifact`(`MIN_VIDEO_BYTES=512` + 컨테이너 시그니처) 단일 지점 |
| TC-LABEL-105 | PASS | [실동작] rawSn=906(procLog `req_dt=2026-08-01 23:52`, 파일 mtime `2026-08-01 23:44`, 신고 `2026-08-04 01:02`) → **409**. 파일 `touch` 후 재시도 → **200**. 대조군 rawSn=905 는 fixture 파일 mtime 이 `2030-01-01`(미래)이라 시간조건 통과 → 200 |

#### TC-LABEL-90 부수효과 실측표 (rawSn=905)

| 항목 | 신고 전 | 신고 후 | 판정 |
|---|---|---|---|
| `ls_data_lbl`(3프레임 합) | 3 | **3** | 라벨 보존 ✅ |
| `ls_label_version` | 0 | **0** | 스냅샷 미생성 ✅ |
| `ls_data_raw.de_ident_yn` | Y | **F** | ✅ |
| `ls_data_raw.data_stts_cd` | COMPLETED | **COMPLETED** | 배치 상태 역행 없음 ✅ |
| `ls_auth_work_lock(LOCKED)` | 0 | **1** | 작업락 ✅ |
| 프레임 3필드(459·460·461) | Y/N/N | **NULL** | 리셋 ✅ |
| `ls_data_raw` 3필드 | Y/N/N | **NULL** | 영상 축 리셋 ✅ |
| `ls_data_lbl_hstry` | 2 | **5**(+3) | 프레임당 1행 감사 ✅ |
| `ls_task_event_log` | — | `PRIVACY_META_RESET rawSn=905 actor=1001 rsn="… rprtSn=31"` | 영상 축 감사 ✅ |
| `GET /v1/frames/459/labels` | 200 | **412** | 신고 게이트 ✅ |
| resolve 후 라벨 재조회 | — | **200 · 라벨 3건 그대로** | 보존 라벨 재사용 ✅ |

---

## 3. 이슈 (C-ISSUE-41 ~)

### [C-ISSUE-41] TC-LABEL-60(및 68~76 파생) — ai-server 모델 가중치 미탑재로 검출 의존 분기가 여전히 실환경에서 한 번도 실행되지 않는다 (1차 C-ISSUE-44 **미해소 이월**)
- **심각도**: MEDIUM (검증 커버리지 결함 — 프로덕션 코드 결함 아님)
- **기대 동작(기대효과)**: 온라인 오토라벨의 핵심 위험 구간(좌표 clamp/퇴화 스킵/형식 위반 400, 폴리곤 상한·예산·부분실패·TOCTOU)은 **실제 ai-server 검출 응답 위에서** 판정돼야 한다. 이 프로젝트의 실패 모드는 "코드가 있으니 PASS"이므로, 검출이 0건이면 그 뒤 분기는 반증 시도 자체가 불가능하다. 특히 ai-server 응답 스키마(필드명·좌표 순서·`source`/`mock` 메타)의 **계약 드리프트**는 단위테스트(mock 주입)로는 절대 잡히지 않는다.
- **현재 동작(이슈 내용)**: `docker exec klid-ai-server ls /app/weights` → 0 files. 모든 온라인 오토라벨 호출이 mock 으로 폴백한다.
  ```
  backend  : [Autolabel] mock response — skip detection srcSn=468 source=mock reason=weights_missing
  ai-server: WARNING:app.routers.yolo:[DETECT:yolox][MOCK] returning mock track … reason=weights_missing
  응답     : {"srcSn":468,"detectedCount":0,"savedCount":0,"labels":[]}  message="AI 모델 미로드 — 결과 신뢰 불가"
  ```
  BE 는 mock 을 정상 차단하므로(`AutolabelOnlineService.java:247-252`, TC-67 PASS) **그 뒤에 있는** `normalizeDetections`(`:257`)·빈검출 분기(`:260`)·`polygonAutolabel`(`:268-269`) 전부가 도달 불가 코드가 된다.
- **재현/확인 경로**: `curl -X POST localhost:18081/api/v1/frames/468/autolabel -H "Authorization: Bearer <WORKER>" -d '{}'` → 항상 `detectedCount:0`. `docker logs klid-ai-server | grep weights_missing`.
- **영향**: 기능/검증. TC-LABEL-60·68·69·71~76 **7건이 단위테스트 커버리지만으로 PASS** 판정된다(본 회차 판정에도 그대로 반영). 실 ai-server 응답 계약 회귀 감지력 0.
- **수정 방향(제안)**: ①`docker-compose.local.yml` 에 YOLOX ONNX 가중치 마운트/다운로드 스텝 추가, 또는 ②mock 플래그 없이 **결정적 고정 좌표**를 돌려주는 검증 전용 ai-server 스텁 모드 도입(그래야 BE 의 mock 차단을 통과해 하류 분기를 실제로 태울 수 있다). ⚠ 구현하지 않음.

### [C-ISSUE-42] TC-LABEL-60/72 인접 — `findLabelIdByDtctType` 를 검출 건수만큼 반복 호출(N+1) (1차 C-ISSUE-42 **미해소 이월**)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 라벨링 화면 툴바에서 프레임마다 호출되는 대화형 경로다. `rules/performance.md` 는 반복 단건 조회를 금지한다. 검출 N건이어도 매핑 조회는 1회로 끝나야 한다. 이 서비스는 스스로 "**비트랜잭셔널** — AI 블로킹 호출이 control HikariCP 커넥션을 점유하지 않도록"(`AutolabelOnlineService.java:48-50`)이라고 선언하는데, 응답 조립 단계에서 커넥션을 N회 재획득하면 그 의도를 부분 상쇄한다.
- **현재 동작(이슈 내용)**: 1차 지적 이후 코드 변경 없음.
  ```java
  // AutolabelOnlineService.java:425-433  toItems (BBOX)
  for (YoloResponse.Detection d : detections) {
      Long labelId = labelMasterService.findLabelIdByDtctType(d.label()).orElse(null);   // ← 검출 1건마다
  // AutolabelOnlineService.java:336      polygonAutolabel (POLYGON) — 박스마다 동일 호출
      Long labelId = labelMasterService.findLabelIdByDtctType(d.label()).orElse(null);
  ```
  `findLabelIdByDtctType` 는 `@Transactional(readOnly=true)`(`LabelMasterService.java:150-157`)라 호출마다 트랜잭션·커넥션 획득 + `SELECT … WHERE dtct_type_cd=? AND use_yn='Y'` 1회가 발생한다. 캐시 없음. 같은 요청에서 이미 `mappedDetectClasses()`(`:460`)로 **전체 매핑을 읽었는데도** 재사용하지 않는다.
- **재현/확인 경로**: 가중치 탑재된 ai-server 로 `POST /v1/frames/{srcSn}/autolabel` 후 `hibernate.SQL` DEBUG 로 `ls_label` 조회 횟수 = 검출 건수인지 확인. 현 스택은 검출 0건이라 실측 불가(C-ISSUE-41 종속, 정적 판정).
- **영향**: 성능/자원(커넥션 풀). BBOX 는 검출 수 상한이 없어 혼잡 프레임이면 수십~수백 회, POLYGON 은 `maxBoxes`(최대 100)까지.
- **수정 방향(제안)**: `mappedDetectClasses()` 를 `Map<String,Long>`(COCO코드→labelId) 반환으로 확장하거나 `LsLabelRepository.findByDtctTypeCdInAndUseYn(...)` 로 1회 일괄 조회 후 루프에서 맵 조회. ⚠ 구현하지 않음.

### [C-ISSUE-43] TC-LABEL-100~103 인접 — `resolveManually` 가 미인가자에게 **신고 존재 여부를 403/404 로 흘린다** (같은 서비스의 `reportByVideo` 와 정반대)
- **심각도**: LOW
- **기대 동작(기대효과)**: 같은 서비스가 영상 단위 신고 진입점에서는 이 원칙을 **명시적으로 코드 주석에 못박고 지키고 있다** — `DeidentReportService.java:154`: *"인가 — 영상 단위(IDOR, CWE-639). **영상 조회보다 먼저 평가해 미인가자에게 존재 여부를 흘리지 않는다.**"* resolve 도 동일 축(rawSn 기반 `verifyRawAccess`)이므로 같은 규칙이 적용돼야 한다.
- **현재 동작(이슈 내용)**: resolve 는 **조회 → 인가** 순서라 응답 코드가 존재 여부 오라클이 된다.
  ```java
  // DeidentReportService.java:385-389
  LsDeidentReport report = reportRepository.findById(rprtSn)
          .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "신고를 찾을 수 없습니다."));  // ← 인가 이전에 404
  accessGuard.verifyRawAccess(report.getRawSn(), actor);                                          // ← 그 다음 403
  ```
  **실측(WORKER 2001 토큰, 본인 배정 아님)**
  ```
  POST /v1/deident-reports/31/resolve        → 403   (신고가 존재함을 알려줌)
  POST /v1/deident-reports/99999999/resolve  → 404   (존재하지 않음을 알려줌)
  대조군 — 영상 단위 신고 진입점(주석대로 동작):
  POST /v1/videos/905/deident-report         → 403
  POST /v1/videos/99999999/deident-report    → 403   (구분 불가 ✅)
  ```
- **재현/확인 경로**: 위 4줄 curl(WORKER 토큰).
- **영향**: 보안(정보 노출) — CWE-209 / OWASP API1:2023. 인증된 WORKER 가 `rprtSn` 을 순회해 **다른 작업자 영상의 비식별 신고 발생 여부·PK 분포**를 열거할 수 있다. 신고 존재 자체가 "그 영상에 개인정보 노출이 있었다"는 민감 신호다. 실질 피해는 낮으나(본문·rawSn 미노출) 같은 파일 안에서 규칙이 갈라져 있는 것 자체가 회귀 위험이다.
- **수정 방향(제안)**: `resolveManually` 도 존재/미존재를 **같은 코드로 수렴**시킨다 — 조회 실패와 인가 실패를 모두 404 로 내거나(리소스 은닉), 최소한 `reportByVideo` 와 동일하게 인가 실패를 403 으로 통일. 어느 쪽을 택하든 **카탈로그 TC-LABEL-101(404)·102(403) 기대값을 함께 갱신**해야 한다(현 카탈로그는 지금 동작을 정본으로 적고 있어 그대로 두면 다음 회차에 "회귀"로 오판된다). ⚠ 구현하지 않음.

### [C-ISSUE-44] TC-LABEL-53 인접 — `GET /v1/manage/labels/detect-candidates`(AI 탐지 후보)가 **PORTAL_USER 에게도 열려 있다** + 카탈로그 미수록
- **심각도**: LOW
- **기대 동작(기대효과)**: 포털은 오토라벨링·SAM2·VLM 미제공이 확정 정책이다(ADR-013, CLAUDE.md "포털(외부 채널)"). 이 엔드포인트는 스스로 *"라벨링 화면 'AI 탐지' 팝업이 소비한다"*(`LabelMasterService.java:57`)고 선언하는 **내부 전용** 조회다. 같은 클러스터의 `/v1/frames/{srcSn}/deid-image` 는 정확히 이 이유로 `hasAnyRole('REVIEWER','WORKER')` 로 좁혀 놓았고(TC-LABEL-143 이 그 비대칭을 **의도된 것**으로 명시), 그 판단 기준을 그대로 적용하면 여기도 내부 역할로 좁혀야 한다.
- **현재 동작(이슈 내용)**: `GET /v1/manage/labels/**` 가 통째로 `.authenticated()` 라 나중에 추가된 하위 경로가 자동으로 포털에 노출됐다.
  ```java
  // SecurityConfig.java:126
  .requestMatchers(HttpMethod.GET, "/v1/manage/labels", "/v1/manage/labels/**").authenticated()
  // LabelMasterController.java:69-72  — 메서드 @PreAuthorize 없음
  @GetMapping("/detect-candidates")
  public ApiResponse<List<DetectCandidateResponse>> detectCandidates() { … }
  ```
  **실측**: PORTAL_USER 토큰 `GET /v1/manage/labels/detect-candidates` → **200**(활성 라벨 전체 + COCO 매핑 여부 `mapped`). WORKER → 200(정상).
- **재현/확인 경로**: `curl -s localhost:18081/api/v1/manage/labels/detect-candidates -H "Authorization: Bearer <PORTAL_USER>"` → 200.
- **영향**: 보안(정보 노출, CWE-200) — 외부 채널 사용자가 내부 AI 검출 매핑 구성(어떤 라벨이 어떤 COCO 클래스에 물려 있는지)을 열람한다. PII 는 없으나 내부 파이프라인 구성 노출이며 ADR-013 경계를 침범한다. 부수적으로 **카탈로그 결함**: 이 엔드포인트를 다루는 케이스가 C 클러스터에 한 건도 없다(TC-LABEL-53 은 `list()` 만 다룬다).
- **수정 방향(제안)**: ①`detectCandidates()` 에 `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 추가(`/deid-image` 와 동일 패턴), 또는 SecurityConfig 의 GET 예외를 `/v1/manage/labels` 와 `/v1/manage/labels/*/attrs` 로 **좁혀서** 열거. ②카탈로그에 `detect-candidates` 권한 케이스 신설. ⚠ 구현하지 않음(카탈로그 신설은 내 담당 라인범위 밖이라 미반영 — 병합 담당자 처리 필요).

### [C-ISSUE-45] TC-LABEL-90/91 — Swagger 설명이 **폐기된 "라벨 삭제" 정책을 계속 광고**하고 412 응답을 문서화하지 않는다 (1차 C-ISSUE-123 **미해소 이월** + 신규 하위 결함)
- **심각도**: LOW
- **기대 동작(기대효과)**: 2026-07-27 사용자 확정으로 비식별 신고는 **라벨을 보존**한다(구 "스냅샷 후 전량 삭제" 폐기). 이 API 를 소비하는 FE·관제·외부 통합자가 보는 OpenAPI 설명이 실제 동작과 일치해야 한다(CLAUDE.md 문서 동기화 규칙).
- **현재 동작(이슈 내용)**: 서비스 본체는 정책 반전이 반영됐는데(실동작 실측: 라벨 3건 보존, `LS_LABEL_VERSION` 0건, 로그 `labelsPreserved=true`) **컨트롤러 Swagger 문구는 1차 지적 이후 그대로**다.
  ```java
  // DeidentReportController.java:48   @Tag(description = …)
  "복원 가능 스냅샷 기록 후 삭제한다. 재처리는 외부 솔루션 수동 비식별화 흐름으로 진행되며, "
  // DeidentReportController.java:99   @Operation(description = …)  POST /v1/labels/{srcSn}/deident-report
  "신고 시 해당 영상 전체 라벨을 복원 가능 스냅샷으로 기록 후 삭제하고 영상을 잠근다. "
  ```
  **추가로 발견(신규)**: srcSn 경로의 `@ApiResponses`(`:103-110`)에 **412 가 없다**. 실제로는 파생영상(`requireReportableVideo`)·비식별 미수행(`requireDeidentAttempted`) 두 프리컨디션이 412 를 낸다(TC-LABEL-131). 영상 단위 진입점(`:107-111`) 설명은 최신 정책으로 갱신돼 있어 **같은 컨트롤러 안에서 두 엔드포인트 설명이 서로 모순**된다.
- **재현/확인 경로**: `GET /api/v3/api-docs` 또는 Swagger UI 의 `DeidentReport` 태그 · `POST /v1/labels/{srcSn}/deident-report` 설명.
- **영향**: API 계약 오도. 소비자가 "신고하면 라벨이 사라진다"고 가정해 불필요한 방어 로직을 넣거나, 412 를 처리하지 않아 FE 가 알 수 없는 오류로 처리한다.
- **수정 방향(제안)**: `@Tag(description)`(`:45-49`)과 srcSn 경로 `@Operation(description)`(`:97-102`)을 현행 정책으로 교체(라벨 **보존** + 신고 구간 라벨 조회 412 + resolve 시 자동 해제 + 부수효과 5종), `@ApiResponses` 에 412 추가. 이미 최신인 영상 단위 진입점 문구를 기준으로 정렬하면 된다. ⚠ 구현하지 않음.

---

## 4. 카탈로그 정정 (담당 라인범위 124~168행 내에서만 수행) — **23행 정정**

VERIFY-PROMPT §5-2 에 따라 근거 `file:line` 을 전부 Read 로 대조했다. 서비스 파일 상단 확장으로 **후반부에 계통적 +27~28행 드리프트**가 있었다.

| 행 | ID | 정정 전 | 정정 후 |
|---|---|---|---|
| 135 | TC-LABEL-63 | `AutolabelOnlineService.java:217-219, :277-279` | `:217-219, :278-280` |
| 136 | TC-LABEL-64 | `:228-235, :457-461` | `:228-235, :459-463` |
| 137 | TC-LABEL-65 | `:465-475` | `:467-476` |
| 138 | TC-LABEL-66 | `:457-476` | `:459-478` |
| 143 | TC-LABEL-71 | `:258-264` | `:259-265` |
| 144 | TC-LABEL-72 | `:296-301` | `:297-302` |
| 146 | TC-LABEL-74 | `:321-348, :370-380` | `:321-350, :372-389` |
| 147 | TC-LABEL-75 | `:341-346, :531-535` | `:340-345, :531-535` |
| 148 | TC-LABEL-76 | `:308-312, :352-353` | `:310-313, :353-354` |
| 153 | TC-LABEL-90 | `DeidentReportService.java:118-126, :169-257` | `:122-130, :173-285` |
| 154 | TC-LABEL-91 | `:158-162` | `:161-166` |
| 156 | TC-LABEL-93 | `:122` | `:126` |
| 157 | TC-LABEL-94 | `:189-191` | `:193-195` |
| 158 | TC-LABEL-95 | `:178-180` | `:182-183` |
| 159 | TC-LABEL-96 | `:237-241` | `:264-268` (**+27**) |
| 160 | TC-LABEL-97 | `:218-219` | `:222-223, :237-246` + **영상 축 리셋·`LS_TASK_EVENT_LOG` 감사 사실 보강** |
| 161 | TC-LABEL-98 | `:230-233` | `:257-260` (**+27**) + **"이벤트 발행 ≠ outbound 발송, 신고 구간에는 export 보류로 통지 지연" 명시 보강** |
| 163 | TC-LABEL-100 | `:354-356` | `:382-384` (**+28**) |
| 164 | TC-LABEL-101 | `:357-358` | `:385-386` (**+28**) |
| 165 | TC-LABEL-102 | `:361` | `:389` (**+28**) |
| 166 | TC-LABEL-103 | `:364-366` | `:392-394` (**+28**) |
| 167 | TC-LABEL-104 | `:542-581` | `:570-609 · DeidentArtifactIntegrity.java:85-101` (**+28** + 판정 단일지점 근거 추가) |
| 168 | TC-LABEL-105 | `:504, :563-580` | `:532, :591-608` (**+28**) |

> 폐기 행(TC-LABEL-70·99)의 "현재 부재" 표기는 `grep` 으로 재확인해 **유효**했으므로 그대로 두었다.
> 다른 파트 담당 구간(75~123행·169~222행)은 **일절 수정하지 않았다.**

---

## 5. 이전 회차(2026-08-01 1차) 이슈 해소 여부 — 본 파트 범위

| 1차 이슈 | 대상 | 3차 상태 |
|---|---|---|
| C-ISSUE-42 (N+1 `findLabelIdByDtctType`) | TC-LABEL-60/72 | **미해소** — 코드 무변경(`:336`, `:428`). 본 회차 **C-ISSUE-42** 로 재기록 |
| C-ISSUE-44 (ai-server 가중치 미탑재) | TC-LABEL-60/68/69/71~76 | **미해소** — `weights` 디렉터리 여전히 0 files. 본 회차 **C-ISSUE-41** 로 재기록 |
| C-ISSUE-123 (Swagger 라벨삭제 문구 드리프트) | TC-LABEL-90/91 | **미해소** — `DeidentReportController.java:48`·`:99` 문구 그대로. 본 회차 **C-ISSUE-45** 로 재기록(412 미문서화 신규 하위결함 추가) |
| C-ISSUE-21 (좌표 null 원소 NPE) | TC-LABEL-15 | 담당 범위 밖(75~123행 파트) — 본 파트 미판정 |
| C-ISSUE-61 (`GET /v1/deident-reports` 정렬 500) | TC-LABEL-108/109 | 담당 범위 밖(169~222행 파트). 단 `DeidentReportController.java:83-84` 에 `@Pattern(status)` + `A-ISSUE-61` 주석이 들어와 있어 **정렬 allowlist 배선이 진행된 흔적**은 확인됨(판정은 해당 파트 소관) |

---

## 6. 확정 정책 대조 (재보고 금지 항목 준수 확인)

- **★1 신고 게이트 = 자기 rawSn 행 하나** — 본 파트에서 조상/자손 전파를 결함으로 보고하지 않았다. rawSn=905 신고가 다른 영상에 영향을 주지 않는 것을 실측(`ls_data_raw` 다른 행 `de_ident_yn` 불변)했고, 이를 정상으로 판정했다.
- **★3 좌표 검증 2축(사용자 저장=400 / AI 응답=clamp+퇴화 스킵)** — TC-68/69(형식 위반만 400)와 TC-136~138(clamp)의 비대칭을 **결함으로 보고하지 않았다**. `AutolabelOnlineService.java:583-600` 의 "BBOX=clamp / SAM 폴리곤=거부" 비대칭도 코드 주석의 근거대로 정합 상태로 판정.
- **★4 라벨명 = 마스터 등록명 그대로** — 프리셋 join 반증에서 `qa-c3-RENAMED`(영문) 가 그대로 노출되는 것을 **정상**으로 판정했다.
- 자체 채움(self-fill) 점검 — 온라인 오토라벨은 mock 응답 시 좌표를 **만들어 내지 않고** 0건을 반환한다(`detectedCount:0`, DB write 0). self-fill 결함 없음.

---

## 7. 환경 원복 확인

| 대상 | 조치 | 최종 상태 |
|---|---|---|
| rawSn=905 | 신고(rprtSn=31) → resolve | `RESOLVED` / `de_ident_yn='Y'` / 락 0건 / 라벨 3건 보존 |
| rawSn=906 | 신고(rprtSn=33) → procLog 변조 3회 → 원복 → resolve | `RESOLVED` / `de_ident_yn='Y'` / 락 0건, `proc_log_sn=65` 경로·상태 원복 완료 |
| `stub.mp4`(임시 18B) | 삭제 | 부재 |
| 라벨 마스터 28·29·42 | soft delete | `use_yn='N'`(hard delete 불가 — 설계상 정상) |
| 프리셋 58(QA-C3-JOIN) | 삭제 | 부재. 타 파트 생성분 `QA3RD-PRESET-TEST` 는 **미접촉** |
| rawSn=101(공용 fixture) | **미접촉** | `de_ident_yn='Y'`, 신고 0건 |

---

# C 클러스터 part4 — C-2. TC-LABEL 뒷부분 (파일 169~212행 = TC-LABEL-106 ~ 149, 44건)

> 담당 범위: `docs/test-cases/C-marking-labeling.md` **169~222행**. 실제 케이스 행은 **169~212행(TC-LABEL-106~149, 44건)**
> 이고 213~222행은 `no-store 5경로` 주석 블록 + 구분선 + `## C-3` 헤딩이다(케이스 아님).
> 검증일 2026-08-04 KST(3차) · 스택: `_raw/stack-bringup.md` 기준 재빌드된 HEAD 이미지(Flyway v163) · 전 케이스 실동작 우선.

## 사용한 검증 데이터 (다른 파트와 분리 확보)

| 용도 | rawSn | srcSn | 상태 |
|---|---|---|---|
| 신고/해소 주 시나리오 | **115** | 508·509·510 | `Y`→신고→`F`→resolve→`Y`, WORKER 2001 배정(assignment 76), 워크플로 `ASSIGNED` |
| rawSn 진입점 신고(TC-127) | **112** | 507 | `Y`→신고→`F`→resolve→`Y` (복구 완료) |
| 파생영상 412(TC-128/131) | **88**(부모 900) · **98**(부모 906) | 439 · 466 | `ORGNL_RAW_SN` non-null |
| 비식별 미수행 412(TC-129/131) | **901** | 434 | `DE_IDNTF_YN='N'` |
| 게이트 자기행 판정(TC-123) | **88**(부모 900=`'F'`) | 439 | 파생은 `'Y'` |
| bounds 미상(TC-117)·서브트리(TC-145/146) | **110** | 499·502 | 검증 후 원상복구 |
| 락 없는 `'F'`(TC-135b) | **110** | 499 | SQL 로 `'F'` 세팅 후 원복 |

> 검증용 DB 데이터 조작(개인정보 3필드 세팅, 프레임 경로 임시 변경, `de_ident_yn` 임시 변경)과 심링크 생성은
> **모두 원상복구**했다. 프로덕션 코드·설정·마이그레이션은 일절 수정하지 않았다.
> ⚠ 검증 중 생성한 신고 32(rawSn 115)·35(rawSn 112)는 정상 resolve 로 종결했고, rawSn 900 에 잘못 접수된 신고 34 는
> 원상태(`'F'` + 락 없음 + 신고 없음)로 복구했다.

---

## 판정 결과

| ID | 케이스명 | 판정 | 근거 확인 |
|---|---|:--:|---|
| TC-LABEL-106 | resolve 정상+'F'→'Y' 복원 | PASS | [실동작] `POST /v1/deident-reports/32/resolve`(WORKER 2001) → 200. DB: `ls_data_raw.de_ident_yn='F'→'Y'`, `ls_deident_report.report_stts_cd='RESOLVED'`, `resolved_dt=01:04:45.707`, 락 `work_lock_sn=36 → RELEASED/MANUAL_DEIDENT_DONE`. 로그 `[DeidentReport] resolved-manually rprtSn=32 rawSn=115 actor=2001`. ★근거 드리프트: `:374-391` → 실제 `:402-419` |
| TC-LABEL-107 | resolve 배치상태 역행 금지 | PASS | [실동작] resolve 전후 `ls_data_raw.data_stts_cd` **COMPLETED 불변**(01:02 신고→01:04 resolve 양쪽 조회). 코드 `DeidentReportService.java:412-419` 주석·구현이 `markDeidentified("Y")` 만 수행하고 `DATA_STTS_CD` 미변경. ★드리프트 `:384-391`→`:412-419` |
| TC-LABEL-108 | listReports status allowlist | PASS | [실동작] `?status=X` → **400** `"list.status: status 는 OPEN/RESOLVED/DISMISSED 만 허용됩니다."`. `?status=resolved`(소문자)도 **400**(컨트롤러 `@Pattern` 선행 — 서비스 `toUpperCase` 관용은 도달 불가, 카탈로그 ※ 서술 그대로). WORKER 접근 403. ★드리프트 `:426-438`→`:454-466` |
| TC-LABEL-109 | listReports 기본 OPEN | PASS | [실동작] `GET /v1/deident-reports`(파라미터 없음) → 200, `totalElements=5`, `content[].status` 집합 = `{OPEN}` 단일. ★드리프트 `:427-429`→`:455-457` |
| TC-LABEL-110 | labelVersion 불일치 409 | PASS | [실동작] 현재 버전 4 인 srcSn 508 에 `labelVersion:0` 전송 → **409** `"다른 사용자가 먼저 저장했습니다. 최신 라벨을 불러온 뒤 다시 저장하세요."`, 라벨 미변경(후속 GET 동일). 근거 `LabelService.java:457-465` 유효 |
| TC-LABEL-111 | labelVersion 미첨부 = 검사 skip | PASS | [실동작] 같은 stale 상황에서 `labelVersion` 필드 자체를 빼고 전송 → **200** 저장, 응답 `labelVersion 3→4`. 근거 `LabelService.java:458` 유효 / `LabelBulkUpsertRequest.java` 는 `:24-31`(카탈로그 `:26-32` 미세 드리프트) |
| TC-LABEL-112 | 무변경 저장은 버전 미증가 | PASS | [실동작] srcSn 508 동일 세트 재전송 → 200, 응답 `labelVersion` **3 그대로**(bump 없음). 근거 `LabelService.java:400-406` 유효 |
| TC-LABEL-113 | 좌표 상한 초과 신규 라벨 400 | PASS | [실동작] `points=[[999999,888888],…]` (id 없음) → **400** `"좌표가 이미지 경계를 벗어났습니다 (x=999999.0, y=888888.0, 이미지=320x240)"` — 클램프 아님. ⚠ **단, 이 방어는 우회 가능**(→ C-ISSUE-61) |
| TC-LABEL-114 | 경계값 x==width 허용 | PASS | [실동작] 320x240 프레임에 `[[0,0],[320,240]]` 신규 저장 → **200**(labelVersion 4→5) |
| TC-LABEL-115 | 레거시 out-of-bounds 라벨 무변경 재저장 허용 | PASS | [실동작] DB 로 lbl 741 을 `[[5000,4000],[6000,5000]]`(경계 밖)로 만든 뒤 동일 좌표 재전송 → **200**. 같은 요청에 정상 신규 라벨을 추가해도 **200**(프레임 전체 차단 회귀 없음). 근거 `:330-335` 유효 |
| TC-LABEL-116 | 기존 라벨을 경계 밖으로 이동 → 400 | PASS | [실동작] 같은 라벨 좌표를 `[[5001,4000],…]`(1픽셀만 변경)으로 전송 → **400**(pointsEqual 불일치 시 상한 강제). 근거 `:333-335` 유효. ⚠ C-ISSUE-61 우회는 별도 |
| TC-LABEL-117 | 치수 측정 실패 시 상한만 skip | PASS | [실동작] srcSn 502 의 원본·비식별 경로를 모두 존재하지 않는 파일로 바꾼 뒤 `[[99999,88888],…]` 저장 → **200**. WARN `[Label] frame image path unresolved — coordinate upper-bound check skipped srcSn=502 cause=CustomException`. 메트릭 `GET /actuator/metrics/label.bounds.skipped` → `COUNT=2`, `tag reason=[unresolved]`. **하한은 유지** — 같은 프레임에 `[[-5,-5],…]` → 400 `"좌표는 0 이상이어야 합니다"` |
| TC-LABEL-118 | SKELETON v=0 키포인트 상한 제외 | PASS | [실동작] 대조 실험. `[99999,99999,**0**]` 포함 17점 → **200** / 같은 좌표에 가시성만 `2` 로 바꾼 `[99999,99999,**2**]` → **400** `"…이미지=320x240"`. 근거 `:764-767, :777-780` 유효 |
| TC-LABEL-119 | 비활성 마스터 기존 참조 유지 저장 허용 | PASS | [실동작] lbl 741 의 `lbl_id` 를 비활성 마스터 **10**(`c2a-truck-x`, USE_YN='N')으로 세팅 후, 같은 labelId 재전송 + **같은 프레임의 다른 라벨(1248) 좌표 수정** → **200**(labelVersion 8→9). 프레임 전체 409 영구차단 회귀 없음. 근거 `:685-695` 유효 |
| TC-LABEL-120 | 비활성 마스터를 기존 라벨에 새로 부여 → 409 | PASS | [실동작] ①기존 라벨(id=741)의 labelId 를 비활성 **11** 로 변경 → **409** `"사용 중지된 라벨입니다: labelId=11"` ②id 없는 신규 라벨에 비활성 **10** 부여 → **409**. "id 붙이면 통과" 우회 차단됨. 근거 `:674-677, :685-695` 유효 |
| TC-LABEL-121 | 신고 구간 라벨 조회 412 | PASS | [실동작] rawSn 115 신고 접수 후 `GET /v1/frames/508/labels` — WORKER(배정) **412**, **REVIEWER 도 412**(역할 무관) `"비식별 재처리 대기 중인 영상입니다…"`. 인가 선행 확인: 미배정 WORKER(2002) 는 **403**. 근거 `LabelService.java:193` 유효 / `LabelAccessGuard.java:135-141`→**실제 `:139-146`** |
| TC-LABEL-122 | 신고 구간 라벨 이력 412 | PASS | [실동작] `GET /v1/frames/508/label-history` → **412** 동일 메시지. 좌표 전문(chgDtlCn) 우회 창 차단 확인. 근거 `LabelService.java:482` 유효 |
| TC-LABEL-123 | 게이트 판정 = 자기 rawSn 행 하나 (구속) | PASS | [실동작] 부모 900=`'F'`, 파생 88=`'Y'` 상태에서 ①파생 프레임 `GET /v1/frames/439/labels` → **200** ②부모 프레임 `GET /v1/frames/429/labels` → **412**(대조군) ③다른 파생 98(부모 906=`'F'`) `GET /v1/frames/466/deid-image` → **200**. 조상 체인 미순회 실증. 근거 `DeidentReportGate.java:23-47, :66-71` 유효. **★1 확정 정책의 귀결이므로 결함 아님** |
| TC-LABEL-124 | 신고 후 라벨 무변경 확인 | PASS | [실동작] 신고 전후 `ls_data_lbl` 건수 **6→6**, `ls_label_version(data_raw_sn=115)` **0→0**(스냅샷 미생성), `ls_data_src.lbl_ver` **508=6·509=7·510=9 불변**(bump 없음). 로그 `labelsPreserved=true`. ★드리프트 `:197-205`→`:201-209` |
| TC-LABEL-125 | 개인정보 리셋 행 단위 감사 | PASS | [실동작] 3필드 보유 프레임 2건(508·509) 세팅 후 신고 → `ls_data_lbl_hstry` 에 **정확히 2행**(`{"event":"PRIVACY_META_RESET","deidentReportSn":32,"changes":[]}`, reg_id=2001), 영상 축은 `ls_task_event_log` 179 `PRIVACY_META_RESET`. **V139 필터 실측 확인**: `pg_get_viewdef('v_completed_label_change')` 의 `WHERE (add+mdfcn+del) > 0` 으로 델타 0 행은 미노출. ⚠ 로그의 `privacyReset=6` 은 프레임 총수(→ C-ISSUE-66 이월). ★드리프트 `:218-226`→`:222-253` |
| TC-LABEL-126 | resolve 후 게이트 자동 해제 + 보존 라벨 재사용 | PASS | [실동작] resolve 직후 `GET /v1/frames/508/labels` **200**, `deid-image` **200**, `label-history` **200**. 반환 라벨셋·`lbl_ver`(6) 이 신고 직전과 동일 — 별도 복원 API 호출 없음. ★드리프트 `:387-391`→`:415-419`, `LabelAccessGuard:135-141`→`:139-146` |
| TC-LABEL-127 | 마킹 단계 rawSn 신고 정상 | PASS | [실동작] `POST /v1/videos/112/deident-report`(REVIEWER) → **201** `data=35`. 부수효과 5종 srcSn 경로와 동일: `de_ident_yn='F'` / 작업락 LOCKED / 개인정보 3필드 리셋(`privacyReset=1 privacyResetAudited=1`) / 라벨·스냅샷 무변경(0건) / `[StreamMetaCache] evicted rawSn=112`. 통지 `srcSn=null` 은 코드(`reportByVideo` → `doReport(rawSn, **null**, …)`)로 확인(112 미승인이라 통지 미발행). ★드리프트 `Controller:122-131`→`:138-146`, `Service:147-155`→`:151-159` |
| TC-LABEL-128 | rawSn 신고 — 파생영상 412 | PASS | [실동작] `POST /v1/videos/88/deident-report`(부모 900) → **412** `"이 영상은 원본 영상의 비식별 결과를 복사해 만든 파생영상(증강·해상도 변환본)이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다."` — **원본 유도 문구 없음, 부모 rawSn 미노출**. `ls_deident_report` 신규 행 0. 개행(`\n`) 포함 사유를 보냈으나 로그는 sanitize 된 WARN 1줄. ★드리프트 `:183, :295-306`→`:187, :323-334` |
| TC-LABEL-129 | rawSn 신고 — 비식별 미수행 412 | PASS | [실동작] `POST /v1/videos/901/deident-report`(`de_ident_yn='N'`) → **412** `"아직 비식별 처리가 완료되지 않은 영상입니다…"`. `'F'` 통과 규약도 실측(rawSn 900 `'F'` → 412 아님). ⚠ 다만 "그 중복은 409 로 처리"는 **작업락이 있을 때만** 성립(→ C-ISSUE-67). ★드리프트 `:186, :329-337`→`:190, :357-365` |
| TC-LABEL-130 | rawSn 신고 인가 축 = verifyRawAccess | PASS | [실동작] 미배정 WORKER(2002) → `POST /v1/videos/115/deident-report` **403** `"본인에게 배정되지 않은 영상입니다."`. **존재하지 않는 rawSn 999999 도 동일 403**(404 아님) → 존재 여부 오라클 없음(조회 이전 평가 실증). ★드리프트 `:151`→`:155` (`LabelAccessGuard.java:83-100` 은 유효) |
| TC-LABEL-131 | srcSn 경로도 파생/미수행 412 | PASS | [실동작] ①파생 프레임 `POST /v1/labels/439/deident-report` → **412**(파생 문구) ②비식별 미수행 프레임 `POST /v1/labels/434/deident-report` → **412**(미수행 문구). rawSn 경로와 **문구·코드 완전 일치** → `doReport` 수렴 실증. ★드리프트 `:125, :169-186`→`:126, :173-190` |
| TC-LABEL-132 | resolve 시 DeidentGateReopenedEvent 항상 발행 | PASS | [실동작] rawSn 115 는 **미승인(`ASSIGNED`)** 인데도 resolve 직후 로그 `[VlmResumeBridge] deident gate reopened rawSn=115 — checking withheld VLM submit`. ★드리프트 `:487-495`→`:515-523` |
| TC-LABEL-133 | DeidentReportResolvedEvent 는 APPROVED만 | PASS | [실동작] **대조 실증**. ①미승인 115 resolve → `VlmResumeBridge` 만 출력, `DatasetExportBridge` **없음** ②같은 시간대 APPROVED 영상 906 resolve → `[DatasetExportBridge] deident report resolved rawSn=906 — re-triggering withheld export/notify` + `AsyncDatasetExportRunner` 기동. ★드리프트 `:492-494`→`:520-522` |
| TC-LABEL-134 | 신고·해소 시 스트림 메타 캐시 무효화 | PASS | [실동작] 신고 시 `[StreamMetaCache] evicted rawSn=115`, resolve 시 `evicted rawSn=115` — **그 영상 하나만**(같은 로그 구간에 파생 rawSn evict 0건). ★드리프트 `:248, :395`→`:275, :423` |
| TC-LABEL-135 | 오토라벨 신고 구간 차단 순서 | PASS | [실동작] ①신고로 잠긴 115 → `POST /v1/frames/508/autolabel` **409** `"작업이 잠긴 영상입니다."` ②락 없이 `'F'` 인 110 → **412** `"비식별 재처리 대기 중인 영상은 오토라벨을 실행할 수 없습니다."` — 작업락 판정 선행 확인. 근거 `AutolabelOnlineService.java:411-419`(실제 `:414-419`, ±5 허용) |
| TC-LABEL-136 | 검출 좌표 상한 clamp | PASS | [정적+단위] `DetectionBoxNormalizer.java:59-68` 유효(`clamp(v, upperBound)`), 단위테스트 `DetectionBoxNormalizerTest.이미지_상한_초과좌표는_이미지_경계로_clamp된다` 통과(3차 baseline 실패 0건). ⚠ 온라인 종단은 ai-server weights 미탑재로 `mockReason=weights_missing` → 실호출이 `normalizeDetections` 이전에 반환(1차 C-ISSUE-44 이월) |
| TC-LABEL-137 | 검출 좌표 음수 clamp | PASS | [정적+단위] 동상. `DetectionBoxNormalizerTest.경계밖_음수좌표는_0으로_clamp되어_반환된다` / `y좌표_음수도_0으로_clamp된다` 통과. 근거 `:59-68` 유효 |
| TC-LABEL-138 | 퇴화 박스 검출 단위 스킵 | PASS | [정적+단위] `:65-67` `if (x2<=x1 \|\| y2<=y1) return Optional.empty()`, 호출부 `AutolabelOnlineService.normalizeDetections` 가 `continue` + WARN(400 아님). 단위테스트 `clamp후_퇴화한_박스는_예외가_아니라_스킵신호를_반환한다`·`좌표순서가_역전된_박스도_스킵신호를_반환한다` 통과 |
| TC-LABEL-139 | bounds 미상 시 하한만 clamp | PASS | [정적+단위] `:71-77` `upperBound()` → `Double.MAX_VALUE`. 단위테스트 `이미지_실측_해상도를_모르면_상한없이_음수만_0으로_clamp된다`·`잘못된_bounds_배열은_상한없음으로_취급한다` 통과 |
| TC-LABEL-140 | SAM 폴리곤은 clamp 미적용(거부 유지) | PASS | [정적] `AutolabelOnlineService.validatePolygonPoints`(`:602-621`)는 음수/비유한/2튜플 위반을 `INVALID_INPUT` 으로 던지고, 호출부(`:333` + `:340-350` catch)가 **박스 단위 스킵**한다(429 만 전파). all-or-nothing 아님 → 비대칭 결함 아님. 근거 `:581-617` 은 javadoc+메서드를 포괄(±근사) |
| TC-LABEL-141 | `/deid-image` 정상 200 | PASS | [실동작] `GET /v1/frames/508/deid-image`(WORKER) → **200**, `Content-Type: image/jpeg`, `Content-Length: 13164`(= 비식별 파일 크기, 원본은 9441), `X-Content-Type-Options: nosniff`, `Content-Disposition: inline; filename="frame_deid_508.jpg"` — **srcSn + MIME 파생 확장자만**(파일명 유래 문자열 없음, CWE-113). 근거 `Controller:115-122`·`Service:305, :322-333` 유효 |
| TC-LABEL-142 | `/deid-image` 원본 폴백 없음 → 404 | PASS | [실동작] `de_idntf_src_file_path_nm` 이 빈 값이고 `src_file_path_nm` 은 실재하는 srcSn 324 → `/deid-image` **404** `"비식별 이미지 파일이 존재하지 않습니다."` / 같은 프레임의 `/image` 는 **200**(원본이 실재함을 증명). 폴백 부재 확정. 근거 `:266-270, :290-301` 유효 |
| TC-LABEL-143 | `/deid-image` 권한 범위가 `/image` 와 다름 | PASS | [실동작] **카탈로그 기대값을 이번 회차에 정정한 뒤 판정**. PORTAL_USER 토큰(`channel=PORTAL`) → `/deid-image` **403** / `/image` 도 **403**(`"권한이 없습니다."`) — `SecurityConfig` 채널 격리가 `@PreAuthorize` 이전에 차단. 1차 **C-ISSUE-62 미해소 이월**이었고 본 회차에서 카탈로그 정정 완료(→ C-ISSUE-63) |
| TC-LABEL-144 | `/deid-image` 신고 구간 412 + 평가 순서 고정 | PASS | [실동작] 신고 구간 rawSn 115 에서 ①미배정 WORKER(2002) → **403** ②배정 WORKER(2001) → **412**. 순서 인가→게이트→경로해석 확정(412/404 로 프레임 존재 탐색 불가). 근거 `FrameImageLookupService.java:99-101`·`FrameImageService.java:288` 유효 |
| TC-LABEL-145 | `/deid-image` 심링크 TOCTOU 차단 | PASS | [실동작+정적] 비식별 base **내부**에 원본 프레임을 가리키는 심링크(`link-test.jpg`)를 만들고 DB 경로를 그리로 돌림 → **403**(링크를 따라 원본을 서빙하지 않음, `toRealPath` 가 base 밖으로 탈출 판정). 판정~open 사이 교체 레이스 자체는 재현 불가하나 `openNoFollow`(`:362-366`)가 크기·스트림 모두 `LinkOption.NOFOLLOW_LINKS` 로 읽고 실패 시 404 로 마감(`:313-320`)함을 코드로 확인. 근거 유효 |
| TC-LABEL-146 | `/deid-image` 비식별 서브트리 밖 경로 403 | PASS | [실동작] srcSn 502 의 비식별 경로를 `/app/storage/raw/...` 로 바꿈 → **403** `"허용되지 않은 이미지 경로입니다."`. 판정은 `StorageSubtreePolicy.verifyDeidentifiedFile` 단일 위임이며 base 동일 운영형상에서도 `frames/deid`·`videos` **서브트리 세그먼트 검사**(`:122`)가 남아 방어가 성립. 근거 `:292-300` 유효 |
| TC-LABEL-147 | `/deid-image` 응답 `Cache-Control: no-store` | PASS | [실동작] 응답 헤더 `Cache-Control: no-store` 확인(`max-age` 없음). 근거 `:327` 유효 |
| TC-LABEL-148 | `/v1/frames/{srcSn}/image` 응답 `no-store` | PASS | [실동작] `Cache-Control: no-store` 확인. ⚠ **기대결과 사유 문구가 폐기된 동작을 서술**하고 있어 정정함 — 이 경로는 더 이상 "비식별 판정 없이 원본 프레임을 서빙"하지 않는다. 실측: WORKER 요청 시 Content-Length **13164 = 비식별본**(원본 9441), 응답 `frameImageType=DEID` → `serveBySrcSn`→`serveFrame` 공용 판정기 경유(2026-07-30 확정 정책). no-store 필요성 자체는 유효. 근거 `FrameImageService.java:252` 유효 (→ C-ISSUE-64) |
| TC-LABEL-149 | `/v1/videos/{rawSn}/frames/{frameNo}/image` 응답 `no-store` | PASS | [실동작] WORKER 기본 요청 **200 + no-store**(13164=비식별본), REVIEWER `raw=true` **200 + no-store**(9441=원본). 신고 구간에는 동일 경로가 **412**. 근거 `VideoController.java:320-338`·`FrameImageService.java:252` 유효 |

### 집계

| 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---:|---:|---:|---:|---:|---:|---:|
| 44 | 44 | 0 | 0 | 0 | 0 | 0 |

> ⚠ **"전건 PASS" 를 그대로 신뢰하지 말 것.** 케이스 표에 적힌 단언 자체는 전부 실동작으로 성립했으나,
> **적극적 반증 과정에서 케이스 밖 우회 경로 2건(C-ISSUE-61/62)** 을 찾았다. 특히 C-ISSUE-61 은
> TC-LABEL-113/116 이 지키려는 좌표 상한 정책을 **요청 한 줄로 무력화**한다. 케이스가 지정한 입력만 넣으면
> 통과하지만 정책은 지켜지지 않는 전형적 사례라, 카탈로그에 우회 케이스를 신설할 필요가 있다.

---

## 이전 회차 이슈 대조 (1차 `2026-08-01/1차/ISSUES.md`)

| 1차 이슈 | 대상 | 3차 상태 |
|---|---|---|
| **C-ISSUE-61**(1차) — `GET /v1/deident-reports` 정렬 키 미검증 → 500 + 엔티티 필드 열거 오라클 | TC-108/109 인접 | **✅ 해소.** `?sort=nosuchfield,desc` → **400** `"지원하지 않는 정렬 기준입니다."`, `?sort=rsn,desc`(엔티티 내부 필드) → **400**, `?sort=rawSn,desc` → **200**(allowlist 매핑 적용). `size=100000` → 응답 `size=100`(캡 유지). 500 및 200/500 오라클 소멸 |
| **C-ISSUE-62**(1차) — TC-143 기대결과 "`/image`=통과"가 성립하지 않음 | TC-143 | **미해소 → 본 회차 카탈로그 정정으로 종결.** 실측 여전히 `/image`=403·`/deid-image`=403. 카탈로그 기대결과를 실동작에 맞춰 수정함(C-ISSUE-63 참조). 코드 쪽 죽은 권한 표기(`FrameImageController.java:80` 의 `PORTAL_USER`)는 그대로 |
| **C-ISSUE-63**(1차) — TC-141~149 근거 file:line 드리프트 | TC-141~149 | **✅ 해소.** 9건 전부 1차 제안값으로 갱신되어 있고 실제 코드 위치와 일치함을 재확인(`Controller:115-122/117 vs :80`, `Service:266-270·290-301·307-320·327·252·362-366`, `VideoController:320-338`) |
| **C-ISSUE-64**(1차) — 신고 감사 로그 `privacyReset` 이 실제 변경 건수가 아님 | TC-125 인접 | **미해소 이월.** 실측 `privacyReset=6 privacyResetAudited=2`(프레임 6건 중 값 보유 2건). 로그 필드명·값 그대로(→ C-ISSUE-66) |
| **C-ISSUE-44**(1차) — ai-server 가중치 미탑재로 검출 의존 분기 실동작 불가 | TC-136~140 | **미해소 이월.** `[Autolabel] mock response — skip detection … reason=weights_missing`, `detectedCount=0`, message `"AI 모델 미로드 — 결과 신뢰 불가"`. `normalizeDetections` 종단 호출 불가(단위테스트로 대체 판정) |

---

## 카탈로그 정정 (본 회차, 담당 라인범위 169~212 안에서만 수행)

| # | 대상 | 정정 내용 |
|---|---|---|
| 1 | TC-106·107·108·109·124·125·126·127·128·129·130·131·132·133·134 | `DeidentReportService.java` / `DeidentReportController.java` **근거 라인 15건 갱신**(V163 영상 개인정보 메타 도입으로 +20~30줄 이동) |
| 2 | TC-121·126 | `LabelAccessGuard.java:135-141` → `:139-146` |
| 3 | TC-111 | `LabelBulkUpsertRequest.java:26-32` → `:24-31` |
| 4 | TC-135 | `AutolabelOnlineService.java:411-419` → `:414-419` |
| 5 | TC-143 | **기대결과 정정** — `/image` = 통과 → `/image` 도 **403**(채널 격리 `SecurityConfig` 가 `@PreAuthorize` 보다 먼저 차단). 역할 집합 차이는 애노테이션 계층에만 존재 |
| 6 | TC-148 | **기대결과 사유 정정** — "비식별 판정 없이 원본 프레임을 서빙" 서술 폐기. 이 경로는 `serveBySrcSn`→`serveFrame` 공용 판정기로 **기본 DEID** 서빙 |
| 7 | TC-113·116 뒤 | **신규 케이스 TC-LABEL-150 추가** — 존재하지 않는 `id` 를 붙인 신규 라벨의 좌표 상한 우회(C-ISSUE-61 회귀 가드) |

> 프로덕션 코드·테스트·설정은 일절 수정하지 않았다.

---

## 이슈 대장

### [C-ISSUE-61] TC-LABEL-113/116 인접 — 존재하지 않는 `id` 를 붙이면 좌표 경계 상한(400) 검증이 통째로 우회된다

- **심각도**: HIGH
- **기대 동작(기대효과)**: ★3(확정 정책) — **사용자 저장 경로의 좌표는 이미지 경계를 넘으면 400 으로 거부**한다(클램프 아님, 사용자 확정). 이유는 "작업자가 의도한 위치가 조용히 왜곡되면 안 된다"이며, 그 결과 `LS_DATA_LBL` 에는 프레임 밖 좌표가 들어가지 않아야 한다(export JSON·데이터마트로 그대로 흘러가는 값이다). 신규 라벨 판정은 "그 프레임에 실재하지 않는 라벨"이면 전부 신규여야 한다 — TC-LABEL-120 이 `USE_YN` 축에서 이미 이 원칙("id 붙이면 통과" 우회 차단)을 명시적으로 구현하고 있다.
- **현재 동작(이슈 내용)**: 상한 검증은 **`item.id() == null` 일 때만** 수행되는데, 저장 분기는 `id != null` 이어도 **그 프레임에 없는 id 면 신규 라벨로 만든다.** 두 판정 기준이 어긋나 상한 검증이 전혀 걸리지 않는 창이 생긴다.
  ```java
  // LabelService.java:288-292  (사전 검증)
  validatePoints(item.lblTypeCd(), item.points(), item.id() == null);
  if (item.id() == null) {                       // ← id 가 있으면 상한 검증 자체를 건너뜀
      validateWithinBounds(item.lblTypeCd(), item.points(), bounds);
  }

  // LabelService.java:331-335  (UPDATE 분기 — idIndex 에 있을 때만)
  if (item.id() != null && idIndex.containsKey(item.id())) {
      if (!pointsEqual(found.getPointCn(), pointsJson)) { validateWithinBounds(...); }
  } else {
      // LabelService.java:349-365 — 신규 생성. validateWithinBounds 호출 없음.
      created = labelRepository.save(LsDataLbl.createManual(srcSn, ..., pointsJson, actorNo));
  }
  ```
  대조 `LabelService.java:685-695`(`isNewLabelAssignment`)는 **같은 상황을 "신규 취급"으로 올바르게 처리**한다 — 즉 USE_YN 축은 막혀 있고 좌표 축만 뚫려 있다(설계 의도가 아니라 누락으로 판단).
  **실측**(WORKER 2001, srcSn 509, 프레임 320x240):
  ```
  PUT /v1/frames/509/labels
    {"items":[{"id":99999999,"lblTypeCd":"BBOX","labelId":1,"label":"bypass-oob",
               "points":[[999999,888888],[999999,888889]]}]}
    → 200 OK   (id 를 빼면 동일 요청이 400 "좌표가 이미지 경계를 벗어났습니다 … 이미지=320x240")
  DB : select lbl_sn, point_cn from ls_data_lbl where lbl_nm='bypass-oob';
       1257 | [[999999.0,888888.0],[999999.0,888889.0]]     ← 경계 밖 좌표가 실제로 적재됨
  ```
- **재현/확인 경로**: 위 curl 2줄(같은 payload 를 `id` 유/무로 각각 전송해 200/400 대조). 사후 정리: 해당 라벨은 full-replace 델타로 삭제됨.
- **영향**: ①확정 정책(★3)이 무력화되어 프레임 밖 좌표가 `LS_DATA_LBL` → 검수 승인 스냅샷(`LS_LABEL_VERSION`) → export JSON → 데이터마트까지 그대로 전파된다(학습데이터 오염, 데이터 정합) ②입력 검증 우회(CWE-20 / CWE-1287 Improper Validation of Specified Type of Input) ③TC-LABEL-113·116 의 회귀 가드가 "지정된 입력에서만" 성립해 방어가 실질적으로 없는 상태를 통과로 보이게 한다.
- **수정 방향(제안)**: 상한 검증의 판정 기준을 **저장 분기와 동일한 술어**로 맞춘다 — 사전 검증 루프에서 `item.id() == null` 대신 `isNewLabelAssignment` 와 같은 축(즉 `item.id() == null || !idIndex.containsKey(item.id())`)을 쓰거나, `else`(신규 생성) 분기 안에서 `validateWithinBounds` 를 호출한다. 후자가 안전하다(사전 루프는 `idIndex` 적재 이전에 돌기 때문). 동일 술어를 `validatePoints(..., enforceMaxPoints)` 에도 적용해야 C-ISSUE-62 가 함께 닫힌다. 회귀 가드로 TC-LABEL-150 신설(본 회차 카탈로그에 추가함).

### [C-ISSUE-62] TC-LABEL-113 인접 — 같은 우회로 `MAX_POINTS_PER_LABEL`(1000) 상한도 무력화된다

- **심각도**: LOW
- **기대 동작(기대효과)**: 신규 라벨의 좌표 개수 상한 1000점은 과대 좌표 DoS(CWE-770) 방어로 명시돼 있다(`LabelService.java:700-717` javadoc). 기존 라벨만 상한 없이 통과시키고 저장 직전 simplify 하는 것이 설계다.
- **현재 동작(이슈 내용)**: `validatePoints(..., item.id() == null)` 이므로 존재하지 않는 `id` 를 붙이면 신규 라벨인데도 `enforceMaxPoints=false` 가 되어 상한 검사를 건너뛴다.
  ```
  PUT /v1/frames/509/labels  {"items":[{"id":99999998, ... ,"points": 1500점}]}   → 200
  PUT /v1/frames/509/labels  {"items":[{           ... ,"points": 1500점}]}       → 400 "라벨당 좌표 개수 초과 (최대 1000 점)"
  ```
- **재현/확인 경로**: 위 2줄 대조.
- **영향**: 데이터 영향은 없다 — 저장 직전 `capPoints`(Douglas-Peucker)가 실제 적재를 줄인다(실측: 1500점 요청 → 저장 29점). 남는 것은 **요청 파싱·직렬화·simplify 연산이 상한 없이 수행**되는 자원 소모 표면뿐이며 `@Size(max=500)` items 상한과 요청 바디 크기 제한이 뒤를 받친다. 그래서 LOW.
- **수정 방향(제안)**: C-ISSUE-61 과 **같은 한 줄**로 닫힌다(판정 술어를 저장 분기와 일치시키면 `enforceMaxPoints` 도 함께 정상화). 별도 수정 불필요.

### [C-ISSUE-63] TC-LABEL-143 — 기대결과 "`/image` = 통과"가 성립하지 않음 (1차 C-ISSUE-62 이월 → 본 회차 카탈로그 정정)

- **심각도**: LOW (제품 결함 아님 — 실동작이 기대보다 **더 엄격**)
- **기대 동작(기대효과)**: 카탈로그는 PORTAL_USER 토큰에 대해 `/deid-image`=403 / `/image`=통과 로 "두 형제 경로의 역할 집합이 의도적으로 다름"을 검증하려 한다.
- **현재 동작(이슈 내용)**: 실측 **양쪽 모두 403**(`{"message":"권한이 없습니다.","errorCode":"FORBIDDEN"}`). PORTAL_USER 토큰은 `channel=PORTAL` 이라 `SecurityConfig` 의 내부 채널 격리(`/v1/portal/**` 외 `/v1/**` = `CHANNEL_INTERNAL`)에서 `@PreAuthorize` 도달 **이전에** 차단된다. `FrameImageController.java:80` 의 `hasAnyRole(..., 'PORTAL_USER')` 는 현 배선상 **도달 불가 권한 표기**다. 1차에서 동일 결론이 났으나 카탈로그가 갱신되지 않아 이월됐다.
- **재현/확인 경로**: `POST /v1/dev/tokens {"userNo":"3001","role":"PORTAL_USER","channel":"PORTAL"}` → `GET /v1/frames/508/image` 403 / `GET /v1/frames/508/deid-image` 403.
- **영향**: 보안상 더 강함(영향 없음). 다만 기대값이 틀린 채로 두면 다음 회차에 위양성 FAIL 을 유발하고, 컨트롤러의 죽은 권한 표기가 정책 오독을 부른다.
- **수정 방향(제안)**: ①**카탈로그 기대결과는 본 회차에서 정정 완료**(양쪽 403 + 채널 격리 선행 명시) ②코드 쪽은 `FrameImageController` `/image` 의 `PORTAL_USER` 표기를 제거하거나 "채널 격리로 실효 없음" 주석을 다는 것을 검토(동작 변경 아님). 2회 연속 같은 지적이 반복됐으므로 코드 쪽 정리를 권장.

### [C-ISSUE-64] TC-LABEL-148 — 기대결과의 사유 문구가 폐기된 동작을 서술한다 (본 회차 카탈로그 정정)

- **심각도**: LOW (제품 결함 아님 — 문서/코드 드리프트)
- **기대 동작(기대효과)**: 케이스가 검증하려는 것은 `GET /v1/frames/{srcSn}/image` 응답의 `Cache-Control: no-store` 이며, 이는 **성립한다**.
- **현재 동작(이슈 내용)**: 기대결과 문장이 *"이 경로는 비식별 판정 없이 **원본 프레임**을 서빙하므로"* 라고 사유를 적었는데, 2026-07-30 확정 정책(`CLAUDE.md` "라벨링 캔버스는 비식별 프레임을 서빙한다")으로 이 경로는 `serveBySrcSn`→`serveFrame` 공용 판정기를 타 **기본 DEID** 를 서빙하도록 바뀌었다. 실측: WORKER 요청 `Content-Length: 13164`(비식별 파일 크기) — 원본은 9441 이고 REVIEWER `raw=true` 일 때만 9441 이 나온다. 응답 JSON 의 `frameImageType` 도 `DEID`.
- **재현/확인 경로**: `GET /v1/frames/508/image`(WORKER) 와 `GET /v1/videos/115/frames/0/image?raw=true`(REVIEWER) 의 `Content-Length` 대조 + `docker exec klid-backend ls -l` 로 두 파일 크기 확인.
- **영향**: 검증자가 "원본이 서빙되는 경로"로 오독해 다음 회차에 결함으로 오분류할 위험(이 저장소의 "폐기된 동작을 계속 검증 대상으로 들고 있는" 반복 패턴).
- **수정 방향(제안)**: **본 회차에서 카탈로그 사유 문구 정정 완료.** 코드 수정 불필요.

### [C-ISSUE-65] TC-LABEL-106~134 — 근거 `file:line` 드리프트 15건 (본 회차 카탈로그 정정)

- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 file:line 이 실제 구현 위치를 가리켜야 재검증 비용이 들지 않는다.
- **현재 동작(이슈 내용)**: V163(영상 개인정보 메타) 도입으로 `DeidentReportService` 가 +20~30줄 밀리면서 신고/해소 계열 근거가 일제히 어긋났다(1차에서는 "TC-106~140 근거 전건 유효"였으므로 **본 회차 신규 드리프트**).
  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | 106 | `:374-391` | `:402-419` |
  | 107 | `:384-391` | `:412-419` |
  | 108 | `:426-438` | `:454-466` |
  | 109 | `:427-429` | `:455-457` |
  | 121·126 | `LabelAccessGuard.java:135-141` | `:139-146` |
  | 124 | `:197-205` | `:201-209` |
  | 125 | `:218-226` | `:222-253` |
  | 126 | `:387-391` | `:415-419` |
  | 127 | `Controller:122-131` · `Service:147-155` | `Controller:138-146` · `Service:151-159` |
  | 128 | `:183, :295-306` | `:187, :323-334` |
  | 129 | `:186, :329-337` | `:190, :357-365` |
  | 130 | `:151` | `:155` |
  | 131 | `:125, :169-186` | `:126, :173-190` |
  | 132 | `:487-495` | `:515-523` |
  | 133 | `:492-494` | `:520-522` |
  | 134 | `:248, :395` | `:275, :423` |
  | 111 | `LabelBulkUpsertRequest.java:26-32` | `:24-31` |
  | 135 | `AutolabelOnlineService.java:411-419` | `:414-419` |
  (TC-110·112~120·122·123·136~149 의 근거는 전건 유효.)
- **재현/확인 경로**: 해당 파일 Read 대조.
- **영향**: 검증 비용 증가·오판 위험.
- **수정 방향(제안)**: **본 회차에서 카탈로그 갱신 완료.**

### [C-ISSUE-66] TC-LABEL-125 인접 — 신고 감사 로그의 `privacyReset` 이 "실제로 지워진 프레임 수"가 아니다 (1차 C-ISSUE-64 미해소 이월)

- **심각도**: LOW
- **기대 동작(기대효과)**: 개인정보 표기를 되돌리는 행위의 감사 로그는 **실제 변경 건수**를 말해야 한다(OWASP A09). 행 단위 감사(`privacyResetAudited`)와 집계 로그가 다른 수를 말하면 운영자가 어느 쪽을 믿을지 알 수 없다.
- **현재 동작(이슈 내용)**: `srcRepository.resetPrivacyMetaByRawSn(rawSn)`(`DeidentReportService.java:223`)은 `where s.rawSn = :rawSn` 만으로 전 프레임을 무조건 update 하므로 반환값이 곧 "프레임 총수"다.
  ```
  [DeidentReport] created rprtSn=32 rawSn=115 ... privacyReset=6 privacyResetAudited=2 videoPrivacyReset=true
  ```
  실제 3필드 값을 보유한 프레임은 2건(508·509)인데 `privacyReset=6`(rawSn 115 의 전체 프레임 수)이다. 1차 실측(`privacyReset=5 / audited=0~2`)과 동일 양상 — **미해소**.
- **재현/확인 경로**: 개인정보 3필드가 전부 NULL 인 영상에 신고 접수 → 로그의 `privacyReset` 이 프레임 수와 같음을 확인. 실측 대조군: rawSn 906 `privacyReset=2 privacyResetAudited=0`.
- **영향**: 감사 로그 오독(실제 변경이 없었는데 "6건 리셋"으로 읽힘). 데이터 정합·보안 자체에는 영향 없음(행 단위 감사 `LS_DATA_LBL_HSTRY` 는 정확하다).
- **수정 방향(제안)**: 로그 필드명을 `privacyResetScanned`(또는 `frameCount`)로 바꾸거나, 값을 `privacyResetSrcSns.size()`(=실제 변경분)로 통일. 벌크 update 자체는 멱등이라 조건 추가는 불필요.

### [C-ISSUE-67] TC-LABEL-129 인접 — `'F'` 인데 작업락이 없는 영상(비식별 **실패**분)이 신고를 받아 영구 잠금될 수 있다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `requireDeidentAttempted` 의 설계 근거는 *"이미 `'F'` 인 영상은 통과해 기존 409(재비식별 진행 중) 경로 유지"* 다(`DeidentReportService.java:351-355` javadoc, 카탈로그 TC-129 기대결과). 즉 `'F'` 통과는 **"이미 신고돼 잠겨 있으니 409 가 받는다"** 를 전제로 한다.
- **현재 동작(이슈 내용)**: `'F'` 는 의미가 둘인데(①신고 — 락 있음·산출물 있음 ②**비식별 API 실패** — 락 없음·산출물 없음, `CLAUDE.md` 가 명시), 신고 접수 경로는 **락 존재 여부만** 보고 산출물 실재를 보지 않는다.
  ```java
  // DeidentReportService.java:190  (판정은 'Y'|'F' 통과)
  requireDeidentAttempted(raw);
  // DeidentReportService.java:193-195
  if (workLockService.isRawLocked(rawSn)) { throw CONFLICT("이미 비식별 재처리 중인 영상입니다."); }
  ```
  `'F'` 를 락 없이 만드는 경로가 실재한다 — `KpstDeidentTxService.java:172,:233,:256,:271` 과 `BatchTransitionService.java:223` 의 실패 전이(`markDeidentified("F")`).
  **실측**(rawSn 900 — `de_ident_yn='F'`, `LOCKED` 락 0건):
  ```
  POST /v1/videos/900/deident-report {"reason":"중복 신고"}
    → 201 {"data":34}      (기대 서술대로면 409)
  ```
  접수되면 작업락이 걸리는데, 해소 경로 `verifyDeidentArtifact`(`:570-609`)는 **SUCCEEDED procLog + 실파일 + 신고 이후 갱신**을 요구한다. 비식별이 한 번도 성공하지 않은 영상은 이 조건을 영원히 만족할 수 없어 `resolve` 가 상시 409(`"비식별 산출물이 확인되지 않습니다…"`)로 fail-closed 된다 — 실측으로 같은 409 를 rawSn 115 에서 확인(파일 mtime 갱신 전).
- **재현/확인 경로**:
  ```sql
  -- 비식별 실패 영상 재현: 성공 procLog 가 없고 de_ident_yn='F' 이며 LOCKED 락이 없는 rawSn 선정
  select r.raw_sn from ls_data_raw r
   where r.de_ident_yn='F'
     and not exists (select 1 from ls_auth_work_lock l where l.data_raw_sn=r.raw_sn and l.lck_stts_cd='LOCKED')
     and not exists (select 1 from ls_deident_proc_log p where p.data_raw_sn=r.raw_sn and p.proc_stts_cd='SUCCEEDED');
  ```
  → `POST /v1/videos/{rawSn}/deident-report` 201 → `POST /v1/deident-reports/{rprtSn}/resolve` 항상 409.
- **영향**: 기능/가용성 — 비식별이 **실패**한 영상에 신고가 접수되면 작업락이 걸린 채 해소 수단이 없다(관리자 DB 개입 필요). `LS_DEIDENT_REPORT` 에 해소 불가 OPEN 행이 누적되어 REVIEWER 신고 관리 화면(TC-108/109)의 기본 목록을 오염시킨다. 파생·해상도 게이트의 판정 원천(`hasDeidentArtifact()`)이 "산출물 있음"으로 거짓 응답하는 문제와 같은 뿌리다.
- **수정 방향(제안)**: `requireDeidentAttempted` 를 **플래그 단독 판정에서 산출물 실재 확인으로 승격**한다 — `'F'` 인 경우 `LsDeidentProcLogRepository.findLatestSuccessByDataRawSn` 이 존재하고 경로가 비지 않았을 때만 통과시키고, 아니면 412(신고 대상 아님 — 비식별 실패 재처리 대기)로 거부. 이는 `CLAUDE.md` 가 이미 채택한 "산출물 실재 검증이 fail-closed 로 뒤를 받친다" 패턴(해상도 Phase A/B)을 신고 입구에도 적용하는 것이며, 기존 "신고 중복 → 409" 계약은 그대로 유지된다. ⚠ 카탈로그 TC-129 기대결과에 "락이 있을 때"라는 전제를 명시하는 정정도 함께 필요(본 회차에서는 정정하지 않음 — 정책 확정 대상).

---

## 검증 중 확인한 환경/데이터 사실 (참고)

1. **동시 검증 간섭 실재** — 검증 시간대(00:28~01:07)에 다른 파트가 rawSn 103·104·105·107·109·905·906 에 신고를 생성·해소 중이었다. rawSn 906 의 APPROVED resolve 로그가 TC-133 대조군으로 유용했으나, 신고는 대상 영상을 잠그므로 **파트별 전용 rawSn 배정이 필요**하다(1차에서도 같은 권고).
2. **DB 컬럼명 주의(재확인)** — 카탈로그·`CLAUDE.md` 표기는 `DE_IDNTF_YN` 이지만 실제 컬럼은 `ls_data_raw.de_ident_yn`(프레임은 `de_idntf_src_file_path_nm`). 신고 PK 도 `rprt_sn` 이 아니라 `deident_report_sn`, 락 테이블은 `ls_auth_work_lock`.
3. **actuator 는 인증 필요** — `/api/actuator/metrics/**` 는 토큰 없이 401. 메트릭 확인 시 REVIEWER 토큰 필요.
4. **프레임 실측 해상도 320x240** — 3차 시드 프레임(`clip-9101`) 기준. 좌표 경계 케이스 판정 시 1280x720 을 전제로 한 카탈로그 예시 값과 다르다(케이스 문구는 예시일 뿐 판정에는 영향 없음).

---

# C 클러스터 part5 — C-3. TC-SAM2 (SAM2 분할/트랙 프록시) 전수 검증

- **회차**: 2026-08-03 3차
- **담당 범위**: `docs/test-cases/C-marking-labeling.md` C-3 절 (검증 착수 시점 223~268행, TC-SAM2-01~35 **35건**)
- **검증 방식**: 풀스택 실동작(backend `localhost:18081` + ai-server `:9300` + mock-server `:9400` + PostgreSQL) 우선, 실동작 재현 불가분만 자동테스트/정적 대조
- **사용 데이터**: `_raw/pipeline-drive.md` 공용 정상 데이터 **rawSn=101 / srcSn 468~477**(WORKER 2001 배정), 파생영상 **rawSn=18 / srcSn 45~74**(`SRC_FILE_PATH_NM` NULL), 신고구간 **rawSn=900 / srcSn 429~433**(`DE_IDENT_YN='F'`)
- **토큰**: `POST /v1/dev/tokens` — WORKER(userNo 2001) / REVIEWER(userNo 1001)
- **금지사항 준수**: 프로덕션·테스트·설정 코드 **무수정**. 빌드/테스트 **미실행**. 카탈로그는 담당 라인범위 내에서만 Edit. DB 는 TC-SAM2-06 경로순회 재현을 위해 `ls_data_src.src_sn=46` 1행을 일시 변경 후 **즉시 원복**(원복 확인 완료).

---

## 0. 환경 전제 (판정에 직접 영향)

| 항목 | 실측 | 판정 영향 |
|---|---|---|
| ai-server `/app/weights` | **빈 디렉터리**(0 files) | **YOLO 는 `weights_missing` mock**. 단 **SAM2 는 mock 이 아니다** — `sam2_loader.py:44-48` 이 HuggingFace `SAM2ImagePredictor.from_pretrained(model_id)` 로 **HF 캐시에서 로드**하며 `/app/weights` 를 쓰지 않는다. 실측 응답 `source="model"`, `score=0.9652`(mock 고정값 0.95/0.9 아님) → **실모델 동작 확인** |
| SAM2 mock 유도 방법 | 마스크가 안 잡히는 프롬프트(`box:[10000,10000,10001,10001]` / 역박스 / 화면 밖 `prevPolygon`) → ai-server 가 `mock_reason=empty_mask` 로 폴백 | **mock 게이트를 실동작으로 검증할 수 있었다**(정적 추정 아님) |
| 자동테스트 baseline | `_raw/test-baseline.md` — backend 4,152 / 실패 0 / 에러 0 | 테스트 근거로 인용한 케이스는 baseline 통과분 |

---

## 1. 판정 결과표

> 판정 토큰: PASS / FAIL / PARTIAL / BLOCKED / N/A / 확인필요 (§7 표기 규칙 준수)

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-SAM2-01 | PASS | [실동작] `POST /v1/frames/468/sam2-segment {"points":[[50,50]]}` → **200** `{"polygon":[[76,9],…9점],"score":0.9652,"empty":false}`, `message:null`. **클릭 1점이 400 으로 죽지 않음**(회귀 가드 성립). box 프롬프트도 200. DB 미저장(`ls_data_lbl` 신규행 0 — 서비스가 write 없음). [정적] `Sam2SegmentService.java:91-175`, 요청검증 `:97-99` |
| TC-SAM2-02 | PASS | [실동작] path 469 / body 468 → **400** `"path 의 srcSn 과 body 의 srcSn 이 다릅니다."` [정적] `LabelController.java:171-176` |
| TC-SAM2-03 | PASS | [실동작] points+box 동시 → **400** `"exactlyOnePrompt: points 또는 box 중 정확히 하나만 제공해야 합니다."` [정적] `Sam2SegmentRequest.java:46-51` |
| TC-SAM2-04 | PASS | [실동작] 둘 다 미전송 → **400** 동일 메시지(`hasPoints ^ hasBox`) |
| TC-SAM2-05 | PASS | [실동작] WORKER 2001 → 미배정 srcSn 429 → **403** `"본인에게 배정되지 않은 영상입니다."` [정적] `Sam2SegmentService.java:93` |
| TC-SAM2-06 | PASS | [실동작] `ls_data_src.src_sn=46` 을 `src_file_path_nm='../../../etc/passwd'`, `de_idntf_src_file_path_nm=NULL` 로 일시 변경 후 segment 호출 → **400** `"허용되지 않은 경로입니다."` (원복 완료). 서비스가 경로를 조립하지 않고 `FrameImageEncoder.resolveSafe`(`:220-229` `startsWith(baseDir)`)가 차단함을 확인. 회귀 가드 `FrameImageEncoderTest#경로_순회_시도는_INVALID_INPUT_차단` |
| TC-SAM2-07 | PASS | [실동작] srcSn 999999 → **404** `"프레임을 찾을 수 없습니다."` |
| TC-SAM2-08 | PASS | [정적+테스트] `Sam2SegmentService.java:114-118` → `ErrorCode.PAYLOAD_TOO_LARGE` = `HttpStatus.PAYLOAD_TOO_LARGE`(`ErrorCode.java:24`) = **413**. `Sam2SegmentServiceTest#이미지_크기_상한_초과시_400`(제목과 달리 실제 단언은 `PAYLOAD_TOO_LARGE`)이 `verify(aiServerClient, never()).segment(any())` 까지 고정. ※실동작은 `authoring.sam2.max-image-bytes`(기본 20MB)를 낮추려면 재기동이 필요해 미수행 — 프레임 이미지가 KB 급이라 자연 초과 불가 |
| TC-SAM2-09 | PASS | [실동작] mock 유도 `{"box":[10000,10000,10001,10001]}` → **200** `{"polygon":[],"score":0.0,"empty":true}` + `message:"AI 모델 미로드 — 결과 신뢰 불가"`. 역박스 `[100,100,10,10]` 도 동일. FE `OverlayLayer.tsx:611-616` 이 `polygon.length===0` 에서 **저신뢰 분기보다 먼저** 자동적용 차단 + `onMockWarning` 호출 → **FE 자동적용 차단 종단 확인**. ⚠ 안내 문구 정확도는 별건(C-ISSUE-83 아래) |
| TC-SAM2-10 | PASS | [테스트+정적] `Sam2SegmentService.java:182-185` → `EXTERNAL_API_ERROR`(502). `Sam2SegmentServiceTest#3점_미만_폴리곤_응답시_오류` 가 2점 응답 → `EXTERNAL_API_ERROR` 단언 |
| TC-SAM2-11 | PASS | [테스트+정적] `:186-198` `x>imgWidth‖y>imgHeight` → 502. `Sam2SegmentServiceTest#응답_폴리곤_좌표_이미지경계_초과시_오류`(100×100 이미지에 x=150). ※경계 상한이 segment 전용인 것은 확정 정책 방향(★3 AI 응답 축)과 별개의 **미적용 잔여**이며 카탈로그 오참조를 정정함(아래 §3) |
| TC-SAM2-12 | PASS | [실동작] `simplifyTolerance:60` → **400** `"simplifyTolerance: 경계 세밀함은 50.0 이하여야 합니다."` [정적] `Sam2SegmentRequest.java:35-36` |
| TC-SAM2-13 | PASS | [테스트+정적] `:166-169` 단순화 결과 <3점이면 `outPolygon = aiRes.polygon()`. `Sam2SegmentServiceTest#AI분할_simplify_결과가_3점미만이면_원본폴리곤유지`(tolerance 100 → 원본 5점 유지) |
| TC-SAM2-14 | PASS | [실동작] `POST /v1/frames/468/sam2-track` nextSrcSns=[469,470] → **200**, 프레임별 폴리곤 7점씩 2건, `score` 0.9834/0.9818(**프레임마다 다름 = 시드 복제 아님**). DB 미저장 |
| TC-SAM2-15 | PASS | [실동작] path 469 / body 468 → **400** [정적] `LabelController.java:137-142` |
| TC-SAM2-16 | PASS | [실동작] nextSrcSns 51개 → **400** `"nextSrcSns: size must be between 0 and 50"`. 경계 50개는 검증 통과 후 인가 단계 도달(403) → **상한이 정확히 50** [정적] `label/Sam2TrackRequest.java:33`. FE 도 `SAM2_TRACK_CHUNK_SIZE=50` 청크 분할(`features/label/api.ts:632,717`) |
| TC-SAM2-17 | PASS | [실동작] `nextSrcSns:[]` → **400** `"must not be empty"` |
| TC-SAM2-18 | PASS | [실동작] prevPolygon 2점 → **400** `"prevPolygon: size must be between 3 and 1000"` |
| TC-SAM2-19 | PASS | [실동작] prevPolygon 1001점 → **400** 동일 |
| TC-SAM2-20 | PASS | [실동작] trackId 65자 → **400** `"trackId: size must be between 0 and 64"` |
| TC-SAM2-21 | PASS | [실동작] 시작 468(배정) + 후속 429(미배정) → **403**. 루프 선두 `accessGuard.verifyAccess(nextSrcSn)`(`:99`)가 **그 프레임의 ai 호출 이전**에 판정 |
| TC-SAM2-22 | PASS | [실동작] 후속 srcSn 999999 → **404**. ⚠ 근거 드리프트 — 실측 메시지가 `"프레임을 찾을 수 없습니다."`(가드 문구)로, 카탈로그 근거 `:102` 의 `"후속 프레임을 찾을 수 없습니다: 999999"` 가 **아니다**. 카탈로그 정정함(아래 §3) |
| TC-SAM2-23 | PASS | [테스트+정적] `Sam2TrackService.java:233-236` = `validateResponseMinPoints`(502) **먼저** → `validatePolygon`(400). 좌표 형식 위반은 400 유지: `Sam2TrackServiceTest#sam2_track_ai응답폴리곤_검증실패시_400`(음수 좌표 3점 → `INVALID_INPUT`). 카탈로그의 "정점 수를 먼저 판정한다" 서술이 코드 순서와 일치 |
| TC-SAM2-24 | PASS | [실동작] `shape:"BBOX"` → **200** `points:[[40,8],[79,178]]` = 폴리곤 외접박스 [[minX,minY],[maxX,maxY]] |
| TC-SAM2-25 | PASS | [테스트+정적] `:153-159`(BBOX 만 `continue` 스킵) + `:206-208`(`MIN_BBOX_EXTENT=1.0`). `Sam2TrackServiceTest#추적_박스형태_퇴화폴리곤은_해당프레임만_스킵한다`. POLYGON 퇴화는 TC-SAM2-34(502)가 담당 |
| TC-SAM2-26 | PASS | [실동작] `shape:null` → **200** `shapeType:"POLYGON"` [정적] `:80` `shapeOrDefault()` |
| TC-SAM2-27 | PASS | [테스트+정적] `:114-123` catch → `EXTERNAL_API_ERROR`(502) + 예외 원문 미노출(LogSanitizer 서버로그 전용). `Sam2TrackServiceTest#sam2_track_ai호출실패시_502`·`#sam2_track_ai응답_polygon_null이면_502`. segment 대응: `Sam2SegmentInputValidationTest#segment_외부호출_실패시_내부URL이_노출되지_않는다` |
| TC-SAM2-28 | PASS | [실동작] trackId `"t\r\nINJECTED"` → **400** `"트랙 ID 는 영숫자와 . _ : - 만 사용할 수 있습니다."` — CRLF 는 `@Pattern`(`label/Sam2TrackRequest.java:29`)이 **선차단**하며 `LogSanitizer`(`:139-140`,`:171`)는 심층방어. 기대결과 보강(아래 §3) |
| TC-SAM2-29 | PASS | [실동작] REVIEWER 토큰 + srcSn 429(rawSn 900 `DE_IDENT_YN='F'`) → **412** `"비식별 재처리 대기 중인 영상은 AI 추론을 실행할 수 없습니다."`. `FrameImageEncoder.requireNotUnderDeidentReport`(`:206-217`)가 `resolveFrameImageWithoutGate` **앞**에서 판정 → 파일 read·base64 인코딩 0회. **역할 무관 차단 확인(REVIEWER 도 412)** |
| TC-SAM2-30 | PASS | [실동작] 동일 조건 track → **412** 동일 메시지. 시작·후속 프레임 인코딩이 모두 `encodeFrame`(`:178-180`) 경유 → 프레임마다 재판정. 회귀 가드 `AiInferenceDeidentReportGateTest`(SAM2 분할/추적/파생/YOLO 15건) |
| TC-SAM2-31 | PASS | [정적] `grep -rn "encodeToBase64" backend/src/main` → **0건**. `FrameImageEncoder` 의 public 진입점은 `resolveFrameImageForInference`·`encodeFrame`·`encodeDeidentifiedFrameForInference` 뿐이고 전부 `LsDataSrc` 를 받아 게이트를 통과한 뒤 private `encode(Path)`(`:190-197`)로 수렴. 경로 문자열 오버로드 없음. ⚠ 부수 발견 — `encodeDeidentifiedFrameForInference` 는 포털 SAM2 제거 이후 **프로덕션 호출부 0건**(아래 C-ISSUE-85) |
| TC-SAM2-32 | PASS | [정적] `resolveFrameImageWithoutGate`(`:94`)에 접근제어자 없음 = package-private. 유일 소비자 `FrameBoundsResolver.java:101` 은 `ImageIO.read` 로 **치수만** 읽고 픽셀 미유출. ⚠ 카탈로그·프로덕션 javadoc 의 "**패키지 외** 소비자" 서술은 사실 오류 — `FrameBoundsResolver` 는 `kr.co.cudo.authoring.label.service` 로 **동일 패키지**다. 카탈로그 정정함(§3), 코드 javadoc 은 C-ISSUE-86 |
| TC-SAM2-33 | PASS | [실동작] srcSn 45(rawSn 18 파생, `src_file_path_nm` **NULL**) → segment **200** 9점 폴리곤 / track **200** 7점. "이미지 경로가 비어있습니다"(400) 미발생 → `FrameImageEncoder:98-105` 비식별 우선 해석 성립 |
| TC-SAM2-34 | PASS | [테스트+정적] `Sam2CoordinateValidator.validateResponseMinPoints`(`:49-54`, `MIN_POLYGON_POINTS=3` `:31`) → `EXTERNAL_API_ERROR`. `Sam2TrackServiceTest#SAM2_track_응답_폴리곤_2점이면_502` + 과차단 회귀 가드 `#SAM2_track_응답_폴리곤_3점이면_정상` |
| TC-SAM2-35 | PASS | [실동작] ① segment `points:[[50,50]]` **200**(1점 클릭 정상) ② track `prevPolygon` 2점 **400** `@Size(min=3)`. 요청 축 400 ↔ 응답 축 502 분리 유지. [정적] 공용 `validatePolygon`(`Sam2CoordinateValidator.java:65-77`)에 최소 정점 수 **없음** 확인 |
| **TC-SAM2-36**(신설) | PASS | [실동작] 화면 밖 `prevPolygon:[[9000,9000],…]` 로 mock 유도 → **200** `{"tracked":[]}` + `message:"AI 모델 미로드 — 결과 신뢰 불가"`. **1차 C-ISSUE-81(HIGH) 실동작 해소 확인** — 구 동작이면 시드 폴리곤 2건이 `score 0.9` 로 반환됐어야 한다. 판정이 `AiMockMeta.untrusted`(`:50-52` 긍정 증명)라 mock 메타 생략 응답도 fail-closed. 가드: `AiMockMetaTest`(5) · `Sam2MockMetaDriftTest` · `Sam2TrackServiceTest` mock 4건 |
| **TC-SAM2-37**(신설) | PASS | [테스트+정적] `Sam2TrackService.java:136-144` 가 mock 프레임만 `continue` 하고 실결과는 유지, `:172` `Sam2TrackOutcome.of(...,anyMock)` → `PARTIAL_MOCK_MESSAGE`. `Sam2TrackServiceTest#추적_일부프레임만_mock이면_실결과는_유지하고_부분안내를_준다`·`#추적_실모델(mock아님)_응답은_그대로_자동적용되고_안내가_없다`. FE 청크 누적 `features/label/api.ts:745-746,:753-757` + `hooks/useSam2Track.ts:75`. ※특정 프레임만 mock 으로 유도할 결정적 수단이 없어 실동작 재현은 미수행 |

### 집계 (37건 = 원 35 + 신설 2)

| PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|:--:|:--:|:--:|:--:|:--:|:--:|
| **37** | 0 | 0 | 0 | 0 | 0 |

> 기대결과 단언 기준으로는 전건 통과다. 다만 **케이스 기대결과 바깥**에서 발견한 부수 결함·이월 미해소가 9건 있어 아래에 전건 기록한다(§2). "케이스가 PASS 라 문제 없음"으로 읽지 말 것.

---

## 2. 이슈 기록

> ⚠ **번호 충돌 주의**: 본 파트 이슈 번호는 **2026-08-03 3차 회차의 part5 배정 번호(C-ISSUE-81~)** 다.
> 2026-08-01 1차 ISSUES.md 의 동명 번호(C-ISSUE-81~86)와 **무관**하다. 1차 이슈와의 대응은 각 블록에 명시했다.

---

### [C-ISSUE-81] TC-SAM2-01 (부수) — `Sam2SegmentService` 의 `@Transactional` 이 여전히 남아 AI 블로킹 호출 내내 DB 커넥션을 점유한다 (1차 C-ISSUE-83 **미해소 이월**)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: SAM2 분할은 **DB write 가 없는 stateless 프록시**다(클래스 javadoc `Sam2SegmentService.java:41` — "BE 는 DB 저장하지 않는 stateless 프록시"). 파일 I/O + `ImageIO.read` + base64 + **최대 60s 블로킹 AI 호출** 구간에서 control HikariCP 커넥션을 쥐면 안 된다. 형제 서비스 `Sam2TrackService` 는 이미 이 이유로 트랜잭션을 제거했고 근거를 코드에 남겼다(`Sam2TrackService.java:38-39`: *"비트랜잭셔널(F-1 커넥션풀 고갈 방지) … AI 블로킹 호출 구간이 control HikariCP 커넥션을 점유하지 않는다"*). 프로젝트 규약도 동일하다(`CLAUDE.md` — *"프레임 이미지 서빙은 트랜잭션 밖에서 파일 I/O 를 한다 … 커넥션을 쥔 채 NAS I/O 를 하면 커넥션 기아로 간다 — 전례 있음"*).
- **현재 동작(이슈 내용)**: segment 만 트랜잭션이 남아 있고, 1차 지적 이후 코드가 바뀌지 않았다.
  ```java
  // backend/.../label/service/Sam2SegmentService.java:62-66
  @Slf4j
  @Service
  @RequiredArgsConstructor
  @Transactional(value = "controlTransactionManager", readOnly = true)   // ← 여전히 존재
  public class Sam2SegmentService {
      public Sam2SegmentResponse segment(...) {          // :91  프록시 경유 public
          accessGuard.verifyAccess(req.srcSn(), actor);  // :93  DB 접근 → 커넥션 획득
          …
          aiRes = aiServerClient.segment(aiReq).block(); // :130 최대 60s 블로킹
  ```
  **3차 실측(반증 재현)** — segment 6건 동시 호출 중 `pg_stat_activity` 샘플링:
  ```
   state               | count
   active              |     1
   idle                |     4
   idle in transaction |     6     ← 6건 전부 AI 호출 동안 커넥션 점유 (1차와 동일)
  ```
  대조군인 track 은 `idle in transaction` 0건이다(1차 실측, 코드 무변경).
- **재현/확인 경로**:
  ```bash
  W=$(<worker.token)
  for i in 468 469 470 471 472 473; do curl -s -o /dev/null -X POST \
    -H "Authorization: Bearer $W" -H 'Content-Type: application/json' \
    -d "{\"srcSn\":$i,\"points\":[[50,50]]}" \
    localhost:18081/api/v1/frames/$i/sam2-segment & done
  sleep 1.2
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select state, count(*) from pg_stat_activity where datname='klid_system' group by 1;"
  ```
- **영향**: 가용성/성능 — CWE-400 Uncontrolled Resource Consumption · OWASP API4:2023. 운영 풀은 `application-prd.yml` `maximum-pool-size: 20`(control/portal 각각). 라벨링 작업자 20명이 동시에 클릭 분할하면 control 풀이 소진되고, ai-server 가 느려질수록(60s 타임아웃) 점유 시간이 그대로 늘어나 **SAM2 와 무관한 API(라벨 저장·검수·배치)까지 동반 지연/실패**한다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. 클래스 `@Transactional` 제거 → DB 조회(`accessGuard.verifyAccess` + `srcRepository.findById` + 신고게이트 조회)만 별도 `@Transactional(readOnly)` 조회 빈으로 분리하고 **파일 I/O·`ImageIO.read`·base64·AI 호출은 트랜잭션 밖**에서 수행한다. 같은 목적의 선례 `FrameImageLookupService` 패턴을 그대로 따르면 된다. ⚠ 자기호출(self-invocation)로 프록시를 우회하면 효과가 없다. 회귀 가드는 `FrameImageServingHardeningTest` 와 동형으로 "SAM2 분할 빈에 `@Transactional` 이 없다"를 구조 단언으로 고정.

---

### [C-ISSUE-82] TC-SAM2-06 (부수) — `Sam2SegmentService` 클래스 javadoc 과 **공개 Swagger 설명**이 "SAM2 는 원본 이미지에만 실행"이라 실제 동작(비식별 우선)과 정반대다 (1차 C-ISSUE-84 ② **미해소 이월** + 신규 표면 1건)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 2026-07-30 확정 정책 — **라벨링 캔버스는 비식별 프레임을 서빙**하며 온라인(사용자 트리거) SAM2 도 같은 픽셀을 대상으로 해야 좌표가 맞는다. 문서·주석은 이 동작을 그대로 서술해야 한다. 특히 Swagger 설명은 **OpenAPI 로 외부에 나가는 계약 문서**라, 여기에 "원본 이미지에만 실행"이 적혀 있으면 관제/연동 상대가 저작도구가 비식별 전 픽셀을 AI 로 보낸다고 오해한다(개인정보 처리 방침 오기).
- **현재 동작(이슈 내용)**: 1차에서 dead code `resolveSafe` 삭제와 경로가드 javadoc(`:53-56`) 정정은 반영됐으나, **이미지 소스 정책 서술 2곳은 그대로 남았다.**
  ```java
  // Sam2SegmentService.java:43-45  (클래스 javadoc)
  * <p>이미지 소스 정책: CLAUDE.md "오토라벨링" 규칙에 따라 SAM2 는 <b>원본 이미지에만 실행</b>한다.
  * 원본과 비식별본은 동일 해상도이므로 좌표를 공유하며, 별도 비식별 추론은 수행하지 않는다.
  * (기존 {@link Sam2TrackService} 와 동일하게 {@code srcFilePathNm} = 원본 프레임 경로 사용.)

  // LabelController.java:155  (Swagger @Operation description — 신규 발견 표면)
  + "path srcSn 과 body srcSn 불일치 시 400 (CWE-345). 정책상 원본 이미지에만 실행."
  ```
  실제 코드는 정반대다 — `Sam2SegmentService.java:112` → `FrameImageEncoder.resolveFrameImageForInference` → `resolveFrameImageWithoutGate`(`:98-105`)가 **`DE_IDNTF_SRC_FILE_PATH_NM` 을 먼저** 검증·반환하고, 없을 때만 원본으로 폴백한다.
  **실동작 반증(결정적)**: srcSn 45(rawSn 18 파생 프레임)는 `SRC_FILE_PATH_NM` 이 **NULL** 인데 segment 가 **200 + 9점 폴리곤**을 반환했다 — 원본 경로만 쓴다면 물리적으로 불가능하다.
  ```
  psql> SELECT src_sn, src_file_path_nm IS NULL FROM ls_data_src WHERE src_sn=45;  →  45 | t
  curl POST /v1/frames/45/sam2-segment {"srcSn":45,"points":[[50,50]]}
    → 200 {"polygon":[[76,9],…],"score":0.9652,"empty":false}
  ```
- **재현/확인 경로**: `grep -n "원본 이미지에만" backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java backend/src/main/java/kr/co/cudo/authoring/label/controller/LabelController.java` → 2건. 위 srcSn 45 curl. Swagger 는 `GET /api/v3/api-docs` 의 `/v1/frames/{srcSn}/sam2-segment` description 에서 확인.
- **영향**: 유지보수·감사(주석이 개인정보 처리 동작을 잘못 서술 — 감리 지적 가능) + **외부 계약 문서 오기**(OpenAPI). 런타임 보안 영향은 없다(실동작은 정책 준수 방향). `CLAUDE.md` 가 경고한 *"컨트롤러가 정책 판정을 복제 보유하면 정책 갱신 때 조용히 뒤처진다"* 의 문서판이며, 다음 검증자가 "원본을 쓴다"를 믿고 비식별 서빙 회귀를 놓칠 위험이 있다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. ① `Sam2SegmentService.java:43-45` 를 "**비식별 우선 해석**(`FrameImageEncoder.resolveFrameImageForInference`) — 캔버스가 보여주는 픽셀과 분할 대상이 같아야 좌표가 맞는다. 원본 폴백은 비식별 경로 부재 시에만"으로 정정. ② `LabelController.java:155` 의 "정책상 원본 이미지에만 실행" 문구를 삭제하고 "비식별 프레임 기준 실행"으로 교체. ③ `CLAUDE.md` "오토라벨링" 절에 **온라인(사용자 트리거) SAM2 = 비식별본 / 배치 YOLO·SAM2 = 원본** 구분을 명시(현재 절은 배치 기준만 서술).

---

### [C-ISSUE-83] TC-SAM2-09 (부수) — mock 안내 메시지가 사유와 무관하게 "AI 모델 미로드"로 고정돼 오진단을 유도한다 (1차 C-ISSUE-86 **미해소 이월**)

- **심각도**: LOW
- **기대 동작(기대효과)**: 사용자·운영자에게 나가는 안내는 실제 사유와 일치해야 한다. ai-server 는 사유를 4종으로 구분해 내려준다(`mock_reason` ∈ `env_mock` | `weights_missing` | `load_failed` | `empty_mask`)이며 BE DTO 도 이미 파싱한다(`Sam2Response.java:23`, `Sam2TrackResponse.java:31`).
- **현재 동작(이슈 내용)**: BE 는 사유를 무시하고 단일 문자열을 세팅한다.
  ```java
  // label/dto/Sam2SegmentResponse.java:25
  public static final String MOCK_UNAVAILABLE_MESSAGE = "AI 모델 미로드 — 결과 신뢰 불가";
  // LabelController.java:179-181 — res.isEmpty() 이면 무조건 위 문자열
  // label/dto/Sam2TrackOutcome.java:20 — track 도 같은 상수를 재사용
  ```
  **3차 실측(1차보다 강한 증거)**: 이번 스택은 SAM2 가 **HF 캐시에서 정상 로드**돼 있다(같은 프레임에 정상 프롬프트를 주면 `score 0.9652` 실결과 반환). 그런데 마스크가 안 잡히는 프롬프트를 주면:
  ```
  POST /v1/frames/468/sam2-segment {"srcSn":468,"box":[100,100,10,10]}
  → 200 {"polygon":[],"score":0.0,"empty":true}  message:"AI 모델 미로드 — 결과 신뢰 불가"
  ```
  ai-server 실제 사유는 `empty_mask`(`ai-server/app/routers/sam2.py:328-331`)이고 **모델은 로드돼 있다.** 실제 의미는 "프롬프트 위치에 객체가 없음"이다. track 도 동일(`_prev_polygon_fallback` reason=`empty_mask` → 같은 문구).
- **재현/확인 경로**: 위 curl 2줄. 대조로 `{"points":[[50,50]]}` 을 주면 같은 프레임에서 실결과 200 이 나온다(= 모델은 살아 있음).
- **영향**: 운영/UX. 작업자가 "서버 장애"로 오인해 불필요한 에스컬레이션을 하거나, 반대로 진짜 `weights_missing`(현재 YOLO 가 그 상태다) 상황이 "늘 뜨는 메시지"로 묻힌다. 자동적용 차단 동작 자체는 정상이므로 데이터 오염 위험은 없다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. `mockReason` 을 사유별 메시지로 매핑 — `empty_mask` → "선택 지점에서 객체를 찾지 못했습니다 — 다른 위치를 시도해 주세요", `weights_missing`/`load_failed`/`env_mock` → 현행 문구. **빈 폴리곤(자동적용 차단) 동작은 두 경우 모두 유지**한다. 매핑은 segment·track 이 공유하도록 `Sam2SegmentResponse`/`Sam2TrackOutcome` 상수 대신 공용 헬퍼로 뺀다. FE(`OverlayLayer.onMockWarning`·`useSam2Track`)는 `message` 를 그대로 표시하므로 BE 만 고치면 된다.

---

### [C-ISSUE-84] TC-SAM2-11 / TC-SAM2-14 — SAM2 **track** 응답의 `score` 가 클램프·반올림 없이 원본 그대로 나간다 (1차 C-ISSUE-82 ③ **잔여**)

- **심각도**: LOW
- **기대 동작(기대효과)**: "외부 응답 불신" 원칙상 신뢰도도 계약 범위 [0,1] 로 강제돼야 한다. segment 는 이미 그렇게 한다 — `Sam2SegmentService.clampScore`(`:242-246`)가 `NaN→0.0`, `[0,1]` 클램프, 4자리 반올림을 적용한다. FE 는 이 값으로 저신뢰 분기(`SAM_LOW_CONFIDENCE_THRESHOLD`)를 태운다.
- **현재 동작(이슈 내용)**: track 은 ai 원본값을 그대로 싣는다.
  ```java
  // Sam2TrackService.java:160-166 — clampScore 호출 없음
  tracked.add(new Sam2TrackResponseDto.TrackedItem(
          nextSrcSn, aiRes.trackId(), req.label(), bbox, aiRes.score(), …));
  ```
  **실측**:
  ```
  POST /v1/frames/468/sam2-track … → 200
  {"tracked":[{"srcSn":469,…,"score":0.9834924936294556,"shapeType":"POLYGON"}, …]}
  ```
  segment 는 같은 스택에서 `"score":0.9652`(4자리)로 나간다. ai-server 가 계약 밖 값(음수·>1·NaN)을 주면 track 만 그대로 통과한다.
- **재현/확인 경로**: 위 두 엔드포인트 응답의 `score` 자릿수 비교. 계약 위반값 재현은 ai-server 스텁 필요(단위 레벨).
- **영향**: 기능/UX(FE 저신뢰 임계 판정의 입력이 계약 밖 값일 수 있음) + 표현 비일관. 보안 영향 없음. ⚠ **이미지 경계 상한 미적용**은 별개 축으로, 카탈로그 TC-SAM2-11 이 "track·오토라벨은 **의도적** 미적용"으로 고정하고 있으므로 본 이슈에 포함하지 않는다(다만 그 비고의 교차참조 번호가 틀려 정정함 — §3).
- **수정 방향(제안)**: ⚠ 구현하지 않음. `clampScore` 를 `Sam2SegmentService` 의 private 에서 공용 유틸(`Sam2CoordinateValidator` 또는 신설 `Sam2ScorePolicy`)로 올려 track 의 두 `tracked.add` 지점에 적용. 단위테스트로 `NaN`·`-0.1`·`1.5` 3케이스 고정.

---

### [C-ISSUE-85] TC-SAM2-31 (부수) — 포털 SAM2 제거 후 `encodeDeidentifiedFrameForInference` 가 **프로덕션 호출부 0건 dead code** 이고, 클래스 javadoc 이 폐지된 포털 SAM2 를 여전히 소비자로 서술한다

- **심각도**: LOW
- **기대 동작(기대효과)**: "단일 진입점" 클래스의 javadoc 은 **현재 실제 소비자**를 정확히 나열해야 한다. 이 클래스는 그 정확성 자체가 보안 통제(다음 사람이 "여기만 보면 된다"고 믿는 근거)이며, 클래스 스스로도 그렇게 경고한다(`:47-48`: *"**\"모든 전송이 이 클래스를 통과한다\"고 쓰지 말 것** — 사실이 아니고, 다음 사람이 그 문장을 믿고 검사를 생략한다"*).
- **현재 동작(이슈 내용)**: 포털 SAM2 컨트롤러·서비스가 제거됐는데(회귀 가드 `portal/PortalSam2RemovedTest.java` — "포털(외부 채널) SAM2 도구 **완전 제거**") javadoc 과 메서드는 남았다.
  ```java
  // FrameImageEncoder.java:34-36
  * <b>사용자 요청으로 실행되는</b> 프레임 전송 경로는 예외 없이 {@link #resolveFrameImageForInference}
  * / {@link #encodeFrame} / {@link #encodeDeidentifiedFrameForInference} 를 거친다 — 내부 채널의
  * SAM2 분할·SAM2 추적·YOLO 추적·온라인 오토라벨, 외부 채널의 포털 SAM2 분할/추적이 전부 해당한다.
  //                                              ^^^^^^^^^^^^^^^^^^^^^^ 이미 제거된 경로
  // :138-148
  * <b>비식별본 전용</b> 외부 추론 전송 진입점 … <p>포털(외부 채널)용이다.
  public String encodeDeidentifiedFrameForInference(LsDataSrc frame) { … }
  ```
  호출부 실측:
  ```
  $ grep -rn "encodeDeidentifiedFrameForInference" backend/src/main backend/src/test
  main/.../FrameImageEncoder.java:35   (javadoc)
  main/.../FrameImageEncoder.java:148  (정의)
  test/.../FrameImageEncoderTest.java:103  (테스트만)
  ```
  → **프로덕션 호출부 0건.**
- **재현/확인 경로**: 위 grep. 포털 SAM2 부재는 `PortalSam2RemovedTest` + `grep -rn "sam2" frontend/src/features/portal` 로 교차 확인.
- **영향**: 유지보수/검증 신뢰도. TC-SAM2-31 의 구조 단언("게이트 없는 base64 오버로드 부재")은 여전히 성립하나, **public 이면서 호출부 없는 진입점**은 향후 누군가 "포털용이 있네" 하며 다시 외부 채널을 여는 진입 유혹이 된다(이 클래스가 이미 겪은 사고 유형이다). 런타임 영향 없음.
- **수정 방향(제안)**: ⚠ 구현하지 않음. ① 메서드를 제거하거나(테스트 동반 삭제) 최소한 package-private 로 좁히고 `@Deprecated` + "현재 소비자 없음" 명시 ② `:34-36` 의 "외부 채널의 포털 SAM2 분할/추적" 문구 삭제 ③ 판단 근거를 남길 것 — 포털이 다시 SAM2 를 갖게 되는 일은 ADR-013 상 없다.

---

### [C-ISSUE-86] TC-SAM2-32 — `FrameImageEncoder` javadoc 의 "유일한 **패키지 외부** 소비자" 서술이 사실과 다르다 (1차 C-ISSUE-84 ③ **미해소 이월**, 카탈로그 측은 이번에 정정)

- **심각도**: LOW
- **기대 동작(기대효과)**: `resolveFrameImageWithoutGate` 를 package-private 으로 좁힌 이유가 주석에 정확히 남아야 한다. 이 케이스의 단언은 "**패키지 밖 소비자가 0건**"이며, 그래야 게이트 없는 해석기가 외부로 새지 않는다는 보장이 성립한다.
- **현재 동작(이슈 내용)**:
  ```java
  // FrameImageEncoder.java:86-88
  * <p>현재 유일한 패키지 외부 소비자는 {@code FrameBoundsResolver} — 라벨 좌표 정규화를 위해
  * 이미지 <b>치수만</b> 읽고 픽셀을 밖으로 내보내지 않으므로 게이트 대상이 아니다.
  ```
  실제로는 두 클래스가 같은 패키지다:
  ```
  FrameImageEncoder.java:1    package kr.co.cudo.authoring.label.service;
  FrameBoundsResolver.java:1  package kr.co.cudo.authoring.label.service;
  ```
  package-private 메서드는 애초에 패키지 밖에서 호출될 수 없으므로 "패키지 외부 소비자"라는 표현 자체가 성립하지 않는다.
- **재현/확인 경로**: `head -1 backend/src/main/java/kr/co/cudo/authoring/label/service/{FrameImageEncoder,FrameBoundsResolver}.java`
- **영향**: 유지보수/검증 신뢰도(LOW). 실제 접근제어는 정상이며 런타임 영향 없음. 다만 이 문장을 근거로 "패키지 밖에도 소비자가 있다 → 그러면 public 으로 올려도 되겠다"는 오판이 나올 수 있다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. "현재 유일한 소비자는 **동일 패키지의** `FrameBoundsResolver` 이며 **패키지 밖 소비자는 0건**이다"로 정정. 구조 회귀 가드로 "이 메서드에 public 접근제어자가 없다"를 ArchUnit 류로 고정하는 것도 검토.

---

### [C-ISSUE-87] TC-SAM2-06 (부수) — `Sam2SegmentService` 에 미사용 `@Value` 필드 `storageRawPath` 잔존 (1차 C-ISSUE-84 ① **부분 미해소**)

- **심각도**: LOW
- **기대 동작(기대효과)**: 1차 지적의 취지는 "이 서비스는 경로를 스스로 조립하지 않는다"를 코드로도 참으로 만드는 것이었다. dead 메서드 `resolveSafe` 는 삭제됐으나 그 메서드가 쓰던 설정 주입 필드가 남아, 여전히 "이 클래스가 스토리지 base 를 안다"는 신호를 준다.
- **현재 동작(이슈 내용)**:
  ```java
  // Sam2SegmentService.java:84-85
  @Value("${authoring.storage.raw-path:./storage/raw}")
  private String storageRawPath;      // ← 클래스 내 참조 0건
  ```
  ```
  $ grep -n "storageRawPath" .../Sam2SegmentService.java
  85:    private String storageRawPath;      ← 선언 1건뿐
  ```
- **재현/확인 경로**: 위 grep.
- **영향**: 코드 위생/유지보수(LOW). 런타임 영향 없음(빈 필드 주입 1회). 다만 `resolveSafe` 를 다시 만들고 싶어지는 "재료"가 남아 있어 판정 지점 이중화(=정책 드리프트) 재발 유혹이 된다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. 필드와 `@Value` 삭제, `import org.springframework.beans.factory.annotation.Value` 정리.

---

### [C-ISSUE-88] TC-SAM2-22 — `Sam2TrackService:101-102` 의 후속 프레임 404 는 **도달 불가**하다 (근거 드리프트 — 카탈로그는 이번에 정정)

- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그 근거 `file:line` 은 **실제로 실행되는 방어 지점**을 가리켜야 한다. 그래야 다음 수정자가 그 줄을 지웠을 때 회귀를 인지한다.
- **현재 동작(이슈 내용)**: 루프 선두의 인가 검사가 먼저 404 를 낸다.
  ```java
  // Sam2TrackService.java:97-102
  for (Long nextSrcSn : req.nextSrcSns()) {
      accessGuard.verifyAccess(nextSrcSn, actor);            // :99  ← 여기서 404
      LsDataSrc nextSrc = srcRepository.findById(nextSrcSn)
              .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                      "후속 프레임을 찾을 수 없습니다: " + nextSrcSn));   // :102 ← 도달 불가
  ```
  `LabelAccessGuard.verifyAndGet`(`:55`, `:58`)이 존재하지 않는 `srcSn` 에 대해 `"프레임을 찾을 수 없습니다."` 로 먼저 던진다.
  **실측**:
  ```
  POST /v1/frames/468/sam2-track {"nextSrcSns":[999999], …}
  → 404 {"message":"프레임을 찾을 수 없습니다.","errorCode":"NOT_FOUND"}
      (":102" 의 "후속 프레임을 찾을 수 없습니다: 999999" 가 아님)
  ```
- **재현/확인 경로**: 위 curl 의 응답 message 문자열 비교.
- **영향**: 카탈로그 정합성(검증 자체의 신뢰도). 동작은 기대대로 404 이므로 기능·보안 영향 없음. `:101-102` 는 방어심층으로 유지할 가치가 있으나 "여기가 404 의 출처"라는 서술은 틀리다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. **카탈로그는 본 파트에서 이미 정정**(§3-2). 코드 측은 선택 — `:102` 메시지를 가드와 통일하거나, 주석으로 "가드가 먼저 판정하므로 방어심층"임을 명시.

---

### [C-ISSUE-89] TC-SAM2-23/34/36 (구조 관찰) — track 은 **응답 좌표 검증(:128) → mock 판정(:136)** 순서이고 segment 는 **mock 판정(:145) → 응답 검증(:151)** 로 반대다

- **심각도**: LOW (현재 도달 불가 — 예방적 기록)
- **기대 동작(기대효과)**: mock 응답은 "외부가 잘못 준 좌표"가 아니라 "신뢰할 수 없는 모드"이므로, 두 경로 모두 **mock 을 먼저 판정해 우아하게 제외/빈 결과**로 마감하는 편이 일관적이다. 검증을 먼저 두면 mock 응답이 502/400 오류로 나가 "부분 추적 + 안내"라는 설계된 UX 가 깨진다.
- **현재 동작(이슈 내용)**:
  ```java
  // Sam2TrackService.java:127-136
  validateResponsePolygon(aiRes.polygon(), "ai-server polygon");  // :128  502/400
  …
  if (aiRes.untrusted()) { anyMock = true; … continue; }          // :136  mock 제외
  ```
  ```java
  // Sam2SegmentService.java:145-151  — 반대 순서
  if (aiRes.untrusted()) { return Sam2SegmentResponse.empty(); }  // :145
  validatePolygon(aiRes.polygon(), imgWidth, imgHeight);          // :151
  ```
- **재현/확인 경로**: 현재는 **도달 불가**임을 확인했다 — ai-server 의 track mock 두 경로(`_mock_track` `sam2.py:443-455` / `_prev_polygon_fallback` `:351-366`)가 모두 **BE 가 보낸 `prev_polygon` 을 되돌려주고**, 그 값은 요청 축(`@Size(min=3)`) 또는 직전 응답 축(`validateResponseMinPoints`)으로 이미 3점 이상·유한·비음수임이 보장되기 때문이다. 단 `_echo_polygon`(`:99-117`)이 변환 불가 원소를 **버리므로**, ai-server 가 향후 좌표를 가공하면 mock 응답이 2점으로 줄어 **502 로 나갈 수 있다**(설계 의도는 "빈 결과 + 안내").
- **영향**: 현재 0. 계약 드리프트 시 UX 저하(안내 대신 502). 보안·데이터 정합 영향 없음.
- **수정 방향(제안)**: ⚠ 구현하지 않음. track 의 `untrusted()` 판정을 `validateResponsePolygon` **앞으로** 이동해 segment 와 순서를 맞춘다(둘 다 "신뢰 판정 → 내용 검증"). 회귀 가드로 "mock=true + 2점 폴리곤 응답 → 502 가 아니라 프레임 제외 + 안내"를 단위테스트로 고정.

---

## 3. 카탈로그 정정 (담당 라인범위 내 Edit 완료 — 4건 정정 + 2건 신설)

> 프로덕션·테스트 코드는 일절 수정하지 않았다. 아래는 `docs/test-cases/C-marking-labeling.md` C-3 절만 대상이다.

### 3-1. TC-SAM2-11 — 교차참조 번호 오류 + 누락 축 보강
- **정정 전**: "track·오토라벨은 의도적으로 미적용(별건 **C-ISSUE-61**)"
- **문제**: 1차 `C-ISSUE-61` 은 `GET /v1/deident-reports` 정렬 키 미검증 이슈로 **완전히 무관**하다. 실제 대응 이슈는 1차 `C-ISSUE-82 ③`(경계 상한·score 미검증).
- **정정 후**: 교차참조를 `C-ISSUE-82 ③` 으로 바꾸고 오참조 사실을 명시. 아울러 **score 클램프도 segment 전용**임을 실측값과 함께 비고에 추가(근거 `:242-246` 병기).

### 3-2. TC-SAM2-22 — 근거 `file:line` 드리프트 정정
- **정정 전**: 근거 `Sam2TrackService.java:101-102`
- **문제**: 실측 404 메시지가 가드 문구(`"프레임을 찾을 수 없습니다."`)로, `:102` 는 도달하지 않는다(C-ISSUE-88).
- **정정 후**: 근거를 `Sam2TrackService.java:99 · LabelAccessGuard.java:55, :58` 로 교체하고 `:101-102` 는 "도달 불가 방어심층"으로 괄호 표기.

### 3-3. TC-SAM2-28 — 기대결과 보강 (실동작이 카탈로그보다 강함)
- **정정 전**: "CRLF → LogSanitizer 정제"
- **문제**: 실제로는 `@Pattern("^[A-Za-z0-9._:-]+$")` 가 **400 으로 선차단**해 LogSanitizer 까지 도달하지 않는다. 카탈로그대로만 확인하면 "패턴이 완화돼도 통과"하는 무의미한 검증이 된다.
- **정정 후**: 1차 방어선 = `label/Sam2TrackRequest.java:29` `@Pattern`(400, 실측 응답 병기), LogSanitizer 는 심층방어임을 명시하고 근거에 두 지점 모두 기재.

### 3-4. TC-SAM2-32 — 사실 오류 정정
- **정정 전**: "유일한 **패키지 외** 소비자 `FrameBoundsResolver`"
- **문제**: 두 클래스 모두 `kr.co.cudo.authoring.label.service` 로 **동일 패키지**. 이 케이스의 단언은 "패키지 밖 소비자 0건"이어야 한다(C-ISSUE-86).
- **정정 후**: "동일 패키지 유일 소비자 / 패키지 밖 소비자 0건"으로 정정하고, 프로덕션 javadoc `FrameImageEncoder.java:86` 에 같은 오기가 남아 있음을 ⚠ 로 병기.

### 3-5. **TC-SAM2-36 신설** — track mock → 해당 프레임 제외 + 전량 안내
- **사유**: 1차 `C-ISSUE-81`(HIGH, track mock 게이트 부재)이 **수정 반영**됐는데 C-3 에 대응 케이스가 없었다(1차 보고서도 "카탈로그에 track mock 케이스가 **없다** → 신설 필요"로 지적). 2차 타겟 재검증도 "카탈로그에 없어 인접 신규 케이스로 판정"했다. CLAUDE.md 문서 동기화 규칙(**신설**)에 따라 정식 케이스로 승격.
- **내용**: 200 + `tracked:[]` + 전량 안내. 판정 규약이 **긍정 증명**(`AiMockMeta.untrusted`)이라는 점(필드 생략 fail-open 방지)과 mock 플래그를 FE-facing DTO 로 노출하지 않는 계약을 기대결과에 못박음.

### 3-6. **TC-SAM2-37 신설** — track 일부 프레임만 mock → 실결과 유지 + 부분 안내
- **사유**: 전량/부분 두 갈래가 별도 메시지 상수(`MOCK_UNAVAILABLE_MESSAGE` vs `PARTIAL_MOCK_MESSAGE`)로 갈리는데 커버 케이스가 없었다. **과차단 회귀**(실모델 결과까지 버리는 회귀)를 잡는 축이라 별도 케이스가 필요하다.
- **내용**: 실모델 프레임만 반환 + 부분 안내, 전 프레임 실모델이면 `message=null`. mock 프레임도 전파(`currentPolygon`)는 이어간다는 설계 의도와 FE 청크 누적까지 기대결과에 포함.

### 3-7. 미조치 (담당 라인범위 밖 — 병합 담당자 반영 필요)
- 파일 상단 **`## 변경 이력`** 표에 3차 회차 행을 추가해야 한다(정정 4 / 신설 2 / 폐기 0). 이 표는 담당 라인범위(223~268행) 밖이고 다른 part 에이전트와 동시 편집 충돌 위험이 있어 **의도적으로 건드리지 않았다.**
- C-3 절의 케이스 수가 **35 → 37** 로 늘었다. `README.md` 총계·`grep -cE '^\| *~*TC-'` 실측 대조 시 반영 필요.

---

## 4. self-fill 관점 정리 (§9 SUMMARY 입력)

| 항목 | 판정 |
|---|---|
| SAM2 분할/추적 좌표의 출처 | **전량 ai-server 응답**. BE 에 하드코딩 폴백 좌표 0건 — 응답이 null 이거나 polygon 이 null 이면 502 로 마감(`Sam2SegmentService.java:139-141` / `Sam2TrackService.java:124-126`). **self-fill 없음** |
| ai-server 내부 mock 의 self-fill 성격 | ai-server `_mock_segment`(합성 사각형) / `_mock_track`·`_prev_polygon_fallback`(시드 폴리곤 복사, `score` 0.9/0.5)은 **좌표를 자체 합성한다**. → **BE 가 6개 경로 전부에서 차단**: 온라인 segment(`Sam2SegmentService:145`) · 온라인 track(`Sam2TrackService:136`) · 온라인 오토라벨 YOLO(`AutolabelOnlineService:241`) · 온라인 오토라벨 SAM 폴리곤(`:325-330`) · 배치 YOLO(`YoloAutolabelStep:244`) · 배치 SAM2(`Sam2SegmentStep:215`). **1차 self-fill 항목(C-ISSUE-81, track 미차단)은 해소됨** |
| 판정 규약의 fail-open 여부 | `AiMockMeta.untrusted(mock, source) = mock \|\| !"model".equals(source)` — **긍정 증명 요구**. mock 메타를 통째로 생략한 JSON(primitive `boolean` 기본값 `false`)도 불신 처리 = fail-closed. 계약 고정 `Sam2MockMetaDriftTest`·`AiMockMetaTest` |
| 과차단(정상 결과 차단) 회귀 여부 | 없음 — 실모델 응답은 `source="model"` 로 정상 통과함을 실동작으로 확인(segment `score 0.9652` / track 2프레임 반환) |

---

## 5. 이전 회차 이슈 대조

| 이슈 | 1차 내용 | 3차 상태 |
|---|---|---|
| **C-ISSUE-81**(1차, HIGH) | SAM2 **track** 경로에 mock 게이트 없음 → mock 좌표 자동 적용(CWE-345) | **✅ 해소 — 실동작 확인.** mock 유도 시 `tracked:[]` + 안내. DTO 3필드 추가 + `AiMockMeta` 긍정 증명 + `Sam2TrackOutcome` + FE 배선까지 종단 완결. 2차 타겟 재검증(PASS)이 3차 실동작으로 재확인됨. **카탈로그 케이스 신설(TC-SAM2-36/37)로 커버 공백도 해소** |
| **C-ISSUE-82**(1차, MEDIUM) | track 응답 검증이 요청 검증과 동일 코드 → ①400 오귀속 ②정점 수 미검증 ③경계 상한·score 미검증 | **🔶 부분 해소.** ② 해소(`validateResponseMinPoints` → 502). ① 해소(정점 수 축은 502, 좌표 형식 축은 400 유지 — 카탈로그 TC-SAM2-23 이 2축 공존으로 재정의). ③ **미해소** — 경계 상한은 카탈로그가 "의도적 미적용"으로 고정, **score 클램프는 여전히 잔여** → 3차 **C-ISSUE-84** 로 이월 |
| **C-ISSUE-83**(1차, MEDIUM) | `Sam2SegmentService` `@Transactional` 잔존 → AI 호출 중 커넥션 점유 | **❌ 미해소 이월.** 3차 실측에서도 `idle in transaction 6` 재현 → 3차 **C-ISSUE-81** |
| **C-ISSUE-84**(1차, LOW) | ①dead `resolveSafe` ②javadoc 이 "원본 이미지" 로 반대 서술 ③TC-SAM2-32 "패키지 외" 오기 | **🔶 부분 해소.** ① `resolveSafe` 삭제됨(단 미사용 `storageRawPath` 필드 잔존 → 3차 **C-ISSUE-87**). ② **미해소** + **Swagger 표면 1건 추가 발견** → 3차 **C-ISSUE-82**. ③ **미해소**(코드), 카탈로그는 이번에 정정 → 3차 **C-ISSUE-86** |
| **C-ISSUE-86**(1차, LOW) | mock 안내 메시지가 사유 무관 고정 문구 | **❌ 미해소 이월.** 3차에서 `empty_mask` + **모델 정상 로드** 상태로 더 강한 반증 확보 → 3차 **C-ISSUE-83** |
| UNCERTAINTIES #1 (포털 SAM2 노출 = 정책 위반) | 문서 정본, 결함 유지 | **✅ 해소(F 클러스터 소관, 여기서는 교차 확인만).** `PortalSam2Controller`/`PortalSam2Service` **완전 제거**, 회귀 가드 `portal/PortalSam2RemovedTest`(핸들러 부재 404 판정). 부작용으로 `encodeDeidentifiedFrameForInference` 가 dead 가 됨 → 3차 **C-ISSUE-85** |

---

## 6. 실행 로그 요약 (재현용)

```bash
# 토큰
POST /api/v1/dev/tokens {"role":"WORKER","channel":"INTERNAL","userNo":"2001","expSeconds":86400}
POST /api/v1/dev/tokens {"role":"REVIEWER","channel":"INTERNAL","userNo":"1001","expSeconds":86400}

# 정상 (rawSn 101 / srcSn 468~477, WORKER 2001 배정)
POST /v1/frames/468/sam2-segment {"srcSn":468,"points":[[50,50]]}                       → 200 9점 score .9652
POST /v1/frames/468/sam2-track   {"srcSn":468,"trackId":"t1","prevPolygon":[[40,49],[78,9],[79,176],[41,178]],
                                  "label":"person","nextSrcSns":[469,470]}              → 200 2프레임
POST … same + {"shape":"BBOX"}                                                          → 200 [[40,8],[79,178]]

# mock 게이트 (SAM2 실모델 로드 상태에서 empty_mask 유도)
POST /v1/frames/468/sam2-segment {"box":[10000,10000,10001,10001]}                      → 200 polygon:[] + 안내
POST /v1/frames/468/sam2-segment {"box":[100,100,10,10]}                                → 200 polygon:[] + 안내
POST /v1/frames/468/sam2-track   prevPolygon:[[9000,9000],[9010,9000],[9010,9010],[9000,9010]]
                                                                                        → 200 tracked:[] + 안내

# 신고구간 (rawSn 900 DE_IDENT_YN='F', REVIEWER 토큰)
POST /v1/frames/429/sam2-segment                                                        → 412
POST /v1/frames/429/sam2-track                                                          → 412

# IDOR (WORKER 2001)
POST /v1/frames/429/sam2-segment                                                        → 403
POST /v1/frames/468/sam2-track  nextSrcSns:[429]                                        → 403

# 파생영상 프레임 (rawSn 18, SRC_FILE_PATH_NM NULL)
POST /v1/frames/45/sam2-segment                                                         → 200
POST /v1/frames/45/sam2-track   nextSrcSns:[46]                                         → 200

# 입력 검증 (전부 400)
srcSn 불일치 / points+box 동시 / 둘 다 없음 / simplifyTolerance 60 / points 101개 / box 3개
nextSrcSns 51개·[] / prevPolygon 2점·1001점·음수 / trackId 65자·CRLF

# 경로순회 (DB 일시 변경 후 원복)
UPDATE ls_data_src SET src_file_path_nm='../../../etc/passwd', de_idntf_src_file_path_nm=NULL WHERE src_sn=46;
POST /v1/frames/46/sam2-segment                                                         → 400 "허용되지 않은 경로입니다."
UPDATE ls_data_src SET src_file_path_nm=NULL,
       de_idntf_src_file_path_nm='/app/storage/deidentified/frames/deid/18/frame-1.jpg' WHERE src_sn=46;  -- 원복 확인
```

---

# C클러스터 part6 — C-4 TC-KEYPOINT · C-5 TC-TRACK · C-6 TC-PRESET (3차 회차)

담당 라인범위: `docs/test-cases/C-marking-labeling.md` 269~356행 (62건: KEYPOINT 13 + TRACK 31 + PRESET 18)
스택: 3차 §3-1(`stack-bringup.md`) 기준 5개 컨테이너 healthy, 외부연동 전부 mock-server 실배선 확인됨. 백엔드 `cleanTest test` 5198/5203 pass, 0 failure(`test-baseline.md`).
검증 방법: ①실동작(curl, WORKER=2001/REVIEWER=1001 dev 토큰, rawSn=101 seed 재사용) ②정적 대조(file:line) ③테스트 커버 대조(JUnit XML). 빌드/테스트 실행 없음(baseline 재사용), 파일 수정은 카탈로그 1개 파일만.

---

## 1. C-4 TC-KEYPOINT (13건) — 전건 PASS

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-KEYPOINT-01 | PASS | [정적] LabelService.java:792-825(validateSkeletonPoints), :834-839(serializePoints, 카탈로그 838 대비 종료`}` 1줄 차이·무의미) 정확 일치. [테스트] `LabelServiceKeypointTest` 10/10 pass(`SKELETON_17개_삼중값_저장후_조회시_동일값_반환`) |
| TC-KEYPOINT-02 | PASS | [정적] LabelService.java:793-796 정확 일치 |
| TC-KEYPOINT-03 | PASS | [정적] LabelService.java:797-801 정확 일치 |
| TC-KEYPOINT-04 | PASS | [정적] LabelService.java:805-811 정확 일치(NPE 이전 400 fail-secure) |
| TC-KEYPOINT-05 | PASS | [정적] LabelService.java:816-819 정확 일치 |
| TC-KEYPOINT-06 | PASS | [정적] LabelService.java:820-823 — v=0,x=0,y=0 은 음수 검사 통과(0<0 은 false) |
| TC-KEYPOINT-07 | PASS(동작 정상) | [정적] LabelService.java:820-823 동일 분기, x=-1 이면 400. **단 회귀 테스트 부재**(아래 C-ISSUE-101 carry) |
| TC-KEYPOINT-08 | PASS | [정적] KeypointSerializer.java:46 toJson 일치. [테스트] `KeypointSerializerTest#toJsonTripletFormat` pass |
| TC-KEYPOINT-09 | PASS(동작 정상) | [정적] KeypointSerializer.java:80-81 정확 일치. **테스트 부재**(C-ISSUE-101) |
| TC-KEYPOINT-10 | PASS | [정적] KeypointSerializer.java:85-86 정확 일치 |
| TC-KEYPOINT-11 | PASS(동작 정상) | [정적] KeypointSerializer.java:91-92 정확 일치. **테스트 부재**(C-ISSUE-101) |
| TC-KEYPOINT-12 | PASS | [정적] KeypointSerializer.java:69-81(빈/`"[]"` early return 70-72 포함) |
| TC-KEYPOINT-13 | PASS(동작 정상) | [정적] LabelService.java:568-585(normalizePoints, 2-튜플 파서 실패 시 raw 배열 폴백) 정확 일치. **`normalizePoints` 전용 테스트 0건**(`grep -rn "normalizePoints" backend/src/test` 무결과, C-ISSUE-101) |

근거 `file:line` 드리프트: **0건** (13건 전부 실제 코드와 정확 일치 — LabelService.java/KeypointSerializer.java 구간은 카탈로그 품질 양호).

---

## 2. C-5 TC-TRACK (31건)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-TRACK-01~11 | PASS(11건) | [정적] `batch/interpolation/TrackInterpolator.java` 전 라인 정확 일치(48-83 interpolate, 100-139 interpolatePolyshape 등). [테스트] `batch.interpolation.TrackInterpolatorTest` 10/10 pass |
| TC-TRACK-12 | PASS | [정적] CoordinateTransformer.java:21-41 rotate 정확 일치 |
| TC-TRACK-13 | PASS(카탈로그 비고 정정) | [정적] :22-27 정확 일치. **비고 오류 수정**: "전용 단위테스트 여전히 부재(C-ISSUE-81)"는 사실이 아님 — `CoordinateTransformerTest`에 `rotateRejectsNullPoints`/`rotateRejectsNullCenter`/`rotateChecksPointsGuardBeforeCenterGuard` 3건 존재·pass(1차 회차 ISSUES.md에 이미 지적됐으나 카탈로그 본문 미반영 — 이번에 직접 정정, 아래 §4 참조) |
| TC-TRACK-14 | PASS | [정적] :44-65 scale/translate 정확 일치 |
| TC-TRACK-15~23 | PASS(9건) | [정적] MaskRleConverter.java 전 라인 정확 일치(:22,:30-33,:37-39,:44-47,:85-95,:102-104). [테스트] `MaskRleConverterTest` 12/12 pass |
| TC-TRACK-24 | **PASS[실동작]** | `POST /v1/frames/468/yolo-track` body srcSn=469(path=468) → `{"errorCode":"INVALID_INPUT","message":"path 의 srcSn 과 body 의 srcSn 이 다릅니다."}`. LabelController.java:198-208 정확 일치 |
| TC-TRACK-25 | **PASS[실동작]** | nextSrcSns 51개 → `"nextSrcSns: size must be between 0 and 50"`. YoloTrackRequest.java:23 정확 일치 |
| TC-TRACK-26 | **PASS[실동작]** | nextSrcSns=[] → `"nextSrcSns: must not be empty"`. 동일 라인 |
| TC-TRACK-27 | PASS | [정적] YoloTrackService.java:163-180 · DetectionBoxNormalizer.java:59-68 정확 일치. [테스트] `YoloTrackServiceTest#aiserver_응답_음수좌표는_거부되지_않고_0으로_clamp되어_반환된다` 외 2건 pass |
| TC-TRACK-28 | PASS | [정적] YoloTrackService.java:168-172 · DetectionBoxNormalizer.java:54-58 정확 일치. [테스트] `#aiserver_응답_좌표가_NaN이면_INVALID_INPUT` pass |
| TC-TRACK-29 | PASS | [정적] YoloTrackService.java:173-177 정확 일치. [테스트] `#이미지_전체밖_퇴화박스는_해당_검출만_스킵되고_시퀀스_전체는_유지된다` pass |
| TC-TRACK-30 | PASS | [정적] YoloTrackService.java:110-114(카탈로그 110-115, 실제 if~} 는 111-114·직전 주석 109-110 — 1줄 이내 오차, 무의미). [테스트] `#후속프레임이_다른_영상이면_INVALID_INPUT` pass. (실동작 재현은 accessGuard 가 먼저 403 을 낼 수 있어 시도하지 않음 — 단위테스트가 서비스 로직을 직접 검증하므로 충분) |
| TC-TRACK-31 | PASS(구조 단언 확인됨) | [정적] `grep -rn "common.util.TrackInterpolator\|import kr.co.cudo.authoring.common.util.TrackInterpolator" backend/src/main` → **0건**. `batch/interpolation/TrackInterpolator.java:23` 클래스 선언 확인. 카탈로그가 단언한 "여전히 잔존 + 미해소 dead code" 그대로 재확인됨(신규 결함 아님, 기존 UNCERTAINTIES 이월 항목) |

근거 `file:line` 드리프트: **TC-TRACK-13 비고 1건 정정**(위 §4). 그 외 30건은 카탈로그와 정확 일치.

---

## 3. C-6 TC-PRESET (18건) — 전건 PASS, 단 근거 라인 드리프트 다수(카탈로그 직접 정정)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-PRESET-01 | **PASS[실동작]** | `POST /v1/manage/presets` labelIds=[1](person,BBOX) → `"labelCodeOptions":[{"labelId":1,"code":null,...}]` — **`code:null` 확인, LBL_CD 미저장** 실증. create() :62-73 정확. 코드문자열 미저장 근거는 :185-194(toSpecs) — **카탈로그 정정**(구 163-172, 드리프트 22줄) |
| TC-PRESET-02 | **PASS[실동작]** | eventTypeCd="NOT_A_REAL_CATEGORY" → HTTP 400. 근거 :137-144(validateEventType) — **카탈로그 정정**(구 126-134) |
| TC-PRESET-03 | PASS | eventTypeCd="" 는 위 :01 라이브 호출에서 통과 확인(빈 문자열 허용). 근거 :137-144 — **카탈로그 정정**(구 126-134, TC-PRESET-02 와 동일 오류) |
| TC-PRESET-04 | **PASS[실동작]** | 동일 name 재생성 → HTTP 409. 근거 :65-67 정확(정정 불필요) |
| TC-PRESET-05 | **PASS[실동작]** | labelIds=[999999] → `"존재하지 않거나 비활성 라벨입니다: labelId=999999"` 메시지 완전 일치. 근거 :166-183(resolveLabels) — **카탈로그 정정**(구 144-160, 드리프트 22줄) |
| TC-PRESET-06 | PASS | [정적] :197-203(saveWithEventUniqueGuard) — **카탈로그 정정**(구 174-181) |
| TC-PRESET-07 | PASS | [정적] update() 존재 검증 :86-87 — **카탈로그 정정**(구 79-80) |
| TC-PRESET-08 | PASS | [정적] :91-93 — **카탈로그 정정**(구 81-83) |
| TC-PRESET-09 | PASS | [정적] :98-103(saveAndFlush try/catch) — **카탈로그 정정**(구 88-93) |
| TC-PRESET-10 | PASS | [정적] delete() :108-113 — **카탈로그 정정**(구 98-103, 그 구간은 실제로 TC-09 의 saveAndFlush 였음) |
| TC-PRESET-11 | PASS | [정적] resolveCloneName :205-218 — **카탈로그 정정**(구 183-195) |
| TC-PRESET-12 | PASS | [정적] clone() :115-128 — **카탈로그 정정**(구 106-118) |
| TC-PRESET-13 | PASS | [정적] resolveCloneName 루프+throw :211-217 — **카탈로그 정정**(구 185-195, TC-11 과 동일 잘못된 구간이 재사용돼 있었음) |
| TC-PRESET-14 | PASS | [정적] toCodeView !linked 분기 :261-267 — **카탈로그 정정**(구 237-244) |
| TC-PRESET-15 | PASS | [정적] :262-267 — **카탈로그 정정**(구 238-244) |
| TC-PRESET-16 | **PASS[실동작]** | 위 TC-01 응답에서 `"labelType":"BBOX","bboxEnabled":true,"polygonEnabled":false"` — 마스터(person=BBOX)에서 파생 확인, 프리셋 자체 토글 필드 없음. 근거 :268-273 — **카탈로그 정정**(구 245-251) |
| TC-PRESET-17 | PASS | [테스트] `LsLabelPresetCodeLabelIdUniqueIT` 1/1 pass(`동일_프리셋에_같은_labelId_코드_중복INSERT는_부분유니크인덱스로_거부된다`), 마이그레이션 `V119__preset_code_labelid_unique.sql:24` `UK_LS_LABEL_PRESET_CODE_LBLID` 확인 |
| TC-PRESET-18 | PASS | [정적] toViews() 배치 조회 :242-258(`findActiveByIds(labelIds)` 1회 호출 후 순회) — **카탈로그 정정**(구 220-235, 그 구간은 실제로 단건 toView 오버로드였음) |

**PresetServiceTest 25/25 pass** (test-results XML, 0 failures) — create/update/delete/clone/코드뷰 전 케이스 자동 테스트로도 뒷받침.

---

## 4. 카탈로그 정정 내역 (총 17건, 본 세션에서 Edit 직접 반영 — `docs/test-cases/C-marking-labeling.md` 269~356행 범위 내)

전부 `file:line` 근거 드리프트(카탈로그 자체 정합성 결함) — **동작 결함 아님**. `PresetService.java` 에 이력 중 `resolveLabels`(22줄)·`toSpecs`(위치 이동)·update() 확장 javadoc(약 7~10줄) 등이 순차 삽입되며 카탈로그가 갱신 없이 방치된 것으로 추정.

| # | 케이스 | 정정 |
|---|---|---|
| 1 | TC-PRESET-01 | 보조근거 `:163-172` → `:185-194` |
| 2 | TC-PRESET-02 | `126-134` → `137-144` |
| 3 | TC-PRESET-03 | `126-134` → `137-144` |
| 4 | TC-PRESET-05 | `144-160` → `166-183` |
| 5 | TC-PRESET-06 | `174-181` → `197-203` |
| 6 | TC-PRESET-07 | `79-80` → `86-87` |
| 7 | TC-PRESET-08 | `81-83` → `91-93` |
| 8 | TC-PRESET-09 | `88-93` → `98-103` |
| 9 | TC-PRESET-10 | `98-103` → `108-113` |
| 10 | TC-PRESET-11 | `183-195` → `205-218` |
| 11 | TC-PRESET-12 | `106-118` → `115-128` |
| 12 | TC-PRESET-13 | `185-195` → `211-217` |
| 13 | TC-PRESET-14 | `237-244` → `261-267` |
| 14 | TC-PRESET-15 | `238-244` → `262-267` |
| 15 | TC-PRESET-16 | `245-251` → `268-273` |
| 16 | TC-PRESET-18 | `220-235` → `242-258` |
| 17 | TC-TRACK-13 | 비고 "전용 단위테스트 여전히 부재(C-ISSUE-81)" 오류 문구 → 테스트 3건 존재·pass 로 정정 |

(TC-PRESET-04, 17 은 기존 근거가 정확해 정정 없음.)

---

## 5. 이슈 기록 (C-ISSUE-101부터, 담당 파트 신규 번호 부여분)

### [C-ISSUE-101] TC-KEYPOINT-07/09/11/13 — 자동테스트 공백 (1차 C-ISSUE-85 미해소 이월)
- **심각도**: LOW
- **기대 동작(기대효과)**: SKELETON 은 `LBL_TYPE_CD` 기반 type-route 로 격리된 분기라(`KeypointSerializer` javadoc:14-16) 회귀 테스트로 방어가 고정돼야 한다.
- **현재 동작(이슈 내용)**: 3차 실측 재확인 — 1차(2026-08-01) 지적 이후 코드/테스트 변경 없음.
  - `KeypointSerializerTest` 여전히 4건(`roundTrip`/`toJsonTripletFormat`/`emptyInputs`/`rejectsTwoTuple`) — 비배열(TC-09)·숫자아님(TC-11) 케이스 미추가.
  - `LabelServiceKeypointTest` 10건 중 음수 좌표(TC-07) 케이스 없음.
  - `grep -rn "normalizePoints" backend/src/test` → 0건(TC-13, R7 폴백 회귀 가드 없음).
- **재현/확인 경로**: `grep -n DisplayName backend/src/test/java/kr/co/cudo/authoring/common/util/KeypointSerializerTest.java`(4건) · `.../label/LabelServiceKeypointTest.java`(10건, 음수 케이스 0) · `grep -rn "normalizePoints" backend/src/test`(0건).
- **영향**: 회귀 감지력 저하. 특히 TC-13 무커버 상태에서 `normalizePoints` 폴백이 사라지면 SKELETON 라벨이 매 저장마다 "변경됨"으로 오판정되어 `LS_DATA_LBL_HSTRY`/`TASK_MODIFIED` 통지가 무한 증식할 수 있다(1차 분석과 동일, 운영 영향 조용하고 큼).
- **수정 방향(제안)**: ⚠ 구현하지 않음. `KeypointSerializerTest`에 비배열(`"{}"`)·비숫자(`"[[\"a\",2,1]]"`) 2건, `LabelServiceKeypointTest`에 음수좌표 400 1건, R7 무변경 폴백은 "SKELETON 동일값 재저장 시 이력 0건" 통합테스트 1건 추가.

### [C-ISSUE-102] TC-TRACK-12~23 — CoordinateTransformer·MaskRleConverter 프로덕션 미사용 (1차 C-ISSUE-101 미해소 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md CVAT 포팅 전략에 명시된 "MASK↔RLE↔Polygon 변환"·"좌표 변환/회전 유틸"은 실제 라벨링 파이프라인에서 소비돼야 포팅 목적이 달성된다.
- **현재 동작(이슈 내용)**: 3차 재확인 — `grep -rn "MaskRleConverter" backend/src/main` / `grep -rn "CoordinateTransformer" backend/src/main` 모두 **자기 자신 파일 1건뿐**(호출부 0건). 두 클래스 모두 대응 Test 클래스에서만 참조(`MaskRleConverterTest` 12건, `CoordinateTransformerTest` 10건 — 클래스 자체 품질은 양호하나 소비처가 없음). SAM2 분할 결과는 여전히 `Sam2SegmentService`가 polygon 을 직접 응답하고 RLE 경로를 타지 않으며, 라벨 엔티티에 회전각 필드가 없어 서버측 회전 재계산 지점도 없다.
  - 부수 결함(미해소): `MaskRleConverter.maskToRle`에 `height>0,width==0`(예: `new boolean[3][0]`) 입력 시 `mask.length==0` 가드를 통과한 뒤 `flat=new boolean[0]`에서 `flat[0]` 접근으로 **미제어 ArrayIndexOutOfBoundsException**(다른 가드는 전부 IAE로 통일된 것과 대비). 호출부가 없어 악용 경로는 없음(dead code 한정 결함).
- **재현/확인 경로**: `cd backend/src/main && grep -rn "MaskRleConverter\|CoordinateTransformer" .` → 자기 파일만.
- **영향**: 기능 결함은 아님(호출부 없어 런타임 영향 0). 문서-코드 드리프트(유지보수 혼동) + 향후 실연동 시 width==0 극단값 결함이 활성화될 잠재 리스크.
- **수정 방향(제안)**: ⚠ 구현하지 않음. ① RLE 저장·회전이 실제로 불필요하면 두 클래스+테스트를 정리하거나 CLAUDE.md 포팅 전략 표에 "구현만 되고 미연동" 명시. ② 연동 계획이 있다면 `maskToRle`의 `width==0` 가드를 다른 가드와 동일하게 `IllegalArgumentException`으로 통일.

### (참고, 신규 번호 미부여) TC-TRACK-31 — common/util/TrackInterpolator dead code
- 카탈로그 자체가 이미 "C-ISSUE-82 미해소 — dead code"로 정확히 서술 중이며 UNCERTAINTIES.md 이월표 항목과 일치. 3차 재확인 결과 그대로(`grep` 0건 재확인). 별도 신규 이슈 번호 없이 상태만 재확인.

---

## 6. 이전 회차(1차, 2026-08-01) 이슈 대조

| 이전 이슈 | 본 파트 관련 여부 | 3차 상태 |
|---|---|---|
| C-ISSUE-85(TC-KEYPOINT-07/09/11/13 테스트 공백) | 직접 관련 | **미해소** — C-ISSUE-101 로 이월 재기록(코드/테스트 변경 없음 재확인) |
| C-ISSUE-101(TC-TRACK-12~23 dead code) | 직접 관련 | **미해소** — C-ISSUE-102 로 이월 재기록(변경 없음 재확인, width==0 결함도 동일) |
| TC-TRACK-13 비고 드리프트(1차 "카탈로그 근거 드리프트" 섹션에 지적, `C-ISSUE-81` 클레임 재검증 권고) | 직접 관련 | **1차에서 지적만 되고 카탈로그 본문 미반영 상태였음 — 3차에서 직접 정정 완료**(§4) |
| C-ISSUE-82(1차, SAM2 track 외부응답 검증) · C-ISSUE-83(Sam2SegmentService @Transactional) · C-ISSUE-84~86 | C-3(SAM2) 소관, 본 파트(C-4~6) 범위 밖 | 대조 안 함 |

2차(2026-08-02) 회차는 F/G/H 클러스터만 검증해 C 클러스터 비교 대상 없음.

---

## 7. self-fill / 확정정책 대조

- **self-fill**: 본 구간 0건. TC-TRACK-24~30 실동작 응답은 전부 요청 검증(Bean Validation)·서비스 로직 결과이며 하드코딩 값 없음. TC-PRESET 라이브 응답(`code:null`, `bboxEnabled` 등)도 DB/마스터 조회 결과 그대로.
- **★4(라벨명=마스터 등록명)**: TC-PRESET-16 라이브 확인에서 `labelName`이 마스터 그대로("person") — 별도 사전 치환 없음, 확정 정책과 일치.
- **★3(좌표 검증 2축)**: TC-TRACK-27/28 은 AI 검출 응답 축(clamp+퇴역 스킵)이라 확정 정책과 일치, 재보고 안 함.
- 그 외 확정 정책(★1/★2/★5) 해당 케이스 없음.

---

## 8. 남긴 테스트 데이터 (다음 회차 참고)

- `LS_LABEL_PRESET.PRESET_ID=57`("QA3RD-PRESET-TEST", labelId=1 person, eventTypeCd=null) — 실동작 검증용 신규 생성. 삭제하지 않음(다른 검증에 영향 없는 독립 데이터).

---

