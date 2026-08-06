# C 클러스터 part1 (C-1 마킹) 2차 검증 결과

- 검증 시각: 2026-07-31 03:22 ~ 03:35 KST (호스트 UTC 18:22~18:35). backend `localhost:18081`(HEAD `ca3c712b` 재빌드본) · mock-server `:9400` · postgres `public` 스키마.
- 대상: `docs/test-cases/C-marking-labeling.md` `## C-1. TC-MARK` **46건**(취소선 폐기 행 0건 — 집계 제외 대상 없음).
- **실동작 우선 원칙 적용** — 46건 중 **35건을 실제 API 호출/DB 상태 전이/mock 인바운드 로그로 판정**했고, 11건(브리지 내부 분기·엔티티 팩토리 등 API 경로로 도달 불가한 단위 계약)만 정적+테스트 대조로 판정했다.
- **본 검증이 만든 픽스처**: 관제 클립 `DEV-CLIP-9501~9511`(MNG_* 입력 픽스처) → `POST /v1/dev/batch/scan` 으로 정규 적재된 **rawSn 145~155**. 상태 전이·마킹은 전부 API 실행이며 `LS_MARKING`/`LS_DATA_RAW.DATA_STTS_CD`/`LS_RAW_DATA_STATUS` 를 직접 UPDATE 로 위조한 구간은 아래 3건뿐이다(전부 "해석 불가 입력"을 만들기 위한 것이며, 각 케이스 비고에 명시).
  1. rawSn 149·155 — `VDO_LEN_SEC` null/0 + `video.duration_ms` 메타 삭제 + 파일경로 부재값(TC-MARK-08/09 재현용, 이후 경로 복원)
  2. rawSn 148 — `video.fps` 메타 삭제(TC-MARK-12 폴백 재현용)
  3. rawSn 151 — `LS_MARKING(21).STTS_CD → VLM_FAILED`(TC-MARK-38 "종결 마킹만 존재" 재현용. VLM 실패 콜백을 인위 유발할 수단이 없어 불가피)
- ⚠ **동시 실행 주의**: 검증 착수 시점(03:22)에 이미 다른 에이전트가 rawSn 134~144(`DEV-CLIP-92xx/93xx`)로 마킹을 구동 중이었다(마킹 sn 7~13, 03:16~03:20 생성). 본 문서의 근거는 **rawSn 145~155 · 마킹 sn 14~24** 로 한정하며, 134 는 "활성 마킹 보유 영상" 픽스처로만(비파괴) 재사용했다.
- ⚠ rawSn **133(신고 OPEN)** 은 건드리지 않았다. resolve 호출 0건.
- backend 컨테이너 재기동·재빌드·빌드/테스트 실행 **0회**. 소스/설정 파일 수정 **0건**(본 결과 파일 1개만 신규 작성).

---

## 집계

| 판정 | 건수 | 비율 |
|---|---:|---:|
| PASS | **45** | 97.8% |
| PARTIAL | **1** | 2.2% |
| FAIL | 0 | — |
| BLOCKED | 0 | — |
| N/A | 0 | — |
| 확인필요 | 0 | — |
| **합계** | **46** | |

- 근거 유형: **[실동작] 35건 / [정적]+테스트대조 11건**.
- **케이스 표에 없는 결함(연장) 2건**을 별도 보고한다 — C-ISSUE-01(원본 경로 노출, MEDIUM), C-ISSUE-03(배치 미트리거 마킹의 영구 고착, MEDIUM). 두 건 모두 해당 케이스의 명시 단언은 충족하므로 케이스 판정은 PASS 로 두고 "연장 이슈"로 분리했다(1차 C-ISSUE-01 의 `TC-MARK-02(연장)` 표기 관례와 동일).
- 근거 `file:line` 드리프트 **4건**(전부 ±1~4행, 판정에 영향 없음).

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 실측 | 판정 |
|---|---|---|---|
| **B-ISSUE-22** | 동일 rawSn 에 마킹 N건 생성(동시 3요청 → 3행 201), 배치는 1건만 위탁 → 고아 PENDING | rawSn 152 동시 **5요청 → 1×201 / 4×409**, `ls_marking` **1행**. 4건 모두 `[Marking] concurrent duplicate rejected rawSn=152` (= `DataIntegrityViolationException` → 409 변환 경로, 부분 유니크 인덱스 `UK_LS_MARKING_RAW_ACTVTN` 발화). 순차 중복도 409(rawSn 134·151) | **✅ 해소** |
| **B-ISSUE-23** (미해소 이월) | AUTO `intervalFrames` 상한 미검증 → 과대값이 marks 1건으로 퇴화 | rawSn 154 에 `intervalFrames=2147483647`(int 최대) → **201**, `marks=[{0,"00:00"}]` 1건, 배치가 실제로 **프레임 1장만** 추출(`ls_data_src(154)=1`)하고 `LS_DATA_RAW=COMPLETED` 로 정상 종료. 상한 검증은 여전히 없다(`MarkingService.java:196-198` 하한만) | **❌ 미해소(이월 유지)** — C-ISSUE-04 |
| **B-ISSUE-27** | 29.97fps 직접 계산 회귀 테스트 부재 | 실동작으로는 **확인됨**(아래 TC-MARK-10/11). 그러나 `backend/src/test` 전수 grep 결과 마킹 계산 경로에 `29.97` 을 쓰는 테스트는 **여전히 0건**(있는 것은 `VideoFpsResolverTest`·`VideoMetaServiceTest`·`BrampVideoProbeTest` 의 파싱/적재 테스트뿐, 마킹 테스트는 fps 25/30/60 만 커버) | **🔶 부분 해소**(동작 확인 / 회귀 고정 부재) — C-ISSUE-02 |
| **B-ISSUE-28 · B-ISSUE-64** | 마킹 단계 rawSn 비식별 신고 미구현 | `DeidentReportController.java:122` `POST /v1/videos/{rawSn}/deident-report` 구현 확인(정적). 상세 판정은 C-2 TC-LABEL-127~130 소관이라 본 파일에서는 존재만 확인(신고 접수 호출은 타 에이전트 데이터 오염 방지를 위해 미수행) | **✅ 해소(존재 확인)** |
| **C-ISSUE-01** | MANUAL marks 콘텐츠 무검증(중복·상한·음수·형식 전부 201 통과) | 1차 재현 페이로드 그대로 재현 시도 → **전부 400**. 음수 `marks[0].frameIndex: frameIndex 는 0 이상이어야 합니다` / 형식 `timestamp 는 mm:ss 또는 mm:ss:ff 형식이어야 합니다` / 중복 `중복된 마킹 시점입니다: frameIndex=0` / 상한 `영상 길이를 벗어난 마킹 시점입니다: frameIndex=999999999 (허용 상한 3417 프레임 미만)` | **✅ 해소** |
| PIPE-ISSUE-02 (2차 파이프라인 구동) | 마킹 레코드·응답이 원본(비식별 전) 경로를 담는다 | **재확인됨**(아래 전용 절) | **미해소** — C-ISSUE-01 |

