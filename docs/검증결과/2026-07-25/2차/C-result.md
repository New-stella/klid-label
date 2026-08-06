# C 클러스터 (마킹/라벨링) — 2차 검증 결과

> 275건 · 기준 실동작(HEAD ca3c712b + 파이프라인 실데이터) · 2026-07-31

> ⚠ 이슈 ID 주의: C-part5 의 `C-ISSUE-81/82` 는 1차 ISSUES.md 의 동명 ID 와 충돌한다(2차 신규 건). ISSUES.md 병합 시 재번호 필요.


---

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

---

# C 클러스터 part2 (TC-LABEL-01~73) 2차 검증 결과

- 검증 시각: 2026-07-31 03:25~03:45 KST · backend `localhost:18081`(이미지 HEAD `ca3c712b` 재빌드본, pipeline-drive §서두)
- 스키마 `public` · 인증 `POST /v1/dev/tokens`(REVIEWER 1001 / WORKER 2001 / PORTAL_USER 3001)
- **컨테이너 재기동·재빌드·빌드/테스트 실행 0건. 소스/설정 파일 수정 0건**(본 문서 1개만 신규 작성)
- **rawSn 126 라벨 무변경**(조회만) · **rawSn 133 신고 OPEN 유지**(resolve 안 함) · 파생 129/130/131 무접촉

## 검증용 샌드박스 (파괴적 조작 격리)

D/E 클러스터 참조 데이터를 건드리지 않기 위해 아래를 사용했다.

| 용도 | 대상 | 착수 상태 | 종료 상태 |
|---|---|---|---|
| 라벨 CRUD·full-replace·버전·좌표 검증 | **srcSn 105** (rawSn **138**, 미배정, 실이미지 1920×1080, 라벨 0건) | 라벨 0 / `LBL_VER=0` | **라벨 0 으로 원복**(`LBL_VER` 는 증가 잔존) |
| 온라인 오토라벨(실검출) | **srcSn 106** (rawSn 138) | 라벨 10 | **라벨 10 (무변경 — 온라인 경로는 미저장)** |
| bounds 실측 불가 분기 | **srcSn 30** (rawSn 20006, 이미지 파일 부재) | 라벨 0 | **라벨 0 으로 원복** |
| IDOR 대상 | srcSn 105/rawSn 138 (WORKER 2001 미배정) | — | — |

**잔존 부산물(의도·무해)**: ①`LS_LABEL` `lblId=37 'ZZTEST-C2'` **USE_YN='N'**(soft delete — 활성 목록 비노출) ②`LS_LABEL_ATTR` `atrbId=1`(labelId 1 에 생성 후 DELETE 호출로 비활성) ③`LS_DATA_LBL_HSTRY` srcSn=105 이벤트 28행 ④`LS_DATA_SRC(105).LBL_VER` 증가. **다른 클러스터 판정에 영향 없음**(활성 라벨 마스터 9종·rawSn 126/132/133/129~131 전부 불변).

## 집계

| 판정 | 건수 | 비고 |
|---|--:|---|
| PASS | **66** | 실동작 **60** / 정적+테스트대조 **6** |
| FAIL | 0 | |
| PARTIAL | 0 | |
| BLOCKED | 0 | |
| 확인필요 | 0 | |
| N/A | 0 | |
| **검증 대상 계** | **66** | |
| (집계 제외) 폐기 | 1 | `~~TC-LABEL-70~~` — 좌표정책 반전(6d1b3703)으로 폐기, 대체 TC-LABEL-137/138(범위 밖) |

- 대상 ID 실측: `01~37`(37건, 38·39 는 결번) + `40~55`(16건) + `60~73`(14건, 70 폐기) = 표 행 67 − 폐기 1 = **66건**
- 정적 판정 6건: **TC-LABEL-54 · 67 · 68 · 69 · 72 · 73** — 전부 "런타임 유발 조건을 이 환경에서 만들 수 없음"이 사유이며, 각각 대응 단위테스트가 존재해 커버 확인(사유는 결과표 비고에 개별 명시)
- 근거 드리프트 **4건**(전부 경미 — 아래 별도 절)

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 실측 | 판정 |
|---|---|---|---|
| **C-ISSUE-21** | full-replace 동시저장 lost update — stale 작업본이 타인 라벨을 **무경고 삭제**(200). REVIEWER 가 `LabelAccessGuard` 를 무조건 통과해 WORKER 와 동시 편집 가능 | ①`labelVersion` 첨부 시 stale 요청 **409**(`{"message":"다른 사용자가 먼저 저장했습니다…"}`, `[Label] stale label version rejected srcSn=105 requested=0 current=1`) ②**REVIEWER 토큰으로도 409** — 역할 우회 없음 ③프레임 행 비관적 락 + 스칼라 프로젝션 CAS(`LabelService:272-274`) ④무변경 저장은 버전 미증가(`labelVersion` 1→1 실측) ⑤FE 가 실제로 토큰을 실어 보냄(`frontend/src/features/label/hooks/useUpdateLabels.ts:55`, `api.ts:304`) | **해소** |
| **C-ISSUE-22** | 이미지 경계 초과 좌표 무검증 — `[[999999,888888]]` 이 200 으로 그대로 저장 | 신규 라벨 `x=5000`/`y=2000`/`1e18` 전부 **400** `"좌표가 이미지 경계를 벗어났습니다 (…이미지=1920x1080)"`. 경계값 `[[0,0],[1920,1080]]` 은 200(허용). 기존 라벨은 **좌표가 실제로 바뀔 때만** 강제(레거시 OOB 무변경 재전송 200 / 경계 밖으로 이동 400) — 프레임 영구 차단 회귀 없음 | **해소** |
| **C-ISSUE-23** | R7 손상 `pointCn` fail-safe 전용 회귀 테스트 부재 | 동작은 정상(손상값 `not-a-json{{` → '변경됨' 판정, UPDATED 이력 기록 실측). 그러나 `grep -rn "pointsEqual\|normalizePoints" backend/src/test` = **0건** 유지 | **미해소(이월, LOW)** |
| **C-ISSUE-24** | `CONTROL_NOTIFY_ENABLED=false` 라 TASK_MODIFIED 런타임 관측 불가 | 런타임 실효값 **true**. `LS_CONTROL_NOTIFY_FALLBACK` `queue_sn=5` `TASK_MODIFIED/126/SUCCEEDED/SUCCESS` 실재(라벨 수정 → export v2 → 통지) | **해소** |
| **C-ISSUE-25** | 마스터 soft delete 시 그 라벨을 참조하는 프레임의 **모든 저장이 409 로 영구 차단** | 3분기 전수 실측 — ⓐ기존 라벨이 비활성 labelId **유지** → **200** ⓑ**신규** 라벨에 비활성 labelId 부여 → **409** ⓒ**기존 라벨의 labelId 를 비활성으로 변경** → **409**(우회 차단) | **해소** |
| **C-ISSUE-41** | 실모델 YOLO 경계 음수 좌표로 온라인 오토라벨이 프레임 80% 400 거부(배치와 정책 불일치) | 온라인 BBOX 9건·POLYGON 9건 정상 반환(400 0건). 규칙이 `DetectionBoxNormalizer` 공용 단일 원천으로 통합돼 배치(`YoloLabelPersister`)와 동일 함수 사용 | **해소** |

## ★좌표 2축 실측 (★3 정합 확인)

| 입력 | ① 사용자 저장 경로 (`PUT /v1/frames/105/labels`) | ② AI 검출 응답 경로 (`POST /v1/frames/106/autolabel`) | ★3 정합 |
|---|---|---|:--:|
| 음수 `x=-1` | **400** `"좌표는 0 이상이어야 합니다 (x=-1.0, y=5.0)"` | **clamp → 0** (`DetectionBoxNormalizer:79-81`, 단위테스트 `경계밖_음수좌표는_0으로_clamp되어_반환된다`) | ✅ 의도된 비대칭 |
| 경계 초과 `x=5000` (이미지 1920) | **400** `"좌표가 이미지 경계를 벗어났습니다"` | **clamp → 1920** (`이미지_상한_초과좌표는_이미지_경계로_clamp된다`) | ✅ |
| 경계값 `x=1920, y=1080` | **200**(허용 — 우/하단 끝 정상 좌표) | clamp 무변형 | ✅ |
| 거대값 `1e18` | **400**(경계 초과 메시지) | clamp → 경계값 | ✅ |
| `NaN` | **400** `"요청 본문이 올바르지 않습니다."`(Jackson 역직렬화 단계) | **400 all-or-nothing** (`DetectionBoxNormalizer:54-58` 유한성 가드가 clamp **이전**) | ✅ |
| 좌표쌍 원소수 ≠ 2 | **400** `"좌표는 [x, y] 형태여야 합니다."` | 개수 ≠ 4 → **400 all-or-nothing**(`:51-53`) | ✅ |
| 퇴화(`x2<=x1`) / 순서역전 | **200 (검증 없음)** ← 아래 **C-ISSUE-21** | **해당 검출만 스킵**(`:65-67`, WARN) | ⚠ 축 밖 갭 |
| 소수점 `10.5, 10.25` | **200**(정밀도 보존 저장) | 그대로 | ✅ |
| bounds 실측 불가(이미지 파일 부재, srcSn 30) | **200 + WARN**(`[Label] frame image path unresolved — coordinate upper-bound check skipped srcSn=30`) — 상한만 skip, 하한·형식 유지 | 상한 없음 취급, 하한 0 clamp 유지 | ✅ fail-open 일치 |

> **결론**: ★3(사용자=400 거부 / AI=clamp)은 **양축 모두 정책대로 동작**한다. 통일 제안 없음. 마지막 행의 퇴화 박스 갭은 *경계(bounds)* 축이 아니라 *기하 유효성(arity/degeneracy)* 축이라 ★3 의 대상이 아니며, 별도 이슈로 기록한다.

