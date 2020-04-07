package com.nccgroup.loggerplusplus.logview.processor;

import burp.*;
import com.coreyd97.BurpExtenderUtilities.Preferences;
import com.nccgroup.loggerplusplus.LoggerPlusPlus;
import com.nccgroup.loggerplusplus.filter.colorfilter.ColorFilter;
import com.nccgroup.loggerplusplus.logentry.LogEntry;
import com.nccgroup.loggerplusplus.logentry.LogManagerHelper;
import com.nccgroup.loggerplusplus.logentry.Status;
import com.nccgroup.loggerplusplus.logview.logtable.LogTableController;
import com.nccgroup.loggerplusplus.util.NamedThreadFactory;
import com.nccgroup.loggerplusplus.util.PausableThreadPoolExecutor;

import javax.swing.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

import static com.nccgroup.loggerplusplus.util.Globals.*;

/**
 * Created by corey on 07/09/17.
 */
public class LogProcessor implements IHttpListener, IProxyListener {
    public static final SimpleDateFormat LOGGER_DATE_FORMAT = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
    public static final SimpleDateFormat SERVER_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");

    private final LoggerPlusPlus loggerPlusPlus;
    private final LogTableController logTableController;
    private final Preferences preferences;
    private final ConcurrentHashMap<Integer, UUID> proxyIdToUUIDMap;
    private final ConcurrentHashMap<UUID, LogEntry> entriesPendingProcessing;
    private final ConcurrentHashMap<UUID, Future<LogEntry>> entryProcessingFutures;
    private final PausableThreadPoolExecutor entryProcessExecutor;
    private final PausableThreadPoolExecutor entryImportExecutor;
    private final ScheduledExecutorService cleanupExecutor;
    private final String instanceIdentifier = String.format("%02d", (int)Math.floor((Math.random()*100)));

    /**
     * Capture incoming requests and responses.
     * Logic to allow requests independently and match them to responses once received.
     * TODO SQLite integration
     * TODO Capture requests modified after logging using request obtained from response objects.
     */
    public LogProcessor(LoggerPlusPlus loggerPlusPlus, LogTableController logTableController){
        this.loggerPlusPlus = loggerPlusPlus;
        this.logTableController = logTableController;
        this.preferences = this.loggerPlusPlus.getPreferencesController().getPreferences();

        this.proxyIdToUUIDMap = new ConcurrentHashMap<>();
        this.entriesPendingProcessing = new ConcurrentHashMap<>();
        this.entryProcessingFutures = new ConcurrentHashMap<>();
        this.entryProcessExecutor = new PausableThreadPoolExecutor(0, 2147483647,
                30L, TimeUnit.SECONDS, new SynchronousQueue<>(), new NamedThreadFactory("LPP-LogManager"));
        this.entryImportExecutor = new PausableThreadPoolExecutor(0, 10, 60L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), new NamedThreadFactory("LPP-Import"));

