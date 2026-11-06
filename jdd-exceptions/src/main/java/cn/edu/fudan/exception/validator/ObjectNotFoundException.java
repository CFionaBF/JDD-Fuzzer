package cn.edu.fudan.exception.validator;

public class ObjectNotFoundException extends JDDFuzzingException {
    public ObjectNotFoundException(String msg) {
        super(msg);
    }
    public ObjectNotFoundException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
