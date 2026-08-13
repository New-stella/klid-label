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
| `info` | year·version(`1.3`)·description·date_created — ⚠ **`year` 는 검수완료(`RVW_CMPL_DT`) 연도**(2026-08-05 정정) |
| `dataset` | id(=RAW_SN)·name·path·(url=null) — ⚠ **`name` 은 `{이벤트명} 데이터셋 구축`**(2026-08-05 정정) |
| `licences` | 사용 라이선스(현재 private-use 1건) |
| `video` | 영상 메타 (§24.4 매핑표) |
| `image` | 프레임 메타 (아래) |
| `annotations` | 라벨 목록 — 타입별 bbox/polygon/keypoints 중 하나 (객체 인스턴스 배열) |
| `event` | **이벤트 단위 VQA/CoT (§24.3.1)** — 프레임별 문서마다 동일 영상(RAW_SN) 단위 블록을 최상위 키로 첨부. `caption`/`evidence` 는 후보 키 `c1..cn` 객체. (JSON 출력 키는 정본 샘플 정합으로 `event`, 위치는 `video` 다음 유지. 내부 클래스/필드명은 `eventAnnotation` 유지) |
| `categories` | 사용된 라벨 마스터(`LS_LABEL`) → 카테고리 |
| `type` | `instances` (고정) |

`@JsonInclude(ALWAYS)` 로 값이 null 인 **필수 키도 항상 직렬화**되어 관제 데이터마트 스키마와 정합한다.

#### ★ 2026-08-05 어노테이션 정정 3건 (NIA 표준 대조) — 되돌리지 말 것

| 키 | 구 동작 (폐기) | 현행 | 사유 |
|---|---|---|---|
| `dataset.name` | **파일명** | **`{이벤트명} 데이터셋 구축`** | 뷰 `V_COMPLETED_VIDEO.DATST_NM` 과 **같은 규칙**이며 가드가 상수가 아니라 *뷰 SELECT 결과 vs 빌더 산출물*을 비교한다. `EVNT_NM` 이 `null` 이면 **`dataset.name` 도 `null`**(지어내지 않음 — 뷰와 같은 시맨틱) |
| `info.year` | **`LocalDate.now()`** | **검수완료(`RVW_CMPL_DT`) 연도** | 구 동작은 **연말/연초 재export 에서 값이 바뀌어** 멱등이 깨졌다. `RVW_CMPL_DT` 가 `null` 이면 `info.year` 도 `null` |
| `video.event_id` | **`EVNT_TYPE_CD`** | **인입 `EVNT_ID`** | 축이 다른 값을 넣던 결함. 미제공 시 **폴백 없이 `null`** |

> **동결 스냅샷 `AI_CRT_YN` 도출식도 같은 회차에 정정**됐다 — 구 식은 `ORGNL_RAW_SN != null`(파생 여부)로만 계산해
> `SRC_TYPE='GENERATED'` 인 **원본을 `N` 으로 오동결**했다. 이제 `LsDataRaw.genAiYnOf()` 를 재사용해
> **뷰 SQL(`GEN_AI_YN`) · 완료 통지(`gen_ai_yn`) · 동결 스냅샷** 3경로가 같은 판정을 공유한다.
> ⚠ 뷰는 애초에 이 컬럼을 읽지 않고 `SRC_TYPE` 에서 직접 도출하므로(설계 D2) **기존 행 백필은 불필요**하다.

### image 블록 주요 필드
- `file_name` = **`{FRM_NO}.jpg` 4자리 zero-pad**(`String.format("%04d", frmNo)` — `ExportFileNaming` 단일 지점. 10000 이상은 자연 확장). 구 `frame-{n}.jpg` 접두사 형식은 **폐기**. **통지 `changed_items`·디스크 실제 파일명·JSON `file_name` 3자가 항상 일치**해야 한다.
- `frame_num` = **`LS_DATA_SRC.VDO_FRM_NO`(실제 영상 디코더 프레임 위치)**. 구 `FRM_NO`(추출 순번) 아님 — 두 값은 별개 컬럼이다(예: 추출 순번 2 ↔ 실제 프레임 20). **`VDO_FRM_NO` 가 null 이면 `frame_num` 도 null 로 내보낸다**(`FRM_NO` 폴백 금지 — 의미 혼선).
- `width`/`height` = 영상 메타 해상도.
- `description` = `LS_DATA_SRC.FRM_EXPLN`(작업자 수기 프레임 설명). 미입력 시 null.
- **개인정보 3필드(`anonymity` / `pseudonymity` / `privacy_included`) — `orgnl` 은 정책 상수 `N`/`N`/`Y`, `deid` 는 프레임 수동값 우선(미입력 시 `Y`/`N`/`N`)** (★2026-08-04 확정, §24.3.3). 파생영상(증강·해상도)만 `orgnl` 이 `null` 이다.
- (`orign_file_name` 은 **정본 샘플에 없어 제거**됨 — 원천 소스 파일 basename 은 더 이상 image 블록에 노출하지 않는다.)

> **orgnl ↔ deid JSON 델타(소비측 주의)**: 같은 프레임의 원본/비식별 JSON 은 **개인정보 3필드(`anonymity`·`pseudonymity`·`privacy_included`)** 가 다르다(§24.3.3 — **원본=관제 인입값(video)·정책 상수(image) / 비식별=수동값 또는 기본상수**). ⚠ 소비측은 `orgnl` 의 3필드를 **값으로 파싱**해야 한다 — **구 서술 "`orgnl` 은 항상 null" 은 폐기**(2026-08-04)이며, 이제 `null` 이 남는 것은 **파생영상**(원천 영상 자체가 없음)과 **관제가 보내지 않은 `video` 원천 필드**뿐이다(키는 항상 유지된다). **`file_name`·`frame_num`·좌표·`annotations`·해상도·`description` 은 동일**하며, 두 벌은 폴더 경로 `orgnl/`·`deid/` 로 구분된다. 즉 "이미지 파일명이 다르다"가 아니라 "**폴더와 개인정보 3필드가 다르다**"가 정확한 서술이다.

### 24.3.3 개인정보 3필드 — 산출종류 × 블록의 4칸 (★2026-08-04 확정, 원천 축 전환)

export 는 `orgnl`/`deid` **두 벌**로 나가고 각 문서에 `video`(영상 단위)·`image`(프레임 단위) 두 블록이 있다. 3필드는 이 **4칸마다 조달 방식이 다르며**, 판정 로직의 단일 원천은 `dataset/export/ExportPrivacyPolicy` 한 곳이다(복제 금지).

| 블록 | 원천(`orgnl`) | 비식별(`deid`) |
|---|---|---|
| `video`(영상 단위) | **관제 인입값** `LS_DATA_INGEST.ANONY_INCL_YN`/`PSDO_INCL_YN`/`PRVC_INCL_YN`(**V166** 신설 · **V170** fail-closed DB DEFAULT `N`/`N`/`Y`) 그대로. 관제가 보내지 않은 필드는 **`null` 유지**(지어내지 않는다) | 영상 단위 수동값 `LS_DATA_RAW.*_INCL_YN`(**V163**) 우선, 미입력 시 `Y`/`N`/`N` |
| `image`(프레임 단위) | **정책 상수 `N`/`N`/`Y`**(`ExportPrivacyPolicy.ORGNL_DEFAULT_*`) — 프레임 단위 원천 판정 **데이터가 존재하지 않기** 때문 | 프레임 단위 수동값 `LS_DATA_SRC.*_INCL_YN`(**V130**) 우선, 미입력 시 `Y`/`N`/`N` |

