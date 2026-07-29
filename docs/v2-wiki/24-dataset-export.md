# 24. 검수 승인 시 학습데이터 파일 산출 (Dataset Export)

> 출처: CLAUDE.md(라벨링·버전관리·데이터마트 View), 코드(`dataset/export/`), NIA COCO 확장 포맷(xlsx v1.3).
> 관련: [12 검수](12-review-assignment.md) · [13 버전관리](13-version-control.md) · [15 관제서버 통지](15-control-notify.md) · [18 데이터베이스](18-database.md)

검수 승인(`APPROVED`) 시, 영상 1건의 확정 라벨을 **디스크에 물리 파일(프레임 이미지 + NIA COCO 확장 JSON)** 로 산출하는 기능이다. [13 버전관리](13-version-control.md)의 `LS_LABEL_VERSION`(DB 스냅샷)과는 **별개 레이어** — 버전관리는 DB 안의 라벨 페이로드 스냅샷이고, 본 기능은 데이터마트/외부 소비를 위한 **파일 산출물**이다.

## 24.1 트리거 (승인 AFTER_COMMIT, API 없음)

- 사용자가 직접 호출하는 REST API가 **없다**. 검수 승인 트랜잭션 커밋 이후 자동 실행된다.
- 배선: `ReviewApprovedEvent`(`@TransactionalEventListener(AFTER_COMMIT)`) → `DatasetExportBridge` → `@Async("batchAsyncExecutor")` `AsyncDatasetExportRunner.runAsync(rawSn)` → `DatasetExportService.export(rawSn)`.
- **승인 불변 정합(HIGH)**: 산출은 승인 커밋 이후 별도 스레드에서 실행되며, 파일 쓰기 실패는 예외를 삼키고 export 레코드를 `FAILED` 로만 기록한다 — **검수 승인은 절대 롤백되지 않는다**.
- 토글: `authoring.dataset-export.enabled` (기본 활성 — `matchIfMissing=true`). `false` 면 브릿지 빈이 등록되지 않아 산출이 비활성화된다.

## 24.2 폴더 구조

**산출 루트는 원본 영상과 같은 디렉터리 하위(co-locate)다.** base 는 `dirname(LS_DATA_RAW.RAW_FILE_PATH_NM)` 로 **영상마다 다르다**(구 고정 루트 `authoring.storage.labeling-path` 는 롤백 전략으로만 남는다).

```
{dirname(RAW_FILE_PATH_NM)}/            ← 관제 NAS. 원본 영상이 있는 디렉터리
    {원본영상}.mp4                       ← 관제 소유. 읽기만·절대 변경 금지
    {RAW_SN}/                            ← 저작도구가 신규 생성 (RAW_SN == job_id)
        deid/{비식별영상}                 ← 비식별 영상(버전 무관). 파일명은 아래 참조
        v{n}/orgnl/{FRM_NO:%04d}.jpg | .json
        v{n}/deid/{FRM_NO:%04d}.jpg  | .json
```

- **버전이 종류(orgnl/deid)의 상위** — `v{n}/{orgnl|deid}/`.
- **원본 영상은 복사하지 않는다** — `{RAW_SN}/` 의 바로 상위 형제로 이미 존재한다.
- **비식별 영상은 버전 무관**이라 `v{n}` 밖 `{RAW_SN}/deid/` 에 둔다.
- ⚠ **비식별 영상 파일명은 고정이 아니다** — 저작도구가 지정하는 것은 **디렉터리(`export_path`)까지**이고 파일명은 외부 비식별 솔루션이 정한다(mock=`deidentified.mp4`, KPST 실연동=`{원본stem}-mask{ext}`). **파일명을 조합·추측하지 말고 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 을 읽는다.**
- ⚠ **경로 가드(CWE-22)**: base 가 DB 값에서 도출되므로 `VideoArtifactRootResolver` 가 2단계로 검증한다 — ①`dirname(RAW_FILE_PATH_NM)` 이 **고정 allowlist**(`authoring.storage.raw-mount-roots`) 하위인지 ②검증된 base 기준으로 target 을 `normalize()` + `toRealPath()` 후 재검증. 위반 시 **기본 루트로 fallback 하지 않고 export 를 FAILED 로 마감**한다(승인 트랜잭션은 롤백하지 않음).
- `orgnl` = 원본 프레임 산출, `deid` = 비식별 프레임 산출. **원본·비식별 2벌**을 함께 산출한다.
- 프레임 이미지는 `FrameSource` 가 해석하는 원천 경로에서 복사한다 — 원본은 `authoring.storage.raw-path` 하위(`LS_DATA_SRC.SRC_FILE_PATH_NM`), 비식별은 `authoring.storage.deidentified-path` 하위(`LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM`).
- **부분 성공**: 원천 이미지가 없는 프레임은 건너뛰고(skip) 나머지만 산출한다(경로 원문/PII 미로깅). 산출량에 따라 상태가 갈린다 — 전부 성공=`SUCCEEDED`, 일부만 산출(skip>0 & written>0)=`PARTIAL`, 한 장도 못 씀(written=0)=`FAILED`(§24.7).
- **경로 보안(CWE-22/59)**: 산출 경로는 `DatasetExportPathResolver` 가 `labeling-path` 하위 포함을 `normalize()`+`startsWith` 로 검증, 원천 이미지는 `FrameSource` 가 base 하위 포함 + `toRealPath()` 심링크 이탈까지 재검증한다. JSON 은 `.tmp` 기록 후 `Files.move`(ATOMIC_MOVE 시도→REPLACE 폴백)로 원자적 교체.

