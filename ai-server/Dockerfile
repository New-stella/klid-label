# syntax=docker/dockerfile:1
# ============================================================================
# klid-label ai-server (FastAPI) — GPU 추론 서버
#   베이스: pytorch/pytorch:2.0.1-cuda11.7-cudnn8-runtime
#     - 호스트 NVIDIA 드라이버(515.x → CUDA 11.7 한계)에 맞춰 CUDA 11.7 토치 사용.
#       CUDA 12.x 이미지는 "driver too old" 로 torch.cuda 비활성됨.
#   설치 검증 목적이라 AI_MOCK_MODE=true 로 가중치 없이 기동 (가중치 부재 시 mock)
# ============================================================================
FROM pytorch/pytorch:2.0.1-cuda11.7-cudnn8-runtime

WORKDIR /app

# opencv 런타임 의존성(libGL 등) + curl(healthcheck)
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 curl \
    && rm -rf /var/lib/apt/lists/*

# torch/torchvision 은 베이스 이미지에 포함 — requirements 에서 제외하고 나머지만 설치.
# numpy<2 고정: torch 2.0.x 는 numpy 1.x 로 컴파일되어 numpy 2.x 와 ABI 비호환.
COPY requirements.txt ./requirements.txt
RUN grep -ivE '^(torch|torchvision)\b' requirements.txt > requirements.runtime.txt \
    && sed -i 's/^numpy.*/numpy<2/' requirements.runtime.txt \
    && pip install --no-cache-dir 'numpy<2' -r requirements.runtime.txt

COPY app ./app

# 비루트 유저
RUN useradd -r -m -d /home/app app && chown -R app:app /app
USER app

EXPOSE 9300

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "9300"]
