# 05. 영상 관리 · 업로드

> 출처: CLAUDE.md(배치 파이프라인·파일 업로드), 코드(`video/`, db migration), D9
> 관련: [06 마킹](06-marking.md) · [07 배치 파이프라인](07-batch-pipeline.md) · [18 DB](18-database.md)

화면: `KLID-AT-SC-007`(영상 목록 `/video/completed`), `SC-009`(영상 상세 `/video/:id`). 코드: `video/`. (구 `SC-008` 처리 현황 `/video/status`는 진입점 없는 orphan으로 2026-06-17 deprecated·코드 제거 → [04 화면·IA](04-screens-ia.md))

## 5.1 작업 단위 = 영상 1건

- 작업 식별자: **`LS_DATA_RAW.RAW_SN`** (프로젝트 개념 없음)
- 영상 1건이 마킹→배치→라벨링→검수→통지의 단위
- 증강 결과는 **새 영상**(`RAW_SN`, `ORGNL_RAW_SN`=원본 참조) → [14](14-augmentation.md)

## 5.2 영상 적재

- 영상 적재는 **자체 업로드(관리 화면) 기반** — 관제서버 자동 송신 미연동
- **포털 사용자 업로드는 미제공** (ADR-013)
- **TUS 재개 가능 업로드**(CVAT portable-modules/03 포팅) — 내부 관리 화면 대용량 영상 적재용

### TUS 1.0 재개 가능 업로드 (V59 — 구현)
- 엔드포인트 `/api/v1/uploads` (REVIEWER·INTERNAL 전용). **헤더 기반 TUS 프로토콜** — `ApiResponse` 래퍼 미사용, 표준 클라이언트(tus-js-client 등) 호환. 모든 응답 `Tus-Resumable: 1.0.0`, 버전 불일치 412.
  - `POST /v1/uploads` — `Upload-Length`(TUS 헤더) + **인입 메타 JSON 바디**(`application/json`, 관제 수신 29컬럼 재현) → `@Valid` 검증 → `LS_TUS_UPLOAD` 행+임시파일 생성 → **`LS_DATA_INGEST` 인입 행(`PRCS_STTS_CD='PENDING'`) INSERT** → 201 + `Location: /v1/uploads/{uploadId}` + `X-Ingest-Status`
    - ★ **메타를 `Upload-Metadata` 헤더로 받지 않는다**(표준 이탈, 의도적) — 관제일지(`MNTR_CN VARCHAR(4000)`) 하나만으로도 헤더 상한(1KB)을 넘긴다. `creation-with-upload`(POST 바디에 첫 청크를 싣는 확장)를 구현하지 않아 충돌이 없으며, 그래서 `Tus-Extension` 에 그 확장을 **광고하지 않는다**(광고하면 표준 클라이언트가 바디에 바이너리를 실어 계약이 깨진다). **포털 업로드(`/v1/portal/uploads/tus`)는 별도 컨트롤러라 헤더 방식 그대로다**
    - ★ **인입 행이 파일보다 먼저 생긴다** — 인입 테이블이 "행 먼저, 파일 나중"을 이미 견디기 때문이다(미도착은 실패가 아니라 대기 → `PENDING` 복귀 + backoff, 상한 초과 시에만 종결). 덕분에 2단계 확정 API·새 세션 상태 없이 29컬럼을 그대로 실을 수 있다
  - `HEAD /v1/uploads/{id}` — `Upload-Offset`/`Upload-Length` 응답(재개), 만료 410
  - `PATCH /v1/uploads/{id}` (`application/offset+octet-stream`) — 청크 append → 새 `Upload-Offset`. 완료(offset==length) 시 매직바이트 + 재생 가능성(ffprobe) 검증 → 파일을 인입 영역(`{raw-path}/data/upload/v2/{vmsClipId}.{ext}`, = 인입 행이 이미 가리키는 경로)으로 이동 → **`NXTM_RTY_DT` 를 지금으로 당겨 backoff 해제**. **추가 INSERT 는 없다**
    - ★ backoff 해제가 필수인 이유: 미도착 backoff 는 "지금까지 기다린 만큼 더"(1분~1시간)라, 20분짜리 업로드는 **파일이 도착한 뒤에도 최대 20분을 더 기다린다**. 도착 사실을 아는 주체는 완료 처리뿐이다
    - ffprobe 는 **값을 쓰기 위한 호출이 아니다** — 영상 길이 정본은 화면 입력값이고, 비우면 적재 후 `VideoMetaService` 가 채운다. 여기서는 손상·미지원 파일을 적재 대기열에 넣지 않기 위한 검증으로만 돈다
  - `DELETE /v1/uploads/{id}` — 세션 취소 + 임시파일 삭제 + **인입 행 `FAILED` 종결**(사유 `업로드 취소…`). 관제 인입과 달리 취소는 오류가 아니라 일상적 동선이라, 종결하지 않으면 파일이 영영 오지 않는 행이 미도착 대기 상한(24h) 동안 재시도하다 쌓인다. **이미 완료된 세션은 인입 행을 건드리지 않는다**(적재 대기 중인 정상분 보호)
    - ⚠ 인입 행은 **영구 보존**(삭제 금지)이라 취소분도 `UK(VMS_CLIP_ID)` 를 계속 점유한다. 그래서 같은 클립 ID 재업로드는 그 행을 **지우지 않고 되살린다**(같은 PK 를 새 메타로 UPDATE — `InternalUploadIngestWriter.reviveForUpload`). 되살리기 **4조건**(모두 만족, fail-closed): ①경로가 인입 영역 하위(= 우리가 만든 행. 관제 행은 관제 NAS 경로라 절대 매칭 안 됨) ②`FAILED` + `RAW_SN IS NULL` ③파일 미도착 ④**그 클립 ID 로 진행 중(IN_PROGRESS) 세션이 없음**
      - ★ ④가 필요한 이유(2026-08-03) — 세션 종결 경로는 모두 세션·인입 행을 함께 종결하는데 **미도착 대기 상한 종결만은 인입 행만 `FAILED` 로 내리고 세션을 모른다**(그 잡은 세션 테이블을 보지 않는다). 상한이 세션 TTL(24h)보다 짧은 형상이면 **세션이 살아 있는 채 행이 종결**되고, 그것을 되살리면 같은 클립 ID 의 세션이 둘 살아나 완료 순서에 따라 "뒤 세션 메타 + 앞 세션 파일" 로 뒤섞인다. 파일 대체 자체는 원자 예약이 막지만(아래) 상태를 애초에 만들지 않는다
      - ⚠ **미해소 — 관제 clipId 선점**: 되살리기는 우리 세션 생성 경로에서만 발동하므로, REVIEWER 가 "관제가 앞으로 쓸 clipId" 로 세션을 만들었다 취소하면 그 `FAILED` 행이 UK 를 계속 점유해 **관제 INSERT 가 UK 위반으로 실패**한다(관제엔 관측 수단 없음). 내부 권한 보유자로 한정된 잔여 위험이며, 해소하려면 clipId 네임스페이스 분리 또는 관제 측 실패 관측 통로가 필요하다(관제 계약 변경 — 별도 트랙)
      - ⚠ **미해소 — 서로 다른 clipId 반복**: 되살리기는 *같은* clipId 반복만 1행으로 눌러 준다. 서로 다른 clipId 로 세션 생성·취소를 반복하면 인입 행은 여전히 누적 증식한다(동시 세션 상한 3 은 **동시** 수만 제한)
