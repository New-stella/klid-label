# E 클러스터 part4 — E-5 해상도 파생 예약/확정 + E-5B(폐기절) 검증 결과

- **회차**: 2026-08-03 3차
- **담당 범위**: `docs/test-cases/E-augment-resolution-export-meta.md` §E-5(TC-RESL-030~071, 42건) + §E-5B(TC-RESL-080~095, 16건 — 폐기 표기 확인만)
- **검증 방식**: 풀스택 실동작(backend `localhost:18081`, context-path `/api`, PostgreSQL `public` 스키마) + 정적 대조 + 기존 자동테스트 대조
- **환경**: `_raw/stack-bringup.md` 기준 스택(HEAD `e065da42`, 이미지 재빌드 완료). backend 실효 배선 mock-server 정상. `test-baseline.md` backend 실패 0건.
- **HEAD 확인**: 워크트리·형제 워크트리 모두 `e065da42` — 컨테이너 이미지와 검증 소스 일치.
- **빌드/테스트 실행 없음**(지시 준수). 프로덕션 코드 미수정. 카탈로그(E-5/E-5B 담당 범위)만 Edit 정정.

## 판정 집계

| 구분 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|------|:--:|:----:|:----:|:-------:|:-------:|:---:|:--------:|
| E-5 (분모 = 42 − 폐기 1) | **41** | **37** | **1** | **3** | 0 | 0 | 0 |
| E-5B (폐기, 분모 제외) | 16 | — | — | — | — | — | — |

PASS율 **90.2%** (37/41). 폐기행 TC-RESL-047 은 분모 제외.

---

## 1. 실동작 구동 요약 (이번 회차 신규 생성분)

REVIEWER 토큰(`POST /v1/dev/tokens`, userNo=1001)으로 `POST /v1/videos/{rawSn}/resolution` 을 직접 호출해
예약(PENDING) → Phase A(snapshot) → Phase B(materialize) → Phase C(persist) 전 구간을 실제로 돌렸다.

| 부모 rawSn | 조건(픽스처) | 프리셋 | 결과 | 검증한 케이스 |
|:--:|---|:--:|---|---|
| 906 | `'Y'`, 프레임 2건(deid 경로가 **서로 다른 디렉터리·동일 basename**) | 720P→163, 1080P→165 | 확정(COMPLETED/'Y'/ACCEPTED) **그러나 산출 파일 1건뿐** | 030·041·044·052·069·070·071 |
| 903 | `'Y'`, 프레임 2건 **vdo_frm_no 중복(7,7)** | 720P→166 | Phase A fail-fast → cleanup → 예약 해제 → RAW 삭제 | 039·053·061·066 |
| 902 | `'Y'`, 프레임 2건 중 1건 **deid 경로 null** | 720P→167 | Phase A CONFLICT → 동일 정리 경로 | 040·061·066 |
| 905 | `'Y'`, 비식별본 mtime **2030-01-01**(스냅샷 이후 교체 모사) | 720P→168 | **Phase C mtime stale 게이트 abort** | 049·061·066 |
| 905 | 요청~Phase C 사이에 **새 SUCCEEDED procLog(다른 경로) 삽입**(40ms 지연 레이스) | 1080P→170 | **Phase C 경로변경 stale 게이트 abort** | 048·061·066 |
| 905 | 정상 | 480P→171 | 확정 | 041·044·052 |
| 905 | **`DE_IDNTF_YN='F'`(신고구간)로 일시 전환** | 1080P→172 | **정상 확정** — 예약·PhaseA·PhaseC 3게이트 모두 통과 | ★031·036·050 |
| 905 | **동일 (부모, 프리셋) 3요청 동시 발사** | 720P×3→173 | 1건만 201, 2건 거부. `RESL_720P` aug 행 **정확히 1건**, 고아 RAW 0건 | ★033 |
| 115 | 라벨 5건·메타 7건·VLM 검수행 APPROVED 보유 (타 에이전트 구동분 관측) | 720P→161, 480P→162 | 레터박스 좌표·메타/검수행 승계 검증 | 045·046·070·071 |

> 검증 종료 후 905 의 `de_ident_yn` 은 `'Y'` 로 원복했다. 그 외 DB/파일 상태 변경은 API 를 통한 정상 생성분뿐이다.

### ★ 반증(적극적 실패 유도)에서 실제로 드러난 것
1. **Phase C stale 창 게이트 2조건 모두 실제로 발화**(경로변경·mtime) — 코드만 보면 "게이트가 있다"로 끝날 구간을 레이스로 강제해 abort·cleanup·예약해제·고아 RAW 삭제까지 전 흐름을 관측했다.
2. **`'F'`(신고) 통과 정책이 3게이트 전부에서 실동작으로 성립** — 2026-07-29 확정 정책과 코드가 일치.
3. **동시 예약 경합이 UK 로 정확히 1건만 통과** — 다만 패자의 **API 응답이 409 가 아니라 500**(E-ISSUE-66).
4. **파생 프레임 목적 파일명 충돌(E-ISSUE-61) 재현** — "코드가 그럴듯해 보이는" 구간에서 실제 파일 개수를 세어 확인. 부모 2프레임 → DB 2행이 같은 1개 파일을 가리키고 디스크엔 1개만 존재.
5. **거짓 FAIL 1건 회피** — 처음 파생 163 의 프레임 개인정보 3필드가 전부 NULL 로 보여 FAIL 로 판단할 뻔했으나, 동시 구동 중이던 타 에이전트가 부모 906 의 프레임 개인정보 필드를 그 사이에 갱신한 것이었다. 재현(165)에서 `N/Y/Y` 정상 복사 확인 → PASS. **공용 DB 동시 검증 환경에서는 부모 상태를 파생 생성 *직전*에 스냅샷해야 한다.**

---

## 2. E-5 케이스별 판정 (TC-RESL-030 ~ 071)

