package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.KeypointPoint;
import kr.co.cudo.authoring.common.util.KeypointSerializer;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link LsDataLbl} → {@link NiaAnnotation} 순수 매퍼 (파일 IO/DB 없음).
 *
 * <p>{@code LBL_TYPE_CD} 분기:
 * <ul>
 *   <li>BBOX / TRACK → {@code bbox=[x,y,w,h]} (POINT_CN 대각/사각 좌표의 min/max 바운딩)</li>
 *   <li>POLYGON / SEGMENT → {@code polygon=[[x,y,x,y,…]]} (xlsx flat 예시 형식)</li>
 *   <li>SKELETON → {@code keypoints=[[x,y,v]×17]} (KeypointSerializer 재사용)</li>
 * </ul>
 * 좌표는 픽셀 그대로(정규화 금지). 좌표 파싱은 {@link LabelPointSerializer}/{@link KeypointSerializer} 재사용.
 *
 * <p>입력 검증(CWE-20): malformed POINT_CN 은 {@link CustomException}(INVALID_INPUT)으로 명확히 거부한다.
 * 예외 메시지에 좌표 원문(PII 가능)을 노출하지 않는다(CWE-117/209).
 *
 * <h3>저장소가 둘이어도 판정은 한 벌이다</h3>
 * 같은 어노테이션 문서를 만드는 라벨 저장소가 둘이다(내부 파이프라인 {@link LsDataLbl} · 포털 사용자
 * 작업 라벨). 엔티티 타입이 달라도 필요한 것은 {@link AnnotationSource} 의 다섯 값뿐이므로, <b>판정은
 * {@link #toAnnotation(AnnotationSource, Integer)} 한 곳</b>에 두고 엔티티 오버로드는 좁은 형태로
 * 옮겨 담아 위임하기만 한다. 도형 분기·좌표 파싱을 저장소마다 복제하면 두 산출물의 좌표 해석이
 * 갈리므로 복제 금지.
 */
@Component
public class LabelToAnnotationMapper {

    private final ObjectMapper objectMapper;

    public LabelToAnnotationMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 한 라벨(내부 파이프라인 엔티티)을 {@link NiaAnnotation} 으로 변환한다.
     *
     * <p>판정은 {@link #toAnnotation(AnnotationSource, Integer)} 가 소유하며 이 오버로드는 좁은 형태로
     * 옮겨 담기만 한다(동작 동일).
     *
     * @param lbl     대상 라벨 (null 금지)
     * @param imageId 소속 프레임 image.id (annotations[].image_id)
     * @return 타입별로 bbox/polygon/keypoints 중 하나만 채워진 annotation
     * @throws CustomException 좌표 파싱 실패(malformed) 시 INVALID_INPUT
     */
    public NiaAnnotation toAnnotation(LsDataLbl lbl, Integer imageId) {
        if (lbl == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "변환 대상 라벨이 null 입니다.");
        }
        return toAnnotation(AnnotationSource.of(lbl), imageId);
    }

    /**
     * 좁은 입력({@link AnnotationSource}) 하나를 {@link NiaAnnotation} 으로 변환한다 — <b>판정 단일 지점</b>.
     *
     * @param src     대상 라벨의 최소 입력 (null 금지)
     * @param imageId 소속 프레임 image.id (annotations[].image_id)
     * @return 타입별로 bbox/polygon/keypoints 중 하나만 채워진 annotation
     * @throws CustomException 좌표 파싱 실패(malformed)·미지원 도형 유형 시 INVALID_INPUT
     *
     * @design API-203
     */
    public NiaAnnotation toAnnotation(AnnotationSource src, Integer imageId) {
        if (src == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "변환 대상 라벨이 null 입니다.");
        }
        Integer id = (src.id() == null) ? null : src.id().intValue();
        String categoryId = (src.labelId() == null) ? null : String.valueOf(src.labelId());
        String trackId = src.trackId();
        String type = src.lblTypeCd();

        List<Number> bbox = null;
        List<List<Number>> polygon = null;
        List<List<Number>> keypoints = null;

        try {
            if (LsDataLbl.TYPE_BBOX.equals(type) || LsDataLbl.TYPE_TRACK.equals(type)) {
                bbox = toBbox(src.pointCn());
            } else if (LsDataLbl.TYPE_POLYGON.equals(type) || LsDataLbl.TYPE_SEGMENT.equals(type)) {
                polygon = toPolygon(src.pointCn());
            } else if (LsDataLbl.TYPE_SKELETON.equals(type)) {
                keypoints = toKeypoints(src.pointCn());
            } else {
                throw new CustomException(ErrorCode.INVALID_INPUT, "지원하지 않는 LBL_TYPE_CD: " + type);
            }
        } catch (CustomException e) {
            throw e;
        } catch (RuntimeException e) {
            // 좌표 원문 미노출 — 식별자만으로 추적 (CWE-117/209).
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "라벨 좌표 파싱 실패 lblSn=" + src.id());
        }

        return new NiaAnnotation(id, imageId, categoryId, trackId, bbox, polygon, keypoints, null);
    }

    /** 대각/사각 좌표를 [x,y,w,h] 바운딩 박스로 변환 (min/max, 픽셀 그대로). */
    private List<Number> toBbox(String pointCn) {
        List<Point> points = LabelPointSerializer.fromJson(pointCn, objectMapper);
        if (points.isEmpty()) {
            throw new IllegalArgumentException("BBOX 좌표가 비어있습니다.");
        }
        double minX = points.get(0).x();
        double minY = points.get(0).y();
        double maxX = minX;
        double maxY = minY;
        for (Point p : points) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
        }
        return List.of(minX, minY, maxX - minX, maxY - minY);
    }

    /** 폴리곤 좌표를 xlsx flat 예시 형식 {@code [[x,y,x,y,…]]} 으로 변환 (픽셀 그대로). */
    private List<List<Number>> toPolygon(String pointCn) {
        List<Point> points = LabelPointSerializer.fromJson(pointCn, objectMapper);
        if (points.isEmpty()) {
            throw new IllegalArgumentException("POLYGON 좌표가 비어있습니다.");
        }
        List<Number> flat = new ArrayList<>(points.size() * 2);
        for (Point p : points) {
            flat.add(p.x());
            flat.add(p.y());
        }
        return List.of(flat);
    }

    /** 키포인트 17점 삼중값 {@code [[x,y,v]×17]} 으로 변환. */
    private List<List<Number>> toKeypoints(String pointCn) {
        List<KeypointPoint> kps = KeypointSerializer.fromJson(pointCn, objectMapper);
        if (kps.isEmpty()) {
            throw new IllegalArgumentException("SKELETON 좌표가 비어있습니다.");
        }
        List<List<Number>> result = new ArrayList<>(kps.size());
        for (KeypointPoint kp : kps) {
            result.add(List.of(kp.x(), kp.y(), kp.v()));
        }
        return result;
    }
}
