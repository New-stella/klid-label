# D11-R1 단위시험 절차서 (기능 정상동작 한정 · 컴포넌트 기준 · UI 시험절차)

## 작성 목적
> 본 절차서는 `D11-R1-단위시험절차서.md`(전체)에서 **예외·경계·권한·검증 케이스를 제외하고 각 기능의 정상 동작 케이스만** 추린 기능 검증용 변형본이다. 혼합 케이스는 정상 절만 추출했다(원문 verbatim 아님). 활성 R1 기능 유스케이스 18종은 모두 정상 동작 케이스를 1건 이상 보유한다. 본 판본은 단위시험을 **컴포넌트(CO) 단위 블록**으로 재편하고, 케이스 ID를 `KLID-AT-UT-{컴포넌트번호}-NN`로 부여한 컴포넌트 기준본이다. 본 상세본은 시험항목 및 처리절차·예상결과 및 검증방법을 실제 코드·DB 설계(D8·D9) 기준으로 다단계 절차/테이블·컬럼 검증 수준까지 상세화한 판본이다. 본 판본은 시험항목 및 처리절차를 실제 UI/기능 시험 절차(번호 단계 + ':' 상세 설명)로 기술한다(UI 근거: D2 사용자인터페이스설계서). 화면이 없는 배치·내부·콜백 케이스는 기능 트리거 절차로 표기하며, 예상결과 및 검증방법은 코드·D8/D9 기반 검증 상세를 유지한다.

### 제·개정 이력

| 날짜 | 버전 | 제·개정 내역 | 작성자 | 검토자 |
|------|------|------------|--------|--------|
| 2026-06-30 | 1.0 | 전체 단위시험 절차서에서 기능 정상동작만 추출 | - | - |
| 2026-07-01 | 1.1 | CBD 표준 단위시험 케이스 구조로 블록 머리표(단위시험ID·설명·관련 컴포넌트/프로그램 ID) 추가, 케이스표 헤더 표준화 | - | - |
| 2026-07-01 | 2.0 | 단위시험을 컴포넌트(CO) 단위로 재편, 단위시험ID=KLID-AT-UT-{컴포넌트번호}, 케이스ID를 블록별 01부터 순번 재부여(원본 D11 TC 번호 제거), 다중 CO 블록 분리 | - | - |
| 2026-07-01 | 3.0 | 시험항목 및 처리절차·예상결과 및 검증방법을 코드·D8/D9 기반 다단계 절차·테이블/컬럼/상태 검증 수준으로 상세화 | - | - |
| 2026-07-01 | 4.0 | 시험항목 및 처리절차를 실제 UI/기능 시험 절차(번호 단계+':' 상세, D2 근거)로 재작성, UI 없는 배치/내부 케이스는 기능 트리거 절차로 표기 | - | - |
| 2026-07-01 | 4.1 | 시험항목 및 처리절차에서 불필요한 API 엔드포인트 제거, 예상결과 및 검증방법의 DB 항목을 INSERT 서술 → 확인 SELECT 쿼리로 전환(D8/D9 실측 키) | - | - |
| 2026-07-01 | 4.2 | 관련 프로그램 ID를 D3 컴포넌트설계서 인터페이스 ID(KLID-AT-IF-NNN) 기준으로 기입, 외부 연계 컴포넌트는 D4 송수신 프로그램 ID 병기 | - | - |

### 헤더

| D11-R1 | 단위시험 절차서 (R1 한정) |
|-------|---------|
| 시스템명 | AI 기반 지방정부 CCTV 관제지원시스템 구축(2차) | 서브시스템명 | 학습데이터 저작도구 (AT) |
| 단계명 | 설계 | 작성일자 | 2026-06-30 | 버전 | 1.0 |

> 각 블록은 컴포넌트(CO) 1:1 단위시험으로, [단위시험ID(=관련 컴포넌트 번호) · 설명 · 관련 컴포넌트 ID · 관련 프로그램 ID] 머리표로 시작한다(설명 칸에 R1 요구사항 ID·UC 보존, 관련 프로그램 ID 칸에 클래스명). 케이스 ID는 `KLID-AT-UT-{컴포넌트번호}-NN`(블록마다 01부터 순번). 케이스표 컬럼: 케이스 ID · 케이스 명 · 작업 권한 · 시험 데이터 · 시험항목 및 처리절차 · 예상결과 및 검증방법 · 시험 결과(공란).

---

## 단위시험 KLID-AT-UT-001

| 단위시험ID | KLID-AT-UT-001 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-07-01 / RQ-SFR-11-05 · UC KLID-AT-UC-001] 생성형 AI 학습데이터 자동생성(증강 요청) | | |
| 관련 컴포넌트 ID | KLID-AT-CO-001 | 관련 프로그램 ID | KLID-AT-IF-001 증강 요청·IF-002 외부 증강 연동 (D4 송신 프로그램: 증강위탁, II-005) |
> 출처: D11 UT-03(외부 증강). 증강 요청·결과 묶음 조회 케이스. RQ-SFR-11-05는 07-01과 동일 기능이라 통합.

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-001-01 | 증강요청 WINTER 단일 정상 200 | REVIEWER | 검수완료 영상·WINTER | 1. 데이터 &gt; 증강 요청 메뉴에서 증강 요청 화면 진입 <br>2. 검수 완료 영상 목록에서 대상 영상 1건을 선택하고 처리 종류 '겨울'(WINTER)을 단일 선택 <br>3. 하단 요약에서 대상 영상·처리 종류를 확인한 뒤 처리 요청 실행 <br>: 검수 완료(승인) 영상만 목록에 노출되며 미승인 영상은 선택 불가 <br>: 겨울·야간·우천 증강 중 겨울 1종만 선택(처리 종류·대상 영상 각 단일 선택) <br>: 요청 성공 시 증강 결과 화면으로 이동하고 성공 알림 표시 | · `SELECT AUG_TYPE_CD, AUG_PROC_STTS_CD, IDMP_KEY, OTSD_JOB_ID FROM LS_DATA_AUG WHERE SRC_SN = :srcSn AND AUG_TYPE_CD = 'WINTER'` → WINTER · PENDING · IDMP_KEY/OTSD_JOB_ID 채워짐 1건 <br>· AugmentRequestResponse(jobId·requestedAt·videoCount=1·typeCount=1) 200 반환 <br>· 검증: HTTP 200·응답 jobId 존재·위 SELECT 신규 row 1건 | |

## 단위시험 KLID-AT-UT-002

