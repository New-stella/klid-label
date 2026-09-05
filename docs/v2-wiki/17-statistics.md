# 17. 통계 · 대시보드

> 출처: D2(SC-011/020/021), 코드(`stats/`, `frontend stat/`, `dashboard/`)
> 관련: [12 검수·배정](12-review-assignment.md)

화면: `KLID-AT-SC-011`(대시보드 `/dashboard`), `SC-020`(작업자 통계 `/stat`), `SC-021`(전체 통계 `/stat/overall`, REVIEWER). 코드: `stats/`(9 파일).

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"`KLID-AT-SC-011`"* 은 낡은 식별자다. 코드의 1차 식별자는 `SCREEN-011`(축약 `SC-011`). 근거: `04-screens-ia.md`(§4.1) · `reports/wiki-align-20260819/facts/F3-frontend-screens.md`.

## 17.0 집계 기준 — 검수완료 / 전체 분리 (2026-08-03 확정, 구속)

누적 카드와 이벤트 유형 분포는 **두 기준을 한 카드 안에 병기**한다. 주 수치는 **검수완료**, 보조는 **전체 + 완료율**이다.

```
┌─ 이미지 학습데이터 ──────────┐
│  1,200 장   ← 검수완료(주 수치)
│  검수완료 기준 · 전체 5,000장 (완료율 24%)
└──────────────────────────────┘
```

- **검수완료 기준** = `LS_RAW_DATA_STATUS.DATA_STTS_CD = 'APPROVED'` (검수 승인 = 작업 완료 = 학습데이터 확정). 핵심 산출물 목표(이미지 10만장·영상 5,000건) 진척은 이 값으로 판단한다.
- **★검수완료 기준 집계는 폐기(`DSCD_YN='Y'`) 프레임을 제외한다(2026-08-17 반영)** — 대상은 **라벨 수**(작업자 통계의 `labelCount`/`approvedLabelCount` — §17.2)·**이미지(프레임) 수** 카드·**프레임 단위 이벤트 유형 분포**(`approvedImageDistribution`) 세 집계다. 학습데이터 산출물·데이터마트 노출이 그 프레임을 구조적으로 제외하므로, 위 "검수완료 기준 = 학습데이터로 확정된 분량"이라는 정의와 집합을 맞춘 것이다. **영상 단위 집계(`approvedVideoCount`·`approvedEventDistribution`)는 무관**하다(폐기는 프레임 축이라 그 영상 자체의 승인 여부·건수를 바꾸지 않는다). **전체 기준 수치(`cumulativeImageCount` 등)도 무관**하다 — "수집한 전체 분량"이라는 별개 축이라 폐기를 빼지 않는다. 판정은 `COALESCE(DSCD_YN,'N') <> 'Y'` 로 뷰·산출물과 동일 — [18 §18.2](18-database.md) `LS_DATA_SRC.DSCD_YN` 참조. 화면 숫자가 이전보다 줄어 보이는 것은 결함이 아니라 정정이다.
- **적용 대상**: 누적 이미지·영상 카드 + 이벤트 유형 분포(대시보드는 이미지·영상 **그리드 2개 모두**, 전체 구축 현황은 파이 + 가로막대). 카드와 분포가 **같은 모집단**을 가리키도록 통일한다.
- **미적용(현행 유지)**: 처리 현황 5카드(대기/진행중/검수대기/승인/반려), 작업자별 현황 표(§17.3), 대시보드 KPI 4카드(§17.1) — 이미 상태별로 분해되어 있다.
  - ⚠ **예외 — 작업자 통계(§17.2, SCR-STAT-001) 자신의 KPI 카드 2종은 별도로 병기가 적용됐다(2026-08-18)**. "완료 작업"·"총 라벨 수" 카드가 그 대상이며, 위 목록의 "작업자별 현황 표"(§17.3 REVIEWER 전체 표)·"대시보드 KPI 4카드"(§17.1)와는 **다른 화면 요소**라 혼동하지 말 것 — 상세는 §17.2.
