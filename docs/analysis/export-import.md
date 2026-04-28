# 데이터셋 내보내기/가져오기

## 개요
CVAT의 Export/Import는 `datumaro` 라이브러리를 기반으로 하며, `dataset_manager/formats/` 하위에 24종의 포맷 변환기가 있다. Export는 RQ 비동기 큐로 처리되며, 준비되면 파일을 다운로드할 수 있다.

## 핵심 코드 위치
| 역할 | 파일 경로 | 비고 |
|------|---------|------|
| 포맷 레지스트리 | `cvat/apps/dataset_manager/formats/registry.py` | 포맷 등록 및 조회 |
| YOLO 포맷 | `cvat/apps/dataset_manager/formats/yolo.py` | YOLOv5, YOLOv8 등 |
| COCO 포맷 | `cvat/apps/dataset_manager/formats/coco.py` | COCO JSON |
| CVAT XML 포맷 | `cvat/apps/dataset_manager/formats/cvat.py` | 자체 포맷 |
| 어노테이션 변환 | `cvat/apps/dataset_manager/task.py` | export_task/export_job |
| Datumaro 바인딩 | `cvat/apps/dataset_manager/bindings.py` | CVAT ↔ Datumaro 변환 |

## 지원 포맷 목록 {#formats-list}

```
cvat/apps/dataset_manager/formats/
├── camvid.py          # CamVid (시맨틱 세그먼테이션)
├── cityscapes.py      # Cityscapes
├── coco.py            # MS COCO JSON (detection/segmentation/keypoint)
├── cvat.py            # CVAT XML (자체 포맷, 가장 완전한 변환)
├── datumaro.py        # Datumaro JSON
├── icdar.py           # ICDAR (텍스트 감지)
├── imagenet.py        # ImageNet
├── kitti.py           # KITTI (자율주행)
├── labelme.py         # LabelMe XML
├── lfw.py             # LFW (얼굴 인식)
├── market1501.py      # Market-1501 (person re-ID)
├── mask.py            # Segmentation Mask (PNG)
├── mot.py             # MOT (Multiple Object Tracking)
├── mots.py            # MOTS (Multi-Object Tracking & Segmentation)
├── openimages.py      # Open Images
├── pascal_voc.py      # Pascal VOC XML
├── pointcloud.py      # 포인트 클라우드
├── velodynepoint.py   # Velodyne 포인트 클라우드
├── vggface2.py        # VGGFace2
├── widerface.py       # WIDER Face
└── yolo.py            # YOLO v5/v8/OBB
```

## YOLO 포맷 {#yolo-format}

```python
# dataset_manager/formats/yolo.py
# 출력 구조:
# obj.names (클래스 목록)
# train.txt (이미지 경로 목록)
# obj_train_data/
#   ├── frame_000001.jpg
#   └── frame_000001.txt  (좌표: class cx cy w h — 정규화 0~1)

# CVAT 픽셀 좌표 → YOLO 정규화 변환:
# cx = (x0 + x1) / (2 * image_width)
# cy = (y0 + y1) / (2 * image_height)
# w  = (x1 - x0) / image_width
# h  = (y1 - y0) / image_height
```

## COCO 포맷 {#coco-format}

```json
// instances_default.json
{
  "info": {...},
  "licenses": [],
  "categories": [
    {"id": 1, "name": "car", "supercategory": ""}
  ],
  "images": [
    {"id": 1, "file_name": "frame_000001.jpg", "width": 1920, "height": 1080}
  ],
  "annotations": [
    {
      "id": 1,
      "image_id": 1,
      "category_id": 1,
      "bbox": [100, 200, 200, 150],  // [x, y, width, height]
      "area": 30000,
      "segmentation": [[100,200,300,200,300,350,100,350]],  // polygon
      "iscrowd": 0
    }
  ]
}
```

## Export API 흐름