## 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-LABEL-01 | 프레임 라벨 조회 정상 | PASS | [실동작] `GET /v1/frames/65,66,105/labels` → 200. `siblings`(3~6건, `hasLabel`) + AI enrich(`autoLblYn:"Y"`,`confScore:0.703`,`lblSrcCd:"YOLO"`) + 마스터 enrich(`labelName`,`color`) + `labelVersion:2` 전부 포함 | |
| TC-LABEL-02 | 조회 IDOR: 타 영상 프레임 | PASS | [실동작] WORKER(2001) → 미배정 rawSn 138 프레임 105 → **403** `"본인에게 배정되지 않은 영상입니다."` | `LabelAccessGuard:62-69` |
| TC-LABEL-03 | 조회: 미인증 | PASS | [실동작] 무토큰 → **401** `UNAUTHORIZED` | |
| TC-LABEL-04 | 조회: 프레임 미존재 | PASS | [실동작] srcSn 999999 → **404** `"프레임을 찾을 수 없습니다."` | null 방어는 `:54-58` 정적 |
| TC-LABEL-05 | frameImageType RAW(REVIEWER+raw) | PASS | [실동작] REVIEWER `?raw=true` → `"frameImageType":"RAW"`. 추가로 `GET /v1/frames/65/image?raw=true` 바이트 md5 `c155a39c…` = **원본** 프레임 파일 md5 일치 | |
| TC-LABEL-06 | frameImageType DEID(WORKER raw무시) | PASS | [실동작] WORKER `?raw=true` → `"DEID"`. 이미지 md5 `064376b2…` = **비식별** 프레임 파일 md5 일치(원본 아님) | CWE-359 방어 실증 |
| TC-LABEL-07 | bulkUpsert 신규 INSERT(수동) | PASS | [실동작] 3건 INSERT → `LS_DATA_LBL` 356/357/358, `LS_DATA_LBL_AI_INFO` 행 **미생성**(→ 응답 `autoLblYn:"N"`), 이력 `add_cnt=3` ADDED×3 | |
| TC-LABEL-08 | bulkUpsert 기존 UPDATE | PASS | [실동작] id=356 좌표 변경 → `lbl_hstry_sn=14` `mdfcn_cnt=1`, `before{[[10,10],[100,100]]}`/`after{[[11,11],[101,101]]}` 스냅샷 기록 | |
| TC-LABEL-09 | full-replace: 빠진 라벨 삭제 | PASS | [실동작] 3건 중 358 제외 저장 → 358 **실삭제**, `lbl_hstry_sn=15` `del_cnt=1` + `before` 스냅샷(`after:null`) | |
| TC-LABEL-10 | full-replace: 빈 items=전량 삭제 | PASS | [실동작] `{"items":[]}` → `select count(*) ... src_sn=105` = **0**, `del_cnt=2` DELETED×2 | 의도된 계약(컨트롤러 javadoc 명시) |
| TC-LABEL-11 | 삭제 순서 FK고아 방지 | PASS | [실동작] `ATTR_VAL`(atrbVlId 1)+`AI_INFO` 자식 보유한 lblSn 383 을 full-replace 삭제 → **200**, 세 테이블 모두 0행(FK 위반 500 없음) | `FK_LS_DATA_LBL_ATTR_LBL` 실존 확인 |
| TC-LABEL-12 | 작업락 시 저장 차단 | PASS | [실동작] 신고 OPEN(작업락) rawSn 133 의 srcSn 78 PUT → **409** `"비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다."` | |
| TC-LABEL-13 | dedupById 동일 id 중복 | PASS | [실동작] 같은 id=356 을 2회(앞 `[[1,1],[2,2]]` / 뒤 `[[12,12],[102,102]]`) → DB `[[12.0,12.0],[102.0,102.0]]` = **last-value-wins**, 이력 UPDATED 1건 | |
| TC-LABEL-14 | 좌표 검증: 빈 points | PASS | [실동작] `points:[]` → **400** `"items[2].points: must not be empty"` — **DTO `@NotEmpty` 가 선발화**(케이스 정정 문구와 일치, 서비스 메시지 미도달) | |
| TC-LABEL-15 | 좌표 검증: [x,y] 형식 위반 | PASS | [실동작] `[[1,2,3]]` → **400** `"좌표는 [x, y] 형태여야 합니다."` | |
| TC-LABEL-16 | 좌표 검증: 음수 | PASS | [실동작] `x=-1` → **400** `"좌표는 0 이상이어야 합니다 (x=-1.0, y=5.0)"` | **사용자 경로 한정**(★3 ①). AI 경로 clamp 와 비대칭 = 정책 |
| TC-LABEL-17 | 좌표 검증: 0 허용(경계) | PASS | [실동작] `[[0,0],[10,10]]` → **200** 저장(lblSn 376) | |
| TC-LABEL-18 | 신규 라벨 >1000점 차단(DoS) | PASS | [실동작] 신규 POLYGON 1001점 → **400** `"라벨당 좌표 개수 초과 (최대 1000 점)"` / 경계 **1000점 → 200** 저장(`length(point_cn)=13781`) | CWE-770 |
| TC-LABEL-19 | 기존 라벨 >1000점 simplify | PASS | [실동작] 기존 lblSn 382 에 1001점 전송 → **200**, 저장 후 점수 **15점**(Douglas-Peucker) — 400 아님 | |
| TC-LABEL-20 | labelId 미존재 | PASS | [실동작] `labelId:999999` → **404** `"라벨 마스터를 찾을 수 없습니다: labelId=999999"` | |
| TC-LABEL-21 | labelId USE_YN='N' → 신규 부여에만 409 | PASS | [실동작] labelId 37 soft delete 후 3분기 전수: ⓐ기존 라벨 유지 **200** ⓑ신규 부여 **409** ⓒ기존 라벨 labelId 변경 **409** — 정정 문구·우회방지 그대로 | C-ISSUE-25 해소 확증 |
| TC-LABEL-22 | Mass Assignment: autoLblYn 무시 | PASS | [실동작] `source:"AUTO_YOLO"` + `autoLblYn:"N"` → 응답 **"Y"** / `source:"MANUAL"` + `autoLblYn:"Y"` → 응답 **"N"**. `ls_data_lbl` 에 컬럼 자체 부재(전 컬럼 조회 확인) — AI_INFO 행 존재로 파생 | CWE-915 |
| TC-LABEL-23 | provenance AUTO_YOLO→AI_INFO | PASS | [실동작] `AUTO_YOLO/conf 0.87/algorithm YOLO` → `LS_DATA_LBL_AI_INFO(383, lbl_src_cd=YOLO, conf_score=0.87000, auto_lbl_yn=Y)` 행 생성 | |
| TC-LABEL-24 | provenance 화이트리스트 위반 | PASS | [실동작] `source:"HACK"` → **400** `"items[2].source: source 는 MANUAL/AUTO_YOLO/AUTO_SAM2 중 하나여야 합니다."` | |
| TC-LABEL-25 | confScore 범위 초과 | PASS | [실동작] `confScore:1.5` → **400** `"confScore 는 1.0 이하여야 합니다."` | |
| TC-LABEL-26 | items >500 상한(DoS) | PASS | [실동작] 501건 → **400** `"items: 한 번에 처리 가능한 라벨 수 초과 (최대 500)"` | 근거 라인 드리프트(아래) |
| TC-LABEL-27 | 무변경 재저장 이력 미발행(R7) | PASS | [실동작] 동일 3건 재전송 → 200, `labelVersion` **1→1 미증가**, `LS_DATA_LBL_HSTRY` 신규 행 **0**, 통지 0 | |
| TC-LABEL-28 | R7 정규화 비교(5 vs 5.0) | PASS | [실동작] DB 저장값 `[[10.0,10.0],…]` 에 요청 `[[10,10],…]`(정수 표기) 재전송 → **무변경 판정**(이력·버전 불변) | |
| TC-LABEL-29 | R7 레거시 3포맷 흡수 | PASS | [실동작] `pointCn` 을 평탄 `[10,10,100,100]` · 객체배열 `[{"x":20,"y":20},…]` 로 직접 적재 후 정규 표현 재전송 → **이력 0건**(무변경). 정규 포맷은 TC-28 에서 확인 = 3포맷 전수 | |
| TC-LABEL-30 | R7 손상 JSON fail-safe | PASS | [실동작] `pointCn='not-a-json{{'` 라벨 재저장 → **UPDATED 이력 기록**(`before.pointCn="not-a-json{{"`) = '변경됨' fail-safe. **전용 회귀 테스트는 여전히 0건**(C-ISSUE-23 이월) | |
| TC-LABEL-31 | TASK_MODIFIED: APPROVED만 | PASS | [실동작] APPROVED rawSn 126 라벨 수정분에 대해 `LS_CONTROL_NOTIFY_FALLBACK queue_sn=5 TASK_MODIFIED/126/SUCCEEDED/SUCCESS` + payload `changed_items` 실재(직접 SELECT). export **v2** 재생성 후 발송(`reExport=true`, pipeline-drive §1 #17) | 126 무변경 원칙상 재유발 안 함 — 기존 산출물 실측으로 판정 |
| TC-LABEL-32 | TASK_MODIFIED: 검수전 미발행 | PASS | [실동작] 미승인 rawSn 138(`LS_RAW_DATA_STATUS` 행 없음)에 **저장 20회 이상** 수행 → notify 테이블에 rawSn 138 행 **0건** | |
| TC-LABEL-33 | 이력 조회 IDOR | PASS | [실동작] WORKER → 미배정 프레임 105 이력 → **403** | |
| TC-LABEL-34 | 이력 페이지 상한 클램프 | PASS | [실동작] `?size=500` → 응답 `"size":100`. 기본값 `?` 미지정 → `"size":20` | |
| TC-LABEL-35 | 이력 임의 sort 무시(500 차단) | PASS | [실동작] `?sort=badfield,desc&size=3` → **200**(500 아님), content 선두가 최신 이벤트(`lblHstrySn=27`) = 서버 고정 정렬 | |
| TC-LABEL-36 | 동시 저장 lost update 차단(정정) | PASS | [실동작] stale `labelVersion:0`(현재 1) → **409 CONFLICT**. **REVIEWER 토큰으로도 409**(역할 우회 없음). WARN `stale label version rejected srcSn=105 requested=0 current=1`. 미첨부 시 skip(하위호환)은 확정 정책이며 FE 는 실제로 첨부 | C-ISSUE-21 해소 |
| TC-LABEL-37 | 삭제 감사 로깅 PII 미출력 | PASS | [실동작] 로그 8건 전수 확인 — `existing=/saved=/deleted=/labelVersion=` 카운트·버전만, **좌표·라벨명·PII 0** | |
| TC-LABEL-40 | 마스터 생성 정상 | PASS | [실동작] `POST /v1/manage/labels` → **201** `{labelId:37, useYn:"Y"}` | |
| TC-LABEL-41 | 마스터 근사중복(대소문+공백) | PASS | [실동작] 활성 `ZZTEST-C2` 존재 상태에서 `" zztest-c2 "` → **409** `"이미 사용 중인 라벨 이름입니다."` | |
| TC-LABEL-42 | 마스터 동시 생성 DB유니크 | PASS | [실동작] `pg_indexes` 에 `uk_ls_label_nm_ci ... WHERE use_yn='Y'`(V120) 실재. 앱 선검사 우회 직접 INSERT `' PERSON '` → `duplicate key ... "uk_ls_label_nm_ci"` 거부. [정적] `GlobalExceptionHandler:151-155` 가 이 제약명만 409 로 매핑(그 외 fail-closed 500) | |
| TC-LABEL-43 | dtctTypeCd allowlist 위반 | PASS | [실동작] `dtctTypeCd:"human"` → **400** `"지원하지 않는 검출 클래스입니다."` | 근거 라인 드리프트(아래) |
| TC-LABEL-44 | dtctTypeCd 활성 중복 매핑 | PASS | [실동작] `person` 재매핑 → **409** `"이미 사용 중인 검출 클래스 매핑입니다."`. DB `uk_ls_label_dtct_type` 부분 유니크도 직접 INSERT 로 거부 확인 | |
| TC-LABEL-45 | dtctTypeCd 미매핑 해제 허용 | PASS | [실동작] `"airplane"` 설정 → 응답 `dtctTypeCd:"airplane"` → 공백 `"  "` 재전송 → 응답 **`dtctTypeCd:null`** 저장 | |
| TC-LABEL-46 | color 소문자 hex 거부 | PASS | [실동작] `"#aabbcc"` → **400** `"color 는 대문자 hex (#RRGGBB) 형식이어야 합니다."` | |
| TC-LABEL-47 | type allowlist | PASS | [실동작] `type:"FOO"` → **400** `"type 은 BBOX/POLYGON/POINT/SKELETON 중 하나여야 합니다."` | |
| TC-LABEL-48 | sortNo 음수 | PASS | [실동작] `sortNo:-1` → **400** `"sortNo 는 0 이상이어야 합니다."` | |
| TC-LABEL-49 | 마스터 수정 미존재 | PASS | [실동작] `PUT /v1/manage/labels/999999` → **404** `"라벨을 찾을 수 없습니다."` | |
| TC-LABEL-50 | 마스터 수정 근사중복(자기제외) | PASS | [실동작] labelId 37 을 `"CAR"` 로 개명 → **409**. 자기 이름 `"ZZTEST-C2"` 재전송 → **200**(자기제외 정상) | |
| TC-LABEL-51 | 마스터 삭제 soft delete | PASS | [실동작] `DELETE /v1/manage/labels/37` → **204**, DB `use_yn='N'` 행 **잔존**(hard delete 없음) | |
| TC-LABEL-52 | 관리 권한: WORKER POST 차단 | PASS | [실동작] WORKER `POST /v1/manage/labels` → **403** `"권한이 없습니다."` | |
| TC-LABEL-53 | 조회 권한: WORKER/PORTAL 허용 | PASS | [실동작] WORKER GET **200** / **PORTAL_USER GET 200**. 대조군 — PORTAL 토큰의 `GET /v1/frames/65/labels` 는 **403**(채널 격리 유지) | |
| TC-LABEL-54 | findLabelIdByDtctType null/blank | PASS | [정적] `LabelMasterService:151-157` early-return(repository 미호출). 내부 메서드라 HTTP 표면 없음 → 단위테스트 `findLabelIdByDtctType_null_빈문자열이면_repository_미호출_empty` 로 커버 확인 | 정적 사유: 외부 진입점에서 null/blank 라벨명이 발생하지 않음 |
| TC-LABEL-55 | findLabelIdByDtctType 활성 유니크 최대1 | PASS | [실동작] `uk_ls_label_dtct_type ... WHERE use_yn='Y' AND dtct_type_cd IS NOT NULL`(V129) 인덱스 실재 + 중복 활성 매핑 직접 INSERT 거부 → `Optional` 시그니처의 NonUniqueResult 구조적 불가. trim 매칭은 `LabelMasterServiceTest` 커버 | |
| TC-LABEL-60 | autolabel BBOX 정상 | PASS | [실동작] `POST /v1/frames/106/autolabel` → 200, `detectedCount:9`, **모든 항목 `lblSn:null`**, `LS_DATA_LBL(src_sn=106)` 건수 **10 → 10 무변경**(DB 미저장 = 2경로 분리 준수) | `savedCount` 는 문서화된 deprecated mirror |
| TC-LABEL-61 | autolabel IDOR | PASS | [실동작] WORKER → 미배정 105 → **403**, ai 미호출(로그 무발생) | |
| TC-LABEL-62 | autolabel 작업락 | PASS | [실동작] 잠긴 rawSn 133(srcSn 78) → **409** `"작업이 잠긴 영상입니다."` | 락 없는 신고건은 412(코드 순서) |
| TC-LABEL-63 | autolabel inFlight 중복 | PASS | [실동작] 동일 프레임 POLYGON 3병렬 → **200/409/409** `"이미 오토라벨링이 진행 중인 프레임입니다."`. 완료 후 재요청 200 = finally 락해제 | |
| TC-LABEL-64 | resolveDetectClasses 매핑 0건 게이팅 | PASS | [실동작] 동일 분기(`effectiveClasses.isEmpty()`, `:229-235`)를 `classes:["fire"]`(마스터에 COCO 매핑 없음)로 발화 → `detectedCount:0` + `"검출할 수 있는 라벨이 없습니다…"` + 로그 `no mapped detect classes … requested=1`, **ai 미호출**. '마스터 전체 미매핑' 변형은 마스터 파괴 없이 유발 불가 → 단위테스트 `매핑된_라벨이_하나도_없으면_ai_미호출_빈결과_안내메시지` 커버 | |
| TC-LABEL-65 | resolveDetectClasses 화이트리스트 교집합 | PASS | [실동작] `classes:["person","hack"]` → person 검출 2건만 반환(전체 9건 중), WARN `[Autolabel] drop unmapped detect class=hack` | |
| TC-LABEL-66 | resolveDetectClasses 우회 시도 | PASS | [실동작] 미매핑 `fire` 강제 요청 → 교집합 공집합 → **ai 미호출·0건·NO_MAPPED 안내**. FE 요청 불신 확인 | |
| TC-LABEL-67 | autolabel mock 응답 차단 | PASS | [정적] `AutolabelOnlineService:246-251` — `resp.mock()` 이면 좌표 미반환 + `detectedCount=0` + MOCK message. 단위테스트 `mock_응답이면_빈_결과와_안내메시지를_반환한다`·`AutolabelMockMessageWiringTest` 커버 | 정적 사유: 실행 중 ai-server env `AI_MOCK_MODE=false` — mock 응답을 유발할 수단 없음(설정 변경 금지) |
| TC-LABEL-68 | 검출 좌표 개수 ≠4 → all-or-nothing 400 | PASS | [정적] `DetectionBoxNormalizer:51-53` `IllegalArgumentException` → `AutolabelOnlineService:565-570` 이 400 으로 승격(부분 반환 없음). 단위테스트 `좌표개수가_4개가_아니면_거부한다`·`형식위반은_여전히_all_or_nothing…` 커버 | 정적 사유: 실모델 ai-server 응답에 형식 위반을 주입할 수단 없음 |
| TC-LABEL-69 | 검출 좌표 NaN/Infinity → 400 | PASS | [정적] `DetectionBoxNormalizer:54-58` — `Double.isFinite` 가드가 **clamp 이전**(`:59` 보다 앞). 단위테스트 `NaN_Infinity_좌표는_거부한다_역직렬화_500_회귀방지` 커버 | 동상 |
| ~~TC-LABEL-70~~ | ~~validateBbox 음수/순서위반 400~~ | — | **폐기(2026-07-30)** — 집계 제외. `AutolabelOnlineService.validateBbox` 부재 확인(grep 0건) | 대체 TC-LABEL-137/138(범위 밖) |
| TC-LABEL-71 | 검출 0건 SAM 스킵 | PASS | [실동작] srcSn 105(검출 0인 프레임) → 200 `detectedCount:0`, 로그 `[Autolabel] no detections srcSn=105 rawSn=138 shape=BBOX` — SAM 호출 없음, reCheckLock 통과 후 빈 결과 | |
| TC-LABEL-72 | POLYGON maxBoxes 상한 | PASS | [정적] `AutolabelOnlineService:296-301` `limit=min(detected,maxBoxes)` + `truncated` → `polygonTruncatedMessage`. 단위테스트 `박스_개수가_상한을_초과하면_상한까지만_처리하고_message로_고지한다` 커버 | 정적 사유: 설정 `autolabel.polygon.max-boxes=20` 인데 이 환경 최대 검출이 13건 → 초과 유발 불가(설정 변경 금지) |
| TC-LABEL-73 | POLYGON wall-clock 예산 소진 | PASS | [정적] `:303`(deadline) `:313-319`(잔여≤0 → truncated=true + break + WARN). 단위테스트 `폴리곤_예산소진시_잔여박스_잘라_message_고지`(package-private `polygonTotalBudget` 주입) 커버 | 정적 사유: 기본 예산 60s, 실측 9박스 처리 ~17s → 소진 유발 불가 |

## 근거 드리프트

카탈로그 `근거(file:line)` 와 HEAD `ca3c712b` 실측의 차이. **모두 경미**(동일 파일 내 소폭 이동/포괄 범위)로 판정에는 영향 없음.

| ID | 카탈로그 근거 | 실측 | 성격 |
|---|---|---|---|
| TC-LABEL-22 | `LabelService.java:133-148` | `:133-143` 은 `resolveAiInfoMap`, `:145-165` 는 `resolveLsLabelMap`. **실제 Mass Assignment 트러스트 경계**는 `LabelItemDto.java:43`(autoLblYn 응답전용) + `LabelService.java:350-366`(source→AUTO_LBL_YN 서버 파생) | 근거 위치 부정확 |
| TC-LABEL-26 | `LabelBulkUpsertRequest.java:26` | `@Size(max = 500 …)` 은 **`:24`**. `:26` 은 record 본문 여는 `) {` | 2줄 밀림 |
| TC-LABEL-43·45 | `LabelMasterService.java:122-134` | `validateAndNormalizeDtctType` 본문은 **`:126-135`**(`:118-125` 는 javadoc) | 1줄 밀림 |
| TC-LABEL-42 | `LabelMasterService.java:74-78` · `LsLabelRepository.java:37` | 두 근거 모두 **앱 선검사**만 가리킨다. 케이스의 기대결과("DB유니크 차단 → 409")를 실제로 만드는 지점은 인덱스 `uk_ls_label_nm_ci`(V120) + **`GlobalExceptionHandler.java:138-166, :178-181`**(제약명 판별 후 409, 그 외 fail-closed 500)이며 근거에 없음 | 근거 누락 |

## 이슈 상세

FAIL/PARTIAL/확인필요 **0건**. 아래 1건은 반증(적대 검증) 과정에서 발견한 **범위 외 결함 후보**다(1차 C-ISSUE-25 와 동일한 성격의 기록).

### [C-ISSUE-21] (범위 외 발견 — TC-LABEL-15/16/17 반증 중) 사용자 저장 경로에 **기하 유효성(점 개수·퇴화·순서역전) 검증이 없다**

- **심각도**: MEDIUM (데이터 품질 오염 — 학습데이터/export COCO JSON 로 그대로 전파)
- **기대 동작(기대효과)**: `LBL_TYPE_CD` 가 요구하는 최소 기하 조건을 저장 시점에 강제해야 한다. BBOX/TRACK 은 대각 2점(면적 > 0), POLYGON/SEGMENT 는 정점 ≥ 3. 저장된 라벨은 곧바로 `LabelToAnnotationMapper` 를 거쳐 `bbox=[x,y,w,h]` / `polygon` 으로 export 되므로 폭·높이 0 이나 정점 1개짜리 도형은 학습데이터로 의미가 없다.
- **현재 동작**: `LabelService.validatePoints`(`backend/.../label/service/LabelService.java:706-730`)는 **① 비어있음 ② 각 원소가 `[x,y]` 2튜플인가 ③ 점 개수 상한 1000 ④ 음수** 만 본다. **점 개수 하한도, 타입별 arity 도, 퇴화/순서역전도 검사하지 않는다.**
  ```java
  // LabelService.java:719-729 (발췌)
  for (List<Double> pair : points) {
      if (pair == null || pair.size() != 2) { ... 400 ... }   // 원소 내부만 검사
      double x = pair.get(0); double y = pair.get(1);
      if (x < 0 || y < 0) { ... 400 ... }
  }
  // → points.size() 자체에 대한 하한·타입별 제약 없음
  ```
  실동작(srcSn 105, REVIEWER, 전부 **HTTP 200 저장 성공**):

  | 입력 | 결과 |
  |---|---|
  | `{"lblTypeCd":"BBOX","points":[[1,1]]}` (1점) | **200** 저장 |
  | `{"lblTypeCd":"BBOX","points":[[1,1],[2,2],[3,3]]}` (3점) | **200** 저장 |
  | `{"lblTypeCd":"POLYGON","points":[[1,1]]}` (정점 1) | **200** 저장 |
  | `{"lblTypeCd":"BBOX","points":[[50,50],[50,50]]}` (면적 0) | **200** 저장 |
  | `{"lblTypeCd":"BBOX","points":[[50,50],[50,80]]}` (폭 0) | **200** 저장 |
  | `{"lblTypeCd":"BBOX","points":[[500,500],[100,100]]}` (x2<x1 역전) | **200** 저장, DB `[[500.0,500.0],[100.0,100.0]]` 확인 |

  **같은 시스템의 다른 경로는 전부 막는다** — 비대칭이 본 지적의 핵심이다:
  - AI 검출 응답: `DetectionBoxNormalizer:51-53`(개수 ≠ 4 → 400), `:65-67`(clamp 후 `x2<=x1 || y2<=y1` → 해당 검출 스킵)
  - SAM 폴리곤 응답: `AutolabelOnlineService.validatePolygonPoints:600-604`(정점 < 3 → INVALID_INPUT)
  - SKELETON: `LabelService.validateSkeletonPoints:792-825`(정확히 17점, 원소 3, v∈{0,1,2})
  즉 **외부(AI) 입력에는 기하 유효성 규칙이 있는데 사용자 입력에는 없다.**
- **재현/확인 경로**:
  ```bash
  B=http://localhost:18081/api; RT=<REVIEWER 토큰>
  curl -s -o /dev/null -w "%{http_code}\n" -X PUT $B/v1/frames/105/labels \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' \
    -d '{"items":[{"lblTypeCd":"BBOX","labelId":1,"label":"rev","points":[[500,500],[100,100]]}]}'
  # → 200
  ```
  ```sql
  select lbl_sn, lbl_type_cd, lbl_nm, point_cn from ls_data_lbl where src_sn = 105;
  -- 411|BBOX|rev|[[500.0,500.0],[100.0,100.0]]
  ```
  (본 시험에서 만든 라벨은 검증 종료 시 `{"items":[]}` 로 전량 삭제 — 잔존 없음)
- **영향**: CWE-20(입력 검증 부재). 보안 침해는 아니고 **데이터 품질**이 리스크다. ①`LabelToAnnotationMapper.toBbox`(`:83-99`)가 min/max 바운딩을 하므로 크래시는 없으나 **`bbox` 의 w/h 가 0** 인 annotation 이 export JSON 에 그대로 실린다(정점 1개 POLYGON 은 `polygon=[x,y]` 2원소로 나간다). ②관제/데이터마트로 나가는 학습데이터셋에 학습 불가 도형이 섞인다. ③FE 캔버스가 이런 도형을 되읽을 때 렌더 이상이 발생할 수 있다(미확인). ④API 직접 호출·FE 버그·타 도구 연동 시 방어선이 전무하다.
- **★3 확정 정책과의 관계 (명시)**: **본 지적은 ★3 의 2축(사용자=400 거부 / AI=clamp)을 통일하자는 요구가 아니다.** ★3 은 *경계(bounds) 초과 좌표를 고칠 것인가 거부할 것인가* 의 축이고, 본 건은 *도형이 도형인가(arity·degeneracy)* 라는 별개 축이다. 좌표값을 **조용히 고치자는 제안이 아니라** 사용자 경로의 기존 스탠스(거부)를 그대로 적용하자는 것이다. 실제로 이 환경의 경계 clamp/거부 동작은 정책대로 정상 작동함을 위 「★좌표 2축 실측」에 별도 기록했다.
- **수정 방향(제안)**: `validatePoints` 에 타입별 최소 조건을 추가 — BBOX/TRACK 은 `points.size() == 2` 이고 `x2 != x1 && y2 != y1`(또는 min/max 로 면적 > 0), POLYGON/SEGMENT 는 `points.size() >= 3`. 위반 시 기존 좌표 검증과 동일하게 400(`INVALID_INPUT`). 기존 라벨(`id != null`)에는 `MAX_POINTS`·bounds 와 동일하게 **좌표가 실제로 바뀔 때만** 강제해 레거시 데이터로 프레임 저장이 영구 차단되는 회귀를 피한다. ⚠ **구현하지 않는다.**

### [C-ISSUE-22] (이월 확인) TC-LABEL-30 — R7 손상 `pointCn` fail-safe 전용 회귀 테스트 여전히 부재

- **심각도**: LOW (동작 정상 — 회귀 위험만)
- **기대 동작(기대효과)**: `pointCn` 이 파싱 불가한 손상값일 때 `pointsEqual` 이 '변경됨'으로 판정해 이력이 유실되지 않아야 하고, 그 동작이 회귀 테스트로 고정되어야 한다.
- **현재 동작**: 구현은 정상이다 — `LabelService.normalizePoints:568-585` 가 3포맷 + raw 폴백 모두 실패하면 `null` 반환, `pointsEqual:556-559` 가 `false`(변경됨)로 fail-safe. **실동작으로 확증**했다(`pointCn='not-a-json{{'` 라벨 재저장 → `LS_DATA_LBL_HSTRY` UPDATED 1건, `before.pointCn="not-a-json{{"`). 그러나 테스트 자산은 1차와 동일하게 없다: `grep -rn "pointsEqual\|normalizePoints" backend/src/test` = **0건**. 유사명 `LabelServiceFullReplaceIntegrationTest`「손상된_diff_JSON이_있어도_조회는_200과_빈changes를_반환한다」는 **이력 diff JSON(`CHG_DTL_CN`)** 손상 케이스로 대상이 다르다.
- **재현/확인 경로**:
  ```bash
  grep -rn "pointsEqual\|normalizePoints" backend/src/test   # 0건
  ```
  ```sql
  INSERT INTO ls_data_lbl(src_sn,lbl_type_cd,lbl_nm,point_cn,reg_user_no,reg_dt,lbl_id)
    VALUES (105,'BBOX','corrupt','not-a-json{{',1001,now(),NULL);
  -- 이후 PUT /v1/frames/105/labels 로 정상 좌표 재전송 → UPDATED 이력 생성 확인
  ```
- **영향**: 현재 결함 아님. 향후 `normalizePoints` 리팩터가 손상값을 '무변경'으로 오판하면 이력이 조용히 유실되는데 이를 잡을 테스트가 없다.
- **수정 방향(제안)**: `LabelServiceFullReplaceIntegrationTest` 에 `pointCn` 을 손상값(`"{{{"`)으로 덮어쓴 뒤 무변경 재저장 시 UPDATED 이력이 남는지 확인하는 케이스 1건 추가. ⚠ **구현하지 않는다.**

---

# C 클러스터 part3 (TC-LABEL-74~149) 2차 검증 결과

- 검증 일시: 2026-07-31 03:30~03:55 KST · backend `localhost:18081`(이미지 HEAD `ca3c712b` 재빌드본) · ai-server `:19300` · mock-server `:9400` · postgres `:5432`(스키마 `public`)
- 참조 데이터: `pipeline-drive.md` §3 (rawSn 126 완주/APPROVED · 132 검수대기 · 133 신고 OPEN · 129/130/131 파생 · 라벨마스터 9종)
- 대상: `C-marking-labeling.md` §C-2 의 `TC-LABEL-74`~`TC-LABEL-149` **66건**
  (번호 결번 81~89 는 카탈로그 자체에 존재하지 않음. `~~TC-LABEL-99~~` 는 **폐기 케이스라 집계 제외** — 라벨 보존 정책 반전(b0647c4c)으로 신고 경로의 스냅샷/삭제 분기가 소멸했고 대체는 TC-LABEL-124)
- 파일 수정 0건(본 결과 파일 1개만 신규). 빌드/테스트 실행 0건. **backend 컨테이너 재기동 0건.**

## 집계

| 판정 | 건수 |
|---|---:|
| PASS | **65** |
| PARTIAL | **1** |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **계(폐기 1건 제외)** | **66** |

- 근거 유형: **[실동작] 45건 · [정적]+테스트커버 21건**(bulkhead·TOCTOU·심링크 교체 등 런타임 강제 불가 항목).
- 실동작 검증 중 발생시킨 데이터 변경은 **전건 원복**했다(§검증 중 개입 참조). rawSn **126 라벨 22건 불변**, rawSn **133 신고 OPEN·`DE_IDENT_YN='F'` 불변** 확인.
- 신규 이슈 **2건**(LOW 2) — `C-ISSUE-41`(PORTAL_USER dead grant · 케이스 기대값 불성립) · `C-ISSUE-42`(412 OpenAPI 미선언).
  ⚠ 번호는 임무 지시(part3 = 41번부터)에 따른 것으로, **1차(2026-07-25) ISSUES.md 의 동명 `C-ISSUE-41`(YOLO 좌표 clamp, 이미 해소)과는 별개**다.

## 1차 이슈 해소 대조

| 1차 이슈 | 내용 | 2차 실측 | 판정 |
|---|---|---|---|
| C-ISSUE-21 | 라벨 full-replace 동시 저장 lost update(무경고 삭제) | `labelVersion` 낙관적 토큰 + 프레임 행 비관적 락(`lockAndReadLabelVersion`) 도입. stale 토큰 저장 **409** 실측, 미첨부는 200 하위호환, 무변경은 버전 미증가 | **해소** (TC-LABEL-110/111/112) |
| C-ISSUE-22 | 좌표 이미지 경계 초과 저장 무검증 | `FrameBoundsResolver` 실측 + `validateWithinBounds`. 999999 → **400**(`이미지=1920x1080`), 경계값 1920/1080 → 200, 측정 실패 프레임은 상한만 skip + WARN | **해소** (TC-LABEL-113~118) |
| C-ISSUE-23 | R7 손상 좌표 JSON fail-safe 전용 테스트 부재 | `backend/src/test` 전수 grep `pointsEqual|normalizePoints` → **여전히 0건** | **미해소(이월)** — UNCERTAINTIES 이월 목록과 동일 |
| C-ISSUE-24 | TASK_MODIFIED 발행 런타임 관측 불가(`CONTROL_NOTIFY_ENABLED=false`) | 현 형상 `CONTROL_NOTIFY_ENABLED=true`. pipeline-drive §1 #17 에서 `TASK_MODIFIED` mock 도달 실측 | **해소(환경)** |
| C-ISSUE-25 | 비활성 마스터 참조 시 프레임 저장 전면 409 차단 | `isNewLabelAssignment` 로 **신규 부여에만** USE_YN 강제. 기존 참조 유지 재전송 **200** 실측 / 신규·변경 부여 **409** 실측 | **해소** (TC-LABEL-119/120) |
| C-ISSUE-41(1차) | 실모델 YOLO 경계 음수 좌표로 온라인 오토라벨 전량 400 | `DetectionBoxNormalizer` 공용 규칙(clamp + 퇴화 스킵 + 형식위반만 400). 실동작 BBOX 6건·POLYGON 6건 정상 반환, 400 0건 | **해소** (TC-LABEL-136~139) |

## ★프레임 이미지 서빙 4경로 실측

> 라벨링 캔버스 백지·원본 유출·판정기 이원화 3대 실패모드를 겨냥한 반증 실측.

| # | 경로 | 역할 | `raw` 파라미터 | 기대 | 실측 | 심링크 방어 |
|:-:|---|---|---|---|---|---|
| 1 | `GET /v1/frames/{srcSn}/image` | WORKER(배정) | 미지정 | DEID 서빙 | **200 / Content-Length 247,266 = 비식별 파일 크기**(원본은 226,485) · `no-store` · `nosniff` | `serveFrame` → deid 분기는 `StorageSubtreePolicy.verifyDeidentifiedFile` → `v.path()`(realpath) → `openNoFollow` |
| 1b | 〃 | WORKER | `raw=true` | **무시·DEID 강제** | **200 / 247,266 (DEID)** — 원본 미유출 | 〃 |
| 1c | 〃 | REVIEWER | `raw=true` | 원본 허용 | **200 / 226,485 (RAW)** | 원본 분기도 `toRealPath()` 재검증 후 그 실경로를 `openNoFollow` |
| 1d | 〃 (파생 프레임 srcSn=71, 480P) | REVIEWER | 미지정 | 파생도 DEID 로 200(백지 아님) | **200 / 7,773** | 〃 |
| 1e | 〃 (파생 프레임) | REVIEWER | `raw=true` | 원본 픽셀 부재 | **404** `이미지 경로가 비어있습니다` | — |
| 2 | `GET /v1/frames/{srcSn}/deid-image` | WORKER(배정) | 없음 | 비식별 전용 200 | **200 / 247,266** · `Content-Disposition: inline; filename="frame_deid_66.jpg"`(srcSn+MIME 파생만) · `no-store` · `nosniff` | 단일 판정기 + `openNoFollow` |
| 2b | 〃 (deid 경로 null, srcSn=25) | WORKER | — | **원본 폴백 없음 404** | **404** `비식별 이미지 파일이 존재하지 않습니다` (같은 프레임의 `SRC_FILE_PATH_NM` 은 채워져 있음) | — |
| 3 | `GET /v1/videos/{rawSn}/frames/{frameNo}/image` | REVIEWER | 미지정/`raw=true` | DEID 기본·REVIEWER 원본 | **200 / 247,266** · `no-store` / `raw=true` → **226,485** | `FrameImageService.serveFrame` **동일 함수** |
| 3b | 〃 | WORKER | `raw=true` | 무시·DEID | **200 / 247,266** | 〃 |
| 4 | `GET /v1/portal/frames/{srcSn}/image` (F 담당, 규약 정합만 확인) | — | — | 같은 판정기·open·no-store | `PortalLabelService:459` `verifyDeidentifiedFile` · `:482` `FrameImageService.openNoFollow` · `:497` `noStore()` | 동일 규약 재사용 |

**판정기 단일화 확증**: 경로 1·1b~1e·3·3b 는 컨트롤러가 판정을 복제 보유하지 않고 `FrameImageService.serveFrame` 한 곳으로 수렴(`FrameImageController:89`, `VideoController:303`), 경로 2·4 는 `StorageSubtreePolicy.verifyDeidentifiedFile` 단일 판정기 + `FrameImageService.openNoFollow`(public static) 재사용. 아키텍처 가드 테스트 `architecture/FileServingLinkFollowGuardTest — 서빙_클래스는_링크추종_open_API를_쓰지_않는다` 가 회귀를 고정한다.

**신고 게이트 평가 순서**(rawSn 133 = `'F'`): 미배정 WORKER → **403**(파생 68) / 배정 WORKER → **412**(80). 인가가 항상 먼저이며 게이트가 프레임 존재 오라클이 되지 않는다.

## 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-LABEL-74 | POLYGON 박스별 실패 스킵 | PASS | [정적] `AutolabelOnlineService.java:321-348`(박스별 try/catch)·`:370-387`(buildPolygonMessage, `anyExcluded = anyMock \|\| skipped>0`) + 테스트 `AutolabelPolygonServiceTest:238 일부_박스_SAM실패시_성공분만_반환하고_스킵을_로깅한다`(:253 에서 비-mock 실패도 `POLYGON_PARTIAL_MOCK_MESSAGE` 고지 단언) | [실동작] srcSn=66 POLYGON 6검출/6반환·skip 0 → 스킵 분기 미발화 |
| TC-LABEL-75 | POLYGON bulkhead 429 즉시전파 | PASS | [정적] `:338-343`(TOO_MANY_REQUESTS 만 rethrow)·`:529-533` + 테스트 `AutolabelPolygonServiceTest:318 폴리곤_배치중_bulkhead429는_즉시_전파되고_삼켜지지_않는다` | 런타임 bulkhead 포화 강제 불가 |
| TC-LABEL-76 | POLYGON TOCTOU 중간 잠금/신고 | PASS | [정적] `:312`(루프 내 `requireNotBlocked`)·`:352`(마감 재확인) + 테스트 `AutolabelPolygonServiceTest:303 폴리곤_배치중_작업락걸리면_409로_차단한다` · `AutolabelGateWiringTest 폴리곤_루프중_게이트가_있으면_배치_도중_신고시_남은_박스는_전송되지_않는다` | 좌표 미반환 확인은 테스트 단언 |
| TC-LABEL-77 | YOLO bulkhead 초과 429 | PASS | [정적] `:499-503` + 테스트 `AutolabelOnlineServiceTest 온라인_AI경로_동시_초과요청시_bulkhead_거부_429_TOO_MANY_REQUESTS` | |
| TC-LABEL-78 | ai-server 호출 실패 502 | PASS | [정적] `:504-509`(`LogSanitizer` + 고정 문구 `YOLO 오토라벨 호출 실패`) + 테스트 `AutolabelOnlineServiceTest AI_실패시_502이며_inflight_락이_해제된다` | 스택·경로 미노출 |
| TC-LABEL-79 | AutolabelRequest conf 범위 | PASS | [실동작] `POST /v1/frames/66/autolabel {"confThreshold":0.9}` → **400** `인식 민감도는 0.80 이하여야 합니다` / `0.1` → **400** `0.25 이상` | `AutolabelRequest.java:39-40` |
| TC-LABEL-80 | classes 100개 초과 | PASS | [실동작] classes 101개 → **400** `클래스는 최대 100개까지 지정할 수 있습니다` | `AutolabelRequest.java:36` |
| TC-LABEL-90 | 신고 정상 — 라벨 보존 | PASS | [실동작] srcSn=53(rawSn 20029, 라벨 1건 사전 삽입) → **201** `rprtSn=6`. 라벨 **1→1 불변**, `LS_LABEL_VERSION` **0행**, `DE_IDENT_YN Y→F`, 작업락 LOCKED, 로그 `labelsPreserved=true privacyReset=1 privacyResetAudited=1` | 사후 전량 원복 |
| TC-LABEL-91 | 신고 reason 누락 | PASS | [실동작] `{"reason":""}` → **400**, `{}` → **400** (`reason: 신고 사유는 필수입니다`) | 서비스 백스톱 `:158-162` 정적 확인 |
| TC-LABEL-92 | 신고 reason 1000자 초과 | PASS | [실동작] 1001자 → **400** `신고 사유는 1000자 이하여야 합니다` | |
| TC-LABEL-93 | 신고 IDOR(srcSn 경로) | PASS | [실동작] WORKER(2001) → 미배정 srcSn=68 → **403** `본인에게 배정되지 않은 영상입니다` | `DeidentReportService.java:122` |
| TC-LABEL-94 | 신고 이미 잠금 | PASS | [실동작] srcSn=80·rawSn=133(잠김) → **409** `이미 비식별 재처리 중인 영상입니다`(두 진입점 동일) | 신고행 3건 불변 |
| TC-LABEL-95 | 신고 PII TOCTOU(FOR UPDATE) | PASS | [정적] `:178-180` `findByRawSnForUpdate` 로 부모 RAW 잠금 조회 + 테스트 `webhook/AugmentDeidentConcurrencyIT` | 동시 증강 콜백 직렬화 |
| TC-LABEL-96 | 신고 동시 락 유니크 409 | PASS | [정적] `:237-241` `DataIntegrityViolationException`→409 + 테스트 `DeidentReportServiceTest 동시_신고_unique_위반시_409` | |
| TC-LABEL-97 | 신고 개인정보 3필드 리셋 | PASS | [실동작] srcSn=53 `anony/psdo/prvc = Y,N,Y` → 신고 후 **전부 NULL**, 대상은 해당 rawSn 프레임만 | `:218-219` |
| TC-LABEL-98 | 신고 APPROVED→TASK_MODIFIED(META_UPDATED) | PASS | [정적] `:230-233` `ChangeType.META_UPDATED` + 테스트 `DeidentReportServiceTest:408·:504` 가 `META_UPDATED` 단언(구 `LABEL_DELETED` 부재) | APPROVED 영상 실신고는 rawSn 126 파괴 위험으로 미수행 |
| TC-LABEL-100 | resolve 미인증 | PASS | [실동작] 토큰 없이 `POST /v1/deident-reports/3/resolve` → **401** | 필터 단계 차단 + 서비스 백스톱 `:354-356` |
| TC-LABEL-101 | resolve 신고 미존재 | PASS | [실동작] rprtSn=999999 → **404** `신고를 찾을 수 없습니다` | |
| TC-LABEL-102 | resolve IDOR | PASS | [실동작] WORKER(2001) → rprtSn=4(rawSn 144, 미배정) → **403** | `:361` verifyRawAccess |
| TC-LABEL-103 | resolve OPEN 아님 | PASS | [실동작] rprtSn=4(RESOLVED) REVIEWER → **409** `이미 처리된 신고입니다` | |
| TC-LABEL-104 | resolve 산출물 미검증 gate | PASS | [실동작] rprtSn=3 REVIEWER → **409** `비식별 산출물이 확인되지 않습니다…`, 이후 DB 확인 `report=OPEN` · `DE_IDENT_YN='F'` · 작업락 LOCKED 유지(fail-closed). WARN `resolve blocked — deident artifact not verified rawSn=133`. [정적] 무결성 판정은 `:553 DeidentArtifactIntegrity.isValidVideoArtifact` **단일 지점** + 테스트 `18바이트_스텁으로는_비식별_신고가_해제되지_않는다` | |
| TC-LABEL-105 | resolve 시간조건 | PASS | [실동작] 신고 03:00:33 vs procLog `rspns_dt` 02:57:32 · 비식별 mp4 mtime 02:57:26 → 두 조건 모두 미충족 → **409**. [정적] `:504` 60초 스큐(mtime 전용)·`:565` procLog 엄격 비교 + 테스트 `신고이전_비식별본만_존재시_resolve_거부` / `신고이후_파일교체시_resolve_성공` | |
| TC-LABEL-106 | resolve 정상 + `'F'→'Y'` 복원 | PASS | [실동작·간접] rawSn 144 의 rprtSn 4·5 → `report_stts_cd=RESOLVED`, `ls_auth_work_lock` **RELEASED / rmv_rsn=MANUAL_DEIDENT_DONE**, `ls_data_raw(144).de_ident_yn='Y'`. [정적] `:374-391`(FOR UPDATE 하 복원) + 테스트 `비식별파일_존재시_resolve성공_deIdntfYn_Y복원_마킹게이트_통과` | 해당 행은 **동시 진행 중인 다른 담당 에이전트의 드라이브 산출물**을 DB 로 확인한 것(본 검증은 rawSn 133 을 OPEN 으로 보존해야 하므로 자체 성공 resolve 미수행) |
| TC-LABEL-107 | resolve 배치상태 역행 금지 | PASS | [실동작·간접] 위 rawSn 144 는 resolve 후에도 `DATA_STTS_CD=FAILED` 유지(되감김 없음). [정적] `:384-391` 주석·구현이 `markDeidentified("Y")` 만 수행 + 테스트 `APPROVED_영상_신고_해소시_배치단계가_되감기지_않는다` | CWE-664 |
| TC-LABEL-108 | listReports status allowlist | PASS | [실동작] `?status=X` → **400**, `?status=resolved`(소문자) → **400**. 메시지 접두 `list.status:` 로 **컨트롤러 `@Pattern` 이 먼저** 발화함을 확인(서비스 `toUpperCase` 관용은 도달 불가) | `:426-438` |
| TC-LABEL-109 | listReports 기본 OPEN | PASS | [실동작] status 미지정 → `totalElements=1`, content = rprtSn 3(OPEN)만. RESOLVED 2건(4·5) 미노출 | `:427-429` |
| TC-LABEL-110 | labelVersion 불일치 409 | PASS | [실동작] srcSn=77 현재 ver=1 에 `labelVersion:0` 전송 → **409** `다른 사용자가 먼저 저장했습니다…`, 라벨 미변경 | `LabelService.java:457-465` |
| TC-LABEL-111 | labelVersion 미첨부 = 검사 skip | PASS | [실동작] labelVersion 없이 신규 1건 저장 → **200**, `LS_DATA_SRC.LBL_VER 0→1` | 하위호환 |
| TC-LABEL-112 | 무변경 저장은 버전 미증가 | PASS | [실동작] 동일 세트 재전송(`[[10,10],[100,200]]` ↔ 저장값 `[[10.0,10.0],[100.0,200.0]]`) → 200, `LBL_VER` **1 유지**. `pointCn` 수치 정규화 비교가 실제로 동작함을 함께 확증 | `:400-406` |
| TC-LABEL-113 | 좌표 상한 초과 신규 라벨 400 | PASS | [실동작] `[[999999,888888],…]` → **400** `좌표가 이미지 경계를 벗어났습니다 (x=999999.0, y=888888.0, 이미지=1920x1080)` — 클램프 아님 | ★3 확정 정책 정합 |
| TC-LABEL-114 | 경계값 x==width 허용 | PASS | [실동작] `[[1900,1000],[1920,1080]]` → **200** 저장 | `:768` `x > width` |
| TC-LABEL-115 | 레거시 out-of-bounds 무변경 재저장 허용 | PASS | [실동작] DB 로 기존 라벨 좌표를 `[[1900,1000],[5000,4000]]` 로 만든 뒤 **동일 좌표 재전송 → 200**(프레임 전체 저장 차단 회귀 없음) | `:330-335` |
| TC-LABEL-116 | 기존 라벨을 경계 밖으로 이동 → 400 | PASS | [실동작] 경계 안 라벨을 `[…,[5000,4000]]` 로 변경 → **400** / 위 레거시 라벨을 `[…,[6000,4000]]` 로 **변경**해도 **400** | `:333-335` |
| TC-LABEL-117 | 치수 측정 실패 시 상한만 skip | PASS | [실동작] srcSn=25(이미지 파일 부재) 에 `[[500000,400000],[600000,500000]]` → **200 저장** + WARN `[Label] frame image path unresolved — coordinate upper-bound check skipped srcSn=25 cause=CustomException`. [정적] 메트릭 `label.bounds.skipped{reason}` 계상은 `FrameBoundsResolver:103/111/118` | actuator 는 인증 필요로 메트릭 값 직접 조회 불가 |
| TC-LABEL-118 | SKELETON v=0 키포인트 상한 제외 | PASS | [실동작] 17점 중 `[99999,99999,**0**]` 포함 → **200**(제외) / 같은 좌표를 `v=2` 로 바꾸면 → **400** `이미지=1920x1080` | `:764-767`·`:777-780` |
| TC-LABEL-119 | 비활성 마스터 기존 참조 유지 저장 허용 | PASS | [실동작] `LS_LABEL(37, USE_YN='N')` 참조 라벨을 DB 로 만든 뒤 `id`+동일 `labelId=37` 재전송 + 같은 프레임의 다른 라벨 좌표 수정 → **200** | `:685-695` |
| TC-LABEL-120 | 비활성 마스터를 기존 라벨에 새로 부여 → 409 | PASS | [실동작] `id` 지정 + `labelId` 를 37 로 **변경** → **409** `사용 중지된 라벨입니다: labelId=37` / 신규(id=null)에 37 부여도 **409** | `:674-677` — "id 붙이면 통과" 우회 차단 확인 |
| TC-LABEL-121 | 신고 구간 라벨 조회 412 | PASS | [실동작] srcSn=80(rawSn 133 `'F'`) — WORKER **412**, REVIEWER **412**(역할 무관). 대조군 srcSn=66 → 200 | `LabelService.java:193`(인가 `:188` 이후) |
| TC-LABEL-122 | 신고 구간 라벨 이력 412 | PASS | [실동작] `GET /v1/frames/80/label-history` — WORKER **412**, REVIEWER **412** | `LabelService.java:482`(인가 `:477` 이후) |
| TC-LABEL-123 | 게이트 판정 = 자기 rawSn 행 하나 | PASS | [정적] `DeidentReportGate.java:66-71` 는 `findDeIdntfYnByRawSn(rawSn)` **단일 행 projection** 이며 `ORGNL_RAW_SN` 을 참조하지 않음(`:23-47` 폐기 사유 명시) + 테스트 `LabelAccessGuardDeidentGateTest ★원본이_신고중이어도_파생영상_프레임은_열려있다` / `파생영상도_자기행이_F면_차단된다`. [실동작] 파생 프레임 68 라벨 조회 **200** | 현 DB 에 "부모 `'F'` × 파생 `'Y'`" 조합이 없어(133 은 파생 없음, 129~131 의 부모 126 은 `'Y'`) 실동작은 부분. **파생 경유 열람은 확정 정책의 귀결 — 결함 아님** |
| TC-LABEL-124 | 신고 후 라벨 무변경 확인 | PASS | [실동작] rawSn 133 프레임(78·80·82·84·86·88) 라벨 **28건 유지**, `LS_LABEL_VERSION` **0행**. 20029 재현에서도 라벨 1→1·스냅샷 0 | `:197-205` |
| TC-LABEL-125 | 개인정보 리셋 행 단위 감사 | PASS | [실동작] srcSn=53 신고 시 `LS_DATA_LBL_HSTRY` **1행 신규**: `chg_dtl_cn={"event":"PRIVACY_META_RESET","deidentReportSn":6,"changes":[]}`, `add/mdfcn/del_cnt=0`, `reg_id=2001`(actor)·`reg_dt` 기록 → 라벨 델타 0건이라 `V_COMPLETED_LABEL_CHANGE` V139 필터로 미노출 | `:218-226` |
| TC-LABEL-126 | resolve 후 게이트 자동 해제 + 보존 라벨 재사용 | PASS | [정적] `:387-391` `'F'→'Y'` 복원만으로 `LabelAccessGuard:135-141` 게이트가 열림(별도 복원 API 없음) + 테스트 `DeidentReportLabelPreservationIT 비식별_신고_상태에서는_라벨_조회가_차단되고_resolve_후에는_기존_라벨이_그대로_조회된다` · `DeidentReportGateCoverageIT resolve_후에는_위_경로_전부가_다시_열리고_라벨이_그대로다` | 실동작 미수행 — rawSn 133 을 OPEN 으로 보존하라는 임무 제약 |
| TC-LABEL-127 | 마킹 단계 rawSn 신고 정상 | PASS | [정적] `DeidentReportController.java:122`(`POST /v1/videos/{rawSn}/deident-report`)·`DeidentReportService.java:147-155` → 부수효과는 `doReport` 공용 본체(`:169-257`) 로 수렴, 통지 `srcSn=null` + 테스트 `마킹단계_rawSn_신고시_작업락과_F전이와_개인정보리셋이_srcSn경로와_동일하게_수행된다`. [실동작] rawSn 경로의 409/403/412 계열은 전건 실측(TC-94/128/129/130) | 201 정상 접수 실동작은 srcSn 경로로만 수행(20029) |
| TC-LABEL-128 | rawSn 신고 — 파생영상 412 | PASS | [실동작] `POST /v1/videos/129/deident-report`(REVIEWER) → **412**, 본문 = `이 영상은 원본 영상의 비식별 결과를 복사해 만든 파생영상(증강·해상도 변환본)이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다` — **부모 rawSn 미노출·원본 유도 없음**. 신고행 3건 불변. WARN 감사 `rejected — derivative video is out of the report workflow rawSn=129 orgnlRawSn=126 reason=derivative test`(사유 sanitize 후 기록) | `:183`·`:295-306` |
| TC-LABEL-129 | rawSn 신고 — 비식별 미수행 412 | PASS | [실동작] rawSn 20030 을 일시 `DE_IDENT_YN='N'` 으로 두고 요청 → **412** `아직 비식별 처리가 완료되지 않은 영상입니다…`(즉시 `'Y'` 원복). 반면 이미 `'F'` 인 133 은 통과해 기존 **409** 경로 유지 | `:186`·`:329-337`, 판정 원천 `LsDataRaw.hasDeidentArtifact()` |
| TC-LABEL-130 | rawSn 신고 인가 축 = verifyRawAccess | PASS | [실동작] WORKER(2001) → 미배정 rawSn 129 → **403** `본인에게 배정되지 않은 영상입니다`(파생 412 보다 **먼저** 평가되어 존재 여부 미노출) | `:151`·`LabelAccessGuard.java:83-100` |
| TC-LABEL-131 | srcSn 경로도 파생/미수행 412 | PASS | [실동작] 파생 srcSn=68 → **412**(파생 문구) / 비식별 미수행 srcSn=54 → **412**(미수행 문구). 두 진입점 응답 동일 | `:125`·`:169-186` |
| TC-LABEL-132 | resolve 시 DeidentGateReopenedEvent 항상 발행 | PASS | [정적] `:487-495` — 승인 여부 무관 발행, `DeidentReportResolvedEvent` 만 조건부 + 테스트 `미승인_영상_해소시_게이트_재개방만_발행되고_export_재산출은_없다 — VLM 보류 재개 경로 보존` | |
| TC-LABEL-133 | DeidentReportResolvedEvent 는 APPROVED만 | PASS | [정적] `:492-494` `isReviewApproved` 가드 + 테스트 `resolve시_승인영상은_자기_rawSn으로만_export가_재트리거된다` | |
| TC-LABEL-134 | 신고·해소 시 스트림 메타 캐시 무효화 | PASS | [실동작] srcSn=53 신고 직후 로그 `[StreamMetaCache] evicted rawSn=20029` **단건**(다른 rawSn 무접촉). [정적] `:248`(신고)·`:395`(해소) | 파생 캐시 미접촉 = 확정 정책 정합 |
| TC-LABEL-135 | 오토라벨 신고 구간 차단 순서 | PASS | [실동작] ①잠긴 영상(srcSn=80/rawSn 133) → **409** `작업이 잠긴 영상입니다` ②락 없이 `'F'`(rawSn 132 일시 `'F'`, srcSn=79) → **412** `비식별 재처리 대기 중인 영상은 오토라벨을 실행할 수 없습니다`(즉시 `'Y'` 원복) | `AutolabelOnlineService.java:411-419` — 작업락 판정 선행 확인 |
| TC-LABEL-136 | 검출 좌표 상한 clamp | PASS | [정적] `DetectionBoxNormalizer.java:59-68`(`clamp(v, upperBound)`) + 테스트 `DetectionBoxNormalizerTest 이미지_상한_초과좌표는_이미지_경계로_clamp된다` · `AutolabelOnlineServiceTest ai_응답_좌표_이미지_상한_초과시_이미지_경계로_clamp된다` | 실모델이 in-bounds 만 출력해 런타임 강제 불가 |
| TC-LABEL-137 | 검출 좌표 음수 clamp | PASS | [정적] 동상(하한 0 clamp) + 테스트 `경계밖_음수좌표는_0으로_clamp되어_반환된다` · `ai_응답_좌표_음수면_거부하지않고_0으로_clamp해_반환한다`. [실동작] srcSn=66 BBOX 6건 정상 반환, 400 **0건**(구 동작이면 전량 400) | ★3 확정 정책 정합 |
| TC-LABEL-138 | 퇴화 박스 검출 단위 스킵 | PASS | [정적] `:65-67` `Optional.empty()` + `AutolabelOnlineService.java:571-575` WARN 후 `continue` + 테스트 `clamp후_퇴화한_박스는_예외가_아니라_스킵신호를_반환한다` · `이미지_전체_밖_박스는_해당_검출만_스킵되고_나머지는_반환된다` | 400 아님 |
| TC-LABEL-139 | bounds 미상 시 하한만 clamp | PASS | [정적] `:71-77` `Double.MAX_VALUE` + `AutolabelOnlineService.java:556-561`(`resolve(...).orElse(null)`) + 테스트 `이미지_실측_해상도를_모르면_상한없이_음수만_0으로_clamp된다` · `프레임_해상도_측정실패시에도_음수는_clamp되어_검출이_반환된다` | |
| TC-LABEL-140 | SAM 폴리곤은 clamp 미적용(거부 유지) | PASS | [정적] `AutolabelOnlineService.java:581-617` — 음수/비유한 거부하되 호출부가 박스별 try/catch 로 **그 박스만 스킵**(부분 성공). 비대칭 근거는 javadoc `:585-598` 에 명문화 | ★3 확정 정책 정합 — 통일 제안 없음 |
| TC-LABEL-141 | `/deid-image` 정상 200 | PASS | [실동작] WORKER/srcSn=66 → **200** `Content-Type: image/jpeg` · `Content-Length: 247266` · `X-Content-Type-Options: nosniff` · `Content-Disposition: inline; filename="frame_deid_66.jpg"`(파일명 유래 문자열 미사용 — CWE-113) | 근거 라인 드리프트(§근거 드리프트) |
| TC-LABEL-142 | `/deid-image` 원본 폴백 없음 → 404 | PASS | [실동작] srcSn=25 (`DE_IDNTF_SRC_FILE_PATH_NM=null`, `SRC_FILE_PATH_NM` 은 존재) → **404** `비식별 이미지 파일이 존재하지 않습니다`. [정적] `FrameImageLookupService.deidPathBySrcSn` 이 원본 경로를 **트랜잭션 밖으로 내보내지도 않음** | verdict BLANK/MISSING/NOT_REGULAR_FILE/REALPATH_FAILED → 404 수렴 확인 |
| TC-LABEL-143 | `/deid-image` 권한 범위가 `/image` 와 다름 | **PARTIAL** | [실동작] PORTAL_USER 토큰: `/deid-image` **403** (기대 일치) · `/image` **도 403** (기대 "통과" 불일치, 본문 `권한이 없습니다`). [정적] `@PreAuthorize` 차이는 실재(`FrameImageController:117` vs `:80`) 하나 `SecurityConfig` 의 `/v1/**` = `CHANNEL_INTERNAL ∧ (REVIEWER∨WORKER)` 가 컨트롤러 도달 전 차단 → `/image` 의 `PORTAL_USER` 허용은 **dead grant** | → **C-ISSUE-41** |
| TC-LABEL-144 | `/deid-image` 신고 구간 412 + 평가 순서 | PASS | [실동작] 미배정 WORKER(파생 srcSn=68) → **403** / 배정 WORKER(신고 srcSn=80) → **412**. 인가→게이트→경로해석 순서 확인 | `FrameImageService.serveDeidentified` + `FrameImageLookupService:98-102` |
| TC-LABEL-145 | `/deid-image` 심링크 TOCTOU 차단 | PASS | [정적] `FrameImageService.java:313-320`(판정 실경로를 `openNoFollow` 로 open, IOException → 404 fail-closed)·`:362-366`(`readAttributes`/`newInputStream` 모두 `NOFOLLOW_LINKS`) + 테스트 `FrameImageServingHardeningTest openNoFollow_대상이_심링크면_열지_않고_실패한다_TOCTOU_fail_closed` · `FrameDeidImageControllerTest H5_DeidImage_비식별경로가_원본프레임_심볼릭링크면_403` · `architecture/FileServingLinkFollowGuardTest` | 운영 스토리지에 심링크 생성은 파괴적이라 실동작 미수행 |
| TC-LABEL-146 | `/deid-image` 비식별 서브트리 밖 경로 403 | PASS | [정적] `FrameImageService.java:291-301` 이 `StorageSubtreePolicy.verifyDeidentifiedFile` **단일 판정기**에 위임(default verdict → FORBIDDEN). 서비스/컨트롤러 어디에도 재구현 없음 + 테스트 `DeidImage_비식별_서브트리_밖_경로면_403` · `FrameDeidImageSameBaseTest H4_…raw와_deid_base가_동일해도_원본프레임_서브트리는_403` | |
| TC-LABEL-147 | `/deid-image` 응답 `Cache-Control: no-store` | PASS | [실동작] 응답 헤더 `Cache-Control: no-store` 확인 + 테스트 `FrameDeidImageControllerTest:140` | `FrameImageService.java:327` |
| TC-LABEL-148 | `/v1/frames/{srcSn}/image` 응답 `no-store` | PASS | [실동작] `Cache-Control: no-store` 확인(WORKER 200) + 테스트 `FrameImageControllerTest:122` | ⚠ 케이스 **기대결과 서술**("이 경로는 비식별 판정 없이 **원본 프레임**을 서빙") 은 현 구현과 불일치 — 이 경로는 기본 DEID 서빙이며 `FrameImageLookupService.bySrcSn` 에서 게이트를 평가한다(§근거 드리프트). 단언(`no-store`) 자체는 성립 |
| TC-LABEL-149 | `/v1/videos/{rawSn}/frames/{frameNo}/image` 응답 `no-store` | PASS | [실동작] REVIEWER 기본·`raw=true` 양쪽 **200 + `Cache-Control: no-store`**. IDOR 가드(`VideoController:302 verifyRawAccess`) → 게이트(`FrameImageLookupService:73`) → 서빙 순서 정적 확인, 신고 영상 133 은 **412** 실측 | `no-store` 세팅 위치는 `FrameImageService.java:252`(§근거 드리프트) |

> 폐기 각주: `~~TC-LABEL-99~~`(신고 라벨 0건 스킵)는 **[폐기 2026-07-30]** 로 집계에서 제외했다. 신고 경로에 라벨 스냅샷/삭제 분기가 존재하지 않음을 `DeidentReportService.java:197-205`(설명 주석만 남고 코드 부재)로 재확인했다.

## 근거 드리프트

> 판정에는 영향이 없으나 카탈로그의 `file:line` 이 현 HEAD(`ca3c712b`)와 어긋난 항목. 원인은 07-30 커밋 `46f47cee`/`476bc91a`(파생영상 캔버스 백지 해소 · 서빙 경로 하드닝)로 `FrameImageService` 에 `serveBySrcSn`/`serveFrame` 가 삽입되면서 이후 라인이 약 +60~70 밀린 것이다.

| TC | 카탈로그 근거 | 현 HEAD 실제 | 비고 |
|---|---|---|---|
| TC-LABEL-141 | `FrameImageController.java:162-171` · `FrameImageService.java:214-269` | `FrameImageController.java:115-122` · `FrameImageService.java:283-334` | 메서드 자체는 동일(`getDeidImage`/`serveDeidentified`) |
| TC-LABEL-142 | `FrameImageService.java:223-234` | `FrameImageService.java:290-301` | verdict→404 switch |
| TC-LABEL-143 | `FrameImageController.java:164 vs :90` | `FrameImageController.java:117 vs :80` | @PreAuthorize 두 지점 |
| TC-LABEL-144 | `FrameImageService.java:214-226` | `FrameImageService.java:283-293` (+ 인가·게이트 실체는 `FrameImageLookupService.java:98-102`) | 조회/게이트가 별도 빈으로 분리됨(W3) — 근거 파일 자체가 늘어남 |
| TC-LABEL-145 | `FrameImageService.java:246-256` | `FrameImageService.java:313-320`(open) · `:362-366`(`openNoFollow` 본체) | |
| TC-LABEL-146 | `FrameImageService.java:224-233` | `FrameImageService.java:291-301` | |
| TC-LABEL-147 | `FrameImageService.java:263` | `FrameImageService.java:327` | |
| TC-LABEL-148 | `FrameImageController.java:132` | `FrameImageService.java:252` — **컨트롤러에 헤더 세팅 코드가 없다**(전량 서비스 위임). 더불어 기대결과 서술 "비식별 판정 없이 원본 프레임을 서빙" 은 폐기된 구 구현 기준 | **서술 드리프트**(단언은 유효) |
| TC-LABEL-149 | `VideoController.java:277-295` · `FrameImageService.java:186` | `VideoController.java:285-304`(277~284 는 @Operation 본문) · `FrameImageService.java:252` | |

> 위 9건 외 **TC-LABEL-74~80·90~109·110~140 의 근거 `file:line` 은 전건 정확**했다(`AutolabelOnlineService`·`DeidentReportService`·`LabelService`·`DetectionBoxNormalizer`·`DeidentReportGate`·`LabelAccessGuard`·`FrameBoundsResolver`·`AutolabelRequest`·`LabelBulkUpsertRequest` 실측 대조).

## 이슈 상세

### [C-ISSUE-41] TC-LABEL-143 — `/v1/frames/{srcSn}/image` 의 `PORTAL_USER` 허용은 도달 불가한 dead grant (케이스 기대값 "통과" 불성립)

> ⚠ 번호는 임무 지시(part3 = 41번부터)를 따른 것이며, **1차 ISSUES.md 의 `C-ISSUE-41`(YOLO 좌표 clamp — 이미 해소)과는 다른 이슈**다.

- **심각도**: LOW (보안 위험 없음 — 오히려 더 좁게 닫혀 있음. 계약·문서 정합 문제)
- **기대 동작(기대효과)**: 카탈로그 TC-LABEL-143 은 "두 형제 경로의 역할 집합이 **의도적으로 다름**" 을 검증 대상으로 삼아 `/deid-image` = 403 / `/image` = **통과**를 기대한다.
- **현재 동작**: PORTAL 채널 토큰으로 두 경로 모두 **403**(`{"errorCode":"FORBIDDEN","message":"권한이 없습니다."}`).
  - `FrameImageController.java:80` 은 실제로 `@PreAuthorize("hasAnyRole('REVIEWER','WORKER','PORTAL_USER')")` 이나,
  - `SecurityConfig.java:147-153` 이 그보다 앞서 `/v1/**` 에 대해 `CHANNEL_INTERNAL ∧ (ROLE_REVIEWER ∨ ROLE_WORKER ∨ STREAM_SIGNED)` 를 요구한다. PORTAL 토큰은 `CHANNEL_PORTAL` 이라 **컨트롤러에 도달하지 못한다**.
  - 설령 채널 검사를 통과하더라도 `LabelAccessGuard.verifyAndGet:59-71` 이 REVIEWER/WORKER 외 역할을 `FORBIDDEN` 으로 끝낸다(2중 차단).
  - 즉 `/image` 의 `PORTAL_USER` 항목은 **어떤 경로로도 실행되지 않는 dead grant** 이며, 두 형제 경로의 관측 가능한 역할 집합은 **동일**하다.
- **재현/확인 경로**:
  ```bash
  PT=$(curl -s -X POST $B/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"PORTAL_USER","channel":"PORTAL"}' | jq -r .data.token)
  curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $PT" $B/v1/frames/66/image      # 403
  curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $PT" $B/v1/frames/66/deid-image # 403
  ```
  테스트 자산에도 `/deid-image` 의 PORTAL 403 케이스(`FrameDeidImageControllerTest DeidImage_PORTAL_USER_채널은_내부_프레임_접근_403`)만 있고 `/image` 의 PORTAL 통과를 단언하는 테스트는 **0건**이다.
- **영향**: 보안상 위험은 없다(포털은 ADR-013 대로 `/v1/portal/**` 전용 경로를 쓰며 실제로 닫혀 있다). 다만 ①`@PreAuthorize` 가 실제 정책보다 넓게 적혀 있어 **읽는 사람이 포털 접근을 허용된 것으로 오해**할 수 있고 ②채널 격리가 나중에 완화되면 그 순간 내부 프레임이 포털에 열린다(잠재 fail-open 표면) ③카탈로그 기대값이 영구히 성립하지 않는다.
- **수정 방향(제안)**: (a) `FrameImageController:80` 의 `@PreAuthorize` 에서 `PORTAL_USER` 를 제거해 `/deid-image` 와 동일하게 맞추고 (b) TC-LABEL-143 의 기대결과를 "두 경로 모두 PORTAL 403 — 포털은 `/v1/portal/**` 전용" 으로 정정. ⚠ **구현하지 않는다.**

### [C-ISSUE-42] TC-LABEL-149 — `GET /v1/videos/{rawSn}/frames/{frameNo}/image` 의 412(신고 게이트)가 OpenAPI 에 선언되어 있지 않다

- **심각도**: LOW (문서·계약 갭. 런타임 동작은 정상)
- **기대 동작(기대효과)**: 형제 경로(`FrameImageController` 의 `/image`·`/deid-image`)와 동일하게, 비식별 누락 신고 구간에서 반환되는 **412** 가 API 문서(@ApiResponses)에 선언되어 FE·관제가 분기를 인지할 수 있어야 한다.
- **현재 동작**: `VideoController.java:279-284` 의 `@ApiResponses` 는 200/401/403/404 만 선언한다. 그러나 실제로는 신고 구간에서 **412** 를 반환한다(실측):
  ```
  GET /v1/videos/133/frames/1/image  (WORKER, rawSn 133 = DE_IDENT_YN 'F')
  → 412 {"errorCode":"PRECONDITION_FAILED","message":"비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다."}
  ```
  같은 게이트를 쓰는 `FrameImageController` 는 `/image`(:77)·`/deid-image`(:113) 양쪽에 412 를 선언하고 있어 **형제 경로 간 문서 규약만 어긋난 상태**다.
- **재현/확인 경로**: 위 curl. 또는 `/swagger-ui` 에서 두 컨트롤러의 응답 코드 목록 비교.
- **영향**: FE/외부 소비자가 412 를 미문서화 오류로 취급해 일반 에러 처리로 흘릴 수 있다(신고 구간 전용 안내 UI 분기 누락). 보안 영향 없음.
- **수정 방향(제안)**: `VideoController.getFrameImage` 의 `@ApiResponses` 에 412 항목 추가(문구는 `FrameImageController:77` 과 동일하게). 부수적으로 같은 블록의 404 설명("비식별 미완료")도 412 와 구분되도록 정리. ⚠ **구현하지 않는다.**

---

## 검증 중 개입 및 원복 (투명성)

> 실동작 검증을 위해 만든 변경은 전부 **본 검증이 만든 데이터에 한정**되며 종료 시점에 원복했다. rawSn 126·133 은 임무 제약대로 보호했다.

| # | 대상 | 무엇을 | 왜 | 원복 |
|---|---|---|---|---|
| 1 | srcSn 77 (rawSn 132, 라벨 0건 프레임) | 라벨 저장/수정 API 로 라벨 생성·수정, 일부 좌표·`LBL_ID` 를 DB 로 조작(레거시 out-of-bounds·비활성 마스터 참조 상황 재현) | TC-110~120 은 "기존 라벨이 이미 경계 밖" 등 **정상 API 로는 만들 수 없는 상태**가 전제 | `PUT {"items":[]}` 로 전량 삭제 → 라벨 **0건**, `LS_DATA_LBL_HSTRY` 삭제, `LBL_VER=0` 복원 |
| 2 | srcSn 25 (rawSn 20001 시드) | 큰 좌표 라벨 1건 생성 | TC-117(치수 측정 실패 프레임) | 동상 — 라벨 0건 복원 |
| 3 | rawSn 20029 / srcSn 53 (시드) | 개인정보 3필드 `Y/N/Y` 세팅 후 **실제 신고 접수(201)** | TC-90/97/125/134 는 신고 정상 경로가 필요. 126·132·133 을 쓸 수 없어 시드 영상 사용 | 신고행·작업락·이력 삭제, `DE_IDENT_YN='Y'` 복원, 라벨·`LBL_VER` 복원 — **완전 원복 확인** |
| 4 | rawSn 20030 | `DE_IDENT_YN` `'Y'→'N'` 일시 | TC-129(비식별 미수행 412)는 `'N'` 영상이 DB 에 하나도 없어 재현 불가 | 즉시 `'Y'` 복원 |
| 5 | rawSn 132 | `DE_IDENT_YN` `'Y'→'F'` 일시(수 초) | TC-135 의 "락 없이 `'F'`" 분기(412)는 락 없는 `'F'` 영상이 없어 재현 불가 | 즉시 `'Y'` 복원 |

**개입하지 않은 것(명시)**: rawSn **126**(라벨 22건·export·버전스냅샷 무접촉 — 온라인 오토라벨은 미저장 경로라 srcSn 66 라벨 9건 **불변** 확인), rawSn **133**(신고 rprtSn=3 `OPEN` · `DE_IDENT_YN='F'` · 라벨 28건 유지), `LS_LABEL` 마스터(비활성 라벨 `lbl_id=37` 은 **다른 에이전트가 만든 것**으로 읽기만 함), 파생영상 129/130/131, `LS_DATASET_EXPORT`·`LS_LABEL_VERSION`·`LS_CONTROL_NOTIFY_FALLBACK`.

## 부기 — 다른 에이전트와의 동시 실행 관측

- 검증 중 rawSn **134~155**(`DEV-CLIP-92xx/95xx`)와 신고 rprtSn **4·5**(rawSn 144, RESOLVED)가 다른 담당의 드라이브로 생성돼 있었다. TC-LABEL-106/107 은 이 rawSn 144 산출물을 **DB 로 사후 확인**한 것이며 본 검증이 만든 것이 아니다(성공 resolve 를 직접 수행하면 rawSn 133 의 OPEN 보존 제약과 충돌하므로 의도적으로 회피).
- `LS_LABEL` 에 `lbl_id=37 (ZZTEST-C2, USE_YN='N')` 가 존재해 TC-119/120 의 비활성 마스터 픽스처로 **읽기만** 사용했다.

---

# C 클러스터 part4 (C-3·C-4) 2차 검증 결과

> 대상: `docs/test-cases/C-marking-labeling.md` 의 **C-3. TC-SAM2 (33건)** + **C-4. TC-KEYPOINT (13건)** = **46건**
> 환경: backend `localhost:18081`(HEAD `ca3c712b` 재빌드본) · ai-server `:19300`(SAM2 **실가중치 실추론**, `mock=false/source=model`) · postgres `:5432` 스키마 `public`
> 검증 일시: 2026-07-31 03:4x~04:0x KST
> ⚠ `~~취소선~~` 폐기 행 **0건** — C-3·C-4 에는 폐기 케이스가 없어 33+13 전부 검증 대상.

## 사용/생성 데이터 (파괴적 변경 회피 기록)

| 용도 | 대상 | 비고 |
|---|---|---|
| SAM2 분할/추적 정상 경로 | **rawSn 132** (srcSn 77·79·81·83·85·87, 6프레임, `DE_IDENT_YN=Y`, WORKER 2001 배정) | segment/track 은 **미저장 프록시**라 DB 무변경. 검증 전후 `ls_data_lbl` **28행 동일** |
| 신고 구간 412 | **rawSn 133** (srcSn 78, `DE_IDENT_YN='F'`) | 읽기만 — **OPEN 상태 유지**(변경 없음) |
| 비식별 우선 폴백(파생) | **rawSn 130** (해상도 720P 파생, srcSn 74·75, `SRC_FILE_PATH_NM=null`) | 미저장. 라벨 21행 불변 |
| IDOR | WORKER **2002** 토큰(미배정) / PORTAL_USER 토큰 | |
| **키포인트 저장(쓰기 발생)** | **rawSn 143 / srcSn 111** (검증 착수 시 라벨 **0건**, `ASSIGNED`) | TC-KEYPOINT-06/13 로 SKELETON 라벨 **1건 신규 생성(lblSn 520)** + `ls_data_lbl_hstry` 1행. **원복하지 않음**(원복 저장도 이력을 남기므로 추가 오염). rawSn 143 은 B-part2 생성분 |
| **무변경 확인** | rawSn **126 · 129 · 133** | 라벨/상태/신고 전부 그대로. 126 프레임(65~67)에는 어떤 요청도 보내지 않음 |

## 집계

| 구분 | 검증 | PASS | PARTIAL | FAIL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| C-3 TC-SAM2 | 33 | 31 | 1 | 1 | 0 | 0 | 0 |
| C-4 TC-KEYPOINT | 13 | 13 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **46** | **44** | **1** | **1** | **0** | **0** | **0** |

- 실동작 근거 **35건** / 정적·테스트대조 근거 11건(실모델·실환경에서 물리적으로 강제 불가한 분기: ai 응답 오염, 20MB 초과 이미지, 파일 결손 프레임 등).
- 이슈 **6건** — HIGH 1 / MEDIUM 2 / LOW 3.
- 근거 드리프트 **6건**(전부 C-3. C-4 는 13건 `file:line` 전건 정확).

## 1차 이슈 해소 대조

1차(2026-07-25) C-3·C-4 는 **TC-SAM2-01~28 · TC-KEYPOINT-01~13 전건 PASS** 였고, C-3/C-4 귀속 이슈는 참고 1건뿐이었다. TC-SAM2-29~33 은 2026-07-30 카탈로그 최신화 신규분이라 1차 판정이 없다.

| 1차 항목 | 1차 판정 | 2차 실측 | 결론 |
|---|---|---|---|
| **C-ISSUE-61(1차, LOW·참고)** — SAM2 프롬프트 입력좌표 이미지 경계 사전검증 부재 | 참고 관찰(테이블 판정 무영향) | **미해소 재확인.** `points:[[99999,99999]]` → **200** + 981정점/score 0.1984, `points:[[-500,-500]]` → **200** + 정상 폴리곤. `Sam2SegmentRequest`·`Sam2TrackRequest` 어디에도 요청 좌표 상한 검증 없음(응답만 검증) | **이월** → 본 회차 `C-ISSUE-65` 로 재기록 |
| (1차) TC-SAM2-22 — `Sam2TrackService:93` 분기 미도달(LabelAccessGuard 선점) | 1차 비고로 기록 | **동일 재현.** `nextSrcSns=[999999]` → 404 이나 메시지가 `"프레임을 찾을 수 없습니다."`(가드 메시지)이지 `"후속 프레임을 찾을 수 없습니다: 999999"` 가 아님 | 기능 동일(404) — 드리프트로만 계상 |
| (1차) TC-SAM2-23 — 1차 PASS 판정 | PASS | **2차 FAIL 로 반전.** 1차는 *"공유 검증함수이므로 ai 응답 경로도 동일 방어 확인됨"* 으로 **요청 경로(prevPolygon) 실측 결과를 응답 경로 판정에 전용**했다. 실제 코드는 `INVALID_INPUT`(**400**)을 던져 카탈로그 기대값 **502 EXTERNAL_API_ERROR** 와 불일치하고, "정점부족"은 아예 검증하지 않는다 | **1차 확증편향 미검출** → `C-ISSUE-62` |
| (1차) TC-SAM2-26 — trackShape 회귀 재발 여부(과거 결함) | PASS | **재확인 PASS.** BE `shapeOrDefault():73` POLYGON 정규화 + FE `ObjectAttributePanel.tsx:320` `shapeToDetectType(target.shape) ?? track.shape`(선택 객체 형태 우선) 양쪽 유지. 잘못된 문자열 `"CIRCLE"` → 400 | 회귀 없음 |
| `nextSrcSns` 무제한 전송 → 400 "추적 실패"(과거 계약 버그) | — | **양쪽 다 정합.** BE `@Size(max=50)`(51→400 / 50→200 실측) + FE `sam2TrackAllChunks` 가 50개 청크로 분할(`sam2-track.test.tsx:96 상수는_BE_Size_상한과_정합한다`, `:179 정확히_50개_후속프레임은_단일_청크`) | 회귀 없음 |
| **G-ISSUE-22**(G-part2, HIGH) FE 파급 | BE DTO 유실까지만 확인 | **FE 종단까지 실증 완료** — 아래 절 | `C-ISSUE-61` 로 승계·확장 |

---

## ★ mock 플래그 전파 실측 (ai-server 응답 / BE DTO / FE 자동적용 차단 여부)

계약: *"SAM2 분할(클릭/박스→폴리곤+신뢰도) — **mock 응답은 FE 자동적용 차단**"* (CLAUDE.md, SFR-08-01 VOS).
**Segment 는 계약대로 차단되고, Track 은 전 계층에서 신호가 소멸한다.**

| 계층 | SAM2 **Segment** | SAM2 **Track** |
|---|---|---|
| ai-server 응답 스키마 | `mock` / `source` / `mock_reason` 송신 | **동일하게 송신**(`schemas.py:180-188`, G-part2 §G-4 실측 `{"mock":false,"source":"model","mock_reason":null}`) |
| BE 클라이언트 DTO | `Sam2Response.java:26-32` — 3필드 **전부 선언** ✓ | `Sam2TrackResponse.java:14-18` — `trackId`/`polygon`/`score` 뿐, **3필드 전부 미선언** → Jackson 이 조용히 폐기 |
| BE 서비스 판정 | `Sam2SegmentService.java:124-127` `if (aiRes.mock()) return empty()` | `Sam2TrackService.java:105-142` — **mock 판정 자체가 없음** |
| BE→FE 응답 DTO | `Sam2SegmentResponse` polygon=`[]` + `ApiResponse.message="AI 모델 미로드 — 결과 신뢰 불가"`(`LabelController.java:174-178`) | `Sam2TrackResponseDto.TrackedItem` — mock 필드 없음, `message=null` |
| FE 처리 | `OverlayLayer.tsx:408-412` — `res.polygon.length===0` → 프리뷰 차단 + `onMockWarning` + **자동 적용 차단**. 추가로 `:413-417` score<0.3 저신뢰 차단 | `LabelingPage.tsx:634-667 handleTracked` — **무조건** `mergeAutoLabels`/`stashPendingTracks` 후 `"AI 추적 완료 (N프레임)"` **성공 토스트**. mock·score 분기 **0건** |
| **실동작 증거** | `box:[400,400,100,100]`(역전) → ai-server `WARNING [SAM2] segment real returned no mask — mock fallback` → BE **200** `{"polygon":[],"score":0.0,"empty":true}` + `message:"AI 모델 미로드 — 결과 신뢰 불가"` | `prevPolygon:[[9000,9000],[9000.5,9000],[9000.5,9000.4]]`… 및 `[[9000,9000],[9001,9000],[9001,9001]]` → ai-server `WARNING [SAM2] track real returned no mask — prev polygon fallback` → BE **200** `{"srcSn":79,"trackId":"mock-probe","points":[[9000.0,9000.0],[9001.0,9000.0],[9001.0,9001.0]],"score":0.5,"shapeType":"POLYGON"}`, **`message:null`** — 입력 폴리곤을 그대로 반사한 가짜 추적 결과가 실추론과 완전히 동일한 형태로 내려옴 |

→ **`C-ISSUE-61`(HIGH)**. 비대칭은 설계 실수가 아니라 *배선 누락*이다 — Segment 쪽 `Sam2SegmentResponse` javadoc 은 "mock 필드 없이 빈 폴리곤+메시지로 차단"을 **명시적 설계**로 기술하고 전용 테스트(`Sam2SegmentServiceTest:212 Sam2SegmentResponse에_mock필드가_없다`, `Sam2SegmentMockMessageWiringTest` 3건)까지 있으나, Track 에는 대응 설계·테스트가 **하나도 없다**.

---

## C-3 결과표 (TC-SAM2, 33건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|---|---|---|
| TC-SAM2-01 | segment 정상 | PASS | [실동작] `POST /v1/frames/77/sam2-segment {"points":[[640,540]]}` → **200**, polygon 265정점 / `score 0.9855`(실모델). `box:[100,100,400,400]` 도 200(1379정점). 호출 전후 `ls_data_lbl(77,79,81,83,85,87)` **28행 동일 = 미저장** | 온라인 SAM2 자동저장 없음 재확인(오토라벨 2경로 정책 정합). 근거 `:84-149` 는 실제 메서드 `:84-153` |
| TC-SAM2-02 | segment path/body srcSn 불일치 | PASS | [실동작] path 77 / body 79 → **400** `INVALID_INPUT` "path 의 srcSn 과 body 의 srcSn 이 다릅니다." (`LabelController.java:167-172`) | CWE-345 |
| TC-SAM2-03 | points/box 배타 위반(둘 다) | PASS | [실동작] `points`+`box` 동시 → **400** "exactlyOnePrompt: points 또는 box 중 정확히 하나만 제공해야 합니다." | `@AssertTrue` `Sam2SegmentRequest.java:46` |
| TC-SAM2-04 | points/box 배타 위반(둘 다 빈) | PASS | [실동작] 둘 다 미지정 → **400** 동일 메시지 | |
| TC-SAM2-05 | segment IDOR | PASS | [실동작] WORKER **2002**(rawSn132 미배정) → **403** "본인에게 배정되지 않은 영상입니다." / **PORTAL_USER** → **403** "권한이 없습니다."(`@PreAuthorize` 단계) | CWE-639 양방향 확인 |
| TC-SAM2-06 | segment 경로순회 차단 | PASS | [정적] 실효 가드 = `FrameImageEncoder.resolveSafe:220-229`(`normalize()+startsWith`) + `StorageSubtreePolicy.verifyDeidentifiedFile:181-193`(**`toRealPath()` 실경로 서브트리 판정** — 심링크 우회 차단). 사용자 입력이 경로에 닿는 표면 없음(경로는 DB `LS_DATA_SRC` 컬럼) | ⚠ 카탈로그가 함께 인용한 `Sam2SegmentService.java:179 resolveSafe` 는 **호출부 0건 dead code** → `C-ISSUE-64` |
| TC-SAM2-07 | segment 이미지 미존재 | PASS | [실동작] `srcSn=999999` → **404** "프레임을 찾을 수 없습니다."(`:89` 계열). [정적] 파일 결손 분기는 `FrameImageEncoder.resolveFrameImageWithoutGate:111-113` → NOT_FOUND | 실환경에 DB 행은 있고 파일만 없는 프레임이 없어(컨테이너 실측 8개 영상 전수 존재) 파일 분기는 정적 확인 |
| TC-SAM2-08 | segment 이미지 크기 초과 | PASS | [정적+테스트] `Sam2SegmentService.java:99-102` → `PAYLOAD_TOO_LARGE`(**413**). 테스트 `Sam2SegmentServiceTest#imageTooLargeRejected`(상한을 1B 로 낮춰 `ErrorCode.PAYLOAD_TOO_LARGE` 단언 + `verify(aiServerClient, never())`) | 실환경 프레임 최대 226KB(상한 20MB, `application.yml:396`)라 실호출 재현 불가. ⚠ 해당 테스트 `@DisplayName` 이 "…초과시_**400**" 으로 오기(단언은 413) |
| TC-SAM2-09 | segment mock→빈 폴리곤+메시지 | PASS | [실동작] 역전 박스로 실모델 mask 실패 유도 → ai-server `[SAM2] segment real returned no mask — mock fallback` → BE **200** `{"polygon":[],"score":0.0,"empty":true}` + `message:"AI 모델 미로드 — 결과 신뢰 불가"`. FE `OverlayLayer.tsx:408-412` 가 빈 폴리곤에서 **자동 적용 차단 + onMockWarning** | 계약대로 동작. 같은 계약이 Track 에는 없음 → `C-ISSUE-61` |
| TC-SAM2-10 | segment 폴리곤 정점<3 | PASS | [정적+테스트] `validatePolygon:160-163` → `EXTERNAL_API_ERROR`(**502**). `Sam2SegmentServiceTest#3점_미만_폴리곤_응답시_오류` | 실모델이 <3정점을 반환하도록 강제 불가 |
| TC-SAM2-11 | segment 좌표 경계초과 | PASS | [정적+테스트] `:171-174` `x>imgWidth‖y>imgHeight‖음수‖null` → **502**. 이미지 실측 해상도는 `readImageSize:199-209` 로 확보(1920×1080) | 외부 응답 불신 원칙 정합 |
| TC-SAM2-12 | segment simplifyTolerance 범위 | PASS | [실동작] `60` → **400** "경계 세밀함은 50.0 이하여야 합니다.", `-1` → **400** "…0.0 이상…". 경계 `0`·`50` 은 200 | `@DecimalMin/@DecimalMax` `Sam2SegmentRequest.java:35-36` |
| TC-SAM2-13 | segment 단순화 3점 미만→원본유지 | PASS | [실동작] 동일 프롬프트에서 `simplifyTolerance:0` → 492정점 / `:50` → 5정점(Douglas-Peucker 실동작 확인, 3점 미만까지는 안 줄어듦). [정적] 폴백 분기 `Sam2SegmentService.java:144-147` + 테스트 `#AI분할_simplify_결과가_3점미만이면_원본폴리곤유지` | 근거 드리프트 — 실제 폴백은 `:144-147`(카탈로그 `:132-143` 은 tolerance 조회·단순화 블록) |
| TC-SAM2-14 | track 정상 POLYGON | PASS | [실동작] `77 → nextSrcSns:[79,81]` → **200**, 프레임별 70·121정점 / score `0.7871`·`0.1955`(실모델). `ls_data_lbl` 28행 불변 = 미저장 | 응답 필드 = `srcSn/trackId/label/points/score/shapeType` — **mock 지표 없음**(→ `C-ISSUE-61`) |
| TC-SAM2-15 | track path/body srcSn 불일치 | PASS | [실동작] path 77 / body 79 → **400** (`LabelController.java:138-143`) | |
| TC-SAM2-16 | track nextSrcSns 50 초과(경계) | PASS | [실동작] **51개 → 400** "nextSrcSns: size must be between 0 and 50" / **50개 → 200**(50프레임 순차 추론 완주) | 과거 "추적 실패" 계약 버그의 서버측 상한 유효. FE 도 50 청크 분할로 정합 |
| TC-SAM2-17 | track nextSrcSns 빈 | PASS | [실동작] `[]` → **400** "nextSrcSns: must not be empty" | |
| TC-SAM2-18 | track prevPolygon <3점 | PASS | [실동작] 2점 → **400** "prevPolygon: size must be between 3 and 1000" | |
| TC-SAM2-19 | track prevPolygon >1000점 | PASS | [실동작] 1001점 → **400** 동일 메시지 | CWE-770 |
| TC-SAM2-20 | track trackId 64자 초과 | PASS | [실동작] 65자 → **400** "trackId: size must be between 0 and 64" | `TRACK_ID VARCHAR(64)` 정합 |
| TC-SAM2-21 | track IDOR 시작+후속 각각 | PASS | [실동작] ①시작 프레임 미배정(W2002→srcSn77) → **403** ②**후속만 미배정**(W2002, 시작=114 본인 배정 / next=[77] 타인) → **403** + `docker logs klid-ai-server \| grep -c sam2/track` **5→5(AI 호출 0건)** | 후속 프레임 인가가 AI 호출 이전임을 로그 카운트로 실증 |
| TC-SAM2-22 | track 후속 프레임 미존재 | PASS | [실동작] `nextSrcSns:[999999]` → **404**. `[-1]`·`[0]` 도 404 | 근거 드리프트 — 실제 throw 는 `LabelAccessGuard`(선점)이고 `Sam2TrackService.java:93` 분기는 미도달(1차와 동일). 결과 404 동일 |
| TC-SAM2-23 | track ai 응답 폴리곤 검증 | FAIL | [정적+테스트] ①`Sam2TrackService.java:119` → `validatePolygon(:188-204)` 이 던지는 것은 `ErrorCode.INVALID_INPUT` = **400**(기대 502 EXTERNAL_API_ERROR 와 불일치). 테스트도 400 을 고정(`Sam2TrackServiceTest#sam2_track_ai응답폴리곤_검증실패시_400`, 음수 폴리곤 스텁 → `INVALID_INPUT` 단언) ②"**정점부족**"은 검증 자체가 없음 — track 의 `validatePolygon` 은 `null‖isEmpty` 만 거부하고 segment 의 `MIN_POLYGON_POINTS(3)` 대칭 검사가 부재 | → `C-ISSUE-62`. 1차 PASS 판정은 요청 경로 실측을 응답 경로에 전용한 오판 |
| TC-SAM2-24 | track BBOX 외접박스 산출 | PASS | [실동작] `shape:"BBOX"` → `points:[[96.0,133.0],[225.0,305.0]]`(정확히 2점 `[[minX,minY],[maxX,maxY]]`), `shapeType:"BBOX"` | `toCircumscribedBbox:171-185` |
| TC-SAM2-25 | track 퇴화 bbox 프레임 스킵 | PASS | [실동작] mock 폴백(입력 폴리곤 반사)을 이용해 폭 0.5px·높이 0.4px 폴리곤 강제 + `shape:"BBOX"`, `nextSrcSns:[79,81]` → **200** `{"tracked":[]}` — 두 프레임 모두 스킵되고 **전체 추적은 예외 없이 완주**. 대조군(100×100 폴리곤)은 정상 1건 반환 | 1차는 [정적]이었으나 2차는 실동작 재현 성공 |
| TC-SAM2-26 | track shape 기본 POLYGON | PASS | [실동작] `shape` 생략 / `shape:null` 둘 다 `shapeType:"POLYGON"`. 잘못된 문자열 `"CIRCLE"` → **400**(Jackson enum 거부). [정적] FE `ObjectAttributePanel.tsx:320` 이 선택 객체 형태 우선(`shapeToDetectType(target.shape) ?? track.shape`) | "AI 추적 출력 형태 = 선택 객체 형태 고정" 정책 양단 유지 — 과거 결함 재발 없음 |
| TC-SAM2-27 | track ai 호출 실패 502 | PARTIAL | [정적+테스트] track 경로 claim 자체는 **참** — `:106-114` catch → `EXTERNAL_API_ERROR`(502) + 클라이언트 메시지는 일반화("SAM2 track 호출에 실패했습니다.") + `LogSanitizer` 로그(`Sam2TrackServiceTest#sam2_track_ai호출실패시_502`). **단 형제 경로 segment 는 동일 실패에서 `e.getMessage()` 를 그대로 실어** `"SAM2 segment 호출 실패: 500 Internal Server Error from POST http://klid-ai-server:9300/infer/sam2/segment"` 로 **내부 호스트·포트·경로를 응답 본문에 노출**(실동작: `points:[[]]` → ai-server 500 유발) | 인접 발견 반영해 등급 하향(G-part2 TC-AICONTRACT-06 선례) → `C-ISSUE-63` |
| TC-SAM2-28 | track trackId 로그 sanitize | PASS | [실동작] `trackId:"evil\r\nFAKE-LOG-INJECTED admin=true"` → 200, 백엔드 로그는 **단일 라인** `[Sam2Track] propagated trackId=evilFAKE-LOG-INJECTED admin=true startSrc=77 …`(CR/LF 완전 제거). `grep -n` 결과 위조 라인 미생성 | CWE-117 방어 실증. 응답 본문 echo 는 JSON 이스케이프되어 무해 |
| TC-SAM2-29 | segment 신고 구간 412 (신규) | PASS | [실동작] srcSn **78**(rawSn 133, `DE_IDNTF_YN='F'`) → **412** `PRECONDITION_FAILED` "비식별 재처리 대기 중인 영상은 AI 추론을 실행할 수 없습니다." + ai-server 호출 0건 | `resolveFrameImageForInference:133-136` → `requireNotUnderDeidentReport:206-217` — **파일 읽기 이전** 차단(전송 후 폐기 아님) |
| TC-SAM2-30 | track 신고 구간 412 (신규) | PASS | [실동작] 동일 srcSn 78 시작 + `nextSrcSns:[80]` → **412**, ai-server 호출 0건 | 시작 프레임 인코딩(`:86`)이 `encodeFrame:178-180` 경유라 후속 루프 진입 전 차단 |
| TC-SAM2-31 | 게이트 없는 base64 오버로드 부재 (신규, 구조 단언) | PASS | [정적] `grep -rn "encodeToBase64" backend/src/main/java` → **메서드 정의·호출 0건**(주석/javadoc 4건만). `FrameImageEncoder` public 표면 = 생성자 + `resolveFrameImageForInference(LsDataSrc)` + `encodeDeidentifiedFrameForInference(LsDataSrc)` + `encodeFrame(LsDataSrc)` — **경로 문자열 진입점 없음**, 전부 `private encode(Path)` 로 수렴 | 포털 SAM2 원본 픽셀 유출 경로(3630558d) 차단 유지 |
| TC-SAM2-32 | 게이트 없는 해석기는 패키지 전용 (신규, 구조 단언) | PASS | [정적] `FrameImageEncoder.java:94` 선언에 접근제어자 없음(**package-private**). 전 코드베이스 호출부 = 내부 `:135` 1건 + 패키지 외부 **`FrameBoundsResolver.java:101` 단 1건**이며 그 소비는 `ImageIO.read → getWidth/getHeight` 치수 측정뿐(픽셀 미반출) | |
| TC-SAM2-33 | 비식별 우선 폴백 경로 해석 (신규) | PASS | [실동작] srcSn **74**(rawSn 130 = 720P 해상도 파생, `SRC_FILE_PATH_NM` **null** / `DE_IDNTF_SRC_FILE_PATH_NM` 존재) → segment **200** 412정점·score 0.9872, track `74→[75]` **200**. "이미지 경로가 비어있습니다"(400) 미발생 | `resolveFrameImageWithoutGate:94-115` 비식별 우선 폴백 실동작 확인 |

## C-4 결과표 (TC-KEYPOINT, 13건)

> 저장 실동작은 `PUT /v1/frames/111/labels`(rawSn 143, WORKER 2001) 로 수행. `lblTypeCd` 는 요청 DTO 값(`LabelItemDto` `@Pattern(...|SKELETON)`)이라 SKELETON 라벨 마스터 없이도 경로 진입 가능.

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|---|---|---|
| TC-KEYPOINT-01 | SKELETON 정상 저장 | PASS | [실동작] 17개 삼중값 저장 → **200**, `labelVersion 0→1`. DB `ls_data_lbl.point_cn` = `[[0.0,0.0,0],[10.0,20.0,2],…]` **정확히 17개 삼중값, v 정수 보존**. 재조회(`GET /v1/frames/111/labels`)도 삼중값 그대로 반환(`LabelResponse.java:110 KeypointSerializer.fromJson` 라운드트립 무손실) | 근거 `:792-825`·`:834-838` **정확 일치** |
| TC-KEYPOINT-02 | 개수≠17 | PASS | [실동작] 16개 → **400** "SKELETON 키포인트는 정확히 17 개여야 합니다." / **18개도 400**(상·하한 양쪽) | `:793-796` 정확 |
| TC-KEYPOINT-03 | 삼중값 크기≠3 | PASS | [실동작] 전 원소 `[x,y]` → **400** "키포인트는 [x, y, v] 형태여야 합니다." / 원소 4개 `[10,20,2,5]` → **400** 동일(초과도 거부) | `:797-801` 정확 |
| TC-KEYPOINT-04 | v null 원소(NPE 방어) | PASS | [실동작] `[10,20,null]` 포함 → **400** "키포인트 좌표에 null 원소가 있습니다." — **500 NPE 아님**(언박싱 이전 fail-secure) | `:805-811` 정확 |
| TC-KEYPOINT-05 | v 범위 밖 | PASS | [실동작] `v=3` → **400** "가시성 v 는 0/1/2 중 하나여야 합니다." / **`v=-1` 400** / **`v=1.5`(비정수) 400**(`v != vRaw` 정수성 검사 실동작) | `:816-819` 정확 |
| TC-KEYPOINT-06 | v=0 좌표 0 허용(경계) | PASS | [실동작] `[0,0,0]` + 나머지 16개 → **200** 저장 성공, DB 에 `[0.0,0.0,0]` 보존 | `:820-823` 정확 |
| TC-KEYPOINT-07 | 음수 좌표(v>0) | PASS | [실동작] `x=-1,v=2` → **400** "좌표는 0 이상이어야 합니다 (x=-1.0, y=20.0)". **`v=0` 이어도 음수는 400**(v 무관 — 0 허용은 "0 이상"이지 음수 허용이 아님) | `:820-823` 정확. 반증 추가: `x=99999,v=2` → **400** "좌표가 이미지 경계를 벗어났습니다 (…이미지=1920x1080)" — ★3 사용자 저장 경로 = 경계 초과 400 거부(clamp 아님) 정합 |
| TC-KEYPOINT-08 | KeypointSerializer toJson | PASS | [실동작+정적] 저장 결과 JSON 이 `[[x,y,v],…]` 결정적 순서로 직렬화됨(위 DB 실측). `KeypointSerializer.java:46-60` `List.of(x,y,v)` 중첩 후 `writeValueAsString`. 테스트 `KeypointSerializerTest#roundTrip`·`#toJsonTripletFormat` | `:46` 정확 |
| TC-KEYPOINT-09 | fromJson 배열 아님 | PASS | [정적] `:80-81` `if (!root.isArray()) throw new IllegalArgumentException("키포인트는 배열이어야 합니다")` | 전용 단위테스트 **없음**(회귀 공백) — `KeypointSerializerTest` 4건에 미포함 |
| TC-KEYPOINT-10 | fromJson 삼중값 크기위반 | PASS | [정적+테스트] `:85-86` `!triplet.isArray() ‖ size!=3` → IAE. `KeypointSerializerTest#rejectsTwoTuple` 커버 | `:85-86` 정확 |
| TC-KEYPOINT-11 | fromJson 숫자아님 | PASS | [정적] `:91-92` `!x.isNumber()‖!y.isNumber()‖!v.isNumber()` → IAE | 전용 단위테스트 **없음**(회귀 공백) |
| TC-KEYPOINT-12 | fromJson 빈/[] | PASS | [정적+테스트] `:70-72` `null‖isBlank‖"[]"` → `List.of()`. `KeypointSerializerTest#emptyInputs` 커버 | `:69-81` 범위 내 |
| TC-KEYPOINT-13 | SKELETON R7 비교 폴백 | PASS | [실동작] 동일 삼중값 페이로드 재저장(`id:520` 지정) → **200**, `labelVersion` **1 유지(미증가)** + `ls_data_lbl_hstry` **1행 유지**(최초 ADDED 만) — 2-튜플 파서가 삼중값을 거부한 뒤 raw 숫자배열 폴백 비교로 "무변경" 정확 판정됨 | `LabelService.java:568-585 normalizePoints` **정확 일치**. 폴백이 없으면 매 저장이 UPDATED 로 오분류돼 버전·이력이 부풀었을 것 |

---

## 근거 드리프트

C-3 6건. **C-4 는 13건 전건 정확**(`LabelService.java:792-825/793-796/797-801/805-811/816-819/820-823/834-838/568-585`, `KeypointSerializer.java:46/80-81/85-86/91-92/69-81` 모두 실측 라인과 일치).

| # | TC | 카탈로그 근거 | 실측 | 성격 |
|---|---|---|---|---|
| 1 | TC-SAM2-06 | `Sam2SegmentService.java:179` (resolveSafe) | 선언은 `:179` 에 실재하나 **호출부 0건 — dead code**. 실효 가드는 함께 인용된 `FrameImageEncoder.java:220-229` + (미인용) `StorageSubtreePolicy.verifyDeidentifiedFile:181-193` | **실질**(무효 근거 → `C-ISSUE-64`) |
| 2 | TC-SAM2-13 | `Sam2SegmentService.java:132-143` | "3점 미만→원본유지" 분기는 실제 `:144-147`. `:132-143` 은 tolerance 결정 + Douglas-Peucker 적용 구간 | **실질**(기대결과가 가리키는 코드가 범위 밖) |
| 3 | TC-SAM2-22 | `Sam2TrackService.java:93` | 해당 `orElseThrow("후속 프레임을 찾을 수 없습니다: …")` 는 **도달 불가** — `accessGuard.verifyAccess(:90)` 가 먼저 404 를 던짐(실동작 메시지로 확인). 1차와 동일 관측 | **실질**(도달 불가 근거) |
| 4 | TC-SAM2-01 | `Sam2SegmentService.java:84-149` | `segment` 메서드 실제 범위 `:84-153` | 경미 |
| 5 | TC-SAM2-23 | `Sam2TrackService.java:188-206` | `validatePolygon` 실제 범위 `:188-204`(`:205-206` 은 다음 javadoc) | 경미 |
| 6 | TC-SAM2-27 | `Sam2TrackService.java:111-116` | catch 블록 `:108-114`(throw `:112-113`), `:115-116` 은 별개의 null 응답 가드 | 경미 |

> 근거는 아니지만 함께 기록: `Sam2SegmentServiceTest#imageTooLargeRejected` 의 `@DisplayName` 이 "이미지_크기_상한_초과시_**400**" 으로 오기(실 단언은 `PAYLOAD_TOO_LARGE`=413). 테스트 명칭만의 문제로 동작은 정상.

---

## 이슈 상세

### [C-ISSUE-61] TC-SAM2-14 / TC-SAM2-09 대비 — SAM2 Track 의 mock 폴백이 실추론과 구분 불가하고 FE 가 무조건 자동 병합한다 (G-ISSUE-22 의 FE 종단 파급)

- **심각도**: **HIGH**
- **기대 동작(기대효과)**: CLAUDE.md SFR-08-01 은 *"SAM2 분할(클릭/박스→폴리곤+신뢰도) … **mock 응답은 FE 자동적용 차단**"* 을 계약으로 못박고 있다. SAM2 Track 은 같은 VOS(추적+분할) 축의 형제 기능이고, ai-server 는 Track 응답에도 `mock`/`source`/`mock_reason` 을 **실제로 실어 보낸다**(`ai-server/app/schemas.py:180-188`). 따라서 Track 의 mock 폴백 결과도 사용자 작업본에 자동 병합되어서는 안 되며, 최소한 "AI 모델 미로드 — 결과 신뢰 불가" 급의 경고가 표시돼야 한다. Track 의 mock 폴백은 *"이전 프레임 폴리곤을 그대로 반사하고 score 를 0.5 로 고정"* 하는 **추적이 전혀 일어나지 않은 가짜 결과**라 오히려 Segment 보다 위험하다(Segment 는 빈 폴리곤이라 시각적으로 티가 나지만 Track 은 정상 결과와 형태가 동일하다).
- **현재 동작(이슈 내용)**: mock 신호가 **BE 클라이언트 DTO → BE 서비스 → BE 응답 DTO → FE** 4계층에서 연속으로 소멸한다.
  1. `backend/src/main/java/kr/co/cudo/authoring/common/client/dto/Sam2TrackResponse.java:14-18`
     ```java
     public record Sam2TrackResponse(
             @JsonProperty("track_id") String trackId,
             List<List<Double>> polygon,
             double score
     ) {}
     ```
     `mock`/`source`/`mock_reason` **미선언** → Jackson 이 조용히 폐기(컴파일·런타임 에러 없음). 대조군 `Sam2Response.java:26-32` 는 3필드를 모두 선언하고 javadoc 에 *"BE 는 이를 그대로 클라이언트 응답으로 전파해 FE 가 … 자동 적용 차단을 수행하도록 한다"* 고 명시돼 있다.
  2. `label/service/Sam2TrackService.java:105-142` — `aiRes.trackId()`/`polygon()`/`score()` 만 읽고 mock 판정 없음. 대조군 `Sam2SegmentService.java:124-127` 는 `if (aiRes.mock()) return Sam2SegmentResponse.empty();`.
  3. `label/dto/Sam2TrackResponseDto.java:21-28 TrackedItem` — mock 필드 없음. 컨트롤러(`LabelController.java:132-145`)도 Segment 와 달리 안내 message 를 세팅하지 않는다.
  4. `frontend/src/pages/label/LabelingPage.tsx:634-667 handleTracked` — **조건 없이** `mergeAutoLabels(...)` / `stashPendingTracks(...)` 후 `pushToast({variant:'success', message:'AI 추적 완료 (N프레임)'})`. mock 분기도 score 임계 분기도 없다. 대조군 `frontend/src/features/label/canvas/layers/OverlayLayer.tsx:408-417` 는 `res.polygon.length===0`(mock 신호) 와 `res.score < 0.3`(저신뢰) 두 단계로 자동 적용을 차단한다.
- **재현/확인 경로** (실측 완료):
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
    -H 'Content-Type: application/json' -d '{"role":"WORKER","channel":"INTERNAL"}' | jq -r .data.token)
  # 이미지 밖 폴리곤으로 실모델 mask 실패 → ai-server 가 mock 폴백
  curl -s -X POST http://localhost:18081/api/v1/frames/77/sam2-track \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"srcSn":77,"trackId":"mock-probe","prevPolygon":[[9000,9000],[9001,9000],[9001,9001]],"label":"car","nextSrcSns":[79]}'
  # → 200 {"success":true,"data":{"tracked":[{"srcSn":79,"trackId":"mock-probe","label":"car",
  #        "points":[[9000.0,9000.0],[9001.0,9000.0],[9001.0,9001.0]],"score":0.5,"shapeType":"POLYGON"}]},
  #        "message":null,"errorCode":null}
  docker logs --tail 20 klid-ai-server | grep "mock fallback"
  # → WARNING:app.routers.sam2:[SAM2] track real returned no mask — prev polygon fallback
  ```
  대조군(Segment 는 정상 차단):
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/frames/77/sam2-segment \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"srcSn":77,"box":[400,400,100,100]}'
  # → 200 {"data":{"polygon":[],"score":0.0,"empty":true},"message":"AI 모델 미로드 — 결과 신뢰 불가"}
  ```