| 단위시험ID | KLID-AT-UT-002 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-07-02 · UC KLID-AT-UC-002] 생성 라벨 무결성 유지·증강 결과 수신 | | |
| 관련 컴포넌트 ID | KLID-AT-CO-002 | 관련 프로그램 ID | KLID-AT-IF-003 증강 결과 수신 (D4 수신 프로그램: 증강결과수신, II-006) |
> 출처: D11 UT-03(외부 증강) 중 라벨 보존율 계산 케이스. + UT-20(외부 콜백 웹훅, UC-002 증강 결과 수신·등록) 새영상 생성·라벨 복사 케이스 추가(UC-002 수신·등록 직접 검증).

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-002-01 | LabelIntegrity 완전 동일 라벨 보존율 100 | - | 원본·증강 라벨쌍 | 1. 라벨 보존율 계산 컴포넌트 단위 시험 실행(화면 없음 — 서비스 계산 로직 단위) <br>2. 원본 라벨과 좌표·분류가 완전히 동일한 증강 라벨쌍을 보존율 계산에 입력 <br>: 좌표 보존율(원본별 IoU 0.9 초과 매칭 비율, 가중 0.7)과 분류 보존율(분류명 동일 비율, 가중 0.3)을 합산해 100점 척도로 산출 <br>: 계산 결과(반환 값)로 정상 동작을 확인하는 서비스 단위 시험 | · 전 원본 라벨이 IoU>0.9·label 일치 → coordPreserved=1.0·categoryPreserved=1.0 <br>· (1.0×0.7 + 1.0×0.3)×100 = 100.00 (소수 2자리 HALF_UP 반올림) <br>· 검증: 반환 BigDecimal == 100.00 | |
| KLID-AT-UT-002-02 | 증강 SUCCESS 시 새 RAW_SN 생성 PARENT_RAW_SN 참조 + 원본 프레임 라벨·메타 복사 | - | 증강 SUCCESS | 1. 외부 증강 서비스가 처리 완료 결과를 콜백으로 전송(화면 없음 — 외부 콜백 수신 단위) <br>2. 성공(SUCCESS) 결과와 원본 식별자·증강 유형(겨울·야간·우천)·결과 파일 경로를 수신해 새 영상 생성 처리 <br>: 서명(위·변조) 검증과 중복 수신 방지(멱등) 통과 시 원본을 부모로 하는 새 영상을 미검수 상태로 등록 <br>: 원본 프레임·라벨·메타를 새 영상으로 복사하는 콜백 처리 단위 시험(결과는 DB 조회로 확인) | · `SELECT RAW_SN, PARENT_RAW_SN, DATA_STTS_CD, DE_IDNTF_YN FROM LS_DATA_RAW WHERE PARENT_RAW_SN = :orgnlRawSn` → 신규 RAW_SN 1건 · PENDING · N <br>· `SELECT COUNT(*) FROM LS_DATA_SRC WHERE RAW_SN = :newRawSn` → 원본 프레임 건수와 동일 <br>· `SELECT COUNT(*) FROM LS_DATA_LBL WHERE SRC_SN IN (SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN = :newRawSn)` → 원본 라벨 건수와 동일 <br>· `SELECT COUNT(*) FROM LS_DATA_META WHERE RAW_SN = :newRawSn` → 원본 메타 건수와 동일 | |
| KLID-AT-UT-002-03 | 증강 성공시 원본 라벨/메타가 새 프레임에 정확히 복사·복수 프레임 매핑 | - | 원본 라벨·메타 | 1. 복수 프레임·라벨을 가진 원본 영상에 대해 성공 콜백 수신(화면 없음 — 외부 콜백 수신 단위) <br>2. 원본 프레임별로 새 프레임을 매핑하고 각 프레임에 라벨을 복사 <br>: 원본↔복사 라벨의 대응 관계를 매핑 정보로 기록(해상도 동일 → 좌표 재계산 없음) <br>: 프레임별 라벨 복사·매핑 정확성을 DB 조회로 확인하는 콜백 처리 단위 시험 | · 각 원본 SRC_SN → 신규 SRC_SN 매핑 정확·라벨이 대응 프레임에 복사 <br>· `SELECT ORGNL_DATA_LBL_SN, DATA_LBL_SN, COORD_RECALC_YN, SCALE_X, SCALE_Y FROM LS_DATA_AUG_LBL_MAP WHERE DATA_AUG_SN = :dataAugSn` → 원본 라벨 건수만큼 매핑 행 · 각 행 COORD_RECALC_YN='N' · SCALE_X/SCALE_Y NULL <br>· 검증: 위 SELECT 매핑 row 수 원본 라벨 수 일치 · 프레임별 라벨 좌표(POINT_CN) 원본 일치 | |

## 단위시험 KLID-AT-UT-003

| 단위시험ID | KLID-AT-UT-003 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-06-03 · UC KLID-AT-UC-003] 이미지 확대·축소·해상도 변경 | | |
| 관련 컴포넌트 ID | KLID-AT-CO-003 | 관련 프로그램 ID | KLID-AT-IF-004 해상도 변경 |
> 출처: D11 UT-22(영상·스트리밍·해상도). 해상도 변경(다운스케일 이미지셋, SFR-06-03) 케이스만 추출(영상 목록·스트리밍·서명 URL은 인프라/공통으로 제외).

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-003-01 | 다운스케일 성공시 이미지셋 생성·EXPORT 1행 기록 | REVIEWER | 해상도 preset | 1. 데이터 > 증강 요청 화면(KLID-AT-SC-022) 진입 <br>2. 검수 완료 영상 1건 선택 후 처리 종류 '해상도 변경' 선택 <br>3. 타겟 해상도 720P 지정 후 하단바 처리 요청 실행 <br>: 원본보다 낮은 해상도로 프레임 이미지셋을 다운스케일 제공(업스케일 요청 거부), 변환 결과를 화면에 표시 | · `SELECT DATA_RAW_SN, TARGET_RES_CD, ORGNL_W, ORGNL_H, TARGET_W, TARGET_H, FRAME_CNT, OUTPUT_DIR_PATH, REG_ID FROM LS_RESOLUTION_EXPORT WHERE DATA_RAW_SN={대상 rawSn} AND TARGET_RES_CD='RES_720P'` → 1행(모든 컬럼 값 채워짐) <br>· 다운스케일 이미지셋 `{base}/resolution/{rawSn}/{TARGET_RES_CD}/` 하위 생성 <br>· 신규 LS_DATA_RAW 미생성·라벨 좌표 미복사 <br>· 검증: `SELECT COUNT(*) FROM LS_RESOLUTION_EXPORT WHERE DATA_RAW_SN={대상 rawSn} AND TARGET_RES_CD='RES_720P'` → 1(UK 유일) | |
| KLID-AT-UT-003-02 | 정상 해상도 변경 요청시 201 | REVIEWER | preset | 1. 데이터 > 증강 요청 화면에서 영상 1건·처리 종류 '해상도 변경'·타겟 720P 선택 <br>2. 하단바 처리 요청 버튼 실행 <br>: 종류 1건과 영상 1건이 선택되면(해상도 변경 시 타겟 해상도 포함) 처리 요청이 활성화되고, 정상 접수 시 성공 알림 표시 | · HTTP 201 Created <br>· data = `{exportSn, srcW, srcH, targetW, targetH, frameCount}` <br>· OUTPUT_DIR_PATH 등 내부 파일 경로 응답 미포함(CWE-209) <br>· 검증: 응답 상태코드·바디 필드 존재/경로 부재 | |

## 단위시험 KLID-AT-UT-004

| 단위시험ID | KLID-AT-UT-004 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-08-01 · UC KLID-AT-UC-004] 라벨링 정확도(위치·경계) 향상 | | |
| 관련 컴포넌트 ID | KLID-AT-CO-004 | 관련 프로그램 ID | KLID-AT-IF-005 객체 추적·IF-006 AI 추론 연동 |
> 출처: D11 UT-11(트랙 보간, 직접 UC-004) + UT-09(오토라벨링 YOLO, 간접 UC-004 — SFR-08 라벨링 핵심이므로 포함).

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-004-01 | 두 키프레임 5~10 사이 6 7 8 9 프레임 선형 보간 / 세 키프레임 각 구간 독립 보간 | - | 키프레임 2~3개 | 1. 트랙 보간 도메인 로직 단위 실행(화면 조작 없음) <br>2. 키프레임 5·10(outside=false) 목록 구성 후 사이 프레임 선형 보간 계산 <br>: 순수 계산 로직 단위 시험 — 키프레임 3개 입력 시 각 구간(5~10, 10~N) 독립 보간, 반환값으로 확인 | · 반환 Map(frame→Bbox)에 6/7/8/9 선형 보간값 산출 <br>· 키프레임 자체(5·10) 포함, `outside=true` 이후·마지막 키프레임 이후 propagate 없음 <br>· 검증: 순수 도메인 로직 단위테스트 — 반환 Map 좌표 계산 일치 | |
| KLID-AT-UT-004-02 | 같은 trackId 5~10 프레임 BBOX 2개 있으면 6 7 8 9 프레임 보간 4건 저장 | - | trackId 키프레임 | 1. 오토라벨 파이프라인의 트랙 보간 Step 실행(rawSn 대상) <br>2. 같은 trackId 자동 BBOX 키프레임(5·10) 사이 프레임을 보간해 저장 <br>: 배치 Step 단위 시험 — 키프레임은 건너뛰고 사이 프레임(6~9)만 자동 라벨 생성, DB 저장으로 확인 | · `SELECT COUNT(*) FROM LS_DATA_LBL WHERE TRCK_ID={대상 trackId} AND AUTO_LBL_YN='Y' AND LBL_TYPE_CD='BBOX'` → 4건(CONF_SCORE=0·POINT_CN nested) <br>· `SELECT COUNT(*) FROM LS_DATA_LBL_AI_INFO WHERE LBL_SRC_CD='INTERPOLATE' AND DATA_RAW_SN={대상 rawSn}` → 4건(DATA_SRC_SN 매핑) <br>· 검증: 위 조회 4건 + SRC_SN(frameNo 6/7/8/9) 매핑 | |
| KLID-AT-UT-004-03 | YOLO 검출 결과는 AUTO_LBL_YN Y + BBOX 타입 + score 0~1 저장 | - | 검출 응답 | 1. 오토라벨 파이프라인의 YOLO 검출 Step 실행(rawSn 대상) <br>2. 프레임별 AI 추론 서버 YOLO 호출 후 검출 결과를 자동 라벨로 저장 <br>: 배치 Step 단위 시험 — 검출 BBOX·신뢰도(0~1) 자동 라벨 생성, DB 저장으로 확인 | · `SELECT AUTO_LBL_YN, LBL_TYPE_CD, CONF_SCORE FROM LS_DATA_LBL WHERE SRC_SN={대상 프레임 srcSn}` → AUTO_LBL_YN='Y'·LBL_TYPE_CD='BBOX'·CONF_SCORE 0.0~1.0 <br>· `SELECT LBL_SRC_CD, CONF_SCORE FROM LS_DATA_LBL_AI_INFO WHERE DATA_SRC_SN={대상 프레임 srcSn}` → LBL_SRC_CD='YOLO'·CONF_SCORE 존재 <br>· 검증: 위 조회 행 필드 값 일치 | |

