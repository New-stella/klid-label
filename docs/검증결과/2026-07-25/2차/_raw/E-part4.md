# E 클러스터 part4 (E-6·E-7) 2차 검증 결과

> 대상: `docs/test-cases/E-augment-resolution-export-meta.md` §E-6(23) + §E-7(18) = **41건**
> 환경: 로컬 도커 스택 · backend `localhost:18081`(HEAD `ca3c712b`) · PG `:5432`(스키마 `public`)
> 검증 시각: 2026-07-31 04:00~04:15 KST · backend 재기동/재빌드/빌드·테스트 실행 **0회** · 소스 수정 **0건**
> 폐기(`~~취소선~~`) 행: E-6·E-7 구간에는 **없음**(폐기는 E-5 이전 TC-RESL 구간에만 존재 — 집계 영향 없음)

## 집계

| 섹션 | 검증 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| E-6 Export 오케스트레이션 | 23 | 22 | 1 | 0 | 0 | 0 | 0 |
| E-7 Export JSON 포맷 | 18 | 17 | 0 | 1 | 0 | 0 | 0 |
| **합계** | **41** | **39** | **1** | **1** | **0** | **0** | **0** |

근거 확인 내역: **[실동작] 30건 · [정적] 11건**
신규 이슈 **2건** — HIGH 1 / MEDIUM 1

---

## 1차 이슈 해소 대조

| 1차 항목 | 1차 판정 | 2차 실측 | 결론 |
|---|---|---|---|
| **`E-ISSUE-22` — `FrameSource` DEIDENTIFIED 의 rawBase 폴백(1차 최고위험, CWE-359 fail-open)** | HIGH 미해소 | `FrameSource.java:75` 가 `Path base = (kind == ORIGINAL) ? rawBase : deidBase;` **단일 base** 로 되돌아왔고(구 `candidateBases = {deidBase, rawBase}` 삭제), DEIDENTIFIED 분기는 `StorageSubtreePolicy.verifyDeidentifiedFile` 단일 판정기 + `toRealPath` 로 서브트리(`frames/deid`)까지 강제(`:89-101`). ORIGINAL 분기도 역방향 혼입을 `isDeidentifiedArtifact` 로 차단(`:126-130`). 회귀 가드 `FrameSourceTest` 19건(동일 base 문자열·심링크 바꿔치기·TOCTOU 포함) | **✅ 해소** |
| 〃 실측 반증 | — | **전 export 버전 폴더 md5 전수 스윕**: `orgnl/*.jpg` 와 `deid/*.jpg` 가 동일 해시인 쌍 **0건**(스캔 대상 = `/app/storage/raw/seed/*/v*/`, rawSn 126·132·136·146·152·153·156·173 의 전 버전) | **✅ PII 격리 실증** |
| **`UNCERTAINTIES #19` — `DatasetExportWriter`/`TxService` 미열람** | 미확인 | 전 메서드 `@Transactional(REQUIRES_NEW)` 실측(`DatasetExportTxService:100,173,186,192,224,242,257,280`), `finalizeUnlessUnderDeidentReport`(`:224-239`) 가 RAW 잠금 하 재판정 → 차단 시 export 행 `deleteById`, `claimForRetry` 조건부 UPDATE(`LsDatasetExportRepository` native UPDATE) | **✅ 해소** |
| **"E-32 dead-letter 프로덕션 호출자 0건"** | — | 1차 `ISSUES.md` 에 `E-ISSUE-32` 는 **존재하지 않음**. 지시의 취지에 해당하는 건은 `E-ISSUE-06`(증강 `markDeadLetter()` 호출자 0건 — **E-part1/E-2 스코프**). export 축의 대응물인 `DatasetExportFailureRecoverer` 는 **프로덕션 도달 확인**: Quartz `DatasetExportFailureRecoveryJob` 이 900초 주기로 실제 tick(`03:00:32 / 03:15:32 / 03:30:32 / 03:45:32 / 04:00:32` — `[DatasetExportRecovery] no retryable failed export`) | **본 파트 대상 아님 / export 축은 도달 가능** |
| `E-ISSUE-41` — 파생 orgnl·deid 프레임 경로 동일 → 2벌 바이트 동일·anonymity 오표기 | HIGH | 정책 A 로 전환됨. 파생(rawSn 178) 프레임은 `SRC_FILE_PATH_NM=null`, `DE_IDNTF_SRC_FILE_PATH_NM` 만 보유 → `hasNoOriginalFrames` 판정으로 **ORIGINAL 벌 미생성**(`deid/` 1벌만) → 오표기 경로 소멸 | **✅ 해소** |
| `E-ISSUE-43` — export FAILED 시 **빈 산출 디렉터리 잔존** + retention 미구현 | MEDIUM | ①retention: **의도된 미구현이 확정 정책**(TC-EXPORT-043) → 결함 아님 ②빈 디렉터리: `totalWritten==0` FAILED(rawSn 173 v5) 후 `/app/storage/raw/seed/173/v5/{orgnl,deid}` 가 **빈 디렉터리로 잔존**(실측) — `purgeThisRunVersionDir` 는 신고 차단 경로에서만 호출됨 | **부분 미해소(빈 디렉터리)** — 신규 이슈로 승격하지 않음(LOW·데이터 위생, 뷰는 최신 SUCCEEDED 조인이라 노출 안 됨) |
| `E-ISSUE-44` — 최상위 키 `event_annotation` ↔ `event` 이원화 | LOW~MED | 현 코드·현 산출물 **전부 `event`**(`NiaAnnotationDoc.java:32` `@JsonProperty("event")`). 이번 회차에 새로 만든 산출물 전수(126 v1·v2, 173 v1~v8, 178 v1)에 `event_annotation` 키 **0건**. 카탈로그 TC-EXPORT-020/021 도 `event` 로 정정 완료 | **✅ 해소**(구 v1 산출물은 이 스택에 부재) |

---

## ★export 산출물 실측

`docker exec klid-backend` 로 파일을 직접 열어 대조했다.