```
1. Export 요청 (비동기 시작)
   GET /api/tasks/{id}/dataset?format=YOLO+1.0&save_images=True
   → Response: 202 Accepted
   → Header: X-Request-Id: <rq_id>   (또는 응답 바디의 rq_id 필드)

2. 상태 폴링
   GET /api/requests/{rq_id}
   → {"status": "queued|started|finished|failed", ...}

3. 파일 다운로드 (status == "finished" 후)
   GET /api/tasks/{id}/dataset?format=YOLO+1.0&action=download
   → ZIP 파일 스트리밍
```

> 비동기 상태 추적 전체 흐름은 [async-jobs.md](async-jobs.md) 참조.

## Import API 흐름

```
1. 어노테이션 가져오기
   PUT /api/tasks/{id}/annotations?format=YOLO+1.0
   Body: multipart/form-data (annotation_file)

2. 백그라운드 파싱 (RQ)
   → 202 Accepted

3. 완료 확인 후 어노테이션 반영
```

## CVAT XML 포맷 (자체 포맷)

CVAT XML은 모든 데이터(Track, 속성, interpolation, occluded 등)를 완전하게 표현하는 자체 포맷이다.

```xml
<annotations>
  <version>1.1</version>
  <meta>
    <task>
      <name>my_task</name>
      <labels>
        <label>
          <name>car</name>
          <attributes>
            <attribute>
              <name>color</name>
              <input_type>select</input_type>
              <values>red\nblue\ngreen</values>
            </attribute>
          </attributes>
        </label>
      </labels>
    </task>
  </meta>
  
  <!-- 단일 프레임 shape -->
  <image id="0" name="frame_000001.jpg" width="1920" height="1080">
    <box label="car" xtl="100" ytl="200" xbr="300" ybr="350"
         occluded="0" z_order="0">
      <attribute name="color">red</attribute>
    </box>
    <polygon label="person" points="100,200;150,300;200,200" occluded="0"/>
    <mask label="flood" rle="1 5 2 3" left="100" top="200" width="100" height="50"/>
  </image>
  
  <!-- 비디오 Track -->
  <track id="0" label="car" source="manual">
    <box frame="0" xtl="100" ytl="100" xbr="200" ybr="200" outside="0" occluded="0">
      <attribute name="speed">fast</attribute>
    </box>
    <box frame="10" xtl="150" ytl="110" xbr="250" ybr="210" outside="0" occluded="0"/>
    <box frame="20" outside="1" occluded="0"/>  <!-- 트랙 종료 -->
  </track>
</annotations>
```

## 핵심 의사결정

- **datumaro 라이브러리 채택**: CVAT은 24종 포맷 변환을 자체 구현하지 않고 `datumaro` 오픈소스 라이브러리에 위임한다. 신규 포맷 추가 시 datumaro 플러그인으로 확장.
- **포맷 레지스트리**: `dataset_manager/formats/registry.py`에서 모든 포맷을 등록/조회하는 단일 진입점.
- **Export는 RQ 비동기**: 대용량 export는 별도 워커(`cvat_worker_export`)에서 실행. 202 Accepted + `X-Request-Id`로 클라이언트가 폴링.
- **Import도 RQ 비동기**: PUT으로 어노테이션 파일 업로드 → 별도 워커(`cvat_worker_import`)에서 파싱.
- **CVAT XML이 자체 포맷**: Track, 속성, interpolation, occluded 등 CVAT의 모든 데이터를 손실 없이 표현. 다른 포맷은 부분 정보만 표현 가능.
- **YOLO 정규화**: 픽셀 절대값 → 정규화 좌표(0~1) 변환을 export 시 수행. 역방향 import 시 image width/height가 필요.
- **POLYGON → YOLO 정보 손실**: YOLO는 BBOX만 지원하므로 POLYGON의 외접 사각형으로 변환됨.
- **MASK(RLE) → COCO segmentation**: RLE 디코딩이 필요하며 `datumaro.util.mask_tools`에 구현됨.
- **YOLO 버전 분기**: YOLOv5/v7/v8/OBB 버전별로 디렉토리 구조가 다르므로 `yolo.py` 내부에 버전별 분기 존재.

