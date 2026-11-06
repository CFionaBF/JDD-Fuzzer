package cn.edu.fudan.exception.inst;

public class InvalidCFGStyleSigException extends CFGException {
    public InvalidCFGStyleSigException() {

    }

    public InvalidCFGStyleSigException(String msg) {
        super(msg);
    }

    public InvalidCFGStyleSigException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
