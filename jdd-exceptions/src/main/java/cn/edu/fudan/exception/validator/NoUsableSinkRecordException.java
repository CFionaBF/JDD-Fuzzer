package cn.edu.fudan.exception.validator;

public class NoUsableSinkRecordException extends SinkPayloadsGeneratorException {
    public NoUsableSinkRecordException() {
    }

    public NoUsableSinkRecordException(String msg) {
        super(msg);
    }
}
