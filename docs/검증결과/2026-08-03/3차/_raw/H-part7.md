# H클러스터 part7 — H-6(MarkingPage) · H-8(ReviewPage) · H-9(관리화면) · H-10(증강/해상도 파생화면) — 3차 검증

담당 라인범위: `docs/test-cases/H-frontend-e2e.md` 249~346행 (H-6·H-8·H-9·H-10), 총 66건.
방법: 실 소스코드 정적 대조(파일:라인 1:1 실측) + `_raw/test-baseline.md`(2026-08-04 실행, frontend 340 files/2064 tests 전량 PASS) 테스트 커버 대조 + BE DTO 실측(ReviewResponse.java) + 이전 회차(2차) 이슈 대조. 스택은 살아있음(`localhost:9400/health`=200, `localhost:18081/api/actuator/health`=UP) 확인. 코드/빌드/테스트 실행 없음(baseline 재사용).

## 판정 요약

| 절 | 케이스 수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| H-6 MarkingPage | 20 | 20 | 0 | 0 | 0 | 0 | 0 |
| H-8 ReviewPage | 21 | 20 | 1 | 0 | 0 | 0 | 0 |
| H-9 관리화면 | 11 | 11 | 0 | 0 | 0 | 0 | 0 |
| H-10 증강/해상도 | 14 | 14 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **66** | **65** | **1** | 0 | 0 | 0 | 0 |

## H-6. MarkingPage (TC-FE-110~125, TC-FE-203~205, TC-E2E-003) — 전건 PASS