## 단위시험 KLID-AT-UT-005

| 단위시험ID | KLID-AT-UT-005 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-08-02 · UC KLID-AT-UC-005] 객체 외곽경계 자동밀착 | | |
| 관련 컴포넌트 ID | KLID-AT-CO-005 | 관련 프로그램 ID | KLID-AT-IF-007 외곽 분할·IF-006 AI 추론 연동 |
> 출처: D11 UT-10(배치 단계 — SAM2 분할).

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-005-01 | DB BBOX 있으면 SAM2 호출 후 POLYGON 저장 | - | BBOX 있음 | 1. 오토라벨 파이프라인의 SAM2 분할 Step 실행(rawSn 대상) <br>2. 프레임 내 자동 BBOX를 프롬프트로 AI 추론 서버 분할 호출 후 폴리곤 저장 <br>: 배치 Step 단위 시험 — 외곽 경계 폴리곤 자동 라벨 생성, DB 저장으로 확인 | · `AiServerClient.segment` 호출 발생 <br>· `SELECT LBL_TYPE_CD, AUTO_LBL_YN, POINT_CN, CONF_SCORE FROM LS_DATA_LBL WHERE SRC_SN={대상 프레임 srcSn} AND LBL_TYPE_CD='POLYGON'` → LBL_TYPE_CD='POLYGON'·AUTO_LBL_YN='Y'·POINT_CN=폴리곤 JSON·CONF_SCORE(setScale 4) <br>· 검증: 위 조회 POLYGON 행 저장 확인 | |
| KLID-AT-UT-005-02 | POLYGON 저장 시 LsDataLblAiInfo SRC SAM2도 동시 저장 | - | 저장 결과 | 1. SAM2 분할 Step의 폴리곤 저장 직후 동일 트랜잭션 처리 <br>2. 폴리곤 라벨 1건당 AI 정보(출처 SAM2)를 동시 저장 <br>: 저장 로직 단위 시험 — 폴리곤 1건:AI정보 1건 매칭, DB 조회로 확인 | · `SELECT LBL_SRC_CD, DATA_LBL_SN, DATA_RAW_SN, DATA_SRC_SN, CONF_SCORE FROM LS_DATA_LBL_AI_INFO WHERE DATA_LBL_SN={저장 라벨 PK}` → 1행(LBL_SRC_CD='SAM2'·DATA_RAW_SN·DATA_SRC_SN·CONF_SCORE 채워짐) <br>· POLYGON 라벨 1건당 AiInfo 1건 매칭 <br>· 검증: `SELECT COUNT(*) FROM LS_DATA_LBL_AI_INFO WHERE LBL_SRC_CD='SAM2' AND DATA_LBL_SN={저장 라벨 PK}` → 1(행 존재) | |

## 단위시험 KLID-AT-UT-006

| 단위시험ID | KLID-AT-UT-006 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-08-03 · UC KLID-AT-UC-006] 라벨링 정밀도 조절 | | |
| 관련 컴포넌트 ID | KLID-AT-CO-006 | 관련 프로그램 ID | KLID-AT-IF-008 시스템 설정 |
> 출처: D11 UT-26(시스템 설정) 중 POLYGON_SIMPLIFY_TOLERANCE(정밀도 설정값) 케이스만 추출(YOLO 파라미터·배치 주기 설정은 인프라/공통으로 제외).

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-006-01 | POLYGON_SIMPLIFY_TOLERANCE 기본값 1.0·REVIEWER 2.5 저장 반영 | REVIEWER | 허용오차 | 1. 관리 > 시스템 설정 화면(KLID-AT-SC-025) 진입 <br>2. 라벨링 정밀도 카드의 경계 세밀함(외곽선 단순화 허용오차) 값을 2.5로 수정 후 저장 <br>: 기본값 1.0 → 2.5 저장(0.0~50.0 범위) — 저장 후 객체 추적·외곽 밀착 결과의 경계 세밀함에 반영 | · `SELECT STNG_VALUE FROM LS_SYSTEM_CONFIG WHERE STNG_KEY='POLYGON_SIMPLIFY_TOLERANCE'` → '2.5'(기본 '1.0'에서 변경) <br>· 캐시 무효화 → 다음 `getDouble` 호출에서 2.5 반영 <br>· 검증: 응답 ConfigResponse.value='2.5' + 위 SELECT 결과 일치 | |
| KLID-AT-UT-006-02 | ConfigKeys에 YOLO CONF IMGSZ IOU·POLYGON_SIMPLIFY_TOLERANCE 화이트리스트 포함·NUMBER/DECIMAL RANGE 유효·기존 Batch 키 유지 | - | 설정 키 | 1. 설정 키 화이트리스트·범위 상수 단위 검증(화면 조작 없음) <br>2. 허용 키 집합과 정수/소수 범위 정의를 확인 <br>: 순수 상수 단위 시험 — YOLO 3종·정밀도 키 포함 및 기존 배치 키 유지, 범위 값 정확 | · ALLOWED 포함: YOLO_CONF_THRESHOLD·YOLO_IMGSZ·YOLO_IOU·POLYGON_SIMPLIFY_TOLERANCE + 기존 BATCH_INTERVAL_SEC·BATCH_CONCURRENCY 유지 <br>· NUMBER_RANGE: YOLO_CONF_THRESHOLD 25~80·YOLO_IMGSZ 320~1920·YOLO_IOU 30~80 <br>· DECIMAL_RANGE: POLYGON_SIMPLIFY_TOLERANCE 0.0~50.0 <br>· 검증: 상수 단위테스트 — 집합·범위 정확 | |

## 단위시험 KLID-AT-UT-007

