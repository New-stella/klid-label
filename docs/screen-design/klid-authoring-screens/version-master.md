# Version Master — 전체 통합 (DOMAIN-000) — 화면 키트

| 항목 | 값 |
|---|---|
| project_id | 4ece2c3f-8e99-46f5-9580-71108a76e578 |
| Domain | DOMAIN-000 전체 통합 |
| 다운로드 화면 | SCREEN-001, SCREEN-002, SCREEN-003, SCREEN-004, SCREEN-005, SCREEN-006, SCREEN-008, SCREEN-009, SCREEN-010, SCREEN-011, SCREEN-012, SCREEN-018, SCREEN-019, SCREEN-020, SCREEN-021, SCREEN-022, SCREEN-023, SCREEN-024, SCREEN-025, SCREEN-026, SCREEN-027, SCREEN-028, SCREEN-029, SCREEN-030, SCREEN-031, SCREEN-032, SCREEN-033, SCREEN-035, SCREEN-036, SCREEN-037, SCREEN-038, SCREEN-039, SCREEN-040, SCREEN-041, SCREEN-042, SCREEN-043, SCREEN-044, SCREEN-045, SCREEN-046 |
| Last sync | 2026-09-17T02:12:07.565Z (session 44) |
| Mode | SYNC — NEW 0 / CHANGED 0 / UNCHANGED 634 |
| 출력 루트 | docs/screen-design/klid-authoring-screens |

## ITEM 버전 표