- **파생영상(증강·해상도)은 포함**한다. 파생 제외 필터를 두지 않는다.
- **완료율은 FE 가 계산**한다 — `total > 0 ? Math.round(approved/total*100) : 0`. BE 는 카운트만 내린다. 계산·문구는 공유 컴포넌트 `frontend/src/components/common/ApprovedRatioNote.tsx` 가 단일 원천이며, 화면마다 인라인으로 중복 구현하지 않는다(두 화면 동일 패턴 보장).

### API 계약 (필드 추가만 — 하위호환)

| 엔드포인트 | 전체 기준 (기존, 값·의미 불변) | 검수완료 기준 (신규) |
|---|---|---|
| `GET /v1/stats/summary` | `cumulativeImageCount`·`cumulativeVideoCount`·`eventDistribution`·`imageDistribution` | `approvedImageCount`·`approvedVideoCount`·`approvedEventDistribution`·`approvedImageDistribution` |
| `GET /v1/stats/overall` | `cumulativeImageCount`·`cumulativeVideoCount`·`eventDistribution` | `approvedImageCount`·`approvedVideoCount`·`approvedEventDistribution` |

- ⚠ **`approvedVideoCount` 는 `completedCount`(summary) / `processing.approved`(overall) 와 동일 값이다** — 신규 쿼리를 만들지 않고 기존 `countByDataSttsCd()` 의 APPROVED 를 재사용한 **의도된 단일 원천**이다. 중복 필드로 오인해 별도 쿼리로 갈라놓으면 드리프트가 생긴다.
- ⚠ **`approved*` 집계는 `LS_RAW_DATA_STATUS` INNER JOIN 으로만** 한다. 이 테이블은 **배정 시점 lazy 생성**이라 `LS_DATA_RAW` 와 1:1이 아니며, LEFT JOIN 으로 바꾸면 미배정 영상이 학습데이터로 계상된다. 상태 행이 없는 영상이 제외되는 것이 의도된 동작이다.
- ⚠ **분포 항목 합계는 카드 수치보다 작을 수 있다** — 비수집 코드(`CLCT_YN='N'`)·ignore 대분류가 `EventTypeService.filterOptions()` 정책상 분포에서 빠지기 때문. 기타 항목을 추가해 임의 보정하지 않는다.
- **★분포 칸은 표시명 그룹 단위다(2026-08-05)** — `filterOptions()` 가 접은 그룹(대표코드 1건 = 그리드 1칸)마다 `memberCodes`(그룹 전체 EV-코드) 카운트를 합산한다. 표시명이 같은 상세 코드 여러 건이 같은 칸으로 합산되고(예: 표시명 폴백으로 이름이 같은 침수 3종 → 1칸), 표시명이 다르면 별도 칸으로 분리된다. 비수집·제외 대분류 코드는 어떤 칸에도(다른 그룹의 memberCodes 로도) 합산되지 않는다. 관리 화면에서 표시명을 바꾸면 그룹이 즉시 재구성돼 다음 조회부터 칸 구성이 바뀐다.
- ⚠ **FE 신규 필드는 required 로 선언**한다. optional + `?? 0` 은 미수신을 실데이터 0 으로 오인시킨다. 런타임 미수신은 `Number.isFinite` 판정 후 주 수치 `-` + 안내 문구로 처리하며, **분포를 전체 기준으로 조용히 폴백하지 않는다**("검수완료 기준" 라벨 아래 전체 값이 나오면 거짓 표시).

### 누적 카드 회귀 가드

전체 구축 현황의 누적 카드 영역(`data-testid="cumulative-cards"`)에는 **ProgressBar 컴포넌트 / `role="progressbar"` / `aria-valuenow` / `<progress>` 를 렌더하지 않는다.** 완료율은 **텍스트로만** 표기한다(Tailwind `<div>` + `width%` 형태의 시각적 진행바도 이 영역에선 금지 — 이벤트 분포 섹션의 가로막대는 이 영역 밖이라 무관).