## 24.3 JSON 포맷 (NIA COCO 확장 xlsx v1.3)

프레임마다 자기완결 문서 1건(`NiaAnnotationDoc`). **최상위 8키(고정 순서)**:

| 키 | 내용 |
|----|------|
| `info` | year·version(`1.3`)·description·date_created |
| `dataset` | id(=RAW_SN)·name·path·(url=null) |
| `licences` | 사용 라이선스(현재 private-use 1건) |
| `video` | 영상 메타 (§24.4 매핑표) |
| `image` | 프레임 메타 (아래) |
| `annotations` | 라벨 목록 — 타입별 bbox/polygon/keypoints 중 하나 (객체 인스턴스 배열) |
| `event` | **이벤트 단위 VQA/CoT (§24.3.1)** — 프레임별 문서마다 동일 영상(RAW_SN) 단위 블록을 최상위 키로 첨부. `caption`/`evidence` 는 후보 키 `c1..cn` 객체. (JSON 출력 키는 정본 샘플 정합으로 `event`, 위치는 `video` 다음 유지. 내부 클래스/필드명은 `eventAnnotation` 유지) |
| `categories` | 사용된 라벨 마스터(`LS_LABEL`) → 카테고리 |
| `type` | `instances` (고정) |

`@JsonInclude(ALWAYS)` 로 값이 null 인 **필수 키도 항상 직렬화**되어 관제 데이터마트 스키마와 정합한다.

### image 블록 주요 필드
- `file_name` = **`{FRM_NO}.jpg` 4자리 zero-pad**(`String.format("%04d", frmNo)` — `ExportFileNaming` 단일 지점. 10000 이상은 자연 확장). 구 `frame-{n}.jpg` 접두사 형식은 **폐기**. **통지 `changed_items`·디스크 실제 파일명·JSON `file_name` 3자가 항상 일치**해야 한다.
- `frame_num` = **`LS_DATA_SRC.VDO_FRM_NO`(실제 영상 디코더 프레임 위치)**. 구 `FRM_NO`(추출 순번) 아님 — 두 값은 별개 컬럼이다(예: 추출 순번 2 ↔ 실제 프레임 20). **`VDO_FRM_NO` 가 null 이면 `frame_num` 도 null 로 내보낸다**(`FRM_NO` 폴백 금지 — 의미 혼선).
- `width`/`height` = 영상 메타 해상도.
- `description` = `LS_DATA_SRC.FRM_EXPLN`(작업자 수기 프레임 설명). 미입력 시 null.
- `anonymity` = **orgnl → `N`, deid → `Y`** (산출 종류로 결정).
- `pseudonymity` = 영상 개인정보 유형이 `PSDO` 이면 `Y`, 아니면 `N`.
- (`orign_file_name` 은 **정본 샘플에 없어 제거**됨 — 원천 소스 파일 basename 은 더 이상 image 블록에 노출하지 않는다.)

> **orgnl ↔ deid JSON 델타(소비측 주의)**: 같은 프레임의 원본/비식별 JSON 은 **`anonymity`(N/Y)** 만 다르다. **`file_name`(`frame-{n}.jpg`)·`frame_num`·좌표·`annotations`·해상도·`description` 은 동일**하며, 두 벌은 폴더 경로 `orgnl/`·`deid/` 로 구분된다. 즉 "이미지 파일명이 다르다"가 아니라 "**폴더와 anonymity 가 다르다**"가 정확한 서술이다.

### annotations 블록 (타입별)
- `BBOX`/`TRACK` → `bbox=[x, y, w, h]` (POINT_CN min/max 바운딩, 픽셀 그대로).
- `POLYGON`/`SEGMENT` → `polygon=[[x, y, x, y, …]]` (flat).
- `SKELETON` → `keypoints=[[x, y, v]×17]` (v: 0 미표기 / 1 비가시 / 2 가시).
- malformed 라벨 1건은 문서 전체를 깨지 않고 skip(fail-secure).

### 24.3.1 event 블록 (VQA/CoT) — 확정·구현 완료

> ✅ **확정·구현 완료.** BE 저장/API/검수/export(Phase 1~3) + FE 수동입력 폼(Phase 4)까지 구현됐다.
> 프레임별 자기완결 문서(`NiaAnnotationDoc`) 각각에 **동일 영상(RAW_SN) 단위** event 블록을 최상위 키로 첨부한다.
> **JSON 출력 키는 정본 샘플 정합으로 `event`** (구 `event_annotation` 에서 rename, 위치는 `video` 다음 유지). 내부 저장/API DTO 클래스·필드명(`EventAnnotationPayload`·`eventAnnotation`)은 유지한다.
> 근거: `docs/AI기반CCTV_어노테이션 포맷 및 데이터 구조_v1.3` 계열(속성 정의) + `docs/AI기반CCTV_어노테이션_예제_sample.json`(예제).

**분리 이유**: VQA/CoT는 프레임·객체 단위가 아니라 **이벤트/영상 단위** 추론·서술 메타다. COCO 규격의 `annotations`(per-object 배열, → `LS_DATA_LBL`)와 층위가 달라, 우리 DB에서도 **영상 단위(`LS_EVNT_ANNO`, RAW_SN)** 로 분리 적재되는 값이다. 따라서 JSON도 `annotations` 안에 넣지 않고 **별도 최상위 `event`(object)** 으로 둔다. ([09 VLM 시계열](09-vlm-timeseries.md) 참조)