- **영향**: **데이터 무결성 / 학습데이터 품질**(CWE-393 Return of Wrong Status Code 계열, 신뢰경계 미표시). ①ai-server 모델 미로드(`AI_MOCK_MODE=true`·가중치 부재·로드 실패)나 실모델의 mask 실패 시, 작업자는 **"AI 추적 완료"** 성공 토스트를 보고 가짜 좌표를 작업본에 받아들인다. ②그 좌표는 `PUT /v1/frames/{srcSn}/labels` 로 확정되면 실제 학습데이터가 되고, `AUTO_LBL_YN='Y'`+`AUTO_SAM2` provenance 까지 붙어 **"AI 가 추적한 라벨"로 데이터마트에 나간다**. ③mock 폴백은 이전 폴리곤을 그대로 반사하므로 N프레임 전부 **동일 좌표**가 되어 트랙 보간·품질검사에서도 "정지한 객체"로 보일 뿐 오류로 식별되지 않는다. ④부수적으로, 반사된 좌표가 이미지 경계를 벗어나 있으면(track 의 `validatePolygon` 은 상한 검사가 없다 — `C-ISSUE-62` 참조) 저장 단계(`LabelService.validateWithinBounds`)에서야 400 이 나 작업자는 원인 불명의 저장 실패를 겪는다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`Sam2TrackResponse` 에 `mock`/`source`/`mock_reason` 3필드 추가(하위호환 생성자 병행) → ②`Sam2TrackService` 에서 `aiRes.mock()` 이면 해당 프레임을 결과에서 제외하거나(Segment 의 `empty()` 대칭) 응답에 표식 전파 → ③`Sam2TrackResponseDto.TrackedItem` 또는 `Sam2TrackResponseDto` 상위에 표식 노출 + 컨트롤러가 `Sam2SegmentResponse.MOCK_UNAVAILABLE_MESSAGE` 를 재사용 → ④FE `handleTracked` 에 mock·저신뢰 차단 분기 추가(Segment 의 `OverlayLayer.tsx:408-417` 과 동일 규약). 회귀 가드로 `Sam2TrackServiceTest` 에 mock 응답 케이스, FE `sam2-track.test.tsx` 에 자동병합 차단 케이스 추가.

