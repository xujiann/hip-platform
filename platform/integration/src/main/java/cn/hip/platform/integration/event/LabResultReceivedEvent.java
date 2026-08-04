package cn.hip.platform.integration.event;

import java.util.List;

/** LIS 结果回传事件：集成层解析后发布，业务模块订阅落库 */
public record LabResultReceivedEvent(String orderGroupNo, List<Item> items) {

    /** abnormalFlag: N 正常 / H 偏高 / L 偏低 / HH LL 危急 */
    public record Item(String code, String name, String value, String unit,
                       String refRange, String abnormalFlag) {}
}
