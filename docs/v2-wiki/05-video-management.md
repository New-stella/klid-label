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
  - `POST /v1/uploads` — `Upload-Length`(TUS 헤더) + **인입 메타 JSON 바디**(`application/json`, 관제 수신 30컬럼 재현) → `@Valid` 검증 → `LS_TUS_UPLOAD` 행+임시파일 생성 → **`LS_DATA_INGEST` 인입 행(`PRCS_STTS_CD='PENDING'`) INSERT** → 201 + `Location: /v1/uploads/{uploadId}` + `X-Ingest-Status`
    - ★ **메타를 `Upload-Metadata` 헤더로 받지 않는다**(표준 이탈, 의도적) — 관제일지(`MNTR_CN VARCHAR(4000)`) 하나만으로도 헤더 상한(1KB)을 넘긴다. `creation-with-upload`(POST 바디에 첫 청크를 싣는 확장)를 구현하지 않아 충돌이 없으며, 그래서 `Tus-Extension` 에 그 확장을 **광고하지 않는다**(광고하면 표준 클라이언트가 바디에 바이너리를 실어 계약이 깨진다). **포털 업로드(`/v1/portal/uploads/tus`)는 별도 컨트롤러라 헤더 방식 그대로다**
    - ★ **인입 행이 파일보다 먼저 생긴다** — 인입 테이블이 "행 먼저, 파일 나중"을 이미 견디기 때문이다(미도착은 실패가 아니라 대기 → `PENDING` 복귀 + backoff, 상한 초과 시에만 종결). 덕분에 2단계 확정 API·새 세션 상태 없이 29컬럼을 그대로 실을 수 있다
  - `HEAD /v1/uploads/{id}` — `Upload-Offset`/`Upload-Length` 응답(재개), 만료 410
  - `PATCH /v1/uploads/{id}` (`application/offset+octet-stream`) — 청크 append → 새 `Upload-Offset`. 완료(offset==length) 시 매직바이트 + 재생 가능성(ffprobe) 검증 → 파일을 인입 영역(`{raw-path}/data/upload/v2/{vmsClipId}.{ext}`, = 인입 행이 이미 가리키는 경로)으로 이동 → **`NXTM_RTRY_DT` 를 지금으로 당겨 backoff 해제**. **추가 INSERT 는 없다**
    - ★ backoff 해제가 필수인 이유: 미도착 backoff 는 "지금까지 기다린 만큼 더"(1분~1시간)라, 20분짜리 업로드는 **파일이 도착한 뒤에도 최대 20분을 더 기다린다**. 도착 사실을 아는 주체는 완료 처리뿐이다
    - ★ **ffprobe 는 완료당 정확히 1회** 돌고, 그 측정값을 **① 재생 가능성 게이트 ② 인입 기술메타 back-fill**(아래 「기술메타 12종」)이 **공유**한다 — 호출 횟수를 늘리지 않는다(`TusUploadService.verifyPlayable`). ⚠ **구 서술 "값을 쓰기 위한 호출이 아니다 — 검증으로만 돈다"는 폐기**(2026-08-06)
      - **게이트 축은 길이 하나다** — 측정 실패(예외·전량 미상)와 길이 범위 이탈만 409 로 떨어뜨린다. 해상도·코덱·프레임수 등 **부가 필드가 미상인 것은 게이트를 건드리지 않는다**(컨테이너가 신고하지 않는 값이 있다고 정상 영상의 업로드를 실패시키면 안 된다)
      - ★ probe 대상은 **우리가 UUID 로 만들어 소유한 임시 파일**이다 — 인입 영역으로 옮긴 뒤의 경로를 다시 probe 하면 공유 마운트에서 최종 컴포넌트를 심링크로 바꿔치기할 창이 열린다(CWE-59/367)
      - stdout 은 **임시파일로 리다이렉트**하고 stderr 는 `Redirect.DISCARD` 한다 — 구 구조(stderr 파이프 미배수 + `waitFor` 이전 `readLine`)는 자식이 64KiB 파이프 버퍼를 채우면 **타임아웃과 `destroyForcibly` 가 영원히 도달하지 못하는 교착**이었다(실증됨). ⚠ **`redirectErrorStream(true)` 로 고치지 말 것** — 교착이 **OOM 으로 옮겨갈 뿐**이다
      - 출력이 상한(**64KiB / 200라인 / 라인당 512자**)에 걸려 절단되면 **부분 채택하지 않고 전량 미상**으로 접는다(`exit≠0` 과 동일한 fail-closed) — 절단은 값 중간을 잘라 **형식상 정상인 틀린 값**을 만들어 하류 검증을 전부 통과시킨다
      - ⚠ **인지·수용된 잔여 위험**: 임시파일 전환으로 파이프 역압이 사라져 **디스크 기록량은 상한되지 않는다**(실질 상한 = 타임아웃 × 자식 쓰기 속도)
  - `DELETE /v1/uploads/{id}` — 세션 취소 + 임시파일 삭제 + **인입 행 `FAILED` 종결**(사유 `업로드 취소…`). 관제 인입과 달리 취소는 오류가 아니라 일상적 동선이라, 종결하지 않으면 파일이 영영 오지 않는 행이 미도착 대기 상한(24h) 동안 재시도하다 쌓인다. **이미 완료된 세션은 인입 행을 건드리지 않는다**(적재 대기 중인 정상분 보호)
    - ⚠ 인입 행은 **영구 보존**(삭제 금지)이라 취소분도 `UK(VMS_CLIP_ID)` 를 계속 점유한다. 그래서 같은 클립 ID 재업로드는 그 행을 **지우지 않고 되살린다**(같은 PK 를 새 메타로 UPDATE — `InternalUploadIngestWriter.reviveForUpload`). 되살리기 **4조건**(모두 만족, fail-closed): ①경로가 인입 영역 하위(= 우리가 만든 행. 관제 행은 관제 NAS 경로라 절대 매칭 안 됨) ②`FAILED` + `RAW_SN IS NULL` ③파일 미도착 ④**그 클립 ID 로 진행 중(IN_PROGRESS) 세션이 없음**
      - ★ ④가 필요한 이유(2026-08-03) — 세션 종결 경로는 모두 세션·인입 행을 함께 종결하는데 **미도착 대기 상한 종결만은 인입 행만 `FAILED` 로 내리고 세션을 모른다**(그 잡은 세션 테이블을 보지 않는다). 상한이 세션 TTL(24h)보다 짧은 형상이면 **세션이 살아 있는 채 행이 종결**되고, 그것을 되살리면 같은 클립 ID 의 세션이 둘 살아나 완료 순서에 따라 "뒤 세션 메타 + 앞 세션 파일" 로 뒤섞인다. 파일 대체 자체는 원자 예약이 막지만(아래) 상태를 애초에 만들지 않는다
      - ⚠ **미해소 — 관제 clipId 선점**: 되살리기는 우리 세션 생성 경로에서만 발동하므로, REVIEWER 가 "관제가 앞으로 쓸 clipId" 로 세션을 만들었다 취소하면 그 `FAILED` 행이 UK 를 계속 점유해 **관제 INSERT 가 UK 위반으로 실패**한다(관제엔 관측 수단 없음). 내부 권한 보유자로 한정된 잔여 위험이며, 해소하려면 clipId 네임스페이스 분리 또는 관제 측 실패 관측 통로가 필요하다(관제 계약 변경 — 별도 트랙)
      - ⚠ **미해소 — 서로 다른 clipId 반복**: 되살리기는 *같은* clipId 반복만 1행으로 눌러 준다. 서로 다른 clipId 로 세션 생성·취소를 반복하면 인입 행은 여전히 누적 증식한다(동시 세션 상한 3 은 **동시** 수만 제한)