> 구 가드였던 *"`%` 텍스트 절대 미노출"* 은 **폐기**했다(2026-08-03). 완료율 표기가 확정 요구와 정면 충돌하고, 근거로 인용되던 `UI/UX §4-11` 이 리포지토리 문서에 실존하지 않았기 때문이다. 되살리지 말 것.

## 17.1 대시보드 (SC-011)

영상/라벨/검수 진행도 요약 KPI 카드. 코드: `frontend dashboard/` + `DashboardPage`.

- KPI 4카드(처리 대기 / 처리 완료 / 내 작업(WORKER) / 반려 건수) — 상태별이라 기준 분리 대상 아님
- `이미지 데이터 개수` · `영상 데이터 개수` 카드 + 각 카드 내 이벤트 분포 그리드 → **17.0 기준 분리 적용**
  - 이미지 그리드 = `approvedImageDistribution`(프레임 단위), 영상 그리드 = `approvedEventDistribution`(영상 단위). ⚠ 두 필드는 타입이 같아 **뒤바꿔 써도 컴파일·테스트가 잡지 못한다** — 픽스처 값을 서로 다르게 두어 교차 검증할 것

## 17.2 작업자 통계 (SC-020)

- 라벨러별 진행도, 라벨 개수 등
- **★"완료 작업"·"총 라벨 수" KPI 카드는 검수완료/전체 병기를 쓴다(2026-08-18, SCREEN-020, API-056)** — "완료 작업" 카드는 `completed`(검수완료, 주 수치) + `assignedTotal`(=`completed+inProgress`, 배정 총계)·`completionRate`(서버가 내려주는 비율 0.0~1.0)을 병기하고, "총 라벨 수" 카드는 `approvedLabelCount`(검수완료 영상의 라벨, **폐기 프레임 제외** — §17.0) + `labelCount`(전체 배정분, 전체)를 병기하되 **완료율은 붙이지 않는다**(라벨 단위 비율은 서버가 내려주지 않아 화면이 지어내지 않음 — 사양이 명시적으로 금지). **완료율은 서버가 내려준 값을 그대로 채택**하며 화면이 `completed/assignedTotal` 을 다시 나누지 않는다(정의가 서버에서 바뀌면 재유도값이 조용히 어긋나므로). `assignedTotal` 은 `completed + inProgress`이고 **`rejected` 는 이미 `inProgress`(=APPROVED 아님) 안에 포함**돼 있어 따로 더하지 않는다(반려 이중 계상 주의). `approvedLabelCount` 는 **최신 버전 하나만** 센다 — 재승인으로 쌓인 이전 버전 스냅샷(`LS_LABEL_VERSION`)은 조인하지 않는다(라이브 작업본 `LS_DATA_LBL` 기준).
- **`inProgress`(진행 중) = 배정된 작업 중 완료(`APPROVED`)가 아닌 것 전부** — 17.3 작업자별 현황 표의 같은 이름 지표와 **같은 축**이며 판정식 조각 하나(`StatsQueryRepository.IN_PROGRESS_PREDICATE`)를 두 쿼리가 공유한다
  - 진행 상태를 열거하지 않는다(구 판정 `ASSIGNED + IN_REVIEW` 폐기) — 검수 완료로 쓰이는 상태값은 `APPROVED` 하나뿐이라, 열거하면 **새 상태값이 생길 때 완료에도 진행에도 안 잡혀 화면에서 조용히 사라진다**. **반려(`REJECTED`)도 작업자가 다시 손봐야 하는 건이라 진행 중에 포함**된다
  - ⚠ 이 화면의 **비율 필드는 0~1**, 17.3 의 같은 이름 비율 필드는 **0~100 백분율**이다 — 단위 비대칭은 외부 FE 계약이라 의도적으로 유지한다("일관성" 명목으로 통일하지 말 것)
