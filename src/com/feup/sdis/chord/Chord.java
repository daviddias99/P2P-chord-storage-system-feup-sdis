package com.feup.sdis.chord;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.feup.sdis.messages.Status;
import com.feup.sdis.messages.requests.BatchRequest;
import com.feup.sdis.messages.requests.Request;
import com.feup.sdis.messages.requests.chord.ClosestPrecedingRequest;
import com.feup.sdis.messages.requests.chord.FindSuccessorRequest;
import com.feup.sdis.messages.requests.chord.GetPredecessorRequest;
import com.feup.sdis.messages.requests.chord.NotifyRequest;
import com.feup.sdis.messages.requests.chord.PingRequest;
import com.feup.sdis.messages.requests.chord.ReconcileSuccessorListRequest;
import com.feup.sdis.messages.responses.BatchResponse;
import com.feup.sdis.messages.responses.Response;
import com.feup.sdis.messages.responses.chord.ClosestPrecedingResponse;
import com.feup.sdis.messages.responses.chord.FindSuccessorResponse;
import com.feup.sdis.messages.responses.chord.GetPredecessorResponse;
import com.feup.sdis.messages.responses.chord.ReconcileSuccessorListResponse;
import com.feup.sdis.model.StoredChunkInfo;
import com.feup.sdis.peer.MessageListener;

/**
 * CHORD TODO
 * 
 * - [x] Add list of r sucessors as described in the paper 
 * - [x] Move updating threads to a scheduled thread pool 
 * - [x] Handle peer failing 
 * - [ ] Handle peer shutdown 
 * - [ ] See concurrency of chord (maybe we need some syncrhonized methods) 
 * - [x] Implement the "predecessor failure-checking" of the protocol 
 * - WARNING: Chord table sizes and key sizes are receiving transformations to allow testing for
 * smaller tables, this may caused unexpected behaviour.
 */

public class Chord {

    // Constants
    private final boolean DEBUG_MODE = true;
    private final int FINGER_TABLE_SIZE = 8;
    private final int FIX_FINGERS_INTERVAL_MS = 500;
    private final int STABILIZE_INTERVAL_MS = 500;
    private final int CHECK_PREDECESSOR_INTERVAL_MS = 500;
    private final int SUCESSOR_LIST_SIZE = 3;
    private final ScheduledThreadPoolExecutor periodicThreadPool = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(3);

    public static Chord chordInstance;
    private SocketAddress[] fingerTable;
    private SocketAddress[] successorList;
    private UUID[] stepValues;
    private SocketAddress self = null;
    private SocketAddress predecessor = null;
    private int next = 1;

    // create
    public Chord(SocketAddress self) {
        this.self = self;
        this.fingerTable = new SocketAddress[FINGER_TABLE_SIZE];
        this.successorList = new SocketAddress[SUCESSOR_LIST_SIZE];
        this.stepValues = new UUID[FINGER_TABLE_SIZE];
        Arrays.fill(this.fingerTable, this.self);
        Arrays.fill(this.successorList, this.self);
        this.initKeyLookupSteps();
        
        // TODO: Remove this, only used to allow for smaller table sizes for testing
        self.setPeerID(Chord.normalizeToSize(self.getPeerID(), FINGER_TABLE_SIZE));
    }

    // join
    public Chord(SocketAddress self, SocketAddress node) throws Exception {
        this(self);

        // TODO: Remove this, only used to allow for smaller table sizes for testing
        node.setPeerID(Chord.normalizeToSize(node.getPeerID(), FINGER_TABLE_SIZE));

        Response res = MessageListener.sendMessage(new ClosestPrecedingRequest(self), node);

        if(res.getStatus() == Status.ERROR)
            throw new Exception("Could not create chord peer");

        this.setSucessor(((ClosestPrecedingResponse)res).getAddress());
    }

    public SocketAddress[] getSucessorList(){

        return this.successorList;
    }

    private SocketAddress getSucessor() {

        return this.fingerTable[0];
    }

    private void setSucessor(SocketAddress newSucessor) {

        this.fingerTable[0] = newSucessor;
        this.successorList[0] = newSucessor;
    }

