# 포털 작업 데이터 ZIP 다운로드 + 보존기간 만료 자동삭제 — 설계 스펙

- 도메인: `DOMAIN-013 포털` · 키트: `docs/design/포털-DOMAIN-013/`
- 대상 ITEM: `DFEAT-055` · `API-203` · `API-115` · `API-140` · `API-142` · `AC-032`~`AC-037` · `UC-024` · `DFEAT-044`
- 작성: 2026-08-17 · 브랜치 기준 `5b68802f`

---

## 1. 범위

### 포함 (이번 라운드 — 백엔드·도메인)

| # | 산출 | 근거 ITEM |
|---|---|---|
| 1 | ZIP 다운로드 API `GET /v1/portal/datamart/videos/{rawSn}/download` | `api_endpoint/API-203.md` |
| 2 | 보존기간 만료 자동삭제 배치 2축(데이터마트 라벨 · 업로드 자산) | `domain_feature/DFEAT-055.md` |
| 3 | 설정 키 3개 + 시드 | `DFEAT-055` 본문 |
| 4 | 만료 예정 시각 응답 필드 3곳 | `API-115` · `API-142` · `API-140` · `AC-033` |
| 5 | 기존 `deleteUpload` 실경로 검증 하드닝 | `AC-037` and_examples[2] |
| 6 | 문서 반전 — `docs/test-cases/UNCERTAINTIES.md` #11 · `F-portal.md` · `docs/v2-wiki/16-portal.md` | `UC-024` 개정 |

### 제외 (별도 경로 — 사용자 확정 2026-08-17)

- **`SCREEN-028` 포털 홈 다운로드 버튼** 및 그것을 막고 있는 FE 회귀 가드 2건
  (`PortalLayout.test.tsx(다운로드_메뉴_미존재_V1_5)` · `PortalHomePage.test.tsx(다운로드_UI_미제공_V1_5_포털_자체_책임)`).
  → 포털 화면 키트 SYNC 후 화면 전용 파이프라인이 담당한다.
  백엔드만 반영해도 그 두 테스트는 **UI 부재를 단언하므로 깨지지 않는다** — 지금 건드릴 이유가 없다.
- **업로드 자산 개별 다운로드** — `PortalUploadLabelService.downloadFile` 로 **이미 구현돼 있다**
  (회귀 `TC-PORTALUP-056/057/058`). 이번 신설은 **데이터마트 축 전용**이라 그 경로와 무관하다.

---

## 2. 확정 정책 (사용자 확정 — 재논쟁 대상 아님)

| 축 | 기준점 | 기본 | 설정 키 | 만료 시 |
|---|---|---|---|---|
| 데이터마트 라벨 | 본인 저장 라벨 `MAX(REG_DT)` | 7일 | `portal.datamart.retention-days` | DB 행 삭제 |
| 업로드 `READY` | 자산 `REG_DT` · 그 자산 라벨 최종 저장일 중 **늦은 쪽** | 7일 | `portal.upload.retention-days` | DB + **파일** 삭제 |
| 업로드 `FAILED` | FAILED 전이 시각(`MDFCN_DT`) | **1일** | `portal.upload.failed-retention-days` | DB + **파일** 삭제 |

- `PROCESSING` 은 삭제 대상이 **아니다** (`AC-036`).
- 속도 제한 **사용자당 분당 3회**.
- 산출물은 ZIP 하나 — 라벨 JSON + 프레임 이미지 + 비식별 영상(있을 때만).

---

## 3. 설계 결정

### D1 — 판정 순서를 고정한다 (오라클 누출 방지)

```
① 인증 PORTAL_USER (SecurityConfig)
② 속도 제한 permit 실패 → 429
③ 데이터마트 노출(APPROVED) 아님 → 403
④ 비식별 누락 신고 열림 → 412
⑤ 본인 저장 라벨 0건 → 410
⑥ → 200 ZIP
```

**③이 ④보다 먼저다.** 순서를 뒤집으면 데이터마트에 노출되지도 않은 영상의 **비식별 신고 상태가
응답으로 새어나간다**(CWE-209). 이 저장소가 스트리밍 응답을 404 로 통일한 것과 같은 취지다.

### D2 — 판정기는 전부 기존 단일 원천을 재사용한다 (복제 금지)