- **`autoLabelRate`(오토라벨 비율) = 그 작업자에게 배정된 영상의 라벨 중 자동 생성분 비율** — `inProgress` 와 마찬가지로 17.3 작업자별 현황 표의 같은 이름 지표와 **같은 축**이며 판정식 조각 하나(`StatsQueryRepository.AUTO_LABEL_PREDICATE`)를 두 쿼리가 공유한다. 분모(라벨 총 수)가 0 이면 0(0 으로 나눠 `NaN`/`Infinity` 가 JSON 에 실리지 않게 한다)
  - 판정은 **영속된 자동 생성 플래그 `LS_DATA_LBL.AUTO_LBL_YN='Y'` 하나**다(V6 흡수 — 구 `LS_DATA_LBL_AI_INFO.AUTO_LBL_YN`)(구 판정 `LS_DATA_LBL.REG_USER_NO IS NULL` 프록시 폐기) — 등록자를 남기지 않는 생성 경로가 자동 생성 외에도 있어(버전 롤백 복원) 사람이 그린 라벨을 자동으로 오분류했다
  - **V6 이후 컬럼 술어 한 줄**이다. 판정 축이 분리 테이블에 있던 시절에는 `DATA_LBL_SN` 에 UNIQUE 가 없어(한 라벨에 여러 행 가능) JOIN 이 **분모를 중복 계상**했고 그래서 EXISTS 여야 했다 — 흡수로 라벨 1건 = 값 1개가 되어 그 위험 자체가 사라졌다(판정 결과는 동일)
- 코드: `WorkerStatPage`, `stats/StatsController`

## 17.3 전체 통계 (SC-021, REVIEWER)

- 프로젝트(전체 구축) 수준 KPI
- `이미지 학습데이터` · `영상 학습데이터` 누적 카드 + 이벤트 유형 분포(파이 + 가로막대) → **17.0 기준 분리 적용**. 카드 제목의 "학습데이터"가 실제로 검수완료 확정분을 가리키게 된 것이 이 변경의 핵심이다
- **처리 현황 = 가로 스택형 비율 막대 + 범례**(구 5카드 폐기) — `processing` 5값을 **4구간**(완료=`approved` · 처리중=`inProgress + reviewPending` · 대기=`pending` · 실패=`rejected`)으로 접어 비율로 표시한다. 구간 순서·라벨·색은 SCREEN-021 ③ 고정이며 KRDS 의미상태색 토큰(success/info/중립/danger)을 쓴다
  - 4구간 합이 0이면 막대를 렌더하지 않고 범례 숫자(전부 0)로만 말한다 — 폭 0짜리 빈 트랙은 "0건"과 "아직 못 읽음"을 구분해 주지 못하기 때문
- **작업자별 현황 표 = 6컬럼**(작업자 / 라벨 `labeled` / 진행 `inProgress` / 검수 `reviewed` / 오토라벨 `autoLabelRate` / 반려율 `100 - approvalRate`) + 숫자 컬럼 헤더 클릭 정렬. ⚠ 이 표는 §17.0 「미적용」 목록에 있으므로 검수완료/전체 병기는 없다 — `labeled` 는 (§17.2 의 `approvedLabelCount`/`labelCount` 와 달리) **배정 전체분** 한 값만 보여준다
  - **`inProgress`·`autoLabelRate` 두 필드는 응답에 추가된 것**이다(2026-08-08). 그 전에는 표가 6칸인데 응답이 세 지표만 공급해 두 칸이 채워지지 않았다. 기존 5필드(`userId`·`name`·`labeled`·`reviewed`·`approvalRate`)의 **이름·타입·의미는 불변** — 외부 FE 팀도 쓰는 목록 계약이라 **추가만** 한다
  - 두 지표의 판정 축은 17.2 작업자 통계와 **공유 조각 하나씩**(`IN_PROGRESS_PREDICATE`·`AUTO_LABEL_PREDICATE`)으로 통일돼 있다 → §17.2. **단위는 이 화면이 백분율(0~100)** 이고 17.2 는 0~1 이다(의도된 비대칭)
  - **헤더 라벨·정렬 키·표시 값은 한 곳에서 함께 정의**한다(`WorkerStatsTable.NUMERIC_COLUMNS`) — 헤더와 셀이 서로 다른 자리에서 필드를 고르면 "헤더는 A 로 정렬하는데 셀은 B 를 그리는" 컬럼 교차가 다시 생긴다(실제 결함이었다). 값이 없거나 숫자가 아니면 `0` 으로 뭉개지 않고 자리표시(`—`)를 그린다