        //Create incomplete request cleanup thread so map doesn't get too big.
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("LPP-LogManager-Cleanup"));
        this.cleanupExecutor.scheduleAtFixedRate(new AbandonedRequestCleanupRunnable(),30, 30, TimeUnit.SECONDS);

        LoggerPlusPlus.callbacks.registerHttpListener(this);
        LoggerPlusPlus.callbacks.registerProxyListener(this);
    }

    /**
     * Process messages from all tools but proxy.
     * Adds to queue for later processing.
     * @param toolFlag Tool used to make request
     * @param isRequestOnly If the message is request only or complete with response
     * @param httpMessage The request and potentially response received.
     */
    @Override
    public void processHttpMessage(final int toolFlag, final boolean isRequestOnly, final IHttpRequestResponse httpMessage) {
        if(toolFlag == IBurpExtenderCallbacks.TOOL_PROXY) return; //Proxy messages handled by proxy method
        if(httpMessage == null || !(Boolean) preferences.getSetting(PREF_ENABLED) || !isValidTool(toolFlag)) return;
        Date arrivalTime = new Date();

        if(!(Boolean) preferences.getSetting(PREF_LOG_OTHER_LIVE)){
            //Submit normally, we're not tracking requests and responses separately.
            if(!isRequestOnly) { //But only add entries complete with a response.
                final LogEntry logEntry = new LogEntry(toolFlag, arrivalTime, httpMessage);
                submitNewEntryProcessingRunnable(logEntry);
            }
            return;
        }

        if(isRequestOnly){
            final LogEntry logEntry = new LogEntry(toolFlag, arrivalTime, httpMessage);
            //Tag the request with the UUID in the comment field, as this persists for when we get the response back!
            LogManagerHelper.tagRequestResponseWithUUID(instanceIdentifier, logEntry.getIdentifier(), httpMessage);
            submitNewEntryProcessingRunnable(logEntry);
        }else{
            UUID uuid = LogManagerHelper.extractAndRemoveUUIDFromRequestResponseComment(instanceIdentifier, httpMessage);
            if(uuid != null) {
                updateRequestWithResponse(uuid, arrivalTime, httpMessage);
            }
        }
    }

    /**
     * Process messages received from the proxy tool.
     * For requests, a new processing job is added to the executor.
     * @param isRequestOnly
     * @param proxyMessage
     */
    @Override
    public void processProxyMessage(final boolean isRequestOnly, final IInterceptedProxyMessage proxyMessage) {
        final int toolFlag = IBurpExtenderCallbacks.TOOL_PROXY;
        if(proxyMessage == null || !(Boolean) preferences.getSetting(PREF_ENABLED) || !isValidTool(toolFlag)) return;
        Date arrivalTime = new Date();

        if(isRequestOnly){
            //The request is not yet sent, process the request object
            final LogEntry logEntry = new LogEntry(toolFlag, arrivalTime, proxyMessage.getMessageInfo());
            //Store our proxy specific info now.
            logEntry.clientIP = String.valueOf(proxyMessage.getClientIpAddress());
            logEntry.listenerInterface = proxyMessage.getListenerInterface();

            //Make a note of the entry UUID corresponding to the message identifier.
            proxyIdToUUIDMap.put(proxyMessage.getMessageReference(), logEntry.getIdentifier());
            submitNewEntryProcessingRunnable(logEntry);
        }else{
            //We're handling a response.
            UUID uuid = proxyIdToUUIDMap.remove(proxyMessage.getMessageReference());
            if(uuid != null){
                updateRequestWithResponse(uuid, arrivalTime, proxyMessage.getMessageInfo());
            }else{
                System.out.println("LOST");
            }
        }
    }

    /**
     * When a response comes in, determine if the request has already been processed or not.
     * If it has not yet been processed, add the response information to the entry and let the original job handle it.
     * Otherwise, create a new job to process the response.
     * Unknown UUID's signify the response arrived after the pending request was cleaned up.
     * @param entryIdentifier The unique UUID for the log entry.
     * @param arrivalTime The arrival time of the response.
     * @param requestResponse The HTTP request response object.
     */
    private void updateRequestWithResponse(UUID entryIdentifier, Date arrivalTime, IHttpRequestResponse requestResponse){
        if(entriesPendingProcessing.containsKey(entryIdentifier)){
            //Not yet started processing the entry, we can add the response so it is processed in the first pass
            final LogEntry logEntry = entriesPendingProcessing.get(entryIdentifier);
            //Update the requestResponse with the new one, and tell it when it arrived.
            logEntry.addResponse(requestResponse, arrivalTime);

            //Do nothing now, there's already a runnable submitted to process it somewhere in the queue.
            return;

        }else if(entryProcessingFutures.containsKey(entryIdentifier)){
            //Already started processing.

            //Get the processing thread
            Future<LogEntry> processingFuture = entryProcessingFutures.get(entryIdentifier);

            //Submit a job for the processing of its response.
            //This will block on the request finishing processing, then update the response and process it separately.
            entryProcessExecutor.submit(createEntryUpdateRunnable(processingFuture, requestResponse, arrivalTime));
        }else{
            //Unknown UUID. Potentially for a request which was ignored or cleaned up already?
        }
    }

    private void submitNewEntryProcessingRunnable(final LogEntry logEntry){
        entriesPendingProcessing.put(logEntry.getIdentifier(), logEntry);
        RunnableFuture<LogEntry> processingRunnable = new FutureTask<>(() -> {
            entriesPendingProcessing.remove(logEntry.getIdentifier());
            LogEntry result = processEntry(logEntry);

            if(result == null) {
                entryProcessingFutures.remove(logEntry.getIdentifier());
                return null; //Ignored entry. Skip it.
            }else{
                //Ensure capacity and add the entry
                SwingUtilities.invokeLater(() -> {
                    logTableController.getLogTableModel().addEntry(logEntry);
                });

                if(result.getStatus() == Status.PROCESSED){
                    //If the entry was fully processed, remove it from the processing list.
                    entryProcessingFutures.remove(logEntry.getIdentifier());
                }else{
                    //We're waiting on the response, we'll use this future to know we're done later.
                }
                return result;
            }
        });
        entryProcessingFutures.put(logEntry.getIdentifier(), processingRunnable);
        entryProcessExecutor.submit(processingRunnable);
    }

    /**
     * Create a runnable to be used in an executor which will process a
     * HTTP object and store the results in the provided LogEntry object.
     * @param logEntry The LogEntry object which will store the processed results
     * @return LogEntry Stores the processed results
     */
    LogEntry processEntry(final LogEntry logEntry){
        synchronized (logEntry) {
            logEntry.process();

            //If the status has been changed
            if (logEntry.getStatus() != logEntry.getPreviousStatus()) {
                if (logEntry.getStatus() == Status.IGNORED) return null; //Don't care about entry

                //Check against color filters
                HashMap<UUID, ColorFilter> colorFilters = preferences.getSetting(PREF_COLOR_FILTERS);
                for (ColorFilter colorFilter : colorFilters.values()) {
                    logEntry.testColorFilter(colorFilter, true);
                }
            }
        }
        return logEntry;
    }

    private RunnableFuture<LogEntry> createEntryUpdateRunnable(final Future<LogEntry> processingFuture,
                                                              final IHttpRequestResponse requestResponse,
                                                              final Date arrivalTime){
        return new FutureTask<>(() -> {
            //Block until initial processing is complete.
            LogEntry logEntry = processingFuture.get();
            if (logEntry == null) {
                return null; //Request to an ignored host. Stop processing.
            }
            logEntry.addResponse(requestResponse, arrivalTime);
            processEntry(logEntry);

            if (logEntry.getStatus() == Status.PROCESSED) {
                //If the entry was fully processed, remove it from the processing list.
                entryProcessingFutures.remove(logEntry.getIdentifier());
            }

            updateExistingEntry(logEntry);

            return logEntry;
        });
    }

    public EntryImportWorker.Builder createEntryImportBuilder(){
        return new EntryImportWorker.Builder(this);
    }

    public void importProxyHistory(){
        //TODO Fix time bug for imported results. Multithreading means results will likely end up mixed.
        //TODO Remove to more suitable UI class and show dialog

        //Build list of entries to import
        IHttpRequestResponse[] proxyHistory = LoggerPlusPlus.callbacks.getProxyHistory();
        int maxEntries = preferences.getSetting(PREF_MAXIMUM_ENTRIES);
        int startIndex = Math.max(proxyHistory.length - maxEntries, 0);
        List<IHttpRequestResponse> entriesToImport = Arrays.asList(proxyHistory).subList(startIndex, proxyHistory.length);

        //Build and start import worker
        EntryImportWorker importWorker = new EntryImportWorker.Builder(this)
                .setOriginatingTool(IBurpExtenderCallbacks.TOOL_PROXY)
                .setEntries(entriesToImport).build();

        importWorker.execute();
    }

    private boolean isValidTool(int toolFlag){
        return ((Boolean) preferences.getSetting(PREF_LOG_GLOBAL) ||
                ((Boolean) preferences.getSetting(PREF_LOG_PROXY) && toolFlag== IBurpExtenderCallbacks.TOOL_PROXY) ||
                ((Boolean) preferences.getSetting(PREF_LOG_INTRUDER) && toolFlag== IBurpExtenderCallbacks.TOOL_INTRUDER) ||
                ((Boolean) preferences.getSetting(PREF_LOG_REPEATER) && toolFlag== IBurpExtenderCallbacks.TOOL_REPEATER) ||
                ((Boolean) preferences.getSetting(PREF_LOG_SCANNER) && toolFlag== IBurpExtenderCallbacks.TOOL_SCANNER) ||
                ((Boolean) preferences.getSetting(PREF_LOG_SEQUENCER) && toolFlag== IBurpExtenderCallbacks.TOOL_SEQUENCER) ||
                ((Boolean) preferences.getSetting(PREF_LOG_SPIDER) && toolFlag== IBurpExtenderCallbacks.TOOL_SPIDER) ||
                ((Boolean) preferences.getSetting(PREF_LOG_EXTENDER) && toolFlag== IBurpExtenderCallbacks.TOOL_EXTENDER) ||
                ((Boolean) preferences.getSetting(PREF_LOG_TARGET_TAB) && toolFlag== IBurpExtenderCallbacks.TOOL_TARGET));
    }

    public void shutdown(){
        this.cleanupExecutor.shutdownNow();
        this.entryProcessExecutor.shutdownNow();
        this.entryImportExecutor.shutdownNow();
    }

    void addProcessedEntry(LogEntry logEntry){
        SwingUtilities.invokeLater(() -> {
            logTableController.getLogTableModel().addEntry(logEntry);
        });
    }

    void updateExistingEntry(LogEntry logEntry){
        SwingUtilities.invokeLater(() -> {
            logTableController.getLogTableModel().updateEntry(logEntry);
        });
    }

    PausableThreadPoolExecutor getEntryImportExecutor() {
        return entryImportExecutor;
    }

    public PausableThreadPoolExecutor getEntryProcessExecutor() {
        return entryProcessExecutor;
    }


    /*************************
     *
     * Private worker implementations
     *
     *************************/

    private class AbandonedRequestCleanupRunnable implements Runnable {

        @Override
        public void run() {
            long timeNow = new Date().getTime();
            synchronized (entryProcessingFutures){
                try {
                    HashSet<UUID> removedUUIDs = new HashSet<>();
                    Iterator<Map.Entry<UUID, Future<LogEntry>>> iter
                            = entryProcessingFutures.entrySet().iterator();

                    while (iter.hasNext()) {
                        Map.Entry<UUID, Future<LogEntry>> abandonedEntry = iter.next();
                        if(abandonedEntry.getValue().isDone()){
                            LogEntry logEntry = abandonedEntry.getValue().get();
                            if(logEntry.requestDateTime == null){
                                //Should never be the case.
                                //Entries should always have request times unless they are imported,
                                //In which case they will never be awaiting a response so never in this list.
                                continue;
                            }
                            long entryTime = logEntry.requestDateTime.getTime();
                            long responseTimeout = 1000 * ((Integer) preferences.getSetting(PREF_RESPONSE_TIMEOUT)).longValue();
                            if (timeNow - entryTime > responseTimeout) {
                                iter.remove();
                                LogManagerHelper.extractAndRemoveUUIDFromRequestResponseComment(instanceIdentifier, logEntry.requestResponse);
                                logEntry.requestResponse.setComment("Timed Out " + logEntry.requestResponse.getComment());
                                removedUUIDs.add(abandonedEntry.getKey());
                            }
                        }
                    }

                    //Clean the removed UUIDs from the proxy reference map also.
                    Iterator<Map.Entry<Integer, UUID>> proxyMapIter = proxyIdToUUIDMap.entrySet().iterator();
                    while (proxyMapIter.hasNext()) {
                        Map.Entry<Integer, UUID> entry = proxyMapIter.next();
                        if (removedUUIDs.contains(entry.getValue())) {
                            iter.remove();
                        }
                    }

                    if (removedUUIDs.size() > 0) {
                        loggerPlusPlus.getLoggingController().logOutput("Cleaned Up " + removedUUIDs.size()
                                + " proxy requests without a response after the specified timeout.");
                    }
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        }
    }
}