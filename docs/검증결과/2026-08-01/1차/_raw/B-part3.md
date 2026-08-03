# B 클러스터 part3 — B-5. 마킹 완료 브릿지 + B-6. MarkingService (40건)

> 대상: `docs/test-cases/B-batch-deidentify.md` §B-5(TC-BATCH-050~062, 13건) · §B-6(TC-BATCH-070~096, 27건)
> 검증일 2026-08-01 · 코드 기준 `56d30478`(qa-0801) · 스택 실기동(klid-backend:18081 `/api`, postgres `public`)

## 0. 환경 버전 격차 판정 — 이 담당 구간은 **격차 없음**(BLOCKED 0건)

`stack-bringup.md` 하단이 경고한 "backend 컨테이너가 구버전(V146)" 문제가 **B-5/B-6 에는 적용되지 않는다.** 근거(실측):

| 항목 | 값 | 근거 |
|---|---|---|
| 실행 중 jar 빌드시각 | `2026-07-30 17:49 UTC` | `docker exec klid-backend ls -la /app/app.jar` (컨테이너 TZ=UTC 확인) |
| `marking/` 최종 커밋 | `862ca6d8` = `2026-07-30 14:12 UTC`(23:12 KST) | `git log -1 --format='%h %ai' -- .../marking` |
| `batch/status/` 최종 커밋 | 동일 `862ca6d8` | 동상 |
| 환경격차 커밋 범위(`b2b44f0e..56d30478`)가 marking/batch 를 건드림? | **0건** | `git log b2b44f0e..56d30478 -- .../marking .../batch` → 무결과 |

즉 **jar 빌드가 담당 구간 최종 커밋보다 뒤**이고 이후 변경이 0건이므로, 아래 실동작 판정은 전부 소스와 동일한 코드에 대한 것이다. 런타임 교차검증도 일치: H11 신규 필드 `batchTriggered`/`batchSkipReason` 응답 노출, V142 부분 유니크 인덱스(`uk_ls_marking_raw_actvtn`) DB 존재, H10 `manualFrameIndexLimit` 1초 마진(상한 930) 모두 실측됨.

## 1. 검증 셋업 (재현 가능)

```bash
# 토큰
curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
  -d '{"role":"REVIEWER","channel":"INTERNAL"}'    # sub=1001
  # WORKER: sub=2001 / PORTAL_USER 도 동일 엔드포인트
# 마킹 호출
curl -s -X POST http://localhost:18081/api/v1/videos/{rawSn}/markings \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":30}'
```

사용한 픽스처(기존 DB 데이터, 수정 없음) + 신규 업로드 3건(`/v1/dev/autolabel-test`):

| rawSn | stage(`ls_data_raw.data_stts_cd`) | `de_ident_yn` | 작업상태 row | 길이/파일 | 용도 |
|--:|---|:--:|---|---|---|
| 9103 | MARKING_READY | Y | 없음→(테스트로 ASSIGNED INSERT) | 30s | 음성검증 전량 + TC-053 |
| 9104 | MARKING_READY | **N** | — | 30s | TC-075 |
| 9105 | MARKING_READY | Y | — | `evnt_type_cd` 공백 | TC-077 |
| 9106 | **COMPLETED** | Y | — | — | TC-076 |
| 9109 | **FAILED** | Y | FAILED | — | TC-076 |
| 9110 | MARKING_READY | Y | **APPROVED** | 30s | TC-057/058/061/062 |
| 9112 | MARKING_READY | **F** | APPROVED | — | TC-075 |
| 9113 | MARKING_READY | Y | 없음 | 길이 null + **파일 부재** | TC-079/088/094/095 |
| 9114 | MARKING_READY | Y | APPROVED | 30s, **fps 29.97** | TC-081/090 |
| **30**(신규) | MARKING_READY→COMPLETED | Y | 없음 | 5s | TC-091 동시성 |
| **33**(신규) | MARKING_READY | Y | **ASSIGNED**(실배정) | 5s | TC-053/071 |
| **38**(신규) | MARKING_READY | Y | 없음 | 5s | TC-096 |

> DB 는 신규 INSERT 만 했다(9103 작업상태 row 1건 + 업로드 3건). 기존 행은 수정하지 않았다.

---

## 2. B-5. 마킹 완료 브릿지 (동시성·가드) — 13건