- **적재는 인입 경로가 담당한다 (적재 주체 반전 정합)** — 업로드는 `LS_DATA_RAW` 를 직접 만들지도 `VideoIngestedEvent` 를 발행하지도 않는다. 관제가 INSERT 한 인입 행과 **똑같이** 폴링 배치(`ControlTrainingVideoScanJob` → `TrainingVideoIngestTx`)가 픽업해 적재하고, 비식별 선두 트리거도 그 배치가 발행한다. 우회하면 적재 규칙(경로 allowlist·중복 판정·상태 전이·기술메타 back-fill)이 업로드에만 적용되지 않는 두 번째 진실원이 된다. → [07](07-batch-pipeline.md)
  - `LS_DATA_INGEST` 에 대한 **비-관제 INSERT 통로는 `InternalUploadIngestWriter` 하나**다(고정 컬럼 + 플레이스홀더만, 저작도구 운영 8컬럼은 SQL 에 없음). 엔티티 `LsDataIngest` 에는 INSERT 팩토리·setter 를 두지 않는다
  - `LS_TUS_UPLOAD.FILE_PATH` 는 **임시 경로 그대로** 둔다 — NAS 경로로 갱신하면 완료 전이가 유실된 세션을 24h 뒤 `TusUploadCleanupJob` 이 스윕할 때 인입 완료된 원본을 지운다(정리 가드가 `raw-path` 하위만 보므로 새 경로도 통과)
  - **입력 항목 = 관제 수신 29컬럼**(필수 5: `VMS_CLIP_ID`·`VMS_CCTV_ID`·`VDO_FILE_NM`·`RAW_FILE_PATH_NM`·`SRC_TYPE`. 뒤 둘은 서버가 저장 규약으로 정한다). `SRC_TYPE` 은 폼에서 선택 가능하되 적재와 **같은 allowlist**(`LsDataIngest.ALLOWED_SRC_TYPES`)로 판정한다(어긋나면 적재 시 조용히 null 이 된다)
  - **기술메타 12종은 선택 입력** — 채운 키는 그 값이 인입 행에 실리고 **비운 키만** 적재 후 ffprobe 가 채운다(`VideoMetaService` 의 "관제 인입값 우선, 없는 키만 ffprobe — 폴백은 키 단위" 규칙을 그대로 재사용, 재구현 없음). 서버가 이미 아는 `FILE_SZ`(=`Upload-Length`)·`FILE_FMT`(=확장자)만 기본값을 채우고 나머지는 **추측해 채우지 않는다**
  - `prvcTypeCd`·`eventTypeCd` **입력은 폐지**됐다 — `LS_DATA_INGEST` 에 대응 컬럼이 없기 때문이다(개인정보 유형은 적재 시 `PRVC` fail-closed 기본값). 이벤트는 인입 컬럼과 같은 축인 `EVNT_ID`(식별자형, 예 `ABA_0001`)·`EVNT_NM` 입력으로 대체됐다
    - ⚠ **`EVNT_ID` 를 비우면 그 영상은 마킹할 수 없다** (2026-08-04) — 적재 시 `EVNT_ID` 로 `MNG_CLIP_EVNT_LST` 를 조회해 `LS_DATA_RAW.EVNT_TYPE_CD` 를 채우는데, 그 값이 비면 마킹 프리컨디션이 400 으로 막는다. dev 업로드로 마킹까지 돌려 보려면 **이벤트리스트에 실재하는 `EVNT_ID`** 를 넣어야 한다(로컬 시드값은 `DEV-EVT-9101~9103`) → [06](06-marking.md#61-마킹이란)
  - ★ 업로드 저장 경로가 적재 allowlist(`STORAGE_RAW_MOUNT_ROOTS`) 밖이면 인입이 매 건 `REJECTED` 로 영구 종결되므로, `InternalUploadWiringGuard` 가 배포 형상(local 외)에서 **업로드 기능만 비활성**한다 — 앱은 정상 기동하고 기동 로그에 **ERROR** 1회, TUS 엔드포인트는 **503**(조용한 성공·원본 경로 폴백 없음, fail-closed). **실패 범위를 앱 전체가 아니라 기능 단위로 한정**한 이유는 onprem 설치 안내가 마운트 루트를 좁히라고 권장하므로, 기동을 막으면 업로드 1개 기능의 오설정으로 라벨링·검수·배치까지 정지하기 때문이다. onprem 에서 마운트 루트를 좁힐 때는 `STORAGE_RAW_PATH` 를 반드시 포함할 것 → [04-configuration](../../deploy/onprem/docs/04-configuration.md)
  - ★ **인입 폴링(`TRAINING_SCAN_ENABLED`)이 꺼져 있으면 업로드분은 영원히 `PENDING`** 이다(적재 통로가 이 잡 하나뿐). 기동 시 ERROR 로그 + 응답 헤더 `X-Ingest-Status: PENDING` \| `PENDING_SCAN_DISABLED`(POST·완료 PATCH 양쪽) 로 드러내고, **화면이 그 값을 완료 문구에 반영**한다("인입 대기 중" / 스캔 꺼짐 경고). local 프로파일도 이제 이 잡을 **켠다**(끄면 자체 업로드가 무의미 — 테스트 격리는 `src/test/resources/application-local.yml` 이 담당)
  - **실패 모델 — 옮긴 파일을 회수하지 않는다**. 인입 행이 **먼저** 커밋돼 그 파일을 가리키므로, 지우는 쪽이 오히려 결함이다(폴링이 영원히 미도착 대기 → 24h 뒤 `FAILED`). 구 모델(완료 시 INSERT)에서 필요했던 "행 없는 고아 파일" 보상 삭제는 구조적으로 사라졌다. 대신 **세션 생성 시 INSERT 실패**면 방금 만든 임시 파일을 회수하고(0바이트 누수 방지), **완료 시 검증 실패**(매직바이트·재생 불가)는 파일이 영영 오지 않을 것이 확정되므로 세션과 인입 행을 **둘 다 별도 트랜잭션으로 종결**한다(같은 트랜잭션이면 직후 예외와 함께 롤백돼 무효)
  - **파일명 선점은 원자적**(`InternalUploadPathResolver.reserveIngestTarget` = `Files.createFile` = `O_EXCL`)이다. `exists()` 검사 후 `ATOMIC_MOVE` 는 **검사 후 사용**이라 그 사이 다른 주체가 만든 파일을 조용히 대체한다(같은 `vmsClipId` 두 세션 완료 시 뒤에 온 영상이 앞의 영상을 덮고 **양쪽 다 204**. 세션 락은 uploadId 단위라 직렬화 불가). 예약에 성공한 실행만 그 이름에 내용을 싣고, 진 쪽은 **409**
    - 예약은 최종 경로에 잠깐 **0바이트** 파일을 만든다 — 이것이 폴링에 노출돼도 적재 측 **완결성 게이트**(크기 0 = `NOT_ARRIVED`)가 대기로 떨어뜨리므로 빈 영상이 적재되지 않는다. 이동 실패 시 예약을 즉시 회수하고, 회수까지 실패한 잔여물도 게이트가 계속 막는다(대신 그 clipId 는 잔여물을 치우기 전까지 재사용 불가 — WARN 으로 드러난다)
    - 회귀 가드: 예약~이동 창에 경합을 주입하는 `TusUploadServiceTest#F3_예약~이동_사이에…`(예약 통로를 타지 않으면 실패) + 8스레드 동시 예약 `InternalUploadPathResolverTest#F3_같은_이름을_동시에…`(배타적이지 않으면 실패)
  - **"파일이 도착했는가" 판정은 경로 검증을 포함한다 (2026-08-03)** — 인입 행 종결(취소·TTL 만료)과 되살리기 3번 조건이 모두 이 판정에 걸린다. `Files.exists` 단독 판정은 **임의 대상 심링크**에 속아(허용 루트 밖 아무 파일이나 가리켜도 "도착") 종결을 보류시키고 그 clipId 를 잠글 수 있었다. 판정은 적재 측과 같은 규약(고정 allowlist 재판정 → 실경로 해석 → 일반 파일)을 쓰는 `InternalUploadPathResolver.arrivedRegularFile` 하나로 모은다(호출처마다 재구현하면 갈라진다)
  - **도착 통지 0행 시 짧은 재시도 (M5, 2026-08-03)** — 완료 시점에 폴링이 그 행을 클레임 중(`PROCESSING`)이면 backoff 해제(`markUploadArrived`, 술어 `PENDING`)가 0행이 된다. 폴링의 미도착 복귀는 같은 tick 안 수 ms 에 커밋되고 이 UPDATE 는 멱등이므로 **30ms×3회 bounded-retry** 로 대부분 회수한다(실패해도 구 동작과 동일 — 최대 backoff 상한만큼 늦어질 뿐). "스키마 없이는 구조적으로 불가"가 아니라 **확률적 회수**다
  - **쓰기 직전 경로 재판정은 고정 allowlist 축**으로 한다 — 대상 자신(`uploadDir`)을 기준으로 삼으면 항등식이라 아무것도 판정하지 못하고, 경로 중간 디렉터리를 심링크로 교체하면 그대로 통과한다(CWE-59/367)
- 만료 정리: `EXPIRES_AT`(+24h) TTL + `@Scheduled` 정리 잡(`TusUploadCleanupJob`, 1h 간격)
- FE: `useTusUpload` 훅 + `TusUploadPanel`(진행률 + 일시정지/재개). 폼은 인입 29컬럼을 **식별 / 위치·CCTV / 이벤트 / 기술메타(선택)** 4그룹으로 나눠 받고, 기술메타 그룹에는 "비우면 서버가 파일에서 자동 추출"을 명시한다. 완료 문구는 **"영상 등록 생성"이 아니라 "인입 대기"** 다(업로드 완료 ≠ 적재). 기존 multipart 경로(`/dev/autolabel-test`)는 fallback 유지

### 업로드 검증 (보안)
- 확장자 allowlist + 파일 크기 제한 + MIME 검증 필수
- 경로 순회(`..`) 차단(`Path.normalize` + 기준 경로 검증, CWE-22)
- **`vmsClipId`·`cctvId` allowlist** — 둘 다 `^[A-Za-z0-9_-]{1,64}$`(인입 `VMS_CLIP_ID`/`VMS_CCTV_ID` 와 정합). `vmsClipId` 는 **저장 파일명이 되므로** 경로 문자·상위참조를 원천 차단하고(CWE-22), 두 값 모두 **세션 생성 단(400)** 에서 fail-fast 한다(완료 시점 INSERT 에서 길이 초과로 터지면 500 + 0바이트 임시파일 잔존)
- **★ CCTV 존재 검증은 400 → 경고로 완화**(Phase 1) — `MNG_RESOURCE_CCTV` 를 채우는 주체는 관제뿐이고 저작도구에는 local 시드 외 공급 경로가 없어, 400 을 유지하면 dev/운영 형상의 **모든 업로드가 "등록되지 않은 CCTV"로 죽는다**. 관제 인입 경로(`TrainingVideoIngestTx`)도 이 검증을 하지 않으므로 "관제 인입과 동일 재현" 원칙에도 맞는다. 미등록 CCTV 는 WARN 로그만 남기고 업로드는 계속한다(값 자체는 NOT NULL 이라 필수)
- TUS: 동시 PATCH 오프셋 충돌 `@Version` 409 / `Upload-Length` > 500MB 413 / 완료 멱등(STATUS 원자 전이) / 매직바이트(mp4·webm·avi) 불일치 409 / 세션 소유자(USER_NO) 403 / 동시 IN_PROGRESS 3 상한 429 / 메타 1KB 상한

## 5.3 영상 스트리밍

- `GET /v1/videos/{rawSn}/stream` — **HTTP Range 지원** (마킹 화면 재생용). **항상 비식별 영상 서빙** — 비식별 결과 경로는 최신 성공 `LsDeidentProcLog` 에서 도출하며, 비식별 미완료(경로/파일 부재) 시 **NOT_FOUND** 로 원본 노출을 차단한다(`VideoStreamService`, 전체 무조건 비식별 정책상 모든 영상이 대상)
- **비식별 누락 신고 게이트(S7)** — `/stream`·`/stream-url` 은 **대상 영상 자신의** `DE_IDENT_YN='F'`(신고 구간)이면 **404** 로 거부한다. 판정은 **자기 `rawSn` 행 하나만** 보며 `ORGNL_RAW_SN` 을 따라 올라가지 않으므로, **부모가 신고 중이어도 파생영상(해상도·증강) 재생은 막히지 않는다**(2026-07-29 확정 — 파생은 독립 취급, 함의는 [08 §8.4](08-deidentification.md) 참조). 판정은 `stream-meta` 캐시 **앞**(매 요청)에서 수행되어 캐시 히트가 게이트를 건너뛰지 않는다 → [08 §8.4](08-deidentification.md)
- **경로 가드는 lexical + 실경로 2단이다 (B-ISSUE-81, 2026-08-02 · CWE-59/367/359)** — 비식별 경로(`DE_IDNTF_FILE_PATH_NM`)는 `normalize()+startsWith` 로 허용 base 하위인지 본 뒤, **같은 정적 판정기 `VideoArtifactRootResolver.resolveRealPathUnder` 로 실경로를 재검증**하고 **판정이 돌려준 실경로를 그대로 연다**(판정 대상 == 사용 대상 → TOCTOU 차단). 허용 base 에는 외부 비식별 벤더(KPST)가 공유 마운트로 직접 쓰는 co-locate 디렉터리가 포함되므로, 그 안의 산출물 파일을 **원본(비식별 이전) 영상 심링크**로 바꾸면 lexical 검사만으로는 통과해 마스킹 전 영상이 "비식별 영상"으로 200 서빙됐다(실측 exploit). 모든 base 후보가 실패하면 기존대로 **NOT_FOUND**(경로 원문 미노출). 회귀 가드: `VideoStreamServiceTest` 심링크 3케이스. 프레임 이미지 4경로의 단일 규약(`StorageSubtreePolicy.verifyDeidentifiedFile`)과 같은 원칙이다
- **응답 캐시 정책** — `/stream` 200·206 응답은 `Cache-Control: no-store`. 클라이언트가 받은 청크를 재사용하면 신고 이후에도 마스킹 실패 영상이 서버를 거치지 않고 재생되므로(CWE-359/525) 장기 캐시를 두지 않는다(프레임 이미지 서빙과 동일 정책). 시크마다 Range 재요청이 발생하지만 경로·크기·MIME 해석은 서버측 `stream-meta` 캐시가 흡수하고, 청크 상한(기본 8MB)이 재요청 빈도를 억제한다
- 마킹 화면에서 배속(0.25x~4x) 재생 → [06](06-marking.md)

## 5.4 개인정보 분류 (PRVC_TYPE_CD)

| 값 | 의미 |
|----|------|
| `PRVC` | 개인정보 포함 |
| `PSDO` | 가명처리 대상 |
| `ANONY` | 비식별 불요(분류상) |

- **★ 적재값은 `PRVC` 고정(fail-closed) — 2026-07-31 사용자 확정, 구 `ANONY` 하드코딩 폐기**: 관제팀 확인 결과 **관제서버는 `PRVC_TYPE_CD` 를 실제로 보내지 않는다.** 적재 주체 반전(`LS_DATA_INGEST`) 이후에는 **인입 테이블에 이 컬럼 자체가 없다**(설계 R6 — 워크플로 컬럼은 인입에서 제외). 따라서 관제 인입 경로로 들어오는 **모든 영상**이 이 기본값으로 적재되며, **입력이 없으면 원천영상을 "개인정보가 있고 익명처리되지 않은 것"으로 본다**
  - 단일 원천은 `TrainingVideoIngestTx.DEFAULT_PRVC_TYPE` 다
  - **의도된 회귀**: 신규 적재분의 `PRVC_TYPE_CD` 가 `ANONY`→`PRVC` 로 바뀐다(기존 행 백필 없음)
  - **파급(의도된 fail-closed)**: `needsDeidentify()` 가 true 가 되어 **비식별본이 없는 영상의 프레임 조회는 404**로 닫힌다(구 "ANONY + 비식별 미준비 → 원본 폴백" 분기가 닫힘). 마스킹 전 원본 노출 차단(CWE-359)이 목적이며 결함이 아니다 → [10](10-labeling.md)
- **비식별 처리는 분류와 무관하게 전체 영상 무조건 실행**(ANONY 포함, 게이팅 폐지) — 적재 직후 선두 자동 → [08](08-deidentification.md)
- 원본 영상과 비식별 영상은 **별도 경로 동시 저장** (`STORAGE_RAW_PATH` / `STORAGE_DEIDENTIFIED_PATH`)
- 비식별 상태: `LS_DATA_RAW.DE_IDENT_YN` (Y/N/F) → [08](08-deidentification.md)
- **★ 영상 목록(SC-007)·상세(SC-009)의 "개인정보 분류" 표시는 폐지했다(2026-08-05, 화면 노출만 — 컬럼·응답 필드는 존치)**: 관제서버가 이 값을 실제로 보내지 않는다(dev DB 실측 — 인입 원장 `LS_DATA_INGEST.ANONY_INCL_YN`/`PSDO_INCL_YN`/`PRVC_INCL_YN` 40행 전부 NULL). 화면이 그동안 보여준 값은 관제값이 아니라 위 적재 시 고정되는 레거시 컬럼 `PRVC_TYPE_CD` 였다. `VideoSummaryResponse.privacyTypeCd`/`VideoDetail.privacyTypeCd` 응답 필드는 그대로 내려가며(하위호환), `PRVC_TYPE_CD` 는 계속 `needsDeidentify()`(비식별 대상 판정)에 쓰인다 — 바뀐 것은 **화면 노출뿐**이다. FE `components/common/PrivacyBadge.tsx` 컴포넌트 삭제, `VideoListPage.tsx`(컬럼 8종으로 축소)·`VideoDetailPage.tsx`(기본정보 항목 제거)에서 참조 제거. ⚠ 라벨링 화면의 **개인정보 메타 패널**(`VideoPrivacyMetaPanel`/`FramePrivacyMetaPanel` — 사람이 직접 판정을 입력하는 축, [04 §SC-005](04-screens-ia.md))은 이 폐지와 **무관**하며 그대로 유지된다.

## 5.5 영상 상태 (LS_RAW_DATA_STATUS)

- `LS_RAW_DATA_STATUS.DATA_STTS_CD` — 작업(검수 워크플로우) 진행 상태
- 배치 진행 시 `PROCESSING`, 배치 완료 시 `ASSIGNED` 복귀(라벨링/검수 진행 가능), 검수 제출 `PENDING`, 검수 시작 `IN_REVIEW`, 검수 승인 `APPROVED`, 실패 `FAILED`
- `COMPLETED` 는 작업 종결 상태로 검수 승인 흐름에서만 도달(배치 완료가 점프시키지 않음). 배치 단계 종료는 `LS_DATA_RAW.DATA_STTS_CD=COMPLETED` 로 별도 표기
- **`LS_DATA_RAW.DATA_STTS_CD`(배치 단계)** 흐름: 적재 `PENDING` → 선두 비식별 성공 `MARKING_READY`(마킹 진입 허용) → 배치 완료 `COMPLETED`. `LS_RAW_DATA_STATUS`(작업/검수 상태)와 책임 분리 → [07](07-batch-pipeline.md)
- `APPROVED` 전이 시 버전 스냅샷 + 관제 `TASK_COMPLETED` 통지
- 영상 등록/상태 분리: `LS_RAW_DATA_ENROLLMENT`(등록) + `LS_RAW_DATA_STATUS`(상태)

## 5.5.1 영상 목록에서 마킹 진입 · 작업자 배정 (REVIEWER 동선)

- **영상 목록(SC-007 `/video/completed`)에서 곧바로 마킹 진입** — 구 정책('배정은 작업 배정 화면 `/task/assign`에서만' → 이후 '행 인라인 배정')에서 재개편. 미배정 영상의 행 액션을 **"마킹" 버튼으로 통합**해, 자동(프레임 간격)/수동(작업자 배치)을 한 팝업에서 선택한다. 마킹 전 배정 정책은 유지(마킹 주체·시점 변경 없음) → [06](06-marking.md) · [12](12-review-assignment.md)
- **"마킹" 버튼 노출 조건** — REVIEWER + **마킹 가능(`canMark`) 영상**에만 노출: `dataSttsCd==='MARKING_READY'` 이고 비식별이 미완료가 아닐 것(`isMarkingBlocked` 재사용 — deidentStatus IN_PROGRESS/FAILED·deIdntfYn 'N'/'F' 이면 차단). `PROCESSING/COMPLETED/FAILED/PENDING` 및 비식별 미완 영상은 미노출. `WORKER`에겐 행/일괄 액션·액션 바 모두 미노출(FE `isReviewer` 게이팅, 실제 인가는 BE `@PreAuthorize` 1차 — CWE-285 심층방어). 상태 판정은 순수 헬퍼(`features/marking/markingEligibility.ts` `canMark`/`isMarkingDone`)로 단일화
- **"마킹" 클릭 → 자동/수동 선택 팝업**(`MarkingModal`):
  - **자동** — 프레임 간격(`intervalFrames`, 정수 ≥1 FE 1차 검증) 입력 → 즉시 자동마킹 트리거(`POST /v1/videos/{rawSn}/markings`, `mode=AUTO`, **작업자 배치 없이**). 성공 시 영상 목록 무효화, 실패 시 BE 메시지 토스트. 이벤트명은 요청에 미포함(서버가 `EVNT_TYPE_CD` 자동 소싱 — API-047)
  - **수동** — 기존 작업자 선택 모달(`AssignModal`, `mode='assign'`)로 흐름 전환 → 기존 배정 API(`POST /v1/assignments`) 호출. 신규 BE 없음. 코드: `features/marking/components/MarkingModal.tsx`, `features/marking/hooks/useMarkings.ts`(재사용), `features/task/components/AssignModal.tsx`(재사용)
- **재배정은 별도 유지**(무회귀): 이미 배정된 영상(`workerId!=null`)은 **"재배정" 버튼**으로 전환되고 클릭 시 **현재 배정자가 사전선택**된 재배정 모달(동일자 저장 비활성). 검수 완료(`assignStatus='COMPLETED'`) 영상은 재배정·마킹 버튼 모두 미노출(BE 완료 재배정 차단과 정합). 마킹 성공/배정·재배정 성공 시 `VIDEO_KEYS.all`·`ASSIGNMENT_KEYS.all` 무효화로 행 즉시 갱신. 일괄 배정 바(다중 선택)는 유지
- **배정 시나리오는 작업 목록(SC-012)과 정합**: 목록에 **배정자 컬럼** 표시(미배정/배정자명)
- **배정정보는 `GET /v1/videos` 응답에 포함**: `VideoSummaryResponse`에 `assignmentId·workerId·workerName·assignedAt·assignStatus`(미배정 null). 산출 기준은 작업 목록(`TaskBoardService`)의 **현재 활성 LABELER 배정 1건**과 동일(재배정 시 최신 배정자, 배치 IN 조회로 N+1 회피). 코드: `video/dto/VideoSummaryResponse.java`, `video/service/VideoQueryService.java`

## 5.5.2 영상 기술메타(`video.*`) 소스 — 관제 인입값 우선, 없는 키만 ffprobe

- **저장 위치·키는 불변**: `LS_DATA_META`(키-값) 에 `video.fps`·`video.codec`·`video.bit_rate`·`video.duration_ms`·`video.filesize`·`video.resolution` 6키. 소비처(`VideoFpsResolver`·`VideoDurationResolver`·`VideoDurationDbReader`·`DatasetMetaSourceRepository`·`DatasetVideoMetaSnapshotService`)와 증강 메타 복사 제외 필터(`VideoMetaService.isTechnicalKey`)도 무변경
- **소스만 교체**: 관제가 `LS_DATA_INGEST` 에 넣어 준 값을 **우선** 쓰고, 그 값이 없는 키만 ffprobe 로 채운다. 매핑은 `FPS`→`video.fps` · `VDO_CDC`→`video.codec` · `VDO_LEN_SEC`(**초**)→`video.duration_ms`(**ms**, ×1000) · `FILE_SZ`→`video.filesize` · `RESL`→`video.resolution`
- **폴백은 키 단위**다 — 인입이 3키만 채웠으면 그 3키는 인입값, 나머지 3키만 ffprobe(“하나라도 없으면 전부 ffprobe” 아님). 인입 6키가 전부 차면 NAS 접근·ffprobe 실행 자체를 건너뛴다
- **파생영상(증강·해상도)은 인입 행이 없다**(저작도구가 만든다) → 종전대로 **전량 ffprobe**(자기 비식별 사본 측정)
- ⚠ **`video.bit_rate` 만 ffprobe 전용**: 인입 `BIT` 은 **색심도 표기**(`24bit`)이지 비트레이트가 아니다. 소비처가 `LS_DATASET_VIDEO_META.BIT_RT`(BIGINT)라 `'24bit'` 를 흘리면 파싱 실패로 값이 사라진다. 어노테이션 `bit` 을 색심도로 바꾸려면 `BIT_RT` 타입 확장이 선행돼야 한다(별도 과제)
- **관제 수신값은 신뢰 경계 밖**: fps 양수 유한 실수 · 길이/파일크기 양수 · 해상도 `WIDTHxHEIGHT` 형식만 채택하고, 위반한 키는 담지 않아 그 키만 ffprobe 폴백으로 넘어간다
- 코드: `video/service/VideoMetaService.java`(병합·검증), `batch/runner/AsyncVideoMetaRunner.java`(probe 생략 판정)

## 5.5.3 영상 처리 현황 목록 검색·필터 (`GET /v1/videos`)

영상 처리 현황(SC-007 `/video/completed`)의 검색·필터는 **전부 BE 조건**으로 적용된다(페이징 후 FE 필터 금지 — `totalElements`도 필터 적용 후 전체 건수). 모든 파라미터는 **선택**이며 하나도 보내지 않으면 기존과 동일한 목록·정렬(`regDt DESC`)이다.

| 파라미터 | 기준 컬럼 | 의미 |
|---------|----------|------|
| `dataSttsCd` | `LS_DATA_RAW.DATA_STTS_CD` | 배치 단계 — `PENDING`/`MARKING_READY`/`PROCESSING`/`COMPLETED`/`FAILED` (FE 드롭다운 5종과 1:1) |
| `reviewStatusCd` | `LS_RAW_DATA_STATUS.DATA_STTS_CD` | 검수 워크플로 상태(조인 필터 — 상태행 없는 영상 제외) |
| `cctvNameKeyword` | `MNG_RESOURCE_CCTV.CCTV_NM`(LEFT JOIN) **또는** `LS_DATA_RAW.RAW_SN` | 최대 100자. CCTV 명 부분일치(대소문자 무시) OR **숫자 입력 시 영상 ID 일치**. LIKE 메타문자(`%`·`_`)는 이스케이프되어 리터럴 취급 |
| `eventTypeCd` | `LS_DATA_RAW.EVNT_TYPE_CD` | 값은 **이벤트유형코드**(V168 — 축은 유형, 구 카테고리 키 방식 폐기). 다만 드롭다운 옵션은 **표시명 그룹**으로 접혀 있으므로(2026-08-05, [18 §18.4.1](18-database.md)) 필터는 단일 코드 동등비교가 아니라 `EventTypeService.codesForFilterKey`로 **그룹 전체 EV-코드 집합**을 펼쳐 `IN` 비교한다 — 대표코드·비대표코드(그룹 도입 이전 북마크) 어느 쪽으로 와도 같은 그룹이 매칭된다. **미등록 키·관제 미등록 EV-코드는 오류가 아니라 0건**(fail-safe) |
| `from` / `to` | `LS_DATA_RAW.SHT_DT` | `yyyy-MM-dd`. **경계 포함**(from 당일 00:00:00 ~ to 당일 23:59:59.999999999). 촬영일시가 없는 영상은 잡히지 않는다 |

- **400 응답**: 검색어 100자 초과 / 날짜 형식 오류 / `from > to`. (셋 다 이번에 신설된 파라미터라 하위호환 파손 없음 — 미지정 정렬 키의 lenient 폴백 정책은 종전대로 유지)
- **표시 축 정합**: `VideoSummaryResponse.capturedAt` 은 **`SHT_DT`(촬영/녹화 시각)** 이다. 구현 초기 `REG_DT`(수신 시각)를 싣고 있어 화면 컬럼('녹화일')·정렬 키(`capturedAt→shtDt`)·기간 필터와 3갈래로 갈렸던 드리프트를 정정했다. **수신 시각으로 폴백하지 않으며**(BE·FE 양쪽) 촬영 시각이 없으면 `null` → 화면 `-`. 수신 시각은 별도 필드 `regDt` 로 계속 나간다.
- **파생영상 제외 불변**: 검색·필터 어떤 조합에서도 `ORGNL_RAW_SN IS NOT NULL`(증강·해상도 파생) 영상은 노출되지 않는다(조건은 통합 쿼리 `VideoRepository.searchOriginals` 한 곳에만 존재).
- 코드: `video/controller/VideoController.java`, `video/dto/VideoListFilter.java`, `video/service/VideoQueryService.java`, `video/repository/VideoRepository.java#searchOriginals`, FE `features/video/components/VideoFilters.tsx`

## 5.6 관련 데이터 (DB)

`LS_DATA_RAW`(영상 메타·VMS_CLIP_ID·EVNT_TYPE_CD·DE_IDENT_YN·ORGNL_RAW_SN), `LS_DATA_RAW_HSTRY`(상태 이력), `LS_DATA_SRC`(추출 프레임·원본/비식별 경로), `LS_RAW_DATA_STATUS`/`LS_RAW_DATA_ENROLLMENT`. 관제 소유 `MNG_CLIP_MASTER`/`MNG_RESOURCE_CCTV` 참조. → [18](18-database.md).