### [C-ISSUE-62] TC-SAM2-23 — SAM2 Track 이 ai-server 응답 오류를 400(클라이언트 귀책)으로 반환하고, 응답 폴리곤 최소 정점 수를 검증하지 않는다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그 기대값은 **502 `EXTERNAL_API_ERROR`** 다. 외부 시스템(ai-server)이 규약 위반 응답을 보낸 것은 **클라이언트 귀책이 아니므로** 400 이 아니라 502 여야 한다(형제 경로 `Sam2SegmentService.validatePolygon:159-175` 은 정확히 `EXTERNAL_API_ERROR` 를 던진다). 또한 폐곡선 폴리곤의 최소 정점 수(3) 검사도 segment 와 동일하게 있어야 한다.
- **현재 동작(이슈 내용)**:
  - `backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2TrackService.java:119`
    ```java
    // 외부 시스템 응답도 신뢰하지 않음 — 동일 좌표 검증.
    validatePolygon(aiRes.polygon(), "ai-server polygon");
    ```
    이 `validatePolygon(:188-204)` 은 **요청 `prevPolygon` 과 공용**이며 전부 `ErrorCode.INVALID_INPUT`(=`HttpStatus.BAD_REQUEST`, `ErrorCode.java:6`)을 던진다 → 외부 응답 오염이 **400** 으로 나간다.
  - 같은 메서드에 **최소 정점 수 검사가 없다**:
    ```java
    private void validatePolygon(List<List<Double>> polygon, String fieldName) {
        if (polygon == null || polygon.isEmpty()) { ... }   // ← null/빈 만 거부
        for (List<Double> pair : polygon) { /* size!=2, null, 비유한, 음수 */ }
    }
    ```
    segment 의 `MIN_POLYGON_POINTS(=3)` 대칭 검사가 부재하므로 ai-server 가 **1~2정점 폴리곤**을 반환해도 그대로 통과해 FE 작업본에 병합된다(요청 `prevPolygon` 은 DTO `@Size(min=3)` 로 막히지만 **응답 경로는 무방비**).
  - 상한(이미지 경계) 검사도 없다 — segment 는 `x > imgWidth` 를 502 로 막지만 track 은 `x < 0` 만 본다. `C-ISSUE-61` 실측에서 `[[9000,9000],…]` 이 그대로 200 으로 나온 것이 그 증거다.
  - 회귀 테스트가 현재 동작을 **기대값으로 고정**하고 있다: `backend/src/test/java/kr/co/cudo/authoring/label/Sam2TrackServiceTest.java:272-285 sam2_track_ai응답폴리곤_검증실패시_400` — 음수 좌표 응답 스텁에 대해 `ErrorCode.INVALID_INPUT` 을 단언. 즉 코드를 502 로 고치면 이 테스트가 깨진다(정책 확정 필요).
