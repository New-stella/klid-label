---
logicraft_item: EXTSYS-006
type: external_system
version: 4
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:56.458Z
status: NEW
prev_version: null
content_hash: d6c1b3065cf51b18bc254da110d4b1e6de86fd3afdc30668e8f6cab308dc086d
stale: false
raw: ./_raw/EXTSYS-006.json
links:
  depends_on_backward: ["[[DOMAIN-013]]"]
  provided_by_backward: ["[[INT-009]]"]
---

# 포털 DB (외부 채널 데이터 소스)

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

포털(외부 채널) 데이터 소스 DB. 관제서버 → 데이터마트 → 포털 DB 적재는 관제서버 책임이다. 저작도구는 이 포털 DB 를 읽지 않는다 — 듀얼 데이터소스는 반대 방향으로 쓰인다: 저작도구가 자신의 검수 승인(APPROVED) 영상 메타를 outbox 단방향 복제로 이 DB 에 써 넣는 연동이다(INT-009).

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

## environments

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

## compliance_tags

_(empty)_

## used_by_domains

- DOMAIN-013

## data_sensitivity

internal

## shared_with_projects

_(empty)_
