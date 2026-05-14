# 학습데이터 저작도구 DB 설계서

> **사업명**: AI 기반 지방정부 CCTV 관제지원시스템 구축(2차)  
> **문서명**: 학습데이터 저작도구 DB 설계서  
> **작성일**: 2026-05-14  
> **관련 문서**: [붙임2] 제안요청서(수정)_260303.hwpx

---

## 1. DB 운영 원칙

저작도구는 별도 독립 DB를 두지 않고 `klid_system` 공유 DB를 사용한다. 관제서버도 같은 DB를 조회하므로, 학습데이터 제공을 위한 별도 API를 필수로 두지 않는다.

저작도구는 라벨링, 검수, 비식별 상태, 메타, 증강 결과, 버전 이력을 DB에 기록한다. 관제서버는 승인 완료 데이터를 DB에서 직접 조회해 학습데이터로 추출한다.

---

## 2. 주요 테이블

| 도메인 | 테이블 | 용도 |
|--------|--------|------|
| 사용자 | `MNG_ACCT_USER` | 사용자 기본 정보 |
| 권한 | `MNG_ACCT_AUTHRT`, `MNG_ACCT_USER_AUTHRT` | 사용자 권한 |
| 프로젝트 | `LS_PJT`, `LS_PJT_DATA_MPNG` | 프로젝트와 데이터 매핑 |
| 작업 상태 | `LS_PJT_DATA_STTS` | 영상별 작업/검수 상태 |
| 작업 배정 | `LS_PJT_USER_AUTHRT`, `LS_PJT_USER_AUTHRT_HSTRY` | 작업자 배정과 이력 |
| 작업 이벤트 | `LS_PJT_TASK_EVENT_LOG` | 배정, 재배정, 반려 등 이벤트 |
| 영상 | `LS_DATA_RAW`, `LS_DATA_RAW_HSTRY` | 원본 영상, 비식별 영상, 상태 |
| 프레임 | `LS_DATA_SRC`, `LS_DATA_SRC_HSTRY` | 원본/비식별 프레임 |
| 라벨 | `LS_DATA_LBL`, `LS_DATA_LBL_HSTRY` | 라벨 데이터와 저장 이력 |
| 메타 | `LS_DATA_META`, `LS_DATA_META_HSTRY` | 영상/프레임 메타와 수정 이력 |
| 검수 이슈 | `LS_DATA_ISSUE` | 반려 사유와 이슈 |
| 비식별 신고 | `LS_DEIDENT_REPORT` | 비식별 누락 신고 |
| 증강 | `LS_DATA_AUG` | 증강 결과와 채택/반려 |
| 포털 | `LS_PORTAL_USER_VIDEO` | 포털 사용자 업로드 |
| 설정 | `LS_SYSTEM_CONFIG` | 시스템 설정 |
| 배치 | `MNG_CLIP_SCHEDULE_QUE`, `LS_BATCH_PROC_LOG`, `QRTZ_*` | 큐, 단계 로그, 스케줄러 |

---

## 3. 영상 테이블

`LS_DATA_RAW`는 라벨링 대상 영상의 기준 테이블이다.

| 컬럼 | 설명 |
|------|------|
| `RAW_SN` | 영상 식별자 |
| `VMS_CLIP_ID` | 관제서버 클립 ID |
| `VMS_CCTV_ID` | CCTV ID |
| `EVNT_TYPE_CD` | 이벤트 유형 코드 |
| `LCLGV_CD` | 지자체 코드 |
| `PRVC_TYPE_CD` | 개인정보 유형 |
| `FILE_PATH` | 원본 영상 경로 |
| `DEID_FILE_PATH` | 비식별 영상 경로 |
| `DE_IDNTF_YN` | 비식별 상태 |
| `DATA_STTS_CD` | 영상 처리 상태 |
| `LOCK_STTS_CD` | 재비식별 잠금 상태 |
| `CAPTURED_AT` | 촬영 시각 |
| `DURATION_SEC` | 영상 길이 |

비식별은 영상 단위로 처리한다. 성공 시 `DEID_FILE_PATH`에 비식별 영상 경로를 저장하고 `DE_IDNTF_YN='Y'`로 표시한다.

---

## 4. 프레임 테이블

`LS_DATA_SRC`는 원본 프레임과 비식별 프레임을 모두 저장한다.

| 컬럼 | 설명 |
|------|------|
| `SRC_SN` | 프레임 식별자 |
| `RAW_SN` | 영상 식별자 |
| `FRAME_NO` | 프레임 번호 |
| `FILE_PATH` | 프레임 이미지 경로 |
| `FRM_TYPE_CD` | `RAW` 또는 `DEID` |
| `CAPTURED_AT` | 프레임 캡처 시각 |