- **적재는 인입 경로가 담당한다 (적재 주체 반전 정합)** — 업로드는 `LS_DATA_RAW` 를 직접 만들지도 `VideoIngestedEvent` 를 발행하지도 않는다. 관제가 INSERT 한 인입 행과 **똑같이** 폴링 배치(`ControlTrainingVideoScanJob` → `TrainingVideoIngestTx`)가 픽업해 적재하고, 비식별 선두 트리거도 그 배치가 발행한다. 우회하면 적재 규칙(경로 allowlist·중복 판정·상태 전이·기술메타 back-fill)이 업로드에만 적용되지 않는 두 번째 진실원이 된다. → [07](07-batch-pipeline.md)
  - `LS_DATA_INGEST` 에 대한 **비-관제 INSERT 통로는 `InternalUploadIngestWriter` 하나**다(고정 컬럼 + 플레이스홀더만, 저작도구 운영 8컬럼은 SQL 에 없음). 엔티티 `LsDataIngest` 에는 INSERT 팩토리·setter 를 두지 않는다
  - `LS_TUS_UPLOAD.FILE_PATH` 는 **임시 경로 그대로** 둔다 — NAS 경로로 갱신하면 완료 전이가 유실된 세션을 24h 뒤 `TusUploadCleanupJob` 이 스윕할 때 인입 완료된 원본을 지운다(정리 가드가 `raw-path` 하위만 보므로 새 경로도 통과)
  - **입력 항목 = 관제 수신 컬럼 전량**(필수 5: `VMS_CLIP_ID`·`VMS_CCTV_ID`·`VDO_FILE_NM`·`RAW_FILE_PATH_NM`·`SRC_TYPE`. 뒤 둘은 서버가 저장 규약으로 정한다). `SRC_TYPE` 은 폼에서 선택 가능하되 적재와 **같은 allowlist**(`LsDataIngest.ALLOWED_SRC_TYPES`)로 판정한다(어긋나면 적재 시 조용히 null 이 된다)
    - ★ **`OG_CD`(기관코드) 입력은 제거됐다 (2026-08-12 관제 확정, V185)** — 관제 회신 *"현행 미사용 값이라 공급 불가"*. 수신 컬럼 자체를 없앴으므로 사용자가 채워도 실릴 곳이 없다. ⚠ 학습데이터 export JSON 의 `og_cd` 필드는 **원래부터 값을 싣지 않는 null 고정**이라 그대로 둔다(관제와 합의된 산출 포맷 — 함께 지우지 말 것)
    - ★ **`VMS_CCTV_ID` 는 DB 에서 NULL 을 허용한다 (2026-08-12 관제 확정, V185)** — 관제 회신 *"CCTV 식별자가 없는 영상(수동 업로드 등)이 존재"*. 그런 영상은 관제가 `CCTV_NM` 에 대체 표기(수동 업로드 파일명 등)를 채워 보낸다. **`LS_DATA_INGEST`·`LS_DATA_RAW` 양쪽**을 함께 풀었다 — 인입만 풀면 적재가 NOT NULL 위반으로 터진다. 다만 **dev 업로드 입력면은 계속 값을 요구**한다(사람이 입력하는 화면에서 식별자를 비울 이유가 없다)
  - **★`evntTypeCd`(이벤트유형코드) 선택 입력 — 2026-08-09 신설**: 관제가 인입 평면값(`LS_DATA_INGEST.EVNT_TYPE_CD VARCHAR(20)`, V166)으로 싣는 값이며, 적재(`TrainingVideoIngestTx.resolveEvntTypeCd`)가 이것을 **단독 조달원**으로 `LS_DATA_RAW.EVNT_TYPE_CD` 에 복사한다
    - **왜 열었나** — 이 값이 비면 그 영상은 비식별까지만 가고 **마킹 진입에서 400**(`MarkingGuards.requirePreconditions` — "이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다")으로 멈춘다. 관제가 아직 이 값을 보내지 않는 형상이라, 업로드에 입력이 없으면 **dev 업로드만으로는 파이프라인을 한 건도 완주시킬 수 없었다**(2026-08-09 실측: 업로드→적재→비식별 정상, 마킹 400)
    - ★ **검증이벤트유형(`VRFC_EVNT_TYPE_CD`)과 축이 다른 값**이다 — 이쪽은 관제 코드 체계의 유형 식별자이고 저쪽은 외부 VLM 검증 API 의 `event_type` 이다. 서로 유도·대체하지 않는다(아래 항 참조)
    - **값 목록으로 좁히지 않는다** — 관제 코드 체계는 우리 소유가 아니고 미등록·비규격 코드(예 `INTRUSION`)도 실제로 들어오며, 적재가 처음 보는 코드를 이벤트유형 마스터에 자동 등록한다(`eventTypeAutoRegistrar`). 사본 allowlist 를 들면 관제가 코드를 넓힐 때 **정상 값을 우리가 먼저 막는다**(검증이벤트유형에서 이미 폐기한 실패 방식)
    - **판정은 형식뿐** — 대문자·숫자·`_` + **20자**(컬럼 폭 `VARCHAR(20)`. 입구에서 400 을 주지 않으면 INSERT 시점 DB 오류 500 이 된다). 문자 집합을 제한하는 이유는 이 값이 조회 파라미터·로그에 그대로 실리기 때문이다(CWE-117)
    - **미지정이 기본이고 필수가 아니다** — 관제 미송신 상태를 그대로 재현할 수 있어야 한다. 빈 값은 **키 부재**로 보내고(폼 관례), 서버는 `evntTypeCdOrNull()` 로 `null` 적재한다(빈 문자열이 들어가면 마킹 가드의 `isBlank` 판정과 화면 표시가 갈린다)
    - **대소문자를 바꾸지 않고 그대로 싣는다** — 검증이벤트유형(벤더 enum 이라 소문자 정규화)과 달리 이 값의 표기는 우리가 정하지 않는다. 다만 **화면이 전송 직전 대문자로 올린다**(소문자 입력은 표기 실수이지 다른 값이 아니다)
    - **화면 입력** — dev 업로드 폼 '이벤트 · 관제일지' 항목의 「이벤트유형코드」 텍스트 입력(`TusMetaFieldsets.EventFieldset`). 프리셋 select 로 좁히지 않는 이유는 위 "값 목록으로 좁히지 않는다"와 같다
    - ⚠ **`EVNT_ID`(이벤트 아이디, 예 `ABA_0001`)와도 다른 값**이다 — 식별자형이며 유형코드가 아니다(DTO 주석·화면 힌트가 모두 명시)
  - **`vrfcEvntTypeCd`(검증이벤트유형) 선택 입력 — 2026-08-06 신설(V176 `LS_DATA_INGEST.VRFC_EVNT_TYPE_CD VARCHAR(20)`)**: 외부 VLM 검증 API 요청의 `event_type` 이며 허용값은 **enum 6종**(`fire`·`fall`·`violence`·`flooding`·`car_accident`·`kidnapping`). 판정 목록·정규화의 단일 진실원은 `LsDataIngest.VRFC_EVNT_TYPES` / `LsDataIngest.normalizeVrfcEvntType` 이고 DTO `@AssertTrue` 와 서비스 2차 방어선이 **같은 집합**을 본다(리터럴 복제 금지 — `srcType` 실사고와 동형)
    - ★ **`EVNT_TYPE_CD`(이벤트유형코드, 예 `EV01000101`)와 축이 다른 값**이다 — 대체·통합하거나 한쪽에서 유도하지 않는다. 저작도구가 관제 코드 → 벤더 enum 번역표를 들면 관제 코드 체계 변경마다 조용히 낡아 **잘못 번역된 값으로 외부 위탁**이 나가므로, 값 자체를 관제 인입으로 받는다(사용자 확정)
    - **정규화는 `trim` + 소문자**(`Locale.ROOT` 고정 — 터키어 로케일에서 `FIRE`→`fıre` 방지) 후 **정확 매치**. 밖이면 세션 생성 단에서 **400** 이고 메시지에는 **허용 목록만** 담는다(입력 원문 미포함 — CWE-117/209). **공백만 있는 값은 400 이 아니라 미지정(`null`)** 이다(`srcType`·`fileFmt` 와 같은 "빈 값 = 미지정" 계약)
    - ★ **DB CHECK 제약을 두지 않는다** — 이 테이블에 INSERT 하는 주체는 관제이고 우리 코드를 거치지 않으므로, CHECK 를 걸면 우리가 모르는 값 하나에 **관제 인입 INSERT 자체가 실패**해 영상 수신이 멈춘다. 검증은 ①우리 쓰기 통로(400) ②소비 시점 fail-closed 재검증 2단이다
    - **조달 경로**: `rawSn` → `IngestSourceRepository.findSourceMeta` → `IngestSourceRow.getVrfcEvntTypeCd()`(조인 규칙은 `IngestSourceLink` 재사용). **파생영상은 부모 인입값을 그대로 물려받는다** — 폴백 예외는 개인정보 3필드뿐이며, 이 값은 개인정보 *판정*이 아니라 **분석 대상 지정**이라 그 예외의 근거가 성립하지 않는다
    - **기존 행 소급 백필 없음** — 관제 송신분·신규 업로드분부터 채워지고 그 전에는 `null` 이 정상이다(유추해 채우면 그것이 곧 자체 매핑표다). ⚠ **VLM 호출부에서 이 값을 실제로 사용하는 것은 후속 단계**이며 이번 변경 범위가 아니다 → [09](09-vlm-timeseries.md)
    - **화면 입력 — dev 업로드 패널의 「검증이벤트유형」 select (2026-08-06 신설)**: `/dev/autolabel-test`(SC-027) 의 TUS 업로드 폼 '이벤트 · 관제일지' 항목에 있다. 관제 반영 전까지 verify 연동을 실제로 돌려보려면 값을 직접 넣을 수단이 필요해 연 입력이다. 옵션은 **미지정(기본) + enum 6종**이고 라벨은 한글 병기(`화재 (fire)`)이되 **전송값은 벤더 규격 소문자 원문**이다(라벨을 보내면 벤더가 거부한다). FE 목록의 단일 진실원은 `tusUploadForm.ts(VRFC_EVNT_TYPES)` 이며 select 가 그것을 map 한다
      - **미지정이 기본이고 필수가 아니다** — 값 없는 업로드는 오류가 아니라 "event_type 미수신 → 위탁 SKIPPED" 경로를 그대로 밟는 정상 동선이다. 미지정이면 세션 생성 바디에 **키 자체를 보내지 않는다**(폼의 다른 선택 필드와 같은 "빈 값 = 키 부재" 관례). BE record 에 `@JsonProperty` 가 없어 **자바 필드명이 곧 JSON 키**이므로 키 이름은 `vrfcEvntTypeCd` 다
      - ★ **select 는 UX 보조일 뿐 신뢰 경계가 아니다** — 값 범위를 좁혀 오입력을 줄일 뿐이고, 최종 판정은 서버(`InternalUploadCreateRequest.isVrfcEvntTypeAllowed` → 400)가 한다. FE 에 별도 검증 로직을 두지 않는다(두면 BE allowlist 와 갈라지는 두 번째 목록이 된다)
  - **기술메타 12종은 선택 입력** — 채운 키는 그 값이 인입 행에 실린다. 서버가 이미 아는 `FILE_SZ`(=`Upload-Length`)·`FILE_FMT`(=확장자)는 **종전대로 세션 생성 시점**에 채워진다(back-fill 대상 아님). **비운 키의 처리는 축이 둘이고 서로 다른 테이블**이다
    - ⚠ **구 서술 폐기 (2026-08-06)** — 구 문서는 "비운 키만 **적재 후** ffprobe 가 채운다(`VideoMetaService` 규칙 재사용)" + "나머지는 **추측해 채우지 않는다**" 라고 적었는데 **둘 다 사실이 아니었다**. `VideoMetaService` 는 `LS_DATA_META` 의 `video.*` **6키**를 채울 뿐 **인입 원장 `LS_DATA_INGEST` 의 컬럼은 채우지 않는다** — 그래서 비운 기술메타 컬럼은 **영구 NULL** 로 남아 있었다. 그리고 이제는 **완료 시점에 측정해 채운다**
    - **① 인입 원장 축 (신설)** — 업로드 **완료 시점**에 ffprobe 측정값으로 `LS_DATA_INGEST` 의 **비어 있는** 기술메타 컬럼만 채운다(`TusUploadService.backfillIngestMeta` → `InternalUploadIngestWriter.backfillMeasuredMeta`)
      - **채우는 8컬럼**: `VDO_LEN_SEC`·`FPS`·`VDO_CDC`·`WDTH`·`VRTC`·`RESL`(측정) + `FRME_CNT`(ffprobe `nb_frames` 우선, 없으면 `길이 × fps` 파생) + `ASPRT_RT`(`display_aspect_ratio` 우선, 없으면 너비:높이 **최대공약수 축약**). 해석·파생·검증(DDL 길이·`NUMERIC(10)` 자릿수 포함)의 판정 단일 원천은 `InternalUploadMetaResolver` 이며 복제 금지 — 검증 실패는 예외가 아니라 **그 컬럼만 미채택**이다
      - ★ **`PXL`(화소)·`BIT`(색심도)는 채우지 않는다** — 등급·표기 규약이 정의돼 있지 않아 무엇을 넣든 지어낸 값이 되기 때문이다(프로젝트 "값을 지어내지 않는다" 원칙). `ResolvedIngestMeta` 에 **필드 자체가 없어** 구조로 강제되고, 가드 테스트가 back-fill SET 절의 부재를 단언한다
      - ★ **사용자 입력값은 절대 덮어쓰지 않는다** — `COALESCE` 조건부 UPDATE 로 **단일 문장 안에서** 원자 판정한다(앱이 먼저 조회해 "비었는가"를 판단하면 read-then-write 라 그 사이 들어온 값을 덮는다 — CWE-362). VARCHAR 4종(`FPS`·`VDO_CDC`·`RESL`·`ASPRT_RT`)은 `NULLIF(BTRIM(col), '')` 로 **공백문자열도 미입력** 취급한다(폼이 빈 문자열을 보내면 세션 생성 INSERT 가 그것을 그대로 실었기 때문). NUMERIC 4종에 `BTRIM` 을 쓰지 않는 이유는 PostgreSQL 이 `function btrim(numeric) does not exist` 로 **파싱 자체가 실패**하고, 애초에 NUMERIC 에는 빈 문자열 개념이 없기 때문이다
      - ★ **순서 — back-fill 은 반드시 파일 이동(`moveIntoIngestArea`) 이전**이다. 두 이유가 겹친다: ①**기능** — 파일이 임시 영역에 있는 동안에만 폴링이 "미도착"으로 판정해 **적재가 구조적으로 불가능**하다. 뒤로 옮기면 폴링이 먼저 `LS_DATA_RAW` 를 만들어 NULL 인 채로 복사해 간다 ②**잠금 순서(CWE-833)** — `markUploadArrived`(호출자 트랜잭션, **같은 행** UPDATE) 뒤로 옮기면 `REQUIRES_NEW` inner 가 outer 가 쥔 행 락을 기다리고 outer 는 inner 를 기다려 **교착**한다. 배선 순서(back-fill → 파일 이동 → 완료 전이 → 도착 통지)가 두 요구를 함께 만족시키며 순서 회귀 가드가 고정한다
      - **트랜잭션은 `REQUIRES_NEW` 로 격리**한다 — 같은 트랜잭션이면 PostgreSQL 이 문 실패 시 **트랜잭션 전체를 abort** 시켜 "best-effort 삼킴"이 성립하지 않는다(뒤따르는 `markCompletedIfInProgress`·`markUploadArrived` 가 모조리 실패해 **정상적으로 다 올라온 업로드가 500**). 그래서 흡수하는 실패는 **writer 호출 한 줄**뿐이다
      - **UPDATE 0행은 정상**이다 — ①폴링이 그 순간 `PROCESSING` 으로 클레임 중 ②미도착 상한 초과로 `FAILED` ③이미 적재됨(`RAW_SN` 존재). 셋 다 "이번엔 채우지 않는다"가 옳은 결과라 예외로 올리지 않고 skip 한다
      - ★ **신뢰 경계 (CWE-915)** — 이 UPDATE 는 **관제 소유 29컬럼 중 8개**를 SET 한다. SQL 술어(`PENDING` + `RAW_SN IS NULL`)는 **관제가 넣은 미처리 행에도 맞으므로**, 관제 행과 우리 행을 가르는 것은 오직 Java 측 경로 판정 `InternalUploadPathResolver.isUploadAreaPath`(관제 행은 관제 NAS 경로를 가리킨다) 하나다 — 되살리기(`reviveForUpload`)와 **동일 구조**다. `LsDataIngestWriteGuardTest` 가 통로 수·문별 술어·SET 절 allowlist·호출부 단일성을 **구조로 고정**한다
      - **기존 인입 행 소급 백필 없음** — 신규 업로드부터 적용된다
      - ⚠ **알려진 한계**: FE 가 미입력 숫자 필드를 `null` 이 아니라 `0` 으로 보내면 `COALESCE` 가 "입력됨"으로 보아 그 컬럼은 영구히 채워지지 않는다(`0` 이 유효값인 경우와 구분할 수단이 없어 서버가 강제하지 않는다). 되살리기는 `COALESCE` 없이 덮어쓰므로 back-fill 로 채운 8컬럼이 새 폼 입력으로 재초기화되지만, 재업로드 완료 시 back-fill 이 다시 돌아 채워진다
    - **② `LS_DATA_META` 축 (기존, 무변경)** — 적재 **후** `VideoMetaService` 가 `video.*` 6키를 "관제 인입값 우선, 없는 키만 ffprobe — 폴백은 키 단위" 규칙으로 채운다(§5.5.2). **이번 변경으로 인입이 먼저 채워지므로 그 값이 자연히 우선 채택**되며, **같은 ffprobe 원천이라 두 축의 값이 갈리지 않는다**
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
- **내부 업로드분은 인입 행이 이미 채워져 온다** (2026-08-06) — 업로드 완료 시점 back-fill(§5.2)이 인입 8컬럼을 채우므로 여기서는 그 값이 우선 채택되고 ffprobe 폴백이 대개 불필요해진다. **이 절의 규칙 자체는 무변경**이며(소스 우선순위·키 단위 폴백 동일), 두 축이 **같은 ffprobe 원천**을 쓰므로 값이 갈리지 않는다
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

