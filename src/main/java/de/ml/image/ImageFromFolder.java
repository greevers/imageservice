package de.ml.image;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Qualifier;
import javax.inject.Singleton;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.ThreadPoolBuilder;
import org.slf4j.Logger;

import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import de.ml.boot.ArgsConfiguration.Folder;
import de.ml.image.ImageFromFolder.ImageProviderImpl;

@Singleton
@ImageProviderImpl
public class ImageFromFolder implements ImageProvider, Processor {

    private List<File> folders;

    private Iterator<Path> currentSortedIterator;

    private String currentIteratorName;

    private volatile List<Path> files = Lists.newArrayList();

    private Logger log;

    private Random random = new Random();

    private ExecutorService exec;

    private Map<File,Future<Void>> currentTasks = Maps.newHashMap();

    private Cache<String, List<Path>> cache;

    @Inject
    private ImageFromFolder(@Folder List<File> folders, Logger log, CamelContext context) {
        this.folders = folders;
        this.log = log;
        try {
            exec = new ThreadPoolBuilder(context).poolSize(1).maxPoolSize(folders.size()).build("fetch files");
        } catch (Exception e) {
            throw new IllegalStateException("Problem on creating executor: ", e);
        }
        setupCache();
        fetchAllFiles();
    }

    private void setupCache() {
        cache = CacheBuilder.newBuilder().build();
    }

    @Override
    public File getRandom() {
        return getRandomOf(files).toFile();
    }

    private Path getRandomOf(List<Path> list) {
        if (list.size() > 0) {
            int index = random.nextInt(list.size());
            Path file = list.get(index);
            if (file.toFile().canRead()) {
                return file;
            } else {
                log.info("File " + file + " not found.");
                return null;
            }
        } else {
            return null;
        }
    }

    private void fetchAllFiles() {
        files.clear();
        cache.invalidateAll();
        for (File folder : folders) {
            Future<Void> currentTask = currentTasks.get(folder);
            if (currentTask == null || currentTask.isDone() || currentTask.isCancelled()) {
                currentTask = exec.submit(new FetchFilesTask(folder));
                currentTasks.put(folder, currentTask);
            } else {
                log.info("Reject to update folder "+folder +", previous update not finished yet.");
            }
        }
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        int oldFileCount = files.size();
        fetchAllFiles();
        //wait for result
        waitUntilFinished();
        String message = "Update yielded " + (files.size()-oldFileCount) + " files. Total file count now: " + files.size();
        log.info(message);
        exchange.getIn().setBody(message);
    }

    private void waitUntilFinished() {
        for (Future<Void> task : currentTasks.values()) {
            try {
                task.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new IllegalStateException("Problem on waiting for file tasks: ",e);
            }
        }
    }

    private class FetchFilesTask implements Callable<Void> {

        private File folder;

        private FetchFilesTask(File folder){
            this.folder = folder;
        }

        @Override
        public Void call() throws Exception {
            log.info("Starting file update on " +  folder);
            Stopwatch stopwatch = Stopwatch.createStarted();
            int oldCount =  files.size();
            try {
                Files.walkFileTree(folder.toPath(), new SimpleFileVisitor<Path>() {

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!attrs.isDirectory() && file.getFileName().toString().toLowerCase().endsWith("jpg")) {
                            files.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                });
            } catch (IOException e) {
                throw new IllegalArgumentException("Problem reading folder: ", e);
            }
            stopwatch.stop();
            log.info("... " + (files.size() - oldCount) + " files found in " + folder + " in "
                     + stopwatch.elapsed(TimeUnit.SECONDS)
                     + "s. Total file count: " + files.size());
            return null;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + getOuterType().hashCode();
            result = prime * result + ((folder == null) ? 0 : folder.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (!(obj instanceof FetchFilesTask)) {
                return false;
            }
            FetchFilesTask other = (FetchFilesTask) obj;
            if (!getOuterType().equals(other.getOuterType())) {
                return false;
            }
            if (folder == null) {
                if (other.folder != null) {
                    return false;
                }
            } else if (!folder.equals(other.folder)) {
                return false;
            }
            return true;
        }

        private ImageFromFolder getOuterType() {
            return ImageFromFolder.this;
        }



    }

    private List<Path> getFilesWithNameContains(String inName) {
        log.info("fetching files with " + inName + " in path...");
        ArrayList<Path> result = Lists.newArrayList();
        waitUntilFinished();
        for (Path path : files) {
            if (path.toString().toLowerCase().contains(inName.toLowerCase())) {
                result.add(path);
            }
        }
        return result;
    }


    @Override
    public File getWithName(String inName) {
            Path file = getRandomOf(getCachedWithName(inName));
            return file == null ? null : file.toFile();
    }

    @Override
    public int getCountWithName(String inName) {
            return getCachedWithName(inName).size();
    }


    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE})
    public @interface ImageProviderImpl {

    }

    @Override
    public File getWithNameSort(String inName) {
        if(Strings.isNullOrEmpty(inName)){
            throw new IllegalArgumentException("inName must be set to a non emptz string.");
        }
        if(!inName.toLowerCase().equals(currentIteratorName)){
            List<Path> list = getCachedWithName(inName);
            Collections.sort(list);
            currentSortedIterator = Iterators.cycle(list);
            currentIteratorName = inName.toLowerCase();
        }
        return currentSortedIterator.next().toFile();
    }

    private List<Path> getCachedWithName(String inName){
        try {
            return cache.get(inName, () -> getFilesWithNameContains(inName));
        } catch (ExecutionException e) {
            throw new IllegalStateException("Problem on loading list from cache: ", e);
        }
    }

}
