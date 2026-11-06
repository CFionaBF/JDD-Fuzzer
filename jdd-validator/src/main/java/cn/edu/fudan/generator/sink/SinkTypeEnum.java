package cn.edu.fudan.generator.sink;

public class SinkTypeEnum {

    enum SinkType {
        FILE,
        INVOKE,
        EXPRESSION_INJECTION,
        GROOVY,
        CLOJURE,
        JNDI,
        CUSTOM,
        UNKNOWN
    }

    // 将和SinkType枚举值中相同的字符串转为对应的SinkType
    public static SinkType transformSinkType(String type) {
        for (SinkType sinkType : SinkType.values()) {
            if (sinkType.name().equals(type)) {
                return sinkType;
            }
        }
        return SinkType.UNKNOWN;
    }

}
