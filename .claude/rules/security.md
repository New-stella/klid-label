# Security Rules

코드 생성 및 리뷰 시 아래 보안 규칙을 항상 확인합니다.
모든 코드는 **Fortify Static Code Analyzer** 및 **GitHub Advanced Security (CodeQL)** 점검을 통과해야 합니다.

> 기준: **OWASP Top 10 (2025)**, **OWASP API Security Top 10 (2023)**, **CWE Top 25 (2025)**
> CLAUDE.md "보안 원칙" 섹션의 기본 규칙은 여기서 반복하지 않습니다.

## Fortify / GitHub CodeQL 필수 통과 항목

### Injection 계열 (Critical) — OWASP A05:2025
- **SQL Injection** (CWE-89): 문자열 직접 연결 금지 → JPA 파라미터 바인딩 또는 QueryDSL 필수
- **Command Injection** (CWE-78): `Runtime.exec()`, `ProcessBuilder`에 사용자 입력 직접 전달 금지 → allowlist 기반 명령 매핑
- **Log Injection** (CWE-117): 사용자 입력 로그 출력 시 개행(`\n`, `\r`) 문자 제거 필수
- **XSS** (CWE-79): 사용자 입력을 응답에 그대로 반환 금지, HTML 이스케이프 필수
- **LDAP Injection** (CWE-90): LDAP 쿼리에 사용자 입력 직접 삽입 금지
- **XPath Injection** (CWE-643): XPath 표현식에 사용자 입력 직접 삽입 금지
- **Expression Language Injection** (CWE-917): SpEL, OGNL 등 표현식에 사용자 입력 삽입 금지
- **Template Injection** (CWE-1336): 서버 사이드 템플릿(Thymeleaf, Freemarker)에 사용자 입력 직접 삽입 금지

### SSRF (Critical) — OWASP A01:2025 (Broken Access Control에 흡수), CWE-918
- 사용자 입력으로 URL/호스트를 구성하여 서버 측 요청 금지
- 허용 URL allowlist 기반 검증 필수
- 내부 네트워크(127.0.0.1, 10.x, 172.16~31.x, 192.168.x, metadata API 169.254.169.254) 접근 차단
- DNS rebinding 방어: 요청 전 IP 해석 후 검증

### Insecure Deserialization (Critical) — OWASP A08:2025 (Data Validation Failures), CWE-502
- `ObjectInputStream.readObject()` 사용자 입력 직접 역직렬화 금지
- 신뢰할 수 없는 데이터의 Java 직렬화 금지 → JSON(Jackson/Gson)으로 대체
- Jackson `enableDefaultTyping()` / `@JsonTypeInfo(use=CLASS)` 금지 → 명시적 서브타입 등록
- `SnakeYAML` 사용 시 `new Yaml(new SafeConstructor())` 필수

### XML External Entity — XXE (Critical) — CWE-611
- XML 파서 외부 엔티티 비활성화 필수: `FEATURE_SECURE_PROCESSING = true`
- `DocumentBuilderFactory`: `setExpandEntityReferences(false)`, `setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`
- `SAXParserFactory`, `XMLInputFactory`: 외부 엔티티/DTD 비활성화

### Broken Access Control (Critical) — OWASP A01:2025, CWE-862/863
- 모든 API 엔드포인트에 인증/인가 처리 명시
- **IDOR (Insecure Direct Object Reference)**: 리소스 접근 시 소유자/권한 검증 필수 (ID만으로 접근 금지)
- 수평 권한 상승: 다른 사용자의 리소스에 접근하지 못하도록 비즈니스 레벨 권한 검증
- 수직 권한 상승: 관리자 기능에 역할 기반 접근 제어 (RBAC) 필수
- `@PreAuthorize`, `@Secured` 또는 커스텀 권한 검증 로직 사용

### Mass Assignment (High) — CWE-915, OWASP API3:2023
- Entity를 RequestBody로 직접 바인딩 금지 → DTO 사용 필수
- DTO에 허용 필드만 명시적으로 정의 (role, isAdmin 등 민감 필드 바인딩 차단)
- `@JsonIgnoreProperties(ignoreUnknown = true)` 설정 권장

### Credential 계열 (Critical) — OWASP A07:2025
- **Hardcoded Password/Key** (CWE-798): 코드에 비밀번호, API키, JWT 시크릿 하드코딩 절대 금지
- **Password in Configuration File**: application.yml에도 평문 비밀번호 금지 → 환경변수 `${VAR}` 사용
- **Insufficient Credential Protection**: 비밀번호 평문 저장 금지 → bcrypt/Argon2id 해싱 필수

