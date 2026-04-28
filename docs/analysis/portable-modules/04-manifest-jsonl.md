# manifest.jsonl 포맷 (영상 청크 인덱스)

## 한 줄 요약
긴 영상을 프레임 번호로 빠르게 seek/decode하기 위한 키프레임 인덱스 파일 포맷. JSONL(한 줄 한 객체)로 저장되며, PyAV 기반 영상 스캔 → 키프레임 PTS 기록 → 임의 프레임 빠른 seek를 가능하게 한다.

## 추출 가치
**왜 떼어 쓸 가치가 있는가**
- 1시간짜리 영상의 5000번 프레임에 즉시 가려면 매번 처음부터 디코딩하면 수십 초 소요. manifest로 가까운 키프레임을 찾고 그 위치부터 seek하면 1초 미만.
- CVAT은 영상 1건 = 어노테이션 작업 1건 모델이라 이 인덱스가 핵심. 청크 기반 영상 어노테이션 도구를 처음 만들 때 직접 설계할 만한 포맷이다.
- 포맷이 단순(JSONL)하고 의존성이 PyAV(`av`) 1개뿐 → 단독 추출 매우 쉽다.

## 원본 코드 위치

| 역할 | 파일:라인 | 핵심 함수/클래스 |
|------|---------|--------------|
| Manifest 베이스 | `cvat/utils/dataset_manifest/core.py:390-424` | `_Manifest` (FILE_NAME, VERSION, TYPE) |
| 인덱스 파일 | `cvat/utils/dataset_manifest/core.py:429-495` | `_Index` (offset 룩업, JSON dump/load) |
| Manager 베이스 | `cvat/utils/dataset_manifest/core.py:498-609` | `_ManifestManager` (read/iter) |
| 비디오 매니저 | `cvat/utils/dataset_manifest/core.py:612-687` | `VideoManifestManager` (PyAV 스캔) |
| 비디오 매니저 검증 | `cvat/utils/dataset_manifest/core.py:689-727` | `VideoManifestValidator` (key frame 검증) |
| 이미지셋 매니저 | `cvat/utils/dataset_manifest/core.py:736-820` | `ImageManifestManager` |
| VideoStreamReader (PyAV 스캔) | `cvat/utils/dataset_manifest/core.py:41-249` | `VideoStreamReader` |
| 검증 함수 | `cvat/utils/dataset_manifest/core.py:991-1000` | `is_manifest`, `is_video_manifest`, `is_dataset_manifest` |
| 에러 정의 | `cvat/utils/dataset_manifest/errors.py` | `InvalidManifestError`, `InvalidVideoError` |

## 알고리즘/프로토콜 핵심

### 비디오 manifest.jsonl 정확한 구조

```jsonl
{"version":"1.1"}
{"type":"video"}
{"properties":{"name":"video.mp4","resolution":[1920,1080],"length":300,"chapters":[]}}
{"number":0,"pts":0,"checksum":"e0b3..."}
{"number":12,"pts":12012,"checksum":"a1c4..."}
{"number":24,"pts":24024,"checksum":"b2d5..."}
...
```

| 라인 | 내용 |
|------|------|
| 1 | `{"version": "1.1"}` — 포맷 버전 |
| 2 | `{"type": "video"}` |
| 3 | `{"properties": {"name": ..., "resolution": [w, h], "length": N, "chapters": [...]}}` |
| 4~ | 키프레임 1줄씩: `{"number": int, "pts": int, "checksum": "md5hex"}` |

**비디오 manifest는 키프레임만 기록**한다. 중간 프레임은 가까운 키프레임에서 디코딩 시작.

### 이미지셋 manifest.jsonl

```jsonl
{"version":"1.1"}
{"type":"images"}
{"name":"frame_000001","extension":".jpg","width":1920,"height":1080,"checksum":"..."}
{"name":"frame_000002","extension":".jpg","width":1920,"height":1080,"checksum":"..."}
...
```

| 라인 | 내용 |
|------|------|
| 1 | `{"version": "1.1"}` |
| 2 | `{"type": "images"}` |
| 3~ | 이미지 한 장당 1줄: `{"name": str, "extension": str, "width": int, "height": int, ...}` |

이미지 manifest는 헤더 2줄, 비디오는 헤더 3줄(properties 포함).

### index.json (룩업 가속)

