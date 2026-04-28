# AI 함수 핸들러 템플릿 (function.yaml + handler.py)

## 한 줄 요약
AI 모델 1개 = `function.yaml`(메타데이터) + `main.py`(핸들러) + `model_handler.py`(전/후처리) 파일 3개로 표준화하는 패턴. Nuclio 자체를 안 쓰더라도 마이크로서비스 모델 코드 구조로 우수.

## 추출 가치
**왜 떼어 쓸 가치가 있는가**
- "AI 모델 한 개를 추가/교체/삭제"라는 명확한 단위로 코드를 격리한다 → 모델별 의존성 충돌 차단, 모델별 GPU 자원 분리.
- function.yaml의 `metadata.annotations.spec`에 라벨 정의(JSON)를 같이 넣어 **모델 자체가 어떤 라벨을 출력하는지 자체 기술(self-describing)**.
- 핸들러 시그니처(`init_context`, `handler(context, event)`)는 단순 → Nuclio 의존성을 제거하고 FastAPI/Flask로 30라인 변환 가능.
- detector / interactor / tracker / reid 4종 표준 응답 형식이 정해져 있어 클라이언트가 모델별 분기 없이 처리 가능.

## 원본 코드 위치

| 역할 | 파일:라인 | 핵심 함수/클래스 |
|------|---------|--------------|
| YOLOv7 함수 (detector) | `serverless/onnx/WongKinYiu/yolov7/nuclio/main.py` (39줄) | `init_context`, `handler` |
| YOLOv7 모델 핸들러 | `serverless/onnx/WongKinYiu/yolov7/nuclio/model_handler.py` (128줄) | `ModelHandler.infer(image, threshold)` |
| YOLOv7 function.yaml | `serverless/onnx/WongKinYiu/yolov7/nuclio/function.yaml` (124줄) | metadata, spec.build, spec.triggers |
| SAM 함수 (interactor) | `serverless/pytorch/facebookresearch/sam/nuclio/main.py` (39줄) | feature 추출 후 base64 응답 |
| SAM function.yaml | `serverless/pytorch/facebookresearch/sam/nuclio/function.yaml` (67줄) | startswith_box_optional 등 인터랙터 옵션 |
| 배포 스크립트 | `serverless/deploy_cpu.sh`, `serverless/deploy_gpu.sh` | `nuctl deploy` 명령 |
| 클라이언트(CVAT 측) 호출 | `cvat/apps/lambda_manager/views.py` | `LambdaGateway.invoke()` |

추가 모델 9개(`mmpose/hrnet32`, `iog`, `transt`, `face-detection-0205`, `mask_rcnn_inception_resnet_v2_atrous_coco`, `retinanet_r101`, `faster_rcnn_inception_v2_coco` 등)도 같은 구조.

## 알고리즘/프로토콜 핵심

### function.yaml 표준 구조

```yaml
metadata:
  name: <unique-function-name>          # Nuclio 함수 ID (DNS-safe)
  namespace: cvat                        # 보통 cvat 고정
  annotations:
    name: "사용자 표시 이름"              # 클라이언트 UI에 표시
    type: detector                       # detector | interactor | tracker | reid
    version: 2                           # 호환성 표시 (선택)
    spec: |
      [
        {"id": 0, "name": "person", "type": "rectangle"},
        {"id": 2, "name": "car", "type": "rectangle"}
      ]
    # interactor 전용 추가 필드 (SAM 예시):
    min_pos_points: 0
    min_neg_points: 0
    startswith_box_optional: true
    help_message: "포인트 클릭으로 객체 분할"

spec:
  description: "..."
  runtime: 'python:3.10'                  # Nuclio 런타임
  handler: main:handler                   # main.py의 handler 함수
  eventTimeout: 30s                       # 단일 호출 타임아웃

  build:
    image: <docker-image-tag>
    baseImage: ubuntu:22.04
    directives:
      preCopy:
        - kind: ENV
          value: DEBIAN_FRONTEND=noninteractive
        - kind: WORKDIR
          value: /opt/nuclio
        - kind: RUN
          value: |
            apt-get update && apt-get install -y python3-pip
        - kind: RUN
          value: pip install onnxruntime opencv-python-headless pillow
        - kind: RUN
          value: wget https://example.com/model_weights.onnx

  triggers:
    myHttpTrigger:
      numWorkers: 2                       # 동시 처리 워커 수
      kind: 'http'
      workerAvailabilityTimeoutMilliseconds: 10000
      attributes:
        maxRequestBodySize: 33554432      # 32MB (이미지 base64 한도)

  platform:
    attributes:
      restartPolicy:
        name: always
        maximumRetryCount: 3
      mountMode: volume
```

