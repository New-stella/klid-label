# F 클러스터 part3 (F-8~F-11) 2차 검증 결과

> 대상: `docs/test-cases/F-portal.md` §F-8(32) · §F-9(14) · §F-10·F-11(13) = **59건**
> 환경: 로컬 도커 스택 · backend `localhost:18081`(이미지 HEAD `ca3c712b` 대비 backend 커밋 11개 뒤처짐, `stack-bringup.md` §2) · PG `klid_system`/`public`
> 방식: **PORTAL_USER 토큰으로 실제 TUS 세션 생성→청크 전송→중단→재개→완료** 를 태우고 `LS_PORTAL_TUS_ULD`·`LS_PORTAL_ULD`·`LS_PORTAL_ULD_FRME` DB 행 + 컨테이너 실파일을 근거로 판정. 프레임 추출은 실제 ffmpeg 로 구동.
> 검증 일시: 2026-07-31 04:15~04:40 KST · 폐기(`~~취소선~~`) 행 **0건**(이 3개 섹션에는 없음)

## 집계

| 구분 | 건수 | PASS | PARTIAL | FAIL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| F-8 TUS 업로드 | 32 | 32 | 0 | 0 | 0 | 0 | 0 |
| F-9 프레임 추출 | 14 | 14 | 0 | 0 | 0 | 0 | 0 |
| F-10·F-11 상태전이·스윕·분리 | 13 | 11 | 2 | 0 | 0 | 0 | 0 |
| **합계** | **59** | **57** | **2** | **0** | **0** | **0** | **0** |

- 실동작 근거로 판정한 케이스 **44건**, 정적 대조 **15건**(순수함수·프록시 경계·2노드 등 로컬 단일노드에서 구동 불가한 항목).
- self-fill 의심 **0건**. 프레임 간격은 sysconfig 값을 **5→10→5 로 바꿔가며 결과 프레임 수가 3→2→(복원) 로 실제 변하는 것**을 확인해 하드코딩이 아님을 반증했다.
- 신규 이슈 **7건** — HIGH 1 · MEDIUM 4 · LOW 2.

## ★TUS 프로토콜 악용 실측 (공격 / 기대 / 실측 / 파일 상태)

세션 `07246fc8-…`(valid.mp4 30,817B) 및 파생 세션들로 실측. 전 항목 curl/raw socket 실행.

| # | 공격 | 기대 | 실측 | 디스크/DB 상태 |
|---|------|------|------|------|
| 1 | `Upload-Length: 5368709121`(5GB+1) | 413 | **413** `PAYLOAD_TOO_LARGE` | 세션 미생성 |
| 2 | `Upload-Length: 0` / `-1` | 400 | **400** ×2 | 세션 미생성 |
| 3 | `Upload-Length` 헤더 누락 | 400 | **400** | — |
| 4 | `Upload-Length: notanumber` | 400 | **400** `type mismatch param=Upload-Length` | — |
| 5 | 확장자 `.exe` / 메타 없음(확장자 미상) | 400 | **400** ×2 (`허용: [mp4, mov, avi]`) | 세션 미생성 |
| 6 | 파일명 `../../../etc/passwd.mp4` | UUID 강제 | **저장경로 `/app/storage/raw/portal/tus-video/07246fc8-….mp4`** | root 밖 파일 0건 |
| 7 | **파일명에 널바이트**(`a\0b.mp4`) | 400 | **500** ⛔ | **0바이트 고아 파일 잔존 + DB 행 0** → `F-ISSUE-41` |
| 8 | 타 사용자(3002) 세션 HEAD / PATCH / DELETE | 403 | **403 / 403 / 403** | 무변화 |
| 9 | 존재하지 않는 UUID HEAD / 비정형 UUID | 404 / 400 | **404 / 400** | — |
| 10 | 만료(`EXPRY_DT` 과거) 세션 HEAD / PATCH | 410 | **410 / 410** | 무변화 |
| 11 | `Upload-Offset` 헤더 누락 | 400 | **400** | — |
| 12 | `Upload-Offset: abc` | 400 | **400** `type mismatch` | — |
| 13 | `Upload-Offset: -5` / `999999`(length 초과) | 400 | **400 / 400** | offset 미전진 |
| 14 | `Upload-Offset: 5`(서버 0) 불일치 | 409 | **409** `CONFLICT` | offset 0 유지 |
| 15 | offset 20000 + 20000B(잔여 10,817B 초과) | 400 | **400** | 파일 20,000B 유지 |
| 16 | 17MB 단일 청크(상한 16,777,216) | 413 | **413** | 파일 0B 유지 |
| 17 | **동시 PATCH ×3 (전부 offset 0, 20,000B)** | 1건만 성공 | **204 ×1 / 409 ×2** | **파일 정확히 20,000B**(겹침·손상 0) |
| 18 | **마지막 청크 동시 ×3(완료 경합)** | ULD 1행·이벤트 1회 | **204 ×1 / 409 ×2** | `ls_portal_uld` **121→122**(1행만), `PortalFrameExtractBridge` 로그 1회 |
| 19 | **완료 검증 중 DELETE 경합**(race2) | 409 + ULD 보상 삭제 | **409** | **`uld_sn=130` 결번** — 생성 후 보상 삭제 확인 |
| 20 | 취소된 세션 PATCH | 409 | **409** | 파일 이미 삭제됨 |
| 21 | 완료 세션에 마지막 청크 재전송 | 멱등 | **204 + `Upload-Offset: 30817`** | **md5 동일**(`4e16816a…`), ULD 중복 0 |
| 22 | 완료 세션 DELETE | no-op | **204** | 영구 영상·프레임 3장 보존 |
| 23 | **`Content-Length` 를 실제보다 크게 선언**(실 20,000 / 선언 25,000) | 미완료 | 서버 대기 후 타임아웃 | **offset 0, 파일 0B**(truncate 복원) |
| 24 | **`Content-Length` 를 실제보다 작게 선언**(선언 100 / 전송 20,000) | 100B 만 기록 | **204 + `Upload-Offset: 100`** | **파일 정확히 100B**(초과분 미기록) |
| 25 | `Transfer-Encoding: chunked`(Content-Length 없음) | 400 | **400** | offset 무변화 |
| 26 | **위조 컨테이너**(텍스트를 .mp4 로) | 400+CANCELLED+삭제 | **400** `유효한 영상 컨테이너가 아닙니다` | `CANCELLED`, 파일 삭제, ULD 미생성 |
| 27 | 손상 mp4(헤더만 + garbage) | 400+CANCELLED | **400** `영상을 확인할 수 없습니다` | `CANCELLED`, 파일 삭제 |
| 28 | 오디오 전용 mp4 | 400+CANCELLED | **400** `비디오 스트림이 없는 파일입니다` | `CANCELLED`, 파일 삭제 |
| 29 | `Tus-Resumable: 0.2.2` | 412 | **412** | — |
| 30 | **`Tus-Resumable` 헤더 자체 누락** | (TUS 1.0 권고 412) | **통과(정상 처리)** | → `F-ISSUE-47` (LOW) |
| 31 | `Upload-Metadata` 1KB 초과 / 깨진 base64 | 413 / 400 | **413 / 400** | — |
| 32 | **PATCH `Content-Type: application/json`** | 415 | **500** ⛔ `INTERNAL_ERROR` | → `F-ISSUE-44` |
| 33 | 내부 토큰(WORKER)으로 POST | 403 | **403** | — |
| 34 | 무토큰 POST | 401 | **401** | — |
| 35 | 세션 생성 연타(동시 IN_PROGRESS 3 초과) | 429 | **429 ×6 연속** | 4번째부터 전부 429 |

