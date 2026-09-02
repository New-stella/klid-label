---
logicraft_item: EXTSYS-006
type: external_system
version: 12
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-02T10:52:38.908Z
status: CHANGED
prev_version: 11
content_hash: c0373c66f3095422c41e0234024ca9dae0d98a14219640b880058ca4be7976ab
stale: false
raw: ./_raw/EXTSYS-006.json
links:
  depends_on_backward: ["[[DOMAIN-013]]"]
  provided_by_backward: ["[[INT-009]]", "[[INT-013]]"]
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

포털팀

## criticality

medium

## description

> ## ★ [폐기] 연동 표면 2종 중 「메타 단방향 복제」는 폐기됐다 (2026-08-31 확정, 구속)
>
> 저작도구와 포털은 **서로의 DB 에 접근하지 않으며** 데이터 교환은 **API 로 설계한다**(양방향 약속 — 포털도 저작도구 DB 를 참조하지 않는다). 따라서 이 시스템과 이어지는 갈래는 **프론트엔드 런타임 임베딩(INT-013) 하나만 남는다.**
>
> - **근거는 채널별 별도 배포다**(ADR-012) — 저작도구는 관제 채널·포털 채널에 **각각 별도 배포**되고 각 배포본은 **자기 별도 PostgreSQL DB** 에만 연결한다. 한 배포본이 두 채널의 DB 를 함께 무는 구조가 아니므로 이 복제가 설 자리 자체가 없다. **두 배포본끼리도 직접 이어지지 않으며**, 승인 자산이 포털로 흐르는 경로는 **관제 중계**다(상세 ADR-012).
> - ✅ **철거 완료 (2026-08-31)** — 듀얼 데이터소스·복제 워커·발신함·주기 잡·메트릭이 **코드에서 제거**됐고 반입 산출물의 포털 DB 단계도 사라졌다. **되살리지 말 것.** 아래 본문의 복제 서술은 **철거 이전의 동작 기록**이며, 이 시스템과 이어지는 갈래는 이제 **임베딩 하나뿐**이다. 포털 데이터소스로 저장되는 테이블은 **0종**이 됐다.
> - 대체 API 가 필요한지는 **미확정**이다 — 복제가 **쓰기 전용**이라 실 소비처가 양측 어디에서도 확인되지 않는다. 상세 판단은 INT-009 가 소유한다.
> - ⚠ 아래 「명명과 실제 데이터소스 불일치」 절은 **그대로 유효**하다 — 이름에 "portal" 이 든 엔티티가 전부 저작도구 DB 에 저장된다는 사실은 복제 철거와 무관하며, 철거 후에는 **포털 데이터소스로 저장되는 테이블이 0종**이 된다.

포털(외부 채널). 저작도구는 이 시스템과 두 갈래로 이어진다 — 검수 승인 영상 메타를 포털 DB 로 단방향 복제하는 데이터 연동, 그리고 포털 화면 안에서 저작도구 프론트엔드가 런타임으로 실려 동작하는 임베딩이다.

## 연동 표면 2종
1. **[폐기·철거 완료] 메타 단방향 복제**(INT-009) — 관제서버 → 데이터마트 → 포털 DB 적재는 관제서버 책임이다. 저작도구는 이 포털 DB 를 읽지 않는다 — 듀얼 데이터소스는 반대 방향으로 쓰인다: 저작도구가 자신의 검수 승인(APPROVED) 영상 메타를 outbox 단방향 복제로 이 DB 에 써 넣는 연동이다(INT-009). 상세는 아래 절 전체.
2. **프론트엔드 런타임 임베딩**(INT-013) — 포털이 Host, 저작도구가 Remote 로 같은 브라우저 문서에서 함께 실행된다. 포털 회신을 저작도구가 수용해 **협의 항목이 전부 해소된 확정 연동**이며 미결로 남은 축은 없다. 저작도구는 포털 셸 안으로 들어가고(포털 채널의 머리 영역과 좌측 주 메뉴를 렌더하지 않되 구성은 남긴다), 자기 스타일을 Host 전역 스타일과 격리할 책임을 지며, 산출물은 포털 주색으로 뜬다. 스택 세대도 생태계 주 버전에 맞춰 정렬돼 공유 라이브러리를 따로 협상하지 않는다. 토큰 인계 수단은 채널마다 갈린다 — 포털 채널은 Host 가 주입한 인계 창구에서 토큰을 얻어 전용 요청 헤더로 싣고 access token 을 Host 메모리에만 두며(브라우저 저장소 미사용), 내부 채널은 같은 출처 브라우저 저장소를 쓰는 기존 방식을 유지한다. 그 인계 결정은 ADR-012 가, 나머지 축의 확정과 진입점·마운트·서빙 규약은 INT-013 이 소유한다. 대응 화면도 INT-013.

