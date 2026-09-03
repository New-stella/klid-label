---
logicraft_item: DOMAIN-012
type: domain
version: 9
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-03T07:39:21.619Z
status: CHANGED
prev_version: 8
content_hash: 382ccd9979303cabaebf89ca9b9aabd405ab32dfc9c9069030ab40d40b64c90f
stale: false
raw: ./_raw/DOMAIN-012.json
links:
  based_on: ["[[ADR-006]]"]
  applies_to_backward: ["[[NFR-008]]", "[[NFR-012]]"]
  belongs_to_domain_backward: ["[[AC-1063]]", "[[AC-1064]]", "[[AC-1065]]", "[[AC-1066]]", "[[AC-1067]]", "[[ADR-006]]", "[[ADR-022]]", "[[ADR-024]]", "[[ADR-025]]", "[[ADR-027]]", "[[API-094]]", "[[API-109]]", "[[API-112]]", "[[API-183]]", "[[API-184]]", "[[API-202]]", "[[CDIAG-003]]", "[[CMP-003]]", "[[DFEAT-041]]", "[[DFEAT-042]]", "[[DFEAT-048]]", "[[ERD-017]]", "[[EVT-007]]", "[[EVT-008]]", "[[INT-004]]", "[[INT-005]]", "[[REQ-027]]", "[[SCREEN-032]]", "[[UC-011]]", "[[UC-013]]", "[[UC-016]]"]
  derived_domain_backward: ["[[AC-1063]]", "[[AC-1064]]", "[[AC-1065]]", "[[AC-1066]]", "[[AC-1067]]"]
  implements_in_backward: ["[[MOD-005]]", "[[MOD-049]]"]
  references_backward: ["[[ADR-006]]", "[[TEST-001]]", "[[TEST-008]]"]
---

# 비식별화

## name

비식별화

## brownfield

### status

modified

### decided_by

ADR-006

### change_kind

- component-replace

### diff_summary

1차 라벨링 캔버스 수동 블러(DFEAT-013 deprecated) → 2차 외부 영상비식별 솔루션 연동으로 대체. [추정 — 1차 도메인 경계 확인 필요]

## description

영상의 개인정보 비식별 처리를 외부 비식별 솔루션(KPST) 위탁으로 수행하는 도메인.

[★파이프라인 선두 — 게이팅 폐지] 적재된 모든 영상이 (ANONY 포함) 무조건 비식별 대상이다. 구 서술 'PRVC_TYPE_CD 가 PRVC/PSDO 일 때만 호출'은 폐기됐다. 적재 직후 VideoIngested(EVT-005) 가 커밋 이후(AFTER_COMMIT) 비동기로 비식별 단계를 자동 트리거하며, 성공 시 LsDataRaw.dataSttsCd 가 MARKING_READY 로 전이해 마킹 진입이 열린다. 마킹·라벨링의 대상은 비식별 영상이고 원본은 별도 경로에 보존된다.

[★결과 수신 = 폴링(콜백 아님)] 구 서술 '외부 콜백(webhook)으로 결과 수신'은 폐기됐다. 위탁 후 주기 폴링(진행조회)으로 완료를 감지하고 산출물 경로를 기록한다. 산출 파일명은 우리가 정하지 않는다 — mock 은 deidentified.mp4, KPST 실연동은 {원본stem}-mask{ext} 라 영상마다 다르므로 반드시 LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM 값을 읽고 문자열로 조합·추측하지 않는다.

[★제출은 논블로킹] KPST 비식별 제출은 WAITING 원장 행을 선커밋하고 createProject 를 비동기 디스패치한다. 스텝이 확정적으로 말하는 사실은 '제출을 개시했다' 뿐이며 수락(ACK)은 완료 핸들러가 비동기 기록한다.

