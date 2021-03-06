package blockchain.utility;

import blockchain.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MiningUtility {

    private static final Logger logger = LoggerFactory.getLogger(MiningUtility.class);

    public static void executeMiningCycle(Blockchain blockchain, int NUMBER_OF_PROCESSORS, int numberOfNewBlocksToCreate) throws InterruptedException {
        /* Mining the first block without messages from users */
        if (blockchain.getChain().isEmpty()) {
            mineNewBlock(blockchain, NUMBER_OF_PROCESSORS);
            --numberOfNewBlocksToCreate;
        }

        /* Create user threads for sending messages to the blockchain's message deque */
        ExecutorService userExecutorService = createUserExecutorService(blockchain, List.of("Jun", "Mike"));
        try {
            logger.trace("userExecutorService is waiting for users to be prepared to send messages.");
            TimeUnit.MILLISECONDS.sleep(1000);
        } catch (Exception e) {
            logger.info("Exception Occurred while waiting for userExecutorService to be activated", e);
        }

        /* Mining blocks with messages from users */
        while (blockchain.getUserMsgDeque().size() < 5) {
            TimeUnit.MILLISECONDS.sleep(100);
        }
        for (int i = 0; i < numberOfNewBlocksToCreate; i++) {
            mineNewBlock(blockchain, NUMBER_OF_PROCESSORS);
        }

        /* userExecutorService shutdown */
        userExecutorService.shutdownNow();
    }

    private static void mineNewBlock(Blockchain blockchain, int NUMBER_OF_PROCESSORS) {
        int MINIMUM_BLOCK_SIZE = 5;
        int MAXIMUM_BLOCK_SIZE = 10;

        ExecutorService miningExecutorService = Executors.newFixedThreadPool(NUMBER_OF_PROCESSORS);
        logger.trace("miningExecutorService has been initiated");

        int blockId = blockchain.getChain().size() + 1;
        String lastBlockHash = blockchain.getLastBlockHash();
        int numOfZeros = blockchain.getNumOfZeros();

        Deque<Message> userMsgDeque = blockchain.getUserMsgDeque();
        List<Message> tmpMessageContainer = new ArrayList<>();
        Deque<Message> userMsgRollbackStack = new LinkedList<>();
        int tmpMaxMsgIdForRollBack = blockchain.getMaxMsgIdOfBlockUnderConstruction();

        if (!blockchain.getChain().isEmpty()) {
            while (userMsgDeque.size() < MINIMUM_BLOCK_SIZE) {
                try {
                    logger.trace("Waiting for userMsgDeque to fill up.");
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (Exception e) {
                    logger.error("Exception occurred while waiting for userMsgDeque to fill up.", e);
                }
            };

            synchronized (userMsgDeque) {
                int newBlockDataSize = Math.min(userMsgDeque.size(), MAXIMUM_BLOCK_SIZE);
                for (int i = 0; i < newBlockDataSize; i++) {
                    blockchain.setMaxMsgIdOfBlockUnderConstruction(userMsgDeque.peekFirst().getId());
                    userMsgRollbackStack.offerLast(userMsgDeque.peekFirst());
                    tmpMessageContainer.add(userMsgDeque.pollFirst());
                }
            }
        }

        List<Message> immutableMessageList = Collections.unmodifiableList(tmpMessageContainer);
        Set<BlockMiner> minerSet = Stream.iterate(0, i -> i + 1)
                .limit(NUMBER_OF_PROCESSORS)
                .map(i -> new BlockMiner(blockId, lastBlockHash, numOfZeros, immutableMessageList))
                .collect(Collectors.toSet());

        Block newlyCreatedBlock;
        try {
            newlyCreatedBlock = miningExecutorService.invokeAny(minerSet);
        } catch (Exception e) {
            /* Message roll back */
            while (userMsgRollbackStack.peekLast() != null) {
                userMsgDeque.offerFirst(userMsgRollbackStack.pollLast());
            }
            blockchain.setMaxMsgIdOfBlockUnderConstruction(tmpMaxMsgIdForRollBack);
            logger.debug("Exception occurred while calling invokeAny() method. Messages Rollback Done.", e);
            return;
        }
        blockchain.addBlock(newlyCreatedBlock);

        miningExecutorService.shutdownNow();
        try {
            miningExecutorService.awaitTermination(60, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.debug("Exception occurred while awaiting executor service termination.", e);
        }
        logger.trace("miningExecutorService has been shut down");
    }

    private static ExecutorService createUserExecutorService(Blockchain blockchain, List<String> userNameList) {
        logger.trace("userExecutorService starts getting activated");
        int numOfThreads = Math.min(userNameList.size(), 4);
        ExecutorService userExecutorService = Executors.newFixedThreadPool(numOfThreads);

        for (String username : userNameList) {

        }

        userNameList.stream()
                .map(userName -> new User(blockchain, userName))
                .collect(Collectors.toList())
                .forEach(userExecutorService::submit);

        return userExecutorService;
    }
}
