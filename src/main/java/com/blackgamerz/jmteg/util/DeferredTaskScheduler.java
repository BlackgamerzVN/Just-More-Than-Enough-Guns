package com.blackgamerz.jmteg.util;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Very small server-thread-only deferred task scheduler.
 * Schedule tasks to run after N server ticks.
 *
 * Usage: DeferredTaskScheduler.schedule(task, delayTicks);
 *
 * This class runs on the Forge event bus (server side). Keep tasks short.
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DeferredTaskScheduler {
    private static final Logger LOGGER = LogManager.getLogger("JMT-DeferredTaskScheduler");

    private static final AtomicLong sequencer = new AtomicLong(0);

    private static final PriorityQueue<Scheduled> queue = new PriorityQueue<>((a, b) -> {
        if (a.runTick != b.runTick) return Long.compare(a.runTick, b.runTick);
        return Long.compare(a.seq, b.seq);
    });

    private static long currentTick = 0L;

    private DeferredTaskScheduler() {}

    public static void schedule(Runnable task, long delayTicks) {
        if (task == null) return;
        long run = Math.max(0, currentTick + Math.max(0, delayTicks));
        synchronized (queue) {
            queue.add(new Scheduled(run, sequencer.incrementAndGet(), task));
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        currentTick++;
        // run all tasks scheduled for <= currentTick
        while (true) {
            Scheduled s;
            synchronized (queue) {
                s = queue.peek();
                if (s == null || s.runTick > currentTick) break;
                queue.poll();
            }
            try {
                s.runnable.run();
            } catch (Throwable t) {
                LOGGER.debug("DeferredTaskScheduler task threw", t);
            }
        }
    }

    public static long getCurrentTick() {
        return currentTick;
    }

    private static final class Scheduled {
        final long runTick;
        final long seq;
        final Runnable runnable;
        Scheduled(long runTick, long seq, Runnable runnable) {
            this.runTick = runTick;
            this.seq = seq;
            this.runnable = runnable;
        }
    }
}