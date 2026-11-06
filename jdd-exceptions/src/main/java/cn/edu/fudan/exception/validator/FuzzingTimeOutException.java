package cn.edu.fudan.exception.validator;

public class FuzzingTimeOutException extends JDDFuzzingException {
        public FuzzingTimeOutException(String msg) {
            super(msg);
        }

        public FuzzingTimeOutException(String msg, Throwable cause) {
            super(msg, cause);
        }
}