**핵심**: 선언 크기 ≠ 실제 바이트 조합(23·24), 청크 겹침(17), 완료 경합(18·19) 어느 경로로도 **파일이 오염되거나 ULD 가 중복 생성되지 않았다.** 완료 검증(매직바이트+ffprobe)은 세 종류 위조 파일을 모두 거부했다. 결함은 **널바이트 파일명(7)** 과 **415 → 500(32)** 두 건.

## ★내부 파이프라인 분리 실측 (포털자산이 `LS_DATA_RAW`·`V_COMPLETED_*` 에 존재하는가)

포털 업로드 실행 후(영상 6건 신규 적재, `uld_sn` 124~132) 직접 SQL 로 확인.

| 검사 | SQL | 결과 | 판정 |
|---|---|---:|:--:|
| 포털 자산 총량 | `select count(*) from ls_portal_uld` | 122 | — |
| 포털 경로가 `LS_DATA_RAW` 에 샜는가 | `raw_file_path_nm like '%/portal/%'` | **0** | 분리 OK |
| 포털 경로가 `LS_DATA_SRC` 에 샜는가 | `src_file_path_nm` / `de_idntf_src_file_path_nm` `like '%/portal/%'` | **0** | 분리 OK |
| `V_COMPLETED_VIDEO` 노출 | `original_video_path`/`export_path_nm` `like '%/portal/%'` | **0** | 분리 OK |
| `V_COMPLETED_FRAME` 노출 | `original_path`/`deidentified_path` `like '%/portal/%'` | **0** | 분리 OK |
| 뷰 정의가 `LS_PORTAL_*` 를 참조하는가 | `pg_views.definition ilike '%ls_portal%'` | **0** | 분리 OK |
| 비식별 로그 증가 | `ls_deident_proc_log` count | 영상 6건 업로드 전후 **51 → 51** | 비식별 미적용 OK |
| 포털 사용자 라벨 저장이 내부 라벨을 건드리는가 | `POST /v1/portal/user-labels` 201 후 `ls_data_lbl` count | **655 → 655**, `ls_portal_user_label` 0 → 1 | 단방향 OK |
| 코드 의존 방향 (portal → 내부 파이프라인) | 업로드 경로 8개 파일의 `import kr.co.cudo.authoring.*` | 파이프라인 엔티티/리포 **0건** (공유 유틸 `TusChunkStore`·`VideoMagicByteValidator`·`FfmpegFrameExtractor.FrameWriter`·`SystemConfigService`·`FrameImageService` 만) | OK |
| 코드 의존 방향 (내부 → portal) | `/portal/` 밖에서 `LsPortalUld*` 참조 | **0건** | OK |
| 역방향 누수(내부 영상이 포털 목록에) | `GET /v1/portal/uploads` totalElements=66, 전부 `LS_PORTAL_ULD` 행 | 내부 `rawSn` 0건 | OK |
| 실행 자원 격리 | 추출 스레드명 | `portal-extract-1` (`AsyncConfig:210` core1/max2/queue20/CallerRuns) | OK |

> ⚠ 저장 루트는 **공유**다 — 포털 자산도 `STORAGE_RAW_PATH`(`/app/storage/raw`) 하위이나 `**/portal/**` 서브트리로 분리돼 내부 `frames/raw|deid/{rawSn}` 와 경로 충돌이 없다(내부는 `frames/{raw|deid}/…`, 포털은 `portal/frames/{uldSn}`). 실측상 교차 참조 0건.
> ⚠ 포털 SAM2·데이터마트 Load 서비스(`PortalSam2Service`·`PortalLabelService`)는 내부 엔티티를 참조하지만 이는 **F-2/F-4 의 데이터마트 Load 경로**이며 본 파트(업로드 자산 경로)와는 다른 축이다 — TC-PORTAL-090/091 의 판정 대상 아님.

