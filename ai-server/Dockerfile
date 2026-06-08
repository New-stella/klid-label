# syntax=docker/dockerfile:1
# ============================================================================
# klid-label ai-server (FastAPI) — GPU 추론 서버
#   베이스: pytorch/pytorch:2.2.2-cuda12.1-cudnn8-runtime (CUDA torch 포함)
#   설치 검증 목적이라 AI_MOCK_MODE=true 로 가중치 없이 기동 (가중치 부재 시 mock)
# ============================================================================
FROM pytorch/pytorch:2.2.2-cuda12.1-cudnn8-runtime

WORKDIR /app

# opencv 런타임 의존성(libGL 등) + curl(healthcheck)
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 curl \
    && rm -rf /var/lib/apt/lists/*

# torch/torchvision 은 베이스 이미지에 포함 — requirements 에서 제외하고 나머지만 설치
COPY requirements.txt ./requirements.txt
RUN grep -ivE '^(torch|torchvision)\b' requirements.txt > requirements.runtime.txt \
    && pip install --no-cache-dir -r requirements.runtime.txt

COPY app ./app

# 비루트 유저
RUN useradd -r -m -d /home/app app && chown -R app:app /app
USER app

EXPOSE 9300

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "9300"]