**저장·조달**: 외부 자동 생성(외부 시계열/VQA 시스템 전달)값을 `LS_EVNT_ANNO.ANNO_CN`(jsonb) 에 적재해 폼에 프리필하고, WORKER/REVIEWER 가 라벨링 화면 '메타' 탭 **이벤트 어노테이션 패널**에서 전 필드를 수동 덮어쓰기·검토한다(REVIEWER 승인/반려). 저장 API `PUT /v1/videos/{rawSn}/event-annotation`, 페이로드 스키마는 아래와 정확 일치(BE `EventAnnotationPayload`).

| 필드 (경로) | 타입 | 값 조달 | 비고 |
|-------------|------|:------:|------|
| `event_class` | string | 외부 자동 + 수동입력 | 이벤트 클래스(분류명) — **필수** |
| `question` | string | 외부 자동 + 수동입력 | 이벤트 발생 여부·근거를 묻는 질문 |
| `caption` | object | 외부 자동 + 수동입력 | 후보 캡션 집합 `{c1, c2, … cn}` |
| `caption.{cN}.caption_text` | string | 외부 자동 + 수동입력 | 후보별 캡션 텍스트 |
| `caption.{cN}.cot` | object | 외부 자동 + 수동입력 | 사고 과정(CoT) — **단계 라벨 키 객체** `{"1단계":..,"2단계":..,"3단계":..}`(정본 샘플 정합). 과거 배열 동결본은 조회 시 `n단계` 키 객체로 관용 흡수(하위호환) |
| `answer` | string | 외부 자동 + 수동입력 | 이벤트 확인 결과 |
| `evidence` | object | 외부 자동 + 수동입력 | 후보 근거 집합 `{c1, c2, … cn}` |
| `evidence.{cN}.evidence_text` | string | 외부 자동 + 수동입력 | 후보별 근거 서술 |
| `evidence.{cN}.frame_id` | number[] | 외부 자동 + 수동입력 | 근거 프레임 ID 목록 |
| `evidence.{cN}.obj_id` | string[] | 외부 자동 + 수동입력 | 객체 ID 목록 |
| `evidence.{cN}.obj_bbox` | number[][] | 외부 자동 + 수동입력 | 객체 바운딩박스 `[x1,y1,x2,y2]` 목록 |
| `evidence.{cN}.obj_label` | string[] | 외부 자동 + 수동입력 | 객체 라벨 목록 |

- **후보 키(`c1`~`cn`)는 가변**이며, caption `cN` 과 evidence `cN` 은 같은 키로 연결된다. `cot` 는 후보마다 단계 라벨 키 객체(`{"1단계":..,"2단계":..,"3단계":..}`)로 채운다.
- 값이 비어있는(미입력) 필드/후보는 저장 시 payload 에서 생략된다(`event_class` 만 필수). 알 수 없는 키는 BE 가 무시(`ignoreUnknown`).

## 24.4 video 필드 매핑표

`LsDatasetVideoMeta`(1차 소스) + `LsDataRaw`(폴백) → `NiaVideo`. 원천이 없는 **미보유 필수 필드는 null** 로 두되 키는 유지한다.

| video 키 | 원천 | 비고 |
|----------|------|------|
| `id` | RAW_SN | |
| `filename` | RAW_FILE_PATH_NM basename | (`orign_filename` 은 정본 샘플에 없어 **제거**) |
| `date_created` | SHT_DT (YYYY-MM-DD) | |
| `format` / `filesize` | FILE_FMT / FILE_SZ | |
| `location` | SIDO_NM + SGG_NM | |
| `length` | VDO_LEN_SEC (폴백 raw.durationSec) | 초 |
| `fps` / `aspect_ratio` | FPS / ASPRT_RT | |
| `width` / `height` / `resolution` | VDO_WDTH / VDO_HGT / RESL | |
| `bit` | BIT_RT | |
| `weather` | **LS_DATA_RAW.WTHR_NM(수동값) → 스냅샷 WTHR_NM** | 촬영환경 수동 저장값 우선(§24.4.1) |
| `coordinates` | WGS84_LAT,WGS84_LOT | |
| `cctv_name` | CCTV_NM | |
| `anonymity` | **산출 종류 오버라이드** (orgnl=N/deid=Y) | |
| `pseudonymity` / `privacy_included` | PRVC_TYPE_CD(=PSDO?) / PRVC_YN | |
| `event_id` / `event_name` | EVNT_TYPE_CD / EVNT_NM | |
| `time_of_day` / `season` | **LS_DATA_RAW.DAY_NGT_CD / SESN_CD(수동값) → 스냅샷 DAY_NGT_CD / SESN_CD** | 촬영환경 수동 저장값 우선. 둘 다 미입력이면 **null(미상)** — 촬영일시 추정 안 함(§24.4.1) |
| `type`, `pixel`, `frames`, `license_id`, `og_cd`, `cctv_height`, `cctv_azimuth`, `cctv_mng_no`, `event_log`, `vd_description` | — | **미보유 → null** (키 유지) |

