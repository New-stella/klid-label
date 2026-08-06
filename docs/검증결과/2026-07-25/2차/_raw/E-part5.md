# E 클러스터 part5 (E-8·E-9) 2차 검증 결과

> 대상: `docs/test-cases/E-augment-resolution-export-meta.md` §E-8(촬영환경 메타 20건) · §E-9(프레임 개인정보 메타 15건)
> 환경: 로컬 도커 스택 · backend `localhost:18081`(HEAD `ca3c712b`) · PG `klid_system`/`public`
> 실행: 2026-07-31 04:08~04:19 KST · **backend 무재기동** · 파일 수정 0건 · 빌드/테스트 실행 0건

## 집계

| 구분 | 검증 | PASS | PARTIAL | FAIL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| E-8 촬영환경 메타 | 19 | 17 | 2 | 0 | 0 | 0 | 0 |
| E-9 프레임 개인정보 메타 | 15 | 14 | 1 | 0 | 0 | 0 | 0 |
| **합계** | **34** | **31** | **3** | **0** | **0** | **0** | **0** |

- **폐기 1건 집계 제외**: `TC-META-009`(재동결 시 export 미재생성) — 2026-07-30 정책 반전(C-1b)으로 폐기, 대체는 `TC-META-017`. E-9 에는 폐기 행 없음.
- 이슈 6건 — HIGH 2 / MEDIUM 1 / LOW 3.
- **사용 영상**: 168(APPROVED, 실동작 주무대) · 145/20012(미승인·스냅샷부재 대조) · 133(신고 OPEN 게이트) · 181(168의 720P 파생, 본 검증이 생성).
  - ⚠ **rawSn 126 은 손대지 않았다**(읽지도 쓰지도 않음).
  - ⚠ **rawSn 133 은 resolve 하지 않았다**. 신고 구간 쓰기 실측분(프레임 78 개인정보 3필드·프레임 설명·영상 촬영환경)은 **전부 원복 확인**(DB 재조회로 null 복귀 확증). `DE_IDNTF_YN='F'` · `LS_DEIDENT_REPORT` sn=3 `OPEN` · 작업락 `LOCKED` 유지.
  - **rawSn 168 은 검증 산출물이 남는다**(원복 시 export 버전이 더 쌓여 노이즈가 커지므로 최종 상태를 남김): `LS_DATA_RAW` 촬영환경=안개/DAY/FALL · 프레임 180=(Y,Y,Y)/182=(null,null,Y) · `LS_DATASET_VIDEO_META` 활성 스냅샷 갱신 · `LS_DATASET_EXPORT` v2~v5 추가 · 파생 rawSn=181 생성. rawSn 145/20012/133 은 전부 원복.
  - ⚠ **첫 시도(rawSn 169)는 타 에이전트 간섭으로 무효**: 04:11:27 에 D-part4 가 169 에 비식별 신고를 접수해 1초 뒤 내 디바운스 flush 의 export 가 `export blocked — deident report open rawSn=169` 로 차단됐다. 제품 결함 아님. 168 로 재수행해 완결.

## 1차 이슈 해소 대조

| 1차 이슈 | 내용 | 2차 판정 | 근거 |
|---|---|:--:|---|
| **★E-ISSUE-42** | `time_of_day`/`season` 이 원천 없이 `SHT_DT` 규칙으로 생성돼 export·마트뷰에 **파생 표시 없이** 실림 (self-fill CRITICAL 급) | **해소 (핵심축)** | [실동작] rawSn 168·169 `LS_DATA_RAW.SHT_DT` non-null(2026-07-30 18:59) + 수동값 미입력 상태에서 ①`LS_DATASET_VIDEO_META` 활성 스냅샷 `day_ngt_cd`/`sesn_cd`/`wthr_nm` **전부 NULL** ②export `168/v1/orgnl/0000.json` → `video.weather=null, time_of_day=null, season=null`. [정적] `DatasetVideoMetaSnapshotService.java:106-123` 이 `nullIfBlank(src.getDayNgtCd())` 로 **수동값만** 동결하고 `TimeOfDaySeasonDeriver` 를 호출하지 않는다. `TimeOfDaySeasonDeriver.java:12-15` 주석에 "동결/산출 경로에서는 쓰지 않는다(E-ISSUE-42) … 여기에 파생 폴백을 다시 배선하지 말 것" 명시 |
| E-ISSUE-42 부속 ①: 조회 프리필 | 화면 프리필이 파생값을 준다 | **정책상 유지 + 투명** | [실동작] `GET /v1/videos/145/environment-meta` → `{"timeOfDay":"NGT","season":"SUMMER","weather":null,"timeOfDaySource":"DERIVED","seasonSource":"DERIVED","weatherSource":null}`. 항목별 출처가 응답에 동반된다. 카탈로그 TC-META-002 의 "조회 프리필은 유지" 와 정합 |
| E-ISSUE-42 부속 ②: 레거시 동결행 정정 | 파생 폐기 **이전**에 NGT/SUMMER 로 동결된 행 잔존 | **경로 구현 확인 / 대상 0건** | [실동작] `GET /v1/dev/dataset-video-meta/shooting-env-correction-targets` → `{"targetCount":0}`. DB 판별식 직접 실행도 0건(현 스택은 07-31 재구축분이라 레거시 행 부재). 정정 실행 경로는 정적+테스트로만 확인 → **TC-META-020 PARTIAL** |
| E-ISSUE-42 부속 ③: 관제 원천 매핑 | `MNG_CLIP_EVNT_LST.WTHR_CD/SESN_CD/HR_TYPE_CD/PRVC_TYPE_CD` 매핑 추가 | **미해소(의도적 보류) — 단 self-fill 은 아님** | [정적] `MngClipEvntLst.java:23-27` 이 "코드도메인 확정 후 별건 — 지금 매핑하면 그 해석 자체가 추정(self-fill)이 된다" 로 보류를 명문화하고, 대신 **미입력을 null 로 동결**해 추정 배포를 끊었다. 즉 "정답을 안 쓰는" 상태이지 "추정을 정답인 척 내보내는" 상태는 아니다 |
| E-ISSUE-42 부속 ④: `PRVC_TYPE_CD` 하드코딩 | 적재가 `ANONY` 상수 고정 | **미해소** | [실동작] `ls_data_raw` 전 영상 `prvc_type_cd='ANONY'`. [정적] `TrainingVideoIngestTx.java:67,118` `DEFAULT_PRVC_TYPE`. → **E-ISSUE-83** |
| **E-ISSUE-45** | `CONTROL_NOTIFY_ENABLED=false` 로 TASK_MODIFIED 통지 실동작 미검증(검증 한계) | **해소** | 현 컨테이너 `CONTROL_NOTIFY_ENABLED=true`. [실동작] `[ControlNotify] TASK_MODIFIED sent rawSn=168 frames=0 videoLevel=1 reExport=true actual=TASK_MODIFIED`(env 경로) · `frames=2 videoLevel=0`(privacy 벌크 경로) 모두 실왕복 관측 |
| **B-ISSUE-81** | `PUT /v1/frames/{srcSn}/privacy-meta` 신고 게이트·작업락 미배선 | **미해소(독립 재확인)** | 아래 §신고 게이트 실측표. → **E-ISSUE-81** |
| **B-ISSUE-82** | `PUT /v1/frames/{srcSn}/description` 작업락 무시 | **미해소(독립 재확인)** | 동상. E-ISSUE-82 에 병기 |

