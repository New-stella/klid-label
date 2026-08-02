# E 클러스터 검증 — part4: E-5. 해상도 파생 예약/확정 (Reservation·Snapshot·Materialize·Persist·Runner)

> 대상: `docs/test-cases/E-augment-resolution-export-meta.md` `## E-5` — **TC-RESL-030 ~ TC-RESL-071 (42건)**
> (그중 **TC-RESL-047 은 폐기** → 검증 대상 **41건**)
> 검증일 2026-08-02 · 코드 기준 `qa-0801` 워크트리 · 실행 스택 `localhost:18081`(`/api`) + `klid-postgres`(`public` 스키마)

---

## 0. 검증 환경·실동작 근거 (요약)

| 항목 | 실측 |
|------|------|
| backend | `GET /api/actuator/health` → `{"status":"UP"}` |
| DB 마이그레이션 | `flyway_schema_history` **V158 까지 적용**(2026-08-01 23:10) — E-5 관련 V125/V155 모두 반영됨. (`pipeline-drive.md` 의 "V146 스테일 jar" 기록은 그 이후 재빌드로 해소된 상태) |
| `UK_LS_DATA_AUG_RESL` | `\d ls_data_aug` → `"uk_ls_data_aug_resl" UNIQUE, btree (src_sn, aug_type_cd) WHERE aug_type_cd::text ~~ 'RESL\_%'::text` — **술어에 상태 조건 없음(상태 무관)** ✅ |
| 실구동 성공 파생 | `rawSn 76/77/78`(부모 26, 3프리셋 — E-part3 담당 에이전트가 08:44:20 에 생성) · `rawSn 88`(부모 900, `'F'` 게이트 검증용) |
| 실구동 실패 파생 | `rawSn 89`(deid 프레임 경로 null) · `90`(중복 videoFrameNo) · `97`(Phase C mtime stale) — 전부 정리 후 RAW 삭제됨 |
| 메트릭 | `GET /api/actuator/metrics/resolution.finalize.failed` → `COUNT=2.0` (내 실패 유도 2건과 일치) |
| 자동테스트 baseline | `_raw/test-baseline.md` — backend 4,755 tests / 실패 0 / skip 5. E-5 관련 테스트 8파일(아래 §3) 전량 통과 |

### 검증용 투입 데이터 (INSERT-only, 기존 데이터 미수정 — 후속 회차 참고용으로 남김)

| rawSn | 용도 | 특징 |
|--:|------|------|
| 900 | `'F'`(신고) 게이트 통과 실증 | `de_ident_yn='F'`, 26의 프레임/deid경로 복제, SUCCESS procLog |
| 901 | `'N'` 게이트 차단 실증 | `de_ident_yn='N'` |
| 902 | Phase A deid 프레임 strict | frm_no=1 의 `de_idntf_src_file_path_nm=NULL` |
| 903 | Phase A 중복 videoFrameNo | frm 0/1 모두 `vdo_frm_no=7` |
| 905 | Phase C stale(mtime) | procLog 가 미래 mtime(2030-01-01) 파일 `/app/storage/deidentified/videos/qa-e5/stale.mp4` 를 가리킴 |
| 906 | 목적지 파일명 충돌(적대검증) | frm 0/1 의 deid 경로 basename 이 둘 다 `frame-0.jpg` |

> 생성 파일: `/app/storage/deidentified/videos/qa-e5/stale.mp4`(mtime 2030) — 검증 픽스처. 그 외 파일 생성/수정 없음. **코드·설정·테스트 파일은 일절 수정하지 않았다.**

---

## 1. 판정 결과표 (41건)

