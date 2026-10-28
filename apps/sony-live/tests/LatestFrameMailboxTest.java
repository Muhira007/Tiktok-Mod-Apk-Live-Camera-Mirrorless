package dev.otglive.bridge;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercise nonblocking producer behavior and shutdown with a slower consumer. */
public final class LatestFrameMailboxTest {
    static void require(boolean value,String message) {
        if(!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        LatestFrameMailbox<Integer> mailbox=new LatestFrameMailbox<>();
        mailbox.offer(1); mailbox.offer(2); mailbox.offer(3);
        require(mailbox.take()==3,"Stale queued frames were delivered");
        require(mailbox.dropped()==2,"Overwritten frames not accounted for");
        CountDownLatch waiting=new CountDownLatch(1),stopped=new CountDownLatch(1);
        Thread blocked=new Thread(()->{
            try { waiting.countDown(); require(mailbox.take()==null,"Closed mailbox returned a stale frame"); }
            catch(InterruptedException error) { throw new AssertionError(error); }
            finally { stopped.countDown(); }
        });
        blocked.start(); waiting.await(); mailbox.close();
        require(stopped.await(2,TimeUnit.SECONDS),"Closing did not wake blocked decoder");
        require(!mailbox.offer(4),"Closed session accepted new data");

        LatestFrameMailbox<Integer> concurrent=new LatestFrameMailbox<>();
        CountDownLatch last=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread consumer=new Thread(()->{
            int previous=-1;
            try {
                for(;;) {
                    Integer frame=concurrent.take();
                    if(frame==null) break;
                    require(frame>previous,"Frame order regressed");
                    previous=frame;
                    if(frame==99999) last.countDown();
                    Thread.sleep(1);
                }
            } catch(Throwable error) { failure.set(error); }
        });
        consumer.start();
        Thread producer=new Thread(()->{ for(int frame=0;frame<100000;frame++) concurrent.offer(frame); });
        producer.start(); producer.join(3000);
        require(!producer.isAlive(),"USB producer waited on decoder work");
        require(last.await(3,TimeUnit.SECONDS),"Newest final frame was not delivered");
        concurrent.close(); consumer.join(2000);
        require(!consumer.isAlive(),"Consumer survived session shutdown");
        if(failure.get()!=null) throw new AssertionError(failure.get());
        require(concurrent.dropped()>0,"Slow consumer test did not exercise frame replacement");
        System.out.println("PASS: newest-only queue, nonblocking producer, ordered delivery, shutdown isolation");
    }
}
