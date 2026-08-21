---
logicraft_item: DFEAT-045
type: domain_feature
version: 12
domain: DOMAIN-014
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-21T00:40:15.011Z
status: CHANGED
prev_version: 10
content_hash: fa896d27f98660b5ece56b75631883f9ccd5530f53786241bcdcbe3dcffa3518
stale: false
raw: ./_raw/DFEAT-045.json
links:
  belongs_to_domain: ["[[DOMAIN-014]]"]
  implements: ["[[API-068]]", "[[API-069]]", "[[API-090]]", "[[API-193]]", "[[API-194]]"]
  verifies: ["[[AC-006]]", "[[AC-055]]"]
  depicts_backward: ["[[CDIAG-012]]", "[[CMP-011]]"]
  realizes_backward: ["[[MOD-016]]", "[[MOD-050]]", "[[UC-031]]"]
  references_backward: ["[[CDIAG-012]]"]
---

# 시스템 설정 관리

## title

시스템 설정 관리

## status

designed

## consumes

_(empty)_

## priority

should

## triggers

_(empty)_

## brownfield

### notes

2026-07-30: 포털 업로드 프레임 추출 간격(portal.upload.frame-interval-sec) 설정 키 반영(경미) / 2026-08-21: 외부 시계열 위탁 전체 건너뛰기 스위치(batch.vlm.skip-by-default · batch.vlm.skip-by-default-reason) 설정 키 반영

### status

new

### decided_by

ADR-046

### change_kind

- capability-add

### diff_summary

2차 신규 — 운영 파라미터 키-값 관리에 더해 외부 연동 헬스 조회와 연동 서버 주소 변경(관리자 단기 유효창)을 담는다. 1차에는 설정 관리가 없었다.

## description