`frontend/src/pages/MarkingPage.tsx`(244줄) 전문을 Read 하여 카탈로그 근거 20건 **전부 라인 단위로 정확히 일치**함을 확인(TC-FE-110:154-156, 111:158-173, 113~115:139-147, 116:136-137, 117~118:118-125, 119:116, 120:61-77, 121:81-89, 122:49-58, 123:92-97, 124:181-187, 125:221-241 — 전건 실측 라인과 카탈로그 라인 동일). `features/marking/markingFps.ts`(TC-FE-203, resolveMarkingFps 21-27+markingFrameIndex 29-31)·`VideoPlayer.tsx`(TC-FE-205, SPEED_OPTIONS 34/changeSpeed 46-49/배속버튼 125-140) 도 정확. 드리프트 0건 — 이 절은 최근 회차에 이미 정비된 상태로 판단.
- 기능 확인: isMarkingBlocked 백스톱(158-173, role=alert), Space/Del·Backspace/Enter 단축키(139-147), INPUT/TEXTAREA/**SELECT** 3종 억제(136-137), AUTO intervalFrames<1·MANUAL 0건 제출 차단(118-125), 중복 제출 방지(createMutation.isPending, 116), batchTriggered=false 시 error 토스트로 성공 위장 금지(63-75, TC-FE-204) 전부 소스 확인.
- 테스트 커버: `pages/__tests__/MarkingPage.test.tsx`(TC-E2E-003) 파일 존재 확인, `test-baseline.md` frontend 2064건 전량 PASS(2026-08-04 실행)에 포함.

## H-8. ReviewPage (TC-FE-131~147, TC-FE-206~208, TC-E2E-004) — 20 PASS / 1 FAIL

`frontend/src/pages/ReviewPage.tsx`(412줄) 전문 Read. TC-FE-131~145·147·206~208 근거 라인 전부 실측과 일치(131:229-238, 132:240-255, 133:257-266, 134:179-183, 136:306, 137:206-208, 138:155-160, 139:174, 140:65-84, 141:380, 142:126-139, 143:141-149, 144:186-192, 145:194-200, 147:355, 206:170-172+224-227+387-397, 207:165-175, 208: `ReviewMetaPanel.tsx` normalizeCot(55)+`eventAnnotation.ts` normalizeCot(26) 확인). TC-FE-135(캔버스 읽기전용, 카탈로그 294-311)만 `<main>`이 실제로는 308행에서 닫히고 309-311은 `<aside>` 시작부라 3줄 과다포함 — 기능 검증에는 무해한 경미한 오차라 별도 이슈로 올리지 않음.

### [해소 확인 실패 — H-ISSUE-81 재확인 FAIL] TC-FE-146 — 검수 화면 증강/해상도 파생 표시, 2차 이후 미해소
- **판정**: **FAIL** (2차 H-ISSUE-81 그대로 이월, 미해소)
- 근거: `backend/src/main/java/kr/co/cudo/authoring/review/dto/ReviewResponse.java` 전체 필드(id/cctvName/workerId/workerName/submittedAt/labelCount/status/videoId/dataSttsCd/version/updDt/eventName/eventTypeCd)에 `orgnlRawSn`/파생 종류 필드 없음(그대로 실측). FE `features/review/types.ts`의 `Review` 인터페이스도 동일하게 파생 필드 없음. `ReviewMetaPanel.tsx`·`ReviewHeader.tsx`에 `orgnlRawSn`/`ORGNL_RAW_SN`/`vmsClipId`/증강 키워드 매치 0건(grep 재확인).
- 이 회차에도 관련 커밋 없음(`git log`로 review/dto 변경이력 확인, 이슈 이후 review 폴더 변경은 `이슈 댓글 작성자 이름 표시`(a3dc1579)뿐 — 파생 필드 추가 없음).
- **이전 회차 대비**: H-ISSUE-81(2차, MEDIUM)과 완전 동일 사유로 재확인 FAIL. 별도 신규 이슈 번호 부여하지 않고 이월 처리(ISSUES.md 병합 단계에서 H-ISSUE-81 재확인으로 표기 권장).

## H-9. 관리 화면 /manage/* (TC-FE-148~156, TC-E2E-005~006) — 전건 PASS

파일 존재·핵심 동작 실측: `LabelMasterManagePage.tsx`(dtctTypeCd 폼 필드 112행), `YoloConfigCard`/`PrecisionConfigCard.tsx`(zod 스키마+useUpdateConfig 재사용, YOLO_CONF_THRESHOLD/POLYGON_SIMPLIFY_TOLERANCE), `PresetListPage.tsx`(라벨 1~20종 zod 제한 + labelId 기반 join 표시), `DeidentReportListPage.tsx`(OPEN/RESOLVED 상태 필터), `AssignModal.tsx`(workerId state), `HistoryDrawer.tsx`(REVIEWER 전용 주석 + `/assignments/{id}/history` 조회) 전부 카탈로그 기대대로 존재.
E2E: `reviewer-workflow.spec.ts`(TC-E2E-005, 카탈로그 14-47 → 실측 5개 테스트 시작줄 14/24/33/40/47과 정확히 일치), `video-list.spec.ts:5,15`(TC-E2E-006, 실측 라인 5·15 정확 일치).

### [카탈로그 정정 1건] TC-FE-149 — 근거 file 드리프트 재확인 + 이번 회차 직접 정정
- 2차 H-ISSUE-82(LOW, 카탈로그 정합성)에서 이미 지적된 건: 카탈로그가 `pages/manage/__tests__/UserManagePage.test.tsx`를 근거로 들지만 이 파일(70줄)은 REVIEWER 세션 고정 후 검색필터만 검증하고 역할 분기 코드가 0건(grep "WORKER\|forbidden\|Role\." 결과 REVIEWER role 세팅 라인 1건뿐, WORKER 분기 없음). 실제 역할 접근제어 검증은 `frontend/src/router/__tests__/manageGuard.test.tsx:45-71`(WORKER→FORBIDDEN 2케이스 + REVIEWER→정상 렌더 1케이스, 3케이스 전부 실측 확인)이다.
- **이번 회차 조치**: 담당 라인범위(315행) 내에서 `H-frontend-e2e.md`를 Edit로 직접 정정 — 근거를 `router/__tests__/manageGuard.test.tsx:45-71`로 교체하고 구 근거의 한계를 인라인 각주로 남김. 기능 자체는 정상(라우터 가드 + BE 403 실측 기반, 2차에서 이미 실HTTP 확인됨)이라 판정은 PASS 유지.

## H-10. 증강/해상도 파생 화면 (TC-FE-157~164, TC-FE-209~213, TC-E2E-007) — 전건 PASS

2차 H-ISSUE-105가 "H-10/H-11 근거 file:line 드리프트 8건"을 지적했으나, **이번 3차 실측 결과 H-10 범위(TC-FE-159/160/161/209/210/211) 전부 이미 카탈로그가 정확한 라인으로 갱신되어 있음을 확인**(2차 이후 다른 세션이 반영한 것으로 추정, 이번 회차 재수정 불필요):
- TC-FE-159: `JobCard.tsx:81-89`(resolutionTypes.map 렌더) — 실측 라인 81-89 정확 일치.
- TC-FE-160: `AugmentResultPanel.tsx:123-128,237-252`(isResolution/showDecision 판정 + DecisionCard 조건부 렌더) — 정확 일치.
- TC-FE-161: `JobCard.tsx:90-98`(파생 태그 조건부 렌더) — 정확 일치.
- TC-FE-209: `AugmentResultPanel.tsx:168-197`(gridFrames 렌더 블록, FrameGrid12+SideBySideCompare authImages) — 정확 일치.
- TC-FE-210: `AugmentResultPanel.tsx:29-30,110-113,177-186`(FRAME_PAGE_SIZE 상수+showFramePager 판정+페이저 렌더) — 정확 일치.
- TC-FE-211: `AugmentVideoSection.tsx:45-64`(activeId state+탭 전환 시 프레임페이지 리셋 effect) — 정확 일치.
- TC-FE-158: `useResolutionDerivative.ts:17-27`(mutation 함수 전체) — 정확 일치. TC-FE-157: `augment/types.ts:24-46`(PROCESS_KINDS~PROCESS_KIND_DESCRIPTION) — 정확 일치. TC-FE-212: `augTypeLabel.ts:30-36`(augTypeLabel 함수 전체) — 정확 일치. TC-FE-213: `AugmentResultPage.tsx:263-296,339-421`(완료+결과0건 안내 미노출 블록·생성률 카드·summarize 함수 페이지스코프 주석) — 정확 일치, "비교 프레임 쌍은 페이지 스코프 값" 정정 문구도 코드 주석(340행 `⚠ **현재 항목 페이지에 실린 항목들**`)과 부합.
- TC-FE-162~164(DecisionCard.tsx/RejectReasonModal.tsx, 파일 레벨 근거)도 파일 존재·테스트(`DecisionCard.test.tsx`/`.discard.test.tsx`/`.restore.test.tsx`) 확인.
- TC-E2E-007: `augment-decision.spec.ts:4` 파일 존재 확인(2차 H-ISSUE-102가 지적한 "존재하지 않는 jobId 대상 무단언 통과" 문제는 H-11 인접 범위라 본 파트 판정 대상 아님, 참고만 기록).

## 이전 회차(2차) 이슈 대조 — 내 담당 범위(H-6/H-8/H-9/H-10) 관련분

| 2차 이슈 | 내용 | 3차 상태 |
|---|---|---|
| H-ISSUE-81 | TC-FE-146 검수화면 파생 표시 BE DTO 미보유 FAIL | **미해소 — 재확인 FAIL** (위 상세 참조) |
| H-ISSUE-82 | TC-FE-149 근거 file 드리프트(LOW) | **미해소이나 이번 회차 카탈로그 직접 정정 완료**(315행) |
| H-ISSUE-104 | TC-FE-213 "라벨 무결성"→"증강 이미지 생성률" 개명 카탈로그 정합 | **해소 확인** — 카탈로그가 이미 "증강 이미지 생성률"·"비교 프레임 쌍" 문구로 갱신되어 실제 코드(287행 "증강 이미지 생성률")와 일치 |
| H-ISSUE-105 (H-10 해당분) | TC-FE-159/160/161/209/210/211 근거 라인 드리프트 8건 중 H-10분 | **해소 확인** — 위 상세 참조, 재수정 불필요 |

## 카탈로그 정정 건수

**1건** (TC-FE-149, 315행 — 근거 file 교체 `router/__tests__/manageGuard.test.tsx:45-71`). H-10 관련 드리프트는 이미 타 세션이 정정 완료된 상태를 재확인만 함(신규 Edit 없음).

## 결론

담당 66건 중 PASS 65 / FAIL 1(TC-FE-146, 2차 H-ISSUE-81 미해소 이월). 카탈로그 자체 정합성은 H-6·H-10 전건 정확, H-9 1건 정정 완료, H-8 1건(TC-FE-135) 3줄 경미한 과다범위는 기능 검증 무해로 별도 이슈화 안 함.