| ID | 판정 | 근거 확인 | 상세 |
|---|:--:|---|---|
| TC-BATCH-050 | PASS | [정적] | `MarkingBatchBridge.java:98-103` — `findById` empty → WARN + `skipped(REASON_VIDEO_NOT_FOUND)` + return. 단위테스트 `MarkingBatchBridgeTest#영상_행_미존재시_스킵`. ⚠ **API 경로로는 도달 불가** — `MarkingGuards.requirePreconditions` 가 먼저 404 를 내고 이벤트가 발행되지 않는다(publisher 전수: `MarkingService.java:237` **단 1곳**). 방어적 이중화로 유효 |
| TC-BATCH-051 | PASS | [정적] | `:81-82,109-114` `SKIP_BATCH_STAGES={PROCESSING,COMPLETED}` → `skipped(REASON_STAGE_ALREADY_RUN)`. 테스트 2건(`배치_COMPLETED_영상에_마킹이벤트_재발생시…`, `배치_PROCESSING_중…`). API 도달 불가(412 선행) — 방어적 이중화 |
| TC-BATCH-052 | PASS | [정적] | `:116-123` `!"Y".equals(deIdntfYn)` → `REASON_NOT_DEIDENTIFIED`. 테스트 2건(deIdntfYn=N / =F). API 도달 불가(412 선행) |
| TC-BATCH-053 | PASS | [실동작] | rawSn=33 에 **실배정**(`POST /v1/assignments`)으로 `ls_raw_data_status=ASSIGNED(ver=1)` 생성 후 WORKER 가 MANUAL 마킹 → `201 batchTriggered=true`, 로그 `[MarkingBatchBridge] enqueued rawSn=33` → `[AsyncBatchRunner] starting batch rawSn=33`, 이후 `ver=3`(BATCH_QUEUED→…→ASSIGNED 복귀) |
| TC-BATCH-054 | PASS | [실동작] | rawSn=9113·30 은 작업상태 row **부재** 상태에서 마킹 → `batchTriggered=true` + `ls_raw_data_status` 행이 **신규 생성**됨(9113: 조회 0행 → 마킹 후 1행). tx1 false → `tryCreateBatchQueuedRow` 경로 확인 |
| TC-BATCH-055 | PASS | [정적] | `:138-145` `catch (DataIntegrityViolationException)` → `concurrent row creation … skipping`. 테스트 `MarkingBatchBridgeTest#동시_row생성경합_tx2가_DataIntegrityViolationException_던지면_잡아서_스킵` + `BatchTransitionServiceRowCreationIT#동시_2스레드_row부재_rawSn_클레임`. ⚠ **실동작 재현 불가** — V142 부분 유니크가 *마킹 생성 단계*에서 먼저 직렬화해 동시 5요청에도 이벤트가 1건만 발행된다(§TC-091 실측). 사실만 기록 |
| TC-BATCH-056 | PASS | [정적] | `LsRawDataStatusRepository.java:86-91`(`transitionByBatchIfNotBlocked`)·`:60-68`(`transitionToBatchQueuedIfNotSkipped`) 단일 조건부 `UPDATE VERSIONED … WHERE dataSttsCd NOT IN :skip` → 영향행수 1건만. 테스트 `MarkingBatchBridgeTest#동일_rawSn_마킹이벤트_2회_동시발생시…` + `LsRawDataStatusRepositoryClaimIT` |
| TC-BATCH-057 | PASS | [실동작] | 9110(work=APPROVED) 마킹 → 로그 `[MarkingBatchBridge] batch already claimed/in-progress or review-owned rawSn=9110 — skipping` + `[Marking] batch not triggered rawSn=9110 markingSn=25`. 이후 `ls_raw_data_status=APPROVED`·`ls_data_raw=MARKING_READY` **양쪽 불변** |
| TC-BATCH-058 | PASS | [실동작] | 위 9110 케이스가 곧 이 경로다 — tx1 false(APPROVED 가 skip 집합) → tx2 `existsById=true` → false 멱등 스킵(행 생성·전이 0). `BatchTransitionService.java:299-320` |
| TC-BATCH-059 | PASS | [정적] | `BatchTransitionService.java:321-332` `saveAndFlush` + 예외 미포획 전파(REQUIRES_NEW 롤백). `BatchTransitionServiceRowCreationIT` 동시 2스레드 → 1건 true·나머지 예외없이 false·DB BATCH_QUEUED 1건 |
| TC-BATCH-060 | PASS | [정적] | `:166-168` `sanitize()` 가 `\n`/`\r` 제거, 적용 지점 2곳(`dataSttsCd` `:111`, `deIdntfYn` `:120`) — 그 외 로그 인자는 `Long rawSn` 이라 주입 표면 없음. ⚠ **전용 테스트 0건**(`MarkingBatchBridgeTest` 에 CRLF 케이스 없음) + API 도달 불가라 실효 반증 불가 → B-ISSUE-43 |
| TC-BATCH-061 | PASS | [실동작]+[정적] | `:66-71` `SKIP_STATUSES = {BATCH_QUEUED,PROCESSING,COMPLETED} ∪ BatchTransitionService.REVIEW_OWNED_STATUSES` 상수 합성(문자열 재정의 없음). 실동작은 9110(APPROVED) 차단으로 확인 |
| TC-BATCH-062 | PASS | [실동작] | 9110 응답 실측: `201` + `"batchTriggered":false,"batchSkipReason":"검수 진행/완료(또는 반려) 상태이거나 이미 배치가 큐잉되어 배치를 시작하지 않았습니다."`(고정 상수, DB/외부 문자열 미포함). 정상 트리거 시 `batchTriggered:true, batchSkipReason:null`(9103/30/33/38). 브리지 미실행 시 두 필드 null 분기는 `MarkingService.java:135-138` 정적 확인(실경로 미도달) |

