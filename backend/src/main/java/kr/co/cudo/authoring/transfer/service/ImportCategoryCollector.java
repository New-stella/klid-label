package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.transfer.entity.LsOtsdCtgryMpng;
import kr.co.cudo.authoring.transfer.parser.ExternalNameSanitizer;
import kr.co.cudo.authoring.transfer.parser.ImportedDataset;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 산출물이 <b>실제로 쓴 분류</b>를 모으는 단일 지점.
 *
 * <h3>왜 한 곳인가</h3>
 * <p>검사(미리보기)와 적재가 각자 이 목록을 만들면 두 목록이 어긋난다. 검사에서 "대응이 다 끝났다"고
 * 본 산출물이 적재에서는 미확정으로 막히거나, 반대로 검사가 묻지 않은 분류가 적재에서 짐작으로
 * 연결될 수 있다. 그 둘은 <b>같은 근거로 판정</b>돼야 한다.
 *
 * <h3>선언이 아니라 사용 기준이다</h3>
 * <p>문서의 분류 선언 목록이 아니라 <b>도형이 실제로 참조한</b> 분류만 모은다. 쓰이지 않은 분류까지
 * 확정을 요구하면 사람이 영상마다 손대야 하는 항목이 늘어난다.
 *
 * @design DOMAIN-017
 * @design ERD-031
 * @design AC-042
 */
public final class ImportCategoryCollector {

    private ImportCategoryCollector() {
    }

    /** 도형 라벨이 쓴 분류(식별 문자열 → 표시 이름). 등장 순서를 지킨다. */
    public static Map<String, String> labelCategories(ImportedDataset dataset) {
        Map<String, String> categories = new LinkedHashMap<>();
        for (ImportedDataset.Frame frame : dataset.frames()) {
            for (ImportedDataset.Shape shape : frame.shapes()) {
                String code = ExternalNameSanitizer.text(shape.categoryId(),
                        LsOtsdCtgryMpng.OTSD_CTGRY_NM_MAX);
                if (code == null) {
                    continue;
                }
                categories.putIfAbsent(code, ExternalNameSanitizer.text(shape.categoryName(),
                        LsOtsdCtgryMpng.OTSD_CTGRY_NM_MAX));
            }
        }
        return categories;
    }

    /**
     * 영상이 가리키는 이벤트 분류. 산출물은 이벤트를 <b>이름으로만</b> 주고 코드를 주지 않으므로
     * 그 이름이 곧 대응의 열쇠다. 상위 계층 이름은 참고 정보라 대응 대상이 아니다(ERD-031).
     */
    public static Map<String, String> eventCategories(ImportedDataset dataset) {
        if (dataset.video() == null) {
            return Map.of();
        }
        String eventName = ExternalNameSanitizer.text(dataset.video().eventName(),
                LsOtsdCtgryMpng.OTSD_CTGRY_NM_MAX);
        if (eventName == null) {
            return Map.of();
        }
        return Map.of(eventName, eventName);
    }
}