## ★self-fill 실측

**대전제 판정: E-8/E-9 의 산출 경로에 self-fill 없음.** 원천(수동입력)이 없으면 null 이 그대로 나간다.

| 필드 | 관제 원천값(`MNG_CLIP_EVNT_LST`) | 저작도구 출력값 (동결/export) | 일치 | MANUAL/DERIVED 표기 |
|---|---|---|:--:|---|
| `weather` / `WTHR_NM` | **컬럼 부재** — 로컬 `MNG_CLIP_EVNT_LST` 는 V63 stub(`EVNT_ID`,`EVNT_TYPE_CD`,`SHT_DT` 3컬럼)이라 `WTHR_CD` 자체가 없다 | 수동 미입력 → `null`(스냅샷·export 모두). 수동 입력 시 그 값 그대로(안개) | 대조 불가(원천 부재) | export/뷰 **표기 없음** — 단 non-null=수동값 단언이 성립하므로 불필요. 조회 API 는 `weatherSource=MANUAL/null` |
| `time_of_day` / `DAY_NGT_CD` | **컬럼 부재**(`HR_TYPE_CD` 미존재) | 수동 미입력 → `null`. **`SHT_DT` 규칙 파생 안 함**(실측: 168/169 `SHT_DT` 있어도 스냅샷·`v1` JSON 모두 null) | 대조 불가(원천 부재) | export/뷰 표기 없음(수동값만 실림). 조회 API `timeOfDaySource=DERIVED` 로 프리필 구분 |
| `season` / `SESN_CD` | **컬럼 부재**(`SESN_CD` 미존재) | 동상 — `null` | 대조 불가 | 동상 |
| `event` / `EVNT_TYPE_CD` | `INTRUSION`/`LOITERING` (실재) | export `video.event_id="LOITERING"`(rawSn 169) — 관제 실값 pass-through | **일치** | — |
| `anonymity`(image) | — (산출 종류 파생이 정본) | `ExportKind` 파생: orgnl=`N` / deid=`Y`. **프레임 수동값이 덮지 않음** | 설계대로 | `NiaJsonBuilder.java:150-154` 주석 명시 |
| `pseudonymity`(image) | `PRVC_TYPE_CD` (관제에 실재하나 **미매핑**) | 프레임 수동값 우선 → 없으면 `PRVC_TYPE_CD==PSDO?Y:N` = **항상 `N`**(적재가 ANONY 하드코딩) | **불일치 가능** | 없음 → **E-ISSUE-83** |
| `privacy_included`(image) | 동상 | 프레임 수동값 우선 → 없으면 `PRVC_YN` = **항상 `N`** | 동상 | 동상 |
| `vd_description`/`event_log`/`coordinates`/`cctv_*` | 미보유 | `null` 유지 | 정상(미보유 필드) | — |

- **판정 근거(반증 시도)**: ①`SHT_DT` 가 있는데 동결값이 채워지는가 → **아니오**(168·169 실측 null) ②규칙 파생 코드가 동결 경로에 남아 있는가 → **아니오**(`grep TimeOfDaySeasonDeriver` 참조처 = `EnvironmentMetaService`(조회) + `ShootingEnvironmentVocabulary`(상수 재사용) 뿐, 스냅샷 서비스 미참조) ③수동 입력이 실제로 산출까지 도달하는가 → **예**(168 `v2`/`v5` JSON `weather=안개, time_of_day=DAY, season=FALL`).
- **잔존 self-fill 성격의 값은 `pseudonymity`/`privacy_included` 하나뿐**이며, 그 뿌리는 E-8/E-9 가 아니라 **적재 단계의 `PRVC_TYPE_CD='ANONY'` 상수**다(E-ISSUE-83).

## ★신고 게이트 메타 경로 실측

대상 = **rawSn 133**(`DE_IDNTF_YN='F'`, `LS_DEIDENT_REPORT` sn=3 `OPEN`, `LS_AUTH_WORK_LOCK` `LOCKED`, 프레임 srcSn 78) / 토큰 = REVIEWER(1001).

| # | 엔드포인트 | 기대(정책상) | 신고구간 응답 | DB 변경 | 판정 |
|--:|---|:--:|:--:|:--:|:--:|
| 0 | `GET /v1/frames/78/labels` (대조 기준선) | 412 | **412** | — | 게이트 정상 |
| 1 | `GET /v1/frames/78/privacy-meta` | (미명시) | **200** — `{anonymity:"Y",pseudonymity:"N",privacyIncluded:"N"}` (파생 프리필) | — | 정보(판단값 Y/N 이라 PII 위치 아님) |
| 2 | `PUT /v1/frames/78/privacy-meta` | **차단(412 또는 409)** | **200** | **예** — `ls_data_src(78)` 3필드 `NULL→Y/Y/Y` | **미배선 → E-ISSUE-81** |
| 3 | `PUT /v1/frames/78/description` | **차단(409 작업락)** | **200** | **예** — `frm_expln` 설정됨 | **미배선 → E-ISSUE-82(병기)** |
| 4 | `PUT /v1/videos/133/environment-meta` | **차단(412 또는 409)** | **200** | **예** — `ls_data_raw(133)` `wthr_nm/day_ngt_cd/sesn_cd` = 비/DAY/FALL | **미배선(신규) → E-ISSUE-82** |
| 5 | `PUT /v1/frames/78/labels` (대조군) | 409 | **409** | 아니오 | 작업락 정상 |
| — | 위 #2~#4 **전건 원복 완료** | — | — | `ls_data_src(78)` 3필드+`frm_expln` NULL 복귀 · `ls_data_raw(133)` 3필드 NULL 복귀 · `de_ident_yn='F'`·신고 `OPEN` 유지 | 확인 |

