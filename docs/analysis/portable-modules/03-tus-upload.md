# TUS 재개 가능 업로드 (Resumable Upload)

## 한 줄 요약
[TUS 1.0 프로토콜](https://tus.io/protocols/resumable-upload)의 서버 사이드를 Django 의존성을 최소화한 자체 구현. 5GB+ 영상 파일을 브라우저에서 안정적으로 업로드(네트워크 끊어져도 재개 가능).

## 추출 가치
**왜 떼어 쓸 가치가 있는가**
- 브라우저에서 5GB 영상을 한 번의 POST로 올리면 네트워크 한 번만 끊겨도 처음부터 다시 시작해야 한다. TUS는 이를 청크 단위 PATCH로 해결한다.
- TUS 프로토콜 자체는 단순(POST/PATCH 두 개)지만, 메타파일 동시성, FileID 충돌, offset 무결성 등 코너 케이스를 직접 처리하면 의외로 손이 많이 간다.
- CVAT 구현은 Django ORM에 의존하지 않고 **파일시스템에 `.meta` 사이드카 파일** 패턴을 쓴다 → DB 없이도 동작 → FastAPI/Flask로 옮기기 매우 쉽다.
- `tus-js-client`(MIT) 같은 검증된 클라이언트 라이브러리와 자동으로 호환된다.

## 원본 코드 위치

| 역할 | 파일:라인 | 핵심 함수/클래스 |
|------|---------|--------------|
| TUS 코어 | `cvat/apps/engine/tus.py:54-276` | `TusFile`, `TusChunk`, `TusFile.TusMeta`, `TusFile.FileID`, `TusFile.TusMetaFile` |
| 예외 클래스 | `cvat/apps/engine/tus.py:27-36` | `TusFileNotFoundError`, `TusFileForbiddenError`, `TusTooLargeFileError` |
| Django ViewSet 통합 | `cvat/apps/engine/views.py` (검색: `class UploadMixin`) | TUS 헤더 파싱 + 응답 |
| 청크 크기 / 최대 파일 크기 설정 | `cvat/cvat/settings/base.py` | `TUS_MAX_FILE_SIZE`, `TUS_DEFAULT_CHUNK_SIZE` |

## 알고리즘/프로토콜 핵심

### TUS 1.0 프로토콜 요약 (HTTP 헤더 기반)

```
1) HEAD /files/{file_id}              → 현재 업로드 상태 조회
   응답: Upload-Offset, Upload-Length, Tus-Resumable: 1.0.0

2) POST /files/                        → 업로드 세션 생성
   요청 헤더:
     Upload-Length: <total bytes>
     Upload-Metadata: filename <base64>,filetype <base64>
   응답: 201 Created, Location: /files/{file_id}

3) PATCH /files/{file_id}              → 청크 업로드
   요청 헤더:
     Content-Type: application/offset+octet-stream
     Upload-Offset: <current offset>
     Content-Length: <chunk bytes>
   요청 바디: raw chunk bytes
   응답: 204 No Content, Upload-Offset: <new offset>

4) DELETE /files/{file_id}             → 업로드 취소

5) OPTIONS /files/                     → 서버 capability 조회
   응답: Tus-Version, Tus-Resumable, Tus-Extension, Tus-Max-Size
```

### CVAT 메타파일 저장 패턴

각 업로드 파일에 대해 두 개의 파일을 생성:
```
{upload_dir}/{user_id}_{uuid}          ← 실제 데이터 파일
{upload_dir}/{user_id}_{uuid}.meta     ← JSON 메타데이터
```

`.meta` 파일 내용:
```json
{
  "file_size": 5368709120,
  "offset": 1048576,
  "filename": "video.mp4",
  "filetype": "video/mp4",
  "message_id": null
}
```

### FileID 형식 (tus.py:106-125)

```
{user_id}_{uuid4}
예: 5_550e8400-e29b-41d4-a716-446655440000
```

- `user_id`로 권한 검증 (`TusFile.validate(user_id=...)` 시 user_id 불일치면 `TusFileForbiddenError`).
- `uuid`로 충돌 방지.
- 정규식: `[0-9]+_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`

### 파일 사전 할당 (tus.py:210-214)

```python
def init_file(self):
    self.upload_dir.mkdir(parents=True, exist_ok=True)
    with open(self.file_path, "wb") as file:
        file.seek(self.file_size - 1)    # 파일 크기만큼 sparse seek
        file.write(b"\0")
```

→ 5GB 파일도 즉시 할당 가능 (sparse file). 청크가 들어오는 위치에 `seek + write`로 부분 기록.

### 청크 쓰기 (tus.py:216-221)

```python
def write_chunk(self, chunk: TusChunk):
    with open(self.file_path, "r+b") as file:
        file.seek(chunk.offset)
        shutil.copyfileobj(chunk.request, file)   # request body를 직접 파일에 stream
        self.meta_file.meta.offset = file.tell()
    self.meta_file.dump()
```

→ 청크 데이터를 메모리에 적재하지 않고 직접 디스크에 stream → 대용량 청크도 메모리 안전.

### 메타데이터 헤더 파싱 (tus.py:86-103)

```python
@classmethod
def from_request(cls, request) -> TusFile.TusMeta:
    metadata = {"file_size": int(request.META.get("HTTP_UPLOAD_LENGTH", "0"))}

    # Upload-Metadata: "filename Zm9vLnR4dA==,filetype dmlkZW8vbXA0"
    for kv in request.META.get("HTTP_UPLOAD_METADATA", "").split(","):
        splitted = kv.split(" ")
        if len(splitted) == 2:
            key, value = splitted
            metadata[key] = base64.b64decode(value).decode()
    ...
```

키-값 페어가 콤마로 구분되고, 값은 base64 인코딩 (TUS 1.0 표준).

### 파일 크기 제한 (tus.py:58, 67-68)

```python
MAX_FILE_SIZE: ClassVar[int] = settings.TUS_MAX_FILE_SIZE
@file_size.validator
def validate_file_size(self, attribute, value):
    if value > self.MAX_FILE_SIZE:
        raise TusTooLargeFileError
```

→ 헤더로 받은 `Upload-Length`가 한도 초과면 즉시 거부.

### 업로드 완료 검출 (tus.py:223-224)

```python
def is_complete(self):
    return self.offset == self.file_size
```

→ `offset == file_size`이면 자동 완료. 별도 "complete" 호출 불필요.

## 의존성

### 외부 라이브러리

| 라이브러리 | 버전 | 용도 | 대체 가능? |
|----------|------|------|---------|
| attrs | >=22 | TusFile/TusMeta dataclass | dataclasses(stdlib)로 대체 가능 |
| Django | (선택) | request.META 헤더 접근 | FastAPI Request, Flask request로 대체 |

순수 코어(`TusFile`, `TusMeta`, `TusMetaFile`, `FileID`)는 표준 라이브러리만 있어도 동작.

### CVAT 내부 의존성

| 의존 모듈 | 분리 방법 |
|----------|---------|
| `django.conf.settings.TUS_MAX_FILE_SIZE` | `os.environ` 또는 직접 상수 |
| `cvat.apps.engine.log.ServerLogManager` | 표준 `logging.getLogger()` |
| `cvat.apps.engine.types.ExtendedRequest` | FastAPI `Request` 또는 Flask `request` |
| `rest_framework.serializers.ValidationError` | `ValueError` 또는 자체 예외 |

## 단독 추출 예시 코드 — FastAPI 변환

```python
# tus_fastapi.py — CVAT TusFile을 FastAPI로 옮긴 단독 모듈
from __future__ import annotations
import base64
import json
import os
import shutil
from dataclasses import dataclass, field, asdict
from pathlib import Path
from uuid import UUID, uuid4

from fastapi import APIRouter, FastAPI, Request, HTTPException, Header, Response

UPLOAD_DIR = Path("/var/lib/uploads")
MAX_FILE_SIZE = 5 * 1024**3  # 5 GB
TUS_VERSION = "1.0.0"


@dataclass
class TusMeta:
    file_size: int
    offset: int = 0
    filename: str | None = None
    filetype: str | None = None


def _meta_path(file_path: Path) -> Path:
    return file_path.with_suffix(file_path.suffix + ".meta")


def _save_meta(file_path: Path, meta: TusMeta) -> None:
    _meta_path(file_path).write_text(json.dumps(asdict(meta)))


def _load_meta(file_path: Path) -> TusMeta:
    return TusMeta(**json.loads(_meta_path(file_path).read_text()))


def _file_id(user_id: int) -> str:
    return f"{user_id}_{uuid4()}"


def _validate_file_id(file_id: str, user_id: int) -> None:
    parts = file_id.split("_", 1)
    if len(parts) != 2 or int(parts[0]) != user_id:
        raise HTTPException(403, "forbidden")
    UUID(parts[1])  # validates uuid format


def _parse_upload_metadata(header_value: str) -> dict:
    out = {}
    for kv in header_value.split(","):
        splitted = kv.strip().split(" ", 1)
        if len(splitted) == 2:
            key, b64_value = splitted
            out[key] = base64.b64decode(b64_value).decode("utf-8")
    return out


router = APIRouter()


@router.options("/files/")
async def tus_options():
    return Response(
        status_code=204,
        headers={
            "Tus-Resumable": TUS_VERSION,
            "Tus-Version": TUS_VERSION,
            "Tus-Extension": "creation,termination",
            "Tus-Max-Size": str(MAX_FILE_SIZE),
        },
    )


@router.post("/files/")
async def tus_create(
    request: Request,
    upload_length: int = Header(..., alias="Upload-Length"),
    upload_metadata: str = Header("", alias="Upload-Metadata"),
):
    if upload_length > MAX_FILE_SIZE:
        raise HTTPException(413, "file too large")

    # TODO: derive user_id from auth (omitted)
    user_id = 1

    md = _parse_upload_metadata(upload_metadata)
    meta = TusMeta(
        file_size=upload_length,
        filename=md.get("filename"),
        filetype=md.get("filetype"),
    )

    file_id = _file_id(user_id)
    file_path = UPLOAD_DIR / file_id

    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    # sparse file pre-allocation
    with open(file_path, "wb") as fp:
        fp.seek(upload_length - 1)
        fp.write(b"\0")
    _save_meta(file_path, meta)

    return Response(
        status_code=201,
        headers={
            "Location": f"/files/{file_id}",
            "Tus-Resumable": TUS_VERSION,
        },
    )


@router.head("/files/{file_id}")
async def tus_head(file_id: str):
    user_id = 1
    _validate_file_id(file_id, user_id)
    file_path = UPLOAD_DIR / file_id
    if not file_path.exists():
        raise HTTPException(404, "not found")
    meta = _load_meta(file_path)
    return Response(
        status_code=200,
        headers={
            "Upload-Offset": str(meta.offset),
            "Upload-Length": str(meta.file_size),
            "Tus-Resumable": TUS_VERSION,
            "Cache-Control": "no-store",
        },
    )


@router.patch("/files/{file_id}")
async def tus_patch(
    file_id: str,
    request: Request,
    upload_offset: int = Header(..., alias="Upload-Offset"),
    content_length: int = Header(..., alias="Content-Length"),
):
    user_id = 1
    _validate_file_id(file_id, user_id)

    file_path = UPLOAD_DIR / file_id
    if not file_path.exists():
        raise HTTPException(404, "not found")
    meta = _load_meta(file_path)

    if upload_offset != meta.offset:
        # spec: 409 Conflict
        raise HTTPException(409, "offset mismatch")

    written = 0
    with open(file_path, "r+b") as fp:
        fp.seek(upload_offset)
        async for chunk in request.stream():
            fp.write(chunk)
            written += len(chunk)
        meta.offset = upload_offset + written

    _save_meta(file_path, meta)

    return Response(
        status_code=204,
        headers={
            "Upload-Offset": str(meta.offset),
            "Tus-Resumable": TUS_VERSION,
        },
    )


@router.delete("/files/{file_id}")
async def tus_delete(file_id: str):
    user_id = 1
    _validate_file_id(file_id, user_id)
    file_path = UPLOAD_DIR / file_id
    if file_path.exists():
        file_path.unlink()
    mp = _meta_path(file_path)
    if mp.exists():
        mp.unlink()
    return Response(status_code=204, headers={"Tus-Resumable": TUS_VERSION})


# 사용
app = FastAPI()
app.include_router(router)
```

## 입력/출력 명세

### 클라이언트 흐름 (tus-js-client 사용 시)
```javascript
import * as tus from "tus-js-client";

const upload = new tus.Upload(file, {
    endpoint: "/files/",
    chunkSize: 5 * 1024 * 1024,   // 5 MB
    metadata: { filename: file.name, filetype: file.type },
    onError: (e) => console.error(e),
    onProgress: (sent, total) => console.log(sent, total),
    onSuccess: () => console.log("done"),
});
upload.start();
```

라이브러리가 자동으로:
1. POST `/files/` (Upload-Length, Upload-Metadata)
2. 응답 Location 헤더 추출
3. PATCH `/files/{id}` 청크 반복
4. 실패 시 HEAD로 offset 조회 → 재개

## 통합 가이드 (다른 시스템에 붙이는 법)

1. **인증 통합**: `_validate_file_id`의 `user_id`를 인증 미들웨어에서 가져오도록 교체. CVAT은 Django session/JWT에서 추출.
2. **업로드 완료 콜백**: `tus_patch`에서 `meta.offset == meta.file_size`일 때 후속 처리 트리거 (예: 파일을 task 디렉토리로 이동, 메시지큐에 작업 등록).
3. **파일명 충돌 처리**: CVAT은 `rename()` 시 `filename_1.ext`, `filename_2.ext` 식으로 자동 회피 (tus.py:226-240).
4. **만료/청소**: 미완료 업로드는 일정 기간 후 정리 필요 (cron 또는 RQ scheduled job). CVAT은 별도 cleanup 태스크 사용.

## 검증 방법

1. **tus-js-client로 5GB 더미 파일 업로드** → 중간에 네트워크 끊고 → 재시작했을 때 이어서 업로드되는지 확인.
2. **HEAD 요청 결과**: 업로드 진행 중 HEAD `/files/{id}` 호출 시 `Upload-Offset`이 정상 진행 표시.
3. **Content-Length mismatch**: PATCH 요청에서 Content-Length와 실제 body 크기 다르게 보냈을 때 에러 처리.
4. **권한 검증**: 다른 user_id의 file_id로 PATCH 시도 → 403.
5. **OPTIONS 응답 헤더**: `tus-js-client` capability 검사를 통과해야 정상 동작.

## 알려진 한계와 함정

- **`.meta` 파일 동시성**: 같은 파일에 동시 PATCH가 들어오면 `meta.offset`이 race condition. CVAT은 Django request 시리얼라이저에서 호출당 하나의 PATCH만 보장. FastAPI에서는 file-level lock(`fcntl.flock`) 또는 Redis 분산 락 추가 필요.
- **stream 본문 처리**: FastAPI 변환 시 `request.stream()`을 쓰지 않고 `await request.body()`로 받으면 대용량 청크가 메모리에 다 적재됨 → OOM 위험.
- **sparse file vs 디스크 사용량**: `init_file`로 5GB 사전 할당해도 sparse file이라 실제 디스크 사용은 청크 도착분만큼만 늘어난다. 단, ext4/XFS 등 sparse file 지원 FS에서만. NTFS/exFAT는 즉시 5GB 점유.
- **sparse hole 보안**: 업로드 미완료 상태에서 파일 읽으면 비어있는 부분이 0 바이트로 보임. 미완료 파일을 외부에서 다운로드 가능하면 안 됨 → access 검증 필수.
- **TUS-Resumable 헤더**: 모든 응답에 `Tus-Resumable: 1.0.0` 헤더가 없으면 `tus-js-client`가 fallback 모드로 들어가 비표준 동작.
- **base64 패딩**: `Upload-Metadata` 값이 base64 패딩(`=`)을 포함하면 콤마 분리 시 깨질 수 있음. CVAT은 단순히 콤마/공백 분리하므로 값 안에 콤마가 들어가면 파싱 실패. tus-js-client는 패딩 사용하지 않으므로 호환 OK.
- **CORS**: 브라우저에서 사용 시 `Upload-Length`, `Upload-Offset`, `Upload-Metadata`, `Tus-Resumable`을 `Access-Control-Allow-Headers`에 추가 + `Tus-Resumable`, `Upload-Offset`을 `Access-Control-Expose-Headers`에 추가.

## 라이선스 주의사항

CVAT MIT. tus-js-client(MIT)와 100% 호환. 추출 시:
```python
# Adapted from CVAT (https://github.com/cvat-ai/cvat)
# Copyright (C) CVAT.ai Corporation
# SPDX-License-Identifier: MIT
```