### Privacy / Information Leakage (High) — OWASP A01:2025
- **Privacy Violation** (CWE-359): 민감 정보(비밀번호, 주민번호, 카드번호, 전화번호) 로그 출력 금지
- **System Information Leak** (CWE-209): `printStackTrace()` 사용 금지 → 로거 사용
- **Error Message Information Leak**: 예외 메시지에 스택트레이스, 내부 경로, DB 스키마 노출 금지
- **Server Header Leak**: 응답 헤더에 서버/프레임워크 버전 정보 노출 금지 (`Server`, `X-Powered-By` 제거)

### Input Validation (High) — OWASP A08:2025 (Data Validation Failures)
- 모든 Controller 메서드 파라미터에 `@Valid` 적용
- 사용자 입력은 반드시 검증 후 사용 (길이, 형식, 범위)
- 파일 업로드: 확장자 allowlist, 크기 제한, MIME 타입 검증, 파일명 정규화
- **Path Manipulation** (CWE-22): 사용자 입력으로 파일 경로 구성 시 `..` 경로 순회 차단 → `Path.normalize()` + 기준 경로 검증
- **Open Redirect** (CWE-601): 사용자 입력 기반 리다이렉트 시 allowlist 도메인 검증 필수
- **Header Manipulation** (CWE-113): HTTP 응답 헤더에 사용자 입력 삽입 시 CRLF 문자 제거

### Resource Management (High)
- InputStream, Connection, Statement 등 반드시 try-with-resources로 닫기
- DB 커넥션, HTTP 클라이언트 등 외부 리소스 해제 보장
- **Unrestricted Resource Consumption** (OWASP API4:2023): 요청 크기 제한, 페이징 필수, 무제한 조회 금지

