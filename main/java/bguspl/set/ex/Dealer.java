package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    private ConcurrentLinkedQueue<Integer> setsToCheck; // added by us

    public final ReadWriteLock locker = new ReentrantReadWriteLock();
    public final Lock writeLocker = locker.writeLock();
    public final Lock readLocker = locker.readLock();
    private ArrayList<Thread> playersThreads; // list of players threads
    public ArrayList<Long> frozenPlayers;
    private long tenMil = 10;



    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        setsToCheck = new ConcurrentLinkedQueue<Integer>();
        playersThreads = new ArrayList<Thread>();
        frozenPlayers = new ArrayList<Long>();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for (int i = 0; i < players.length; i++)
        {
            playersThreads.add(new Thread(players[i], "player " + players[i].id));
            playersThreads.get(playersThreads.size()-1).start(); // start each added thread
            frozenPlayers.add((long) -1); // by default - no penalty when created
        }
        while (!shouldFinish()) {
            writeLocker.lock();
            int[] playersStatus = new int[players.length];
            try {
                trackPlayersStatus(playersStatus, true);
                placeCardsOnTable();
                trackPlayersStatus(playersStatus, false);
                updateFrozen();
                reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 500;
            } finally {writeLocker.unlock();}

            timerLoop();

            writeLocker.lock();
            try {
                trackPlayersStatus(playersStatus, true);
                updateFrozen();
                updateTimerDisplay(false);
                removeAllCardsFromTable();
                trackPlayersStatus(playersStatus, false);
            } finally {writeLocker.unlock();}
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        terminate();
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateFrozen();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // TODO implement
        for (int i = playersThreads.size() - 1; i >= 0; i--)
        {
            players[i].terminate();
            try {
                // awake all sleep players to notice them the game is over, and terminate
                players[i].isPlaying = true;
                playersThreads.get(i).interrupt();
                playersThreads.get(i).join();
            } catch (Exception ex) {}
        }
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        // TODO implement
        while (!(setsToCheck.isEmpty()))
        {
            int currId = setsToCheck.remove();
            if (table.playersTokens.get(currId).size() == env.config.featureSize)
            { // checks if the tokens of the current player removed
                int[] isSet = new int[env.config.featureSize];
                for (int i = 0; i < env.config.featureSize; i++) { // get the slots for testSet
                    isSet[i] = table.slotToCard[table.playersTokens.get(currId).get(i)];
                }
                if (env.util.testSet(isSet))
                {
                    writeLocker.lock();
                    try
                    {
                        // point
                        for (int i = 0; i < env.config.featureSize; i++) { // remove cards, if they make Set from the table
                            table.removeCard(table.playersTokens.get(currId).get(0));
                        }
                        placeCardsOnTable();
                        players[currId].pointOrPenalty = 2;
                        players[currId].frozen = true;
                        players[currId].isPlaying = true;
                        frozenPlayers.set(currId, System.currentTimeMillis() + env.config.pointFreezeMillis);
                        updateFrozen();
                        updateTimerDisplay(true); // after set, reset timer
                    } finally{writeLocker.unlock();}
                }
                else
                { // penalty
                    players[currId].pointOrPenalty = 1;
                    players[currId].frozen = false;
                    players[currId].isPlaying = true;
                    frozenPlayers.set(currId, System.currentTimeMillis() + env.config.penaltyFreezeMillis);
                    updateFrozen();
                }
            }
            else
                players[currId].isPlaying = true;
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        // TODO implement
        Collections.shuffle (deck);
        for (int i = 0; i < env.config.tableSize && !(deck.isEmpty()); i++)
        {
            updateFrozen();
            if (table.slotToCard[i] == null)
            {
                Integer card = deck.remove(0);
                table.placeCard(card, i);
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        // TODO implement
        updateTimerDisplay(false);
        try{
            Thread.sleep(tenMil);
        } catch (InterruptedException ex){};
        updateTimerDisplay(false);
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        // TODO implement
        if (reset)
        {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 500;
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        }
        else
        {
            if (System.currentTimeMillis() >= reshuffleTime - env.config.turnTimeoutWarningMillis)
                env.ui.setCountdown(Math.max(reshuffleTime - System.currentTimeMillis(), 0), true);
            else
                env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        // TODO implement
        for (int i = 0; i < table.slotToCard.length; i++)
        // removes each card and the tokens on it, and updates UI
        {
            updateFrozen();
            if (table.slotToCard[i] != null)
            { // do not add null to deck
                deck.add(table.slotToCard[i]);
                table.removeCard(i);
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
        int maxScore = 0;
        int numOfWinners = 0;
        for (Player player : players)
        {
            if (player.score() > maxScore) {
                maxScore = player.score();
                numOfWinners = 1;
            }
            else if (player.score() == maxScore)
                numOfWinners++;
        }
        int [] winners = new int[numOfWinners];
        int index = 0;
        for (Player player : players)
        {
            if (player.score() == maxScore) {
                winners[index] = player.id;
                index++;
            }
        }
        env.ui.announceWinner(winners);
    }

    // added by us
    public ConcurrentLinkedQueue<Integer> getSetsToCheck() {
        return setsToCheck;
    }

    public void updateFrozen ()
    { // added by us - for update ui and players remaining penalty
        for (int i = 0; i < frozenPlayers.size(); i++)
        {
            if (frozenPlayers.get(i) >= 0)
            { // otherwise dont change anything
                if (System.currentTimeMillis() < frozenPlayers.get(i))
                {
                    if (players [i].frozen)
                    { // point freeze
                        env.ui.setFreeze(i, Math.min(frozenPlayers.get(i) - System.currentTimeMillis() + 1000, env.config.pointFreezeMillis));
                    }
                    else
                    { // penalty freeze
                        env.ui.setFreeze(i, Math.min(frozenPlayers.get(i) - System.currentTimeMillis() + 1000, env.config.penaltyFreezeMillis));
                    }
                }
                else
                { // out of freeze
                    env.ui.setFreeze(i, 0);
                    frozenPlayers.set(i, (long) -1);
                }
            }
        }
    }

    public void trackPlayersStatus (int [] playersStatus, boolean flag)
    { // track status for each player when removing/placing cards, also restricting placing tokens when not allowed
        if (flag)
        {
            for (int i = 0; i < players.length; i++)
            {
                if (players[i].isPlaying)
                    playersStatus[i] = 1;
                else
                    playersStatus[i] = 0;
                players[i].isPlaying = false;
            }
        }
        else
        {
            for (int i = 0; i < players.length; i++)
            {
                if (playersStatus[i] == 1)
                    players[i].isPlaying = true;
            }
        }
    }
}