## F-8 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-TUS-001 | OPTIONS: TUS 능력 광고(max 5GB) | PASS | [실동작] 204 + `Tus-Max-Size: 5368709120` · `Tus-Version/Tus-Resumable: 1.0.0` · `Tus-Extension: creation,termination`. `PortalTusUploadController.java:66-76` 일치 | 확장 목록에 `expiration` 미광고(24h TTL 은 있으나 미노출) — 정보성 |
| TC-TUS-002 | POST 세션 생성 → 201+Location | PASS | [실동작] 201 + `Location: /v1/portal/uploads/tus/07246fc8-3efc-4fac-a284-bbc47d8c59a9`, DB 행 `IN_PROGRESS`/`uld_offset=0`/`expry_dt=+24h` | |
| TC-TUS-003 | Upload-Length 누락 → 400 | PASS | [실동작] 400 `INVALID_INPUT "Upload-Length 헤더가 필요합니다."` (`:88-90`) | |
| TC-TUS-004 | Upload-Length 5GB 초과 → 413 | PASS | [실동작] 5368709121 → 413 `PAYLOAD_TOO_LARGE`, 한도 5368709120 메시지 (`PortalVideoUploadService.java:87-90`) | 5GB 실업로드 없이 헤더로 검증(지시 준수) |
| TC-TUS-005 | 확장자 allowlist 밖 → 400 | PASS | [실동작] `.exe` → 400, 메시지 `허용: [mp4, mov, avi]`. 메타 미첨부(확장자 미상)도 400 | |
| TC-TUS-006 | 동시 진행 세션 상한(3) 초과 → 429 | PASS | [실동작] 3건 생성 후 4번째 429 `TOO_MANY_REQUESTS`, 연타 6회 전부 429 | 만료 세션도 슬롯 점유 → `F-ISSUE-43` |
| TC-TUS-007 | 저장 파일명 UUID 강제(경로순회 차단) | PASS | [실동작] 메타 `../../../etc/passwd.mp4` → `file_path_nm=/app/storage/raw/portal/tus-video/{uuid}.mp4`, `orgnl_file_nm` 만 원문 보존 | |
| TC-TUS-008 | Upload-Metadata 1KB 초과 → 413 | PASS | [실동작] 1,409자 메타 → 413 (`:182-184`) | |
| TC-TUS-009 | Upload-Metadata base64 디코딩 실패 → 400 | PASS | [실동작] `!!!not-base64!!!` → 400 (`:194-200`) | |
| TC-TUS-010 | 지원 안 되는 Tus-Resumable → 412 | PASS | [실동작] `0.2.2` → 412 `PRECONDITION_FAILED` (`:163-168`) | 헤더 **누락** 시는 통과(관대) — `F-ISSUE-47` |
| TC-TUS-011 | HEAD offset 조회(재개) | PASS | [실동작] 204 + `Upload-Offset: 20000` / `Upload-Length: 30817` / `Cache-Control: no-store` | |
| TC-TUS-012 | 타 사용자 세션 HEAD/PATCH/DELETE → 403(IDOR) | PASS | [실동작] userNo 3002 토큰으로 3건 전부 403 `본인의 업로드 세션이 아닙니다.` | 미존재 UUID 는 404 로 갈려 존재 오라클 성립(경미, TC 기대와 일치하므로 이슈 미제기) |
| TC-TUS-013 | 만료(24h) 세션 HEAD → 410 | PASS | [실동작] `expry_dt` 를 과거로 조정 후 HEAD 410 / PATCH 410 `GONE` | DELETE 는 만료여도 204(취소 가능) — 합리적 |
| TC-TUS-014 | PATCH 청크 append offset 전진 | PASS | [실동작] 0→20000(204) → 중단 → HEAD 20000 → 20000→30817(204). **완성 파일 md5 가 원본과 동일**(`4e16816a39d8b215dbdccf22d1884e36`) | 재개 무결성 실증 |
| TC-TUS-015 | PATCH Upload-Offset 누락 → 400 | PASS | [실동작] 400 (`:130-132`) | |
| TC-TUS-016 | offset 불일치(재개 무결성) → 409 | PASS | [실동작] 서버 0 / 요청 5 → 409 (`PortalVideoUploadTxService.java:98-100`) | |
| TC-TUS-017 | offset 범위 밖(음수/length 초과) → 400 | PASS | [실동작] `-5`, `999999` 둘 다 400 (`:95-97`) | |
| TC-TUS-018 | 청크 크기 상한(16MB) 초과 → 413 | PASS | [실동작] 17,825,792B → 413, 한도 16777216 메시지 (`:91-94`) | |
| TC-TUS-019 | 청크 길이가 잔여 용량 초과 → 400 | PASS | [실동작] offset 20000 + 20000B(총 30817) → 400 (`:101-103`) | |
| TC-TUS-020 | 동시 PATCH 낙관적 락 충돌 → 409+truncate 복원 | PASS | [실동작] 3병렬 → 204×1 / 409×2, 파일 **정확히 20,000B**(겹침 0), 서버 offset 20000 | ⚠ 실경로는 **비관 락(`findByUldIdForUpdate`) 직렬화 후 offset 불일치 409** — 낙관락 분기(`:109-115`)는 미도달(근거 드리프트 참조) |
| TC-TUS-021 | 취소된 세션 PATCH → 409 | PASS | [실동작] DELETE 후 PATCH → 409 `취소된 업로드 세션입니다.` (`:81-83`) | |
| TC-TUS-022 | 완료 세션 마지막 청크 재전송 → 멱등 | PASS | [실동작] 204 + `Upload-Offset: 30817`, 파일 md5 불변, ULD 중복 0 (`:84-87`) | HTTP 응답에 `uldSn` 미노출(최초 완료 응답도 동일) — 계약상 클라이언트는 TUS 응답으로 자산 ID 를 알 수 없음(정보성) |
| TC-TUS-023 | 완료 검증: 매직바이트 불일치 → 거부(CANCELLED)+400 | PASS | [실동작] 텍스트 83B `.mp4` → 400, DB `CANCELLED`, 임시파일 삭제, ULD 미생성 (`PortalVideoUploadService.java:162-165`) | |
| TC-TUS-024 | 완료 검증: ffprobe 실패 → 400+CANCELLED | PASS | [실동작] 헤더 200B+garbage → 400 `영상을 확인할 수 없습니다`, `CANCELLED` (`:166-175`) | |
| TC-TUS-025 | 완료 검증: 비디오 스트림 없음 → 400 | PASS | [실동작] AAC 전용 mp4 → 400 `비디오 스트림이 없는 파일입니다(오디오 전용 등).` (`:176-179`) | |
| TC-TUS-026 | 완료 검증은 락/트랜잭션 밖(커넥션 점유 방지) | PASS | [정적] `appendChunk`(`:146-186`)에 `@Transactional` 없음 — 짧은 tx(`appendChunkTx`) 종료 후 매직바이트+ffprobe(최대 30s) 수행, 이후 별 빈 `finalizeCompleted`/`finalizeRejected` 재진입. self-invocation 없음 | |
| TC-TUS-027 | 완료 원자 전이(멱등): affectedRows==1만 이벤트 | PASS | [실동작] 마지막 청크 3병렬 → `ls_portal_uld` 121→**122**(1행), 완료 로그 1회, `PortalFrameExtractBridge` 1회 (`PortalVideoUploadTxService.java:132-157`) | |
| TC-TUS-028 | 검증 중 취소된 세션 완료 시도 → 409+ULD 보상 삭제 | PASS | [실동작] 최종 PATCH 와 DELETE 경합(4회 시도 중 1회 적중) → **409**, `uld_sn=130 결번`(INSERT 후 `uldRepository.delete` 보상) (`:141-152`) | |
| TC-TUS-029 | DELETE 세션 취소+임시파일 삭제 | PASS | [실동작] 204 → 컨테이너에서 해당 `.mp4` 소멸, DB `CANCELLED` (`PortalVideoUploadService.java:190-206`) | |
| TC-TUS-030 | 완료 세션 DELETE → no-op(영구 파일 삭제 금지) | PASS | [실동작] 204 반환하되 영구 영상 30,817B·프레임 3장 그대로 (`:198-201`) | |
| TC-TUS-031 | cancel도 행 잠금(PATCH와 직렬화) | PASS | [정적+실동작] `cancel` 이 `findByUldIdForUpdate`(PESSIMISTIC_WRITE) 사용(`:192-194`). race2 에서 cancel↔완료가 직렬화돼 409 로 귀결 | |
| TC-TUS-032 | TUS 저장 경로 Path Traversal 차단 | PASS | [실동작+정적] `resolveSafe`(`:210-218`) + 저장명 UUID 강제 이중. 경로형·유니코드 전각 파일명 모두 storage root 하위 UUID 로 귀결 | |

