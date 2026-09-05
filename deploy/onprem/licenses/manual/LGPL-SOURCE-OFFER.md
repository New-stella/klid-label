# (L)GPL 대응 소스 안내

> 이 문서는 사람이 쓴다(자동 생성 아님). 반입물의 카피레프트 구성요소에 대해
> **대응 소스를 어떻게 이행하는지**를 밝힌다.
>
> 관련 자동 산출물: `licenses/UNRESOLVED.md` 의 「카피레프트(대응 소스 의무 가능) 목록」

## 1. 결론 — 이 매체는 대응 소스를 **함께 싣는다**

폐쇄망 반입이라 *"고객이 인터넷에서 받으면 된다"* 가 성립하지 않는다.
서면 제공 확약(LGPL-2.1 §6(b) / LGPL-3.0 §4(d) → GPLv3 §6(b))도 법적으로는 가능하지만,
확약을 받은 담당자가 폐쇄망 안에서 그것을 행사하려면 다시 우리를 거쳐야 한다.
그래서 **비용이 감당되는 것은 전부 실어서 보낸다.**

| 구성요소 | 판 | 라이선스 | 실린 위치 | 크기 |
|---|---|---|---|---|
| FFmpeg (opencv-python wheel 번들) | 8.0.1 | LGPL-2.1-or-later | `licenses/copyleft-sources/ffmpeg-8.0.1.tar.xz` | 11 MB |
| Qt 5 (opencv-python wheel 번들) | 5.15.18 | LGPL-3.0 | `licenses/copyleft-sources/qtbase-everywhere-opensource-src-5.15.18.tar.xz` | 51 MB |
| hibernate-core | 6.5.2.Final | LGPL-2.1-or-later | `licenses/copyleft-sources/hibernate-core-6.5.2.Final-sources.jar` | 7.7 MB |
| hibernate-commons-annotations | 6.0.6.Final | LGPL-2.1-or-later | `licenses/copyleft-sources/hibernate-commons-annotations-6.0.6.Final-sources.jar` | 43 KB |
| ffmpeg (시스템 RPM, RPM Fusion) | 리포 최신 | GPLv3+ | `syspkgs/ffmpeg-src/*.src.rpm` | 약 11 MB |

합계 약 81 MB. 매체 전체 규모에 비하면 작다.

> ⚠ 마지막 행(시스템 RPM)은 **위 네 건과 다른 물건**이다.
> 백엔드가 프로세스로 호출하는 `/usr/bin/ffmpeg` 의 소스이고, `50-collect-syspkgs.sh` 가
> 기존 경로(`syspkgs/ffmpeg-src/`)로 받는다. wheel 안에 번들된 FFmpeg 와 혼동해
> 어느 한쪽을 "중복"으로 지우지 말 것 — **판도 라이선스도 다르다**(GPLv3+ vs LGPL-2.1+).

## 2. 무엇을 어떻게 확인했나 (대응성 근거)

LGPL 이 요구하는 것은 "소스"가 아니라 **그 바이너리에 대응하는 소스**다.
판이 어긋난 소스를 대응 소스라고 부르는 것은 이행이 아니라 **틀린 주장**이며,
아무것도 안 준 것보다 나쁘다. 그래서 눈이 아니라 **기계로 대조**한다.

### 2-1. FFmpeg

반입 바이너리는 `opencv-python==4.13.0.92` wheel(linux x86_64, non-headless) 안의
`opencv_python.libs/libav*.so.*` 다. 별도로 설치되는 시스템 ffmpeg 이 아니다.

바이너리에서 직접 읽은 사실:

```
FFmpeg version 8.0.1
libavutil license: LGPL version 2.1 or later
configure: --prefix=/ffmpeg_build ... --enable-openssl --enable-libvpx --enable-shared --enable-pic
           (--enable-gpl 없음, --enable-nonfree 없음 → LGPL 빌드)
```

soname ↔ 소스 `version.h` 대조 (4/4 일치):

| 라이브러리 | wheel 바이너리 | ffmpeg-8.0.1 소스 |
|---|---|---|
| libavcodec | 62.11.100 | 62.11.100 |
| libavformat | 62.3.100 | 62.3.100 |
| libavutil | 60.8.100 | 60.8.100 |
| libswscale | 9.1.100 | 9.1.100 |

이 대조는 `scripts/package/65-collect-copyleft-sources.sh` 가 **매 수집마다 자동으로** 다시 한다.
불일치면 그 자리에서 실패한다(경고가 아니라 중단이다 — 경고는 로그에 묻힌다).

### 2-2. Qt 5

같은 wheel 이 `libQt5Core / Gui / Test / Widgets / XcbQpa` 를 번들한다(모두 `.so.5.15.18`).
**다섯 모듈 전부 qtbase 소속**이므로 대응 소스는 qtbase 하나로 닫힌다.

`qt-everywhere`(664 MB) 대신 `qtbase`(51 MB)를 싣는 근거가 이것이다 —
LGPL-3.0 이 요구하는 것은 **그 라이브러리**의 대응 소스이지 Qt 제품군 전체가 아니다.

