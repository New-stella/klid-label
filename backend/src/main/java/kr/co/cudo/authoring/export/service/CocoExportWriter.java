package kr.co.cudo.authoring.export.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.CocoAnnotation;
import kr.co.cudo.authoring.common.util.CocoCategory;
import kr.co.cudo.authoring.common.util.CocoImage;
import kr.co.cudo.authoring.common.util.CocoJson;
import kr.co.cudo.authoring.common.util.YoloCocoConverter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 10 — COCO instances JSON 직렬화 (CVAT YoloCocoConverter 위임).
 *
 * <p>다중 프레임을 단일 COCO JSON 으로 통합 — images[]/annotations[]/categories[] 누적.
 */
@Component
public class CocoExportWriter {

    public String write(List<ExportRunner.FramePayload> frames, ObjectMapper objectMapper) {
        List<CocoImage> images = new ArrayList<>();
        List<CocoAnnotation> annotations = new ArrayList<>();
        Map<String, CocoCategory> categoryMap = new LinkedHashMap<>();

        int annId = 1;
        if (frames != null) {
            for (ExportRunner.FramePayload f : frames) {
                int imgId = images.size() + 1;
                int imgW = 1920;
                int imgH = 1080;
                CocoImage image = CocoImage.of(imgId, "src_" + f.src().getSrcSn() + ".jpg", imgW, imgH);
                images.add(image);

                List<YoloCocoConverter.CocoShape> shapes = new ArrayList<>(f.labels().size());
                for (ExportRunner.LabelPayload lbl : f.labels()) {
                    int catId = categoryMap.computeIfAbsent(lbl.labelName(),
                            k -> new CocoCategory(categoryMap.size() + 1, k, "")).id();
                    shapes.add(new YoloCocoConverter.CocoShape(catId, lbl.points()));
                }
                // 프레임 1장 단위로 변환 후 annotations 누적 (id 는 누적 시퀀스로 재할당)
                CocoJson partial = YoloCocoConverter.toCoco(shapes, image, List.copyOf(categoryMap.values()));
                for (CocoAnnotation a : partial.annotations()) {
                    annotations.add(new CocoAnnotation(
                            annId++, a.imageId(), a.categoryId(), a.bbox(),
                            a.area(), a.segmentation(), a.iscrowd()));
                }
            }
        }

        CocoJson coco = new CocoJson(images, annotations, List.copyOf(categoryMap.values()));
        try {
            return objectMapper.writeValueAsString(coco);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "COCO JSON 직렬화 실패: " + e.getMessage(), e);
        }
    }
}