## F-9 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-PORTALUP-060 | 영상 완료 후 AFTER_COMMIT → 비동기 추출 | PASS | [실동작] 로그: `[PortalTus] completed uldSn=124` → `[PortalFrameExtractBridge] … triggering frame extract` → `[portal-extract-1] ready uldSn=124 frames=3`. `PortalFrameExtractBridge` 는 `@TransactionalEventListener(AFTER_COMMIT)` | |
| TC-PORTALUP-061 | UPLOADED→PROCESSING 원자 전이 실패 → 중단 | PASS | [정적] `PortalFrameExtractRunner:86-91` → `beginProcessing` 이 `transitionToProcessing`(조건부 UPDATE `WHERE uldSttsCd='UPLOADED'`) 0행이면 `Optional.empty` → `skip` 로그 후 return. 테스트 `PortalFrameExtractRunnerTest#러너_진입시_PROCESSING_전이_및_삭제된_자산이면_중단` | |
| TC-PORTALUP-062 | 프레임 간격 sysconfig 스냅샷(기본 5초) | PASS | [실동작] DB `ls_system_config` = **5**. 12.0s/10fps 영상 → **3프레임(frame_no 0·1·2, 원본 프레임 0/50/100)**. **반증 대조**: 설정을 10 으로 바꾸고 동일 영상 재업로드 → **2프레임**, 다시 5 로 복원 → 24프레임/120s. 하드코딩 아님을 실증 | UNCERTAINTIES #5 확정(5초) 재확인 |
| TC-PORTALUP-063 | sysconfig 미설정/오류 시 5초 폴백 | PASS | [정적] `snapshotIntervalSec()`(`:185-193`) — null·≤0·RuntimeException 전부 `DEFAULT_INTERVAL_SEC=5`(`:47`) | |
| TC-PORTALUP-064 | 프레임 간격 config 경계 [1,600] | PASS | [실동작] `PUT /v1/manage/configs/portal.upload.frame-interval-sec` 에 `0`/`601`/`700` → 전부 **400** `값이 허용 범위를 벗어났습니다`. `ConfigKeys.java:71` = `{1, 600}` | |
| TC-PORTALUP-065 | maxFrames 2000 초과 → 균등 샘플링 | PASS | [정적] `computeFrameNumbers`(`:161-178`) — `candidates.size() > cap` 이면 `stride=totalFrames/cap` 로 `cap` 개 단조증가 재샘플링, `frameNo>=totalFrames` 시 break → 결과 ≤ 2000. 테스트 `프레임_수_상한_초과시_균등_샘플링으로_상한_이내` | 상한 적용 **전** 후보 리스트 전량 생성 → `F-ISSUE-45` |
| TC-PORTALUP-066 | maxFrames 경계: 정확히 2000 | PASS | [정적] `candidates.size() <= cap` 분기(`:161-163`)로 2000 은 그대로 반환(재샘플링 미진입) | |
| TC-PORTALUP-067 | 영상 길이<간격 → 최소 1프레임 보장 | PASS | [실동작] 0.5s/10fps 영상 업로드 → `uld_sn=126`, `frme_cnt=1`, `ls_portal_uld_frme` 1행(frme_no=0) (`:151-160`) | |
| TC-PORTALUP-068 | fps 미상 시 30 폴백 | PASS | [정적] `probe.fps() > 0 ? probe.fps() : DEFAULT_FPS`(`:102`) + `computeFrameNumbers` 내 `effFps`(`:148`). `PortalVideoProbeFfprobe.fpsOf` 는 파싱 불가 시 0 반환 | 실측 영상 전건 fps 정상(10.0)이라 폴백 미발화 — 정적 판정 |
| TC-PORTALUP-069 | 추출 성공 후 원자 커밋 READY+메타 | PASS | [실동작] `uld_sn=124/125/127/131/132` 전부 `READY` + `vdo_len_sec`·`fps`·`frme_cnt` 동시 확정. `transitionToReady` 는 `WHERE uldSttsCd='PROCESSING'` 조건부 | |
| TC-PORTALUP-070 | ffmpeg/probe 실패 → 부분 파일 정리+FAILED | PASS | [실동작] `frames/128` 을 **일반 파일로 선점**해 `Files.createDirectories` 실패 유도 → `uld_stts_cd=FAILED`, `fail_rsn_cn='프레임 추출 실패: FileAlreadyExistsException'`, `ls_portal_uld_frme` **0행**, PROCESSING 고착 없음 (`:133-138`) | **B-ISSUE-21/C-ISSUE-03 류 영구 고착 재현 안 됨** — 실패가 FAILED 로 정상 마감 |
| TC-PORTALUP-071 | 진행 중 자산 삭제 감지 → 중단+정리 | PASS | [실동작] 120s 영상 추출 중(98프레임 기록 시점) 상태를 FAILED 로 전이(스윕 모사) → `[PortalFrame] aborted — asset gone mid-extract uldSn=129`, **부분 파일 98장 + 디렉터리 삭제**, `ls_portal_uld_frme` 0행 (`:112-119`) | 부활 차단(`transitionToReady` 0행)도 함께 성립 |
| TC-PORTALUP-072 | 프레임 출력 경로 Path Traversal 차단 | PASS | [정적] `resolveSafeFramesDir`(`:195-201`) — 입력이 `Long uldSn` 이라 문자열 주입 자체가 불가, 추가로 `startsWith(storageRoot)` 가드 | 심층방어. 실측 경로 전건 `…/portal/frames/{uldSn}` |
| TC-PORTALUP-073 | @Async 예외 전파 금지(runner 흡수) | PASS | [정적] `runAsync`(`:71-82`) `catch (Exception)` → WARN 로그만. `markFailed` 는 `extract` 내부에서 선행 | ⚠ `OutOfMemoryError` 등 `Error` 는 미포착(`F-ISSUE-45` 참조) |

