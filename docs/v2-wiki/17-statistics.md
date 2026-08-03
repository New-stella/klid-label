# 17. 통계 · 대시보드

> 출처: D2(SC-011/020/021), 코드(`stats/`, `frontend stat/`, `dashboard/`)
> 관련: [12 검수·배정](12-review-assignment.md)

화면: `KLID-AT-SC-011`(대시보드 `/dashboard`), `SC-020`(작업자 통계 `/stat`), `SC-021`(전체 통계 `/stat/overall`, REVIEWER). 코드: `stats/`(9 파일).

## 17.0 집계 기준 — 검수완료 / 전체 분리 (2026-08-03 확정, 구속)

누적 카드와 이벤트 유형 분포는 **두 기준을 한 카드 안에 병기**한다. 주 수치는 **검수완료**, 보조는 **전체 + 완료율**이다.

```
┌─ 이미지 학습데이터 ──────────┐
│  1,200 장   ← 검수완료(주 수치)
│  검수완료 기준 · 전체 5,000장 (완료율 24%)
└──────────────────────────────┘
```

- **검수완료 기준** = `LS_RAW_DATA_STATUS.DATA_STTS_CD = 'APPROVED'` (검수 승인 = 작업 완료 = 학습데이터 확정). 핵심 산출물 목표(이미지 10만장·영상 5,000건) 진척은 이 값으로 판단한다.
- **적용 대상**: 누적 이미지·영상 카드 + 이벤트 유형 분포(대시보드는 이미지·영상 **그리드 2개 모두**, 전체 구축 현황은 파이 + 가로막대). 카드와 분포가 **같은 모집단**을 가리키도록 통일한다.
- **미적용(현행 유지)**: 처리 현황 5카드(대기/진행중/검수대기/승인/반려), 작업자별 현황 표, 대시보드 KPI 4카드 — 이미 상태별로 분해되어 있다.
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
- 코드: `WorkerStatPage`, `stats/StatsController`

## 17.3 전체 통계 (SC-021, REVIEWER)

- 프로젝트(전체 구축) 수준 KPI
- `이미지 학습데이터` · `영상 학습데이터` 누적 카드 + 이벤트 유형 분포(파이 + 가로막대) → **17.0 기준 분리 적용**. 카드 제목의 "학습데이터"가 실제로 검수완료 확정분을 가리키게 된 것이 이 변경의 핵심이다
- 처리 현황 5카드 · 작업자별 현황 표 · 일별 전체 작업량 차트는 현행 유지
- **CSV 리포트 다운로드** (`KLID-AT-SC-021-RPT`)
- 코드: `OverallStatPage`

## 17.4 차트

- `recharts` 사용 (frontend)

## 17.5 관찰가능성 (NFR-007)

- API 응답시간·배치 처리량·외부 API 호출을 **Micrometer + Prometheus** 수집
- 모든 요청에 traceId(MDC) 부여, `X-Trace-Id` 전파
- `/actuator/prometheus` 노출
- 비즈니스 메트릭은 observability 도메인/규칙 참조 → [19](19-external-security-cvat.md)

## 17.6 관련 데이터 (DB)

통계는 `LS_DATA_RAW`/`LS_DATA_SRC`/`LS_RAW_DATA_STATUS`/`LS_TASK_EVENT_LOG` 등 집계. → [18](18-database.md).
