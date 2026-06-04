# 16. 보안 정책

> 출처: 아키텍처설계서 §2, 통합설계서 §18, 코드규격서(보안 관리)
> 관련: [03 역할·권한](03-roles-permissions.md) · [15 데이터베이스](15-database.md)

## 16.1 사용자 인증

- **GPKI(정부 공개키 기반구조)** 연동 인증 적용
- 비밀번호 보안 정책 강제 적용
- 역할 기반 접근 제어 (RBAC)

## 16.2 네트워크 · 입력 보안

- 모든 사용자 입력 값 **서버 사이드 검증**
- XSS 공격 방지 처리
- 파일 업로드 검증 (유형, 크기, 악성코드 검사)
- HTTPS/TLS 암호화 통신

## 16.3 데이터 보안

- `crontab` 기반 정기 데이터 백업 + 백업 데이터 암호화
- 이력·백업 데이터는 시스템과 별개의 **NAS 저장소**에 보관
- 감사 로깅 — 모든 접근·작업 기록

## 16.4 개인정보 보호

- 비식별화 작업 **다른 라벨링 전 우선 수행** 강제 ([07 §블러](07-labeling-tools.md#블러--비식별화))
- 추적 플래그 3종:
  - `DE_IDNTF_YN` — 개인정보 포함 여부
  - `PRVC_YN` — 비식별(가명처리) 여부
  - `AI_CRT_YN` — AI 생성 여부(합성 데이터 식별)
- 개인정보보호 관련 법령 준수

## 16.5 코드 보안 (코드규격서)

- OWASP Top 10 기반 취약점 검사 + SAST
- 의존성 보안 스캔 (Snyk, Dependabot)
- 시크릿 관리 (환경 변수, Vault)
- 접근 제어(RBAC) + 감사 로깅 + 암호화 통신

> **v2 참고**: v2 보안 기준은 [`.claude/rules/security.md`](../../.claude/rules/security.md)(OWASP Top 10 2025, CWE Top 25, Fortify/CodeQL)이며 훨씬 상세하다. 인증은 **GPKI가 아니라 관제/포털 발급 JWT 인계**(`JwtAuthenticationFilter`), 독립 로그인 UI 없음. 비식별은 외부 비식별 서버를 `DeidentifyClient`(Resilience4j)로 호출. 로그 마스킹은 Logback MaskingPatternLayout.
