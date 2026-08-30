# 08. [대상 서버] 인터넷 없이 소스에서 빌드

폐쇄망 타깃(레드햇 엔터프라이즈 리눅스 8.9, x86_64)에서 **인터넷 없이 소스를 직접 재빌드**하는 절차다.
사전 빌드 아티팩트(jar/dist) 대신, 번들된 **소스 + 오프라인 빌드 키트**로 타깃에서 빌드한다.

> 외부 네트워크 호출 0. 모든 빌드 도구·의존성 캐시·소스는 `deploy/onprem/` 안에 번들되어 있다.

> ★★ **빌드 키트는 2026-08-30 부터 기본 반입 대상이 아니다** (사용자 확정, 구속).
> 현장 재빌드 요구가 없음을 확인했고, 빼면 라이선스 표면(JDK full 한 벌 · node_modules 645 패키지,
> MPL-2.0 3건)·매체 용량·보안 표면이 함께 줄어든다.
> **따라서 이 문서의 절차는 `WITH_BUILDTOOLS=1 ./scripts/package.sh` 로 수집한 패키지에서만 동작한다.**
> 키트가 없는 패키지에서 `build-from-source.sh` 를 실행하면 **손상이 아니라 형상**임을 안내하고
> 종료한다(사전 빌드 아티팩트로 설치하거나 키트를 포함해 재수집하라는 안내).
> ⚠ 구 서술 폐기(2026-08-30) — 빌드 키트가 **기본 포함**이라는 전제.

---

## 사전 빌드 아티팩트 vs 소스 빌드 — 무엇을 선택하나

| 항목 | 사전 빌드 아티팩트(기본) | 소스 빌드(이 문서) |
|------|--------------------------|---------------------|
| 절차 | `sudo ./scripts/install.sh` 만 실행 | `build-from-source.sh` → `install.sh` |
| 번들 필요물 | `artifacts/{backend,frontend}` | `src/` + `buildtools/`(빌드 키트) |
| 타깃 빌드 시간 | 없음(즉시 설치) | gradle/npm 빌드 시간 소요 |
| 용량 | 작음 | 큼(JDK full·gradle-home·node_modules) |
| 언제 | **대부분의 경우 권장** | 납품처가 "소스에서 직접 빌드" 요구 / 아티팩트 신뢰 검증 / 소스 패치 후 재빌드 |

> 둘 다 결국 동일한 **`artifacts/backend/api.war`**(반입 정본) + `artifacts/backend/klid-backend.jar`
> (개발/베어메탈용) + `artifacts/frontend/dist` 를 만들고, 그 다음 `install.sh` 가 이를 배치한다.
> 차이는 "아티팩트를 빌드머신에서 받았나, 타깃에서 만들었나" 뿐이다.
> ⚠ backend 는 배치까지만이다 — **WAS 배포와 WAS 설정 이관은 사람이** 한다(03·10 문서).

---

## 전제 — 빌드 키트가 번들되어 있어야 함

빌드머신에서 `WITH_BUILDTOOLS=1 ./scripts/package.sh`(또는 `60-collect-buildtools.sh` 직접 실행)가
다음을 채워둬야 한다. **기본 수집에는 포함되지 않는다.**
(상세: [docs/02-build-package.md](02-build-package.md) "오프라인 빌드 키트(60단계)").

```
deploy/onprem/
├── buildtools/
│   ├── jdk/      OpenJDK17U-jdk_x64_linux_hotspot_*.tar.gz  (javac 포함)
│   ├── node/     node-v20.*-linux-x64.tar.xz
│   ├── gradle/   gradle-8.8-bin.zip
│   ├── gradle-home/   populated GRADLE_USER_HOME(전 의존 jar 캐시)
│   └── frontend-node_modules.tar.gz   (⚠ Linux x64 에서 수집된 것)
└── src/{backend,frontend,ai-server}   빌드용 소스
```

> **⚠ node_modules 는 반드시 Linux x64 에서 수집된 것**이어야 한다(esbuild 등 plat 바이너리).
> mac 에서 만든 키트면 `frontend-node_modules.tar.gz` 가 비어 있거나 누락이다 — 02 문서의
> "el8 컨테이너에서 node_modules 만 채우기" 예시로 보강하라.

---

## 실행

```bash
cd deploy/onprem
sudo ./scripts/install/build-from-source.sh
```

스크립트가 하는 일(전부 로컬, 외부 호출 0):

1. **빌드 도구 해제 + 무결성 검증**
   - `buildtools/{jdk,node,gradle}` → `/opt/klid/buildtools/{jdk,node,gradle}` 로 해제
     (각 tarball/zip 을 `versions.sh` 의 공식 SHA256 과 대조 — fail-closed).
   - `buildtools/gradle-home` 캐시를 `--offline` 빌드의 `GRADLE_USER_HOME` 으로 사용.
