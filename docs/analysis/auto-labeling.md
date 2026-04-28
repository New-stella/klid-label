# AI 오토라벨링 시스템

## 개요
CVAT의 AI 오토라벨링은 Nuclio 서버리스 함수 플랫폼을 게이트웨이로 사용한다. AI 모델은 각각 독립적인 Docker 컨테이너로 실행되며, CVAT 서버는 HTTP를 통해 Nuclio에 함수 호출을 위임한다. 결과는 임시가 아닌 즉시 Job 어노테이션에 저장된다.

## 핵심 코드 위치
| 역할 | 파일 경로 | 비고 |
|------|---------|------|
| Lambda 게이트웨이/ViewSet | `cvat/apps/lambda_manager/views.py` | LambdaGateway, FunctionViewSet |
| Lambda URL 라우팅 | `cvat/apps/lambda_manager/urls.py` | /api/lambda/ |
| Lambda 모델 | `cvat/apps/lambda_manager/models.py` | FunctionKind 등 |
| Nuclio function.yaml 예시 (YOLO) | `serverless/onnx/WongKinYiu/yolov7/nuclio/` | function.yaml + main.py |
| Nuclio function.yaml 예시 (SAM) | `serverless/pytorch/facebookresearch/` | Segment Anything |
| 배포 스크립트 | `serverless/deploy_cpu.sh` | Nuclio CLI 배포 |

## Nuclio 기반 아키텍처 {#nuclio-architecture}

```
[CVAT Server]
    ↓ POST http://nuclio-dashboard/api/function_invocations
    ↓ headers: {x-nuclio-function-name: "onnx-wongkinyiu-yolov7"}
[Nuclio Dashboard]
    ↓ 요청 라우팅
[AI Function Container (Docker)]
    - YOLOv7 ONNX 모델 로드
    - 이미지 전처리
    - 추론
    - 결과 JSON 반환
[CVAT Server]
    ↓ 결과를 Job 어노테이션으로 저장
```

### 설정 (`cvat/settings/base.py`)
```python
NUCLIO = {
    "SCHEME": os.getenv("CVAT_NUCLIO_SCHEME", "http"),
    "HOST": os.getenv("CVAT_NUCLIO_HOST", "localhost"),
    "PORT": os.getenv("CVAT_NUCLIO_PORT", 8070),
    "FUNCTION_NAMESPACE": os.getenv("CVAT_NUCLIO_FUNCTION_NAMESPACE", "nuclio"),
    "DEFAULT_TIMEOUT": int(os.getenv("CVAT_NUCLIO_TIMEOUT", "120")),
    "INVOKE_METHOD": os.getenv("CVAT_NUCLIO_INVOKE_METHOD", "dashboard"),
}
```

## AI 모델 등록 방식

각 모델은 `function.yaml` 파일로 정의된다.

```yaml
# serverless/onnx/WongKinYiu/yolov7/nuclio/function.yaml
metadata:
  name: onnx-wongkinyiu-yolov7
  namespace: cvat
  annotations:
    name: "YOLO v7"
    type: detector            # detector | interactor | tracker | reid
    spec: |
      [
        { "id": 0, "name": "person", "type": "rectangle" },
        { "id": 2, "name": "car",    "type": "rectangle" }
      ]

spec:
  description: "YOLO v7 object detection"
  runtime: 'python:3.10'
  handler: main:handler
  eventTimeout: 120s
  build:
    image: cvat/onnx.wongkinyiu.yolov7
    baseImage: python:3.10-slim

  triggers:
    myHttpTrigger:
      maxWorkers: 2
      kind: http
      workerAvailabilityTimeoutMilliseconds: 10000
      attributes:
        maxRequestBodySize: 33554432  # 32MB

  resources:
    requests:
      memory: "1Gi"
    limits:
      memory: "2Gi"
```

### FunctionKind 타입

```python
# cvat/apps/lambda_manager/models.py
class FunctionKind(str, Enum):
    DETECTOR    = 'detector'    # 프레임 단위 감지 → shapes 생성
    INTERACTOR  = 'interactor'  # 사용자 포인트 → shape 생성 (SAM 등)
    TRACKER     = 'tracker'     # 다음 프레임 추적
    REID        = 'reid'        # 사람 재식별
```

## AI 모델 호출 방식 {#yolo-integration}

### Detector (YOLO) 호출

