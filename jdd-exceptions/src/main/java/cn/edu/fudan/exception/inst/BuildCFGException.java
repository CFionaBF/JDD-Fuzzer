package cn.edu.fudan.exception.inst;

public class BuildCFGException extends CFGException {
    public BuildCFGException() {
    }

    public BuildCFGException(String msg) {
        super(msg);
    }

    public BuildCFGException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
