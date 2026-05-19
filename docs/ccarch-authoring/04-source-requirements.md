# SOURCE_REQUIREMENT 노드 정의 — 저작도구 직접 책임 발췌

> RFP `docs/requirements/[붙임2]  제안요청서(수정)_260303.pdf` 의 요구사항 중 저작도구 서브시스템이 직접 책임지는 부분만 발췌. 영상 수집/중계 모니터링/시스템 연동/침수 시범/관제지원시스템 자체 기능/일반 개인정보 보호조치/외부 데이터 제공 같은 본 도구 책임 외 항목은 등록하지 않는다.

## SR-01. SFR-03 — 시계열 메타데이터 생성·관리

```json
{
  "type": "SOURCE_REQUIREMENT",
  "title": "[SFR-03] 시계열 메타데이터 생성 및 관리 기능 개발",
  "content": "프레임 시퀀스 단위 자연어 설명·객체/행동 변화·환경 조건 변화를 시계열 메타로 관리.\n\n**본 도구 측 책임**\n- 본 도구가 외부 VLM 서비스를 호출하여 시계열 메타 생성 (영상 단위 일반 메타는 외부 책임으로 본 도구 DB 에 적재되므로 호출 대상 아님)\n- 결과를 `LS_DATA_META` + `LS_DATA_META_REVIEW` 에 적재\n- REVIEWER 가 검토·승인·반려",
  "attrs": {"sourceDoc": "제안요청서(수정)_260303.pdf", "sourcePage": "p.29", "priority": "HIGH"},
  "_handle": "sr-sfr-03"
}
```

## SR-02. SFR-06/07 — 생성형 AI 학습데이터 / 데이터 증강 (검수 측면)

```json
{
  "type": "SOURCE_REQUIREMENT",
  "title": "[SFR-06/07] 생성형 AI 학습데이터 제작 및 데이터 증강 자동화 — 검수 측면",
  "content": "**본 도구 측 책임**\n- 검수 완료 영상에 대한 증강 요청을 외부 생성형 AI 시스템에 인계\n- 외부 시스템이 생성한 증강 영상·이미지 결과를 본 도구가 수신\n- 라벨 무결성 (`LS_DATA_AUG_LBL_MAP`) 검증\n- 사용자가 학습데이터 활용 여부를 선택하도록 검수 UI 제공\n\n**본 도구 책임 외**: 생성형 AI 모델 본체(QWEN IMAGE, WAN2.2 등), 프롬프트 UI",
  "attrs": {"sourceDoc": "제안요청서(수정)_260303.pdf", "sourcePage": "p.31", "priority": "HIGH"},
  "_handle": "sr-sfr-06-07"
}
```

## SR-03. SFR-08 — 학습데이터 저작도구 고도화

```json
{
  "type": "SOURCE_REQUIREMENT",
  "title": "[SFR-08] 학습데이터 저작도구 고도화 기능 구현",
  "content": "학습데이터 제작 편의성 및 효율성 증대를 위한 저작도구 추가기능.\n\n**본 도구 측 책임**\n- 라벨링 정확도 향상: 사용자가 지정한 객체 추적·자동 위치/경계 갱신, 외곽 경계 자동 밀착, 라벨링 정밀도 조절\n- 동일 학습데이터의 버전 관리·변경이력 추적·복구 (Gitea 자동 커밋)\n- 라벨 마스터 풀(CVAT-Like) 관리\n- 작업 배정·재배정·검수 워크플로우 (저작도구 사용 흐름)",
  "attrs": {"sourceDoc": "제안요청서(수정)_260303.pdf", "sourcePage": "p.32", "priority": "HIGH"},
  "_handle": "sr-sfr-08"
}
```

## SR-04. SFR-09 — 개인정보 비식별 처리

```json
{
  "type": "SOURCE_REQUIREMENT",
  "title": "[SFR-09] 영상 및 이미지 개인정보 비식별 처리 기능 개발",
  "content": "**본 도구 측 책임**\n- 외부 비식별 솔루션(분리발주)을 본 도구가 호출하여 모든 영상에 대해 비식별 처리\n- 원본과 비식별본을 별도 경로로 동시 저장\n- 비식별 처리 결과·이력 관리 및 REVIEWER 검토 UI\n- 비식별 솔루션 옵션을 관제지원시스템 UI 로 노출\n\n**본 도구 책임 외**: 비식별 솔루션 본체",
  "attrs": {"sourceDoc": "제안요청서(수정)_260303.pdf", "sourcePage": "p.32", "priority": "HIGH"},
  "_handle": "sr-sfr-09"
}
```

