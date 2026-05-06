package kr.co.cudo.authoring.export.service;

import kr.co.cudo.authoring.common.util.YoloCocoConverter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 10 — YOLO 포맷 직렬화 (CVAT YoloCocoConverter 위임).
 *
 * <p>본 V1 은 단일 파일 본문으로 모든 프레임의 YOLO 라벨을 합치고,
 * 프레임 구분자(주석 라인 # frame={src_sn})를 삽입해 외부 데이터마트에서 후처리 가능하도록 한다.
 *
 * <p>YOLO classic 디렉토리 구조(obj.names/obj.data/train.txt)는 후속 Phase 에서 zip 패키징 시 추가.
 */
@Component
public class YoloExportWriter {

    public String write(List<ExportRunner.FramePayload> frames) {
        if (frames == null || frames.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < frames.size(); i++) {
            ExportRunner.FramePayload f = frames.get(i);
            // 이미지 크기는 LsDataSrc 에 별도 컬럼이 없으므로 기본 1920x1080 가정 (실제 운영 시 이미지 메타 조회로 보강 예정)
            int imgW = 1920;
            int imgH = 1080;
            List<YoloCocoConverter.YoloShape> shapes = new ArrayList<>(f.labels().size());
            for (ExportRunner.LabelPayload lbl : f.labels()) {
                shapes.add(new YoloCocoConverter.YoloShape(lbl.categoryIndex(), lbl.points()));
            }
            String yolo = YoloCocoConverter.toYolo(shapes, imgW, imgH);
            if (i > 0) {
                out.append('\n');
            }
            out.append("# frame=").append(f.src().getSrcSn()).append('\n').append(yolo);
        }
        return out.toString();
    }
}
