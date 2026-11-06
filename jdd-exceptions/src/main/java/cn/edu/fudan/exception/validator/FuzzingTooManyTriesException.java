package cn.edu.fudan.exception.validator;

public class FuzzingTooManyTriesException extends JDDFuzzingException {
        public FuzzingTooManyTriesException(String msg) {
            super(msg);
        }

        public FuzzingTooManyTriesException(String msg, Throwable cause) {
            super(msg, cause);
        }
}
