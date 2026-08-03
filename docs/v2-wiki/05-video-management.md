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
  - `POST /v1/uploads` — `Upload-Length`+`Upload-Metadata`(base64 filename 등) → 검증 → `LS_TUS_UPLOAD` 행+임시파일 생성 → 201 + `Location: /v1/uploads/{uploadId}`
  - `HEAD /v1/uploads/{id}` — `Upload-Offset`/`Upload-Length` 응답(재개), 만료 410
  - `PATCH /v1/uploads/{id}` (`application/offset+octet-stream`) — 청크 append → 새 `Upload-Offset`. 완료(offset==length) 시 매직바이트 검증 → ffprobe duration → `LS_DATA_RAW` 합류
  - `DELETE /v1/uploads/{id}` — 세션 취소+임시파일 삭제
- 만료 정리: `EXPIRES_AT`(+24h) TTL + `@Scheduled` 정리 잡(`TusUploadCleanupJob`, 1h 간격)
- FE: `useTusUpload` 훅 + `TusUploadPanel`(진행률 + 일시정지/재개). 기존 multipart 경로(`/dev/autolabel-test`)는 fallback 유지

### 업로드 검증 (보안)
- 확장자 allowlist + 파일 크기 제한 + MIME 검증 필수
- 경로 순회(`..`) 차단(`Path.normalize` + 기준 경로 검증, CWE-22)
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

- **비식별 처리는 분류와 무관하게 전체 영상 무조건 실행**(ANONY 포함, 게이팅 폐지) — 적재 직후 선두 자동 → [08](08-deidentification.md)
- 원본 영상과 비식별 영상은 **별도 경로 동시 저장** (`STORAGE_RAW_PATH` / `STORAGE_DEIDENTIFIED_PATH`)
- 비식별 상태: `LS_DATA_RAW.DE_IDENT_YN` (Y/N/F) → [08](08-deidentification.md)

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
| `eventTypeCd` | `LS_DATA_RAW.EVNT_TYPE_CD` | 값은 **카테고리 키**(`EVNT_CLS_CD+EVNT_CTGRY_CD`, 예 `010001`). 영상이 가진 상세 EV-코드와 축이 달라 관제 마스터 역인덱스(`EventTypeService.codesForCategoryKey`)로 EV-코드 집합을 펼쳐 `IN` 비교한다. **미등록 키·관제 미등록 EV-코드는 오류가 아니라 0건**(fail-safe) |
| `from` / `to` | `LS_DATA_RAW.SHT_DT` | `yyyy-MM-dd`. **경계 포함**(from 당일 00:00:00 ~ to 당일 23:59:59.999999999). 촬영일시가 없는 영상은 잡히지 않는다 |

- **400 응답**: 검색어 100자 초과 / 날짜 형식 오류 / `from > to`. (셋 다 이번에 신설된 파라미터라 하위호환 파손 없음 — 미지정 정렬 키의 lenient 폴백 정책은 종전대로 유지)
- **표시 축 정합**: `VideoSummaryResponse.capturedAt` 은 **`SHT_DT`(촬영/녹화 시각)** 이다. 구현 초기 `REG_DT`(수신 시각)를 싣고 있어 화면 컬럼('녹화일')·정렬 키(`capturedAt→shtDt`)·기간 필터와 3갈래로 갈렸던 드리프트를 정정했다. **수신 시각으로 폴백하지 않으며**(BE·FE 양쪽) 촬영 시각이 없으면 `null` → 화면 `-`. 수신 시각은 별도 필드 `regDt` 로 계속 나간다.
- **파생영상 제외 불변**: 검색·필터 어떤 조합에서도 `ORGNL_RAW_SN IS NOT NULL`(증강·해상도 파생) 영상은 노출되지 않는다(조건은 통합 쿼리 `VideoRepository.searchOriginals` 한 곳에만 존재).
- 코드: `video/controller/VideoController.java`, `video/dto/VideoListFilter.java`, `video/service/VideoQueryService.java`, `video/repository/VideoRepository.java#searchOriginals`, FE `features/video/components/VideoFilters.tsx`

## 5.6 관련 데이터 (DB)

`LS_DATA_RAW`(영상 메타·VMS_CLIP_ID·EVNT_TYPE_CD·DE_IDENT_YN·ORGNL_RAW_SN), `LS_DATA_RAW_HSTRY`(상태 이력), `LS_DATA_SRC`(추출 프레임·원본/비식별 경로), `LS_RAW_DATA_STATUS`/`LS_RAW_DATA_ENROLLMENT`. 관제 소유 `MNG_CLIP_MASTER`/`MNG_RESOURCE_CCTV` 참조. → [18](18-database.md).
