# WAS 설정 예시 (WAR 반입 형상)

배포 형상은 **외부 WAS(Tomcat 10.1) 에 `api.war` 반입**이다(@design DEPLOY-001 · RUNBOOK-001).
WAS 자체는 **설치 대상이 아니라 전제**다 — 고객 장비에 이미 있는 것을 쓰고, 이 패키지는 설치하지 않는다.

이 디렉터리는 **그 WAS 에 무엇을 어떻게 넣어야 하는지의 예시**다. 규정 내용(무엇을 왜 옮기는가)의
정본은 [`docs/10-was-settings.md`](../../docs/10-was-settings.md) 이며, 여기 파일들은 그것을
**옮겨 적기 좋은 형태로 구체화한 사본**이다. 값이 갈리면 그 문서가 이긴다.

## 파일

| 파일 | 대상 위치(예) | 무엇 |
|---|---|---|
| `context-api.xml.example` | `<WAS_BASE>/conf/Catalina/localhost/api.xml` | `api.war` 컨텍스트 배포 서술자 |
| `setenv.sh.example` | `<WAS_HOME>/bin/setenv.sh` | `CATALINA_OPTS` — 설정 파일 위치·프로파일·JVM 옵션 |
| `server-connector.xml.example` | `<WAS_BASE>/conf/server.xml` 의 커넥터 | 업로드 본문 한도·스레드 예산·비동기 타임아웃 |

## 쓰는 법

1. **현장값을 먼저 정한다.** 아래 자리표시자는 **우리가 모르는 값**이다 — 지어내지 말고
   WAS 운영 주체에게 확인한다. 확인한 값은 `/etc/klid/was.env` 에 적어 둔다
   (→ [`docs/09-operations-runbook.md`](../../docs/09-operations-runbook.md) §0-1).

   | 자리표시자 | 뜻 |
   |---|---|
   | `<WAS 유닛명>` | `systemctl` 로 다루는 WAS 서비스 유닛명 |
   | `<WAS_HOME>` | `CATALINA_HOME` — `bin/setenv.sh` 가 놓이는 곳 |
   | `<WAS_BASE>` | `CATALINA_BASE` — `conf/`·`webapps/`·`logs/` 가 있는 곳(분리하지 않았으면 `<WAS_HOME>` 과 같다) |
   | `<WAS 실행 계정>` | WAS 프로세스의 OS 계정 |

2. **예시를 그대로 덮어쓰지 않는다.** WAS 는 다른 애플리케이션도 함께 돌고 있을 수 있다.
   기존 파일이 있으면 **해당 항목만 병합**한다 — 특히 `server.xml` 은 통째로 교체하면
   다른 서비스가 죽는다.

3. **반영 확인은 기동 성공이 아니다.** `server.*` 설정이 WAR 배포에서 무시되는 것은
   **오류를 내지 않는다** — 기동도 일반 요청도 정상이다. **대용량 업로드를 실제로 1회 수행**해
   통과를 확인해야 이 단계가 끝난다(정본 문서의 점검 체크리스트 마지막 항목).

## 지키지 않으면 조용히 깨지는 것 3가지

- **`api.war` 의 파일명을 바꾸지 않는다.** WAR 배포에서 **컨텍스트 경로는 파일명이 정하고**,
  현재 API 주소가 전부 `/api` 하위다. `server.servlet.context-path` 는 내장 서버 전용이라
  이 형상에서 적용되지 않는다. 컨텍스트 서술자를 쓰는 경우에는 **서술자 파일명(`api.xml`)** 이
  같은 역할을 한다.
- **`RemoteIpValve`(및 동등한 프록시 IP 치환 밸브)를 넣지 않는다.** 넣으면 신뢰 프록시 대조와
  시도 횟수 제한이 헤더 한 줄로 우회된다(CWE-348/CWE-307). 앱 쪽 가드는 **WAS 설정 파일을 볼 수 없어**
  이 경우를 잡지 못한다 — 사람이 확인해야 하는 항목이다.
- **비밀값을 이 디렉터리의 파일에 적지 않는다.** DB 비밀번호·시크릿은 전부
  `/etc/klid/application.properties` 에 있고, WAS 는 그 파일의 **위치만** 알면 된다.