매번 manifest 파일을 처음부터 읽지 않도록, 각 데이터 라인의 **byte offset**을 별도 인덱스에 저장.

```json
{"0": 78, "1": 120, "2": 162, ...}
```

key는 데이터 라인 번호(0-based, 헤더 제외), value는 manifest.jsonl 내 byte offset. 사용 시:
```python
# 5번째 키프레임 정보
with open("manifest.jsonl", "r") as f:
    f.seek(index[5])
    line = f.readline()
    data = json.loads(line)
```

### PTS (Presentation TimeStamp) 의미

- libavcodec의 `frame.pts` 값을 그대로 기록.
- 단위는 video stream의 `time_base`. 보통 1/12800초 또는 1/90000초.
- 실제 시간 = `pts * time_base` (초).
- seek 시 `container.seek(offset=pts, stream=video_stream)` 호출 → 해당 PTS 이전의 가장 가까운 키프레임으로 점프.

### 스캔 알고리즘 (VideoStreamReader.__iter__ — core.py 200줄대)

```
for packet in container.demux(video_stream):
    for frame in packet.decode():
        if frame.pict_type == av.video.frame.PictureType.I:   # I-frame (키프레임)
            yield (frame_number, frame.pts, md5_hash(frame))
        frame_number += 1
```

- 모든 프레임을 디코딩하지만 키프레임만 yield.
- `frame.pict_type` 으로 I/P/B 구분.
- checksum은 첫 프레임 데이터의 MD5 (bgr24).

### force=True 옵션 (비표준 영상 허용)

기본은 첫 프레임이 키프레임이 아니면 `InvalidVideoError`. `force=True` 시 첫 프레임 검사를 건너뛴다 — 스트림 녹화/일부 cctv 영상에 사용.

### 키프레임 검증 (VideoManifestValidator.validate_seek_key_frames — core.py:713)

업로드된 manifest의 키프레임 PTS로 실제 영상에서 seek해서 같은 PTS 프레임이 나오는지 확인. PTS가 어긋나면 manifest는 다른 영상의 것이라고 판단 → 거부.

## 의존성

### 외부 라이브러리

| 라이브러리 | 버전 | 용도 | 대체 가능? |
|----------|------|------|---------|
| av (PyAV) | >=10 | 영상 디코딩 + PTS/키프레임 추출 | 필수. ffmpeg-python(subprocess)로 대체 가능하나 큰 손실 |
| Pillow | >=9 | 이미지 manifest의 width/height 추출 | 이미지 manifest에만 필요 |

### CVAT 내부 의존성

| 의존 모듈 | 분리 방법 |
|----------|---------|
| `cvat.utils.dataset_manifest.utils.MemOpenable`, `NamedOpenable`, `Openable` | `pathlib.Path` 또는 `BinaryIO` 직접 사용으로 단순화 가능 |
| `cvat.utils.dataset_manifest.utils.md5_hash` | `hashlib.md5(frame_bytes).hexdigest()` 한 줄로 인라인 |
| `cvat.utils.dataset_manifest.utils.rotate_image` | OpenCV `cv2.rotate()` 또는 numpy 회전. 영상 회전 메타데이터(EXIF rotation) 처리용 |
| `cvat.utils.dataset_manifest.utils.PcdReader` | 포인트 클라우드용. 2D 영상만 다루면 불필요 |
| `cvat.utils.dataset_manifest.errors` | 자체 예외로 대체 |

→ 비디오 매니페스트만 추출하면 PyAV + 표준 라이브러리만으로 동작.

## 단독 추출 예시 코드