> **VQA/CoT 위치 정정**: 구 video 블록의 `cto`/`vqa` 플레이스홀더는 **필드 자체를 제거**했다 — 정본 샘플에 없으며, VQA/CoT는 영상 기술메타(video)가 아니라 **최상위 `event`**(§24.3.1)으로 분리한다. 정본 샘플에 있는 `vd_description`(영상 서술) 키는 추가했다(현재 원천 미보유 → null). (확정·구현 완료)

### 24.4.1 촬영환경(weather / time_of_day / season) — 수동값 우선

영상 단위 촬영환경은 작업자가 화면에서 직접 입력·정정할 수 있다(`GET|PUT /v1/videos/{rawSn}/environment-meta`, REVIEWER·WORKER(본인 배정)).

| 구분 | 규칙 |
|------|------|
| 저장 | `LS_DATA_RAW.WTHR_NM / DAY_NGT_CD / SESN_CD`(V130). PUT 은 **전체 교체** — 3필드를 항상 함께 전송하고, 생략한 필드는 수동값이 삭제된다 |
| 허용값 | weather=맑음·흐림·비·눈·안개 / time_of_day=DAY·NGT / season=SPRING·SUMMER·FALL·WINTER (화이트리스트, 그 외 400) |
| 조회 프리필 | 수동값이 있으면 그 값(`MANUAL`), 없으면 SHT_DT 파생값(`DERIVED`). weather 는 자동 출처가 없어 미입력 시 null. SHT_DT 가 null 이면 time_of_day·season 도 null |
| 승인 동결 | 검수 승인 스냅샷(`LS_DATASET_VIDEO_META`)에 **수동값만** 동결하고 **미입력이면 null(미상)을 유지**한다 — SHT_DT 기반 추정(self-fill)을 하지 않는다(2026-07-29, E-ISSUE-42. 구 정책 "미입력이면 SHT_DT 파생값 폴백" 폐기). 근거: 동결값은 export JSON·데이터마트 뷰로 **출처 구분자 없이** 전파돼 관제가 추정값을 관측값과 동일하게 소비한다(실증: 여름 18:00 촬영분이 구 규칙 `hour>=18 → NGT` 로 야간 오분류, 한국 7월 일몰 ≈ 19:50). 파생을 하지 않으므로 **동결된 non-null 값은 전부 수동값** → 출처 구분 컬럼(`ENV_SRC_CD` 등)이 불필요하다(스키마 변경 없음). ⚠ 단 이 단언은 **아래 "레거시 파생 동결값 정정 백필" 완료를 전제로 한 참**이다 — 백필 이전에는 파생 폐지 전에 동결된 `NGT`/`SUMMER` 행이 남아 거짓이었고, 관제는 그 값이 관측값인지 추정값인지 구분할 수 없었다. 해시: `DAY_NGT_CD`·`SESN_CD` 는 항상 해시 입력에 포함(키 유지, 값만 null)되고 `WTHR_NM` 은 **값이 있을 때만** 포함한다(미입력이면 키 생략 — 도입 이전 승인 영상이 내용 무변경인데도 재동결 시 새 해시로 중복 버전이 쌓이는 것을 막는 하위호환) |
| 동결 해시 영향(파생 폐지) | 촬영환경 미입력 영상은 동결값이 `NGT/SUMMER` → `null` 로 **실제 내용이 바뀌므로** 해시가 달라져 재승인 시 새 스냅샷 버전이 append 된다(정상 — 버전-per-내용 정합) |
| 레거시 파생 동결값 정정 백필 | 파생 폐지 **이전**에 이미 `NGT`/`SUMMER` 로 동결된 스냅샷은 남아 있으므로 **소급 정정한다**(2026-07-29 M-1. 구 정책 "기존 동결 행 백필은 하지 않는다" 폐기 — 그 행은 뷰로 노출될 뿐 아니라 `raw → meta` 폴백을 타고 **재-export 되는 새 버전 폴더에도 다시 기록**돼 오염이 계속 번졌다). **판별식**: 라이브 `LS_DATA_RAW` 의 수동값이 없는데 활성 스냅샷에 값이 있는 APPROVED 영상 — 수동 원천이 없는데 동결값이 있을 경로는 폐기된 파생 폴백뿐이라 파생값임이 결정적으로 증명된다. **날씨(`WTHR_NM`)는 대상이 아니다**(원래 파생 원천이 없어 non-null 이면 수동값). **반대 방향(raw non-null + 스냅샷 null)도 대상이 아니다**(승인 이후 수동 입력이 추가된 정상 케이스). 정정은 SQL UPDATE 가 아니라 **`materialize` 재동결**로 수행해 `SNPSHT_HASH`·활성 1건 불변식을 유지하고, 검수 완료 일시(`RVW_CMPL_DT`)는 승계한다. **이전 오염 행은 삭제하지 않고 `ACTIVE_YN='N'` 이력으로 남긴다**. 실행은 기동 시 1회(`DatasetVideoMetaBackfillRunner`, `!local`)이며 멱등(정정 후 판별식에서 빠짐) |
| 정정 백필의 재산출·통지 | 정정 1건마다 `TaskModifiedEvent(META_UPDATED, exportRegenerated=true)` 를 발행해 **export 를 새 버전 폴더로 전량 재생성한 뒤 통지**한다(순서 역전 시 관제가 구 버전 폴더를 픽업). 폭주 방지로 **1회 실행당 상한**(`authoring.dataset-video-meta.env-correction.max-per-run` / `DATASET_ENV_CORRECTION_MAX_PER_RUN`, 기본 200)을 두고 잔여분은 다음 기동에서 이어서 처리한다. 시작 시 총 대상 건수 / 종료 시 잔여 건수를 로그로 남긴다 |
| 정정 백필 수동 트리거 | 기동 러너가 `@Profile("!local")` 이라 **로컬 스택에서는 백필이 돌지 않으므로**(로컬 기동마다 APPROVED export 가 재생성되는 노이즈 회피 — 기존 정책 유지) 검증용 수동 트리거를 둔다. **dev 전용·REVIEWER 전용**(`@Profile("!prd")` 빈 게이팅 + `SecurityConfig` `/v1/dev/**` → `hasRole('REVIEWER')` + 핸들러 `@PreAuthorize`, 배포 표식은 `DevProfileGuard` 가 별도 축으로 차단). ①`GET /v1/dev/dataset-video-meta/shooting-env-correction-targets` = **대상 건수만**(dry-run, 정정 미수행) ②`POST /v1/dev/dataset-video-meta/shooting-env-corrections` = 실제 정정(응답 `corrected`/`remaining`/`completed`). 같은 URL 의 쿼리 파라미터 분기(`?dryRun=`) 금지 원칙에 따라 sub-resource 로 분리한다. 실행은 위 `max-per-run` 상한을 **그대로** 타며 상한 우회(전량 실행) 옵션은 없다 — 잔여가 남으면 재호출(멱등, 두 번째는 0건) |
| export | `NiaVideo.weather/time_of_day/season` 은 **raw 수동값 → 스냅샷** 순으로 채운다(다른 video 필드는 스냅샷 우선이라 순서가 반대). 영상 단위 값이라 ORIGINAL/DEIDENTIFIED 2벌이 항상 동일. **두 원천이 모두 미입력이면 값은 null** 이고, `@JsonInclude(ALWAYS)` 로 **키(`time_of_day`/`season`/`weather`)는 유지**되므로 관제/데이터마트 파서 계약은 깨지지 않는다 |
| 승인 후 수정 | 검수 완료(APPROVED) 영상의 촬영환경을 정정하면 **①동결 스냅샷(데이터마트 뷰) 재동결(materialize)** + **②export 폴더를 새 버전 `v{n+1}` 로 전량 재생성**을 함께 수행한다(Phase 5C — 구 정책 "export 폴더는 재산출하지 않고 다음 재승인 시 재산출"은 폐기: 재동결이 스냅샷만 갱신하고 파일은 옛 촬영환경으로 남으면 관제가 픽업하는 산출물과 뷰가 불일치했기 때문). 재산출은 `TaskModifiedEvent(exportRegenerated=true)` → 디바운스 flush 가 export 를 먼저 마친 뒤 통지를 내보내는 순서로 직렬화된다(§24.6 R6, [15](15-control-notify.md) §15.2). 재동결 시 **검수 완료 일시(`RVW_CMPL_DT`)는 최초 승인 시각을 보존**한다(편집 시각으로 덮지 않음 — `TASK_COMPLETED` "검수 완료 일시" 계약). 활성 스냅샷이 없으면 fail-safe skip |
| 파생영상 | 증강·해상도 파생본은 생성 시 부모의 촬영환경 수동값 3필드를 **복사**한다(같은 영상 소스이므로). 부모가 미입력이면 파생본도 null(미상) 이다. 복사는 **생성 시점 1회**이며 파생 생성 후 부모 촬영환경 수정은 파생본으로 **재전파하지 않는다**(스냅샷 시맨틱 — 의도) |
| 통지 | 검수 완료(APPROVED) 후 수정 시 관제 `TASK_MODIFIED`(META_UPDATED) 발행. 위 재생성이 트리거되는 경우 **export 성공(SUCCEEDED) 이후**에만 발송된다([15](15-control-notify.md) §15.2) |