## F-10 / F-11 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-PORTALUP-080 | LsPortalUld 상태 전이 비즈니스 메서드만 | PASS | [정적] `LsPortalUld.java:114-138` — `markProcessing()`/`markReady(vdoLen,fps,frmeCnt)`/`markFailed(reason)` 만 존재, `@Setter` 없음. 테스트 `LsPortalUldStateTransitionTest` 2건 | |
| TC-PORTALUP-081 | 이미지 업로드는 즉시 READY | PASS | [정적] `PortalUploadService.java:129` `uld.markReady(null, null, 1)` + `LsPortalUldFrme.create(uldSn, IMAGE_FRAME_NO, …)` | 근거 라인 **128 → 129** 드리프트 |
| TC-PORTALUP-082 | 만료 TUS 세션 스윕: 임시파일+행 제거 | PARTIAL | [정적] `PortalUploadSweepJob:71-77` + `PortalUploadSweepTxService:45-56` 로직·테스트(`PortalUploadSweepJobTest`·`PortalUploadSweepIT`) 정상. **[실동작] 만료 세션 픽스처를 만들고 30분+ 대기했으나 스윕 미발화**(`docker logs` 에 `[PortalSweep]` 0건) — local 프로파일에서 `@EnableScheduling` 자체가 등록되지 않음 | `F-ISSUE-42` |
| TC-PORTALUP-083 | 고착 자산(30m 정지) → FAILED+프레임 정리 | PARTIAL | [정적] `PortalUploadSweepJob:83-89`·`cleanupFrameDir:92-97` 로직 정상. **[실동작] `mdfcn_dt` 2시간 과거 + PROCESSING 픽스처를 두고 대기했으나 미발화**(동일 원인) | `F-ISSUE-42` |
| TC-PORTALUP-084 | 스윕 프레임 디렉토리 정리 root 가드 | PASS | [정적] `PortalUploadSweepJob:92-97` — `storageRoot.resolve("frames").resolve(uldSn).normalize()` + `startsWith` 미충족 시 WARN 후 skip | |
| TC-PORTALUP-085 | 스윕 트랜잭션 경계 별 위임(프록시 우회 방지) | PASS | [정적] 잡(`:39`)이 `PortalUploadSweepTxService` 주입, 벌크 UPDATE/DELETE 는 전부 별 빈의 `@Transactional` 메서드(`:71-72`, `:83-84`). 파일 I/O 만 잡에서 tx 밖 수행 | 프로젝트 반복결함(self-invocation tx 유실) 회피 패턴 준수 |
| TC-PORTALUP-086 (신규) | 만료 TUS 세션 정리는 조건부 DELETE로 2노드 중복 실행 안전 | PASS | [실동작(SQL 모사)] 리포지토리와 동일한 `DELETE … WHERE uld_id=? AND stts_cd='IN_PROGRESS'` 를 연속 2회 실행 → **DELETE 1 / DELETE 0**(예외 없음). `LsPortalTusUploadRepository:57-68`, 파일 정리도 1행 반환 노드만 수행(`PortalUploadSweepTxService:49-54`) | 2노드 동시 기동은 단일 노드 로컬이라 모사 |
| TC-PORTALUP-087 (신규) | 고착 자산 FAILED 전이도 조건부 UPDATE로 2노드 중복 방지 | PASS | [실동작(SQL 모사)] `UPDATE … SET FAILED WHERE uld_sn=? AND uld_stts_cd IN ('UPLOADED','PROCESSING')` 연속 2회 → **UPDATE 1 / UPDATE 0**. `PortalUploadSweepTxService:67-84` | 검증 후 `uld_sn=126` 을 READY 로 원복 |
| TC-PORTAL-090 | 포털 업로드는 LS_PORTAL_* 전용, 내부 미참조 | PASS | [실동작+정적] 업로드 경로 8개 파일의 import 에 batch/video/label 엔티티·리포 **0건**(공유 유틸만). `PortalUploadLabelService:45,79-81` 은 포털 3종 리포지토리만 보유. SQL 상 포털 경로가 `LS_DATA_RAW`/`LS_DATA_SRC` 에 0건 | |
| TC-PORTAL-091 | 포털 업로드 영상 비식별 미적용 | PASS | [실동작] 영상 6건 업로드 전후 `ls_deident_proc_log` **51 → 51**(무증가), `DeidentifyClient`/`VideoIngestedEvent` 참조 0건. 완료 이벤트는 포털 전용 `PortalVideoUploadedEvent` 만(`PortalVideoUploadService.java:27-31`) | 본인 데이터 정책대로 원본 그대로 서빙 |
| TC-PORTAL-092 | 포털 사용자 라벨 저장이 LS_DATA_LBL 불변 | PASS | [실동작] `POST /v1/portal/user-labels`(rawSn 20012, BBOX) → 201, `ls_data_lbl` **655 → 655**, `ls_portal_user_label` 0 → 1 (`PortalLabelService.java:196-223`) | 단방향(데이터마트 미반영) 확인 |
| TC-PORTAL-093 | 포털 프레임 추출 풀 관제 배치와 격리 | PASS | [실동작] 추출 스레드명 `portal-extract-1`(관제 `batchAsyncExecutor` 아님). `AsyncConfig.java:210-222` core=1/max=2/queue=20/CallerRunsPolicy | 병렬 업로드 2건이 순차 처리되는 것도 관측(race3 PROCESSING / race4 UPLOADED 대기) |
| TC-PORTAL-094 | 포털 자산은 데이터마트 View 미노출 | PASS | [실동작] 4개 `V_COMPLETED_*` 뷰 정의에 `ls_portal` 참조 **0건**, `%/portal/%` 경로 조회 결과 **0행** | |

## 근거 드리프트

| TC | 카탈로그 근거 | 실제 | 영향 |
|---|---|---|---|
| TC-PORTALUP-081 | `PortalUploadService.java:128` | **`:129`** (`uld.markReady(null, null, 1)`) | 라인 1줄 이동. 판정 무영향 |
| TC-TUS-020 | `PortalVideoUploadTxService.java:109-115`(낙관적 락 충돌 → 409+truncate) | 코드는 존재하나 **실행 경로에서 미도달**. 같은 메서드 선두 `:72` 의 `findByUldIdForUpdate`(PESSIMISTIC_WRITE)가 동시 PATCH 를 직렬화하므로, 후행 요청은 `@Version` 충돌 전에 `:98-100` offset 불일치(409)로 걸러진다. 실측 3병렬에서 `[PortalTus] concurrent PATCH conflict` 로그 0건 | **기대결과 문구 정정 필요** — "낙관적 락 충돌"이 아니라 "비관 락 직렬화 후 offset 불일치 409". 결과(409·파일 무손상)는 동일하므로 PASS 유지. `:109-115` 는 사실상 도달 불가 분기(잔존 심층방어) |

> 그 외 F-8·F-9·F-10/11 의 근거 `file:line` 은 **전건 일치**했다(카탈로그 F-8 각주의 "`PortalVideoUploadTxService` 전체 -1 이동 반영" 은 정확히 반영돼 있었다).

## 이슈 상세

