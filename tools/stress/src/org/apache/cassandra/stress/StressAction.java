/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.stress;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.Uninterruptibles;

import org.apache.cassandra.stress.operations.OpDistribution;
import org.apache.cassandra.stress.operations.OpDistributionFactory;
import org.apache.cassandra.stress.settings.StressSettings;
import org.apache.cassandra.stress.util.JavaDriverClient;
import org.apache.cassandra.stress.util.ThriftClient;
import org.apache.cassandra.stress.util.Timer;
import org.apache.cassandra.transport.SimpleClient;

import org.HdrHistogram.HistogramLogWriter;

public class StressAction implements Runnable
{

    private final StressSettings settings;
    private final PrintStream output;
    private final HistogramLogWriter responseTimeLogWriter;
    private final HistogramLogWriter serviceTimeLogWriter;
    private long reportingStartTime;

    public StressAction(StressSettings settings, PrintStream out, PrintStream responseTimeLogOutput, PrintStream serviceTimeLogOutput)
    {
        this.settings = settings;
        output = out;
        responseTimeLogWriter = (responseTimeLogOutput == null) ? null : new HistogramLogWriter(responseTimeLogOutput);
        serviceTimeLogWriter = (serviceTimeLogOutput == null) ? null : new HistogramLogWriter(serviceTimeLogOutput);
    }

    public void run()
    {
        // creating keyspace and column families
        settings.maybeCreateKeyspaces();

        // TODO: warmup should operate configurably over op/pk/row, and be of configurable length
        if (!settings.command.noWarmup)
            warmup(settings.command.getFactory(settings));

        output.println("Sleeping 2s...");
        Uninterruptibles.sleepUninterruptibly(2, TimeUnit.SECONDS);

        reportingStartTime = System.currentTimeMillis();
        if (responseTimeLogWriter != null) {
            responseTimeLogWriter.outputComment("[Latency histogram (based on correct start times), logged with cassandra-stress]");
            responseTimeLogWriter.outputLogFormatVersion();
            responseTimeLogWriter.outputStartTime(reportingStartTime);
            responseTimeLogWriter.setBaseTime(reportingStartTime);
            responseTimeLogWriter.outputLegend();
        }

        if (serviceTimeLogWriter != null) {
            serviceTimeLogWriter.outputComment("[Latency histogram (based on uncorrected start times), logged with " +
                    "cassandra-stress]");
            serviceTimeLogWriter.outputLogFormatVersion();
            serviceTimeLogWriter.outputStartTime(reportingStartTime);
            serviceTimeLogWriter.setBaseTime(reportingStartTime);
            serviceTimeLogWriter.outputLegend();
        }

        // TODO : move this to a new queue wrapper that gates progress based on a poisson (or configurable) distribution
        double rateOpsPerSec = settings.rate.opRateTargetPerSecond;
        if (rateOpsPerSec == 0) {
            rateOpsPerSec = 100000000000L;
        }

        boolean success;
        if (settings.rate.minThreads > 0) {
            success = runMulti(settings.rate.auto, rateOpsPerSec);
        }
        else {
            success = null != run(settings.command.getFactory(settings), settings.rate.threadCount, settings.command.count,
                    settings.command.duration, rateOpsPerSec, settings.command.durationUnits, output, responseTimeLogWriter, serviceTimeLogWriter);
        }

        if (success)
            output.println("END");
        else
            output.println("FAILURE");

        settings.disconnect();
    }

    // type provided separately to support recursive call for mixed command with each command type it is performing
    private void warmup(OpDistributionFactory operations)
    {
        // warmup - do 50k iterations; by default hotspot compiles methods after 10k invocations
        PrintStream warmupOutput = new PrintStream(new OutputStream() { @Override public void write(int b) throws IOException { } } );
        int iterations = 50000 * settings.node.nodes.size();
        for (OpDistributionFactory single : operations.each())
        {
            // we need to warm up all the nodes in the cluster ideally, but we may not be the only stress instance;
            // so warm up all the nodes we're speaking to only.
            output.println(String.format("Warming up %s with %d iterations...", single.desc(), iterations));
            run(single, 20, iterations, 0, 100000000000.0, null, warmupOutput, null, null);
        }
    }

