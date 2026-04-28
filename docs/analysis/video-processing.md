# 영상 처리 파이프라인

## 개요
CVAT은 PyAV(libavcodec/libavformat 파이썬 바인딩)를 사용해 영상을 프레임 단위로 처리한다. 비디오를 "청크(chunk)" 단위로 분할하여 캐싱하며, 원본과 압축 두 가지 품질의 청크를 제공한다.

## 핵심 코드 위치
| 역할 | 파일 경로 | 비고 |
|------|---------|------|
| 미디어 추출기/청크 작성 | `cvat/apps/engine/media_extractors.py` | VideoReader, ZipChunkWriter, Mpeg4ChunkWriter |
| Data/Video/Image 모델 | `cvat/apps/engine/models.py:L422` | Data, Video, Image 클래스 |
| Task 생성 + 미디어 처리 | `cvat/apps/engine/task.py` | _create_thread, _save_task_to_db |
| 프레임 프로바이더 | `cvat/apps/engine/frame_provider.py` | TaskFrameProvider |
| 캐시 관리 | `cvat/apps/engine/cache.py` | CacheManager |

## 파이프라인 {#pipeline}

```
[Client: 영상 파일 업로드]
    ↓ POST /api/tasks/{id}/data (TUS 프로토콜 또는 multipart)
[cvat_server: 파일 수신 → raw 디렉토리 저장]
    ↓ django-rq → CVAT_QUEUES.CHUNKS
[cvat_worker_chunks: 비동기 처리]
    ↓
[_create_thread()]
    1. 파일 타입 감지 (get_mime)
    2. VideoReader로 비디오 열기 (PyAV)
    3. 메타데이터 추출 (width, height, frame count, fps)
    4. Data 레코드 생성 (start_frame, stop_frame 설정)
    5. Video 레코드 생성 (path, width, height)
    6. Segment 생성 (프레임 범위 분할)
    7. Job 생성 (Segment당 1개)
    8. 청크 생성 (ZipChunkWriter 또는 Mpeg4ChunkWriter)
        ├── compressed: JPEG 압축 ZIP (기본 quality=50)
        └── original: 원본 품질 ZIP
    9. manifest.jsonl 생성 (프레임별 메타데이터)
```

## FFmpeg 사용 방식 {#ffmpeg-usage}

CVAT은 `av` 패키지(PyAV)를 통해 libavcodec을 간접 사용한다. FFmpeg 바이너리를 직접 호출하지 않는다.

```python
# cvat/apps/engine/media_extractors.py
import av  # PyAV — libavcodec/libavformat Python bindings

class VideoReader(IMediaReader):
    def __init__(self, source_path, ...):
        self._container = av.open(source_path)
        self._video_stream = self._container.streams.video[0]

    def __iter__(self):
        # 프레임 디코딩 반복자
        for packet in self._container.demux(self._video_stream):
            for frame in packet.decode():
                yield frame.to_ndarray(format='rgb24'), frame.pts

    @property
    def fps(self):
        return float(self._video_stream.average_rate)
```

### 청크 작성기

```python
# 압축 ZIP 청크 (프레임을 JPEG로 압축하여 ZIP으로 묶음)
class ZipCompressedChunkWriter:
    def save_as_chunk(self, images, chunk_path):
        with zipfile.ZipFile(chunk_path, 'w') as zf:
            for i, (frame_data, _) in enumerate(images):
                img = Image.fromarray(frame_data)
                buf = io.BytesIO()
                img.save(buf, format='JPEG', quality=self.quality)
                zf.writestr(f'{i:06d}.jpg', buf.getvalue())

# 원본 MP4 청크 (keyframe만 추출하여 새 MP4 생성)
class Mpeg4ChunkWriter:
    def save_as_chunk(self, images, chunk_path):
        with av.open(chunk_path, 'w', format='mp4') as container:
            stream = container.add_stream('libx264', rate=self.fps)
            for frame_data, _ in images:
                frame = av.VideoFrame.from_ndarray(frame_data, format='rgb24')
                container.mux(stream.encode(frame))
```