대조: wheel soname `5.15.18` ↔ qtbase `.qmake.conf` 의 `MODULE_VERSION = 5.15.18` (모듈 5개 전부 일치).
65 단계가 자동 대조하며, **qtbase 밖 모듈이 나타나면 경고**한다(그 서브모듈 소스를 추가해야 한다).

### 2-3. hibernate 2건

`hibernate-core-6.5.2.Final.pom` 의 `<licenses>` 가
`GNU Library General Public License v2.1 or later` 를 선언한다(부모 상속 아님, 자기 POM).
`hibernate-commons-annotations-6.0.6.Final` 도 같다.

> ⚠ **표기 함정** — 이 이름에는 `GPL` 도 `Lesser` 도 들어 있지 않다("Library General Public").
> `GPL|Lesser` 로만 훑는 검사기는 이 두 건을 **놓친다**. 실제로 그 실수를 밟았고,
> 그래서 검사 패턴에 `General Public` 를 통째로 넣었다(`70-generate-notices.sh`).
> 검사 패턴을 줄일 때 이 두 건이 여전히 잡히는지 확인할 것.

두 jar 안에는 라이선스 전문이 **없다**(`META-INF/LICENSE`·`NOTICE` 0건 — 실측).
전문은 함께 실린 sources jar 안 `lgpl-2.1.txt` 등에 들어 있다.

체크섬은 Maven Central 이 게시한 `.sha1` 과 대조해 확인한 뒤 sha256 을 산출했다.

## 3. 서면 제공 확약 (보완적 — 위 동봉이 실패했을 때)

`SKIP_COPYLEFT_SRC=1` 로 수집을 껐거나 다운로드가 실패해
`licenses/copyleft-sources/` 가 비어 있는 매체를 받았다면, 아래 확약이 적용된다.

> 본 제품의 배포자는 위 표에 열거된 (L)GPL 구성요소에 대하여, **본 제품을 인도받은 날로부터
> 3년간**, 요청하는 누구에게나 해당 바이너리에 대응하는 완전한 기계가독 소스코드를
> **물리적 배포에 소요되는 실비를 넘지 않는 비용으로** 제공한다.
> 요청처: 본 제품의 공급 계약에 기재된 담당 조직.

이 확약은 LGPL-2.1 §6(b) 및 LGPL-3.0 §4(d) → GPLv3 §6(b) 가 허용하는 이행 방법이다.

> ⚠ **"고지만 하면 된다"는 성립하지 않는다.** 라이선스 전문을 동봉하는 것(§4(a) 계열 의무)과
> 대응 소스를 제공하는 것(§6 / §4(d) 의무)은 **별개 의무**다.
> opencv 의 `LICENSE-3RD-PARTY.txt` 가 LGPL-2.1·LGPL-3.0 전문을 담고 있어 전자는 충족되지만,
> 그것만으로 후자가 사라지지는 않는다.

## 4. 대응 소스 의무가 **없는** 카피레프트 표기 (선택적 이중 라이선스)

아래는 인벤토리의 카피레프트 목록에 뜨지만, **다른 쪽 라이선스를 선택**하면 소스 의무가 없다.
본 배포는 각각 괄호 안의 라이선스를 선택한다.

| 구성요소 | 표기 | 선택 |
|---|---|---|
| logback-classic / logback-core 1.5.6 | EPL-1.0 **또는** LGPL-2.1 | EPL-1.0 |
| mchange-commons-java 0.2.15 | LGPL-2.1 **또는** EPL-1.0 | EPL-1.0 |
| jakarta.annotation-api 2.1.1 | EPL-2.0 **또는** GPL-2.0 w/ Classpath Exception | EPL-2.0 |
| jakarta.transaction-api 2.0.1 | EPL-2.0 **또는** GPL-2.0 w/ Classpath Exception | EPL-2.0 |

## 5. 이 표를 갱신해야 하는 때

- `ai-server/requirements.txt` 의 `opencv-python` 판이 바뀔 때
  → FFmpeg·Qt5 번들 판이 함께 바뀐다. 65 단계가 자동으로 잡아 **실패**시키므로,
    그때 `licenses/manual/LGPL-SOURCES.tsv` 를 새 판으로 고치고 옛 tarball 을 지운다.
- `backend/gradle.lockfile` 의 hibernate 판이 바뀔 때
  → Hibernate ORM 은 6.6 이후 Apache-2.0 으로 재라이선스됐다. 올리면 이 두 행이 **불필요해질 수 있다.**
    올린 뒤 POM `<licenses>` 를 다시 확인하고, Apache-2.0 이면 이 표에서 뺀다.
- opencv-python 을 `opencv-python-headless` 로 바꿀 때
  → Qt5 가 번들에서 빠진다(65 단계가 그 사실을 경고한다). 그때 qtbase 행을 뺀다.
    ⚠ 단 `requirements.in` 주석이 밝히듯 headless 교체는 현재 **성립하지 않는다**
      (supervision·trackers 가 `opencv-python` 을 직접 요구한다).