- **파생영상(증강·해상도)은 원천 축이 `video`·`image` 모두 `null`** 이다 — 인입값도 상수도 싣지 않는다. 파생 비디오는 부모의 **비식별본**을 복사해 만들어져 "비식별 처리 전 원천"이라는 대상 자체가 없다(결손이 아니라 정상). 조달 분기는 `SourcePrivacyMeta.sourceExists()` 이며 `DatasetExportTxService` 가 영상 행으로 명시 판정한다(리포지토리 술어와 이중화).
- **왜 원천에 값을 채우는가(왕복 근거)** — export JSON 은 **다시 읽혀 적재되는 자산**이 된다(데이터마트 업로드 기능 예정). 원천이 `null` 이면 재적재 시 **"판정 안 함"과 "값 유실"이 구분되지 않는다.**
- **두 블록의 원천값은 같아 보여도 출처가 다르다 — 통합 금지**: `video`=관제가 실제로 판정해 보낸 값 / `image`=정책 상수. 관제가 `PRVC_INCL_YN='N'` 을 보내면 `video=N` / `image=Y` 로 **갈리는데, 모순이 아니라 입도가 다른 사실**이다.
- **`LS_DATA_SRC` 에 원천용 DB DEFAULT 를 걸지 않는다** — 그 3컬럼은 **한 벌인데 두 축이 공유**한다(`image` 블록의 원천·비식별이 같은 컬럼을 본다). 원천 기본값(`N`/`N`/`Y`)을 DB DEFAULT 로 걸면 비식별의 "미입력" 상태가 사라져 `deid.anonymity` 가 `Y`→`N` 으로 **뒤집힌다**. 그래서 프레임 축 원천값은 스키마가 아니라 **정책 클래스의 상수**로 둔다.
- **★비식별 축은 INSERT 시점에 실제 값(`Y`/`N`/`N`)을 적재한다** (2026-08-04) — `LsDataRaw`(`@Builder` 생성자)·`LsDataSrc`(팩토리)에서 채우고, 수정은 **기존 라벨링 화면 경로**(`PUT /v1/videos/{rawSn}/privacy-meta` · `PUT /v1/frames/{srcSn}/privacy-meta`)를 그대로 쓴다(새 화면·새 API 없음). ⚠ **DB 컬럼 DEFAULT 가 아니라 애플리케이션 팩토리인 이유**: 두 엔티티에 `@DynamicInsert` 가 없어 **Hibernate 가 모든 컬럼을 명시 INSERT**(값이 없으면 명시적 NULL)하므로 DEFAULT 가 주 적재 경로에서 **적용될 수 없다**. 반대로 인입(`LS_DATA_INGEST`)은 **관제가 우리 코드를 거치지 않고 직접 INSERT** 하므로 DB DEFAULT 가 유일한 수단이다 — 두 축이 다른 기법을 쓰는 기준은 **"누가 INSERT 하는가"** 다.
- **기존 행 백필 없음**(인입 축·비식별 축 공통) — ①소급 UPDATE 는 "관제/사람이 실제로 판정한 값"과 영구히 구분되지 않는 **사실 날조**이고 ②전 행 rewrite 는 2노드 무중단 배포에 불리하며 ③백필하면 레거시 행의 "아직 재판정하지 않았다"(`DERIVED`) 신호가 사라진다. 레거시 행은 프리필(비식별)·`null`(원천 `video`)로 남는다.
- ⚠ **잃는 것(인지·수용 — 되돌리지 말 것)**: DEFAULT/적재값이 생기면 **"미송신"과 "실제로 N 판정"**, **"적재 기본값"과 "사람이 고른 값"** 이 값만으로는 구분되지 않는다. 구분이 필요해지면 "수신 여부" 별도 컬럼이 필요하다(값 자체로는 복원 불가).
- **★비식별 누락 신고는 이 3필드를 리셋하지 않고 보존한다** (2026-08-04 반전 — §24.3.4). 따라서 `null` 이 남는 경로는 **레거시 행 하나뿐**이며, 그럼에도 `ExportPrivacyPolicy` 의 비식별 프리필 상수(`DEID_DEFAULT_*`)는 **안전망으로 존치**한다(제거하면 레거시 행의 export 가 빈 값이 된다).
- **두 블록의 값이 다를 수 있고 그것은 모순이 아니다** — `video.privacy_included=Y` / `image.privacy_included=N` 은 **"영상 어딘가엔 개인정보가 있지만 이 프레임엔 없다"** 는 서로 다른 입도의 사실이다. 판정 *로직*을 복제하지 않는다는 원칙(단일 판정기)은 그대로다.
- **GET 응답은 수동값 우선 + 기본상수 프리필 + `*Source`(MANUAL/DERIVED) 병기**다. 기본상수를 화면이 하드코딩하지 않게 하려는 것이며, 상수의 단일 원천은 `ExportPrivacyPolicy.DEID_DEFAULT_*` 다.
  - ⚠ **의미 축소(2026-08-04) — 그러나 셋 다 존치한다**: 위 "INSERT 시점 적재" 이후 신규 영상·프레임은 항상 저장값을 갖고 프리필을 타지 않아 `*Source` 가 늘 `MANUAL` 로 보인다. 프리필·`*Source` 가 실제로 의미를 갖는 것은 **레거시 행**뿐이며, 그럼에도 ①레거시 행이 실재하고 ②응답 필드 삭제가 FE 계약 파괴라 **① `*Source` 응답 필드 ② GET 의 `NULL`→상수 프리필 ③ FE 의 미터치 필드 `null` 전송 규약** 셋을 그대로 유지한다. **구 존치 근거("비식별 신고 리셋 직후에도 `NULL` 이 생기므로 `DERIVED` 가 '아직 재판정 안 함' 신호로 살아난다")는 폐기**됐다 — 신고가 더 이상 리셋하지 않는다(§24.3.4). 결론(존치)만 그대로다.
  - ⚠ **프리필 왕복 주의**: `DERIVED` 프리필을 그대로 PUT 으로 되돌려 보내면 **상수가 사람의 판정(MANUAL)으로 승격**된다. FE 는 사용자가 직접 고르지 않은 필드를 `null` 로 전송한다(`VideoPrivacyMetaPanel.resolveField` / `EnvironmentMetaPanel` 동일 규율). BE 는 전송값의 출처를 알 수 없어 이를 막지 못한다(알려진 한계).
  - "누가 실제로 손댔나"는 컬럼이 아니라 **`LS_TASK_EVENT_LOG` 감사**가 독립적으로 담당한다(INSERT 자동 채움은 감사를 남기지 않고, 사람의 PUT 만 남긴다).
- **저장소는 라이브 `LS_DATA_RAW`이며 동결 스냅샷 컬럼을 두지 않는다** — 비식별 축 3필드의 소비자는 export JSON 하나뿐이고(데이터마트 뷰에 없음) export 는 라이브 raw 를 이미 로드한다. 대신 ①승인 후 수정 시 `TaskModifiedEvent(exportRegenerated=true)` 로 새 버전 폴더 전량 재생성 ②`LabelContentHasher` 입력에 편입(멱등 skip 방지)으로 동기화를 완결한다. 근거는 `VideoPrivacyMetaService` 클래스 주석.
  - **원천 축(관제 인입)도 같은 세트가 필요하다** — `LabelContentHasher` 가 조건부 블록 `SPRV` 로 이 값을 해시에 편입한다(값이 하나도 없으면 append 하지 않아 기존 해시 유지 = 하위호환). 빠지면 관제가 판정을 정정 재송신해도 재승인이 멱등 skip 되어 **저장은 바뀌었는데 파일은 옛 값으로 고착**된다. 단 **우리 화면에 원천 축 쓰기 경로가 없으므로 `TaskModifiedEvent` 발행 지점은 없다**(비식별 축은 이벤트+해시 세트를 모두 갖춘다). `image` 블록 원천값은 **정책 상수**라 영상별로 달라지지 않으므로 해시 입력이 아니다.
- **★파생영상(증강·해상도)은 부모의 영상 축 판정을 생성 시점에 계승한다** (2026-08-03 DEV_FIX, 유지). `LsDataRaw.createFromAugment`/`createFromResolution` 이 촬영환경 복사와 **같은 지점**에서 `*_INCL_YN` 3컬럼을 복사한다(`copyPrivacyMetaFrom`). 계승하지 않으면 파생 프레임은 부모 프레임 값을 복사받는데(`AugmentExtractPersist`/`ResolutionPersistService`) 영상 축만 미입력이라, 같은 `deid` 문서에서 `image="Y"` / `video="N"` 이 난다 — 위에서 정당화한 방향("영상엔 있지만 이 프레임엔 없다")의 **역방향이라 성립 불가능한 조합**이며 실질은 개인정보 잔존의 **과소 신고**다. 복사는 **생성 시점 1회**이고 이후 부모 정정은 파생으로 재전파하지 않는다(촬영환경과 동일 시맨틱).
  - ⚠ **부모가 미입력(레거시)이면 파생은 `null` 이 아니라 적재 기본값(`Y`/`N`/`N`)으로 시작한다** (2026-08-04 — "값은 항상 실재한다" 규약을 파생에서도 유지). 영상 축·프레임 축 모두 동일하며, export 산출값이 프리필 상수와 같아 **파일 내용은 달라지지 않는다**. 구 기대 "부모 null 이면 파생도 null" 은 폐기.
  - 회귀 가드: `LsDataRawTest.증강_파생본이_부모의_영상단위_개인정보_수동값을_계승한다`(+해상도) · `LsDataRawTest.부모가_개인정보_미입력이면_파생본은_적재_기본값을_쓴다` · `LsDataSrcTest.파생_프레임은_부모값을_계승하고_부모_미입력이면_적재_기본값을_쓴다` · `NiaJsonBuilderTest.derivativeInheritsVideoAxisPrivacy`.