| 단위시험ID | KLID-AT-UT-007 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-08-04 · UC KLID-AT-UC-007·009] 버전관리·변경이력 추적 | | |
| 관련 컴포넌트 ID | KLID-AT-CO-007 | 관련 프로그램 ID | KLID-AT-IF-009 버전 스냅샷·IF-011 관제 통지 연동 (D4 송신 프로그램: 관제통지, II-007) |
> 출처: D11 UT-18(버전관리) 중 검수 승인 스냅샷·이력 케이스. + UT-19(관제 통지, UC-009 검수 완료·수정 통지) 통지 케이스 추가. UC-009는 08-04·08-05 공통이며 KLID-AT-UT-008 블록도 동일 UC-009를 실현한다(케이스는 본 블록에 집약).

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-007-01 | 검수 승인시 영상 프레임의 현재 라벨로 DB 스냅샷 APPROVED 버전 생성 / 다중 프레임 각 스냅샷 | REVIEWER | 라벨 보유 영상 | 1. 검수 화면에 진입해 제출된 영상의 프레임·라벨을 읽기 전용으로 확인한다 <br>2. 승인 버튼을 눌러 승인 확인 후 검수를 완료 처리한다 <br>: 검수 승인 시점(KLID-AT-SD-007)에 별도 조작 없이 해당 영상 프레임의 현재 라벨 전체가 DB 스냅샷 APPROVED 버전으로 자동 저장된다. 라벨을 가진 다중 프레임은 프레임마다 각각 스냅샷이 생성되고, 라벨 0건 프레임은 생성에서 제외된다. | · `SELECT SAVE_REASON_CD, ACTVTN_YN, VERSION_HASH, VERSION_NO, LBL_PAYLOAD FROM LS_LABEL_VERSION WHERE DATA_SRC_SN={대상 프레임} AND ACTVTN_YN='Y'` → 프레임당 1행, SAVE_REASON_CD='APPROVED', VERSION_HASH=payload SHA-256, LBL_PAYLOAD=라벨 전체 JSON, VERSION_NO 증가 <br>· `SELECT COUNT(*) FROM LS_LABEL_VERSION WHERE DATA_SRC_SN={대상 프레임} AND ACTVTN_YN='Y'` → 1건(동일 프레임 기존 active 는 ACTVTN_YN='N' 으로 deactivate) <br>· `SELECT COUNT(*) FROM LS_LABEL_VERSION WHERE DATA_SRC_SN={라벨 0건 프레임}` → 0건(스냅샷 미생성·스킵), 반환값 `CommitResult(created, skipped)` <br>· 검증: 프레임 수 = 생성 버전 수, SAVE_REASON_CD/ACTVTN_YN/VERSION_HASH 확인 | |
| KLID-AT-UT-007-02 | LsDataLblHstry 삭제 이력 저장·PK 채번 / 프레임 단위 최신순 조회 | - | 이력 데이터 | 1. 라벨 삭제가 발생하면 변경 이력 적재가 트리거된다 <br>2. 삭제된 라벨 식별자·프레임 정보로 이력을 저장하고 같은 프레임에 여러 건 적재 후 프레임 단위 최신순으로 조회한다 <br>: UI 없는 백엔드 변경이력 서비스 단위 시험. 삭제 이력 저장 시 이력 PK가 자동 채번(not-null)되고, 조회 결과가 등록일시 내림차순(최신순)으로 정렬됨을 DB로 확인한다. | · `SELECT LBL_HSTRY_SN, LBL_SN, SRC_SN, REGISTERED_AT FROM LS_DATA_LBL_HSTRY WHERE SRC_SN={대상 프레임}` → 삭제 건수만큼 행, LBL_HSTRY_SN not-null(IDENTITY 자동 채번), LBL_SN·SRC_SN·REGISTERED_AT 기록(PII·사유 미저장) <br>· `SELECT REGISTERED_AT FROM LS_DATA_LBL_HSTRY WHERE SRC_SN={대상 프레임} ORDER BY REGISTERED_AT DESC` → 최신순(내림차순) 정렬 <br>· 검증: 저장 후 PK not-null + 조회 순서 DB 확인 | |
| KLID-AT-UT-007-03 | TASK_COMPLETED/TASK_MODIFIED 통지 정상 송신시 200 | - | 통지 페이로드 | 1. 검수 완료 및 검수 완료 후 수정 시 관제서버로 완료·수정 통지 전송이 트리거된다 <br>2. TASK_COMPLETED·TASK_MODIFIED 통지를 관제 inbound(mock)로 송신하고 200 응답을 수신한다 <br>: UI 없는 백엔드 단방향 통지 송신 서비스 단위 시험. 정상 200 응답 시 예외 없이 반환되고 완료·수정 성공 지표가 증가하며, 폴백(재등록) 큐에는 적재되지 않음을 확인한다. | · 예외 없이 정상 반환, `metrics.incrementCompletedSuccess()`/`incrementModifiedSuccess()` 증가 <br>· 실패 시에만 `fallbackService.enqueuePending(requestId, eventType, rawSn, payload)` 로 폴백 큐 적재(정상 200 이면 미적재) <br>· 검증: 응답 200 + 폴백 큐 미적재 확인 | |
| KLID-AT-UT-007-04 | TaskCompletedPayload/TaskModifiedPayload record 정상 생성 | - | 페이로드 | 1. 통지 발행 전 완료·수정 통지 페이로드 생성이 트리거된다 <br>2. TASK_COMPLETED·TASK_MODIFIED 페이로드 객체를 생성하고 각 필드·구조를 검증한다 <br>: UI 없는 백엔드 페이로드 구조 단위 시험. 이벤트 타입 고정값과 요청 ID(멱등키 UUID)가 채워지고, 변경 종류가 허용 집합에 속하며, PII·토큰·라벨/메타 본문·원본 이미지 경로가 포함되지 않음(CWE-359)을 확인한다. | · eventType 고정값("TASK_COMPLETED"/"TASK_MODIFIED"), requestId=UUID(idempotency) <br>· TaskModifiedPayload.changeTypes ⊆ {LABEL_ADDED, LABEL_UPDATED, LABEL_DELETED, META_UPDATED}(ChangeType.ALL), frameIds=SRC_SN 목록 <br>· PII·토큰·라벨/메타 본문·원본 이미지 경로 미포함(CWE-359) <br>· 검증: record 필드 값 assert | |
| KLID-AT-UT-007-05 | ReviewApprovedEvent 수신시 sendCompleted 호출 / TaskModifiedEvent 수신시 debouncer accumulate 호출 | - | 이벤트 | 1. 검수 승인·수정 트랜잭션 커밋 후(AFTER_COMMIT) 관련 이벤트가 발행·수신된다 <br>2. 승인 이벤트 수신 시 완료 통지 발행을, 수정 이벤트 수신 시 디바운스 누적을 호출하고 만료 윈도우를 flush 한다 <br>: UI 없는 백엔드 이벤트 리스너 단위 시험. 승인 이벤트는 완료 통지 1회 호출, 수정 이벤트는 프레임 변경을 rawSn 윈도우에 누적(중복 프레임 합쳐짐) 후 만료 시 수정 통지 1회 flush, 롤백 트랜잭션에서는 미호출됨을 확인한다. | · ReviewApprovedEvent 수신 시 sendCompleted 1회 호출 <br>· TaskModifiedEvent 수신 시 rawSn 윈도우에 frameIds/changeTypes 누적(중복 srcSn 합쳐짐), 윈도우 만료 후 sendModified 1회 flush <br>· 롤백 트랜잭션에서는 AFTER_COMMIT 미호출 <br>· 검증: mock notifyService/debouncer 호출 횟수·인자 검증 | |

## 단위시험 KLID-AT-UT-008

| 단위시험ID | KLID-AT-UT-008 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-08-05 · UC KLID-AT-UC-008·009] 버전 비교·복구 | | |
| 관련 컴포넌트 ID | KLID-AT-CO-008 | 관련 프로그램 ID | KLID-AT-IF-010 버전 비교·복구 |
> 출처: D11 UT-18(버전관리) 중 diff/롤백 케이스. (UC-009 검수 완료·수정 통지 케이스는 KLID-AT-UT-007 블록에 집약 — 중복 회피.)

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-008-01 | diff 두 스냅샷 labels JSON 파싱하여 라벨 단위 ADDED REMOVED MODIFIED 반환 / shape 변경 before·after | WORKER | 스냅샷쌍 | 1. 버전 관리 화면(KLID-AT-SC-010)에 진입해 프레임의 커밋(버전 스냅샷) 목록을 확인한다 <br>2. 커밋 목록에서 비교할 두 버전을 선택해 변경 내용(Diff)을 확인한다 <br>: 검수 승인 스냅샷 두 건의 라벨 JSON을 라벨 단위로 비교해 추가·수정·삭제로 구분 표시한다. 좌표 변경은 BBOX(2점)·POLYGON(3점+) 형태의 이전·이후 shape 로 보여주고, 서로 다른 프레임이거나 동일 내용은 결과에서 제외한다. | · from 에만 존재=REMOVED(before만), to 에만 존재=ADDED(after만), 양쪽 존재+좌표/lblTypeCd/label 차이=MODIFIED(before+after) <br>· ShapeDto: 2점=BBOX(left/top/right/bottom), 3점+=POLYGON(flat points 배열) <br>· 동일 내용은 결과 제외, JSON 파싱 실패 시 빈 리스트(장애 격리) <br>· 검증: 응답 배열의 type·objectId·before/after shape 확인 | |
| KLID-AT-UT-008-02 | 배정된 WORKER 본인 프레임 rollback시 대상 스냅샷 복원 새 ROLLBACK 버전 / 복원 후 LS_DATA_LBL이 스냅샷과 일치 | WORKER | 스냅샷·본인 프레임 | 1. 버전 관리 화면에서 최신이 아닌 이전 버전을 선택한다 <br>2. 롤백 버튼을 눌러 확인 후 해당 스냅샷으로 복원한다 <br>: 본인에게 배정된 영상만 롤백 가능하며 타인 배정·작업락 영상은 거부된다. 대상 스냅샷 라벨로 프레임 라벨을 교체하고 새 활성 ROLLBACK 버전 이력 1건을 생성하며, 검수 완료(APPROVED) 영상이면 수정 통지가 발행된다. 복원 후 라벨이 스냅샷과 일치함을 확인한다. | · `SELECT SAVE_REASON_CD, ACTVTN_YN FROM LS_LABEL_VERSION WHERE DATA_SRC_SN={대상 프레임} AND ACTVTN_YN='Y'` → SAVE_REASON_CD='ROLLBACK', 1행(동일 해시면 기존 행 재활성, 이전 active 는 'N') <br>· `SELECT LBL_TYPE_CD, LBL_NM, POINT_CN FROM LS_DATA_LBL WHERE SRC_SN={대상 프레임}` → 대상 스냅샷 라벨과 일치(lblTypeCd·label·좌표) — 라벨링 캔버스/V_COMPLETED_LABEL 즉시 반영 <br>· 라벨 교체·버전 전환·통지가 동일 트랜잭션에 묶여 원자적 커밋/롤백 <br>· APPROVED 영상은 TASK_MODIFIED(LABEL_UPDATED) 발행 <br>· 검증: DB 조회로 LS_DATA_LBL vs 스냅샷 items 비교 + LS_LABEL_VERSION active 행(SAVE_REASON_CD/ACTVTN_YN) 확인 | |

