package kr.co.cudo.authoring.portal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 배포 압축본 해제본을 시험 안에서 만든다 — 개발망 실물(2026-09-16)과 같은 모양이 기본이다.
 *
 * <pre>
 *   content/0001.jpg  content/0001.json      ← 평평한 자리(실물)
 *   content/sub/0002.jpg  content/sub/0002.json  ← 하위 폴더(섞여 있어도 같은 규칙)
 *   content/orgnl/…                          ← 원본으로 보이는 자리(등록이 읽으면 안 된다)
 * </pre>
 *
 * <p>비식별 이미지와 원본 이미지는 <b>바이트가 다르다</b> — 원본이 복사됐는지를 내용으로 가를 수 있게 한다.
 */
final class PortalDatasetLayoutFixture {

    /** 비식별 이미지 바이트 — JPEG SOI 로 시작한다. */
    static final byte[] DEID_JPEG = jpeg((byte) 0x11);

    /** 원본 이미지 바이트 — 비식별과 다르다. */
    static final byte[] ORGNL_JPEG = jpeg((byte) 0x22);

    /** 원본으로 보이는 폴더 이름 — 이 아래는 짝이 온전해도 읽히지 않아야 한다. */
    static final String ORIGINAL_DIR = "orgnl";

    private PortalDatasetLayoutFixture() {
    }

    static byte[] jpeg(byte marker) {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0,
                marker, marker, marker, marker, marker, (byte) 0xFF, (byte) 0xD9};
    }

    /** 해제본 안 하위 폴더를 만든다. */
    static Path dir(Path content, String... segments) throws IOException {
        Path p = content;
        for (String s : segments) {
            p = p.resolve(s);
        }
        return Files.createDirectories(p);
    }

    /** 이미지 + 같은 이름의 라벨 문서 짝 한 벌. */
    static void frame(Path dir, int frameNo, String docJson) throws IOException {
        frame(dir, String.format("%04d", frameNo), docJson);
    }

    /** 이름을 직접 정하는 짝 한 벌(숫자가 아닌 이름도 만들 수 있다). */
    static void frame(Path dir, String stem, String docJson) throws IOException {
        Files.write(dir.resolve(stem + ".jpg"), DEID_JPEG);
        Files.writeString(dir.resolve(stem + ".json"), docJson, StandardCharsets.UTF_8);
    }

    /** 원본으로 보이는 자리에 <b>온전한 짝</b>을 놓는다 — 읽히면 영상이 하나 더 생겨 드러난다. */
    static void originalPair(Path content, int frameNo, String docJson) throws IOException {
        Path orgnl = dir(content, ORIGINAL_DIR);
        String stem = String.format("%04d", frameNo);
        Files.write(orgnl.resolve(stem + ".jpg"), ORGNL_JPEG);
        Files.writeString(orgnl.resolve(stem + ".json"), docJson, StandardCharsets.UTF_8);
    }

    /**
     * 개발망 실물(2026-09-16)과 <b>같은 모양</b>의 라벨 문서.
     *
     * <p>영상 파일명이 {@code file_name}, 프레임 번호가 {@code frame_no} 이고, 분류 식별자 목록
     * ({@code categories})이 없이 어노테이션이 분류 <b>이름 문자열</b>({@code category})을 싣는다.
     */
    static String realDoc(String videoFileName, int frameNo, String category) {
        return """
                {
                  "dataset": {"name": "화재 데이터셋 구축", "job_id": "DUMMY-001"},
                  "image": {"file_name": "%04d.jpg", "width": 1280, "height": 720, "frame_no": %d},
                  "video": {"file_name": "%s", "vdo_len_sec": 12, "evnt_type_cd": "EV02000102"},
                  "annotations": [
                    {"id": 1, "category": "%s", "bbox": [400, 200, 480, 360], "bbox_format": "xywh"}
                  ],
                  "lbl_type": "객체 검출",
                  "lbl_fmt": "COCO-JSON",
                  "lat": 37.4783,
                  "lon": 126.9516
                }
                """.formatted(frameNo, frameNo, videoFileName, category);
    }

    /**
     * 우리 산출물(NIA) 모양의 라벨 문서 — 이 모양도 계속 읽힌다.
     *
     * <p>영상 파일명이 {@code filename}, 프레임 번호가 {@code frame_num} 이고 분류가 식별자
     * ({@code category_id}) + {@code categories} 목록이다. 사각 박스 1 · 폴리곤 1 · 키포인트 1.
     *
     * @param categoryId 분류 식별자(라벨 마스터 후보)
     */
    static String niaDoc(String filename, int frameNum, String description, String categoryId) {
        return """
                {
                  "info": {"year": 2026, "version": "1.3"},
                  "video": {"id": "1", "filename": "%s", "fps": "30", "width": 1920, "height": 1080},
                  "event": null,
                  "image": {"id": 1, "file_name": "x.jpg", "width": 1920, "height": 1080,
                            "frame_num": %d, "anonymity": "Y", "pseudonymity": "N",
                            "privacy_included": "N", "description": "%s"},
                  "annotations": [
                    {"id": 1, "image_id": 1, "category_id": "%s", "track_id": "t-1",
                     "bbox": [10, 20, 30, 40], "polygon": null, "keypoints": null},
                    {"id": 2, "image_id": 1, "category_id": "%s", "track_id": null,
                     "bbox": null, "polygon": [[0, 0, 10, 0, 10, 10]], "keypoints": null},
                    {"id": 3, "image_id": 1, "category_id": "%s", "track_id": null,
                     "bbox": null, "polygon": null, "keypoints": [[1, 2, 2]]}
                  ],
                  "categories": [{"id": "%s", "name": "사람"}],
                  "type": "instances"
                }
                """.formatted(filename, frameNum, description, categoryId, categoryId, categoryId, categoryId);
    }
}