| ID | 판정 | 근거 확인 | 비고 |
|----|:----:|-----------|------|
| TC-RESL-030 | PASS | [실동작] 예약 로그 `derivative reserved parentRawSn=900 newRawSn=88 dataAugSn=30 preset=RESL_720P src=320x240 target=1280x720`(08:47:27.760, http 스레드) 직후 **같은 밀리초에** `batch-async-1` 에서 `AsyncResolutionRunner starting ... rawSn=88` → AFTER_COMMIT 트리거 확인. `ls_data_aug`(dataAugSn=30) 는 `RESL_720P`, `ls_data_raw` 88 은 `orgnl_raw_sn=900`·`aug_type_cd=RESL_720P` | 예약 시점 PENDING 은 `ResolutionDerivativeFlowIntegrationTest`("예약직후 finalize 전에는 aug 가 PENDING") 가 커버. 근거 라인 `68-123` → 실제 `68-130` (경미 드리프트) |
| TC-RESL-031 | PASS | [실동작] `POST /v1/videos/901/resolution`(`'N'`) → **409** `{"message":"비식별 산출물이 있는 원본 영상만 파생영상을 만들 수 있습니다.","errorCode":"CONFLICT"}` + 로그 `[Video][DeidArtifact] parent has no deident artifact — reject at request rawSn=901 deIdntfYn=N`. `POST /v1/videos/900/resolution`(`'F'`) → **201 CREATED** `rawSn=88` → **★`'F'` 통과 실증** | 요청 입구 `ParentDeidArtifactGuard`(같은 `hasDeidentArtifact()` 판정, 메시지 축 동일)가 먼저 걸러 예약 게이트 문구("…해상도 파생영상을 만들 수 있습니다.")는 잠금 하 재검증용. 판정 헬퍼는 `LsDataRaw.hasDeidentArtifact()` 단일 원천(`'Y'\|'F'`, LsDataRaw.java:444-446) — 3게이트 + `AugmentResultService:390` 이 동일 사용 |
| TC-RESL-032 | PASS | [정적] `ResolutionReservationPersister.java:92-94` — `firstFrameSrcSn==null` → `INVALID_INPUT("대표 프레임을 확인할 수 없습니다.")`, `augRepository.save`(101) **이전**. 테스트 `ResolutionReservationPersisterTest`("srcSn_null이면_LS_DATA_AUG_INSERT전에_INVALID_INPUT을_던진다") | API 경로에서는 `VideoResolutionService.measureFirstFrame` 이 먼저 "실측할 프레임이 없습니다"로 거부하므로 이 분기는 방어적 재검증 |
| TC-RESL-033 | PASS | [실동작] 이미 파생이 있는 `rawSn=26`·`900` 에 동일 프리셋 재요청 → PG `ERROR: duplicate key value violates unique constraint "uk_ls_data_aug_resl" Detail: Key (src_sn, aug_type_cd)=(296, RESL_720P) already exists.` → `CustomException: 동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.`(`ResolutionReservationPersister.java:107`) | 인덱스 술어에 상태 조건 없음 = **상태 무관** ✅. 오케스트레이터(E-4)가 "전 프리셋 실패"를 500 으로 매핑하므로 HTTP 는 500 — E-4 확정 정책과 정합(예약 계층 판정은 409) |
| TC-RESL-034 | PASS | [정적] `ResolutionReservationPersister.resolveSafeDir` — `base.resolve(relative).normalize()` 후 `StorageSubtreePolicy.isDeidentifiedArtifact(base, resolved)` 실패 시 `INVALID_INPUT("출력 경로가 허용된 비식별 저장 경로를 벗어납니다.")`. `isDeidentifiedArtifact` 내부가 `relativeUnder`(=`startsWith(base)`) + `frames/deid`\|`videos` 세그먼트 접두를 **둘 다** 요구 → 두 base 동일 설정에서도 `frames/raw/**` 차단 | **근거 드리프트**: 카탈로그 `157-163` → 실제 **164-170**. 이 경로에 사용자 입력이 도달하지 않아(경로는 전부 내부 상수 + rawSn) 실동작 반증은 불가 |
| TC-RESL-035 | PASS | [정적] `ResolutionSnapshotService.java:102-106` — `"Y".equals(newRaw.getDeIdntfYn())` → `Optional.empty()` (부모 재잠금 이전). 테스트 `ResolutionSnapshotServiceTest`("이미확정된_파생RAW면_멱등skip하고_부모재잠금을_하지않는다") + IT("확정된_파생을_PhaseA가_멱등skip하고_프레임이_중복생성되지_않는다") | |
| TC-RESL-036 | PASS | [실동작] 부모 900(`'F'`)에서 Phase A 통과 실증 — `[Video][ResolutionDerivative][A] snapshot ready rawSn=88 parentRawSn=900 frames=5 scale=3.0 offset=160,0`. [정적] `'N'` 차단은 `ResolutionSnapshotService.java:116-122` `CONFLICT("비식별 산출물이 있는 원본 영상만 파생영상을 확정할 수 있습니다.")` + 테스트 2건 | ★`'F'` 통과 = 2026-07-29 확정 정책과 일치. 결함 아님 |
| TC-RESL-037 | PASS | [정적] `ResolutionSnapshotService.java:149-153` — `findLatestSuccessByDataRawSn(...).map(getDeIdntfFilePathNm).filter(non-blank).orElseThrow(NOT_FOUND "원본 비식별 영상 경로를 찾을 수 없습니다: parentRawSn=…")`. 테스트 "확정게이트1_부모의_비식별_비디오_procLog가_없으면_NOT_FOUND로_거부한다(E-29)" | API 경로에선 `ParentDeidArtifactGuard` 가 먼저 409 로 거름 → Phase A 분기는 예약~확정 창 방어 |
| TC-RESL-038 | PASS | [정적] `ResolutionSnapshotService.java:161-164` `INTERNAL_ERROR("파생할 프레임이 없습니다: parentRawSn=…")`. 테스트 "부모_프레임이_0건이면_fail_fast로_거부한다(#9)" | `measureParentDimensions`(216-221)가 **동일 코드·동일 메시지**로 더 앞에서 던진다 — 기대 단언은 어느 경로로도 성립 |
| TC-RESL-039 | PASS | [실동작] 부모 903(frm 0/1 모두 `vdo_frm_no=7`) → 예약 성공(`newRawSn=90`) 후 `finalize failed rawSn=90 cause=CustomException` → `reserved aug slot released dataAugSn=32` → `failed derivative RAW removed rawSn=90`. 903 은 프레임 경로·파일이 전부 정상이라 **중복 videoFrameNo 외 실패 요인이 없음**(차분 실증). [정적] `ResolutionSnapshotService.java:195-199` `INTERNAL_ERROR("부모 프레임에 중복 videoFrameNo 가 있습니다: …")` | |
| TC-RESL-040 | PASS | [실동작] 부모 902(frm 1 의 `de_idntf_src_file_path_nm=NULL`) → `newRawSn=89` 예약 후 finalize 실패 → RAW 삭제. **원본(`frames/raw/**`) 폴백으로 진행하지 않음** 확인(파생 프레임 0건, 디렉터리 미생성). [정적] `ResolutionSnapshotService.java:240-247` `CONFLICT("비식별 프레임 경로가 없어 파생영상을 생성할 수 없습니다: srcSn=…")` | 원본 픽셀 복제 + `'Y'` 위장 차단 실증 |
| TC-RESL-041 | PASS | [실동작] 부모 26 비식별본 `95b05485-…-mask.mp4` **50,854 bytes** ↔ 파생 3본 `videos/resolution/26/{76,77,78}/RESL_*.mp4` 전부 **50,854 bytes**(재인코딩 없는 복사). `ffprobe` 프레임 실측 — 부모 `320x240` → 76 `1920x1080` / 77 `1280x720` / 78 `854x480`. [정적] `ResolutionFileMaterializer` 는 필드가 `ImageResizer`·`VideoFileCopier`·`ResizeConcurrencyGate` 3개뿐 → **리포지토리 주입 0 · `@Transactional` 0 · 부모 잠금 0** | |
| TC-RESL-042 | PASS | [정적] `ResolutionFileMaterializer.java:66-68` — `!videoFileCopier.exists(deidVideoSrc)` → `NOT_FOUND("원본 비식별 영상 파일을 찾을 수 없습니다.")`, 원본 폴백 분기 부재. 테스트 "비식별_비디오원본이_없으면_NOT_FOUND로_실패하고_게이트를_반환한다" | |
| TC-RESL-043 | PASS | [정적] `materialize`(62-80) 가 `resizeGate.acquire()`(63) → **비디오 복사(69) + 전 프레임 리사이즈(72-74)** → `finally { resizeGate.release(); }`(77-79). `ResizeConcurrencyGate` 는 단일 `@Component` 의 fair `Semaphore(max=2)` + `tryAcquire(5s)` → 초과 시 `TOO_MANY_REQUESTS(429)` | 세마포어를 서비스마다 두지 않아 상한 배수화 없음 확인 |
| TC-RESL-044 | PASS | [실동작] `[C] persisted rawSn=88 orgnlRawSn=900 dataAugSn=30 frames=5 labels=0 metas=0 metaReviews=0`. DB: `ls_data_src` 파생 5행 / `ls_data_aug`(30) `ACCEPTED`+`new_raw_sn=88` / `ls_data_raw`(88) `de_ident_yn='Y'`·`data_stts_cd='COMPLETED'` / `ls_deident_proc_log` `SUCCEEDED`·`reg_id='resolution-derivative'`·경로=파생 비디오. [정적] 순서 = 프레임(122) → 라벨(126) → 확정블록(134-143) → `copyMetaAndReviews`(148) | 확정 블록 **이후** 메타 복사 ✅(TC-RESL-071 과 동일 근거) |
| TC-RESL-045 | PASS | [실동작] `ls_data_aug_lbl_map` — dataAugSn 5/6/7(부모 4, 1080P/720P/480P): `scale_x==scale_y` = **4.5/4.5 · 3.0/3.0 · 2.0/2.0**, `coord_recalc_yn='Y'`. 좌표 실측(aug 5, 320×240→1920×1080, scale 4.5·offsetX 240·offsetY 0): BBOX `[[10,10],[50,50]]`→`[[285,45],[465,225]]`(=10·4.5+240 / 10·4.5), SKELETON `[0,0,0]`→`[240,0,0]`(가시성 플래그 보존), POLYGON 26점 전부 동일 변환. 로그 `offset=160,0`(1280×720) · `offset=107,0`(854×480) 가 `LetterboxTransform` 계산과 일치 | **축별 독립 배율 아님**(구 E-ISSUE-26 폐기) 실증. 매핑행에 오프셋 컬럼 없음(추적성 한계 — 카탈로그 명시대로) |
| TC-RESL-046 | PASS | [실동작] 부모 26 은 라벨 0건(`ls_data_lbl` 0) → `[C] persisted … labels=0`, `ls_data_aug_lbl_map`(15/16/17) 0행. [정적] `ResolutionPersistService.java:386-389` `parentLabels.isEmpty() → return 0` | |
| ~~TC-RESL-047~~ | — | **[폐기 2026-07-30]** 검증 대상 아님 | 분모 제외 |
| TC-RESL-048 | PASS | [정적] `ResolutionPersistService.java:328-342` — Phase C 가 `findLatestSuccessByDataRawSn` 재조회 후 `currentDeid==null \|\| !currentDeid.equals(snapshotDeid)` → `CONFLICT("스냅샷 이후 원본 비식별본이 변경되어 파생영상을 확정할 수 없습니다.")`. `resolveQuietly`(362-369)가 예외 시 `null` 반환 → **해석 불가도 불일치로 abort** ✅. 테스트 "stale창_최신비식별procLog경로가_스냅샷과_다르면_CONFLICT로_abort한다(H-1_①경로게이트)" + IT "진짜_A~C창_PhaseB중_부모비식별본이_신규경로로_교체되면_PhaseC가_CONFLICT하고_cleanup이_실제파일을_삭제한다" | A→C 창이 실측 ~40ms 라 수동 레이스 주입 불가 → IT 로 커버 |
| TC-RESL-049 | PASS | [실동작] 부모 905 의 비식별본 mtime 을 2030-01-01 로 만든 뒤 요청 → `[C] parent deident file replaced since snapshot (mtime) — abort (…) parentRawSn=905 newRawSn=97` → 러너 cleanup + FAILED + RAW 삭제. [정적] `344-359` — `Files.exists` **일 때만** 판정, `IOException` 은 WARN 후 통과 | `CustomException` 은 `RuntimeException` 이라 `catch (IOException)` 에 삼켜지지 않음(확인) |
| TC-RESL-050 | PASS | [실동작] 부모 900(`'F'`)에서 Phase C 통과 → `[C] persisted rawSn=88 orgnlRawSn=900`. [정적] `90-103` — `findByRawSnForUpdate(parentRawSn)`(94) → `findByRawSnForUpdate(newRawSn)`(113) 로 **parent → newRaw 잠금 순서 고정**, `'N'`/null 만 `CONFLICT` | |
| TC-RESL-051 | PASS | [정적] `111-119` — newRaw 재잠금 후 `"Y".equals(deIdntfYn)` → `Result.SKIPPED`(프레임 INSERT 이전). 테스트 IT "같은_파생RAW를_2스레드가_동시_finalize해도_프레임은_1회만_삽입된다(#5_승자보호)" | |
| TC-RESL-052 | PASS | [실동작] 파생 77 의 `ls_data_src` 5행 — `src_file_path_nm` **전부 NULL**, `de_idntf_src_file_path_nm=/app/storage/deidentified/frames/deid/77/frame-N.jpg`, `vdo_frm_no` 0/30/60/90/120(부모 보존), 개인정보 3필드 전부 NULL(부모가 NULL). [정적] `275-292` `LsDataSrc.create(newRawSn, frameNo, videoFrameNo, **null**, dst, shtDt, parent?.anony/psdo/prvc)` | 정책 A(파생엔 원본 픽셀 부재) 실증 |
| TC-RESL-053 | PASS | [실동작] 3회 실패 전부에서 `[C] reserved aug slot released dataAugSn=31/32/43 augTypeCd=RESL_720P/RESL_720P/RESL_480P` + `ls_data_aug` 잔존 0행 확인 → 동일 프리셋 재요청 락아웃 없음. [정적] `200-222` | |
| TC-RESL-054 | **PARTIAL** | [정적] `205-210` — `lblMapRepository.findAllByDataAugSn(dataAugSn)` 비어있지 않으면 `WARN` + 삭제 skip ✅ (**케이스의 문자적 단언은 충족**). 그러나 **부모 라벨이 0건이면 승자도 라벨맵을 만들지 않아 이 보호가 무효**다 — 실측으로 부모 26 은 라벨 0건이고 성공 파생(76/77/78)의 라벨맵도 0행이었다 | → **E-ISSUE-61** |
| TC-RESL-055 | PASS | [정적] `211-221` — `augTypeCd.startsWith(LsDataAug.RESL_PREFIX)` 아니면 `WARN`("reserved aug is not a resolution reservation — skip release") 후 미삭제. 테스트 "releaseReservedAug_RESL_접두가_아니면_삭제하지않는다(오배송_방어)" | |
| TC-RESL-056 | PASS | [정적] `178-187` — `findByRawSnForUpdate`(PESSIMISTIC_WRITE) 로 `deIdntfYn=='Y' \|\| dataSttsCd==COMPLETED` 판정, `readOnly` 미지정(PG 제약 대응). 잔여 창(승자-뒤짐 / 락 타임아웃 페일오픈)이 javadoc 161-176 에 명시돼 카탈로그 서술과 일치. 테스트 2건 | 카탈로그의 "정직한 한계 명시" 그대로 |
| TC-RESL-057 | PASS | [실동작] rawSn 88 로그 시퀀스: `starting …`(08:47:27.760) → `[A] snapshot ready`(…764) → `[B] materialized`(…854) → `[C] persisted`(…858) → `resolution derivative finalize completed rawSn=88`(…859). 예외 없음 | 76/77/78 도 동일 |
| TC-RESL-058 | PASS | [정적] `AsyncResolutionRunner.java:62-66` — `opt.isEmpty()` → INFO + `return`(B·C 미실행). 테스트 "PhaseA가_멱등skip이면_B_C_정리_전이_모두_수행하지않는다" | |
| TC-RESL-059 | PASS | [정적] `73-79` — `Result.SKIPPED` 시 INFO 후 `return`, `handleFailure`/cleanup 미호출. 테스트 "PhaseC가_SKIPPED_중복finalize패자면_정리도_FAILED전이도_하지않는다" | 승자 산출물 보호 |
| TC-RESL-060 | PASS | [정적] `98-113` — `handleFailure` 첫 블록이 `persistService.isAlreadyFinalized(newRawSn)` 이고 true 면 **cleanup(126-141)·release(145)·FAILED(150) 이전에** `return`. 재조회 예외는 보수적 false. 테스트 "실패했지만_이미_승자가_확정(Y)했으면_cleanup도_FAILED전이도_aug해제도_스킵한다(M-1_승자산출물보호)" | |
| TC-RESL-061 | PASS | [실동작] 실패 3건 전부 — `WARN [AsyncResolutionRunner] derivative discarded — finalize failed, reservation released rawSn=…` → `resolution.finalize.failed` **COUNT=2.0**(내 유도 2건; 나머지 1건은 동일 세션 후속) → (스냅샷 있는 97 은) 아티팩트 cleanup → `reserved aug slot released` → `markRawDataFailed` | `[BatchTransition] raw data status not found rawSn=… target=FAILED` WARN 은 파생에 `LS_RAW_DATA_STATUS` 행이 없어서 나는 정상 로그 — `ls_data_raw.data_stts_cd` 는 FAILED 로 전이됨(고아 삭제가 그 조건으로 성공한 것이 증거) |
| TC-RESL-062 | PASS | [실동작] rawSn 97 실패 후 — `frames/deid/97/` **디렉터리째 삭제**, `videos/resolution/905/97/RESL_480P.mp4` 삭제. 반면 부모 26 의 `frames/deid/26/` **5장 온전**, 부모 비식별 영상(`videos/qa-e5/stale.mp4`) 온전, `frames/raw/**` 무변동. [정적] cleanup 대상 키가 `deidFramesDir(newRawSn)` + `videoDst`(파생 RAW_SN 포함 경로) | 잔여: 빈 디렉터리 `videos/resolution/905/97/` 는 남는다(파일만 삭제) → **E-ISSUE-65**(LOW) |
| TC-RESL-063 | PASS | [실동작] Phase A/C 예외가 발생한 3건 모두 HTTP 응답은 **201 CREATED** 였고 async 예외가 호출자에 전파되지 않음. [정적] `runAsync`(47-50) → `finalizeDerivative` 가 `catch (RuntimeException)`(81-85)로 삼킴 | |
| TC-RESL-064 | PASS | [정적] `LsDataAug.buildResolution` — `augResTypeCd == null \|\| !startsWith(RESL_PREFIX)` → `INVALID_INPUT("해상도 파생 코드는 'RESL_' 접두여야 합니다.")` | **근거 드리프트**: 카탈로그 `LsDataAug.java:214` → 실제 **320-323**(`createResolutionPending` 은 306). 전용 단위테스트 없음(커버리지 갭) |
| TC-RESL-065 | PASS | [정적] `LsDataAug.markResolutionGenerated` — RESL 접두 가드(341-343) + `!STTS_PENDING.equals(augProcSttsCd)` → `CONFLICT("이미 처리된 해상도 파생 행입니다. status=…")` | **근거 드리프트**: 카탈로그 `248-249` → 실제 **340-348**(이중전이 가드 344-347) |
| TC-RESL-066 | PASS | [실동작] `[C] failed derivative RAW removed rawSn=89 / 90 / 97` + `ls_data_raw` 에 해당 행 부재 확인(고아 무한 누적 해소). [정적] `237-259` 잠금 후 4조건 재확인 + `VideoRepository.deleteFailedDerivative` 네이티브 DELETE 에 `ORGNL_RAW_SN IS NOT NULL AND DATA_STTS_CD='FAILED' AND NOT EXISTS(LS_DATA_SRC)` 동봉 | DELETE 문에는 `DE_IDENT_YN <> 'Y'` 만 빠져 있다(카탈로그 "동일 조건 동봉" 문구와 미세 불일치) — 같은 tx 의 `FOR UPDATE` 로 행이 잠겨 있고 확정 경로가 `'Y'` 와 `COMPLETED` 를 항상 동시 설정하므로 기능적 갭 없음 → **E-ISSUE-64**(LOW, 문서/방어 일치성) |
| TC-RESL-067 | PASS | [정적] `AsyncResolutionRunner.java:126-141`(cleanup 결과를 `artifactsClean` 에 수집, 잔존 시 ERROR + `resolution.cleanup.failed`) + `155-158`(`if (!artifactsClean) { WARN "keep FAILED derivative RAW — artifacts remain on disk"; return; }` → `deleteFailedDerivativeRaw` 미호출). 테스트 "cleanup이_잔존false를_반환하면_cleanupFailed_메트릭을_올린다" | 실동작으로 cleanup 실패를 유도하지 못함(파일 삭제가 항상 성공) |
| TC-RESL-068 | PASS | [정적] `ResolutionFileMaterializer.java:119-138` — `legacyRoot = {rawBase}/resolution`, `legacyDir = legacyRoot/{newRawSn}`, `legacyDir.startsWith(legacyRoot) && !legacyDir.equals(legacyRoot)` 통과 시에만 재귀 삭제. raw base 미설정은 `IllegalStateException` → catch 에서 로그만(=`clean` 불변, 잔존 판정 미반영) | **전용 테스트 없음**(`ResolutionFileMaterializerTest` 에 레거시 케이스 부재) — 커버리지 갭 |
| TC-RESL-069 | PASS | [실동작] `ls_data_raw.raw_file_path_nm` — `…/videos/resolution/26/76/RESL_1080P.mp4`, `…/26/77/RESL_720P.mp4`, `…/26/78/RESL_480P.mp4`, `…/videos/resolution/900/88/RESL_720P.mp4` → **키에 파생 RAW_SN 포함** ✅. 디스크에도 파생별 개별 파일 3개 존재(공유 0). [정적] 잠정 `.pending/` 경로(137-141)는 같은 트랜잭션에서 `assignDerivativeVideoPath`(119)로 즉시 교체 — 커밋된 값에 `.pending` 이 관측된 사례 0건(DB 전수 확인) | |
| TC-RESL-070 | PASS | [실동작] 부모 26 메타(metaSn=123, key `0-5`) → 파생 77 에 metaSn=161 로 값 동일 복사. 검수행: 부모 `(123, VLM, APPROVED, AI_SERVER)` → 파생 `(161, VLM, **PENDING**, AI_SERVER)` — **유형·출처 승계, APPROVED 미승계** ✅. 로그 `[C] persisted rawSn=87 … metas=1 metaReviews=1`. [정적] `DerivedMetaCopier.java:72-158` — `upsertMetaBatch` 단일 배치, `(metaSn, metaTypeCd)` 선재 skip(114-117,138-140) | `video.*` 전체 복사 정책은 UNCERTAINTIES #18 확정과 일치 |
| TC-RESL-071 | PASS | [정적] `ResolutionPersistService.java:145-148` — `copyMetaAndReviews` 가 확정 블록(`markResolutionGenerated` 135 / `markDeidentified` 138 / `markCompleted` 139 / procLog 143) **이후** 호출. `LsDataMetaRepositoryImpl.upsertMetaBatch:34-56` 이 `entityManager.flush()`(39) → 배치 → `entityManager.clear()`(55) 로 순서 계약 실체 확인. [실동작] 파생 77 이 **확정(`'Y'`+COMPLETED+ACCEPTED)** 과 **메타 복사** 를 동시에 만족 = 순서가 뒤바뀌지 않았음의 종단 증거. 테스트 IT "해상도_finalize_실DB에서_부모메타_전건복사_VLM검수행만_PENDING신규_이면서_파생RAW는_확정유지된다(순서계약_HIGH4)" | |