- **★PUT 은 동시 승인과 직렬화된다** (2026-08-03 DEV_FIX — 구 서술 "경합 창 자체가 없다"는 **오류**). 동결(`materialize`)이 이 컬럼을 읽지 않는 것은 사실이나 **실제 소비자인 export(`DatasetExportTxService`)가 라이브 raw 를 직독**하므로, 창은 사라진 게 아니라 동결→산출로 옮겨간 것이었다(PUT 이 PENDING 을 읽어 통지 미발행 확정 → 승인 커밋 → async export 가 구 스냅샷의 null 을 `v1` 에 기록 → 재산출 트리거 없음 → 영구 "개인정보 없음"). 지금은 승인 판정 전에 `flush`(raw 행락) → **rawSn advisory 락**(`acquireRawLock` — 승인의 `materialize` 와 동일 락) 순서로 잠그고, 상태는 **잠금 없이** 읽는다(`EnvironmentMetaService` 와 완전히 같은 형태). ⚠ **`LS_RAW_DATA_STATUS` 를 `FOR SHARE` 로 잠그는 방식은 폐기됐다 — 교착(40P01)** (2026-08-03 2차 정정): 그 방식은 이 트랜잭션을 `raw → status` 순서로 만드는데, `BatchTransitionService` 가 같은 `REQUIRES_NEW` 트랜잭션 안에서 `status`(조건부 벌크 UPDATE) → `raw`(dirty checking) **역순**으로 잠근다. 1차 근거였던 "승인 경로는 raw 를 안 잠근다"는 참이지만 **교착 상대가 승인이 아니라 배치**였고, 그 배치 진입점은 주기 배치·수동 재처리 등 모든 배치 시작에서 돈다. advisory 를 쓰면 이 트랜잭션이 만드는 간선이 `raw → advisory` 하나뿐이라 새 `raw → status` 간선이 생기지 않는다. 회귀는 정적 가드(`LockOrderGuardTest`)가 막는다.
- **★PUT 은 비식별 신고 구간에서 412 로 차단된다 — 유지, 근거만 교체** (2026-08-04. GET 은 차단하지 않는다). **현행 근거**: 신고 구간은 **"비식별이 잘못됐다"고 알려진 구간**이고, 그 잘못된 비식별본 위에서 내린 개인정보 판정을 이 구간에 새로 쓰면 resolve 후 재산출 때 그 값이 그대로 관제로 나간다. 라벨 저장도 **2026-08-04 부터 같은 게이트가 412 로 차단**한다(C-ISSUE-22 — 구 서술 *"라벨은 작업락으로 409 차단"* 은 폐기: 작업락은 6h 만료 후 회수되는데 `'F'` 는 resolve 까지 남아 **저장만 열리는 창**이 있었다 → [08 §8.4](08-deidentification.md)). 개인정보 선언만 열려 있던 **비대칭**은 이로써 양방향 모두 닫혔다. GET 을 막지 않는 이유는 값 자체가 PII 가 아니고 막으면 화면이 뜨지 않기 때문이다. **게이트는 영상 축과 프레임 축 PUT 양쪽에 건다**(`PUT /v1/frames/{srcSn}/privacy-meta` 단건 + `PUT /v1/frames/privacy-meta` 벌크 — 한쪽만 막으면 비대칭을 없앤 게 아니라 옮긴 것이다). 촬영환경 PUT 은 **PII 축이 아니라서** 제외한다. 판정은 단일 원천 `DeidentReportGate`(`LabelAccessGuard.requireNotUnderDeidentReport`)만 쓰고 컨트롤러가 복제 보유하지 않는다.
  - ⚠ **구 근거(2026-08-03) → 폐기**: *"신고 접수가 이 3필드를 재판정 대상으로 NULL 리셋하는데, 같은 구간에 배정자가 PUT 으로 옛 판정을 되돌리면 resolve 후 그 값이 관제로 나간다"* + *"신고는 두 축을 함께 리셋하므로 영상 축만 막으면 우회가 프레임 축으로 남는다"*. 신고가 더 이상 리셋하지 않으므로(§24.3.4) 이 근거는 성립하지 않는다. **게이트 자체는 리셋 여부와 무관하게 성립하므로 제거하지 말 것** — 위 현행 근거가 단독으로 지탱한다.
- **★영상 축 변경은 행 단위로 감사된다** (2026-08-03 2차) — `LS_TASK_EVENT_LOG` 에 `PRIVACY_META_UPDATE`(저장) 1행(actor·시각·사유 고정 문구). **판단값(Y/N)은 남기지 않는다**(CWE-359 — 개인정보 유무 자체가 민감 신호이고 작업 이력 화면에 노출된다). 라벨 이력(`LS_DATA_LBL_HSTRY`)은 `SRC_SN NOT NULL` 인 프레임 스코프라 영상 축 행을 담지 못할 뿐이며, rawSn 스코프 감사 축은 원래부터 있었다.
  - ⚠ **`PRIVACY_META_RESET`(신고 리셋 감사) 은 신규 발생이 0 이다** (2026-08-04 리셋 폐기). 이벤트 타입 상수와 팩토리(`LsTaskEventLog.privacyMetaReset`)는 **이미 적재된 과거 행 판독용으로 존치**하며 삭제하지 않는다. 프레임 축 리셋 감사(`LS_DATA_LBL_HSTRY` 행 단위 이력)도 동일하게 신규 발생 0 이다.

#### ★ 폐기된 구 정책 이력 — 되돌리지 말 것

이 저장소는 **철회된 정책이 되살아나는 것**이 반복 사고 패턴이라 경위를 지우지 않고 남긴다.

| 회차 | `ORIGINAL`(원천) | `DEIDENTIFIED`(비식별) | 상태 |
|---|---|---|---|
| 구 정책 ①(2026-07-31) | 개인정보 있음(`anonymity=N`, 가명·개인정보는 `PRVC_TYPE_CD`/`PRVC_YN` 파생) + 프레임 수동 override 허용 | 상수 `Y`/`N`/`N` 고정, **수동값 무시** | **폐기** |
| 구 정책 ②(2026-08-03) | **3필드 모두 `null`**(판정 안 함) | **수동값 우선**(미입력 시 상수) | **원천 축만 폐기** |
| **현행(2026-08-04)** | `video`=**관제 인입값** / `image`=**정책 상수 `N`/`N`/`Y`**(파생영상만 `null`) | 수동값 우선(미입력 시 상수) — **두 번의 반전에도 불변** | 유효 |

- **구 정책 ② 의 폐기 사유**: 근거였던 *"원천영상은 비식별 처리 전이라 판정이 성립하지 않는다"* 가 두 가지로 무너졌다 — ①관제가 원천 판정을 **무조건 채워 보내며**(2026-08-04 사용자 확정) 그 수신 통로가 **V166** 으로 실재하게 됐다 ②export JSON 이 **재적재되는 왕복 자산**이 되면서 `null` 이 "유실"과 구분되지 않는다.
- ⚠ **단 구 정책 ② 의 "값을 지어내지 않는다" 조항은 살아 있다** — `video` 원천 축에서 **관제가 보내지 않은 필드는 여전히 `null`** 이다(상수로 메우지 않는다). 이 조항까지 함께 폐기하지 말 것.
- **구 정책 ① 이 `deid` 에서 override 를 막았던 이유**: 프레임 수동값을 `image` 에만 태우고 `video` 는 태울 원천이 없어 같은 `deid/0000.json` 안에서 `video.privacy_included="N"` / `image.privacy_included="Y"` 모순이 났다(적대검증 실행 재현). **왜 풀렸나**: 그 모순의 실체는 **"원천이 없어서 기본값인 video" vs "사실인 image"** 의 충돌이었고, 영상 단위 저장소(**V163**)가 생겨 두 블록 모두 사람이 입력한 사실을 읽으므로 소멸했다(구 주석이 스스로 적어 둔 해소 조건 충족).
- **`PRVC_TYPE_CD`/`PRVC_YN` 파생 소멸(유지)**: 이 두 컬럼은 export 3필드의 입력이 **아니다**. ⚠ 2026-08-04 정정 — 원천값이 생겼지만 그 원천은 **관제 인입값·정책 상수**이지 이 두 컬럼이 아니므로 결론은 그대로다. ★2026-08-03 DEV_FIX 로 **프레임 메타 GET 프리필에서도 폐기**됐고, 컬럼 자체는 **비식별 대상 판정(`needsDeidentify`)에만** 남는다.
- **★프레임 개인정보 패널 프리필도 `ExportPrivacyPolicy` 기본상수로 통일** (2026-08-03 DEV_FIX): 구 `FramePrivacyMetaService` 는 `PRVC_TYPE_CD=='ANONY' ? Y : N` 파생을 남겨 두고 있었는데, 업로드가 `PRVC` 고정(fail-closed)이 된 뒤로 **실질 모든 신규 영상에서 화면이 `anonymity=N`** 을 보여줬다. 같은 축의 deid export `image` 블록은 기본상수 `Y` 를 실으므로 **사용자가 보는 값 ≠ 파일 값**이었고, 영상 패널(기본 `Y`)과 프레임 패널(기본 `N`)이 같은 개념에 다른 기본값을 표시했다. 이제 두 패널 모두 `ExportPrivacyPolicy.DEID_DEFAULT_*` 를 **참조**한다(상수 복제 금지 — 이 결함 자체가 복제의 사례였다).
- 회귀 가드: `ExportPrivacyPolicyTest`(원천/비식별 축 교차 금지·파생 null·인입 상수 정합 등 16건) · **`ExportPrivacyPolicySingleSourceGuardTest`**(프로덕션 소스 스캔 — 상수 정의 단일 위치 + 매퍼 2종의 `resolve*` 3회 호출 + 화면 프리필 2종의 상수 참조) · `NiaJsonBuilderTest.원천_문서는_video는_인입값_image는_정책상수로_채워진다`/`원천_image블록은_프레임_수동값에_영향받지_않는다`/`파생영상은_원천축이_video_image_모두_null_이다`/`deidReadsPerAxisManualValues`/`bothBlocksShareSingleDecisionMaker` · `VideoMetaMapperTest` · `DatasetExportE2EIT.privacyFieldsOnlyInDeidOnDisk`(디스크 orgnl JSON 이 원천 축 판정을 싣는다)/`deidJsonCarriesPerAxisManualPrivacyOnDisk` · `LabelContentHasherTest.원천축_개인정보가_바뀌면_해시가_달라진다`/`원천축_값이_없으면_기존_해시가_유지된다`/`videoPrivacyMetaChangeChangesHash` · `LsDataRawTest`/`LsDataSrcTest.비식별_3필드는_적재_시점에_YNN_으로_채워진다`.