- **재현/확인 경로**: 실모델이 오염 폴리곤을 반환하도록 강제할 수 없어 정적·테스트 대조로 확인.
  ```bash
  sed -n '115,120p;188,204p' backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2TrackService.java
  sed -n '1,10p' backend/src/main/java/kr/co/cudo/authoring/common/exception/ErrorCode.java   # INVALID_INPUT = BAD_REQUEST
  sed -n '270,285p' backend/src/test/java/kr/co/cudo/authoring/label/Sam2TrackServiceTest.java
  ```
- **영향**: ①**오류 귀속 왜곡** — FE·운영자가 400 을 보고 "내 요청이 잘못됐다"고 판단해 ai-server 장애를 놓친다(관측성 저하). 동일 실패에서 segment 는 502, track 은 400 으로 갈려 계약이 비일관하다. ②**미검증 정점부족·경계초과 좌표 유입** — 1~2정점 폴리곤은 폐곡선이 아니라 캔버스 렌더·MASK/RLE 변환·트랙 보간에서 하류 오류를 유발하고, 경계 초과 좌표는 저장 단계(`validateWithinBounds`)에서야 400 이 나 작업 손실로 이어진다. ③보안 등급은 낮음 — 응답 메시지 `"ai-server polygon 좌표는 …"` 이 내부 컴포넌트명을 노출하나 호스트·경로는 없다(CWE-209 경미).
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `validatePolygon` 을 **요청용/응답용 2개로 분리**해 ①요청 경로는 현행 `INVALID_INPUT`(400) 유지 ②응답 경로는 `EXTERNAL_API_ERROR`(502) + `MIN_POLYGON_POINTS(3)` + 이미지 실측 상한(segment 처럼 `readImageSize` 로 확보) 검사 추가. `Sam2TrackServiceTest:272` 는 502 기대로 갱신. ⚠ 카탈로그 기대값을 코드에 맞춰 400 으로 내리는 선택도 가능하나, 그 경우에도 **정점부족·상한 미검증**은 별개로 남으므로 최소한 그 두 가드는 추가해야 한다.

