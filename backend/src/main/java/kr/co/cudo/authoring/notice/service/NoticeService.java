package kr.co.cudo.authoring.notice.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.notice.entity.LsNotice;
import kr.co.cudo.authoring.notice.repository.LsNoticeQueryRepository;
import kr.co.cudo.authoring.notice.repository.LsNoticeQueryRepository.SearchField;
import kr.co.cudo.authoring.notice.repository.LsNoticeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게시판(공지) 응용 서비스.
 *
 * <p>도메인 상태 변경은 Aggregate Root({@link LsNotice}) 의 도메인 메서드에 위임한다.
 *
 * <p>가시성 규칙(보안):
 * <ul>
 *   <li>WORKER 는 PUBLISHED 공지만 조회 가능 — 목록은 QueryDSL WHERE 로 DRAFT 제외,
 *       상세는 DRAFT 접근 시 <b>404</b>(403 아님 — DRAFT 존재 자체를 노출하지 않음).</li>
 *   <li>REVIEWER 는 DRAFT 포함 전체 조회 가능.</li>
 * </ul>
 */
@Slf4j
@Service
public class NoticeService {

    private final LsNoticeRepository noticeRepository;
    private final LsNoticeQueryRepository noticeQueryRepository;
    private final NoticeAttachService noticeAttachService;

    // NoticeAttachService 가 NoticeService 를 주입받으므로 순환 회피를 위해 @Lazy 로 주입.
    public NoticeService(LsNoticeRepository noticeRepository,
                         LsNoticeQueryRepository noticeQueryRepository,
                         @Lazy NoticeAttachService noticeAttachService) {
        this.noticeRepository = noticeRepository;
        this.noticeQueryRepository = noticeQueryRepository;
        this.noticeAttachService = noticeAttachService;
    }

    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Page<LsNotice> search(SearchField field, String keyword, Pageable pageable, TokenClaims actor) {
        boolean publishedOnly = !isReviewer(actor);
        return noticeQueryRepository.search(field, keyword, publishedOnly, pageable);
    }

    @Transactional(value = "controlTransactionManager", readOnly = true)
    public LsNotice get(long id, TokenClaims actor) {
        LsNotice notice = noticeRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "공지를 찾을 수 없습니다."));
        // WORKER 가 DRAFT 에 접근하면 존재를 숨기기 위해 404 로 응답.
        if (!isReviewer(actor) && !notice.isPublished()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "공지를 찾을 수 없습니다.");
        }
        return notice;
    }

    @Transactional("controlTransactionManager")
    public LsNotice create(String title, String content, boolean pinned, TokenClaims actor) {
        LsNotice notice = LsNotice.create(title, content, pinned, actorId(actor));
        return noticeRepository.save(notice);
    }

    @Transactional("controlTransactionManager")
    public LsNotice update(long id, String title, String content, boolean pinned, TokenClaims actor) {
        LsNotice notice = findExisting(id);
        notice.update(title, content, pinned, actorId(actor));
        return notice;
    }

    @Transactional("controlTransactionManager")
    public void delete(long id) {
        if (!noticeRepository.existsById(id)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "공지를 찾을 수 없습니다.");
        }
        // MINOR 3 / MAJOR 2 — 첨부 물리 파일 정리는 NoticeAttachService 에 일원화 위임한다.
        // DB row 는 FK ON DELETE CASCADE 로 함께 삭제되고, 파일은 커밋 성공 후(afterCommit)에만 제거.
        noticeAttachService.cleanupPhysicalFiles(id);
        noticeRepository.deleteById(id);
    }

    @Transactional("controlTransactionManager")
    public LsNotice publish(long id) {
        LsNotice notice = findExisting(id);
        notice.publish();
        return notice;
    }

    @Transactional("controlTransactionManager")
    public LsNotice unpublish(long id) {
        LsNotice notice = findExisting(id);
        notice.unpublish();
        return notice;
    }

    private LsNotice findExisting(long id) {
        return noticeRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "공지를 찾을 수 없습니다."));
    }

    private static boolean isReviewer(TokenClaims actor) {
        return actor != null && actor.role() == Role.REVIEWER;
    }

    private static String actorId(TokenClaims actor) {
        return actor == null ? null : actor.sub();
    }
}
