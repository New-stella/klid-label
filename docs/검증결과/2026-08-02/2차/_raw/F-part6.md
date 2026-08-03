# F-portal 검증 결과 — part6 (F-9 · F-10 · F-11)

- 담당 범위: `## F-9. 포털 영상 프레임 추출 (비동기 · 간격 · 상한 · 실패)`(14건, TC-PORTALUP-060~073) +
  `## F-10. 상태 전이 · 정리 스윕 / F-11. 내부 파이프라인 분리`(13건, TC-PORTALUP-080~087, TC-PORTAL-090~094) = **27건**
- 스택: docker compose 기존 기동 상태 유지(klid-backend :18081→8080, klid-postgres, klid-mock-server :9400) — 신규 기동 없음
- 코드/설정/테스트 파일 수정 없음, 빌드/테스트 실행 없음(기존 baseline `docs/검증결과/2026-08-01/1차/_raw/test-baseline.md`: backend 4,755 tests 4,750 성공/0 실패/5 스킵 참조)
- 검증 대상 커밋: 워킹트리 `qa-0801` HEAD (git status clean)

## 방법

1. **실동작 최우선** — HS256 JWT 자체 서명(컨테이너 실효 `JWT_SECRET`, `sub=3001,channel=PORTAL,iss=klid-portal`→PORTAL_USER, `sub=1001,channel=INTERNAL`→REVIEWER, `GET /v1/me` 로 검증)으로 실제 TUS 업로드(`backend/src/test/resources/fixtures/tiny-video.mp4`, 실측 duration=0.04s/fps=25 — 컨테이너 내 실 ffprobe) → PATCH 청크 전송 → AFTER_COMMIT 비동기 추출 → DB(`ls_portal_uld`/`ls_portal_uld_frme`) + 파일시스템(`/app/storage/raw/portal/frames/{uldSn}`) 실측. 생성한 테스트 자산(uld_sn=58)은 검증 후 `DELETE /v1/portal/uploads/58` 로 정리, DB/실측 확인 완료(원복).
2. `portal.upload.frame-interval-sec` 시스템설정 경계값[1,600] 은 REVIEWER 토큰으로 실제 `PUT /v1/manage/configs/...` 왕복(601/0 거부 400, 600 허용 200) 후 **5로 원복 확인**.
3. 정적 대조 — file:line Read, 근거 드리프트 없음(전 라인 일치 확인).
4. 테스트 커버 — `PortalFrameExtractRunnerTest`·`PortalFrameExtractTxServiceTest`·`PortalUploadSweepJobTest`·`PortalUploadSweepTxServiceTest`·`PortalUploadSweepIT`·`LsPortalUldStateTransitionTest`·`entity/LsPortalUldTest`·`PortalUserLabelServiceTest` 등 전용 테스트 존재, 전건 baseline PASS(0 실패)에 포함.
5. F-11 분리는 실측: 업로드 후 `ls_data_raw` 에 해당 파일 참조 행 0건(`SELECT count(*) ... WHERE raw_file_path_nm LIKE '%qa-f9-test%'` → 0), `PortalVideoUploadedEvent` 리스너는 `PortalFrameExtractBridge` 유일(관제 `VideoIngestedEvent` 무관 — 코드 확인), 데이터마트 View DDL 전수에 `LS_PORTAL_*` 참조 0건(grep).

## 결과 표