```python
# manifest_jsonl.py — CVAT 비디오 manifest를 단독 모듈로 추출
import hashlib
import json
import os
from pathlib import Path
from typing import Iterator

import av


MANIFEST_VERSION = "1.1"
MANIFEST_FILE = "manifest.jsonl"
INDEX_FILE = "index.json"


def create_video_manifest(video_path: Path, manifest_dir: Path) -> None:
    """영상 파일을 스캔해 manifest.jsonl + index.json 생성"""
    manifest_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = manifest_dir / MANIFEST_FILE
    index_path = manifest_dir / INDEX_FILE

    with av.open(str(video_path)) as container:
        video_stream = next(s for s in container.streams if s.type == "video")
        video_stream.thread_type = "AUTO"

        # 첫 프레임으로 해상도 확인
        first_frame = next(container.decode(video_stream))
        if first_frame.pict_type != av.video.frame.PictureType.I:
            raise ValueError("첫 프레임이 I-frame이 아님 (force 옵션 필요)")
        width, height = first_frame.width, first_frame.height

        # 다시 처음부터 스캔 (키프레임만 수집)
        container.seek(0, stream=video_stream)
        keyframes = []
        frame_number = 0
        for packet in container.demux(video_stream):
            for frame in packet.decode():
                if frame.pict_type == av.video.frame.PictureType.I:
                    img = frame.to_ndarray(format="bgr24")
                    md5 = hashlib.md5(img.tobytes()).hexdigest()
                    keyframes.append({
                        "number": frame_number,
                        "pts": frame.pts,
                        "checksum": md5,
                    })
                frame_number += 1

        total_frames = frame_number

    # manifest.jsonl 작성 (헤더 3줄 + 키프레임)
    with open(manifest_path, "w") as f:
        f.write(json.dumps({"version": MANIFEST_VERSION}, separators=(",", ":")) + "\n")
        f.write(json.dumps({"type": "video"}, separators=(",", ":")) + "\n")
        f.write(json.dumps({
            "properties": {
                "name": os.path.basename(str(video_path)),
                "resolution": [width, height],
                "length": total_frames,
                "chapters": [],
            }
        }, separators=(",", ":")) + "\n")
        for kf in keyframes:
            f.write(json.dumps(kf, separators=(",", ":")) + "\n")

    # index.json 생성 (키프레임 라인의 byte offset 기록)
    index = {}
    with open(manifest_path, "r") as f:
        # 헤더 3줄 스킵
        for _ in range(3):
            f.readline()
        for i, _ in enumerate(keyframes):
            index[str(i)] = f.tell()
            f.readline()

    with open(index_path, "w") as f:
        json.dump(index, f, separators=(",", ":"))


def read_manifest_properties(manifest_dir: Path) -> dict:
    """manifest의 헤더에서 properties 추출"""
    manifest_path = manifest_dir / MANIFEST_FILE
    with open(manifest_path) as f:
        f.readline()  # version
        f.readline()  # type
        return json.loads(f.readline())["properties"]


def get_keyframe(manifest_dir: Path, kf_index: int) -> dict:
    """index.json을 사용해 N번째 키프레임 정보를 빠르게 읽기"""
    with open(manifest_dir / INDEX_FILE) as f:
        index = json.load(f)
    with open(manifest_dir / MANIFEST_FILE) as f:
        f.seek(index[str(kf_index)])
        return json.loads(f.readline())


def find_nearest_keyframe(manifest_dir: Path, target_frame: int) -> dict:
    """target_frame 이하인 가장 큰 키프레임 반환"""
    with open(manifest_dir / INDEX_FILE) as f:
        index = json.load(f)
    with open(manifest_dir / MANIFEST_FILE) as f:
        # binary search 형태로 최적화 가능. 여기는 단순 선형 탐색.
        candidate = None
        for i in range(len(index)):
            f.seek(index[str(i)])
            kf = json.loads(f.readline())
            if kf["number"] <= target_frame:
                candidate = kf
            else:
                break
        return candidate


def seek_and_decode(video_path: Path, target_frame: int, manifest_dir: Path):
    """manifest를 사용해 target_frame을 빠르게 디코딩"""
    kf = find_nearest_keyframe(manifest_dir, target_frame)
    skip_count = target_frame - kf["number"]

    with av.open(str(video_path)) as container:
        video_stream = next(s for s in container.streams if s.type == "video")
        video_stream.thread_type = "AUTO"
        container.seek(offset=kf["pts"], stream=video_stream)

        decoded_frames = container.decode(video_stream)
        for _ in range(skip_count):
            next(decoded_frames)
        return next(decoded_frames).to_ndarray(format="rgb24")


# 사용 예
if __name__ == "__main__":
    video = Path("./sample.mp4")
    work_dir = Path("./manifest_dir")

    create_video_manifest(video, work_dir)
    props = read_manifest_properties(work_dir)
    print(f"video: {props['resolution']}, total_frames={props['length']}")

    # 1500번 프레임에 빠르게 점프
    frame_data = seek_and_decode(video, 1500, work_dir)
    print(f"frame 1500 shape: {frame_data.shape}")
```

## 입력/출력 명세