### 집계

| 판정 | 건수 |
|------|--:|
| PASS | **40** |
| PARTIAL | **1** (TC-RESL-054) |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| (폐기 제외) | TC-RESL-047 |
| **합계(검증 대상)** | **41** |

---

## 2. 이슈 대장 (E-ISSUE-61 ~ 65)

### [E-ISSUE-61] TC-RESL-054 — 파생 프레임 목적지 파일명이 부모 프레임 basename 에서 파생되어 **서로 다른 프레임이 같은 파일로 덮어써진다**(무경고 확정)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 부모 프레임 N개는 파생 프레임 N개의 **서로 다른** 파일로 산출돼야 한다. Phase A 는 이미 "라벨 이중매핑"을 막으려고 중복 `videoFrameNo` 를 fail-fast 하는데(`ResolutionSnapshotService:195-199`), 같은 급의 위험인 **목적지 경로 충돌**에도 동일한 fail-fast 가 있어야 한다. 충돌을 허용하면 파생 프레임 i 의 라벨이 프레임 j 의 픽셀 위에 얹히는 데이터 정합 붕괴가 생기고, 그 산출물이 export→관제로 나간다.
- **현재 동작(이슈 내용)**: 목적지 파일명이 부모 `deidFilePath` 의 basename 그대로다. 중복 검사 없음.
  ```java
  // ResolutionSnapshotService.java:205-208
  Path fdst = resolveSafeDir(base,
          StorageSubtreePolicy.deidFramesDir(newRawSn) + "/" + fileNameOf(deidFrameSrc, pf));
  specs.add(new ResolutionSnapshot.FrameSpec(
          pf.getSrcSn(), pf.getFrameNo(), pf.getVideoFrameNo(), pf.getShtDt(), fsrc, fdst));
  // fileNameOf(231-234): Paths.get(frameSrc).getFileName()  ← 부모 파일명 그대로, 유일성 미검증
  ```
  실측(부모 906: frm0=`…/deid/26/frame-0.jpg`, frm1=`…/deid/27/frame-0.jpg`) → 파생 98 확정 **성공**(aug `ACCEPTED`), 그런데:
  ```
  src_sn | raw_sn | frm_no | vdo_frm_no | de_idntf_src_file_path_nm
     466 |     98 |      0 |          0 | /app/storage/deidentified/frames/deid/98/frame-0.jpg
     467 |     98 |      1 |         30 | /app/storage/deidentified/frames/deid/98/frame-0.jpg   ← 동일 경로
  $ ls /app/storage/deidentified/frames/deid/98/   →   frame-0.jpg  (1개뿐)
  ```
  두 프레임 행이 한 파일을 가리키고, 나중 리사이즈가 앞 프레임을 덮어썼다. 경고 로그도 없다.
