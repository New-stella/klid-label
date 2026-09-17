package kr.co.cudo.authoring.review.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.util.List;

/**
 * 검수 목록 응답 — 기존 페이지에 <b>일괄 승인 건수 상한 한 개</b>를 얹은 형태.
 *
 * <h3>왜 항목마다가 아니라 응답 한 번에 하나인가</h3>
 * 상한은 요청 전체에 걸리는 값이라 영상마다 다르지 않다. 항목에 실으면 같은 숫자가 페이지 크기만큼
 * 반복되고, 행이 하나도 없는 페이지에서는 화면이 상한을 알 수 없게 된다.
 *
 * <h3>왜 새 응답 객체를 만들지 않고 페이지를 넓혔는가</h3>
 * 응답 본문을 다른 모양으로 감싸면 {@code content}·{@code totalElements} 같은 <b>기존 페이지 계약이
 * 통째로 바뀐다</b>. 상속으로 속성 하나만 더하면 기존 키·타입·의미가 그대로 남고 새 키 하나가 는다 —
 * 이 라운드가 지켜야 하는 「추가만」에 맞는 유일한 형태다.
 *
 * <p>화면은 이 값으로 고를 건수를 <b>미리</b> 제한한다(눌러서 거부당한 뒤 안내받는 동선을 없앤다).
 * 다만 이것은 편의이고 <b>실제 강제는 일괄 승인 창구가 그대로 한다</b>.
 *
 * @design API-008
 * @design AC-1117
 */
@JsonSerialize(using = ReviewListPage.Serializer.class)
public class ReviewListPage extends PageImpl<ReviewResponse> {

    /** 응답 키 이름 — 화면과의 계약이라 한 곳에서만 정한다. */
    static final String BULK_APPROVE_LIMIT_FIELD = "bulkApproveLimit";

    private final int bulkApproveLimit;

    public ReviewListPage(List<ReviewResponse> content, Pageable pageable,
                          long total, int bulkApproveLimit) {
        super(content, pageable, total);
        this.bulkApproveLimit = bulkApproveLimit;
    }

    /**
     * 한 번에 일괄 승인으로 담을 수 있는 최대 건수.
     *
     * <p>배포 설정값이라 계약에 고정 숫자가 없다 — 값이 바뀌면 다음 조회부터 바뀐 값이 내려온다.
     * 소유자는 {@code ReviewBatchApprovePolicy} 한 곳이며 화면은 숫자를 스스로 갖지 않는다.
     */
    public int getBulkApproveLimit() {
        return bulkApproveLimit;
    }

    /**
     * 기존 페이지 본문을 <b>그대로</b> 내보낸 뒤 키 하나만 더한다.
     *
     * <h3>왜 직렬화기가 필요한가 (실측)</h3>
     * 페이지를 상속해 getter 를 더해도 그 값이 <b>응답에 나오지 않는다</b> — 페이지 본체의 직렬화가
     * 미리 정해진 속성 집합으로 고정돼 있어 하위 클래스의 속성이 조용히 빠진다. 「추가했는데 응답에는
     * 없는」 상태는 화면이 값을 못 받는 형태로만 드러나므로, 여기서 명시적으로 쓴다.
     *
     * <h3>왜 키를 손으로 나열하지 않는가</h3>
     * 페이지 본문의 키 집합(내용·전체 건수·쪽 번호·정렬 …)은 <b>이미 화면이 쓰고 있는 계약</b>이다.
     * 손으로 나열하면 그 목록이 두 번째 진실원이 되어 한 키만 빠뜨려도 조용히 계약이 깨진다. 그래서
     * <b>같은 내용의 평범한 페이지를 한 번 직렬화한 결과</b>에 키를 얹는다 — 본문은 구조적으로 동일하다.
     */
    static class Serializer extends JsonSerializer<ReviewListPage> {

        @Override
        public void serialize(ReviewListPage value, JsonGenerator gen, SerializerProvider providers)
                throws IOException {
            ObjectMapper mapper = (ObjectMapper) gen.getCodec();
            // 하위 클래스가 아닌 <b>평범한 페이지</b>를 직렬화한다 — 그러지 않으면 이 직렬화기가 자기를 다시 부른다.
            ObjectNode node = mapper.valueToTree(
                    new PageImpl<>(value.getContent(), value.getPageable(), value.getTotalElements()));
            node.put(BULK_APPROVE_LIMIT_FIELD, value.getBulkApproveLimit());
            gen.writeTree(node);
        }
    }
}