```python
# cvat/apps/lambda_manager/views.py:LambdaGateway.invoke()

# 1. 클라이언트 → CVAT: 오토라벨링 요청
POST /api/lambda/requests
{
    "function": "onnx-wongkinyiu-yolov7",
    "task": 5,
    "job": 3,             # job 지정 시 해당 job 범위만 처리
    "mapping": {
        "car": {"name": "vehicle"},  # 모델 클래스 → 프로젝트 라벨 매핑
        "person": {"name": "pedestrian"}
    },
    "cleanup": false,         # 기존 어노테이션 삭제 여부
    "threshold": 0.5,         # confidence threshold
    "conv_mask_to_poly": true, # MASK 결과를 POLYGON으로 자동 변환
    "max_distance": 50        # TRACKER 함수용: 객체 이동 최대 거리 (픽셀)
}

# 2. CVAT → Nuclio 함수 호출
POST http://nuclio/api/function_invocations
Header: x-nuclio-function-name: onnx-wongkinyiu-yolov7
Body: {
    "image": "<base64_encoded_frame>",
    "threshold": 0.5
}

# 3. Nuclio 응답
[
    {"confidence": 0.95, "label": "car", "points": [100, 200, 300, 400], "type": "rectangle"},
    {"confidence": 0.87, "label": "person", "points": [50, 60, 120, 200], "type": "rectangle"}
]

# 4. CVAT: 응답을 Job 어노테이션으로 변환/저장
# source='auto', score=confidence로 LabeledShape 생성
```

### SAM (Segment Anything) 인터랙티브 호출 {#sam-integration}

```python
# Interactor 타입 함수 (SAM, DEXTR 등)

# 실시간 호출 (online mode):
POST /api/lambda/requests
{
    "function": "pth-facebookresearch-sam-vit-h",
    "job": 3,
    "frame": 5,
    "points": [[100, 200], [150, 250]],  # positive 포인트
    "neg_points": [[50, 50]],             # negative 포인트
    "shape": [90, 190, 200, 300]          # bbox 힌트 (선택)
}

# 응답:
{
    "points": [x0, y0, x1, y1, ..., xn, yn],  # polygon points
    "type": "polygon"
}
```

### FunctionCallRequestSerializer 파라미터 목록

```python
# cvat/apps/lambda_manager/serializers.py:FunctionCallRequestSerializer
class FunctionCallRequestSerializer(serializers.Serializer):
    function         = CharField          # 함수명 (필수)
    task             = IntegerField       # 태스크 ID (필수)
    job              = IntegerField       # 잡 ID (선택, 지정 시 해당 잡 범위만)
    threshold        = FloatField         # confidence threshold (선택)
    cleanup          = BooleanField       # 기존 어노테이션 삭제 여부 (기본: False)
    conv_mask_to_poly = BooleanField      # MASK → POLYGON 자동 변환 (선택)
    max_distance     = IntegerField       # TRACKER용 최대 이동 거리 픽셀 (선택)
    mapping          = DictField          # 클래스명 → 라벨명 매핑 (선택)
```

## 배치 처리 최적화

배치 처리는 **프레임 범위 파라미터 없이** Job 단위로만 범위를 지정한다.
CVAT은 100프레임씩 누적하여 서버에 일괄 제출하는 방식으로 메모리와 API 호출 횟수를 최적화한다.

```python
# cvat/apps/lambda_manager/views.py:L1021-L1025
# Accumulate data during 100 frames before submitting results.
# It is optimization to make fewer calls to our server.
if frame and frame % 100 == 0:
    collector.submit()

collector.submit()  # 남은 결과 마지막 일괄 제출
```

- 배치 범위 파라미터(`start_frame`, `end_frame` 등)는 없음
- 범위 제한 필요 시 `job` 파라미터로 Job 단위 지정
- `db_task.data.deleted_frames`에 있는 프레임은 건너뜀

## _invoke_directly (Nuclio 대시보드 없이 직접 호출)

`CVAT_NUCLIO_INVOKE_METHOD=direct` 설정 시, Nuclio 대시보드를 거치지 않고
AI 함수 컨테이너에 직접 HTTP POST 요청을 보낸다.

```python
# cvat/apps/lambda_manager/views.py:L141
def _invoke_directly(self, func, payload):
    # Docker 컨테이너 내부: host.docker.internal:{func.port}
    # 외부 실행: localhost:{func.port}
    url = f"http://host.docker.internal:{func.port}"  # Docker 20.10+ 필요
    session.post(url, timeout=NUCLIO_TIMEOUT, json=payload)
```

