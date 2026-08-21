---
logicraft_item: DFEAT-053
type: domain_feature
version: 6
domain: DOMAIN-013
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-21T00:02:56.124Z
status: CHANGED
prev_version: 5
content_hash: c00f4faa2f68ea03730d8fe14169e0bfa494de81324d6b457c9a99d46f25b6da
stale: true
raw: ./_raw/DFEAT-053.json
links:
  belongs_to_domain: ["[[DOMAIN-013]]"]
  consumes: ["[[EVT-012]]"]
  implements: ["[[API-139]]", "[[API-140]]", "[[API-142]]", "[[API-147]]", "[[API-149]]", "[[API-151]]", "[[API-154]]", "[[API-155]]", "[[API-157]]", "[[API-159]]", "[[API-161]]", "[[API-163]]", "[[API-166]]", "[[API-169]]", "[[API-171]]"]
  triggers: ["[[EVT-012]]"]
  depicts_backward: ["[[CDIAG-011]]", "[[CMP-009]]"]
  realizes_backward: ["[[UC-027]]"]
  references_backward: ["[[CDIAG-011]]"]
---

# 포털 자산 업로드·수동 라벨링

## title

포털 자산 업로드·수동 라벨링

## status

implemented

## consumes

- EVT-012

## priority

must

## triggers

- EVT-012

## brownfield

### status

new

### decided_by

ADR-013

### change_kind

- capability-add

### diff_summary

2차 외부 채널(포털) 신규 — 본인 자산 업로드·수동 라벨링(ADR-013 예외, 2026-07-17)

## description

포털 회원(PORTAL_USER)이 본인 이미지(jpg/jpeg/png, 개당 최대 20MB, 요청당 최대 50장, multipart)·영상(mp4/mov/avi, 최대 5GB, TUS 1.0 프로토콜 재개 가능 청크 업로드)을 직접 업로드해 BBOX/POLYGON 수동 라벨링을 수행하고 본인 데이터(JSON export 및 원본 파일)를 다운로드하는 기능. ADR-013(2026-07-17) 예외로 신설됐다. 업로드된 영상은 고정 간격(portal.upload.frame-interval-sec, 기본 5초)으로 프레임을 추출하며 영상 1건당 최대 추출 프레임 수는 portal.upload.maxFrames(기본 2000)로 제한된다(초과 시 균등 샘플링). 자산 처리 상태는 UPLOADED → PROCESSING → READY|FAILED 순으로 전이하며 READY 상태에서만 라벨링·다운로드가 가능하다(이미지는 업로드 즉시 READY). UPLOADED·PROCESSING 상태로 방치 판정 시간(portal.upload.stuck-timeout-minutes, 기본 30분) 동안 최종 변경 일시가 갱신되지 않으면 FAILED 로 전이하며 그 사유를 남긴다 — 영구 로딩으로 남지 않게 하기 위함이고, 그 전이가 보존기간 만료 자동 삭제(DFEAT-055)의 진입점이 된다. ★ 오토라벨링(YOLO/SAM2)·VLM 시계열 연동·검수(REVIEWER 배정·승인/반려 워크플로)·버전관리(LS_LABEL_VERSION 스냅샷/롤백)는 전혀 제공하지 않으며 BBOX/POLYGON 수동 라벨링만 가능하다(SAM 분할·키포인트·오토라벨 도구는 화면에서 노출되지 않음). 내부 배치 파이프라인(비식별화→마킹→VLM→프레임추출→오토라벨링→SAM2→트랙보간)·데이터마트 적재용 View(V_COMPLETED_*)와는 완전히 분리된 독립 경로로 운영되며, 기존 데이터마트 영상 선택 후 라벨 저장 경로(LS_PORTAL_USER_LABEL)와도 별개의 신규 스키마(LS_PORTAL_ULD/LS_PORTAL_ULD_FRME/LS_PORTAL_ULD_LBL/LS_PORTAL_TUS_ULD)를 사용한다. 저장된 라벨은 내부 파이프라인·데이터마트에 정합·반영되지 않는다(완전 단방향 독립).

## invokes_apis

_(empty)_

## implementation

### status

implemented

### modules

- MOD-017

### records

_(empty)_

### progress

100

### subtasks

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_PORTAL_ULD
- LS_PORTAL_ULD_FRME
- LS_PORTAL_ULD_LBL
- LS_PORTAL_TUS_ULD

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-139
- API-140
- API-142
- API-147
- API-149
- API-151
- API-154
- API-155
- API-157
- API-159
- API-161
- API-163
- API-166
- API-169
- API-171

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
