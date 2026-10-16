package org.hopto.git23069.textpad;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Joins the same serialized sync queue as saves and foreground notifications. */
public final class SyncWorker extends Worker {
    public SyncWorker(@NonNull Context context,@NonNull WorkerParameters parameters) { super(context,parameters); }
    @NonNull @Override public Result doWork() {
        TextpadApplication app=(TextpadApplication)getApplicationContext();
        CompletableFuture<Boolean> completion=new CompletableFuture<>();
        app.main.post(() -> {
            try {
                if(isStopped()) { completion.complete(true); return; }
                app.sync.syncFromWorker(this::isStopped).whenComplete((retry,error) -> {
                    if(error!=null) completion.completeExceptionally(error); else completion.complete(retry);
                });
            } catch(Exception error) { completion.completeExceptionally(error); }
        });
        try { return completion.get()?Result.retry():Result.success(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); return Result.retry(); }
        catch(ExecutionException e) { return Result.retry(); }
    }
}
