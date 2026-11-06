package cn.edu.fudan.exception.validator;

public class GeneratorException extends Exception {

    public GeneratorException() {

    }

    public GeneratorException(String msg) {
        super(msg);
    }

    public GeneratorException(Throwable e) {
        super(e);
    }

    public GeneratorException(String msg, Throwable e) {
        super(msg, e);
    }

}
