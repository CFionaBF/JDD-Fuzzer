package cn.edu.fudan.utils;

import java.util.concurrent.*;

public abstract class TimeOutTask {
    private ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);

    public void run(int timeoutSeconds) {
        Future<String> future = executorService.submit(() -> {
            task();
            return "OK";
        });
        try {
            String result = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException e) {
            future.cancel(true);
            LogUtils.info2Log("TimeOutTask: TimeOut");
            timeoutHandler();
        } catch (ExecutionException e) {
            if (e.getMessage().contains("NullPointerException")) {
                e.printStackTrace();
                System.exit(0);
            }
        } catch (Exception e) {
            LogUtils.error("TimeOutTask:" + e.getMessage());
        }
        executorService.shutdown();
    }

    protected abstract void task() throws Exception;

    protected void timeoutHandler() {
    }


}
