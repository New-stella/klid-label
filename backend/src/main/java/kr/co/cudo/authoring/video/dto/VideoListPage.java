package kr.co.cudo.authoring.video.dto;

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
 * 영상 처리 현황 목록 응답 — 기존 페이지에 <b>「제외됨 N건」 하나</b>를 얹은 형태.
 * [@design API-042] [@design ADR-069] [@design AC-1124]
 *
 * <h3>왜 항목마다가 아니라 응답 한 번에 하나인가</h3>
 * 제외 건수는 목록 <b>전체</b>에 걸리는 값이라 영상마다 다르지 않다. 항목에 실으면 같은 숫자가 페이지
 * 크기만큼 반복되고, 무엇보다 <b>행이 하나도 없는 페이지에서는 화면이 건수를 알 수 없게 된다</b> —
 * 제외분만 남은 상태에서 되돌아갈 길을 안내하지 못한다.
 *
 * <h3>★ 0건이어도 싣는다</h3>
 * 값이 0 이라고 키를 빼지 않는다. 빠지면 화면이 「제외된 것이 없다」와 「제외 기능이 없다」를 구분해
 * 보여 주지 못한다. 키는 항상 있고 값만 0 이 된다.
 *
 * <h3>왜 새 응답 객체를 만들지 않고 페이지를 넓혔는가</h3>
 * 응답 본문을 다른 모양으로 감싸면 {@code content}·{@code totalElements} 같은 <b>기존 페이지 계약이
 * 통째로 바뀐다</b>. 상속으로 속성 하나만 더하면 기존 키·타입·의미가 그대로 남고 새 키 하나가 는다.
 * 검수 목록({@code ReviewListPage})이 같은 이유로 선 선례이며 그 골격을 그대로 따른다.
 *
 * <h3>★ 이 숫자를 싣는 창구는 목록마다 하나뿐이다</h3>
 * 작업 목록·검수 목록은 <b>집계 창구</b>가 그 숫자를 갖고, 집계 창구가 없는 영상 처리 현황만 이
 * 페이지 응답이 갖는다. 같은 숫자를 목록과 집계가 함께 실으면 진실원이 둘이 되어 한쪽만 고쳐졌을 때
 * 어느 쪽이 맞는지 가릴 수 없다.
 */
@JsonSerialize(using = VideoListPage.Serializer.class)
public class VideoListPage extends PageImpl<VideoSummaryResponse> {

    /** 응답 키 이름 — 화면과의 계약이라 한 곳에서만 정한다. */
    static final String EXCLUDED_COUNT_FIELD = "excludedCount";

    private final long excludedCount;

    public VideoListPage(List<VideoSummaryResponse> content, Pageable pageable,
                         long total, long excludedCount) {
        super(content, pageable, total);
        this.excludedCount = excludedCount;
    }

    /**
     * 제외 표시가 붙은 영상 건수.
     *
     * <p><b>현재 페이지가 아니라 지금 걸린 필터 범위 전체</b>의 값이다 — 필터를 좁히면 이 숫자도 그
     * 범위 안에서 세어진 값으로 함께 줄어든다. 그래야 이 숫자를 눌러 제외분만 보기로 전환했을 때
     * 나오는 전체 건수와 정확히 일치한다.
     *
     * <p>집계 범위는 이 창구의 조회 범위와 같다 — 요청자가 볼 수 있는 영상만 센다(작업자는 본인
     * 배정분, 검수자 이상은 전체).
     */
    public long getExcludedCount() {
        return excludedCount;
    }

    /**
     * 기존 페이지 본문을 <b>그대로</b> 내보낸 뒤 키 하나만 더한다.
     *
     * <h3>왜 직렬화기가 필요한가</h3>
     * 페이지를 상속해 getter 를 더해도 그 값이 <b>응답에 나오지 않는다</b> — 페이지 본체의 직렬화가
     * 미리 정해진 속성 집합으로 고정돼 있어 하위 클래스의 속성이 조용히 빠진다(검수 목록에서 실측된
     * 사실이다). 「추가했는데 응답에는 없는」 상태는 화면이 값을 못 받는 형태로만 드러나므로 명시적으로 쓴다.
     *
     * <h3>왜 키를 손으로 나열하지 않는가</h3>
     * 페이지 본문의 키 집합(내용·전체 건수·쪽 번호·정렬 …)은 <b>이미 화면이 쓰고 있는 계약</b>이다.
     * 손으로 나열하면 그 목록이 두 번째 진실원이 되어 한 키만 빠뜨려도 조용히 계약이 깨진다. 그래서
     * <b>같은 내용의 평범한 페이지를 한 번 직렬화한 결과</b>에 키를 얹는다 — 본문은 구조적으로 동일하다.
     */
    static class Serializer extends JsonSerializer<VideoListPage> {

        @Override
        public void serialize(VideoListPage value, JsonGenerator gen, SerializerProvider providers)
                throws IOException {
            ObjectMapper mapper = (ObjectMapper) gen.getCodec();
            // 하위 클래스가 아닌 <b>평범한 페이지</b>를 직렬화한다 — 그러지 않으면 이 직렬화기가 자기를 다시 부른다.
            ObjectNode node = mapper.valueToTree(
                    new PageImpl<>(value.getContent(), value.getPageable(), value.getTotalElements()));
            node.put(EXCLUDED_COUNT_FIELD, value.getExcludedCount());
            gen.writeTree(node);
        }
    }
}