- **사용 시기**: Nuclio 대시보드 없이 로컬 Docker 컨테이너로 직접 테스트할 때
- **제약**: Docker 20.10+ 환경 필요 (Linux에서 `host.docker.internal` 지원)

## 결과 처리 흐름 (임시 저장 vs 영구 저장)

**CVAT의 처리 방식**: 오토라벨링 결과를 `source='auto'`로 **즉시 영구 저장**한다. 별도의 "임시 상태"가 없다.

```python
# cvat/apps/lambda_manager/views.py
# AI 결과 → LabeledDataSerializer로 변환 → PATCH /api/jobs/{id}/annotations

def _process_ai_results(job, results, label_mapping):
    shapes = []
    for item in results:
        if label := label_mapping.get(item['label']):
            shapes.append({
                "frame": frame_number,
                "label_id": label.id,
                "type": item["type"],
                "points": item["points"],
                "score": item.get("confidence", 1.0),
                "source": "auto",   # 출처 표시
                "attributes": []
            })
    # PUT /api/jobs/{id}/annotations 으로 저장
```

## 지원 모델 목록

```
serverless/
├── onnx/
│   ├── WongKinYiu/yolov7/      # YOLO v7 (detector)
│   └── dschoerk/                # GMM 배경 차감
├── pytorch/
│   ├── facebookresearch/        # Segment Anything Model (SAM)
│   ├── mmpose/hrnet32/          # Human Pose (skeleton detector)
│   └── shiyinzhang/iog/         # Inside-Outside Guidance (polygon)
├── openvino/
│   ├── omz/intel/face-detection # 얼굴 감지 + 나이/성별/감정
│   └── omz/public/mask-rcnn/    # Mask R-CNN (mask detector)
└── tensorflow/
    └── faster_rcnn_inception/   # Faster R-CNN (detector)
```

## 배포 방법

```bash
# serverless/deploy_cpu.sh
# Nuclio CLI(nuctl)로 function.yaml을 Nuclio에 배포
nuctl deploy --project-name cvat \
    --path serverless/onnx/WongKinYiu/yolov7/nuclio \
    --platform local

# 함수 목록 조회
GET /api/lambda/functions
# 특정 함수 정보
GET /api/lambda/functions/{function_id}
```

## 핵심 의사결정

- **Nuclio 게이트웨이 패턴**: AI 모델은 Nuclio 함수로 격리하고 CVAT 서버는 HTTP 호출만 담당. 모델 의존성 충돌과 GPU 자원 격리 문제를 해결.
- **function.yaml 메타데이터**: `spec` 필드에 라벨 정의 JSON을 포함하여 모델 자체에 라벨 정보를 묶어 배포한다.
- **FunctionKind 4종**: detector(프레임 단위 감지) / interactor(사용자 포인트 → shape) / tracker(다음 프레임 추적) / reid(재식별).
- **즉시 영구 저장**: 오토라벨링 결과는 임시 상태 없이 `source='auto'`로 바로 LabeledShape에 저장된다.
- **label mapping**: 클라이언트가 모델 출력 클래스명을 프로젝트 라벨명에 매핑한다. 매핑 없는 클래스는 무시된다.
- **Base64 이미지 전송**: HTTP body에 Base64 인코딩 이미지를 담아 전송하므로 대용량 프레임에서 메모리/네트워크 오버헤드가 크다.
- **100프레임 배치 누적**: 결과를 100프레임씩 누적해 일괄 PATCH로 서버에 제출하여 API 호출 횟수를 줄인다.
- **두 가지 호출 모드**: `dashboard`(Nuclio Dashboard 경유, 기본) / `direct`(`host.docker.internal:{port}`로 컨테이너 직접 호출).

## 독립 포팅 가이드

### 추출 난이도
**중** — function.yaml + main.py 패턴(모듈 05)은 단순하지만, CVAT의 LambdaGateway/FunctionViewSet은 Nuclio API 호출 + 결과 변환 + Job 어노테이션 저장 + label mapping까지 포함. Nuclio 자체 의존성을 분리한 단순 detector 호출 + 결과 저장은 200~300줄로 가능.

