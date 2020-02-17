package com.datorama.oss.timbermill;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.tuple.Pair;
import org.awaitility.Awaitility;
import org.junit.Assert;

import com.datorama.oss.timbermill.annotation.TimberLogTask;
import com.datorama.oss.timbermill.unit.LogParams;
import com.datorama.oss.timbermill.unit.Task;
import com.datorama.oss.timbermill.unit.TaskStatus;

import static com.datorama.oss.timbermill.EventLogger.LOG_WITHOUT_CONTEXT;

public abstract class TimberLogTest {

	static final String DEFAULT_TIMBERMILL_URL = "http://localhost:8484";
	static final String TEST = "test";
	static final String EVENT = "Event";
	static final String LOG_REGEX = "\\[.+] \\[INFO] - ";

	protected static final String DEFAULT_ELASTICSEARCH_URL = "http://localhost:9200";
	protected static ElasticsearchClient client;

	private static final String EVENT_CHILD = "EventChild";
	private static final String EVENT_CHILD_OF_CHILD = "EventChildOfChild";
	private static final Exception FAIL = new Exception("fail");
	private static final String SPOT = "Spot";
	private String childTaskId;
	private String childOfChildTaskId;

	public synchronized static void waitForTask(String taskId, TaskStatus status) {
		Callable<Boolean> callable = () -> (client.getTaskById(taskId) != null) && (client.getTaskById(taskId).getStatus() == status);
		waitForCallable(callable);
	}

	public static void waitForTasks(String taskId, int tasksAmounts) {
		Callable<Boolean> callable = () -> (client.getMultipleTasksByIds(taskId) != null) && (client.getMultipleTasksByIds(taskId).size() == tasksAmounts);
		waitForCallable(callable);
	}

	static void waitForCallable(Callable<Boolean> callable) {
		Awaitility.await().atMost(60, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS).until(callable);
	}

	protected void testSimpleTaskIndexerJob() throws InterruptedException {
		String str1 = "str1";
		String str2 = "str2";
		String metric1 = "metric1";
		String metric2 = "metric2";
		String text1 = "text1";
		String text2 = "text2";

		String log1 = "log1";
		String log2 = "log2";
		String ctx = "ctx";
		String taskId = testSimpleTaskIndexerJobTimberLog(str1, str2, metric1, metric2, text1, text2, log1, log2, ctx);

		waitForTask(taskId, TaskStatus.SUCCESS);
		Task task = client.getTaskById(taskId);

		assertTaskPrimary(task, EVENT, TaskStatus.SUCCESS, taskId, true, true);
		Assert.assertEquals(TEST, task.getEnv());
		Assert.assertNotNull(task.getDateToDelete());
		Assert.assertTrue(task.getDuration() >= 1000);
		Map<String, String> strings = task.getString();
		Map<String, Number> metrics = task.getMetric();
		Map<String, String> texts = task.getText();
		String log = task.getLog();

		Assert.assertEquals(str1, strings.get(str1));
		Assert.assertEquals(str2, strings.get(str2));
		Assert.assertEquals(0, metrics.get(metric1).intValue());
		Assert.assertEquals(2, metrics.get(metric2).intValue());
		Assert.assertEquals(text1, texts.get(text1));
		Assert.assertEquals(text2, texts.get(text2));

		String[] split = log.split("\n");
		Assert.assertEquals(2, split.length);
		Assert.assertTrue(split[0].matches(LOG_REGEX + log1));
		Assert.assertTrue(split[1].matches(LOG_REGEX + log2));
	}

	@TimberLogTask(name = EVENT)
	private String testSimpleTaskIndexerJobTimberLog(String str1, String str2, String metric1, String metric2, String text1, String text2, String log1, String log2, String ctx) throws InterruptedException {
		TimberLogger.logString(str1, str1);
		TimberLogger.logMetric(metric1, Double.NaN);
		TimberLogger.logText(text1, text1);
		TimberLogger.logInfo(log1);
		StringBuilder stringBuilder = new StringBuilder();
		for (int i = 0; i < 327660; i++) {
			stringBuilder.append("a");
		}
		String longContext = stringBuilder.toString();
		TimberLogger.logText(ctx, longContext);
		TimberLogger.logParams(LogParams.create().string(str2, str2).metric(metric2, 2).text(text2, text2).logInfo(log2));
		Thread.sleep(1000);
		return TimberLogger.getCurrentTaskId();
	}