---

## ★원본 경로 노출 실측

파이프라인 구동에서 관측된 PIPE-ISSUE-02("마킹 레코드·응답이 원본 NAS 경로 노출")를 **rawSn 145~155 에서 독립 재현**했다. 대조군: 같은 영상의 비식별 산출 경로는 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM = /app/storage/raw/seed/{rawSn}/deid/sample-cctv-1080p-mask.mp4`(원본과 **디렉터리·파일명 모두 상이**).

| # | 경로(노출 지점) | 응답·로그·DB | 노출 여부 | 실측 근거 |
|---|---|---|:--:|---|
| 1 | `POST /v1/videos/{rawSn}/markings` **201 응답** `data.videoPath` | 응답(HTTP 본문) | **노출 ✔** | rawSn 146 을 **배정 WORKER 토큰**으로 호출 → `"videoPath":"/app/storage/raw/seed/sample-cctv-1080p.mp4"` (= 원본 절대경로). MANUAL(147)·AUTO(145/153/154)·검수소유(151) 전 경로 동일 |
| 2 | `LS_MARKING.VIDEO_FILE_PATH_NM` | DB | **노출 ✔** | 본 검증이 만든 마킹 10행(sn 14~24) 전부 `/app/storage/raw/seed/sample-cctv-1080p.mp4`. 코드: `MarkingService.java:217-218` 이 `raw.getRawFilePathNm()` 을 그대로 `createAuto/createManual` 에 전달 |
| 3 | backend 애플리케이션 로그 | 로그 | 미노출 ✗ | `[Marking] created rawSn=146, mode=AUTO, markingSn=14` — 경로 없음. `[MarkingBatchBridge]` 도 rawSn·상태코드만. ffprobe 경로는 `BrampVideoProbe` 가 **해시 마스킹**(`path=6c5be71f`, CWE-209 준수) |
| 4 | 배치 미트리거 사유 문구(`batchSkipReason`) | 응답 | 미노출 ✗ | `MarkingBatchTriggerReport` 의 **고정 상수 4종**만 사용 — DB/외부 유래 문자열 미포함 |
| 5 | VLM 외부 위탁 `media.path` | mock 인바운드 | 미노출 ✗ | mock 로그 `[MOCK][VLM] media duration probed file=sample-cctv-1080p-**mask**.mp4` (호출자 IP `172.18.0.5` = backend 컨테이너). VLM 은 `LS_MARKING.VIDEO_FILE_PATH_NM` 을 쓰지 않고 비식별본을 별도 해석 |
| 6 | 영상 스트리밍 `GET /v1/videos/146/stream` | 응답 | 미노출 ✗ | 206, `Content-Range: bytes 0-99/**34654319**` = 비식별본 파일 크기(원본은 28,220,079B). `Cache-Control: no-store` 부여 확인 |

**결론**: 화면에 **원본 픽셀이 노출되는 경로는 없다**(스트리밍·VLM 위탁 모두 비식별본). 그러나 **원본 NAS 절대경로 문자열 자체는 201 응답과 DB 컬럼 두 곳에 그대로 실린다.** 소비자 추적 결과 `getVideoFilePathNm()` 의 유일한 참조는 `MarkingResponse.java:54`(= 클라이언트 응답) 하나뿐이라, 이 컬럼은 **기능적 소비자 없이 원본 경로만 외부로 내보내는 상태**다. → C-ISSUE-01 (MEDIUM, CWE-209 계열).

---

## C-1 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-MARK-01 | AUTO 마킹 정상 생성 | PASS | [실동작] rawSn 146(배정 WORKER, `intervalFrames=1200`) → **201**, `marks` 3건(0/1200/2400), `status=PENDING`, `batchTriggered=true`. AFTER_COMMIT 브리지 로그 `[MarkingBatchBridge] enqueued rawSn=146` → 배치 완주(`LS_DATA_RAW=COMPLETED`, 작업상태 `ASSIGNED` 복귀), VLM 메타 15건, 프레임 3건 | 이벤트 발행은 브리지 실행 자체가 증거(이벤트 없으면 브리지 미실행). **연장 이슈 C-ISSUE-01**(응답 `videoPath` 원본 경로) |
| TC-MARK-02 | MANUAL 마킹 정상 생성 | PASS | [실동작] rawSn 147 `marks=[{0,"00:00"},{100,"00:03:10"},{3416}]` → **201**, marks 3건 직렬화 그대로 반환, `LS_MARKING.FPS=29.97002997002997` pin | `mm:ss:ff` 형식·`timestamp` 미지정(null) 항목 모두 수용. **연장 이슈 C-ISSUE-01** |
| TC-MARK-03 | mode 누락(blank) | PASS | [실동작] `mode:""` → 400 `mode: mode 는 필수입니다.`, `mode` 필드 자체 누락도 동일 400 | `MarkingRequest.java:20` `@NotBlank` |
| TC-MARK-04 | mode 비AUTO/MANUAL | PASS | [실동작] `mode:"XYZ"` → 400 `mode 는 AUTO 또는 MANUAL 이어야 합니다.` | 프리컨디션 통과 후 서비스 분기에서 거부(`MarkingService.java:210-212`) |
| TC-MARK-05 | AUTO intervalFrames null | PASS | [실동작] 400 `자동 모드에서 intervalFrames 는 1 이상이어야 합니다.` | |
| TC-MARK-06 | AUTO intervalFrames 0/음수(경계) | PASS | [실동작] `0` → 400, `-1` → 400 (동일 메시지) | |
| TC-MARK-07 | MANUAL marks 빈/null | PASS | [실동작] `marks:[]` → 400 `수동 모드에서 marks 는 필수입니다.`, `marks` 누락도 동일 | |
| TC-MARK-08 | AUTO durationSec null(backstop) | PASS | [실동작] rawSn 149 를 3단 폴백 전부 실패 상태로 만든 뒤(`VDO_LEN_SEC=null` + `video.duration_ms` 삭제 + 파일 부재 경로) AUTO → 400 `영상 길이를 확인할 수 없어 자동 마킹을 생성할 수 없습니다.` ffprobe 는 실제 시도됨(`BrampVideoProbe [Video][Probe] ffprobe empty output path=6c5be71f`) | 픽스처 조작(위 3-1). 조작 후 경로 즉시 복원 |
| TC-MARK-09 | AUTO durationSec 0/음수(경계) | PASS | [실동작] rawSn 155 `VDO_LEN_SEC=0` + 메타 삭제 + 파일 부재 → 400(동일 메시지). 리졸버가 `>0` 이 아니면 다음 폴백으로 넘기고 최종 null → backstop 발화 | 음수 주입은 API 표면이 없어 단위 테스트 `MarkingServiceTest:458 "자동마킹_주입_durationSec_0이하면_INVALID_INPUT"` 로 대조 |
| TC-MARK-10 | generateAutoMarks off-by-one | PASS | [실동작] rawSn 153, dur=113s·fps=29.97002997 → `totalFrames=round(3386.6)=3387`. `intervalFrames=1129` → marks `0/1129/2258` 로 **3387 미포함**(끝경계 배타 확인) | 이 입력은 fps 30 가정이면 `totalFrames=3390` 이라 3387 이 **포함**됐어야 한다 → off-by-one 과 실 fps 사용을 동시에 판별 |
| TC-MARK-11 | 분수 fps 반올림(29.97) | **PARTIAL** | [실동작] 위 TC-MARK-10 과 동일 요청이 29.97 기반 `totalFrames=3387` 로 산출됨(30fps 가정과 결과가 갈리는 입력에서 29.97 쪽 결과) + `LS_MARKING.FPS=29.97002997002997` pin. **그러나** [정적] `backend/src/test` 전수 grep 결과 마킹 계산 경로의 29.97 회귀 테스트는 여전히 0건 | 1차 B-ISSUE-27 이월. → **C-ISSUE-02** |
| TC-MARK-12 | fps 미상 → 30 폴백 | PASS | [실동작] rawSn 148 의 `video.fps` 메타 삭제 후 MANUAL 마킹 → `LS_MARKING(17).FPS=30` 저장, 201 정상 | 픽스처 조작(위 3-2) |
| TC-MARK-13 | fps pin 저장(TOCTOU) | PASS | [실동작] 본 검증이 만든 마킹 10행 전부 `FPS` 컬럼 비어있지 않음(AUTO/MANUAL 무관). 실 fps 영상=29.97002997002997, 메타 삭제 영상=30 | `LsMarking.java:102·162·213` |
| TC-MARK-14 | 인가: 미인증 actor=null | PASS | [실동작] 토큰 미첨부 → **401** `인증이 필요합니다.` / 위조 토큰(`abc.def.ghi`) → **401** | 존재확인 이전 거부(rawSn 145 는 실존이나 401) |
| TC-MARK-15 | 인가: 미배정 WORKER(IDOR) | PASS | [실동작] ① 미존재 `rawSn=999999` + WORKER → **403**(404 아님 = 존재 은닉 유지) ② 실존하나 타인(2002) 배정 `rawSn=20031` + worker1(2001) → **403** `본인에게 배정된 영상의 마킹만 접근할 수 있습니다.` | 수평 권한 상승(CWE-639) 차단 확인 |
| TC-MARK-16 | 인가: REVIEWER 전체 허용 | PASS | [실동작] REVIEWER 가 **본인 배정 이력이 전혀 없는** rawSn 145/147/148/149/153/154 에 마킹 생성 성공(201) | |
| TC-MARK-17 | 프리컨디션: 영상 미존재 | PASS | [실동작] REVIEWER + `rawSn=999999` → **404** `영상을 찾을 수 없습니다.` (음수 `-1` 도 404) | |
| TC-MARK-18 | 프리컨디션: 비식별 미완료 | PASS | [실동작] `DE_IDENT_YN='N'`(rawSn 145 적재 직후) → **412**, `='F'`(비식별 API 실패 127) → **412**, `='F'`(신고 OPEN 133) → **412**. 메시지 전부 `비식별이 완료된 영상에서만 마킹할 수 있습니다.` | 신고/실패를 구분하지 않고 동일 412 — 상태 오라클 방지 측면에서 정상 |
| TC-MARK-19 | 프리컨디션: MARKING_READY 아님(재마킹 차단) | PASS | [실동작] `COMPLETED`(126, 20005, 20031) → **412** `이미 처리된 영상은 재마킹할 수 없습니다.` 파생영상 129(증강)·130(해상도)도 동일 412 | "반려 후 재마킹 동선 부재"는 확정 정책(결함 아님). 파생영상은 별도 가드 없이 배치단계 가드로 차단됨 |
| TC-MARK-20 | 프리컨디션: evntTypeCd 미지정 | PASS | [실동작] `MNG_CLIP_EVNT_LST` 행이 없는 클립(`DEV-CLIP-9506`)으로 적재된 rawSn 150(`EVNT_TYPE_CD` 공백, MARKING_READY, deid Y) → **400** `이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다.` | 평가순서(deid→stage→evnt) 실측 확인 |
| TC-MARK-21 | 프로브-이전 사전확인(리소스) | PASS | [실동작] duration 해석 불가 상태(=통과 시 ffprobe 필연 발화)인 rawSn 149 에 **미배정 WORKER** AUTO → 403, 직후 20초 backend 로그에 `BrampVideoProbe`/`VideoDuration` **0건**. 대조군: 같은 영상·같은 상태로 **REVIEWER** 호출 시 `BrampVideoProbe ... ffprobe empty output` 발화 | positive control 로 "프로브가 발화할 조건이었음"까지 확증 |
| TC-MARK-22 | 프리체크 REQUIRES_NEW 격리 | PASS | [정적] `MarkingPrecheckReader.java:44` `@Transactional(readOnly=true, propagation=REQUIRES_NEW)` + **별도 빈**(자기호출 프록시 우회 회피). 호출부 `MarkingService.java:112` 가 비트랜잭션 오케스트레이션 | 커넥션 반납 시점의 풀 계측은 미수행(런타임 프로파일링 도구 없음) |
| TC-MARK-23 | persist 이중화 방어 | PASS | [정적] `MarkingService.java:170·174·178` 이 precheck 와 **동일 헬퍼**(`MarkingGuards.requireAssignedOrReviewer`/`requirePreconditions`/`requireNoActiveMarking`)를 동일 순서로 재호출. 사전확인 경로(`MarkingPrecheckReader.java:46-49`)와 문자 그대로 일치 | 사전확인↔persist 사이 상태 변경 레이스는 인위 유발 수단이 없어 미실행 |
| TC-MARK-24 | 이벤트명 자동소싱(API-047) | PASS | [실동작] 요청 본문에 이벤트명 필드가 **없음에도** 응답 `eventName="INTRUSION"` = `LS_DATA_RAW(146).EVNT_TYPE_CD`. `LS_MARKING.EVNT_NM` 도 전 행 `INTRUSION` | `MarkingRequest` 에 eventName 필드 자체가 부재 |
| TC-MARK-25 | LsMarking.createAuto 검증 | PASS | [정적+테스트] `LsMarking.java:142-153` 4가드. `LsMarkingEntityTest` `createAuto_intervalFrames_0이하_예외`·`createAuto_rawSn_null_예외` | 서비스가 선행 검증하므로 API 로 도달 불가 |
| TC-MARK-26 | LsMarking.createManual 검증 | PASS | [정적+테스트] `LsMarking.java:196-204`. `LsMarkingEntityTest` `createManual_eventName_null_예외`·`createManual_videoPath_blank_예외` | 동상 |
| TC-MARK-27 | markVlmRequested PENDING만 전이 | PASS | [정적+테스트] `LsMarking.java:231-238`. `LsMarkingEntityTest` `markVlmRequested_상태전이`·`PENDING에서만_VLM_REQUESTED로_전이_그외_no_op` + [실동작] 마킹 sn 14/15/16/19/20/24 가 `PENDING→…→VLM_COMPLETED` 로 정상 전이 | |
| TC-MARK-28 | markVlmRequested 이미 완료 no-op | PASS | [정적+테스트] 위 동일 테스트가 4상태 no-op 를 단언 | 역행 유발 API 없음 |
| TC-MARK-29 | 배치브릿지: 배치단계 PROCESSING/COMPLETED 스킵 | PASS | [정적+테스트] `MarkingBatchBridge.java:81-82·109-114`. `MarkingBatchBridgeTest` `배치_COMPLETED_영상에_마킹이벤트_재발생시…`·`배치_PROCESSING_중_마킹이벤트_재발생시…` | **API 경로로는 도달 불가**(마킹 프리컨디션 412 가 선행 차단) → 방어적 이중화 계층. 실동작 미도달을 결함으로 보지 않음 |
| TC-MARK-30 | 배치브릿지: 비식별 미완료 스킵 | PASS | [정적+테스트] `MarkingBatchBridge.java:118-123`. `MarkingBatchBridgeTest` `비식별_미완료영상은…(deIdntfYn=N)`·`비식별_실패영상은…(deIdntfYn=F)` | 동상(TC-MARK-18 이 선행 차단) |
| TC-MARK-31 | 배치브릿지: row 부재 시 생성 | PASS | [실동작] `LS_RAW_DATA_STATUS` 행이 **없던** rawSn 145·147·148·149·153·154 를 REVIEWER 가 직접 마킹 → 전부 배치 완주 후 행이 생성됨(현재 `ASSIGNED`). tx1 클레임 실패 → tx2 `tryCreateBatchQueuedRow` 경로 | `MarkingBatchBridge.java:136-146` |
| TC-MARK-32 | 배치브릿지: 동시 2노드 유니크 경합 | PASS | [정적+테스트] `MarkingBatchBridge.java:140-144` 가 `DataIntegrityViolationException` 을 AFTER_COMMIT(활성 tx 없음) 컨텍스트에서 포획. `MarkingBatchBridgeTest` `동시_row생성경합…정확히1건만_트리거(CWE-362)` | 실동작으로는 마킹 유니크(V142)가 **더 앞에서** 1건으로 수렴시켜 브리지 경합에 도달하지 않음 |
| TC-MARK-33 | 배치브릿지: 영상 미존재 스킵 | PASS | [정적+테스트] `MarkingBatchBridge.java:98-103`. `MarkingBatchBridgeTest` `영상_행_미존재시_스킵` | |
| TC-MARK-34 | 로그 인젝션 방어 | PASS | [정적] `MarkingBatchBridge.java:166-168` `sanitize()` 가 `\n`·`\r` 제거, 적용 지점 `:111`(dataSttsCd)·`:120`(deIdntfYn) 확인. 브리지가 로그에 넣는 DB 유래 문자열은 이 둘뿐 | 전용 테스트는 **부재**(grep 0건). 런타임 재현은 `DATA_STTS_CD` 에 개행을 넣으면 프리컨디션이 선행 차단해 불가 |
| TC-MARK-35 | 컨트롤러 권한 매핑 | PASS | [실동작] PORTAL_USER 토큰 → **403** `권한이 없습니다.` (`MarkingController.java:51` `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")`). 부가로 GET/PUT/DELETE 는 **405** 로 정규화 | |
| TC-MARK-36 | 활성 마킹 중복 409(순차) | PASS | [실동작] ① rawSn 134(타 에이전트 픽스처, `PENDING` 마킹 보유) → **409** ② rawSn 151(본 검증 픽스처, `PENDING` 마킹 보유) → **409** `이미 진행 중인 마킹이 있습니다.` 둘 다 쓰기·프로브 이전 1선 거부 | `MarkingGuards.java:109-114` |
| TC-MARK-37 | 활성 마킹 동시 요청 → DB 유니크 최종 방어 | PASS | [실동작] rawSn 152 에 **동시 5요청** → `req2=201`, 나머지 4건 **409**. `LS_MARKING(152)` 1행(sn 24), IDENTITY 채번 공백(sn 23 결번) = INSERT 시도 후 tx abort 흔적. 로그 `concurrent duplicate rejected rawSn=152` ×4 | 같은 tx 재시도 없음(PG abort 규약) 확인 |
| TC-MARK-38 | "활성" 정의 = PENDING/VLM_REQUESTED | PASS | [실동작] rawSn 151 의 유일 마킹을 종결(`VLM_FAILED`)로 만든 뒤 재마킹 → **201**(sn 22 신규). [정적] `LsMarking.java:65 ACTIVE_STATUSES=List.of(PENDING,VLM_REQUESTED)` ↔ `V142__add_ls_marking_active_unique.sql` 인덱스 술어 `STTS_CD IN ('PENDING','VLM_REQUESTED')` **문자 일치** | 픽스처 조작(위 3-3) |
| TC-MARK-39 | 프리체크 단계 활성중복 판정 | PASS | [실동작] rawSn 151(활성 마킹 보유)의 duration 을 해석 불가로 만든 뒤 AUTO 요청 → **409**, 직후 로그에 `BrampVideoProbe`/`VideoDuration` **0건**(= ffprobe 미트리거). `MarkingPrecheckReader.java:49` | 직후 원상복구(`VDO_LEN_SEC=113`) |
| TC-MARK-40 | MANUAL frameIndex 음수 400 | PASS | [실동작] `frameIndex:-5` → 400 `marks[0].frameIndex: frameIndex 는 0 이상이어야 합니다.` / `frameIndex` 누락 → 400 `marks[0].frameIndex: frameIndex 는 필수입니다.` — **중첩 검증 전파(@Valid) 발화 확인** | 근거 드리프트 2건(아래) |
| TC-MARK-41 | MANUAL 중복 frameIndex 400 | PASS | [실동작] `[{0},{0}]` → 400 `중복된 마킹 시점입니다: frameIndex=0` | `MarkingService.java:312-315` |
| TC-MARK-42 | MANUAL frameIndex 상한 초과 400 | PASS | [실동작] `999999999` → 400 `(허용 상한 3417 프레임 미만)`. **경계 정확 판정**: `3417` → 400 / `3416` → 201(rawSn 147 마킹에 실제 포함). `round(113×29.97002997)+ceil(29.97)=3387+30=3417` 배타 상한 = 1초 마진 규약과 일치 | `MarkingService.java:322-329·350-352` |
| TC-MARK-43 | MANUAL 길이 미상 → 상한만 skip | PASS | [실동작] rawSn 149(길이 해석 불가) `frameIndex=999999` → **201** + WARN `[Marking] duration unknown — manual mark upper-bound check skipped rawSn=149 marks=1`. 같은 상태에서 하한(-5)·중복·형식 검증은 그대로 400 | 전부 스킵 금지 규약 충족 |
| TC-MARK-44 | timestamp 형식 위반 400 | PASS | [실동작] `"99:99"` → 400, `"-1:00"` → 400(`mm:ss 또는 mm:ss:ff 형식`), `timestamp` 미지정(null) → 통과(프리컨디션까지 진행), `"00:03:10"`(mm:ss:ff) → 201 수용 | 근거 드리프트 1건(아래). 전용 테스트 부재 |
| TC-MARK-45 | marks 개수 상한 20000(CWE-770) | PASS | [실동작] 20,001건 요청 → 400 `marks: 한 번에 처리 가능한 마킹 수 초과 (최대 20000)` | 근거 드리프트 1건(아래). 전용 테스트 부재 |
| TC-MARK-46 | 배치 미트리거 사유 응답 반영 | PASS | [실동작] rawSn 151 을 배정→검수제출(작업상태 `PENDING`, 배치단계는 `MARKING_READY` 유지) 후 WORKER 마킹 → **201** + `batchTriggered:false` + `batchSkipReason:"검수 진행/완료(또는 반려) 상태이거나 이미 배치가 큐잉되어 배치를 시작하지 않았습니다."` 로그 `batch already claimed/in-progress or review-owned rawSn=151` + `[Marking] batch not triggered rawSn=151 markingSn=21`. DB 로 배치 미기동 확인(`LS_DATA_RAW=MARKING_READY` 유지) | 스레드로컬 `begin()` 초기화도 간접 확인(선행 403/412 요청들이 후속 201 의 필드를 오염시키지 않음). **연장 이슈 C-ISSUE-03** |

---

## 근거 드리프트

| TC | 표기 근거 | 실제 위치 | 비고 |
|---|---|---|---|
| TC-MARK-40 | `MarkItem.java:26-27` | `MarkItem.java:23-25`(`@NotNull`/`@Min`/`frameIndex`) | 26 은 공백행, 27 은 `@Pattern` |
| TC-MARK-40 | `MarkingRequest.java:31` (`@Valid`) | `MarkingRequest.java:30` | 31 은 `@Size` |
| TC-MARK-44 | `MarkItem.java:29-30` | `MarkItem.java:27-28`(`@Pattern`) | 29 는 `String timestamp` |
| TC-MARK-45 | `MarkingRequest.java:32` (`@Size`) | `MarkingRequest.java:31` | 32 는 `List<MarkItem> marks` |

그 외 42건의 `file:line` 은 실측 위치와 일치(±1행 이내 주석 경계 포함). 특히 `MarkingService.java:105-143`·`167-241`·`196-198`·`204-206`·`210-212`·`226-233`·`269-273`·`276-281`·`312-315`·`317-321`·`322-329`·`350-352`, `MarkingGuards.java:53-55`·`56-64`·`80-82`·`83-86`·`87-90`·`91-95`·`109-114`, `MarkingBatchBridge.java:66-71`·`81-82`·`98-104`·`136-146`·`166`, `LsMarking.java:65`·`102`·`140-153`·`194-205`·`231-240`, `MarkingPrecheckReader.java:44`·`49`, `MarkingController.java:51`, `MarkingRequest.java:20`, `V142__add_ls_marking_active_unique.sql` 전부 정확.

---

## 이슈 상세

### [C-ISSUE-01] TC-MARK-01/02(연장) — 마킹 201 응답과 `LS_MARKING` 이 **원본(비식별 전) NAS 절대경로**를 담는다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 마킹은 비식별 영상을 대상으로 하는 단계다(루트 `CLAUDE.md` — "마킹 화면: **비식별 영상** 스트리밍", "마킹 = 비식별화 완료 후 비식별 영상 대상"). 마킹 레코드가 보관·반환하는 "영상 경로"도 비식별 산출물(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`)이어야 하며, 최소한 원본 절대경로를 작업자에게 반환해서는 안 된다.
- **현재 동작(이슈 내용)**: `MarkingService.java:216-218`
  ```java
  LsMarking marking = "AUTO".equals(req.mode())
          ? LsMarking.createAuto(rawSn, eventName, req.intervalFrames(), raw.getRawFilePathNm(), marksJson, actorNo, fps)
          : LsMarking.createManual(rawSn, eventName, raw.getRawFilePathNm(), marksJson, actorNo, fps);
  ```
  `raw.getRawFilePathNm()`(= 관제 원본 경로)이 `LS_MARKING.VIDEO_FILE_PATH_NM` 에 저장되고, `MarkingResponse.java:54` 가 이 값을 `videoPath` 로 **201 응답에 실어 WORKER 에게 반환**한다.
  - 실측(rawSn 146, **배정 WORKER 토큰**): `"videoPath":"/app/storage/raw/seed/sample-cctv-1080p.mp4"`
  - 같은 영상의 비식별 산출 경로: `LS_DEIDENT_PROC_LOG(146).DE_IDNTF_FILE_PATH_NM = /app/storage/raw/seed/146/deid/sample-cctv-1080p-mask.mp4` (디렉터리·파일명 모두 상이 — 문자열 치환으로 도출되지 않음)
  - 본 검증이 만든 마킹 10행(sn 14~24) **전부** 원본 경로.
  - **소비자 추적**: `src/main` 전수 grep 결과 `getVideoFilePathNm()` 참조는 `MarkingResponse.java:54` **한 곳뿐**이다. 즉 이 컬럼은 파이프라인 어디에서도 쓰이지 않고 **응답으로 내보내는 것이 유일한 용도**다. (VLM 위탁은 이 값을 쓰지 않고 별도로 비식별본을 해석 — mock 로그가 `-mask.mp4` 를 프로브한 것으로 확인.)
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/videos/146/markings \
    -H "Authorization: Bearer $WORKER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"mode":"AUTO","intervalFrames":1200}' | python3 -m json.tool   # → data.videoPath
  ```
  ```sql
  select m.video_file_path_nm, d.de_idntf_file_path_nm
    from ls_marking m join ls_deident_proc_log d on d.data_raw_sn = m.raw_sn
   where m.raw_sn = 146 and d.proc_stts_cd = 'SUCCEEDED';
  ```
- **영향**: 화면상 원본 **픽셀** 노출은 없다(스트리밍은 비식별본만 서빙, `Content-Range .../34654319` = mask 파일 크기로 확인). 그러나 ① 관제 NAS 내부 절대경로가 WORKER 권한 응답에 실린다(CWE-209 정보 노출 — 저장소 레이아웃·파일명 규칙이 그대로 드러난다) ② 지금은 소비자가 없지만 이 컬럼을 읽는 후속 로직이 생기면 곧바로 **원본을 가리키게 된다**(비식별 계약 위반의 잠복 지점). 2차 파이프라인 구동의 PIPE-ISSUE-02 와 동일 결함이며 독립 재현으로 확정됐다.
- **수정 방향(제안)**: 비식별 산출 경로(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` **저장값 조회** — 파일명을 조합·추측하지 말 것)를 저장·반환하거나, 응답에서 경로 필드를 제거하고 스트리밍 URL 만 내린다. ⚠ **구현하지 않는다.**

### [C-ISSUE-02] TC-MARK-11 — 29.97fps(분수 fps) 마킹 계산의 회귀 테스트가 여전히 없다 (1차 B-ISSUE-27 이월)
- **심각도**: LOW
- **기대 동작(기대효과)**: 실제 CCTV 자산의 표준 프레임레이트인 29.97(30000/1001)에서 `totalFrames = Math.round(dur×fps)` 와 타임스탬프 절단이 정확한지 자동 회귀로 고정돼야 한다. 이 값이 틀어지면 마킹↔프레임추출 정렬(`seekMillis`)이 통째로 어긋난다.
- **현재 동작(이슈 내용)**: 실동작은 정상이다 — rawSn 153(dur=113s, `video.fps=29.97002997002997`)에 `intervalFrames=1129` → marks `0/1129/2258`. fps 30 가정이면 `totalFrames=3390` 이라 3387 이 포함됐어야 하는데 포함되지 않았으므로 **29.97 기반 3387 이 실제로 쓰였음**이 확정된다.
  그러나 `backend/src/test` 전수 grep 결과 **마킹 계산 경로에 29.97 을 쓰는 테스트는 0건**이다. 존재하는 것은 `VideoFpsResolverTest:52 "분수fps_29.97_정확파싱"`(리졸버 파싱), `VideoMetaServiceTest`(메타 적재), `BrampVideoProbeTest`(프로브 파싱)뿐이고, `MarkingServiceTest` 가 커버하는 fps 는 **25 / 30(폴백) / 60** 세 가지다.
- **재현/확인 경로**:
  ```bash
  grep -rn "29.97" backend/src/test/java/kr/co/cudo/authoring/marking/   # → 0건
  grep -n "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/marking/MarkingServiceTest.java | grep -i fps
  ```
- **영향**: 정수 fps(25/30/60)만 고정돼 있어, `Math.round` 를 다른 반올림/절단으로 바꾸는 리팩터링이 **정수 fps 테스트는 전부 통과시키면서** 분수 fps에서만 1프레임 어긋나는 회귀를 통과시킬 수 있다. 실 자산이 29.97 이므로 운영 영향이 가장 큰 구간이 무방비다.
- **수정 방향(제안)**: `MarkingServiceTest` 에 `fps=29.97002997002997 · dur=113 → totalFrames=3387`(끝경계 3387 미포함) 및 대표 타임스탬프 절단 단언을 추가. ⚠ **구현하지 않는다.**

### [C-ISSUE-03] TC-MARK-46(연장) — 배치가 미트리거된 마킹은 **영원히 활성(PENDING)** 으로 남아 해당 영상의 재마킹을 무기한 409 로 막는다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 409 안내문이 실제로 수행 가능한 동선을 말해야 한다(루트 `CLAUDE.md` 의 증강 409 안내 원칙과 동일 취지 — "수행 불가능한 안내를 하지 않는다"). `이미 진행 중인 마킹이 있습니다. 기존 마킹이 **종결된 뒤** 다시 시도하세요.` 라고 안내한다면, 그 마킹을 종결시키는 경로가 존재해야 한다.
- **현재 동작(이슈 내용)**: `MarkingBatchBridge` 가 배치를 트리거하지 못하는 경로(검수 소유 상태 `PENDING/IN_REVIEW/APPROVED/REJECTED` 또는 이미 클레임됨)에서는 마킹 행만 `PENDING` 으로 커밋되고 배치·VLM 이 전혀 돌지 않는다. 그런데 `LS_MARKING` 을 종결(`VLM_COMPLETED`/`VLM_FAILED`)로 전이시키는 코드는 **VLM 콜백 수신부(`VlmResultService.java:188·224`) 하나뿐**이며(그 외 `src/main` 전수 grep 결과 마킹 상태를 내리는 스윕/만료 잡 **0건**), VLM 위탁은 배치가 돌아야 발생한다. 따라서:
  - 마킹 = `PENDING`(활성) 고착 → `MarkingGuards.requireNoActiveMarking` 이 이후 모든 재마킹을 **409** 로 거부
  - 배치단계는 `MARKING_READY` 로 남아 있어 프리컨디션은 계속 통과 → 사용자는 "마킹은 되는데 아무 일도 안 일어남"이 아니라 "이제 마킹 자체가 안 됨" 으로 악화
  - 실측 고착 인스턴스 2건: rawSn **151**(본 검증 — 검수제출로 작업상태 PENDING, 마킹 sn 21이 `PENDING` 으로 남아 재마킹 409), rawSn **134**(타 에이전트 구동 — 작업상태 `REJECTED`, 마킹 sn 10이 `PENDING` 고착, 03:18 이후 계속). **특히 반려(REJECTED) 후 재마킹 시도는 이 상태를 만드는 정규 동선이다.**
- **재현/확인 경로**:
  ```bash
  # 1) 배정 → 검수제출(작업상태 PENDING) → 마킹
  curl -X POST .../v1/assignments -d '{"workerId":2001,"rawDataIds":[N]}'
  curl -X POST .../v1/reviews/N/submit
  curl -X POST .../v1/videos/N/markings -d '{"mode":"AUTO","intervalFrames":1200}'   # 201 batchTriggered=false
  # 2) 같은 영상 재마킹
  curl -X POST .../v1/videos/N/markings -d '{"mode":"AUTO","intervalFrames":1200}'   # 409 (영구)
  ```
  ```sql
  select m.marking_sn, m.stts_cd, r.data_stts_cd, s.data_stts_cd
    from ls_marking m join ls_data_raw r on r.raw_sn=m.raw_sn
    left join ls_raw_data_status s on s.raw_data_id=m.raw_sn
   where m.stts_cd='PENDING';   -- 실측: 134, 151
  ```
- **영향**: 기능 정지(가용성). 해당 영상은 마킹을 다시 걸 수 없어 배치 재기동 경로가 사라진다(운영 API 중 마킹 상태를 종결시키는 것이 없으므로 DBA 수동 UPDATE 외 복구 수단 없음). DEV_FIX H11 이 "무음 스킵"은 없앴지만 그 대가로 만들어진 활성 마킹의 **회수 경로가 없다** — 이 프로젝트가 반복해 겪은 "차단은 있는데 복구가 없다" 비대칭의 재발 형태다.
- **수정 방향(제안)**: ① 브리지가 배치를 트리거하지 못한 경우 같은 트랜잭션 경계 밖에서 그 마킹을 종결(`VLM_FAILED` 등)로 내려 활성 집합에서 빼거나, ② 아예 배치 미트리거가 확정된 상태(검수 소유)에서는 마킹 생성 자체를 프리컨디션에서 거부하거나, ③ 활성 마킹 만료 스윕(고아 PENDING 회수)을 추가. ⚠ **구현하지 않는다.**

### [C-ISSUE-04] (이월 재확인) B-ISSUE-23 — AUTO `intervalFrames` 상한 미검증이 2차에도 그대로다
- **심각도**: LOW
- **기대 동작(기대효과)**: `intervalFrames` 가 `totalFrames` 를 초과하면 자동 마킹이 의미를 잃으므로 400 으로 거부하거나 최소 marks 수를 보장한다(1차 기대값 유지).
- **현재 동작(이슈 내용)**: `MarkingService.java:196-198` 은 여전히 하한(`null || <= 0`)만 본다. 실측(rawSn 154, dur=113s·fps 29.97 → totalFrames 3387):
  ```
  POST /v1/videos/154/markings {"mode":"AUTO","intervalFrames":2147483647}
  → 201 {"marks":[{"frameIndex":0,"timestamp":"00:00"}], "batchTriggered":true}
  ```
  후속 배치가 정상 완주하여 `LS_DATA_SRC(154)=1`(프레임 1장), `LS_DATA_RAW(154)=COMPLETED`. 즉 **학습데이터가 1프레임만 생성됐는데 상태는 정상 완료**다. 타 에이전트 구동분(rawSn 137, `FRME_INTV_NOCS=999999999`, marks 1건)도 동일 형태로 관측된다.
- **재현/확인 경로**: 위 curl + `select frme_intv_nocs, mark_cn from ls_marking where raw_sn=154;` / `select count(*) from ls_data_src where raw_sn=154;`
- **영향**: 1차 판단과 동일(오조작 시 1프레임 학습데이터가 정상 완료로 흐름). `durationSec<=0` 은 backstop 으로 막으면서 반대편 경계는 무방비인 비대칭도 그대로다. 케이스 소관은 B 클러스터 `TC-BATCH-096`("현재 동작 고정")이므로 C-1 판정에는 반영하지 않고 **이월 이슈 대조 목적**으로만 기록한다.
- **수정 방향(제안)**: `intervalFrames < totalFrames` 검증 또는 산출 marks 가 1건이면 경고/거부. ⚠ **구현하지 않는다.**

---

## 부기 — 결함이 아닌 관찰

1. **409(활성 마킹 중복)는 정상 흐름에서 거의 도달하지 않는다.** 가드 평가 순서가 `MARKING_READY`(412) → 활성중복(409) 이고, 마킹이 성공하면 배치가 즉시 `LS_DATA_RAW.DATA_STTS_CD` 를 `PROCESSING/COMPLETED` 로 올리기 때문에, 재요청은 409 가 아니라 412 를 받는다(rawSn 149 실측). 409 가 실제로 나오는 조건은 **배치가 트리거되지 않은 마킹**(= C-ISSUE-03 상태)뿐이다. 설계 의도대로의 순서이므로 결함으로 분류하지 않되, "409 는 곧 고착 신호"라는 해석이 성립한다.
2. **파생영상(129 증강 / 130·131 해상도)에 마킹 전용 가드는 없다.** 실측 412 는 `ORGNL_RAW_SN` 판정이 아니라 배치단계(`COMPLETED`) 가드에서 나온다. 파생은 생성 시점에 이미 `COMPLETED` 로 확정되므로 현재는 결과적으로 차단되지만, 파생을 `MARKING_READY` 로 만드는 경로가 생기면 마킹이 열린다. C-1 케이스 표에 해당 단언이 없어 판정 대상은 아니다.
3. **트랜잭션 경계 실증**: `MarkingService` 의 오케스트레이션→persist 호출이 `@Lazy self` 프록시 경유임을 실동작으로 확인했다 — 자기호출이라면 `@Transactional` 이 적용되지 않아 `@TransactionalEventListener(AFTER_COMMIT)` 가 발화하지 못하는데, 마킹 10건 전부에서 브리지 로그(`[MarkingBatchBridge] handling marking completed rawSn=...`)가 **응답 반환 전 같은 HTTP 스레드**(`http-nio-8080-exec-N`)에서 관측됐고, 그 결과가 응답 `batchTriggered` 에 실렸다. 이 프로젝트의 알려진 실사고(자기호출 프록시 우회)는 이 경로에 없다.
4. **mock 실경유 확인(self-fill 없음)**: 본 검증이 유발한 외부 왕복은 전부 backend 컨테이너 IP `172.18.0.5` 발이다 — KPST `POST /project`→`GET /retrieve_progress`(rawSn 145~155 비식별), VLM `POST /v1/videovlm/describe` 200 → 콜백 `POST /api/v1/vlm/callback` 200(rawSn 146·153 각 시계열 메타 15건). VLM 에 전달된 미디어는 `sample-cctv-1080p-**mask**.mp4`(비식별본).
5. **정리하지 않은 잔여 데이터**: rawSn 145~155(및 `DEV-CLIP-9501~9511`)는 그대로 남겨 두었다. 특히 **150**(`EVNT_TYPE_CD` 공백 · MARKING_READY)과 **155**(MARKING_READY · 마킹 0건)는 후속 에이전트가 재사용 가능한 마킹 픽스처다. rawSn **151** 은 C-ISSUE-03 고착 상태의 증거이므로 마킹 상태를 되돌리지 않았다.