- **일별 작업량(최근 30일) 차트** = `dailyCounts` — 전체(모든 작업자) 일별 **검수 완료** 건수
  - 항상 **정확히 30건**이며 작업이 없던 날도 `count: 0` 으로 채운다(0-fill) — 막대차트 X축이 날짜 연속으로 그려져야 하기 때문. `date` 는 `yyyy-MM-dd` 오름차순, 마지막 항목이 오늘
  - 완료 시각 기준은 `LS_RAW_DATA_STATUS.UPD_DT`(APPROVED 전이 시점) — 대시보드 "최근 완료 영상" 정렬과 동일 축
  - 집계 원천은 `approvedVideoCount`(= APPROVED 상태 행 수)와 동일해 카드와 차트가 어긋나지 않는다. **작업자 배정(`LS_TASK_ALTMNT`) 조인이 없어** 배정 이력 없이 승인된 영상도 포함되며, 이것이 전체 기준에서 요구되는 동작이다(작업자별 합계와는 미세하게 다를 수 있음)
  - ⚠ 작업자 통계(17.2)의 `dailyCompletion` 은 **sparse 유지**(데이터 있는 날만) — 0-fill 은 전체 경로 전용이다. 두 경로를 "일관성" 명목으로 통일하면 SCR-STAT-001 FE 계약이 바뀐다
