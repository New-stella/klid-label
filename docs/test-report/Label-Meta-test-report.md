# Label / Meta (Phase 6) 테스트 결과

> 테스트 결과: 32/32 통과 (Phase 6 신규)
> 누적: 147/147 통과 (Phase 0~6 backend 전체)

## API 테스트 결과 (Controller — MockMvc)

### 1. LabelController_본인_배정_아닌_프레임_편집시_403 (IDOR 차단)
| 항목 | 내용 |
|------|------|
| Method | `PUT` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `403 Forbidden` |
| Auth | `Bearer {WORKER JWT — 미배정}` |
| Response Body | `{ "success": false, "errorCode": "FORBIDDEN", "message": "본인에게 배정되지 않은 영상입니다." }` |
| 결과 | PASS |

### 2. LabelController_bbox_좌표_저장시_AUTO_LBL_YN_N_저장
| 항목 | 내용 |
|------|------|
| Method | `PUT` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `200 OK` |
| Auth | `Bearer {WORKER JWT — 배정자}` |
| Request Body | `{ "items": [{ "lblTypeCd": "BBOX", "label": "person", "points": [[10,10],[50,50]] }] }` |
| Response Body | `{ "success": true, "data": { "items": [{ "id": ..., "autoLblYn": "N", ... }] } }` |
| DB | `LS_DATA_LBL.AUTO_LBL_YN = 'N'` (수동 입력) |
| 결과 | PASS |

### 3. LabelController_오토_라벨_수정시_AUTO_LBL_YN은_Y_유지
| 항목 | 내용 |
|------|------|
| Method | `PUT` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `200 OK` |
| Auth | `Bearer {WORKER JWT}` |
| Request Body | 기존 자동 라벨 id 동봉, 좌표만 변경 |
| 정책 검증 | AUTO_LBL_YN 'Y' 유지 (Mass Assignment 방어 — DTO 의 autoLblYn 필드 무시) |
| 결과 | PASS |

### 4. LabelController_좌표_음수_입력시_INVALID_INPUT_400
| 항목 | 내용 |
|------|------|
| Method | `PUT` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `400 Bad Request` |
| Request Body | `{ "items": [{ "points": [[-1, 10], [50, 50]], ... }] }` |
| Response Body | `{ "errorCode": "INVALID_INPUT", "message": "좌표는 0 이상이어야 합니다 ..." }` |
| 결과 | PASS |

### 5. LabelController_좌표_개수_과다_입력시_INVALID_INPUT_400 (DoS 방어)
| 항목 | 내용 |
|------|------|
| Method | `PUT` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `400 Bad Request` |
| Request Body | polygon 1500 점 |
| Response Body | `{ "errorCode": "INVALID_INPUT", "message": "라벨당 좌표 개수 초과 (최대 1000 점)" }` |
| 보안 | CWE-770 — 단일 라벨 좌표 점 최대 1000 개 |
| 결과 | PASS |

### 6. LabelController_라벨_조회_GET_정상
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `200 OK` |
| Auth | `Bearer {WORKER JWT — 배정자}` |
| Response Body | `{ "data": { "items": [{ "label": "person", "autoLblYn": "Y", ... }] } }` |
| 결과 | PASS |

### 7. LabelController_REVIEWER는_미배정_프레임도_조회_가능
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/v1/frames/{srcSn}/labels` |
| Status | `200 OK` |
| Auth | `Bearer {REVIEWER JWT}` |
| 정책 | REVIEWER 는 모든 프레임 조회·수정 가능 (검수 책임) |
| 결과 | PASS |

### 8. MetaController_외부_생성_메타_GET_REVIEWER
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/v1/frames/{srcSn}/meta` |
| Status | `200 OK` |
| Auth | `Bearer {REVIEWER JWT}` |
| Response Body | `{ "data": { "items": [{ "metaKey": "weather", "metaVal": "rain" }, ...] } }` |
| 결과 | PASS |

