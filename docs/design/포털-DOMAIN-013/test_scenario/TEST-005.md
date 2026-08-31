---
logicraft_item: TEST-005
type: test_scenario
version: 12
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-31T11:08:59.717Z
status: CHANGED
prev_version: 11
content_hash: 0dacd1a70f4ddd93487a25ea3f2ff0e16af98c56e473ec67aa35761cd41f1677
stale: false
raw: ./_raw/TEST-005.json
links:
  references: ["[[SCREEN-029]]", "[[UC-024]]"]
---

# 포털 채널 데이터 읽기 Load 정상 흐름

## kind

integration

## notes

시험시나리오 KLID-AT-IT-TS-005. 대외 연동 경계: 포털 DB(외부 채널) 읽기 Load(KLID-AT-II-008 inbound DB READ). 관련 요구사항 RQ-SFR-19(R2 미수록). 핵심 테이블 LS_PORTAL_USER_LABEL. 정상 흐름(happy path)만 수록.

[검증 대상 대응] 본 시험이 검증하는 포털 DB 읽기 Load 흐름은 UC-024(포털 라벨 작업)의 본문—관제→데이터마트→포털 DB 적재 후 저작도구가 Load—와 직접 대응하므로 covers_use_cases 에 UC-024 를 포함한다. 다만 UC-024 자신이 R1 기능요구사항 대응 없음을 명시하므로, 이 시나리오의 검증 대상 자체가 적절한지는 관제 협의가 필요한 별도 판단 사항이다. ⚠ **[재검토 대상 — 검증 대상 자체가 성립하지 않는다]** 이 시나리오는 「포털 DB 에서 영상·라벨·메타를 조회」를 검증하는데 **두 겹으로 성립하지 않는다.** ①저작도구는 그 방향으로 읽은 적이 없다 — 포털 라벨 경로는 저작도구(control) DB 를 쓰며, 포털 DB 는 반대로 내보내던 곳이었다. ②2026-08-31 확정으로 **저작도구와 포털은 서로의 DB 에 접근하지 않는다**(ADR-012). 승인된 산출물이라 본문·단계는 고치지 않고 표기만 남긴다 — **폐기할지, 「배포물을 자기 DB 에 적재한 뒤 조회」로 재작성할지는 별도 판단이 필요**하며 그 방향의 조회 창구도 포털 회신 대기다.

## steps

### [1]

- **seq**: 1
- **note**: TC-011 | manual_test / JDBC 읽기 전용
- **action**: 포털 DB 조회
- **expected**: 포털 DB 에서 영상·라벨·메타를 조회만 수행하고(쓰기 0건) 저작도구 원본과 데이터마트가 변경되지 않는다.
- **test_item**: 별도 데이터소스로 포털 영상·라벨·메타를 읽기 조회하는지 확인
- **input_data**: 【영상 식별자】"3001" (응답: 영상 정보·라벨 목록·시계열 메타 목록 JSONB)
- **screen_ref**: SCREEN-029
- **preconditions**: 포털 DB에 영상·라벨·메타가 적재돼 있고 포털 데이터소스에 연결 가능하다

### [2]

- **seq**: 2
- **note**: TC-011 | manual_test / 화면 확인
- **action**: 라벨링 화면 표시
- **expected**: 라벨링 캔버스에 포털 영상·기존 라벨이 표시되고 원본·데이터마트는 수정되지 않는다. 【검증】화면(SCREEN-029 포털 라벨링 화면): 포털 영상+기존 라벨이 캔버스·객체 목록에 표시(원본·데이터마트 미수정).
- **test_item**: 조회 데이터가 라벨링 캔버스 화면에 표시되는지 확인
- **input_data**: 【영상 식별자】"3001"
- **screen_ref**: SCREEN-029
- **preconditions**: 순번1 조회 성공

### [3]

- **seq**: 3
- **note**: TC-011 | DB SQL 확인
- **action**: 사용자 라벨 별도 적재
- **expected**: 사용자 라벨이 별도로 1건 적재되고 원본·데이터마트에는 반영되지 않는다(단방향). 【검증】DB: SELECT PORTAL_USER_NO, SRC_RAW_SN, SRC_DATA_SRC_SN, LBL_TYPE_CD, POINT_CN FROM LS_PORTAL_USER_LABEL WHERE SRC_RAW_SN=:sourceRawSn AND SRC_DATA_SRC_SN=:sourceSrcSn → LBL_TYPE_CD='BBOX'·POINT_CN 저장·PORTAL_USER_NO=토큰 sub 1행(원본 LS_DATA_LBL 미반영, 단방향)
- **test_item**: 포털 사용자 작업 라벨이 단방향 별도 적재되는지 확인
- **input_data**: 【영상 식별자】"3001" 【프레임 식별자】"4001" 【라벨 유형 코드】"BBOX" 【좌표점】"[[100,100],[200,200]]" 〔PORTAL_USER_NO=토큰 sub에서 세팅, 입력 아님〕
- **screen_ref**: SCREEN-029
- **preconditions**: 순번2 표시·사용자 편집

## status

draft

## objective

포털 DB(외부 채널)에서 영상·라벨·메타를 별도 데이터소스로 읽기 조회해 라벨링 캔버스 화면 표시 데이터를 확보하고, 사용자 작업은 별도로 단방향 적재(원본·데이터마트 미수정)하는 정상 흐름을 검증한다.

## related_apis

_(empty)_

## preconditions

- 대상 영상은 검수 승인(APPROVED) 상태다 — 데이터마트에 노출된 미승인 영상은 이 흐름의 대상이 아니다(쿼리 게이트로 미포함/403).

## verifies_nfrs

_(empty)_

## attached_files

_(empty)_

## related_domains

_(empty)_

## covers_use_cases

- UC-024

## exercises_screens

- SCREEN-029

## verifies_requirements

_(empty)_