## 스토리지 구조 {#storage}

```
/home/django/data/             (MEDIA_DATA_ROOT)
├── {data_id}/
│   ├── raw/                   (업로드 원본)
│   │   ├── video.mp4
│   │   └── manifest.jsonl
│   ├── compressed/            (압축 청크 캐시)
│   │   ├── segment_1-0.zip    (첫 번째 Segment의 0번 청크)
│   │   └── segment_1-1.zip
│   └── original/              (원본 품질 청크 캐시)
│       ├── segment_1-0.zip
│       └── segment_1-1.zip

/home/django/tasks/{task_id}/  (TASKS_ROOT)
/home/django/jobs/{job_id}/    (JOBS_ROOT)
```

### 청크 파일 네이밍 규칙

```python
# cvat/apps/engine/models.py:L500
def _get_chunk_name(segment_id, chunk_number, chunk_type):
    if chunk_type == DataChoice.VIDEO:
        ext = 'mp4'
    elif chunk_type == DataChoice.IMAGESET:
        ext = 'zip'
    else:
        ext = 'list'
    return f'segment_{segment_id}-{chunk_number}.{ext}'
```

## manifest.jsonl 형식

각 줄이 독립적인 JSON 오브젝트인 JSONL(Newline-delimited JSON) 포맷.
헤더 4줄(version/type/properties) + 프레임별 1줄 구조.

```jsonl
{"version":"1.1"}
{"type":"video"}
{"properties":{"name":"video.mp4","resolution":[1920,1080],"length":300,"chapters":[]}}
{"number":0,"pts":0,"checksum":"abc123def456..."}
{"number":1,"pts":3003,"checksum":"def456abc123..."}
...
```

- `version`: 항상 `"1.1"` (SupportedVersion.V1_1)
- `type`: `"video"` 또는 `"images"`
- `properties.name`: 원본 파일명 (basename)
- `properties.resolution`: `[width, height]`
- `properties.length`: 총 프레임 수
- `properties.chapters`: 챕터 정보 목록 (보통 빈 배열)
- `number`: 0-based 프레임 번호
- `pts`: Presentation Time Stamp (libav 기준)
- `checksum`: MD5 hex 문자열 (프레임 무결성 검증용)

> 코드 참조: `utils/dataset_manifest/core.py:VideoManifestManager._write_base_information()` (L623), `_write_core_part()` (L638)

## 청크 크기 결정 알고리즘

청크 크기(`chunk_size`)는 업로드 시 명시하지 않으면 해상도 기반으로 자동 결정된다.

```python
# cvat/apps/engine/task.py:L1498
if db_data.chunk_size is None:
    if db_data.compressed_chunk_type == DataChoice.IMAGESET:
        w, h = extractor.get_image_size(first_image_idx)
        area = h * w
        db_data.chunk_size = max(2, min(72, 36 * 1920 * 1080 // area))
    else:
        # 비디오 타입은 고정값
        db_data.chunk_size = 36
```

- **이미지셋**: `max(2, min(72, 36 * 1920 * 1080 // (width * height)))`
  - 1080p(1920×1080) → 36프레임/청크
  - 4K(3840×2160) → max(2, 9) → 9프레임/청크
  - 저해상도(640×480) → min(72, 270) → 72프레임/청크
- **비디오**: 항상 36프레임/청크 고정

## 청크 캐싱 메커니즘

```python
# cvat/apps/engine/cache.py
class CacheManager:
    # 청크 요청 시:
    # 1. Kvrocks(온디스크 Redis) 에서 캐시 확인
    # 2. 없으면 원본 파일에서 청크 생성
    # 3. Kvrocks에 저장 (TTL 무제한)

    def get_task_chunk(self, task, segment_id, chunk_number, quality):
        cache_key = f"task_{task.id}_{segment_id}_{chunk_number}_{quality}"
        # Kvrocks 조회 → 없으면 생성 → 반환
```