### Security Misconfiguration (High) — OWASP A02:2025
- 운영 환경 디버그 모드 비활성화 (`debug=false`, `devtools` 제거)
- 불필요한 HTTP 메서드 비활성화 (TRACE, OPTIONS 등)
- CORS: `@CrossOrigin(origins = "*")` 무분별한 허용 금지 → 도메인 명시
- Security 응답 헤더 필수: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Strict-Transport-Security`
- Actuator 엔드포인트 운영 환경 최소 노출 (health만)

### Cryptography (Medium) — OWASP A04:2025
- MD5, SHA-1 사용 금지 → SHA-256 이상 사용
- **비밀번호 해싱**: bcrypt(cost 12+) 또는 Argon2id 사용 필수 (SHA/PBKDF2 비권장)
- `java.util.Random` 보안 용도 사용 금지 → `SecureRandom` 사용
- 암호화 없이 민감 데이터 전송/저장 금지
- 대칭 암호: AES-256-GCM 권장 (ECB 모드 금지)
- 비대칭 암호: RSA 2048비트 이상 또는 ECDSA P-256 이상

### JWT 보안 (Medium)
- `alg: none` 허용 금지 → 알고리즘 명시적 지정 필수
- 시크릿 키 256비트(HS256) 또는 RSA 2048비트(RS256) 이상
- 만료 시간(`exp`) 필수 설정, 리프레시 토큰 분리
- JWT payload에 민감 정보(비밀번호, 주민번호) 포함 금지
- 토큰 블랙리스트/리보케이션 전략 구현 (로그아웃/비밀번호 변경 시)

### Race Condition (Medium) — CWE-362
- 파일 TOCTOU: 원자적 연산 사용
- 공유 자원 접근 시 동기화 처리
- **비즈니스 로직 Race Condition**: 재고 차감, 포인트 사용 등 동시성 제어 필수 (낙관적/비관적 잠금)

### Code Quality (Medium)
- null 가능성 있는 반환값 체크 필수 → Optional 활용
- 도달 불가능한 코드 제거
- volatile 없는 Double-Checked Locking 금지
- **Unsafe Reflection** (CWE-470): 사용자 입력으로 클래스명/메서드명 동적 로드 금지

### Software Supply Chain Failures (High) — OWASP A03:2025 (신규)
- 의존성 무결성 검증: `package-lock.json`, `gradle.lockfile` 등 lock 파일 커밋 필수
- 취약한/deprecated 라이브러리 사용 금지 → 정기적 CVE 스캔 (Dependabot, Snyk)
- 빌드 파이프라인에 의존성 검증 단계 포함
- 내부 패키지 레지스트리 사용 시 네임스페이스 혼동 공격(dependency confusion) 방어
- typosquatting 주의: 패키지명 오타로 인한 악성 패키지 설치 방지

### Mishandling of Exceptional Conditions (Medium) — OWASP A10:2025 (신규)
- 예외 발생 시 안전한 기본값으로 동작 (fail-secure, fail-closed)
- catch 블록에서 예외 무시 금지 → 최소한 로깅 필수
- 인증/인가 실패 시 반드시 거부 처리 (fail-open 금지)
- 리소스 고갈 시 graceful degradation 전략 구현
- 예외 메시지에 내부 구현 세부사항 노출 금지

### 프론트엔드 보안 (Medium)
- `v-html` / `dangerouslySetInnerHTML` 사용 금지 → 불가피 시 DOMPurify로 sanitize
- `eval()`, `new Function()`, `setTimeout(string)` 사용 금지
- `postMessage` 수신 시 `event.origin` 검증 필수
- localStorage/sessionStorage에 토큰 저장 시 XSS 위험 인지 → httpOnly 쿠키 권장
- CSP(Content Security Policy) 헤더 설정 권장

## OWASP Top 10 (2025) vs 2021 변경 사항

| 2025 | 항목 | 2021 대비 변경 |
|------|------|---------------|
| A01:2025 | Broken Access Control | 유지 (SSRF가 여기로 흡수) |
| A02:2025 | Security Misconfiguration | 5→2위 상승 |
| A03:2025 | Software Supply Chain Failures | **신규** (A06:2021 확장) |
| A04:2025 | Cryptographic Failures | 2→4위 |
| A05:2025 | Injection | 3→5위 |
| A06:2025 | Insecure Design | 유지 |
| A07:2025 | Authentication Failures | 유지 (명칭 변경) |
| A08:2025 | Data Validation Failures | **변경** (A08:2021 대체) |
| A09:2025 | Security Logging & Alerting Failures | 유지 (명칭 변경) |
| A10:2025 | Mishandling of Exceptional Conditions | **신규** (A10:2021 SSRF 대체) |

## OWASP API Security Top 10 (2023) 매핑

| OWASP API | 항목 | 이 문서 참조 |
|-----------|------|-------------|
| API1:2023 | Broken Object Level Authorization | Broken Access Control (IDOR) |
| API2:2023 | Broken Authentication | 인증/인가, JWT 보안 |
| API3:2023 | Broken Object Property Level Authorization | Mass Assignment |
| API4:2023 | Unrestricted Resource Consumption | Resource Management |
| API5:2023 | Broken Function Level Authorization | Broken Access Control (RBAC) |
| API6:2023 | Unrestricted Access to Sensitive Business Flows | Race Condition, Rate Limiting |
| API7:2023 | Server Side Request Forgery | SSRF |
| API8:2023 | Security Misconfiguration | Security Misconfiguration |
| API9:2023 | Improper Inventory Management | 미사용 API 엔드포인트 제거 |
| API10:2023 | Unsafe Consumption of APIs | 외부 API 응답 검증, SSRF |

## 민감 정보 목록 (로그 출력 절대 금지)
- 비밀번호, 토큰, API 키, 시크릿
- 주민등록번호, 카드번호, CVV, 계좌번호
- 전화번호, 이메일 (마스킹 필수: `010-****-1234`)
- 요청 Body 전체 (민감 필드 포함 가능)
- 세션 ID, JWT 토큰 전문

## 인증/인가
- 모든 API 엔드포인트 인증/인가 처리 명시
- SecurityConfig에서 허용 경로 명시적으로 관리 (화이트리스트 방식)
- JWT 토큰 만료 시간 적절히 설정 (access 15~30분, refresh 7~14일)
- `@CrossOrigin(origins = "*")` 무분별한 CORS 허용 금지
- 비밀번호 정책: 최소 8자, 복잡도 검증, bcrypt/Argon2id 해싱
- 로그인 실패 횟수 제한 (계정 잠금 또는 지수 백오프)
- 세션 고정 공격 방지: 로그인 성공 시 세션 ID 재생성

## 의심 패턴 감지 시 즉시 경고
- `password = "..."` 하드코딩
- `"SELECT ... " + variable` SQL 문자열 연결
- `System.out.println` 민감 정보 출력
- `e.printStackTrace()` 사용
- `new Random()` 보안 목적 사용
- `MessageDigest.getInstance("MD5")` 또는 `"SHA-1"` 사용
- `Runtime.getRuntime().exec(userInput)` 명령 실행
- `ObjectInputStream.readObject()` 사용자 입력 역직렬화
- `new URL(userInput).openConnection()` SSRF 위험
- `response.sendRedirect(userInput)` Open Redirect
- `enableDefaultTyping()` Jackson 다형성 역직렬화
- `@JsonTypeInfo(use = Id.CLASS)` 또는 `Id.MINIMAL_CLASS`
- `eval()`, `new Function()` 동적 코드 실행
- `dangerouslySetInnerHTML` / `v-html` XSS 위험