| 판정 | 재사용할 것 |
|---|---|
| 데이터마트 노출 | `assignment/service/ReviewApprovalGate(isApproved)` |
| 비식별 신고 | `label/service/LabelAccessGuard(requireNotUnderDeidentReport)` → `video/service/DeidentReportGate(isUnderDeidentReport)` |
| 라벨 병합 | `portal/service/PortalLabelService(loadFrameLabels)` |
| 비식별 영상 경로 | `video/service/VideoStreamService(resolveDeidPath)` |
| 프레임 파일명 | `dataset/export/ExportFileNaming` |
| 속도 제한 | Resilience4j per-user `RateLimiter` — 형제 `PortalUploadController(acquireUploadPermit)` 패턴 |

⚠ `PortalLabelService` 는 데이터마트 노출 판정을 사설 메서드(`isExposedToDatamart`)로 **이미 복제
보유**한다. **신규 코드는 그것을 따라 복제하지 말고 `ReviewApprovalGate` 를 쓴다.** 기존 사설
구현의 정리는 이번 범위 밖이며, 건드리면 회귀 위험만 늘린다.

### D3 — ZIP 구조는 `API-203` 원문 그대로

```
{rawSn}/labels.json                      프레임별 라벨(본인 저장분 우선)
{rawSn}/frames/{FRM_NO 4자리 zero-pad}.jpg
{rawSn}/video.{ext}                      비식별 영상 — 있을 때만
```

- 파일명 `portal-video-{rawSn}-{yyyyMMdd}.zip` — **서버 생성 고정명, 사용자 입력 미포함**이라
  CRLF 인젝션 여지가 구조적으로 없다. `PortalUploadLabelService` 의 `sanitizeFileName` 계열을
  끌어올 필요가 없다(그건 사용자 업로드 원본명을 다루는 다른 축이다).
- `Cache-Control: no-store` — 게이트가 걸린 미디어 공통 규칙. 캐시되면 신고 직후에도 재노출된다.

### D4 — 라벨은 "병합"이 아니라 "본인 저장분 override"다

`loadFrameLabels` 실측 규칙: 본인 저장 라벨(`LS_PORTAL_USER_LABEL`, `portalUserNo`+`srcSn`)에
좌표가 있는 행이 **1건이라도 있으면 본인 저장분만** 반환하고, 없으면 데이터마트 원본(`LS_DATA_LBL`)을
반환한다. `API-203` 본문의 *"있으면 그것을, 없으면 원본"* 과 같은 축이다.
이 성질이 곧 `AC-035`(타 사용자 저장분 미노출)의 보장 근거다 — 별도 격리 코드를 새로 만들지 않는다.

### D5 — 비식별 영상은 없으면 없는 대로 담는다 (원본 폴백 금지)

`resolveDeidPath` 는 `DE_IDNTF_YN != 'Y'` 이거나 최신 SUCCESS 로그가 없으면 **`null`** 을 준다.

⚠ **함정**: 신고(`'F'`)와 "비식별 이력 없음"이 **둘 다 `null`** 로 온다. 그래서 D1 의 ④(신고 → 412)를
**`resolveDeidPath` 호출 전에** 독립적으로 평가해야 한다. 안 그러면 신고 영상이 412 가 아니라
"영상 없는 200 ZIP"으로 조용히 나간다 — `AC-034` 와 `API-203` 412 규정이 동시에 깨진다.

### D6 — ZIP 은 스트리밍으로 쓰고, 파일 I/O 는 트랜잭션 밖에서 한다

영상이 들어가면 응답이 GB 급이 될 수 있어 메모리 적재는 OOM 이다. 조회·인가·게이트는
`@Transactional(readOnly)` 안에서 값 레코드만 만들고, **경로 검증·파일 open·ZIP 스트리밍은
트랜잭션 밖**에서 한다. 이 저장소가 프레임 이미지 서빙에 이미 강제하는 규칙과 동일하다
(커넥션을 쥔 채 NAS I/O → 커넥션 기아 전례 있음).

### D7 — 만료 예정 시각은 저장하지 않는 파생값이다

`AC-033` 이 못박은 성질이다. **스냅샷 컬럼을 만들면 이 인수기준이 깨진다** — 설정을 7→14일로
바꾸면 이미 저장된 라벨의 만료 예정도 **다음 조회부터 즉시** 달라져야 한다.

| 응답 | 필드 | 계산 |
|---|---|---|
| `API-115` `GET /v1/portal/datamart/videos` | `myLabelExpiresAt` | `MAX(REG_DT)` (본인·그 영상) + `portal.datamart.retention-days` |
| `API-142` `GET /v1/portal/uploads` | `expiresAt` | 아래 업로드 규칙 |
| `API-140` `GET /v1/portal/uploads/{uldSn}` | `expiresAt` | 아래 업로드 규칙 |