> **미보유 필수 필드 null 정책 (소비측 주의)**: 위 "미보유" 필드는 저작도구가 원천 데이터를 보유하지 않아 **의도적으로 null** 이다. `@JsonInclude(ALWAYS)` 로 키 자체는 항상 존재하므로, 소비측은 "키 부재"가 아니라 "**값 null**"로 미보유를 판정해야 한다.

> **파생 프레임 개인정보 cross-stale 경계 (아키텍처 경계·후속 백로그)**: 프레임 개인정보 3필드(`LS_DATA_SRC.ANONY_INCL_YN`/`PSDO_INCL_YN`/`PRVC_INCL_YN`)는 증강·해상도 파생 생성 시 부모 프레임 → 자식 파생 프레임으로 **생성 시점 1회 복사**된다(`AugmentExtractPersist`/`ResolutionPersistService` 의 `loadParentSrcs`). 부모 영상에 이후 **비식별 누락 신고**가 발생하면 `DeidentReportService.report()` 가 부모 rawSn 의 프레임 3필드만 NULL 리셋하며, **이미 생성된 자식 파생(다른 rawSn)의 복사값은 리셋되지 않는다**(cross-stale). 이는 의도된 아키텍처 경계다 — ① 신규 파생은 생성 시점 **복사 원자성 게이트**(스냅샷 이후 부모 비식별본 *교체* 감지 — procLog 경로 불일치·파일 mtime)로 방어되고(2026-07-29: 구 '신고 이력' 조건은 제거 — 파생 생성은 신고와 무관 → [14 §14.3](14-augmentation.md)), ② 기존 파생은 **원본 신고와 무관하게 독립 취급**된다(2026-07-29 확정 — 파생영상에서는 비식별 누락 신고를 접수하지 않으며 412 로 거부되고, 신고 게이트도 자기 `rawSn` 행만 판정한다 → [08 §8.4](08-deidentification.md)). 따라서 파생본의 3필드 stale 값은 파생본 자체의 라벨링·검수 워크플로에서 수동 정정해야 한다. 부모→기존 파생 캐스케이드 리셋은 현재 미지원이며 후속 백로그로 관리한다(같은 경계를 `DeidentReportService.report` Javadoc 에도 명시).