- **재현/확인 경로**:
  ```sql
  INSERT INTO ls_data_raw (raw_sn,vms_clip_id,vms_cctv_id,prvc_type_cd,prvc_yn,de_ident_yn,raw_file_path_nm,data_stts_cd)
   VALUES (906,'QA','C','ANONY','N','Y','/app/storage/raw/seed/clip-9103.mp4','COMPLETED');
  INSERT INTO ls_raw_data_status VALUES (906,'APPROVED',0,0,now(),0);
  INSERT INTO ls_data_src (raw_sn,frm_no,vdo_frm_no,src_file_path_nm,de_idntf_src_file_path_nm,sht_dt) VALUES
   (906,0,0,'/app/storage/raw/frames/raw/26/frame-0.jpg','/app/storage/deidentified/frames/deid/26/frame-0.jpg',now()),
   (906,1,30,'/app/storage/raw/frames/raw/27/frame-0.jpg','/app/storage/deidentified/frames/deid/27/frame-0.jpg',now());
  INSERT INTO ls_deident_proc_log (data_raw_sn,orgnl_file_path_nm,de_idntf_file_path_nm,proc_stts_cd)
   VALUES (906,'/x','/app/storage/deidentified/videos/resolution/26/77/RESL_720P.mp4','SUCCEEDED');
  ```
  ```bash
  curl -X POST localhost:18081/api/v1/videos/906/resolution -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"presets":["RESL_480P"]}'   # → 201, 이후 확정 성공
  ```
