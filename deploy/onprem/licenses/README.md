# licenses/ — 반입물 제3자 라이선스 고지

매체(`deploy/onprem/`)를 고객에게 넘기는 것은 **재배포**다. 여기 담긴 오픈소스는
저마다 고지·전문 동봉·대응 소스 제공을 요구한다. 이 디렉터리가 그 이행물이다.

> **왜 생겼나** — 2026-08-30 이전까지 이 저장소에는 `NOTICE`·`THIRD-PARTY-LICENSES` 류
> 집합 파일이 **한 건도 없었다.** 번들 파이썬의 제3자 고지 19종, 웹 산출물에 실리는
> 글꼴 2종의 OFL 전문이 모두 빠진 채 나가고 있었다.

## 1. 무엇이 자동이고 무엇이 사람 몫인가 (Critical)

**손으로 적은 목록은 반드시 낡는다.** lock 은 바뀌는데 문서는 안 바뀐다.
그래서 얻을 수 있는 것은 전부 **수집·빌드 시점에 실제 반입물에서 긁는다.**

| 산출물 | 만드는 주체 | 성격 |
|---|---|---|
| `backend/**` + `backend/INVENTORY.tsv` | `scripts/package/10-build-backend.sh` | **자동** |
| `frontend/**` + `frontend/INVENTORY.tsv` | `scripts/package/20-build-frontend.sh` | **자동** |
| `python-packages/**` + `INVENTORY.tsv` | `scripts/package/30-collect-ai-server.sh` | **자동** |
| `python-runtime/**` + `INVENTORY.tsv` | `scripts/package/40-collect-runtimes.sh` | **자동** |
| `copyleft-sources/**` | `scripts/package/65-collect-copyleft-sources.sh` | **자동**(목록은 수동) |
| `NOTICE` · `INVENTORY.md` · `UNRESOLVED.md` · `ALL.tsv` | `scripts/package/70-generate-notices.sh` | **자동** |
| `NOTICE.header.txt` | 사람 | 수동 — 우리 제품의 머리말 |
| `manual/LGPL-SOURCES.tsv` | 사람 | 수동 — 어떤 대응 소스를 실을지의 목록 |
| `manual/LGPL-SOURCE-OFFER.md` | 사람 | 수동 — 대응 소스 이행 방법과 근거 |
| `manual/OVERRIDES.tsv` | 사람 | 수동 — 스캐너가 못 얻은 값을 메우는 자리 |
| `manual/fonts/D2Coding-OFL-1.1.txt` | 사람 | 수동 — npm 패키지에 전문이 없어 직접 채움 |
| `manual/texts/**` | 사람 | 수동 — OVERRIDES 로 채운 항목의 전문 |

**경계는 하나다: 반입물 안에 고지가 있으면 자동, 없으면 사람.**
스캐너는 **값을 지어내지 않는다.** 못 찾으면 `UNRESOLVED.md` 에 남기고 멈춘다 —
틀린 고지는 고지가 없는 것보다 나쁘기 때문이다.

> ⚠ **생성 산출물을 손으로 고치지 마라.** 다음 수집에서 조용히 사라진다.
> 고칠 것이 있으면 `manual/OVERRIDES.tsv` 를 고치고 70 단계를 다시 돌린다.

## 2. 반출 전 확인 (체크리스트)

```bash
cd deploy/onprem
bash scripts/package/70-generate-notices.sh    # 재생성 (네트워크 불필요)
cat licenses/UNRESOLVED.md
```

- [ ] `UNRESOLVED.md` 의 **UNRESOLVED 절이 비어 있다**
- [ ] `TEXT_MISSING` 절의 항목을 각각 판단했다(전문을 `manual/texts/` 에 넣었거나, 넣지 않기로 한 근거가 있다)
- [ ] `UNRESOLVED.md` 의 **카피레프트 목록**을 `manual/LGPL-SOURCE-OFFER.md` 의 표와 대조했다
- [ ] `copyleft-sources/MANIFEST.tsv` 에 **대응성 검증 통과 기록**이 있다
- [ ] `NOTICE` 가 비어 있지 않다(수집 단계가 돌지 않으면 빈 파일이 된다)

## 3. 알아 둘 함정 3가지

1. **`GPL|Lesser` 로만 훑으면 hibernate 를 놓친다.** 그 POM 표기가
   `GNU **Library** General Public License v2.1 or later` 라 두 낱말 어디에도 안 걸린다.
   검사 패턴에 `General Public` 를 통째로 넣어 둔 이유이며, 실제로 밟은 실수다.
2. **폰트 라이선스는 패키지 루트에 없을 수 있다.** `pretendard-gov` 는 `dist/LICENSE.txt` 에 둔다.
   루트만 보면 가장 중요한 글꼴 고지를 놓친다.
3. **대응 소스는 "소스"가 아니라 "그 바이너리의 소스"여야 한다.** 65 단계가 opencv wheel 의
   soname 과 받아 둔 FFmpeg·Qt 소스의 선언 버전을 매번 기계 대조하고, 어긋나면 **중단**한다.

## 4. 반입물에 실리지 않는 것

- **오프라인 빌드 키트**(`buildtools/`, `WITH_BUILDTOOLS=1` 일 때만 수집) — 기본 미반입이라
  그 안의 JDK·`node_modules`(645 패키지)는 이 고지의 대상이 아니다. 켜서 반입한다면
  그 목록의 고지 의무가 새로 생긴다.
- **자바 런타임** — WAR 반입 형상이라 우리가 JRE 를 싣지 않는다(대상 WAS 가 이미 Java 17).
- **시스템 RPM**(`syspkgs/`) — 배포판 패키지라 각 RPM 이 자기 `%license` 를 담고 있고,
  GPL 대응 소스는 `syspkgs/ffmpeg-src/` 로 따로 다룬다(50 단계).