### 9. MetaController_외부_생성_메타_GET_WORKER_배정자
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/v1/frames/{srcSn}/meta` |
| Status | `200 OK` |
| Auth | `Bearer {WORKER JWT — 배정자}` |
| 결과 | PASS |

### 10. MetaController_메타_수정_PUT_정상_동작
| 항목 | 내용 |
|------|------|
| Method | `PUT` |
| URL | `/v1/frames/{srcSn}/meta` |
| Status | `200 OK` |
| Request Body | `{ "items": [{ "metaKey": "weather", "metaVal": "snow" }] }` |
| 정책 (V1.7) | 외부 생성 메타의 값만 수정 가능, 키 추가/삭제 미제공 |
| DB | `LS_DATA_META.META_VAL = 'snow'` |
| 결과 | PASS |

## Service / Util 테스트

| # | 테스트 | 영역 | 결과 |
|---|--------|------|------|
| 1 | TrackInterpolator_RECTANGLE_보간_정확도_0_01_이하 | CVAT 포팅 (트랙 보간) | PASS |
| 2 | TrackInterpolator_회전_180도_경계_최단_경로 | CVAT 포팅 (회전) | PASS |
| 3 | TrackInterpolator_회전_같은_각도시_불변 | CVAT 포팅 | PASS |
| 4 | TrackInterpolator_키프레임_정확히_매칭시_동일_좌표 | CVAT 포팅 | PASS |
| 5 | TrackInterpolator_타겟_프레임이_트랙_범위_밖이면_경계_propagate | CVAT 포팅 | PASS |
| 6 | TrackInterpolator_POLYGON_미지원_NotImplemented | CVAT 포팅 (V1.7+ TODO) | PASS |
| 7 | TrackInterpolator_빈_키프레임_입력시_예외 | CVAT 포팅 | PASS |
| 8 | MaskRleConverter_RLE_양방향_변환_무결성_1000회 | CVAT 포팅 (RLE) | PASS |
| 9 | MaskRleConverter_빈_mask_RLE_정상_처리 | CVAT 포팅 | PASS |
| 10 | MaskRleConverter_첫_픽셀이_on이면_맨_앞에_0_run_삽입 | CVAT 포팅 (CVAT 규칙) | PASS |
| 11 | MaskRleConverter_전체_on_mask_RLE_정상_처리 | CVAT 포팅 | PASS |
| 12 | MaskRleConverter_DoS_최대_크기_초과시_예외 | 보안 (CWE-770) | PASS |
| 13 | CoordinateTransformer_45도_회전_역변환_원점_복귀 | CVAT 포팅 (좌표) | PASS |
| 14 | CoordinateTransformer_스케일_역변환_원점_복귀 | CVAT 포팅 | PASS |
| 15 | CoordinateTransformer_translate_역변환_원점_복귀 | CVAT 포팅 | PASS |
| 16 | CoordinateTransformer_90도_회전_좌표_검증 | CVAT 포팅 | PASS |
| 17 | CoordinateTransformer_빈_리스트_입력시_빈_리스트_반환 | CVAT 포팅 | PASS |
| 18 | LabelPointSerializer_Point_리스트_JSON_배열_직렬화 | 직렬화 | PASS |
| 19 | LabelPointSerializer_JSON_배열_Point_리스트_역직렬화 | 직렬화 | PASS |
| 20 | LabelPointSerializer_빈_배열_역직렬화시_빈_리스트 | 직렬화 | PASS |
| 21 | LabelPointSerializer_null_입력시_각각_null_빈_리스트 | 직렬화 | PASS |
| 22 | Sam2TrackService_연속_프레임_동일_TRCK_ID_전파 | 라벨 트랙 | PASS |

## 요약
| # | 테스트 | Method | URL | Status | 결과 |
|---|--------|--------|-----|--------|------|
| 1 | LabelController_본인_배정_아닌_프레임_편집시_403 | PUT | /v1/frames/{srcSn}/labels | 403 | PASS |
| 2 | LabelController_bbox_저장_AUTO_LBL_YN_N | PUT | /v1/frames/{srcSn}/labels | 200 | PASS |
| 3 | LabelController_오토_라벨_수정시_AUTO_LBL_YN_Y_유지 | PUT | /v1/frames/{srcSn}/labels | 200 | PASS |
| 4 | LabelController_좌표_음수_INVALID_INPUT | PUT | /v1/frames/{srcSn}/labels | 400 | PASS |
| 5 | LabelController_좌표_개수_과다_INVALID_INPUT (DoS) | PUT | /v1/frames/{srcSn}/labels | 400 | PASS |
| 6 | LabelController_라벨_조회 | GET | /v1/frames/{srcSn}/labels | 200 | PASS |
| 7 | LabelController_REVIEWER_미배정_조회 | GET | /v1/frames/{srcSn}/labels | 200 | PASS |
| 8 | MetaController_GET_REVIEWER | GET | /v1/frames/{srcSn}/meta | 200 | PASS |
| 9 | MetaController_GET_WORKER_배정자 | GET | /v1/frames/{srcSn}/meta | 200 | PASS |
| 10 | MetaController_PUT_수정 | PUT | /v1/frames/{srcSn}/meta | 200 | PASS |

## CVAT 포팅 검증 (portable-modules 1/2/6)

| 모듈 | 자바 클래스 | 검증 포인트 |
|------|--------------|-------------|
| 01 트랙 보간 | `common/util/TrackInterpolator` | RECTANGLE 선형 보간 정확도 0.01 이하, 회전 180도 경계 최단 경로 (`(angle+180)%360-180`), 트랙 범위 밖 propagate, POLYGON 미지원 (V1.7+ TODO) |
| 02 MASK ↔ RLE | `common/util/MaskRleConverter` | CVAT 규칙(첫 run = off) 준수, 1000 회 random round-trip 무결성, 빈 mask / 전체 on / 첫 픽셀 on 케이스, 1M 픽셀 초과 거부 |
| 06 좌표 변환 | `common/util/CoordinateTransformer` | 회전·스케일·translate 모두 1e-9 정확도로 역변환 원점 복귀, 90도 회전 결과 직접 검증 |

## 보안 검증
| CWE | 항목 | 검증 위치 |
|-----|------|-----------|
| CWE-639 | IDOR (라벨/메타) — WORKER 본인 배정만 | `LabelService.verifyAccess`, `MetaService.verifyAccess` |
| CWE-915 | Mass Assignment — autoLblYn 응답 전용 | `LsDataLbl.updateUserContent` (autoLblYn 변경 불가) |
| CWE-770 | DoS — 좌표 1000점 / 한 요청 500건 / mask 1M 픽셀 / track 50 프레임 | `LabelService`, `LabelBulkUpsertRequest`, `MaskRleConverter`, `Sam2TrackRequest` |
| CWE-20 | 입력 검증 — 음수 좌표, lblTypeCd 화이트리스트 | `LabelService.validatePoints`, DTO `@Pattern` |
| CWE-502 | Insecure Deserialization 방어 — Jackson 안전 모드 | `LabelPointSerializer` (defaultTyping 미사용) |
| CWE-345 | path/body 식별자 불일치 | `LabelController.sam2Track` |