| ID | 판정 | 근거확인 | 비고 |
|----|:--:|:--:|------|
| TC-PORTALUP-060 | PASS | [실동작] | `PortalVideoUploadedEvent`(AFTER_COMMIT)→`PortalFrameExtractBridge`→`runner.runAsync` 확인(`PortalFrameExtractBridge.java` 전문, `@TransactionalEventListener(phase=AFTER_COMMIT)`). 실 업로드 후 짧은 시간 내 DB `uld_stts_cd=READY`+프레임 파일 생성 실측(uld_sn=58) |
| TC-PORTALUP-061 | PASS | [실동작]+[정적] | `beginProcessing`=`transitionToProcessing`(조건부 UPDATE, WHERE `uld_stts_cd='UPLOADED'`) 0행→Optional.empty→러너 즉시 return. 단위테스트 `abortsWhenAssetDeletedAtEntry` PASS(baseline) |
| TC-PORTALUP-062 | PASS | [실동작] | DB 시드값 5 확인(`SELECT stng_value FROM ls_system_config WHERE stng_key='portal.upload.frame-interval-sec'`→`5`), 실 업로드 프레임 추출도 5초 간격 스냅샷 로직(`snapshotIntervalSec` :185-193) 경유 확인. V109:101 문구 일치 |
| TC-PORTALUP-063 | PASS | [정적] | `snapshotIntervalSec()`(:185-193) catch(RuntimeException)→`DEFAULT_INTERVAL_SEC=5` 폴백 코드 확인. `SystemConfigService.getInt`가 설정 row 부재 시 `loadOrThrow`로 예외 던짐(코드 확인) — 폴백 분기는 도달 가능하나 이 분기 자체를 직접 때리는 전용 단위테스트는 없음(운영 데이터 삭제 리스크로 실동작 재현은 보류) |
| TC-PORTALUP-064 | PASS | [실동작] | `PUT /v1/manage/configs/portal.upload.frame-interval-sec` 실 왕복: `{"value":"601"}`→400, `{"value":"0"}`→400, `{"value":"600"}`→200. `ConfigKeys.java:71` `[1,600]` 범위와 일치. 검증 후 `5`로 원복 + DB 재확인 완료 |
| TC-PORTALUP-065 | PASS | [정적]+[테스트] | `computeFrameNumbers`(:147-183) 후보>cap 시 균등 재샘플링 로직 확인. 단위테스트 `frameCountCappedByUniformSampling`(duration 1000s×30fps, interval 1s, cap 10 → 결과 ≤10·단조증가) baseline PASS. 실효 `maxFrames` 기본값 2000(env `PORTAL_MAX_FRAMES` 미설정 → application.yml 기본값, 클라이언트가 요청으로 override 불가 — 서버 config 단일값) |
| TC-PORTALUP-066 | PASS | [정적] | `if (candidates.size() <= cap) return candidates;`(:161) — 정확히 cap(2000)일 때 재샘플링 없이 그대로 반환(≤2000 보장, off-by-one 없음) |
| TC-PORTALUP-067 | PASS | [실동작] | 실 업로드 tiny-video.mp4(duration 0.04s < interval 5s) → DB `frme_cnt=1`, `ls_portal_uld_frme` 1행(`frme_no=0`) 실측. `computeFrameNumbers` 최소 1프레임 보장 코드(:158-160)와 일치 |
| TC-PORTALUP-068 | PASS | [정적] | `double fps = probe.fps() > 0 ? probe.fps() : DEFAULT_FPS;`(:102), `computeFrameNumbers` 내부도 동일 폴백(:148). 실 업로드는 ffprobe가 fps=25 를 정상 반환해 폴백 미경유(DB `fps=25` 실측) — 폴백 자체는 로직 확인으로 PASS, 0/미상 fps 실촉발 사례는 재현 안 함 |
| TC-PORTALUP-069 | PASS | [실동작] | 업로드 후 DB `uld_stts_cd=READY, vdo_len_sec=0.04, fps=25, frme_cnt=1` 원자 커밋 확인(`completeReady`:126-132, `transitionToReady` 조건부 UPDATE). 프레임 파일 `/app/storage/raw/portal/frames/58/frame-0.jpg` 실재(222 bytes, 컨테이너 내 실 ffmpeg 산출물 — self-fill 아님) |
| TC-PORTALUP-070 | PASS | [테스트]+[정적] | catch(Exception) 블록(:133-138) `cleanup`+`markFailed` 호출 확인. 단위테스트 `failureMarksFailedAndCleansPartialFiles`(writeFrameByNumber IOException 강제 → `markFailed` 호출 + frames 디렉토리 미잔존) baseline PASS. 실 ffmpeg 실패 재현(손상 영상)은 시간 예산상 보류, 코드+테스트 근거로 충분 판단 |
| TC-PORTALUP-071 | PASS | [테스트]+[정적] | `touchProcessing` 하트비트(:115) — `PROGRESS_CHECK_EVERY=50` 프레임마다 조건부 UPDATE 0행이면 abort+cleanup. 단위테스트 `abortsAndCleansWhenHeartbeatFailsMidExtract`(2번째 하트비트 false→중단, 부분파일 정리, `completeReady`/`markFailed` 모두 미호출) baseline PASS |
| TC-PORTALUP-072 | PASS | [정적] | `resolveSafeFramesDir`(:195-201) `storageRoot.resolve(...).normalize()` 후 `!resolved.startsWith(storageRoot)` 시 예외. 단 **비고**: `uldSn` 파라미터가 `Long` 타입(DB PK)이라 `../` 등 문자열 조작이 애초에 불가능한 위협모델 — 가드는 실제 동작하나 "조작 uldSn으로 탈출"은 타입 안전성상 도달 불가능한 경로(방어심층, 결함 아님) |
| TC-PORTALUP-073 | PASS | [정적] | `runAsync`(:71-82) try/catch(Exception) 로 `extract()` 예외 흡수 후 `log.warn`만 수행, 재전파 없음. `extract()` 자체도 내부 catch(:133-138)로 대부분 흡수하므로 이 상위 catch 는 안전망 |
| TC-PORTALUP-080 | PASS | [정적] | `LsPortalUld`(:114-138) `markProcessing`/`markReady`/`markFailed` 3개 비즈니스 메서드만 상태 변경, `@Setter` 미부여(`@Getter`만) 확인. `LsPortalUldStateTransitionTest`·`entity/LsPortalUldTest` baseline PASS |
| TC-PORTALUP-081 | PASS | [실동작]+[정적] | `PortalUploadService.java:128` `uld.markReady(null, null, 1);` 확인. 실동작: `POST /v1/portal/uploads/images` 로 이미지 업로드 시 DB 즉시 `uld_stts_cd=READY`(기존 데이터 uld_sn=54~56 실측, 전부 즉시 READY) |
| TC-PORTALUP-082 | PASS | [정적]+[테스트] | `cleanupExpiredSessions()`(:71-77) `txService.claimExpiredSessions()`(조건부 DELETE) → 반환 경로 `TusChunkStore.deleteQuietly`. `PortalUploadSweepJobTest`(`스윕잡이_만료_세션과_임시파일_정리`) baseline PASS |
| TC-PORTALUP-083 | PASS | [정적]+[테스트] | `failStuckUploads()`(:83-89) `txService.failStuckUploads(stuckTimeoutMinutes)`(조건부 UPDATE) → `cleanupFrameDir`. 기본 30분(`application.yml` `stuck-timeout-minutes:30`, 컨테이너 env override 없음 확인). `PortalUploadSweepJobTest`(`스윕잡이_고착_자산의_부분_프레임_정리`) baseline PASS |
| TC-PORTALUP-084 | PASS | [정적] | `cleanupFrameDir`(:92-114) `!framesDir.startsWith(storageRoot)` 가드 후 skip+WARN. TC-PORTALUP-072와 동일하게 `uldSn:Long` 이라 실 우회 불가(방어심층) |
| TC-PORTALUP-085 | PASS | [정적] | 생성자(:39-44) `PortalUploadSweepTxService` 주입, `cleanupExpiredSessions`(:71-72)·`failStuckUploads`(:83-84) 모두 `txService.*` 위임 — 잡 클래스 자체엔 `@Transactional` 없음(self-invocation 프록시 우회 방지, 클래스 Javadoc:27-28 명시) |
| TC-PORTALUP-086 (신규) | PASS | [정적]+[테스트] | `LsPortalTusUploadRepository.deleteExpiredInProgress`(:66-68) `DELETE ... WHERE uld_id=:uldId AND stts_cd='IN_PROGRESS'` 조건부 삭제 — 두 노드 동시 실행 시 한쪽만 1행. 단위테스트 `PortalUploadSweepTxServiceTest.returnsFilePathsForClaimedSessionsOnly`(claimed=1행/lost=0행 시뮬레이션) baseline PASS |
| TC-PORTALUP-087 (신규) | PASS | [정적]+[테스트] | `LsPortalUldRepository.failIfInStatus`(:76-82) `WHERE uld_sn=:uldSn AND uld_stts_cd IN :statuses` 조건부 UPDATE. 단위테스트 `returnsUldSnForFailedTransitionsOnly`(42=1행/7=0행 시뮬레이션) baseline PASS |
| TC-PORTAL-090 | PASS | [정적] | `PortalUploadLabelService`(:78-82) 생성자 필드 = `uldRepository`/`frmeRepository`/`lblRepository`(포털 3종)+`properties`+`objectMapper` 뿐. 내부 도메인 Repository/Service 주입 0건(단, 정적 유틸 `FrameImageService.openNoFollow` 호출 1건 — 안전 open 헬퍼 정적 호출이며 빈 주입·데이터 접근 아님, 위반 아님) |
| TC-PORTAL-091 | PASS | [실동작]+[정적] | `PortalVideoUploadService.java:28-31` Javadoc "관제 비식별 파이프라인 미연결" + `PortalVideoUploadTxService`(:154) `eventPublisher.publishEvent(new PortalVideoUploadedEvent(...))` 만 발행(`VideoIngestedEvent` 미발행, grep 0건). 실측: 업로드 후 `ls_data_raw` 에 참조 행 0건(`SELECT count(*) FROM ls_data_raw WHERE raw_file_path_nm LIKE '%qa-f9-test%'` → 0), `max(raw_sn)` 불변(906) |
| TC-PORTAL-092 | PASS | [정적]+[테스트] | `PortalLabelService.java:196-223` `saveUserLabel` → `userLabelRepository.save(LsPortalUserLabel.create(...))` — `LS_DATA_LBL` 미접촉. `PortalUserLabelServiceTest.V2_사용자_작업_데이터_별도_적재_원본_미수정` baseline PASS |
| TC-PORTAL-093 | PASS | [정적] | `PortalFrameExtractRunner.java:25-28` Javadoc "portalExtractExecutor 별도 스레드" + `@Async("portalExtractExecutor")`(:71). `AsyncConfig.java:210-220` 별도 `ThreadPoolTaskExecutor`(core=1,max=2,queue=20, `CallerRunsPolicy`) 빈 확인 — 관제 `batchAsyncExecutor` 와 물리적으로 분리된 빈 |
| TC-PORTAL-094 | PASS | [정적] | `PortalUploadLabelService.java:44-46` Javadoc "AC6: 데이터마트/내부 도메인 미참조". 실측: `db/migration/*.sql` 전수 grep 결과 `V_COMPLETED_*` 등 View DDL 어디에도 `LS_PORTAL_*` 테이블 참조 0건(portal 문자열이 등장하는 파일 2건은 코멘트/다른 마이그레이션 설명일 뿐 View 정의 아님) |

