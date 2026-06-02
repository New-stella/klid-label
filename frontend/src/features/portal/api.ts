// 포털 채널 API — BE: /api/v1/portal/*
// 보안: axios가 자동 URL 인코딩. IDOR 방어는 BE 책임 (PORTAL_USER 본인 데이터만 노출).
//
// BE 잔존 엔드포인트 (kr.co.cudo.authoring.portal.controller.PortalLabelController):
//   GET  /v1/portal/datamart/labels   → 데이터마트 라벨 Load
//   GET  /v1/portal/user-labels       → 사용자별 라벨 조회
//
//   POST /v1/portal/user-labels (사용자별 라벨 저장)는 BE DTO(PortalUserLabelRequest:
//   sourceRawSn/sourceSrcSn/lblTypeCd/label/points 단건)에 맞춰 데이터마트 기능과 함께
//   후속에서 구현 예정 — 현재 FE 함수 없음.
//
// ADR-013: 포털은 데이터마트 영상 선택·간편 라벨링 전용. 오토라벨링·업로드(TUS) 미제공.
// echo 엔드포인트(GET /portal/uploads · POST /portal/autolabel · POST /portal/labels)는
// BE 에서 삭제됨 — 호출하지 않는다.

export {};