## 독립 포팅 가이드

### 추출 난이도
**중** — datumaro 라이브러리에 변환 위임이 깔끔하지만, datumaro 자체가 큰 의존성(opencv, numpy, scipy 등). datumaro를 빼고 YOLO/COCO만 직접 변환하면 200줄 가능 (모듈 07 참조). CVAT XML 같은 자체 포맷까지 추출하려면 보간 로직 + Track/속성 직렬화까지 같이 옮겨야 함.

### 핵심 추출 대상 (필수 파일)
| 역할 | 원본 경로 | 포팅 시 행수/조치 |
|------|----------|-----------------|
| YOLO export/import | `cvat/apps/dataset_manager/formats/yolo.py` | 174줄 — datumaro 위임 |
| COCO export/import | `cvat/apps/dataset_manager/formats/coco.py` | 107줄 |
| CVAT XML format | `cvat/apps/dataset_manager/formats/cvat.py` | 1000줄+ — 자체 포맷 직렬화 |
| MaskConverter (RLE) | `cvat/apps/dataset_manager/formats/transformations.py:54-101` | → 모듈 02 |
| RotatedBoxesToPolygons | `cvat/apps/dataset_manager/formats/transformations.py:15-51` | 회전 BBOX → polygon 변환 (40줄) |
| 포맷 레지스트리 | `cvat/apps/dataset_manager/formats/registry.py` | `@exporter`, `@importer` 데코레이터 |
| Datumaro 바인딩 | `cvat/apps/dataset_manager/bindings.py` | 큼. 자체 모델 → datumaro Dataset 변환 |
| Job/Task export 진입 | `cvat/apps/dataset_manager/task.py:export_task/export_job` | RQ 작업 함수 |

### 외부 라이브러리 의존성
| 라이브러리 | 버전 | 대체 가능? | 비고 |
|----------|------|:--------:|------|
| datumaro | >=1.0 | 직접 구현 시 불필요 | 100MB+ 의존성 |
| pycocotools | >=2.0 | 가능 | mask 사용 시 필수 |
| pyunpack | (선택) | 가능 | ZIP/7z 자동 해제 |
| opencv-python | >=4.5 | mask 사용 시 필수 | findContours, drawing |
| Pillow | >=9 | 필수 | 이미지 저장 |

### 내부 CVAT 의존성과 분리 방법
| 의존 모듈 | 포팅 시 처리 |
|---------|-----------|
| `dataset_manager.bindings.GetCVATDataExtractor` | 자체 어노테이션 → datumaro Dataset 어댑터 (없이 직접 변환도 가능) |
| `dataset_manager.bindings.import_dm_annotations` | 자체 import 어댑터 |
| `dataset_manager.util.make_zip_archive` | `zipfile.ZipFile` 표준 |
| RQ 워커 | → 모듈 08 |

### 최소 동작 단위 (MVP)
- YOLO 단방향(export만) → 100줄 (모듈 07)
- COCO 단방향(export만) → 100줄
- ZIP 압축 → 표준 zipfile

### 포팅 단계 (체크리스트)
1. [ ] 자체 어노테이션 모델 → 단순 Shape NamedTuple (모듈 07)
2. [ ] YOLO 좌표 변환 공식 적용
3. [ ] COCO JSON 작성
4. [ ] (mask 시) pycocotools 설치 + RLE 변환 (모듈 02)
5. [ ] (회전 BBOX 시) RotatedBoxesToPolygons 패턴 적용
6. [ ] (Track 시) 보간 + 트랙 ID 부여 (모듈 01)
7. [ ] RQ 워커로 비동기 처리 (모듈 08)

