package kr.co.cudo.authoring.portal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ADR-068 이 <b>가정한</b> 배포본 구성을 시험 안에서 만든다 — 실제 샘플이 아니다.
 *
 * <pre>
 *   content/{묶음}/{영상 키}/v{n}/deid/NNNN.jpg + NNNN.json
 *   content/{묶음}/{영상 키}/v{n}/orgnl/NNNN.jpg          ← 원본(등록이 읽으면 안 된다)
 * </pre>
 *
 * <p>비식별 이미지와 원본 이미지는 <b>바이트가 다르다</b> — 원본이 복사됐는지를 내용으로 가를 수 있게 한다.
 */
final class PortalDatasetLayoutFixture {

    /** 비식별 이미지 바이트 — JPEG SOI 로 시작한다. */
    static final byte[] DEID_JPEG = jpeg((byte) 0x11);

    /** 원본 이미지 바이트 — 비식별과 다르다. */
    static final byte[] ORGNL_JPEG = jpeg((byte) 0x22);

    private PortalDatasetLayoutFixture() {
    }

    static byte[] jpeg(byte marker) {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0,
                marker, marker, marker, marker, marker, (byte) 0xFF, (byte) 0xD9};
    }

    /** 버전 폴더 하나를 만들고 비식별 폴더 자리를 돌려준다(원본 폴더도 함께 만든다). */
    static Path version(Path content, String videoKey, int version) throws IOException {
        Path v = content.resolve("bundle").resolve(videoKey).resolve("v" + version);
        Files.createDirectories(v.resolve("orgnl"));
        return Files.createDirectories(v.resolve("deid"));
    }

    /** 프레임 한 장 — 비식별 이미지 + 같은 이름의 문서 + 원본 폴더의 원본 이미지. */
    static void frame(Path deidDir, int frameNo, String docJson) throws IOException {
        String stem = String.format("%04d", frameNo);
        Files.write(deidDir.resolve(stem + ".jpg"), DEID_JPEG);
        Files.writeString(deidDir.resolve(stem + ".json"), docJson, StandardCharsets.UTF_8);
        Files.write(deidDir.getParent().resolve("orgnl").resolve(stem + ".jpg"), ORGNL_JPEG);
    }

    /**
     * NIA 어노테이션 문서 — 사각 박스 1 · 폴리곤 1 · 키포인트 1.
     *
     * @param categoryId 분류 식별자(라벨 마스터 후보)
     */
    static String doc(String filename, int frameNum, String description, String categoryId) {
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