- **기간 입력은 공용 기간 선택 컨트롤**(2026-08-08) — 화면이 네이티브 날짜 입력 두 벌을 직접 재구현하던 것을 걷어내고 공용 컴포넌트로 배선했다. **구성(시작일·종료일 두 칸 + 상호 min/max 제약)과 상위로 올리는 값 형식(`yyyy-MM-dd`)·URL 직렬화는 종전과 동일**하므로 기존 북마크가 그대로 살아 있고 BE 계약도 무변경이다. 두 입력이 접근성 그룹(`role="group"`)으로 묶이며, 상호 제약(시작일 상한=종료일 / 종료일 하한=시작일)의 소유자가 컴포넌트로 옮겨졌다. ⚠ **날짜와 시각을 함께 받는 입력은 대상이 아니다** — 날짜만 받는 컨트롤로 바꾸면 시·분이 잘려 값이 사라진다.
- **400 응답**: 검색어 100자 초과 / 날짜 형식 오류 / `from > to`. (셋 다 이번에 신설된 파라미터라 하위호환 파손 없음 — 미지정 정렬 키의 lenient 폴백 정책은 종전대로 유지)
- **★영상명 표시 폴백 3단 (2026-08-12 관제 확정, V185)** — 목록·상세가 그리는 영상명은 **CCTV명 → `VMS_CCTV_ID` → `영상 #{rawSn}`** 순이다. 관제에 **CCTV 식별자가 없는 영상**(수동 업로드 등)이 존재하게 되어 기존 2단 폴백만으로는 **둘 다 없는 영상이 빈칸**으로 떠 목록에서 행을 구분할 수 없었다. 판정 단일 원천은 `video/dto/CctvDisplayNamePolicy`(화면들이 각자 삼항식을 들면 같은 영상이 화면마다 다른 이름이 된다). **★적용 범위는 영상 목록·상세에 그치지 않는다 (2026-08-13)** — **작업목록(REVIEWER `GET /v1/tasks/board`)·배정목록(WORKER `GET /v1/assignments`)·검수목록·증강 검수 잡카드·증강 결과 조회**가 모두 같은 판정기를 쓴다. 그 전까지 앞 둘은 `null` 을 그대로 내려 **행 전체가 빈칸**이었고, 검수목록은 `video #N`, 증강 2화면은 `(이름 없음)`(이름 없는 영상이 여럿이면 **모든 행이 같은 문구**가 되어 식별 불가)으로 표기가 셋으로 갈려 있었다. ⚠ **응답 필드는 줄이지 않았다** — 배정목록의 `videoTitle` 은 우리 테스트베드 FE 가 읽지 않지만 실제 화면은 외부 팀이 별도 개발하므로 **필드를 남기고 값만** `cctvName` 과 같은 표기로 통일했다. ⚠ **3순위는 검색 축이 아니다** — 숫자 입력이 이미 `keywordRawSn` 동등비교로 그 영상에 도달한다.
- **표시 축 정합**: `VideoSummaryResponse.capturedAt` 은 **`SHT_DT`(촬영/녹화 시각)** 이다. 구현 초기 `REG_DT`(수신 시각)를 싣고 있어 화면 컬럼('녹화일')·정렬 키(`capturedAt→shtDt`)·기간 필터와 3갈래로 갈렸던 드리프트를 정정했다. **수신 시각으로 폴백하지 않으며**(BE·FE 양쪽) 촬영 시각이 없으면 `null` → 화면 `-`. 수신 시각은 별도 필드 `regDt` 로 계속 나간다.
- **파생영상 제외 불변**: 검색·필터 어떤 조합에서도 `ORGNL_RAW_SN IS NOT NULL`(증강·해상도 파생) 영상은 노출되지 않는다(조건은 통합 쿼리 `VideoRepository.searchOriginals` 한 곳에만 존재).
- 코드: `video/controller/VideoController.java`, `video/dto/VideoListFilter.java`, `video/service/VideoQueryService.java`, `video/repository/VideoRepository.java#searchOriginals`, FE `features/video/components/VideoFilters.tsx`

