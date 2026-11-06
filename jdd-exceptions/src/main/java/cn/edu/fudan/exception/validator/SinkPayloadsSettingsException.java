package cn.edu.fudan.exception.validator;

public class SinkPayloadsSettingsException extends SinkPayloadsGeneratorException {
    public SinkPayloadsSettingsException() {
    }

    public SinkPayloadsSettingsException(String msg) {
        super(msg);
    }

    public SinkPayloadsSettingsException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