업로드: `READY` → `max(자산 REG_DT, 그 자산 라벨 MAX(REG_DT)) + retention` /
`FAILED` → `MDFCN_DT + failedRetention` / `PROCESSING`·`UPLOADED` → **`null`**(삭제 대상이 아니므로 만료가 없다).

⚠ **목록 응답은 N+1 을 만들지 않는다** — 라벨 최종 저장일은 자산별 개별 조회가 아니라
**한 번의 집계 쿼리**로 모아 매핑한다.

### D8 — 설정은 폴백하지 않는다. 대신 시드를 반드시 넣는다 (세트)

이 저장소의 기존 관례(`PortalFrameExtractRunner(snapshotIntervalSec)`)는 설정 조회 실패 시
상수로 fail-safe 폴백한다. **삭제 배치는 그 관례를 의도적으로 따르지 않는다** — 파괴적 기능이
fail-open 되면 "설정을 못 읽어 기본값으로 즉시 삭제"가 성립한다. 코드에 그 근거를 남긴다.

- **배치**: 값 부재·파싱 실패 → 그 tick 을 **skip + ERROR 로그**. 삭제를 시도하지 않는다.
- **조회(만료일 표시)**: 값 부재 → 그 필드만 **`null`**. 목록 화면 전체를 500 으로 깨뜨리지 않는다.
  (둘 다 "설정이 없으면 아무 일도 일어나지 않는다"로 일관된다.)
- ★ **그래서 시드가 필수다.** 폴백을 없앤 채 시드까지 없으면 기능이 **죽은 채로 배포**된다.
  신규 Flyway 마이그레이션으로 `LS_SYSTEM_CONFIG` 에 3행(7 / 7 / 1)을 넣는다.
- `sysconfig/ConfigKeys` 의 `ALLOWED` + `NUMBER_RANGE` 에 3키를 등록해 **저장 시점에 하한을 강제**한다
  (하한 1 — `0`·음수가 들어가면 "즉시 삭제"가 된다).

### D9 — 삭제 배치는 신규 잡으로 분리한다

기존 `PortalUploadSweepJob`(TUS 세션 24h 정리 + 30분 고착 FAILED 전이)에 얹지 않는다 —
보존기간 삭제는 **비가역 파괴 작업**이라 주기·실패 격리·감사를 독립시키는 편이 안전하다.
단 **패턴은 그대로 따른다**: `@Scheduled` 잡은 오케스트레이션만 하고 트랜잭션 경계는 별 빈
(self-invocation 프록시 우회 방지), 2노드는 **조건부 벌크 쿼리의 영향행수(0/1)로 멱등화**.

- **데이터마트 축**: (사용자, 영상) 그룹의 `MAX(REG_DT) < cutoff` 인 라벨을 조건부 DELETE.
  파일이 없으므로 클레임 단계가 필요 없다.
- **업로드 축**: 후보 조회 → 자산별로 **파일 먼저, DB 나중**.
  ⚠ 순서를 뒤집으면 DB 가 먼저 사라져 **어느 파일을 지워야 하는지 알 수 없게 된다**.
  ⚠ 후보 조건에 `uld_stts_cd IN ('READY','FAILED')` 를 **리터럴로 박는다**(`AC-036`) —
  `PROCESSING` 을 지우면 프레임 추출 러너와 경쟁해 파일·DB 불일치가 난다.
  ⚠ 파일 삭제가 실경로 검증에서 거부되면 **그 자산의 DB 행도 이번 tick 에 지우지 않고**
  다음 tick 에 재후보로 둔다(`AC-037` and_examples[2]). 배치 전체를 중단하지는 않는다.

### D10 — `deleteUpload` 실경로 검증 하드닝 (기존 결함, 함께 처리)

실경로 판정기 `PortalUploadService.realWithinBase` 는 현재 **`serveFrameImage` 한 곳에서만** 쓰이고
`deleteUpload` 는 쓰지 않는다. 삭제는 열람보다 위험하다 — **잘못 열면 유출이지만 잘못 지우면
비가역**이다.

- 판정기를 **값 객체 반환**으로 바꿔 호출부가 각자 감싼다:
  읽기 = throw / 사용자 삭제 = throw + **DB 보존** / 배치 = **skip + WARN**.