	protected void testSwitchCasePlugin() {

		String taskId = testSwitchCasePluginLog();

		waitForTask(taskId, TaskStatus.SUCCESS);
		Task task = client.getTaskById(taskId);

        assertTaskPrimary(task, EVENT + "plugin", TaskStatus.SUCCESS, taskId, true, true);
		Map<String, String> strings = task.getString();
		String errorType = strings.get("errorType");
		Assert.assertEquals("TOO_MANY_SERVER_ROWS", errorType);
	}

	@TimberLogTask(name = EVENT + "plugin")
	private String testSwitchCasePluginLog() {
		TimberLogger.logText("exception", "bla bla bla TOO_MANY_SERVER_ROWS bla bla bla");
		return TimberLogger.getCurrentTaskId();
	}

	protected void testSpotWithParent() {
		String context = "context";
		String str = "str";

		final String[] taskIdSpot = {null};
		Pair<String, String> stringStringPair = testSpotWithParentTimberLog(context, str, taskIdSpot);

		String taskId1 = stringStringPair.getLeft();
		String taskId2 = stringStringPair.getRight();
		waitForTask(taskId1, TaskStatus.SUCCESS);
		waitForTask(taskId2, TaskStatus.SUCCESS);
		waitForTask(taskIdSpot[0], TaskStatus.SUCCESS);

		Task task = client.getTaskById(taskId1);
        assertTaskPrimary(task, EVENT, TaskStatus.SUCCESS, taskId1, true, true);

		Task spot = client.getTaskById(taskIdSpot[0]);
		assertTask(spot, SPOT, true, true, taskId1, taskId1, TaskStatus.SUCCESS, EVENT);
		Assert.assertEquals(context, spot.getCtx().get(context));

        Task child = client.getTaskById(taskId2);
        assertTask(child, EVENT + '2', true, true, taskId1, taskId1, TaskStatus.SUCCESS, EVENT);
        Assert.assertEquals(context, child.getCtx().get(context));
	}

	@TimberLogTask(name = EVENT)
	private Pair<String, String> testSpotWithParentTimberLog(String context1, String context2, String[] taskIdSpot) {
		String taskId1 = TimberLogger.getCurrentTaskId();
		TimberLogger.logContext(context1, context1);
		String taskId2 = testSpotWithParentTimberLog2(context2, taskIdSpot, taskId1);
		return Pair.of(taskId1, taskId2);
	}


	@TimberLogTask(name = EVENT + '2')
	private String testSpotWithParentTimberLog2(String context2, String[] taskIdSpot, String taskId1) {
		TimberLogger.logContext(context2, context2);
		Thread thread = new Thread(() -> {
			try (TimberLogContext ignored = new TimberLogContext(taskId1)) {
				taskIdSpot[0] = TimberLogger.spot(SPOT);
			}
		});
		thread.start();
		while(thread.isAlive()){
			waits();
		}
		return TimberLogger.getCurrentTaskId();
	}

	private void waits() {
		try {
			Thread.sleep(1000);
		} catch (InterruptedException ignored) {
		}
	}

	protected void testSimpleTasksFromDifferentThreadsIndexerJob() {

		String context1 = "context1";
		String context2 = "context2";

		String[] tasks = testSimpleTasksFromDifferentThreadsIndexerJobTimberLog1(context1, context2);
		String taskId = tasks[0];
		String taskId2 = tasks[1];
		String taskId3 = tasks[2];
		String taskIdSpot = tasks[3];


		waitForTask(taskId, TaskStatus.SUCCESS);
		waitForTask(taskId2, TaskStatus.SUCCESS);
		waitForTask(taskId3, TaskStatus.SUCCESS);
		waitForTask(taskIdSpot, TaskStatus.SUCCESS);


		Task task = client.getTaskById(taskId);
		assertTaskPrimary(task, EVENT, TaskStatus.SUCCESS, taskId, true, true);
        Assert.assertEquals(context1, task.getCtx().get(context1));

        Task task2 = client.getTaskById(taskId2);
        assertTask(task2, EVENT + '2', true, true, taskId, taskId, TaskStatus.SUCCESS, EVENT);
        Assert.assertEquals(context1, task2.getCtx().get(context1));
        Assert.assertEquals(context2, task2.getCtx().get(context2));

		Task task3 = client.getTaskById(taskId3);
        assertTask(task3, EVENT + '3', true, true, taskId, taskId2, TaskStatus.SUCCESS, EVENT, EVENT + '2');
		Assert.assertEquals(context1, task3.getCtx().get(context1));
		Assert.assertEquals(context2, task3.getCtx().get(context2));

		Task spot = client.getTaskById(taskIdSpot);
		assertTask(spot, SPOT, true, true, taskId, taskId, TaskStatus.SUCCESS, EVENT);
		Assert.assertEquals(context1, spot.getCtx().get(context1));
	}

