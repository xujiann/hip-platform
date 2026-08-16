package cn.hip.platform.integration.hl7;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 轻量 HL7 V2 管道分隔消息模型。
 * 字段索引与 HL7 规范一致：MSH 段中 MSH-1 为字段分隔符本身，其余段 SEG-1 为首个 | 后的字段。
 */
public class Hl7V2Message {

    public record Segment(String name, List<String> fields) {
        /** 取字段（1 基）；越界返回空串 */
        public String field(int idx) {
            return idx >= 0 && idx < fields.size() ? fields.get(idx) : "";
        }
    }

    private final List<Segment> segments = new ArrayList<>();

    public static Hl7V2Message parse(String raw) {
        Hl7V2Message msg = new Hl7V2Message();
        for (String line : raw.replace("\r\n", "\r").replace("\n", "\r").split("\r")) {
            if (line.isBlank() || line.length() < 3) continue;
            String name = line.substring(0, 3);
            List<String> fields = new ArrayList<>();
            if ("MSH".equals(name)) {
                // MSH|^~\&|... : MSH-1 是 |，MSH-2 是 ^~\&
                fields.add("MSH");
                fields.add("|");
                fields.addAll(Arrays.asList(line.substring(4).split("\\|", -1)));
                // 上面 addAll 的第一个元素是 ^~\&（MSH-2），已对齐
            } else {
                fields.addAll(Arrays.asList(line.split("\\|", -1)));
            }
            msg.segments.add(new Segment(name, fields));
        }
        return msg;
    }

    public Segment first(String name) {
        return segments.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }

    /** 按报文原序返回全部段：多 OBR 报文需要按顺序切组，first/all 会丢失归属关系 */
    public List<Segment> segmentsInOrder() {
        return List.copyOf(segments);
    }

    public List<Segment> all(String name) {
        return segments.stream().filter(s -> s.name().equals(name)).toList();
    }

    /** 消息类型，如 ORU^R01（MSH-9） */
    public String messageType() {
        Segment msh = first("MSH");
        return msh == null ? "" : msh.field(9).replace('^', '_');
    }
}
