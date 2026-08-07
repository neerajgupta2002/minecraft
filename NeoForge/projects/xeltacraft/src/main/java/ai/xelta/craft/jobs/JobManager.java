package ai.xelta.craft.jobs;

import net.neoforged.fml.loading.FMLPaths;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JobManager {

    private static final Map<String, String> ACTIVE_JOBS = new ConcurrentHashMap<>();
    
    private static File getJobsFile() {
        return new File(FMLPaths.GAMEDIR.get().toFile(), "xelta_jobs.json");
    }

    /**
     * Register an active API job.
     */
    public static void registerJob(String jobId, String jobType) {
        ACTIVE_JOBS.put(jobId, jobType);
        saveJobs();
    }

    /**
     * Mark a job as complete, removing it from the persistent queue.
     */
    public static void completeJob(String jobId) {
        ACTIVE_JOBS.remove(jobId);
        saveJobs();
    }

    /**
     * Persist the active jobs to disk so they survive a game restart.
     */
    private static void saveJobs() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            int count = 0;
            for (Map.Entry<String, String> entry : ACTIVE_JOBS.entrySet()) {
                sb.append("  \"").append(entry.getKey()).append("\": \"").append(entry.getValue()).append("\"");
                if (++count < ACTIVE_JOBS.size()) {
                    sb.append(",\n");
                } else {
                    sb.append("\n");
                }
            }
            sb.append("}\n");
            Files.writeString(getJobsFile().toPath(), sb.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
