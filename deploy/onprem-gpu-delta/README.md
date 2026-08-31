# klid-label GPU 전환 델타 — 서버 B(ai) 전용

> **이것은 전체 매체가 아니다.** 이미 설치된 **CPU 판 위에 얹는 교체분**이다.
> 먼저 `klid-onprem` CPU 판으로 `install.sh --role=ai` 가 끝나 있어야 한다.
>
> ★ **서버 A(app)에는 아무 상관이 없다.** 백엔드·프론트·httpd·PostgreSQL 은 손대지 않는다.
> 바뀌는 것은 서버 B 의 파이썬 패키지 셋 하나뿐이다.

## 0. 왜 델타인가 — 전체 매체를 다시 뜨지 않는 이유

GPU 판 전체 매체는 약 **5 GiB** 다. 그중 CPU 판과 **달라지는 것은 파이썬 휠 22개뿐**이고
나머지(백엔드 WAR·프론트 정적 자산·시스템 RPM·모델 가중치·라이선스 고지)는 **한 바이트도
같다.** 전체를 다시 반입하면 심의·전송·검증을 전부 다시 하게 되므로 바뀌는 것만 낸다.

## 1. 무엇이 바뀌나

| | CPU 판 (현재) | GPU 판 (이 델타) |
|---|---|---|
| `torch` | `2.12.0+cpu` | **`2.12.0+cu126`** |
| `torchvision` | `0.27.0+cpu` | **`0.27.0+cu126`** |
| 추론 런타임 | `onnxruntime 1.27.0` (CPU) | **`onnxruntime-gpu 1.26.0`** |
| CUDA 런타임 | 없음 | **`nvidia-*` 18개 + `triton`** (휠로 동봉) |
| `AI_DEVICE` | `cpu` | **`cuda`** |

**그 밖은 아무것도 바뀌지 않는다** — 애플리케이션 코드·모델 가중치·설정 구조 모두 그대로다.
코드는 처음부터 `AI_DEVICE` 로 갈리게 돼 있어서 **고칠 코드가 없다.**

### 판 선택 근거 (추정이 아니라 실측이다)

| 무엇 | 왜 그 판인가 |
|---|---|
| `cu126` | 장비 드라이버가 **570.86.10** 이라 CUDA 12.x 계통이 사정권이다. ⚠ `cu128` 인덱스에는 우리가 잠근 `torch 2.12.0` 이 **없다**(그 인덱스 최대가 2.11.0). `cu130` 은 드라이버 580 이상을 요구한다 |
| `onnxruntime-gpu` **1.26.0** | ⚠ **1.27.0 은 CUDA 13 을 요구한다**(`nvidia-cuda-runtime-cu13~=13.0`). 그건 드라이버 580 이상이라 이 장비에서 안 돈다. CPU 판이 1.27.0 이라고 GPU 판도 1.27.0 을 고르면 **바로 그 함정에 빠진다** |

## 2. 적용

```bash
# 서버 B 에서
tar -xzf klid-onprem-gpu-delta-<커밋>.tgz
cd gpu-delta

sudo ./scripts/apply-gpu.sh --check        # ① 아무것도 바꾸지 않고 현재 상태만 본다
sudo ./scripts/apply-gpu.sh --set-device   # ② 교체 + 검증 + AI_DEVICE=cuda
sudo systemctl restart klid-ai-server      # ③ 재기동 (스크립트가 대신 하지 않는다)
```

**②를 여러 번 돌려도 안전하다**(멱등). 검증에 실패하면 `AI_DEVICE` 를 바꾸지 않는다 —
바꿔 두면 기동은 되는데 **조용히 CPU 로 돌아** 원인을 찾기 어려워지기 때문이다.

## 3. ★ 드라이버는 이 델타에 없다

NVIDIA 드라이버는 **커널 모듈**이라 파이썬 휠에 들어가지 않는다. 그리고 이 장비는
관제지원시스템과 공동 배치라 **우리가 드라이버를 덮어쓰면 안 된다**(ffmpeg 을 우리가
자동 설치하지 않는 것과 같은 이유다).

