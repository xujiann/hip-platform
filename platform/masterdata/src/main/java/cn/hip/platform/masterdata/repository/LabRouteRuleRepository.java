package cn.hip.platform.masterdata.repository;

import cn.hip.platform.masterdata.entity.LabRouteRule;
import org.springframework.data.jpa.repository.JpaRepository;

/** v77：检验流向规则。列表/匹配走 jdbc（要 join 科室名与项目名），这里只承担增改启停删的实体读写。 */
public interface LabRouteRuleRepository extends JpaRepository<LabRouteRule, Long> {
}
