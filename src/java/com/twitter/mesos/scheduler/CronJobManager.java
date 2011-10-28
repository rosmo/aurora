package com.twitter.mesos.scheduler;

import it.sauronsoftware.cron4j.InvalidPatternException;
import it.sauronsoftware.cron4j.Scheduler;
import it.sauronsoftware.cron4j.SchedulingPattern;

import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;

import org.apache.commons.lang.StringUtils;

import com.twitter.common.args.Arg;
import com.twitter.common.args.CmdLine;
import com.twitter.common.base.Supplier;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.stats.Stats;
import com.twitter.common.util.BackoffHelper;
import com.twitter.mesos.Tasks;
import com.twitter.mesos.gen.CronCollisionPolicy;
import com.twitter.mesos.gen.JobConfiguration;
import com.twitter.mesos.gen.TaskQuery;
import com.twitter.mesos.scheduler.storage.Storage;
import com.twitter.mesos.scheduler.storage.Storage.Work;

/**
 * A job scheduler that receives jobs that should be run periodically on a cron schedule.
 *
 * TODO(William Farner): Some more work might be required here.  For example, when the cron job is
 * triggered, we may want to see if the same job is still running and allow the configuration to
 * specify the policy for when that occurs (i.e. overlap or kill the existing job).
 *
 * @author William Farner
 */
public class CronJobManager extends JobManager {
  private static Logger LOG = Logger.getLogger(CronJobManager.class.getName());

  private static final String MANAGER_KEY = "CRON";

  @VisibleForTesting
  static final String CRON_USER = "cron";

  @CmdLine(name = "cron_start_initial_backoff", help =
      "Initial backoff delay while waiting for a previous cron run to start.")
  private static final Arg<Amount<Long, Time>> CRON_START_INITIAL_BACKOFF =
      Arg.create(Amount.of(1L, Time.SECONDS));

  @CmdLine(name = "cron_start_max_backoff", help =
      "Max backoff delay while waiting for a previous cron run to start.")
  private static final Arg<Amount<Long, Time>> CRON_START_MAX_BACKOFF =
      Arg.create(Amount.of(1L, Time.MINUTES));

  // Cron manager.
  private final Scheduler scheduler = new Scheduler();

  private final AtomicLong cronJobsTriggered = Stats.exportLong("cron_jobs_triggered");

  // Maps from the our unique job identifier (<role>/<jobName>) to the unique identifier used
  // internally by the cron4j scheduler.
  private final Map<String, String> scheduledJobs =
      Collections.synchronizedMap(Maps.<String, String>newHashMap());

  // Prevents runs from dogpiling while waiting for a run to transition out of the KILLING state.
  // This is necessary because killing a job (if dictated by cron collision policy) is an
  // asynchronous operation.
  private final Map<String, JobConfiguration> pendingRuns =
      Collections.synchronizedMap(Maps.<String, JobConfiguration>newHashMap());
  private final BackoffHelper delayedStartBackoff;
  private final ExecutorService delayedRunExecutor;

  private final Storage storage;

  @Inject
  public CronJobManager(Storage storage) {
    this.storage = Preconditions.checkNotNull(storage);
    this.delayedStartBackoff =
        new BackoffHelper(CRON_START_INITIAL_BACKOFF.get(), CRON_START_MAX_BACKOFF.get());
    this.delayedRunExecutor = Executors.newCachedThreadPool(
        new ThreadFactoryBuilder().setDaemon(true).setNameFormat("CronDelay-%d").build());

    scheduler.setDaemon(true);
    scheduler.start();

    Stats.exportSize("cron_num_pending_runs", pendingRuns);
  }

  private void mapScheduledJob(JobConfiguration job, String scheduledJobKey) {
    scheduledJobs.put(Tasks.jobKey(job), scheduledJobKey);
  }

  @Override
  public void start() {
    storage.doInTransaction(new Work.NoResult.Quiet() {
      @Override protected void execute(Storage.StoreProvider storeProvider) {

        for (JobConfiguration job : storeProvider.getJobStore().fetchJobs(MANAGER_KEY)) {
          try {
            String scheduledJobKey = scheduleJob(job);
            mapScheduledJob(job, scheduledJobKey);
          } catch (ScheduleException e) {
            LOG.log(Level.SEVERE, "While trying to restore state, scheduler module failed.", e);
          }
        }
      }
    });
  }

  /**
   * Triggers execution of a job.
   *
   * @param jobKey Key of the job to start.
   */
  public void startJobNow(final String jobKey) {
    Preconditions.checkNotNull(jobKey);

    JobConfiguration job = fetchJob(jobKey);
    Preconditions.checkArgument(job != null, "No such cron job " + jobKey);

    cronTriggered(job);
  }

  private void delayedRun(final Query query, final JobConfiguration job) {
    final String jobKey = Tasks.jobKey(job);
    LOG.info("Waiting for job to terminate before launching cron job " + jobKey);
    if (pendingRuns.put(jobKey, job) == null) {
      // There was no run already pending for this job, launch a task to delay launch until the
      // existing run has terminated.
      delayedRunExecutor.submit(new Runnable() {
        @Override public void run() {
          runWhenTerminated(query, jobKey);
        }
      });
    }
  }

  private void runWhenTerminated(final Query query, final String jobKey) {
    try {
      delayedStartBackoff.doUntilSuccess(new Supplier<Boolean>() {
        @Override public Boolean get() {
          if (!hasTasks(query)) {
            LOG.info("Initiating delayed launch of cron " + jobKey);
            JobConfiguration job = pendingRuns.remove(jobKey);
            Preconditions.checkNotNull(job, "Failed to fetch job for delayed run of " + jobKey);
            schedulerCore.runJob(job);
            return true;
          } else {
            LOG.info("Not yet safe to run cron " + jobKey);
            return false;
          }
        }
      });
    } catch (InterruptedException e) {
      LOG.log(Level.WARNING, "Interrupted while trying to launch cron " + jobKey, e);
      Thread.currentThread().interrupt();
    }
  }