### [C-ISSUE-63] TC-SAM2-27 인접 — `Sam2SegmentService` 가 예외 원문을 응답 본문에 그대로 실어 내부 ai-server URL·파일 경로를 노출한다 (CWE-209)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `rules/security.md` "System Information Leak (CWE-209)" · `api-design.md` *"스택트레이스, 내부 경로 등 기술 정보 절대 포함 금지"*. 형제 경로 `Sam2TrackService.java:108-114` 는 정확히 이 규약을 지킨다 — 예외 원문은 `LogSanitizer.sanitize` 를 거쳐 **서버 로그로만** 가고 클라이언트에는 `"SAM2 track 호출에 실패했습니다."` 라는 일반화 메시지만 나간다.
- **현재 동작(이슈 내용)**: `backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java` 4곳이 `e.getMessage()` 를 클라이언트 메시지에 직접 연결한다.
  ```java
  :115-118  } catch (Exception e) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "SAM2 segment 호출 실패: " + e.getMessage());   // ← 내부 URL 노출
            }
  :194      throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 크기 확인 실패: " + e.getMessage());
  :207      throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 읽기 실패: " + e.getMessage());
  :217      throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 읽기 실패: " + e.getMessage());
  ```
  실동작 확인(ai-server 500 유발):
  ```
  HTTP=502
  {"success":false,"data":null,
   "message":"SAM2 segment 호출 실패: 500 Internal Server Error from POST http://klid-ai-server:9300/infer/sam2/segment",
   "errorCode":"EXTERNAL_API_ERROR"}
  ```
  → **내부 서비스 호스트명·포트·엔드포인트 경로**가 인증된 WORKER 응답으로 그대로 나간다. `:194/:207/:217` 의 `IOException.getMessage()` 는 통상 **파일 절대경로**(`/app/storage/deidentified/frames/deid/…`)를 포함하므로 스토리지 레이아웃까지 노출된다(CWE-209 + 경로 정보 노출).
  참고: `FrameImageEncoder.encode:190-197` 은 같은 상황에서 `"이미지 읽기에 실패했습니다."` 로 원문을 삼키도록 이미 고쳐져 있다 — `Sam2SegmentService` 만 구 패턴이 남았다.
- **재현/확인 경로**:
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
    -H 'Content-Type: application/json' -d '{"role":"WORKER","channel":"INTERNAL"}' | jq -r .data.token)
  # points 내부 좌표쌍 누락 → ai-server 500 (G-ISSUE-21) → BE 502 + URL 노출
  curl -s -X POST http://localhost:18081/api/v1/frames/77/sam2-segment \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"srcSn":77,"points":[[]]}'
  ```
- **영향**: **정보 노출(CWE-209)**. 내부 네트워크 토폴로지(서비스 DNS 명 `klid-ai-server`, 포트 9300, 추론 엔드포인트 경로)와 스토리지 절대경로가 애플리케이션 응답으로 유출된다. 저작도구는 관제/포털에서 토큰을 인계받는 다중 채널 앱이라 내부 주소 노출은 후속 SSRF·측면 이동의 정찰 정보가 된다. 즉시 착취 가능한 취약점은 아니나 Fortify/CodeQL 의 *System Information Leak* 룰에 직접 걸리는 패턴이다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** 4곳 모두 `Sam2TrackService.java:108-114` 패턴으로 통일 — `log.error(..., LogSanitizer.sanitize(e.getMessage()))` 로 서버 로그에만 남기고, `CustomException` 메시지는 `"SAM2 분할 호출에 실패했습니다."` / `"이미지를 읽을 수 없습니다."` 같은 일반화 문구로 교체(원인 예외는 `CustomException(code, msg, e)` 3-arg 로 체인). 회귀 가드로 "응답 message 에 `http://`·`/app/` 문자열이 없다"는 단언을 `Sam2SegmentServiceTest#aiServerTimeoutMapped` 에 추가.

### [C-ISSUE-64] TC-SAM2-06 — `Sam2SegmentService.resolveSafe` 는 호출부 0건의 dead code이며 카탈로그가 이를 경로순회 방어 근거로 인용하고 있다

- **심각도**: LOW
- **기대 동작(기대효과)**: 보안 가드로 문서화·인용되는 코드는 실제 실행 경로에 있어야 한다. 그렇지 않으면 다음 검증자·유지보수자가 "가드가 있다"고 믿고 실제 방어선을 점검하지 않는다(1차·2차 모두 이 라인을 근거로 PASS 판정했다).
- **현재 동작(이슈 내용)**: `Sam2SegmentService.java:179-188` 의 `private Path resolveSafe(Path baseDir, String relativePath)` 는 **어디서도 호출되지 않는다**. 클래스 javadoc `:52` 도 여전히 *"경로 순회(CWE-22): `{@link #resolveSafe}` 로 기준 디렉토리 외부 접근 차단"* 이라고 기술한다. 실제 경로 해석은 `:96` 에서 `frameImageEncoder.resolveFrameImageForInference(src)` 로 위임되며, 진짜 가드는 `FrameImageEncoder.resolveSafe:220-229`(lexical) 와 `StorageSubtreePolicy.verifyDeidentifiedFile:181-193`(**`toRealPath()` 실경로 서브트리 판정 — 심링크 우회 차단**)이다.
  ```bash
  grep -n "resolveSafe" backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java
  # 52: *   <li>경로 순회(CWE-22): {@link #resolveSafe} 로 ...   ← javadoc
  # 179:    private Path resolveSafe(Path baseDir, String relativePath) {   ← 선언만
  # (호출 0건)
  ```
- **재현/확인 경로**: 위 grep. 방어 자체는 유효함을 별도 확인 — 프레임 경로는 DB 컬럼 유래라 사용자 입력이 닿는 표면이 없고, `FrameImageEncoder`/`StorageSubtreePolicy` 이중 가드가 실행 경로에 있다.
- **영향**: **기능·보안 영향 없음**(경로순회는 실제로 차단됨). 순수 유지보수/추적성 문제 — 검증 문서의 `file:line` 근거가 무효 코드를 가리켜 **감사 추적이 허위 안전감을 준다**. `C-ISSUE-82`(동명이인 dead `TrackInterpolator`)와 같은 계열.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** dead `resolveSafe` 제거 + 클래스 javadoc `:52` 를 실제 가드(`FrameImageEncoder`/`StorageSubtreePolicy`) 위임으로 갱신. 카탈로그 TC-SAM2-06 근거도 `FrameImageEncoder.java:220-229 · StorageSubtreePolicy.java:181-193` 으로 정정.

