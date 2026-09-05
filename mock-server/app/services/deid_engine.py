"""실제 비식별(마스킹) 엔진 — 얼굴·번호판·간판(텍스트)을 검출해 가린다.

<h3>이 모듈이 있는 이유</h3>
목 서버의 기존 산출물은 <b>'비식별 완료' 워터마크를 구운 원본</b>이라 픽셀이 하나도 가려지지
않는다(계약 검증용). 그래서 라벨링·검수 화면에서 "비식별본이 실제로 어떻게 보이는가"를 확인할
수 없고, ``retrieve_report`` 의 얼굴/번호판 수도 dataset_id 로 만든 가짜였다. 이 엔진은 그 자리를
<b>실제 검출·마스킹</b>으로 채운다.

<h3>구속 원칙 — 실패는 예외가 아니라 폴백이다</h3>
이 엔진은 <b>어떤 실패도 예외로 던지지 않고</b> ``None`` 을 돌려준다. 호출측
(``deid_sim._burn_deid_watermark``)은 그때 기존 워터마크 경로로, 그것마저 실패하면 원본 복사로
내려간다. 모델 파일이 없는 환경(CI·신규 클론)에서 목 서버의 기존 동작이 <b>한 치도 바뀌지 않는
것</b>이 이 설계의 요구사항이다.

<h3>절단 금지 (CWE-345/754)</h3>
``ffmpeg`` 의 ``-t``/``-fs`` 를 쓰지 않는 기존 규약을 그대로 따른다. 자원 초과는 "잘라서
내보내기"가 아니라 <b>"포기하고 폴백하기"</b>다. 산출물이 조용히 짧아지면 그것은 학습데이터
오염이며, 호출측의 길이 보존 검증(``_is_duration_preserved``)도 그 전제 위에 서 있다.

<h3>모델 (전부 permissive 라이선스)</h3>
- 얼굴   : YuNet (MIT)                — ``face_detection_yunet.onnx``
- 번호판 : LPD-YuNet (Apache-2.0)      — ``license_plate_detection_lpd_yunet.onnx``
- 간판   : PP-OCRv3 det (Apache-2.0)   — ``text_detection_ppocr.onnx``
- 사람·차량 : YOLOX (Apache-2.0)       — ``object_detection_yolox.onnx`` (한 번의 추론으로 둘 다)

⚠ <b>번호판 모델은 중국 번호판으로 학습됐다</b>(제공처 README 명시). 한국 번호판 재현율은
검증되지 않았으므로, 이 엔진의 번호판 검출 결과를 실납품 근거로 삼지 말 것.

<h3>왜 사람·차량(YOLOX) 폴백이 필요한가</h3>
원거리 CCTV 에서 보행자 얼굴은 10px 미만이라 <b>얼굴 검출기가 구조적으로 0건</b>을 낸다(실측:
640x360 거리 장면에서 얼굴 0건 · 같은 프레임에서 YOLOX 는 person 12건). 개인정보 보호는
fail-closed 여야 하므로, 얼굴을 찾지 못해도 사람이 있으면 <b>상단 머리 영역</b>을 가린다.

<b>번호판도 같은 처지이며 근거는 더 강하다</b>(실 CCTV 실측 — 도심 교차로 720x480):
- 텍스트 검출기로 번호판 <b>0건</b> (같은 프레임에서 간판·표지판은 8건 정확히 검출)
- 번호판 <b>전용</b> LPD-YuNet 도 진짜 번호판 <b>0건</b> — 임계를 0.3 까지 낮추면 5건이 나오지만
  전부 오검출이었다("단속중" 노란 표지판을 번호판으로 잡았다). 중국 번호판(파란 바탕) 학습이라
  한국 번호판과 색·형태가 다르고, 이 각도·거리에서 번호판이 너무 작다.
- 반면 <b>차량은 같은 프레임에서 15대</b>(트럭 7 · 승용차 8)가 정확히 검출됐다.
⇒ 그래서 번호판 전용 모델을 두지 않고 <b>차량 하단 번호판 자리</b>를 가린다.

⚠ 두 폴백 모두 <b>객체를 지우지 않는다</b> — 사람은 머리만, 차량은 하단 띠만 가려 형태를
남긴다. 전체를 덮으면 라벨링 대상이 사라져 학습데이터로서 무의미해진다.
"""