### handler 함수 시그니처

```python
def init_context(context):
    """함수 컨테이너 시작 시 1회 호출. 모델 로딩/워밍업 위치."""
    context.logger.info("loading model...")
    model = ModelHandler(...)             # GPU 메모리에 모델 적재
    context.user_data.model = model       # 후속 handler 호출에서 재사용

def handler(context, event):
    """매 HTTP 요청마다 호출. 응답 반환."""
    data = event.body                      # 이미 dict로 파싱된 JSON
    # ... 추론 ...
    return context.Response(
        body=json.dumps(results),
        headers={},
        content_type="application/json",
        status_code=200,
    )
```

### Detector 응답 형식 (각 객체당 1개 dict)

```json
[
  {
    "confidence": "0.95",
    "label": "car",
    "points": [100, 200, 300, 400],
    "type": "rectangle"
  },
  {
    "confidence": "0.87",
    "label": "person",
    "points": [50, 60, 70, 80, 80, 90, ...],
    "type": "polygon"
  }
]
```

- `points`: shape 타입에 맞는 좌표 (rectangle: x0,y0,x1,y1 / polygon: x0,y0,x1,y1,...)
- `confidence`: 문자열로 반환 (CVAT 클라이언트에서 float 변환)
- `label`: function.yaml `spec`의 name과 일치해야 함

### Interactor 응답 (SAM 등)

```json
{
  "points": [x0, y0, x1, y1, ..., xn, yn],
  "type": "polygon"
}
```

또는 SAM의 경우 (사전 인코딩된 feature 반환):
```json
{
  "blob": "<base64 of feature tensor>"
}
```

### Tracker 응답

```json
{
  "shape": [x0, y0, x1, y1],
  "state": "<base64 of tracker state>"
}
```

`state`는 다음 호출에 전달되어 stateful 추적.

### Reid 응답 (사람 재식별)

```json
[
  {"index1": <idx0>, "index2": <idx1>, "score": 0.92}
]
```

### 입력 형식 (event.body)

| 필드 | 타입 | 의미 |
|------|------|------|
| `image` | base64 string | 단일 프레임 PNG/JPEG, base64 인코딩 |
| `image1`, `image2` | base64 string | 트래커: 이전/현재 프레임 |
| `threshold` | float | confidence threshold |
| `points` | `[[x, y], ...]` | interactor 입력 포인트 |
| `pos_points`, `neg_points` | `[[x, y], ...]` | SAM positive/negative |
| `obj_bbox` | `[x0, y0, x1, y1]` | interactor bbox 힌트 |
| `state` | base64 string | tracker 이전 상태 |
| `boxes` | `[[x0, y0, x1, y1], ...]` | reid 매칭할 bbox 목록 |

이미지는 Base64 디코딩 → PIL.Image:
```python
buf = io.BytesIO(base64.b64decode(data["image"]))
image = Image.open(buf).convert("RGB")
```

### 모델 로딩 패턴 (GPU 활용)

```python
# model_handler.py (YOLOv7 ONNX 예시)
import onnxruntime as ort

class ModelHandler:
    def __init__(self, labels):
        device = ort.get_device()
        cuda = device == "GPU"
        providers = (
            ["CUDAExecutionProvider", "CPUExecutionProvider"]
            if cuda else ["CPUExecutionProvider"]
        )
        so = ort.SessionOptions()
        so.log_severity_level = 3
        self.model = ort.InferenceSession("yolov7-nms-640.onnx",
                                          providers=providers,
                                          sess_options=so)
```

## 의존성

### 외부 라이브러리 (모델별)
| 라이브러리 | 용도 | 대체 가능? |
|----------|------|---------|
| Pillow | 이미지 디코딩 | 필수 |
| numpy | 텐서 연산 | 필수 |
| 모델 프레임워크 (onnxruntime/torch/tensorflow) | 추론 엔진 | 모델별 선택 |
| pyyaml | function.yaml 파싱 (필요한 경우 main.py 안에서) | 라벨 spec 인라인 시 불필요 |

