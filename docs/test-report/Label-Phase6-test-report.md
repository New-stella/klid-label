# Label Phase 6 — LS_DATA_LBL_AI_INFO 분리 테스트 결과

> 테스트 결과: 12/12 통과 (LabelControllerTest 13개 + Phase 6 신규 5개 — `kr.co.cudo.authoring.label.LabelControllerTest`, `kr.co.cudo.authoring.batch.step.{Yolo,Sam2,TrackInterpolation}StepTest`)
> 전체 백엔드 회귀: **580 tests / 0 failures / 0 errors / 3 skipped**

## API 테스트 결과 (LabelControllerTest)

### 1. WORKER 본인 미배정 프레임 편집 시 403
| 항목 | 내용 |
|------|------|
| Method | `PUT` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `403 Forbidden` |
| errorCode | `FORBIDDEN` |
| 결과 | PASS |

### 2. WORKER 본인 배정 프레임 BBOX 저장 — AUTO_LBL_YN='N' (수동) — Phase 6 갱신
| 항목 | 내용 |
|------|------|
| Method | `PUT` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `200 OK` |
| Response | `items[0].autoLblYn=="N"` (AiInfo row 없음) |
| 결과 | PASS |

### 3. 자동 라벨 수정 시 AUTO_LBL_YN='Y' 유지 — Phase 6 갱신
| 항목 | 내용 |
|------|------|
| Method | `PUT` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `200 OK` |
| Response | `items[0].autoLblYn=="Y"` (AiInfo SRC_YOLO 보존) |
| 결과 | PASS |

### 4. 좌표 음수 INVALID_INPUT 400
| 항목 | 내용 |
|------|------|
| Method | `PUT` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `400 Bad Request` |
| errorCode | `INVALID_INPUT` |
| 결과 | PASS |

### 5. 좌표 개수 1500 초과 INVALID_INPUT 400 (CWE-770)
| 항목 | 내용 |
|------|------|
| Method | `PUT` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `400 Bad Request` |
| errorCode | `INVALID_INPUT` |
| 결과 | PASS |

### 6. 라벨 조회 GET — autoLblYn/lblSrcCd 응답 — Phase 6 갱신
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `200 OK` |
| Response | `items[0].autoLblYn=="Y"`, `items[0].lblSrcCd=="YOLO"` |
| 결과 | PASS |

### 7. REVIEWER 미배정 프레임 조회 가능 — Phase 6 갱신
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `200 OK` |
| 결과 | PASS |

### 8. WORKER frameImageType 'DEID' 응답
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `200 OK` |
| Response | `frameImageType=="DEID"` |
| 결과 | PASS |

### 9. REVIEWER raw=true 시 frameImageType 'RAW'
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/v1/frames/{srcSn}/labels?raw=true` |
| Status | `200 OK` |
| Response | `frameImageType=="RAW"` |
| 결과 | PASS |

### 10. WORKER raw=true 무시 — frameImageType 'DEID'
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/v1/frames/{srcSn}/labels?raw=true` |
| Status | `200 OK` |
| Response | `frameImageType=="DEID"` |
| 결과 | PASS |

### 11. 잠금 영상 — lockSttsCd 'LOCKED' 포함 — Phase 3+6 갱신 (LS_AUTH_WORK_LOCK 의존)
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `200 OK` |
| Response | `lockSttsCd=="LOCKED"` |
| 결과 | PASS |

### 12. 비잠금 영상 — lockSttsCd null
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `200 OK` |
| Response | `lockSttsCd==null` |
| 결과 | PASS |

### 13. videoId + siblings 응답 포함
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `200 OK` |
| Response | `videoId`, `siblings.length()==5` |
| 결과 | PASS |

## 단위 테스트 — Phase 6 신규 검증

| # | 클래스 | 테스트 | 검증 항목 | 결과 |
|---|--------|--------|-----------|------|
| 1 | YoloAutolabelStepTest | `aiInfoPersistedAlongsideBbox` | BBOX 저장 시 LsDataLblAiInfo SRC_YOLO 동시 저장 | PASS |
| 2 | YoloAutolabelStepTest | `aiInfoNotPersistedWhenBboxSkipped` | BBOX skip 시 AiInfo 도 미저장 | PASS |
| 3 | Sam2SegmentStepTest | `aiInfoPersistedAlongsidePolygon` | POLYGON 저장 시 LsDataLblAiInfo SRC_SAM2 동시 저장 | PASS |
| 4 | TrackInterpolationStepTest | `aiInfoSavedForEveryInterpolatedRow` | 보간 row 마다 LsDataLblAiInfo SRC_INTERPOLATE 저장 | PASS |
| 5 | TrackInterpolationStepTest | `aiInfoNotSavedWhenNoInterpolation` | 보간 대상 없으면 AiInfo saveAll 미호출 | PASS |

## 요약
| # | 테스트 | Method | URL | Status | 결과 |
|---|--------|--------|-----|--------|------|
| 1 | notAssignedWorkerForbidden | PUT | `/v1/frames/{srcSn}/labels` | 403 | PASS |
| 2 | manualBboxStoredAsAutoNo | PUT | `/v1/frames/{srcSn}/labels` | 200 | PASS |
| 3 | editingAutoLabelKeepsAutoYes | PUT | `/v1/frames/{srcSn}/labels` | 200 | PASS |
| 4 | negativeCoordinateRejected | PUT | `/v1/frames/{srcSn}/labels` | 400 | PASS |
| 5 | excessivePointsRejected | PUT | `/v1/frames/{srcSn}/labels` | 400 | PASS |
| 6 | getLabelsByFrame | GET | `/v1/frames/{srcSn}/labels` | 200 | PASS |
| 7 | reviewerCanAccessAnyFrame | GET | `/v1/frames/{srcSn}/labels` | 200 | PASS |
| 8 | labelResponseFrameImageTypeForWorkerIsDeid | GET | `/v1/frames/{srcSn}/labels` | 200 | PASS |
| 9 | labelResponseFrameImageTypeForReviewerRawTrueIsRaw | GET | `/v1/frames/{srcSn}/labels?raw=true` | 200 | PASS |
| 10 | labelResponseFrameImageTypeForWorkerRawTrueStaysDeid | GET | `/v1/frames/{srcSn}/labels?raw=true` | 200 | PASS |
| 11 | lockedVideoResponseIncludesLockSttsCd | GET | `/v1/frames/{srcSn}/labels` | 200 | PASS |
| 12 | unlockedVideoResponseHasNullLockSttsCd | GET | `/v1/frames/{srcSn}/labels` | 200 | PASS |
| 13 | getLabelsIncludesVideoIdAndSiblings | GET | `/v1/frames/{srcSn}/labels` | 200 | PASS |