- 드라이버가 없으면 `apply-gpu.sh` 는 **교체는 하되 "GPU 로 돌지 않는다"고 분명히 말한다.**
- 드라이버 설치는 **장비 담당이 먼저** 한다. 커널 판(`uname -r`)에 묶이므로 그 값을 확인하고
  RHEL 8.9 x86_64 용을 받아야 한다.
- 드라이버를 넣은 뒤 `apply-gpu.sh --check` 를 다시 돌리면 된다.

## 4. ★★ GPU 전환의 실패는 조용하다 — 반드시 확인할 것

바꿨다고 믿으면서 CPU 로 계속 도는 상태가 **가장 흔한 실패**다. 서버는 정상 기동하고
헬스체크도 200 이며 추론 API 도 응답한다. **"왜 느리지"로만 나타난다.**

`apply-gpu.sh` 가 세 축을 자동으로 본다:

| 축 | 통과 못 하면 |
|---|---|
| `torch.__version__` 에 `+cu` | 휠 교체가 안 됐다 |
| `ort.get_available_providers()` 에 `CUDAExecutionProvider` | CPU 판 onnxruntime 이 남아 있다 → **YOLOX 탐지가 CPU 로 돈다** |
| `torch.cuda.is_available()` | 드라이버 미설치이거나 커널 모듈 미적재 |

세 축을 다 통과해도 마지막 하나가 남는다 — **추론을 1회 돌린 직후 `nvidia-smi` 의 프로세스
목록에 python 이 보이는지** 본다. 여기가 비어 있으면 실제로는 GPU 를 안 쓰는 것이다.

## 5. 되돌리기

`AI_DEVICE=cpu` 로 되돌리면 **동작은 CPU 로 돌아온다.** 다만 설치된 휠은 CUDA 판 그대로라
디스크를 약 6GB 더 쓴다. 완전 복귀는 재설치다. 원본 설정은 `apply-gpu.sh` 가
`/etc/klid/ai-server.env.bak.<시각>` 으로 남긴다.

## 6. ⚠ 라이선스 — 이 델타로 라이선스 표면이 바뀐다

CPU 판 매체는 라이선스가 **전부 오픈소스**였다. 이 델타는 거기에 **NVIDIA 독점 EULA
계통을 새로 들인다**(`nvidia-*` CUDA 런타임 라이브러리 재배포). `licenses/` 에 해당
고지를 동봉했다. 심의에서 이 축이 새로 생긴다는 점을 반드시 함께 보고할 것.

## 7. 왜 3GB 를 넘는가 (줄이려다 실측으로 막힌 기록)

델타는 **약 3.6 GiB** 이며 더 줄일 수 없다. 큰 것 넷을 빼 보고 하나씩 실측했다:

| 뺀 것 | 결과 |
|---|---|
| `nvidia-cusparselt` (274MB) | ✗ `import torch` 가 `libcusparseLt.so.0` 를 찾는다 |
| `nvidia-nccl` (276MB) | ✗ `import torch` 가 `libnccl.so.2` 를 찾는다 |
| `nvidia-nvshmem` (133MB) | ✗ `import torch` 가 `libnvshmem_host.so.3` 를 찾는다 |
| `triton` (192MB) | 뺄 수 있으나 3.4 GiB 라 목표에 못 미치고, SAM2 가 컴파일 경로를 타면 깨진다 |

**`torch` 의 CUDA 판은 import 시점에 CUDA 라이브러리를 전부 preload 한다** — "다중 GPU 전용
이니 지연 로드일 것"이라는 추정이 셋 다 틀렸다. 그래서 전송이 3GB 로 제한되면 **매체를
2권으로 나누는 것**이 유일한 방법이다(내용을 줄이는 것이 아니라).

```bash
# 나눠 보낼 때
split -b 1800m klid-onprem-gpu-delta-<커밋>.tgz klid-gpu-delta.part-
# 받는 쪽에서
cat klid-gpu-delta.part-* > klid-onprem-gpu-delta.tgz
sha256sum klid-onprem-gpu-delta.tgz      # 동봉한 값과 대조
```
