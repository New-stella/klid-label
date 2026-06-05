package kr.co.cudo.authoring.notice.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.notice.entity.LsNoticeAttach;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsNoticeAttachRepository extends JpaRepository<LsNoticeAttach, Long> {

    /**
     * 첨부 + 소속 공지 동시 검증 (IDOR 방어 — CWE-639).
     * attachSn 만으로 조회하지 않고 noticeSn 일치까지 요구해 타 공지의 첨부 접근을 차단한다.
     */
    Optional<LsNoticeAttach> findByAttachSnAndNoticeSn(Long attachSn, Long noticeSn);

    /** 특정 공지의 전체 첨부 목록 (상세 응답·물리 파일 정리용). */
    List<LsNoticeAttach> findAllByNoticeSn(Long noticeSn);
}