- **영향**: 데이터 정합(라벨↔픽셀 불일치) · export/데이터마트로 잘못된 프레임 쌍 유출. CWE-706(경로 → 리소스 잘못된 해석) / CWE-345(데이터 진정성 미검증). **실환경 도달성은 낮다** — 정상 추출은 `frames/deid/{rawSn}/frame-{n}.jpg` 로 raw 별 유일하고 `(RAW_SN, FRM_NO)` UK 가 basename 중복을 사실상 막는다. 다만 방어 없이 "관행"에만 의존하고 있고, 코드베이스 자체가 같은 급의 위험(`videoFrameNo` 중복)엔 fail-fast 를 둔 것과 비대칭이다.
- **수정 방향(제안)**: `ResolutionSnapshotService.buildFrameSpecs` 에서 ①`seenFrameKeys` 와 동일한 방식으로 `Set<Path> seenDst` 유일성 검사를 추가해 충돌 시 `INTERNAL_ERROR` fail-fast, 또는 ②더 근본적으로 목적지 파일명을 부모 basename 이 아니라 **파생 자신의 프레임 번호**(`frame-{frameNo}.{ext}`)로 결정론 생성. ②가 부모 경로 형식에 대한 의존을 없애 낫다.

### [E-ISSUE-62] TC-RESL-054 — `releaseReservedAug` 의 "승자 보호"가 **부모 라벨 0건 영상에서 무효**
- **심각도**: LOW
- **기대 동작(기대효과)**: 중복 finalize 패자의 실패 정리가 승자의 확정 결과(`LS_DATA_AUG` ACCEPTED 행)를 지우면 안 된다. 지워지면 ①증강 이력에서 파생이 사라지고 ②`UK_LS_DATA_AUG_RESL` 슬롯이 풀려 같은 (영상 × 프리셋) 재요청이 **두 번째 파생 RAW** 를 만들어 "요청 1회 = 파생영상 1건" 계약이 깨진다.
- **현재 동작(이슈 내용)**: 승자 보호 판정을 라벨맵 참조 유무 **하나에만** 의존한다.
  ```java
  // ResolutionPersistService.java:205-210
  List<LsDataAugLblMap> refs = lblMapRepository.findAllByDataAugSn(dataAugSn);
  if (!refs.isEmpty()) { log.warn("… skip release …"); return; }
  ```
  그런데 `copyScaledLabels`(386-389)는 **부모 라벨이 0건이면 매핑 행을 하나도 만들지 않는다**. 실측: 부모 26 은 라벨 0건이고 성공 파생 76/77/78 의 `ls_data_aug_lbl_map` 도 0행(`select data_aug_sn,count(*) … group by` 결과에 15/16/17 부재). 즉 라벨 없는 영상의 승자 aug 는 이 방어를 전혀 받지 못한다.
