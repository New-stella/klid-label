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