## 단위시험 KLID-AT-UT-010

| 단위시험ID | KLID-AT-UT-010 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-07-03 · UC KLID-AT-UC-010] 생성 데이터 활용여부 선택(증강 검수) | | |
| 관련 컴포넌트 ID | KLID-AT-CO-010 | 관련 프로그램 ID | KLID-AT-IF-012 증강 활용 검수 (활용 결정 외부 동기화 IF-002) |
> 출처: D11 UT-03(외부 증강) 중 수락/반려 검수 케이스.

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-010-01 | REVIEWER accept시 AUG_PROC_STTS_CD ACCEPTED + LS_DATA_AUG_RVW row INSERT | REVIEWER | 증강 결과 | 1. 데이터 &gt; 증강 요청 &gt; 증강 결과 메뉴에서 증강 결과 화면 진입 <br>2. 증강 유형 탭에서 원본·증강 프레임 페어와 라벨 무결성을 확인한 뒤 채택 실행 <br>: 미검수(처리 대기) 상태의 증강 결과만 채택 대상이며 이미 처리된 결과는 채택 불가 <br>: 채택 시 활용 여부 검수 결과가 '채택'으로 기록되고 처리 결과 알림 표시 | · `SELECT AUG_PROC_STTS_CD FROM LS_DATA_AUG WHERE DATA_AUG_SN = :dataAugSn` → ACCEPTED <br>· `SELECT RVW_STTS_CD, RVW_ID, RVW_DT FROM LS_DATA_AUG_RVW WHERE DATA_AUG_SN = :dataAugSn` → ACCEPTED · RVW_ID/RVW_DT 채워짐 1건 <br>· 검증: HTTP 200·위 두 SELECT 결과 확인 | |
| KLID-AT-UT-010-02 | REJECTED 시 사유 LS_DATA_AUG_RVW REJECT_RSN 저장 | REVIEWER | 반려 사유 | 1. 데이터 &gt; 증강 요청 &gt; 증강 결과 메뉴에서 증강 결과 화면 진입 <br>2. 대상 증강 결과에서 거부를 선택하고 거부 사유(1~500자)를 입력해 실행 <br>: 거부 사유는 필수이며 공백이면 처리되지 않음 <br>: 거부 시 활용 여부 검수 결과가 '거부'로 기록되고 입력 사유가 함께 저장됨(처리 결과 알림 표시) | · `SELECT AUG_PROC_STTS_CD FROM LS_DATA_AUG WHERE DATA_AUG_SN = :dataAugSn` → REJECTED <br>· `SELECT RVW_STTS_CD, REJECT_RSN FROM LS_DATA_AUG_RVW WHERE DATA_AUG_SN = :dataAugSn` → REJECTED · REJECT_RSN=입력 사유 <br>· 검증: HTTP 200·위 SELECT REJECT_RSN 값 확인 | |

## 단위시험 KLID-AT-UT-011

| 단위시험ID | KLID-AT-UT-011 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-09-01 / RQ-SFR-09-02 / RQ-SFR-11-06 · UC KLID-AT-UC-011] 비식별 처리·API 연동 | | |
| 관련 컴포넌트 ID | KLID-AT-CO-011 | 관련 프로그램 ID | KLID-AT-IF-013 비식별 처리·IF-014 외부 비식별 연동 (D4 송신 프로그램: 비식별위탁 II-001·비식별진행폴링 II-002) |
> 출처: D11 UT-07(배치 단계 — 비식별). RQ-SFR-11-06은 09-01과 동일 기능이라 통합.

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-011-01 | 정상 경로 응답이면 LS_DEIDENT_REPORT SUCCEEDED + DE_IDNTF Y + 결과경로 반환 | - | 정상 응답 | 1. 원본 영상 1건이 적재되면 파이프라인 선두 비식별 배치 단계(DeidentifyStep)가 자동 실행된다 <br>2. 처리 시작 시 비식별 처리 이력을 '요청'으로 적재하고, 정상 완료 응답이면 원본을 비식별 경로로 안전 복사(임시파일→원자적 이동)해 결과 경로를 산출한 뒤 영상 비식별 완료 표시(DE_IDNTF='Y')와 처리 이력 성공 전이, 마킹 준비(MARKING_READY) 전이를 수행한다 <br>: 화면 없는 배치 파이프라인 선두(비식별) 단계 단위 시험. 영상 적재 이벤트로 트리거되며, 정상 응답 경로에서 처리 이력·영상 상태 전이를 해당 RAW_SN 두 테이블 조회로 확인한다 | · `SELECT PROC_STTS_CD FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN={대상 RAW_SN}` → 'SUCCEEDED' (REQUESTED→SUCCEEDED 전이 완료) <br>· `SELECT DE_IDNTF_FILE_PATH_NM FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN={대상 RAW_SN}` → 산출 비식별 영상 경로 <br>· `SELECT DE_IDENT_YN FROM LS_DATA_RAW WHERE RAW_SN={대상 RAW_SN}` → 'Y' <br>· `SELECT DATA_STTS_CD FROM LS_DATA_RAW WHERE RAW_SN={대상 RAW_SN}` → 'MARKING_READY' | |
| KLID-AT-UT-011-02 | 위탁 시 LS_DEIDENT_PROC_LOG REQUESTED 상태로 적재 / idempotencyKey 헤더 포함 | - | 위탁 요청 | 1. KPST 위탁 경로(위탁 사용 설정)로 비식별 배치 단계를 실행해 외부 비식별 시스템에 프로젝트 생성 위탁을 호출한다 <br>2. 처리 이력을 '요청'으로 적재(요청 idempotency 식별자 포함)하고, 위탁 성공 시 KPST 프로젝트 ID 기록·폴링 대기(WAITING) 전이를 수행하되 완료는 별도 폴링 잡이 담당하므로 위탁 직후에는 비식별 완료 미전이(deferred)로 둔다 <br>: 화면 없는 외부 비식별 위탁 호출 단위 시험. 위탁 직후 처리 이력이 '요청' 유지·요청 식별자·폴링 상태로 적재됨을 RAW_SN 조회로 확인한다 | · `SELECT PROC_STTS_CD FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN={대상 RAW_SN}` → 'REQUESTED' (위탁 직후 유지) <br>· `SELECT REQ_ID, KPST_PRJ_ID, POLL_STTS_CD FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN={대상 RAW_SN}` → REQ_ID(요청 idempotency 식별자) 기록·KPST_PRJ_ID 기록·POLL_STTS_CD='WAITING' <br>· `SELECT DE_IDENT_YN FROM LS_DATA_RAW WHERE RAW_SN={대상 RAW_SN}` → 미전이(완료는 폴링 잡이 담당) | |