[★회수 경로는 축마다 다르다] [폐기] 구 서술 '아무 신호도 없으면 미결 스위퍼가 회수하고, 2노드 Active-Active 에서 같은 후보를 두 번 재위탁하지 않도록 조건부 UPDATE 로 원자 클레임한다' — 폐기한다. 비식별 축에는 전용 미결 스위퍼가 없고 그 회수는 재위탁도 아니다. 회수 경로는 축마다 다르다. 시계열은 전용 미결 스위퍼가 집어 재위탁하고, 비식별은 전용 스위퍼가 없이 폴링 잡이 ACK 대기 유예 만료로 회수해 실패 코드로 원장을 마감한다(재위탁하지 않는다). 비식별 쪽 회수는 '우리가 ACK 를 관측하지 못했다'는 뜻이지 '외부에서 실패했다'가 아니므로 영상 비식별 상태를 실패로 내리지 않는다 — 외부에는 실제로 작업이 생성돼 있을 수 있다. 제출 신호가 노드와 함께 사라지면 실패 행조차 남지 않아 재시도 큐·실패 회수기가 집지 못하므로, 각 축의 이 회수 경로가 유일하다. 2노드 Active-Active 에서 같은 후보를 두 노드가 동시에 집지 않도록 조건부 UPDATE 로 원자 클레임한다.

[실패·재처리] 실패 시 DE_IDNTF_YN='F' 마킹 + 원본 절대 삭제 금지. 자동 재비식별 큐는 폐기됐고 외부 비식별 프로그램에서 수동 재비식별 후 resolve 로 해소한다.

[★비식별 누락 신고] 작업자가 개인정보 노출을 발견하면 신고 → 작업락 + DE_IDNTF_YN='F'. 라벨도 개인정보 판정도 삭제하지 않고 보존한다. 신고 구간에는 조회뿐 아니라 라벨 저장·버전 diff·롤백·개인정보 메타 PUT·이벤트 어노테이션 저장/승인/반려·검수 승인까지 412 로 차단되며(영상 스트리밍만 404), 한 번이라도 검수 승인된 이력이 있는 영상은 신규 신고 접수 자체를 막는다(판정 축은 지금 상태가 아니라 승인 이력). 게이트 판정 범위는 자기 rawSn 행 하나이며 조상/자손 전파는 4라운드 시도 후 철회됐다 — 다시 시도하지 말 것. 파생영상은 신고 체계 바깥이라 접수하지 않고(412) 원본 신고의 영향도 받지 않는다. 검수 완료·통지 건에 대한 관제 접근은 신고 구간에도 차단하지 않는다.

[잔존 책임] 저작도구는 위탁·결과 저장(DFEAT-041)·대상 범위(DFEAT-042)·누락 신고(DFEAT-048)를 보유한다. 비식별 결과의 상세(deep) 검토는 외부 솔루션 프로그램이 수행하며, 저작도구는 그 축을 경량 상태·이력 확인(FEAT-006)으로만 보유한다 — 폐기된 것은 FEAT-006 자체가 아니라 그 상세 검토 UC·화면·API(UC-012·SCREEN-016/017·API-051/052)다.

## upstream_of

_(empty)_

## context_kind

supporting

## collaborators

_(empty)_

## attached_files

_(empty)_

## integrates_with

_(empty)_

## uses_integrations

_(empty)_

## ubiquitous_language

### [1]

- **term**: DE_IDNTF_YN
- **meaning**: 비식별 여부. Y=완료, N=미수행, F=실패 또는 누락 신고 중. 'F' 는 의미가 둘이라 플래그만으로 구분되지 않고 산출물 실재 검증이 fail-closed 로 뒤를 받친다

### [2]

- **term**: DE_IDNTF_SRC_FILE_PATH_NM
- **meaning**: 비식별 프레임 경로 (원본 SRC_FILE_PATH_NM 과 별도). 신규 추출은 frames/raw|deid/{rawSn} 로 분기 저장돼 두 경로가 항상 다르다

### [3]

- **term**: DE_IDNTF_FILE_PATH_NM
- **meaning**: 비식별 영상 파일 경로. 외부 솔루션이 파일명을 정하므로 이 값을 읽어야 하며 조합·추측 금지

### [4]

- **term**: 비식별 누락 신고
- **meaning**: 마킹·라벨링 중 개인정보 노출을 발견해 재비식별을 요청하는 행위. 작업락과 'F' 마킹을 동반한다. 라벨과 개인정보 판정은 지우지 않고 보존한다

### [5]

- **term**: 신고 게이트
- **meaning**: 자기 rawSn 행의 DE_IDNTF_YN='F' 단일 컬럼만 보고 판정하는 프리컨디션. 인가 검사 이후 평가되며 역할 무관이다

### [6]

- **term**: MARKING_READY
- **meaning**: 선두 비식별이 성공해 마킹 진입이 허용된 상태