| rawSn | 성격 | 버전 폴더(디스크) | orgnl 파일 | deid 파일 | orgnl↔deid md5 | `EXPORT_PATH_NM` | 최상위 JSON 키 |
|---:|---|---|---:|---:|---|---|---|
| **126** | 완주 기준(참조 데이터, **무변경 유지**) | `v1`, `v2` | 3 jpg+3 json | 3 jpg+3 json | **전부 상이**(`c155a39c…`↔`064376b2…` 등 3쌍) | `/app/storage/raw/seed/126`(영상 루트) | info·dataset·licences·video·**event**·image·annotations·categories·type |
| **173** | 본 검증 전용 신규(`E6-CLIP-01`) | `v1 v2 v3 v4 v5 v7 v8`(v6=경로거부라 폴더 미생성) | 각 3(v4는 2) | 각 3 | 전부 상이 | `/app/storage/raw/seed/173` | 동일 9키 |
| **178** | 173 의 해상도 파생(RESL_720P) | `v1` | **없음(정책상 미생성)** | 3 jpg+3 json | N/A | `/app/storage/deidentified/videos/resolution/173/178/178` | 동일 9키 |
| 132·136·146·152·153·156 | 타 에이전트 생성분 | 최대 `v7`(156), `v6`(153) | — | — | 전수 스윕 결과 동일 md5 **0쌍** | 각 영상 루트 | — |

- **폴더 계약**: `{dirname(RAW_FILE_PATH_NM)}/{rawSn}/v{n}/{orgnl|deid}/` + 프레임별 `NNNN.jpg`/`NNNN.json`(4자리 zero-pad). 비식별 영상은 형제 `deid/` 에 co-locate(`126/deid/sample-cctv-1080p-mask.mp4`) — 관제가 **경로 1개**로 전 버전 + 비식별 영상을 본다.
- **파일명 조합·추측 없음**: 비식별 영상 파일명은 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 값을 읽어 쓴다(mock KPST 산출 `{stem}-mask.mp4`). export JSON `dataset.src_path`·`video.filename` 도 그 값의 basename(`sample-cctv-1080p-mask.mp4`)이며, 고정 `deidentified.mp4` 조합 흔적 0건.
- ⚠ **파생의 `EXPORT_PATH_NM` 은 `…/178/178` 로 한 단계 중첩**된다. 파생 `RAW_FILE_PATH_NM` 이 이미 `…/resolution/173/178/RESL_720P.mp4` 라 `dirname + rawSn` 규칙상 자연 도출된 결과이며 규약 위반 아님(관제는 그 경로 하위에서 `v1..vN` 을 본다).
- **전 버전 보존**: 173 은 8회 산출 중 6개 버전 폴더가 그대로 남아 있고 삭제 코드는 `purgeThisRunVersionDir`(신고 중단 전용)·tmp cleanup 둘뿐. retention 잡 **0건**.

---

## ★재생성 트리거 7경로 실측

확정 정책(승인 후 수정 7경로 → `v{n+1}` 전량 재생성)의 발행 플래그를 코드로 전수 확인하고, 그중 3경로는 실동작으로 `v{n+1}` 생성을 확인했다.

| # | 수정 경로 | `TaskModifiedEvent.exportRegenerated` | file:line | `v{n+1}` 생성 | 통지 |
|---:|---|:--:|---|---|---|
| 1 | 라벨 수정 `LabelService` | **true** | `LabelService.java:422-424` | [실동작] 타 에이전트 rawSn 156/132 flush `regen=true` → v{n+1} SUCCEEDED | TASK_MODIFIED |
| 2 | 트랙 편집 `TrackEditService` | **true** | `TrackEditService.java:326` | [정적] | — |
| 3 | 트랙 병합 `TrackMergeService` | **true** | `TrackMergeService.java:200-201` | [정적] | — |
| 4 | 버전 롤백 `VersionService` | **true** | `VersionService.java:518-519` | [정적] | — |
| 5 | 촬영환경 `EnvironmentMetaService` | **true** | `EnvironmentMetaService.java:125-126` | [실동작] 173 재동결 후 flush `regen=true`(신고 구간이라 산출은 게이트 차단 — 아래 TC-EXPORT-016) | 보류됨 |
| 6 | 프레임 설명 `FrameDescriptionService` | **true** | `FrameDescriptionService.java:60-61` | [실동작] **173 v2 생성**(04:05:18) + JSON `image.description="E6 verification frame description"` | TASK_MODIFIED 발송 |
| 7 | 프레임 PII 메타 `FramePrivacyMetaService` | **true**(single `:154-155` / bulk `:131-132`) | 〃 | [실동작] **173 v8 생성**(04:13:40) + `image.pseudonymity/privacy_included="Y"` | — |
| (예외) | `EvntAnnoService`(event_annotation 수정) | **false** | `EvntAnnoService.java:128-129` (4-arg 생성자) | 재생성 없음 | CLAUDE.md 예외 조항 그대로 — **결함 아님** |

- 디바운스 윈도우 60초 + flush tick 10초 → 실측 지연 **68초·82초**(04:04:10→04:05:18, 04:12:56→04:13:40).
- flush 스케줄러는 `ControlNotifyDebouncer` 자체 소유 데몬 스레드(`control-notify-debounce-flush`)로 tick — `@EnableScheduling`/타 토글 비의존 실측 확인.
- **export→통지 직렬화 실측**: `export succeeded rawSn=173 version=2`(04:05:18.187) → `TASK_MODIFIED sent`(04:05:18.208). 역순 0건.

---