### B-5 부가 반증 결과

- **AFTER_COMMIT 시맨틱**: `@TransactionalEventListener(AFTER_COMMIT)`(`:92`)가 마킹 커밋 후 **같은 요청 스레드**에서 동기 실행됨을 로그 타임스탬프로 실증(`created rawSn=33 19:14:04.339` → 같은 `http-nio-8080-exec-*` 스레드에서 `handling marking completed` → `enqueued`). **롤백 시 미발화**는 실동작으로 유도할 경로가 없었다 — `publishEvent`(`:237`) 이후 남은 코드가 `MarkingResponse.from`(예외 자체 흡수)뿐이라 "발행 후 롤백" 시나리오를 만들 수 없다. 스프링 계약 + 코드로 PASS 처리(케이스 표에 독립 항목 없음).
- **ThreadLocal 오염**: `MarkingBatchTriggerReport.begin()`(`MarkingService.java:108`)이 진입마다 `HOLDER.remove()` 하므로 precheck 예외로 `consume()` 이 생략돼도 다음 요청에 이월되지 않음. `runAsync` 거부 시 예외 누출 우려는 `AsyncConfig.java:44` **CallerRunsPolicy** 라 성립하지 않음(대신 아래 B-ISSUE-42 의 증폭 요인).
- **동시 마킹 완료 → 중복 배치 트리거**: 마킹 생성 자체가 V142 로 직렬화되므로 브릿지 이중 발화를 실동작으로 만들 수 없었다. 즉 이 경로는 **2중 방어**(V142 → 조건부 UPDATE)이며 실측상 배치는 1회만 기동(`AsyncBatchRunner starting batch rawSn=30` 1건).

---

## 3. B-6. MarkingService (자동/수동, 경계값, 인가) — 27건