| ID | 판정 | 근거 확인 |
|----|:----:|-----------|
| TC-RESL-030 | PASS | [실동작] `ResolutionDerivativeService` 로그 `derivative reserved parentRawSn=906 newRawSn=163 dataAugSn=59` → AFTER_COMMIT 러너 즉시 기동. `LS_DATA_AUG(RESL_720P)` + 새 RAW(`ORGNL_RAW_SN=906`) 생성 확인. [정적] `ResolutionReservationPersister.java:68-130` 일치(근거 드리프트 0). 예약 직후 PENDING 중간상태는 확정이 4ms 내라 직접 관측 불가 — IT `해상도_파생_예약직후_finalize전에는_aug가_PENDING이라_집계가_COMPLETED가_아니다` 가 커버 |
| TC-RESL-031 | PASS | [실동작] ★`'F'` 통과: 905 를 `'F'` 로 두고 요청 → 201 + 파생 172 확정. [정적] `'N'`/null 차단은 `ResolutionReservationPersister.java:83-88`(`hasDeidentArtifact()`), 단위테스트 `부모_비식별산출물이_없으면(N)_예약게이트에서_파생생성이_거부된다`·`부모가_비식별신고구간(F)이어도_해상도_파생영상이_정상_예약생성된다`. ⚠ API 경로에서는 `ParentDeidArtifactGuard`(동기 409)가 선행하므로 이 게이트는 **2차 방어층** |
| TC-RESL-032 | PASS | [정적] `:92-94` INVALID_INPUT(400). API 경로에서는 `ResolutionDerivativeService.firstFrame()`(`:124-130`)이 먼저 400 을 던져 도달 불가한 방어층. 단위테스트 `srcSn_null이면_LS_DATA_AUG_INSERT전에_INVALID_INPUT을_던진다` |
| TC-RESL-033 | PASS | [실동작] 동일 (부모,프리셋) 3요청 동시 발사 → 1건 201 / 2건 거부, `RESL_720P` aug 행 정확히 1건·고아 RAW 0건. 순차 재요청도 동일. 스택트레이스가 `ResolutionReservationPersister.java:107`(`DataIntegrityViolationException`→CONFLICT)를 정확히 가리킴. DB 인덱스 실측 `uk_ls_data_aug_resl ON (src_sn, aug_type_cd) WHERE aug_type_cd LIKE 'RESL\_%'` — **상태 무관** 확인. ⚠ API 표면 응답은 500 → E-ISSUE-66 |
| TC-RESL-034 | PASS | [정적] `:164-170` `resolveSafeDir` = base 하위 + `StorageSubtreePolicy.isDeidentifiedArtifact` 이중 강제 → INVALID_INPUT(400). 입력이 전부 내부 상수(`SEG_VIDEOS`/`SEG_RESOLUTION`/rawSn)라 traversal 이 외부에서 주입될 표면은 없음(방어층). IT `경로에_상위탈출_시도시_거부되고_파생_확정_실패시_LS_DATA_RAW_고아행이_남지_않음` |
| TC-RESL-035 | PASS | [정적] `ResolutionSnapshotService.java:102-106` `Optional.empty`. 단위테스트 `이미확정된_파생RAW면_멱등skip하고_부모재잠금을_하지않는다` + IT `확정된_파생을_PhaseA가_멱등skip하고_프레임이_중복생성되지_않는다`(baseline 통과) |
| TC-RESL-036 | PASS | [실동작] ★`'F'` 통과(905→172, Phase A `snapshot ready` 로그). [정적] `:116-122` `'N'`만 CONFLICT. 단위테스트 2건(`(N)_게이트에서_CONFLICT`·`(F)이어도_스냅샷이_정상_생성된다`) |
| TC-RESL-037 | PASS | [정적] `:149-153` NOT_FOUND(404). ⚠ **카탈로그 전제의 상태값 `SUCCESS` 는 오기 — 실제 적재값은 `SUCCEEDED`**(DB 실측 `proc_stts_cd` 분포: SUCCEEDED 70 / FAILED 8). 이번 회차에 카탈로그 정정 완료 |
| TC-RESL-038 | PASS | [정적] `:161-164` INTERNAL_ERROR. API 경로에서는 동기 `firstFrame()` 400 이 선행(방어층). 단위테스트 `부모_프레임이_0건이면_fail_fast로_거부한다(#9)` |
| TC-RESL-039 | PASS | [실동작] 부모 903(vdo_frm_no 7,7 중복) → 요청 201 후 러너 `finalize failed rawSn=166 cause=CustomException` → `reserved aug slot released dataAugSn=62` → `failed derivative RAW removed rawSn=166`. DB 에 166 부재 확인. [정적] `:194-199` |
| TC-RESL-040 | PASS | [실동작] 부모 902(프레임 2건 중 1건 `de_idntf_src_file_path_nm` null) → 167 CONFLICT → 동일 정리 경로 완주, RAW 부재 확인. 원본 폴백 없음(로그·DB 어디에도 원본 경로 미기록). [정적] `:240-247` |
| TC-RESL-041 | **FAIL** | [실동작] **목적 파일이 부모 프레임과 1:1 이 아니다.** 부모 906 의 2 프레임 deid 경로가 `frames/deid/26/frame-0.jpg` · `frames/deid/27/frame-0.jpg`(디렉터리는 다르고 basename 동일) → 파생 163·165·98 모두 `LS_DATA_SRC` 2행이 **같은 파일 1개**(`frames/deid/{newRawSn}/frame-0.jpg`)를 가리키고 디스크에도 1개만 존재. `SELECT raw_sn, count(*), count(DISTINCT de_idntf_src_file_path_nm)` → `163|2|1`, `165|2|1`, `98|2|1`. 비디오 복사·리스케일 자체와 "DB 접근 0·부모 잠금 0" 은 충족. → **E-ISSUE-61** |
| TC-RESL-042 | PASS | [정적] `ResolutionFileMaterializer.java:66-68` `exists()` → NOT_FOUND, 원본 폴백 없음. 단위테스트 `비식별_비디오원본이_없으면_NOT_FOUND로_실패하고_게이트를_반환한다`. 실동작 재현 시도(요청 20ms 후 파일 삭제)는 Phase B 가 먼저 복사를 마쳐 레이스 실패 — 파일 존재 확인이 2줄이라 정적 판정으로 충분 |
| TC-RESL-043 | PASS | [정적] `:63` `resizeGate.acquire()` 가 **비디오 복사(`:69`) 앞**, `:77-79` finally release → 복사+리사이즈 전체가 1슬롯. `ResizeConcurrencyGate` = fair Semaphore(기본 2, timeout 5s, 초과 429). 실효 설정 확인(`application.yml:420-421`, 환경변수 미지정 → 기본값). 429 유발은 픽스처 프레임이 2~6장이라 불가 |
| TC-RESL-044 | PASS | [실동작] 163·165·171·172·173 전건 `LS_DATA_RAW`(COMPLETED, `de_ident_yn='Y'`) + `LS_DATA_AUG`(ACCEPTED, `new_raw_sn` 매핑) + `LS_DEIDENT_PROC_LOG`(SUCCEEDED, 파생 비디오 경로) + `LS_DATA_SRC` INSERT + `LS_DATA_AUG_LBL_MAP` 적재 동시 확인. 로그 `[C] persisted rawSn=... frames=.. labels=.. metas=.. metaReviews=..`. [정적] `:85-154` |
| TC-RESL-045 | PASS | [실동작] **수치 정확 일치.** 부모 115(320×240) → 720P: `LetterboxTransform` scale=min(4.0,3.0)=**3.0**, offsetX=(1280−960)/2=**160**, offsetY=0 (로그 `scale=3.0 offset=160,0`). BBOX `[[8,8],[80,80]]` → `[[184,24],[400,240]]` = `x*3+160`, `y*3+0` ✔. 480P: scale=2.0, offsetX=107 → `[[123,16],[267,160]]` ✔. SKELETON 17점도 동일 변환 + **visibility 플래그 보존**(`[0,0,0]`→`[160,0,0]`, `[10,10,2]`→`[190,30,2]`) ✔. 매핑행 `coord_recalc_yn='Y'`, `scale_x=scale_y`(3.000000 / 2.000000) — 축별 독립배율 폐기 확인. [정적] `ResolutionSnapshotService.java:136-143`, `ResolutionPersistService.java:384-409` |
| TC-RESL-046 | PASS | [실동작] 부모 906(라벨 0건) → 파생 163/165 `labels=0`, `LS_DATA_AUG_LBL_MAP` 0행. [정적] `:386-389` |
| ~~TC-RESL-047~~ | (폐기) | 분모 제외. `ResolutionPersistService.java:306-360` 주석 실측 — "구 조건 *capturedAt 이후 신고 이력 존재* 는 제거됐다(순수 신고 결합)" 명시 확인. **폐기 표기 정확** |
| TC-RESL-048 | PASS | [실동작] ★**레이스로 강제 재현.** 905 요청 발사 40ms 후 새 `SUCCEEDED` procLog(다른 경로) INSERT → Phase A `snapshot ready`(01:46:25.612) → Phase B(25.783) → **Phase C `parent deident path changed since snapshot — abort (PII stale guard) parentRawSn=905 newRawSn=170`**(25.784) → cleanup + 예약해제 + RAW 170 삭제. 경로 해석 불가 시 `resolveQuietly` null → 불일치 취급도 코드 확인(`:362-369`). [정적] `:328-342` |
| TC-RESL-049 | PASS | [실동작] 905 비식별본 mtime `2030-01-01` 픽스처 → **`parent deident file replaced since snapshot (mtime) — abort`**(168) → 동일 정리 경로. `Files.exists` 선행 확인 후에만 판정, `IOException` 은 보수적 통과(`:355-359`). [정적] `:344-359` |
| TC-RESL-050 | PASS | [실동작] ★`'F'` 통과(905 `'F'` → 172 확정). [정적] `:94-103` `'N'`만 CONFLICT + 잠금 순서 parent(`:94`)→newRaw(`:113`) 고정 확인. 단위테스트 2건 |
| TC-RESL-051 | PASS | [정적] `:113-119` newRaw `findByRawSnForUpdate` 후 `'Y'` CAS → SKIPPED. IT `같은_파생RAW를_2스레드가_동시_finalize해도_프레임은_1회만_삽입된다(#5_승자보호)`(baseline 통과). 트리거가 AFTER_COMMIT 단일 러너라 실동작 중복 finalize 유도 불가 |
| TC-RESL-052 | PASS | [실동작] 부모 906(`anony/psdo/prvc = N/Y/Y`) → 파생 165 프레임 2행 모두 `N/Y/Y` 복사. `SRC_FILE_PATH_NM` = **null**(정책 A), `DE_IDNTF_SRC_FILE_PATH_NM` 만 채워짐. 부모 115(3필드 null) → 파생 161/162 도 null. [정적] `:275-292`, `LsDataSrc.create(9-arg)`+`normalizeYn`. ⚠ 최초 관측(163)에서 전부 NULL 로 보였으나 **동시 구동 중인 타 에이전트가 부모 필드를 그 사이 갱신**한 것 — 재현으로 정정(위 §1-5) |
| TC-RESL-053 | PASS | [실동작] 실패 3건(166·167·168·170) 전부 `reserved aug slot released dataAugSn=.. augTypeCd=RESL_720P` 로그 + aug 행 부재 → 동일 프리셋 재요청 성공(905 720P 재요청 → 173 정상). 락아웃 없음 확인. [정적] `:200-222` |
| TC-RESL-054 | **PARTIAL** | [정적] `:205-210` 라벨맵 참조 시 skip 은 구현돼 있으나, **부모 라벨 0건이면 승자가 `LS_DATA_AUG_LBL_MAP` 행을 만들지 않아 보호가 성립하지 않는다**(`copyScaledLabels` `:386-389` 가 0 반환하며 조기 return). 실측: 파생 163·165 는 `labels=0` → 매핑 0행. 1차 **E-ISSUE-62 미해소** → **E-ISSUE-64** |
| TC-RESL-055 | PASS | [정적] `:211-221` `RESL_` 접두 아니면 delete skip + WARN. 단위테스트 `releaseReservedAug_RESL_접두가_아니면_삭제하지않는다(오배송_방어)` |
| TC-RESL-056 | PASS | [정적] `:178-187` `findByRawSnForUpdate` + (`'Y'` \|\| COMPLETED). javadoc `:168-176` 이 잔여 창(승자-뒤짐·락 타임아웃 페일오픈)을 **정직하게 명시** — 카탈로그 기대와 일치. 단위테스트 2건 |
| TC-RESL-057 | PASS | [실동작] 163·165·171·172·173 전건 `starting …` → `[A] snapshot ready` → `[B] materialized` → `[C] persisted` → `resolution derivative finalize completed` 순차 로그, 예외 0. [정적] `AsyncResolutionRunner.java:56-86` |
| TC-RESL-058 | PASS | [정적] `:62-66` empty → 로그 후 return. 단위테스트 `PhaseA가_멱등skip이면_B_C_정리_전이_모두_수행하지않는다` |
| TC-RESL-059 | PASS | [정적] `:73-79` SKIPPED → cleanup 미실행 후 return. 단위테스트 `PhaseC가_SKIPPED_중복finalize패자면_정리도_FAILED전이도_하지않는다` |
| TC-RESL-060 | PASS | [정적] `:98-113` `isAlreadyFinalized` 가 cleanup(`:127-141`)보다 **앞**. 단위테스트 `실패했지만_이미_승자가_확정(Y)했으면_cleanup도_FAILED전이도_aug해제도_스킵한다(M-1_승자산출물보호)` |
| TC-RESL-061 | PASS | [실동작] 실패 4건 전부 `WARN derivative discarded — finalize failed, reservation released` → cleanup → `releaseReservedAug` → `markRawDataFailed` 순서 관측. 메트릭은 `resolutionMetrics.finalizeFailed()`(`:121`) 정적 확인. [정적] `:115-150` |
| TC-RESL-062 | **PARTIAL** | [실동작] **핵심 안전성은 충족** — 원본 프레임(`/app/storage/raw/frames/raw/26`,`/27` 전건)·원본 영상 무손상, 파생 프레임 디렉터리 `frames/deid/{166..170}` 재귀 삭제 완료, 성공 파생(165)의 디렉터리는 무손상, 경로 키에 파생 RAW_SN 포함으로 타 파생 미공유 확인. **미충족** — 파생 비디오는 **파일만** 지우고 상위 디렉터리를 남긴다: `videos/resolution/905/{168,170,97}`, `videos/resolution/94/100` 등 **빈 고아 디렉터리 4건 실측**. 게다가 `cleanup()` 은 `Files.exists(videoDst)`(파일)만 보므로 `clean=true` 를 반환해 러너가 RAW 행까지 지운다 → DB 포인터 없는 잔존물. 1차 **E-ISSUE-65 미해소** → **E-ISSUE-62** |
| TC-RESL-063 | PASS | [정적] `:47-50` `@Async` + `finalizeDerivative` 의 `catch (RuntimeException)`. @Async void 라 호출자 전파 없음. ⚠ `handleFailure` 자체는 try 밖 — 관련 잔여 위험은 E-ISSUE-67 |
| TC-RESL-064 | PASS | [정적] `LsDataAug.java:306-308`(`createResolutionPending`) → `:319-323`(`buildResolution` 의 `RESL_` 접두 검증 → INVALID_INPUT 400). 근거 라인 정확 |
| TC-RESL-065 | PASS | [정적] `LsDataAug.java:340-350` — `RESL_` 접두 아니면 400, `PENDING` 아니면 CONFLICT(409) "이미 처리된 해상도 파생 행입니다". 근거 라인 정확 |
| TC-RESL-066 | **PARTIAL** | [실동작] 삭제 자체는 정상 작동 — 166·167·168·170 전건 `failed derivative RAW removed` + DB 부재. 서비스 선검사 4조건(`:242-253`)도 구현됨. **불일치** — 최종 DELETE SQL(`VideoRepository.java:494-502`)에는 조건이 **3개뿐**(`ORGNL_RAW_SN IS NOT NULL`·`DATA_STTS_CD='FAILED'`·프레임 `NOT EXISTS`)이고 `DE_IDENT_YN <> 'Y'` 가 빠져 카탈로그의 "DELETE 문에도 동일 조건 동봉"과 어긋난다. 1차 **E-ISSUE-64 미해소** → **E-ISSUE-63** (카탈로그에 실측 주석 병기 완료) |
| TC-RESL-067 | PASS | [정적] `AsyncResolutionRunner.java:126-141`(`artifactsClean` 수집)·`:155-158`(잔존이면 `deleteFailedDerivativeRaw` 미호출 + WARN). 단위테스트 `cleanup이_잔존false를_반환하면_cleanupFailed_메트릭을_올린다`. 실동작 강제(공유 NAS 퍼미션 조작)는 타 에이전트 영향 우려로 미수행. ⚠ 빈 디렉터리는 `clean` 판정에 안 들어가므로 이 보호가 발동하지 않음(E-ISSUE-62 참조) |
| TC-RESL-068 | PASS | [정적] `ResolutionFileMaterializer.java:119-138` — `legacyDir.startsWith(legacyRoot) && !legacyDir.equals(legacyRoot)` 두 조건 + raw base 미설정은 `catch` 로 잔존 판정 미반영(`clean` 불변). [실동작] 현 환경엔 `/app/storage/raw/resolution` 자체가 없어 no-op(무해) 확인 |
| TC-RESL-069 | PASS | [실동작] 파생 163 의 `RAW_FILE_PATH_NM` = `/app/storage/deidentified/videos/resolution/**906/163**/RESL_720P.mp4` — **부모+파생 RAW_SN 2단 키** 확인. 잠정 `.pending/` 경로는 커밋본 어디에도 없음(DB 실측). [정적] `ResolutionReservationPersister.java:111-119,126-130` + `StorageSubtreePolicy.resolutionVideoFile:83-85` |
| TC-RESL-070 | PASS | [실동작] 부모 115(메타 7건: `0-5` + `video.{bit_rate,codec,duration_ms,filesize,fps,resolution}`) → 파생 161 에 **7건 전건 복사**(`video.*` 포함, 값 동일). 검수행: 부모 `VLM/APPROVED` 1건 → 파생 161·162 각각 `VLM/**PENDING**` 1건(APPROVED 미승계) ✔. 로그 `[MetaCopy] copied rawSn=161 orgnlRawSn=115 metas=7 reviews=1`. 부모 메타 0건(906)이면 `parentMetas=0` no-op ✔. [정적] `DerivedMetaCopier.java:72-158` |
| TC-RESL-071 | PASS | [실동작] 파생 161·162·163·165·171·172·173 전건이 `de_ident_yn='Y'` + `COMPLETED` + aug `ACCEPTED` 로 **확정 유지**(메타 배치 upsert 의 `clear()` 로 dirty 유실이 발생하지 않음). [정적] `ResolutionPersistService.java:145-148` 이 확정블록(`:134-143`) **뒤**에 위치, `DerivedMetaCopier.java:43-49` 순서 계약 javadoc 일치. IT `해상도_finalize_실DB에서_부모메타_전건복사_VLM검수행만_PENDING신규_이면서_파생RAW는_확정유지된다(순서계약_HIGH4)` |

