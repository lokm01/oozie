/**
 * Copyright (c) 2010 Yahoo! Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License. See accompanying LICENSE file.
 */
package org.apache.oozie.service;

import java.util.Date;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.oozie.BundleJobBean;
import org.apache.oozie.CoordinatorJobBean;
import org.apache.oozie.command.bundle.BundlePauseXCommand;
import org.apache.oozie.command.bundle.BundleStartXCommand;
import org.apache.oozie.command.bundle.BundleUnpauseXCommand;
import org.apache.oozie.command.coord.CoordPauseXCommand;
import org.apache.oozie.command.coord.CoordUnpauseXCommand;
import org.apache.oozie.executor.jpa.BundleJobsGetNeedStartJPAExecutor;
import org.apache.oozie.executor.jpa.BundleJobsGetPausedJPAExecutor;
import org.apache.oozie.executor.jpa.BundleJobsGetUnpausedJPAExecutor;
import org.apache.oozie.executor.jpa.CoordJobsGetPausedJPAExecutor;
import org.apache.oozie.executor.jpa.CoordJobsGetUnpausedJPAExecutor;
import org.apache.oozie.service.SchedulerService;
import org.apache.oozie.service.Service;
import org.apache.oozie.service.Services;
import org.apache.oozie.util.MemoryLocks;
import org.apache.oozie.util.XLog;

/**
 * BundlePauseStartService is the runnable which is scheduled to run at the configured interval, it checks all bundles
 * to see if they should be paused, un-paused or started.
 */
public class BundlePauseStartService implements Service {
    public static final String CONF_PREFIX = Service.CONF_PREFIX + "BundlePauseStartService.";
    public static final String CONF_BUNDLE_PAUSE_START_INTERVAL = CONF_PREFIX + "BundlePauseStart.interval";
    private final static XLog LOG = XLog.getLog(BundlePauseStartService.class);

    /**
     * BundlePauseStartRunnable is the runnable which is scheduled to run at the configured interval, it checks all
     * bundles to see if they should be paused, un-paused or started.
     */
    static class BundlePauseStartRunnable implements Runnable {
        private JPAService jpaService = null;
        private MemoryLocks.LockToken lock;

        public BundlePauseStartRunnable() {
            jpaService = Services.get().get(JPAService.class);
            if (jpaService == null) {
                LOG.error("Missing JPAService");
            }
        }

        public void run() {
            try {
                // first check if there is some other running instance from the same service;
                lock = Services.get().get(MemoryLocksService.class).getWriteLock(
                        BundlePauseStartService.class.getName(), lockTimeout);
                if (lock == null) {
                    LOG.info("This BundlePauseStartService instance will"
                            + "not run since there is already an instance running");
                }
                else {
                    LOG.info("Acquired lock for [{0}]", BundlePauseStartService.class.getName());

                    updateBundle();
                    updateCoord();
                }
            }
            catch (Exception ex) {
                LOG.warn("Exception happened when pausing/unpausing/starting bundle/coord jobs", ex);
            }
            finally {
                // release lock;
                if (lock != null) {
                    lock.release();
                    LOG.info("Released lock for [{0}]", BundlePauseStartService.class.getName());
                }
            }
        }

        private void updateBundle() {
            Date d = new Date(); // records the start time of this service run;
            List<BundleJobBean> jobList = null;
            // pause bundles as needed;
            try {
                jobList = jpaService.execute(new BundleJobsGetUnpausedJPAExecutor(-1));
                if (jobList != null) {
                    for (BundleJobBean bundleJob : jobList) {
                        if ((bundleJob.getPauseTime() != null) && !bundleJob.getPauseTime().after(d)) {
                            new BundlePauseXCommand(bundleJob).call();
                            LOG.debug("Calling BundlePauseXCommand for bundle job = " + bundleJob.getId());
                        }
                    }
                }
            }
            catch (Exception ex) {
                LOG.warn("Exception happened when pausing/unpausing/starting Bundle jobs", ex);
            }
            // unpause bundles as needed;
            try {
                jobList = jpaService.execute(new BundleJobsGetPausedJPAExecutor(-1));
                if (jobList != null) {
                    for (BundleJobBean bundleJob : jobList) {
                        if ((bundleJob.getPauseTime() == null || bundleJob.getPauseTime().after(d))) {
                            new BundleUnpauseXCommand(bundleJob).call();
                            LOG.debug("Calling BundleUnpauseXCommand for bundle job = " + bundleJob.getId());
                        }
                    }
                }
            }
            catch (Exception ex) {
                LOG.warn("Exception happened when pausing/unpausing/starting Bundle jobs", ex);
            }
            // start bundles as needed;
            try {
                jobList = jpaService.execute(new BundleJobsGetNeedStartJPAExecutor(d));
                if (jobList != null) {
                    for (BundleJobBean bundleJob : jobList) {
                        bundleJob.setKickoffTime(d);
                        new BundleStartXCommand(bundleJob.getId()).call();
                        LOG.debug("Calling BundleStartXCommand for bundle job = " + bundleJob.getId());
                    }
                }
            }
            catch (Exception ex) {
                LOG.warn("Exception happened when pausing/unpausing/starting Bundle jobs", ex);
            }
        }

        private void updateCoord() {
            Date d = new Date(); // records the start time of this service run;
            List<CoordinatorJobBean> jobList = null;
            // pause coordinators as needed;
            try {
                jobList = jpaService.execute(new CoordJobsGetUnpausedJPAExecutor(-1));
                if (jobList != null) {
                    for (CoordinatorJobBean coordJob : jobList) {
                        if ((coordJob.getPauseTime() != null) && !coordJob.getPauseTime().after(d)) {
                            new CoordPauseXCommand(coordJob).call();
                            LOG.debug("Calling CoordPauseXCommand for coordinator job = " + coordJob.getId());
                        }
                    }
                }
            }
            catch (Exception ex) {
                LOG.warn("Exception happened when pausing/unpausing Coordinator jobs", ex);
            }
            // unpause coordinators as needed;
            try {
                jobList = jpaService.execute(new CoordJobsGetPausedJPAExecutor(-1));
                if (jobList != null) {
                    for (CoordinatorJobBean coordJob : jobList) {
                        if ((coordJob.getPauseTime() == null || coordJob.getPauseTime().after(d))) {
                            new CoordUnpauseXCommand(coordJob).call();
                            LOG.debug("Calling CoordUnpauseXCommand for coordinator job = " + coordJob.getId());
                        }
                    }
                }
            }
            catch (Exception ex) {
                LOG.warn("Exception happened when pausing/unpausing Coordinator jobs", ex);
            }
        }
    }

    /**
     * Initializes the {@link BundlePauseStartService}.
     *
     * @param services services instance.
     */
    @Override
    public void init(Services services) {
        Configuration conf = services.getConf();
        Runnable bundlePauseStartRunnable = new BundlePauseStartRunnable();
        services.get(SchedulerService.class).schedule(bundlePauseStartRunnable, 10,
                conf.getInt(CONF_BUNDLE_PAUSE_START_INTERVAL, 60), SchedulerService.Unit.SEC);
    }

    /**
     * Destroy the StateTransit Jobs Service.
     */
    @Override
    public void destroy() {
    }

    /**
     * Return the public interface for the purge jobs service.
     *
     * @return {@link BundlePauseStartService}.
     */
    @Override
    public Class<? extends Service> getInterface() {
        return BundlePauseStartService.class;
    }
}
