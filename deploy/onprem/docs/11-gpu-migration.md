# 11. GPU 전환 가이드 (이번 반입은 CPU 전용)

> **이번 반입물은 CPU 전용이다.** ai 서버 장비에 GPU 가 있어도 그대로 두면 **CPU 로 돈다.**
> 그리고 그 사실이 **아무 데서도 오류로 드러나지 않는다** — 서버는 정상 기동하고 헬스체크도
> 통과하며 추론 API 도 200 을 돌려준다. 나중에 "왜 이렇게 느리지"로만 나타난다.
> 이 문서는 그 상태를 기록하고, 나중에 GPU 로 옮길 때 무엇을 바꿔야 하는지를 남긴다.
>
> ⚠ **이 문서만 보고 바로 실행하지 마라.** 아래 §2 의 선행 확인을 먼저 하지 않으면
> 받아야 할 CUDA 판을 확정할 수 없다. 이 문서는 **확정된 절차가 아니라 확정하는 방법**이다.

## 1. 지금 상태 — 코드는 이미 준비돼 있다

바꿀 것은 **코드가 아니라 조달(무엇을 반입하는가)과 설정**이다.

| 축 | 현재 | 근거 |
|---|---|---|
| 설정 키 | `AI_DEVICE` 가 `cuda`/`cpu` 를 받는다. 기본 `cpu` | `ai-server/app/config.py` (`ai_device`) |
| YOLOX(탐지) | `cuda` 면 `["CUDAExecutionProvider", "CPUExecutionProvider"]`, 아니면 CPU 만 | `ai-server/app/models/yolox_loader.py` (`_build_yolox_backend`) |
| SAM2(분할·Track) | `SAM2ImagePredictor.from_pretrained(model_id, device=settings.ai_device)` | `ai-server/app/models/sam2_loader.py` |
| 반입 wheel | torch/torchvision 을 **CPU 인덱스**에서만 받는다 | `scripts/lib/versions.sh` (`PYTORCH_CPU_INDEX_URL`) |
| 반입 wheel | `onnxruntime`(CPU 판) | `ai-server/requirements.txt` |
| 설치 기본값 | `AI_DEVICE=cpu` | `config/ai-server/env.template` |

> ★ SAM2 로더 주석이 밝히듯 Meta 의 `from_pretrained` 기본값은 `"cuda"` 다.
> 우리가 `device=` 를 **명시 전달**하기 때문에 CPU 에서 죽지 않는다.
> 그 명시 전달을 걷어내면 CPU 장비에서 로드가 실패하고, 로더가 예외를 삼켜
> **mock 응답으로 조용히 떨어진다.** 건드리지 말 것.

## 2. 선행 확인 — 이것부터 하지 않으면 아무것도 확정할 수 없다

**드라이버 버전이 쓸 수 있는 CUDA 상한을 정한다.** 순서를 바꾸면 안 된다 —
CUDA 판을 먼저 고르고 드라이버를 나중에 보면, 받아 온 wheel 이 그 장비에서 안 돈다.

```bash
# ai 서버(서버 B)에서 실행
nvidia-smi                      # 드라이버 버전 · GPU 모델 · VRAM
lsmod | grep nvidia             # 커널 모듈이 실제로 적재돼 있는가
ls /dev/nvidia*                 # 디바이스 노드
```

- `nvidia-smi` 가 **없거나 실패**하면 → 드라이버가 설치되지 않은 것이다. GPU 전환의
  첫 작업은 wheel 조달이 아니라 **드라이버 설치**다(§3-③).
- `nvidia-smi` 우상단의 `CUDA Version:` 은 **그 드라이버가 지원하는 상한**이지
  설치된 CUDA 툴킷 판이 아니다. 이 상한 이하의 CUDA 빌드만 쓸 수 있다.
- VRAM 도 적어 둔다 — SAM2 hiera 계열은 모델보다 **입력 해상도**에 따라 메모리가 는다.

### 현재까지 확인된 값

| 항목 | 값 | 출처 |
|---|---|---|
| 그래픽 드라이버 | **570.86.10** | 사업 담당 확인 (2026-08-31) |
| 지원 CUDA 상한 | 570 계열이므로 **12.8 계통**으로 보인다(12.8 의 최소 드라이버가 570.26) | 추정 — `nvidia-smi` 표기로 확인 필요 |
| GPU 모델 | 미확인 | — |
| VRAM | 미확인 | — |