### 근거(file:line) 드리프트
**0건.** E-5 42행의 근거 파일·라인을 전부 실제 소스와 대조했고 모두 정확했다(3차 앞선 회차의 전수 재확인 결과가 유지됨). 다만 아래 2건은 **근거 자체가 아니라 전제/보강 정보**의 정정 대상이었고 이번 회차에 카탈로그를 고쳤다.
- TC-RESL-037 / TC-RESL-048 — 전제의 상태값 표기 `SUCCESS` → 실제 `SUCCEEDED`
- TC-RESL-066 — DELETE SQL 실제 위치(`VideoRepository.java:494-502`) 미기재

---

## 3. E-5B (폐기 절) 확인

| 확인 항목 | 결과 |
|---|---|
| 절 헤딩 취소선 + 폐기 표기 | ✔ `## ~~E-5B. 해상도 파생 산출물 비식별 저장소 이관 백필~~ **[폐기 2026-07-30]**` |
| 16행(TC-RESL-080~095) 전건 `~~TC-RESL-0xx~~` | ✔ 16/16 |
| 폐기 사유·대체 케이스 없음 명시 | ✔ 블록인용에 사유·창(V125~V133 나흘)·환경별 확인·복구 커밋(`dedb67b1`) 기재 |
| 대상 코드 실제 삭제 여부 | ✔ `grep -rl 'ResolutionBackfill' backend/src` → **Java 소스 0건**(잔존은 `V133__derivative_pii_isolation_frame_view_gate.sql` 과 `CacheConfig.java` 의 주석 언급뿐) |
| 카탈로그 위생 | ⚠ 폐기 블록인용 안에 **폐기 전 원문 문단이 그대로 이어 붙어** 기능이 살아 있는 것처럼 읽혔다 → `*(폐기 전 원문 — 이력 보존용)*` 라벨 + 3차 재확인 문단 추가로 정정 |