### 통합 지점 (이 모듈을 다른 시스템에 붙일 때)
- 입력: Task/Job ID + format name + (선택) include_images
- 출력: ZIP 파일 (또는 download URL)
- 외부 인터페이스: `GET /api/tasks/{id}/dataset?format=YOLO+1.0` (비동기, X-Request-Id 폴링)

### 알려진 함정
- **datumaro 의존성 무게**: 100MB+ 패키지. 단순 YOLO/COCO만 필요하면 직접 구현이 가벼움.
- **YOLO 정규화 좌표**: cxcywh, [0,1] 범위. 픽셀 단위로 export하면 모델 학습 실패.
- **COCO bbox 형식**: xywh (좌상단+크기). YOLO와 다름. 혼동 시 위치/크기 모두 어긋남.
- **POLYGON → YOLO 정보 손실**: YOLO classic은 BBOX만. 폴리곤은 외접 사각형으로 변환됨. Ultralytics YOLO Segmentation 사용 시만 polygon 보존.
- **MASK → COCO**: iscrowd=1로 RLE export, iscrowd=0이면 polygon. 잘못 설정하면 학습 시 sample 가중치 어긋남.
- **회전 BBOX**: COCO/YOLO classic 미지원. CVAT XML 또는 Ultralytics YOLO OBB만 가능.
- **Track export**: YOLO/COCO 표준은 트랙 미지원. Ultralytics YOLO Detection Track에 track_id 추가 컬럼.
- **categories 순서**: COCO category_id의 정수 자체에 의미가 있을 수 있음 (사전 학습 모델). 순서 보존 필수.
- **이미지 메타 필요**: import 시 정규화 좌표 → 픽셀 변환을 위해 image width/height 필수. 누락 시 모든 좌표가 이상해짐.

## Docker 미사용 대응

Import/Export는 별도 RQ 워커(`cvat_worker_import`, `cvat_worker_export`)에서 실행되지만, 단순 Python 프로세스이므로 Docker 의존성 없음.

```ini
# /etc/systemd/system/cvat-worker-export.service
[Unit]
Description=CVAT RQ worker (export)
After=redis-server.service postgresql.service

[Service]
Type=simple
User=cvat
WorkingDirectory=/opt/cvat
Environment="DJANGO_SETTINGS_MODULE=cvat.settings.production"
EnvironmentFile=/etc/cvat/cvat.env
ExecStart=/opt/cvat/venv/bin/python manage.py rqworker -v 3 export \
    --worker-class cvat.rqworker.DefaultWorker
Restart=always
# Export는 대용량 ZIP/TAR 생성으로 임시 디스크 공간을 사용
# /tmp 마운트 크기 또는 TMPDIR 환경변수로 위치 변경 가능

[Install]
WantedBy=multi-user.target
```

### 대용량 export와 NFS

워커가 여러 호스트에 분산되거나 export 결과를 다른 서버에서 다운로드해야 하는 경우, `MEDIA_DATA_ROOT`를 NFS 또는 GlusterFS 같은 공유 스토리지에 두는 것이 권장된다.

| 옵션 | 권장 시나리오 |
|------|-------------|
| 로컬 디스크 | 단일 호스트 운영 |
| NFS | 단순 공유, 소규모 |
| GlusterFS/CephFS | 대규모, HA 필요 |
| S3 + django-storages | 클라우드 운영, 무한 확장 |

### datumaro 의존성

`datumaro` 패키지는 OpenCV, numpy, Pillow 등에 의존한다. pip install로 설치 가능하지만 OpenCV는 시스템 의존성(`libgl1-mesa-glx`, `libglib2.0-0` 등)이 있다.

```bash
# Ubuntu 22.04
apt install -y libgl1-mesa-glx libglib2.0-0
pip install datumaro
```

자세한 통합 배포는 [deployment-without-docker.md](deployment-without-docker.md) 참고.