## ★ 명명과 실제 데이터소스 불일치 — 가장 흔한 오해 지점
포털 데이터소스로 실제 저장되는 테이블은 `LS_DATASET_VIDEO_META` **1종뿐**이다.
반면 이름에 "portal" 이 들어간 엔티티 — `LsPortalUld` · `LsPortalUldFrme` · `LsPortalUldLbl` · `LsPortalTusUld` · `LsPortalUserLabel` 등 포털 업로드·라벨 기능 전반 — 은 **전부 저작도구(control) DB 에 저장된다**. 즉 패키지·클래스명의 "portal" 은 *채널 구분*을 뜻할 뿐 데이터소스와 무관하다. 신규 개발 시 혼동 주의.

## 접근 방식 — 직접 쓰기가 아닌 outbox 단방향 복제
저작도구 DB 의 발신함(`LS_META_REPL_OUTBOX`)을 워커가 주기 폴링해 포털 DB(`LS_DATASET_VIDEO_META`)에 upsert 한다.
- 멱등: `(RAW_SN, SNPSHT_HASH)` 조합 키로 중복 upsert 를 무해화한다
- 실패 누적 시 재시도 횟수가 증가하고 상한 초과 시 **`DEAD`** dead-letter 전이
- 복제본 미프로비저닝 환경은 probe 로 graceful skip (잡 실패로 번지지 않음)

## 설정
환경변수(`PORTAL_DB_HOST`/`PORT`/`NAME`/`USERNAME`/`PASSWORD`)로 프로파일별 접속정보를 주입한다. 복제 토글 `authoring.meta-replication.enabled` 는 기본 활성이고 **local 만 비활성**.

## 정책
포털 사용자가 저장한 라벨·작업 데이터는 **원본·데이터마트에 반영되지 않는 단방향**이며 사용자별 작업 데이터로만 쌓인다. 실사용 범위는 복제 테이블 1종에 한정된다.

⚠⚠ **「graceful skip」의 범위를 오해하지 말 것 (2026-08-31 실측)** — 이 skip 은 **테이블이 없는 살아 있는 DB** 에만 적용된다. **접속 자체가 안 되는 DB(포털 DB 미존재)는 막지 못하며, 그 배포는 아예 기동하지 못한다.** `portalEntityManagerFactory` 가 **조건 없이** 만들어지는데 방언(`hibernate.dialect`)이 선언돼 있지 않아 Hibernate 가 **부트스트랩 시점에 JDBC 메타데이터를 읽으려 커넥션을 연다** — probe 도 복제 잡의 조건부 활성도 **EMF 생성보다 뒤**라 이것을 막지 못하고, 복제 토글을 꺼도 마찬가지다.
⚠ **회귀 시험이 이 경로를 구조적으로 덮지 못했다** — 미프로비저닝 픽스처는 「테이블 없는 산 DB」만 재현하고, 테스트 컨텍스트가 포털 접속 주소를 **가장 앞에 꽂아** 죽은 주소를 넣어도 조용히 무시되고 초록으로 통과했다.
**철거 전까지의 조치**: 포털 EMF 에만 방언을 명시해 부트스트랩 커넥션을 없앤다(control EMF 는 건드리지 않는다 — 전역으로 올리면 실 DB 버전 탐지가 사라져 주 경로 SQL 생성이 바뀐다). **복제 철거와 함께 이 조치도 사라진다.**

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
