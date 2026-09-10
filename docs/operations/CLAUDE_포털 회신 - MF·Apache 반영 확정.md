# 포털 → 저작도구 회신 : MF·Apache 반영 확정

> 작성일: 2026-09-10
> 대상: 저작도구 프론트·백엔드 개발팀
> 대상 회신: 저작도구 「포털 서버 Apache(httpd.conf) 수정 요청 — 2026-09-10」
> ★ 이 문서가 이전 「저작도구 MF Remote 빌드 요청」을 **대체(폐기)** 한다.
>   그 문서는 포털 프론트 배선(`/label-remote/`·`./PortalApp`)이 이미 끝난 사실을 모르고 쓴
>   잘못된 값(`/authoring/`·`./App`)이었다. 저작도구는 **재빌드 불필요** — 아래 확정값이 정본이다.

---

## 0. 요약 — 저작도구 요청 전부 수용·반영 완료

httpd.conf / httpd.conf.prod 에 아래를 반영했다. **저작도구는 MF·번들 쪽 변경이 없다.**

| 저작도구 요청 | 포털 반영 |
|---|---|
| §1 `/authoring-api/` 접두어 «유지»(제거 안 함) | ✅ `ProxyPass /authoring-api/ …/authoring-api/` |
| §2 번들 경로 = `/label-remote/` (A안) | ✅ `Alias /label-remote/` — **포털 프론트 무변경** |
| §3 `remoteEntry.js`(no-cache)·`assets/`(immutable) 캐시 | ✅ 순서 포함 반영 |
| §4-1 압축(mod_deflate) | ✅ |
| §4-2 `timeout=600` | ✅ |
| §4-3 `/authoring-api/v1/portal-system/` IP 제한 | ✅ `/api/internal/` 과 대칭 |
| §4-4 `/authoring-repo/` 제거 | ✅ |
| §4-5 번들 Dir `Options -Indexes +FollowSymLinks` | ✅ |

## 1. 확정값 (정본)

| 항목 | 값 |
|---|---|
| MF 번들 서빙 경로 | **`/label-remote/`** (INT-013, 2026-08-26 계약값) |
| remoteEntry 주소 | `https://<host>:38444/label-remote/remoteEntry.js` |
| expose 모듈 | **`./PortalApp`** |
| 저작도구 API 접두어 | **`/authoring-api/`** (Apache 가 접두어 유지해 WAS 로 전달) |
| 배포 경로(디스크) | `/opt/apache2_custom/www/author/` (개발) · `/var/www/author/`(상용) |

## 2. ★ 저작도구가 «맞춰야」 하는 것 — API 접두어 3값 (§6)

포털이 API 접두어를 **`/authoring-api/`** 로 확정했다. 저작도구는 아래 **3값을 같은 시점에** 맞춰 주기 바란다.

| # | 값 | 대상 |
|:-:|---|---|
| 1 | `/authoring-api` | WAR 웹 컨텍스트 (`KLID_WEB_CONTEXT`) |
| 2 | `/authoring-api/v1` | 프론트 API 주소 (`VITE_API_BASE_URL`) |
| 3 | `/authoring-api/v1` | 백엔드 응답 절대주소 접두어 (`PUBLIC_API_BASE_PATH`) |

⚠ **세 값이 같이 움직여야 한다** — 한 곳만 바뀌면 WAS 는 정상 기동하고 화면까지 뜨는데 **API 만 전건 404** 다(조용히 깨짐). 이것이 저작도구의 유일한 실제 수정 작업이다.

## 3. 아직 «같이 정할 것» (미합의 — 포털 프론트 참여)

저작도구 회신 §8 항목. 요청이 아니라 **양쪽 합의**가 필요하다.

| 항목 | 필요한 합의 |
|---|---|
| 진입 payload props | 포털이 `/workspace/authoring` 진입 시 넘길 값(datasetId 등)의 **props 이름·형태**. 파일 절대경로는 URL 에 못 실으므로 props 로 전달. 포털 프론트 ↔ 저작도구 협의 |
| `react-router` / history 소유 | 양쪽 미공유 확정. 한 문서에서 브라우저 history 를 누가 소유하는지 미결 |
| 스타일 격리 | 격리 책임은 저작도구. 개발망 첫 로드에서 포털 KRDS 화면 시각 회귀를 함께 확인 |

## 4. 배포 후 검증 (양쪽 공통)

```bash
apachectl -t                                                       # 문법
curl -sI http://<host>:38081/label-remote/remoteEntry.js           # 200 + no-cache
curl -sI http://<host>:38081/label-remote/assets/no-such-chunk.js | head -1   # ★ 404 여야 함
curl -s  http://<host>:38081/authoring-api/actuator/health/liveness           # ★ 200 여야 함
curl -sI -H 'Accept-Encoding: gzip' http://<host>:38081/label-remote/assets/<css>.css | grep -i content-encoding  # gzip
```
`404 청크`·`200 API` 둘이 핵심 검사다.

## 5. 정리 — 저작도구 액션

- [ ] **§6 API 접두어 3값**을 `/authoring-api` 계열로 정렬 (유일한 실제 수정)
- [ ] 진입 payload props 형태를 포털 프론트와 합의(§3)
- [ ] history 소유·스타일 격리 확인(§3)
- [x] MF Remote 빌드(`/label-remote/`·`./PortalApp`·React singleton) — **이미 완료, 재빌드 불필요**

> 포털 서버 Apache 는 위 확정값으로 반영 완료했다. 저작도구가 §6 3값만 맞추면
> `/workspace/authoring` 에서 저작도구 Remote 가 로드된다.
