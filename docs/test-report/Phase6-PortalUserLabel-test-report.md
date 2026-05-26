# Phase 6: 포털 데이터마트 Load + 사용자 작업 별도 적재 — 테스트 결과

> 테스트 결과: 전체 PASS (신규 7건 포함)

## 변경 요약

| 파일 | 변경 | 설명 |
|------|------|------|
| `V47__create_ls_portal_user_label.sql` | 생성 | LS_PORTAL_USER_LABEL 테이블 |
| `LsPortalUserLabel.java` | 생성 | 포털 사용자 라벨 Entity |
| `LsPortalUserLabelRepository.java` | 생성 | @ControlRepo Repository |
| `PortalUserLabelRequest.java` | 생성 | 사용자 라벨 저장 DTO |
| `PortalUserLabelResponse.java` | 생성 | 사용자 라벨 응답 DTO |
| `PortalLabelService.java` | 수정 | 데이터마트 Load + 사용자 라벨 저장/조회 |
| `PortalLabelController.java` | 수정 | 3개 엔드포인트 추가 |

## API 테스트 결과

### 1. GET /v1/portal/datamart/labels
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/v1/portal/datamart/labels?rawSn={rawSn}` |
| Status | `200 OK` |
| 결과 | PASS |

### 2. POST /v1/portal/user-labels
| 항목 | 내용 |
|------|------|
| Method | `POST` |
| URL | `/v1/portal/user-labels` |
| Status | `201 Created` |
| 결과 | PASS |

### 3. GET /v1/portal/user-labels
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/v1/portal/user-labels?rawSn={rawSn}` |
| Status | `200 OK` |
| 결과 | PASS |

## 신규 테스트 (PortalUserLabelServiceTest — 7건)

| # | 테스트 | 결과 |
|---|--------|------|
| 1 | V2_데이터마트_라벨_Load_정상 | PASS |
| 2 | V2_데이터마트_rawSn_null_시_INVALID_INPUT | PASS |
| 3 | V2_사용자_작업_데이터_별도_적재_원본_미수정 | PASS |
| 4 | V2_사용자_라벨_저장_토큰_없으면_401 | PASS |
| 5 | V2_본인_작업_라벨_조회_IDOR_본인만 | PASS |
| 6 | V2_다른_사용자_데이터_접근_불가_IDOR | PASS |
| 7 | V2_본인_라벨_조회_rawSn_null_시_INVALID_INPUT | PASS |

## 수용 기준 달성

- [x] 포털 DB에서 데이터마트 데이터 Load
- [x] 사용자 수정 → LS_PORTAL_USER_LABEL에 별도 저장 (원본 미수정)
- [x] IDOR 방어 (본인 작업 데이터만 접근)