### [C-ISSUE-65] TC-SAM2-01/14 — SAM2 프롬프트 입력좌표의 이미지 경계 사전검증 부재 (1차 C-ISSUE-61 이월·미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: 응답 폴리곤은 이미지 실측 상한까지 검증하면서(`Sam2SegmentService.validatePolygon:171`) 요청 프롬프트는 무검증인 것은 비대칭이다. 최소한 명백히 무의미한 좌표(이미지 폭·높이의 수십 배)는 외부 ai-server 로 보내기 전에 400 으로 끊는 것이 자원·계약상 합리적이다. 서비스는 이미 `:104-106` 에서 실측 `imgWidth/imgHeight` 를 확보하고 있어 추가 I/O 없이 검사 가능하다.
- **현재 동작(이슈 내용)**: `Sam2SegmentRequest.points/box`(`:33-34` — 개수 상한만) 과 `Sam2TrackRequest.prevPolygon`(`:24` — 개수 상한만) 어디에도 좌표 상한 검증이 없다. 실측:
  - `{"srcSn":77,"points":[[99999,99999]]}` → **200**, polygon 981정점 / score 0.1984 (프레임 실측 1920×1080)
  - `{"srcSn":77,"points":[[-500,-500]]}` → **200**, 정상 형태 폴리곤
  - track `prevPolygon:[[9000,9000],[9001,9000],[9001,9001]]` → **200**(음수만 `Sam2TrackService.validatePolygon:199` 로 차단, 상한은 무검증)
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/frames/77/sam2-segment \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"srcSn":77,"points":[[99999,99999]]}'   # → 200
  ```
- **영향**: 보안 취약점 아님(응답은 여전히 사후 검증되고 경로순회·주입 표면 없음). ①낭비성 외부 추론 호출(실모델 CPU 추론 1회 ≈ 수백 ms) ②`C-ISSUE-61` 과 결합 시 악화 — 이미지 밖 프롬프트는 mask 실패 → mock 폴백 확률을 높이고, track 은 그 결과를 무표식으로 자동 병합한다. 1차 대비 **상태 변화 없음**.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `Sam2SegmentService` 는 `:104-106` 직후에, `Sam2TrackService` 는 시작 프레임 이미지 치수를 확보한 뒤 `0 ≤ x ≤ imgWidth`, `0 ≤ y ≤ imgHeight` 사전 400 검증 추가 검토. ⚠ 좌표 2축 정책(★3)상 이는 **사용자 입력 경로**이므로 clamp 가 아니라 **400 거부**가 맞다.

### [C-ISSUE-66] TC-SAM2-08 인접 — 이미지 상한이 압축 바이트만 검사하고 디코딩 픽셀 수 상한이 없으며, 내부 채널 SAM2 엔드포인트에는 rate limit 이 없다

- **심각도**: LOW
- **기대 동작(기대효과)**: `rules/security.md` "Unrestricted Resource Consumption(OWASP API4:2023)" — 요청 크기 제한만으로는 부족하고 **디코딩 후 자원 사용량**까지 통제돼야 한다.
- **현재 동작(이슈 내용)**:
  1. **압축 크기만 검사** — `Sam2SegmentService.java:98-102` 는 `Files.size()`(압축 바이트)를 `maxImageBytes`(기본 20MB)와 비교한 뒤, `:199-209 readImageSize` 에서 `ImageIO.read(imagePath.toFile())` 로 **이미지를 전량 디코딩**한다. JPEG/PNG 압축비를 감안하면 20MB 파일이 수십억 픽셀로 전개될 수 있고(디컴프레션 밤, CWE-409), `BufferedImage` 는 픽셀당 3~4바이트를 힙에 잡는다. 픽셀 치수·총 픽셀 수 상한이 어디에도 없다. (`ImageIO.setUseCache`/`ImageReader` 헤더-only 치수 조회 미사용 — `ImageIO.read` 는 전체 래스터를 읽는다.)
  2. **내부 채널 rate limit 부재** — `POST /v1/frames/{srcSn}/sam2-segment` · `sam2-track` 에는 RateLimiter 배선이 없다(`grep -rn "RateLimit" label/controller/LabelController.java` → 0건). 포털 경로는 `PortalSam2Service`·`PortalUploadController` 가 per-user Resilience4j RateLimiter 를 보유해 대칭이 깨져 있다. track 은 1요청당 최대 **50회 순차 실모델 추론**을 유발하므로(TC-SAM2-16 실측 50개 완주) 인증된 계정 하나로 ai-server CPU 를 장시간 점유시킬 수 있다.
- **재현/확인 경로**:
  ```bash
  grep -n "maxImageBytes\|ImageIO.read" backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java
  grep -rn "RateLimit" backend/src/main/java/kr/co/cudo/authoring/label/controller/LabelController.java   # 0건
  grep -rn "RateLimit" backend/src/main/java/kr/co/cudo/authoring/portal/service/PortalSam2Service.java   # 존재
  ```
- **영향**: **가용성(DoS)**, 착취 난도 높음. ①디컴프레션 밤은 프레임 이미지가 **내부 ffmpeg 추출물**(경로도 DB 유래)이라 공격자가 임의 파일을 심을 표면이 사실상 없어 현실 위험은 낮다 — 다만 외부 비식별 솔루션(KPST)이 산출한 비식별 프레임도 같은 경로를 타므로 "외부 산출물을 무조건 디코딩한다"는 신뢰 가정이 남는다. ②rate limit 부재는 내부 인증 사용자(WORKER/REVIEWER) 한정이라 위협 모델상 낮으나, ai-server 는 배치 오토라벨과 GPU/CPU 를 공유하므로 배치 지연으로 번질 수 있다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`ImageIO.getImageReaders` 로 **헤더만 읽어 치수를 얻고**(전체 디코딩 없이) `width*height` 상한(예: 8K = 33M px)을 먼저 검사한 뒤 필요할 때만 디코딩 ②내부 SAM2 엔드포인트에도 `PortalSam2Service` 와 동일한 per-user Resilience4j RateLimiter 적용 검토(임계값은 운영 판단). ⚠ 실제 도입 전 "내부 작업자 편의 저하 vs 자원 보호" 트레이드오프를 사용자와 확정할 것.

---

# C 클러스터 part5 (C-5·C-6) 2차 검증 결과

> 대상: `docs/test-cases/C-marking-labeling.md` 의 **C-5 TC-TRACK(31건)** + **C-6 TC-PRESET(18건)** = **49건**
> 폐기(`~~취소선~~`) 행 **0건** — 두 섹션 모두 폐기 케이스가 없어 집계 제외분이 없다.
> 환경: 로컬 도커 스택(backend `:18081`, HEAD `ca3c712b` 이미지 — 소스 HEAD 대비 backend 커밋 11개 미반영 / ai-server `:19300` / postgres `:5432` schema `public`). backend **재기동하지 않음**.
> ⚠ 이슈 ID 는 지시대로 `C-ISSUE-81` 부터 부여했다. **1차(`docs/검증결과/2026-07-25/1차/ISSUES.md`)에 이미 `C-ISSUE-81`·`C-ISSUE-82` 가 존재하므로 번호가 충돌한다** — 마감 시 2차 ISSUES.md 로 합칠 때 재번호(예: `C2-ISSUE-81`) 필요.

## 집계

| 구분 | 검증 | PASS | PARTIAL | FAIL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| C-5 TC-TRACK | 31 | 30 | 1 | 0 | 0 | 0 | 0 |
| C-6 TC-PRESET | 18 | 17 | 1 | 0 | 0 | 0 | 0 |
| **합계** | **49** | **47** | **2** | **0** | **0** | **0** | **0** |

이슈 6건 — HIGH 1 / MEDIUM 3 / LOW 2.

### ★ 판정값만 보면 놓치는 것 (필독)

**전건 PASS/PARTIAL 인데 이 섹션에서 가장 심각한 결함(HIGH `C-ISSUE-81`)은 어떤 케이스도 판정하지 않는다.**
C-5 는 "YOLO 트랙 프록시" 를 표방하지만 **`trackId` 의 존재·안정성·정확성을 단언하는 케이스가 0건**이다(24~30 은 입력검증·좌표·교차영상만 본다). 실측 결과 그 `trackId` 는 **엉뚱한 검출에 붙는다**(§이슈 상세). 카탈로그 커버리지 갭이며, 다음 회차에 케이스 신설이 필요하다.

- self-fill 의심: **0건**. yolo-track 은 ai-server(`:19300`) 실추론 경유를 실측(`mock=false, source=model`, 원본 좌표 `-0.604` → BE `0.0` clamp 대조)했고, 프리셋은 외부 연동이 없는 내부 도메인이다.
- 검증 중 생성/변경한 데이터는 전부 원복했다(§검증 부작용).

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 실측 | 판정 |
|---|---|---|---|
| **C-ISSUE-81**(1차) | `MaskRleConverter`·`CoordinateTransformer` 경계/보안 가드 **단위테스트 커버리지 0%** (TC-TRACK-13/16/18/19/20/21/22) | `MaskRleConverterTest` 에 rleToMask 가드 **7건**(null·DoS상한·width≤0·height≤0·가드순서·길이합초과·경계동일) + `CoordinateTransformerTest` 에 null 가드 **4건**(rotate points/center/가드순서·scale·translate) 신설 확인 | **해소** |
| **C-ISSUE-82**(1차) | 동명이인 dead `common/util/TrackInterpolator` 잔존 | `grep -rn "TrackInterpolator" backend/src/main` → 프로덕션 참조 **0건 유지**(사용처는 `batch/interpolation/TrackInterpolator` 뿐). **범위가 더 넓다** — `MaskRleConverter`·`CoordinateTransformer`·`common/util/ShapeType`·`common/util/Keyframe` 도 전부 프로덕션 참조 0건 | **미해소(확대) → `C-ISSUE-84`** |
| B-ISSUE-61(B-part4) | `TRCK_ID` 가 1/298 로 사실상 전량 NULL → 보간 항상 no-op | 2차 재실측 **421행 중 `trck_id` 보유 1행**(값 `'0'`), 배치 로그 전건 `no interpolation candidates`. **근본 원인을 추가 규명**(§보간 실환경 도달성) | **미해소(원인 규명) → `C-ISSUE-81`·`C-ISSUE-82`** |

> ⚠ 카탈로그 TC-TRACK-13 비고 `※전용 단위테스트 여전히 부재(C-ISSUE-81)` 와 C-6 하단 불확실표의 `C-ISSUE-81 … 미해소` 는 **현 코드베이스와 어긋난다**(§근거 드리프트 D2).

## ★보간 실환경 도달성 (알고리즘 정확성 / 실환경 실행 여부 — 분리 기록)

지시에 따라 **①알고리즘 자체의 정확성**과 **②실환경에서 한 번이라도 도는가**를 분리해 기록한다.

### ① 알고리즘 정확성 — 정확함 (픽스처·손계산 기준)

| 항목 | 검증 방법 | 결과 |
|---|---|---|
| BBOX 선형 보간 | `Bbox.linearInterpolate`(`batch/interpolation/Bbox.java:32-42`) 손계산 — 키프레임 f=5 `(0,0,10,10)` / f=10 `(10,10,20,20)`, span=5 → f=6 `t=0.2` → left `0+10*0.2=2.0`, f=9 `t=0.8` → `8.0` | 코드와 일치 |
| propagate 정책 | `TrackInterpolator.java:57-81` — outside 즉시 break / 다음이 outside 면 현재만 저장 후 break / 마지막 이후 미채움 / span≤0 은 `continue` 후 LinkedHashMap 덮어쓰기 | CVAT 규칙과 일치 |
| 폴리쉐이프 | 대응쌍을 **키프레임 쌍당 1회만** 계산 후 재사용(`:126-135`), 중간 정점 수 = `max(n,m)` 불변식 | `PolyshapeMatcherTest` 17건 · `TrackInterpolatorPolyshapeTest` 13건이 폐/개곡선·방향반전·정점0/2개·NaN/Infinity·상한초과까지 커버 |
| 호출부 정렬 | 카탈로그가 지적하지 않은 반증 포인트 — `TrackInterpolator` 는 "정렬은 호출자 책임"이나 유일 호출부 `TrackInterpolationStep.interpolateTrack:346-349` 가 `srcSnToFrame` 기준 오름차순 정렬 후 전달 | 역순 입력 위험 **없음** |
| 부동소수 누적 오차 | `t` 를 매 프레임 `(f-k.frame)/span` 로 **재계산**(누적 가산 아님) → 오차 누적 구조가 아님 | 이상 없음 |

### ② 실환경 실행 여부 — **한 번도 돌지 않았다**

| 근거 | 실측 |
|---|---|
| `LS_DATA_LBL` 전수 | `select count(*), count(trck_id) from ls_data_lbl` → **421행 / trck_id 보유 1행**(값 `'0'`, distinct 1) |
| 배치 로그 | `docker logs klid-backend --since 24h \| grep Interpolation` → 관측 전건이 `[Batch][Interpolation] no interpolation candidates rawSn={143,145,146,147,148,149,152,153,154,156}`. `saved … interpolatedRows=` 로그 **0건** |
| 후보 조건 | `TrackInterpolationStep:174` `lblRepository.findAutoBboxWithTrackId(rawSn)` — `AUTO_LBL_YN='Y'` + BBOX/POLYGON + **`TRCK_ID IS NOT NULL`**. 트랙 1건당 최소 2 키프레임이 있어야 사이 프레임이 생성됨 |
| 결론 | `TRCK_ID` 가 사실상 부재하므로 후보가 0건 → **보간 산출물(`LBL_SRC_CD='INTERPOLATE'`) 0건**. 단위 테스트는 `Keyframe` 픽스처를 직접 만들므로 이 결손과 무관하게 전부 GREEN 이다(거짓 안심의 전형) |

### ③ 왜 `TRCK_ID` 가 안 생기는가 — 이번 회차에 근본 원인 규명

배치(`YoloAutolabelStep:208`)와 온디맨드 프록시(`YoloTrackService:120`) 모두 `/infer/yolo/track`(ByteTrack) 을 호출하고 응답 `track_id` 를 그대로 `TRCK_ID` 로 적재한다(`YoloLabelPersister.java:82-84`). 그런데 —

1. **ByteTrack 미확정 구간이 길다.** ai-server 직접 프로브(동일 이미지 3회, `clip_id=probe-c5`): `frame_index=0,1` → 전 검출 `track_id=null`, `frame_index=2` → 9건 중 **3건만** id 부여. 실영상 프레임 수가 3~12장인 현 데이터에서는 확정 전에 시퀀스가 끝난다.
2. **부여된 id 가 엉뚱한 검출에 붙는다** (→ HIGH `C-ISSUE-81`). ai-server 실로그에 `[ByteTrack] tracker_id 길이 불일치 dets=6 tracker_ids=5` 가 반복 관측됐고, 컨테이너 내 직접 프로브로 `ByteTrackTracker.update()` 가 **입력 순서를 재배열**함을 확증했다.
3. 결과적으로 실DB 에 남은 유일한 `TRCK_ID='0'` 1행조차 **그 값이 올바른 객체의 것이라는 보증이 없다**.

> **판정 원칙**: TC-TRACK-01~11(unit 계층)은 ①에 근거해 PASS 로 둔다. ②③ 은 케이스 단언 밖이므로 판정을 바꾸지 않고 이슈로 분리 기록한다. **픽스처 GREEN 을 근거로 "보간 기능 정상"이라고 읽으면 안 된다.**

## ★프리셋 단일 진실원 실측 (마스터 변경 → 기존 프리셋 반영 여부)

검증 시작 시점 `LS_LABEL_PRESET` **0행**이었으므로 전용 라벨 마스터 2건(labelId **40**/**41**)과 프리셋 6건을 새로 만들어 실측했다(타 에이전트 데이터 무손상, 검증 후 원복 — §검증 부작용).

| 순서 | 조작 | 관측 | 판정 |
|:--:|---|---|---|
| 1 | labelId 40=`ZZTEST-C5A`(BBOX), 41=`ZZTEST-C5B`(POLYGON) 생성 → 프리셋 `ZZTEST-P1`(40,41)·`ZZTEST-P3`(40) 생성 | 응답 `labelCodeOptions[].code=null`, DB `ls_label_preset_code.lbl_cd` **NULL** / `lbl_id`=40,41 → **코드 문자열 스냅샷 미저장** | 계약 준수 |
| 2 | **프리셋 생성 후** 마스터 40 을 `PUT /v1/manage/labels/40` 으로 이름 `ZZTEST-C5A`→`ZZTEST-C5A-RENAMED` + 형태 `BBOX`→`POLYGON` 변경 | **기존 프리셋 2건 모두** 재조회 시 `labelName=ZZTEST-C5A-RENAMED`, `labelType=POLYGON`, `bboxEnabled=false`/`polygonEnabled=true` 로 **즉시 반영**. `ls_label_preset_code` 행은 **무변경**(lbl_id 만 보유) | **★단일 진실원 성립** |
| 3 | 마스터 40 을 다시 `POLYGON`→`POINT` 로 변경 | 프리셋 응답 `labelType=POINT`, **두 토글 모두 false**(`LabelGeometry.POINT(false,false)`) | 형태 소유권=마스터 확인 |
| 4 | 마스터 41 을 `DELETE`(soft delete, `USE_YN='N'`) | 프리셋 응답 `linked=false`, 자동 생성/삭제 **없음**, 오류 없음. **단 `labelName`·`code` 가 모두 `null`** → `C-ISSUE-85` | 부분 성립 |
| 5 | 미연결 레거시 코드(`lbl_id=NULL, lbl_cd='ZZ-LEGACY-CODE'`) 1행 주입 후 조회 | `linked=false`, `labelName='ZZ-LEGACY-CODE'` 로 **오류 없이 노출** | 계약 준수 |
| 6 | 같은 labelId 2회 지정(`labelIds:[40,40]`, `[40,41,40]`) — create/update 양쪽 | **201/200 + DB 1행만 저장**. 애그리거트(`LsLabelPreset.replaceCodes:168-180`)가 `keyOf`(=`"ID:"+labelId`) 로 dedup. 부분 유니크 인덱스 `uk_ls_label_preset_code_lblid (preset_id, lbl_id) WHERE lbl_id IS NOT NULL` 실재(`\d ls_label_preset_code`) — 앱 dedup 1선 + DB 인덱스 최종 방어 | 불변식 성립 |
| 7 | 스냅샷 여부 역검증 | `ls_label_preset_code` 컬럼은 `cd_sn/preset_id/lbl_cd/sort_seq/lbl_id` 뿐 — **라벨명·형태 컬럼 없음**(구 `BBOX_ENABLED`/`POLYGON_ENABLED` 제거 확인) | 계약 준수 |

### DTCT_TYPE_CD 축 일원화 (4경로) — 드리프트 없음

| 경로 | 파일:라인 | 사용 함수 |
|---|---|---|
| 배치 오토라벨 | `batch/step/YoloAutolabelStep.java:277` | `findLabelIdByDtctType` |
| 온라인 AI 탐지 | `label/service/AutolabelOnlineService.java:334`, `:426` | `findLabelIdByDtctType` |
| SAM2 | `batch/step/Sam2SegmentStep.java:198` | `findLabelIdByDtctType` |
| 프리셋 토글 | `batch/policy/PresetLabelLookupService.java:43,57,109,124-136` | 키 = 마스터 `DTCT_TYPE_CD` 정규화(trim+소문자) |

`findLabelIdByName` 은 **프로덕션 코드에서 0건**(잔존은 `V120__label_name_ci_unique.sql:7` 주석 · `LabelMasterService.java:140` 대체 설명 주석 · 레거시 리포지토리 테스트 1건뿐). 미매핑 클래스 차단은 BE 가 강제 — `AutolabelOnlineService.resolveDetectClasses:457-471` 이 마스터 매핑 집합과의 **교집합만** ai-server 로 보내고, 교집합이 비면 호출 자체를 스킵한다(FE 요청 불신). DB 실측상 활성 라벨 9건 중 `dtct_type_cd` 매핑 6건 / 미매핑 3건(fire·smoke·water)으로 정책과 정합.

### 인가 (IDOR) 실측

| 요청 | WORKER 토큰 결과 |
|---|---|
| `GET /v1/manage/presets` | **403** |
| `POST /v1/manage/presets` | **403** (`FORBIDDEN`) |
| `PUT /v1/manage/labels/40` | **403** |

REVIEWER 전용(`@PreAuthorize("hasRole('REVIEWER')")` + `/v1/manage/**` 매처) 확인. 수평 상승 경로 없음.

## C-5 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|---|---|---|
| TC-TRACK-01 | BBOX 보간 정상 | PASS | [정적] `batch/interpolation/TrackInterpolator.java:76-79` + 손계산(t=0.2→2.0 / t=0.8→8.0) · `TrackInterpolatorTest.java:47` | — |
| TC-TRACK-02 | 빈 키프레임 | PASS | [정적] `:53-55` `return Map.of()` · `TrackInterpolatorTest.java:26` | — |
| TC-TRACK-03 | 단일 키프레임 | PASS | [정적] `:57-64`(i+1 미존재 → 그 프레임만) · `TrackInterpolatorTest.java:34` | — |
| TC-TRACK-04 | outside 마커 종료 | PASS | [정적] `:59-62` break(해당 frame 미포함) · `TrackInterpolatorTest.java:109` | 프로덕션은 `outside=false` 고정(`TrackInterpolationStep:370,394`) — 실환경 미도달 경로 |
| TC-TRACK-05 | 다음이 outside | PASS | [정적] `:66-70` · `TrackInterpolatorTest.java:123` | 동상 |
| TC-TRACK-06 | 마지막 이후 propagate 없음 | PASS | [정적] 루프 종료로 구현(코드 부재가 규칙) · `TrackInterpolatorTest.java:140` | 근거 드리프트 D1 — 카탈로그 `:35-44` 는 javadoc |
| TC-TRACK-07 | 동일 frame 중복 뒤값 우선 | PASS | [정적] `:71-75` span≤0 `continue` → 다음 iteration LinkedHashMap 덮어쓰기 · `TrackInterpolatorTest.java:157` | 역순(span<0)도 동일 경로 |
| TC-TRACK-08 | totalFrames 음수 | PASS | [정적] `:50-52` IAE · `TrackInterpolatorTest.java:181` | — |
| TC-TRACK-09 | keyframes null | PASS | [정적] `:49` `Objects.requireNonNull` → NPE · `TrackInterpolatorTest.java:174` | — |
| TC-TRACK-10 | 폴리쉐이프 보간 정점수 다름 | PASS | [정적] `:127-135` 쌍당 1회 match 후 재사용 · `TrackInterpolatorPolyshapeTest.java:65` · `PolyshapeMatcherTest.java:89,162,176,189` | 중간 정점수=max 불변식 3건 커버 |
| TC-TRACK-11 | 폴리쉐이프 closed/open | PASS | [정적] `PolyshapeMatcher.java:54-68`(closed→normalizeRing+matchClosed / open→앵커고정+reverse감지) · `TrackInterpolatorPolyshapeTest.java:79,92` | — |
| TC-TRACK-12 | rotate 중심 기준 | PASS | [정적] `common/util/CoordinateTransformer.java:34-38` 손계산 — (10,0), +90°, center(0,0) → `dx*cos-dy*sin=0`, `dy*cos+dx*sin=10` → (0,10) · `CoordinateTransformerTest.java:64` | ⚠ 이 유틸은 프로덕션 참조 0건(`C-ISSUE-84`) |
| TC-TRACK-13 | rotate points/center null | PASS | [정적] `:22-27` IAE 2종 · `CoordinateTransformerTest.java:81,90,98` | **근거 드리프트 D2** — 카탈로그의 "전용 단위테스트 여전히 부재" 는 무효(1차 C-ISSUE-81 해소) |
| TC-TRACK-14 | scale/translate | PASS | [정적] `:44-65` · `CoordinateTransformerTest.java:38,51,107,115` | ⚠ `C-ISSUE-84` |
| TC-TRACK-15 | maskToRle 첫픽셀 on | PASS | [정적] `MaskRleConverter.java:51-59` + 손계산 `[T,T,F]`→`[0,2,1]` · `MaskRleConverterTest.java:65` | — |
| TC-TRACK-16 | maskToRle 빈/null | PARTIAL | [정적] `:31-33` null·`length==0` → `int[0]` 정상. 그러나 **0폭 행**(`boolean[1][0]`)은 `:53` `flat[0]` 에서 `ArrayIndexOutOfBoundsException` | **`C-ISSUE-83`** |
| TC-TRACK-17 | maskToRle >1M 픽셀(DoS) | PASS | [정적] `:22,:37-39` IAE · `MaskRleConverterTest.java:191`(1001×1001) | — |
| TC-TRACK-18 | maskToRle 비직사각형 | PASS | [정적] `:44-47` row 폭 불일치 IAE | 전용 테스트 **없음**(커버리지 갭, 코드는 정확) |
| TC-TRACK-19 | rleToMask width/height≤0 | PASS | [정적] `:93-95` IAE · `MaskRleConverterTest.java:124,135` | — |
| TC-TRACK-20 | rleToMask >1M | PASS | [정적] `:88-92` 할당 **전** IAE · `MaskRleConverterTest.java:110` + 가드 순서 고정 `:146` | — |
| TC-TRACK-21 | rleToMask 길이합 초과 | PASS | [정적] `:102-104` + 손계산(`rle=[10,10]`, w5×h3=15 → idx15 도달 시 IAE) · `MaskRleConverterTest.java:162` + 경계동일 `:174` | — |
| TC-TRACK-22 | rleToMask null | PASS | [정적] `:85-87` IAE · `MaskRleConverterTest.java:101` | — |
| TC-TRACK-23 | round-trip mask→rle→mask | PASS | [정적] 손계산 4패턴(all-off `[15]` / all-on `[0,N]` / `[T,T,F]` / `[F,F,T]`) 전부 복원 · `MaskRleConverterTest.java:18` 랜덤 1000회 | — |
| TC-TRACK-24 | yolo-track path/body 불일치 | PASS | [실동작] `POST /v1/frames/93/yolo-track` body `srcSn=94` → **400** `INVALID_INPUT` "path 의 srcSn 과 body 의 srcSn 이 다릅니다." · `LabelController` yolo-track 핸들러 | — |
| TC-TRACK-25 | yolo-track nextSrcSns 50 초과 | PASS | [실동작] 51개 → **400** "nextSrcSns: size must be between 0 and 50" · `label/dto/YoloTrackRequest.java:23` | 50개 정확히는 검증 통과(서비스 진입 확인) |
| TC-TRACK-26 | yolo-track nextSrcSns 빈 | PASS | [실동작] `[]` → **400** "nextSrcSns: must not be empty" | — |
| TC-TRACK-27 | yolo-track 좌표 clamp | PASS | [실동작] ai-server 원응답 `car x1=-0.604` → BE 응답 **`0.0`**(400 아님), 나머지 좌표 전부 동일. `DetectionBoxNormalizer.java:59-68` | ★self-fill 아님 — ai 원응답과 BE 응답 1:1 대조 |
| TC-TRACK-28 | yolo-track NaN/Infinity 400 | PASS | [정적] `DetectionBoxNormalizer.java:54-58` `!Double.isFinite` → IAE → `YoloTrackService.java:169-172` 400 · `YoloTrackServiceTest.java:325` | 실AI 로 NaN 유도 불가 → 테스트 대조 |
| TC-TRACK-29 | yolo-track 퇴화 박스 검출 단위 스킵 | PASS | [정적] `DetectionBoxNormalizer.java:65-66` `Optional.empty()` → `YoloTrackService.java:173-177` WARN 후 `continue`(시퀀스 유지) · `YoloTrackServiceTest.java:304` | — |
| TC-TRACK-30 | yolo-track 교차 영상 혼입 차단 | PASS | [실동작] srcSn 93(raw 136) + nextSrcSns `[94,135(raw 157)]` → **400** "시퀀스 프레임이 시작 프레임과 다른 영상에 속합니다: srcSn=135" · `YoloTrackService.java:109-114` | 근거 드리프트 D3(±1행) |
| TC-TRACK-31 | 보간 진실원 단일 확인 | PASS | [정적] `grep -rn "TrackInterpolator" backend/src/main` → 프로덕션 사용은 `TrackInterpolationStep.java:13,72` 의 `batch.interpolation.TrackInterpolator` 뿐. `common/util/TrackInterpolator` 참조 **0건** | 기대결과(=dead code 잔존)와 일치. 자동 구조 단언 테스트는 **부재** → `C-ISSUE-84` |

## C-6 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|---|---|---|
| TC-PRESET-01 | 생성 정상(labelId 기반) | PASS | [실동작] `POST /v1/manage/presets {labelIds:[40,41]}` → **201**, 응답 `code=null`, DB `ls_label_preset_code.lbl_cd` **NULL** · `preset/service/PresetService.java:62-73,163-172` | 코드 문자열 미저장 확인 |
| TC-PRESET-02 | 생성 이벤트타입 무효 | PASS | [실동작] `eventTypeCd:"NOPE_XYZ"` → **400** "지원하지 않는 이벤트 타입입니다" · `PresetService.java:127-134` | 유효 categoryKey 9종은 `/v1/event-types` 실조회로 확인 |
| TC-PRESET-03 | 생성 이벤트타입 빈값 허용 | PASS | [실동작] `eventTypeCd:""` → **201**, 저장값 `null` 로 정규화 | — |
| TC-PRESET-04 | 생성 이름 중복 | PASS | [실동작] 동일 `name` 재생성 → **409** "이미 사용 중인 프리셋 이름입니다." · `:65-67` | — |
| TC-PRESET-05 | 생성 labelId 미존재/비활성 | PASS | [실동작] `labelIds:[999999]` → **400** / `labelIds:[37]`(`USE_YN='N'`) → **400** "존재하지 않거나 비활성 라벨입니다: labelId=…" · `:144-160` | 두 케이스 모두 실측 |
| TC-PRESET-06 | 생성 이벤트 유니크 경합 | PASS | [실동작] 프리셋1 에 `020001` 매핑 후 다른 프리셋을 같은 이벤트로 생성 → **409** "이미 다른 프리셋에 매핑된 이벤트입니다" · `:174-181`, DB `uk_ls_label_preset_evnt` 실재 | 예외→409 변환 경로가 **너무 넓다** → `C-ISSUE-86` |
| TC-PRESET-07 | 수정 미존재 | PASS | [실동작] `PUT /presets/999999` → **404** "프리셋을 찾을 수 없습니다." · `:79-80` | — |
| TC-PRESET-08 | 수정 이름 중복(자기제외) | PASS | [실동작] 타 프리셋명으로 수정 → **409** / 자기 이름 그대로 수정 → **200** · `:81-83` | 자기제외 동작 실측 |
| TC-PRESET-09 | 수정 이벤트 유니크 위반 | PASS | [실동작] 이미 매핑된 `020001` 로 수정 → **409** · `:88-93` `saveAndFlush` | — |
| TC-PRESET-10 | 삭제 멱등 | PASS | [실동작] `DELETE /presets/999999` → **204**(no-op) · `:98-103` | — |
| TC-PRESET-11 | 복제 이름 시퀀스 | PASS | [실동작] 1회차 `"ZZTEST-P1 (복사본)"`, 2회차 `"ZZTEST-P1 (복사본 2)"` · `:184-196` | 근거 드리프트 D4(±1행) |
| TC-PRESET-12 | 복제 이벤트 미상속 | PASS | [실동작] 원본 `eventTypeCd=020001` → 복제본 **`null`**, 코드 2건은 그대로 복사 · `:106-118` | — |
| TC-PRESET-13 | 복제 이름 50회 초과 실패 | PASS | [실동작] `(복사본)`~`(복사본 50)` 50건 선점 후 clone → **409** "복제 이름 생성에 실패했습니다." · `CLONE_SUFFIX_MAX=50`, `:186-195` | 자동 테스트 부재 — 본 회차 실동작으로 커버 |
| TC-PRESET-14 | 코드뷰 미연결(labelId null) | PASS | [실동작] `lbl_id=NULL, lbl_cd='ZZ-LEGACY-CODE'` 행 → `linked=false`, `labelName='ZZ-LEGACY-CODE'`, 오류·자동생성 없음 · `:239-244` | 근거 드리프트 D5 |
| TC-PRESET-15 | 코드뷰 마스터 미존재/soft delete | PARTIAL | [실동작] 마스터 41 soft delete 후 `linked=false`, 자동 생성/삭제 없음(단언 성립). **그러나 `labelName`·`code` 가 모두 `null`** 이라 어떤 라벨이었는지 식별 불가 + `labelCodes`(선언상 `string[]`)에 **null 원소** 유입 · `:240-244` | **`C-ISSUE-85`** |
| TC-PRESET-16 | 코드뷰 형태 마스터 파생 | PASS | [실동작] 마스터 40 을 BBOX→POLYGON→POINT 로 순차 변경 시 프리셋 응답 토글이 `(T,F)→(F,T)→(F,F)` 로 즉시 추종 · `:246-250` + `label/domain/LabelGeometry.java:25-28` | 프리셋 개별 토글 컬럼 부재(스키마 실측) |
| TC-PRESET-17 | 부분유니크 labelId 중복 방지 | PASS | [실동작] `\d ls_label_preset_code` → `uk_ls_label_preset_code_lblid UNIQUE (preset_id, lbl_id) WHERE lbl_id IS NOT NULL` 실재. `labelIds:[40,40]`·`[40,41,40]` 요청 시 DB **1행만** 저장(애그리거트 `LsLabelPreset.replaceCodes:168-180` dedup 1선) · IT `LsLabelPresetCodeLabelIdUniqueIT:67` | 인덱스 자체 거부는 IT 가 커버. 앱 dedup 때문에 API 경로로는 인덱스에 도달하지 않음(설계 의도) |
| TC-PRESET-18 | N+1 회피 배치 조회 | PASS | [실동작] 프리셋 55건 목록 1회 조회 전후 `pg_stat_user_tables.ls_label` 스캔 **delta=1** · [정적] `LsLabelPresetRepository.findAllWithCodes` fetch join + `PresetService.java:220-235` → `LabelMasterService.findActiveByIds:180-186`(`findByLabelIdInAndUseYn`) | `PresetServiceTest.java:349` 도 커버 |

## 근거 드리프트

| # | 케이스 | 카탈로그 근거/비고 | 실제 | 영향 |
|:--:|---|---|---|---|
| **D1** | TC-TRACK-06 | `TrackInterpolator.java:35-44` | 해당 구간은 **javadoc**(`<li>마지막 키프레임 이후 frame 은 propagate 하지 않음</li>`). 실제 규칙은 `:57-81` 루프가 마지막 키프레임 이후를 만들지 않음으로 성립 | 판정 무영향(문서 위치만) |
| **D2** | TC-TRACK-13 · C-6 §불확실표 | `※전용 단위테스트 여전히 부재(C-ISSUE-81)` / `C-ISSUE-81 … 미해소` | `CoordinateTransformerTest.java:81,90,98,107,115` + `MaskRleConverterTest.java:101,110,124,135,146,162,174` **신설 완료** → 1차 C-ISSUE-81 **해소** | **비고가 사실과 반대** — 카탈로그 정정 필요 |
| **D3** | TC-TRACK-30 | `YoloTrackService.java:110-115` | 실제 `:109-114` | ±1행 |
| **D4** | TC-PRESET-11 / 13 | `PresetService.java:183-195` / `:185-195` | 실제 `:184-196` / `:186-195` | ±1행 |
| **D5** | TC-PRESET-02/03 · 14 · 15 · 16 | `:126-134` / `:237-244` / `:238-244` / `:245-251` | 실제 `:127-134` / `:239-244` / `:240-244` / `:246-250` | ±1~2행 |

> D3~D5 는 판정에 영향 없는 ±1~2행 오프셋이다. **D2 만 실질 드리프트**(폐기된 사실을 미해소로 표기)이므로 카탈로그 갱신 대상이다.

## 검증 부작용 (원복 완료)

| 항목 | 조작 | 원복 |
|---|---|---|
| `LS_LABEL_PRESET` / `_CODE` | `ZZTEST-P1/P3/P17/P6…` 등 6건 + 복제이름 선점 50건 생성, 코드행 주입 1건 | **전량 삭제** — 검증 후 `select count(*)` = **0 / 0** (검증 시작 시점과 동일) |
| `LS_LABEL` | labelId **40**(`ZZTEST-C5A-RENAMED`, POINT) · **41**(`ZZTEST-C5B`, POLYGON) 신규 생성 | **`USE_YN='N'` soft delete**(하드 삭제는 FK·감사 이력상 미수행). 활성 라벨 9건은 **무변경** — C-part2 의 labelId 37 과 동일 패턴 |
| `LS_DATA_LBL` / `LS_DATA_SRC` / `LS_DATA_RAW` | **무변경**(yolo-track 은 `readOnly` 프록시라 DB 미기록) | — |
| rawSn 126·129·133 | **미접촉** | — |

---

## 이슈 상세

### [C-ISSUE-81] TC-TRACK-30(인접) — ByteTrack `track_id` 가 위치 기반 zip 으로 **엉뚱한 검출에 배정**된다

- **심각도**: HIGH
- **기대 동작(기대효과)**: `/infer/yolo/track` 이 돌려주는 `track_id` 는 **그 검출(bbox)의 객체 식별자**여야 한다. 이 값이 `LS_DATA_LBL.TRCK_ID` 로 적재되어 트랙 보간·트랙 편집·트랙 병합·검수 스냅샷의 객체 동일성 기준이 되므로, 잘못 붙으면 **서로 다른 객체가 같은 트랙으로 묶이고 같은 객체가 끊긴다**.
- **현재 동작(이슈 내용)**: `ai-server/app/models/bytetrack_util.py:_apply_bytetrack` 이 트래커 출력을 **입력 리스트와 위치(zip)로** 매핑한다.

  ```python
  tracked = tracker.update(sv_dets)
  tracker_ids = list(tracked.tracker_id) if tracked.tracker_id is not None else []
  if len(tracker_ids) != len(dets):
      logger.warning("[ByteTrack] tracker_id 길이 불일치 dets=%d tracker_ids=%d — 누락분 track_id=None 유지", ...)
  for det, tid in zip(dets, tracker_ids):          # ← 위치 기반. tracked 의 순서를 보지 않는다
      det.track_id = int(tid) if tid is not None and int(tid) >= 0 else None
  ```

  그런데 `ByteTrackTracker.update()` 는 **입력 순서를 보존하지 않는다.** 컨테이너 내 직접 프로브(`docker exec klid-ai-server python /tmp/bt_probe.py`, 입력 박스 순서 `[A(10,10), B(200,200), C(400,400)]`):

  ```
  step 0  ids=[-1,-1,-1]   out_xyxy=[[200..],[10..],[400..]]      # B,A,C 로 재배열
  step 1  ids=[ 0, 1,-1]   out_xyxy=[[10..],[400..],[200..]]      # A,C,B 로 재배열
  ```

  step 1 의 정답 매핑은 `A→0, C→1, B→None` 인데, 코드는 위치로 `dets[0]=A→0`(우연히 일치), **`dets[1]=B→1`(오배정 — 정답은 None)**, **`dets[2]=C→None`(정답은 1)** 을 부여한다.
  실환경 로그에도 같은 상황이 반복 관측된다: `docker logs klid-ai-server` → `WARNING:app.models.bytetrack_util:[ByteTrack] tracker_id 길이 불일치 dets=6 tracker_ids=5 — 누락분 track_id=None 유지`(3회). 이 WARN 은 "길이가 다르다"만 알릴 뿐 **순서가 다르다는 사실은 감지하지 못한다.**
  BE 는 이 값을 그대로 신뢰해 적재한다 — 배치 `batch/step/YoloAutolabelStep.java:295` → `batch/step/YoloLabelPersister.java:82-84`(`String.valueOf(trackId)` → `TRCK_ID`).
- **재현/확인 경로**:
  ```bash
  # 1) 재배열 확증 (ai-server 컨테이너 내부)
  docker exec klid-ai-server python - <<'PY'
  import numpy as np, supervision as sv
  from trackers import ByteTrackTracker
  t = ByteTrackTracker()
  boxes=[[10,10,50,50],[200,200,240,240],[400,400,440,440]]; confs=[0.9,0.15,0.85]
  for s in range(2):
      d=sv.Detections(xyxy=np.array(boxes,float),confidence=np.array(confs,float),class_id=np.array([0,0,0]))
      o=t.update(d); print(s, list(o.tracker_id), o.xyxy.tolist())
  PY
  # 2) 실경로 관측
  docker logs klid-ai-server 2>&1 | grep "길이 불일치"
  # 3) BE 경유 (REVIEWER 토큰)
  curl -s -X POST http://localhost:18081/api/v1/frames/94/yolo-track \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' \
    -d '{"srcSn":94,"nextSrcSns":[95,95,95]}'
  # → frameIndex 2·3 에서 9건 중 앞 3건에만 trackId 4,5,6 부여
  ```
- **영향**: 데이터 무결성(CWE-707 계열 — 부정확한 식별자 할당). 학습데이터의 객체 궤적이 조용히 뒤섞인다. `TrackMergeService`·`TrackEditService`·`TrackInterpolationStep` 이 모두 `TRCK_ID` 를 신뢰하므로 오염이 산출물(export JSON)까지 전파된다. 현재 실DB 에 `TRCK_ID` 보유 행이 1건뿐이라 **피해 규모는 작지만, `TRCK_ID` 가 정상화되는 순간 즉시 대량 오염으로 바뀐다**(즉 `C-ISSUE-82` 가 이 결함을 가리고 있다).
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `tracked` 가 돌려주는 `xyxy`(또는 detection index)로 **입력 detection 을 역매핑**한 뒤 `track_id` 를 부여한다(좌표 동등 비교 또는 `sv.Detections` 에 인덱스를 `data` 로 실어 왕복). 길이 불일치 WARN 은 유지하되 **순서 불일치도 감지**하는 단언을 추가한다. 역매핑이 불가능한 트래커 구현이면 `track_id` 를 아예 부여하지 않는 편(전량 None)이 오배정보다 안전하다.

---

### [C-ISSUE-82] TC-TRACK-01~11 — 트랙 보간이 실환경에서 **한 번도 실행되지 않는다**(`TRCK_ID` 사실상 전량 NULL, B-ISSUE-61 재확인)

- **심각도**: MEDIUM (기능 미작동은 HIGH 급이나, 1차 발견 이슈(B-ISSUE-61)의 2차 재확인 + 원인 규명이므로 중복 상향을 피함)
- **기대 동작(기대효과)**: CVAT 트랙 보간 포팅(SFR-08 / `docs/analysis/portable-modules/01`)의 목적은 **키프레임 사이 프레임을 자동으로 채워 라벨링 공수를 줄이는 것**이다. 배치 `INTERPOLATE` 단계가 실제로 `LBL_SRC_CD='INTERPOLATE'` 라벨을 생성해야 한다.
- **현재 동작(이슈 내용)**: 보간 후보 조건이 `TRCK_ID IS NOT NULL` 인데(`batch/step/TrackInterpolationStep.java:174` → `LsDataLblRepository.findAutoBboxWithTrackId`), 실DB 는 —

  ```
  select count(*) total, count(trck_id) with_track, count(distinct trck_id) d from ls_data_lbl;
   total | with_track | d
  -------+------------+---
     421 |          1 | 1        -- 유일한 값은 '0'
  ```

  그 결과 배치 로그가 전건 no-op 이다(`docker logs klid-backend --since 24h`):
  ```
  [Batch][Interpolation] no interpolation candidates rawSn=143
  [Batch][Interpolation] no interpolation candidates rawSn=145 / 146 / 147 / 148 / 149 / 152 / 153 / 154 / 156
  ```
  `[Batch][Interpolation] saved … interpolatedRows=` 로그는 **0건**. 즉 `TrackInterpolator`·`PolyshapeMatcher`(총 약 340줄)와 그 위의 `TrackMergeService` 재보간 원자성 배선까지 **전부 사문(死文)** 이다.
  원인은 상위 `track_id` 부재다 — ai-server 직접 프로브 결과 ByteTrack 은 `frame_index=0,1` 에서 전 검출 `track_id=null`, `frame_index=2` 에서야 9건 중 3건만 부여한다. 현 데이터의 영상당 프레임 수가 3~12장이라 확정 전에 시퀀스가 끝난다. **단위 테스트는 `Keyframe` 픽스처를 직접 생성하므로 이 결손을 구조적으로 감지하지 못한다**(`TrackInterpolatorTest` 10건 + `TrackInterpolatorPolyshapeTest` 13건 전부 GREEN).
- **재현/확인 경로**:
  ```sql
  select count(*), count(trck_id) from ls_data_lbl;
  select count(*) from ls_data_lbl l join ls_data_lbl_ai_info ai on ai.data_lbl_sn=l.lbl_sn
   where ai.lbl_src_cd='INTERPOLATE';   -- 0
  ```
  ```bash
  docker logs klid-backend --since 24h 2>&1 | grep "\[Batch\]\[Interpolation\]"
  ```
- **영향**: 요구 기능(트랙 보간) 미제공. 라벨링 공수 절감 효과 0. 나아가 **회귀 안전망이 실효 없음** — 보간 코드가 깨져도 실환경에서는 증상이 나타나지 않는다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①선행으로 `C-ISSUE-81`(track_id 오배정) 해소 ②ByteTrack 확정 지연(`track_activation_threshold`/`minimum_consecutive_frames`) 튜닝 또는 미확정 트랙에도 잠정 ID 를 부여하는 정책 결정 ③**보간 도달성 자체를 검증하는 통합 테스트**(실프레임 시퀀스 → `TRCK_ID` 부여 → `INTERPOLATE` 라벨 ≥1) 추가 — 픽스처 단위 테스트만으로는 이 결함을 영원히 못 잡는다 ④수동 라벨링에서 작업자가 트랙을 지정하는 동선이 있다면 그 경로로도 `TRCK_ID` 가 채워지는지 확인.

---

### [C-ISSUE-83] TC-TRACK-16 — `maskToRle` 가 0폭 행 mask 에서 `IllegalArgumentException` 이 아니라 `ArrayIndexOutOfBoundsException`

- **심각도**: LOW
- **기대 동작(기대효과)**: "빈 mask" 입력은 예외 없이 `int[0]` 을 돌려주거나, 최소한 이 클래스의 다른 방어 규약과 동일하게 **`IllegalArgumentException`(명시 예외)** 이어야 한다. `common.util` 규약상 이 유틸은 IAE 만 던지도록 설계돼 있다(`DetectionBoxNormalizer` 주석 참조).
- **현재 동작(이슈 내용)**: `backend/src/main/java/kr/co/cudo/authoring/common/util/MaskRleConverter.java:30-59`

  ```java
  public static int[] maskToRle(boolean[][] mask) {
      if (mask == null || mask.length == 0) { return new int[0]; }   // :31-33  ← 0폭 행은 걸리지 않음
      int h = mask.length;  int w = mask[0].length;                   // h=1, w=0
      long total = (long) h * w;                                      // 0
      ...
      boolean[] flat = new boolean[(int) total];                      // length 0
      ...
      if (flat[0]) {                                                  // :53  ← AIOOBE
  ```
  `mask = new boolean[1][0]`(또는 `new boolean[3][0]`)이면 `mask.length != 0` 이라 조기 반환 가드를 통과하고, `total=0` 이라 DoS 가드(`:37-39`)와 직사각형 가드(`:44-47`)도 통과한 뒤 `:53` 의 `flat[0]` 에서 `ArrayIndexOutOfBoundsException` 이 난다.
  `MaskRleConverterTest` 에 이 입력 형태의 테스트가 없어(빈 mask 테스트는 `new boolean[5][5]` = 값이 모두 false 인 5×5) 회귀로 잡히지 않는다.
- **재현/확인 경로**: `MaskRleConverter.maskToRle(new boolean[1][0])` → `ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0`. (정적 판독 — 이 유틸은 프로덕션 호출부가 없어 API 로는 재현 불가, `C-ISSUE-84` 참조.)
- **영향**: 현재 프로덕션 도달 경로가 **없어** 실피해 0. 다만 `C-ISSUE-84` 를 해소해 이 유틸을 실제로 배선하는 순간, 0폭 mask(모델이 폭 0 박스를 낸 경우 등)에서 예외 종류가 규약과 달라 상위 핸들러(IAE→400 매핑)를 빠져나가 **500** 이 된다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `:31-33` 가드를 `mask == null || mask.length == 0 || mask[0].length == 0` 로 확장하거나, `:53` 진입 전에 `if (total == 0) return new int[0];` 를 둔다. 대응 테스트(`maskToRle(new boolean[1][0])`) 추가.

---

### [C-ISSUE-84] TC-TRACK-31 — CVAT 포팅 유틸 5개 파일이 **전부 dead code**(1차 C-ISSUE-82 미해소 + 범위 확대)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md` "CVAT 포팅 전략" 이 Phase 6 산출물로 명시한 **MASK↔RLE↔Polygon 변환**(portable-modules/02)과 **좌표 변환/회전 유틸**(portable-modules/06)은 제품 코드에서 실제로 쓰여야 한다. 또한 보간 진실원은 1개여야 한다.
- **현재 동작(이슈 내용)**: `backend/src/main` 전수 grep 결과, 아래 5개 파일은 **프로덕션 참조가 0건**이다(자기 파일 제외).

  | 파일 | 프로덕션 참조 | 비고 |
  |---|:--:|---|
  | `common/util/TrackInterpolator.java` | 0 | 1차 C-ISSUE-82 그대로. `batch/interpolation/TrackInterpolator`(POLYGON 완전 지원)와 **동명이인**이고 이쪽은 POLYGON 이 `UnsupportedOperationException`(`:37-40`) |
  | `common/util/MaskRleConverter.java` | 0 | portable-modules/02 포팅물 |
  | `common/util/CoordinateTransformer.java` | 0 | portable-modules/06 포팅물 |
  | `common/util/ShapeType.java` | 0 | dead `TrackInterpolator` 전용 |
  | `common/util/Keyframe.java` | 0 | dead `TrackInterpolator` 전용 (`batch/interpolation/Keyframe` 이 실사용본) |

  ```bash
  grep -rn "MaskRleConverter"     backend/src/main | grep -v util/MaskRleConverter.java      # 0건
  grep -rn "CoordinateTransformer" backend/src/main | grep -v util/CoordinateTransformer.java # 0건
  grep -rn "TrackInterpolator"    backend/src/main | grep -v "util/TrackInterpolator.java"
  #  → batch/step/TrackInterpolationStep.java:13,72 (batch.interpolation 쪽) 과 주석뿐
  ```
  이 5개 파일에 대응하는 단위 테스트는 **31건 존재하며 전부 GREEN** 이다(`TrackInterpolatorTest` 7 · `MaskRleConverterTest` 12 · `CoordinateTransformerTest` 10 등). 즉 **테스트 통계가 "구현·검증 완료" 처럼 보이지만 제품에는 배선되지 않았다.**
  또 TC-TRACK-31 이 요구하는 "보간 진실원 단일 확인"의 **자동 구조 단언 테스트가 없어**, 누군가 dead 쪽을 import 해도 CI 가 막지 못한다.
- **재현/확인 경로**: 위 grep 3종. 추가로 `grep -rn "common.util.ShapeType\|common.util.Keyframe" backend/src/main` → 0건.
- **영향**: ①요구 기능(RLE 변환·좌표 회전) **미배선** — SAM2 mask 산출물의 RLE 직렬화·회전 라벨 처리가 필요해지는 시점에 "이미 있다"고 오판할 위험 ②동명이인 클래스로 인한 **오import 위험**(POLYGON 미지원 쪽을 import 하면 런타임 `UnsupportedOperationException`) ③테스트 수·커버리지 지표 왜곡 ④유지보수 비용(사문 코드 5파일 + 테스트 31건).
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①`MaskRleConverter`·`CoordinateTransformer` 는 **배선 계획을 확정**(어느 경로에서 쓸지)하거나, 계획이 없으면 대응 테스트와 함께 삭제 ②`common/util/{TrackInterpolator,ShapeType,Keyframe}` 는 삭제(진실원은 `batch/interpolation`) ③삭제 대신 존치한다면 **구조 단언 테스트**(클래스패스 스캔으로 `common.util.TrackInterpolator` import 0건 단언)를 추가해 TC-TRACK-31 을 자동화.

---

### [C-ISSUE-85] TC-PRESET-15 — 마스터 soft delete 후 프리셋 코드가 **식별정보를 전부 잃는다**(`labelName`·`code` 모두 null, `string[]` 에 null 원소)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md` 라벨 프리셋 절 — *"마스터에 매칭 안 되는 기존 코드(labelId null/비활성)는 오류 없이 **'미연결'**로 표시(자동 생성/삭제 없음)"*. FE 도 이를 전제로 미연결 칩에 *"legacy 라벨명 + '미연결' 배지(마스터에서 **재선택 유도**)"* 를 렌더한다(`frontend/src/features/preset/components/PresetCodeChip.tsx:18`). 재선택을 유도하려면 **어떤 라벨이었는지 보여야** 한다.
- **현재 동작(이슈 내용)**: `backend/src/main/java/kr/co/cudo/authoring/preset/service/PresetService.java:239-245`

  ```java
  private static PresetCodeView toCodeView(LsLabelPresetCode code, Map<Long, LabelMasterResponse> masters) {
      LabelMasterResponse master = code.getLabelId() == null ? null : masters.get(code.getLabelId());
      boolean linked = master != null;
      if (!linked) {
          // 미연결 — 오류 없이 legacy 코드로 노출.
          return new PresetCodeView(code.getLabelId(), code.getCode(), code.getCode(), null, false, false, false);
          //                                            ^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^ labelName 도 code 를 재사용
  ```
  그런데 **labelId 기반으로 생성된 행은 `LBL_CD` 를 저장하지 않는다**(`toSpecs:164-172` 가 `new LabelCodeSpec(id, null)`, DB 실측 `lbl_cd` NULL). 따라서 그 마스터가 나중에 soft delete 되면 `code`·`labelName` **둘 다 null** 이 된다.

  실측(마스터 41 을 `DELETE /v1/manage/labels/41` 후 프리셋 재조회):
  ```json
  "labelCodes": ["ZZTEST-C5A-RENAMED", null],
  "labelCodeOptions": [ {...}, {"labelId":41,"code":null,"labelName":null,"labelType":null,
                                "linked":false,"bboxEnabled":false,"polygonEnabled":false} ]
  ```
  FE 는 `<span>{code.labelName}</span>` 를 그대로 렌더하므로(`PresetCodeChip.tsx:31`) **이름 없는 '미연결' 칩**이 뜬다 — 운영자는 어떤 라벨을 재선택해야 하는지 알 수 없다. 또한 `labelCodes` 는 FE 타입 선언상 `string[]`(`frontend/src/features/preset/api.ts:33`)인데 **null 원소**가 실려 계약이 깨진다.
  참고: `labelId=null` 인 순수 레거시 행은 `lbl_cd` 가 있어 정상 표시된다(TC-PRESET-14 실측 `'ZZ-LEGACY-CODE'`) — **문제는 "연결됐다가 끊긴" 행에 한정**된다.
- **재현/확인 경로**:
  ```bash
  RT=<REVIEWER 토큰>
  curl -s -X POST http://localhost:18081/api/v1/manage/labels -H "Authorization: Bearer $RT" \
    -H 'Content-Type: application/json' -d '{"name":"TMP-X","color":"#AABBCC","type":"BBOX","sortNo":900}'   # → labelId
  curl -s -X POST http://localhost:18081/api/v1/manage/presets -H "Authorization: Bearer $RT" \
    -H 'Content-Type: application/json' -d '{"name":"TMP-P","labelIds":[<labelId>]}'
  curl -s -X DELETE http://localhost:18081/api/v1/manage/labels/<labelId> -H "Authorization: Bearer $RT"
  curl -s http://localhost:18081/api/v1/manage/presets -H "Authorization: Bearer $RT"
  # → labelCodes:[null], labelCodeOptions[0].labelName:null
  ```
- **영향**: 운영 복구 불능(어떤 라벨이 끊겼는지 화면·응답 어디에도 없음). `labelCodes: string[]` 계약 위반으로 FE 가 문자열 메서드를 호출하면 런타임 오류 가능(현재 `PresetCodeChip` 은 텍스트 렌더만 해서 빈칸으로 끝남). 보안 영향 없음.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①`toCodeView` 의 미연결 분기에서 `labelName` 폴백을 두어(`code != null ? code : "미연결 라벨 #" + labelId`) 최소한 labelId 를 노출 ②또는 `labelId` 연결 행에도 마스터 라벨명 스냅샷을 **표시 전용 폴백 컬럼**으로 남길지 정책 결정(단일 진실원 원칙과 상충하므로 "조회 실패 시 폴백" 용도로만) ③`labelCodes` 는 null 원소를 제외하거나 필드 자체를 deprecate(이미 `labelCodeOptions` 가 상위 호환) ④FE `PresetCodeChip` 에 이름 미상 시 `labelId` 표기 추가.

---

### [C-ISSUE-86] TC-PRESET-06/09(인접) — `DataIntegrityViolationException` 을 **무조건 "이미 다른 프리셋에 매핑된 이벤트입니다"** 로 변환

- **심각도**: LOW
- **기대 동작(기대효과)**: 409 응답 메시지는 실제로 위반된 제약을 반영해야 한다(이름 중복이면 이름 중복, 이벤트 중복이면 이벤트 중복).
- **현재 동작(이슈 내용)**: `preset/service/PresetService.java:174-181` · `:88-93`

  ```java
  private LsLabelPreset saveWithEventUniqueGuard(LsLabelPreset preset) {
      try { return presetRepository.saveAndFlush(preset); }
      catch (DataIntegrityViolationException e) {
          throw new CustomException(ErrorCode.CONFLICT, MSG_EVENT_CONFLICT, e);   // = "이미 다른 프리셋에 매핑된 이벤트입니다"
      }
  }
  ```
  `ls_label_preset` 에는 유니크 제약이 **2개**(`uk_ls_label_preset_evnt(evnt_type_cd)`, `uk_ls_label_preset_name(preset_nm)`)이고 `ls_label_preset_code` 에도 2개(`uk_ls_label_preset_code(preset_id,lbl_cd)`, `uk_ls_label_preset_code_lblid`)가 있는데, 어느 것이 깨져도 같은 문구가 나간다. 이름 중복은 `existsByPresetNm`(`:65-67`, `:81-83`)이 1선에서 잡지만 **그 검사와 insert 사이의 동시 요청(TOCTOU)** 은 DB 제약으로 떨어져 "이벤트" 메시지를 받는다. 운영자는 이름이 아니라 이벤트를 고치려다 계속 실패한다.
  ※ labelId 중복은 애그리거트가 dedup 하므로(§프리셋 단일 진실원 6번) API 경로로는 이 분기에 도달하지 않는다.
- **재현/확인 경로**: 동일 `name` 으로 `POST /v1/manage/presets` 를 동시 2건 발사(단일 요청 순차 실행에서는 `existsByPresetNm` 이 선행해 올바른 문구가 나온다 — 본 회차 실측 409 "이미 사용 중인 프리셋 이름입니다."). 정적으로는 위 catch 블록이 제약명을 보지 않음이 근거.
- **영향**: 오해를 유발하는 오류 메시지(사용성). 데이터 정합성·보안 영향 없음. 응답 코드(409)는 어느 경우든 옳다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `DataIntegrityViolationException` 의 root cause(`ConstraintViolationException#getConstraintName`)를 읽어 `uk_ls_label_preset_evnt` / `uk_ls_label_preset_name` 을 분기하고, 미상이면 중립 문구("프리셋 저장 중 제약 조건 위반")로 폴백한다. 제약명은 내부 스키마 정보이므로 **응답에 노출하지 말 것**(CWE-209).
