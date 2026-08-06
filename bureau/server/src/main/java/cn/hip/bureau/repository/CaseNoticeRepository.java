package cn.hip.bureau.repository;

import cn.hip.bureau.entity.CaseNotice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CaseNoticeRepository extends JpaRepository<CaseNotice, Long> {

    List<CaseNotice> findByCaseIdOrderByIdDesc(Long caseId);

    Optional<CaseNotice> findTopByCaseIdOrderByIdDesc(Long caseId);
}