- **재현/확인 경로**: 라벨 0건 부모로 파생 확정 후 `select * from ls_data_aug_lbl_map where data_aug_sn = {승자 augSn}` → 0행. 이 상태에서 `handleFailure` 의 `isAlreadyFinalized` 가 false 로 떨어지는 잔여 창(`ResolutionPersistService` javadoc 168-175 가 명시한 "승자-뒤짐" / `lock_timeout` 페일오픈)에 진입하면 승자 aug 가 삭제된다.
- **영향**: 데이터 정합/이력 유실. 현 트리거가 단일 러너(AFTER_COMMIT 1회 등록)라 실제 재현 난도는 매우 높음 → LOW. 다만 `LS_DATA_AUG` 는 증강 이력·집계의 진실원이라 유실 시 조용하다.
- **수정 방향(제안)**: 참조 검사 축을 라벨맵에서 **`LS_DATA_AUG.NEW_RAW_SN` / `AUG_PROC_STTS_CD`** 로 옮긴다 — `augProcSttsCd != PENDING`(=이미 ACCEPTED 로 확정 전이됨)이면 삭제하지 않는 조건을 추가하면 라벨 유무와 무관하게 승자가 보호된다(V155 `new_raw_sn` 이 이미 승자 파생을 가리키므로 그것과 `newRawSn` 일치 여부도 함께 쓸 수 있다).