**판정: 폐기 표기 정확.** 상세 검증 대상 아님(지시대로 스킵), 통과율 분모 제외.

---

## 4. 이전 회차 이슈 해소 여부 (1차 2026-08-01, E-5 관련)

| 1차 이슈 | 제목 | 3차 상태 | 근거 |
|---|---|:--:|---|
| E-ISSUE-61 | 파생 프레임 목적지 파일명이 부모 basename 파생 → 다른 프레임이 같은 파일로 덮어써짐 | **미해소** | 실동작 재현(163·165·98 각 2행→1파일). `ResolutionSnapshotService.java:205-206,231-234` 무변경 → 3차 **E-ISSUE-61** |
| E-ISSUE-62 | `releaseReservedAug` 승자 보호가 부모 라벨 0건에서 무효 | **미해소** | `copyScaledLabels:386-389` 조기 return 유지 → 3차 **E-ISSUE-64** |
| E-ISSUE-63 | Phase C stale 게이트 `IOException` 폴백 주석이 이미 폐기된 신고 게이트를 근거로 듦 | **미해소** | `ResolutionPersistService.java:356` "PII 는 ②신고 게이트로 닫힘" 잔존(+`:344` 주석 번호도 ①로 오기) → 3차 **E-ISSUE-65** |
| E-ISSUE-64 | 고아 파생 RAW DELETE 문에 `DE_IDENT_YN <> 'Y'` 누락 | **미해소** | `VideoRepository.java:494-502` 조건 3개 → 3차 **E-ISSUE-63** |
| E-ISSUE-65 | cleanup 이 파생 비디오 파일만 지우고 빈 디렉터리 트리를 남김 | **미해소** | 빈 고아 디렉터리 4건 실측 → 3차 **E-ISSUE-62** |
| E-ISSUE-24 | `LS_DATA_RAW.DE_IDNTF_YN` 에 소문자 `'y'` 존재 — 게이트 대소문자 민감 | **미해소**(참고) | `de_ident_yn='y'` 1행(raw_sn=68) 잔존, `hasDeidentArtifact()` 는 `"Y".equals \|\| "F".equals` 로 여전히 대소문자 민감. E-4 소관이라 이슈 재발행하지 않고 사실만 기록 |

