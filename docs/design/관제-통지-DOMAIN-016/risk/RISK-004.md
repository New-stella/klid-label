---
logicraft_item: RISK-004
type: risk
version: 4
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:43:36.594Z
status: NEW
prev_version: null
content_hash: 5a33cfbb561085df7f347a256772a55484eae0f6e67c162a17ca574ffa6d54b2
stale: false
raw: ./_raw/RISK-004.json
links:
  based_on: ["[[ADR-007]]"]
---

# 양방향 M2M 통합 deprecated

## title

양방향 M2M 통합 deprecated

## impact

3

## status

accepted

## affects

_(empty)_

## category

organizational

## brownfield

### status

deprecated

### decided_by

ADR-007

## description

관제/포털 양방향 통합(M2M) API 및 외부 학습데이터 시스템 송수신 인프라가 deprecated 상태다. 저작도구는 채널마다 별도 배포되며 토큰 인계 수단이 채널별로 갈린다 — 관제 채널은 호스트와 동일 웹 origin 을 공유해 브라우저 스토리지로 JWT 를 인계받고, 포털 채널은 Host 가 주입한 인계 창구로 토큰을 얻어 전용 요청 헤더로 실어 보내며 access token 을 브라우저 저장소에 두지 않는다. 어느 수단이든 상위 시스템이 발급한 토큰을 인계받는 구조라 사용자 인증을 대체하므로 M2M 사용자 인증 인프라는 불필요하나, 향후 양방향 연동 재구축 시 별도 설계·비용이 필요하다.

## probability

2

## mitigation_plan

잔존 outbound 통지(TASK_COMPLETED/TASK_MODIFIED)만 인계 토큰 또는 IP 화이트리스트로 보호하여 단방향으로 운영. 상세 데이터는 관제서버가 저작도구 조회 API로 pull.

## contingency_plan

양방향 통합 재구축 요구 시 M2M 인증 인프라 별도 설계 ADR 수립
