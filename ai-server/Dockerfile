# syntax=docker/dockerfile:1
# ============================================================================
# klid-label ai-server (FastAPI) — 로컬/설치 검증용 이미지
#   ※ 운영 배포는 Docker 가 아님 — 호스트 venv + uvicorn 직접 설치
#     (pip install -r requirements.txt). 이 Dockerfile 은 의존성 설치 정합성을
#     검증하는 로컬 전용 이미지로, AI_MOCK_MODE=true 로 가중치 없이 기동한다.
#
#   베이스: pytorch/pytorch:2.5.1-cuda12.1-cudnn9-runtime
#     - torch 2.5.1 은 numpy 2.x ABI 호환 + Meta sam2(SAM-2) 의 torch>=2.5.1 요구를 충족한다.
#       (구 base torch 2.4.1 은 sam2 의 torch>=2.5.1 미충족 → 설치 시 torch 재다운로드/충돌.
#        torch 2.0.1 은 numpy 1.x ABI 라 numpy<2 강제로 trackers(numpy>=2.0.2) 설치 불가였다.)
#       base 를 torch 2.5.1 / numpy 2.x 호환으로 올려 yolox(onnxruntime)·sam2·rtdetr 설치를 지원한다.
#     - CUDA 12.x base 는 실제 GPU 추론 시 호스트 NVIDIA 드라이버 >=525/535 가
#       필요하나, mock(AI_MOCK_MODE=true) 기동·이미지 빌드에는 CUDA/GPU 가 불필요하다.
# ============================================================================
FROM pytorch/pytorch:2.5.1-cuda12.1-cudnn9-runtime

WORKDIR /app

# opencv 런타임 의존성(libGL 등) + curl(healthcheck) + git(sam2 git+ 의존성 설치)
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 curl git \
    && rm -rf /var/lib/apt/lists/*

# torch/torchvision 은 베이스 이미지에 포함 — requirements 에서 제외하고 나머지만 설치.
# numpy 는 requirements lock(numpy==2.4.6)이 관리한다 (base torch 가 numpy 2.x 호환).
COPY requirements.txt ./requirements.txt
RUN grep -ivE '^(torch|torchvision)\b' requirements.txt > requirements.runtime.txt \
    && pip install --no-cache-dir -r requirements.runtime.txt

COPY app ./app

# 비루트 유저
# HF 캐시 디렉토리(/app/.hf-cache)를 app 소유로 미리 생성 — docker-compose.local.yml 의
# named volume 이 이 마운트포인트의 소유권(app:app)을 빈 볼륨 초기화 시 상속하도록 한다.
# (미생성 시 Docker 가 root 소유로 마운트포인트를 만들어 비루트 app 의 HF 다운로드가 권한 거부됨)
RUN useradd -r -m -d /home/app app \
    && mkdir -p /app/.hf-cache \
    && chown -R app:app /app
USER app

EXPOSE 9300

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "9300"]
