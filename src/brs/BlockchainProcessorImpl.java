package brs;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;

import brs.at.AT_Block;
import brs.at.AT_Controller;
import brs.at.AT_Exception;
import brs.crypto.Crypto;
import brs.db.BlockDb;
import brs.db.DerivedTable;
import brs.peer.Peer;
import brs.peer.Peers;
import brs.util.Convert;
import brs.util.DownloadCacheImpl;
import brs.util.FilteringIterator;
import brs.util.JSON;
import brs.util.Listener;
import brs.util.Listeners;
import brs.util.ThreadPool;

final class BlockchainProcessorImpl implements BlockchainProcessor {

  private static final Logger logger = LoggerFactory.getLogger(BlockchainProcessorImpl.class);
  private final BlockDb blockDb = Burst.getDbs().getBlockDb();
  private final TransactionDb transactionDb = Burst.getDbs().getTransactionDb();
  public static final DownloadCacheImpl DownloadCache = new DownloadCacheImpl();

  public static final int MAX_TIMESTAMP_DIFFERENCE = 15;
  private static boolean oclVerify = Burst.getBooleanProperty("brs.oclVerify");
  public static final int oclThreshold = Burst.getIntProperty("brs.oclThreshold") == 0 ? 50 : Burst.getIntProperty("brs.oclThreshold");
  public static final int oclWaitThreshold = Burst.getIntProperty("brs.oclWaitThreshold") == 0 ? 2000 : Burst.getIntProperty("brs.oclWaitThreshold");

  private static final Semaphore gpuUsage = new Semaphore(2);
  /** If we are more than this many blocks behind we can engage "catch-up"-mode if enabled */

  private static final BlockchainProcessorImpl instance = new BlockchainProcessorImpl();

  static BlockchainProcessorImpl getInstance() {
    return instance;
  }

  private final BlockchainImpl blockchain = BlockchainImpl.getInstance();

  private final List<DerivedTable> derivedTables = new CopyOnWriteArrayList<>();
  private final boolean trimDerivedTables = Burst.getBooleanProperty("brs.trimDerivedTables");
  private volatile int lastTrimHeight;

  private final Listeners<Block, Event> blockListeners = new Listeners<>();
  private volatile Peer lastBlockchainFeeder;
  private volatile int lastBlockchainFeederHeight;
  private volatile boolean getMoreBlocks = true;

  private volatile boolean isScanning;
  private volatile boolean forceScan = Burst.getBooleanProperty("brs.forceScan");
  private volatile boolean validateAtScan = Burst.getBooleanProperty("brs.forceValidate");

  private final Runnable debugInfoThread = new Runnable() {
    @Override
    public void run() {
      logger.info("Unverified blocks: " + DownloadCache.getUnverifiedSize());
      logger.info("Blocks in cache: " + DownloadCache.size());
      logger.info("Bytes in cache: " + DownloadCache.getBlockCacheSize());
    }
  };

  public static final void setOclVerify(Boolean b) {
    oclVerify = b;
  }

  public static final Boolean getOclVerify() {
    return oclVerify;
  }
  /*
  private static void addCollectionGauge (MetricRegistry metrics, final Collection c, String name) {
    metrics.register(MetricRegistry.name(BlockchainProcessorImpl.class, name, "size"),
                     (Gauge<Integer>) () -> c.size());
  }
  
  static {
    addCollectionGauge(Burst.metrics, blockCache.keySet(), "BlockCache");
    addCollectionGauge(Burst.metrics, unverified, "Unverified");
    Burst.metrics.register(MetricRegistry.name(BlockchainProcessorImpl.class, "BlockCacheSize", "size"),
                         (Gauge<Integer>) () -> blockCacheSize);
  }
  */
  private final Timer pocTimer = Burst.metrics.timer(MetricRegistry.name(BlockchainImpl.class, "pocVerification"));

