/*
 * Copyright 2014-2018 Chronicle Software
 *
 * http://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.threads.Threads;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public enum QueueFileShrinkManager {
    ;
    public static final String THREAD_NAME = "queue-file-shrink-daemon";
    public static final boolean RUN_SYNCHRONOUSLY = Boolean.getBoolean("chronicle.queue.synchronousFileShrinking");
    public static final boolean DISABLE_QUEUE_FILE_SHRINKING = OS.isWindows() || Boolean.getBoolean("chronicle.queue.disableFileShrinking");

    private static final Logger LOG = LoggerFactory.getLogger(QueueFileShrinkManager.class);
    private static final ScheduledExecutorService EXECUTOR = Threads.acquireScheduledExecutorService(THREAD_NAME, true);
    private static final long DELAY_S = 10;

    public static void scheduleShrinking(@NotNull final File queueFile, final long writePos) {
        if (DISABLE_QUEUE_FILE_SHRINKING)
            return;
        if (RUN_SYNCHRONOUSLY)
            task(queueFile, writePos);
        else {
            // The shrink is deferred a bit to allow any potentially lagging tailers/pre-touchers
            // to move on to the next roll before the file can be safely shrunk.
            // See https://github.com/ChronicleEnterprise/Chronicle-Queue-Enterprise/issues/103
            EXECUTOR.schedule(() -> task(queueFile, writePos), DELAY_S, TimeUnit.SECONDS);
        }

    }

    private static void task(@NotNull final File queueFile, final long writePos) {
        if (LOG.isDebugEnabled())
            LOG.debug("Shrinking {} to {}", queueFile, writePos);
        int timeout = 50;
        for (int i = OS.isWindows() ? 1 : 3; i >= 0; i--) {
            if (!queueFile.exists()) {
                LOG.warn("Failed to shrink file as it no-longer existing, file=" + queueFile);
                return;
            }
            try (RandomAccessFile raf = new RandomAccessFile(queueFile, "rw")) {
                raf.setLength(writePos);

            } catch (IOException ex) {
                // on microsoft windows, keep retrying until the file is unmapped
                if (ex.getMessage().contains("The requested operation cannot be performed on a file with a user-mapped section open")) {
                    LOG.debug("Failed to shrinking {} to {}, {}", queueFile, writePos, i == 0 ? "giving up" : "retrying");
                    Jvm.pause(timeout);
                    timeout *= 2;
                    continue;
                }
                LOG.warn("Failed to shrink file " + queueFile, ex);
            }
            break;
        }
    }

}