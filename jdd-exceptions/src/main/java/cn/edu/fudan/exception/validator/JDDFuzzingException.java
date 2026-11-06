package cn.edu.fudan.exception.validator;

public class JDDFuzzingException extends Exception{
    public JDDFuzzingException() {

    }
    public JDDFuzzingException(String msg) {
        super(msg);
    }

    public JDDFuzzingException(String msg, Throwable cause) {
    }
}