> **E-5 관련 1차 이슈 5건 전부 미해소로 이월.** 수정 사이클이 아직 이 구간에 닿지 않았다.

---

## 5. 이슈 대장 (E-ISSUE-61 ~ 67)

### [E-ISSUE-61] TC-RESL-041 / TC-RESL-044 — 파생 프레임 목적 파일명이 부모 basename 에서 파생돼 **서로 다른 프레임이 한 파일로 덮어써진다**(무경고 확정)
- **심각도**: HIGH
- **기대 동작(기대효과)**: 부모 프레임 N건 → 파생 프레임 파일도 N건이어야 한다. 파생영상의 유일한 소비자는 관제서버이고(`CLAUDE.md` "파생영상에는 원본영상이 없다"), 관제는 `V_COMPLETED_FRAME`/export 폴더로 프레임 이미지를 픽업한다. 프레임 이미지가 서로 뒤바뀌면 **라벨 좌표는 프레임 A 것인데 픽셀은 프레임 B** 인 학습데이터가 마트로 나간다.
- **현재 동작(이슈 내용)**: 목적 경로가 `frames/deid/{newRawSn}/` + **부모 비식별 프레임의 basename** 으로 조립된다. 부모 프레임들이 서로 다른 디렉터리에 있고 파일명이 같으면 목적 경로가 충돌한다.
  ```java
  // ResolutionSnapshotService.java:205-206
  Path fdst = resolveSafeDir(base,
          StorageSubtreePolicy.deidFramesDir(newRawSn) + "/" + fileNameOf(deidFrameSrc, pf));
  // :231-234
  private static String fileNameOf(String frameSrc, LsDataSrc frame) {
      Path name = Paths.get(frameSrc).getFileName();
      return name != null ? name.toString() : (frame.getFrameNo() + ".jpg");
  }
  ```
  중복 방지는 `seenFrameKeys`(videoFrameNo, `:194-199`)뿐이고 **목적 파일명 중복은 검사하지 않는다**. Phase B 는 `imageResizer.resize(f.deidSrc(), f.dst(), …)` 를 순차 실행하므로 뒤 프레임이 앞 프레임을 덮어쓰고, Phase C 는 그 사실을 모른 채 `LS_DATA_SRC` N행을 모두 같은 경로로 INSERT + `markDeidentified('Y')` + `markCompleted()` + aug `ACCEPTED` 로 **정상 확정**한다. 경고 로그조차 없다(`[B] materialized rawSn=163 frames=2` 는 스펙 개수를 셀 뿐).
- **재현/확인 경로**:
  ```bash
  # 부모 906: 프레임 2건의 deid 경로가 서로 다른 디렉터리 + 동일 basename
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT src_sn, frm_no, de_idntf_src_file_path_nm FROM ls_data_src WHERE raw_sn=906 ORDER BY frm_no;"
  #  464 | 0 | /app/storage/deidentified/frames/deid/26/frame-0.jpg
  #  465 | 1 | /app/storage/deidentified/frames/deid/27/frame-0.jpg

  curl -s -X POST localhost:18081/api/v1/videos/906/resolution -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' -d '{"presets":["RESL_1080P"]}'   # -> 201 rawSn=165

  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT raw_sn, count(*) rows, count(DISTINCT de_idntf_src_file_path_nm) paths
       FROM ls_data_src WHERE raw_sn IN (98,163,165) GROUP BY 1;"
  #  98|2|1   163|2|1   165|2|1     <-- 2행이 같은 1경로
  docker exec klid-backend ls /app/storage/deidentified/frames/deid/165/
  #  frame-0.jpg                    <-- 파일도 1개뿐 (정상 파생 171 은 frame-0..4 5개)
  ```
- **영향**: 데이터 무결성 — 파생영상의 프레임 픽셀↔라벨 불일치가 **조용히** 관제/데이터마트로 유출. 손실된 프레임의 원본 픽셀은 파생본에 존재하지 않는다(복구 불가, 재생성만 가능). 현재 부모 프레임은 대개 `frames/deid/{parentRawSn}/frame-N.jpg` 단일 디렉터리라 일상 경로에서는 충돌하지 않지만, **재비식별·재추출로 프레임이 다른 디렉터리에 흩어진 부모**(실환경에 이미 존재)에서 발생한다. CWE-706(경로 이름 부적절 해석) 계열.
- **수정 방향(제안)**: 목적 파일명을 **부모 파일명이 아니라 파생 자신의 프레임 키**로 만든다 — 예 `frame-{frameNo}.{ext}` 또는 `{videoFrameNo}.{ext}`(부모 프레임 유일성 검사와 같은 축). 확장자만 소스에서 취한다. 추가로 `buildFrameSpecs` 에 **목적 경로 중복 fail-fast**(`seenFrameKeys` 와 같은 방식으로 `seenDst`)를 넣어 규약 위반이 다시 생겨도 확정 전에 멈추게 한다. 기존 충돌 파생(98·163·165 등)은 재생성 대상 식별 쿼리(`count(*) <> count(DISTINCT de_idntf_src_file_path_nm)`)로 뽑아 별도 정리.

