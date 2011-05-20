/**
 * JBoss, Home of Professional Open Source
 * Copyright 2009, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.seam.cron.asynchronous.threads;

import org.jboss.seam.cron.asynchronous.spi.support.FutureInvokerSupport;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;

import org.jboss.logging.Logger;
import org.jboss.seam.cron.asynchronous.impl.exception.AsynchronousMethodExecutionException;
import org.jboss.seam.cron.asynchronous.spi.AsynchronousStrategy;
import org.jboss.seam.cron.asynchronous.spi.Invoker;
import org.jboss.seam.cron.scheduling.impl.exception.SchedulerInitialisationException;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerUtils;
import org.quartz.impl.DirectSchedulerFactory;
import org.quartz.simpl.RAMJobStore;
import org.quartz.simpl.SimpleThreadPool;
import org.quartz.spi.JobStore;
import org.quartz.spi.ThreadPool;

/**
 * Simple asynchronous method invocation which schedules @Asynchronous methods
 * to be executed at some point in the very near future using the Quartz scheduler.
 *
 * @author Peter Royle
 */
@ApplicationScoped
public class QuartzAsynchStrategy implements AsynchronousStrategy {

    /**
     * The name of the job group for all arbitrarily scheduled events.
     */
    public static final String ASYNC_JOB_GROUP = "async_job_group";
    public static final String INV_CONTEXT_EXECUTOR = "inv_context_executor";
    public static final String DELAYED_RESULT_SUPPORT = "future";
    private static final String SCHEDULER_NAME_PREFIX = "SeamCronScheduler";
    private String schedulerName;
    private Scheduler scheduler;
    private UUID instanceId;
    private static final Logger log = Logger.getLogger(QuartzAsynchStrategy.class);
    @Inject
    BeanManager beanManager;

    /**
     * Initialises the scheduler.
     *
     */
    public void initMethodInvoker() {
        try {
            instanceId = UUID.randomUUID();
            JobStore jobStore = new RAMJobStore();
            ThreadPool threadPool = new SimpleThreadPool(4, Thread.NORM_PRIORITY);
            threadPool.initialize();
            final DirectSchedulerFactory schedulerFactory = DirectSchedulerFactory.getInstance();
            schedulerName = SCHEDULER_NAME_PREFIX + "_" + instanceId.toString();
            schedulerFactory.createScheduler(schedulerName, instanceId.toString(), threadPool, jobStore);
            scheduler = schedulerFactory.getScheduler(schedulerName);
            scheduler.start();
        } catch (SchedulerException ex) {
            throw new SchedulerInitialisationException("Error initialising Quartz for asynchronous method invocation");
        }
    }

    /**
     * Shutdown the scheduler on application close.
     */
    public void shutdownMethodInvoker() {
        try {
            scheduler.shutdown();
        } catch (SchedulerException ex) {
            log.error("Error shutting down scheduler", ex);
        }
    }

    public void executeWithoutReturn(final Invoker inkover) {
        executeMethodAsScheduledJob(inkover);
    }

    public Future executeAndReturnFuture(final Invoker invoker) {
        FutureTask asyncResult = new FutureTask(executeMethodAsScheduledJob(invoker));
        new Thread(asyncResult).start();
        return asyncResult;
    }

    private FutureInvokerSupport executeMethodAsScheduledJob(final Invoker invoker) throws AsynchronousMethodExecutionException {
        final FutureInvokerSupport drs = new FutureInvokerSupport(invoker);
        try {
            final String name = UUID.randomUUID().toString();
            JobDetail jobDetail = new JobDetail(name, ASYNC_JOB_GROUP, AsyncMethodInvocationJob.class);
            jobDetail.getJobDataMap().put(DELAYED_RESULT_SUPPORT, drs);
            scheduler.scheduleJob(jobDetail, TriggerUtils.makeImmediateTrigger(name, 0, 1));
        } catch (SchedulerException ex) {
            throw new AsynchronousMethodExecutionException("Error invoking method asynchronously", ex);
        }
        return drs;
    }
}