### [E-ISSUE-63] TC-RESL-049 — Phase C stale 게이트의 `IOException` 폴백 주석이 **이미 폐기된 신고 게이트**를 근거로 든다
- **심각도**: LOW
- **기대 동작(기대효과)**: 보수적 통과(페일오픈)를 선택한 근거는 실재하는 다른 방어를 가리켜야 한다. 없는 방어를 근거로 적어두면 다음 수정자가 "다른 게이트가 막아준다"고 오판해 실제 폐쇄를 미룬다.
- **현재 동작(이슈 내용)**:
  ```java
  // ResolutionPersistService.java:355-359
  } catch (IOException e) {
      // stat 실패는 결정적 게이트(①②)가 이미 통과했으므로 보수적으로 통과(로그만). PII 는 ②신고 게이트로 닫힘.
      log.warn("[Video][ResolutionDerivative][C] deident mtime check skipped …");
  }
  ```
  같은 메서드의 javadoc(312-314)은 **"구 조건 'capturedAt 이후 신고 이력 존재'는 제거됐다"** 고 명시한다. 즉 "②신고 게이트"는 더 이상 존재하지 않는다. 남은 게이트는 ①경로 불일치와 ②mtime 뿐이고, 이 catch 는 바로 그 ②를 스킵하는 자리다.
- **재현/확인 경로**: `ResolutionPersistService.java:306-360` 정독 — 주석 내 게이트 번호(①②)가 javadoc 의 ①② 와 의미가 어긋난다.
- **영향**: 문서 정합/유지보수 리스크(기능 영향 없음).
- **수정 방향(제안)**: 주석을 사실대로 정정 — "stat 실패 시에는 경로 게이트(①)만으로 판정하며 제자리 교체는 검출하지 못한다(정직한 한계)". 게이트 번호도 javadoc 과 통일.

### [E-ISSUE-64] TC-RESL-066 — 고아 파생 RAW DELETE 문에 `DE_IDENT_YN <> 'Y'` 조건이 빠져 있어 카탈로그의 "동일 조건 동봉"과 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: "검사~삭제 창 0건 삭제"를 위해 Java 사전검사 4조건(①파생임 ②`'Y'` 아님 ③FAILED ④프레임 0건)이 **그대로** DELETE 술어에 실려야 한다(카탈로그 TC-RESL-066 기대결과 문구).
- **현재 동작(이슈 내용)**: 4조건 중 3개만 SQL 에 있다.
  ```sql
  -- VideoRepository.deleteFailedDerivative (396-401)
  DELETE FROM LS_DATA_RAW
   WHERE RAW_SN = :rawSn
     AND ORGNL_RAW_SN IS NOT NULL
     AND DATA_STTS_CD = 'FAILED'
     AND NOT EXISTS (SELECT 1 FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn)
  -- ② DE_IDENT_YN <> 'Y' 부재
  ```
- **재현/확인 경로**: `ResolutionPersistService.java:242-254` 의 Java 검사와 `VideoRepository.java:394-402` SQL 술어 대조.
- **영향**: **실질 기능 갭은 없다** — 삭제는 같은 트랜잭션이 `findByRawSnForUpdate` 로 잡은 행 잠금 하에서 수행되고, 확정 경로(`ResolutionPersistService:138-139`)가 `markDeidentified("Y")` 와 `markCompleted()` 를 **항상 함께** 수행하므로 `DATA_STTS_CD='FAILED'` 술어가 `'Y'` 행을 이미 배제한다. 방어 다중화 관점의 불일치이자 카탈로그 문구 부정확.
- **수정 방향(제안)**: SQL 에 `AND (DE_IDENT_YN IS NULL OR DE_IDENT_YN <> 'Y')` 를 추가하거나, 카탈로그 기대결과를 "3조건 동봉 + `'Y'` 는 상태 조건으로 간접 배제"로 정정.

### [E-ISSUE-65] TC-RESL-062 — cleanup 이 파생 비디오 **파일**만 지우고 빈 디렉터리 트리를 남긴다
- **심각도**: LOW
- **기대 동작(기대효과)**: 실패한 파생의 산출물 흔적이 저장소에 누적되지 않아야 한다(경로 키에 파생 RAW_SN 이 들어가므로 재시도마다 새 디렉터리가 생긴다).
- **현재 동작(이슈 내용)**: `cleanup` 은 프레임은 디렉터리째 재귀 삭제하지만(`deleteRecursivelyQuietly(framesDir)`, 108-112) 비디오는 파일 단위 삭제만 한다.
  ```java
  // ResolutionFileMaterializer.java:141-152
  if (videoDst != null) { deleteFileQuietly(videoDst); if (Files.exists(videoDst)) clean = false; }
  ```
  실측(rawSn 97 실패 후):
  ```
  $ ls -la /app/storage/deidentified/videos/resolution/905/
  drwxr-xr-x 2 app app 4096 … 97      ← 파일은 사라졌지만 디렉터리 잔존
  ```
