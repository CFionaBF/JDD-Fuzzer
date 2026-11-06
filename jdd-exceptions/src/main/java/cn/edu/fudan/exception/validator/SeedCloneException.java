package cn.edu.fudan.exception.validator;

public class SeedCloneException extends MutationException {

    public SeedCloneException() {

    }

    public SeedCloneException(String msg) {
        super(msg);
    }

    public SeedCloneException(Throwable e) {
        super(e);
    }

    public SeedCloneException(String msg, Throwable e) {
        super(msg, e);
    }
}