### 24.3.4 ★비식별 누락 신고는 개인정보 3필드를 리셋하지 않는다 (2026-08-04 확정, 정책 반전)

신고 접수는 작업락 + `DE_IDNTF_YN='F'` 전이만 수행하고 **라벨도 개인정보 3필드도 보존**한다. 해제(resolve) 후 작업자는 **기존 판정을 그대로 이어서** 진행한다.

| 축 | 구 정책(2026-08-03) → **폐기** | 현행(2026-08-04) |
|---|---|---|
| 프레임 축 `LS_DATA_SRC.*_INCL_YN` | `resetPrivacyMetaByRawSn` 로 전 프레임 `NULL` 리셋 + `LS_DATA_LBL_HSTRY` 행 단위 감사 | **보존**(리셋 없음) |
| 영상 축 `LS_DATA_RAW.*_INCL_YN` | `changePrivacyMeta(null,null,null)` + `LS_TASK_EVENT_LOG PRIVACY_META_RESET` 감사 | **보존**(리셋 없음) |

- **구 근거(기록 보존)**: *"그 판정은 비식별이 잘못된 영상에서 내려진 것이라 재판정 대상이고, 남겨두면 재비식별 후에도 옛 판정이 export 에 stale 로 실린다(CWE-359)."*
- **폐기 사유**: **라벨 보존 정책(2026-07-27)과 같은 취지** — 신고는 "비식별이 잘못됐다"는 신호일 뿐 작업 결과를 폐기할 근거가 아니며, 사람이 입력한 개인정보 판정도 라벨과 같은 작업 결과다.
- **stale 우려를 무엇이 대신 막는가**: 신고 구간에는 **export 산출 자체가 보류**되고(§24.9 `deident_blocked` — 통지도 함께 보류), 해제 시 `DeidentReportResolvedEvent` 로 **새 버전 전량 재산출 + 관제 재통지**가 트리거된다. 해제 후 판정을 고쳐야 하면 기존 화면(`PUT /v1/videos|frames/**/privacy-meta`)으로 정정한다.
- **파급 — "파생 프레임 개인정보 cross-stale" 논의가 소멸**했다(구 §24.4 말미 백로그). 부모→기존 파생 캐스케이드 **리셋**이 문제였는데 리셋 자체가 없어져 대상이 존재하지 않는다. "기존 파생은 원본 신고와 무관하게 독립 취급"이라는 결론은 그대로다(파생은 신고 접수 자체가 412 거부 → [08 §8.4](08-deidentification.md)).
- 회귀 가드: `DeidentReportServiceResetIT.신고_report실행후_커밋조회시_개인정보3필드_보존_및_RAW_DE_IDENT_YN_F_실제반영_라벨보존` · `DeidentReportServiceResetIT.신고해도_개인정보_리셋_감사이력이_생기지_않는다`.

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
| `weather` | **LS_DATA_RAW.WTHR_NM(수동값) → 스냅샷 WTHR_NM** | 촬영환경 수동 저장값 우선(§24.4.1). **관제 미수신 — 수동 입력이 유일한 원천** |
| `coordinates` | WGS84_LAT,WGS84_LOT | |
| `cctv_name` | CCTV_NM | |
| `anonymity` | **orgnl=관제 인입값 `LS_DATA_INGEST.ANONY_INCL_YN`(미송신 시 `null`) / deid=영상 단위 수동값(미입력 시 `Y`)** | §24.3.3 |
| `pseudonymity` / `privacy_included` | **orgnl=관제 인입값(미송신 시 `null`) / deid=영상 단위 수동값(미입력 시 `N`/`N`)** | §24.3.3. video 원천은 `LS_DATA_INGEST.*_INCL_YN`(V166/V170), video 비식별은 `LS_DATA_RAW.*_INCL_YN`(V163), image 는 원천=정책 상수·비식별=`LS_DATA_SRC.*_INCL_YN`(V130). **파생영상은 원천 축이 `null`** |
| `event_id` | **인입 `LS_DATA_INGEST.EVNT_ID`**(예 `ABA_0001`) | ⚠ **2026-08-05 정정** — 구 원천 `EVNT_TYPE_CD` 는 **축이 다른 값**(유형코드)이라 결함이었다. 관제 `video.event_id` 와 같은 축인 `EVNT_ID` 로 교체. **미제공 시 폴백 없이 `null`**(지어내지 않는다) |
| `event_name` | EVNT_NM | |
| `time_of_day` / `season` | **LS_DATA_RAW.DAY_NGT_CD / SESN_CD(수동값) → 스냅샷 DAY_NGT_CD / SESN_CD** | 촬영환경 수동 저장값 우선. 둘 다 미입력이면 **null(미상)** — 촬영일시 추정 안 함(§24.4.1) |
| `vd_description` | **① `LS_DATA_META['vlm.description']`(외부 VLM verify 서술) → ② `manual-timeseries`(사람이 직접 쓴 전문) → ③ 보존된 레거시 구간 행(`0-8`·`8-16` …)을 `start_sec` 오름차순 이어붙임 → ④ 없으면 `null`** | ⚠ **2026-08-06 신설(@req R10)** — 구 동작 "항상 null 하드코딩" **폐기**. 판정 단일 원천 `VlmDescriptionPolicy`(§24.4.3) |
| `type`, `pixel`, `frames`, `license_id`, `og_cd`, `cctv_height`, `cctv_azimuth`, `cctv_mng_no`, `event_log` | — | **미보유 → null** (키 유지) |

> **VQA/CoT 위치 정정**: 구 video 블록의 `cto`/`vqa` 플레이스홀더는 **필드 자체를 제거**했다 — 정본 샘플에 없으며, VQA/CoT는 영상 기술메타(video)가 아니라 **최상위 `event`**(§24.3.1)으로 분리한다. 정본 샘플에 있는 `vd_description`(영상 서술) 키는 추가했다. (확정·구현 완료)
>
> ⚠ 구 서술 **"`vd_description` 은 현재 원천 미보유 → null" 은 폐기**(2026-08-06) — 외부 VLM `verify` 콜백이 서술 전문을 `LS_DATA_META` 에 적재하면서 원천이 생겼다(§24.4.3).

### 24.4.3 `vd_description` — VLM 서술 조달 규칙 (@req R10, 2026-08-06 확정)

어노테이션 포맷 정의: *"이벤트에 대한 VLM 이 출력하는 간단한 상황묘사 내용"*.

| 우선순위 | 원천 | 값 |
|:--:|------|-----|
| 1 | `LS_DATA_META` 의 `vlm.description`(외부 VLM `verify` 서술 전문) | 그 값 |
| 2 | `manual-timeseries`(**사람이 직접 쓴 전문** — 라벨링 화면 시계열 패널의 신규 등록 슬롯) | 그 값 |
| 3 | 보존된 **레거시 구간 행**(구 describe 산출물, metaKey `{start_sec}-{end_sec}`) | **`start_sec` 숫자 오름차순**으로 개행 이어붙임 |
| 4 | 모두 없음 | **`null`** — 키는 유지(`@JsonInclude(ALWAYS)`), 값을 지어내지 않는다 |

> ⚠ **구 서술(2026-08-06 초판) "우선순위 2 = 레거시 구간, `manual-timeseries` 는 조달 무관 키" 는 폐기**. FE 시계열 패널(`TimeseriesSidePanel`)은 **편집 가능한 항목이 하나도 없을 때**(메타 0건 **또는 레거시 구간뿐**) `manual-timeseries` 신규 등록 슬롯을 띄운다 — 즉 사람이 그 칸에 상황묘사 전문을 직접 쓴다. 구 조달은 그 값을 무시하고 **편집조차 불가능한 옛 구간 이어붙임**을 대신 내보냈다(조용한 손실).
>
> - **왜 수동 전문이 레거시보다 앞인가**: 수동 전문은 **사람이 직접 쓴 것**이고 레거시 구간은 **폐기된 describe 자동 산출물**이다.
> - **왜 `vlm.description` 이 더 앞인가**: FE 는 그 키가 있으면 수동 슬롯을 **아예 띄우지 않으므로** 정상 상태에서 둘은 공존하지 않는다. 공존한다면 과거 데이터이며 그때는 현행 편집 대상인 전문이 최신이다.

