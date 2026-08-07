# 구현 상태 원장 — ITEM 본문에서 걷어낸 것

> **왜 이 파일이 있나**: LogiCraft ITEM 본문은 설계(사양)만 담는다(P0). "지금 코드가 사양을 따르는가"는
> 이 저장소를 아는 사람에게만 참인 문장이라 ITEM 이 아니라 여기 남긴다.
> 걷어냈다고 사실이 사라지는 게 아니므로, **지운 문장은 반드시 여기 원문으로 옮긴다.**
>
> 작성 시점: 2026-08-07 (UI 카탈로그 정합 라운드)

---

## DS-001 (KRDS Public) — `known_gaps[9]` 에서 제거

원문:

> 이 ITEM 은 KRDS 정본으로 정합됐으나 테스트베드 프론트엔드(upload-ui/frontend)는 아직 구 팔레트(#0F4C97 계열)를 쓴다 — 즉 현재 코드가 이 ITEM 을 따르지 않는 상태다. 납품 프론트엔드(klid-label-frontend)는 이미 정본에 정합돼 있다. 테스트베드 전환은 별건이며, 그때까지 두 프론트엔드의 색상이 서로 다르다.

- **여전히 유효한 사실이다.** `DS-001` 은 KRDS 정본 팔레트(주조색 `#256EF4`)를 담고 있고,
  `upload-ui/frontend` 는 구 팔레트(`#0F4C97` 계열)를 쓴다. `klid-label-frontend` 는 정본에 정합돼 있다.
- 후속: 테스트베드 팔레트 전환 (별건 — `RESUME.md` §5 "테스트베드 팔레트" 항목과 동일 건)
- 제거 사유: 레포명 + 구현 상태. `known_gaps` 는 **설계 격차**를 적는 자리이지 코드 추적 자리가 아니다.

---

## CONST-001 (COCO-17 키포인트 스켈레톤) — `description` 에서 제거

원문:

> BE KeypointSkeleton.java(common/util) 실측 — FE COCO_SKELETON 상수와 값 일치 필요.

- 사양으로 남긴 것: "백엔드 스켈레톤 상수와 화면 스켈레톤 상수는 값이 같아야 한다"(계약).
- 제거 사유: 코드 파일 경로 + 심볼명.

---

## CONST-002 (COCO-80 검출 클래스) — `description` 에서 제거

원문:

> **정본**: 백엔드 `kr.co.cudo.authoring.label.domain.CocoClasses.LABELS`(80종, index 0~79).
> **계약 상대**: ai-server `app/models/detector_backend.py` 의 `COCO_ID2LABEL`(id 0~79)과 **동일 순서·동일 문자열**. 2026-07-24 실측 확인 결과 두 소스 80종 완전 일치(드리프트 없음). `CocoClassesDriftTest` 가 개수·순서를 계약 검증한다.
> - AI 탐지 온라인/배치 경로가 매핑된 라벨의 COCO 클래스만 검출 대상으로 재구성(AutolabelOnlineService.resolveDetectClasses).

- 여전히 유효: 2026-07-24 실측에서 백엔드 목록과 추론 서버 목록이 80종 완전 일치(드리프트 없음)했고,
  드리프트 검증 테스트가 개수·순서를 계약으로 고정하고 있다.
- 사양으로 남긴 것: 검출 클래스 목록은 저작도구와 추론 서버가 **순서·문자열까지 동일**해야 하는 계약이며,
  index 가 곧 COCO class_id 라는 점.
- 제거 사유: 심볼명 · 파일 경로 · 감사 이력(실측 일자).

---

## NAV-001 (저작도구 내부 메뉴) — `description` 에서 제거

원문:

> 실제 src/components/layout/Lnb.tsx 의 그룹(대시보드/영상/작업/데이터/통계/게시판/관리)과 src/router/index.tsx 라우트·가드를 반영.
> v2: 공지(게시판) 그룹 추가(SCREEN-030/031), 비식별 신고 관리(SCREEN-032) 추가.

- 그룹 구성 자체(대시보드/영상/작업/데이터/통계/게시판/관리)는 **사양이므로 본문에 남긴다.**
  제거한 것은 그 근거가 된 파일 경로와, `change_summary` 가 담당해야 할 리비전 변경 이력이다.

---

## NAV-002 (포털 메뉴) — `description` 에서 제거

원문:

> src/router/index.tsx 의 /portal 라우트(PortalLayout, LNB 없음)와 src/components/layout/PortalLayout.tsx 반영.

- 사양으로 남긴 것: 포털 채널은 LNB 를 두지 않는다는 구조 결정.
- 제거 사유: 파일 경로.

---

## UI-097 — description 에서 제거

원문:

> 검수자↔작업자 이슈 소통 채널 UI — 라벨링 화면(/label/:id) 우측 패널 '이슈' 탭과 검수 화면에 통합되는 스레드 패널. 반려(REJECTION) 이력 + 문의(INQUIRY) 스레드 통합 표시, 댓글 작성, REVIEWER 해소 버튼, 미해소 문의 카운트 배지. videoId 부재 시 탭 비노출(프레임 PK 폴백 금지 — 미커밋 수정분). 위치: frontend/src/features/review/components/IssueThreadPanel.tsx (+ useIssueThreads 훅). R1 외 추가(커밋 fb9debb).

- 제거 사유: 코드 파일 경로(`frontend/src/features/review/components/IssueThreadPanel.tsx`) + 훅 이름 + 커밋 해시(`fb9debb`) + 구현 상태 표현("미커밋 수정분")은 전부 P0 위반(저장소를 아는 사람에게만 참인 문장).
- **정정도 함께 필요했다** — "videoId 부재 시 탭 비노출"은 실제 코드(`AnnotationPanel.tsx`, `ReviewSidePanel.tsx`)와 어긋난다. 실제로는 탭 자체는 항상 노출되고, videoId 가 없을 때는 패널 자리에 "영상 정보가 없어 이슈 스레드를 사용할 수 없습니다" 안내만 표시된다(탭 비노출 아님). description 은 이 실제 동작으로 정정했다.
- "R1 외 추가"라는 스코프 사실 자체는 `R1-외-추가` 태그로 남아 있어 유실되지 않는다(태그는 P0 대상이 아님).

---

## 미처리 (사용자 판단 대기)

| ITEM | 내용 |
|---|---|
| `TEST-001` | `notes` 의 "D4 IF번호 미할당" — 코드가 아니라 **연동명세서에 IF 번호가 없다는 설계 사실**이라 지난 라운드에서 남겼다. 엄격히 보면 진행 상태다. `RESUME.md` §5 와 동일 건 |
