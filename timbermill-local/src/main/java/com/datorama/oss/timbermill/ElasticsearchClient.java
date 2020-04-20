package com.datorama.oss.timbermill;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.admin.cluster.storedscripts.PutStoredScriptRequest;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.*;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.*;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.client.core.CountResponse;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.PutIndexTemplateRequest;
import org.elasticsearch.client.indices.rollover.RolloverRequest;
import org.elasticsearch.client.indices.rollover.RolloverResponse;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.*;
import org.elasticsearch.search.Scroll;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.auth.AWS4Signer;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.util.IOUtils;
import com.datorama.oss.timbermill.common.Constants;
import com.datorama.oss.timbermill.common.DbBulkRequest;
import com.datorama.oss.timbermill.common.DiskHandler;
import com.datorama.oss.timbermill.common.ElasticsearchUtil;
import com.datorama.oss.timbermill.common.exceptions.MaximunInsertTriesException;
import com.datorama.oss.timbermill.unit.Task;
import com.datorama.oss.timbermill.unit.TaskStatus;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.internal.LazilyParsedNumber;

import static com.datorama.oss.timbermill.common.ElasticsearchUtil.TIMBERMILL_INDEX_PREFIX;

public class ElasticsearchClient {

	private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchClient.class);
    private static final String TTL_FIELD = "meta.dateToDelete";
    private static final String STATUS = "status";
	private final RestHighLevelClient client;
    private final int indexBulkSize;
    private final ExecutorService executorService;
    private long maxIndexAge;
    private long maxIndexSizeInGB;
    private long maxIndexDocs;
    private String currentIndex;
    private String oldIndex;
	private int numOfElasticSearchActionsTries;
	private int maxBulkIndexFetches; // after such number of fetches, bulk is considered as failed and won't be persisted anymore
	private LinkedBlockingQueue<Pair<DbBulkRequest, Integer>> failedRequests = new LinkedBlockingQueue<>(100000);
	private DiskHandler diskHandler;
	public AtomicInteger numOfBulksPersistedToDisk = new AtomicInteger(0);
	public int numOfSuccessfullBulksFromDisk = 0;
	public int numOfFetchedMaxTimes = 0;
	public int numOfCouldntBeInserted = 0;
	private int searchMaxSize;

	public ElasticsearchClient(String elasticUrl, int indexBulkSize, int indexingThreads, String awsRegion, String elasticUser, String elasticPassword, long maxIndexAge,
			long maxIndexSizeInGB, long maxIndexDocs, int numOfElasticSearchActionsTries, int maxBulkIndexFetches, int searchMaxSize, Map<String, Object> params,
			String diskHandlerStrategy) {

		this.diskHandler = getDiskHandler(diskHandlerStrategy, params);
		if (diskHandler!=null && diskHandler.isCreatedSuccesfully()){
			numOfBulksPersistedToDisk = new AtomicInteger(diskHandler.failedBulksAmount());
		}

		validateProperties(indexBulkSize, indexingThreads, maxIndexAge, maxIndexSizeInGB, maxIndexDocs, numOfElasticSearchActionsTries, numOfElasticSearchActionsTries);
		this.indexBulkSize = indexBulkSize;
		this.searchMaxSize = searchMaxSize;
		this.maxIndexAge = maxIndexAge;
        this.maxIndexSizeInGB = maxIndexSizeInGB;
        this.maxIndexDocs = maxIndexDocs;
		this.numOfElasticSearchActionsTries = numOfElasticSearchActionsTries;
		this.maxBulkIndexFetches = maxBulkIndexFetches;
        this.executorService = Executors.newFixedThreadPool(indexingThreads);
        HttpHost httpHost = HttpHost.create(elasticUrl);
        LOG.info("Connecting to Elasticsearch at url {}", httpHost.toURI());
        RestClientBuilder builder = RestClient.builder(httpHost);
        if (!StringUtils.isEmpty(awsRegion)){
            LOG.info("Trying to connect to AWS Elasticsearch");
            AWS4Signer signer = new AWS4Signer();
            String serviceName = "es";
            signer.setServiceName(serviceName);
            signer.setRegionName(awsRegion);
            HttpRequestInterceptor interceptor = new AWSRequestSigningApacheInterceptor(serviceName, signer, new DefaultAWSCredentialsProviderChain());
            builder.setHttpClientConfigCallback(callback -> callback.addInterceptorLast(interceptor));
        }

        if (!StringUtils.isEmpty(elasticUser)){
            LOG.info("Connection to Elasticsearch using user {}", elasticUser);
            final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(elasticUser, elasticPassword));
            builder.setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
                    .setDefaultCredentialsProvider(credentialsProvider));
        }

        client = new RestHighLevelClient(builder);
    }

	private DiskHandler getDiskHandler(String diskHandlerStrategy, Map<String, Object> params) {
		DiskHandler diskHandler = null;
		if (diskHandlerStrategy != null && !diskHandlerStrategy.toLowerCase().equals("none")){

			diskHandler = ElasticsearchUtil.getDiskHandler(diskHandlerStrategy, params);
			if (!diskHandler.isCreatedSuccesfully()){
				diskHandler = null;
			}
		}
		return diskHandler;
	}

	private void validateProperties(int indexBulkSize, int indexingThreads, long maxIndexAge, long maxIndexSizeInGB, long maxIndexDocs, int numOfMergedTasksTries, int numOfElasticSearchActionsTries) {
		if (indexBulkSize < 1) {
			throw new RuntimeException("Index bulk size property should be larger than 0");
		}
		if (indexingThreads < 1) {
			throw new RuntimeException("Indexing threads property should be larger than 0");
		}
		if (maxIndexAge < 1) {
			throw new RuntimeException("Index max age property should be larger than 0");
		}
		if (maxIndexSizeInGB < 1) {
			throw new RuntimeException("Index max size property should be larger than 0");
		}
		if (maxIndexDocs < 1) {
			throw new RuntimeException("Index max docs property should be larger than 0");
		}
		if (numOfMergedTasksTries < 0) {
			throw new RuntimeException("Max merge tasks retries property should not be below 0");
		}
		if (numOfElasticSearchActionsTries < 0) {
			throw new RuntimeException("Max elasticsearch actions tries property should not be below 0");
		}
	}

	public String getCurrentIndex() {
        return currentIndex;
    }

    public String getOldIndex() {
        return oldIndex;
    }

    public void setCurrentIndex(String currentIndex) {
        this.currentIndex = currentIndex;
    }

    public void setOldIndex(String oldIndex) {
        this.oldIndex = oldIndex;
    }

    Map<String, Task> fetchIndexedTasks(Set<String> tasksToFetch) {
		if (!tasksToFetch.isEmpty()) {
			Map<String, Task> fetchedTasks = getTasksByIds(currentIndex, tasksToFetch, "Fetch previously indexed parent tasks");
			for (String taskId : tasksToFetch) {
				if (!fetchedTasks.containsKey(taskId)){
					LOG.debug("Couldn't find missing parent task with ID {} in Elasticsearch", taskId);
				}
			}
			return fetchedTasks;
		}
		return Collections.emptyMap();
	}

    public Task getTaskById(String taskId){
		Map<String, Task> tasksByIds = getTasksByIds(null, Sets.newHashSet(taskId), "Test");
		return tasksByIds.get(taskId);
	}

    public List<Task> getMultipleTasksByIds(String taskId) {
        IdsQueryBuilder idsQueryBuilder = QueryBuilders.idsQuery().addIds(taskId);
		Map<String, List<Task>> map = runScrollQuery(null, idsQueryBuilder, "Test");
		return map.get(taskId);
    }

    public Map<String, Task> getTasksByIds(String index, Set<String> taskIds, String functionDescription) {
        IdsQueryBuilder idsQueryBuilder = QueryBuilders.idsQuery();
        for (String taskId : taskIds) {
            idsQueryBuilder.addIds(taskId);
        }
		return getSingleTaskByIds(idsQueryBuilder, index, functionDescription);
    }

    private Map<String, Task> getSingleTaskByIds(AbstractQueryBuilder queryBuilder, String index, String functionDescription) {
        Map<String, Task> retMap = Maps.newHashMap();
		Map<String, List<Task>> tasks = runScrollQuery(index, queryBuilder, functionDescription);
		for (Map.Entry<String, List<Task>> entry : tasks.entrySet()) {
			List<Task> tasksList = entry.getValue();
			String taskId = entry.getKey();
			if (tasksList.size() == 1){
				retMap.put(taskId, tasksList.get(0));
			}
			else {
				LOG.warn("Fetched multiple tasks per id [{}] from Elasticsearch. Tasks: {}", taskId, tasksList);
			}
		}
		return retMap;
    }

    void indexMetaDataTasks(String env, String... metadataEvents) {
        String index = createTimbermillAlias(env);

        BulkRequest bulkRequest = new BulkRequest();
        for (String metadataEvent : metadataEvents) {
            IndexRequest indexRequest = new IndexRequest(index, Constants.TYPE).source(metadataEvent, XContentType.JSON);
            bulkRequest.add(indexRequest);
        }
		try {
			runWithRetries(() -> client.bulk(bulkRequest, RequestOptions.DEFAULT) , 1, "Index metadata tasks");
		} catch (MaxRetriesException e) {
			LOG.error("Couldn't index metadata event with events " + Arrays.toString(metadataEvents) + " to elasticsearch cluster.");
		}
	}

    public void close(){
        try {
        	if (isWithPersistence()){
        		diskHandler.close();
			}
            client.close();
        } catch (IOException e) {
            throw new ElasticsearchException(e);
        }
    }

    // return true iff execution of bulk finished successfully
	private boolean sendDbBulkRequest(DbBulkRequest dbBulkRequest, int retryNum){
		boolean isSucceeded = false;
		BulkRequest request = dbBulkRequest.getRequest();
		int numberOfActions = request.numberOfActions();
		LOG.info("Batch of {} index requests sent to Elasticsearch. Batch size: {} bytes", numberOfActions, request.estimatedSizeInBytes());
		if (retryNum > 0){
			LOG.warn("Retry Number {}/{} for requests of size {}", retryNum, numOfElasticSearchActionsTries, request.estimatedSizeInBytes());
		}
		try {
			BulkResponse responses = bulk(dbBulkRequest,RequestOptions.DEFAULT);
			if (responses.hasFailures()) {
				handleBulkRequestFailure(dbBulkRequest,retryNum,responses,responses.buildFailureMessage());
            }
            else{
            	if (dbBulkRequest.getTimesFetched() > 0 ){
					numOfSuccessfullBulksFromDisk+=1;
				}
                LOG.info("Batch of size {}{} finished successfully. Took: {} millis.", numberOfActions, dbBulkRequest.getTimesFetched() > 0 ? ", that was fetched from disk," : "", responses.getTook().millis());
				isSucceeded =  true;
            }
        } catch (Throwable t) {
			handleBulkRequestFailure(dbBulkRequest,retryNum,null,t.getMessage());
        }
		return isSucceeded;
	}

     // wrap bulk method as a not-final method in order that Mockito will able to mock it
	 public BulkResponse bulk(DbBulkRequest request, RequestOptions requestOptions) throws IOException {
		return client.bulk(request.getRequest(), requestOptions);
	}

	public void index(Map<String, Task> tasksMap, String index) {
        Collection<Pair<Future, DbBulkRequest>> futuresRequests = createFuturesRequests(tasksMap, index);

        for (Pair<Future, DbBulkRequest> futureRequest : futuresRequests) {
            try {
				futureRequest.getLeft().get();
            } catch (InterruptedException | ExecutionException e) {
                LOG.error("An error was thrown while indexing a batch", e);
                failedRequests.offer(Pair.of(futureRequest.getRight(), 0));
            }
        }
    }

    void rolloverIndex(String timbermillAlias) {
        RolloverRequest rolloverRequest = new RolloverRequest(timbermillAlias, null);
        rolloverRequest.addMaxIndexAgeCondition(new TimeValue(maxIndexAge, TimeUnit.DAYS));
        rolloverRequest.addMaxIndexSizeCondition(new ByteSizeValue(maxIndexSizeInGB, ByteSizeUnit.GB));
        rolloverRequest.addMaxIndexDocsCondition(maxIndexDocs);
		try {
			RolloverResponse rolloverResponse = (RolloverResponse) runWithRetries(() -> client.indices().rollover(rolloverRequest, RequestOptions.DEFAULT), 1, "Rollover alias " + timbermillAlias);
			if (rolloverResponse.isRolledOver()){
				LOG.info("Index {} rolled over, new index is [{}]", rolloverResponse.getOldIndex(), rolloverResponse.getNewIndex());

				currentIndex = rolloverResponse.getNewIndex();
				oldIndex = rolloverResponse.getOldIndex();

				moveTasksFromOldToNewIndex();
			}
		} catch (MaxRetriesException e) {
			LOG.error("Could not rollovered index " + timbermillAlias);
		}
    }

	private void moveTasksFromOldToNewIndex() {
		Map<String, Task> previousIndexPartialTasks = getIndexPartialTasks(oldIndex, "Move partials tasks from old to new index");
		indexAndDeleteTasks(previousIndexPartialTasks);
    }

	public void indexAndDeleteTasks(Map<String, Task> previousIndexPartialTasks) {
		if (!previousIndexPartialTasks.isEmpty()) {
			LOG.info("Starting migration between old index [{}] and new index [{}]", oldIndex, currentIndex);
			index(previousIndexPartialTasks, currentIndex);
			deleteTasksFromIndex(previousIndexPartialTasks.keySet(), oldIndex);
			LOG.info("Successfully migrated {} tasks to new index [{}]", previousIndexPartialTasks.size(), currentIndex);
		}
    }

	private Collection<Pair<Future, DbBulkRequest>> createFuturesRequests(Map<String, Task> tasksMap, String index) {
		Collection<UpdateRequest> requests = createUpdateRequests(tasksMap, index);
		BulkRequest request = new BulkRequest();
		Collection<Pair<Future, DbBulkRequest>> futures = new ArrayList<>();
        for (UpdateRequest updateRequest : requests) {
            request.add(updateRequest);

            if (request.estimatedSizeInBytes() > indexBulkSize) {
				DbBulkRequest dbBulkRequest = new DbBulkRequest(request);
				addRequestToFutures(dbBulkRequest, futures);
                request = new BulkRequest();
            }
        }
        if (!request.requests().isEmpty()) {
			DbBulkRequest dbBulkRequest = new DbBulkRequest(request);
			addRequestToFutures(dbBulkRequest, futures);
        }
		return futures;
    }

	private void addRequestToFutures(DbBulkRequest request, Collection<Pair<Future, DbBulkRequest>> futures) {
        Future<?> future = executorService.submit(() -> sendDbBulkRequest(request, 0));
        futures.add(Pair.of(future, request));
    }

    private Collection<UpdateRequest> createUpdateRequests(Map<String, Task> tasksMap, String index) {
        Collection<UpdateRequest> requests = new ArrayList<>();
        for (Map.Entry<String, Task> taskEntry : tasksMap.entrySet()) {
            Task task = taskEntry.getValue();

            try {
				UpdateRequest updateRequest = task.getUpdateRequest(index, taskEntry.getKey());
				requests.add(updateRequest);
			} catch (Throwable t){
				LOG.error("Failed while creating update request. task:" + task.toString(), t);
			}
        }
        return requests;
    }

    public void bootstrapElasticsearch(int numberOfShards, int numberOfReplicas, int maxTotalFields) {
		try {
			putIndexTemplate(numberOfShards, numberOfReplicas, maxTotalFields);
			puStoredScript();
		} catch (MaxRetriesException e) {
			throw new RuntimeException(e);
		}
	}

	private void puStoredScript() throws MaxRetriesException {
		PutStoredScriptRequest request = new PutStoredScriptRequest();
		request.id(Constants.TIMBERMILL_SCRIPT);
		String content = "{\n"
				+ "  \"script\": {\n"
				+ "    \"lang\": \"painless\",\n"
				+ "    \"source\": \"" + ElasticsearchUtil.SCRIPT
				+ "  }\n"
				+ "}";
		request.content(new BytesArray(content), XContentType.JSON);
		runWithRetries(() -> client.putScript(request, RequestOptions.DEFAULT), 1, "Put Timbermill stored script");
	}

	private void putIndexTemplate(int numberOfShards, int numberOfReplicas, int maxTotalFields) throws MaxRetriesException {
        PutIndexTemplateRequest request = new PutIndexTemplateRequest("timbermill2-template");

        request.patterns(Lists.newArrayList(TIMBERMILL_INDEX_PREFIX + "*"));
        request.settings(Settings.builder().put("index.mapping.total_fields.limit", maxTotalFields)
				.put("number_of_shards", numberOfShards)
				.put("number_of_replicas", numberOfReplicas));
		request.mapping(ElasticsearchUtil.MAPPING, XContentType.JSON);
		runWithRetries(() -> client.indices().putTemplate(request, RequestOptions.DEFAULT), 1, "Put Timbermill Index Template");
    }

    String createTimbermillAlias(String env) {
        String timbermillAlias = ElasticsearchUtil.getTimbermillIndexAlias(env);
		String initialIndex = getInitialIndex(timbermillAlias);
		GetAliasesRequest requestWithAlias = new GetAliasesRequest(timbermillAlias);
		try {
			GetAliasesResponse response = (GetAliasesResponse) runWithRetries(() -> client.indices().getAlias(requestWithAlias, RequestOptions.DEFAULT), 1, "Create Timbermill Alias for env " + env);
			boolean exists = !response.getAliases().isEmpty();
			if (!exists) {
				CreateIndexRequest request = new CreateIndexRequest(initialIndex);
				Alias alias = new Alias(timbermillAlias);
				request.alias(alias);
				runWithRetries(() -> client.indices().create(request, RequestOptions.DEFAULT), 1, "Create index alias " + timbermillAlias + " for index " + initialIndex);
				currentIndex = initialIndex;
			}
		} catch (MaxRetriesException e){
			LOG.error("Failed creating Timbermill Alias " + timbermillAlias + ", going to use index " + initialIndex);
		}
		return timbermillAlias;
	}

    private String getInitialIndex(String timbermillAlias) {
        String initialSerial = ElasticsearchUtil.getIndexSerial(1);
        return timbermillAlias + ElasticsearchUtil.INDEX_DELIMITER + initialSerial;
    }

	public Map<String, Task> getIndexPartialTasks(String index, String functionDescription) {
		TermsQueryBuilder query = new TermsQueryBuilder(STATUS, TaskStatus.PARTIAL_ERROR, TaskStatus.PARTIAL_INFO_ONLY, TaskStatus.PARTIAL_SUCCESS, TaskStatus.UNTERMINATED);
        return getSingleTaskByIds(query, index, functionDescription);
    }

    private Map<String, List<Task>> runScrollQuery(String index, QueryBuilder query, String functionDescription){
        SearchRequest searchRequest;
        if (index == null){
            searchRequest = new SearchRequest();
        }
        else {
            searchRequest = new SearchRequest(index);
        }
        Scroll scroll = new Scroll(TimeValue.timeValueMinutes(1L));
        searchRequest.scroll(scroll);
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(query);
        searchSourceBuilder.size(searchMaxSize);
        searchRequest.source(searchSourceBuilder);

		Map<String, List<Task>> tasks = Maps.newHashMap();
		try {
			SearchResponse searchResponse = (SearchResponse) runWithRetries(() -> client.search(searchRequest, RequestOptions.DEFAULT), 1, "Initial search for " + functionDescription);
			String scrollId = searchResponse.getScrollId();
			SearchHit[] searchHits = searchResponse.getHits().getHits();

			while (searchHits != null && searchHits.length > 0) {
				addHitsToMap(searchHits, tasks);
				SearchScrollRequest scrollRequest = new SearchScrollRequest(scrollId);
				scrollRequest.scroll(scroll);
				searchResponse = (SearchResponse) runWithRetries(() -> client.scroll(scrollRequest, RequestOptions.DEFAULT), 1, "Scroll search for scroll id: " + scrollId + " for " + functionDescription);
				scrollId = searchResponse.getScrollId();
				searchHits = searchResponse.getHits().getHits();
			}
			ClearScrollRequest clearScrollRequest = new ClearScrollRequest();
			clearScrollRequest.addScrollId(scrollId);
			try {
				ClearScrollResponse clearScrollResponse = client.clearScroll(clearScrollRequest, RequestOptions.DEFAULT);
				boolean succeeded = clearScrollResponse.isSucceeded();
				if (!succeeded) {
					LOG.error("Couldn't clear scroll id [{}] for fetching partial tasks in index [{}]", scrollId, index);
				}
			} catch (Throwable e) {
				LOG.error("Couldn't clear scroll id [{}] for fetching partial tasks in index [{}]", scrollId, index);
			}
		}
		catch (MaxRetriesException e) {
			LOG.error("Error while running search query.", e);
		}

        return tasks;
    }

	private ActionResponse runWithRetries(Callable<ActionResponse> callable, int tryNum, String functionDescription) throws MaxRetriesException {
		if (tryNum > 1) {
			LOG.info("Retry # {}/{} for [{}]", tryNum, numOfElasticSearchActionsTries, functionDescription);
		}
		try {
			return callable.call();
		} catch (Exception e) {
			if (tryNum <= numOfElasticSearchActionsTries){
				double sleep = Math.pow(2, tryNum);
				LOG.warn("Retry # " + tryNum + "/" + numOfElasticSearchActionsTries + " for [" + functionDescription + "] failed. Going to sleep " + sleep + " seconds.", e);
				try {
					Thread.sleep((long) (sleep * 1000)); //Exponential backoff
				} catch (InterruptedException ignored) {
				}
				return runWithRetries(callable, tryNum + 1, functionDescription);
			}
			else{
				LOG.error("Reached maximum retries (" + numOfElasticSearchActionsTries + ") attempts for [" + functionDescription + "]", e);
				throw new MaxRetriesException();
			}
		}
	}

	private void addHitsToMap(SearchHit[] searchHits, Map<String, List<Task>> tasks) {
        for (SearchHit searchHit : searchHits) {
            String sourceAsString = searchHit.getSourceAsString();
            Task task = Constants.GSON.fromJson(sourceAsString, Task.class);
            fixMetrics(task);
            String id = searchHit.getId();
            if (!tasks.containsKey(id)){
                tasks.put(id, Lists.newArrayList(task));
            }
            else{
                tasks.get(id).add(task);
            }
        }
    }

    private void fixMetrics(Task task) {
        Map<String, Number> metric = task.getMetric();
        Map<String, Number> newMetrics = new HashMap<>();
        for (Map.Entry<String, Number> entry : metric.entrySet()) {
            Number value = entry.getValue();
            if (value instanceof LazilyParsedNumber){
                if (value.toString().contains(".")){
                    Double newValue = value.doubleValue();
                    newMetrics.put(entry.getKey(), newValue);
                }
                else{
                    Long newValue = value.longValue();
                    newMetrics.put(entry.getKey(), newValue);
                }
            }
        }
        metric.putAll(newMetrics);
    }

    private void deleteTasksFromIndex(Set<String> idsSet, String index) {
        LOG.info("Deleting tasks from  index [{}]", index);


        List<String> ids = new ArrayList<>();
		idsSet.forEach(id -> ids.add('"' + id + '"'));
		String query = "{\n"
				+ "    \"query\": {\n"
				+ "        \"ids\" : {\n"
				+ "            \"values\" : " + ids.toString() + " \n"
				+ "        }\n"
				+ "    }\n"
				+ "}";
		deleteByQuery(index, query);
    }

    public void deleteExpiredTasks() {
        LOG.info("About to delete expired tasks");
		String query = "{\n"
				+ "  \"query\": {\n"
				+ "    \"bool\": {\n"
				+ "      \"must\": [\n"
				+ "    {\n"
				+ "    \"range\": {\n"
				+ "      \"" +TTL_FIELD + "\": {\n"
				+ "        \"lte\": \"now\"\n"
				+ "      }\n"
				+ "    }\n"
				+ "    }\n"
				+ "      ]\n"
				+ "    }\n"
				+ "  }\n"
				+ "}";
		deleteByQuery("*", query);
    }

    private void deleteByQuery(String index, String query) {
		Request request = new Request("POST", "/" + index + "/_delete_by_query");
		request.addParameter("conflicts","proceed");
		request.addParameter("wait_for_completion", "false");
		request.setJsonEntity(query);
		try {
			Response response = client.getLowLevelClient().performRequest(request);
			InputStream content = response.getEntity().getContent();
			String json = IOUtils.toString(content);
			JsonObject asJsonObject = new JsonParser().parse(json).getAsJsonObject();
			JsonElement task = asJsonObject.get("task");
			if (task != null) {
				LOG.info("Task id {} for deletion by query {}", task, query);
			} else {
				LOG.error("Delete by query didn't return taskId. Response was {}", json);
			}
		} catch (Exception e) {
            LOG.warn("Could not perform deletion.", e);
        }
	}

    long countByName(String name, String env) throws IOException {
        CountRequest countRequest = new CountRequest();
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(QueryBuilders.boolQuery().must(QueryBuilders.matchQuery("name", name)).must(QueryBuilders.matchQuery("env", env)));
        countRequest.source(searchSourceBuilder);
        CountResponse countResponse = client.count(countRequest, RequestOptions.DEFAULT);
        return countResponse.getCount();
    }

	public void retryFailedRequestsFromMemory() {
		if (!failedRequests.isEmpty()) {
			LOG.info("------------------ Failed Requests From Memory Retry Start ------------------");
			List<Pair<DbBulkRequest, Integer>> list = memoryFailedRequestsAsList();
			for (Pair<DbBulkRequest, Integer> entry : list) {
				DbBulkRequest dbBulkRequest = entry.getKey();
				Integer retryNum = entry.getValue();
				sendDbBulkRequest(dbBulkRequest, retryNum + 1);
			}
			LOG.info("------------------ Failed Requests From Memory Retry End ------------------");
		}
	}

	public boolean hasFailedRequests(){
		return failedRequests.size()>0;
	}

	 void handleBulkRequestFailure(DbBulkRequest dbBulkRequest, int retryNum, BulkResponse responses ,String failureMessage){
		if (retryNum >= numOfElasticSearchActionsTries){
			LOG.error("Reached maximum retries ({}) attempt to index. Failure message: {}", numOfElasticSearchActionsTries, failureMessage);
			if (isWithPersistence()) {
				if (dbBulkRequest.getTimesFetched() < maxBulkIndexFetches){
					DbBulkRequest updatedDbBulkRequest = extractFailedRequestsFromBulk(dbBulkRequest, responses);
					try {
						diskHandler.persistToDisk(updatedDbBulkRequest);
						if (dbBulkRequest.getTimesFetched()==0){
							numOfBulksPersistedToDisk.incrementAndGet();
						}
					} catch (MaximunInsertTriesException e){
						LOG.error("Tasks of failed bulk will not be indexed because couldn't be persisted to disk for the maximum times ({}).", e.getMaximumTriesNumber());
						numOfCouldntBeInserted+=1;
					}
				}
				else{
					LOG.error("Tasks of failed bulk {} will not be indexed because it was fetched maximum times ({}).",dbBulkRequest.getId(),maxBulkIndexFetches);
					numOfFetchedMaxTimes+=1;
				}
			}
			else {
				LOG.error("Tasks of failed bulk will not be indexed because it was fetched maximum times ({}).",maxBulkIndexFetches);
			}
		}
		else {
			LOG.warn("Failed while trying to bulk index tasks, failure message: {}. Going to retry.", failureMessage);
			DbBulkRequest updatedDbBulkRequest = extractFailedRequestsFromBulk(dbBulkRequest,responses);
			failedRequests.offer(Pair.of(updatedDbBulkRequest, retryNum));
		}
	}

	private DbBulkRequest extractFailedRequestsFromBulk(DbBulkRequest dbBulkRequest, BulkResponse bulkResponses) {
		BulkRequest bulkRequest = dbBulkRequest.getRequest();
		int numOfRequests = bulkRequest.numberOfActions();

		if (bulkResponses != null){
			List<DocWriteRequest<?>> requests = dbBulkRequest.getRequest().requests();
			BulkItemResponse[] responses = bulkResponses.getItems();

			BulkRequest failedRequestsBulk = new BulkRequest();
			int length = requests.size();
			for (int i = 0 ; i < length; i++){
				if (responses[i].isFailed()){
					failedRequestsBulk.add(requests.get(i));
				}
			}
			LOG.info("Failed bulk remained with {} failed requests of {}.",requests.size(),numOfRequests);

			dbBulkRequest = new DbBulkRequest(failedRequestsBulk).setId(dbBulkRequest.getId())
					.setTimesFetched(dbBulkRequest.getTimesFetched()).setInsertTime(dbBulkRequest.getInsertTime());
		} else {
			// An exception was thrown while bulking, then all requests failed. No change is needed in the bulk request.
			LOG.info("All {} requests of bulk failed.", numOfRequests);
		}
		return dbBulkRequest;
	}

	public boolean isWithPersistence() {
		return diskHandler != null;
	}

	public boolean retryFailedRequestsFromDisk() {
		boolean keepRunning = false;
		LOG.info("Persistence Status: {} persisted to disk, {} re-processed successfully, {} failed after max retries from db, {} couldn't be inserted to db", numOfBulksPersistedToDisk, numOfSuccessfullBulksFromDisk,
				numOfFetchedMaxTimes, numOfCouldntBeInserted);
		if (diskHandler.hasFailedBulks()) {
			keepRunning = true;
			int successBulks = 0;
			LOG.info("------------------ Retry Failed-Requests From Disk Start ------------------");
			List<DbBulkRequest> failedRequestsFromDisk = diskHandler.fetchAndDeleteFailedBulks();
			if (failedRequestsFromDisk.size() == 0) {
				keepRunning = false;
			}
			for (DbBulkRequest dbBulkRequest : failedRequestsFromDisk) {
				if (!sendDbBulkRequest(dbBulkRequest, 0)) {
					keepRunning = false;
				}
				else {
					successBulks+=1;
				}
			}
			LOG.info("------------------ Retry Failed-Requests From Disk End ({}/{} fetched bulks re-processed successfully) ------------------",successBulks,failedRequestsFromDisk.size());
		} else {
			LOG.info("There are no failed bulks to fetch from disk");
		}
		return keepRunning;
	}

	List<Pair<DbBulkRequest, Integer>> memoryFailedRequestsAsList() {
		List<Pair<DbBulkRequest, Integer>> list = Lists.newLinkedList();
		failedRequests.drainTo(list);
		return list;
	}
}