### [F-ISSUE-41] TC-TUS-002/007 — Upload-Metadata 파일명의 널바이트가 500 + 정리되지 않는 고아 임시파일을 남기고, 동시세션 상한(429)까지 우회한다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 파일명은 표시용 필드지만 사용자 입력이므로 **제어문자(특히 `\0`)를 거부하거나 제거**해 400 으로 마감해야 한다. 실패 시에도 `createEmptyFile` 로 이미 만든 임시파일은 보상 삭제돼야 한다(부분 실패 원자성).
- **현재 동작(이슈 내용)**:
  - `PortalTusUploadController.java:178-192 parseFileName` → `decodeBase64` 는 base64 디코딩만 하고 **제어문자 검사를 하지 않는다.**
  - `PortalVideoUploadService.java:104-118` 은 ① `TusChunkStore.createEmptyFile(absolutePath)`(디스크 쓰기) → ② `LsPortalTusUpload.create(..., truncate(cmd.fileName()))` → ③ `tusRepository.save(session)` 순서다. `ORGNL_FILE_NM` 에 `0x00` 이 실려 **PostgreSQL 이 INSERT 를 거부**한다.
  - 실측 로그: `SQL Error: 0, SQLState: 22021` / `ERROR: invalid byte sequence for encoding "UTF8": 0x00` → `GlobalExceptionHandler - unclassified data integrity violation constraint=null cause=PSQLException` → **HTTP 500** `{"errorCode":"INTERNAL_ERROR"}`.
  - **부작용 3종**:
    1. `/app/storage/raw/portal/tus-video/{uuid}.mp4` **0바이트 고아 파일이 남는다**(트랜잭션 롤백은 DB 만 되돌린다). 실측 2건(`cd6aebc1-…`, `73d0d1b4-…`) 확인.
    2. 스윕(`claimExpiredSessions`)은 **DB 행 기준**으로만 파일을 지우므로 이 고아 파일은 **영구히 정리되지 않는다**(CWE-459).
    3. DB 행이 안 생기므로 `countByPortalUserNoAndSttsCd(IN_PROGRESS)` 가 증가하지 않아 **동시세션 상한 3(429)이 전혀 걸리지 않는다** → 한 PORTAL_USER 가 이 요청을 무제한 반복해 파일/아이노드를 계속 증식시킬 수 있다(CWE-770). TUS 경로에는 별도 rate limiter 도 없다(`F-ISSUE-46`).
  - 성공 INFO 로그 `[PortalTus] session created uldId=… ext=mp4` 가 **커밋 실패 전에** 찍혀 운영 로그가 실제와 어긋난다.
- **재현/확인 경로**:
  ```bash
  TOKEN=... # PORTAL_USER
  MD=$(printf 'a\0b.mp4' | base64)   # YQBiLm1wNA==
  curl -i -X POST http://localhost:18081/api/v1/portal/uploads/tus \
    -H "Authorization: Bearer $TOKEN" -H "Tus-Resumable: 1.0.0" \
    -H "Upload-Length: 100" -H "Upload-Metadata: filename $MD"
  # → HTTP 500 {"errorCode":"INTERNAL_ERROR"}
  docker exec klid-backend ls -l /app/storage/raw/portal/tus-video/   # 0바이트 고아 파일 잔존
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select count(*) from ls_portal_tus_uld where orgnl_file_nm like 'a%';"   # 0
  ```
- **영향**: CWE-20(입력 검증 누락) · CWE-459(불완전 정리) · CWE-770(무제한 자원 소비 — 상한 우회) · CWE-209(운영 500). 실동작 확인. 저장소가 공유 NAS 이므로 아이노드 고갈은 **내부 파이프라인까지 동반 마비**시킬 수 있다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`parseFileName` 단계에서 `\0`·개행 등 제어문자 포함 시 400 으로 거부(또는 제거 후 저장). ②`createSession` 순서를 "DB 저장 → 파일 생성"으로 뒤집거나, INSERT 실패 시 `TusChunkStore.deleteQuietly(absolutePath, storageRoot)` 보상 삭제를 `catch` 에 추가. ③성공 로그를 커밋 이후로 이동.