## E-6 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---|---|
| TC-EXPORT-001 | 검수승인 AFTER_COMMIT 강제 재생성 | PASS | [실동작] `[DatasetExportBridge] review approved rawSn=173 — triggering dataset export (force regenerate)` → `runApprovalAsync` (04:03:35.016~.017) | `DatasetExportBridge.java:36-43` 정합 |
| TC-EXPORT-002 | 승인 경로 멱등 skip 미적용 | PASS | [실동작] 무수정 재승인이 새 버전을 만든 3사례 — rawSn **152 v1~v4 전부 `content_hash=a00d637c…` 동일**, 153 v1/v2 동일(`f0c6e962…`), 156 v1/v5/v6/v7 동일(`27dc8f6d…`) | force=true 경로에 skip 미적용 확증 |
| TC-EXPORT-003 | 재동결 경로 멱등 skip | PASS | [정적] `DatasetExportService.java:152-156` + `ExportPreparation.isUnchangedFromLastExport()`, baseline=SUCCEEDED∪PARTIAL(`LsDatasetExportRepository.findFirstByDataRawSnAndExportSttsCdInOrderByExportVerNoDesc`). 테스트 `DatasetExportServiceTest:280,551`·`DatasetExportTxServiceTest:109,128`·`DatasetExportE2EIT:623` | `DatasetReExportEvent` 는 **발행처 0건(휴면 리스너)** — 실동작 재현 불가, 코드 주석에 사유 명시 |
| TC-EXPORT-004 | 정상 산출 SUCCEEDED + EXPORT_PATH_NM = 영상 루트 | PASS | [실동작] 173 v1 `SUCCEEDED, frame_cnt=6, EXPORT_PATH_NM=/app/storage/raw/seed/173`(버전 루트 아님). 그 아래에 `deid/`·`v1`·`v2`… 공존 | `DatasetExportTxService.java:174-183` 주석과 일치 |
| TC-EXPORT-005 | 일부 프레임 부재 PARTIAL | PASS | [실동작] 173 원본 프레임 1장 삭제 후 재산출 → `frames written kind=ORIGINAL written=2 skipped=1` → `partial export … written=5 skipped=1` → **v4 PARTIAL, frame_cnt=5**. 디스크 `v4/orgnl` 2쌍 / `v4/deid` 3쌍 | `dataset.export.skipped_frames` 증가 |
| TC-EXPORT-006 | 산출 0건 FAILED | PASS | [실동작] 173 전 프레임 이미지 삭제 후 재산출 → `nothing produced — marked FAILED rawSn=173 version=5` → **v5 FAILED, frame_cnt=null**, 작업상태 APPROVED 유지(승인 롤백 없음) | ⚠ 이 경로에서 **통지는 나갔다** → E-ISSUE-61(TC-EXPORT-018). 빈 `v5/{orgnl,deid}` 디렉터리 잔존(1차 E-ISSUE-43 부분 미해소) |
| TC-EXPORT-007 | 파생영상 = ORIGINAL 벌 미생성(PARTIAL 아님) | PASS | [실동작] 해상도 파생 rawSn **178** 승인 → `original kind skipped — derivative video has no original frames rawSn=178 version=1 reason=DERIVATIVE_NO_ORIGINAL` → deid 3건만 written → **SUCCEEDED**(PARTIAL 아님), 디스크에 `orgnl/` 폴더 자체 없음 | `DatasetExportService.java:187-199,330-336` |
| TC-EXPORT-008 | 버전 채번 UK 충돌 재시도 | PASS | [정적] `DatasetExportService.java:367-377` `insertWithRetry` MAX 3회 + `saveAndFlush` 로 UK 즉시 유발(`DatasetExportTxService.java:173-183`). 테스트 `DatasetExportServiceTest:345` | 동시 승인 실측: 152 가 0.17초 간격 4회 승인 → v1~v4 충돌 없이 채번 |
| TC-EXPORT-009 | 버전 채번 재시도 소진 | PASS | [정적] `:174-178` null → `OUTCOME_VERSION_EXHAUSTED` + abort. 테스트 `DatasetExportServiceTest:364,533` | 실동작 유발 불가(정상 경로에서 3회 연속 UK 위반 미발생) |
| TC-EXPORT-010 | 파일쓰기 실패 시 승인 불변 | PASS | [정적] `:234-241` inner catch → `markFailed` + `cause={클래스명}` 만 로깅(경로 원문 미노출), 예외 미전파. 테스트 `DatasetExportServiceTest:264`·`DatasetExportE2EIT:402` | 승인 불변은 TC-006/015 실동작으로도 확인 |
| TC-EXPORT-011 | 입력 부재 NO_INPUT | PASS | [실동작] 프레임 0건인 APPROVED 영상 rawSn **155·151** → `[DatasetExport] no frames — skip export rawSn=155/151`, `ls_dataset_export` 행 **미생성**, metric `outcome=no_input` 태그 존재 | `:143-147` |
| TC-EXPORT-012 | 메트릭 outcome 정확히 1회 | PASS | [실동작] `dataset.export.result` COUNT=**27** ↔ (INSERT 행 25 + no_input 2) 정확히 일치, `dataset.export.duration` COUNT=**27** 동일. 태그 값 실관측 `completed`/`no_input`/`deident_blocked` | `finally` 단일 지점(`:255-263`) — 조기 return·예외 이탈 모두 1회 |
| TC-EXPORT-013 | 브릿지 조건부 비활성화 | PASS | [정적] `DatasetExportBridge.java:29-30` `@ConditionalOnProperty(prefix="authoring.dataset-export", name="enabled", havingValue="true", matchIfMissing=true)`. 테스트 `DatasetExportBridgeBeanConditionTest` 3건 | 현 형상은 `DATASET_EXPORT_ENABLED:true` |
| TC-EXPORT-014 | 폴더 구조 계약 (co-locate) | PASS | [실동작] `/app/storage/raw/seed/173/v{n}/{orgnl,deid}/NNNN.{jpg,json}` + 형제 `deid/…-mask.mp4`. 고정 `labeling_root` 경로 **0건**(스토리지 전수 확인) | `base-strategy: co-locate` |
| TC-EXPORT-015 | 산출 base 3중 가드 + fail-secure | PASS | [실동작] `ls_data_raw(173).raw_file_path_nm` 을 `/etc/e6-not-allowed/x.mp4`(허용 마운트 밖)로 바꾼 뒤 산출 트리거 → `export base rejected — marked FAILED rawSn=173 reason=FORBIDDEN` → **v6 행 INSERT + FAILED + `EXPORT_PATH_NM=null`**, 기본 루트 폴백 0, 로그에 경로 원문 미노출. 검증 후 원복 | `:162-169,345-360`. ⚠ 이 경로에서도 통지 발송됨 → E-ISSUE-61 |
| TC-EXPORT-016 | 비식별 신고 게이트 = 산출 자체 skip | PASS | [실동작] 173 에 신고 OPEN(`DE_IDENT_YN='F'`) 상태에서 촬영환경 수정 → flush `regen=true` → `[DatasetExport] export blocked — deident report open rawSn=173`(04:06:48.247) → `async export failed … cause=CustomException` → **`ls_dataset_export` 행 미증가(FAILED 아님)**, metric 태그 `deident_blocked` 신규 출현, **TASK_MODIFIED 미발송** | `:136-141` 단일 진입부 게이트 |
| TC-EXPORT-017 | 쓰기 중 신고 접수 = 마감 차단 + v{n} 폴더 삭제 | PASS | [정적] `DatasetExportTxService.java:224-239`(RAW 잠금 하 재판정 → `deleteById` → false) + `DatasetExportService.java:247-254`(`purgeThisRunVersionDir` → 예외 이탈). 삭제 범위는 `v{version}` 단일이며 3중 경로가드(`resolveVideoRoot`→`resolveUnder`→`verifyRealPathUnder`) 통과 시에만. 테스트 `DatasetExportDeidentReportGateIT:237,258` | 쓰기 도중 신고 커밋 창을 실동작으로 좁히기 불가(산출 0.02초) |
| TC-EXPORT-018 | **export 성공 후에만 통지** | **FAIL** | [실동작] `nothing produced — marked FAILED rawSn=173 version=5`(04:10:01.352) **직후** `TASK_COMPLETED sent rawSn=173`(04:10:01.362). base 거부 FAILED(v6)에서도 동일(04:10:43.758→.770) | **E-ISSUE-61** — 성공 경로·게이트 차단 경로는 정상 |
| TC-EXPORT-019 | 승인 후 수정 = 재export 후 통지 | PASS | [실동작] `async re-export(+notify) starting rawSn=173 forceRegenerate=true` → `frames written`×2 → `export succeeded … version=2`(04:05:18.187) → `TASK_MODIFIED sent … reExport=true`(04:05:18.208). 콜백은 `Runnable`(`AsyncDatasetExportRunner.java:91`) | 성공 시에만 콜백 실행 — 실패 시 미실행은 E-ISSUE-61 참조 |
| TC-EXPORT-040 | 실패 export 회수 후 통지 재개 | PASS | [정적+실동작] Quartz `DatasetExportFailureRecoveryJob` 900초 주기 tick **실관측**(03:00·03:15·03:30·03:45·04:00 `no retryable failed export`). `claimForRetry` 는 `RTY_NMTM+1, RTY_DT=now WHERE EXPORT_STTS_CD='FAILED' AND RTY_NMTM<:max AND (RTY_DT IS NULL OR RTY_DT<:cutoff)` 조건부 UPDATE(2노드 중복 차단). 신고 구간 건은 **클레임 이전**에 제외(`DatasetExportFailureRecoverer.java:141-144`). 성공 시 `runApprovalAsync` 로 완료 이벤트 재발행(`:157`). 테스트 `DatasetExportFailureRecoveryIT` 4건 | 실제 재시도 발화는 미재현 — 유예 10분·주기 15분이라 대기 창 내 다른 트리거(신고 resolve)가 먼저 v7 SUCCEEDED 를 만들어 FAILED 앵커가 최신에서 밀려남 |
| TC-EXPORT-041 | 신고 해소 시 보류분 복구 | PASS | [실동작] 173 `'F'→'Y'` resolve(04:07:36) → `[DatasetExportBridge] deident report resolved rawSn=173 — re-triggering withheld export/notify` → `runApprovalAsync`(force=true) → **v3 SUCCEEDED** → `TASK_COMPLETED sent`. 총 4회 재현(v3·v7 등) | 팬아웃 없음(그 영상 하나) — 파생 178 은 무영향 |
| TC-EXPORT-042 | 재export 트리거는 control-notify 토글과 무관 | PASS | [정적] `ControlNotifyDebouncer` 에 `@ConditionalOnProperty` **없음**(항상 빈 등록), `notifyService`/`metrics` 만 `@Nullable` 주입. `send()`(`:294-306`)가 `exportRegenerated=true` 면 `notifyService==null` 이어도 `runReExportThenNotify(rawSn,true,null)` 를 항상 호출. flush 스케줄러는 `authoring.dataset-export.regen-flush.enabled`(자체 토글, 기본 true) | 현 형상 `CONTROL_NOTIFY_ENABLED=true` 라 off 상태 실증 불가 |
| TC-EXPORT-043 | retention 정리 로직 없음 | PASS | [실동작] rawSn **173 v1~v8**(v6 제외 6폴더)·**156 v1~v7**·**153 v1~v6** 전 버전 디스크 잔존. [정적] `dataset/` 하위 삭제 코드는 `purgeThisRunVersionDir`(신고 중단 전용)·tmp `deleteIfExists` 뿐, retention 잡·스케줄러 **0건**. TODO 주석 `DatasetExportService.java:45-46,171-172` 존치 | 확정 정책 준수 |

