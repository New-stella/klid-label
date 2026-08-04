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
