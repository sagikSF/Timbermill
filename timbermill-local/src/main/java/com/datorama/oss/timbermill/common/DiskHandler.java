package com.datorama.oss.timbermill.common;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.datorama.oss.timbermill.common.exceptions.MaximunInsertTriesException;

public interface DiskHandler {
	List<DbBulkRequest> fetchAndDeleteFailedBulks();

	void persistToDisk(DbBulkRequest dbBulkRequest) throws MaximunInsertTriesException;

	boolean hasFailedBulks();

	boolean isCreatedSuccesfully();

	static Map<String, Object> buildDiskHandlerParams(int maxFetchedBulksInOneTime, int maxInsertTries, String locationInDisk) {
		Map<String, Object> diskHandlerParams = new HashMap<>();
		diskHandlerParams.put(SQLJetDiskHandler.MAX_FETCHED_BULKS_IN_ONE_TIME, maxFetchedBulksInOneTime);
		diskHandlerParams.put(SQLJetDiskHandler.MAX_INSERT_TRIES, maxInsertTries);
		diskHandlerParams.put(SQLJetDiskHandler.LOCATION_IN_DISK, locationInDisk);
		return diskHandlerParams;
	}

	void close();
}