| ITEM ID | type | title | version | stale | status |
|---|---|---|---|---|---|
| [[AC-1013]] | acceptance | UC-019 이벤트 마킹 — 마킹·질문 선택·완료→잔여 배치 시작 (happy) | 9 | true | UNCHANGED |
| [[AC-1014]] | acceptance | UC-019 마킹 중 비식별 누락 신고 → 마킹 중단·해소 후 재마킹 (edge) | 4 | true | UNCHANGED |
| [[AC-1015]] | acceptance | UC-019 마킹 진입·스트리밍·생성 차단 — 단계 불충족 412·비식별 미완료 404·관제 이벤트 유형 부재 400 (negative) | 8 | true | UNCHANGED |
| [[AC-1016]] | acceptance | UC-041 인증 인계와 채널·역할 인가 — 토큰 인계→발급처 검증→채널·역할 해석→인가 (happy) | 17 | true | UNCHANGED |
| [[AC-1017]] | acceptance | UC-041 인가 거부 — 역할 미해석·채널 어긋남·토큰 없음/만료 (negative) | 12 | true | UNCHANGED |
| [[AC-1018]] | acceptance | UC-030 사용자 계정·역할 관리 — 조회·검색·필터·역할·표시 이름 변경(유효창 가산) (happy) | 9 | false | UNCHANGED |
| [[AC-1019]] | acceptance | UC-030 사용자 수정 거부 — 유효창 없음/만료·역할 화이트리스트 밖 400·마지막 관리자 409·표시 이름 빈값/폭 초과 400 (negative) | 10 | false | UNCHANGED |
| [[AC-1020]] | acceptance | UC-018 영상 적재 — 관제 인입 직접 INSERT→폴링 클레임→파일 검증→LS_DATA_RAW 적재 (happy) | 9 | false | UNCHANGED |
| [[AC-1021]] | acceptance | UC-018 적재 예외 — 파일 미도착 PENDING 유지·backoff·중복 INSERT 차단·비식별 실패 보존·재큐 (negative) | 7 | false | UNCHANGED |
| [[AC-1022]] | acceptance | UC-038 배치 자동 처리 — 비식별 선두→마킹 대기→마킹완료 시 잔여단계 논블로킹 수행 (happy) | 7 | true | UNCHANGED |
| [[AC-1023]] | acceptance | UC-038 파이프라인 분기 — 프리셋 없어 오토라벨 보류·전체 건너뛰기·선점 노드 멈춤 회수 (edge) | 6 | true | UNCHANGED |
| [[AC-1024]] | acceptance | UC-004 객체 자동 추적 — 시드 지정→SAM2 track 전파→자동 라벨 저장·보간 (happy) | 7 | true | UNCHANGED |
| [[AC-1025]] | acceptance | UC-004 추적 실패·정확도 저하 — 서킷 브레이커·원본 유지·수동 보정 (negative) | 5 | true | UNCHANGED |
| [[AC-1026]] | acceptance | UC-005 객체 외곽 경계 자동 밀착 — SAM2 segment→폴리곤 단순화·적용 (happy) | 5 | true | UNCHANGED |
| [[AC-1027]] | acceptance | UC-005 밀착 실패 — 외곽 인식 실패 수동 전환·추론 실패 502·시드 유지 (negative) | 5 | true | UNCHANGED |
| [[AC-1028]] | acceptance | UC-006 라벨링 정밀도 조절 — epsilon 영속 설정·요청 1회성 override·폴백 (happy) | 4 | true | UNCHANGED |
| [[AC-1029]] | acceptance | UC-006 정밀도 설정 거부 — 범위 초과 400·WORKER 권한 부족 403 (negative) | 5 | true | UNCHANGED |
| [[AC-1030]] | acceptance | UC-034 온디맨드 AI 자동 추적 — 구간 다객체 조회·묶음 수락/자동반영·labelId 보존 저장 (happy) | 4 | true | UNCHANGED |
| [[AC-1031]] | acceptance | UC-034 온디맨드 추적 분기 — labelId 없는 검출 미반영·묶음 제외·미저장 미확정 (edge) | 5 | true | UNCHANGED |
| [[AC-1032]] | acceptance | UC-037 마킹 끝난 영상 일괄 올리기 — 폴더 재귀 짝짓기·판정·비동기 적재·비식별 후 마킹 활성 (happy) | 9 | true | UNCHANGED |
| [[AC-1033]] | acceptance | UC-037 일괄 적재 예외 — 짝 못찾음 건너뜀·FPS 어긋남 중단·중복 식별자 건너뜀·비식별 실패 마감·재기동 복구 (negative) | 11 | true | UNCHANGED |
| [[AC-1034]] | acceptance | UC-039 온라인 AI 객체 탐지 — 락·배정 확인→마스터 교집합→문턱 결정→좌표만 응답 (happy) | 6 | true | UNCHANGED |
| [[AC-1035]] | acceptance | UC-039 온라인 탐지 분기 — 미매핑 외부호출 안 함·실패/목 자동적용 안 함·추론 뒤 락 충돌 (negative) | 5 | true | UNCHANGED |
| [[AC-1036]] | acceptance | UC-022 VLM 시계열 메타 검토 — 두 창구 콜백 적재·검수큐 진입·어노 초안 채움·창 검토·승인 (happy) | 9 | false | UNCHANGED |
| [[AC-1037]] | acceptance | UC-022 VLM 결과 오류 — 검수자 직접 수정 후 승인 또는 반려 (edge) | 7 | false | UNCHANGED |
| [[AC-1038]] | acceptance | UC-010 증강 영상 활용 검수 — 조회·확인·accept/reject(리뷰 축)·등재 게이트·유예 폐기 (happy) | 8 | true | UNCHANGED |
| [[AC-1039]] | acceptance | UC-010 증강 검수 예외 — 재결정 409·사유 누락 400·유예내 복구·해상도 예외·그랜드퍼더링·검수 승인 이력 제외 (negative) | 6 | true | UNCHANGED |
| [[AC-1040]] | acceptance | UC-023 검수 승인·반려 — 제출→검토→승인(APPROVED/COMPLETED)+스냅샷+export+통지 / 반려 (happy) | 7 | true | UNCHANGED |
| [[AC-1041]] | acceptance | UC-023 비식별 미완료 승인 거부 — 전이·스냅샷·재생성·통지 전부 미발생 (negative) | 6 | true | UNCHANGED |
| [[AC-1042]] | acceptance | UC-023 재제출 반복·검수완료 후 수정 → 재검토 표시·재승인 시 새 버전·TASK_MODIFIED (edge) | 7 | true | UNCHANGED |
| [[AC-1043]] | acceptance | UC-033 전체 구축 현황 조회 — 검수완료 기준 주수치·전체 병기·표시명 그룹 분포 (happy) | 4 | true | UNCHANGED |
| [[AC-1044]] | acceptance | UC-001 증강 영상 생성 요청 — 대상·생성 조건 지정→외부 생성형 AI 위임→비동기 수락 (happy) | 6 | true | UNCHANGED |
| [[AC-1045]] | acceptance | UC-001 증강 요청 거부 — 파생본 400·비식별 신고 구간 412 (negative) | 4 | true | UNCHANGED |
| [[AC-1046]] | acceptance | UC-002 증강 결과 수신·등록 — 콜백 검증→새 RAW_SN·ORGNL 연결→비식별본 복사→라벨/메타 복사·계승→PENDING (happy) | 8 | true | UNCHANGED |
| [[AC-1047]] | acceptance | UC-002 증강 결과 예외 — 검증 실패·비식별본 부재·재수신 멱등/409·다건 유일부여 (negative) | 6 | true | UNCHANGED |
| [[AC-1048]] | acceptance | UC-003 해상도 변경 수행 — 프리셋 예약→비디오 복사+프레임 균일배율 리스케일→좌표 재계산→finalize (happy) | 6 | true | UNCHANGED |
| [[AC-1049]] | acceptance | UC-003 해상도 변경 예외 — 전부 스킵 400·일부 실패 201/전부 500·파생본 400·비식별 신고 통과 (negative) | 5 | true | UNCHANGED |
| [[AC-1050]] | acceptance | UC-040 공지·가이드라인 관리와 열람 — 목록·작성·수정·발행·열람·삭제 (happy) | 7 | true | UNCHANGED |
| [[AC-1051]] | acceptance | UC-040 공지 접근·첨부·쓰기 제어 — 초안 존재 미노출·허용 밖 첨부 거부·작업자 쓰기 진입 차단 (negative) | 5 | true | UNCHANGED |
| [[AC-1052]] | acceptance | UC-007 라벨 버전 저장·이력 추적 — 승인 시점 전체 스냅샷 적재·해시 식별·이력 기록 (happy) | 9 | true | UNCHANGED |
| [[AC-1053]] | acceptance | UC-007 비식별 미완료로 승인 거부 시 버전 미생성 — 스냅샷·해시·이력·재생성·통지 미발생 (negative) | 7 | true | UNCHANGED |
| [[AC-1054]] | acceptance | UC-008 버전 비교·복구 — 버전 목록→로드 모달(최신 기본)→작업본 diff→전체 불러오기·저장 (happy) | 6 | true | UNCHANGED |
| [[AC-1055]] | acceptance | UC-008 버전 화면 분기 — 둘 미만 모달 미표시·변경없음 안내·손상 400·저장 충돌 409 (negative) | 5 | true | UNCHANGED |
| [[AC-1056]] | acceptance | UC-008 확정 저장 게이트 — 신고 구간 412·검수완료 후 저장 통지(UC-009)·폐기 회차 적용 예외 (negative) | 5 | true | UNCHANGED |
| [[AC-1057]] | acceptance | UC-021 라벨 편집·임시저장 — 본인 배정 확인→캔버스 편집·속성→full-replace upsert(낙관적 토큰) (happy) | 6 | true | UNCHANGED |
| [[AC-1058]] | acceptance | UC-021 라벨 편집 예외 — 타인 403·APPROVED 수정 재검토·신고 구간 412·값 검증·저장 충돌 409 (negative) | 10 | true | UNCHANGED |
| [[AC-1059]] | acceptance | UC-028 라벨 클래스·속성 정의 관리 — 마스터·속성 CRUD(단일 진실원)·형태·매핑 즉시 반영 (happy) | 6 | true | UNCHANGED |
| [[AC-1060]] | acceptance | UC-028 라벨 관리 예외 — 미연결 코드 '미연결' 표시·WORKER 403 (negative) | 4 | true | UNCHANGED |
| [[AC-1061]] | acceptance | UC-032 라벨 프리셋 CRUD — 이벤트유형+라벨 멀티셀렉트(이름·설명 없음)·labelId 실시간 join·보류 재개 (happy) | 7 | true | UNCHANGED |
| [[AC-1062]] | acceptance | UC-032 프리셋 저장 분기 — 라벨 0건=오토라벨 제외·중복 409·미지정 400·미연결 자동제외·AI 미매핑 저장+경고 (negative) | 5 | true | UNCHANGED |
| [[AC-1063]] | acceptance | UC-011 비식별 처리 요청 — KPST 위탁(공유마운트 경로만)→폴링 완료 감지→비식별본 저장·원본 유지·이력·출처유형 제외 시 원본 복사로 완료 (happy) | 9 | true | UNCHANGED |
| [[AC-1064]] | acceptance | UC-011 비식별 위탁 예외 — 연동 실패 F·원본 보존·수동 재비식별·응답 유실 폴링 회수 F 마감·취소 종결만 상태 불변·신고 구간 보류·제외 복사 실패 F·제외 행 회수 대상 아님·제외 출처유형 오표기는 검수 중 신고로 회수 (negative) | 13 | true | UNCHANGED |
| [[AC-1065]] | acceptance | UC-013 비식별 옵션 설정 — 관리 화면에서 설정·저장·이후 요청에 적용 (happy) | 4 | true | UNCHANGED |
| [[AC-1066]] | acceptance | UC-016 비식별 상태·이력·누락 신고·해소 — 상태 확인→신고(작업락+F, 라벨 보존)→구간 차단→산출물 선택(무결성 판정)→resolve 원자 클레임·단계별 재개 (happy) | 11 | true | UNCHANGED |
| [[AC-1067]] | acceptance | UC-016 신고·해소 예외 — 무결성 불통과 409·이미 잠금 409·재-resolve 409·마킹 조건 412·파생 412·타인 403·비식별 실패 F (negative) | 8 | false | UNCHANGED |
| [[AC-1068]] | acceptance | UC-024 포털 라벨 작업 — 마트 영상 Load·수정·메타/어노 편집(포털 전용 저장, 단방향)·기간내 다운로드 (happy) | 13 | true | UNCHANGED |
| [[AC-1069]] | acceptance | UC-024 포털 작업 데이터 격리 — 타인 작업 데이터 조회/다운로드 403 (negative) | 10 | true | UNCHANGED |
| [[AC-1070]] | acceptance | UC-027 포털 자산 업로드·수동 라벨링 — 업로드·등록·업로드 영상 마킹·BBOX/POLYGON 수동 라벨링·AI 증강(검수 없음)·다운로드 (happy) | 20 | true | UNCHANGED |
| [[AC-1071]] | acceptance | UC-027 포털 업로드 예외 — 재개 가능 업로드·타인 자산 403 (negative) | 10 | false | UNCHANGED |
| [[AC-1072]] | acceptance | UC-031 시스템 운영 설정 관리 — 카드별 변경분만 개별 PUT·관리자 유효창으로 연동 주소·위험 액션·헬스 폴링 (happy) | 13 | false | UNCHANGED |
| [[AC-1073]] | acceptance | UC-031 설정 저장 거부 — 범위 밖 400 공통 토스트·변경 없는 카드 비활성·위험 액션 취소 (negative) | 5 | true | UNCHANGED |
| [[AC-1074]] | acceptance | UC-031 관리자 유효창·연동 주소 거부 — 유효창 없이 403·주소 값 위반 400·만료 후 이어서·패스워드 불일치·위반 주소는 기동 통과 후 나가려는 순간 거부 (negative) | 7 | false | UNCHANGED |
| [[AC-1075]] | acceptance | UC-029 작업 목록 조회·필터링·배정 — 역할별 범위·일괄 확정 필터·시간축 정렬·KPI 토글·배정/일괄배정 (happy) | 6 | true | UNCHANGED |
| [[AC-1076]] | acceptance | UC-029 작업 목록 분기 — 미등록 정렬 키 strict 400·옵션 절단 안내·배정 이력 Drawer (negative) | 6 | true | UNCHANGED |
| [[AC-1077]] | acceptance | UC-009 검수 완료·수정 통지 — TASK_COMPLETED/TASK_MODIFIED 평면 페이로드 비동기 push·조회 UPSERT (happy) | 6 | true | UNCHANGED |
| [[AC-1078]] | acceptance | UC-009 통지 신뢰성 — 전송 실패 dead-letter·재등록 큐·다수 변경 재승인 1건=통지 1건 (edge) | 8 | true | UNCHANGED |
| [[AC-1079]] | acceptance | UC-035 외부 산출물 가져오기 — 폴더 탐색·원본/비식별 지정·판정 단일값·분류 매핑·적재·검수 대기 (happy) | 9 | true | UNCHANGED |
| [[AC-1080]] | acceptance | UC-035 이관 적재 거부/경고 — 이미 가져옴 거부·미확정 분류 거부·허용 범위 밖 위치 거부·짝 안 맞음 경고 미차단 (negative) | 7 | true | UNCHANGED |
| [[AC-1081]] | acceptance | UC-035 원본 지정 이관 — 검수 승인 보류(비식별화완료여부 N)·다른 통로는 열림·비식별 성공 시 보류 해제 (edge) | 7 | true | UNCHANGED |
| [[AC-1082]] | acceptance | UC-036 이관 이력 조회 — 목록 시간순·상태 필터·승인 보류 축 가려내기·상세 경위/실패 사유 (happy) | 7 | true | UNCHANGED |
| [[AC-1083]] | acceptance | UC-036 이관 이력 조회 예외 — 잘못 물은 요청 거부·빈 상태 별도·찾을 수 없음·보류 미상 3갈래·실패 사유 갈래 (negative) | 5 | true | UNCHANGED |
| [[AC-1084]] | acceptance | SCREEN-021 전체 구축 현황 리포트 내려받기 — 섹션 5개 실집계와 화면 수치 일치 (happy) | 3 | true | UNCHANGED |
| [[AC-1085]] | acceptance | SCREEN-021 전체 구축 현황 리포트 내려받기 — 권한·기간값 거부와 셀 안전 처리 (negative) | 4 | true | UNCHANGED |
| [[AC-1086]] | acceptance | SCREEN-027 파일 업로드 — 적재 경로 선택·단건 전송·기술메타 자동 채움·결과 상태 추적 (happy) | 3 | false | UNCHANGED |
| [[AC-1087]] | acceptance | SCREEN-027 파일 업로드 — 유효창 없는 시작·허용 밖 형식과 용량·필수값 누락·중복 식별자 거부 (negative) | 2 | true | UNCHANGED |
| [[AC-1088]] | acceptance | AI 장비를 유형별로 여러 대 등록해 쓸 수 있다 | 2 | false | UNCHANGED |
| [[AC-1089]] | acceptance | 장비 주소는 저장될 때도 검증을 받는다 | 2 | false | UNCHANGED |
| [[AC-1090]] | acceptance | 장비 관리 쓰기는 관리자와 유효창을 함께 요구한다 | 2 | false | UNCHANGED |
| [[AC-1091]] | acceptance | 그 유형의 마지막 가용 장비는 잃지 않는다 | 2 | false | UNCHANGED |
| [[AC-1092]] | acceptance | 시계열 장비 씨앗은 주소가 있을 때만 심고 덮어쓰지 않는다 | 2 | false | UNCHANGED |
| [[AC-1095]] | acceptance | 관제 통지 x-access-token 은 발송 시점에 관제 규칙으로 발급된다 | 1 | true | UNCHANGED |
| [[AC-1096]] | acceptance | 정적 토큰 override 가 설정되면 동적 발급보다 우선한다 | 1 | true | UNCHANGED |
| [[AC-1097]] | acceptance | 시크릿·토큰 미설정 시 x-access-token 미부착 fail-safe | 1 | true | UNCHANGED |
| [[AC-1098]] | acceptance | UC-041 역할 미부여 진입자의 이름 조달과 창구 개폐 판정 — 자기 정보 조회→개폐 조회→도착지 갈림 (happy) | 5 | true | UNCHANGED |
| [[AC-1104]] | acceptance | 관제 채널 토큰 추종과 세션 갱신 — 매 요청 현재 토큰·선제 갱신 1회·401 후 1회 재시도·관제 형식 저장·탭 간 중복 갱신 방지 (happy) | 3 | true | UNCHANGED |
| [[AC-1105]] | acceptance | 관제 채널 세션 만료 연장 팝업 — 임계 도달 시 표시(감시 주기 되풀이 없음)·「로그아웃」·「로그인 연장」 두 버튼만(ESC·배경 클릭 무반응)·만료 시 로그아웃·셸 밖 전체 화면 적용·미저장 편집 경고와 만료 시 확인 없이 이동 | 8 | false | UNCHANGED |
| [[AC-1106]] | acceptance | 관제 채널 세션 종결과 적용 경계 — 갱신 거절·401 재시도 실패 시 즉시 로그아웃(다른 탭이 이미 갱신했으면 예외)·일시 장애는 만료까지 유지·세션 만료 아닌 인증 실패 제외·관제 로그아웃 추종·갱신 토큰 없는 진입·포털 채널 무동작 (negative) | 9 | false | UNCHANGED |
| [[AC-1107]] | acceptance | 소재 조달 경로 봉쇄 — 응답이 주장한 저장소 루트를 기준으로 삼지 않는다 (negative) | 1 | true | UNCHANGED |
| [[AC-1108]] | acceptance | 포털 라벨링 AI 보조 — AI 탐지·AI 분할·AI 자동 추적 노출·작업 대상 인가·요청량 한도·비식별 판정 미적용·채널 격리·관제향 화면 무변경 (happy·negative) | 3 | false | UNCHANGED |
| [[AC-1109]] | acceptance | UC-023 검수 시작 — 점유 성립·상태 전이는 한 갈래뿐·승인/반려 기록으로 자동 해제 (happy) | 4 | false | UNCHANGED |
| [[AC-1110]] | acceptance | UC-023 검수 시작 본인 재진입 — 멱등 성공·점유 시각 갱신·최초 시작 시각 보존 (edge) | 1 | true | UNCHANGED |
| [[AC-1111]] | acceptance | UC-023 점유 유예 만료 — 저절로 풀리고 다른 검수자가 이어받는다 (edge) | 1 | true | UNCHANGED |
| [[AC-1112]] | acceptance | UC-023 검수 시작 충돌 — 타인 점유는 점유자 이름과 함께 거절, 동시 경합의 결말은 갈래마다 다르다 (negative) | 3 | false | UNCHANGED |
| [[AC-1113]] | acceptance | UC-023 검수 일괄 승인 — 부분 실패 허용·성공분은 단건 승인과 같은 결과 (edge) | 1 | true | UNCHANGED |
| [[AC-1114]] | acceptance | UC-023 검수 일괄 승인 요청 거부 — 빈 목록·건수 상한 초과는 한 건도 처리하지 않는다 (negative) | 1 | true | UNCHANGED |
| [[AC-1115]] | acceptance | UC-023 관리자 검수 — 검수 시작·승인·반려를 그대로 수행하고 이력에 관리자로 남는다 (happy) | 1 | true | UNCHANGED |
| [[AC-1116]] | acceptance | UC-023 검수 목록 전체 열람 — 배정과 무관하게 전체가 보이고 점유는 표시이지 필터가 아니다 (happy) | 1 | true | UNCHANGED |
| [[AC-1117]] | acceptance | UC-023 일괄 승인 건수 상한 — 응답 한 번에 하나 실리고 화면이 그 값으로 선택을 미리 제한한다 (happy) | 1 | true | UNCHANGED |
| [[AC-1118]] | acceptance | 포털 데이터셋 영상 원장 등록 — 소재 준비 뒤 출처 PORTAL_DATASET 등록·재등록 멱등·구조 불일치 실패·원본 이미지 비노출·검수 상태 없음·내부 작업 범위 제외 (happy·negative) | 6 | true | UNCHANGED |
| [[AC-1119]] | acceptance | 포털 데이터셋 영상 목록 창구 — 소재 미준비 409·포털 회원 아님 403·진입 프레임과 마지막 저장 시각의 요청 사용자 격리·끊긴 등록 다시 시작 (happy·negative) | 2 | true | UNCHANGED |
| [[AC-1120]] | acceptance | 포털 데이터셋 등록 영상의 포털 작업 창구 통과 — 검수 상태 없이 조회·저장·AI 보조·내려받기 허용·원본 라벨 불변·승인 영상 무회귀 (happy·negative) | 5 | true | UNCHANGED |
| [[AC-1121]] | acceptance | 영상 제외의 경계 — 화면 목록에서만 빠지고 배치·관제 통지·데이터마트 조회 뷰·학습데이터 산출물·통계·포털은 그대로다 (happy) | 4 | true | UNCHANGED |
| [[AC-1122]] | acceptance | ADR-069 배정 해제 — 배정만 풀리고 라벨·이력은 남으며 해제 대상자가 이력에 실린다 (happy) | 1 | true | UNCHANGED |
| [[AC-1123]] | acceptance | ADR-069 배정 해제 거부 — 검수 단계 배정은 풀리지 않고 반려는 풀린다·작업자는 할 수 없다 (negative) | 3 | false | UNCHANGED |
| [[AC-1124]] | acceptance | 영상 제외·복원 — 화면 목록 세 곳에서 빠지고 건수·집계가 함께 줄며 되돌리면 다시 보인다 (happy) | 8 | true | UNCHANGED |
| [[AC-1125]] | acceptance | 영상 제외·복원 멱등 — 같은 값을 다시 보내면 아무것도 바뀌지 않고 이력도 남지 않으며 오류가 아니다 (edge) | 3 | true | UNCHANGED |
| [[AC-1126]] | acceptance | 영상 제외·복원 인가 — 작업자는 수행할 수 없고 검수자 이상만 수행하며 작업자 화면에는 그 동작이 없다 (negative) | 5 | true | UNCHANGED |
| [[AC-1127]] | acceptance | ADR-069 배정과 제외의 충돌 — 배정된 영상은 제외 거부·안내가 해제 창구로 이어진다·제외분에는 배정이 없다 (negative) | 8 | false | UNCHANGED |
| [[AC-1128]] | acceptance | 프리셋 편집에서 라벨을 상한보다 많이 고르면 저장할 수 없고 막힌 사유가 저장 수단 곁에서 보인다 (negative) | 3 | false | UNCHANGED |
| [[AC-1129]] | acceptance | 이벤트유형 관리 화면 질문 목록 입력 판정 — 비었거나 공백뿐·상한 초과·개행/제어문자는 저장 전에 그 질문 행 옆에서 막히고 안내를 배너 한 줄로 합치지 않는다 (negative) | 4 | false | UNCHANGED |
| [[AC-1130]] | acceptance | 이벤트유형 관리 화면 질문 목록 저장 거부 표시 — 서버 응답 문구·필드 경로·배열 순번을 그대로 내보내지 않고, 서버 검증은 백스톱으로 남아 화면을 거치지 않은 호출도 거절하며 부분 반영을 남기지 않는다 (negative) | 4 | false | UNCHANGED |
| [[AC-1131]] | acceptance | 이벤트유형 표시명·수집여부 저장 직후 목록 반영 | 2 | true | UNCHANGED |
| [[AC-1132]] | acceptance | 포털 데이터셋 영상 등록 재착수 — 실패 표식만 재착수·완료/진행 중 멱등·소재 미준비 409·목록 응답의 실패 사유·화면 다시 등록 (happy·negative) | 2 | true | UNCHANGED |
| [[AC-1133]] | acceptance | UC-011 선두 비식별 실패 후 배치 재시작 — 건별·일괄 접수(stage=PENDING·선점 없음)→선두 비식별 1회 재수행→비식별 완료·마킹 준비·잠금 해제, 미실패 영상 불변·자동 재시도 없음 (happy) | 3 | false | UNCHANGED |
| [[AC-1134]] | acceptance | UC-011 선두 비식별 실패 재시작 거부 — 파생 400·승인 이력/열린 신고/진행 중 위탁 409·동시 요청 1건만 수락·영상 없음 404·작업자 403·재수행 없음 (negative) | 1 | true | UNCHANGED |
| [[AC-1135]] | acceptance | UC-011 선두 비식별 실패 재시작 잠금 해제 — 모든 실패 종결과 제외 복사 성공에서 해제돼 재요청 수락·다른 기능 잠금 유지 (negative) | 2 | true | UNCHANGED |
| [[API-001]] | api_endpoint | GET /v1/users | 10 | false | UNCHANGED |
| [[API-002]] | api_endpoint | GET /v1/users/workers | 2 | false | UNCHANGED |
| [[API-003]] | api_endpoint | GET /v1/users/{userNo} | 6 | false | UNCHANGED |
| [[API-004]] | api_endpoint | PATCH /v1/users/{userNo} | 12 | false | UNCHANGED |
| [[API-005]] | api_endpoint | GET /v1/users/me | 6 | false | UNCHANGED |
| [[API-006]] | api_endpoint | GET /v1/me | 13 | false | UNCHANGED |
| [[API-007]] | api_endpoint | POST /v1/auth/role-claim | 17 | true | UNCHANGED |
| [[API-008]] | api_endpoint | GET /v1/reviews | 20 | false | UNCHANGED |
| [[API-009]] | api_endpoint | GET /v1/reviews/{videoId} | 10 | false | UNCHANGED |
| [[API-010]] | api_endpoint | GET /v1/reviews/{videoId}/frames | 4 | false | UNCHANGED |
| [[API-011]] | api_endpoint | GET /v1/reviews/{videoId}/issues | 4 | false | UNCHANGED |
| [[API-012]] | api_endpoint | POST /v1/reviews/{videoId}/submit | 6 | false | UNCHANGED |
| [[API-013]] | api_endpoint | POST /v1/reviews/{videoId}/start | 11 | false | UNCHANGED |
| [[API-014]] | api_endpoint | POST /v1/reviews/{videoId}/approve | 13 | false | UNCHANGED |
| [[API-015]] | api_endpoint | POST /v1/reviews/{videoId}/reject | 9 | false | UNCHANGED |
| [[API-016]] | api_endpoint | POST /v1/meta/{metaReviewSn}/approve | 3 | false | UNCHANGED |
| [[API-017]] | api_endpoint | POST /v1/meta/{metaReviewSn}/reject | 5 | false | UNCHANGED |
| [[API-018]] | api_endpoint | GET /v1/frames/{srcSn}/labels | 5 | false | UNCHANGED |
| [[API-019]] | api_endpoint | PUT /v1/frames/{srcSn}/labels | 8 | false | UNCHANGED |
| [[API-020]] | api_endpoint | POST /v1/frames/{srcSn}/sam2-track | 15 | false | UNCHANGED |
| [[API-021]] | api_endpoint | GET /v1/frames/{srcSn}/image | 9 | false | UNCHANGED |
| [[API-022]] | api_endpoint | GET /v1/labels/{lblSn}/attrs | 4 | false | UNCHANGED |
| [[API-023]] | api_endpoint | PUT /v1/labels/{lblSn}/attrs | 4 | false | UNCHANGED |
| [[API-024]] | api_endpoint | GET /v1/manage/labels | 6 | false | UNCHANGED |
| [[API-025]] | api_endpoint | POST /v1/manage/labels | 9 | false | UNCHANGED |
| [[API-026]] | api_endpoint | PUT /v1/manage/labels/{id} | 5 | false | UNCHANGED |
| [[API-027]] | api_endpoint | DELETE /v1/manage/labels/{id} | 4 | false | UNCHANGED |
| [[API-028]] | api_endpoint | GET /v1/manage/labels/{labelId}/attrs | 3 | false | UNCHANGED |
| [[API-029]] | api_endpoint | POST /v1/manage/labels/{labelId}/attrs | 4 | false | UNCHANGED |
| [[API-030]] | api_endpoint | PUT /v1/manage/labels/{labelId}/attrs/{attrId} | 4 | false | UNCHANGED |
| [[API-031]] | api_endpoint | DELETE /v1/manage/labels/{labelId}/attrs/{attrId} | 3 | false | UNCHANGED |
| [[API-032]] | api_endpoint | POST /v1/labels/{srcSn}/deident-report | 10 | true | UNCHANGED |
| [[API-034]] | api_endpoint | GET /v1/frames/{srcSn}/versions | 9 | false | UNCHANGED |
| [[API-035]] | api_endpoint | GET /v1/versions/{version}/diff | 11 | false | UNCHANGED |
| [[API-036]] | api_endpoint | POST /v1/versions/{version}/rollback | 11 | false | UNCHANGED |
| [[API-037]] | api_endpoint | GET /v1/manage/presets | 12 | false | UNCHANGED |
| [[API-038]] | api_endpoint | POST /v1/manage/presets | 14 | false | UNCHANGED |
| [[API-039]] | api_endpoint | PUT /v1/manage/presets/{id} | 13 | false | UNCHANGED |
| [[API-040]] | api_endpoint | DELETE /v1/manage/presets/{id} | 4 | false | UNCHANGED |
| [[API-041]] | api_endpoint | [폐기] POST /v1/manage/presets/{id}/clone | 5 | false | UNCHANGED |
| [[API-042]] | api_endpoint | GET /v1/videos | 10 | false | UNCHANGED |
| [[API-043]] | api_endpoint | GET /v1/videos/{rawSn} | 28 | false | UNCHANGED |
| [[API-044]] | api_endpoint | GET /v1/videos/{rawSn}/labels/auto | 9 | false | UNCHANGED |
| [[API-045]] | api_endpoint | GET /v1/videos/{rawSn}/auto-summary | 2 | false | UNCHANGED |
| [[API-046]] | api_endpoint | GET /v1/videos/{rawSn}/frames/{frameNo}/image | 7 | false | UNCHANGED |
| [[API-047]] | api_endpoint | POST /v1/videos/{rawSn}/markings | 16 | false | UNCHANGED |
| [[API-055]] | api_endpoint | GET /v1/stats/summary | 6 | false | UNCHANGED |
| [[API-056]] | api_endpoint | GET /v1/stats/worker | 7 | false | UNCHANGED |
| [[API-057]] | api_endpoint | GET /v1/stats/overall | 6 | false | UNCHANGED |
| [[API-058]] | api_endpoint | GET /v1/stats/report | 4 | false | UNCHANGED |
| [[API-059]] | api_endpoint | GET /v1/augments | 9 | true | UNCHANGED |
| [[API-060]] | api_endpoint | POST /v1/augments/request | 18 | true | UNCHANGED |
| [[API-061]] | api_endpoint | GET /v1/augments/{jobId}/result | 11 | true | UNCHANGED |
| [[API-062]] | api_endpoint | POST /v1/augments/{id}/accept | 10 | true | UNCHANGED |
| [[API-063]] | api_endpoint | POST /v1/augments/{id}/reject | 10 | true | UNCHANGED |
| [[API-065]] | api_endpoint | POST /v1/vlm/callback | 25 | false | UNCHANGED |
| [[API-066]] | api_endpoint | GET /v1/frames/{srcSn}/meta | 7 | false | UNCHANGED |
| [[API-067]] | api_endpoint | PUT /v1/frames/{srcSn}/meta | 8 | false | UNCHANGED |
| [[API-068]] | api_endpoint | GET /v1/manage/configs | 4 | false | UNCHANGED |
| [[API-069]] | api_endpoint | PUT /v1/manage/configs/{key} | 13 | false | UNCHANGED |
| [[API-070]] | api_endpoint | POST /v1/assignments | 12 | false | UNCHANGED |
| [[API-071]] | api_endpoint | PATCH /v1/assignments/{assignmentId} | 10 | false | UNCHANGED |
| [[API-072]] | api_endpoint | GET /v1/assignments | 12 | false | UNCHANGED |
| [[API-073]] | api_endpoint | GET /v1/tasks/board | 12 | false | UNCHANGED |
| [[API-074]] | api_endpoint | GET /v1/tasks/{rawSn}/summary | 7 | false | UNCHANGED |
| [[API-075]] | api_endpoint | GET /v1/tasks/{rawSn}/labels | 8 | false | UNCHANGED |
| [[API-076]] | api_endpoint | GET /v1/tasks/{rawSn}/meta | 9 | false | UNCHANGED |
| [[API-081]] | api_endpoint | GET /v1/portal/datamart/labels | 8 | false | UNCHANGED |
| [[API-082]] | api_endpoint | POST /v1/portal/user-labels | 11 | false | UNCHANGED |
| [[API-083]] | api_endpoint | GET /v1/portal/user-labels | 6 | false | UNCHANGED |
| [[API-084]] | api_endpoint | GET /v1/videos/{rawSn}/stream | 9 | false | UNCHANGED |
| [[API-090]] | api_endpoint | GET /v1/manage/health | 4 | false | UNCHANGED |
| [[API-091]] | api_endpoint | POST /v1/videos/{rawSn}/deident-report | 13 | true | UNCHANGED |
| [[API-092]] | api_endpoint | POST /v1/videos/{rawSn}/resolution | 10 | true | UNCHANGED |
| [[API-093]] | api_endpoint | POST /v1/frames/{srcSn}/sam2-segment | 15 | false | UNCHANGED |
| [[API-094]] | api_endpoint | POST /v1/deident-reports/{rprtSn}/resolve | 10 | true | UNCHANGED |
| [[API-095]] | api_endpoint | GET /v1/notices | 6 | false | UNCHANGED |
| [[API-096]] | api_endpoint | GET /v1/notices/{id} | 7 | false | UNCHANGED |
| [[API-097]] | api_endpoint | POST /v1/notices | 7 | false | UNCHANGED |
| [[API-098]] | api_endpoint | PUT /v1/notices/{id} | 7 | false | UNCHANGED |
| [[API-099]] | api_endpoint | DELETE /v1/notices/{id} | 6 | false | UNCHANGED |
| [[API-100]] | api_endpoint | POST /v1/notices/{id}/publish | 8 | false | UNCHANGED |
| [[API-101]] | api_endpoint | POST /v1/notices/{id}/unpublish | 8 | false | UNCHANGED |
| [[API-102]] | api_endpoint | POST /v1/videos/{rawSn}/issues | 15 | false | UNCHANGED |
| [[API-103]] | api_endpoint | GET /v1/videos/{rawSn}/issues | 11 | false | UNCHANGED |
| [[API-104]] | api_endpoint | POST /v1/issues/{issueSn}/comments | 16 | false | UNCHANGED |
| [[API-105]] | api_endpoint | POST /v1/issues/{issueSn}/resolve | 8 | false | UNCHANGED |
| [[API-106]] | api_endpoint | POST /v1/notices/{id}/attachments | 10 | false | UNCHANGED |
| [[API-107]] | api_endpoint | GET /v1/notices/{id}/attachments/{attachId}/download | 7 | false | UNCHANGED |
| [[API-108]] | api_endpoint | DELETE /v1/notices/{id}/attachments/{attachId} | 6 | false | UNCHANGED |
| [[API-109]] | api_endpoint | GET /v1/deident-reports | 5 | false | UNCHANGED |
| [[API-110]] | api_endpoint | GET /v1/portal/frames/{srcSn}/labels | 4 | false | UNCHANGED |
| [[API-111]] | api_endpoint | GET /v1/portal/frames/{srcSn}/image | 4 | true | UNCHANGED |
| [[API-112]] | api_endpoint | POST /v1/videos/{rawSn}/redeident | 7 | false | UNCHANGED |
| [[API-113]] | api_endpoint | POST /infer/yolo/predict | 4 | false | UNCHANGED |
| [[API-114]] | api_endpoint | GET /v1/videos/{rawSn}/stream-url | 4 | false | UNCHANGED |
| [[API-115]] | api_endpoint | GET /v1/portal/datamart/videos | 4 | false | UNCHANGED |
| [[API-116]] | api_endpoint | GET /v1/assignments/{assignmentId}/history | 10 | false | UNCHANGED |
| [[API-117]] | api_endpoint | GET /v1/event-types/labels | 5 | false | UNCHANGED |
| [[API-118]] | api_endpoint | GET /v1/system/scheduler/health | 2 | false | UNCHANGED |
| [[API-119]] | api_endpoint | POST /infer/yolo/track | 4 | false | UNCHANGED |
| [[API-120]] | api_endpoint | POST /infer/sam2/segment | 4 | false | UNCHANGED |
| [[API-121]] | api_endpoint | POST /infer/sam2/track | 4 | false | UNCHANGED |
| [[API-122]] | api_endpoint | POST /infer/vlm/verify-objects | 2 | false | UNCHANGED |
| [[API-123]] | api_endpoint | POST /v1/frames/{srcSn}/yolo-track | 14 | false | UNCHANGED |
| [[API-124]] | api_endpoint | POST /v1/frames/{srcSn}/autolabel | 11 | false | UNCHANGED |
| [[API-125]] | api_endpoint | POST /v1/videos/{rawSn}/tracks/merge | 2 | false | UNCHANGED |
| [[API-126]] | api_endpoint | DELETE /v1/videos/{rawSn}/tracks/{trackId} | 2 | false | UNCHANGED |
| [[API-127]] | api_endpoint | POST /v1/videos/{rawSn}/tracks/{trackId}/split | 2 | false | UNCHANGED |
| [[API-128]] | api_endpoint | GET /v1/frames/{srcSn}/description | 3 | false | UNCHANGED |
| [[API-129]] | api_endpoint | PUT /v1/frames/{srcSn}/description | 5 | false | UNCHANGED |
| [[API-132]] | api_endpoint | GET /v1/videos/{rawSn}/event-annotation | 7 | false | UNCHANGED |
| [[API-133]] | api_endpoint | POST /v1/videos/{rawSn}/event-annotation/approve | 4 | false | UNCHANGED |
| [[API-134]] | api_endpoint | PUT /v1/videos/{rawSn}/event-annotation | 7 | false | UNCHANGED |
| [[API-135]] | api_endpoint | POST /v1/videos/{rawSn}/event-annotation/reject | 4 | false | UNCHANGED |
| [[API-136]] | api_endpoint | GET /v1/tasks/board/summary | 6 | false | UNCHANGED |
| [[API-137]] | api_endpoint | GET /v1/tasks/board/event-types | 7 | false | UNCHANGED |
| [[API-138]] | api_endpoint | GET /v1/reviews/summary | 6 | false | UNCHANGED |
| [[API-140]] | api_endpoint | GET /v1/portal/uploads/{uldSn} | 6 | true | UNCHANGED |
| [[API-141]] | api_endpoint | GET /health | 2 | false | UNCHANGED |
| [[API-142]] | api_endpoint | GET /v1/portal/uploads | 8 | true | UNCHANGED |
| [[API-143]] | api_endpoint | POST /v1/dev/batch/scan | 3 | false | UNCHANGED |
| [[API-144]] | api_endpoint | POST /v1/dev/batch/trigger | 3 | false | UNCHANGED |
| [[API-145]] | api_endpoint | POST /v1/dev/batch/trigger/next | 3 | false | UNCHANGED |
| [[API-146]] | api_endpoint | GET /v1/dev/batch/pending | 5 | false | UNCHANGED |
| [[API-147]] | api_endpoint | GET /v1/portal/uploads/{uldSn}/frames | 2 | false | UNCHANGED |
| [[API-148]] | api_endpoint | GET /v1/dev/dataset-video-meta/shooting-env-correction-targets | 3 | false | UNCHANGED |
| [[API-149]] | api_endpoint | GET /v1/portal/uploads/frames/{uldFrmeSn}/image | 3 | false | UNCHANGED |
| [[API-150]] | api_endpoint | POST /v1/dev/dataset-video-meta/shooting-env-corrections | 3 | false | UNCHANGED |
| [[API-151]] | api_endpoint | DELETE /v1/portal/uploads/{uldSn} | 2 | false | UNCHANGED |
| [[API-152]] | api_endpoint | POST /v1/dev/upload | 11 | true | UNCHANGED |
| [[API-153]] | api_endpoint | POST /v1/dev/tokens | 6 | true | UNCHANGED |
| [[API-154]] | api_endpoint | PUT /v1/portal/uploads/frames/{uldFrmeSn}/labels | 4 | false | UNCHANGED |
| [[API-155]] | api_endpoint | GET /v1/portal/uploads/frames/{uldFrmeSn}/labels | 2 | false | UNCHANGED |
| [[API-156]] | api_endpoint | OPTIONS /v1/uploads | 3 | false | UNCHANGED |
| [[API-157]] | api_endpoint | GET /v1/portal/uploads/{uldSn}/export | 4 | true | UNCHANGED |
| [[API-158]] | api_endpoint | POST /v1/uploads | 8 | true | UNCHANGED |
| [[API-159]] | api_endpoint | GET /v1/portal/uploads/{uldSn}/file | 4 | false | UNCHANGED |
| [[API-160]] | api_endpoint | HEAD /v1/uploads/{uploadId} | 4 | false | UNCHANGED |
| [[API-161]] | api_endpoint | OPTIONS /v1/portal/uploads/tus | 3 | false | UNCHANGED |
| [[API-162]] | api_endpoint | PATCH /v1/uploads/{uploadId} | 6 | false | UNCHANGED |
| [[API-163]] | api_endpoint | POST /v1/portal/uploads/tus | 3 | false | UNCHANGED |
| [[API-164]] | api_endpoint | DELETE /v1/uploads/{uploadId} | 3 | false | UNCHANGED |
| [[API-165]] | api_endpoint | POST /v1/genai/callback | 8 | false | UNCHANGED |
| [[API-166]] | api_endpoint | HEAD /v1/portal/uploads/tus/{uldId} | 3 | false | UNCHANGED |
| [[API-167]] | api_endpoint | POST /v1/videos/{rawSn}/batch/retry | 12 | true | UNCHANGED |
| [[API-168]] | api_endpoint | GET /v1/videos/{rawSn}/environment-meta | 2 | false | UNCHANGED |
| [[API-169]] | api_endpoint | PATCH /v1/portal/uploads/tus/{uldId} | 7 | true | UNCHANGED |
| [[API-170]] | api_endpoint | PUT /v1/videos/{rawSn}/environment-meta | 3 | false | UNCHANGED |
| [[API-171]] | api_endpoint | DELETE /v1/portal/uploads/tus/{uldId} | 4 | true | UNCHANGED |
| [[API-172]] | api_endpoint | GET /v1/frames/{srcSn}/privacy-meta | 4 | false | UNCHANGED |
| [[API-173]] | api_endpoint | PUT /v1/frames/{srcSn}/privacy-meta | 6 | false | UNCHANGED |
| [[API-174]] | api_endpoint | PUT /v1/frames/privacy-meta | 6 | false | UNCHANGED |
| [[API-175]] | api_endpoint | GET /v1/frames/{srcSn}/deid-image | 2 | false | UNCHANGED |
| [[API-176]] | api_endpoint | GET /v1/frames/{srcSn}/label-history | 3 | false | UNCHANGED |
| [[API-177]] | api_endpoint | GET /v1/manage/labels/detect-candidates | 5 | false | UNCHANGED |
| [[API-178]] | api_endpoint | POST /v1/reviews/{videoId}/cancel-submit | 8 | false | UNCHANGED |
| [[API-179]] | api_endpoint | GET /v1/videos/{rawSn}/resolution | 9 | false | UNCHANGED |
| [[API-181]] | api_endpoint | GET /v1/event-types | 7 | false | UNCHANGED |
| [[API-182]] | api_endpoint | GET /v1/versions/{version}/diff-with-working | 4 | false | UNCHANGED |
| [[API-183]] | api_endpoint | GET /v1/videos/{rawSn}/privacy-meta | 1 | false | UNCHANGED |
| [[API-184]] | api_endpoint | PUT /v1/videos/{rawSn}/privacy-meta | 2 | false | UNCHANGED |
| [[API-185]] | api_endpoint | GET /v1/manage/event-types | 8 | false | UNCHANGED |
| [[API-186]] | api_endpoint | PATCH /v1/manage/event-types/{evntTypeCd} | 6 | false | UNCHANGED |
| [[API-187]] | api_endpoint | GET /v1/assignments/event-types | 4 | false | UNCHANGED |
| [[API-188]] | api_endpoint | GET /v1/augments/{id}/progress | 3 | true | UNCHANGED |
| [[API-189]] | api_endpoint | POST /v1/augments/{id}/cancel | 3 | true | UNCHANGED |
| [[API-190]] | api_endpoint | POST /v1/augments/{id}/restore | 7 | true | UNCHANGED |
| [[API-191]] | api_endpoint | POST /v1/control-ingests/{rcptnSn}/requeue | 2 | false | UNCHANGED |
| [[API-192]] | api_endpoint | POST /v1/control-ingests/requeue | 4 | false | UNCHANGED |
| [[API-193]] | api_endpoint | GET /v1/ai-defaults | 6 | false | UNCHANGED |
| [[API-194]] | api_endpoint | POST /v1/manage/admin-session | 12 | true | UNCHANGED |
| [[API-195]] | api_endpoint | GET /v1/videos/{rawSn}/versions/{version}/labels | 7 | false | UNCHANGED |
| [[API-196]] | api_endpoint | PUT /v1/videos/{rawSn}/labels | 9 | false | UNCHANGED |
| [[API-197]] | api_endpoint | GET /v1/videos/{rawSn}/versions | 5 | false | UNCHANGED |
| [[API-198]] | api_endpoint | POST /v1/videos/{rawSn}/batch/stages/{stage}/skip | 7 | false | UNCHANGED |
| [[API-199]] | api_endpoint | POST /v1/videos/batch/retry | 4 | true | UNCHANGED |
| [[API-200]] | api_endpoint | DELETE /v1/videos/{rawSn}/batch/stages/{stage}/skip | 4 | false | UNCHANGED |
| [[API-201]] | api_endpoint | POST /v1/videos/{rawSn}/batch/stages/{stage}/rerun | 9 | true | UNCHANGED |
| [[API-202]] | api_endpoint | GET /v1/deident-reports/{rprtSn}/deident-candidates | 3 | false | UNCHANGED |
| [[API-203]] | api_endpoint | 포털 사용자 작업 데이터 ZIP 다운로드 | 10 | false | UNCHANGED |
| [[API-204]] | api_endpoint | POST /v1/ai-requests/{requestId}/cancel | 2 | true | UNCHANGED |
| [[API-205]] | api_endpoint | 외부 산출물 폴더 검사 | 10 | true | UNCHANGED |
| [[API-206]] | api_endpoint | 외부 산출물 적재 | 15 | true | UNCHANGED |
| [[API-207]] | api_endpoint | 이관 이력 목록 조회 | 7 | false | UNCHANGED |
| [[API-208]] | api_endpoint | 이관 이력 상세 조회 | 5 | false | UNCHANGED |
| [[API-209]] | api_endpoint | 분류 대응 목록 조회 | 7 | false | UNCHANGED |
| [[API-210]] | api_endpoint | 분류 대응 확정 | 11 | false | UNCHANGED |
| [[API-211]] | api_endpoint | 분류 대응 해제 | 8 | false | UNCHANGED |
| [[API-212]] | api_endpoint | POST /v1/videos/batch/stages/{stage}/skip | 6 | true | UNCHANGED |
| [[API-213]] | api_endpoint | DELETE /v1/videos/batch/stages/{stage}/skip | 7 | true | UNCHANGED |
| [[API-214]] | api_endpoint | POST /v1/videos/batch/stages/{stage}/rerun | 5 | true | UNCHANGED |
| [[API-215]] | api_endpoint | 비식별 완료 기록 | 6 | false | UNCHANGED |
| [[API-216]] | api_endpoint | 마킹 산출물 폴더 검사 | 7 | false | UNCHANGED |
| [[API-217]] | api_endpoint | 마킹 산출물 일괄 적재 | 8 | false | UNCHANGED |
| [[API-218]] | api_endpoint | 일괄 적재 진행 조회 | 6 | false | UNCHANGED |
| [[API-219]] | api_endpoint | GET /v1/manage/verification-event-types | 4 | true | UNCHANGED |
| [[API-220]] | api_endpoint | PUT /v1/manage/verification-event-types/{vrfcEvntTypeCd}/questions | 4 | true | UNCHANGED |
| [[API-221]] | api_endpoint | 이관 대상 폴더 탐색 | 19 | true | UNCHANGED |
| [[API-222]] | api_endpoint | 이관 대상 영상 파일 탐색 | 13 | true | UNCHANGED |
| [[API-223]] | api_endpoint | PUT /v1/manage/admin-password — 관리자 패스워드 교체 | 8 | true | UNCHANGED |
| [[API-225]] | api_endpoint | GET /v1/portal/user-works | 9 | false | UNCHANGED |
| [[API-226]] | api_endpoint | GET /v1/manage/ai-servers | 4 | false | UNCHANGED |
| [[API-227]] | api_endpoint | POST /v1/manage/ai-servers | 5 | false | UNCHANGED |
| [[API-228]] | api_endpoint | PATCH /v1/manage/ai-servers/{srvrId} | 4 | false | UNCHANGED |
| [[API-229]] | api_endpoint | PATCH /v1/manage/ai-servers/{srvrId}/status | 5 | false | UNCHANGED |
| [[API-230]] | api_endpoint | DELETE /v1/manage/ai-servers/{srvrId} | 4 | false | UNCHANGED |
| [[API-231]] | api_endpoint | POST /v1/portal/uploads/{uldSn}/augments | 5 | true | UNCHANGED |
| [[API-232]] | api_endpoint | GET /v1/portal/augments | 4 | false | UNCHANGED |
| [[API-233]] | api_endpoint | GET /v1/portal/augments/{augSn} | 5 | false | UNCHANGED |
| [[API-234]] | api_endpoint | 포털 프레임 메타 Load | 5 | false | UNCHANGED |
| [[API-235]] | api_endpoint | 포털 프레임 메타 저장 | 5 | false | UNCHANGED |
| [[API-236]] | api_endpoint | 포털 이벤트 어노테이션 Load | 3 | false | UNCHANGED |
| [[API-237]] | api_endpoint | 포털 이벤트 어노테이션 저장 | 4 | false | UNCHANGED |
| [[API-238]] | api_endpoint | GET /v1/portal/uploads/{uldSn}/stream | 3 | false | UNCHANGED |
| [[API-239]] | api_endpoint | GET /v1/portal/uploads/{uldSn}/stream-url | 4 | false | UNCHANGED |
| [[API-240]] | api_endpoint | POST /v1/portal/uploads/{uldSn}/markings | 4 | true | UNCHANGED |
| [[API-241]] | api_endpoint | GET /v1/portal/uploads/{uldSn}/markings | 1 | true | UNCHANGED |
| [[API-245]] | api_endpoint | GET /v1/auth/role-claim/availability — 관리자 부트스트랩 창구 개폐 조회 | 2 | false | UNCHANGED |
| [[API-246]] | api_endpoint | DELETE /v1/auth/control-session — 관제 세션 로그아웃 중계 | 4 | true | UNCHANGED |
| [[API-247]] | api_endpoint | POST /v1/auth/control-tokens — 관제 세션 갱신 중계 | 5 | true | UNCHANGED |
| [[API-248]] | api_endpoint | 포털 데이터셋 소재 조달 착수 | 3 | true | UNCHANGED |
| [[API-249]] | api_endpoint | 포털 데이터셋 소재 조달 상태 조회 | 3 | true | UNCHANGED |
| [[API-250]] | api_endpoint | POST /v1/reviews/batch/approve | 4 | true | UNCHANGED |
| [[API-253]] | api_endpoint | 포털 데이터셋 영상 목록 조회 | 5 | false | UNCHANGED |
| [[API-254]] | api_endpoint | POST /v1/portal/frames/{srcSn}/yolo-track | 5 | false | UNCHANGED |
| [[API-255]] | api_endpoint | POST /v1/portal/frames/{srcSn}/autolabel | 4 | false | UNCHANGED |
| [[API-256]] | api_endpoint | GET /v1/portal/ai-defaults | 2 | true | UNCHANGED |
| [[API-257]] | api_endpoint | POST /v1/portal/frames/{srcSn}/sam2-segment | 4 | false | UNCHANGED |
| [[API-258]] | api_endpoint | POST /v1/portal/ai-requests/{requestId}/cancel | 3 | true | UNCHANGED |
| [[API-259]] | api_endpoint | DELETE /v1/assignments/{assignmentId} | 2 | true | UNCHANGED |
| [[API-260]] | api_endpoint | POST /v1/videos/{rawSn}/exclusion | 4 | true | UNCHANGED |
| [[API-261]] | api_endpoint | DELETE /v1/videos/{rawSn}/exclusion | 3 | true | UNCHANGED |
| [[API-262]] | api_endpoint | 포털 데이터셋 영상 등록 재착수 | 3 | true | UNCHANGED |
| [[CONST-001]] | constant | COCO-17 키포인트 스켈레톤 상수 | 4 | false | UNCHANGED |
| [[CONST-002]] | constant | CocoClasses — COCO-80 검출 클래스 allowlist | 3 | true | UNCHANGED |
| [[DS-001]] | design_system | KRDS Public | 9 | false | UNCHANGED |
| [[DS-002]] | design_system | KLID 포털 플랫폼 (포털 채널) | 1 | false | UNCHANGED |
| [[NAV-001]] | navigation_tree | 저작도구 내부 메뉴 (INTERNAL) | 26 | true | UNCHANGED |
| [[NAV-002]] | navigation_tree | 포털 메뉴 (PORTAL) | 19 | true | UNCHANGED |
| [[ROLE-001]] | permission_role | 검수자 (REVIEWER) | 16 | true | UNCHANGED |
| [[ROLE-002]] | permission_role | 라벨링 작업자 (WORKER) | 10 | true | UNCHANGED |
| [[ROLE-003]] | permission_role | 포털 회원 (PORTAL_USER) | 17 | false | UNCHANGED |
| [[ROLE-004]] | permission_role | 관리자 (ADMIN) | 6 | false | UNCHANGED |
| [[SCREEN-001]] | screen_spec | 세션 인계 진입 화면 | 23 | false | UNCHANGED |
| [[SCREEN-002]] | screen_spec | 관리자 등록 화면 | 34 | true | UNCHANGED |
| [[SCREEN-003]] | screen_spec | 접근 거부 화면 | 16 | false | UNCHANGED |
| [[SCREEN-004]] | screen_spec | 개발용 로그인 화면 | 18 | false | UNCHANGED |
| [[SCREEN-005]] | screen_spec | 라벨링 캔버스 화면 | 118 | true | UNCHANGED |
| [[SCREEN-006]] | screen_spec | 마킹 화면 | 56 | true | UNCHANGED |
| [[SCREEN-008]] | screen_spec | 영상 처리 현황 화면 | 55 | true | UNCHANGED |
| [[SCREEN-009]] | screen_spec | 영상 상세 화면 | 80 | true | UNCHANGED |
| [[SCREEN-010]] | screen_spec | 로드 버전 선택 | 37 | true | UNCHANGED |
| [[SCREEN-011]] | screen_spec | 대시보드 화면 | 21 | true | UNCHANGED |
| [[SCREEN-012]] | screen_spec | 작업 목록 화면 | 54 | false | UNCHANGED |
| [[SCREEN-018]] | screen_spec | 검수 목록 화면 | 38 | true | UNCHANGED |
| [[SCREEN-019]] | screen_spec | 검수 상세 화면 | 56 | true | UNCHANGED |
| [[SCREEN-020]] | screen_spec | 작업자 통계 화면 | 32 | true | UNCHANGED |
| [[SCREEN-021]] | screen_spec | 전체 구축 현황 화면 | 29 | true | UNCHANGED |
| [[SCREEN-022]] | screen_spec | 증강 요청 화면 | 51 | false | UNCHANGED |
| [[SCREEN-023]] | screen_spec | 증강 결과 화면 | 47 | false | UNCHANGED |
| [[SCREEN-024]] | screen_spec | 사용자 관리 화면 | 39 | false | UNCHANGED |
| [[SCREEN-025]] | screen_spec | 시스템 설정 화면 | 49 | true | UNCHANGED |
| [[SCREEN-026]] | screen_spec | 프리셋 관리 화면 | 43 | false | UNCHANGED |
| [[SCREEN-027]] | screen_spec | 파일 업로드 | 54 | false | UNCHANGED |
| [[SCREEN-028]] | screen_spec | 포털 내 작업 화면 | 44 | true | UNCHANGED |
| [[SCREEN-029]] | screen_spec | 포털 라벨링 화면 | 58 | true | UNCHANGED |
| [[SCREEN-030]] | screen_spec | 공지 목록 화면 | 26 | true | UNCHANGED |
| [[SCREEN-031]] | screen_spec | 공지 상세 화면 | 33 | true | UNCHANGED |
| [[SCREEN-032]] | screen_spec | 비식별 신고 관리 화면 | 31 | true | UNCHANGED |
| [[SCREEN-033]] | screen_spec | 포털 업로드 화면 | 46 | true | UNCHANGED |
| [[SCREEN-035]] | screen_spec | 라벨 관리 화면 | 19 | true | UNCHANGED |
| [[SCREEN-036]] | screen_spec | 공지 작성 화면 | 8 | true | UNCHANGED |
| [[SCREEN-037]] | screen_spec | 공지 수정 화면 | 8 | true | UNCHANGED |
| [[SCREEN-038]] | screen_spec | 이벤트유형 관리 화면 | 20 | false | UNCHANGED |
| [[SCREEN-039]] | screen_spec | 산출물 가져오기 | 45 | true | UNCHANGED |
| [[SCREEN-040]] | screen_spec | 관리자 페이지 진입 화면 | 9 | false | UNCHANGED |
| [[SCREEN-041]] | screen_spec | 관리자 패스워드 교체 | 10 | false | UNCHANGED |
| [[SCREEN-042]] | screen_spec | 연동 서버 주소 관리 화면 | 27 | false | UNCHANGED |
| [[SCREEN-043]] | screen_spec | 위험 작업 화면 | 7 | false | UNCHANGED |
| [[SCREEN-044]] | screen_spec | 포털 증강 화면 | 15 | true | UNCHANGED |
| [[SCREEN-045]] | screen_spec | 포털 업로드 영상 마킹 화면 | 8 | false | UNCHANGED |
| [[SCREEN-046]] | screen_spec | 포털 데이터셋 소재 조달 화면 | 5 | true | UNCHANGED |
| [[SD-001]] | screen_design | SCREEN-018 검수 목록 화면 | 5 | true | UNCHANGED |
| [[SD-002]] | screen_design | SCREEN-005 라벨링 캔버스 화면 | 22 | true | UNCHANGED |
| [[SD-003]] | screen_design | SCREEN-012 작업 목록 화면 | 8 | true | UNCHANGED |
| [[SD-004]] | screen_design | SCREEN-009 영상 상세 화면 | 20 | true | UNCHANGED |
| [[SD-005]] | screen_design | SCREEN-019 검수 상세 화면 | 11 | true | UNCHANGED |
| [[SD-006]] | screen_design | SCREEN-026 프리셋 관리 화면 | 9 | true | UNCHANGED |
| [[SD-007]] | screen_design | SCREEN-030 공지 목록 화면 | 6 | true | UNCHANGED |
| [[SD-008]] | screen_design | SCREEN-036 공지 작성 화면 | 4 | true | UNCHANGED |
| [[SD-009]] | screen_design | SCREEN-024 사용자 관리 화면 | 13 | false | UNCHANGED |
| [[SD-010]] | screen_design | SCREEN-031 공지 상세 화면 | 10 | true | UNCHANGED |
| [[SD-011]] | screen_design | SCREEN-037 공지 수정 화면 | 6 | false | UNCHANGED |
| [[SD-012]] | screen_design | SCREEN-006 마킹 화면 | 11 | true | UNCHANGED |
| [[SD-013]] | screen_design | SCREEN-008 영상 처리 현황 화면 | 8 | true | UNCHANGED |
| [[SD-014]] | screen_design | SCREEN-011 대시보드 화면 | 5 | false | UNCHANGED |
| [[SD-015]] | screen_design | SCREEN-025 시스템 설정 화면 | 9 | true | UNCHANGED |
| [[SD-016]] | screen_design | 공통 — 전역 레이아웃(헤더·좌측 주 메뉴) | 6 | false | UNCHANGED |
| [[SD-017]] | screen_design | SCREEN-001 세션 인계 진입 화면 | 6 | true | UNCHANGED |
| [[SD-018]] | screen_design | SCREEN-002 관리자 등록 화면 | 8 | false | UNCHANGED |
| [[SD-019]] | screen_design | SCREEN-003 접근 거부 화면 | 3 | true | UNCHANGED |
| [[SD-020]] | screen_design | SCREEN-004 개발용 로그인 화면 | 5 | true | UNCHANGED |
| [[SD-021]] | screen_design | SCREEN-032 비식별 신고 관리 화면 | 9 | true | UNCHANGED |
| [[SD-022]] | screen_design | SCREEN-035 라벨 관리 화면 | 4 | true | UNCHANGED |
| [[SD-023]] | screen_design | SCREEN-038 이벤트유형 관리 화면 | 5 | true | UNCHANGED |
| [[SD-024]] | screen_design | SCREEN-028 포털 내 작업 화면 | 12 | true | UNCHANGED |
| [[SD-025]] | screen_design | SCREEN-029 포털 라벨링 화면 | 6 | true | UNCHANGED |
| [[SD-026]] | screen_design | SCREEN-033 포털 업로드 화면 | 11 | true | UNCHANGED |
| [[SD-027]] | screen_design | SCREEN-034 포털 업로드 라벨링 화면 | 11 | true | UNCHANGED |
| [[SD-028]] | screen_design | SCREEN-022 증강 요청 화면 | 9 | false | UNCHANGED |
| [[SD-029]] | screen_design | SCREEN-023 증강 결과 화면 | 7 | true | UNCHANGED |
| [[SD-030]] | screen_design | SCREEN-020 작업자 통계 화면 | 5 | false | UNCHANGED |
| [[SD-031]] | screen_design | SCREEN-021 전체 구축 현황 화면 | 3 | true | UNCHANGED |
| [[SD-032]] | screen_design | SCREEN-010 로드 버전 선택 | 2 | true | UNCHANGED |
| [[SD-033]] | screen_design | SCREEN-027 파일 업로드 | 14 | false | UNCHANGED |
| [[SD-034]] | screen_design | SCREEN-040 관리자 페이지 진입 화면 | 6 | false | UNCHANGED |
| [[SD-035]] | screen_design | SCREEN-043 위험 작업 화면 | 4 | true | UNCHANGED |
| [[SD-036]] | screen_design | SCREEN-042 연동 서버 주소 관리 화면 | 16 | false | UNCHANGED |
| [[SD-037]] | screen_design | SCREEN-041 관리자 패스워드 교체 화면 | 5 | true | UNCHANGED |
| [[SD-038]] | screen_design | SCREEN-039 산출물 가져오기 | 3 | true | UNCHANGED |
| [[SHELL-001]] | app_shell | 저작도구 내부 채널 셸 | 19 | true | UNCHANGED |
| [[SHELL-002]] | app_shell | 포털 채널 셸 | 9 | true | UNCHANGED |
| [[UC-001]] | use_case | 증강 영상 생성 요청 | 19 | false | UNCHANGED |
| [[UC-002]] | use_case | 증강 결과 수신·등록 | 22 | false | UNCHANGED |
| [[UC-003]] | use_case | 해상도 변경 수행 | 17 | true | UNCHANGED |
| [[UC-004]] | use_case | 객체 자동 추적 | 19 | false | UNCHANGED |
| [[UC-005]] | use_case | 객체 외곽 경계 자동 밀착 | 14 | false | UNCHANGED |
| [[UC-006]] | use_case | 라벨링 정밀도 조절 | 12 | false | UNCHANGED |
| [[UC-007]] | use_case | 라벨 버전 저장·이력 추적 | 16 | false | UNCHANGED |
| [[UC-008]] | use_case | 버전 비교·복구 | 18 | false | UNCHANGED |
| [[UC-009]] | use_case | 검수 완료·수정 통지 | 26 | false | UNCHANGED |
| [[UC-010]] | use_case | 증강 영상 활용 여부 검수 | 18 | false | UNCHANGED |
| [[UC-011]] | use_case | 비식별 처리 요청 | 27 | true | UNCHANGED |
| [[UC-013]] | use_case | 비식별 옵션 설정 | 13 | true | UNCHANGED |
| [[UC-016]] | use_case | 비식별 처리 상태·이력 확인 | 35 | false | UNCHANGED |
| [[UC-018]] | use_case | 영상 적재 (관제 인입 테이블 직접 INSERT → 폴링 적재) | 28 | true | UNCHANGED |
| [[UC-019]] | use_case | 이벤트 마킹 (자동/수동) | 34 | false | UNCHANGED |
| [[UC-021]] | use_case | 라벨 편집·임시저장 | 25 | false | UNCHANGED |
| [[UC-022]] | use_case | VLM 시계열 메타 검토 | 31 | true | UNCHANGED |
| [[UC-023]] | use_case | 검수 승인·반려 | 37 | true | UNCHANGED |
| [[UC-024]] | use_case | 포털 라벨 작업 (조회·수정·다운로드) | 38 | false | UNCHANGED |
| [[UC-027]] | use_case | 포털 자산 업로드·수동 라벨링 | 37 | true | UNCHANGED |
| [[UC-028]] | use_case | 라벨 클래스·속성 정의 관리 | 9 | false | UNCHANGED |
| [[UC-029]] | use_case | 작업 목록 조회·필터링·배정 | 18 | false | UNCHANGED |
| [[UC-030]] | use_case | 사용자 계정·역할 관리 | 21 | false | UNCHANGED |
| [[UC-031]] | use_case | 시스템 운영 설정 관리 | 21 | true | UNCHANGED |
| [[UC-032]] | use_case | 라벨 프리셋 CRUD 관리 | 18 | true | UNCHANGED |
| [[UC-033]] | use_case | 전체 구축 현황 조회 — 검수완료 기준과 전체 기준 병기 | 8 | false | UNCHANGED |
| [[UC-034]] | use_case | 온디맨드 AI 자동 추적 | 9 | false | UNCHANGED |
| [[UC-035]] | use_case | 외부 산출물 가져오기 | 20 | true | UNCHANGED |
| [[UC-036]] | use_case | 이관 이력 조회 | 9 | true | UNCHANGED |
| [[UC-037]] | use_case | 마킹이 끝난 영상 일괄 올리기 | 16 | false | UNCHANGED |
| [[UC-038]] | use_case | 배치 자동 처리 파이프라인 | 9 | false | UNCHANGED |
| [[UC-039]] | use_case | 온라인 AI 객체 탐지 | 5 | true | UNCHANGED |
| [[UC-040]] | use_case | 공지·가이드라인 관리와 열람 | 5 | true | UNCHANGED |
| [[UC-041]] | use_case | 인증 인계와 채널·역할 인가 | 29 | true | UNCHANGED |
| [[UC-042]] | use_case | 파일 업로드로 영상 한 건 투입 | 6 | false | UNCHANGED |
| [[UC-043]] | use_case | 잘못 들어온 영상을 화면 목록에서 제외하고 되돌리기 | 4 | true | UNCHANGED |
| [[UC-044]] | use_case | 이벤트유형 표시명·수집여부 정정과 검증 이벤트 유형 질문 관리 | 4 | true | UNCHANGED |
| [[UI-001]] | ui_component | action: Button | 3 | false | UNCHANGED |
| [[UI-002]] | ui_component | input: Input | 6 | false | UNCHANGED |
| [[UI-003]] | ui_component | input: Select | 5 | false | UNCHANGED |
| [[UI-004]] | ui_component | overlay: Modal | 4 | false | UNCHANGED |
| [[UI-005]] | ui_component | overlay: ConfirmDialog | 4 | false | UNCHANGED |
| [[UI-006]] | ui_component | overlay: Drawer | 4 | false | UNCHANGED |
| [[UI-007]] | ui_component | data: DataTable | 7 | false | UNCHANGED |
| [[UI-008]] | ui_component | navigation: Pagination | 6 | false | UNCHANGED |
| [[UI-009]] | ui_component | navigation: Tabs | 3 | false | UNCHANGED |
| [[UI-010]] | ui_component | display: KpiCard | 4 | false | UNCHANGED |
| [[UI-011]] | ui_component | layout: Card | 4 | false | UNCHANGED |
| [[UI-012]] | ui_component | layout: PageHeader | 3 | false | UNCHANGED |
| [[UI-013]] | ui_component | navigation: Breadcrumb | 3 | false | UNCHANGED |
| [[UI-014]] | ui_component | display: StatusBadge | 5 | false | UNCHANGED |
| [[UI-015]] | ui_component | [폐기] display: PrivacyBadge | 4 | false | UNCHANGED |
| [[UI-016]] | ui_component | display: EventTypeBadge | 5 | false | UNCHANGED |
| [[UI-017]] | ui_component | display: StageBadge | 8 | false | UNCHANGED |
| [[UI-018]] | ui_component | display: BatchStageIndicator | 10 | false | UNCHANGED |
| [[UI-019]] | ui_component | feedback: ProgressBar | 4 | false | UNCHANGED |
| [[UI-020]] | ui_component | feedback: EmptyState | 5 | false | UNCHANGED |
| [[UI-021]] | ui_component | feedback: ErrorState | 4 | false | UNCHANGED |
| [[UI-022]] | ui_component | feedback: LoadingOverlay | 5 | false | UNCHANGED |
| [[UI-023]] | ui_component | feedback: Toast | 3 | false | UNCHANGED |
| [[UI-024]] | ui_component | input: Checkbox | 6 | false | UNCHANGED |
| [[UI-025]] | ui_component | input: Radio | 3 | false | UNCHANGED |
| [[UI-026]] | ui_component | input: RadioGroup | 6 | false | UNCHANGED |
| [[UI-027]] | ui_component | input: Textarea | 4 | false | UNCHANGED |
| [[UI-028]] | ui_component | input: DatePicker | 4 | false | UNCHANGED |
| [[UI-029]] | ui_component | input: DateRangePicker | 5 | false | UNCHANGED |
| [[UI-030]] | ui_component | [폐기] input: FormField | 6 | false | UNCHANGED |
| [[UI-031]] | ui_component | overlay: Popover | 3 | false | UNCHANGED |
| [[UI-032]] | ui_component | feedback: Spinner | 3 | false | UNCHANGED |
| [[UI-033]] | ui_component | feedback: Skeleton | 3 | false | UNCHANGED |
| [[UI-034]] | ui_component | layout: AppLayout | 4 | false | UNCHANGED |
| [[UI-035]] | ui_component | navigation: Gnb | 8 | false | UNCHANGED |
| [[UI-036]] | ui_component | navigation: Lnb | 3 | false | UNCHANGED |
| [[UI-037]] | ui_component | layout: PortalLayout | 6 | false | UNCHANGED |
| [[UI-038]] | ui_component | layout: Footer | 3 | false | UNCHANGED |
| [[UI-039]] | ui_component | data: SimplePieChart | 3 | false | UNCHANGED |
| [[UI-040]] | ui_component | data: SimpleBarChart | 6 | false | UNCHANGED |
| [[UI-041]] | ui_component | display: AuthImage | 3 | false | UNCHANGED |
| [[UI-042]] | ui_component | display: VideoPlayer | 5 | false | UNCHANGED |
| [[UI-043]] | ui_component | action: MarkingToolbar | 5 | false | UNCHANGED |
| [[UI-044]] | ui_component | display: MarkingTimeline | 4 | false | UNCHANGED |
| [[UI-045]] | ui_component | data: MarkingPanel | 5 | false | UNCHANGED |
| [[UI-046]] | ui_component | display: CanvasShell | 8 | false | UNCHANGED |
| [[UI-047]] | ui_component | action: ToolBar | 6 | false | UNCHANGED |
| [[UI-048]] | ui_component | overlay: LabelPickerModal | 7 | false | UNCHANGED |
| [[UI-049]] | ui_component | data: ObjectClassTree | 7 | false | UNCHANGED |
| [[UI-050]] | ui_component | input: ObjectAttributePanel | 6 | false | UNCHANGED |
| [[UI-051]] | ui_component | navigation: FrameFilmstrip | 4 | false | UNCHANGED |
| [[UI-052]] | ui_component | navigation: FrameNavigator | 7 | false | UNCHANGED |
| [[UI-053]] | ui_component | action: SaveCommitButton | 8 | false | UNCHANGED |
| [[UI-054]] | ui_component | action: UndoRedoToolbar | 5 | false | UNCHANGED |
| [[UI-055]] | ui_component | layout: LabelHeader | 11 | false | UNCHANGED |
| [[UI-056]] | ui_component | display: TimeseriesSidePanel | 8 | false | UNCHANGED |
| [[UI-057]] | ui_component | action: DeidentReportButton | 6 | false | UNCHANGED |
| [[UI-058]] | ui_component | display: ReviewLabelCanvas | 4 | false | UNCHANGED |
| [[UI-059]] | ui_component | [폐기] action: ReviewActionBar | 5 | false | UNCHANGED |
| [[UI-060]] | ui_component | layout: ReviewHeader | 5 | false | UNCHANGED |
| [[UI-061]] | ui_component | [폐기] data: IssueSidebar | 5 | false | UNCHANGED |
| [[UI-062]] | ui_component | overlay: RejectModal | 4 | false | UNCHANGED |
| [[UI-063]] | ui_component | navigation: ReviewFrameTimeline | 4 | false | UNCHANGED |
| [[UI-064]] | ui_component | [폐기] data: ObjectListPanel | 5 | false | UNCHANGED |
| [[UI-065]] | ui_component | input: ReviewMemoPanel | 4 | false | UNCHANGED |
| [[UI-066]] | ui_component | data: VersionList | 5 | false | UNCHANGED |
| [[UI-067]] | ui_component | display: DiffViewer | 4 | false | UNCHANGED |
| [[UI-068]] | ui_component | [폐기] input: VersionPicker | 6 | false | UNCHANGED |
| [[UI-069]] | ui_component | overlay: RollbackConfirmModal | 4 | false | UNCHANGED |
| [[UI-070]] | ui_component | layout: HistoryPanel | 4 | false | UNCHANGED |
| [[UI-071]] | ui_component | input: ProcessKindCard | 5 | false | UNCHANGED |
| [[UI-072]] | ui_component | display: JobCard | 5 | false | UNCHANGED |
| [[UI-073]] | ui_component | action: DecisionCard | 4 | false | UNCHANGED |
| [[UI-074]] | ui_component | [폐기] input: TimeseriesSidePanel | 5 | false | UNCHANGED |
| [[UI-075]] | ui_component | [폐기] display: StateChangeTimeline | 5 | false | UNCHANGED |
| [[UI-076]] | ui_component | display: ConfidenceDistributionChart | 4 | false | UNCHANGED |
| [[UI-077]] | ui_component | data: MyTasksTable | 4 | false | UNCHANGED |
| [[UI-078]] | ui_component | display: EventDistributionGrid | 4 | false | UNCHANGED |
| [[UI-079]] | ui_component | display: NoticeCard | 4 | false | UNCHANGED |
| [[UI-080]] | ui_component | data: WorkerStatsTable | 5 | false | UNCHANGED |
| [[UI-081]] | ui_component | data: DailyCompletionChart | 4 | false | UNCHANGED |
| [[UI-082]] | ui_component | data: EventTypePieChart | 4 | false | UNCHANGED |
| [[UI-083]] | ui_component | overlay: AssignModal | 6 | false | UNCHANGED |
| [[UI-084]] | ui_component | overlay: HistoryDrawer | 6 | false | UNCHANGED |
| [[UI-085]] | ui_component | input: TaskFilters | 4 | false | UNCHANGED |
| [[UI-086]] | ui_component | input: YoloConfigCard | 4 | false | UNCHANGED |
| [[UI-087]] | ui_component | input: BatchConfigCard | 5 | false | UNCHANGED |
| [[UI-088]] | ui_component | input: PrecisionConfigCard | 4 | false | UNCHANGED |
| [[UI-089]] | ui_component | display: HealthStatusList | 5 | false | UNCHANGED |
| [[UI-090]] | ui_component | action: DangerActions | 6 | false | UNCHANGED |
| [[UI-091]] | ui_component | overlay: PresetEditModal | 6 | false | UNCHANGED |
| [[UI-092]] | ui_component | display: PresetCodeChip | 4 | false | UNCHANGED |
| [[UI-093]] | ui_component | [폐기] display: BatchStageSteps | 6 | false | UNCHANGED |
| [[UI-094]] | ui_component | action: VideoActions | 5 | false | UNCHANGED |
| [[UI-095]] | ui_component | input: VideoFilters | 5 | false | UNCHANGED |
| [[UI-096]] | ui_component | input: AugmentTypeCheckbox | 6 | false | UNCHANGED |
| [[UI-097]] | ui_component | data: IssueThreadPanel (이슈 스레드 패널) | 6 | false | UNCHANGED |
| [[UI-098]] | ui_component | input: FileInput | 2 | false | UNCHANGED |
| [[UI-099]] | ui_component | input: Field | 2 | false | UNCHANGED |
| [[UI-100]] | ui_component | input: DeidentConfigCard | 2 | false | UNCHANGED |
| [[UI-101]] | ui_component | display: RecheckBadge | 1 | false | UNCHANGED |
| [[UI-102]] | ui_component | display: ReadOnlyBadge | 1 | false | UNCHANGED |
| [[UI-103]] | ui_component | feedback: AlertBanner | 1 | false | UNCHANGED |
| [[UI-104]] | ui_component | display: CountChip | 1 | false | UNCHANGED |
| [[UI-105]] | ui_component | display: DerivativeBadge | 3 | false | UNCHANGED |
| [[UI-106]] | ui_component | data: KeyValueGrid | 1 | false | UNCHANGED |
| [[UI-107]] | ui_component | input: EventAnnotationPanel | 7 | false | UNCHANGED |
| [[UI-108]] | ui_component | input: PrivacyMetaPanel | 3 | false | UNCHANGED |
| [[UI-109]] | ui_component | display: Avatar | 1 | false | UNCHANGED |
| [[UI-110]] | ui_component | display: RoleBadge | 2 | false | UNCHANGED |
| [[UI-111]] | ui_component | display: Badge | 3 | false | UNCHANGED |
| [[UI-112]] | ui_component | display: AttachmentList | 3 | false | UNCHANGED |
| [[UI-113]] | ui_component | display: PresetLabelOverflowChip | 1 | false | UNCHANGED |
| [[UI-114]] | ui_component | input: PresetLabelPicker | 1 | false | UNCHANGED |
| [[UI-115]] | ui_component | display: FieldCounter | 1 | false | UNCHANGED |
| [[UI-116]] | ui_component | display: LockIconBadge | 1 | false | UNCHANGED |
| [[UI-117]] | ui_component | feedback: DevOnlyNotice | 1 | false | UNCHANGED |
| [[UI-118]] | ui_component | display: ChannelChip | 1 | false | UNCHANGED |
| [[UI-119]] | ui_component | action: IconButton | 1 | false | UNCHANGED |
| [[UI-120]] | ui_component | display: Tooltip | 1 | false | UNCHANGED |
| [[UI-121]] | ui_component | display: DeidentStageBadge | 1 | false | UNCHANGED |
| [[UI-122]] | ui_component | data: DeidentArtifactCandidateList | 1 | false | UNCHANGED |
| [[UI-123]] | ui_component | input: ToggleSwitch | 1 | false | UNCHANGED |
| [[UI-124]] | ui_component | input: ColorSwatchField | 1 | false | UNCHANGED |
| [[UI-125]] | ui_component | input: DynamicList | 1 | false | UNCHANGED |
| [[UI-126]] | ui_component | display: DisplayNameSourceChip | 1 | false | UNCHANGED |
| [[UI-127]] | ui_component | display: DetectClassMapChip | 1 | false | UNCHANGED |
| [[UI-128]] | ui_component | data: DatamartVideoCard | 1 | false | UNCHANGED |
| [[UI-129]] | ui_component | layout: PortalHero | 4 | false | UNCHANGED |
| [[UI-130]] | ui_component | display: LabelOriginChip | 1 | false | UNCHANGED |
| [[UI-131]] | ui_component | input: UploadDropzone | 2 | false | UNCHANGED |
| [[UI-132]] | ui_component | display: AssetTypeChip | 2 | false | UNCHANGED |
| [[UI-133]] | ui_component | input: TargetResolutionSelect | 2 | false | UNCHANGED |
| [[UI-134]] | ui_component | display: SelectionSummary | 2 | false | UNCHANGED |
| [[UI-135]] | ui_component | layout: StickyActionBar | 1 | false | UNCHANGED |
| [[UI-136]] | ui_component | layout: StepSectionHeader | 1 | false | UNCHANGED |
| [[UI-137]] | ui_component | feedback: InlineResultSummary | 1 | false | UNCHANGED |
| [[UI-138]] | ui_component | data: FramePairGrid | 2 | false | UNCHANGED |
| [[UI-139]] | ui_component | overlay: SideBySideCompare | 2 | false | UNCHANGED |
| [[UI-140]] | ui_component | display: AugmentPromptSummary | 2 | false | UNCHANGED |
| [[UI-141]] | ui_component | feedback: AugmentProgressPanel | 1 | false | UNCHANGED |
| [[UI-142]] | ui_component | display: WorkerNameSub | 1 | false | UNCHANGED |
| [[UI-143]] | ui_component | display: RateGaugeCard | 1 | false | UNCHANGED |
| [[UI-144]] | ui_component | display: ProcessingStackBar | 1 | false | UNCHANGED |
| [[UI-145]] | ui_component | overlay: PathPicker | 1 | false | UNCHANGED |
| [[UI-146]] | ui_component | layout: PortalCard | 1 | false | UNCHANGED |
| [[UI-147]] | ui_component | feedback: PortalAlert | 1 | false | UNCHANGED |
| [[UI-148]] | ui_component | feedback: PortalProgress | 1 | false | UNCHANGED |
| [[UI-149]] | ui_component | display: PortalUploadStatusBadge | 1 | false | UNCHANGED |
| [[UI-150]] | ui_component | display: PortalBadge | 1 | false | UNCHANGED |
| [[UI-151]] | ui_component | feedback: PortalEmptyState | 1 | false | UNCHANGED |
| [[UI-152]] | ui_component | layout: PortalSectionHead | 1 | false | UNCHANGED |
| [[UI-153]] | ui_component | feedback: PortalListSkeleton | 1 | false | UNCHANGED |
| [[UI-154]] | ui_component | display: PortalRecordRow | 1 | false | UNCHANGED |
| [[UI-155]] | ui_component | display: PortalFactChip | 1 | false | UNCHANGED |
| [[UI-156]] | ui_component | overlay: FloatingWindow | 2 | false | UNCHANGED |
| [[UI-157]] | ui_component | display: TimeseriesAnnotationSummaryCard | 3 | false | UNCHANGED |
| [[UI-158]] | ui_component | action: AnnotationWindowStrip | 3 | false | UNCHANGED |
