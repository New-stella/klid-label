---
logicraft_item: EXTSYS-005
type: external_system
version: 14
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-25T10:52:15.708Z
status: CHANGED
prev_version: 12
content_hash: 7fad21ebbe7768911ecdffced1e904f1e6b46f01f748fe012dde6088664d4821
stale: false
raw: ./_raw/EXTSYS-005.json
links:
  based_on: ["[[ADR-007]]"]
  depends_on_backward: ["[[DOMAIN-016]]"]
  provided_by_backward: ["[[INT-007]]", "[[INT-010]]", "[[INT-011]]"]
---

# 관제서버 (데이터마트 동기화)

## kind

data

## status

active

## vendor

관제서버(내부 타 서브시스템)

## brownfield

### status

modified

### decided_by

ADR-007

### change_kind

- component-replace

### diff_summary

양방향 M2M → 단방향 outbound 통지 + inbound 조회 API

### legacy_source

#### type

other

#### identifier

1차·초기 고도화 구상의 양방향 M2M 통합(관제서버·외부 학습데이터 시스템 송수신 API + M2M 인증 인프라, deprecated)

## owner_team

관제서버팀

## criticality

high

## description

관제지원시스템(관제서버). 저작도구는 검수 완료된 학습데이터를 관제로 통지하고, 관제가 그것을 데이터마트로 가져간다. **데이터마트 구축·검색·다운로드 자체는 범위 외**(외부 제공 시스템 책임).

## 연동 표면 4종
1. **outbound 통지**(INT-007) — TASK_COMPLETED(검수 승인 시) / TASK_MODIFIED(완료된 영상의 수정이 재검수에서 승인될 때). 영상 1건 단위, 라벨·메타 본문 미포함. **경로·페이로드가 관제 정본(API-251/API-285)과 정합 완료** — 인증(x-access-token)만 미확정(B-4).
2. **inbound 조회 API** — GET /v1/tasks/{rawSn}/summary|labels|meta. 관제가 통지 수신 후 상세를 가져가는 경로(JWT+역할+IDOR 가드).
3. **데이터마트 적재용 View 4종**(INT-010 참조) — klid_at 스키마, 검수 완료(APPROVED) 영상만 노출: V_COMPLETED_VIDEO(+export 폴더 경로·프레임수·비식별 영상 경로·대표 이미지 경로) · V_COMPLETED_FRAME · V_COMPLETED_LABEL_CHANGE · V_COMPLETED_META.
4. **파생영상(증강·해상도) 메타** — ORGNL_RAW_SN not null 인 행은 ORGNL_VDO_PATH_NM(개명 전 ORIGINAL_VIDEO_PATH) 가 NULL 로 동결되고 DE_IDNTF_FILE_PATH_NM(V138)으로 픽업(★BREAKING, 아래 참조).

## 활성화 — 환경별 (확정, 불명 없음)
| 환경 | authoring.control-notify.enabled | 실질 |
|---|---|---|
| local | true(오버라이드) | 실배선 |
| dev/stg/prd | false(기본 상속, 오버라이드 없음) | 미연동 |

## ★★ 관제 정본과 대조 — 경로·페이로드 정합 완료, 인증만 미확정
2026-07-27 조사 시점엔 경로(404)·인증(401)·페이로드(422) 전부 불일치였으나, **경로·페이로드는 정합 완료**되었다(상세는 INT-007). **인증(x-access-token)만 미확정 — B-4 회신 대기**, 회신 전까지 auth_type=none 유지.