from __future__ import annotations

import logging
import os
import subprocess
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

logger = logging.getLogger(__name__)

# ── 마스킹 방식 (KPST 계약 ``masking_type``) ──────────────────────
MASK_COLOR = 0
MASK_MOSAIC = 2
MASK_BLUR = 3

# ── 검출 대상 종류 ────────────────────────────────────────────────
KIND_FACE = "face"
KIND_PLATE = "plate"
KIND_TEXT = "text"
KIND_PERSON = "person"
KIND_VEHICLE = "vehicle"

#: 모델 파일명 — 조달 스크립트(``bin/fetch-deid-models.sh``)와 <b>한 세트</b>다.
MODEL_FILES = {
    KIND_FACE: "face_detection_yunet.onnx",
    KIND_TEXT: "text_detection_ppocr.onnx",
    KIND_PERSON: "object_detection_yolox.onnx",
}
# ⚠ <b>번호판 전용 모델은 여기 없다 — 의도된 것이다.</b> 실 CCTV 실측에서 LPD-YuNet 이 진짜
#   번호판을 한 건도 잡지 못했고(자세한 근거는 위 모듈 설명), 번호판은 <b>차량 검출 폴백</b>으로
#   가린다. 이 목록에 넣으면 조달 스크립트가 받지 않는 파일을 계속 "빠졌다"고 보고해
#   운영자가 없는 문제를 쫓게 된다.

#: 모델 디렉터리. 환경변수로 덮어쓸 수 있다(컨테이너 볼륨 마운트 대응).
MODELS_DIR = Path(
    os.environ.get("MOCK_DEID_MODELS_DIR")
    or Path(__file__).resolve().parents[2] / "models"
)

# ── 검출 주기 (대상별로 다르다) ───────────────────────────────────
#
# 얼굴·번호판·사람은 <b>움직이므로</b> 자주 봐야 하고, 간판은 고정 카메라에서 <b>움직이지
# 않으므로</b> 드물게 봐도 된다. 간판(PP-OCR)이 검출 비용의 대부분을 차지하는데(실측: 640p 에서
# 얼굴 237fps · 번호판 74fps · 텍스트 29fps) 그것을 매 프레임 돌리는 것은 낭비다.
DETECT_EVERY_MOVING = 5
DETECT_EVERY_STATIC = 60

#: 검출 결과를 유지하는 최대 프레임 수 — 이 값을 넘으면 박스를 버린다(잔상 방지).
BOX_TTL_FRAMES = DETECT_EVERY_MOVING * 3

#: 마스킹 영역 배율의 허용 범위. KPST 계약은 0.5~2.0 이며 그 밖의 값은 <b>안전한 쪽</b>으로 죈다.
#: (배율을 키우면 더 가리므로 상한만 넘겨도 위험하지 않지만, 프레임 전체를 덮는 것은 막는다.)
MASK_RANGE_MIN = 0.5
MASK_RANGE_MAX = 3.0

#: 사람 박스에서 머리로 간주할 상단 비율. 상체를 넉넉히 덮어 얼굴 미검출을 보상한다.
PERSON_HEAD_RATIO = 0.25

#: 차량 박스에서 번호판이 있을 자리 — 하단 높이 비율 · 가로 중앙 비율.
#: ⚠ <b>실측 근거</b>: 실 CCTV(도심 교차로 720x480)에서 번호판은 텍스트 검출기로도(0건),
#:   번호판 전용 LPD-YuNet 으로도(진짜 번호판 0건 · "단속중" 표지판을 오검출) 잡히지 않았다.
#:   반면 같은 프레임에서 차량은 15대가 정확히 검출됐다. 그래서 <b>얼굴↔사람과 같은 논리</b>로
#:   차량이 보이면 번호판이 있을 자리를 가린다. 차량 형태는 남으므로 라벨링 대상은 보존된다.
PLATE_BAND_HEIGHT_RATIO = 0.30
PLATE_BAND_WIDTH_RATIO = 0.60

#: 얼굴 검출 임계. <b>일부러 높게</b> 잡는다 — 실 CCTV 에서 간판·옷 무늬를 얼굴로 오검출했고,
#: 놓친 얼굴은 사람(YOLOX) 폴백이 머리 영역째 덮으므로 <b>놓침보다 오검출이 더 해롭다</b>
#: (불필요하게 가린 영역은 그대로 학습데이터 품질 손실이다).
FACE_CONF_THRESHOLD = 0.7

