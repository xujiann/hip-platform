package cn.hip.bureau.repository;

import cn.hip.bureau.entity.CaseReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CaseReviewRepository extends JpaRepository<CaseReview, Long> {

    List<CaseReview> findByCaseIdOrderByIdDesc(Long caseId);

    Optional<CaseReview> findTopByCaseIdOrderByIdDesc(Long caseId);
}
