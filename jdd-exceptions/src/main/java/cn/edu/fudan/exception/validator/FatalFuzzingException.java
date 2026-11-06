package cn.edu.fudan.exception.validator;

public class FatalFuzzingException extends JDDFuzzingException{
    public FatalFuzzingException(String message) {
        super(message);
    }
}
