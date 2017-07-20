package com.vip.saturn.job.sharding;

import com.vip.saturn.job.integrate.service.ReportAlarmService;
import com.vip.saturn.job.sharding.listener.*;
import com.vip.saturn.job.sharding.node.SaturnExecutorsNode;
import com.vip.saturn.job.sharding.service.AddJobListenersService;
import com.vip.saturn.job.sharding.service.ExecutorCleanService;
import com.vip.saturn.job.sharding.service.NamespaceShardingService;
import com.vip.saturn.job.sharding.service.ShardingTreeCacheService;
import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author hebelala
 *
 */
public class NamespaceShardingManager {
	static Logger log = LoggerFactory.getLogger(NamespaceShardingManager.class);

	private NamespaceShardingService namespaceShardingService;
	private ExecutorCleanService executorCleanService;
	private CuratorFramework curatorFramework;
	private AddJobListenersService addJobListenersService;

	private String namespace;
	private String zkAddr;

	private ShardingTreeCacheService shardingTreeCacheService;
	private NamespaceShardingConnectionListener namespaceShardingConnectionListener;
	
	public NamespaceShardingManager(CuratorFramework curatorFramework, String namespace, String hostValue, ReportAlarmService reportAlarmService) {
		this.curatorFramework = curatorFramework;
		this.namespace = namespace;
		this.shardingTreeCacheService = new ShardingTreeCacheService(namespace, curatorFramework);
		this.namespaceShardingService = new NamespaceShardingService(curatorFramework, hostValue, reportAlarmService);
		this.executorCleanService = new ExecutorCleanService(curatorFramework);
		this.addJobListenersService = new AddJobListenersService(namespace, curatorFramework, namespaceShardingService, shardingTreeCacheService);
	}

    public String getZkAddr() {
		return zkAddr;
	}
    
	public void setZkAddr(String zkAddr) {
		this.zkAddr = zkAddr;
	}



	private void start0() throws Exception {
		shardingTreeCacheService.start();
		// create ephemeral node $SaturnExecutors/leader/host & $Jobs.
		namespaceShardingService.leaderElection();
		addJobListenersService.addExistJobPathListener();
		addOnlineOfflineListener();
		addExecutorShardingListener();
		addLeaderElectionListener();
		addNewOrRemoveJobListener();
	}

	private void addConnectionLostListener() {
		namespaceShardingConnectionListener = new NamespaceShardingConnectionListener("connectionListener-for-NamespaceSharding-" + namespace);
		curatorFramework.getConnectionStateListenable().addListener(namespaceShardingConnectionListener);
	}

	/**
	 * leadership election, add listeners
	 */
	public void start() throws Exception {
		start0();
		addConnectionLostListener();
	}

	/**
	 *  watch 1-level-depth of the children of /$Jobs
	 */
	private void addNewOrRemoveJobListener() throws Exception {
		String path = SaturnExecutorsNode.$JOBSNODE_PATH;
		int depth = 1;
		createNodePathIfNotExists(path);
		shardingTreeCacheService.addTreeCacheIfAbsent(path, depth);
		shardingTreeCacheService.addTreeCacheListenerIfAbsent(path, depth, new AddOrRemoveJobListener(addJobListenersService));
	}


	/**
	 *  watch 2-level-depth of the children of /$SaturnExecutors/executors
	 */
	private void addOnlineOfflineListener() throws Exception {
		String path = SaturnExecutorsNode.EXECUTORSNODE_PATH;
		int depth = 2;
		createNodePathIfNotExists(path);
		shardingTreeCacheService.addTreeCacheIfAbsent(path, depth);
		shardingTreeCacheService.addTreeCacheListenerIfAbsent(path, depth, new ExecutorOnlineOfflineTriggerShardingListener(namespaceShardingService, executorCleanService));
	}
	
	/**
	 *  watch 1-level-depth of the children of /$SaturnExecutors/sharding
	 */
	private void addExecutorShardingListener() throws Exception {
		String path = SaturnExecutorsNode.SHARDINGNODE_PATH;
		int depth = 1;
		createNodePathIfNotExists(path);
		shardingTreeCacheService.addTreeCacheIfAbsent(path, depth);
		shardingTreeCacheService.addTreeCacheListenerIfAbsent(path, depth, new SaturnExecutorsShardingTriggerShardingListener(namespaceShardingService));
	}

	/**
	 * watch 1-level-depth of the children of /$SaturnExecutors/leader  
	 */
	private void addLeaderElectionListener() throws Exception {
		String path = SaturnExecutorsNode.LEADERNODE_PATH;
		int depth = 1;
		createNodePathIfNotExists(path);
		shardingTreeCacheService.addTreeCacheIfAbsent(path, depth);
		shardingTreeCacheService.addTreeCacheListenerIfAbsent(path, depth, new LeadershipElectionListener(namespaceShardingService));
	}

	private void createNodePathIfNotExists(String path) throws InterruptedException {
		try {
			if (curatorFramework.checkExists().forPath(path) == null) {
				curatorFramework.create().creatingParentsIfNeeded().forPath(path);
			}
		} catch (InterruptedException e) {
			throw e;
		} catch (Exception e) { //NOSONAR
		}
	}

	/**
	 * close listeners, delete leadership
	 */
	public void stop() {
		try {
			if(namespaceShardingConnectionListener != null) {
				curatorFramework.getConnectionStateListenable().removeListener(namespaceShardingConnectionListener);
				namespaceShardingConnectionListener.shutdownNowUntilTerminated();
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		try {
			shardingTreeCacheService.shutdown();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		try {
			namespaceShardingService.shutdown();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	public void stopWithCurator() {
		stop();
		curatorFramework.close();
	}

	class NamespaceShardingConnectionListener extends  AbstractConnectionListener {

		public NamespaceShardingConnectionListener(String threadName) {
			super(threadName);
		}

		@Override
		public void stop() {
			try {
				shardingTreeCacheService.shutdown();
				namespaceShardingService.shutdown();
			} catch (InterruptedException e) {
				log.info("stop interrupted");
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				log.error("stop error", e);
			}
		}

		@Override
		public void restart() {
			try {
				start0();
			} catch (InterruptedException e) {
				log.info("restart interrupted");
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				log.error("restart error", e);
			}
		}
	}

}
