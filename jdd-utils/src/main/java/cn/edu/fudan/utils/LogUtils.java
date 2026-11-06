package cn.edu.fudan.utils;

import java.io.File;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;

public abstract class LogUtils {

    private static boolean isLog2File = false;
    private static boolean isInit = false;
    private static File logFile;
    private static PrintWriter pw;

    private static HashSet<Integer> loggedInformation = new HashSet<>();

    private static String runtime = getCurrentDateTime();

    private final static String RED_TAG = "\033[31m"; // 错误
    private final static String GREEN_TAG = "\033[32m"; // 成功完成任务
    private final static String YELLOW_TAG = "\033[33m"; // 警告
    private final static String RESET_TAG = "\033[0m"; // 重置颜色
    private final static String BLUE_TAG = "\033[34m"; // DEBUG

    public static void info(String msg) {
        log("[INFO]", getCurrentDateTime(), msg);
    }

    public static void success(String msg) {
        log(GREEN_TAG + "[SUCCESS]", getCurrentDateTime(), msg);
    }

    public static void warn(String msg) {
        log(YELLOW_TAG + "[WARNING]", getCurrentDateTime(), msg);
    }

    public static void error(String msg) {
        log(RED_TAG + "[ERROR]", getCurrentDateTime(), msg);
    }

    public static void errorWithException(String msg, Throwable e) {
        StringBuilder errorMsg = new StringBuilder();
        errorMsg.append(msg).append("\n");
        Throwable current = e;
        while (current != null) {
            errorMsg.append(current.getMessage()).append("\n");
            for (StackTraceElement element : current.getStackTrace()) {
                errorMsg.append("\tat ").append(element.toString()).append("\n");
            }
            current = current.getCause();
            if (current != null) {
                errorMsg.append("Caused by: ");
            }
        }
        LogUtils.error(errorMsg.toString());
    }

    // [DEBUG] lines (several per mutation) only with -Djdd.debug=true: they made the logs of a full
    // application run tens of GB
    private static final boolean DEBUG = Boolean.getBoolean("jdd.debug");

    public static void debug(String msg) {
        if (DEBUG) {
            log(BLUE_TAG + "[DEBUG]", getCurrentDateTime(), msg);
        }
    }

    public static void success2Log(String msg) {
        logIntoFile(msg, LogUtils::success);
    }

    public static void error2Log(String msg) {
        logIntoFile(msg, LogUtils::error);
    }

    public static void warn2Log(String msg) {
        logIntoFile(msg, LogUtils::warn);
    }

    public static void info2Log(String msg) {
        logIntoFile(msg, LogUtils::info);
    }

    public static void debug2Log(String msg) {
        logIntoFile(msg, LogUtils::debug);
    }

    public static void logWarnOnce(String msg) {
        if (loggedInformation.contains(msg.hashCode())) return;
        loggedInformation.add(msg.hashCode());
        warn(msg);
    }

    public static void log(String tag, String time, String msg) {
        if (!isLog2File) System.out.println(tag + "-" + time + "-" + msg + RESET_TAG);
        else {
            if (!isInit) {
                isInit = true;
                try {
                    logFile = new File("logs/jdd-fuzzer-log-" + runtime + ".log");
                    FileUtils.ensureFileParentExist(logFile);
                    logFile.createNewFile();
                    pw = new PrintWriter(logFile);
                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(0);
                }
            }
            pw.println(cmdTag2FileTag(tag) + "-" + time + "-" + msg);
            pw.flush();
        }
    }

    public static void logIntoFile(String msg, LogFunction logFunc) {
        isLog2File = true;
        logFunc.log(msg);
        isLog2File = false;
    }

    private static String cmdTag2FileTag(String tag) {
        int index = tag.indexOf("[");
        if (index == -1) return tag;
        return tag.substring(index);
    }

    public static String getAppRuntime() {
        return runtime;
    }

    public static void log2FuzzerStat(int iocd, String msg) {
        FileUtils.writeStringToFileAppend("logs/" + iocd + "-stats-" + LogUtils.getAppRuntime() , msg);
    }

    /**
     * @return current date and time in the format of "yyyy-MM-dd-HH-mm-ss"
     */
    public static String getCurrentDateTime() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
        return now.format(formatter);
    }

    @FunctionalInterface
    public interface LogFunction {
        void log(String msg);
    }
}
