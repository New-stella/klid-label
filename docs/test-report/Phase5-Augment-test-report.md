# Phase 5: 증강 = 새 영상 — 테스트 결과

> 테스트 결과: 전체 PASS (1004 tests, 신규 3건 포함)

## 변경 요약

| 파일 | 변경 | 설명 |
|------|------|------|
| `V46__add_parent_raw_sn.sql` | 생성 | LS_DATA_RAW.PARENT_RAW_SN 컬럼 + 인덱스 |
| `LsDataRaw.java` | 필드+메서드 추가 | parentRawSn + createFromAugment() |
| `LsDataLbl.java` | 메서드 추가 | copyForNewSrc() 라벨 복사 |
| `AugmentResultService.java` | 로직 추가 | SUCCESS 시 새 영상 생성 + 프레임/라벨/메타 복사 |

## 신규 테스트 결과

### AugmentResultServiceTest (3건 추가)

| # | 테스트 | 결과 |
|---|--------|------|
| 1 | V2_증강_SUCCESS_시_새_RAW_SN_생성_PARENT_RAW_SN_참조 | PASS |
| 2 | V2_증강_SUCCESS_시_원본_프레임_라벨_메타_복사 | PASS |
| 3 | V2_증강_FAILED_시_새_영상_미생성 | PASS |

## 기존 테스트 회귀 확인

| 테스트 클래스 | 결과 |
|-------------|------|
| AugmentResultServiceTest (기존 7건) | PASS |
| AugmentControllerTest | PASS |
| AugmentReviewServiceTest | PASS |
| AugmentRequestServiceTest | PASS |

## 수용 기준 달성

- [x] 증강 결과 → 새 RAW_SN 영상 생성 (PARENT_RAW_SN 참조)
- [x] 원본 프레임/라벨/메타 정확히 복사
- [x] 새 영상 PENDING 상태 → 배정/수정/검수 흐름 동일
- [x] FAILED 시 새 영상 미생성