## 단위시험 KLID-AT-UT-013

| 단위시험ID | KLID-AT-UT-013 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-09-04 · UC KLID-AT-UC-013] 비식별 옵션 설정 | | |
| 관련 컴포넌트 ID | KLID-AT-CO-013 | 관련 프로그램 ID | KLID-AT-IF-008 시스템 설정 (비식별 옵션 설정 변경) |
> **⚠ 설계 케이스(구현·테스트 후속)** — D3 CO-013은 비식별 옵션 설정을 **SystemConfig 메커니즘**(SystemConfigController/Service·LsSystemConfig, IF-008 비식별 옵션 설정 변경)으로 실현하도록 설계돼 있다. SystemConfig 의 설정 저장·화이트리스트 검증·권한·캐시 메커니즘 자체는 **실코드로 구현·검증됨**(KLID-AT-UT-006 블록 `KLID-AT-UT-006-01` POLYGON_SIMPLIFY_TOLERANCE 등 UT-26 실케이스). 다만 **비식별 마스킹 옵션 키(마스킹 타입·범위·출력 화질·포맷)는 현재 `ConfigKeys` 미등록**이고, 비식별 옵션은 `KpstProjectRequest.withDefaults()` 기본값(masking_type=0·db_save=0·masking_range=1·exp_quality=0·exp_format=1)으로 고정돼 위탁된다(사용자 설정 미연동). 따라서 아래는 **D11 실코드 추출이 아닌 설계 케이스**이며, 비식별 옵션 키 등록 시 UT-26 패턴과 동일하게 실테스트로 승격한다. (케이스 ID는 본 절차서 자체 채번 — D11 UT 출처 없음.)

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-013-01 | [설계] REVIEWER가 비식별 마스킹 옵션(타입·범위·화질·포맷) 설정값 저장 시 SystemConfig 반영 + 이후 비식별 위탁에 적용 | REVIEWER | 허용 옵션값 | [설계] 1. 검수자(REVIEWER)로 시스템 설정 화면(관리 > 시스템 설정)에 진입한다 <br>2. [설계] 비식별 마스킹 옵션(마스킹 타입·범위·출력 화질·포맷) 항목에 값을 입력하고 저장하면 → 허용 옵션값(화이트리스트) 검증 후 설정에 저장·반영되고, 이후 비식별 위탁 요청이 저장된 옵션값(masking_type·masking_range·exp_quality·exp_format)으로 구성되어 위탁된다 <br>: [설계] 시스템 설정 화면(KLID-AT-SC-025)의 설정 저장 동선. ※ 현행: 비식별 옵션 키 미등록 → 위탁은 기본값(masking_type=0·db_save=0·masking_range=1·exp_quality=0·exp_format=1)으로 고정 위탁된다. 옵션 키 등록 시 UT-26 패턴 실테스트로 승격 | · [설계] `SELECT STNG_VALUE FROM LS_SYSTEM_CONFIG WHERE STNG_KEY={비식별 옵션 키}` → 저장 옵션값(허용 옵션값만 통과, 그 외 400) <br>· [설계] 저장 후 재조회 시 반영된 옵션값 확인 <br>· [설계] 위탁 요청의 KpstProjectRequest 필드가 저장 옵션값과 일치 <br>· 검증: 위 LS_SYSTEM_CONFIG SELECT + KpstProjectRequest 필드 확인(옵션 키 등록 시 UT-26 패턴 실테스트로 승격) | |

## 단위시험 KLID-AT-UT-016

| 단위시험ID | KLID-AT-UT-016 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-09-03 / RQ-SFR-09-05 · UC KLID-AT-UC-016] 비식별 결과 검토·연동확인 | | |
| 관련 컴포넌트 ID | KLID-AT-CO-016 | 관련 프로그램 ID | KLID-AT-IF-015 비식별 신고 |
> 출처: D11 UT-15(비식별 누락 신고). 신고→락+라벨 스냅샷 후 삭제, 수동 비식별 후 resolve 케이스.

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-016-01 | WORKER 본인 배정 영상 신고 201 + REPORT_STTS OPEN + LS_AUTH_WORK_LOCK LOCKED + DE_IDNTF F | WORKER | 본인 배정 영상 | 1. 라벨링 작업 중 개인정보 노출을 발견한 작업자(WORKER)가 본인 배정 프레임의 라벨링 캔버스 화면에서 '비식별 누락 신고'를 선택한다 <br>2. 신고 사유(1~1000자)를 입력해 신고하면 → 본인 배정 검증(이미 잠긴 영상은 거부) 통과 시 신고가 접수(OPEN)되고, 해당 영상 전체 라벨을 스냅샷 보존 후 안전 삭제하며, 영상이 잠기고(LOCKED) 비식별 완료 표시가 해제(DE_IDNTF='F')된 뒤 검수자에게 알림된다 <br>: 라벨링 캔버스 화면(KLID-AT-SC-005) '비식별 누락 신고' 동선. 신고 후 영상 잠금 상태에서는 저장이 비활성화된다 | · HTTP 201 Created (body=생성된 DEIDENT_REPORT_SN) <br>· `SELECT REPORT_STTS_CD, DATA_RAW_SN, REPORTER_NO, RSN FROM LS_DEIDENT_REPORT WHERE DATA_RAW_SN={대상 RAW_SN}` → REPORT_STTS_CD='OPEN' 1건(신고자·사유 기록) <br>· `SELECT LOCK_TARGET_CD, LOCK_STTS_CD FROM LS_AUTH_WORK_LOCK WHERE DATA_RAW_SN={대상 RAW_SN}` → LOCK_TARGET_CD='RAW'·LOCK_STTS_CD='LOCKED' 1건 <br>· `SELECT DE_IDENT_YN FROM LS_DATA_RAW WHERE RAW_SN={대상 RAW_SN}` → 'F' | |
| KLID-AT-UT-016-02 | 수동 비식별화 완료 resolve 200 + RESOLVED 전이 + LOCK 해제 | WORKER | OPEN 신고 | 1. 신고로 잠긴 영상을 외부 비식별 솔루션으로 수동 비식별화 완료한다 <br>2. 해당 신고에 대해 신고 해제(resolve)를 요청하면 → 본인 배정(검수자는 전체) 검증 통과 후 신고가 '해제됨'(OPEN→RESOLVED, 해제 일시 기록)으로 전이되고 작업락이 동일 트랜잭션에서 원자적으로 해제되어 작업을 재개할 수 있다(이미 처리된 신고 재해제는 거부) <br>: 수동 비식별 완료 후 신고 해제 동선. 별도 신고 관리 화면 없이 잠긴 영상의 신고 해제 요청으로 처리되며, 신고 상태·작업락 해제를 확인한다 | · HTTP 200 OK <br>· `SELECT REPORT_STTS_CD, RESOLVED_DT FROM LS_DEIDENT_REPORT WHERE DEIDENT_REPORT_SN={대상 신고 SN}` → 'RESOLVED' (OPEN→RESOLVED 전이)·RESOLVED_DT 기록 <br>· `SELECT LOCK_STTS_CD, RELEASE_DT, RELEASE_RSN FROM LS_AUTH_WORK_LOCK WHERE DATA_RAW_SN={대상 RAW_SN}` → 'RELEASED' (LOCKED→RELEASED)·RELEASE_RSN='MANUAL_DEIDENT_DONE' | |

## 단위시험 KLID-AT-UT-018