동일한 `RAW_SN`, `FRAME_NO`에 대해 `RAW` 프레임과 `DEID` 프레임이 각각 존재할 수 있다.

---

## 5. 라벨 테이블

`LS_DATA_LBL`은 bbox, polygon, segmentation 등 라벨 정보를 저장한다.

| 컬럼 | 설명 |
|------|------|
| `LBL_SN` | 라벨 식별자 |
| `SRC_SN` | 기준 프레임 |
| `LBL_TYPE_CD` | 라벨 유형 |
| `LABEL` | 클래스명 |
| `POINTS_JSON` | 좌표 JSON |
| `AUTO_LBL_YN` | 자동 라벨 여부 |
| `CONF_SCORE` | 신뢰도 |
| `TRACK_ID` | 객체 track id |
| `LBL_SRC_CD` | 라벨 출처 |
| `REG_USER_NO` | 등록 사용자 |

라벨 좌표는 원본 기준 1벌만 유지한다. 비식별 프레임에는 동일 좌표를 적용한다. 증강 데이터도 같은 좌표를 사용하는 것을 기본으로 하며, 해상도를 낮추는 증강 결과는 원본 이미지와 증강 이미지의 크기 비율에 따라 좌표를 재계산한다.

---

## 6. 라벨 이력과 롤백

`LS_DATA_LBL_HSTRY`는 라벨 저장 시점의 snapshot과 Gitea commit hash를 저장한다.

| 컬럼 | 설명 |
|------|------|
| `LBL_HSTRY_SN` | 이력 식별자 |
| `SRC_SN` | 대상 프레임 |
| `GITEA_CMT_HASH` | Gitea commit hash |
| `LABELS_JSON_SNAPSHOT` | 저장 시점 라벨 데이터 |
| `REGISTERED_USER_NO` | 저장 사용자 |
| `REGISTERED_AT` | 저장 시각 |

롤백은 선택한 저장 시점의 snapshot을 현재 라벨 데이터로 복원한다. 롤백 실행 결과도 새 이력으로 남긴다.

---

## 7. 메타 테이블

`LS_DATA_META`는 영상 또는 프레임에 연결된 메타 정보를 저장한다.

| 컬럼 | 설명 |
|------|------|
| `META_SN` | 메타 식별자 |
| `RAW_SN` | 영상 식별자 |
| `META_KEY` | 메타 키 |
| `META_VAL` | 메타 값 |
| `META_TYPE_CD` | 메타 유형 |
| `REG_DT` | 등록 일시 |
| `UPD_DT` | 수정 일시 |

외부 시스템에서 생성된 메타는 저작도구 검토/수정 후 최종 추출 대상이 된다.

---

## 8. 비식별 신고 테이블

`LS_DEIDENT_REPORT`는 비식별 누락 신고를 저장한다.

| 컬럼 | 설명 |
|------|------|
| `RPRT_SN` | 신고 식별자 |
| `RAW_SN` | 영상 식별자 |
| `REPORTER_NO` | 신고 사용자 |
| `REASON` | 신고 사유 |
| `STTS_CD` | 신고 상태 |
| `RPRT_DT` | 신고 일시 |
| `RESOLVED_DT` | 처리 완료 일시 |

미해결 신고가 있는 영상은 학습데이터 추출 대상에서 제외한다.

---

## 9. 증강 테이블

`LS_DATA_AUG`는 증강 결과와 활용 여부를 저장한다.

| 컬럼 | 설명 |
|------|------|
| `DATA_AUG_SN` | 증강 데이터 식별자 |
| `SRC_SN` | 원본 데이터 식별자 |
| `AUG_TYPE_CD` | 증강 유형 |
| `AUG_PROC_STTS_CD` | 처리/결정 상태 |
| `LBL_INTGRT_PCT` | 라벨 무결성 점수 |
| `REJECT_REASON` | 반려 사유 |
| `DECISION_USER_NO` | 결정 사용자 |
| `DECISION_AT` | 결정 일시 |

채택된 증강 결과만 학습데이터 추출 대상에 포함한다. 원본과 동일 해상도인 증강 결과는 원본 라벨 좌표를 그대로 사용할 수 있고, 해상도 변경 증강 결과는 재계산된 좌표를 적용한다.

---

## 10. 추출 기준

관제서버가 학습데이터를 추출할 때는 다음 조건을 적용한다.

| 항목 | 조건 |
|------|------|
| 작업 상태 | 검수 승인 완료 |
| 영상 상태 | 배치 완료 |
| 비식별 상태 | 비식별 성공 |
| 잠금 상태 | 잠금 없음 |
| 비식별 신고 | 미해결 신고 없음 |
| 증강 | 채택 상태만 포함 |

상태 코드의 실제 값은 운영 코드표를 기준으로 확인한다.