## 5.5.4 배치 실패 복구 — 영상 상세 사유·조치 + 목록 일괄 재시작 (R1, 2026-08-12)

**전용 「배치 실패 관리」 화면은 신설하지 않는다.** 영상 목록(SC-007)에 이미 배치 단계 `실패` 필터(§5.5.3 `dataSttsCd`)가 있어 같은 일을 하는 **두 번째 목록**을 만들지 않는다. 조치는 실패를 이미 보고 있는 자리에 붙였다 — 파이프라인 쪽 규칙·근거는 [07 §7.5-1](07-batch-pipeline.md).

### 영상 상세(SC-009) — 「배치 실패 사유 + 조치」

- **응답 신설 2필드** — `GET /v1/videos/{rawSn}` 에 `batchFailureReason`(사용자 문구, 실패가 아니면 `null`) · `skippedStages`(검수자가 건너뛴 단계 목록, 없으면 **빈 배열**). 기존 `from(...)` 오버로드가 각각 `null`·빈 배열로 위임하므로 **추가만**이고 기존 필드명·타입·순서는 불변이다.
- **사유는 화면이 재해석하지 않는다** — 서버가 준 문구를 그대로 보여준다. 내부 원문(예외 클래스명·SQL·DB 제약명)은 응답에 담기지 않는다(CWE-209).
- **단계를 특정할 수 없는 실패**는 `stages` 가 빈 배열이라 단계 자리에 "확인 불가"를 알리고 **사유는 그대로 보여준다**. 이 분기가 없으면 그 영상에서는 화면이 아무것도 보여주지 못한다.
- ★**노출 조건은 「실패했거나 건너뛴 단계가 하나라도 있으면」이다**(2026-08-12) — 실패했을 때만 노출하면 **건너뛴 뒤 재기동이 성공한 영상에서 이 영역이 통째로 사라진다.** 스킵 표식은 영구라 그 단계는 이후 모든 재기동에서 조용히 건너뛰어지는데 화면에는 완료로 보이고, 되돌릴 창구가 어디에도 남지 않는다(막는 조작에는 되돌리는 길을 함께 둔다).
  - 실패가 없고 스킵만 남은 상태에서는 **보여줄 사유가 없으므로 사유 영역 자체를 만들지 않고**(없는 사유를 "없음"으로 채워 빈 자리를 만들지 않는다) 제목도 「건너뛴 단계 있음」으로 갈린다 — 상태는 색이 아니라 문구가 말한다.
  - 그 상태에서는 `배치 재실행` 버튼도 두지 않는다 — 서버는 **실패 상태만 선점**하므로 눌러도 막히는 버튼이 된다.
  - ⚠ **`건너뛰기` 버튼 조건은 넓히지 않는다** — 사양이 "실패 시 건너뛴다"이므로 그 단계가 실패했을 때만 노출한다. 넓어진 것은 **패널 노출**과 `되돌리기` 쪽이다.
