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
| `YOLO_WEIGHTS_PATH` | `./weights/yolov8m.pt` | YOLO primary 가중치 경로 |
| `YOLO_WEIGHTS_FALLBACK_PATH` | `./weights/yolov8n.pt` | primary 부재 시 fallback |
| `SAM2_WEIGHTS_PATH` | `./weights/sam2_t.pt` | SAM2 가중치 |
| `VLM_MODEL_NAME` | `openai/clip-vit-base-patch32` | HuggingFace 모델명 |
| `CORS_ALLOW_ORIGINS` | `*` | 콤마 구분. 운영은 BE 도메인만 허용 |

## 가중치 다운로드

Phase 1 (모델 업그레이드) 부터 YOLO 기본 가중치는 `yolov8m.pt` 다. 다음 명령으로 다운로드.

```bash
cd ai-server/weights

# YOLO v8 medium (~50MB) — 기본 가중치
curl -fL -o yolov8m.pt https://github.com/ultralytics/assets/releases/download/v8.3.0/yolov8m.pt

# (선택) YOLO v8 nano (~6MB) — fallback 가중치
curl -fL -o yolov8n.pt https://github.com/ultralytics/assets/releases/download/v8.3.0/yolov8n.pt
```

### Fallback 동작

가중치 파일이 없을 때 다음 순서로 시도:

1. `YOLO_WEIGHTS_PATH` (기본 `weights/yolov8m.pt`) 존재 → 사용
2. 부재 시 `YOLO_WEIGHTS_FALLBACK_PATH` (기본 `weights/yolov8n.pt`) 존재 → fallback 사용 + WARN 로그
3. 둘 다 부재 → mock 응답 (`mock_reason=weights_missing`)

이 로직은 `app/models/yolo_loader.py::_resolve_weights_path()` 에서 처리하며
predict / track 양쪽 경로에 적용된다.

## 테스트

```bash
cd ai-server
python -m pytest -v tests/
```

테스트는 `AI_MOCK_MODE=true` 를 강제하므로 가중치 다운로드 없이 실행 가능
(`tests/conftest.py` 참조).