코드성 시스템 설정(예: POLYGON_SIMPLIFY_TOLERANCE, portal.upload.frame-interval-sec — 포털 업로드 영상 프레임 추출 간격 기본 5초·상한 maxFrames 2000)을 조회·변경하고 Caffeine 로컬 캐시(TTL 60s)에 반영한다. 설정 조회 실패 시 fail-safe 기본값 폴백. 연동 서버 주소도 같은 설정 축에서 관리한다 — 비식별 서버(kpst.deid.base-url)·AI 추론 서버(authoring.integration.ai-server.base-url)·외부 시계열 분석 벤더(vlm.client.url)·관제 통지 수신처(authoring.control-notify.url)가 대상이고 데이터베이스 접속 정보는 대상이 아니다. 설정 키는 애플리케이션 속성명을 그대로 써서 매핑표라는 두 번째 진실원을 만들지 않는다. 설정에 값이 있으면 그 값을, 없으면 배포 기본값을 쓰며 행을 시드하지 않아 행이 없는 것이 정상 상태다. 반영은 재기동 없이 다음 호출 시점에 저장값을 다시 읽어 이뤄지고, 값을 바꾼 노드에서는 즉시 다른 노드에서는 캐시 수명만큼 늦다. 이 키들만 검수자 권한 위에 관리자 단기 유효창을 하나 더 요구하며, 그 유효창은 주소 설정 변경에만 효력이 있고 역할을 승격시키지 않는다. 게이트는 저장을 수행하는 지점 한 곳에 두어 진입점이 늘어도 우회되지 않게 한다. 저장 값에 남는 검증은 빈 값 거부·길이 상한·주소 형식·스킴(http 또는 https)·호스트 존재·자격증명을 담은 표기 거부이며 주소 대역으로는 막지 않는다 — 망 통제는 인프라 계층이 담당하고, 거부 사유에 입력 원문이나 해석 결과를 싣지 않는다. 비식별 축에만 관문이 하나 더 있어 운영 배포에서는 알려진 목·시뮬레이터 호스트명을 저장할 수 없는데 그 판정 축도 호스트명이지 주소 대역이 아니다. 변경 사실은 새 테이블 없이 LS_SYSTEM_CONFIG 의 MDFR_ID·MDFCN_DT 와 로그로 남기며 패스워드·토큰은 어떤 로그에도 남기지 않는다. 기동 시점의 배포 기본값을 판정하는 축은 그대로 남아 있으며 이 설정 축과 별개다. 이 도메인은 5초 주기 폴링으로 비식별 서버·AI 추론 서버·데이터베이스 상태를 읽기 전용으로 보여주는 헬스 모니터링과, 되돌릴 수 없는 시스템 초기화·배치 큐 초기화·캐시 삭제를 확인 절차를 거친 뒤에만 실행하는 위험 액션도 함께 관리한다. 관리자 단기 유효창의 유효기간은 설정으로 조정할 수 있으나 기본 10분·상한 30분은 서버가 강제한다 — 상한을 넘긴 값은 상한으로, 0 이하는 기본값으로 되돌려 안전한 방향으로만 어긋나게 한다. 이 값 하나로 기동을 막지는 않는다. 잘못된 설정 하나가 무관한 기능까지 함께 멈추는 편이 더 나쁘기 때문이다. 라벨링 화면의 AI 보조 작업이 한 실행을 얼마나 기다릴지도 같은 설정 축에서 관리한다 — 작업 종류마다 고정분과 프레임당 가산분을 두고(label.ai.detect.max-wait-sec · label.ai.segment.max-wait-sec · label.ai.track.max-wait-base-sec · label.ai.track.max-wait-per-frame-sec · label.ai.auto-track.max-wait-base-sec · label.ai.auto-track.max-wait-per-frame-sec), 계산값의 절대 상한(label.ai.max-wait-ceiling-sec)을 함께 둔다. 이 값들은 검수자 전용 관리 경로로 바꾸고 라벨링 화면은 읽기 전용 조회로 받아 간다. 등록 범위의 하한은 그 작업에서 서버가 정당하게 쓸 수 있는 최악 소요 이상이어야 한다 — 하한이 그보다 낮으면 마지막 시도에서 성공한 추론까지 실패로 보이므로, 호출당 상한이나 재시도 횟수·대기를 바꿀 때 이 하한도 함께 올린다. 하한 미만이거나 상한을 넘긴 값은 다른 범위 검증과 같은 통로에서 거부되어 저장되지 않고 기존 값이 유지된다. 그럼에도 저장된 값이 하한 아래로 남아 있으면 읽는 쪽이 하한으로 끌어올려 쓰고 그 사실을 기록한다 — 범위 갱신을 놓치더라도 설정 때문에 정상 동작이 실패로 바뀌지는 않게, 위 유효창과 같이 안전한 방향으로만 어긋나게 한다. 이 값들로도 기동을 막지 않는다. 외부 시계열 위탁을 영상 전체에 대해 건너뛸지도 같은 설정 축에서 관리한다 — batch.vlm.skip-by-default 가 켜져 있으면 배치가 위탁 단계에 진입하기 직전 건너뜀 표식을 자동으로 남기고 외부 호출을 하지 않으며, batch.vlm.skip-by-default-reason 이 담은 문구가 그 표식의 사유 본문으로 함께 기록된다. 사유가 비어 있으면 스위치를 켤 수 없다 — 건너뛴 이유가 남지 않으면 그 영상의 시계열이 왜 비어 있는지 나중에 되짚을 수 없기 때문이며, 사람이 누르는 건너뛰기에 사유를 필수로 두는 것과 같은 축이다. 자동 표식은 사람이 이미 남긴 표식을 덮지 않는다. 스위치가 켜져 있는 동안 들어오는 영상은 전건이 시계열 없이 확정될 수 있으므로 켜짐 상태는 운영 화면에 상시 드러나야 하며, 끄는 것을 잊으면 벤더 연동이 끝난 뒤에도 계속 건너뛴다.

## invokes_apis

_(empty)_

## implementation

### status

planned

### modules

_(empty)_

### records

_(empty)_

### progress

0

### subtasks

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_SYSTEM_CONFIG

## related_acceptances

- AC-006
- AC-055

## implemented_by_endpoints

- API-068
- API-069
- API-090
- API-193
- API-194

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