- ⚠⚠ **위험은 leaf 심링크가 아니라 중간 디렉터리 심링크다.** `unlink` 는 leaf 링크 자체만 지우지만
  **중간 디렉터리 심링크는 투명하게 따라간다.** 따라서 leaf 링크로 만든 회귀 가드는
  **수정 전에도 통과**해 아무것도 지키지 못한다. 가드는 반드시 **중간 디렉터리** 축으로 만든다.
- ⚠ **`toRealPath()` 는 대상 부재 시 예외**를 던진다(`deleteIfExists` 와 다르다). 그대로 넣으면
  **이미 지워진 파일의 재삭제(멱등)가 깨진다.** 부재를 먼저 분기한다.

---

## 4. 불변 규칙 (위반 시 FAIL)

1. **원본(비식별 이전) 영상은 어떤 경로로도 ZIP 에 들어가지 않는다.** 폴백 금지.
2. 비식별 영상 경로는 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` **적재값을 읽는다**.
   문자열로 조합·추측하지 않는다(mock 은 `deidentified.mp4`, 실연동은 `{stem}-mask{ext}` 로 **다르다**).
3. ZIP 에 **타 사용자의 저장 라벨이 한 건도 들어가지 않는다** (`AC-035`).
4. 삭제 배치는 `PROCESSING` 자산을 **절대** 지우지 않는다 (`AC-036`).
5. 삭제 대상 조건은 **최종 DELETE 문 자체에** 다시 건다(검사~삭제 사이 창 폐쇄).
6. 설정값 부재 시 삭제를 **시도하지 않는다**(폴백 금지).
7. 응답에 PII·토큰·절대 경로를 싣지 않는다. 로그에도 마찬가지(`LogSanitizer`).

---

## 5. 수용 기준 매핑

| AC | 요지 | 검증 방법 |
|---|---|---|
| `AC-032` | 보존기간 경계에서 200 ↔ 410 전환. **배치의 삭제 조건과 API 의 410 판정이 같은 사실을 가리켜야 한다** | 통합 — 저장 → 시간 경과 → 배치 → 다운로드 |
| `AC-033` | 만료 시각은 조회 시점 파생값(설정 변경 즉시 반영) | 설정 변경 후 재조회 |
| `AC-034` | 비식별 영상 없으면 라벨·이미지만, 원본 폴백 없음 | ZIP 엔트리 단언 |
| `AC-035` | 타 사용자 저장분 미노출 | 사용자 2인 저장 후 교차 다운로드 |
| `AC-036` | `PROCESSING` 은 만료돼도 미삭제 | 배치 실행 후 행·파일 존재 단언 |
| `AC-037` | `FAILED` 는 1일, `READY` 는 7일 — 두 축 독립 | 배치 실행 후 선택적 삭제 단언 |

★ **가드 실효성을 뮤테이션으로 증명한다.** 이 저장소는 *"가드를 되돌려도 테스트가 통과"* 한
전례가 있고, 이번엔 그것이 곧 **사용자 데이터 유실**을 뜻한다. 최소한 아래 셋은 가드를 일시적으로
되돌렸을 때 **실제로 실패하는지** 확인한다: `PROCESSING` 제외 · 파일 우선 순서 · 중간 디렉터리 심링크 방어.

---

## 6. 리스크 · 인지 수용

| 항목 | 성질 |
|---|---|
| Resilience4j per-user limiter 는 **노드별 in-memory** 라 2노드면 실질 2배 | 형제(`portalUpload`·`portalUserLabel`)와 **동일 성질**. 분산 제한기를 새로 만들지 않는다 |
| 만료 시각이 파생값이라 클라이언트가 캐시하면 설정 변경이 즉시 안 보임 | `AC-033` 이 응답 필드 설명에 명시하도록 요구 — 문서화로 처리 |
| 삭제는 **비가역**이며 복구 경로가 없다 | 사용자 확정 사항. 화면 만료 예정 고지가 유일한 사전 방어 |
| 업로드 축 `FAILED` 기준점이 `MDFCN_DT` 라 **다른 수정도 시각을 민다** | 실질적으로 FAILED 전이 후 그 행을 고치는 경로가 없어 문제되지 않으나, 생기면 재검토 대상 |

---

## 7. 구현 중 확정 (Phase 3 에서 채운다)

_(비어 있음 — 키트와 실코드가 어긋나 결정이 필요했던 사항을 여기에 누적하고, 종료 시 설계 반영
권고 목록으로 올린다)_