### 핵심 추출 대상 (필수 파일)
| 역할 | 원본 경로 | 포팅 시 행수/조치 |
|------|----------|-----------------|
| Lambda Gateway / FunctionViewSet | `cvat/apps/lambda_manager/views.py` | 1000줄+ — 핵심: invoke(), label mapping |
| Lambda 모델 (FunctionKind enum) | `cvat/apps/lambda_manager/models.py` | 50줄 |
| FunctionCallRequestSerializer | `cvat/apps/lambda_manager/serializers.py` | 50줄 |
| Nuclio function.yaml + main.py | `serverless/{framework}/{model}/nuclio/` | → 모듈 05 참조 |
| 100프레임 배치 처리 | `cvat/apps/lambda_manager/views.py:1021-1025` | 단순 패턴 |

### 외부 라이브러리 의존성
| 라이브러리 | 버전 | 대체 가능? | 비고 |
|----------|------|:--------:|------|
| Nuclio CLI (nuctl) | 1.15+ | 가능 | function.yaml만 필요. 직접 FastAPI/Flask로 변환 |
| requests | >=2.28 | 필수 | Nuclio HTTP 호출 |
| 모델별 (torch/onnxruntime/tf) | - | - | 각 모델 핸들러에서 import |

### 내부 CVAT 의존성과 분리 방법
| 의존 모듈 | 포팅 시 처리 |
|---------|-----------|
| `cvat.apps.engine.frame_provider` (프레임 디코딩) | 자체 영상/프레임 시스템 |
| `cvat.apps.engine.task` (Task/Job DB) | 작업 컨텍스트만 추출 |
| `LabeledShape` 저장 | 자체 어노테이션 모델로 변경 |
| Django RQ (cvat_worker_annotation) | 자체 비동기 워커 |

### 최소 동작 단위 (MVP)
- 단일 detector 모델 + Nuclio 또는 FastAPI 엔드포인트 1개
- API 1개: `POST /detect`(`{"function": "yolo", "frame": <base64>, "threshold": 0.5}`)
- 결과 저장: `POST /annotations` (source='auto')

### 포팅 단계 (체크리스트)
1. [ ] 모델 핸들러 코드를 FastAPI 마이크로서비스로 변환 (모듈 05)
2. [ ] Lambda Gateway에 해당하는 클라이언트 (model URL 결정 + invoke)
3. [ ] label mapping 로직 (모델 클래스명 → 사용자 라벨)
4. [ ] 결과를 어노테이션 형식으로 변환 (`source='auto'`)
5. [ ] 100프레임 배치 패턴 (메모리 절약)
6. [ ] threshold/confidence 처리

### 통합 지점 (이 모듈을 다른 시스템에 붙일 때)
- 입력: `{function_name, target(task/job/frame), mapping, threshold, conv_mask_to_poly}`
- 출력: 어노테이션 객체 리스트 + (비동기) 진행률
- 외부 인터페이스: `POST /api/lambda/requests` 시작, `GET /api/requests/{id}` 폴링

### 알려진 함정
- **Nuclio Dashboard Docker socket 의존**: Dashboard 사용 시 호스트 Docker 데몬 필수. K8s 모드 또는 직접 컨테이너 실행으로 우회.
- **Base64 이미지 메모리**: 4K 프레임은 base64 후 약 10MB. 100프레임 배치면 1GB 메모리. 스트리밍 또는 청크 단위 처리.
- **label mapping 누락**: 모델 클래스명이 사용자 라벨과 1:1 매칭 안 되면 무시됨. 명시적 매핑 필수.
- **threshold 타입**: 함수 응답의 confidence는 보통 string (`str(score)`). 클라이언트 float 변환 필요.
- **타임아웃**: function.yaml `eventTimeout: 30s`가 기본. 큰 모델은 늘려야 함. CVAT 서버측 `CVAT_NUCLIO_TIMEOUT=120` 별도.
- **즉시 영구 저장**: source='auto'로 즉시 DB 저장. 사용자 확인 단계 없음. 잘못 호출하면 기존 어노테이션 덮어쓰기 (cleanup=True 시).
- **동시 호출 OOM**: numWorkers > 1 + GPU 모델이면 동시에 GPU에 두 모델 로드 시도 → CUDA OOM. GPU 1개 = 워커 1개 권장.

## Docker 미사용 대응 (Critical)

### 문제: Nuclio Dashboard는 Docker socket 의존

`components/serverless/docker-compose.serverless.yml`의 Nuclio Dashboard는 `/var/run/docker.sock:/var/run/docker.sock`을 마운트하여 함수 컨테이너를 동적으로 생성/관리한다. 즉, **Nuclio Dashboard 자체는 Docker 데몬 없이는 동작하지 않는다.**