| 단위시험ID | KLID-AT-UT-018 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-11-04 · UC KLID-AT-UC-018] 영상 적재(관제 학습용 설정) | | |
| 관련 컴포넌트 ID | KLID-AT-CO-018 | 관련 프로그램 ID | KLID-AT-IF-016 학습용 적재 |
> 출처: UC-018(영상 적재 관제 학습용 설정)은 D11에 별도 UT 블록이 없으나 백엔드 실테스트(TrainingVideoIngestServiceTest·ControlTrainingVideoScanJobTest)가 존재하여 적재 케이스(실코드 출처·D11 미수록)를 추가했다. 적재(TUS 업로드, D11 UT-23)는 관제 학습용 설정 기반 적재로 대체되는 폐지 예정 경로(CLAUDE.md)라 TUS 업로드(UT-23)는 제외.

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-018-01 | 관제 작업수요 설정 클립을 픽업해 적재 위임한다 | - | 작업수요 설정 클립 | 1. 주기 배치 실행(ControlTrainingVideoScanJob, 1건/분) <br>2. 학습용 지정 클립 픽업 → 적재 위임 <br>3. 신규 적재 건수 확인 <br>: UI 없는 배치·서비스 단위 시험. 관제 공유 DB에서 `MNG_CLIP_MASTER.JOB_DMND_YN='Y'` 클립을 조회(readOnly, controlTransactionManager)해 `TrainingVideoIngestService.scanAndIngest()`가 클립별 독립 트랜잭션(REQUIRES_NEW)으로 `LS_DATA_RAW` 적재 후 `VideoIngestedEvent`(AFTER_COMMIT, 비식별 선두) 발행. `CLIP_ID`/`VMS_CCTV_ID`/`FILE_PATH` blank·중복(`findByVmsClipId`) 클립은 skip. 확인 방법: `scanAndIngest()` 반환 ingested 건수 + DB 조회(`LS_DATA_RAW.VMS_CLIP_ID` 존재) | · `SELECT VMS_CLIP_ID, RAW_FILE_PATH_NM, PRVC_TYPE_CD FROM LS_DATA_RAW WHERE VMS_CLIP_ID={설정클립 CLIP_ID}` → VMS_CLIP_ID=CLIP_ID(UK)·RAW_FILE_PATH_NM=FILE_PATH·PRVC_TYPE_CD=ANONY (1건) <br>· `SELECT DATA_STTS_CD FROM LS_DATA_RAW WHERE VMS_CLIP_ID={설정클립 CLIP_ID}` → DATA_STTS_CD=PENDING (적재 초기 단계) <br>· VideoIngestedEvent 발행 (비식별 트리거) <br>· 중복/blank 식별자 클립은 skip → 신규 적재 건수 미포함 <br>· 검증: `SELECT COUNT(*) FROM LS_DATA_RAW WHERE VMS_CLIP_ID={설정클립 CLIP_ID}` → 1건 + scanAndIngest() 반환 ingested 건수 일치 | |

## 단위시험 KLID-AT-UT-019

| 단위시험ID | KLID-AT-UT-019 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-11-04 · UC KLID-AT-UC-019] 이벤트 마킹(자동/수동) | | |
| 관련 컴포넌트 ID | KLID-AT-CO-019 | 관련 프로그램 ID | KLID-AT-IF-017 이벤트 마킹 |
> 출처: D11 UT-13(마킹). 비식별 영상 자동(프레임간격)/수동(이벤트시점) 마킹 케이스.

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-019-01 | 마킹 자동모드/수동모드 생성 201 | WORKER | 자동·수동 요청 | 1. 마킹 화면 진입(영상 > 영상 처리 현황 > 마킹, KLID-AT-SC-006) <br>2. 비식별 영상 재생 후 자동/수동 마킹 생성 <br>3. [마킹 완료] 실행 <br>: 작업자가 본인 배정 영상의 마킹 화면에서 비식별 영상을 배속 재생(0.25x~4x)하며 마킹 모드(자동/수동)를 선택한다. 자동은 지정 프레임 간격으로, 수동은 단축키(마킹/삭제/완료)로 이벤트 시점을 마킹하며 이벤트명은 관제 클립 메타에서 자동 소싱한다. [마킹 완료] 실행 시 `LS_MARKING` 저장 + `MarkingCompletedEvent` 발행. 본인 미배정 WORKER는 403, 비식별 미완료(`DE_IDENT_YN≠'Y'`)·비-`MARKING_READY` 영상은 차단. 확인 방법: 응답 201 + DB 조회(`LS_MARKING.MARKING_SN` 존재) | · HTTP 201 Created <br>· `SELECT MARKING_SN, EVNT_NM, MARK_MODE_CD, MARK_CN, STTS_CD FROM LS_MARKING WHERE RAW_SN={마킹영상}` → 이벤트명(EVNT_NM)·모드(MARK_MODE_CD)·marks JSON(MARK_CN)·상태(STTS_CD) 저장 (1건) <br>· `SELECT FRME_INTV_NOCS FROM LS_MARKING WHERE RAW_SN={마킹영상}` → AUTO 시 FRME_INTV_NOCS=intervalFrames <br>· MarkingCompletedEvent 발행 (잔여 배치 VLM→프레임추출→오토라벨링 트리거) <br>· 검증: 응답 201 + `SELECT MARKING_SN FROM LS_MARKING WHERE RAW_SN={마킹영상}` → MARKING_SN 존재(1건 이상) | |
| KLID-AT-UT-019-02 | 자동모드 마킹 생성 intervalFrames 기반 marks 자동생성 (30/60프레임 120초) | WORKER | intervalFrames·duration | 1. 마킹 화면에서 자동 모드 선택(KLID-AT-SC-006) <br>2. 자동 마킹 간격(intervalFrames) 지정 후 마킹 생성 <br>3. 생성된 marks 개수 확인 <br>: 자동 모드로 `durationSec`·`intervalFrames`(1~3600) 지정 시 `generateAutoMarks`가 totalFrames=durationSec×30(NATIVE_FPS 고정 가정)을 frameIndex 0부터 intervalFrames 간격으로 `frameIndex < totalFrames`까지 생성(끝 경계 totalFrames 미포함, off-by-one 방지)한다. `durationSec` null/0 이하는 INVALID_INPUT 거부(퇴화 방지). 예: 120초×30fps=3600프레임 → interval 30=120건, interval 60=60건. 확인 방법: DB 조회(`LS_MARKING.MARK_CN` 파싱 marks 배열 개수) | · marks 개수 = totalFrames를 intervalFrames로 나눈 스텝 수(끝 경계 totalFrames 제외) <br>· 예: 120초×30fps=3600프레임 → interval=30 시 120건, interval=60 시 60건 <br>· `SELECT MARK_CN FROM LS_MARKING WHERE RAW_SN={자동마킹영상}` → MARK_CN에 marks JSON 직렬화 저장 <br>· 검증: `SELECT MARK_CN FROM LS_MARKING WHERE RAW_SN={자동마킹영상}` → 파싱한 marks 배열 개수 = interval=30 시 120 / interval=60 시 60 | |

## 단위시험 KLID-AT-UT-021

| 단위시험ID | KLID-AT-UT-021 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-11-08 / RQ-SFR-16 / RQ-SFR-17 · UC KLID-AT-UC-021] 라벨링·이미지/영상 가공(라벨 편집) | | |
| 관련 컴포넌트 ID | KLID-AT-CO-021 | 관련 프로그램 ID | KLID-AT-IF-018 라벨 편집 |
> 출처: D11 UT-14(라벨링) 중 프레임 라벨 편집(임시저장 upsert)·검증 케이스(SAM2 분할/Track은 08-01·08-02 블록에서 다룸).

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-021-01 | bbox 좌표 저장시 AUTO_LBL_YN N 저장(수동) / 오토 라벨 수정시 AUTO_LBL_YN은 Y 유지 | WORKER | 수동·오토 라벨 | 1. 라벨링 캔버스 진입(작업 > 라벨링 캔버스, KLID-AT-SC-005) <br>2. 좌측 도구바 [박스]로 bbox 그리기·수정 <br>3. [저장] 실행 <br>: 작업자가 본인 배정 프레임에서 도구바(선택/박스/폴리곤/분할/추적/삭제)로 객체를 생성·수정하고 우측 패널에서 라벨·좌표·속성을 편집한 뒤 [저장](임시저장 upsert)한다. 신규 라벨(item.id==null)은 `LsDataLbl.createManual`로 `AUTO_LBL_YN='N'` INSERT, 오토 라벨 수정(item.id!=null)은 `updateUserContent`로 좌표/라벨만 UPDATE하고 `AUTO_LBL_YN='Y'` 유지(요청 autoLblYn 무시 — Mass Assignment 차단)한다. 본인 미배정 프레임·재비식별 락(LOCKED)은 차단, 좌표 음수/신규 1000점 초과는 검증·Douglas-Peucker simplify. 검수완료(APPROVED) 후 수정만 `TaskModifiedEvent`(LABEL_UPDATED) 발행. 확인 방법: DB 조회(`LS_DATA_LBL.AUTO_LBL_YN` 값 N/Y) | · 신규 라벨: `SELECT AUTO_LBL_YN FROM LS_DATA_LBL WHERE SRC_SN={대상프레임}` → AUTO_LBL_YN='N' (수동 INSERT) <br>· 오토 라벨 수정: `SELECT AUTO_LBL_YN FROM LS_DATA_LBL WHERE SRC_SN={대상프레임}` → AUTO_LBL_YN='Y' 유지(요청 autoLblYn 무시) <br>· `SELECT POINT_CN FROM LS_DATA_LBL WHERE SRC_SN={대상프레임}` → 좌표 JSON, 1000점 초과 시 Douglas-Peucker simplify 후 저장 <br>· 검수 전 저장은 통지 미발행(TASK_MODIFIED는 APPROVED 후 수정만) <br>· 검증: `SELECT AUTO_LBL_YN FROM LS_DATA_LBL WHERE SRC_SN={대상프레임}` → 신규=N / 오토수정=Y | |

