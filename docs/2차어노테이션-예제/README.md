# 2차 어노테이션 산출 예제

저작도구가 검수 승인 시점에 만들어 내보내는 학습데이터 JSON 의 형태를 한 장으로 보여준다.
포맷 버전은 `1.3` 이고, 1차 사업 산출물(`docs/1차어노테이션/`, `docs/sample.json` — 버전 `1.0`)과는
세대가 다르다.

- `0005.json` — 프레임 1장에 대응하는 어노테이션 문서

## 어디서 나오는가

산출 경로는 `{영상루트}/v{n}/{orgnl|deid}/` 이며, **프레임 1장 = JSON 1개**다.
같은 폴더에 같은 이름의 이미지가 함께 놓인다(`0005.jpg` / `0005.json`).
원천(`orgnl`)과 비식별(`deid`) **두 벌**이 나가고, 이 예제는 `deid` 쪽이다.

승인이 다시 일어나면 이전 버전 폴더를 지우지 않고 `v{n+1}` 로 **전량 재생성**한다.

## 판독 규칙

코드를 읽지 않고도 오해하지 않도록, 헷갈리기 쉬운 것만 모았다.

### `null` 키는 사라지지 않는다

직렬화가 `ALWAYS` 라 값이 없어도 키가 남는다. `type` · `frames` · `pixel` · `og_cd` ·
`cctv_height` · `cctv_azimuth` · `cctv_mng_no` · `event_log` · `license_id` · `label_path` 는
**보유한 데이터가 없어서** 항상 `null` 이다. 누락이 아니라 미보유다.

### 도형 세 종류는 서로 배타적이다

라벨 종류에 따라 셋 중 하나만 채워지고 나머지는 `null` 이다.

| 라벨 종류 | 채워지는 필드 | 형태 |
|---|---|---|
| `BBOX` · `TRACK` | `bbox` | `[minX, minY, width, height]` — 점들의 최소·최대에서 계산 |
| `POLYGON` · `SEGMENT` | `polygon` | `[[x1,y1,x2,y2,…]]` — 한 겹 배열 안에 평탄화 |
| `SKELETON` | `keypoints` | `[[x,y,v], …]` 17개. `categories` 에 관절 이름과 연결이 함께 실린다 |

### 파일명의 번호와 `frame_num` 은 다른 번호다

- 파일명 `0005.jpg` 는 **추출 순번**(`FRM_NO`, 4자리 zero-pad)
- `frame_num` 은 **실제 영상 안에서의 프레임 위치**(`VDO_FRM_NO`)

둘을 같은 것으로 보면 영상에서 엉뚱한 지점을 집게 된다.

### 개인정보 세 필드는 산출 종류 × 블록으로 갈린다

`anonymity` · `pseudonymity` · `privacy_included` 는 네 칸으로 정해진다.

| | `video` 블록 | `image` 블록 |
|---|---|---|
| `deid` | 사람이 입력한 영상 단위 값, 없으면 `Y`/`N`/`N` | 사람이 입력한 프레임 단위 값, 없으면 `Y`/`N`/`N` |
| `orgnl` | 관제 인입값 그대로. **관제가 보내지 않았으면 `null`**(지어내지 않는다) | 정책 상수 `N`/`N`/`Y`(프레임 단위 원천 판정 데이터가 없다) |

같은 문서에서 `video` 와 `image` 의 값이 갈릴 수 있다. **모순이 아니라 입도가 다른 사실**이다 —
"영상 어딘가엔 있지만 이 프레임엔 없다"가 성립한다.

### `event` 는 가공 없는 통과다

검수 승인 시점에 동결된 이벤트 어노테이션을 그대로 싣는다. 없으면 `"event": null` 이다.
예제에 담긴 폭행 내용은 **구조를 보이려고 회귀 시험의 값을 그대로 옮긴 것**이라 보행자 영상과
내용이 맞지 않는다. 실제로는 그 영상의 어노테이션이 들어간다.

### `vd_description` 의 조달 순서

`vlm.description` → 사람이 직접 쓴 `manual-timeseries` → 레거시 구간 행을 `start_sec` 숫자순으로
이어붙임 → 그래도 없으면 `null`. 빈 문자열이 아니다.

### 일치도(`vlm.accuracy`)는 여기 없다

화면 전용 값이라 학습데이터 산출물에 넣지 않는다. **의도된 부재이며 결함이 아니다.**

## 진실원

이 예제가 낡았는지 판단하려면 아래를 본다. 문서가 아니라 이 코드가 산출 형태를 정한다.

| 축 | 판정 지점 |
|---|---|
| 문서 골격·키 순서 | `NiaAnnotationDoc` · `NiaJsonBuilder(build)` |
| `video` 블록 | `VideoMetaMapper(toVideo)` |
| 도형 변환 | `LabelToAnnotationMapper(toAnnotation)` |
| `categories` | `CategoryMapper(toCategory)` |
| 개인정보 네 칸 | `ExportPrivacyPolicy` |
| 파일명 규칙 | `ExportFileNaming` |
| 산출 경로·버전 | `DatasetExportPathResolver(resolve)` |
| `vd_description` | `VlmDescriptionPolicy` |

## 이 예제의 한계

**코드를 읽어 조립한 것이지 실제로 돌려서 뽑은 산출물이 아니다.** 값의 출처는 두 갈래다.

- 회귀 시험 `NiaJsonBuilderTest` 의 픽스처를 그대로 옮긴 것 — 영상·프레임·라벨·좌표
- 형태를 보이려고 채운 것 — `event_id` · `weather` · `vd_description` · 두 번째 어노테이션

따라서 **개별 값을 사실로 인용하지 말 것**이고, 볼 것은 키 구성과 형태다.
실제 산출 파일이 필요하면 export 를 돌려 그 결과를 떠 오면 된다.

작성 2026-08-24 기준.