	@TimberLogTask(name = EVENT)
	private String[] testSimpleTasksFromDifferentThreadsIndexerJobTimberLog1(String context1, String context2) {
		TimberLogger.logContext(context1, context1);
		String taskId = TimberLogger.getCurrentTaskId();
		String[] tasks = testSimpleTasksFromDifferentThreadsIndexerJobTimberLog2(context2, taskId);
		tasks[0] = TimberLogger.getCurrentTaskId();
		return tasks;
	}

	@TimberLogTask(name = EVENT + '2')
	private String[] testSimpleTasksFromDifferentThreadsIndexerJobTimberLog2(String context2, String taskId) {

		TimberLogger.logContext(context2, context2);

		final String[] tasks = new String[4];
		tasks[1] = TimberLogger.getCurrentTaskId();
		Thread thread1 = new Thread(() -> {
			try (TimberLogContext ignored = new TimberLogContext(tasks[1])) {
				tasks[2] = testSimpleTasksFromDifferentThreadsIndexerJobTimberLog3();
			}
		});
		thread1.start();

		Thread thread2 = new Thread(() -> {
			try (TimberLogContext ignored = new TimberLogContext(taskId)) {
				tasks[3] = testSimpleTasksFromDifferentThreadsIndexerJobTimberLog4();
			}
		});
		thread2.start();
		while(thread1.isAlive()){waits();}
		while(thread2.isAlive()){waits();}
		return tasks;
	}

	@TimberLogTask(name = EVENT + '3')
	private String testSimpleTasksFromDifferentThreadsIndexerJobTimberLog3() {
		return TimberLogger.getCurrentTaskId();
	}

	@TimberLogTask(name = SPOT)
	private String testSimpleTasksFromDifferentThreadsIndexerJobTimberLog4() {
		return TimberLogger.getCurrentTaskId();
	}

	protected void testSimpleTasksFromDifferentThreadsWithWrongParentIdIndexerJob() {
		final String[] taskId = new String[1];
		Thread thread = new Thread(() -> {
			try (TimberLogContext ignored = new TimberLogContext("bla")) {
				taskId[0] = testSimpleTasksFromDifferentThreadsWithWrongParentIdIndexerJobTimberLog();
			}

		});
		thread.start();
		while(thread.isAlive()){waits();}

		waitForTask(taskId[0], TaskStatus.SUCCESS);

		Task task = client.getTaskById(taskId[0]);
		assertTask(task, EVENT, true, true, null, "bla", TaskStatus.SUCCESS);
	}

	@TimberLogTask(name = EVENT)
	private String testSimpleTasksFromDifferentThreadsWithWrongParentIdIndexerJobTimberLog() {
		return TimberLogger.getCurrentTaskId();
	}

	protected void testComplexTaskIndexerWithErrorTask() {

		String parentId = testComplexTaskIndexerWithErrorTaskTimberLog3();
		waitForTask(parentId, TaskStatus.SUCCESS);
		waitForTask(childTaskId, TaskStatus.ERROR);
		waitForTask(childOfChildTaskId, TaskStatus.ERROR);

		Task taskParent = client.getTaskById(parentId);
		Task taskChild = client.getTaskById(childTaskId);
		Task taskChildOfChild = client.getTaskById(childOfChildTaskId);

		assertTaskPrimary(taskParent, EVENT, TaskStatus.SUCCESS, parentId, true, true);

		assertTask(taskChild, EVENT_CHILD, true, true, parentId, parentId, TaskStatus.ERROR, EVENT);

		assertTask(taskChildOfChild, EVENT_CHILD_OF_CHILD, true, true, parentId, childTaskId, TaskStatus.ERROR, EVENT, EVENT_CHILD);
	}

	@TimberLogTask(name = EVENT)
	private String testComplexTaskIndexerWithErrorTaskTimberLog3(){
		try {
			testComplexTaskIndexerWithErrorTaskTimberLog2();
		} catch (Exception ignored) {
		}
		return TimberLogger.getCurrentTaskId();
	}