  private boolean hasTasks(Query query) {
    return !schedulerCore.getTasks(query).isEmpty();
  }

  /**
   * Triggers execution of a cron job, depending on the cron collision policy for the job.
   *
   * @param job The config of the job to be triggered.
   */
  @VisibleForTesting
  void cronTriggered(JobConfiguration job) {
    LOG.info(String.format("Cron triggered for %s at %s", Tasks.jobKey(job), new Date()));
    cronJobsTriggered.incrementAndGet();

    boolean runJob = false;

    Query query = new Query(new TaskQuery().setOwner(job.getOwner()).setJobName(job.getName())
        .setStatuses(Tasks.ACTIVE_STATES));

    if (!hasTasks(query)) {
      runJob = true;
    } else {
      // Assign a default collision policy.
      CronCollisionPolicy collisionPolicy = (job.getCronCollisionPolicy() == null)
          ? CronCollisionPolicy.KILL_EXISTING
          : job.getCronCollisionPolicy();

      switch (collisionPolicy) {
        case KILL_EXISTING:
          LOG.info("Cron collision policy requires killing existing job.");
          try {
            schedulerCore.killTasks(query, CRON_USER);
            // Check immediately if the tasks are gone.  This could happen if the existing tasks
            // were pending.
            if (!hasTasks(query)) {
              runJob = true;
            } else {
              delayedRun(query, job);
            }
          } catch (ScheduleException e) {
            LOG.log(Level.SEVERE, "Failed to kill job.", e);
          }

          break;
        case CANCEL_NEW:
          LOG.info("Cron collision policy prevented job from running.");
          break;
        case RUN_OVERLAP:
          LOG.info("Cron collision policy permitting overlapping job run.");
          runJob = true;
          break;
        default:
          LOG.severe("Unrecognized cron collision policy: " + job.getCronCollisionPolicy());
      }
    }

    if (runJob) {
      schedulerCore.runJob(job);
    }
  }

  @Override
  public String getUniqueKey() {
    return MANAGER_KEY;
  }

  private static boolean hasCronSchedule(JobConfiguration job) {
    return !StringUtils.isEmpty(job.getCronSchedule());
  }

  @Override
  public boolean receiveJob(final JobConfiguration job) throws ScheduleException {
    Preconditions.checkNotNull(job);

    if (!hasCronSchedule(job)) {
      return false;
    }

    String scheduledJobKey = scheduleJob(job);
    storage.doInTransaction(new Work.NoResult.Quiet() {
      @Override protected void execute(Storage.StoreProvider storeProvider) {
        storeProvider.getJobStore().saveAcceptedJob(MANAGER_KEY, job);
      }
    });
    mapScheduledJob(job, scheduledJobKey);

    return true;
  }

  private String scheduleJob(final JobConfiguration job) throws ScheduleException {
    if (!hasCronSchedule(job)) {
      throw new ScheduleException(String.format("Not a valid cronjob, %s has no cron schedule",
          Tasks.jobKey(job)));
    }

    if (!validateSchedule(job.getCronSchedule())) {
      throw new ScheduleException("Invalid cron schedule: " + job.getCronSchedule());
    }

    LOG.info(String.format("Scheduling cron job %s: %s", Tasks.jobKey(job), job.getCronSchedule()));
    try {
      return scheduler.schedule(job.getCronSchedule(), new Runnable() {
        @Override public void run() {
          // TODO(William Farner): May want to record information about job runs.
          LOG.info("Running cron job: " + Tasks.jobKey(job));
          cronTriggered(job);
        }
      });
    } catch (InvalidPatternException e) {
      throw new ScheduleException("Failed to schedule cron job: " + e.getMessage(), e);
    }
  }

  private boolean validateSchedule(String cronSchedule) {
    return SchedulingPattern.validate(cronSchedule);
  }

  @Override
  public Iterable<JobConfiguration> getJobs() {
    return storage.doInTransaction(new Work.Quiet<Iterable<JobConfiguration>>() {
      @Override public Iterable<JobConfiguration> apply(Storage.StoreProvider storeProvider) {

        return storeProvider.getJobStore().fetchJobs(MANAGER_KEY);
      }
    });
  }

  @Override
  public boolean hasJob(final String jobKey) {
    Preconditions.checkNotNull(jobKey);

    return fetchJob(jobKey) != null;
  }

  private JobConfiguration fetchJob(final String jobKey) {
    return storage.doInTransaction(new Work.Quiet<JobConfiguration>() {
      @Override public JobConfiguration apply(Storage.StoreProvider storeProvider) {

        return storeProvider.getJobStore().fetchJob(MANAGER_KEY, jobKey);
      }
    });
  }

  @Override
  public boolean deleteJob(final String jobKey) {
    Preconditions.checkNotNull(jobKey);

    if (!hasJob(jobKey)) {
      return false;
    }

    String scheduledJobKey = scheduledJobs.remove(jobKey);
    if (scheduledJobKey != null) {
      scheduler.deschedule(scheduledJobKey);
      storage.doInTransaction(new Work.NoResult.Quiet() {
        @Override protected void execute(Storage.StoreProvider storeProvider) {
          storeProvider.getJobStore().removeJob(jobKey);
        }
      });
      LOG.info("Successfully deleted cron job " + jobKey);
    }
    return true;
  }
}