## TUS 프로토콜 (재개 가능 업로드)

CVAT은 대용량 파일 업로드에 **TUS(Tus Resumable Upload Protocol)** 를 구현한다.

```
코드 위치: cvat/apps/engine/tus.py
```

### 동작 방식

```
1. 업로드 초기화
   POST /api/tasks/{id}/data   (TUS 방식)
   Headers:
     Upload-Length: <전체 파일 크기 바이트>
     Upload-Metadata: filename <base64>, filetype <base64>
   → 201 Created
   → 응답 헤더: Location: /api/tasks/{id}/data/upload/{file_id}

2. 청크 전송
   PATCH /api/tasks/{id}/data/upload/{file_id}
   Headers:
     Content-Type: application/offset+octet-stream
     Content-Length: <청크 크기>
     Upload-Offset: <현재 오프셋>
   → 204 No Content
   → 응답 헤더: Upload-Offset: <새 오프셋>

3. 업로드 완료 확인 (offset == file_size 시 자동 완료)
```

### 핵심 클래스 (tus.py)

| 클래스 | 역할 |
|--------|------|
| `TusFile` | 업로드 중인 파일 상태 관리 |
| `TusFile.TusMeta` | 파일 크기, 오프셋, 파일명 등 메타데이터 |
| `TusFile.TusMetaFile` | `.meta` 파일 읽기/쓰기 |
| `TusChunk` | 요청에서 청크 정보 파싱 (offset, size) |
| `TusFile.FileID` | `{user_id}_{uuid}` 형식의 파일 식별자 |

### 파일 ID 형식

```
{user_id}_{uuid4}
# 예: 5_550e8400-e29b-41d4-a716-446655440000
```

## 프레임 API

```
GET /api/jobs/{id}/data?type=chunk&number=0&quality=compressed
    → segment의 0번 청크 반환 (ZIP 또는 MP4)

GET /api/jobs/{id}/data?type=frame&number=5&quality=compressed
    → 5번 프레임 단일 이미지 반환 (JPEG)

GET /api/tasks/{id}/data?type=preview
    → 첫 프레임 썸네일 반환
```

## 핵심 의사결정

- **PyAV 사용**: FFmpeg 바이너리를 직접 호출하지 않고 `av` 패키지를 통해 libavcodec/libavformat을 임베딩한다. 프로세스 생성 오버헤드 없이 Python 내에서 직접 디코딩한다.
- **압축/원본 이중 청크**: 클라이언트가 네트워크 상황에 따라 quality를 선택할 수 있도록 동일 데이터를 두 가지 포맷(JPEG ZIP, MP4)으로 캐싱한다.
- **해상도 기반 청크 크기 자동 결정**: `36 * 1920 * 1080 / area` 공식으로 청크 한 개의 메모리 사용량을 일정하게 유지한다.
- **manifest.jsonl JSONL 포맷**: 한 줄당 하나의 JSON으로 헤더 4줄 + 프레임당 1줄 구조. 스트리밍 파싱 가능.
- **TUS 프로토콜 직접 구현**: `cvat/apps/engine/tus.py`에서 TUS 1.0을 자체 구현. 프레임워크에 의존하지 않는 `TusFile`/`TusChunk` 클래스를 사용한다.
- **start_frame/stop_frame 컨텍스트 차이**: Data의 `start_frame`/`stop_frame`은 0-based 절대 번호이나, Segment의 동일 필드는 Data 내의 상대 인덱스다.
- **frame_filter step**: `step=2`면 격 프레임 추출(짝수 프레임만). 스킵 추출에 사용.
- **deleted_frames**: IntArrayField로 삭제된 프레임 번호 목록을 저장하며 트랙 보간 시 제외 처리된다.

## 독립 포팅 가이드