	@TimberLogTask(name = EVENT_CHILD)
	private void testComplexTaskIndexerWithErrorTaskTimberLog2() throws Exception {
		childTaskId = TimberLogger.getCurrentTaskId();
		testComplexTaskIndexerWithErrorTaskTimberLog1();
	}

	@TimberLogTask(name = EVENT_CHILD_OF_CHILD)
	private void testComplexTaskIndexerWithErrorTaskTimberLog1() throws Exception {
		childOfChildTaskId = TimberLogger.getCurrentTaskId();
		throw FAIL;
	}

	protected void testTaskWithNullString() {

		String taskId = testTaskWithNullStringTimberLog();
		waitForTask(taskId, TaskStatus.SUCCESS);

		Task task = client.getTaskById(taskId);
		assertTaskPrimary(task, EVENT, TaskStatus.SUCCESS, taskId, true, true);
	}

	@TimberLogTask(name = EVENT)
	private String testTaskWithNullStringTimberLog() {
		TimberLogger.logString("key", "null");
		return TimberLogger.getCurrentTaskId();
	}

	protected void testOverConstructor() {

		TimberLogTestClass timberLogTestClass = new TimberLogTestClass();
		String taskId = timberLogTestClass.getTaskId();
		waitForTask(taskId, TaskStatus.SUCCESS);

		Task task = client.getTaskById(taskId);
		assertTaskPrimary(task, "ctr", TaskStatus.SUCCESS, taskId, true, true);
	}

	protected void testOverConstructorException() {
		String[] taskArr = new String[1];
		try {
			new TimberLogTestClass(taskArr);
		} catch (Exception ignored) {
		}
		String taskId = taskArr[0];
		waitForTask(taskId, TaskStatus.ERROR);

		Task task = client.getTaskById(taskId);
		assertTaskPrimary(task, "ctr", TaskStatus.ERROR, taskId, true, true);
	}

	protected void testCorruptedInfoOnly() {
		String bla1 = "bla1";
		String bla2 = "bla2";
		String taskId = TimberLogger.logText(bla1, bla2);
		waitForTask(taskId, TaskStatus.CORRUPTED);

		Task task = client.getTaskById(taskId);
		Assert.assertEquals(LOG_WITHOUT_CONTEXT, task.getName());
		Map<String, String> text = task.getText();
		Assert.assertNotNull(text.get(EventLogger.STACK_TRACE));
		Assert.assertEquals(bla2, text.get(bla1));
	}

	protected void testOrphan() {
		String taskId = testOrphanTimberLog();
		waitForTask(taskId, TaskStatus.SUCCESS);
		Task task = client.getTaskById(taskId);
		Assert.assertTrue(task.isOrphan());
	}

	private String testOrphanTimberLog() {
		String taskId = TimberLogger.start("testOrphan", "testOrphanParent", LogParams.create());
		TimberLogger.success();
		return taskId;
	}

	static void assertTask(Task task, String name, boolean shouldHaveEndTime, boolean shouldBeComplete, String primaryId, String parentId, TaskStatus status, String... parents) {
        Assert.assertEquals(name, task.getName());
        Assert.assertEquals(status, task.getStatus());
        Assert.assertNotNull(task.getStartTime());
        if (shouldHaveEndTime){
            Assert.assertNotNull(task.getEndTime());
        }
        else{
            Assert.assertNull(task.getEndTime());
        }

		if (shouldBeComplete) {
			Assert.assertNotNull(task.getDuration());
		} else {
			Assert.assertNull(task.getDuration());
		}
        Assert.assertEquals(primaryId, task.getPrimaryId());
        Assert.assertEquals(parentId, task.getParentId());

        List<String> parentsPath = task.getParentsPath();
        if (parentId == null || (parents.length == 0)){
            Assert.assertNull(parentsPath);
        }
        else {
            Assert.assertNotNull(parentsPath);
            Assert.assertEquals(parents.length, parentsPath.size());
            for (String parent : parents) {
                Assert.assertTrue(parentsPath.contains(parent));
            }
        }
    }

    static void assertTaskPrimary(Task task, String name, TaskStatus status, String primaryId, boolean shouldHaveEndTime, boolean shouldBeComplete) {
        assertTask(task, name, shouldHaveEndTime, shouldBeComplete, primaryId, null, status);
    }

	static void assertTaskCorrupted(Task task, String name, TaskStatus status, boolean shouldHaveEndTime) {
		assertTask(task, name, shouldHaveEndTime, false, null, null, status);
	}
}