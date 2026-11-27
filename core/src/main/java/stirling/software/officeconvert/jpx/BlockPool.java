package stirling.software.officeconvert.jpx;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class BlockPool {

    private static final int BLOCKS_PER_WORKER = 4;

    interface Job {
        void run(BlockEncoder coder, int[] scratch);
    }

    private final int threads;

    private final int scratch;

    private final BlockEncoder coder = new BlockEncoder();

    private final int[] own;

    BlockPool(int requested, int scratch) {
        int processors = Runtime.getRuntime().availableProcessors();
        ForkJoinPool pool = ForkJoinTask.inForkJoinPool() ? ForkJoinTask.getPool() : ForkJoinPool.commonPool();
        int available = Math.min(processors, pool.getParallelism() + 1);
        this.threads = Math.max(1, requested == 0 ? available : Math.min(requested, available));
        this.scratch = scratch;
        own = new int[scratch];
    }

    int threads() {
        return threads;
    }

    void run(List<Job> jobs) throws InterruptedIOException {
        int workers = Math.min(threads, jobs.size() / BLOCKS_PER_WORKER);
        if (workers <= 1) {
            for (Job job : jobs) {
                interrupted(Thread.currentThread());
                job.run(coder, own);
            }
            return;
        }
        Thread owner = Thread.currentThread();
        AtomicInteger next = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Helper> helpers = new ArrayList<>(workers - 1);
        for (int i = 1; i < workers; i++) {
            Helper h = new Helper(jobs, next, stop, failure, owner, scratch);
            helpers.add(h);
            h.fork();
        }
        drain(jobs, next, stop, failure, owner, coder, own);
        for (Helper h : helpers) {
            if (h.claimed.compareAndSet(false, true)) {
                continue;
            }
            h.join();
        }
        Throwable first = failure.get();
        if (first instanceof InterruptedIOException e) {
            throw e;
        }
        if (first instanceof RuntimeException e) {
            throw e;
        }
        if (first instanceof Error e) {
            throw e;
        }
        interrupted(owner);
    }

    private static void drain(List<Job> jobs, AtomicInteger next, AtomicBoolean stop,
            AtomicReference<Throwable> failure, Thread owner, BlockEncoder coder, int[] scratch) {
        try {
            int i;
            while (!stop.get() && (i = next.getAndIncrement()) < jobs.size()) {
                interrupted(owner);
                jobs.get(i).run(coder, scratch);
            }
        } catch (Throwable e) {
            failure.compareAndSet(null, e);
            stop.set(true);
        }
    }

    private static void interrupted(Thread owner) throws InterruptedIOException {
        if (owner.isInterrupted()) {
            throw new InterruptedIOException("JPEG 2000 encoding interrupted");
        }
    }

    private static final class Helper extends RecursiveAction {

        final AtomicBoolean claimed = new AtomicBoolean();

        private final List<Job> jobs;
        private final AtomicInteger next;
        private final AtomicBoolean stop;
        private final AtomicReference<Throwable> failure;
        private final Thread owner;
        private final int scratch;

        Helper(List<Job> jobs, AtomicInteger next, AtomicBoolean stop, AtomicReference<Throwable> failure,
                Thread owner, int scratch) {
            this.jobs = jobs;
            this.next = next;
            this.stop = stop;
            this.failure = failure;
            this.owner = owner;
            this.scratch = scratch;
        }

        @Override
        protected void compute() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            drain(jobs, next, stop, failure, owner, new BlockEncoder(), new int[scratch]);
        }
    }
}
