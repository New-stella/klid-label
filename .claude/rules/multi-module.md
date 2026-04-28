# Multi-Module Rules

Gradle 멀티모듈 프로젝트 설계 및 코드 생성 시 아래 규칙을 적용합니다.

## 적용 기준

| 감지 기준 | 판정 |
|-----------|------|
| `settings.gradle`에 `include` 2개 이상 | 멀티모듈 프로젝트 |
| 루트에 `build.gradle` + 하위 디렉토리에 각각 `build.gradle` | 멀티모듈 프로젝트 |

단일 모듈 프로젝트에는 적용하지 않습니다.

## 모듈 분리 기준

### 분리 O
- 여러 서비스/앱에서 공통으로 사용하는 코드 (공통 유틸, 공통 예외, 도메인 모델)
- 독립적으로 배포되는 단위 (API 서버, 배치 서버, 어드민 서버)
- 인프라 레이어 (JPA 구현체, Redis, Kafka 등 기술 의존성 격리)

### 분리 X
- 단순히 패키지로 분리 가능한 것 (모듈 오버엔지니어링)
- 모듈 간 강하게 결합된 코드 (분리 효과 없음)
- 3개 미만의 작은 프로젝트

## 권장 모듈 구조

```
project-root/
├── settings.gradle
├── build.gradle                  # 공통 설정 (allprojects, subprojects)
├── core/                         # 공통 도메인, 예외, 유틸
│   └── build.gradle
├── domain/                       # 도메인 모델, Repository 인터페이스
│   └── build.gradle
├── infra/                        # JPA, Redis, Kafka 구현체
│   └── build.gradle
├── api/                          # REST API 서버 (Spring Boot 실행 모듈)
│   └── build.gradle
└── batch/                        # 배치 서버 (선택)
    └── build.gradle
```

## 의존성 방향 규칙 (Critical)

```
api     →  domain  →  core
batch   →  domain  →  core
infra   →  domain
api     →  infra
batch   →  infra
```

- 단방향만 허용 (상위 → 하위)
- **순환 의존성 절대 금지** (`core`가 `api`를 참조하는 등)
- `core`는 Spring 의존성 최소화 (순수 Java 권장)
- `domain`은 JPA 어노테이션 허용, 외부 API 클라이언트 금지

## build.gradle 설정 패턴

```groovy
// 루트 build.gradle
subprojects {
    apply plugin: 'java'
    apply plugin: 'org.springframework.boot'
    apply plugin: 'io.spring.dependency-management'

    group = 'com.company'
    sourceCompatibility = '17'

    dependencies {
        testImplementation 'org.springframework.boot:spring-boot-starter-test'
    }
}

// 실행 모듈이 아닌 라이브러리 모듈 (api.jar 생성 안 함)
bootJar { enabled = false }
jar     { enabled = true  }
```

```groovy
// api/build.gradle — 실행 모듈
bootJar { enabled = true }
jar     { enabled = false }

dependencies {
    implementation project(':domain')
    implementation project(':infra')
}
```

## 모듈 간 참조 규칙

- 모듈 간 참조: `implementation project(':module-name')`
- 순환 참조 발생 시 → `core` 모듈로 공통 부분 추출
- `api` 모듈이 `batch` 모듈을 참조하거나 그 반대도 금지

## 패키지 네이밍

- 각 모듈의 base package: `com.company.{project}.{module}`
- 예: `com.company.shop.core`, `com.company.shop.domain`, `com.company.shop.api`

## 금지 패턴

- 하위 모듈(`core`, `domain`)에서 상위 모듈(`api`) 참조
- 모든 모듈에 `bootJar { enabled = true }` (실행 모듈만 허용)
- `domain` 모듈에서 외부 API 클라이언트 직접 호출 (infra로 분리)
- 모듈 경계를 넘는 Entity 직접 참조 (ID 참조 또는 DTO 변환 사용)