## 집계

| 판정 | 건수 |
|------|:--:|
| PASS | 27 |
| FAIL | 0 |
| PARTIAL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **27** |

## 이슈

없음 (F-ISSUE-101~120 범위 미사용 — 결함/부분충족/미확정 발견 0건).

## 근거 드리프트

없음 — F-9/F-10/F-11 전 케이스의 `file:line` 근거가 실제 코드와 라인 단위로 일치 확인됨(2026-07-30 최신화 표기 그대로 유효).

## 참고 — 실동작 검증에 사용한 원자료

- JWT: PORTAL_USER(`sub=3001,channel=PORTAL,iss=klid-portal`), REVIEWER(`sub=1001,channel=INTERNAL,iss=klid-auth`) — 컨테이너 실효 `JWT_SECRET`(HS256) 직접 서명, `GET /v1/me` 로 역할 확인
- 업로드 자산: `backend/src/test/resources/fixtures/tiny-video.mp4`(1546 bytes) — TUS `POST`(세션생성 201)→`PATCH`(청크 204, offset=1546=length) 완료 확인
- 생성/삭제된 테스트 행: `ls_portal_uld.uld_sn=58`(READY, 검증 후 `DELETE /v1/portal/uploads/58` 로 제거 완료, DB 0건 재확인) — 프로덕션 코드/설정/테스트 파일 변경 없음, 업로드 자산 데이터만 생성 후 원복
- 시스템설정 `portal.upload.frame-interval-sec`: 601/0/600 순 왕복 후 **5로 원복**(DB 재확인 완료)