- **판정 단일 원천은 `dataset/export/json/VlmDescriptionPolicy` 하나**다. 규칙을 매퍼·서비스·FE 로 복제하지 않는다. `manual-timeseries` **키 문자열**도 이 클래스(`MANUAL_TIMESERIES_META_KEY`)가 소유하며 BE 안에서 리터럴을 복제하지 않는다(정적 가드 `VlmDescriptionPolicySingleSourceGuardTest`). FE 미러는 `features/auto/metaKeys.ts` 로 언어 경계다.
- **정렬은 반드시 숫자다** — metaKey 를 문자열로 정렬하면 구간이 10개를 넘는 순간 `"10-18" < "8-16"` 이 되어 서술 순서가 뒤집힌다(FE 시계열 패널이 겪은 것과 같은 함정).
- **빈 문자열이 아니라 `null`** 이다 — `""` 는 "판정했는데 내용이 없다"는 거짓 사실이다. 공백뿐인 값은 미입력으로 다뤄 **다음 순위로 내려간다**(전문·수동 전문 공통).
- **자동 절단하지 않는다** — 이어붙인 결과가 길어도 자르지 않고(사일런트 손실 금지) 과대 길이는 관측 로그로만 남긴다.
- **구간형이 아닌 키(`video.*` · `vlm.accuracy` · 비규격)는 조용히 무시**하며 예외를 던지지 않는다(export 가 이 값 하나로 깨지지 않는다). `manual-timeseries` 는 무시 대상이 아니라 **우선순위 2** 다.
- **파생영상은 자기 `rawSn` 의 메타만 본다 — 조회 시점 부모 폴백이 없다.** 다만 파생 생성 시 `DerivedMetaCopier.copyMetaAndReviews` 가 부모 메타를 **키 필터 없이 물리 복사**하므로 파생은 자기 행으로 부모 서술을 **실제로 갖는다**(파생 비디오가 부모 비식별본의 복사본이라 서술도 유효). 폴백을 두지 않는다는 것은 **스냅샷 시맨틱**(이후 부모 정정은 재전파되지 않음)이라는 뜻이며, 실제로 복사가 없던 파생만 `null` 이다.
- **`vlm.accuracy`(일치도)는 export 에 넣지 않는다** — 화면 전용이다(R12, [09 §9.4-1](09-vlm-timeseries.md)).

**★ "저장은 됐는데 산출물이 안 바뀐다" 차단 — 두 조치는 세트다**

`vd_description` 이 export 입력이 된 이상, 값이 바뀌었을 때 산출물이 따라 바뀌어야 한다. 아래 둘 중 **하나만 하면 반쪽**이다(CLAUDE.md 「개인정보 보호」의 동일 교훈).

| 조치 | 내용 | 없으면 |
|------|------|--------|
| ① **콘텐츠 해시 편입** | `LabelContentHasher` 에 조건부 블록(마커 `VDSC`) 추가 | 재동결(`force=false`) 경로가 **멱등 skip** 되어 옛 서술 고착 |
| ② **재생성 트리거** | 승인 후 서술 변경 시 `TaskModifiedEvent(exportRegenerated=true)` | 재생성이 **아예 트리거되지 않음**(통지만 나감) |

> ⚠ **①은 현재 배포 형상에서 아무것도 게이트하지 않는다 (정직한 한계, 2026-08-06 확인)**. 해시가 게이트하는 유일한 지점은 `DatasetExportService.export` 의 `!forceRegenerate && isUnchangedFromLastExport()` 인데, **`force=false` 로 진입하는 프로덕션 경로가 0건**이다 — 유일한 후보 `DatasetExportBridge.onReExport` 가 소비하는 `DatasetReExportEvent` 는 **발행처가 없는 휴면 리스너**이고(코드가 스스로 그렇게 적어 뒀다) 승인·수정·회수 등 나머지 경로는 전부 `force=true` 다. 블록은 **미래 정합용으로 유지**하되(그 경로가 살아나는 순간 필요해진다), 무변경 재생성 억제를 이 해시가 해준다고 믿지 말 것 — 그것은 아래 ②의 **값 비교 가드**가 한다.

②의 발행 지점은 두 곳이며 **둘 다 "값이 실제로 바뀌었을 때만"** 발행한다.

| 경로 | 조건 |
|------|------|
| `VlmResultService.recheckIfApproved`(R13) | 승인 완료 영상의 `vlm.description` 이 **실제로 바뀐** 콜백(`applyResults` 의 `changed` 가드) |
| `MetaService.update` | **(조달 참여 키 = `VlmDescriptionPolicy.participates`) AND (값이 실제로 변경됨)** 인 항목이 하나라도 있을 때 |

- **키만 보면 안 되는 이유(CWE-770)**: 재생성은 `ControlNotifyDebouncer` 를 거쳐 **`force=true`** 로 위임되므로 위 ①의 해시 멱등 skip 을 **타지 못한다**. 즉 같은 값으로 저장을 반복하면 `v2·v3·v4…` 가 **이미지 2벌 전량 복사와 함께** 쌓이고(전 버전 보존 정책이라 삭제도 안 된다) 관제 통지도 매번 나간다. REVIEWER 가 저장 버튼을 여러 번 누르는 것으로 충분히 재현된다. FE dirty 체크에 의존하지 않는다 — 클라이언트가 임의 payload 를 보낼 수 있다.
- **무변경 저장 자체는 정상 성공(200)** 이고 통지도 발행한다. 억제 대상은 **재생성 플래그뿐**이다.
- `MetaService` 가 전량 `true` 가 아닌 이유: 디스크가 그대로인데 재생성을 걸면 관제가 안 바뀐 파일 수천 개를 헛 재픽업하고 저장소도 버전마다 증폭된다.
- ①의 조건부 블록(값 없으면 append 안 함)은 **하위호환**이다 — 서술 원천이 없는 기존 승인분의 해시가 안 바뀌어 전량 재산출이 일어나지 않는다. 값이 있는 영상은 해시가 바뀌는데 **산출 JSON 내용이 실제로 달라지므로**(구 산출물은 `vd_description` 이 항상 null) 갱신이 옳다.
- 회귀 가드: `VlmDescriptionPolicyTest` · `NiaJsonBuilderTest` · `LabelContentHasherTest` · `DatasetExportTxServiceTest` · `VlmResultServiceTest` · `MetaServiceTaskModifiedGuardTest` · `MetaControllerTest` · `VlmDescriptionPolicySingleSourceGuardTest`(mutation 실증 완료)

### 24.4.1 촬영환경(weather / time_of_day / season) — 수동값 우선

영상 단위 촬영환경은 작업자가 화면에서 직접 입력·정정할 수 있다(`GET|PUT /v1/videos/{rawSn}/environment-meta`, REVIEWER·WORKER(본인 배정)).