```yaml
# components/serverless/docker-compose.serverless.yml:8
nuclio:
  image: quay.io/nuclio/dashboard:1.15.9-amd64
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock  # ← Docker 의존
```

배포된 함수도 Nuclio가 만든 Docker 컨테이너로 실행되므로 함수 자체도 Docker가 필요하다.

### 대안 1: Nuclio Native(Kubernetes) 모드

Nuclio는 Kubernetes 플랫폼을 지원한다(`--platform kube`). 별도 K8s 클러스터가 있다면 Docker 데몬 없이도 함수 배포가 가능하지만, K8s 운영 비용 발생.

### 대안 2: function.yaml → Flask/FastAPI 직접 변환 (권장)

function.yaml의 핸들러 코드는 사실상 단순한 HTTP 핸들러다. Nuclio 의존성을 제거하고 Flask/FastAPI 라우터로 변환할 수 있다.

```python
# 변환 예: serverless/onnx/WongKinYiu/yolov7/nuclio/main.py 기준

# 원본 Nuclio 핸들러
def handler(context, event):
    data = event.body
    buf = io.BytesIO(base64.b64decode(data["image"]))
    threshold = float(data.get("threshold", 0.5))
    image = Image.open(buf).convert("RGB")
    results = context.user_data.model.infer(image, threshold)
    return context.Response(body=json.dumps(results), status_code=200)


# FastAPI로 변환
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI()
model = ModelHandler(labels)  # 기동 시 한 번만 로드

class InvokeRequest(BaseModel):
    image: str  # Base64
    threshold: float = 0.5

@app.post("/invoke")
async def invoke(req: InvokeRequest):
    buf = io.BytesIO(base64.b64decode(req.image))
    image = Image.open(buf).convert("RGB")
    return model.infer(image, req.threshold)
```

CVAT 서버 측은 `CVAT_NUCLIO_INVOKE_METHOD=direct`로 설정하고 `host.docker.internal:{port}` 대신 변환된 Flask/FastAPI 서버 주소를 가리키게 하면 된다.

### 대안 3: Backend 프로세스 내 임베딩 (단일 GPU 서버 권장)

단일 GPU 서버에서 운영하는 경우 모델을 backend 프로세스에 직접 임베딩하는 것이 가장 단순하다.

```python
# 장점:
# - HTTP 오버헤드 제거
# - Base64 인코딩 비용 제거 (numpy array 직접 전달)
# - 디버깅 용이
# 단점:
# - 모델 의존성과 backend 의존성이 충돌 가능 (예: torch 버전)
# - 모델 OOM 시 backend 전체 재시작
# - 워커 프로세스마다 모델 로드 → 메모리 중복
```

ONNX 모델은 `onnxruntime` 패키지만으로 동작하므로 의존성 충돌이 적고 임베딩에 적합하다.

### function.yaml에서 직접 추출 가능한 정보

기존 function.yaml은 Nuclio 사용 여부와 무관하게 다음 정보를 추출할 수 있다:

| 정보 | 추출 위치 |
|------|----------|
| 모델 핸들러 코드 | `main.py` (Nuclio context 인자만 제거하면 됨) |
| 전처리/후처리 로직 | `model_handler.py` (그대로 사용 가능) |
| 라벨 spec | `metadata.annotations.spec` (JSON 파싱) |
| 모델 가중치 다운로드 명령 | `spec.build.directives.preCopy` (RUN/wget 명령) |
| 베이스 이미지/시스템 패키지 | `spec.build.baseImage`, `apt install` 명령 |
| 모델 종류 | `metadata.annotations.type` (detector/interactor/tracker/reid) |
| HTTP 타임아웃 | `spec.eventTimeout` (예: `30s`) |
| 추론 입력 크기 제한 | `spec.triggers.myHttpTrigger.attributes.maxRequestBodySize` (32MB 기본) |

### 권장 경로

| 운영 규모 | 권장 |
|----------|------|
| 단일 GPU 서버, 소수 모델 | Backend 프로세스 내 임베딩 (대안 3) |
| 다중 GPU/모델 격리 필요 | Flask/FastAPI 마이크로서비스 + `INVOKE_METHOD=direct` (대안 2) |
| 클러스터 운영 | Nuclio Native + K8s (대안 1) |

자세한 통합 배포는 [deployment-without-docker.md](deployment-without-docker.md) 참고.
