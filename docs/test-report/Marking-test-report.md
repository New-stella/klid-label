# Marking API Test Report

## API Summary

| Method | URL | Description | Auth |
|--------|-----|-------------|------|
| POST | `/v1/videos/{rawSn}/markings` | 마킹 생성 (자동/수동) | REVIEWER, WORKER |
| GET | `/v1/videos/{rawSn}/markings` | 마킹 목록 조회 | REVIEWER, WORKER |
| GET | `/v1/videos/{rawSn}/markings/{markingSn}` | 마킹 단건 조회 | REVIEWER, WORKER |
| DELETE | `/v1/videos/{rawSn}/markings/{markingSn}` | 마킹 삭제 | REVIEWER only |

## Test Results

### LsMarkingEntityTest (8 tests)

| # | Test Name | Layer | Result |
|---|-----------|-------|--------|
| 1 | createAuto_정상_생성_STATUS_PENDING | Entity | PASS |
| 2 | createManual_정상_생성 | Entity | PASS |
| 3 | markVlmRequested_상태전이 | Entity | PASS |
| 4 | markVlmCompleted_상태전이 | Entity | PASS |
| 5 | createAuto_intervalSec_0이하_예외 | Entity | PASS |
| 6 | createManual_eventName_null_예외 | Entity | PASS |
| 7 | createAuto_rawSn_null_예외 | Entity | PASS |
| 8 | createManual_videoPath_blank_예외 | Entity | PASS |

### MarkingServiceTest (9 tests)

| # | Test Name | Layer | Result |
|---|-----------|-------|--------|
| 1 | 자동모드_마킹_생성_intervalSec_기반_marks_자동생성 | Service | PASS |
| 2 | 수동모드_마킹_생성_marks_배열_저장 | Service | PASS |
| 3 | 마킹_조회_정상_영상별_목록 | Service | PASS |
| 4 | 마킹_단건_조회_정상 | Service | PASS |
| 5 | 마킹_단건_조회_다른영상_마킹이면_FORBIDDEN | Service | PASS |
| 6 | 미존재_영상_마킹생성시_NOT_FOUND | Service | PASS |
| 7 | 미존재_마킹_조회시_NOT_FOUND | Service | PASS |
| 8 | 마킹_삭제_정상 | Service | PASS |
| 9 | 마킹_삭제_다른영상_마킹이면_FORBIDDEN | Service | PASS |

### MarkingControllerTest (10 tests)

| # | Test Name | Method | URL | Status | Result |
|---|-----------|--------|-----|--------|--------|
| 1 | POST_마킹_자동모드_생성_201 | POST | `/v1/videos/{rawSn}/markings` | 201 | PASS |
| 2 | POST_마킹_수동모드_생성_201 | POST | `/v1/videos/{rawSn}/markings` | 201 | PASS |
| 3 | GET_마킹_목록_조회_200 | GET | `/v1/videos/{rawSn}/markings` | 200 | PASS |
| 4 | GET_마킹_단건_조회_200 | GET | `/v1/videos/{rawSn}/markings/{markingSn}` | 200 | PASS |
| 5 | DELETE_마킹_삭제_REVIEWER_200 | DELETE | `/v1/videos/{rawSn}/markings/{markingSn}` | 200 | PASS |
| 6 | DELETE_마킹_삭제_WORKER_403 | DELETE | `/v1/videos/{rawSn}/markings/{markingSn}` | 403 | PASS |
| 7 | POST_미존재_영상_마킹_404 | POST | `/v1/videos/{nonExistentRawSn}/markings` | 404 | PASS |
| 8 | POST_eventName_빈값_400 | POST | `/v1/videos/{rawSn}/markings` | 400 | PASS |
| 9 | 미인증_요청_401 | POST | `/v1/videos/{rawSn}/markings` | 401 | PASS |
| 10 | GET_단건_다른영상_마킹_접근시_403_IDOR_방어 | GET | `/v1/videos/{rawSn}/markings/{otherMarkingSn}` | 403 | PASS |

## Request/Response Examples

### POST /v1/videos/{rawSn}/markings (AUTO mode)

**Request:**
```json
{
  "eventName": "화재",
  "mode": "AUTO",
  "intervalSec": 10,
  "marks": null
}
```

**Response (201):**
```json
{
  "success": true,
  "data": {
    "markingSn": 1,
    "rawSn": 1,
    "eventName": "화재",
    "markingMode": "AUTO",
    "intervalSec": 10,
    "videoPath": "/var/raw/clip.mp4",
    "marks": [
      {"frameIndex": 0, "timestamp": "00:00"},
      {"frameIndex": 300, "timestamp": "00:10"},
      {"frameIndex": 600, "timestamp": "00:20"},
      {"frameIndex": 900, "timestamp": "00:30"}
    ],
    "status": "PENDING",
    "createdAt": "2026-05-26T19:50:00"
  },
  "message": null,
  "errorCode": null
}
```

### POST /v1/videos/{rawSn}/markings (MANUAL mode)

**Request:**
```json
{
  "eventName": "침입",
  "mode": "MANUAL",
  "intervalSec": null,
  "marks": [
    {"frameIndex": 0, "timestamp": "00:00"},
    {"frameIndex": 150, "timestamp": "00:05"},
    {"frameIndex": 300, "timestamp": "00:10"}
  ]
}
```

**Response (201):**
```json
{
  "success": true,
  "data": {
    "markingSn": 2,
    "rawSn": 1,
    "eventName": "침입",
    "markingMode": "MANUAL",
    "intervalSec": null,
    "videoPath": "/var/raw/clip.mp4",
    "marks": [
      {"frameIndex": 0, "timestamp": "00:00"},
      {"frameIndex": 150, "timestamp": "00:05"},
      {"frameIndex": 300, "timestamp": "00:10"}
    ],
    "status": "PENDING",
    "createdAt": "2026-05-26T19:50:00"
  },
  "message": null,
  "errorCode": null
}
```
