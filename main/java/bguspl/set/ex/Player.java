package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.lang.Thread.sleep;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private Dealer dealer;

    private ConcurrentLinkedQueue <Integer> keyQueue; // a queue for every player actions

    public volatile boolean isPlaying; // is the player active
    public volatile int pointOrPenalty; // indicates if set is legal or not

    public volatile boolean frozen; // indicates whether player is on point/penalty freeze

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.keyQueue = new ConcurrentLinkedQueue<Integer>();
        this.dealer = dealer;
        this.isPlaying = true;
        this.pointOrPenalty = 0;
        frozen = false;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            // TODO implement main player loop
            while (!terminate && !keyQueue.isEmpty())
            {
                dealer.readLocker.lock();
                try
                {
                    int currSlot = keyQueue.remove();
                    if (table.slotToCard[currSlot] != null)
                    {
                        if (table.getPlayersTokens().get(id).contains(currSlot))
                            table.removeToken(id, currSlot);

                        else if (table.playersTokens.get(id).size() < env.config.featureSize)
                        {
                            table.placeToken(id, currSlot);

                            if (table.playersTokens.get(id).size() == env.config.featureSize)
                            {
                                isPlaying = false;
                                keyQueue.clear();
                                dealer.getSetsToCheck().add(id); // testSet
                            }
                        }
                    }
                } finally {dealer.readLocker.unlock();}

                while (!isPlaying) {} // wait for dealer check if set
                isPlaying = false;
                if (pointOrPenalty == 2)
                {
                    point();
                }
                else if (pointOrPenalty == 1)
                {
                    penalty();
                }
                else
                    isPlaying = true;
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                // TODO implement player key press simulator
                Random keyGen = new Random();
                keyPressed(keyGen.nextInt(env.config.tableSize)); // adds generated key to queue
                /*try {
                    synchronized (this) { wait(); }
                } catch (InterruptedException ignored) {}*/
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        // TODO implement
        // available for actions
        if (isPlaying)
        { // so players wont press keys while in penalty
            dealer.readLocker.lock();
            try
            {
                if (keyQueue.size() < env.config.featureSize && table.slotToCard[slot] != null)
                {
                    keyQueue.add(slot);
                }
            } finally {dealer.readLocker.unlock();}
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // TODO implement
        try
        {
            env.ui.setScore(id, ++score);
            env.ui.setFreeze(id,env.config.pointFreezeMillis);
            sleep(env.config.pointFreezeMillis);
        } catch (InterruptedException ex) {}

        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        pointOrPenalty = 0;
        isPlaying = true;
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // TODO implement
        try
        {
            env.ui.setFreeze(id,env.config.penaltyFreezeMillis);
            sleep(env.config.penaltyFreezeMillis);
        } catch (InterruptedException ex) {}
        pointOrPenalty = 0;
        isPlaying = true;
    }

    public int score() {
        return score;
    }
}