### 입력
- 영상 파일 경로 (`.mp4`, `.mov`, `.avi` 등 PyAV가 디코딩 가능한 모든 형식).

### 출력
- `{manifest_dir}/manifest.jsonl` — 헤더 3줄 + 키프레임당 1줄
- `{manifest_dir}/index.json` — `{"0": offset0, "1": offset1, ...}` 형태로 빠른 룩업

### 사용 시
- `manifest_dir`을 먼저 로드 → `index.json`을 메모리에 캐시
- 임의 프레임 N 요청 시 → 가장 가까운 키프레임 PTS로 영상에서 seek → 차이만큼 디코딩

## 통합 가이드 (다른 시스템에 붙이는 법)

1. **업로드 파이프라인**: 영상 업로드 → 비동기 워커에서 `create_video_manifest()` → manifest 파일을 작업 디렉토리에 저장.
2. **프레임 API**: `GET /api/jobs/{id}/frame?number=N` → manifest로 빠른 seek + 디코딩 → JPEG 응답.
3. **청크 API와의 연관**: CVAT은 manifest로 키프레임 위치를 알고 청크(36 프레임씩) 경계를 정한다. 청크 시스템 없이도 manifest 단독 사용 가능.
4. **재처리**: 영상 파일이 변경되면 manifest 재생성 필요 → 영상 파일 mtime 또는 checksum으로 dirty 플래그 관리.

## 검증 방법

1. **Round-trip**: 임의 영상 → manifest 생성 → properties.length == 영상 총 프레임 수 일치.
2. **PTS 정확도**: manifest의 임의 키프레임 PTS로 직접 seek → `frame.pts == manifest.pts` 확인.
3. **임의 프레임 seek 정확도**: `seek_and_decode(video, N, dir)` 결과와 처음부터 디코딩한 N번째 프레임의 픽셀이 동일.
4. **첫 프레임 강제 검증**: 첫 프레임이 키프레임이 아닌 영상 → ValueError 발생 확인 (또는 force 옵션으로 우회).

## 알려진 한계와 함정

- **첫 프레임 키프레임 가정**: 일부 스트림 녹화/CCTV 영상은 첫 프레임이 P-frame. CVAT은 `force=True`로 우회하지만, 그 경우 PTS가 0부터 시작하지 않을 수 있어 seek 계산에 주의.
- **시간 단위 혼동**: PTS는 stream `time_base` 단위. 초로 환산하려면 `pts * time_base`. 실제 프레임 번호와는 다르다 (가변 fps 영상에서 특히).
- **frame_number 정의**: CVAT은 단순 `0, 1, 2, ...` 카운터. PTS와 일치하지 않을 수 있다. 가변 fps 영상에서 PTS↔frame_number 매핑이 명확하지 않은 경우, frame_number는 디코딩 순서 인덱스로만 사용.
- **메모리 사용**: `index.json`은 키프레임 수 × 작은 정수 → 1시간 영상도 수십 KB. 단, 짧은 GOP (키프레임 간격 짧음) 영상은 인덱스가 큼.
- **MD5 비용**: 키프레임마다 MD5 계산 → 1080p 영상의 1MB/프레임 × 수백 키프레임 = 무시 못 할 시간. 무결성 검증이 필요 없으면 checksum 필드 생략 가능.
- **jsonl을 random access**: index.json 없이 manifest를 random access하면 매번 처음부터 readline 반복 → O(N). index.json은 필수.
- **streaming/HLS 영상**: PyAV는 mp4/mkv 등 self-contained 영상에서만 정확. m3u8 등 segmented 영상은 별도 처리 필요.
- **키프레임 빈도**: CVAT은 `chunk_size=36` 기준으로 36프레임마다 키프레임 강제 (`Mpeg4ChunkWriter`로 청크 재생성 시). manifest만 단독 사용 시 원본 영상의 키프레임 빈도에 의존 → 키프레임이 듬성듬성하면 seek 후 디코딩 거리가 멀어 느림.

## 라이선스 주의사항

CVAT MIT, PyAV BSD-3-Clause. 추출 시:
```python
# Adapted from CVAT (https://github.com/cvat-ai/cvat) utils/dataset_manifest
# Copyright (C) 2021-2022 Intel Corporation
# Copyright (C) CVAT.ai Corporation
# SPDX-License-Identifier: MIT
```