### 추출 난이도
**중** — VideoReader 자체는 PyAV 사용으로 단순하지만, 청크 캐싱(Kvrocks) + manifest + storage 분기(local/cloud/share) + start_frame/stop_frame 컨텍스트 차이까지 같이 옮기면 코드량이 늘어난다. manifest(모듈 04)와 TUS(모듈 03)는 별도 모듈로 추출했으므로 영상 디코딩만 떼어내면 200줄 이내.

### 핵심 추출 대상 (필수 파일)
| 역할 | 원본 경로 | 포팅 시 행수/조치 |
|------|----------|-----------------|
| VideoReader (PyAV iter) | `cvat/apps/engine/media_extractors.py` (`class VideoReader`) | 100줄 — 그대로 사용 가능 |
| ZipChunkWriter / Mpeg4ChunkWriter | `cvat/apps/engine/media_extractors.py` | 청크 ZIP/MP4 생성 패턴 (~150줄) |
| Data/Video/Image 모델 | `cvat/apps/engine/models.py:422-700` | DataChoice/StorageChoice enum + 모델 정의 |
| Frame Provider | `cvat/apps/engine/frame_provider.py` | 프레임 스트리밍 |
| Cache Manager (Kvrocks) | `cvat/apps/engine/cache.py` | Redis put/get 단순 패턴 |
| Manifest (별도) | `cvat/utils/dataset_manifest/core.py` | → 모듈 04 참조 |
| TUS 업로드 (별도) | `cvat/apps/engine/tus.py` | → 모듈 03 참조 |

### 외부 라이브러리 의존성
| 라이브러리 | 버전 | 대체 가능? | 비고 |
|----------|------|:--------:|------|
| av (PyAV) | >=10 | 어려움 | ffmpeg-python(subprocess)는 성능 손실 큼 |
| Pillow | >=9 | 필수 | JPEG 인코딩 |
| numpy | >=1.22 | 필수 | 프레임 ndarray 처리 |
| ffmpeg | >=4.2 (system) | 필수 | PyAV의 native lib |
| Kvrocks 또는 Redis | >=7 | 가능 | 청크 캐시 — 파일시스템도 가능 |

### 내부 CVAT 의존성과 분리 방법
| 의존 모듈 | 포팅 시 처리 |
|---------|-----------|
| `cvat.apps.engine.cloud_provider` | S3/Azure/GCS 사용 안 하면 LOCAL storage만 가정 |
| `cvat.apps.engine.location.Location` | LocalStorage 단일 가정 |
| `cvat.apps.engine.cache.CacheManager` (Kvrocks) | dict 또는 파일시스템 캐시로 단순화 |
| `cvat.apps.engine.task._create_thread` (RQ 호출 진입점) | 자체 비동기 워커 구조로 변경 |

### 최소 동작 단위 (MVP)
- 추출: VideoReader (PyAV 디코딩) + 단일 chunk_size 36 고정
- 필요한 최소 DB: Data 1개 테이블 (영상 메타) + Segment 1개 테이블 (프레임 범위)
- 필요한 최소 API 엔드포인트: 3개 — `POST /upload`(TUS), `GET /chunk?n=N`, `GET /frame?n=N`

### 포팅 단계 (체크리스트)
1. [ ] PyAV 시스템 패키지 설치 (`apt install ffmpeg`)
2. [ ] VideoReader 단순화 (cloud storage 분기 제거)
3. [ ] ZipChunkWriter (JPEG 압축 ZIP) 추출
4. [ ] manifest 생성 (모듈 04 적용)
5. [ ] TUS 업로드 (모듈 03 적용)
6. [ ] 청크 캐시 (단순 파일시스템부터)
7. [ ] 프레임 API 구현

### 통합 지점 (이 모듈을 다른 시스템에 붙일 때)
- 입력: 영상 파일 (mp4/mov/avi/mkv 등 PyAV 지원 포맷)
- 출력: 청크 파일 (ZIP of JPEG 또는 MP4) + manifest.jsonl
- 외부 인터페이스: 프레임 번호 N → 디코딩된 numpy ndarray 또는 JPEG 응답