    public synchronized SocketAddress closestPrecedingNode(UUID key) {

        SocketAddress bestMatchFingerTable = null;
        SocketAddress bestMatchSuccTable = null;

        // Check finger table
        for (int i = fingerTable.length - 1; i >= 0; i--) {

            // if (this.fingerTable[i] == null)
            //     continue;

            if (this.betweenTwoKeys(self.getPeerID(), key, this.fingerTable[i].getPeerID(), false, false)){

                bestMatchFingerTable =  this.fingerTable[i];
                break;
            }
        }

        // Check successor list
        for (int i = successorList.length - 1; i >= 0; i--) {

            if (this.betweenTwoKeys(self.getPeerID(), key, this.successorList[i].getPeerID(), false, false)){

                bestMatchSuccTable =  this.successorList[i];
                break;
            }

        }

        if(bestMatchFingerTable == null ||  bestMatchSuccTable == null)
            return self;

        // Return best match
        return compareDistanceToKey(bestMatchFingerTable.getPeerID(), bestMatchSuccTable.getPeerID(), key) <= 0 ? bestMatchFingerTable : bestMatchSuccTable;
    }

    public SocketAddress lookup(String chunkID, int repDegree ){

       return this.findSuccessor( normalizeToSize(UUID.nameUUIDFromBytes(  StoredChunkInfo.getChunkID(chunkID, repDegree).getBytes()), this.FINGER_TABLE_SIZE) );
    }

    public SocketAddress findSuccessor(UUID key) {


        if (this.betweenTwoKeys(this.self.getPeerID(), this.getSucessor().getPeerID(), key, false, true)) {
            // The current peer is the closeste preceding node from key
            return this.getSucessor();

        }

        // Ask the closest match to find key's successor
        // If the designated peer does not answer find the next closest match
        while(true){

            SocketAddress cpn = this.closestPrecedingNode(key);

            if(cpn == self)
                break;

            Response res = MessageListener.sendMessage(new FindSuccessorRequest(key), cpn);

            if(res.getStatus() == Status.ERROR){


                for(int i = 0; i < this.FINGER_TABLE_SIZE; i++){
                    if(this.fingerTable[i].equals(cpn))
                        this.fingerTable[i] = self;
                }

                if(this.DEBUG_MODE)
                    System.out.println("> CHORD: find successor failed, trying again.");

                continue;
            }

            return ((FindSuccessorResponse)res).getAddress();
        }
        
        if(this.DEBUG_MODE)
            System.out.println("> CHORD: find successor failed, could not recover.");

        return self;
    }