#: 모자이크 격자 수 — 영역을 가로·세로 각각 이 개수 이하의 블록으로 뭉갠다.
#: 블록 <b>크기</b>가 아니라 <b>개수</b>를 고정해야 가로로 긴 텍스트 영역도 확실히 지워진다.
MOSAIC_BLOCKS = 8

#: 프레임 1장에서 마스킹할 최대 영역 수 (CWE-400/770).
#: 텍스트가 빽빽한 화면에서 PP-OCR 후보가 폭주해 처리가 <b>50배</b> 느려지는 것을 실측했다.
MAX_BOXES_PER_FRAME = 300

#: 엔진 1건의 전체 실행 상한(초). 초과하면 산출을 <b>포기</b>하고 폴백한다(절단하지 않는다).
ENGINE_TIMEOUT_SEC = float(os.environ.get("MOCK_DEID_ENGINE_TIMEOUT_SEC", "900"))

#: 동시에 돌릴 수 있는 엔진 수 — CPU 를 통째로 점유하지 않도록 묶는다.
ENGINE_MAX_CONCURRENCY = max(1, int(os.environ.get("MOCK_DEID_ENGINE_CONCURRENCY", "1")))

#: 엔진 자리 대기 상한(초). 얻지 못하면 폴백한다.
ENGINE_ACQUIRE_TIMEOUT_SEC = 5.0

#: 디코드 대상 최대 화소 수 — 이보다 크면 <b>검출만</b> 축소 프레임에서 하고 마스킹은 원본에 한다.
#: (원본 해상도를 낮춰 산출하면 그것도 조용한 품질 저하이므로 산출은 항상 원본 해상도다.)
DETECT_MAX_PIXELS = 1920 * 1080

_ENGINE_SEMAPHORE = threading.BoundedSemaphore(ENGINE_MAX_CONCURRENCY)

_detectors_lock = threading.Lock()
_detectors_cache: dict[tuple, Any] = {}


@dataclass
class DeidSummary:
    """엔진 1회 실행 결과 요약 — ``retrieve_report`` 의 실제 검출 수 원천."""

    frames: int = 0
    faces: int = 0
    plates: int = 0
    texts: int = 0
    persons: int = 0
    vehicles: int = 0
    elapsed_sec: float = 0.0
    #: 프레임별 최대 동시 검출 수 — 리포트의 "가장 많이 잡힌 순간" 용
    peak_boxes: int = 0

    def add(self, kind: str, n: int = 1) -> None:
        if kind == KIND_FACE:
            self.faces += n
        elif kind == KIND_PLATE:
            self.plates += n
        elif kind == KIND_TEXT:
            self.texts += n
        elif kind == KIND_PERSON:
            self.persons += n
        elif kind == KIND_VEHICLE:
            self.vehicles += n


def models_available() -> tuple[bool, list[str]]:
    """엔진을 돌릴 수 있는지와 빠진 모델 목록을 돌려준다.

    얼굴 모델만 있어도 엔진은 동작한다(나머지는 그 대상만 건너뛴다). 모델이 <b>하나도</b>
    없으면 엔진을 쓸 수 없다.
    """
    missing = [k for k, name in MODEL_FILES.items() if not (MODELS_DIR / name).is_file()]
    return (len(missing) < len(MODEL_FILES)), missing


def _cv2() -> Any:
    """opencv 를 lazy import 한다 — 미설치 환경에서 목 서버 기동을 막지 않는다."""
    import cv2  # noqa: PLC0415 — 의도적 lazy import

    return cv2


def engine_available() -> bool:
    """엔진 사용 가능 여부(opencv + 모델 최소 1종)."""
    try:
        _cv2()
    except Exception:  # noqa: BLE001 — 미설치/로드 실패 모두 '사용 불가'
        return False
    ok, _ = models_available()
    return ok