- **조치 버튼**: `배치 재실행`(기존 `POST /v1/videos/{rawSn}/batch/retry`) + 시계열 · 오토라벨링 **묶음**의 `건너뛰기`/`되돌리기`. 전부 **REVIEWER 전용**이며 화면 게이팅은 UX 편의일 뿐 실제 강제는 BE(403)다.
  - ⚠ **구 서술 「VLM · AI 탐지 · AI 분할 **단계**의 건너뛰기/되돌리기」는 폐기** — 두 축이 함께 낡아 있었다. ①조작 단위는 개별 단계가 아니라 **작업 묶음** 2종이다(2026-08-12 확정 — 오토라벨은 쪼개서 부분 수행하지 않는다) ②노출명은 「시계열」이다(2026-08-13 확정). 코드값은 `VLM`·`AUTOLABEL` 로 그대로다.
- ★**재기동은 「접수」다 — 완료가 아니다**(2026-08-12). 서버는 실패 상태를 선점하는 것까지만 요청 안에서 처리하고 실제 실행은 비동기로 넘긴다. 따라서 ①안내 문구를 "재시작했다/완료됐다"로 쓰지 않고 진행은 **처리 단계 표시**로 확인시키며 ②접수 직후의 상세는 **아직 실패 그대로**라, 화면은 접수 시각부터 **짧은 유예 창** 동안 상세를 따라간 뒤 스스로 멈춘다(무효화 1회만으로는 딱 한 번 갱신되고 끝나 화면이 멈춘 것처럼 보인다). ⚠ **응답 스키마와 단계 상태값 집합(`DONE|PROGRESS|PENDING|FAIL`)은 무변경**이며 바뀐 것은 값의 **의미**와 사용자 문구뿐이다.
- ★**패널은 `BatchStageIndicator` 안이 아니라 바깥에 산다** — 그 단계 표시기는 **마킹 화면(SC-006)과 공유**하므로 조작 버튼을 표시기 안에 넣으면 마킹 화면에도 함께 나타난다.
- ★**건너뛴 단계는 단계 표시기에서 완료처럼 보인다**(진행 축에 흔적이 없으므로). "건너뜀"이라는 사실은 이 패널의 `skippedStages` 기반 표시에서만 드러난다.
- **파생영상은 사유만 보여주고 조작을 노출하지 않는다** — 파생은 배치 파이프라인을 타지 않아 재실행으로 복구되지 않는다.
- 단계 이름은 `BatchStageIndicator` 가 내보내는 **단일 매핑 함수**를 재사용한다(표를 복제하면 같은 단계가 화면마다 다른 이름으로 불린다). 문구 규칙은 종전대로 기술 모델명 비노출 — **AI 탐지 / AI 분할**.
- ★**조작 UI 의 묶음명과 단계 표시기의 접은 칸 이름은 같다 — 「오토라벨링」**(2026-08-13 확정, 구속). 표시명은 **한 곳에서만 정의**되고 두 축이 그 값을 공유한다 → §5.5.5. ⚠ **구 서술 「두 이름은 다르다 — 버튼은 `AI 탐지 · AI 분할 · 보간`(멤버 노출명을 잇는다), 캡션은 `오토라벨링`」은 폐기**: 같은 것을 가리키는 두 이름이 한 화면에 공존했고, 사양(SCREEN-009)이 이 묶음을 부르는 어휘는 「오토라벨」이다.
- ⚠ **이름이 짧아지며 「보간 포함」 고지가 이름에서 사라졌다** — 조합 이름은 이름만으로 보간이 이 묶음 안에 있음을 알렸는데, 오토라벨 재수행은 **사람이 손댄 보간 라벨을 새로 계산해 덮어쓰는** 조작이다. 그 고지는 이제 **재수행 경고 문단(누르기 전, `aria-describedby` 로 버튼이 가리킴) + 확인 창 본문**이 전담한다. 두 문구 모두 보간 재계산과 그 결과를 명시하며, **지우면 고지가 통째로 사라진다.**
- ★**시계열 묶음의 이름도 「시계열」로 통일됐다**(2026-08-13 확정, 구속) — 화면에 노출되는 기술 모델명 `VLM` 을 「시계열」로 통일하는 확정에 따라 단계 노출명과 묶음 표시명이 함께 「시계열」이 됐다. ⚠ **구 서술 「이름은 여전히 `VLM` 이다 — 확정이 오토라벨 한 건이라 바꾸지 않았다(별건 처리)」는 폐기**: 그 별건이 이 확정으로 해소됐다.
  - ⚠ **바뀐 것은 화면 표시명 한 축뿐이고 묶음 코드값은 여전히 `VLM` 이다** — 재수행·건너뛰기 요청의 경로 파라미터, 응답 `skippedStages` 의 값, 배치 단계 코드(`PROC_STEP_CD`)는 **그대로**다. 표시명이 바뀌었다고 코드값·계약까지 바꾸지 말 것(오토라벨 묶음이 「오토라벨링」으로 불리면서도 코드값이 `AUTOLABEL` 인 것과 같은 축).