## 단위시험 KLID-AT-UT-022

| 단위시험ID | KLID-AT-UT-022 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-11-10 · UC KLID-AT-UC-022] VLM 시계열 메타 검토 | | |
| 관련 컴포넌트 ID | KLID-AT-CO-022 | 관련 프로그램 ID | KLID-AT-IF-019 시계열 결과 수신·IF-020 메타 검토 (D4 수신 프로그램: VLM결과수신, II-004) |
> 출처: D11 UT-16(외부 VLM 메타 검토 승인/반려).

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-022-01 | 검토 승인 REVIEWER 정상 200 | REVIEWER | 승인 요청 | 1. 라벨링 캔버스 진입(작업 > 라벨링 캔버스, KLID-AT-SC-005) <br>2. 우측 패널에서 시계열 메타(외부 VLM/생성 결과) 검토 <br>3. 검토 승인 처리 <br>: 검수자가 우측 패널의 외부 시계열 분석 결과(메타 텍스트)를 조회·검토(시계열 메타 검토·저장, KLID-AT-SD-022)하고 승인하면 `MetaService.approveReview(metaReviewSn)`가 `LS_DATA_META_REVIEW`를 조회(미존재 404)해 `review.approve`로 `RVW_STTS_CD`를 PENDING→APPROVED 전이하고 `RVW_ID`(검토자 sub)·`RVW_DT` 기록. 미인증 401, 비-REVIEWER 403. 확인 방법: 응답 200 + DB 조회(`LS_DATA_META_REVIEW.RVW_STTS_CD=APPROVED`) | · HTTP 200 <br>· `SELECT RVW_STTS_CD FROM LS_DATA_META_REVIEW WHERE DATA_META_REVIEW_SN={대상 metaReviewSn}` → RVW_STTS_CD PENDING→APPROVED 전이 <br>· `SELECT RVW_ID, RVW_DT FROM LS_DATA_META_REVIEW WHERE DATA_META_REVIEW_SN={대상 metaReviewSn}` → RVW_ID=검토자 sub·RVW_DT=처리 일시 기록 <br>· 검증: 응답 200 + `SELECT RVW_STTS_CD FROM LS_DATA_META_REVIEW WHERE DATA_META_REVIEW_SN={대상 metaReviewSn}` → RVW_STTS_CD=APPROVED | |

## 단위시험 KLID-AT-UT-023

| 단위시험ID | KLID-AT-UT-023 | | |
|---|---|---|---|
| 설명 | [RQ-SFR-11-10 · UC KLID-AT-UC-023] 검수 승인·반려 | | |
| 관련 컴포넌트 ID | KLID-AT-CO-023 | 관련 프로그램 ID | KLID-AT-IF-021 검수 처리 |
> 출처: D11 UT-17(검수 승인/반려).

| 케이스 ID | 케이스 명 | 작업 권한 | 시험 데이터 | 시험항목 및 처리절차 | 예상결과 및 검증방법 | 시험 결과 |
|---------|---------|---------|-----------|---------|--------------|------|
| KLID-AT-UT-023-01 | 승인시 LS_RAW_DATA_STATUS APPROVED | REVIEWER | IN_REVIEW 영상 | 1. 검수 상세 화면 진입(작업 > 검수 목록 > 검수 상세, KLID-AT-SC-019) <br>2. 읽기 전용 캔버스로 라벨 확인 <br>3. [승인] 클릭 <br>: 검수자가 제출 영상의 프레임 라벨을 읽기 전용 캔버스로 확인한 뒤 [승인]하면 `ReviewService.approve(videoId)`가 `LS_RAW_DATA_STATUS`를 IN_REVIEW→APPROVED로 전이(`ReviewStateMachine.verify`)하고 `VersionService.commitApproved`로 라벨 전체 스냅샷(`LS_LABEL_VERSION`, SAVE_REASON=APPROVED) 생성 + `ReviewApprovedEvent`(TASK_COMPLETED 연계) 발행(동일 트랜잭션)한다. `@Version` 낙관적 락으로 동시 승인 시 1건만 성공·나머지 409. 미존재 상태 404. 확인 방법: DB 조회(`LS_RAW_DATA_STATUS.DATA_STTS_CD=APPROVED`) | · `SELECT DATA_STTS_CD FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID={대상영상}` → DATA_STTS_CD IN_REVIEW→APPROVED 전이 <br>· `SELECT COUNT(*) FROM LS_LABEL_VERSION WHERE DATA_RAW_SN={대상영상}` → 스냅샷 1건 생성(검수 확정 학습데이터 버전) <br>· ReviewApprovedEvent 발행(TASK_COMPLETED 통지 연계) <br>· 동시 승인 시 1건만 성공·나머지 409 CONFLICT <br>· 검증: `SELECT DATA_STTS_CD FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID={대상영상}` → DATA_STTS_CD=APPROVED | |
| KLID-AT-UT-023-02 | 반려시 LS_DATA_ISSUE 생성 + 상태 REJECTED + DATA_STTS_CD 업데이트 | REVIEWER | 반려 사유 | 1. 검수 상세 화면 진입(KLID-AT-SC-019) <br>2. 검수 메모에 반려 사유 입력 <br>3. [반려] 클릭 <br>: 검수자가 반려 사유(1~1000자 필수)를 입력하고 [반려]하면 `ReviewService.reject(videoId, reason)`가 직전 반려 이슈를 조회해 계층 연결(`UP_DATA_ISSUE_SN`)하고 `LsDataIssue.create/createWithParent`로 `LS_DATA_ISSUE` INSERT(ISSUE_TYPE_CD=REJECTION, ISSUE_STTS_CD=RESOLVED, ISSUE_RSN=사유) + `LS_RAW_DATA_STATUS`를 IN_REVIEW→REJECTED로 전이(`ReviewStateMachine.verify`)한다. `LsTaskEventLog.reject` 기록 + flush(락 충돌 시 409). 확인 방법: DB 조회(`LS_DATA_ISSUE` 생성 + `LS_RAW_DATA_STATUS.DATA_STTS_CD=REJECTED`) | · `SELECT ISSUE_TYPE_CD, ISSUE_STTS_CD, ISSUE_RSN FROM LS_DATA_ISSUE WHERE DATA_RAW_SN={대상영상}` → ISSUE_TYPE_CD=REJECTION·ISSUE_STTS_CD=RESOLVED·ISSUE_RSN=반려사유 <br>· `SELECT DATA_STTS_CD FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID={대상영상}` → DATA_STTS_CD IN_REVIEW→REJECTED 전이 <br>· `SELECT UP_DATA_ISSUE_SN FROM LS_DATA_ISSUE WHERE DATA_RAW_SN={대상영상}` → 직전 반려 존재 시 상위 이슈 SN 연결 <br>· 검증: `SELECT COUNT(*) FROM LS_DATA_ISSUE WHERE DATA_RAW_SN={대상영상}` → 1건 생성 + `SELECT DATA_STTS_CD FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID={대상영상}` → DATA_STTS_CD=REJECTED | |

---

유스케이스 커버리지: 활성 R1 UC 18종 전부 정상 케이스 보유