# ── 검출기 ────────────────────────────────────────────────────────
#
# 얼굴·텍스트는 opencv <b>내장</b> 모델 클래스로 충분해 외부 래퍼 코드를 벤더링하지 않는다.
# 사람(YOLOX)만 출력이 raw grid 라 후처리를 직접 구현한다(표준 디코드 — grid/stride 복원 + NMS).
#
# ⚠ <b>번호판 전용 모델은 쓰지 않는다.</b> 번호판은 그 자체가 텍스트라 텍스트 검출기가 상당 부분
# 잡는다. 전용 모델(LPD-YuNet)은 ①중국 번호판 학습이라 한국 재현율이 미검증이고 ②래퍼 벤더링이
# 필요해, 실제 재현율 측정에서 텍스트 검출로 부족하다고 판명될 때 넣는 것이 순서다.

_YOLOX_INPUT = 640
_YOLOX_STRIDES = (8, 16, 32)
_COCO_PERSON = 0
#: COCO 차량류 — 자동차·오토바이·버스·트럭. 번호판을 다는 것들이다(자전거는 없다).
_COCO_VEHICLES = (2, 3, 5, 7)
_YOLOX_WANTED = {_COCO_PERSON: KIND_PERSON, **{c: KIND_VEHICLE for c in _COCO_VEHICLES}}
_YOLOX_CONF = 0.35


