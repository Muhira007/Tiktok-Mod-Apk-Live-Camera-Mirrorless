package dev.otglive.bridge;

/** One pending item: producer never waits for decoding, and stale work is overwritten. */
final class LatestFrameMailbox<T> {
    private T pending;
    private boolean closed;
    private long dropped;

    synchronized boolean offer(T item) {
        if(item==null) throw new NullPointerException("item");
        if(closed) return false;
        if(pending!=null) dropped++;
        pending=item;
        notifyAll();
        return true;
    }
    synchronized T take() throws InterruptedException {
        while(pending==null && !closed) wait();
        if(closed) return null;
        T result=pending;
        pending=null;
        return result;
    }
    synchronized long dropped() { return dropped; }
    synchronized void close() {
        closed=true;
        pending=null;
        notifyAll();
    }
}