### [F-ISSUE-42] TC-PORTALUP-082/083 — 포털 정리 스윕(`@Scheduled`)의 발화가 무관한 토글 `authoring.work-lock.sweep.enabled` 에 결합돼 있다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `PortalUploadSweepJob` 은 자기 도메인 설정(`portal.upload.sweep.*`)으로 발화 여부가 정해져야 한다. 만료 TUS 세션·고착 자산 정리는 기능 토글과 무관하게 항상 돌아야 하는 위생 잡이다.
- **현재 동작(이슈 내용)**:
  - 애플리케이션 전체에서 `@EnableScheduling` 은 **`auth/scheduler/WorkLockSweepConfig.java:14-17` 단 한 곳**뿐이고, 거기에 `@ConditionalOnProperty(name="authoring.work-lock.sweep.enabled", havingValue="true", matchIfMissing=false)` 가 붙어 있다.
  - `application-local.yml:68-70` 은 `authoring.work-lock.sweep.enabled: false` → **local 프로파일에서는 `@EnableScheduling` 이 등록되지 않아 `PortalUploadSweepJob.run()` 의 `@Scheduled` 가 영원히 발화하지 않는다.**
  - 실측: 컨테이너 기동 `2026-07-30T17:43:08Z`(프로파일 `local`), 초기지연 10분 + 주기 30분 기준으로 4회 이상 발화했어야 하나 `docker logs klid-backend | grep PortalSweep` **0건**. 만료 세션 픽스처(`EXPRY_DT` 2시간 과거, `IN_PROGRESS`)와 고착 자산 픽스처(`PROCESSING`, `MDFCN_DT` 2시간 과거)를 두고 30분+ 관측했으나 **둘 다 그대로 남았다.**
  - `application.yml:206-210` 주석은 이 결합을 **인지하고 있음**을 보여준다("…다른 `@Scheduled` 잡(Portal/TUS sweep)을 부수적으로 발화시키지 않는다"). 즉 의도된 부작용이지만, **포털 스윕 쪽에는 자기 토글이 없어** 운영자가 `WORK_LOCK_SWEEP_ENABLED=false` 로 두면 포털 정리가 조용히 죽는다(dev/stg/prd 기본값은 `true` 라 현재는 동작).
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep SPRING_PROFILES_ACTIVE     # local
  grep -rn "EnableScheduling" backend/src/main/java              # WorkLockSweepConfig 1곳뿐
  sed -n '66,71p' backend/src/main/resources/application-local.yml   # work-lock.sweep.enabled: false
  docker logs klid-backend 2>&1 | grep -c PortalSweep            # 0
  ```
- **영향**: 프로젝트 반복 결함 패턴(`feature-toggle-coupling-defect-pattern`)의 재현. local/테스트에서 스윕 회귀가 **런타임으로는 절대 잡히지 않고**, 운영에서 무관한 토글 조작 한 번으로 고아 세션·임시파일·PROCESSING 영구 고착이 누적된다. CWE-459/CWE-770 의 완화 수단이 사라지는 것.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`PortalUploadSweepConfig`(`@ConditionalOnProperty("portal.upload.sweep.enabled")` + `@EnableScheduling`)를 도메인 국소로 신설해 자기 토글로 발화하게 하거나, ②`ControlNotifyDebouncer`(`:114`)처럼 전용 데몬 스케줄러를 잡이 직접 소유해 `@EnableScheduling` 비의존으로 전환. ③어느 쪽이든 "스케줄이 실제 등록됐는가"를 기동 로그/헬스에 드러낼 것.

### [F-ISSUE-43] TC-TUS-006/013 — 만료된 IN_PROGRESS 세션이 동시세션 슬롯을 계속 점유하고, 세션 목록 조회 API 가 없어 사용자가 최대 24시간 자기 잠금에 걸린다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 동시 진행 상한(3)은 **살아있는** 세션만 세야 한다. 만료된 세션은 상한 계산에서 빠지거나, 사용자가 스스로 정리할 수단이 있어야 한다.
- **현재 동작(이슈 내용)**:
  - `PortalVideoUploadService.java:97-102` 는 `tusRepository.countByPortalUserNoAndSttsCd(portalUserNo, IN_PROGRESS)` 만 세고 **`EXPRY_DT` 를 보지 않는다**(`LsPortalTusUploadRepository:26`).
  - 만료 세션은 `findExpired`+스윕이 지워야 정리되는데, 그 스윕이 30분 주기이고 로컬에선 아예 안 돈다(`F-ISSUE-42`). TTL 은 `LsPortalTusUpload.TTL_HOURS = 24`.
  - 결과: 브라우저 탭을 닫아 `uldId` 를 잃어버린 세션 3건이 쌓이면 **최대 24시간 + 스윕 주기 동안 신규 업로드가 전부 429** 가 된다. `PortalTusUploadController` 에는 **내 세션 목록 조회(GET) 엔드포인트가 없어** 사용자가 uldId 를 되찾아 DELETE 할 방법도 없다.
  - 실측: 사용자 3002 에 만료 세션 1건이 IN_PROGRESS 로 남아 있는 상태에서 신규 생성 → `201, 201, 429`. 만료 세션이 슬롯 1칸을 그대로 먹고 있음을 확인.
- **재현/확인 경로**:
  ```sql
  update ls_portal_tus_uld set expry_dt = now() - interval '2 hour' where uld_id='<세션>';
  ```
  ```bash
  # 같은 사용자로 세션 3개 추가 생성 → 3번째부터 429
  for i in 1 2 3; do curl -s -o /dev/null -w "%{http_code} " -X POST .../uploads/tus \
    -H "Authorization: Bearer $P2" -H "Tus-Resumable: 1.0.0" -H "Upload-Length: 1000" \
    -H "Upload-Metadata: filename $(printf lock$i.mp4|base64)"; done   # 201 201 429
  ```
- **영향**: 가용성(사용자 자기 잠금). 보안 결함은 아니나 포털 사용자 입장에서 복구 불가능한 막다른 상태다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①상한 카운트를 `stts_cd='IN_PROGRESS' AND expry_dt > now()` 로 좁히거나, ②세션 생성 시 자기 만료 세션을 lazy 정리, ③`GET /v1/portal/uploads/tus`(내 진행 중 세션 목록)를 추가해 재개·취소 동선을 열 것.

### [F-ISSUE-44] TC-TUS-014 — TUS PATCH 에 잘못된 Content-Type 을 보내면 415 가 아니라 500 + `INTERNAL_ERROR` 가 나간다 (A-ISSUE-21 의 포털 경로 재현)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `@PatchMapping(consumes = "application/offset+octet-stream")` 매칭 실패는 **415 Unsupported Media Type**.
- **현재 동작(이슈 내용)**: `HttpMediaTypeNotSupportedException` 이 `GlobalExceptionHandler` 의 `Exception.class` fallback 으로 떨어져 **500** 을 반환하고, 로그에 `ERROR … unhandled exception` + 스택트레이스를 남긴다.
  ```
  2026-07-31 04:33:59.630 ERROR [0c43e8eabb47] GlobalExceptionHandler - [Exception] unhandled exception
  org.springframework.web.HttpMediaTypeNotSupportedException: Content-Type 'application/json' is not supported
  ```
  응답: `{"success":false,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}` / HTTP 500.
- **재현/확인 경로**:
  ```bash
  curl -i -X PATCH .../v1/portal/uploads/tus/<uldId> -H "Authorization: Bearer $P1" \
    -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 100" -H "Content-Type: application/json" --data-binary @chunk
  # → HTTP 500
  ```
- **영향**: A 클러스터 `A-ISSUE-21`(415/406/multipart 4xx → 500 + 전체 스택)의 **동일 근본원인이 포털 TUS 경로에서도 재현**됨을 확증. 인증된 PORTAL_USER 가 헤더 한 줄로 ERROR 로그 + 스택트레이스를 무제한 생성 가능(CWE-770/CWE-209). 표준 tus 클라이언트 호환성도 깨진다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** A-ISSUE-21 수정(`HttpMediaTypeNotSupportedException`·`HttpMediaTypeNotAcceptableException` 등 Spring MVC 표준 예외를 `@ExceptionHandler` 로 4xx 매핑)에 흡수시킬 것 — 포털 전용 수정은 불필요.

### [F-ISSUE-45] TC-PORTALUP-065 — `computeFrameNumbers` 가 `maxFrames` 상한을 적용하기 *전에* 후보 프레임 리스트를 전량 생성하고, 그 크기가 ffprobe 가 신고한 영상 길이에 비례한다(길이 무검증)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 상한(2000)이 **메모리 할당 자체를 제한**해야 한다. 컨테이너 헤더가 신고하는 duration 은 신뢰할 수 없는 사용자 입력이므로 상식적 범위로 클램프돼야 한다.
- **현재 동작(이슈 내용)**: `PortalFrameExtractRunner.java:151-163`
  ```java
  long totalFrames = Math.max(1L, Math.round(durationSec * effFps));
  long step        = Math.max(1L, Math.round(effInterval * effFps));
  List<Integer> candidates = new ArrayList<>();
  for (long n = 0; n < totalFrames; n += step) { candidates.add((int) n); }   // ← 상한 적용 전
  if (candidates.size() <= cap) { return candidates; }
  ```
  루프 반복 수 = `totalFrames / step` ≈ **`durationSec / intervalSec`** (fps 는 상쇄된다). `cap`(=2000)은 리스트를 **다 만든 뒤에** 적용된다.
  - `durationSec` 은 `PortalVideoProbeFfprobe.probeTask` 가 ffprobe `format.duration`/스트림 duration 을 **그대로** 돌려주며(`:88-95`) 어떤 상한 검증도 없다. 파일 크기와의 정합성 교차검증도 없다.
  - mp4 `mvhd/mdhd` duration 은 uint32 이므로 timescale=1 이면 최대 ≈4.29e9 초를 신고할 수 있다. 이 경우 후보 수 ≈ 8.6억 → `ArrayList<Integer>` OOM.
  - `runAsync` 의 `catch (Exception)`(`:78`)은 **`OutOfMemoryError` 를 잡지 못하며**, `portalExtractExecutor` 는 core=1 이라 이 스레드가 죽으면 포털 프레임 추출 전체가 영향을 받는다.
  - `(int) n` 캐스팅(`:156`)도 `totalFrames > Integer.MAX_VALUE` 에서 음수 프레임 번호를 만든다.
- **재현/확인 경로**: 관계식은 실측으로 확인했다 — 120s/interval 5 → 후보 24개, 12s/interval 10 → 2개, 12s/interval 5 → 3개(정확히 `duration/interval`). ⚠ **OOM PoC(mvhd duration 위조 컨테이너 업로드)는 공유 스택을 죽여 다른 검증 에이전트의 데이터를 파괴할 위험이 있어 의도적으로 실행하지 않았다.** 코드 경로상 도달 가능성은 위 라인으로 확정.
  ```bash
  # 관계식 확인(실행함)
  # interval 10 + 12s/10fps → frme_cnt=2 / interval 5 + 120s/10fps → frme_cnt=24
  ```
- **영향**: CWE-770(무제한 자원 소비) / CWE-789(입력값 기반 대용량 할당). 인증된 PORTAL_USER 가 **작은 크기의 크래프트 mp4 하나로** 백엔드 힙을 고갈시킬 수 있다. 5GB 상한·확장자 allowlist·매직바이트·ffprobe 검증을 전부 통과할 수 있는 경로다(모두 "실제 영상인가"만 보고 "길이가 말이 되는가"는 안 본다).
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`candidates` 생성 루프에 `cap` 기반 조기 중단(또는 처음부터 `min(totalFrames/step, cap)` 개만 생성)을 넣고, ②`durationSec` 을 상식 상한(예: 24h)으로 클램프하며 초과 시 400/FAILED 로 마감, ③`(int)` 캐스팅 대신 long→int 안전 변환.

### [F-ISSUE-46] TC-TUS-002/029 — TUS 세션 생성에 rate limit 이 없고 CANCELLED/COMPLETED 세션 행은 스윕 대상이 아니라 `LS_PORTAL_TUS_ULD` 가 무한 증식한다
- **심각도**: LOW
- **기대 동작(기대효과)**: 세션 생성에 속도 제한이 있거나, 종결된 세션 행이 일정 기간 후 정리돼야 한다.
- **현재 동작(이슈 내용)**:
  - `/v1/portal/uploads/tus/**` 에는 rate limiter 가 없다 — 코드베이스에서 rate limit 을 갖는 곳은 `PortalUploadController`(이미지 업로드)·`PortalSam2Service`·`RoleClaimRateLimiter`·`WebhookRateLimiter` 뿐이고 TUS 컨트롤러/서비스에는 없다(grep 0건). 유일한 억제는 **동시 IN_PROGRESS 3건** 상한이다.
  - 그런데 `deleteExpiredInProgress`(`LsPortalTusUploadRepository:66-68`)와 `findExpired`(`:53-55`)는 **`stts_cd='IN_PROGRESS'` 인 행만** 지운다. `CANCELLED` 행은 어떤 경로로도 삭제되지 않는다(`COMPLETED` 는 영구 영상 링크라 보존이 타당).
  - 따라서 "3건 생성 → 3건 DELETE(취소) → 반복" 루프로 **행을 무제한 적재**할 수 있다(파일은 취소 시 지워지므로 디스크는 안전). 실측 세션 18건 중 CANCELLED 7건이 남아 있다.
  - 참고로 UNCERTAINTIES #12 는 "rate limit 은 업로드·SAM2·**TUS** 에만 존재"라고 적고 있으나 **TUS 에는 실제로 없다** — 문서 정정 대상.
- **재현/확인 경로**:
  ```bash
  grep -rln "RateLimit" backend/src/main/java | grep -i tus     # 0건
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select stts_cd, count(*) from ls_portal_tus_uld group by stts_cd;"   # CANCELLED 누적
  ```
- **영향**: CWE-770(약함 — 행 크기가 작고 디스크는 무사). 장기적으로 `LS_PORTAL_TUS_ULD` 비대화 및 `idx_lptu_user_stts` 외 조회 성능 저하.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①TUS `POST` 에 사용자별 생성 rate limit 추가, ②스윕에 "종결(CANCELLED) 후 N일 경과 행 삭제" 절 추가, ③UNCERTAINTIES #12 의 "TUS 에도 rate limit 존재" 서술 정정.

### [F-ISSUE-47] TC-TUS-010 — `Tus-Resumable` 헤더가 아예 없는 요청을 그대로 처리한다(TUS 1.0 권고는 412)
- **심각도**: LOW
- **기대 동작(기대효과)**: TUS 1.0 은 OPTIONS 를 제외한 모든 요청에 `Tus-Resumable` 을 요구하며, 없으면 412 를 권고한다.
- **현재 동작(이슈 내용)**: `PortalTusUploadController.java:163-168`
  ```java
  private void requireTusVersion(String tusResumable) {
      if (tusResumable != null && !TUS_VERSION.equals(tusResumable)) { throw ...PRECONDITION_FAILED; }
  }
  ```
  `null` 이면 검사를 건너뛴다. 실측: 헤더 없이 보낸 POST/PATCH/HEAD/DELETE 가 모두 정상 처리됐다(본 검증의 여러 요청이 이 경로를 탔다).
- **재현/확인 경로**:
  ```bash
  curl -i -X POST .../v1/portal/uploads/tus -H "Authorization: Bearer $P1" \
    -H "Upload-Length: 30817" -H "Upload-Metadata: filename $(printf a.mp4|base64)"   # → 201
  ```
- **영향**: 프로토콜 준수 미달(보안 영향 없음). 버전 협상 없이 다른 TUS 버전 클라이언트가 조용히 붙어 향후 스펙 변경 시 무증상 오동작 가능.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `tusResumable == null` 도 412 로 마감(OPTIONS 제외). FE 포털 TUS 클라이언트가 항상 헤더를 붙이는지 먼저 확인 후 적용할 것.

---

## 검증 중 생성/변경한 데이터 (다른 에이전트 참고)

- `LS_PORTAL_ULD` **uld_sn 124~132 신규**(126=short.mp4 READY 1프레임, 128=FAILED 재현용, 129=중단 재현용 FAILED, 130 결번=보상삭제, 131/132=READY 24프레임). `LS_PORTAL_TUS_ULD` 세션 다수(COMPLETED/CANCELLED).
- `LS_PORTAL_USER_LABEL` 1행(rawSn 20012, TC-PORTAL-092 검증용).
- `ls_system_config['portal.upload.frame-interval-sec']` 를 5 → 10 → 1 → **5 로 원복 완료**(현재 값 5, DB 확인함).
- `uld_sn=126` 을 스윕 픽스처로 PROCESSING/FAILED 로 바꿨다가 **READY 로 원복 완료**.
- **rawSn 126·133 은 건드리지 않았다.** `LS_DATA_RAW`/`LS_DATA_SRC`/`LS_DATA_LBL` 은 조회만 했고 변경 0건(라벨 655 → 655 불변 확인).
- 잔존 고아 임시파일 2건(`F-ISSUE-41` 증거)은 그대로 두었다: `/app/storage/raw/portal/tus-video/cd6aebc1-…mp4`, `73d0d1b4-…mp4`(각 0바이트, DB 행 없음).
