package cn.edu.fudan.exception.validator;

public class ValidationException extends Exception {
    public ValidationException() {

    }

    public ValidationException(String msg) {
        super(msg);
    }
}