---

## E-7 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---|---|
| TC-EXPORT-020 | ★최상위 키는 `event` (rename) | PASS | [실동작] 신규 산출 전수(126 v1·v2, 173 v1~v8, 178 v1)에서 최상위 키가 `"event"`. `"event_annotation"` **0건**. 위치는 `video` 다음(`@JsonPropertyOrder` `{info,dataset,licences,video,event,image,annotations,categories,type}`) | `NiaAnnotationDoc.java:25-26,32`. 내부 필드명 `eventAnnotation` 유지 |
| TC-EXPORT-021 | event 값 null 처리 | PASS | [실동작] 동결 event 없는 영상(126·173·178) JSON 54행 `"event" : null` — 키 present, 값만 null | `@JsonInclude(ALWAYS)` `NiaAnnotationDoc.java:24` |
| TC-EXPORT-022 | anonymity=ExportKind 파생(수동 override 금지) | PASS | [실동작] **반증 실행** — `PUT /v1/frames/188/privacy-meta {"anonymity":"Y",…}` 로 `ls_data_src.anony_incl_yn='Y'` 저장 후 재산출(v8) → `v8/orgnl/0000.json` `image.anonymity="N"`, `v8/deid/0000.json` `="Y"`. 수동값이 덮지 않음 | `NiaJsonBuilder.java:150-153` — `src.getAnonyInclYn()` 참조 자체가 없음(CWE-359 방어) |
| TC-EXPORT-023 | pseudonymity/privacyIncluded 수동 우선 | PASS | [실동작] 같은 v8 에서 `image.pseudonymity="Y"`, `image.privacy_included="Y"`(수동값 반영, **2벌 모두**). 수동값 리셋 상태(v1~v7)에서는 파생 폴백 `"N"` | `NiaJsonBuilder.java:156-159` + `firstNonBlank`(`:203-206`, CHAR(1) 공백 패딩도 blank 취급) |
| TC-EXPORT-024 | deid 산출 경로 fail-secure | PASS | [정적] `NiaJsonBuilder.java:132-137`(`buildDataset` kind 분기, deid 미상이면 `path=null` → `name`/`src_path` 둘 다 null) + `VideoMetaMapper.java:61-64`(`kindVideoPath` → `basename` null). 테스트 `NiaJsonBuilderTest:499`·`VideoMetaMapperTest:173` | 실측 환경은 procLog 가 항상 존재해 null 케이스 미발생 |
| TC-EXPORT-025 | malformed 라벨 skip | PASS | [정적] `NiaJsonBuilder.java:178-201` — `CustomException` catch → `lblSn` 만 로깅(좌표 원문 미노출), 문서는 정상 생성. 테스트 `NiaJsonBuilderTest:410`·`:116` | 정상 데이터라 실동작 미발생 |
| TC-EXPORT-026 | 잉여키 제거/키 유지 정합 | PASS | [실동작] 산출 JSON `video` 33키·`image` 13키 전부 present, 미보유 필드는 값만 null(`type`·`location`·`license_id`·`pixel`·`og_cd`·`cctv_height`·`cctv_azimuth`·`cctv_mng_no`·`frames`·`event_log`). 잉여키 `orign_filename`·`orign_file_name`·`cto`·`vqa` **0건** | `NiaVideo.java:12`·`NiaImage.java:9` `@JsonInclude(ALWAYS)` |
| TC-EXPORT-027 | vd_description 필드 존재 | PASS | [실동작] 산출 JSON 52행 `"vd_description" : null` (키 존재·값 null) | `NiaVideo.java:46`; `VideoMetaMapper.java:101` 하드 null |
| TC-EXPORT-028 | weather/time_of_day/season — 수동값만 | PASS | [실동작] 173 v1·v2(수동 미입력) → 세 값 **모두 null**(`sht_dt` 가 있어도 `NGT`/`SUMMER` 파생 **미발생** = self-fill 제거 확인). 촬영환경 수동 입력 후 v3~v8 → `"weather":"맑음","time_of_day":"DAY","season":"SUMMER"`. orgnl/deid 2벌 동일 | `VideoMetaMapper.java:57-59`(raw→meta) + `DatasetVideoMetaSnapshotService.java:106-122`(수동값만 동결, `SHT_DT` 파생 폐기 주석 명시). `TimeOfDaySeasonDeriver` 는 조회 프리필(`EnvironmentMetaService.java:199,202`, `source=DERIVED` 명시)에만 잔존 |
| TC-EXPORT-029 | 촬영환경 blank→null 정규화 | PASS | [정적] `VideoMetaMapper.java:144-152` `firstNonBlank` — 공백만 있는 값도 미입력 취급, 전부 blank 면 null. 스냅샷 측 `nullIfBlank`(`DatasetVideoMetaSnapshotService.java:120-122`)와 표현 일치. 테스트 `VideoMetaMapperTest:271` | 빈 문자열 행 주입 불가(파일 수정 금지) |
| TC-EXPORT-030 | anonymity kind override(video) | PASS | [실동작] `v8/orgnl` `video.anonymity="N"` ↔ `v8/deid` `="Y"`, 같은 문서의 `weather/time_of_day/season` 은 2벌 동일 | `VideoMetaMapper.java:65` |
| TC-EXPORT-031 | meta null 방어 | PASS | [정적] `NiaJsonBuilder.java:88-91` → `INVALID_INPUT`("영상 메타가 null 입니다.") = HTTP 400. 테스트 `NiaJsonBuilderTest:514` | 상위(`loadPreparation`)가 활성 메타 부재를 먼저 skip 해 실경로 미도달 |
| TC-EXPORT-032 | FORMAT_VERSION/info 계약 | PASS | [실동작] 전 산출 JSON `info.version="1.3"`, `info.description="AI기반 CCTV 관제지원시스템 학습데이터"`, `info.year=2026`, `info.date_created="2026-07-31"`, 최상위 `"type":"instances"` | `NiaJsonBuilder.java:37,39,96` |
| TC-EXPORT-033 | image.file_name 은 ExportFileNaming 단일 지점 | PASS | [실동작] JSON `image.file_name="0000.jpg"/"0001.jpg"/"0002.jpg"` ↔ 같은 폴더 실제 파일명 **완전 일치**. 구 `frame-{n}.jpg` 형식 산출물 **0건** | `NiaJsonBuilder.java:145` → `ExportFileNaming.imageFileName`(`%04d`, 음수 fail-closed). 계약 테스트 `ExportNamingContractTest:85` |
| TC-EXPORT-034 | frame_num = VDO_FRM_NO, 미측정이면 null | PASS | [실동작] 173 프레임 `FRM_NO 0/1/2` ↔ `VDO_FRM_NO 0/1200/2400` → JSON `0000.json:frame_num=0`, `0001.json:frame_num=1200`, `0002.json:frame_num=2400`. **파일명(FRM_NO)과 값(VDO_FRM_NO)이 서로 다름** = FRM_NO 폴백 없음 실증. 126(interval 300)은 0/300/600 | `NiaJsonBuilder.java:146-149,169-170`. 테스트 `DatasetExportE2EIT:357,375` |
| TC-EXPORT-035 | 파생영상 video.filename 은 비식별 사본 | PARTIAL | [실동작] 케이스 기대는 **충족** — 파생 178 은 deid 1벌만 산출, `video.filename="RESL_720P.mp4"`, `dataset.src_path="/app/storage/deidentified/videos/resolution/173/178/RESL_720P.mp4"`(자기 비식별 사본), 부모 원본 경로 유출 0. 동결 `RAW_FILE_PATH_NM` 은 파생에서 null(`DatasetVideoMetaSnapshotService.java:132`) | ⚠ **같은 파생 문서에서 해상도 메타가 실제 산출물과 불일치** — JSON `image.width/height=1920×1080`·`video.width/height=1920×1080`·`resolution="1920x1080"` 인데 실제 `0001.jpg` 는 **1280×720**, 라벨 좌표도 720p 로 재계산돼 있음 → **E-ISSUE-62** |
| TC-EXPORT-036 | dataset 블록 kind 분기 | PASS | [실동작] 126 v2 `orgnl/0000.json` → `src_path=/app/storage/raw/seed/sample-cctv-1080p.mp4`, `name="sample-cctv-1080p"` / `deid/0000.json` → `src_path=/app/storage/raw/seed/126/deid/sample-cctv-1080p-mask.mp4`, `name="sample-cctv-1080p-mask"`. 비식별 산출물에 원본 경로 누출 0 | `NiaJsonBuilder.java:132-137` |
| TC-EXPORT-037 | frm_expln pass-through | PASS | [실동작] `PUT /v1/frames/189/description` → 재export(v2) → `0001.json` `image.description="E6 verification frame description"`. 미입력 프레임은 `"description":null` | `NiaJsonBuilder.java:174`(`NiaImage` 마지막 필드) |