class _Detectors:
    """3종 검출기 묶음. 좌표는 항상 <b>원본 프레임 스케일</b>로 돌려준다."""

    def __init__(self, width: int, height: int) -> None:
        cv2 = _cv2()
        self.cv2 = cv2
        self.width, self.height = width, height

        # 검출용 축소 배율 — 큰 프레임은 축소해 검출하고 좌표만 되돌린다(산출은 원본 해상도).
        self.scale = 1.0
        if width * height > DETECT_MAX_PIXELS:
            self.scale = (DETECT_MAX_PIXELS / (width * height)) ** 0.5
        self.dw, self.dh = max(1, int(width * self.scale)), max(1, int(height * self.scale))

        self.face = self._load_face(cv2)
        self.text = self._load_text(cv2)
        self.person = self._load_person(cv2)

    # -- 로딩: 개별 실패는 그 대상만 끄고 나머지는 계속 쓴다 --------
    def _load_face(self, cv2: Any) -> Any:
        path = MODELS_DIR / MODEL_FILES[KIND_FACE]
        if not path.is_file():
            return None
        try:
            return cv2.FaceDetectorYN.create(
                str(path), "", (self.dw, self.dh), FACE_CONF_THRESHOLD, 0.3, 5000
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("[MOCK][DEID] 얼굴 모델 로드 실패 type=%s", type(exc).__name__)
            return None

    def _load_text(self, cv2: Any) -> Any:
        path = MODELS_DIR / MODEL_FILES[KIND_TEXT]
        if not path.is_file():
            return None
        try:
            model = cv2.dnn.TextDetectionModel_DB(cv2.dnn.readNet(str(path)))
            model.setBinaryThreshold(0.3).setPolygonThreshold(0.5)
            model.setUnclipRatio(2.0).setMaxCandidates(MAX_BOXES_PER_FRAME)
            model.setInputParams(
                1.0 / 255.0, (736, 736), (122.67891434, 116.66876762, 104.00698793)
            )
            return model
        except Exception as exc:  # noqa: BLE001
            logger.warning("[MOCK][DEID] 텍스트 모델 로드 실패 type=%s", type(exc).__name__)
            return None

    def _load_person(self, cv2: Any) -> Any:
        path = MODELS_DIR / MODEL_FILES[KIND_PERSON]
        if not path.is_file():
            return None
        try:
            return cv2.dnn.readNet(str(path))
        except Exception as exc:  # noqa: BLE001
            logger.warning("[MOCK][DEID] 사람 모델 로드 실패 type=%s", type(exc).__name__)
            return None

    @property
    def any_loaded(self) -> bool:
        return any((self.face, self.text, self.person))

    def _shrink(self, frame: Any) -> Any:
        if self.scale >= 1.0:
            return frame
        return self.cv2.resize(frame, (self.dw, self.dh))

    def _to_origin(self, x: float, y: float, w: float, h: float) -> tuple[int, int, int, int]:
        """검출 좌표계 → 원본 좌표계."""
        inv = 1.0 / self.scale if self.scale else 1.0
        return int(x * inv), int(y * inv), int(w * inv), int(h * inv)

    # -- 검출 ------------------------------------------------------
    def detect_moving(self, frame: Any) -> list[tuple[int, int, int, int, str]]:
        """움직이는 대상(얼굴·사람)을 검출한다."""
        small = self._shrink(frame)
        out: list[tuple[int, int, int, int, str]] = []
        if self.face is not None:
            try:
                _, faces = self.face.detect(small)
                if faces is not None:
                    for f in faces[:MAX_BOXES_PER_FRAME]:
                        out.append((*self._to_origin(*f[:4]), KIND_FACE))
            except Exception as exc:  # noqa: BLE001
                logger.debug("[MOCK][DEID] 얼굴 검출 예외 type=%s", type(exc).__name__)
        if self.person is not None:
            # ★ YOLOX 추론은 <b>한 번만</b> 돌리고 사람·차량을 함께 뽑는다. 종류별로 따로
            #   추론하면 이 파이프라인에서 가장 비싼 연산이 두 배가 된다.
            out.extend(self._detect_objects(small))
        return out

    def detect_static(self, frame: Any) -> list[tuple[int, int, int, int, str]]:
        """고정 대상(간판·번호판 등 텍스트)을 검출한다."""
        if self.text is None:
            return []
        cv2 = self.cv2
        try:
            resized = cv2.resize(frame, (736, 736))
            sx, sy = self.width / 736.0, self.height / 736.0
            boxes, _ = self.text.detect(resized)
        except Exception as exc:  # noqa: BLE001
            logger.debug("[MOCK][DEID] 텍스트 검출 예외 type=%s", type(exc).__name__)
            return []
        import numpy as np  # noqa: PLC0415

        out = []
        for box in boxes[:MAX_BOXES_PER_FRAME]:
            pts = np.array(box, dtype=np.float32).reshape(-1, 2)
            pts[:, 0] *= sx
            pts[:, 1] *= sy
            x, y, w, h = cv2.boundingRect(pts.astype(np.int32))
            out.append((x, y, w, h, KIND_TEXT))
        return out

    def _detect_objects(self, small: Any) -> list[tuple[int, int, int, int, str]]:
        """YOLOX 로 <b>사람과 차량</b>을 한 번에 검출한다 — 두 fail-safe 축의 공통 원천.

        사람은 얼굴 미검출을, 차량은 번호판 미검출을 보상한다. 둘 다 "가려야 할 작은 것"을
        직접 못 찾을 때 "그것을 품고 있는 큰 것"을 찾아 해당 부위를 가리는 같은 전략이다.

        YOLOX 공식 ONNX 는 <b>미디코드 raw grid</b> 를 내보내므로 stride 별 grid/stride 를 적용해
        복원한 뒤 NMS 한다(공식 ``demo_postprocess`` 와 동일 규약).
        """
        import numpy as np  # noqa: PLC0415

        cv2 = self.cv2
        ih, iw = small.shape[:2]
        ratio = min(_YOLOX_INPUT / ih, _YOLOX_INPUT / iw)
        nw, nh = int(iw * ratio), int(ih * ratio)
        canvas = np.full((_YOLOX_INPUT, _YOLOX_INPUT, 3), 114, dtype=np.uint8)
        canvas[:nh, :nw] = cv2.resize(small, (nw, nh))
        blob = np.transpose(canvas.astype(np.float32), (2, 0, 1))[None]

        try:
            self.person.setInput(blob)
            out = self.person.forward(self.person.getUnconnectedOutLayersNames())[0]
        except Exception as exc:  # noqa: BLE001
            logger.debug("[MOCK][DEID] 사람 검출 예외 type=%s", type(exc).__name__)
            return []

        pred = np.squeeze(out)
        if pred.ndim != 2 or pred.shape[1] < 6:
            return []

        # grid/stride 복원
        grids, strides = [], []
        for s in _YOLOX_STRIDES:
            g = _YOLOX_INPUT // s
            xv, yv = np.meshgrid(np.arange(g), np.arange(g))
            grids.append(np.stack((xv, yv), 2).reshape(-1, 2))
            strides.append(np.full((g * g, 1), s))
        grid = np.concatenate(grids, 0)
        stride = np.concatenate(strides, 0)
        if pred.shape[0] != grid.shape[0]:
            return []
        cxcy = (pred[:, :2] + grid) * stride
        wh = np.exp(pred[:, 2:4]) * stride
        scores = pred[:, 4:5] * pred[:, 5:]
        cls = scores.argmax(1)
        conf = scores.max(1)

        keep = np.isin(cls, list(_YOLOX_WANTED)) & (conf > _YOLOX_CONF)
        if not keep.any():
            return []
        boxes = np.concatenate(
            [cxcy[keep] - wh[keep] / 2, wh[keep]], axis=1
        )  # x,y,w,h (letterbox 좌표)
        kept_cls = cls[keep]
        idx = cv2.dnn.NMSBoxes(
            boxes.tolist(), conf[keep].tolist(), _YOLOX_CONF, 0.5
        )
        if idx is None or len(idx) == 0:
            return []
        res = []
        for i in np.array(idx).flatten()[:MAX_BOXES_PER_FRAME]:
            j = int(i)
            x, y, w, h = boxes[j] / ratio  # letterbox → 축소프레임
            kind = _YOLOX_WANTED.get(int(kept_cls[j]), KIND_PERSON)
            res.append((*self._to_origin(x, y, w, h), kind))
        return res


# ── 마스킹 적용 ───────────────────────────────────────────────────


def _expand(
    box: tuple[int, int, int, int], ratio: float, width: int, height: int, kind: str
) -> tuple[int, int, int, int]:
    """``masking_range`` 배율만큼 넓히고 프레임 안으로 자른다.

    <b>사람·차량은 박스 전체를 가리지 않는다.</b> 사람은 상단 머리 영역만, 차량은 하단 번호판
    자리만 가린다. 전체를 덮으면 라벨링 대상인 객체 자체가 사라져 학습데이터 가치가 없어진다 —
    이 둘은 "가려야 할 작은 것을 못 찾았을 때 그것을 품은 큰 것에서 해당 부위만 가리는" 폴백이지,
    그 객체를 지우려는 것이 아니다.
    """
    x, y, w, h = box
    if kind == KIND_PERSON:
        h = max(6, int(h * PERSON_HEAD_RATIO))
    elif kind == KIND_VEHICLE:
        # 하단 중앙 = 번호판 자리. 세로는 아래로 붙이고 가로는 가운데로 모은다.
        band_h = max(4, int(h * PLATE_BAND_HEIGHT_RATIO))
        band_w = max(6, int(w * PLATE_BAND_WIDTH_RATIO))
        x = x + (w - band_w) // 2
        y = y + h - band_h
        w, h = band_w, band_h
    cx, cy = x + w / 2.0, y + h / 2.0
    nw, nh = w * ratio, h * ratio
    nx = int(max(0, cx - nw / 2.0))
    ny = int(max(0, cy - nh / 2.0))
    return nx, ny, int(min(width - nx, nw)), int(min(height - ny, nh))


def _apply_mask(cv2: Any, frame: Any, box: tuple[int, int, int, int], mask_type: int) -> None:
    """영역 하나를 가린다. 블러 커널은 영역 크기에 <b>비례</b>시킨다.

    고정 커널을 쓰면 큰 얼굴이 뭉개지지 않아 식별이 남는다 — 개인정보 관점에서 이것이 실패다.
    """
    x, y, w, h = box
    if w <= 0 or h <= 0:
        return
    roi = frame[y : y + h, x : x + w]
    if roi.size == 0:
        return
    if mask_type == MASK_COLOR:
        frame[y : y + h, x : x + w] = 0
    elif mask_type == MASK_MOSAIC:
        # 블록 <b>개수</b>를 고정한다 — 블록 <b>크기</b>를 영역에 비례시키면 안 된다.
        # ⚠ 실측 사고: 구 구현은 ``step = min(w,h)//8`` 이었는데, 간판·번호판 같은 텍스트 영역은
        #   가로로 길고 세로가 짧아(예: 200x20) step 이 2 로 떨어져 <b>2배 축소</b>밖에 안 됐다.
        #   산출 영상에서 상호명이 그대로 읽혔다. 개인정보 관점에서 이것은 마스킹 실패다.
        sw = max(1, min(w, MOSAIC_BLOCKS))
        sh = max(1, min(h, MOSAIC_BLOCKS))
        small = cv2.resize(roi, (sw, sh), interpolation=cv2.INTER_LINEAR)
        frame[y : y + h, x : x + w] = cv2.resize(
            small, (w, h), interpolation=cv2.INTER_NEAREST
        )
    else:  # MASK_BLUR (기본)
        k = max(3, (min(w, h) // 2) | 1)
        frame[y : y + h, x : x + w] = cv2.GaussianBlur(roi, (k, k), 0)


# ── ffmpeg 입출력 ─────────────────────────────────────────────────


def _probe_video(ffprobe: str, src: Path) -> Optional[tuple[int, int, str]]:
    """(가로, 세로, 프레임률 분수) — 실패하면 None."""
    try:
        completed = subprocess.run(  # noqa: S603 — 고정 인자 리스트, shell 미사용
            [
                ffprobe, "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height,r_frame_rate",
                "-of", "default=nw=1:nk=1", os.fspath(src),
            ],
            shell=False, capture_output=True, stdin=subprocess.DEVNULL,
            timeout=30, check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if completed.returncode != 0:
        return None
    lines = completed.stdout.decode("utf-8", "replace").split()
    if len(lines) < 3:
        return None
    try:
        return int(lines[0]), int(lines[1]), lines[2]
    except ValueError:
        return None


def _decode_cmd(ffmpeg: str, src: Path) -> list[str]:
    return [
        ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
        "-i", os.fspath(src),
        "-f", "rawvideo", "-pix_fmt", "bgr24", "-",
    ]


def _encode_cmd(
    ffmpeg: str, src: Path, temp: Path, width: int, height: int, fps: str,
    codec_args: list[str], threads: int, faststart: bool,
) -> list[str]:
    """원본을 <b>두 번째 입력</b>으로 붙여 오디오를 그대로 옮긴다(기존 워터마크 경로와 동일 규약).

    ⚠ ``-t``/``-fs`` 를 넣지 않는다 — 절단은 rc=0 으로 끝나 성공과 구분되지 않는다.
    """
    cmd = [
        ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
        "-f", "rawvideo", "-pix_fmt", "bgr24",
        "-s", f"{width}x{height}", "-r", fps, "-i", "-",
        "-i", os.fspath(src),
        "-map", "0:v:0", "-map", "1:a?",
        *codec_args,
        "-pix_fmt", "yuv420p", "-c:a", "copy",
        "-threads", str(threads),
    ]
    if faststart:
        cmd += ["-movflags", "+faststart"]
    cmd.append(os.fspath(temp))
    return cmd


# ── 파이프라인 본체 ───────────────────────────────────────────────


def render_masked(
    src: Path,
    temp: Path,
    *,
    ffmpeg: str,
    ffprobe: str = "ffprobe",
    masking_type: int = MASK_BLUR,
    masking_range: float = 1.2,
    codec_args: Optional[list[str]] = None,
    threads: int = 2,
    faststart: bool = False,
) -> Optional[DeidSummary]:
    """``src`` 를 실제로 비식별해 ``temp`` 로 산출한다. 실패는 <b>전부</b> ``None``.

    호출측은 ``None`` 을 받으면 기존 워터마크 → 원본 복사 순으로 폴백한다. 이 함수는 예외를
    밖으로 내보내지 않는다(목의 기존 계약을 깨지 않는 것이 요구사항).

    프레임 수를 <b>1:1 로 보존</b>한다 — 읽은 프레임을 모두 그대로 내보내므로 호출측의 길이
    보존 검증을 통과한다. 시간 상한에 걸리면 <b>중간 산출물을 버리고</b> ``None`` 을 돌려준다
    (짧은 영상을 성공으로 내보내지 않는다).
    """
    try:
        cv2 = _cv2()
        import numpy as np  # noqa: PLC0415
    except Exception:  # noqa: BLE001
        return None

    probed = _probe_video(ffprobe, src)
    if probed is None:
        logger.info(
            "[MOCK][DEID] ffprobe 실패 — 비식별 엔진 건너뜀 file=%s", _short(src.name)
        )
        return None
    width, height, fps = probed
    if width <= 0 or height <= 0:
        return None

    ratio = min(MASK_RANGE_MAX, max(MASK_RANGE_MIN, float(masking_range or 1.0)))

    if not _ENGINE_SEMAPHORE.acquire(timeout=ENGINE_ACQUIRE_TIMEOUT_SEC):
        logger.warning(
            "[MOCK][DEID] 엔진 동시 실행 상한(%d) 대기 초과 — 폴백 file=%s",
            ENGINE_MAX_CONCURRENCY, _short(src.name),
        )
        return None

    summary = DeidSummary()
    started = time.monotonic()
    dec = enc = None
    try:
        with _detectors_lock:
            key = (width, height)
            det = _detectors_cache.get(key)
            if det is None:
                det = _Detectors(width, height)
                _detectors_cache[key] = det
        if not det.any_loaded:
            return None

        codec = codec_args or ["-c:v", "libx264", "-preset", "ultrafast", "-crf", "23"]
        dec = subprocess.Popen(  # noqa: S603 — 고정 인자 리스트
            _decode_cmd(ffmpeg, src), shell=False,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL,
        )
        enc = subprocess.Popen(  # noqa: S603 — 고정 인자 리스트
            _encode_cmd(ffmpeg, src, temp, width, height, fps, codec, threads, faststart),
            shell=False,
            stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )

        frame_bytes = width * height * 3
        moving: list = []
        static: list = []
        moving_age = 0
        idx = 0
        while True:
            if time.monotonic() - started > ENGINE_TIMEOUT_SEC:
                logger.warning(
                    "[MOCK][DEID] 엔진 시간 상한(%.0fs) 초과 — 산출을 버리고 폴백 file=%s",
                    ENGINE_TIMEOUT_SEC, _short(src.name),
                )
                return None
            chunk = dec.stdout.read(frame_bytes)
            if not chunk or len(chunk) < frame_bytes:
                break
            frame = np.frombuffer(chunk, dtype=np.uint8).reshape(height, width, 3).copy()

            if idx % DETECT_EVERY_MOVING == 0:
                moving = det.detect_moving(frame)
                moving_age = 0
                for b in moving:
                    summary.add(b[4])
            else:
                moving_age += 1
                if moving_age > BOX_TTL_FRAMES:
                    moving = []
            if idx % DETECT_EVERY_STATIC == 0:
                static = det.detect_static(frame)
                for b in static:
                    summary.add(b[4])

            boxes = (moving + static)[:MAX_BOXES_PER_FRAME]
            summary.peak_boxes = max(summary.peak_boxes, len(boxes))
            for b in boxes:
                _apply_mask(cv2, frame, _expand(b[:4], ratio, width, height, b[4]), masking_type)

            try:
                enc.stdin.write(frame.tobytes())
            except (BrokenPipeError, OSError):
                logger.warning(
                    "[MOCK][DEID] 인코더 파이프 끊김 — 폴백 file=%s", _short(src.name)
                )
                return None
            idx += 1

        summary.frames = idx
        if idx == 0:
            return None
        try:
            enc.stdin.close()
        except OSError:
            pass
        if enc.wait(timeout=ENGINE_TIMEOUT_SEC) != 0:
            logger.warning("[MOCK][DEID] 인코딩 실패 — 폴백 file=%s", _short(src.name))
            return None
    except Exception as exc:  # noqa: BLE001 — 어떤 실패든 폴백으로 흡수한다
        logger.warning(
            "[MOCK][DEID] 엔진 예외 — 폴백 file=%s type=%s",
            _short(src.name), type(exc).__name__,
        )
        return None
    finally:
        _terminate(enc)
        _terminate(dec)
        _ENGINE_SEMAPHORE.release()

    summary.elapsed_sec = time.monotonic() - started
    logger.info(
        "[MOCK][DEID] 비식별 완료 file=%s frames=%d 얼굴=%d 사람=%d 차량=%d 텍스트=%d %.1fs",
        _short(src.name), summary.frames, summary.faces, summary.persons,
        summary.vehicles, summary.texts, summary.elapsed_sec,
    )
    return summary


def _terminate(proc: Any) -> None:
    """자식 프로세스를 확실히 정리한다 — 남으면 ffmpeg 가 좀비로 쌓인다."""
    if proc is None:
        return
    try:
        if proc.poll() is None:
            proc.kill()
        for stream in (proc.stdin, proc.stdout):
            if stream is not None:
                try:
                    stream.close()
                except OSError:
                    pass
        proc.wait(timeout=5)
    except Exception:  # noqa: BLE001
        pass


def _short(name: str) -> str:
    """로그용 파일명 정화 — 제어문자 제거(CWE-117)와 길이 절단만 한다."""
    cleaned = "".join(ch for ch in str(name) if ch.isprintable())
    return cleaned[:120]