| 구분 | 규칙 |
|------|------|
| 저장 | `LS_DATA_RAW.WTHR_NM / DAY_NGT_CD / SESN_CD`(V130). PUT 은 **전체 교체** — 3필드를 항상 함께 전송하고, 생략한 필드는 수동값이 삭제된다 |
| 적재 시 관제값 채택 — **없음** (2026-08-01 정정) | 적재 주체 반전(`LS_DATA_INGEST`) 이후 **촬영환경 3필드는 인입 테이블에 존재하지 않는다**(설계 R6 — 수동입력 필드는 인입에서 제외). 따라서 적재는 촬영환경을 **채우지 않고 null(미상)로 둔다.** 촬영일시 파생 추정값도 여전히 **적재하지 않는다**(E-ISSUE-42). ⚠ 구 서술("`HR_TYPE_CD`·`SESN_CD` 를 `ControlClipMetaResolver` 가 채택") 폐기 — 그 해석기는 읽을 소스가 사라져 삭제됐다. **`LS_DATA_RAW` 의 촬영환경 3필드는 이제 `EnvironmentMetaService`(작업자 수동 입력)만이 채운다** |
| **★ 날씨는 관제에서 받지 않는다 (2026-07-31 사용자 확정, 되돌리지 말 것)** | 관제 `WTHR_CD` 는 **코드값**(예: `CLEAR`)이고 저작도구 허용 어휘는 **한글 표시명**(맑음/흐림/비/눈/안개)이라 **대응표가 없다**. 대응표 없이 변환하면 그 변환 자체가 추정(self-fill)이므로, 날씨는 **저작도구에서 직접 입력**하기로 확정했다. 적재 주체 반전(`LS_DATA_INGEST`) 이후에는 **인입 테이블에 `WTHR_CD` 컬럼 자체가 없어** 이 결정이 구조적으로 고정됐다. `LS_DATA_RAW.WTHR_NM` 은 `EnvironmentMetaService`(작업자 수동 입력)만이 채운다 |
| 허용값 | weather=맑음·흐림·비·눈·안개 / time_of_day=DAY·NGT / season=SPRING·SUMMER·FALL·WINTER (화이트리스트, 그 외 400) |
| 조회 프리필 | 수동값이 있으면 그 값(`MANUAL`), 없으면 SHT_DT 파생값(`DERIVED`). weather 는 자동 출처가 없어 미입력 시 null. SHT_DT 가 null 이면 time_of_day·season 도 null |
| 승인 동결 | 검수 승인 스냅샷(`LS_DATASET_VIDEO_META`)에 **수동값만** 동결하고 **미입력이면 null(미상)을 유지**한다 — SHT_DT 기반 추정(self-fill)을 하지 않는다(2026-07-29, E-ISSUE-42. 구 정책 "미입력이면 SHT_DT 파생값 폴백" 폐기). 근거: 동결값은 export JSON·데이터마트 뷰로 **출처 구분자 없이** 전파돼 관제가 추정값을 관측값과 동일하게 소비한다(실증: 여름 18:00 촬영분이 구 규칙 `hour>=18 → NGT` 로 야간 오분류, 한국 7월 일몰 ≈ 19:50). 파생을 하지 않으므로 **동결된 non-null 값은 전부 관측값**(= 작업자 수동 입력. 적재 경로는 촬영환경을 채우지 않는다) → 출처 구분 컬럼(`ENV_SRC_CD` 등)이 불필요하다(스키마 변경 없음). ⚠ 단 이 단언은 **아래 "레거시 파생 동결값 정정 백필" 완료를 전제로 한 참**이다 — 백필 이전에는 파생 폐지 전에 동결된 `NGT`/`SUMMER` 행이 남아 거짓이었고, 관제는 그 값이 관측값인지 추정값인지 구분할 수 없었다. 해시: `DAY_NGT_CD`·`SESN_CD` 는 항상 해시 입력에 포함(키 유지, 값만 null)되고 `WTHR_NM` 은 **값이 있을 때만** 포함한다(미입력이면 키 생략 — 도입 이전 승인 영상이 내용 무변경인데도 재동결 시 새 해시로 중복 버전이 쌓이는 것을 막는 하위호환) |
| 동결 해시 영향(파생 폐지) | 촬영환경 미입력 영상은 동결값이 `NGT/SUMMER` → `null` 로 **실제 내용이 바뀌므로** 해시가 달라져 재승인 시 새 스냅샷 버전이 append 된다(정상 — 버전-per-내용 정합) |
| 레거시 파생 동결값 정정 백필 | 파생 폐지 **이전**에 이미 `NGT`/`SUMMER` 로 동결된 스냅샷은 남아 있으므로 **소급 정정한다**(2026-07-29 M-1. 구 정책 "기존 동결 행 백필은 하지 않는다" 폐기 — 그 행은 뷰로 노출될 뿐 아니라 `raw → meta` 폴백을 타고 **재-export 되는 새 버전 폴더에도 다시 기록**돼 오염이 계속 번졌다). **판별식**: 라이브 `LS_DATA_RAW` 의 수동값이 없는데 활성 스냅샷에 값이 있는 APPROVED 영상 — 수동 원천이 없는데 동결값이 있을 경로는 폐기된 파생 폴백뿐이라 파생값임이 결정적으로 증명된다. **날씨(`WTHR_NM`)는 대상이 아니다**(원래 파생 원천이 없어 non-null 이면 수동값). **반대 방향(raw non-null + 스냅샷 null)도 대상이 아니다**(승인 이후 수동 입력이 추가된 정상 케이스). 정정은 SQL UPDATE 가 아니라 **`materialize` 재동결**로 수행해 `SNPSHT_HASH`·활성 1건 불변식을 유지하고, 검수 완료 일시(`RVW_CMPL_DT`)는 승계한다. **이전 오염 행은 삭제하지 않고 `ACTIVE_YN='N'` 이력으로 남긴다**. 실행은 기동 시 1회(`DatasetVideoMetaBackfillRunner`, `!local`)이며 멱등(정정 후 판별식에서 빠짐) |
| 정정 백필의 재산출·통지 | 정정 1건마다 `TaskModifiedEvent(META_UPDATED, exportRegenerated=true)` 를 발행해 **export 를 새 버전 폴더로 전량 재생성한 뒤 통지**한다(순서 역전 시 관제가 구 버전 폴더를 픽업). 폭주 방지로 **1회 실행당 상한**(`authoring.dataset-video-meta.env-correction.max-per-run` / `DATASET_ENV_CORRECTION_MAX_PER_RUN`, 기본 200)을 두고 잔여분은 다음 기동에서 이어서 처리한다. 시작 시 총 대상 건수 / 종료 시 잔여 건수를 로그로 남긴다 |
| 정정 백필 수동 트리거 | 기동 러너가 `@Profile("!local")` 이라 **로컬 스택에서는 백필이 돌지 않으므로**(로컬 기동마다 APPROVED export 가 재생성되는 노이즈 회피 — 기존 정책 유지) 검증용 수동 트리거를 둔다. **dev 전용·REVIEWER 전용**(`@Profile("!prd")` 빈 게이팅 + `SecurityConfig` `/v1/dev/**` → `hasRole('REVIEWER')` + 핸들러 `@PreAuthorize`, 배포 표식은 `DevProfileGuard` 가 별도 축으로 차단). ①`GET /v1/dev/dataset-video-meta/shooting-env-correction-targets` = **대상 건수만**(dry-run, 정정 미수행) ②`POST /v1/dev/dataset-video-meta/shooting-env-corrections` = 실제 정정(응답 `corrected`/`remaining`/`completed`). 같은 URL 의 쿼리 파라미터 분기(`?dryRun=`) 금지 원칙에 따라 sub-resource 로 분리한다. 실행은 위 `max-per-run` 상한을 **그대로** 타며 상한 우회(전량 실행) 옵션은 없다 — 잔여가 남으면 재호출(멱등, 두 번째는 0건) |
| export | `NiaVideo.weather/time_of_day/season` 은 **raw 수동값 → 스냅샷** 순으로 채운다(다른 video 필드는 스냅샷 우선이라 순서가 반대). 영상 단위 값이라 ORIGINAL/DEIDENTIFIED 2벌이 항상 동일. **두 원천이 모두 미입력이면 값은 null** 이고, `@JsonInclude(ALWAYS)` 로 **키(`time_of_day`/`season`/`weather`)는 유지**되므로 관제/데이터마트 파서 계약은 깨지지 않는다 |
| 승인 후 수정 | 검수 완료(APPROVED) 영상의 촬영환경을 정정하면 **①동결 스냅샷(데이터마트 뷰) 재동결(materialize)** + **②export 폴더를 새 버전 `v{n+1}` 로 전량 재생성**을 함께 수행한다(Phase 5C — 구 정책 "export 폴더는 재산출하지 않고 다음 재승인 시 재산출"은 폐기: 재동결이 스냅샷만 갱신하고 파일은 옛 촬영환경으로 남으면 관제가 픽업하는 산출물과 뷰가 불일치했기 때문). 재산출은 `TaskModifiedEvent(exportRegenerated=true)` → 디바운스 flush 가 export 를 먼저 마친 뒤 통지를 내보내는 순서로 직렬화된다(§24.6 R6, [15](15-control-notify.md) §15.2). 재동결 시 **검수 완료 일시(`RVW_CMPL_DT`)는 최초 승인 시각을 보존**한다(편집 시각으로 덮지 않음 — `TASK_COMPLETED` "검수 완료 일시" 계약). 활성 스냅샷이 없으면 fail-safe skip |
| 파생영상 | 증강·해상도 파생본은 생성 시 부모의 촬영환경 수동값 3필드를 **복사**한다(같은 영상 소스이므로). 부모가 미입력이면 파생본도 null(미상) 이다. 복사는 **생성 시점 1회**이며 파생 생성 후 부모 촬영환경 수정은 파생본으로 **재전파하지 않는다**(스냅샷 시맨틱 — 의도) |
| 통지 | 검수 완료(APPROVED) 후 수정 시 관제 `TASK_MODIFIED`(META_UPDATED) 발행. 위 재생성이 트리거되는 경우 **export 성공(SUCCEEDED) 이후**에만 발송된다([15](15-control-notify.md) §15.2) |

> **미보유 필수 필드 null 정책 (소비측 주의)**: 위 "미보유" 필드는 저작도구가 원천 데이터를 보유하지 않아 **의도적으로 null** 이다. `@JsonInclude(ALWAYS)` 로 키 자체는 항상 존재하므로, 소비측은 "키 부재"가 아니라 "**값 null**"로 미보유를 판정해야 한다.