    // TODO : permit varying more than just thread count
    // TODO : vary thread count based on percentage improvement of previous increment, not by fixed amounts
    private boolean runMulti(boolean auto, double rateOpsPerSec)
    {
        if (settings.command.targetUncertainty >= 0)
            output.println("WARNING: uncertainty mode (err<) results in uneven workload between thread runs, so should be used for high level analysis only");
        int prevThreadCount = -1;
        int threadCount = settings.rate.minThreads;
        List<StressMetrics> results = new ArrayList<>();
        List<String> runIds = new ArrayList<>();
        do
        {
            output.println(String.format("Running with %d threadCount", threadCount));

            StressMetrics result = run(settings.command.getFactory(settings), threadCount, settings.command.count,
                    settings.command.duration, rateOpsPerSec, settings.command.durationUnits, output, responseTimeLogWriter, serviceTimeLogWriter);
            if (result == null)
                return false;
            results.add(result);

            if (prevThreadCount > 0)
                System.out.println(String.format("Improvement over %d threadCount: %.0f%%",
                        prevThreadCount, 100 * averageImprovement(results, 1)));

            runIds.add(threadCount + " threadCount");
            prevThreadCount = threadCount;
            if (threadCount < 16)
                threadCount *= 2;
            else
                threadCount *= 1.5;

            if (!results.isEmpty() && threadCount > settings.rate.maxThreads)
                break;

            if (settings.command.type.updates)
            {
                // pause an arbitrary period of time to let the commit log flush, etc. shouldn't make much difference
                // as we only increase load, never decrease it
                output.println("Sleeping for 15s");
                try
                {
                    Thread.sleep(15 * 1000);
                } catch (InterruptedException e)
                {
                    return false;
                }
            }
            // run until we have not improved throughput significantly for previous three runs
        } while (!auto || (hasAverageImprovement(results, 3, 0) && hasAverageImprovement(results, 5, settings.command.targetUncertainty)));

        // summarise all results
        StressMetrics.summarise(runIds, results, output);
        return true;
    }

    private boolean hasAverageImprovement(List<StressMetrics> results, int count, double minImprovement)
    {
        return results.size() < count + 1 || averageImprovement(results, count) >= minImprovement;
    }

    private double averageImprovement(List<StressMetrics> results, int count)
    {
        double improvement = 0;
        for (int i = results.size() - count ; i < results.size() ; i++)
        {
            double prev = results.get(i - 1).getTiming().getHistory().opRate();
            double cur = results.get(i).getTiming().getHistory().opRate();
            improvement += (cur - prev) / prev;
        }
        return improvement / count;
    }

    private StressMetrics run(OpDistributionFactory operations,
                              int threadCount,
                              long opCount,
                              long duration,
                              double rateOpsPerSec,
                              TimeUnit durationUnits,
                              PrintStream output,
                              HistogramLogWriter responseTimeLogWriter,
                              HistogramLogWriter serviceTimeLogWriter)
    {
        output.println(String.format("Running %s with %d threads %s",
                                     operations.desc(),
                                     threadCount,
                                     durationUnits != null ? duration + " " + durationUnits.toString().toLowerCase()
                                        : opCount > 0      ? "for " + opCount + " iteration"
                                                           : "until stderr of mean < " + settings.command.targetUncertainty));
        final WorkManager workManager;
        if (opCount < 0)
            workManager = new WorkManager.ContinuousWorkManager();
        else
            workManager = new WorkManager.FixedWorkManager(opCount);

        final StressMetrics metrics = new StressMetrics(output,
                responseTimeLogWriter, serviceTimeLogWriter, settings.log.intervalMillis, settings);

        final CountDownLatch done = new CountDownLatch(threadCount);
        final Consumer[] consumers = new Consumer[threadCount];
        double threadRateOpsPerSec = rateOpsPerSec / threadCount;
        for (int i = 0; i < threadCount; i++)
        {
            Timer timer = metrics.getTiming().newTimer(settings.samples.liveCount / threadCount);
            consumers[i] = new Consumer(operations, done, workManager, timer, metrics, new Pacer(threadRateOpsPerSec, settings.rate.catchupMultiple));
        }

        // starting worker threadCount
        for (int i = 0; i < threadCount; i++)
            consumers[i].start();

        metrics.start();

        if (durationUnits != null)
        {
            Uninterruptibles.sleepUninterruptibly(duration, durationUnits);
            workManager.stop();
        }
        else if (opCount <= 0)
        {
            try
            {
                metrics.waitUntilConverges(settings.command.targetUncertainty,
                        settings.command.minimumUncertaintyMeasurements,
                        settings.command.maximumUncertaintyMeasurements);
            } catch (InterruptedException e) { }
            workManager.stop();
        }

        try
        {
            done.await();
            metrics.stop();
        }
        catch (InterruptedException e) {}

        if (metrics.wasCancelled())
            return null;

        metrics.summarise();

        boolean success = true;
        for (Consumer consumer : consumers)
            success &= consumer.success;

        if (!success)
            return null;

        return metrics;
    }

    private class Consumer extends Thread
    {

        private final OpDistribution operations;
        private final StressMetrics metrics;
        private final Timer timer;
        private final Pacer pacer;
        private volatile boolean success = true;
        private final WorkManager workManager;
        private final CountDownLatch done;

