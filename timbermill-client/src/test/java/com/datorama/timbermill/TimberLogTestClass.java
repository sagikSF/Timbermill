package com.datorama.timbermill;

import com.datorama.timbermill.annotation.TimberLogTask;


class TimberLogTestClass {

    private final String taskId;

    @TimberLogTask(name = "ctr")
    TimberLogTestClass() {
        taskId = TimberLogger.getCurrentTaskId();
    }

    @TimberLogTask(name = "ctr")
    TimberLogTestClass(String[] task) throws Exception {
        task[0] = TimberLogger.getCurrentTaskId();
        throw new Exception();
    }

    String getTaskId() {
        return taskId;
    }
}