- **CSV 리포트 다운로드** (`SC-021` 화면 내 기능 — `GET /v1/stats/report?period=WEEK|MONTH|QUARTER|YEAR`(REVIEWER 전용))
  > ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"`KLID-AT-SC-021-RPT`"* 라는 파생 서브 ID는 코드에서 확인되지 않는다. CSV 다운로드는 별도 화면이 아니라 `SC-021`(전체 통계) 화면 안의 기능이다. 근거: `reports/wiki-align-20260819/Q4-contradictions.md` §2-A #11.
  - ⚠ **구 서술 폐기(2026-08-31)** — *"placeholder — 실데이터 없음. `period` 값과 무관하게 항상 헤더 행 한 줄(`month,labeled,reviewed,approvalRate`)만 반환하며 실제 통계 집계를 전혀 호출하지 않는다"* 는 **더 이상 사실이 아니다.** 실데이터가 채워졌다(사용자 신고 "리포트 다운로드 하면 내용이 비어져 있어"의 해소).
  - **본문은 섹션 블록 5개**를 빈 줄로 구분해 순서대로 담는다 — `[누적 학습데이터]`(구분/검수완료/전체) · `[처리현황]`(구분/건수) · `[일별 작업량]`(일자/검수완료건수) · `[이벤트 유형 분포]`(유형/검수완료/전체) · `[작업자별 현황]`(작업자/라벨/진행/검수/오토라벨(%)/반려율(%)). 각 블록은 대괄호 제목 행으로 시작하고 그 다음이 컬럼 이름 행이며, 파일 첫 행에는 생성 기준 일시가 온다. 응답 계약(CSV·UTF-8 BOM 선행·`Content-Disposition` 파일명)은 **무변경**이고 바뀐 것은 본문뿐이다. 근거: `API-058`(v4) · `OverallStatReportCsvWriter`.
  - ★**수치는 전체 구축 현황(`GET /v1/stats/overall`)과 같은 집계 하나에서만 조달한다**(`StatsService.getOverallSummary`). 리포트 전용 집계를 따로 유도하면 화면과 리포트가 다른 것을 말하게 된다 — 이 저장소의 "두 번째 진실원" 결함 패턴이다. `[처리현황]` 은 응답의 5값을 그대로 펴지 않고 화면과 같은 **4구간으로 접는다**(완료=`approved` / 처리중=`inProgress+reviewPending` / 대기=`pending` / 실패=`rejected`).
  - `period` 는 **`[일별 작업량]` 블록의 창만** 정한다 — WEEK 7 / MONTH 30 / QUARTER 90 / YEAR 365일. 매핑의 단일 지점은 `StatsReportPeriod` 이며 나머지 4개 블록은 기간과 무관한 누적·현재 시점 집계다. 리포지토리 쿼리는 이미 `since` 를 파라미터로 받아 **JPQL 변경 없이** 창이 움직이고, 화면 경로(무인자 `getOverallSummary()`)는 `DAILY_WINDOW_DAYS`(30일)를 그대로 넘겨 **동작이 바뀌지 않는다**. 0-fill 도 그대로라 작업 없는 날도 `0` 행으로 남는다.
  - `period` 는 종전대로 `@Pattern` allowlist 검증(위반 시 **400 `INVALID_INPUT`**) 후에만 파일명에 반영된다(CWE-117 방어). **본문 어디에도 요청 파라미터를 되비추지 않는다.**
  - ⚠ **셀에 사람이 쓴 문자열이 들어가면서 새로 생긴 위험 둘** — ①**수식 인젝션(CWE-1236)**: 작업자명·이벤트 표시명이 `=`·`+`·`-`·`@`·탭·캐리지리턴으로 시작하면 Excel 이 수식으로 해석·실행하므로 작은따옴표를 앞세워 무해화한다(**숫자 셀은 대상이 아니다** — 무해화하면 재가공이 깨진다) ②**RFC 4180**: `,`·`"`·개행이 든 값은 큰따옴표로 감싸고 내부 큰따옴표를 이중화한다(이벤트 표시명은 운영자가 바꿀 수 있어 쉼표가 들어올 수 있다). 두 규약은 **모든 셀이 조립 지점 하나**(`OverallStatReportCsvWriter.row`)를 강제로 거쳐 적용된다.
  - `[이벤트 유형 분포]` 는 검수완료·전체를 **`eventTypeCd` 로 짝지어** 한 행에 놓는다(인덱스 위치로 맞추면 수치가 다른 유형에 붙는다). `count=0` 유형도 남긴다. `[작업자별 현황]` 의 비율은 **이미 백분율(0~100)** 이라 다시 100 을 곱하지 않으며(17.2 의 0~1 과 의도된 비대칭), `workers` 가 빈 배열인 것은 자리표시가 아니라 **LABELER 배정 0건**이라는 뜻이므로 예외 없이 헤더 + 안내 문구("작업자 통계가 없습니다") 한 줄을 반환한다.
- 코드: `OverallStatPage`

## 17.4 차트

- `recharts` 사용 (frontend)

## 17.5 관찰가능성 (NFR-007)

- API 응답시간·배치 처리량·외부 API 호출을 **Micrometer + Prometheus** 수집
- 모든 요청에 traceId(MDC) 부여, `X-Trace-Id` 전파
- `/actuator/prometheus` 노출
- 비즈니스 메트릭은 observability 도메인/규칙 참조 → [19](19-external-security-cvat.md)

## 17.6 관련 데이터 (DB)

통계는 `LS_DATA_RAW`/`LS_DATA_SRC`/`LS_RAW_DATA_STATUS`/`LS_TASK_EVNT_LOG` 등 집계. → [18](18-database.md).