### Nuclio 의존성
Nuclio 런타임이 제공하는 것:
- `context.logger.info/...` — `logging` 모듈로 대체
- `context.user_data` — 단순 객체로 대체
- `context.Response(...)` — FastAPI Response 또는 dict 반환으로 대체
- `event.body` — FastAPI Pydantic 모델로 대체

→ 위 4가지를 mock하면 핸들러 코드는 그대로 사용 가능.

## 단독 추출 예시 코드 — FastAPI 마이크로서비스 변환

```python
# fastapi_inference.py — Nuclio 핸들러를 FastAPI로 변환한 단독 모듈
# 30라인으로 마이그레이션 완료
import base64
import io
from typing import Any

from fastapi import FastAPI
from PIL import Image
from pydantic import BaseModel

# 모델 핸들러는 그대로 가져와서 사용
from model_handler import ModelHandler

# function.yaml의 spec 필드 내용을 여기로 옮김
LABELS = {
    0: "person",
    1: "bicycle",
    2: "car",
    # ...
}


class DetectionRequest(BaseModel):
    image: str          # base64
    threshold: float = 0.5


app = FastAPI()
model: ModelHandler = None  # type: ignore


@app.on_event("startup")
def init():
    """Nuclio init_context에 해당 — 프로세스당 1회"""
    global model
    model = ModelHandler(LABELS)


@app.post("/invoke")
def invoke(req: DetectionRequest) -> list[dict[str, Any]]:
    """Nuclio handler에 해당 — 매 요청마다 호출"""
    buf = io.BytesIO(base64.b64decode(req.image))
    image = Image.open(buf).convert("RGB")
    return model.infer(image, req.threshold)


@app.get("/healthz")
def healthz():
    return {"status": "ok"}
```

호출 예 (CVAT의 `INVOKE_METHOD=direct`로 가리키는 방식):
```bash
curl -X POST http://localhost:8000/invoke \
    -H "Content-Type: application/json" \
    -d '{"image": "<base64>", "threshold": 0.6}'
```

### model_handler.py는 그대로 사용

`model_handler.py`(전/후처리 + 추론 로직)는 Nuclio 의존성이 0이므로 **수정 없이 그대로 import 가능**. main.py만 FastAPI/Flask로 교체.

## 입력/출력 명세

### detector 모델 표준
- **입력**: `{"image": base64, "threshold": float}`
- **출력**: `[{"label": str, "confidence": str, "points": [...], "type": "rectangle"|"polygon"|"mask"}, ...]`

### interactor 모델 표준 (SAM 등)
- **입력**: `{"image": base64, "pos_points": [[x,y],...], "neg_points": [[x,y],...], "obj_bbox": [x0,y0,x1,y1]}`
- **출력**: `{"points": [...], "type": "polygon"}` (또는 base64 mask blob)

### tracker 모델 표준
- **입력**: `{"image1": base64, "image2": base64, "shape": [x0,y0,x1,y1], "state": base64|null}`
- **출력**: `{"shape": [x0,y0,x1,y1], "state": base64}`

### reid 모델 표준
- **입력**: `{"image1": base64, "image2": base64, "boxes1": [[...]], "boxes2": [[...]], "max_distance": int}`
- **출력**: `[{"index1": int, "index2": int, "score": float}, ...]`

## 통합 가이드 (다른 시스템에 붙이는 법)

### 모델 추가 5단계 체크리스트
1. `serverless/{framework}/{author}/{model}/nuclio/` 디렉토리 생성
2. `function.yaml` — metadata.name, type, spec(라벨 JSON), build.directives 작성
3. `main.py` — `init_context` + `handler` 작성 (실제 코드는 model_handler.py에 위임)
4. `model_handler.py` — 모델 로딩 + 전처리 + 추론 + 후처리
5. (선택) `Dockerfile` — function.yaml의 directives 대신 직접 작성 (Nuclio 미사용 시)

### Nuclio 없이 동등한 기능
1. **단일 GPU 서버**: 모델 핸들러를 backend 프로세스에 직접 import → HTTP 호출 오버헤드 제거
2. **멀티 모델 격리**: 각 모델을 별도 FastAPI 컨테이너로 배포 → reverse proxy(nginx)로 라우팅
3. **K8s 환경**: Nuclio Native(K8s 모드)로 동등하게 함수 단위 배포