        public Consumer(OpDistributionFactory operations, CountDownLatch done, WorkManager workManager, Timer timer, StressMetrics metrics, Pacer pacer)
        {
            this.done = done;
            this.pacer = pacer;
            this.workManager = workManager;
            this.metrics = metrics;
            this.timer = timer;
            this.operations = operations.get(timer);
        }

        public void run()
        {
            timer.init();
            pacer.setInitialStartTime(System.nanoTime());
            try
            {

                SimpleClient sclient = null;
                ThriftClient tclient = null;
                JavaDriverClient jclient = null;

                switch (settings.mode.api)
                {
                    case JAVA_DRIVER_NATIVE:
                        jclient = settings.getJavaDriverClient();
                        break;
                    case SIMPLE_NATIVE:
                        sclient = settings.getSimpleNativeClient();
                        break;
                    case THRIFT:
                    case THRIFT_SMART:
                    case THRIFT_DUMMY:
                        tclient = settings.getThriftClient();
                        break;
                    default:
                        throw new IllegalStateException();
                }

                while (true)
                {
                    Operation op = operations.next();
                    op.timer.expectedStart(pacer.expectedStartTimeNsec());
                    if (!op.ready(workManager, pacer))
                        break;

                    try
                    {
                        switch (settings.mode.api)
                        {
                            case JAVA_DRIVER_NATIVE:
                                op.run(jclient);
                                break;
                            case SIMPLE_NATIVE:
                                op.run(sclient);
                                break;
                            case THRIFT:
                            case THRIFT_SMART:
                            default:
                                op.run(tclient);
                        }
                    }
                    catch (Exception e)
                    {
                        if (output == null)
                        {
                            System.err.println(e.getMessage());
                            success = false;
                            System.exit(-1);
                        }

                        e.printStackTrace(output);
                        success = false;
                        workManager.stop();
                        metrics.cancel();
                        return;
                    }
                }
            }
            finally
            {
                done.countDown();
                timer.close();
            }

        }

    }

    public class Pacer {
        private long initialStartTime;
        private double throughputInUnitsPerNsec;
        private long unitsCompleted;

        private boolean caughtUp = true;
        private long catchUpStartTime;
        private long unitsCompletedAtCatchUpStart;
        private double catchUpThroughputInUnitsPerNsec;
        private double catchUpRateMultiple;

        public Pacer(double unitsPerSec) {
            this(unitsPerSec, 3.0); // Default to catching up at 3x the set throughput
        }

        public Pacer(double unitsPerSec, double catchUpRateMultiple) {
            setThroughout(unitsPerSec);
            setCatchupRateMultiple(catchUpRateMultiple);
            initialStartTime = System.nanoTime();
        }

        public void setInitialStartTime(long initialStartTime) {
            this.initialStartTime = initialStartTime;
        }

        public void setThroughout(double unitsPerSec) {
            throughputInUnitsPerNsec = unitsPerSec / 1000000000.0;
            catchUpThroughputInUnitsPerNsec = catchUpRateMultiple * throughputInUnitsPerNsec;
        }

        public void setCatchupRateMultiple(double multiple) {
            catchUpRateMultiple = multiple;
            catchUpThroughputInUnitsPerNsec = catchUpRateMultiple * throughputInUnitsPerNsec;
        }

        /**
         * @return the time for the next operation
         */
        public long expectedStartTimeNsec() {
            return initialStartTime + (long)(unitsCompleted / throughputInUnitsPerNsec);
        }

        public long nsecToNextSend() {

            long now = System.nanoTime();

            long nextStartTime = expectedStartTimeNsec();

            boolean sendNow = true;

            if (nextStartTime > now) {
                // We are on pace. Indicate caught_up and don't send now.}
                caughtUp = true;
                sendNow = false;
            } else {
                // We are behind
                if (caughtUp) {
                    // This is the first fall-behind since we were last caught up
                    caughtUp = false;
                    catchUpStartTime = now;
                    unitsCompletedAtCatchUpStart = unitsCompleted;
                }

                // Figure out if it's time to send, per catch up throughput:
                long unitsCompletedSinceCatchUpStart =
                        unitsCompleted - unitsCompletedAtCatchUpStart;

                nextStartTime = catchUpStartTime +
                        (long)(unitsCompletedSinceCatchUpStart / catchUpThroughputInUnitsPerNsec);

                if (nextStartTime > now) {
                    // Not yet time to send, even at catch-up throughout:
                    sendNow = false;
                }
            }

            return sendNow ? 0 : (nextStartTime - now);
        }

        /**
         * Will wait for next operation time. After this the expectedStartTimeNsec() will move forward.
         * @param unitCount
         */
        public void acquire(long unitCount) {
            long nsecToNextSend = nsecToNextSend();
            if (nsecToNextSend > 0) {
                Timer.sleepNs(nsecToNextSend);
            }
            unitsCompleted += unitCount;
        }
    }

}