| ID | 판정 | 근거 확인 | 실측 |
|---|:--:|---|---|
| TC-BATCH-070 | PASS | [실동작] | 토큰 없이 POST /v1/videos/9103/markings → `401 UNAUTHORIZED "인증이 필요합니다."` (Security 필터가 `MarkingGuards` 이전에 차단) |
| TC-BATCH-071 | PASS | [실동작] | REVIEWER(sub=1001)가 9103·9110·9113·30·38 전건 통과(배정 무관) |
| TC-BATCH-072 | PASS | [실동작] | 미배정 WORKER(2001) → 9103 `403 FORBIDDEN "본인에게 배정된 영상의 마킹만…"`, **미존재 rawSn=999999 도 동일 403**(REVIEWER 는 같은 rawSn 에 404) → 존재 여부 미노출 확인 |
| TC-BATCH-073 | PASS | [실동작] | A/B 대조: 9113(길이 null + 파일 부재)에 **REVIEWER** AUTO → `BrampVideoProbe [Video][Probe] ffprobe empty output` 로그 발생 / **미배정 WORKER** 동일 요청 → 403 이고 같은 창의 프로브 로그 **0건**(`grep -c` = 0). 인가-전-프로브 보장 |
| TC-BATCH-074 | PASS | [실동작] | REVIEWER + rawSn=999999 → `404 NOT_FOUND "영상을 찾을 수 없습니다."` |
| TC-BATCH-075 | PASS | [실동작] | 9104(`de_ident_yn='N'`) → `412 PRECONDITION_FAILED "비식별이 완료된 영상에서만 마킹할 수 있습니다."` / 9112(`'F'`) → **동일 412**(신고본도 차단) |
| TC-BATCH-076 | PASS | [실동작] | 9106(stage=COMPLETED) → `412 "이미 처리된 영상은 재마킹할 수 없습니다."` / 9109(stage=FAILED) → **동일 412** |
| TC-BATCH-077 | PASS | [실동작] | 9105(`evnt_type_cd` 공백) → `400 INVALID_INPUT "이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다."` |
| TC-BATCH-078 | PASS | [실동작] | `intervalFrames` 생략/`0`/`-5` 3케이스 모두 `400 "자동 모드에서 intervalFrames 는 1 이상이어야 합니다."` |
| TC-BATCH-079 | PASS | [실동작] | 9113(VDO_LEN null + `video.duration_ms` 메타 부재 + 파일 부재로 프로브 실패) → `400 "영상 길이를 확인할 수 없어 자동 마킹을 생성할 수 없습니다."` — backstop 실발화(퇴화 1건 생성 아님) |
| TC-BATCH-080 | PASS | [실동작] | rawSn=30(dur=5s, fps=30, interval=30) → `totalFrames=150`, marks `0,30,60,90,120` **5건**(150 미포함). off-by-one 정확 |
| TC-BATCH-081 | PASS | [실동작] | 9114(`video.fps=29.97`, dur=30, interval=300) 마킹 실 데이터: `round(30×29.97)=899` → marks `0,300,600`(900 제외), 타임스탬프 `00:00/00:10/00:20`(=`300/29.97=10.01` 절단). `Math.round` 정책 실증 |
| TC-BATCH-082 | PASS | [실동작] | `ls_marking.fps` 실적재: AUTO(30→`30`), MANUAL(33→`30`), 29.97 영상(`29.97`). AUTO/MANUAL 양쪽 pin 확인 |
| TC-BATCH-083 | PASS | [실동작] | MANUAL + `marks` 생략/`[]` → `400 "수동 모드에서 marks 는 필수입니다."` |
| TC-BATCH-084 | PASS | [실동작] | `"X"`/`"auto"`/`" AUTO "` 3건 모두 `400 "mode 는 AUTO 또는 MANUAL 이어야 합니다."`(대소문자·trim 미허용 fail-closed). `""` 는 `@NotBlank` 로 `400 "mode: mode 는 필수입니다."` |
| TC-BATCH-085 | PASS | [실동작] | 생성 성공(201) 직후 `[MarkingBatchBridge] handling marking completed rawSn=…` 로그 → `MarkingCompletedEvent` 발행 확인(9103/9110/9113/30/33/38 전건) |
| TC-BATCH-086 | PASS | [실동작] | 응답 `eventName` = 영상 `evnt_type_cd` 그대로(30/33/38 → `EV02000201`, 9103/9110 → `INTRUSION`). 요청에 이벤트명 미포함 |
| TC-BATCH-087 | PASS | [실동작] | `self` 프록시 경유가 아니면 `@Transactional` 미적용 → AFTER_COMMIT 리스너가 발화하지 않는다. 실측상 **모든 성공 케이스에서 브릿지가 발화**했으므로 프록시 경유 + 실 트랜잭션 커밋 확인 |
| TC-BATCH-088 | PASS | [실동작]+[정적] | `VideoDurationResolver.java:74-91` — ①`VDO_LEN_SEC` ②`video.duration_ms` 메타 ③직접 프로브 3단. 9113 에서 ①②가 비어 ③이 실제로 기동(프로브 로그)했고 그마저 실패해 null 반환 → backstop. `@Transactional(propagation=NOT_SUPPORTED)` 로 커넥션 미보유 확인 |
| TC-BATCH-089 | PASS | [실동작] | PORTAL_USER 토큰 → `403 FORBIDDEN "권한이 없습니다."`(`@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 발화 — 서비스 진입 전) |
| TC-BATCH-090 | PASS | [실동작] | 9114(활성 PENDING 마킹 존재) 재요청 → `409 CONFLICT "이미 진행 중인 마킹이 있습니다…"` (`requireNoActiveMarking` 1선) |
| TC-BATCH-091 | PASS | [실동작] | rawSn=30 에 **동시 5요청** → `201 ×1 / 409 ×4`, **500 0건**. 로그에 `[Marking] concurrent duplicate rejected rawSn=30` **4건**(= 4건 모두 서비스 1선이 아니라 **V142 인덱스 위반 catch** 경로로 409 변환). DB `ls_marking WHERE raw_sn=30` **1행**(부분 저장 없음) |
| TC-BATCH-092 | PASS | [실동작] | `frameIndex` 10 중복 2건 → `400 "중복된 마킹 시점입니다: frameIndex=10"` |
| TC-BATCH-093 | PASS | [실동작] | 9103(dur=30, fps=30) 상한 = `round(30×30)+ceil(30)=930`. `frameIndex=930` → **400**(배타 상한), `999999999` → 400. 응답 메시지에 상한값 930 노출로 공식 실증 |
| TC-BATCH-094 | PASS | [실동작] | 9113(길이 미상) MANUAL `[{999999999},{0}]` → **201** + WARN `[Marking] duration unknown — manual mark upper-bound check skipped rawSn=9113 marks=2`. 같은 영상에 음수·중복은 그대로 400(전량 스킵 아님) |
| TC-BATCH-095 | PASS | [실동작] | 9113 MANUAL 요청(19:11:14) 시 `BrampVideoProbe` 로그 **미발생** — 직전 AUTO 요청(19:11:04)에서는 발생. `resolveDurationSecWithoutProbe` 실사용 확인 |
| TC-BATCH-096 | PASS | [실동작] | rawSn=38(dur=5) + `intervalFrames=999999999` → **201**, marks `[{frameIndex:0,timestamp:"00:00"}]` 1건. **B-ISSUE-23 미해소 확정**(케이스가 현재 동작을 고정하므로 PASS) |

---

## 4. 판정 집계

| 구간 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| B-5 (050~062) | 13 | 13 | 0 | 0 | 0 | 0 | 0 |
| B-6 (070~096) | 27 | 27 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **40** | **40** | 0 | 0 | 0 | 0 | 0 |

> 카탈로그 케이스 자체는 40건 전부 기대결과대로 동작한다(31건 실동작·9건 정적+단위테스트). 다만 **케이스가 커버하지 않는 구간**에서 아래 신규 결함 3건을 반증 과정에서 발견했다.
> **근거 드리프트**: `file:line` 40건 대조 결과 **불일치 0건**(TC-088/093/094/095/089 는 애초 라인 대신 메서드명·어노테이션 표기).

---

## 5. 신규 이슈

> ⚠ **ID 충돌 주의**: 병렬 분할 규약에 따라 part3 은 41 번부터 부여했으나, `UNCERTAINTIES.md` 의 "미해소 이월" 표에 **1차(2026-07-25) 회차의 `B-ISSUE-41`(레거시 프레임 백필)·`B-ISSUE-42`(오토라벨 일괄저장)** 가 이미 존재한다. 병합 시 회차 접두(예: `B-ISSUE-2026-08-01-41`)로 구분하거나 번호 재부여가 필요하다.

### [B-ISSUE-41] TC-BATCH-057/062 파생 — 브릿지가 배치를 skip 하면 **영구 고아 활성 마킹**이 남아 그 영상이 마킹 409 로 영구 잠긴다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 브릿지가 배치를 트리거하지 않기로 정당하게 판단했다면(검수 소유 상태·이미 큐잉), 그 마킹은 소비될 일이 없으므로 **종결 처리되거나 애초에 생성되지 않아야** 한다. V142 부분 유니크 인덱스의 도입 목적 자체가 *"영상당 마킹이 2건 이상 쌓이면 배치는 최신 1건만 VLM 에 위탁하고 나머지는 영원히 PENDING 인 고아가 된다"* 를 막는 것인데(`V142__add_ls_marking_active_unique.sql` 헤더), 현재 skip 경로는 **바로 그 고아를 제도적으로 생성**한다. 또한 `LS_MARKING` 활성 마킹은 후속 마킹을 409 로 막으므로, 고아가 종결되지 않으면 그 영상은 **다시는 마킹할 수 없다.**
- **현재 동작(이슈 내용)**: 마킹은 커밋된 뒤(`MarkingService.java:227-237`) AFTER_COMMIT 브릿지가 skip 을 결정한다 — 즉 **마킹 저장과 배치 트리거 판단이 분리**돼 있어 skip 이어도 `LS_MARKING` 행은 남는다.
  ```java
  // MarkingBatchBridge.java:146-154
  if (!claimed) {
      log.warn("[MarkingBatchBridge] batch already claimed/in-progress or review-owned rawSn={} — skipping", rawSn);
      MarkingBatchTriggerReport.skipped(MarkingBatchTriggerReport.REASON_ALREADY_CLAIMED);
      return;   // ← 방금 커밋된 PENDING 마킹을 종결시키지 않는다
  }
  ```
  `PENDING → VLM_REQUESTED/VLM_FAILED` 전이는 **오직 VLM 단계**(`VlmMarkingTxService.persistVlmRequested/markVlmFailedIfRequested`, `VlmResultService:224`)에서만 일어나며, 그 단계는 배치가 돌아야 도달한다. 만료·정리 스윕은 **존재하지 않는다**(`markVlmFailed()` 호출부 전수 = 위 2곳).
  실측(rawSn=9110, stage=`MARKING_READY` · work=`APPROVED`):
  ```
  POST /v1/videos/9110/markings → 201 {"markingSn":25, "batchTriggered":false,
     "batchSkipReason":"검수 진행/완료(또는 반려) 상태이거나 …"}
  로그: [MarkingBatchBridge] batch already claimed/in-progress or review-owned rawSn=9110 — skipping
  DB : ls_marking(25, raw_sn=9110, stts_cd='PENDING')   ← 이후 아무도 전이시키지 않음
  POST /v1/videos/9110/markings (재시도) → 409 "이미 진행 중인 마킹이 있습니다…"
  POST /v1/videos/9110/batch/retry     → 409 "배치가 실패(FAILED)한 영상만 재처리할 수 있으며…"
  ```
  즉 **복구용 API 가 하나도 없다**(재처리는 stage/work 중 하나가 `FAILED` 여야 하는데 여기는 `MARKING_READY`/`APPROVED`).
- **재현/확인 경로**: 도달 가능한 정상 동선이 `MarkingBatchBridge` Javadoc(`:56-60`)에 이미 명시돼 있다 — *"배치가 한 번도 안 돈 채 반려된 영상(stage=MARKING_READY, work=REJECTED — 배정→검수제출→반려로 만들 수 있다)"*. 그 영상에 마킹을 1회 하면 이후 영구 409.
  ```bash
  # 위 실측 그대로 (9110 = MARKING_READY + APPROVED)
  curl -X POST $API/v1/videos/9110/markings -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":100}'   # 201, batchTriggered=false
  curl -X POST $API/v1/videos/9110/markings ... # 409 (영구)
  ```
  ```sql
  -- 현재 고아 실태(운영 데이터에도 이미 존재)
  SELECT m.marking_sn, m.raw_sn, m.stts_cd, r.data_stts_cd stage, s.data_stts_cd work
    FROM ls_marking m JOIN ls_data_raw r ON r.raw_sn=m.raw_sn
    LEFT JOIN ls_raw_data_status s ON s.raw_data_id=m.raw_sn
   WHERE m.stts_cd IN ('PENDING','VLM_REQUESTED');
  -- 실측: 9110/9111/9108/9114 등이 (stage=MARKING_READY, work=APPROVED) 조합으로 PENDING 고착
  ```
- **영향**: 기능 — 해당 영상은 **마킹 재수행 불가(영구 409)**, DB 직접 수정 외 복구 수단 없음. 데이터 정합 — `LS_MARKING` 에 소비되지 않는 활성 행이 무기한 누적되고, V142 가 방지하려던 "고아 활성 마킹"이 정책적으로 재생산된다. 부가로 `APPROVED`(검수 완료) 영상에 새 마킹 행이 생성되는 것 자체가 완료 산출물의 부수 변경이다. (CWE-459 불완전 정리 / CWE-667 계열 자원 고착)
- **수정 방향(제안)**: 택1 —
  ① **입구에서 막기**: `MarkingGuards.requirePreconditions` 에 "작업 상태가 `REVIEW_OWNED_STATUSES` 면 412" 게이트를 추가해 브릿지가 skip 할 마킹은 애초에 만들지 않는다(응답 코드가 201→412 로 바뀌므로 FE 문구 동반 수정). 브릿지의 skip 은 진짜 동시성 경합 전용으로 축소된다.
  ② **출구에서 회수**: 브릿지 skip 분기에서 방금 생성된 마킹(`event.markingSn()` — 이벤트에 이미 실려 있다)을 별도 `REQUIRES_NEW` 트랜잭션으로 `VLM_FAILED`(또는 신설 종결코드 `SKIPPED`)로 내려 활성 집합에서 제거한다. `LsMarking.markVlmFailed()` 는 상태 무관 대입이므로 `PENDING` 한정 가드를 함께 둔다.
  > ①이 근본적이다(무의미한 마킹 자체를 만들지 않음). ②만 하면 "201 인데 마킹이 곧바로 실패 종결"이라는 또 다른 혼란이 남는다.

### [B-ISSUE-42] TC-BATCH-078/096 파생 — AUTO 마킹의 **생성 marks 개수에 상한이 없다**(MANUAL 은 20,000 캡, CWE-770)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 단일 요청이 만들어내는 산출물 크기는 유계여야 한다. MANUAL 경로는 이미 그렇게 설계돼 있다 — `MarkingRequest.marks` 에 `@Size(max = 20000, message = "한 번에 처리 가능한 마킹 수 초과 (최대 20000)")` 가 **CWE-770 방어 목적으로 명시**돼 있다. AUTO 는 같은 산출물(`MARK_CN`)을 서버가 생성하므로 동일 상한이 걸려야 한다.
- **현재 동작(이슈 내용)**: AUTO 는 `intervalFrames >= 1` **하한만** 검증하고 결과 개수를 보지 않는다.
  ```java
  // MarkingService.java:196-198, 276-282
  if (req.intervalFrames() == null || req.intervalFrames() <= 0) { throw INVALID_INPUT; }
  ...
  int totalFrames = (int) Math.round(durationSec * fps);
  for (int frameIndex = 0; frameIndex < totalFrames; frameIndex += intervalFrames) { marks.add(...); }
  ```
  실측(DB 기존 행, rawSn=9111 · `VDO_LEN_SEC=1200` · fps 30 · `intervalFrames=1`):
  ```
  marking_sn=22 → mark_cn 길이 1,464,891 byte, marks 36,000건 (단일 TEXT 컬럼)
  ```
  1시간(3,600s) 30fps 영상이면 108,000건 ≈ 4.4 MB 가 한 요청으로 생성된다. `@Size` 상한(20,000)의 5배를 AUTO 로 우회할 수 있다.
- **재현/확인 경로**:
  ```bash
  # 긴 영상(예: 1200s)에 intervalFrames=1
  curl -X POST $API/v1/videos/9111/markings -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":1}'
  ```
  ```sql
  SELECT marking_sn, frme_intv_nocs, length(mark_cn),
         (length(mark_cn)-length(replace(mark_cn,'frameIndex','')))/10 AS mark_cnt
    FROM ls_marking WHERE raw_sn = 9111;   -- 실측 1464891 / 36000
  ```
- **영향**: 가용성/자원(CWE-770, OWASP API4). ①`ArrayList` 36k~108k 엔트리 + Jackson 직렬화가 요청 스레드에서 수행 ②`MARK_CN` 단일 TEXT 에 MB 급 적재 ③**증폭이 배치까지 전파** — `FfmpegFrameExtractor` 가 mark 당 1프레임을 뽑고(`mark-based extracted rawSn=30 frames=5` 로 1:1 확인) VLM 위탁 페이로드에도 marks 가 실린다. 즉 마킹 1회로 수만 회 ffmpeg seek + 수만 프레임 파일이 생성된다. ④`AsyncConfig.java:38-44` 의 `batchAsyncExecutor` 는 core 2/max 4/queue 50 에 **`CallerRunsPolicy`** 라, 큐 포화 시 이 대형 파이프라인이 **AFTER_COMMIT 리스너 안(=Tomcat 요청 스레드)에서 동기 실행**되어 마킹 API 응답시간이 무한정 늘어난다.
- **수정 방향(제안)**: `MarkingService.generateAutoMarks` 에 `MarkingRequest.marks` 의 `@Size` 와 **동일 상수**(20,000)를 공유 상수로 뽑아 상한 검증을 추가한다 — 산출 예상 개수 `ceil(totalFrames / intervalFrames)` 를 루프 **이전에** 계산해 초과 시 `INVALID_INPUT`(권장 최소 `intervalFrames` 를 메시지에 안내). 겸사겸사 B-ISSUE-23(intervalFrames 상한 미검증)도 같은 지점에서 "결과 0/1건 퇴화" 경고와 함께 정리 가능하다.

### [B-ISSUE-43] TC-BATCH-050/051/052/060 — 브릿지 가드 4종이 **마킹 API 경로에서 도달 불가**한데 CRLF 정제에는 테스트가 없다
- **심각도**: LOW
- **기대 동작(기대효과)**: 방어적 이중화 코드라도 "언제 발화하는지"가 명확해야 하고, 보안 정제(CWE-117)는 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: `MarkingCompletedEvent` 의 publisher 는 `MarkingService.java:237` **단 1곳**이고, 그 앞의 `MarkingGuards.requirePreconditions`(`MarkingGuards.java:79-96`)가 이미 ①영상 존재 ②`de_ident_yn='Y'` ③`stage == MARKING_READY` 를 강제한다. 따라서 브릿지의 `REASON_VIDEO_NOT_FOUND`(`:99-103`)·`REASON_STAGE_ALREADY_RUN`(`:109-114`)·`REASON_NOT_DEIDENTIFIED`(`:116-123`) 세 분기와, 그 안에서만 쓰이는 `sanitize()`(`:166-168`)는 **API 로 도달할 수 없다**(실측: 9104/9106/9109/9112 모두 마킹 단계 412 에서 종료, 브릿지 로그 미발생). 그리고 `MarkingBatchBridgeTest` 에 CR/LF 주입 케이스가 없다(`grep sanitize|\\n|CRLF` 무결과).
- **재현/확인 경로**: `grep -rn "new MarkingCompletedEvent" backend/src/main` → 1건. 9106(stage=COMPLETED) 마킹 시도 → 412 이며 `docker logs klid-backend | grep MarkingBatchBridge` 에 해당 rawSn 없음.
- **영향**: 보안 회귀 감지 불가(CWE-117 정제가 조용히 제거돼도 테스트가 잡지 못함) + 도달 불가 코드에 대한 오해(향후 다른 publisher 추가 시 이 가드가 유일한 방어선이 되는데 검증 자산이 없음).
- **수정 방향(제안)**: `MarkingBatchBridgeTest` 에 `dataSttsCd = "PROCESSING\r\n[FAKE] injected"`, `deIdntfYn = "N\nadmin"` 을 주입해 로그 출력에 개행이 없음을 단언하는 케이스 2건을 추가한다(코드 수정 불필요). 아울러 세 가드의 Javadoc 에 "현재 publisher 는 1곳이며 이 분기들은 향후 publisher 추가 대비 방어선"임을 명시.

---

## 6. 부수 관찰 (결함 아님 / 정보성)

1. **배치 실패 영상은 마킹 API 가 영구히 닫힌다** — 배치 실패 시 stage 가 `FAILED` 로 바뀌므로 `requirePreconditions` 가 412 를 낸다(9109 실측, 본 검증 중 9103·9113 도 동일 상태가 됨). 복구는 `POST /v1/videos/{rawSn}/batch/retry` 뿐이며 이는 `MarkingBatchBridge` Javadoc(`:50-55`)이 명시한 의도된 설계다. B-ISSUE-41 과 달리 **복구 API 가 존재**하므로 결함으로 보지 않는다.
2. **검증 중 생성한 데이터**(다음 회차 참고): 신규 영상 `rawSn=30/33/38`(vmsClipId `QA0801-B3-CONC`/`-WORKER`/`-TC096`), 신규 마킹 `marking_sn=25(9110), 26(9113), 27(9103), 28(30), 33(33), 38(38)`, `ls_raw_data_status` 신규 행 `9103`(테스트용 ASSIGNED INSERT, 이후 배치 실패로 FAILED). 기존 행 수정·삭제는 없음.
3. **YOLO 는 이 환경에서 mock 응답** — `mockReason=weights_missing`(ai-server 가중치 미탑재). B-5/B-6 판정에는 영향 없음(마킹 단계는 ai-server 를 타지 않음).
4. **VLM 실왕복 확인** — rawSn=30 마킹 → `[Batch][VlmTimeseries] describe submit … request_id=bc2d9a2b` → mock-server 콜백 `[Webhook][Vlm] result applied … markingsTransitioned=1` → 마킹이 `PENDING→VLM_REQUESTED→VLM_COMPLETED` 로 정상 종결. **즉 배치가 도는 정상 경로에서는 고아가 생기지 않는다** — B-ISSUE-41 은 오직 skip 경로 전용이다(대조군 확보).