## 24.5 categories 와 SKELETON 인덱싱

- 사용된 `LS_LABEL` → `NiaCategory`(id·name·type). type: BBOX/TRACK→`bbox`, POLYGON/SEGMENT→`polygon`, POINT/SKELETON→`keypoints`.
- keypoints 타입 카테고리에는 COCO-17 관절명(`keypoints`)과 스켈레톤 엣지(`skeleton`)를 주입한다(`KeypointSkeleton`).
- **⚠ SKELETON_EDGES 는 1-indexed(COCO 표준 관절 번호쌍)** 이다. 관절명 리스트(`KEYPOINT_NAMES`)는 0-based 인덱스로 접근하지만, 스켈레톤 엣지의 관절 번호는 **1-based**(예: `[16,14]` = 16번 left_ankle ↔ 14번 left_knee, 배열 접근 시 −1)다. 이는 COCO person-keypoints 표준 토폴로지와 정확히 일치하며 BE `KeypointSkeleton.SKELETON_EDGES` ↔ FE `COCO_SKELETON` 이 동일 값으로 잠겨 있다(FE 테스트 `keypointConstants.test.ts` 로 parity 검증, 렌더러는 `keypoints[a−1]` 로 소비).
  - **정본 대조**: xlsx v1.3 은 `skeleton` 필드를 "키포인트 연결 정보(`number[]`)"로만 선언하고 **index base(0/1)를 규정하지 않는다**(구체 예시 없음). 따라서 저작도구는 사실상 표준인 **COCO 1-indexed** 를 채택한다 — xlsx 와 모순되지 않는다. 외부 소비측(관제 데이터마트)은 skeleton 관절 번호를 **1-based** 로 해석해야 한다(off-by-one 방지).

## 24.6 멱등 · 버전 누적

- **버전 채번**: 기존 export 건수 + 1 = 다음 `EXPORT_VER_NO`. UK(DATA_RAW_SN, EXPORT_VER_NO) 위반 시 재채번 재시도(동시 승인 TOCTOU/CWE-362 백스톱).
- **★R6 — 검수 승인은 항상 전량 재생성(멱등 skip 미적용)**: 검수 승인(`onReviewApproved`, 최초·재승인 무관) 트리거는 **`forceRegenerate=true`** 로 진입해, **내용 변경 여부와 무관하게 매 승인마다 새 버전 폴더 + JSON/이미지를 전량 재생성**한다. 아래 콘텐츠 해시 멱등 skip 은 **재동결 경로(`onReExport`, event_annotation 지연 승인 등, `forceRegenerate=false`)에서만** 적용된다.
  - **★승인 후 수정도 전량 재생성한다(Phase 5C — 구 정책 폐기)**: 라벨 수정(`LabelService`)·트랙 편집(`TrackEditService`)·트랙 병합(`TrackMergeService`)·버전 롤백(`VersionService`)·촬영환경 수정(`EnvironmentMetaService`, §24.4.1)·프레임 설명 수정(`FrameDescriptionService`)·프레임 개인정보 메타 수정(`FramePrivacyMetaService`)은 검수 완료(APPROVED) 이후 발생하면 `TaskModifiedEvent(exportRegenerated=true)` 를 발행하고, `ControlNotifyDebouncer` 의 flush 가 `AsyncDatasetExportRunner.runReExportThenNotify(rawSn, forceRegenerate=true, ...)` 로 export 를 새 버전 `v{n+1}` 로 **전량 재생성**(멱등 skip 미적용)한 뒤 통지를 내보낸다. 구 서술 "편집(촬영환경 PUT 등)은 재export 자체를 트리거하지 않는다"는 **폐기** — 이제 승인 후 편집은 재export 를 트리거한다. **예외**: `EvntAnnoService`(event_annotation 일반 수정)·`MetaService`(VLM 시계열 메타)는 재생성을 발행하지 않는다(CLAUDE.md "★ export 재생성·동기화 정책" 참조).
  - **재생성 경로는 항상 `force=true`**: 승인(R6)·승인 후 수정(위) 모두 `forceRegenerate=true` 로 export 를 호출하므로 §24.6 의 콘텐츠 해시 멱등 skip 은 이 두 경로에는 적용되지 않는다. 멱등 skip 은 `onReExport`(휴면, 현재 발행처 없음) 경로에서만 유효하다.
  - **retention 백로그(범위 밖)**: 승인마다 새 버전 + 프레임 2벌(orgnl/deid) 복사가 누적되나 구 버전 정리(retention) 잡은 미구현이다. 보존 정책·정리 잡·저장소 메트릭 알람은 **별도 후속 Phase**에서 도입한다(코드에 `TODO(retention)` 주석).