### 클라이언트(CVAT) 측 호출 흐름
```
1. POST /api/lambda/requests
   → server는 function 이름으로 Nuclio/FastAPI URL 결정
   → frame을 base64 인코딩
   → 추론 함수 호출
   → 응답을 LabeledShape로 변환 (label mapping 적용)
   → DB 저장 (source='auto')
```

label mapping은 클라이언트 요청의 `mapping` 필드 사용:
```json
{
  "function": "onnx-wongkinyiu-yolov7",
  "task": 5,
  "mapping": {
    "car": {"name": "vehicle"},
    "person": {"name": "pedestrian"}
  }
}
```

## 검증 방법

1. **healthz 핑**: `GET /healthz` 응답이 200 OK인지.
2. **샘플 이미지 detector**: COCO 샘플 이미지(`person`, `car` 포함)를 base64 → POST → 결과에 person/car bbox가 정상 좌표로 나오는지.
3. **threshold 동작**: threshold=0.0 → 모든 검출 반환, threshold=0.99 → 거의 0개.
4. **interactor SAM**: positive 포인트 1개 → 응답이 polygon 형태이고 점 개수가 합리적(>10).
5. **메모리 누수**: 100회 연속 호출 후 RSS가 안정적인지 (모델이 매번 재로드되면 메모리 누적).

## 알려진 한계와 함정

- **첫 호출 cold start**: `init_context`가 무거우면(SAM은 ViT-H 가중치 ~2.5GB) 첫 추론까지 30초 걸릴 수 있음. Nuclio는 prewarm 옵션, FastAPI는 `@app.on_event("startup")` 이미 차가운 상태에서 호출 차단 필요.
- **base64 오버헤드**: 4K 프레임을 base64로 보내면 ~10MB. 32MB `maxRequestBodySize` 한도에 가까움. 큰 이미지는 url로 전달하거나 raw 바이너리 PUT으로 변경 고려.
- **GPU 워커 동시성**: `numWorkers: 2`인데 GPU 1개라면 두 워커가 동시에 모델 호출 → CUDA OOM. GPU 1개 = 워커 1개가 안전.
- **이미지 회전**: 영상의 EXIF rotation 메타데이터를 PIL.Image가 적용하지 않을 수 있음 → 좌표가 회전된 채로 반환되어 클라이언트에서 어긋남. CVAT은 영상 처리 단계에서 회전 적용 후 `frame.to_ndarray(format="bgr24")`로 표준화.
- **threshold 타입**: detector 응답의 `confidence`는 문자열로 반환하는 컨벤션 (Python `str(score)`). 클라이언트는 float 변환 가정.
- **label spec과 function.yaml 동기화**: function.yaml의 `spec` 라벨 JSON과 model_handler의 클래스 인덱스가 일치하지 않으면 잘못된 라벨 부여. 특히 모델 weights 교체 시 주의.
- **mask 응답 형식**: detector 모델이 mask를 반환할 때, `type: "mask"` + `points: [rle..., left, top, right, bottom]` 형식으로 RLE 인코딩 필수 (모듈 02 참조).
- **Polygon 닫기**: detector polygon 응답은 일반적으로 마지막 점이 첫 점과 같지 않게 (open polygon) 반환. 닫힌 polygon으로 보내도 동작은 하나 중복 점 발생.
- **타임아웃**: 영상의 모든 프레임에 detector 적용 시 함수당 호출 횟수가 수천 번 → 한 번 호출이 30초 이상이면 전체 작업이 한참 걸림. CVAT 클라이언트는 100프레임 누적 PATCH로 최적화하지만 함수 자체의 처리 시간 단축이 우선.

## 라이선스 주의사항

CVAT MIT, Nuclio Apache-2.0. 모델 자체의 라이선스는 별도 (YOLOv7는 GPL-3.0, SAM은 Apache-2.0). 추출 시:
```python
# Adapted from CVAT serverless template (https://github.com/cvat-ai/cvat)
# Copyright (C) CVAT.ai Corporation
# SPDX-License-Identifier: MIT
```
모델 가중치 라이선스는 별도 명시 필수.