> **~~파생 프레임 개인정보 cross-stale 경계 (후속 백로그)~~ → [폐기 2026-08-04]**: 구 서술은 *"부모 영상에 비식별 누락 신고가 발생하면 부모 rawSn 의 프레임 3필드만 NULL 리셋되고 이미 생성된 자식 파생(다른 rawSn)의 복사값은 리셋되지 않는다(cross-stale). 부모→기존 파생 캐스케이드 리셋은 미지원이며 후속 백로그로 관리한다"* 였다. **신고 시 개인정보 3필드 리셋 자체가 폐기**(§24.3.4)되어 **캐스케이드 리셋의 대상이 존재하지 않으므로 논의가 통째로 소멸**했다 — 백로그도 함께 닫는다(`DeidentReportService.report` Javadoc 도 같은 취지로 정정됨).
> 남는 사실은 둘이며 **그대로 유효**하다: ① 프레임 개인정보 3필드는 파생 생성 시 부모 프레임 → 자식 파생 프레임으로 **생성 시점 1회 복사**된다(`AugmentExtractPersist`/`ResolutionPersistService` 의 `loadParentSrcs`. 부모가 미입력이면 적재 기본값 `Y`/`N`/`N` — §24.3.3). 신규 파생은 **복사 원자성 게이트**(스냅샷 이후 부모 비식별본 *교체* 감지 — procLog 경로 불일치·파일 mtime)로 방어된다(2026-07-29: 구 '신고 이력' 조건은 제거 — 파생 생성은 신고와 무관 → [14 §14.3](14-augmentation.md)). ② 기존 파생은 **원본 신고와 무관하게 독립 취급**된다(2026-07-29 확정 — 파생영상에서는 비식별 누락 신고를 접수하지 않으며 412 로 거부되고, 신고 게이트도 자기 `rawSn` 행만 판정한다 → [08 §8.4](08-deidentification.md)). 파생본의 판정을 고쳐야 하면 파생본 자체의 라벨링·검수에서 수동 정정한다.

## 24.5 categories 와 SKELETON 인덱싱

- 사용된 `LS_LABEL` → `NiaCategory`(id·name·type). type: BBOX/TRACK→`bbox`, POLYGON/SEGMENT→`polygon`, POINT/SKELETON→`keypoints`.
- keypoints 타입 카테고리에는 COCO-17 관절명(`keypoints`)과 스켈레톤 엣지(`skeleton`)를 주입한다(`KeypointSkeleton`).
- **⚠ SKELETON_EDGES 는 1-indexed(COCO 표준 관절 번호쌍)** 이다. 관절명 리스트(`KEYPOINT_NAMES`)는 0-based 인덱스로 접근하지만, 스켈레톤 엣지의 관절 번호는 **1-based**(예: `[16,14]` = 16번 left_ankle ↔ 14번 left_knee, 배열 접근 시 −1)다. 이는 COCO person-keypoints 표준 토폴로지와 정확히 일치하며 BE `KeypointSkeleton.SKELETON_EDGES` ↔ FE `COCO_SKELETON` 이 동일 값으로 잠겨 있다(FE 테스트 `keypointConstants.test.ts` 로 parity 검증, 렌더러는 `keypoints[a−1]` 로 소비).
  - **정본 대조**: xlsx v1.3 은 `skeleton` 필드를 "키포인트 연결 정보(`number[]`)"로만 선언하고 **index base(0/1)를 규정하지 않는다**(구체 예시 없음). 따라서 저작도구는 사실상 표준인 **COCO 1-indexed** 를 채택한다 — xlsx 와 모순되지 않는다. 외부 소비측(관제 데이터마트)은 skeleton 관절 번호를 **1-based** 로 해석해야 한다(off-by-one 방지).

## 24.6 멱등 · 버전 누적

- **버전 채번**: 기존 export 건수 + 1 = 다음 `OUTPUT_VER_NO`. UK(DATA_RAW_SN, OUTPUT_VER_NO) 위반 시 재채번 재시도(동시 승인 TOCTOU/CWE-362 백스톱).
- **★R6 — 검수 승인은 항상 전량 재생성(멱등 skip 미적용)**: 검수 승인(`onReviewApproved`, 최초·재승인 무관) 트리거는 **`forceRegenerate=true`** 로 진입해, **내용 변경 여부와 무관하게 매 승인마다 새 버전 폴더 + JSON/이미지를 전량 재생성**한다. 아래 콘텐츠 해시 멱등 skip 은 **재동결 경로(`onReExport`, event_annotation 지연 승인 등, `forceRegenerate=false`)에서만** 적용된다.
  - **★승인 후 수정도 전량 재생성한다(Phase 5C — 구 정책 폐기)**: 라벨 수정(`LabelService`)·트랙 편집(`TrackEditService`)·트랙 병합(`TrackMergeService`)·버전 롤백(`VersionService`)·촬영환경 수정(`EnvironmentMetaService`, §24.4.1)·프레임 설명 수정(`FrameDescriptionService`)·프레임 개인정보 메타 수정(`FramePrivacyMetaService`)은 검수 완료(APPROVED) 이후 발생하면 `TaskModifiedEvent(exportRegenerated=true)` 를 발행하고, `ControlNotifyDebouncer` 의 flush 가 `AsyncDatasetExportRunner.runReExportThenNotify(rawSn, forceRegenerate=true, ...)` 로 export 를 새 버전 `v{n+1}` 로 **전량 재생성**(멱등 skip 미적용)한 뒤 통지를 내보낸다. 구 서술 "편집(촬영환경 PUT 등)은 재export 자체를 트리거하지 않는다"는 **폐기** — 이제 승인 후 편집은 재export 를 트리거한다. **예외**: `EvntAnnoService`(event_annotation 일반 수정)·`MetaService`(VLM 시계열 메타)는 재생성을 발행하지 않는다(CLAUDE.md "★ export 재생성·동기화 정책" 참조).
  - **재생성 경로는 항상 `force=true`**: 승인(R6)·승인 후 수정(위) 모두 `forceRegenerate=true` 로 export 를 호출하므로 §24.6 의 콘텐츠 해시 멱등 skip 은 이 두 경로에는 적용되지 않는다. 멱등 skip 은 `onReExport`(휴면, 현재 발행처 없음) 경로에서만 유효하다.
  - **retention 백로그(범위 밖)**: 승인마다 새 버전 + 프레임 2벌(orgnl/deid) 복사가 누적되나 구 버전 정리(retention) 잡은 미구현이다. 보존 정책·정리 잡·저장소 메트릭 알람은 **별도 후속 Phase**에서 도입한다(코드에 `TODO(retention)` 주석).
- **무수정 재동결 멱등(콘텐츠 해시, force=false 경로 한정)**: 산출 시점 상태를 SHA-256 콘텐츠 해시로 계산한다. 해시 원천은 **라벨 + 프레임(FRM_EXPLN 등) + 영상 메타(개인정보 유형·해상도 등) + 영상 단위 개인정보 수동값(`VPRV` 블록) + 원천 축 개인정보(관제 인입, `SPRV` 블록)** — 라벨뿐 아니라 프레임 설명/개인정보 정정도 반영한다. 뒤의 두 블록은 **값이 있을 때만 append 하는 조건부 블록**이라 도입 이전 승인분의 해시가 보존된다(하위호환). `image` 블록 원천값은 **정책 상수**라 영상별로 달라지지 않아 해시 입력이 아니다(상수를 바꾸면 그때는 전량 재산출이 의도된 동작이다). 직전 **SUCCEEDED 또는 PARTIAL**(멱등 baseline) export 의 해시와 같으면 재산출을 **skip**(중복 버전 생성 방지). 직전이 FAILED/PENDING 이어도 그 이전의 SUCCEEDED/PARTIAL 해시를 상태 IN 필터로 정확히 찾는다.
  - **PARTIAL 을 baseline 에 포함하는 이유(무한 누적 방지)**: 원천 이미지가 지속 부재해 매번 `PARTIAL` 로 끝나는 영상을 무수정 재동결(force=false)할 때, PARTIAL 을 baseline 에서 제외하면 `v2·v3·v4…` 가 무한 채번되며 매 버전 이미지 파일이 재복사되어 디스크가 무한 증가한다(OWASP API4). PARTIAL 도 멱등 baseline 으로 삼아 이를 차단한다. `FAILED`(written=0)는 디스크 누적이 없고 재시도를 유도해야 하므로 baseline 에서 계속 제외한다.
  - **한계(설계상 수용)**: PARTIAL 후 라벨이 불변인 채 부재 이미지가 나중에 실제로 복구돼도, 해시가 같아 자동 재완성되지는 않는다(라벨/설명을 조금이라도 수정하면 해시 변경으로 재산출됨). 이는 무한 누적 방지를 위한 의도된 트레이드오프이며 기존 산출물은 보존된다(데이터 손실 아님).