- **무수정 재동결 멱등(콘텐츠 해시, force=false 경로 한정)**: 산출 시점 상태를 SHA-256 콘텐츠 해시로 계산한다. 해시 원천은 **라벨 + 프레임(FRM_EXPLN 등) + 영상 메타(개인정보 유형·해상도 등)** — 라벨뿐 아니라 프레임 설명/개인정보 정정도 반영한다. 직전 **SUCCEEDED 또는 PARTIAL**(멱등 baseline) export 의 해시와 같으면 재산출을 **skip**(중복 버전 생성 방지). 직전이 FAILED/PENDING 이어도 그 이전의 SUCCEEDED/PARTIAL 해시를 상태 IN 필터로 정확히 찾는다.
  - **PARTIAL 을 baseline 에 포함하는 이유(무한 누적 방지)**: 원천 이미지가 지속 부재해 매번 `PARTIAL` 로 끝나는 영상을 무수정 재동결(force=false)할 때, PARTIAL 을 baseline 에서 제외하면 `v2·v3·v4…` 가 무한 채번되며 매 버전 이미지 파일이 재복사되어 디스크가 무한 증가한다(OWASP API4). PARTIAL 도 멱등 baseline 으로 삼아 이를 차단한다. `FAILED`(written=0)는 디스크 누적이 없고 재시도를 유도해야 하므로 baseline 에서 계속 제외한다.
  - **한계(설계상 수용)**: PARTIAL 후 라벨이 불변인 채 부재 이미지가 나중에 실제로 복구돼도, 해시가 같아 자동 재완성되지는 않는다(라벨/설명을 조금이라도 수정하면 해시 변경으로 재산출됨). 이는 무한 누적 방지를 위한 의도된 트레이드오프이며 기존 산출물은 보존된다(데이터 손실 아님).