### [E-ISSUE-62] TC-RESL-062 / TC-RESL-067 — cleanup 이 파생 비디오 **파일만** 지우고 디렉터리를 남기며, 그 잔존을 `clean=true` 로 오판한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 확정 실패 파생의 Phase B 산출물은 흔적 없이 정리돼야 한다. 특히 `cleanup()` 의 반환값은 러너가 "RAW 행(유일한 DB 포인터)을 지워도 되는가"를 판단하는 근거(`AsyncResolutionRunner.java:155-158`)이므로, 잔존물이 있으면 `false` 여야 한다.
- **현재 동작(이슈 내용)**: 프레임 디렉터리는 `deleteRecursivelyQuietly` 로 재귀 삭제되지만, 파생 비디오는 파일 1개만 삭제되고 그 부모 디렉터리(`videos/resolution/{parentRawSn}/{newRawSn}/`)가 남는다. 잔존 판정도 파일만 본다.
  ```java
  // ResolutionFileMaterializer.java:141-152
  if (videoDst != null) {
      try {
          deleteFileQuietly(videoDst);
          if (Files.exists(videoDst)) { clean = false; }   // 파일만 확인 — 상위 디렉터리는 미검사
      } catch (RuntimeException e) { clean = false; ... }
  }
  ```
  결과적으로 `clean=true` → 러너가 `deleteFailedDerivativeRaw` 로 RAW 행까지 지워 **DB 어디서도 참조되지 않는 빈 디렉터리**가 영구 누적된다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend find /app/storage/deidentified/videos/resolution -mindepth 2 -maxdepth 2 -type d -empty
  # /app/storage/deidentified/videos/resolution/905/168
  # /app/storage/deidentified/videos/resolution/905/170
  # /app/storage/deidentified/videos/resolution/905/97
  # /app/storage/deidentified/videos/resolution/94/100        (4건)
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT count(*) FROM ls_data_raw WHERE raw_sn IN (168,170,97,100);"   # -> 대응 RAW 없음
  ```
- **영향**: PII 노출은 없다(빈 디렉터리). 다만 NAS inode 무한 누적 + 운영자가 "이 디렉터리는 뭐지"를 추적할 DB 포인터가 없다. 5,000건 영상 × 3프리셋 재시도 규모에서 축적된다.
- **수정 방향(제안)**: `videoDst` 삭제 후 **부모 디렉터리가 비어 있으면 함께 제거**(`Files.deleteIfExists(videoDst.getParent())`, 단 `videos/resolution/{parentRawSn}` 루트까지 올라가지 않도록 파생 RAW_SN 세그먼트 1단만). 그리고 그 디렉터리 잔존도 `clean` 판정에 포함해 `deleteFailedDerivativeRaw` 보호(TC-RESL-067)와 일관되게 한다. 기존 4건은 일회성 스크립트로 정리(`-type d -empty` + DB 미참조 확인 후).

### [E-ISSUE-63] TC-RESL-066 — 고아 파생 RAW `DELETE` SQL 에 `DE_IDENT_YN <> 'Y'` 가 빠져 "검사 조건 = SQL 조건" 계약이 성립하지 않는다 (1차 E-ISSUE-64 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그·javadoc 이 명시한 대로 "잠금 후 4조건 재확인 + **최종 DELETE 문에도 동일 조건 동봉**"이어야 검사~삭제 사이 창이 닫힌다. `LS_DATA_SRC.RAW_SN` 에 FK 가 없어 DB 가 대신 막아주지 않으므로 SQL 조건이 마지막 방어선이다.
- **현재 동작(이슈 내용)**: 서비스는 4조건을 검사하는데 SQL 은 3조건뿐이다.
  ```java
  // ResolutionPersistService.java:246 — 서비스 선검사에는 있다
  if ("Y".equals(raw.getDeIdntfYn()) || !LsDataRaw.DATA_STTS_FAILED.equals(raw.getDataSttsCd())) { ... return false; }
  ```
  ```sql
  -- VideoRepository.java:495-501 — SQL 에는 없다
  DELETE FROM LS_DATA_RAW
   WHERE RAW_SN = :rawSn
     AND ORGNL_RAW_SN IS NOT NULL
     AND DATA_STTS_CD = 'FAILED'
     AND NOT EXISTS (SELECT 1 FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn)
  ```
- **재현/확인 경로**: 정적. `sed -n '494,502p' backend/src/main/java/kr/co/cudo/authoring/video/repository/VideoRepository.java`. 실동작 삭제 경로 자체는 정상(166·167·168·170 삭제 확인) — 결함은 **경합 창**에서만 드러난다: 검사 통과 후 DELETE 직전에 승자 Phase C 가 `markDeidentified('Y')` + `markCompleted()` 를 커밋하면 `DATA_STTS_CD` 가 `COMPLETED` 로 바뀌어 실제로는 SQL 이 0건 삭제하므로 **현 상태 머신에서는 사고가 나지 않는다**(`'Y'` + `FAILED` 조합이 만들어지지 않기 때문). 즉 위험은 잠재적이고, 문제는 **문서화된 계약과 코드의 불일치**다.
- **영향**: 현재 데이터 손실 위험은 낮음(위 근거). 그러나 향후 `'Y'` 와 `FAILED` 가 공존하는 전이(예: 확정 후 후처리 실패로 FAILED 표기)가 생기면 **확정된 파생 RAW 가 삭제**된다. 방어 심층화 위반.
- **수정 방향(제안)**: DELETE 문에 `AND (DE_IDENT_YN IS NULL OR DE_IDENT_YN <> 'Y')` 를 추가해 서비스 선검사와 1:1로 맞춘다(`VideoRepository.java:495-501`). 회귀 가드로 `ResolutionPersistServiceTest.고아행_정리_직전_상태가_바뀐_행은_삭제되지_않는다` 에 "`'Y'` 로 바뀐 FAILED 행" 케이스를 추가. 카탈로그 TC-RESL-066 에는 이번 회차에 실측 주석을 병기해 두었다.

### [E-ISSUE-64] TC-RESL-054 — `releaseReservedAug` 의 "승자 보호"가 **부모 라벨 0건 영상에서 무효** (1차 E-ISSUE-62 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 동시 finalize 에서 패자의 실패 정리가 **승자의 예약 aug 행을 지우면 안 된다**. 지우면 승자의 파생 RAW 는 확정돼 있는데 `LS_DATA_AUG`(RESL_*) 행이 사라져 ①증강 이력(`GET /v1/augments`)에서 사라지고 ②`new_raw_sn` 매핑이 끊겨 파생 식별 근거가 없어지며 ③같은 (부모, 프리셋) 재요청이 통과해 중복 파생이 생긴다.
- **현재 동작(이슈 내용)**: 보호 근거가 `LS_DATA_AUG_LBL_MAP` 참조 존재 **하나뿐**인데, 그 매핑은 부모에 라벨이 있어야만 만들어진다.
  ```java
  // ResolutionPersistService.java:205-210
  List<LsDataAugLblMap> refs = lblMapRepository.findAllByDataAugSn(dataAugSn);
  if (!refs.isEmpty()) { ...skip... }        // 라벨 0건이면 refs 는 항상 비어 있다
  // :386-389 — 승자조차 매핑을 만들지 않는다
  List<LsDataLbl> parentLabels = lblRepository.findBySrcSnIn(parentSrcToNewSrc.keySet());
  if (parentLabels.isEmpty()) { return 0; }
  ```
- **재현/확인 경로**:
  ```bash
  # 부모 906 은 라벨 0건 -> 파생 163/165 확정 후에도 매핑 0행
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT (SELECT count(*) FROM ls_data_lbl l JOIN ls_data_src s ON s.src_sn=l.src_sn WHERE s.raw_sn=906) parent_lbl,
            (SELECT count(*) FROM ls_data_aug_lbl_map WHERE data_aug_sn IN (59,60)) map_rows;"
  # parent_lbl=0, map_rows=0   -> 이 aug 는 releaseReservedAug 가 무조건 삭제한다
  ```
  실제 동시 finalize 는 트리거가 단일 AFTER_COMMIT 러너라 현 배선에서는 재현되지 않는다(잠재 결함).
- **영향**: 데이터 정합 — 라벨이 아직 없는(막 검수 승인된, 오토라벨 0건인) 영상의 파생에서 승자 예약행 소실. 파생 등재 게이트(`CLAUDE.md` "등재 게이트의 축은 리뷰 행")의 해상도 예외 판정 근거인 매핑 행이 사라지는 것도 부작용.
- **수정 방향(제안)**: 보호 근거를 라벨맵이 아니라 **파생 RAW 확정 상태**로 바꾼다 — `releaseReservedAug(dataAugSn)` 진입 시 `aug.getNewRawSn()` 으로 파생 RAW 를 조회해 `deIdntfYn='Y' || DATA_STTS_CD=COMPLETED` 이면 삭제 skip. 라벨맵 검사는 보조로 유지. 또는 `aug.augProcSttsCd == ACCEPTED`(확정 전이 완료)면 skip — `markResolutionGenerated` 가 승자 커밋에서만 일어나므로 라벨 유무와 무관한 판정축이 된다.

### [E-ISSUE-65] TC-RESL-049 — Phase C stale 게이트의 `IOException` 폴백 주석이 **이미 폐기된 신고 게이트**를 안전 근거로 든다 (1차 E-ISSUE-63 이월)
- **심각도**: LOW (주석/추적성 — 동작 영향 없음)
- **기대 동작(기대효과)**: fail-open 분기(`stat` 실패 시 통과)의 주석은 "왜 통과시켜도 안전한가"의 **현재 유효한** 근거를 제시해야 한다. 폐기된 게이트를 근거로 들면 다음 사람이 "다른 게이트가 막아주니 괜찮다"고 오판한다.
- **현재 동작(이슈 내용)**:
  ```java
  // ResolutionPersistService.java:355-359
  } catch (IOException e) {
      // stat 실패는 결정적 게이트(①②)가 이미 통과했으므로 보수적으로 통과(로그만). PII 는 ②신고 게이트로 닫힘.
      log.warn("[Video][ResolutionDerivative][C] deident mtime check skipped ...");
  }
  ```
  "②신고 게이트" 는 2026-07-30 에 **제거**됐다(같은 파일 `:312-314` 이 "구 조건 *capturedAt 이후 신고 이력 존재* 는 순수 신고 결합이라 제거됐다"고 명시). 또한 `:344` 의 두 번째 조건 주석이 `// ①` 로 번호가 잘못 붙어 있어(앞선 `:328` 도 `①`) 두 조건이 구분되지 않는다.