## SR-05. SFR-15 — 외부 포털에서 저작도구 활용 기능 제공

```json
{
  "type": "SOURCE_REQUIREMENT",
  "title": "[SFR-15] 외부 포털에서 진입한 사용자에게 저작도구 활용 기능 제공",
  "content": "SFR-15 의 본체는 외부 사용자 서비스 포털 구축이며 회원가입·로그인·업로드·다운로드·공지사항 등 포털 자체 기능은 외부 책임이다.\n\n**본 도구 측 책임**\n- 포털 서버가 인증한 PORTAL_USER 가 본 도구에 진입했을 때 저작도구 기능(간편 라벨링) 제공\n- 본인 데이터에 대한 라벨 조회·수정 (라벨링 캔버스 활용)\n- 포털 채널 가드 + 역할 가드 (`ChannelGuard('PORTAL') + RoleGuard([PORTAL_USER])`)\n\n**본 도구 책임 외**\n- 회원가입·로그인·인증·마이페이지·탈퇴\n- 영상 업로드(TUS 등)\n- 학습데이터 다운로드·D-day 배지·만료 처리 UI\n- 공지사항·FAQ·매뉴얼·사용 통계·다운로드 현황",
  "attrs": {"sourceDoc": "제안요청서(수정)_260303.pdf", "sourcePage": "p.37-39", "priority": "HIGH"},
  "_handle": "sr-sfr-15"
}
```

## SR-06. SFR-16/17 — 학습데이터셋 산출물

```json
{
  "type": "SOURCE_REQUIREMENT",
  "title": "[SFR-16/17] AI 기반 CCTV 고도화 학습데이터셋 구축 (이미지·영상)",
  "content": "**산출 목표**\n- 이미지 학습데이터 10만장 (바운딩박스/폴리곤/세그멘테이션 + 메타정보, 가명처리)\n- 영상 학습데이터 5,000건 (이벤트 상황 포함 30초 이상 1건, **CoT 캡션 데이터**)\n- 외부전문 시험기관 샘플링 품질검사\n\n**본 도구 측 책임**: 라벨링·정제·가공·검수의 전 과정 본 도구에서 수행",
  "attrs": {"sourceDoc": "제안요청서(수정)_260303.pdf", "sourcePage": "p.39-41", "priority": "HIGH"},
  "_handle": "sr-sfr-16-17"
}
```

## SR-07. NFR — 응답시간·반응형·보안 (PER-02 + SIR-04 + SER-01 묶음)

```json
{
  "type": "SOURCE_REQUIREMENT",
  "title": "[PER-02 + SIR-04 + SER-01] 핵심 비기능 요구사항 (응답시간/반응형/보안)",
  "content": "**PER-02** 페이지별 응답속도 3초 이하 (10초 이상 작업은 사전 알림). 시스템 자원 평균사용률 80% 이하, DB 커넥션·메모리 누수 방지.\n\n**SIR-04** 반응형 웹: 1024×768 저해상도부터 1920×1080 까지 지원, CSS 단위 지정, 수평스크롤 미사용. WCAG 2.1 + 한국형 웹 콘텐츠 접근성 지침 + 전자정부 웹사이트 품질관리 지침. 멀티플랫폼·멀티브라우저 (Active-X 불가).\n\n**SER-01** 개발 시 보안: 로그인 실패 5회 이상 차단, 동일 사용자 동시 로그인 차단, 우회 접근 차단, 중요정보·개인정보 전송 구간 암호화, 시크릿 하드코딩 금지, 권한별 페이지 구분, 개발/테스트 서버 외부 노출 차단.",
  "attrs": {"sourceDoc": "제안요청서(수정)_260303.pdf", "sourcePage": "p.42, p.47, p.79", "priority": "HIGH"},
  "_handle": "sr-nfr-core"
}
```

---

## 등록 순서

REQUIREMENT 보다 먼저 등록 (DERIVES_FROM 링크의 target).

1. sr-sfr-03
2. sr-sfr-06-07
3. sr-sfr-08
4. sr-sfr-09
5. sr-sfr-15
6. sr-sfr-16-17
7. sr-nfr-core
