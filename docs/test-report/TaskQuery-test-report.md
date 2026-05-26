# TaskQuery API Test Report

> Phase 4: 관제서버 조회 API — TaskQueryController + TaskQueryService

## API Endpoints

| Method | URL | Description |
|--------|-----|-------------|
| GET | `/v1/tasks/{rawSn}/summary` | 영상별 요약 (프레임/라벨/메타 카운트, 상태, 최종 수정일) |
| GET | `/v1/tasks/{rawSn}/labels` | 영상별 라벨 목록 (프레임 단위) |
| GET | `/v1/tasks/{rawSn}/labels?frameIds=1,2,3` | 특정 프레임 라벨 필터 조회 |
| GET | `/v1/tasks/{rawSn}/meta` | 영상별 메타데이터 |

## Controller Test (MockMvc — `TaskQueryControllerTest`)

| # | Test | Method/URL | Status | Request | Response |
|---|------|-----------|--------|---------|----------|
| 1 | 영상별_요약_조회_정상_200 | GET /v1/tasks/{rawSn}/summary | 200 | Bearer REVIEWER token | `{ success: true, data: { rawSn, totalFrames: 1, totalLabels: 1, totalMeta: 1 } }` |
| 2 | 영상별_라벨_목록_조회_정상_200 | GET /v1/tasks/{rawSn}/labels | 200 | Bearer REVIEWER token | `{ success: true, data: [{ srcSn, frameNo: 0, labels: [{ label: "person" }] }] }` |
| 3 | 특정_프레임_라벨_필터_조회_frameIds_파라미터 | GET /v1/tasks/{rawSn}/labels?frameIds={srcSn} | 200 | Bearer REVIEWER token | `{ data: [1 item] }` (filtered) |
| 4 | 영상별_메타데이터_조회_정상_200 | GET /v1/tasks/{rawSn}/meta | 200 | Bearer REVIEWER token | `{ data: { rawSn, items: [{ metaKey: "weather", metaVal: "sunny" }] } }` |
| 5 | 미존재_영상_조회시_404 | GET /v1/tasks/999999/summary | 404 | Bearer REVIEWER token | `{ errorCode: "NOT_FOUND" }` |
| 6 | 미인증_조회시_401 | GET /v1/tasks/{rawSn}/summary | 401 | (no token) | `{ errorCode: "UNAUTHORIZED" }` |
| 7 | 응답에_원본_이미지_경로_미포함 | GET /v1/tasks/{rawSn}/labels | 200 | Bearer REVIEWER token | filePath/deidentFilePath 필드 부재 확인 |

## Service Test (Mockito — `TaskQueryServiceTest`)

| # | Test | Layer | Result |
|---|------|-------|--------|
| 1 | getSummary_정상_라벨_메타_카운트_정확 | Service | PASS |
| 2 | getSummary_프레임_없는_영상_0_반환 | Service | PASS |
| 3 | getSummary_미존재_rawSn_NOT_FOUND_예외 | Service | PASS |
| 4 | getLabels_전체_프레임_라벨_반환 | Service | PASS |
| 5 | getLabels_frameIds_필터_적용 | Service | PASS |
| 6 | getLabels_빈_frameIds_전체_반환 | Service | PASS |
| 7 | getLabels_미존재_rawSn_NOT_FOUND_예외 | Service | PASS |
| 8 | getMeta_정상_메타_목록_반환 | Service | PASS |
| 9 | getMeta_메타_없는_영상_빈_목록 | Service | PASS |
| 10 | getMeta_미존재_rawSn_NOT_FOUND_예외 | Service | PASS |

## Summary

- Total: **17** tests (7 Controller + 10 Service)
- Passed: **17/17**
- Failed: **0**

## Security Notes

- CWE-359: 응답 DTO(record)에 filePath, deidentFilePath 미포함 — 구조적으로 불가
- CWE-639: `@PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")` 역할 검증
- CWE-89: JPA 파라미터 바인딩만 사용 (SQL Injection 방어)
- CWE-20: `@PathVariable Long rawSn` 타입 안전, `List<Long> frameIds` Spring 자동 파싱
