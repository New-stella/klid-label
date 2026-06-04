# 17. 통계 · 대시보드

> 출처: D2(SC-011/020/021), 코드(`stats/`, `frontend stat/`, `dashboard/`)
> 관련: [12 검수·배정](12-review-assignment.md)

화면: `KLID-AT-SC-011`(대시보드 `/dashboard`), `SC-020`(작업자 통계 `/stat`), `SC-021`(전체 통계 `/stat/overall`, REVIEWER). 코드: `stats/`(9 파일).

## 17.1 대시보드 (SC-011)

영상/라벨/검수 진행도 요약 KPI 카드. 코드: `frontend dashboard/` + `DashboardPage`.

## 17.2 작업자 통계 (SC-020)

- 라벨러별 진행도, 라벨 개수 등
- 코드: `WorkerStatPage`, `stats/StatsController`

## 17.3 전체 통계 (SC-021, REVIEWER)

- 프로젝트(전체 구축) 수준 KPI
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
