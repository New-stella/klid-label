---
logicraft_item: EXTSYS-006
type: external_system
version: 14
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-07T15:15:54.466Z
status: CHANGED
prev_version: 12
content_hash: 6eba716170ec705522771efeeda9f3e32275abd3aae66e66d86dc414511248b3
stale: false
raw: ./_raw/EXTSYS-006.json
links:
  depends_on_backward: ["[[DOMAIN-013]]"]
  provided_by_backward: ["[[INT-009]]", "[[INT-013]]", "[[INT-014]]"]
---

# 포털 (외부 채널)

## kind

data

## status

active

## vendor

포털(외부 채널)

## brownfield

### status

new

### change_kind

- component-add

### diff_summary

고도화 — 포털 듀얼 데이터소스 Load 연동

## owner_team

타 시스템

## criticality

medium

## description

포털(외부 채널). 저작도구는 이 시스템과 **프론트엔드 런타임 임베딩 하나**로 이어진다 — 포털이 Host, 저작도구가 Remote 로 같은 브라우저 문서에서 함께 실행된다. 두 시스템은 **서로의 DB 에 접근하지 않으며** 데이터 교환은 **API 로 설계한다**(양방향 약속 — 포털도 저작도구 DB 를 참조하지 않는다).

## ★ [폐기] 메타 단방향 복제 — 철거 완료 (2026-08-31 확정, 구속)

연동 표면이 원래 둘이었고 그중 「검수 승인 영상 메타를 포털 DB 로 단방향 복제하는 데이터 연동」은 **폐기·철거됐다.** **되살리지 말 것.**

- **근거는 채널별 별도 배포다** — 저작도구는 관제 채널·포털 채널에 **각각 별도 배포**되고 각 배포본은 **자기 별도 DB** 에만 연결한다. 한 배포본이 두 채널의 DB 를 함께 무는 구조가 아니므로 이 복제가 설 자리 자체가 없다. **두 배포본끼리도 직접 이어지지 않으며**, 승인 자산이 포털로 흐르는 경로는 **관제 중계**다.
- ✅ 듀얼 데이터소스·복제 워커·발신함·주기 잡·메트릭이 **코드에서 제거**됐고 반입 산출물의 포털 DB 단계도 사라졌다. 포털 데이터소스로 저장되는 표는 **0종**이다.
- 대체 창구가 필요한지는 **미확정**이다 — 그 복제가 **쓰기 전용**이라 실 소비처가 양측 어디에서도 확인되지 않는다. 철거 이전의 동작 상세와 대체 판단은 **INT-009 가 소유한다**(여기에 복제하지 않는다).

## 프론트엔드 런타임 임베딩

협의 항목이 전부 해소된 **확정 연동**이며 미결로 남은 축은 없다. 저작도구는 포털 셸 안으로 들어가고(포털 채널의 머리 영역과 좌측 주 메뉴를 렌더하지 않되 구성은 남긴다), 자기 스타일을 Host 전역 스타일과 격리할 책임을 지며, 산출물은 포털 주색으로 뜬다. 스택 세대도 생태계 주 버전에 맞춰 정렬돼 공유 라이브러리를 따로 협상하지 않는다.

**토큰 인계 수단은 채널마다 갈린다** — 포털 채널은 Host 가 주입한 인계 창구에서 토큰을 얻어 전용 요청 헤더로 싣고 access token 을 **Host 메모리에만** 둔다(브라우저 저장소 미사용). 내부 채널은 같은 출처 브라우저 저장소를 쓰는 기존 방식을 유지한다. 그 인계 결정은 ADR-012 가, 나머지 축의 확정과 진입점·마운트·서빙 규약은 INT-013 이 소유한다. 대응 화면도 INT-013.

## ★ 명명과 실제 데이터소스 불일치 — 가장 흔한 오해 지점

이름에 "portal" 이 들어간 엔티티(포털 업로드·라벨 기능 전반)는 **전부 저작도구 DB 에 저장된다.** 즉 패키지·클래스명의 "portal" 은 *채널 구분*을 뜻할 뿐 데이터소스와 무관하다. 신규 개발 시 혼동 주의.

## 정책

포털 사용자가 저장한 라벨·작업 데이터는 **원본·데이터마트에 반영되지 않는 단방향**이며 사용자별 작업 데이터로만 쌓인다.

## environments

_(empty)_

## attached_files

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

### module_paths

_(empty)_

## compliance_tags

_(empty)_

## used_by_domains

- DOMAIN-013

## data_sensitivity

internal

## shared_with_projects

- cdfb5f30-c4fe-4940-bb78-4d32a598ae1f