- **핵심**: 비식별 신고는 개인정보 3필드를 "재판정 대상"으로 리셋하는데(`DeidentReportService` `privacyReset=6`), 그 값을 **신고 구간에 200 으로 즉시 되돌릴 수 있다**. `TC-DEID-035` 의 명시 목적(stale PII 방지)이 성립하지 않는다 — B-part5 판정과 독립 일치.
- **확장 발견**: 같은 결함이 **영상 단위 촬영환경 메타에도 있다**(#4). B-part5 는 프레임 축 2개(#2·#3)만 관측했다. 이쪽은 부작용이 더 크다 — APPROVED 영상이면 이 PUT 이 **동결 스냅샷을 즉시 재동결**(→ `V_COMPLETED_VIDEO` 값 변경)하면서 뒤따르는 export 는 `export blocked — deident report open` 으로 차단돼, **뷰와 export 폴더가 갈린다**(실제 로그로 rawSn 169 사례 관측).
- 확정 정책 ★1(신고 게이트=자기 rawSn 행 하나) 과 무관한 별개 표면이다 — 여기서 문제는 판정 **범위**가 아니라 판정 자체의 **미호출**이다.

## E-8 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-META-001 | 조회 — 수동값 우선 프리필 | PASS | [실동작] 145 에 `{"weather":"비","timeOfDay":"DAY","season":"WINTER"}` PUT 후 GET → 3필드 그대로 + `weatherSource/timeOfDaySource/seasonSource` 전부 `MANUAL`. [정적] `EnvironmentMetaService.java:193-211` `toResponse`(192행은 주석) | |
| TC-META-002 | 조회 — 파생 프리필(화면 전용) | PASS | [실동작] 수동값 없는 145·169 GET → `timeOfDay:"NGT", season:"SUMMER", weather:null` + `timeOfDaySource/seasonSource="DERIVED"`, `weatherSource=null`. 같은 영상의 동결 스냅샷·export 는 3필드 null → **조회만 파생, 산출은 null** 이 실측으로 분리 확인. [정적] `:198-210`; `DatasetVideoMetaSnapshotService.java:118-123` | ★E-ISSUE-42 해소 핵심 증거 |
| TC-META-003 | PUT 전체 교체 저장 | PARTIAL | [실동작] 145 PUT 3필드 → `ls_data_raw` 의 `wthr_nm/day_ngt_cd/sesn_cd` **만** 변경, `prvc_type_cd`·`prvc_yn`·`de_ident_yn`·`data_stts_cd` 불변(배치 컬럼 미간섭 확인). [정적] `:110-112` dirty checking(`changeShootingEnvironment`), 테스트 `EnvironmentMetaServiceTest:378`. **그러나 이 PUT 에 상태 게이트가 전무**하다 — 비식별 신고 OPEN + 작업락 LOCKED 인 133 에서도 200 으로 기록된다(§게이트 실측 #4) | **E-ISSUE-82** |
| TC-META-004 | null 필드 = 수동값 삭제 | PASS | [실동작] 145 에 `{"weather":"눈"}` 만 PUT → 응답 `weather:"눈"(MANUAL)`, `timeOfDay:"NGT"(DERIVED)`, `season:"SUMMER"(DERIVED)`; DB `day_ngt_cd`/`sesn_cd` NULL 로 삭제됨. [정적] `:106-112,174-185` | |
| TC-META-005 | 허용값 화이트리스트 weather | PASS | [실동작] `"폭우"` → **400** `INVALID_INPUT` "촬영환경 weather 값이 허용 목록에 없습니다." / `"맑음","흐림","비","눈","안개"` 는 200. [정적] `ShootingEnvironmentVocabulary.java:23` | |
| TC-META-006 | 허용값 timeOfDay/season | PASS | [실동작] `timeOfDay:"NIGHT"` 400 · `"day"`(소문자) 400 · `season:"여름"` 400 · `"summer"` 400. `DAY/NGT`·`SPRING/SUMMER/FALL/WINTER` 만 통과. [정적] `:26-32` | 대소문자 관대성 없음 |
| TC-META-007 | 길이 상한 | PASS | [실동작] 300자 weather → **400** `"weather: size must be between 0 and 20"`. 5,000자도 동일 | ⚠ 실제 선차단은 화이트리스트가 아니라 DTO `@Size(max=20)` 다(카탈로그 기대문 "화이트리스트 검사에 함께 걸림"과 경로가 다름). 결과(400)는 동일하므로 PASS |
| TC-META-008 | APPROVED 후 수정 재동결 + 통지 | PASS | [실동작] rawSn **168**(APPROVED) PUT `안개/DAY/FALL` → 로그 `materialized rawSn=168 hashPrefix=bec503b5 inserted=true` → `re-freeze triggered rawSn=168`. `ls_dataset_video_meta` 신규 활성행(DAY/FALL/안개) + 구행 `active_yn='N'`, **`rvw_cmpl_dt` 양쪽 모두 `2026-07-31 04:02:52.804331` 로 동일(승계 확인, 편집 시각으로 덮이지 않음)**. 이어 `TASK_MODIFIED sent rawSn=168 … reExport=true`. [정적] `:120-127,156-167` | 1차 E-ISSUE-45(통지 미검증) 해소 |
| ~~TC-META-009~~ | ~~재동결 시 export 미재생성~~ | — | **폐기 2026-07-30**(집계 제외) | 대체 TC-META-017 |
| TC-META-010 | 미검수 영상 수정 — 재동결·통지 없음 | PASS | [실동작] 145(`data_stts_cd`=배치 COMPLETED, 검수 미승인) PUT → `ls_dataset_video_meta` 0행 유지, `ls_dataset_export` 0행 유지, 로그에 re-freeze/TaskModified 없음. [정적] `:120,221-227` | |
| TC-META-011 | 재동결 시 활성 스냅샷 부재 fail-safe | PASS | [실동작] rawSn **20012**(`APPROVED` + 활성 스냅샷 0건) PUT → **200**, 로그 `WARN [EnvironmentMeta] re-freeze skipped — no active snapshot rawSn=20012`, 예외 없음. 이후 디바운스 flush 도 `no active video meta — skip export rawSn=20012` 로 안전 스킵 후 통지만 발송. [정적] `:157-161` | 원복 완료 |
| TC-META-012 | 동시성 — env저장 vs 승인 materialize | PASS | [정적] `:115-118` `videoRepository.flush()` → `videoMetaRepository.acquireRawLock(rawSn)` → `:120` `isReviewApproved` 순서 고정. `materialize` 도 동일 advisory 락을 첫 단계로 잡아(`DatasetVideoMetaSnapshotService` 1) 잠금 순서 단방향. 테스트 `EnvironmentMetaConcurrencyIT:80`("촬영환경_저장이_배치의_다른_컬럼_갱신을_덮어쓰지_않음") | 실동작 동시 승인 재현은 미수행(다른 에이전트 간섭 위험) |
| TC-META-013 | WORKER 본인배정 아닌 영상 | PASS | [실동작] WORKER **2001**(`ls_task_assignment`에 168·169·133 만 보유) → `GET/PUT /v1/videos/145/environment-meta` **403** "본인에게 배정되지 않은 영상입니다.". 미배정 WORKER 2099 → 403. 대조: 2001 의 168 PUT 은 200(정상 배정) | 배정표 실조회로 오판 회피 |
| TC-META-014 | 미인증/포털 채널 | PASS | [실동작] 토큰 없음 → **401** `UNAUTHORIZED`, PORTAL_USER 토큰 → **403** `FORBIDDEN`(채널 격리) | |
| TC-META-015 | 영상 미존재 | PASS | [실동작] `GET`/`PUT /v1/videos/9999999/environment-meta` **404** "영상을 찾을 수 없습니다.". [정적] `:187-190` | |
| TC-META-016 | 로그 PII 미출력 | PASS | [실동작] 허용값 외 3종 투입 후 로그 = `WARN [EnvironmentMeta] rejected value field=weather` / `field=timeOfDay` / `field=season` — **입력 원문 0건**. `<script>alert(1)</script>`·300자 입력도 로그·응답 어디에도 원문 미노출(응답은 필드명만). 성공 로그도 `updated rawSn=168` 식별자만 | CWE-117/209 방어 확인 |
| TC-META-017 | ★APPROVED 후 수정 = export 새 버전 전량 재생성 | PASS | [실동작] 168 PUT(04:12:00) → 디바운스 flush(04:13:08) `flush rawSn=168 regen=true` → `async re-export(+notify) starting rawSn=168 forceRegenerate=true` → `frames written kind=ORIGINAL version=2 written=2` + `kind=DEIDENTIFIED version=2 written=2` → `export succeeded rawSn=168 version=2`(04:13:08.731) → **그 뒤** `TASK_MODIFIED sent … reExport=true`(04:13:08.754). **순서 = export 성공 후 통지** 확증. 산출물 대조: `168/v2/orgnl/0000.json` `video.weather="안개", time_of_day="DAY", season="FALL"` ↔ `v1` 은 3필드 전부 `null` 로 **불변 보존**. [정적] `:122-127`; `AsyncDatasetExportRunner.java:91-106` | 최종 상태는 v5(재수정분까지 반영, 안개/DAY/FALL) |
| TC-META-018 | ★동결값은 수동값만 (self-fill 폐기) | PASS | [실동작] 168·169(수동 미입력, `SHT_DT`=2026-07-30 18:59) → 동결 스냅샷 `day_ngt_cd/sesn_cd/wthr_nm` 전부 NULL, export `v1` JSON 3필드 null. 판별식 SQL(라이브 raw null ↔ 동결 non-null) 전수 실행 결과 **0건**. [정적] `DatasetVideoMetaSnapshotService.java:106-123` `nullIfBlank` 3회. 테스트 `DatasetVideoMetaSnapshotServiceIT:230`("수동값_미저장_영상은_촬영환경_3필드가_모두_null로_동결된다") | ★E-ISSUE-42 해소 |
| TC-META-019 | 레거시 파생 동결값 정정 백필 — dry-run | PASS | [실동작] `GET /v1/dev/dataset-video-meta/shooting-env-correction-targets` REVIEWER **200** `{"targetCount":0}` — 호출 후 `ls_dataset_video_meta` 행 수·해시 불변, `ls_dataset_export` 신규 0, 통지 로그 0. WORKER **403** / 미인증 **401**. [정적] `DatasetVideoMetaBackfillDevController.java:80-85` `@Profile("!prd")` + `@PreAuthorize("hasRole('REVIEWER')")` | |
| TC-META-020 | 레거시 정정 백필 — 실행 + 상한 + 멱등 | PARTIAL | [실동작] `POST …/shooting-env-corrections` REVIEWER **200** `{"corrected":0,"remaining":0,"completed":true}`, 재호출도 동일(멱등 형태 확인). WORKER 403. **그러나 현 스택에 대상 0건이라 "정정 1건마다 재동결→TaskModifiedEvent→export 재생성" 과 `max-per-run` 상한 소진 경로에 도달하지 못했다.** [정적] `DatasetVideoMetaBackfillService.java:161-215`(상한 루프·`attempted` 무한루프 차단), `DatasetVideoMetaEnvCorrectionTx.correct:60-88`(advisory 락 → materialize → `TaskModifiedEvent(...,true)`), 설정 `application.yml:245 max-per-run: ${DATASET_ENV_CORRECTION_MAX_PER_RUN:200}`. 테스트 `DatasetVideoMetaEnvCorrectionIT` 6건(정정/보존/멱등/재export통지 축적) | **E-ISSUE-86** (검증 한계) |

## E-9 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-META-030 | 조회 — 수동값 우선/파생 폴백 | PASS | [실동작] 미저장 프레임 117 GET → `{anonymity:"Y", pseudonymity:"N", privacyIncluded:"N"}` (영상 `PRVC_TYPE_CD='ANONY'`·`PRVC_YN='N'` 파생과 정확히 일치). 수동 저장 프레임 180 GET → 저장값 `Y/Y/Y` 그대로. 필드 혼합(182: `anonymity` 미저장 + `privacyIncluded='Y'` 저장) → `{"anonymity":"Y"(파생), "pseudonymity":"N"(파생), "privacyIncluded":"Y"(수동)}` 로 **필드별 폴백** 확인. [정적] `FramePrivacyMetaService.java:66-70,160-183` | |
| TC-META-031 | 단건 PUT 전체 교체 | PARTIAL | [실동작] `PUT /v1/frames/182/privacy-meta` `{anonymity:null, pseudonymity:null, privacyIncluded:"Y"}` → DB `anony_incl_yn/psdo_incl_yn` NULL 로 삭제, `prvc_incl_yn='Y'`; 응답은 삭제분을 파생으로 폴백해 반환. [정적] `:73-78,144-157`. **그러나 이 PUT 은 비식별 신고·작업락 어느 게이트도 통과하지 않는다** — 133/프레임 78 에서 200 으로 3필드 재설정 성공(§게이트 실측 #2) | **E-ISSUE-81** |
| TC-META-032 | 값 화이트리스트 `^[YN]$` | PASS | [실동작] `"1"`·`"true"`·`"y"`(소문자)·5,000자 `"YYY…"` **전부 400** `"anonymity 는 Y 또는 N 이어야 합니다."`. [정적] `FramePrivacyMetaUpdateRequest`/`FramePrivacyBulkItem` `@Pattern(regexp="^[YN]$")` | |
| TC-META-033 | path/body srcSn 불일치 | PASS | [실동작] path 117 + body `srcSn:120` → **400** "path 의 srcSn 과 body 의 srcSn 이 다릅니다.". body `srcSn` 누락 → 400 `"srcSn: must not be null"`. [정적] `FramePrivacyMetaController.java:85-88` | CWE-345 |
| TC-META-034 | ★anonymity 는 export 미덮음 | PASS | [실동작] 프레임 180 수동 `anonymity="Y"`, 182 수동 `anonymity="N"` 저장 후 재생성된 `168/v3` 대조 → `orgnl/0000.json` `anonymity:"N"`(수동 Y 무시) · `deid/0001.json` `anonymity:"Y"`(수동 N 무시). 같은 파일의 `pseudonymity/privacy_included` 는 수동값 `Y/Y` 반영 → **anonymity 만 산출종류 파생** 확증. [정적] `NiaJsonBuilder.java:150-154` | TC-EXPORT-022 와 쌍 |
| TC-META-035 | 벌크 저장 N+1 제거 | PASS | [실동작] 4프레임 벌크 PUT 의 hibernate SQL 실측 = `select … from LS_DATA_SRC`(findAllById) **1회** + `select … from LS_RAW_DATA_STATUS` **1회** + `LS_DATA_RAW` 조회 1회 + `update LS_DATA_SRC` 4회. 프레임 수만큼의 findById 없음. 로그 `bulk-updated count=4 rawSns=1`(인가 rawSn 1회). [정적] `:100-140` | update 4회는 행 단위 dirty flush(불가피) |
| TC-META-036 | 벌크 미존재 프레임 404 | PASS | [실동작] `items=[{117,Y/Y/Y},{9999999,N}]` → **404** "프레임을 찾을 수 없습니다."; 직후 DB 재조회로 srcSn 117 **3필드 NULL 유지**(첫 항목도 미반영 = 전체 롤백). [정적] `:101-118` | |
| TC-META-037 | 벌크 타 영상 403 | PASS | [실동작] WORKER 2001 로 `items=[{117(raw145, 미배정)},{180(raw168, 배정)}]` → **403** "본인에게 배정되지 않은 영상입니다."; 두 프레임 모두 DB 불변. 순서 검증: `[180(미인가 아님)…]` 대신 `[존재하는 미인가, 미존재]`·`[미존재, 존재하는 미인가]` 양방향 모두 **404 우선**(항목 순회 중 미존재 판정이 인가보다 앞) → 카탈로그의 "404先→403後" 보존 확인. [정적] `:114-123` | |
| TC-META-038 | 벌크 원자성 | PASS | [실동작] TC-META-036·037 의 실패 케이스에서 선행 항목 DB 미반영 확인(위). [정적] `:99` `@Transactional("controlTransactionManager")` | |
| TC-META-039 | APPROVED 후 수정 = 재export + 통지 디바운스 | PASS | [실동작] 168 프레임 180·182 벌크 PUT(04:13:35) → 축적 테이블 `ls_mon_noti_acml` **1행**(`export_rprcs_yn='Y'`, `chg_dtl_cn={"frames":{"180":["META_UPDATED"],"182":["META_UPDATED"]}}`) = rawSn 단위 코얼레스. flush(04:14:38) `flush rawSn=168 regen=true frames=180=[META_UPDATED],182=[META_UPDATED]` → `export succeeded rawSn=168 version=3`(.788) → `TASK_MODIFIED sent rawSn=168 frames=2 videoLevel=0 reExport=true`(.811) — **export 선행, 통지 후행**. [정적] `:126-135,153-155` | |
| TC-META-040 | 벌크 항목 수 초과/빈 목록 | PASS | [실동작] `items:[]` → **400** "items 는 1건 이상이어야 합니다."; 5,001건 → **400** "items 는 5000건 이하여야 합니다.". [정적] `FramePrivacyBulkRequest MAX_ITEMS=5000`, `FramePrivacyMetaController.java:102-108` | |
| TC-META-041 | WORKER 본인배정/미인증 | PASS | [실동작] 미인증 GET/PUT **401**; PORTAL_USER 단건·벌크 **403**; 미배정 WORKER 2099 **403**; 미존재 프레임 **404**. 배정된 WORKER 2001 의 자기 프레임(180) 은 정상 처리. [정적] `:66-75,119-123` | |
| TC-META-042 | 로그 판단값 미출력 | PASS | [실동작] 단건 로그 `[FramePrivacyMeta] updated srcSn=182 rawSn=168`, 벌크 로그 `[FramePrivacyMeta] bulk-updated count=4 rawSns=1` — **Y/N 판단값·본문 0건**. 400/403/404 응답도 필드명·일반 메시지만 | CWE-359 |
| TC-META-043 | 단건 수정도 재export 트리거 | PASS | [실동작] 168 프레임 182 **단건** PUT(04:15:06) → 축적 `{"frames":{"182":["META_UPDATED"]}}` `export_rprcs_yn='Y'` → flush(04:16:08) `regen=true` → `export succeeded rawSn=168 version=4` → `TASK_MODIFIED … frames=1 reExport=true`. `v4/orgnl/0001.json` `privacy_included:"Y"` 반영 확인. [정적] `:153-155` | 재export 7경로 중 "개인정보 메타 수정" 확증 |
| TC-META-044 | 파생영상 프레임의 개인정보 3필드 상속 | PASS | [실동작] 부모 168(프레임 180=`Y/Y/Y`, 182=`null/null/Y`)에서 `POST /v1/videos/168/resolution {"presets":["RESL_720P"]}` → 파생 rawSn=181 생성. 파생 프레임 `src_sn=231 → Y/Y/Y`, `232 → null/null/Y` 로 **부모값 그대로 복사, 부모 null 은 파생도 null**. 파생 프레임 GET 은 파생 폴백 적용(`231` → `Y/Y/Y`). [정적] 해상도 `ResolutionPersistService.java:276-292`, 증강 `AugmentExtractPersist.java:96-115` — 두 경로 동일 코드형(`parent==null?null:parent.getXxx()`) | 증강 경로는 정적(외부 위탁 왕복 불필요 판단) |

## 근거 드리프트

**실질 드리프트 0건.** 아래 3건은 인용 범위가 주석/메서드 시작 1~4행 어긋나는 수준으로 대상 코드는 정확히 지목한다.

| 케이스 | 카탈로그 인용 | 실제 | 비고 |
|---|---|---|---|
| TC-META-001 | `EnvironmentMetaService.java:192-211` | `toResponse` 본문 193-211 (192는 Javadoc) | 무해 |
| TC-META-017 | `AsyncDatasetExportRunner.java:90-106` | `runReExportThenNotify` 91-106 (90은 `@Async`) | 무해 |
| TC-META-034 | `NiaJsonBuilder.java:150-153` | ★#1 주석 150-153 + 결정문 154 | 무해 |

- ⚠ **기대문 경로 드리프트 1건(판정 무영향)**: `TC-META-007` 은 "허용값이 모두 컬럼 길이 이내라 **화이트리스트 검사에 함께 걸림**"으로 적었으나 실제 선차단은 DTO `@Size(max=20)` 다(서비스 화이트리스트에 도달하지 않음). 결과 코드·메시지 톤 모두 400 이라 PASS 유지. 차기 최신화 시 기대문 정정 권고.

---

## 이슈 상세

### [E-ISSUE-81] TC-META-031 / TC-DEID-035 — `PUT /v1/frames/{srcSn}/privacy-meta` 가 비식별 신고 게이트·작업락 어디에도 걸리지 않아, 신고가 리셋한 개인정보 3필드를 신고 구간에 즉시 되돌릴 수 있다
- **심각도**: HIGH (B-ISSUE-81 독립 재확인 — 본 part 가 결함의 본진)
- **기대 동작(기대효과)**: 비식별 누락 신고는 "그 영상의 비식별이 잘못됐다"는 신호이며, `DeidentReportService` 가 개인정보 3필드(익명/가명/PII 포함여부)를 **재판정 대상으로 리셋**한다(`CLAUDE.md` 개인정보 보호 절). 따라서 신고가 열려 있는 동안(`DE_IDNTF_YN='F'` + 작업락 `LOCKED`) 그 3필드에 대한 **쓰기는 차단**되어야 한다 — 라벨 저장이 409 로 막히는 것과 동일 축이다. 최소한 작업락 409, 정책상으로는 신고 게이트 412 가 맞다.
- **현재 동작(이슈 내용)** [실동작]:
  - rawSn 133(`DE_IDNTF_YN='F'`, `LS_DEIDENT_REPORT` sn=3 `OPEN`, `LS_AUTH_WORK_LOCK` `LOCKED`)의 프레임 srcSn 78 에 대해
    `PUT /v1/frames/78/privacy-meta {"srcSn":78,"anonymity":"Y","pseudonymity":"Y","privacyIncluded":"Y"}` → **200**.
  - DB 즉시 확인: `ls_data_src(78)` 의 `anony_incl_yn/psdo_incl_yn/prvc_incl_yn` 이 `NULL,NULL,NULL` → `Y,Y,Y` 로 **실제 기록**됨(신고 시 `privacyReset=6` 로 지운 값의 복귀).
  - 같은 시각 대조군: `GET /v1/frames/78/labels` = **412**, `PUT /v1/frames/78/labels` = **409**(작업락). 즉 **라벨 축만 닫혀 있고 메타 축은 열려 있다.**
  - 코드 근거 — `FramePrivacyMetaService.java:74-78`:
    ```java
    public FramePrivacyMetaResponse update(Long srcSn, FramePrivacyMetaUpdateRequest req, TokenClaims actor) {
        LsDataSrc src = guard.verifyAndGet(srcSn, actor);   // 인가만 수행
        applyAndNotify(src, req.anonymity(), req.pseudonymity(), req.privacyIncluded(), actor);
    ```
    `DeidentReportGate`(판정 단일 원천) 주입 자체가 없고, `updateBulk:100-140` 에도 없다. `grep DeidentReportGate` → `dataset` 패키지 전체 0건.
- **재현/확인 경로**:
  ```bash
  TOK=$(curl -s -XPOST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"REVIEWER","channel":"INTERNAL"}' | jq -r .data.token)
  curl -s -o /dev/null -w '%{http_code}\n' -XPUT localhost:18081/api/v1/frames/78/privacy-meta \
    -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d '{"srcSn":78,"anonymity":"Y","pseudonymity":"Y","privacyIncluded":"Y"}'   # => 200
  ```
  ```sql
  SELECT src_sn, anony_incl_yn, psdo_incl_yn, prvc_incl_yn FROM ls_data_src WHERE src_sn=78;
  ```
- **영향**: `TC-DEID-035` 의 명시 목적("stale PII 방지")이 성립하지 않는다. 재비식별 전의 낡은 개인정보 판정이 그대로 복원되어 승인·export·데이터마트 뷰로 전파될 수 있다(APPROVED 영상이면 `pseudonymity`/`privacy_included` 로 export JSON 에 직접 실린다). **CWE-359**(개인정보 오표기 노출) / **CWE-362**(신고 처리 중 상태 변경). 작업락을 무시한다는 점에서 **CWE-863**(부정확한 인가) 성격도 있다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `FramePrivacyMetaService.update`/`updateBulk` 진입부(인가 직후)에 `DeidentReportGate` 판정을 추가한다 — 라벨 저장과 동일하게 **작업락 409 우선, 신고 412** 규약을 따르면 응답 코드 오라클(CWE-209) 위험 없이 기존 규약과 정합한다. 벌크는 rawSn distinct 집합 기준 1회 판정으로 N+1 없이 가능하다.

### [E-ISSUE-82] TC-META-003 — `PUT /v1/videos/{rawSn}/environment-meta` 도 같은 게이트 미배선. APPROVED 영상이면 신고 구간에 **동결 스냅샷만 갱신되고 export 는 차단**되어 뷰와 산출물이 갈린다
- **심각도**: HIGH (신규 — B-part5 미탐지 표면)
- **기대 동작(기대효과)**: 신고 구간에는 그 영상의 산출 계열 상태를 바꾸는 쓰기가 차단되어야 한다. 특히 촬영환경 수정은 APPROVED 영상에서 **동결 스냅샷 재동결(→ `V_COMPLETED_VIDEO` 즉시 변경) + export 새 버전 전량 재생성 + 관제 통지**를 연쇄 유발하는 무거운 경로라, 신고로 산출이 보류된 구간에서는 시작 자체가 막혀야 한다.
- **현재 동작(이슈 내용)** [실동작]:
  - rawSn 133(신고 OPEN + 작업락 LOCKED)에 `PUT /v1/videos/133/environment-meta {"weather":"비","timeOfDay":"DAY","season":"FALL"}` → **200**, `ls_data_raw(133)` 3필드 실제 기록.
  - 코드 근거 — `EnvironmentMetaService.java:100-112`: `accessGuard.verifyRawAccess(rawSn, actor)` → 값 검증 → `raw.changeShootingEnvironment(...)`. 게이트 판정 호출 없음.
  - **APPROVED 영상에서의 파생 피해(실측 관측)**: rawSn 169 에서 `PUT environment-meta` 로 재동결이 커밋된 뒤(04:10:18 `materialized … inserted=true`), 60초 디바운스 flush 시점(04:11:28)에 그 사이 접수된 신고 때문에 `[DatasetExport] export blocked — deident report open rawSn=169` → `async export failed rawSn=169`. 결과적으로 **①`LS_DATASET_VIDEO_META`(=`V_COMPLETED_VIDEO`)는 새 촬영환경으로 갱신되고 ②export 폴더는 옛 값 그대로** 남았다. 관제 계약상 뷰와 폴더는 같은 승인분을 가리켜야 하는데 갈린다.
  - **형제 결함 동시 재확인**: `PUT /v1/frames/78/description` 도 신고 구간에 **200**(작업락 무시) — B-ISSUE-82 미해소. `FrameDescriptionService` 역시 `exportRegenerated=true` 로 발행하므로(`:59-62`) 위와 같은 갈림을 만든다.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -XPUT localhost:18081/api/v1/videos/133/environment-meta \
    -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d '{"weather":"비","timeOfDay":"DAY","season":"FALL"}'    # => 200
  ```
  ```sql
  SELECT raw_sn, wthr_nm, day_ngt_cd, sesn_cd, de_ident_yn FROM ls_data_raw WHERE raw_sn=133;
  ```
- **영향**: ①신고 구간 산출 보류 정책의 우회 ②뷰(즉시 갱신) ↔ export 폴더(보류) 불일치로 관제가 서로 다른 촬영환경을 본다 — `CLAUDE.md` "통지는 export 성공 후" 직렬화가 지키려던 불변식이 이 경로에서 깨진다. **CWE-362**(TOCTOU/상태 경합) / **CWE-863**. `EnvironmentMetaService` 는 `CLAUDE.md` 의 재export 7경로 중 하나로 명시된 서비스라 정책적 비중이 크다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `EnvironmentMetaService.update`(및 `FrameDescriptionService.update`) 진입부에 `DeidentReportGate` 판정을 추가한다. 게이트 배선을 서비스마다 손으로 다는 방식이 반복 누락의 원인이므로(메모리 `state-gate-single-entry-point-rule`), **`TaskModifiedEvent(exportRegenerated=true)` 를 발행하는 모든 서비스에 게이트를 강제하는 구조적 장치**(공통 진입점 또는 클래스패스 스캔 가드 테스트)를 함께 검토할 것.

### [E-ISSUE-83] TC-META-030 / TC-META-034 — 적재가 `PRVC_TYPE_CD='ANONY'` 상수를 쓰고 관제 원천을 읽지 않아 export `pseudonymity`/`privacy_included` 가 항상 `N`, 프레임 프리필 `anonymity` 가 항상 `Y` 로 고정
- **심각도**: MEDIUM (1차 E-ISSUE-42 의 잔여 축 — 코드에 "의도적 미해소"로 명시되어 있으나 산출물 오염은 실재)
- **기대 동작(기대효과)**: 개인정보 유형의 원천은 관제 `MNG_CLIP_EVNT_LST.PRVC_TYPE_CD` 다(메모리 `control-clip-meta-source-of-truth`). 원천을 읽지 못하면 `null`(미상)이어야 하고, 상수로 특정 값을 단정해서는 안 된다.
- **현재 동작(이슈 내용)**:
  - [실동작] `ls_data_raw` 전 영상 `prvc_type_cd='ANONY'`, `prvc_yn='N'`(47건 전수).
  - [정적] `TrainingVideoIngestTx.java:64-67,116-119`:
    ```java
    /** 비식별 유형 기본값. 전체 비식별 정책상 ANONY 로 적재한다(파이프라인이 무조건 비식별 수행). */
    private static final String DEFAULT_PRVC_TYPE = LsDataRaw.PRVC_TYPE_ANONY;
    ...
    LsDataRaw.createFromIngest(vmsClipId, clip.getVmsCctvId(), evntTypeCd, clip.getLclgvCd(), DEFAULT_PRVC_TYPE, ...)
    ```
  - 파급 — [실동작] 프레임 수동 입력이 없는 모든 프레임에서 `GET privacy-meta` → `{anonymity:"Y", pseudonymity:"N", privacyIncluded:"N"}`(srcSn 117 실측), export `image.pseudonymity="N"`, `image.privacy_included="N"`(rawSn 169 `v1` 실측). 관제 실값이 `PSDO`(가명)여도 그대로 `N` 이 나간다.
  - [정적] `MngClipEvntLst.java:28-32` 가 이 사실을 **미해소로 명기**: "관제 원천이 여기 실재하는데도 적재는 `DEFAULT_PRVC_TYPE` 하드코딩을 쓴다 … 코드도메인 확정 전에는 매핑 자체가 추정이 되므로 의도적으로 미해소로 둔다".
  - **본 환경 한계**: 로컬 `MNG_CLIP_EVNT_LST` 는 V63 stub(`EVNT_ID`,`EVNT_TYPE_CD`,`SHT_DT` 3컬럼)이라 `PRVC_TYPE_CD` 컬럼 자체가 없다 → 관제 실값과의 1:1 대조는 이 환경에서 **수행 불가**(값 불일치를 실증하지 못했고, 원천 미독 사실만 확증).
- **재현/확인 경로**:
  ```sql
  SELECT DISTINCT prvc_type_cd, prvc_yn, count(*) FROM ls_data_raw GROUP BY 1,2;   -- ANONY|N 만
  SELECT column_name FROM information_schema.columns WHERE table_name='mng_clip_evnt_lst';  -- 3컬럼(stub)
  ```
  운영 DB 에서는 `SELECT prvc_type_cd FROM MNG_CLIP_EVNT_LST WHERE evnt_id=?` 로 실값 대조 필요.
- **영향**: 학습데이터 속성(가명/개인정보 포함여부)이 사실과 다를 수 있고, 데이터마트가 이 속성으로 필터링하면 오염이 전파된다. 촬영환경 3필드는 "미상=null" 로 정리됐는데 이 축만 "상수 단정"이 남아 self-fill 금지 원칙의 유일한 잔존 위반이다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①관제 코드도메인(ERD-024) 확보 → `MngClipEvntLst` 에 `PRVC_TYPE_CD` 매핑 추가 → 적재 시 관제 실값 우선, 부재면 `null` ②확보 전이라면 `DEFAULT_PRVC_TYPE` 을 `null` 로 바꾸고 파생 프리필도 `null`(미상)로 내려 "상수 단정"을 제거(촬영환경 3필드와 동일 처방) ③둘 다 어려우면 최소한 export 소비자가 구분할 수 있도록 출처 표기 추가.

### [E-ISSUE-84] TC-META-017 — `EnvironmentMetaController` Swagger 설명이 폐기된 구 정책("편집은 export 재생성을 트리거하지 않음")을 그대로 유지해 실동작과 정면 배치
- **심각도**: LOW (문서 드리프트 — 외부 계약 문서에 노출)
- **기대 동작(기대효과)**: 공개 API 문서(Swagger)는 실동작과 일치해야 한다. 2026-07-27 확정 정책(C-1b)은 "APPROVED 후 촬영환경 수정 = export 새 버전 `v{n+1}` 전량 재생성"이다.
- **현재 동작(이슈 내용)** [정적] `EnvironmentMetaController.java:66-68`:
  ```java
  + "검수 완료 후 수정 시 동결 스냅샷만 재동결(데이터마트 뷰에 최신값 반영)되고 관제 "
  + "TASK_MODIFIED(META_UPDATED) 통지가 발행된다. 편집은 export 파일 재생성을 트리거하지 않으며(라벨 수정과 동일 정책), "
  + "export 폴더는 다음 검수 승인 시점에 전량 재산출된다. WORKER 는 본인 배정 영상만.")
  ```
  [실동작] 실제로는 rawSn 168 PUT 이 60초 내에 `v2` 를 전량 재생성했다(TC-META-017 근거). 즉 **"트리거하지 않는다"·"다음 검수 승인 시점에 재산출"이 둘 다 거짓**이다. 서비스 Javadoc(`EnvironmentMetaService.java:76-78,145-150`)은 신정책으로 정확히 갱신돼 있어 컨트롤러 설명만 뒤처졌다.
- **재현/확인 경로**: `GET /swagger-ui/index.html` → EnvironmentMeta → PUT 설명 문구 ↔ `ls_dataset_export` 버전 증가 실측 대조.
- **영향**: FE·관제·감리가 Swagger 를 계약 정본으로 읽으면 "수정해도 파일은 안 바뀐다"고 오판해, 데이터마트 동기화 요구의 충족 여부를 잘못 판단한다. 폐기 케이스 `TC-META-009` 와 같은 문장이라 폐기 정책이 문서에 살아남은 형태다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 해당 3줄을 "검수 완료 후 수정 시 동결 스냅샷 재동결 + **export 새 버전 폴더로 전량 재생성** 후 관제 `TASK_MODIFIED(META_UPDATED)` 통지(통지는 export 성공 후)" 로 교체.

### [E-ISSUE-85] TC-META-002 — 조회 프리필(DERIVED)의 MANUAL 승격을 BE 가 막지 못해, FE 이외 클라이언트는 추정값을 출처 구분자 없이 동결·export 로 밀어넣을 수 있다
- **심각도**: LOW (코드에 "알려진 한계"로 명시 · 현 FE 는 규율 준수 확인)
- **기대 동작(기대효과)**: self-fill 금지의 보증은 서버가 져야 한다. 동결·export 에는 출처 구분자가 없으므로("동결된 non-null = 전부 수동값" 단언에 의존), 추정값이 수동값으로 승격되는 경로가 서버 차원에서 닫혀 있어야 한다.
- **현재 동작(이슈 내용)**:
  - [정적] `EnvironmentMetaService.java:85-91` 이 한계를 명문화: "BE 는 전송값의 출처를 알 수 없어 DERIVED 프리필의 MANUAL 승격을 **막지 못한다** … 이 API 는 공개 계약이라 다른 클라이언트는 프리필을 그대로 되돌려 보내 추정값을 수동값으로 승격시킬 수 있다".
  - [실동작] 재현 확인: 145 GET → `timeOfDay:"NGT"(DERIVED)` → 같은 값을 그대로 PUT → 응답 `timeOfDaySource:"MANUAL"`, DB `day_ngt_cd='NGT'` 저장. 이후 승인되면 그 추정값이 그대로 동결·export 된다.
  - 완화 확인 — FE 는 규율을 지킨다: `frontend/src/features/label/components/EnvironmentMetaPanel.tsx:64-112` `resolveField(…, source)` 가 손대지 않은 DERIVED 필드를 `null` 로 전송.
- **재현/확인 경로**: 위 GET→그대로 PUT→`SELECT day_ngt_cd FROM ls_data_raw WHERE raw_sn=145;`
- **영향**: E-ISSUE-42 가 닫은 self-fill 경로가 클라이언트 규율에만 의존해 재개방될 수 있다. 현 시점 실제 오염은 관측되지 않았다(모든 동결 스냅샷 3필드 null).
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①요청에 항목별 `source` 축을 추가해 `MANUAL` 만 저장(계약 변경) ②또는 저장 시 "전송값 == 현재 파생 프리필값 && 기존 수동값 없음" 이면 수동값으로 승격하지 않고 null 유지 — 둘 다 하위호환 영향이 있으므로 FE 계약과 함께 결정.

### [E-ISSUE-86] TC-META-020 — 촬영환경 정정 백필의 **실행 경로**(재동결→재export→통지, `max-per-run` 상한)가 대상 0건이라 실동작으로 확증되지 않음
- **심각도**: LOW (검증 커버리지 공백 — 제품 결함 아님)
- **기대 동작**: `POST /v1/dev/dataset-video-meta/shooting-env-corrections` 1회 실행이 대상 최대 200건을 정정하고, 정정 1건마다 재동결 → `TaskModifiedEvent(exportRegenerated=true)` → export 새 버전 재생성 → 통지가 이어져야 한다. 재호출은 자연 멱등(0건).
- **현재 상태** [실동작]: 현 스택은 2026-07-31 재구축분이라 "파생 폐기 이전에 동결된 레거시 행"이 없다 — `GET .../shooting-env-correction-targets` → `{"targetCount":0}`, 판별식 SQL 직접 실행도 0건. 따라서 `POST` 는 `{"corrected":0,"remaining":0,"completed":true}` 만 반환하고 루프 본문에 진입하지 않았다(로그도 `no target`). API 형태(sub-resource 분리 · REVIEWER 전용 · 멱등 응답)와 인가(403/401)는 실동작 확인 완료.
- **재현/확인 경로**: 레거시 상태를 인위로 만들려면 `ls_dataset_video_meta` 활성행의 `day_ngt_cd/sesn_cd` 를 직접 UPDATE 해야 하는데, ①`SNPSHT_HASH` 멱등 계약이 깨지고 ②동시 검증 중인 타 에이전트의 영상을 오염시키므로 **의도적으로 수행하지 않았다**.
- **영향**: 상한 소진·부분 진행·실패 격리 동작이 실환경에서 미확증. 정적 근거(`DatasetVideoMetaBackfillService.java:161-215`, `DatasetVideoMetaEnvCorrectionTx.correct:60-88`)와 IT 6건(`DatasetVideoMetaEnvCorrectionIT`)은 갖춰져 있다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 레거시 동결행이 실재하는 dev(246) 스냅샷에서 별도 회차로 재검증하거나, 검증 전용 시드(파생값으로 동결된 영상 1건)를 `dev-seed.sql` 에 추가하는 방안 검토.
