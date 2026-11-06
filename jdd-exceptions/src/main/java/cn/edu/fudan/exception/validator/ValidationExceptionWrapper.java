package cn.edu.fudan.exception.validator;

public class ValidationExceptionWrapper extends ValidationException {
    public Throwable coreCause;
    public ValidationExceptionWrapper(Throwable e) {
        this.coreCause = e;
    }
}