    private void stabilize() {

        if (this.getSucessor() == this.self) {

            // Successor is self
            SocketAddress successorsPerceivedPredecessorAddr = this.predecessor;

            if (successorsPerceivedPredecessorAddr != null){
                
                // If the sucessor is self, and the predecessor is a different peer, the successor should now be that peer
                this.setSucessor(successorsPerceivedPredecessorAddr);
            }

            return;
        }

        // Send a batch-request containing a perceived predecessor and r-list request
        GetPredecessorRequest getPredReq = new GetPredecessorRequest();
        ReconcileSuccessorListRequest recSucReq = new ReconcileSuccessorListRequest();
        Request[] requestList = {getPredReq,recSucReq};
        Response responses = MessageListener.sendMessage(new BatchRequest(requestList), this.getSucessor());

        // Check for errors on the responses
        if(responses.getStatus() == Status.ERROR){

            if(this.DEBUG_MODE)
                System.out.println("> CHORD: Stabilization failed (get_predecessor/reconcile_succ).");
            return;
        }

        // Fetch request responses
        BatchResponse batchResponses = (BatchResponse) responses;
        GetPredecessorResponse successorsPerceivedPredecessor = (GetPredecessorResponse) batchResponses.getResponses()[0];
        ReconcileSuccessorListResponse successorsSuccList = (ReconcileSuccessorListResponse) batchResponses.getResponses()[1];
        
        // Update successor list
        SocketAddress [] newSuccList = successorsSuccList.getSuccessorList();
        for(int i = 0; i < this.SUCESSOR_LIST_SIZE - 1; i++)
            this.successorList[i+1] = newSuccList[i];

        SocketAddress successorsPerceivedPredecessorAddr = successorsPerceivedPredecessor.getAddress();

        // Sucessor has no predecessor, notify him
        if (successorsPerceivedPredecessorAddr == null) {
            Response res = MessageListener.sendMessage(new NotifyRequest(self), this.getSucessor());

            if(res.getStatus() == Status.ERROR && this.DEBUG_MODE)
                System.out.println("> CHORD: Stabilization failed (notify on successor A).");

            return;
        }

        // No need to notify self
        UUID successorsPerceivedPredecessorID = successorsPerceivedPredecessorAddr.getPeerID();
        if(successorsPerceivedPredecessorID.equals(self.getPeerID()))
            return;

        // Update the sucessor
        if (this.betweenTwoKeys(this.self.getPeerID(), this.getSucessor().getPeerID(), successorsPerceivedPredecessorID,
                false, false)) {

            this.setSucessor(successorsPerceivedPredecessorAddr);

            if (DEBUG_MODE)
                System.out.println("> CHORD: Sucessor updated to " + successorsPerceivedPredecessorAddr);
        }

        Response res = MessageListener.sendMessage(new NotifyRequest(self), this.getSucessor());

        if(res.getStatus() == Status.ERROR && this.DEBUG_MODE)
            System.out.println("> CHORD: Stabilization failed (notify on successor B).");
            
        return;
    }

    public boolean notify(SocketAddress newPred) {

        UUID candidateID = newPred.getPeerID();

        if (this.predecessor == null || this.betweenTwoKeys(this.predecessor.getPeerID(), this.self.getPeerID(),
                candidateID, false, false)) {

            if (DEBUG_MODE)
                System.out.println("> CHORD: Predecessor updated to " + newPred);
            this.predecessor = newPred;

            return true;
        }

        return false;
    }

    private void fixFingers() {

        UUID neededID = this.stepValues[next];
        SocketAddress newFinger = this.findSuccessor(neededID);

        if (this.fingerTable[next].equals(newFinger)) {

            next = (next + 1) % FINGER_TABLE_SIZE;
            return;
        }
        if (DEBUG_MODE)
            System.out.println("> CHORD: Fixing fingers was " + this.fingerTable[next] + " (next = " + next + ")");

        synchronized (this.fingerTable) {

            this.fingerTable[next] = newFinger;
        }

        if (DEBUG_MODE)
            System.out.println("> CHORD: Added " + newFinger);

        next = (next + 1) % FINGER_TABLE_SIZE;

    }


    public SocketAddress getPredecessor() {
        return predecessor;
    }

    private void setPredecessor(SocketAddress predecessor) {
        this.predecessor = predecessor;
    }

    public void checkPredecessor() {

        if(this.predecessor == null)
            return;

        Response res = MessageListener.sendMessage(new PingRequest(), predecessor);

        if(res.getStatus().equals(Status.ERROR)){

            this.predecessor = null;
            if(DEBUG_MODE)
                System.out.println("> CHORD: Predecessor failed");
        }

    }