### 영상 목록(SC-007) — 일괄 작업 바

- 다중 선택 시 뜨던 「일괄 배정 바」가 **「일괄 작업 바」**가 되어 `일괄 재시작` 버튼이 함께 놓인다(`POST /v1/videos/batch/retry`). 배치가 한 번 멈추면 여러 건이 함께 실패하므로 상세 화면을 건건이 여는 대신 목록에서 처리한다.
- **상한 100건** — 초과하면 보내기 전에 알리고 버튼을 막는다(서버도 400).
- ★**부분 성공을 그대로 다룬다** — 결과는 건별 성패·사유 모달로 보여주고, **실패분만 선택으로 남긴다**. 성공했다고 선택을 통째로 비우면 "이미 진행 중"으로 밀린 영상을 사용자가 목록에서 처음부터 다시 골라야 한다.
- ★**건별 결과는 「접수했는지」이지 파이프라인이 끝났다는 뜻이 아니다**(2026-08-12) — 모달 문구를 접수 축으로 쓰고 진행은 **각 영상 상세의 처리 단계**에서 확인시킨다(응답 스키마는 무변경).
- WORKER 에게는 액션 바 자체가 노출되지 않는다(기존 규칙 유지).

### 파생영상 취급 — 비대칭이 남아 있다 (미해결)