⚠ **드라이버 버전 하나로는 아래 절차가 끝나지 않는다.** `nvidia-smi` 우상단의 `CUDA Version:`
표기가 상한의 정본이고, VRAM 은 SAM2 의 입력 해상도 한계를 정한다. 둘 다 받기 전에는 wheel 을
조달하지 않는다. 다만 570 계열이 확인됐으므로 **cu12x 빌드는 사정권**이고 CUDA 13 계열은
드라이버를 더 올려야 한다는 것까지는 정해졌다.

### 확정 절차

1. `nvidia-smi` 의 드라이버 버전 · 지원 CUDA 상한을 적는다.
2. **PyTorch 배포 인덱스**에서 그 상한 이하의 CUDA 빌드를 고른다
   (`https://download.pytorch.org/whl/cu<XXX>` 형태로 CUDA 판별 인덱스가 나뉜다).
   `versions.sh` 에 핀된 `torch`/`torchvision` 판이 그 인덱스에 있는지 확인한다.
3. **`onnxruntime-gpu`** 의 그 판이 요구하는 CUDA·cuDNN 조합을 릴리스 문서에서 확인한다.
   ⚠ onnxruntime 은 판마다 요구 CUDA 가 다르고 PyTorch 와 **다른 조합을 요구할 수 있다.**
   두 요구가 겹치는 지점을 찾아야 한다.
4. 그 결과로 CUDA 판이 **하나로 확정**된다. 이 문서는 그 값을 적어 두지 않는다 —
   장비를 보지 않고 적으면 틀린 값이 굳는다.

> ⚠ **여기에 구체적인 CUDA 버전이나 wheel URL 이 적혀 있지 않은 것은 누락이 아니라 의도다.**
> 드라이버 버전을 모르는 상태에서 그 값을 적으면, 다음 사람이 확인 없이 그대로 받아
> 장비에서 안 도는 wheel 을 폐쇄망까지 들고 들어가게 된다.

## 3. 전환에 필요한 네 가지

### ① torch CUDA wheel 로 교체 (조달)

`scripts/lib/versions.sh` 의 `PYTORCH_CPU_INDEX_URL` 이 CPU 인덱스를 가리키고,
`30-collect-ai-server.sh` 가 **PyPI 폴백 없이** 그 인덱스에서만 받는다
(폴백을 두지 않은 이유는 "조용히 다른 판이 섞이는 것"을 막기 위해서다 — 그 성질은 유지한다).

- 인덱스를 §2 에서 확정한 CUDA 인덱스로 바꾼다.
- 그 인덱스에 핀된 판이 없으면 **판을 조정**해야 한다. 그 경우 `ai-server/requirements.in`
  → lock 재생성이 따라온다.
- ⚠ CPU 판과 CUDA 판을 **섞지 마라.** `torch` 만 CUDA 로 바꾸고 `torchvision` 을 CPU 로 두면
  import 시점에 ABI 불일치로 죽는다.

### ② `onnxruntime` → `onnxruntime-gpu` (패키지 이름이 다르다)

`CUDAExecutionProvider` 는 **CPU 판 `onnxruntime` 에 들어 있지 않다.**
`AI_DEVICE=cuda` 로 바꾸기만 하면 provider 를 못 찾아 CPU 로 폴백하거나 세션 생성이 실패한다.

- `ai-server/requirements.in` 의 `onnxruntime` 을 `onnxruntime-gpu` 로 **교체**한다(추가 아님).
- ⚠ **둘을 함께 설치하지 마라.** 같은 `onnxruntime` 파이썬 모듈을 제공해 서로 덮어쓴다.
- lock 재생성이 필요하다:
  ```bash
  cd ai-server
  .venv/bin/pip-compile --no-header --output-file requirements.txt requirements.in
  ```
- ⚠ **lock 재생성은 이 반입 작업의 범위 밖이다.** 다른 의존성 판까지 함께 움직일 수 있으므로
  재생성 뒤에는 `pytest` 와 오프라인 설치 리허설(`docs/02-build-package.md`)을 다시 돌린다.

### ③ NVIDIA 드라이버 (pip 로 해결되지 않는다)

- 드라이버는 **커널 모듈**이라 pip wheel 에 들어 있지 않다. 별도로 설치해야 한다.
- 폐쇄망이므로 RHEL 8.9 x86_64 용 드라이버(및 필요 시 `dkms`·커널 헤더)를 **미리 받아
  매체에 실어야** 한다. 커널 버전에 묶이므로 대상 장비의 `uname -r` 을 먼저 확인한다.