  private final Runnable pocVerificationThread = new Runnable() {
    @Override
    public void run() {
      for (;;) {
        final Timer.Context context = pocTimer.time();
        try {
          if (oclVerify) {
            boolean gpuAcquired = false;
            try {
              List<BlockImpl> blocks = new LinkedList<>();
              synchronized (DownloadCache) {
                if (DownloadCache.getUnverifiedSize() == 0) {
                  return;
                }
                int verifiedCached = DownloadCache.size() - DownloadCache.getUnverifiedSize();
                if (verifiedCached >= oclWaitThreshold && DownloadCache.getUnverifiedSize() < OCLPoC.getMaxItems() / 2) {
                  return;
                }
                if (DownloadCache.getUnverifiedSize() < oclThreshold) {
                  Long blockId = DownloadCache.GetUnverifiedBlockId(0);
                  DownloadCache.removeUnverified(blockId);
                  blocks.add(DownloadCache.GetBlock(blockId));
                } else {
                  if (!gpuUsage.tryAcquire()) {
                    logger.debug("already max locked");
                    return;
                  }
                  gpuAcquired = true;
                  while (DownloadCache.getUnverifiedSize() > 0 && blocks.size() < OCLPoC.getMaxItems()) {
                    Long blockId = DownloadCache.GetUnverifiedBlockId(0);
                    DownloadCache.removeUnverified(blockId);
                    blocks.add(DownloadCache.GetBlock(blockId));
                  }
                }
              } // end synchronized
              try {
                if (blocks.size() > 1) {
                  OCLPoC.validatePoC(blocks);
                } else {
                  blocks.get(0).preVerify();
                }
              } catch (OCLPoC.PreValidateFailException e) {
                logger.info(e.toString(), e);
                blacklistClean(e.getBlock(), e);
              } catch (BlockNotAcceptedException e) {
                logger.info(e.toString(), e);
                blacklistClean(blocks.get(0), e);
              }
            } finally {
              if (gpuAcquired) {
                gpuUsage.release();
              }
            }
          } else {
            BlockImpl block;
            synchronized (DownloadCache) {
              if (DownloadCache.getUnverifiedSize() == 0)
                return;
              Long blockId = DownloadCache.GetUnverifiedBlockId(0);
              DownloadCache.removeUnverified(blockId);
              block = DownloadCache.GetBlock(blockId);
            }
            try {
              block.preVerify();
            } catch (BlockchainProcessor.BlockNotAcceptedException e) {
              blacklistClean(block, e);
            }
          }
        } finally {
          context.stop();
        }
        try {
          Thread.sleep(10);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
    }
  };
  private final Timer blockImportTimer = Burst.metrics.timer(MetricRegistry.name(BlockchainImpl.class, "blockImport"));
  private final Runnable blockImporterThread = new Runnable() {
    @Override
    public void run() {
      try {
        while (true) {
          synchronized (blockchain) {
            for (;;) {
              final Timer.Context context = blockImportTimer.time();
              try {
                Long lastId = blockchain.getLastBlock().getId();
                BlockImpl currentBlock;
                synchronized (DownloadCache) {
                  currentBlock = DownloadCache.GetNextBlock(lastId); /* we should fetch first block in cache */
                  if (currentBlock == null) {
                    break;
                  }
                }
                try {
                  if (!currentBlock.isVerified()) {
                    currentBlock.preVerify();
                  }
                  pushBlock(currentBlock);
                } catch (BlockNotAcceptedException e) {
                  logger.error("Block not accepted", e);
                  blacklistClean(currentBlock, e);
                  break;
                }
                // Remove processed block.
                synchronized (DownloadCache) {
                  DownloadCache.RemoveBlock(currentBlock);
                }
              } finally {
                context.stop();
              }
            }
          }

          //threadsleep?
          try {
            Thread.sleep(10);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }

        }
      } catch (Throwable exception) {
        logger.error("Uncaught exception in blockImporterThread", exception);
      }
    }
  };

  private void blacklistClean(BlockImpl block, Exception e) {
    logger.debug("Blacklisting peer and cleaning cache queue");
    if (block == null) {
      return;
    }
    Peer peer = block.getPeer();
    if (peer != null) {
      peer.blacklist(e);
    }
    synchronized (DownloadCache) {
      DownloadCache.ResetCache();
    }
    logger.debug("Blacklisted peer and cleaned queue");
  }

  private final Runnable getMoreBlocksThread = new Runnable() {
    private final JSONStreamAware getCumulativeDifficultyRequest;

    {
      JSONObject request = new JSONObject();
      request.put("requestType", "getCumulativeDifficulty");
      getCumulativeDifficultyRequest = JSON.prepareRequest(request);
    }

    private boolean peerHasMore;

    @Override
    public void run() {
      while (true) {
        try {
          try {
            if (!getMoreBlocks) {
              //  logger.debug("just exit");
              return;
            }
            if (DownloadCache.IsFull()) {
              //    logger.debug("blockcache full.");
              return;
            }
            peerHasMore = true;
            Peer peer = Peers.getAnyPeer(Peer.State.CONNECTED, true);
            if (peer == null) {
              logger.debug("No peer connected.");
              return;
            }
            JSONObject response = peer.send(getCumulativeDifficultyRequest);
            if (response == null) {
              //    logger.debug("Peer Response is null");
              return;
            }
            if (response.get("blockchainHeight") != null) {
              lastBlockchainFeeder = peer;
              lastBlockchainFeederHeight = ((Long) response.get("blockchainHeight")).intValue();
            } else {
              logger.debug("Peer has no chainheight");
              return;
            }

            /* Cache now contains Cumulative Difficulty */

            BigInteger curCumulativeDifficulty = DownloadCache.getCumulativeDifficulty();
            String peerCumulativeDifficulty = (String) response.get("cumulativeDifficulty");
            if (peerCumulativeDifficulty == null) {
              logger.debug("Peer CumulativeDifficulty is null");
              return;
            }
            BigInteger betterCumulativeDifficulty = new BigInteger(peerCumulativeDifficulty);
            if (betterCumulativeDifficulty.compareTo(curCumulativeDifficulty) < 0) {
              //    logger.debug("Peer has lower chain or is on bad fork.");
              return;
            }
            if (betterCumulativeDifficulty.equals(curCumulativeDifficulty)) {
              //         logger.debug("We are on same height.");
              return;
            }

            long commonBlockId = Genesis.GENESIS_BLOCK_ID;
            long cacheLastBlockId = DownloadCache.getLastBlockId();

            // Now we will find the highest common block between ourself and our peer
            if (cacheLastBlockId != Genesis.GENESIS_BLOCK_ID) {
              commonBlockId = getCommonMilestoneBlockId(peer);
              if (commonBlockId == 0 || !peerHasMore) {
                logger.debug("We could not get a common milestone block from peer.");
                return;
              }
            }

            /*
             * if we did not get the last block in chain as common block we will be downloading a fork. 
             * however if it is to far off we cannot process it anyway. 
             * CanBeFork will check where in chain this common block is fitting and return true if it is worth to continue.
             */

            boolean saveInCache = true;
            if (commonBlockId != cacheLastBlockId) {
              if (DownloadCache.CanBeFork(commonBlockId)) {
                // the fork is not that old. Lets see if we can get more precise.
                commonBlockId = getCommonBlockId(peer, commonBlockId);
                if (commonBlockId == 0 || !peerHasMore) {
                  logger.debug("Trying to get a more precise common block resulted in an error.");
                  return;
                }
                saveInCache = false;
              } else {
                logger.warn("Our peer want to feed us a fork that is more than " + Constants.MAX_ROLLBACK + " blocks old.");
                return;
              }
            }

            List<BlockImpl> forkBlocks = new ArrayList<>();
            JSONArray nextBlocks = getNextBlocks(peer, commonBlockId);
            if (nextBlocks == null || nextBlocks.isEmpty()) {
              logger.debug("Peer did not feed us any blocks");
              return;
            }

            // download blocks from peer
            int chainHeight = DownloadCache.getChainHeight();
            BlockImpl lastBlock = DownloadCache.GetBlock(commonBlockId);

            // loop blocks and make sure they fit in chain
            synchronized (DownloadCache) {
              BlockImpl block;
              JSONObject blockData;
              for (Object o : nextBlocks) {
                blockData = (JSONObject) o;
                try {
                  block = BlockImpl.parseBlock(blockData);
                  if (block == null) {
                    logger.debug("Unable to process downloaded blocks.");
                    return;
                  }
                  // Make sure it maps back to chain
                  if (lastBlock.getId() != block.getPreviousBlockId()) {
                    logger.debug("Discarding downloaded data. Last downloaded blocks is rubbish");
                    return;
                  }
                  // set height and cumulative difficulty to block
                  block.setHeight(lastBlock.getHeight() + 1);
                  block.setPeer(peer);
                  block.setByteLength(blockData.toString().length());
                  block.calculateBaseTarget(lastBlock);
                  if (saveInCache) {
                    DownloadCache.AddBlock(block);
                  } else {
                    // at correct height we can check if this fork even is worth processing.
                    if (chainHeight == block.getHeight() && (block.getCumulativeDifficulty().compareTo(curCumulativeDifficulty) < 0)) {
                        // peer does not have better Cumulative difficulty at same height as us.
                        logger.debug("Peer almost caused us to popoff blocks. Blacklisting.");
                        peer.blacklist();
                        forkBlocks.clear();
                        break;
                    }
                    forkBlocks.add(block);
                  }
                  lastBlock = block;
                } catch (RuntimeException | BurstException.ValidationException e) {
                  logger.info("Failed to parse block: {}" + e.toString(), e);
                  logger.info("Failed to parse block trace: " + e.getStackTrace());
                  peer.blacklist(e);
                  return;
                } catch (Exception e) {
                  logger.warn("Unhandled exception {}" + e.toString(), e);
                  logger.warn("Unhandled exception trace: " + e.getStackTrace());
                }
              } // end block loop

              logger.trace("Unverified blocks: " + DownloadCache.getUnverifiedSize());
              logger.trace("Blocks in cache: " + DownloadCache.size());
              logger.trace("Bytes in cache: " + DownloadCache.getBlockCacheSize());
            } // end synchronized
            if (forkBlocks.size() > 0) {
              processFork(peer, forkBlocks, commonBlockId);
            }

          } catch (BurstException.StopException e) {
            logger.info("Blockchain download stopped: " + e.getMessage());
          } catch (Exception e) {
            logger.info("Error in blockchain download thread", e);
          } // end second try
        } catch (Throwable t) {
          logger.info("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString(), t);
          System.exit(1);
        } // end first try
        try {
          Thread.sleep(10);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      } // end while
    }

    /** Returns the current blockchain height for a peer (or the current height of this instance if something goes wrong) */

    private long getCommonMilestoneBlockId(Peer peer) {

      String lastMilestoneBlockId = null;

      while (true) {
        JSONObject milestoneBlockIdsRequest = new JSONObject();
        milestoneBlockIdsRequest.put("requestType", "getMilestoneBlockIds");
        if (lastMilestoneBlockId == null) {
          milestoneBlockIdsRequest.put("lastBlockId", Convert.toUnsignedLong(DownloadCache.getLastBlockId()));
        } else {
          milestoneBlockIdsRequest.put("lastMilestoneBlockId", lastMilestoneBlockId);
        }

        JSONObject response = peer.send(JSON.prepareRequest(milestoneBlockIdsRequest));
        if (response == null) {
          logger.debug("Got null respose in getCommonMilestoneBlockId");
          return 0;
        }
        JSONArray milestoneBlockIds = (JSONArray) response.get("milestoneBlockIds");
        if (milestoneBlockIds == null) {
          logger.debug("MilestoneArray is null");
          return 0;
        }
        if (milestoneBlockIds.isEmpty()) {
          return Genesis.GENESIS_BLOCK_ID;
        }
        // prevent overloading with blockIds
        if (milestoneBlockIds.size() > 20) {
          logger.debug("Obsolete or rogue peer " + peer.getPeerAddress() + " sends too many milestoneBlockIds, blacklisting");
          peer.blacklist();
          return 0;
        }
        if (Boolean.TRUE.equals(response.get("last"))) {
          peerHasMore = false;
        }

        for (Object milestoneBlockId : milestoneBlockIds) {
          long blockId = Convert.parseUnsignedLong((String) milestoneBlockId);

          if (DownloadCache.HasBlock(blockId)) {
            if (lastMilestoneBlockId == null && milestoneBlockIds.size() > 1) {
              peerHasMore = false;
              logger.debug("Peer dont have more (cache)");
            }
            return blockId;
          }
          lastMilestoneBlockId = (String) milestoneBlockId;
        }
      }
    }

    private long getCommonBlockId(Peer peer, long commonBlockId) {

      while (true) {
        JSONObject request = new JSONObject();
        request.put("requestType", "getNextBlockIds");
        request.put("blockId", Convert.toUnsignedLong(commonBlockId));
        JSONObject response = peer.send(JSON.prepareRequest(request));
        if (response == null) {
          return 0;
        }
        JSONArray nextBlockIds = (JSONArray) response.get("nextBlockIds");
        if (nextBlockIds == null || nextBlockIds.isEmpty()) {
          return 0;
        }
        // prevent overloading with blockIds
        if (nextBlockIds.size() > 1440) {
          logger.debug("Obsolete or rogue peer " + peer.getPeerAddress() + " sends too many nextBlockIds, blacklisting");
          peer.blacklist();
          return 0;
        }

        for (Object nextBlockId : nextBlockIds) {
          long blockId = Convert.parseUnsignedLong((String) nextBlockId);
          if (!DownloadCache.HasBlock(blockId)) {
            return commonBlockId;
          }
          commonBlockId = blockId;
        }
      }

    }

    private JSONArray getNextBlocks(Peer peer, long curBlockId) {

      JSONObject request = new JSONObject();
      request.put("requestType", "getNextBlocks");
      request.put("blockId", Convert.toUnsignedLong(curBlockId));
      logger.debug("Getting next Blocks after " + curBlockId + " from " + peer.getPeerAddress());
      JSONObject response = peer.send(JSON.prepareRequest(request));
      if (response == null) {
        return null;
      }

      JSONArray nextBlocks = (JSONArray) response.get("nextBlocks");
      if (nextBlocks == null) {
        return null;
      }
      // prevent overloading with blocks
      if (nextBlocks.size() > 1440) {
        logger.debug("Obsolete or rogue peer " + peer.getPeerAddress() + " sends too many nextBlocks, blacklisting");
        peer.blacklist();
        return null;
      }
      logger.debug("Got " + nextBlocks.size() + " Blocks after " + curBlockId + " from " + peer.getPeerAddress());
      return nextBlocks;

    }

    private void processFork(Peer peer, final List<BlockImpl> forkBlocks, long forkBlockId) {

      logger.warn("We have got a forked chain. Waiting for cache to be processed.");
      while (true) {
        synchronized (DownloadCache) {
          if (DownloadCache.size() == 0) {
            break;
          }
        }
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }

      logger.warn("Cache is now processed. Starting to process fork.");
      BlockImpl forkBlock = blockchain.getBlock(forkBlockId);

      synchronized (blockchain) {
        // we read the current cumulative difficulty
        BigInteger curCumulativeDifficulty = blockchain.getLastBlock().getCumulativeDifficulty();

        // We remove blocks from chain back to where we start our fork
        // and save it in a list if we need to restore
        List<BlockImpl> myPoppedOffBlocks = popOffTo(forkBlock);

        // now we check that our chain is popped off.
        // If all seems ok is we try to push fork.
        int pushedForkBlocks = 0;
        if (blockchain.getLastBlock().getId() == forkBlockId) {
          for (BlockImpl block : forkBlocks) {
            if (blockchain.getLastBlock().getId() == block.getPreviousBlockId()) {
              try {
                pushBlock(block);
                pushedForkBlocks += 1;
              } catch (BlockNotAcceptedException e) {
                peer.blacklist(e);
                break;
              }
            }
          }
        }

        /*
         * we check if we succeeded to push any block.
         * if we did we check against cumulative difficulty
         * If it is lower we blacklist peer and set chain to be processed later. 
         */
        if (pushedForkBlocks > 0 && blockchain.getLastBlock().getCumulativeDifficulty().compareTo(curCumulativeDifficulty) < 0) {
          logger.debug("Pop off caused by peer " + peer.getPeerAddress() + ", blacklisting");
          peer.blacklist();
          List<BlockImpl> peerPoppedOffBlocks = popOffTo(forkBlock);
          pushedForkBlocks = 0;
          peerPoppedOffBlocks.forEach(block -> {
              TransactionProcessorImpl.getInstance().processLater(block.getTransactions());
            });
        }

        // if we did not push any blocks we try to restore chain.
        if (pushedForkBlocks == 0) {
          for (int i = myPoppedOffBlocks.size() - 1; i >= 0; i--) {
            BlockImpl block = myPoppedOffBlocks.remove(i);
            try {
              pushBlock(block);
            } catch (BlockNotAcceptedException e) {
              logger.error("Popped off block no longer acceptable: " + block.getJSONObject().toJSONString(), e);
              break;
            }
          }
        } else {
            myPoppedOffBlocks.forEach(block -> {
                TransactionProcessorImpl.getInstance().processLater(block.getTransactions());
            });
        }
      } // synchronized
      DownloadCache.ResetCache(); //Reset and set cached vars to chaindata.
    }
  };

  private BlockchainProcessorImpl() {

    blockListeners.addListener(new Listener<Block>() {
      @Override
      public void notify(Block block) {
        if (block.getHeight() % 5000 == 0) {
          logger.info("processed block " + block.getHeight());
        }
      }
    }, Event.BLOCK_SCANNED);

    blockListeners.addListener(new Listener<Block>() {
      @Override
      public void notify(Block block) {
        if (block.getHeight() % 5000 == 0) {
          logger.info("processed block " + block.getHeight());
          // Db.analyzeTables(); no-op
        }
      }
    }, Event.BLOCK_PUSHED);

    if (trimDerivedTables) {
      blockListeners.addListener(new Listener<Block>() {
        @Override
        public void notify(Block block) {
          if (block.getHeight() % 1440 == 0) {
            lastTrimHeight = Math.max(block.getHeight() - Constants.MAX_ROLLBACK, 0);
            if (lastTrimHeight > 0) {
                derivedTables.forEach(table -> {
                    table.trim(lastTrimHeight);
                });
            }
          }
        }
      }, Event.AFTER_BLOCK_APPLY);
    }
    // No-op
    //		blockListeners.addListener(new Listener<Block>() {
    //			@Override
    //			public void notify(Block block) {
    //				Db.analyzeTables();
    //			}
    //		}, Event.RESCAN_END);

    ThreadPool.runBeforeStart(new Runnable() {
      @Override
      public void run() {
        addGenesisBlock();
        if (forceScan) {
          scan(0);
        }
      }
    }, false);

    ThreadPool.scheduleThread("GetMoreBlocks", getMoreBlocksThread, 2);
    ThreadPool.scheduleThread("ImportBlocks", blockImporterThread, 10);
    ThreadPool.scheduleThreadCores("VerifyPoc", pocVerificationThread, 9);
    //ThreadPool.scheduleThread("Info", debugInfoThread, 5);
  }

  @Override
  public boolean addListener(Listener<Block> listener, BlockchainProcessor.Event eventType) {
    return blockListeners.addListener(listener, eventType);
  }

  @Override
  public boolean removeListener(Listener<Block> listener, Event eventType) {
    return blockListeners.removeListener(listener, eventType);
  }

  @Override
  public void registerDerivedTable(DerivedTable table) {
    logger.info("Registering derived table " + table.getClass());
    derivedTables.add(table);
  }

  @Override
  public Peer getLastBlockchainFeeder() {
    return lastBlockchainFeeder;
  }

  @Override
  public int getLastBlockchainFeederHeight() {
    return lastBlockchainFeederHeight;
  }

  @Override
  public boolean isScanning() {
    return isScanning;
  }

  @Override
  public int getMinRollbackHeight() {
    return trimDerivedTables ? (lastTrimHeight > 0 ? lastTrimHeight : Math.max(blockchain.getHeight() - Constants.MAX_ROLLBACK, 0)) : 0;
  }

  @Override
  public void processPeerBlock(JSONObject request) throws BurstException {
    BlockImpl newBlock = BlockImpl.parseBlock(request);
    if (newBlock == null) {
      logger.debug("Peer has announced an unprocessable block.");
      return;
    }
    synchronized (blockchain) {
      synchronized (DownloadCache) {
        /*
         *  This process takes care of the blocks that is announced by peers
         *  We do not want to be feeded forks.
         */
        BlockImpl chainblock = DownloadCache.getLastBlock();
        if (chainblock.getId() == newBlock.getPreviousBlockId()) {
          newBlock.setHeight(chainblock.getHeight() + 1);
          newBlock.setByteLength(newBlock.toString().length());
          newBlock.calculateBaseTarget(chainblock);
          DownloadCache.AddBlock(newBlock);
        } else {
          logger.debug("Peer sent us block: " + newBlock.getPreviousBlockId() + " that does not match our chain.");
        }
      }
    }
  }

  @Override
  public List<BlockImpl> popOffTo(int height) {
    return popOffTo(blockchain.getBlockAtHeight(height));
  }

  @Override
  public void fullReset() {
    synchronized (blockchain) {
      //blockDb.deleteBlock(Genesis.GENESIS_BLOCK_ID); // fails with stack overflow in H2
      blockDb.deleteAll();
      addGenesisBlock();
      scan(0);
    }
  }

  @Override
  public void forceScanAtStart() {
    forceScan = true;
  }

  @Override
  public void validateAtNextScan() {
    validateAtScan = true;
  }

  void setGetMoreBlocks(boolean getMoreBlocks) {
    this.getMoreBlocks = getMoreBlocks;
  }

  private void addBlock(BlockImpl block) {
    if (Burst.getStores().getBlockchainStore().addBlock(block))
      blockchain.setLastBlock(block);

  }

  private void addGenesisBlock() {
    if (blockDb.hasBlock(Genesis.GENESIS_BLOCK_ID)) {
      logger.info("Genesis block already in database");
      BlockImpl lastBlock = blockDb.findLastBlock();
      blockchain.setLastBlock(lastBlock);
      logger.info("Last block height: " + lastBlock.getHeight());
      return;
    }
    logger.info("Genesis block not in database, starting from scratch");
    try {
      List<TransactionImpl> transactions = new ArrayList<>();
      MessageDigest digest = Crypto.sha256();
      transactions.forEach(transaction -> {
          digest.update(transaction.getBytes());
        });
      ByteBuffer bf = ByteBuffer.allocate(0);
      bf.order(ByteOrder.LITTLE_ENDIAN);
      byte[] byteATs = bf.array();
      BlockImpl genesisBlock = new BlockImpl(-1, 0, 0, 0, 0, transactions.size() * 128, digest.digest(), Genesis.CREATOR_PUBLIC_KEY, new byte[32], Genesis.GENESIS_BLOCK_SIGNATURE, null, transactions, 0, byteATs);
      genesisBlock.setPrevious(null);
      addBlock(genesisBlock);
    } catch (BurstException.ValidationException e) {
      logger.info(e.getMessage());
      throw new RuntimeException(e.toString(), e);
    }
  }

  private final Timer pushBlockTimer = Burst.metrics.timer(MetricRegistry.name(BlockchainImpl.class, "pushBlock"));
  private final Timer finishTimer = Burst.metrics.timer(MetricRegistry.name(BlockchainImpl.class, "pushBlockFinishTables"));
  private final Timer commitTimer = Burst.metrics.timer(MetricRegistry.name(BlockchainImpl.class, "pushBlockCommit"));

  private void pushBlock(final BlockImpl block) throws BlockNotAcceptedException {
    final Timer.Context context = pushBlockTimer.time();
    try {
      int curTime = Burst.getEpochTime();

      synchronized (blockchain) {
        TransactionProcessorImpl transactionProcessor = TransactionProcessorImpl.getInstance();
        BlockImpl previousLastBlock = null;
        try {
          Burst.getStores().beginTransaction();
          previousLastBlock = blockchain.getLastBlock();

          if (previousLastBlock.getId() != block.getPreviousBlockId()) {
            throw new BlockOutOfOrderException("Previous block id doesn't match");
          }

          if (block.getVersion() != getBlockVersion(previousLastBlock.getHeight())) {
            throw new BlockNotAcceptedException("Invalid version " + block.getVersion());
          }

          if (block.getVersion() != 1 && !Arrays.equals(Crypto.sha256().digest(previousLastBlock.getBytes()), block.getPreviousBlockHash())) {
            throw new BlockNotAcceptedException("Previous block hash doesn't match");
          }
          if (block.getTimestamp() > curTime + MAX_TIMESTAMP_DIFFERENCE || block.getTimestamp() <= previousLastBlock.getTimestamp()) {
            throw new BlockOutOfOrderException("Invalid timestamp: " + block.getTimestamp() + " current time is " + curTime + ", previous block timestamp is " + previousLastBlock.getTimestamp());
          }
          if (block.getId() == 0L || blockDb.hasBlock(block.getId())) {
            throw new BlockNotAcceptedException("Duplicate block or invalid id");
          }
          if (!block.verifyGenerationSignature()) {
            throw new BlockNotAcceptedException("Generation signature verification failed");
          }
          if (!block.verifyBlockSignature()) {
            throw new BlockNotAcceptedException("Block signature verification failed");
          }

          Map<TransactionType, Set<String>> duplicates = new HashMap<>();
          long calculatedTotalAmount = 0;
          long calculatedTotalFee = 0;
          MessageDigest digest = Crypto.sha256();

          for (TransactionImpl transaction : block.getTransactions()) {

            if (transaction.getTimestamp() > curTime + MAX_TIMESTAMP_DIFFERENCE) {
              throw new BlockOutOfOrderException("Invalid transaction timestamp: " + transaction.getTimestamp() + ", current time is " + curTime);
            }
            if (transaction.getTimestamp() > block.getTimestamp() + MAX_TIMESTAMP_DIFFERENCE || transaction.getExpiration() < block.getTimestamp()) {
              throw new TransactionNotAcceptedException("Invalid transaction timestamp " + transaction.getTimestamp() + " for transaction " + transaction.getStringId() + ", current time is " + curTime + ", block timestamp is " + block.getTimestamp(), transaction);
            }
            if (transactionDb.hasTransaction(transaction.getId())) {
              throw new TransactionNotAcceptedException("Transaction " + transaction.getStringId() + " is already in the blockchain", transaction);
            }
            if (transaction.getReferencedTransactionFullHash() != null) {
              if ((previousLastBlock.getHeight() < Constants.REFERENCED_TRANSACTION_FULL_HASH_BLOCK && !transactionDb.hasTransaction(Convert.fullHashToId(transaction.getReferencedTransactionFullHash())))
                  || (previousLastBlock.getHeight() >= Constants.REFERENCED_TRANSACTION_FULL_HASH_BLOCK && !hasAllReferencedTransactions(transaction, transaction.getTimestamp(), 0))) {
                throw new TransactionNotAcceptedException("Missing or invalid referenced transaction " + transaction.getReferencedTransactionFullHash() + " for transaction " + transaction.getStringId(), transaction);
              }
            }
            if (transaction.getVersion() != transactionProcessor.getTransactionVersion(previousLastBlock.getHeight())) {
              throw new TransactionNotAcceptedException("Invalid transaction version " + transaction.getVersion() + " at height " + previousLastBlock.getHeight(), transaction);
            }
            /*if (!transaction.verifySignature()) { // moved to preVerify
              throw new TransactionNotAcceptedException("Signature verification failed for transaction "
              + transaction.getStringId() + " at height " + previousLastBlock.getHeight(), transaction);
              }*/
            if (!transaction.verifyPublicKey()) {
              throw new TransactionNotAcceptedException("Wrong public key in transaction " + transaction.getStringId() + " at height " + previousLastBlock.getHeight(), transaction);
            }
            if (Burst.getBlockchain().getHeight() >= Constants.AUTOMATED_TRANSACTION_BLOCK) {
              if (!EconomicClustering.verifyFork(transaction)) {
                logger.debug("Block " + block.getStringId() + " height " + (previousLastBlock.getHeight() + 1) + " contains transaction that was generated on a fork: " + transaction.getStringId() + " ecBlockHeight " + transaction.getECBlockHeight() + " ecBlockId "
                    + Convert.toUnsignedLong(transaction.getECBlockId()));
                throw new TransactionNotAcceptedException("Transaction belongs to a different fork", transaction);
              }
            }
            if (transaction.getId() == 0L) {
              throw new TransactionNotAcceptedException("Invalid transaction id", transaction);
            }
            if (transaction.isDuplicate(duplicates)) {
              throw new TransactionNotAcceptedException("Transaction is a duplicate: " + transaction.getStringId(), transaction);
            }
            try {
              transaction.validate();
            } catch (BurstException.ValidationException e) {
              throw new TransactionNotAcceptedException(e.getMessage(), transaction);
            }

            calculatedTotalAmount += transaction.getAmountNQT();

            calculatedTotalFee += transaction.getFeeNQT();

            digest.update(transaction.getBytes());

          }

          if (calculatedTotalAmount > block.getTotalAmountNQT() || calculatedTotalFee > block.getTotalFeeNQT()) {
            throw new BlockNotAcceptedException("Total amount or fee don't match transaction totals");
          }
          if (!Arrays.equals(digest.digest(), block.getPayloadHash())) {
            throw new BlockNotAcceptedException("Payload hash doesn't match");
          }

          long remainingAmount = Convert.safeSubtract(block.getTotalAmountNQT(), calculatedTotalAmount);
          long remainingFee = Convert.safeSubtract(block.getTotalFeeNQT(), calculatedTotalFee);

          block.setPrevious(previousLastBlock);
          blockListeners.notify(block, Event.BEFORE_BLOCK_ACCEPT);
          transactionProcessor.requeueAllUnconfirmedTransactions();
          Account.flushAccountTable();
          addBlock(block);
          accept(block, remainingAmount, remainingFee);
          Timer.Context finishContext = finishTimer.time();
          derivedTables.forEach(table -> {
              table.finish();
            });
          finishContext.stop();
          Timer.Context commitContext = commitTimer.time();
          Burst.getStores().commitTransaction();
          commitContext.stop();
        } catch (BlockNotAcceptedException | ArithmeticException e) {
          Burst.getStores().rollbackTransaction();
          blockchain.setLastBlock(previousLastBlock);
          throw e;
        } finally {
          Burst.getStores().endTransaction();
        }
        logger.debug("Successully pushed " + block.getId() + " (height " + block.getHeight() + ")");
      } // synchronized

      blockListeners.notify(block, Event.BLOCK_PUSHED);

      if (block.getTimestamp() >= Burst.getEpochTime() - MAX_TIMESTAMP_DIFFERENCE) {
        Peers.sendToSomePeers(block);
      }
    } finally {
      context.stop();
    }
  }

  private final Timer acceptBlockTimer = Burst.metrics.timer(MetricRegistry.name(BlockchainImpl.class, "acceptBlock"));

  private void accept(BlockImpl block, Long remainingAmount, Long remainingFee) throws TransactionNotAcceptedException, BlockNotAcceptedException {
    final Timer.Context context = acceptBlockTimer.time();
    try {
      Subscription.clearRemovals();
      TransactionProcessorImpl transactionProcessor = TransactionProcessorImpl.getInstance();
      for (TransactionImpl transaction : block.getTransactions()) {
        if (!transaction.applyUnconfirmed()) {
          throw new TransactionNotAcceptedException("Double spending transaction: " + transaction.getStringId(), transaction);
        }
      }
      long calculatedRemainingAmount = 0;
      long calculatedRemainingFee = 0;
      //ATs
      AT_Block atBlock;
      AT.clearPendingFees();
      AT.clearPendingTransactions();
      try {
        atBlock = AT_Controller.validateATs(block.getBlockATs(), Burst.getBlockchain().getHeight());
      } catch (NoSuchAlgorithmException e) {
        //should never reach that point
        throw new BlockNotAcceptedException("md5 does not exist");
      } catch (AT_Exception e) {
        throw new BlockNotAcceptedException("ats are not matching at block height " + Burst.getBlockchain().getHeight() + " (" + e + ")");
      }
      calculatedRemainingAmount += atBlock.getTotalAmount();
      calculatedRemainingFee += atBlock.getTotalFees();
      //ATs
      if (Subscription.isEnabled()) {
        calculatedRemainingFee += Subscription.applyUnconfirmed(block.getTimestamp());
      }
      if (remainingAmount != null && remainingAmount != calculatedRemainingAmount) {
        throw new BlockNotAcceptedException("Calculated remaining amount doesn't add up");
      }
      if (remainingFee != null && remainingFee != calculatedRemainingFee) {
        throw new BlockNotAcceptedException("Calculated remaining fee doesn't add up");
      }
      blockListeners.notify(block, Event.BEFORE_BLOCK_APPLY);
      block.apply();
      Subscription.applyConfirmed(block);
      if (Escrow.isEnabled()) {
        Escrow.updateOnBlock(block);
      }
      blockListeners.notify(block, Event.AFTER_BLOCK_APPLY);
      if (block.getTransactions().size() > 0) {
        transactionProcessor.notifyListeners(block.getTransactions(), TransactionProcessor.Event.ADDED_CONFIRMED_TRANSACTIONS);
      }
    } finally {
      context.stop();
    }
  }

  private List<BlockImpl> popOffTo(Block commonBlock) {
    synchronized (blockchain) {
      if (commonBlock.getHeight() < getMinRollbackHeight()) {
        throw new IllegalArgumentException("Rollback to height " + commonBlock.getHeight() + " not suppported, " + "current height " + Burst.getBlockchain().getHeight());
      }
      if (!blockchain.hasBlock(commonBlock.getId())) {
        logger.debug("Block " + commonBlock.getStringId() + " not found in blockchain, nothing to pop off");
        return Collections.emptyList();
      }
      List<BlockImpl> poppedOffBlocks = new ArrayList<>();
      try {
        Burst.getStores().beginTransaction();
        BlockImpl block = blockchain.getLastBlock();
        logger.debug("Rollback from " + block.getHeight() + " to " + commonBlock.getHeight());
        while (block.getId() != commonBlock.getId() && block.getId() != Genesis.GENESIS_BLOCK_ID) {
          poppedOffBlocks.add(block);
          block = popLastBlock();
        }
        derivedTables.forEach(table -> {
            table.rollback(commonBlock.getHeight());
          });
        Burst.getStores().commitTransaction();
      } catch (RuntimeException e) {
        Burst.getStores().rollbackTransaction();
        logger.debug("Error popping off to " + commonBlock.getHeight(), e);
        throw e;
      } finally {
        Burst.getStores().endTransaction();
      }
      return poppedOffBlocks;
    } // synchronized
  }

  private BlockImpl popLastBlock() {
    BlockImpl block = blockchain.getLastBlock();
    if (block.getId() == Genesis.GENESIS_BLOCK_ID) {
      throw new RuntimeException("Cannot pop off genesis block");
    }
    BlockImpl previousBlock = blockDb.findBlock(block.getPreviousBlockId());
    blockchain.setLastBlock(block, previousBlock);
    block.getTransactions().forEach(transaction -> {
        transaction.unsetBlock();
      });
    blockDb.deleteBlocksFrom(block.getId());
    blockListeners.notify(block, Event.BLOCK_POPPED);
    return previousBlock;
  }

  int getBlockVersion(int previousBlockHeight) {
    return previousBlockHeight < Constants.TRANSPARENT_FORGING_BLOCK ? 1 : previousBlockHeight < Constants.NQT_BLOCK ? 2 : 3;
  }

  void generateBlock(String secretPhrase, byte[] publicKey, Long nonce) throws BlockNotAcceptedException {

    TransactionProcessorImpl transactionProcessor = TransactionProcessorImpl.getInstance();
    List<TransactionImpl> orderedUnconfirmedTransactions = new ArrayList<>();
    try (FilteringIterator<TransactionImpl> transactions = new FilteringIterator<>(transactionProcessor.getAllUnconfirmedTransactions(), new FilteringIterator.Filter<TransactionImpl>() {
      @Override
      public boolean ok(TransactionImpl transaction) {
        return hasAllReferencedTransactions(transaction, transaction.getTimestamp(), 0);
      }
    })) {
      for (TransactionImpl transaction : transactions) {
        orderedUnconfirmedTransactions.add(transaction);
      }
    }

    BlockImpl previousBlock = blockchain.getLastBlock();

    SortedSet<TransactionImpl> blockTransactions = new TreeSet<>();

    Map<TransactionType, Set<String>> duplicates = new HashMap<>();

    long totalAmountNQT = 0;
    long totalFeeNQT = 0;
    int payloadLength = 0;

    int blockTimestamp = Burst.getEpochTime();

    while (payloadLength <= Constants.MAX_PAYLOAD_LENGTH && blockTransactions.size() <= Constants.MAX_NUMBER_OF_TRANSACTIONS) {

      int prevNumberOfNewTransactions = blockTransactions.size();

      for (TransactionImpl transaction : orderedUnconfirmedTransactions) {

        if (blockTransactions.size() >= Constants.MAX_NUMBER_OF_TRANSACTIONS) {
          break;
        }

        int transactionLength = transaction.getSize();
        if (blockTransactions.contains(transaction) || payloadLength + transactionLength > Constants.MAX_PAYLOAD_LENGTH) {
          continue;
        }

        if (transaction.getVersion() != transactionProcessor.getTransactionVersion(previousBlock.getHeight())) {
          continue;
        }

        if (transaction.getTimestamp() > blockTimestamp + MAX_TIMESTAMP_DIFFERENCE || transaction.getExpiration() < blockTimestamp) {
          continue;
        }

        if (Burst.getBlockchain().getHeight() >= Constants.AUTOMATED_TRANSACTION_BLOCK) {
          if (!EconomicClustering.verifyFork(transaction)) {
            logger.debug("Including transaction that was generated on a fork: " + transaction.getStringId() + " ecBlockHeight " + transaction.getECBlockHeight() + " ecBlockId " + Convert.toUnsignedLong(transaction.getECBlockId()));
            continue;
          }
        }

        if (transaction.isDuplicate(duplicates)) {
          continue;
        }

        try {
          transaction.validate();
        } catch (BurstException.NotCurrentlyValidException e) {
          continue;
        } catch (BurstException.ValidationException e) {
          transactionProcessor.removeUnconfirmedTransaction(transaction);
          continue;
        }

        blockTransactions.add(transaction);
        payloadLength += transactionLength;
        totalAmountNQT += transaction.getAmountNQT();
        totalFeeNQT += transaction.getFeeNQT();

      }

      if (blockTransactions.size() == prevNumberOfNewTransactions) {
        break;
      }
    }

    if (Subscription.isEnabled()) {
      synchronized (blockchain) {
        Subscription.clearRemovals();
        try {
          Burst.getStores().beginTransaction();
          transactionProcessor.requeueAllUnconfirmedTransactions();
            //transactionProcessor.processTransactions(newTransactions, false);
            blockTransactions.forEach(transaction -> {
                transaction.applyUnconfirmed();
            });
          totalFeeNQT += Subscription.calculateFees(blockTimestamp);
        } finally {
          Burst.getStores().rollbackTransaction();
          Burst.getStores().endTransaction();
        }
      }
    }

    //final byte[] publicKey = Crypto.getPublicKey(secretPhrase);

    //ATs for block
    AT.clearPendingFees();
    AT.clearPendingTransactions();
    AT_Block atBlock = AT_Controller.getCurrentBlockATs(Constants.MAX_PAYLOAD_LENGTH - payloadLength, previousBlock.getHeight() + 1);
    byte[] byteATs = atBlock.getBytesForBlock();

    //digesting AT Bytes
    if (byteATs != null) {
      payloadLength += byteATs.length;
      totalFeeNQT += atBlock.getTotalFees();
      totalAmountNQT += atBlock.getTotalAmount();

    }

    //ATs for block

    MessageDigest digest = Crypto.sha256();

    blockTransactions.forEach(transaction -> {
        digest.update(transaction.getBytes());
      });

    byte[] payloadHash = digest.digest();

    byte[] generationSignature = Burst.getGenerator().calculateGenerationSignature(previousBlock.getGenerationSignature(), previousBlock.getGeneratorId());

    BlockImpl block;
    byte[] previousBlockHash = Crypto.sha256().digest(previousBlock.getBytes());

    try {

      block = new BlockImpl(getBlockVersion(previousBlock.getHeight()), blockTimestamp, previousBlock.getId(), totalAmountNQT, totalFeeNQT, payloadLength, payloadHash, publicKey, generationSignature, null, previousBlockHash, new ArrayList<>(blockTransactions), nonce, byteATs);

    } catch (BurstException.ValidationException e) {
      // shouldn't happen because all transactions are already validated
      logger.info("Error generating block", e);
      return;
    }

    block.sign(secretPhrase);

    block.setPrevious(previousBlock);

    try {
      pushBlock(block);
      blockListeners.notify(block, Event.BLOCK_GENERATED);
      logger.debug("Account " + Convert.toUnsignedLong(block.getGeneratorId()) + " generated block " + block.getStringId() + " at height " + block.getHeight());

      synchronized (DownloadCache) {
        DownloadCache.ResetCache();
      }
    } catch (TransactionNotAcceptedException e) {
      logger.debug("Generate block failed: " + e.getMessage());
      Transaction transaction = e.getTransaction();
      logger.debug("Removing invalid transaction: " + transaction.getStringId());
      transactionProcessor.removeUnconfirmedTransaction((TransactionImpl) transaction);
      throw e;
    } catch (BlockNotAcceptedException e) {
      logger.debug("Generate block failed: " + e.getMessage());
      throw e;
    }
  }

  private boolean hasAllReferencedTransactions(Transaction transaction, int timestamp, int count) {
    if (transaction.getReferencedTransactionFullHash() == null) {
      return timestamp - transaction.getTimestamp() < 60 * 1440 * 60 && count < 10;
    }
    transaction = transactionDb.findTransactionByFullHash(transaction.getReferencedTransactionFullHash());
    if (!Subscription.isEnabled()) {
      if (transaction != null && transaction.getSignature() == null) {
        transaction = null;
      }
    }
    return transaction != null && hasAllReferencedTransactions(transaction, timestamp, count + 1);
  }

  @Override
  public void scan(int height) {
    throw new UnsupportedOperationException("scan is disabled for the moment - please use the pop off feature");

    /** Disabled since we are not sure weather this works correctly
     *
     * */

    //        synchronized (blockchain) {
    //
    //            TransactionProcessorImpl transactionProcessor = TransactionProcessorImpl.getInstance();
    //            int blockchainHeight = Burst.getBlockchain().getHeight();
    //            if (height > blockchainHeight + 1) {
    //                throw new IllegalArgumentException("Rollback height " + (height - 1) + " exceeds current blockchain height of " + blockchainHeight);
    //            }
    //            if (height > 0 && height < getMinRollbackHeight()) {
    //                logger.info("Rollback of more than " + Constants.MAX_ROLLBACK + " blocks not supported, will do a full scan");
    //                height = 0;
    //            }
    //            if (height < 0) {
    //                height = 0;
    //            }
    //            isScanning = true;
    //            logger.info("Scanning blockchain starting from height " + height + "...");
    //            if (validateAtScan) {
    //                logger.debug("Also verifying signatures and validating transactions...");
    //            }
    //            try (Connection con = Db.beginTransaction();
    //
    //                 PreparedStatement pstmt = con.prepareStatement("SELECT * FROM block WHERE height >= ? ORDER BY db_id ASC")) {
    //                transactionProcessor.requeueAllUnconfirmedTransactions();
    //                Account.flushAccountTable();
    //                for (DerivedTable table : derivedTables) {
    //                    if (height == 0) {
    //                        table.truncate();
    //                    } else {
    //                        table.rollback(height - 1);
    //                    }
    //                }
    //                pstmt.setInt(1, height);
    //                try (ResultSet rs = pstmt.executeQuery()) {
    //                    BlockImpl currentBlock = blockDb.findBlockAtHeight(height);
    //                    blockListeners.notify(currentBlock, BlockchainProcessor.Event.RESCAN_BEGIN);
    //                    long currentBlockId = currentBlock.getId();
    //                    if (height == 0) {
    //                        blockchain.setLastBlock(currentBlock); // special case to avoid no last block
    //                        //Account.addOrGetAccount(Genesis.CREATOR_ID).apply(Genesis.CREATOR_PUBLIC_KEY, 0);
    //                    } else {
    //                        blockchain.setLastBlock(blockDb.findBlockAtHeight(height - 1));
    //                    }
    //                    while (rs.next()) {
    //                        try {
    //                            currentBlock = blockDb.loadBlock(con, rs);
    //                            if (currentBlock.getId() != currentBlockId) {
    //                                if (currentBlockId == Genesis.GENESIS_BLOCK_ID) {
    //                                    logger.debug("Wrong genesis block id set. Should be: " + Convert.toUnsignedLong(currentBlock.getId()));
    //                                }
    //                                throw new BurstException.NotValidException("Database blocks in the wrong order!");
    //                            }
    //                            if (validateAtScan && currentBlockId != Genesis.GENESIS_BLOCK_ID) {
    //                                if (!currentBlock.verifyBlockSignature()) {
    //                                    throw new BurstException.NotValidException("Invalid block signature");
    //                                }
    //                                if (!currentBlock.verifyGenerationSignature()) {
    //                                    throw new BurstException.NotValidException("Invalid block generation signature");
    //                                }
    //                                if (currentBlock.getVersion() != getBlockVersion(blockchain.getHeight())) {
    //                                    throw new BurstException.NotValidException("Invalid block version");
    //                                }
    //                                byte[] blockBytes = currentBlock.getBytes();
    //                                JSONObject blockJSON = (JSONObject) JSONValue.parse(currentBlock.getJSONObject().toJSONString());
    //                                if (!Arrays.equals(blockBytes, BlockImpl.parseBlock(blockJSON).getBytes())) {
    //                                    throw new BurstException.NotValidException("Block JSON cannot be parsed back to the same block");
    //                                }
    //                                for (TransactionImpl transaction : currentBlock.getTransactions()) {
    //									/*if (!transaction.verifySignature()) { // moved to preVerify
    //										throw new BurstException.NotValidException("Invalid transaction signature");
    //									}*/
    //                                    if (!transaction.verifyPublicKey()) {
    //                                        throw new BurstException.NotValidException("Wrong transaction public key");
    //                                    }
    //                                    if (transaction.getVersion() != transactionProcessor.getTransactionVersion(blockchain.getHeight())) {
    //                                        throw new BurstException.NotValidException("Invalid transaction version");
    //                                    }
    //									/*
    //                                    if (!EconomicClustering.verifyFork(transaction)) {
    //                                        logger.debug("Found transaction that was generated on a fork: " + transaction.getStringId()
    //                                                + " in block " + currentBlock.getStringId() + " at height " + currentBlock.getHeight()
    //                                                + " ecBlockHeight " + transaction.getECBlockHeight() + " ecBlockId " + Convert.toUnsignedLong(transaction.getECBlockId()));
    //                                        //throw new BurstException.NotValidException("Invalid transaction fork");
    //                                    }
    //									 */
    //                                    transaction.validate();
    //                                    byte[] transactionBytes = transaction.getBytes();
    //                                    if (currentBlock.getHeight() > Constants.NQT_BLOCK
    //                                            && !Arrays.equals(transactionBytes, transactionProcessor.parseTransaction(transactionBytes).getBytes())) {
    //                                        throw new BurstException.NotValidException("Transaction bytes cannot be parsed back to the same transaction");
    //                                    }
    //                                    JSONObject transactionJSON = (JSONObject) JSONValue.parse(transaction.getJSONObject().toJSONString());
    //                                    if (!Arrays.equals(transactionBytes, transactionProcessor.parseTransaction(transactionJSON).getBytes())) {
    //                                        throw new BurstException.NotValidException("Transaction JSON cannot be parsed back to the same transaction");
    //                                    }
    //                                }
    //                            }
    //                            blockListeners.notify(currentBlock, BlockchainProcessor.Event.BEFORE_BLOCK_ACCEPT);
    //                            blockchain.setLastBlock(currentBlock);
    //                            accept(currentBlock, null, null);
    //                            currentBlockId = currentBlock.getNextBlockId();
    //                            Db.commitTransaction();
    //                        } catch (BurstException | RuntimeException e) {
    //                            Db.rollbackTransaction();
    //                            logger.debug(e.toString(), e);
    //                            logger.debug("Applying block " + Convert.toUnsignedLong(currentBlockId) + " at height "
    //                                    + (currentBlock == null ? 0 : currentBlock.getHeight()) + " failed, deleting from database");
    //                            if (currentBlock != null) {
    //                                transactionProcessor.processLater(currentBlock.getTransactions());
    //                            }
    //                            while (rs.next()) {
    //                                try {
    //                                    currentBlock = blockDb.loadBlock(con, rs);
    //                                    transactionProcessor.processLater(currentBlock.getTransactions());
    //                                } catch (BurstException.ValidationException ignore) {
    //                                }
    //                            }
    //                            blockDb.deleteBlocksFrom(currentBlockId);
    //                            blockchain.setLastBlock(blockDb.findLastBlock());
    //                        }
    //                        blockListeners.notify(currentBlock, BlockchainProcessor.Event.BLOCK_SCANNED);
    //                    }
    //                    Db.endTransaction();
    //                    blockListeners.notify(currentBlock, BlockchainProcessor.Event.RESCAN_END);
    //                }
    //            } catch (SQLException e) {
    //                throw new RuntimeException(e.toString(), e);
    //            }
    //            validateAtScan = false;
    //            logger.info("...done at height " + Burst.getBlockchain().getHeight());
    //            isScanning = false;
    //        } // synchronized
  }

}