- 목록은 원본 전용이라 「실패」 필터에 파생이 나오지 않고(§5.5.3 파생영상 제외 불변), 신규 3종(스킵·해제·일괄 재시작)은 파생을 거부한다.
- ⚠ **기존 단건 재기동은 파생을 거부하지 않는다** — 계약 변경이라 이번 범위 밖이며 **미해결**이다.

## 5.5.5 배치 단계 표시기 — 오토라벨 세 단계를 **한 칸으로 접어 5칸** (UI-018 v7, 2026-08-13)

영상 상세(SC-009)·마킹 화면(SC-006)이 공유하는 처리 단계 스테퍼(`BatchStageIndicator`) 규칙이다.

- **7단계를 7칸으로 그리지 않는다** — 오토라벨에 해당하는 마지막 세 단계(AI 탐지 · AI 분할 · 보간)를 **「오토라벨링」한 칸**으로 접어 **5칸**(비식별 · 마킹 · 시계열 · 프레임추출 · 오토라벨링)으로 보여준다.
- **근거는 표시 단위를 조작 단위에 맞추는 것**이다 — 건너뛰기·되돌리기·재수행이 **오토라벨 묶음 단위로만** 동작하는데(§5.5.4) 세 칸으로 나뉘어 보이면 각 칸을 따로 조작할 수 있다고 읽힌다.
- ⚠ **접히는 것은 표시 층뿐이다** — 서버가 내려주는 단계 목록(`stages` 7단계)과 응답 계약, 묶음 멤버 구성은 **무변경**이다. 실패한 **단계**를 그 단계가 속한 **묶음**으로 해석하는 지점이 두 축의 유일한 접점이라는 규칙도 그대로다.
- **접은 칸의 상태는 세 단계를 합쳐 판정**한다 — 하나라도 실패면 **실패**, 실패가 없고 하나라도 진행 중이면 **진행 중**, **셋 다 끝났으면** 완료. 그 밖(전부 대기 · 일부만 완료)은 **대기**로 둔다 — 일부만 끝났는데 완료로 적으면 남은 단계가 끝난 것처럼 읽히므로 과대 보고가 아닌 쪽을 택했다(사양 미규정 구간의 구현 결정).
- **세부 단계는 보조 표기로 작게 병기**해 접기로 잃는 진행 해상도를 되돌린다 — 진행 중이면 지금 어느 세부 단계인지(`AI 분할 진행 중`), 실패면 어느 세부 단계에서 실패했는지(`보간에서 실패`). **완료·대기에는 두지 않는다**(적을 대상이 없다). 세부 단계가 여럿 실패·진행 중이면 **서버 배열 순서상 앞선 것**을 적는다(뒤 단계가 앞 산출물을 입력으로 받으므로 앞선 실패가 원인이고, 같은 입력에 항상 같은 문구가 나온다).
- ⚠ **보조 표기는 글자이며 백분율 진행률 바가 아니다** — `progress` 값은 계약상 함께 내려오지만 화면에 쓰지 않는다.
- **접은 칸도 캡션 규칙을 그대로 따른다** — 묶음 이름과 상태를 함께 적어(「오토라벨링 실패」) 색상만으로 상태를 구분하지 않는다. 점은 네 상태 모두 모양·크기가 같아 캡션이 색을 대신하는 유일한 구분 수단이기 때문이다. 스크린리더 안내(`aria-live="polite"`)도 **화면과 같은 5칸 축**으로 말하고 보조 표기를 함께 읽는다 — 단계 축으로 낭독하면 화면은 「오토라벨링 실패」인데 낭독은 「AI 분할 실패」가 되어 보는 것과 듣는 것이 갈린다.
- ★**접은 칸의 이름(「오토라벨링」)은 조작 버튼의 묶음명과 같다**(2026-08-13 확정, 구속) — 표시명은 **묶음 표시명 표 한 곳에서만** 정의되고 스테퍼 캡션이 그 값을 가져다 쓴다. 두 축이 각자 문자열을 적으면 한쪽만 갱신돼 다시 갈리므로, 소스에 그 리터럴이 **한 번만** 적히는지를 기계로 고정한다. 멤버 **목록** 자체는 여전히 단일 진실원에서 파생한다. ⚠ **구 서술 「두 이름은 다르다 — 버튼은 `AI 탐지 · AI 분할 · 보간`, 캡션은 `오토라벨링`」은 폐기**(같은 묶음이 한 화면에서 두 이름으로 불렸고, 사양의 어휘가 「오토라벨」이다). 이름이 잃은 **「보간 포함」 고지**는 재수행 경고 문단·확인 창이 전담한다 → §5.5.4.
- **서버가 오토라벨 세 단계를 일부만 내려주거나 순서를 달리 실어도 깨지지 않는다** — 첫 멤버가 나타난 자리에 접은 칸을 한 번만 놓고 흩어진 나머지도 그 칸이 흡수하며, 멤버가 하나도 없으면 접은 칸을 만들지 않는다. `stages` 가 비면 종전대로 아무것도 렌더하지 않고 상위 화면 배지로 폴백한다.
- 코드: FE `components/common/BatchStageIndicator.tsx`(`collapseStages` · `collapsedStatus` · `liveStageMessage`). 테스트 케이스는 [H-61](../test-cases/H-frontend-e2e.md).

## 5.6 관련 데이터 (DB)

`LS_DATA_RAW`(영상 메타·VMS_CLIP_ID·EVNT_TYPE_CD·DE_IDENT_YN·ORGNL_RAW_SN), `LS_DATA_RAW_HSTRY`(상태 이력), `LS_DATA_SRC`(추출 프레임·원본/비식별 경로), `LS_RAW_DATA_STATUS`/`LS_RAW_DATA_ENROLLMENT`. 관제 소유 `MNG_CLIP_MASTER`/`MNG_RESOURCE_CCTV` 참조. → [18](18-database.md).