## ★★ 관제 협의 대상 (BREAKING, 관제 측 소비 로직 파손 가능)
1. **데이터마트 라벨내용 뷰 제거(V114/V137, ADR-037)** — V_COMPLETED_LABEL/V_COMPLETED_LABEL_ATTR DROP. 라벨 본문은 검수완료 export JSON 파일에만 존재. 이 두 뷰를 SELECT 하던 관제 쿼리는 파손됨 — **관제 협의 대상**.
2. **파생영상 ORGNL_VDO_PATH_NM NULL** — ORGNL_RAW_SN not null 행은 원본영상 자체가 없어(비식별 사본만 존재) 메타 동결 시 원본 경로가 항상 NULL. 관제는 DE_IDNTF_FILE_PATH_NM(V138)으로 픽업해야 하며, 개명 전 이름(ORIGINAL_VIDEO_PATH)을 기대하던 소비 로직은 **파손 가능** — **관제 협의 대상**.
3. **관제 통지 API 계약 전면 교체** — 구 단일경로(/api/v1/notify)를 신뢰하던 관제 측 통합 테스트/로직이 있었다면 파손. 상세 INT-007 — **관제 협의 대상**.
4. **완료 영상 뷰의 썸네일 조달원 교체** — 대표 이미지 경로를 관제 인입값 pass-through 로 넘기던 방식을 폐기하고, 저작도구가 보유한 비식별 첫 프레임의 절대경로로 바꾼다. 협의가 필요한 이유는 다음과 같다: (가) 인입 원장의 썸네일파일경로명 컬럼이 제거되므로, 관제가 이미 그 컬럼에 값을 넣고 있었다면 컬럼이 없어지는 순간 썸네일만이 아니라 영상 인입 INSERT 전체가 실패한다 — 배포 전 통보가 선행돼야 한다. (나) 뷰 썸네일 값의 의미가 바뀐다 — 관제가 보낸 경로가 아니라 저작도구가 산출한 비식별 프레임 경로이며, 그 파일의 소유·수명 주체가 관제에서 저작도구로 옮겨간다. (다) 그 컬럼의 폭이 넓어진다 — 관제 적재 컬럼이 좁으면 잘리거나 적재가 실패한다 — **관제 협의 대상**. 대표 이미지 후보는 프레임 페어 뷰와 같은 조건으로 거르므로, 그 뷰에 나오지 않는 프레임이 대표로 나가지 않는다.

## 관제 협의 대상 — 프레임만 이관한 원본의 비식별 축
- 프레임만 이관한 원본은 `V_COMPLETED_VIDEO.DE_IDNTF_YN` 이 미수행으로 나가고 `DE_IDNTF_FILE_PATH_NM` 도 빈 값일 수 있다 — 비식별할 영상 자체가 없으므로 그것이 사실이며, 비식별의 실체는 프레임 축에 있다. 승인된 영상이므로 뷰에서 감추거나 비우지 않는다. 관제는 이 조합을 결함으로 보지 않아야 하며, 해석을 바꾸려면 협의가 선행된다(관제 협의 대상).
기존 계약 필드를 바꾸지 않고 이미 성립하는 값 조합을 기록하는 것이라 위 BREAKING 목록과 분리한다. 상세는 INT-010·INTSPEC-004. 이 예외의 근거 결정은 ADR-023 이 소유한다.

## 인증 정책 (사용자 확정 2026-07-27)
JWT 는 관제서버가 발급한 것을 사용하며 저작도구는 발급하지 않는다. 관제 ADR-027 의 '저작도구 발급 JWT' 표현은 관제 측 부정확 — 협의 시 정정 요청 대상.

## 협의중 — 회신 대기 4건 (§6, 등록 보류)
- B-1: 관제 워커의 실제 픽업 방식(폴더 통짜 vs DB 파일별 경로) — 루트 경로(`{원본영상디렉터리}/{rawSn}/`)는 확정, 세부 프로토콜만 미회신.
- B-2/B-3: 관제 정본 INT-017(NAS 공유마운트)/INT-018(LS_DATA_* JDBC 직접조회) 이 현재도 유효한지 미회신. 우리 LS_DATA_* 는 실제 15개 테이블(관제 ADR-067 "9테이블" 서술과 불일치), 비식별 경로는 LS_DEIDENT_PROC_LOG(LS_DATA_* 밖).
- B-4: INT-007 auth_type — x-access-token 발급 주체 미확정.

## environments

_(empty)_

## implementation

### status

in_progress

### modules

_(empty)_

### records

_(empty)_

### progress

80

### subtasks

_(empty)_

### module_paths

_(empty)_

## compliance_tags

_(empty)_

## used_by_domains

- DOMAIN-016

## data_sensitivity

internal

## shared_with_projects

_(empty)_