2. **backend** — `gradle --offline --gradle-user-home /opt/klid/buildtools/gradle-home -p src/backend bootJar bootWar -x test`
   → `artifacts/backend/api.war`(**반입 정본**) + `artifacts/backend/klid-backend.jar`(개발/베어메탈용)
   배치(+SHA256SUMS는 WAR 배치 뒤에 기록 — 정본이 목록에서 빠지지 않도록).
   ⚠ 구 절차 폐기(2026-08-30) — `bootJar` 만 돌려 jar 만 만들던 것. 그러면 **배포할 수 없는 산출물**만 나온다.
3. **frontend** — node_modules 복원(tarball → `src/frontend/node_modules`) 후
   `npm run build`(오프라인) → `artifacts/frontend/dist` 배치(+SHA256SUMS).
   - Vite 빌드 인자는 빌드머신과 동일 기본값: `VITE_API_BASE_URL=/api/v1`,
     `VITE_TOKEN_INGRESS=localStorage`, `VITE_DEV_LOGIN_ENABLED=true`, `VITE_DEV_UPLOAD_ENABLED=true`.
     필요 시 환경변수로 override.
   - ★ `VITE_TOKEN_INGRESS` 는 `localStorage` 를 유지할 것 — `url`/`both`/`all` 은 JWT 를 URL
     쿼리로 받는 채널을 열어 접근 로그·리퍼러 헤더·브라우저 히스토리에 토큰이 잔존한다(CWE-598).
4. **ai-server** — 별도 컴파일 없음. `install.sh` 의 `13-install-ai-server.sh` 가
   `pip install --no-index --find-links vendor/wheels`(+ sam2 로컬 소스)로 venv 에 소스 설치한다
   (사전 빌드/소스 빌드 모두 동일 — 추가 작업 불필요).

### 옵션 / override

```bash
# 일부만 재빌드
SKIP_FRONTEND=1 sudo ./scripts/install/build-from-source.sh   # backend 만
SKIP_BACKEND=1  sudo ./scripts/install/build-from-source.sh   # frontend 만

# Vite 빌드 인자 override (VITE_TOKEN_INGRESS 는 localStorage 유지 — 위 ★ 참고)
VITE_API_BASE_URL=/api/v1 VITE_TOKEN_INGRESS=localStorage \
  sudo ./scripts/install/build-from-source.sh

# 빌드 도구 해제 위치 변경(기본 /opt/klid/buildtools)
KLID_BUILD_PREFIX=/data/klid-build sudo ./scripts/install/build-from-source.sh
```

---

## 재빌드 후 설치

```bash
sudo ./scripts/install.sh
```

`build-from-source.sh` 가 `artifacts/{backend,frontend}` 를 채워두므로, 이후 `install.sh` 는
사전 빌드 아티팩트를 쓸 때와 **완전히 동일하게** 동작한다(python 런타임 → backend 산출물 배치 →
ai-server → frontend → DB 초기화). 이어지는 설정·기동·검증은 [docs/03-install.md](03-install.md),
[docs/05-run-verify.md](05-run-verify.md) 를 따르며, **WAS 설정 이관**은
[docs/10-was-settings.md](10-was-settings.md) 가 정본이다.

---

## 흔한 실패

| 증상 | 원인 | 해결 |
|------|------|------|
| `gradle ... --offline` 의존성 해소 실패 | `buildtools/gradle-home` 캐시 누락/불완전 | 빌드머신에서 `60-collect-buildtools.sh` 의 gradle-home populate 단계를 다시 실행(인터넷 필요) |
| `npm run build` 실패(esbuild 등 실행 불가) | node_modules 를 mac 등에서 수집(plat 불일치) | **Linux x64**(el8 컨테이너)에서 node_modules 를 다시 채움. mac 이면 60-collect-buildtools.sh 가 docker 로 자동 처리 |
| `frontend node_modules 번들 없음` 으로 die | mac 에서 키트를 만들어 node_modules SKIP 됨 | 02 문서의 "node_modules 만 컨테이너에서 채우기" 예시 실행 |
| `javac 없음` 으로 die | JRE tarball 을 jdk/ 에 둠(full JDK 아님) | `JDK17_FULL_*`(image_type=jdk) tarball 을 `buildtools/jdk/` 에 배치. ⚠ 실행용 JRE 핀(`TEMURIN_JRE_*`)과 **별개**이며 그쪽은 아예 반입되지 않는다 |
| `빌드 키트 없음` 으로 die | 기본 수집이라 키트가 반입되지 않았다(손상 아님) | 재빌드가 필요 없으면 `sudo ./scripts/install.sh` 로 설치. 필요하면 빌드머신에서 `WITH_BUILDTOOLS=1 ./scripts/package.sh` 로 재수집 |
| `WAR 산출물 없음` 으로 die | `src/backend` 의 `build.gradle` 에 `war` 플러그인/`bootWar` 설정이 없다(구 소스) | 반입한 소스가 WAR 형상 반영본인지 확인 — 반입 정본은 `api.war` 다 |
| 체크섬 불일치로 die | 전송 손상/변조 | 해당 tarball 을 삭제하고 빌드머신에서 다시 받아 재전송 |