- **재현/확인 경로**: 정적. `sed -n '344,359p' backend/src/main/java/kr/co/cudo/authoring/video/service/ResolutionPersistService.java`
- **영향**: 추적성/유지보수. 이 저장소의 "철회된 정책 재시도" 사고 패턴(조상/자손 전파 4라운드)과 같은 뿌리 — 폐기된 정책이 주석에 살아 있으면 되살아난다.
- **수정 방향(제안)**: 주석을 실제 근거로 교체 — *"경로 게이트(①)가 결정적으로 통과했고, 파생 RAW 는 이 시점까지 `deIdntfYn='N'` 이라 미서빙이므로 mtime 미확인을 보수적으로 통과시킨다"*. 두 번째 조건 번호를 `②` 로 정정.

### [E-ISSUE-66] TC-RESL-033 — 중복/경합 예약 패자가 API 표면에서 **409 가 아니라 500** 으로 응답된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 예약 계층은 정확히 409 CONFLICT("동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.")를 던진다. 단일 프리셋 요청에서 그 원인이 **클라이언트가 고칠 수 있는 상태 충돌**이라면 API 도 4xx 로 알려줘야 FE 가 "이미 있음"과 "서버 오류"를 구분해 안내·재시도 정책을 나눌 수 있다. 특히 `CLAUDE.md` 는 파생 중복의 **연타 방어를 FE 단독 책임**으로 두고 있어, FE 가 응답으로 원인을 알 수 있어야 한다.
- **현재 동작(이슈 내용)**: `VideoResolutionService.changeResolution` 이 프리셋별 실패를 격리하며 예외를 삼키고, 전부 실패하면 일괄 500 을 낸다.
  ```
  WARN  VideoResolutionService - [Video][Resolution] derivative creation failed rawSn=906 preset=RESL_720P reason=CustomException
        at ResolutionReservationPersister.reserveAndCreate(ResolutionReservationPersister.java:107)   <-- 내부는 CONFLICT
  ERROR VideoResolutionService - [Video][Resolution] all presets failed rawSn=906 attempted=1
  ```
  ```json
  HTTP 500 {"success":false,"message":"요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다.","errorCode":"INTERNAL_ERROR"}
  ```
- **재현/확인 경로**:
  ```bash
  # (1) 순차 중복
  curl -s -w '\n%{http_code}\n' -X POST localhost:18081/api/v1/videos/906/resolution \
    -H "Authorization: Bearer $REV" -H 'Content-Type: application/json' -d '{"presets":["RESL_720P"]}'
  # -> 500 INTERNAL_ERROR  (내부 원인은 409 CONFLICT)

  # (2) 동시 경합 3발
  for i in 1 2 3; do curl -s -o /tmp/c$i -w "req$i %{http_code}\n" -X POST \
    localhost:18081/api/v1/videos/905/resolution -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' -d '{"presets":["RESL_720P"]}' & done; wait
  # -> req3 201 / req1 500 / req2 500 ... aug 행은 정확히 1건(직렬화 자체는 정상)
  ```
- **영향**: 기능/UX. FE 가 `errorCode=INTERNAL_ERROR` 만 보고는 "장애"로 오인해 재시도 루프를 돌거나 사용자에게 잘못된 안내를 한다. 500 은 모니터링 알람도 오염시킨다(정상 중복 클릭이 서버 에러로 집계). 보안 영향은 없다.
- **수정 방향(제안)**: `VideoResolutionService` 의 롤업 규칙을 **원인 코드 보존형**으로 바꾼다 — 시도한 프리셋이 전부 실패했고 그 실패가 **모두 동일한 4xx `CustomException`** 이면 그 코드/메시지를 그대로 전파하고, 혼재·5xx 포함일 때만 현재의 500 롤업을 유지한다. E-4 의 "1건 이상 성공=201 / 전부 실패=500 / 전부 스킵=400" 계약과 충돌하지 않도록 카탈로그(E-4)도 함께 갱신 필요 — **이 판단은 E-4 담당 범위와 겹치므로 병합 시 조정 요망**.