- **수정 후 재승인**: 라벨/설명/개인정보 변경 → 해시 변경 → **v{n+1} 폴더 신규 생성, 이전 버전 폴더는 보존**. 재검수→재승인마다 버전이 누적된다.
- **배포노트(#7 해시 churn, self-heal — 재동결 force=false 경로 한정)**: 프레임 개인정보 3필드(익명/가명/개인정보 포함여부, `LS_DATA_SRC.ANONY_INCL_YN`/`PSDO_INCL_YN`/`PRVC_INCL_YN`)를 콘텐츠 해시 원천에 편입했다. 이 편입 이전에 승인·export 되어 있던 영상을 **무수정 재동결**하면 해시가 1회 달라져 `v{n+1}` 이 한 번 재산출된다(1회성 churn). 이후에는 값이 불변이면 해시가 안정되어 다시 멱등 skip 으로 수렴한다(self-heal — 운영자 조치 불필요, 기존 버전 폴더 보존). ※ 승인 경로(force=true, R6)는 해시와 무관하게 매번 재산출되므로 churn 개념이 적용되지 않는다.

## 24.7 추적 원장 (LS_DATASET_EXPORT)

> **V173 표준용어 정합** — `EXPORT_*` 는 표준 미등록 약어(`EXPORT` 는 표준용어에서 *수출*=`EXP` 의 영문일 뿐)라 산출물=`OUTPUT`·프레임=`FRME` 표준단어로 정정했다(`EXPORT_SN`→`OUTPUT_SN` 등 5건). **테이블명은 `LS_DATASET_EXPORT` 그대로**이고, **JPA 엔티티의 자바 필드명도 그대로**다(`@Column(name)` 값만 변경 — 파생 쿼리 메서드 연쇄 방지). ⚠ **뷰 출력명은 V173 시점에는 바뀌지 않았다** — PostgreSQL 은 `RENAME COLUMN` 시 뷰 *본문*만 추종하고 출력명은 자동 별칭으로 보존하기 때문이다(`e.OUTPUT_PATH_NM AS export_path_nm`). **출력명 정합은 V174 가 뷰를 명시 재작성하면서 처리했다** — 이제 뷰 출력도 `OUTPUT_PATH_NM`·`OUTPUT_STTS_CD`·`FRME_CNT` 이며, 구 이름(`EXPORT_PATH_NM`·`EXPORT_STTS_CD`·`FRAME_CNT`)은 뷰에서 사라졌다([18 DB](18-database.md) §18.3).

| 컬럼 | 내용 |
|------|------|
| `OUTPUT_SN` | PK |
| `DATA_RAW_SN` | 대상 영상(RAW_SN) |
| `OUTPUT_VER_NO` | 산출 버전(≥1). UK(DATA_RAW_SN, OUTPUT_VER_NO) |
| `OUTPUT_PATH_NM` | **영상 루트 경로**(`{dirname(RAW_FILE_PATH_NM)}/{RAW_SN}`) — **버전 루트가 아니다.** 관제가 `v1`·`v2` 를 한 경로 아래에서 보고 골라야 요구사항의 *버전별 비교·복구*가 성립하기 때문. 값은 **절대경로 그대로 저장·사용**하며 조회 시 재계산하지 않는다(롤백 전략 전환 후에도 기존 행이 깨지지 않도록) |
| `OUTPUT_STTS_CD` | `PENDING → SUCCEEDED\|PARTIAL\|FAILED` (아래 상태 의미) |
| `FRME_CNT` | **실제 프레임 수(N)** — **벌 합계가 아니다.** 관제가 이 값을 `datasets.img_nocs`·`dataset_versions.data_etbl_nocs` 에 그대로 적재하며 그 정의가 "추출·라벨링 프레임 수"라, 원본(orgnl)+비식별(deid) 2벌 쓰기 건수를 합산하면 정확히 2배로 부풀고 파생영상(1벌만 산출)과 값의 축이 갈린다. 산정 방식은 **벌별 쓰기 건수의 최댓값**(한 벌은 프레임당 최대 1건을 쓰므로 이 값은 영상의 실제 프레임 수를 넘지 않고, 부재한 벌은 0 이라 결과를 끌어내리지 않는다 — 최솟값을 쓰면 파생영상이 전부 0 으로 적재된다) — 영상 유형(일반 2벌/파생 1벌)과 무관하게 일관된다 |
| `DATA_ETBL_CPCT` | **데이터구축용량**(V173) — 이 버전의 산출 폴더(`{영상루트}/v{n}`) **총 바이트**. 관제 `dataset_versions.data_etbl_cpct` 에 공급한다. **영상 누적이 아니라 버전 단위**이며, 심링크는 따라가지도 합산하지도 않는다. 산출 실패 시 `NULL`(=미산출)이고 **그때도 export 는 성공으로 종결**한다 — 용량은 부수 정보라 본체를 좌우하지 않는다. **★`FRME_CNT` 와 산정 축이 다르다** — 용량은 폴더 총 바이트라 2벌 포함이 정상이고, 프레임 수는 벌 최댓값(=N)이라 2벌을 포함하지 않는다. 두 값을 같은 축("2벌 합산 여부")으로 묶어 해석하지 말 것 |
| `CONTENT_HASH` | 산출 시점 콘텐츠 해시(SHA-256, 멱등 판정 키) |
| `REG_DT` | 생성 일시 |

**상태 의미**: `PENDING`=채번 후 파일 쓰기 전(예약), `SUCCEEDED`=전 프레임 산출, `PARTIAL`=일부 프레임 skip(원천 이미지 부재 등), `FAILED`=한 장도 못 씀 또는 파일 쓰기 예외.

**stale PENDING 정리 (크래시 복구)**: `insertNextVersion` 이 PENDING 레코드를 커밋한 뒤 파일 쓰기/상태 마감 전에 프로세스가 크래시하면 그 레코드가 `PENDING` 으로 영구 고착된다. 주기 Quartz 잡 `DatasetExportPendingSweepJob`(기본 10분 간격, `@DisallowConcurrentExecution` + PostgreSQL JobStore 클러스터 락으로 2노드 중 1노드만 tick)이 `REG_DT` 가 `stale-minutes`(기본 30분) 이전인 PENDING 을 `FAILED` 로 마감한다. **파일은 삭제하지 않고 상태만 회수**한다(잔재는 `{RAW_SN}/v{n}/` 안에만 남고 **원본 영상과 형제 디렉터리라 원본에 영향이 없으며**, 다음 산출은 `v{n+1}` 로 진행). 정상 산출은 수 초 내 완료되므로 30분 임계를 넘는 PENDING 은 크래시 잔재뿐이다. 초장기 산출이 sweep 으로 FAILED 마킹돼도 이후 완료가 `SUCCEEDED` 로 최종 수렴한다(last-writer-wins, 무해). `stale-minutes` 오설정(0/음수)은 안전 기본값 30 으로 폴백한다.

## 24.8 설정

| 설정 키 | 기본값 | 의미 |
|---------|--------|------|
| `authoring.dataset-export.base-strategy` | `co-locate` | 산출 base 전략. `co-locate`=원본 영상 디렉터리 하위(`dirname(RAW_FILE_PATH_NM)/{RAW_SN}`), `labeling-root`=구 고정 루트(롤백용). **플래그는 신규 산출의 base 선택에만 관여**하며 이미 기록된 `OUTPUT_PATH_NM` 을 재해석하지 않는다 |
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
- **outcome ↔ 레코드 정합**: 파일을 다 썼더라도 상태전이(`markSucceeded`)가 예외로 실패하면 레코드는 `FAILED` 로 마감되고 metric outcome 도 `failed` 다(다운스트림 View 는 `SUCCEEDED`/`PARTIAL` 만 소비하므로 실효 결과와 일치).
- **outcome ↔ 통지 정합(D-ISSUE-61)**: `export()` 는 이 outcome 을 `DatasetExportOutcome` 으로 **반환**하며, `AsyncDatasetExportRunner` 가 `notifiable()` 로 관제 통지 여부를 판정한다. 즉 **metric 태그와 통지 판정이 같은 값에서 나와 어긋날 수 없다** — 판정 기준을 상위에서 재계산하지 않는 것이 이 설계의 핵심이다. 통지 대상은 `completed`·`partial`·`idempotent_skip`, 보류는 `failed`·`version_exhausted`·`no_input`·`deident_blocked` ([15 통지](15-control-notify.md) §15.2).

## 24.10 코드 · 테스트

- 코드: `dataset/export/` — `DatasetExportService`(오케스트레이터) · `DatasetExportTxService`(REQUIRES_NEW DB 게이트웨이 + `sweepStalePending`) · `DatasetExportWriter`(파일 쓰기) · `DatasetExportPathResolver`(CWE-22) · `FrameSource`(원천 이미지·CWE-59) · `json/`(NIA 문서 빌더·매퍼) · `listener/DatasetExportBridge` · `AsyncDatasetExportRunner` · `DatasetExportPendingSweeper`. 스케줄러: `batch/scheduler/DatasetExportPendingSweepJob` · `DatasetExportPendingSweepTriggerConfig`. 메트릭: `observability/metrics/DatasetExportMetrics`.
- 테스트: `DatasetExportServiceTest`(판정 3분기·멱등·재채번·메트릭 outcome) · `DatasetExportPendingSweeperTest`/`DatasetExportPendingSweepJobTest`(sweep cutoff·하한폴백·예외삼킴) · `DatasetExportTxServiceTest`(멱등 baseline IN·sweep) · `DatasetExportServiceIT`(채번·상태·멱등, 파일 부재 환경) · `DatasetExportE2EIT`(실 이미지 fixture 기반 디스크 파일 산출 end-to-end — orgnl/deid 이미지+JSON 페어, 8키·description·좌표·anonymity, v1/v2 누적).
