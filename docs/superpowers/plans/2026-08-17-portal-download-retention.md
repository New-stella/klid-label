# 플랜 — 포털 ZIP 다운로드 + 보존기간 만료 자동삭제

- 스펙: `docs/superpowers/specs/2026-08-17-portal-download-retention-design.md`
- 브랜치: `domain-check` (이미 feature 브랜치 — main 아님)
- 키트: `docs/design/포털-DOMAIN-013/`

## 태스크 순서 (키트 빌드 순서: 제약 → 데이터 → 계약 → 로직 → 검증)

| # | 태스크 | 근거 ITEM | 의존 |
|---|---|---|---|
| 0 | 설정 3키 등록 + Flyway 시드 | `DFEAT-055` | — |
| 1 | `deleteUpload` 실경로 검증 하드닝 (판정기 값객체화) | `AC-037` | — |
| 2 | 만료 예정 시각 파생 계산 + 응답 3곳 | `API-115`·`API-140`·`API-142`·`AC-033` | 0 |
| 3 | ZIP 다운로드 API | `API-203`·`AC-034`·`AC-035` | — |
| 4 | 보존기간 만료 삭제 배치 2축 | `DFEAT-055`·`AC-032`·`AC-036`·`AC-037` | 0·1·2 |
| 5 | 문서 반전 | `UC-024` | 3·4 |

## Task 0 — 설정 3키 + 시드

**Files**: `sysconfig/ConfigKeys.java` · `db/migration/V11__seed_portal_retention_config.sql`
**키트 참조**: `docs/design/포털-DOMAIN-013/domain_feature/DFEAT-055.md`

- `portal.datamart.retention-days`(7) · `portal.upload.retention-days`(7) · `portal.upload.failed-retention-days`(1)
- `ALLOWED` 등록 + `NUMBER_RANGE` **하한 1**(0·음수 = 즉시 삭제)
- V11 로 `LS_SYSTEM_CONFIG` 3행 시드 — **폴백을 없앴으므로 시드가 없으면 기능이 죽은 채 배포된다**

## Task 1 — `deleteUpload` 하드닝

**Files**: `portal/service/PortalUploadService.java` + 테스트
**키트 참조**: `acceptance/AC-037.md`

- `realWithinBase` → **값 객체 반환**. 3호출부가 각자 감싼다
  (읽기=throw / 사용자삭제=throw+DB보존 / 배치=skip+WARN)
- ⚠ 가드는 **중간 디렉터리 심링크** 축으로. leaf 축 가드는 수정 전에도 통과한다
- ⚠ `toRealPath()` 부재 시 예외 → 멱등 재삭제 보존

## Task 2 — 만료 예정 시각

**Files**: `portal/service/PortalRetentionPolicy.java`(신규 · 단일 판정 지점) ·
`portal/dto/PortalUploadResponse.java` · `PortalUploadDetailResponse.java` · 데이터마트 목록 DTO
**키트 참조**: `acceptance/AC-033.md`

- **저장 컬럼 금지** — 조회 시점 파생. 설정 변경이 다음 조회부터 즉시 반영돼야 한다
- `PROCESSING`·`UPLOADED` → `null` · 설정 부재 → `null`(목록을 500 으로 깨뜨리지 않는다)
- **N+1 금지** — 라벨 최종 저장일은 집계 쿼리 1회

## Task 3 — ZIP 다운로드 API

**Files**: `portal/controller/PortalLabelController.java` · `portal/service/PortalDatamartDownloadService.java`(신규) · `application.yml`
**키트 참조**: `api_endpoint/API-203.md` · `acceptance/AC-034.md` · `AC-035.md`

- 판정 순서 **429 → 403 → 412 → 410 → 200** (스펙 D1)
- ⚠ 신고 판정을 `resolveDeidPath` **앞에서 독립적으로** — 둘 다 `null` 이라 안 그러면 신고 영상이 200 으로 샌다
- ZIP 스트리밍(`StreamingResponseBody`), 파일 I/O 는 **트랜잭션 밖**
- `Cache-Control: no-store` · 속도 제한 `portalDatamartDownload` config(3/분)

## Task 4 — 삭제 배치 2축

**Files**: `portal/scheduler/PortalRetentionSweepJob.java`(신규) · `portal/service/PortalRetentionSweepTxService.java`(신규)
**키트 참조**: `domain_feature/DFEAT-055.md` · `acceptance/AC-032.md` · `AC-036.md` · `AC-037.md`

- 데이터마트: 조건부 DELETE 1회 · 업로드: **파일 먼저 → DB 나중**
- ⚠ `uld_stts_cd IN ('READY','FAILED')` **리터럴**로 · 최종 DELETE 문에 조건 재삽입
- ⚠ 파일 검증 실패 → 그 자산 DB 도 남기고 다음 tick 재후보
- 설정 부재 → **skip + ERROR**(폴백 금지)

## Task 5 — 문서 반전

**Files**: `docs/test-cases/UNCERTAINTIES.md` · `docs/test-cases/F-portal.md` · `docs/v2-wiki/16-portal.md`

## 제외 (별도 경로)

`SCREEN-028` 화면 + FE 회귀 가드 2건 — 포털 화면 키트 SYNC 후 화면 파이프라인.