### 알려진 함정
- **PyAV 시스템 의존성**: pip install만으로는 부족. ffmpeg dev 라이브러리 시스템 설치 필수. Docker 이미지에선 base에 포함, 서버 설치 시 별도 단계.
- **start_frame/stop_frame 컨텍스트 다름**: Data의 start_frame은 0-based 절대, Segment의 start_frame은 Data 내부 상대 인덱스. 혼동 시 잘못된 프레임 표시.
- **첫 프레임 키프레임 가정**: 첫 프레임이 I-frame이 아니면 InvalidVideoError. 일부 CCTV 녹화에서는 force=True 필요.
- **청크 크기 메모리**: chunk_size=36이면 1080p 36프레임 = 약 200MB의 raw frame 메모리. 대용량 청크는 OOM.
- **PyAV thread_type='AUTO'**: 멀티스레드 디코딩 활성화. 일부 영상에서 비결정성(같은 frame 다른 결과) 발생 가능.
- **frame.pts vs frame_number**: PTS는 시간 기반(stream time_base 단위), frame_number는 디코딩 순서. 가변 fps 영상에서 둘 사이 매핑이 모호.

## Docker 미사용 대응

### PyAV 시스템 의존성

PyAV는 pip 패키지지만 시스템에 libav* 또는 ffmpeg 라이브러리가 사전 설치되어 있어야 한다. Docker 이미지에서는 베이스 이미지에 미리 포함시킨다.

| OS | 설치 명령 |
|----|-----------|
| Ubuntu/Debian | `apt install -y ffmpeg libavcodec-dev libavformat-dev libavutil-dev libswscale-dev libswresample-dev libavfilter-dev` |
| RHEL/Rocky/CentOS | `dnf install -y ffmpeg-free ffmpeg-free-devel`(EPEL 또는 RPM Fusion 활성화 필요) |
| macOS | `brew install ffmpeg` |
| Alpine | `apk add ffmpeg ffmpeg-dev` |

```bash
# 시스템 의존성 설치 후
pip install av  # PyAV — wheel에 정적 빌드 또는 시스템 libav 동적 링크
```

### TUS 업로드 네이티브화

`cvat/apps/engine/tus.py`의 `TusFile`/`TusChunk`는 Django 의존성을 최소화한 자체 구현이다. Django 없이 다른 프레임워크에서 사용할 경우, 동일 로직을 라우터에 매핑하거나 다음 라이브러리를 활용할 수 있다.

- `django-tus`: Django 전용 TUS 핸들러
- `tuspy`: 클라이언트 라이브러리(서버 구현은 별도)
- 직접 구현: TUS 1.0 스펙은 단순(POST 초기화 + PATCH 청크)하므로 Flask/FastAPI 라우터 두 개로 충분

### 스토리지

CVAT의 `MEDIA_DATA_ROOT`는 Docker 볼륨 마운트 경로지만, 네이티브 운영 시 다음 옵션이 가능하다:

- 로컬 디스크: `/var/lib/cvat/data/`
- NFS 마운트: 워커 분산 시 공유 스토리지 필수(import/export/chunks 워커가 같은 파일에 접근)
- S3/Azure/GCS: `CVAT_CLOUD_STORAGE_*` 환경변수 + django-storages 사용

### 청크 캐시(Kvrocks 대체)

Kvrocks는 디스크 영구 저장 + Redis 프로토콜 호환을 제공한다. 대체 옵션:

| 대체 | 트레이드오프 |
|------|-------------|
| Redis 7+ 단독 | RAM 사용량 증가, AOF/RDB로 영속화 가능하나 Kvrocks 대비 디스크 효율 떨어짐 |
| Dragonfly | Redis 호환, 멀티스레드, 메모리 효율 우수 |
| 파일시스템 직접 사용 | 캐시 키 → 파일 경로 매핑을 코드에 추가 |

자세한 네이티브 배포는 [deployment-without-docker.md](deployment-without-docker.md) 참고.
