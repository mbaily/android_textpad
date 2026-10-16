package org.hopto.git23069.textpad;

import android.content.Context;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

/** Persisted jobs contain no credentials or file contents; the worker reads current settings. */
final class SyncScheduler {
    static final String PERIODIC="textpad-periodic-sync", RETRY="textpad-sync-retry";
    private static Constraints connected() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }
    static Operation configure(Context context,boolean enabled) {
        WorkManager work=WorkManager.getInstance(context);
        if(!enabled) { work.cancelUniqueWork(PERIODIC); return work.cancelUniqueWork(RETRY); }
        PeriodicWorkRequest periodic=new PeriodicWorkRequest.Builder(SyncWorker.class,15,TimeUnit.MINUTES)
                .setConstraints(connected()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
        return work.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,periodic);
    }
    static void retry(Context context) {
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(SyncWorker.class).setConstraints(connected())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork(RETRY,ExistingWorkPolicy.KEEP,request);
    }
}