    public void initThreads() {

        Runnable t1 = () -> { try{Chord.chordInstance.stabilize();} catch(Exception ex){ ex.printStackTrace();}};
        Runnable t2 = () -> { try{Chord.chordInstance.fixFingers();} catch(Exception ex){ ex.printStackTrace();}};
        Runnable t3 = () -> { try{Chord.chordInstance.checkPredecessor();} catch(Exception ex){ ex.printStackTrace();}};

        // The threads are started with a delay to avoid them running at the same time
        this.periodicThreadPool.scheduleAtFixedRate(t1, 0, this.STABILIZE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        this.periodicThreadPool.scheduleAtFixedRate(t2, 200, this.FIX_FINGERS_INTERVAL_MS, TimeUnit.MILLISECONDS);
        this.periodicThreadPool.scheduleAtFixedRate(t3, 400, this.CHECK_PREDECESSOR_INTERVAL_MS, TimeUnit.MILLISECONDS);

    }

    private void initKeyLookupSteps() {

        BigInteger maxV = new BigInteger(String.valueOf(2)).pow(this.FINGER_TABLE_SIZE);

        for (int i = 0; i < this.FINGER_TABLE_SIZE; i++) {
            int step = (int) Math.pow(2, i);
            BigInteger nextVal = Chord.convertToBigInteger(this.self.getPeerID());
            nextVal = nextVal.add(new BigInteger(String.valueOf(step + ""))).mod(maxV);
            UUID neededID = Chord.convertFromBigInteger(nextVal);
            this.stepValues[i] = neededID;
        }
    }

    /*
     * HELPER FUNCTIONS
     */

    public boolean betweenTwoKeys(UUID a, UUID b, UUID c, boolean closedLeft, boolean closedRight) {

        if ((closedLeft && c.equals(a)) || (closedRight && c.equals(b)))
            return true;

        if ((!closedLeft && c.equals(a)) || (!closedRight && c.equals(b)))
            return false;

        // Whole circle is valid
        if (b.equals(a) && !c.equals(a))
            return true;

        return (a.compareTo(b) < 0) ? (a.compareTo(c) < 0) && (c.compareTo(b) < 0)
                : !((b.compareTo(c) < 0) && (c.compareTo(a) < 0));
    }

    public static int compareDistanceToKey(UUID a, UUID b, UUID c){

        BigInteger aInt = convertToBigInteger(a);
        BigInteger bInt = convertToBigInteger(b);
        BigInteger cInt = convertToBigInteger(c);
        BigInteger acDif = aInt.subtract(cInt).abs();
        BigInteger bcDif = bInt.subtract(cInt).abs();
        
        return acDif.compareTo(bcDif);
    }

    // TODO: Move this from here

    public static final BigInteger B = BigInteger.ONE.shiftLeft(64); // 2^64
    public static final BigInteger L = BigInteger.valueOf(Long.MAX_VALUE);

    public static BigInteger convertToBigInteger(UUID id) {
        BigInteger lo = BigInteger.valueOf(id.getLeastSignificantBits());
        BigInteger hi = BigInteger.valueOf(id.getMostSignificantBits());

        // If any of lo/hi parts is negative interpret as unsigned

        if (hi.signum() < 0)
            hi = hi.add(B);

        if (lo.signum() < 0)
            lo = lo.add(B);

        return lo.add(hi.multiply(B));
    }

    public static UUID convertFromBigInteger(BigInteger x) {
        BigInteger[] parts = x.divideAndRemainder(B);
        BigInteger hi = parts[0];
        BigInteger lo = parts[1];

        if (L.compareTo(lo) < 0)
            lo = lo.subtract(B);

        if (L.compareTo(hi) < 0)
            hi = hi.subtract(B);

        return new UUID(hi.longValueExact(), lo.longValueExact());
    }

    public static UUID normalizeToSize(UUID id, int bits){
        BigInteger maxV = new BigInteger(String.valueOf(2));
        maxV = maxV.pow(bits);
        BigInteger val = Chord.convertToBigInteger(id);
        val = val.mod(maxV);
        UUID neededID = Chord.convertFromBigInteger(val);
        
        return neededID;
        
    }

    public String state() {

        String message = "Chord state: \n";
        message += "  - Self" + "\n";
        message += "  > " + self + "\n";
        message += "  - Predecessor" + "\n";
        message += "  > " + predecessor + "\n";
        message += "  - Successor" + "\n";
        message += "  > " + this.getSucessor() + "\n";
        message += "  - Finger table" + "\n";
        for (int i = 0; i < this.fingerTable.length; i++) {
            message += "  > entry #" + i + " - " + this.fingerTable[i] + "\n";
        }
        message += "  - Successor list" + "\n";
        for (int i = 0; i < this.successorList.length; i++) {
            message += "  > entry #" + i + " - " + this.successorList[i] + "\n";
        }


        return message;
    }
}