- **재현/확인 경로**: 위 §0 의 905 시나리오 후 `ls /app/storage/deidentified/videos/resolution/905/`.
- **영향**: 저장소 위생(파일시스템 inode 누적). 기능·보안 영향 없음.
- **수정 방향(제안)**: `deleteFileQuietly(videoDst)` 이후 부모 디렉터리(`videoDst.getParent()`)가 비어 있으면 `Files.deleteIfExists` 로 함께 제거(루트 `videos/resolution` 자기자신 보호 조건 포함). 잔존 판정(`clean`)에는 반영하지 않는다.

---

## 3. 자동테스트 커버리지 대조

| 테스트 파일 | E-5 커버 케이스 |
|------|------|
| `video/ResolutionReservationPersisterTest.java` | 030 · 031 · 032 · 033 |
| `video/ResolutionSnapshotServiceTest.java` | 035 · 036 · 037 · 038 · 039 · 040 (+ deid base 격리) |
| `video/ResolutionPersistServiceTest.java` | 044 · 048 · 049 · 050 · 051 · 052 · 053 · 054 · 055 · 056 · 066 |
| `video/ResolutionFileMaterializerTest.java` | 041 · 042 · 043 · 062 · 067 |
| `video/AsyncResolutionRunnerTest.java` | 057 · 058 · 059 · 060 · 061 · 063 · 067 |
| `video/ResolutionLetterboxTest.java` | 045 |
| `video/ResolutionReservationPathIT.java` | 069 |
| `video/ResolutionDerivativeFlowIntegrationTest.java` | 030 · 033 · 035 · 044 · 045 · 048 · 050 · 051 · 052 · 053 · 069 · 070 · 071 |
| `meta/DerivedMetaCopierTest.java` · `DerivedMetaCopierIT.java` | 070 · 071 |

- baseline(`_raw/test-baseline.md`) 기준 위 전부 **통과**(backend 실패 0건).
- **커버리지 갭(전용 테스트 없음)**: **TC-RESL-034**(예약 출력경로 CWE-22) · **TC-RESL-064**(`RESL_` 접두 강제) · **TC-RESL-065**(이중전이 409) · **TC-RESL-068**(레거시 raw base 디렉터리 정리). 네 건 모두 정적 대조로는 구현이 확인되나 회귀 가드가 없다.

---

## 4. 근거(file:line) 드리프트

| TC | 카탈로그 근거 | 실제 위치 | 비고 |
|----|------|------|------|
| TC-RESL-030 | `ResolutionReservationPersister.java:68-123` | `68-130` | 메서드 끝 확장(경미) |
| TC-RESL-034 | `ResolutionReservationPersister.java:157-163` | **`164-170`** | `resolveSafeDir` 위치 이동 |
| TC-RESL-064 | `LsDataAug.java:214,228` | **`306`(createResolutionPending) · `320-323`(접두 가드)** | 214 는 무관한 빌더 생성자 |
| TC-RESL-065 | `LsDataAug.java:248-249` | **`340-348`(가드 341-343 · 344-347)** | |

그 외 37건은 근거 라인이 실제 코드와 일치했다.

---

## 5. ★ 확증편향 반증 결과 (핵심 위험 4축)

1. **3곳 부모 게이트가 `'N'`만 막고 `'F'`는 통과하는가** → **개별 실증 완료.**
   - 예약(`ResolutionReservationPersister:83-88`) — 부모 900(`'F'`) 요청이 **201** 로 예약됨.
   - Phase A(`ResolutionSnapshotService:116-122`) — `[A] snapshot ready rawSn=88 parentRawSn=900` 로그.
   - Phase C(`ResolutionPersistService:97-103`) — `[C] persisted rawSn=88 orgnlRawSn=900` 로그.
   - `'N'`(부모 901) 은 **409**. 세 게이트 모두 `LsDataRaw.hasDeidentArtifact()`(`'Y'|'F'`) 단일 헬퍼를 호출(grep 전수: 예약/A/C + `AugmentResultService:390` + `ParentDeidArtifactGuard:70` + `DeidentReportService:330`) → 축 드리프트 없음.
2. **Phase A 산출물 실재 검증이 fail-closed 인가** → procLog 부재 `NOT_FOUND`(149-153) · deid 프레임 경로 blank/null `CONFLICT`(240-247) 확인. **후자는 실동작으로도 실증**(부모 902 → 확정 실패, 원본 프레임 폴백 흔적 0). 두 경로 어디에도 `frames/raw/**` 폴백 분기가 없다(`resolveSafeDeidSource` 가 `isDeidentifiedArtifact` 로 거부).
3. **Phase C stale 창 게이트의 판정축이 신고가 아니라 복사 원자성인가** → **맞다.** `assertDeidentNotReplacedSince`(322-360) 에 신고(`DE_IDNTF_YN`) 참조가 **없고**, 조건은 ①최신 SUCCESS procLog 경로 불일치 ②파일 mtime > capturedAt 둘뿐. ②는 미래 mtime 주입으로 **실동작 실증**(rawSn 97 abort + cleanup).
4. **예약 동시성(같은 프리셋 중복 예약 방지)** → DB 부분 유니크 인덱스가 **상태 무관** 술어(`WHERE aug_type_cd ~~ 'RESL\_%'`)로 실재하고, 응용은 `save`+`flush` 로 **새 RAW/파일 생성 이전에** 위반을 감지해 `CONFLICT` 로 변환(101-109). 실동작으로 PG 23505 → 409 변환 확인. 동시(2스레드) 경합은 IT("같은영상_같은프리셋_동시요청시_1건성공_1건CONFLICT")가 커버.

**추가 반증에서 나온 것**: 위 4축은 전부 방어가 성립했고, 실제 결함은 **방어가 아예 없는 축**에서 나왔다 — 목적지 파일명 유일성(E-ISSUE-61). Phase A 가 `videoFrameNo` 중복은 fail-fast 하면서 목적지 경로 중복은 검사하지 않는 비대칭이 그 지점이다.