### [E-ISSUE-67] TC-RESL-061 / TC-RESL-066 — `handleFailure` 의 `markRawDataFailed` 가 무가드라 예외 시 고아 RAW 정리(④)가 건너뛰어진다
- **심각도**: LOW
- **기대 동작(기대효과)**: 실패 정리의 모든 단계는 best-effort 이며 "원래 실패를 가리지 않는다"가 이 메서드의 명시 계약(`AsyncResolutionRunner.java:90-91`). 앞 단계 실패가 뒤 단계를 통째로 건너뛰게 하면 안 된다.
- **현재 동작(이슈 내용)**: cleanup(`:128-140`)과 `releaseReservedAug`(`:144-149`)는 각각 try/catch 로 감싸져 있으나 `markRawDataFailed` 는 맨몸이다.
  ```java
  // AsyncResolutionRunner.java:150
  batchTransitionService.markRawDataFailed(newRawSn);
  // :159-164  ← 위에서 예외가 나면 이 블록에 도달하지 못한다
  try { persistService.deleteFailedDerivativeRaw(newRawSn); } catch (RuntimeException re) { ... }
  ```
- **재현/확인 경로**: 정적. 관련 관측 사실 — 파생 RAW 는 `LS_RAW_DATA_STATUS` 행이 없어 매 실패마다 WARN 이 뜬다: `[BatchTransition] raw data status not found rawSn=170 target=FAILED`(정상 폴백 경로이며 결함 아님, 다만 이 WARN 이 실패 로그에 늘 섞여 실제 이상 신호를 가린다).
- **영향**: 관측성/누적. `markRawDataFailed` 가 던지는 상황(락 타임아웃 등)에서 고아 파생 RAW 가 정리되지 않고 남는다. E-ISSUE-23(고아 무한 누적) 재발 경로.
- **수정 방향(제안)**: `markRawDataFailed` 호출도 try/catch 로 감싸고, 실패해도 ④ 로 진행한다. 더불어 파생 RAW 에 대한 `raw data status not found` WARN 은 **파생(ORGNL_RAW_SN != null)일 때 DEBUG 로 낮추거나 메시지에 "파생 — 정상"을 명시**해 실패 로그의 신호대잡음비를 높인다.

---

## 6. 카탈로그 정정 내역 (담당 범위 내 Edit, 총 4건 + 절 위생 1건)

| 대상 | 정정 내용 | 사유 |
|---|---|---|
| TC-RESL-037 | 전제 `SUCCESS procLog 없음` → `최신 성공 procLog 없음 — ⚠ 실제 적재 상태값은 **SUCCEEDED**` + 근거에 `findLatestSuccessByDataRawSn(REQ_DT DESC)` 추가 | DB 실측값이 `SUCCEEDED`. 3차 검증 중 `proc_stts_cd='SUCCESS'` 로 조회해 "procLog 0건"으로 **실제 오판이 발생**했다 |
| TC-RESL-048 | 전제 `최신 SUCCESS procLog` → `최신 성공(SUCCEEDED) procLog` | 동일 |
| TC-RESL-066 | 근거에 `VideoRepository.java:494-502` 추가 + "DELETE SQL 실측 3조건뿐(`DE_IDENT_YN<>'Y'` 누락, E-ISSUE-63)" 주석 병기 | 기대결과가 주장하는 "동일 조건 동봉"의 검증 지점이 카탈로그에 없었다. **기대결과 자체는 낮추지 않았다**(결함을 숨기지 않기 위해) |
| TC-RESL-041 | 기대결과에 ★"목적 파일 ↔ 부모 프레임 1:1" 불변식 명문화 + 실측 FAIL·E-ISSUE-61 참조 + 근거에 `ResolutionSnapshotService.java:205-206,231-234` 추가 | 이 불변식이 카탈로그에 없어 1차에서 지적된 결함(E-ISSUE-61)이 회차마다 "검증 대상 밖"으로 흘렀다 |
| E-5B 블록인용 | 폐기 문단 뒤에 이어 붙어 있던 **폐기 전 원문**에 `*(폐기 전 원문 — 이력 보존용)*` 라벨 부여 + 3차 재확인 문단(Java 소스 0건·16행 취소선 정상) 추가 | 폐기 절인데 본문이 기능이 살아 있는 것처럼 읽혔다 |

추가로 파일 상단 `## 변경 이력` 아래에 **[3차 · E-5 part4 추가 정정 2026-08-03]** 항목을 신설해 위 4건을 요약 기재했다.

> ⚠ 이 카탈로그 파일은 검증 중 **다른 part 에이전트도 동시에 편집**했다(Edit 도구가 "modified on disk" 경고). 본 정정은 전부 E-5/E-5B 담당 라인 범위 안에서 수행했고 타 절은 건드리지 않았다.

---

## 7. 실동작 환경 관련 특기사항 (다음 회차 참고)

1. **`proc_stts_cd` 값은 `SUCCEEDED`** — `SUCCESS` 로 조회하면 전건 0건. `_raw/` 문서·카탈로그의 "SUCCESS procLog" 표현에 주의.
2. **컬럼명 함정** — `LS_DATA_RAW.DE_IDENT_YN`(엔티티 필드는 `deIdntfYn`, 컬럼은 `DE_IDENT_YN`), `LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM`(엔티티 getter 는 `getDeidFilePath()`), `LS_RAW_DATA_STATUS.RAW_DATA_ID`(`RAW_SN` 아님), `LS_DATA_LBL` 은 bbox 컬럼이 없고 `POINT_CN` JSON 하나, `LS_DATA_AUG_LBL_MAP.ORGNL_DATA_LBL_SN`. `LS_DATA_RAW` 에 `RESL` 컬럼은 없다(해상도는 `LS_DATA_META.video.resolution`).
3. **공용 DB 동시 검증 주의** — 여러 part 에이전트가 같은 스택을 쓴다. 부모 영상의 상태를 파생 생성 **직후**에 읽으면 타 에이전트의 갱신이 섞여 거짓 FAIL 이 난다(§1-5 실제 사례). 부모 상태는 생성 직전에 스냅샷할 것.
4. **이번 회차 픽스처 활용** — 1·2차가 만들어 둔 검증용 영상이 그대로 유효했다: 903(중복 vdo_frm_no) · 902(deid 경로 결손) · 905(`qa-e5/stale.mp4` mtime 2030) · 906(`QA-E5-DSTCOLLIDE`, deid basename 충돌) · 900(`'F'` + 3프리셋). 다음 회차도 재사용 권장.
5. **미검증으로 남은 것** — TC-RESL-043 의 429 실발화(픽스처 프레임이 2~6장이라 세마포어 포화 불가), TC-RESL-067 의 아티팩트 잔존 강제(공유 NAS 퍼미션 조작 필요), TC-RESL-051/059/060 의 실제 중복 finalize(트리거가 단일 AFTER_COMMIT 러너). 전부 단위/IT 로 커버되어 있고 baseline 실패 0건이라 PASS 로 판정했으나, 대규모 영상(수백 프레임) 픽스처가 생기면 TC-RESL-043 은 실동작 재검증 가치가 있다.