- ⚠ **라이선스 성격이 다르다.** NVIDIA 드라이버와 `nvidia-*` 런타임 라이브러리는
  **독점 EULA** 다. 지금 이 매체의 라이선스 표면은 전부 오픈소스인데,
  GPU 로 전환하면 거기에 **독점 EULA 계통이 새로 들어온다.**
  `licenses/manual/OVERRIDES.tsv` 에 그 항목을 추가하고 EULA 전문을
  `licenses/manual/texts/` 에 넣어야 한다(자동 수집으로는 안 잡힌다).
- ⚠ 이 장비가 관제지원시스템과 공동 배치라면 **드라이버 설치는 관제 팀과 협의**한다.
  ffmpeg 을 우리가 자동 설치하지 않는 것과 같은 이유다(다른 시스템이 쓰던 것을 덮어쓴다).

### ④ `AI_DEVICE=cuda`

`/etc/klid/ai-server.env` 의 값을 바꾸고 `klid-ai-server` 를 재기동한다.
①②③ 없이 이 값만 바꾸면 **아무 일도 일어나지 않거나 조용히 CPU 로 돈다.**

```bash
sudo sed -i 's/^AI_DEVICE=.*/AI_DEVICE=cuda/' /etc/klid/ai-server.env
sudo systemctl restart klid-ai-server
journalctl -u klid-ai-server -n 50 --no-pager | grep -i 'device='
```

기동 로그에 `[YOLOX] loading onnx session device=cuda` 가 찍히는지 본다.
`device=cuda` 인데 실제 provider 가 CPU 로 잡히는 경우가 있으므로 §5 로 확인한다.

## 4. 대가 (전환 전에 합의할 것)

| 항목 | 내용 |
|---|---|
| 매체 용량 | **약 +3GB 추정** — torch CUDA wheel 과 `nvidia-*` 런타임 wheel 이 대부분이다. 실측값은 아니며 확정한 CUDA 판으로 실제 받아 봐야 안다 |
| 라이선스 | `nvidia-*` 런타임 재배포로 **NVIDIA 독점 EULA 계통이 추가**된다. 지금은 오픈소스 축만 있다 |
| 검증 | 추론 경로 **재검증 필요** — YOLOX 탐지 / SAM2 분할 / SAM2 Track 세 경로를 실제 영상으로 다시 돌린다. 디바이스가 바뀌면 수치가 미세하게 달라질 수 있다 |
| 운영 | GPU 메모리 고갈은 CPU 와 다른 실패 양상을 낸다(`CUDA out of memory`). 동시 요청 한도를 재검토한다 |
| 롤백 | `AI_DEVICE=cpu` 로 되돌려도 **wheel 은 CUDA 판 그대로**다. 완전 롤백은 재설치다 |

## 5. 전환 후 확인 (건성으로 넘어가지 말 것)

GPU 전환의 실패는 **조용하다.** 아래를 실제로 확인하지 않으면
"GPU 로 바꿨다"고 믿으면서 CPU 로 계속 도는 상태가 된다.

```bash
# ai 서버에서
sudo -u klid /opt/klid/ai/venv/bin/python - <<'PY'
import onnxruntime as ort, torch
print("ort providers:", ort.get_available_providers())   # CUDAExecutionProvider 가 있어야 한다
print("torch.cuda.is_available():", torch.cuda.is_available())
print("torch device count:", torch.cuda.device_count())
PY

nvidia-smi              # 추론을 한 번 돌린 직후 프로세스 목록에 python 이 보이는가
```

- `ort.get_available_providers()` 에 `CUDAExecutionProvider` 가 **없으면**
  ②(패키지 교체)가 안 된 것이다.
- `torch.cuda.is_available()` 이 `False` 면 ①(CPU wheel) 또는 ③(드라이버)이 안 된 것이다.
- 둘 다 통과하는데 `nvidia-smi` 프로세스 목록이 비어 있으면 추론이 실제로 GPU 를 안 쓰는 것이다.

## 6. 함께 고쳐야 할 곳

GPU 로 전환하면 아래 서술이 **사실과 달라진다.** 함께 고칠 것.

- `config/ai-server/env.template` — `AI_DEVICE` 위 주석
- `docs/00-overview.md` — 구성도의 서버 B `※ CPU 전용`
- `docs/01-prerequisites.md` — 대상 서버 요구사항(드라이버·VRAM)
- `docs/02-build-package.md` — 수집 절차(CUDA 인덱스·드라이버 반입)
- `licenses/manual/OVERRIDES.tsv` + `licenses/manual/texts/` — NVIDIA EULA