---

## 근거 드리프트 / self-fill 점검

### 근거 file:line 드리프트 (경미 2건 — 판정 영향 없음)

| ID | 카탈로그 근거 | 실제 | 성격 |
|---|---|---|---|
| TC-EXPORT-040 | `DatasetExportTxService.java:257-278` | `claimForRetry` 는 **257-261**. 262-278 은 다음 메서드(`sweepStalePending`) javadoc | 범위 과다 |
| TC-EXPORT-016 | `AsyncDatasetExportRunner.java:115-129` | `doExport` 본문은 **121-130**(115-120 은 javadoc) | 1행 어긋남 |

그 외 E-6 21건 · E-7 18건의 근거는 **전부 정합**(라인 단위 대조 완료). 특히 E-7 은 `NiaAnnotationDoc.java:24,32` · `NiaJsonBuilder.java:150-153/156-159/132-137/142-145/146-149,169-170/174/88-91/178-201` · `NiaVideo.java:12,46` · `NiaImage.java:9` · `VideoMetaMapper.java:46-59,61-64,65,101,144-152` · `DatasetVideoMetaSnapshotService.java:126-133` 이 **행 단위로 정확**하다.

### self-fill 점검

| 값 | 판정 | 근거 |
|---|:--:|---|
| 비식별 영상 파일명/경로(`dataset.src_path`·`video.filename`) | **self-fill 아님** | `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 실적재값(`…/sample-cctv-1080p-mask.mp4` — mock KPST 가 정한 이름)의 basename. 고정 `deidentified.mp4` 조합 흔적 0. `DatasetExportTxService.java:133-135` |
| 촬영환경 3필드(`weather`·`time_of_day`·`season`) | **self-fill 제거 확인** | 수동 미입력 상태(173 v1·v2)에서 `sht_dt` 가 있어도 **전부 null**. 1차 `E-ISSUE-42`(`SHT_DT`→NGT/SUMMER 추정)의 동결·산출 경로 파생은 폐기됨 |
| `image.anonymity` | **파생(정상)** | `ExportKind` 단일 원천. 수동 `Y` 저장 상태에서도 orgnl=`N` 유지 실증 |
| `image.pseudonymity`/`privacy_included` | **수동값 우선(정상)** | 저장값 반영 실증, 미저장 시 meta 파생 폴백 |
| `video.length` | **self-fill 아님 — 단 출처 혼재 재확인(PIPE-ISSUE-03)** | `VideoMetaMapper.java:42` 가 `meta.getVdoLenSec()`(= `LS_DATA_RAW.VDO_LEN_SEC`, **관제 `MNG_CLIP_MASTER.VDO_LEN_SEC` 신고값**)을 쓴다. 프로그램이 값을 지어내지 않으므로 self-fill 아님. **재확인 결과**: rawSn **126** 은 관제 신고값 30000ms → `"length":"30"` 인데 같은 JSON 의 `fps=29.97`·`width/height`·`filesize` 는 ffprobe 실측이고 `LS_DATA_META.video.duration_ms=112679`(112.7초) → **한 레코드 안에 30초/112.7초 공존**. 반면 rawSn **173/132/133/153/156** 은 관제 신고값이 113000ms 라 `"length":"113"` 로 ffprobe 와 일치 → **결함이 아니라 "관제 신고값을 그대로 쓴다"는 구조**이며, 신고값이 부정확하면 그대로 전파된다. 126 의 30초는 dev 시드 아티팩트. **PIPE-ISSUE-03 을 MEDIUM 으로 존치**(본 파트는 신규 이슈로 중복 등록하지 않음) |

### 파괴적 변경 회피 / 검증용 데이터 생성 기록

- **rawSn 126**(참조 기준)·**133**(신고 OPEN)은 **일절 건드리지 않았다** — 최종 확인: 126 `DE_IDENT_YN='Y'`, export `v1/v2` 그대로 2행 / 133 `DE_IDENT_YN='F'`, 신고 sn=3 `OPEN` 유지.
- 파괴 검증(PARTIAL·FAILED·base 거부·신고 게이트)은 **본 검증 전용으로 새로 만든 rawSn 173**(관제 클립 `E6-CLIP-01` 신규 시드 → `/v1/dev/batch/scan` → 비식별 → 마킹 → 배치 → 승인, 전 구간 정규 API)과 그 해상도 파생 **178** 에서만 수행했다.
- 173 의 프레임 이미지 삭제/복원, `raw_file_path_nm` 변경/원복은 검증 직후 원상 복구했다(현재 `raw_file_path_nm=/app/storage/raw/seed/sample-cctv-1080p.mp4`, 프레임 3장 복원 완료).
- 비식별 영상 파일 `touch` 는 **외부 비식별 솔루션의 제자리 교체를 모사**한 것이다(resolve 게이트 `DeidentReportService:567-576` 가 mtime>신고시각을 요구). DB 상태를 UPDATE 로 위조한 구간은 base-거부 검증 1건(즉시 원복)뿐이다.

---

## 이슈 상세

### [E-ISSUE-61] TC-EXPORT-018 — export 가 **FAILED 로 마감된 경우에도** 관제 통지가 발송된다 (통지 보류 계약 파손)

- **심각도**: HIGH
- **기대 동작(기대효과)**: 확정 정책 — *"통지는 export 성공(SUCCEEDED) 후 발송한다. **export 가 실패하면 통지를 보류**하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다."* 즉 산출이 실패하면 관제가 통지를 받고 `V_COMPLETED_VIDEO.EXPORT_PATH_NM`(최신 SUCCEEDED)을 조회해 **구 버전 폴더를 픽업**하는 일이 없어야 한다. TC-EXPORT-018 기대결과도 `doExport` 가 true 일 때만 완료 이벤트를 발행하고 "실패면 통지 보류"다.
- **현재 동작(이슈 내용)**: `DatasetExportService.export()` 의 **FAILED 종결 3분기가 예외를 던지지 않고 정상 return** 한다. 따라서 `AsyncDatasetExportRunner.doExport` 가 `true` 를 반환하고 통지가 그대로 나간다.

  ```java
  // DatasetExportService.java:205-210  (totalWritten == 0)
  txService.markFailed(inserted.exportSn());
  log.warn("[DatasetExport] nothing produced — marked FAILED rawSn={} version={}", ...);
  outcome = OUTCOME_FAILED;          // ← throw 없음

  // DatasetExportService.java:234-241 (writer.write 예외)
  } catch (RuntimeException e) {
      txService.markFailed(inserted.exportSn());
      log.warn("[DatasetExport] export failed — approval unaffected ...");
      outcome = OUTCOME_FAILED;      // ← 삼키고 정상 흐름 복귀
  }

  // DatasetExportService.java:162-169 (산출 base 거부)
  } catch (RuntimeException e) {
      markBaseRejected(rawSn, prep.contentHash(), e);
      outcome = OUTCOME_FAILED;
      return;                        // ← 정상 return
  }

  // AsyncDatasetExportRunner.java:121-130
  private boolean doExport(Long rawSn, boolean forceRegenerate) {
      try { exportService.export(rawSn, forceRegenerate); return true; }   // ← FAILED 도 true
      catch (Exception e) { ...; return false; }
  }
  ```

  **실동작 근거(rawSn 173, 두 경로 모두 재현)**
  ```
  04:10:01.352 WARN  [DatasetExport] nothing produced — marked FAILED rawSn=173 version=5
  04:10:01.360 INFO  [ControlNotify] completed conflicted -> resend as updated rawSn=173
  04:10:01.362 INFO  [ControlNotify] TASK_COMPLETED sent rawSn=173 actual=TASK_MODIFIED
  ---
  04:10:43.758 ERROR [DatasetExport] export base rejected — marked FAILED rawSn=173 reason=FORBIDDEN
  04:10:43.767 INFO  [ControlNotify] completed conflicted -> resend as updated rawSn=173
  04:10:43.770 INFO  [ControlNotify] TASK_COMPLETED sent rawSn=173 actual=TASK_MODIFIED
  ```
  대조군(정상 보류): 신고 게이트 차단은 예외로 이탈하므로 `async export failed rawSn=173 cause=CustomException` 이 찍히고 통지가 **나가지 않는다**(04:06:48). 즉 **보류가 동작하는 유일한 경로는 신고 게이트뿐**이고, 정작 회수기가 다루도록 설계된 `FAILED` 유형이 전부 새고 있다.

  **왜 테스트가 못 잡았나 (거짓 GREEN)**: `AsyncDatasetExportRunnerTest:87 승인_export_실패시_완료이벤트를_발행하지_않는다 (HIGH-D)` 와 `:113 재산출_실패시_통지콜백을_실행하지_않는다` 는 둘 다 `doThrow(new RuntimeException("io")).when(exportService).export(...)` 로 **예외를 스텁**한다. 그런데 실제 서비스는 같은 상황에서 예외를 던지지 않도록 `DatasetExportServiceTest:264 파일산출_실패해도_승인은_롤백되지_않는다 — 예외 미전파` 가 명시적으로 보장한다. 두 테스트가 **서로 모순된 전제** 위에서 각자 GREEN 이라 결합 지점이 비어 있다.

- **재현/확인 경로**
  ```bash
  # 1) 승인된 영상의 프레임 원천 이미지를 전부 제거 → 산출 0건 유도
  docker exec klid-backend sh -c 'rm -f /app/storage/raw/frames/raw/173/*.jpg \
                                        /app/storage/deidentified/frames/deid/173/*.jpg'
  # 2) 산출 트리거(신고→해소 또는 승인 후 수정 디바운스)
  # 3) 로그에서 markFailed 직후 통지가 나가는지 확인
  docker logs klid-backend 2>&1 | grep -E "nothing produced|TASK_COMPLETED sent|async export failed"
  ```
  ```sql
  SELECT export_ver_no, export_stts_cd, frame_cnt FROM ls_dataset_export WHERE data_raw_sn=173 ORDER BY 1;
  -- 5|FAILED|(null)  ← 이 시점에 통지가 이미 발송됨
  SELECT export_path_nm, frame_cnt FROM v_completed_video WHERE raw_sn=173;
  -- 관제는 통지 수신 후 여기서 '최신 SUCCEEDED' = 구 버전(v3)을 픽업한다
  ```

- **영향**
  - 관제서버가 **산출되지 않은/실패한 버전에 대해 통지를 받고, 뷰에서는 직전 성공 버전(구 내용)을 픽업**한다 — 확정 정책이 막으려던 바로 그 시나리오("파일이 옛 내용이면 라벨링 정보 동기화 요구가 성립하지 않는다").
  - 최초 승인이 실패한 영상(직전 SUCCEEDED 없음)이면 `EXPORT_PATH_NM=NULL` 인 행을 통지받아 관제 배치가 빈 경로/NULL 을 픽업한다.
  - `DatasetExportFailureRecoverer` 의 "성공 시점으로 지연" 설계가 무의미해진다(이미 통지가 나간 뒤라 재통지가 정정이 아니라 중복이 된다).
  - 데이터 무결성 계열(CWE-670 부적절한 제어 흐름 구현). PII 유출 방향은 아니다.

- **수정 방향(제안)** ⚠ **구현하지 않는다**
  1. `DatasetExportService.export()` 를 `boolean`(또는 `ExportOutcome`) 반환으로 바꾸고 `AsyncDatasetExportRunner.doExport` 가 예외 유무가 아니라 **outcome 으로** 성공을 판정한다(`COMPLETED`·`PARTIAL`·`IDEMPOTENT_SKIP` = 통지, `FAILED`·`VERSION_EXHAUSTED`·`DEIDENT_BLOCKED` = 보류, `NO_INPUT` 은 정책 결정 필요).
  2. 시그니처를 바꾸기 어렵다면 FAILED 3분기에서 **전용 예외**(`ExportFailedSilentlyException` 등)를 던지고 러너가 그것만 잡아 `false` 를 반환한다. 단 "승인 불변(예외 미전파)" 계약은 러너가 여전히 삼키므로 유지된다.
  3. 회귀 가드는 **스텁 예외가 아니라 실제 FAILED 경로**로 작성한다 — 예: `DatasetExportE2EIT` 에서 원천 이미지 0건 상태로 승인 → `verify(eventPublisher, never()).publishEvent(DatasetExportCompletedEvent)` 를 단언.

---

### [E-ISSUE-62] TC-EXPORT-035 — 해상도 파생 export JSON 의 `image.width/height`(및 `video.width/height/resolution`)가 **실제 산출 이미지·라벨 좌표계와 불일치**

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 학습데이터 JSON 의 `image.width`/`image.height` 는 같은 폴더에 실제로 놓인 프레임 이미지의 픽셀 크기여야 한다. `annotations[].bbox`/`polygon` 좌표계와도 일치해야 COCO 계열 소비자가 정규화·검증을 할 수 있다. (`video.*` 기술메타가 부모와 같은 것은 CLAUDE.md 확정 정책상 정상이나, 그 정책은 **비디오 파일 기준**을 말한다.)
- **현재 동작(이슈 내용)**: 해상도 파생(rawSn 178, `AUG_TYPE_CD=RESL_720P`)의 export JSON 이 부모 해상도를 그대로 싣는다.

  ```java
  // NiaJsonBuilder.java:161-164  — image 블록의 크기 원천이 "영상 메타"다
  return new NiaImage(
          imageId,
          fileName,
          meta.getVdoWdth(),      // ← 1920 (부모 비디오 폭)
          meta.getVdoHgt(),       // ← 1080 (부모 비디오 높이)
          ...
  ```
  ```java
  // VideoMetaMapper.java:81-83 — video 블록도 동일 원천
          meta.getVdoWdth(), meta.getVdoHgt(), meta.getResl(),
  ```

  **실동작 근거**
  | 항목 | 값 |
  |---|---|
  | 실제 파일 `…/178/178/v1/deid/0001.jpg` 크기 | **1280 × 720** (JPEG SOF 파싱) |
  | 같은 폴더 `0001.json` `image.width/height` | **1920 / 1080** |
  | 〃 `video.width/height`, `video.resolution` | **1920 / 1080**, `"1920x1080"` |
  | 〃 `annotations[].bbox` (id 734) | `[1223.94, 352.50, 55.84, 74.18]` — x 최댓값 1279.x = **720p 좌표계** |
  | 부모 173 의 대응 라벨 (id 608) | `[1835.91, 528.76, 83.75, 111.27]` — **1080p 좌표계** (배율 1.5 정확히 대응) |
  | DB | `ls_data_aug.AUG_TYPE_CD='RESL_720P'`, `targetW/H=1280/720`, `LS_DATA_AUG_LBL_MAP` 좌표 재계산 적용 |

  즉 **이미지와 라벨은 720p 로 서로 정합**한데 **선언된 크기만 1080p** 다. `LS_DATASET_VIDEO_META` 에는 목표 해상도를 담을 필드가 없고(`DerivedMetaCopier` 가 부모 `video.*` 를 복사), 목표값은 `LS_DATA_AUG.AUG_TYPE_CD`/예약행에만 존재한다.

- **재현/확인 경로**
  ```bash
  BASE=http://localhost:18081/api
  curl -s -X POST "$BASE/v1/videos/{승인영상}/resolution" -H "Authorization: Bearer $REVIEWER" \
       -H 'Content-Type: application/json' -d '{"presets":["RESL_720P"]}'
  # 파생 rawSn 배정→검수제출→승인 후
  docker exec klid-backend sh -c 'grep -nE "\"width\"|\"height\"|resolution" \
       /app/storage/deidentified/videos/resolution/{parent}/{derived}/{derived}/v1/deid/0001.json'
  docker exec klid-backend python3 -c '...JPEG SOF 파싱...'   # 실제 1280x720
  ```
  ```sql
  SELECT aug_type_cd FROM ls_data_aug WHERE src_sn IN (SELECT src_sn FROM ls_data_src WHERE raw_sn={parent});
  SELECT point_cn FROM ls_data_lbl WHERE src_sn={파생 프레임};  -- 720p 좌표
  ```

- **영향**
  - 학습데이터셋 소비자가 `image.width/height` 로 좌표를 정규화하면 **1.5배 어긋난 박스**를 얻는다(x/1920 vs x/1280).
  - 해상도 변경 산출물(SFR-06-03)의 본래 목적인 "여러 해상도 학습데이터"가 메타상 구분되지 않는다 — 3종 파생 모두 `resolution="1920x1080"` 로 나간다.
  - `V_COMPLETED_VIDEO.RESL/VDO_WDTH/VDO_HGT` 도 같은 동결값을 쓰므로 관제 데이터마트에도 동일하게 전파된다.
  - 증강 파생(WINTER/NIGHT/RAIN)은 해상도를 바꾸지 않아 영향 없음 — **해상도 파생 전용 결함**이다.
  - CWE-1188(부정확한 초기화) 계열. PII 유출 방향 아님.

- **수정 방향(제안)** ⚠ **구현하지 않는다** — 정책 확정이 선행돼야 한다.
  1. **`image.width/height` 를 프레임 실측으로 분리**한다. `LS_DATA_SRC` 에 프레임 폭/높이 컬럼(표준용어 조합 필요)을 두고 추출·리스케일 시 적재해 `NiaJsonBuilder.buildImage` 가 그것을 쓰게 한다. 미측정이면 현행 video 메타 폴백(하위호환).
  2. **`video.width/height/resolution` 은 "비디오 파일 기준"이라는 확정 정책을 유지**하되(파생 비디오는 실제로 부모 복사본이므로 1920×1080 이 맞다), 관제 계약 문서에 그 의미를 명시하고 **목표 해상도를 별도 필드로 노출**하는 안을 검토한다(예: `AUG_TYPE_CD` 를 뷰/JSON 으로 전달).
  3. 회귀 가드: 해상도 파생 export IT 에서 `image.width == ffprobe(실파일).width` 와 `max(bbox.x) <= image.width` 를 단언.