- **수정 후 재승인**: 라벨/설명/개인정보 변경 → 해시 변경 → **v{n+1} 폴더 신규 생성, 이전 버전 폴더는 보존**. 재검수→재승인마다 버전이 누적된다.
- **배포노트(#7 해시 churn, self-heal — 재동결 force=false 경로 한정)**: 프레임 개인정보 3필드(익명/가명/개인정보 포함여부, `LS_DATA_SRC.ANONY_INCL_YN`/`PSDO_INCL_YN`/`PRVC_INCL_YN`)를 콘텐츠 해시 원천에 편입했다. 이 편입 이전에 승인·export 되어 있던 영상을 **무수정 재동결**하면 해시가 1회 달라져 `v{n+1}` 이 한 번 재산출된다(1회성 churn). 이후에는 값이 불변이면 해시가 안정되어 다시 멱등 skip 으로 수렴한다(self-heal — 운영자 조치 불필요, 기존 버전 폴더 보존). ※ 승인 경로(force=true, R6)는 해시와 무관하게 매번 재산출되므로 churn 개념이 적용되지 않는다.

## 24.7 추적 원장 (LS_DATASET_EXPORT)

| 컬럼 | 내용 |
|------|------|
| `EXPORT_SN` | PK |
| `DATA_RAW_SN` | 대상 영상(RAW_SN) |
| `EXPORT_VER_NO` | 산출 버전(≥1). UK(DATA_RAW_SN, EXPORT_VER_NO) |
| `EXPORT_PATH_NM` | **영상 루트 경로**(`{dirname(RAW_FILE_PATH_NM)}/{RAW_SN}`) — **버전 루트가 아니다.** 관제가 `v1`·`v2` 를 한 경로 아래에서 보고 골라야 요구사항의 *버전별 비교·복구*가 성립하기 때문. 값은 **절대경로 그대로 저장·사용**하며 조회 시 재계산하지 않는다(롤백 전략 전환 후에도 기존 행이 깨지지 않도록) |
| `EXPORT_STTS_CD` | `PENDING → SUCCEEDED\|PARTIAL\|FAILED` (아래 상태 의미) |
| `FRAME_CNT` | 산출 프레임 파일 수(orgnl + deid 합산) |
| `CONTENT_HASH` | 산출 시점 콘텐츠 해시(SHA-256, 멱등 판정 키) |
| `REG_DT` | 생성 일시 |

**상태 의미**: `PENDING`=채번 후 파일 쓰기 전(예약), `SUCCEEDED`=전 프레임 산출, `PARTIAL`=일부 프레임 skip(원천 이미지 부재 등), `FAILED`=한 장도 못 씀 또는 파일 쓰기 예외.

**stale PENDING 정리 (크래시 복구)**: `insertNextVersion` 이 PENDING 레코드를 커밋한 뒤 파일 쓰기/상태 마감 전에 프로세스가 크래시하면 그 레코드가 `PENDING` 으로 영구 고착된다. 주기 Quartz 잡 `DatasetExportPendingSweepJob`(기본 10분 간격, `@DisallowConcurrentExecution` + PostgreSQL JobStore 클러스터 락으로 2노드 중 1노드만 tick)이 `REG_DT` 가 `stale-minutes`(기본 30분) 이전인 PENDING 을 `FAILED` 로 마감한다. **파일은 삭제하지 않고 상태만 회수**한다(잔재는 `{RAW_SN}/v{n}/` 안에만 남고 **원본 영상과 형제 디렉터리라 원본에 영향이 없으며**, 다음 산출은 `v{n+1}` 로 진행). 정상 산출은 수 초 내 완료되므로 30분 임계를 넘는 PENDING 은 크래시 잔재뿐이다. 초장기 산출이 sweep 으로 FAILED 마킹돼도 이후 완료가 `SUCCEEDED` 로 최종 수렴한다(last-writer-wins, 무해). `stale-minutes` 오설정(0/음수)은 안전 기본값 30 으로 폴백한다.

## 24.8 설정

| 설정 키 | 기본값 | 의미 |
|---------|--------|------|
| `authoring.dataset-export.base-strategy` | `co-locate` | 산출 base 전략. `co-locate`=원본 영상 디렉터리 하위(`dirname(RAW_FILE_PATH_NM)/{RAW_SN}`), `labeling-root`=구 고정 루트(롤백용). **플래그는 신규 산출의 base 선택에만 관여**하며 이미 기록된 `EXPORT_PATH_NM` 을 재해석하지 않는다 |
| `authoring.storage.raw-mount-roots` | (미설정 시 `raw-path`+`deidentified-path` 로 폴백) | **경로 가드용 고정 allowlist**(CWE-22). `dirname(RAW_FILE_PATH_NM)` 이 이 목록 하위여야 산출이 허용된다. 요청과 무관한 고정값이어야 가드가 유효하다 |
| `authoring.storage.labeling-path` | `./storage/labeling` | **구 고정 산출 루트 — `base-strategy=labeling-root` 롤백 시에만 사용** |
| `authoring.storage.raw-path` | `./storage/raw` | 원본 프레임 이미지 base(orgnl 복사 원천) |
| `authoring.storage.deidentified-path` | `./storage/deidentified` | 비식별 프레임 이미지 base(deid 복사 원천) |
| `authoring.dataset-export.enabled` | `true`(matchIfMissing) | 승인 시 파일 산출 트리거 토글 |
| `authoring.dataset-export.pending-sweep.enabled` | `true`(matchIfMissing) | stale PENDING 정리 Quartz 잡 등록 토글 |
| `authoring.dataset-export.pending-sweep.interval-sec` | `600` | sweep 실행 간격(초) |
| `authoring.dataset-export.pending-sweep.stale-minutes` | `30` | 이 분(minute)보다 오래된 PENDING 을 FAILED 로 회수(0/음수 오설정 시 30 폴백) |

## 24.9 관찰성 메트릭 (Micrometer)

산출 오케스트레이션은 종결 시점마다 메트릭을 발행한다. 메트릭은 전용 `DatasetExportMetrics`(`observability.metrics` 패키지, 기존 `*Metrics` 컴포넌트 관례) 로 캡슐화한다. 태그는 6개 고정 문자열만 사용(저카디널리티 — rawSn·경로 등 미포함).

| 메트릭 | 타입 | 태그 | 의미 |
|--------|------|------|------|
| `dataset.export.result` | Counter | `outcome` | 산출 종결 건수. outcome ∈ `completed`/`partial`/`failed`/`version_exhausted`/`idempotent_skip`/`no_input`. `idempotent_skip` 은 **재동결(force=false) 경로에서만** 발생 — 승인(force=true, R6) 경로는 발생하지 않음 |
| `dataset.export.duration` | Timer | `outcome` | 산출 1건 소요 시간(outcome 별) |
| `dataset.export.skipped_frames` | Counter | — | 원천 이미지 부재 등으로 건너뛴 프레임 총량 |

- **정확히 1회 기록**: `export()` 진입 시 `Timer.Sample` 시작 + outcome 지역변수(기본값 `failed`), 모든 종결 분기(조기 return 3종 + 파일결과 3종 + 예외)에서 `finally` 단일 지점이 timer stop + result counter 를 배타적으로 1회 기록한다. 계측 코드는 예외를 던지지 않아 "파일 실패가 승인 롤백 안 함" 계약이 불변이다.
- **outcome ↔ 레코드 정합**: 파일을 다 썼더라도 상태전이(`markSucceeded`)가 예외로 실패하면 레코드는 `FAILED` 로 마감되고 metric outcome 도 `failed` 다(다운스트림 View 는 SUCCEEDED 만 소비하므로 실효 결과와 일치).

## 24.10 코드 · 테스트

- 코드: `dataset/export/` — `DatasetExportService`(오케스트레이터) · `DatasetExportTxService`(REQUIRES_NEW DB 게이트웨이 + `sweepStalePending`) · `DatasetExportWriter`(파일 쓰기) · `DatasetExportPathResolver`(CWE-22) · `FrameSource`(원천 이미지·CWE-59) · `json/`(NIA 문서 빌더·매퍼) · `listener/DatasetExportBridge` · `AsyncDatasetExportRunner` · `DatasetExportPendingSweeper`. 스케줄러: `batch/scheduler/DatasetExportPendingSweepJob` · `DatasetExportPendingSweepTriggerConfig`. 메트릭: `observability/metrics/DatasetExportMetrics`.
- 테스트: `DatasetExportServiceTest`(판정 3분기·멱등·재채번·메트릭 outcome) · `DatasetExportPendingSweeperTest`/`DatasetExportPendingSweepJobTest`(sweep cutoff·하한폴백·예외삼킴) · `DatasetExportTxServiceTest`(멱등 baseline IN·sweep) · `DatasetExportServiceIT`(채번·상태·멱등, 파일 부재 환경) · `DatasetExportE2EIT`(실 이미지 fixture 기반 디스크 파일 산출 end-to-end — orgnl/deid 이미지+JSON 페어, 8키·description·좌표·anonymity, v1/v2 누적).
