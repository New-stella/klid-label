# ai-server

Stateless AI 추론 전용 FastAPI 서버. Spring Boot 백엔드가 오케스트레이션을 담당하고
이 서버는 YOLO/SAM2/VLM 모델 추론만 수행한다.

- 인증/DB/큐 없음
- 가중치 부재 시 mock 응답 (`mock_reason=weights_missing`)
- `AI_MOCK_MODE=true` 강제 시 mock 응답 (`mock_reason=env_mock`)
  - ⚠ `env_mock` 은 **빈 결과가 아니라 합성 라벨**(person, score=0.9)을 반환한다. 배포 환경
    (`ENV=stg|prd`)에서 이 조합이면 **기동을 거부**한다(`app/startup_guard.py`) — 가짜 라벨이
    학습데이터로 저장되는 것을 막기 위한 fail-closed 가드다.

## 실행

```bash
cd ai-server
pip install -r requirements.txt
cp .env.example .env  # 필요 시 값 조정
uvicorn app.main:app --host 0.0.0.0 --port 9300 --reload
```

## 환경 변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `AI_MOCK_MODE` | `false` | true면 모델 로드 없이 mock 응답. `ENV=stg\|prd` 와 함께 켜면 기동 거부 |
| `ENV` | (없음) | 배포 환경 표식(`local\|dev\|stg\|prd`). backend 와 동일 신호 |
| `AI_DEVICE` | `cpu` | `cpu` 또는 `cuda` |
| `MAX_IMAGE_SIZE_MB` | `10` | image_b64 최대 크기 |
| `YOLOX_WEIGHTS_PATH` | `./weights/yolox_s.onnx` | YOLOX ONNX 탐지 가중치 경로. 부재 시 mock |
| `SAM2_MODEL_ID` | `facebook/sam2-hiera-tiny` | SAM2 HuggingFace 모델 ID (로컬 가중치 파일 아님) |
| `VLM_MODEL_NAME` | `openai/clip-vit-base-patch32` | HuggingFace 모델명 |
| `CORS_ALLOW_ORIGINS` | `*` | 콤마 구분. 운영은 BE 도메인만 허용 |

## 가중치 다운로드

탐지 백엔드는 **YOLOX(ONNX Runtime) + ByteTrack 단일 백엔드**다. 가중치는 다음 명령으로 받는다 —
**SHA256 을 검증하며 불일치하면 실패**한다.

```bash
cd ai-server
./scripts/download-yolox-weights.sh
```

받는 파일의 출처·릴리스 태그·해시·라이선스는 **`weights/README.md`** 에 기록돼 있다
(바이너리는 `.gitignore` 대상이라 git 에 없고, 그 기록이 재현의 유일한 근거다).

SAM2 는 로컬 가중치 파일을 쓰지 않는다 — `SAM2_MODEL_ID` 의 HuggingFace 모델을
`SAM2ImagePredictor.from_pretrained` 로 적재하며, 최초 1회 네트워크가 필요하다
(폐쇄망 반입은 온프렘 패키징의 HF prefetch 가 담당).

> ⚠ ultralytics 배포 가중치(`yolov8*.pt` · `sam2_*.pt`)는 **AGPL-3.0** 이라 이 프로젝트의 배제
> 정책 대상이다. 이를 받아 오던 `scripts/download-weights.sh` ·
> `scripts/download-sam2-weights.sh` 는 제거됐다.

### 가중치 부재 시 동작

`YOLOX_WEIGHTS_PATH` 파일이 없으면 크래시하지 않고 mock 응답(`mock_reason=weights_missing`)으로
떨어진다(`app/models/yolox_loader.py::_resolve_yolox_weights()`). predict / track 양쪽 경로에 적용된다.
SAM2 도 로드 실패 시 mock(`load_failed`)으로 폴백한다.

> ⚠ **가중치 부재는 기동을 막지 않는다.** 기동 거부 대상은 `ENV=stg|prd` + `AI_MOCK_MODE=true`
> 조합뿐이다(`app/startup_guard.py::verify_deployment_settings`). 가중치가 없어도 서버는 뜬다.
> 다만 배포 환경(`ENV=stg|prd`)에서는 **요청 시점에 503 으로 거부**한다
> (`refuse_mock_in_deployed_env`) — 가짜 라벨이 학습데이터로 적재되는 것보다 실패가 낫기 때문이다.
> local/dev 에서는 그대로 mock 응답이 나가므로, 이 환경에서는 `mock_reason` 밖에서 드러나지 않는다.

## 테스트

```bash
cd ai-server
python -m pytest -v tests/
```

테스트는 `AI_MOCK_MODE=true` 를 강제하므로 가중치 다운로드 없이 실행 가능
(`tests/conftest.py` 참조).
