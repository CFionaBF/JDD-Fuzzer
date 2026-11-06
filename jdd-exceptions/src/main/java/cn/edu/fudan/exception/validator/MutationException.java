package cn.edu.fudan.exception.validator;

public class MutationException extends GeneratorException {

    public MutationException() {

    }

    public MutationException(String msg) {
        super(msg);
    }

    public MutationException(Throwable e) {
        super(e);
    }

    public MutationException(String msg, Throwable e) {
        super(msg, e);
    }
}
