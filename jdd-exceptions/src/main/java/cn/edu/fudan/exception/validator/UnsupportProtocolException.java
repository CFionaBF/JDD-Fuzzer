package cn.edu.fudan.exception.validator;

public class UnsupportProtocolException extends ValidationException {
    public UnsupportProtocolException() {

    }

    public UnsupportProtocolException(String msg) {
        super(msg);
    }

    public UnsupportProtocolException(Throwable e) {
        super(e.getMessage());
    }
}
