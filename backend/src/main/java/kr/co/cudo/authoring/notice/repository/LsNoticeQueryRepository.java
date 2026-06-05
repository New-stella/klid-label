package kr.co.cudo.authoring.notice.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.notice.entity.LsNotice;
import kr.co.cudo.authoring.notice.entity.QLsNotice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 공지 검색 전용 QueryDSL 리포지토리.
 *
 * <p>보안:
 * <ul>
 *   <li>검색 필드는 {@link SearchField} enum 으로 화이트리스트 — 컬럼 선택에 사용자 문자열 직접 사용 금지.</li>
 *   <li>keyword 는 {@code %}/{@code _}/{@code \} 를 이스케이프 후 {@code like(...).escape('\\')} 로 바인딩
 *       — SQL Injection · 와일드카드 주입 차단.</li>
 *   <li>WORKER 는 {@code publishedOnly=true} 로 강제되어 DRAFT 가 WHERE 단에서 제외된다.</li>
 *   <li>정렬은 결정론적: PIN_YN DESC(고정 우선) → REG_DT DESC(최신) → NOTICE_SN DESC(PK tie-break).</li>
 * </ul>
 */
@Repository
public class LsNoticeQueryRepository {

    private static final char ESCAPE_CHAR = '\\';

    @PersistenceContext(unitName = "control")
    private EntityManager entityManager;

    /**
     * 공지 페이징 검색.
     *
     * @param field         검색 대상 필드 (화이트리스트). null 이면 검색 조건 없음.
     * @param keyword       검색어. null/blank 이면 검색 조건 없음.
     * @param publishedOnly true 면 PUBLISHED 만 (WORKER), false 면 전체 (REVIEWER).
     * @param pageable      페이징 정보
     */
    public Page<LsNotice> search(SearchField field, String keyword, boolean publishedOnly, Pageable pageable) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);
        QLsNotice notice = QLsNotice.lsNotice;

        BooleanBuilder where = new BooleanBuilder();
        if (publishedOnly) {
            where.and(notice.pubStatus.eq(LsNotice.PublishStatus.PUBLISHED));
        }
        BooleanExpression keywordCond = keywordCondition(notice, field, keyword);
        if (keywordCond != null) {
            where.and(keywordCond);
        }

        List<LsNotice> content = queryFactory
                .selectFrom(notice)
                .where(where)
                .orderBy(orderSpecifiers(notice))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        JPAQuery<Long> countQuery = queryFactory
                .select(notice.count())
                .from(notice)
                .where(where);

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    private OrderSpecifier<?>[] orderSpecifiers(QLsNotice notice) {
        // PIN_YN 은 "Y"/"N" 문자열 — DESC 정렬 시 "Y"(고정) 가 먼저 온다.
        return new OrderSpecifier<?>[]{
                notice.pinYn.desc(),
                notice.regDt.desc(),
                notice.noticeSn.desc()
        };
    }

    /**
     * keyword LIKE 조건 생성. 와일드카드/이스케이프 문자를 이스케이프하여 안전 바인딩한다.
     * field == ALL 이면 제목 OR 내용.
     */
    private BooleanExpression keywordCondition(QLsNotice notice, SearchField field, String keyword) {
        if (field == null || keyword == null || keyword.isBlank()) {
            return null;
        }
        String pattern = "%" + escapeLike(keyword) + "%";
        return switch (field) {
            case TITLE -> notice.title.like(pattern, ESCAPE_CHAR);
            case CONTENT -> notice.content.like(pattern, ESCAPE_CHAR);
            case ALL -> notice.title.like(pattern, ESCAPE_CHAR)
                    .or(notice.content.like(pattern, ESCAPE_CHAR));
        };
    }

    /** LIKE 특수문자({@code \ % _}) 이스케이프. 백슬래시를 먼저 처리한다. */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /** 검색 대상 필드 화이트리스트. */
    public enum SearchField {
        TITLE,
        CONTENT,
        ALL
    }
}
