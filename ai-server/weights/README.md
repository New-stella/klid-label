# ai-server/weights — 모델 가중치 출처 기록

가중치 **바이너리는 git 에 두지 않는다**(`.gitignore` 의 `weights/*`). 대신 **출처·릴리스·해시·라이선스를
이 파일에 남겨** 누구나 같은 파일을 다시 만들 수 있게 한다.

> 왜 기록이 필요한가: 가중치가 없어도 ai-server 는 크래시하지 않고 mock 응답
> (`mock_reason=weights_missing`)으로 조용히 떨어진다. 출처 기록이 없으면 "이 파일이 무엇이고
> 어디서 왔는지" 를 아무도 확인할 수 없고, 온프렘 반입 번들도 재현할 수 없다.

## yolox_s.onnx — 탐지 백엔드 (필수)

| 항목 | 값 |
|------|-----|
| 출처 | [Megvii-BaseDetection/YOLOX](https://github.com/Megvii-BaseDetection/YOLOX) 공식 릴리스 자산 |
| 릴리스 태그 | `0.1.1rc0` (2021-08-18) |
| URL | `https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_s.onnx` |
| SHA256 | `c5c2d13e59ae883e6af3b45daea64af4833a4951c92d116ec270d9ddbe998063` |
| 크기 | 35,858,002 바이트 |
| 라이선스 | **Apache-2.0** (YOLOX 저장소 라이선스) |
| 소비처 | `app/models/yolox_loader.py` (ONNX Runtime 세션), 설정 키 `YOLOX_WEIGHTS_PATH` |

받는 방법 — 해시가 다르면 **실패**한다(조용히 넘어가지 않는다):

```bash
cd ai-server
./scripts/download-yolox-weights.sh
```

기대 해시의 단일 출처는 **`weights/yolox_s.onnx.sha256`**(git 추적)이다. 다운로드 스크립트와
온프렘 수집 스크립트(`deploy/onprem/scripts/package/30-collect-ai-server.sh`)가 **같은 파일을 읽어**
검증하므로, 해시를 바꿀 일이 생기면 이 한 곳만 고친다.

## SAM2 — 세그멘테이션 (이 디렉터리에 파일을 두지 않는다)

SAM2 는 로컬 가중치 파일이 아니라 **HuggingFace Hub 경유**로 적재한다
(`app/models/sam2_loader.py` → `SAM2ImagePredictor.from_pretrained(sam2_model_id)`).

| 항목 | 값 |
|------|-----|
| 기본 모델 ID | `facebook/sam2-hiera-tiny` (설정 키 `SAM2_MODEL_ID`) |
| 라이선스 | Apache-2.0 (Meta 공식 `facebookresearch/sam2`) |
| 폐쇄망 반입 | `30-collect-ai-server.sh` 의 HF prefetch(`HF_SAM2_MODEL_ID`)가 `models/hf-cache/` 로 수집 |

> `weights/sam2_*.pt` 같은 로컬 파일은 **쓰지 않는다.** 그 파일명은 ultralytics SAM 시절의 잔재이며
> 현재 로더에는 그런 경로 설정 자체가 없다.

## 여기에 두면 안 되는 것 — ultralytics 배포 가중치 (AGPL-3.0)

`yolov8*.pt` · `sam2_*.pt` 등 **`github.com/ultralytics/assets` 배포 가중치는 AGPL-3.0** 이라
이 프로젝트의 배제 정책 대상이다(탐지·분할 모두 permissive 백엔드로 구성 — 저장소 `CLAUDE.md`
「라이선스 정책」). 이 파일들을 받아 오던 `scripts/download-weights.sh` ·
`scripts/download-sam2-weights.sh` 는 그래서 제거됐다.

⚠ 이 디렉터리에 AGPL 산출물을 떨어뜨리면 **그대로 납품물에 실린다.** 온프렘 패키징이 두 경로로
가져가기 때문이다 — `30-collect-ai-server.sh` 는 `yolox_s.onnx` 만 골라 `models/weights/` 로
복사하지만, `60-collect-buildtools.sh` 의 오프라인 빌드 키트는 **`ai-server/` 트리를 통째로**
`src/ai-server/` 로 복사한다(제외 목록에 `weights/` 가 없다).
